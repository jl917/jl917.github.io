-- =====================================================================
-- Step 05 — 연산자와 조건 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < practice.sql
-- =====================================================================
USE shop;

-- [5-1] 비교 연산자 6종
SELECT product_id, name, price
FROM products
WHERE price > 1000000;

-- [5-1] 같지 않다: <> (표준) 와 != (MySQL 도 지원) 는 동일
SELECT COUNT(*) AS `<>_결과` FROM products WHERE status <> 'ON_SALE';
SELECT COUNT(*) AS `!=_결과` FROM products WHERE status != 'ON_SALE';

-- [5-1] 비교 결과 자체를 SELECT 해보기 — 1(TRUE) / 0(FALSE) / NULL(UNKNOWN)
SELECT 1 = 1 AS eq, 1 = 2 AS ne, 1 = NULL AS with_null, NULL = NULL AS null_null;

-- [5-2] AND / OR / NOT 과 우선순위
-- AND 가 OR 보다 먼저 묶인다 → 아래 두 쿼리는 결과가 다르다
SELECT COUNT(*) AS 괄호없음
FROM products
WHERE category_id = 21 OR category_id = 22 AND price < 500000;

SELECT COUNT(*) AS 괄호있음
FROM products
WHERE (category_id = 21 OR category_id = 22) AND price < 500000;

-- [5-2] 괄호 없이 쓴 쿼리가 실제로 무엇을 뽑았는지 확인
SELECT product_id, category_id, name, price
FROM products
WHERE category_id = 21 OR category_id = 22 AND price < 500000
ORDER BY category_id, product_id;

-- [5-3] BETWEEN — 양 끝 포함 (>= AND <=)
SELECT product_id, name, price
FROM products
WHERE price BETWEEN 100000 AND 200000
ORDER BY price;

-- [5-3] NOT BETWEEN
SELECT COUNT(*) AS cnt
FROM products
WHERE price NOT BETWEEN 100000 AND 200000;

-- [5-3] 날짜 BETWEEN 의 함정: DATETIME 컬럼에 날짜만 주면 00:00:00 으로 해석된다
--   '2025-06-30' 은 '2025-06-30 00:00:00' 이 되므로,
--   6월 30일 00:00:01 이후의 주문은 전부 누락된다!
SELECT COUNT(*) AS 잘못된_6월_주문수
FROM orders
WHERE order_date BETWEEN '2025-06-01' AND '2025-06-30';

-- [5-3] 올바른 방법: 반열림 구간 [시작, 다음시작)
SELECT COUNT(*) AS 올바른_6월_주문수
FROM orders
WHERE order_date >= '2025-06-01'
  AND order_date <  '2025-07-01';

-- [5-3] 누락된 행을 직접 확인 — 6월 30일 04:16 의 주문 1건이 통째로 사라졌다
SELECT order_id, order_date, total_amount
FROM orders
WHERE order_date >= '2025-06-30' AND order_date < '2025-07-01'
ORDER BY order_date;

-- [5-4] IN — OR 의 축약
SELECT product_id, category_id, name
FROM products
WHERE category_id IN (21, 22)
ORDER BY product_id;

-- [5-4] IN 서브쿼리 — "600만원 넘는 주문이 배송된 도시" 에 사는 고객
SELECT customer_id, name, grade, city
FROM customers
WHERE city IN (SELECT shipping_city FROM orders WHERE total_amount > 6000000)
ORDER BY customer_id
LIMIT 5;

-- [5-5] NULL 3값 논리 — 핵심
-- NULL 은 "값이 없음"이 아니라 "값을 모름". 모르는 값끼리는 비교할 수 없다.
SELECT
    NULL = NULL      AS `NULL = NULL`,
    NULL <> NULL     AS `NULL <> NULL`,
    NULL = 0         AS `NULL = 0`,
    NULL + 1         AS `NULL + 1`,
    CONCAT('a', NULL) AS `CONCAT(a,NULL)`;

-- [5-5] 그래서 phone = NULL 은 아무 행도 못 찾는다
SELECT COUNT(*) AS `phone = NULL` FROM customers WHERE phone = NULL;

-- [5-5] IS NULL / IS NOT NULL 을 써야 한다
SELECT customer_id, name, phone
FROM customers
WHERE phone IS NULL;

SELECT COUNT(*) AS `phone IS NOT NULL` FROM customers WHERE phone IS NOT NULL;

