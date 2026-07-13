-- =====================================================================
-- Step 21 — 파티셔닝 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t --force < practice.sql
--   또는 mysql 접속 후:  source practice.sql
--
-- ※ --force 를 붙이는 이유: 이 스크립트는 "일부러 실패하는" SQL(제약 위반)을
--    포함합니다. 에러를 눈으로 보는 것이 학습 포인트입니다.
-- ※ 공용 테이블(access_logs 등)은 SELECT 만 합니다. 실습 테이블은 전부 s21_ 접두사.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- 21-1. 실습 테이블 준비
--   access_logs(100만 행, logged_at 2024-01-01 ~ 2024-12-01) 구조를 복제해
--   월 단위 RANGE COLUMNS 파티션 테이블을 만든다.
--   비교군으로 "같은 인덱스를 가진 비파티션 테이블"도 만든다.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s21_access_logs;

CREATE TABLE s21_access_logs (
  log_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  path        VARCHAR(100) NOT NULL,
  method      ENUM('GET','POST','PUT','DELETE') NOT NULL,
  status_code SMALLINT NOT NULL,
  duration_ms INT NOT NULL,
  user_agent  VARCHAR(60) NOT NULL,
  logged_at   DATETIME NOT NULL,
  -- 규칙: 모든 유니크 키(PK 포함)는 파티션 키를 "반드시" 포함해야 한다.
  --       그래서 PK 가 (log_id) 가 아니라 (log_id, logged_at) 이다.
  PRIMARY KEY (log_id, logged_at),
  KEY idx_s21_logged_at (logged_at)
) ENGINE=InnoDB COMMENT='Step21 파티션 실습용 접근로그'
PARTITION BY RANGE COLUMNS(logged_at) (
  PARTITION p2024_01 VALUES LESS THAN ('2024-02-01'),
  PARTITION p2024_02 VALUES LESS THAN ('2024-03-01'),
  PARTITION p2024_03 VALUES LESS THAN ('2024-04-01'),
  PARTITION p2024_04 VALUES LESS THAN ('2024-05-01'),
  PARTITION p2024_05 VALUES LESS THAN ('2024-06-01'),
  PARTITION p2024_06 VALUES LESS THAN ('2024-07-01'),
  PARTITION p2024_07 VALUES LESS THAN ('2024-08-01'),
  PARTITION p2024_08 VALUES LESS THAN ('2024-09-01'),
  PARTITION p2024_09 VALUES LESS THAN ('2024-10-01'),
  PARTITION p2024_10 VALUES LESS THAN ('2024-11-01'),
  PARTITION p2024_11 VALUES LESS THAN ('2024-12-01'),
  PARTITION p2024_12 VALUES LESS THAN ('2025-01-01'),
  PARTITION pmax     VALUES LESS THAN (MAXVALUE)
);

INSERT INTO s21_access_logs (customer_id, path, method, status_code, duration_ms, user_agent, logged_at)
SELECT customer_id, path, method, status_code, duration_ms, user_agent, logged_at
FROM access_logs;

-- 비교군: 파티션 없음 + 동일한 logged_at 인덱스
DROP TABLE IF EXISTS s21_access_logs_np;
CREATE TABLE s21_access_logs_np LIKE access_logs;
ALTER TABLE s21_access_logs_np ADD KEY idx_np_logged_at (logged_at);
INSERT INTO s21_access_logs_np (customer_id, path, method, status_code, duration_ms, user_agent, logged_at)
SELECT customer_id, path, method, status_code, duration_ms, user_agent, logged_at
FROM access_logs;

ANALYZE TABLE s21_access_logs, s21_access_logs_np;


-- ---------------------------------------------------------------------
-- 21-2. 파티션 메타데이터 확인
-- ---------------------------------------------------------------------
SELECT
  PARTITION_NAME,
  PARTITION_METHOD,
  PARTITION_DESCRIPTION,
  TABLE_ROWS,                                   -- InnoDB 는 "추정치"다
  ROUND(DATA_LENGTH  / 1024 / 1024, 1) AS data_mb,
  ROUND(INDEX_LENGTH / 1024 / 1024, 1) AS idx_mb
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = 'shop' AND TABLE_NAME = 's21_access_logs'
ORDER BY PARTITION_ORDINAL_POSITION;

