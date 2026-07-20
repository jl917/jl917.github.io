# Step 10 — 흐름 제어

> **학습 목표**
> - `.on(...).to(...)` / `.from(...).on(...).to(...)` 로 Step 전이 그래프를 구성한다
> - **`on()` 이 매칭하는 것은 `BatchStatus` 가 아니라 `ExitStatus` 라는 사실**을 실측으로 확인한다
> - `*` / `?` 와일드카드와 **가장 구체적인 패턴이 이기는** 매칭 우선순위를 검증한다
> - `.end()` / `.fail()` / `.stopAndRestart()` 가 Job 의 최종 상태와 재시작 지점을 어떻게 바꾸는지 구분한다
> - `JobExecutionDecider` 로 주문 건수에 따라 정산 방식을 분기시킨다
> - `.split(new SimpleAsyncTaskExecutor())` 로 두 흐름을 병렬 실행하고, 로그의 스레드 이름으로 확인한다
> - **`.on("FAILED").to(recoveryStep).end()` 가 실패를 은폐해 Job 을 COMPLETED 로 만드는 것**을 재현하고 대응한다
>
> **선행 스텝**: Step 09 — ExecutionContext 와 스코프
> **예상 소요**: 110분

---

여기까지의 Job 은 전부 직선이었습니다. `start(a).next(b).next(c)` — 앞이 성공하면 뒤가 돌고, 실패하면 거기서 끝납니다.

실무의 정산 배치는 그렇지 않습니다. "대상 건수가 많으면 벌크 방식으로", "집계와 리포트는 동시에", "실패하면 복구 Step 을 태우고 알림". 이번 스텝은 그 그래프를 만드는 법과, **그 과정에서 조용히 망가지는 지점**을 다룹니다.

---

## 10-1. 직선에서 그래프로

`JobBuilder` 는 `.next()` 를 만나는 순간 `SimpleJob` 을, `.on()` 을 만나는 순간 **`FlowJob`** 을 만듭니다. 전이 문법은 다음 세 조각뿐입니다.

```java
// [10-1] 전이의 기본 문법
@Bean
public Job flowBasicJob(JobRepository jobRepository) {
    return new JobBuilder("flowBasicJob", jobRepository)
            .start(stepA())                          // 시작점
                .on("COMPLETED").to(stepB())         // stepA 가 COMPLETED 면 stepB
            .from(stepA())
                .on("FAILED").to(stepC())            // stepA 가 FAILED 면 stepC
            .from(stepB())
                .on("*").end()                       // stepB 뒤에는 무조건 종료
            .from(stepC())
                .on("*").fail()                      // stepC 뒤에는 Job 을 실패로
            .end()                                   // ← FlowBuilder 를 닫습니다
            .build();
}
```

| 조각 | 의미 |
|---|---|
| `.start(step)` | 흐름의 시작 상태 |
| `.on("패턴")` | **직전 상태의 ExitStatus** 가 패턴에 맞으면 |
| `.to(step)` / `.to(flow)` | 그 다음으로 갈 곳 |
| `.from(step)` | "이 상태에서 나가는 전이를 추가로 정의한다" |
| `.end()` (전이) | 이 지점에서 Job 을 `COMPLETED` 로 종료 |
| `.fail()` | 이 지점에서 Job 을 `FAILED` 로 종료 |
| `.stopAndRestart(step)` | Job 을 `STOPPED` 로 멈추고, 재시작하면 지정 Step 부터 |
| `.end()` (빌더) | `FlowBuilder` 를 닫아 `JobBuilder` 로 돌아옴 |

> ⚠️ **함정 — `.end()` 가 두 가지 뜻으로 쓰입니다**
> 위 코드에 `.end()` 가 두 번 나옵니다. **완전히 다른 것입니다.**
> - `.on("*").end()` → **전이 종료.** "여기서 Job 을 COMPLETED 로 끝낸다."
> - 맨 마지막 `.end()` → **빌더 종료.** `FlowBuilder` 를 닫고 `.build()` 를 부를 수 있게 만든다.
>
> 마지막 `.end()` 를 빼먹으면 `.build()` 가 `FlowBuilder` 에 없어서 컴파일 에러가 납니다. 이건 금방 고칩니다.
> 진짜 문제는 **전이 쪽 `.end()`** 입니다. 이것 때문에 실패한 Job 이 성공으로 보고되는 사고가 납니다. 10-5 에서 정면으로 다룹니다.

---

## 10-2. `on()` 이 보는 것은 ExitStatus 입니다

이 스텝에서 딱 하나만 가져간다면 이 절입니다.

Spring Batch 에는 상태를 나타내는 값이 **두 종류** 있습니다.

| | `BatchStatus` | `ExitStatus` |
|---|---|---|
| 타입 | `enum` (고정) | **`String` 코드** (자유) |
| 값 | `STARTING` `STARTED` `STOPPING` `STOPPED` `FAILED` `COMPLETED` `ABANDONED` `UNKNOWN` | `COMPLETED` `FAILED` `NOOP` `EXECUTING` `STOPPED` **+ 여러분이 만든 값** |
| 용도 | 프레임워크가 실행 상태를 관리 | **흐름 제어의 입력값** |
| 컬럼 | `BATCH_STEP_EXECUTION.STATUS` | `BATCH_STEP_EXECUTION.EXIT_CODE` |
| `on()` 이 보는 것 | ✗ | **✓** |

`on("COMPLETED")` 는 `BatchStatus.COMPLETED` 를 보는 것이 **아닙니다.** `ExitStatus.getExitCode()` 문자열을 봅니다. 둘은 기본값이 같아서 평소에는 구분할 일이 없지만, **다르게 만들 수 있고 그 순간 흐름이 바뀝니다.**

```java
// [10-2] ExitStatus 를 직접 바꿉니다
@Bean
public Step checkStep(JobRepository jobRepository, PlatformTransactionManager tm,
                      JdbcTemplate jdbcTemplate) {
    return new StepBuilder("checkStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                String date = (String) chunkContext.getStepContext()
                        .getJobParameters().get("date");
                Long cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                        Long.class, date);
                chunkContext.getStepContext().getStepExecution()
                        .getExecutionContext().putLong("targetCount", cnt);
                return RepeatStatus.FINISHED;
            }, tm)
            .listener(new StepExecutionListener() {
                @Override
                public ExitStatus afterStep(StepExecution stepExecution) {
                    long cnt = stepExecution.getExecutionContext().getLong("targetCount", 0L);
                    // BatchStatus 는 여전히 COMPLETED 입니다. ExitStatus 만 바꿉니다.
                    return cnt == 0 ? new ExitStatus("NO_DATA") : new ExitStatus("HAS_DATA");
                }
            })
            .build();
}

@Bean
public Job exitStatusJob(JobRepository jobRepository) {
    return new JobBuilder("exitStatusJob", jobRepository)
            .start(checkStep(null, null, null))
                .on("HAS_DATA").to(settleStep(null, null))
            .from(checkStep(null, null, null))
                .on("NO_DATA").to(skipNoticeStep(null, null))
            .end()
            .build();
}
```

