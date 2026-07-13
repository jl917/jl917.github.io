-- =====================================================================
-- Step 09 — CTE와 재귀 쿼리 : 정답 + 해설
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- A1. 비재귀 CTE.
--     집계를 CTE 로 빼면 바깥 쿼리가 "무엇을 하려는지"만 남아 읽기 쉽다.
--     HAVING 으로도 되지만, 이후 단계가 붙기 시작하면 CTE 쪽이 확장성이 좋다.
-- ---------------------------------------------------------------------
WITH cat_cnt AS (
    SELECT category_id, COUNT(*) AS product_cnt
    FROM products
    GROUP BY category_id
)
SELECT cc.category_id, c.name AS cat_name, cc.product_cnt
FROM cat_cnt cc
JOIN categories c ON c.category_id = cc.category_id
WHERE cc.product_cnt >= 4
ORDER BY cc.product_cnt DESC, cc.category_id;
-- → 주변기기(5), 남성의류(4), 여성의류(4), 노트북(4), 신선식품(4)

-- ---------------------------------------------------------------------
-- A2. 재귀로 1..20 을 만든 뒤 바깥에서 짝수만 거른다.
--     재귀 부분에서 n+2 로 건너뛰어도 되지만(더 효율적),
--     "생성 → 필터"의 분리가 읽기엔 더 명확하다.
-- ---------------------------------------------------------------------
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 20
)
SELECT n FROM seq WHERE n % 2 = 0;
-- → 2,4,6,...,20 (10행)

-- 대안: 애초에 짝수만 생성 (반복 횟수가 절반)
WITH RECURSIVE evens AS (
    SELECT 2 AS n
    UNION ALL
    SELECT n + 2 FROM evens WHERE n < 20
)
SELECT n FROM evens;

-- ---------------------------------------------------------------------
-- A3. 조직도 전개.
--     path 를 만들어 두면 ORDER BY path 만으로 트리(깊이 우선) 순서가 나온다.
--     앵커에서 CAST(name AS CHAR(200)) 을 하지 않으면
--     path 가 VARCHAR(50) 로 고정되어 긴 경로가 잘린다. (매우 흔한 실수)
-- ---------------------------------------------------------------------
WITH RECURSIVE org AS (
    SELECT employee_id, name, manager_id, position, 1 AS depth,
           CAST(name AS CHAR(200)) AS path
    FROM employees
    WHERE manager_id IS NULL
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, e.position, o.depth + 1,
           CONCAT(o.path, ' > ', e.name)
    FROM employees e
    JOIN org o ON e.manager_id = o.employee_id
)
SELECT employee_id, depth,
       CONCAT(REPEAT('    ', depth - 1), name) AS tree,
       position
FROM org
ORDER BY path;
-- → 18행. depth 1(1명) / 2(3명) / 3(4명) / 4(10명)

-- ---------------------------------------------------------------------
-- A4. 상향 재귀. 조인 방향이 A3 과 반대다.
--     A3:  e.manager_id = o.employee_id   (아래로)
--     A4:  e.employee_id = u.manager_id   (위로)
-- ---------------------------------------------------------------------
WITH RECURSIVE up AS (
    SELECT employee_id, name, manager_id, position, 0 AS step
    FROM employees
    WHERE employee_id = 17
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, e.position, u.step + 1
    FROM employees e
    JOIN up u ON e.employee_id = u.manager_id
)
SELECT step, employee_id, name, position FROM up ORDER BY step;
-- → 노총무(0) > 윤사람(1) > 오지원(2) > 정한별(3)

-- ---------------------------------------------------------------------
-- A5. gap filling.
--     핵심 3가지:
--       ① 날짜 뼈대를 재귀 CTE 로 먼저 만든다
--       ② 뼈대가 LEFT JOIN 의 "왼쪽"에 와야 없는 날이 살아남는다
--       ③ COUNT(o.order_id) 를 써야 없는 날이 0 이 된다.
--          COUNT(*) 로 쓰면 LEFT JOIN 으로 붙은 NULL 행도 1로 세어 1이 나온다!
-- ---------------------------------------------------------------------
WITH RECURSIVE dates AS (
    SELECT DATE('2025-06-01') AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dates WHERE d < '2025-06-15'
)
SELECT dt.d, COUNT(o.order_id) AS order_cnt
FROM dates dt
LEFT JOIN orders o ON DATE(o.order_date) = dt.d
GROUP BY dt.d
ORDER BY dt.d;
-- → 15행. 주문 없는 날은 0.
--   (주의: 위는 DATE(o.order_date) 에 함수를 씌워 인덱스를 못 탄다.
--    행이 적은 학습용이라 괜찮지만, 실무에선 아래처럼 집계를 먼저 하고 조인하라.)

WITH RECURSIVE dates AS (
    SELECT DATE('2025-06-01') AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dates WHERE d < '2025-06-15'
),
daily AS (
    SELECT DATE(order_date) AS d, COUNT(*) AS cnt
    FROM orders
    WHERE order_date >= '2025-06-01' AND order_date < '2025-06-16'  -- 범위조건 → 인덱스 가능
    GROUP BY DATE(order_date)
)
SELECT dt.d, COALESCE(dl.cnt, 0) AS order_cnt
FROM dates dt
LEFT JOIN daily dl ON dl.d = dt.d
ORDER BY dt.d;

-- ---------------------------------------------------------------------
-- A6. CTE 체이닝. 단계마다 이름이 붙어 있어 중간 결과를 따로 실행해보기 쉽다.
-- ---------------------------------------------------------------------
WITH
vip_customers AS (
    SELECT customer_id FROM customers WHERE grade IN ('VIP','GOLD')
),
vip_orders AS (
    SELECT o.order_id, o.order_date, o.total_amount
    FROM orders o
    JOIN vip_customers v ON v.customer_id = o.customer_id
    WHERE o.status <> 'CANCELLED'
)
SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym,
       COUNT(*)                          AS order_cnt,
       SUM(total_amount)                 AS amount
FROM vip_orders
GROUP BY ym
ORDER BY ym
LIMIT 6;

-- ---------------------------------------------------------------------
-- A7. 카테고리 계층 전개. employees 와 완전히 같은 패턴이다.
--     계층형 데이터는 전부 이 틀로 푼다.
-- ---------------------------------------------------------------------
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
SELECT category_id, lvl, full_path FROM cat_tree ORDER BY full_path;
-- → 17행 (대분류 5 + 소분류 12)

-- ---------------------------------------------------------------------
-- A8. 깊이 한계 조정.
--     기본 1000 이므로 3000 까지 가려면 반드시 늘려야 한다.
--     GLOBAL 이 아니라 SESSION 으로 바꾸고, 끝나면 되돌리는 것이 예의다.
-- ---------------------------------------------------------------------
SET SESSION cte_max_recursion_depth = 5000;

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 3000
)
SELECT COUNT(*) AS cnt, SUM(n) AS total FROM seq;
-- → cnt=3000, total=4501500

SET SESSION cte_max_recursion_depth = 1000;

-- 참고: tally 테이블을 쓰면 재귀도, 설정 변경도 필요 없다.
SELECT COUNT(*) AS cnt, SUM(n) AS total FROM tally WHERE n <= 3000;
