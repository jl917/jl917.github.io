-- =====================================================================
-- Step 10 — 집합 연산 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
-- * 이 파일은 SELECT 만 합니다. 원본 데이터를 변경하지 않습니다.
-- * INTERSECT / EXCEPT 는 MySQL 8.0.31 이상에서만 동작합니다.
-- =====================================================================
USE shop;

-- [10-0] 내 서버 버전 확인 (8.0.31 미만이면 INTERSECT/EXCEPT 는 문법 에러)
SELECT VERSION() AS version;

-- [10-1] UNION : 두 결과를 세로로 붙이고 중복 제거
SELECT city FROM customers WHERE grade = 'VIP'
UNION
SELECT shipping_city FROM orders WHERE total_amount > 6000000;

-- [10-2] UNION ALL : 중복을 제거하지 않는다 (그래서 빠르다)
SELECT city FROM customers WHERE grade = 'VIP'
UNION ALL
SELECT shipping_city FROM orders WHERE total_amount > 6000000;

-- [10-3] 중복 제거 여부를 개수로 확인
SELECT 'UNION' AS op, COUNT(*) AS cnt FROM (
    SELECT city FROM customers WHERE grade = 'VIP'
    UNION
    SELECT shipping_city FROM orders WHERE total_amount > 6000000
) x
UNION ALL
SELECT 'UNION ALL', COUNT(*) FROM (
    SELECT city FROM customers WHERE grade = 'VIP'
    UNION ALL
    SELECT shipping_city FROM orders WHERE total_amount > 6000000
) y;

-- [10-4] 컬럼 개수가 다르면 에러
-- SELECT customer_id, name FROM customers
-- UNION
-- SELECT product_id FROM products;
-- ERROR 1222 (21000): The used SELECT statements have a different number of columns

-- [10-5] 컬럼 이름은 첫 번째 SELECT 것을 따른다
SELECT customer_id AS id, name AS label FROM customers WHERE customer_id <= 2
UNION ALL
SELECT product_id, name FROM products WHERE product_id <= 2;

-- [10-6] 타입이 달라도 에러가 아니다 — 조용히 합쳐진다 (위험!)
SELECT customer_id AS v FROM customers WHERE customer_id = 1
UNION ALL
SELECT email FROM customers WHERE customer_id = 1;

-- [10-7] 결과 컬럼의 타입 확인
CREATE TEMPORARY TABLE s10_tmp_type AS
SELECT customer_id AS v FROM customers WHERE customer_id = 1
UNION ALL
SELECT email FROM customers WHERE customer_id = 1;
DESC s10_tmp_type;
DROP TEMPORARY TABLE s10_tmp_type;

-- [10-8] ORDER BY 는 맨 끝에 딱 한 번, 전체 결과에 적용된다
SELECT name, price FROM products WHERE category_id = 21
UNION ALL
SELECT name, price FROM products WHERE category_id = 22
ORDER BY price DESC;

-- [10-9] 개별 SELECT 에 ORDER BY/LIMIT 를 걸려면 괄호로 감싼다
(SELECT name, price FROM products WHERE category_id = 21 ORDER BY price DESC LIMIT 2)
UNION ALL
(SELECT name, price FROM products WHERE category_id = 22 ORDER BY price DESC LIMIT 2);

-- [10-10] 전체에 ORDER BY + LIMIT
(SELECT name, price, '노트북' AS kind FROM products WHERE category_id = 21)
UNION ALL
(SELECT name, price, '스마트폰' FROM products WHERE category_id = 22)
ORDER BY price DESC
LIMIT 3;

-- [10-11] ORDER BY 에는 첫 SELECT 의 "결과 컬럼명"만 쓸 수 있다
SELECT p.name AS pname, p.price AS pprice FROM products p WHERE p.category_id = 21
UNION ALL
SELECT p.name, p.price FROM products p WHERE p.category_id = 22
ORDER BY pprice DESC
LIMIT 3;

-- [10-12] INTERSECT (8.0.31+) : 양쪽에 모두 있는 것
--         "10만원 이상 상품" ∩ "후기가 달린 상품"
SELECT product_id FROM products WHERE price >= 100000
INTERSECT
SELECT product_id FROM reviews
ORDER BY product_id;

