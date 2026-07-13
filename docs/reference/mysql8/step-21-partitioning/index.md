# Step 21 — 파티셔닝

> **학습 목표**
> - 파티셔닝이 실제로 푸는 문제(대량 삭제, 넓은 범위 스캔)와 **못 푸는 문제**(단일 행 조회 성능)를 구분한다
> - RANGE / LIST / HASH / KEY / RANGE COLUMNS 를 목적에 맞게 고른다
> - `EXPLAIN` 의 `partitions` 컬럼으로 **파티션 프루닝**이 걸렸는지 눈으로 확인한다
> - `DROP PARTITION` 으로 오래된 로그를 즉시 버리는 실무 패턴을 익힌다 (`DELETE` 대비 30배 이상)
> - 파티션의 제약(유니크 키 규칙, FK 불가)을 미리 알고 설계 단계에서 피한다
>
> **선행 스텝**: [Step 15 — 인덱스](../step-15-indexes/index.md), [Step 16 — EXPLAIN](../step-16-explain-optimizer/index.md)
> **예상 소요**: 60분

---

## 21-1. 파티셔닝은 "만능"이 아니다 — 먼저 이것부터

파티셔닝(Partitioning)은 **하나의 논리 테이블을 여러 개의 물리 조각(파티션)으로 나누어 저장**하는 기능입니다.
애플리케이션 입장에서는 여전히 테이블 하나입니다. SQL 도 그대로입니다.

가장 흔한 오해부터 깨고 시작합시다.

| 흔한 기대 | 진실 |
|---|---|
| "테이블이 커서 느리니 파티션 나누면 빨라지겠지" | **아니다.** 인덱스로 1행 찾는 쿼리는 파티션해도 빨라지지 않는다. B-트리 높이는 100만 행이든 1억 행이든 3~4단계다. |
| "파티션이 인덱스를 대신한다" | **아니다.** 파티션은 인덱스가 아니다. 파티션 키가 없는 조건은 **모든 파티션을 다 뒤진다**(= 오히려 느려진다). |
| "일단 나눠두면 손해는 없다" | **손해가 있다.** 파티션 개수만큼 파일 핸들/메타데이터가 늘고, 프루닝이 안 되는 쿼리는 파티션 수만큼 스캔을 반복한다. |

**파티셔닝이 진짜로 잘 푸는 문제는 딱 세 가지입니다.**

1. **대량 삭제 / 아카이빙** — `DROP PARTITION` 은 파일을 통째로 버린다. 행 단위 `DELETE` 와는 차원이 다르다. (← 이게 90%의 도입 이유)
2. **넓은 범위 스캔** — 조회 범위가 테이블의 상당 부분(수십 %)이면 인덱스가 무용지물이 된다. 이때 파티션 프루닝이 스캔량 자체를 줄여준다.
3. **거대 테이블의 유지보수 분할** — `OPTIMIZE`/`ANALYZE` 를 파티션 단위로 돌릴 수 있다.

> ⚠️ **함정** — "테이블이 크다"가 파티셔닝의 도입 근거가 될 수 없습니다.
> 도입 근거는 **"오래된 데이터를 주기적으로 버려야 한다"** 또는 **"조회가 항상 날짜 범위로 들어온다"** 입니다.
> 이 둘 중 어느 것도 해당하지 않으면 파티셔닝하지 마세요.

---

## 21-2. 실습 테이블 만들기

100만 행짜리 `access_logs`(`logged_at` 은 2024-01-01 ~ 2024-12-01) 구조를 복제해서
`logged_at` 기준 **월 단위 RANGE COLUMNS 파티션** 테이블을 만듭니다.

```sql
CREATE TABLE s21_access_logs (
  log_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  path        VARCHAR(100) NOT NULL,
  method      ENUM('GET','POST','PUT','DELETE') NOT NULL,
  status_code SMALLINT NOT NULL,
  duration_ms INT NOT NULL,
  user_agent  VARCHAR(60) NOT NULL,
  logged_at   DATETIME NOT NULL,
  -- 규칙: 모든 유니크 키(PK 포함)는 파티션 키를 반드시 포함해야 한다.
  --       그래서 PK 가 (log_id) 가 아니라 (log_id, logged_at) 이다.
  PRIMARY KEY (log_id, logged_at),
  KEY idx_s21_logged_at (logged_at)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(logged_at) (
  PARTITION p2024_01 VALUES LESS THAN ('2024-02-01'),
  PARTITION p2024_02 VALUES LESS THAN ('2024-03-01'),
  -- ... (12월까지)
  PARTITION p2024_12 VALUES LESS THAN ('2025-01-01'),
  PARTITION pmax     VALUES LESS THAN (MAXVALUE)   -- 안전망
);

INSERT INTO s21_access_logs (customer_id, path, method, status_code, duration_ms, user_agent, logged_at)
SELECT customer_id, path, method, status_code, duration_ms, user_agent, logged_at
FROM access_logs;
```

파티션이 실제로 나뉘었는지는 `information_schema.PARTITIONS` 로 확인합니다.

```sql
SELECT PARTITION_NAME, PARTITION_METHOD, PARTITION_DESCRIPTION, TABLE_ROWS,
       ROUND(DATA_LENGTH/1024/1024,1) AS data_mb,
       ROUND(INDEX_LENGTH/1024/1024,1) AS idx_mb
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_access_logs'
ORDER BY PARTITION_ORDINAL_POSITION;
```

