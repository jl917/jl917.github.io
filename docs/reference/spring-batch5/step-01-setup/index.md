# Step 01 — 환경 구축과 첫 Job

> **학습 목표**
> - Spring Boot 3.2.5 + Spring Batch 5.1.1 환경에서 Tasklet 하나짜리 최소 Job 을 작성해 실행한다
> - `JobLauncherApplicationRunner` 가 **부팅 직후 Job 을 자동 실행하는 경로**를 로그로 추적한다
> - Spring Batch 콘솔 로그 한 줄 한 줄이 무엇을 뜻하는지 해부한다
> - 실행이 남긴 흔적을 `BATCH_*` **9개 테이블 전부** `SELECT` 해서 컬럼 단위로 읽는다
> - `BatchStatus` 와 `ExitStatus` 가 왜 별개인지 실패 Job 으로 직접 확인한다
> - 같은 Job 을 두 번 돌렸을 때 무슨 일이 벌어지는지 미리 목격한다
>
> **선행 스텝**: [실습 프로젝트 셋업](../project/)
> **예상 소요**: 70분

---

## 1-0. 실습 준비 — 지금은 아무 흔적도 없습니다

프로젝트 셋업에서 `./gradlew bootRun` 이 한 번 성공했다면 `BATCH_*` 테이블 9개는 이미 만들어져 있습니다. 다만 **Job 이 하나도 없었으므로 전부 비어 있습니다.** 출발선을 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT 'JOB_INSTANCE'   t, COUNT(*) c FROM BATCH_JOB_INSTANCE
UNION ALL SELECT 'JOB_EXECUTION',        COUNT(*) FROM BATCH_JOB_EXECUTION
UNION ALL SELECT 'JOB_EXECUTION_PARAMS', COUNT(*) FROM BATCH_JOB_EXECUTION_PARAMS
UNION ALL SELECT 'STEP_EXECUTION',       COUNT(*) FROM BATCH_STEP_EXECUTION;"
```

**결과**
```
+----------------------+---+
| t                    | c |
+----------------------+---+
| JOB_INSTANCE         | 0 |
| JOB_EXECUTION        | 0 |
| JOB_EXECUTION_PARAMS | 0 |
| STEP_EXECUTION       | 0 |
+----------------------+---+
```

0 이 아니라면 이전 실습이 남아 있는 것입니다. 프로젝트 셋업 문서 `P-10 (a)` 의 초기화 스크립트로 지우고 시작하세요. **이 스텝의 모든 ID 값(1, 2, 3…)은 메타데이터가 비어 있다는 전제로 적혀 있습니다.**

---

## 1-1. 첫 Job — Tasklet 하나짜리

가장 작은 Job 을 만듭니다. `src/main/java/com/example/batch/step01/HelloJobConfig.java`:

```java
package com.example.batch.step01;

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
public class HelloJobConfig {

    @Bean
    public Job helloJob(JobRepository jobRepository, Step helloStep) {
        return new JobBuilder("helloJob", jobRepository)
                .start(helloStep)
                .build();
    }

    @Bean
    public Step helloStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("helloStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println(">>> Hello, Spring Batch 5!");
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }
}
```

읽을 것이 네 가지 있습니다.

| 코드 | 의미 |
|---|---|
| `new JobBuilder("helloJob", jobRepository)` | 5.0 부터 **빌더를 직접 생성**합니다. 4.x 의 `jobBuilderFactory.get("helloJob")` 은 삭제됐습니다 |
| `.start(helloStep)` | Job 의 **첫 Step**. Job 은 Step 의 순서 있는 묶음입니다 |
| `.tasklet(lambda, txManager)` | Tasklet 은 "한 번 실행하고 끝"인 작업 단위. **트랜잭션 매니저를 인자로 받는 것도 5.0 변화**입니다 |
| `RepeatStatus.FINISHED` | "더 반복하지 마라." `CONTINUABLE` 을 반환하면 같은 Tasklet 이 다시 호출됩니다 |

`@EnableBatchProcessing` 이 **없다는 점**을 기억해 두세요. Spring Boot 3.x 의 자동설정이 `JobRepository`·`JobLauncher`·`PlatformTransactionManager` 를 이미 등록해 주기 때문에 붙일 필요가 없습니다. 왜 붙이면 오히려 손해인지는 [Step 02](../step-02-job-step/) 에서 다룹니다.

> 💡 **`Step helloStep` 을 파라미터로 주입받는 이유**
> `helloJob(...)` 안에서 `helloStep(jobRepository, txManager)` 를 직접 호출해도 컴파일은 됩니다. 하지만 그러면 **프록시를 거치지 않는 호출**이 될 위험이 있고, 무엇보다 스프링이 관리하지 않는 `Step` 인스턴스가 생길 수 있습니다.
> `@Bean` 메서드 파라미터로 받으면 컨테이너가 만든 **그 빈**을 받는 것이 보장됩니다.

---

## 1-2. 실행

```bash
./gradlew bootRun
```

**결과**
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.5)

INFO 41211 --- [           main] c.e.batch.BatchLabApplication            : Starting BatchLabApplication using Java 21.0.2
INFO 41211 --- [           main] c.e.batch.BatchLabApplication            : No active profile set, falling back to 1 default profile: "default"
INFO 41211 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Starting...
INFO 41211 --- [           main] com.zaxxer.hikari.pool.HikariPool        : batch-pool - Added connection com.mysql.cj.jdbc.ConnectionImpl@6b7d1df8
INFO 41211 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Start completed.
INFO 41211 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.907 seconds (process running for 2.184)
INFO 41211 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] launched with the following parameters: [{}]
INFO 41211 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [helloStep]
>>> Hello, Spring Batch 5!
INFO 41211 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [helloStep] executed in 18ms
INFO 41211 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 42ms

BUILD SUCCESSFUL in 5s
```

Job 이 **42ms** 에 끝났습니다. 그중 Step 이 18ms 이고, 나머지 24ms 는 `JobRepository` 가 메타데이터를 INSERT/UPDATE 하는 데 쓴 시간입니다. **아무 일도 안 하는 Job 조차 메타데이터 왕복이 6번 이상 일어납니다.** 이 오버헤드는 [Step 05](../step-05-chunk/) 의 청크 크기 결정에서 다시 중요해집니다.

> 💡 **실무 팁 — `SimpleJobLauncher` 라고 적힌 문서는 4.x 기준입니다**
> 5.0 에서 `SimpleJobLauncher` 는 **deprecated** 되고 `TaskExecutorJobLauncher` 로 이름이 바뀌었습니다. Boot 자동설정도 `TaskExecutorJobLauncher` 를 등록합니다.
> 그래서 로그 로거 이름이 `o.s.b.c.l.s.TaskExecutorJobLauncher` 로 나옵니다. 인터넷 예제 로그와 다르다고 당황할 필요 없습니다. **메시지 포맷 자체는 4.x 와 동일**합니다.

---

## 1-3. 로그 한 줄씩 읽기

배치는 사람이 보고 있지 않을 때 도는 프로그램입니다. **로그가 유일한 목격자**이므로 포맷을 정확히 읽을 줄 알아야 합니다.

```
INFO 41211 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] launched with the following parameters: [{}]
 │    │           │               │                                          │
 │    │           │               │                                          └─ 메시지
 │    │           │               └─ 로거(클래스명 축약). o.s.b.c.l.s = org.springframework.batch.core.launch.support
 │    │           └─ 스레드명. 배치는 기본이 main 스레드입니다 (Step 13 에서 바뀝니다)
 │    └─ PID
 └─ 로그 레벨
```

