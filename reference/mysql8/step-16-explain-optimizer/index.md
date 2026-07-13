# Step 16 — EXPLAIN 과 옵티마이저

> **학습 목표**
> - EXPLAIN 의 모든 컬럼(특히 `type`, `key`, `rows`, `filtered`, `Extra`)을 정확히 읽는다
> - `type` 등급(system~ALL)을 좋고 나쁨으로 서열화한다
> - EXPLAIN ANALYZE(8.0.18+)로 추정이 아닌 **실측** 실행 계획을 읽는다
> - FORMAT=TREE / JSON 으로 비용과 실행 흐름을 본다
> - 옵티마이저 힌트·인덱스 힌트·옵티마이저 스위치로 계획을 제어한다
> - ANALYZE TABLE 과 히스토그램(8.0)으로 옵티마이저의 추정을 개선한다
> - "느린 쿼리 → EXPLAIN → 인덱스 설계 → 재측정"의 실전 튜닝 절차를 익힌다
>
> **선행 스텝**: Step 15 — 인덱스
> **예상 소요**: 90분

---

## 16-1. EXPLAIN 이란

`EXPLAIN` 은 쿼리를 **실행하지 않고**, 옵티마이저가 세운 **실행 계획**을 보여줍니다. "이 쿼리를 어떤 순서로, 어떤 인덱스로, 몇 행쯤 읽어서 처리할 예정"인지의 설계도입니다.

```sql
EXPLAIN SELECT * FROM orders WHERE customer_id = 1;
```

**결과**
```
+----+-------------+--------+------+---------------------+---------------------+---------+-------+------+----------+-------+
| id | select_type | table  | type | possible_keys       | key                 | key_len | ref   | rows | filtered | Extra |
+----+-------------+--------+------+---------------------+---------------------+---------+-------+------+----------+-------+
|  1 | SIMPLE      | orders | ref  | idx_orders_customer | idx_orders_customer | 4       | const |   20 |   100.00 | NULL  |
+----+-------------+--------+------+---------------------+---------------------+---------+-------+------+----------+-------+
```

각 컬럼의 의미:

| 컬럼 | 의미 |
|---|---|
| `id` | SELECT 식별자. JOIN 은 같은 id, 서브쿼리는 다른 id |
| `select_type` | SIMPLE / PRIMARY / SUBQUERY / DERIVED / UNION 등 |
| `table` | 접근 대상 테이블(또는 `<derived2>` 같은 파생) |
| `partitions` | 접근한 파티션(파티션 테이블일 때) |
| **`type`** | **접근 방식. 가장 중요.** (아래 16-2) |
| `possible_keys` | 쓸 수 있었던 인덱스 후보 |
| **`key`** | **실제로 고른 인덱스** (NULL 이면 인덱스 안 씀) |
| `key_len` | 사용한 인덱스의 바이트 길이(복합 인덱스 중 몇 컬럼까지 썼는지 추정) |
| `ref` | 인덱스와 비교된 값(`const` = 상수, 컬럼명 = 조인 상대) |
| **`rows`** | **읽을 것으로 추정한 행 수** (추정치!) |
| `filtered` | 그 rows 중 조건을 통과할 비율(%) 추정 |
| **`Extra`** | **추가 동작.** Using index / filesort / temporary 등 (아래 16-3) |

---

## 16-2. `type` — 접근 방식 등급 (제일 먼저 보는 것)

`type` 은 "테이블에 어떻게 접근하는가"이며, **성능의 핵심 지표**입니다. 좋은 순서:

```
system > const > eq_ref > ref > range > index > ALL
 좋음  ←──────────────────────────────────────→ 나쁨
```

### const — PK/UNIQUE 등치 (최고)

```sql
EXPLAIN SELECT * FROM customers WHERE customer_id = 1;
```
```
| type  | key     | ref   | rows |
| const | PRIMARY | const |    1 |    ← 딱 1행. 상수처럼 취급
```

### eq_ref — 조인에서 상대 테이블의 PK/UNIQUE 를 1:1 매칭

```sql
EXPLAIN SELECT o.order_id, c.name
FROM orders o JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_id < 10;
```
```
| table | type   | key     | ref                | rows |
| o     | range  | PRIMARY | NULL               |    9 |
| c     | eq_ref | PRIMARY | shop.o.customer_id |    1 |    ← o의 각 행마다 c를 PK로 1행씩
```

### ref — 비유니크 인덱스 등치 (실무의 주력)

```sql
EXPLAIN SELECT * FROM orders WHERE customer_id = 1;
```
```
| type | key                 | ref   | rows |
| ref  | idx_orders_customer | const |   20 |    ← 인덱스로 같은 값 여러 행
```

