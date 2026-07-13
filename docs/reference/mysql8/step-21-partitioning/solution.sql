-- =====================================================================
-- Step 21 — 파티셔닝 : solution.sql (정답)
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t --force < solution.sql
-- 전제: practice.sql 을 먼저 실행한 상태
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] 2024년 5월 → p2024_05 하나만 열린다
-- ---------------------------------------------------------------------
EXPLAIN SELECT COUNT(*) FROM s21_access_logs
WHERE logged_at >= '2024-05-01' AND logged_at < '2024-06-01';
-- partitions: p2024_05


-- ---------------------------------------------------------------------
-- [정답 2] DATE(logged_at) 은 함수 → 프루닝 불가 (전체 파티션)
--          → 범위 조건으로 바꾸면 p2024_05 만 열린다
-- ---------------------------------------------------------------------
EXPLAIN SELECT COUNT(*) FROM s21_access_logs WHERE DATE(logged_at) = '2024-05-05';
-- partitions: p2024_02,...,pmax  (전부)

EXPLAIN SELECT COUNT(*) FROM s21_access_logs
WHERE logged_at >= '2024-05-05' AND logged_at < '2024-05-06';
-- partitions: p2024_05
--
-- 교훈: 파티션 키에 함수를 씌우는 순간 프루닝은 죽는다.
--       (인덱스가 함수 때문에 안 먹는 것과 똑같은 원리)
--       예외는 TO_DAYS / YEAR / TO_SECONDS 뿐이고, 그것도 파티션 식이
--       같은 함수로 정의돼 있을 때만이다.


-- ---------------------------------------------------------------------
-- [정답 3] LIST 파티션
--   access_logs 의 status_code 는 200, 304, 400, 404, 500, 503 6종류다.
--   LIST 는 미정의 값이 들어오면 ERROR 1526 이므로 여유 있게 열거해 둔다.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s21_ex_status;
CREATE TABLE s21_ex_status (
  log_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  status_code SMALLINT NOT NULL,
  path        VARCHAR(100) NOT NULL,
  logged_at   DATETIME NOT NULL,
  PRIMARY KEY (log_id, status_code)      -- 파티션 키 포함 필수
) ENGINE=InnoDB
PARTITION BY LIST (status_code) (
  PARTITION p_ok     VALUES IN (200, 201, 204, 301, 302, 304),
  PARTITION p_client VALUES IN (400, 401, 403, 404, 429),
  PARTITION p_server VALUES IN (500, 502, 503, 504)
);

INSERT INTO s21_ex_status (status_code, path, logged_at)
SELECT status_code, path, logged_at FROM access_logs;

SELECT PARTITION_NAME, TABLE_ROWS FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_ex_status'
ORDER BY PARTITION_ORDINAL_POSITION;

EXPLAIN SELECT COUNT(*) FROM s21_ex_status WHERE status_code = 200;
-- partitions: p_ok

-- 4xx/5xx 만 보는 "에러 대시보드" 쿼리도 프루닝된다 → LIST 의 전형적 활용
EXPLAIN SELECT COUNT(*) FROM s21_ex_status WHERE status_code >= 400;
-- partitions: p_client,p_server   ← 범위 조건도 LIST 프루닝이 된다


-- ---------------------------------------------------------------------
-- [정답 4] pmax 를 REORGANIZE 로 쪼갠다 (ADD PARTITION 은 ERROR 1493)
--   주의: practice.sql 이 p2025_q1(< '2025-03-01') 을 남겨두었다.
--         그대로 pmax 를 p2025_01(< '2025-02-01') 로 쪼개면 경계가 역행해
--         ERROR 1493 (strictly increasing 위반)이 난다. 먼저 치운다.
-- ---------------------------------------------------------------------
ALTER TABLE s21_access_logs DROP PARTITION p2025_q1;

ALTER TABLE s21_access_logs REORGANIZE PARTITION pmax INTO (
  PARTITION p2025_01 VALUES LESS THAN ('2025-02-01'),
  PARTITION p2025_02 VALUES LESS THAN ('2025-03-01'),
  PARTITION p2025_03 VALUES LESS THAN ('2025-04-01'),
  PARTITION pmax     VALUES LESS THAN (MAXVALUE)
);

