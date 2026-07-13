# Step 14 — 뷰와 생성 컬럼

> **학습 목표**
> - 뷰(VIEW)로 복잡한 쿼리를 이름 붙여 재사용하고, 업데이트 가능한 뷰의 조건을 안다
> - `WITH CHECK OPTION` (LOCAL / CASCADED)으로 뷰를 통한 변경을 통제한다
> - `ALGORITHM` MERGE 와 TEMPTABLE 의 성능 차이를 실행 계획으로 확인한다
> - 생성 컬럼(Generated Column)의 VIRTUAL / STORED 를 구분하고 자동 계산에 활용한다
> - 생성 컬럼 + 인덱스, 그리고 8.0.13+ 함수 기반 인덱스로 "함수 인덱스"를 만든다
> - 인비저블 컬럼(8.0.23+)으로 `SELECT *` 로부터 컬럼을 숨긴다
>
> **선행 스텝**: Step 13 — 제약 조건과 정규화
> **예상 소요**: 70분

---

## 14-0. 실습 준비

이 스텝도 DDL 을 다룹니다. 공용 테이블에는 **뷰(읽기)만** 만들고, 데이터를 바꾸는 실습은 `s14_` 사본 테이블에서 합니다.

```sql
USE shop;
CREATE TABLE s14_customers AS SELECT * FROM customers;
ALTER TABLE s14_customers ADD PRIMARY KEY (customer_id);
```

> ⚠️ 뷰 자체는 데이터를 저장하지 않으므로 공용 테이블 위에 만들어도 안전합니다.
> 하지만 **업데이트 가능한 뷰를 통해 UPDATE 하면 원본이 바뀝니다.** 그래서 변경 실습은 반드시 사본 위에서 합니다.

---

## 14-1. 뷰(VIEW)란

뷰는 **저장된 SELECT 문**입니다. 데이터를 복사해 두는 게 아니라, 조회할 때마다 정의된 쿼리를 실행합니다. "가상 테이블"이라고 부릅니다.

```sql
CREATE VIEW v14_order_summary AS
SELECT o.order_id, o.order_date, o.status,
       c.name AS customer_name, c.grade, o.total_amount
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.status <> 'CANCELLED';

-- 이제 복잡한 JOIN 을 몰라도 테이블처럼 조회할 수 있다
SELECT * FROM v14_order_summary WHERE grade = 'VIP' ORDER BY total_amount DESC LIMIT 5;
```

**결과**
```
+----------+---------------------+-----------+---------------+-------+--------------+
| order_id | order_date          | status    | customer_name | grade | total_amount |
+----------+---------------------+-----------+---------------+-------+--------------+
|      240 | 2024-04-30 00:00:00 | DELIVERED | 김민수        | VIP   |   4380000.00 |
|      480 | 2024-08-28 00:00:00 | DELIVERED | 김민수        | VIP   |   4380000.00 |
|      360 | 2024-06-29 00:00:00 | DELIVERED | 김민수        | VIP   |   4380000.00 |
|      120 | 2024-03-01 00:00:00 | DELIVERED | 김민수        | VIP   |   4380000.00 |
|      600 | 2024-10-27 00:00:00 | DELIVERED | 김민수        | VIP   |   4380000.00 |
+----------+---------------------+-----------+---------------+-------+--------------+
```

뷰의 용도:
- **복잡성 은닉** — 5개 테이블 JOIN 을 뷰 하나로 감춘다
- **권한 제어** — 민감 컬럼을 뺀 뷰만 특정 사용자에게 노출한다 (행/열 수준 보안)
- **인터페이스 고정** — 테이블 구조가 바뀌어도 뷰가 같은 컬럼을 제공하면 애플리케이션은 그대로

뷰 관리 명령:

```sql
CREATE OR REPLACE VIEW v14_order_summary AS ... ;   -- 재정의 (있으면 교체)
ALTER VIEW v14_order_summary AS ... ;               -- 재정의
DROP VIEW IF EXISTS v14_order_summary;              -- 삭제
SHOW CREATE VIEW v14_order_summary;                 -- 정의 확인
```

뷰 목록과 정의는 `information_schema.VIEWS` 에서 볼 수 있습니다.

```sql
SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME LIKE 'v14%';
```

> 💡 **실무 팁 — 뷰는 성능 도구가 아니다**
> 뷰는 매번 원본 쿼리를 실행합니다. 느린 쿼리를 뷰로 감싼다고 빨라지지 않습니다.
> "매번 계산하기 싫다"면 뷰가 아니라 **구체화(materialized) 개념**이 필요합니다. MySQL 은 구체화 뷰를 기본 지원하지 않으므로,
> 요약 테이블을 만들어 배치로 채우거나(반정규화, Step 13), 생성 컬럼(아래)을 쓰거나, 캐시를 씁니다.

---

## 14-2. 업데이트 가능한 뷰