**결과**
```
+----------------+------------------+-----------------------+------------+---------+--------+
| PARTITION_NAME | PARTITION_METHOD | PARTITION_DESCRIPTION | TABLE_ROWS | data_mb | idx_mb |
+----------------+------------------+-----------------------+------------+---------+--------+
| p2024_01       | RANGE COLUMNS    | '2024-02-01'          |      89818 |     6.5 |    2.5 |
| p2024_02       | RANGE COLUMNS    | '2024-03-01'          |      86203 |     5.5 |    2.5 |
| p2024_03       | RANGE COLUMNS    | '2024-04-01'          |      92131 |     6.5 |    2.5 |
| p2024_04       | RANGE COLUMNS    | '2024-05-01'          |      89167 |     6.5 |    2.5 |
| p2024_05       | RANGE COLUMNS    | '2024-06-01'          |      89818 |     6.5 |    2.5 |
| p2024_06       | RANGE COLUMNS    | '2024-07-01'          |      89167 |     6.5 |    2.5 |
| p2024_07       | RANGE COLUMNS    | '2024-08-01'          |      89818 |     6.5 |    2.5 |
| p2024_08       | RANGE COLUMNS    | '2024-09-01'          |      92131 |     6.5 |    2.5 |
| p2024_09       | RANGE COLUMNS    | '2024-10-01'          |      89167 |     6.5 |    2.5 |
| p2024_10       | RANGE COLUMNS    | '2024-11-01'          |      92131 |     6.5 |    2.5 |
| p2024_11       | RANGE COLUMNS    | '2024-12-01'          |      89167 |     6.5 |    2.5 |
| p2024_12       | RANGE COLUMNS    | '2025-01-01'          |       1932 |     0.2 |    0.1 |
| pmax           | RANGE COLUMNS    | MAXVALUE              |          0 |     0.0 |    0.0 |
+----------------+------------------+-----------------------+------------+---------+--------+
```

> 💡 **실무 팁** — `TABLE_ROWS` 는 InnoDB에서 **추정치**입니다(89,818 vs 실제 92,358). 정확한 행수가 필요하면
> `SELECT COUNT(*) FROM s21_access_logs PARTITION (p2024_09);` 처럼 **파티션을 직접 지목**하세요.
>
> ```sql
> SELECT COUNT(*) AS rows_in_2024_09 FROM s21_access_logs PARTITION (p2024_09);
> ```
> ```
> +-----------------+
> | rows_in_2024_09 |
> +-----------------+
> |           89380 |
> +-----------------+
> ```

---

## 21-3. 파티션 프루닝 — EXPLAIN 의 `partitions` 컬럼

**파티션 프루닝(Partition Pruning)** = 옵티마이저가 WHERE 조건을 보고 "이 파티션은 볼 필요조차 없다"고 잘라내는 것.
**이게 안 걸리면 파티셔닝은 아무 의미가 없습니다.** 확인 방법은 딱 하나, `EXPLAIN` 의 `partitions` 컬럼입니다.

### (O) 프루닝 성공 — 파티션 키에 대한 상수 범위

```sql
EXPLAIN SELECT COUNT(*) FROM s21_access_logs
WHERE logged_at >= '2024-09-01' AND logged_at < '2024-10-01';
```

**결과**
```
+----+-------------+-----------------+------------+-------+---------------------------+-------------------+-------+----------+--------------------------+
| id | select_type | table           | partitions | type  | possible_keys             | key               | rows  | filtered | Extra                    |
+----+-------------+-----------------+------------+-------+---------------------------+-------------------+-------+----------+--------------------------+
|  1 | SIMPLE      | s21_access_logs | p2024_09   | index | PRIMARY,idx_s21_logged_at | idx_s21_logged_at | 89167 |    50.00 | Using where; Using index |
+----+-------------+-----------------+------------+-------+---------------------------+-------------------+-------+----------+--------------------------+
```

`partitions = p2024_09` — 13개 중 **1개만** 열었습니다. 성공.

### (X) 프루닝 실패 1 — 파티션 키에 함수를 씌웠다

```sql
EXPLAIN SELECT COUNT(*) FROM s21_access_logs WHERE YEAR(logged_at) = 2024;
```

**결과**
```
+----+-------------+-----------------+-----------------------------------------------------------------------------------------------------------------+-------+--------+-------------+
| id | select_type | table           | partitions                                                                                                      | type  | rows   | Extra       |
+----+-------------+-----------------+-----------------------------------------------------------------------------------------------------------------+-------+--------+-------------+
|  1 | SIMPLE      | s21_access_logs | p2024_01,p2024_02,p2024_03,p2024_04,p2024_05,p2024_06,p2024_07,p2024_08,p2024_09,p2024_10,p2024_11,p2024_12,pmax | index | 990650 |  Using where; Using index |
+----+-------------+-----------------+-----------------------------------------------------------------------------------------------------------------+-------+--------+-------------+
```

전 파티션(13개) 스캔. 인덱스가 함수 때문에 안 먹는 것과 **정확히 같은 원리**입니다.

### (X) 프루닝 실패 2 — 파티션 키가 WHERE 에 아예 없다

```sql
EXPLAIN SELECT COUNT(*) FROM s21_access_logs WHERE customer_id = 7;
```

**결과**
```
|  1 | SIMPLE | s21_access_logs | p2024_01,...,p2024_12,pmax | ALL | NULL | NULL | 990650 | 10.00 | Using where |
```

> ⚠️ **함정** — 파티션 키를 `logged_at` 으로 잡았는데 정작 API 쿼리가 `WHERE customer_id = ?` 라면,
> 파티셔닝은 **이득이 0이고 손해만 남습니다.** 파티션 키는 반드시 **가장 흔한 WHERE 조건**을 보고 정하세요.

---

## 21-4. 실측 — 파티션이 이기는 경우 / 지는 경우

