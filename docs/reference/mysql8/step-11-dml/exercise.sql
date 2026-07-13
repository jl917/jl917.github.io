-- =====================================================================
-- Step 11 — DML : 연습문제
--   ★ 반드시 s11_ 사본에서만 작업하세요. 공용 테이블 변경 금지!
--   ★ 끝나면 cleanup.sql 로 사본을 지우세요.
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < exercise.sql
-- 정답은 solution.sql
-- =====================================================================
USE shop;

-- 준비: 공통으로 쓸 사본
DROP TABLE IF EXISTS s11_ex_order_items;
DROP TABLE IF EXISTS s11_ex_orders;
DROP TABLE IF EXISTS s11_ex_products;
DROP TABLE IF EXISTS s11_ex_customers;
DROP TABLE IF EXISTS s11_ex_stock;

CREATE TABLE s11_ex_customers   LIKE customers;
CREATE TABLE s11_ex_products    LIKE products;
CREATE TABLE s11_ex_orders      LIKE orders;
CREATE TABLE s11_ex_order_items LIKE order_items;
INSERT INTO s11_ex_customers   SELECT * FROM customers;
INSERT INTO s11_ex_products    SELECT * FROM products;
INSERT INTO s11_ex_orders      SELECT * FROM orders;
INSERT INTO s11_ex_order_items SELECT * FROM order_items;

-- ---------------------------------------------------------------------
-- Q1. s11_ex_products 에 신상품 2개를 "다중 행 INSERT" 로 넣으시오.
--     category_id 는 51(IT/컴퓨터 도서), status 는 ON_SALE.
--     넣은 뒤 그 2개를 SELECT 로 확인.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q2. "재고(stock)가 0인 상품"만 골라 s11_ex_stock 테이블에 복사하시오.
--     s11_ex_stock(product_id PK, name, stock) 을 만들고 INSERT ... SELECT.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q3. s11_ex_stock 에 대해 ON DUPLICATE KEY UPDATE 로 재고를 UPSERT 하시오.
--     - 이미 있는 product_id 는 stock 을 100 만큼 "누적(+=)"
--     - 없는 product_id 는 새로 삽입
--     (8.0.19+ 별칭 문법 AS new 를 사용할 것)
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q4. Q3 와 같은 값을 REPLACE 로 넣어 보고, ON DUPLICATE 와의 차이를
--     주석으로 설명하시오. (힌트: REPLACE 는 누적이 안 된다 — 왜?)
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q5. JOIN UPDATE : 'BRONZE' 등급 고객의 모든 주문(s11_ex_orders)의
--     shipping_city 를 '본사수령' 으로 바꾸고, 변경된 행 수를 출력하시오.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q6. JOIN DELETE : 상태가 'PENDING' 인 주문의 상세(order_items)만
--     삭제하고, 삭제된 행 수를 출력하시오. (주문 헤더는 남긴다)
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q7. sql_safe_updates = 1 로 켠 뒤,
--     (a) 막히는 UPDATE 한 개 (주석으로 남기고 에러 메시지 기재)
--     (b) 통과하는 UPDATE 한 개 (실제 실행)
--     를 작성하시오. 끝나면 sql_safe_updates = 0 으로 되돌릴 것.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Q8. s11_ex_ai(id AUTO_INCREMENT, v) 테이블을 만들어
--     DELETE 후 INSERT 한 id 와 TRUNCATE 후 INSERT 한 id 를 비교하시오.
-- ---------------------------------------------------------------------


-- 정리 (자기 사본 삭제)
DROP TABLE IF EXISTS s11_ex_order_items;
DROP TABLE IF EXISTS s11_ex_orders;
DROP TABLE IF EXISTS s11_ex_products;
DROP TABLE IF EXISTS s11_ex_customers;
DROP TABLE IF EXISTS s11_ex_stock;
DROP TABLE IF EXISTS s11_ex_ai;