### range — 범위 검색

```sql
EXPLAIN SELECT * FROM orders WHERE order_id BETWEEN 1 AND 50;
```
```
| type  | key     | rows | Extra       |
| range | PRIMARY |   50 | Using where |    ← BETWEEN, >, <, IN, LIKE 'x%'
```

### index — 인덱스 풀스캔 (주의!)

```sql
EXPLAIN SELECT customer_id FROM orders;
```
```
| type  | key                 | rows | Extra       |
| index | idx_orders_customer |  600 | Using index |    ← 인덱스 전체를 훑음
```

> ⚠️ **함정 — `type: index` 는 사실상 풀스캔이다** (Step 15 에서 예고)
> "index 니까 인덱스 탔네" 는 오해입니다. **탐색(seek)이 아니라 인덱스 전체를 처음부터 끝까지 읽는** 것입니다.
> 테이블 풀스캔(`ALL`)보다 조금 나을 뿐(인덱스가 테이블보다 작고, 커버링이면 테이블 접근이 없어서). **좋은 신호가 아닙니다.**

### ALL — 테이블 풀스캔 (최악, 대용량에서)

```sql
EXPLAIN SELECT * FROM orders WHERE shipping_city = '서울';
```
```
| type | key  | rows | Extra       |
| ALL  | NULL |  600 | Using where |    ← 인덱스 없음. 전 행 스캔
```

> 💡 **작은 테이블의 ALL 은 정상이다**
> `orders` 는 600행뿐이라 옵티마이저가 **일부러 풀스캔**을 고릅니다. 인덱스로 왔다 갔다 하는 것보다 그냥 다 읽는 게 빠르기 때문입니다.
> `type: ALL` 이 문제가 되는 건 **행이 많을 때**입니다. Step 15 의 100만 행 `access_logs` 에서 ALL 은 재앙이었죠.
> **EXPLAIN 은 항상 `rows` 와 함께 해석하세요.** type 만 보고 좋다/나쁘다 하면 안 됩니다.

---

## 16-3. `Extra` — 숨은 비용이 드러나는 곳

`Extra` 에는 옵티마이저가 추가로 하는 일이 적힙니다. 여기서 성능 문제가 자주 튀어나옵니다.

| Extra | 의미 | 좋음/나쁨 |
|---|---|---|
| `Using index` | **커버링 인덱스**. 테이블 접근 없음 | 좋음 |
| `Using where` | 스토리지에서 받은 행을 서버가 추가 필터링 | 보통 |
| `Using index condition` | 인덱스 컨디션 푸시다운(ICP). 필터를 인덱스 단계로 밀어냄 | 좋음 |
| `Using filesort` | 정렬을 위해 **별도 정렬** 수행(메모리/디스크) | 나쁨(대량일 때) |
| `Using temporary` | **임시 테이블** 생성(GROUP BY/DISTINCT/UNION 등) | 나쁨(대량일 때) |

### Using filesort + Using temporary 가 함께 뜨는 전형적 케이스

```sql
EXPLAIN SELECT shipping_city, COUNT(*) c FROM orders GROUP BY shipping_city ORDER BY c DESC;
```
```
| type | key  | rows | Extra                           |
| ALL  | NULL |  600 | Using temporary; Using filesort |
```

`GROUP BY shipping_city` 를 처리하려 임시 테이블을 만들고(`Using temporary`), `ORDER BY c`(집계값) 로 다시 정렬합니다(`Using filesort`). 데이터가 크면 둘 다 비쌉니다.

> 💡 `Using filesort` 는 "파일로 정렬한다"는 뜻이 **아닙니다.** 인덱스 순서를 못 써서 **별도 정렬 단계**가 필요하다는 의미이고,
> 데이터가 작으면 메모리에서 끝납니다. 크면 디스크로 넘칩니다. Step 15 처럼 정렬 컬럼을 인덱스에 포함시켜 없앨 수 있습니다.

---

## 16-4. `rows` 와 `filtered` — 추정치임을 잊지 마라

`rows` 와 `filtered` 는 **옵티마이저의 추정치**입니다. 통계에 기반한 예측이지 실제 값이 아닙니다.

Step 15 의 `customer_id = 7` 을 다시 봅시다. 인덱스가 있을 때 EXPLAIN 은 `rows: 64428` 이라고 추정했지만, 실제 행은 33,333 이었습니다. **거의 2배 틀립니다.**

- `rows`: 이 단계에서 읽을 것으로 **추정**한 행 수
- `filtered`: 그 rows 중 `WHERE` 를 통과할 **추정** 비율(%)
- 최종 예상 행 = `rows × filtered / 100`