비교를 공정하게 하기 위해, **같은 데이터 + 같은 `logged_at` 인덱스를 가진 비파티션 테이블** `s21_access_logs_np` 를 만들어 놓고 `EXPLAIN ANALYZE`(8.0.18+, 쿼리를 실제로 실행하고 시간을 측정)로 비교합니다.

### [A] 좁은 범위(1개월) — 차이가 거의 없다

```sql
EXPLAIN ANALYZE SELECT AVG(duration_ms) FROM s21_access_logs
WHERE logged_at >= '2024-09-01' AND logged_at < '2024-10-01';

EXPLAIN ANALYZE SELECT AVG(duration_ms) FROM s21_access_logs_np
WHERE logged_at >= '2024-09-01' AND logged_at < '2024-10-01';
```

**결과**
```
-- 파티션
-> Aggregate: avg(s21_access_logs.duration_ms)  (cost=13479 rows=1) (actual time=17.7..17.7 rows=1 loops=1)
    -> Filter: (logged_at >= '2024-09-01' and logged_at < '2024-10-01')  (actual time=0.0897..14.5 rows=89380 loops=1)
        -> Table scan on s21_access_logs  (cost=9021 rows=89167) (actual time=0.0874..9.11 rows=89380 loops=1)

-- 비파티션 (인덱스 레인지 스캔)
-> Aggregate: avg(s21_access_logs_np.duration_ms)  (cost=95568 rows=1) (actual time=41.8..41.8 rows=1 loops=1)
    -> Index range scan on s21_access_logs_np using idx_np_logged_at over ('2024-09-01' <= logged_at < '2024-10-01') ...
       (cost=78192 rows=173760) (actual time=0.243..38.6 rows=89380 loops=1)
```

17.7ms vs 41.8ms. 파티션 쪽이 조금 빠르지만, **비파티션도 인덱스로 충분히 잘 처리**합니다.
(파티션 쪽은 "1개 파티션 풀스캔"이 되어 인덱스 랜덤 액세스를 피한 것이 이득의 정체입니다.)

### [B] 넓은 범위(6개월 = 전체의 46%) — 파티션이 확실히 이긴다

```sql
EXPLAIN ANALYZE SELECT COUNT(*), AVG(duration_ms) FROM s21_access_logs
WHERE logged_at >= '2024-07-01' AND logged_at < '2025-01-01';

EXPLAIN ANALYZE SELECT COUNT(*), AVG(duration_ms) FROM s21_access_logs_np
WHERE logged_at >= '2024-07-01' AND logged_at < '2025-01-01';
```

**결과**
```
-- 파티션 : 6개 파티션만 읽는다 → 457,766행만 스캔
-> Aggregate: count(0), avg(duration_ms)  (cost=68772 rows=1) (actual time=90..90 rows=1 loops=1)
    -> Filter: (...)  (actual time=0.0203..73 rows=457766 loops=1)
        -> Table scan on s21_access_logs  (cost=45958 rows=454346) (actual time=0.0194..45.1 rows=457766 loops=1)

-- 비파티션 : 선택도가 나빠서 옵티마이저가 인덱스를 포기 → 100만 행 풀스캔
-> Aggregate: count(0), avg(duration_ms)  (cost=150440 rows=1) (actual time=156..156 rows=1 loops=1)
    -> Filter: (...)  (actual time=68.4..139 rows=457766 loops=1)
        -> Table scan on s21_access_logs_np  (cost=100633 rows=996151) (actual time=0.0259..94 rows=1e+6 loops=1)
```

90ms vs 156ms. 핵심은 시간이 아니라 **스캔한 행수**입니다: `rows=457766` vs `rows=1e+6`.
인덱스가 포기되는 넓은 범위에서, 파티션은 **읽어야 할 데이터 자체를 절반 이하로 줄였습니다.**

### [C] 파티션 키가 없는 조건 — 파티션이 **진다**

```sql
EXPLAIN ANALYZE SELECT COUNT(*) FROM s21_access_logs    WHERE customer_id = 7;
EXPLAIN ANALYZE SELECT COUNT(*) FROM s21_access_logs_np WHERE customer_id = 7;
```

**결과**
```
-- 파티션    : (actual time=125..125 rows=1 loops=1)   ← 13개 파티션을 차례로 풀스캔
-- 비파티션  : (actual time=105..105 rows=1 loops=1)   ← 테이블 1개를 한 번에 풀스캔
```

**125ms vs 105ms — 파티션 테이블이 더 느립니다.** 같은 100만 행을 읽는데 파티션은 13번 나눠 읽었기 때문입니다.
이것이 "파티셔닝은 공짜가 아니다"의 실측 증거입니다.

---

## 21-5. 파티션 종류 5가지

### RANGE — 정수 파티션 식 (날짜는 `TO_DAYS()` 등으로 정수화)

```sql
CREATE TABLE s21_range_todays (
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  logged_at DATE NOT NULL,
  msg       VARCHAR(50) NOT NULL,
  PRIMARY KEY (id, logged_at)
) ENGINE=InnoDB
PARTITION BY RANGE (TO_DAYS(logged_at)) (
  PARTITION p2024_h1 VALUES LESS THAN (TO_DAYS('2024-07-01')),
  PARTITION p2024_h2 VALUES LESS THAN (TO_DAYS('2025-01-01')),
  PARTITION pmax     VALUES LESS THAN MAXVALUE
);

EXPLAIN SELECT * FROM s21_range_todays WHERE logged_at = '2024-08-01';
```

**결과**
```
| 1 | SIMPLE | s21_range_todays | p2024_h2 | ALL | ... |
```

`WHERE TO_DAYS(logged_at) = ...` 이라고 쓰지 않았는데도 프루닝됐습니다.
**옵티마이저는 `TO_DAYS`/`YEAR`/`TO_SECONDS` 세 함수에 한해 파티션 식을 역산해 줍니다.** (그 외 함수는 안 됩니다.)

