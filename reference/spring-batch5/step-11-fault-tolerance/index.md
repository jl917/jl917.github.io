# Step 11 — 내결함성: skip · retry · 재시작

> **학습 목표**
> - `.faultTolerant()` 를 켜고 `.skip()` · `.skipLimit()` · `.noSkip()` · 커스텀 `SkipPolicy` 로 건너뛸 예외를 정확히 통제한다
> - **skip 이 발생하면 청크 전체가 롤백되고 아이템을 하나씩 다시 처리(scan)한다**는 사실을 로그로 정면 재현한다
> - 정상 6.1초 → 쓰기 skip 100건 34.7초, **약 5.7배** 느려지는 것을 실측하고 원인을 `commitCount` 로 증명한다
> - `.retry()` · `.retryLimit()` · `backOffPolicy` 를 걸고, retry 와 skip 이 어떤 순서로 맞물리는지 확인한다
> - 실패한 Job 을 재시작해 `BATCH_STEP_EXECUTION.read_count` 로 중단 지점을 이어받았는지 검증한다
> - **Reader 가 상태를 저장하지 않으면 재시작이 처음부터 다시 읽는다**는 조용한 사고를 재현한다
> - `.allowStartIfComplete(true)` · `.startLimit(n)` · `.noRollback()` 의 정확한 의미를 구분한다
>
> **선행 스텝**: Step 10 — 흐름 제어와 조건 분기
> **예상 소요**: 120분

---

배치가 10만 건을 처리하다 3만 건째에서 예외 하나를 만났습니다. 선택지는 셋입니다.

1. **죽는다** — 3만 건은 커밋됐고 나머지는 안 됐습니다. 손으로 이어 붙여야 합니다.
2. **건너뛴다(skip)** — 그 한 건만 버리고 계속 갑니다. 대신 **버렸다는 사실을 아무도 모르게 될 위험**이 생깁니다.
3. **다시 해 본다(retry)** — 일시적 장애(데드락, 커넥션 끊김)라면 몇 초 뒤엔 성공할 수 있습니다.

Spring Batch 는 셋 다 지원합니다. 문제는 **셋을 잘못 조합하면 에러 없이 조용히 데이터가 새거나, 배치가 6배 느려진다**는 점입니다. 이 스텝은 그 대가를 전부 숫자로 확인합니다.

---

## 11-0. 실습 준비 — 불량 데이터 심기

정산 대상은 `orders` 의 `COMPLETED` **70,000건**이고, 청크 1,000이면 **정확히 70청크**입니다(프로젝트 셋업 문서 참조).
여기에 두 종류의 사고를 인위적으로 심습니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t <<'SQL'
-- (A) Processor 에서 터질 불량 주문: 금액이 음수. order_id 가 1000의 배수인 100건.
--     order_id % 10 = 0 이므로 이 100건은 전부 status='COMPLETED' 입니다.
UPDATE orders SET amount = -1.00 WHERE order_id % 1000 = 0;

-- (B) Writer 에서 터질 중복 정산: settlement 에 미리 100행을 넣어 UNIQUE 키를 충돌시킵니다.
TRUNCATE TABLE settlement;
INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
SELECT o.order_id, o.customer_id, DATE(o.ordered_at), 1000.00, 0.0300, 30.00, 970.00
FROM orders o WHERE o.status = 'COMPLETED' AND o.order_id % 1000 = 0;

SELECT (SELECT COUNT(*) FROM orders WHERE status='COMPLETED')            AS target,
       (SELECT COUNT(*) FROM orders WHERE amount < 0)                    AS bad_amount,
       (SELECT COUNT(*) FROM settlement)                                 AS preloaded;
SQL
```

**결과**
```
+--------+------------+-----------+
| target | bad_amount | preloaded |
+--------+------------+-----------+
|  70000 |        100 |       100 |
+--------+------------+-----------+
```

**불량 100건이 70청크에 고르게 흩어져 있다**는 점이 중요합니다. `COMPLETED` 700건마다 불량이 1건이므로, 청크 크기가 1,000이면 **70청크 전부가 최소 1건의 불량을 품습니다.** 뒤에서 이 배치가 왜 그렇게까지 느려지는지의 열쇠가 됩니다.

되돌리려면:

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "
UPDATE orders o JOIN (SELECT order_id, 1000 + (order_id % 977) * 100 AS amt FROM orders) s
  ON o.order_id = s.order_id SET o.amount = s.amt WHERE o.amount < 0;
TRUNCATE TABLE settlement;"
```

---

## 11-1. 아무 대비 없는 Step 은 첫 예외에서 통째로 멈춘다

Step 07 에서 만든 정산 Step 을 그대로 돌립니다. Processor 는 금액이 0 이하면 예외를 던집니다.

```java
@Bean
public ItemProcessor<Order, Settlement> settlementProcessor(JdbcTemplate jdbc) {
    return order -> {
        if (order.amount().signum() <= 0) {
            throw new IllegalStateException(
                    "음수 금액 주문: order_id=" + order.orderId() + ", amount=" + order.amount());
        }
        BigDecimal rate = feeRateOf(jdbc, order.customerId());
        BigDecimal fee  = order.amount().multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return new Settlement(order.orderId(), order.customerId(),
                order.orderedAt().toLocalDate(), order.amount(), rate, fee,
                order.amount().subtract(fee));
    };
}

@Bean
public Step settlementStep(JobRepository repo, PlatformTransactionManager tx,
                           ItemReader<Order> reader, ItemProcessor<Order, Settlement> processor,
                           ItemWriter<Settlement> writer) {
    return new StepBuilder("settlementStep", repo)
            .<Order, Settlement>chunk(1000, tx)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();      // 내결함성 없음
}
```

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-03-01'
```

**결과**
```
INFO 42117 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 42117 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
ERROR 42117 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step settlementStep in job settlementJob

java.lang.IllegalStateException: 음수 금액 주문: order_id=1000, amount=-1.00
	at com.example.batch.step11.SettlementProcessor.process(SettlementProcessor.java:34) ~[main/:na]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.doProcess(SimpleChunkProcessor.java:127) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.transform(SimpleChunkProcessor.java:301) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.process(SimpleChunkProcessor.java:210) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.ChunkOrientedTasklet.execute(ChunkOrientedTasklet.java:75) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.tasklet.TaskletStep$ChunkTransactionCallback.doInTransaction(TaskletStep.java:407) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...

INFO 42117 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 141ms
INFO 42117 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [FAILED] in 268ms
```

`order_id=1000` 은 첫 청크 안에 있습니다. **70,000건 중 999건도 못 쓰고 죽었습니다.**

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT step_name, status, read_count, write_count, commit_count, rollback_count
FROM BATCH_STEP_EXECUTION ORDER BY step_execution_id DESC LIMIT 1;"
```

**결과**
```
+----------------+--------+------------+-------------+--------------+----------------+
| step_name      | status | read_count | write_count | commit_count | rollback_count |
+----------------+--------+------------+-------------+--------------+----------------+
| settlementStep | FAILED |          0 |           0 |            0 |              1 |
+----------------+--------+------------+-------------+--------------+----------------+
```

`read_count = 0` 입니다. 1,000건을 읽긴 했지만 **그 청크의 트랜잭션이 롤백되면서 카운터도 함께 되돌아갔습니다.** StepExecution 의 카운터는 커밋된 것만 셉니다. 이 성질은 11-10 의 재시작 실습에서 그대로 쓰입니다.

---

## 11-2. `.faultTolerant()` 와 `.skip()`

`.faultTolerant()` 를 호출하면 빌더가 `SimpleStepBuilder` → `FaultTolerantStepBuilder` 로 바뀌고, 내부의 청크 처리기가 `SimpleChunkProcessor` → `FaultTolerantChunkProcessor` 로 교체됩니다. **이 교체가 성능 특성 전체를 바꿉니다**(11-5).

```java
return new StepBuilder("settlementStep", repo)
        .<Order, Settlement>chunk(1000, tx)
        .reader(reader)
        .processor(processor)
        .writer(writer)
        .faultTolerant()
        .skip(IllegalStateException.class)
        .skipLimit(200)
        .build();
```