의미 있는 네 줄만 따로 봅니다.

| 로그 | 언제 나오나 | 놓치면 안 되는 정보 |
|---|---|---|
| `Job: [SimpleJob: [name=helloJob]] launched with the following parameters: [{}]` | `JobLauncher.run()` 진입 직후, **JobExecution 이 이미 DB 에 INSERT 된 뒤** | `[{}]` 가 **파라미터**입니다. 지금은 비었습니다 |
| `Executing step: [helloStep]` | `SimpleStepHandler` 가 Step 실행을 결정한 뒤 | 이 줄이 **없으면** Step 이 스킵된 것입니다(이미 COMPLETED 라서) |
| `Step: [helloStep] executed in 18ms` | Step 종료 후 | Step 단위 소요시간. Job 시간과 차이가 크면 메타데이터 I/O 를 의심 |
| `... completed with the following parameters: [{}] and the following status: [COMPLETED] in 42ms` | Job 종료 | `status:` 는 **BatchStatus** 입니다. ExitStatus 가 아닙니다(1-13) |

> ⚠️ **함정 — `Executing step:` 이 안 보이는데 Job 은 COMPLETED 로 끝난다**
> 로그에 Step 실행 줄이 없는데 Job 은 성공으로 끝나는 경우가 있습니다. 에러도 안 납니다. **이미 성공한 Step 을 Spring Batch 가 조용히 건너뛴 것**입니다.
> 같은 JobInstance 를 재시작하면 `SimpleStepHandler` 는 `shouldStart()` 로 "이 Step 은 이미 COMPLETED 이고 `allowStartIfComplete` 가 아니다" 라고 판단해 실행 자체를 하지 않습니다.
> "돌렸는데 데이터가 안 바뀌었어요" 문의의 상당수가 이것입니다. **로그에서 `Executing step:` 줄 개수를 세는 습관**을 들이세요. Step 3개짜리 Job 이면 그 줄이 3번 나와야 정상입니다.

---

## 1-4. 왜 부팅하자마자 Job 이 돌았나 — JobLauncherApplicationRunner

우리는 `JobLauncher.run()` 을 호출한 적이 없습니다. 그런데 Job 이 돌았습니다. 범인은 Spring Boot 의 `JobLauncherApplicationRunner` 입니다.

```
SpringApplication.run()
   │
   ├─ (1) ApplicationContext 생성 및 모든 빈 초기화
   │        ├─ BatchAutoConfiguration        → JobRepository, JobLauncher, JobExplorer, JobOperator 등록
   │        ├─ BatchDataSourceScriptDatabaseInitializer → BATCH_* DDL 실행 (initialize-schema: always)
   │        └─ HelloJobConfig                → helloJob, helloStep 등록
   │
   ├─ (2) "Started BatchLabApplication in 1.907 seconds"   ← 여기까지가 부팅
   │
   └─ (3) callRunners() — ApplicationRunner / CommandLineRunner 를 전부 호출
            └─ JobLauncherApplicationRunner
                 ├─ 컨텍스트의 모든 Job 빈을 수집
                 ├─ spring.batch.job.name 이 있으면 그 이름만 필터
                 ├─ 커맨드라인 인자를 JobParameters 로 변환
                 └─ jobLauncher.run(job, params)   ← 여기서 우리 Job 이 실행됨
```

세 가지가 중요합니다.

1. **부팅 로그(`Started ...`)가 Job 실행보다 먼저 나옵니다.** 로그 순서가 그렇게 보이는 게 정상입니다.
2. 이 러너는 `spring.batch.job.enabled` 가 `true`(기본값)일 때만 동작합니다. `application.yml` 에서 이미 `true` 로 명시해 뒀습니다.
3. **컨텍스트에 Job 빈이 여러 개면 전부 실행됩니다.** 이름을 지정하지 않는 한 그렇습니다.

3번을 확인해 봅니다. `spring.batch.job.enabled: false` 로 바꾸고 다시 실행하면:

```bash
SPRING_BATCH_JOB_ENABLED=false ./gradlew bootRun
```

**결과**
```
INFO 41398 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.884 seconds (process running for 2.160)

BUILD SUCCESSFUL in 4s
```

`launched with the following parameters` 줄이 통째로 사라졌습니다. Job 빈은 여전히 컨텍스트에 있지만 아무도 실행하지 않습니다.

> ⚠️ **함정 — 스텝을 쌓다 보면 부팅 한 번에 Job 이 14개 돕니다**
> 이 코스는 하나의 프로젝트에 `step01` ~ `step14` 패키지를 계속 추가합니다. 아무 설정 없이 `bootRun` 하면 **컨텍스트에 등록된 모든 Job 이 순차로 실행됩니다.**
> Step 05 쯤 가면 7만 건짜리 청크 Job 이 섞여 있어서, "왜 hello 하나 돌리는데 40초가 걸리지?" 가 됩니다. 더 나쁜 건 **의도하지 않은 Job 이 `settlement` 테이블을 건드린다**는 점입니다.
> 해결책은 실행할 Job 을 명시하는 것입니다.
> ```bash
> ./gradlew bootRun --args='--spring.batch.job.name=helloJob'
> ```
> 지금부터 이 코스의 모든 실행 명령은 `--spring.batch.job.name` 을 붙입니다. **붙이는 습관 자체가 안전장치입니다.**

명시해서 다시 돌려 봅니다. 메타데이터를 한번 비우고 시작하겠습니다(P-10 (a)).

```bash
./gradlew bootRun --args='--spring.batch.job.name=helloJob'
```

**결과**
```
INFO 41455 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.921 seconds (process running for 2.203)
INFO 41455 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] launched with the following parameters: [{}]
INFO 41455 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [helloStep]
>>> Hello, Spring Batch 5!
INFO 41455 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [helloStep] executed in 17ms
INFO 41455 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 40ms

BUILD SUCCESSFUL in 4s
```

이제 이 한 번의 실행이 9개 테이블에 무엇을 남겼는지 봅니다.

---

## 1-5. 메타데이터 9개 테이블 지도

먼저 전체 관계를 그립니다.

```
  BATCH_JOB_INSTANCE  ─── "helloJob 을 파라미터 X 로 돌리는 일" 1개
       │ 1:N                (JOB_NAME + JOB_KEY 가 UNIQUE)
       ▼
  BATCH_JOB_EXECUTION ─── 그 일에 대한 "시도" 1회 (실패하면 시도가 늘어남)
       │
       ├──1:N──► BATCH_JOB_EXECUTION_PARAMS    파라미터 한 줄 = 한 행
       ├──1:1──► BATCH_JOB_EXECUTION_CONTEXT   Job 범위 저장소 (직렬화된 JSON)
       │
       │ 1:N
       ▼
  BATCH_STEP_EXECUTION ─ Step 실행 1회. 읽은/쓴/스킵 건수가 전부 여기
       │
       └──1:1──► BATCH_STEP_EXECUTION_CONTEXT  Step 범위 저장소 (재시작 지점 저장)

  ── 위와 별개로, ID 채번용 3개 ──
  BATCH_JOB_SEQ · BATCH_JOB_EXECUTION_SEQ · BATCH_STEP_EXECUTION_SEQ
```