데이터가 있는 날로 실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=exitStatusJob date=2025-03-01'
```

**결과**
```
INFO 45011 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=exitStatusJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 45011 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [checkStep]
INFO 45011 --- [           main] c.e.batch.step10.ExitStatusConfig        : [checkStep] date=2025-03-01, targetCount=389 → ExitStatus=HAS_DATA
INFO 45011 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [checkStep] executed in 38ms
INFO 45011 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
INFO 45011 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 421ms
INFO 45011 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=exitStatusJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 583ms
```

데이터가 없는 날(시드 범위 밖)로 실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=exitStatusJob date=2025-12-25'
```

**결과**
```
INFO 45044 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [checkStep]
INFO 45044 --- [           main] c.e.batch.step10.ExitStatusConfig        : [checkStep] date=2025-12-25, targetCount=0 → ExitStatus=NO_DATA
INFO 45044 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [checkStep] executed in 21ms
INFO 45044 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [skipNoticeStep]
INFO 45044 --- [           main] c.e.batch.step10.ExitStatusConfig        : [skipNotice] 2025-12-25 정산 대상 없음. 건너뜁니다.
INFO 45044 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=exitStatusJob]] completed with the following parameters: [{'date':'{value=2025-12-25, ...}'}] and the following status: [COMPLETED] in 194ms
```

메타데이터로 두 값이 어떻게 다른지 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, EXIT_CODE FROM BATCH_STEP_EXECUTION
ORDER BY STEP_EXECUTION_ID DESC LIMIT 4;"
```

**결과**
```
+----------------+-----------+-----------+
| STEP_NAME      | STATUS    | EXIT_CODE |
+----------------+-----------+-----------+
| skipNoticeStep | COMPLETED | COMPLETED |
| checkStep      | COMPLETED | NO_DATA   |
| settleStep     | COMPLETED | COMPLETED |
| checkStep      | COMPLETED | HAS_DATA  |
+----------------+-----------+-----------+
```

**`STATUS` 는 둘 다 `COMPLETED` 인데 `EXIT_CODE` 만 다릅니다.** `on()` 은 오른쪽 컬럼을 봅니다.

> ⚠️ **함정 — `on("COMPLETED")` 가 갑자기 안 먹는 날이 옵니다**
> 잘 돌던 Job 에 `StepExecutionListener` 를 하나 붙였습니다. 로그를 좀 더 남기려고요.
> ```java
> @Override
> public ExitStatus afterStep(StepExecution stepExecution) {
>     log.info("처리 건수 = {}", stepExecution.getWriteCount());
>     return ExitStatus.COMPLETED;   // ← "성공했으니까" 라고 생각하고 넣은 한 줄
> }
> ```
> 이 리스너가 **원래의 ExitStatus 를 덮어씁니다.** 위 예의 `NO_DATA` / `HAS_DATA` 는 사라지고, 흐름은 언제나 `HAS_DATA` 쪽 전이를 못 찾아 `NO_DATA` 쪽도 못 찾고 — 매칭되는 전이가 하나도 없어 Job 이 끝나 버립니다.
>
> **`afterStep` 에서 값을 바꿀 생각이 없으면 `stepExecution.getExitStatus()` 를 그대로 돌려주거나 `null` 을 반환하세요.**
> ```java
> return stepExecution.getExitStatus();   // 또는 return null;
> ```
> 리스너 여러 개가 붙어 있으면 **뒤에 등록된 리스너의 반환값이 이깁니다.** 로그용 리스너 하나가 분기 로직 전체를 무력화할 수 있습니다. Step 12 에서 리스너 순서를 다시 다룹니다.

---

## 10-3. 와일드카드와 매칭 우선순위

패턴에는 두 가지 와일드카드를 쓸 수 있습니다.

| 기호 | 의미 | 예 |
|---|---|---|
| `*` | 0개 이상의 임의 문자 | `"COMPLETED*"` 는 `COMPLETED`, `COMPLETED_WITH_SKIPS` 둘 다 매칭 |
| `?` | 정확히 1개의 임의 문자 | `"C?MPLETED"` 는 `COMPLETED` 매칭, `CMPLETED` 는 불가 |

가장 흔한 쓰임은 `on("*")` — "무엇이든" 입니다.

```java
// [10-3] 여러 패턴이 동시에 맞을 때
new JobBuilder("wildcardJob", jobRepository)
        .start(checkStep)
            .on("COMPLETED").to(exactStep)          // (1) 정확 일치
        .from(checkStep)
            .on("COMPLETED*").to(prefixStep)        // (2) 접두 와일드카드
        .from(checkStep)
            .on("*").to(catchAllStep)               // (3) 전부
        .end()
        .build();
```

`checkStep` 의 ExitStatus 가 `COMPLETED` 라면 셋 다 매칭됩니다. 어디로 갈까요?

**결과**
```
INFO 45102 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [checkStep]
INFO 45102 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [checkStep] executed in 19ms
INFO 45102 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [exactStep]
INFO 45102 --- [           main] c.e.batch.step10.WildcardConfig          : [exactStep] 정확 일치 패턴이 선택되었습니다
INFO 45102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=wildcardJob]] completed with ... and the following status: [COMPLETED] in 141ms
```

**가장 구체적인 패턴이 이깁니다.** `SimpleFlow` 는 전이 목록을 정렬해 두고, 와일드카드가 적을수록 앞에 놓습니다.

```
ExitStatus = "COMPLETED_WITH_SKIPS" 일 때 매칭 순서

   "COMPLETED"            ← 매칭 실패 (정확히 같아야 함)
   "COMPLETED_WITH_*"     ← 매칭 성공. 여기서 멈춥니다
   "COMPLETED*"           ← (검사되지 않음)
   "*"                    ← (검사되지 않음)
   ────────────────────
   구체적 ──────────► 포괄적
