-- =====================================================================
-- Step 12 — 내장 함수 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
-- * 이 파일은 SELECT 만 합니다. 원본 데이터를 변경하지 않습니다.
-- =====================================================================
USE shop;

-- =====================================================================
-- 1. 문자열 함수
-- =====================================================================

-- [12-1] CONCAT / CONCAT_WS : 문자열 이어붙이기
SELECT
    CONCAT(name, '(', grade, ')')                 AS labeled,
    CONCAT_WS(' / ', city, grade, points)         AS joined_ws
FROM customers
ORDER BY customer_id
LIMIT 5;

-- [12-2] CONCAT 은 인자 중 하나라도 NULL 이면 전체가 NULL (함정)
--        CONCAT_WS 는 NULL 인자를 건너뛴다
SELECT
    customer_id, phone,
    CONCAT('전화: ', phone)          AS concat_null,
    CONCAT_WS(' ', '전화:', phone)   AS concat_ws_skip
FROM customers
WHERE phone IS NULL
LIMIT 3;

-- [12-3] SUBSTRING / LEFT / RIGHT : 부분 문자열 (인덱스는 1부터)
SELECT
    email,
    SUBSTRING(email, 1, 3)                       AS first3,
    SUBSTRING_INDEX(email, '@', 1)               AS local_part,
    SUBSTRING_INDEX(email, '@', -1)              AS domain
FROM customers
LIMIT 5;

-- [12-4] REPLACE / TRIM / LPAD : 치환·공백제거·자리채움
SELECT
    REPLACE(phone, '-', '')                      AS digits_only,
    LPAD(customer_id, 5, '0')                    AS padded_id,
    TRIM('  여백 있음  ')                         AS trimmed,
    LENGTH('가')                                  AS bytes_len,   -- utf8mb4 → 3
    CHAR_LENGTH('가')                             AS char_len     -- → 1
FROM customers
WHERE phone IS NOT NULL
LIMIT 3;

-- [12-5] LOCATE / INSTR : 위치 찾기 (없으면 0)
SELECT
    email,
    LOCATE('@', email)     AS at_pos,
    INSTR(email, 'example') AS example_pos
FROM customers
LIMIT 3;

-- [12-6] UPPER / LOWER
SELECT DISTINCT UPPER(city) AS up, LOWER('MySQL') AS low FROM customers LIMIT 3;

-- [12-7] REGEXP_REPLACE (8.0+) : 정규식 치환
--        전화번호에서 숫자만 남기고 마스킹
SELECT
    phone,
    REGEXP_REPLACE(phone, '[0-9]', '*')                 AS all_masked,
    REGEXP_REPLACE(phone, '[0-9]{4}$', '****')          AS tail_masked
FROM customers
WHERE phone IS NOT NULL
LIMIT 3;

-- [12-8] REGEXP_LIKE / REGEXP_SUBSTR (8.0+)
SELECT
    email,
    REGEXP_LIKE(email, '^[a-z]+\\.[a-z]+@')  AS is_dotted,
    REGEXP_SUBSTR(email, '[a-z]+$')          AS tld_like
FROM customers
LIMIT 3;

-- =====================================================================
-- 2. 숫자 함수
-- =====================================================================

-- [12-9] ROUND / CEIL / FLOOR / TRUNCATE 의 차이
SELECT
    price,
    ROUND(price/1000)        AS round0,
    ROUND(price/1000, 1)     AS round1,
    CEIL(price/1000)         AS ceil_,
    FLOOR(price/1000)        AS floor_,
    TRUNCATE(price/1000, 1)  AS trunc1
FROM products
WHERE product_id IN (24, 30, 12)
ORDER BY product_id;

-- [12-10] ROUND vs TRUNCATE : 반올림 vs 버림
SELECT
    ROUND(12345.678, 2)   AS r2,
    ROUND(12345.678, -2)  AS r_neg2,   -- 백의 자리에서 반올림
    ROUND(12345.678, 0)   AS r0,
    TRUNCATE(12345.678, 2) AS t2,
    TRUNCATE(12345.678, -2) AS t_neg2;

-- [12-11] ABS / MOD / POWER / SQRT
SELECT
    ABS(-1500)      AS abs_,
    MOD(17, 5)      AS mod_,
    17 % 5          AS mod_op,
    POWER(2, 10)    AS pow_,
    SQRT(144)       AS sqrt_;

-- [12-12] 마진율 계산 (숫자 함수 종합)
SELECT
    product_id, name, price, cost,
    price - cost                              AS margin,
    ROUND((price - cost) / price * 100, 1)    AS margin_pct
