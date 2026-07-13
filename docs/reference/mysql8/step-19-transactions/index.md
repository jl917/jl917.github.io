# Step 19 — 트랜잭션과 락

> **학습 목표**
> - ACID 와 트랜잭션 제어문(START TRANSACTION/COMMIT/ROLLBACK/SAVEPOINT)을 이해한다
> - 4가지 격리수준과 이상현상(Dirty/Non-repeatable/Phantom Read)을 **두 세션으로 직접 재현**한다
> - MySQL 기본값 REPEATABLE READ 와 MVCC 스냅샷 읽기의 동작을 눈으로 확인한다
> - 공유락/배타락, `FOR UPDATE`/`FOR SHARE`, `NOWAIT`/`SKIP LOCKED`(8.0), 레코드/갭/넥스트키 락을 구분한다
> - 데드락을 재현하고 `SHOW ENGINE INNODB STATUS`, `data_locks` 로 분석한다
>
> **선행 스텝**: Step 18
> **예상 소요**: 90분

> **MySQL 8.0 신기능**
> `SELECT ... FOR UPDATE/FOR SHARE` 뒤에 붙이는 **`NOWAIT`** 와 **`SKIP LOCKED`** 는 8.0에서 추가됐습니다.
> (5.7 까지는 `FOR SHARE` 대신 `LOCK IN SHARE MODE` 만 있었고, 잠긴 행을 만나면 무조건 기다릴 수밖에 없었습니다)

> ⚠️ **안전 규칙**
> 이 스텝은 락과 데드락을 다룹니다. 공용 테이블에 락을 걸면 다른 학습자를 멈추게 합니다.
> **모든 실습은 `s19_accounts` / `s19_seats` 사본에서만** 합니다. 먼저 `setup.sql` 을 실행하세요.
> ```bash
> mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < setup.sql
> ```

---

## 19-1. ACID 와 트랜잭션 제어문

**트랜잭션**은 "전부 되거나, 전부 안 되거나" 하는 작업 묶음입니다. InnoDB 는 ACID 를 보장합니다.

| 글자 | 이름 | 의미 |
|---|---|---|
| **A** | Atomicity(원자성) | 묶음 안의 모든 작업이 다 성공하거나 다 취소된다 |
| **C** | Consistency(일관성) | 제약조건(FK/CHECK/UNIQUE)을 깨지 않는 상태만 커밋된다 |
| **I** | Isolation(격리성) | 동시에 실행되는 트랜잭션이 서로 간섭하지 않는다 (수준 조절 가능) |
| **D** | Durability(지속성) | 커밋된 것은 서버가 죽어도 살아남는다 |

계좌 이체가 원자성의 교과서 예시입니다. 출금과 입금 중 하나만 되면 돈이 사라지거나 복제됩니다.

```sql
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 5000 WHERE account_id = 1;
UPDATE s19_accounts SET balance = balance + 5000 WHERE account_id = 2;
COMMIT;   -- 둘 다 확정. 중간에 서버가 죽었으면 둘 다 취소됨.
```

`ROLLBACK` 은 취소, `SAVEPOINT` 는 트랜잭션 안의 중간 지점입니다.

```sql
START TRANSACTION;
UPDATE s19_accounts SET balance = balance - 1000 WHERE account_id = 1;
SAVEPOINT after_withdraw;
UPDATE s19_accounts SET balance = balance + 1000 WHERE account_id = 3;   -- 잘못된 대상
ROLLBACK TO SAVEPOINT after_withdraw;   -- 3번 입금만 취소, 1번 출금은 유지
```

**결과** (`practice.sql` 실행)
```
+-------------------------------+------------+----------+
| step                          | account_id | balance  |
+-------------------------------+------------+----------+
| 세이브포인트 롤백 전          |          1 | 94000.00 |
| 세이브포인트 롤백 전          |          3 | 31000.00 |
+-------------------------------+------------+----------+
+-------------------------------+------------+----------+
| step                          | account_id | balance  |
+-------------------------------+------------+----------+
| 세이브포인트 롤백 후          |          1 | 94000.00 |   ← 출금은 살아있고
| 세이브포인트 롤백 후          |          3 | 30000.00 |   ← 입금만 취소됨
+-------------------------------+------------+----------+
```

> ⚠️ **함정 — DDL 은 암묵적 커밋을 일으킨다**
> 트랜잭션 도중 `CREATE`/`ALTER`/`DROP`/`TRUNCATE` 같은 DDL 을 실행하면, 진행 중이던 트랜잭션이
> **그 직전에 자동으로 커밋**됩니다. 이후 `ROLLBACK` 을 해도 앞의 변경은 되돌릴 수 없습니다.
> `RENAME TABLE`, `LOCK TABLES`, 심지어 일부 관리 명령도 마찬가지입니다.

