-- =====================================================================
-- Step 17 — 윈도우 함수 (Window Functions)  practice.sql
-- ---------------------------------------------------------------------
-- 실행:
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
--
-- 윈도우 함수는 MySQL 8.0 에서 처음 도입된 기능입니다. (5.7 에는 없습니다)
-- 이 스텝은 SELECT 만 합니다. 어떤 테이블도 변경하지 않습니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [17-1] GROUP BY 는 행을 "접고", 윈도우 함수는 행을 "남긴다"
-- ---------------------------------------------------------------------
-- (A) GROUP BY : 카테고리당 1행으로 줄어든다. 개별 상품명은 사라진다.
SELECT category_id,
       COUNT(*)   AS cnt,
       AVG(price) AS avg_price
FROM products
WHERE category_id IN (21, 22)
GROUP BY category_id;

-- (B) 윈도우 함수 : 상품 행은 그대로 두고, 옆에 카테고리 평균을 "붙인다".
SELECT product_id,
       category_id,
       name,
       price,
       AVG(price) OVER (PARTITION BY category_id) AS cat_avg_price,
       price - AVG(price) OVER (PARTITION BY category_id) AS diff_from_avg
FROM products
WHERE category_id IN (21, 22)
ORDER BY category_id, price DESC;

-- ---------------------------------------------------------------------
-- [17-2] OVER () — 빈 괄호는 "결과 집합 전체"가 하나의 윈도우
-- ---------------------------------------------------------------------
SELECT product_id,
       name,
       price,
       SUM(price)   OVER () AS total_price,
       COUNT(*)     OVER () AS row_cnt,
       ROUND(price / SUM(price) OVER () * 100, 1) AS pct
FROM products
WHERE category_id = 13
ORDER BY price DESC;

-- ---------------------------------------------------------------------
-- [17-3] PARTITION BY — 윈도우를 그룹으로 쪼갠다
-- ---------------------------------------------------------------------
-- 사원의 급여를 "부서 안에서" 비교한다.
SELECT dept,
       name,
       salary,
       MAX(salary) OVER (PARTITION BY dept)                  AS dept_max,
       ROUND(salary / MAX(salary) OVER (PARTITION BY dept), 2) AS ratio_to_top
FROM employees
WHERE dept IN ('개발본부', '영업본부')
ORDER BY dept, salary DESC;

-- ---------------------------------------------------------------------
-- [17-4] 순위 함수 : ROW_NUMBER / RANK / DENSE_RANK / NTILE
-- ---------------------------------------------------------------------
-- 상품별 판매 수량(취소 제외)을 카테고리 안에서 순위 매기기.
-- 동점(TIE)이 있을 때 세 함수가 어떻게 다른지 보세요.
WITH sold AS (
  SELECT p.category_id,
         p.product_id,
         p.name,
         SUM(oi.quantity) AS qty
  FROM order_items oi
  JOIN orders   o ON o.order_id   = oi.order_id
  JOIN products p ON p.product_id = oi.product_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY p.category_id, p.product_id, p.name
)
SELECT category_id,
       name,
       qty,
       ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY qty DESC) AS rn,
       RANK()       OVER (PARTITION BY category_id ORDER BY qty DESC) AS rnk,
       DENSE_RANK() OVER (PARTITION BY category_id ORDER BY qty DESC) AS drnk,
       NTILE(2)     OVER (PARTITION BY category_id ORDER BY qty DESC) AS tile
FROM sold
WHERE category_id IN (11, 13)
ORDER BY category_id, qty DESC, name;

-- ---------------------------------------------------------------------
-- [17-5] 집계 윈도우 : SUM / AVG / COUNT / MIN / MAX ... OVER
-- ---------------------------------------------------------------------
-- 도시별 매출과, 전체 매출에서 차지하는 비중.
WITH city_sales AS (
  SELECT shipping_city AS city, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
  GROUP BY shipping_city
)
SELECT city,
       amt,
       SUM(amt) OVER ()                              AS grand_total,
       ROUND(amt / SUM(amt) OVER () * 100, 2)        AS pct,
       ROUND(AVG(amt) OVER (), 0)                    AS avg_city_amt,
       COUNT(*) OVER ()                              AS city_cnt
FROM city_sales
ORDER BY amt DESC;