-- 정확한 행수를 파티션별로 세고 싶다면 SELECT ... PARTITION(...) 을 쓴다
SELECT COUNT(*) AS rows_in_2024_09 FROM s21_access_logs PARTITION (p2024_09);


-- ---------------------------------------------------------------------
-- 21-3. 파티션 프루닝(Partition Pruning)을 EXPLAIN 으로 확인
--   EXPLAIN 의 partitions 컬럼 = "실제로 열어볼 파티션 목록"
-- ---------------------------------------------------------------------

-- (O) 파티션 키에 대한 상수 범위 조건 → 1개 파티션만 스캔
EXPLAIN SELECT COUNT(*) FROM s21_access_logs
WHERE logged_at >= '2024-09-01' AND logged_at < '2024-10-01';

-- (O) 등치 조건도 프루닝된다
EXPLAIN SELECT * FROM s21_access_logs
WHERE logged_at = '2024-05-05 10:00:00';

-- (X) 파티션 키에 함수를 씌우면 프루닝 불가 → 전체 파티션 스캔
--     ※ 예외: RANGE(TO_DAYS(col)) 처럼 "파티션 식과 동일한 함수"인 경우만 예외적으로 됨
EXPLAIN SELECT COUNT(*) FROM s21_access_logs
WHERE YEAR(logged_at) = 2024;

-- (X) 파티션 키가 WHERE 에 아예 없으면 프루닝 불가
EXPLAIN SELECT COUNT(*) FROM s21_access_logs WHERE customer_id = 7;


-- ---------------------------------------------------------------------
-- 21-4. 실측 : 파티션이 이기는 경우 / 지는 경우
--   ※ EXPLAIN ANALYZE 는 쿼리를 "실제로 실행"하고 시간을 측정한다 (8.0.18+)
-- ---------------------------------------------------------------------

-- [A] 좁은 범위(1개월). 비파티션도 인덱스 레인지 스캔이 가능 → 큰 차이 없음
EXPLAIN ANALYZE SELECT AVG(duration_ms) FROM s21_access_logs
WHERE logged_at >= '2024-09-01' AND logged_at < '2024-10-01';

EXPLAIN ANALYZE SELECT AVG(duration_ms) FROM s21_access_logs_np
WHERE logged_at >= '2024-09-01' AND logged_at < '2024-10-01';

-- [B] 넓은 범위(6개월, 전체의 46%). 비파티션은 인덱스를 포기하고 풀스캔 →
--     파티션 쪽은 6개 파티션만 읽어서 이긴다
EXPLAIN ANALYZE SELECT COUNT(*), AVG(duration_ms) FROM s21_access_logs
WHERE logged_at >= '2024-07-01' AND logged_at < '2025-01-01';

EXPLAIN ANALYZE SELECT COUNT(*), AVG(duration_ms) FROM s21_access_logs_np
WHERE logged_at >= '2024-07-01' AND logged_at < '2025-01-01';

-- [C] 파티션 키가 없는 조건. 파티션 테이블이 "더 느리다"
--     (파티션마다 따로 스캔 → 오버헤드만 추가됨)
EXPLAIN ANALYZE SELECT COUNT(*) FROM s21_access_logs WHERE customer_id = 7;
EXPLAIN ANALYZE SELECT COUNT(*) FROM s21_access_logs_np WHERE customer_id = 7;


-- ---------------------------------------------------------------------
-- 21-5. 파티션 종류 4가지 + RANGE COLUMNS
-- ---------------------------------------------------------------------