> 💡 **실무 팁 — autocommit**
> 평소 우리는 `autocommit=1` 세계에 삽니다. `UPDATE` 한 방이 곧 커밋입니다.
> `START TRANSACTION` 을 쓰거나 `SET autocommit=0` 을 하면 수동 커밋 모드가 됩니다.
> 애플리케이션에서 커넥션 풀을 쓸 땐, 트랜잭션을 안 닫고 커넥션을 반납하면
> **다음 사람이 열린 트랜잭션을 물려받는** 사고가 납니다. 항상 COMMIT/ROLLBACK 으로 명확히 닫으세요.

---

## 19-2. 격리수준과 이상현상 — 두 세션으로 재현

동시에 도는 두 트랜잭션이 서로를 얼마나 볼 수 있는지가 **격리수준**입니다. 낮을수록 빠르지만 이상현상이 생깁니다.

| 격리수준 | Dirty Read | Non-repeatable Read | Phantom Read |
|---|:---:|:---:|:---:|
| READ UNCOMMITTED | 발생 | 발생 | 발생 |
| READ COMMITTED | 방지 | 발생 | 발생 |
| **REPEATABLE READ** (MySQL 기본) | 방지 | 방지 | **대체로 방지**\* |
| SERIALIZABLE | 방지 | 방지 | 방지 |

\* 표준 SQL 에서는 REPEATABLE READ 가 Phantom 을 허용하지만, **InnoDB 는 MVCC + 갭락으로 대부분 막습니다.**

- **Dirty Read**: 남이 아직 커밋 안 한 값을 읽음
- **Non-repeatable Read**: 같은 행을 두 번 읽었는데 값이 다름 (사이에 남이 UPDATE+커밋)
- **Phantom Read**: 같은 조건으로 두 번 조회했는데 행 개수가 다름 (사이에 남이 INSERT+커밋)

### 두 터미널 실행 방법

터미널을 **두 개** 열고 각각 접속합니다.

```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop
```

`session-a.sql`(터미널 1)과 `session-b.sql`(터미널 2)의 블록을 **아래 표 순서대로 한 줄씩(블록씩)** 번갈아 실행합니다.

### 시나리오 1 — REPEATABLE READ: 반복 읽기가 안정적이다

| 순서 | 터미널 | 블록 | 하는 일 |
|:--:|:--:|:--:|---|
| ① | A | `[A-1]` | `START TRANSACTION` 후 balance 읽기 → **100000** |
| ② | B | `[B-1]` | balance `+999` 하고 **COMMIT** |
| ③ | A | `[A-2]` | 같은 행 다시 읽기 → **여전히 100000** (내 스냅샷) |
| ④ | A | `[A-3]` | COMMIT 후 다시 읽기 → **100999** (이제 보임) |

**세션 A 출력** (실제)
```
+-----------+
| balance   |
+-----------+
| 100000.00 |     ← [A-1] 첫 읽기
+-----------+
+-----------+
| balance   |
+-----------+
| 100000.00 |     ← [A-2] B가 커밋했는데도 그대로!
+-----------+
```
**세션 B 출력**
```
+-----------+
| balance   |
+-----------+
| 100999.00 |     ← [B-1] B는 자기가 바꾼 값을 본다
+-----------+
```

A 는 트랜잭션을 시작한 순간의 **스냅샷(consistent read)** 을 계속 봅니다. B 가 커밋해도 A 의 세계는 안 바뀝니다.
이것이 InnoDB 의 **MVCC(다중 버전 동시성 제어)** 이고, MySQL 기본값이 REPEATABLE READ 인 이유입니다.

### 시나리오 2 — READ COMMITTED: 반복 읽기가 흔들린다

같은 시나리오를 A 가 `READ-COMMITTED` 로 바꿔서 (`[A-4]`) 실행하면 결과가 달라집니다.

| 순서 | 터미널 | 블록 | 결과 |
|:--:|:--:|:--:|---|
| ① | A | `[A-4]` | 첫 읽기 → **100000** |
| ② | B | `[B-2]` | `+999` COMMIT |
| ③ | A | `[A-5]` | 다시 읽기 → **100999** ← 값이 바뀜! |

**세션 A 출력** (실제)
```
+-----------+
| balance   |
+-----------+
| 100000.00 |     ← [A-4] 첫 읽기
+-----------+
+-----------+
| balance   |
+-----------+
| 100999.00 |     ← [A-5] non-repeatable read 발생!
+-----------+
```

READ COMMITTED 는 **매 SELECT 마다 새 스냅샷**을 뜹니다. 그래서 남이 커밋한 값이 트랜잭션 도중에 보입니다.

### Phantom Read — REPEATABLE READ 가 막아준다

A 가 `status='FREE'` 좌석 수를 두 번 세는 사이 B 가 새 좌석을 INSERT+커밋해도, A 의 스냅샷 읽기는 안 흔들립니다.

**세션 A 출력** (실제, REPEATABLE READ)
```
+------------+
| free_seats |
+------------+
|          5 |     ← 1st count
+------------+
+------------+
| free_seats |
+------------+
|          5 |     ← 2nd count (B가 6번째 좌석을 커밋했는데도 그대로)
+------------+
```