FROM products
ORDER BY margin_pct DESC
LIMIT 5;

-- =====================================================================
-- 3. 날짜/시간 함수
-- =====================================================================

-- [12-13] 현재 시각
SELECT NOW() AS now_, CURDATE() AS today, CURTIME() AS time_, UTC_TIMESTAMP() AS utc_;

-- [12-14] DATE_ADD / DATE_SUB / INTERVAL
SELECT
    order_date,
    DATE_ADD(order_date, INTERVAL 3 DAY)     AS plus_3d,
    DATE_SUB(order_date, INTERVAL 1 MONTH)   AS minus_1m,
    order_date + INTERVAL 1 YEAR             AS plus_1y
FROM orders
ORDER BY order_id
LIMIT 3;

-- [12-15] DATEDIFF (일 단위) vs TIMESTAMPDIFF (단위 지정)
SELECT
    o.order_id,
    o.order_date,
    p.paid_at,
    DATEDIFF(p.paid_at, o.order_date)                 AS days_diff,
    TIMESTAMPDIFF(HOUR,   o.order_date, p.paid_at)     AS hours_diff,
    TIMESTAMPDIFF(MINUTE, o.order_date, p.paid_at)     AS minutes_diff
FROM orders o
JOIN payments p ON p.order_id = o.order_id
ORDER BY o.order_id
LIMIT 5;

-- [12-16] 고객 나이 계산 (TIMESTAMPDIFF 의 정석)
SELECT
    name, birth_date,
    TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) AS age
FROM customers
WHERE birth_date IS NOT NULL
ORDER BY age DESC
LIMIT 5;

-- [12-17] DATE_FORMAT : 날짜 → 문자열
SELECT
    order_date,
    DATE_FORMAT(order_date, '%Y-%m-%d')             AS ymd,
    DATE_FORMAT(order_date, '%Y년 %m월 %d일')        AS korean,
    DATE_FORMAT(order_date, '%H:%i:%s')             AS hms,
    DATE_FORMAT(order_date, '%W')                   AS weekday_name
FROM orders
ORDER BY order_id
LIMIT 3;

-- [12-18] STR_TO_DATE : 문자열 → 날짜 (DATE_FORMAT 의 역방향)
--         d3 는 "일(日)"이 없어 00일이 되는데, NO_ZERO_DATE 모드라 NULL + 경고.
SELECT
    STR_TO_DATE('2025-07-13', '%Y-%m-%d')             AS d1,
    STR_TO_DATE('13/07/2025 15:30', '%d/%m/%Y %H:%i') AS d2,
    STR_TO_DATE('2025년 07월 01일', '%Y년 %m월 %d일') AS d3_ok,
    STR_TO_DATE('2025년 07월', '%Y년 %m월')           AS d4_null;
SHOW WARNINGS;

-- [12-19] EXTRACT / YEAR / MONTH / DAY / 주차
SELECT
    order_date,
    YEAR(order_date)                 AS y,
    MONTH(order_date)                AS m,
    QUARTER(order_date)              AS q,
    WEEK(order_date, 3)              AS iso_week,     -- ISO 주차(모드 3)
    EXTRACT(YEAR_MONTH FROM order_date) AS ym
FROM orders
ORDER BY order_id
LIMIT 3;

-- [12-20] LAST_DAY / 월초·월말 구하기
SELECT
    order_date,
    DATE_FORMAT(order_date, '%Y-%m-01')          AS month_start,
    LAST_DAY(order_date)                         AS month_end,
    DAY(LAST_DAY(order_date))                    AS days_in_month
FROM orders
ORDER BY order_id
LIMIT 3;

-- [12-21] 월별 집계에 DATE_FORMAT 활용
SELECT
    DATE_FORMAT(order_date, '%Y-%m') AS ym,
    COUNT(*)                         AS cnt,
    SUM(total_amount)                AS amount
FROM orders
WHERE status <> 'CANCELLED'
GROUP BY ym
ORDER BY ym
LIMIT 6;

-- =====================================================================
-- 4. 조건 함수
-- =====================================================================

-- [12-22] IF : 삼항 연산
SELECT
    product_id, name, stock,
    IF(stock = 0, '품절', '판매중') AS state
FROM products
ORDER BY product_id
LIMIT 5;

-- [12-23] IFNULL / COALESCE : NULL 대체
SELECT
    customer_id, phone,
    IFNULL(phone, '(없음)')                  AS ifnull_,
    COALESCE(phone, '(없음)')                AS coalesce_,
    COALESCE(NULLIF(phone, ''), phone, '기본') AS chained
