-- =====================================================================
-- 부록 A — NULL 완전 정복 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
-- * 공용 테이블은 읽기만 합니다.
-- * 데이터 변경이 필요한 예제(A-8, A-12)만 s26_ 사본을 만들고 파일 끝에서 지웁니다.
-- =====================================================================
USE shop;

-- =====================================================================
-- A-1. NULL 은 값이 아니라 상태다
-- =====================================================================

-- [A-1] NULL / 0 / '' 는 완전히 다르다
SELECT
    NULL = NULL   AS `NULL = NULL`,
    NULL <> NULL  AS `NULL <> NULL`,
    NULL = 0      AS `NULL = 0`,
    '' = NULL     AS `'' = NULL`,
    '' IS NULL    AS `'' IS NULL`,
    LENGTH('')    AS `LENGTH('')`;

-- [A-1] NULL 은 전염된다. 단 CONCAT_WS 는 NULL 인자를 건너뛴다
SELECT
    NULL + 1                    AS `NULL + 1`,
    1 / NULL                    AS `1 / NULL`,
    CONCAT('a', NULL)           AS `CONCAT`,
    CONCAT_WS('-','a',NULL,'b') AS `CONCAT_WS`,
    LENGTH(NULL)                AS `LENGTH(NULL)`,
    UPPER(NULL)                 AS `UPPER(NULL)`;

-- =====================================================================
-- A-2. 3값 논리 — TRUE / FALSE / UNKNOWN
-- =====================================================================

-- [A-2] AND 는 FALSE 가 흡수한다
SELECT TRUE AND NULL AS `T AND ?`, FALSE AND NULL AS `F AND ?`, NULL AND NULL AS `? AND ?`;

-- [A-2] OR 는 TRUE 가 흡수한다
SELECT TRUE OR  NULL AS `T OR  ?`, FALSE OR  NULL AS `F OR  ?`, NULL OR  NULL AS `? OR  ?`;

-- [A-2] 모르는 것의 반대는 여전히 모르는 것
SELECT NOT NULL AS `NOT ?`;

-- =====================================================================
-- A-4. NULL 을 찾는 법 — IS NULL 과 <=>
-- =====================================================================

-- [A-4] = NULL 은 언제나 0건. IS NULL / <=> 만 찾아낸다
SELECT
    (SELECT COUNT(*) FROM customers WHERE phone =   NULL) AS `phone = NULL`,
    (SELECT COUNT(*) FROM customers WHERE phone IS  NULL) AS `phone IS NULL`,
    (SELECT COUNT(*) FROM customers WHERE phone <=> NULL) AS `phone <=> NULL`;

-- [A-4] 부정 조건에서 NULL 이 새어나간다 (26 vs 29)
SELECT
    (SELECT COUNT(*) FROM customers WHERE phone <> '010-1000-0001')                       AS `<> 만`,
    (SELECT COUNT(*) FROM customers WHERE phone <> '010-1000-0001' OR phone IS NULL)      AS `NULL 포함`;

-- =====================================================================
-- A-5. NOT IN + NULL — 최대 함정
-- =====================================================================

-- [A-5] IN 은 TRUE 가 흡수해서 살아남고, NOT IN 은 확정을 못 한다
SELECT
    3 IN (1,2,NULL)     AS `3 IN (1,2,?)`,
    1 IN (1,2,NULL)     AS `1 IN (1,2,?)`,
    3 NOT IN (1,2,NULL) AS `3 NOT IN (1,2,?)`,
    1 NOT IN (1,2,NULL) AS `1 NOT IN (1,2,?)`;

-- [A-5] employees.manager_id 에 NULL(CEO) 이 있어서 NOT IN 만 0 이 나온다. 정답은 10
SELECT
    (SELECT COUNT(*) FROM employees e
      WHERE e.employee_id NOT IN (SELECT manager_id FROM employees))                       AS `NOT IN (버그)`,
    (SELECT COUNT(*) FROM employees e
      WHERE e.employee_id NOT IN (SELECT manager_id FROM employees WHERE manager_id IS NOT NULL)) AS `NULL 제거`,
    (SELECT COUNT(*) FROM employees e
      WHERE NOT EXISTS (SELECT 1 FROM employees m WHERE m.manager_id = e.employee_id))     AS `NOT EXISTS`,
    (SELECT COUNT(*) FROM employees e
      LEFT JOIN employees m ON m.manager_id = e.employee_id
      WHERE m.employee_id IS NULL)                                                         AS `LEFT JOIN IS NULL`;

