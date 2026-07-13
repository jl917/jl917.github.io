-- =====================================================================
-- Step 13 — 제약 조건과 정규화 : practice.sql
-- ---------------------------------------------------------------------
-- README 의 예제를 순서대로 담았습니다. 위에서부터 실행하세요.
--
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 --force shop < practice.sql
--        ( --force : 일부러 에러를 내는 예제가 있으므로 계속 진행시킵니다 )
--
-- ⚠️ 공용 테이블(customers/products/orders/...)은 절대 수정하지 않습니다.
--    모든 실습은 s13_ 접두사 사본 테이블에서 합니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [13-0] 실습 준비 — 사본 테이블 만들기
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_orders;
CREATE TABLE s13_orders LIKE orders;              -- 구조 + 인덱스 + 제약 복사
SHOW INDEX FROM s13_orders;                       -- PK / idx_orders_customer 가 그대로 복사됨

DROP TABLE IF EXISTS s13_orders_full;
CREATE TABLE s13_orders_full AS SELECT * FROM orders;   -- 데이터는 오지만 PK/인덱스는 안 옴!
SHOW INDEX FROM s13_orders_full;                  -- 인덱스가 하나도 없음을 확인

DROP TABLE IF EXISTS s13_orders, s13_orders_full;


-- ---------------------------------------------------------------------
-- [13-2] NOT NULL 과 DEFAULT
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_members;
CREATE TABLE s13_members (
  member_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email      VARCHAR(120) NOT NULL,
  nickname   VARCHAR(30)  NOT NULL DEFAULT '익명',
  grade      ENUM('BRONZE','SILVER','GOLD') NOT NULL DEFAULT 'BRONZE',
  points     INT          NOT NULL DEFAULT 0,
  memo       VARCHAR(100) NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id)
) ENGINE=InnoDB;

-- email 만 주고 나머지는 DEFAULT 에 맡긴다
INSERT INTO s13_members (email) VALUES ('a@ex.com');
SELECT member_id, email, nickname, grade, points, memo FROM s13_members;

-- NOT NULL 위반 : DEFAULT 가 있어도 "명시적 NULL" 은 거부된다
--   → ERROR 1048: Column 'nickname' cannot be null
INSERT INTO s13_members (email, nickname) VALUES ('b@ex.com', NULL);

-- DEFAULT 키워드로 기본값 요청
INSERT INTO s13_members (email, nickname, points) VALUES ('c@ex.com', DEFAULT, DEFAULT);
SELECT member_id, email, nickname, points FROM s13_members;


-- ---------------------------------------------------------------------
-- [13-3] PRIMARY KEY 와 UNIQUE — 결정적 차이는 NULL
-- ---------------------------------------------------------------------
-- PK 중복 → ERROR 1062
INSERT INTO s13_members (member_id, email) VALUES (1, 'dup@ex.com');

-- UNIQUE 는 NULL 을 몇 개든 허용한다
DROP TABLE IF EXISTS s13_uk;
CREATE TABLE s13_uk (
  id   INT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(20) NULL,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB;

INSERT INTO s13_uk (code) VALUES ('A'), (NULL), (NULL), (NULL);   -- NULL 3개 모두 성공
SELECT id, code FROM s13_uk;

-- NULL 이 아닌 값의 중복은 막힌다 → ERROR 1062
INSERT INTO s13_uk (code) VALUES ('A');

-- 복합 UNIQUE : "한 장바구니에 같은 상품은 한 줄만"
DROP TABLE IF EXISTS s13_uk2;
CREATE TABLE s13_uk2 (
  cart_id    INT UNSIGNED NOT NULL,
  product_id INT UNSIGNED NOT NULL,
  qty        INT NOT NULL,
  UNIQUE KEY uk_cart_product (cart_id, product_id)
) ENGINE=InnoDB;

INSERT INTO s13_uk2 VALUES (1, 10, 2), (1, 11, 1), (2, 10, 5);   -- OK
INSERT INTO s13_uk2 VALUES (1, 10, 9);   -- (1,10) 중복 → ERROR 1062


-- ---------------------------------------------------------------------
-- [13-4] CHECK 제약 — MySQL 8.0.16+ 부터 실제로 강제된다
-- ---------------------------------------------------------------------
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
  CONSTRAINT chk_s13_margin    CHECK (price >= cost),            -- 컬럼끼리 비교 가능
  CONSTRAINT chk_s13_discount  CHECK (discount_rate BETWEEN 0 AND 90)
) ENGINE=InnoDB;

