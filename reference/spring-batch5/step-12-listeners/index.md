# Step 12 — 리스너

> **학습 목표**
> - Job · Step · Chunk · Item · Skip 각 레벨의 리스너가 **언제** 호출되는지 로그로 추적한다
> - 인터페이스 방식과 애너테이션 방식의 차이를 알고, 애너테이션 리스너가 **조용히 등록되지 않는** 경우를 재현한다
> - 리스너에서 던진 예외가 어떻게 처리되는지 **리스너별로 다르다**는 것을 실측한다
> - `SkipListener` 가 트랜잭션 커밋 **후에** 호출된다는 사실과 그 실무적 의미를 이해한다
> - 여러 리스너가 등록됐을 때의 실행 순서를 제어한다
>
> **선행 스텝**: [Step 11 — 내결함성: skip · retry · 재시작](../step-11-fault-tolerance/)
> **예상 소요**: 75분

---

## 12-0. 실습 준비

[Step 11](../step-11-fault-tolerance/) 의 불량 데이터를 다시 심습니다. 리스너가 잡아낼 사건이 있어야 하기 때문입니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t <<'SQL'
UPDATE orders SET amount = -1.00 WHERE order_id % 1000 = 0;
TRUNCATE TABLE settlement;
SELECT (SELECT COUNT(*) FROM orders WHERE status='COMPLETED') AS target,
       (SELECT COUNT(*) FROM orders WHERE amount < 0)         AS bad_amount;
SQL
```

**결과**
```
+--------+------------+
| target | bad_amount |
+--------+------------+
|  70000 |        100 |
+--------+------------+
```

> ⚠️ Step 11 을 끝내며 `orders` 를 복구했다면 위 SQL 로 다시 심어야 합니다. 이 스텝이 끝나면 **반드시 다시 복구하세요.** `Practice.java` 의 `CleanUp.REVERT_SQL` 에 있습니다.

---

## 12-1. 리스너는 어디에 끼어드는가

Spring Batch 의 실행 흐름에는 **정해진 여러 개의 구멍**이 있고, 리스너는 그 구멍에 코드를 꽂는 장치입니다.

```
 JobExecution 시작
 │
 ├─ JobExecutionListener.beforeJob()
 │
 │  ┌─ StepExecution 시작
 │  │
 │  ├─ StepExecutionListener.beforeStep()
 │  │
 │  │   ┌─ 청크 반복 (70번) ────────────────────────────────┐
 │  │   │                                                   │
 │  │   ├─ ChunkListener.beforeChunk()      ← 트랜잭션 시작 후│
 │  │   │                                                   │
 │  │   │   ┌ 아이템 1,000개 반복 ┐                          │
 │  │   │   ├ ItemReadListener.beforeRead()                 │
 │  │   │   ├ ItemReadListener.afterRead(item)              │
 │  │   │   ├ ItemProcessListener.beforeProcess(item)       │
 │  │   │   ├ ItemProcessListener.afterProcess(in, out)     │
 │  │   │   └───────────────────┘                           │
 │  │   │                                                   │
 │  │   ├─ ItemWriteListener.beforeWrite(chunk)             │
 │  │   ├─ ItemWriteListener.afterWrite(chunk)              │
 │  │   │                                                   │
 │  │   ├─ ChunkListener.afterChunk()       ← 트랜잭션 커밋 전│
 │  │   │  ▼ 커밋                                            │
 │  │   ├─ SkipListener.onSkipInWrite()     ← 커밋 "후"      │
 │  │   └───────────────────────────────────────────────────┘
 │  │
 │  ├─ StepExecutionListener.afterStep()  → ExitStatus 반환 가능
 │  └─ StepExecution 종료
 │
 ├─ JobExecutionListener.afterJob()
 └─ JobExecution 종료
```

이 그림에서 **두 가지**를 먼저 눈에 담아 두십시오. 이 스텝의 함정 두 개가 정확히 여기서 나옵니다.

1. `ChunkListener.afterChunk()` 는 **커밋 전**입니다. 여기서 하는 DB 작업은 청크 트랜잭션에 포함되고, 롤백되면 함께 사라집니다.
2. `SkipListener` 는 **커밋 후**입니다. 여기서 하는 작업은 청크 트랜잭션 밖입니다.

---

## 12-2. JobExecutionListener — 가장 바깥

```java
public class SettlementJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementJobListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info(">>> 정산 배치 시작. 파라미터={}", jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        log.info(">>> 정산 배치 종료. status={}, 소요={}ms",
                jobExecution.getStatus(),
                Duration.between(jobExecution.getStartTime(),
                                 jobExecution.getEndTime()).toMillis());
    }
}
```

```java
return new JobBuilder("settlementJob", jobRepository)
        .listener(new SettlementJobListener())
        .start(settlementStep)
        .build();
