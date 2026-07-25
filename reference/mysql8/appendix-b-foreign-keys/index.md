# 부록 B — 외래키(FK) 실무 완전 정복

> **학습 목표**
> - FK 의 기본 동작을 넘어, **실무에서 실제로 부딪히는 이슈**를 원인·재현·해결까지 한 번에 정리한다
> - `errno 150` / `1452` / `1451` / `3730` / `3780` / `1822` — **FK 에러 코드를 보고 바로 원인을 짚는다**
> - "우리 회사는 FK 를 안 쓴다"는 말의 **진짜 트레이드오프**를 이해한다 (애플리케이션 레벨 FK)
> - ORM(JPA/Hibernate)·마이그레이션·온라인 스키마 변경·파티셔닝·샤딩에서 FK 가 일으키는 **실전 함정**을 피한다
> - FK 로 생기는 **데드락·락 병목·고아 행**을 진단하고 정리하는 쿼리를 손에 익힌다
>
> **선행 스텝**: [Step 13 — 제약 조건과 정규화](../step-13-constraints/index.md) (특히 [13-5 ~ 13-6](../step-13-constraints/index.md))
> **예상 소요**: 70분

> 이 부록은 Step 13 의 FK 절을 **다 읽었다고 가정**합니다. 기본 문법(5가지 `ON DELETE/UPDATE` 옵션, 인덱스 자동 생성)은 반복하지 않고, **그 다음에 실무에서 터지는 것들**만 모았습니다.

---

## B-0. 실습 준비

이 부록의 예제는 `s13b_` 접두사 테이블만 씁니다. 공용 테이블(`customers`, `orders` …)은 건드리지 않습니다.

```sql
-- 정리부터 (재실행 안전)
SET FOREIGN_KEY_CHECKS = 1;
DROP TABLE IF EXISTS s13b_child, s13b_parent,
  s13b_order_item, s13b_order, s13b_a, s13b_b;
```

> 💡 이 부록의 SQL 은 [`practice.sql`](./practice.sql) 에 그대로 담겨 있습니다. 에러 메시지는 직접 재현해 보는 것이 가장 잘 외워집니다.

---

## B-1. 한 장 요약 — FK 가 실제로 하는 일

FK 는 선언 한 줄이지만, InnoDB 는 매 DML 마다 아래를 **자동으로** 합니다.

| 작업 | FK 가 추가로 하는 일 |
|---|---|
| 자식 INSERT / UPDATE | 부모에 그 값이 **있는지 조회** → 없으면 거부(`1452`), 부모 행에 **공유락(S)** |
| 부모 DELETE | 자식이 참조하는지 조회 → 옵션대로 처리(`RESTRICT`면 거부 `1451`, `CASCADE`면 자식 삭제) |
| 부모 PK UPDATE | `ON UPDATE` 옵션대로 자식 전파 |
| 테이블 DROP | 참조하는 자식이 있으면 거부(`3730`) |

**핵심 3줄**
1. FK 비용의 본질은 "부모 조회가 캐시에서 끝나는가" — 부모가 작으면 거의 공짜, 부모가 크고 콜드면 비쌈 ([Step 13-6](../step-13-constraints/index.md) 참조).
2. FK 는 **참조되는 컬럼(부모)** 뿐 아니라 **참조하는 컬럼(자식)에도 인덱스가 필요** → 자식 인덱스는 InnoDB 가 자동 생성.
3. FK 는 **데이터 정합성을 DB 가 보증**해 주지만, 그 대가로 **락·순서·마이그레이션 제약**을 떠안는다.

---

## B-2. FK 에러 코드 카탈로그 (실무 1순위 암기)

> 로그에서 이 번호만 보고 원인을 짚을 수 있어야 합니다.