### LIST — 값을 열거

```sql
CREATE TABLE s21_list_region (
  id        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  region_id TINYINT NOT NULL,
  amount    DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (id, region_id)
) ENGINE=InnoDB
PARTITION BY LIST (region_id) (
  PARTITION p_capital VALUES IN (1, 2, 3),
  PARTITION p_south   VALUES IN (4, 5, 6),
  PARTITION p_etc     VALUES IN (7, 8, 9, 0)
);

INSERT INTO s21_list_region (region_id, amount) VALUES (99, 1);
```

**결과**
```
ERROR 1526 (HY000): Table has no partition for value 99
```

> ⚠️ **함정** — LIST 에는 RANGE 의 `MAXVALUE` 같은 **안전망이 없습니다.** 정의되지 않은 값이 들어오면
> `INSERT` 가 통째로 실패합니다. 새 지역 코드가 추가될 수 있다면 LIST 는 위험합니다.
> (`PARTITION p_etc VALUES IN (...)` 에 미리 넉넉히 넣거나, RANGE 를 쓰세요.)

### HASH — 고르게 흩뿌리기

```sql
CREATE TABLE s21_hash_cust (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  note        VARCHAR(30) NULL,
  PRIMARY KEY (id, customer_id)
) ENGINE=InnoDB
PARTITION BY HASH (customer_id) PARTITIONS 4;
```

**결과** (고객 30명이 4개 파티션에 고르게 분산)
```
+----------------+------------+
| PARTITION_NAME | TABLE_ROWS |
+----------------+------------+
| p0             |          7 |
| p1             |          8 |
| p2             |          8 |
| p3             |          7 |
+----------------+------------+
```

프루닝은 **등치(`=`, `IN`) 조건에서만** 걸립니다.

```sql
EXPLAIN SELECT * FROM s21_hash_cust WHERE customer_id = 7;   -- partitions: p3        (프루닝 O)
EXPLAIN SELECT * FROM s21_hash_cust WHERE customer_id > 7;   -- partitions: p0,p1,p2,p3 (프루닝 X)
```

> ⚠️ **함정** — HASH 파티션의 개수를 바꾸면(`COALESCE`/`ADD PARTITION`) **전체 데이터를 재분배**합니다.
> 테이블 전체 재작성이라 운영 중에는 사실상 불가능합니다. 처음부터 넉넉히 잡으세요.

### KEY — MySQL 내부 해시. 정수가 아닌 컬럼도 가능

```sql
CREATE TABLE s21_key_email (
  email VARCHAR(120) NOT NULL,
  name  VARCHAR(50)  NOT NULL,
  PRIMARY KEY (email)
) ENGINE=InnoDB
PARTITION BY KEY (email) PARTITIONS 4;
```

**결과**
```
+----------------+------------+
| PARTITION_NAME | TABLE_ROWS |
+----------------+------------+
| p0             |          5 |
| p1             |          9 |
| p2             |          9 |
| p3             |          7 |
+----------------+------------+
```

HASH 는 파티션 식이 **정수를 반환해야** 하지만, KEY 는 MySQL 이 내부 해시 함수를 써서 **문자열/날짜도** 받습니다.

### RANGE COLUMNS — 컬럼을 그대로 비교 (권장)

```sql
PARTITION BY RANGE COLUMNS (yr, mon) (
  PARTITION p1 VALUES LESS THAN (2024, 7),
  PARTITION p2 VALUES LESS THAN (2025, 1),
  PARTITION p3 VALUES LESS THAN (MAXVALUE, MAXVALUE)
);

EXPLAIN SELECT * FROM s21_range_columns2 WHERE yr = 2024 AND mon = 9;   -- partitions: p2
```

`RANGE COLUMNS` 는 **함수를 쓰지 않고 컬럼 값을 그대로** 비교합니다.
DATE/DATETIME/문자열/여러 컬럼(튜플 비교)을 지원해서, 날짜 파티션이라면 `RANGE(TO_DAYS(...))` 보다 **`RANGE COLUMNS(날짜컬럼)` 가 더 읽기 쉽고 안전합니다.** 이 스텝의 `s21_access_logs` 도 그렇게 만들었습니다.

---

## 21-6. 제약 — 설계 전에 반드시 알아야 할 것들

### (1) 모든 유니크 키(PK 포함)는 파티션 키를 전부 포함해야 한다

```sql
CREATE TABLE s21_bad_uk (
  id        BIGINT UNSIGNED NOT NULL,
  logged_at DATETIME NOT NULL,
  PRIMARY KEY (id)                 -- logged_at 이 빠졌다!
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(logged_at) (PARTITION p0 VALUES LESS THAN ('2025-01-01'));
```

**결과**
```
ERROR 1503 (HY000): A PRIMARY KEY must include all columns in the table's partitioning function
                    (prefixed columns are not considered).
```

이유는 단순합니다. MySQL 의 파티션은 **로컬 인덱스**(파티션마다 자기 인덱스를 따로 가짐)만 지원합니다.
"글로벌 유니크"를 보장하려면 INSERT 마다 모든 파티션의 인덱스를 뒤져야 하는데, 그러면 파티셔닝의 이점이 사라집니다.
그래서 MySQL 은 **아예 금지**합니다.

**이 제약의 진짜 무서운 점**: 유니크 인덱스도 마찬가지입니다.

```sql
  PRIMARY KEY (id, logged_at),
  UNIQUE KEY uk_email (email)      -- 파티션 키(logged_at) 미포함
```
```
ERROR 1503 (HY000): A UNIQUE INDEX must include all columns in the table's partitioning function ...
```