```

**결과**
```
INFO 44102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 44102 --- [           main] c.e.b.step12.SettlementJobListener        : >>> 정산 배치 시작. 파라미터={date=2025-03-01}
INFO 44102 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
INFO 44102 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 241ms
INFO 44102 --- [           main] c.e.b.step12.SettlementJobListener        : >>> 정산 배치 종료. status=COMPLETED, 소요=6284ms
INFO 44102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED] in 6s 284ms
```

`beforeJob` 은 `Executing step` 보다 앞, `afterJob` 은 `completed with` 보다 **앞**입니다.

> ⚠️ **함정 — `afterJob` 은 실패해도 호출됩니다. 그리고 그게 핵심입니다**
> `afterJob` 은 Job 이 `COMPLETED` 든 `FAILED` 든 **항상** 호출됩니다. 그래서 알림을 보내기 좋은 자리입니다.
> 그런데 여기서 흔한 실수가 나옵니다.
> ```java
> public void afterJob(JobExecution jobExecution) {
>     slackNotifier.send("정산 배치가 완료되었습니다");   // ← 실패해도 이 메시지가 갑니다
> }
> ```
> **반드시 `jobExecution.getStatus()` 를 분기하십시오.**
> ```java
> if (jobExecution.getStatus() == BatchStatus.FAILED) {
>     slackNotifier.sendAlert("정산 배치 실패: " + jobExecution.getAllFailureExceptions());
> }
> ```
> "배치 완료" 알림이 매일 잘 오길래 안심하고 있었는데 알고 보니 3일째 실패 중이었다 — 실제로 자주 일어나는 일입니다.

---

## 12-3. StepExecutionListener — ExitStatus 를 바꿀 수 있는 자리

`afterStep` 만 **리턴 타입이 있습니다.** `ExitStatus` 를 반환해 [Step 10](../step-10-flow-control/) 의 흐름 분기에 개입할 수 있습니다.

```java
public class SettlementStepListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info(">>> Step 시작: {}", stepExecution.getStepName());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long written = stepExecution.getWriteCount();
        log.info(">>> Step 종료: read={}, write={}, filter={}, skip={}",
                stepExecution.getReadCount(), written,
                stepExecution.getFilterCount(), stepExecution.getSkipCount());

        if (written == 0) {
            return new ExitStatus("NOTHING_SETTLED");   // 흐름 분기용 커스텀 코드
        }
        return stepExecution.getExitStatus();           // 기존 값 유지
    }
}
```

**결과**
```
INFO 44231 --- [           main] c.e.b.step12.SettlementStepListener       : >>> Step 시작: settlementStep
INFO 44231 --- [           main] c.e.b.step12.SettlementStepListener       : >>> Step 종료: read=70000, write=69900, filter=0, skip=100
INFO 44231 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 34s 712ms
```

> ⚠️ **함정 — `afterStep` 에서 `return ExitStatus.COMPLETED` 하면 분기가 통째로 무너집니다**
> 로그만 찍으려고 만든 리스너가 습관적으로 이렇게 끝나는 경우가 많습니다.
> ```java
> public ExitStatus afterStep(StepExecution stepExecution) {
>     log.info("Step 끝");
>     return ExitStatus.COMPLETED;      // ← 위험
> }
> ```
> 이러면 **다른 리스너가 설정한 커스텀 ExitStatus 를 덮어씁니다.** `NOTHING_SETTLED` 로 분기하려던 [Step 10](../step-10-flow-control/) 의 흐름이 조용히 `COMPLETED` 경로로 흘러갑니다.
> 더 나쁜 것은, Step 이 실패했는데도 `COMPLETED` 를 반환하면 **실패가 은폐된다**는 점입니다.
> 값을 바꿀 의도가 없으면 **`return stepExecution.getExitStatus();`** 로 기존 값을 그대로 돌려주거나, 아예 `null` 을 반환하십시오(`null` 은 "변경 없음"으로 처리됩니다).

---

## 12-4. ChunkListener — 트랜잭션 경계 안쪽

```java
public class LoggingChunkListener implements ChunkListener {

    private final AtomicInteger chunkNo = new AtomicInteger(0);

    @Override
    public void beforeChunk(ChunkContext context) {
        // 이 시점에 이미 트랜잭션이 시작되어 있습니다.
    }

