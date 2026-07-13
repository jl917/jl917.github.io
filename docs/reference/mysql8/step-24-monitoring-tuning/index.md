# Step 24 — 모니터링과 튜닝

> **학습 목표**
> - 슬로우 쿼리 로그와 `performance_schema`/`sys` 스키마로 **느린 쿼리를 찾아낸다**
> - 버퍼풀 히트율, 연결 수, 임시테이블 등 핵심 상태변수를 읽고 해석한다
> - `innodb_buffer_pool_size` 등 핵심 설정의 의미와 튜닝 방향을 안다
> - 인덱스 하나로 실제 쿼리를 **131ms → 0.008ms** 로 줄여본다
> - CPU 100% / 커넥션 폭증 / 특정 API 지연 상황의 **트러블슈팅 플레이북**을 익힌다
>
> **선행 스텝**: [Step 15 — 인덱스](../step-15-indexes/index.md), [Step 16 — EXPLAIN](../step-16-explain-optimizer/index.md)
> **예상 소요**: 75분

> 이 스텝은 **관찰**이 중심입니다. GLOBAL 설정은 **읽기만** 합니다(공용 서버라 변경 금지).
> 실행: `mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop`

---

## 24-1. 슬로우 쿼리 로그 (이미 켜져 있음)

이 코스의 MySQL 은 `long_query_time=0.5` 로 슬로우 로그가 켜져 있습니다.

```sql
SHOW VARIABLES LIKE 'slow_query_log';
SHOW VARIABLES LIKE 'long_query_time';
SHOW VARIABLES LIKE 'log_queries_not_using_indexes';
```
**결과**
```
slow_query_log                 = ON
slow_query_log_file            = /var/lib/mysql/slow.log
long_query_time                = 0.500000     ← 0.5초 넘으면 기록
log_queries_not_using_indexes  = ON           ← 인덱스 안 쓴 쿼리도 기록
```

슬로우 로그의 실제 항목(느린 풀스캔 하나):
```
# Query_time: 0.144426  Lock_time: 0.000003 Rows_sent: 1  Rows_examined: 1000000
SET timestamp=1783907579;
SELECT COUNT(*) FROM access_logs WHERE user_agent LIKE '%Bot%' AND duration_ms > 100;
```

읽는 법: **`Rows_examined`(읽은 행) vs `Rows_sent`(내보낸 행)** 의 격차가 크면 비효율입니다.
위는 100만 행을 뒤져서 1행을 냈습니다 → 인덱스가 필요하다는 신호.

> 💡 **실무 팁 — `mysqldumpslow`**
> 슬로우 로그를 쿼리 패턴별로 집계하는 CLI 도구입니다(서버 패키지에 포함, 이 컨테이너 이미지에는 미포함).
> ```
> mysqldumpslow -s t -t 10 /var/lib/mysql/slow.log   # 총 시간(-s t) 상위 10개
> mysqldumpslow -s c -t 10 /var/lib/mysql/slow.log   # 호출 횟수(-s c) 상위
> ```
> 최신 실무에서는 아래 `performance_schema` 다이제스트가 같은 일을 **더 정확히**(로그 파일 없이 실시간으로) 해줍니다.

---

## 24-2. performance_schema — 상위 쿼리 뽑기

`events_statements_summary_by_digest` 는 **"같은 모양의 쿼리"(리터럴을 `?`로 치환한 다이제스트)를 자동 집계**합니다.
슬로우 로그와 달리 **모든 쿼리**를 대상으로 하고, 총합/평균/최대를 실시간으로 봅니다.

