-- =====================================================================
-- Step 11 — DML (INSERT / UPDATE / DELETE) : practice.sql
--
-- ★★ 매우 중요 ★★
--   이 스텝은 데이터를 "바꾸는" 실습입니다.
--   공용 테이블(orders/customers/products/...)을 직접 건드리면
--   같은 DB 를 쓰는 다른 학습자의 결과가 전부 어긋납니다.
--   따라서 모든 실습은 s11_ 접두사가 붙은 "작업 사본"에서만 합니다.
--   맨 아래 [11-29] 정리 스크립트로 사본을 모두 지우고 끝냅니다.
--
--   ⚠️ ROW_COUNT() / LAST_INSERT_ID() 는 "바로 직전 문장"의 결과입니다.
--      DML 과 이 함수 사이에 다른 SELECT 가 끼면 값이 초기화됩니다.
--      (mysql 클라이언트로 스크립트를 실행할 때는 '주석 한 줄'조차
--       빈 문장으로 서버에 전달되어 ROW_COUNT() 를 0 으로 리셋합니다.)
--      그래서 이 파일에서는 측정 SELECT 를 DML 바로 뒤에 붙여 둡니다.
--
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
--       (LOAD DATA LOCAL INFILE 예제는 --local-infile=1 옵션 필요)
-- =====================================================================
USE shop;

-- =====================================================================
-- 0. 작업 사본 만들기
-- =====================================================================

-- [11-1] 사본 생성 : LIKE 는 구조(인덱스/제약 포함)만 복사한다
DROP TABLE IF EXISTS s11_order_items;
DROP TABLE IF EXISTS s11_orders;
DROP TABLE IF EXISTS s11_products;
DROP TABLE IF EXISTS s11_customers;
DROP TABLE IF EXISTS s11_stock_feed;

CREATE TABLE s11_customers   LIKE customers;
CREATE TABLE s11_products    LIKE products;
CREATE TABLE s11_orders      LIKE orders;
CREATE TABLE s11_order_items LIKE order_items;

-- [11-2] 데이터 복사 : INSERT ... SELECT
INSERT INTO s11_customers   SELECT * FROM customers;
INSERT INTO s11_products    SELECT * FROM products;
INSERT INTO s11_orders      SELECT * FROM orders;
INSERT INTO s11_order_items SELECT * FROM order_items;

SELECT (SELECT COUNT(*) FROM s11_customers)   AS customers,
       (SELECT COUNT(*) FROM s11_products)    AS products,
       (SELECT COUNT(*) FROM s11_orders)      AS orders,
       (SELECT COUNT(*) FROM s11_order_items) AS order_items;

-- =====================================================================
-- 1. INSERT
-- =====================================================================

-- [11-3] 단일 행 INSERT — 컬럼 목록을 반드시 명시하자.
--        그리고 그 직후 LAST_INSERT_ID() 로 방금 부여된 PK 를 얻는다.
INSERT INTO s11_customers (email, name, phone, grade, birth_date, city, points)
VALUES ('new.user1@example.com', '신규일', '010-9000-0001', 'BRONZE', '1995-05-05', '서울', 0);
SELECT LAST_INSERT_ID() AS new_id;

-- [11-4] LAST_INSERT_ID() 는 다음 INSERT 전까지 값이 유지된다
SELECT customer_id, email, name, grade FROM s11_customers WHERE customer_id = LAST_INSERT_ID();

-- [11-5] 다중 행 INSERT (한 번의 왕복으로 여러 행 → 훨씬 빠르다).
--        다중 행 INSERT 후 LAST_INSERT_ID() 는 "첫 번째" 행의 ID,
--        ROW_COUNT() 는 삽입된 행 수(3)를 돌려준다.
INSERT INTO s11_customers (email, name, grade, city, points) VALUES
  ('new.user2@example.com', '신규이', 'SILVER', '부산', 100),
  ('new.user3@example.com', '신규삼', 'GOLD',   '대구', 200),
  ('new.user4@example.com', '신규사', 'VIP',    '인천', 300);
SELECT LAST_INSERT_ID() AS first_of_batch, ROW_COUNT() AS inserted_rows;