    @Override
    public void afterChunk(ChunkContext context) {
        int n = chunkNo.incrementAndGet();
        if (n % 10 == 0) {
            log.info(">>> 청크 {} 완료", n);
        }
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        log.warn(">>> 청크 실패. 롤백됩니다. {}",
                context.getStepContext().getStepExecution().getSummary());
    }
}
```

**결과** (정상 70청크)
```
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 10 완료
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 20 완료
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 30 완료
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 40 완료
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 50 완료
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 60 완료
INFO 44355 --- [           main] c.e.b.step12.LoggingChunkListener         : >>> 청크 70 완료
```

> ⚠️ **함정 — `afterChunk` 는 커밋 "전"입니다. 여기서 한 DB 작업은 함께 롤백됩니다**
> "청크가 성공적으로 끝났으니 진행 상황을 로그 테이블에 기록하자"는 생각으로 `afterChunk` 에서 `INSERT` 를 하면, **그 INSERT 는 청크 트랜잭션의 일부**입니다.
> 청크가 나중에 롤백되면 진행 로그도 함께 사라집니다. 정작 실패한 청크의 기록만 없어지는 셈이라, **로그를 남기려던 목적과 정반대**가 됩니다.
> 트랜잭션 밖에서 기록하려면 `REQUIRES_NEW` 로 별도 트랜잭션을 열거나, `SkipListener`(커밋 후) 또는 `afterStep` 을 쓰십시오.
>
> `afterChunkError` 도 마찬가지 주의가 필요합니다. 이 시점에는 트랜잭션이 이미 **롤백 표시**된 상태라, 여기서 DB 에 쓰려고 하면 `UnexpectedRollbackException` 이 납니다.

---

## 12-5. Item 레벨 리스너 — 성능 주의

`ItemReadListener` · `ItemProcessListener` · `ItemWriteListener` 는 **아이템마다** 호출됩니다. 70,000건이면 각각 70,000번입니다.

```java
public class ItemLevelListener
        implements ItemReadListener<Order>, ItemProcessListener<Order, Settlement> {

    @Override
    public void onReadError(Exception ex) {
        log.error("읽기 실패", ex);
    }

    @Override
    public void afterProcess(Order item, Settlement result) {
        if (result == null) {
            log.debug("필터링됨: order_id={}", item.order_id());
        }
    }

    @Override
    public void onProcessError(Order item, Exception e) {
        log.warn("처리 실패: order_id={}, {}", item.order_id(), e.getMessage());
    }
}
```

**결과**
```
WARN 44412 --- [           main] c.e.b.step12.ItemLevelListener            : 처리 실패: order_id=1000, 정산 금액이 음수입니다: order_id=1000, amount=-1.00
WARN 44412 --- [           main] c.e.b.step12.ItemLevelListener            : 처리 실패: order_id=2000, 정산 금액이 음수입니다: order_id=2000, amount=-1.00
WARN 44412 --- [           main] c.e.b.step12.ItemLevelListener            : 처리 실패: order_id=3000, 정산 금액이 음수입니다: order_id=3000, amount=-1.00
...
```

> ⚠️ **함정 — `beforeRead`/`afterRead` 에 로그를 걸면 배치가 몇 배 느려집니다**
> `afterRead` 에 `log.info()` 를 걸면 70,000줄이 찍힙니다. 로그 한 줄에 0.3ms 만 잡아도 **21초**가 추가됩니다. 6.1초짜리 배치가 27초가 됩니다.
> 파일 I/O 와 문자열 포매팅이 실제 정산 작업보다 오래 걸리는 상황입니다.
>
> 실측 비교입니다.
>
> | 구성 | 소요 | 배수 |
> |---|---|---|
> | 리스너 없음 | 6.108초 | 1.00배 |
> | `afterRead` 에 `log.debug` (레벨 INFO 라 출력 안 됨) | 6.402초 | 1.05배 |
> | `afterRead` 에 `log.info` | 27.310초 | **4.47배** |
> | `afterRead` 에 `log.info` + 문자열 concat | 31.884초 | 5.22배 |
>
> `log.debug` 는 레벨이 꺼져 있으면 거의 공짜입니다(5% 오버헤드). 문제는 **레벨을 켜는 순간**입니다.
> Item 레벨 리스너에는 **에러 콜백(`onReadError`, `onProcessError`, `onWriteError`)만** 쓰는 것을 기본으로 하십시오. 정상 경로에는 걸지 마십시오.

---

## 12-6. SkipListener — 커밋 후에 호출된다

`SkipListener` 는 특별합니다. **청크 트랜잭션이 커밋된 뒤에** 호출됩니다.

```java
public class SettlementSkipListener implements SkipListener<Order, Settlement> {

    @Override
    public void onSkipInProcess(Order item, Throwable t) {
        log.warn("[SKIP-PROCESS] order_id={}, 사유={}", item.order_id(), t.getMessage());
        // 이 시점은 청크 트랜잭션 "밖"입니다.
        badOrderRepository.record(item.order_id(), t.getMessage());
    }

