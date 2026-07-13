-- =====================================================================
-- Step 08 — 서브쿼리 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
-- * 이 파일은 SELECT 만 합니다. 원본 데이터를 변경하지 않습니다.
-- =====================================================================
USE shop;

-- [8-1] 스칼라 서브쿼리 : 전체 평균 주문금액을 한 컬럼으로 붙이기
SELECT
    o.order_id,
    o.total_amount,
    (SELECT ROUND(AVG(total_amount), 0) FROM orders) AS avg_all,
    o.total_amount - (SELECT AVG(total_amount) FROM orders) AS diff
FROM orders o
ORDER BY o.total_amount DESC
LIMIT 5;

-- [8-2] WHERE 절 스칼라 서브쿼리 : 평균보다 비싼 상품
SELECT product_id, name, price
FROM products
WHERE price > (SELECT AVG(price) FROM products)
ORDER BY price DESC
LIMIT 5;

-- [8-3] 스칼라 서브쿼리가 2행 이상 반환하면 에러 (일부러 실패시키는 예제)
-- SELECT * FROM products WHERE price = (SELECT price FROM products WHERE category_id = 21);
-- ERROR 1242 (21000): Subquery returns more than 1 row

-- [8-4] IN : 다중행 서브쿼리 — 서울에 사는 고객의 주문
SELECT order_id, customer_id, total_amount
FROM orders
WHERE customer_id IN (SELECT customer_id FROM customers WHERE city = '서울')
ORDER BY order_id
LIMIT 5;

-- [8-5] 행(ROW) 서브쿼리 : 여러 컬럼을 한 번에 비교
SELECT order_id, customer_id, order_date, total_amount
FROM orders
WHERE (customer_id, total_amount) = (
    SELECT customer_id, MAX(total_amount)
    FROM orders
    WHERE customer_id = 1
    GROUP BY customer_id
);

-- [8-6] FROM 절 서브쿼리(파생 테이블) : 고객별 합계를 먼저 만들고 다시 필터
SELECT s.customer_id, c.name, s.order_cnt, s.sum_amount
FROM (
    SELECT customer_id, COUNT(*) AS order_cnt, SUM(total_amount) AS sum_amount
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY customer_id
) AS s
JOIN customers c ON c.customer_id = s.customer_id
WHERE s.sum_amount >= 3000000
ORDER BY s.sum_amount DESC
LIMIT 5;

-- [8-7] 파생 테이블에는 반드시 별칭(alias)이 필요하다
-- SELECT * FROM (SELECT 1) ;   -- ERROR 1248 (42000): Every derived table must have its own alias

-- [8-8] 상관 서브쿼리 : 바깥 행마다 안쪽 쿼리가 다시 평가된다
SELECT
    c.customer_id,
    c.name,
    (SELECT COUNT(*)      FROM orders o WHERE o.customer_id = c.customer_id) AS order_cnt,
    (SELECT MAX(o.order_date) FROM orders o WHERE o.customer_id = c.customer_id) AS last_order
FROM customers c
ORDER BY order_cnt DESC
LIMIT 5;

-- [8-9] EXISTS : "있기만 하면 참" — 후기를 한 번이라도 쓴 고객
SELECT c.customer_id, c.name, c.grade
FROM customers c
WHERE EXISTS (
    SELECT 1 FROM reviews r WHERE r.customer_id = c.customer_id
)
ORDER BY c.customer_id
LIMIT 5;

-- [8-10] NOT EXISTS : 결제 기록이 하나도 없는 주문(PENDING)
SELECT o.order_id, o.status, o.total_amount
FROM orders o
WHERE NOT EXISTS (
    SELECT 1 FROM payments p WHERE p.order_id = o.order_id
)
ORDER BY o.order_id
LIMIT 5;

-- [8-11] NOT EXISTS 로 세는 전체 건수
SELECT COUNT(*) AS no_payment_orders
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.order_id);

-- [8-12] 같은 질문 3가지 방법 (1) IN
SELECT COUNT(*) AS cnt
FROM products p
WHERE p.product_id IN (SELECT oi.product_id FROM order_items oi);

-- [8-13] 같은 질문 3가지 방법 (2) EXISTS
SELECT COUNT(*) AS cnt
FROM products p
WHERE EXISTS (SELECT 1 FROM order_items oi WHERE oi.product_id = p.product_id);

-- [8-14] 같은 질문 3가지 방법 (3) JOIN — DISTINCT 를 잊으면 결과가 부풀어 오른다
SELECT COUNT(*) AS cnt_wrong
FROM products p
JOIN order_items oi ON oi.product_id = p.product_id;

