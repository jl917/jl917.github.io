# Step 04 — Tasklet Step

> **학습 목표**
> - `Tasklet` 인터페이스의 계약(`execute(StepContribution, ChunkContext)`)과 반환값의 의미를 이해한다
> - `RepeatStatus.FINISHED` 와 `RepeatStatus.CONTINUABLE` 의 차이를, **매 반복이 새 트랜잭션임을 로그로 확인**하며 익힌다
> - `CONTINUABLE` 의 무한루프 함정을 재현하고, 왜 그것이 배치에서 특히 치명적인지 안다
> - `StepContribution` 으로 처리 건수를 보고하고 `BATCH_STEP_EXECUTION` 에서 확인한다
> - `MethodInvokingTaskletAdapter`, `SystemCommandTasklet` 의 용도와 한계를 파악한다
> - Tasklet 이 적합한 경우와 청크가 적합한 경우를 판단 기준으로 정리하고, **settlement 정리 Tasklet 을 실제로 만들어 70,000행을 지운다**
>
> **선행 스텝**: Step 03 — JobParameters 와 실행 식별
> **예상 소요**: 90분

---

## 4-0. 실습 준비 — 지울 데이터를 먼저 만든다

이 스텝의 마지막 실습은 **`settlement` 테이블 정리 Tasklet** 입니다. 지우려면 먼저 있어야 하므로, Step 05 이후의 정산 결과를 SQL 로 미리 만들어 둡니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb <<'SQL'
TRUNCATE TABLE settlement;

INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
SELECT o.order_id,
       o.customer_id,
       DATE(o.ordered_at),
       o.amount,
       c.fee_rate,
       ROUND(o.amount * c.fee_rate, 2),
       o.amount - ROUND(o.amount * c.fee_rate, 2)
  FROM orders o JOIN customers c ON c.customer_id = o.customer_id
 WHERE o.status = 'COMPLETED';
SQL

mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) rows_, SUM(gross_amount) gross, SUM(fee_amount) fee, SUM(net_amount) net FROM settlement;"
```

**결과**
```
+-------+---------------+--------------+---------------+
| rows_ | gross         | fee          | net           |
+-------+---------------+--------------+---------------+
| 70000 | 3485250000.00 | 95844375.00  | 3389405625.00 |
+-------+---------------+--------------+---------------+
```

`COMPLETED` 70,000건이 그대로 정산되었습니다(프로젝트 셋업의 숫자와 일치). `gross = fee + net` 도 맞습니다.

이번 스텝의 코드는 `com.example.batch.step04` 패키지에 놓습니다.

---

## 4-1. Tasklet — Step 의 가장 단순한 구현

Spring Batch 의 `Step` 은 구현이 두 갈래입니다.

```
                    Step
                     │
        ┌────────────┴────────────┐
        │                         │
   TaskletStep              (파티셔닝/Flow 등 특수 Step)
        │
   ┌────┴──────────────────────┐
   │                           │
Tasklet 직접 구현        ChunkOrientedTasklet
"한 덩어리 작업"          "read → process → write 를 chunk 단위로"
(Step 04)                 (Step 05 ~ 08)
```

재미있는 사실이 있습니다. **청크 지향 처리도 결국 Tasklet 입니다.** `ChunkOrientedTasklet` 이라는 이름의 `Tasklet` 구현체가 read-process-write 루프를 돌리는 구조입니다. 즉 Tasklet 을 이해하면 청크의 뼈대도 이해한 것입니다.

인터페이스는 메서드 하나뿐입니다.

```java
package org.springframework.batch.core.step.tasklet;

@FunctionalInterface
public interface Tasklet {
    RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception;
}
```

| 요소 | 의미 |
|---|---|
| `StepContribution` | 이번 실행분의 **처리 건수 누적기**. read/write/filter/skip 카운트를 여기에 보고 |
| `ChunkContext` | 현재 실행의 **컨텍스트**. JobParameters, ExecutionContext, StepExecution 에 접근 |
| 반환 `RepeatStatus` | **`FINISHED`** = 끝났다 / **`CONTINUABLE`** = 한 번 더 불러 달라 |
| `throws Exception` | 예외를 던지면 트랜잭션이 롤백되고 Step 이 `FAILED` |

가장 단순한 Tasklet 을 만듭니다.

```java
package com.example.batch.step04;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class HelloTaskletConfig {

    @Bean
    public Job helloTaskletJob(JobRepository jobRepository, Step helloTaskletStep) {
        return new JobBuilder("helloTaskletJob", jobRepository)
                .start(helloTaskletStep)
                .build();
    }

    @Bean
    public Step helloTaskletStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("helloTaskletStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("[helloTasklet] 한 번 실행되고 끝납니다.");
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }
}
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=helloTaskletJob"
```

**결과**
```
INFO 45102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloTaskletJob]] launched with the following parameters: [{}]
INFO 45102 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [helloTaskletStep]
[helloTasklet] 한 번 실행되고 끝납니다.
INFO 45102 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [helloTaskletStep] executed in 18ms
INFO 45102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloTaskletJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 43ms
```

> ⚠️ **함정 — `.tasklet(...)` 은 5.0 부터 `PlatformTransactionManager` 를 반드시 요구합니다**
> 4.x 의 `stepBuilderFactory.get("step").tasklet(tasklet).build()` 를 그대로 붙여 넣으면 컴파일이 안 됩니다.
> 5.0 에서 `StepBuilder` 의 시그니처가 **`tasklet(Tasklet, PlatformTransactionManager)`** 로 바뀌었기 때문입니다.
> 트랜잭션 매니저를 "어딘가에서 알아서 찾는" 암묵적 동작을 없애고 **명시하도록 강제**한 것이며, 청크 Step 의 `.chunk(size, txManager)` 도 동일하게 바뀌었습니다.

---

## 4-2. `RepeatStatus` — FINISHED 와 CONTINUABLE

반환값의 의미는 딱 이것뿐입니다.

| 반환값 | 의미 | TaskletStep 의 동작 |
|---|---|---|
| `RepeatStatus.FINISHED` | 할 일을 다 했다 | 트랜잭션 커밋 → Step 종료 |
| `RepeatStatus.CONTINUABLE` | 아직 남았다, 다시 불러 달라 | 트랜잭션 커밋 → **`execute()` 를 다시 호출** |
| `null` | `FINISHED` 와 동일하게 취급 | Step 종료 |

핵심은 이것입니다.

> **`CONTINUABLE` 로 반복되는 매 회차는 "각각 독립된 트랜잭션" 입니다.**

`TaskletStep` 의 실행 루프를 코드 수준으로 보면 이렇습니다.

```
TaskletStep.doExecute()
  └─ stepOperations.iterate(  ← RepeatTemplate
         () -> {
             transactionTemplate.execute(status -> {      ← ★ 여기서 트랜잭션 시작
                 RepeatStatus rs = tasklet.execute(contribution, chunkContext);
                 jobRepository.update(stepExecution);      ← 메타데이터도 같은 트랜잭션
                 return rs;
             });                                          ← ★ 여기서 커밋
             return rs;   // CONTINUABLE 이면 iterate 가 위를 다시 실행
         })
