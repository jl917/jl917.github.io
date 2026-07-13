-- =====================================================================
-- Step 09 — CTE와 재귀 쿼리 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
-- * 이 파일은 SELECT 만 합니다. 원본 데이터를 변경하지 않습니다.
-- =====================================================================
USE shop;

-- [9-1] 비재귀 CTE : 파생 테이블을 이름 붙여 앞으로 빼낸다
WITH customer_stats AS (
    SELECT customer_id,
           COUNT(*)          AS order_cnt,
           SUM(total_amount) AS sum_amount
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY customer_id
)
SELECT c.customer_id, c.name, s.order_cnt, s.sum_amount
FROM customer_stats s
JOIN customers c ON c.customer_id = s.customer_id
WHERE s.sum_amount >= 40000000
ORDER BY s.sum_amount DESC;

-- [9-2] 같은 CTE 를 두 번 참조할 수 있다 (파생 테이블은 불가능한 일)
WITH monthly AS (
    SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym,
           SUM(total_amount) AS amt
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY ym
)
SELECT cur.ym,
       cur.amt,
       prev.amt AS prev_amt,
       ROUND((cur.amt - prev.amt) / prev.amt * 100, 1) AS growth_pct
FROM monthly cur
JOIN monthly prev
  ON prev.ym = DATE_FORMAT(DATE_SUB(STR_TO_DATE(CONCAT(cur.ym,'-01'), '%Y-%m-%d'),
                                    INTERVAL 1 MONTH), '%Y-%m')
ORDER BY cur.ym;

-- [9-3] CTE 체이닝 : 앞의 CTE 를 뒤의 CTE 가 참조한다
WITH
paid_orders AS (
    SELECT order_id, customer_id, total_amount, order_date
    FROM orders
    WHERE status IN ('PAID','SHIPPED','DELIVERED')
),
per_customer AS (
    SELECT customer_id,
           COUNT(*)                 AS cnt,
           ROUND(AVG(total_amount)) AS avg_amt
    FROM paid_orders
    GROUP BY customer_id
),
ranked AS (
    SELECT p.*, c.name, c.grade
    FROM per_customer p
    JOIN customers c ON c.customer_id = p.customer_id
)
SELECT customer_id, name, grade, cnt, avg_amt
FROM ranked
ORDER BY avg_amt DESC
LIMIT 5;

-- [9-4] 가장 단순한 재귀 CTE : 1부터 5까지
WITH RECURSIVE seq AS (
    SELECT 1 AS n                       -- 앵커(anchor) : 시작점
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 5   -- 재귀 : 자기 자신을 참조
)
SELECT n FROM seq;

-- [9-5] 종료 조건을 빼면? → cte_max_recursion_depth 가 우리를 구해준다
-- WITH RECURSIVE boom AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM boom)
-- SELECT * FROM boom;
-- ERROR 3636 (HY000): Recursive query aborted after 1001 iterations.
-- Try increasing @@cte_max_recursion_depth to a larger value.

-- [9-6] 기본 재귀 깊이 한계 확인
SELECT @@cte_max_recursion_depth AS default_depth;

-- [9-7] 깊이를 늘려서 1~5000 만들기 (세션 한정)
SET SESSION cte_max_recursion_depth = 10000;
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 5000
)
SELECT COUNT(*) AS cnt, MAX(n) AS max_n FROM seq;
SET SESSION cte_max_recursion_depth = 1000;

-- [9-8] 재귀 CTE 로 조직도 전개 (하향 : CEO → 말단)
WITH RECURSIVE org AS (
    SELECT employee_id, name, manager_id, dept, position, 1 AS lvl,
           CAST(name AS CHAR(200)) AS path
    FROM employees
    WHERE manager_id IS NULL              -- 앵커: 최상위
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, e.dept, e.position, o.lvl + 1,
           CONCAT(o.path, ' > ', e.name)
    FROM employees e
    JOIN org o ON e.manager_id = o.employee_id   -- 부모를 찾아 붙인다
)
SELECT lvl,
       CONCAT(REPEAT('    ', lvl - 1), name) AS tree,
       position, dept, path
FROM org
ORDER BY path;

-- [9-9] 특정 사원의 상위 라인 거슬러 올라가기 (상향)
WITH RECURSIVE up AS (
    SELECT employee_id, name, manager_id, position, 0 AS up_lvl
    FROM employees
    WHERE employee_id = 13                -- 남뷰어(주니어)
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, e.position, u.up_lvl + 1
    FROM employees e
    JOIN up u ON e.employee_id = u.manager_id
)
SELECT up_lvl, employee_id, name, position FROM up ORDER BY up_lvl;

-- [9-10] 부하 직원 수 세기 (하위 조직 전체 인원)
WITH RECURSIVE sub AS (
    SELECT employee_id AS root_id, employee_id, name
    FROM employees
    UNION ALL
    SELECT s.root_id, e.employee_id, e.name
    FROM employees e
    JOIN sub s ON e.manager_id = s.employee_id
)
SELECT r.employee_id, r.name, r.position,
       COUNT(*) - 1 AS descendants     -- 자기 자신 제외
