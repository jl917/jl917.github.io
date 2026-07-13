-- =====================================================================
-- Step 06 — 집계함수와 GROUP BY : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < practice.sql
-- =====================================================================
USE shop;

-- [6-1] 집계함수 기본 — 여러 행을 한 행으로 접는다
SELECT
    COUNT(*)      AS 상품수,
    SUM(stock)    AS 총재고,
    AVG(price)    AS 평균가,
    MIN(price)    AS 최저가,
    MAX(price)    AS 최고가
FROM products;

-- [6-1] ROUND 로 보기 좋게
SELECT
    COUNT(*)             AS 상품수,
    ROUND(AVG(price))    AS 평균가,
    FORMAT(SUM(price * stock), 0) AS 재고자산
FROM products;

-- [6-2] COUNT 3형제 — 셋은 완전히 다른 것을 센다
--   employees 는 18명, 그중 CEO 1명만 manager_id 가 NULL
SELECT
    COUNT(*)                    AS `COUNT(*)`,
    COUNT(manager_id)           AS `COUNT(manager_id)`,
    COUNT(DISTINCT manager_id)  AS `COUNT(DISTINCT manager_id)`
FROM employees;

-- [6-2] customers 로 한 번 더 — phone 이 NULL 인 3명
SELECT
    COUNT(*)            AS 전체고객,
    COUNT(phone)        AS 전화번호_있는_고객,
    COUNT(DISTINCT city) AS 도시_수
FROM customers;

-- [6-2] COUNT(1) 은 COUNT(*) 와 동일하다 (빠르지도 느리지도 않다)
SELECT COUNT(*) AS star, COUNT(1) AS one FROM orders;

-- [6-3] 집계함수는 NULL 을 "무시"한다 — AVG 의 함정
--   points 가 0 인 고객 3명을 "미적립"으로 본다면 평균이 달라진다
SELECT
    COUNT(*)                        AS 전체,
    AVG(points)                     AS `AVG(points)_0포함`,
    AVG(NULLIF(points, 0))          AS `AVG_0을_NULL로`,
    SUM(points) / COUNT(*)          AS `SUM/COUNT(*)`,
    SUM(points) / COUNT(NULLIF(points,0)) AS `SUM/COUNT(non-zero)`
FROM customers;

-- [6-4] GROUP BY — 그룹마다 집계
SELECT
    city                AS 도시,
    COUNT(*)            AS 고객수,
    ROUND(AVG(points))  AS 평균포인트,
    MAX(points)         AS 최대포인트
FROM customers
GROUP BY city
ORDER BY 고객수 DESC, 도시;

-- [6-4] 여러 컬럼으로 그룹핑
SELECT
    grade    AS 등급,
    city     AS 도시,
    COUNT(*) AS 고객수
FROM customers
GROUP BY grade, city
ORDER BY grade, city
LIMIT 10;

-- [6-4] 주문 상태별 집계
SELECT
    status                        AS 상태,
    COUNT(*)                      AS 주문수,
    FORMAT(SUM(total_amount), 0)  AS 합계금액,
    FORMAT(ROUND(AVG(total_amount)), 0) AS 평균금액
FROM orders
GROUP BY status
ORDER BY 주문수 DESC;

-- [6-5] WHERE 와 HAVING 의 차이
--   WHERE  : 그룹핑 "전" 에 개별 행을 거른다
--   HAVING : 그룹핑 "후" 에 그룹을 거른다
SELECT
    city     AS 도시,
    COUNT(*) AS VIP_GOLD_수
FROM customers
WHERE grade IN ('VIP', 'GOLD')     -- 먼저 행을 거르고
GROUP BY city
HAVING COUNT(*) >= 2                -- 그 다음 그룹을 거른다
ORDER BY VIP_GOLD_수 DESC, 도시;

-- [6-5] HAVING 에서는 SELECT 별칭을 쓸 수 있다 (MySQL 확장)
SELECT
    city     AS 도시,
    COUNT(*) AS 고객수
FROM customers
GROUP BY city
HAVING 고객수 >= 3
ORDER BY 고객수 DESC;