> 💡 **실무 팁 — 격리수준은 SESSION 으로만 바꾸세요**
> `SET SESSION transaction_isolation = 'READ-COMMITTED';` 는 내 연결에만 적용됩니다.
> `SET GLOBAL ...` 은 이후 접속하는 **모든 사용자**에게 영향을 주므로 공용 서버에서 금지입니다.
> 참고로 오라클/PostgreSQL 의 기본은 READ COMMITTED 라, 그쪽에서 온 사람은 MySQL 의 RR 동작에 자주 놀랍니다.

---

## 19-3. 락 — 공유락 vs 배타락, FOR UPDATE / FOR SHARE

읽기만 하면 MVCC 로 락 없이 잘 돌아갑니다. 하지만 **"읽은 값을 근거로 수정"** 할 땐 락이 필요합니다.
(예: 잔액 확인 후 출금 — 그 사이 남이 잔액을 바꾸면 안 됨)

| 구문 | 락 종류 | 의미 |
|---|---|---|
| `SELECT ... FOR SHARE` | 공유락(S) | 남도 읽을 수 있지만 아무도 수정 못 함 |
| `SELECT ... FOR UPDATE` | 배타락(X) | 나만 접근. 남은 읽기(잠금)도 수정도 대기 |

`FOR UPDATE` 로 A 가 seat 1 을 잠그면, B 의 `FOR UPDATE` 는 **A 가 커밋할 때까지 기다립니다.**

**세션 B 출력** (실제, `[B-5]`)
```
+----------------------------+
| 시작                       |
+----------------------------+
| 2026-07-13 10:43:03.585746 |     ← 여기서 멈춰서 A를 기다림
+----------------------------+
+---------+---------+
| seat_id | seat_no |
+---------+---------+
|       1 | A1      |             ← A가 COMMIT(A-7)한 순간 깨어남
+---------+---------+
+----------------------------+
| 획득                       |
+----------------------------+
| 2026-07-13 10:43:05.590829 |     ← 약 2초 뒤. 그동안 대기했다는 증거
+----------------------------+
```

시작 `03.58` → 획득 `05.59`, 약 2초를 기다렸습니다. A 가 잠금을 쥐고 있던 시간입니다.

### NOWAIT / SKIP LOCKED (MySQL 8.0)

기다리기 싫을 때 두 가지 선택지가 생겼습니다.

```sql
-- NOWAIT : 잠긴 행을 만나면 즉시 에러 (기다리지 않음)
SELECT seat_id, seat_no FROM s19_seats WHERE seat_id = 1 FOR UPDATE NOWAIT;
```
**결과** (`[B-3]`, 실제)
```
ERROR 3572 (HY000): Statement aborted because lock(s) could not be acquired
immediately and NOWAIT is set.
```

```sql
-- SKIP LOCKED : 잠긴 행은 건너뛰고, 잠기지 않은 행만 가져온다
SELECT seat_id, seat_no FROM s19_seats
 WHERE status='FREE' ORDER BY seat_id LIMIT 2 FOR UPDATE SKIP LOCKED;
```
**결과** (`[B-4]`, A 가 seat 1 을 잠근 상태, 실제)
```
+---------+---------+
| seat_id | seat_no |
+---------+---------+
|       2 | A2      |     ← 잠긴 seat 1 을 건너뛰고
|       3 | A3      |     ← 다음 FREE 좌석 2개를 잡았다
+---------+---------+
```

> 💡 **실무 팁 — SKIP LOCKED 는 작업 큐의 정석**
> 여러 워커가 `job` 테이블에서 할 일을 하나씩 꺼내 처리할 때
> `SELECT ... WHERE status='READY' LIMIT 1 FOR UPDATE SKIP LOCKED` 를 쓰면
> 워커들이 서로 다른 행을 잡아 **경합 없이 병렬 처리**됩니다. 좌석/쿠폰/재고 선점에도 씁니다.

> ⚠️ **함정 — 문법 순서**
> `LIMIT` 은 `FOR UPDATE` **앞에** 와야 합니다.
> `... FOR UPDATE SKIP LOCKED LIMIT 2` 는 문법 에러(ERROR 1064)입니다.
> 올바른 순서: `... ORDER BY ... LIMIT 2 FOR UPDATE SKIP LOCKED`.

---

## 19-4. 레코드락 / 갭락 / 넥스트키락

InnoDB 의 락은 "행"만 잠그는 게 아닙니다. **행과 행 사이의 빈틈(gap)** 도 잠급니다.

```
인덱스 값:   ... 10 ─────gap───── 20 ─────gap───── 30 ...
             레코드락    갭락      레코드락    갭락    레코드락
             └──────넥스트키락(레코드+앞쪽 갭)──────┘
```

| 락 | 잠그는 대상 | 목적 |
|---|---|---|
| 레코드락(Record) | 인덱스 레코드 하나 | 그 행의 수정 차단 |
| 갭락(Gap) | 레코드 사이의 빈 구간 | 그 구간에 **INSERT 를 차단** → Phantom 방지 |
| 넥스트키락(Next-key) | 레코드 + 그 앞 갭 | REPEATABLE READ 의 기본 락. 위 둘의 합 |