| 에러 | 언제 | 원인 | 해결 |
|---|---|---|---|
| **1452** `Cannot add or update a child row` | 자식 INSERT/UPDATE | 부모에 **없는 값**을 참조 | 부모 먼저 넣기 / 값 확인 / NULL 허용이면 NULL |
| **1451** `Cannot delete or update a parent row` | 부모 DELETE/UPDATE | 자식이 아직 **참조 중** (RESTRICT) | 자식 먼저 정리 / CASCADE 검토 |
| **3730** `Cannot drop table ... referenced by a foreign key` | 부모 `DROP TABLE` | 다른 테이블이 참조 중 | 자식 FK 먼저 drop / `SET FOREIGN_KEY_CHECKS=0` |
| **3780** `columns ... are incompatible` | FK **생성 시** | 부모·자식 컬럼 **타입/부호/콜레이션 불일치** | 타입 정확히 일치시키기 (아래 B-3) |
| **1822** `Missing index for constraint` | FK 생성 시 | **부모** 참조 컬럼에 인덱스 없음 | 부모 컬럼에 PK/UNIQUE/INDEX |
| **150** (`errno: 150`) | FK 생성 시 (일반) | 위 원인들의 InnoDB 내부 코드 | `SHOW ENGINE INNODB STATUS` 의 `LATEST FOREIGN KEY ERROR` 확인 |

> 💡 **FK 생성이 실패하면 항상 이 명령부터**:
> ```sql
> SHOW ENGINE INNODB STATUS\G
> ```
> 출력의 `LATEST FOREIGN KEY ERROR` 섹션에 **어떤 컬럼이 왜 안 맞는지** 사람이 읽을 수 있게 적혀 있습니다. `errno 150` 만 보고 헤매지 마세요.

### 재현 — 1452 (고아 자식)

```sql
CREATE TABLE s13b_parent (id INT UNSIGNED PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE s13b_child (
  cid INT AUTO_INCREMENT PRIMARY KEY,
  pid INT UNSIGNED NOT NULL,
  KEY idx_pid (pid),
  CONSTRAINT fk_b_child FOREIGN KEY (pid) REFERENCES s13b_parent(id)
) ENGINE=InnoDB;

INSERT INTO s13b_parent VALUES (1);
INSERT INTO s13b_child (pid) VALUES (999);   -- 부모 999 없음
```
```
ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint
fails (`shop`.`s13b_child`, CONSTRAINT `fk_b_child` FOREIGN KEY (`pid`)
REFERENCES `s13b_parent` (`id`))
```

### 재현 — 1451 (참조되는 부모 삭제)

```sql
INSERT INTO s13b_child (pid) VALUES (1);
DELETE FROM s13b_parent WHERE id = 1;        -- 자식이 참조 중
```
```
ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key
constraint fails (`shop`.`s13b_child`, CONSTRAINT `fk_b_child` ...)
```

---

## B-3. FK 생성이 안 되는 진짜 흔한 이유 — 타입 불일치 (errno 3780/150)

FK 는 부모·자식 컬럼의 타입이 **완벽히 같아야** 걸립니다. "둘 다 INT처럼 보이는데 왜 안 되지?"의 90%는 아래입니다.

| 불일치 요소 | 예 | 결과 |
|---|---|---|
| **부호(SIGNED/UNSIGNED)** | 부모 `INT UNSIGNED`, 자식 `INT` | ❌ 3780 |
| **길이/정밀도** | 부모 `BIGINT`, 자식 `INT` | ❌ 3780 |
| **문자셋/콜레이션** | 부모 `utf8mb4_0900_ai_ci`, 자식 `utf8mb4_general_ci` | ❌ 3780 |
| **부모에 인덱스 없음** | 참조 컬럼이 PK/UNIQUE/INDEX 아님 | ❌ 1822 |

```sql
CREATE TABLE s13b_a (id INT UNSIGNED PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE s13b_b (
  id INT PRIMARY KEY,                         -- ⚠️ UNSIGNED 빠짐
  a_id INT,                                   -- ⚠️ 부모는 UNSIGNED 인데 여긴 SIGNED
  CONSTRAINT fk_b_a FOREIGN KEY (a_id) REFERENCES s13b_a(id)
) ENGINE=InnoDB;
```
```
ERROR 3780 (HY000): Referencing column 'a_id' and referenced column 'id' in
foreign key constraint 'fk_b_a' are incompatible.
```

> ⚠️ **함정 — 문자열 FK 의 콜레이션 지옥**
> `VARCHAR` 컬럼을 FK 로 걸 때, 부모와 자식의 **콜레이션이 1글자라도 다르면** FK 가 안 걸립니다. 게다가 콜레이션이 다르면 이후 **조인할 때도 인덱스를 못 타는**(암묵 변환) 별개의 성능 문제까지 생깁니다. 그래서 **FK 키는 되도록 정수 대리키(surrogate key)** 를 쓰는 것이 실무 정석입니다.

