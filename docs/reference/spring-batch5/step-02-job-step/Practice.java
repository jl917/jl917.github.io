package com.example.batch.step02;

/*
 * ============================================================================
 * Step 02 — Job 과 Step 의 구조  :  본문 예제 모음
 * ============================================================================
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=whichTxJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=pipelineJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=statefulJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=statefulFixedJob'
 *
 * ⚠️ 이 파일에서 가장 주의할 것
 *    [2-6] TrapConfig 의 주석을 풀면 @EnableBatchProcessing 이 켜져
 *    BatchAutoConfiguration 이 통째로 물러나고 "모든 Job 이 부팅 시 실행되지 않습니다".
 *    함정을 재현한 뒤에는 반드시 다시 주석 처리하세요.
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

public final class Practice {

    private Practice() {
    }

    // =========================================================================
    // [2-2] Spring Batch 4.3 의 방식 — 5.1 에서는 컴파일되지 않습니다
    // -------------------------------------------------------------------------
    // 주석을 풀면 다음 에러가 납니다. 그것을 확인하는 것도 실습입니다.
    //
    //   error: cannot find symbol
    //     symbol:   class JobBuilderFactory
    //     symbol:   class StepBuilderFactory
    //
    // 확인했으면 다시 주석으로 되돌리세요.
    // =========================================================================
    /*
    @Configuration
    @EnableBatchProcessing                                   // (1) 4.x 에서는 필수
    public static class LegacySettlementJobConfig {

        private final JobBuilderFactory jobBuilderFactory;   // (2) 팩토리 필드
        private final StepBuilderFactory stepBuilderFactory; // (3) 팩토리 필드

        public LegacySettlementJobConfig(JobBuilderFactory jbf, StepBuilderFactory sbf) {
            this.jobBuilderFactory = jbf;
            this.stepBuilderFactory = sbf;
        }

        @Bean
        public Job settlementJob() {
            return jobBuilderFactory.get("settlementJob")    // (4) get(name)
                    .start(settlementStep())                 // (5) 메서드 직접 호출
                    .build();
        }

        @Bean
        public Step settlementStep() {
            return stepBuilderFactory.get("settlementStep")  // (6) get(name)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println("정산 중...");
                        return RepeatStatus.FINISHED;
                    })                                       // (7) 트랜잭션 매니저 없음
                    .build();
        }
    }
    */

    // =========================================================================
    // [2-3] 같은 설정의 Spring Batch 5.1 판  —  위 블록과 나란히 놓고 비교하세요
    // -------------------------------------------------------------------------
    //   (1) @EnableBatchProcessing 삭제            → Boot 자동설정이 대신
    //   (2)(3) 팩토리 필드 삭제                     → JobRepository 를 파라미터로
    //   (4)(6) get(name) → new XxxBuilder(name, jobRepository)
    //   (5) 메서드 직접 호출 → Step 을 파라미터로 주입
    //   (7) .tasklet(t) → .tasklet(t, txManager)
    // =========================================================================
    @Configuration
    public static class SettlementJobConfig {

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
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // [2-4] JobRepository 없이 만들면? — 시끄럽게 실패합니다
    // -------------------------------------------------------------------------
    // 주석을 풀고 실행하면 빈 초기화 시점에 다음이 납니다.
    //
    //   java.lang.IllegalArgumentException: JobRepository must be set
    //     at org.springframework.batch.core.job.AbstractJob.afterPropertiesSet(...)
    //
    // Job 이 "잘못 도는" 것이 아니라 "아예 못 뜨는" 것이므로 좋은 실패입니다.
    // =========================================================================
    /*
    @Configuration
    public static class NoRepositoryJobConfig {
        @Bean
        public Job brokenJob(Step settlementStep) {
            return new JobBuilder("brokenJob")      // deprecated 생성자 — JobRepository 없음
                    .start(settlementStep)
                    .build();
        }
    }
    */

    // =========================================================================
    // [2-5] 주입된 PlatformTransactionManager 가 무엇인지 눈으로 확인
    // -------------------------------------------------------------------------
    // 이 프로젝트는 spring-boot-starter-data-jpa 를 포함하므로 결과는
    //   >>> txManager = org.springframework.orm.jpa.JpaTransactionManager
    // 입니다. DataSourceTransactionManager 가 나오면 JPA 스타터가 빠진 것입니다.
    //
    // 왜 확인해야 하는가: DataSource 가 둘 이상인 프로젝트에서 Step 의 트랜잭션과
    // ItemWriter 가 쓰는 커넥션이 어긋나면, 청크가 롤백돼도 쓰기가 남습니다.
    // 예외는 정상적으로 나므로 로그만 보면 롤백된 줄 압니다.
    // =========================================================================
    @Configuration
    public static class WhichTxJobConfig {

        @Bean
        public Job whichTxJob(JobRepository jobRepository, Step whichTxStep) {
            return new JobBuilder("whichTxJob", jobRepository).start(whichTxStep).build();
        }

        @Bean
        public Step whichTxStep(JobRepository jobRepository,
                                PlatformTransactionManager txManager) {
            return new StepBuilder("whichTxStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> txManager = " + txManager.getClass().getName());
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // [2-5'] DB 를 건드리지 않는 Step — ResourcelessTransactionManager
    // -------------------------------------------------------------------------
    // 알림 발송, 파일 이동 같은 Tasklet 에 진짜 DB 트랜잭션을 열 이유가 없습니다.
    // 단, JobRepository 의 메타데이터 갱신은 여전히 진짜 DB 트랜잭션으로 나갑니다.
    // (JobRepository 는 자기 트랜잭션 매니저를 별도로 갖고 있습니다)
    // =========================================================================
    @Configuration
    public static class ResourcelessJobConfig {

        @Bean
        public Job notifyJob(JobRepository jobRepository, Step notifyStep) {
            return new JobBuilder("notifyJob", jobRepository).start(notifyStep).build();
        }

        @Bean
        public Step notifyStep(JobRepository jobRepository) {
            return new StepBuilder("notifyStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> 정산 완료 알림 발송 (DB 미사용)");
                        return RepeatStatus.FINISHED;
                    }, new ResourcelessTransactionManager())
                    .build();
        }
    }

    // =========================================================================
    // [2-6] ★ 함정 재현용 ★  @EnableBatchProcessing
    // -------------------------------------------------------------------------
    // 주석을 풀면:
    //   - BatchAutoConfiguration 이 통째로 물러납니다.
    //   - JobLauncherApplicationRunner 가 없어져 "모든 Job 이 실행되지 않습니다".
    //   - BATCH_* 테이블이 없는 상태라면 BadSqlGrammarException 으로 시끄럽게 죽고,
    //     테이블이 이미 있다면 아무 에러 없이 종료코드 0 으로 조용히 끝납니다. ← 최악
    //
    // 확인 방법:
    //   ./gradlew bootRun --args='--debug' 2>&1 | grep -A2 'BatchAutoConfiguration'
    //   → "Did not match: @ConditionalOnMissingBean ... found beans of annotation
    //      type EnableBatchProcessing 'trapConfig'"
    //
    // ⚠️ 재현이 끝나면 반드시 다시 주석 처리하세요.
    // =========================================================================
    /*
    @Configuration
    @EnableBatchProcessing
    public static class TrapConfig {
    }
    */

    // =========================================================================
    // [2-7] Step 세 개를 .next() 로 잇기
    // -------------------------------------------------------------------------
    // 로그에서 "Executing step:" 이 3번 나와야 정상입니다.
    // BATCH_JOB_EXECUTION 은 1행, BATCH_STEP_EXECUTION 은 3행입니다.
    //
    // ⚠️ transformStep 이 ExitStatus("WARN") 을 반환해도 loadStep 은 실행됩니다.
    //    .next() 는 ExitStatus 가 아니라 BatchStatus 를 보기 때문입니다.
    //    ExitStatus 로 분기하려면 Step 10 의 .on("WARN").to(...) 가 필요합니다.
    // =========================================================================
    @Configuration
    public static class PipelineJobConfig {

        @Bean
        public Job pipelineJob(JobRepository jobRepository,
                               Step extractStep, Step transformStep, Step loadStep) {
            return new JobBuilder("pipelineJob", jobRepository)
                    .start(extractStep)
                    .next(transformStep)
                    .next(loadStep)
                    .build();
        }

        @Bean
        public Step extractStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("extractStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> extract : orders 에서 COMPLETED 70000건 확인");
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }

        @Bean
        public Step transformStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("transformStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> transform : 수수료율 적용 규칙 로드");
                        // ExitStatus 를 바꿔도 .next() 흐름은 그대로 진행됩니다.
                        contribution.setExitStatus(new ExitStatus("WARN", "환율 테이블이 어제 것입니다"));
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }

        @Bean
        public Step loadStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("loadStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> load : settlement 적재 준비 완료");
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }
    }

    // =========================================================================
    // [2-8] ⚠️ 잘못된 방식 — 설정 클래스 필드에 상태를 둔다
    // -------------------------------------------------------------------------
    // Step 빈은 싱글턴입니다. 람다가 캡처한 것은 이 설정 클래스 인스턴스이고,
    // 그것은 JVM 이 살아 있는 동안 하나뿐입니다.
    //
    // 로컬에서는 bootRun 한 번 = JVM 한 번이라 counter 가 항상 1 입니다. 절대 재현 안 됩니다.
    // 운영에서 Quartz 로 같은 JVM 안에서 하루 24번 돌리면 counter 가 24까지 올라갑니다.
    // =========================================================================
    @Configuration
    public static class StatefulStepConfig {

        private int counter = 0;

        @Bean
        public Job statefulJob(JobRepository jobRepository, Step statefulStep) {
            return new JobBuilder("statefulJob", jobRepository).start(statefulStep).build();
        }

        @Bean
        public Step statefulStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("statefulStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        counter++;
                        System.out.println(">>> [BAD] counter = " + counter);
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }
    }

    // =========================================================================
    // [2-8'] ✅ 올바른 방식 — ExecutionContext 에 둔다
    // -------------------------------------------------------------------------
    // 실행마다 0 부터 시작하고, 값이 BATCH_STEP_EXECUTION_CONTEXT 에 저장되어
    // 재시작 시 이어집니다.
    //
    //   SELECT STEP_EXECUTION_ID, SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT;
    //   → {"@class":"java.util.HashMap","counter":1, ...}
    // =========================================================================
    @Configuration
    public static class StatefulFixedConfig {

        @Bean
        public Job statefulFixedJob(JobRepository jobRepository, Step statefulFixedStep) {
            return new JobBuilder("statefulFixedJob", jobRepository)
                    .start(statefulFixedStep)
                    .build();
        }

        @Bean
        public Step statefulFixedStep(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new StepBuilder("statefulFixedStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext ctx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        long counter = ctx.getLong("counter", 0L) + 1;
                        ctx.putLong("counter", counter);
                        System.out.println(">>> [GOOD] counter = " + counter);
                        return RepeatStatus.FINISHED;
                    }, tx)
                    .build();
        }
    }

    // =========================================================================
    // [2-10] 마이그레이션 확인용 명령/SQL 모음
    // =========================================================================
    public static final String MIGRATION_CHECKS = """
            # 1. 삭제된 팩토리가 남아 있는가 (컴파일러가 잡아 주지만 먼저 확인)
            grep -rn 'JobBuilderFactory\\|StepBuilderFactory' src/

            # 2. ★ 컴파일러가 못 잡는 항목 — 반드시 grep 할 것
            grep -rn 'EnableBatchProcessing' src/

            # 3. jakarta 전환 누락
            grep -rn 'javax\\.persistence\\|javax\\.annotation' src/

            # 4. 자동설정이 살아 있는지 확인
            ./gradlew bootRun --args='--debug' 2>&1 | grep -A2 'BatchAutoConfiguration'

            -- 5. 5.x 스키마인지 확인 (PARAMETER_NAME/TYPE/VALUE 3컬럼이어야 함)
            DESC BATCH_JOB_EXECUTION_PARAMS;

            -- 6. 4.x 잔재 컬럼이 남아 있는지
            SHOW COLUMNS FROM BATCH_JOB_EXECUTION LIKE 'JOB_CONFIGURATION_LOCATION';

            -- 7. 4.x → 5.x 파라미터 테이블 마이그레이션 DDL
            ALTER TABLE BATCH_JOB_EXECUTION_PARAMS
              DROP COLUMN TYPE_CD,
              DROP COLUMN DATE_VAL,
              DROP COLUMN LONG_VAL,
              DROP COLUMN DOUBLE_VAL,
              CHANGE COLUMN KEY_NAME   PARAMETER_NAME  VARCHAR(100)  NOT NULL,
              CHANGE COLUMN STRING_VAL PARAMETER_VALUE VARCHAR(2500),
              ADD COLUMN PARAMETER_TYPE VARCHAR(100) NOT NULL AFTER PARAMETER_NAME;

            ALTER TABLE BATCH_JOB_EXECUTION DROP COLUMN JOB_CONFIGURATION_LOCATION;

            -- 8. Step 실행 이력 확인
            SELECT STEP_EXECUTION_ID, STEP_NAME, STATUS, COMMIT_COUNT, EXIT_CODE
            FROM BATCH_STEP_EXECUTION
            WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
            ORDER BY STEP_EXECUTION_ID;
            """;
}