-- [5-5] WHERE 는 TRUE 만 남긴다 → FALSE 도 UNKNOWN 도 버려진다
--   "phone 이 010-1000-0001 이 아닌 고객" 을 찾으면 NULL 인 3명이 빠진다
SELECT COUNT(*) AS `<> 로 센 결과`
FROM customers
WHERE phone <> '010-1000-0001';

-- [5-5] NULL 도 포함하려면 명시적으로 OR IS NULL
SELECT COUNT(*) AS `NULL 포함`
FROM customers
WHERE phone <> '010-1000-0001' OR phone IS NULL;

-- [5-6] <=> NULL-safe equal : NULL <=> NULL 은 TRUE
SELECT
    NULL <=> NULL AS `NULL <=> NULL`,
    NULL <=> 1    AS `NULL <=> 1`,
    1 <=> 1       AS `1 <=> 1`;

-- [5-6] <=> 로 "phone 이 NULL 인 고객" 찾기 (IS NULL 과 동일)
SELECT customer_id, name, phone
FROM customers
WHERE phone <=> NULL;

-- [5-6] 파라미터가 NULL 일 수도 있는 검색 — <=> 한 방으로 처리
--   (애플리케이션에서 ? 에 값을 바인딩. NULL 이면 NULL 인 행을 찾는다)
SELECT customer_id, name, phone
FROM customers
WHERE phone <=> NULL
LIMIT 3;

-- [5-7] NOT IN + NULL 의 최악의 함정
--   서브쿼리 결과에 NULL 이 하나라도 있으면 NOT IN 은 항상 빈 결과!
SELECT COUNT(*) AS `NOT IN 결과`
FROM categories
WHERE category_id NOT IN (SELECT parent_id FROM categories);

-- [5-7] 왜? NOT IN (1,2,NULL) 은 "1도 아니고 2도 아니고 NULL도 아니다"
--   → NULL 과의 비교가 UNKNOWN 이라 전체가 절대 TRUE 가 못 된다
SELECT
    3 NOT IN (1, 2)       AS `3 NOT IN (1,2)`,
    3 NOT IN (1, 2, NULL) AS `3 NOT IN (1,2,NULL)`;

-- [5-7] 해결책 ①: 서브쿼리에서 NULL 을 제거
SELECT category_id, name
FROM categories
WHERE category_id NOT IN (
    SELECT parent_id FROM categories WHERE parent_id IS NOT NULL
)
ORDER BY category_id
LIMIT 8;

-- [5-7] 해결책 ②: NOT EXISTS (NULL 에 안전하고 보통 더 빠르다)
SELECT c.category_id, c.name
FROM categories c
WHERE NOT EXISTS (
    SELECT 1 FROM categories x WHERE x.parent_id = c.category_id
)
ORDER BY c.category_id
LIMIT 8;

-- [5-8] LIKE — % (0자 이상) / _ (정확히 1자)
SELECT product_id, name FROM products WHERE name LIKE '스마트폰%';
SELECT product_id, name FROM products WHERE name LIKE '%노트북%';
SELECT customer_id, email FROM customers WHERE email LIKE '_im%' ORDER BY customer_id;

-- [5-8] LIKE 는 콜레이션을 따른다. utf8mb4_0900_ai_ci 는 대소문자 구분 안 함
SELECT product_id, name FROM products WHERE name LIKE '%mysql%';

-- [5-8] 대소문자를 구분하고 싶다면 BINARY 또는 명시적 콜레이션
SELECT product_id, name FROM products WHERE name LIKE BINARY '%mysql%';
SELECT product_id, name FROM products WHERE name LIKE BINARY '%MySQL%';

-- [5-8] % 자체를 찾고 싶을 때 — ESCAPE
SELECT product_id, name FROM products WHERE name LIKE '%\%%';
SELECT product_id, name FROM products WHERE name LIKE '%!%%' ESCAPE '!';

-- [5-9] REGEXP / RLIKE — 정규식
--   MySQL 8.0 부터 정규식 엔진이 ICU 로 교체되었습니다 (기존 Henry Spencer).
SELECT product_id, name
FROM products
WHERE name REGEXP '노트북|스마트폰'
ORDER BY product_id;

-- [5-9] 앵커(^ $) 와 문자 클래스
SELECT product_id, name FROM products WHERE name REGEXP '^[0-9]';   -- 숫자로 시작
SELECT product_id, name FROM products WHERE name REGEXP '[0-9]+GB$'; -- GB 로 끝