> 💡 **실무 팁**: 테이블을 여러 번에 나눠 만들 때는 `CREATE TABLE ... LIKE` 로 부모 컬럼 정의를 복사하거나, 팀 컨벤션으로 **"ID 는 무조건 `BIGINT UNSIGNED`"** 처럼 못 박아 두면 이 부류의 사고가 통째로 사라집니다.

---

## B-4. 순서 문제 — 생성·삭제·적재의 방향

FK 는 **부모가 먼저 존재**해야 한다는 순서 제약을 만듭니다. 실무에서 이게 마이그레이션 스크립트와 덤프 복원을 괴롭힙니다.

| 작업 | 올바른 순서 |
|---|---|
| 테이블 **생성** | 부모 → 자식 |
| 데이터 **적재** | 부모 → 자식 |
| 데이터 **삭제** | 자식 → 부모 |
| 테이블 **DROP** | 자식 → 부모 |

### 순환 참조(circular FK)는 순서로 못 푼다

`A → B`, `B → A` 처럼 서로 참조하면 "누구를 먼저?"가 성립하지 않습니다. 해법:

```sql
-- 1) FK 없이 두 테이블을 먼저 만들고
-- 2) 나중에 ALTER 로 FK 를 추가하거나
-- 3) 적재 시에만 잠깐 검사를 끈다
SET FOREIGN_KEY_CHECKS = 0;
-- ... 부모/자식 순서 상관없이 적재 ...
SET FOREIGN_KEY_CHECKS = 1;   -- 켠 뒤 고아 행 검증 필수! (B-8)
```

> ⚠️ **함정 — `mysqldump` 복원이 FK 순서로 실패**
> 덤프 파일은 테이블을 알파벳순으로 복원하는데, 자식이 부모보다 먼저 나오면 `1452` 로 터집니다. 그래서 `mysqldump` 는 기본적으로 덤프 앞머리에 `SET FOREIGN_KEY_CHECKS=0` 을 넣습니다. **직접 만든 마이그레이션 스크립트에는 이게 없으니** 순서를 맞추거나 검사를 꺼야 합니다.

> 💡 **실무 팁 — 전체 스키마를 안전하게 drop**
> ```sql
> SET FOREIGN_KEY_CHECKS = 0;
> -- DROP TABLE 들 (순서 신경 안 써도 됨)
> SET FOREIGN_KEY_CHECKS = 1;
> ```

---

## B-5. "우리 회사는 FK 를 안 씁니다" — 애플리케이션 레벨 FK

대규모 서비스(특히 대형 인터넷 기업)에서 **DB FK 를 아예 선언하지 않는** 곳이 많습니다. 신입이 "정합성 어떻게 지켜요?"라고 물으면 팀장은 "애플리케이션에서 지켜요"라고 답합니다. 이게 무슨 뜻이고 트레이드오프가 뭔지 알아야 합니다.

| | DB 레벨 FK | 애플리케이션 레벨 FK |
|---|---|---|
| 정합성 보증 | **DB 가 100% 강제** | 코드에 의존 (버그·경합에 뚫림) |
| 쓰기 성능 | 부모 조회·락 오버헤드 | 없음 (더 빠름) |
| **샤딩** | 부모/자식이 다른 샤드면 **불가능** | 가능 (앱이 조율) |
| 온라인 스키마 변경 | `gh-ost`/`pt-osc` 가 **싫어함** (B-6) | 자유로움 |
| 대량 삭제 | CASCADE 폭발 위험 | 앱이 배치로 제어 |
| 실수 방어 | 잘못된 참조를 **DB 가 즉시 차단** | 차단 못 함 → 고아 데이터 축적 |

**결론(실무 기준선)**

- **단일 DB · OLTP 서비스**라면 **FK 를 켜세요.** 정합성 사고의 비용이 성능 오버헤드보다 훨씬 큽니다.
- **샤딩됐거나 초당 수만 쓰기**라면 FK 를 빼는 것이 합리적일 수 있습니다. 대신 **고아 행 정기 점검 배치**(B-8)를 반드시 운영해야 합니다.
- 어느 쪽이든 **"FK 없음 = 정합성 안 지켜도 됨"이 아닙니다.** 책임 주체가 DB 에서 애플리케이션으로 넘어갔을 뿐입니다.