REPEATABLE READ 에서 범위 조건(`WHERE balance BETWEEN ...`)에 `FOR UPDATE` 를 걸면
넥스트키락이 걸려 **그 범위에 새 행을 못 넣게** 막습니다. 이것이 InnoDB 가 Phantom 을 막는 방법입니다.

> ⚠️ **함정 — 인덱스가 없으면 락이 폭발한다**
> `WHERE` 조건에 인덱스가 없으면 InnoDB 는 **스캔한 모든 행에 락**을 겁니다.
> 한 행만 바꾸려던 `UPDATE ... WHERE non_indexed_col = 1` 이 사실상 **테이블 전체를 잠글** 수 있습니다.
> 락은 "조건에 맞는 행"이 아니라 "실행 계획이 훑은 행"에 걸린다는 걸 기억하세요.

---

## 19-5. 데드락 재현과 분석

**데드락**은 두 트랜잭션이 서로 상대가 쥔 자원을 기다려 영원히 못 푸는 상태입니다.
InnoDB 는 이를 자동 감지해서 **한쪽을 희생자로 골라 롤백**시킵니다.

| 순서 | 터미널 | 블록 | 하는 일 |
|:--:|:--:|:--:|---|
| ① | A | `[A-8]` | account **1** 잠금 |
| ② | B | `[B-6]` | account **2** 잠금 |
| ③ | A | `[A-9]` | account **2** 요청 → B가 쥠, 대기 |
| ④ | B | `[B-7]` | account **1** 요청 → 서로 물림 = **데드락** |

**세션 B 출력** (실제, 희생자로 선택됨)
```
ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction
```
세션 A 는 B 가 롤백되어 자원이 풀리면서 **정상적으로 진행**됩니다.

### SHOW ENGINE INNODB STATUS 로 사후 분석

데드락 직후 (root 권한으로) `SHOW ENGINE INNODB STATUS` 를 보면 `LATEST DETECTED DEADLOCK` 섹션에 전말이 남습니다.

```bash
mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -E -e "SHOW ENGINE INNODB STATUS"
```

**결과** (실제, 핵심 부분 발췌)
```
LATEST DETECTED DEADLOCK
------------------------
*** (1) TRANSACTION:
TRANSACTION 9235, ACTIVE 3 sec starting index read
UPDATE s19_accounts SET balance = balance - 1 WHERE account_id = 2
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index PRIMARY of table `shop`.`s19_accounts` ... lock_mode X ...
 0: len 4; hex 80000001; ...          ← account_id = 1 을 쥐고 있음
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... lock_mode X locks rec but not gap waiting
 0: len 4; hex 80000002; ...          ← account_id = 2 를 기다림

*** (2) TRANSACTION:
TRANSACTION 9236, ACTIVE 2 sec starting index read
UPDATE s19_accounts SET balance = balance - 1 WHERE account_id = 1
*** (2) HOLDS THE LOCK(S): ... 80000002 ...   ← account_id = 2 를 쥐고
*** (2) WAITING ...        ... 80000001 ...   ← account_id = 1 을 기다림
*** WE ROLL BACK TRANSACTION (2)              ← InnoDB 가 트랜잭션 2를 희생시킴
```

`hex 80000001` 은 account_id 1, `80000002` 는 2 입니다 (InnoDB 인코딩). 두 트랜잭션이 서로 X 락을 엇갈려 쥐고 있음이 그대로 보입니다.

### 진행 중인 락 대기 조회 (data_locks / innodb_lock_waits)

데드락이 나기 **전**, 즉 한쪽이 대기 중일 때는 `performance_schema.data_locks` 로 실시간 조회할 수 있습니다.
(이 뷰와 `SHOW ENGINE INNODB STATUS` 는 **PROCESS 권한**이 필요합니다. 이 실습 환경의 `learner` 는 PROCESS 를 갖고 있어 조회되지만,
운영에서 최소 권한 계정을 쓴다면 관리자 계정으로 봐야 합니다)

```sql
SELECT ENGINE_TRANSACTION_ID AS trx, OBJECT_NAME AS tbl, INDEX_NAME AS idx,
       LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
FROM performance_schema.data_locks WHERE OBJECT_NAME='s19_seats';
```

**결과** (실제, A 가 seat 1 을 쥐고 B 가 대기 중)
```
+------+-----------+---------+-----------+---------------+-------------+-----------+
| trx  | tbl       | idx     | LOCK_TYPE | LOCK_MODE     | LOCK_STATUS | LOCK_DATA |
+------+-----------+---------+-----------+---------------+-------------+-----------+
| 9261 | s19_seats | NULL    | TABLE     | IX            | GRANTED     | NULL      |
| 9262 | s19_seats | NULL    | TABLE     | IX            | GRANTED     | NULL      |
| 9261 | s19_seats | PRIMARY | RECORD    | X,REC_NOT_GAP | GRANTED     | 1         |  ← A가 획득
| 9262 | s19_seats | PRIMARY | RECORD    | X,REC_NOT_GAP | WAITING     | 1         |  ← B가 대기
+------+-----------+---------+-----------+---------------+-------------+-----------+
```