FROM sub s
JOIN employees r ON r.employee_id = s.root_id
GROUP BY r.employee_id, r.name, r.position
ORDER BY descendants DESC, r.employee_id
LIMIT 6;

-- [9-11] 재귀 CTE 로 날짜 시퀀스 생성 (2025-01-01 ~ 2025-01-10)
WITH RECURSIVE dates AS (
    SELECT DATE('2025-01-01') AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dates WHERE d < '2025-01-10'
)
SELECT d, DAYNAME(d) AS dow FROM dates;

-- [9-12] 빈 구간 채우기(gap filling) : 주문이 없는 날도 0으로 표시
WITH RECURSIVE dates AS (
    SELECT DATE('2025-03-01') AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dates WHERE d < '2025-03-10'
),
daily AS (
    SELECT DATE(order_date) AS d, COUNT(*) AS cnt, SUM(total_amount) AS amt
    FROM orders
    WHERE order_date >= '2025-03-01' AND order_date < '2025-03-11'
    GROUP BY DATE(order_date)
)
SELECT dt.d,
       COALESCE(dl.cnt, 0) AS order_cnt,
       COALESCE(dl.amt, 0) AS amount
FROM dates dt
LEFT JOIN daily dl ON dl.d = dt.d
ORDER BY dt.d;

-- [9-13] 빈 구간을 채우지 않으면? (비교용 — 없는 날은 아예 사라진다)
SELECT DATE(order_date) AS d, COUNT(*) AS cnt
FROM orders
WHERE order_date >= '2025-03-01' AND order_date < '2025-03-11'
GROUP BY DATE(order_date)
ORDER BY d;

-- [9-14] 월 시퀀스 생성 + 월별 매출 (빈 달 0)
WITH RECURSIVE months AS (
    SELECT DATE('2025-01-01') AS m
    UNION ALL
    SELECT m + INTERVAL 1 MONTH FROM months WHERE m < '2025-06-01'
)
SELECT DATE_FORMAT(mo.m, '%Y-%m') AS ym,
       COUNT(o.order_id)              AS order_cnt,
       COALESCE(SUM(o.total_amount),0) AS amount
FROM months mo
LEFT JOIN orders o
       ON o.order_date >= mo.m
      AND o.order_date <  mo.m + INTERVAL 1 MONTH
      AND o.status <> 'CANCELLED'
GROUP BY ym
ORDER BY ym;

-- [9-15] tally 테이블로도 같은 일을 할 수 있다 (재귀보다 빠르다)
SELECT DATE_ADD('2025-01-01', INTERVAL t.n - 1 DAY) AS d
FROM tally t
WHERE t.n <= 5;

-- [9-16] 카테고리 계층 전개 (재귀 CTE)
WITH RECURSIVE cat_tree AS (
    SELECT category_id, parent_id, name, 1 AS lvl,
           CAST(name AS CHAR(200)) AS full_path
    FROM categories
    WHERE parent_id IS NULL
    UNION ALL
    SELECT c.category_id, c.parent_id, c.name, t.lvl + 1,
           CONCAT(t.full_path, ' / ', c.name)
    FROM categories c
    JOIN cat_tree t ON c.parent_id = t.category_id
)
SELECT category_id, lvl, full_path FROM cat_tree ORDER BY full_path LIMIT 8;

-- [9-17] 무한루프 방지 : 재귀 CTE 안에서는 UNION ALL 대신 UNION 을 쓰면 중복이 제거된다
--        (사이클이 있는 그래프에서 유용하지만, 완전한 방어는 아니다)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION            -- DISTINCT: 같은 행이 다시 나오면 재귀가 멈춘다
    SELECT 1 FROM seq
)
SELECT * FROM seq;

-- [9-18] 방어적 패턴 : 깊이 상한을 쿼리 안에 직접 넣는다
WITH RECURSIVE org AS (
    SELECT employee_id, name, manager_id, 1 AS lvl
    FROM employees WHERE manager_id IS NULL
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, o.lvl + 1
    FROM employees e
    JOIN org o ON e.manager_id = o.employee_id
    WHERE o.lvl < 10          -- ← 안전장치. 데이터에 사이클이 있어도 10에서 멈춘다
)
SELECT lvl, COUNT(*) AS cnt FROM org GROUP BY lvl ORDER BY lvl;

-- [9-19] CTE 는 INSERT/UPDATE/DELETE 앞에도 붙일 수 있다 (구문만 확인)
-- WITH target AS (SELECT order_id FROM orders WHERE status = 'PENDING')
-- DELETE FROM orders WHERE order_id IN (SELECT order_id FROM target);
--   → Step 11 에서 실제로 실습합니다(사본 테이블에서).

-- [9-20] 실행계획 : 여러 번 참조되는 CTE 는 한 번만 구체화(materialize)된다
EXPLAIN FORMAT=TREE
WITH monthly AS (
    SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
    FROM orders GROUP BY ym
)
SELECT a.ym, a.amt, b.amt FROM monthly a JOIN monthly b ON b.ym = a.ym;