뷰가 **단순히 원본 행과 1:1 로 대응**되면, 그 뷰를 통해 INSERT/UPDATE/DELETE 를 할 수 있습니다.

```sql
CREATE VIEW v14_vip AS
SELECT customer_id, name, grade, city, points
FROM s14_customers
WHERE grade = 'VIP';

SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='v14_vip';
```

**결과**
```
+------------+--------------+
| TABLE_NAME | IS_UPDATABLE |
+------------+--------------+
| v14_vip    | YES          |
+------------+--------------+
```

```sql
UPDATE v14_vip SET points = points + 1000 WHERE customer_id = 1;
SELECT customer_id, name, points FROM s14_customers WHERE customer_id = 1;
```

**결과** (원본 테이블이 바뀐다)
```
+-------------+-----------+--------+
| customer_id | name      | points |
+-------------+-----------+--------+
|           1 | 김민수    |  13500 |
+-------------+-----------+--------+
```

### 업데이트 불가능해지는 조건

뷰가 아래 중 **하나라도** 포함하면 업데이트할 수 없습니다. 원본의 어느 행을 바꿔야 할지 **1:1 로 특정할 수 없기** 때문입니다.

| 포함하면 업데이트 불가 |
|---|
| 집계 함수 (`SUM`, `COUNT`, `AVG` ...) |
| `GROUP BY` / `HAVING` |
| `DISTINCT` |
| `UNION` / `UNION ALL` |
| 대부분의 서브쿼리(SELECT 목록·WHERE 상관 등) |
| 윈도우 함수 (`OVER(...)`) |
| 파생 컬럼만 있고 원본 컬럼이 없는 경우 |

```sql
CREATE VIEW v14_grouped AS
SELECT grade, COUNT(*) AS cnt, AVG(points) AS avg_points
FROM s14_customers GROUP BY grade;

SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='v14_grouped';
```

**결과**
```
+-------------+--------------+
| TABLE_NAME  | IS_UPDATABLE |
+-------------+--------------+
| v14_grouped | NO           |
+-------------+--------------+
```

```sql
UPDATE v14_grouped SET cnt = 99 WHERE grade = 'VIP';
```

**결과**
```
ERROR 1288 (HY000): The target table v14_grouped of the UPDATE is not updatable
```

---

## 14-3. WITH CHECK OPTION

업데이트 가능한 뷰에는 위험한 함정이 있습니다. **뷰의 WHERE 조건을 벗어나게 만드는 UPDATE 가 그냥 통과**한다는 것입니다.

```sql
-- CHECK OPTION 이 없는 v14_vip (WHERE grade='VIP')
UPDATE v14_vip SET grade = 'GOLD' WHERE customer_id = 1;   -- VIP 뷰에서 GOLD 로 바꿈?!
SELECT customer_id, name, grade FROM s14_customers WHERE customer_id = 1;
```

**결과**
```
+-------------+-----------+-------+
| customer_id | name      | grade |
+-------------+-----------+-------+
|           1 | 김민수    | GOLD  |     ← 바뀌었다
+-------------+-----------+-------+
```

```sql
SELECT COUNT(*) AS still_in_view FROM v14_vip WHERE customer_id = 1;
```

**결과**
```
+---------------+
| still_in_view |
+---------------+
|             0 |     ← 방금 수정한 행이 뷰에서 사라졌다!
+---------------+
```

"VIP 뷰"를 통해 수정했는데 그 행이 VIP 가 아니게 되어 **뷰에서 증발**했습니다. 마치 수정한 데이터가 사라진 것처럼 보입니다.

`WITH CHECK OPTION` 을 걸면 이런 변경을 거부합니다.

```sql
CREATE OR REPLACE VIEW v14_vip AS
SELECT customer_id, name, grade, city, points
FROM s14_customers WHERE grade = 'VIP'
WITH CHECK OPTION;

UPDATE v14_vip SET grade = 'GOLD' WHERE customer_id = 1;
```

**결과**
```
ERROR 1369 (HY000): CHECK OPTION failed 'shop.v14_vip'
```

INSERT 도 검사합니다. 뷰의 조건을 만족하지 않는 행은 애초에 넣을 수 없습니다.

```sql
CREATE VIEW v14_high AS
SELECT id, name, score FROM s14_scores WHERE score >= 60 WITH CHECK OPTION;

INSERT INTO v14_high (name, score) VALUES ('통과', 80);   -- OK
INSERT INTO v14_high (name, score) VALUES ('탈락', 40);   -- score < 60
```

**결과**
```
ERROR 1369 (HY000): CHECK OPTION failed 'shop.v14_high'
```

### LOCAL vs CASCADED

뷰 위에 뷰를 얹었을 때, CHECK OPTION 이 **어느 범위까지** 검사할지 정합니다.

- `CASCADED` (기본값): **이 뷰 + 그 아래 모든 부모 뷰**의 조건을 전부 검사
- `LOCAL`: **이 뷰 자신의 WHERE 조건만** 검사

