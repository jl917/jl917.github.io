# Step 02 — Job 과 Step 의 구조

> **학습 목표**
> - Job → Step → Tasklet → (ItemReader/Processor/Writer) 계층 구조를 그림과 코드로 연결한다
> - Spring Batch 5.0 에서 `JobBuilderFactory` / `StepBuilderFactory` 가 **왜** 사라졌는지 이해한다
> - 4.x 코드와 5.x 코드를 before/after 로 나란히 놓고 마이그레이션 규칙을 손에 익힌다
> - `JobRepository` 를 빌더 **생성자**로 넘기는 새 방식과, `PlatformTransactionManager` 를 Step 에 넘겨야 하는 이유를 안다
> - `@EnableBatchProcessing` 을 붙였을 때 Boot 자동설정이 통째로 꺼지는 것을 **직접 재현한다**
> - Step 빈이 싱글턴이라는 사실이 만드는 상태 공유 사고를 확인한다
>
> **선행 스텝**: [Step 01 — 환경 구축과 첫 Job](../step-01-setup/)
> **예상 소요**: 80분

---

## 2-0. 이 스텝이 필요한 이유

인터넷에서 Spring Batch 예제를 검색하면 90% 는 이렇게 시작합니다.

```java
@Autowired private JobBuilderFactory jobBuilderFactory;
@Autowired private StepBuilderFactory stepBuilderFactory;
```

그리고 여러분의 프로젝트에서는 **컴파일이 안 됩니다.**

```
error: cannot find symbol
  symbol:   class JobBuilderFactory
  location: class com.example.batch.step02.LegacyJobConfig
```

이 스텝은 그 에러를 고치는 방법이 아니라, **왜 그렇게 바뀌었고 그 변화가 무엇을 요구하는지**를 다룹니다. 기계적으로 치환하면 컴파일은 되지만 조용히 잘못 도는 경우가 있기 때문입니다.

---

## 2-1. Job / Step / Tasklet 계층 구조

먼저 전체 그림을 고정합니다.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Job                                    "settlementJob"              │
│  · 배치 작업 전체. 이름을 가진다.                                      │
│  · Step 들의 실행 순서(또는 흐름)를 정의한다.                          │
│  · JobRepository 에 JobInstance / JobExecution 을 남긴다.             │
│                                                                      │
│   ┌────────────────────────────────────────────────────────────┐    │
│   │ Step 1  "prepareStep"           ← TaskletStep              │    │
│   │  · 독립적인 처리 단계. 자기만의 트랜잭션 경계를 가진다.       │    │
│   │  · StepExecution 을 남긴다 (모든 카운트의 출처).             │    │
│   │                                                            │    │
│   │   ┌──────────────────────────────────────────────┐        │    │
│   │   │ Tasklet  (인터페이스, 메서드 하나)             │        │    │
│   │   │   RepeatStatus execute(StepContribution,      │        │    │
│   │   │                        ChunkContext)          │        │    │
│   │   │  → FINISHED 를 반환할 때까지 반복 호출된다     │        │    │
│   │   │  → 호출 1회 = 트랜잭션 1개 = 커밋 1회          │        │    │
│   │   └──────────────────────────────────────────────┘        │    │
│   └────────────────────────────────────────────────────────────┘    │
│                              ↓ next                                  │
│   ┌────────────────────────────────────────────────────────────┐    │
│   │ Step 2  "settlementStep"        ← 같은 TaskletStep 이다!    │    │
│   │                                                            │    │
│   │   ┌──────────────────────────────────────────────┐        │    │
│   │   │ ChunkOrientedTasklet  (Tasklet 의 구현체)      │        │    │
│   │   │   ┌────────────┐ ┌───────────────┐ ┌────────┐│        │    │
│   │   │   │ ItemReader │→│ ItemProcessor │→│ Writer ││        │    │
│   │   │   └────────────┘ └───────────────┘ └────────┘│        │    │
│   │   │   chunk(1000) 만큼 읽고 처리한 뒤 한 번에 쓴다 │        │    │
│   │   └──────────────────────────────────────────────┘        │    │
│   └────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

여기서 놓치기 쉬운 사실 하나를 강조합니다.

> **청크 지향 Step 도 결국 TaskletStep 입니다.**
> `.chunk(1000, txManager)` 로 만든 Step 은 특별한 종류의 Step 이 아니라, **`ChunkOrientedTasklet` 이라는 Tasklet 을 품은 `TaskletStep`** 입니다.
> 그래서 Step 01 에서 배운 "Tasklet 호출 1회 = 트랜잭션 1개" 규칙이 그대로 적용되고, 그 결과 **청크 하나 = 트랜잭션 하나**가 됩니다. [Step 05](../step-05-chunk/) 의 청크 커밋 동작이 여기서 유도됩니다.

| 계층 | 인터페이스 | 대표 구현 | 메타데이터 |
|---|---|---|---|
| Job | `Job` | `SimpleJob`, `FlowJob` | `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION` |
| Step | `Step` | `TaskletStep`, `PartitionStep`, `FlowStep`, `JobStep` | `BATCH_STEP_EXECUTION` |
| Tasklet | `Tasklet` | 람다, `ChunkOrientedTasklet`, `SystemCommandTasklet` | 없음 (Step 에 집계) |
| 청크 3요소 | `ItemReader/Processor/Writer` | `JdbcPagingItemReader` 등 | 없음 (카운트로 반영) |

---

## 2-2. 4.x 의 방식 — 무엇이 문제였나

Spring Batch 4.3 코드는 이렇게 생겼습니다.

```java
// ⚠️ Spring Batch 4.3 — 5.1 에서는 컴파일되지 않습니다
package com.example.batch.legacy;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableBatchProcessing                     // 4.x 에서는 필수였습니다
public class LegacySettlementJobConfig {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;

    public LegacySettlementJobConfig(JobBuilderFactory jbf, StepBuilderFactory sbf) {
        this.jobBuilderFactory = jbf;
        this.stepBuilderFactory = sbf;
    }

    @Bean
    public Job settlementJob() {
        return jobBuilderFactory.get("settlementJob")
                .start(settlementStep())
                .build();
    }

    @Bean
    public Step settlementStep() {
        return stepBuilderFactory.get("settlementStep")
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("정산 중...");
                    return RepeatStatus.FINISHED;
                })
                .build();                  // 트랜잭션 매니저를 안 넘깁니다
        }
}
```