| 테이블 | 한 줄 요약 | 행이 생기는 시점 |
|---|---|---|
| `BATCH_JOB_INSTANCE` | **무엇을** 돌리는가 (Job이름 + 파라미터 지문) | 그 조합의 첫 실행 때 1회 |
| `BATCH_JOB_EXECUTION` | **몇 번째 시도**인가 | 매 실행마다 |
| `BATCH_JOB_EXECUTION_PARAMS` | 그 시도에 넘긴 파라미터 | 파라미터 개수만큼 |
| `BATCH_JOB_EXECUTION_CONTEXT` | Job 범위 key-value | 매 실행마다 1행 |
| `BATCH_STEP_EXECUTION` | Step 실행 결과와 **모든 카운트** | Step 실행마다 |
| `BATCH_STEP_EXECUTION_CONTEXT` | Step 범위 key-value (재시작 지점) | Step 실행마다 1행 |
| `BATCH_JOB_SEQ` | JobInstance ID 채번 | 부팅 시 1행 고정 |
| `BATCH_JOB_EXECUTION_SEQ` | JobExecution ID 채번 | 부팅 시 1행 고정 |
| `BATCH_STEP_EXECUTION_SEQ` | StepExecution ID 채번 | 부팅 시 1행 고정 |

---

## 1-6. BATCH_JOB_INSTANCE — "무엇을 돌리는 일인가"

```sql
SELECT * FROM BATCH_JOB_INSTANCE\G
```

**결과**
```
*************************** 1. row ***************************
JOB_INSTANCE_ID: 1
        VERSION: 0
       JOB_NAME: helloJob
        JOB_KEY: d41d8cd98f00b204e9800998ecf8427e
1 row in set (0.00 sec)
```

| 컬럼 | 의미 |
|---|---|
| `JOB_INSTANCE_ID` | PK. `BATCH_JOB_SEQ` 로 채번 |
| `VERSION` | 낙관적 락 버전. JobInstance 는 만들어진 뒤 변하지 않으므로 항상 0 |
| `JOB_NAME` | Job 빈 이름이 아니라 **`new JobBuilder("helloJob", ...)` 에 준 이름** |
| `JOB_KEY` | **식별 파라미터들의 MD5 해시(32자)** |

`JOB_KEY` 가 이 테이블의 전부입니다. `d41d8cd9...` 는 **빈 문자열의 MD5** 입니다. 파라미터를 안 줬으니 당연합니다.

```sql
SHOW CREATE TABLE BATCH_JOB_INSTANCE\G
```

**결과** (제약 부분만)
```
  UNIQUE KEY `JOB_INST_UN` (`JOB_NAME`,`JOB_KEY`)
```

**`(JOB_NAME, JOB_KEY)` 가 UNIQUE 입니다.** 이 한 줄이 Spring Batch 의 가장 중요한 규칙을 만듭니다.

> **같은 Job 이름 + 같은 식별 파라미터 = 같은 JobInstance = 세상에 단 하나.**

"2025-03-01 정산"이라는 일은 세상에 하나뿐이고, 그 일을 몇 번 시도했든 **일 자체는 하나**라는 모델입니다. 그래서 파라미터 없이 `helloJob` 을 두 번 돌리면 두 번째는 같은 JobInstance 를 가리키게 되고, 이미 COMPLETED 라면 거부됩니다(1-15).

---

## 1-7. BATCH_JOB_EXECUTION — "몇 번째 시도인가"

```sql
SELECT * FROM BATCH_JOB_EXECUTION\G
```

**결과**
```
*************************** 1. row ***************************
 JOB_EXECUTION_ID: 1
          VERSION: 2
  JOB_INSTANCE_ID: 1
      CREATE_TIME: 2026-07-20 13:41:07.412000
       START_TIME: 2026-07-20 13:41:07.428000
         END_TIME: 2026-07-20 13:41:07.468000
           STATUS: COMPLETED
        EXIT_CODE: COMPLETED
     EXIT_MESSAGE:
     LAST_UPDATED: 2026-07-20 13:41:07.468000
1 row in set (0.00 sec)
```

| 컬럼 | 의미 |
|---|---|
| `JOB_EXECUTION_ID` | PK. **재시작하면 이 값이 늘어납니다** (JobInstance 는 그대로) |
| `VERSION` | 낙관적 락. `2` 인 이유는 INSERT(0) → 시작 시 UPDATE(1) → 종료 시 UPDATE(2) |
| `CREATE_TIME` | JobExecution 이 **DB 에 만들어진** 시각 |
| `START_TIME` | Job 이 **실제로 실행을 시작한** 시각 |
| `END_TIME` | 종료 시각. **NULL 이면 아직 돌고 있거나, 프로세스가 강제로 죽은 것** |
| `STATUS` | `BatchStatus`. 프레임워크가 판단하는 상태 |
| `EXIT_CODE` | `ExitStatus` 의 코드. **개발자가 바꿀 수 있는 값** (1-13) |
| `EXIT_MESSAGE` | 실패 시 스택트레이스가 통째로 들어갑니다 (VARCHAR(2500), 넘치면 잘림) |
| `LAST_UPDATED` | 마지막 갱신 시각 |

`CREATE_TIME` 과 `START_TIME` 이 16ms 차이납니다. 그 사이에 Spring Batch 는 파라미터를 검증하고, JobInstance 를 만들고, `JobExecutionListener` 의 `beforeJob` 을 호출합니다.

> ⚠️ **함정 — 5.0 에서 `JOB_CONFIGURATION_LOCATION` 컬럼이 사라졌습니다**
> 4.x 스키마에는 `BATCH_JOB_EXECUTION.JOB_CONFIGURATION_LOCATION VARCHAR(2500)` 이 있었습니다. 5.0 에서 **제거**됐습니다.
> 4.x 프로젝트를 5.x 로 올리면서 **테이블을 그대로 재사용하면** 그 컬럼이 `NOT NULL` 이 아니라서 대개 조용히 넘어갑니다. 하지만 `SELECT *` 를 하는 커스텀 DAO 나 모니터링 쿼리를 쓰고 있었다면 컬럼 개수가 안 맞아 깨집니다.
> **버전을 올릴 때는 `schema-mysql.sql` 의 diff 를 반드시 보세요.** jar 안 `org/springframework/batch/core/schema-mysql.sql` 에 있습니다.

> ⚠️ **함정 — `END_TIME IS NULL` 인 채로 남은 실행**
> 배치 프로세스를 `kill -9` 하거나 파드가 OOM 으로 죽으면 `STATUS` 는 `STARTED` 인데 `END_TIME` 은 `NULL` 인 행이 남습니다. Spring Batch 는 이걸 **"아직 돌고 있는 실행"** 으로 봅니다.
> 그 상태에서 재실행하면 `JobExecutionAlreadyRunningException` 이 납니다. 실제로는 아무것도 안 돌고 있는데 말입니다.
> 운영에서 정말 자주 겪는 좀비 상태이고, 해결은 `JobOperator.abandon()` 또는 수동 UPDATE 입니다. [Step 14](../step-14-operations/) 에서 절차를 정리합니다.
> 지금 기억할 것: **`STATUS='STARTED' AND END_TIME IS NULL` 은 감시 대상 쿼리다.**

---

## 1-8. BATCH_JOB_EXECUTION_PARAMS — 5.0 에서 구조가 통째로 바뀐 테이블

```sql
SELECT * FROM BATCH_JOB_EXECUTION_PARAMS;
```

**결과**
```
Empty set (0.00 sec)
```

파라미터를 안 줬으니 비어 있습니다. 구조만 봅니다.