```

구체성 판단은 대략 "와일드카드가 적고 고정 문자가 많을수록 구체적"입니다. `""`(빈 패턴)이 가장 구체적이고 `"*"` 가 가장 포괄적입니다.

> ⚠️ **함정 — 매칭되는 전이가 하나도 없으면 Job 이 조용히 멈춥니다**
> ```java
> .start(checkStep)
>     .on("COMPLETED").to(settleStep)
> .end()
> ```
> 이 Job 에서 `checkStep` 이 `FAILED` 로 끝나면? `FAILED` 에 맞는 전이가 없습니다.
>
> **결과**
> ```
> ERROR 45150 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step checkStep in job noMatchJob
> java.lang.IllegalStateException: 대상 조회 실패
> 	...
> INFO 45150 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=noMatchJob]] completed with the following parameters: [{...}] and the following status: [FAILED] in 231ms
> ```
> 이 경우는 다행히 `FAILED` 로 끝납니다. 하지만 **커스텀 ExitStatus 를 쓰기 시작하면 이야기가 달라집니다.**
> `NO_DATA` 를 반환했는데 전이에 `on("NO_DATA")` 를 안 써 뒀다면, `NoSuchElementException` 이나 흐름 중단으로 이어집니다.
> **커스텀 ExitStatus 를 쓰는 모든 Step 뒤에는 `on("*")` 로 안전망을 하나 두세요.** 어떤 상태가 와도 갈 곳이 있어야 합니다.
> ```java
> .from(checkStep).on("*").to(unexpectedStatusStep)   // 안전망
> ```

---

## 10-4. `.end()` / `.fail()` / `.stopAndRestart()`

전이의 종착점은 세 가지입니다. **Job 의 최종 `BatchStatus` 와 재시작 동작이 각각 다릅니다.**

| 종착점 | Job `BatchStatus` | Job `ExitStatus` | 재시작하면 |
|---|---|---|---|
| `.end()` | `COMPLETED` | `COMPLETED` | **불가.** 같은 파라미터면 `JobInstanceAlreadyCompleteException` |
| `.end("코드")` | `COMPLETED` | `코드` | 위와 동일 |
| `.fail()` | `FAILED` | `FAILED` | 가능. 실패한 Step 부터 |
| `.stopAndRestart(step)` | `STOPPED` | `STOPPED` | 가능. **지정한 Step 부터** |

```java
// [10-4] 세 종착점
new JobBuilder("terminalJob", jobRepository)
        .start(validateStep)
            .on("VALID").to(settleStep)
        .from(validateStep)
            .on("INVALID").fail()                        // 검증 실패 → Job FAILED
        .from(validateStep)
            .on("NEEDS_APPROVAL").stopAndRestart(settleStep)  // 사람 승인 대기 → STOPPED
        .from(settleStep)
            .on("*").end()                               // 정상 종료
        .end()
        .build();
```

`stopAndRestart` 를 실행해 봅니다.

**결과**
```
INFO 45210 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [validateStep]
INFO 45210 --- [           main] c.e.batch.step10.TerminalConfig          : [validate] 승인 필요 금액 감지 → ExitStatus=NEEDS_APPROVAL
INFO 45210 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [validateStep] executed in 33ms
INFO 45210 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=terminalJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [STOPPED] in 187ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT JOB_EXECUTION_ID, STATUS, EXIT_CODE FROM BATCH_JOB_EXECUTION
ORDER BY JOB_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+------------------+---------+-----------+
| JOB_EXECUTION_ID | STATUS  | EXIT_CODE |
+------------------+---------+-----------+
|               31 | STOPPED | STOPPED   |
+------------------+---------+-----------+
```

같은 파라미터로 다시 실행하면 `validateStep` 을 건너뛰고 `settleStep` 부터 시작합니다.

**결과**
```
INFO 45255 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=terminalJob]] launched with the following parameters: [{'date':'{value=2025-03-01, ...}'}]
INFO 45255 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
INFO 45255 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 407ms
INFO 45255 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=terminalJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 512ms
```

> 💡 **실무 팁 — `stopAndRestart` 는 "사람의 개입"을 배치에 넣는 방법입니다**
> 정산 금액이 임계치를 넘으면 자동으로 진행하지 않고 멈추게 하는 패턴에 씁니다. 담당자가 확인한 뒤 같은 파라미터로 재실행하면 승인 이후 단계부터 이어집니다.
> `.fail()` 로 하면 안 되냐고 물을 수 있는데, **의미가 다릅니다.** `FAILED` 는 모니터링 알람을 울려야 하는 사건이고, `STOPPED` 는 설계된 대기 상태입니다. 이 둘을 섞으면 알람이 늑대소년이 됩니다.

---

## 10-5. 가장 위험한 한 줄 — 실패를 은폐하는 `.end()`

복구 Step 을 붙이는 것은 좋은 습관입니다. 그런데 이렇게 쓰면 안 됩니다.

```java
// [10-5] 겉보기에 멀쩡한, 그러나 위험한 Job
@Bean
public Job hidingJob(JobRepository jobRepository) {
    return new JobBuilder("hidingJob", jobRepository)
            .start(settleStep)
                .on("COMPLETED").to(reportStep)
            .from(settleStep)
                .on("FAILED").to(recoveryStep).end()   // ← 이 한 줄
            .from(reportStep)
                .on("*").end()
            .end()
            .build();
}
```

`settleStep` 이 실패하도록 만들고 실행합니다.

**결과**
```
INFO 45301 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=hidingJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 45301 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
ERROR 45301 --- [           main] o.s.batch.core.step.AbstractStep          : Encountered an error executing step settleStep in job hidingJob