> ⚠️ **함정** — `users` 테이블을 `created_at` 으로 파티션하려면 `UNIQUE(email)` 을
> `UNIQUE(email, created_at)` 으로 바꿔야 합니다. 그런데 그건 **"이메일 중복 방지"가 깨진다**는 뜻입니다.
> (같은 이메일이 다른 날짜로 여러 번 들어올 수 있게 됨.)
> **비즈니스 유니크 제약이 있는 테이블은 애초에 파티셔닝 후보가 아닙니다.**
> 로그/이벤트/이력처럼 **유니크 제약이 PK 하나뿐인 테이블**이 파티셔닝의 자리입니다.

### (2) 외래 키(FK)를 쓸 수 없다

```sql
CREATE TABLE s21_bad_fk (
  ...,
  CONSTRAINT fk_s21 FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB PARTITION BY RANGE COLUMNS(logged_at) (...);
```

**결과**
```
ERROR 1506 (HY000): Foreign keys are not yet supported in conjunction with partitioning
```

파티션 테이블은 FK 를 **걸 수도 없고, 남이 자기를 참조하게 할 수도 없습니다.** 양방향 모두 금지입니다.

### (3) 파티션 식은 결정적(deterministic)이어야 한다

```sql
PARTITION BY RANGE (DATEDIFF(NOW(), d)) (PARTITION p0 VALUES LESS THAN (30));
```
```
ERROR 1064 (42000): Constant, random or timezone-dependent expressions in (sub)partitioning function
                    are not allowed near 'DATEDIFF(NOW(), d)) ...'
```

"최근 30일" 같은 **움직이는 기준**의 파티션은 원리상 만들 수 없습니다. 시간이 흐르면 행이 파티션을 옮겨다녀야 하니까요.
날짜 파티션은 항상 **고정된 경계값**(`'2024-02-01'`)으로 정의하고, 새 파티션을 **주기적으로 추가**하는 방식이어야 합니다.

### (4) MySQL 8.0 에서 없어진 것들

```sql
CREATE TABLE s21_bad_myisam (...) ENGINE=MyISAM PARTITION BY RANGE COLUMNS(d) (...);
```
```
ERROR 1178 (42000): The storage engine for the table doesn't support native partitioning
```

| 5.7 까지 | 8.0 |
|---|---|
| 서버가 제공하는 **범용 파티셔닝 핸들러**로 MyISAM 등 아무 엔진이나 파티션 가능 | **삭제됨.** 스토리지 엔진이 **네이티브 파티셔닝**을 지원해야만 가능 → 사실상 **InnoDB 전용** |
| `ALTER TABLE ... ANALYZE/CHECK/OPTIMIZE/REPAIR PARTITION` 이 비InnoDB에서도 동작 | InnoDB 네이티브 파티셔닝 기준으로 재구현 |
| (참고) `query_cache` 존재 | **8.0 에서 완전 제거** — 파티션 테이블의 쿼리 캐시 관련 이슈 자체가 사라짐 |

> 💡 **실무 팁** — 5.7 에서 8.0 으로 업그레이드할 때 **MyISAM 파티션 테이블이 하나라도 있으면 업그레이드가 막힙니다.**
> 업그레이드 전에 `ALTER TABLE ... ENGINE=InnoDB` 로 바꾸거나 파티션을 제거해야 합니다.

---

## 21-7. 파티션 관리 DDL

### ADD PARTITION — MAXVALUE 가 있으면 실패한다

```sql
ALTER TABLE s21_access_logs
  ADD PARTITION (PARTITION p2025_01 VALUES LESS THAN ('2025-02-01'));
```

**결과**
```
ERROR 1493 (HY000): VALUES LESS THAN value must be strictly increasing for each partition
```

`ADD PARTITION` 은 **맨 뒤에만** 붙일 수 있는데, 맨 뒤가 `MAXVALUE` 라서 그보다 큰 값을 만들 수 없습니다.

### REORGANIZE PARTITION — MAXVALUE 를 쪼개는 것이 정석

```sql
ALTER TABLE s21_access_logs REORGANIZE PARTITION pmax INTO (
  PARTITION p2025_01 VALUES LESS THAN ('2025-02-01'),
  PARTITION p2025_02 VALUES LESS THAN ('2025-03-01'),
  PARTITION pmax     VALUES LESS THAN (MAXVALUE)
);
```

**결과**
```
Query OK, 0 rows affected (0.055 sec)
```

`pmax` 가 비어 있으므로 데이터 이동이 없어 순식간에 끝납니다.
**`pmax` 를 항상 비워두는 것**(= 미래 파티션을 미리 만들어 두는 것)이 운영의 핵심입니다.

### 병합도 REORGANIZE — 단, 전체 범위를 바꾸면 안 된다

```sql
-- 실패: p2025_02 의 상한(2025-03-01)을 2025-07-01 로 늘리려 함. 뒤에 pmax 가 있어서 마지막이 아님
ALTER TABLE s21_access_logs REORGANIZE PARTITION p2025_01, p2025_02 INTO (
  PARTITION p2025_q1 VALUES LESS THAN ('2025-07-01')
);
```
```
ERROR 1520 (HY000): Reorganize of range partitions cannot change total ranges
                    except for last partition where it can extend the range
```

```sql
-- 성공: 상한을 그대로(2025-03-01) 유지
ALTER TABLE s21_access_logs REORGANIZE PARTITION p2025_01, p2025_02 INTO (
  PARTITION p2025_q1 VALUES LESS THAN ('2025-03-01')
);
```
```
Query OK, 0 rows affected (0.013 sec)
```

### TRUNCATE PARTITION — 구조는 남기고 데이터만 비우기