```sql
SELECT
  SCHEMA_NAME,
  LEFT(DIGEST_TEXT, 60)         AS digest,
  COUNT_STAR                    AS calls,
  ROUND(SUM_TIMER_WAIT/1e12, 3) AS total_s,     -- 피코초 → 초
  ROUND(AVG_TIMER_WAIT/1e9,  2) AS avg_ms,       -- 피코초 → 밀리초
  SUM_ROWS_EXAMINED             AS rows_examined
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'shop'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 5;
```
**결과**
```
+-------------+----------------------------------------------------+-------+---------+----------+---------------+
| SCHEMA_NAME | digest                                             | calls | total_s | avg_ms   | rows_examined |
+-------------+----------------------------------------------------+-------+---------+----------+---------------+
| shop        | SELECT `a`.`path`, COUNT(*) FROM `access_logs` ...  |     1 | 135.547 | 135547.1 |     644453927 |
| shop        | ALTER TABLE `access_logs` DROP INDEX `idx_customer`|     1 |  96.511 | 96511.35 |             0 |
| shop        | DO `SLEEP`(?)                                       |    23 |  47.051 |  2045.68 |            23 |
| shop        | INSERT INTO `s21_access_logs` (`customer_id`, ...   |     5 |  20.423 |  4084.65 |       5000000 |
+-------------+----------------------------------------------------+-------+---------+----------+---------------+
```

맨 위 쿼리가 **6억 4천만 행**을 읽고 135초를 썼습니다(잘못 짠 셀프조인). **이것부터 잡아야** 합니다.

> ⚠️ **함정** — `total_s`(총합)와 `avg_ms`(평균)를 같이 보세요.
> "평균은 빠른데 총합이 1위"인 쿼리(초당 수천 번 호출)가 **평균이 느린 쿼리보다 서버를 더 괴롭히는** 경우가 흔합니다.
> `calls × avg` = 총 부하. 튜닝 우선순위는 **총합** 기준입니다.

---

## 24-3. sys 스키마 — 사람이 읽기 좋은 뷰

`sys` 는 `performance_schema` 를 **가공해 읽기 좋게** 만든 뷰 모음입니다(피코초 → `us`/`ms`, 바이트 → `MiB`).

### 느린 문장 분석 — `sys.statement_analysis`
```sql
SELECT query, db, exec_count, total_latency, rows_examined_avg, rows_sent_avg
FROM sys.statement_analysis WHERE db='shop' ORDER BY total_latency DESC LIMIT 5;
```

### 인덱스 사용 통계 — `sys.schema_index_statistics`
```sql
SELECT table_name, index_name, rows_selected, rows_inserted
FROM sys.schema_index_statistics WHERE table_schema='shop' ORDER BY rows_selected DESC LIMIT 6;
```
**결과**
```
+---------------------+---------------------+---------------+---------------+
| table_name          | index_name          | rows_selected | rows_inserted |
+---------------------+---------------------+---------------+---------------+
| s21_access_logs     | idx_s21_logged_at   |       2538209 |             0 |
| s21_access_logs_np  | idx_np_logged_at    |       1089381 |             0 |
| tally               | PRIMARY             |        137625 |             0 |
| orders              | idx_orders_customer |         62274 |             0 |
+---------------------+---------------------+---------------+---------------+
```

### 안 쓰이는 인덱스 — `sys.schema_unused_indexes`
```sql
SELECT * FROM sys.schema_unused_indexes WHERE object_schema='shop';
```
서버 기동 후 **한 번도 안 읽힌** 인덱스 목록입니다. 인덱스는 조회를 돕지만 **쓰기마다 갱신 비용**이 듭니다. 안 쓰이면 지우는 게 이득. (단, 관측 기간이 짧으면 "가끔 쓰는" 인덱스가 오탐될 수 있으니 며칠 관찰 후 판단)

### 테이블별 버퍼풀 점유 — `sys.innodb_buffer_stats_by_table`
```sql
SELECT object_name, allocated, data, pages FROM sys.innodb_buffer_stats_by_table
WHERE object_schema='shop' ORDER BY pages DESC LIMIT 6;
```
**결과**
```
+-------------+------------+-----------+-------+
| object_name | allocated  | data      | pages |
+-------------+------------+-----------+-------+
| access_logs | 63.11 MiB  | 58.00 MiB |  4039 |
| order_items | 144.00 KiB | 75.12 KiB |     9 |
| tally       | 96.00 KiB  | 65.56 KiB |     6 |
+-------------+------------+-----------+-------+
```
100만 행 `access_logs` 가 버퍼풀의 대부분을 차지합니다. **메모리를 누가 먹는지**가 한눈에 보입니다.

---

## 24-4. 버퍼풀 히트율

