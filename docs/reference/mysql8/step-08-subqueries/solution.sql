-- =====================================================================
-- Step 08 — 서브쿼리 : 정답 + 해설
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- A1. 스칼라 서브쿼리를 WHERE 에 그대로 쓴다.
--     AVG 는 한 번만 계산된다(상관 서브쿼리가 아니므로).
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS over_avg_cnt
FROM orders
WHERE total_amount > (SELECT AVG(total_amount) FROM orders);
-- → 210

-- ---------------------------------------------------------------------
-- A2. NOT EXISTS 안티 조인.
--     상관 조건(r.product_id = p.product_id)이 반드시 필요하다.
-- ---------------------------------------------------------------------
SELECT p.product_id, p.name, p.price
FROM products p
WHERE NOT EXISTS (
    SELECT 1 FROM reviews r WHERE r.product_id = p.product_id
)
ORDER BY p.product_id;
-- → 24행

-- ---------------------------------------------------------------------
-- A3. NOT IN 버전.
--     핵심은 서브쿼리에 WHERE product_id IS NOT NULL 을 붙여
--     "NULL 이 섞여 들어올 가능성"을 원천 차단하는 것.
--     지금 스키마에선 reviews.product_id 가 NOT NULL 이라 없어도 되지만,
--     스키마는 바뀔 수 있으므로 방어적으로 쓰는 습관이 안전하다.
--     (근본적으로는 A2 처럼 NOT EXISTS 를 쓰는 게 낫다.)
-- ---------------------------------------------------------------------
SELECT p.product_id, p.name, p.price
FROM products p
WHERE p.product_id NOT IN (
    SELECT r.product_id FROM reviews r WHERE r.product_id IS NOT NULL
)
ORDER BY p.product_id;
-- → A2 와 동일하게 24행

-- ---------------------------------------------------------------------
-- A4. 파생 테이블로 먼저 집계 → 그다음 customers 와 조인.
--     조인부터 하고 GROUP BY 해도 되지만, 집계를 먼저 하면
--     조인 대상 행 수가 30건으로 줄어 계획이 단순해진다.
-- ---------------------------------------------------------------------
SELECT s.customer_id, c.name, c.grade, s.order_cnt, s.sum_amount
FROM (
    SELECT customer_id,
           COUNT(*)           AS order_cnt,
           SUM(total_amount)  AS sum_amount
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY customer_id
) AS s
JOIN customers c ON c.customer_id = s.customer_id
ORDER BY s.sum_amount DESC
LIMIT 5;
-- → 임수진(58,449,000) / 정 훈(51,568,000) / 황도윤(50,248,500) / 남규리 / 백승호

-- ---------------------------------------------------------------------
-- A5. 상관 서브쿼리. e.dept 를 안쪽에서 참조하므로
--     사원 18명 각각에 대해 부서 평균이 다시 계산된다.
-- ---------------------------------------------------------------------
SELECT e.employee_id, e.name, e.dept, e.salary,
       (SELECT ROUND(AVG(e2.salary)) FROM employees e2 WHERE e2.dept = e.dept) AS dept_avg
FROM employees e
WHERE e.salary > (SELECT AVG(e2.salary) FROM employees e2 WHERE e2.dept = e.dept)
ORDER BY e.dept, e.salary DESC;
-- → 7행 (개발본부 김코드/박서버/최화면, 경영지원 오지원/윤사람, 영업본부 이세일/강매출)

-- ---------------------------------------------------------------------
-- A6. > ALL : 서브쿼리의 "모든" 값보다 커야 한다 = 최댓값보다 커야 한다.
--     MAX 로 바꿔 써도 같다:  price > (SELECT MAX(price) ... )
-- ---------------------------------------------------------------------
SELECT product_id, name, price
FROM products
WHERE category_id = 21
  AND price > ALL (SELECT price FROM products WHERE category_id = 23)
ORDER BY price;
-- → 주변기기 최고가는 459,000(27인치 4K 모니터). 노트북 4종이 모두 그보다 비싸다.

-- ---------------------------------------------------------------------
-- A7. LATERAL (8.0.14+).
--     파생 테이블 안에서 바깥의 c.category_id 를 참조할 수 있다.
--     그래서 "카테고리마다 ORDER BY price DESC LIMIT 1" 이 가능해진다.
--     LATERAL 이 없다면 윈도우 함수(ROW_NUMBER) 를 써야 한다.
-- ---------------------------------------------------------------------
SELECT c.category_id, c.name AS cat_name,
       t.product_id, t.name AS product_name, t.price
FROM categories c
JOIN LATERAL (
    SELECT p.product_id, p.name, p.price
    FROM products p
    WHERE p.category_id = c.category_id
    ORDER BY p.price DESC, p.product_id
    LIMIT 1
) AS t ON TRUE
ORDER BY t.price DESC;
-- → 12행 (상품이 있는 카테고리만. JOIN 이므로 상품 없는 카테고리는 자동 제외)

-- ---------------------------------------------------------------------
-- A8-a. NOT EXISTS
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS cnt_not_exists
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.order_id);
-- → 60

-- ---------------------------------------------------------------------
-- A8-b. LEFT JOIN ... IS NULL
--     주의: IS NULL 검사는 조인 대상 테이블의 "NOT NULL 컬럼"(보통 PK)에 걸어야 한다.
--     NULL 을 허용하는 컬럼에 걸면 "조인은 됐지만 그 컬럼이 NULL 인 행"까지 섞인다.
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS cnt_left_join
FROM orders o
LEFT JOIN payments p ON p.order_id = o.order_id
WHERE p.payment_id IS NULL;
-- → 60  (NOT EXISTS 와 동일)

-- ---------------------------------------------------------------------
-- 보너스: 셋 중 무엇을 쓸까?
--   - 안티 조인은 NOT EXISTS 가 의도가 가장 명확하고 NULL 에 안전하다.
--   - LEFT JOIN IS NULL 은 예전 MySQL 에서 더 빨랐지만,
--     8.0 은 NOT EXISTS 도 안티조인으로 최적화하므로 차이가 거의 없다.
--   - NOT IN 은 NULL 함정 때문에 마지막 선택지.
-- ---------------------------------------------------------------------
