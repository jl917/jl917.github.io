package com.example.batch.step02;

/*
 * ============================================================================
 * Step 02 — 연습문제 정답 및 해설
 * ============================================================================
 * Exercise.java 를 먼저 풀어 본 뒤에 보세요.
 * ============================================================================
 */

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

public final class Solution {

    private Solution() {
    }

    // =========================================================================
    // 정답 1. 4.3 → 5.1 마이그레이션
    // =========================================================================
    //
    // 치환한 다섯 군데
    //
    //   ① @EnableBatchProcessing            → 삭제          ★ 컴파일러가 못 잡음
    //   ② JobBuilderFactory  필드/생성자     → 삭제, 파라미터 주입으로
    //   ③ StepBuilderFactory 필드/생성자     → 삭제, 파라미터 주입으로
    //   ④ xxxBuilderFactory.get(name)       → new XxxBuilder(name, jobRepository)
    //   ⑤ .tasklet(t)                       → .tasklet(t, txManager)
    //
    // 그리고 부수적으로 closeStep() / archiveStep() 메서드 직접 호출을
    // @Bean 메서드 파라미터 주입으로 바꿨습니다.
    //
    // ★ 컴파일러가 못 잡는 항목은 ① 하나뿐입니다.
    //
    //   ②③④⑤ 는 타입이 없어졌거나 시그니처가 바뀌었으므로 빌드가 깨집니다.
    //   빌드가 깨지는 것은 좋은 실패입니다. 고칠 수밖에 없으니까요.
    //
    //   그런데 ① 은 애너테이션이 여전히 존재하고, 붙여도 컴파일이 됩니다.
    //   심지어 애플리케이션도 정상 기동합니다.
    //   결과는 "BatchAutoConfiguration 이 물러나 JobLauncherApplicationRunner 가
    //   등록되지 않고, 부팅 시 Job 이 하나도 실행되지 않는" 것입니다.
    //   에러 없이, 로그도 깨끗하게, 종료 코드 0 으로 끝납니다.
    //
    //   즉 마이그레이션 PR 은 "빌드가 통과했으니 됐다" 로 끝나고,
    //   배포된 뒤 첫 배치 시간에 아무 일도 일어나지 않습니다.
    //   그리고 아무도 모릅니다. 알람이 안 오니까요.
    //
    //   → 마이그레이션 PR 에서 가장 먼저 실행할 명령:
    //        grep -rn 'EnableBatchProcessing' src/
    //     결과가 비어 있어야 합니다.
    //
    @Configuration
    public static class Sol1Config {

        @Bean
        public Job dailyCloseJob(JobRepository jobRepository, Step closeStep, Step archiveStep) {
            return new JobBuilder("dailyCloseJob", jobRepository)
                    .start(closeStep)
                    .next(archiveStep)
                    .build();
        }

        @Bean
        public Step closeStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("closeStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println("일마감 처리");
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }

        @Bean
        public Step archiveStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("archiveStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println("아카이브");
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }
    }

    // =========================================================================
    // 정답 2. JobRepository 없는 JobBuilder
    // =========================================================================
    //
    //   (a) 컴파일은 됩니다. new JobBuilder(String) 생성자는 5.1 에도 남아 있습니다
    //       (@Deprecated(since = "5.0") 로 표시만 되어 있습니다).
    //
    //   (b) java.lang.IllegalArgumentException
    //
    //   (c) "JobRepository must be set"
    //
    //   (d) 빈 초기화 시점입니다.
    //       스택트레이스의 맨 위가 이렇습니다.
    //         at org.springframework.util.Assert.state(Assert.java:76)
    //         at org.springframework.batch.core.job.AbstractJob.afterPropertiesSet(AbstractJob.java:141)
    //       AbstractJob 은 InitializingBean 을 구현하고 있어서,
    //       컨테이너가 빈을 만들 때 afterPropertiesSet() 이 호출되며 거기서 검사합니다.
    //       Job 을 실행하기도 전에 애플리케이션 기동이 실패합니다.
    //
    //   (e) 시끄러운 실패입니다. 그리고 이것이 옳습니다.
    //
    // 왜 좋은 설계인가:
    //   JobRepository 가 없는 Job 은 메타데이터를 남길 수 없습니다.
    //   즉 "어디까지 처리했는지 기억하지 못하는 배치" 입니다.
    //   그런 Job 이 조용히 돌아서 절반쯤 처리하고 죽는다면 복구할 방법이 없습니다.
    //   그러느니 애플리케이션이 아예 안 뜨는 게 낫습니다.
    //
    //   deprecated 생성자를 남겨 둔 것도 이 검사 덕에 안전합니다.
    //   "잘못 쓸 수 있게 열어 두되, 잘못 쓰면 즉시 크게 실패하게 한다" 는 패턴입니다.

