# Step 11 — DML (INSERT / UPDATE / DELETE)

> **학습 목표**
> - `INSERT`(단일·다중·`INSERT ... SELECT`)와 마지막 ID 얻는 법(`LAST_INSERT_ID`)을 익힌다
> - JOIN UPDATE / 다중 테이블 UPDATE, JOIN DELETE / 다중 테이블 DELETE 를 쓴다
> - **UPSERT** 두 방식(`ON DUPLICATE KEY UPDATE` vs `REPLACE`)의 결정적 차이를 안다
> - `INSERT IGNORE` 가 **무엇을 삼키는지**, `TRUNCATE` vs `DELETE` 차이를 안다
> - 안전 모드(`sql_safe_updates`)와 `LOAD DATA` 를 안다
>
> **선행 스텝**: Step 10 (집합 연산)
> **예상 소요**: 60분

---

## 11-0. ⚠️ 먼저 읽으세요 : 원본을 건드리지 않습니다

이 스텝은 데이터를 **바꾸는** 실습입니다. 그런데 이 `shop` DB는 **여러 학습자가 동시에** 사용합니다. 누군가 `orders` 를 `UPDATE` 하거나 `TRUNCATE` 하면, 앞선 Step 들의 예제 결과가 그 사람 화면에서 전부 어긋납니다.

그래서 이 스텝의 모든 실습은 **`s11_` 접두사가 붙은 작업 사본**에서만 합니다. 공용 테이블(customers/products/orders/order_items/payments/reviews/categories/employees/tally)에는 **INSERT/UPDATE/DELETE/TRUNCATE/DROP/ALTER 를 절대 하지 않습니다.**

```sql
-- LIKE 는 구조(인덱스·제약 포함)만 복사한다
CREATE TABLE s11_orders LIKE orders;
-- 데이터는 INSERT ... SELECT 로 채운다
INSERT INTO s11_orders SELECT * FROM orders;
```

실습이 끝나면 맨 끝의 정리 스크립트(`cleanup.sql`)로 사본을 모두 지웁니다.

> ⚠️ **함정 (스크립트 실행 시)**: `ROW_COUNT()` 와 `LAST_INSERT_ID()` 는 **바로 직전 문장**의 결과를 돌려줍니다. DML 과 이 함수 호출 사이에 **다른 SELECT 가 하나라도 끼면 값이 초기화**됩니다. 게다가 `mysql` 클라이언트로 `.sql` 파일을 실행하면 **주석 한 줄조차 빈 문장으로 서버에 전달되어** `ROW_COUNT()` 를 0으로 리셋합니다. 그래서 `practice.sql` 에서는 측정 SELECT 를 DML 바로 뒤에 붙여 두었습니다.

---

## 11-1. INSERT

### 단일 행 — 컬럼 목록을 반드시 쓰자

```sql
INSERT INTO s11_customers (email, name, phone, grade, birth_date, city, points)
VALUES ('new.user1@example.com', '신규일', '010-9000-0001', 'BRONZE', '1995-05-05', '서울', 0);
SELECT LAST_INSERT_ID() AS new_id;
```

**결과**
```
+--------+
| new_id |
+--------+
|     31 |
+--------+
```

`INSERT INTO s11_customers VALUES (...)` 처럼 컬럼 목록을 생략하면, 나중에 테이블에 컬럼이 하나 추가되는 순간 모든 INSERT 문이 깨집니다. **항상 컬럼을 명시**하세요.

### MySQL에는 RETURNING이 없다 → LAST_INSERT_ID()

PostgreSQL 은 `INSERT ... RETURNING id` 로 방금 생성된 키를 돌려받습니다. MySQL 8.0.46 에는 그런 문법이 **없습니다**(MariaDB 에는 있습니다). 대신 `LAST_INSERT_ID()` 를 씁니다.

```sql
SELECT customer_id, email, name, grade FROM s11_customers WHERE customer_id = LAST_INSERT_ID();
```

**결과**
```
+-------------+-----------------------+-----------+--------+
| customer_id | email                 | name      | grade  |
+-------------+-----------------------+-----------+--------+
|          31 | new.user1@example.com | 신규일    | BRONZE |
+-------------+-----------------------+-----------+--------+
```

`LAST_INSERT_ID()` 는 **연결(세션)별로 관리**됩니다. 다른 사람이 동시에 INSERT 해도 내 값은 안전합니다.

### 다중 행 — 한 문장으로 여러 행

```sql
INSERT INTO s11_customers (email, name, grade, city, points) VALUES
  ('new.user2@example.com', '신규이', 'SILVER', '부산', 100),
  ('new.user3@example.com', '신규삼', 'GOLD',   '대구', 200),
  ('new.user4@example.com', '신규사', 'VIP',    '인천', 300);
SELECT LAST_INSERT_ID() AS first_of_batch, ROW_COUNT() AS inserted_rows;
```