-- [6-5] HAVING 에 집계가 아닌 조건을 넣는 실수 (동작은 하지만 느리다)
--   나쁜 예: 인덱스를 못 쓰고 전부 그룹핑한 뒤 버린다
SELECT city, COUNT(*) AS cnt
FROM customers
GROUP BY city
HAVING city IN ('서울', '부산');

--   좋은 예: WHERE 로 미리 걸러서 그룹핑 대상 자체를 줄인다
SELECT city, COUNT(*) AS cnt
FROM customers
WHERE city IN ('서울', '부산')
GROUP BY city;

-- [6-6] ONLY_FULL_GROUP_BY — MySQL 8 의 기본값
SELECT @@sql_mode;

-- [6-6] GROUP BY 에 없는 컬럼을 SELECT 하면 에러 (MySQL 5.7 이전엔 통과했다!)
--   SELECT city, name, COUNT(*) FROM customers GROUP BY city;
--   ERROR 1055 (42000): Expression #2 of SELECT list is not in GROUP BY clause
--     and contains nonaggregated column 'shop.customers.name' which is not
--     functionally dependent on columns in GROUP BY clause;
--     this is incompatible with sql_mode=only_full_group_by

-- [6-6] 해결책 ①: 집계함수로 감싼다 (의미가 명확해진다)
SELECT city, MAX(name) AS 대표이름, COUNT(*) AS 고객수
FROM customers
GROUP BY city
ORDER BY 고객수 DESC
LIMIT 5;

-- [6-6] 해결책 ②: ANY_VALUE() — "아무 값이나 하나" 라고 명시적으로 선언
SELECT city, ANY_VALUE(name) AS 샘플이름, COUNT(*) AS 고객수
FROM customers
GROUP BY city
ORDER BY 고객수 DESC
LIMIT 5;

-- [6-6] 기능적 종속성(functional dependency)은 허용된다
--   customer_id 가 PK 이므로 name 은 customer_id 에 종속 → 에러 안 남
SELECT c.customer_id, c.name, COUNT(o.order_id) AS 주문수
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.customer_id
ORDER BY 주문수 DESC
LIMIT 5;

-- [6-7] MySQL 8 에서 제거된 문법: GROUP BY ... ASC/DESC
--   SELECT city, COUNT(*) FROM customers GROUP BY city DESC;
--   ERROR 1064 (42000): You have an error in your SQL syntax ... near 'DESC'
--
--   → 정렬은 ORDER BY 로 명시해야 한다 (8.0.13 에서 제거)
SELECT city, COUNT(*) AS cnt
FROM customers
GROUP BY city
ORDER BY city DESC;

-- [6-8] WITH ROLLUP — 소계/총계를 자동으로
SELECT
    status                       AS 상태,
    COUNT(*)                     AS 주문수,
    FORMAT(SUM(total_amount), 0) AS 합계
FROM orders
GROUP BY status WITH ROLLUP;

-- [6-8] 2단계 ROLLUP — 도시별/등급별 소계 + 총계
SELECT
    city     AS 도시,
    grade    AS 등급,
    COUNT(*) AS 고객수
FROM customers
WHERE city IN ('서울', '부산')
GROUP BY city, grade WITH ROLLUP;

-- [6-8] ROLLUP 의 NULL 은 두 종류다 — GROUPING() 으로 구분 (MySQL 8 신규)
--   GROUPING(col) = 1 이면 "ROLLUP 이 만든 소계 행", 0 이면 "진짜 데이터"
SELECT
    IF(GROUPING(city)  = 1, '── 전체 ──', city)  AS 도시,
    IF(GROUPING(grade) = 1, '  소계',     grade) AS 등급,
    COUNT(*)                                      AS 고객수,
    GROUPING(city)                                AS g_city,
    GROUPING(grade)                               AS g_grade
FROM customers
WHERE city IN ('서울', '부산')
GROUP BY city, grade WITH ROLLUP;

-- [6-9] GROUP_CONCAT — 그룹의 값들을 한 문자열로
SELECT
    city                                   AS 도시,
    COUNT(*)                               AS 고객수,
    GROUP_CONCAT(name ORDER BY name SEPARATOR ', ') AS 고객목록