```sql
ALTER TABLE s21_access_logs TRUNCATE PARTITION p2025_q1;
```
```
Query OK, 0 rows affected (0.008 sec)
```

---

## 21-8. 【핵심】 오래된 로그 삭제 : DELETE vs DROP PARTITION

**파티셔닝을 도입하는 가장 큰 이유**입니다. 완전히 같은 **92,358행**을 지워봅시다.

```sql
-- (1) 비파티션 테이블: 행을 하나씩 지운다. 언두 로그, 리두 로그, 인덱스 갱신 전부 발생
DELETE FROM s21_access_logs_np WHERE logged_at < '2024-02-01';

-- (2) 파티션 테이블: 테이블스페이스 파일을 통째로 버린다
ALTER TABLE s21_access_logs DROP PARTITION p2024_01;
```

**결과**
```
DELETE FROM s21_access_logs_np WHERE logged_at < '2024-02-01'
Query OK, 92358 rows affected (0.244 sec)

ALTER TABLE s21_access_logs DROP PARTITION p2024_01
Query OK, 0 rows affected (0.007 sec)
```

**0.244초 vs 0.007초 — 약 35배.** 그런데 진짜 차이는 시간이 아닙니다.

| | `DELETE` | `DROP PARTITION` |
|---|---|---|
| 소요 시간 | 0.244초 (92k행 기준. **1억 행이면 수 시간**) | 0.007초 (**행수와 거의 무관**) |
| 언두/리두 로그 | 지운 행 전부만큼 생성 | 거의 없음 (DDL) |
| 복제 지연 | 레플리카에서 그대로 재실행 → **지연 폭발** | 거의 없음 |
| 잠금 | 행 잠금 대량 발생 → 다른 트랜잭션 대기 | 짧은 메타데이터 락 |
| 디스크 반환 | **안 줄어든다** (`DATA_FREE` 로 남음) | **즉시 OS 에 반환** |

디스크 확인:

```sql
SELECT TABLE_NAME,
       ROUND((DATA_LENGTH + INDEX_LENGTH)/1024/1024, 1) AS total_mb,
       ROUND(DATA_FREE/1024/1024, 1)                    AS data_free_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME IN ('s21_access_logs','s21_access_logs_np');
```

**결과**
```
+--------------------+----------+--------------+
| TABLE_NAME         | total_mb | data_free_mb |
+--------------------+----------+--------------+
| s21_access_logs    |     98.6 |         44.0 |
| s21_access_logs_np |     82.2 |          6.0 |
+--------------------+----------+--------------+
```

> ⚠️ **함정** — 대량 `DELETE` 는 디스크를 **되돌려주지 않습니다.** 빈 공간(`DATA_FREE`)으로 남아 재사용될 뿐입니다.
> 실제로 파일을 줄이려면 `OPTIMIZE TABLE`(= 테이블 전체 재작성)을 돌려야 하는데, 이건 **더 무겁습니다.**
> `DROP PARTITION` 은 이 문제 자체가 없습니다.

> 💡 **실무 팁 — 로그 테이블 운영 루틴**
> 1. 매월 1일: 다음 달 파티션을 `REORGANIZE PARTITION pmax INTO (...)` 로 미리 만든다
> 2. 같은 날: 보관 기간(예: 13개월)이 지난 파티션을 `DROP PARTITION` 한다
> 3. 이 두 줄을 [Step 20 의 `EVENT`](../step-20-stored-programs/index.md) 로 자동화한다
>
> 파티션 이름을 `p2024_01` 처럼 **정렬 가능한 규칙**으로 지으면 `information_schema.PARTITIONS` 에서
> 오래된 파티션 목록을 SQL 로 뽑아 동적 DDL 을 만들 수 있습니다.

---

## 21-9. EXCHANGE PARTITION — 버리기 전에 빼돌리기

법적 보관 의무 때문에 "지우기 전에 따로 보관"해야 할 때가 있습니다.
`EXCHANGE PARTITION` 은 **파티션 하나와 일반 테이블 하나를 통째로 맞바꿉니다.** (데이터 복사 없음, 메타데이터만 교환)

```sql
-- 교환 대상은 "파티션되지 않은, 컬럼/인덱스가 완전히 동일한" 테이블이어야 한다
CREATE TABLE s21_archive_2024_03 ( ... s21_access_logs 와 동일한 정의, PARTITION BY 절만 제외 ... );

ALTER TABLE s21_access_logs
  EXCHANGE PARTITION p2024_03 WITH TABLE s21_archive_2024_03;

SELECT COUNT(*) AS moved_to_archive  FROM s21_archive_2024_03;
SELECT COUNT(*) AS left_in_partition FROM s21_access_logs PARTITION (p2024_03);
```

**결과**
```
Query OK, 0 rows affected (0.008 sec)      ← 92,359행이 0.008초 만에 이동

+------------------+        +-------------------+
| moved_to_archive |        | left_in_partition |
+------------------+        +-------------------+
|            92359 |        |                 0 |
+------------------+        +-------------------+
```

이제 빈 파티션을 안심하고 버립니다.

```sql
ALTER TABLE s21_access_logs DROP PARTITION p2024_03;
```

---

## 정리