**결과**
```
INFO 42214 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
WARN 42214 --- [           main] o.s.b.c.s.i.FaultTolerantChunkProcessor   : Skipping item on process: java.lang.IllegalStateException: 음수 금액 주문: order_id=1000, amount=-1.00
WARN 42214 --- [           main] o.s.b.c.s.i.FaultTolerantChunkProcessor   : Skipping item on process: java.lang.IllegalStateException: 음수 금액 주문: order_id=2000, amount=-1.00
...
INFO 42214 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 8s 312ms
INFO 42214 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 8s 461ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT read_count, write_count, filter_count,
       read_skip_count, process_skip_count, write_skip_count,
       commit_count, rollback_count, status
FROM BATCH_STEP_EXECUTION ORDER BY step_execution_id DESC LIMIT 1;"
```

**결과**
```
+------------+-------------+--------------+-----------------+--------------------+------------------+--------------+----------------+-----------+
| read_count | write_count | filter_count | read_skip_count | process_skip_count | write_skip_count | commit_count | rollback_count | status    |
+------------+-------------+--------------+-----------------+--------------------+------------------+--------------+----------------+-----------+
|      70000 |       69900 |            0 |               0 |                100 |                0 |           70 |            100 | COMPLETED |
+------------+-------------+--------------+-----------------+--------------------+------------------+--------------+----------------+-----------+
```

읽은 카운터 6종의 의미를 한 번 정리합니다.

| 카운터 | 증가 조건 |
|---|---|
| `read_count` | Reader 가 아이템을 하나 돌려줄 때마다 |
| `write_count` | Writer 에 실제로 넘어간 아이템 수 |
| `filter_count` | **Processor 가 `null` 을 반환**해 걸러낸 수 (skip 아님) |
| `read_skip_count` | 읽기 중 예외를 skip 한 수 |
| `process_skip_count` | 처리 중 예외를 skip 한 수 |
| `write_skip_count` | 쓰기 중 예외를 skip 한 수 |
| `commit_count` | 청크 트랜잭션 커밋 횟수 |
| `rollback_count` | 청크 트랜잭션 롤백 횟수 |

> ⚠️ **함정 — `filter_count` 와 `process_skip_count` 는 완전히 다릅니다**
> Processor 가 `null` 을 반환하면 `filter_count` 가 오르고, **아무 로그도 남지 않으며, 예외도 없습니다.**
> "정산 대상이 아니면 `null` 반환" 같은 코드는 정상 필터링이지만, **`null` 반환을 예외 처리 대용으로 쓰면 조용히 데이터가 사라집니다.**
> 버려도 되는 데이터면 `null`(filter), 버리면 안 되는데 어쩔 수 없이 버리는 데이터면 예외 + skip 입니다. **skip 은 최소한 카운터와 `WARN` 로그를 남깁니다.**

`rollback_count = 100` 에 주목하세요. 커밋은 70번인데 **롤백이 100번**입니다. skip 한 건마다 롤백이 한 번씩 일어났습니다. 이유는 11-5 에서 밝힙니다.

---

## 11-3. `skipLimit` 의 기본값과 예외 계층

### 기본값은 10 입니다

`.skipLimit()` 을 생략하면 어떻게 될까요?

```java
.faultTolerant()
.skip(IllegalStateException.class)      // skipLimit 생략
.build();
```

**결과**
```
WARN 42301 --- [           main] o.s.b.c.s.i.FaultTolerantChunkProcessor   : Skipping item on process: java.lang.IllegalStateException: 음수 금액 주문: order_id=1000, amount=-1.00
...(10건)
ERROR 42301 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step settlementStep in job settlementJob

org.springframework.batch.core.step.skip.SkipLimitExceededException: Skip limit of '10' exceeded
	at org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy.shouldSkip(LimitCheckingItemSkipPolicy.java:120) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.FaultTolerantChunkProcessor$2.recover(FaultTolerantChunkProcessor.java:290) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.retry.support.RetryTemplate.handleRetryExhausted(RetryTemplate.java:557) ~[spring-retry-2.0.5.jar:na]
	...
Caused by: java.lang.IllegalStateException: 음수 금액 주문: order_id=11000, amount=-1.00
	at com.example.batch.step11.SettlementProcessor.process(SettlementProcessor.java:34) ~[main/:na]
```

**`Skip limit of '10' exceeded`.** Spring Batch 5.1 의 `FaultTolerantStepBuilder` 는 `skipLimit` 기본값이 **10** 입니다. 무제한이 아닙니다.

> 💡 **실무 팁 — skipLimit 은 "허용치"가 아니라 "경보 임계값"입니다**
> `skipLimit(Integer.MAX_VALUE)` 로 두면 배치는 절대 안 죽지만, **10만 건 중 8만 건이 버려져도 COMPLETED 로 끝납니다.** 그건 성공이 아닙니다.
> 정상 데이터 품질에서 나올 법한 skip 수의 2~3배 정도로 잡아 두면, 데이터가 갑자기 망가졌을 때 배치가 **시끄럽게** 실패합니다. 조용한 성공보다 시끄러운 실패가 낫습니다.

### 예외 계층과 `.noSkip()`

`.skip()` 은 **하위 타입까지 포함**합니다. `.skip(Exception.class)` 는 사실상 전부입니다.

```java
.faultTolerant()
.skip(Exception.class)                          // 전부 건너뛴다
.noSkip(DataAccessResourceFailureException.class)  // 단, DB 가 죽은 건 건너뛰지 않는다
.noSkip(OutOfMemoryError.class)                 // (Error 는 애초에 Exception 이 아니지만 명시)
.skipLimit(500)
```

판정은 `BinaryExceptionClassifier` 가 하며, **가장 구체적인(상속 거리가 가까운) 등록이 이깁니다.** 등록 순서는 무관합니다.

| 던져진 예외 | `.skip(Exception)` + `.noSkip(DataAccessResourceFailureException)` |
|---|---|
| `IllegalStateException` | skip |
| `DataIntegrityViolationException` | skip (`DataAccessException` 하위지만 noSkip 대상 아님) |
| `DataAccessResourceFailureException` | **skip 안 함 → Step FAILED** |
| `CannotAcquireLockException` | skip |

> ⚠️ **함정 — `.skip(Exception.class)` 는 인프라 장애까지 건너뜁니다**
> DB 커넥션 풀이 고갈되면 아이템마다 `CannotGetJdbcConnectionException` 이 납니다. `.skip(Exception.class).skipLimit(100000)` 이면 배치는 **7만 건을 전부 skip 하고 COMPLETED 로 끝납니다.**
> `settlement` 테이블은 비어 있는데 Job 은 성공. 다음 날 아침 정산 담당자가 발견합니다.
> **skip 대상은 "데이터가 나쁜 경우"로 한정하고, "인프라가 나쁜 경우"는 `noSkip` 으로 빼세요.**

---

## 11-4. 커스텀 SkipPolicy — 조건을 코드로 쓴다

"예외 타입"만으로 판단이 안 되는 경우가 있습니다. `SkipPolicy` 를 직접 구현합니다.

```java
public static class SettlementSkipPolicy implements SkipPolicy {

    private final int limit;

    public SettlementSkipPolicy(int limit) {
        this.limit = limit;
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        // (1) 인프라 장애는 절대 건너뛰지 않는다 — 즉시 Step 실패
        if (t instanceof DataAccessResourceFailureException
                || t instanceof CannotGetJdbcConnectionException) {
            return false;
        }
        // (2) 데이터 품질 문제는 한도까지 건너뛴다
        if (t instanceof IllegalStateException || t instanceof DataIntegrityViolationException) {
            if (skipCount >= limit) {
                throw new SkipLimitExceededException(limit, t);   // 한도 초과는 예외로 알린다
            }
            return true;
        }
        // (3) 나머지는 모르는 예외 → 건너뛰지 않는다
        return false;
    }
}
```