`JobBuilderFactory.get(name)` 은 사실 이 한 줄이 전부였습니다.

```java
// Spring Batch 4.3 의 JobBuilderFactory (요약)
public class JobBuilderFactory {
    private final JobRepository jobRepository;
    public JobBuilder get(String name) {
        return new JobBuilder(name).repository(this.jobRepository);
    }
}
```

**팩토리가 한 일은 "JobRepository 를 주입해 주는 것" 하나뿐입니다.** 그런데 이 편의를 위해 치른 대가가 컸습니다.

| 문제 | 설명 |
|---|---|
| 숨은 의존성 | `JobRepository` 가 어디서 왔는지 코드에 안 보입니다. `@EnableBatchProcessing` 이 등록한 것을 팩토리가 물고 있습니다 |
| 커스터마이징 어려움 | 다른 `JobRepository`(예: 다른 DataSource)를 쓰려면 팩토리 자체를 갈아치워야 합니다 |
| `@EnableBatchProcessing` 강제 | 팩토리 빈이 그 애너테이션 없이는 존재하지 않습니다 |
| Step 의 트랜잭션 매니저가 암묵적 | `.tasklet(t)` 만 쓰면 **컨텍스트에 하나뿐인 `PlatformTransactionManager` 를 자동으로** 씁니다. 여러 개면? |

마지막 항목이 특히 위험했습니다. JPA + JDBC 를 함께 쓰는 프로젝트에서 `JpaTransactionManager` 와 `DataSourceTransactionManager` 가 둘 다 있으면, **어느 쪽이 잡힐지 코드만 봐서는 알 수 없었습니다.**

---

## 2-3. 5.x 로 옮기기 — before / after

같은 설정을 5.1 로 옮기면 이렇습니다.

```java
// ✅ Spring Batch 5.1
package com.example.batch.step02;

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
public class SettlementJobConfig {          // @EnableBatchProcessing 없음

    @Bean
    public Job settlementJob(JobRepository jobRepository, Step settlementStep) {
        return new JobBuilder("settlementJob", jobRepository)
                .start(settlementStep)
                .build();
    }

    @Bean
    public Step settlementStep(JobRepository jobRepository,
                               PlatformTransactionManager txManager) {
        return new StepBuilder("settlementStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("정산 중...");
                    return RepeatStatus.FINISHED;
                }, txManager)               // 트랜잭션 매니저를 명시
                .build();
    }
}
```

변경점을 한 줄씩 대응시킵니다.

| # | 4.x | 5.x | 왜 |
|---|---|---|---|
| 1 | `@EnableBatchProcessing` 필수 | **제거** | Boot 3 자동설정이 대신함. 붙이면 오히려 손해 (2-6) |
| 2 | `JobBuilderFactory jbf` 필드 주입 | **삭제** | `JobRepository` 를 `@Bean` 메서드 파라미터로 |
| 3 | `jobBuilderFactory.get("x")` | `new JobBuilder("x", jobRepository)` | 의존성을 눈에 보이게 |
| 4 | `stepBuilderFactory.get("x")` | `new StepBuilder("x", jobRepository)` | 동일 |
| 5 | `.tasklet(t)` | `.tasklet(t, txManager)` | 트랜잭션 매니저를 명시 |
| 6 | `.<I,O>chunk(1000)` | `.<I,O>chunk(1000, txManager)` | 동일 |
| 7 | `settlementStep()` 직접 호출 | `Step settlementStep` 파라미터 주입 | 컨테이너가 만든 빈 보장 |
| 8 | `javax.*` | `jakarta.*` | Jakarta EE 9+ |

> ⚠️ **함정 — "deprecated 라고 나왔는데 왜 컴파일이 안 되죠?"**
> 정확한 타임라인은 이렇습니다.
>
> | 버전 | `JobBuilderFactory` / `StepBuilderFactory` |
> |---|---|
> | 4.3 | 정상 API |
> | **5.0** | `@Deprecated` — 경고만 뜨고 **동작은 함** |
> | **5.1** | **완전 삭제** — 컴파일 에러 |
>
> 이 코스는 Spring Batch **5.1.1** 이므로 삭제된 상태입니다.
> 그래서 "5.0 으로 올렸을 때는 경고만 떴는데 5.1 로 올리니 빌드가 깨졌다"가 정상적인 경험입니다.
> **5.0 으로 올릴 때 경고를 무시하고 넘어간 팀이 5.1 에서 한꺼번에 터집니다.** deprecation 경고는 청구서가 미뤄진 것이지 면제된 게 아닙니다.

두 코드를 실제로 돌려 결과가 같은지 확인합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
```

**결과**
```
INFO 42107 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.913 seconds (process running for 2.188)
INFO 42107 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{}]
INFO 42107 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
정산 중...
INFO 42107 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [settlementStep] executed in 19ms
INFO 42107 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 44ms

BUILD SUCCESSFUL in 5s
```

로그는 4.x 와 완전히 동일합니다. **동작이 바뀐 게 아니라 의존성을 표현하는 방식이 바뀐 것**입니다.

---

## 2-4. JobRepository 를 생성자로 넘긴다는 것의 의미

`new JobBuilder("settlementJob", jobRepository)` 는 단순한 문법 변경이 아닙니다.

```java
// Spring Batch 5.1 의 JobBuilder 생성자
public class JobBuilder extends JobBuilderHelper<JobBuilder> {

    public JobBuilder(String name, JobRepository jobRepository) {
        super(name, jobRepository);       // JobRepository 가 필수 인자
    }

    @Deprecated(since = "5.0")
    public JobBuilder(String name) { ... }   // 5.1 에서도 남아 있지만 쓰지 마세요
}
```

**이름 하나로 만드는 생성자는 여전히 존재합니다.** 그런데 그걸 쓰면:

```java
// ⚠️ 컴파일도 되고 IDE 도 조용하지만
return new JobBuilder("settlementJob")
        .start(settlementStep)
        .build();
