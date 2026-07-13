-- =====================================================================
-- Step 02 solution.sql — 연습문제 정답
-- =====================================================================
USE shop;

-- ── 문제 1. s02_coupons 설계
DROP TABLE IF EXISTS s02_coupons;
CREATE TABLE s02_coupons (
  coupon_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '수억 장 발행 예정 → INT(21억) 아님, BIGINT',
  code          CHAR(16)        NOT NULL COMMENT '길이가 항상 16 → VARCHAR 아닌 CHAR',
  discount_type ENUM('RATE','AMOUNT') NOT NULL COMMENT '값 집합이 고정 → ENUM',
  discount_rate DECIMAL(5,2)    NULL COMMENT '0.00 ~ 100.00. 정률일 때만',
  discount_amt  DECIMAL(12,2)   NULL COMMENT '정액일 때만. 돈이므로 DECIMAL',
  min_order_amt DECIMAL(12,2)   NOT NULL DEFAULT 0,
  valid_from    DATETIME        NOT NULL,
  valid_to      DATETIME        NOT NULL COMMENT 'TIMESTAMP 아님! 2038년 이후 만료일도 있을 수 있음',
  is_used       TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'BOOLEAN = TINYINT(1)',
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (coupon_id),
  UNIQUE KEY uk_s02_coupons_code (code),
  KEY idx_s02_coupons_valid (valid_to),

  -- 정률이면 rate 가, 정액이면 amt 가 반드시 있어야 한다 (CHECK 는 8.0.16+ 부터 진짜로 강제됩니다)
  CONSTRAINT chk_s02_coupons_discount CHECK (
    (discount_type = 'RATE'   AND discount_rate IS NOT NULL AND discount_rate BETWEEN 0 AND 100)
    OR
    (discount_type = 'AMOUNT' AND discount_amt  IS NOT NULL AND discount_amt >= 0)
  )
) ENGINE=InnoDB COMMENT='쿠폰';

-- 해설 포인트 4가지
--  1) 할인금액에 FLOAT 를 쓰면 정산이 어긋납니다. → DECIMAL
--  2) 쿠폰 ID 는 INT(21억)로 시작했다가 나중에 BIGINT 로 바꾸려면 테이블 전체 재작성입니다. 처음부터 BIGINT.
--  3) valid_to 를 TIMESTAMP 로 하면 2038-01-19 이후 만료일을 저장할 수 없습니다. → DATETIME
--  4) CHECK 제약으로 "정률인데 rate 가 NULL" 같은 모순 데이터를 DB 레벨에서 차단합니다.

-- CHECK 가 실제로 막는지 확인
-- INSERT INTO s02_coupons (code, discount_type, valid_from, valid_to)
-- VALUES ('WELCOME000000001', 'RATE', '2026-01-01', '2026-12-31');
-- → ERROR 3819 (HY000): Check constraint 'chk_s02_coupons_discount' is violated.  (rate 가 NULL 이므로)

INSERT INTO s02_coupons (code, discount_type, discount_rate, valid_from, valid_to)
VALUES ('WELCOME000000001', 'RATE', 10.00, '2026-01-01 00:00:00', '2026-12-31 23:59:59');
SELECT coupon_id, code, discount_type, discount_rate, valid_to, is_used FROM s02_coupons;


-- ── 문제 2. NULL 을 허용하는 컬럼
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = 'shop'
  AND is_nullable = 'YES'
  AND table_name IN ('customers','products','orders','order_items','payments','reviews','categories','employees')
ORDER BY table_name, ordinal_position;
-- 결과:
--   categories.parent_id  (최상위 카테고리는 부모가 없음 → NULL 이 의미를 가짐)
--   customers.phone       (전화번호 미입력 가능)
--   customers.birth_date
--   employees.manager_id  (CEO 는 상사가 없음)
--   products.attrs        (JSON, 속성 없는 상품 존재)
--   reviews.title / reviews.body
-- 해설: NULL 을 허용한 컬럼은 "값이 없을 수 있다"는 비즈니스 의미가 있어야 합니다.
--       습관적으로 NULL 허용을 남발하면 Step 05 에서 배울 3값 논리 함정에 계속 걸립니다.


-- ── 문제 3. DECIMAL 컬럼 목록
SELECT table_name, column_name,
       numeric_precision AS total_digits,
       numeric_scale     AS decimal_digits,
       column_type
FROM information_schema.columns
WHERE table_schema = 'shop' AND data_type = 'decimal'
ORDER BY table_name, ordinal_position;
-- 결과: employees.salary(10,2), order_items.unit_price(10,2), orders.total_amount(12,2),
--       payments.amount(12,2), products.price(10,2), products.cost(10,2)
-- 해설: 이 코스의 모든 금액 컬럼이 DECIMAL 인 것을 확인하세요. 의도적인 설계입니다.


