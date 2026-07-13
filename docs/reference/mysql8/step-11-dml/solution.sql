-- =====================================================================
-- Step 11 — DML : 정답 + 해설
--   ★ 전 과정 s11_ 사본에서만 실행. 공용 테이블은 절대 변경하지 않음.
--   실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < solution.sql
-- =====================================================================
USE shop;

-- 준비
DROP TABLE IF EXISTS s11_ex_order_items;
DROP TABLE IF EXISTS s11_ex_orders;
DROP TABLE IF EXISTS s11_ex_products;
DROP TABLE IF EXISTS s11_ex_customers;
DROP TABLE IF EXISTS s11_ex_stock;
DROP TABLE IF EXISTS s11_ex_ai;

CREATE TABLE s11_ex_customers   LIKE customers;
CREATE TABLE s11_ex_products    LIKE products;
CREATE TABLE s11_ex_orders      LIKE orders;
CREATE TABLE s11_ex_order_items LIKE order_items;
INSERT INTO s11_ex_customers   SELECT * FROM customers;
INSERT INTO s11_ex_products    SELECT * FROM products;
INSERT INTO s11_ex_orders      SELECT * FROM orders;
INSERT INTO s11_ex_order_items SELECT * FROM order_items;

-- ---------------------------------------------------------------------
-- A1. 다중 행 INSERT. 컬럼 목록을 명시한다.
-- ---------------------------------------------------------------------
INSERT INTO s11_ex_products (category_id, name, price, cost, stock, status) VALUES
  (51, '연습문제로 배우는 SQL', 32000, 18000, 150, 'ON_SALE'),
  (51, '한 권으로 끝내는 인덱스', 41000, 24000, 90,  'ON_SALE');
SELECT product_id, name, price, stock, status
FROM s11_ex_products
WHERE name IN ('연습문제로 배우는 SQL', '한 권으로 끝내는 인덱스');
-- → product_id 41, 42 로 삽입된다(원본 40개 다음).

-- ---------------------------------------------------------------------
-- A2. INSERT ... SELECT 로 조건에 맞는 행만 복사.
-- ---------------------------------------------------------------------
CREATE TABLE s11_ex_stock (
  product_id INT UNSIGNED NOT NULL PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  stock      INT NOT NULL
);
INSERT INTO s11_ex_stock (product_id, name, stock)
SELECT product_id, name, stock FROM s11_ex_products WHERE stock = 0;
SELECT * FROM s11_ex_stock ORDER BY product_id;
-- → 재고 0 상품: 4(울 니트 스웨터), 27(노르웨이 연어 필렛) → 2행

-- ---------------------------------------------------------------------
-- A3. ON DUPLICATE KEY UPDATE 로 UPSERT.
--     기존 product_id=4 는 stock 누적(+100), 새 product_id=999 는 삽입.
--     별칭 문법(AS new)을 쓴다.
-- ---------------------------------------------------------------------
INSERT INTO s11_ex_stock (product_id, name, stock) VALUES
  (4,   '울 니트 스웨터', 100),
  (999, '신규 입고 상품', 100) AS new
ON DUPLICATE KEY UPDATE stock = s11_ex_stock.stock + new.stock;
SELECT ROW_COUNT() AS affected;
SELECT * FROM s11_ex_stock WHERE product_id IN (4, 999) ORDER BY product_id;
-- → product_id=4 : stock 0 + 100 = 100 (누적)
--   product_id=999 : 100 (신규 삽입)
--   affected = 1(insert) + 2(update) = 3