```

**결과**
```
INFO 42155 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{}]
ERROR 42155 --- [           main] o.s.boot.SpringApplication               : Application run failed

java.lang.IllegalArgumentException: JobRepository must be set
	at org.springframework.util.Assert.state(Assert.java:76)
	at org.springframework.batch.core.job.AbstractJob.afterPropertiesSet(AbstractJob.java:141)
	...
```

다행히 **시끄럽게 실패합니다.** `AbstractJob.afterPropertiesSet()` 이 검사하기 때문입니다. 이건 좋은 설계입니다.

> 💡 **실무 팁 — 여러 DB 를 쓰는 배치에서 진짜 이득이 나옵니다**
> 생성자로 넘기게 되면서 "이 Job 은 어떤 JobRepository 를 쓰는가"를 **Job 마다 다르게** 지정할 수 있습니다.
> 메타데이터 DB 를 업무 DB 와 분리한 환경(프로젝트 셋업 `P-1` 의 실무 구성)에서는 다음처럼 씁니다.
> ```java
> @Bean
> public Job settlementJob(@Qualifier("metaJobRepository") JobRepository repo, Step s) {
>     return new JobBuilder("settlementJob", repo).start(s).build();
> }
> ```
> 4.x 에서는 `JobBuilderFactory` 를 통째로 커스텀 빈으로 대체해야 했습니다. 지금은 `@Qualifier` 한 줄입니다.

---

## 2-5. StepBuilder 와 PlatformTransactionManager

Step 쪽 변화가 더 중요합니다. `.tasklet(t)` 에서 `.tasklet(t, txManager)` 로 바뀐 이유를 봅니다.

```java
// Spring Batch 5.1 의 StepBuilder
public class StepBuilder extends StepBuilderHelper<StepBuilder> {

    public StepBuilder(String name, JobRepository jobRepository) { ... }

    public TaskletStepBuilder tasklet(Tasklet tasklet, PlatformTransactionManager txManager) { ... }

    public <I, O> SimpleStepBuilder<I, O> chunk(int chunkSize, PlatformTransactionManager txManager) { ... }

    public <I, O> SimpleStepBuilder<I, O> chunk(CompletionPolicy policy, PlatformTransactionManager txManager) { ... }
}
```

**Step 은 트랜잭션 경계의 주인입니다.** 청크 하나가 하나의 트랜잭션이고, 그 트랜잭션을 여는 것이 `PlatformTransactionManager` 입니다. 즉 트랜잭션 매니저는 Step 의 **부수적 설정이 아니라 필수 구성요소**인데, 4.x 는 그걸 "알아서 찾아 주는" 방식으로 숨겼습니다.

프로젝트 셋업에서 `spring-boot-starter-data-jpa` 를 넣었으므로 우리 컨텍스트에는 트랜잭션 매니저 후보가 있습니다. 무엇이 주입됐는지 확인해 봅니다.

```java
@Bean
public Step whichTxStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("whichTxStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                System.out.println(">>> txManager = " + txManager.getClass().getName());
                return RepeatStatus.FINISHED;
            }, txManager)
            .build();
}
```

**결과**
```
INFO 42203 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [whichTxStep]
>>> txManager = org.springframework.orm.jpa.JpaTransactionManager
INFO 42203 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [whichTxStep] executed in 20ms
```

**`JpaTransactionManager` 입니다.** `DataSourceTransactionManager` 가 아닙니다. `spring-boot-starter-data-jpa` 가 있으면 `JpaTransactionManager` 가 우선 등록되기 때문입니다.

> ⚠️ **함정 — 트랜잭션 매니저가 둘인데 JDBC 쓰기가 커밋되지 않는다**
> `JpaTransactionManager` 는 **EntityManager 의 트랜잭션**을 관리합니다. 같은 DataSource 를 공유하므로 대개 `JdbcTemplate` 쓰기도 같은 커넥션·같은 트랜잭션에 참여합니다. 대개는요.
> 문제는 DataSource 가 둘 이상일 때입니다. `JpaTransactionManager` 는 A 커넥션의 트랜잭션을 열었는데 `JdbcBatchItemWriter` 는 B DataSource 를 쓰도록 설정돼 있으면, **쓰기는 자기 오토커밋으로 나가고 롤백에 참여하지 않습니다.**
> 청크가 실패해 롤백돼도 **이미 쓴 데이터는 남습니다.** 예외는 정상적으로 나므로 로그만 보면 "실패했으니 롤백됐겠지"라고 믿게 됩니다. 그리고 재시작하면 같은 데이터가 한 번 더 들어갑니다.
> 5.0 이 트랜잭션 매니저를 인자로 승격시킨 이유가 정확히 이것입니다. **"어느 트랜잭션 매니저인가"를 Step 마다 눈으로 확인하게 만든 것**입니다.
> JPA 를 쓰지 않는 순수 JDBC 배치라면 명시적으로 선택하세요.
> ```java
> @Bean
> public Step jdbcOnlyStep(JobRepository jobRepository,
>                          @Qualifier("jdbcTxManager") PlatformTransactionManager tx) { ... }
>
> @Bean("jdbcTxManager")
> public PlatformTransactionManager jdbcTxManager(DataSource ds) {
>     return new DataSourceTransactionManager(ds);
> }
> ```

> 💡 **트랜잭션이 필요 없는 Step 도 있습니다**
> 파일 이동, API 호출, 알림 발송처럼 DB 를 안 건드리는 Tasklet 에도 `PlatformTransactionManager` 는 **문법상 필수**입니다.
> 그런 경우 `ResourcelessTransactionManager` 를 넘기면 실제 DB 트랜잭션을 열지 않아 커넥션을 낭비하지 않습니다.
> ```java
> .tasklet(notifyTasklet, new ResourcelessTransactionManager())
> ```
> 단, **JobRepository 의 메타데이터 갱신은 여전히 진짜 DB 트랜잭션**으로 나갑니다. 헷갈리지 마세요.

---

## 2-6. `@EnableBatchProcessing` — 붙이면 손해입니다

여기가 이 스텝의 핵심입니다.

Spring Boot 3 + Spring Batch 5 에서 `@EnableBatchProcessing` 은 **필수가 아닙니다.** 그런데 4.x 습관으로 붙이는 사람이 많고, 붙이면 조용히 나빠집니다.

Boot 의 `BatchAutoConfiguration` 선언을 보면 이유가 즉시 보입니다.

```java
@AutoConfiguration(after = { HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class })
@ConditionalOnClass({ JobLauncher.class, DataSource.class, DatabasePopulator.class })
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class })
@ConditionalOnMissingBean(value = DefaultBatchConfiguration.class,
                          annotation = EnableBatchProcessing.class)     // ★
