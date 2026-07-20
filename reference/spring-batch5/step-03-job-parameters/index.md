# Step 03 — JobParameters 와 실행 식별

> **학습 목표**
> - `JobParameters` 를 커맨드라인·프로그램·`spring.batch.job.name` 세 가지 경로로 전달한다
> - **`BATCH_JOB_INSTANCE` / `BATCH_JOB_EXECUTION` 을 직접 SELECT 해서** JobInstance 와 JobExecution 의 차이를 눈으로 확인한다
> - 같은 파라미터로 재실행해 `JobInstanceAlreadyCompleteException` 을 스택트레이스까지 재현하고, 세 가지 해결책의 차이를 판단한다
> - identifying / non-identifying 파라미터를 구분하고, 5.x 의 `JobParameter<T>(value, Class, identifying)` 시그니처를 쓴다
> - `RunIdIncrementer` 와 `JobParametersValidator` 로 "매번 새 인스턴스" 와 "잘못된 입력 차단" 을 구현한다
> - `LocalDate` 등 5.x 에서 확장된 파라미터 타입 변환 규칙을 확인한다
>
> **선행 스텝**: Step 02 — Job 과 Step 의 구조
> **예상 소요**: 90분

---

## 3-0. 실습 준비

메타데이터를 깨끗이 비우고 시작합니다. 이 스텝은 **재실행 실패**를 일부러 만들어 보는 스텝이라, 초기화를 자주 하게 됩니다.

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
SELECT (SELECT COUNT(*) FROM BATCH_JOB_INSTANCE)  AS instances,
       (SELECT COUNT(*) FROM BATCH_JOB_EXECUTION) AS executions,
       (SELECT COUNT(*) FROM settlement)          AS settlements;
SQL
```

**결과**
```
+-----------+------------+-------------+
| instances | executions | settlements |
+-----------+------------+-------------+
|         0 |          0 |           0 |
+-----------+------------+-------------+
```

이번 스텝의 Job 은 `com.example.batch.step03` 패키지에 만듭니다. 하는 일은 아주 단순합니다 — **파라미터로 받은 날짜의 주문 건수를 세어 로그로 찍습니다.** 정산 로직 자체는 Step 05 부터이고, 지금은 **파라미터가 어떻게 들어오고 어떻게 기록되는가**에만 집중합니다.

---

## 3-1. JobParameters 는 Job 의 "입력"이자 "신분증"

배치 Job 은 대개 매일 돕니다. 어제 돌린 Job 과 오늘 돌린 Job 은 **같은 코드, 다른 대상**입니다. 그 "대상"을 알려주는 것이 `JobParameters` 입니다.

```
        같은 Job 정의 (settlementJob)
                 │
   ┌─────────────┼─────────────┐
   │             │             │
date=2025-03-01  date=2025-03-02  date=2025-03-03
   │             │             │