INSERT INTO s13_products (name, price, cost, stock, discount_rate)
VALUES ('정상상품', 10000, 6000, 10, 20);                        -- OK

-- price >= cost 위반 → ERROR 3819
INSERT INTO s13_products (name, price, cost) VALUES ('역마진상품', 5000, 9000);

-- discount 범위 위반 → ERROR 3819
INSERT INTO s13_products (name, price, cost, discount_rate) VALUES ('과할인', 10000, 1000, 95);

-- CHECK 는 UPDATE 에서도 검사된다 → ERROR 3819
UPDATE s13_products SET stock = stock - 100 WHERE product_id = 1;

-- CHECK 와 NULL : 결과가 UNKNOWN 이면 "통과"한다 (함정!)
DROP TABLE IF EXISTS s13_chk_null;
CREATE TABLE s13_chk_null (
  id  INT AUTO_INCREMENT PRIMARY KEY,
  age INT NULL,
  CONSTRAINT chk_age CHECK (age >= 18)
) ENGINE=InnoDB;

INSERT INTO s13_chk_null (age) VALUES (20), (NULL);   -- 둘 다 성공! NULL 이 통과한다
SELECT id, age FROM s13_chk_null;
INSERT INTO s13_chk_null (age) VALUES (10);           -- 이건 거부 → ERROR 3819

-- CHECK 제약 조회
SELECT cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE, tc.ENFORCED
FROM information_schema.CHECK_CONSTRAINTS cc
JOIN information_schema.TABLE_CONSTRAINTS tc
  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
 AND tc.CONSTRAINT_NAME   = cc.CONSTRAINT_NAME
WHERE cc.CONSTRAINT_SCHEMA = 'shop' AND tc.TABLE_NAME = 's13_products';

-- 일시 해제 (NOT ENFORCED)
ALTER TABLE s13_products ALTER CHECK chk_s13_margin NOT ENFORCED;
INSERT INTO s13_products (name, price, cost) VALUES ('역마진 허용됨', 5000, 9000);  -- 이제 통과
SELECT product_id, name, price, cost FROM s13_products;

-- 함정: 다시 켜려면 기존 데이터를 재검사한다 → ERROR 3819
ALTER TABLE s13_products ALTER CHECK chk_s13_margin ENFORCED;

-- 위반 행을 치우고 나서야 다시 켤 수 있다
DELETE FROM s13_products WHERE name = '역마진 허용됨';
ALTER TABLE s13_products ALTER CHECK chk_s13_margin ENFORCED;   -- 이제 성공

-- CHECK 추가 / 삭제
ALTER TABLE s13_products ADD CONSTRAINT chk_s13_name_len CHECK (CHAR_LENGTH(name) >= 2);
ALTER TABLE s13_products DROP CHECK chk_s13_name_len;


-- ---------------------------------------------------------------------
-- [13-5] FOREIGN KEY 기본
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_child;
DROP TABLE IF EXISTS s13_parent;

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
    FOREIGN KEY (parent_id) REFERENCES s13_parent(parent_id)   -- 참조 동작 생략 = RESTRICT
) ENGINE=InnoDB;

INSERT INTO s13_parent (parent_id, name) VALUES (1,'P1'), (2,'P2');
INSERT INTO s13_child (parent_id, name) VALUES (1,'C1'), (1,'C2'), (2,'C3');

-- 부모에 없는 값 INSERT → ERROR 1452
INSERT INTO s13_child (parent_id, name) VALUES (99, 'C99');

-- 자식이 있는 부모 DELETE → ERROR 1451 (기본 = RESTRICT)
DELETE FROM s13_parent WHERE parent_id = 1;

-- FK 컬럼에 인덱스가 자동으로 생성된다 (fk_s13_child_parent)
SHOW INDEX FROM s13_child;


-- ---------------------------------------------------------------------
-- [13-5b] ON DELETE / ON UPDATE 5종
-- ---------------------------------------------------------------------

-- (1) CASCADE : 부모를 따라간다
DROP TABLE IF EXISTS s13_post;
DROP TABLE IF EXISTS s13_cat;
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

-- ON UPDATE CASCADE : 부모 PK 를 바꾸면 자식 FK 도 따라 바뀐다
UPDATE s13_cat SET cat_id = 10 WHERE cat_id = 1;
SELECT post_id, cat_id, title FROM s13_post ORDER BY post_id;