@EnableConfigurationProperties(BatchProperties.class)
public class BatchAutoConfiguration { ... }
```

**★ 줄이 전부입니다.** "`@EnableBatchProcessing` 이 붙은 빈이 있거나 `DefaultBatchConfiguration` 을 상속한 빈이 있으면, 이 자동설정은 **통째로 물러난다**."

`BatchAutoConfiguration` 이 물러나면 무엇이 없어지는지 표로 봅니다.

| 없어지는 것 | 결과 |
|---|---|
| `JobLauncherApplicationRunner` | **부팅 시 Job 이 자동 실행되지 않음** |
| `BatchDataSourceScriptDatabaseInitializer` | `spring.batch.jdbc.initialize-schema` 가 무시됨 → `BATCH_*` 테이블 미생성 |
| `spring.batch.job.name` 처리 | 실행할 Job 지정 불가 |
| `spring.batch.jdbc.table-prefix` 반영 | `application.yml` 설정이 안 먹음 |
| `BatchConversionServiceCustomizer` | 커스텀 JobParameter 타입 변환 설정 무시 |

직접 재현합니다. `@EnableBatchProcessing` 을 아무 `@Configuration` 에나 붙입니다.

```java
@Configuration
@EnableBatchProcessing            // ⚠️ 이 한 줄만 추가
public class TrapConfig { }
```

메타데이터를 전부 지우고(`P-10 (b)` 볼륨 초기화) 다시 띄웁니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
```

**결과**
```
INFO 42311 --- [           main] c.e.batch.BatchLabApplication            : Starting BatchLabApplication using Java 21.0.2
INFO 42311 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Starting...
INFO 42311 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Start completed.
ERROR 42311 --- [           main] o.s.boot.SpringApplication               : Application run failed

org.springframework.jdbc.BadSqlGrammarException: PreparedStatementCallback;
  bad SQL grammar [SELECT JOB_INSTANCE_ID, JOB_NAME from BATCH_JOB_INSTANCE where JOB_NAME = ? and JOB_KEY = ?];
  Table 'batchdb.BATCH_JOB_INSTANCE' doesn't exist
```

**테이블이 없다고 합니다.** `initialize-schema: always` 를 분명히 써 뒀는데도 그렇습니다. 스키마 초기화 빈이 `BatchAutoConfiguration` 안에 있었기 때문입니다.

더 나쁜 시나리오를 봅니다. `BATCH_*` 테이블이 **이미 존재하는 상태**에서 `@EnableBatchProcessing` 을 붙이면:

```bash
./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
```

**결과**
```
INFO 42355 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.877 seconds (process running for 2.144)

BUILD SUCCESSFUL in 4s
```

> ⚠️ **함정 — 에러도 없고, 로그도 깨끗하고, Job 만 안 돕니다**
> 이것이 이 스텝에서 가장 위험한 상황입니다.
> - 애플리케이션은 정상 기동합니다.
> - `BUILD SUCCESSFUL` 이고 종료 코드는 **0** 입니다.
> - `Job: [...] launched` 로그가 **없습니다.**
> - `BATCH_JOB_EXECUTION` 에 행이 안 늘어납니다.
>
> 스케줄러는 성공으로 봅니다. 알람도 안 옵니다. **정산이 안 돌았는데 아무도 모릅니다.**
> `JobLauncherApplicationRunner` 가 등록되지 않았으니 아무도 Job 을 실행하지 않은 것뿐인데, 그 사실을 알려 주는 로그가 한 줄도 없습니다.
>
> **탐지 방법**: 로그에서 `Job: [...] launched with the following parameters` 줄이 있는지 확인하세요. 없으면 Job 이 안 돈 것입니다. 운영에서는 이 문자열을 로그 알림 조건으로 걸어 두는 것이 좋습니다.
>
> **검증 방법**: 자동설정이 물러났는지 직접 확인할 수 있습니다.
> ```bash
> ./gradlew bootRun --args='--debug' 2>&1 | grep -A2 'BatchAutoConfiguration'
> ```
> **결과**
> ```
>    BatchAutoConfiguration:
>       Did not match:
>          - @ConditionalOnMissingBean (types: org.springframework.batch.core.configuration.support.DefaultBatchConfiguration; annotations: org.springframework.batch.core.configuration.annotation.EnableBatchProcessing) found beans of annotation type EnableBatchProcessing 'trapConfig' (OnBeanCondition)
> ```
> `Did not match` 와 원인 빈 이름(`trapConfig`)까지 나옵니다. **`--debug` 로 자동설정 리포트를 읽는 습관**은 Boot 를 쓰는 한 계속 쓸모 있습니다.

### 그럼 `@EnableBatchProcessing` 은 언제 쓰나

Spring Boot 를 **안 쓰는** 순수 Spring 프로젝트이거나, 배치 인프라 빈을 통째로 직접 구성하고 싶을 때입니다. 5.x 에서는 후자를 위해 더 나은 선택지가 생겼습니다.

| 방법 | 용도 | Boot 자동설정 |
|---|---|---|
| 아무것도 안 붙임 | **Boot 3 + Batch 5 의 기본. 이 코스가 쓰는 방식** | 살아 있음 |
| `@EnableBatchProcessing(속성...)` | 순수 Spring, 또는 `tablePrefix`/`isolationLevelForCreate` 등을 애너테이션으로 지정 | **꺼짐** |
| `extends DefaultBatchConfiguration` | 인프라 빈을 메서드 오버라이드로 세밀 조정 | **꺼짐** |