**결과**
```
+----------------+---------------+
| first_of_batch | inserted_rows |
+----------------+---------------+
|             32 |             3 |
+----------------+---------------+
```

> 💡 **실무 팁**: 다중 행 INSERT 는 행마다 따로 INSERT 하는 것보다 **훨씬 빠릅니다**(네트워크 왕복과 트랜잭션 오버헤드가 1회로 줄어듦). 단, `LAST_INSERT_ID()` 는 배치의 **첫 번째** 행 ID 를 돌려줍니다. AUTO_INCREMENT 는 연속이므로 나머지는 `first + 1`, `first + 2` … 로 계산할 수 있습니다.

### INSERT ... SELECT — 다른 테이블에서 적재

```sql
INSERT INTO s11_stock_feed (product_id, stock)
SELECT product_id, stock + 10
FROM s11_products
WHERE category_id IN (21, 22);
```

**결과** (`SELECT * FROM s11_stock_feed`)
```
+------------+-------+---------------------+
| product_id | stock | updated_at          |
+------------+-------+---------------------+
|         12 |    28 | 2026-07-13 10:45:38 |
|         13 |    19 | 2026-07-13 10:45:38 |
|         14 |    16 | 2026-07-13 10:45:38 |
|         15 |    50 | 2026-07-13 10:45:38 |
|         16 |    60 | 2026-07-13 10:45:38 |
+------------+-------+---------------------+
... (총 7행)
```

---

## 11-2. UPSERT : ON DUPLICATE KEY UPDATE vs REPLACE

"있으면 수정, 없으면 삽입"(UPSERT)을 MySQL 은 두 방식으로 제공합니다. **이 둘은 겉보기엔 비슷하지만 내부 동작이 완전히 다릅니다.**

### ON DUPLICATE KEY UPDATE — 진짜 UPSERT

PRIMARY KEY 나 UNIQUE 제약과 충돌하면 INSERT 대신 지정한 UPDATE 를 수행합니다.

```sql
-- product_id=12 는 이미 있고(→UPDATE), product_id=1 은 없다(→INSERT)
INSERT INTO s11_stock_feed (product_id, stock) VALUES (12, 999), (1, 555)
ON DUPLICATE KEY UPDATE
    stock      = VALUES(stock),
    updated_at = NOW();
SELECT ROW_COUNT() AS affected;
```

**결과**
```
+----------+
| affected |
+----------+
|        3 |
+----------+
```

> 💡 `ROW_COUNT()` 규칙: **INSERT 는 1, (값이 바뀐) UPDATE 는 2, 값이 그대로면 0**. 여기선 INSERT 1건(=1) + UPDATE 1건(=2) → 3. 이 "UPDATE가 2로 세어진다"는 점 때문에 affected 값만 보고 실제 행 수를 판단하면 틀립니다.

MySQL 8.0.19 부터 `VALUES()` 함수는 **deprecated** 되었습니다. 별칭 문법을 쓰세요.

```sql
INSERT INTO s11_stock_feed (product_id, stock) VALUES (13, 777) AS new
ON DUPLICATE KEY UPDATE
    stock      = new.stock,      -- VALUES(stock) 대신
    updated_at = NOW();
```

### REPLACE — 사실은 DELETE + INSERT

이름은 "replace"지만, REPLACE 는 UPDATE 가 아닙니다. 충돌하는 **기존 행을 DELETE 하고 새 행을 INSERT** 합니다. 이 차이가 사고를 부릅니다.

```sql
-- REPLACE 전
SELECT product_id, stock, updated_at FROM s11_stock_feed WHERE product_id = 12;
--   → stock=999, updated_at='...10:45:38'

REPLACE INTO s11_stock_feed (product_id, stock) VALUES (12, 111);
SELECT ROW_COUNT() AS replace_affected;
SELECT product_id, stock, updated_at FROM s11_stock_feed WHERE product_id = 12;
```

**결과**
```
+------------------+
| replace_affected |
+------------------+
|                2 |            ← DELETE 1 + INSERT 1 = 2
+------------------+
+------------+-------+---------------------+
| product_id | stock | updated_at          |
+------------+-------+---------------------+
|         12 |   111 | 2026-07-13 10:45:38 |   ← updated_at 이 "지금"으로 리셋됐다!
+------------+-------+---------------------+
```