```sql
CREATE OR REPLACE VIEW v14_vip AS                    -- 부모: grade='VIP'
SELECT customer_id, name, grade, city, points FROM s14_customers WHERE grade = 'VIP';

CREATE OR REPLACE VIEW v14_vip_seoul AS              -- 자식: city='서울', LOCAL
SELECT * FROM v14_vip WHERE city = '서울'
WITH LOCAL CHECK OPTION;

-- LOCAL 은 자기 조건(city)만 검사한다. 부모 조건(grade)은 안 본다 → 통과!
UPDATE v14_vip_seoul SET grade = 'GOLD' WHERE customer_id = 1;
SELECT customer_id, grade, city FROM s14_customers WHERE customer_id = 1;
```

**결과** (LOCAL 은 grade 변경을 허용 — city 조건만 봄)
```
+-------------+-------+--------+
| customer_id | grade | city   |
+-------------+-------+--------+
|           1 | GOLD  | 서울   |
+-------------+-------+--------+
```

```sql
CREATE OR REPLACE VIEW v14_vip_seoul AS
SELECT * FROM v14_vip WHERE city = '서울'
WITH CASCADED CHECK OPTION;

-- CASCADED 는 부모 뷰의 grade='VIP' 조건까지 검사한다 → 거부!
UPDATE v14_vip_seoul SET grade = 'GOLD' WHERE customer_id = 1;
```

**결과**
```
ERROR 1369 (HY000): CHECK OPTION failed 'shop.v14_vip_seoul'
```

> ⚠️ **함정 — 기본값은 CASCADED 다**
> `WITH CHECK OPTION` 만 쓰면 CASCADED 입니다. 중첩 뷰에서 예상보다 엄격하게 막힐 수 있습니다.
> 자기 조건만 검사하고 싶으면 명시적으로 `WITH LOCAL CHECK OPTION` 을 쓰세요.

---

## 14-4. ALGORITHM — MERGE vs TEMPTABLE

뷰를 조회할 때 MySQL 이 뷰를 처리하는 방식이 두 가지입니다.

```
MERGE     : 뷰를 바깥 쿼리에 "펼쳐 넣어" 하나의 쿼리로 합친다.
            → 바깥 WHERE 조건이 원본 테이블까지 전달되어 인덱스를 쓸 수 있다.

TEMPTABLE : 뷰의 SELECT 를 먼저 실행해 "임시 테이블"로 구체화한 뒤,
            그 임시 테이블에 바깥 조건을 적용한다.
            → 원본 인덱스를 못 쓴다. 임시 테이블은 인덱스가 없으니까.
```

같은 정의의 뷰를 두 알고리즘으로 만들어 비교합니다.

```sql
CREATE ALGORITHM=MERGE VIEW v14_merge AS
SELECT log_id, customer_id, path, status_code, logged_at FROM access_logs;

CREATE ALGORITHM=TEMPTABLE VIEW v14_temptable AS
SELECT log_id, customer_id, path, status_code, logged_at FROM access_logs;
```

PK 로 한 행을 찾는 쿼리의 실행 계획:

```sql
EXPLAIN SELECT * FROM v14_merge WHERE log_id = 1;
```

**결과** (MERGE — PK 조건이 원본까지 전달되어 `const`)
```
+----+-------------+-------------+-------+---------------+---------+---------+-------+------+-------+
| id | select_type | table       | type  | possible_keys | key     | key_len | ref   | rows | Extra |
+----+-------------+-------------+-------+---------------+---------+---------+-------+------+-------+
|  1 | SIMPLE      | access_logs | const | PRIMARY       | PRIMARY | 8       | const |    1 | NULL  |
+----+-------------+-------------+-------+---------------+---------+---------+-------+------+-------+
```

```sql
EXPLAIN SELECT * FROM v14_temptable WHERE log_id = 1;
```

**결과** (TEMPTABLE — `<derived2>` 임시테이블을 거친다)
```
+----+-------------+-------------+--------+---------------+---------+---------+-------+------+
| id | select_type | table       | type   | possible_keys | key     | key_len | ref   | rows |
+----+-------------+-------------+--------+---------------+---------+---------+-------+------+
|  1 | PRIMARY     | <derived2>  | system | NULL          | NULL    | NULL    | NULL  |    1 |
|  2 | DERIVED     | access_logs | const  | PRIMARY       | PRIMARY | 8       | const |    1 |
+----+-------------+-------------+--------+---------------+---------+---------+-------+------+
```

`<derived2>` 라는 파생 테이블이 등장합니다. 이 경우는 옵티마이저가 영리해서 결국 빠르지만, 조건이 임시 테이블 밖에서만 적용될 때는 이야기가 다릅니다. `status_code = 500` 을 세는 쿼리로 실제 시간을 재봅니다(아직 status_code 인덱스는 없습니다).

