-- =====================================================================
-- Step 04 — SELECT 기본 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < practice.sql
--   또는 mysql 접속 후:  source practice.sql
-- =====================================================================
USE shop;

-- [4-1] 테이블 없이 SELECT — 계산기처럼 쓰기
SELECT 1 + 1;

-- [4-1] 여러 표현식을 한 번에
SELECT 1 + 1, NOW(), VERSION(), 'hello';

-- [4-2] FROM: 테이블의 모든 컬럼 (* 는 학습/탐색용)
SELECT * FROM categories;

-- [4-2] 필요한 컬럼만 명시 (실무의 기본)
SELECT customer_id, name, grade, city FROM customers;

-- [4-3] 컬럼 별칭 (AS). 공백/한글 별칭은 백틱으로 감싼다
SELECT
    product_id AS id,
    name       AS 상품명,
    price      AS 판매가,
    price - cost AS `마진(원)`
FROM products;

-- [4-3] AS 생략 가능 (권장하지 않음 — 콤마 빠뜨리면 별칭이 되어버린다)
SELECT product_id id, name 상품명 FROM products;

-- [4-3] 콤마를 빠뜨린 사고: name 이 price 의 별칭이 되어 컬럼이 사라진다
SELECT product_id, name price FROM products;

-- [4-4] 주석 3종
SELECT 1 AS a;  -- 한 줄 주석 (-- 뒤에 반드시 공백)
# MySQL 전용 한 줄 주석
/* 여러 줄
   주석 */
SELECT 2 AS b;

-- [4-5] WHERE: 행 걸러내기
SELECT product_id, name, price, stock
FROM products
WHERE price >= 1000000;

-- [4-5] WHERE + 여러 조건
SELECT customer_id, name, grade, city, points
FROM customers
WHERE grade = 'VIP' AND city = '서울';

-- [4-6] ORDER BY: 정렬 (기본 ASC)
SELECT product_id, name, price
FROM products
ORDER BY price DESC;

-- [4-6] 다중 정렬 키
SELECT customer_id, name, grade, points
FROM customers
ORDER BY grade DESC, points DESC;

-- [4-6] 별칭으로 정렬 — ORDER BY 에서는 허용된다
SELECT product_id, name, price - cost AS margin
FROM products
ORDER BY margin DESC
LIMIT 5;

-- [4-6] NULL 의 정렬 위치: ASC 면 맨 앞, DESC 면 맨 뒤
SELECT customer_id, name, phone
FROM customers
ORDER BY phone ASC
LIMIT 6;

-- [4-6] NULL 을 항상 뒤로 보내기
SELECT customer_id, name, phone
FROM customers
ORDER BY (phone IS NULL) ASC, phone ASC
LIMIT 6;

-- [4-7] LIMIT: 상위 N건
SELECT product_id, name, price
FROM products
ORDER BY price DESC
LIMIT 5;

-- [4-7] LIMIT offset, count — 2페이지(6~10위)
SELECT product_id, name, price
FROM products
ORDER BY price DESC
LIMIT 5 OFFSET 5;

-- [4-7] ORDER BY 없는 LIMIT 은 순서를 보장하지 않는다 (나쁜 예)
SELECT order_id, customer_id, total_amount FROM orders LIMIT 3;

-- [4-8] DISTINCT: 중복 제거
SELECT DISTINCT city FROM customers;

-- [4-8] DISTINCT 는 SELECT 목록 "전체"에 걸린다 (조합의 유일성)
SELECT DISTINCT grade, city FROM customers ORDER BY grade, city;

-- [4-8] DISTINCT 는 함수가 아니다 — 이건 (grade, city) 조합의 DISTINCT 다
SELECT DISTINCT grade, city FROM customers WHERE city = '서울';

-- [4-9] 논리적 실행 순서 확인 ①: WHERE 에서는 SELECT 별칭을 못 쓴다 (에러)
-- SELECT product_id, price - cost AS margin FROM products WHERE margin > 300000;

-- [4-9] 해결책 ①: 식을 그대로 반복
SELECT product_id, name, price - cost AS margin
FROM products
WHERE price - cost > 300000
ORDER BY margin DESC;

-- [4-9] 해결책 ②: 파생 테이블(서브쿼리)로 한 번 감싸기
SELECT *
FROM (
    SELECT product_id, name, price - cost AS margin
    FROM products
) AS t
WHERE t.margin > 300000
ORDER BY t.margin DESC;

-- [4-9] 테이블 별칭 — 조인 전에도 습관을 들여두면 좋다
SELECT p.product_id, p.name, p.price
FROM products AS p
WHERE p.status = 'ON_SALE'
ORDER BY p.price
LIMIT 3;

-- [4-10] 종합: "재고가 있는 판매중 상품 중 마진율 상위 5개"
SELECT
    p.product_id                                  AS id,
    p.name                                        AS 상품명,
    p.price                                       AS 판매가,
    p.cost                                        AS 원가,
    ROUND((p.price - p.cost) / p.price * 100, 1)  AS 마진율_pct
FROM products AS p
WHERE p.status = 'ON_SALE'
  AND p.stock > 0
ORDER BY 마진율_pct DESC, p.price DESC
LIMIT 5;
