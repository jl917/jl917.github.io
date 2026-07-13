-- =====================================================================
-- Step 01 solution.sql — 연습문제 정답
-- =====================================================================

-- ── 문제 1. 최대 동시 접속 수
SHOW VARIABLES LIKE 'max_connections';
-- 또는
SELECT @@max_connections;
-- 결과: 151
-- 해설: MySQL 기본값은 151 입니다. 여기에 SUPER 권한용 예비 1개가 더 있습니다.
--       실무에서 "Too many connections" 에러가 나면 이 값을 올리기 전에
--       "왜 커넥션이 안 반납되는가"(커넥션 풀 설정, 슬로우 쿼리)를 먼저 의심해야 합니다.
--       max_connections 를 무작정 올리면 메모리만 터집니다. → Step 24


-- ── 문제 2. 버퍼 풀 크기를 MB 로
SELECT @@innodb_buffer_pool_size AS bytes,
       ROUND(@@innodb_buffer_pool_size / 1024 / 1024) AS mb;
-- 결과: bytes=268435456, mb=256
-- 해설: my.cnf 에서 256M 으로 지정했습니다. 운영에서는 물리 메모리의 50~70% 가 정석입니다.
--       InnoDB 는 데이터/인덱스를 이 버퍼 풀에 캐싱합니다. 여기 안 들어가면 디스크를 읽습니다.


-- ── 문제 3. 현재 세션 목록
SHOW PROCESSLIST;
-- 또는 (더 자세히, 잘리지 않은 전체 쿼리)
SHOW FULL PROCESSLIST;
-- 또는 8.0 권장 방식
SELECT id, user, host, db, command, time, state, LEFT(info, 60) AS query
FROM information_schema.processlist;
-- 해설: 운영 장애 대응의 1번 명령입니다. Time 이 큰 세션, State 가 'Locked'/'Waiting for ...'
--       인 세션이 범인일 확률이 높습니다. 죽이려면 KILL <id>; → Step 24


-- ── 문제 4. 서버 가동 시간을 보기 좋게
SELECT
  VARIABLE_VALUE AS uptime_seconds,
  CONCAT(
    FLOOR(VARIABLE_VALUE / 3600), '시간 ',
    FLOOR((VARIABLE_VALUE % 3600) / 60), '분'
  ) AS uptime_readable
FROM performance_schema.global_status
WHERE VARIABLE_NAME = 'Uptime';
-- 해설: MySQL 8 부터 상태 변수는 performance_schema.global_status 에서 조회합니다.
--       (5.7 의 information_schema.global_status 는 8.0 에서 제거 예정 → deprecated)
--       SHOW GLOBAL STATUS LIKE 'Uptime'; 도 여전히 동작합니다.


-- ── 문제 5. 세션 타임존 변경
SELECT @@SESSION.time_zone AS tz_before, NOW() AS now_before;
SET SESSION time_zone = '+00:00';
SELECT @@SESSION.time_zone AS tz_after,  NOW() AS now_after;
SET SESSION time_zone = '+09:00';   -- 원복
-- 결과 예시:
--   tz_before=+09:00, now_before=2026-07-13 10:28:10
--   tz_after =+00:00, now_after =2026-07-13 01:28:10
-- 해설: DATETIME 컬럼은 타임존 정보를 갖지 않습니다. "그냥 적힌 값"입니다.
--       반면 TIMESTAMP 는 내부적으로 UTC 로 저장되고 조회 시 세션 타임존으로 변환됩니다.
--       → 그래서 세션 타임존이 다르면 TIMESTAMP 는 다른 값으로 보이고, DATETIME 은 그대로입니다.
--       이 차이가 글로벌 서비스에서 시간 버그의 단골 원인입니다. → Step 02, Step 12


-- ── 문제 6. 스토리지 엔진 목록
SHOW ENGINES;
-- 해설: Support 컬럼이 DEFAULT 인 것이 기본 엔진(InnoDB)입니다.
--       InnoDB 만 Transactions=YES, XA=YES, Savepoints=YES 입니다.
--       MyISAM 은 트랜잭션도 외래키도 행 잠금도 없습니다. 신규 개발에서 쓸 이유가 없습니다.
--       MEMORY 는 재시작하면 데이터가 날아갑니다. 임시 집계용으로만.


-- ── 문제 7. 'buffer' 가 들어간 시스템 변수
SHOW VARIABLES LIKE '%buffer%';
-- 또는 값까지 정렬해서 보기
SELECT VARIABLE_NAME, VARIABLE_VALUE
FROM performance_schema.global_variables
WHERE VARIABLE_NAME LIKE '%buffer%'
ORDER BY VARIABLE_NAME;
-- 해설: sort_buffer_size, join_buffer_size, read_buffer_size 등은 "세션마다" 할당됩니다.
--       즉 이 값을 크게 잡으면 (커넥션 수 × 버퍼 크기) 만큼 메모리가 폭증합니다.
--       반면 innodb_buffer_pool_size 는 서버 전체가 공유합니다.
--       이 구분을 모르고 sort_buffer_size 를 1G 로 올렸다가 OOM 을 내는 사고가 흔합니다.