> ⚠️ **함정 — FK 없이 컬럼명으로 관계를 추측**
> FK 를 선언하지 않으면 `information_schema` 에 관계 정보가 없어서, 새 개발자는 `order_id`, `customer_id` 같은 **컬럼 이름으로 관계를 추측**해야 합니다. ERD 툴도 관계선을 못 그립니다. FK 는 정합성 도구이자 **살아있는 문서**이기도 합니다.

---

## B-6. ORM · 마이그레이션 · 온라인 스키마 변경의 함정

### JPA / Hibernate

- [ ] ⚠️ **`CascadeType.REMOVE` / `orphanRemoval` 와 DB `ON DELETE CASCADE` 를 동시에 걸지 마세요.** 둘 다 켜면 **이중 삭제 로직**이 되어, 앱이 자식을 하나씩 지우는 동안 DB 도 CASCADE 를 돌립니다. 보통은 **DB CASCADE 를 쓰고 앱 cascade 는 끄거나**, 반대로 통일하세요.
- [ ] ⚠️ **FK 자식 컬럼 인덱스 누락** — JPA 가 `@JoinColumn` 으로 FK 를 만들면 InnoDB 가 자식 인덱스를 자동 생성하지만, **일부 스키마 생성 전략/DB 에선 안 만들어질 수 있습니다.** 자식 FK 컬럼에 인덱스가 없으면 부모 삭제 시 **자식 풀스캔**이 일어나 대형 테이블에서 재앙이 됩니다.
- [ ] ⚠️ **양방향 연관에서 지연 로딩 + FK** — N+1 과 별개로, `@ManyToOne` 이 `NOT NULL` FK 인데 앱이 부모 없이 자식을 저장하려 하면 `1452`.
- [ ] 💡 마이그레이션 도구(Flyway/Liquibase)로 FK 를 **명시적으로 버전 관리**하세요. `ddl-auto=update` 가 만든 FK 는 이름이 `FKxxxx` 로 랜덤이라 나중에 추적이 안 됩니다.

### 온라인 스키마 변경 (pt-online-schema-change / gh-ost)

대형 테이블을 무중단으로 바꾸는 이 도구들은 **FK 를 극도로 싫어합니다.** 원리상 "새 테이블을 만들어 복사 후 원자적 교체(RENAME)"인데, **원본을 참조하는 자식 FK 가 교체 순간 깨지기** 때문입니다.

- `pt-osc` 는 `--alter-foreign-keys-method` 옵션으로 우회하지만 위험하고 느립니다.
- `gh-ost` 는 아예 FK 걸린 테이블을 **기본적으로 거부**합니다.

> 💡 **실무 팁**: 대형 테이블 + 무중단 변경이 잦은 환경이라면, 이것이 **애플리케이션 레벨 FK 를 선택하는 강력한 현실적 이유**가 됩니다. 반대로 FK 를 유지하려면 스키마 변경을 서비스 저부하 시간대에 계획해야 합니다.

### 파티셔닝

> ⚠️ **함정**: **파티셔닝된 테이블에는 FK 를 걸 수 없습니다** (파티션 테이블이 FK 의 부모도, 자식도 될 수 없음). 시계열 데이터를 파티셔닝하려면 FK 를 포기하거나, 정합성을 앱에서 지켜야 합니다. [Step 21](../step-21-partitioning/index.md) 과 함께 보세요.

---

## B-7. FK 가 만드는 락과 데드락

### 부모 행 S락 → hot row 병목

자식 INSERT 는 부모 행에 **공유락(S)** 을 겁니다("내가 참조하는 동안 이 부모 지우지 마"). "게스트 사용자", "기본 카테고리" 같은 **인기 부모 행**은 수많은 자식 INSERT 가 동시에 S락을 걸고, 그 부모를 누군가 `UPDATE`(X락) 하려는 순간 전부 대기합니다.

```
세션 A: INSERT INTO orders(customer_id,...) VALUES(1,...);  → customers#1 에 S락
세션 B: UPDATE customers SET grade='VIP' WHERE customer_id=1; → X락 대기
```

> 💡 자주 참조되는 마스터 데이터(코드값)는 **거의 안 바뀌게** 설계하고, 바꿀 일이 있으면 저부하 시간에 하세요.

