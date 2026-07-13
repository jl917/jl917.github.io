# Step 12 — 내장 함수

> **학습 목표**
> - 문자열 함수(CONCAT/SUBSTRING/REPLACE/TRIM/LPAD/LOCATE/REGEXP_REPLACE)를 다룬다
> - 숫자 함수(ROUND/CEIL/FLOOR/TRUNCATE/MOD)의 미묘한 차이를 안다
> - 날짜 함수(DATE_ADD/DATEDIFF/TIMESTAMPDIFF/DATE_FORMAT/STR_TO_DATE/LAST_DAY/주차)를 자유자재로 쓴다
> - 조건 함수(IF/IFNULL/NULLIF/COALESCE/CASE)와 형변환(CAST/CONVERT)을 익힌다
> - **컬럼에 함수를 씌우면 인덱스를 못 탄다**는 원칙을 EXPLAIN 으로 확인한다
>
> **선행 스텝**: Step 11 (DML)
> **예상 소요**: 55분

---

## 12-1. 문자열 함수

### CONCAT / CONCAT_WS

```sql
SELECT
    CONCAT(name, '(', grade, ')')          AS labeled,
    CONCAT_WS(' / ', city, grade, points)  AS joined_ws
FROM customers
ORDER BY customer_id
LIMIT 5;
```

**결과**
```
+-------------------+------------------------+
| labeled           | joined_ws              |
+-------------------+------------------------+
| 김민수(VIP)       | 서울 / VIP / 12500     |
| 이지은(GOLD)      | 서울 / GOLD / 8300     |
| 박철수(SILVER)    | 부산 / SILVER / 3100   |
| 최영희(BRONZE)    | 대구 / BRONZE / 200    |
| 정  훈(GOLD)      | 인천 / GOLD / 7600     |
+-------------------+------------------------+
```

> ⚠️ **함정**: `CONCAT` 은 **인자 중 하나라도 NULL 이면 결과 전체가 NULL** 입니다. `CONCAT_WS`(With Separator)는 NULL 인자를 **건너뜁니다**.
> ```sql
> SELECT CONCAT('전화: ', phone) AS a, CONCAT_WS(' ', '전화:', phone) AS b
> FROM customers WHERE phone IS NULL LIMIT 1;
> ```
> ```
> +------+--------+
> | a    | b      |
> +------+--------+
> | NULL | 전화:  |
> +------+--------+
> ```
> 이름 뒤에 붙인 별명 하나가 NULL 이라 전체 문자열이 통째로 사라지는 사고가 흔합니다. NULL 가능성이 있으면 `CONCAT_WS` 를 쓰거나 `COALESCE(phone,'')` 로 감싸세요.

### SUBSTRING / SUBSTRING_INDEX

문자열 인덱스는 **1부터** 시작합니다(0이 아닙니다).

```sql
SELECT
    email,
    SUBSTRING(email, 1, 3)          AS first3,
    SUBSTRING_INDEX(email, '@', 1)  AS local_part,   -- @ 앞
    SUBSTRING_INDEX(email, '@', -1) AS domain        -- @ 뒤 (음수 = 뒤에서)
FROM customers LIMIT 3;
```

**결과**
```
+---------------------------+--------+---------------+-------------+
| email                     | first3 | local_part    | domain      |
+---------------------------+--------+---------------+-------------+
| ahn.jisoo@example.com     | ahn    | ahn.jisoo     | example.com |
| bae.chaeyoung@example.com | bae    | bae.chaeyoung | example.com |
| baek.seungho@example.com  | bae    | baek.seungho  | example.com |
+---------------------------+--------+---------------+-------------+
```

### REPLACE / LPAD / TRIM 그리고 LENGTH vs CHAR_LENGTH

```sql
SELECT
    REPLACE(phone, '-', '') AS digits_only,
    LPAD(customer_id, 5, '0') AS padded_id,
    LENGTH('가')     AS bytes_len,   -- 바이트 수
    CHAR_LENGTH('가') AS char_len    -- 글자 수
FROM customers WHERE phone IS NOT NULL LIMIT 1;
```

**결과**
```
+-------------+-----------+-----------+----------+
| digits_only | padded_id | bytes_len | char_len |
+-------------+-----------+-----------+----------+
| 01010000001 | 00001     |         3 |        1 |
+-------------+-----------+-----------+----------+
```