    // =========================================================================
    // 정답 3. @EnableBatchProcessing 을 붙이면 사라지는 것
    // =========================================================================
    //
    //   (1) 빈: JobLauncherApplicationRunner
    //       증상: 부팅 시 Job 이 자동 실행되지 않는다.
    //             에러 없음, 로그 없음, 종료 코드 0.   ← ★ 조용한 증상
    //
    //   (2) 빈: BatchDataSourceScriptDatabaseInitializer
    //       증상: spring.batch.jdbc.initialize-schema 가 무시되어 BATCH_* 테이블이
    //             생성되지 않는다. 테이블이 없는 상태라면
    //             BadSqlGrammarException: Table 'batchdb.BATCH_JOB_INSTANCE' doesn't exist
    //             로 시끄럽게 죽는다.  ← 시끄러움, 그나마 다행
    //
    //   (3) 설정 바인딩: BatchProperties (spring.batch.*)
    //       증상: table-prefix, job.name 등 application.yml 설정이 반영되지 않는다.
    //             table-prefix 를 커스텀으로 쓰고 있었다면 기본값 BATCH_ 로 되돌아가
    //             "테이블이 없다" 또는 "빈 메타데이터를 보고 처음부터 다시 실행" 이 된다.
    //
    //   조용한 것은 (1) 입니다.
    //
    //   탐지 문자열: "launched with the following parameters"
    //     이 줄이 로그에 없으면 Job 이 한 번도 실행되지 않은 것입니다.
    //     운영에서는 이 문자열의 부재를 알림 조건으로 거는 것이 좋습니다.
    //     (배치 로그는 "무엇이 있었는가" 보다 "무엇이 없었는가" 가 더 중요할 때가 많습니다)
    //
    //   진단 명령:
    //     ./gradlew bootRun --args='--debug' 2>&1 | grep -A2 'BatchAutoConfiguration'
    //
    //     BatchAutoConfiguration:
    //        Did not match:
    //           - @ConditionalOnMissingBean (... annotations: ...EnableBatchProcessing)
    //             found beans of annotation type EnableBatchProcessing 'trapConfig'
    //
    //     원인 빈 이름까지 알려 줍니다.

    // =========================================================================
    // 정답 4. ExitStatus 를 바꿔도 ex4StepC 는 실행됩니다
    // =========================================================================
    //
    // 결과
    //   +--------------+-----------+-----------+
    //   | STEP_NAME    | STATUS    | EXIT_CODE |
    //   +--------------+-----------+-----------+
    //   | ex4StepA     | COMPLETED | COMPLETED |
    //   | ex4StepB     | COMPLETED | WARN      |   ← ExitStatus 만 WARN
    //   | ex4StepC     | COMPLETED | COMPLETED |   ← 실행됨
    //   +--------------+-----------+-----------+
    //
    // 왜인가:
    //   SimpleJob 은 각 Step 실행 후 stepExecution.getStatus() — 즉 BatchStatus 를 봅니다.
    //   COMPLETED 이면 다음 Step 으로 진행하고, 아니면 Job 을 중단합니다.
    //   ExitStatus 는 아예 보지 않습니다.
    //
    //     .next() 가 보는 것 = BatchStatus (enum, 프레임워크가 정함)
    //     .on(...)  가 보는 것 = ExitStatus  (문자열, 개발자가 정할 수 있음)
    //
    // 흔한 오해와 그 결과:
    //   "ExitStatus 를 SKIPPED 로 바꿨으니 뒤 Step 은 안 돌겠지" 라고 믿고
    //   휴일 처리 로직을 만들면, 휴일에도 정산이 그대로 돕니다.
    //   에러는 안 납니다. 로그도 COMPLETED 입니다.
    //   ExitStatus 로 흐름을 바꾸려면 반드시 FlowBuilder 를 써야 합니다(Step 10).
    //
    //     new JobBuilder("ex4Job", jobRepository)
    //             .start(ex4StepA)
    //             .next(ex4StepB)
    //             .on("WARN").end()            // ← 이렇게 해야 흐름이 바뀝니다
    //             .from(ex4StepB).on("*").to(ex4StepC)
    //             .end()
    //             .build();
    //
    @Configuration
    public static class Sol4Config {

