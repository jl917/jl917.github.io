# Step 13 — 제약 조건과 정규화

> **학습 목표**
> - NOT NULL / DEFAULT / PRIMARY KEY / UNIQUE 의 정확한 의미와 NULL 처리 규칙을 구분한다
> - CHECK 제약이 MySQL **8.0.16 부터 실제로 강제**된다는 것을 확인하고 사용한다
> - FOREIGN KEY 의 `ON DELETE` / `ON UPDATE` 5가지 옵션을 실제 동작으로 구별한다
> - FK 가 성능과 락에 미치는 영향을 측정하고, 언제 FK 를 쓰고 언제 뺄지 판단한다
> - AUTO_INCREMENT 가 "구멍(gap)"을 만드는 3가지 상황을 안다
> - 1NF~3NF 정규화와, 실무에서 일부러 정규화를 깨는 반정규화의 트레이드오프를 이해한다
>
> **선행 스텝**: Step 12 — 내장 함수
> **예상 소요**: 70분

---

## 13-0. 실습 준비 — 공용 테이블은 건드리지 않는다

이 스텝은 DDL(테이블 구조 변경)을 다룹니다. `customers`, `orders` 같은 **공용 테이블에 ALTER/DROP 을 하면 다른 학습자의 실습이 깨집니다.**

그래서 이 스텝의 모든 실습은 `s13_` 접두사가 붙은 **사본 테이블**에서 합니다.

```sql
USE shop;

-- 사본 만들기 예시 (구조만 복사, 데이터는 안 옴)
CREATE TABLE s13_orders LIKE orders;

-- 구조 + 데이터까지
CREATE TABLE s13_orders_full AS SELECT * FROM orders;
```

> ⚠️ **함정**
> `CREATE TABLE ... LIKE` 는 인덱스와 제약을 **그대로** 복사합니다.
> 하지만 `CREATE TABLE ... AS SELECT` 는 **PK도 인덱스도 FK도 복사하지 않습니다.** 컬럼과 데이터만 옵니다.
> "왜 사본은 느리지?" 의 90%가 이것입니다.

---

## 13-1. 제약 조건 6종 한눈에

제약(constraint)은 **DB가 스스로 지키는 규칙**입니다. 애플리케이션 코드가 버그를 내도, 잘못된 배치 스크립트가 돌아도, DB는 거부합니다. 애플리케이션 검증은 "부탁"이고 제약은 "법"입니다.

| 제약 | 막는 것 | 인덱스 생성? | MySQL 특이사항 |
|---|---|---|---|
| `NOT NULL` | NULL 입력 | 아니오 | — |
| `DEFAULT` | (막지 않음) 값 생략 시 기본값 | 아니오 | 표현식 DEFAULT 는 8.0.13+ |
| `PRIMARY KEY` | NULL + 중복 | **예** (클러스터드) | 테이블당 1개 |
| `UNIQUE` | 중복 (NULL 은 예외) | **예** (세컨더리) | NULL 은 몇 개든 허용 |
| `CHECK` | 조건을 만족하지 않는 값 | 아니오 | **8.0.16+ 부터 실제 강제** |
| `FOREIGN KEY` | 부모에 없는 값 | **예** (없으면 자동 생성) | InnoDB 만 지원 |

> 💡 **실무 팁**
> 5.7 이하에서 `CHECK` 는 **파싱만 되고 무시**됐습니다. 그래서 "MySQL 은 CHECK 안 된다"는 말이 아직도 돌아다닙니다.
> 8.0.16 부터는 진짜로 강제됩니다. 5.7 → 8.0 마이그레이션 때 그동안 무시되던 CHECK 가 갑자기 살아나서 INSERT 가 터지는 사고가 실제로 납니다.

---

## 13-2. NOT NULL 과 DEFAULT

```sql
DROP TABLE IF EXISTS s13_members;
CREATE TABLE s13_members (
  member_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email      VARCHAR(120) NOT NULL,                       -- 필수
  nickname   VARCHAR(30)  NOT NULL DEFAULT '익명',         -- 필수지만 기본값 있음
  grade      ENUM('BRONZE','SILVER','GOLD') NOT NULL DEFAULT 'BRONZE',
  points     INT          NOT NULL DEFAULT 0,
  memo       VARCHAR(100) NULL,                           -- 선택 (NULL 허용)
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB;

-- email 만 주고 나머지는 DEFAULT 에 맡긴다
INSERT INTO s13_members (email) VALUES ('a@ex.com');
SELECT member_id, email, nickname, grade, points, memo FROM s13_members;
```

**결과**
```
+-----------+----------+----------+--------+--------+------+
| member_id | email    | nickname | grade  | points | memo |
+-----------+----------+----------+--------+--------+------+
|         1 | a@ex.com | 익명     | BRONZE |      0 | NULL |
+-----------+----------+----------+--------+--------+------+
```

`NOT NULL DEFAULT` 컬럼이라도 **명시적으로 NULL 을 넣으면 거부**됩니다. DEFAULT 는 "값을 생략했을 때"만 동작합니다.

```sql
INSERT INTO s13_members (email, nickname) VALUES ('b@ex.com', NULL);
```

**결과**
```
ERROR 1048 (23000): Column 'nickname' cannot be null
```

DEFAULT 를 쓰고 싶다면 컬럼을 생략하거나, 키워드 `DEFAULT` 를 명시합니다.

```sql
INSERT INTO s13_members (email, nickname, points) VALUES ('c@ex.com', DEFAULT, DEFAULT);
SELECT member_id, email, nickname, points FROM s13_members;
```

**결과**
```
+-----------+----------+----------+--------+
| member_id | email    | nickname | points |
+-----------+----------+----------+--------+
|         1 | a@ex.com | 익명     |      0 |
|         2 | c@ex.com | 익명     |      0 |
+-----------+----------+----------+--------+
```

> 💡 **실무 팁 — `NOT NULL` 을 기본값으로 삼아라**
> NULL 이 섞이면 `=`, `<>`, `IN`, `SUM`, `COUNT` 가 전부 다르게 동작합니다(Step 05 참고). 인덱스 통계도 왜곡됩니다.
> "이 컬럼에 값이 없을 수 있다"가 **비즈니스적으로 의미가 있을 때만** NULL 을 허용하세요.
> "아직 안 정했으니 일단 NULL" 은 나중에 반드시 버그가 됩니다.

> 💡 **`updated_at ... ON UPDATE CURRENT_TIMESTAMP`**
> UPDATE 가 일어날 때 자동으로 현재 시각이 들어갑니다. 애플리케이션이 깜빡해도 DB가 챙겨줍니다.
> 단, 값이 **실제로 바뀐 행**만 갱신됩니다. `SET points = points` 처럼 값이 그대로면 updated_at 도 그대로입니다.

---

## 13-3. PRIMARY KEY 와 UNIQUE — 결정적 차이는 NULL

PK 는 **NULL 을 허용하지 않고**, 테이블당 하나뿐이며, InnoDB 에서는 **클러스터드 인덱스**가 됩니다(Step 15 에서 자세히).

```sql
INSERT INTO s13_members (member_id, email) VALUES (1, 'dup@ex.com');
```

**결과**
```
ERROR 1062 (23000): Duplicate entry '1' for key 's13_members.PRIMARY'
```

UNIQUE 는 중복을 막지만 **NULL 은 몇 개든 허용**합니다. SQL 표준에서 NULL 은 "값이 없음"이라 NULL 끼리 서로 같다고 보지 않기 때문입니다.

```sql
DROP TABLE IF EXISTS s13_uk;
CREATE TABLE s13_uk (
  id   INT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(20) NULL,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB;

INSERT INTO s13_uk (code) VALUES ('A'), (NULL), (NULL), (NULL);   -- NULL 3개 성공
SELECT id, code FROM s13_uk;
```

**결과**
```
+----+------+
| id | code |
+----+------+
|  2 | NULL |
|  3 | NULL |
|  4 | NULL |
|  1 | A    |
+----+------+
```

