-- =====================================================================
-- Step 17 — 윈도우 함수  solution.sql  (정답 + 해설)
-- ---------------------------------------------------------------------
-- 실행:
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < solution.sql
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] 고객별 총 구매액 순위
--
-- 해설:
--   먼저 CTE 에서 GROUP BY 로 고객별 합계를 "접고",
--   그 결과 집합에 RANK() 를 씌웁니다.
--   RANK 를 쓰라고 한 이유는 "동점이면 같은 순위, 다음은 건너뜀" 이기 때문.
--   ROW_NUMBER 를 쓰면 동점자에게 임의로 다른 번호가 붙어 순위표로는 부적절합니다.
-- ---------------------------------------------------------------------
WITH cust AS (
  SELECT c.customer_id, c.name, c.grade, SUM(o.total_amount) AS amt
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.status = 'DELIVERED'
  GROUP BY c.customer_id, c.name, c.grade
)
SELECT RANK() OVER (ORDER BY amt DESC) AS rnk,
       name, grade, amt
FROM cust
ORDER BY rnk
LIMIT 10;

-- ---------------------------------------------------------------------
-- [정답 2] 등급 안 순위 + 전체 순위 + 등급 평균
--
-- 해설:
--   서로 다른 윈도우가 3개 필요합니다.
--     - 전체 순위        : ORDER BY amt DESC          (PARTITION 없음)
--     - 등급 내 순위     : PARTITION BY grade ORDER BY amt DESC
--     - 등급 평균        : PARTITION BY grade         (ORDER BY 없음! 있으면 누적평균이 됨)
--   grade_avg 에 ORDER BY 를 넣으면 기본 프레임 때문에 "누적 평균"이 되어버립니다.
--   이것이 17-5 에서 배운 함정입니다.
-- ---------------------------------------------------------------------
WITH cust AS (
  SELECT c.name, c.grade, SUM(o.total_amount) AS amt
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.status = 'DELIVERED'
  GROUP BY c.name, c.grade
)
SELECT grade,
       name,
       amt,
       RANK() OVER wg                    AS rank_in_grade,
       RANK() OVER (ORDER BY amt DESC)   AS rank_overall,
       ROUND(AVG(amt) OVER (PARTITION BY grade), 0) AS grade_avg
FROM cust
WINDOW wg AS (PARTITION BY grade ORDER BY amt DESC)
ORDER BY grade, rank_in_grade
LIMIT 12;

-- ---------------------------------------------------------------------
-- [정답 3] 카테고리별 매출 1위 상품
--
-- 해설:
--   TOP-N 의 정석. 3단 구조입니다.
--     1) rev    : GROUP BY 로 상품별 매출 집계
--     2) ranked : ROW_NUMBER 로 카테고리 내 순위 부여
--     3) 바깥   : WHERE rn = 1 로 필터
--   2)와 3)을 한 번에 못 하는 이유 = 윈도우 함수는 WHERE 에서 평가 불가.
-- ---------------------------------------------------------------------
WITH rev AS (
  SELECT c.name AS category, p.name AS product,
         SUM(oi.quantity * oi.unit_price) AS revenue
  FROM order_items oi
  JOIN orders     o ON o.order_id    = oi.order_id
  JOIN products   p ON p.product_id  = oi.product_id
  JOIN categories c ON c.category_id = p.category_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY c.name, p.name
),
ranked AS (
  SELECT rev.*, ROW_NUMBER() OVER (PARTITION BY category ORDER BY revenue DESC) AS rn
  FROM rev
)
SELECT category, product, revenue
FROM ranked
WHERE rn = 1
ORDER BY revenue DESC;