-- (1) RANGE : 정수 파티션 식. 날짜는 TO_DAYS()/YEAR()/UNIX_TIMESTAMP() 로 정수화
DROP TABLE IF EXISTS s21_range_todays;
CREATE TABLE s21_range_todays (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  logged_at  DATE NOT NULL,
  msg        VARCHAR(50) NOT NULL,
  PRIMARY KEY (id, logged_at)
) ENGINE=InnoDB
PARTITION BY RANGE (TO_DAYS(logged_at)) (
  PARTITION p2024_h1 VALUES LESS THAN (TO_DAYS('2024-07-01')),
  PARTITION p2024_h2 VALUES LESS THAN (TO_DAYS('2025-01-01')),
  PARTITION pmax     VALUES LESS THAN MAXVALUE
);
INSERT INTO s21_range_todays (logged_at, msg) VALUES
  ('2024-03-01','h1'), ('2024-08-01','h2'), ('2025-03-01','future');
-- TO_DAYS 파티션은 TO_DAYS 조건이 아니어도 프루닝된다(옵티마이저가 변환)
EXPLAIN SELECT * FROM s21_range_todays WHERE logged_at = '2024-08-01';

-- (2) LIST : 열거값. 주문 상태/지역코드처럼 값이 유한할 때
DROP TABLE IF EXISTS s21_list_region;
CREATE TABLE s21_list_region (
  id        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  region_id TINYINT NOT NULL,
  amount    DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (id, region_id)
) ENGINE=InnoDB
PARTITION BY LIST (region_id) (
  PARTITION p_capital VALUES IN (1, 2, 3),   -- 서울/경기/인천
  PARTITION p_south   VALUES IN (4, 5, 6),
  PARTITION p_etc     VALUES IN (7, 8, 9, 0)
);
INSERT INTO s21_list_region (region_id, amount) VALUES (1,100),(5,200),(9,300);
EXPLAIN SELECT SUM(amount) FROM s21_list_region WHERE region_id IN (1,2);

-- LIST 는 "정의되지 않은 값"이 들어오면 에러 (RANGE 의 MAXVALUE 같은 게 없다)
INSERT INTO s21_list_region (region_id, amount) VALUES (99, 1);   -- ERROR 1526

-- (3) HASH : 값을 고르게 흩뿌린다. 프루닝은 등치(=) 조건에서만.
DROP TABLE IF EXISTS s21_hash_cust;
CREATE TABLE s21_hash_cust (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  note        VARCHAR(30) NULL,
  PRIMARY KEY (id, customer_id)
) ENGINE=InnoDB
PARTITION BY HASH (customer_id) PARTITIONS 4;
INSERT INTO s21_hash_cust (customer_id) SELECT customer_id FROM customers;
SELECT PARTITION_NAME, TABLE_ROWS FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_hash_cust'
ORDER BY PARTITION_ORDINAL_POSITION;
EXPLAIN SELECT * FROM s21_hash_cust WHERE customer_id = 7;    -- 1개 파티션
EXPLAIN SELECT * FROM s21_hash_cust WHERE customer_id > 7;    -- 프루닝 불가(전체)

-- (4) KEY : MySQL 내부 해시 함수 사용. 정수가 아닌 컬럼도 파티션 키로 쓸 수 있다
DROP TABLE IF EXISTS s21_key_email;
CREATE TABLE s21_key_email (
  email VARCHAR(120) NOT NULL,
  name  VARCHAR(50)  NOT NULL,
  PRIMARY KEY (email)
) ENGINE=InnoDB
PARTITION BY KEY (email) PARTITIONS 4;
INSERT INTO s21_key_email (email, name) SELECT email, name FROM customers;
SELECT PARTITION_NAME, TABLE_ROWS FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_key_email'
ORDER BY PARTITION_ORDINAL_POSITION;