`@EnableBatchProcessing` 은 5.0 에서 속성이 늘었습니다. 참고로 적어 둡니다.

```java
@EnableBatchProcessing(
        dataSourceRef = "batchDataSource",
        transactionManagerRef = "batchTxManager",
        tablePrefix = "BATCH_",
        isolationLevelForCreate = "ISOLATION_SERIALIZABLE",
        taskExecutorRef = "batchTaskExecutor"
)
```

하지만 **Boot 를 쓴다면 이 값들은 전부 `application.yml` 로 설정 가능합니다.** 애너테이션을 붙여 자동설정을 끄고, 그 대가로 러너와 스키마 초기화를 직접 만드는 것은 손해입니다.

---

## 2-7. 여러 Step 을 이어 붙이기

`SimpleJob` 은 Step 을 등록된 순서대로 실행합니다.

```java
@Bean
public Job pipelineJob(JobRepository jobRepository,
                       Step extractStep, Step transformStep, Step loadStep) {
    return new JobBuilder("pipelineJob", jobRepository)
            .start(extractStep)
            .next(transformStep)
            .next(loadStep)
            .build();
}
```

**결과**
```
INFO 42402 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=pipelineJob]] launched with the following parameters: [{}]
INFO 42402 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [extractStep]
>>> extract : orders 에서 COMPLETED 70000건 확인
INFO 42402 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [extractStep] executed in 34ms
INFO 42402 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [transformStep]
>>> transform : 수수료율 적용 규칙 로드
INFO 42402 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [transformStep] executed in 16ms
INFO 42402 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [loadStep]
>>> load : settlement 적재 준비 완료
INFO 42402 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step: [loadStep] executed in 17ms
INFO 42402 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=pipelineJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 106ms
```

```sql
SELECT STEP_EXECUTION_ID, STEP_NAME, STATUS, COMMIT_COUNT, EXIT_CODE
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
ORDER BY STEP_EXECUTION_ID;
```

**결과**
```
+-------------------+---------------+-----------+--------------+-----------+
| STEP_EXECUTION_ID | STEP_NAME     | STATUS    | COMMIT_COUNT | EXIT_CODE |
+-------------------+---------------+-----------+--------------+-----------+
|                 1 | extractStep   | COMPLETED |            1 | COMPLETED |
|                 2 | transformStep | COMPLETED |            1 | COMPLETED |
|                 3 | loadStep      | COMPLETED |            1 | COMPLETED |
+-------------------+---------------+-----------+--------------+-----------+
```

**JobExecution 은 1개, StepExecution 은 3개**입니다. Job 소요 106ms 는 각 Step(34+16+17=67ms)에 메타데이터 갱신 시간이 더해진 값입니다.

`.next()` 는 **앞 Step 이 COMPLETED 일 때만** 다음으로 갑니다. `transformStep` 이 실패하면:

**결과**
```
INFO 42451 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [extractStep]
INFO 42451 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [transformStep]
ERROR 42451 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step transformStep in job pipelineJob
INFO 42451 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=pipelineJob]] completed with the following parameters: [{}] and the following status: [FAILED] in 71ms
```

`loadStep` 의 `Executing step:` 줄이 아예 없습니다. **실행되지 않았고, `BATCH_STEP_EXECUTION` 에 행도 안 생깁니다.**

| 상황 | `.next()` 의 동작 |
|---|---|
| 앞 Step COMPLETED | 다음 Step 실행 |
| 앞 Step FAILED | Job 을 FAILED 로 끝냄. 다음 Step 미실행 |
| 앞 Step 이 커스텀 ExitStatus 반환 | **`.next()` 는 무시합니다.** 분기하려면 `.on(...).to(...)` ([Step 10](../step-10-flow-control/)) |

세 번째 줄이 중요합니다. Step 01 에서 만든 `NO_DATA` ExitStatus 는 `.next()` 체인에서는 **아무 영향이 없습니다.** BatchStatus 가 COMPLETED 이면 그냥 다음으로 갑니다. "ExitStatus 를 바꿨는데 왜 흐름이 안 바뀌지?" 의 답입니다.

---

## 2-8. Step 빈은 싱글턴입니다

`@Bean` 으로 만든 `Step` 은 **싱글턴**입니다. 그런데 `StepExecution` 은 실행마다 새로 만들어집니다. 이 둘의 수명 차이가 사고를 만듭니다.

```java
// ⚠️ 위험한 코드 — 컴파일도 되고 한 번은 잘 돕니다
@Configuration
public class StatefulStepConfig {

    private int counter = 0;                 // 설정 클래스의 필드

    @Bean
    public Step statefulStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("statefulStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    counter++;
                    System.out.println(">>> counter = " + counter);
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }
}
```

같은 JVM 에서 Job 을 두 번 실행하면(파라미터를 달리해서):

**결과**
```
--- 1회차 ---
>>> counter = 1
--- 2회차 (같은 프로세스, 파라미터만 다름) ---
>>> counter = 2
```

`counter` 가 1 로 초기화되지 않습니다. 람다가 캡처한 것은 **설정 클래스 인스턴스**이고, 그것은 싱글턴이기 때문입니다.

> ⚠️ **함정 — 로컬에서는 절대 안 재현되는 버그**
> 로컬에서는 `bootRun` 한 번에 Job 한 번만 돌고 JVM 이 죽습니다. **`counter` 는 항상 1 입니다.** 테스트도 통과합니다.
> 그런데 운영에서 스케줄러가 같은 JVM 안에서 하루에 24번 Job 을 돌리면(Quartz 방식, [Step 14](../step-14-operations/)) `counter` 가 계속 누적됩니다.
> "왜 오후 배치만 결과가 이상하지?" 가 됩니다. 재현이 안 되니 원인을 찾는 데 며칠이 걸립니다.
>
> **상태를 두어야 한다면 자리는 두 곳뿐입니다.**
> 1. `StepExecution` / `JobExecution` 의 **ExecutionContext** — 재시작 시 복원까지 됩니다 ([Step 09](../step-09-execution-context/))
> 2. `@StepScope` / `@JobScope` 빈 — 실행마다 새로 만들어집니다 ([Step 03](../step-03-job-parameters/))
>
> 올바른 버전:
> ```java
> .tasklet((contribution, chunkContext) -> {
>     ExecutionContext ctx = chunkContext.getStepContext()
>             .getStepExecution().getExecutionContext();
>     long counter = ctx.getLong("counter", 0L) + 1;
>     ctx.putLong("counter", counter);
>     System.out.println(">>> counter = " + counter);
>     return RepeatStatus.FINISHED;
> }, txManager)
> ```
> 이러면 실행마다 0 부터 시작하고, 값이 `BATCH_STEP_EXECUTION_CONTEXT` 에 저장되어 재시작 시 이어집니다.

