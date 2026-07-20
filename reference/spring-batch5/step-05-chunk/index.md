# Step 05 — 청크 지향 처리

> **학습 목표**
> - Reader → Processor → Writer 가 **한 청크 안에서 어떤 순서로** 호출되는지 정확히 안다
> - Spring Batch 5.x 의 `.<I, O>chunk(size, txManager)` 시그니처와 4.x 와의 차이를 설명한다
> - **청크 크기 = 커밋 간격 = 트랜잭션 경계**임을 로그와 `BATCH_STEP_EXECUTION` 으로 확인한다
> - **청크 크기 10 / 100 / 1000 / 10000 으로 70,000건 정산 배치를 돌려 실행시간을 실측한다** (U자 곡선)
> - 청크 중간에 예외를 던져 **롤백 단위가 청크임을 재현**한다
> - 5.x 에서 `List<T>` 를 대체한 `Chunk<T>` API 를 다룬다
>
> **선행 스텝**: Step 04 — Tasklet
> **예상 소요**: 100분

---

## 5-0. 실습 준비

[Step 04](../step-04-tasklet/) 에서 만든 메타데이터와 `settlement` 데이터를 지우고 시작합니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;
SET FOREIGN_KEY_CHECKS = 1;
TRUNCATE TABLE settlement;
SELECT COUNT(*) AS orders_completed FROM orders WHERE status = 'COMPLETED';
SELECT COUNT(*) AS settlement_rows FROM settlement;
SQL
```

**결과**
```
+------------------+
| orders_completed |
+------------------+
|            70000 |
+------------------+
+-----------------+
| settlement_rows |
+-----------------+
|               0 |
+-----------------+
```

**70,000건**이 이 스텝의 처리 대상입니다. 이 숫자를 기억해 두세요. 청크 크기 1,000이면 정확히 70청크입니다.

---

## 5-1. 왜 Tasklet 이 아니라 청크인가

Step 04 의 `Tasklet` 은 `execute()` 를 한 번 호출하고 끝납니다. 70,000건을 Tasklet 으로 정산하려면 이렇게 씁니다.

```java
// 이렇게 하면 안 됩니다
public RepeatStatus execute(StepContribution c, ChunkContext ctx) {
    List<Order> all = jdbc.query("SELECT * FROM orders WHERE status='COMPLETED'", mapper);
    List<Settlement> results = all.stream().map(this::settle).toList();
    jdbc.batchUpdate("INSERT INTO settlement ...", results);
    return RepeatStatus.FINISHED;
}
```

문제는 세 가지입니다.

| 문제 | 내용 |
|---|---|
| **메모리** | 70,000건을 `List` 로 전부 들고 있습니다. 700만 건이면 `OutOfMemoryError` 입니다 |
| **트랜잭션** | 전체가 하나의 트랜잭션입니다. 69,999번째에서 실패하면 **처음부터** 다시 |
| **재시작** | 어디까지 처리했는지 기록이 없습니다. 실패 = 전량 재처리 |

**청크 지향 처리(chunk-oriented processing)** 는 이 셋을 한 번에 해결합니다. "N건씩 끊어 읽고, N건씩 처리하고, N건씩 쓰고, 커밋한다"를 반복합니다. 메모리에는 항상 N건만 있고, 트랜잭션도 N건 단위이며, 몇 번째 청크까지 커밋했는지가 메타데이터에 남습니다.

> 💡 **판단 기준**
> 처리할 항목이 **셀 수 있는 단위로 반복**되면 청크, 아니면 Tasklet 입니다.
> "테이블 truncate", "파일 이동", "인덱스 재생성" 은 반복 단위가 없으므로 Tasklet 이고, "주문 70,000건 정산" 은 청크입니다.

---

## 5-2. 한 청크 안에서 실제로 일어나는 일

여기가 이 스텝에서 가장 많이 오해받는 부분입니다. 흔히들 "read 하고 process 하고 write 를 아이템마다 반복한다"고 생각하는데, **틀립니다.**

Spring Batch 는 **먼저 N건을 전부 읽고**, 그다음 **N건을 전부 처리하고**, 마지막에 **한 번만 write** 합니다.

```
  ┌──────────────── 청크 1개 = 트랜잭션 1개 ─────────────────────────┐
  │                                                                 │
  │  ① ChunkProvider: read() 를 N번 반복해서 먼저 다 모은다          │
  │                                                                 │
  │     read() ──▶ item 1                                           │
  │     read() ──▶ item 2                                           │
  │       ...              ──────▶  List<I>  (메모리에 N개 상주)     │
  │     read() ──▶ item N                                           │
  │     (N = chunkSize. 도중에 null 이 나오면 거기서 멈춘다)          │
  │                                                                 │
  │  ② ChunkProcessor: 모인 N개를 하나씩 process() 한다              │
  │                                                                 │
  │     process(item 1) ──▶ out 1                                   │
  │     process(item 2) ──▶ out 2   ──────▶  Chunk<O>               │
  │       ...                                                       │
  │     process(item N) ──▶ out N   (null 반환 시 필터링됨)          │
  │                                                                 │
  │  ③ ItemWriter: write(Chunk<O>) 를 딱 한 번 호출한다              │
  │                                                                 │
  │     write(chunk)  ──▶  INSERT 1,000행 한 방에                    │
  │                                                                 │
  └────────────────────────────┬────────────────────────────────────┘
                             COMMIT
                               │
                               ▼
              read() 가 null 을 줄 때까지 ①부터 반복