-- [A-5] 빈 서브쿼리는 UNKNOWN 이 아니라 확실한 FALSE / TRUE 다
SELECT 5 IN     (SELECT customer_id FROM customers WHERE 1=0) AS in_empty,
       5 NOT IN (SELECT customer_id FROM customers WHERE 1=0) AS notin_empty;

-- =====================================================================
-- A-6. 집계함수와 NULL
-- =====================================================================

-- [A-6] COUNT 3형제 : COUNT(*) 만 NULL 을 센다
SELECT
    COUNT(*)              AS `COUNT(*)`,
    COUNT(phone)          AS `COUNT(phone)`,
    COUNT(DISTINCT phone) AS `COUNT(DISTINCT phone)`,
    MIN(phone)            AS `MIN(phone)`
FROM customers;

-- [A-6] AVG 의 분모가 달라진다 (5959 vs 6621)
SELECT
    AVG(points)            AS `AVG(0 포함)`,
    AVG(NULLIF(points, 0)) AS `AVG(0을 NULL로)`,
    SUM(points) / COUNT(*) AS `SUM / COUNT(*)`
FROM customers;

-- [A-6] 빈 집합 : SUM/AVG/MAX 는 NULL, COUNT 는 0
SELECT SUM(points) AS s, AVG(points) AS a, MAX(points) AS m,
       COUNT(points) AS cp, COUNT(*) AS ca
FROM customers WHERE 1 = 0;

-- [A-6] 그래서 화면 표시용 합계는 COALESCE 로 마감한다
SELECT COALESCE(SUM(points), 0) AS `COALESCE 처리` FROM customers WHERE 1 = 0;

-- =====================================================================
-- A-7. GROUP BY / DISTINCT / UNION 은 NULL 을 "같다"고 본다
-- =====================================================================

-- [A-7] NULL 5건이 흩어지지 않고 한 그룹으로 묶인다
SELECT parent_id, COUNT(*) AS cnt FROM categories GROUP BY parent_id ORDER BY parent_id;

-- [A-7] DISTINCT 는 NULL 을 값으로 세지 않는다 (27 vs 30)
SELECT COUNT(DISTINCT phone) AS distinct_phone, COUNT(*) AS total FROM customers;

-- [A-7] UNION 은 NULL 끼리 중복으로 보고 하나로 접는다 (3+3 → 1)
SELECT COUNT(*) AS rows_after_union FROM (
    SELECT phone FROM customers WHERE phone IS NULL
    UNION
    SELECT phone FROM customers WHERE phone IS NULL
) t;

-- [A-7] ROLLUP 이 만든 NULL : 이게 총계인가 "도시 미상"인가?
SELECT city AS 도시, COUNT(*) AS 고객수, SUM(points) AS 포인트합
FROM customers WHERE city IN ('서울','부산')
GROUP BY city WITH ROLLUP;

-- [A-7] GROUPING() 으로 소계 행을 정확히 구분한다 (MySQL 8)
SELECT
    IF(GROUPING(city) = 1, '── 전체 ──', COALESCE(city, '(도시 미상)')) AS 도시,
    COUNT(*)       AS 고객수,
    GROUPING(city) AS g_city
FROM customers WHERE city IN ('서울','부산')
GROUP BY city WITH ROLLUP;

-- =====================================================================
-- A-8. JOIN 과 NULL — NULL 확장
-- =====================================================================

DROP TABLE IF EXISTS s26_order_memo;
DROP TABLE IF EXISTS s26_cust;

CREATE TABLE s26_cust (
  id   INT PRIMARY KEY,
  name VARCHAR(10) NOT NULL
);
CREATE TABLE s26_order_memo (
  order_id INT PRIMARY KEY,
  cust_id  INT NOT NULL,
  memo     VARCHAR(20) NULL          -- ← 함정의 씨앗 : NULL 을 허용하는 컬럼
);
INSERT INTO s26_cust VALUES (1,'김'),(2,'이'),(3,'박');
INSERT INTO s26_order_memo VALUES (100,1,'급송'),(101,2,NULL);

-- [A-8] 2번(짝은 있고 memo 만 NULL)과 3번(짝이 없어 NULL 확장)은 memo 만 보면 구분이 안 된다
SELECT c.id, c.name, o.order_id, o.memo
FROM s26_cust c
LEFT JOIN s26_order_memo o ON o.cust_id = c.id
ORDER BY c.id;

-- [A-8] 올바른 안티조인 : NOT NULL 컬럼(PK)으로 판정 → 3번만
SELECT c.id, c.name
FROM s26_cust c
LEFT JOIN s26_order_memo o ON o.cust_id = c.id
WHERE o.order_id IS NULL;