```

`transactionTemplate.execute(...)` 가 **루프 안쪽**에 있습니다. 그래서 반복 = 트랜잭션입니다.

---

## 4-3. 매 반복이 새 트랜잭션임을 로그로 확인한다

말로만 하면 믿기 어려우니 직접 봅니다. 트랜잭션 로그를 켭니다.

```yaml
# application.yml — 이 절에서만 켰다가 끄세요. 로그가 매우 많아집니다.
logging:
  level:
    org.springframework.jdbc.datasource.DataSourceTransactionManager: DEBUG
```

3회 반복 후 종료하는 Tasklet 을 만듭니다.

```java
public static class CountingTasklet implements Tasklet {

    private int count = 0;                 // ⚠️ 상태를 필드에 두는 것의 위험은 아래 함정 참고

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        count++;
        System.out.printf("[countingTasklet] %d 회차 (thread=%s)%n",
                count, Thread.currentThread().getName());
        return count < 3 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
    }
}
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=countingJob"
```

**결과**
```
INFO  45233 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [countingStep]
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Creating new transaction with name [null]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Acquired Connection [HikariProxyConnection@1893214716 wrapping com.mysql.cj.jdbc.ConnectionImpl@34c2b1] for JDBC transaction
[countingTasklet] 1 회차 (thread=main)
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Initiating transaction commit
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Committing JDBC transaction on Connection [HikariProxyConnection@1893214716 wrapping com.mysql.cj.jdbc.ConnectionImpl@34c2b1]
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Releasing JDBC Connection [HikariProxyConnection@1893214716 ...] after transaction

DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Creating new transaction with name [null]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Acquired Connection [HikariProxyConnection@704531892 wrapping com.mysql.cj.jdbc.ConnectionImpl@34c2b1] for JDBC transaction
[countingTasklet] 2 회차 (thread=main)
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Initiating transaction commit
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Committing JDBC transaction on Connection [HikariProxyConnection@704531892 ...]
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Releasing JDBC Connection [HikariProxyConnection@704531892 ...] after transaction

DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Creating new transaction with name [null]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
[countingTasklet] 3 회차 (thread=main)
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Initiating transaction commit
DEBUG 45233 --- [           main] o.s.j.d.DataSourceTransactionManager     : Releasing JDBC Connection ... after transaction

INFO  45233 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [countingStep] executed in 61ms
```

**`Creating new transaction` 이 3번, `Committing JDBC transaction` 이 3번.** 반복 횟수와 정확히 같습니다.

메타데이터에도 흔적이 남습니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, COMMIT_COUNT, READ_COUNT, WRITE_COUNT, ROLLBACK_COUNT
  FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = 'countingStep';"
```

**결과**
```
+--------------+-----------+--------------+------------+-------------+----------------+
| STEP_NAME    | STATUS    | COMMIT_COUNT | READ_COUNT | WRITE_COUNT | ROLLBACK_COUNT |
+--------------+-----------+--------------+------------+-------------+----------------+
| countingStep | COMPLETED |            3 |          0 |           0 |              0 |
+--------------+-----------+--------------+------------+-------------+----------------+
```

**`COMMIT_COUNT = 3`.** 반복 3회 = 커밋 3회입니다. 이 사실이 실무에서 갖는 의미가 큽니다.

> 💡 **CONTINUABLE 은 "커밋 지점을 내 손으로 만드는" 도구입니다**
> 700만 행을 한 트랜잭션으로 지우면 InnoDB 의 언두 로그가 폭증하고, 락이 오래 잡히고, 롤백이 나면 지운 시간만큼 되돌리는 시간이 또 걸립니다.
> `CONTINUABLE` 로 1만 행씩 700번 나누면 **커밋이 700번** 일어나 언두가 계속 정리되고 락 시간도 짧아집니다.
> 4-10 의 settlement 정리 Tasklet 이 정확히 이 패턴입니다.

> ⚠️ **함정 — Tasklet 의 인스턴스 필드는 재시작 시 초기화됩니다**
> 위 `CountingTasklet` 은 `count` 를 필드에 들고 있습니다. Step 이 3회차에서 죽고 재시작하면 `count` 는 **0부터 다시** 시작합니다. Bean 이 새로 만들어지기 때문입니다.
> 게다가 Tasklet Bean 은 기본적으로 싱글턴이라, **같은 Job 을 한 JVM 에서 두 번 실행하면 `count` 가 이어집니다.** 두 번째 실행은 1회차에 이미 `count=4` 가 되어 즉시 종료됩니다.
> 반복 상태는 필드가 아니라 **`ExecutionContext`** 에 저장해야 합니다(4-6). 재시작 이야기는 Step 09·11 에서 본격적으로 다룹니다.

---

## 4-4. 무한루프 — CONTINUABLE 의 진짜 함정

종료 조건을 잘못 쓰면 이렇게 됩니다.

```java
// ❌ 절대 이렇게 쓰지 마세요
@Bean
public Step infiniteStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                         JdbcTemplate jdbcTemplate) {
    return new StepBuilder("infiniteStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                int deleted = jdbcTemplate.update(
                        "DELETE FROM settlement WHERE settle_date = '2099-01-01' LIMIT 1000");
                System.out.println("[infiniteStep] deleted=" + deleted);
                return RepeatStatus.CONTINUABLE;     // ← 종료 조건이 없다
            }, txManager)
            .build();
}
```

`2099-01-01` 짜리 데이터는 애초에 없으므로 `deleted` 는 항상 0입니다. 그런데 항상 `CONTINUABLE` 을 돌려주므로 **영원히 돕니다.**