-- [11-6] INSERT ... SELECT : 다른 테이블의 결과를 그대로 적재
CREATE TABLE s11_stock_feed (
  product_id INT UNSIGNED NOT NULL PRIMARY KEY,
  stock      INT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO s11_stock_feed (product_id, stock)
SELECT product_id, stock + 10
FROM s11_products
WHERE category_id IN (21, 22);
SELECT * FROM s11_stock_feed ORDER BY product_id;

-- =====================================================================
-- 2. UPSERT : ON DUPLICATE KEY UPDATE vs REPLACE
-- =====================================================================

-- [11-7] ON DUPLICATE KEY UPDATE : 있으면 UPDATE, 없으면 INSERT.
--        product_id=12 는 이미 있고(→UPDATE), product_id=1 은 없다(→INSERT).
--        ROW_COUNT(): INSERT=1, 값이 바뀐 UPDATE=2, 변화 없으면 0 → 여기선 1+2=3.
INSERT INTO s11_stock_feed (product_id, stock) VALUES (12, 999), (1, 555)
ON DUPLICATE KEY UPDATE
    stock      = VALUES(stock),
    updated_at = NOW();
SELECT ROW_COUNT() AS affected;
SELECT product_id, stock FROM s11_stock_feed WHERE product_id IN (1, 12) ORDER BY product_id;

-- [11-8] 8.0.19+ : VALUES() 대신 별칭 문법 (VALUES() 는 deprecated)
INSERT INTO s11_stock_feed (product_id, stock) VALUES (13, 777) AS new
ON DUPLICATE KEY UPDATE
    stock      = new.stock,
    updated_at = NOW();
SELECT product_id, stock FROM s11_stock_feed WHERE product_id = 13;

-- [11-9] REPLACE 의 정체 : 이건 UPDATE 가 아니라 DELETE + INSERT 다.
--        따라서 ROW_COUNT()=2 (삭제 1 + 삽입 1),
--        그리고 VALUES 에 없는 컬럼(updated_at)은 DEFAULT 로 "리셋"된다.
SELECT product_id, stock, updated_at FROM s11_stock_feed WHERE product_id = 12;
REPLACE INTO s11_stock_feed (product_id, stock) VALUES (12, 111);
SELECT ROW_COUNT() AS replace_affected;
SELECT product_id, stock, updated_at FROM s11_stock_feed WHERE product_id = 12;

-- [11-10] REPLACE 는 AUTO_INCREMENT 도 소모한다 (id 가 튄다)
DROP TABLE IF EXISTS s11_seq_demo;
CREATE TABLE s11_seq_demo (
  id   INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(20) NOT NULL,
  memo VARCHAR(50) DEFAULT '기본메모',
  UNIQUE KEY uk_code (code)
);
INSERT INTO s11_seq_demo (code, memo) VALUES ('A', '원본메모');
SELECT id, code, memo FROM s11_seq_demo;
REPLACE INTO s11_seq_demo (code) VALUES ('A');
SELECT id, code, memo FROM s11_seq_demo;
-- → id 가 1 → 2 로 바뀌고, memo 는 '원본메모' → '기본메모' 로 날아갔다.
--   FK 가 이 id 를 참조하고 있었다면? ON DELETE CASCADE 로 자식이 삭제된다. 재앙.

-- [11-11] 같은 일을 ON DUPLICATE KEY UPDATE 로 하면 id 와 memo 가 보존된다
DELETE FROM s11_seq_demo;
ALTER TABLE s11_seq_demo AUTO_INCREMENT = 1;
INSERT INTO s11_seq_demo (code, memo) VALUES ('A', '원본메모');
INSERT INTO s11_seq_demo (code, memo) VALUES ('A', '원본메모') AS new
ON DUPLICATE KEY UPDATE code = new.code;
SELECT id, code, memo FROM s11_seq_demo;
-- → id=1, memo='원본메모' 그대로 유지

-- =====================================================================
-- 3. INSERT IGNORE 의 위험
-- =====================================================================

-- [11-12] INSERT IGNORE 는 에러를 "경고"로 낮춰 삼킨다.
--         이메일 UNIQUE 위반이지만 에러 없이 0행 처리된다.
INSERT IGNORE INTO s11_customers (email, name, grade, city)
VALUES ('new.user2@example.com', '중복이', 'BRONZE', '서울');
SELECT ROW_COUNT() AS affected;
SHOW WARNINGS;

-- [11-13] 삼켜지는 것은 중복만이 아니다 — 데이터가 조용히 변조된다
DROP TABLE IF EXISTS s11_ignore_demo;
CREATE TABLE s11_ignore_demo (
  id  INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  qty INT NOT NULL,
  nm  VARCHAR(5) NOT NULL
);
INSERT IGNORE INTO s11_ignore_demo (qty, nm) VALUES
  (100,  '정상'),
  (NULL, '널값'),               -- NOT NULL 위반 → 0 으로 들어간다
  (200,  '아주긴이름입니다');   -- VARCHAR(5) 초과 → 잘려서 들어간다
SHOW WARNINGS;
SELECT * FROM s11_ignore_demo;
-- → NULL 이 0 이 되고, 이름이 잘렸는데도 "성공"이다. 애플리케이션은 아무것도 모른다.

-- [11-14] IGNORE 없이 실행하면 정상적으로 에러가 난다 (이게 옳다)
-- INSERT INTO s11_ignore_demo (qty, nm) VALUES (NULL, '널값');
-- ERROR 1048 (23000): Column 'qty' cannot be null

-- =====================================================================
-- 4. UPDATE
-- =====================================================================

-- [11-15] 기본 UPDATE
UPDATE s11_products
SET price = price * 1.1, stock = stock - 1
WHERE product_id = 1;
SELECT product_id, name, price, stock FROM s11_products WHERE product_id = 1;

-- [11-16] JOIN UPDATE (다중 테이블 조인 후 UPDATE).
--         GOLD 고객의 모든 주문 배송도시를 '제주'로 바꾼다.
UPDATE s11_orders o
JOIN s11_customers c ON c.customer_id = o.customer_id
SET o.shipping_city = '제주'
WHERE c.grade = 'GOLD';
SELECT ROW_COUNT() AS updated;

-- [11-17] 다중 테이블 UPDATE : 두 테이블을 한 문장으로 동시 갱신
UPDATE s11_orders o
JOIN s11_customers c ON c.customer_id = o.customer_id
SET o.status  = 'CANCELLED',
    c.points  = c.points + 1000
WHERE o.order_id = 7;
SELECT o.order_id, o.status, c.customer_id, c.points
FROM s11_orders o JOIN s11_customers c ON c.customer_id = o.customer_id
WHERE o.order_id = 7;

-- [11-18] 상관 서브쿼리 UPDATE : 주문 합계를 상세 합계로 재계산
UPDATE s11_orders o
SET o.total_amount = (
    SELECT COALESCE(SUM(oi.quantity * oi.unit_price), 0)
    FROM s11_order_items oi
    WHERE oi.order_id = o.order_id
)
WHERE o.order_id <= 5;
SELECT order_id, total_amount FROM s11_orders WHERE order_id <= 5 ORDER BY order_id;

-- =====================================================================
-- 5. 안전 모드 (sql_safe_updates)
-- =====================================================================

-- [11-19] sql_safe_updates = 1 이면 WHERE 없는(또는 키를 안 쓰는) UPDATE/DELETE 를 막는다
SET SESSION sql_safe_updates = 1;

-- 아래 두 문장은 일부러 실패시키는 예제다(주석 유지):
-- UPDATE s11_products SET stock = 0;
--   ERROR 1175 (HY000): You are using safe update mode and you tried to update
--   a table without a WHERE that uses a KEY column
-- UPDATE s11_products SET stock = 0 WHERE name LIKE '%셔츠%';
--   ERROR 1175 (HY000): ...  (키가 아닌 컬럼이라 전체 스캔 → 차단)

-- 키(PK) 를 쓰면 통과한다
UPDATE s11_products SET stock = stock + 1 WHERE product_id = 1;
SELECT ROW_COUNT() AS ok_with_key;

-- 키가 아니어도 LIMIT 을 붙이면 통과한다
UPDATE s11_products SET stock = stock + 1 WHERE name LIKE '%셔츠%' LIMIT 10;
SELECT ROW_COUNT() AS ok_with_limit;

SET SESSION sql_safe_updates = 0;

-- =====================================================================
-- 6. DELETE / TRUNCATE
-- =====================================================================

-- [11-20] 기본 DELETE
SELECT COUNT(*) AS before_delete FROM s11_order_items WHERE order_id = 1;
DELETE FROM s11_order_items WHERE order_id = 1;
SELECT ROW_COUNT() AS deleted;
SELECT COUNT(*) AS after_delete FROM s11_order_items WHERE order_id = 1;

-- [11-21] JOIN DELETE : 삭제할 테이블(별칭)을 DELETE 와 FROM 사이에 명시한다.
--         CANCELLED 주문의 상세만 지운다.
DELETE oi
FROM s11_order_items oi
JOIN s11_orders o ON o.order_id = oi.order_id
WHERE o.status = 'CANCELLED';
SELECT ROW_COUNT() AS deleted_items;

-- [11-22] 다중 테이블 DELETE : 부모와 자식을 한 문장으로.
--         PENDING 주문과 그 상세를 함께 삭제.
DELETE o, oi
FROM s11_orders o
LEFT JOIN s11_order_items oi ON oi.order_id = o.order_id
WHERE o.status = 'PENDING';
SELECT ROW_COUNT() AS deleted_rows;
SELECT COUNT(*) AS pending_left FROM s11_orders WHERE status = 'PENDING';

-- [11-23] TRUNCATE vs DELETE : AUTO_INCREMENT 동작이 다르다
DROP TABLE IF EXISTS s11_trunc_demo;
CREATE TABLE s11_trunc_demo (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  v  INT NOT NULL
);
INSERT INTO s11_trunc_demo (v) VALUES (1),(2),(3);

DELETE FROM s11_trunc_demo;                 -- DML. 롤백 가능. AUTO_INCREMENT 유지
INSERT INTO s11_trunc_demo (v) VALUES (9);
SELECT 'after DELETE' AS step, id, v FROM s11_trunc_demo;   -- id = 4 (이어서 증가)

TRUNCATE TABLE s11_trunc_demo;              -- DDL. 롤백 불가. AUTO_INCREMENT 리셋
INSERT INTO s11_trunc_demo (v) VALUES (9);
SELECT 'after TRUNCATE' AS step, id, v FROM s11_trunc_demo; -- id = 1 (리셋됨)

-- [11-24] TRUNCATE 는 DDL 이라 암묵적 커밋이 일어난다 → 롤백 불가
START TRANSACTION;
INSERT INTO s11_trunc_demo (v) VALUES (100);
ROLLBACK;
SELECT COUNT(*) AS after_rollback_of_insert FROM s11_trunc_demo;   -- INSERT 는 롤백됨

START TRANSACTION;
TRUNCATE TABLE s11_trunc_demo;   -- ← 여기서 암묵적 COMMIT 발생
ROLLBACK;
SELECT COUNT(*) AS after_rollback_of_truncate FROM s11_trunc_demo; -- 0. 되돌릴 수 없다

-- =====================================================================
-- 7. LOAD DATA
-- =====================================================================
-- [11-25] LOAD DATA LOCAL INFILE : 대량 적재의 표준 (INSERT 보다 훨씬 빠르다).
--   클라이언트에 --local-infile=1, 서버에 local_infile=ON 이 필요하다.
--   먼저 CSV 를 만든다 (셸에서):
--     printf '9001,CSV상품A,10000,5000,10\n9002,CSV상품B,20000,9000,20\n' > /tmp/s11_products.csv
--
--   DROP TABLE IF EXISTS s11_load_demo;
--   CREATE TABLE s11_load_demo (
--     product_id INT UNSIGNED PRIMARY KEY,
--     name VARCHAR(100) NOT NULL,
--     price DECIMAL(10,2) NOT NULL,
--     cost  DECIMAL(10,2) NOT NULL,
--     stock INT NOT NULL
--   );
--   LOAD DATA LOCAL INFILE '/tmp/s11_products.csv'
--   INTO TABLE s11_load_demo
--   FIELDS TERMINATED BY ',' ENCLOSED BY '"'
--   LINES  TERMINATED BY '\n'
--   (product_id, name, price, cost, stock);
--   SELECT * FROM s11_load_demo;
--
--   RETURNING 이 없는 MySQL: INSERT 직후 생성된 키는 LAST_INSERT_ID() 로 얻는다
--   (PostgreSQL 의 INSERT ... RETURNING id 에 해당하는 별도 문법이 없다).

-- =====================================================================
-- 8. 정리 : 사본 전부 삭제
-- =====================================================================
-- [11-26] cleanup.sql 에 동일한 내용이 들어 있습니다.
DROP TABLE IF EXISTS s11_order_items;
DROP TABLE IF EXISTS s11_orders;
DROP TABLE IF EXISTS s11_products;
DROP TABLE IF EXISTS s11_customers;
DROP TABLE IF EXISTS s11_stock_feed;
DROP TABLE IF EXISTS s11_seq_demo;
DROP TABLE IF EXISTS s11_ignore_demo;
DROP TABLE IF EXISTS s11_trunc_demo;
DROP TABLE IF EXISTS s11_load_demo;

SELECT '정리 완료. 원본 테이블은 건드리지 않았습니다.' AS msg;