JobInstance #1  JobInstance #2  JobInstance #3     ← 파라미터가 다르면 다른 "인스턴스"
```

여기서 중요한 것은 `JobParameters` 가 **단순한 입력값이 아니라 실행의 신분증**이라는 점입니다. Spring Batch 는 `(Job 이름, identifying 파라미터 집합)` 의 조합으로 **JobInstance 를 유일하게 식별**합니다. 그래서 같은 파라미터로 두 번 성공시킬 수 없습니다.

> 💡 **주민등록번호 비유**
> Job 은 "이름"이고 JobParameters 는 "주민등록번호"입니다. 동명이인은 있어도 같은 주민번호는 없습니다.
> `settlementJob + date=2025-03-01` 이라는 신분증은 **세상에 딱 하나**뿐이고, 그 신분증으로 이미 성공했다면 다시 발급되지 않습니다.

Job 정의부터 봅니다. Step 02 에서 배운 `new JobBuilder(name, jobRepository)` 형태 그대로입니다.

```java
package com.example.batch.step03;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ParamJobConfig {

    @Bean
    public Job paramJob(JobRepository jobRepository, Step paramStep) {
        return new JobBuilder("paramJob", jobRepository)
                .start(paramStep)
                .build();
    }

    @Bean
    public Step paramStep(JobRepository jobRepository,
                          PlatformTransactionManager txManager,
                          JdbcTemplate jdbcTemplate) {
        return new StepBuilder("paramStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    // JobParameters 는 StepContribution → StepExecution → JobExecution 경로로 닿습니다.
                    var params = chunkContext.getStepContext()
                            .getStepExecution().getJobParameters();
                    String date = params.getString("date");

                    Integer count = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM orders
                             WHERE status = 'COMPLETED' AND DATE(ordered_at) = ?
                            """, Integer.class, date);

                    System.out.printf("[paramStep] date=%s, COMPLETED orders=%d%n", date, count);
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }
}
```

---

## 3-2. 파라미터를 전달하는 세 가지 경로

### (1) `./gradlew bootRun` — 프로젝트 설정의 `args` 사용

`build.gradle` 에 이미 다음이 들어 있습니다(프로젝트 셋업 P-6).

```groovy
bootRun {
    if (project.hasProperty('args')) {
        args project.property('args').split(',')
    }
}
```

그래서 이렇게 넘깁니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-01"
```

**결과**
```
INFO 42817 --- [           main] c.e.batch.BatchLabApplication            : Starting BatchLabApplication using Java 21.0.2
INFO 42817 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Starting...
INFO 42817 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Start completed.
INFO 42817 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.911 seconds (process running for 2.184)
INFO 42817 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
INFO 42817 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [paramStep]
[paramStep] date=2025-03-01, COMPLETED orders=389
INFO 42817 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [paramStep] executed in 41ms
INFO 42817 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] completed with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED] in 78ms

BUILD SUCCESSFUL in 5s
```

**389건.** 프로젝트 셋업에서 확인한 "하루 약 555건, 그중 COMPLETED 약 389건"과 일치합니다.

여기서 두 인자의 성격이 완전히 다르다는 점을 짚고 갑니다.

| 인자 | 정체 | 소비자 |
|---|---|---|
| `--spring.batch.job.name=paramJob` | **Spring 프로퍼티**. `--` 로 시작 | `JobLauncherApplicationRunner` — "어떤 Job 을 돌릴지" 고름 |
| `date=2025-03-01` | **JobParameter**. `--` 없음, `key=value` | `DefaultJobParametersConverter` — JobParameters 로 변환 |

> ⚠️ **함정 — `--date=2025-03-01` 이라고 쓰면 파라미터가 조용히 사라집니다**
> 앞에 `--` 를 붙이면 Spring 은 그것을 **애플리케이션 프로퍼티**로 해석합니다. JobParameters 에는 들어가지 않습니다.
> 그런데 **에러가 나지 않습니다.** Job 은 파라미터 0개로 정상 기동하고, `params.getString("date")` 가 `null` 을 돌려주며, SQL 은 `DATE(ordered_at) = NULL` 이 되어 **0건**을 셉니다.
>
> ```
> INFO 42901 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{}]
> [paramStep] date=null, COMPLETED orders=0
> INFO 42901 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] ... and the following status: [COMPLETED] in 31ms
> ```
>
> 상태는 **COMPLETED** 입니다. 정산이 0건 되었는데 배치는 "성공"이라고 보고합니다. 이것이 이 코스가 말하는 *에러 없이 조용히 틀리는 코드* 의 전형입니다.
> 방어책은 **`launched with the following parameters: [{}]` 로그를 확인하는 습관**과, 3-8 의 `JobParametersValidator` 입니다.

### (2) 실행 가능한 jar — `java -jar`

운영 배포 형태입니다.

```bash
./gradlew bootJar
java -jar build/libs/spring-batch5-lab-1.0.0.jar \
     --spring.batch.job.name=paramJob date=2025-03-02
```

**결과**
```
INFO 43055 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{'date':'{value=2025-03-02, type=class java.lang.String, identifying=true}'}]
INFO 43055 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [paramStep]
[paramStep] date=2025-03-02, COMPLETED orders=389
INFO 43055 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] completed with the following parameters: [{'date':'{value=2025-03-02, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED] in 69ms
```

`bootRun` 은 `-Dargs="a,b"` 처럼 **쉼표**로 이어 붙여야 하지만, `java -jar` 는 그냥 **공백**으로 나열합니다. 이 차이 때문에 로컬에서 되던 명령이 운영 스크립트에서 안 되는 일이 흔합니다.

### (3) 프로그램에서 `JobLauncher` 로 직접 실행

`application.yml` 의 `spring.batch.job.enabled: false` 로 자동 실행을 끄고, 코드에서 직접 띄우는 방식입니다. 테스트나 API 트리거에서 씁니다.

```java
package com.example.batch.step03;

import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Component;

@Component
public class ManualLauncher {

    private final JobLauncher jobLauncher;
    private final Job paramJob;

    public ManualLauncher(JobLauncher jobLauncher, Job paramJob) {
        this.jobLauncher = jobLauncher;
        this.paramJob = paramJob;
    }

    public JobExecution run(String date) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("date", date)          // identifying = true (기본값)
                .toJobParameters();
        return jobLauncher.run(paramJob, params);
    }
}
```

**결과** (`run("2025-03-03")` 호출 시)
```
INFO 43188 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{'date':'{value=2025-03-03, type=class java.lang.String, identifying=true}'}]
[paramStep] date=2025-03-03, COMPLETED orders=389
INFO 43188 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] completed with the following parameters: [{'date':'{value=2025-03-03, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED] in 74ms
```

> 💡 **실무 팁 — `spring.batch.job.name` 을 지정하지 않으면 등록된 Job 이 전부 돕니다**
> Boot 3.0 이전에는 `spring.batch.job.names` (복수형)였고, 3.0 부터 **`spring.batch.job.name` (단수형)** 으로 바뀌어 **하나만** 지정할 수 있습니다.
> 코스가 진행되며 `step01Job`, `step02Job`, `paramJob` … 이 계속 늘어나므로, **항상 `--spring.batch.job.name=` 을 붙이는 습관**을 들이세요.
> 안 붙이면 Step 12 쯤에서 Job 열 개가 줄줄이 실행되며 왜 이렇게 오래 걸리나 헤매게 됩니다.

---

## 3-3. JobInstance vs JobExecution — 테이블로 확인한다

이 둘의 차이는 말로 설명하면 헷갈리고, **테이블을 보면 5초 만에 이해됩니다.**

| | JobInstance | JobExecution |
|---|---|---|
| 정의 | Job 이름 + identifying 파라미터의 **논리적 실행 단위** | 그 인스턴스를 **실제로 돌린 한 번의 시도** |
| 테이블 | `BATCH_JOB_INSTANCE` | `BATCH_JOB_EXECUTION` |
| 비유 | "3월 1일치 정산" 이라는 **과제** | 그 과제를 푼 **1차 시도, 2차 시도** |
| 개수 관계 | 1 | N (1:N) |
| 재실행하면 | 그대로 (새로 안 생김) | **새로 생김** |

지금까지 세 번 돌렸습니다(`03-01`, `03-02`, `03-03`). 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT JOB_INSTANCE_ID, JOB_NAME, JOB_KEY FROM BATCH_JOB_INSTANCE ORDER BY JOB_INSTANCE_ID;"
```

**결과**
```
+-----------------+----------+----------------------------------+
| JOB_INSTANCE_ID | JOB_NAME | JOB_KEY                          |
+-----------------+----------+----------------------------------+
|               1 | paramJob | 9a4f1e7c2b3d5a8f0c1e6b7d9f2a4c8e |
|               2 | paramJob | 3b7e0d2a5c9f1e4b8d6a0c3f7e2b5d91 |
|               3 | paramJob | c1d8f3a6b0e492d7f5a3c8e10b62d4f7 |
+-----------------+----------+----------------------------------+
```

**JOB_KEY 가 핵심입니다.** identifying 파라미터들을 정렬해 이어 붙인 뒤 MD5 로 해시한 값입니다(`DefaultJobKeyGenerator`). 이 컬럼에 `UNIQUE KEY JOB_INST_UN (JOB_NAME, JOB_KEY)` 가 걸려 있어서, **DB 레벨에서 중복 인스턴스가 원천 차단**됩니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT e.JOB_EXECUTION_ID, e.JOB_INSTANCE_ID, e.STATUS, e.EXIT_CODE,
       DATE_FORMAT(e.START_TIME,'%H:%i:%s') st, DATE_FORMAT(e.END_TIME,'%H:%i:%s') et
  FROM BATCH_JOB_EXECUTION e ORDER BY e.JOB_EXECUTION_ID;"
```

**결과**
```
+------------------+-----------------+-----------+-----------+----------+----------+
| JOB_EXECUTION_ID | JOB_INSTANCE_ID | STATUS    | EXIT_CODE | st       | et       |
+------------------+-----------------+-----------+-----------+----------+----------+
|                1 |               1 | COMPLETED | COMPLETED | 14:02:11 | 14:02:11 |
|                2 |               2 | COMPLETED | COMPLETED | 14:05:47 | 14:05:47 |
|                3 |               3 | COMPLETED | COMPLETED | 14:07:03 | 14:07:03 |
+------------------+-----------------+-----------+-----------+----------+----------+
```

아직 1:1 입니다. **1:N 이 되는 순간은 "실패한 인스턴스를 재실행"할 때**입니다. 그 장면은 Step 11 에서 정면으로 다루지만, 여기서 미리 한 번 만들어 봅니다. 존재하지 않는 형식의 날짜를 넣어 SQL 을 깨뜨립니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=NOT-A-DATE"
```

**결과**
```
INFO 43302 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{'date':'{value=NOT-A-DATE, type=class java.lang.String, identifying=true}'}]
INFO 43302 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [paramStep]
ERROR 43302 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step paramStep in job paramJob

org.springframework.dao.DataIntegrityViolationException: PreparedStatementCallback; Incorrect DATE value: 'NOT-A-DATE'
	at org.springframework.jdbc.support.SQLExceptionSubclassTranslator.doTranslate(SQLExceptionSubclassTranslator.java:79)
	at org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:661)
	at org.springframework.batch.core.step.tasklet.TaskletStep$ChunkTransactionCallback.doInTransaction(TaskletStep.java:407)
	...
INFO 43302 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [paramStep] executed in 22ms
INFO 43302 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] completed with the following parameters: [{'date':'{value=NOT-A-DATE, type=class java.lang.String, identifying=true}'}] and the following status: [FAILED] in 45ms
```

`FAILED` 입니다. 이제 **똑같은 파라미터로 다시** 돌려 봅니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=NOT-A-DATE"
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT JOB_INSTANCE_ID, JOB_EXECUTION_ID, STATUS, VERSION FROM BATCH_JOB_EXECUTION
 WHERE JOB_INSTANCE_ID = 4 ORDER BY JOB_EXECUTION_ID;"
```

**결과**
```
+-----------------+------------------+--------+---------+
| JOB_INSTANCE_ID | JOB_EXECUTION_ID | STATUS | VERSION |
+-----------------+------------------+--------+---------+
|               4 |                4 | FAILED |       2 |
|               4 |                5 | FAILED |       2 |
+-----------------+------------------+--------+---------+
```

**JobInstance 는 4번 하나, JobExecution 은 4·5 두 개.** 이것이 1:N 입니다.
그리고 여기가 결정적입니다 — **`FAILED` 인스턴스는 같은 파라미터로 재실행이 허용됩니다.** 재시작해서 이어가라는 뜻이니까요. 반면 `COMPLETED` 는 허용되지 않습니다. 다음 절이 그 이야기입니다.

---

## 3-4. `JobInstanceAlreadyCompleteException` 재현

3-2 에서 `date=2025-03-01` 은 **COMPLETED** 로 끝났습니다. 그대로 한 번 더 돌립니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-01"
```

**결과**
```
INFO 43451 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.877 seconds (process running for 2.146)
ERROR 43451 --- [           main] o.s.boot.SpringApplication               : Application run failed

java.lang.IllegalStateException: Failed to execute ApplicationRunner
	at org.springframework.boot.SpringApplication.callRunner(SpringApplication.java:790)
	at org.springframework.boot.SpringApplication.callRunners(SpringApplication.java:768)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:322)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1303)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1292)
	at com.example.batch.BatchLabApplication.main(BatchLabApplication.java:14)
Caused by: org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException: A job instance already exists and is complete for identifying parameters={'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}.  If you want to run this job again, change the parameters.
	at org.springframework.batch.core.repository.support.SimpleJobRepository.createJobExecution(SimpleJobRepository.java:141)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:352)
	at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)
	at org.springframework.aop.framework.JdkDynamicAopProxy.invoke(JdkDynamicAopProxy.java:223)
	at jdk.proxy2/jdk.proxy2.$Proxy71.createJobExecution(Unknown Source)
	at org.springframework.batch.core.launch.support.TaskExecutorJobLauncher$1.run(TaskExecutorJobLauncher.java:140)
	at org.springframework.core.task.SyncTaskExecutor.execute(SyncTaskExecutor.java:50)
	at org.springframework.batch.core.launch.support.TaskExecutorJobLauncher.run(TaskExecutorJobLauncher.java:135)
	at org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner.execute(JobLauncherApplicationRunner.java:213)
	at org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner.executeLocalJobs(JobLauncherApplicationRunner.java:191)
	at org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner.launchJobFromProperties(JobLauncherApplicationRunner.java:169)
	at org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner.run(JobLauncherApplicationRunner.java:164)
	at org.springframework.boot.SpringApplication.callRunner(SpringApplication.java:787)
	... 5 common frames omitted

BUILD FAILED in 4s
```

메시지가 친절합니다. **"If you want to run this job again, change the parameters."**

호출 경로를 정리하면 이렇습니다.

```
JobLauncherApplicationRunner.run()
  └─ TaskExecutorJobLauncher.run(job, params)
       └─ jobRepository.getLastJobExecution(name, params)   ← 기존 실행 조회
       └─ jobRepository.createJobExecution(name, params)
            └─ 마지막 실행이 COMPLETED 인가?  → 예: JobInstanceAlreadyCompleteException
            └─ 재시작 불가(Job.isRestartable()==false)인가? → 예: JobRestartException
            └─ 아니면 새 JobExecution 생성
```

> 💡 **이 예외는 축복입니다**
> "정산 배치를 두 번 돌려서 3월 1일 정산이 두 배가 되었다"는 사고는 실무에서 정말 흔합니다.
> `settlement.order_id` 의 UNIQUE 키(프로젝트 셋업 P-4)와 이 예외는 **같은 목적의 이중 방어**입니다.
> 이 예외를 "귀찮은 에러"로 보고 무조건 우회하면, 그 방어를 스스로 걷어내는 셈입니다.

---

## 3-5. 해결책 세 가지 — 그리고 각각의 대가

### (a) 파라미터를 바꾼다 — 가장 정직한 방법

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-04"
```

**결과**
```
INFO 43530 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{'date':'{value=2025-03-04, type=class java.lang.String, identifying=true}'}]
[paramStep] date=2025-03-04, COMPLETED orders=389
INFO 43530 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] ... and the following status: [COMPLETED] in 71ms
```

날짜가 다르면 대상 데이터도 다르니 **당연히 새 인스턴스여야 맞습니다.** 일 배치의 정상 운영 형태입니다.

### (b) `JobParametersIncrementer` — 매 실행마다 값을 하나 증가시킨다

```java
@Bean
public Job incrementJob(JobRepository jobRepository, Step paramStep) {
    return new JobBuilder("incrementJob", jobRepository)
            .incrementer(new RunIdIncrementer())   // run.id 를 1씩 증가시킴
            .start(paramStep)
            .build();
}
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=incrementJob,date=2025-03-01"
./gradlew bootRun -Dargs="--spring.batch.job.name=incrementJob,date=2025-03-01"
```

**결과** (두 번째 실행)
```
INFO 43619 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=incrementJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}','run.id':'{value=2, type=class java.lang.Long, identifying=true}'}]
[paramStep] date=2025-03-01, COMPLETED orders=389
INFO 43619 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=incrementJob]] ... and the following status: [COMPLETED] in 68ms
```

`run.id` 가 `1` → `2` 로 올라갔습니다. `date` 는 같지만 identifying 파라미터 집합이 달라졌으므로 **다른 JobInstance** 입니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT p.JOB_EXECUTION_ID, p.PARAMETER_NAME, p.PARAMETER_TYPE, p.PARAMETER_VALUE, p.IDENTIFYING
  FROM BATCH_JOB_EXECUTION_PARAMS p
  JOIN BATCH_JOB_EXECUTION e ON e.JOB_EXECUTION_ID = p.JOB_EXECUTION_ID
  JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
 WHERE i.JOB_NAME = 'incrementJob' ORDER BY p.JOB_EXECUTION_ID, p.PARAMETER_NAME;"
```

**결과**
```
+------------------+----------------+-------------------+-----------------+-------------+
| JOB_EXECUTION_ID | PARAMETER_NAME | PARAMETER_TYPE    | PARAMETER_VALUE | IDENTIFYING |
+------------------+----------------+-------------------+-----------------+-------------+
|                7 | date           | java.lang.String  | 2025-03-01      | Y           |
|                7 | run.id         | java.lang.Long    | 1               | Y           |
|                8 | date           | java.lang.String  | 2025-03-01      | Y           |
|                8 | run.id         | java.lang.Long    | 2               | Y           |
+------------------+----------------+-------------------+-----------------+-------------+
```

> ⚠️ **함정 — `BATCH_JOB_EXECUTION_PARAMS` 스키마가 5.0 에서 바뀌었습니다**
> 4.x 는 타입별로 컬럼이 나뉘어 있었습니다(`STRING_VAL`, `DATE_VAL`, `LONG_VAL`, `DOUBLE_VAL`, `KEY_NAME`, `TYPE_CD`).
> 5.0 은 **`PARAMETER_NAME` / `PARAMETER_TYPE`(FQCN 문자열) / `PARAMETER_VALUE`(문자열) / `IDENTIFYING`** 네 컬럼으로 통합됐습니다.
> 4.x 시절에 만든 메타데이터 조회 쿼리·모니터링 대시보드는 **전부 깨집니다.** 마이그레이션 시 반드시 확인하세요.

> ⚠️ **함정 — `RunIdIncrementer` 는 "중복 실행 방지" 를 통째로 무력화합니다**
> `run.id` 를 붙이면 **몇 번을 돌려도 항상 새 인스턴스**가 됩니다. 즉 `JobInstanceAlreadyCompleteException` 이 영원히 안 납니다.
> 정산 배치에 이걸 붙이면, 실수로 두 번 돌렸을 때 프레임워크는 아무 말도 안 하고 **3월 1일 정산을 두 번 수행**합니다.
> - `settlement` 의 UNIQUE 키가 있으면 → `DuplicateKeyException` 으로 시끄럽게 실패 (다행)
> - UNIQUE 키가 없으면 → **정산 금액이 정확히 두 배가 된 채로 COMPLETED** (재앙)
>
> `RunIdIncrementer` 는 **"몇 번을 돌려도 결과가 같은(멱등한) Job"** 에만 쓰세요. 예를 들어 "전체 통계 테이블을 지우고 다시 채우는" Job 은 안전합니다.
> 날짜별로 누적 INSERT 하는 Job 에는 **절대** 붙이면 안 됩니다.

### (c) `allowStartIfComplete(true)` — Step 레벨의 재실행 허용

```java
@Bean
public Step alwaysRunStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("alwaysRunStep", jobRepository)
            .tasklet((c, cc) -> RepeatStatus.FINISHED, txManager)
            .allowStartIfComplete(true)     // 이미 COMPLETED 여도 다시 실행
            .build();
}
```

**주의: 이것은 (a)·(b) 의 대안이 아닙니다.** 적용 범위가 다릅니다.

| 옵션 | 적용 대상 | 해결하는 문제 |
|---|---|---|
| 파라미터 변경 | JobInstance | 새 인스턴스를 만든다 |
| `JobParametersIncrementer` | JobInstance | 자동으로 새 인스턴스를 만든다 |
| `allowStartIfComplete(true)` | **Step** | **이미 성공한 인스턴스를 재시작할 때**, 성공했던 Step 을 건너뛰지 않고 다시 실행한다 |

`allowStartIfComplete` 는 **JobExecution 이 새로 만들어진 뒤**의 이야기입니다. `JobInstanceAlreadyCompleteException` 은 JobExecution 이 만들어지기도 전에 터지므로, 이 옵션으로는 막을 수 없습니다.

> 💡 **`allowStartIfComplete(true)` 의 진짜 용도**
> "Step1(임시 테이블 초기화) → Step2(대량 처리)" 구조에서 Step2 만 실패해 재시작하는 상황을 생각해 보세요.
> 기본 동작은 성공한 Step1 을 **건너뜁니다.** 그러면 임시 테이블이 초기화되지 않은 채 Step2 가 돌아 데이터가 섞입니다.
> Step1 에 `allowStartIfComplete(true)` 를 걸면 재시작 때도 반드시 다시 돕니다. **"매번 돌아야 안전한 준비 Step"** 에 씁니다. Step 11 에서 실측합니다.

---

## 3-6. identifying vs non-identifying 파라미터

모든 파라미터가 인스턴스를 식별할 필요는 없습니다. 예를 들면 이렇습니다.

- `date=2025-03-01` → **identifying**. 대상이 달라지므로 인스턴스가 달라야 합니다.
- `chunkSize=1000` → **non-identifying**. 성능 튜닝 값일 뿐, 처리 대상은 같습니다.
- `executedBy=jenkins-42` → **non-identifying**. 기록용 메타 정보입니다.

### 5.x 의 `JobParameter<T>` 시그니처

4.x 의 `JobParameter` 는 값 타입별 생성자(`JobParameter(String)`, `JobParameter(Long)`, …)를 갖는 비제네릭 클래스였습니다. 5.0 부터는 **제네릭 레코드형**으로 바뀌었습니다.

```java
// Spring Batch 5.x
public class JobParameter<T> implements Serializable {
    public JobParameter(T value, Class<T> type, boolean identifying) { ... }
    public JobParameter(T value, Class<T> type) { this(value, type, true); }
}
```

즉 **`(값, 타입, identifying여부)`** 세 개를 명시합니다. 타입을 `Class<T>` 로 들고 다니기 때문에, 메타데이터 테이블에 FQCN 을 그대로 저장하고 복원할 수 있습니다.

```java
JobParameters params = new JobParametersBuilder()
        .addJobParameter("date",      new JobParameter<>(LocalDate.of(2025, 3, 1), LocalDate.class, true))
        .addJobParameter("chunkSize", new JobParameter<>(1000L,                    Long.class,      false))
        .addJobParameter("executedBy",new JobParameter<>("jenkins-42",             String.class,    false))
        .toJobParameters();
```

빌더 편의 메서드로도 동일하게 됩니다.

```java
JobParameters params = new JobParametersBuilder()
        .addLocalDate("date", LocalDate.of(2025, 3, 1))   // identifying = true
        .addLong("chunkSize", 1000L, false)               // 마지막 인자가 identifying
        .addString("executedBy", "jenkins-42", false)
        .toJobParameters();
```

### 커맨드라인에서는 이름 뒤에 `,identifying=false`

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-05,chunkSize=1000\,java.lang.Long\,false"
```

커맨드라인 파라미터의 완전한 문법은 다음과 같습니다.

```
name=value                         → String, identifying=true
name=value,type                    → 지정 타입, identifying=true
name=value,type,identifying        → 전부 명시
```

**결과**
```
INFO 43744 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] launched with the following parameters: [{'date':'{value=2025-03-05, type=class java.lang.String, identifying=true}','chunkSize':'{value=1000, type=class java.lang.Long, identifying=false}'}]
[paramStep] date=2025-03-05, COMPLETED orders=389
INFO 43744 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=paramJob]] ... and the following status: [COMPLETED] in 72ms
```

이제 **`chunkSize` 만 바꿔서** 다시 돌려 봅니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-05,chunkSize=500\,java.lang.Long\,false"
```

**결과**
```
Caused by: org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException: A job instance already exists and is complete for identifying parameters={'date':'{value=2025-03-05, type=class java.lang.String, identifying=true}'}.  If you want to run this job again, change the parameters.
	at org.springframework.batch.core.repository.support.SimpleJobRepository.createJobExecution(SimpleJobRepository.java:141)
```

예외 메시지의 `identifying parameters=` 에 **`date` 만 있고 `chunkSize` 는 없습니다.** non-identifying 파라미터는 JOB_KEY 계산에서 빠지기 때문입니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING
  FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = 9;"
```

**결과**
```
+----------------+------------------+-----------------+-------------+
| PARAMETER_NAME | PARAMETER_TYPE   | PARAMETER_VALUE | IDENTIFYING |
+----------------+------------------+-----------------+-------------+
| date           | java.lang.String | 2025-03-05      | Y           |
| chunkSize      | java.lang.Long   | 1000            | N           |
+----------------+------------------+-----------------+-------------+
```

**저장은 됩니다. 식별에만 안 쓰일 뿐입니다.** 즉 "무슨 값으로 돌렸는지 기록은 남기되 인스턴스는 나누지 않는다"가 non-identifying 의 정확한 의미입니다.

---

## 3-7. `JobParametersIncrementer` 를 직접 만든다

`RunIdIncrementer` 는 `run.id` 라는 의미 없는 숫자를 씁니다. 일 배치라면 **날짜를 하루씩 증가시키는** 편이 훨씬 자연스럽습니다.

```java
public static class DailyDateIncrementer implements JobParametersIncrementer {

    @Override
    public JobParameters getNext(JobParameters parameters) {
        // 최초 실행이면 오늘 날짜, 아니면 마지막 date + 1일
        LocalDate next;
        if (parameters == null || parameters.getString("date") == null) {
            next = LocalDate.of(2025, 3, 1);          // 실습용 고정 시작일
        } else {
            next = LocalDate.parse(parameters.getString("date")).plusDays(1);
        }
        return new JobParametersBuilder(parameters == null ? new JobParameters() : parameters)
                .addString("date", next.toString())
                .toJobParameters();
    }
}
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=dailyJob"
./gradlew bootRun -Dargs="--spring.batch.job.name=dailyJob"
./gradlew bootRun -Dargs="--spring.batch.job.name=dailyJob"
```

**결과** (세 번의 실행 로그를 이어 붙임)
```
INFO 43811 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=dailyJob]] launched with the following parameters: [{'date':'{value=2025-03-01, type=class java.lang.String, identifying=true}'}]
[paramStep] date=2025-03-01, COMPLETED orders=389
INFO 43877 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=dailyJob]] launched with the following parameters: [{'date':'{value=2025-03-02, type=class java.lang.String, identifying=true}'}]
[paramStep] date=2025-03-02, COMPLETED orders=389
INFO 43940 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=dailyJob]] launched with the following parameters: [{'date':'{value=2025-03-03, type=class java.lang.String, identifying=true}'}]
[paramStep] date=2025-03-03, COMPLETED orders=389
```

파라미터를 전혀 주지 않았는데 날짜가 하루씩 전진합니다.

> ⚠️ **함정 — Incrementer 는 `JobLauncher.run()` 에서 자동 호출되지 않습니다**
> `getNext()` 를 호출해 주는 주체는 **`JobLauncherApplicationRunner`**(Boot 자동 실행) 또는 **`JobOperator.startNextInstance(jobName)`** 입니다.
> 여러분이 코드에서 `jobLauncher.run(job, params)` 를 직접 부르면 **Incrementer 는 무시됩니다.** `.incrementer()` 를 붙였는데 왜 `run.id` 가 안 붙지? 하는 혼란의 원인입니다.
> 직접 실행할 때는 `job.getJobParametersIncrementer().getNext(prev)` 를 손으로 호출하거나 `JobOperator` 를 쓰세요.

---

## 3-8. `JobParametersValidator` — 잘못된 입력을 시작 전에 막는다

3-2 의 함정을 기억할 것입니다. `date` 를 안 넘겼는데 Job 이 **COMPLETED** 로 끝났습니다. 이걸 막는 표준 장치가 `JobParametersValidator` 입니다.

### (1) `DefaultJobParametersValidator` — 필수/선택 키 선언

```java
@Bean
public JobParametersValidator dateRequiredValidator() {
    DefaultJobParametersValidator validator = new DefaultJobParametersValidator(
            new String[]{"date"},                    // requiredKeys
            new String[]{"chunkSize", "executedBy"}  // optionalKeys
    );
    validator.afterPropertiesSet();
    return validator;
}

@Bean
public Job validatedJob(JobRepository jobRepository, Step paramStep,
                        JobParametersValidator dateRequiredValidator) {
    return new JobBuilder("validatedJob", jobRepository)
            .validator(dateRequiredValidator)
            .start(paramStep)
            .build();
}
```

`date` 를 빼고 돌립니다.

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=validatedJob"
```

**결과**
```
ERROR 44012 --- [           main] o.s.boot.SpringApplication               : Application run failed

java.lang.IllegalStateException: Failed to execute ApplicationRunner
	at org.springframework.boot.SpringApplication.callRunner(SpringApplication.java:790)
	at org.springframework.boot.SpringApplication.callRunners(SpringApplication.java:768)
	at com.example.batch.BatchLabApplication.main(BatchLabApplication.java:14)
Caused by: org.springframework.batch.core.JobParametersInvalidException: The JobParameters do not contain required keys: [date]
	at org.springframework.batch.core.job.DefaultJobParametersValidator.validate(DefaultJobParametersValidator.java:120)
	at org.springframework.batch.core.job.AbstractJob.execute(AbstractJob.java:311)
	at org.springframework.batch.core.launch.support.TaskExecutorJobLauncher$1.run(TaskExecutorJobLauncher.java:149)
	at org.springframework.core.task.SyncTaskExecutor.execute(SyncTaskExecutor.java:50)
	at org.springframework.batch.core.launch.support.TaskExecutorJobLauncher.run(TaskExecutorJobLauncher.java:135)
	... 6 common frames omitted

BUILD FAILED in 4s
```

`COMPLETED` 대신 **`JobParametersInvalidException`** 입니다. 조용한 성공이 시끄러운 실패로 바뀌었습니다.

> ⚠️ **함정 — `optionalKeys` 를 지정하면 "화이트리스트"가 됩니다**
> `optionalKeys` 를 **비워 두면** 어떤 추가 키든 허용됩니다. 그런데 하나라도 지정하는 순간, **required + optional 에 없는 키는 전부 거부**됩니다.
> ```
> Caused by: org.springframework.batch.core.JobParametersInvalidException: The JobParameters contains keys that are not explicitly optional or required: [run.id]
> ```
> 특히 `RunIdIncrementer` 와 함께 쓰면 프레임워크가 자동으로 붙이는 **`run.id` 가 거부되어** Job 이 시작조차 못 합니다.
> 둘을 같이 쓸 거라면 `optionalKeys` 에 `"run.id"` 를 반드시 넣으세요. 이건 로컬에서는 안 나고 운영 스케줄러에서만 나는 사고입니다.

### (2) 커스텀 Validator — 값의 형식과 범위까지 검증

키의 존재 여부만으로는 부족합니다. `date=NOT-A-DATE` 는 여전히 통과합니다.

```java
public static class SettlementDateValidator implements JobParametersValidator {

    private static final LocalDate MIN = LocalDate.of(2025, 1, 1);
    private static final LocalDate MAX = LocalDate.of(2025, 6, 29);

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("JobParameters 가 null 입니다.");
        }
        String raw = parameters.getString("date");
        if (raw == null || raw.isBlank()) {
            throw new JobParametersInvalidException("필수 파라미터 'date' 가 없습니다. 예: date=2025-03-01");
        }
        LocalDate d;
        try {
            d = LocalDate.parse(raw);   // ISO-8601 (yyyy-MM-dd) 만 허용
        } catch (DateTimeParseException e) {
            throw new JobParametersInvalidException(
                    "'date' 형식이 잘못되었습니다: '%s' (기대 형식: yyyy-MM-dd)".formatted(raw));
        }
        if (d.isBefore(MIN) || d.isAfter(MAX)) {
            throw new JobParametersInvalidException(
                    "'date' 가 데이터 보유 기간(%s ~ %s)을 벗어났습니다: %s".formatted(MIN, MAX, d));
        }
    }
}
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=strictJob,date=2025-13-45"
```

**결과**
```
Caused by: org.springframework.batch.core.JobParametersInvalidException: 'date' 형식이 잘못되었습니다: '2025-13-45' (기대 형식: yyyy-MM-dd)
	at com.example.batch.step03.Practice$SettlementDateValidator.validate(Practice.java:171)
	at org.springframework.batch.core.job.AbstractJob.execute(AbstractJob.java:311)
	at org.springframework.batch.core.launch.support.TaskExecutorJobLauncher$1.run(TaskExecutorJobLauncher.java:149)
```

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=strictJob,date=2024-12-31"
```

**결과**
```
Caused by: org.springframework.batch.core.JobParametersInvalidException: 'date' 가 데이터 보유 기간(2025-01-01 ~ 2025-06-29)을 벗어났습니다: 2024-12-31
	at com.example.batch.step03.Practice$SettlementDateValidator.validate(Practice.java:175)
```

여러 Validator 를 묶고 싶으면 `CompositeJobParametersValidator` 를 씁니다.

```java
@Bean
public JobParametersValidator compositeValidator() {
    CompositeJobParametersValidator composite = new CompositeJobParametersValidator();
    composite.setValidators(List.of(
            new DefaultJobParametersValidator(new String[]{"date"}, new String[]{"run.id", "chunkSize"}),
            new SettlementDateValidator()
    ));
    return composite;
}
```

> 💡 **실무 팁 — Validator 는 Job 이 아니라 파라미터의 계약서입니다**
> 배치는 사람이 손으로 실행하는 일이 많고, 그때 오타가 납니다. `date=2025-3-1`(0 패딩 누락), `date=20250301`(구분자 누락)이 대표적입니다.
> Validator 없이 이런 값이 들어가면 `DATE(ordered_at) = '20250301'` 이 **0건**을 반환하며 조용히 COMPLETED 됩니다.
> **"입력을 검증하지 않는 배치는 언젠가 0건을 정산한다"** 고 외워 두세요.

---

## 3-9. 타입 변환 — 5.x 에서 무엇이 늘었나

4.x 의 `JobParameter` 가 지원한 타입은 **네 개뿐**이었습니다: `String`, `Long`, `Double`, `java.util.Date`.
5.0 부터는 `JobParameter<T>` 가 임의의 타입을 담을 수 있고, `DefaultJobParametersConverter` 가 Spring 의 `ConversionService` 를 통해 문자열 ↔ 타입 변환을 처리합니다.

| 표기 | 변환 결과 타입 | 비고 |
|---|---|---|
| `date=2025-03-01` | `java.lang.String` | **타입 미지정 시 기본은 String** |
| `date=2025-03-01,java.time.LocalDate` | `java.time.LocalDate` | 5.x 신규 |
| `ts=2025-03-01T09:30:00,java.time.LocalDateTime` | `java.time.LocalDateTime` | 5.x 신규 |
| `chunkSize=1000,java.lang.Long` | `java.lang.Long` | |
| `rate=0.025,java.lang.Double` | `java.lang.Double` | |
| `dryRun=true,java.lang.Boolean` | `java.lang.Boolean` | 5.x 신규 |

```bash
./gradlew bootRun -Dargs="--spring.batch.job.name=typedJob,date=2025-03-06\,java.time.LocalDate,dryRun=true\,java.lang.Boolean"
```

**결과**
```
INFO 44201 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=typedJob]] launched with the following parameters: [{'date':'{value=2025-03-06, type=class java.time.LocalDate, identifying=true}','dryRun':'{value=true, type=class java.lang.Boolean, identifying=true}'}]
[typedStep] date=2025-03-06 (LocalDate), dryRun=true, COMPLETED orders=389
INFO 44201 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=typedJob]] ... and the following status: [COMPLETED] in 75ms
```

읽는 쪽 코드입니다.

```java
JobParameters params = chunkContext.getStepContext().getStepExecution().getJobParameters();

LocalDate date   = params.getLocalDate("date");     // 5.x 신규 게터
Boolean  dryRun  = (Boolean) params.getParameter("dryRun").getValue();
Long     chunk   = params.getLong("chunkSize", 1000L);   // 두 번째 인자는 기본값
```

> ⚠️ **함정 — 게터의 타입과 저장된 타입이 다르면 ClassCastException 입니다**
> `date=2025-03-01` (타입 미지정 → String)로 넘겨 놓고 코드에서 `params.getLocalDate("date")` 를 부르면:
> ```
> Caused by: java.lang.ClassCastException: class java.lang.String cannot be cast to class java.time.LocalDate (java.lang.String and java.time.LocalDate are in module java.base of loader 'bootstrap')
> 	at org.springframework.batch.core.JobParameters.getLocalDate(JobParameters.java:141)
> 	at com.example.batch.step03.Practice$TypedTasklet.execute(Practice.java:214)
> ```
> 커맨드라인 표기와 코드의 게터는 **한 쌍**입니다. 한쪽만 바꾸면 컴파일은 통과하고 런타임에 터집니다.
> 더 나쁜 경우도 있습니다 — `params.getString("date")` 로 읽으면 `LocalDate` 로 넘긴 값도 `toString()` 되어 `"2025-03-01"` 로 잘 나옵니다.
> 그런데 **`java.util.Date` 로 넘겼다면** `toString()` 이 `"Sat Mar 01 00:00:00 KST 2025"` 가 되어, SQL 비교가 조용히 0건이 됩니다.
> **타입을 명시했다면 게터도 그 타입으로** 통일하세요.

> 💡 **실무 팁 — 커맨드라인에서는 `String` 으로 받고 코드에서 파싱하는 편이 안전합니다**
> `,java.time.LocalDate` 표기는 셸에서 쉼표를 이스케이프해야 하고(`\,`), Jenkins·Airflow 같은 스케줄러를 거치면 이스케이프가 또 한 번 꼬입니다.
> 실무에서는 `date=2025-03-01` (String) 으로 받고, Validator 에서 `LocalDate.parse()` 로 검증한 뒤, 사용하는 쪽에서 다시 파싱하는 패턴이 흔합니다.
> **타입 안전성은 Validator 로 확보하고, 전달은 단순하게** 가는 것이 트레이드오프의 실용적인 지점입니다.

---

## 3-10. 파라미터를 Bean 안에서 쓰기 — `@StepScope` 와 늦은 바인딩

Tasklet 람다 안에서는 `ChunkContext` 로 파라미터에 닿았습니다. 그런데 Reader/Writer 같은 **Bean** 안에서 쓰려면 문제가 생깁니다. Bean 은 애플리케이션 기동 시점에 만들어지는데, JobParameters 는 **Job 실행 시점**에야 정해지기 때문입니다.

해결책이 `@StepScope` 입니다. Bean 생성을 Step 실행 시점까지 미룹니다(**늦은 바인딩, late binding**).

```java
@Bean
@StepScope
public Tasklet dateAwareTasklet(@Value("#{jobParameters['date']}") String date,
                                JdbcTemplate jdbcTemplate) {
    return (contribution, chunkContext) -> {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND DATE(ordered_at) = ?",
                Integer.class, date);
        System.out.printf("[dateAwareTasklet] date=%s, count=%d%n", date, count);
        return RepeatStatus.FINISHED;
    };
}
```

**결과**
```
INFO 44330 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=scopedJob]] launched with the following parameters: [{'date':'{value=2025-03-07, type=class java.lang.String, identifying=true}'}]
INFO 44330 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [scopedStep]
[dateAwareTasklet] date=2025-03-07, count=389
INFO 44330 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [scopedStep] executed in 44ms
```

`@StepScope` 를 빼면 기동 시점에 이렇게 터집니다.

```
Caused by: org.springframework.beans.factory.BeanExpressionException: Expression parsing failed; nested exception is org.springframework.expression.spel.SpelEvaluationException: EL1008E: Property or field 'jobParameters' cannot be found on object of type 'org.springframework.beans.factory.config.BeanExpressionContext' - maybe not public or not valid?
	at org.springframework.context.expression.StandardBeanExpressionResolver.evaluate(StandardBeanExpressionResolver.java:165)
	at org.springframework.beans.factory.support.AbstractBeanFactory.evaluateBeanDefinitionString(AbstractBeanFactory.java:1550)
```

**이건 다행히 시끄럽게 실패합니다.** `@StepScope` 는 Step 06~08 의 Reader/Writer 에서 계속 쓰게 되므로 여기서 이름만 기억해 두면 됩니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| JobParameters | Job 의 입력이자 **신분증**. identifying 집합이 인스턴스를 결정 |
| JobInstance | `Job 이름 + identifying 파라미터` = 논리적 실행 단위. `BATCH_JOB_INSTANCE` |
| JobExecution | 그 인스턴스를 실제로 돌린 **한 번의 시도**. `BATCH_JOB_EXECUTION`. 1:N |
| JOB_KEY | identifying 파라미터의 MD5 해시. `UNIQUE(JOB_NAME, JOB_KEY)` 로 중복 차단 |
| 전달 문법 | `name=value[,type[,identifying]]`. **`--` 를 붙이면 Spring 프로퍼티가 되어 조용히 사라짐** |
| Job 선택 | `--spring.batch.job.name=` (Boot 3.0 부터 **단수형**) |
| 재실행 실패 | 마지막 실행이 COMPLETED 면 `JobInstanceAlreadyCompleteException`. FAILED 면 재실행 허용 |
| 해결 (a) | 파라미터 변경 — 가장 정직 |
| 해결 (b) | `JobParametersIncrementer` / `RunIdIncrementer` — **중복 방지를 무력화하므로 멱등한 Job 에만** |
| 해결 (c) | `allowStartIfComplete(true)` — **Step 레벨.** 인스턴스 문제를 푸는 게 아님 |
| non-identifying | 저장은 되지만 JOB_KEY 계산에서 제외. 튜닝값·메타정보용 |
| 5.x `JobParameter<T>` | `(value, Class<T>, identifying)`. 4.x 의 타입별 생성자 폐기 |
| 5.x PARAMS 스키마 | `PARAMETER_NAME/TYPE/VALUE/IDENTIFYING` 4컬럼으로 통합 (4.x 쿼리 전부 깨짐) |
| Validator | `DefaultJobParametersValidator`(키) + 커스텀(형식·범위). **optionalKeys 지정 시 화이트리스트가 됨** |
| 타입 변환 | 5.x 는 `LocalDate`/`LocalDateTime`/`Boolean` 등 확장. **게터 타입과 반드시 일치시킬 것** |
| `@StepScope` | Bean 생성을 Step 실행 시점으로 미루는 늦은 바인딩. `#{jobParameters['...']}` |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. `date` 를 넘겼는데 `[{}]` 로 로그가 찍힐 때, 잘못된 명령줄을 찾아 고치기
2. 주어진 파라미터 조합 4개가 각각 **몇 개의 JobInstance** 를 만드는지 판정
3. `JobInstanceAlreadyCompleteException` 이 나는 상황에서, 정산 배치에 **써도 되는 해결책**과 **쓰면 안 되는 해결책**을 근거와 함께 고르기
4. `RunIdIncrementer` + `DefaultJobParametersValidator` 를 함께 쓸 때 나는 예외를 예측하고 고치기
5. `date`(yyyy-MM-dd, 2025-01-01~2025-06-29 범위), `dryRun`(선택, Boolean) 을 검증하는 커스텀 Validator 작성
6. 하루씩 전진하되 **2025-06-29 를 넘으면 예외를 던지는** `JobParametersIncrementer` 작성

---

## 다음 단계

파라미터로 "무엇을 처리할지"를 정하고, 그 실행이 메타데이터에 어떻게 기록되는지까지 봤습니다.
하지만 지금까지의 Step 은 전부 람다 한 줄짜리였습니다. 다음 스텝에서 **Tasklet 이라는 Step 구현체**를 제대로 파고듭니다.
`RepeatStatus` 하나로 무한루프에 빠지는 함정, Tasklet 의 트랜잭션 경계, 그리고 "언제 Tasklet 을 쓰고 언제 청크를 써야 하는가"를 판단 기준까지 정리합니다.

→ [Step 04 — Tasklet Step](../step-04-tasklet/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 프로젝트의 `src/main/java/com/example/batch/step03/` 에 놓고 본문 3-1 ~ 3-10 의 실행을 순서대로 재현한 뒤, `Exercise.java` 의 6문제를 직접 채워 보고, `Solution.java` 로 대조합니다. 세 파일 모두 **하나의 최상위 클래스 안에 `static class` 로 Job 설정을 중첩**하는 구조라, 파일 하나만 복사하면 바로 돌아갑니다.

### Practice.java

본문에 나온 모든 Job 정의·Tasklet·Validator·Incrementer 를 `// [3-1]` 형태의 절 번호 주석과 함께 담았습니다.

- 최상위 `Practice` 클래스는 `@Configuration` 이 아니며, 안쪽의 `ParamJobConfig`, `IncrementerConfig`, `ValidatorConfig`, `TypedConfig`, `ScopedConfig` 다섯 개의 `static class` 가 각각 `@Configuration` 입니다. 특정 절만 실험하고 싶으면 **나머지 클래스에 `@Configuration` 을 잠시 주석 처리**하면 됩니다.
- `[3-2]` 의 `ManualLauncher` 는 `spring.batch.job.enabled: false` 일 때만 의미가 있습니다. 자동 실행을 켠 채로 두면 Job 이 두 번 도는 것처럼 보이니 주의하세요.
- `[3-5]` 의 `RunIdIncrementer` 블록에는 **정산 배치에 붙이면 안 되는 이유**가 주석으로 길게 달려 있습니다. 코드를 복사하기 전에 반드시 읽으세요.
- `[3-6]` 의 커맨드라인 예시는 셸에서 쉼표를 `\,` 로 이스케이프해야 합니다. 파일 주석에 zsh / bash 각각의 표기를 적어 두었습니다.
- `[3-9]` 의 `TypedTasklet` 은 `params.getLocalDate("date")` 를 씁니다. `date` 를 타입 없이 넘기면 `ClassCastException` 이 나는데, **이건 의도된 실습**입니다. 일부러 한 번 틀려 보세요.
- 모든 Job 은 `paramStep` 을 공유하지만 Job 이름이 다르므로 JobInstance 는 Job 이름별로 따로 관리됩니다. 이 사실이 `[3-3]` 의 SELECT 결과를 읽을 때 중요합니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·2·3** 은 코드를 거의 쓰지 않고 **판단**하는 문제입니다. 답을 주석 문자열로 적고, 실제로 명령을 실행해 검증하세요. 특히 문제 2 는 답을 적은 뒤 `SELECT COUNT(*) FROM BATCH_JOB_INSTANCE` 로 반드시 확인해야 의미가 있습니다.
- **문제 4** 는 파일이 이미 깨지는 조합(`RunIdIncrementer` + `optionalKeys` 에 `run.id` 없음)을 만들어 둔 상태입니다. 실행하면 `JobParametersInvalidException` 이 납니다. 예외 메시지를 먼저 예측하고 나서 실행하세요.
- **문제 5·6** 이 실제 코딩입니다. 문제 5 의 `dryRun` 은 **선택** 파라미터이므로 "없어도 통과, 있으면 `true`/`false` 만 허용" 이라는 두 조건을 모두 만족해야 합니다. `Boolean.parseBoolean("yes")` 가 예외 없이 `false` 를 돌려준다는 점이 이 문제의 함정입니다.
- 문제 6 의 경계값은 `2025-06-29` 입니다. **그날은 성공하고 그다음 날 호출에서 예외**여야 합니다. off-by-one 을 조심하세요.
- 파일 하단의 `main` 은 없습니다. Spring 컨텍스트에서 Bean 으로 로드되어야 하므로, 실행은 `./gradlew bootRun -Dargs="--spring.batch.job.name=..."` 으로 합니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 의 핵심은 `--date=...` 의 `--` 하나입니다. 이 한 글자 때문에 Job 이 파라미터 0개로 성공한다는 것 — 즉 **실패가 아니라 성공하기 때문에 위험하다** 는 점을 주석으로 강조합니다.
- **정답 2** 는 4개 조합에 대해 JobInstance 수가 `3` 이라고 답합니다. non-identifying 파라미터만 다른 두 조합이 같은 인스턴스로 합쳐지기 때문입니다. JOB_KEY 계산 규칙(identifying 만, 이름순 정렬, MD5)까지 함께 설명합니다.
- **정답 3** 은 "정산 배치에는 (a) 파라미터 변경만 쓴다" 입니다. `RunIdIncrementer` 를 붙이면 `settlement` 의 UNIQUE 키가 없을 때 **정확히 두 배의 정산 금액이 COMPLETED 상태로 남는다**는 시나리오를 수치와 함께 적었습니다.
- **정답 4** 는 `optionalKeys` 에 `"run.id"` 를 추가하는 것입니다. 더 근본적인 답으로 "`optionalKeys` 를 아예 지정하지 않아 화이트리스트 모드를 끄는" 선택지도 함께 제시하고, 둘의 트레이드오프(오타 방어 vs 유연성)를 비교합니다.
- **정답 5** 는 `dryRun` 을 `Boolean.parseBoolean` 으로 파싱하지 **않고**, `"true".equals(v) || "false".equals(v)` 로 화이트리스트 검사합니다. `parseBoolean("yes")` 가 조용히 `false` 가 되는 것 — 사용자는 dry-run 을 켰다고 믿는데 **실제로 데이터가 쓰이는** 사고를 막기 위해서입니다.
- **정답 6** 은 `getNext()` 에서 `next.isAfter(MAX)` 를 검사해 `IllegalStateException` 을 던집니다. 여기에 더해 "Incrementer 안에서 예외를 던지면 JobExecution 이 아예 생성되지 않아 메타데이터에 흔적이 남지 않는다"는 운영상의 단점과, 대안(마지막 날 이후에는 `getNext()` 가 이전 값을 그대로 돌려주어 `JobInstanceAlreadyCompleteException` 으로 자연스럽게 멈추게 하는 방식)을 함께 설명합니다.

```java file="./Solution.java"
```
