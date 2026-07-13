# MySQL 8 완전 학습 코스

기본부터 고급까지, **25개 스텝**으로 MySQL 8을 처음부터 끝까지 익힙니다.
모든 예제는 실제로 돌아가는 MySQL 8.0에서 검증했고, **교재의 결과는 여러분 화면의 결과와 정확히 일치합니다.**

---

## 시작하기 (3분)

```bash
# 1. MySQL 8 컨테이너 기동
cd mysql8/docker
docker compose up -d

# 2. 예제 데이터베이스 설치
cd ../sql
./install.sh          # 기본 데이터 (5초)
./install.sh --big    # + 100만 행 테이블 (Step 15부터 필요)

# 3. 접속
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop
```

문제가 생기면 언제든 `./install.sh`를 다시 실행하세요. 완전한 초기 상태로 돌아갑니다.
그래도 안 되면 `docker compose down -v && docker compose up -d`.

> mysql 클라이언트가 없다면: `brew install mysql-client` (macOS)
> 컨테이너 안에서 써도 됩니다: `docker exec -it learn-mysql8 mysql -ulearner -plearn1234 shop`

---

## 커리큘럼

### 1부 — 기초 (Step 01~07)
> SQL을 한 줄도 못 써도 됩니다. 여기서 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/README.md) | 환경 구축과 첫 접속 | Docker, mysql CLI, `sql_mode`, 시스템 변수 |
| [02](step-02-ddl-datatypes/README.md) | 테이블과 데이터 타입 | `CREATE/ALTER`, **DECIMAL vs FLOAT**, DATETIME vs TIMESTAMP |
| [03](step-03-sample-database/README.md) | 예제 DB 구축과 탐색 | `shop` 스키마, ER 파악법, 정합성 검증 |
| [04](step-04-select-basics/README.md) | SELECT 기본 | `SELECT/FROM/WHERE/ORDER BY/LIMIT`, `DISTINCT`, 별칭 |
| [05](step-05-where-operators/README.md) | 연산자와 조건 | `BETWEEN/IN/LIKE/REGEXP`, **NULL 3값 논리**, 페이징 |
| [06](step-06-aggregate-groupby/README.md) | 집계와 GROUP BY | `COUNT/SUM/AVG`, `HAVING`, `ONLY_FULL_GROUP_BY`, `ROLLUP` |
| [07](step-07-joins/README.md) | JOIN | `INNER/LEFT/CROSS/SELF`, **ON vs WHERE 함정**, 안티 조인 |

### 2부 — 중급 (Step 08~14)
> 여기부터가 진짜 SQL입니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [08](step-08-subqueries/README.md) | 서브쿼리 | 스칼라/파생테이블, `IN vs EXISTS`, **`NOT IN` + NULL 함정** |
| [09](step-09-cte-recursive/README.md) | CTE와 재귀 쿼리 | `WITH`, **재귀 CTE**(조직도 전개, 날짜 채우기) |
| [10](step-10-set-operations/README.md) | 집합 연산 | `UNION/UNION ALL`, `INTERSECT/EXCEPT`(8.0.31+) |
| [11](step-11-dml/README.md) | 데이터 변경 | `INSERT/UPDATE/DELETE`, **UPSERT**, `REPLACE`의 함정 |
| [12](step-12-builtin-functions/README.md) | 내장 함수 | 문자열/숫자/날짜/조건/형변환, **함수가 인덱스를 죽이는 문제** |
| [13](step-13-constraints/README.md) | 제약조건과 정규화 | `PK/UNIQUE/CHECK/FK`, `ON DELETE` 옵션, 1NF~3NF와 반정규화 |
| [14](step-14-views-generated/README.md) | 뷰와 생성 컬럼 | `VIEW`, 갱신 가능한 뷰, `VIRTUAL vs STORED`, 함수 기반 인덱스 |