InnoDB 는 데이터를 **버퍼풀(메모리)** 에 캐시합니다. 요청한 페이지가 메모리에 있으면 "히트", 디스크에서 읽어야 하면 "미스".

```
히트율 = 1 - (Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests)
              └ 디스크에서 실제로 읽음        └ 버퍼풀에 요청한 총 횟수
```

```sql
SELECT
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests') AS read_requests,
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads')          AS disk_reads,
  ROUND(100*(1 - (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads')
   / (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests')),4) AS hit_ratio_pct;
```
**결과**
```
+---------------+------------+---------------+
| read_requests | disk_reads | hit_ratio_pct |
+---------------+------------+---------------+
| 128699512     | 28133      |       99.9781 |
+---------------+------------+---------------+
```

99.98% — 대부분 메모리에서 처리됩니다. **95% 밑으로 떨어지면** 버퍼풀이 작업량 대비 작다는 신호이고, `innodb_buffer_pool_size` 를 늘릴 후보입니다.

`SHOW ENGINE INNODB STATUS` 의 `BUFFER POOL AND MEMORY` 섹션에서도 실시간 히트율을 봅니다:
```
Buffer pool hit rate 1000 / 1000, young-making rate 0 / 1000 not 0 / 1000
Pages made young 69057, not young 10847716
```
`1000 / 1000` = 최근 구간 100% 히트.

> ⚠️ **함정** — 히트율은 **부팅 후 누적값**입니다. 방금 대형 배치가 버퍼풀을 훑고 지나갔어도 누적 히트율은 여전히 높게 보일 수 있습니다. 순간 진단은 `SHOW ENGINE INNODB STATUS` 의 `... / 1000` 쪽(최근 구간)을 보세요.

---

## 24-5. 핵심 상태변수 & 설정

```sql
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Threads_connected', 'Threads_running', 'Max_used_connections',
  'Aborted_connects', 'Created_tmp_disk_tables', 'Created_tmp_tables');
```
**결과**
```
+-------------------------+-------+
| Variable_name           | Value |
+-------------------------+-------+
| Aborted_connects        | 6     |
| Created_tmp_disk_tables | 7     |
| Created_tmp_tables      | 849   |
| Max_used_connections    | 3     |
| Threads_connected       | 1     |
| Threads_running         | 2     |
+-------------------------+-------+
```

| 변수 | 해석 |
|---|---|
| `Threads_connected` | 현재 열린 연결 수. `max_connections` 에 근접하면 위험 |
| `Threads_running` | **지금 실제로 CPU 를 쓰며 실행 중인** 수. 이게 급증 = 쿼리 적체(CPU 병목 신호) |
| `Max_used_connections` | 부팅 후 최대 동시 연결. `max_connections` 대비 여유 확인 |
| `Created_tmp_disk_tables` / `Created_tmp_tables` | 디스크 임시테이블 비율이 높으면 `tmp_table_size` 부족 또는 쿼리 개선 필요 |

```sql
SHOW VARIABLES WHERE Variable_name IN (
  'innodb_buffer_pool_size','innodb_redo_log_capacity',
  'innodb_flush_log_at_trx_commit','max_connections','tmp_table_size','table_open_cache');
```
**결과**
```
+--------------------------------+-----------+
| Variable_name                  | Value     |
+--------------------------------+-----------+
| innodb_buffer_pool_size        | 268435456 |   (256 MiB — 학습용이라 작음)
| innodb_flush_log_at_trx_commit | 1         |
| innodb_redo_log_capacity       | 104857600 |   (100 MiB)
| max_connections                | 151       |
| tmp_table_size                 | 16777216  |   (16 MiB)
| table_open_cache               | 4000      |
+--------------------------------+-----------+
```

### 핵심 설정 튜닝 가이드