-- ON DELETE CASCADE : 부모를 지우면 자식도 사라진다
DELETE FROM s13_cat WHERE cat_id = 10;
SELECT post_id, cat_id, title FROM s13_post ORDER BY post_id;   -- 공지1, 공지2 소멸


-- (2) SET NULL : 부모가 없어져도 자식은 남고 FK 만 NULL 이 된다
DROP TABLE IF EXISTS s13_emp;
DROP TABLE IF EXISTS s13_dept;
CREATE TABLE s13_dept (
  dept_id INT UNSIGNED NOT NULL PRIMARY KEY,
  name    VARCHAR(20) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE s13_emp (
  emp_id  INT AUTO_INCREMENT PRIMARY KEY,
  dept_id INT UNSIGNED NULL,                    -- NULL 허용이어야 한다!
  name    VARCHAR(20) NOT NULL,
  CONSTRAINT fk_emp_dept FOREIGN KEY (dept_id) REFERENCES s13_dept(dept_id)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB;

INSERT INTO s13_dept VALUES (1,'개발'),(2,'영업');
INSERT INTO s13_emp (dept_id,name) VALUES (1,'김코드'),(1,'박서버'),(2,'이세일');

DELETE FROM s13_dept WHERE dept_id = 1;
SELECT emp_id, dept_id, name FROM s13_emp ORDER BY emp_id;      -- 김코드/박서버의 dept_id 가 NULL

-- (2b) SET NULL 인데 컬럼이 NOT NULL → 테이블 생성 자체가 실패 (ERROR 1830)
CREATE TABLE s13_fk_bad (
  id      INT AUTO_INCREMENT PRIMARY KEY,
  dept_id INT UNSIGNED NOT NULL,
  CONSTRAINT fk_bad FOREIGN KEY (dept_id) REFERENCES s13_dept(dept_id) ON DELETE SET NULL
) ENGINE=InnoDB;


-- (3)(4) RESTRICT / NO ACTION : InnoDB 에서는 완전히 동일하다
DROP TABLE IF EXISTS s13_fk_setdefault;   -- 자식부터 지워야 부모를 지울 수 있다
DROP TABLE IF EXISTS s13_order_line;
DROP TABLE IF EXISTS s13_prod;
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

DELETE FROM s13_prod WHERE prod_id = 1;              -- RESTRICT  → ERROR 1451
UPDATE s13_prod SET prod_id = 9 WHERE prod_id = 1;   -- NO ACTION → ERROR 1451 (동일!)


-- (5) SET DEFAULT : 문법은 통과하지만 InnoDB 는 실행을 거부한다 (조용한 거짓말)
DROP TABLE IF EXISTS s13_fk_setdefault;
CREATE TABLE s13_fk_setdefault (
  id      INT AUTO_INCREMENT PRIMARY KEY,
  prod_id INT UNSIGNED NOT NULL DEFAULT 1,
  CONSTRAINT fk_sd FOREIGN KEY (prod_id) REFERENCES s13_prod(prod_id)
    ON DELETE SET DEFAULT
) ENGINE=InnoDB;                                     -- 테이블 생성은 성공!

INSERT INTO s13_prod VALUES (2,'상품B');
INSERT INTO s13_fk_setdefault (prod_id) VALUES (2);

DELETE FROM s13_prod WHERE prod_id = 2;              -- 기본값으로 안 바뀌고 그냥 거부 → ERROR 1451

-- 참조 동작을 한눈에 확인
SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'shop' AND TABLE_NAME LIKE 's13%'
ORDER BY TABLE_NAME;


-- ---------------------------------------------------------------------
-- [13-6] FK 가 성능과 락에 미치는 영향 — 실측
-- ---------------------------------------------------------------------

-- 실측 1 : FK 있음 vs 없음, 20만 행 INSERT
DROP TABLE IF EXISTS s13_fk_on;
DROP TABLE IF EXISTS s13_fk_off;

CREATE TABLE s13_fk_on (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT UNSIGNED NOT NULL,
  amt         INT NOT NULL,
  KEY idx_cust (customer_id),
  CONSTRAINT fk_s13_on FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB;

CREATE TABLE s13_fk_off (          -- 구조 동일, FK 만 없음
  id          INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT UNSIGNED NOT NULL,
  amt         INT NOT NULL,
  KEY idx_cust (customer_id)
) ENGINE=InnoDB;

-- FK 있음  → 0.662 sec
INSERT INTO s13_fk_on (customer_id, amt)
SELECT 1 + (a.n * 7 + b.n) % 30, (a.n * b.n) % 1000
FROM tally a JOIN tally b ON b.n <= 20 WHERE a.n <= 10000;

-- FK 없음  → 0.627 sec  (약 5% 차이. 부모가 작고 캐시되어 있으면 FK 는 거의 공짜)
INSERT INTO s13_fk_off (customer_id, amt)
SELECT 1 + (a.n * 7 + b.n) % 30, (a.n * b.n) % 1000
FROM tally a JOIN tally b ON b.n <= 20 WHERE a.n <= 10000;


-- 실측 2 : CASCADE 는 "1행 삭제"를 "2만행 삭제"로 바꾼다 (진짜 위험한 곳)
DROP TABLE IF EXISTS s13_cas_child;
DROP TABLE IF EXISTS s13_cas_parent;
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
SELECT COUNT(*) AS child_rows FROM s13_cas_child;      -- 200,000 (부모 1행당 2만행)

-- 부모 1행 삭제 → "Query OK, 1 row affected (0.057 sec)"  ...실제로는 2만행이 지워졌다!
DELETE FROM s13_cas_parent WHERE id = 1;
SELECT COUNT(*) AS remaining FROM s13_cas_child;        -- 180,000

-- 비교: 자식 2만행을 직접 삭제 → (0.055 sec)  ... 위와 거의 같은 시간! 이게 증거다
DELETE FROM s13_cas_child WHERE pid = 2;
SELECT COUNT(*) AS remaining FROM s13_cas_child;        -- 160,000


-- 실측 3 : FOREIGN_KEY_CHECKS = 0 은 "고아 행"을 남길 수 있다
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO s13_child (parent_id, name) VALUES (777, '유령자식');   -- 부모 777 은 없다
SET FOREIGN_KEY_CHECKS = 1;                                          -- 다시 켜도...

-- ...기존 고아 행은 재검증되지 않는다! 직접 찾아야 한다
SELECT COUNT(*) AS orphan_rows
FROM s13_child c
LEFT JOIN s13_parent p ON p.parent_id = c.parent_id
WHERE c.parent_id IS NOT NULL AND p.parent_id IS NULL;               -- 1

DELETE FROM s13_child WHERE name = '유령자식';


-- ---------------------------------------------------------------------
-- [13-7] AUTO_INCREMENT 와 구멍(gap)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_ai;
CREATE TABLE s13_ai (
  id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB;

INSERT INTO s13_ai (code) VALUES ('a'),('b'),('c');       -- id 1, 2, 3

-- 구멍 1 : 롤백해도 카운터는 되돌아가지 않는다
START TRANSACTION;
INSERT INTO s13_ai (code) VALUES ('rollback-me');          -- id 4 를 가져감
ROLLBACK;                                                  -- 행은 사라지지만 4 는 안 돌아옴
INSERT INTO s13_ai (code) VALUES ('d');
SELECT id, code FROM s13_ai ORDER BY id;                   -- 1,2,3,5  ← 4 없음

-- 구멍 2 : 실패한 INSERT 도 카운터를 소모한다
INSERT INTO s13_ai (code) VALUES ('a');                    -- UNIQUE 위반 → ERROR 1062 (6 소모)
INSERT INTO s13_ai (code) VALUES ('d2');
SELECT id, code FROM s13_ai ORDER BY id;                   -- 1,2,3,5,7  ← 6 없음

SELECT AUTO_INCREMENT AS next_counter FROM information_schema.TABLES
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s13_ai';         -- 8

-- ON DUPLICATE KEY UPDATE 도 카운터를 태운다 (upsert 배치가 id 를 폭주시키는 원인)
INSERT INTO s13_ai (code) VALUES ('a') ON DUPLICATE KEY UPDATE code = VALUES(code);   -- 8 소모
INSERT INTO s13_ai (code) VALUES ('e');
SELECT id, code FROM s13_ai ORDER BY id;                   -- 1,2,3,5,7,9  ← 8 없음

-- LAST_INSERT_ID() : 세션 단위 + 다중행 INSERT 는 "첫" 행의 id 를 준다
INSERT INTO s13_ai (code) VALUES ('g'),('h'),('i');        -- 10, 11, 12 가 들어감
SELECT LAST_INSERT_ID() AS last_id, ROW_COUNT() AS rows_inserted;   -- 10, 3  ← 12 가 아니다!

-- 구멍 3 : DELETE 는 카운터를 리셋하지 않는다
DELETE FROM s13_ai;
INSERT INTO s13_ai (code) VALUES ('after-delete');
SELECT id, code FROM s13_ai;                               -- id = 13 (비었는데도 이어감)

-- 하지만 TRUNCATE 는 리셋한다 (DELETE 와의 결정적 차이)
TRUNCATE TABLE s13_ai;
INSERT INTO s13_ai (code) VALUES ('after-truncate');
SELECT id, code FROM s13_ai;                               -- id = 1

-- 카운터 수동 조정
ALTER TABLE s13_ai AUTO_INCREMENT = 1000;
INSERT INTO s13_ai (code) VALUES ('from-1000');
SELECT id, code FROM s13_ai;                               -- 1, 1000


-- ---------------------------------------------------------------------
-- [13-8] 정규화 / 반정규화 — shop 스키마로 확인
-- ---------------------------------------------------------------------

-- 반정규화 컬럼 orders.total_amount 는 order_items 의 합계를 "복사"해 둔 것이다.
-- 정규화 원칙대로라면 매번 계산해야 한다:
SELECT o.order_id, SUM(oi.quantity * oi.unit_price) AS calculated
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY o.order_id
LIMIT 5;

-- 하지만 실제로는 컬럼에 저장되어 있다 (읽기 성능을 위해):
SELECT order_id, total_amount FROM orders LIMIT 5;

-- 반정규화의 대가 : 두 값이 어긋나지 않았는지 "직접" 검증해야 한다
-- (실무에서는 이런 정합성 검증 배치를 반드시 만들어 둡니다)
-- (주의: STORED 는 MySQL 8 예약어라 별칭으로 쓸 수 없습니다 → stored_amount 로)
SELECT o.order_id,
       o.total_amount                        AS stored_amount,
       SUM(oi.quantity * oi.unit_price)      AS calculated_amount
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY o.order_id, o.total_amount
HAVING stored_amount <> calculated_amount;   -- 0건이면 정합성 OK

-- 1NF 위반의 현대적 변종 : 콤마 구분 문자열은 인덱스를 못 탄다
DROP TABLE IF EXISTS s13_bad_tags;
CREATE TABLE s13_bad_tags (
  product_id INT UNSIGNED NOT NULL PRIMARY KEY,
  tags       VARCHAR(255) NOT NULL,          -- '세일,신상,베스트'  ← 1NF 위반
  KEY idx_tags (tags)
) ENGINE=InnoDB;
INSERT INTO s13_bad_tags VALUES (1,'세일,신상'), (2,'베스트,세일'), (3,'신상');

-- 인덱스가 있어도 FIND_IN_SET 은 인덱스 "탐색"을 못 한다.
-- type: index = 인덱스를 처음부터 끝까지 훑는 풀스캔이다 (검색이 아니다!). Step 15/16 참고
EXPLAIN SELECT * FROM s13_bad_tags WHERE FIND_IN_SET('세일', tags);
-- +-------+---------------+----------+------+--------------------------+
-- | type  | possible_keys | key      | rows | Extra                    |
-- +-------+---------------+----------+------+--------------------------+
-- | index | NULL          | idx_tags |    3 | Using where; Using index |
-- +-------+---------------+----------+------+--------------------------+
-- possible_keys 가 NULL 인 것에 주목: 옵티마이저가 쓸 수 있는 "조건"이 하나도 없다.

-- 올바른 설계 : 행으로 쪼갠다
DROP TABLE IF EXISTS s13_good_tags;
CREATE TABLE s13_good_tags (
  product_id INT UNSIGNED NOT NULL,
  tag        VARCHAR(30) NOT NULL,
  PRIMARY KEY (product_id, tag),
  KEY idx_tag (tag)                          -- 이제 태그로 검색이 인덱스를 탄다
) ENGINE=InnoDB;
INSERT INTO s13_good_tags VALUES (1,'세일'),(1,'신상'),(2,'베스트'),(2,'세일'),(3,'신상');

EXPLAIN SELECT product_id FROM s13_good_tags WHERE tag = '세일';   -- type: ref, Using index


-- ---------------------------------------------------------------------
-- 실습 정리 — s13_ 사본 테이블 전부 삭제
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_post, s13_cat, s13_emp, s13_dept,
                     s13_order_line, s13_fk_setdefault, s13_prod,
                     s13_cas_child, s13_cas_parent,
                     s13_child, s13_parent,
                     s13_fk_on, s13_fk_off,
                     s13_members, s13_uk, s13_uk2,
                     s13_products, s13_chk_null, s13_ai,
                     s13_bad_tags, s13_good_tags;

SELECT 'Step 13 practice 완료' AS msg;