> ⚠️ **함정**: utf8mb4 에서 한글 한 글자는 **3바이트**입니다. `LENGTH('가')` 는 3, `CHAR_LENGTH('가')` 는 1. 글자 수를 세려면 반드시 `CHAR_LENGTH` 를 쓰세요. `VARCHAR(50)` 은 50 "글자"이지 50 바이트가 아닙니다.

### REGEXP_REPLACE / REGEXP_LIKE (MySQL 8.0+)

정규식 함수는 **8.0에서 새로 들어왔습니다**(5.7 에는 `REGEXP` 매칭만 있고 치환/추출이 없었습니다).

```sql
SELECT
    phone,
    REGEXP_REPLACE(phone, '[0-9]', '*')        AS all_masked,
    REGEXP_REPLACE(phone, '[0-9]{4}$', '****')  AS tail_masked
FROM customers WHERE phone IS NOT NULL LIMIT 3;
```

**결과**
```
+---------------+---------------+---------------+
| phone         | all_masked    | tail_masked   |
+---------------+---------------+---------------+
| 010-1000-0001 | ***-****-**** | 010-1000-**** |
| 010-1000-0002 | ***-****-**** | 010-1000-**** |
| 010-1000-0003 | ***-****-**** | 010-1000-**** |
+---------------+---------------+---------------+
```

---

## 12-2. 숫자 함수

### ROUND / CEIL / FLOOR / TRUNCATE

넷 다 "소수를 정리"하지만 방식이 다릅니다.

```sql
SELECT
    price,
    ROUND(price/1000)       AS round0,   -- 반올림
    ROUND(price/1000, 1)    AS round1,   -- 소수 1자리 반올림
    CEIL(price/1000)        AS ceil_,    -- 올림
    FLOOR(price/1000)       AS floor_,   -- 내림
    TRUNCATE(price/1000, 1) AS trunc1    -- 버림(자리 지정)
FROM products WHERE product_id IN (24, 30, 12) ORDER BY product_id;
```

**결과**
```
+------------+--------+--------+-------+--------+--------+
| price      | round0 | round1 | ceil_ | floor_ | trunc1 |
+------------+--------+--------+-------+--------+--------+
| 1290000.00 |   1290 | 1290.0 |  1290 |   1290 | 1290.0 |
|   19900.00 |     20 |   19.9 |    20 |     19 |   19.9 |
|    4900.00 |      5 |    4.9 |     5 |      4 |    4.9 |
+------------+--------+--------+-------+--------+--------+
```

`ROUND` 와 `TRUNCATE` 의 결정적 차이는 **반올림 vs 버림**입니다. 또 둘 다 **음수 자릿수**를 받아 정수부를 정리할 수 있습니다.

```sql
SELECT
    ROUND(12345.678, 2)     AS r2,      -- 12345.68
    ROUND(12345.678, -2)    AS r_neg2,  -- 12300 (백의 자리 반올림)
    TRUNCATE(12345.678, 2)  AS t2,      -- 12345.67
    TRUNCATE(12345.678, -2) AS t_neg2;  -- 12300
```

**결과**
```
+----------+--------+----------+--------+
| r2       | r_neg2 | t2       | t_neg2 |
+----------+--------+----------+--------+
| 12345.68 |  12300 | 12345.67 |  12300 |
+----------+--------+----------+--------+
```

> 💡 **실무 팁**: 금액을 다룰 때 `ROUND` 와 `TRUNCATE` 를 혼동하면 회계상 1원씩 어긋납니다. "부가세는 버림"처럼 업무 규칙이 반올림인지 버림인지 먼저 확인하세요.

### MOD 와 마진율 계산

```sql
SELECT product_id, name, price, cost,
       price - cost                           AS margin,
       ROUND((price - cost) / price * 100, 1) AS margin_pct
FROM products ORDER BY margin_pct DESC LIMIT 3;
```

**결과**
```
+------------+--------------------------+----------+----------+----------+------------+
| product_id | name                     | price    | cost     | margin   | margin_pct |
+------------+--------------------------+----------+----------+----------+------------+
|         30 | 다크초콜릿 72% 100g      |  4900.00 |  1900.00 |  3000.00 |       61.2 |
|         22 | USB-C 허브 8in1          | 59000.00 | 24000.00 | 35000.00 |       59.3 |
|         20 | 무선 마우스 프로         | 79000.00 | 33000.00 | 46000.00 |       58.2 |
+------------+--------------------------+----------+----------+----------+------------+
```