-- [10-13] INTERSECT 를 쓰기 전 방식 (IN / EXISTS) — 결과는 같다
SELECT DISTINCT p.product_id
FROM products p
WHERE p.price >= 100000
  AND p.product_id IN (SELECT product_id FROM reviews)
ORDER BY p.product_id;

-- [10-14] EXCEPT (8.0.31+) : 왼쪽에는 있고 오른쪽에는 없는 것
SELECT product_id FROM products
EXCEPT
SELECT product_id FROM reviews
ORDER BY product_id
LIMIT 5;

-- [10-15] EXCEPT 개수 확인
SELECT COUNT(*) AS never_reviewed FROM (
    SELECT product_id FROM products
    EXCEPT
    SELECT product_id FROM reviews
) x;

-- [10-16] EXCEPT 대신 쓰던 방식 (NOT EXISTS)
SELECT COUNT(*) AS never_reviewed
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.product_id = p.product_id);

-- [10-17] INTERSECT ALL / EXCEPT ALL (8.0.31+) : 중복을 남긴다
SELECT n FROM (SELECT 1 AS n UNION ALL SELECT 1 UNION ALL SELECT 2) a
INTERSECT ALL
SELECT n FROM (SELECT 1 AS n UNION ALL SELECT 1 UNION ALL SELECT 3) b;

SELECT n FROM (SELECT 1 AS n UNION ALL SELECT 1 UNION ALL SELECT 2) a
EXCEPT ALL
SELECT n FROM (SELECT 1 AS n) b;

-- [10-18] 연산 우선순위 : INTERSECT 가 UNION/EXCEPT 보다 먼저 평가된다
--         괄호로 명시하는 습관을 들이자
(SELECT 1 AS n UNION ALL SELECT 2)
UNION
(SELECT 2 INTERSECT SELECT 3);

-- [10-19] UNION 이 느린 이유 : 중복 제거 = 임시 테이블 + 정렬/해시
EXPLAIN FORMAT=TREE
SELECT city FROM customers
UNION
SELECT shipping_city FROM orders;

-- [10-20] UNION ALL 은 그냥 이어붙이기만 한다
EXPLAIN FORMAT=TREE
SELECT city FROM customers
UNION ALL
SELECT shipping_city FROM orders;

-- [10-21] FULL OUTER JOIN 흉내내기 (MySQL 에는 FULL OUTER JOIN 이 없다)
--         LEFT JOIN UNION RIGHT JOIN
SELECT c.category_id, c.name AS cat_name, p.product_id, p.name AS product_name
FROM categories c
LEFT JOIN products p ON p.category_id = c.category_id
UNION
SELECT c.category_id, c.name, p.product_id, p.name
FROM categories c
RIGHT JOIN products p ON p.category_id = c.category_id
ORDER BY category_id, product_id
LIMIT 8;

-- [10-22] FULL OUTER JOIN 의 "짝 없는 행"만 보기 (양쪽 안티조인)
SELECT c.category_id, c.name AS cat_name, NULL AS product_id, NULL AS product_name
FROM categories c
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.category_id = c.category_id)
UNION ALL
SELECT NULL, NULL, p.product_id, p.name
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.category_id = p.category_id)
ORDER BY category_id;

-- [10-23] 실무 패턴 : 서로 다른 소스를 하나의 리포트로 (UNION ALL + 구분 컬럼)
SELECT 'ORDER'   AS src, order_id   AS id, order_date AS ts, total_amount AS amount
FROM orders WHERE order_date >= '2025-12-01'
UNION ALL
SELECT 'PAYMENT', payment_id, paid_at, amount
FROM payments WHERE paid_at >= '2025-12-01'
ORDER BY ts
LIMIT 8;

-- [10-24] UNION ALL + 소계/합계 행 만들기
SELECT status, COUNT(*) AS cnt, SUM(total_amount) AS amount
FROM orders
GROUP BY status
UNION ALL
SELECT '__TOTAL__', COUNT(*), SUM(total_amount)
FROM orders
ORDER BY cnt DESC;