```sql
SELECT COUNT(*) FROM v14_merge     WHERE status_code = 500;   -- (0.096 sec)
SELECT COUNT(*) FROM v14_temptable WHERE status_code = 500;   -- (0.136 sec)
```

TEMPTABLE 은 100만 행을 임시 테이블로 구체화하는 비용이 더해져 **약 40% 느립니다.** 데이터가 크고 조건이 선택적일수록 격차는 더 벌어집니다.

> 💡 **실무 팁**
> 특별한 이유가 없으면 뷰는 **MERGE(기본)** 로 두세요. `ALGORITHM=UNDEFINED`(기본)면 MySQL 이 알아서 고릅니다.
> 그런데 뷰 정의에 위 14-2 의 "업데이트 불가 요소"(집계·DISTINCT·UNION·윈도우 함수 등)가 들어가면
> **MERGE 가 불가능해서 자동으로 TEMPTABLE 로 떨어집니다.** "왜 이 뷰만 느리지?" 의 흔한 원인입니다.

> ⚠️ **함정 — TEMPTABLE 뷰는 업데이트도 불가능하다**
> 임시 테이블을 거치므로 원본 행과의 1:1 대응이 끊깁니다. `ALGORITHM=TEMPTABLE` 뷰는 항상 읽기 전용입니다.

---

## 14-5. 생성 컬럼 (Generated Column)

생성 컬럼은 **다른 컬럼으로부터 계산되는 컬럼**입니다. 값을 직접 넣지 않고, 표현식이 자동으로 채웁니다.

```sql
CREATE TABLE s14_gen (
  product_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name       VARCHAR(50) NOT NULL,
  price      DECIMAL(10,2) NOT NULL,
  cost       DECIMAL(10,2) NOT NULL,
  margin     DECIMAL(10,2) AS (price - cost) VIRTUAL,                      -- 가상
  margin_pct DECIMAL(5,2)  AS (ROUND((price-cost)/price*100, 2)) STORED,   -- 저장
  name_upper VARCHAR(50)   AS (UPPER(name)) VIRTUAL,
  PRIMARY KEY (product_id)
) ENGINE=InnoDB;

INSERT INTO s14_gen (name, price, cost) VALUES ('shirt', 39000, 18000), ('laptop', 1290000, 980000);
SELECT product_id, name, price, cost, margin, margin_pct, name_upper FROM s14_gen;
```

**결과**
```
+------------+--------+------------+-----------+-----------+------------+------------+
| product_id | name   | price      | cost      | margin    | margin_pct | name_upper |
+------------+--------+------------+-----------+-----------+------------+------------+
|          1 | shirt  |   39000.00 |  18000.00 |  21000.00 |      53.85 | SHIRT      |
|          2 | laptop | 1290000.00 | 980000.00 | 310000.00 |      24.03 | LAPTOP     |
+------------+--------+------------+-----------+-----------+------------+------------+
```

### VIRTUAL vs STORED

| | VIRTUAL (기본) | STORED |
|---|---|---|
| 값 저장 | 저장 안 함, **읽을 때 계산** | 디스크에 **저장** |
| 저장 공간 | 안 씀 | 씀 |
| INSERT/UPDATE 비용 | 거의 없음 | 계산해서 써야 함 |
| 인덱스 | **가능** (세컨더리) | 가능 |
| 언제 | 대부분의 경우 | 계산이 비싸고 자주 읽을 때, 또는 PK 로 쓸 때 |

> 💡 **기본은 VIRTUAL 로 두세요.** 저장 공간을 쓰지 않고, 값이 필요할 때만 계산합니다. 인덱스도 걸 수 있으니 대부분 VIRTUAL 로 충분합니다.
> STORED 는 표현식이 복잡해서 매 조회마다 계산하기 아까울 때만 씁니다.

생성 컬럼에는 **직접 값을 넣을 수 없습니다.**

```sql
INSERT INTO s14_gen (name, price, cost, margin) VALUES ('x', 100, 50, 999);
```

**결과**
```
ERROR 3105 (HY000): The value specified for generated column 'margin' in table 's14_gen' is not allowed.
```

원본 컬럼이 바뀌면 생성 컬럼은 **자동으로 다시 계산**됩니다.

```sql
UPDATE s14_gen SET price = 45000 WHERE name = 'shirt';
SELECT product_id, name, price, cost, margin, margin_pct FROM s14_gen WHERE name = 'shirt';
```

**결과** (margin, margin_pct 가 알아서 갱신됨)
```
+------------+-------+----------+----------+----------+------------+
| product_id | name  | price    | cost     | margin   | margin_pct |
+------------+-------+----------+----------+----------+------------+
|          1 | shirt | 45000.00 | 18000.00 | 27000.00 |      60.00 |
+------------+-------+----------+----------+----------+------------+
```

