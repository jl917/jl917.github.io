-- =====================================================================
-- Step 22 — 사용자와 보안 : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < practice.sql
--   ※ 반드시 root 로 실행합니다 (사용자/롤 생성은 관리 권한 필요).
--   ※ 이 스크립트는 서버에 계정을 "만들고, 검증하고, 마지막에 전부 지웁니다".
--     공용 스키마에는 s22_ 접두사 뷰 하나만 잠깐 만들었다가 지웁니다.
--   ※ 계정 검증(로그인 성공/거부)은 SQL 만으로는 보여줄 수 없어
--     README 에 셸 명령과 실제 출력을 함께 실었습니다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 깨끗한 시작 (이전 실행 잔재 제거)
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS shop.s22_customers_masked;
DROP ROLE IF EXISTS s22_reader, s22_writer, s22_analyst;
DROP USER IF EXISTS 'app_ro'@'%', 'app_rw'@'%', 's22_alice'@'%',
                    's22_native'@'%', 's22_lock'@'%';

-- ---------------------------------------------------------------------
-- 22-1. 사용자 생성 + 인증 플러그인
--   caching_sha2_password : MySQL 8.0 기본. SHA-256 + 캐시. 안전.
--   mysql_native_password  : 예전 방식. 구형 드라이버 호환용. 8.0 에서 비권장.
--                           (8.4 에서 기본 비활성, 9.x 에서 제거 예정)
-- ---------------------------------------------------------------------
CREATE USER 'app_ro'@'%'     IDENTIFIED WITH caching_sha2_password BY 'Ro#Pass123';
CREATE USER 'app_rw'@'%'     IDENTIFIED WITH caching_sha2_password BY 'Rw#Pass123';
CREATE USER 's22_native'@'%' IDENTIFIED WITH mysql_native_password  BY 'Nat#Pass123';

SELECT user, host, plugin FROM mysql.user
WHERE user IN ('app_ro','app_rw','s22_native') ORDER BY user;

-- ---------------------------------------------------------------------
-- 22-2. 롤(ROLE) — MySQL 8.0 신기능
--   권한을 "역할"에 모아두고, 역할을 사람에게 부여한다.
--   담당자가 바뀌어도 롤만 갈아끼우면 된다. (권한을 사람마다 일일이 안 준다)
-- ---------------------------------------------------------------------
CREATE ROLE s22_reader, s22_writer, s22_analyst;

-- 22-3. 권한 레벨 : 글로벌 / DB / 테이블 / 컬럼
--   최소권한 원칙: 필요한 것만, 필요한 범위만.
GRANT SELECT                         ON shop.*          TO s22_reader;   -- DB 레벨
GRANT SELECT, INSERT, UPDATE, DELETE ON shop.*          TO s22_writer;   -- DB 레벨
GRANT SELECT                         ON shop.orders     TO s22_analyst;  -- 테이블 레벨
GRANT SELECT (customer_id, grade, city, points)
                                     ON shop.customers  TO s22_analyst;  -- 컬럼 레벨!

-- 22-4. 롤을 유저에게 부여하고 기본 롤 활성화
GRANT s22_reader            TO 'app_ro'@'%';
GRANT s22_reader, s22_writer TO 'app_rw'@'%';
-- ★ 중요: 롤은 부여만 해서는 "활성화"되지 않는다. 로그인 시 켜지도록 기본 롤을 지정.
SET DEFAULT ROLE ALL TO 'app_ro'@'%', 'app_rw'@'%';

-- 22-5. 권한 확인
SHOW GRANTS FOR 'app_ro'@'%';
SHOW GRANTS FOR 'app_rw'@'%' USING s22_writer;   -- 특정 롤을 켠 상태의 유효 권한

-- ---------------------------------------------------------------------
-- 22-6. 뷰를 통한 컬럼 마스킹
--   민감정보(email/phone)를 원본 테이블 대신 "가려진 뷰"로만 노출한다.
--   analyst 에게는 customers 원본 테이블 권한을 주지 않고, 뷰만 준다.
-- ---------------------------------------------------------------------
CREATE VIEW shop.s22_customers_masked AS
SELECT
  customer_id,
  name,
  CONCAT(LEFT(email, 2), '***@', SUBSTRING_INDEX(email, '@', -1)) AS email_masked,
  CONCAT(LEFT(phone, 3), '-****-', RIGHT(phone, 4))               AS phone_masked,
  grade, city
FROM shop.customers;

GRANT SELECT ON shop.s22_customers_masked TO s22_analyst;
SELECT * FROM shop.s22_customers_masked LIMIT 3;

-- ---------------------------------------------------------------------
-- 22-7. 비밀번호 정책 (계정 단위)
--   서버 전역 정책(validate_password 컴포넌트)은 공용 서버라 건드리지 않는다.
--   대신 "계정 단위" 정책은 자유롭게 걸 수 있다.
-- ---------------------------------------------------------------------

-- 계정 잠금 : 연속 실패 N회 시 잠금 (FAILED_LOGIN_ATTEMPTS, 8.0.19+)
CREATE USER 's22_lock'@'%' IDENTIFIED BY 'Lock#Pass123'
  FAILED_LOGIN_ATTEMPTS 3       -- 3회 연속 틀리면
  PASSWORD_LOCK_TIME 1;         -- 1일간 잠금 (N=UNBOUNDED 면 무기한)

-- 비밀번호 만료 : 다음 로그인 때 강제 변경
ALTER USER 's22_lock'@'%' PASSWORD EXPIRE;

-- 비밀번호 재사용 제한 : 최근 3개 / 365일 이내 재사용 금지
ALTER USER 's22_lock'@'%' PASSWORD HISTORY 3 PASSWORD REUSE INTERVAL 365 DAY;

-- 계정에 걸린 정책 확인 (mysql.user.User_attributes 는 JSON)
SELECT user, host,
       JSON_EXTRACT(User_attributes, '$.Password_locking') AS lock_policy,
       password_expired
FROM mysql.user WHERE user = 's22_lock';

-- 잠긴 계정 해제
ALTER USER 's22_lock'@'%' ACCOUNT UNLOCK;

-- ---------------------------------------------------------------------
-- 22-8. REVOKE — 권한 회수
-- ---------------------------------------------------------------------
REVOKE INSERT, UPDATE, DELETE ON shop.* FROM s22_writer;
SHOW GRANTS FOR s22_writer;    -- 이제 SELECT 만 남는다

-- ---------------------------------------------------------------------
-- 22-9. 뒷정리 — 만든 계정/롤/뷰를 전부 제거
--   (공용 서버이므로 실습 흔적을 반드시 지운다)
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS shop.s22_customers_masked;
DROP ROLE IF EXISTS s22_reader, s22_writer, s22_analyst;
DROP USER IF EXISTS 'app_ro'@'%', 'app_rw'@'%', 's22_alice'@'%',
                    's22_native'@'%', 's22_lock'@'%';

SELECT 'cleanup done' AS status,
       (SELECT COUNT(*) FROM mysql.user
        WHERE user LIKE 's22%' OR user IN ('app_ro','app_rw')) AS leftover_users;