FROM customers
WHERE phone IS NULL
LIMIT 3;

-- [12-24] NULLIF : 두 값이 같으면 NULL (0으로 나누기 방지에 유용)
SELECT
    10 / NULLIF(0, 0)      AS safe_div_null,   -- 0으로 나누기 → NULL (에러 아님)
    NULLIF('A', 'A')       AS same_null,
    NULLIF('A', 'B')       AS diff_a;

-- [12-25] CASE : 다분기
SELECT
    grade,
    CASE grade
        WHEN 'VIP'    THEN '★★★'
        WHEN 'GOLD'   THEN '★★'
        WHEN 'SILVER' THEN '★'
        ELSE '·'
    END AS stars,
    CASE
        WHEN points >= 10000 THEN '1만+'
        WHEN points >= 1000  THEN '1천+'
        ELSE '소액'
    END AS point_band
FROM customers
ORDER BY points DESC
LIMIT 6;

-- [12-26] CASE + 집계 : 조건부 카운트 (피벗의 기초)
SELECT
    SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered,
    SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
    SUM(CASE WHEN status = 'PENDING'   THEN 1 ELSE 0 END) AS pending,
    COUNT(*)                                              AS total
FROM orders;

-- =====================================================================
-- 5. 형변환
-- =====================================================================

-- [12-27] CAST / CONVERT
SELECT
    CAST('2025-07-13' AS DATE)          AS as_date,
    CAST(3.14159 AS DECIMAL(10,2))      AS as_decimal,
    CAST(255 AS CHAR)                   AS as_char,
    CONVERT('123', SIGNED)              AS as_int,
    CAST('abc' AS CHAR)                 AS keep_char;

-- [12-28] 암묵적 형변환의 함정 : 문자열과 숫자 비교
SELECT
    '10' = 10       AS str_eq_num,     -- 1 (숫자로 변환되어 비교)
    '10abc' = 10    AS partial,        -- 1 (앞의 숫자만 취함 + 경고)
    'abc' = 0       AS text_eq_zero;   -- 1 (숫자 변환 실패 → 0)
SHOW WARNINGS;

-- =====================================================================
-- 6. ★ 함수를 컬럼에 씌우면 인덱스를 못 탄다 (매우 중요)
-- =====================================================================

-- [12-29] 인덱스가 있는 컬럼(customer_id, idx_orders_customer)으로 시연한다.
--         나쁜 예 : 컬럼에 연산을 씌우면 인덱스를 못 탄다 → type=ALL(풀 스캔)
EXPLAIN
SELECT * FROM orders WHERE customer_id + 0 = 5;

-- [12-30] 좋은 예 : 값 쪽을 그대로 두면 인덱스를 탄다 → type=ref, key 사용
EXPLAIN
SELECT * FROM orders WHERE customer_id = 5;

-- [12-31] 날짜도 원리는 같다. YEAR() 로 감싸면 인덱스가 있어도 못 쓴다.
--         (이 스키마의 orders.order_date 에는 인덱스가 없어 둘 다 풀 스캔이지만,
--          '컬럼을 함수로 감싸지 않는다'는 원칙은 인덱스 유무와 무관하게 지켜야 한다)
EXPLAIN
SELECT * FROM orders WHERE YEAR(order_date) = 2025;

-- [12-32] 좋은 예 : 범위 조건으로 바꾼다. 인덱스가 있다면 이 형태만 인덱스를 탄다.
EXPLAIN
SELECT * FROM orders
WHERE order_date >= '2025-01-01' AND order_date < '2026-01-01';

-- [12-33] 문자열도 마찬가지 : 함수 대신 LIKE 접두 매칭
--         나쁜 예 (인덱스 못 씀) — 참고용, email 은 UNIQUE 인덱스 있음
EXPLAIN
SELECT * FROM customers WHERE SUBSTRING(email, 1, 3) = 'kim';

-- [12-34] 좋은 예 : 접두 LIKE 는 인덱스 범위 스캔 가능
EXPLAIN
SELECT * FROM customers WHERE email LIKE 'kim%';

-- [12-35] 8.0 대안 : 함수 기반 인덱스(functional index)를 걸면 함수도 인덱스를 탄다
--         (원본 테이블 변경 금지이므로 여기선 설명만; 문법 참고)
-- ALTER TABLE orders ADD INDEX idx_order_year ((YEAR(order_date)));
-- → 이후 WHERE YEAR(order_date) = 2025 가 인덱스를 사용하게 된다 (8.0.13+)