```sql
DESC BATCH_JOB_EXECUTION_PARAMS;
```

**결과**
```
+------------------+---------------+------+-----+---------+-------+
| Field            | Type          | Null | Key | Default | Extra |
+------------------+---------------+------+-----+---------+-------+
| JOB_EXECUTION_ID | bigint        | NO   | MUL | NULL    |       |
| PARAMETER_NAME   | varchar(100)  | NO   |     | NULL    |       |
| PARAMETER_TYPE   | varchar(100)  | NO   |     | NULL    |       |
| PARAMETER_VALUE  | varchar(2500) | YES  |     | NULL    |       |
| IDENTIFYING      | char(1)       | NO   |     | NULL    |       |
+------------------+---------------+------+-----+---------+-------+
```

**4.x 를 아는 사람이라면 낯설 것입니다.** 비교하면:

| 4.x | 5.x |
|---|---|
| `TYPE_CD` (`STRING`/`DATE`/`LONG`/`DOUBLE` 4종만) | `PARAMETER_TYPE` — **완전한 클래스명** (`java.lang.String`, `java.time.LocalDate` …) |
| `KEY_NAME` | `PARAMETER_NAME` |
| `STRING_VAL` / `DATE_VAL` / `LONG_VAL` / `DOUBLE_VAL` **4개 컬럼** | `PARAMETER_VALUE` **1개 컬럼**(문자열로 저장, 타입은 위 컬럼이 담당) |
| `IDENTIFYING` | `IDENTIFYING` (동일) |

타입이 4종으로 제한됐던 것이 **임의 타입**으로 풀렸습니다. `ConversionService` 로 문자열 ↔ 객체를 변환하기 때문입니다. 값이 어떻게 들어가는지는 파라미터를 실제로 넘겨 보면 됩니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=helloJob greeting=hi runId(long)=7'
```

**결과**
```
INFO 41502 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] launched with the following parameters: [{'greeting':'{value=hi, type=class java.lang.String, identifying=true}','runId':'{value=7, type=class java.lang.Long, identifying=true}'}]
INFO 41502 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [helloStep]
>>> Hello, Spring Batch 5!
INFO 41502 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [helloStep] executed in 16ms
INFO 41502 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=helloJob]] completed with the following parameters: [{...}] and the following status: [COMPLETED] in 45ms
```

```sql
SELECT * FROM BATCH_JOB_EXECUTION_PARAMS;
```

**결과**
```
+------------------+----------------+------------------+-----------------+-------------+
| JOB_EXECUTION_ID | PARAMETER_NAME | PARAMETER_TYPE   | PARAMETER_VALUE | IDENTIFYING |
+------------------+----------------+------------------+-----------------+-------------+
|                2 | greeting       | java.lang.String | hi              | Y           |
|                2 | runId          | java.lang.Long   | 7               | Y           |
+------------------+----------------+------------------+-----------------+-------------+
```

그리고 JobInstance 가 하나 더 생겼습니다.

```sql
SELECT JOB_INSTANCE_ID, JOB_NAME, JOB_KEY FROM BATCH_JOB_INSTANCE;
```

**결과**
```
+-----------------+----------+----------------------------------+
| JOB_INSTANCE_ID | JOB_NAME | JOB_KEY                          |
+-----------------+----------+----------------------------------+
|               1 | helloJob | d41d8cd98f00b204e9800998ecf8427e |
|               2 | helloJob | 7f4c2a1e9b0d5c63a8e2f10b4d97c5ea |
+-----------------+----------+----------------------------------+
```

**파라미터가 다르면 다른 JobInstance 입니다.** `JOB_KEY` 는 `IDENTIFYING='Y'` 인 파라미터들만 정렬해서 해시한 값입니다. `IDENTIFYING='N'` 인 파라미터는 해시에 들어가지 않아, 값이 달라도 같은 JobInstance 가 됩니다. 이 구분이 [Step 03](../step-03-job-parameters/) 의 핵심입니다.

> ⚠️ **함정 — 커맨드라인 파라미터에 타입을 안 쓰면 전부 String 입니다**
> `runId=7` 이라고 쓰면 `java.lang.String` 의 `"7"` 이 됩니다. `runId(long)=7` 이라고 써야 `java.lang.Long` 의 `7` 입니다.
> 그리고 **String `"7"` 과 Long `7` 은 서로 다른 JOB_KEY 를 만듭니다.** 즉 같은 값처럼 보이는데 JobInstance 가 두 개 생깁니다.
> 스케줄러가 `date=2025-03-01` 로 넘기다가 누군가 `date(string)=2025-03-01` 로 바꿔 쓰면, **중복 정산이 에러 없이 실행됩니다.** 타입 표기를 팀 컨벤션으로 고정하세요.

---

## 1-9. BATCH_JOB_EXECUTION_CONTEXT — Job 범위 저장소

```sql
SELECT * FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID = 1\G
```

**결과**
```
*************************** 1. row ***************************
  JOB_EXECUTION_ID: 1
     SHORT_CONTEXT: {"@class":"java.util.HashMap"}
SERIALIZED_CONTEXT: NULL
1 row in set (0.00 sec)
```

| 컬럼 | 의미 |
|---|---|
| `JOB_EXECUTION_ID` | PK 이자 FK. **JobExecution 당 정확히 1행** |
| `SHORT_CONTEXT` | 직렬화 결과가 2500자 이하면 여기에 통째로 (`VARCHAR(2500)`) |
| `SERIALIZED_CONTEXT` | 2500자를 넘으면 여기에 (`TEXT`), 그리고 `SHORT_CONTEXT` 에는 앞부분만 |

지금은 빈 HashMap 입니다. 우리가 아무것도 안 넣었으니까요. `{"@class":"java.util.HashMap"}` 이라는 형태에 주목하세요. **Spring Batch 4.3 부터 기본 직렬화가 Java 직렬화가 아니라 Jackson 기반 JSON** 입니다. 그래서 `@class` 로 타입을 함께 적습니다.

> ⚠️ **함정 — ExecutionContext 에 아무 객체나 넣으면 재시작 때 터집니다**
> `Jackson2ExecutionContextStringSerializer` 는 JSON 으로 직렬화하고, 역직렬화 때 `@class` 로 타입을 복원합니다. 문제는 **기본 생성자와 게터/세터가 없는 객체**입니다.
> 넣을 때는 아무 에러가 없습니다. `put()` 은 그냥 Map 에 담는 것이니까요. **커밋 시점에 직렬화하다 터지거나**, 더 나쁘게는 **직렬화는 됐는데 재시작 시 역직렬화에서 터집니다.**
> 즉 "정상 실행할 때는 멀쩡하고, 장애가 나서 재시작할 때만 터지는" 최악의 타이밍입니다.
> **ExecutionContext 에는 `String`, `Long`, `Double`, `Date` 같은 원시 수준 값만 넣으세요.** [Step 09](../step-09-execution-context/) 에서 정면으로 다룹니다.

---

## 1-10. BATCH_STEP_EXECUTION — 모든 숫자가 여기 있습니다

배치 운영에서 가장 자주 보게 될 테이블입니다.

```sql
SELECT * FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = 1\G
```

**결과**
```
*************************** 1. row ***************************
   STEP_EXECUTION_ID: 1
             VERSION: 3
           STEP_NAME: helloStep
    JOB_EXECUTION_ID: 1
         CREATE_TIME: 2026-07-20 13:41:07.431000
          START_TIME: 2026-07-20 13:41:07.433000
            END_TIME: 2026-07-20 13:41:07.451000
              STATUS: COMPLETED
        COMMIT_COUNT: 1
          READ_COUNT: 0
        FILTER_COUNT: 0
         WRITE_COUNT: 0
     READ_SKIP_COUNT: 0
    WRITE_SKIP_COUNT: 0
  PROCESS_SKIP_COUNT: 0
      ROLLBACK_COUNT: 0
           EXIT_CODE: COMPLETED
        EXIT_MESSAGE:
        LAST_UPDATED: 2026-07-20 13:41:07.451000
