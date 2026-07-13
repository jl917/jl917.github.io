-- =====================================================================
-- Step 21 — 파티셔닝 : exercise.sql (연습문제)
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t --force < exercise.sql
--
-- 전제: practice.sql 을 먼저 실행해 s21_access_logs 가 존재해야 합니다.
--       (practice.sql 이 p2024_01 / p2024_03 파티션을 이미 제거했다는 점에 주의)
-- 각 문제의 TODO 를 채워 넣으세요. 정답은 solution.sql.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [문제 1] 2024년 5월 데이터만 세는 쿼리를 쓰고, EXPLAIN 으로
--          파티션이 p2024_05 하나만 열리는지 확인하라.
-- 힌트: 날짜 범위는 항상 [시작 <= x < 다음달1일) 형태의 반열린 구간으로 쓴다.
-- ---------------------------------------------------------------------
-- TODO
-- EXPLAIN SELECT ... ;


-- ---------------------------------------------------------------------
-- [문제 2] 아래 쿼리는 파티션 프루닝이 되는가? EXPLAIN 으로 확인하고,
--          프루닝이 되도록 고쳐라.
-- ---------------------------------------------------------------------
EXPLAIN SELECT COUNT(*) FROM s21_access_logs WHERE DATE(logged_at) = '2024-05-05';

-- TODO: 위 쿼리를 프루닝되도록 다시 쓰기
-- EXPLAIN SELECT ... ;


-- ---------------------------------------------------------------------
-- [문제 3] status_code 를 LIST 파티션 키로 하는 s21_ex_status 를 만들어라.
--          - p_ok    : 200, 201, 204, 301, 302, 304
--          - p_client: 400, 401, 403, 404, 429
--          - p_server: 500, 502, 503, 504
--          만든 뒤 access_logs 에서 데이터를 넣고,
--          status_code = 200 조회 시 p_ok 만 열리는지 EXPLAIN 으로 확인하라.
-- 힌트: PK 에 파티션 키(status_code)가 포함돼야 한다.
-- 힌트: access_logs 에 없는 status_code 를 넣으면 ERROR 1526 이 난다. 어떤 값들이 있는지 먼저 확인!
-- ---------------------------------------------------------------------
SELECT DISTINCT status_code FROM access_logs ORDER BY status_code;   -- 먼저 확인

-- TODO
-- DROP TABLE IF EXISTS s21_ex_status;
-- CREATE TABLE s21_ex_status ( ... ) ENGINE=InnoDB PARTITION BY LIST (status_code) ( ... );
-- INSERT INTO s21_ex_status ... ;
-- EXPLAIN SELECT COUNT(*) FROM s21_ex_status WHERE status_code = 200;


-- ---------------------------------------------------------------------
-- [문제 4] s21_access_logs 의 pmax 를 쪼개서
--          p2025_01, p2025_02, p2025_03 (각각 월 단위) + pmax 를 만들어라.
-- 힌트: ADD PARTITION 은 MAXVALUE 때문에 실패한다. 무엇을 써야 하는가?
-- 힌트: practice.sql 이 p2025_q1(< '2025-03-01') 을 남겨 두었다. 경계가 역행하면
--       ERROR 1493 이 난다. 먼저 무엇을 해야 하는가?
-- ---------------------------------------------------------------------
-- TODO
-- ALTER TABLE s21_access_logs REORGANIZE PARTITION ... ;

-- 확인
-- SELECT PARTITION_NAME, PARTITION_DESCRIPTION FROM information_schema.PARTITIONS
--  WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='s21_access_logs' ORDER BY PARTITION_ORDINAL_POSITION;


-- ---------------------------------------------------------------------
-- [문제 5] 2024년 4월(p2024_04) 파티션을 아카이브 테이블 s21_ex_archive_04 로
--          빼낸 뒤, 파티션을 삭제하라. 삭제 전후 전체 행수를 비교하라.
-- 힌트: EXCHANGE PARTITION → DROP PARTITION
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS before_rows FROM s21_access_logs;

-- TODO
-- DROP TABLE IF EXISTS s21_ex_archive_04;
-- CREATE TABLE s21_ex_archive_04 ( ... );
-- ALTER TABLE s21_access_logs EXCHANGE PARTITION ... ;
-- ALTER TABLE s21_access_logs DROP PARTITION ... ;

-- SELECT COUNT(*) AS after_rows FROM s21_access_logs;
-- SELECT COUNT(*) AS archived FROM s21_ex_archive_04;


-- ---------------------------------------------------------------------
-- [문제 6] customers 를 created_at 으로 파티셔닝하려고 한다.
--          왜 그대로는 불가능한가? SQL 로 증명하라.
--          (공용 테이블은 건드리지 말고, s21_ex_customers 로 복제해서 시도할 것)
-- 힌트: customers 에는 uk_customers_email (UNIQUE) 가 있다.
-- ---------------------------------------------------------------------
SHOW CREATE TABLE customers;

-- TODO: 실패하는 CREATE TABLE 을 작성해 에러 번호/메시지를 확인하고,
--       "무엇을 포기해야 파티셔닝이 가능한지" 주석으로 답하라.