**결과**
```
INFO 45341 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [infiniteStep]
[infiniteStep] deleted=0
[infiniteStep] deleted=0
[infiniteStep] deleted=0
[infiniteStep] deleted=0
... (Ctrl+C 로 죽일 때까지 초당 수천 줄)
```

> ⚠️ **함정 — 배치의 무한루프는 웹의 무한루프보다 훨씬 나쁩니다**
> 웹 요청이라면 타임아웃이 끊어 줍니다. 배치는 **아무도 안 끊습니다.**
>
> 더 나쁜 것은 그 뒤에 남는 상태입니다. Ctrl+C 로 프로세스를 죽이면:
>
> ```
> mysql> SELECT JOB_EXECUTION_ID, STATUS, START_TIME, END_TIME FROM BATCH_JOB_EXECUTION
>     -> WHERE JOB_EXECUTION_ID = 14;
> +------------------+----------+---------------------+----------+
> | JOB_EXECUTION_ID | STATUS   | START_TIME          | END_TIME |
> +------------------+----------+---------------------+----------+
> |               14 | STARTED  | 2025-07-20 15:41:02 | NULL     |
> +------------------+----------+---------------------+----------+
> ```
>
> **`STATUS = STARTED`, `END_TIME = NULL`.** 프레임워크 입장에서는 "아직 돌고 있는 Job" 입니다.
> 이 상태에서 같은 파라미터로 재실행하면 이렇게 거부됩니다.
>
> ```
> Caused by: org.springframework.batch.core.repository.JobExecutionAlreadyRunningException: A job execution for this job is already running: JobInstance: id=11, version=0, Job=[infiniteJob]
> 	at org.springframework.batch.core.repository.support.SimpleJobRepository.createJobExecution(SimpleJobRepository.java:130)
> ```
>
> 프로세스는 이미 죽었는데 DB 는 살아 있다고 믿습니다. 손으로 고쳐야 합니다.
>
> ```sql
> UPDATE BATCH_STEP_EXECUTION SET STATUS='FAILED', END_TIME=NOW() WHERE JOB_EXECUTION_ID=14;
> UPDATE BATCH_JOB_EXECUTION  SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=NOW() WHERE JOB_EXECUTION_ID=14;
> ```
>
> 새벽 3시에 이 UPDATE 를 치고 싶지 않다면, **`CONTINUABLE` 을 쓸 때는 종료 조건을 먼저 쓰고 나서 나머지를 쓰세요.**

안전한 형태는 이렇습니다. **"진행이 없으면 종료"** 를 항상 넣습니다.

```java
return (contribution, chunkContext) -> {
    int deleted = jdbcTemplate.update("DELETE FROM settlement WHERE settle_date = ? LIMIT 1000", date);
    contribution.incrementWriteCount(deleted);
    // ★ 진행이 없으면(0건) 반드시 종료한다
    return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
};
```

거기에 **상한선**을 하나 더 두면 이중 안전장치가 됩니다.

```java
private static final int MAX_ITERATIONS = 10_000;   // 1000행 × 10000회 = 1천만 행이면 충분

if (++iteration > MAX_ITERATIONS) {
    throw new IllegalStateException(
            "최대 반복 횟수 %d 를 초과했습니다. 종료 조건을 점검하세요.".formatted(MAX_ITERATIONS));
}
```

> 💡 **실무 팁 — 반복은 "잔여 건수" 가 아니라 "이번에 처리한 건수" 로 판정하세요**
> `SELECT COUNT(*) ... > 0` 으로 종료를 판정하면, 지워지지 않는 행(예: FK 로 막힌 행)이 하나라도 있을 때 무한루프입니다.
> `DELETE` 가 돌려주는 **영향 행 수** 로 판정하면 진행이 없을 때 반드시 멈춥니다.

---

## 4-5. `StepContribution` — 처리 건수를 보고한다

`StepContribution` 은 "이번 트랜잭션에서 몇 건을 처리했는지"를 담는 임시 집계기입니다. 트랜잭션이 커밋될 때 `StepExecution` 에 합산되고, `BATCH_STEP_EXECUTION` 에 반영됩니다.

```
Tasklet.execute()
   └─ contribution.incrementWriteCount(1000)
              │  (커밋 시점)
              ▼
   StepExecution.writeCount += 1000
              │
              ▼
   BATCH_STEP_EXECUTION.WRITE_COUNT
```

| 메서드 | 반영 컬럼 | 용도 |
|---|---|---|
| `incrementReadCount()` | `READ_COUNT` | 읽은 건수 (1씩) |
| `incrementWriteCount(int)` | `WRITE_COUNT` | 쓴 건수 |
| `incrementFilterCount(int)` | `FILTER_COUNT` | Processor 가 걸러낸 건수 |
| `setExitStatus(ExitStatus)` | `EXIT_CODE` | Step 의 종료 코드. Flow 분기(Step 10)에 사용 |

> ⚠️ **함정 — 카운트를 보고하지 않으면 모니터링이 조용히 거짓말을 합니다**
> Tasklet 에서 70,000행을 지워도 `contribution.incrementWriteCount()` 를 호출하지 않으면 `BATCH_STEP_EXECUTION.WRITE_COUNT` 는 **0** 입니다.
>
> ```
> +----------------+-----------+--------------+-------------+
> | STEP_NAME      | STATUS    | COMMIT_COUNT | WRITE_COUNT |
> +----------------+-----------+--------------+-------------+
> | cleanupStep    | COMPLETED |            8 |           0 |   ← 실제로는 70000행 삭제
> +----------------+-----------+--------------+-------------+
> ```
>
> 배치는 정상 동작했고 데이터도 맞습니다. 그런데 **운영 대시보드에는 "0건 처리" 로 뜹니다.**
> 그러면 "이 배치는 아무 일도 안 하네, 지워도 되겠다" 는 판단이 나오거나, 진짜로 0건이 처리된 장애를 구분하지 못합니다.
> 청크 Step 은 프레임워크가 자동으로 세어 주지만 **Tasklet 은 여러분이 직접 보고해야 합니다.** 이걸 잊는 것이 Tasklet 의 가장 흔한 실수입니다.

```java
return (contribution, chunkContext) -> {
    int deleted = jdbcTemplate.update("DELETE FROM settlement WHERE settle_date = ? LIMIT 1000", date);
    contribution.incrementWriteCount(deleted);          // ★ 반드시
    if (deleted == 0) {
        contribution.setExitStatus(new ExitStatus("NOTHING_TO_DELETE"));   // Step 10 의 분기에 사용
        return RepeatStatus.FINISHED;
    }
    return RepeatStatus.CONTINUABLE;
};
```