> 💡 **생성 컬럼은 "안전한 반정규화"다**
> Step 13 에서 반정규화의 위험은 "동기화를 개발자가 책임져야 한다"는 것이었습니다.
> 생성 컬럼은 **DB 가 동기화를 보장**합니다. `margin` 이 `price - cost` 와 어긋날 방법이 없습니다.
> 계산식으로 표현 가능한 파생값이라면, 수동 반정규화 컬럼보다 생성 컬럼이 훨씬 안전합니다.

---

## 14-6. 함수 인덱스 만들기

Step 15/16 에서 자세히 다루지만, **컬럼에 함수를 씌우면 인덱스를 못 탑니다.** `WHERE UPPER(name) = ...` 는 `name` 인덱스를 무용지물로 만듭니다.

두 가지 해결책이 있습니다.

### 방법 1 — 생성 컬럼 + 인덱스

```sql
-- name_upper 는 위에서 만든 VIRTUAL 생성 컬럼 (UPPER(name))
ALTER TABLE s14_gen ADD INDEX idx_name_upper (name_upper);
```

이제 `UPPER(name) = 'SHIRT'` 라고 써도 옵티마이저가 **생성 컬럼 인덱스로 자동 매핑**합니다(생성 컬럼 이름을 직접 쓰지 않아도 됩니다).

```sql
EXPLAIN SELECT * FROM s14_gen WHERE UPPER(name) = 'SHIRT';
```

**결과** (type: ref, 인덱스를 탄다)
```
+----+---------+------+----------------+----------------+---------+-------+------+-------+
| id | table   | type | possible_keys  | key            | key_len | ref   | rows | Extra |
+----+---------+------+----------------+----------------+---------+-------+------+-------+
|  1 | s14_gen | ref  | idx_name_upper | idx_name_upper | 203     | const |    1 | NULL  |
+----+---------+------+----------------+----------------+---------+-------+------+-------+
```

### 방법 2 — 함수 기반 인덱스 (MySQL 8.0.13+)

8.0.13 부터는 생성 컬럼을 따로 만들지 않고 **표현식에 직접 인덱스**를 걸 수 있습니다. 이때 표현식은 괄호로 감쌉니다.

```sql
ALTER TABLE s14_gen ADD INDEX idx_func_margin ((price - cost));
ANALYZE TABLE s14_gen;   -- 통계를 갱신해야 옵티마이저가 선택도를 제대로 판단한다
```

```sql
EXPLAIN SELECT product_id, name FROM s14_gen WHERE (price - cost) = 3500.00;
```

**결과** (type: ref)
```
+----+---------+------+-----------------+-----------------+---------+-------+------+
| id | table   | type | possible_keys   | key             | key_len | ref   | rows |
+----+---------+------+-----------------+-----------------+---------+-------+------+
|  1 | s14_gen | ref  | idx_func_margin | idx_func_margin | 5       | const |    1 |
+----+---------+------+-----------------+-----------------+---------+-------+------+
```

`SHOW INDEX` 에 표현식이 그대로 보입니다.

```
| Key_name        | Column_name | Expression         |
| idx_func_margin | NULL        | (`price` - `cost`) |
```

> ⚠️ **함정 1 — 리터럴 타입이 표현식 타입과 맞아야 한다**
> `price`, `cost` 가 DECIMAL 이라 `(price - cost)` 는 DECIMAL 입니다. 그런데 `WHERE (price - cost) = 3500` (정수)로 쓰면
> **인덱스를 못 탑니다.** `= 3500.00` 처럼 DECIMAL 리터럴로 맞춰야 매핑됩니다. 실제로 이 차이로 인덱스가 죽는 것을 자주 봅니다.
>
> ⚠️ **함정 2 — 쿼리의 표현식이 인덱스 정의와 "글자까지" 같아야 한다**
> 인덱스가 `(price - cost)` 인데 쿼리에 `(cost - price)` 나 `(price - cost + 0)` 을 쓰면 안 탑니다. 옵티마이저는 수학이 아니라 표현식 문자열을 매칭합니다.
>
> ⚠️ **함정 3 — 통계가 없으면 안 쓸 수 있다**
> 방금 인덱스를 만든 직후에는 카디널리티 통계가 부실해서 옵티마이저가 풀스캔을 고르기도 합니다.
> `ANALYZE TABLE` 로 통계를 갱신하세요(Step 16 에서 자세히).

> 💡 **JSON 함수 인덱스** — 8.0 은 JSON 컬럼의 값을 뽑는 표현식에도 인덱스를 걸 수 있습니다.
> ```sql
> ALTER TABLE products ADD INDEX idx_ram ((CAST(attrs->>'$.ram_gb' AS UNSIGNED)));
> ```
> JSON 을 자주 조건으로 쓴다면 필수입니다. (Step 18 JSON 에서 다룹니다.)

---

## 14-7. 인비저블 컬럼 (MySQL 8.0.23+)

인비저블 컬럼은 존재하지만 **`SELECT *` 결과에 나오지 않는** 컬럼입니다. 명시적으로 이름을 적어야만 보입니다.

