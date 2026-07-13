-- =====================================================================
-- 00-grants.sql
--   컨테이너가 "처음 만들어질 때" 딱 한 번 자동 실행됩니다.
--   (docker-entrypoint-initdb.d 규칙. 볼륨이 이미 있으면 실행되지 않습니다)
--
-- learner 계정에 학습에 필요한 권한을 넉넉히 부여합니다.
--   - DB 생성/삭제 (Step 02)
--   - performance_schema / sys 조회 (Step 24)
--   - 세션·글로벌 변수 조회 및 변경 (Step 01, 24)
--   - 프로세스 목록 조회 (Step 19, 24)
--
-- ⚠️ 이것은 "학습 환경"이라서 이렇게 하는 것입니다.
--    운영에서는 절대 이러면 안 됩니다. 최소 권한 원칙은 Step 22에서 배웁니다.
-- =====================================================================

GRANT ALL PRIVILEGES ON *.* TO 'learner'@'%';

-- 8.0 의 동적 권한(Dynamic Privileges)은 ALL PRIVILEGES 에 포함되지 않으므로 따로 부여
GRANT SYSTEM_VARIABLES_ADMIN ON *.* TO 'learner'@'%';   -- SET GLOBAL / SET PERSIST
GRANT SESSION_VARIABLES_ADMIN ON *.* TO 'learner'@'%';

FLUSH PRIVILEGES;