---

## 4-6. `ChunkContext` — 컨텍스트와 반복 상태 저장소

`ChunkContext` 를 통해 닿을 수 있는 것들입니다.

```java
StepContext stepContext = chunkContext.getStepContext();
StepExecution stepExecution = stepContext.getStepExecution();

JobParameters params      = stepExecution.getJobParameters();               // Step 03
ExecutionContext stepCtx  = stepExecution.getExecutionContext();            // Step 범위 (재시작 시 복원됨)
ExecutionContext jobCtx   = stepExecution.getJobExecution().getExecutionContext();  // Job 범위 (Step 간 공유)
String jobName            = stepExecution.getJobExecution().getJobInstance().getJobName();
```

4-3 의 함정을 `ExecutionContext` 로 고치면 이렇게 됩니다.

```java
@Override
public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    ExecutionContext ctx = chunkContext.getStepContext().getStepExecution().getExecutionContext();

    int count = ctx.getInt("iteration", 0);   // 없으면 0. 재시작하면 저장된 값이 복원됩니다.
    count++;
    ctx.putInt("iteration", count);           // 같은 트랜잭션에서 BATCH_STEP_EXECUTION_CONTEXT 에 저장

    System.out.printf("[ctxTasklet] %d 회차%n", count);
    return count < 3 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
}
```

**결과**
```
[ctxTasklet] 1 회차
[ctxTasklet] 2 회차
[ctxTasklet] 3 회차
INFO 45412 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [ctxStep] executed in 57ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT c.SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT c
  JOIN BATCH_STEP_EXECUTION s ON s.STEP_EXECUTION_ID = c.STEP_EXECUTION_ID
 WHERE s.STEP_NAME = 'ctxStep';"
```

**결과**
```
+---------------------------------------------------------------------+
| SHORT_CONTEXT                                                       |
+---------------------------------------------------------------------+
| {"@class":"java.util.HashMap","iteration":3,"batch.taskletType":...} |
+---------------------------------------------------------------------+
```

값이 DB 에 저장되었습니다. 재시작하면 여기서 복원됩니다. `ExecutionContext` 의 직렬화 규칙과 Job/Step 범위의 차이는 Step 09 에서 정면으로 다룹니다.

---

## 4-7. `MethodInvokingTaskletAdapter` — 기존 서비스 메서드를 그대로 쓴다

이미 잘 만들어 둔 서비스 메서드가 있는데, Tasklet 을 새로 구현하기가 아까울 때 씁니다.

```java
@Component
public class SettlementReportService {

    private final JdbcTemplate jdbcTemplate;

    public SettlementReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 배치를 전혀 모르는 평범한 메서드. Spring Batch 의존성이 하나도 없습니다. */
    public long summarize(String settleDate) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(net_amount), 0) FROM settlement WHERE settle_date = ?",
                Long.class, settleDate);
        System.out.printf("[report] %s 정산 순금액 합계 = %,d%n", settleDate, total);
        return total;
    }
}
```

```java
@Bean
@StepScope
public MethodInvokingTaskletAdapter reportTasklet(SettlementReportService service,
                                                  @Value("#{jobParameters['date']}") String date) {
    MethodInvokingTaskletAdapter adapter = new MethodInvokingTaskletAdapter();
    adapter.setTargetObject(service);
    adapter.setTargetMethod("summarize");
    adapter.setArguments(new Object[]{date});
    return adapter;
}
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=reportJob,date=2025-03-01"
```

**결과**
```
INFO 45520 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [reportStep]
[report] 2025-03-01 정산 순금액 합계 = 18,842,196
INFO 45520 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [reportStep] executed in 34ms
```

> ⚠️ **함정 — 메서드 이름을 문자열로 지정하므로 컴파일러가 오타를 못 잡습니다**
> `setTargetMethod("summarise")` 처럼 철자를 틀리면 **기동 시점이 아니라 Step 실행 시점**에 터집니다.
>
> ```
> Caused by: java.lang.IllegalArgumentException: Unable to locate method: summarise
> 	at org.springframework.util.Assert.notNull(Assert.java:172)
> 	at org.springframework.beans.support.ArgumentConvertingMethodInvoker.prepare(ArgumentConvertingMethodInvoker.java:97)
> 	at org.springframework.batch.core.step.tasklet.MethodInvokingTaskletAdapter.execute(MethodInvokingTaskletAdapter.java:56)
> ```
>
> 리팩터링으로 메서드 이름을 바꿔도 IDE 가 이 문자열은 안 바꿔 줍니다. **테스트 없이 배포하면 운영에서 처음 발견됩니다.**
> 그래서 실무에서는 어댑터보다 **람다로 서비스를 직접 호출**하는 편을 더 많이 씁니다.
>
> ```java
> .tasklet((contribution, ctx) -> { service.summarize(date); return RepeatStatus.FINISHED; }, txManager)
> ```
>
> 어댑터가 유리한 경우는 XML 설정을 유지해야 하거나, 배치 의존성을 서비스 계층에 절대 넣지 않겠다는 방침이 있을 때입니다.

또 하나 주의할 점은 **반환값이 `ExitStatus` 로 해석된다**는 것입니다. `summarize()` 가 `long` 을 돌려주면 EXIT_CODE 가 그 숫자의 문자열이 됩니다.

```
+-------------+-----------+-----------+
| STEP_NAME   | STATUS    | EXIT_CODE |
+-------------+-----------+-----------+
| reportStep  | COMPLETED | 18842196  |
+-------------+-----------+-----------+
```

Flow 분기(Step 10)에서 `on("COMPLETED")` 를 기대하고 있었다면 **분기가 조용히 안 걸립니다.** 반환 타입이 `void` 면 `ExitStatus.COMPLETED` 가 됩니다.

---

## 4-8. `SystemCommandTasklet` — 외부 명령 실행

셸 명령이나 외부 프로그램을 Step 으로 감쌉니다.

```java
@Bean
public SystemCommandTasklet archiveTasklet() {
    SystemCommandTasklet tasklet = new SystemCommandTasklet();
    tasklet.setCommand("/bin/sh", "-c", "gzip -f /tmp/batch/settlement-2025-03-01.csv");
    tasklet.setTimeout(60_000);                       // 60초. 필수는 아니지만 반드시 넣으세요.
    tasklet.setWorkingDirectory("/tmp/batch");
    tasklet.setInterruptOnCancel(true);               // Step 중단 시 프로세스도 죽임
    tasklet.setTerminationCheckInterval(1000);
    return tasklet;
}
```

