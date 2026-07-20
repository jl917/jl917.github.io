package com.example.batch.step01;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 Job  :  본문 예제 모음
 * ============================================================================
 *
 * 실행 방법 (프로젝트 루트에서)
 *
 *   ./gradlew bootRun --args='--spring.batch.job.name=helloJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=helloJob greeting=hi runId(long)=7'
 *   ./gradlew bootRun --args='--spring.batch.job.name=exitCodeJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=failJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=twoStepJob'
 *
 * ⚠️ --spring.batch.job.name 을 빼면 이 파일의 Job 4개가 전부 실행됩니다 (본문 1-4).
 *
 * 메타데이터 초기화가 필요하면 project/index.md 의 P-10 (a) 스크립트를 쓰세요.
 * _SEQ 테이블은 절대 TRUNCATE 하지 마세요 (본문 1-12).
 * ============================================================================
 */

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

public final class Practice {

    private Practice() {
    }

    // =========================================================================
    // [1-1] 가장 단순한 Job — Tasklet 하나
    // =========================================================================
    //
    // 주목할 점 4가지
    //   (1) @EnableBatchProcessing 이 없다        → Boot 3.x 자동설정이 대신한다 (Step 02)
    //   (2) new JobBuilder(name, jobRepository)   → 5.0 에서 JobBuilderFactory 삭제
    //   (3) new StepBuilder(name, jobRepository)  → 5.0 에서 StepBuilderFactory 삭제
    //   (4) .tasklet(tasklet, txManager)          → 5.0 에서 트랜잭션 매니저가 인자로 승격
    //
    @Configuration
    public static class HelloJobConfig {

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
                        // FINISHED = "더 반복하지 마라"
                        // CONTINUABLE 을 반환하면 같은 Tasklet 이 새 트랜잭션에서 또 호출된다
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // [1-13] BatchStatus 는 COMPLETED 로 두고 ExitStatus 만 커스텀으로 바꾸기
    // =========================================================================
    //
    // 실행 후 확인:
    //   SELECT STATUS, EXIT_CODE FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 1;
    //   → STATUS = COMPLETED, EXIT_CODE = NO_DATA 여야 정상.
    //
    // 이 NO_DATA 를 Step 10 의 .on("NO_DATA").to(...) 분기 조건으로 쓰게 된다.
    //
    @Configuration
    public static class ExitCodeJobConfig {

        @Bean
        public Job exitCodeJob(JobRepository jobRepository, Step exitCodeStep) {
            return new JobBuilder("exitCodeJob", jobRepository)
                    .start(exitCodeStep)
                    .build();
        }

