-- =====================================================================
-- Step 24 — 모니터링과 튜닝 : solution.sql (정답)
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < solution.sql
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] 평균 시간 상위 5개
--   ORDER BY AVG_TIMER_WAIT. 총합(SUM_TIMER_WAIT) 상위와 다른 이유:
--   - 평균 상위 = "한 번 돌면 오래 걸리는" 쿼리 (배치/리포트성. 호출은 드묾)
--   - 총합 상위 = calls × avg. 자주 불리는 쿼리는 평균이 짧아도 총합 1위가 됨
--   → 튜닝 우선순위는 "총합"이 원칙. 단, 평균이 매우 큰 쿼리는 사용자 체감 지연이라 별도 관리.
-- ---------------------------------------------------------------------
SELECT LEFT(DIGEST_TEXT, 60) AS digest,
       COUNT_STAR             AS calls,
       ROUND(AVG_TIMER_WAIT/1e9, 2) AS avg_ms,
       ROUND(SUM_TIMER_WAIT/1e12, 3) AS total_s
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'shop'
ORDER BY AVG_TIMER_WAIT DESC
LIMIT 5;

-- ---------------------------------------------------------------------
-- [정답 2] 인덱스 전/후 비교
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s24_logs;
CREATE TABLE s24_logs LIKE access_logs;
INSERT INTO s24_logs SELECT * FROM access_logs;
ANALYZE TABLE s24_logs;

-- (before) 풀스캔
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE path = '/orders' AND method = 'POST';

-- 복합 인덱스: 등치 조건 두 개
CREATE INDEX idx_path_method ON s24_logs (path, method);

-- (after) 인덱스 레인지 스캔
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE path = '/orders' AND method = 'POST';
-- Table scan(1e6행) → index lookup 으로 바뀐다.

-- ---------------------------------------------------------------------
-- [정답 3] 버퍼풀 히트율 + 상위 테이블
-- ---------------------------------------------------------------------
SELECT
  ROUND(100*(1 -
     (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads')
   / (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests')
  ), 4) AS hit_ratio_pct;

SELECT object_name, allocated, data, pages
FROM sys.innodb_buffer_stats_by_table
WHERE object_schema = 'shop'
ORDER BY pages DESC
LIMIT 3;

-- ---------------------------------------------------------------------
-- [정답 4] 디스크 임시테이블 비율
--   높으면(예: >10%) tmp_table_size/max_heap_table_size 부족 또는
--   BLOB/TEXT·큰 GROUP BY 로 인메모리 임시테이블이 못 쓰인 것.
-- ---------------------------------------------------------------------
SELECT
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Created_tmp_disk_tables') AS disk_tmp,
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Created_tmp_tables')      AS all_tmp,
  ROUND(100 *
     (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Created_tmp_disk_tables')
   / NULLIF((SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Created_tmp_tables'),0)
  , 2) AS disk_tmp_pct;

-- ---------------------------------------------------------------------
-- [정답 5] 안 쓰이는 인덱스 만들기 + 확인
--   duration_ms 인덱스를 만들되, duration_ms 로 검색하는 쿼리는 실행하지 않는다.
-- ---------------------------------------------------------------------
CREATE INDEX idx_unused_duration ON s24_logs (duration_ms);
-- (이 인덱스를 타는 쿼리를 일부러 실행하지 않는다)
SELECT object_schema, object_name, index_name
FROM sys.schema_unused_indexes
WHERE object_schema = 'shop' AND object_name = 's24_logs';
-- → idx_unused_duration 이 목록에 나타난다(아직 한 번도 안 쓰임).
-- ※ idx_path_method 는 [정답 2]에서 조회에 쓰였으므로 목록에 없다.

-- ---------------------------------------------------------------------
-- [정답 6] "특정 API 가 느리다" 플레이북 (실행 순서)
-- ---------------------------------------------------------------------
-- 1) 지금 무엇이 도는가
SELECT id, user, time, state, LEFT(info, 60) AS query
FROM information_schema.processlist
WHERE command = 'Query' AND info IS NOT NULL
ORDER BY time DESC
LIMIT 5;

-- 2) 누적으로 무엇이 무거운가 (해당 API 쿼리 패턴을 찾는다)
SELECT query, exec_count, total_latency, rows_examined_avg, rows_sent_avg
FROM sys.statement_analysis
WHERE db = 'shop'
ORDER BY total_latency DESC
LIMIT 5;

-- 3) 의심 쿼리를 EXPLAIN ANALYZE 로 확인 (rows_examined ≫ rows_sent 면 인덱스 문제)
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s24_logs WHERE path = '/orders' AND method = 'POST';

-- 4) 디스크 임시테이블이 늘고 있으면 tmp_table_size 도 의심
SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status
WHERE VARIABLE_NAME IN ('Created_tmp_disk_tables','Created_tmp_tables');

-- 뒷정리
DROP TABLE IF EXISTS s24_logs;