추정이 실제와 크게 어긋나면 옵티마이저가 잘못된 계획을 세웁니다. 그 추정을 바로잡는 도구가 **ANALYZE TABLE 과 히스토그램**(16-8)이고, 추정이 아닌 실측을 보는 도구가 **EXPLAIN ANALYZE**(다음 절)입니다.

---

## 16-5. EXPLAIN ANALYZE — 추정이 아닌 실측 (8.0.18+)

`EXPLAIN` 은 계획(추정)만 보여줍니다. `EXPLAIN ANALYZE` 는 **쿼리를 실제로 실행하고**, 각 단계의 **실제 시간·실제 행 수**를 함께 보여줍니다.

Step 15 의 `access_logs` 를 인덱스 **없이**:

```sql
EXPLAIN ANALYZE SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```

**결과**
```
-> Aggregate: count(0)  (cost=110594 rows=1) (actual time=120..120 rows=1 loops=1)
    -> Filter: (access_logs.customer_id = 7)  (cost=100633 rows=99615) (actual time=1.69..119 rows=33333 loops=1)
        -> Table scan on access_logs  (cost=100633 rows=996151) (actual time=1.68..90.6 rows=1e+6 loops=1)
```

읽는 법 (안쪽 → 바깥쪽 순으로 실행됨):
- `Table scan on access_logs` — 100만 행(`rows=1e+6`)을 전부 스캔. 실제 90.6ms.
- `Filter: customer_id = 7` — 추정 99615 행이지만 **실제(actual) 33333 행**. 여기서 추정 오차가 보입니다.
- `actual time=1.69..119` — **첫 행까지 1.69ms, 마지막 행까지 119ms**.

인덱스를 **만든 뒤**:

```sql
ALTER TABLE access_logs ADD INDEX idx_customer (customer_id);
EXPLAIN ANALYZE SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```

**결과**
```
-> Aggregate: count(0)  (cost=12981 rows=1) (actual time=3.28..3.28 rows=1 loops=1)
    -> Covering index lookup on access_logs using idx_customer (customer_id=7)
       (cost=6538 rows=64428) (actual time=0.272..2.49 rows=33333 loops=1)
```

`Table scan`(90.6ms) 이 `Covering index lookup`(2.49ms) 으로 바뀌었습니다. 전체도 120ms → 3.28ms.

> 💡 **`actual time=A..B` 읽는 법**: A = 이 연산이 **첫 행**을 내놓기까지 걸린 ms, B = **마지막 행**까지. `loops` = 이 연산이 반복된 횟수(조인 안쪽이면 여러 번).
> 실제 튜닝에서는 **`rows` 추정 vs `actual rows` 실측의 괴리**와, **어느 연산에서 `actual time` 이 폭증하는지**를 봅니다.

> ⚠️ **EXPLAIN ANALYZE 는 쿼리를 진짜로 실행합니다.** `SELECT` 은 안전하지만, `UPDATE`/`DELETE` 에 쓰면 **실제로 데이터가 바뀝니다.**
> 변경 쿼리를 분석하려면 `EXPLAIN`(실행 안 함)만 쓰거나, 트랜잭션으로 감싸고 롤백하세요.

---

## 16-6. FORMAT=TREE / JSON

### FORMAT=TREE — 실행 흐름을 트리로

```sql
EXPLAIN FORMAT=TREE
SELECT c.grade, COUNT(*) FROM orders o JOIN customers c ON c.customer_id=o.customer_id
GROUP BY c.grade;
```
```
-> Table scan on <temporary>
    -> Aggregate using temporary table
        -> Nested loop inner join  (cost=71 rows=600)
            -> Table scan on c  (cost=3.25 rows=30)
            -> Covering index lookup on o using idx_orders_customer (customer_id=c.customer_id)  (cost=0.324 rows=20)
```

들여쓰기가 깊을수록 먼저 실행됩니다. "customers 를 스캔하고(30행), 각 고객마다 orders 를 인덱스로 찾고, 임시 테이블에 모아 집계"라는 흐름이 보입니다. `EXPLAIN ANALYZE` 도 이 트리 형식을 씁니다.

### FORMAT=JSON — 비용까지 상세히

```sql
EXPLAIN FORMAT=JSON SELECT * FROM orders WHERE customer_id = 1;
```
```json
{
  "query_block": {
    "cost_info": { "query_cost": "5.00" },
    "table": {
      "table_name": "orders",
      "access_type": "ref",
      "key": "idx_orders_customer",
      "used_key_parts": ["customer_id"],
      "rows_examined_per_scan": 20,
      "filtered": "100.00",
      "cost_info": {
        "read_cost": "3.00", "eval_cost": "2.00", "prefix_cost": "5.00"
      }
    }
  }
}
```