-- [8-15] JOIN 으로 올바르게 세기
SELECT COUNT(DISTINCT p.product_id) AS cnt_right
FROM products p
JOIN order_items oi ON oi.product_id = p.product_id;

-- [8-16] IN 서브쿼리의 실행계획 (8.0 은 semijoin 으로 변환한다)
EXPLAIN FORMAT=TREE
SELECT p.product_id FROM products p
WHERE p.product_id IN (SELECT oi.product_id FROM order_items oi);

-- [8-17] ANY : 서브쿼리 결과 중 "하나라도" 만족
SELECT product_id, name, price
FROM products
WHERE category_id = 21
  AND price > ANY (SELECT price FROM products WHERE category_id = 22)
ORDER BY price;

-- [8-18] ALL : 서브쿼리 결과 "전부"보다 커야 함
SELECT product_id, name, price
FROM products
WHERE category_id = 21
  AND price > ALL (SELECT price FROM products WHERE category_id = 22)
ORDER BY price;

-- [8-19] 비교 대상 확인 (스마트폰 카테고리 가격)
SELECT product_id, name, price FROM products WHERE category_id = 22 ORDER BY price;

-- [8-20] NOT IN + NULL 함정 — 관리자가 아닌 사원을 찾으려 했지만 0행
SELECT COUNT(*) AS cnt
FROM employees e
WHERE e.employee_id NOT IN (SELECT manager_id FROM employees);

-- [8-21] 원인 : 서브쿼리에 NULL 이 섞여 있다
SELECT manager_id, COUNT(*) AS cnt
FROM employees
GROUP BY manager_id
ORDER BY manager_id
LIMIT 3;

-- [8-22] NULL 과의 비교는 UNKNOWN
SELECT 5 NOT IN (1, 2, NULL)  AS r1,
       5 IN     (1, 2, NULL)  AS r2,
       5 <> NULL              AS r3;

-- [8-23] 해결 1 : 서브쿼리에서 NULL 제거
SELECT COUNT(*) AS cnt
FROM employees e
WHERE e.employee_id NOT IN (
    SELECT manager_id FROM employees WHERE manager_id IS NOT NULL
);

-- [8-24] 해결 2 : NOT EXISTS (NULL 에 안전. 가장 권장)
SELECT COUNT(*) AS cnt
FROM employees e
WHERE NOT EXISTS (
    SELECT 1 FROM employees m WHERE m.manager_id = e.employee_id
);

-- [8-25] 해결 3 : LEFT JOIN ... IS NULL (안티 조인)
SELECT COUNT(*) AS cnt
FROM employees e
LEFT JOIN employees m ON m.manager_id = e.employee_id
WHERE m.employee_id IS NULL;

-- [8-26] 파생 테이블 머지(derived merge) — 8.0 은 파생 테이블을 바깥 쿼리에 합친다
EXPLAIN FORMAT=TREE
SELECT d.product_id, d.name
FROM (SELECT product_id, name, price, category_id FROM products) AS d
WHERE d.category_id = 21;

-- [8-27] 머지를 막는 요소(GROUP BY 등)가 있으면 임시테이블로 구체화(materialize)된다
EXPLAIN FORMAT=TREE
SELECT d.category_id, d.cnt
FROM (SELECT category_id, COUNT(*) AS cnt FROM products GROUP BY category_id) AS d
WHERE d.cnt >= 4;

-- [8-28] optimizer_switch 로 머지를 강제로 끄고 비교 (세션 한정)
SET SESSION optimizer_switch = 'derived_merge=off';
EXPLAIN FORMAT=TREE
SELECT d.product_id, d.name
FROM (SELECT product_id, name, price, category_id FROM products) AS d
WHERE d.category_id = 21;
SET SESSION optimizer_switch = 'derived_merge=on';

-- [8-29] LATERAL (8.0.14+) : 파생 테이블이 바깥 행을 참조할 수 있다
SELECT c.customer_id, c.name, t.order_id, t.total_amount
FROM customers c
JOIN LATERAL (
    SELECT o.order_id, o.total_amount
    FROM orders o
    WHERE o.customer_id = c.customer_id
    ORDER BY o.total_amount DESC
    LIMIT 1
) AS t ON TRUE
ORDER BY t.total_amount DESC
LIMIT 5;

-- [8-30] 종합 : 카테고리 평균가보다 비싼 상품 (상관 서브쿼리)
SELECT p.product_id, p.name, p.category_id, p.price,
       (SELECT ROUND(AVG(p2.price)) FROM products p2 WHERE p2.category_id = p.category_id) AS cat_avg
FROM products p
WHERE p.price > (SELECT AVG(p2.price) FROM products p2 WHERE p2.category_id = p.category_id)
ORDER BY p.category_id, p.price DESC
LIMIT 8;