-- ---------------------------------------------------------------------
-- A4. REPLACE 로 같은 시도.
--     REPLACE 는 기존 행을 DELETE 하고 새로 INSERT 하므로,
--     "기존 stock 에 더하기"가 불가능하다(기존 값을 참조할 방법이 없음).
--     현재 product_id=4 의 stock 은 100. REPLACE 로 50 을 넣으면?
-- ---------------------------------------------------------------------
REPLACE INTO s11_ex_stock (product_id, name, stock) VALUES (4, '울 니트 스웨터', 50);
SELECT ROW_COUNT() AS replace_affected;   -- 2 = DELETE 1 + INSERT 1 (값이 바뀌므로)
SELECT * FROM s11_ex_stock WHERE product_id = 4;
-- → stock = 50 (150 이 아니다!). REPLACE 는 이전 값을 알 수 없어 누적이 아니라 덮어쓴다.
--   따라서 "누적/증분 UPSERT"에는 반드시 ON DUPLICATE KEY UPDATE 를 써야 한다.
--   참고: REPLACE 하는 새 행이 기존 행과 완전히 동일하면 ROW_COUNT()=1 로 최적화된다.

-- ---------------------------------------------------------------------
-- A5. JOIN UPDATE. BRONZE 고객의 주문 배송지를 일괄 변경.
-- ---------------------------------------------------------------------
UPDATE s11_ex_orders o
JOIN s11_ex_customers c ON c.customer_id = o.customer_id
SET o.shipping_city = '본사수령'
WHERE c.grade = 'BRONZE';
SELECT ROW_COUNT() AS updated;
-- → BRONZE 고객 10명 × 각 20주문 = 200행

-- ---------------------------------------------------------------------
-- A6. JOIN DELETE. 삭제 대상(oi)을 DELETE 와 FROM 사이에 명시.
-- ---------------------------------------------------------------------
DELETE oi
FROM s11_ex_order_items oi
JOIN s11_ex_orders o ON o.order_id = oi.order_id
WHERE o.status = 'PENDING';
SELECT ROW_COUNT() AS deleted_items;
-- → PENDING 주문(60건)에 매달린 상세 행 수만큼 삭제
SELECT COUNT(*) AS pending_headers_left
FROM s11_ex_orders WHERE status = 'PENDING';
-- → 헤더는 그대로 60건 남아 있다

-- ---------------------------------------------------------------------
-- A7. 안전 모드.
-- ---------------------------------------------------------------------
SET SESSION sql_safe_updates = 1;

-- (a) 막히는 예 (일부러 주석) :
-- UPDATE s11_ex_products SET stock = 0 WHERE status = 'ON_SALE';
--   ERROR 1175 (HY000): You are using safe update mode ...
--   ('status' 는 인덱스가 아니라 전체 스캔 → 차단)

-- (b) 통과하는 예 : PK 를 조건에 사용
UPDATE s11_ex_products SET stock = stock + 5 WHERE product_id = 1;
SELECT ROW_COUNT() AS ok;   -- 1

SET SESSION sql_safe_updates = 0;

-- ---------------------------------------------------------------------
-- A8. TRUNCATE vs DELETE 의 AUTO_INCREMENT 차이.
-- ---------------------------------------------------------------------
CREATE TABLE s11_ex_ai (id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, v INT NOT NULL);
INSERT INTO s11_ex_ai (v) VALUES (1),(2),(3);

DELETE FROM s11_ex_ai;
INSERT INTO s11_ex_ai (v) VALUES (9);
SELECT 'after DELETE'   AS step, MAX(id) AS id FROM s11_ex_ai;   -- id = 4 (이어짐)

TRUNCATE TABLE s11_ex_ai;
INSERT INTO s11_ex_ai (v) VALUES (9);
SELECT 'after TRUNCATE' AS step, MAX(id) AS id FROM s11_ex_ai;   -- id = 1 (리셋)

-- ---------------------------------------------------------------------
-- 정리
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s11_ex_order_items;
DROP TABLE IF EXISTS s11_ex_orders;
DROP TABLE IF EXISTS s11_ex_products;
DROP TABLE IF EXISTS s11_ex_customers;
DROP TABLE IF EXISTS s11_ex_stock;
DROP TABLE IF EXISTS s11_ex_ai;

SELECT '정리 완료.' AS msg;