> ⚠️ **함정 1 — VALUES에 없는 컬럼이 DEFAULT로 초기화된다**: REPLACE 는 기존 행을 통째로 지우므로, 이번 REPLACE 문에 명시하지 않은 컬럼(`updated_at`)은 원래 값을 잃고 **DEFAULT** 로 돌아갑니다. `ON DUPLICATE KEY UPDATE` 는 명시한 컬럼만 바꾸므로 이런 일이 없습니다.

> ⚠️ **함정 2 — AUTO_INCREMENT가 튀고, 자식 행이 삭제될 수 있다**:
> ```sql
> INSERT INTO s11_seq_demo (code, memo) VALUES ('A', '원본메모');   -- id=1
> REPLACE INTO s11_seq_demo (code) VALUES ('A');                    -- code='A' 충돌
> ```
> **결과**
> ```
> INSERT 직후 : id=1, memo='원본메모'
> REPLACE 후  : id=2, memo='기본메모'   ← id가 바뀌고 memo가 날아갔다
> ```
> `id` 가 1 → 2 로 바뀌었습니다. 만약 다른 테이블이 이 `id=1` 을 FK 로 참조하고 있고 그 FK가 `ON DELETE CASCADE` 라면, **REPLACE 한 번에 자식 행이 전부 삭제**됩니다. REPLACE 가 내부적으로 DELETE 를 하기 때문입니다.

**결론: 특별한 이유가 없으면 REPLACE 대신 `ON DUPLICATE KEY UPDATE` 를 쓰세요.** REPLACE 는 "행 전체를 새 것으로 갈아끼우는 게 정확히 내 의도일 때"만 씁니다.

| | ON DUPLICATE KEY UPDATE | REPLACE |
|---|---|---|
| 내부 동작 | INSERT 또는 UPDATE | DELETE + INSERT |
| 명시 안 한 컬럼 | 유지됨 | **DEFAULT 로 리셋** |
| AUTO_INCREMENT | 유지 | **소모(id 튐)** |
| 자식 FK(CASCADE) | 안전 | **삭제될 수 있음** |
| 트리거 | UPDATE 트리거 | DELETE + INSERT 트리거 |

---

## 11-3. ⚠️ INSERT IGNORE 가 삼키는 것들

`INSERT IGNORE` 는 오류를 **에러 대신 경고(warning)로 낮춥니다.** 중복 키를 건너뛰려고 쓰지만, **중복만 삼키는 게 아니라는 것**이 문제입니다.

```sql
INSERT IGNORE INTO s11_customers (email, name, grade, city)
VALUES ('new.user2@example.com', '중복이', 'BRONZE', '서울');   -- 이메일 UNIQUE 위반
SELECT ROW_COUNT() AS affected;
SHOW WARNINGS;
```

**결과**
```
+----------+
| affected |
+----------+
|        0 |            ← 조용히 0행 처리
+----------+
```

여기까진 의도한 대로입니다. 문제는 다음입니다.

```sql
CREATE TABLE s11_ignore_demo (
  id  INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  qty INT NOT NULL,
  nm  VARCHAR(5) NOT NULL
);
INSERT IGNORE INTO s11_ignore_demo (qty, nm) VALUES
  (100,  '정상'),
  (NULL, '널값'),               -- NOT NULL 위반
  (200,  '아주긴이름입니다');   -- VARCHAR(5) 초과
SHOW WARNINGS;
SELECT * FROM s11_ignore_demo;
```

**결과**
```
+---------+------+-----------------------------------------+
| Level   | Code | Message                                 |
+---------+------+-----------------------------------------+
| Warning | 1048 | Column 'qty' cannot be null             |
| Warning | 1265 | Data truncated for column 'nm' at row 3 |
+---------+------+-----------------------------------------+
+----+-----+-----------------+
| id | qty | nm              |
+----+-----+-----------------+
|  1 | 100 | 정상            |
|  2 |   0 | 널값            |   ← NULL 이 0 으로 둔갑
|  3 | 200 | 아주긴이름      |   ← 이름이 잘려서 저장됨
+----+-----+-----------------+
```

`NOT NULL` 위반은 **0으로**, 길이 초과는 **잘려서** 들어갔습니다. 그런데 문장은 "성공"했고, 애플리케이션은 아무 예외도 받지 못합니다. 잘못된 데이터가 조용히 쌓입니다.

> ⚠️ **함정**: `INSERT IGNORE` 는 "중복이면 건너뛴다"가 아니라 "**모든 종류의 오류를 경고로 낮춘다**"입니다. 정말 중복만 무시하고 싶다면, 차라리 `INSERT ... ON DUPLICATE KEY UPDATE id=id`(아무것도 안 바꾸는 no-op) 처럼 **의도를 좁혀** 쓰세요. IGNORE 없이 실행하면 `ERROR 1048: Column 'qty' cannot be null` 로 제대로 멈춥니다 — 그게 옳은 동작입니다.

