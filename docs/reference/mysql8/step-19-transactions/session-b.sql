-- =====================================================================
-- Step 19 — 두 세션 실습 : 세션 B (터미널 2)
-- ---------------------------------------------------------------------
-- 통째로 실행하지 마세요. README 순서 표에 따라 [B-1], [B-2] ... 를
-- 하나씩 복사해 실행합니다. (세션 A 와 번갈아 가며)
-- =====================================================================
USE shop;

-- =====================================================================
-- 시나리오 1 : A가 REPEATABLE READ 로 읽는 동안 B가 값을 바꾼다
-- =====================================================================
-- [B-1] A-1 실행 후에 실행. B는 별도 트랜잭션으로 값을 바꾸고 즉시 커밋.
UPDATE s19_accounts SET balance = balance + 999 WHERE account_id = 1;
COMMIT;
SELECT balance FROM s19_accounts WHERE account_id = 1;   -- 100999 (B 입장)


-- =====================================================================
-- 시나리오 2 : READ COMMITTED 대비 (값 리셋 후 진행)
-- =====================================================================
-- 값 리셋:
UPDATE s19_accounts SET balance = 100000 WHERE account_id = 1;

-- [B-2] A-4 실행 후에 실행
UPDATE s19_accounts SET balance = balance + 999 WHERE account_id = 1;
COMMIT;


-- =====================================================================
-- 시나리오 3 : A가 seat 1 을 잠근 상태에서 B의 세 가지 반응
-- =====================================================================
-- [B-3] NOWAIT : 잠긴 행을 만나면 기다리지 않고 즉시 에러 (ERROR 3572)
SELECT seat_id, seat_no FROM s19_seats WHERE seat_id = 1 FOR UPDATE NOWAIT;

-- [B-4] SKIP LOCKED : 잠긴 seat 1 은 건너뛰고, 다음 FREE 좌석을 잡는다
START TRANSACTION;
SELECT seat_id, seat_no FROM s19_seats
 WHERE status = 'FREE'
 ORDER BY seat_id
 LIMIT 2
 FOR UPDATE SKIP LOCKED;      -- seat 1 을 건너뛰고 2,3 을 반환
COMMIT;

-- [B-5] 기본 동작 : 옵션 없이 FOR UPDATE 하면 A가 커밋할 때까지 "대기"한다
--       A-7(COMMIT) 을 실행하는 순간 이 쿼리가 깨어나 결과를 낸다.
SELECT NOW(6) AS 시작;
START TRANSACTION;
SELECT seat_id, seat_no FROM s19_seats WHERE seat_id = 1 FOR UPDATE;   -- A가 커밋할 때까지 멈춰 있음
SELECT NOW(6) AS 획득;
COMMIT;


-- =====================================================================
-- 시나리오 4 : 데드락
-- =====================================================================
-- [B-6] A-8 실행 후에 실행. B는 account 2 를 먼저 잠근다.
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 1 WHERE account_id = 2;

-- [B-7] 이제 account 1 을 잠그려 한다 → A가 쥐고 있어 대기.
--       그런데 A(A-9)도 account 2 를 기다리는 중이라 서로 물림 = 데드락.
--       InnoDB 가 B를 희생자로 골라 아래 에러를 던진다:
--       ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction
UPDATE s19_accounts SET balance = balance - 1 WHERE account_id = 1;
-- 데드락으로 롤백되었으므로 트랜잭션을 다시 시작해야 한다.
ROLLBACK;
