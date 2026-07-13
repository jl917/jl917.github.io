-- =====================================================================
-- Step 23 — 백업과 복제 : practice.sql
-- ---------------------------------------------------------------------
-- 이 스텝의 실습은 대부분 셸 스크립트입니다:
--   * backup-restore.sh          — mysqldump 백업/복구 (learn-mysql8, 3307)
--   * setup-replication.sh       — 소스-레플리카 복제 구성 (별도 3308/3309)
--   * ../docker/replication/docker-compose.yml — 복제용 2노드 스택
--
-- 이 SQL 은 learn-mysql8(3307)에서 "읽기 전용"으로 백업/복제 관련 상태를
-- 관찰하는 용도입니다. (공용 서버의 설정을 바꾸지 않습니다)
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 -t < practice.sql
-- =====================================================================

-- 23-1. 바이너리 로그 상태
SHOW VARIABLES LIKE 'log_bin';                     -- binlog 켜졌는가
SHOW VARIABLES LIKE 'binlog_format';               -- ROW / STATEMENT / MIXED
SHOW VARIABLES LIKE 'binlog_expire_logs_seconds';  -- binlog 보관 기간(8.0 신규)
SHOW VARIABLES LIKE 'gtid_mode';                   -- GTID 켜졌는가

-- 23-2. binlog 파일 목록 (log_bin 이 ON 일 때만 결과가 나옴)
--   ↓ learn-mysql8 은 학습 편의상 binlog 가 꺼져 있을 수 있습니다.
--     그러면 "You are not using binary logging" 에러가 정상입니다.
-- SHOW BINARY LOGS;
-- SHOW MASTER STATUS;

-- 23-3. 복제 상태 (레플리카에서만 의미 있음. 3307 은 단독 서버라 비어 있음)
-- SHOW REPLICA STATUS;   -- 8.0.22+ 신용어. (구: SHOW SLAVE STATUS)

-- 23-4. 복구 검증용 — 원본 테이블 행수 스냅샷
--   backup-restore.sh 가 복구본과 비교할 기준값입니다.
SELECT 'orders'      AS tbl, COUNT(*) AS rows_now FROM shop.orders
UNION ALL SELECT 'order_items', COUNT(*) FROM shop.order_items
UNION ALL SELECT 'customers',   COUNT(*) FROM shop.customers
UNION ALL SELECT 'products',    COUNT(*) FROM shop.products
UNION ALL SELECT 'payments',    COUNT(*) FROM shop.payments
UNION ALL SELECT 'reviews',     COUNT(*) FROM shop.reviews
UNION ALL SELECT 'access_logs', COUNT(*) FROM shop.access_logs;

-- 23-5. 저장 프로시저/함수/이벤트 존재 확인
--   → mysqldump 에서 --routines / --events 를 빼면 복구본에서 이것들이 사라진다.
SELECT ROUTINE_TYPE, ROUTINE_NAME FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA='shop';
SELECT EVENT_NAME, STATUS FROM information_schema.EVENTS
WHERE EVENT_SCHEMA='shop';