> ⚠️ **함정 — `shouldSkip` 의 두 번째 인자는 "해당 예외의 skip 수"가 아니라 "전체 skip 수"입니다**
> 시그니처가 `long skipCount` 라서 "이 예외를 몇 번 건너뛰었나"로 오해하기 쉽습니다. 실제로는 **읽기·처리·쓰기를 합친 Step 전체의 누적 skip 수**입니다.
> 예외 종류별 한도를 두려면 정책 안에 `Map<Class<?>, AtomicInteger>` 를 직접 들고 있어야 합니다.
> 그리고 이 정책 객체는 **Step 인스턴스와 수명을 같이하므로**, `@Bean` 싱글턴으로 두면 두 번째 실행에서 카운터가 이어집니다. `@StepScope` 를 붙이거나 `open()` 에서 초기화하세요.

등록은 `skipPolicy()` 입니다.

```java
.faultTolerant()
.skipPolicy(new SettlementSkipPolicy(200))
```

> ⚠️ **`.skipPolicy()` 를 쓰면 `.skip()` / `.skipLimit()` 은 전부 무시됩니다.**
> 둘을 섞어 쓰면 컴파일도 되고 실행도 되지만 `.skip(...)` 설정이 **조용히 사라집니다.** 하나만 고르세요.

```
INFO 42388 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 8s 297ms
+------------+-------------+--------------------+--------------+----------------+-----------+
| read_count | write_count | process_skip_count | commit_count | rollback_count | status    |
+------------+-------------+--------------------+--------------+----------------+-----------+
|      70000 |       69900 |                100 |           70 |            100 | COMPLETED |
+------------+-------------+--------------------+--------------+----------------+-----------+
```

---

## 11-5. skip 의 진짜 비용 — 청크 롤백과 스캔 모드

여기가 이 스텝의 핵심입니다.

### 왜 롤백이 100번인가

청크는 **하나의 트랜잭션**입니다. 청크 중간에서 예외가 나면 트랜잭션은 되돌릴 수밖에 없습니다. 그런데 Spring Batch 는 "몇 번째 아이템이 문제인지" 미리 알지 못합니다. 그래서 이렇게 동작합니다.

```
[처리 단계 예외 — process skip]
청크 12 (아이템 11001..12000)
  ├ 1차 시도: 11001 처리 … 11700 에서 IllegalStateException  ──▶ 트랜잭션 ROLLBACK (rollback_count +1)
  ├ 2차 시도: 캐시된 같은 1,000건을 처음부터 다시 처리.
  │           11700 은 "skip 대상"으로 기억해 두었으므로 건너뜀 → 999건 Writer 로 → COMMIT
  └ 결과: rollback 1, commit 1, 처리 함수 호출 1,999회
```

**처리 단계 skip 은 청크를 한 번 더 처리하는 비용**입니다. 아이템 재처리는 메모리 안에서 일어나므로 상대적으로 쌉니다.

쓰기 단계는 완전히 다릅니다.

```
[쓰기 단계 예외 — write skip]
청크 12 (아이템 11001..12000)
  ├ 1차 시도: 1,000건을 batch INSERT → DuplicateKeyException  ──▶ ROLLBACK (rollback_count +1)
  ├ ★ 스캔 모드 진입 ★
  │   Spring Batch 는 "1,000건 중 누가 범인인지" 모릅니다.
  │   그래서 아이템을 하나씩, 각각 독립 트랜잭션으로 다시 씁니다.
  │     11001 → INSERT → COMMIT   (commit_count +1)
  │     11002 → INSERT → COMMIT   (commit_count +1)
  │     ...
  │     11700 → INSERT → DuplicateKeyException → ROLLBACK → skip (rollback_count +1, write_skip_count +1)
  │     ...
  │     12000 → INSERT → COMMIT   (commit_count +1)
  └ 결과: 청크 하나에 트랜잭션이 1,001개
```

**청크 크기가 곧 트랜잭션 폭발 계수입니다.** 청크 1,000에서 쓰기 skip 이 한 건만 나도 그 청크는 1,000번 커밋합니다.

### 실측

이제 (B) 시나리오, 즉 `settlement` 에 미리 넣어 둔 100행과 UNIQUE 키가 충돌하는 상황을 돌립니다.

```java
.faultTolerant()
.skip(DataIntegrityViolationException.class)   // DuplicateKeyException 의 상위
.skipLimit(200)
```

먼저 **정상 기준선**(불량 데이터 없이, `settlement` 비운 상태):

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-03-01'
```

**결과**
```
INFO 42455 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 108ms
+------------+-------------+------------------+--------------+----------------+-----------+
| read_count | write_count | write_skip_count | commit_count | rollback_count | status    |
+------------+-------------+------------------+--------------+----------------+-----------+
|      70000 |       70000 |                0 |           70 |              0 | COMPLETED |
+------------+-------------+------------------+--------------+----------------+-----------+
```

**6.108초, 커밋 70회.** 이제 100행을 미리 심고 다시:

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb <<'SQL'
TRUNCATE TABLE settlement;
INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
SELECT order_id, customer_id, DATE(ordered_at), 1000.00, 0.0300, 30.00, 970.00
FROM orders WHERE status='COMPLETED' AND order_id % 1000 = 0;
SQL
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-03-02'
```

**결과**
```
WARN 42502 --- [           main] o.s.b.c.s.i.FaultTolerantChunkProcessor   : Skipping item on write: org.springframework.dao.DuplicateKeyException: PreparedStatementCallback; SQL [INSERT INTO settlement ...]; Duplicate entry '1000' for key 'settlement.uk_settlement_order'
WARN 42502 --- [           main] o.s.b.c.s.i.FaultTolerantChunkProcessor   : Skipping item on write: org.springframework.dao.DuplicateKeyException: ... Duplicate entry '2000' for key 'settlement.uk_settlement_order'
...
INFO 42502 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 34s 712ms
INFO 42502 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 34s 856ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT read_count, write_count, write_skip_count, commit_count, rollback_count, status
FROM BATCH_STEP_EXECUTION ORDER BY step_execution_id DESC LIMIT 1;"
```

**결과**
```
+------------+-------------+------------------+--------------+----------------+-----------+
| read_count | write_count | write_skip_count | commit_count | rollback_count | status    |
+------------+-------------+------------------+--------------+----------------+-----------+
|      70000 |       69900 |              100 |        69900 |            170 | COMPLETED |
+------------+-------------+------------------+--------------+----------------+-----------+
```

**6.108초 → 34.712초. 약 5.7배 느려졌습니다.**

증거는 `commit_count` 에 그대로 있습니다.

| 지표 | 정상 | 쓰기 skip 100건 | 배수 |
|---|---|---|---|
| 소요 시간 | 6.108초 | 34.712초 | **약 5.7배** |
| `commit_count` | 70 | **69,900** | 999배 |
| `rollback_count` | 0 | 170 | — |
| Writer 호출 횟수 | 70 | 70 + 70,000 | — |

`rollback_count = 170` 의 내역: 각 청크의 1차 시도 실패 70회 + 스캔 중 실제 skip 된 아이템 100회 = 170.
`commit_count = 69,900` 은 **스캔 모드에서 아이템 하나씩 커밋한 횟수**입니다(70,000건 중 skip 100건 제외).

> ⚠️ **함정 — skip 은 "그 한 건만 버리는" 값싼 기능이 아닙니다**
> 많은 사람이 `.skip(Exception.class).skipLimit(10000)` 을 "안전장치"라고 생각하고 걸어 둡니다. 데이터가 깨끗할 땐 아무 일도 없습니다. **문제는 데이터가 나빠진 날입니다.**
> 불량률이 0.14%(7만 건 중 100건) 올라간 것만으로 배치가 6초에서 35초가 됩니다. 불량 100건이 **모든 청크에 하나씩 흩어져 있으면 전체 청크가 스캔 모드로 떨어지기** 때문입니다.
> 실제 운영에서 "어제까지 20분이던 배치가 오늘 3시간 걸린다"의 상당수가 이 현상입니다. 코드는 그대로고, 에러도 없고, Job 은 COMPLETED 로 끝납니다.