`sys.innodb_lock_waits` 는 "누가 누구를 막고 있는지"를 요약해 줍니다.

```sql
SELECT waiting_pid, waiting_query, blocking_pid, wait_age
FROM sys.innodb_lock_waits;
```
**결과** (실제)
```
  waiting_pid: 537
waiting_query: UPDATE s19_seats SET status='B ... WHERE seat_id=1
 blocking_pid: 538
     wait_age: 00:00:01
```

`blocking_pid` 를 `KILL` 하면 대기를 풀 수 있습니다 (`sql_kill_blocking_query` 컬럼이 명령을 만들어 줍니다).

> 💡 **실무 팁 — 데드락을 줄이는 법**
> 1. **항상 같은 순서로 락을 잡아라.** 위 예시는 A 는 1→2, B 는 2→1 로 순서가 엇갈려서 터졌습니다.
>    모두가 `account_id` 오름차순으로 잡으면 데드락이 안 납니다.
> 2. **트랜잭션을 짧게.** 락을 쥔 시간이 짧을수록 충돌 확률이 낮습니다.
> 3. **데드락은 정상이다.** 애플리케이션은 `ERROR 1213` 을 잡아 **재시도**하도록 짜야 합니다. 없앨 수는 없습니다.

---

## 19-6. 긴 트랜잭션의 해악

트랜잭션을 열어놓고 오래 안 닫으면 여러 재앙이 겹칩니다.

- **언두 로그(undo)가 부풀어 오른다.** MVCC 는 "오래된 스냅샷을 보는 트랜잭션"이 하나라도 있으면
  그 시점 이후의 옛 행 버전을 못 지웁니다. 긴 트랜잭션 하나가 언두를 무한정 쌓이게 해
  **`ibdata`/언두 테이블스페이스가 수십 GB 로 폭증**하는 사고가 실무에서 흔합니다.
- **락을 오래 쥐어 다른 트랜잭션을 굶긴다.** (`innodb_lock_wait_timeout`, 기본 50초에 걸려 줄줄이 실패)
- **purge 스레드가 밀려 성능이 전반적으로 저하**된다.

확인용 쿼리 (root):

```sql
-- 오래 열려 있는 트랜잭션 찾기
SELECT trx_id, trx_started,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_sec,
       trx_rows_locked, trx_query
FROM information_schema.innodb_trx
ORDER BY trx_started;

-- 히스토리 리스트 길이(언두가 얼마나 쌓였나) — 계속 커지면 위험 신호
SHOW ENGINE INNODB STATUS\G   -- "History list length" 항목
```

> ⚠️ **함정 — "읽기만 하는" 트랜잭션도 위험하다**
> 리포트를 만드느라 `START TRANSACTION` 후 몇십 분 동안 SELECT 만 하는 세션도
> 오래된 스냅샷을 붙잡아 **언두 purge 를 막습니다.**
> 배치/분석 쿼리는 트랜잭션을 짧게 끊거나, 오토커밋으로 돌리세요.
> 커넥션 풀이 트랜잭션을 안 닫고 커넥션을 반납하는 것도 같은 문제를 만듭니다.

---

## 정리

### 트랜잭션 제어문

| 구문 | 의미 |
|---|---|
| `START TRANSACTION` / `BEGIN` | 트랜잭션 시작 (수동 커밋 모드) |
| `COMMIT` | 확정 |
| `ROLLBACK` | 전체 취소 |
| `SAVEPOINT s` / `ROLLBACK TO SAVEPOINT s` | 부분 지점 / 그 지점까지 취소 |
| `SET SESSION transaction_isolation = ...` | 격리수준 변경 (SESSION 만!) |

### 격리수준 요약

| 수준 | Dirty | Non-repeatable | Phantom | 비고 |
|---|:--:|:--:|:--:|---|
| READ UNCOMMITTED | O | O | O | 거의 안 씀 |
| READ COMMITTED | X | O | O | 오라클/PG 기본 |
| **REPEATABLE READ** | X | X | X\* | **MySQL 기본** (MVCC+갭락) |
| SERIALIZABLE | X | X | X | 모든 SELECT 가 잠금 읽기 |

### 락 요약

| 구문 | 락 | 8.0 옵션 |
|---|---|---|
| `FOR SHARE` | 공유락(S) | `NOWAIT`, `SKIP LOCKED` |
| `FOR UPDATE` | 배타락(X) | `NOWAIT`, `SKIP LOCKED` |

### 진단 도구

| 도구 | 용도 | 권한 |
|---|---|---|
| `SHOW ENGINE INNODB STATUS` | 최근 데드락, 히스토리 길이 | PROCESS |
| `performance_schema.data_locks` | 현재 걸린 락 목록 | PROCESS |
| `sys.innodb_lock_waits` | 대기-차단 관계 요약 | PROCESS |
| `information_schema.innodb_trx` | 진행 중 트랜잭션(수명) | PROCESS |

---

## 정리(cleanup)

```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < cleanup.sql
```

---

## 연습문제