org.springframework.dao.DuplicateKeyException: PreparedStatementCallback; SQL [INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount) VALUES (?,?,?,?,?,?,?)]; Duplicate entry '17' for key 'settlement.uk_settlement_order'
	at org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator.doTranslate(SQLErrorCodeSQLExceptionTranslator.java:243) ~[spring-jdbc-6.1.6.jar:6.1.6]
	at org.springframework.batch.item.database.JdbcBatchItemWriter.write(JdbcBatchItemWriter.java:212) ~[spring-batch-infrastructure-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.writeItems(SimpleChunkProcessor.java:194) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.ChunkOrientedTasklet.execute(ChunkOrientedTasklet.java:77) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...
INFO 45301 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 1s102ms
INFO 45301 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [recoveryStep]
INFO 45301 --- [           main] c.e.batch.step10.HidingConfig            : [recovery] 부분 정산 데이터를 정리했습니다
INFO 45301 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [recoveryStep] executed in 64ms
INFO 45301 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=hidingJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 1s388ms
```

**마지막 줄을 보세요. `status: [COMPLETED]` 입니다.**

메타데이터로 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT je.JOB_EXECUTION_ID, je.STATUS AS job_status, je.EXIT_CODE AS job_exit,
       se.STEP_NAME, se.STATUS AS step_status, se.EXIT_CODE AS step_exit
FROM BATCH_JOB_EXECUTION je JOIN BATCH_STEP_EXECUTION se USING (JOB_EXECUTION_ID)
WHERE je.JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
ORDER BY se.STEP_EXECUTION_ID;"
```

**결과**
```
+------------------+------------+-----------+---------------+-------------+-----------+
| JOB_EXECUTION_ID | job_status | job_exit  | STEP_NAME     | step_status | step_exit |
+------------------+------------+-----------+---------------+-------------+-----------+
|               34 | COMPLETED  | COMPLETED | settleStep    | FAILED      | FAILED    |
|               34 | COMPLETED  | COMPLETED | recoveryStep  | COMPLETED   | COMPLETED |
+------------------+------------+-----------+---------------+-------------+-----------+
```

> ⚠️ **함정 — `.on("FAILED").to(recoveryStep).end()` 는 실패를 지웁니다**
> Step 은 분명히 `FAILED` 인데 Job 은 `COMPLETED` 입니다. 이게 왜 재앙인지 하나씩 봅니다.
>
> 1. **모니터링이 침묵합니다.** 배치 알람은 대부분 `BATCH_JOB_EXECUTION.STATUS = 'FAILED'` 나 프로세스 종료 코드로 걸려 있습니다. 둘 다 정상입니다.
> 2. **`System.exit(SpringApplication.exit(...))` 이 0 을 반환합니다.** 스케줄러(Quartz, Airflow, 쿠버네티스 Job)가 성공으로 판정합니다. 후속 잡이 **미완성 정산 데이터 위에서** 돌기 시작합니다.
> 3. **재시작이 막힙니다.** Job 이 `COMPLETED` 이므로 같은 파라미터로 다시 돌리면 `JobInstanceAlreadyCompleteException` 이 납니다. 고치고 다시 돌리려면 파라미터를 바꾸거나 메타데이터를 손봐야 합니다.
> 4. **정산이 반쯤 된 채로 남습니다.** 70,000건 중 40,000건만 `settlement` 에 들어간 상태인데, 아무도 모릅니다.
>
> **대응**
>
> | 원하는 것 | 이렇게 씁니다 |
> |---|---|
> | 복구 Step 을 돌리되 **Job 은 실패로** | `.on("FAILED").to(recoveryStep).on("*").fail()` |
> | 복구 Step 을 돌리고 **커스텀 실패 코드** | `.on("FAILED").to(recoveryStep).on("*").end("RECOVERED_BUT_FAILED")` 는 **여전히 COMPLETED 입니다.** `.fail()` 을 쓰세요 |
> | 실패를 정말 무시해도 되는 경우 | `.end()` 를 쓰되, **왜 무시해도 되는지 주석으로 남기세요** |
>
> 고친 코드:
> ```java
> new JobBuilder("safeJob", jobRepository)
>         .start(settleStep)
>             .on("COMPLETED").to(reportStep)
>         .from(settleStep)
>             .on("FAILED").to(recoveryStep)     // 복구는 하되
>         .from(recoveryStep)
>             .on("*").fail()                    // Job 은 실패로 끝냅니다
>         .from(reportStep)
>             .on("*").end()
>         .end()
>         .build();
> ```
>
> **결과**
> ```
> INFO 45360 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [recoveryStep]
> INFO 45360 --- [           main] c.e.batch.step10.SafeConfig              : [recovery] 부분 정산 데이터를 정리했습니다
> INFO 45360 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=safeJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [FAILED] in 1s401ms
> ```
> ```
> +------------------+------------+----------+--------------+-------------+
> | JOB_EXECUTION_ID | job_status | job_exit | STEP_NAME    | step_status |
> +------------------+------------+----------+--------------+-------------+
> |               35 | FAILED     | FAILED   | settleStep   | FAILED      |
> |               35 | FAILED     | FAILED   | recoveryStep | COMPLETED   |
> +------------------+------------+----------+--------------+-------------+
> ```
> 복구 Step 은 돌았고, Job 은 실패로 보고됐고, 재시작도 가능합니다. **문법 에러는 금방 고치지만, 이렇게 조용히 성공으로 보고되는 코드는 몇 달을 갑니다.**

---

## 10-6. `JobExecutionDecider` — Step 없이 분기 판단만

10-2 의 `checkStep` 은 "판단만 하는 Step" 이었습니다. 이런 목적이라면 Step 대신 **`JobExecutionDecider`** 가 더 적절합니다.

| | ExitStatus 를 바꾸는 Step | `JobExecutionDecider` |
|---|---|---|
| `BATCH_STEP_EXECUTION` 에 행이 남습니까 | 남습니다 | **안 남습니다** |
| 트랜잭션 | 있습니다 | 없습니다 |
| 재시작 시 | Step 으로 취급 | 매번 다시 평가됩니다 |
| 적합한 용도 | 실제 작업 + 부수적 분기 | **순수한 판단** |

정산 방식을 주문 건수로 분기시킵니다.

```java
// [10-6] 주문 건수에 따라 정산 방식을 고르는 Decider
public class SettleModeDecider implements JobExecutionDecider {

    private static final long BULK_THRESHOLD = 1_000L;

    private final JdbcTemplate jdbcTemplate;

    public SettleModeDecider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
        String date = jobExecution.getJobParameters().getString("date");

        Long count = (date == null)
                ? jdbcTemplate.queryForObject(
                      "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'", Long.class)
                : jdbcTemplate.queryForObject(
                      "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                      Long.class, date);

        long c = count == null ? 0L : count;

        // 판단 결과를 Job Context 에 남깁니다 — Step 09 에서 배운 그대로입니다.
        jobExecution.getExecutionContext().putLong("targetCount", c);

        if (c == 0)                  return new FlowExecutionStatus("NO_DATA");
        if (c >= BULK_THRESHOLD)     return new FlowExecutionStatus("BULK");
        return new FlowExecutionStatus("NORMAL");
    }
}
```

```java
@Bean
public Job deciderJob(JobRepository jobRepository, SettleModeDecider settleModeDecider) {
    return new JobBuilder("deciderJob", jobRepository)
            .start(prepareStep)
            .next(settleModeDecider)
                .on("BULK").to(bulkSettleStep)
            .from(settleModeDecider)
                .on("NORMAL").to(normalSettleStep)
            .from(settleModeDecider)
                .on("NO_DATA").to(skipNoticeStep)
            .from(settleModeDecider)
                .on("*").fail()                 // 안전망 (10-3 의 교훈)
            .end()
            .build();
}
```

하루치(389건)로 실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=deciderJob date=2025-03-01'
```

**결과**
```
INFO 45412 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [prepareStep]
INFO 45412 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [prepareStep] executed in 27ms
INFO 45412 --- [           main] c.e.batch.step10.SettleModeDecider       : date=2025-03-01, targetCount=389 → NORMAL
INFO 45412 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [normalSettleStep]
INFO 45412 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [normalSettleStep] executed in 418ms
INFO 45412 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=deciderJob]] completed with ... and the following status: [COMPLETED] in 601ms
```

전체(70,000건)로 실행합니다. `date` 파라미터를 빼면 됩니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=deciderJob run.id=2'
```