**결과**
```
INFO 45633 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [archiveStep]
DEBUG 45633 --- [ystemCommandTask] o.s.b.c.s.t.SystemCommandTasklet         : Executing command: [/bin/sh, -c, gzip -f /tmp/batch/settlement-2025-03-01.csv]
INFO  45633 --- [           main] o.s.b.c.s.t.SystemCommandTasklet         : Command exited with code 0
INFO  45633 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [archiveStep] executed in 218ms
```

종료 코드가 0이 아니면 Step 이 실패합니다.

```
ERROR 45701 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step archiveStep in job archiveJob

org.springframework.batch.core.step.tasklet.SystemCommandException: Execution of system command did not finish within the timeout of 60000ms
	at org.springframework.batch.core.step.tasklet.SystemCommandTasklet.execute(SystemCommandTasklet.java:159)
	at org.springframework.batch.core.step.tasklet.TaskletStep$ChunkTransactionCallback.doInTransaction(TaskletStep.java:407)
```

> ⚠️ **함정 — `setCommand("gzip -f a.csv")` 처럼 한 문자열로 넣으면 동작하지 않습니다**
> 5.0 에서 `setCommand(String)` 이 **`setCommand(String... command)`** 로 바뀌었습니다. 4.x 는 문자열 하나를 받아 공백으로 쪼갰지만, 5.x 는 **각 인자를 배열 원소로** 넘겨야 합니다.
> 한 문자열로 넣으면 셸이 `"gzip -f a.csv"` 라는 이름의 실행 파일을 찾다가 실패합니다.
> 그리고 파이프(`|`)·리다이렉션(`>`)·와일드카드(`*`)는 셸 기능이므로, **`/bin/sh -c "…"` 로 감싸야** 동작합니다.

> 💡 **실무 팁 — `setTimeout()` 을 반드시 지정하세요**
> 기본값은 0(무제한)입니다. 외부 명령이 응답 없이 멈추면 배치도 영원히 멈춥니다. 4-4 의 무한루프와 같은 결과입니다(STATUS=STARTED 로 방치).
> 외부 시스템을 부르는 모든 Step 에는 타임아웃이 있어야 합니다.

---

## 4-9. Tasklet vs 청크 — 무엇을 언제 쓰는가

| 기준 | Tasklet | 청크(Chunk-oriented) |
|---|---|---|
| 처리 모델 | "한 덩어리 작업" 을 내가 직접 | `read → process → write` 를 프레임워크가 반복 |
| 트랜잭션 | 반복 1회 = 1 트랜잭션 (내가 나눔) | 청크 1개 = 1 트랜잭션 (프레임워크가 나눔) |
| 카운트 집계 | **직접 `StepContribution` 에 보고** | 자동 |
| 재시작 지점 | 내가 `ExecutionContext` 로 관리 | Reader 가 자동으로 관리 |
| 스킵/재시도 | 직접 구현 | `.faultTolerant().skip().retry()` (Step 11) |
| 메모리 | 내가 로딩한 만큼 | 청크 크기만큼만 |
| 코드량 | 적음 | 많음 (Reader/Processor/Writer) |

**Tasklet 이 적합한 경우**

| 사례 | 이유 |
|---|---|
| 파일 이동·압축·삭제 | 항목 단위 반복이 아니라 단발성 작업 |
| 테이블 `TRUNCATE` / 대량 `DELETE` | SQL 한 줄이면 끝. 항목을 읽어올 이유가 없음 |
| 통계 집계 (`INSERT ... SELECT` 단일 SQL) | **DB 안에서 처리하는 것이 압도적으로 빠름** |
| 외부 API 한 번 호출 (완료 알림 등) | 항목이 없음 |
| 선행 조건 검사 (원본 파일 존재 여부) | 실패 시 즉시 Job 중단 |

**청크가 적합한 경우**

| 사례 | 이유 |
|---|---|
| 항목마다 다른 계산이 필요 (등급별 수수료율) | Processor 가 항목 단위로 개입 |
| 항목 단위 스킵/재시도가 필요 | `faultTolerant` 가 항목 단위로 동작 |
| 데이터가 메모리에 다 안 들어감 | 청크 크기만큼만 로딩 |
| 소스와 타깃이 다름 (DB → 파일, 파일 → DB) | Reader/Writer 조합 |
| 중간 실패 후 이어서 재시작 | Reader 가 위치를 기억 |

> 💡 **실무 팁 — "한 SQL 로 되는 일에 청크를 쓰지 마세요"**
> 이 코스의 정산 로직은 청크로 만듭니다(Step 05~08). 하지만 정직하게 말하면, 이 정산은
> `INSERT INTO settlement SELECT ... FROM orders JOIN customers WHERE status='COMPLETED'` 라는 **SQL 한 줄**로도 됩니다.
> 그리고 그게 **훨씬 빠릅니다.** 4-0 에서 그 SQL 로 70,000행을 만드는 데 1초도 안 걸렸습니다.
>
> 그럼에도 청크를 쓰는 이유는 이런 요구가 붙을 때입니다.
> - 항목마다 외부 API 를 호출해야 한다 (SQL 로 불가능)
> - 특정 항목만 스킵하고 나머지는 처리해야 한다 (SQL 은 전부 아니면 전무)
> - 어디까지 처리했는지 재시작 지점이 필요하다
> - 진행률을 항목 단위로 보고해야 한다
>
> **"SQL 한 줄로 되는가?"를 먼저 물으세요.** 된다면 Tasklet 이 정답입니다.
> 배치 프레임워크를 배웠다고 모든 것을 청크로 만드는 것은, 망치를 든 사람이 모든 것을 못으로 보는 것과 같습니다.

---

## 4-10. 실전 — settlement 정리 Tasklet

이제 전부 합칩니다. 요구사항은 이렇습니다.

1. 파라미터 `settleDate` 가 주어지면 **그 날짜의 정산만**, 주어지지 않으면 **전체** 삭제
2. 한 번에 10,000행씩 나눠 지워 락을 오래 잡지 않는다
3. 삭제 건수를 `StepContribution` 에 보고한다
4. 진행이 없으면 반드시 종료한다 (무한루프 방지)
5. 반복 상한을 둔다 (이중 안전장치)