### FK 로 인한 데드락 — 실제로 흔한 시나리오

두 트랜잭션이 자식을 넣으며 서로 다른 순서로 부모 S락을 잡고, 이어서 부모를 갱신하려다 교착됩니다.

```
T1: INSERT child(pid=1)   → parent#1 S락
T2: INSERT child(pid=2)   → parent#2 S락
T1: UPDATE parent WHERE id=2  → parent#2 X락 대기 (T2 보유)
T2: UPDATE parent WHERE id=1  → parent#1 X락 대기 (T1 보유)  → 데드락!
```

InnoDB 가 한쪽을 자동 롤백합니다:
```
ERROR 1213 (40001): Deadlock found when trying to get lock;
try restarting transaction
```

> ⚠️ **함정 — 데드락은 "정상"이다.** 없앨 수 없습니다. 애플리케이션은 `1213` 을 만나면 **트랜잭션을 재시도**하는 로직을 반드시 갖춰야 합니다.
> 💡 진단: `SHOW ENGINE INNODB STATUS\G` → `LATEST DETECTED DEADLOCK` 섹션에 두 트랜잭션이 잡은/기다린 락이 전부 나옵니다. 예방: **모든 트랜잭션이 부모를 같은 순서(예: id 오름차순)로 접근**하게 만드세요.

---

## B-8. 고아 행(orphan row) 찾기와 정리

`SET FOREIGN_KEY_CHECKS=0` 으로 넣었거나, FK 없이 운영했거나, 과거에 잘못 지운 데이터 때문에 **부모 없는 자식**이 생깁니다. FK 를 **뒤늦게 추가하려면** 먼저 이걸 청소해야 합니다(고아 행이 있으면 `ALTER TABLE ... ADD FOREIGN KEY` 가 `1452` 로 실패).

### 고아 행 찾기 (안티조인)

```sql
SELECT c.*
FROM s13b_child c
LEFT JOIN s13b_parent p ON p.id = c.pid
WHERE c.pid IS NOT NULL      -- NULL 은 "참조 안 함"이라 고아 아님
  AND p.id IS NULL;          -- 부모가 없는 자식 = 고아
```

> ⚠️ **함정**: `c.pid IS NOT NULL` 을 빼먹으면, FK 컬럼이 NULL(정상적으로 "참조 없음")인 행까지 고아로 잡습니다. **NULL 은 고아가 아닙니다.**

### 정리 후 FK 추가

```sql
-- 1) 고아 삭제 (또는 유효한 부모로 UPDATE)
DELETE c FROM s13b_child c
LEFT JOIN s13b_parent p ON p.id = c.pid
WHERE c.pid IS NOT NULL AND p.id IS NULL;

-- 2) 이제 FK 추가 성공
ALTER TABLE s13b_child
  ADD CONSTRAINT fk_b_child FOREIGN KEY (pid) REFERENCES s13b_parent(id);
```

> 💡 **실무 팁 — 대형 테이블에 뒤늦게 FK 붙이기**: `ALTER TABLE ... ADD FOREIGN KEY` 는 **전체 자식 행을 검증**하므로 대형 테이블에서 오래 걸리고 락을 잡습니다. 저부하 시간대에, 가능하면 온라인 DDL 지원 여부를 확인하고 진행하세요. FK 없는 프로젝트에 관계를 문서화만 하고 싶다면, 실제 FK 대신 **정기 고아 점검 배치**로 갈음하기도 합니다.

---

## B-9. TRUNCATE vs DELETE, 그리고 FK

| | `DELETE FROM t` | `TRUNCATE TABLE t` |
|---|---|---|
| 자식이 참조 중일 때 | RESTRICT 면 거부, CASCADE 면 전파 | **자식 존재 시 무조건 거부** (`1701`) |
| AUTO_INCREMENT | 유지 | **리셋** |
| 속도 | 행 단위 (느림) | 테이블 재생성 (빠름) |

```sql
TRUNCATE TABLE s13b_parent;   -- 자식이 FK 로 참조 중이면
```
```
ERROR 1701 (42000): Cannot truncate a table referenced in a foreign key
constraint (`shop`.`s13b_child`, CONSTRAINT `fk_b_child` ...)
```