**결과**
```
INFO 45450 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [prepareStep]
INFO 45450 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [prepareStep] executed in 24ms
INFO 45450 --- [           main] c.e.batch.step10.SettleModeDecider       : date=null, targetCount=70000 → BULK
INFO 45450 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [bulkSettleStep]
INFO 45450 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [bulkSettleStep] executed in 8s114ms
INFO 45450 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=deciderJob]] completed with ... and the following status: [COMPLETED] in 8s390ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, READ_COUNT, WRITE_COUNT FROM BATCH_STEP_EXECUTION
ORDER BY STEP_EXECUTION_ID DESC LIMIT 3;"
```

**결과**
```
+------------------+------------+-------------+
| STEP_NAME        | READ_COUNT | WRITE_COUNT |
+------------------+------------+-------------+
| bulkSettleStep   |      70000 |       70000 |
| prepareStep      |          0 |           0 |
| normalSettleStep |        389 |         389 |
+------------------+------------+-------------+
```

`decider` 자체는 `BATCH_STEP_EXECUTION` 에 **행을 남기지 않습니다.** 위 결과에 `settleModeDecider` 라는 이름이 없는 것이 그 증거입니다.

흐름 다이어그램:

```
                        ┌──────────────┐
                        │ prepareStep  │
                        └──────┬───────┘
                               │
                        ┌──────▼────────────┐
                        │ SettleModeDecider │   (Step 아님. 행이 안 남음)
                        └──┬────┬────┬───┬──┘
              "BULK"       │    │    │   │      "*"
        ┌──────────────────┘    │    │   └──────────────┐
        │           "NORMAL"    │    │  "NO_DATA"       │
        │        ┌──────────────┘    └────────┐         │
        ▼        ▼                            ▼         ▼
 ┌─────────────┐ ┌───────────────┐ ┌────────────────┐ ┌──────┐
 │bulkSettle   │ │normalSettle   │ │skipNoticeStep  │ │fail()│
 │Step (70000) │ │Step (389)     │ │                │ │      │
 └──────┬──────┘ └──────┬────────┘ └───────┬────────┘ └──────┘
        └───────────────┴──────────────────┘
                        │
                     end()
```

> ⚠️ **함정 — Decider 는 재시작할 때마다 다시 평가됩니다**
> Decider 는 Step 이 아니므로 실행 이력이 남지 않고, 따라서 **재시작 시 이전 판단을 기억하지 않습니다.**
> 1차 실행 때 389건이라 `NORMAL` 로 갔는데, 그 사이에 데이터가 들어와 재시작 시점에 1,200건이 되었다면 이번에는 `BULK` 로 갑니다. **같은 JobExecution 의 이어서 실행인데 경로가 바뀝니다.**
> 이미 `normalSettleStep` 이 부분 처리한 데이터 위에서 `bulkSettleStep` 이 돌면 무슨 일이 벌어질지는 상상에 맡기겠습니다.
>
> **대응**: 판단 근거를 첫 실행 때 `jobExecution.getExecutionContext()` 에 저장하고, Decider 는 컨텍스트에 값이 있으면 **DB 를 다시 조회하지 않고 그 값을 씁니다.**
> ```java
> ExecutionContext ctx = jobExecution.getExecutionContext();
> long c = ctx.containsKey("targetCount")
>         ? ctx.getLong("targetCount")        // 재시작 — 이전 판단을 재사용
>         : queryCount(date);                 // 첫 실행 — 조회 후 저장
> ctx.putLong("targetCount", c);
> ```
> Step 09 의 ExecutionContext 가 여기서 쓰입니다.

---

## 10-7. `.split()` — 병렬 흐름

서로 의존하지 않는 두 흐름은 동시에 돌릴 수 있습니다.

```java
// [10-7] 두 흐름을 병렬로
@Bean
public Flow customerAggFlow() {
    return new FlowBuilder<Flow>("customerAggFlow")
            .start(customerAggStep)
            .build();
}

@Bean
public Flow cityAggFlow() {
    return new FlowBuilder<Flow>("cityAggFlow")
            .start(cityAggStep)
            .build();
}

@Bean
public Job splitJob(JobRepository jobRepository) {
    return new JobBuilder("splitJob", jobRepository)
            .start(settleStep)
            .next(new FlowBuilder<Flow>("parallelAgg")
                    .split(new SimpleAsyncTaskExecutor("agg-"))
                    .add(customerAggFlow(), cityAggFlow())
                    .build())
            .next(reportStep)
            .build()
            .build();
}
```

**결과**
```
INFO 45510 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=splitJob]] launched with the following parameters: [{'date':'{value=2025-03-01, ...}'}]
INFO 45510 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settleStep]
INFO 45510 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settleStep] executed in 412ms
INFO 45510 --- [        agg-1] o.s.batch.core.job.SimpleStepHandler      : Executing step: [customerAggStep]
INFO 45510 --- [        agg-2] o.s.batch.core.job.SimpleStepHandler      : Executing step: [cityAggStep]
INFO 45510 --- [        agg-2] c.e.batch.step10.SplitConfig             : [cityAgg] 5개 도시 집계 완료 (1204ms)
INFO 45510 --- [        agg-2] o.s.batch.core.step.AbstractStep          : Step: [cityAggStep] executed in 1s211ms
INFO 45510 --- [        agg-1] c.e.batch.step10.SplitConfig             : [customerAgg] 1000명 고객 집계 완료 (1873ms)
INFO 45510 --- [        agg-1] o.s.batch.core.step.AbstractStep          : Step: [customerAggStep] executed in 1s882ms
INFO 45510 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [reportStep]
INFO 45510 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [reportStep] executed in 44ms
INFO 45510 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=splitJob]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 2s491ms
```

읽을 것이 두 가지 있습니다.

1. **스레드 이름이 `agg-1`, `agg-2` 로 갈렸습니다.** 진짜로 병렬입니다.
2. **`reportStep` 은 두 흐름이 **모두** 끝난 뒤에 시작합니다.** `split` 은 조인 지점을 자동으로 만듭니다.

순차로 돌렸다면 1,211ms + 1,882ms = 3,093ms 였을 것이 **1,882ms** 로 줄었습니다. 느린 쪽에 맞춰집니다.