1 row in set (0.00 sec)
```

카운트 컬럼 7개가 이 테이블의 본체입니다.

| 컬럼 | 의미 | 언제 늘어나나 |
|---|---|---|
| `COMMIT_COUNT` | 커밋 횟수 | 청크 하나가 커밋될 때마다. **Tasklet 도 1회 커밋하므로 1** |
| `READ_COUNT` | ItemReader 가 반환한 아이템 수 | `read()` 가 non-null 을 반환할 때마다 |
| `FILTER_COUNT` | ItemProcessor 가 **null 을 반환**해 걸러낸 수 | processor 가 null 리턴 시 |
| `WRITE_COUNT` | ItemWriter 에 실제로 전달된 아이템 수 | chunk 쓰기 성공 시 |
| `READ_SKIP_COUNT` | 읽다 예외 나서 스킵된 수 | `faultTolerant().skip()` 설정 시 |
| `PROCESS_SKIP_COUNT` | 처리 중 예외로 스킵된 수 | 동상 |
| `WRITE_SKIP_COUNT` | 쓰다 예외 나서 스킵된 수 | 동상 |
| `ROLLBACK_COUNT` | 롤백 횟수 | 청크 실패 후 재시도할 때마다 |

Tasklet 이라 읽고 쓴 게 없어 전부 0 이고 `COMMIT_COUNT` 만 1 입니다.

> 💡 **실무 팁 — `READ_COUNT = FILTER_COUNT + WRITE_COUNT` 인지 확인하는 습관**
> 청크 Job 이 끝난 뒤 이 등식이 안 맞으면 어딘가에서 아이템이 **조용히 사라진** 것입니다(스킵되었거나, processor 가 예외를 삼켰거나).
> [Step 05](../step-05-chunk/) 에서 `orders` 70,000건을 처리한 뒤 이 등식을 실제로 검증합니다. 정산 배치에서 "합계가 안 맞는다"는 신고가 들어오면 **가장 먼저 볼 곳이 이 세 숫자**입니다.
> ```sql
> SELECT STEP_NAME, READ_COUNT, FILTER_COUNT, WRITE_COUNT,
>        READ_COUNT - FILTER_COUNT - WRITE_COUNT AS leak
> FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?;
> ```
> `leak` 이 0 이 아니면 조사 대상입니다.

---

## 1-11. BATCH_STEP_EXECUTION_CONTEXT — 재시작이 가능한 이유

```sql
SELECT * FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID = 1\G
```

**결과**
```
*************************** 1. row ***************************
 STEP_EXECUTION_ID: 1
     SHORT_CONTEXT: {"@class":"java.util.HashMap","batch.taskletType":"com.example.batch.step01.HelloJobConfig$$Lambda/0x000000012b0c81f8","batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep"}
SERIALIZED_CONTEXT: NULL
1 row in set (0.00 sec)
```

우리가 아무것도 안 넣었는데 두 개의 키가 있습니다. **Spring Batch 가 자기 용도로 넣은 값**입니다.

| 키 | 용도 |
|---|---|
| `batch.taskletType` | 이 Step 이 어떤 Tasklet 구현을 썼는지. 재시작 시 **타입이 바뀌었으면 경고** |
| `batch.stepType` | Step 구현 타입 |

청크 Job 이라면 여기에 `JdbcPagingItemReader.read.count = 45000` 같은 값이 들어갑니다. **재시작 시 리더가 이 값을 읽어 45,000번째부터 이어 읽습니다.** 이것이 배치 재시작의 물리적 실체입니다. [Step 09](../step-09-execution-context/) 에서 이 값을 직접 조작해 봅니다.

> ⚠️ **함정 — Step 이 COMPLETED 로 끝나면 컨텍스트는 "다음 실행에 쓸모없어집니다"**
> 재시작 시 Spring Batch 는 **마지막 실패한 StepExecution 의 컨텍스트**를 복원합니다. COMPLETED 로 끝난 Step 은 아예 재실행하지 않으므로 그 컨텍스트를 쓸 일이 없습니다.
> 그래서 "재시작했는데 왜 45,000번째부터 시작 안 하지?" 의 원인은 대개 **그 Step 이 사실 실패한 게 아니라 COMPLETED 였던 것**입니다. `BATCH_STEP_EXECUTION.STATUS` 를 먼저 확인하세요.

---

## 1-12. 3개의 `_SEQ` 테이블 — MySQL 에는 시퀀스가 없어서

```sql
SELECT * FROM BATCH_JOB_SEQ;
SELECT * FROM BATCH_JOB_EXECUTION_SEQ;
SELECT * FROM BATCH_STEP_EXECUTION_SEQ;
```

**결과**
```
+----+------------+
| ID | UNIQUE_KEY |
+----+------------+
|  2 | 0          |
+----+------------+
+----+------------+
| ID | UNIQUE_KEY |
+----+------------+
|  2 | 0          |
+----+------------+
+----+------------+
| ID | UNIQUE_KEY |
+----+------------+
|  2 | 0          |
+----+------------+
```

각 테이블은 **정확히 1행**입니다. 구조를 보면 이유가 보입니다.

```sql
SHOW CREATE TABLE BATCH_JOB_SEQ\G
```

**결과**
```
CREATE TABLE `BATCH_JOB_SEQ` (
  `ID` bigint NOT NULL,
  `UNIQUE_KEY` char(1) NOT NULL,
  UNIQUE KEY `UNIQUE_KEY_UN` (`UNIQUE_KEY`)
) ENGINE=InnoDB
```

`UNIQUE_KEY` 가 UNIQUE 이고 값은 항상 `'0'` 입니다. **행이 두 개가 될 수 없도록 물리적으로 강제한 것**입니다. Oracle/PostgreSQL 이라면 `CREATE SEQUENCE` 를 썼겠지만, MySQL 에는 시퀀스 객체가 없습니다. 그래서 `MySQLMaxValueIncrementer` 가 이 한 행에 대해:

```sql
UPDATE BATCH_JOB_SEQ SET ID = LAST_INSERT_ID(ID + 1);
SELECT LAST_INSERT_ID();
```

를 실행해 다음 ID 를 뽑습니다. `LAST_INSERT_ID(expr)` 은 세션 단위로 값을 기억하므로 동시 실행에도 안전합니다.

값이 왜 `2` 냐면, JobInstance 를 2개 만들었기 때문입니다(1-8 에서 파라미터를 줘서 하나 더 만들었습니다).

> ⚠️ **함정 — `_SEQ` 테이블을 TRUNCATE 하면 배치가 죽습니다**
> "메타데이터 초기화"를 하겠다고 `TRUNCATE TABLE BATCH_JOB_SEQ` 를 하면 **행이 0개**가 됩니다. 그러면 `UPDATE ... SET ID = LAST_INSERT_ID(ID+1)` 이 0행을 갱신하고, `LAST_INSERT_ID()` 가 이전 값을 반환하거나 0 을 반환합니다.
> 결과는 `DataIntegrityViolationException` 이거나, 더 나쁘게는 **이미 존재하는 ID 로 INSERT 를 시도해 PK 충돌**입니다.
> 프로젝트 셋업의 초기화 스크립트가 `_SEQ` 를 건드리지 않고 `DELETE FROM BATCH_JOB_INSTANCE` 만 하는 이유가 이것입니다. ID 가 1부터 다시 시작하지 않아도 아무 문제 없습니다.
> 굳이 리셋하려면 `UPDATE BATCH_JOB_SEQ SET ID = 0;` 로 값만 되돌리세요. **행을 지우면 안 됩니다.**

---

## 1-13. BatchStatus vs ExitStatus — 왜 둘 다 있는가

`BATCH_JOB_EXECUTION` 에 `STATUS` 와 `EXIT_CODE` 가 따로 있었습니다. 둘 다 `COMPLETED` 라서 같아 보이지만 **완전히 다른 물건**입니다.

| | `BatchStatus` (`STATUS` 컬럼) | `ExitStatus` (`EXIT_CODE` 컬럼) |
|---|---|---|
| 타입 | **enum** — 값이 고정 | **클래스** — 문자열, 마음대로 만들 수 있음 |
| 정하는 주체 | **프레임워크** | 프레임워크 기본값 + **개발자가 덮어쓸 수 있음** |
| 용도 | 재시작 가능 여부 판단, 내부 제어 | **Flow 분기 조건**, 외부 시스템 통보 |
| 값 | `COMPLETED` `STARTING` `STARTED` `STOPPING` `STOPPED` `FAILED` `ABANDONED` `UNKNOWN` | `COMPLETED` `EXECUTING` `FAILED` `NOOP` `STOPPED` `UNKNOWN` + **커스텀** |

`BatchStatus` 는 enum 이므로 순서(severity)가 있습니다.

```
COMPLETED < STARTING < STARTED < STOPPING < STOPPED < FAILED < ABANDONED < UNKNOWN
       (낮을수록 정상)                                    (높을수록 심각)