---

## 2-9. 빌더가 제공하는 나머지 옵션

자주 쓰는 것만 정리합니다. 각각 어느 스텝에서 본격적으로 다루는지 표시했습니다.

### JobBuilder

| 메서드 | 설명 | 스텝 |
|---|---|---|
| `.start(Step)` | 첫 Step. `SimpleJob` 을 만듭니다 | 02 |
| `.next(Step)` | 다음 Step | 02 |
| `.start(Flow)` / `.on(...).to(...)` | 조건 분기. `FlowJob` 을 만듭니다 | [10](../step-10-flow-control/) |
| `.incrementer(JobParametersIncrementer)` | 실행마다 파라미터 자동 증가 | [03](../step-03-job-parameters/) |
| `.validator(JobParametersValidator)` | 파라미터 검증 | [03](../step-03-job-parameters/) |
| `.listener(JobExecutionListener)` | Job 전후 훅 | [12](../step-12-listeners/) |
| `.preventRestart()` | 재시작 자체를 금지 | [11](../step-11-fault-tolerance/) |

### StepBuilder

| 메서드 | 설명 | 스텝 |
|---|---|---|
| `.tasklet(Tasklet, PlatformTransactionManager)` | Tasklet Step | [04](../step-04-tasklet/) |
| `.<I,O>chunk(int, PlatformTransactionManager)` | 청크 Step | [05](../step-05-chunk/) |
| `.listener(...)` | Step/Chunk/Item 리스너 | [12](../step-12-listeners/) |
| `.allowStartIfComplete(true)` | 이미 성공한 Step 도 재실행 | [11](../step-11-fault-tolerance/) |
| `.startLimit(int)` | 이 Step 의 최대 시도 횟수(기본 `Integer.MAX_VALUE`) | [11](../step-11-fault-tolerance/) |
| `.faultTolerant()` | skip / retry 설정 진입 | [11](../step-11-fault-tolerance/) |
| `.taskExecutor(...)` | 멀티스레드 Step | [13](../step-13-scaling/) |

> 💡 **`.allowStartIfComplete(true)` 는 신중하게**
> Step 01 의 "이미 COMPLETED 인 Step 은 건너뛴다"를 무력화하는 옵션입니다. 파일 정리·임시 테이블 truncate 처럼 **몇 번 실행해도 결과가 같은(멱등)** Step 에만 쓰세요.
> 정산처럼 실행할 때마다 결과가 쌓이는 Step 에 붙이면 **재시작할 때마다 정산이 중복**됩니다. 에러 없이요.

---

## 2-10. 마이그레이션 체크리스트

4.x → 5.1 로 실제 프로젝트를 옮길 때 순서대로 확인할 목록입니다.

| # | 항목 | 확인 방법 | 증상 |
|---|---|---|---|
| 1 | `JobBuilderFactory` / `StepBuilderFactory` 제거 | `grep -rn 'BuilderFactory' src/` | 컴파일 에러 (시끄러움) |
| 2 | `.tasklet(t)` → `.tasklet(t, tx)` | 컴파일러가 잡아 줌 | 컴파일 에러 |
| 3 | `.chunk(n)` → `.chunk(n, tx)` | 컴파일러가 잡아 줌 | 컴파일 에러 |
| 4 | `@EnableBatchProcessing` **제거** | `grep -rn 'EnableBatchProcessing' src/` | **조용히 Job 미실행** ★ |
| 5 | `javax.*` → `jakarta.*` | `grep -rn 'javax\.persistence\|javax\.sql' src/` | 컴파일 에러 |
| 6 | `ItemWriter.write(List)` → `write(Chunk)` | 컴파일 에러 | [Step 08](../step-08-item-writer/) |
| 7 | `JobParameter` 타입 파라미터화 | 컴파일 에러 | [Step 03](../step-03-job-parameters/) |
| 8 | `BATCH_JOB_EXECUTION_PARAMS` 스키마 변경 | `DESC BATCH_JOB_EXECUTION_PARAMS;` | **런타임 SQL 에러** |
| 9 | `BATCH_JOB_EXECUTION.JOB_CONFIGURATION_LOCATION` 제거 | `SHOW COLUMNS FROM BATCH_JOB_EXECUTION;` | 커스텀 쿼리 깨짐 |
| 10 | 트랜잭션 매니저가 의도한 것인지 | Step 에서 `txManager.getClass()` 출력 | **조용히 롤백 안 됨** ★ |

★ 표시된 4번과 10번이 **컴파일러가 안 잡아 주는** 항목입니다. 나머지는 빌드가 깨져서 알게 되지만, 이 둘은 배포 후에 알게 됩니다.

기존 메타데이터를 그대로 쓰는 경우 8번의 마이그레이션 DDL 은 이렇습니다.

```sql
-- 4.x → 5.x 파라미터 테이블 마이그레이션 (기존 데이터를 버려도 된다면 DROP/CREATE 가 간단합니다)
ALTER TABLE BATCH_JOB_EXECUTION_PARAMS
  DROP COLUMN TYPE_CD,
  DROP COLUMN DATE_VAL,
  DROP COLUMN LONG_VAL,
  DROP COLUMN DOUBLE_VAL,
  CHANGE COLUMN KEY_NAME   PARAMETER_NAME  VARCHAR(100)  NOT NULL,
  CHANGE COLUMN STRING_VAL PARAMETER_VALUE VARCHAR(2500),
  ADD COLUMN PARAMETER_TYPE VARCHAR(100) NOT NULL AFTER PARAMETER_NAME;

ALTER TABLE BATCH_JOB_EXECUTION DROP COLUMN JOB_CONFIGURATION_LOCATION;
```