SELECT PARTITION_NAME, PARTITION_DESCRIPTION
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_access_logs'
ORDER BY PARTITION_ORDINAL_POSITION;


-- ---------------------------------------------------------------------
-- [정답 5] EXCHANGE PARTITION → DROP PARTITION
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS before_rows FROM s21_access_logs;

DROP TABLE IF EXISTS s21_ex_archive_04;
CREATE TABLE s21_ex_archive_04 (
  log_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  path        VARCHAR(100) NOT NULL,
  method      ENUM('GET','POST','PUT','DELETE') NOT NULL,
  status_code SMALLINT NOT NULL,
  duration_ms INT NOT NULL,
  user_agent  VARCHAR(60) NOT NULL,
  logged_at   DATETIME NOT NULL,
  PRIMARY KEY (log_id, logged_at),
  KEY idx_s21_logged_at (logged_at)
) ENGINE=InnoDB;      -- 파티션 없음 + 구조 완전 동일

ALTER TABLE s21_access_logs
  EXCHANGE PARTITION p2024_04 WITH TABLE s21_ex_archive_04;

ALTER TABLE s21_access_logs DROP PARTITION p2024_04;

SELECT COUNT(*) AS after_rows FROM s21_access_logs;
SELECT COUNT(*) AS archived   FROM s21_ex_archive_04;
-- before - after = archived 여야 한다


-- ---------------------------------------------------------------------
-- [정답 6] customers 를 created_at 으로 파티셔닝할 수 없는 이유
--   customers 에는 UNIQUE KEY uk_customers_email (email) 이 있다.
--   파티션 키(created_at)를 포함하지 않으므로 ERROR 1503.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s21_ex_customers;
CREATE TABLE s21_ex_customers (
  customer_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email       VARCHAR(120) NOT NULL,
  name        VARCHAR(50)  NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id, created_at),
  UNIQUE KEY uk_email (email)                    -- ← 파티션 키 미포함
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(created_at) (
  PARTITION p2024 VALUES LESS THAN ('2025-01-01'),
  PARTITION pmax  VALUES LESS THAN (MAXVALUE)
);
-- ERROR 1503 (HY000): A UNIQUE INDEX must include all columns in the table's
--                     partitioning function (prefixed columns are not considered).

-- "포기하면" 만들 수는 있다 — 그러나 포기하는 것이 무엇인지 보라
DROP TABLE IF EXISTS s21_ex_customers;
CREATE TABLE s21_ex_customers (
  customer_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email       VARCHAR(120) NOT NULL,
  name        VARCHAR(50)  NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id, created_at),
  UNIQUE KEY uk_email (email, created_at)        -- 파티션 키를 끼워넣음
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(created_at) (
  PARTITION p2024 VALUES LESS THAN ('2025-01-01'),
  PARTITION pmax  VALUES LESS THAN (MAXVALUE)
);

-- 하지만 이제 "이메일 유일성"이 깨진다. 같은 이메일이 두 번 들어간다!
INSERT INTO s21_ex_customers (email, name, created_at) VALUES
  ('dup@example.com', '첫번째', '2024-05-01 10:00:00'),
  ('dup@example.com', '두번째', '2025-05-01 10:00:00');   -- 성공해 버린다

SELECT email, COUNT(*) AS cnt FROM s21_ex_customers GROUP BY email;
-- +-----------------+-----+
-- | dup@example.com |   2 |   ← 중복 가입!
-- +-----------------+-----+
--
-- 결론: 비즈니스 유니크 제약(email 유일)이 있는 테이블은 파티셔닝 후보가 아니다.
--       파티셔닝은 로그/이벤트/이력처럼 "유니크 제약이 PK 하나뿐인" 테이블의 것이다.


-- ---------------------------------------------------------------------
-- 뒷정리 (원하면 실행)
-- ---------------------------------------------------------------------
-- DROP TABLE IF EXISTS s21_ex_status, s21_ex_archive_04, s21_ex_customers;