```

여러 Step 의 상태를 합칠 때 Spring Batch 는 `max()` 를 씁니다. **Step 하나라도 FAILED 면 Job 도 FAILED** 인 이유입니다.

ExitStatus 를 커스텀으로 만들어 봅니다.

```java
@Bean
public Step exitCodeStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("exitCodeStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                long processed = 0;                 // 처리 건수가 0이라고 가정
                if (processed == 0) {
                    // BatchStatus 는 COMPLETED 로 두고, ExitStatus 만 바꿉니다
                    contribution.setExitStatus(new ExitStatus("NO_DATA", "처리 대상이 없습니다"));
                }
                return RepeatStatus.FINISHED;
            }, txManager)
            .build();
}
```

```bash
./gradlew bootRun --args='--spring.batch.job.name=exitCodeJob'
```

**결과**
```
INFO 41603 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=exitCodeJob]] launched with the following parameters: [{}]
INFO 41603 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [exitCodeStep]
INFO 41603 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [exitCodeStep] executed in 15ms
INFO 41603 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=exitCodeJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 39ms
```

```sql
SELECT JOB_EXECUTION_ID, STATUS, EXIT_CODE, EXIT_MESSAGE
FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 1;
```

**결과**
```
+------------------+-----------+-----------+--------------------------+
| JOB_EXECUTION_ID | STATUS    | EXIT_CODE | EXIT_MESSAGE             |
+------------------+-----------+-----------+--------------------------+
|                3 | COMPLETED | NO_DATA   | 처리 대상이 없습니다     |
+------------------+-----------+-----------+--------------------------+
```

**`STATUS = COMPLETED`, `EXIT_CODE = NO_DATA`.** 프레임워크 입장에서는 정상 종료(재시작 필요 없음)지만, 업무 입장에서는 "데이터가 없었다"는 신호를 남긴 것입니다. 이 `NO_DATA` 로 다음 Step 을 건너뛰는 분기를 만드는 것이 [Step 10](../step-10-flow-control/) 의 `.on("NO_DATA").to(...)` 입니다.

> ⚠️ **함정 — `contribution.setExitStatus()` 를 써도 Job 의 EXIT_CODE 가 안 바뀌는 경우**
> Step 의 ExitStatus 가 Job 으로 전파되는 것은 **`SimpleJob` 이 마지막 Step 의 ExitStatus 를 Job 의 것으로 쓰기 때문**입니다. Step 이 여러 개면 **마지막 Step** 것만 올라갑니다.
> 중간 Step 에서 `NO_DATA` 를 세팅하고 Job 의 EXIT_CODE 를 확인했더니 `COMPLETED` 더라 — 는 여기서 옵니다. 중간 Step 의 ExitStatus 는 `BATCH_STEP_EXECUTION.EXIT_CODE` 에는 남아 있으니 그쪽을 보세요.

---

## 1-14. 실패시켜 보기 — 두 상태가 갈라지는 순간

일부러 예외를 던지는 Step 을 만듭니다.

```java
@Bean
public Step failStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("failStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                throw new IllegalStateException("정산 원장이 잠겨 있습니다");
            }, txManager)
            .build();
}
```

```bash
./gradlew bootRun --args='--spring.batch.job.name=failJob'
```

**결과**
```
INFO 41655 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=failJob]] launched with the following parameters: [{}]
INFO 41655 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [failStep]
ERROR 41655 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step failStep in job failJob

java.lang.IllegalStateException: 정산 원장이 잠겨 있습니다
	at com.example.batch.step01.FailJobConfig.lambda$failStep$0(FailJobConfig.java:31)
	at org.springframework.batch.core.step.tasklet.TaskletStep$ChunkTransactionCallback.doInTransaction(TaskletStep.java:407)
	...
INFO 41655 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [failStep] executed in 21ms
INFO 41655 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=failJob]] completed with the following parameters: [{}] and the following status: [FAILED] in 51ms