### 불량이 뭉쳐 있으면 어떻게 다른가

같은 100건이라도 **앞쪽 1청크에 몰려 있으면** 이야기가 달라집니다.

```bash
# 불량을 order_id 1..1000 안에만 심는다 (COMPLETED 700건 중 100건)
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb <<'SQL'
TRUNCATE TABLE settlement;
INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
SELECT order_id, customer_id, DATE(ordered_at), 1000.00, 0.0300, 30.00, 970.00
FROM orders WHERE status='COMPLETED' AND order_id <= 1000 LIMIT 100;
SQL
```

**결과**
```
INFO 42571 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 594ms
+------------+-------------+------------------+--------------+----------------+
| read_count | write_count | write_skip_count | commit_count | rollback_count |
+------------+-------------+------------------+--------------+----------------+
|      70000 |       69900 |              100 |         1069 |            101 |
+------------+-------------+------------------+--------------+----------------+
```

**6.594초.** skip 수는 똑같이 100건인데 34.7초가 아니라 6.6초입니다. 스캔 모드에 떨어진 청크가 **70개가 아니라 1개**이기 때문입니다(`commit_count` 1,069 = 정상 69청크 + 스캔 1,000회).

**skip 비용은 "skip 건수"가 아니라 "skip 이 몇 개의 청크에 퍼져 있는가"에 비례합니다.**

---

## 11-6. skip 비용을 줄이는 네 가지 방법

| 방법 | 내용 | 효과 |
|---|---|---|
| ① Writer 이전으로 옮기기 | 쓰기에서 터질 조건을 **Processor 에서 미리 판정**해 예외를 던지거나 `null` 을 반환 | 스캔 모드 자체가 사라짐 |
| ② Reader 쿼리에서 제외 | `WHERE amount > 0 AND NOT EXISTS (SELECT 1 FROM settlement s WHERE s.order_id = o.order_id)` | skip 이 0건이 됨 |
| ③ 청크 크기 줄이기 | 스캔 비용은 청크 크기에 비례. 1,000 → 200 이면 스캔 트랜잭션이 1/5 | 부분 완화 |
| ④ 멱등 쓰기 | `INSERT ... ON DUPLICATE KEY UPDATE` 로 중복 자체를 예외로 만들지 않음 | 예외가 안 나므로 최선 |

②를 적용해 다시 재봅니다.

```java
// Reader 의 SQL 에 한 줄 추가
String sql = """
        SELECT o.order_id, o.customer_id, o.amount, o.status, o.ordered_at
        FROM orders o
        WHERE o.status = 'COMPLETED'
          AND o.amount > 0
          AND NOT EXISTS (SELECT 1 FROM settlement s WHERE s.order_id = o.order_id)
        ORDER BY o.order_id
        """;
```

**결과**
```
INFO 42640 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 233ms
+------------+-------------+------------------+--------------+----------------+
| read_count | write_count | write_skip_count | commit_count | rollback_count |
+------------+-------------+------------------+--------------+----------------+
|      69900 |       69900 |                0 |           70 |              0 |
+------------+-------------+------------------+--------------+----------------+
```

**34.712초 → 6.233초. 약 5.6배 빨라졌습니다.** skip 을 잘 쓰는 최고의 방법은 **skip 이 안 나게 하는 것**입니다.

> 💡 **실무 팁 — skip 은 "예상 못 한 소수"를 위한 것입니다**
> 예상되는 제외 조건(취소 주문, 중복 정산, 음수 금액)은 **Reader 의 `WHERE` 절**에 넣는 것이 정답입니다.
> skip 은 "쿼리로는 못 거르는, 처리 중에만 알 수 있는, 드물게 발생하는" 예외를 위한 안전망입니다. 안전망으로 주행하면 안 됩니다.

---

## 11-7. `.retry()` 와 backoff

skip 은 "포기"이고 retry 는 "다시 해 보기"입니다. **일시적(transient) 장애**에만 의미가 있습니다.

```java
.faultTolerant()
.retry(DeadlockLoserDataAccessException.class)
.retry(CannotAcquireLockException.class)
.retryLimit(3)
.backOffPolicy(exponentialBackOff())
.skip(DataIntegrityViolationException.class)
.skipLimit(200)
```

```java
private static BackOffPolicy exponentialBackOff() {
    ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
    policy.setInitialInterval(200L);   // 첫 재시도 전 200ms
    policy.setMultiplier(2.0);         // 200 → 400 → 800
    policy.setMaxInterval(5_000L);
    return policy;
}
```

Writer 가 세 번째 청크에서 데드락을 던지도록 흉내 내면:

**결과**
```
INFO 42711 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
DEBUG 42711 --- [           main] o.s.retry.support.RetryTemplate           : Retry: count=0
WARN 42711 --- [           main] o.s.b.c.s.item.FaultTolerantChunkProcessor: Retryable exception on write: org.springframework.dao.DeadlockLoserDataAccessException: Deadlock found when trying to get lock; try restarting transaction
DEBUG 42711 --- [           main] o.s.retry.backoff.ExponentialBackOffPolicy: Sleeping for 200
DEBUG 42711 --- [           main] o.s.retry.support.RetryTemplate           : Retry: count=1
WARN 42711 --- [           main] o.s.b.c.s.item.FaultTolerantChunkProcessor: Retryable exception on write: org.springframework.dao.DeadlockLoserDataAccessException: Deadlock found when trying to get lock; try restarting transaction
DEBUG 42711 --- [           main] o.s.retry.backoff.ExponentialBackOffPolicy: Sleeping for 400
DEBUG 42711 --- [           main] o.s.retry.support.RetryTemplate           : Retry: count=2
INFO 42711 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 913ms
+------------+-------------+------------------+--------------+----------------+-----------+
| read_count | write_count | write_skip_count | commit_count | rollback_count | status    |
+------------+-------------+------------------+--------------+----------------+-----------+
|      70000 |       70000 |                0 |           70 |              2 | COMPLETED |
+------------+-------------+------------------+--------------+----------------+-----------+
```

`Retry: count=0` → `count=1` → `count=2` 로 **총 3회 시도**했고, 3회차에서 성공했습니다. `rollback_count = 2` 는 실패한 두 번의 시도입니다. skip 은 0건이므로 데이터는 온전합니다.

> ⚠️ **함정 — `.retryLimit(3)` 은 "재시도 3번"이 아니라 "총 시도 3회"입니다**
> 내부적으로 `SimpleRetryPolicy(maxAttempts = retryLimit)` 이 만들어집니다. **최초 시도가 1회에 포함**되므로 실제 재시도는 2번입니다.
> "3번 더 시도하겠지" 하고 SLA 를 계산하면 어긋납니다. 재시도를 N번 하고 싶으면 `.retryLimit(N + 1)` 입니다.

> ⚠️ **함정 — `.retry()` 만 쓰고 `.retryLimit()` 을 빠뜨리면 아무 일도 안 일어납니다**
> `FaultTolerantStepBuilder` 의 `retryLimit` 기본값은 **0** 입니다. 이 상태에서는 등록한 예외가 **재시도 없이 그대로 전파**됩니다.
> 컴파일 에러도, 경고 로그도 없습니다. "retry 를 걸었는데 왜 안 되지?"의 절반은 이것입니다. `.retry()` 와 `.retryLimit()` 은 항상 짝으로 쓰세요.

> 💡 **실무 팁 — backoff 없는 retry 는 장애를 키웁니다**
> `backOffPolicy` 를 생략하면 기본이 `NoBackOffPolicy` 라 **즉시** 재시도합니다. DB 가 부하로 죽어 가는 중이라면 재시도가 부하를 더합니다.
> 데드락·락 타임아웃·커넥션 고갈은 전부 "잠깐 기다리면 풀리는" 종류입니다. `ExponentialBackOffPolicy` 를 기본으로 쓰세요.

---

## 11-8. retry 와 skip 이 만나면

같은 예외를 `.retry()` 와 `.skip()` 에 모두 등록하면 **retry 를 다 소진한 뒤 skip 으로 넘어갑니다.**