```sql
CREATE TABLE s14_inv (
  id     INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name   VARCHAR(50) NOT NULL,
  secret VARCHAR(50) NOT NULL INVISIBLE,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO s14_inv (id, name, secret) VALUES (1, '홍길동', 'password123');

SELECT * FROM s14_inv;              -- secret 이 안 보인다
```

**결과**
```
+----+-----------+
| id | name      |
+----+-----------+
|  1 | 홍길동    |
+----+-----------+
```

```sql
SELECT id, name, secret FROM s14_inv;   -- 명시하면 보인다
```

**결과**
```
+----+-----------+-------------+
| id | name      | secret      |
+----+-----------+-------------+
|  1 | 홍길동    | password123 |
+----+-----------+-------------+
```

비저블/인비저블은 언제든 토글할 수 있습니다.

```sql
ALTER TABLE s14_inv ALTER COLUMN secret SET VISIBLE;    -- 다시 보이게
ALTER TABLE s14_inv ALTER COLUMN secret SET INVISIBLE;  -- 다시 숨기게
```

> 💡 **실무 용도**
> - **컬럼 안전 삭제 리허설**: 컬럼을 지우기 전에 INVISIBLE 로 바꿔 두면, `SELECT *` 에 의존하던 코드가 있는지
>   실제로 지우지 않고 확인할 수 있습니다. 문제 없으면 그때 DROP 합니다.
> - **새 컬럼 점진 도입**: 새 컬럼을 INVISIBLE 로 추가하면 기존 `INSERT ... SELECT * ...` 배치를 깨지 않고 준비할 수 있습니다.

> ⚠️ **함정 — `SELECT *` 에 의존하지 마라 (INSERT ... SELECT * 특히)**
> 인비저블 컬럼은 `SELECT *` 에서 빠지므로, `INSERT INTO t2 SELECT * FROM t1` 같은 코드가 컬럼 수 불일치로 깨질 수 있습니다.
> 이건 인비저블 컬럼의 문제가 아니라 **`SELECT *` 에 의존한 코드의 문제**입니다. 애초에 컬럼을 명시하는 습관이 답입니다.

> 참고: **인비저블 인덱스**(8.0.0+)는 다른 기능입니다 — 옵티마이저가 특정 인덱스를 무시하게 만들어
> "이 인덱스를 지워도 안전한가"를 테스트합니다. Step 15 에서 다룹니다.

---

## 정리

| 주제 | 핵심 |
|---|---|
| 뷰 | 저장된 SELECT. 데이터를 복사하지 않고 매번 실행한다 |
| 뷰와 성능 | 뷰로 감싼다고 빨라지지 않는다. 성능 도구가 아니다 |
| 업데이트 가능 뷰 | 집계/GROUP BY/DISTINCT/UNION/윈도우 함수가 있으면 불가 |
| `WITH CHECK OPTION` | 뷰 조건을 벗어나는 INSERT/UPDATE 를 거부. 기본은 CASCADED |
| LOCAL vs CASCADED | LOCAL=자기 조건만, CASCADED=부모 뷰 조건까지 |
| ALGORITHM MERGE | 뷰를 펼쳐 원본 인덱스 활용. 기본이자 권장 |
| ALGORITHM TEMPTABLE | 임시테이블로 구체화 → 느리고 **읽기 전용** |
| 생성 컬럼 VIRTUAL | 저장 안 함, 읽을 때 계산. 기본이자 권장 |
| 생성 컬럼 STORED | 디스크에 저장. 계산이 비쌀 때만 |
| 생성 컬럼 | DB 가 동기화를 보장하는 "안전한 반정규화" |
| 함수 인덱스 (8.0.13) | 표현식에 직접 인덱스. 리터럴 타입·표현식 문자열이 일치해야 함 |
| 인비저블 컬럼 (8.0.23) | `SELECT *` 에서 숨김. 컬럼 삭제 리허설·점진 도입에 유용 |

---

## 연습문제

`exercise.sql` 에 6문제가 있습니다. 정답은 `solution.sql`.

1. 고객별 주문 통계 뷰 만들기 (JOIN + 집계)
2. 주어진 뷰가 업데이트 가능한지 판정하고 이유 대기
3. `WITH CHECK OPTION` 으로 "재고 있는 상품만" 뷰 통제하기
4. LOCAL vs CASCADED 결과 예측하기
5. `full_name` 생성 컬럼 + 인덱스로 검색 최적화하기
6. 함수 기반 인덱스가 안 먹는 쿼리 3개를 진단하고 고치기

---

## 다음 단계

지금까지 인덱스를 "결과"로만 봤습니다(생성 컬럼 인덱스, 함수 인덱스가 type: ref 로 바뀌는 것).
다음 스텝에서는 그 인덱스가 **왜, 어떻게** 동작하는지 — B+Tree 구조부터 복합 인덱스 컬럼 순서, 커버링 인덱스,
그리고 100만 행 `access_logs` 로 인덱스 전후 실행시간을 직접 재는 것까지 파고듭니다.

