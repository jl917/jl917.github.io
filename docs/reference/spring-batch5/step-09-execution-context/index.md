# Step 09 — ExecutionContext 와 스코프

> **학습 목표**
> - Job ExecutionContext 와 Step ExecutionContext 가 **서로 보이지 않는 별개의 저장소**임을 실측으로 확인한다
> - `ExecutionContextPromotionListener` 로 Step → Job 승격을 하고, **승격이 일어나는 정확한 시점**을 로그로 짚는다
> - `BATCH_*_EXECUTION_CONTEXT` 의 `SHORT_CONTEXT` / `SERIALIZED_CONTEXT` 를 직접 SELECT 해서 저장된 JSON 을 눈으로 본다
> - `@StepScope` / `@JobScope` 의 늦은 바인딩(late binding)과 `#{jobParameters[...]}` · `#{stepExecutionContext[...]}` 를 쓴다
> - **`@StepScope` 를 빼먹었을 때 나는 `EL1008E` 에러를 그대로 재현**하고, 왜 기동 시점에 터지는지 설명한다
> - 프록시 모드(`ScopedProxyMode.TARGET_CLASS`) 때문에 **리턴 타입을 인터페이스로 쓰면 ItemStream 콜백이 조용히 사라지는 것**을 재현한다
>
> **선행 스텝**: Step 08 — ItemWriter
> **예상 소요**: 100분

---

Step 05 ~ Step 08 에서 청크 하나가 어떻게 읽히고 가공되고 쓰이는지를 봤습니다.
이번 스텝은 **그 사이에 남는 "상태"** 를 다룹니다. 배치가 재시작될 수 있는 이유, 그리고 실행할 때마다 달라지는 파라미터를 빈이 어떻게 받아 쓰는지가 전부 여기에 있습니다.

---

## 9-1. ExecutionContext 는 "다음 실행에게 남기는 메모"입니다

`ExecutionContext` 는 겉보기에 그냥 `Map<String, Object>` 입니다. 실제 내부도 `ConcurrentHashMap` 하나입니다.

```java
ExecutionContext ctx = stepExecution.getExecutionContext();
ctx.putLong("lastOrderId", 41000L);
ctx.putString("mode", "BULK");
long v = ctx.getLong("lastOrderId");
```

평범한 Map 과 다른 점은 딱 하나, **JobRepository 가 이걸 DB 에 저장한다**는 것입니다. 그래서 이 Map 은 프로세스가 죽어도 살아남습니다.

```
  [ 1차 실행 ]                            [ 2차 실행 (재시작) ]
  Step 실행 중 ────────────┐              ┌──────────► Step 이 이어서 시작
    ctx.putLong(           │              │             ctx.getLong("lastOrderId") == 41000
      "lastOrderId",41000) │              │
                           ▼              │
              BATCH_STEP_EXECUTION_CONTEXT│
              SHORT_CONTEXT = {"lastOrderId":41000}
                           └──────────────┘
```

Spring Batch 가 **여러분 대신** 이 메모를 쓰는 경우도 있습니다. `ItemStream` 을 구현한 Reader/Writer 들이 그렇습니다. 예를 들어 `JdbcPagingItemReader` 는 매 청크 커밋마다 자기가 몇 번째 아이템까지 읽었는지를 `JdbcPagingItemReader.read.count` 키로 적어 둡니다. 재시작하면 그 숫자만큼 건너뛰고 시작합니다.

| 구분 | 저장 위치 | 수명 | 누가 씁니까 |
|---|---|---|---|
| Step ExecutionContext | `BATCH_STEP_EXECUTION_CONTEXT` | 그 **StepExecution** 한 번 | ItemStream 구현체, 사용자 |
| Job ExecutionContext | `BATCH_JOB_EXECUTION_CONTEXT` | 그 **JobExecution** 한 번 | 사용자, PromotionListener |

> 💡 **실무 팁 — ExecutionContext 에는 작은 값만 넣으세요**
> 이 Map 은 **직렬화되어 한 컬럼에 통째로 들어갑니다.** 여기에 10만 건짜리 리스트를 넣으면 매 청크 커밋마다 그 리스트를 JSON 으로 직렬화해서 UPDATE 합니다.
> 넣어도 되는 것: 커서 위치, 마지막 ID, 파일 경로, 집계 결과 몇 개. 넣으면 안 되는 것: 엔티티 컬렉션, 대용량 문자열, DB 커넥션 같은 비직렬화 객체.

---

## 9-2. Job Context 와 Step Context 는 서로 안 보입니다

가장 많이 하는 착각이 이것입니다. **Step 에서 넣은 값은 Job 에서 안 보이고, 그 반대도 마찬가지입니다.**

```java
// [9-2] Step 에서 값을 넣고, 다음 Step 에서 읽어 보려는 시도
@Bean
public Tasklet writerTasklet() {
    return (contribution, chunkContext) -> {
        ExecutionContext stepCtx = chunkContext.getStepContext()
                .getStepExecution().getExecutionContext();
        stepCtx.putLong("completedCount", 70000L);
        log.info("[writeStep] stepContext 에 completedCount=70000 저장");
        return RepeatStatus.FINISHED;
    };
}

@Bean
public Tasklet readerTasklet() {
    return (contribution, chunkContext) -> {
        ExecutionContext stepCtx = chunkContext.getStepContext()
                .getStepExecution().getExecutionContext();
        ExecutionContext jobCtx = chunkContext.getStepContext()
                .getStepExecution().getJobExecution().getExecutionContext();
        log.info("[readStep] stepContext.completedCount = {}", stepCtx.get("completedCount"));
        log.info("[readStep] jobContext.completedCount  = {}", jobCtx.get("completedCount"));
        return RepeatStatus.FINISHED;
    };
}
```

**결과**
```
INFO 44012 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=contextScopeJob]] launched with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}]
INFO 44012 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [writeStep]
INFO 44012 --- [           main] c.e.b.step09.ContextScopeConfig          : [writeStep] stepContext 에 completedCount=70000 저장
INFO 44012 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [writeStep] executed in 21ms
INFO 44012 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [readStep]
INFO 44012 --- [           main] c.e.b.step09.ContextScopeConfig          : [readStep] stepContext.completedCount = null
INFO 44012 --- [           main] c.e.b.step09.ContextScopeConfig          : [readStep] jobContext.completedCount  = null
INFO 44012 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [readStep] executed in 9ms
INFO 44012 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=contextScopeJob]] completed with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 118ms
```