BUILD SUCCESSFUL in 4s
```

두 가지를 보세요.

1. `status: [FAILED]` — Job 이 실패했습니다.
2. **`BUILD SUCCESSFUL`** — Gradle 은 성공으로 봅니다. `bootRun` 은 JVM 종료 코드만 보는데, `SpringApplication.exit()` 이 있어도 `bootRun` 태스크가 그것을 실패로 옮기지는 않습니다.

> ⚠️ **함정 — 배치가 실패했는데 스케줄러는 성공으로 알았다**
> 이것이 이 스텝에서 가장 위험한 함정입니다. 크론/에어플로우/쿠버네티스 Job 은 **프로세스 종료 코드**로 성공/실패를 판단합니다. Job 이 FAILED 인데 종료 코드가 0 이면 **아무도 모릅니다.**
> 정산이 안 돌았는데 알림이 안 옵니다. 다음 날 아침 CS 로 알게 됩니다.
> 프로젝트 셋업의 `main()` 이 이렇게 생긴 이유가 이것입니다.
> ```java
> System.exit(SpringApplication.exit(SpringApplication.run(BatchLabApplication.class, args)));
> ```
> `SpringApplication.exit()` 은 컨텍스트의 `ExitCodeGenerator` 를 찾아 종료 코드를 계산하고, Spring Batch 의 `JobExecutionExitCodeGenerator` 가 **FAILED 면 1** 을 돌려줍니다. 운영 배포에서는 `java -jar` 로 직접 실행하므로 이 코드가 그대로 살아납니다.
> 확인해 봅니다.
> ```bash
> ./gradlew bootJar -q && java -jar build/libs/spring-batch5-lab-1.0.0.jar --spring.batch.job.name=failJob > /dev/null 2>&1; echo "exit=$?"
> ```
> **결과**
> ```
> exit=1
> ```
> 성공 Job 이면 `exit=0` 입니다. **배치 프로젝트를 만들면 가장 먼저 이걸 확인하세요.**

메타데이터도 봅니다.

```sql
SELECT JOB_EXECUTION_ID, STATUS, EXIT_CODE, LEFT(EXIT_MESSAGE, 60) AS msg
FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 1;
```

**결과**
```
+------------------+--------+-----------+--------------------------------------------------------------+
| JOB_EXECUTION_ID | STATUS | EXIT_CODE | msg                                                          |
+------------------+--------+-----------+--------------------------------------------------------------+
|                4 | FAILED | FAILED    | java.lang.IllegalStateException: 정산 원장이 잠겨 있습니다    |
+------------------+--------+-----------+--------------------------------------------------------------+
```

`EXIT_MESSAGE` 에 스택트레이스가 통째로 들어갑니다. `VARCHAR(2500)` 이므로 긴 트레이스는 **잘립니다**. 원인 파악은 애플리케이션 로그로 하고, 이 컬럼은 "대략 뭐 때문이었나" 확인용으로 쓰세요.

---

## 1-15. 한 번 더 돌리면 — 성공한 Job 은 다시 안 돕니다

성공한 `helloJob` 을 파라미터 없이 다시 실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=helloJob'
```

**결과**
```
INFO 41702 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.898 seconds (process running for 2.171)
INFO 41702 --- [           main] o.s.b.a.b.JobLauncherApplicationRunner    : Job helloJob was not executed as it was already completed with the same parameters

BUILD SUCCESSFUL in 4s
```

**에러가 아닙니다.** `JobLauncherApplicationRunner` 가 `JobInstanceAlreadyCompleteException` 을 잡아서 INFO 로 남기고 넘어갑니다. `BATCH_JOB_EXECUTION` 에는 **행이 추가되지 않습니다**.

```sql
SELECT COUNT(*) AS execs FROM BATCH_JOB_EXECUTION
WHERE JOB_INSTANCE_ID = 1;
```

**결과**
```
+-------+
| execs |
+-------+
|     1 |
+-------+
```

1-6 에서 본 `(JOB_NAME, JOB_KEY)` UNIQUE 제약이 만든 결과입니다. "같은 일은 한 번만 성공한다."

> 💡 **실무 팁 — 이 동작은 축복입니다**
> 처음 만나면 불편하지만, 이건 **중복 정산을 막는 안전장치**입니다. 크론이 실수로 두 번 트리거되어도 두 번째는 안 돕니다.
> 매번 새로 돌려야 하는 Job(예: 임시 데이터 정리)이라면 `RunIdIncrementer` 로 매 실행마다 다른 파라미터를 붙이면 됩니다. 반대로 **날짜별로 딱 한 번만 돌아야 하는 정산**은 `date` 파라미터를 identifying 으로 두어 이 제약을 그대로 활용합니다.
> 어느 쪽을 택할지, 그리고 `RunIdIncrementer` 를 무심코 붙였다가 중복 정산이 나는 사고는 [Step 03](../step-03-job-parameters/) 에서 다룹니다.

마지막으로 이번 스텝의 전체 흔적을 한 번에 봅니다.

```sql
SELECT i.JOB_INSTANCE_ID inst, i.JOB_NAME, e.JOB_EXECUTION_ID exec_id,
       e.STATUS, e.EXIT_CODE,
       TIMESTAMPDIFF(MICROSECOND, e.START_TIME, e.END_TIME)/1000 AS ms,
       (SELECT COUNT(*) FROM BATCH_STEP_EXECUTION s
         WHERE s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID) AS steps
FROM BATCH_JOB_INSTANCE i
JOIN BATCH_JOB_EXECUTION e ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
ORDER BY e.JOB_EXECUTION_ID;
```

**결과**
```
+------+-------------+---------+-----------+-----------+---------+-------+
| inst | JOB_NAME    | exec_id | STATUS    | EXIT_CODE | ms      | steps |
+------+-------------+---------+-----------+-----------+---------+-------+
|    1 | helloJob    |       1 | COMPLETED | COMPLETED | 40.0000 |     1 |
|    2 | helloJob    |       2 | COMPLETED | COMPLETED | 45.0000 |     1 |
|    3 | exitCodeJob |       3 | COMPLETED | NO_DATA   | 39.0000 |     1 |
|    4 | failJob     |       4 | FAILED    | FAILED    | 51.0000 |     1 |
+------+-------------+---------+-----------+-----------+---------+-------+
```

**이 쿼리를 즐겨찾기에 넣어 두세요.** 운영에서 "어제 배치 어떻게 됐어?" 에 답하는 가장 빠른 방법입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 최소 Job | `new JobBuilder(name, jobRepository).start(step).build()` — 팩토리 없음 |
| 최소 Step | `new StepBuilder(name, jobRepository).tasklet(t, txManager).build()` |
| 자동 실행 | `JobLauncherApplicationRunner` 가 부팅 **후** `callRunners()` 단계에서 실행 |
| 실행 제한 | `--spring.batch.job.name=xxx` 없으면 **모든 Job 이 실행됨** |
| `BATCH_JOB_INSTANCE` | "무엇을 돌리는 일" — `(JOB_NAME, JOB_KEY)` UNIQUE |
| `JOB_KEY` | identifying 파라미터들의 MD5. 파라미터 없으면 `d41d8cd9...` |
| `BATCH_JOB_EXECUTION` | "몇 번째 시도" — `END_TIME IS NULL` 은 좀비 후보 |
| `BATCH_JOB_EXECUTION_PARAMS` | 5.0 에서 **컬럼 구조가 통째로 변경**(`PARAMETER_NAME/TYPE/VALUE`) |
| `BATCH_*_EXECUTION_CONTEXT` | Jackson JSON 직렬화. 2500자 넘으면 `SERIALIZED_CONTEXT` |
| `BATCH_STEP_EXECUTION` | 카운트 7종의 원천. `READ = FILTER + WRITE` 등식 확인 |
| `_SEQ` 3개 | MySQL 에 시퀀스가 없어 만든 1행짜리 채번 테이블. **TRUNCATE 금지** |
| `BatchStatus` | enum, 프레임워크 결정, 재시작 판단용. `max()` 로 합산 |
| `ExitStatus` | 문자열, 개발자가 커스텀 가능, **Flow 분기용** |
| 종료 코드 | `SpringApplication.exit()` 없으면 **FAILED 여도 exit 0** |
| 재실행 | 성공한 JobInstance 는 다시 안 돎 (`already completed`) |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 두 개의 Step 을 순서대로 실행하는 Job 을 만들고, `BATCH_STEP_EXECUTION` 에 행이 2개 생기는 것을 확인하기
2. Tasklet 이 `RepeatStatus.CONTINUABLE` 을 3번 반환한 뒤 `FINISHED` 를 반환하도록 만들고, `COMMIT_COUNT` 가 몇이 되는지 예측 후 확인
3. `contribution.setExitStatus()` 로 ExitStatus 를 `SKIPPED` 로 바꾸되 `BatchStatus` 는 `COMPLETED` 로 유지하기
4. 예외를 던지는 Step 을 만들고, `BATCH_JOB_EXECUTION.EXIT_MESSAGE` 에서 예외 클래스명만 추출하는 SQL 작성
5. `JobExecution` 의 `START_TIME`/`END_TIME` 으로 "최근 24시간 내 3초 이상 걸린 Job" 을 찾는 SQL 작성
6. 파라미터 `runDate(string)=2025-03-01` 과 `runDate=2025-03-01` 이 서로 다른 JobInstance 를 만드는지 확인하고 `JOB_KEY` 비교

