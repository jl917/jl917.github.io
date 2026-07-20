# Step 14 — 운영: 스케줄링 · 모니터링 · 최종 프로젝트

> **학습 목표**
> - 부팅 시 자동 실행을 끄고 `@Scheduled` / Quartz / CLI 세 가지 방식으로 Job 을 띄운다
> - 종료 코드로 크론·에어플로우에 성공·실패를 정확히 전달한다
> - **중복 실행을 막는 3단 방어**를 설계하고, JobInstance 중복 차단만으로는 왜 부족한지 확인한다
> - 메타데이터 테이블만으로 실패 분석·성능 추이·재시작 이력을 조회한다
> - Micrometer 지표를 Prometheus 로 노출하고 배치 전용 알림 규칙을 만든다
> - **Step 01~13 의 모든 요소를 하나로 조립해 일일 주문 정산 배치를 완성한다**
>
> **선행 스텝**: [Step 13 — 병렬 처리와 확장](../step-13-scaling/)
> **예상 소요**: 120분

---

## 14-0. 실습 준비

[Step 12](../step-12-listeners/) 에서 심은 불량 데이터를 복구하고 시작합니다. 이 스텝의 종합 실습은 **깨끗한 데이터**를 전제로 합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t <<'SQL'
UPDATE orders o
  JOIN (SELECT order_id, 1000 + (order_id % 977) * 100 AS amt FROM orders) s
    ON o.order_id = s.order_id
   SET o.amount = s.amt
 WHERE o.amount < 0;
TRUNCATE TABLE settlement;
SELECT COUNT(*) AS bad_amount FROM orders WHERE amount < 0;
SQL
```

**결과**
```
+------------+
| bad_amount |
+------------+
|          0 |
+------------+
```

---

## 14-1. 자동 실행을 끈다

지금까지는 `JobLauncherApplicationRunner` 가 부팅 시 모든 Job 을 실행했습니다. 학습에는 편했지만 **운영에서는 위험합니다.**

```yaml
spring:
  batch:
    job:
      enabled: false        # 부팅했다고 Job 이 돌지 않습니다
```

> ⚠️ **함정 — `enabled: true` 인 채로 배포하면 재시작할 때마다 정산이 돕니다**
> 쿠버네티스에서 파드가 OOM 으로 재시작되면 어떻게 될까요? 애플리케이션이 다시 뜨고, **정산 배치가 다시 돕니다.**
> 파드가 크래시루프에 빠지면 정산이 5분 동안 40번 돕니다. 멱등하지 않은 Writer 라면 그 시점에 데이터가 어떻게 되어 있을지 아무도 모릅니다.
> 운영 배포물에는 **반드시 `enabled: false`** 로 두고, 실행 트리거를 명시적으로 만드십시오.

이제 Job 을 실행하는 방법은 세 가지입니다.

| 방식 | 프로세스 | 언제 쓰나 |
|---|---|---|
| `@Scheduled` + `JobLauncher` | **상주** | 배치 전용 서버가 계속 떠 있을 때 |
| Quartz | **상주** (스케줄 영속) | 스케줄을 DB 로 관리·변경해야 할 때 |
| CLI (`java -jar`) | **일회성** | 크론·에어플로우·쿠버네티스 Job |

---

## 14-2. `@Scheduled` — 가장 단순한 스케줄

```java
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final JobLauncher jobLauncher;
    private final Job dailySettlementJob;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")     // 매일 새벽 2시
    public void runDailySettlement() throws Exception {
        LocalDate targetDate = LocalDate.now().minusDays(1);   // 어제치

        JobParameters params = new JobParametersBuilder()
                .addString("date", targetDate.toString())      // identifying
                .addLong("launchedAt", System.currentTimeMillis(), false)  // non-identifying
                .toJobParameters();

        jobLauncher.run(dailySettlementJob, params);
    }
}
```

**결과**
```
INFO 45102 --- [   scheduling-1] c.e.b.step14.SettlementScheduler          : 일일 정산 시작: date=2025-03-01
INFO 45102 --- [   scheduling-1] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=dailySettlementJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}','launchedAt':'{value=1741050000123, type=class java.lang.Long, identifying=false}'}]
INFO 45102 --- [   scheduling-1] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
INFO 45102 --- [   scheduling-1] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 421ms
INFO 45102 --- [   scheduling-1] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=dailySettlementJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 467ms
```

하루치(389건)라 421ms 만에 끝납니다.

> ⚠️ **함정 — `@Scheduled` 는 기본적으로 단일 스레드입니다**
> 스프링의 기본 `TaskScheduler` 는 **풀 크기가 1** 입니다. 스케줄된 작업이 두 개인데 하나가 30분 걸리면, 나머지 하나는 **30분 동안 실행되지 않습니다.**
> 정산 배치가 밀려서 그날의 다른 배치가 통째로 건너뛰어지는 사고가 여기서 납니다. 그리고 로그에는 아무 흔적이 없습니다 — 그냥 안 돌 뿐입니다.
> ```yaml
> spring:
>   task:
>     scheduling:
>       pool:
>         size: 5
> ```
> 그리고 **`jobLauncher.run()` 은 동기 호출**입니다. 스케줄러 스레드가 배치가 끝날 때까지 붙잡힙니다. 긴 배치라면 별도 `TaskExecutor` 를 쓰거나 CLI 방식으로 분리하십시오.

> 💡 **실무 팁 — `launchedAt` 을 non-identifying 으로 넣는 이유**
> `date` 만 파라미터로 주면 같은 날짜로 재실행할 수 없습니다([Step 03](../step-03-job-parameters/)). 그렇다고 `RunIdIncrementer` 를 쓰면 **중복 실행 방지가 통째로 무력화**됩니다.
> `launchedAt` 을 `identifying=false` 로 넣으면 `JOB_KEY` 에 영향을 주지 않으므로 중복은 여전히 차단되면서, 메타데이터에는 "언제 실행했는지"가 기록됩니다. 감사 추적에 유용합니다.

---

## 14-3. Quartz — 스케줄을 DB 로 관리한다

`@Scheduled` 의 cron 은 코드에 박혀 있어 바꾸려면 재배포해야 합니다. Quartz 는 스케줄을 DB 에 저장합니다.

```java
@Configuration
public class QuartzConfig {