> ⚠️ **함정 — "UNIQUE 걸었는데 왜 중복이 들어가지?"**
> 위 결과가 정답입니다. NULL 은 UNIQUE 검사를 통과합니다.
> "이메일은 유일해야 한다"면 `email VARCHAR(120) NOT NULL UNIQUE` 처럼 **NOT NULL 을 같이** 걸어야 합니다.
> (참고: 결과 정렬이 `2,3,4,1` 순인 것도 힌트입니다. `uk_code` 인덱스를 타서 NULL 이 먼저 나온 것 — Step 15 의 커버링 인덱스입니다.)

NULL 이 아닌 값의 중복은 당연히 막힙니다.

```sql
INSERT INTO s13_uk (code) VALUES ('A');
```

**결과**
```
ERROR 1062 (23000): Duplicate entry 'A' for key 's13_uk.uk_code'
```

**복합 UNIQUE** — "한 장바구니에 같은 상품은 한 줄만" 같은 규칙에 씁니다.

```sql
DROP TABLE IF EXISTS s13_uk2;
CREATE TABLE s13_uk2 (
  cart_id    INT UNSIGNED NOT NULL,
  product_id INT UNSIGNED NOT NULL,
  qty        INT NOT NULL,
  UNIQUE KEY uk_cart_product (cart_id, product_id)
) ENGINE=InnoDB;

INSERT INTO s13_uk2 VALUES (1, 10, 2), (1, 11, 1), (2, 10, 5);  -- OK
INSERT INTO s13_uk2 VALUES (1, 10, 9);                          -- (1,10) 중복
```

**결과**
```
ERROR 1062 (23000): Duplicate entry '1-10' for key 's13_uk2.uk_cart_product'
```

---

## 13-4. CHECK 제약 — MySQL 8.0.16 부터 진짜로 강제된다

```sql
DROP TABLE IF EXISTS s13_products;
CREATE TABLE s13_products (
  product_id    INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name          VARCHAR(100) NOT NULL,
  price         DECIMAL(10,2) NOT NULL,
  cost          DECIMAL(10,2) NOT NULL,
  stock         INT NOT NULL DEFAULT 0,
  discount_rate TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (product_id),
  CONSTRAINT chk_s13_price_pos CHECK (price >= 0),
  CONSTRAINT chk_s13_stock_pos CHECK (stock >= 0),
  CONSTRAINT chk_s13_margin    CHECK (price >= cost),          -- 컬럼끼리 비교 가능
  CONSTRAINT chk_s13_discount  CHECK (discount_rate BETWEEN 0 AND 90)
) ENGINE=InnoDB;

INSERT INTO s13_products (name, price, cost, stock, discount_rate)
VALUES ('정상상품', 10000, 6000, 10, 20);      -- OK

INSERT INTO s13_products (name, price, cost) VALUES ('역마진상품', 5000, 9000);
```

**결과**
```
ERROR 3819 (HY000): Check constraint 'chk_s13_margin' is violated.
```

CHECK 는 INSERT 뿐 아니라 **UPDATE 에서도 검사**됩니다.

```sql
UPDATE s13_products SET stock = stock - 100 WHERE product_id = 1;   -- 10 - 100 = -90
```

**결과**
```
ERROR 3819 (HY000): Check constraint 'chk_s13_stock_pos' is violated.
```

### CHECK 와 NULL — UNKNOWN 은 통과한다

CHECK 는 결과가 **FALSE 일 때만** 거부합니다. NULL 이 끼면 결과는 `UNKNOWN` 이고, UNKNOWN 은 **통과**입니다.

```sql
DROP TABLE IF EXISTS s13_chk_null;
CREATE TABLE s13_chk_null (
  id  INT AUTO_INCREMENT PRIMARY KEY,
  age INT NULL,
  CONSTRAINT chk_age CHECK (age >= 18)
) ENGINE=InnoDB;

INSERT INTO s13_chk_null (age) VALUES (20), (NULL);   -- 둘 다 성공!
SELECT id, age FROM s13_chk_null;
```

**결과**
```
+----+------+
| id | age  |
+----+------+
|  1 |   20 |
|  2 | NULL |
+----+------+
```

```sql
INSERT INTO s13_chk_null (age) VALUES (10);
```

**결과**
```
ERROR 3819 (HY000): Check constraint 'chk_age' is violated.
```

> ⚠️ **함정 — CHECK 는 NULL 을 막아주지 않는다**
> `CHECK (age >= 18)` 만 걸어두고 "18세 미만은 절대 못 들어온다"고 믿으면 안 됩니다. `NULL` 은 유유히 통과합니다.
> NULL 도 막으려면 컬럼에 `NOT NULL` 을 같이 걸거나, `CHECK (age IS NOT NULL AND age >= 18)` 로 명시하세요.

### CHECK 조회 / 일시 해제

```sql
SELECT cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE, tc.ENFORCED
FROM information_schema.CHECK_CONSTRAINTS cc
JOIN information_schema.TABLE_CONSTRAINTS tc
  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
 AND tc.CONSTRAINT_NAME   = cc.CONSTRAINT_NAME
WHERE cc.CONSTRAINT_SCHEMA = 'shop' AND tc.TABLE_NAME = 's13_products';
```

**결과**
```
+-------------------+------------------------------------+----------+
| CONSTRAINT_NAME   | CHECK_CLAUSE                       | ENFORCED |
+-------------------+------------------------------------+----------+
| chk_s13_price_pos | (`price` >= 0)                     | YES      |
| chk_s13_stock_pos | (`stock` >= 0)                     | YES      |
| chk_s13_margin    | (`price` >= `cost`)                | YES      |
| chk_s13_discount  | (`discount_rate` between 0 and 90) | YES      |
+-------------------+------------------------------------+----------+
```

데이터 이관처럼 잠깐 규칙을 꺼야 할 때는 `NOT ENFORCED` 로 끕니다.

```sql
ALTER TABLE s13_products ALTER CHECK chk_s13_margin NOT ENFORCED;
INSERT INTO s13_products (name, price, cost) VALUES ('역마진 허용됨', 5000, 9000);  -- 통과!
SELECT product_id, name, price, cost FROM s13_products;
```

**결과**
```
+------------+---------------------+----------+---------+
| product_id | name                | price    | cost    |
+------------+---------------------+----------+---------+
|          1 | 정상상품            | 10000.00 | 6000.00 |
|          2 | 역마진 허용됨       |  5000.00 | 9000.00 |
+------------+---------------------+----------+---------+
```

그런데 다시 켜려고 하면?

```sql
ALTER TABLE s13_products ALTER CHECK chk_s13_margin ENFORCED;
```

**결과**
```
ERROR 3819 (HY000): Check constraint 'chk_s13_margin' is violated.
```

> ⚠️ **함정 — 껐다 켤 때 기존 데이터를 다시 검사한다**
> `NOT ENFORCED` 로 끄고 더러운 데이터를 넣었다면, **다시 켤 수 없습니다.** 위반 행을 먼저 치워야 합니다.
> ```sql
> DELETE FROM s13_products WHERE name = '역마진 허용됨';
> ALTER TABLE s13_products ALTER CHECK chk_s13_margin ENFORCED;   -- 이제 성공
> ```
> 제약을 끄는 순간, **다시 켤 수 있는 상태로 데이터를 유지하는 책임이 당신에게 넘어옵니다.**

CHECK 추가/삭제:

```sql
ALTER TABLE s13_products ADD CONSTRAINT chk_s13_name_len CHECK (CHAR_LENGTH(name) >= 2);
ALTER TABLE s13_products DROP CHECK chk_s13_name_len;
```

> ⚠️ **CHECK 에 못 쓰는 것**: 서브쿼리, `NOW()`/`RAND()` 같은 비결정적 함수, 사용자 변수, 다른 테이블 참조, AUTO_INCREMENT 컬럼.
> "주문일은 오늘 이전이어야 한다" 같은 규칙은 CHECK 로 못 만듭니다(`NOW()` 금지). 트리거나 애플리케이션에서 처리하세요.

---

## 13-5. FOREIGN KEY — 참조 무결성