**둘 다 `null` 입니다.** 관계를 그림으로 정리합니다.

```
                     JobExecution
                ┌──────────────────────────────┐
                │ Job ExecutionContext          │  ← 모든 Step 이 함께 봅니다
                │  { }                          │
                └───────┬──────────────┬────────┘
                        │              │
            ┌───────────▼───┐   ┌──────▼────────┐
            │ writeStep     │   │ readStep      │
            │ StepExecution │   │ StepExecution │
            │ Step Ctx      │   │ Step Ctx      │   ← 서로 완전히 별개
            │ {completed=   │   │ { }           │
            │   70000}      │   │               │
            └───────────────┘   └───────────────┘
                    ✗ 여기로 자동으로 흐르지 않습니다 ✗
```

Job Context 는 **모든 Step 이 공유**하지만, Step Context 는 **StepExecution 마다 하나씩** 새로 만들어집니다. 값을 다음 Step 에 넘기려면 Job Context 로 **올려야(promote)** 합니다.

---

## 9-3. ExecutionContextPromotionListener — Step → Job 승격

승격은 리스너 한 줄로 됩니다.

```java
// [9-3] 승격 리스너
@Bean
public ExecutionContextPromotionListener promotionListener() {
    ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
    listener.setKeys(new String[]{"completedCount", "settleDate"});  // 올릴 키 목록
    listener.setStatuses(new String[]{"COMPLETED"});                 // 기본값. 이 ExitStatus 일 때만 승격
    listener.setStrict(false);                                       // 키가 없어도 예외 안 냄
    return listener;
}

@Bean
public Step writeStep(JobRepository jobRepository, PlatformTransactionManager tm) {
    return new StepBuilder("writeStep", jobRepository)
            .tasklet(writerTasklet(), tm)
            .listener(promotionListener())      // ← Step 에 붙입니다. Job 이 아닙니다.
            .build();
}
```

읽는 쪽을 Job Context 로 바꿉니다.

```java
ExecutionContext jobCtx = chunkContext.getStepContext()
        .getStepExecution().getJobExecution().getExecutionContext();
log.info("[readStep] jobContext.completedCount  = {}", jobCtx.get("completedCount"));
```

**결과**
```
INFO 44119 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [writeStep]
INFO 44119 --- [           main] c.e.b.step09.PromotionConfig             : [writeStep] stepContext 에 completedCount=70000 저장
INFO 44119 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [writeStep] executed in 24ms
INFO 44119 --- [           main] o.s.b.c.l.ExecutionContextPromotionListener: Promoting keys [completedCount, settleDate] from step [writeStep] to job execution context
INFO 44119 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [readStep]
INFO 44119 --- [           main] c.e.b.step09.PromotionConfig             : [readStep] stepContext.completedCount = null
INFO 44119 --- [           main] c.e.b.step09.PromotionConfig             : [readStep] jobContext.completedCount  = 70000
```

`stepContext` 는 여전히 `null` 이고, `jobContext` 에만 값이 있습니다. 승격은 **복사**이지 **이동**이 아니며, 복사본이 들어가는 곳은 Job Context 입니다.

### 승격이 일어나는 정확한 시점

`ExecutionContextPromotionListener` 는 `StepExecutionListener#afterStep` 구현체입니다. 즉:

```
Step 본문 실행 (청크 반복)
   └─ 마지막 청크 커밋
        └─ AbstractStep.close()
             └─ afterStep 콜백  ← 여기서 승격
                  └─ JobRepository.updateExecutionContext(jobExecution)
                       └─ 다음 Step 시작
```

**"Step 이 끝나야" 승격됩니다.** Step 중간에는 Job Context 가 갱신되지 않습니다. 그래서 같은 Step 안에서 "내가 방금 넣은 값을 Job Context 로 읽는" 코드는 언제나 `null` 입니다.

> ⚠️ **함정 — `setStatuses` 기본값이 `COMPLETED` 라 실패한 Step 은 승격되지 않습니다**
> 이 리스너는 기본적으로 **ExitStatus 가 `COMPLETED` 일 때만** 승격합니다. Step 이 `FAILED` 로 끝나면 아무것도 올라가지 않습니다.
> 문제는 여기서 **예외가 나지 않는다**는 점입니다. 다음 Step 은 `jobContext.get("completedCount")` 로 조용히 `null` 을 받고, 그걸 그대로 `long` 으로 언박싱하면 그제서야 엉뚱한 자리에서 `NullPointerException` 이 납니다.
> 부분 실패에서도 값이 필요하다면 `listener.setStatuses(new String[]{"COMPLETED", "FAILED"})` 로 명시하거나, 읽는 쪽에서 `ctx.containsKey(...)` 로 방어하세요.
>
> 반대편 함정도 있습니다. `setStrict(true)` 로 두면 지정한 키가 Step Context 에 **없을 때 `IllegalArgumentException`** 을 던집니다. 조건부로만 넣는 키에는 절대 `strict=true` 를 쓰면 안 됩니다.

---

## 9-4. DB 에 실제로 어떻게 저장되는지 봅니다

메타데이터 테이블을 직접 엽니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT
FROM BATCH_JOB_EXECUTION_CONTEXT ORDER BY JOB_EXECUTION_ID DESC LIMIT 1\G"
```

**결과**
```
*************************** 1. row ***************************
  JOB_EXECUTION_ID: 12
     SHORT_CONTEXT: {"@class":"java.util.HashMap","completedCount":["java.lang.Long",70000],"settleDate":"2025-03-01"}
SERIALIZED_CONTEXT: NULL
```

Step 쪽도 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT s.STEP_NAME, c.SHORT_CONTEXT
FROM BATCH_STEP_EXECUTION s
JOIN BATCH_STEP_EXECUTION_CONTEXT c ON c.STEP_EXECUTION_ID = s.STEP_EXECUTION_ID
WHERE s.JOB_EXECUTION_ID = 12 ORDER BY s.STEP_EXECUTION_ID;"
```