```java
.faultTolerant()
.retry(DataIntegrityViolationException.class).retryLimit(3)
.skip(DataIntegrityViolationException.class).skipLimit(200)
```

한 아이템의 생애:

```
아이템 11700 (중복 키)
 ├ 시도 1 → DuplicateKeyException → 롤백 → 재시도
 ├ 시도 2 → DuplicateKeyException → 롤백 → 재시도
 ├ 시도 3 → DuplicateKeyException → retry 소진
 └ RetryTemplate 이 recover() 호출 → SkipPolicy 판정 → skip (write_skip_count +1)
```

**결과**
```
INFO 42780 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 1m 42s 337ms
+------------+-------------+------------------+--------------+----------------+-----------+
| read_count | write_count | write_skip_count | commit_count | rollback_count | status    |
+------------+-------------+------------------+--------------+----------------+-----------+
|      70000 |       69900 |              100 |        69900 |            370 | COMPLETED |
+------------+-------------+------------------+--------------+----------------+-----------+
```

**34.712초 → 102.337초. 약 2.9배 더 느려졌습니다.** 절대 성공할 수 없는 예외(중복 키)를 3번씩 재시도한 뒤에야 skip 하기 때문입니다. `rollback_count` 가 170 → 370 으로 늘어난 것이 그 흔적입니다.

| 예외 성격 | retry | skip |
|---|---|---|
| 데드락 / 락 타임아웃 / 일시적 커넥션 끊김 | **O** | 최후 수단 |
| 외부 API 5xx · 타임아웃 | **O** | 상황에 따라 |
| 중복 키 / 제약 위반 | X (재시도해도 같음) | **O** |
| 데이터 형식 오류 · 파싱 실패 | X | **O** |
| NPE · 로직 버그 | X | **X** — 고쳐야 합니다 |

> ⚠️ **함정 — "일단 둘 다 걸어 두자"가 가장 흔한 실수입니다**
> `.retry(Exception.class).skip(Exception.class)` 조합은 문법적으로 완벽하고, 정상 데이터에서는 아무 차이가 없습니다.
> 불량 데이터가 들어온 날 배치는 **정상 대비 17배**(6.1초 → 102.3초) 느려지고, 여전히 COMPLETED 로 끝납니다.
> **재시도해서 성공할 가능성이 있는 예외에만 retry 를 거세요.**

---

## 11-9. `noRollback` — 롤백시키지 않을 예외

기본적으로 청크에서 나간 예외는 전부 트랜잭션을 롤백시킵니다. 하지만 "예외로 알리되 DB 작업은 유지"하고 싶을 때가 있습니다. 대표적으로 **검증 실패**입니다.

```java
.faultTolerant()
.skip(ValidationException.class)
.skipLimit(500)
.noRollback(ValidationException.class)     // 이 예외는 트랜잭션을 롤백시키지 않는다
```

`noRollback` 없이 `ValidationException` 100건이 발생한 경우와 비교합니다.

**결과 (noRollback 없음)**
```
INFO 42841 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 8s 271ms
+------------+-------------+--------------------+--------------+----------------+
| read_count | write_count | process_skip_count | commit_count | rollback_count |
+------------+-------------+--------------------+--------------+----------------+
|      70000 |       69900 |                100 |           70 |            100 |
+------------+-------------+--------------------+--------------+----------------+
```

**결과 (noRollback 적용)**
```
INFO 42868 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 402ms
+------------+-------------+--------------------+--------------+----------------+
| read_count | write_count | process_skip_count | commit_count | rollback_count |
+------------+-------------+--------------------+--------------+----------------+
|      70000 |       69900 |                100 |           70 |              0 |
+------------+-------------+--------------------+--------------+----------------+
```

**8.271초 → 6.402초 (약 1.3배).** `rollback_count` 가 100 → **0** 이 되었습니다. 청크 재처리가 사라졌기 때문입니다.

> ⚠️ **함정 — `noRollback` 을 DB 예외에 걸면 데이터가 깨집니다**
> `noRollback(DataIntegrityViolationException.class)` 같은 설정은 **절대 하면 안 됩니다.**
> DB 가 제약 위반을 던진 시점에 그 트랜잭션은 이미 오염됐고, MySQL 커넥션은 롤백을 기대합니다. 롤백을 건너뛰면 이후 문장이 `Transaction is marked rollback-only` 로 터지거나, **최악의 경우 반쯤 쓰인 청크가 커밋됩니다.**
> `noRollback` 은 **DB 를 건드리지 않은, 순수 애플리케이션 검증 예외**에만 쓰세요. Processor 가 던지는 `ValidationException` 이 정확히 그 자리입니다.

---

## 11-10. 재시작 — 중단 지점을 이어받는다

이제 skip 없이, 40,001번째 아이템에서 확실히 죽는 Step 을 만듭니다.

```java
@Bean
@StepScope
public ItemProcessor<Order, Settlement> failingProcessor(
        @Value("#{jobParameters['failAt']}") Long failAt) {
    AtomicLong seen = new AtomicLong();
    return order -> {
        if (failAt != null && seen.incrementAndGet() == failAt) {
            throw new IllegalStateException("의도적 실패: " + failAt + "번째 아이템");
        }
        return toSettlement(order);
    };
}
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-04-01 failAt=40001'
```

**결과**
```
INFO 42911 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
ERROR 42911 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step settlementStep in job settlementJob

java.lang.IllegalStateException: 의도적 실패: 40001번째 아이템
	at com.example.batch.step11.RestartConfig.lambda$failingProcessor$0(RestartConfig.java:58) ~[main/:na]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.doProcess(SimpleChunkProcessor.java:127) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...

INFO 42911 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [{'date':'{value=2025-04-01,...}', 'failAt':'{value=40001,...}'}] and the following status: [FAILED] in 3s 981ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT step_execution_id, status, read_count, write_count, commit_count, rollback_count
FROM BATCH_STEP_EXECUTION ORDER BY step_execution_id DESC LIMIT 1;
SELECT COUNT(*) AS settled FROM settlement;"
```

**결과**
```
+-------------------+--------+------------+-------------+--------------+----------------+
| step_execution_id | status | read_count | write_count | commit_count | rollback_count |
+-------------------+--------+------------+-------------+--------------+----------------+
|                17 | FAILED |      40000 |       40000 |           40 |              1 |
+-------------------+--------+------------+-------------+--------------+----------------+
+---------+
| settled |
+---------+
|   40000 |
+---------+
```

**40청크(40,000건)까지 커밋됐습니다.** 41번째 청크는 롤백되어 카운터에 반영되지 않았습니다.

Reader 가 남긴 상태를 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "
SELECT short_context FROM BATCH_STEP_EXECUTION_CONTEXT WHERE step_execution_id = 17\G"
```

**결과**
```
*************************** 1. row ***************************
short_context: {"@class":"java.util.HashMap","orderReader.read.count":40000,"batch.taskletType":"org.springframework.batch.core.step.item.ChunkOrientedTasklet","batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep"}
```

**`orderReader.read.count: 40000`.** 이것이 재시작의 전부입니다. Reader 가 `ItemStream.update()` 에서 청크 커밋마다 저장한 값입니다.

### 같은 파라미터로 다시 실행 = 재시작

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-04-01'
```

`failAt` 을 뺐습니다. 하지만 `failAt` 은 `identifying=true` 인 파라미터라 **JobInstance 가 달라져 버립니다.** 재시작하려면 **식별 파라미터가 완전히 같아야** 합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-04-01 failAt=999999'
```

`failAt=999999` 도 다른 인스턴스입니다. 실무에서는 실패를 유발한 조건만 고치고 **같은 파라미터로** 다시 던집니다. 여기서는 `failAt` 을 `identifying=false` 로 선언해 두었다고 가정하고 그대로 재실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob date=2025-04-01'
```

