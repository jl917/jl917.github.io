-- =====================================================================
-- Step 19 — 트랜잭션과 락  practice.sql  (단일 세션 개념)
-- ---------------------------------------------------------------------
-- 두 세션이 필요한 실습(격리수준/락/데드락)은 session-a.sql / session-b.sql
-- 에서 진행합니다. 이 파일은 혼자서 확인할 수 있는 기초 개념만 다룹니다.
--
-- 먼저 setup.sql 을 실행해 두세요.
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
--
-- ★ 안전 규칙: 공용 테이블은 절대 변경하지 않습니다. s19_* 사본만 씁니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [19-1] autocommit — 평소 우리는 "자동 커밋" 세계에 산다
-- ---------------------------------------------------------------------
SELECT @@autocommit AS autocommit;   -- 보통 1

-- autocommit=1 이면 UPDATE 한 방이 곧바로 커밋됩니다.
-- 명시적으로 START TRANSACTION 을 하면 그 순간부터 수동 커밋 모드가 됩니다.

-- ---------------------------------------------------------------------
-- [19-2] START TRANSACTION / COMMIT — 원자성(Atomicity)
-- ---------------------------------------------------------------------
-- 계좌 이체: 1번 → 2번 5000원. 둘 다 되거나 둘 다 안 되거나여야 한다.
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 5000 WHERE account_id = 1;
UPDATE s19_accounts SET balance = balance + 5000 WHERE account_id = 2;
SELECT '이체 후(커밋 전)' AS step, account_id, owner, balance
FROM s19_accounts WHERE account_id IN (1,2) ORDER BY account_id;
COMMIT;

SELECT '커밋 후' AS step, account_id, owner, balance
FROM s19_accounts WHERE account_id IN (1,2) ORDER BY account_id;

-- ---------------------------------------------------------------------
-- [19-3] ROLLBACK — 취소
-- ---------------------------------------------------------------------
START TRANSACTION;
UPDATE s19_accounts SET balance = 0 WHERE account_id = 1;   -- 실수!
SELECT '롤백 전(세션 안에서만 보임)' AS step, balance FROM s19_accounts WHERE account_id=1;
ROLLBACK;
SELECT '롤백 후(원래대로)' AS step, balance FROM s19_accounts WHERE account_id=1;

-- ---------------------------------------------------------------------
-- [19-4] SAVEPOINT — 트랜잭션 안의 부분 되돌리기
-- ---------------------------------------------------------------------
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 1000 WHERE account_id = 1;
SAVEPOINT after_withdraw;
UPDATE s19_accounts SET balance = balance + 1000 WHERE account_id = 3;   -- 잘못된 대상
SELECT '세이브포인트 롤백 전' AS step, account_id, balance
FROM s19_accounts WHERE account_id IN (1,3) ORDER BY account_id;

ROLLBACK TO SAVEPOINT after_withdraw;   -- 3번 입금만 취소, 1번 출금은 유지
SELECT '세이브포인트 롤백 후' AS step, account_id, balance
FROM s19_accounts WHERE account_id IN (1,3) ORDER BY account_id;
ROLLBACK;   -- 전체 취소 (실습이므로 원상복구)

-- ---------------------------------------------------------------------
-- [19-5] DDL 은 암묵적 커밋을 일으킨다 (함정)
-- ---------------------------------------------------------------------
-- START TRANSACTION 도중에 CREATE/ALTER/DROP/TRUNCATE 같은 DDL 을 실행하면
-- 진행 중이던 트랜잭션이 "그 직전에" 자동 커밋되어 버립니다. ROLLBACK 이 안 먹습니다.
-- 아래는 개념만 보여주는 예시입니다(실제로 돌리면 앞 UPDATE 가 커밋됨).
--
-- START TRANSACTION;
-- UPDATE s19_accounts SET balance = 1 WHERE account_id = 4;
-- CREATE TABLE s19_tmp (x INT);   -- ← 여기서 위 UPDATE 가 암묵 커밋됨!
-- ROLLBACK;                        -- UPDATE 는 이미 커밋돼서 취소 안 됨
-- DROP TABLE s19_tmp;

-- ---------------------------------------------------------------------
-- [19-6] 현재 격리수준과 락 관련 설정 확인
-- ---------------------------------------------------------------------
SELECT @@transaction_isolation    AS isolation,   -- MySQL 기본값: REPEATABLE-READ
       @@autocommit               AS autocommit,
       @@innodb_lock_wait_timeout AS lock_timeout_sec;

-- 격리수준은 세션 단위로만 바꾸세요 (GLOBAL 금지 — 다른 사용자에게 영향).
SET SESSION transaction_isolation = 'READ-COMMITTED';
SELECT @@transaction_isolation AS after_change;
SET SESSION transaction_isolation = 'REPEATABLE-READ';   -- 원복
SELECT @@transaction_isolation AS restored;

-- 최종 상태 확인
SELECT account_id, owner, balance FROM s19_accounts ORDER BY account_id;