```java
package com.example.batch.step04;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public class SettlementCleanupTasklet implements Tasklet {

    private static final int BATCH_SIZE = 10_000;
    private static final int MAX_ITERATIONS = 1_000;   // 10,000 × 1,000 = 1천만 행이면 충분

    private final JdbcTemplate jdbcTemplate;
    private final String settleDate;   // null 이면 전체 삭제

    public SettlementCleanupTasklet(JdbcTemplate jdbcTemplate, String settleDate) {
        this.jdbcTemplate = jdbcTemplate;
        this.settleDate = settleDate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext ctx = chunkContext.getStepContext()
                .getStepExecution().getExecutionContext();

        int iteration = ctx.getInt("iteration", 0) + 1;
        if (iteration > MAX_ITERATIONS) {
            throw new IllegalStateException(
                    "최대 반복 %d 회를 초과했습니다. 종료 조건을 점검하세요.".formatted(MAX_ITERATIONS));
        }

        int deleted = (settleDate == null)
                ? jdbcTemplate.update("DELETE FROM settlement LIMIT " + BATCH_SIZE)
                : jdbcTemplate.update("DELETE FROM settlement WHERE settle_date = ? LIMIT " + BATCH_SIZE,
                                      settleDate);

        int total = ctx.getInt("deletedTotal", 0) + deleted;
        ctx.putInt("iteration", iteration);
        ctx.putInt("deletedTotal", total);

        contribution.incrementWriteCount(deleted);   // ★ 4-5 의 함정. 절대 빼먹지 마세요.

        System.out.printf("[cleanup] %2d회차 deleted=%,d (누적 %,d)%n", iteration, deleted, total);

        if (deleted == 0) {
            contribution.setExitStatus(
                    total == 0 ? new ExitStatus("NOTHING_TO_DELETE") : ExitStatus.COMPLETED);
            return RepeatStatus.FINISHED;            // ★ 진행이 없으면 반드시 종료
        }
        return RepeatStatus.CONTINUABLE;
    }
}
```

Step 과 Job 을 엮습니다. `@StepScope` 로 파라미터를 늦은 바인딩합니다(Step 03 의 3-10).

```java
@Bean
@StepScope
public SettlementCleanupTasklet cleanupTasklet(
        JdbcTemplate jdbcTemplate,
        @Value("#{jobParameters['settleDate']}") String settleDate) {
    return new SettlementCleanupTasklet(jdbcTemplate, settleDate);
}

@Bean
public Step cleanupStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                        SettlementCleanupTasklet cleanupTasklet) {
    return new StepBuilder("cleanupStep", jobRepository)
            .tasklet(cleanupTasklet, txManager)
            .build();
}

@Bean
public Job cleanupJob(JobRepository jobRepository, Step cleanupStep) {
    return new JobBuilder("cleanupJob", jobRepository)
            .start(cleanupStep)
            .build();
}
```