---

## 11-4. UPDATE

### 기본

```sql
UPDATE s11_products
SET price = price * 1.1, stock = stock - 1
WHERE product_id = 1;
```

**결과** (`SELECT ... WHERE product_id = 1`)
```
+------------+-------------------------------+----------+-------+
| product_id | name                          | price    | stock |
+------------+-------------------------------+----------+-------+
|          1 | 베이직 옥스퍼드 셔츠          | 42900.00 |   119 |
+------------+-------------------------------+----------+-------+
```

### JOIN UPDATE (다중 테이블 조인 후 갱신)

한 테이블을 다른 테이블 조건으로 갱신할 때 씁니다. "GOLD 고객의 모든 주문 배송지를 제주로."

```sql
UPDATE s11_orders o
JOIN s11_customers c ON c.customer_id = o.customer_id
SET o.shipping_city = '제주'
WHERE c.grade = 'GOLD';
SELECT ROW_COUNT() AS updated;
```

**결과**
```
+---------+
| updated |
+---------+
|     160 |
+---------+
```

### 다중 테이블 UPDATE (두 테이블을 동시에 갱신)

`SET` 절에 여러 테이블의 컬럼을 나열하면 **한 문장으로 둘 다** 바꿉니다.

```sql
UPDATE s11_orders o
JOIN s11_customers c ON c.customer_id = o.customer_id
SET o.status  = 'CANCELLED',
    c.points  = c.points + 1000       -- 주문 취소 보상 포인트
WHERE o.order_id = 7;
```

**결과**
```
+----------+-----------+-------------+--------+
| order_id | status    | customer_id | points |
+----------+-----------+-------------+--------+
|        7 | CANCELLED |          30 |  10200 |
+----------+-----------+-------------+--------+
```

### 상관 서브쿼리 UPDATE

`03_seed_orders.sql` 이 `total_amount` 를 채운 방식과 같습니다. 주문 합계를 상세 합계로 재계산합니다.

```sql
UPDATE s11_orders o
SET o.total_amount = (
    SELECT COALESCE(SUM(oi.quantity * oi.unit_price), 0)
    FROM s11_order_items oi
    WHERE oi.order_id = o.order_id
)
WHERE o.order_id <= 5;
```

**결과**
```
+----------+--------------+
| order_id | total_amount |
+----------+--------------+
|        1 |   1836000.00 |
|        2 |   6663900.00 |
|        3 |    658000.00 |
|        4 |    837000.00 |
|        5 |   1194000.00 |
+----------+--------------+
```

---

## 11-5. 안전 모드 : sql_safe_updates

실수로 `UPDATE products SET stock = 0`(WHERE 없음!)을 실행하면 전 상품 재고가 0이 됩니다. 이런 참사를 막는 스위치가 `sql_safe_updates` 입니다.

```sql
SET SESSION sql_safe_updates = 1;

UPDATE s11_products SET stock = 0;
-- ERROR 1175 (HY000): You are using safe update mode and you tried to update
-- a table without a WHERE that uses a KEY column

UPDATE s11_products SET stock = 0 WHERE name LIKE '%셔츠%';
-- ERROR 1175 ... (name 은 키가 아니라 전체 스캔 → 역시 차단)
```

`WHERE` 가 있어도 **키(인덱스) 컬럼을 쓰지 않으면** 막습니다(전체 스캔 = 사실상 전체 갱신 위험). 키를 쓰거나 `LIMIT` 을 붙이면 통과합니다.

```sql
UPDATE s11_products SET stock = stock + 1 WHERE product_id = 1;      -- PK 사용 → OK
SELECT ROW_COUNT() AS ok_with_key;

UPDATE s11_products SET stock = stock + 1 WHERE name LIKE '%셔츠%' LIMIT 10;  -- LIMIT → OK
SELECT ROW_COUNT() AS ok_with_limit;

SET SESSION sql_safe_updates = 0;
```

**결과**
```
+-------------+       +---------------+
| ok_with_key |       | ok_with_limit |
+-------------+       +---------------+
|           1 |       |             1 |
+-------------+       +---------------+
```

> 💡 **실무 팁**: 운영 DB에 붙는 콘솔/툴에는 **항상 `sql_safe_updates` 를 켜 두세요.** `mysql` 클라이언트라면 `--safe-updates`(또는 `-U`) 옵션으로 접속하면 됩니다. WHERE 를 깜빡한 단 한 번의 실수를 막아 줍니다.

---

## 11-6. DELETE / TRUNCATE

### 기본 DELETE

```sql
DELETE FROM s11_order_items WHERE order_id = 1;
SELECT ROW_COUNT() AS deleted;
```