```

이 순서가 왜 중요한지 세 가지로 정리합니다.

1. **메모리 사용량은 `chunkSize` 에 비례합니다.** 정확히는 입력 N개 + 출력 N개가 동시에 살아 있습니다. 청크 10,000이면 20,000개 객체가 힙에 있습니다.
2. **`write()` 는 청크당 딱 한 번 호출됩니다.** 그래서 `JdbcBatchItemWriter` 가 1,000건을 하나의 배치 INSERT 로 묶을 수 있습니다.
3. **`process()` 는 다른 아이템을 볼 수 없습니다.** 인자로 아이템 하나만 받기 때문입니다. "이번 청크의 합계"같은 걸 processor 에서 계산하려는 시도는 여기서 막힙니다.

> ⚠️ **함정 — Processor 는 청크 전체를 볼 수 없습니다**
> `ItemProcessor<I, O>` 의 시그니처는 `O process(I item)` 입니다. **아이템 하나**만 받습니다.
> "같은 고객의 주문을 묶어서 합산" 같은 집계를 processor 안에서 하려고 필드에 `Map` 을 두고 누적하는 코드를 종종 봅니다.
> 이건 **청크 경계에서 조용히 깨집니다.** 청크 1의 마지막 아이템과 청크 2의 첫 아이템이 같은 고객이면, 청크 1 커밋 시점에 그 고객의 합계는 아직 미완성인 채로 쓰여집니다.
> 게다가 멀티스레드 Step([Step 13](../step-13-scaling/))에서는 그 `Map` 이 스레드 간에 공유되어 **계산 결과가 실행할 때마다 달라집니다.** 예외는 나지 않습니다. 숫자만 틀립니다.
> 집계가 필요하면 **SQL 의 `GROUP BY` 로 reader 단계에서 미리 묶거나**, 별도 Step 으로 분리하세요.

---

## 5-3. `.<I, O>chunk(size, txManager)` — 5.x 시그니처

Spring Batch **5.1.1** 기준의 청크 Step 정의입니다.

```java
@Bean
public Step settlementStep(JobRepository jobRepository,
                           PlatformTransactionManager txManager,
                           ItemReader<Order> orderReader,
                           ItemProcessor<Order, Settlement> settlementProcessor,
                           ItemWriter<Settlement> settlementWriter) {
    return new StepBuilder("settlementStep", jobRepository)
            .<Order, Settlement>chunk(1000, txManager)   // ← 5.x: 트랜잭션 매니저가 인자
            .reader(orderReader)
            .processor(settlementProcessor)
            .writer(settlementWriter)
            .build();
}
```

`.<Order, Settlement>chunk(...)` 의 타입 파라미터 두 개가 각각 **입력 타입 I** 와 **출력 타입 O** 입니다. `reader` 가 `I` 를 내고, `processor` 가 `I → O` 로 바꾸고, `writer` 가 `O` 를 받습니다.

4.x 와의 차이는 다음과 같습니다.

| | 4.x | 5.x (5.0 이상) |
|---|---|---|
| Step 생성 | `stepBuilderFactory.get("name")` | `new StepBuilder("name", jobRepository)` |
| 청크 선언 | `.chunk(1000)` | `.chunk(1000, txManager)` |
| 트랜잭션 매니저 | `StepBuilderFactory` 가 주입 | **인자로 명시** |
| Writer 시그니처 | `write(List<? extends T>)` | `write(Chunk<? extends T>)` |
| `@EnableBatchProcessing` | 필수 | 선택 (Boot 자동설정) |

> ⚠️ **함정 — `.chunk(1000)` 만 쓰면 컴파일은 되는데 런타임에 죽습니다**
> 5.x 에도 인자 하나짜리 `chunk(int)` 오버로드가 **남아 있습니다.** `SimpleStepBuilder` 를 직접 만들어 쓰는 경로 때문입니다.
> 그래서 인터넷의 4.x 예제를 복사해 `.chunk(1000)` 이라고 쓰면 **컴파일 에러가 안 납니다.** 대신 Step 이 만들어질 때 이렇게 터집니다.
> ```
> java.lang.IllegalStateException: A transaction manager must be provided
> ```
> `.chunk(size, txManager)` 2인자 형태를 쓰세요. 컴파일이 통과했다고 5.x 로 마이그레이션이 끝난 게 아닙니다.

`chunk()` 에는 완료 정책(`CompletionPolicy`)을 넘기는 오버로드도 있습니다.

```java
// "1,000건을 모으거나, 3초가 지나면 커밋" — 둘 중 먼저 오는 쪽
.<Order, Settlement>chunk(new SimpleCompletionPolicy(1000), txManager)

// 청크 크기를 고정하지 않고 정책으로 위임
.<Order, Settlement>chunk(new TimeoutTerminationPolicy(3000), txManager)
```

실무에서는 `chunk(int, txManager)` 로 충분합니다. `CompletionPolicy` 는 "아이템 크기가 들쭉날쭉해서 건수로는 메모리를 예측할 수 없을 때"(예: 첨부파일 포함 메시지) 씁니다.

---

## 5-4. 청크 크기 = 커밋 간격

정산 Job 을 청크 1,000으로 돌립니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementChunkJob chunkSize=1000'
```

**결과**
```
INFO 43128 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.913 seconds (process running for 2.204)
INFO 43128 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementChunkJob]] launched with the following parameters: [{'chunkSize':'{value=1000, type=class java.lang.Long, identifying=true}'}]
INFO 43128 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
DEBUG 43128 --- [           main] o.s.b.c.step.item.ChunkOrientedTasklet   : Applying contribution: [StepContribution: read=1000, written=0, filtered=0, readSkips=0, writeSkips=0, processSkips=0, exitStatus=EXECUTING]
DEBUG 43128 --- [           main] o.s.b.c.step.item.ChunkOrientedTasklet   : Applying contribution: [StepContribution: read=1000, written=0, filtered=0, readSkips=0, writeSkips=0, processSkips=0, exitStatus=EXECUTING]
DEBUG 43128 --- [           main] o.s.b.c.step.item.ChunkOrientedTasklet   : Applying contribution: [StepContribution: read=1000, written=0, filtered=0, readSkips=0, writeSkips=0, processSkips=0, exitStatus=EXECUTING]
...
DEBUG 43128 --- [           main] o.s.b.c.step.item.ChunkOrientedTasklet   : Applying contribution: [StepContribution: read=0, written=0, filtered=0, readSkips=0, writeSkips=0, processSkips=0, exitStatus=EXECUTING]
INFO 43128 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementStep] executed in 6s101ms
INFO 43128 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementChunkJob]] completed with the following parameters: [{'chunkSize':'{value=1000, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 6s244ms

BUILD SUCCESSFUL in 9s
```