**결과**
```
+-----------+-----------------------------------------------------------------------------------------------------------------------------+
| STEP_NAME | SHORT_CONTEXT                                                                                                               |
+-----------+-----------------------------------------------------------------------------------------------------------------------------+
| writeStep | {"@class":"java.util.HashMap","batch.taskletType":"com.example.batch.step09.PromotionConfig$$Lambda$1234/0x000000080123abcd","batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep","completedCount":["java.lang.Long",70000],"settleDate":"2025-03-01"} |
| readStep  | {"@class":"java.util.HashMap","batch.taskletType":"com.example.batch.step09.PromotionConfig$$Lambda$1235/0x000000080123abce","batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep"}                                                                       |
+-----------+-----------------------------------------------------------------------------------------------------------------------------+
```

읽을 수 있는 것이 세 가지 있습니다.

1. **JSON 입니다.** Spring Batch 5 의 기본 직렬화기는 `Jackson2ExecutionContextStringSerializer` 입니다. 4.x 의 자바 직렬화(BLOB) 가 아닙니다.
2. **`@class` 와 `["java.lang.Long",70000]` 같은 타입 정보가 함께 저장됩니다.** Jackson 의 default typing 이 켜져 있어서, 역직렬화할 때 `Long` 을 `Integer` 로 잘못 복원하는 일이 없습니다.
3. **`batch.taskletType` / `batch.stepType` 은 프레임워크가 넣은 것입니다.** 재시작 시 "이전과 같은 종류의 Step 인가"를 검증하는 데 씁니다. 지우면 안 됩니다.

> ⚠️ **함정 — `SERIALIZED_CONTEXT` 가 NULL 이라고 저장이 안 된 게 아닙니다**
> 이 테이블에는 컨텍스트 컬럼이 **두 개** 있습니다.
>
> | 컬럼 | 타입 | 언제 채워집니까 |
> |---|---|---|
> | `SHORT_CONTEXT` | `VARCHAR(2500)` | **항상.** 2,500자를 넘으면 앞부분만 잘려 들어갑니다 |
> | `SERIALIZED_CONTEXT` | `TEXT` | 직렬화 결과가 **2,500자를 넘을 때만** |
>
> 즉 작은 컨텍스트는 `SHORT_CONTEXT` 에 전문이 들어가고 `SERIALIZED_CONTEXT` 는 NULL 입니다. **읽을 때 프레임워크는 `SERIALIZED_CONTEXT` 가 NULL 이면 `SHORT_CONTEXT` 를 씁니다.**
> 운영 중 컨텍스트를 눈으로 확인할 때 `SERIALIZED_CONTEXT` 만 보고 "비어 있네" 하고 넘어가는 실수가 흔합니다. 항상 `COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT)` 로 보세요.
>
> ```sql
> SELECT JOB_EXECUTION_ID, COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) AS ctx
> FROM BATCH_JOB_EXECUTION_CONTEXT ORDER BY JOB_EXECUTION_ID DESC LIMIT 3;
> ```
>
> 그리고 **`SHORT_CONTEXT` 가 잘린다는 사실 자체가 경고**입니다. 2,500자를 넘겼다면 컨텍스트에 넣지 말아야 할 것을 넣고 있을 가능성이 높습니다.

---

## 9-5. ExecutionContext 는 재시작의 근거입니다

Step 08 까지 만든 정산 Step 에 `JdbcPagingItemReader` 가 있었습니다. 이 Reader 가 컨텍스트에 무엇을 남기는지 봅니다. 70,000건 중 **41,000건째에서 일부러 실패**시켜 봅니다.

```java
// [9-5] 41,000번째 아이템에서 터지는 Processor
@Bean
public ItemProcessor<Order, Settlement> boobyTrapProcessor() {
    AtomicInteger seen = new AtomicInteger();
    return order -> {
        if (seen.incrementAndGet() == 41_000) {
            throw new IllegalStateException("의도적 실패 — orderId=" + order.order_id());
        }
        return toSettlement(order);
    };
}
```

**결과**
```
INFO 44230 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
ERROR 44230 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step settleStep in job restartDemoJob

java.lang.IllegalStateException: 의도적 실패 — orderId=58572
	at com.example.batch.step09.RestartConfig.lambda$boobyTrapProcessor$2(RestartConfig.java:88) ~[main/:na]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.doProcess(SimpleChunkProcessor.java:127) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.transform(SimpleChunkProcessor.java:302) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.process(SimpleChunkProcessor.java:210) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.ChunkOrientedTasklet.execute(ChunkOrientedTasklet.java:75) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.tasklet.TaskletStep$ChunkTransactionCallback.doInTransaction(TaskletStep.java:407) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...
INFO 44230 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 3s842ms
INFO 44230 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=restartDemoJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [FAILED] in 3s991ms
```

이제 컨텍스트를 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT,
       COALESCE(c.SERIALIZED_CONTEXT, c.SHORT_CONTEXT) AS ctx
FROM BATCH_STEP_EXECUTION s JOIN BATCH_STEP_EXECUTION_CONTEXT c USING (STEP_EXECUTION_ID)
ORDER BY s.STEP_EXECUTION_ID DESC LIMIT 1\G"
```

**결과**
```
*************************** 1. row ***************************
  STEP_NAME: settleStep
     STATUS: FAILED
 READ_COUNT: 41000
WRITE_COUNT: 40000
COMMIT_COUNT: 40
        ctx: {"@class":"java.util.HashMap","batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep","JdbcPagingItemReader.read.count":40000,"batch.taskletType":"org.springframework.batch.core.step.item.ChunkOrientedTasklet"}