**결과**
```
+---------+
| deleted |
+---------+
|       2 |
+---------+
```

### JOIN DELETE — 삭제 대상을 명시한다

조인 결과에서 **어느 테이블의 행을 지울지**를 `DELETE` 와 `FROM` 사이에 별칭으로 적습니다. 이걸 빠뜨리면 문법 에러입니다.

```sql
DELETE oi                                    -- ← oi 만 삭제 (o 는 조건용)
FROM s11_order_items oi
JOIN s11_orders o ON o.order_id = oi.order_id
WHERE o.status = 'CANCELLED';
SELECT ROW_COUNT() AS deleted_items;
```

**결과**
```
+---------------+
| deleted_items |
+---------------+
|           122 |
+---------------+
```

### 다중 테이블 DELETE — 부모와 자식을 한 번에

```sql
DELETE o, oi                                 -- ← 둘 다 삭제
FROM s11_orders o
LEFT JOIN s11_order_items oi ON oi.order_id = o.order_id
WHERE o.status = 'PENDING';
SELECT ROW_COUNT() AS deleted_rows;
```

**결과**
```
+--------------+
| deleted_rows |
+--------------+
|          177 |
+--------------+
```

> 💡 **실무 팁**: 원본 스키마처럼 FK에 `ON DELETE CASCADE` 가 걸려 있으면 부모(`orders`)만 지워도 자식(`order_items`)이 따라 삭제됩니다. 다중 테이블 DELETE 는 CASCADE 가 없거나, 삭제 순서를 명시적으로 통제하고 싶을 때 유용합니다.

### TRUNCATE vs DELETE

둘 다 "행을 전부 지운다"지만 성질이 다릅니다.

```sql
INSERT INTO s11_trunc_demo (v) VALUES (1),(2),(3);

DELETE FROM s11_trunc_demo;               -- DML
INSERT INTO s11_trunc_demo (v) VALUES (9);
-- → id = 4  (AUTO_INCREMENT 이어서 증가)

TRUNCATE TABLE s11_trunc_demo;            -- DDL
INSERT INTO s11_trunc_demo (v) VALUES (9);
-- → id = 1  (AUTO_INCREMENT 리셋)
```

**결과**
```
after DELETE   : id = 4
after TRUNCATE : id = 1
```

`TRUNCATE` 는 **DDL** 이라 실행하는 순간 **암묵적 커밋**이 일어납니다. 트랜잭션으로 감싸도 **롤백되지 않습니다.**

```sql
START TRANSACTION;
INSERT INTO s11_trunc_demo (v) VALUES (100);
ROLLBACK;
SELECT COUNT(*) AS after_rollback_of_insert FROM s11_trunc_demo;    -- 1 (INSERT 는 롤백됨)

START TRANSACTION;
TRUNCATE TABLE s11_trunc_demo;   -- ← 여기서 암묵적 COMMIT
ROLLBACK;
SELECT COUNT(*) AS after_rollback_of_truncate FROM s11_trunc_demo;  -- 0 (되돌릴 수 없다)
```

**결과**
```
after_rollback_of_insert   : 1
after_rollback_of_truncate : 0
```

| | DELETE | TRUNCATE |
|---|---|---|
| 종류 | DML | DDL |
| WHERE | 가능 | 불가(전체만) |
| 롤백 | 가능 | **불가(암묵 커밋)** |
| AUTO_INCREMENT | 유지 | **리셋** |
| 속도(전체 삭제) | 느림(행마다) | 빠름(테이블 재생성) |
| 트리거 | 발동 | 발동 안 함 |

> ⚠️ **함정**: "전체 삭제니까 TRUNCATE 가 빠르지" 하고 운영에서 무심코 썼다가, 트랜잭션 롤백을 기대할 수 없어 사고가 커지는 경우가 있습니다. **되돌릴 여지가 필요하면 `DELETE`**, 확실히 비우고 초기화할 거면 `TRUNCATE`.

---

## 11-7. LOAD DATA — 대량 적재

CSV 같은 파일을 통째로 적재할 때는 `INSERT` 를 수천 번 날리는 것보다 `LOAD DATA` 가 훨씬 빠릅니다. 클라이언트 파일이면 `LOCAL` 을 붙입니다(클라이언트 `--local-infile=1`, 서버 `local_infile=ON` 필요).

```sql
-- 셸에서 CSV 준비: printf '9001,CSV상품A,10000,5000,10\n9002,CSV상품B,20000,9000,20\n' > /tmp/s11_products.csv
LOAD DATA LOCAL INFILE '/tmp/s11_products.csv'
INTO TABLE s11_load_demo
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES  TERMINATED BY '\n'
(product_id, name, price, cost, stock);
```