    @Bean
    public JobDetail settlementJobDetail() {
        return JobBuilder.newJob(SettlementQuartzJob.class)
                .withIdentity("settlementQuartzJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger settlementTrigger(JobDetail settlementJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(settlementJobDetail)
                .withIdentity("settlementTrigger")
                .withSchedule(CronScheduleBuilder
                        .cronSchedule("0 0 2 * * ?")
                        .inTimeZone(TimeZone.getTimeZone("Asia/Seoul"))
                        // ⚠️ 놓친 실행을 어떻게 할 것인가 — 아래 함정 참조
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
```

```java
@Component
@RequiredArgsConstructor
public class SettlementQuartzJob extends QuartzJobBean {

    private final JobLauncher jobLauncher;
    private final Job dailySettlementJob;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        // ...JobParameters 를 만들어 jobLauncher.run(...)
    }
}
```

```yaml
spring:
  quartz:
    job-store-type: jdbc          # 스케줄을 DB 에 저장
    jdbc:
      initialize-schema: always
    properties:
      org.quartz.jobStore.isClustered: true      # 여러 서버에서 하나만 실행
      org.quartz.scheduler.instanceId: AUTO
```

> ⚠️ **함정 — misfire 정책을 정하지 않으면 서버를 켜자마자 밀린 배치가 몰려 돕니다**
> 서버가 3일간 내려가 있었다고 합시다. 그동안 새벽 2시가 세 번 지났습니다. 서버를 다시 켜면?
> Quartz 의 기본 misfire 정책은 **놓친 실행을 즉시 따라잡는 것**입니다. 켜자마자 정산 배치가 **세 번 연달아** 돕니다.
> - `withMisfireHandlingInstructionDoNothing()` — 놓친 건 버리고 다음 정규 시각을 기다립니다. **일일 배치에는 대개 이게 맞습니다.**
> - `withMisfireHandlingInstructionFireAndProceed()` — 한 번만 즉시 실행하고 정상 스케줄로 복귀합니다.
> 놓친 날짜를 정말 처리해야 한다면, 자동으로 몰아 돌리지 말고 **운영자가 날짜를 지정해 수동 실행**하는 것이 안전합니다.

`isClustered: true` 가 중요합니다. 배치 서버를 2대로 늘렸을 때 **Quartz 가 DB 락으로 하나만 실행하도록** 보장합니다. 이것이 중복 실행 방어의 한 축입니다(14-5).

---

## 14-4. CLI 실행과 종료 코드

가장 운영 친화적인 방식입니다. 프로세스가 뜨고, 일하고, 죽습니다.

```bash
./gradlew clean bootJar

java -jar build/libs/spring-batch5-lab-1.0.0.jar \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=dailySettlementJob \
  date=2025-03-01
```

**결과**
```
INFO 45311 --- [           main] o.s.b.a.b.JobLauncherApplicationRunner    : Running default command line with: [date=2025-03-01]
INFO 45311 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=dailySettlementJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 45311 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [settlementStep]
INFO 45311 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [settlementStep] executed in 418ms
INFO 45311 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [SimpleJob: [name=dailySettlementJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 462ms
```

```bash
echo $?
```

**결과**
```
0
```

이제 **실패시켜 봅니다.**

```bash
java -jar build/libs/spring-batch5-lab-1.0.0.jar \
  --spring.batch.job.name=dailySettlementJob date=2025-03-01
echo $?
```

**결과**
```
ERROR 45402 --- [           main] o.s.boot.SpringApplication                : Application run failed
org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException:
	A job instance already exists and is complete for identifying parameters={date=2025-03-01}.
	If you want to run this job again, change the parameters.
	...
1
```

**종료 코드 1.** 크론이나 에어플로우가 이 값을 보고 실패를 인지합니다.

> ⚠️ **함정 — `SpringApplication.exit()` 을 안 쓰면 배치가 실패해도 종료 코드가 0 입니다**
> [프로젝트 셋업](../project/) 의 `main` 을 다시 보십시오.
> ```java
> System.exit(SpringApplication.exit(SpringApplication.run(BatchLabApplication.class, args)));
> ```
> 이 감싸기가 없으면, **Job 이 `FAILED` 로 끝나도 JVM 은 0 을 반환합니다.**
> 결과가 뭘까요? 크론은 성공으로 알고 넘어갑니다. 에어플로우 태스크가 초록불로 뜹니다. **정산이 실패했는데 아무도 모릅니다.**
> 그리고 이건 로그를 봐도 안 보입니다. 로그에는 `FAILED` 가 찍혀 있는데 파이프라인만 성공으로 인식하기 때문입니다.
>
> 확인 방법은 딱 하나, **실패시켜 보고 `echo $?` 를 찍어 보는 것**입니다. 새 배치를 운영에 올리기 전 반드시 한 번 하십시오.

종료 코드를 세분화하려면 `ExitCodeGenerator` 를 씁니다.

```java
@Component
public class BatchExitCodeGenerator implements ExitCodeGenerator {

    private final JobExplorer jobExplorer;

    @Override
    public int getExitCode() {
        // 0 = 성공, 1 = 실패, 2 = 데이터 없음(정산 대상 0건) 등
        // 운영 파이프라인이 재시도 여부를 판단하는 근거가 됩니다.
        return ...;
    }
}
```

> 💡 **실무 팁 — 쿠버네티스 CronJob 이 배치에 가장 잘 맞습니다**
> 상주 프로세스가 없으니 `enabled: true` 사고가 없고, 종료 코드로 성공·실패가 그대로 전달되며, `backoffLimit` 으로 재시도 정책을 선언적으로 관리할 수 있습니다.
> `concurrencyPolicy: Forbid` 를 주면 **이전 실행이 안 끝났으면 새로 시작하지 않습니다** — 중복 실행 방어의 또 한 축입니다.

---

## 14-5. 중복 실행 방지 — 3단 방어

**이 절이 이 스텝에서 가장 중요합니다.** 정산 배치가 두 번 돌면 그건 곧 돈입니다.

### 1차 방어 — JobInstance 중복 차단 (프레임워크 제공)

같은 `(JOB_NAME, JOB_KEY)` 로 `COMPLETED` 인 JobInstance 가 있으면 실행이 거부됩니다([Step 03](../step-03-job-parameters/)).

```bash
java -jar app.jar --spring.batch.job.name=dailySettlementJob date=2025-03-01
java -jar app.jar --spring.batch.job.name=dailySettlementJob date=2025-03-01   # 두 번째
```

**결과**
```
org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException:
	A job instance already exists and is complete for identifying parameters={date=2025-03-01}.
```

훌륭합니다. 그런데 **이것만으로는 부족합니다.** 세 가지 구멍이 있습니다.

> ⚠️ **함정 — JobInstance 중복 차단이 뚫리는 세 가지 경우**
>
> **① 파라미터가 다르면 통과합니다.**
> ```bash
> java -jar app.jar ... date=2025-03-01
> java -jar app.jar ... date=2025-03-01 retry=1     # ← 다른 JOB_KEY. 통과!
> ```
> 운영자가 "재시도해 볼까" 하고 파라미터를 하나 추가하는 순간 **같은 날짜가 두 번 정산됩니다.**
>
> **② `RunIdIncrementer` 를 쓰면 아예 무력화됩니다.**
> 매 실행마다 `run.id` 가 증가하므로 `JOB_KEY` 가 항상 달라집니다. 중복 차단이 **작동하지 않습니다.**
>
> **③ 동시 실행은 못 막습니다.**
> 두 프로세스가 **정확히 동시에** 시작하면, 둘 다 "기존 JobInstance 없음"을 확인하고 둘 다 진행합니다. `BATCH_JOB_INSTANCE` 의 UNIQUE 제약이 하나를 튕겨내긴 하지만, 그건 **이미 일이 시작된 뒤**입니다.
>
> 세 경우 모두 결과는 같습니다. **정산이 두 배가 됩니다.**

### 2차 방어 — 실행 중인 Job 확인 (`JobExplorer`)

```java
public boolean isAlreadyRunning(String jobName) {
    Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
    return !running.isEmpty();
}
```

```java
@Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
public void runDailySettlement() throws Exception {
    if (isAlreadyRunning("dailySettlementJob")) {
        log.warn("이전 정산 배치가 아직 실행 중입니다. 이번 실행을 건너뜁니다.");
        return;
    }
    // ...
}
```

**결과**
```
WARN 45521 --- [   scheduling-1] c.e.b.step14.SettlementScheduler          : 이전 정산 배치가 아직 실행 중입니다. 이번 실행을 건너뜁니다.
```

> ⚠️ **함정 — `findRunningJobExecutions` 는 "죽은 실행"도 RUNNING 으로 봅니다**
> 배치 서버가 `kill -9` 로 죽으면 `BATCH_JOB_EXECUTION` 에 `STATUS='STARTED'`, `END_TIME=NULL` 인 행이 **영원히 남습니다.** 아무도 그걸 정리해 주지 않습니다.
> 그러면 다음 날부터 2차 방어가 "아직 실행 중"이라고 판단해 **정산이 영원히 건너뛰어집니다.** 중복을 막으려던 장치가 실행 자체를 막습니다.
>
> 좀비 실행을 찾는 쿼리:
> ```sql
> SELECT je.JOB_EXECUTION_ID, ji.JOB_NAME, je.START_TIME, je.STATUS
> FROM BATCH_JOB_EXECUTION je JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
> WHERE je.STATUS IN ('STARTED','STARTING') AND je.END_TIME IS NULL
>   AND je.START_TIME < NOW() - INTERVAL 6 HOUR;
> ```
> 정리:
> ```sql
> UPDATE BATCH_JOB_EXECUTION SET STATUS='FAILED', EXIT_CODE='FAILED',
>        END_TIME=NOW(), EXIT_MESSAGE='좀비 실행 수동 정리'
> WHERE JOB_EXECUTION_ID = ?;
> ```
> 이 정리를 **기동 시 자동으로 하는 코드**를 두는 것이 좋습니다. `Practice.java` 의 `ZombieCleaner` 를 참고하십시오.

### 3차 방어 — DB 락 (진짜 동시성 방어)

앞의 두 방어는 **경쟁 상태(race condition)를 막지 못합니다.** 확인과 실행 사이에 틈이 있기 때문입니다.

```java
public boolean tryAcquireLock(String jobName, LocalDate date) {
    try {
        jdbcTemplate.update("""
                INSERT INTO batch_job_lock (job_name, target_date, acquired_at, holder)
                VALUES (?, ?, NOW(), ?)
                """, jobName, date, InetAddress.getLocalHost().getHostName());
        return true;
    } catch (DuplicateKeyException e) {
        return false;       // 다른 프로세스가 이미 잡았습니다
    }
}
```

```sql
CREATE TABLE batch_job_lock (
  job_name    VARCHAR(100) NOT NULL,
  target_date DATE         NOT NULL,
  acquired_at DATETIME     NOT NULL,
  holder      VARCHAR(100) NOT NULL,
  PRIMARY KEY (job_name, target_date)      -- ← 이 제약이 방어의 실체
) ENGINE=InnoDB;
```

**PK 제약이 원자적으로 동시성을 막습니다.** 두 프로세스가 정확히 동시에 `INSERT` 해도 DB 가 하나만 통과시킵니다. 애플리케이션의 "확인 후 실행" 로직에는 틈이 있지만, **DB 의 유니크 제약에는 틈이 없습니다.**

**결과** (두 프로세스를 동시에 띄운 경우)
```
[서버 A] INFO  c.e.b.step14.JobLockService : 락 획득 성공: dailySettlementJob/2025-03-01
[서버 A] INFO  o.s.b.c.l.s.TaskExecutorJobLauncher : Job: [SimpleJob: [name=dailySettlementJob]] launched ...
[서버 B] WARN  c.e.b.step14.JobLockService : 락 획득 실패 — 다른 인스턴스가 실행 중입니다. 종료합니다.
```

### 3단 방어 요약

| 방어 | 막는 것 | 못 막는 것 |
|---|---|---|
| ① JobInstance 중복 차단 | 같은 파라미터 재실행 | 다른 파라미터·`RunIdIncrementer`·동시 실행 |
| ② `JobExplorer` RUNNING 확인 | 이전 실행이 안 끝난 경우 | 경쟁 상태, **좀비 실행에 취약** |
| ③ DB 유니크 락 | **동시 실행** (원자적) | 락 해제 실패 시 교착 |

> 💡 **실무 팁 — 그리고 마지막 방어선은 데이터 모델입니다**
> [프로젝트 셋업](../project/) 에서 `settlement.order_id` 에 UNIQUE 를 건 이유가 이것입니다.
> 위 세 방어가 전부 뚫려도, **UNIQUE 제약이 중복 정산을 막습니다.** `DuplicateKeyException` 으로 시끄럽게 실패하기 때문입니다.
> 방어를 애플리케이션 로직에만 두지 말고 **스키마에 새기십시오.** 코드는 바뀌지만 제약은 남습니다.

---

## 14-6. 메타데이터로 하는 운영 분석

`BATCH_*` 테이블만 있으면 별도 모니터링 도구 없이도 많은 것을 알 수 있습니다.

### 최근 실패한 Job

```sql
SELECT ji.JOB_NAME, je.JOB_EXECUTION_ID AS exec_id, je.START_TIME,
       je.STATUS, LEFT(je.EXIT_MESSAGE, 80) AS reason
FROM BATCH_JOB_EXECUTION je
JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
WHERE je.STATUS = 'FAILED'
ORDER BY je.START_TIME DESC
LIMIT 5;
```

**결과**
```
+---------------------+---------+---------------------+--------+----------------------------------------------------------+
| JOB_NAME            | exec_id | START_TIME          | STATUS | reason                                                   |
+---------------------+---------+---------------------+--------+----------------------------------------------------------+
| dailySettlementJob  |      47 | 2025-07-19 02:00:01 | FAILED | org.springframework.dao.DeadlockLoserDataAccessException: |
| dailySettlementJob  |      41 | 2025-07-16 02:00:02 | FAILED | java.lang.IllegalArgumentException: 정산 금액이 음수입니다 |
+---------------------+---------+---------------------+--------+----------------------------------------------------------+
```

### 실행 시간 추이 — 느려지고 있는가

```sql
SELECT DATE(je.START_TIME) AS d,
       COUNT(*) AS runs,
       ROUND(AVG(TIMESTAMPDIFF(SECOND, je.START_TIME, je.END_TIME)), 1) AS avg_sec,
       MAX(TIMESTAMPDIFF(SECOND, je.START_TIME, je.END_TIME)) AS max_sec
FROM BATCH_JOB_EXECUTION je
JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
WHERE ji.JOB_NAME = 'dailySettlementJob' AND je.END_TIME IS NOT NULL
GROUP BY DATE(je.START_TIME)
ORDER BY d DESC LIMIT 7;
```

**결과**
```
+------------+------+---------+---------+
| d          | runs | avg_sec | max_sec |
+------------+------+---------+---------+
| 2025-07-20 |    1 |     0.5 |       0 |
| 2025-07-19 |    2 |     1.2 |       2 |
| 2025-07-18 |    1 |     0.4 |       0 |
| 2025-07-17 |    1 |     0.4 |       0 |
| 2025-07-16 |    2 |     0.9 |       1 |
+------------+------+---------+---------+
```

> 💡 **실무 팁 — `runs` 가 1보다 크면 그날 재시도가 있었다는 뜻입니다**
> 이 컬럼 하나로 "조용히 재시도로 넘어간 날"을 찾을 수 있습니다. 재시도해서 결국 성공했다면 알림이 안 갔을 수 있는데, 그런 날이 반복되면 근본 원인이 있는 것입니다.

### 가장 느린 Step

```sql
SELECT se.STEP_NAME,
       COUNT(*) AS runs,
       ROUND(AVG(TIMESTAMPDIFF(SECOND, se.START_TIME, se.END_TIME)), 1) AS avg_sec,
       SUM(se.READ_COUNT) AS total_read,
       SUM(se.WRITE_COUNT) AS total_write,
       SUM(se.ROLLBACK_COUNT) AS rollbacks
FROM BATCH_STEP_EXECUTION se
WHERE se.END_TIME IS NOT NULL
GROUP BY se.STEP_NAME
ORDER BY avg_sec DESC;
```

**결과**
```
+---------------------------+------+---------+------------+-------------+-----------+
| STEP_NAME                 | runs | avg_sec | total_read | total_write | rollbacks |
+---------------------------+------+---------+------------+-------------+-----------+
| settlementStep            |   12 |    31.4 |     840000 |      838800 |       120 |
| settlementPartitionMaster |    4 |    11.2 |     280000 |      280000 |         0 |
| dailySettlementStep       |   18 |     0.4 |       7002 |        7002 |         0 |
| reportStep                |   18 |     0.1 |          0 |           0 |         0 |
+---------------------------+------+---------+------------+-------------+-----------+
```

### 데이터 정합성 점검 — 매일 아침 돌릴 쿼리

```sql
SELECT se.STEP_EXECUTION_ID,
       se.READ_COUNT, se.WRITE_COUNT, se.FILTER_COUNT,
       se.READ_SKIP_COUNT + se.PROCESS_SKIP_COUNT + se.WRITE_SKIP_COUNT AS skips,
       se.READ_COUNT - se.WRITE_COUNT - se.FILTER_COUNT
         - (se.READ_SKIP_COUNT + se.PROCESS_SKIP_COUNT + se.WRITE_SKIP_COUNT) AS unexplained
FROM BATCH_STEP_EXECUTION se
WHERE se.STATUS = 'COMPLETED'
HAVING unexplained <> 0;
```

**결과**
```
Empty set (0.01 sec)
```

**빈 결과가 정상입니다.** [Step 01](../step-01-setup/) 에서 소개한 등식 `READ = WRITE + FILTER + SKIP` 을 검증하는 쿼리입니다.

> ⚠️ **함정 — 이 쿼리가 잡지 못하는 유실이 있습니다**
> [Step 13](../step-13-scaling/) 의 스레드 안전성 문제를 떠올려 보십시오. 메타데이터는 `READ_COUNT=70000, WRITE_COUNT=70000` 이라고 기록했지만 실제 테이블에는 69,213행뿐이었습니다.
> **프레임워크의 카운터는 "프레임워크가 본 것"일 뿐, 실제 저장 결과가 아닙니다.**
> 그래서 정합성 점검은 반드시 **업무 데이터 쪽에서도** 해야 합니다.
> ```sql
> SELECT (SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND DATE(ordered_at)=?) AS 대상,
>        (SELECT COUNT(*) FROM settlement WHERE settle_date=?)                        AS 정산;
> ```
> 두 숫자가 다르면 즉시 알림. 이게 최종 방어선입니다.

---

## 14-7. Micrometer 지표

`spring-boot-starter-actuator` 와 `micrometer-registry-prometheus` 가 이미 들어 있습니다([프로젝트 셋업](../project/)). Spring Batch 5 는 **자동으로** 지표를 냅니다.

```bash
curl -s localhost:8080/actuator/prometheus | grep spring_batch
```

**결과**
```
# HELP spring_batch_job_seconds Job duration
# TYPE spring_batch_job_seconds summary
spring_batch_job_seconds_count{name="dailySettlementJob",status="COMPLETED",} 18.0
spring_batch_job_seconds_sum{name="dailySettlementJob",status="COMPLETED",} 8.412
spring_batch_job_seconds_count{name="dailySettlementJob",status="FAILED",} 2.0
spring_batch_job_seconds_sum{name="dailySettlementJob",status="FAILED",} 1.104
# HELP spring_batch_step_seconds Step duration
# TYPE spring_batch_step_seconds summary
spring_batch_step_seconds_count{job_name="dailySettlementJob",name="dailySettlementStep",status="COMPLETED",} 18.0
spring_batch_step_seconds_sum{job_name="dailySettlementJob",name="dailySettlementStep",status="COMPLETED",} 7.601
# HELP spring_batch_item_read_seconds
# TYPE spring_batch_item_read_seconds summary
spring_batch_item_read_seconds_count{job_name="dailySettlementJob",step_name="dailySettlementStep",status="SUCCESS",} 7002.0
# HELP spring_batch_chunk_write_seconds
# TYPE spring_batch_chunk_write_seconds summary
spring_batch_chunk_write_seconds_count{job_name="dailySettlementJob",step_name="dailySettlementStep",status="SUCCESS",} 18.0
```

> ⚠️ **함정 — 배치 프로세스가 죽으면 지표도 함께 사라집니다**
> Prometheus 는 **주기적으로 긁어 가는(pull)** 방식입니다. CLI 로 30초 만에 끝나고 죽는 배치는 Prometheus 가 긁어 갈 기회가 없습니다. 지표가 통째로 유실됩니다.
> 그래서 **일회성 배치는 Pushgateway** 를 씁니다.
> ```java
> PushGateway pushGateway = new PushGateway("pushgateway:9091");
> pushGateway.pushAdd(registry.getPrometheusRegistry(), "batch_settlement");
> ```
> 상주 프로세스(`@Scheduled`/Quartz)라면 pull 로 충분합니다. **실행 방식에 따라 지표 수집 방식이 달라진다**는 점을 기억하십시오.

커스텀 지표를 추가할 수도 있습니다.

```java
@Bean
public StepExecutionListener metricsListener(MeterRegistry registry) {
    return new StepExecutionListener() {
        @Override
        public ExitStatus afterStep(StepExecution se) {
            registry.gauge("settlement.written.amount",
                    Tags.of("date", se.getJobParameters().getString("date")),
                    fetchTotalNetAmount(se));
            return se.getExitStatus();      // Step 12 의 규칙
        }
    };
}
```

**알림 규칙 예시** (Prometheus):

```yaml
groups:
  - name: batch
    rules:
      - alert: SettlementJobFailed
        expr: increase(spring_batch_job_seconds_count{name="dailySettlementJob",status="FAILED"}[1h]) > 0
        annotations:
          summary: "정산 배치 실패"

      - alert: SettlementJobMissing
        # 새벽 2시에 돌아야 하는데 26시간째 성공 기록이 없다
        expr: time() - max(spring_batch_job_seconds_count{name="dailySettlementJob",status="COMPLETED"}) > 93600
        annotations:
          summary: "정산 배치가 돌지 않았습니다"
```

> 💡 **실무 팁 — "실패 알림"보다 "안 돌았음 알림"이 더 중요합니다**
> 실패는 시끄럽습니다. 로그도 남고 종료 코드도 1입니다. 그런데 **아예 실행되지 않은 것**은 아무 흔적이 없습니다.
> 스케줄러가 죽었거나, `enabled: false` 로 배포됐거나, 좀비 실행 때문에 건너뛰어졌거나 — 전부 "조용한 실패"입니다.
> 두 번째 알림 규칙(`SettlementJobMissing`)이 그것을 잡습니다. **배치 모니터링에서 반드시 있어야 하는 규칙입니다.**

---

## 14-8. 종합 실습 — 일일 주문 정산 배치

지금까지의 모든 요소를 하나로 조립합니다.

### 요구사항

1. 파라미터로 받은 날짜의 `COMPLETED` 주문만 정산한다
2. 고객 등급별 수수료율을 적용한다 (BRONZE 3.5% ~ VIP 2.0%)
3. 이미 정산된 주문은 건너뛴다 (재실행 안전)
4. 정산 요약을 CSV 파일로 내보낸다
5. 실패 시 재시작하면 중단 지점부터 이어간다
6. 불량 데이터는 skip 하고 별도 테이블에 기록한다
7. 정산 대상이 0건이면 리포트 Step 을 건너뛴다
8. 매일 새벽 2시에 자동 실행한다

### 흐름

```
                    ┌─────────────────────────┐
                    │  dailySettlementJob     │
                    └───────────┬─────────────┘
                                │
                    ┌───────────▼─────────────┐
                    │  ① dailySettlementStep  │  청크 500
                    │  Reader: 안티조인 페이징  │  Step 06, 11
                    │  Processor: 등급별 수수료 │  Step 07
                    │  Writer: 멱등 INSERT     │  Step 08
                    │  skip + SkipListener     │  Step 11, 12
                    └───────────┬─────────────┘
                                │ ExitStatus
                    ┌───────────▼─────────────┐
                    │  ② SettlementDecider    │  Step 10
                    └─────┬─────────────┬─────┘
                    NOTHING│             │HAS_DATA
                          │             │
                    ┌─────▼─────┐ ┌─────▼──────────┐
                    │  종료     │ │ ③ reportStep   │  Step 04
                    │  (end)    │ │ CSV 파일 출력   │  Step 08
                    └───────────┘ └────────────────┘
```

### 핵심 코드

**Reader** — 이미 정산된 주문을 안티 조인으로 제외합니다([Step 11](../step-11-fault-tolerance/) 연습문제 3의 결론).

```java
@Bean
@StepScope
public JdbcPagingItemReader<Order> dailyOrderReader(
        DataSource dataSource,
        @Value("#{jobParameters['date']}") String date) {

    MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
    provider.setSelectClause("o.order_id, o.customer_id, o.amount, o.status, o.ordered_at");
    provider.setFromClause("FROM orders o LEFT JOIN settlement s ON s.order_id = o.order_id");
    provider.setWhereClause("""
            WHERE o.status = 'COMPLETED'
              AND DATE(o.ordered_at) = :date
              AND s.order_id IS NULL
            """);
    // 유니크한 정렬 키. Step 06 의 함정을 피합니다.
    provider.setSortKeys(Map.of("o.order_id", Order.ASCENDING));

    return new JdbcPagingItemReaderBuilder<Order>()
            .name("dailyOrderReader")               // ExecutionContext 키 접두사
            .dataSource(dataSource)
            .queryProvider(provider)
            .parameterValues(Map.of("date", date))
            .pageSize(500)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

**Decider** — 정산 건수에 따라 분기합니다.

```java
public class SettlementDecider implements JobExecutionDecider {
    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
        long written = stepExecution == null ? 0 : stepExecution.getWriteCount();
        return new FlowExecutionStatus(written > 0 ? "HAS_DATA" : "NOTHING");
    }
}
```

**Job 조립**

```java
@Bean
public Job dailySettlementJob(JobRepository jobRepository,
                              Step dailySettlementStep,
                              Step reportStep,
                              SettlementDecider decider) {
    return new JobBuilder("dailySettlementJob", jobRepository)
            .listener(new SettlementJobListener())
            .start(dailySettlementStep)
            .next(decider)
                .on("HAS_DATA").to(reportStep)
            .from(decider)
                .on("NOTHING").end()
            .end()
            .build();
}
```

### 실행

```bash
java -jar build/libs/spring-batch5-lab-1.0.0.jar \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=dailySettlementJob \
  date=2025-03-01
```

**결과**
```
INFO 45812 --- [           main] c.e.b.step14.SettlementJobListener        : >>> 일일 정산 시작. date=2025-03-01
INFO 45812 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=dailySettlementJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 45812 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [dailySettlementStep]
INFO 45812 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [dailySettlementStep] executed in 412ms
INFO 45812 --- [           main] o.s.batch.core.job.SimpleStepHandler      : Executing step: [reportStep]
INFO 45812 --- [           main] c.e.b.step14.ReportTasklet                : 리포트 생성 완료: output/settlement-2025-03-01.csv (390줄)
INFO 45812 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [reportStep] executed in 38ms
INFO 45812 --- [           main] c.e.b.step14.SettlementJobListener        : >>> 일일 정산 종료. status=COMPLETED, 소요=497ms
INFO 45812 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=dailySettlementJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 497ms
```

**검증**

```sql
SELECT COUNT(*) AS 정산건수, SUM(gross_amount) AS 총매출,
       SUM(fee_amount) AS 총수수료, SUM(net_amount) AS 총정산액
FROM settlement WHERE settle_date = '2025-03-01';
```

**결과**
```
+----------+-------------+------------+-------------+
| 정산건수 | 총매출      | 총수수료   | 총정산액    |
+----------+-------------+------------+-------------+
|      389 | 19359500.00 |  539302.00 | 18820198.00 |
+----------+-------------+------------+-------------+
```

**389건.** [프로젝트 셋업](../project/) 에서 계산한 "하루 약 555건 중 COMPLETED 389건"과 정확히 일치합니다.

**재실행 안전성 확인** — 파라미터를 바꿔 같은 날짜를 다시 돌려 봅니다.

```bash
java -jar app.jar ... date=2025-03-01 attempt=2
```

**결과**
```
INFO 45901 --- [           main] o.s.batch.core.step.AbstractStep          : Step: [dailySettlementStep] executed in 21ms
INFO 45901 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher       : Job: [FlowJob: [name=dailySettlementJob]] completed with ... status: [COMPLETED] in 63ms
```

```sql
SELECT COUNT(*) FROM settlement WHERE settle_date = '2025-03-01';
```

**결과**
```
+----------+
| COUNT(*) |
+----------+
|      389 |
+----------+
```

**여전히 389건입니다.** 안티 조인 Reader 가 이미 정산된 주문을 아예 읽지 않았으므로 `read_count = 0` 이고, `NOTHING` 분기로 리포트 Step 도 건너뛰었습니다.

> 💡 **이것이 이 코스가 도달하려던 지점입니다**
> 1차 방어(JobInstance)가 파라미터 변경으로 뚫려도, **데이터 모델과 Reader 쿼리가 중복을 막습니다.**
> 방어를 한 겹에만 두지 마십시오. 프레임워크 · 애플리케이션 로직 · 스키마 제약 세 곳에 두면, 하나가 뚫려도 정산은 두 배가 되지 않습니다.

---

## 14-9. 코스 전체 회고 — 각 스텝의 핵심 함정

이 코스는 처음부터 하나를 말해 왔습니다. **문법 에러는 금방 고치지만, 에러 없이 조용히 틀리는 코드가 진짜 위험합니다.**

| Step | 핵심 함정 | 증상 |
|---|---|---|
| [01](../step-01-setup/) | 종료 코드를 안 넘기면 실패해도 `0` | 크론이 성공으로 인식 |
| [02](../step-02-job-step/) | `@EnableBatchProcessing` 이 Boot 자동설정을 끔 | Job 이 조용히 안 돎 |
| [03](../step-03-job-parameters/) | `--date=` 의 `--` 하나 | 파라미터 누락 → 0건 정산 후 `COMPLETED` |
| [04](../step-04-tasklet/) | `CONTINUABLE` 무한루프 | `STATUS=STARTED` 잔존 → 재실행 봉쇄 |
| [05](../step-05-chunk/) | 롤백 단위는 아이템이 아니라 **청크** | 25,501에서 실패 → 25,000건만 남음 |
| [06](../step-06-item-reader/) | 페이징 `sortKeys` 가 유니크하지 않음 | 70,000 중 **67,207건**만 읽고 `COMPLETED` |
| [07](../step-07-item-processor/) | `CompositeItemProcessor` 타입 미검증 | 런타임 `ClassCastException`, 최악엔 조용히 틀린 값 |
| [08](../step-08-item-writer/) | `ClassifierCompositeItemWriter` 는 `ItemStream` 미구현 | 파일이 **0바이트** |
| [09](../step-09-execution-context/) | `@Bean` 리턴 타입을 인터페이스로 선언 | 프록시가 `ItemStream` 을 잃어 **재시작 파손** |
| [10](../step-10-flow-control/) | `.on("FAILED").to(recovery).end()` | Step 은 실패했는데 **Job 은 `COMPLETED`** |
| [11](../step-11-fault-tolerance/) | skip 하나가 청크를 스캔 모드로 | 6.108초 → **34.712초** |
| [12](../step-12-listeners/) | `afterJob` 의 예외는 삼켜짐 | 알림 실패 → **아무도 실패를 모름** |
| [13](../step-13-scaling/) | 상태 있는 Reader + 멀티스레드 | 3.3배 빨라지고 **787건 유실** |
| [14](../step-14-operations/) | 중복 실행 방어가 한 겹뿐 | 정산이 **두 배** |

공통점이 보입니까? **열네 개 중 열두 개가 "에러 없이 끝나는" 사고입니다.** 배치가 `COMPLETED` 로 끝났다는 사실은 아무것도 보장하지 않습니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `spring.batch.job.enabled` | 운영에서는 **`false`**. 재시작 때마다 배치가 도는 사고 방지 |
| `@Scheduled` | 기본 스케줄러 풀 크기가 **1**. 늘리지 않으면 배치가 서로를 막음 |
| Quartz | 스케줄을 DB 로 관리. **misfire 정책**을 반드시 지정 |
| CLI | 종료 코드로 성공·실패 전달. `SpringApplication.exit()` 필수 |
| 중복 방지 ① | JobInstance 중복 차단 — 파라미터 변경·동시 실행에 뚫림 |
| 중복 방지 ② | `JobExplorer` RUNNING 확인 — **좀비 실행에 취약** |
| 중복 방지 ③ | **DB 유니크 락** — 원자적, 동시성 방어의 실체 |
| 최종 방어선 | **스키마 제약** (`settlement.order_id` UNIQUE) |
| 좀비 실행 | `kill -9` 후 `STATUS='STARTED'` 영구 잔존. 기동 시 자동 정리 권장 |
| 메타데이터 분석 | 실패 원인·시간 추이·정합성 등식을 SQL 로 |
| 카운터의 한계 | 프레임워크 카운터는 "본 것"일 뿐. **업무 데이터로도 검증** |
| Micrometer | `spring_batch_job_seconds` 등 자동 노출. 일회성 배치는 **Pushgateway** |
| 알림 | 실패 알림보다 **"안 돌았음" 알림**이 더 중요 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. `enabled: false` 상태에서 특정 Job 만 CLI 로 실행하고 종료 코드 확인하기
2. 좀비 실행을 만들고(`kill -9`), 그것이 2차 방어를 어떻게 무력화하는지 재현한 뒤 정리 코드 작성하기
3. DB 락 기반 3차 방어를 구현하고, 두 프로세스를 동시에 띄워 검증하기
4. 메타데이터만으로 "최근 7일간 재시도가 있었던 날"을 찾는 쿼리 작성하기
5. "배치가 아예 돌지 않았음"을 감지하는 알림 규칙 설계하기
6. 종합 실습 Job 에 Step 하나를 추가하기 (정산 결과를 검증하고 불일치 시 실패시키는 Step)

---

## 다음 단계

**코스를 완주했습니다.**

14개 스텝에 걸쳐 Job 과 Step 의 구조부터 청크 지향 처리, 내결함성, 병렬화, 운영까지 다뤘습니다. 그리고 그 과정에서 **에러 없이 조용히 틀리는 열네 가지 방식**을 직접 재현해 봤습니다.

실무에 적용할 때의 체크리스트로 마무리합니다.

**설계 단계**
- [ ] 이 작업이 정말 배치여야 하는가? (실시간·스트리밍이 더 맞지 않는가)
- [ ] Tasklet 인가 청크 지향인가? ([Step 04](../step-04-tasklet/))
- [ ] 재시작이 필요한가? 필요하다면 Reader 가 상태를 저장하는가? ([Step 11](../step-11-fault-tolerance/))
- [ ] Writer 가 멱등한가? 두 번 돌아도 안전한가? ([Step 08](../step-08-item-writer/))
- [ ] 중복 방지를 **스키마 제약**으로도 걸었는가? ([Step 14](#14-5-중복-실행-방지--3단-방어))

**구현 단계**
- [ ] 페이징 Reader 의 `sortKeys` 가 **유니크**한가? ([Step 06](../step-06-item-reader/))
- [ ] `@StepScope` 를 쓴 빈의 리턴 타입이 **구현체**인가? ([Step 09](../step-09-execution-context/))
- [ ] `rewriteBatchedStatements=true` 가 켜져 있는가? ([Step 08](../step-08-item-writer/))
- [ ] 금액을 `BigDecimal` 로 다루는가? ([프로젝트 셋업](../project/))
- [ ] Processor 에 상태가 없는가? ([Step 07](../step-07-item-processor/), [Step 13](../step-13-scaling/))
- [ ] 리스너의 로그가 **실제로 찍히는지** 눈으로 확인했는가? ([Step 12](../step-12-listeners/))

**배포 전**
- [ ] `spring.batch.job.enabled: false` 인가?
- [ ] **일부러 실패시켜 보고 `echo $?` 가 1인지 확인**했는가?
- [ ] 실패 알림과 **"안 돌았음" 알림**을 둘 다 걸었는가?
- [ ] 정합성 검증 쿼리를 업무 데이터 쪽에서도 돌리는가?
- [ ] 좀비 실행 정리 절차가 있는가?

**운영 중**
- [ ] 실행 시간이 추세적으로 늘고 있지 않은가?
- [ ] `runs > 1` 인 날(조용한 재시도)이 반복되지 않는가?
- [ ] 성능이 부족하면 — **단일 스레드 튜닝 → 파티셔닝** 순서로 접근하는가? ([Step 13](../step-13-scaling/))

마지막으로 이 코스의 한 문장을 다시 남깁니다.

> **배치가 `COMPLETED` 로 끝났다는 사실은, 그 배치가 옳게 동작했다는 뜻이 아닙니다.**

수고하셨습니다.

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 는 14-1 ~ 14-7 의 운영 도구들과 **14-8 의 종합 실습 Job 전체**를 담고 있어 이 코스에서 가장 긴 실습 파일입니다. `Exercise.java` 의 6문제를 푼 뒤 `Solution.java` 로 대조합니다.

다른 스텝과 달리 이 스텝의 실습은 **`bootRun` 이 아니라 `bootJar` + `java -jar` 로** 하십시오. 종료 코드 확인이 실습의 절반이고, `bootRun` 은 Gradle 이 종료 코드를 가려 버리기 때문입니다.

### Practice.java

- **`[14-1]`** 부터는 `application.yml` 의 `spring.batch.job.enabled` 를 **`false`** 로 바꾼 상태를 전제합니다. 이걸 안 바꾸면 이 파일의 Job 들이 부팅 때 우르르 돌아 실습이 뒤엉킵니다. 파일 상단 주석에 바꿔야 할 설정을 모아 두었습니다.
- **`[14-2]`** 의 `SettlementScheduler` 는 cron 이 `0 0 2 * * *`(새벽 2시)로 되어 있어 실습 중에는 절대 돌지 않습니다. 테스트하려면 `@Scheduled(fixedDelay = 60000)` 으로 잠깐 바꾸거나, `runNow()` 메서드를 직접 호출하십시오. **cron 을 `0/10 * * * * *` 로 바꿔 두고 잊으면 10초마다 정산이 돕니다.**
- **`[14-5]`** 의 `JobLockService` 와 `ZombieCleaner` 가 이 스텝의 실무 핵심입니다. `ZombieCleaner` 는 `@PostConstruct` 로 기동 시 자동 실행되도록 되어 있는데, **실습 중에는 주석 처리해 두었습니다.** 연습문제 2에서 좀비를 일부러 만들어야 하는데 자동 정리가 켜져 있으면 재현이 안 되기 때문입니다.
- **`[14-8]`** 의 `DailySettlementJobConfig` 가 종합 실습입니다. Step 06 의 안티조인 Reader, Step 07 의 등급별 Processor, Step 08 의 멱등 Writer, Step 10 의 Decider 분기, Step 11 의 skip, Step 12 의 리스너가 전부 한 파일에 모여 있습니다. **각 빈에 어느 스텝에서 온 요소인지 주석으로 표시**해 두었으니, 코스를 복습하는 지도로 쓰십시오.
- `ReportTasklet` 이 만드는 CSV 는 프로젝트 루트의 `output/` 아래에 생깁니다. 이 디렉터리가 없으면 `FileNotFoundException` 이 나므로 `mkdir output` 을 먼저 하십시오. Tasklet 안에서 `Files.createDirectories` 로 처리하고 있지만, 권한 문제는 직접 확인해야 합니다.
- 파일 하단의 `OperationalQueries` 에 14-6 의 운영 분석 SQL 을 전부 상수로 모아 두었습니다. 그대로 복사해 mysql 클라이언트에 붙이거나, 모니터링 대시보드의 쿼리로 쓰십시오.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 다른 스텝보다 **셸에서 하는 작업의 비중이 큽니다.**

- **문제 1** 은 코드를 거의 안 씁니다. `bootJar` 로 빌드하고, 성공·실패 두 경우를 실행해 `echo $?` 를 비교하는 것이 전부입니다. 그런데 이게 배포 전 체크리스트에서 가장 자주 빠지는 항목입니다.
- **문제 2 가 이 스텝에서 가장 실전적입니다.** 배치를 실행하고 도중에 `kill -9` 로 죽인 뒤, `BATCH_JOB_EXECUTION` 에 `STATUS='STARTED'` 가 남는 것을 확인하고, 그 상태에서 2차 방어(`findRunningJobExecutions`)가 **정산을 영원히 건너뛰게** 만드는 것을 재현합니다. 중복을 막으려던 장치가 실행 자체를 막는 역설을 직접 봐야 합니다.
- 문제 3 은 프로세스 두 개를 **동시에** 띄워야 합니다. 셸에서 `&` 로 백그라운드 실행을 두 번 하거나, 터미널 두 개를 준비하십시오. 순차로 실행하면 락이 이미 해제되어 있어 경쟁 상태가 재현되지 않습니다.
- 문제 4·5 는 SQL 과 PromQL 문제입니다. 코드를 안 씁니다. 문제 5 는 정답이 하나가 아니며, **어떤 시간 창을 잡을 것인가**(24시간? 26시간?)를 스스로 정당화하는 것이 핵심입니다.
- 문제 6 은 종합 실습에 Step 을 하나 더 붙이는 문제입니다. 검증 Step 이 실패하면 Job 이 `FAILED` 가 되어야 하는데, [Step 10](../step-10-flow-control/) 의 함정을 피해 **실패가 은폐되지 않도록** 흐름을 짜야 합니다.

```java file="./Exercise.java"
```

### Solution.java

- **정답 1** 은 종료 코드 0 과 1 을 각각 만드는 명령을 제시하고, `SpringApplication.exit()` 이 없을 때 **실패해도 0이 나오는** 것까지 재현합니다. 그리고 이것이 왜 로그만 봐서는 발견되지 않는지 — 로그에는 `FAILED` 가 있는데 파이프라인만 성공으로 본다 — 를 설명합니다.
- **정답 2** 는 좀비 정리 SQL 과 `ZombieCleaner` 구현을 함께 제시합니다. 핵심은 **"얼마나 오래된 STARTED 를 좀비로 볼 것인가"** 의 판단입니다. 배치 최대 실행 시간보다 넉넉히 잡아야 하며, 정상 실행 중인 배치를 좀비로 오인해 `FAILED` 로 만들면 그게 더 큰 사고라는 점을 강조합니다.
- **정답 3** 은 락 획득 실패 시 **종료 코드를 무엇으로 할 것인가**라는 까다로운 질문에 답합니다. 1(실패)로 하면 크론이 알림을 보내는데, 중복 실행을 막은 것은 정상 동작이므로 알림이 갈 이유가 없습니다. 그렇다고 0으로 하면 "돌았다"고 오인됩니다. 별도 코드(예: 3)를 쓰고 파이프라인에서 구분하는 방식을 권합니다.
- **정답 4** 는 `GROUP BY ... HAVING COUNT(*) > 1` 로 재시도가 있었던 날을 찾고, 여기에 **첫 실행이 실패했는지**까지 조인해 "조용히 넘어간 날"을 정확히 특정합니다.
- **정답 5** 는 26시간(24 + 여유 2)을 제안하되, 그 근거와 트레이드오프를 함께 답니다. 너무 짧으면 배치가 조금만 늦어도 오탐이고, 너무 길면 이틀치를 놓칩니다. 그리고 `absent()` 를 쓰는 대안도 비교합니다.
- **정답 6** 이 코스의 마지막 코드입니다. 검증 Step 은 `orders` 의 대상 건수와 `settlement` 의 정산 건수를 비교해 불일치 시 예외를 던집니다. **`ExitStatus` 로 `FAILED` 를 반환하는 것으로는 부족하고 반드시 예외를 던져야 한다**는 [Step 01](../step-01-setup/) 의 교훈과, 흐름에서 `.on("*").fail()` 로 실패를 확실히 전파하는 [Step 10](../step-10-flow-control/) 의 교훈이 여기서 합쳐집니다.

```java file="./Solution.java"
```