-- ---------------------------------------------------------------------
-- [정답 4] 2025년 월별 매출 누적합
--
-- 해설:
--   ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW 가 누적합의 표준형입니다.
--   사실 ORDER BY 만 써도 기본 프레임이 같은 효과를 내지만(RANGE 기준),
--   "읽는 사람이 의도를 알 수 있도록" 명시하는 습관이 좋습니다.
-- ---------------------------------------------------------------------
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2025-01-01' AND order_date < '2026-01-01'
  GROUP BY ym
)
SELECT ym, amt,
       SUM(amt) OVER (ORDER BY ym
                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_sum
FROM m
ORDER BY ym;

-- ---------------------------------------------------------------------
-- [정답 5] 5개월 이동평균
--
-- 해설:
--   ROWS BETWEEN 4 PRECEDING AND CURRENT ROW  = 자신 포함 5개.
--   window_rows 를 같이 뽑아 보면 1~4월은 행이 모자라 1,2,3,4개뿐인 걸 알 수 있습니다.
--   → 이 구간의 "5개월 이동평균"은 5개월 평균이 아닙니다. 리포트에서 잘라내야 합니다.
-- ---------------------------------------------------------------------
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2025-01-01' AND order_date < '2026-01-01'
  GROUP BY ym
)
SELECT ym, amt,
       ROUND(AVG(amt) OVER w, 0) AS ma5,
       COUNT(*)       OVER w     AS window_rows
FROM m
WINDOW w AS (ORDER BY ym ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)
ORDER BY ym;

-- ---------------------------------------------------------------------
-- [정답 6] 전월 대비 증감률 + 최고 매출 월  ★ LAST_VALUE 함정
--
-- 해설:
--   핵심은 best_amt 입니다.
--     LAST_VALUE(amt) OVER (ORDER BY amt)            ← 틀림! 기본 프레임이 CURRENT ROW 까지라
--                                                       매 행마다 자기 자신을 반환합니다.
--     LAST_VALUE(amt) OVER (ORDER BY amt
--       ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)  ← 정답
--   물론 MAX(amt) OVER () 로도 같은 값을 얻을 수 있고 그게 더 간단합니다.
--   여기서는 함정을 눈으로 보라고 일부러 LAST_VALUE 를 씁니다. (bad 컬럼과 비교)
-- ---------------------------------------------------------------------
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2025-01-01' AND order_date < '2026-01-01'
  GROUP BY ym
)
SELECT ym, amt,
       CONCAT(ROUND((amt - LAG(amt) OVER (ORDER BY ym))
                    / LAG(amt) OVER (ORDER BY ym) * 100, 1), '%')  AS mom_pct,
       LAST_VALUE(amt) OVER (ORDER BY amt
                             ROWS BETWEEN UNBOUNDED PRECEDING
                                      AND UNBOUNDED FOLLOWING)      AS best_amt,
       MAX(amt) OVER ()                                             AS best_amt_simple,
       LAST_VALUE(amt) OVER (ORDER BY amt)                          AS best_amt_BAD
FROM m
ORDER BY ym;

-- ---------------------------------------------------------------------
-- [정답 7] 주문 간격
--
-- 해설:
--   LAG 로 이전 행의 order_date 를 가져온 뒤 DATEDIFF.
--   서브쿼리 없이 이전 행에 접근할 수 있다는 것이 윈도우 함수의 큰 장점입니다.
--   (MySQL 5.7 이었다면 셀프 조인이나 사용자 변수 트릭이 필요했습니다)
-- ---------------------------------------------------------------------
SELECT order_id,
       order_date,
       LAG(order_date) OVER w AS prev_date,
       DATEDIFF(order_date, LAG(order_date) OVER w) AS days_gap,
       total_amount
FROM orders
WHERE customer_id = 1
WINDOW w AS (ORDER BY order_date)
ORDER BY order_date;

-- ---------------------------------------------------------------------
-- [정답 8] NTILE 로 고객 4분위
--
-- 해설:
--   NTILE(4) 는 정렬 후 행을 4등분합니다. (행 수가 안 나눠떨어지면 앞쪽 버킷이 1개씩 더 가짐)
--   "금액을 4등분" 이 아니라 "행 수를 4등분" 이라는 점에 주의하세요.
--   금액 구간으로 나누고 싶다면 NTILE 이 아니라 CASE WHEN 이나 WIDTH_BUCKET 류의 계산이 필요합니다.
-- ---------------------------------------------------------------------
WITH cust AS (
  SELECT c.name, SUM(o.total_amount) AS amt
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.status = 'DELIVERED'
  GROUP BY c.name
),
q AS (
  SELECT name, amt, NTILE(4) OVER (ORDER BY amt DESC) AS quartile
  FROM cust
)
SELECT quartile,
       COUNT(*)          AS customers,
       ROUND(AVG(amt),0) AS avg_amt,
       MIN(amt)          AS min_amt,
       MAX(amt)          AS max_amt
FROM q
GROUP BY quartile
ORDER BY quartile;