`exercise.sql` 을 풀어 보세요 (일부는 두 세션 필요). 정답은 `solution.sql`.

1. 안전한 계좌 이체 프로시저형 트랜잭션 (잔액 부족 시 롤백)
2. SAVEPOINT 로 부분 롤백
3. FOR UPDATE 로 재고 선점 (두 세션)
4. SKIP LOCKED 작업 큐 흉내 (두 세션)
5. 데드락 재현 후 락 순서로 방지하기
6. READ COMMITTED vs REPEATABLE READ 결과 차이 예측

---

## 다음 단계
→ [Step 20 — 저장 프로그램](../step-20-stored-programs/index.md)

---

## 실습 파일

이 스텝은 **`setup.sql` 로 전용 사본 테이블(`s19_accounts`, `s19_seats`)을 만드는 것에서 시작**합니다.
그다음 혼자 확인할 수 있는 개념은 `practice.sql` 한 파일로 돌리고, 격리수준·락·데드락처럼 **동시성이 필요한 실습은 터미널 두 개를 열어 `session-a.sql` 과 `session-b.sql` 의 블록을 번갈아 실행**합니다.
마지막으로 `exercise.sql` 로 스스로 풀어 보고 `solution.sql` 로 답을 맞춘 뒤, `cleanup.sql` 로 사본 테이블을 정리합니다.

### setup.sql

가장 먼저 실행하는 준비 스크립트입니다. 이 스텝의 모든 실습이 **공용 테이블을 건드리지 않고 `s19_` 접두사 사본에서만** 이뤄지도록 두 테이블을 새로 만듭니다.

- `s19_accounts` 는 `account_id INT PRIMARY KEY` 와 `balance DECIMAL(12,2)` 를 가진 계좌 테이블로, 김민수(100000) · 이지은(50000) · 박철수(30000) · **최영희(0)** 4행을 넣습니다. 최영희의 잔액이 0인 것은 연습문제 1번의 "잔액 부족 → ROLLBACK" 시나리오를 위한 의도된 값입니다.
- `s19_seats` 는 `status ENUM('FREE','BOOKED')` 를 가진 좌석 테이블로 A1~A5 5행이 모두 `FREE` 로 들어갑니다. 19-3의 `SKIP LOCKED` 실습에서 "잠긴 seat 1 을 건너뛰고 2, 3 을 가져온다"는 결과가 나오려면 이 5행이 그대로 있어야 합니다.
- 두 테이블 모두 `ENGINE=InnoDB` 입니다. 트랜잭션과 행 단위 락은 InnoDB 에서만 동작하므로 이 지정이 스텝 전체의 전제입니다. `idx_status` 인덱스가 있어야 `WHERE status='FREE'` 조건의 락이 스캔한 전 행으로 번지지 않습니다(19-4의 "인덱스가 없으면 락이 폭발한다" 함정과 짝을 이룹니다).
- 맨 앞의 `DROP TABLE IF EXISTS` 덕분에 **여러 번 실행해도 안전**합니다. 오히려 실습 도중 잔액이 뒤엉키면 이 파일을 다시 돌려 초기 상태로 되돌리는 것이 정석입니다(`session-a.sql` 의 "값 리셋 권장" 주석이 가리키는 것이 이 동작입니다).

```sql file="./setup.sql"
```

### practice.sql

19-1 "ACID 와 트랜잭션 제어문" 본문을 그대로 따라가는 **단일 세션용** 스크립트입니다. 두 세션이 필요한 내용은 일부러 빼 두었으므로, `--table` 옵션을 붙여 통째로 실행하면 됩니다.

- `[19-1]` 은 `SELECT @@autocommit` 으로 평소 우리가 자동 커밋 세계에 살고 있음을 먼저 확인시킵니다.
- `[19-2]` 는 5000원 이체(`balance - 5000` / `balance + 5000`)를 한 트랜잭션에 묶고, **커밋 전과 커밋 후를 각각 SELECT** 해서 원자성을 눈으로 보여줍니다. 이 블록은 `COMMIT` 까지 하므로 1번 계좌가 95000, 2번이 55000 으로 **실제로 바뀝니다.**
- `[19-3]` 은 `ROLLBACK` 입니다. `UPDATE ... SET balance = 0 WHERE account_id = 1` 로 잔액을 날리는 "실수"를 저지른 뒤, 커밋 전 SELECT 로 **내 세션 안에서만 0이 보인다**는 것을 확인하고 `ROLLBACK` 으로 되돌립니다. 롤백 후 다시 읽으면 `[19-2]` 직후 값인 95000 으로 복구되어 있습니다.
- `[19-4]` 는 본문의 SAVEPOINT 예시 그대로입니다. 1번에서 1000 출금 → `SAVEPOINT after_withdraw` → 3번에 1000 입금(잘못된 대상) → `ROLLBACK TO SAVEPOINT after_withdraw` 를 거치면 **출금은 남고 입금만 취소**되어, 본문에 실린 "94000 / 30000" 출력이 재현됩니다. 마지막의 `ROLLBACK` 은 실습 흔적을 지우기 위한 전체 취소입니다.
- `[19-5]` 의 DDL 암묵 커밋 예시는 **의도적으로 전부 주석 처리**되어 있습니다. 실제로 실행하면 `CREATE TABLE s19_tmp` 시점에 앞의 `UPDATE` 가 커밋되어 `ROLLBACK` 이 먹지 않는, 되돌릴 수 없는 상태가 되기 때문입니다. 함정을 직접 확인하고 싶다면 주석을 풀되 결과를 되돌릴 수 없다는 점을 감수해야 합니다.
- `[19-6]` 은 `SET SESSION transaction_isolation = 'READ-COMMITTED'` 로 바꿨다가 곧바로 `'REPEATABLE-READ'` 로 **원복**합니다. `SET GLOBAL` 이 아니라 `SET SESSION` 이라는 점이 핵심입니다.