-- (5) RANGE COLUMNS : 컬럼을 "그대로" 비교. 여러 컬럼(튜플 비교)도 가능
DROP TABLE IF EXISTS s21_range_columns2;
CREATE TABLE s21_range_columns2 (
  yr   SMALLINT NOT NULL,
  mon  TINYINT  NOT NULL,
  v    INT      NOT NULL,
  PRIMARY KEY (yr, mon)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS (yr, mon) (
  PARTITION p1 VALUES LESS THAN (2024, 7),
  PARTITION p2 VALUES LESS THAN (2025, 1),
  PARTITION p3 VALUES LESS THAN (MAXVALUE, MAXVALUE)
);
INSERT INTO s21_range_columns2 VALUES (2024,3,1),(2024,9,2),(2025,5,3);
EXPLAIN SELECT * FROM s21_range_columns2 WHERE yr = 2024 AND mon = 9;


-- ---------------------------------------------------------------------
-- 21-6. 제약 (여기서 대부분의 사람이 넘어진다)
-- ---------------------------------------------------------------------

-- (제약1) 모든 유니크 키/PK 는 파티션 키 컬럼을 전부 포함해야 한다
--         → 아래는 ERROR 1503
DROP TABLE IF EXISTS s21_bad_uk;
CREATE TABLE s21_bad_uk (
  id        BIGINT UNSIGNED NOT NULL,
  logged_at DATETIME NOT NULL,
  PRIMARY KEY (id)                 -- logged_at 이 빠졌다!
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(logged_at) (
  PARTITION p0 VALUES LESS THAN ('2025-01-01')
);

-- (제약2) 유니크 인덱스도 동일. email UNIQUE + logged_at 파티션 → 불가
DROP TABLE IF EXISTS s21_bad_uk2;
CREATE TABLE s21_bad_uk2 (
  id        BIGINT UNSIGNED NOT NULL,
  email     VARCHAR(120) NOT NULL,
  logged_at DATETIME NOT NULL,
  PRIMARY KEY (id, logged_at),
  UNIQUE KEY uk_email (email)      -- 파티션 키(logged_at) 미포함 → ERROR 1503
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(logged_at) (
  PARTITION p0 VALUES LESS THAN ('2025-01-01')
);

-- (제약3) 외래 키(FK) 불가 — 걸어도, 걸리는 대상이 되어도 안 된다
DROP TABLE IF EXISTS s21_bad_fk;
CREATE TABLE s21_bad_fk (
  id          BIGINT UNSIGNED NOT NULL,
  customer_id INT UNSIGNED NOT NULL,
  logged_at   DATETIME NOT NULL,
  PRIMARY KEY (id, logged_at),
  CONSTRAINT fk_s21 FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(logged_at) (
  PARTITION p0 VALUES LESS THAN ('2025-01-01')
);      -- ERROR 1506

-- (제약4) 파티션 식에는 결정적(deterministic) 함수만. NOW()/RAND() 불가
--         (오늘부터 30일 이내" 같은 파티션은 원리상 만들 수 없다)
DROP TABLE IF EXISTS s21_bad_expr;
CREATE TABLE s21_bad_expr (
  id INT NOT NULL, d DATETIME NOT NULL, PRIMARY KEY (id, d)
) ENGINE=InnoDB
PARTITION BY RANGE (DATEDIFF(NOW(), d)) (
  PARTITION p0 VALUES LESS THAN (30)
);      -- ERROR 1064

-- (제약5) MySQL 8.0 은 InnoDB "네이티브" 파티셔닝만 지원.
--         MyISAM 등 파티셔닝을 직접 지원하지 않는 엔진은 이제 불가 (5.7 → 8.0 변경점)
DROP TABLE IF EXISTS s21_bad_myisam;
CREATE TABLE s21_bad_myisam (
  id INT NOT NULL, d DATE NOT NULL, PRIMARY KEY (id, d)
) ENGINE=MyISAM
PARTITION BY RANGE COLUMNS(d) (
  PARTITION p0 VALUES LESS THAN ('2025-01-01')
);      -- ERROR 1178


-- ---------------------------------------------------------------------
-- 21-7. 파티션 관리 DDL
-- ---------------------------------------------------------------------
SHOW CREATE TABLE s21_access_logs;

-- (a) ADD PARTITION : RANGE 의 "맨 뒤"에만 붙일 수 있다.
--     MAXVALUE 파티션이 있으면 실패한다 (ERROR 1493) — 아래에서 확인
ALTER TABLE s21_access_logs
  ADD PARTITION (PARTITION p2025_01 VALUES LESS THAN ('2025-02-01'));

-- (b) REORGANIZE PARTITION : MAXVALUE 를 쪼개서 새 파티션을 만드는 정석 방법
ALTER TABLE s21_access_logs REORGANIZE PARTITION pmax INTO (
  PARTITION p2025_01 VALUES LESS THAN ('2025-02-01'),
  PARTITION p2025_02 VALUES LESS THAN ('2025-03-01'),
  PARTITION pmax     VALUES LESS THAN (MAXVALUE)
);

SELECT PARTITION_NAME, PARTITION_DESCRIPTION
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_access_logs'
ORDER BY PARTITION_ORDINAL_POSITION;

-- (c) 병합도 REORGANIZE 다. 단, "인접 파티션끼리" + "전체 범위는 그대로" 여야 한다.
--     아래는 실패한다: p2025_02 의 상한(2025-03-01)을 2025-07-01 로 늘리려 하는데,
--     뒤에 pmax 가 있으므로 마지막 파티션이 아니다 → ERROR 1520
ALTER TABLE s21_access_logs REORGANIZE PARTITION p2025_01, p2025_02 INTO (
  PARTITION p2025_q1 VALUES LESS THAN ('2025-07-01')
);

-- 올바른 병합: 상한을 그대로(2025-03-01) 유지하면 성공
ALTER TABLE s21_access_logs REORGANIZE PARTITION p2025_01, p2025_02 INTO (
  PARTITION p2025_q1 VALUES LESS THAN ('2025-03-01')
);

-- (d) TRUNCATE PARTITION : 데이터만 비우고 파티션 구조는 남긴다
ALTER TABLE s21_access_logs TRUNCATE PARTITION p2025_q1;


-- ---------------------------------------------------------------------
-- 21-8. 【핵심 실무 패턴】 오래된 로그 삭제 : DELETE vs DROP PARTITION
--   같은 92,358행을 지운다. 시간을 비교하라.
-- ---------------------------------------------------------------------

-- (1) 비파티션 테이블에서 DELETE — 행을 하나하나 지우고, 언두/리두를 다 쓴다
DELETE FROM s21_access_logs_np WHERE logged_at < '2024-02-01';
SELECT ROW_COUNT() AS deleted_rows;

-- (2) 파티션 테이블에서 DROP PARTITION — 파일(테이블스페이스)을 통째로 버린다
ALTER TABLE s21_access_logs DROP PARTITION p2024_01;

-- (3) 결과 확인 : 행수는 같아졌지만 걸린 시간이 다르다
SELECT
  (SELECT COUNT(*) FROM s21_access_logs)    AS partitioned_rows,
  (SELECT COUNT(*) FROM s21_access_logs_np) AS non_partitioned_rows;

-- (4) 디스크도 다르다. DELETE 는 파일 크기가 안 줄어든다(DATA_FREE 로 남음)
SELECT TABLE_NAME,
       ROUND((DATA_LENGTH + INDEX_LENGTH)/1024/1024, 1) AS total_mb,
       ROUND(DATA_FREE/1024/1024, 1)                    AS data_free_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME IN ('s21_access_logs','s21_access_logs_np');


-- ---------------------------------------------------------------------
-- 21-9. EXCHANGE PARTITION : 파티션 ↔ 일반 테이블 교환 (아카이빙)
--   파티션을 지우기 전에 "따로 빼서 보관"하고 싶을 때.
--   교환 대상 테이블은 파티션되지 않은 "완전히 동일한 구조"여야 한다.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s21_archive_2024_03;
CREATE TABLE s21_archive_2024_03 (
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
) ENGINE=InnoDB;

ALTER TABLE s21_access_logs
  EXCHANGE PARTITION p2024_03 WITH TABLE s21_archive_2024_03;

SELECT COUNT(*) AS moved_to_archive FROM s21_archive_2024_03;
SELECT COUNT(*) AS left_in_partition FROM s21_access_logs PARTITION (p2024_03);

-- 이제 빈 파티션은 안심하고 버린다
ALTER TABLE s21_access_logs DROP PARTITION p2024_03;


-- ---------------------------------------------------------------------
-- 21-10. 뒷정리 (원하면 실행)
-- ---------------------------------------------------------------------
-- DROP TABLE IF EXISTS s21_access_logs, s21_access_logs_np, s21_archive_2024_03,
--   s21_range_todays, s21_list_region, s21_hash_cust, s21_key_email, s21_range_columns2;
