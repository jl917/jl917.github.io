-- =====================================================================
-- Step 12 — 내장 함수 : 정답 + 해설
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- A1. SUBSTRING_INDEX 로 @ 기준 분리, UPPER 로 대문자 변환.
-- ---------------------------------------------------------------------
SELECT
    email,
    SUBSTRING_INDEX(email, '@', -1)          AS domain,
    UPPER(SUBSTRING_INDEX(email, '@', 1))    AS local_upper
FROM customers
ORDER BY customer_id
LIMIT 5;

-- ---------------------------------------------------------------------
-- A2. "뒤 4자리만 남기고" 앞쪽 숫자를 * 로.
--     하이픈이 숫자 사이에 끼어 있어 정규식 전방탐색만으로는 까다롭다.
--     안정적인 방법: 뒤 4글자를 떼어 보존하고, 앞부분의 숫자만 * 로 바꾼 뒤 합친다.
-- ---------------------------------------------------------------------
SELECT
    phone,
    CONCAT(
        REGEXP_REPLACE(LEFT(phone, CHAR_LENGTH(phone) - 4), '[0-9]', '*'),
        RIGHT(phone, 4)
    ) AS masked
FROM customers
WHERE phone IS NOT NULL
ORDER BY customer_id
LIMIT 5;
-- → 010-1000-0001 → ***-****-0001

-- ---------------------------------------------------------------------
-- A3. 같은 식에 ROUND 와 TRUNCATE 를 각각.
--     반올림과 버림이 소수 1자리에서 갈리는 상품이 있다.
-- ---------------------------------------------------------------------
SELECT
    product_id, name,
    ROUND((price - cost) / price * 100, 1)    AS margin_round,
    TRUNCATE((price - cost) / price * 100, 1) AS margin_trunc
FROM products
ORDER BY margin_round DESC
LIMIT 5;

-- ---------------------------------------------------------------------
-- A4. TIMESTAMPDIFF(MINUTE, 시작, 끝). 인자 순서 주의(시작 먼저).
-- ---------------------------------------------------------------------
SELECT
    o.order_id, o.order_date, p.paid_at,
    TIMESTAMPDIFF(MINUTE, o.order_date, p.paid_at) AS minutes
FROM orders o
JOIN payments p ON p.order_id = o.order_id
WHERE TIMESTAMPDIFF(MINUTE, o.order_date, p.paid_at) >= 60
ORDER BY minutes DESC
LIMIT 5;
-- → paid_at 은 order_date + (5 + order_id%120) 분이므로 최대 124분까지 나온다.

-- ---------------------------------------------------------------------
-- A5. CASE 로 나이대 분류. 나이는 TIMESTAMPDIFF(YEAR, ...).
--     FLOOR(age/10)*10 으로 계산해도 되지만, 요구가 "미만/이상"이라 CASE 로 명시.
-- ---------------------------------------------------------------------
SELECT
    name,
    TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) AS age,
    CASE
        WHEN TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) < 20 THEN '20대 미만'
        WHEN TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) < 30 THEN '20대'
        WHEN TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) < 40 THEN '30대'
        WHEN TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) < 50 THEN '40대'
        ELSE '50대 이상'
    END AS age_band
FROM customers
WHERE birth_date IS NOT NULL
ORDER BY age DESC
LIMIT 8;

-- ---------------------------------------------------------------------
-- A6. DATE_FORMAT 으로 라벨을 만들되,
--     정렬은 라벨 문자열이 아니라 실제 연월(정렬 가능한 값)로 해야 안전하다.
--     여기선 '%Y년 %m월' 라벨이 사전순=시간순이 되므로 라벨로 정렬해도 된다.
-- ---------------------------------------------------------------------
SELECT
    DATE_FORMAT(order_date, '%Y년 %m월') AS ym_label,
    COUNT(*)                             AS cnt,
    SUM(total_amount)                    AS amount
FROM orders
WHERE status <> 'CANCELLED'
GROUP BY ym_label
ORDER BY MIN(order_date)          -- 라벨이 아니라 실제 날짜로 정렬(안전)
LIMIT 6;

-- ---------------------------------------------------------------------
-- A7. COALESCE(phone, '연락처없음').
--     IFNULL 로도 가능하지만 COALESCE 가 표준이고 인자 확장이 쉽다.
-- ---------------------------------------------------------------------
SELECT
    customer_id, name,
    COALESCE(phone, '연락처없음') AS phone_or_default
FROM customers
WHERE phone IS NULL
ORDER BY customer_id;
-- → 7(윤대현), 14(남규리), 28(심준호)

-- ---------------------------------------------------------------------
-- A8. 함수 씌운 컬럼 → 인덱스 무력화.
--     (a) 나쁜 버전 (주석):
--        EXPLAIN SELECT * FROM orders WHERE YEAR(order_date) = 2025;
--        → type=ALL (풀 스캔)
--     (b) 좋은 버전: 범위 조건.
-- ---------------------------------------------------------------------
EXPLAIN
SELECT * FROM orders
WHERE order_date >= '2025-01-01' AND order_date < '2026-01-01';
-- 참고: orders.order_date 에는 인덱스가 없어 이 스키마에선 둘 다 풀 스캔이지만,
--       "값을 범위로 주고 컬럼은 감싸지 않는다"가 인덱스를 살리는 유일한 형태다.
--       만약 order_date 에 인덱스가 있다면 (b) 만 type=range 로 인덱스를 탄다.

-- 실제 인덱스가 있는 컬럼(customer_id)으로 원리를 확인:
EXPLAIN SELECT * FROM orders WHERE customer_id + 0 = 5;   -- type=ALL  (나쁜 예)
EXPLAIN SELECT * FROM orders WHERE customer_id = 5;       -- type=ref  (좋은 예)