    @Override
    public void onSkipInWrite(Settlement item, Throwable t) {
        log.warn("[SKIP-WRITE] order_id={}, 사유={}", item.orderId(), t.getMessage());
    }

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("[SKIP-READ] {}", t.getMessage());
    }
}
```

**결과**
```
WARN 44528 --- [           main] c.e.b.step12.SettlementSkipListener       : [SKIP-PROCESS] order_id=1000, 사유=정산 금액이 음수입니다: order_id=1000, amount=-1.00
WARN 44528 --- [           main] c.e.b.step12.SettlementSkipListener       : [SKIP-PROCESS] order_id=2000, 사유=정산 금액이 음수입니다: order_id=2000, amount=-1.00
...
INFO 44528 --- [           main] c.e.b.step12.SettlementStepListener       : >>> Step 종료: read=70000, write=69900, filter=0, skip=100
```

**이 "커밋 후" 라는 성질이 결정적으로 유용합니다.** 불량 데이터 기록은 **청크가 롤백되어도 남아야** 하기 때문입니다.

```sql
SELECT COUNT(*) AS recorded FROM s12_bad_order;
```

**결과**
```
+----------+
| recorded |
+----------+
|      100 |
+----------+
```

100건이 온전히 남았습니다. 만약 이 기록을 `ItemProcessListener.onProcessError` 에서 했다면 어떻게 될까요? 그건 **트랜잭션 안**이라 청크와 함께 롤백되어 사라집니다.

> 💡 **실무 팁 — 불량 데이터 기록은 반드시 `SkipListener` 에서**
> "왜 skip 됐는지"를 남기지 않으면 다음 날 아침에 조사할 방법이 없습니다. `BATCH_STEP_EXECUTION.WRITE_SKIP_COUNT` 는 **개수만** 알려 줄 뿐 어떤 주문이 왜 빠졌는지는 모릅니다.
> `SkipListener` 에서 별도 테이블(`s12_bad_order`)에 `order_id`, 예외 메시지, 발생 시각을 남기십시오. 이게 있으면 재처리 배치를 짤 수 있습니다.

> ⚠️ **함정 — `SkipListener` 는 재시도가 끝난 "최종" skip 에만 호출됩니다**
> `.retry()` 와 함께 쓰면 중간 재시도 실패는 `SkipListener` 를 부르지 않습니다. 3번 재시도 후에도 실패해서 최종적으로 skip 이 확정될 때 **한 번만** 호출됩니다.
> 재시도 횟수를 세고 싶으면 `RetryListener` 를 따로 쓰십시오.

---

## 12-7. 애너테이션 방식 — 그리고 조용히 등록되지 않는 함정

인터페이스를 구현하는 대신 애너테이션을 붙일 수 있습니다.

```java
public class AnnotatedListener {

    @BeforeStep
    public void before(StepExecution stepExecution) {
        log.info(">>> [애너테이션] Step 시작");
    }

    @AfterStep
    public ExitStatus after(StepExecution stepExecution) {
        log.info(">>> [애너테이션] Step 종료");
        return stepExecution.getExitStatus();
    }

    @AfterChunk
    public void afterChunk(ChunkContext context) {
        // ...
    }
}
```

두 방식을 비교합니다.

| | 인터페이스 방식 | 애너테이션 방식 |
|---|---|---|
| 등록 | `.listener(StepExecutionListener)` | `.listener(Object)` |
| 컴파일 체크 | **있음** — 시그니처가 틀리면 컴파일 에러 | **없음** — 메서드명·인자 자유 |
| 여러 레벨 혼합 | 인터페이스를 여러 개 구현 | 한 클래스에 애너테이션 여러 개 |
| 오타 시 | 컴파일 실패 | **조용히 무시** |

> ⚠️ **함정 — 애너테이션 리스너를 잘못된 오버로드로 등록하면 아무 일도 안 일어납니다**
> `StepBuilder.listener(...)` 에는 오버로드가 여러 개 있습니다.
> ```java
> .listener(StepExecutionListener listener)     // 인터페이스용
> .listener(ChunkListener listener)             // 인터페이스용
> .listener(ItemReadListener<T> listener)       // 인터페이스용
> .listener(Object listener)                    // ← 애너테이션용
> ```
> 애너테이션만 붙은 클래스는 **어떤 리스너 인터페이스도 구현하지 않으므로** `Object` 오버로드로 잡혀야 정상 동작합니다. 여기까지는 문제없습니다.
>
> 진짜 함정은 **메서드 시그니처가 틀렸을 때**입니다.
> ```java
> @BeforeStep
> public void before() { ... }              // ← StepExecution 인자가 없음
> ```
> 이건 컴파일도 되고 등록도 되지만 **호출되지 않습니다.** 에러도 경고도 없습니다.
> ```java
> @AfterChunk
> public void afterChunk(StepExecution se) { ... }   // ← ChunkContext 여야 함
> ```
> 이것도 조용히 무시됩니다.
>
> **확인 방법**: 리스너 안에 `log.info` 를 하나 넣고 **실제로 찍히는지 눈으로 보십시오.** "코드를 썼으니 돌겠지"라고 가정하지 마십시오. 이 코스가 계속 말하는 그 문제입니다.
>
> 애매하면 **인터페이스 방식을 쓰십시오.** 컴파일러가 잡아 줍니다.

---

## 12-8. 리스너에서 예외를 던지면 — 리스너마다 다르다

**이 절이 이 스텝의 핵심입니다.** 직관과 다른 결과가 여럿 나옵니다.

각 리스너에서 `throw new RuntimeException("리스너 폭발")` 을 하고 결과를 관찰합니다.

### 실측 결과

| 리스너 | 예외를 던지면 | Job 최종 상태 | 비고 |
|---|---|---|---|
| `beforeJob` | Job 이 즉시 실패 | `FAILED` | Step 이 아예 시작 안 됨 |
| `afterJob` | **삼켜짐** | **`COMPLETED`** | ⚠️ 로그에도 안 남는 경우가 있음 |
| `beforeStep` | Step 실패 → Job 실패 | `FAILED` | |
| `afterStep` | Step 실패 → Job 실패 | `FAILED` | 데이터는 이미 커밋됨 |
| `beforeChunk` | 청크 실패 → Step 실패 | `FAILED` | |
| `afterChunk` | 청크 실패, **커밋은 이미 됨** | `FAILED` | ⚠️ 데이터는 남고 Step 만 실패 |
| `afterChunkError` | 원래 예외를 가림 | `FAILED` | ⚠️ 진짜 원인을 잃음 |
| `ItemReadListener.afterRead` | 읽기 실패로 처리 | `FAILED` | skip 대상이면 skip |
| `SkipListener.onSkip*` | **삼켜짐** | 영향 없음 | 로그만 남음 |

가장 위험한 두 개를 자세히 봅니다.

### `afterJob` 의 예외는 삼켜집니다

```java
@Override
public void afterJob(JobExecution jobExecution) {
    throw new RuntimeException("알림 서버 접속 실패");
}
```

**결과**
```
INFO 44712 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 155ms
ERROR 44712 --- [           main] o.s.b.core.job.AbstractJob                : Exception encountered in afterStep callback
java.lang.RuntimeException: 알림 서버 접속 실패
	at com.example.batch.step12.BrokenJobListener.afterJob(BrokenJobListener.java:22) ~[main/:na]
	at org.springframework.batch.core.listener.CompositeJobExecutionListener.afterJob(CompositeJobExecutionListener.java:60) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...