### 3부 — 고급: 성능 (Step 15~17)
> 이 코스의 심장부입니다. 100만 행 테이블로 직접 측정합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [15](step-15-indexes/README.md) | 인덱스 | B+Tree, 클러스터드 인덱스, **복합 인덱스 컬럼 순서**, 커버링 인덱스 |
| [16](step-16-explain-optimizer/README.md) | EXPLAIN과 옵티마이저 | 실행계획 완전 해독, `EXPLAIN ANALYZE`, 힌트, 히스토그램 |
| [17](step-17-window-functions/README.md) | 윈도우 함수 | `ROW_NUMBER/RANK`, `LAG/LEAD`, **프레임 절**, 누적합·이동평균 |

### 4부 — 고급: 기능 (Step 18~21)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [18](step-18-json/README.md) | JSON | `JSON_EXTRACT`, `->>`, **`JSON_TABLE`**, JSON 인덱싱 |
| [19](step-19-transactions/README.md) | 트랜잭션과 락 | ACID, **격리 수준 4단계 재현**, MVCC, 갭 락, 데드락 분석 |
| [20](step-20-stored-programs/README.md) | 저장 프로그램 | 프로시저/함수/커서/트리거/이벤트 |
| [21](step-21-partitioning/README.md) | 파티셔닝 | RANGE/LIST/HASH, 파티션 프루닝, **`DROP PARTITION`으로 즉시 삭제** |

### 5부 — 운영 (Step 22~25)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [22](step-22-users-security/README.md) | 계정과 보안 | `GRANT/REVOKE`, **ROLE**(8.0), 최소 권한, 비밀번호 정책 |
| [23](step-23-backup-replication/README.md) | 백업과 복제 | `mysqldump`, PITR, binlog, GTID, **복제 2노드 실제 구성** |
| [24](step-24-monitoring-tuning/README.md) | 모니터링과 튜닝 | 슬로우 쿼리, `performance_schema`, `sys`, **트러블슈팅 플레이북** |
| [25](step-25-final-project/README.md) | 종합 실습 | 매출 리포트, 코호트/RFM 분석, 느린 쿼리 튜닝, 스키마 설계 |

---

## 각 스텝의 구성

```
step-07-joins/
├── README.md      ← 교재 본문. 개념 설명 + 예제 + 실제 실행 결과 + 함정/팁
├── practice.sql   ← README의 모든 예제를 그대로 담은 실행 파일
├── exercise.sql   ← 연습문제 (문제만)
└── solution.sql   ← 정답 + 해설
```

**권장 학습 방법**

1. `README.md`를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마세요.
2. 결과가 교재와 다르면 멈추고 원인을 찾으세요. (거의 항상 오타입니다)
3. `exercise.sql`을 풀고 `solution.sql`로 채점합니다.
4. 다음 스텝으로.

```bash
# 예제 파일 통째로 실행
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < step-07-joins/practice.sql

# 표 형태로 예쁘게 보기
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < step-07-joins/practice.sql
```

---

## 예제 데이터베이스 `shop`

가상의 온라인 쇼핑몰입니다.

```
                     ┌──────────────┐
                     │  categories  │◄─┐ parent_id (대분류 ─ 소분류)
                     └──────┬───────┘  │
                            │          └──┘
                            │ N
   ┌────────────┐    ┌──────▼───────┐    ┌──────────────┐
   │ customers  │    │   products   │    │  employees   │◄─┐ manager_id
   └─────┬──────┘    └──────┬───────┘    └──────────────┘  │ (4단계 조직도)
         │                  │                              └──┘
         │ N                │ N
   ┌─────▼──────┐    ┌──────▼───────┐
   │   orders   │───►│ order_items  │
   └─────┬──────┘ 1:N└──────────────┘
         │ N
   ┌─────▼──────┐         ┌──────────────┐
   │  payments  │         │   reviews    │──► products, customers
   └────────────┘         └──────────────┘
```