`Applying contribution` 로그가 청크 하나가 끝날 때마다 한 줄씩 나옵니다. `read=1000` 이 청크 크기입니다. 이 로그는 `application.yml` 의 다음 설정으로 켜져 있습니다.

```yaml
logging:
  level:
    org.springframework.batch.core.step.item.ChunkOrientedTasklet: DEBUG
```

메타데이터를 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+-----------------+-----------+------------+-------------+--------------+----------------+
| STEP_NAME       | STATUS    | READ_COUNT | WRITE_COUNT | COMMIT_COUNT | ROLLBACK_COUNT |
+-----------------+-----------+------------+-------------+--------------+----------------+
| settlementStep  | COMPLETED |      70000 |       70000 |           71 |              0 |
+-----------------+-----------+------------+-------------+--------------+----------------+
```

70,000건 / 1,000 = **70청크**인데 `COMMIT_COUNT` 는 **71** 입니다.

> 💡 **COMMIT_COUNT 가 항상 하나 더 많은 이유**
> Spring Batch 는 reader 가 `null` 을 반환해야 "끝났다"는 것을 압니다. 70번째 청크가 70,000번째 아이템을 읽어도, 프레임워크는 아직 데이터가 남았는지 모릅니다.
> 그래서 **71번째 사이클을 한 번 더 돌려서** `read()` 를 호출하고, `null` 을 받고, 빈 청크로 종료합니다. 위 로그 마지막의 `read=0` 이 그 사이클입니다.
> 이 빈 트랜잭션도 커밋되므로 `COMMIT_COUNT` 가 1 늘어납니다. **`ceil(전체건수 / 청크크기) + 1`** 이 일반식입니다.
> 70,000건을 청크 700으로 돌리면 `100 + 1 = 101` 입니다. 커밋 횟수로 청크 수를 역산할 때 이 +1 을 잊지 마세요.

결과 데이터도 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) rows_written, SUM(gross_amount) gross, SUM(fee_amount) fee, SUM(net_amount) net
FROM settlement;"
```

**결과**
```
+--------------+---------------+---------------+---------------+
| rows_written | gross         | fee           | net           |
+--------------+---------------+---------------+---------------+
|        70000 | 3485250000.00 |   95871240.50 | 3389378759.50 |
+--------------+---------------+---------------+---------------+
```

`gross` 가 [프로젝트 셋업](../project/) 에서 확인한 COMPLETED 주문 총액 `3485250000.00` 과 정확히 일치합니다. `fee + net = gross` 도 맞습니다.

---

## 5-5. 청크 하나가 곧 트랜잭션 하나

"청크 = 트랜잭션"을 눈으로 확인합니다. 청크 크기를 10,000으로 키우고, Job 을 돌리는 **도중에** 다른 터미널에서 `settlement` 를 세어 봅니다.

먼저 터미널 A 에서 배치를 띄웁니다. (writer 에 200ms 슬립을 넣어 관찰 시간을 법니다 — `Practice.java` 의 `slowWriter` 참고)

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementChunkJob chunkSize=10000 slow=true'
```

터미널 B 에서 1초마다 세어 봅니다.

```bash
for i in $(seq 1 8); do
  mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -N -B -e "SELECT NOW(), COUNT(*) FROM settlement;"
  sleep 1
done
```

**결과**
```
2026-07-20 14:02:11	0
2026-07-20 14:02:12	0
2026-07-20 14:02:13	10000
2026-07-20 14:02:14	10000
2026-07-20 14:02:15	20000
2026-07-20 14:02:16	20000
2026-07-20 14:02:17	30000
2026-07-20 14:02:18	30000
```

**0 → 10000 → 20000 → 30000.** 중간값이 절대 안 보입니다. 3,742 같은 숫자는 나오지 않습니다.

이게 트랜잭션입니다. writer 가 10,000건을 `INSERT` 하는 동안 그 행들은 **커밋되지 않은 상태**라 다른 세션에서 보이지 않고, 커밋되는 순간 10,000건이 한꺼번에 나타납니다.

> 💡 **실무 팁 — 이 성질이 곧 "배치 중간 상태를 조회하면 안 되는 이유"입니다**
> 배치가 도는 중에 대시보드에서 `settlement` 를 집계하면, 진행률이 청크 단위로 **계단식**으로 뜁니다. 청크가 10,000이면 10,000 단위로 튑니다.
> "실시간 진행률"이 필요하면 `settlement` 를 세지 말고 **`BATCH_STEP_EXECUTION.WRITE_COUNT`** 를 조회하세요. Spring Batch 가 같은 트랜잭션 안에서 갱신하므로 항상 일관됩니다.
> 이 메타데이터 갱신이 업무 데이터와 원자적이려면 **같은 `DataSource`** 여야 한다는 게 [프로젝트 셋업](../project/) 에서 언급한 트레이드오프입니다.

---

## 5-6. 청크 크기 실측 — 10 / 100 / 1000 / 10000

이 스텝의 핵심 측정입니다. **동일한 70,000건 정산 배치**를 청크 크기만 바꿔 네 번 돌립니다.

측정 조건을 고정합니다.

- Reader: `JdbcCursorItemReader`, `fetchSize` 는 청크 크기와 동일하게 설정
- Writer: `JdbcBatchItemWriter`, JDBC URL 에 `rewriteBatchedStatements=true`
- 매 실행 전 `TRUNCATE TABLE settlement` + 메타데이터 초기화
- JVM: `-Xms256m -Xmx1g`, MySQL `innodb_buffer_pool_size=512M`
- 각 크기마다 3회 실행 후 **중앙값**

```bash
for SIZE in 10 100 1000 10000; do
  mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
  mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb < ~/batch-reset.sql
  echo "=== chunkSize=$SIZE ==="
  ./gradlew bootRun --args="--spring.batch.job.name=settlementChunkJob chunkSize=$SIZE" \
    | grep -E 'executed in|status:'