---

## 12-3. 날짜/시간 함수

### 현재 시각

```sql
SELECT NOW() AS now_, CURDATE() AS today, CURTIME() AS time_, UTC_TIMESTAMP() AS utc_;
```

**결과**
```
+---------------------+------------+----------+---------------------+
| now_                | today      | time_    | utc_                |
+---------------------+------------+----------+---------------------+
| 2026-07-13 10:51:38 | 2026-07-13 | 10:51:38 | 2026-07-13 01:51:38 |
+---------------------+------------+----------+---------------------+
```

### DATE_ADD / DATE_SUB / INTERVAL

```sql
SELECT order_date,
       DATE_ADD(order_date, INTERVAL 3 DAY)   AS plus_3d,
       DATE_SUB(order_date, INTERVAL 1 MONTH) AS minus_1m,
       order_date + INTERVAL 1 YEAR           AS plus_1y   -- 연산자 형태도 가능
FROM orders ORDER BY order_id LIMIT 1;
```

**결과**
```
+---------------------+---------------------+---------------------+---------------------+
| order_date          | plus_3d             | minus_1m            | plus_1y             |
+---------------------+---------------------+---------------------+---------------------+
| 2024-02-07 13:07:00 | 2024-02-10 13:07:00 | 2024-01-07 13:07:00 | 2025-02-07 13:07:00 |
+---------------------+---------------------+---------------------+---------------------+
```

### DATEDIFF vs TIMESTAMPDIFF

`DATEDIFF(a, b)` 는 **일(day) 수만** 돌려줍니다. 시·분·연 단위가 필요하면 `TIMESTAMPDIFF(단위, 시작, 끝)` 을 씁니다. **인자 순서가 반대**라는 점에 주의하세요.

```sql
SELECT o.order_id, o.order_date, p.paid_at,
       DATEDIFF(p.paid_at, o.order_date)              AS days_diff,
       TIMESTAMPDIFF(HOUR,   o.order_date, p.paid_at)  AS hours_diff,
       TIMESTAMPDIFF(MINUTE, o.order_date, p.paid_at)  AS minutes_diff
FROM orders o JOIN payments p ON p.order_id = o.order_id
ORDER BY o.order_id LIMIT 3;
```

**결과**
```
+----------+---------------------+---------------------+-----------+------------+--------------+
| order_id | order_date          | paid_at             | days_diff | hours_diff | minutes_diff |
+----------+---------------------+---------------------+-----------+------------+--------------+
|        1 | 2024-02-07 13:07:00 | 2024-02-07 13:13:00 |         0 |          0 |            6 |
|        2 | 2024-03-15 02:14:00 | 2024-03-15 02:21:00 |         0 |          0 |            7 |
|        3 | 2024-04-21 15:21:00 | 2024-04-21 15:29:00 |         0 |          0 |            8 |
+----------+---------------------+---------------------+-----------+------------+--------------+
```

나이 계산은 `TIMESTAMPDIFF(YEAR, ...)` 가 정석입니다(윤년·생일 지남 여부까지 알아서 처리).

```sql
SELECT name, birth_date, TIMESTAMPDIFF(YEAR, birth_date, CURDATE()) AS age
FROM customers WHERE birth_date IS NOT NULL ORDER BY age DESC LIMIT 3;
```

**결과**
```
+-----------+------------+------+
| name      | birth_date | age  |
+-----------+------------+------+
| 박철수    | 1978-11-02 |   47 |
| 양현우    | 1979-01-31 |   47 |
| 구세진    | 1981-10-27 |   44 |
+-----------+------------+------+
```

### DATE_FORMAT / STR_TO_DATE

`DATE_FORMAT` 은 날짜 → 문자열, `STR_TO_DATE` 는 그 반대입니다.

```sql
SELECT order_date,
       DATE_FORMAT(order_date, '%Y년 %m월 %d일') AS korean,
       DATE_FORMAT(order_date, '%H:%i:%s')       AS hms,
       DATE_FORMAT(order_date, '%W')             AS weekday_name
FROM orders ORDER BY order_id LIMIT 2;
```

