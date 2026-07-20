# Spring Batch 5 완전 학습 코스

기본부터 운영까지, **14개 스텝**으로 Spring Batch 5를 처음부터 끝까지 익힙니다.
모든 예제는 실제로 돌아가는 **Spring Boot 3.2.5 / Spring Batch 5.1.1 / Java 21 / MySQL 8.0.36** 환경에서 검증했고, **교재의 로그와 숫자는 여러분 화면의 결과와 정확히 일치합니다.**

---

## 시작하기 (10분)

```bash
# 1. MySQL 8 메타데이터 DB 기동 (약 90초, 10만 행 시드 포함)
cd spring-batch5-lab/docker
docker compose up -d
docker compose ps            # (healthy) 가 뜰 때까지 대기

# 2. 시드 데이터 확인 — orders 100,000 / COMPLETED 70,000 이어야 정상
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t \
  -e "SELECT status, COUNT(*) FROM orders GROUP BY status;"

# 3. 애플리케이션 기동 — BUILD SUCCESSFUL 로 끝나야 정상
cd ..
./gradlew bootRun

# 4. 메타데이터 테이블 9개 생성 확인
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "SHOW TABLES LIKE 'BATCH%';"
```

프로젝트를 아직 만들지 않았다면 [실습 프로젝트 셋업](project/) 을 먼저 읽으세요. `build.gradle` 전문부터 시드 SQL 까지 전부 있습니다.

실습이 꼬이면 메타데이터만 초기화합니다(3초).

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT; DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;  DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;          DELETE FROM BATCH_JOB_INSTANCE;
SET FOREIGN_KEY_CHECKS = 1;
TRUNCATE TABLE settlement;
SQL
```

그래도 안 되면 `docker compose down -v && docker compose up -d` 로 완전 초기화합니다(약 90초).

---

## 커리큘럼

### 1부 — 기초 (Step 01~04)
> Spring Batch 를 한 줄도 안 써 봤어도 됩니다. 여기서 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 환경 구축과 첫 Job | 첫 Job 실행, **`BATCH_*` 메타데이터 9개 테이블 해부**, BatchStatus vs ExitStatus |
| [02](step-02-job-step/) | Job 과 Step 의 구조 | JobBuilder/StepBuilder, **5.0 에서 `JobBuilderFactory` 제거**, `@EnableBatchProcessing` 이 선택적으로 바뀐 이유 |
| [03](step-03-job-parameters/) | JobParameters 와 실행 식별 | JobInstance vs JobExecution, **`JobInstanceAlreadyCompleteException` 재현**, Incrementer, identifying 파라미터 |
| [04](step-04-tasklet/) | Tasklet Step | `RepeatStatus.CONTINUABLE` 반복과 트랜잭션 경계, MethodInvokingTaskletAdapter, **Tasklet vs 청크 선택 기준** |

### 2부 — 청크 지향 처리 (Step 05~08)
> 이 코스의 심장부입니다. 7만 건을 실제로 돌리며 측정합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [05](step-05-chunk/) | 청크 지향 처리 | Reader/Processor/Writer 사이클, **청크 크기 10~10000 실측(U자 곡선)**, 청크 = 커밋 = 롤백 단위 |
| [06](step-06-item-reader/) | ItemReader | Cursor vs Paging 비교, `DataClassRowMapper`, **페이징 리더 정렬 키 누락 = 데이터 유실** |
| [07](step-07-item-processor/) | ItemProcessor | 변환, **`null` 반환 = 필터링**, CompositeItemProcessor, **제네릭이 못 막는 런타임 ClassCastException** |
| [08](step-08-item-writer/) | ItemWriter | `Chunk<T>` 시그니처(4.x `List<T>` 에서 변경), **`rewriteBatchedStatements` 로 8배**, **`@Bean` 아닌 writer 는 ItemStream 콜백이 안 온다** |

### 3부 — 상태와 흐름 (Step 09~12)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [09](step-09-execution-context/) | ExecutionContext 와 스코프 | Job/Step 컨텍스트와 승격, `@StepScope`, **`@StepScope` 없이 늦은 바인딩 시도하면 기동 시 실패** |
| [10](step-10-flow-control/) | 흐름 제어 | `on/to/from/end`, **`on()` 이 보는 건 BatchStatus 가 아니라 ExitStatus**, JobExecutionDecider, `split` 병렬 흐름 |
| [11](step-11-fault-tolerance/) | 내결함성: skip · retry · 재시작 | skipLimit/skipPolicy, retryLimit, **skip 이 나면 청크가 롤백되고 1건씩 재처리되어 성능이 급락**, 재시작 이어받기 |
| [12](step-12-listeners/) | 리스너 | Job/Step/Chunk/Item/Skip 리스너, 애너테이션 방식, **리스너에서 던진 예외의 운명은 리스너마다 다르다** |

### 4부 — 확장과 운영 (Step 13~14)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [13](step-13-scaling/) | 병렬 처리와 확장 | 멀티스레드 Step / 파티셔닝 / 원격 청킹, **상태 있는 Reader 를 멀티스레드에 쓰면 조용히 건수가 어긋난다**, 성능 실측 |
| [14](step-14-operations/) | 운영: 스케줄링 · 모니터링 · 최종 프로젝트 | `@Scheduled`/Quartz, CommandLineJobRunner, **중복 실행 방지**, 메타데이터 기반 실패 분석, Micrometer, **일일 주문 정산 배치 완성** |

---

## 각 스텝의 구성

```
step-05-chunk/
├── index.md        ← 교재 본문. 개념 + 코드 + 실제 실행 로그 + 함정/팁
├── Practice.java   ← 본문의 모든 예제를 절 번호 주석과 함께 담은 실행 파일
├── Exercise.java   ← 연습문제 (문제만)
└── Solution.java   ← 정답 + 왜 그 답인지 설명하는 해설 주석
```

**권장 학습 방법**

1. `index.md` 를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마세요.
2. 로그가 교재와 다르면 멈추고 원인을 찾으세요. 시드가 결정론적이므로 **다르면 거의 항상 여러분 쪽 문제**입니다.
3. `Exercise.java` 를 풀고 `Solution.java` 로 채점합니다.
4. 다음 스텝으로.

```bash
# 특정 스텝의 Job 만 실행
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-03-01'