**결과**
```
INFO 42977 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{'date':'{value=2025-04-01, type=class java.lang.String, identifying=true}'}]
INFO 42977 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
INFO 42977 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 2s 617ms
INFO 42977 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 2s 744ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT step_execution_id, status, read_count, write_count, commit_count
FROM BATCH_STEP_EXECUTION ORDER BY step_execution_id DESC LIMIT 2;
SELECT COUNT(*) AS settled FROM settlement;"
```

**결과**
```
+-------------------+-----------+------------+-------------+--------------+
| step_execution_id | status    | read_count | write_count | commit_count |
+-------------------+-----------+------------+-------------+--------------+
|                18 | COMPLETED |      30000 |       30000 |           30 |
|                17 | FAILED    |      40000 |       40000 |           40 |
+-------------------+-----------+------------+-------------+--------------+
+---------+
| settled |
+---------+
|   70000 |
+---------+
```

**두 번째 실행의 `read_count` 는 70,000 이 아니라 30,000 입니다.** 40,000건을 건너뛰고 나머지 30,000건만 처리했습니다. `settlement` 는 정확히 70,000행. 중복도 누락도 없습니다.

| | 1차 실행 | 2차 실행(재시작) | 합계 |
|---|---|---|---|
| `read_count` | 40,000 | **30,000** | 70,000 |
| `write_count` | 40,000 | 30,000 | 70,000 |
| `commit_count` | 40 | 30 | 70 |
| `status` | FAILED | COMPLETED | |

---

## 11-11. 함정 — Reader 가 상태를 저장하지 않으면

여기가 이 스텝에서 가장 조용하고 가장 비싼 사고입니다.

Reader 를 직접 만들어 봅니다. 아주 자연스러운 코드입니다.

```java
// ⚠️ 이 코드는 컴파일도 되고 정상 실행도 되지만, 재시작이 망가집니다.
@Bean
public ItemReader<Order> naiveReader(JdbcTemplate jdbc) {
    List<Order> all = jdbc.query(SQL, new DataClassRowMapper<>(Order.class));
    Iterator<Order> it = all.iterator();
    return () -> it.hasNext() ? it.next() : null;      // ItemStream 을 구현하지 않음
}
```

Writer 는 중복 사고가 시끄럽게 나지 않도록 upsert 라고 가정합니다(실무에서 흔한 선택입니다).

```java
String sql = """
        INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
        VALUES (:orderId, :customerId, :settleDate, :grossAmount, :feeRate, :feeAmount, :netAmount)
        ON DUPLICATE KEY UPDATE gross_amount = VALUES(gross_amount), net_amount = VALUES(net_amount)
        """;
```

40,001에서 실패시키고 재시작합니다.

**결과 (1차 — 실패)**
```
+-------------------+--------+------------+-------------+--------------+
| step_execution_id | status | read_count | write_count | commit_count |
+-------------------+--------+------------+-------------+--------------+
|                21 | FAILED |      40000 |       40000 |           40 |
+-------------------+--------+------------+-------------+--------------+
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "
SELECT short_context FROM BATCH_STEP_EXECUTION_CONTEXT WHERE step_execution_id = 21\G"
```

**결과**
```
*************************** 1. row ***************************
short_context: {"@class":"java.util.HashMap","batch.taskletType":"org.springframework.batch.core.step.item.ChunkOrientedTasklet","batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep"}
```

**`read.count` 키가 없습니다.** Reader 가 `ItemStream` 이 아니므로 Step 이 저장할 상태 자체가 없었습니다.

**결과 (2차 — 재시작)**
```
INFO 43044 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 155ms
+-------------------+-----------+------------+-------------+--------------+
| step_execution_id | status    | read_count | write_count | commit_count |
+-------------------+-----------+------------+-------------+--------------+
|                22 | COMPLETED |      70000 |       70000 |           70 |
+-------------------+-----------+------------+-------------+--------------+
+---------+
| settled |
+---------+
|   70000 |
+---------+
```

**Job 은 COMPLETED 이고 `settlement` 도 70,000행으로 정확합니다.** 아무 문제 없어 보입니다.

그런데 2차 실행의 `read_count` 가 **30,000 이 아니라 70,000** 입니다. 처음부터 다시 읽은 것입니다. 정상 재시작(11-10)의 2.617초가 여기서는 6.155초, **약 2.4배**입니다.

> ⚠️ **함정 — 재시작이 "동작하는 것처럼 보이는" 것이 가장 위험합니다**
> 결과 테이블이 맞으니 아무도 눈치채지 못합니다. 하지만 실제로 벌어진 일은 이렇습니다.
>
> | | 정상 Reader | 상태 없는 Reader |
> |---|---|---|
> | 재시작 시 `read_count` | 30,000 | **70,000** |
> | Processor 호출 횟수 | 30,000 | **70,000** (4만 건은 두 번째) |
> | 소요 시간 | 2.617초 | 6.155초 (**약 2.4배**) |
> | 결과 테이블 | 정확 | (upsert 덕에) 정확 |
>
> **Processor 에 부작용이 하나라도 있으면 그 순간 사고가 됩니다.** 40,000건에 대해 알림 메일이 두 번 나가고, 포인트가 두 번 적립되고, 외부 정산 API 가 두 번 호출됩니다.
> upsert 가 결과 테이블만 지켜 줄 뿐 **부작용은 못 막습니다.**
>
> **해결책 세 가지**
> 1. `ItemStreamReader` 를 구현하거나, `JdbcCursorItemReader` / `JdbcPagingItemReader` / `FlatFileItemReader` 등 **`ItemStream` 을 구현한 기본 Reader 를 쓴다**(전부 `saveState = true` 가 기본).
> 2. Reader 쿼리를 **"아직 처리 안 된 것만"** 으로 만든다(`NOT EXISTS`). 상태를 DB 가 대신 들고 있게 하는 방식이며 가장 견고합니다.
> 3. Processor 를 **멱등**하게 만든다. 부작용은 Step 밖으로 빼거나, 처리 이력 테이블로 이중 발사를 막습니다.

관련해서 하나 더:

> ⚠️ **함정 — `.saveState(false)` 와 비유니크 `sortKey`**
> - `JdbcCursorItemReader`, `JdbcPagingItemReader` 는 `saveState(false)` 로 끄면 **위와 똑같은 상황**이 됩니다. 멀티스레드 Step(Step 13)에서 상태 저장이 무의미해 끄는 경우가 있는데, 그 순간 재시작 능력을 포기하는 것입니다.
> - `JdbcPagingItemReader` 의 `sortKey` 가 **유니크하지 않으면** 페이지 경계에서 같은 값이 잘리며 **행이 중복되거나 누락**됩니다. `ORDER BY ordered_at` 처럼 중복 가능한 컬럼 단독은 금지입니다. `ordered_at, order_id` 처럼 PK 를 뒤에 붙이세요.
>
> 둘 다 **에러가 나지 않습니다.** 카운터만 이상해집니다.

### 롤백 후 재처리 — Processor 는 두 번 호출된다

11-5 에서 본 "청크 재처리"에는 부수 효과가 하나 더 있습니다.

```java
@Bean
public ItemProcessor<Order, Settlement> sideEffectProcessor(NotificationClient client) {
    return order -> {
        if (order.amount().signum() <= 0) throw new IllegalStateException("음수 금액");
        client.notifySettled(order.orderId());     // ← 외부 호출. 부작용.
        return toSettlement(order);
    };
}
```

불량 100건이 70청크에 흩어진 상태로 돌리면:

**결과**
```
+------------+-------------+--------------------+----------------+
| read_count | write_count | process_skip_count | rollback_count |
+------------+-------------+--------------------+----------------+
|      70000 |       69900 |                100 |            100 |
+------------+-------------+--------------------+----------------+

[NotificationClient] 총 호출 횟수: 139,251
```

**69,900건을 처리했는데 알림은 139,251번 나갔습니다.** 롤백된 70청크가 통째로 재처리되면서 그 안의 정상 아이템들이 두 번씩 Processor 를 통과했기 때문입니다.

기본값 `processorTransactional = true` 는 "Processor 출력은 트랜잭션과 함께 버려지고 다시 만든다"는 뜻입니다. `.processorNonTransactional()` 을 붙이면 출력을 캐시해 재처리를 건너뜁니다.

