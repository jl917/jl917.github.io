-- =====================================================================
-- Step 05 — 연산자와 조건 : solution.sql (정답 + 해설)
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < solution.sql
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] 가격대 + 상태 필터
--   BETWEEN 은 양 끝을 포함합니다. 숫자 범위에는 안심하고 써도 됩니다.
--   (문제가 되는 건 DATETIME 뿐입니다 — 정답 3 참고)
--   결과: 10건 (플리츠 롱스커트 59000 ~ 러닝화 에어플로우 139000)
-- ---------------------------------------------------------------------
SELECT product_id, name, price, status
FROM products
WHERE price BETWEEN 50000 AND 150000
  AND status = 'ON_SALE'
ORDER BY price ASC;

-- ---------------------------------------------------------------------
-- [정답 2] 괄호 함정 피하기
--   OR 조건 두 덩어리를 AND 로 묶으려면 각각을 괄호로 싸야 합니다.
--   괄호를 빼면 AND 가 먼저 묶여서
--     city='서울'  OR  (city='부산' AND grade='GOLD')  OR  grade='VIP'
--   가 되어 "서울 사는 BRONZE" 까지 딸려옵니다.
--
--   IN 을 쓰면 괄호 실수 자체가 불가능해집니다 — 이쪽을 권합니다.
--   결과: 9명 (서울 8명 + 부산 1명(하준서))
-- ---------------------------------------------------------------------
SELECT customer_id, name, grade, city
FROM customers
WHERE city IN ('서울', '부산')
  AND grade IN ('GOLD', 'VIP')
ORDER BY customer_id;

-- (괄호로 쓴 동일한 쿼리)
SELECT customer_id, name, grade, city
FROM customers
WHERE (city = '서울' OR city = '부산')
  AND (grade = 'GOLD' OR grade = 'VIP')
ORDER BY customer_id;

-- ---------------------------------------------------------------------
-- [정답 3] 날짜 범위는 반열림 구간으로
--   order_date 는 DATETIME 입니다.
--   BETWEEN '2025-03-01' AND '2025-03-31' 로 쓰면
--   '2025-03-31 00:00:00' 이 상한이 되어 3월 31일 낮의 주문이 전부 누락됩니다.
--   >= 시작 AND < 다음달1일  이 어떤 시간 정밀도에도 안전한 정답입니다.
--   결과: 26건
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS 주문건수_2025_03
FROM orders
WHERE order_date >= '2025-03-01'
  AND order_date <  '2025-04-01';

-- ---------------------------------------------------------------------
-- [정답 4] NULL 찾기 (두 가지 방법)
--   phone = NULL 은 절대 안 됩니다 (항상 UNKNOWN → 0건).
--   결과: 윤대현(광주) / 남규리(울산) / 심준호(대구)
--
--   (a) IS NULL — 가장 표준적이고 읽기 쉬운 방법
-- ---------------------------------------------------------------------
SELECT name, city, grade
FROM customers
WHERE phone IS NULL;

-- ---------------------------------------------------------------------
--   (b) <=> — NULL-safe 등호. NULL <=> NULL 이 TRUE 라서 동작합니다.
--       단순히 NULL 을 찾는 목적이라면 IS NULL 이 더 명확합니다.
--       <=> 의 진가는 비교 대상이 "값일 수도, NULL 일 수도 있는 파라미터"
--       일 때 나옵니다: WHERE phone <=> ?
-- ---------------------------------------------------------------------
SELECT name, city, grade
FROM customers
WHERE phone <=> NULL;

-- ---------------------------------------------------------------------
-- [정답 5] 숫자가 없는 상품명
--   NOT REGEXP '[0-9]' = "숫자가 한 개도 매칭되지 않는다"
--   결과: 17건 (의류/신발 대부분, 무선 마우스 프로, 노이즈캔슬링 헤드폰 등)
--   "27인치 4K 모니터", "6단 책장", "실전 MySQL 8" 등은 숫자가 있어 제외됩니다.
-- ---------------------------------------------------------------------
SELECT product_id, name
FROM products
WHERE name NOT REGEXP '[0-9]'
ORDER BY product_id;

-- (동일한 결과 — 전체가 "숫자 아닌 문자" 로만 이루어졌다는 표현)
SELECT product_id, name
FROM products
WHERE name REGEXP '^[^0-9]*$'
ORDER BY product_id;