FK 는 "자식 테이블의 값은 반드시 부모 테이블에 존재해야 한다"를 강제합니다.

```sql
DROP TABLE IF EXISTS s13_child;  DROP TABLE IF EXISTS s13_parent;

CREATE TABLE s13_parent (
  parent_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name      VARCHAR(30) NOT NULL,
  PRIMARY KEY (parent_id)
) ENGINE=InnoDB;

CREATE TABLE s13_child (
  child_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  parent_id INT UNSIGNED NULL,
  name      VARCHAR(30) NOT NULL,
  PRIMARY KEY (child_id),
  CONSTRAINT fk_s13_child_parent
    FOREIGN KEY (parent_id) REFERENCES s13_parent(parent_id)
) ENGINE=InnoDB;

INSERT INTO s13_parent (parent_id, name) VALUES (1,'P1'), (2,'P2');
INSERT INTO s13_child (parent_id, name) VALUES (1,'C1'), (1,'C2'), (2,'C3');

-- 부모에 없는 99 를 넣으면?
INSERT INTO s13_child (parent_id, name) VALUES (99, 'C99');
```

**결과**
```
ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails
(`shop`.`s13_child`, CONSTRAINT `fk_s13_child_parent` FOREIGN KEY (`parent_id`)
 REFERENCES `s13_parent` (`parent_id`))
```

자식이 있는 부모를 지우려 해도 막힙니다(기본 동작 = RESTRICT).

```sql
DELETE FROM s13_parent WHERE parent_id = 1;
```

**결과**
```
ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key constraint fails ...
```

### FK 는 인덱스를 자동으로 만든다

```sql
SHOW INDEX FROM s13_child;
```

**결과**
```
+-----------+------------+---------------------+--------------+-------------+-------------+
| Table     | Non_unique | Key_name            | Seq_in_index | Column_name | Cardinality |
+-----------+------------+---------------------+--------------+-------------+-------------+
| s13_child |          0 | PRIMARY             |            1 | child_id    |           3 |
| s13_child |          1 | fk_s13_child_parent |            1 | parent_id   |           2 |
+-----------+------------+---------------------+--------------+-------------+-------------+
```

인덱스를 만들라고 한 적이 없는데 `fk_s13_child_parent` 라는 인덱스가 생겼습니다.
InnoDB 는 FK 컬럼에 인덱스가 **없으면 자동으로 만듭니다.** 부모 행을 지울 때 "이 부모를 참조하는 자식이 있나?"를 매번 확인해야 하는데, 인덱스가 없으면 그때마다 자식 테이블 풀스캔이 되기 때문입니다.

> 💡 **실무 팁**
> 자동 생성된 인덱스는 **FK 컬럼 단독**입니다. 만약 `(parent_id, created_at)` 복합 인덱스를 이미 만들어 뒀다면, 그 인덱스의 선두 컬럼이 `parent_id` 이므로 InnoDB 는 **추가 인덱스를 만들지 않습니다.** (Step 15 의 "선두 컬럼 규칙")

---

## 13-5b. ON DELETE / ON UPDATE 5종 — 실제 동작으로 구별하기

| 옵션 | 부모 행이 DELETE/UPDATE 될 때 자식은 | InnoDB 지원 |
|---|---|---|
| `RESTRICT` | **거부** (기본값) | O |
| `NO ACTION` | **거부** — InnoDB 에서는 RESTRICT 와 동일 | O |
| `CASCADE` | 자식도 **같이 삭제/변경** | O |
| `SET NULL` | 자식 FK 컬럼을 **NULL 로** | O (컬럼이 NULL 허용일 때만) |
| `SET DEFAULT` | 자식 FK 컬럼을 기본값으로 | **문법만 통과, 실행 시 거부** |

### (1) CASCADE

```sql
CREATE TABLE s13_cat (
  cat_id INT UNSIGNED NOT NULL PRIMARY KEY,
  name   VARCHAR(20) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE s13_post (
  post_id INT AUTO_INCREMENT PRIMARY KEY,
  cat_id  INT UNSIGNED NOT NULL,
  title   VARCHAR(30) NOT NULL,
  CONSTRAINT fk_post_cat FOREIGN KEY (cat_id) REFERENCES s13_cat(cat_id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB;

INSERT INTO s13_cat VALUES (1,'공지'),(2,'자유');
INSERT INTO s13_post (cat_id,title) VALUES (1,'공지1'),(1,'공지2'),(2,'자유1');

-- ON UPDATE CASCADE: 부모 PK 를 바꾸면 자식 FK 도 따라간다
UPDATE s13_cat SET cat_id = 10 WHERE cat_id = 1;
SELECT post_id, cat_id, title FROM s13_post ORDER BY post_id;
```

**결과**
```
+---------+--------+---------+
| post_id | cat_id | title   |
+---------+--------+---------+
|       1 |     10 | 공지1   |     ← 1 이 10 으로 자동 변경됨
|       2 |     10 | 공지2   |
|       3 |      2 | 자유1   |
+---------+--------+---------+
```

```sql
-- ON DELETE CASCADE: 부모를 지우면 자식도 사라진다
DELETE FROM s13_cat WHERE cat_id = 10;
SELECT post_id, cat_id, title FROM s13_post ORDER BY post_id;
```

**결과**
```
+---------+--------+---------+
| post_id | cat_id | title   |
+---------+--------+---------+
|       3 |      2 | 자유1   |
+---------+--------+---------+
```

post 1, 2 가 소리 없이 사라졌습니다.

### (2) SET NULL

```sql
CREATE TABLE s13_dept (
  dept_id INT UNSIGNED NOT NULL PRIMARY KEY,
  name    VARCHAR(20) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE s13_emp (
  emp_id  INT AUTO_INCREMENT PRIMARY KEY,
  dept_id INT UNSIGNED NULL,                 -- NULL 허용이어야 함!
  name    VARCHAR(20) NOT NULL,
  CONSTRAINT fk_emp_dept FOREIGN KEY (dept_id) REFERENCES s13_dept(dept_id)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB;

INSERT INTO s13_dept VALUES (1,'개발'),(2,'영업');
INSERT INTO s13_emp (dept_id,name) VALUES (1,'김코드'),(1,'박서버'),(2,'이세일');

DELETE FROM s13_dept WHERE dept_id = 1;      -- 부서가 없어져도 사원은 남는다
SELECT emp_id, dept_id, name FROM s13_emp ORDER BY emp_id;
```

**결과**
```
+--------+---------+-----------+
| emp_id | dept_id | name      |
+--------+---------+-----------+
|      1 |    NULL | 김코드    |
|      2 |    NULL | 박서버    |
|      3 |       2 | 이세일    |
+--------+---------+-----------+
```

FK 컬럼이 `NOT NULL` 인데 `SET NULL` 을 걸면 **테이블 생성 자체가 실패**합니다.

```sql
CREATE TABLE s13_fk_bad (
  id INT AUTO_INCREMENT PRIMARY KEY,
  dept_id INT UNSIGNED NOT NULL,                       -- NOT NULL 인데
  CONSTRAINT fk_bad FOREIGN KEY (dept_id) REFERENCES s13_dept(dept_id)
    ON DELETE SET NULL                                 -- SET NULL?
) ENGINE=InnoDB;
```

**결과**
```
ERROR 1830 (HY000): Column 'dept_id' cannot be NOT NULL:
needed in a foreign key constraint 'fk_bad' SET NULL
```

### (3)(4) RESTRICT / NO ACTION

```sql
CREATE TABLE s13_prod (
  prod_id INT UNSIGNED NOT NULL PRIMARY KEY,
  name    VARCHAR(20) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE s13_order_line (
  line_id INT AUTO_INCREMENT PRIMARY KEY,
  prod_id INT UNSIGNED NOT NULL,
  CONSTRAINT fk_line_prod FOREIGN KEY (prod_id) REFERENCES s13_prod(prod_id)
    ON DELETE RESTRICT ON UPDATE NO ACTION
) ENGINE=InnoDB;

INSERT INTO s13_prod VALUES (1,'상품A');
INSERT INTO s13_order_line (prod_id) VALUES (1);

DELETE FROM s13_prod WHERE prod_id = 1;             -- RESTRICT
UPDATE s13_prod SET prod_id = 9 WHERE prod_id = 1;  -- NO ACTION
```