```java
.faultTolerant()
.skip(IllegalStateException.class).skipLimit(200)
.processorNonTransactional()
```

**결과**
```
[NotificationClient] 총 호출 횟수: 69,900
```

> ⚠️ **`.processorNonTransactional()` 은 Processor 가 순수 함수일 때만 안전합니다.**
> 캐시된 출력을 재사용하므로, Processor 가 내부 상태나 외부 조회 결과에 의존하면 **낡은 값을 쓰게** 됩니다.
> 근본 해법은 **Processor 에서 부작용을 없애는 것**입니다. 알림·API 호출은 별도 Step 이나 `SkipListener`/`ItemWriteListener`(Step 12)로 옮기세요.

---

## 11-12. `allowStartIfComplete` 와 `startLimit`

### `allowStartIfComplete(true)`

COMPLETED 로 끝난 Step 은 재시작 시 **건너뜁니다.**

```
INFO 43111 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Step already complete or not restartable, so no action to take: StepExecution: id=17, version=4, name=prepareStep, status=COMPLETED, exitStatus=COMPLETED
INFO 43111 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
```

그런데 "매번 다시 해야 하는" Step 이 있습니다. 임시 테이블 정리, 작업 디렉터리 비우기 같은 것입니다.

```java
@Bean
public Step prepareStep(JobRepository repo, PlatformTransactionManager tx) {
    return new StepBuilder("prepareStep", repo)
            .tasklet(truncateStagingTasklet(), tx)
            .allowStartIfComplete(true)      // 재시작마다 다시 실행
            .build();
}
```

**결과**
```
INFO 43158 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [prepareStep]
INFO 43158 --- [           main] c.e.batch.step11.PrepareTasklet           : staging 테이블 초기화 완료
INFO 43158 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
```

> ⚠️ **함정 — `allowStartIfComplete(true)` 를 정산 Step 에 걸면 정산이 두 배가 됩니다**
> "재시작이 잘 안 돼서" 이 옵션을 붙이는 경우가 있습니다. 그러면 **이미 커밋된 4만 건을 포함해 전부 다시** 처리합니다.
> `settlement.uk_settlement_order` UNIQUE 제약이 있으면 `DuplicateKeyException` 으로 시끄럽게 실패하니 그나마 다행이고(프로젝트 셋업 문서의 그 제약입니다), upsert 라면 조용히 넘어가지만 부작용은 두 번 발사됩니다.
> 이 옵션은 **멱등한 준비/정리 Step 에만** 씁니다.

### `startLimit(n)`

같은 JobInstance 안에서 이 Step 을 최대 몇 번 시도할지 제한합니다. 기본값은 `Integer.MAX_VALUE` 입니다.

```java
.startLimit(2)
```

세 번째 재시작에서:

**결과**
```
ERROR 43205 --- [           main] o.s.batch.core.job.AbstractJob            : Encountered fatal error executing job

org.springframework.batch.core.StartLimitExceededException: Maximum start limit exceeded for step: settlementStepStartMax: 2
	at org.springframework.batch.core.job.SimpleStepHandler.handleStep(SimpleStepHandler.java:158) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.job.SimpleJob.handleStep(SimpleJob.java:148) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...
INFO 43205 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [FAILED] in 112ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT je.job_execution_id, je.status, COUNT(se.step_execution_id) AS steps
FROM BATCH_JOB_EXECUTION je LEFT JOIN BATCH_STEP_EXECUTION se USING (job_execution_id)
GROUP BY je.job_execution_id ORDER BY je.job_execution_id DESC LIMIT 3;"
```

**결과**
```
+------------------+--------+-------+
| job_execution_id | status | steps |
+------------------+--------+-------+
|               12 | FAILED |     0 |
|               11 | FAILED |     1 |
|               10 | FAILED |     1 |
+------------------+--------+-------+
```

세 번째 실행은 **Step 을 하나도 만들지 못하고** 죽었습니다. 무한 재시작 루프를 막는 안전장치입니다.

> 💡 **실무 팁 — 스케줄러가 자동 재시도한다면 startLimit 을 꼭 거세요**
> Quartz/Airflow 가 실패 시 자동 재실행하도록 설정돼 있으면, 코드 버그로 항상 실패하는 Step 이 **5분마다 4만 건을 다시 처리**하며 DB 를 갈아 댑니다.
> `startLimit(3)` 정도를 두면 세 번 만에 멈추고 사람에게 넘어옵니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `.faultTolerant()` | 청크 처리기를 `FaultTolerantChunkProcessor` 로 교체. 성능 특성이 통째로 바뀜 |
| `.skip(E)` | E 와 **모든 하위 타입**을 건너뜀 |
| `.skipLimit(n)` | **생략 시 기본 10.** 무제한 아님 |
| `.noSkip(E)` | skip 대상에서 제외. 가장 구체적인 등록이 이김(순서 무관) |
| `.skipPolicy(p)` | 쓰는 순간 `.skip()` / `.skipLimit()` 은 **조용히 무시됨** |
| `shouldSkip(t, skipCount)` | `skipCount` 는 Step **전체** 누적 skip 수 |
| skip 의 비용(처리) | 청크 롤백 + 청크 전체 재처리 (약 1.4배) |
| skip 의 비용(쓰기) | 청크 롤백 + **아이템 1건 = 트랜잭션 1개** 스캔 모드 |
| 실측 | 정상 6.108초 → 쓰기 skip 100건 **34.712초 (약 5.7배)**, `commit_count` 70 → 69,900 |
| 비용 결정 요인 | skip **건수**가 아니라 **몇 개 청크에 퍼졌는가**. 1청크에 몰리면 6.594초 |
| 최선의 대응 | Reader `WHERE` 절로 미리 제외 → 6.233초 (**약 5.6배 개선**) |
| `.retry(E).retryLimit(n)` | **n 은 총 시도 횟수**(최초 시도 포함). 재시도는 n-1 번 |
| retryLimit 기본값 | **0** — `.retry()` 만 쓰면 재시도가 일어나지 않음 |
| `backOffPolicy` | 생략 시 `NoBackOffPolicy`(즉시 재시도). 데드락엔 `ExponentialBackOffPolicy` |
| retry + skip 같은 예외 | retry 소진 → skip. 성공 불가능한 예외에 걸면 102.337초(**약 17배**) |
| `.noRollback(E)` | 트랜잭션을 롤백시키지 않음. **애플리케이션 검증 예외에만.** DB 예외에는 금지 |
| 재시작 | 식별 파라미터가 같으면 같은 JobInstance → `ExecutionContext` 로 이어받음 |
| 재시작 검증 | `BATCH_STEP_EXECUTION.read_count` 가 40,000 → **30,000** 이면 성공 |
| 상태 없는 Reader | `ItemStream` 미구현 시 **처음부터 다시 읽음.** 에러 없음, 2.4배 느림, 부작용 두 번 |
| `processorTransactional` | 기본 true → 롤백 시 Processor **재호출**. 부작용 있으면 알림 139,251회 |
| `.allowStartIfComplete(true)` | 멱등한 준비/정리 Step 전용. 정산 Step 에 걸면 이중 정산 |
| `.startLimit(n)` | 같은 JobInstance 내 최대 시도 횟수. 초과 시 `StartLimitExceededException` |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **각 문제는 반드시 실행해 `BATCH_STEP_EXECUTION` 카운터로 검증**하세요.

1. `.skip()` 만 걸고 `.skipLimit()` 을 빠뜨린 Step 이 몇 건째에서 죽는지 예측하고 확인하기
2. 인프라 예외는 절대 건너뛰지 않고 데이터 예외만 300건까지 건너뛰는 `SkipPolicy` 구현하기
3. 쓰기 skip 100건을 처리 skip 으로 옮겨 `commit_count` 를 69,900 → 70 으로 되돌리기
4. `.retry(DeadlockLoserDataAccessException.class)` 에 지수 backoff 를 붙이고 총 시도 횟수를 4회로 맞추기
5. 40,001번째에서 실패시킨 뒤 재시작해 `read_count` 가 30,000 인지 검증하기
6. 상태를 저장하지 않는 Reader 를 `ItemStreamReader` 로 고쳐 재시작이 이어지게 만들기