**결과**
```
+---------------------+---------------------+----------+--------------+
| order_date          | korean              | hms      | weekday_name |
+---------------------+---------------------+----------+--------------+
| 2024-02-07 13:07:00 | 2024년 02월 07일    | 13:07:00 | Wednesday    |
| 2024-03-15 02:14:00 | 2024년 03월 15일    | 02:14:00 | Friday       |
+---------------------+---------------------+----------+--------------+
```

```sql
SELECT STR_TO_DATE('2025-07-13', '%Y-%m-%d')             AS d1,
       STR_TO_DATE('13/07/2025 15:30', '%d/%m/%Y %H:%i') AS d2,
       STR_TO_DATE('2025년 07월 01일', '%Y년 %m월 %d일') AS d3_ok,
       STR_TO_DATE('2025년 07월', '%Y년 %m월')           AS d4_null;
```

**결과**
```
+------------+---------------------+------------+---------+
| d1         | d2                  | d3_ok      | d4_null |
+------------+---------------------+------------+---------+
| 2025-07-13 | 2025-07-13 15:30:00 | 2025-07-01 | NULL    |
+------------+---------------------+------------+---------+
```

> ⚠️ **함정**: `d4_null` 은 "일(日)"이 없어 `2025-07-00` 이 되는데, 서버가 `NO_ZERO_DATE` 모드라 **NULL + 경고**로 처리합니다. 파싱 포맷에 필수 요소가 빠지면 조용히 NULL 이 되니, 변환 후 `IS NULL` 검사를 잊지 마세요.

### 주차 / 분기 / EXTRACT / LAST_DAY

```sql
SELECT order_date,
       QUARTER(order_date)                 AS q,
       WEEK(order_date, 3)                 AS iso_week,   -- 모드 3 = ISO 8601 주차
       EXTRACT(YEAR_MONTH FROM order_date) AS ym,
       LAST_DAY(order_date)                AS month_end,
       DAY(LAST_DAY(order_date))           AS days_in_month
FROM orders ORDER BY order_id LIMIT 2;
```

**결과**
```
+---------------------+------+----------+--------+------------+---------------+
| order_date          | q    | iso_week | ym     | month_end  | days_in_month |
+---------------------+------+----------+--------+------------+---------------+
| 2024-02-07 13:07:00 |    1 |        6 | 202402 | 2024-02-29 |            29 |
| 2024-03-15 02:14:00 |    1 |       11 | 202403 | 2024-03-31 |            31 |
+---------------------+------+----------+--------+------------+---------------+
```

> 💡 **실무 팁**: `WEEK()` 는 모드에 따라 결과가 달라집니다(일요일 시작/월요일 시작, 1주차 정의). 리포트에서 주차를 쓸 땐 **모드를 명시**(보통 ISO 8601 인 모드 3)하지 않으면 서버 설정에 따라 값이 흔들립니다. `LAST_DAY` 는 월말 구하기와 "그 달의 일수" 계산에 유용합니다.

---

## 12-4. 조건 함수

### IF / IFNULL / COALESCE / NULLIF

```sql
SELECT customer_id, phone,
       IFNULL(phone, '(없음)')                   AS ifnull_,
       COALESCE(phone, '(없음)')                 AS coalesce_
FROM customers WHERE phone IS NULL LIMIT 2;
```

**결과**
```
+-------------+-------+----------+-----------+
| customer_id | phone | ifnull_  | coalesce_ |
+-------------+-------+----------+-----------+
|           7 | NULL  | (없음)   | (없음)    |
|          14 | NULL  | (없음)   | (없음)    |
+-------------+-------+----------+-----------+
```

`IFNULL` 은 인자 2개 전용, `COALESCE` 는 **인자 여러 개 중 첫 번째 NULL 아닌 값**을 돌려줍니다(표준 SQL). 셋 이상을 순서대로 시도하려면 `COALESCE` 를 쓰세요.

`NULLIF(a, b)` 는 `a = b` 면 NULL, 아니면 `a`. **0으로 나누기 방지**의 정석입니다.

```sql
SELECT 10 / NULLIF(0, 0) AS safe_div,   -- 0으로 나누기 → 에러 대신 NULL
       NULLIF('A','A')   AS same_null,
       NULLIF('A','B')   AS diff_a;
```

**결과**
```
+----------+-----------+--------+
| safe_div | same_null | diff_a |
+----------+-----------+--------+
|     NULL | NULL      | A      |
+----------+-----------+--------+
```

### CASE — 단순형과 검색형