> ⚠️ **함정**: `TRUNCATE` 는 `ON DELETE CASCADE` 를 **타지 않습니다.** CASCADE 가 걸려 있어도 자식이 하나라도 있으면 그냥 거부합니다. 전체를 비우려면 자식부터 `TRUNCATE` 하거나 `SET FOREIGN_KEY_CHECKS=0`.

---

## B-10. FK 컬럼 인덱스 — 자동 생성의 그림자

Step 13 에서 봤듯 **자식 FK 컬럼의 인덱스는 InnoDB 가 자동 생성**합니다. 하지만 여기에 두 가지 실무 이슈가 있습니다.

- [ ] ⚠️ **자동 생성 인덱스가 최적이 아닐 수 있다.** InnoDB 는 FK 컬럼 **단독** 인덱스를 만듭니다. 하지만 실제 쿼리가 `(pid, created_at)` 복합으로 조회한다면, 이미 그 복합 인덱스가 앞에 `pid` 를 포함하므로 **FK 자동 인덱스는 중복**입니다. → 복합 인덱스를 먼저 만들면 InnoDB 는 그걸 FK 인덱스로 재사용하고 별도 인덱스를 안 만듭니다.
- [ ] ⚠️ **자식 인덱스를 지우려 하면 막힌다.** FK 가 그 인덱스를 쓰고 있으면 `DROP INDEX` 가 `1553` 으로 거부됩니다. 인덱스를 바꾸려면 FK 를 먼저 떼거나, 대체 인덱스를 먼저 만들어야 합니다.

```sql
ALTER TABLE s13b_child DROP INDEX idx_pid;   -- FK 가 사용 중이면
```
```
ERROR 1553 (HY000): Cannot drop index 'idx_pid': needed in a foreign key constraint
```

> 💡 **실무 팁**: FK 를 걸기 **전에** 실제 조회 패턴에 맞는 복합 인덱스를 먼저 설계하세요. 그러면 (1) FK 자동 인덱스 중복을 피하고 (2) 부모 삭제/자식 조회 성능도 같이 챙깁니다.

---

## B-11. 실무 체크리스트 (이것만 지키면 FK 사고 대부분 예방)

**설계 시**
- [ ] FK 키는 **정수 대리키**(`BIGINT UNSIGNED`)로 통일 — 타입/콜레이션 불일치(3780) 원천 차단
- [ ] `ON DELETE` 를 **의식적으로** 선택: 자식 적으면 `CASCADE`, 많으면 `RESTRICT` + 앱 배치 삭제
- [ ] `ON DELETE SET NULL` 을 쓸 거면 자식 FK 컬럼을 **NULL 허용**으로
- [ ] 실제 조회 패턴에 맞는 **복합 인덱스를 FK 전에** 설계 (자동 인덱스 중복 방지)
- [ ] `ON DELETE SET DEFAULT` 는 **InnoDB 가 무시**하니 쓰지 말 것

**운영 시**
- [ ] 대량 적재는 `SET FOREIGN_KEY_CHECKS=0` → 적재 → 켠 뒤 **고아 행 검증 필수**
- [ ] 트랜잭션은 부모를 **일관된 순서(id 오름차순)** 로 접근 → 데드락 예방
- [ ] 애플리케이션은 `1213`(데드락) 을 만나면 **재시도**
- [ ] FK 없는 프로젝트라면 **고아 행 정기 점검 배치** 운영
- [ ] 대형 테이블 무중단 스키마 변경(`gh-ost`) 계획 시 FK 존재 여부 확인

**에러 만나면**
- [ ] `1452` → 부모에 값이 있는가 / 적재 순서 / NULL 허용 여부
- [ ] `1451` → 자식이 참조 중 / RESTRICT / CASCADE 검토
- [ ] `3730` → DROP 순서 / `FOREIGN_KEY_CHECKS=0`
- [ ] `3780` / `1822` / `errno 150` → `SHOW ENGINE INNODB STATUS\G` 의 `LATEST FOREIGN KEY ERROR`

---

> 되짚기: [Step 13 — 제약 조건과 정규화](../step-13-constraints/index.md) · 락과 트랜잭션의 원리는 [Step 19](../step-19-transactions/index.md) · NULL 규칙은 [부록 A](../appendix-a-null/index.md).