`query_cost` 는 옵티마이저가 계산한 **추상 비용 단위**입니다. 절대값보다는 **같은 쿼리를 다르게 썼을 때 비용이 줄어드는지** 비교할 때 씁니다. `used_key_parts` 로 복합 인덱스 중 몇 컬럼을 실제로 썼는지도 볼 수 있습니다.

---

## 16-7. 옵티마이저 제어 — 힌트와 스위치

옵티마이저는 대개 옳지만, 통계가 낡거나 데이터가 편향되면 잘못된 인덱스를 고르기도 합니다. 그럴 때 계획을 강제할 수 있습니다.

### 인덱스 힌트 (전통적)

```sql
SELECT ... FROM t USE INDEX (idx_a) ...       -- idx_a 중에서 고르라고 권함
SELECT ... FROM t FORCE INDEX (idx_a) ...     -- idx_a 를 강하게 강제
SELECT ... FROM t IGNORE INDEX (idx_a) ...    -- idx_a 는 쓰지 말라
```

### 옵티마이저 힌트 (8.0 권장, `/*+ ... */`)

주석 형태로 SELECT 바로 뒤에 넣습니다. 인덱스 힌트보다 세밀하게 제어합니다.

```sql
EXPLAIN SELECT /*+ NO_INDEX(access_logs idx_customer) */ COUNT(*)
FROM access_logs WHERE customer_id = 7;
```
```
| type | key  | rows   | Extra       |
| ALL  | NULL | 996151 | Using where |    ← 인덱스를 못 쓰게 막으니 풀스캔으로 돌아감
```

자주 쓰는 옵티마이저 힌트:

| 힌트 | 뜻 |
|---|---|
| `/*+ INDEX(t idx) */` | 테이블 t 에 인덱스 idx 사용 |
| `/*+ NO_INDEX(t idx) */` | 인덱스 idx 사용 금지 |
| `/*+ JOIN_ORDER(t1, t2) */` | 조인 순서 고정 |
| `/*+ NO_MERGE(v) */` | 뷰/파생테이블 머지 금지 |
| `/*+ MAX_EXECUTION_TIME(1000) */` | 이 쿼리 1초 넘으면 중단 |

> ⚠️ **함정 — 힌트는 최후의 수단이다**
> 힌트로 계획을 강제하면 그 순간엔 빨라져도, 데이터가 커지거나 분포가 바뀌면 **강제된 계획이 오히려 발목**을 잡습니다.
> 옵티마이저가 잘못 고른다면, 힌트로 덮기 전에 **왜 잘못 고르는지**(대개 통계·히스토그램 문제)를 먼저 확인하세요. 힌트는 원인을 못 고칠 때만 씁니다.

### 옵티마이저 스위치

세션/전역 단위로 옵티마이저 기능을 켜고 끕니다. Step 15 에서 스킵 스캔을 껐던 그것입니다.

```sql
SELECT @@optimizer_switch;                                   -- 현재 상태
SET SESSION optimizer_switch = 'skip_scan=off';              -- 스킵 스캔 끄기
SET SESSION optimizer_switch = 'index_merge=off';            -- 인덱스 머지 끄기
```

> 💡 특정 최적화가 오히려 계획을 나쁘게 만드는지 실험할 때 유용합니다. "index_merge 를 껐더니 더 빨라지네?" 같은 진단.
> 전역 변경은 서버 전체에 영향을 주니, **세션 단위(`SET SESSION`)** 로 테스트하세요.

---

## 16-8. ANALYZE TABLE 과 히스토그램 (8.0)

옵티마이저의 `rows`/`filtered` 추정은 **통계**에 의존합니다. 통계가 낡으면 추정이 틀리고, 추정이 틀리면 계획이 나빠집니다.

### ANALYZE TABLE — 인덱스 통계 갱신

```sql
ANALYZE TABLE access_logs;
```
대량 INSERT/DELETE 후, 또는 "인덱스가 있는데 안 쓴다" 싶을 때 실행합니다. 인덱스의 카디널리티 통계를 다시 계산합니다.

### 히스토그램 — 인덱스 없는 컬럼의 값 분포

인덱스 통계는 "값이 몇 종인가(카디널리티)"만 압니다. **값이 얼마나 치우쳐 있는지**는 모릅니다. `status_code` 는 200 이 75%, 나머지가 각 5% 인데, 옵티마이저는 이 편향을 모르고 "6종이니 각 1/6" 이라고 뭉뚱그립니다.