→ [Step 15 — 인덱스](../step-15-indexes/index.md)

---

## 실습 파일

이 스텝은 SQL 스크립트 세 개로 구성됩니다. 먼저 `practice.sql` 을 통째로 실행해 14-1 ~ 14-7 본문의 모든 예제(뷰, CHECK OPTION, MERGE/TEMPTABLE, 생성 컬럼, 함수 인덱스, 인비저블 컬럼)를 눈으로 확인하고, 그다음 `exercise.sql` 의 빈칸 6문제를 직접 채운 뒤, `solution.sql` 로 답과 해설을 대조하는 순서입니다. 세 스크립트 모두 `USE shop;` 으로 시작하며, 공용 테이블에는 **뷰(읽기)만** 만들고 데이터를 바꾸는 실습은 전부 `s14_` / `s14_ex_` 사본 테이블 위에서 수행하도록 설계되어 있습니다.

### practice.sql

본문 14-0 ~ 14-7 을 그대로 스크립트로 옮긴 **따라하기용 데모**입니다. 파일 머리말의 실행 명령이 핵심입니다.

- 실행: `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 --force shop < practice.sql`. **`--force` 가 반드시 필요합니다.** 이 스크립트에는 `UPDATE v14_grouped ...`(ERROR 1288), `WITH CHECK OPTION` 위반(ERROR 1369), 생성 컬럼 직접 INSERT(ERROR 3105)처럼 **일부러 에러를 내는 줄**이 들어 있어서, `--force` 가 없으면 첫 에러에서 스크립트가 중단됩니다. 즉 에러 메시지 자체가 학습 결과물입니다.
- 맨 앞과 맨 뒤에서 `DROP VIEW IF EXISTS v14_vip_seoul, v14_vip, ...` / `DROP TABLE IF EXISTS s14_customers, s14_gen, s14_inv, s14_scores` 를 실행하므로 **몇 번을 다시 돌려도 같은 결과**가 나옵니다. 뒤처리까지 하므로 실습 흔적이 DB 에 남지 않습니다.
- 14-3 구간을 보면 `UPDATE v14_vip SET grade='GOLD' ...` 로 행을 뷰에서 "증발"시킨 직후 `UPDATE s14_customers SET grade='VIP' WHERE customer_id=1;` 로 **원복**합니다. LOCAL/CASCADED 비교를 같은 1번 고객으로 이어서 하기 때문에 이 원복 줄을 빼먹으면 뒤의 결과가 달라집니다.
- 14-6 의 `INSERT INTO s14_gen (name, price, cost) SELECT CONCAT('item', n), 1000 + n, 500 FROM tally WHERE n <= 5000;` 는 **함수 인덱스가 실제로 선택되게 만들기 위한 장치**입니다. 행이 2개뿐이면 옵티마이저가 인덱스를 무시하고 풀스캔을 고르므로 5,000행을 채워 카디널리티를 만들고, 인덱스 생성 직후 `ANALYZE TABLE s14_gen;` 으로 통계를 갱신합니다(본문 "함정 3").
- `EXPLAIN ... WHERE (price - cost) = 3500.00;` 의 리터럴이 `3500` 이 아니라 `3500.00` 인 것도 의도된 것입니다(본문 "함정 1" — DECIMAL 표현식에는 DECIMAL 리터럴).
- `access_logs`(100만 행)와 `tally` 테이블에 의존하므로, 시드 데이터가 적재된 `shop` 스키마에서 실행해야 합니다.

```sql file="./practice.sql"
```

### exercise.sql

본문을 다 읽은 뒤 푸는 **연습문제 6개**입니다. 각 문제는 "여기에 작성:" 주석으로 끝나며, 그 아래에 직접 SQL 을 채워 넣으면 됩니다. 새로 만드는 객체는 `v14_ex_` / `s14_ex_` 접두사를 쓰라는 규칙이 머리말에 명시되어 있습니다.