```sql
SELECT grade,
       CASE grade                              -- 단순 CASE (값 비교)
           WHEN 'VIP' THEN '★★★' WHEN 'GOLD' THEN '★★'
           WHEN 'SILVER' THEN '★' ELSE '·'
       END AS stars,
       CASE                                    -- 검색 CASE (조건식)
           WHEN points >= 10000 THEN '1만+'
           WHEN points >= 1000  THEN '1천+'
           ELSE '소액'
       END AS point_band
FROM customers ORDER BY points DESC LIMIT 4;
```

**결과**
```
+-------+-----------+------------+
| grade | stars     | point_band |
+-------+-----------+------------+
| VIP   | ★★★       | 1만+       |
| VIP   | ★★★       | 1만+       |
| VIP   | ★★★       | 1만+       |
| VIP   | ★★★       | 1만+       |
+-------+-----------+------------+
```

### CASE + 집계 = 조건부 카운트 (피벗의 기초)

```sql
SELECT
    SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered,
    SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
    SUM(CASE WHEN status = 'PENDING'   THEN 1 ELSE 0 END) AS pending,
    COUNT(*) AS total
FROM orders;
```

**결과**
```
+-----------+-----------+---------+-------+
| delivered | cancelled | pending | total |
+-----------+-----------+---------+-------+
|       240 |        60 |      60 |   600 |
+-----------+-----------+---------+-------+
```

> 💡 **실무 팁**: `SUM(CASE WHEN 조건 THEN 1 ELSE 0 END)` 는 행을 열로 돌리는 **수동 피벗**의 핵심 패턴입니다. `COUNT(CASE WHEN 조건 THEN 1 END)`(ELSE 없이 NULL) 로도 같은 결과를 얻습니다 — COUNT 는 NULL 을 세지 않으니까요.

---

## 12-5. 형변환 : CAST / CONVERT

```sql
SELECT
    CAST('2025-07-13' AS DATE)     AS as_date,
    CAST(3.14159 AS DECIMAL(10,2)) AS as_decimal,
    CONVERT('123', SIGNED)         AS as_int;
```

**결과**
```
+------------+------------+--------+
| as_date    | as_decimal | as_int |
+------------+------------+--------+
| 2025-07-13 |       3.14 |    123 |
+------------+------------+--------+
```

### 암묵적 형변환의 함정

MySQL 은 타입이 다르면 알아서 변환해 비교합니다. 편리하지만 위험합니다.

```sql
SELECT '10' = 10    AS str_eq_num,   -- 1 : 문자열이 숫자로 변환됨
       '10abc' = 10 AS partial,      -- 1 : 앞 숫자만 취함 + 경고
       'abc' = 0    AS text_eq_zero; -- 1 : 숫자 변환 실패 → 0 취급
```

**결과**
```
+------------+---------+--------------+
| str_eq_num | partial | text_eq_zero |
+------------+---------+--------------+
|          1 |       1 |            1 |
+------------+---------+--------------+
```
```
Warning | 1292 | Truncated incorrect DOUBLE value: '10abc'
Warning | 1292 | Truncated incorrect DOUBLE value: 'abc'
```

> ⚠️ **함정**: `'abc' = 0` 이 **참**입니다. 문자열이 숫자로 변환되다 실패하면 0이 되기 때문입니다. `WHERE code = 0` 처럼 숫자 리터럴을 쓰면 문자열 코드가 전부 걸려들 수 있습니다. **타입을 맞춰서 비교**하세요(문자열은 따옴표로).

---

## 12-6. ★ 컬럼에 함수를 씌우면 인덱스를 못 탄다

이 절이 이 스텝에서 **성능상 가장 중요**합니다. 함수 자체는 잘못이 없지만, **인덱스가 걸린 컬럼을 함수로 감싸는 순간** 그 인덱스는 무력화됩니다. 인덱스는 "컬럼의 원본 값"으로 정렬돼 있는데, 함수를 씌우면 값이 바뀌어 정렬이 소용없어지기 때문입니다.

`orders.customer_id` 에는 인덱스(`idx_orders_customer`)가 있습니다. 여기에 연산을 씌워 봅시다.

```sql
-- 나쁜 예 : 컬럼에 연산
EXPLAIN SELECT * FROM orders WHERE customer_id + 0 = 5;
```

