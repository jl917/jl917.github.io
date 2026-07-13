-- =====================================================================
-- Step 19 — 두 세션 실습 : 세션 A (터미널 1)
-- ---------------------------------------------------------------------
-- 이 파일은 "통째로 실행"하지 마세요.
-- 터미널을 두 개 열고, README 의 실행 순서 표에 따라
-- [A-1], [A-2] ... 블록을 하나씩 복사해서 실행합니다.
--
-- 먼저 두 터미널 모두에서 접속하세요:
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop
-- 그리고 setup.sql 을 한 번 실행해 두세요 (둘 중 아무 터미널에서나).
-- =====================================================================
USE shop;

-- =====================================================================
-- 시나리오 1 : REPEATABLE READ 에서 반복 읽기 (기본값) — 값이 안 바뀐다
-- =====================================================================
-- [A-1] 스냅샷을 여는 첫 읽기
SET SESSION transaction_isolation = 'REPEATABLE-READ';
START TRANSACTION;
SELECT balance FROM s19_accounts WHERE account_id = 1;   -- 100000

-- ... 여기서 B-1 을 실행 (B가 +999 하고 COMMIT) ...

-- [A-2] 같은 걸 다시 읽는다 → B가 커밋했는데도 100000 그대로 (내 스냅샷)
SELECT balance FROM s19_accounts WHERE account_id = 1;   -- 여전히 100000
COMMIT;

-- [A-3] 트랜잭션을 끝낸 뒤 다시 읽으면 그제서야 B의 변경이 보인다
SELECT balance FROM s19_accounts WHERE account_id = 1;   -- 100999


-- =====================================================================
-- 시나리오 2 : READ COMMITTED 에서는 반복 읽기가 흔들린다
-- =====================================================================
-- (실습 전 값 리셋: UPDATE s19_accounts SET balance=100000 WHERE account_id=1;)
-- [A-4]
SET SESSION transaction_isolation = 'READ-COMMITTED';
START TRANSACTION;
SELECT balance FROM s19_accounts WHERE account_id = 1;   -- 100000

-- ... 여기서 B-2 실행 (B가 +999 하고 COMMIT) ...

-- [A-5] 다시 읽으면 이번엔 바뀐 값이 보인다 (non-repeatable read 발생)
SELECT balance FROM s19_accounts WHERE account_id = 1;   -- 100999
COMMIT;
SET SESSION transaction_isolation = 'REPEATABLE-READ';   -- 원복


-- =====================================================================
-- 시나리오 3 : 배타락(FOR UPDATE) — A가 잡으면 B는 기다린다
-- =====================================================================
-- [A-6] seat 1 을 배타락으로 잠근다 (커밋 전까지 계속 쥐고 있음)
START TRANSACTION;
SELECT seat_id, seat_no, status FROM s19_seats WHERE seat_id = 1 FOR UPDATE;

-- ... 여기서 B-3(NOWAIT), B-4(SKIP LOCKED), B-5(기본 대기) 를 실행해 본다 ...

-- [A-7] 잠금을 푼다 → B-5 가 그제서야 깨어난다
COMMIT;


-- =====================================================================
-- 시나리오 4 : 데드락 — 서로 상대가 쥔 자원을 기다린다
-- =====================================================================
-- (값 리셋 권장)
-- [A-8] account 1 을 먼저 잠근다
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 1 WHERE account_id = 1;

-- ... 여기서 B-6 실행 (B는 account 2 를 잠근다) ...

-- [A-9] 이제 account 2 를 잠그려 한다 → B가 쥐고 있어 대기
UPDATE s19_accounts SET balance = balance - 1 WHERE account_id = 2;
-- ↑ 이 순간 B-7 이 account 1 을 요청하면 데드락!
--   InnoDB 가 한쪽을 희생자로 골라 ERROR 1213 을 던지고,
--   살아남은 쪽(보통 A)은 여기서 정상 진행됩니다.
COMMIT;