```sql file="./practice.sql"
```

### session-a.sql

19-2 ~ 19-5 의 동시성 실습에서 **터미널 1(세션 A)** 이 실행하는 블록 모음입니다. **통째로 실행하면 안 됩니다.** 본문의 순서 표를 보며 `[A-1]`, `[A-2]` … 를 하나씩 복사해 붙여넣어야 합니다. 통째로 돌리면 B 가 끼어들 틈이 없어 이상현상도 데드락도 재현되지 않습니다.

- `[A-1]`~`[A-3]`: `SET SESSION transaction_isolation = 'REPEATABLE-READ'` 후 `START TRANSACTION` 으로 스냅샷을 엽니다. B 가 `+999` 를 커밋해도 `[A-2]` 의 재조회는 **100000 그대로**이고, `COMMIT` 뒤인 `[A-3]` 에서야 100999 가 보입니다. MVCC 스냅샷 읽기의 실체입니다.
- `[A-4]`~`[A-5]`: 같은 흐름을 `'READ-COMMITTED'` 로 돌리면 `[A-5]` 에서 값이 100999 로 **바뀌어 보입니다**(non-repeatable read). 블록 끝에서 `'REPEATABLE-READ'` 로 원복하는 줄을 빠뜨리면 뒤이은 실습의 결과가 달라지니 주의하세요.
- `[A-6]`~`[A-7]`: `SELECT ... WHERE seat_id = 1 FOR UPDATE` 로 seat 1 에 배타락을 걸고 **커밋하지 않은 채 멈춥니다.** 이 상태가 유지되는 동안 B 가 `[B-3]`(NOWAIT), `[B-4]`(SKIP LOCKED), `[B-5]`(기본 대기)의 세 가지 반응을 실험합니다. `[A-7]` 의 `COMMIT` 이 곧 `[B-5]` 를 깨우는 신호입니다.
- `[A-8]`~`[A-9]`: 데드락 유발 블록입니다. A 는 account **1 → 2** 순서로 잠급니다. B 가 **2 → 1** 로 반대 방향을 잡기 때문에 순환 대기가 생깁니다. 보통 A 는 살아남고 B 가 `ERROR 1213` 희생자가 됩니다.
- 각 시나리오 사이에 잔액이 계속 변하므로, 결과가 본문과 어긋나면 `setup.sql` 을 다시 실행해 초기화한 뒤 이어가세요.

```sql file="./session-a.sql"
```

### session-b.sql

**터미널 2(세션 B)** 가 실행하는 짝 파일입니다. 마찬가지로 `[B-1]`, `[B-2]` … 를 순서 표에 맞춰 하나씩만 실행합니다.

- `[B-1]`/`[B-2]`: A 가 트랜잭션을 연 뒤 끼어들어 `balance + 999` 를 하고 **즉시 COMMIT** 합니다. 이 커밋이 A 에게 보이느냐 안 보이느냐가 격리수준의 차이를 가릅니다. `[B-2]` 앞의 `UPDATE ... SET balance = 100000` 은 시나리오 2를 같은 출발점에서 시작하기 위한 **값 리셋**이니 건너뛰지 마세요.
- `[B-3]`: `FOR UPDATE NOWAIT` — A 가 쥔 seat 1 을 만나 기다리지 않고 곧장 `ERROR 3572` 로 끝납니다. 에러가 나는 것이 정상이며, 그것이 이 블록의 학습 포인트입니다.
- `[B-4]`: `WHERE status='FREE' ORDER BY seat_id LIMIT 2 FOR UPDATE SKIP LOCKED` — 잠긴 seat 1 을 건너뛰고 **seat 2, 3** 을 잡습니다. `LIMIT` 이 `FOR UPDATE` **앞**에 있다는 점을 눈여겨보세요. 순서를 바꾸면 `ERROR 1064` 문법 에러입니다.
- `[B-5]`: 옵션 없는 기본 `FOR UPDATE` 라 A 가 커밋할 때까지 **멈춥니다**. 앞뒤를 `SELECT NOW(6) AS 시작` / `SELECT NOW(6) AS 획득` 으로 감싸 두었기 때문에, 두 타임스탬프의 차(본문 예시에서는 약 2초)가 곧 **대기한 시간의 증거**가 됩니다. 만약 A 가 계속 커밋하지 않으면 `innodb_lock_wait_timeout`(기본 50초)에 걸려 에러로 끝납니다.
- `[B-6]`/`[B-7]`: 데드락의 반대편입니다. B 는 account **2 를 먼저** 잠그고(`[B-6]`) 그다음 **1 을 요청**(`[B-7]`)합니다. A 와 순서가 엇갈리므로 이 순간 데드락이 확정되고, InnoDB 가 B 를 희생자로 골라 `ERROR 1213` 을 던집니다. 이미 롤백된 상태이므로 마지막 `ROLLBACK` 으로 트랜잭션을 깨끗이 닫습니다.