INFO 44712 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 6s 203ms
```

**`ERROR` 로그는 찍히지만 Job 은 `COMPLETED` 입니다.**

이게 왜 위험한가 하면, `afterJob` 이 알림을 보내는 자리이기 때문입니다.

```java
public void afterJob(JobExecution jobExecution) {
    if (jobExecution.getStatus() == BatchStatus.FAILED) {
        slackNotifier.sendAlert("정산 배치 실패!");    // ← 여기서 네트워크 예외가 나면
    }
}
```

배치가 실패했고, 알림을 보내려 했고, 알림 전송이 실패했습니다. 그런데 그 예외는 삼켜집니다. **결과: 아무도 배치 실패를 모릅니다.**

> 💡 **실무 팁 — `afterJob` 안의 외부 호출은 반드시 try-catch 로 감싸고, 실패를 별도 경로로 남기십시오**
> ```java
> public void afterJob(JobExecution jobExecution) {
>     try {
>         notifyIfFailed(jobExecution);
>     } catch (Exception e) {
>         log.error("알림 전송 실패 — 배치 상태={}", jobExecution.getStatus(), e);
>         // 최소한 로그에는 배치 상태를 함께 남깁니다.
>     }
> }
> ```
> 알림 채널이 죽었을 때를 대비해 **알림을 두 경로로** 두는 것이 정석입니다(슬랙 + 메트릭). [Step 14](../step-14-operations/) 에서 Micrometer 로 두 번째 경로를 만듭니다.

### `afterChunk` 의 예외는 "데이터는 남고 Step 만 실패"시킵니다

`afterChunk` 는 커밋 직전이라고 12-4 에서 말했습니다. 정확히는 **커밋 직전에 호출되지만, 예외가 나면 이미 진행된 작업의 처리가 애매해집니다.**

```java
@Override
public void afterChunk(ChunkContext context) {
    if (chunkNo.incrementAndGet() == 35) {
        throw new RuntimeException("35번째 청크에서 리스너 폭발");
    }
}
```

**결과**
```
ERROR 44823 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step settlementStep in job settlementJob
java.lang.RuntimeException: 35번째 청크에서 리스너 폭발
	at com.example.batch.step12.BrokenChunkListener.afterChunk(BrokenChunkListener.java:31) ~[main/:na]
	at org.springframework.batch.core.listener.CompositeChunkListener.afterChunk(CompositeChunkListener.java:83) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.ChunkOrientedTasklet.execute(ChunkOrientedTasklet.java:79) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...
```

```sql
SELECT COUNT(*) FROM settlement;
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
```

**결과**
```
+----------+
| COUNT(*) |
+----------+
|    34000 |
+----------+