```
        settleStep
             │
        ┌────┴────┐  split(SimpleAsyncTaskExecutor)
        │         │
   [agg-1]     [agg-2]
customerAgg   cityAgg
  1882ms       1211ms
        │         │
        └────┬────┘  ← 둘 다 끝나야 통과 (join)
             │
        reportStep
```

> ⚠️ **함정 — `split` 안의 흐름이 하나라도 실패하면 전체가 실패합니다. 그런데 나머지는 계속 돕니다.**
> `customerAggStep` 이 실패해도 `cityAggStep` 은 이미 다른 스레드에서 돌고 있으므로 중간에 멈추지 않습니다. 끝까지 돈 뒤에 전체 흐름이 `FAILED` 가 됩니다.
> 즉 **"실패했는데 부수 효과는 다 일어난"** 상태가 됩니다. `split` 안에 넣는 Step 들은 서로, 그리고 이후 단계와 **부수 효과가 독립**이어야 합니다.
>
> 그리고 `SimpleAsyncTaskExecutor` 는 **요청마다 새 스레드를 만들고 재사용하지 않습니다.** 흐름이 두세 개일 때는 괜찮지만, 개수가 늘어나면 `ThreadPoolTaskExecutor` 로 바꾸세요.
> ```java
> ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
> exec.setCorePoolSize(4);
> exec.setMaxPoolSize(4);
> exec.setThreadNamePrefix("agg-");
> exec.initialize();
> ```
> 마지막으로 **HikariCP 풀 크기**를 확인하세요. 병렬 흐름 수보다 커넥션이 적으면 서로 기다리다 타임아웃이 납니다. 이 프로젝트는 `maximum-pool-size: 20` 입니다.

---

## 10-8. Flow 재사용과 FlowStep

전이 그래프가 커지면 `Job` 설정 하나가 수백 줄이 됩니다. **`Flow` 를 빈으로 뽑아 재사용**하면 됩니다.

```java
// [10-8] 재사용 가능한 Flow
@Bean
public Flow settleFlow() {
    return new FlowBuilder<Flow>("settleFlow")
            .start(validateStep)
                .on("VALID").to(settleStep)
            .from(validateStep)
                .on("*").fail()
            .from(settleStep)
                .on("*").to(verifyStep)
            .build();
}

// 이 Flow 를 여러 Job 이 씁니다
@Bean
public Job dailyJob(JobRepository jobRepository, Flow settleFlow) {
    return new JobBuilder("dailyJob", jobRepository)
            .start(settleFlow)
            .next(dailyReportStep)
            .end()
            .build();
}

@Bean
public Job monthlyJob(JobRepository jobRepository, Flow settleFlow) {
    return new JobBuilder("monthlyJob", jobRepository)
            .start(settleFlow)
            .next(monthlyReportStep)
            .next(archiveStep)
            .end()
            .build();
}
```

**`FlowStep`** 은 한 걸음 더 나갑니다. Flow 전체를 **하나의 Step 처럼** 다룹니다.

```java
// [10-8] Flow 를 Step 으로 감싸기
@Bean
public Step settleFlowStep(JobRepository jobRepository, Flow settleFlow) {
    return new StepBuilder("settleFlowStep", jobRepository)
            .flow(settleFlow)
            .build();
}
```

두 방식의 차이는 **메타데이터에 나타납니다.**

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, EXIT_CODE FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
ORDER BY STEP_EXECUTION_ID;"
```

**결과** — `.start(settleFlow)` 로 직접 붙였을 때
```
+-----------------+-----------+-----------+
| STEP_NAME       | STATUS    | EXIT_CODE |
+-----------------+-----------+-----------+
| validateStep    | COMPLETED | VALID     |
| settleStep      | COMPLETED | COMPLETED |
| verifyStep      | COMPLETED | COMPLETED |
| dailyReportStep | COMPLETED | COMPLETED |
+-----------------+-----------+-----------+
```

**결과** — `FlowStep` 으로 감쌌을 때
```
+-----------------+-----------+-----------+
| STEP_NAME       | STATUS    | EXIT_CODE |
+-----------------+-----------+-----------+
| settleFlowStep  | COMPLETED | COMPLETED |
| validateStep    | COMPLETED | VALID     |
| settleStep      | COMPLETED | COMPLETED |
| verifyStep      | COMPLETED | COMPLETED |
| dailyReportStep | COMPLETED | COMPLETED |
+-----------------+-----------+-----------+
```

`settleFlowStep` 이라는 **묶음 행이 하나 더 생깁니다.** 그 행의 상태는 내부 Flow 전체의 결과를 요약합니다.

| 방식 | 언제 씁니까 |
|---|---|
| `.start(flow).next(step)` | Flow 를 Job 구조의 일부로 그대로 펼칠 때 |
| `FlowStep` | Flow 를 **하나의 논리 단위**로 다루고 싶을 때. 상위 Job 에서 `on()` 으로 전이시키거나, 그 묶음의 성패를 한 행으로 모니터링하고 싶을 때 |

> 💡 **실무 팁 — Flow 빈은 `@Bean` 이름 충돌에 주의하세요**
> 여러 Job 이 같은 `Flow` 빈을 공유할 때, 그 Flow 안의 **Step 이름은 전역에서 유일**해야 합니다. 같은 Step 인스턴스가 두 Job 에서 돌면 `BATCH_STEP_EXECUTION` 에 같은 이름의 행이 섞여 남고, 재시작 시 어느 Job 의 것인지 헷갈립니다.
> Step 이름 앞에 도메인 접두사를 붙이는 관례(`settle.validate`, `settle.write`)를 권합니다.

---

## 10-9. 종합 — 정산 Job 의 완성형

지금까지의 요소를 하나로 묶습니다.

```
                          ┌──────────────┐
                          │ prepareStep  │  (대상 조회, Job Ctx 에 targetCount 승격)
                          └──────┬───────┘
                                 │
                        ┌────────▼──────────┐
                        │ SettleModeDecider │  ← 컨텍스트 우선, 없으면 DB 조회
                        └──┬──────┬──────┬──┘
                  "BULK"   │      │      │  "NO_DATA"
             ┌─────────────┘      │      └──────────────┐
             │        "NORMAL"    │                     │
             ▼                    ▼                     ▼
      ┌─────────────┐      ┌──────────────┐      ┌──────────────┐
      │bulkSettle   │      │normalSettle  │      │skipNotice    │
      │Step         │      │Step          │      │Step          │
      └──┬───────┬──┘      └──┬────────┬──┘      └──────┬───────┘