### 하루치만 삭제

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=cleanupJob,settleDate=2025-03-01"
```

**결과**
```
INFO 45812 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=cleanupJob]] launched with the following parameters: [{'settleDate':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 45812 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [cleanupStep]
[cleanup]  1회차 deleted=389 (누적 389)
[cleanup]  2회차 deleted=0 (누적 389)
INFO 45812 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [cleanupStep] executed in 62ms
INFO 45812 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=cleanupJob]] completed with the following parameters: [{'settleDate':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED] in 91ms
```

389건. Step 03 에서 센 하루치 COMPLETED 건수와 정확히 같습니다. 2회차에서 0건이 나와 종료했습니다.

**2회차가 반드시 필요합니다.** 1회차에 `deleted(389) < BATCH_SIZE(10000)` 이니 더 없다는 걸 추측할 수는 있지만, 그런 추측은 `LIMIT` 이 정확히 떨어지는 경우(예: 정확히 10,000행)에 틀립니다. **"0건이 나올 때까지" 가 유일하게 안전한 종료 조건입니다.**

### 전체 삭제 — 70,000행

`settleDate` 를 **아예 넘기지 않으면** `@Value("#{jobParameters['settleDate']}")` 가 `null` 이 되어 전체 삭제 분기를 탑니다. 다만 파라미터가 하나도 없으면 앞선 실행과 JobInstance 가 겹칠 수 있으므로, 인스턴스 구분용으로 `mode=full` 을 줍니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=cleanupJob,mode=full"
```

**결과**
```
INFO 45903 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=cleanupJob]] launched with the following parameters: [{'mode':'{value=full, type=class java.lang.String, identifying=true}'}]
INFO 45903 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [cleanupStep]
[cleanup]  1회차 deleted=10,000 (누적 10,000)
[cleanup]  2회차 deleted=10,000 (누적 20,000)
[cleanup]  3회차 deleted=10,000 (누적 30,000)
[cleanup]  4회차 deleted=10,000 (누적 40,000)
[cleanup]  5회차 deleted=10,000 (누적 50,000)
[cleanup]  6회차 deleted=10,000 (누적 60,000)
[cleanup]  7회차 deleted=9,611 (누적 69,611)
[cleanup]  8회차 deleted=0 (누적 69,611)
INFO 45903 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [cleanupStep] executed in 1s412ms
INFO 45903 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=cleanupJob]] completed with the following parameters: [{'mode':'{value=full, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED] in 1s448ms
```

69,611행입니다. 70,000 이 아닌 이유는 **앞에서 2025-03-01 의 389행을 이미 지웠기** 때문입니다. `69,611 + 389 = 70,000`. 숫자가 맞습니다.

메타데이터를 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, EXIT_CODE, COMMIT_COUNT, WRITE_COUNT, ROLLBACK_COUNT
  FROM BATCH_STEP_EXECUTION WHERE STEP_NAME='cleanupStep' ORDER BY STEP_EXECUTION_ID;
SELECT COUNT(*) AS remaining FROM settlement;"
```

**결과**
```
+-------------+-----------+-----------+--------------+-------------+----------------+
| STEP_NAME   | STATUS    | EXIT_CODE | COMMIT_COUNT | WRITE_COUNT | ROLLBACK_COUNT |
+-------------+-----------+-----------+--------------+-------------+----------------+
| cleanupStep | COMPLETED | COMPLETED |            2 |         389 |              0 |
| cleanupStep | COMPLETED | COMPLETED |            8 |       69611 |              0 |
+-------------+-----------+-----------+--------------+-------------+----------------+
+-----------+
| remaining |
+-----------+
|         0 |
+-----------+
```

**`COMMIT_COUNT` 가 반복 횟수와 정확히 일치**하고(2회 / 8회), `WRITE_COUNT` 가 실제 삭제 건수와 일치합니다. 4-5 에서 `incrementWriteCount()` 를 호출했기 때문에 모니터링이 진실을 말합니다.

> ⚠️ **함정 — `DELETE ... LIMIT` 에 `ORDER BY` 가 없으면 삭제 순서가 보장되지 않습니다**
> 지금은 "전부 지우는" 것이라 순서가 문제가 되지 않습니다. 하지만 **일부만 지우고 멈출 수 있는** 상황(예: 상한 초과로 예외)에서는 "무엇이 남았는지"를 예측할 수 없습니다.
> 재시작 가능한 정리 배치를 만들 거라면 `DELETE FROM settlement WHERE settlement_id > ? ORDER BY settlement_id LIMIT 10000` 처럼 **커서 컬럼 기준**으로 지우고, 마지막 id 를 `ExecutionContext` 에 저장하세요.
> `LIMIT` 만 쓰는 방식은 "전부 지운다"는 전제에서만 안전합니다.

실습을 마쳤으니 다음 스텝을 위해 데이터를 복구해 둡니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "
SELECT COUNT(*) FROM settlement;"   -- 0 이면 정상. Step 05 가 여기서부터 채웁니다.
```

---

## 정리

| 개념 | 핵심 |
|---|---|
| `Tasklet` | `execute(StepContribution, ChunkContext)` 하나뿐인 함수형 인터페이스 |
| 청크와의 관계 | 청크 처리도 결국 `ChunkOrientedTasklet` 이라는 Tasklet 구현체 |
| 5.x 시그니처 | `.tasklet(tasklet, txManager)` — **트랜잭션 매니저 필수** |
| `FINISHED` | 커밋하고 Step 종료. `null` 도 동일 |
| `CONTINUABLE` | 커밋하고 **다시 호출**. 반복 1회 = 트랜잭션 1회 = `COMMIT_COUNT` 1 |
| 무한루프 | 종료 조건이 없으면 영원히 돎. 죽여도 `STATUS=STARTED` 로 남아 재실행 불가 |
| 안전한 종료 조건 | **"이번에 처리한 건수가 0이면 종료"** + 반복 상한 예외 |
| `StepContribution` | 처리 건수를 **직접 보고**해야 함. 안 하면 `WRITE_COUNT=0` 으로 모니터링이 거짓말 |
| `ChunkContext` | JobParameters / Step·Job `ExecutionContext` / StepExecution 접근 경로 |
| 반복 상태 | 인스턴스 필드 ❌ (싱글턴·재시작 문제) → `ExecutionContext` ⭕ |
| `MethodInvokingTaskletAdapter` | 기존 서비스 메서드 재사용. **메서드명이 문자열이라 오타를 런타임에 발견** |
| 어댑터 반환값 | `ExitStatus` 로 해석됨. `long` 을 돌려주면 EXIT_CODE 가 숫자가 되어 분기가 깨짐 |
| `SystemCommandTasklet` | 외부 명령. 5.x 는 `setCommand(String...)` 가변인자. **`setTimeout()` 필수** |
| Tasklet 이 적합 | 파일 이동, TRUNCATE/대량 DELETE, 단일 SQL 집계, 외부 API 1회, 선행 조건 검사 |
| 청크가 적합 | 항목별 계산·스킵·재시도, 메모리 초과 데이터, 소스≠타깃, 재시작 지점 필요 |
| 판단 기준 | **"SQL 한 줄로 되는가?" 를 먼저 묻는다.** 되면 Tasklet |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 무한루프가 되는 Tasklet 을 찾아 최소 수정으로 고치기
2. 반복 3회짜리 Tasklet 의 `COMMIT_COUNT` / `WRITE_COUNT` / 트랜잭션 로그 줄 수 예측
3. 인스턴스 필드로 상태를 들고 있는 Tasklet 을 `ExecutionContext` 기반으로 리팩터링
4. `WRITE_COUNT` 가 0으로 남는 Tasklet 을 고치고, 왜 이것이 "조용한 버그"인지 서술
5. 주어진 요구 6개를 Tasklet / 청크로 분류하고 근거 적기
6. `orders` 를 일자별로 집계해 `daily_summary` 에 넣는 작업을 **단일 SQL Tasklet** 으로 구현

---

## 다음 단계

Tasklet 으로 "한 덩어리 작업"을 다루는 법을 익혔습니다. 하지만 70,000건의 주문을 하나씩 읽어 등급별 수수료를 계산하고 정산 테이블에 넣는 일은, Tasklet 안에서 직접 루프를 돌리면 메모리도 트랜잭션도 감당이 안 됩니다.
다음 스텝에서 **청크 지향 처리**의 구조를 열어 봅니다. `chunk(1000, txManager)` 의 1000이 정확히 무엇을 나누는지, 70,000건이 왜 정확히 70청크가 되는지, 그리고 청크 크기를 바꿨을 때 커밋 횟수와 처리 시간이 어떻게 달라지는지를 실측합니다.

→ [Step 05 — 청크 지향 처리](../step-05-chunk/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 4-0 의 SQL 로 `settlement` 70,000행을 만들어 두고, `Practice.java` 를 `src/main/java/com/example/batch/step04/` 에 놓은 뒤 4-1 부터 순서대로 실행합니다. 그다음 `Exercise.java` 의 6문제를 풀고 `Solution.java` 로 대조합니다. **4-10 을 실행하면 `settlement` 가 비워지므로, 다시 실습하려면 4-0 의 INSERT 를 한 번 더 돌려야 합니다.**

### Practice.java

본문 4-1 ~ 4-10 의 모든 Tasklet 과 Step/Job 정의를 `// [4-3]` 형태의 절 번호 주석과 함께 담았습니다.

- 최상위 `Practice` 클래스 안에 `HelloConfig`, `RepeatConfig`, `AdapterConfig`, `SystemCommandConfig`, `CleanupConfig` 다섯 개의 `static class` 가 각각 `@Configuration` 입니다. 절별로 실험하려면 나머지의 `@Configuration` 을 주석 처리하세요.
- **`[4-4]` 의 `infiniteStep` 은 기본적으로 주석 처리되어 있습니다.** 무한루프를 직접 보고 싶다면 주석을 풀되, **Ctrl+C 로 죽인 뒤 반드시 파일 하단 주석의 복구 SQL 을 실행**해야 합니다. 안 하면 `STATUS=STARTED` 가 남아 그 Job 을 다시 실행할 수 없습니다.
- `[4-3]` 의 `CountingTasklet` 은 상태를 **일부러** 인스턴스 필드에 둡니다. 같은 JVM 에서 두 번 실행하면 두 번째는 1회차 만에 끝나는 것을 확인하기 위해서입니다. 올바른 형태는 바로 아래 `[4-6]` 의 `ContextCountingTasklet` 입니다. 두 개를 나란히 놓고 비교하세요.
- `[4-7]` 의 `SettlementReportService` 는 Spring Batch 의존성이 하나도 없는 평범한 `@Component` 입니다. 이것이 `MethodInvokingTaskletAdapter` 의 존재 이유입니다. 어댑터 Bean 정의 바로 위 주석에 **메서드명 오타 시 나는 예외**를 적어 두었으니, 한 글자를 틀려 보고 실행해 보길 권합니다.
- `[4-8]` 의 `SystemCommandTasklet` 은 `/tmp/batch` 디렉터리를 전제로 합니다. 실행 전에 `mkdir -p /tmp/batch && touch /tmp/batch/settlement-2025-03-01.csv` 를 해 두세요. 없으면 `gzip` 이 종료 코드 1로 실패하며 Step 이 FAILED 됩니다(이것도 유익한 실습입니다).
- `[4-10]` 의 `SettlementCleanupTasklet` 이 이 스텝의 결론입니다. 무한루프 방지 조건 두 개(`deleted == 0` 종료, `MAX_ITERATIONS` 예외), `ExecutionContext` 상태 저장, `incrementWriteCount` 보고가 모두 들어 있습니다. 셋 중 하나라도 빼면 어떤 문제가 생기는지 주석에 적어 두었습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1** 의 `Ex1Tasklet` 은 실행하면 진짜로 무한루프에 빠집니다. **실행하기 전에** 코드를 읽고 원인을 찾으세요. 실행해서 확인하고 싶다면 Ctrl+C 준비를 하고, 이후 복구 SQL 을 잊지 마세요.
- **문제 2** 는 코드를 쓰지 않고 예측만 하는 문제입니다. 예측을 문자열로 적은 뒤 실행하고 `BATCH_STEP_EXECUTION` 을 SELECT 해서 맞춰 보세요. 트랜잭션 로그 줄 수를 세려면 `DataSourceTransactionManager` 로거를 DEBUG 로 올려야 합니다.
- **문제 3** 은 리팩터링입니다. 필드 `private int processed` 를 없애고 `ExecutionContext` 의 `getInt`/`putInt` 로 바꾸면 됩니다. 키 이름은 자유이지만, `ExecutionContext` 는 Step 범위와 Job 범위가 다르다는 점을 기억하세요(문제는 Step 범위로 푸는 것이 맞습니다).
- **문제 4** 는 고치는 것보다 **왜 위험한지 서술하는 부분**이 본체입니다. "데이터는 맞는데 지표가 틀린" 상황이 배치 운영에서 어떤 결과를 낳는지 두세 문장으로 적으세요.
- **문제 5** 는 6개 요구를 분류하는 표 채우기입니다. 답이 갈릴 수 있는 항목이 하나 있으므로(대량 UPDATE), 근거를 반드시 함께 적으세요.
- **문제 6** 은 실제 코딩입니다. `daily_summary` 테이블 DDL 이 문제 주석에 있으니 먼저 만들고 시작하세요. `INSERT ... SELECT` 한 방이면 되며, `jdbcTemplate.update()` 의 반환값을 그대로 `incrementWriteCount` 에 넘기면 됩니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 의 최소 수정은 `return RepeatStatus.CONTINUABLE;` 을 `return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;` 로 바꾸는 한 줄입니다. 여기에 더해 "왜 `SELECT COUNT(*) > 0` 으로 판정하면 안 되는가" — FK 나 트리거로 지워지지 않는 행이 하나만 있어도 영원히 도는 시나리오 — 를 설명합니다.
- **정답 2** 는 `COMMIT_COUNT=3`, `WRITE_COUNT=1500`(500×3), `Creating new transaction` 3줄, `Committing JDBC transaction` 3줄입니다. 반복·트랜잭션·커밋카운트가 **1:1:1 로 대응**한다는 것이 이 문제의 요지입니다.
- **정답 3** 은 리팩터링 코드와 함께, 인스턴스 필드가 위험한 두 가지 이유를 구분해 설명합니다. ① Bean 이 싱글턴이라 **같은 JVM 내 두 번째 실행에서 값이 이어진다** ② 재시작 시 값이 초기화되어 **이미 처리한 구간을 다시 처리한다**. 두 번째가 정산 배치에서는 중복 정산으로 이어집니다.
- **정답 4** 는 `contribution.incrementWriteCount(deleted)` 한 줄을 추가하는 것이며, 서술 부분에서 "데이터는 맞는데 지표가 0" 인 상황의 실제 피해를 셋으로 정리합니다. ① 진짜 0건 장애와 구분 불가 ② SLA·처리량 리포트가 무의미 ③ "일 안 하는 배치" 로 오인되어 삭제 후보가 됨.
- **정답 5** 의 분류에서 갈리는 항목은 "10만 건의 `status` 를 일괄 UPDATE" 입니다. 단일 UPDATE 로 되니 Tasklet 이 정답이지만, **락 시간이 길어지므로 `CONTINUABLE` + `LIMIT` 로 쪼개는 Tasklet** 이어야 한다는 단서를 답니다. 청크로 만드는 것은 오버엔지니어링입니다.
- **정답 6** 은 `INSERT INTO daily_summary ... SELECT ... GROUP BY DATE(ordered_at)` 단일 SQL 이며, 180일치가 한 번에 들어가 `WRITE_COUNT=180` 이 됩니다. 같은 일을 청크로 만들면 Reader 로 100,000건을 읽어 Processor 에서 집계해야 하는데, **집계는 스트리밍으로 안 되므로 결국 전부 메모리에 올려야 한다**는 것 — 즉 청크의 최대 장점(메모리 상한)이 무너진다는 점을 설명합니다. 이 스텝의 "SQL 한 줄로 되는가?" 판단 기준을 코드로 확인하는 문제입니다.

```java file="./Solution.java"
```