**결과** (둘 다 동일한 에러)
```
ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key constraint fails
(`shop`.`s13_order_line`, CONSTRAINT `fk_line_prod` FOREIGN KEY (`prod_id`)
 REFERENCES `s13_prod` (`prod_id`) ON DELETE RESTRICT)
```

> 💡 표준 SQL 에서 `NO ACTION` 은 "트랜잭션 끝에 검사"(지연 검사), `RESTRICT` 는 "즉시 검사"로 다릅니다.
> **InnoDB 는 지연 검사를 지원하지 않으므로 둘이 완전히 같습니다.** 다른 DB에서 넘어왔다면 이 차이를 기대하지 마세요.

### (5) SET DEFAULT — 만들어지지만 동작하지 않는다

```sql
CREATE TABLE s13_fk_setdefault (
  id      INT AUTO_INCREMENT PRIMARY KEY,
  prod_id INT UNSIGNED NOT NULL DEFAULT 1,
  CONSTRAINT fk_sd FOREIGN KEY (prod_id) REFERENCES s13_prod(prod_id)
    ON DELETE SET DEFAULT
) ENGINE=InnoDB;
-- 테이블 생성 성공! (에러 없음)

INSERT INTO s13_prod VALUES (2,'상품B');
INSERT INTO s13_fk_setdefault (prod_id) VALUES (2);

DELETE FROM s13_prod WHERE prod_id = 2;
```

**결과**
```
ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key constraint fails ...
```

> ⚠️ **함정 — SET DEFAULT 는 조용한 거짓말이다**
> InnoDB 는 `ON DELETE SET DEFAULT` 를 **문법적으로는 받아주지만 구현하지 않았습니다.**
> 테이블 생성도 되고, `information_schema` 조회하면 `DELETE_RULE = SET DEFAULT` 라고 버젓이 나옵니다.
> 그런데 실제로 부모를 지우면 그냥 **RESTRICT 처럼 거부**합니다. 스키마만 보고 "기본값으로 바뀌겠구나" 하고 믿으면 안 됩니다.

전체 참조 동작을 한눈에:

```sql
SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA='shop' AND TABLE_NAME LIKE 's13%'
ORDER BY TABLE_NAME;
```

**결과**
```
+-----------------+-------------------+-----------------------+-------------+-------------+
| CONSTRAINT_NAME | TABLE_NAME        | REFERENCED_TABLE_NAME | UPDATE_RULE | DELETE_RULE |
+-----------------+-------------------+-----------------------+-------------+-------------+
| fk_emp_dept     | s13_emp           | s13_dept              | CASCADE     | SET NULL    |
| fk_sd           | s13_fk_setdefault | s13_prod              | NO ACTION   | SET DEFAULT |
| fk_line_prod    | s13_order_line    | s13_prod              | NO ACTION   | RESTRICT    |
| fk_post_cat     | s13_post          | s13_cat               | CASCADE     | CASCADE     |
+-----------------+-------------------+-----------------------+-------------+-------------+
```

`RESTRICT` 를 명시했는데 `NO ACTION` 으로 나오는 항목도 있습니다(InnoDB 내부적으로 같으니까요).

---

## 13-6. FK 가 성능과 락에 미치는 영향

FK 는 공짜가 아닙니다. 매 INSERT/UPDATE/DELETE 마다 **추가 조회 + 추가 락**이 발생합니다.

### 실측 1 — INSERT 오버헤드

`customers`(30행, 완전히 캐시됨)를 참조하는 자식 테이블에 20만 행씩 INSERT 해봅니다.

```sql
CREATE TABLE s13_fk_on (
  id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT UNSIGNED NOT NULL,
  amt INT NOT NULL,
  KEY idx_cust (customer_id),
  CONSTRAINT fk_s13_on FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB;

CREATE TABLE s13_fk_off (       -- 구조 동일, FK 만 없음
  id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT UNSIGNED NOT NULL,
  amt INT NOT NULL,
  KEY idx_cust (customer_id)
) ENGINE=InnoDB;

INSERT INTO s13_fk_on (customer_id, amt)
SELECT 1 + (a.n * 7 + b.n) % 30, (a.n * b.n) % 1000
FROM tally a JOIN tally b ON b.n <= 20 WHERE a.n <= 10000;

INSERT INTO s13_fk_off (customer_id, amt)
SELECT 1 + (a.n * 7 + b.n) % 30, (a.n * b.n) % 1000
FROM tally a JOIN tally b ON b.n <= 20 WHERE a.n <= 10000;
```

**결과**
```
FK 있음 : Query OK, 200000 rows affected (0.662 sec)
FK 없음 : Query OK, 200000 rows affected (0.627 sec)
```

**약 5% 차이.** 생각보다 작죠? 부모가 30행짜리 초소형 테이블이라 PK 조회가 전부 메모리에서 끝나기 때문입니다.
→ **결론: 부모 테이블이 작고 캐시에 올라와 있으면 FK 의 INSERT 비용은 거의 무시할 수 있습니다.**

### 실측 2 — CASCADE 는 "1행 삭제"를 "2만행 삭제"로 바꾼다

여기가 진짜 위험한 곳입니다.

```sql
CREATE TABLE s13_cas_parent (id INT UNSIGNED NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE s13_cas_child (
  cid INT AUTO_INCREMENT PRIMARY KEY,
  pid INT UNSIGNED NOT NULL,
  pad CHAR(100) NOT NULL DEFAULT 'x',
  KEY idx_pid (pid),
  CONSTRAINT fk_cas_c FOREIGN KEY (pid) REFERENCES s13_cas_parent(id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT INTO s13_cas_parent SELECT n FROM tally WHERE n <= 10;
INSERT INTO s13_cas_child (pid)
SELECT 1 + (a.n * 3 + b.n) % 10 FROM tally a JOIN tally b ON b.n <= 20 WHERE a.n <= 10000;
-- 부모 10행, 자식 20만행 (부모 1행당 자식 2만행)

DELETE FROM s13_cas_parent WHERE id = 1;   -- 딱 1행 삭제!
```

**결과**
```
Query OK, 1 row affected (0.057 sec)
```

```sql
-- 비교: 자식 2만 행을 직접 지우면?
DELETE FROM s13_cas_child WHERE pid = 2;
```

**결과**
```
Query OK, 20000 rows affected (0.055 sec)
```

> ⚠️ **함정 — "1 row affected" 를 믿지 마라**
> 위 DELETE 는 `1 row affected` 라고 보고하지만, 실제로는 **자식 20,000 행을 삭제**했습니다.
> 시간(0.057초)이 자식 2만 행 직접 삭제(0.055초)와 **똑같다**는 것이 증거입니다.
>
> 실무 사고 시나리오:
> 1. 운영자가 카테고리 1건을 지웠다 → `1 row affected` → "잘 됐네"
> 2. 실제로는 CASCADE 로 상품 5만건, 리뷰 30만건이 함께 삭제됨
> 3. 그 트랜잭션이 35만 행에 X락을 잡고 있는 동안 서비스 전체가 멈춤
> 4. 롤백하려고 하면 언두 로그 35만 건을 되감아야 해서 더 오래 걸림
>
> **CASCADE 는 자식이 적을 때만 쓰세요.** 자식이 많은 관계(주문→주문상세는 OK, 카테고리→상품은 위험)에는
> `RESTRICT` 를 걸어두고, 삭제는 애플리케이션이 배치로 나눠서 하게 하는 편이 안전합니다.

### FK 와 락

자식 테이블에 INSERT 하면 InnoDB 는 **부모의 해당 행에 공유락(S lock)** 을 겁니다. "내가 참조하는 동안 이 부모를 지우지 마"라는 뜻입니다.