"COMPLETED"│   "FAILED"       │        │                │
         │ └────────┬─────────┘        │                │
         │          ▼                  │                │
         │   ┌─────────────┐           │                │
         │   │recoveryStep │           │                │
         │   └──────┬──────┘           │                │
         │          │ on("*").fail()   │                │
         │          ▼                  │                │
         │      [ FAILED ]             │                │
         │                             │                │
         └──────────┬──────────────────┘                │
                    ▼                                   │
        ┌───────────────────────┐ split(agg-)           │
        │  ┌─────────────────┐  │                       │
        │  │ customerAggStep │  │                       │
        │  ├─────────────────┤  │                       │
        │  │ cityAggStep     │  │                       │
        │  └─────────────────┘  │                       │
        └───────────┬───────────┘                       │
                    ▼                                   │
             ┌─────────────┐                            │
             │ reportStep  │                            │
             └──────┬──────┘                            │
                    ▼                                   ▼
                 end()                               end()
```

**결과** (하루치, `date=2025-03-01`)
```
INFO 45601 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=step10Job]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 45601 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [prepareStep]
INFO 45601 --- [           main] c.e.batch.step10.Step10Job               : [prepare] date=2025-03-01, targetCount=389
INFO 45601 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [prepareStep] executed in 31ms
INFO 45601 --- [           main] o.s.b.c.l.ExecutionContextPromotionListener: Promoting keys [targetCount] from step [prepareStep] to job execution context
INFO 45601 --- [           main] c.e.batch.step10.SettleModeDecider       : 컨텍스트에서 targetCount=389 재사용 → NORMAL
INFO 45601 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [normalSettleStep]
INFO 45601 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [normalSettleStep] executed in 402ms
INFO 45601 --- [        agg-1] o.s.batch.core.job.SimpleStepHandler      : Executing step: [customerAggStep]
INFO 45601 --- [        agg-2] o.s.batch.core.job.SimpleStepHandler      : Executing step: [cityAggStep]
INFO 45601 --- [        agg-2] o.s.batch.core.step.AbstractStep          : Step: [cityAggStep] executed in 118ms
INFO 45601 --- [        agg-1] o.s.batch.core.step.AbstractStep          : Step: [customerAggStep] executed in 164ms
INFO 45601 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [reportStep]
INFO 45601 --- [           main] c.e.batch.step10.Step10Job               : [report] 2025-03-01 정산 389건 (NORMAL), 수수료 합계 484670.00
INFO 45601 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [reportStep] executed in 27ms
INFO 45601 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=step10Job]] completed with the following parameters: [{'date':'{value=2025-03-01, ...}'}] and the following status: [COMPLETED] in 934ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, EXIT_CODE, READ_COUNT, WRITE_COUNT
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
ORDER BY STEP_EXECUTION_ID;"
```

**결과**
```
+------------------+-----------+-----------+------------+-------------+
| STEP_NAME        | STATUS    | EXIT_CODE | READ_COUNT | WRITE_COUNT |
+------------------+-----------+-----------+------------+-------------+
| prepareStep      | COMPLETED | COMPLETED |          0 |           0 |
| normalSettleStep | COMPLETED | COMPLETED |        389 |         389 |
| cityAggStep      | COMPLETED | COMPLETED |          0 |           0 |
| customerAggStep  | COMPLETED | COMPLETED |          0 |           0 |
| reportStep       | COMPLETED | COMPLETED |          0 |           0 |
+------------------+-----------+-----------+------------+-------------+
```

`bulkSettleStep`, `recoveryStep`, `skipNoticeStep` 은 **실행되지 않았으므로 행 자체가 없습니다.** Job 정의에 있다고 해서 행이 생기는 것이 아닙니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `.next()` vs `.on()` | 전자는 `SimpleJob`, 후자는 **`FlowJob`** 을 만듭니다 |
| **`on()` 이 보는 값** | `BatchStatus` 가 아니라 **`ExitStatus`(= `EXIT_CODE` 컬럼)** |
| `afterStep` 반환값 | 원래 값을 덮어씁니다. 바꿀 게 없으면 `getExitStatus()` 나 `null` 반환 |
| 와일드카드 | `*` = 0개 이상, `?` = 정확히 1개 |
| 매칭 우선순위 | **가장 구체적인 패턴이 이깁니다** (와일드카드가 적을수록 앞) |
| 매칭 실패 | 갈 곳이 없으면 흐름이 끊깁니다. 커스텀 ExitStatus 뒤엔 `on("*")` 안전망 |
| `.end()` (전이) | Job 을 **COMPLETED** 로 종료. 재시작 불가 |
| `.end()` (빌더) | `FlowBuilder` 를 닫는 것. **전이의 `.end()` 와 다릅니다** |
| `.fail()` | Job 을 **FAILED** 로 종료. 재시작 가능 |
| `.stopAndRestart(step)` | Job 을 **STOPPED** 로. 재시작하면 지정 Step 부터. 사람 개입용 |
| **실패 은폐 함정** | `.on("FAILED").to(recovery).end()` → Step FAILED, **Job COMPLETED**. 알람 침묵 + 재시작 봉쇄 |
| 대응 | 복구 Step 뒤에 `.on("*").fail()` |
| `JobExecutionDecider` | 순수 판단용. `BATCH_STEP_EXECUTION` 에 **행이 안 남음** |
| Decider 함정 | 재시작마다 **다시 평가**됨 → 판단 근거를 Job Context 에 저장해 재사용 |
| `.split(executor)` | 흐름 병렬 실행. 모두 끝나야 다음으로(join) |
| `split` 함정 | 하나가 실패해도 나머지는 끝까지 돕니다. 부수 효과가 독립이어야 함 |
| `Flow` 빈 | 여러 Job 이 재사용. **Step 이름은 전역 유일**하게 |
| `FlowStep` | Flow 를 한 Step 으로 감쌉니다. 묶음 행이 하나 더 생김 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `on()` 이 매칭하는 값이 무엇인지 SQL 결과로 증명하기
2. 커스텀 ExitStatus 를 반환하는 `StepExecutionListener` 작성하기
3. 네 개의 패턴이 동시에 맞을 때 어디로 가는지 예측하고 검증하기
4. 실패를 은폐하는 Job 을 찾아내고 `.fail()` 로 고치기
5. `JobExecutionDecider` 로 세 갈래 분기 만들기 (재시작 안전성 포함)
6. `.split()` 으로 두 집계 흐름을 병렬화하고 스레드 이름으로 확인하기
7. 주어진 요구사항을 ASCII 흐름도로 그리고 그대로 코드로 옮기기

---

## 다음 단계

흐름 제어는 "Step 단위의 성공/실패"를 다뤘습니다. 하지만 70,000건을 처리하다 보면 **한두 건이 문제인데 Step 전체가 죽는** 일이 훨씬 많습니다. 잘못된 고객 ID 하나 때문에 나머지 69,999건의 정산이 멈추면 안 됩니다.

다음 스텝은 그 입자를 아이템 단위로 낮춥니다. `faultTolerant()`, `skip()`, `retry()`, 그리고 **스킵 한도를 잘못 잡으면 불량 데이터가 조용히 사라지는** 함정을 다룹니다.

→ [Step 11 — 내결함성](../step-11-fault-tolerance/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 로 10-1 ~ 10-9 의 흐름을 순서대로 재현하고, `Exercise.java` 의 7문제를 풀고, `Solution.java` 로 대조합니다. 패키지는 `com.example.batch.step10` 이며, 각 절의 Job 설정을 `static class` 로 중첩해 두었습니다. 흐름 제어는 **로그와 메타데이터를 함께 봐야** 이해되므로, 각 실행 뒤에 파일 하단의 조회 SQL 을 반드시 돌려 보세요.

### Practice.java

본문의 모든 Job 정의를 절 번호 주석(`// [10-3]` 형태)과 함께 담았습니다.