| 설정 | 의미 | 운영 권장 |
|---|---|---|
| **`innodb_buffer_pool_size`** | InnoDB 데이터/인덱스 캐시. **가장 중요** | 물리 메모리의 **50~70%** |
| **`innodb_redo_log_capacity`** (8.0.30+) | redo 로그 총량. 크면 쓰기 폭주 시 체크포인트 여유↑ (구 `innodb_log_file_size` 를 대체) | 쓰기 많으면 수 GB |
| **`innodb_flush_log_at_trx_commit`** | `1`=커밋마다 디스크 flush(완전 내구성, 기본) / `2`=OS 캐시까지만 / `0`=1초마다 | 금전 데이터는 `1`. 로그성은 `2` 고려 |
| **`max_connections`** | 최대 동시 연결 | 커넥션 풀 크기 × 앱 인스턴스 수 + 여유 |
| **`tmp_table_size`** / `max_heap_table_size` | 인메모리 임시테이블 한도(둘 중 작은 값 적용) | 넘으면 디스크 임시테이블로 전락 |

> ⚠️ **함정** — `innodb_buffer_pool_size` 를 **물리 메모리보다 크게** 잡으면 OS 가 스와핑을 시작해 오히려 급격히 느려집니다. "크면 클수록 좋다"가 아니라 **"물리 메모리 안에서 최대한"** 입니다.

> 💡 **실무 팁 — 커넥션 풀**
> `max_connections` 를 무작정 키우는 것은 답이 아닙니다. 연결 하나하나가 메모리를 먹고, 수천 개가 동시에 `Threads_running` 이 되면 컨텍스트 스위칭으로 서버가 마비됩니다.
> **애플리케이션 쪽 커넥션 풀**(HikariCP 등)로 연결 수를 **묶어서 재사용**하는 것이 정석입니다. DB 는 소수의 연결을 빠르게 처리하는 게 가장 효율적입니다.

---

## 24-6. 【실측】 인덱스 하나로 131ms → 0.008ms

```sql
CREATE TABLE s24_logs LIKE access_logs;
INSERT INTO s24_logs SELECT * FROM access_logs;   -- 100만 행
ANALYZE TABLE s24_logs;

-- (before) 인덱스 없음
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE customer_id = 7 AND status_code = 500;
```
**결과 (before)**
```
-> Aggregate: count(0)  (cost=101629 rows=1) (actual time=130..130 rows=1 loops=1)
    -> Filter: ((status_code = 500) and (customer_id = 7))  (actual time=130..130 rows=0 loops=1)
        -> Table scan on s24_logs  (cost=100633 rows=996151) (actual time=0.037..101 rows=1e+6 loops=1)
```

```sql
ALTER TABLE s24_logs ADD INDEX idx_cust_status (customer_id, status_code);

-- (after)
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE customer_id = 7 AND status_code = 500;
```
**결과 (after)**
```
-> Aggregate: count(0)  (cost=1.2 rows=1) (actual time=0.0072..0.0073 rows=1 loops=1)
    -> Covering index lookup on s24_logs using idx_cust_status (customer_id=7, status_code=500)
       (cost=1.1 rows=1) (actual time=0.0062..0.0062 rows=0 loops=1)
```

**130ms → 0.007ms.** 100만 행 풀스캔이 **커버링 인덱스 조회**로 바뀌었습니다.
등치 조건 두 개(`customer_id`, `status_code`)를 그대로 복합 인덱스로 만들었고, `COUNT(*)` 는 인덱스만으로 답이 나오므로 테이블을 아예 안 읽습니다(`Covering index`).

---

## 24-7. 실전 트러블슈팅 플레이북

### 상황 A — CPU 100%
```
1) SHOW PROCESSLIST (또는 sys.processlist / performance_schema.threads)
   → command='Query' 이고 time 이 큰 세션을 찾는다. 무슨 쿼리가 도는가?
2) SELECT * FROM sys.statement_analysis ORDER BY total_latency DESC LIMIT 10;
   → 지금 서버를 갈아먹는 쿼리 유형을 확인
3) Threads_running 확인 → CPU 코어 수보다 훨씬 크면 쿼리 적체
4) 범인 쿼리를 EXPLAIN → 풀스캔/잘못된 조인이면 인덱스 추가 or 쿼리 수정
5) 급하면 KILL QUERY <id> 로 폭주 쿼리 중단 (24-6 같은 6억 행 셀프조인이 전형)
```