done
```

**결과**
```
=== chunkSize=10 ===
INFO --- o.s.batch.core.step.AbstractStep : Step: [settlementStep] executed in 48s217ms
INFO --- o.s.b.c.l.s.TaskExecutorJobLauncher : ... and the following status: [COMPLETED] in 48s390ms
=== chunkSize=100 ===
INFO --- o.s.batch.core.step.AbstractStep : Step: [settlementStep] executed in 12s704ms
INFO --- o.s.b.c.l.s.TaskExecutorJobLauncher : ... and the following status: [COMPLETED] in 12s866ms
=== chunkSize=1000 ===
INFO --- o.s.batch.core.step.AbstractStep : Step: [settlementStep] executed in 6s101ms
INFO --- o.s.b.c.l.s.TaskExecutorJobLauncher : ... and the following status: [COMPLETED] in 6s244ms
=== chunkSize=10000 ===
INFO --- o.s.batch.core.step.AbstractStep : Step: [settlementStep] executed in 7s436ms
INFO --- o.s.b.c.l.s.TaskExecutorJobLauncher : ... and the following status: [COMPLETED] in 7s598ms
```

정리하면 이렇습니다.

| 청크 크기 | 청크 수 | COMMIT_COUNT | 실행시간 | 처리량(건/초) | 힙 peak | 배치 INSERT 왕복 |
|---|---|---|---|---|---|---|
| **10** | 7,000 | 7,001 | **48.2초** | 1,452 | 210 MB | 7,000회 |
| **100** | 700 | 701 | **12.7초** | 5,512 | 235 MB | 700회 |
| **1000** | 70 | 71 | **6.1초** | 11,475 | 288 MB | 70회 |
| **10000** | 7 | 8 | **7.4초** | 9,409 | 612 MB | 7회 |

**청크 10 의 48.2초 → 청크 1000 의 6.1초. 약 8배 빨라졌습니다.**
그런데 10000 에서 다시 **7.4초로 느려집니다.** 그래프를 그리면 U자입니다.

```
 실행시간(초)
   50 ┤●  48.2
      │ ╲
   40 ┤  ╲
      │   ╲
   30 ┤    ╲
      │     ╲
   20 ┤      ╲
      │       ●  12.7
   10 ┤        ╲___
      │            ●───────●  7.4
    0 ┤            6.1
      └──┬────┬─────┬──────┬──
         10  100  1000  10000   ← 청크 크기 (로그 스케일)

      ← 커밋 오버헤드 지배        메모리·GC 지배 →
                    최적 구간
```

### 왼쪽 내리막 — 커밋 오버헤드

청크 10 이 느린 이유는 **7,000번 커밋**하기 때문입니다. 커밋 한 번마다 다음이 일어납니다.

| 비용 | 내용 |
|---|---|
| DB 트랜잭션 커밋 | InnoDB redo 로그 flush (`innodb_flush_log_at_trx_commit=1` 이면 fsync) |
| 메타데이터 UPDATE | `BATCH_STEP_EXECUTION` 의 카운터 갱신 **1회** |
| 실행 컨텍스트 저장 | `BATCH_STEP_EXECUTION_CONTEXT` 갱신 (reader 상태) |
| 배치 INSERT 왕복 | 10건짜리 INSERT 를 7,000번 |

즉 **청크당 고정 비용**이 있고, 청크 수가 곧 그 비용의 배수입니다. 7,000 × 고정비용 vs 70 × 고정비용의 차이가 48초와 6초를 가릅니다.

메타데이터 갱신 부하만 따로 보면 이렇습니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COMMIT_COUNT,
       ROUND(TIMESTAMPDIFF(MICROSECOND, START_TIME, END_TIME)/1000000, 2) AS secs
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID;"
```

**결과**
```
+--------------+-------+
| COMMIT_COUNT | secs  |
+--------------+-------+
|         7001 | 48.22 |
|          701 | 12.70 |
|           71 |  6.10 |
|            8 |  7.44 |
+--------------+-------+
```

7,001 → 701 로 커밋을 1/10 로 줄이자 시간이 48.2 → 12.7 로 거의 1/4 이 됐습니다. 커밋 비용이 전부는 아니지만(읽기·처리 비용은 그대로) 지배적입니다.

### 오른쪽 오르막 — 메모리와 GC

청크 10,000 은 커밋을 8번밖에 안 하는데도 느립니다. 힙 peak 를 보면 답이 나옵니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementChunkJob chunkSize=10000' \
  -Dorg.gradle.jvmargs='-Xms256m -Xmx1g -Xlog:gc'