| 테이블 | 행 수 | 비고 |
|---|---:|---|
| `categories` | 17 | 자기참조 계층 |
| `customers` | 30 | 등급/도시/포인트. **전화번호 NULL 3명** |
| `products` | 40 | **`attrs` JSON 컬럼**. NULL 2개 |
| `orders` | 600 | 2024-01-02 ~ 2025-12-30 |
| `order_items` | 1,200 | 주문당 1~3개 |
| `payments` | 540 | **PENDING 주문 60건엔 결제 없음** |
| `reviews` | 80 | **후기 없는 상품 24개** |
| `employees` | 18 | 자기참조 조직도 |
| `tally` | 10,000 | 숫자 1~10000 (데이터 생성 보조) |
| `access_logs` | 1,000,000 | **인덱스 없음** — Step 15에서 직접 붙입니다 |

### 데이터가 항상 똑같은 이유

시드 스크립트는 `RAND()`를 쓰지 않고 **나머지 연산(`%`)** 으로 값을 만듭니다. 그래서 누가 몇 번을 실행하든 **완전히 동일한 데이터**가 나오고, 교재의 모든 예제 결과가 여러분 화면과 일치합니다. 결과가 다르면 바로 뭔가 잘못됐다는 뜻입니다.

NULL, 빈 관계, 편향된 분포 등 **학습에 필요한 함정을 의도적으로 심어 두었습니다.** 각 스텝에서 재료로 씁니다.

---

## 실습 규칙

- **공용 테이블(`customers`, `orders`, `products` …)은 읽기만 하세요.**
- 데이터를 바꿔야 하는 실습(Step 11, 13, 19, 20 등)은 **`s11_`, `s13_` 같은 접두사가 붙은 사본 테이블**에서 진행합니다. 각 스텝 README에 안내가 있습니다.
- 실습 흔적을 지우려면:
  ```sql
  SELECT CONCAT('DROP TABLE IF EXISTS ', table_name, ';')
  FROM information_schema.tables
  WHERE table_schema = 'shop' AND table_name REGEXP '^s[0-9]{2}_';
  ```
  결과를 복사해 실행하세요.

---

## 이 코스가 특히 신경 쓴 것

**틀린 채로 돌아가는 SQL**을 잡는 데 집중했습니다. 문법 에러는 금방 고칠 수 있지만, **에러 없이 조용히 잘못된 답을 내는 쿼리**가 진짜 위험합니다. 예를 들면:

- `NOT IN (서브쿼리)`에 NULL이 하나 섞이면 결과가 **통째로 사라집니다** (Step 08)
- 조인한 뒤 `COUNT(*)`를 세면 고객 수가 아니라 **주문 수**가 나옵니다 (Step 03, 07)
- `LEFT JOIN`의 조건을 `WHERE`에 쓰면 **INNER JOIN이 되어버립니다** (Step 07)
- 돈을 `FLOAT`로 저장하면 `0.1 + 0.2 ≠ 0.3`이 되어 **정산이 어긋납니다** (Step 02)
- `LAST_VALUE()`는 기본 프레임 때문에 **마지막 값을 주지 않습니다** (Step 17)
- 컬럼에 함수를 씌우면 **인덱스를 못 탑니다** (Step 12, 15)

각 스텝의 `⚠️ 함정` 블록을 특히 눈여겨 보세요.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| MySQL | 8.0 (검증: 8.0.46) |
| 접속 | `127.0.0.1:3307` |
| 계정 | `learner` / `learn1234` (관리용: `root` / `root1234`) |
| DB | `shop` |
| 문자셋 | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 타임존 | `Asia/Seoul (+09:00)` |
| 설정 | [`docker/conf/my.cnf`](./docker/) — 슬로우 쿼리 로그 ON, 엄격 모드 유지 |

> 학습용 `learner` 계정에는 편의를 위해 권한을 넉넉히 주었습니다. **운영에서는 절대 이러면 안 됩니다.** 올바른 권한 설계는 [Step 22](step-22-users-security/README.md)에서 배웁니다.