```

`READ_COUNT` 는 41,000인데 컨텍스트의 `JdbcPagingItemReader.read.count` 는 **40,000** 입니다. 이 차이가 핵심입니다.

- 41,000건째를 읽긴 했지만 그 청크(40,001~41,000)는 **롤백**되었습니다.
- 컨텍스트는 **커밋된 트랜잭션 안에서만** 갱신됩니다. 그래서 마지막으로 성공한 40청크 = 40,000건이 기록됩니다.
- `READ_COUNT`/`WRITE_COUNT` 는 실패한 시도까지 반영된 통계값이라 컨텍스트와 다릅니다. **재시작이 신뢰하는 것은 컨텍스트 쪽**입니다.

같은 파라미터로 다시 실행합니다.

**결과**
```
INFO 44311 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
DEBUG 44311 --- [           main] o.s.b.c.s.i.ChunkOrientedTasklet          : Inputs not busy, ended: false
INFO 44311 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 2s417ms
INFO 44311 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=restartDemoJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 2s508ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT FROM BATCH_STEP_EXECUTION
ORDER BY STEP_EXECUTION_ID DESC LIMIT 2;"
```

**결과**
```
+------------+-----------+------------+-------------+
| STEP_NAME  | STATUS    | READ_COUNT | WRITE_COUNT |
+------------+-----------+------------+-------------+
| settleStep | COMPLETED |      30000 |       30000 |
| settleStep | FAILED    |      41000 |       40000 |
+------------+-----------+------------+-------------+
```

2차 실행의 `READ_COUNT` 는 **30,000** 입니다. 70,000 − 40,000 = 30,000. **컨텍스트에 남아 있던 40,000을 건너뛰고 이어서 처리한 것입니다.**

> 💡 **실무 팁 — 재시작을 원한다면 Reader 를 컨텍스트에 등록해야 합니다**
> 위가 동작한 이유는 `JdbcPagingItemReader` 가 `ItemStream` 이고, `SimpleStepBuilder.reader()` 가 그것을 자동으로 `registerStream()` 했기 때문입니다.
> **이 등록이 깨지는 경우가 있습니다.** 바로 다음 9-8 의 프록시 함정입니다.

---

## 9-6. `@StepScope` — 늦은 바인딩

Job 파라미터는 **실행할 때** 정해집니다. 그런데 스프링 빈은 **애플리케이션이 뜰 때** 만들어집니다. 이 시차가 문제입니다.

```
애플리케이션 기동                          Job 실행
  ────────────────────────────────►  ──────────────────────────────►
  싱글턴 빈 생성                         jobParameters 확정
  (아직 jobParameters 가 없음)             ↑
                                          이때 빈을 만들면 파라미터를 쓸 수 있다
