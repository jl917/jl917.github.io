-- =====================================================================
-- Step 03 practice.sql — 예제 데이터베이스 탐색
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
--
-- 이 파일은 전부 SELECT 입니다. 데이터를 바꾸지 않습니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [3-3] 데이터 둘러보기
-- ---------------------------------------------------------------------
SELECT * FROM orders      ORDER BY order_id      LIMIT 5;
SELECT * FROM order_items ORDER BY order_item_id LIMIT 5;   -- 주문 1건이 여러 줄로 갈라짐
SELECT * FROM products    ORDER BY product_id    LIMIT 5;
SELECT * FROM customers   ORDER BY customer_id   LIMIT 5;

-- 전체 행 수 한눈에
SELECT
  (SELECT COUNT(*) FROM categories)  AS categories,
  (SELECT COUNT(*) FROM customers)   AS customers,
  (SELECT COUNT(*) FROM products)    AS products,
  (SELECT COUNT(*) FROM orders)      AS orders,
  (SELECT COUNT(*) FROM order_items) AS order_items,
  (SELECT COUNT(*) FROM payments)    AS payments,
  (SELECT COUNT(*) FROM reviews)     AS reviews,
  (SELECT COUNT(*) FROM employees)   AS employees;


-- ---------------------------------------------------------------------
-- [3-4] 처음 보는 DB 구조 파악하기
-- ---------------------------------------------------------------------

-- ① 테이블 목록과 크기 — 데이터가 몰린 곳이 서비스의 핵심
SELECT table_name,
       table_rows AS approx_rows,
       ROUND((data_length + index_length) / 1024 / 1024, 2) AS total_mb,
       table_comment
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_type = 'BASE TABLE'
ORDER BY (data_length + index_length) DESC;

-- ② 외래키 관계 = SQL 로 뽑는 ER 다이어그램  ★ 새 DB 만나면 이거부터
SELECT
  table_name             AS child_table,
  column_name            AS child_column,
  referenced_table_name  AS parent_table,
  referenced_column_name AS parent_column
FROM information_schema.key_column_usage
WHERE table_schema = 'shop'
  AND referenced_table_name IS NOT NULL
ORDER BY table_name;

-- ③ 값의 분포 확인
SELECT status, COUNT(*) AS cnt,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct   -- 윈도우 함수 (Step 17)
FROM orders
GROUP BY status
ORDER BY cnt DESC;

SELECT grade, COUNT(*) AS cnt FROM customers GROUP BY grade;
SELECT city,  COUNT(*) AS cnt FROM customers GROUP BY city ORDER BY cnt DESC;

-- ④ 데이터의 시간 범위 ★ 리포트 만들기 전에 반드시 확인
SELECT MIN(order_date) AS first_order,
       MAX(order_date) AS last_order,
       DATEDIFF(MAX(order_date), MIN(order_date)) AS span_days
FROM orders;


-- ---------------------------------------------------------------------
-- [3-5] 데이터 정합성 검증
-- ---------------------------------------------------------------------

-- 1) orders.total_amount 가 order_items 합계와 일치하는가? → 0 이어야 정상
SELECT COUNT(*) AS mismatched_orders
FROM orders o
WHERE o.total_amount <> (
    SELECT COALESCE(SUM(oi.quantity * oi.unit_price), 0)
    FROM order_items oi
    WHERE oi.order_id = o.order_id
);

-- 2) 결제가 없는 주문은 정말 PENDING 뿐인가?  (안티 조인 패턴 → Step 07)
SELECT o.status, COUNT(*) AS orders_without_payment
FROM orders o
LEFT JOIN payments p ON p.order_id = o.order_id
WHERE p.payment_id IS NULL
GROUP BY o.status;

-- 3) 후기가 하나도 없는 상품은 몇 개인가?
SELECT COUNT(*) AS products_without_review
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.product_id = p.product_id);

-- 4) 일부러 심어둔 NULL 들
SELECT customer_id, name, phone FROM customers WHERE phone IS NULL;
SELECT product_id, name        FROM products  WHERE attrs IS NULL;
SELECT category_id, name       FROM categories WHERE parent_id IS NULL;   -- 최상위 카테고리
SELECT employee_id, name       FROM employees WHERE manager_id IS NULL;   -- CEO


-- ---------------------------------------------------------------------
-- [3-6] 실습 흔적 청소용 SQL 을 SQL 로 생성하기
-- ---------------------------------------------------------------------
SELECT CONCAT('DROP TABLE IF EXISTS ', table_name, ';') AS cleanup_sql
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_name REGEXP '^s[0-9]{2}_';

SELECT 'Step 03 practice 완료' AS msg;
