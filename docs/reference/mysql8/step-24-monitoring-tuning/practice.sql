-- =====================================================================
-- Step 24 — 모니터링과 튜닝 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < practice.sql
--   ※ 관찰(SELECT)이 중심입니다. GLOBAL 설정은 "읽기만" 합니다(변경 금지).
--   ※ 실습 테이블은 s24_ 접두사. 공용 테이블은 SELECT 만.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- 24-1. 슬로우 쿼리 로그 상태 (이미 켜져 있음: long_query_time=0.5)
-- ---------------------------------------------------------------------
SHOW VARIABLES LIKE 'slow_query_log';
SHOW VARIABLES LIKE 'slow_query_log_file';
SHOW VARIABLES LIKE 'long_query_time';
SHOW VARIABLES LIKE 'log_queries_not_using_indexes';

-- ---------------------------------------------------------------------
-- 24-2. performance_schema : 상위 쿼리 (다이제스트별 집계)
--   digest = 리터럴을 ?로 치환해 "같은 모양의 쿼리"를 하나로 묶은 것.
--   총 소요시간(SUM_TIMER_WAIT)이 큰 순서 = 서버를 가장 괴롭히는 쿼리.
-- ---------------------------------------------------------------------
SELECT
  SCHEMA_NAME,
  LEFT(DIGEST_TEXT, 60)              AS digest,
  COUNT_STAR                         AS calls,
  ROUND(SUM_TIMER_WAIT/1e12, 3)      AS total_s,
  ROUND(AVG_TIMER_WAIT/1e9,  2)      AS avg_ms,
  SUM_ROWS_EXAMINED                  AS rows_examined,
  SUM_ROWS_SENT                      AS rows_sent
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'shop'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 5;

-- ---------------------------------------------------------------------
-- 24-3. sys 스키마 : 사람이 읽기 좋게 가공된 뷰들
-- ---------------------------------------------------------------------

-- (a) 느린 문장 분석 (읽기 좋은 단위로 변환됨)
SELECT query, db, exec_count, total_latency, rows_examined_avg, rows_sent_avg
FROM sys.statement_analysis
WHERE db = 'shop'
ORDER BY total_latency DESC
LIMIT 5;

-- (b) 인덱스 사용 통계 : 어떤 인덱스가 실제로 읽히는가
SELECT table_name, index_name, rows_selected, rows_inserted
FROM sys.schema_index_statistics
WHERE table_schema = 'shop'
ORDER BY rows_selected DESC
LIMIT 8;

-- (c) 안 쓰이는 인덱스 : 지워도 되는 후보 (쓰기 비용만 유발)
--   ※ 서버가 켜진 이후 한 번도 안 쓰인 인덱스. 관측 기간이 짧으면 오탐 주의.
SELECT * FROM sys.schema_unused_indexes WHERE object_schema = 'shop';

-- (d) 테이블별 버퍼풀 점유 : 메모리를 누가 먹고 있나
SELECT object_name, allocated, data, pages
FROM sys.innodb_buffer_stats_by_table
WHERE object_schema = 'shop'
ORDER BY pages DESC
LIMIT 6;

-- ---------------------------------------------------------------------
-- 24-4. 버퍼풀 히트율 직접 계산
--   히트율 = 1 - (디스크에서 읽은 페이지 / 버퍼풀에 요청한 페이지)
--   99% 이상이면 대부분 메모리에서 처리. 낮으면 버퍼풀이 작다는 신호.
-- ---------------------------------------------------------------------
SELECT
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests') AS read_requests,
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME='Innodb_buffer_pool_reads')          AS disk_reads,
  ROUND(100 * (1 -
     (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads')
   / (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests')
  ), 4) AS hit_ratio_pct;

-- ---------------------------------------------------------------------
-- 24-5. 주요 상태변수 / 연결 상태
-- ---------------------------------------------------------------------
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Threads_connected',        -- 현재 연결 수
  'Threads_running',          -- 지금 실제로 쿼리 실행 중인 수 (급증 = 위험)
  'Max_used_connections',     -- 부팅 후 최대 동시 연결 (max_connections 대비 확인)
  'Aborted_connects',         -- 연결 실패 (인증/네트워크 문제 신호)
  'Created_tmp_disk_tables',  -- 디스크에 만들어진 임시테이블 (많으면 tmp_table_size 부족)
  'Created_tmp_tables'
);

-- ---------------------------------------------------------------------
-- 24-6. 핵심 설정값 관찰 (읽기만! 변경 금지)
-- ---------------------------------------------------------------------
SHOW VARIABLES WHERE Variable_name IN (
  'innodb_buffer_pool_size',         -- 가장 중요. 운영: 물리메모리의 50~70%
  'innodb_redo_log_capacity',        -- 8.0.30+ : redo 로그 총량 (구 innodb_log_file_size 대체)
  'innodb_flush_log_at_trx_commit',  -- 1=완전내구성(기본), 2/0=성능↑ 내구성↓
  'max_connections',
  'tmp_table_size',                  -- 인메모리 임시테이블 한도
  'table_open_cache'
);

-- ---------------------------------------------------------------------
-- 24-7. 【튜닝 실측】 인덱스 하나로 131ms → 0.008ms
--   s24_logs 를 만들어 인덱스 유무의 차이를 직접 측정한다.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s24_logs;
CREATE TABLE s24_logs LIKE access_logs;
INSERT INTO s24_logs SELECT * FROM access_logs;
ANALYZE TABLE s24_logs;

-- (before) 인덱스 없음 → 100만 행 풀스캔
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE customer_id = 7 AND status_code = 500;

-- 복합 인덱스 추가 (등치 조건 두 개 → 커버링 인덱스)
ALTER TABLE s24_logs ADD INDEX idx_cust_status (customer_id, status_code);

-- (after) 커버링 인덱스 조회
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE customer_id = 7 AND status_code = 500;

-- ---------------------------------------------------------------------
-- 24-8. SHOW ENGINE INNODB STATUS — 스냅샷 진단
--   BUFFER POOL AND MEMORY / TRANSACTIONS / LATEST DETECTED DEADLOCK 등을 본다.
--   (출력이 길어 -E 세로 모드나 mysql 클라이언트에서 직접 보는 것을 권장)
-- ---------------------------------------------------------------------
-- SHOW ENGINE INNODB STATUS\G

-- 뒷정리 (원하면)
-- DROP TABLE IF EXISTS s24_logs;