```

`@StepScope` 는 "**이 빈은 Step 이 실제로 시작될 때 만들어라**"는 지시입니다. 그때는 파라미터가 이미 있으므로 SpEL 로 꺼내 쓸 수 있습니다. 이것을 **늦은 바인딩(late binding)** 이라고 합니다.

```java
// [9-6] StepScope + jobParameters 바인딩
@Bean
@StepScope
public JdbcPagingItemReader<Order> dailyOrderReader(
        DataSource dataSource,
        @Value("#{jobParameters['date']}") String date) {

    MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
    provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
    provider.setFromClause("FROM orders");
    provider.setWhereClause("WHERE status = 'COMPLETED' AND DATE(ordered_at) = :date");
    provider.setSortKeys(Map.of("order_id", Order.ASCENDING));

    return new JdbcPagingItemReaderBuilder<Order>()
            .name("dailyOrderReader")
            .dataSource(dataSource)
            .queryProvider(provider)
            .parameterValues(Map.of("date", date))
            .pageSize(1000)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=dailySettleJob date=2025-03-01'
```

**결과**
```
INFO 44402 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=dailySettleJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 44402 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [dailySettleStep]
DEBUG 44402 --- [           main] c.e.batch.step09.ScopeConfig             : dailyOrderReader 생성 — date=2025-03-01
INFO 44402 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [dailySettleStep] executed in 341ms
INFO 44402 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=dailySettleJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 452ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, READ_COUNT, WRITE_COUNT FROM BATCH_STEP_EXECUTION
ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+-----------------+------------+-------------+
| STEP_NAME       | READ_COUNT | WRITE_COUNT |
+-----------------+------------+-------------+
| dailySettleStep |        389 |         389 |
+-----------------+------------+-------------+
```

**389건.** 2025-03-01 하루치 COMPLETED 주문 수와 정확히 일치합니다(하루 약 555건 × 70%).

### 쓸 수 있는 SpEL 루트 객체

| 표현식 | 스코프 | 무엇을 줍니까 |
|---|---|---|
| `#{jobParameters['date']}` | `@JobScope`, `@StepScope` | 실행 파라미터 |
| `#{jobExecutionContext['completedCount']}` | `@JobScope`, `@StepScope` | Job Context (= 승격된 값) |
| `#{stepExecutionContext['minId']}` | `@StepScope` **만** | Step Context (파티셔닝에서 주로) |
| `#{jobExecutionContext}` / `#{stepExecution}` | 각각 | 객체 자체 |

```java
// [9-6] 승격된 값을 다음 Step 의 빈이 받아 쓰기
@Bean
@StepScope
public Tasklet reportTasklet(
        @Value("#{jobExecutionContext['completedCount']}") Long completedCount) {
    return (contribution, chunkContext) -> {
        log.info("정산 대상 {}건에 대한 리포트 생성", completedCount);
        return RepeatStatus.FINISHED;
    };
}
```

**결과**
```
INFO 44455 --- [           main] o.s.b.c.l.ExecutionContextPromotionListener: Promoting keys [completedCount] from step [settleStep] to job execution context
INFO 44455 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [reportStep]
INFO 44455 --- [           main] c.e.batch.step09.ScopeConfig             : 정산 대상 70000건에 대한 리포트 생성
```

`#{stepExecutionContext['minId']}` 는 Step 13 의 파티셔닝에서 각 파티션이 자기 구간을 받는 데 쓰입니다. 미리 형태만 봅니다.

```java
@Bean
@StepScope
public JdbcPagingItemReader<Order> partitionedReader(
        @Value("#{stepExecutionContext['minId']}") Long minId,
        @Value("#{stepExecutionContext['maxId']}") Long maxId) { ... }
```

> 💡 **실무 팁 — 파라미터 타입은 SpEL 이 아니라 파라미터 선언이 정합니다**
> `#{jobParameters['date']}` 의 결과는 `JobParameter` 가 담고 있던 타입 그대로입니다. 커맨드라인 `date=2025-03-01` 은 `String` 이고, `date(date)=2025/03/01` 은 `java.util.Date` 입니다.
> `@Value("#{jobParameters['date']}") LocalDate date` 처럼 선언하면 스프링의 `ConversionService` 가 변환을 시도하고, 실패하면 `ConversionFailedException` 이 납니다. **문자열로 받고 코드에서 `LocalDate.parse()` 하는 편이 예측 가능합니다.**

---

## 9-7. `@StepScope` 를 빼먹으면 — 기동 시점에 터집니다

이 스텝에서 가장 자주 만나는 실패입니다. 위 코드에서 `@StepScope` 만 지웁니다.

```java
// [9-7] 잘못된 코드 — @StepScope 가 없습니다
@Bean
// @StepScope   ← 지웠습니다
public JdbcPagingItemReader<Order> brokenReader(
        DataSource dataSource,
        @Value("#{jobParameters['date']}") String date) {
    ...
}
```

**결과**
```
ERROR 44510 --- [           main] o.s.boot.SpringApplication               : Application run failed

org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'brokenSettleStep' defined in class path resource [com/example/batch/step09/BrokenScopeConfig.class]: Unsatisfied dependency expressed through method 'brokenSettleStep' parameter 2: Error creating bean with name 'brokenReader' defined in class path resource [com/example/batch/step09/BrokenScopeConfig.class]: Unsatisfied dependency expressed through method 'brokenReader' parameter 1: Expression parsing failed
	at org.springframework.beans.factory.support.ConstructorResolver.resolveAutowiredArgument(ConstructorResolver.java:912) ~[spring-beans-6.1.6.jar:6.1.6]
	at org.springframework.beans.factory.support.ConstructorResolver.createArgumentArray(ConstructorResolver.java:790) ~[spring-beans-6.1.6.jar:6.1.6]
	...
Caused by: org.springframework.beans.factory.BeanExpressionException: Expression parsing failed
	at org.springframework.context.expression.StandardBeanExpressionResolver.evaluate(StandardBeanExpressionResolver.java:170) ~[spring-context-6.1.6.jar:6.1.6]
	at org.springframework.beans.factory.support.AbstractBeanFactory.evaluateBeanDefinitionString(AbstractBeanFactory.java:1584) ~[spring-beans-6.1.6.jar:6.1.6]
	... 61 common frames omitted
Caused by: org.springframework.expression.spel.SpelEvaluationException: EL1008E: Property or field 'jobParameters' cannot be found on object of type 'org.springframework.beans.factory.config.BeanExpressionContext' - maybe not public or not valid?
	at org.springframework.expression.spel.ast.PropertyOrFieldReference.readProperty(PropertyOrFieldReference.java:217) ~[spring-expression-6.1.6.jar:6.1.6]
	at org.springframework.expression.spel.ast.PropertyOrFieldReference.getValueInternal(PropertyOrFieldReference.java:104) ~[spring-expression-6.1.6.jar:6.1.6]
	at org.springframework.expression.spel.ast.CompoundExpression.getValueRef(CompoundExpression.java:78) ~[spring-expression-6.1.6.jar:6.1.6]
	... 66 common frames omitted
```

**`EL1008E: Property or field 'jobParameters' cannot be found`** — 이 문장을 기억하세요. 원인은 하나뿐입니다.

`jobParameters` 는 `StepScope`(또는 `JobScope`) 가 등록한 SpEL 컨텍스트에만 존재합니다. `@StepScope` 가 없으면 그 빈은 **싱글턴**이라 애플리케이션 기동 중에 만들어지고, 그 시점의 SpEL 루트는 `BeanExpressionContext` 입니다. 거기에는 `jobParameters` 라는 것이 없습니다.

| 상황 | 결과 |
|---|---|
| `@StepScope` + `#{jobParameters[...]}` | 정상 |
| `@JobScope` + `#{jobParameters[...]}` | 정상 |
| 스코프 없음 + `#{jobParameters[...]}` | **기동 실패 `EL1008E`** |
| `@JobScope` + `#{stepExecutionContext[...]}` | **`EL1008E` (stepExecutionContext 없음)** |

> 💡 **이 에러는 오히려 착한 에러입니다**
> 애플리케이션이 **아예 안 뜨기 때문에** 100% 발견됩니다. 이 코스에서 반복하는 원칙 그대로입니다 — **조용히 틀리는 것보다 시끄럽게 실패하는 편이 낫습니다.**
> 진짜 위험한 것은 다음 절입니다. 그쪽은 에러가 안 납니다.

---

## 9-8. 프록시 함정 — 리턴 타입을 인터페이스로 쓰면 ItemStream 이 사라집니다

`@StepScope` 의 정의를 보면 이렇습니다.

```java
@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
public @interface StepScope { }
```

`proxyMode = TARGET_CLASS` 는 **CGLIB 프록시**를 만든다는 뜻입니다. Step 이 시작되기 전에는 실제 객체가 없으므로, 스프링은 껍데기(프록시)를 먼저 주입해 두고 첫 호출 때 진짜 객체를 만들어 위임합니다.

문제는 **"무엇의 프록시를 만들 것인가"를 `@Bean` 메서드의 리턴 타입으로 결정한다**는 점입니다.

```java
// [9-8] 위험한 선언 — 리턴 타입이 인터페이스
@Bean
@StepScope
public ItemReader<Order> leakyReader(DataSource ds,
        @Value("#{jobParameters['date']}") String date) {
    return new JdbcCursorItemReaderBuilder<Order>()
            .name("leakyReader")
            .dataSource(ds)
            .sql("SELECT order_id, customer_id, amount, status, ordered_at FROM orders "
               + "WHERE status='COMPLETED' AND DATE(ordered_at)=?")
            .queryArguments(date)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

리턴 타입이 `ItemReader<Order>` 이므로 프록시는 **`ItemReader` 만** 구현합니다. `ItemStream` 은 구현하지 않습니다.

```
[ 올바른 선언 ]  리턴타입 = JdbcCursorItemReader<Order>
   프록시 : JdbcCursorItemReader 를 상속 → ItemReader + ItemStream 둘 다 ✓
   SimpleStepBuilder.reader(r) { if (r instanceof ItemStream) registerStream(r); }  → 등록됨

[ 잘못된 선언 ] 리턴타입 = ItemReader<Order>
   프록시 : ItemReader 만 구현 → ItemStream 아님 ✗
   SimpleStepBuilder.reader(r) { if (r instanceof ItemStream) ... }  → 건너뜀
        └─ open() / update() / close() 가 한 번도 호출되지 않음
```

`JdbcCursorItemReader` 는 `open()` 에서 `ResultSet` 을 여는데, 그게 호출되지 않으니 첫 `read()` 에서 터집니다.

**결과**
```
INFO 44601 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [leakyStep]
ERROR 44601 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step leakyStep in job leakyJob

org.springframework.batch.item.ReaderNotOpenException: Reader must be open before it can be read.
	at org.springframework.batch.item.database.AbstractCursorItemReader.doRead(AbstractCursorItemReader.java:435) ~[spring-batch-infrastructure-5.1.1.jar:5.1.1]
	at org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader.read(AbstractItemCountingItemStreamItemReader.java:93) ~[spring-batch-infrastructure-5.1.1.jar:5.1.1]
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103) ~[na:na]
	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:351) ~[spring-aop-6.1.6.jar:6.1.6]
	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:720) ~[spring-aop-6.1.6.jar:6.1.6]
	at org.springframework.batch.core.step.item.SimpleChunkProvider.doRead(SimpleChunkProvider.java:99) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProvider.read(SimpleChunkProvider.java:92) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...