-- [A-8] 잘못된 안티조인 : NULL 가능 컬럼으로 판정 → 주문이 있는 2번까지 딸려온다
SELECT c.id, c.name
FROM s26_cust c
LEFT JOIN s26_order_memo o ON o.cust_id = c.id
WHERE o.memo IS NULL;

-- [A-8] ON vs WHERE : 258 vs 240. WHERE 에 두면 LEFT JOIN 이 INNER JOIN 으로 퇴화한다
SELECT
    (SELECT COUNT(*) FROM customers c
       LEFT JOIN orders o ON o.customer_id = c.customer_id AND o.status = 'DELIVERED') AS 조건이_ON,
    (SELECT COUNT(*) FROM customers c
       LEFT JOIN orders o ON o.customer_id = c.customer_id
      WHERE o.status = 'DELIVERED')                                                    AS 조건이_WHERE;

-- =====================================================================
-- A-9. 정렬과 인덱스에서의 NULL
-- =====================================================================

-- [A-9] NULL 은 가장 작은 값 → ASC 면 맨 앞
SELECT customer_id, name, phone FROM customers ORDER BY phone LIMIT 4;

-- [A-9] NULLS LAST 흉내 : IS NULL(0/1) 로 먼저 정렬
SELECT customer_id, name, phone FROM customers ORDER BY phone IS NULL, phone LIMIT 4;

-- [A-9] IS NULL 도 인덱스를 탄다 (type=ref, key=idx_employees_manager)
EXPLAIN SELECT employee_id, name FROM employees WHERE manager_id IS NULL;

-- [A-9] 비교용 : = 1 로 조회할 때와 접근 방식이 같다
EXPLAIN SELECT employee_id, name FROM employees WHERE manager_id = 1;

-- =====================================================================
-- A-10. NULL 을 다루는 함수
-- =====================================================================

-- [A-10] IF(NULL, ...) 은 FALSE 분기로 간다. CASE 는 ELSE 가 없으면 NULL
SELECT
    COALESCE(NULL, NULL, '기본값')    AS coalesce_ex,
    NULLIF(100, 100)                  AS nullif_same,
    IF(NULL, 'TRUE분기', 'FALSE분기') AS if_unknown,
    CASE WHEN 1 = 2 THEN 'x' END      AS `CASE(ELSE 없음)`;

-- [A-10] NULLIF 의 정석 : 0 나누기 방지
SELECT name, price, (price - cost) / NULLIF(price, 0) AS margin_rate
FROM products ORDER BY product_id LIMIT 3;

-- =====================================================================
-- A-11. NULL 이 만들어지는 경로 ④ — 행이 없는 스칼라 서브쿼리
-- =====================================================================

SELECT (SELECT phone FROM customers WHERE customer_id = 999) AS no_row_scalar;

-- =====================================================================
-- A-12. NULL 이 특별대우를 받는 곳
-- =====================================================================

DROP TABLE IF EXISTS s26_uniq;
CREATE TABLE s26_uniq (
  id    INT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(50) UNIQUE,          -- ← NULL 은 몇 개든 허용된다
  age   INT,
  CHECK (age >= 18)                  -- ← NULL 은 위반이 아니다(UNKNOWN 통과)
);

-- [A-12] UNIQUE 인데 NULL 2건, CHECK(age>=18) 인데 age NULL 1건이 모두 통과한다
INSERT INTO s26_uniq (email, age) VALUES (NULL, 20), (NULL, 30), ('a@x.com', NULL);
SELECT * FROM s26_uniq;

-- [A-12] 참고 : CHECK 는 "FALSE 인 행만" 거부한다.
--        아래는 age=10 이 확실한 FALSE 라서 ERROR 3819 로 정상 차단된다.
--        (스크립트가 중간에 죽지 않도록 주석 처리해 두었습니다. 콘솔에서 직접 실행해 보세요.)
-- INSERT INTO s26_uniq (email, age) VALUES ('b@x.com', 10);

-- [A-12] AUTO_INCREMENT 컬럼의 NULL 은 "네가 알아서 채워라"라는 신호다 → id 1, 2
DROP TABLE IF EXISTS s26_ai;
CREATE TABLE s26_ai (id INT AUTO_INCREMENT PRIMARY KEY, v INT);
INSERT INTO s26_ai (id, v) VALUES (NULL, 10), (NULL, 20);
SELECT * FROM s26_ai;

-- =====================================================================
-- 정리 : s26_ 사본 삭제
-- =====================================================================
DROP TABLE IF EXISTS s26_order_memo;
DROP TABLE IF EXISTS s26_cust;
DROP TABLE IF EXISTS s26_uniq;
DROP TABLE IF EXISTS s26_ai;