| 항목 | 내용 |
|---|---|
| **도입 이유 1순위** | `DROP PARTITION` 으로 오래된 데이터를 **즉시** 버리기 (DELETE 대비 35배+, 디스크 즉시 반환) |
| **도입 이유 2순위** | 넓은 날짜 범위 조회에서 **스캔량 자체**를 줄이기 (프루닝) |
| **도입하면 안 되는 경우** | 단일 행 조회가 주력 / 파티션 키가 WHERE 에 안 들어옴 / 비즈니스 유니크 제약이 있음 / FK 가 필요함 |
| **RANGE** | 정수 파티션 식. 날짜는 `TO_DAYS()` 등으로 정수화. `MAXVALUE` 안전망 필수 |
| **RANGE COLUMNS** | 컬럼을 그대로 비교. 날짜 파티션의 **1순위 선택** |
| **LIST** | 유한한 열거값. `MAXVALUE` 가 없어 미정의 값은 INSERT 실패(ERROR 1526) |
| **HASH / KEY** | 균등 분산. 등치 조건에서만 프루닝. 파티션 수 변경 = 전체 재분배 |
| **최대 제약 1** | 모든 유니크 키/PK 는 파티션 키를 **전부 포함**해야 함 (ERROR 1503) |
| **최대 제약 2** | **FK 불가** (ERROR 1506), 양방향 모두 |
| **8.0 변경점** | 네이티브 파티셔닝만 지원 → **InnoDB 전용**. MyISAM 파티션은 ERROR 1178 |
| **확인 방법** | `EXPLAIN` 의 `partitions` 컬럼. `information_schema.PARTITIONS` |
| **관리 DDL** | `REORGANIZE`(pmax 쪼개기) → `DROP PARTITION`(버리기) → `EXCHANGE PARTITION`(아카이빙) |

---

## 연습문제

`exercise.sql` 을 열어 직접 풀어 보세요. 정답은 `solution.sql` 에 있습니다.

1. `s21_access_logs` 에서 2024년 5월 데이터만 세는 쿼리를 쓰고, `EXPLAIN` 으로 **파티션 1개만** 열리는지 확인하라.
2. `WHERE DATE(logged_at) = '2024-05-05'` 는 프루닝이 되는가? 안 된다면 되게 고쳐라.
3. `status_code` 를 LIST 파티션 키로 하는 테이블 `s21_ex_status` 를 만들어라 (성공 2xx/3xx, 클라이언트 4xx, 서버 5xx 3개 파티션). `status_code = 200` 조회 시 프루닝을 확인하라.
4. `s21_access_logs` 의 `pmax` 를 쪼개서 2025년 1분기 파티션 3개(`p2025_01`~`p2025_03`)와 `pmax` 를 만들어라.
5. 2024년 4월 파티션을 아카이브 테이블로 빼낸 뒤 파티션을 삭제하라. 삭제 전후의 전체 행수를 비교하라.
6. `customers` 테이블을 `created_at` 으로 파티셔닝하려고 한다. **왜 불가능한가**(혹은 무엇을 포기해야 하는가)? SQL 로 증명하라.

---

## 다음 단계

→ [Step 22 — 사용자와 보안](../step-22-users-security/index.md)

---

## 실습 파일

이 스텝은 SQL 스크립트 세 개로 구성됩니다. 먼저 `practice.sql` 을 실행해 파티션 실습 테이블(`s21_access_logs`)과 비교군(`s21_access_logs_np`)을 만들고 21-1 ~ 21-9 의 모든 예제를 직접 재현합니다. 그다음 `exercise.sql` 의 TODO 를 채워 연습문제를 풀고, 마지막으로 `solution.sql` 로 답을 맞춰 봅니다. **세 파일 모두 `practice.sql` → `exercise.sql` → `solution.sql` 순서에 의존**하므로 순서를 지켜 주세요.

### practice.sql

강의 본문 21-1 ~ 21-9 를 그대로 따라가는 메인 실습 스크립트입니다.

- **실행 방법이 특이합니다.** 헤더 주석에 있듯 `mysql ... -t --force < practice.sql` 로 실행합니다. `--force` 를 붙이는 이유는 이 스크립트가 **일부러 실패하는 SQL**(21-6 의 제약 위반 5종)을 포함하고 있어서, 에러가 나도 멈추지 않고 끝까지 진행하게 하기 위함입니다. 에러 메시지를 눈으로 보는 것 자체가 학습 포인트입니다.
- 21-1 에서 `access_logs`(100만 행)를 `s21_access_logs` 로 복제하는데, PK 가 `PRIMARY KEY (log_id, logged_at)` 입니다. `log_id` 단독이 아닌 이유는 **모든 유니크 키가 파티션 키(`logged_at`)를 포함해야 한다**는 제약 때문입니다. 여기서 어기면 뒤에 나오는 ERROR 1503 을 자기 테이블에서 만나게 됩니다.
- 비교군 `s21_access_logs_np` 는 `CREATE TABLE ... LIKE access_logs` + `ADD KEY idx_np_logged_at (logged_at)` 로 만듭니다. **같은 데이터, 같은 인덱스, 파티션만 없음** — 21-4 의 실측 비교를 공정하게 하기 위한 장치입니다.
- 21-6 의 다섯 블록(`s21_bad_uk`, `s21_bad_uk2`, `s21_bad_fk`, `s21_bad_expr`, `s21_bad_myisam`)은 **전부 의도적으로 실패하는 DDL** 입니다. 각각 ERROR 1503(PK 에 파티션 키 누락) / ERROR 1503(UNIQUE 에 파티션 키 누락) / ERROR 1506(FK 불가) / ERROR 1064(`DATEDIFF(NOW(), d)` 는 비결정적) / ERROR 1178(MyISAM 은 네이티브 파티셔닝 미지원)이 납니다. 테이블이 만들어지지 않는 것이 **정상**입니다.
- 21-7 의 `ADD PARTITION` (a) 도 일부러 실패하는 문장이라 ERROR 1493 이 납니다. 맨 뒤가 `pmax`(MAXVALUE)라 그보다 큰 경계를 붙일 수 없기 때문이며, 바로 아래 (b) `REORGANIZE PARTITION pmax INTO (...)` 가 정석 해법으로 이어집니다. (c) 의 첫 번째 `REORGANIZE` 역시 전체 범위를 늘리려 해서 ERROR 1520 이 나고, 상한을 `'2025-03-01'` 로 유지한 두 번째 문장만 성공합니다.
- ⚠️ **파괴적 & 순서 의존**: 21-8 은 `ALTER TABLE s21_access_logs DROP PARTITION p2024_01`, 21-9 는 `EXCHANGE PARTITION p2024_03` 후 `DROP PARTITION p2024_03` 을 실행합니다. 즉 **스크립트를 한 번 돌리고 나면 `p2024_01` 과 `p2024_03` 파티션이 사라진 상태**가 되고, `s21_access_logs_np` 에서는 2024-02-01 이전 92,358행이 `DELETE` 됩니다. `exercise.sql` 이 이 상태를 전제로 하므로 그대로 두면 됩니다. 맨 끝 21-10 의 뒷정리 `DROP TABLE` 은 주석 처리돼 있으니, 다시 처음부터 하고 싶을 때만 풀어서 쓰세요.
- 공용 테이블(`access_logs`, `customers`)은 `SELECT` 만 하고, 실습 테이블은 전부 `s21_` 접두사를 씁니다. 다른 스텝의 데이터를 건드리지 않는 안전 장치입니다.