---

## 다음 단계

Job 하나를 돌려 보고 그것이 남긴 9개 테이블의 흔적을 전부 읽었습니다. 그런데 우리가 쓴 `new JobBuilder(...)` / `new StepBuilder(...)` 는 Spring Batch 4.x 예제와 생김새가 완전히 다릅니다.
다음 스텝에서는 **왜 5.0 이 `JobBuilderFactory`/`StepBuilderFactory` 를 제거했는지**, 4.x 코드를 어떻게 옮기는지, 그리고 `@EnableBatchProcessing` 을 무심코 붙였을 때 Boot 자동설정이 통째로 꺼지는 함정을 다룹니다.

→ [Step 02 — Job 과 Step 의 구조](../step-02-job-step/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 의 설정 클래스들을 프로젝트에 옮겨 놓고 `--spring.batch.job.name` 을 바꿔 가며 1-2 ~ 1-15 를 순서대로 재현한 뒤, `Exercise.java` 의 6문제를 직접 채워 보고, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step01` 패키지이며, 여러 설정 클래스를 하나의 파일에 담기 위해 **`static class` 중첩**을 사용합니다. 실제 프로젝트에 넣을 때는 중첩 클래스를 그대로 두어도 되고(`@Configuration` 이 붙어 있으므로 컴포넌트 스캔이 잡습니다), 파일로 분리해도 됩니다.

### Practice.java

본문 1-1 ~ 1-15 의 모든 Java 예제를 절 번호 주석과 함께 모아 둔 파일입니다.

- 최상위 `Practice` 클래스 안에 `HelloJobConfig`(1-1), `ExitCodeJobConfig`(1-13), `FailJobConfig`(1-14), `TwoStepJobConfig`(1-15 확장) 네 개의 `@Configuration static class` 가 들어 있습니다. **Job 빈이 4개이므로 `--spring.batch.job.name` 을 반드시 지정하세요.** 안 그러면 네 개가 전부 돕니다(1-4 의 함정).
- `[1-14]` 의 `failJob` 은 **의도적으로 실패합니다.** 실행하면 `ERROR` 로그와 스택트레이스가 쏟아지는 게 정상입니다. 이 실행 뒤 `BATCH_JOB_EXECUTION` 에 `FAILED` 행이 남고, 다음에 `failJob` 을 같은 파라미터로 다시 돌리면 **이번에는 실행됩니다**(실패한 JobInstance 는 재시작 가능하므로). 1-15 의 "성공한 Job 만 거부된다"와 대비해서 확인해 보세요.
- 파일 하단의 `METADATA_QUERIES` 상수에 1-6 ~ 1-15 에서 쓴 SQL 을 텍스트 블록으로 모아 뒀습니다. 복사해서 mysql CLI 에 붙여 넣는 용도이며 코드에서 실행하지는 않습니다.
- `[1-13]` 의 `ExitStatus("NO_DATA", ...)` 는 **`BatchStatus` 를 건드리지 않습니다.** 실행 후 `STATUS` 는 `COMPLETED`, `EXIT_CODE` 는 `NO_DATA` 여야 정상입니다. 둘 다 `NO_DATA` 로 나온다면 `contribution.setExitStatus()` 가 아니라 `stepExecution.setStatus()` 를 쓴 것입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `// 여기에 작성:` 자리를 채우면 됩니다.

- **문제 1·2·3** 은 Java 코드를 작성하는 문제이고, **문제 4·5** 는 SQL 을 문자열 상수로 작성하는 문제, **문제 6** 은 실행 후 관찰한 값을 주석으로 적는 문제입니다.
- 문제 2 의 `RepeatStatus.CONTINUABLE` 은 예측이 어긋나기 쉽습니다. **답을 보기 전에 `COMMIT_COUNT` 가 몇일지 숫자를 적어 두고** 실행하세요. 예측과 결과가 다르다면 그 차이가 바로 이 문제의 학습 포인트입니다.
- 문제 3 은 "`BatchStatus` 는 유지하고 `ExitStatus` 만 바꾼다"가 조건입니다. `chunkContext.getStepContext().getStepExecution().setExitStatus(...)` 로도 되지만, **Tasklet 안에서는 `contribution` 을 쓰는 것이 정석**입니다(트랜잭션 롤백 시 되돌려지기 때문). 어느 쪽으로 풀었든 이유를 설명할 수 있어야 합니다.
- 문제 6 은 **두 번 실행해야** 답을 낼 수 있습니다. `runDate(string)=2025-03-01` 로 한 번, `runDate=2025-03-01` 로 한 번. 두 번째가 "already completed" 로 스킵되면 답이 하나로 나온 것이고, 새 JobInstance 가 생기면 답이 둘로 나온 것입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석입니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 2** 가 이 스텝에서 가장 놀라운 답입니다. `CONTINUABLE` 을 3번 반환하면 Tasklet 이 4번 호출되지만 `COMMIT_COUNT` 는 **4** 입니다. `TaskletStep` 은 **Tasklet 호출 한 번당 트랜잭션 하나**를 열기 때문입니다. "한 Tasklet = 한 트랜잭션"이라는 흔한 오해를 깨는 지점이라 주석을 길게 달아 두었습니다.
- **정답 3** 은 `contribution.setExitStatus()` 와 `stepExecution.setExitStatus()` 의 차이를 설명합니다. 전자는 `StepContribution` 에 모아 뒀다가 **청크 커밋이 성공할 때만** StepExecution 에 반영됩니다. 후자는 즉시 반영되어 **롤백돼도 남습니다.** 실패한 청크의 ExitStatus 가 살아남는 건 대개 버그입니다.
- **정답 4** 는 `SUBSTRING_INDEX(EXIT_MESSAGE, ':', 1)` 로 예외 FQCN 을 뽑습니다. 다만 `EXIT_MESSAGE` 가 비어 있을 수 있고(성공 시), 스택트레이스가 2500자에서 잘릴 수 있다는 한계를 함께 적었습니다. **메타데이터는 로그의 대체재가 아니라는** 결론으로 이어집니다.
- **정답 5** 는 `TIMESTAMPDIFF(MICROSECOND, START_TIME, END_TIME) > 3000000` 을 씁니다. `SECOND` 단위로 빼면 3.9초가 3으로 내려앉아 놓치므로 마이크로초로 계산하는 이유를 설명합니다. 또한 `END_TIME IS NOT NULL` 조건을 반드시 붙여야 좀비 실행이 결과를 오염시키지 않습니다.
- **정답 6** 은 `JOB_KEY` 가 **다릅니다.** 파라미터 값 문자열이 같아도 `PARAMETER_TYPE` 이 해시 입력에 포함되기 때문입니다. 이것이 1-8 의 함정이며, 스케줄러 설정 한 글자 차이로 중복 정산이 나는 경로를 SQL 로 재현합니다.

```java file="./Solution.java"
```