---

## 다음 단계

skip 과 retry 를 걸었지만, 지금은 **누가 왜 버려졌는지 `WARN` 로그로만** 남습니다. 운영에서는 "어제 정산에서 버려진 주문 100건의 목록"을 파일이나 테이블로 남겨야 하고, 청크마다 진행률을 찍어야 하며, Job 이 끝나면 결과를 알려야 합니다.

다음 스텝에서는 Job/Step/Chunk/Item/Skip/Retry 의 모든 지점에 후크를 거는 **리스너**를 다룹니다. 특히 `SkipListener` 가 **트랜잭션 커밋 이후에 호출된다**는 점과, **리스너에서 예외를 던지면 리스너 종류마다 결과가 다르다**는 점이 이 스텝의 내용과 직접 이어집니다.

→ [Step 12 — 리스너](../step-12-listeners/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 따라가며 11-1 ~ 11-12 의 모든 측정을 재현하고, `Exercise.java` 의 6문제를 직접 채운 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step11` 패키지이며, 하나의 파일 안에 `static class` 로 설정 클래스들을 중첩해 두었습니다. 실행은 `--spring.batch.job.name=` 으로 원하는 Job 만 골라 돌립니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 실습 파일입니다.

- **`[11-0]`** 의 `PlantBadData` 는 불량 데이터를 심고 되돌리는 SQL 을 상수 문자열로 갖고 있습니다. `main` 에서 직접 실행하지 말고, 문서의 `mysql` 명령과 동일한 내용임을 확인하는 용도로 보세요. 실습 순서상 **이것을 먼저 실행하지 않으면 11-2 이후의 skip 이 0건**으로 나옵니다.
- **`[11-1]` → `[11-2]`** 는 같은 Step 을 `.faultTolerant()` 유무로만 다르게 만든 쌍입니다. `noToleranceStep` 과 `skipStep` 을 순서대로 돌려 FAILED / COMPLETED 를 대조하세요.
- **`[11-5]`** 의 `ScanModeDemo` 가 이 스텝의 핵심 측정입니다. `baselineJob`(정상) → `writeSkipJob`(쓰기 skip 100건) → `clusteredSkipJob`(1청크에 몰린 skip 100건) 을 **이 순서대로** 돌려야 6.108 / 34.712 / 6.594 세 숫자가 나옵니다. 중간에 `TRUNCATE TABLE settlement` 를 빠뜨리면 앞 실행의 결과가 남아 skip 수가 달라집니다.
- **`[11-7]`** 의 `FlakyWriter` 는 `AtomicInteger` 로 "세 번째 청크의 처음 두 번의 시도에서만 데드락"을 흉내 냅니다. 진짜 MySQL 데드락을 재현하려면 커넥션 두 개로 교차 갱신을 해야 하는데, 재시도 로그를 보는 것이 목적이므로 예외를 직접 던집니다. 던지는 예외 타입(`DeadlockLoserDataAccessException`)은 실제와 동일합니다.
- **`[11-10]`** 의 `failAt` 파라미터는 `new JobParameter<>(value, Long.class, **false**)` 로 선언돼 있습니다. `identifying=false` 여야 재실행 시 같은 JobInstance 로 붙어 재시작이 됩니다. 이 `false` 를 `true` 로 바꾸면 재시작이 아니라 새 인스턴스가 만들어져 실습이 성립하지 않습니다.
- **`[11-11]`** 은 `NaiveReader`(상태 없음) 와 `StatefulReader`(`ItemStreamReader` 구현) 두 개를 나란히 두었습니다. 같은 Job 을 Reader 만 바꿔 두 번 돌려 `read_count` 30,000 vs 70,000 을 비교하는 것이 목적입니다.
- 파일 맨 아래 `CleanUp` 에 되돌리기 SQL 과 메타데이터 초기화 스크립트를 주석으로 모아 두었습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·4** 는 빌더 체인의 한 줄을 채우는 문제이고, **문제 2·6** 은 클래스를 직접 구현하는 문제, **문제 3·5** 는 설계를 바꿔 카운터를 목표값으로 만드는 문제입니다.
- 문제 2 의 `SkipPolicy` 는 **인프라 예외를 먼저 걸러내는 순서**가 핵심입니다. `instanceof` 판정 순서를 바꾸면 `DataAccessResourceFailureException` 이 `DataAccessException` 분기에 먼저 걸려 조용히 skip 됩니다. 이 순서 실수가 문제 2 의 진짜 함정입니다.
- 문제 3 은 "쓰기에서 터질 조건을 처리 단계에서 미리 판정"하는 문제입니다. 정답 코드가 짧아서 쉬워 보이지만, **검증 쿼리를 아이템마다 날리면 스캔 모드보다 더 느려집니다.** 청크 단위로 한 번에 조회하거나 Reader 쿼리에서 거르는 쪽으로 유도됩니다.
- 문제 5·6 은 **연속 실습**입니다. 5번에서 실패시킨 JobInstance 를 6번에서 그대로 재시작하므로, 5번을 풀고 나서 메타데이터를 초기화하면 6번을 풀 수 없습니다. 파일 상단 주석의 순서를 지키세요.
- 각 문제 끝에 `-- 검증:` 주석으로 확인용 SQL 이 붙어 있습니다. 코드를 고친 뒤 반드시 이 쿼리로 카운터를 확인하세요. **"에러 없이 끝났다"는 정답의 근거가 되지 못합니다.**

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `Skip limit of '10' exceeded` 가 **11번째 불량**에서 터진다는 것을 계산으로 보여줍니다. 불량이 `order_id % 1000 = 0` 이므로 11번째 불량은 `order_id = 11000` 이고, 이는 `COMPLETED` 기준 7,700번째 아이템, 즉 **8번째 청크** 안입니다. "10건 skip 후 죽는다"를 실제 order_id 까지 특정하는 것이 학습 포인트입니다.
- **정답 2** 는 `instanceof` 체인 대신 `BinaryExceptionClassifier` 를 조합해 쓰는 대안도 함께 제시합니다. 그리고 `skipCount` 가 Step 전체 누적이라 **예외 종류별 한도를 두려면 정책이 직접 카운터를 들어야 한다**는 점을, `ConcurrentHashMap<Class<?>, LongAdder>` 구현으로 보여줍니다.
- **정답 3** 의 결론은 `commit_count` 69,900 → **70** 입니다. 처리 단계 skip 은 청크를 한 번 더 처리할 뿐 트랜잭션을 쪼개지 않기 때문입니다. 34.712초 → 8.271초로 줄고, Reader `WHERE` 절로 아예 거르면 6.233초까지 갑니다. 세 단계의 개선폭을 표로 비교합니다.
- **정답 4** 는 `.retryLimit(4)` 입니다. "재시도 3번"이 아니라 "총 시도 4회"라는 것과, `ExponentialBackOffPolicy` 의 `initialInterval × multiplier^(n-1)` 로 최악의 대기 시간이 200+400+800 = 1.4초임을 계산해 둡니다. `maxInterval` 을 안 걸면 재시도가 많은 Step 에서 대기가 폭주한다는 경고도 있습니다.
- **정답 6** 이 가장 깁니다. `ItemStreamReader` 의 `open` / `update` / `close` 를 각각 언제 호출하는지, `ExecutionContext` 의 키에 왜 **Reader 이름을 접두사로** 붙여야 하는지(`setName()` 을 안 부르면 두 Reader 가 같은 키를 덮어씁니다), 그리고 `open()` 에서 `context.containsKey(...)` 로 재시작 여부를 판정하는 관용구를 설명합니다. 마지막에 "직접 구현하지 말고 `JdbcPagingItemReader` 를 쓰라"는 결론이 붙습니다 — **직접 만들 수 있게 된 다음에야 안 만드는 선택이 의미가 있기 때문**입니다.

```java file="./Solution.java"
```
