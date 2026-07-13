-- =====================================================================
-- Step 03 solution.sql — 연습문제 정답
-- =====================================================================
USE shop;

-- ── 문제 1. 모든 테이블의 정확한 행 수
SELECT 'categories'  AS table_name, COUNT(*) AS exact_rows FROM categories
UNION ALL SELECT 'customers',   COUNT(*) FROM customers
UNION ALL SELECT 'products',    COUNT(*) FROM products
UNION ALL SELECT 'orders',      COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL SELECT 'payments',    COUNT(*) FROM payments
UNION ALL SELECT 'reviews',     COUNT(*) FROM reviews
UNION ALL SELECT 'employees',   COUNT(*) FROM employees
UNION ALL SELECT 'tally',       COUNT(*) FROM tally
ORDER BY exact_rows DESC;
-- 결과: tally 10000, order_items 1200, orders 600, payments 540,
--       reviews 80, products 40, customers 30, employees 18, categories 17
-- 해설: information_schema.table_rows 는 InnoDB 의 "통계 샘플 추정값"입니다.
--       실제로 100만 행인 access_logs 가 996,151 로 나오는 걸 Step 02 에서 봤습니다.
--       정확한 수가 필요하면 반드시 COUNT(*) 를 쓰세요.
--       (그래서 대용량 테이블의 COUNT(*) 는 비싼 연산입니다 → Step 15)


-- ── 문제 2. 보조 인덱스가 없는 테이블
SELECT t.table_name,
       COUNT(DISTINCT CASE WHEN s.index_name <> 'PRIMARY' THEN s.index_name END) AS secondary_indexes
FROM information_schema.tables t
LEFT JOIN information_schema.statistics s
       ON s.table_schema = t.table_schema AND s.table_name = t.table_name
WHERE t.table_schema = 'shop'
  AND t.table_type = 'BASE TABLE'
  AND t.table_name NOT REGEXP '^s[0-9]{2}_'      -- 다른 스텝의 실습 테이블 제외
GROUP BY t.table_name
HAVING secondary_indexes = 0
ORDER BY t.table_name;
-- 결과: access_logs, tally
-- 해설: access_logs 에 인덱스가 없는 것은 의도한 것입니다.
--       Step 15 에서 여러분이 직접 인덱스를 설계해 붙이고, 100만 행 조회가
--       몇 초 → 몇 밀리초로 줄어드는 것을 실측합니다.
--       tally 는 PK(n) 만으로 충분합니다 (조회 용도가 n 뿐이므로).


-- ── 문제 3. 카테고리 계층 (SELF JOIN)
SELECT CONCAT(p.name, ' > ', c.name) AS category_path
FROM categories c
JOIN categories p ON p.category_id = c.parent_id     -- 같은 테이블을 두 번! 별칭이 필수
ORDER BY p.sort_order, c.sort_order;
-- 결과:
--   패션 > 남성의류 / 패션 > 여성의류 / 패션 > 신발
--   디지털 > 노트북 / 디지털 > 스마트폰 / 디지털 > 주변기기
--   식품 > 신선식품 / 식품 > 가공식품
--   리빙 > 주방용품 / 리빙 > 가구
--   도서 > IT/컴퓨터 / 도서 > 소설
-- 해설: SELF JOIN 은 같은 테이블에 서로 다른 별칭(c, p)을 붙여 조인하는 것입니다.
--       "자식 c 의 parent_id 와 같은 category_id 를 가진 부모 p" 를 붙였습니다.
--       계층이 2단계라서 JOIN 한 번으로 됐지만, 깊이가 정해지지 않은 계층은
--       JOIN 을 몇 번 해야 할지 알 수 없습니다. 그때 재귀 CTE 를 씁니다 → Step 09


-- ── 문제 4. 주문이 없는 고객
SELECT c.customer_id, c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
WHERE o.order_id IS NULL;
-- 결과: (0 rows) — 빈 결과
-- 해설: ★ 이 빈 결과가 정답입니다.
--       시드에서 customer_id = 1 + (n * 17) % 30 이 30명을 고르게 순환하므로
--       모든 고객이 최소 1건 이상 주문했습니다.
--       실무에서 안티 조인이 0건을 반환하면 "쿼리 버그"와 "정말 없음"을 구분해야 합니다.
--       구분법: LEFT JOIN 을 빼고 실행해서 행이 나오는지 확인하세요.
SELECT COUNT(DISTINCT c.customer_id) AS total_customers,
       COUNT(DISTINCT o.customer_id) AS customers_with_orders
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id;
-- → 30, 30  ... 전원이 주문 이력이 있음을 교차 검증
--
-- ★ 여기서 COUNT(DISTINCT c.customer_id) 대신 COUNT(*) 를 쓰면 어떻게 될까요?
--   답: 600 이 나옵니다. 고객 수가 아니라 "조인 결과의 행 수"(= 주문 수)를 세기 때문입니다.
--   조인 뒤에 COUNT(*) 를 쓰는 것은 초보자가 가장 자주 저지르는 집계 실수입니다.
--   조인은 행을 "불립니다". 무엇을 세고 있는지 항상 의식하세요 → Step 06, Step 07