-- [5-9] MySQL 8 신규 정규식 함수들
SELECT
    REGEXP_LIKE('kim.minsu@example.com', '^[a-z.]+@example\\.com$') AS is_valid,
    REGEXP_SUBSTR('스마트폰 X20 Pro 512GB', '[0-9]+GB')             AS storage,
    REGEXP_REPLACE('010-1000-0001', '[0-9]{4}$', '****')            AS masked,
    REGEXP_INSTR('실전 MySQL 8', 'MySQL')                           AS pos;

-- [5-9] REGEXP_SUBSTR 로 상품명에서 용량 추출
SELECT product_id, name,
       REGEXP_SUBSTR(name, '[0-9]+(GB|TB)') AS capacity
FROM products
WHERE name REGEXP '[0-9]+(GB|TB)'
ORDER BY product_id;

-- [5-9] REGEXP 도 콜레이션을 따라 대소문자를 무시한다.
--   MySQL 8 에서는 BINARY 'MySQL' REGEXP 'mysql' 이 에러가 난다 (ICU 엔진).
--     ERROR 3995: Character set 'binary' cannot be used in conjunction with
--                 'utf8mb4_0900_ai_ci' in call to regexp_like.
--   대신 COLLATE 를 쓰거나 REGEXP_LIKE 의 match_type 'c' 를 쓴다.
SELECT
    'MySQL' REGEXP 'mysql'                            AS ci,
    'MySQL' COLLATE utf8mb4_0900_as_cs REGEXP 'mysql' AS cs_collate,
    REGEXP_LIKE('MySQL', 'mysql', 'c')                AS cs_flag,
    REGEXP_LIKE('MySQL', 'mysql', 'i')                AS ci_flag;

-- [5-10] NULL 다루는 함수들
SELECT
    customer_id,
    name,
    phone,
    IFNULL(phone, '(미등록)')            AS ifnull_ex,
    COALESCE(phone, '(미등록)')          AS coalesce_ex,
    NULLIF(points, 0)                    AS points_0은_null로
FROM customers
WHERE customer_id IN (1, 7, 14, 17)
ORDER BY customer_id;

-- [5-11] 페이징 ① OFFSET 방식 (직관적이지만 뒤로 갈수록 느리다)
SELECT order_id, order_date, total_amount
FROM orders
ORDER BY order_date DESC, order_id DESC
LIMIT 5 OFFSET 0;

SELECT order_id, order_date, total_amount
FROM orders
ORDER BY order_date DESC, order_id DESC
LIMIT 5 OFFSET 5;

-- [5-11] 깊은 OFFSET 은 앞의 595건을 전부 읽고 버린다
EXPLAIN SELECT order_id, order_date FROM orders
ORDER BY order_date DESC, order_id DESC LIMIT 5 OFFSET 595;

-- [5-12] 페이징 ② 커서(keyset) 페이징
--   1페이지 마지막 행의 (order_date, order_id) 를 커서로 삼는다.
--   위 1페이지 마지막 행은 (2025-12-25 20:44:00, 572) 였다.
--   → 결과는 OFFSET 5 와 완전히 동일하지만, 앞의 5건을 읽지 않는다.
SELECT order_id, order_date, total_amount
FROM orders
WHERE (order_date, order_id) < ('2025-12-25 20:44:00', 572)
ORDER BY order_date DESC, order_id DESC
LIMIT 5;

-- [5-12] 행 생성자(row constructor)를 안 쓰고 풀어 쓰면 이렇게 된다 (동일한 결과)
SELECT order_id, order_date, total_amount
FROM orders
WHERE order_date < '2025-12-25 20:44:00'
   OR (order_date = '2025-12-25 20:44:00' AND order_id < 572)
ORDER BY order_date DESC, order_id DESC
LIMIT 5;

-- [5-13] 종합: "2025년 하반기 / 취소 아님 / 서울·인천 배송 / 100만원 이상" 주문
SELECT
    o.order_id,
    o.order_date,
    o.status,
    o.shipping_city,
    o.total_amount
FROM orders o
WHERE o.order_date >= '2025-07-01'
  AND o.order_date <  '2026-01-01'
  AND o.status <> 'CANCELLED'
  AND o.shipping_city IN ('서울', '인천')
  AND o.total_amount >= 1000000
ORDER BY o.total_amount DESC, o.order_id
LIMIT 8;
