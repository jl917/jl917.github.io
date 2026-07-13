-- =====================================================================
-- Step 19 — 트랜잭션과 락  solution.sql  (정답 + 해설)
-- ---------------------------------------------------------------------
-- setup.sql 을 먼저 실행하세요.
-- 단일 세션으로 확인 가능한 문제(1,2)만 실행 결과를 냅니다.
-- 두 세션 문제(3,4,5)는 session-a.sql / session-b.sql 로 진행하고
-- 여기서는 "정답 쿼리"와 해설만 제공합니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] 안전한 이체
--
-- 해설:
--   출금 대상 잔액을 FOR UPDATE 로 잠그고 읽어 "확인 후 결정"합니다.
--   애플리케이션이라면 IF 로 분기하겠지만, 순수 SQL 실습에서는
--   조건이 맞을 때만 UPDATE 되도록 WHERE 에 잔액 조건을 넣는 방법이 안전합니다.
--   account 4 는 잔액 0 이라 아래 UPDATE 는 0행에 영향 → 이체가 안 일어남 → ROLLBACK.
-- ---------------------------------------------------------------------
START TRANSACTION;
SELECT account_id, balance FROM s19_accounts WHERE account_id = 4 FOR UPDATE;  -- 0

-- 잔액 >= 10000 일 때만 출금되도록 WHERE 에 조건. 0행 갱신되면 이체 실패.
UPDATE s19_accounts SET balance = balance - 10000
 WHERE account_id = 4 AND balance >= 10000;
SELECT ROW_COUNT() AS withdrawn_rows;   -- 0 이면 잔액 부족

-- 실무에서는 ROW_COUNT()=0 이면 ROLLBACK. 여기서는 잔액 부족이므로 취소.
ROLLBACK;
SELECT '이체 취소됨(잔액 부족)' AS result, balance FROM s19_accounts WHERE account_id = 4;

-- ---------------------------------------------------------------------
-- [정답 2] SAVEPOINT
--
-- 해설:
--   실수 입금을 SAVEPOINT 로 되돌리고 올바른 금액을 다시 넣습니다.
--   출금(3000)은 SAVEPOINT 이전이라 유지됩니다.
-- ---------------------------------------------------------------------
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 3000 WHERE account_id = 1;
SAVEPOINT sp_withdraw;
UPDATE s19_accounts SET balance = balance + 5000 WHERE account_id = 2;   -- 실수(많이 넣음)
ROLLBACK TO SAVEPOINT sp_withdraw;                                       -- 그 입금만 취소
UPDATE s19_accounts SET balance = balance + 3000 WHERE account_id = 2;   -- 올바르게
COMMIT;

SELECT '문제2 결과' AS step, account_id, owner, balance
FROM s19_accounts WHERE account_id IN (1,2) ORDER BY account_id;
-- 이 실습으로 잔액이 변했으니 필요하면 setup.sql 을 다시 실행해 초기화하세요.

-- ---------------------------------------------------------------------
-- [정답 3] 재고 선점 (두 세션) — 정답 쿼리
--
-- 해설:
--   A 가 FOR UPDATE 로 잠그고 커밋하지 않으면, B 의 FOR UPDATE 는 A 의 COMMIT
--   (또는 lock_wait_timeout 50초)까지 "대기"합니다. A 가 커밋하면 B 가 깨어나
--   이미 BOOKED 로 바뀐 값을 보게 됩니다.
-- ---------------------------------------------------------------------
-- 세션 A:
--   START TRANSACTION;
--   SELECT * FROM s19_seats WHERE seat_id=2 FOR UPDATE;
--   UPDATE s19_seats SET status='BOOKED', booked_by='A' WHERE seat_id=2;
--   -- (잠시 대기)
--   COMMIT;
-- 세션 B (A가 커밋할 때까지 여기서 멈춤):
--   START TRANSACTION;
--   SELECT * FROM s19_seats WHERE seat_id=2 FOR UPDATE;   -- A 커밋 후 status=BOOKED 를 봄
--   COMMIT;

-- ---------------------------------------------------------------------
-- [정답 4] 작업 큐 (두 세션) — SKIP LOCKED
--
-- 해설:
--   두 워커가 같은 쿼리를 동시에 실행해도 SKIP LOCKED 덕분에 서로 다른 행을 잡습니다.
--   A 가 seat 1 을 잠그면 B 는 그걸 건너뛰고 seat 2 를 잡습니다.
-- ---------------------------------------------------------------------
-- 세션 A:
--   START TRANSACTION;
--   SELECT seat_id FROM s19_seats WHERE status='FREE'
--    ORDER BY seat_id LIMIT 1 FOR UPDATE SKIP LOCKED;   -- seat 1
--   -- (아직 커밋 안 함)
-- 세션 B:
--   START TRANSACTION;
--   SELECT seat_id FROM s19_seats WHERE status='FREE'
--    ORDER BY seat_id LIMIT 1 FOR UPDATE SKIP LOCKED;   -- seat 1 은 잠겨서 건너뜀 → seat 2
--   COMMIT;
-- 세션 A:
--   COMMIT;

-- ---------------------------------------------------------------------
-- [정답 5] 데드락 방지 — 락 순서 통일
--
-- 해설:
--   데드락의 원인은 "락 획득 순서가 엇갈린 것"입니다.
--   두 세션 모두 account_id 오름차순(1 먼저, 그다음 2)으로 잠그면
--   B 는 account 1 을 A 가 놓을 때까지 기다렸다가 순차 진행 → 데드락 없음.
-- ---------------------------------------------------------------------
-- 세션 A (그대로): UPDATE ... account_id=1;  그다음  UPDATE ... account_id=2;
-- 세션 B (수정):   UPDATE ... account_id=1;  그다음  UPDATE ... account_id=2;
--   즉 B 도 2→1 이 아니라 1→2 순서로 바꾼다.
--   (B 가 account 1 에서 먼저 대기하므로, 순환 대기가 생기지 않는다)

-- ---------------------------------------------------------------------
-- [정답 6] 결과 예측
--
--   (a) REPEATABLE READ : A 의 두 번째 SELECT = 100000 (스냅샷 유지, 변화 없음)
--   (b) READ COMMITTED  : A 의 두 번째 SELECT = 100500 (B 의 커밋이 보임)
--
--   이유: RR 은 트랜잭션 시작 시점의 스냅샷을 끝까지 유지하고,
--        RC 는 매 SELECT 마다 최신 커밋 스냅샷을 새로 뜬다.
-- ---------------------------------------------------------------------
SELECT '정답 6: RR=100000, RC=100500' AS answer;