```
세션 A: INSERT INTO orders (customer_id, ...) VALUES (1, ...);
         → customers 의 customer_id=1 행에 S락

세션 B: UPDATE customers SET grade='VIP' WHERE customer_id = 1;
         → X락 필요 → 세션 A 가 커밋할 때까지 대기
```

인기 있는 부모 행(예: "기본 카테고리", "게스트 사용자")이 있으면 그 한 행이 **락 병목(hot row)** 이 됩니다.

> 💡 **실무 팁 — 대용량 적재 시 FK 끄기**
> ```sql
> SET FOREIGN_KEY_CHECKS = 0;
> -- LOAD DATA INFILE ... / 대량 INSERT
> SET FOREIGN_KEY_CHECKS = 1;
> ```

> ⚠️ **함정 — 다시 켜도 이미 들어간 고아 행은 검사하지 않는다**
> ```sql
> SET FOREIGN_KEY_CHECKS = 0;
> INSERT INTO s13_child (parent_id, name) VALUES (777, '유령자식');  -- 부모 777 없음
> SET FOREIGN_KEY_CHECKS = 1;                                        -- 다시 켬
>
> SELECT COUNT(*) AS orphan_rows
> FROM s13_child c LEFT JOIN s13_parent p ON p.parent_id = c.parent_id
> WHERE c.parent_id IS NOT NULL AND p.parent_id IS NULL;
> ```
> **결과**
> ```
> +-------------+
> | orphan_rows |
> +-------------+
> |           1 |
> +-------------+
> ```
> `FOREIGN_KEY_CHECKS = 1` 로 되돌려도 MySQL 은 **기존 데이터를 재검증하지 않습니다.** 고아 행은 그대로 남아 있고,
> 그 이후로도 조용히 살아 있습니다. 끄고 넣었다면 켠 뒤에 위 쿼리로 **직접 검증**해야 합니다.

### FK, 써야 하나 말아야 하나

| 상황 | 권장 |
|---|---|
| 일반적인 OLTP 서비스, 단일 DB | **FK 사용** — 데이터 정합성이 성능보다 훨씬 비쌉니다 |
| 초대용량 쓰기 (초당 수만 INSERT) | FK 제거 검토 — 대신 애플리케이션/배치로 정합성 검증 |
| 샤딩된 DB (부모/자식이 다른 서버) | FK 불가 — 물리적으로 못 검사 |
| 대량 데이터 마이그레이션 | 일시적으로 `FOREIGN_KEY_CHECKS=0`, 끝나고 검증 쿼리 필수 |

---

## 13-7. AUTO_INCREMENT 와 "구멍"

**AUTO_INCREMENT 값은 연속을 보장하지 않습니다.** 이건 버그가 아니라 설계입니다.

```sql
DROP TABLE IF EXISTS s13_ai;
CREATE TABLE s13_ai (
  id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB;

INSERT INTO s13_ai (code) VALUES ('a'),('b'),('c');   -- id 1,2,3
```

### 구멍 1 — 롤백해도 카운터는 되돌아가지 않는다

```sql
START TRANSACTION;
INSERT INTO s13_ai (code) VALUES ('rollback-me');   -- id 4 를 가져감
ROLLBACK;                                            -- 행은 사라지지만...
INSERT INTO s13_ai (code) VALUES ('d');
SELECT id, code FROM s13_ai ORDER BY id;
```

**결과**
```
+----+------+
| id | code |
+----+------+
|  1 | a    |
|  2 | b    |
|  3 | c    |
|  5 | d    |     ← 4 는 영원히 사라짐
+----+------+
```

카운터가 롤백을 따라가면, 동시에 INSERT 하는 다른 세션들이 전부 그 카운터를 기다려야 합니다. **동시성을 위해 일부러 되돌리지 않는 것입니다.**

### 구멍 2 — 실패한 INSERT 도 카운터를 소모한다

```sql
INSERT INTO s13_ai (code) VALUES ('a');   -- UNIQUE 위반 → 실패, 그래도 6번을 가져감
-- ERROR 1062 (23000): Duplicate entry 'a' for key 's13_ai.uk_code'

INSERT INTO s13_ai (code) VALUES ('d2');  -- 다음 id 는 6 이 아니라 7
SELECT id, code FROM s13_ai ORDER BY id;
```

**결과**
```
+----+------+
| id | code |
+----+------+
|  1 | a    |
|  2 | b    |
|  3 | c    |
|  5 | d    |
|  7 | d2   |     ← 실패한 INSERT 가 6 을 태워버림
+----+------+
```

`INSERT ... ON DUPLICATE KEY UPDATE` 도 마찬가지입니다. UPDATE 로 처리되더라도 AUTO_INCREMENT 값은 소모됩니다.

```sql
INSERT INTO s13_ai (code) VALUES ('a') ON DUPLICATE KEY UPDATE code = VALUES(code);  -- 8 소모
INSERT INTO s13_ai (code) VALUES ('e');
SELECT id, code FROM s13_ai ORDER BY id;
```

**결과**
```
+----+------+
| id | code |
+----+------+
|  1 | a    |
|  2 | b    |
|  3 | c    |
|  5 | d    |
|  7 | d2   |
|  9 | e    |     ← 8 도 사라짐
+----+------+
```

> ⚠️ **함정 — `ON DUPLICATE KEY UPDATE` 를 자주 쓰는 테이블은 id 가 폭주한다**
> "매일 upsert 배치를 도는데 id 가 INT 한계(21억)를 넘었다"는 사고가 실제로 있습니다.
> 매번 upsert 할 때마다 실제 INSERT 가 아니어도 카운터를 태우기 때문입니다. `BIGINT` 를 쓰거나 `INSERT IGNORE` 대신
> `UPDATE` → 없으면 `INSERT` 순서로 바꾸는 것을 검토하세요.

### 구멍 3 — DELETE 는 카운터를 되돌리지 않지만, TRUNCATE 는 리셋한다

```sql
DELETE FROM s13_ai;                            -- 전부 삭제 (직전 최대 id = 12)
INSERT INTO s13_ai (code) VALUES ('after-delete');
SELECT id, code FROM s13_ai;
```

**결과**
```
+----+--------------+
| id | code         |
+----+--------------+
| 13 | after-delete |     ← 테이블이 비었는데도 13 부터 이어감
+----+--------------+
```

```sql
TRUNCATE TABLE s13_ai;                         -- 전부 삭제 + 카운터 리셋
INSERT INTO s13_ai (code) VALUES ('after-truncate');
SELECT id, code FROM s13_ai;
```

**결과**
```
+----+----------------+
| id | code           |
+----+----------------+
|  1 | after-truncate |     ← 1 로 리셋됨
+----+----------------+
```

카운터를 직접 조정할 수도 있습니다(현재 최댓값보다 작게는 설정 안 됩니다).

```sql
ALTER TABLE s13_ai AUTO_INCREMENT = 1000;
INSERT INTO s13_ai (code) VALUES ('from-1000');
SELECT id, code FROM s13_ai;
```

**결과**
```
+------+----------------+
| id   | code           |
+------+----------------+
|    1 | after-truncate |
| 1000 | from-1000      |
+------+----------------+
```

> 💡 **MySQL 8.0 의 변화 — 카운터가 영속(persistent)이 되었다**
> 5.7 까지는 AUTO_INCREMENT 카운터가 **메모리에만** 있었습니다. 그래서 서버를 재시작하면
> `SELECT MAX(id)+1` 로 다시 계산했고, 최댓값 행을 지우고 재시작하면 **id 가 재사용**되는 무서운 일이 있었습니다.
> 8.0 부터는 카운터가 리두 로그에 기록되어 재시작해도 유지됩니다. **8.0 에서는 id 재사용이 없습니다.**

> 💡 **`LAST_INSERT_ID()`**
> - **세션 단위**입니다. 다른 세션이 INSERT 해도 내 값은 안 바뀝니다. (동시성 안전)
> - 다중 행 INSERT 에서는 **첫 번째 행의 id** 를 돌려줍니다. 마지막이 아닙니다.
> ```sql
> INSERT INTO s13_ai (code) VALUES ('g'),('h'),('i');
> SELECT LAST_INSERT_ID() AS last_id, ROW_COUNT() AS rows_inserted;
> ```
> ```
> +---------+---------------+
> | last_id | rows_inserted |
> +---------+---------------+
> |      10 |             3 |     ← 10,11,12 가 들어갔는데 10 을 반환
> +---------+---------------+
> ```