-- ---------------------------------------------------------------------
-- [정답 6] 안티조인 (NOT EXISTS)
--   후기를 한 번도 안 남긴 고객 = 26명 (전체 30명 중 4명만 후기를 남겼습니다)
--
--   [NOT IN 이 위험한 이유]
--   지금 reviews.customer_id 는 NOT NULL 이므로
--       WHERE customer_id NOT IN (SELECT customer_id FROM reviews)
--   도 "현재는" 정상 동작합니다. 그런데도 NOT EXISTS 를 권하는 이유:
--
--   1) NOT IN 의 정확성이 "서브쿼리 컬럼이 NOT NULL 인가"에 의존합니다.
--      누군가 스키마를 바꿔 NULL 을 허용하는 순간, 이 쿼리는 에러 없이
--      조용히 0건을 반환하기 시작합니다. 배치가 아무 일도 안 하는데
--      로그에는 아무 흔적도 안 남습니다. 최악의 버그입니다.
--   2) 서브쿼리가 조인/UNION 등으로 복잡해지면 NOT NULL 보장이 쉽게 깨집니다.
--      (예: LEFT JOIN 결과 컬럼은 NOT NULL 컬럼이어도 NULL 이 될 수 있습니다)
--   3) NOT EXISTS 는 애초에 "매칭되는 행이 있는가"만 보므로 NULL 과 무관하게
--      항상 옳습니다. 옵티마이저도 안티조인으로 잘 풀어냅니다.
--
--   → 결론: NOT IN 은 상수 리스트에만. 서브쿼리에는 NOT EXISTS.
-- ---------------------------------------------------------------------
SELECT c.customer_id, c.name, c.grade, c.city
FROM customers c
WHERE NOT EXISTS (
    SELECT 1
    FROM reviews r
    WHERE r.customer_id = c.customer_id
)
ORDER BY c.customer_id;

-- ---------------------------------------------------------------------
-- [정답 7] REGEXP_REPLACE 로 마스킹
--   REGEXP_REPLACE(대상, 패턴, 치환문자열) — MySQL 8.0 신규 함수입니다.
--   [0-9] 로 쓰면 숫자 "한 글자씩" 치환되므로 256GB → ###GB 가 됩니다.
--   [0-9]+ 로 쓰면 연속된 숫자 덩어리가 하나의 # 이 되어 256GB → #GB 입니다.
--   문제의 의도("숫자를 전부 # 로")는 전자입니다.
-- ---------------------------------------------------------------------
SELECT
    product_id,
    name,
    REGEXP_REPLACE(name, '[0-9]', '#') AS masked
FROM products
WHERE name REGEXP '[0-9]'
ORDER BY product_id;

-- ---------------------------------------------------------------------
-- [정답 8] 커서 페이징
--   (a) 1페이지 — total_amount 가 6,663,900 으로 5건이 전부 동점입니다!
--       order_id DESC 가 tie-break 를 해주지 않으면 순서가 뒤죽박죽이 되고
--       페이징이 깨집니다. 이 문제가 tie-break 의 중요성을 잘 보여줍니다.
--       결과: 482 / 362 / 242 / 122 / 2  (전부 6663900.00)
-- ---------------------------------------------------------------------
SELECT order_id, total_amount
FROM orders
ORDER BY total_amount DESC, order_id DESC
LIMIT 5;

-- ---------------------------------------------------------------------
--   (b) 커서 페이징 — 1페이지 마지막 행 (6663900.00, 2) 를 커서로 삼습니다.
--       행 생성자 비교 (a,b) < (x,y) 는 사전식 비교입니다:
--         total_amount 가 더 작거나,
--         total_amount 가 같으면서 order_id 가 더 작은 행
--       total_amount 만으로 커서를 잡았다면(WHERE total_amount < 6663900)
--       동점인 5건이 통째로 사라졌을 겁니다. order_id 가 반드시 필요합니다.
--       결과: 520 / 400 / 280 / 160 / 40  (전부 6599000.00)
-- ---------------------------------------------------------------------
SELECT order_id, total_amount
FROM orders
WHERE (total_amount, order_id) < (6663900.00, 2)
ORDER BY total_amount DESC, order_id DESC
LIMIT 5;

-- ---------------------------------------------------------------------
--   (c) OFFSET 방식 2페이지 — (b) 와 결과가 완전히 동일합니다.
--       차이는 성능입니다. OFFSET 은 앞의 5건을 읽고 버리지만,
--       커서 방식은 인덱스로 바로 그 지점에 점프합니다.
--       600건에선 체감이 없지만 1000만 건에선 하늘과 땅 차이입니다.
-- ---------------------------------------------------------------------
SELECT order_id, total_amount
FROM orders
ORDER BY total_amount DESC, order_id DESC
LIMIT 5 OFFSET 5;