### 상황 B — 커넥션 폭증 ("Too many connections")
```
1) SHOW STATUS LIKE 'Threads_connected';  vs  max_connections
2) SHOW PROCESSLIST → 'Sleep' 상태 연결이 수백 개면?
   → 애플리케이션이 연결을 안 닫는다(커넥션 누수) 또는 풀 설정 과다
3) Threads_running 도 같이 본다:
   - connected 는 높은데 running 은 낮다 → 유휴 연결 누적(앱/풀 문제)
   - running 도 높다 → 느린 쿼리가 연결을 오래 잡고 있음(→ 상황 A 로)
4) 근본 해결: 앱 커넥션 풀 크기 조정 + 느린 쿼리 튜닝.
   max_connections 를 올리는 건 임시방편(메모리만 더 먹는다)
```

### 상황 C — 특정 API 만 느리다
```
1) 그 API 가 실행하는 쿼리를 특정한다(코드/APM/slow log)
2) performance_schema.events_statements_summary_by_digest 에서
   해당 다이제스트의 avg_ms, rows_examined 를 본다
3) rows_examined ≫ rows_sent 면 인덱스 문제 → EXPLAIN 으로 확인
4) EXPLAIN 에서 type=ALL(풀스캔), Using filesort, Using temporary 를 찾는다
   - 풀스캔 → 적절한 인덱스 추가 (24-6 처럼)
   - filesort/temporary → ORDER BY/GROUP BY 컬럼을 커버하는 인덱스 검토
5) Created_tmp_disk_tables 가 늘고 있으면 tmp_table_size 부족도 의심
```

> 💡 **실무 팁 — 진단의 3대 출발점**
> ① `SHOW PROCESSLIST`(지금 뭐가 도나) ② `sys.statement_analysis`(누적으로 뭐가 무겁나) ③ `EXPLAIN ANALYZE`(그 쿼리가 왜 느린가).
> 이 순서로 좁혀 들어가면 대부분의 성능 문제는 잡힙니다.

> ⚠️ **함정** — 지표 하나만 보고 결론 내지 마세요. "CPU 100%" 는 원인이 아니라 **증상**입니다.
> 느린 쿼리 때문일 수도, 커넥션 폭증 때문일 수도, 통계 부정확으로 옵티마이저가 나쁜 계획을 골라서일 수도 있습니다.
> 항상 **"어떤 쿼리가"** 까지 내려가야 진짜 원인입니다.

---

## 정리

| 도구 | 용도 |
|---|---|
| 슬로우 쿼리 로그 | 임계값 초과 쿼리를 파일로. `Rows_examined ≫ Rows_sent` 를 본다 |
| `events_statements_summary_by_digest` | 모든 쿼리를 다이제스트별 실시간 집계. **총합 기준** 우선순위 |
| `sys.statement_analysis` | 위를 사람이 읽기 좋게 가공 |
| `sys.schema_unused_indexes` | 지워도 되는 인덱스 후보 |
| `sys.innodb_buffer_stats_by_table` | 메모리를 먹는 테이블 |
| 버퍼풀 히트율 | `1 - reads/read_requests`. 95% 밑이면 버퍼풀 확대 검토 |
| `SHOW ENGINE INNODB STATUS` | 버퍼풀/트랜잭션/데드락 스냅샷 |
| 핵심 설정 | `innodb_buffer_pool_size`(메모리 50~70%), `flush_log_at_trx_commit`, `max_connections`, `tmp_table_size` |
| 튜닝 1순위 | 대개 **인덱스**. 풀스캔을 커버링 인덱스로 (131ms → 0.008ms) |
| 플레이북 | PROCESSLIST → statement_analysis → EXPLAIN ANALYZE |

---

## 연습문제

`exercise.sql` 을 열어 풀어 보세요. 정답은 `solution.sql`.