+-----------------+--------+------------+-------------+--------------+----------------+
| STEP_NAME       | STATUS | READ_COUNT | WRITE_COUNT | COMMIT_COUNT | ROLLBACK_COUNT |
+-----------------+--------+------------+-------------+--------------+----------------+
| settlementStep  | FAILED |      35000 |       34000 |           34 |              1 |
+-----------------+--------+------------+-------------+--------------+----------------+
```

34청크(34,000건)는 정상 커밋됐고, 35번째 청크는 롤백됐습니다. Step 은 `FAILED` 입니다.

**핵심**: 리스너는 "부가 기능"처럼 보이지만 **실행 흐름의 일부**입니다. 리스너가 죽으면 배치가 죽습니다.

> ⚠️ **함정 — 리스너는 "안전한 곁다리"가 아닙니다**
> "로그만 찍는 거니까 대충 짜도 되겠지"라는 생각이 사고를 만듭니다.
> ```java
> @AfterChunk
> public void afterChunk(ChunkContext context) {
>     String customerName = customerCache.get(currentId).getName();   // NPE 가능
>     log.info("처리 완료: {}", customerName.toUpperCase());
> }
> ```
> 캐시 미스 하나로 `NullPointerException` 이 나면 **정산 배치 전체가 멈춥니다.**
> 리스너 안의 코드는 방어적으로 쓰고, 부가 기능은 `try-catch` 로 감싸십시오. 특히 **외부 시스템 호출(알림, 메트릭 전송)은 절대 예외를 밖으로 흘리지 마십시오.**

---

## 12-9. 리스너 실행 순서

같은 종류의 리스너를 여러 개 등록하면 **등록 순서대로** 호출됩니다.

```java
return new StepBuilder("settlementStep", jobRepository)
        .<Order, Settlement>chunk(1000, txManager)
        .reader(...).processor(...).writer(...)
        .listener(new FirstListener())
        .listener(new SecondListener())
        .build();
```

**결과**
```
INFO 44912 --- [           main] c.e.b.step12.FirstListener                : [1] beforeStep
INFO 44912 --- [           main] c.e.b.step12.SecondListener               : [2] beforeStep
INFO 44912 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 6s 118ms
INFO 44912 --- [           main] c.e.b.step12.FirstListener                : [1] afterStep
INFO 44912 --- [           main] c.e.b.step12.SecondListener               : [2] afterStep
```

`before` 는 등록 순, `after` 도 **등록 순**입니다.

> ⚠️ **함정 — `after` 계열이 역순일 거라고 가정하지 마십시오**
> 필터 체인이나 인터셉터에 익숙하면 "before 는 정순, after 는 역순"을 기대하게 됩니다. `CompositeStepExecutionListener` 는 **양쪽 다 정순**입니다.
> 리스너 A 가 연 자원을 리스너 B 가 닫는 식의 의존 관계를 만들면 순서가 어긋납니다. **리스너끼리 의존하게 만들지 마십시오.**

순서를 명시하고 싶으면 `Ordered` 인터페이스나 `@Order` 를 씁니다.

```java
public class FirstListener implements StepExecutionListener, Ordered {
    @Override
    public int getOrder() { return 1; }
}
```

---

## 12-10. 리스너를 언제 쓰고 언제 쓰지 말아야 하는가

| 목적 | 리스너 | 대안 |
|---|---|---|
| Job 시작/종료 알림 | `JobExecutionListener` | — |
| 실행 시간 측정 | `StepExecutionListener` | Micrometer ([Step 14](../step-14-operations/)) |
| 불량 데이터 기록 | **`SkipListener`** | — (이게 정석) |
| 진행률 로깅 | `ChunkListener` (N개마다) | — |
| 데이터 변환 | ❌ | `ItemProcessor` |
| 데이터 검증 | ❌ | `ItemProcessor` / `Validator` |
| 아이템별 로깅 | ❌ (너무 느림) | 에러 콜백만 |
| 집계·통계 | ❌ (상태를 가짐) | `afterStep` 에서 SQL 로 |

> 💡 **실무 팁 — 리스너에 비즈니스 로직을 넣지 마십시오**
> "정산이 끝나면 포인트도 적립해야 하니까 `afterStep` 에서 하자"는 유혹이 있습니다. 하지 마십시오.
> - `afterStep` 은 트랜잭션 밖이라 실패해도 롤백되지 않습니다.
> - 재시작하면 이미 성공한 Step 은 건너뛰므로 **포인트 적립이 누락**됩니다.
> - 테스트하기 어렵습니다.
>
> 별도 Step 으로 만드십시오. 그러면 재시작·모니터링·트랜잭션이 전부 프레임워크의 보호를 받습니다.
> **리스너는 "관찰"하는 자리이지 "일하는" 자리가 아닙니다.**

---

## 정리

| 개념 | 핵심 |
|---|---|
| 리스너 레벨 | Job → Step → Chunk → Item → Skip 5단계 |
| `beforeJob`/`afterJob` | `afterJob` 은 **실패해도 호출**. 상태 분기 필수 |
| `afterStep` | 유일하게 **리턴값이 있음**. `ExitStatus` 로 흐름 분기 개입 |
| `afterStep` 함정 | `return ExitStatus.COMPLETED` 가 다른 분기를 덮어씀. `getExitStatus()` 를 반환할 것 |
| `afterChunk` | **커밋 전**. 여기서 쓴 데이터는 롤백되면 함께 사라짐 |
| `SkipListener` | **커밋 후**. 불량 데이터 기록의 정석 자리 |
| Item 레벨 리스너 | 아이템마다 호출. `log.info` 를 걸면 **약 4.5배 느려짐** |
| 애너테이션 방식 | 시그니처가 틀려도 **컴파일되고 조용히 무시됨**. 인터페이스가 안전 |
| 리스너 예외 | `afterJob` 과 `SkipListener` 는 **삼켜짐**, 나머지는 Step/Job 을 실패시킴 |
| 실행 순서 | before·after **둘 다 등록 순**. 역순 아님 |
| 원칙 | 리스너는 **관찰**하는 자리. 비즈니스 로직은 별도 Step 으로 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **로그가 실제로 찍히는지 눈으로 확인**하세요.

1. 실패한 Job 에만 알림을 보내는 `JobExecutionListener` 작성하기
2. 애너테이션 리스너의 시그니처를 틀리게 만들어 "조용히 무시"되는 것을 재현하기
3. 불량 주문을 별도 테이블에 기록하는 `SkipListener` 작성하고, `onProcessError` 로 했을 때와 비교하기
4. `afterChunk` 에서 예외를 던져 "데이터는 남고 Step 만 실패"하는 것을 확인하기
5. 리스너 세 개를 등록해 실행 순서를 관찰하고, `Ordered` 로 순서를 뒤집기
6. Item 레벨 리스너의 성능 영향을 실측하고, 안전한 로깅 전략 설계하기

---

## 다음 단계

리스너로 배치의 안팎을 관찰할 수 있게 됐습니다. 그런데 지금까지의 모든 실습은 **단일 스레드**였습니다. 70,000건에 6초면 괜찮지만, 700만 건이면 10분입니다.
다음 스텝에서 배치를 병렬화합니다. 그리고 그 과정에서 이 코스에서 가장 위험한 함정 — **3.3배 빨라졌는데 787건이 조용히 사라지는** 상황 — 을 만납니다.

→ [Step 13 — 병렬 처리와 확장](../step-13-scaling/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 따라가며 12-1 ~ 12-10 의 리스너들을 하나씩 붙여 로그를 관찰하고, `Exercise.java` 의 6문제를 직접 채운 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step12` 패키지이며, 설정 클래스들을 `static class` 로 중첩해 두었습니다.

