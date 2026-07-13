-- =====================================================================
-- Step 01 practice.sql — 환경 구축과 첫 접속
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
--   (또는 mysql 접속 후:  source practice.sql)
-- =====================================================================

-- [1-4] 내가 접속한 서버는 어떤 서버인가
SELECT
  VERSION()                 AS version,
  @@version_comment         AS edition,
  @@character_set_server    AS charset,
  @@collation_server        AS collation,
  @@default_storage_engine  AS engine,
  @@time_zone               AS tz;

-- [1-4] sql_mode — 이 코스는 기본(엄격) 모드를 유지합니다
SELECT @@sql_mode;

-- [1-4] 콜레이션이 대소문자를 무시한다는 증거 (ci = case insensitive)
SELECT 'Apple' = 'apple' AS ci_compare;   -- 1 (참)

-- [1-4] utf8mb4 라서 이모지도 저장/비교 가능
SELECT LENGTH('가')  AS bytes_of_hangul,   -- 3 (바이트 수)
       CHAR_LENGTH('가') AS chars_of_hangul, -- 1 (글자 수)
       LENGTH('🚀') AS bytes_of_emoji;     -- 4  ← utf8(가짜)이면 여기서 저장조차 안 됨

-- [1-5] 세로 출력은 CLI 안에서 \G 로. (파일 실행 시에는 동작하지 않음)
SELECT * FROM customers WHERE customer_id = 1;

-- [1-6] 설정이 실제로 먹었는지 확인
SHOW VARIABLES LIKE 'slow_query%';
SHOW VARIABLES LIKE 'long_query_time';
SHOW VARIABLES LIKE 'event_scheduler';

-- [1-6] 변수 범위: SESSION 은 내 접속에만 적용
SELECT @@SESSION.sort_buffer_size AS before_change;
SET SESSION sort_buffer_size = 1048576;
SELECT @@SESSION.sort_buffer_size AS after_change;

-- [1-6] 안전 모드: WHERE 없는 UPDATE/DELETE 를 서버가 거부한다
--       (운영 DB 접속 시 반드시 켤 것)
SET SESSION sql_safe_updates = 1;
SELECT @@SESSION.sql_safe_updates AS safe_updates_on;
SET SESSION sql_safe_updates = 0;   -- 실습 편의를 위해 다시 끔

-- [1-7] 현재 DB 와 접속 계정 확인
SELECT DATABASE() AS current_db, USER() AS connected_as, CURRENT_USER() AS authenticated_as;