**결과**
```
+------------+------------+----------+---------+-------+
| product_id | name       | price    | cost    | stock |
+------------+------------+----------+---------+-------+
|       9001 | CSV상품A   | 10000.00 | 5000.00 |    10 |
|       9002 | CSV상품B   | 20000.00 | 9000.00 |    20 |
+------------+------------+----------+---------+-------+
```

> ⚠️ **함정**: `LOAD DATA LOCAL` 은 보안 이슈로 기본 비활성일 수 있습니다. `ERROR 3948: Loading local data is disabled` 가 나면 클라이언트 옵션(`--local-infile=1`)과 서버 변수(`local_infile`)를 둘 다 확인하세요. `LOCAL` 없이 서버 경로에서 읽으려면 `secure_file_priv` 디렉터리 제한도 걸립니다.

---

## 11-8. 정리 : 사본 삭제

실습이 끝나면 반드시 사본을 지웁니다. `practice.sql` 맨 끝에도, 별도 `cleanup.sql` 에도 들어 있습니다.

```sql
DROP TABLE IF EXISTS s11_order_items;
DROP TABLE IF EXISTS s11_orders;
DROP TABLE IF EXISTS s11_products;
DROP TABLE IF EXISTS s11_customers;
DROP TABLE IF EXISTS s11_stock_feed;
DROP TABLE IF EXISTS s11_seq_demo;
DROP TABLE IF EXISTS s11_ignore_demo;
DROP TABLE IF EXISTS s11_trunc_demo;
DROP TABLE IF EXISTS s11_load_demo;
```

---

## 정리

| 주제 | 핵심 |
|---|---|
| INSERT | 컬럼 목록 **항상 명시**. 다중 행이 빠름 |
| 마지막 ID | MySQL엔 RETURNING 없음 → **`LAST_INSERT_ID()`**(세션별, 배치의 첫 ID) |
| `ON DUPLICATE KEY UPDATE` | 진짜 UPSERT. 명시 컬럼만 변경. 8.0.19+ 는 `AS 별칭` |
| `REPLACE` | **DELETE + INSERT**. 미명시 컬럼 DEFAULT 리셋, AI 소모, 자식 CASCADE 위험 |
| `INSERT IGNORE` | 중복뿐 아니라 **NULL·길이초과 등 모든 오류를 경고로 삼킴** |
| JOIN UPDATE/DELETE | 대상 테이블(별칭)을 명확히. DELETE 는 `DELETE 별칭 FROM ...` |
| `ROW_COUNT()` | 직전 문장 결과. UPDATE 는 **변경된** 행 수(안 바뀌면 0) |
| `sql_safe_updates` | 키 없는 UPDATE/DELETE 차단. 운영 콘솔에선 **항상 ON** |
| TRUNCATE vs DELETE | TRUNCATE=DDL(롤백 불가·AI 리셋), DELETE=DML(롤백 가능) |
| LOAD DATA | 대량 적재 표준. `LOCAL` + `--local-infile=1` |

---

## 연습문제

`exercise.sql` 을 푸세요. 정답은 `solution.sql`. **모든 문제는 `s11_` 사본에서** 합니다.

1. 사본 `s11_products` 를 만들고, 신상품 2개를 다중 행 INSERT
2. `INSERT ... SELECT` 로 '재고 0인 상품'만 다른 사본 테이블에 복사
3. `ON DUPLICATE KEY UPDATE` 로 재고 UPSERT (있으면 누적, 없으면 삽입)
4. 같은 데이터를 `REPLACE` 로 넣고, 두 방식의 차이(updated_at/id)를 관찰
5. JOIN UPDATE 로 'BRONZE 고객 주문'의 배송지를 일괄 변경하고 ROW_COUNT 확인
6. JOIN DELETE 로 특정 상태 주문의 상세를 삭제
7. `sql_safe_updates=1` 상태에서 막히는 UPDATE 와 통과하는 UPDATE 를 각각 작성
8. `TRUNCATE` 와 `DELETE` 후 AUTO_INCREMENT 차이를 재현

---

## 다음 단계

→ [Step 12 — 내장 함수](../step-12-builtin-functions/index.md)

---

## 실습 파일

이 스텝의 SQL 파일은 네 개이며, **`practice.sql` → `exercise.sql` → `solution.sql` → `cleanup.sql`** 순서로 씁니다. 먼저 `practice.sql` 로 본문 11-1 ~ 11-8 의 예제를 그대로 재현하고, `exercise.sql` 의 빈칸을 직접 채워 푼 뒤 `solution.sql` 로 답을 맞춰 봅니다. 마지막에 `cleanup.sql` 로 `s11_` 작업 사본을 모두 지우고 끝냅니다. 네 파일 모두 **공용 테이블은 읽기만(`INSERT ... SELECT` 의 소스로만)** 사용하고, 변경은 오직 사본에서만 일어납니다.