-- ---------------------------------------------------------------------
-- [17-6] 프레임 절 (1) : ORDER BY 를 쓰면 기본 프레임이 자동으로 붙는다
-- ---------------------------------------------------------------------
--   ORDER BY 없음  → 기본 프레임 = 파티션 전체     (전체 합계)
--   ORDER BY 있음  → 기본 프레임 = RANGE BETWEEN
--                       UNBOUNDED PRECEDING AND CURRENT ROW  (누적 합계!)
--
-- 그래서 아래 sum_no_order 와 running_sum 이 완전히 다릅니다.
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym,
         SUM(total_amount)                AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2024-01-01' AND order_date < '2025-01-01'
  GROUP BY ym
)
SELECT ym,
       amt,
       SUM(amt) OVER ()                 AS sum_no_order,   -- 파티션 전체 합
       SUM(amt) OVER (ORDER BY ym)      AS running_sum     -- 누적 합 (기본 프레임)
FROM m
ORDER BY ym;

-- ---------------------------------------------------------------------
-- [17-7] 프레임 절 (2) : ROWS vs RANGE — 동점(peer)이 있을 때 갈린다
-- ---------------------------------------------------------------------
--   ROWS  ... : "물리적인 행" 개수로 센다.  현재 행 = 딱 그 한 행.
--   RANGE ... : "값이 같은 행(peer)"을 한 덩어리로 본다. 동점은 통째로 포함.
--
-- 판매수량 60/60/60/45 처럼 동점이 있는 데이터로 확인합니다.
WITH sold AS (
  SELECT p.name, SUM(oi.quantity) AS qty
  FROM order_items oi
  JOIN orders   o ON o.order_id   = oi.order_id
  JOIN products p ON p.product_id = oi.product_id
  WHERE o.status <> 'CANCELLED' AND p.category_id = 11
  GROUP BY p.name
)
SELECT name,
       qty,
       SUM(qty) OVER (ORDER BY qty DESC
                      ROWS  BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS by_rows,
       SUM(qty) OVER (ORDER BY qty DESC
                      RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS by_range
FROM sold
ORDER BY qty DESC, name;

-- ---------------------------------------------------------------------
-- [17-8] 프레임 절 (3) : 이동 평균 (moving average)
-- ---------------------------------------------------------------------
-- 최근 3개월(자신 포함) 이동 평균 = ROWS BETWEEN 2 PRECEDING AND CURRENT ROW
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym,
         SUM(total_amount)                AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2024-01-01' AND order_date < '2025-01-01'
  GROUP BY ym
)
SELECT ym,
       amt,
       ROUND(AVG(amt) OVER (ORDER BY ym
                            ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 0) AS ma3,
       COUNT(*) OVER (ORDER BY ym
                      ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)           AS window_rows
FROM m
ORDER BY ym;

-- ---------------------------------------------------------------------
-- [17-9] ★ 유명한 함정 : LAST_VALUE 가 "마지막 값"을 주지 않는다
-- ---------------------------------------------------------------------
-- ORDER BY 가 있으면 기본 프레임이 ... AND CURRENT ROW 이므로
-- LAST_VALUE 의 "마지막"은 곧 "현재 행"이 됩니다. (= 자기 자신)
-- 해결: 프레임을 UNBOUNDED FOLLOWING 까지 명시적으로 넓힌다.
SELECT name,
       price,
       FIRST_VALUE(name) OVER (ORDER BY price DESC)  AS first_v,
       LAST_VALUE(name)  OVER (ORDER BY price DESC)  AS last_v_TRAP,   -- 함정!
       LAST_VALUE(name)  OVER (ORDER BY price DESC
                               ROWS BETWEEN UNBOUNDED PRECEDING
                                        AND UNBOUNDED FOLLOWING) AS last_v_FIXED
FROM products
WHERE category_id = 21
ORDER BY price DESC;

-- ---------------------------------------------------------------------
-- [17-10] 오프셋 함수 : LAG / LEAD — 전월 대비 증감률
-- ---------------------------------------------------------------------
--   LAG(expr, n, default)  : n 행 앞(과거)의 값
--   LEAD(expr, n, default) : n 행 뒤(미래)의 값
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym,
         SUM(total_amount)                AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2024-01-01' AND order_date < '2025-01-01'
  GROUP BY ym
)
SELECT ym,
       amt,
       LAG(amt)  OVER (ORDER BY ym)                        AS prev_amt,
       amt - LAG(amt) OVER (ORDER BY ym)                   AS diff,
       CONCAT(ROUND((amt - LAG(amt) OVER (ORDER BY ym))
                    / LAG(amt) OVER (ORDER BY ym) * 100, 1), '%') AS mom_pct,
       LEAD(amt) OVER (ORDER BY ym)                        AS next_amt
FROM m
ORDER BY ym;

-- ---------------------------------------------------------------------
-- [17-11] 오프셋 함수 : FIRST_VALUE / NTH_VALUE
-- ---------------------------------------------------------------------
-- 카테고리 안에서 1위 상품 / 2위 상품 이름을 모든 행에 붙인다.
-- FIRST_VALUE 는 기본 프레임에서도 잘 동작하지만(프레임 시작 = 파티션 시작),
-- NTH_VALUE 는 LAST_VALUE 와 똑같은 함정이 있으므로 프레임을 넓혀야 합니다.
SELECT category_id,
       name,
       price,
       FIRST_VALUE(name) OVER w AS top1,
       NTH_VALUE(name, 2) OVER (PARTITION BY category_id ORDER BY price DESC
                                ROWS BETWEEN UNBOUNDED PRECEDING
                                         AND UNBOUNDED FOLLOWING) AS top2
FROM products
WHERE category_id IN (13, 21)
WINDOW w AS (PARTITION BY category_id ORDER BY price DESC)
ORDER BY category_id, price DESC;

-- ---------------------------------------------------------------------
-- [17-12] 실전 : 그룹별 TOP-N (카테고리별 매출 상위 2개 상품)
-- ---------------------------------------------------------------------
-- 윈도우 함수는 WHERE 절에서 쓸 수 없습니다 (SELECT 다음에 평가되므로).
-- → CTE/서브쿼리로 한 번 감싼 뒤 바깥에서 필터링합니다. 이것이 정석 패턴입니다.
WITH rev AS (
  SELECT c.name           AS category,
         p.name           AS product,
         SUM(oi.quantity * oi.unit_price) AS revenue
  FROM order_items oi
  JOIN orders     o ON o.order_id   = oi.order_id
  JOIN products   p ON p.product_id = oi.product_id
  JOIN categories c ON c.category_id = p.category_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY c.name, p.name
),
ranked AS (
  SELECT rev.*,
         ROW_NUMBER() OVER (PARTITION BY category ORDER BY revenue DESC) AS rn
  FROM rev
)
SELECT category, rn, product, revenue
FROM ranked
WHERE rn <= 2
ORDER BY category, rn;

-- ---------------------------------------------------------------------
-- [17-13] 명명된 윈도우 (WINDOW 절) — 같은 OVER 를 반복하지 않기
-- ---------------------------------------------------------------------
-- WINDOW 절은 SELECT 뒤, ORDER BY 앞에 위치합니다.
-- 이름 붙인 윈도우를 상속해서 프레임만 바꿔 쓸 수도 있습니다: OVER (w ROWS ...)
SELECT dept,
       name,
       salary,
       ROW_NUMBER() OVER w                                    AS rn,
       SUM(salary)  OVER w                                    AS running_sum,
       SUM(salary)  OVER (w ROWS BETWEEN UNBOUNDED PRECEDING
                                     AND UNBOUNDED FOLLOWING) AS dept_total,
       LAG(salary)  OVER w                                    AS prev_salary
FROM employees
WHERE dept = '개발본부'
WINDOW w AS (PARTITION BY dept ORDER BY salary DESC)
ORDER BY salary DESC;

-- ---------------------------------------------------------------------
-- [17-14] 정리용 : 하나의 쿼리에 모아보기 (고객 등급별 구매 리포트)
-- ---------------------------------------------------------------------
WITH cust AS (
  SELECT c.grade, c.name, SUM(o.total_amount) AS amt
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.status = 'DELIVERED'
  GROUP BY c.grade, c.name
)
SELECT grade,
       name,
       amt,
       RANK()       OVER w                            AS rank_in_grade,
       ROUND(amt / SUM(amt) OVER (PARTITION BY grade) * 100, 1) AS pct_in_grade,
       ROUND(AVG(amt) OVER (PARTITION BY grade), 0)   AS grade_avg,
       NTILE(4)     OVER (ORDER BY amt DESC)          AS quartile_overall
FROM cust
WINDOW w AS (PARTITION BY grade ORDER BY amt DESC)
ORDER BY grade, amt DESC
LIMIT 12;