-- ── 문제 4. 온라인 DDL 강제
ALTER TABLE s02_coupons
  ADD COLUMN used_count INT NOT NULL DEFAULT 0,
  ALGORITHM=INPLACE, LOCK=NONE;
-- 해설: ALGORITHM/LOCK 을 명시하지 않으면 MySQL 이 알아서 고릅니다.
--       그런데 "알아서"가 COPY(테이블 전체 재작성)일 수 있고, 1억 행 테이블에서는 몇 시간 락입니다.
--       명시해 두면 온라인 처리가 불가능할 때 실행되는 대신 에러가 나므로, 사고를 사전에 막습니다.
--       참고: 단순 컬럼 추가는 8.0.12+ 에서 ALGORITHM=INSTANT 로 즉시 끝납니다.
ALTER TABLE s02_coupons
  ADD COLUMN memo VARCHAR(100) NULL,
  ALGORITHM=INSTANT;


-- ── 문제 5. TIMESTAMP 에 2039년
DROP TABLE IF EXISTS s02_ts_test;
CREATE TABLE s02_ts_test (ts TIMESTAMP NULL, dt DATETIME NULL);

-- INSERT INTO s02_ts_test (ts) VALUES ('2039-01-01 00:00:00');
-- → ERROR 1292 (22007): Incorrect datetime value: '2039-01-01 00:00:00' for column 'ts' at row 1

INSERT INTO s02_ts_test (dt) VALUES ('2039-01-01 00:00:00');   -- DATETIME 은 OK
SELECT dt FROM s02_ts_test;
-- 해설: TIMESTAMP 는 1970-01-01 부터의 초를 32비트 정수로 저장합니다.
--       2^31 초 후인 2038-01-19 03:14:07 UTC 에서 오버플로우합니다. ("2038년 문제")
--       구독 만료일, 보증기간, 예약일처럼 먼 미래를 다루는 컬럼에 TIMESTAMP 를 쓰면 안 됩니다.


-- ── 문제 6. CHAR(3) 에 4글자
DROP TABLE IF EXISTS s02_char_test;
CREATE TABLE s02_char_test (c CHAR(3));

-- (a) 엄격 모드(기본): 아예 거부
-- INSERT INTO s02_char_test VALUES ('abcd');
-- → ERROR 1406 (22001): Data too long for column 'c' at row 1

-- (b) 엄격 모드를 끄면: 경고만 내고 조용히 잘라서 저장
SET SESSION sql_mode = '';
INSERT INTO s02_char_test VALUES ('abcd');
SHOW WARNINGS;
-- +---------+------+----------------------------------------+
-- | Level   | Code | Message                                |
-- | Warning | 1265 | Data truncated for column 'c' at row 1  |
-- +---------+------+----------------------------------------+
SELECT CONCAT('[', c, ']') AS saved_value FROM s02_char_test;   -- [abc]  ← 'd' 가 사라짐!

SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- 해설: 이것이 엄격 모드를 절대 끄면 안 되는 이유입니다.
--       엄격 모드가 없으면 애플리케이션은 "저장 성공"으로 알고 넘어가는데
--       DB 안의 데이터는 이미 손상돼 있습니다. 경고는 아무도 안 봅니다.
--       "에러가 나서 불편하다"는 것은, 사실 DB 가 당신의 버그를 잡아준 것입니다.
-- 참고: 위 SELECT 에서 별칭을 'stored' 로 쓰면 문법 에러가 납니다. STORED 는 MySQL 8 예약어입니다
--       (생성 컬럼에서 씁니다 → Step 14). 예약어를 별칭으로 쓰려면 백틱으로 감싸세요: AS `stored`


-- ── 문제 7. ENUM 에 값 추가
DROP TABLE IF EXISTS s02_orders_copy;
CREATE TABLE s02_orders_copy LIKE orders;

ALTER TABLE s02_orders_copy
  MODIFY COLUMN status ENUM('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED','REFUNDED')
  NOT NULL DEFAULT 'PENDING';

SHOW CREATE TABLE s02_orders_copy;
-- 해설: ENUM 값 추가는 MODIFY COLUMN 으로 목록 전체를 다시 써야 합니다.
--       ★ 새 값은 반드시 "맨 뒤에" 추가하세요. 중간에 끼워 넣으면 기존 데이터의 내부 정수값이
--         전부 밀려서 테이블 전체를 재작성해야 하고, 최악의 경우 데이터가 뒤바뀝니다.
--       ★ 값이 자주 늘어나는 도메인이라면 ENUM 대신 코드 테이블 + FK 를 쓰세요.
--         (ALTER 없이 INSERT 한 줄로 값을 추가할 수 있습니다)


-- ── 정리
DROP TABLE IF EXISTS s02_coupons;
DROP TABLE IF EXISTS s02_ts_test;
DROP TABLE IF EXISTS s02_char_test;
DROP TABLE IF EXISTS s02_orders_copy;

SELECT 'Step 02 solution 완료' AS msg;