FROM customers
GROUP BY city
ORDER BY 고객수 DESC;

-- [6-9] GROUP_CONCAT + DISTINCT
SELECT
    c.parent_id                                       AS 상위카테고리,
    GROUP_CONCAT(DISTINCT c.name ORDER BY c.sort_order) AS 하위목록
FROM categories c
WHERE c.parent_id IS NOT NULL
GROUP BY c.parent_id
ORDER BY c.parent_id;

-- [6-9] GROUP_CONCAT 의 함정: group_concat_max_len (기본 1024 바이트)
--   넘으면 에러가 아니라 "조용히 잘린다"! (경고만 뜨는데 보통 아무도 안 본다)
SELECT @@group_concat_max_len AS 기본_최대길이;

--   600건 주문 id 를 전부 이어붙이면 2291 바이트가 나와야 하는데...
SELECT
    LENGTH(GROUP_CONCAT(order_id)) AS 잘린_길이,
    COUNT(*)                       AS 실제_주문수
FROM orders;

--   딱 1024 에서 잘렸다. 이때 경고가 뜬다 (대화형 클라이언트에서 SHOW WARNINGS):
--     Warning 1260  Row 269 was cut by GROUP_CONCAT()
--
--   세션 단위로 늘린다 (공용 DB 이므로 GLOBAL 이 아니라 SESSION 만 변경!)
SET SESSION group_concat_max_len = 1000000;

--   이제 온전한 2291 바이트가 나온다
SELECT
    LENGTH(GROUP_CONCAT(order_id)) AS 온전한_길이,
    COUNT(*)                       AS 실제_주문수
FROM orders;

-- [6-10] 종합 ①: 카테고리별 매출 TOP 5 (조인은 Step 07 에서 자세히)
SELECT
    cat.name                              AS 카테고리,
    COUNT(DISTINCT o.order_id)            AS 주문수,
    SUM(oi.quantity)                      AS 판매수량,
    FORMAT(SUM(oi.quantity * oi.unit_price), 0) AS 매출
FROM order_items oi
JOIN orders o     ON o.order_id   = oi.order_id
JOIN products p   ON p.product_id = oi.product_id
JOIN categories cat ON cat.category_id = p.category_id
WHERE o.status <> 'CANCELLED'
GROUP BY cat.category_id, cat.name
HAVING SUM(oi.quantity) >= 100
ORDER BY SUM(oi.quantity * oi.unit_price) DESC
LIMIT 5;

-- [6-10] 종합 ②: 등급별 매출 — 그리고 "사라진 그룹" 함정
--   WHERE 로 CANCELLED 를 거르면, 주문이 "전부" 취소된 고객은
--   그룹 자체가 사라진다. 고객수 합계가 30 이 아니라 27 이 된다!
SELECT
    c.grade                        AS 등급,
    COUNT(DISTINCT c.customer_id)  AS 고객수,
    COUNT(o.order_id)              AS 주문수,
    FORMAT(SUM(o.total_amount), 0) AS 총매출
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
WHERE o.status <> 'CANCELLED'
GROUP BY c.grade
ORDER BY SUM(o.total_amount) DESC;

-- [6-10] 사라진 고객은 누구인가? — 주문 20건이 전부 취소된 고객 3명
SELECT
    c.customer_id, c.name, c.grade,
    COUNT(*)                            AS 총주문,
    SUM(o.status = 'CANCELLED')         AS 취소건,
    SUM(o.status <> 'CANCELLED')        AS 정상건
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.customer_id, c.name, c.grade
HAVING SUM(o.status <> 'CANCELLED') = 0;

-- [6-10] SUM(조건) 관용구 — 조건부 집계 (피벗의 기초)
--   불리언은 1/0 이므로 SUM(조건) = "조건을 만족하는 행 수"
SELECT
    c.grade                                        AS 등급,
    COUNT(*)                                       AS 전체주문,
    SUM(o.status = 'CANCELLED')                    AS 취소,
    SUM(o.status = 'DELIVERED')                    AS 배송완료,
    ROUND(AVG(o.status = 'CANCELLED') * 100, 1)    AS 취소율_pct
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.grade
ORDER BY 취소율_pct DESC;