# 빌드 후 jar 로 실행 (Step 14 의 운영 방식)
./gradlew clean bootJar
java -jar build/libs/spring-batch5-lab-1.0.0.jar --spring.batch.job.name=settlementJob date=2025-03-01
```

---

## 실습 도메인 `batchdb`

가상의 온라인 쇼핑몰 **주문 정산 배치**입니다. 코스 전체가 하나의 시나리오로 이어집니다.

```
   ┌──────────────┐
   │  customers   │  1,000명 · 등급별 수수료율(BRONZE 3.5% ~ VIP 2.0%)
   └──────┬───────┘
          │ 1:N
   ┌──────▼───────┐        ┌──────────────┐
   │    orders    │───────►│ order_items  │  300,000행 (주문당 3건)
   │   100,000행   │  1:N   └──────────────┘
   └──────┬───────┘                 │ N:1
          │                  ┌──────▼───────┐
          │ status=COMPLETED │   products   │  200개
          │ 70,000행만 대상    └──────────────┘
          ▼
   ┌──────────────┐
   │  settlement  │  ◄── 배치가 채웁니다. 처음엔 비어 있습니다.
   └──────────────┘      gross - fee = net
```

| 테이블 | 행 수 | 비고 |
|---|---:|---|
| `customers` | 1,000 | 등급 4종(`customer_id % 4`), **등급별 `fee_rate` 가 정산 계산의 입력** |
| `products` | 200 | 카테고리 5종 |
| `orders` | 100,000 | 2025-01-01 ~ 2025-06-29 (180일). 하루 약 555건 |
| `order_items` | 300,000 | 주문당 정확히 3건 |
| `settlement` | **0** | 배치 결과 테이블. `order_id` 에 **UNIQUE 제약**(중복 정산 방지) |
| `tally` | 300,000 | 숫자 1~300000 (시드 생성 보조) |

주문 상태 분포는 `order_id % 10` 으로 고정되어 있습니다.

| status | 행 수 | 비율 | 정산 대상 |
|---|---:|---:|---|
| `COMPLETED` | **70,000** | 70% | **O** |
| `CANCELLED` | 10,000 | 10% | X |
| `PENDING` | 10,000 | 10% | X |
| `REFUNDED` | 10,000 | 10% | X |

> **정산 대상 70,000건**은 이 코스의 기준 숫자입니다. 청크 크기 1,000이면 **정확히 70청크**라서, 로그의 커밋 횟수·건수를 암산으로 검증할 수 있습니다. Step 05 이후 모든 실측이 이 숫자 위에서 이루어집니다.

### 데이터가 항상 똑같은 이유

시드 스크립트는 `RAND()` 를 쓰지 않고 **나머지 연산(`%`)** 으로만 값을 만듭니다. 그래서 누가 몇 번을 실행하든 **완전히 동일한 데이터**가 나옵니다. 배치 학습에서 이건 특히 중요합니다. "7만 건 중 68,412건만 처리됐다" 같은 **결함을 재현**하려면, 데이터가 매번 같아야 그 숫자를 교재에 적을 수 있기 때문입니다.

---

## 실습 규칙

- **`orders`, `customers`, `products`, `order_items` 는 읽기만 하세요.** 이 네 테이블이 바뀌면 이후 모든 스텝의 결과가 교재와 어긋납니다.
- `settlement` 는 배치가 자유롭게 쓰고 지웁니다. 언제든 `TRUNCATE TABLE settlement;` 해도 됩니다.
- 실습으로 만드는 임시 테이블은 `s05_`, `s11_` 처럼 **스텝 번호 접두사**를 붙입니다.
- 파일 출력은 프로젝트 루트의 `output/` 아래에만 씁니다.
- **각 스텝을 시작하기 전에 메타데이터를 초기화하는 습관**을 들이세요. Job 이름이 겹치면 이전 스텝의 JobInstance 가 남아 `JobInstanceAlreadyCompleteException` 이 납니다(Step 03 에서 자세히).

---

## 이 코스가 특히 신경 쓴 것

**에러 없이 조용히 틀리는 배치**를 잡는 데 집중했습니다. 문법 에러는 컴파일러가 잡아 주지만, **7만 건 중 2,793건이 소리 없이 사라지는 배치**는 아무도 안 잡아 줍니다. 로그는 깨끗하고, Job 은 `COMPLETED` 이고, 카운터마저 정상입니다. 그리고 정산 배치에서 그건 곧 돈입니다. 예를 들면:

- 페이징 리더에 **정렬 키를 안 주면** 페이지가 밀려 데이터가 **누락되거나 중복**됩니다 (Step 06)
- `ItemProcessor` 가 `null` 을 반환하면 예외가 아니라 **조용히 필터링**됩니다 (Step 07)
- Writer 를 `@Bean` 이 아니라 `new` 로 만들면 **`ItemStream` 콜백이 호출되지 않아** 파일이 비거나 재시작이 깨집니다 (Step 08)
- `@StepScope` 를 빼먹으면 늦은 바인딩이 **기동 시점에** 실패합니다 — 다행히 이건 시끄럽게 실패합니다 (Step 09)
- `.on("FAILED").to(recoveryStep).end()` 로 잡으면 Job 이 **COMPLETED 로 끝나** 실패가 은폐됩니다 (Step 10)
- `skip` 이 한 번 발생하면 청크가 롤백되고 **아이템 1건씩 재처리**되어 성능이 급락합니다 (Step 11)
- 상태를 가진 Reader 를 멀티스레드 Step 에 쓰면 **건수가 매번 다르게** 나옵니다 (Step 13)
- 같은 Job 을 서로 다른 파라미터로 동시에 띄우면 **JobInstance 중복 차단이 무력화**됩니다 (Step 14)
- `record` 에 `BeanPropertyRowMapper` 를 쓰면 **모든 필드가 `null`** 이 됩니다 ([프로젝트 셋업](project/), Step 06·08)

각 스텝의 `⚠️ 함정` 블록을 특히 눈여겨 보세요.

---

## Spring Batch 4 → 5 변경점 지도

4.x 예제를 보고 따라 하다 막히는 지점이 정해져 있습니다. 어느 스텝에서 다루는지 표로 정리합니다.

| 4.x | 5.x | 다루는 곳 |
|---|---|---|
| `JobBuilderFactory` / `StepBuilderFactory` | **삭제.** `new JobBuilder(name, jobRepository)` | [Step 02](step-02-job-step/) |
| `@EnableBatchProcessing` 필수 | **선택.** 붙이면 오히려 Boot 자동설정이 꺼짐 | [Step 02](step-02-job-step/) |
| `javax.persistence.*` | `jakarta.persistence.*` | [프로젝트 셋업](project/) |
| `JobParameter(Object)` | `JobParameter<T>(value, Class<T>, identifying)` | [Step 03](step-03-job-parameters/) |
| `.chunk(size)` | `.chunk(size, transactionManager)` | [Step 05](step-05-chunk/) |
| `ItemWriter.write(List<T>)` | `ItemWriter.write(Chunk<? extends T>)` | [Step 08](step-08-item-writer/) |
| `JobBuilder.repository(...)` | 생성자로 이동 | [Step 02](step-02-job-step/) |

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Java | 21 (Gradle toolchain 고정) |
| Spring Boot | 3.2.5 |
| Spring Batch | **5.1.1** |
| 빌드 | Gradle Groovy DSL |
| MySQL | 8.0.36 |
| 접속 | `127.0.0.1:3308` |
| 계정 | `batch` / `batch1234` (관리용: `root` / `root1234`) |
| 스키마 | `batchdb` (메타데이터 + 업무 데이터 공용) |
| 문자셋 | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 타임존 | `Asia/Seoul (+09:00)` |
| 커넥션 풀 | HikariCP, `maximum-pool-size: 20` (Step 13 의 병렬 실습에 필요) |

> 이 코스는 학습 편의를 위해 **메타데이터와 업무 데이터를 한 스키마에 둡니다.** 운영에서는 대개 분리하며, 분리했을 때 생기는 트랜잭션 경계 문제는 [프로젝트 셋업](project/) 과 [Step 11](step-11-fault-tolerance/) 에서 짚습니다.

---

## 시작

→ [실습 프로젝트 셋업](project/)