**결과**
```
Query OK, 0 rows affected (0.09 sec)
Records: 0  Duplicates: 0  Warnings: 0

Query OK, 0 rows affected (0.04 sec)
Records: 0  Duplicates: 0  Warnings: 0
```

> ⚠️ **함정 — 마이그레이션 후 옛 JOB_KEY 는 재계산되지 않습니다**
> 파라미터 저장 방식이 바뀌었어도 `BATCH_JOB_INSTANCE.JOB_KEY` 는 **과거에 계산된 값 그대로** 남아 있습니다. 5.x 의 해시 계산 로직은 파라미터 타입을 포함하므로, 같은 파라미터로 다시 돌려도 **다른 JOB_KEY 가 나올 수 있습니다.**
> 즉 마이그레이션 직후 첫 실행에서 "이미 성공한 Job 인데 새 JobInstance 로 다시 실행"되는 일이 벌어집니다.
> 정산 배치라면 **중복 정산**입니다. 마이그레이션 전에 진행 중인 Job 을 모두 마무리하고, 첫 실행은 반드시 사람이 지켜보세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 계층 | Job → Step → Tasklet → (청크면 ItemReader/Processor/Writer) |
| 청크 Step 의 정체 | `ChunkOrientedTasklet` 을 품은 `TaskletStep`. 청크 1개 = 트랜잭션 1개 |
| `JobBuilderFactory` | **5.0 deprecated → 5.1 삭제.** `new JobBuilder(name, jobRepository)` |
| `StepBuilderFactory` | 동일. `new StepBuilder(name, jobRepository)` |
| 팩토리를 없앤 이유 | 숨은 의존성 제거, Job 별 JobRepository 지정 가능 |
| `.tasklet(t, tx)` | 트랜잭션 매니저가 **선택이 아니라 인자**로 승격 |
| 트랜잭션 매니저 확인 | JPA 스타터가 있으면 `JpaTransactionManager` 가 잡힘 |
| `@EnableBatchProcessing` | **붙이면 `BatchAutoConfiguration` 이 통째로 물러남** |
| 물러나면 없어지는 것 | 러너(=Job 자동실행), 스키마 초기화, `spring.batch.*` 설정 반영 |
| 최악의 증상 | **에러 없이, 종료코드 0 으로, Job 만 안 도는 것** |
| 진단 도구 | `--debug` 자동설정 리포트의 `Did not match` |
| `.next()` | 앞 Step 이 COMPLETED 일 때만 진행. ExitStatus 분기는 못 함 |
| Step 빈 | **싱글턴.** 설정 클래스 필드에 상태를 두면 실행 간에 누적됨 |
| 상태를 둘 곳 | ExecutionContext 또는 `@StepScope`/`@JobScope` |
| 마이그레이션 위험 항목 | `@EnableBatchProcessing` 잔존, 트랜잭션 매니저 오지정 (둘 다 컴파일러가 못 잡음) |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 주어진 4.x 설정 클래스를 5.1 로 마이그레이션하기 (`JobBuilderFactory` + `@EnableBatchProcessing` + `.tasklet(t)` 전부 포함)
2. `new JobBuilder("x")` 만 쓴 Job 이 왜 실패하는지 예측하고, 예외 메시지와 발생 지점을 적기
3. `@EnableBatchProcessing` 을 붙였을 때 사라지는 빈 3개를 나열하고, 각각이 사라지면 어떤 증상이 나오는지 매칭
4. Step 세 개를 `.next()` 로 잇되, 두 번째 Step 이 `ExitStatus("WARN")` 을 반환해도 세 번째가 실행되는지 확인하고 이유 적기
5. 설정 클래스 필드에 상태를 둔 Step 을 ExecutionContext 기반으로 고치기
6. Step 이 어떤 `PlatformTransactionManager` 를 쓰는지 출력하고, `ResourcelessTransactionManager` 로 바꿨을 때 메타데이터 갱신은 어떻게 되는지 확인

---

## 다음 단계

Job 과 Step 을 5.x 방식으로 만들 수 있게 됐습니다. 하지만 지금까지 만든 Job 은 전부 **파라미터가 없어서 딱 한 번밖에 성공할 수 없습니다.**
다음 스텝에서는 `JobParameters` 를 다룹니다. identifying / non-identifying 의 구분, 5.0 에서 타입 파라미터화된 `JobParameter<T>`, `@StepScope` 와 `#{jobParameters['...']}` 의 지연 바인딩, 그리고 `RunIdIncrementer` 를 무심코 붙였다가 **날짜별 정산이 중복 실행되는** 사고를 다룹니다.