-- ── 문제 5. 후기가 없는 상품
SELECT COUNT(*) AS products_without_review
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.product_id = p.product_id);
-- 결과: 24

SELECT p.product_id, p.name, p.price
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.product_id = p.product_id)
ORDER BY p.product_id
LIMIT 5;
-- 해설: NOT EXISTS 는 "일치하는 행이 하나도 없는가"를 묻습니다.
--       같은 뜻의 LEFT JOIN 버전:
--         SELECT p.* FROM products p
--         LEFT JOIN reviews r ON r.product_id = p.product_id
--         WHERE r.review_id IS NULL;
--       NOT IN 버전은 위험합니다. reviews.product_id 에 NULL 이 하나라도 있으면
--       결과가 통째로 비어버립니다 → Step 08 의 핵심 함정


-- ── 문제 6. attrs 가 NULL 인 상품
SELECT product_id, name, category_id
FROM products
WHERE attrs IS NULL;
-- 결과: 22번 'USB-C 허브 8in1', 29번 '프리미엄 라면 5입'
-- 해설: JSON 컬럼도 결국 컬럼이라 NULL 이 될 수 있습니다.
--       주의: JSON 의 NULL(문자열 'null')과 SQL 의 NULL 은 다릅니다!
--         - attrs IS NULL          → 컬럼 자체가 비어 있음
--         - JSON_TYPE(attrs)='NULL' → JSON 문서 안에 null 이 들어 있음
--       이 둘을 헷갈리면 JSON 쿼리가 조용히 틀립니다 → Step 18


-- ── 문제 7. 각 테이블의 PK 컬럼
SELECT t.table_name,
       GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position) AS pk_columns
FROM information_schema.tables t
JOIN information_schema.key_column_usage k
  ON  k.table_schema   = t.table_schema
  AND k.table_name     = t.table_name
  AND k.constraint_name = 'PRIMARY'          -- PK 의 제약 이름은 항상 'PRIMARY'
WHERE t.table_schema = 'shop'
  AND t.table_type = 'BASE TABLE'
  AND t.table_name NOT REGEXP '^s[0-9]{2}_'
GROUP BY t.table_name
ORDER BY t.table_name;
-- 결과:
--   access_logs=log_id, categories=category_id, customers=customer_id,
--   employees=employee_id, order_items=order_item_id, orders=order_id,
--   payments=payment_id, products=product_id, reviews=review_id, tally=n
-- 해설: GROUP_CONCAT 은 여러 행을 한 문자열로 합칩니다 (복합 PK 대응).
--       ORDER BY 를 GROUP_CONCAT "안에" 쓴다는 점에 주목하세요 → Step 06


-- ── 문제 8. 최고 금액 주문의 상세 내역
SELECT
  o.order_id,
  c.name           AS customer,
  o.order_date,
  o.total_amount,
  p.name           AS product,
  oi.quantity,
  oi.unit_price,
  oi.quantity * oi.unit_price AS line_amount
FROM orders o
JOIN customers   c  ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id   = o.order_id
JOIN products    p  ON p.product_id  = oi.product_id
WHERE o.order_id = (SELECT order_id FROM orders ORDER BY total_amount DESC LIMIT 1)
ORDER BY line_amount DESC;
-- 해설: 서브쿼리로 "최고 금액 주문의 ID" 를 먼저 구한 뒤 그 주문의 상세를 조인했습니다.
--       ORDER BY total_amount DESC LIMIT 1 로 최댓값 행을 찾는 것은 흔한 패턴입니다.
--       (MAX(total_amount) 를 쓰면 "그 값이 몇인지"는 알지만 "어느 행인지"는 모릅니다)
--       동점이 있으면 LIMIT 1 은 그 중 하나만 고릅니다. 전부 원하면 Step 17 의 RANK() 를 쓰세요.

SELECT 'Step 03 solution 완료' AS msg;