1. `events_statements_summary_by_digest` 에서 **평균 시간(avg_ms) 상위 5개**를 뽑아라. 총합 상위와 어떻게 다른가?
2. `s24_logs`(100만 행)를 만들고, `WHERE path='/orders' AND method='POST'` 쿼리를 인덱스 전/후로 `EXPLAIN ANALYZE` 비교하라.
3. 현재 버퍼풀 히트율을 계산하고, 어떤 테이블이 버퍼풀을 가장 많이 쓰는지 찾아라.
4. `SHOW GLOBAL STATUS` 에서 디스크 임시테이블 비율(`Created_tmp_disk_tables / Created_tmp_tables`)을 계산하라.
5. `sys.schema_unused_indexes` 로 `s24_logs` 에 안 쓰이는 인덱스를 만들어 두고 조회로 확인하라(인덱스를 하나 더 만들되 쓰지 않는 쿼리만 실행).
6. (플레이북) "특정 API 가 느리다"는 신고를 받았다. 어떤 순서로 무엇을 조회할지 SQL 로 시나리오를 작성하라.

---

## 다음 단계

→ [Step 25 — 최종 프로젝트](../step-25-final-project/index.md)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 진행합니다. 먼저 `practice.sql` 을 통째로 실행해 강의 본문의 관찰 쿼리와 인덱스 실측을 그대로 재현해 보고, 그다음 `exercise.sql` 의 TODO 를 직접 채워 풀어 본 뒤, 마지막으로 `solution.sql` 로 답을 맞춰 봅니다. 세 파일 모두 `mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < 파일명` 으로 실행하며, 실습 테이블은 모두 `s24_` 접두사를 써서 공용 테이블과 섞이지 않게 합니다.

### practice.sql

강의 본문의 흐름을 그대로 따라가는 **관찰용 스크립트**입니다.

> 파일 안의 절 번호는 본문과 **한 칸씩 어긋납니다.** 본문 24-5(상태변수 + 설정)를 파일에서는 24-5(`SHOW GLOBAL STATUS`)와 24-6(`SHOW VARIABLES`)으로 나눠 놓았기 때문에, 본문 24-6(인덱스 실측)이 파일에서는 **24-7** 이 됩니다. 아래 설명의 번호는 모두 **파일 기준**입니다.

`SHOW VARIABLES LIKE 'slow_query_log'` 로 슬로우 로그 상태를 확인하는 24-1 부터, `events_statements_summary_by_digest` 를 `ORDER BY SUM_TIMER_WAIT DESC` 로 정렬해 "서버를 가장 괴롭히는 쿼리"를 뽑는 24-2, `sys.statement_analysis` / `sys.schema_index_statistics` / `sys.schema_unused_indexes` / `sys.innodb_buffer_stats_by_table` 네 개 뷰를 차례로 조회하는 24-3 까지가 앞부분입니다. 24-2 의 SELECT 목록에는 본문에 없던 `SUM_ROWS_SENT` 가 하나 더 붙어 있어서, `rows_examined` 와 `rows_sent` 의 격차(= 인덱스가 필요하다는 신호)를 한 화면에서 바로 비교할 수 있습니다.

- 24-4 의 히트율 계산은 `1 - Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests` 를 스칼라 서브쿼리 세 개로 조립해 `ROUND(..., 4)` 로 소수점 넷째 자리까지 뽑습니다. 본문의 99.9781% 가 이 쿼리의 결과입니다.
- 24-5(`SHOW GLOBAL STATUS`)와 24-6(`SHOW VARIABLES`)은 **읽기 전용**입니다. 주석에도 명시돼 있듯 이 코스의 MySQL 은 공용 서버라 `SET GLOBAL` 로 값을 바꾸면 안 됩니다.
- 24-7 이 이 파일의 하이라이트입니다(본문 24-6 에 해당). `CREATE TABLE s24_logs LIKE access_logs` + `INSERT INTO s24_logs SELECT * FROM access_logs` 로 100만 행 복사본을 만들고 `ANALYZE TABLE` 로 통계를 갱신한 뒤, 인덱스 없이 `EXPLAIN ANALYZE SELECT COUNT(*) ... WHERE customer_id = 7 AND status_code = 500` → `ALTER TABLE s24_logs ADD INDEX idx_cust_status (customer_id, status_code)` → 같은 쿼리 재실행 순으로 **before/after 를 한 번에 비교**합니다.
- 주의: 100만 행 `INSERT ... SELECT` 는 수십 초가 걸릴 수 있습니다. 24-7 의 첫 줄이 `DROP TABLE IF EXISTS s24_logs` 라서 스크립트를 여러 번 재실행해도 안전합니다. 마지막 24-8 의 `SHOW ENGINE INNODB STATUS\G` 와 뒷정리 `DROP TABLE` 은 **주석 처리**되어 있으니, 필요하면 mysql 클라이언트에서 직접 실행하세요(`\G` 는 스크립트 리다이렉트로는 잘 안 맞습니다). 즉 이 스크립트는 `s24_logs` 를 **남긴 채** 끝나므로, 이어서 `exercise.sql` 을 풀 수 있습니다.