히스토그램은 **값의 실제 분포**를 저장해 이 추정을 정확하게 만듭니다.

**히스토그램 없이** `status_code = 500` 추정:
```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE status_code = 500;
```
```
| type | rows   | filtered | Extra       |
| ALL  | 996151 |    10.00 | Using where |    ← filtered 10% = 막연한 기본 추정
```

`filtered: 10.00` — 근거 없는 기본값입니다(실제 500 은 5%).

**히스토그램 생성 후:**
```sql
ANALYZE TABLE access_logs UPDATE HISTOGRAM ON status_code, method;

EXPLAIN SELECT COUNT(*) FROM access_logs WHERE status_code = 500;
```
```
| type | rows   | filtered | Extra       |
| ALL  | 996151 |     4.99 | Using where |    ← filtered 4.99% = 실제 분포 반영!
```

`filtered` 가 10.00 → **4.99** 로 정확해졌습니다(실제 status_code=500 은 정확히 5%). 예상 결과 행이 99,615 → 49,700 으로 바로잡혔습니다.

히스토그램 조회/삭제:
```sql
SELECT COLUMN_NAME,
       JSON_EXTRACT(HISTOGRAM, '$."histogram-type"') AS htype
FROM information_schema.COLUMN_STATISTICS
WHERE SCHEMA_NAME='shop' AND TABLE_NAME='access_logs';
-- singleton(값 종류가 적을 때) 또는 equi-height(많을 때)

ANALYZE TABLE access_logs DROP HISTOGRAM ON status_code, method;
```

> 💡 **히스토그램은 언제 쓰나**
> - 인덱스를 걸기엔 선택도가 낮지만(Step 15), 값이 **심하게 편향된** 컬럼(상태값, 등급, 국가 등)
> - 그 컬럼이 `WHERE`/`JOIN` 조건에 자주 쓰여 옵티마이저의 행 수 추정이 중요할 때
> 인덱스와 달리 **쓰기 비용이 없습니다**(자동 갱신 안 됨). 대신 데이터가 바뀌면 수동으로 다시 만들어야 합니다.

> ⚠️ **함정 — 히스토그램은 인덱스가 아니다**
> 히스토그램은 **추정을 개선**할 뿐, 데이터를 빨리 찾아주지 않습니다. `status_code=500` 조회 자체는 여전히 풀스캔입니다.
> 히스토그램은 "옵티마이저가 더 나은 **계획**(예: 조인 순서)을 세우도록" 돕는 것이지, 그 자체가 조회를 빠르게 하진 않습니다.

---

## 16-9. 실전 튜닝 절차

이 코스의 Step 15~16 을 하나의 절차로 묶습니다.

```
① 느린 쿼리 발견
   - slow query log, performance_schema, 또는 애플리케이션 APM 으로 찾는다
   - "가장 느린 쿼리"보다 "느리면서 자주 실행되는 쿼리"가 우선순위

② EXPLAIN 으로 진단
   - type 이 ALL/index 인가? (풀스캔)
   - key 가 NULL 인가? (인덱스 안 씀)
   - Extra 에 Using filesort / Using temporary 가 있는가?
   - rows 추정이 비현실적으로 크거나, 실제와 동떨어졌는가?

③ 원인별 처방
   - 인덱스가 없다        → 인덱스 설계 (Step 15: 선두 컬럼·커버링)
   - 쿼리가 못 타게 썼다   → sargable 하게 재작성 (함수 제거, 범위로)
   - 통계가 낡았다        → ANALYZE TABLE
   - 분포 추정이 틀렸다    → 히스토그램
   - 옵티마이저가 오판한다 → (최후) 힌트

④ EXPLAIN ANALYZE 로 재측정
   - actual time 이 실제로 줄었는가?
   - actual rows 가 추정과 가까워졌는가?

⑤ 부작용 점검
   - 새 인덱스가 INSERT/UPDATE 를 얼마나 느리게 하는가? (Step 15: 인덱스는 부채)
   - 인비저블 인덱스로 안전하게 검증했는가?
```

### 미니 실습 — 절차를 한 번에

"특정 고객의 5xx 에러 로그를 최근순으로" 라는 요구가 있다고 합시다.