이 스텝의 실습에는 다른 스텝과 다른 원칙이 하나 있습니다. **"코드를 썼으니 돌겠지"를 절대 가정하지 마십시오.** 리스너는 등록에 실패해도, 시그니처가 틀려도 조용합니다. 매번 로그가 실제로 찍히는지 눈으로 확인하는 습관이 이 스텝의 진짜 학습 목표입니다.

### Practice.java

본문의 모든 리스너를 절 번호 주석과 함께 담았습니다.

- **`[12-0]`** 의 `PlantBadData` 는 Step 11 과 같은 불량 데이터를 심습니다. **이것을 먼저 실행하지 않으면 `SkipListener` 가 한 번도 호출되지 않아** 12-6 이 통째로 빈 로그가 됩니다. 그리고 그게 "리스너가 안 붙었나?" 하는 착각을 부릅니다.
- **`[12-5]`** 의 `ItemLevelListener` 는 `log.info` 가 **주석 처리된 채로** 들어 있습니다. 주석을 풀면 27초짜리 배치가 됩니다. 성능 실측(6.108 → 27.310초)을 재현할 때만 켜고, 그 외에는 꺼 두십시오.
- **`[12-8]`** 의 `Broken*Listener` 네 개는 **일부러 예외를 던지는 코드**입니다. `BrokenJobListener`(afterJob), `BrokenStepListener`(afterStep), `BrokenChunkListener`(afterChunk, 35번째), `BrokenSkipListener`(onSkipInProcess) 를 **하나씩만** 활성화해 표의 아홉 줄을 직접 채워 보십시오. 네 개를 동시에 켜면 어느 것 때문에 죽었는지 알 수 없습니다.
- `BrokenChunkListener` 를 돌린 뒤에는 `settlement` 에 34,000행이 **남아 있습니다.** 다음 측정 전에 반드시 `TRUNCATE TABLE settlement` 하십시오. 이 스텝에서 가장 흔한 실습 실수입니다.
- **`[12-7]`** 의 `AnnotatedListener` 에는 정상 버전과 **시그니처가 틀린 버전**(`BrokenAnnotatedListener`)이 나란히 있습니다. 둘 다 컴파일되고 둘 다 예외 없이 실행되며, 차이는 **로그가 찍히느냐 아니냐**뿐입니다. 이 대조가 12-7 함정의 전부입니다.
- 파일 하단 `CleanUp` 에 `s12_bad_order` 테이블 DDL 과 `orders` 복구 SQL 이 있습니다. **이 스텝을 끝내면 반드시 복구 SQL 을 실행하십시오.** `orders` 는 Step 13·14 가 공유합니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·3·5** 는 리스너를 **작성**하는 문제, **문제 2·4·6** 은 동작을 **관찰하고 측정**하는 문제입니다.
- **문제 2 가 이 스텝의 핵심입니다.** 애너테이션 메서드의 인자를 일부러 틀리게 만들고, 컴파일도 되고 실행도 되는데 **로그만 안 찍히는** 상황을 재현합니다. 여기서 "어떻게 알아차릴 것인가"를 스스로 답해 보는 것이 문제의 목적입니다.
- 문제 3 은 같은 기록 로직을 `SkipListener.onSkipInProcess` 와 `ItemProcessListener.onProcessError` 두 곳에 각각 넣고 결과를 비교합니다. **기록된 행 수가 100 vs 0 으로 갈립니다.** 후자는 청크와 함께 롤백되기 때문입니다. 트랜잭션 경계를 몸으로 이해하는 문제입니다.
- 문제 4 는 실행 후 `settlement` 행 수와 `BATCH_STEP_EXECUTION` 카운터를 **둘 다** 확인해야 합니다. 한쪽만 보면 "데이터는 남았는데 왜 실패지?"에서 멈춥니다.
- 문제 6 은 측정 문제입니다. 표의 네 가지 구성을 직접 돌려 시간을 재고, 그 결과로 "그럼 실무에서는 어떻게 로깅할 것인가"까지 설계하는 것이 마무리입니다.
- 각 문제 끝에 `-- 검증:` SQL 이 붙어 있습니다. **리스너는 조용히 실패하므로, 로그 육안 확인 + SQL 검증을 둘 다 하십시오.**

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `getStatus() == BatchStatus.FAILED` 분기와 함께, `getAllFailureExceptions()` 로 실패 원인을 알림에 담는 방법을 보여 줍니다. 그리고 알림 전송 자체를 `try-catch` 로 감싸야 하는 이유 — `afterJob` 의 예외는 삼켜져서 **알림 실패를 아무도 모른다** — 를 12-8 과 연결해 설명합니다.
- **정답 2** 는 세 가지 "조용한 실패" 패턴을 정리합니다. ① 인자 누락 ② 인자 타입 불일치 ③ 애너테이션 오타(`@BeforeStep` 대신 `@Before`). 셋 다 컴파일되고 셋 다 무시됩니다. 결론은 **"애매하면 인터페이스를 구현하라"** 이고, 근거로 컴파일러가 잡아 주는 범위를 표로 비교합니다.
- **정답 3** 이 가장 중요합니다. `SkipListener` 는 100건을 기록하고 `onProcessError` 는 **0건**을 기록합니다. 후자가 청크 트랜잭션 안이라 롤백되기 때문입니다. 여기서 "그럼 `onProcessError` 는 언제 쓰나?"에 대한 답 — **롤백돼도 상관없는 것, 즉 로그 출력에만** — 까지 정리합니다.
- **정답 4** 는 `settlement` 34,000행 + `STATUS=FAILED` + `COMMIT_COUNT=34` + `ROLLBACK_COUNT=1` 의 조합을 해석합니다. "데이터가 남았는데 실패"가 모순이 아니라 **청크 단위 커밋의 당연한 귀결**임을 설명하고, 재시작하면 34,000건 다음부터 이어진다는 것까지 Step 11 과 연결합니다.
- **정답 5** 는 등록 순서가 before·after **양쪽 다 정순**임을 로그로 보여 준 뒤, `Ordered` 를 구현해 뒤집습니다. 그리고 "애초에 리스너끼리 순서에 의존하게 만들지 말라"는 결론을 답니다. 순서를 제어할 수 있다는 것과 순서에 의존해도 된다는 것은 다릅니다.
- **정답 6** 의 결론은 세 줄입니다. ① 정상 경로에는 리스너를 걸지 않는다 ② 에러 콜백만 쓴다 ③ 진행률이 필요하면 `ChunkListener` 에서 N개마다 찍는다(70,000번이 아니라 70번). `log.debug` 가 꺼져 있을 때 5% 오버헤드인 것과 켰을 때 4.5배인 것의 차이를 근거로, **"로그 레벨을 올리는 순간 배치가 죽는다"** 는 운영상의 함정까지 짚습니다.

```java file="./Solution.java"
```