```sql file="./practice.sql"
```

### exercise.sql

본문 「연습문제」 6문항의 **빈칸 채우기 템플릿**입니다. 대부분이 `-- TODO` 주석으로 남겨져 있고, 일부는 뼈대만 주어집니다.

- 문제 1 은 `ORDER BY ??? DESC` 부분이 비어 있습니다. 총합(`SUM_TIMER_WAIT`)이 아니라 **평균(`AVG_TIMER_WAIT`)** 으로 정렬해야 하며, 두 결과가 왜 달라지는지를 주석으로 답하는 것이 진짜 문제입니다.
- 문제 2 는 `DROP/CREATE/INSERT/ANALYZE` 4줄이 이미 실행 가능한 상태로 주어져 있고, before `EXPLAIN ANALYZE` → `CREATE INDEX` → after `EXPLAIN ANALYZE` 세 자리만 비어 있습니다. 조건이 `path='/orders' AND method='POST'` 로 바뀌었을 뿐 24-6 과 구조가 같습니다.
- 문제 5 의 힌트("인덱스를 만들되, 그 인덱스를 타는 쿼리는 실행하지 않는다")가 `sys.schema_unused_indexes` 의 동작 원리를 그대로 설명합니다. 인덱스를 만들어 두고 **조회하지 않아야** 미사용 목록에 뜹니다.
- 이 파일도 문제 2 에서 `s24_logs` 를 새로 만들므로 `practice.sql` 을 먼저 돌렸어도 충돌하지 않습니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 6문항의 정답입니다. 단순한 정답 나열이 아니라 **왜 그런지**가 주석에 정리돼 있으니, 답을 맞춘 뒤 주석을 꼭 읽어 보세요.

- 정답 1 의 주석이 24-2 의 함정을 다시 짚습니다: 평균 상위는 "한 번 돌면 오래 걸리는" 배치/리포트성 쿼리, 총합 상위는 `calls × avg` 라 자주 불리는 쿼리가 올라옵니다. **튜닝 우선순위는 총합**이 원칙입니다.
- 정답 2 는 `CREATE INDEX idx_path_method ON s24_logs (path, method)` 로 등치 조건 두 개를 복합 인덱스로 묶습니다. 100만 행 Table scan 이 index lookup 으로 바뀌는 것을 `EXPLAIN ANALYZE` 출력에서 확인합니다.
- 정답 4 의 `NULLIF(..., 0)` 이 포인트입니다. `Created_tmp_tables` 가 0 일 때 0으로 나누기를 막아 NULL 을 반환하게 합니다. 결과가 10% 를 넘으면 `tmp_table_size` 부족을 의심합니다.
- 정답 5 는 `idx_unused_duration` 을 만들고 **일부러 쓰지 않은 뒤** `sys.schema_unused_indexes` 에서 확인합니다. 주석대로 `idx_path_method` 는 정답 2 에서 조회에 쓰였으므로 목록에 나타나지 않습니다 — 이 대비가 학습 포인트입니다.
- 정답 6 은 24-7 플레이북의 SQL 버전입니다. `information_schema.processlist`(지금 뭐가 도나) → `sys.statement_analysis`(누적으로 뭐가 무겁나) → `EXPLAIN ANALYZE`(왜 느린가) → 임시테이블 상태변수 순서로 좁혀 들어갑니다.
- 파일 마지막 줄이 `DROP TABLE IF EXISTS s24_logs;` 입니다. 이 스크립트를 끝까지 실행하면 실습 테이블이 **삭제된다**는 점을 기억하세요(공용 테이블은 건드리지 않습니다).

```sql file="./solution.sql"
```