INFO 44601 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=leakyJob]] completed with the following parameters: [{...}] and the following status: [FAILED] in 213ms
```

고치는 방법은 **리턴 타입을 구현체로 바꾸는 것 하나**입니다.

```java
// [9-8] 올바른 선언
@Bean
@StepScope
public JdbcCursorItemReader<Order> safeReader(...) { ... }   // ← 구현체 타입
```

**결과**
```
INFO 44655 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [safeStep]
INFO 44655 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [safeStep] executed in 297ms
INFO 44655 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=safeJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 388ms
```

> ⚠️ **함정 — `JdbcPagingItemReader` 로 같은 실수를 하면 에러가 안 납니다. 재시작만 조용히 망가집니다.**
> 위 예는 `JdbcCursorItemReader` 라서 `ReaderNotOpenException` 으로 시끄럽게 죽었습니다. **운이 좋았던 것입니다.**
>
> `JdbcPagingItemReader` 는 `open()` 없이도 첫 페이지를 그냥 조회해 옵니다. 즉 **정상적으로 70,000건을 전부 처리하고 COMPLETED 로 끝납니다.** 아무 에러도 없습니다.
> 사라진 것은 `update()` 입니다. 매 청크 커밋마다 `JdbcPagingItemReader.read.count` 를 적어 두는 그 콜백입니다.
>
> ```sql
> SELECT COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) FROM BATCH_STEP_EXECUTION_CONTEXT
> ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
> ```
> ```
> +-----------------------------------------------------------------------------------------------+
> | {"@class":"java.util.HashMap","batch.stepType":"...TaskletStep","batch.taskletType":"...ChunkOrientedTasklet"} |
> +-----------------------------------------------------------------------------------------------+
> ```
> `read.count` 키가 **없습니다.** 평소에는 아무 문제가 없다가, **장애가 나서 재시작하는 날** 처음부터 다시 읽습니다. 이미 정산된 4만 건을 다시 INSERT 하려다 `settlement.uk_settlement_order` 에 걸려 `DuplicateKeyException` 이 나거나, UNIQUE 가 없었다면 **정산이 두 배로 찍힙니다.**
>
> 대응은 세 가지입니다.
> 1. **`@Bean` 리턴 타입은 항상 구현체로.** `ItemReader<T>` / `ItemWriter<T>` 로 선언하지 마세요.
> 2. 부득이하게 인터페이스로 노출해야 하면 `.stream(reader)` 로 **명시 등록**합니다.
>    ```java
>    new StepBuilder("settleStep", jobRepository)
>        .<Order, Settlement>chunk(1000, tm)
>        .reader(reader).processor(p).writer(w)
>        .stream(reader)          // ← 명시적으로 ItemStream 등록
>        .build();
>    ```
>    단, 이때 `reader` 의 컴파일 타임 타입도 `ItemStream` 이어야 하므로 결국 캐스팅이 필요합니다.
> 3. 배포 전에 **컨텍스트에 `read.count` 가 남는지 SQL 로 확인**하는 것을 체크리스트에 넣으세요. 이 한 줄이 재시작 사고를 막습니다.

---

## 9-9. `@JobScope` 와, 스코프 빈이 몇 번 만들어지는지

`@JobScope` 는 **JobExecution 하나당 한 번** 만들어집니다. `@StepScope` 는 **StepExecution 하나당 한 번**입니다.

```java
// [9-9] 생성 횟수를 세는 스코프 빈
private final AtomicInteger stepScopedCount = new AtomicInteger();
private final AtomicInteger jobScopedCount  = new AtomicInteger();

@Bean
@StepScope
public StepScopedProbe stepProbe(@Value("#{stepExecution.stepName}") String stepName) {
    log.info(">>> @StepScope 빈 생성 #{} (step={})", stepScopedCount.incrementAndGet(), stepName);
    return new StepScopedProbe(stepName);
}

