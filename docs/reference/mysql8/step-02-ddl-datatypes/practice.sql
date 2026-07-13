-- =====================================================================
-- Step 02 practice.sql — 데이터베이스·테이블·데이터 타입
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
--
-- 주의: 모든 실습 테이블은 s02_ 접두사를 씁니다. 예제 데이터는 건드리지 않습니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [2-1] 데이터베이스
-- ---------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS playground
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

SHOW DATABASES;

DROP DATABASE playground;   -- 되돌릴 수 없습니다
USE shop;


-- ---------------------------------------------------------------------
-- [2-2] DECIMAL vs FLOAT — 돈에 FLOAT 를 쓰면 안 되는 이유
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s02_types;
CREATE TABLE s02_types (
  c_dec DECIMAL(10,2),   -- 고정소수점: 정확
  c_flt FLOAT,           -- 부동소수점: 근사값
  c_dbl DOUBLE           -- 부동소수점: 근사값
);
INSERT INTO s02_types VALUES (0.1, 0.1, 0.1), (0.2, 0.2, 0.2);

-- 0.1 + 0.2 = 0.3 이어야 하는데...
SELECT SUM(c_dec) AS dec_sum,
       SUM(c_flt) AS flt_sum,
       SUM(c_dbl) AS dbl_sum,
       SUM(c_dec) = 0.3 AS dec_eq_03,   -- 1 (참)
       SUM(c_dbl) = 0.3 AS dbl_eq_03    -- 0 (거짓!)
FROM s02_types;


-- ---------------------------------------------------------------------
-- [2-3] CHAR 는 뒤 공백을 조용히 삼킨다
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s02_char;
CREATE TABLE s02_char (a CHAR(5), b VARCHAR(5));
INSERT INTO s02_char VALUES ('ab   ', 'ab   ');   -- 둘 다 'ab' + 공백 3칸

SELECT CONCAT('[', a, ']') AS char_col,      -- [ab]    ← 공백 사라짐
       CONCAT('[', b, ']') AS varchar_col,   -- [ab   ] ← 공백 유지
       LENGTH(a) AS char_len,                -- 2
       LENGTH(b) AS varchar_len              -- 5
FROM s02_char;

-- [2-3] ENUM 은 선언 순서대로 정렬된다 (알파벳순 아님!)
SELECT status, COUNT(*) AS cnt
FROM products
GROUP BY status
ORDER BY status;   -- ON_SALE, SOLD_OUT, HIDDEN 순 (선언 순서)


-- ---------------------------------------------------------------------
-- [2-4] DATETIME vs TIMESTAMP — 타임존
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s02_time;
CREATE TABLE s02_time (id INT, dt DATETIME, ts TIMESTAMP);
INSERT INTO s02_time VALUES (1, '2026-07-13 10:00:00', '2026-07-13 10:00:00');

SELECT 'KST(+09:00)' AS session_tz, dt, ts FROM s02_time;

SET SESSION time_zone = '+00:00';
SELECT 'UTC(+00:00)' AS session_tz, dt, ts FROM s02_time;   -- ts 만 09:00 → 01:00

SET SESSION time_zone = '+09:00';   -- 원복


-- ---------------------------------------------------------------------
-- [2-5] 테이블 만들기 (전체 문법)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s02_orders;
CREATE TABLE s02_orders (
  order_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no     CHAR(16)        NOT NULL COMMENT '외부 노출용 주문번호',
  customer_id  INT UNSIGNED    NOT NULL,
  amount       DECIMAL(12,2)   NOT NULL DEFAULT 0,
  status       ENUM('PENDING','PAID','CANCELLED') NOT NULL DEFAULT 'PENDING',
  memo         VARCHAR(200)    NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (order_id),
  UNIQUE  KEY uk_s02_orders_no (order_no),
  KEY     idx_s02_orders_customer (customer_id),
  CONSTRAINT chk_s02_orders_amount CHECK (amount >= 0),
  CONSTRAINT fk_s02_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
)
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COMMENT='Step02 실습용 주문';

-- ON UPDATE CURRENT_TIMESTAMP 확인
INSERT INTO s02_orders (order_no, customer_id, amount) VALUES ('ORD-2026-000001', 1, 50000);
SELECT order_id, created_at, updated_at FROM s02_orders;

DO SLEEP(1);
UPDATE s02_orders SET amount = 60000 WHERE order_id = 1;
SELECT order_id, created_at, updated_at FROM s02_orders;   -- updated_at 만 갱신됨


-- ---------------------------------------------------------------------
-- [2-6] 스키마 조회
-- ---------------------------------------------------------------------
-- 서버가 실제로 이해하고 있는 정의 (CLI 에서는 뒤에 \G 를 붙이면 읽기 좋습니다)
SHOW CREATE TABLE products;

DESCRIBE customers;

-- information_schema: SQL 로 가공 가능한 메타데이터
SELECT
  table_name,
  table_rows                            AS approx_rows,   -- 추정값! 정확하지 않음
  ROUND(data_length  / 1024 / 1024, 2)  AS data_mb,
  ROUND(index_length / 1024 / 1024, 2)  AS index_mb,
  table_comment
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_type = 'BASE TABLE'
ORDER BY data_length DESC;

-- 컬럼 메타데이터
SELECT table_name, column_name, column_type, is_nullable, column_default, extra
FROM information_schema.columns
WHERE table_schema = 'shop' AND table_name = 'orders'
ORDER BY ordinal_position;


-- ---------------------------------------------------------------------
-- [2-7] ALTER TABLE
-- ---------------------------------------------------------------------
ALTER TABLE s02_orders ADD COLUMN coupon_code VARCHAR(20) NULL AFTER memo;
ALTER TABLE s02_orders MODIFY COLUMN memo VARCHAR(500) NULL;          -- 타입 변경
ALTER TABLE s02_orders CHANGE COLUMN memo note VARCHAR(500) NULL;     -- 이름+타입 변경
ALTER TABLE s02_orders RENAME COLUMN note TO memo;                    -- 8.0: 이름만 변경
ALTER TABLE s02_orders DROP COLUMN coupon_code;

-- 여러 변경은 반드시 한 문장으로 묶으세요 (테이블 재구성 횟수를 줄임)
ALTER TABLE s02_orders
  ADD COLUMN a INT NULL,
  ADD COLUMN b INT NULL,
  ADD COLUMN c INT NULL;

ALTER TABLE s02_orders DROP COLUMN a, DROP COLUMN b, DROP COLUMN c;

-- 온라인 DDL 이 불가능하면 "조용히 테이블 복사" 대신 에러를 내게 만들기
ALTER TABLE s02_orders
  ADD COLUMN memo2 VARCHAR(100) NULL,
  ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE s02_orders DROP COLUMN memo2;

SHOW CREATE TABLE s02_orders;


-- ---------------------------------------------------------------------
-- [2-8] 정리
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s02_orders;
DROP TABLE IF EXISTS s02_types;
DROP TABLE IF EXISTS s02_time;
DROP TABLE IF EXISTS s02_char;

SELECT 'Step 02 practice 완료' AS msg;