```sql
-- ① 대상 쿼리 (access_logs, 인덱스 없음)
-- ② EXPLAIN: type ALL, filesort 예상
EXPLAIN SELECT log_id, status_code, logged_at FROM access_logs
WHERE customer_id = 3 AND status_code >= 500 ORDER BY logged_at DESC LIMIT 10;

-- ③ 처방: (customer_id, logged_at) 로 정렬을 인덱스에 태우고,
--         status_code 는 커버링을 위해 포함 → (customer_id, logged_at, status_code)
ALTER TABLE access_logs ADD INDEX idx_tune (customer_id, logged_at, status_code);

-- ④ 재측정
EXPLAIN ANALYZE SELECT log_id, status_code, logged_at FROM access_logs
WHERE customer_id = 3 AND status_code >= 500 ORDER BY logged_at DESC LIMIT 10;

-- ⑤ 정리 (실습이므로 인덱스 제거)
ALTER TABLE access_logs DROP INDEX idx_tune;
```

**④ 재측정 결과 (전 → 후)**
```
BEFORE:
-> Limit: 10 row(s)  (actual time=157..157 rows=10 loops=1)
    -> Sort: logged_at DESC, limit to 10  (actual time=157..157 rows=10)          ← filesort
        -> Filter: (customer_id=3 and status_code>=500)  (actual ... rows=16667)
            -> Table scan on access_logs  (actual time=0.23..123 rows=1e+6)       ← 100만 풀스캔

AFTER:
-> Limit: 10 row(s)  (actual time=0.0445..0.0477 rows=10 loops=1)
    -> Filter: (status_code >= 500)  (actual time=0.044..0.047 rows=10)
        -> Covering index lookup on access_logs using idx_tune (customer_id=3) (reverse)
           (actual time=0.043..0.045 rows=19)                                     ← 인덱스로 19행만
```

**157ms → 0.05ms.** 풀스캔+filesort 가 커버링 인덱스 역방향 조회(`(reverse)`)로 바뀌었습니다.
`practice.sql` 에서 직접 실행해 확인하세요.

> 💡 이 시드 데이터는 재현성을 위해 값을 나머지 연산으로 만들어서, `customer_id` 와 `status_code` 가 서로 상관되어 있습니다
> (예: `customer_id=7` 은 전부 200, `customer_id=3` 은 5xx 가 많음). 실제 데이터의 우연한 상관을 흉내 낸 것으로, 튜닝 실습에는 오히려 현실적입니다.

---

## 정리

| 항목 | 핵심 |
|---|---|
| `type` 순위 | system > const > eq_ref > ref > range > index > ALL |
| `type: index` | 인덱스 풀스캔 = 사실상 전수조사. 좋은 게 아니다 |
| `type: ALL` | 대용량에서 최악. 단 **작은 테이블에선 정상** |
| `key` | 실제 고른 인덱스. NULL = 인덱스 미사용 |
| `rows`/`filtered` | **추정치**. 실제와 다를 수 있다 |
| `Extra` 경보 | `Using filesort`, `Using temporary` |
| `Using index` | 커버링. 테이블 접근 없음(좋음) |
| EXPLAIN ANALYZE | 실제 실행 + actual time/rows. **UPDATE/DELETE 엔 위험** |
| FORMAT=TREE/JSON | 실행 흐름 / 비용 상세 |
| 인덱스 힌트 | USE / FORCE / IGNORE INDEX |
| 옵티마이저 힌트 | `/*+ INDEX / NO_INDEX / JOIN_ORDER ... */` (최후 수단) |
| ANALYZE TABLE | 인덱스 통계 갱신 |
| 히스토그램(8.0) | 편향된 컬럼의 분포 → filtered 추정 개선. 조회를 빠르게 하진 않음 |
| 튜닝 절차 | 느린 쿼리 → EXPLAIN → 처방 → EXPLAIN ANALYZE 재측정 → 부작용 점검 |

---

## 연습문제

`exercise.sql` 에 7문제가 있습니다. 정답은 `solution.sql`.

1. EXPLAIN 출력 읽고 무엇이 문제인지 진단
2. `type` 등급 서열 맞히기
3. filesort/temporary 를 없애는 인덱스 설계
4. EXPLAIN ANALYZE 로 추정 vs 실측 괴리 찾기
5. 옵티마이저 힌트로 인덱스 강제/차단
6. 히스토그램으로 filtered 추정 개선 확인
7. 종합 튜닝 — 느린 쿼리를 EXPLAIN → 인덱스 → 재측정

---

## 다음 단계

Step 13~16 으로 **스키마 설계(제약·정규화) → 뷰·생성 컬럼 → 인덱스 → 실행 계획 분석**까지,
데이터를 "안전하게 담고 빠르게 꺼내는" 한 사이클을 완주했습니다.
다음 파트에서는 트랜잭션과 격리 수준, 락, 그리고 JSON·윈도우 함수 같은 고급 주제로 이어집니다.