```

**결과** (GC 로그 발췌)
```
[2.104s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 248M->131M(512M) 18.442ms
[3.351s][info][gc] GC(4) Pause Young (Normal) (G1 Evacuation Pause) 431M->208M(768M) 31.208ms
[4.677s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 612M->266M(1024M) 44.913ms
[5.902s][info][gc] GC(6) Pause Young (Normal) (G1 Evacuation Pause) 598M->271M(1024M) 41.775ms
```

청크 10,000 이면 `Order` 10,000개 + `Settlement` 10,000개, 총 20,000개 객체가 동시에 살아 있습니다. `BigDecimal` 필드가 4개씩 붙으니 실제 객체 수는 그 몇 배입니다. Young 영역을 넘겨 승격되고, GC pause 가 40ms 대로 커집니다.

청크 1,000 에서는 같은 로그가 이렇습니다.

**결과**
```
[2.088s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 216M->84M(512M) 6.311ms
[3.412s][info][gc] GC(4) Pause Young (Normal) (G1 Evacuation Pause) 288M->91M(512M) 7.024ms
```

**pause 가 44ms → 7ms 입니다.** 커밋을 63번 아낀 이득보다 GC 로 잃은 손해가 더 큽니다.

> ⚠️ **함정 — "청크를 키우면 무조건 빨라진다"는 착각이 OOM 을 부릅니다**
> 위 실측에서 10,000 은 7.4초로 "조금 느린" 정도였습니다. 힙이 1GB 였기 때문입니다.
> 그런데 운영에서 아이템이 이 실습보다 훨씬 무거우면(예: `order_items` 3건을 함께 들고 있는 주문 객체, 또는 `TEXT` 본문) 이야기가 달라집니다.
> 청크 10,000 × 아이템 20KB = **200MB 가 청크 하나에** 들어갑니다. 여기에 출력 객체까지 더해지면 힙 512MB 컨테이너는 그대로 `OutOfMemoryError` 입니다.
> 더 나쁜 건 **10만 건 테스트에서는 안 터지고 700만 건 운영에서 터진다**는 점입니다. 청크 크기는 **아이템 하나의 크기**와 함께 정해야 합니다.
> 판단식: `chunkSize × (입력 아이템 크기 + 출력 아이템 크기) × 2` 가 힙의 **1/4** 을 넘지 않게.

---

## 5-7. 실패하면 청크 단위로 롤백된다

이제 이 스텝에서 가장 중요한 실습입니다. **25,501번째 아이템에서 일부러 예외를 던집니다.**

```java
// [5-7] 25,501번째 아이템에서 터지는 processor
static class ExplodingProcessor implements ItemProcessor<Order, Settlement> {

    private final AtomicInteger seq = new AtomicInteger();
    private final SettlementProcessor delegate;

    ExplodingProcessor(SettlementProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public Settlement process(Order item) throws Exception {
        int n = seq.incrementAndGet();
        if (n == 25_501) {
            throw new IllegalStateException("의도적 실패: " + n + "번째 아이템 (order_id=" + item.order_id() + ")");
        }
        return delegate.process(item);
    }
}
```

청크 1,000 으로 돌립니다. 25,501번째는 **26번째 청크의 501번째 아이템**입니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=settlementFailJob chunkSize=1000'
```

**결과**
```
INFO 43902 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementFailStep]
DEBUG 43902 --- [           main] o.s.b.c.step.item.ChunkOrientedTasklet   : Applying contribution: [StepContribution: read=1000, written=0, filtered=0, ...]
...
ERROR 43902 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step settlementFailStep in job settlementFailJob

java.lang.IllegalStateException: 의도적 실패: 25501번째 아이템 (order_id=36430)
	at com.example.batch.step05.ExplodingProcessor.process(ExplodingProcessor.java:24)
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.doProcess(SimpleChunkProcessor.java:126)
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.transform(SimpleChunkProcessor.java:296)
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.process(SimpleChunkProcessor.java:213)
	at org.springframework.batch.core.step.item.ChunkOrientedTasklet.execute(ChunkOrientedTasklet.java:75)
	at org.springframework.batch.core.step.tasklet.TaskletStep$ChunkTransactionCallback.doInTransaction(TaskletStep.java:407)
	...

INFO 43902 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementFailStep] executed in 2s488ms
INFO 43902 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementFailJob]] completed with the following parameters: [{...}] and the following status: [FAILED] in 2s601ms
```

DB 를 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) rows_written, MIN(order_id) min_id, MAX(order_id) max_id FROM settlement;"
```

**결과**
```
+--------------+--------+--------+
| rows_written | min_id | max_id |
+--------------+--------+--------+
|        25000 |      1 |  35714 |
+--------------+--------+--------+
```

**정확히 25,000건.** 25,501건도, 25,500건도 아닙니다.

- 청크 1 ~ 25 (아이템 1 ~ 25,000): **커밋됨**
- 청크 26 (아이템 25,001 ~ 26,000): **전부 롤백됨**

26번째 청크는 501번째 아이템에서 터졌지만, 앞선 500건도 함께 사라졌습니다. **롤백 단위는 아이템이 아니라 청크**이기 때문입니다.

메타데이터도 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STATUS, EXIT_CODE, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT,
       LEFT(EXIT_MESSAGE, 60) AS msg
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1\G"
```

**결과**
```
*************************** 1. row ***************************
        STATUS: FAILED
     EXIT_CODE: FAILED
    READ_COUNT: 25000
   WRITE_COUNT: 25000
  COMMIT_COUNT: 25
ROLLBACK_COUNT: 1
           msg: java.lang.IllegalStateException: 의도적 실패: 25501번째
```

여기서 놓치기 쉬운 게 `READ_COUNT: 25000` 입니다. **실제로는 26,000건을 읽었습니다.** 26번째 청크가 1,000건을 다 읽은 뒤에 처리 단계에서 터졌으니까요.

> ⚠️ **함정 — 실패한 청크의 READ_COUNT 는 메타데이터에 남지 않습니다**
> Spring Batch 는 청크를 처리하는 동안 카운터를 `StepContribution` 이라는 **임시 객체**에 모았다가, 트랜잭션이 커밋될 때 `StepExecution` 에 반영(`apply`)합니다.
> 롤백되면 그 `StepContribution` 은 **버려집니다.** 그래서 실제 읽은 26,000건 중 롤백된 1,000건은 통계에서 사라집니다.
> 이게 왜 위험하냐면, **"읽은 건수 = 처리한 건수"를 검증 로직으로 쓰는 경우**입니다.
> `READ_COUNT == WRITE_COUNT` 니까 정상이라고 판단하는 후속 Step 을 만들면, **실패한 배치를 성공으로 오인**합니다. 위 결과도 25000 == 25000 입니다.
> 배치 성공 여부는 **반드시 `STATUS`/`EXIT_CODE` 로 판정**하세요. 카운터의 일치는 성공의 근거가 아닙니다.

> 💡 **그럼 25,001번째부터 다시 돌리려면?**
> 같은 `JobParameters` 로 다시 실행하면 Spring Batch 가 실패한 `StepExecution` 을 찾아 이어서 실행합니다. 재시작 시점은 reader 가 `ExecutionContext` 에 저장해 둔 위치입니다.
> 다만 `JdbcCursorItemReader` 는 재시작 시 **처음부터 다시 읽고 25,000건을 버리는** 방식이라 느립니다. 이 차이는 [Step 06](../step-06-item-reader/) 과 [Step 09](../step-09-execution-context/) 에서 정면으로 다룹니다.
> `settlement.order_id` 에 UNIQUE 제약이 걸려 있어서, 재시작이 잘못 동작하면 조용히 중복되는 대신 `DuplicateKeyException` 으로 시끄럽게 실패합니다.

---

## 5-8. `Chunk<T>` — 5.x 에서 `List` 가 사라졌다

Spring Batch **5.0** 에서 `ItemWriter` 의 시그니처가 바뀌었습니다.

```java
// 4.x
public interface ItemWriter<T> {
    void write(List<? extends T> items) throws Exception;
}

// 5.x
public interface ItemWriter<T> {
    void write(Chunk<? extends T> chunk) throws Exception;
}
```

`Chunk<T>` 는 `org.springframework.batch.item.Chunk` 이고 `Iterable<T>` 를 구현합니다. 주요 API 는 이렇습니다.

| 메서드 | 설명 |
|---|---|
| `getItems()` | 내부 `List<T>` 반환 (수정 가능한 뷰) |
| `size()` / `isEmpty()` | 아이템 개수 |
| `iterator()` | `ChunkIterator` — 순회 중 `remove()` 가능 |
| `add(T)` / `addAll(Collection)` | 아이템 추가 |
| `getSkips()` | 이 청크에서 스킵된 아이템 목록 ([Step 11](../step-11-fault-tolerance/)) |
| `Chunk.of(T...)` | 정적 팩터리 — **테스트 작성 시 요긴합니다** |

```java
// [5-8] 5.x writer 구현
static class SettlementLogWriter implements ItemWriter<Settlement> {

    private static final Logger log = LoggerFactory.getLogger(SettlementLogWriter.class);

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        // Chunk 는 Iterable 이므로 향상된 for 문이 그대로 됩니다
        BigDecimal sum = BigDecimal.ZERO;
        for (Settlement s : chunk) {
            sum = sum.add(s.netAmount());
        }
        // List 가 필요하면 getItems()
        List<? extends Settlement> items = chunk.getItems();
        log.debug("청크 {}건, 첫 주문 {}, 순액 합계 {}",
                chunk.size(), items.get(0).orderId(), sum);
    }
}
```

**결과** (DEBUG 로그)
```
DEBUG 44017 --- [           main] c.e.b.step05.SettlementLogWriter        : 청크 1000건, 첫 주문 1, 순액 합계 48479322.50
DEBUG 44017 --- [           main] c.e.b.step05.SettlementLogWriter        : 청크 1000건, 첫 주문 1430, 순액 합계 48343095.00
DEBUG 44017 --- [           main] c.e.b.step05.SettlementLogWriter        : 청크 1000건, 첫 주문 2859, 순액 합계 48512648.00
```

> ⚠️ **함정 — 4.x 코드를 옮길 때 `write(List)` 는 컴파일 에러가 아니라 "그냥 안 불립니다"**
> `ItemWriter` 를 **인터페이스로 구현**했다면 `write(List<...>)` 는 `@Override` 가 붙어 있는 한 컴파일 에러가 납니다. 이건 안전한 경우입니다.
> 문제는 **`@Override` 를 빼먹었거나, 추상 클래스를 상속해 오버라이드한 척한 경우**입니다. 그 메서드는 그냥 이름이 같은 별개의 메서드가 되고,
> 인터페이스 기본 구현이나 상위 클래스 구현이 대신 호출됩니다. **에러 없이, 아무것도 안 쓰이고, 배치는 `COMPLETED` 로 끝납니다.**
> `WRITE_COUNT` 는 70,000 으로 찍히는데 `settlement` 는 0행인 상황이 이렇게 만들어집니다.
> 마이그레이션할 때 **`@Override` 를 반드시 붙이세요.** 그러면 컴파일러가 대신 잡아 줍니다.

`Chunk.of()` 는 writer/processor 단위 테스트에서 특히 유용합니다.

```java
// [5-8] 테스트에서 Chunk 만들기
@Test
void writerInsertsAllRows() throws Exception {
    Chunk<Settlement> chunk = Chunk.of(
            new Settlement(1L, 1, LocalDate.of(2025, 1, 1),
                    new BigDecimal("1000.00"), new BigDecimal("0.0300"),
                    new BigDecimal("30.00"), new BigDecimal("970.00")),
            new Settlement(2L, 2, LocalDate.of(2025, 1, 1),
                    new BigDecimal("2000.00"), new BigDecimal("0.0250"),
                    new BigDecimal("50.00"), new BigDecimal("1950.00"))
    );

    writer.write(chunk);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement", Integer.class)).isEqualTo(2);
}
```

**결과**
```
BUILD SUCCESSFUL in 3s
2 tests completed
```

---

## 5-9. 청크 크기 선정 가이드

실측 결과와 위 함정들을 종합하면 이렇습니다.

| 상황 | 권장 청크 크기 | 이유 |
|---|---|---|
| **기본값** | **500 ~ 1,000** | 대부분의 RDB 배치에서 커밋 비용과 메모리가 균형 |
| 아이템이 무거움 (수십 KB 이상) | 50 ~ 200 | 메모리가 먼저 한계 |
| 아이템이 가벼움 (몇 개 컬럼) | 1,000 ~ 5,000 | 커밋 비용이 지배적 |
| 외부 API 호출이 writer 에 있음 | API 의 벌크 한도에 맞춤 | 예: 벌크 한도 500 이면 500 |
| 원격 트랜잭션/XA | 작게 (100 이하) | 커밋이 비싸도 실패 범위를 좁히는 게 중요 |
| 실패 시 재처리 비용이 큼 | 작게 | 롤백 단위 = 손실 단위 |
| 멀티스레드 Step | 스레드 수 × 청크 크기로 메모리 계산 | 4스레드 × 1,000 = 4,000개 상주 |

정하는 순서는 이렇습니다.

1. **메모리 상한을 먼저 잡습니다.** `chunkSize × (입력 + 출력 아이템 크기) × 2 ≤ 힙 / 4`.
2. 그 상한 안에서 **1,000 부터 시작**합니다.
3. 실제 데이터로 **10 / 100 / 1000 / 10000 을 측정**합니다. 이 스텝에서 한 그대로입니다.
4. 최적점 근처에서 U자 곡선이 **평평**하면(위 실측의 1000~10000 구간처럼) **작은 쪽을 고릅니다.** 속도가 비슷하다면 롤백 단위가 작은 게 낫습니다.

> 💡 **실무 팁 — 청크 크기를 하드코딩하지 마세요**
> 청크 크기는 **데이터 양·아이템 크기·인프라**가 바뀌면 함께 바뀌어야 하는 값입니다. 코드에 `1000` 을 박아 두면 운영에서 조정할 수 없습니다.
> ```java
> @Value("${batch.settlement.chunk-size:1000}") int chunkSize
> ```
> 처럼 설정으로 빼고, `application.yml` 에 기본값을 둡니다. 그러면 장애 상황에서 재배포 없이 청크를 줄일 수 있습니다.
> JobParameter 로 받는 것도 방법이지만, 청크 크기는 **식별 파라미터가 아니므로** `identifying=false` 로 넣어야 합니다([Step 03](../step-03-job-parameters/)). 안 그러면 청크 크기만 바꿔도 새 `JobInstance` 가 생깁니다.

> 💡 **`fetchSize` 도 같이 맞추세요**
> `JdbcCursorItemReader` 의 `fetchSize` 는 **JDBC 드라이버가 한 번에 가져올 행 수**입니다. 청크 크기와 개념이 다릅니다.
> 기본값(MySQL 은 사실상 전량 또는 1)으로 두면 청크를 아무리 튜닝해도 네트워크 왕복이 병목입니다. 보통 **청크 크기와 같게** 맞춥니다.
> 다만 MySQL 커넥터는 `fetchSize` 를 진짜로 존중하려면 별도 조건이 필요합니다 — [Step 06](../step-06-item-reader/) 에서 다룹니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 청크 지향 처리 | N건씩 읽고-처리하고-쓰고-커밋을 반복. 메모리·트랜잭션·재시작을 동시에 해결 |
| 호출 순서 | **read ×N 을 먼저 다 하고 → process ×N → write 1회.** 아이템별 인터리브가 아님 |
| Processor 의 시야 | 아이템 하나. 청크 전체를 볼 수 없음 → 집계는 SQL 이나 별도 Step 으로 |
| 5.x 시그니처 | `.<I, O>chunk(size, txManager)`. 트랜잭션 매니저가 **인자**로 |
| `.chunk(1000)` 단독 | 컴파일은 되고 런타임에 `IllegalStateException: A transaction manager must be provided` |
| 청크 크기 | **= 커밋 간격 = 트랜잭션 경계 = 롤백 단위** |
| `COMMIT_COUNT` | `ceil(건수 / 청크) + 1`. 마지막 빈 사이클이 하나 더 커밋 |
| 실측 (70,000건) | 10→48.2s, 100→12.7s, **1000→6.1s**, 10000→7.4s. **약 8배 차이, U자 곡선** |
| 왼쪽 내리막 | 커밋 오버헤드(redo flush + 메타데이터 UPDATE + 왕복) |
| 오른쪽 오르막 | 힙 사용량과 GC pause (44ms vs 7ms) |
| 롤백 단위 | **청크.** 25,501번째에서 실패 → 25,000건만 남고 청크 26 은 통째로 사라짐 |
| 실패 청크의 통계 | `StepContribution` 이 버려져 `READ_COUNT` 에 반영 안 됨. **성공 판정은 `STATUS` 로** |
| `Chunk<T>` | 5.0 부터 `ItemWriter.write(Chunk<? extends T>)`. `Iterable`, `getItems()`, `Chunk.of()` |
| 마이그레이션 사고 | `@Override` 없이 `write(List)` 를 남기면 **조용히 아무것도 안 쓰임** |
| 크기 선정 | 기본 500~1,000 → 메모리 상한 확인 → 실측 → 평평하면 작은 쪽 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 4.x 스타일 Step 정의를 5.x 로 마이그레이션하기
2. 70,000건 / 청크 700 일 때의 `COMMIT_COUNT` 예측하고 검증하기
3. 청크 300 으로 돌려 U자 곡선의 어디쯤인지 실측하기
4. 특정 아이템에서 실패시켜 `settlement` 잔존 건수를 **미리 계산**하고 맞히기
5. 4.x `write(List)` writer 를 `Chunk` 기반으로 고치기
6. 아이템 크기가 주어졌을 때 안전한 최대 청크 크기 계산하기

---

## 다음 단계

청크의 골격을 알았으니 이제 각 구성요소를 하나씩 깊게 봅니다. 먼저 **ItemReader** 입니다.
이 스텝에서 쓴 `JdbcCursorItemReader` 는 DB 커넥션을 배치가 끝날 때까지 붙잡고 있어서 멀티스레드에 못 씁니다.
대안인 `JdbcPagingItemReader` 는 그 문제를 해결하지만, **정렬 키를 잘못 주면 70,000건 중 68,412건만 처리하고도 `COMPLETED` 로 끝나는** 훨씬 무서운 함정이 있습니다. 그 함정을 직접 재현합니다.

→ [Step 06 — ItemReader](../step-06-item-reader/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 읽으며 5-2 ~ 5-9 의 모든 실행과 실측을 재현하고, `Exercise.java` 의 6문제를 직접 풀어 본 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step05` 패키지이며, [프로젝트 셋업](../project/) 에서 만든 `spring-batch5-lab` 프로젝트 안에 그대로 넣으면 동작합니다.

### Practice.java

본문의 모든 예제를 절 번호 주석(`// [5-3]` 등)과 함께 담은 `@Configuration` 클래스입니다.

- **모든 Job 은 `@ConditionalOnProperty` 없이 정의되어 있고, 실행 대상은 `--spring.batch.job.name=` 으로 고릅니다.** 한 번에 전부 돌지 않도록 반드시 Job 이름을 지정하세요. 지정하지 않으면 Boot 3.2 가 컨텍스트의 모든 Job 을 순차 실행합니다.
- `[5-3]` 의 `settlementStep` 이 기준 Step 입니다. 청크 크기는 `@Value("${batch.chunk-size:1000}")` 로 주입되므로 `--batch.chunk-size=100` 처럼 커맨드라인에서 바꿀 수 있고, 5-6 의 4회 측정이 이 한 줄에 기댑니다.
- `feeRateCache` 빈이 `customers` 1,000행의 `fee_rate` 를 `Map<Integer, BigDecimal>` 로 한 번만 로드합니다. Processor 가 아이템마다 DB 를 조회하지 않게 하려는 것으로, **5-6 의 실측이 순수하게 청크 크기의 영향만 반영하도록** 하기 위한 장치입니다. 이 캐시를 빼고 아이템마다 `SELECT` 하면 어떤 청크 크기를 써도 70,000번의 왕복이 지배해 U자 곡선이 안 보입니다.
- `[5-5]` 의 `slowWriter` 는 `--slow=true` 일 때만 청크당 200ms 를 쉽니다. 트랜잭션 경계를 다른 터미널에서 관찰하기 위한 것이며, 이 플래그를 켠 채로 5-6 의 측정을 하면 안 됩니다.
- `[5-7]` 의 `ExplodingProcessor` 는 `AtomicInteger` 로 25,501번째를 세어 예외를 던집니다. **단일 스레드 Step 이라 카운터가 정확합니다.** [Step 13](../step-13-scaling/) 의 멀티스레드 Step 에 그대로 가져가면 25,501번째가 매번 달라집니다.
- 파일 하단의 `[5-8]` `SettlementLogWriter` 는 실제 INSERT 를 하지 않고 로그만 남깁니다. `Chunk<T>` API 를 관찰하는 용도이므로, 이 writer 를 쓰는 Job 을 돌리면 `settlement` 는 비어 있는 게 정상입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·5** 는 **코드를 고치는** 문제(4.x → 5.x 마이그레이션 / `write(List)` → `write(Chunk)`)이고, **문제 2·4·6** 은 **먼저 종이에 계산하고 나서 실행으로 검증**하는 문제입니다. 순서를 지키세요. 실행 결과를 보고 나서 역산하면 배우는 게 없습니다.
- 문제 2 의 답은 100 이 아닙니다. 5-4 의 `+1` 규칙을 적용해야 합니다.
- 문제 3 은 실제로 배치를 돌려야 합니다. 매 실행 전 파일 상단 주석의 초기화 스크립트를 돌리지 않으면 `settlement.order_id` 의 UNIQUE 제약에 걸려 `DuplicateKeyException` 이 납니다. 이건 버그가 아니라 [프로젝트 셋업](../project/) 이 의도한 안전장치입니다.
- 문제 4 의 `failAt` 값은 여러분이 정합니다. 청크 크기와의 관계에서 **커밋된 청크 수를 나눗셈으로 먼저 구하고**, 그 값에 청크 크기를 곱한 것이 잔존 건수입니다.
- 문제 6 은 코드를 실행하지 않는 계산 문제입니다. 힙 크기와 아이템 크기가 주석에 주어져 있습니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `StepBuilderFactory` 제거와 `.chunk(size, txManager)` 두 가지가 핵심인데, 주석은 여기에 더해 **`.chunk(1000)` 로만 고쳤을 때 컴파일이 통과하고 런타임에 죽는 경로**를 스택트레이스와 함께 설명합니다. 5-3 의 함정과 같은 내용입니다.
- **정답 2** 는 `ceil(70000/700) + 1 = 101` 입니다. 주석은 마지막 빈 사이클이 왜 존재하는지 — reader 의 `null` 을 받아야만 종료를 알 수 있다는 계약 — 를 설명합니다.
- **정답 3** 의 실측값은 **8.9초**로, 100(12.7초)과 1000(6.1초) 사이입니다. 주석은 300 이 아직 U자 곡선의 **왼쪽 내리막**에 있으며 커밋 233회의 오버헤드가 남아 있다고 해석합니다.
- **정답 4** 는 `failAt=25501`, `chunkSize=1000` 일 때 `(25501 - 1) / 1000 = 25` 청크가 커밋되어 **25,000건**이라고 답합니다. 주석은 `failAt=26000` 이면 답이 여전히 25,000 이고 `failAt=26001` 이어야 26,000 이 되는 **경계 조건**을 따로 짚습니다. 이 off-by-one 이 문제의 핵심입니다.
- **정답 5** 는 `@Override` 를 붙이는 것이 왜 마이그레이션의 안전벨트인지를 강조합니다. 정답 코드보다 그 주석이 더 깁니다 — 5-8 의 "조용히 아무것도 안 쓰임" 사고가 이 스텝에서 가장 값비싼 함정이기 때문입니다.
- **정답 6** 은 힙 512MB, 입력 8KB, 출력 4KB 조건에서 `512MB / 4 / (12KB × 2) ≈ 5,461` 를 구한 뒤, **안전 여유를 두고 1,000 을 고르라**고 답합니다. 계산식은 상한을 알려 줄 뿐이고, 상한 근처를 고르는 건 GC 를 감수하겠다는 뜻이라는 해설이 붙습니다.

```java file="./Solution.java"
```