**결과**
```
+------+---------------+------+------+------+-------------+
| type | possible_keys | key  | ref  | rows | Extra       |
+------+---------------+------+------+------+-------------+
| ALL  | NULL          | NULL | NULL |  600 | Using where |
+------+---------------+------+------+------+-------------+
```

`type=ALL` — **풀 테이블 스캔** 600행 전부를 읽습니다. `key=NULL`, 인덱스를 안 씁니다.

```sql
-- 좋은 예 : 값 쪽을 그대로 둔다
EXPLAIN SELECT * FROM orders WHERE customer_id = 5;
```

**결과**
```
+------+---------------------+---------------------+-------+------+-------+
| type | possible_keys       | key                 | ref   | rows | Extra |
+------+---------------------+---------------------+-------+------+-------+
| ref  | idx_orders_customer | idx_orders_customer | const |   20 | NULL  |
+------+---------------------+---------------------+-------+------+-------+
```

`type=ref`, `key=idx_orders_customer` — 인덱스로 **20행만** 콕 집어 읽습니다. `+ 0` 하나 붙였을 뿐인데 600행 vs 20행입니다.

### 날짜에서 가장 자주 저지르는 실수

```sql
-- 나쁜 예 : 흔히 이렇게 쓴다
EXPLAIN SELECT * FROM orders WHERE YEAR(order_date) = 2025;
```
```sql
-- 좋은 예 : 범위 조건으로 바꾼다
EXPLAIN SELECT * FROM orders WHERE order_date >= '2025-01-01' AND order_date < '2026-01-01';
```

`YEAR(order_date) = 2025` 는 모든 행의 `order_date` 에 `YEAR()` 를 적용해 봐야 하므로 인덱스를 못 씁니다. 아래처럼 **컬럼은 건드리지 않고 값을 범위로** 주면, `order_date` 에 인덱스가 있을 경우 그 인덱스를 탈 수 있습니다.

> 참고: 이 스키마의 `orders.order_date` 에는 인덱스가 없어서 두 쿼리 모두 EXPLAIN 상 풀 스캔으로 나옵니다. 하지만 **"컬럼을 함수로 감싸지 않는다"는 원칙은 인덱스 유무와 무관하게** 지켜야 합니다. 지금은 인덱스가 없지만, 나중에 누군가 `order_date` 에 인덱스를 추가하면 범위 조건 쿼리만 이득을 봅니다.

문자열도 마찬가지입니다.

```sql
EXPLAIN SELECT * FROM customers WHERE SUBSTRING(email, 1, 3) = 'kim';  -- 나쁜 예: type=ALL
EXPLAIN SELECT * FROM customers WHERE email LIKE 'kim%';               -- 좋은 예: type=range
```

`email` 에는 UNIQUE 인덱스가 있습니다. `SUBSTRING(...)='kim'` 은 풀 스캔이지만, **접두(prefix) LIKE `'kim%'`** 는 인덱스 범위 스캔을 씁니다. 단, `LIKE '%kim'`(앞에 `%`)은 접두를 특정할 수 없어 인덱스를 못 탑니다.

### 정말 함수로 검색해야 한다면 : 함수 기반 인덱스 (8.0.13+)

MySQL 8.0.13 부터는 **표현식에 인덱스**를 걸 수 있습니다. 그러면 그 함수를 쓴 조건도 인덱스를 탑니다.

```sql
-- (원본 테이블 변경 금지이므로 문법만 소개)
ALTER TABLE orders ADD INDEX idx_order_year ((YEAR(order_date)));
-- → 이후 WHERE YEAR(order_date) = 2025 가 인덱스를 사용한다
```

> 💡 **실무 팁**: 원칙은 "**컬럼을 감싸지 말고 값을 가공하라**" 입니다. 그게 불가능한 경우(예: 대소문자 무시 검색, 특정 표현식 반복 조회)에만 함수 기반 인덱스나 생성 컬럼(generated column)을 고려하세요. 인덱스를 늘리면 쓰기가 느려지므로 공짜가 아닙니다.

---

## 정리