(이 스텝 시리즈는 Step 16 에서 마칩니다. 수고하셨습니다.)

---

## 실습 파일

이 스텝은 SQL 파일 3개로 진행합니다. 먼저 `practice.sql` 을 실행해 본문 16-2~16-9 의 EXPLAIN 출력을 직접 눈으로 확인하고, 그다음 `exercise.sql` 의 7문제를 스스로 풀어 본 뒤, `solution.sql` 로 정답과 해설을 대조합니다.

세 파일 모두 `USE shop;` 으로 시작하며, **`shop` 스키마(`sql/01_schema.sql`)와 100만 행짜리 `access_logs` 테이블(`sql/04_seed_big.sql`)이 이미 만들어져 있다는 전제**로 동작합니다. `access_logs` 는 Step 15 에서 처음 쓰지만 만든 것은 공용 시드 스크립트이고, **보조 인덱스가 하나도 없는 상태(PK `log_id` 만)** 로 시드됩니다. 이 "인덱스 없음"이 Step 15~16 실습의 출발점이므로, 앞 스텝의 인덱스가 남아 있으면 EXPLAIN 결과가 본문과 달라집니다. 그래서 실습으로 만든 인덱스와 히스토그램은 **문제마다(그리고 `practice.sql` 은 파일 끝에서) 반드시 DROP** 하도록 되어 있습니다.

### practice.sql