@Bean
@JobScope
public JobScopedProbe jobProbe(@Value("#{jobExecution.jobInstance.jobName}") String jobName) {
    log.info(">>> @JobScope  빈 생성 #{} (job={})", jobScopedCount.incrementAndGet(), jobName);
    return new JobScopedProbe(jobName);
}
```

Step 을 세 개 두고 실행합니다.

**결과**
```
INFO 44710 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=scopeProbeJob]] launched with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}]
INFO 44710 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [probeStepA]
INFO 44710 --- [           main] c.e.batch.step09.ProbeConfig             : >>> @JobScope  빈 생성 #1 (job=scopeProbeJob)
INFO 44710 --- [           main] c.e.batch.step09.ProbeConfig             : >>> @StepScope 빈 생성 #1 (step=probeStepA)
INFO 44710 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [probeStepA] executed in 14ms
INFO 44710 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [probeStepB]
INFO 44710 --- [           main] c.e.batch.step09.ProbeConfig             : >>> @StepScope 빈 생성 #2 (step=probeStepB)
INFO 44710 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [probeStepB] executed in 8ms
INFO 44710 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [probeStepC]
INFO 44710 --- [           main] c.e.batch.step09.ProbeConfig             : >>> @StepScope 빈 생성 #3 (step=probeStepC)
INFO 44710 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [probeStepC] executed in 7ms
INFO 44710 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=scopeProbeJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 141ms
```

**`@StepScope` 빈은 3번, `@JobScope` 빈은 1번** 만들어졌습니다. 그리고 생성 시점이 애플리케이션 기동 시가 아니라 **Step 이 실제로 시작된 뒤**임에 주목하세요 — `Executing step: [probeStepA]` 로그 **다음에** 생성 로그가 찍힙니다.

| 스코프 | 생성 단위 | 소멸 시점 | `jobParameters` | `stepExecutionContext` |
|---|---|---|---|---|
| 싱글턴 | 애플리케이션 1회 | 컨텍스트 종료 | ✗ `EL1008E` | ✗ |
| `@JobScope` | JobExecution 마다 | Job 종료 | ✓ | ✗ |
| `@StepScope` | StepExecution 마다 | Step 종료 | ✓ | ✓ |

> ⚠️ **함정 — `@StepScope` 빈에 상태를 두면 재시작 때 사라집니다**
> "매 StepExecution 마다 새로 만들어진다"는 것은 곧 **누적한 카운터가 재시작 후 0부터 시작한다**는 뜻입니다.
> 9-5 의 예에서 `AtomicInteger seen` 을 `@StepScope` 빈의 필드에 뒀다면, 재시작한 2차 실행에서 그 값은 다시 0입니다. 실제로 처리를 이어받은 건 30,000건인데 카운터는 1부터 셉니다.
> **누적해야 하는 값은 빈 필드가 아니라 ExecutionContext 에 두세요.** 그래야 DB 에 남고, 재시작 때 복원됩니다.

---

## 9-10. 이 스텝 전체를 하나로 — 승격 + 스코프

정산 Job 을 세 Step 으로 나눠, 앞 Step 의 결과를 뒤 Step 이 스코프 빈으로 받는 형태를 만듭니다.

```
  ┌─────────────────┐   promote        ┌────────────────────┐   promote   ┌──────────────┐
  │ prepareStep     │  completedCount  │ settleStep         │  feeTotal   │ reportStep   │
  │ (Tasklet)       │  settleDate      │ (chunk 1000)       │             │ (Tasklet)    │
  │ COUNT(*) 조회   │ ───────────────► │ #{jobParameters}   │ ──────────► │ #{jobExec... │
  │ stepCtx 에 저장 │                  │ #{jobExecutionCtx} │             │  Context}    │
  └─────────────────┘                  └────────────────────┘             └──────────────┘
             │                                    │                              │
             └──── ExecutionContextPromotionListener 를 각 Step 에 부착 ─────────┘
```

**결과**
```
INFO 44802 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=step09Job]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 44802 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [prepareStep]
INFO 44802 --- [           main] c.e.batch.step09.Step09Job               : [prepare] date=2025-03-01, COMPLETED 건수=389
INFO 44802 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [prepareStep] executed in 47ms
INFO 44802 --- [           main] o.s.b.c.l.ExecutionContextPromotionListener: Promoting keys [completedCount, settleDate] from step [prepareStep] to job execution context
INFO 44802 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
DEBUG 44802 --- [           main] c.e.batch.step09.Step09Job               : settleReader 생성 — date=2025-03-01, 예상 건수=389
INFO 44802 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 402ms
INFO 44802 --- [           main] o.s.b.c.l.ExecutionContextPromotionListener: Promoting keys [feeTotal] from step [settleStep] to job execution context
INFO 44802 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [reportStep]
INFO 44802 --- [           main] c.e.batch.step09.Step09Job               : [report] 2025-03-01 정산 389건, 수수료 합계 = 484670.00
INFO 44802 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [reportStep] executed in 11ms
INFO 44802 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=step09Job]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 611ms
```

마지막으로 Job Context 를 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) AS job_context
FROM BATCH_JOB_EXECUTION_CONTEXT ORDER BY JOB_EXECUTION_ID DESC LIMIT 1\G"
```

**결과**
```
*************************** 1. row ***************************
job_context: {"@class":"java.util.HashMap","completedCount":["java.lang.Long",389],"settleDate":"2025-03-01","feeTotal":["java.math.BigDecimal",484670.00]}
```

세 Step 이 남긴 값이 하나의 Job Context 에 모였습니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| ExecutionContext | JobRepository 가 DB 에 저장하는 `Map`. 재시작의 근거 |
| Job vs Step Context | **서로 안 보입니다.** Step 것은 StepExecution 마다 새로 생김 |
| 승격 | `ExecutionContextPromotionListener.setKeys(...)` 를 **Step 에** 부착 |
| 승격 시점 | `afterStep` — Step 이 **끝난 뒤**. Step 중간에는 안 보임 |
| `setStatuses` | 기본 `COMPLETED` 만. 실패 Step 은 승격 안 됨 (조용히 `null`) |
| `SHORT_CONTEXT` | `VARCHAR(2500)`, **항상** 채워짐. 초과분만 `SERIALIZED_CONTEXT` |
| 조회 요령 | `COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT)` |
| 직렬화 | Spring Batch 5 기본은 **JSON** (`Jackson2ExecutionContextStringSerializer`) |
| 재시작 기준 | `READ_COUNT` 가 아니라 컨텍스트의 `...read.count`(커밋된 값) |
| `@StepScope` | StepExecution 마다 생성. `jobParameters` · `stepExecutionContext` 사용 가능 |
| `@JobScope` | JobExecution 마다 생성. `stepExecutionContext` 는 **불가** |
| 스코프 누락 | 기동 시 `SpelEvaluationException: EL1008E ... 'jobParameters'` |
| 프록시 모드 | `ScopedProxyMode.TARGET_CLASS` — **리턴 타입이 프록시 타입을 결정** |
| 인터페이스 리턴 함정 | `ItemStream` 미구현 → `open`/`update`/`close` 누락 → **재시작 조용히 파손** |
| 상태 보관 위치 | 스코프 빈 필드 ✗ / ExecutionContext ✓ |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. Step 에서 계산한 값을 다음 Step 이 읽도록 승격 리스너를 구성하기
2. `@StepScope` 를 붙여 `#{jobParameters['date']}` 를 받는 Reader 완성하기
3. `EL1008E` 가 나는 코드를 보고 원인을 진단하고 고치기
4. `BATCH_*_EXECUTION_CONTEXT` 를 조회해 저장된 JSON 을 꺼내는 SQL 작성하기
5. 인터페이스 리턴 타입으로 선언된 Reader 의 위험을 설명하고 두 가지 방법으로 고치기
6. `@JobScope` 와 `@StepScope` 중 어느 것을 써야 하는지 세 가지 상황에서 판단하기

---

## 다음 단계

