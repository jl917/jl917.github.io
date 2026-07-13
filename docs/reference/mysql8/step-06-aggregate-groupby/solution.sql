-- =====================================================================
-- Step 06 — 집계함수와 GROUP BY : solution.sql (정답 + 해설)
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < solution.sql
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] 카테고리별 상품 수 + 평균가
--   HAVING COUNT(*) >= 3 은 집계 결과 조건이므로 HAVING 이 맞습니다.
--   결과: 노트북(21) 평균 149만원이 1위, 스마트폰(22) 2위 ...
-- ---------------------------------------------------------------------
SELECT
    category_id,
    COUNT(*)          AS 상품수,
    ROUND(AVG(price)) AS 평균가
FROM products
GROUP BY category_id
HAVING COUNT(*) >= 3
ORDER BY 평균가 DESC;

-- ---------------------------------------------------------------------
-- [정답 2] 결제 방법별 집계
--   payments 테이블을 씁니다. status = 'DONE' 은 개별 행 조건이므로 WHERE.
--   결과: POINT 2.0억 > CARD 1.8억 > BANK > MOBILE
-- ---------------------------------------------------------------------
SELECT
    method               AS 결제방법,
    COUNT(*)             AS 건수,
    FORMAT(SUM(amount), 0) AS 총액
FROM payments
WHERE status = 'DONE'
GROUP BY method
ORDER BY SUM(amount) DESC;

-- ---------------------------------------------------------------------
-- [정답 3] 등급별 고객 수 + 총계 (ROLLUP + GROUPING)
--   GROUPING(grade) = 1 인 행이 ROLLUP 이 만든 총계 행입니다.
--   IF 로 그 행의 라벨을 '전체' 로 바꿉니다.
--   결과: BRONZE 10 / SILVER 7 / GOLD 8 / VIP 5 / 전체 30
--
--   [주의] grade 는 ENUM 이라 정의된 순서(BRONZE..VIP)대로 그룹이 나옵니다.
-- ---------------------------------------------------------------------
SELECT
    IF(GROUPING(grade) = 1, '전체', grade) AS 등급,
    COUNT(*)                                AS 고객수
FROM customers
GROUP BY grade WITH ROLLUP;

-- ---------------------------------------------------------------------
-- [정답 4] COUNT(*) vs COUNT(col)
--   결과: COUNT(*) = 30, COUNT(phone) = 27.
--   phone 이 NULL 인 고객이 3명(윤대현/남규리/심준호)이기 때문입니다.
--   COUNT(*) 는 행을 무조건 세지만, COUNT(phone) 은 phone 이 NULL 인 행을
--   건너뜁니다. "전화번호를 등록한 고객 수" 를 원하면 COUNT(phone) 이 정답.
-- ---------------------------------------------------------------------
SELECT
    COUNT(*)     AS 전체행,
    COUNT(phone) AS 전화번호_있는_행,
    COUNT(*) - COUNT(phone) AS 전화번호_NULL_수
FROM customers;

-- ---------------------------------------------------------------------
-- [정답 5] 문제 있는 상품 찾기
--   HAVING 에 집계 조건이 두 개(COUNT, AVG)입니다. 둘 다 HAVING 에.
--   결과: 평점 2.0 짜리 상품 4개 (플리츠 롱스커트, 보급형 노트북 15 등)
--   ※ 시드 데이터 특성상 한 상품의 후기 평점이 균일하게 생성됩니다.
-- ---------------------------------------------------------------------
SELECT
    p.product_id            AS id,
    p.name                  AS 상품명,
    COUNT(*)                AS 후기수,
    ROUND(AVG(r.rating), 2) AS 평균평점
FROM reviews r
JOIN products p ON p.product_id = r.product_id
GROUP BY p.product_id, p.name
HAVING COUNT(*) >= 3
   AND AVG(r.rating) < 4.0
ORDER BY 평균평점 ASC, 후기수 DESC;

-- ---------------------------------------------------------------------
-- [정답 6] 고객별 상태 분포 (SUM(조건) 관용구)
--   불리언(1/0)을 SUM 하면 그 상태의 건수가 됩니다.
--   GROUP BY 에 customer_id(PK 는 아니지만 orders 의 그룹 키)를 씁니다.
--   결과: 상위 5명 모두 20건씩 주문. 흥미롭게도 고객마다 상태가 한 종류로
--        몰려 있습니다(시드가 customer_id 기준 결정론적으로 생성한 결과).
-- ---------------------------------------------------------------------
SELECT
    customer_id,
    COUNT(*)                     AS 전체주문,
    SUM(status = 'PAID')         AS PAID수,
    SUM(status = 'SHIPPED')      AS SHIPPED수,
    SUM(status = 'DELIVERED')    AS DELIVERED수,
    SUM(status = 'CANCELLED')    AS CANCELLED수
FROM orders
GROUP BY customer_id
ORDER BY 전체주문 DESC, customer_id ASC
LIMIT 5;

-- ---------------------------------------------------------------------
-- [정답 7] 카테고리별 상품명 목록 (GROUP_CONCAT)
--   GROUP_CONCAT 안에 ORDER BY price DESC 와 SEPARATOR 를 넣습니다.
--   목록이 길어질 수 있으면 group_concat_max_len 을 먼저 늘려두는 습관을.
-- ---------------------------------------------------------------------
SET SESSION group_concat_max_len = 100000;
SELECT
    category_id,
    GROUP_CONCAT(name ORDER BY price DESC SEPARATOR ' | ') AS 상품목록
FROM products
GROUP BY category_id
ORDER BY category_id;

-- ---------------------------------------------------------------------
-- [정답 8] (함정 확인) 사라지는 그룹
--   결과: BRONZE 2명, GOLD 2명 — 딱 2개 등급, 4명만 나옵니다!
--   SILVER 와 VIP 는 아예 안 보입니다.
--
--   [왜?] WHERE o.total_amount > 5000000 은 조인 결과의 개별 행을 거릅니다.
--   500만원 넘는 주문이 한 건도 없는 고객은 그룹핑 단계에 도달할 행이
--   없으므로 그룹 자체가 사라집니다. 그런 고객만 있는 SILVER/VIP 등급은
--   통째로 결과에서 빠집니다.
--
--   → "전체 등급을 다 보여주되 조건에 맞는 건수만 세고 싶다" 면
--     WHERE 로 거르지 말고 조건부 집계를 써야 합니다 (아래 대안).
--     이 "필터 위치" 문제의 완전한 해법은 Step 07(LEFT JOIN)에서 다룹니다.
-- ---------------------------------------------------------------------
SELECT
    c.grade                       AS 등급,
    COUNT(DISTINCT c.customer_id) AS 고객수
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
WHERE o.total_amount > 5000000
GROUP BY c.grade;

-- (대안) WHERE 대신 조건부 집계 — 4개 등급이 모두 유지된다
SELECT
    c.grade AS 등급,
    COUNT(DISTINCT IF(o.total_amount > 5000000, c.customer_id, NULL)) AS 고액고객수
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.grade;