- **`ExitStatusConfig`(10-2)** 가 이 스텝의 출발점입니다. `checkStep` 하나로 `HAS_DATA` / `NO_DATA` 를 나누고, `BATCH_STEP_EXECUTION` 의 `STATUS` 와 `EXIT_CODE` 가 **서로 다른 값**을 갖는 것을 확인합니다. `date=2025-03-01`(389건)과 `date=2025-12-25`(0건) 두 번을 모두 실행해야 양쪽 분기를 봅니다.
- **`WildcardConfig`(10-3)** 는 `"COMPLETED"` / `"COMPLETED*"` / `"C?MPLETED"` / `"*"` 네 개의 전이를 같은 Step 에서 내보냅니다. 어디로 갈지 **먼저 예측한 뒤** 실행하세요. 예측이 틀렸다면 그게 이 절의 학습 지점입니다.
- **`HidingConfig`(10-5)** 는 **일부러 실패를 은폐하는** Job 입니다. 실행 전에 `settlement` 에 데이터를 남겨 두어야(=`TRUNCATE` 하지 **않아야**) `DuplicateKeyException` 이 재현됩니다. 실행 후 반드시 Job 상태를 SQL 로 확인하세요. 로그만 보면 `COMPLETED` 라서 아무 문제가 없어 보입니다.
- **`SafeConfig`(10-5)** 가 그 교정판입니다. 두 클래스를 나란히 놓고 `.end()` 하나가 `.fail()` 로 바뀐 것만으로 결과가 어떻게 달라지는지 비교하세요.
- **`SettleModeDecider`(10-6)** 는 **Job Context 를 먼저 확인하고 없을 때만 DB 를 조회**하는 형태로 작성돼 있습니다. 재시작 안전성을 위한 것이며, 본문 함정 블록과 짝을 이룹니다. 컨텍스트 캐시를 끄고 싶으면 `USE_CONTEXT_CACHE` 상수를 `false` 로 바꿔 차이를 관찰하세요.
- **`SplitConfig`(10-7)** 의 집계 Step 두 개는 실행 시간을 벌리려고 각각 `Thread.sleep` 없이 실제 집계 쿼리를 돕니다(고객 1,000명 / 도시 5개). 로그에서 스레드 이름 `agg-1` / `agg-2` 가 보이는지가 확인 포인트입니다. **`main` 스레드에서 둘 다 돈다면 `split` 이 아니라 `next` 로 이어졌다는 뜻입니다.**
- **`Step10Job`(10-9)** 이 종합 예제입니다. Step 09 의 승격 리스너까지 함께 씁니다. 실행 전 `TRUNCATE TABLE settlement;` 를 권합니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·3** 은 실행해서 관찰하는 문제, **문제 2·5·6·7** 은 코드를 쓰는 문제, **문제 4** 는 주어진 Job 을 진단하고 고치는 문제입니다.
- 문제 3 은 답을 **먼저 주석에 적고** 실행하도록 설계했습니다. 실행 결과를 보고 나서 답을 적으면 아무것도 배우지 못합니다.
- 문제 4 의 `Q4Job` 은 실행하면 `COMPLETED` 로 끝납니다. **성공했다고 넘어가면 틀린 것입니다.** `BATCH_STEP_EXECUTION` 을 조회해 `FAILED` 인 Step 이 있는지 확인해야 문제가 보입니다.
- 문제 5 는 Decider 를 쓰되 **재시작해도 같은 경로로 가도록** 만드는 것이 요구사항입니다. 단순히 `decide()` 안에서 `COUNT(*)` 를 부르면 절반만 맞은 답입니다.
- 문제 7 은 코드보다 **다이어그램을 먼저** 그리게 합니다. 흐름 제어는 그림으로 정리되지 않으면 코드로도 정리되지 않습니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과 해설 주석입니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 SQL 한 줄로 증명합니다. `STATUS='COMPLETED'` 이면서 `EXIT_CODE='NO_DATA'` 인 행이 존재한다는 것이 곧 "`on()` 은 `EXIT_CODE` 를 본다"의 증거입니다.
- **정답 2** 는 `afterStep` 에서 **원래 값을 보존하는 것**까지가 정답입니다. `return ExitStatus.COMPLETED` 로 뭉개면 다른 리스너나 프레임워크가 설정한 값을 지웁니다. 리스너 여러 개가 붙었을 때 뒤에 등록된 것이 이긴다는 점도 함께 설명합니다.
- **정답 3** 의 정답은 `"COMPLETED"`(정확 일치)입니다. `"C?MPLETED"` 도 매칭되지만 `?` 하나가 와일드카드라 덜 구체적입니다. 구체성 순서를 표로 정리했습니다.
- **정답 4** 는 `.end()` → `.fail()` 이 전부가 아닙니다. **왜 `.end("코드")` 로는 해결되지 않는지** — `ExitStatus` 만 바뀌고 `BatchStatus` 는 여전히 `COMPLETED` 라 알람도 재시작도 그대로 막힌다 — 를 설명합니다.
- **정답 5** 는 `jobExecution.getExecutionContext()` 를 캐시로 쓰는 형태입니다. "첫 실행에는 조회, 재시작에는 재사용"이 왜 필요한지 실제 사고 시나리오(389건 → 1,200건으로 변해 경로가 바뀜)로 풉니다.
- **정답 7** 은 다이어그램과 코드를 나란히 놓았습니다. 요구사항의 "실패 시 알림을 보내되 Job 은 실패로 끝낼 것" 이 `.on("*").fail()` 로 어떻게 번역되는지가 핵심입니다.

```java file="./Solution.java"
```