| 분류 | 함수 | 핵심 / 함정 |
|---|---|---|
| 문자열 | `CONCAT` vs `CONCAT_WS` | CONCAT 은 **NULL 하나면 전체 NULL**; WS 는 건너뜀 |
| | `SUBSTRING_INDEX` | `@` 앞/뒤 자르기, 음수=뒤에서 |
| | `LENGTH` vs `CHAR_LENGTH` | 바이트 vs 글자(한글=3바이트) |
| | `REGEXP_REPLACE` 등 | **8.0+** 정규식 치환/추출 |
| 숫자 | `ROUND` vs `TRUNCATE` | 반올림 vs 버림, 음수 자릿수 가능 |
| | `NULLIF(x,0)` | 0으로 나누기 방지 |
| 날짜 | `DATEDIFF` vs `TIMESTAMPDIFF` | 일 수 vs 단위지정(**인자 순서 반대**) |
| | `DATE_FORMAT`/`STR_TO_DATE` | 상호 변환. 필수 요소 빠지면 NULL |
| | `LAST_DAY`/`WEEK(d,3)` | 월말/ISO 주차(**모드 명시**) |
| 조건 | `COALESCE` | 여러 인자 중 첫 non-NULL |
| | `CASE`+`SUM` | 조건부 카운트 = 수동 피벗 |
| 형변환 | `CAST`/`CONVERT` | `'abc'=0` 이 참 → **타입 맞춰 비교** |
| 성능 | **컬럼에 함수 금지** | 인덱스 무력화. 값을 가공하거나 함수 기반 인덱스(8.0.13+) |

---

## 연습문제

`exercise.sql` 을 푸세요. 정답은 `solution.sql`.

1. 이메일에서 도메인만 추출하고, 로컬 파트를 대문자로
2. 전화번호 뒤 4자리만 남기고 마스킹 (REGEXP_REPLACE)
3. 상품 마진율을 소수 1자리로, 버림(TRUNCATE)과 반올림(ROUND) 둘 다
4. 각 주문의 "주문~결제 소요 분"을 구하고 60분 이상만
5. 고객을 나이대(20대/30대/…)로 분류 (CASE + TIMESTAMPDIFF)
6. 월별 매출을 `YYYY년 MM월` 형식 라벨로 집계
7. `COALESCE` 로 phone 이 없으면 '연락처없음' 표시
8. `YEAR(order_date)=2025` 를 인덱스 친화적인 범위 조건으로 다시 쓰기

---

## 다음 단계

→ Step 13 — 윈도우 함수 (예정)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 진행합니다. 먼저 `practice.sql` 을 실행해 본문 12-1 ~ 12-6 의 예제(문자열 → 숫자 → 날짜 → 조건 → 형변환 → EXPLAIN)를 순서대로 눈으로 확인하고, 그다음 `exercise.sql` 의 빈칸을 스스로 채워 8문제를 풉니다. 막히거나 다 풀었으면 `solution.sql` 로 답과 해설을 대조합니다. 세 파일 모두 `USE shop;` 으로 시작하며 **SELECT / EXPLAIN 만** 수행하므로 원본 데이터를 건드리지 않습니다.

### practice.sql

본문에 나온 예제를 한 파일에 모아 둔 **따라 하기용 스크립트**입니다. `[12-1]` 부터 `[12-35]` 까지 번호가 붙어 있어 본문 절 번호와 나란히 읽을 수 있습니다.

- 실행법은 파일 상단 주석 그대로입니다: `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql`. 포트가 **3307** 인 점(로컬 학습용 컨테이너)에 주의하세요.
- 본문보다 예제가 조금 더 많습니다. `[12-5]` `LOCATE`/`INSTR`(못 찾으면 0), `[12-6]` `UPPER`/`LOWER`, `[12-8]` `REGEXP_LIKE`/`REGEXP_SUBSTR`, `[12-11]` `ABS`/`MOD`/`POWER`/`SQRT`, `[12-21]` `DATE_FORMAT` 을 이용한 월별 집계, `[12-22]` `IF` 는 본문에 표로만 언급된 것들이니 파일에서 실제 결과를 확인해 보세요.
- `[12-2]` 는 `WHERE phone IS NULL` 로 골라낸 행에 `CONCAT('전화: ', phone)` 과 `CONCAT_WS(' ', '전화:', phone)` 를 나란히 찍어, 전자가 통째로 NULL 이 되는 것을 눈으로 확인시킵니다. 12-1 절의 "함정" 박스와 같은 내용이되, `customer_id`, `phone` 컬럼을 함께 보여 주고 3행까지 출력합니다.
- `[12-18]` 의 `d4_null` 은 `STR_TO_DATE('2025년 07월', '%Y년 %m월')` 로 **일(日)이 빠져** NULL 이 되는 예입니다. 바로 뒤에 `SHOW WARNINGS;` 를 붙여 두었으니 경고 메시지까지 함께 보세요. `[12-28]` 의 암묵적 형변환(`'abc' = 0` → 1) 뒤에도 같은 이유로 `SHOW WARNINGS;` 가 있습니다.
- `[12-29]` ~ `[12-34]` 가 이 스텝의 핵심입니다. `customer_id + 0 = 5`(type=ALL) 와 `customer_id = 5`(type=ref, key=`idx_orders_customer`) 를 EXPLAIN 으로 비교합니다. `+ 0` 하나 차이로 600행 풀 스캔과 20행 인덱스 조회가 갈립니다.
- `[12-35]` 의 함수 기반 인덱스 `ALTER TABLE ... ADD INDEX idx_order_year ((YEAR(order_date)))` 는 **주석 처리되어 있습니다.** 원본 테이블을 변경하지 않기 위한 의도이니 주석을 풀지 마세요.