        @Bean
        public Step exitCodeStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("exitCodeStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        long processed = 0L;   // 처리 대상이 없었다고 가정
                        if (processed == 0L) {
                            contribution.setExitStatus(
                                    new ExitStatus("NO_DATA", "처리 대상이 없습니다"));
                        }
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // [1-14] 일부러 실패시키기 — BatchStatus 와 ExitStatus 가 갈라지는 순간
    // =========================================================================
    //
    // 이 Job 은 실패하는 것이 정상입니다. ERROR 로그와 스택트레이스가 나옵니다.
    //
    // 종료 코드 확인 (배치 운영에서 가장 먼저 확인해야 할 것):
    //   ./gradlew bootJar -q
    //   java -jar build/libs/spring-batch5-lab-1.0.0.jar \
    //        --spring.batch.job.name=failJob > /dev/null 2>&1; echo "exit=$?"
    //   → exit=1 이어야 한다. exit=0 이면 스케줄러가 실패를 못 알아챈다.
    //
    @Configuration
    public static class FailJobConfig {

        @Bean
        public Job failJob(JobRepository jobRepository, Step failStep) {
            return new JobBuilder("failJob", jobRepository)
                    .start(failStep)
                    .build();
        }

        @Bean
        public Step failStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("failStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        throw new IllegalStateException("정산 원장이 잠겨 있습니다");
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // [1-15+] Step 두 개짜리 Job — BATCH_STEP_EXECUTION 에 행이 2개 생기는지 확인
    // =========================================================================
    //
    // 로그에서 "Executing step:" 이 두 번 나와야 정상입니다 (본문 1-3 의 함정).
    //
    // 확인:
    //   SELECT STEP_NAME, STATUS, COMMIT_COUNT, EXIT_CODE
    //   FROM BATCH_STEP_EXECUTION
    //   WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
    //   ORDER BY STEP_EXECUTION_ID;
    //
    @Configuration
    public static class TwoStepJobConfig {

        @Bean
        public Job twoStepJob(JobRepository jobRepository, Step firstStep, Step secondStep) {
            return new JobBuilder("twoStepJob", jobRepository)
                    .start(firstStep)
                    .next(secondStep)     // .next() 로 이어 붙인다 (Step 02 에서 자세히)
                    .build();
        }

        @Bean
        public Step firstStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("firstStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> [1] 정산 대상 집계");
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }

        @Bean
        public Step secondStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("secondStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        // chunkContext 로 현재 실행 정보에 접근할 수 있다
                        String jobName = chunkContext.getStepContext()
                                .getStepExecution().getJobExecution()
                                .getJobInstance().getJobName();
                        Long execId = chunkContext.getStepContext()
                                .getStepExecution().getJobExecutionId();
                        System.out.println(">>> [2] " + jobName + " / jobExecutionId=" + execId);
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // [1-6 ~ 1-15] 메타데이터 확인용 SQL 모음
    // -------------------------------------------------------------------------
    // 코드에서 실행하지 않습니다. mysql CLI 에 복사해서 쓰세요.
    //
    //   mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb
    // =========================================================================
    public static final String METADATA_QUERIES = """
            -- [1-6] BATCH_JOB_INSTANCE : "무엇을 돌리는 일인가"
            SELECT * FROM BATCH_JOB_INSTANCE;
            SHOW CREATE TABLE BATCH_JOB_INSTANCE;        -- (JOB_NAME, JOB_KEY) UNIQUE 확인

            -- [1-7] BATCH_JOB_EXECUTION : "몇 번째 시도인가"
            SELECT JOB_EXECUTION_ID, JOB_INSTANCE_ID, VERSION,
                   CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE
            FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID;

            -- 좀비 실행 감시 쿼리 (운영에서 알람으로 걸어 둘 것)
            SELECT * FROM BATCH_JOB_EXECUTION
            WHERE STATUS = 'STARTED' AND END_TIME IS NULL;

            -- [1-8] 5.0 에서 구조가 바뀐 파라미터 테이블
            DESC BATCH_JOB_EXECUTION_PARAMS;
            SELECT * FROM BATCH_JOB_EXECUTION_PARAMS;

            -- [1-9] Job 범위 ExecutionContext
            SELECT JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT
            FROM BATCH_JOB_EXECUTION_CONTEXT;

            -- [1-10] 모든 카운트의 원천. READ = FILTER + WRITE 등식을 확인한다.
            SELECT STEP_NAME, STATUS, COMMIT_COUNT,
                   READ_COUNT, FILTER_COUNT, WRITE_COUNT,
                   READ_COUNT - FILTER_COUNT - WRITE_COUNT AS leak,
                   READ_SKIP_COUNT, PROCESS_SKIP_COUNT, WRITE_SKIP_COUNT, ROLLBACK_COUNT,
                   EXIT_CODE
            FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID;

            -- [1-11] Step 범위 ExecutionContext — 재시작 지점이 저장되는 곳
            SELECT STEP_EXECUTION_ID, SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT;

            -- [1-12] 채번 테이블 3종. 각각 정확히 1행이어야 한다. TRUNCATE 금지.
            SELECT 'JOB'  t, ID, UNIQUE_KEY FROM BATCH_JOB_SEQ
            UNION ALL SELECT 'JOB_EXEC',  ID, UNIQUE_KEY FROM BATCH_JOB_EXECUTION_SEQ
            UNION ALL SELECT 'STEP_EXEC', ID, UNIQUE_KEY FROM BATCH_STEP_EXECUTION_SEQ;

            -- [1-15] 실행 이력 한눈에 보기 (즐겨찾기 추천)
            SELECT i.JOB_INSTANCE_ID inst, i.JOB_NAME, e.JOB_EXECUTION_ID exec_id,
                   e.STATUS, e.EXIT_CODE,
                   TIMESTAMPDIFF(MICROSECOND, e.START_TIME, e.END_TIME)/1000 AS ms,
                   (SELECT COUNT(*) FROM BATCH_STEP_EXECUTION s
                     WHERE s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID) AS steps
            FROM BATCH_JOB_INSTANCE i
            JOIN BATCH_JOB_EXECUTION e ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
            ORDER BY e.JOB_EXECUTION_ID;
            """;
}