```sql file="./session-b.sql"
```

### exercise.sql

본문 "연습문제"의 6문항이 **빈 칸으로** 들어 있는 파일입니다. `-- 여기에 작성` 아래에 직접 쿼리를 채워 넣으며 풉니다.

- 문제 1(안전한 이체)은 잔액이 0인 **account 4** 를 출금 대상으로 잡습니다. 즉 "성공하는 이체"가 아니라 **취소되어야 하는 이체**를 짜는 것이 목적입니다.
- 문제 2는 `practice.sql` 의 SAVEPOINT 흐름을 응용해, 실수 입금(5000)을 되돌리고 올바른 금액(3000)을 다시 넣어 최종적으로 1번 -3000 / 2번 +3000 이 되게 만듭니다.
- 문제 3·4·5 는 **터미널 두 개가 필요합니다.** `session-a.sql` / `session-b.sql` 의 블록을 seat 번호나 락 순서만 바꿔 응용하는 형태입니다.
- 문제 6 은 실행 없이 먼저 답을 예측한 뒤 확인하는 문제이므로, **답을 쓰기 전에 돌려보지 않는 것**이 학습 효과가 큽니다.
- 문제를 풀다 보면 잔액이 초기값에서 벗어납니다. 다음 문제로 넘어가기 전에 `setup.sql` 을 다시 실행해 초기화하면 헷갈리지 않습니다.

```sql file="./exercise.sql"
```

### solution.sql

연습문제의 정답과 해설입니다. 단일 세션으로 확인 가능한 **문제 1·2 만 실제로 실행되는 SQL** 이고, 두 세션이 필요한 문제 3·4·5 는 정답 쿼리가 **주석으로** 적혀 있습니다(한 세션에서 그대로 돌리면 재현되지 않기 때문입니다).

- 정답 1 의 핵심은 `WHERE account_id = 4 AND balance >= 10000` 처럼 **조건을 WHERE 에 넣어** 잔액이 부족하면 아예 0행만 갱신되게 만드는 기법입니다. 뒤이은 `SELECT ROW_COUNT()` 가 0 이면 잔액 부족이라는 신호이고, 실무에서는 이 값을 보고 `ROLLBACK` 을 결정합니다.
- 정답 2 는 `SAVEPOINT sp_withdraw` 이전의 출금(3000)은 유지되고, 이후의 실수 입금(5000)만 `ROLLBACK TO SAVEPOINT` 로 날아간다는 것을 보여줍니다. 이 스크립트는 `COMMIT` 까지 하므로 **잔액이 실제로 바뀝니다.** 이어서 다른 실습을 할 거라면 `setup.sql` 로 초기화하세요(파일 안에도 같은 안내가 있습니다).
- 정답 5 는 데드락 해법을 한 줄로 요약합니다 — B 도 `2 → 1` 이 아니라 **`1 → 2` 오름차순**으로 잠그면 순환 대기가 생기지 않습니다.
- 정답 6 은 RR = 100000, RC = 100500 입니다. 마지막 `SELECT` 문이 이 답을 출력해 줍니다.

```sql file="./solution.sql"
```

### cleanup.sql

실습을 끝낼 때 실행하는 뒷정리 스크립트입니다. 짧지만 **순서에 의미가 있습니다.**

- 맨 앞의 `ROLLBACK` 은 두 세션 실습 도중 **닫지 않고 남겨 둔 트랜잭션**을 정리합니다. 열린 트랜잭션이 없으면 아무 일도 하지 않으므로 무해합니다. 반대로 열린 채로 `DROP TABLE` 을 시도하면 락 때문에 오래 대기할 수 있습니다.
- 그다음 `DROP TABLE IF EXISTS s19_accounts` / `s19_seats` 로 사본 테이블을 지웁니다. **`s19_` 접두사가 붙은 실습 사본만** 지우므로 공용 테이블에는 영향이 없습니다.
- ⚠️ 이 스크립트는 파괴적입니다. 실습 결과를 더 살펴볼 생각이라면 실행하지 마세요. 다시 시작하려면 `setup.sql` 을 돌리면 됩니다.
- 참고로 `DROP TABLE` 은 DDL 이라 **암묵적 커밋**을 일으킵니다(19-1 의 함정). 앞의 `ROLLBACK` 을 먼저 두는 이유이기도 합니다.

```sql file="./cleanup.sql"
```