> 💡 **결론: id 를 "개수"나 "순번"으로 쓰지 마세요.**
> "id 가 1000번이니까 가입자가 1000명" → 틀립니다. `COUNT(*)` 를 쓰세요.
> id 는 **유일성**만 보장하는 값입니다. 연속성도, 개수도 보장하지 않습니다.

---

## 13-8. 정규화 (1NF ~ 3NF) 와 반정규화

정규화는 **중복을 제거해서 이상현상(anomaly)을 없애는** 과정입니다.

### 정규화 안 된 테이블 (0NF)

```
+---------+----------------------+---------------------------------+-----------+-------------+
| order_id| customer_name        | products                        | cust_city | city_zone   |
+---------+----------------------+---------------------------------+-----------+-------------+
| 1       | 김민수               | 셔츠:2, 팬츠:1                  | 서울      | 수도권      |
| 2       | 김민수               | 스니커즈:1                      | 서울      | 수도권      |
| 3       | 이지은               | 셔츠:1, 노트북:1, 마우스:2      | 서울      | 수도권      |
+---------+----------------------+---------------------------------+-----------+-------------+
```

이 테이블의 문제:
- **갱신 이상**: 김민수가 부산으로 이사 → 2개 행을 다 고쳐야 함. 하나 놓치면 데이터가 모순됨.
- **삽입 이상**: 아직 주문한 적 없는 고객은 등록할 수가 없음.
- **삭제 이상**: 주문 3을 지우면 이지은이라는 고객이 통째로 사라짐.

### 1NF — 모든 컬럼이 원자값(atomic)일 것

`products` 컬럼에 `'셔츠:2, 팬츠:1'` 처럼 **여러 값이 뭉쳐 있으면 1NF 위반**입니다.

이 컬럼으로는 "셔츠를 산 사람"을 찾을 수 없습니다. `LIKE '%셔츠%'` 는 인덱스를 못 타고(Step 15), "셔츠맨투맨"까지 걸립니다.

**해결: 행으로 쪼갠다** → `order_items` 테이블 분리

```
orders                    order_items
+----------+-----------+  +----------+------------+----------+
| order_id | cust_name |  | order_id | product    | quantity |
+----------+-----------+  +----------+------------+----------+
| 1        | 김민수    |  | 1        | 셔츠       | 2        |
| 2        | 김민수    |  | 1        | 팬츠       | 1        |
| 3        | 이지은    |  | 2        | 스니커즈   | 1        |
+----------+-----------+  | 3        | 셔츠       | 1        |
                          | 3        | 노트북     | 1        |
                          | 3        | 마우스     | 2        |
                          +----------+------------+----------+
```

> ⚠️ **1NF 위반의 현대적 변종 — 콤마 구분 문자열**
> `tags VARCHAR(255)` 에 `'세일,신상,베스트'` 를 넣는 설계는 **지금도 흔한 1NF 위반**입니다.
> `FIND_IN_SET('세일', tags)` 로 조회할 수는 있지만 **인덱스를 절대 못 탑니다.** 풀스캔 확정입니다.
> 태그가 필요하면 `product_tags(product_id, tag)` 테이블을 만드세요.
> (JSON 컬럼은 다릅니다 — 8.0 은 JSON 에 함수 인덱스를 걸 수 있습니다. Step 14/18 참고)

### 2NF — 1NF + 부분 함수 종속 제거

**복합 PK 의 "일부"에만 의존하는 컬럼이 있으면 2NF 위반**입니다.

```
order_items (PK = order_id + product_id)
+----------+------------+----------+---------------+
| order_id | product_id | quantity | product_name  |   ← product_name 은
+----------+------------+----------+---------------+      product_id 에만 의존!
| 1        | 1          | 2        | 옥스퍼드 셔츠 |      (order_id 와 무관)
| 3        | 1          | 1        | 옥스퍼드 셔츠 |   ← 중복 저장
+----------+------------+----------+---------------+
```

상품명이 바뀌면 이 테이블의 모든 행을 고쳐야 합니다.

**해결**: `product_name` 을 `products` 테이블로 보내고, `order_items` 에는 `product_id` 만 둡니다.

### 3NF — 2NF + 이행적 함수 종속 제거

**PK 가 아닌 컬럼이 다른 PK 아닌 컬럼에 의존하면 3NF 위반**입니다.

```
customers
+-------------+--------+-----------+-------------+
| customer_id | name   | city      | city_zone   |   ← city_zone 은 city 에 의존
+-------------+--------+-----------+-------------+      (customer_id → city → city_zone)
| 1           | 김민수 | 서울      | 수도권      |
| 2           | 이지은 | 서울      | 수도권      |   ← 중복
| 3           | 박철수 | 부산      | 영남        |
+-------------+--------+-----------+-------------+
```

"서울의 zone 을 '수도권'에서 '서울권'으로 바꾸자" → 서울 사는 고객 전부를 UPDATE 해야 합니다.

**해결**: `cities(city, zone)` 테이블 분리.

우리 `shop` 스키마는 3NF 를 대체로 지키고 있습니다. `orders.shipping_city` 가 `customers.city` 와 중복되는 것처럼 보이지만, **배송지는 주문 시점의 스냅샷**이라 의미가 다릅니다. 이건 중복이 아니라 **의도된 별개의 사실**입니다. (`order_items.unit_price` 도 마찬가지 — 주문 시점의 가격을 얼려둔 것입니다.)

> 💡 **"주문 시점 스냅샷"은 반정규화가 아니다**
> 값이 같아 보여도 **의미가 다르면 별개의 컬럼**입니다. 상품 가격이 나중에 오르더라도 과거 주문의 결제액은 바뀌면 안 됩니다.
> `order_items.unit_price` 를 지우고 `JOIN products` 로 대체하면 **회계가 망가집니다.**

### 정규화 요약

| 단계 | 규칙 | 한 줄 요약 |
|---|---|---|
| 1NF | 모든 값이 원자값 | 한 칸에 하나의 값만 |
| 2NF | 1NF + 부분 종속 제거 | PK 전체에 의존해야 함 |
| 3NF | 2NF + 이행 종속 제거 | PK가 아닌 것에 의존하지 말 것 |

암기용: **"키에, 오직 키에만, 키의 전부에 의존하라"** (the key, the whole key, and nothing but the key)

### 반정규화 — 일부러 규칙을 깨는 것

**반정규화(denormalization)는 읽기 성능을 위해 중복을 의도적으로 허용하는 것**입니다.

우리 `shop` 스키마에도 이미 있습니다: **`orders.total_amount`**

```sql
-- 정규화 원칙대로라면 total_amount 는 저장하면 안 되고 매번 계산해야 함
SELECT o.order_id, SUM(oi.quantity * oi.unit_price) AS total
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY o.order_id;

-- 하지만 실제로는 컬럼에 저장해 뒀다
SELECT order_id, total_amount FROM orders;
```

**왜?** 주문 목록 화면은 하루에 수백만 번 열리는데, 그때마다 `order_items` 를 JOIN + GROUP BY 하면 감당이 안 됩니다.

**대가**: `order_items` 가 바뀔 때마다 `orders.total_amount` 를 **반드시** 같이 갱신해야 합니다. 하나라도 빠뜨리면 **DB 안에서 두 값이 서로 다른 진실을 말하게 됩니다.**

| | 정규화 | 반정규화 |
|---|---|---|
| 데이터 정합성 | **강함** (한 곳에만 저장) | 약함 (동기화 책임이 개발자에게) |
| 쓰기 성능 | 좋음 (한 곳만 UPDATE) | 나쁨 (여러 곳 UPDATE) |
| 읽기 성능 | 나쁨 (JOIN 필요) | **좋음** (JOIN 없이 조회) |
| 저장 공간 | 적음 | 많음 |