```sql file="./practice.sql"
```

### exercise.sql

연습문제 8개가 **주석만 있고 답이 비어 있는** 스켈레톤 파일입니다. 본문 "연습문제" 절의 1~8번과 그대로 대응하며, 각 문제 블록 아래 빈 줄에 직접 SQL 을 써 넣고 실행해 보는 방식입니다.

- Q1~Q3 은 문자열·숫자 함수(`SUBSTRING_INDEX`, `UPPER`, `REGEXP_REPLACE`, `ROUND` vs `TRUNCATE`)를, Q4~Q6 은 날짜 함수(`TIMESTAMPDIFF`, `DATE_FORMAT`)와 `CASE` 를, Q7 은 `COALESCE` 를, Q8 은 인덱스 친화적 조건 작성을 다룹니다.
- 문제마다 **출력 컬럼명과 정렬·행 수까지 지정**되어 있습니다(예: Q3 "컬럼: product_id, name, margin_round, margin_trunc / margin_round DESC 상위 5행"). 정답 대조가 쉬워지므로 지시대로 별칭을 맞추세요.
- Q2 는 `010-1000-0001 → ***-****-0001` 처럼 **하이픈은 남기고 앞쪽 숫자만** 가려야 해서 `REGEXP_REPLACE` 한 번으로는 잘 풀리지 않습니다. 의도된 난관이며, 해법은 `solution.sql` 의 A2 를 보세요.
- Q8 은 "(a) 인덱스를 못 타는 나쁜 버전을 **주석으로** 쓰고 (b) 범위 조건 버전을 실제로 작성"하라고 요구합니다. 12-6 절의 원칙을 손으로 재현해 보는 문제입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 의 정답과 **해설 주석**입니다. 먼저 스스로 풀어 본 뒤에 여세요.

- A2 가 가장 배울 것이 많습니다. 하이픈이 숫자 사이에 끼어 있어 정규식만으로는 까다로우므로, `LEFT(phone, CHAR_LENGTH(phone) - 4)` 로 앞부분을 떼어 `[0-9]` 를 `*` 로 치환하고 `RIGHT(phone, 4)` 를 `CONCAT` 으로 다시 붙입니다. 여기서 `LENGTH` 가 아니라 **`CHAR_LENGTH`** 를 쓴 이유는 12-1 절의 바이트 vs 글자 함정과 같습니다.
- A4 의 주석은 데이터 생성 규칙까지 알려 줍니다 — `paid_at` 이 `order_date + (5 + order_id%120)` 분이라 소요 시간이 **최대 124분**까지만 나옵니다. 결과가 그 이상이면 뭔가 잘못 짠 것입니다.
- A6 은 `GROUP BY ym_label` 로 묶되 `ORDER BY MIN(order_date)` 로 정렬합니다. 라벨 문자열이 아니라 **실제 날짜로 정렬**해야 안전하다는 점(`'%Y년 %m월'` 은 우연히 사전순=시간순일 뿐)을 짚어 줍니다.
- A8 은 나쁜 버전(`YEAR(order_date) = 2025`)을 실행하지 않고 주석으로만 두고, 범위 조건 버전과 `customer_id` 기반 EXPLAIN 두 줄로 원리를 확인시킵니다. `orders.order_date` 에는 인덱스가 없어 둘 다 풀 스캔이지만 원칙은 동일하다는 설명이 주석에 담겨 있습니다.

```sql file="./solution.sql"
```