- **문제 1** — `v14_ex_customer_stats` 뷰. "주문이 한 건도 없는 고객도 포함(`order_cnt = 0`)"이라는 조건이 함정입니다. LEFT JOIN + `CANCELLED` 제외 조건을 어디에 두느냐(ON 절 vs WHERE 절)를 묻습니다.
- **문제 2** — `s14_ex_prod` 사본 위에 네 개 뷰(`v14_ex_a` 단순 필터, `v14_ex_b` GROUP BY, `v14_ex_c` DISTINCT, `v14_ex_d` 파생 컬럼 `price * 1.1`)를 미리 만들어 두고 `IS_UPDATABLE` 을 예측하게 합니다. (2-3) 은 "뷰가 업데이트 가능한 것"과 "그 뷰의 모든 컬럼이 업데이트 가능한 것"이 다르다는 점을 직접 부딪혀 보게 하는 문항입니다.
- **문제 3** — `WITH CHECK OPTION` 으로 "재고 있는 상품 뷰를 통해 재고를 0 으로 만드는" 모순을 막습니다. `stock = 1` 은 성공, `stock = 0` 은 거부되어야 합니다.
- **문제 4** — `s14_ex_emp`(개발 2명, 영업 1명)와 부모 뷰 `v14_ex_dev`(`dept='개발'`, CHECK OPTION 없음) 위에 `v14_ex_dev_highpay`(`salary >= 4000`, LOCAL)를 얹어 둡니다. `UPDATE ... SET dept='영업' WHERE emp_id=1` 이 LOCAL 에서는 통과하고 CASCADED 에서는 거부되는 차이를 예측하게 합니다.
- **문제 5** — `s14_ex_cust` 사본을 만든 뒤 `UPDATE ... SET email = REPLACE(email, 'example.com', 'test.org') WHERE customer_id % 3 = 0;` 로 **도메인 분포를 인위적으로 3분의 1만 바꿔 둡니다.** 모든 이메일이 같은 도메인이면 선택도가 0 이라 인덱스를 타지 않기 때문입니다.
- **문제 6** — `s14_ex_log` 에 함수 기반 인덱스 `INDEX idx_ym ((YEAR(logged_at)))` 만 걸어 두고, `tally` 로 2022-01-01 부터 1,000일치 데이터를 넣습니다. `YEAR(logged_at) = 2023`, `YEAR(logged_at) + 0 = 2023`, `logged_at >= '2023-01-01' AND ...` 세 쿼리 중 무엇이 인덱스를 못 타는지 EXPLAIN 으로 진단하는 문제입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 6문제의 **정답과 해설**입니다. 스스로 풀어본 뒤에 열어보세요. 각 정답 아래에 "왜 그런가"를 설명하는 주석이 길게 붙어 있어서, 답만 베끼는 것보다 해설을 읽는 것이 핵심입니다.

- 정답 1 의 요지는 `ON o.customer_id = c.customer_id AND o.status <> 'CANCELLED'` 처럼 **취소 제외 조건을 ON 절에 두는 것**입니다. WHERE 로 내리면 주문이 없는 고객의 `o.status` 가 NULL 이라 함께 걸러져 LEFT JOIN 이 INNER JOIN 으로 퇴화합니다. 또 `COUNT(*)` 가 아니라 `COUNT(o.order_id)` 를 써야 주문 없는 고객이 0 으로 나옵니다.
- 정답 2 는 a=YES, b=NO(GROUP BY), c=NO(DISTINCT), d=YES 입니다. 그런데 `UPDATE v14_ex_d SET price_with_vat = 999` 는 `ERROR 1348: Column 'price_with_vat' is not updatable` 로 거부되고, 같은 뷰의 `UPDATE ... SET price = 50000` 은 성공합니다 — `price * 1.1` 을 역산해 원본에 무엇을 쓸지 알 수 없기 때문입니다.
- 정답 3 은 `WHERE stock > 0 WITH CHECK OPTION` 뷰 `v14_ex_instock` 을 만든 뒤, `UPDATE ... SET stock = 1` 은 통과시키고 `UPDATE ... SET stock = 0` 은 `ERROR 1369` 로 거부하는 것을 보여줍니다. CHECK OPTION 이 없었다면 stock=0 UPDATE 가 통과하면서 그 상품이 뷰에서 조용히 증발했을 것입니다.
- 정답 4 는 같은 UPDATE 문이 LOCAL 이면 성공, CASCADED 면 `ERROR 1369` 로 실패하는 것을 나란히 보여줍니다. LOCAL 성공 뒤에는 `UPDATE s14_ex_emp SET dept='개발' WHERE emp_id=1;` 로 원복하고 나서 CASCADED 를 시험하므로 **순서를 건너뛰면 결과가 달라집니다.**
- 정답 5 의 하이라이트는 "보너스" 구간입니다. `email_domain` 생성 컬럼에 `idx_domain` 을 걸면, 애플리케이션 코드를 고치지 않고 **기존의 `WHERE SUBSTRING_INDEX(email,'@',-1) = 'example.com'` 조건 그대로도 옵티마이저가 인덱스로 자동 매핑**합니다. 무중단 인덱스 도입 기법입니다.
- 정답 6 은 (b) 가 `+ 0` 하나 때문에 표현식 문자열 매칭에 실패해 풀스캔이 되고, (c) 는 함수 인덱스로는 원본 컬럼 범위 검색을 커버할 수 없어 `ALTER TABLE s14_ex_log ADD INDEX idx_logged (logged_at);` 가 필요함을 보여줍니다. 결론은 "함수 인덱스를 남발하기보다 원본 컬럼에 범위(sargable)로 접근하는 쿼리가 더 범용적이다" 입니다.
- 스크립트 끝에서 `DROP VIEW` / `DROP TABLE` 로 만든 객체를 모두 정리합니다.

```sql file="./solution.sql"
```