### practice.sql

본문 예제를 위에서부터 그대로 실행하는 메인 실습 스크립트입니다. `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 로 통째로 돌려도 되고, 블록 번호(`[11-1]` ~ `[11-26]`)를 보며 한 덩어리씩 복사해 실행해도 됩니다.

- **[11-1]~[11-2] 사본 준비**: `CREATE TABLE s11_customers LIKE customers` 로 구조(인덱스·제약 포함)만 복사한 뒤 `INSERT INTO s11_customers SELECT * FROM customers` 로 데이터를 채웁니다. 이후 모든 DML 은 `s11_` 테이블에만 가해집니다.
- **[11-3]~[11-6] INSERT**: 단일 행 INSERT 직후 `SELECT LAST_INSERT_ID()`, 다중 행 INSERT 직후 `SELECT LAST_INSERT_ID() AS first_of_batch, ROW_COUNT() AS inserted_rows` 가 **바로 붙어 있는 것**에 주목하세요. 본문 11-0 의 경고대로, 사이에 다른 SELECT(심지어 주석 한 줄)가 끼면 `ROW_COUNT()` 가 0 으로 리셋되기 때문에 일부러 붙여 둔 배치입니다.
- **[11-7]~[11-11] UPSERT 비교**: `ON DUPLICATE KEY UPDATE` 는 `VALUES(stock)`(구문법)과 `AS new ... new.stock`(8.0.19+ 별칭 문법)을 모두 보여 줍니다. `s11_seq_demo` 는 `memo VARCHAR(50) DEFAULT '기본메모'` 를 일부러 넣어 둔 함정 테이블입니다 — `REPLACE INTO s11_seq_demo (code) VALUES ('A')` 를 하면 `memo` 가 `'원본메모'` → `'기본메모'` 로 날아가고 `id` 도 1 → 2 로 튑니다.
- **[11-12]~[11-14] INSERT IGNORE**: `s11_ignore_demo` 의 `qty INT NOT NULL`, `nm VARCHAR(5) NOT NULL` 이 핵심입니다. `(NULL, '널값')` 과 `(200, '아주긴이름입니다')` 가 에러 없이 `qty=0`, 잘린 이름으로 들어가는 것을 눈으로 확인하세요. [11-14] 는 IGNORE 없이 실행하면 `ERROR 1048` 로 제대로 멈춘다는 것을 보여 주려고 **주석 처리해 둔** 문장입니다.
- **[11-19] 안전 모드**: `SET SESSION sql_safe_updates = 1` 아래에 **차단되는 예 두 개**(`UPDATE s11_products SET stock = 0;` 와 `UPDATE s11_products SET stock = 0 WHERE name LIKE '%셔츠%';`)가 **일부러 주석 처리**되어 있습니다. 스크립트 전체를 파이프로 실행할 때 여기서 `ERROR 1175` 로 죽지 않게 하려는 것이니, 직접 확인하고 싶다면 콘솔에서 주석을 벗겨 손으로 실행해 보세요. 실제로 실행되는 것은 PK 를 쓰는 `WHERE product_id = 1` 과 `LIMIT 10` 을 붙인 두 문장이며, 블록 끝에서 `sql_safe_updates` 를 다시 0 으로 되돌립니다.
- **[11-25] LOAD DATA**: `LOAD DATA LOCAL INFILE` 블록 역시 주석입니다. 실행하려면 먼저 셸에서 `/tmp/s11_products.csv` 를 만들고, 클라이언트에 `--local-infile=1`, 서버에 `local_infile=ON` 이 켜져 있어야 합니다.
- **[11-26] 정리**: 파일 끝에서 `DROP TABLE IF EXISTS` 9 개로 사본을 전부 지웁니다. 즉 스크립트를 통째로 돌리면 마지막에 사본이 사라지므로, 중간 결과를 살펴보고 싶다면 이 블록 전까지만 실행하세요.

```sql file="./practice.sql"
```

### exercise.sql

연습문제 8개(Q1 ~ Q8)가 **답이 비워진 채로** 들어 있는 파일입니다. 본문 11-8 까지 읽고 `practice.sql` 을 돌려 본 뒤 이 파일의 빈 줄을 채워 넣으세요.

- 파일 앞부분에서 `s11_ex_` 접두사(practice 의 `s11_` 과 **다른** 접두사)로 사본을 새로 만듭니다. 덕분에 `practice.sql` 의 사본과 충돌하지 않고 병행 실습이 가능합니다.
- Q1(다중 행 INSERT) → Q2(`INSERT ... SELECT` 로 재고 0 상품 복사) → Q3(`ON DUPLICATE KEY UPDATE` + `AS new` 별칭으로 재고 **누적**) → Q4(같은 값을 `REPLACE` 로 넣어 누적이 안 되는 이유 설명) 순서가 UPSERT 두 방식의 차이를 스스로 발견하게 만드는 흐름입니다. Q3 을 풀지 않고 Q4 로 가면 비교 대상이 없으니 순서를 지키세요.
- Q5(JOIN UPDATE), Q6(JOIN DELETE, 헤더는 남기고 상세만 삭제), Q7(`sql_safe_updates`), Q8(`DELETE` vs `TRUNCATE` 의 AUTO_INCREMENT) 은 각각 본문 11-4 · 11-6 · 11-5 · 11-6 에 대응합니다.
- 파일 맨 끝의 `DROP TABLE IF EXISTS s11_ex_*` 블록은 **자기 사본만** 지웁니다. 아직 답을 채우지 않은 상태로 이 파일을 통째로 실행하면 사본을 만들었다가 곧바로 지우기만 하니, 실제로는 문제 블록마다 코드를 채워 가며 실행하는 편이 좋습니다.

```sql file="./exercise.sql"
```

### solution.sql

Q1 ~ Q8 의 정답과 해설 주석이 들어 있는 파일입니다. 먼저 스스로 풀어 본 뒤에 열어 보세요.

- **A3 vs A4 가 이 스텝의 핵심**입니다. A3 은 `ON DUPLICATE KEY UPDATE stock = s11_ex_stock.stock + new.stock` 로 기존 값을 **참조해 누적**합니다(`product_id=4` 는 `0 + 100 = 100`). 반면 A4 의 `REPLACE INTO ... VALUES (4, '울 니트 스웨터', 50)` 은 기존 행을 지우고 새로 넣으므로 결과가 `150` 이 아니라 **`50`** 입니다 — REPLACE 에는 "이전 값"이라는 개념이 아예 없습니다. **누적/증분 UPSERT 에는 REPLACE 를 쓸 수 없다**는 결론이 여기서 나옵니다.
- A3 의 `affected = 3` 은 INSERT 1건(=1) + 값이 바뀐 UPDATE 1건(=2) 의 합입니다. A4 의 `replace_affected = 2` 는 DELETE 1 + INSERT 1 입니다. 같은 "2 처럼 보이는 숫자"라도 의미가 다르다는 점을 주석으로 짚어 두었습니다.
- A6 은 `DELETE oi FROM s11_ex_order_items oi JOIN s11_ex_orders o ...` 로 **상세만** 지우고, 바로 뒤의 `SELECT COUNT(*) AS pending_headers_left` 로 주문 헤더 60건이 그대로 남아 있음을 확인합니다.
- A7 의 막히는 예(`WHERE status = 'ON_SALE'`)는 `status` 가 인덱스 컬럼이 아니어서 전체 스캔이 되므로 차단됩니다. 이 문장은 스크립트가 중간에 죽지 않도록 **주석 처리**되어 있고, 통과하는 예(`WHERE product_id = 1`)만 실제로 실행됩니다.

```sql file="./solution.sql"
```

### cleanup.sql

`practice.sql` 이 만든 `s11_` 사본 9개를 지우는 정리 전용 스크립트입니다. 실습 도중 에러가 나서 중단했거나, `practice.sql` 의 [11-26] 블록을 건너뛰어 사본이 남아 있을 때 **이 파일만 단독으로** 실행하면 됩니다.

- `DROP TABLE IF EXISTS` 순서에 주의하세요. 자식 테이블인 `s11_order_items` 를 먼저 지우고 부모인 `s11_orders`, `s11_products`, `s11_customers` 를 지웁니다. FK 제약이 `LIKE` 로 함께 복사되므로 순서를 뒤집으면 삭제가 실패할 수 있습니다.
- `IF EXISTS` 가 붙어 있어 사본이 일부만 남아 있어도, 혹은 이미 다 지워졌어도 에러 없이 통과합니다. 여러 번 실행해도 안전합니다.
- 대상은 전부 `s11_` 접두사 테이블입니다. 공용 테이블(customers/products/orders/…)은 이름조차 등장하지 않으므로 이 스크립트로 원본이 지워질 일은 없습니다. 다만 `exercise.sql`/`solution.sql` 이 만드는 `s11_ex_` 사본은 여기서 지우지 않으니, 그쪽은 각 파일 끝의 정리 블록을 쓰세요.

```sql file="./cleanup.sql"
```