        @Bean
        public Job ex4Job(JobRepository jobRepository,
                          Step ex4StepA, Step ex4StepB, Step ex4StepC) {
            return new JobBuilder("ex4Job", jobRepository)
                    .start(ex4StepA)
                    .next(ex4StepB)
                    .next(ex4StepC)
                    .build();
        }

        @Bean
        public Step ex4StepA(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("ex4StepA", jobRepository)
                    .tasklet((c, cc) -> {
                        System.out.println(">>> A");
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }

        @Bean
        public Step ex4StepB(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("ex4StepB", jobRepository)
                    .tasklet((c, cc) -> {
                        System.out.println(">>> B (ExitStatus=WARN)");
                        c.setExitStatus(new ExitStatus("WARN", "환율 테이블이 어제 것입니다"));
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }

        @Bean
        public Step ex4StepC(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("ex4StepC", jobRepository)
                    .tasklet((c, cc) -> {
                        System.out.println(">>> C (실행됩니다)");
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }
    }

    // =========================================================================
    // 정답 5. 상태를 ExecutionContext 로 옮기기
    // =========================================================================
    //
    // 왜 StepExecution 의 컨텍스트인가 (JobExecution 이 아니라):
    //
    //   - 세는 대상이 "이 Step 이 처리한 파일 수" 이므로 수명이 Step 과 같아야 합니다.
    //   - 재시작 시 Spring Batch 는 실패한 StepExecution 의 컨텍스트를 복원해
    //     같은 Step 의 새 실행에 넘겨 줍니다. 즉 "이어서 세기" 가 공짜로 됩니다.
    //   - JobExecution 컨텍스트에 두면 Job 안의 다른 Step 도 볼 수 있어
    //     의도치 않은 공유가 생깁니다. 공유가 필요할 때만 그쪽을 쓰세요(Step 09).
    //
    // 저장되는 JSON (BATCH_STEP_EXECUTION_CONTEXT.SHORT_CONTEXT):
    //
    //   {"@class":"java.util.HashMap","fileCount":1,
    //    "batch.taskletType":"com.example.batch.step02.Solution$Sol5Config$$Lambda/0x...",
    //    "batch.stepType":"org.springframework.batch.core.step.tasklet.TaskletStep"}
    //
    //   batch.* 두 개는 프레임워크가 넣은 것이고, fileCount 가 우리 값입니다.
    //   @class 가 붙는 이유는 4.3 부터 기본 직렬화가 Jackson JSON 이기 때문입니다.
    //
    // ⚠️ 여기서 putLong 을 쓴 이유:
    //   ExecutionContext 는 put(String, Object) 도 받습니다. 아무 객체나 넣을 수 있습니다.
    //   넣는 순간에는 에러가 안 납니다. 커밋 시 직렬화하다가, 또는 재시작 시
    //   역직렬화하다가 터집니다. 즉 "정상 실행 때는 멀쩡하고 장애 복구 때만 터지는"
    //   최악의 타이밍입니다. putLong / putString / putDouble 처럼 타입이 명시된
    //   메서드만 쓰는 습관이 이 사고를 막습니다.
    //
    @Configuration
    public static class Sol5Config {

        @Bean
        public Job ex5Job(JobRepository jobRepository, Step ex5Step) {
            return new JobBuilder("ex5Job", jobRepository).start(ex5Step).build();
        }

        @Bean
        public Step ex5Step(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("ex5Step", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext ctx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        long fileCount = ctx.getLong("fileCount", 0L) + 1;
                        ctx.putLong("fileCount", fileCount);
                        System.out.println(">>> fileCount = " + fileCount);
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }
    }

    // =========================================================================
    // 정답 6. ResourcelessTransactionManager 를 써도 메타데이터는 정상 저장됩니다
    // =========================================================================
    //
    //   (a) BATCH_STEP_EXECUTION 에 행이 정상적으로 남습니다.
    //
    //   +-----------+-----------+--------------+
    //   | STEP_NAME | STATUS    | COMMIT_COUNT |
    //   +-----------+-----------+--------------+
    //   | ex6Step   | COMPLETED |            1 |
    //   +-----------+-----------+--------------+
    //
    //   (b) 이유: 트랜잭션 매니저가 두 군데에 따로 있기 때문입니다.
    //
    //       ┌─────────────────────────────────────────────────────────┐
    //       │ Step 의 트랜잭션 매니저                                   │
    //       │  = .tasklet(t, txManager) 로 넘긴 것                     │
    //       │  = "업무 데이터" 쓰기의 트랜잭션 경계                      │
    //       │  → ResourcelessTransactionManager 로 두면 실제 DB 트랜잭션│
    //       │    을 열지 않습니다 (커넥션을 낭비하지 않습니다)           │
    //       └─────────────────────────────────────────────────────────┘
    //       ┌─────────────────────────────────────────────────────────┐
    //       │ JobRepository 의 트랜잭션 매니저                          │
    //       │  = Boot 자동설정이 DataSource 로 만들어 준 것             │
    //       │  = "메타데이터" 갱신의 트랜잭션 경계                       │
    //       │  → Step 설정과 무관하게 진짜 DB 트랜잭션으로 나갑니다      │
    //       └─────────────────────────────────────────────────────────┘
    //
    //       이 분리를 모르면 "리소스리스로 바꿨으니 메타데이터도 안 남겠지" 라는
    //       잘못된 걱정을 하게 됩니다. 남습니다.
    //
    // ⚠️ 반대로 정말 위험한 조합은 이것입니다.
    //
    //       Step 의 tx        → DataSource A (업무 DB)
    //       JobRepository 의 tx → DataSource B (메타데이터 DB)
    //
    //    이러면 청크 커밋과 메타데이터 갱신이 서로 다른 트랜잭션입니다.
    //    청크는 커밋됐는데 메타데이터 갱신 직전에 프로세스가 죽으면,
    //    "처리는 됐지만 처리했다는 기록은 없는" 상태가 됩니다.
    //    재시작하면 같은 데이터를 한 번 더 처리합니다. 중복 정산입니다.
    //    에러는 나지 않습니다. 그저 숫자가 두 배일 뿐입니다.
    //
    //    프로젝트 셋업(P-1)이 "한 트랜잭션으로 묶고 싶다면 같은 DataSource 여야 한다"
    //    고 적은 이유가 이것이고, Step 11 에서 이 트레이드오프를 다시 다룹니다.
    //
    @Configuration
    public static class Sol6Config {

        @Bean
        public Job ex6Job(JobRepository jobRepository, Step ex6Step) {
            return new JobBuilder("ex6Job", jobRepository).start(ex6Step).build();
        }

        @Bean
        public Step ex6Step(JobRepository jobRepository) {
            PlatformTransactionManager resourceless = new ResourcelessTransactionManager();
            return new StepBuilder("ex6Step", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> step tx = " + resourceless.getClass().getName());
                        System.out.println(">>> DB 를 건드리지 않는 Step 입니다");
                        return RepeatStatus.FINISHED;
                    }, resourceless)
                    .build();
        }
    }
}