```sql file="./practice.sql"
```

### exercise.sql

위 [연습문제](#연습문제) 6문항의 실습 틀입니다. `practice.sql` 을 **먼저 실행한 뒤**에 열어야 합니다.

- 문제 1·2 는 프루닝 확인용입니다. 특히 문제 2 의 `EXPLAIN SELECT COUNT(*) FROM s21_access_logs WHERE DATE(logged_at) = '2024-05-05';` 는 **일부러 프루닝이 깨지는 쿼리**로 미리 실행되게 두었습니다. `partitions` 컬럼에 전 파티션이 나열되는 것을 눈으로 확인한 뒤, 반열린 구간(`>= '2024-05-05' AND < '2024-05-06'`)으로 고쳐 쓰는 것이 과제입니다.
- 문제 3 은 LIST 파티션을 직접 만드는 문제입니다. `SELECT DISTINCT status_code FROM access_logs ORDER BY status_code;` 를 먼저 돌려보게 해 둔 이유는, LIST 에는 `MAXVALUE` 안전망이 없어서 열거하지 않은 값이 하나라도 들어오면 `INSERT` 전체가 ERROR 1526 으로 죽기 때문입니다.
- 문제 4 의 힌트 두 줄이 핵심입니다. `ADD PARTITION` 은 `pmax` 때문에 ERROR 1493 이 나고, 게다가 `practice.sql` 이 `p2025_q1`(< `'2025-03-01'`)을 남겨 뒀기 때문에 그냥 `REORGANIZE` 만 하면 경계가 역행해 또 ERROR 1493 이 납니다. **먼저 `p2025_q1` 을 치워야** 한다는 것이 이 문제의 함정입니다.
- 문제 5 는 `EXCHANGE PARTITION` → `DROP PARTITION` 2단 콤보를, 문제 6 은 `customers` 의 `uk_customers_email` 때문에 `created_at` 파티셔닝이 불가능함을 SQL 로 증명하는 문제입니다. 문제 6 은 반드시 `s21_ex_customers` 로 복제해서 시도하세요 — 공용 `customers` 테이블은 건드리면 안 됩니다.
- 실행 시에도 `--force` 를 붙입니다. 문제 2·6 처럼 실패를 관찰하는 문장이 있기 때문입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 6문항의 정답과 해설입니다. 스스로 풀어본 뒤에 열어 보세요.

- 정답 2 는 "파티션 키에 함수를 씌우는 순간 프루닝은 죽는다"는 원칙과, 예외가 `TO_DAYS` / `YEAR` / `TO_SECONDS` **뿐이며 그것도 파티션 식이 같은 함수로 정의됐을 때만**이라는 단서를 못 박습니다.
- 정답 3 은 `access_logs` 의 실제 `status_code` 가 200/304/400/404/500/503 여섯 종류임을 밝히고, 그럼에도 `VALUES IN (200, 201, 204, 301, 302, 304)` 처럼 **여유 있게 열거**합니다. 보너스로 `WHERE status_code >= 400` 이 `p_client,p_server` 두 파티션으로 프루닝되는 것을 보여줍니다 — LIST 도 범위 조건에서 프루닝이 됩니다.
- 정답 4 는 `ALTER TABLE s21_access_logs DROP PARTITION p2025_q1;` 을 **먼저** 실행한 뒤 `REORGANIZE PARTITION pmax INTO (...)` 로 2025년 1~3월 + `pmax` 를 만듭니다. 순서가 바뀌면 ERROR 1493 입니다.
- 정답 5 의 `s21_ex_archive_04` 는 `s21_access_logs` 와 컬럼·인덱스가 **완전히 동일**하되 `PARTITION BY` 절만 없습니다. `EXCHANGE PARTITION` 의 필수 조건이라 한 컬럼이라도 다르면 교환이 거부됩니다. 검산은 `before_rows - after_rows = archived` 로 합니다.
- 정답 6 이 이 스텝의 백미입니다. 먼저 `UNIQUE KEY uk_email (email)` 로 ERROR 1503 을 재현한 뒤, `UNIQUE KEY uk_email (email, created_at)` 로 "포기하면 되긴 된다"를 보여주고, 곧바로 같은 이메일 `dup@example.com` 을 다른 날짜로 두 번 넣어 **중복 가입이 성공해 버리는 것**을 실증합니다. 비즈니스 유니크 제약이 있는 테이블은 애초에 파티셔닝 후보가 아니라는 결론의 근거입니다.

```sql file="./solution.sql"
```