> 💡 **실무 판단 기준**
> 1. **일단 3NF 로 설계하세요.** 반정규화는 "느려서 못 쓰겠다"는 **측정된 증거**가 나온 다음에 합니다.
> 2. 반정규화를 했다면 **동기화 수단을 반드시 함께 만드세요** — 트리거, 애플리케이션 트랜잭션, 또는 야간 정합성 검증 배치.
> 3. **집계값은 생성 컬럼(Generated Column)이나 뷰로 대체할 수 있는지 먼저 검토하세요** → Step 14 에서 다룹니다.
>
> ⚠️ 가장 흔한 실패: "성능 때문에" 반정규화를 하고, 동기화 코드를 한 군데 빠뜨리고, 6개월 뒤 정산이 안 맞아서 밤새 데이터를 맞추는 것.

### 실습 정리

```sql
-- 이 스텝에서 만든 사본 테이블 정리
DROP TABLE IF EXISTS s13_post, s13_cat, s13_emp, s13_dept,
                     s13_order_line, s13_fk_setdefault, s13_prod,
                     s13_cas_child, s13_cas_parent,
                     s13_child, s13_parent,
                     s13_fk_on, s13_fk_off,
                     s13_members, s13_uk, s13_uk2,
                     s13_products, s13_chk_null, s13_ai;
```

---

## 정리

| 주제 | 핵심 |
|---|---|
| `NOT NULL` | DEFAULT 가 있어도 **명시적 NULL 은 거부**된다 |
| `UNIQUE` | NULL 은 **몇 개든 허용**. 유일성이 필요하면 `NOT NULL` 을 같이 걸어라 |
| `CHECK` | **8.0.16+ 부터 실제 강제**. NULL 은 UNKNOWN → **통과**한다 |
| `CHECK ... ENFORCED` | 다시 켤 때 **기존 데이터를 재검사**한다 |
| FK 기본 동작 | `RESTRICT` (= `NO ACTION`, InnoDB 에서 동일) |
| FK `SET DEFAULT` | 문법은 통과, **실행은 거부** — 쓰지 마라 |
| FK 인덱스 | FK 컬럼에 인덱스가 없으면 **자동 생성**된다 |
| FK CASCADE | `1 row affected` 뒤에 **수만 행 삭제 + 락**이 숨어 있을 수 있다 |
| `FOREIGN_KEY_CHECKS=0` | 다시 켜도 **기존 고아 행은 검증하지 않는다** |
| AUTO_INCREMENT 구멍 | 롤백 / 실패한 INSERT / `ON DUPLICATE KEY UPDATE` 모두 카운터를 소모 |
| AUTO_INCREMENT 8.0 | 카운터가 **영속**이 되어 재시작 후 id 재사용이 사라짐 |
| `LAST_INSERT_ID()` | 세션 단위, 다중행 INSERT 는 **첫 행**의 id |
| `TRUNCATE` vs `DELETE` | TRUNCATE 만 AUTO_INCREMENT 를 **리셋** |
| 정규화 | 키에, 오직 키에만, 키의 전부에 의존하라 |
| 반정규화 | 측정된 증거가 있을 때만. **동기화 수단을 반드시 함께** |

---

## 연습문제

`exercise.sql` 에 8문제가 있습니다. 정답은 `solution.sql`.

1. 쿠폰 테이블 설계 (NOT NULL / DEFAULT / UNIQUE / CHECK 전부 사용)
2. UNIQUE 인데 중복이 들어가는 이유 진단하고 고치기
3. CHECK 로 "할인가는 정가보다 클 수 없다" 강제하기
4. FK 참조 동작 선택 — 시나리오별로 CASCADE / SET NULL / RESTRICT 중 고르기
5. CASCADE 지뢰 찾기 — 주어진 스키마에서 위험한 CASCADE 를 찾아 근거와 함께 지적
6. AUTO_INCREMENT 구멍 예측 — 주어진 SQL 실행 후 id 값 맞히기
7. 3NF 위반 찾아서 테이블 분해하기
8. 반정규화 컬럼의 동기화 누락 탐지 쿼리 작성

---

## 다음 단계

제약이 **데이터의 규칙**을 지키는 도구였다면, 다음은 **데이터를 보는 방식**을 다룹니다.
뷰(VIEW)로 복잡한 쿼리를 감추고, 생성 컬럼(Generated Column)으로 계산값을 자동화하며,
생성 컬럼에 인덱스를 걸어 "함수 인덱스"를 만드는 법까지 갑니다.

→ [Step 14 — 뷰와 생성 컬럼](../step-14-views-generated/index.md)

---

## 실습 파일

이 스텝의 실습 파일은 3개이며, `practice.sql` → `exercise.sql` → `solution.sql` 순서로 씁니다.
먼저 `practice.sql` 로 본문 13-0 ~ 13-8 의 예제를 그대로 재현해 보고(일부러 에러를 내는 문장이 많으므로 `--force` 옵션이 필수입니다),
그다음 `exercise.sql` 의 8문제를 스스로 푼 뒤, `solution.sql` 로 정답과 해설을 대조합니다.
세 파일 모두 공용 테이블(`customers` / `orders` / `products` …)은 읽기만 하고, 새로 만드는 테이블에는 `s13_` 또는 `s13_ex_` 접두사를 붙여 다른 학습자의 실습과 충돌하지 않게 설계되어 있습니다.

### practice.sql

본문의 예제를 위에서부터 순서대로 담은 **재현용 스크립트**입니다. 파일 상단 주석대로
`mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 --force shop < practice.sql` 로 실행합니다.

- **`--force` 가 반드시 필요합니다.** 이 스크립트에는 `INSERT INTO s13_members (email, nickname) VALUES ('b@ex.com', NULL);`(ERROR 1048), `INSERT INTO s13_uk (code) VALUES ('A');`(ERROR 1062), `INSERT INTO s13_products ... ('역마진상품', 5000, 9000)`(ERROR 3819), `CREATE TABLE s13_fk_bad ... ON DELETE SET NULL`(ERROR 1830) 처럼 **일부러 실패시키는 문장**이 여럿 들어 있습니다. `--force` 없이 실행하면 첫 에러에서 스크립트가 멈춰 버립니다.
- `[13-0]` 구간은 `CREATE TABLE s13_orders LIKE orders` 와 `CREATE TABLE s13_orders_full AS SELECT * FROM orders` 를 각각 만든 뒤 `SHOW INDEX` 로 비교합니다. `LIKE` 사본에는 PK 와 `idx_orders_customer` 가 그대로 복사되지만, `AS SELECT` 사본은 **인덱스가 하나도 없습니다** — 13-0 의 "왜 사본은 느리지?" 함정을 눈으로 확인하는 부분입니다.
- `[13-6]` 실측 구간은 `tally` 테이블을 자기 조인(`tally a JOIN tally b ON b.n <= 20 WHERE a.n <= 10000`)해서 **20만 행**을 생성합니다. `s13_fk_on` / `s13_fk_off` 에 각각 적재해 FK 유무의 INSERT 비용 차이(약 5%)를 재고, `s13_cas_parent`(10행) / `s13_cas_child`(20만 행)로 `DELETE FROM s13_cas_parent WHERE id = 1` 한 줄이 자식 2만 행을 날려버리는 것을 `SELECT COUNT(*)` 로 증명합니다. 20만 행 INSERT 가 두 번 돌아가므로 실행에 수 초가 걸립니다.
- 마지막 `[13-8]` 구간에는 본문에 없는 보너스가 있습니다. `s13_bad_tags`(`tags VARCHAR(255)` 에 콤마 구분 문자열)와 `s13_good_tags`(`PRIMARY KEY (product_id, tag)`)를 만들어 `EXPLAIN` 을 비교합니다. `FIND_IN_SET('세일', tags)` 는 인덱스가 있어도 `type: index`(= 인덱스 풀스캔) 에 `possible_keys: NULL` 이 나오는 반면, `WHERE tag = '세일'` 은 `type: ref` 로 인덱스 탐색을 탑니다.
- 스크립트 끝의 `DROP TABLE IF EXISTS s13_post, s13_cat, ...` 구문이 이 스텝에서 만든 `s13_*` 테이블을 전부 정리합니다. 중간에 끊고 다시 돌려도 각 블록이 `DROP TABLE IF EXISTS` 로 시작하므로 **몇 번이든 반복 실행할 수 있습니다.**