본문의 예제를 위에서부터 그대로 따라가는 **따라 하기용 스크립트**입니다. `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 로 한 번에 돌릴 수 있지만, `EXPLAIN ANALYZE` 와 `FORMAT=TREE` 는 여러 줄로 출력되므로 **대화형 클라이언트에서 한 문장씩 실행**하는 편이 훨씬 읽기 좋습니다(배치로 돌리면 줄바꿈이 `\n` 문자로 뭉개져 나옵니다).

- `[16-2]` 블록은 `const`(PK 등치) → `eq_ref`(조인 1:1) → `ref`(비유니크 인덱스) → `range`(BETWEEN) → `index`(인덱스 풀스캔) → `ALL`(테이블 풀스캔) 순으로 **type 등급을 실제 출력으로 하나씩 재현**합니다. 마지막 `EXPLAIN SELECT * FROM orders WHERE shipping_city = '서울';` 가 `ALL` 로 나오는 건 버그가 아니라 600행짜리 작은 테이블이라 옵티마이저가 일부러 고른 결과라는 점을 확인하세요.
- `[16-5]` 블록은 `ALTER TABLE access_logs ADD INDEX idx_customer (customer_id);` 를 **가운데 두고 같은 쿼리를 두 번** EXPLAIN ANALYZE 합니다. 전(Table scan, ~120ms) 과 후(Covering index lookup, ~3ms) 의 `actual time` 차이가 이 스텝의 핵심 체험입니다.
- `[16-7]` 의 `SET SESSION optimizer_switch = 'skip_scan=off';` 다음 줄에 바로 `'skip_scan=on'` 원복이 들어 있습니다. 세션 단위라 접속을 끊으면 어차피 사라지지만, 같은 세션에서 뒤 실습을 이어 하기 때문에 원복이 필요합니다.
- `[16-8]` 은 `ANALYZE TABLE access_logs UPDATE HISTOGRAM ON status_code, method;` 전후로 `status_code = 500` 의 `filtered` 가 10.00 → 4.99 로 바뀌는 것을 보여주고, 끝에서 `DROP HISTOGRAM` 으로 되돌립니다.
- 스크립트 맨 끝의 `ALTER TABLE access_logs DROP INDEX idx_customer;` 와 `SHOW INDEX FROM access_logs;` 는 **뒷정리 확인**입니다. 여기서 `PRIMARY` 만 남아야 정상이며, 남아 있는 인덱스가 있으면 뒤이은 `exercise.sql` 의 EXPLAIN 결과가 본문과 달라집니다. `access_logs` 외의 공용 테이블(`orders`, `customers`)에는 인덱스를 만들거나 지우지 마세요.

```sql file="./practice.sql"
```

### exercise.sql

본문 「연습문제」에서 말한 **7문제의 문제지**입니다. 각 문제는 진단(주석으로 답 쓰기)과 실행(인덱스를 만들고 EXPLAIN 을 다시 보기)이 섞여 있으니, 빈 줄에 직접 SQL 과 주석을 채워 넣으며 푸세요.

- 문제 1의 `EXPLAIN SELECT * FROM access_logs WHERE DATE(logged_at) = '2024-06-15';` 는 **일부러 컬럼에 함수를 씌운 안티패턴**입니다. `DATE(logged_at)` 때문에 인덱스를 탈 수 없어 `type: ALL`, `key: NULL` 이 나오는 것이 학습 포인트입니다(Step 15 의 sargable 규칙).
- 문제 3은 `WHERE customer_id BETWEEN 1 AND 5 GROUP BY customer_id ORDER BY customer_id` 에서 `Using temporary; Using filesort` 를 **단일 컬럼 인덱스 하나로** 동시에 없애는 것이 목표입니다.
- 문제 5는 파일 안에 `ALTER TABLE access_logs ADD INDEX idx_ex5 (customer_id);` 와 `DROP INDEX idx_ex5;` 가 **미리 짝으로 들어 있습니다.** 그 사이의 "여기에 작성" 자리에 힌트 실습을 채워야 하므로, 파일을 통째로 배치 실행하면 인덱스가 만들어졌다 바로 지워져 아무 관찰도 못 합니다. **한 줄씩 실행**하세요.
- 문제 6의 지문은 `method` 를 "GET/POST/PUT **3종**, GET 70% / POST 20% / PUT 10%" 로 안내하는데, `solution.sql` 은 "**4종** ENUM 이라 기본 추정 25%" 라고 말합니다. 모순처럼 보이지만 둘 다 맞습니다 — 컬럼 정의는 `ENUM('GET','POST','PUT','DELETE')` 로 **4종**이고, 시드가 넣는 값은 GET/POST/PUT **3종**뿐이라 `DELETE` 는 **0건**입니다. 옵티마이저는 히스토그램이 없으면 실제 데이터를 모른 채 **ENUM 정의상의 4종을 균등하다고 가정해 `filtered: 25.00`(1/4)** 을 내놓습니다. 실제 PUT 은 10% 이니 2.5배 과대추정이고, 이 괴리를 히스토그램으로 잡는 것이 문제 6의 핵심입니다.
- 문제 7은 `method = 'GET' AND duration_ms >= 2900 ORDER BY logged_at DESC LIMIT 20` 이라는 요구에서 **등치 → 정렬 → 범위** 순으로 복합 인덱스 컬럼 순서를 정하는 종합 문제입니다.
- 모든 문제에서 실습용 인덱스/히스토그램은 확인 후 **반드시 DROP** 하세요. 남겨 두면 다음 문제의 EXPLAIN 이 오염됩니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 7문제의 **정답과 해설**입니다. 먼저 스스로 풀어 본 뒤에 열어 보세요. 단순 정답만이 아니라 "왜 그렇게 되는가"의 근거가 주석으로 길게 붙어 있습니다.

- 정답 3은 `idx_ex3 (customer_id)` 하나로 `Extra` 가 `Using where; Using index` 가 되어 temporary/filesort 가 둘 다 사라지는 이유를 설명합니다. 인덱스가 이미 `customer_id` 순으로 정렬돼 있어 그룹핑과 정렬을 공짜로 얻고, `COUNT(*)` 만 필요하므로 커버링까지 성립합니다.
- 정답 4는 EXPLAIN 의 `rows` 추정(≈66756)과 EXPLAIN ANALYZE 의 `actual rows`(33333)가 **약 2배 어긋나는** 실례입니다. `customer_id` 30종이 고르게 분포하므로 100만/30 ≈ 33333 이 실제값인데, 샘플링 기반 통계가 크게 잡은 것입니다.
- 정답 6은 `filtered` 가 25.00(4종 ENUM 이라 막연히 1/4) → 10.00(히스토그램이 실제 분포 반영)으로 정확해지는 과정을 보여주고, `SELECT ROUND(SUM(method='PUT') / COUNT(*) * 100, 2)` 로 실제 비율이 10.00 임을 검증합니다. 본문 16-8 의 "히스토그램은 인덱스가 아니다" 경고가 여기서도 반복됩니다 — 추정만 개선될 뿐 조회는 여전히 풀스캔입니다.
- 정답 7의 `idx_q7 (method, logged_at, duration_ms)` 는 범위 조건인 `duration_ms` 를 정렬 컬럼 뒤로 밀어 둔 것이 핵심입니다. 범위를 정렬 앞에 두면 인덱스의 정렬 순서가 깨져 filesort 가 되살아납니다. 결과는 211ms → 0.4ms 이며, `path` 가 인덱스에 없어 커버링은 아니지만 최종 20건에만 테이블을 접근하므로 충분히 빠릅니다.
- 스크립트 곳곳의 `ALTER TABLE access_logs DROP INDEX ...` / `DROP HISTOGRAM ...` 은 뒷정리이므로 생략하지 마세요.

```sql file="./solution.sql"
```