→ [Step 03 — JobParameters](../step-03-job-parameters/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 에는 4.x 코드(주석 처리)와 5.x 코드가 **나란히** 들어 있어 diff 를 눈으로 보게 되어 있고, `Exercise.java` 의 6문제로 마이그레이션을 손으로 해 본 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step02` 패키지이며 `static class` 중첩을 씁니다.

### Practice.java

본문 2-2 ~ 2-9 의 모든 예제를 절 번호 주석과 함께 모아 둔 파일입니다.

- **`[2-2] LegacyJobConfig` 는 통째로 주석 처리되어 있습니다.** Spring Batch 5.1 에서는 `JobBuilderFactory` 타입 자체가 없어 컴파일이 안 되기 때문입니다. 주석을 풀면 빌드가 깨지는 것이 정상이며, 그것을 확인하는 것도 실습의 일부입니다. `[2-3] SettlementJobConfig` 가 같은 설정의 5.1 판이므로 두 블록을 위아래로 놓고 비교하세요.
- `[2-5] WhichTxJobConfig` 는 주입된 `PlatformTransactionManager` 의 실제 클래스명을 출력합니다. 이 프로젝트는 `spring-boot-starter-data-jpa` 를 포함하므로 `org.springframework.orm.jpa.JpaTransactionManager` 가 나와야 정상입니다. `DataSourceTransactionManager` 가 나온다면 JPA 스타터가 빠진 것이고, 그 상태로 Step 06 의 `JpaPagingItemReader` 실습이 안 됩니다.
- **`[2-6] TrapConfig` 는 기본적으로 주석 처리되어 있습니다.** 주석을 풀면 `@EnableBatchProcessing` 이 활성화되어 **이 프로젝트의 모든 Job 이 부팅 시 실행되지 않게 됩니다.** 함정을 재현했다면 **반드시 다시 주석으로 되돌리세요.** 되돌리는 것을 잊고 Step 03 으로 넘어가면 "왜 아무것도 안 돌지?" 로 한참 헤매게 됩니다. 이 파일에서 가장 주의할 부분입니다.
- `[2-8] StatefulStepConfig` 와 `[2-8'] StatefulFixedConfig` 는 같은 기능을 잘못된 방식(설정 클래스 필드)과 올바른 방식(ExecutionContext)으로 구현한 쌍입니다. **같은 JVM 에서 두 번 실행해야 차이가 드러납니다.** `bootRun` 은 매번 새 JVM 이라 차이가 안 보이므로, 파일 주석에 적어 둔 `--spring.batch.job.name` 없이 두 Job 을 함께 돌리는 방법이나 Step 14 의 Quartz 실습에서 다시 확인하세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `// 여기에 작성:` 자리를 채우면 됩니다.

- **문제 1이 이 스텝의 본체**입니다. 4.x 설정 클래스 원본이 주석 블록으로 통째로 들어 있고, 그 아래 빈 5.x 클래스를 채우는 구조입니다. 치환해야 할 곳이 **다섯 군데**(애너테이션, 필드 2개, `get()` 2개, `.tasklet()` 인자)이며, 그중 하나는 컴파일러가 안 잡아 줍니다. 어느 것인지 찾는 것이 목적입니다.
- 문제 2는 코드를 실행하기 전에 **예외 클래스명과 발생 지점을 먼저 적으라**고 요구합니다. `IllegalStateException` 인지 `NullPointerException` 인지, 빈 생성 시점인지 Job 실행 시점인지를 예측해 보세요. Spring Batch 가 이 실수를 "시끄럽게" 잡아 주는지 확인하는 문제입니다.
- 문제 4는 함정입니다. `ExitStatus("WARN")` 을 반환해도 `.next()` 는 **그냥 다음 Step 을 실행합니다.** 예측을 적고 실행해서 확인한 뒤, `.next()` 가 무엇을 보고 판단하는지 한 문장으로 정리하세요.
- 문제 6은 `ResourcelessTransactionManager` 로 바꿔도 `BATCH_STEP_EXECUTION` 에 행이 정상적으로 남는지 확인하는 문제입니다. "트랜잭션 매니저를 리소스 없는 것으로 바꿨으니 메타데이터도 안 남겠지"라는 예상이 틀리는 이유를 설명해야 합니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석입니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 다섯 군데 치환을 하나씩 짚고, 그중 `@EnableBatchProcessing` 제거만이 **컴파일러가 못 잡는 항목**임을 강조합니다. 나머지 넷은 빌드가 깨져서 알게 되지만, 이것 하나는 빌드가 통과하고 배포도 되고 나서 "Job 이 안 돈다"로 나타납니다. 마이그레이션 PR 리뷰에서 가장 먼저 grep 해야 할 문자열이라는 결론으로 이어집니다.
- **정답 2** 는 `IllegalArgumentException: JobRepository must be set` 이며, 발생 지점은 `AbstractJob.afterPropertiesSet()` — 즉 **빈 초기화 시점**입니다. Job 을 실행하기도 전에 애플리케이션 기동이 실패하므로 좋은 실패입니다. "왜 deprecated 생성자를 남겨 뒀는데도 안전한가"에 대한 답이기도 합니다.
- **정답 3** 은 `JobLauncherApplicationRunner`(→ Job 미실행), `BatchDataSourceScriptDatabaseInitializer`(→ 테이블 미생성), `BatchProperties` 바인딩(→ `spring.batch.*` 무시) 셋을 증상과 매칭합니다. 그리고 세 증상 중 **첫 번째만 조용하다**는 점을 지적합니다. 테이블이 없으면 시끄럽게 실패하지만, 러너가 없으면 아무 일도 안 일어납니다.
- **정답 4** 는 `.next()` 가 `ExitStatus` 가 아니라 **`BatchStatus`** 를 본다는 것입니다. 정확히는 `SimpleJob` 이 각 Step 실행 후 `stepExecution.getStatus()` 를 확인하고 `COMPLETED` 가 아니면 중단합니다. ExitStatus 로 분기하려면 `FlowBuilder` 의 `.on("WARN").to(...)` 가 필요하며 그것이 Step 10 의 주제입니다.
- **정답 5** 는 `ExecutionContext#getLong(key, default)` 를 쓰는 버전이고, 왜 `JobExecution` 이 아니라 `StepExecution` 의 컨텍스트를 골랐는지(Step 단위 재시작 지점이므로), 그리고 이 값이 `BATCH_STEP_EXECUTION_CONTEXT.SHORT_CONTEXT` 에 어떤 JSON 으로 저장되는지 실제 문자열로 보여 줍니다.
- **정답 6** 은 `ResourcelessTransactionManager` 를 쓰더라도 **`JobRepository` 는 자기 트랜잭션 매니저를 따로 갖고 있어서** 메타데이터가 정상적으로 저장된다는 것입니다. Step 의 트랜잭션 매니저와 JobRepository 의 트랜잭션 매니저는 별개의 설정이며, 이 분리를 모르면 "메타데이터도 리소스리스로 날아가는 것 아닌가" 하는 잘못된 걱정을 하게 됩니다. 반대로 **정말 위험한 조합**(Step 의 tx 와 JobRepository 의 tx 가 서로 다른 DataSource 를 가리키는 경우)이 무엇인지도 함께 적었습니다.

```java file="./Solution.java"
```