```sql file="./practice.sql"
```

### exercise.sql

8문제가 담긴 **연습문제 파일**입니다. 본문 "연습문제" 절에서 언급한 그 파일이며, 정답을 보기 전에 먼저 스스로 풀어야 합니다.

- 문제 1(쿠폰 테이블)은 `NOT NULL` / `DEFAULT` / `UNIQUE` / `CHECK` 를 한 테이블에 모두 적용하는 종합 설계 문제입니다. `max_uses` 는 "NULL 이면 무제한"이라는 **의미 있는 NULL** 이라 NULL 을 허용하되 값이 있으면 1 이상이어야 한다는, 13-4 의 "CHECK 와 NULL" 규칙을 정확히 이해해야 풀 수 있습니다.
- 문제 2는 `s13_ex_sellers` 를 미리 만들어 두고 `biz_no` 에 NULL 3개를 넣습니다. `UNIQUE KEY uk_biz_no (biz_no)` 가 걸려 있는데도 이 INSERT 가 전부 성공한다는 것이 출발점이며, 13-3 의 "UNIQUE 는 NULL 을 몇 개든 허용한다"가 답입니다.
- 문제 3의 `s13_ex_items` 는 `qty INT NULL` 로 정의되어 있습니다. `CHECK (qty BETWEEN 1 AND 999)` 만으로 NULL 을 막을 수 있는지 **직접 INSERT 해서 확인**하라는 것이 핵심 — `NULL BETWEEN 1 AND 999` 는 UNKNOWN 이므로 통과합니다.
- 문제 6은 실행하기 전에 **예측을 주석으로 먼저 적으라**고 요구합니다. `START TRANSACTION` → `ROLLBACK`, UNIQUE 위반 INSERT, `DELETE` 가 섞여 있어 세 종류의 구멍이 한꺼번에 나옵니다.
- 문제 5는 "**절대 진짜로 DELETE 하지 마세요! COUNT 만 하세요**"라고 명시합니다. `shop` 스키마의 실제 FK 를 대상으로 하므로 여기서 DELETE 를 실행하면 공용 데이터가 CASCADE 로 날아갑니다. 반드시 `SELECT COUNT(*)` 로만 파급 규모를 확인하세요.
- 파일 안의 `-- 여기에 작성:` 자리에 답을 채워 넣으면 됩니다. 만들어야 할 테이블에는 `s13_ex_` 접두사를 쓰라는 것이 파일 상단의 규칙입니다.
- **이 파일도 통째로 실행하려면 `--force` 가 필요합니다.** 문제 6의 셋업에 `INSERT INTO s13_ex_ai (val) VALUES ('x');`(중복 → ERROR 1062)가 **일부러** 들어 있어서, `--force` 없이 리다이렉트로 실행하면 거기서 스크립트가 멈추고 문제 7·8 의 셋업 테이블이 만들어지지 않습니다. 한 문제씩 클라이언트에 붙여넣어 푸는 방식이라면 신경 쓰지 않아도 됩니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 8문제의 **정답과 해설**입니다. 스스로 풀어본 뒤에 열어보세요. 문제 파일과 달리 이 파일은 **단독으로 실행해도 동작하도록** 문제 2·3의 셋업 테이블(`s13_ex_sellers`, `s13_ex_items`)을 스스로 다시 만듭니다.

- 정답 1의 `CONSTRAINT chk_cp_uses CHECK (max_uses IS NULL OR max_uses >= 1)` 에는 중요한 해설이 붙어 있습니다. 사실 `CHECK (max_uses >= 1)` 만 써도 NULL 은 UNKNOWN 이라 통과하므로 동작은 같지만, **"NULL 을 의도적으로 허용한다"는 의도를 코드로 남기는 편**이 낫다는 것입니다. 또 `discount_pct` 에 `UNSIGNED` 를 붙였다고 음수가 막히는 게 아니며(STRICT 모드가 꺼져 있으면 0 으로 조용히 변환), **CHECK 가 정답**이라는 점도 짚습니다.
- 정답 2-3 은 NULL 이 남아 있는 컬럼을 `NOT NULL` 로 바꾸는 실무 절차를 3단계로 보여줍니다: NULL 행 개수 확인 → `UPDATE ... SET biz_no = CONCAT('TEMP-', seller_id)` 로 값 채우기 → `ALTER TABLE ... MODIFY COLUMN biz_no VARCHAR(20) NOT NULL`. 임시값을 채우는 2단계에는 "**절대 운영에서 이러면 안 됩니다!**"라는 경고가 붙어 있습니다.
- 정답 4는 참조 동작 선택의 판단 규칙을 정리합니다 — 자식이 부모 없이 무의미하고 수가 적으면 `CASCADE`(orders→order_items), 자식이 독립적 의미를 가지면 `SET NULL`(employees.manager_id), 자식이 회계/법적 기록이거나 수가 많으면 `RESTRICT`(customers→orders, categories→products).
- 정답 5는 `information_schema.REFERENTIAL_CONSTRAINTS` 에서 `DELETE_RULE = 'CASCADE'` 인 FK 를 뽑고, 그중 `fk_reviews_customer` 를 **가장 위험한 것**으로 지목합니다. 리뷰는 고객만의 것이 아니라 "상품 페이지의 자산"이라 고객 1명 삭제가 상품 평점을 바꿔버린다는 논리입니다. 파급 규모는 `SELECT COUNT(*)` 상관 서브쿼리로만 확인합니다.
- 정답 8이 이 파일의 백미입니다. `INNER JOIN` 기반 정합성 검증 쿼리는 "`order_items` 가 한 건도 없는 주문"을 **아예 결과에서 놓칩니다.** `COALESCE(..., 0)` 을 쓴 상관 서브쿼리 버전(A)이나 `LEFT JOIN` 버전(B)으로 고쳐야 "결제 실패로 상세만 롤백되고 헤더가 남은 주문" 같은 진짜 사고를 잡을 수 있습니다.
- 정답 8의 별칭에 `stored_amount` 를 쓴 이유도 주석에 있습니다 — `STORED` 는 MySQL 8 **예약어**라 별칭으로 쓸 수 없습니다.
- 정답 3은 "CHECK 만으로 NULL 을 막을 수 있는가?"에 **막지 못한다**고 답한 뒤, 고치는 방법 두 가지를 제시합니다 — (방법 A) 컬럼에 `NOT NULL` 을 거는 것(권장), (방법 B) `CHECK (qty IS NOT NULL AND qty BETWEEN 1 AND 999)`. 이어서 방법 A 를 실제로 적용하는데, 기존 NULL 행을 `DELETE` 로 먼저 치운 뒤에야 `ALTER TABLE ... MODIFY COLUMN qty INT NOT NULL` 이 통과한다는 순서까지 보여줍니다.
- **이 파일 역시 `--force` 로 실행해야 끝까지 돕니다.** 정답 안에 "실패해야 정상"인 문장이 여럿 있습니다 — `('BAD', 95, ...)`(ERROR 3819), `code='WELCOME10'` 재삽입(ERROR 1062), `('회사A-복제', '111-11-11111')`(ERROR 1062), `sale_price > list_price`·`qty=1500`(ERROR 3819), `NOT NULL` 로 바꾼 뒤의 `qty = NULL` 삽입(ERROR 1048). 즉 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 --force shop < solution.sql` 로 실행합니다.
- 파일 끝의 `DROP TABLE IF EXISTS s13_ex_enroll, s13_ex_course, ...` 가 정답 실행 중 만든 `s13_ex_*` 테이블을 모두 정리합니다.

```sql file="./solution.sql"
```