지금까지의 Job 은 Step 을 **일렬로** 이어 붙인 것뿐이었습니다. 하지만 실무의 정산 배치는 "주문 건수가 많으면 벌크 방식으로, 적으면 일반 방식으로", "집계와 리포트는 동시에", "실패하면 복구 Step 으로" 같은 분기가 필요합니다.

다음 스텝은 그 흐름 제어를 다룹니다. 그리고 그 중심에는 **`on()` 이 매칭하는 것이 BatchStatus 가 아니라 ExitStatus 라는 사실**, 그리고 이번 스텝에서 배운 **ExecutionContext 를 `JobExecutionDecider` 가 읽어 분기 판단에 쓴다는 점**이 있습니다.

→ [Step 10 — 흐름 제어](../step-10-flow-control/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 의 중첩 클래스를 위에서부터 읽으며 9-2 ~ 9-10 의 모든 실행 결과를 재현하고, `Exercise.java` 의 6문제를 직접 채운 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 패키지는 `com.example.batch.step09` 이며, 하나의 파일 안에 `static class` 로 설정 클래스를 중첩해 두었습니다. 실제 프로젝트에 넣을 때는 각 중첩 클래스를 별도 `@Configuration` 파일로 떼어 내도 그대로 동작합니다.

### Practice.java

본문의 모든 예제를 절 번호 주석(`// [9-2]` 형태)과 함께 담은 실습 파일입니다.

- **`ContextScopeConfig`(9-2)** 는 승격 없이 Step Context 에만 값을 넣고 다음 Step 에서 읽어 `null` 을 확인하는, **일부러 실패하는 예제**입니다. 이걸 먼저 돌려야 9-3 의 승격이 왜 필요한지가 몸에 남습니다.
- **`BrokenScopeConfig`(9-7)** 와 **`LeakyProxyConfig`(9-8)** 는 **컴파일은 되지만 실행하면 실패하도록 의도된 코드**입니다. 두 클래스에는 `@Configuration` 이 주석 처리되어 있어 기본 상태에서는 컨텍스트에 올라가지 않습니다. 재현하려면 주석을 풀고 실행하세요. **풀어 놓은 채로 다른 절을 실행하면 애플리케이션이 아예 뜨지 않습니다**(9-7 은 기동 실패이므로 다른 모든 실습을 막습니다).
- **`RestartConfig`(9-5)** 의 `boobyTrapProcessor` 는 41,000번째 아이템에서 예외를 던집니다. 1차 실행은 반드시 `FAILED` 로 끝나야 정상이고, **같은 `date` 파라미터로 한 번 더 실행**해야 2차의 `READ_COUNT=30000` 을 볼 수 있습니다. 파라미터를 바꾸면 새 JobInstance 가 되어 처음부터 70,000건을 읽습니다.
- **`ProbeConfig`(9-9)** 는 `AtomicInteger` 두 개로 스코프 빈 생성 횟수를 셉니다. 이 카운터는 **싱글턴 설정 클래스의 필드**라 애플리케이션 단위로 누적됩니다. 한 번의 실행에서 `@StepScope` 3회 / `@JobScope` 1회가 찍히는지 확인하세요.
- **`Step09Job`(9-10)** 이 이 스텝의 종합 예제입니다. `prepareStep` → `settleStep` → `reportStep` 을 승격으로 잇고, 실행 전 `TRUNCATE TABLE settlement` 를 권합니다(`uk_settlement_order` 때문에 재실행 시 중복 키가 납니다).
- 파일 하단의 `CONTEXT_INSPECT_SQL` 상수에 9-4 · 9-5 · 9-8 에서 쓴 조회 SQL 세 개를 모아 두었습니다. 복사해서 mysql CLI 에 붙여 넣으면 됩니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·2·5** 는 코드를 채우는 문제이고, **문제 3·6** 은 주어진 코드를 진단하는 문제, **문제 4** 는 SQL 을 쓰는 문제입니다.
- 문제 3 의 `Q3BrokenConfig` 는 **컴파일은 됩니다.** 실행해야 `EL1008E` 를 볼 수 있으므로, 진단만 하지 말고 실제로 기동시켜 스택트레이스를 확인하세요. 원인 문장을 스스로 찾아내는 것이 이 문제의 목적입니다.
- 문제 5 의 `Q5LeakyReader` 는 리턴 타입이 `ItemReader<Order>` 로 되어 있고 내부는 `JdbcPagingItemReader` 입니다. **9-8 의 함정 중 "에러가 안 나는" 쪽**이라, 실행하면 그냥 성공합니다. 성공했다고 넘어가면 문제를 틀린 것입니다 — 컨텍스트에 `read.count` 가 남았는지 SQL 로 확인해야 합니다.
- 문제 6 은 코드 없이 판단만 요구합니다. 세 시나리오(파일 경로 파라미터 / 파티션 구간 / Job 단위 통계 수집기)에 대해 스코프를 고르고 이유를 주석으로 적으세요.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `setKeys` 뿐 아니라 **`setStatuses` 를 명시하는 것**까지가 답입니다. 기본값이 `COMPLETED` 뿐이라는 사실과, 부분 실패 시나리오에서 그것이 어떻게 조용한 `null` 로 이어지는지를 주석으로 풉니다.
- **정답 3** 의 핵심은 "`jobParameters` 는 스코프가 등록하는 SpEL 루트에만 있다" 한 문장입니다. `@StepScope` 를 붙이는 것 외에, `@JobScope` 로도 되는 경우와 안 되는 경우를 표로 정리했습니다.
- **정답 5** 는 두 가지 해법을 모두 제시합니다. ① 리턴 타입을 `JdbcPagingItemReader<Order>` 로 바꾸기(권장), ② `.stream(...)` 으로 명시 등록하기. 그리고 **②가 왜 차선책인지** — 등록을 잊기 쉽고, 컴파일 타임 타입 캐스팅이 필요해 결국 구현체 타입을 알아야 한다 — 를 설명합니다.
- **정답 6** 의 판단 기준은 한 줄로 요약됩니다: **`stepExecutionContext` 가 필요하면 `@StepScope`, Job 전체에서 하나만 있어야 하면 `@JobScope`, 실행 시점 값이 전혀 필요 없으면 싱글턴.** 세 번째 시나리오(통계 수집기)에서 `@StepScope` 를 고르면 Step 마다 초기화되어 집계가 날아간다는 것이 함정입니다.

```java file="./Solution.java"
```
