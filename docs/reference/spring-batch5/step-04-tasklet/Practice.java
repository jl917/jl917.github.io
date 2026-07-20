package com.example.batch.step04;

/*
 * ============================================================================
 * Step 04 — Tasklet Step / 실습 코드
 * ============================================================================
 *
 * 환경: Spring Boot 3.2.5 / Spring Batch 5.1.1 / Java 21 / MySQL 8.0.36(127.0.0.1:3308)
 *
 * 놓을 위치: src/main/java/com/example/batch/step04/Practice.java
 *
 * ── 실행 전 준비 (4-0) ──────────────────────────────────────────────────────
 *   mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb <<'SQL'
 *   TRUNCATE TABLE settlement;
 *   INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
 *   SELECT o.order_id, o.customer_id, DATE(o.ordered_at), o.amount, c.fee_rate,
 *          ROUND(o.amount * c.fee_rate, 2), o.amount - ROUND(o.amount * c.fee_rate, 2)
 *     FROM orders o JOIN customers c ON c.customer_id = o.customer_id
 *    WHERE o.status = 'COMPLETED';
 *   SQL
 *   → 70,000행 / gross 3,485,250,000.00 / fee 95,844,375.00 / net 3,389,405,625.00
 *
 *   [4-8] 을 실행할 거라면:
 *   mkdir -p /tmp/batch && touch /tmp/batch/settlement-2025-03-01.csv
 *
 * ── 실행 ────────────────────────────────────────────────────────────────────
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=helloTaskletJob"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=countingJob"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=ctxJob"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=reportJob,date=2025-03-01"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=archiveJob"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=cleanupJob,settleDate=2025-03-01"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=cleanupJob,mode=full"
 *
 * ── 트랜잭션 로그 켜기 ([4-3] 에서만) ────────────────────────────────────────
 *   application.yml:
 *     logging.level.org.springframework.jdbc.datasource.DataSourceTransactionManager: DEBUG
 *   확인이 끝나면 반드시 다시 끄세요. 로그가 매우 많아집니다.
 * ============================================================================
 */

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.MethodInvokingTaskletAdapter;
import org.springframework.batch.core.step.tasklet.SystemCommandTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

public class Practice {

    // ========================================================================
    // [4-1] 가장 단순한 Tasklet
    // ========================================================================
    @Configuration
    public static class HelloConfig {

        @Bean
        public Job helloTaskletJob(JobRepository jobRepository, Step helloTaskletStep) {
            return new JobBuilder("helloTaskletJob", jobRepository)
                    .start(helloTaskletStep)
                    .build();
        }

        /*
         * ⚠️ 5.0 부터 .tasklet(Tasklet, PlatformTransactionManager) 로 시그니처가 바뀌었습니다.
         *    4.x 의 stepBuilderFactory.get("step").tasklet(t).build() 는 컴파일되지 않습니다.
         *    트랜잭션 매니저를 암묵적으로 찾던 동작을 없애고 명시하도록 강제한 변경입니다.
         */
        @Bean
        public Step helloTaskletStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("helloTaskletStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println("[helloTasklet] 한 번 실행되고 끝납니다.");
                        return RepeatStatus.FINISHED;   // null 을 반환해도 동일하게 취급됩니다.
                    }, txManager)
                    .build();
        }
    }

    // ========================================================================
    // [4-2] [4-3] RepeatStatus.CONTINUABLE — 매 반복이 새 트랜잭션
    // [4-6] ExecutionContext 로 반복 상태 저장
    // ========================================================================
    @Configuration
    public static class RepeatConfig {

        /**
         * [4-3] ⚠️ 일부러 잘못 만든 버전 — 상태를 인스턴스 필드에 둡니다.
         *
         * 문제 두 가지:
         *   ① Tasklet Bean 은 싱글턴입니다. 같은 JVM 에서 이 Job 을 두 번 실행하면
         *      두 번째 실행은 count 가 이미 3 이라 1회차 만에 끝납니다.
         *      (bootRun 은 매번 새 JVM 이라 잘 안 보이지만, 테스트나 상주 프로세스에서는 바로 드러납니다.)
         *   ② Step 이 중간에 실패해 재시작하면 count 는 0부터 다시 시작합니다.
         *      이미 처리한 구간을 다시 처리하게 됩니다.
         *
         * 올바른 형태는 아래 ContextCountingTasklet 입니다. 나란히 놓고 비교하세요.
         */
        public static class CountingTasklet implements Tasklet {

            private int count = 0;

            @Override
            public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
                count++;
                System.out.printf("[countingTasklet] %d 회차 (thread=%s)%n",
                        count, Thread.currentThread().getName());
                return count < 3 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
            }
        }

        @Bean
        public Step countingStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("countingStep", jobRepository)
                    .tasklet(new CountingTasklet(), txManager)
                    .build();
        }

        @Bean
        public Job countingJob(JobRepository jobRepository, Step countingStep) {
            return new JobBuilder("countingJob", jobRepository)
                    .start(countingStep)
                    .build();
        }
        /*
         * 실행 후 확인:
         *   SELECT STEP_NAME, STATUS, COMMIT_COUNT, WRITE_COUNT, ROLLBACK_COUNT
         *     FROM BATCH_STEP_EXECUTION WHERE STEP_NAME='countingStep';
         *   → COMMIT_COUNT = 3. 반복 횟수와 정확히 같습니다.
         *
         * 트랜잭션 로그(DEBUG)에서도 Creating new transaction 3줄 / Committing 3줄이 나옵니다.
         * transactionTemplate.execute() 가 RepeatTemplate 의 루프 "안쪽" 에 있기 때문입니다.
         */

        /**
         * [4-6] 올바른 형태 — 반복 상태를 ExecutionContext 에 저장합니다.
         *
         * ExecutionContext 는 같은 트랜잭션에서 BATCH_STEP_EXECUTION_CONTEXT 에 직렬화되어
         * 저장되므로, 재시작 시 그대로 복원됩니다.
         */
        public static class ContextCountingTasklet implements Tasklet {

            @Override
            public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
                ExecutionContext ctx = chunkContext.getStepContext()
                        .getStepExecution().getExecutionContext();

                int count = ctx.getInt("iteration", 0) + 1;
                ctx.putInt("iteration", count);

                System.out.printf("[ctxTasklet] %d 회차%n", count);
                return count < 3 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
            }
        }

        @Bean
        public Step ctxStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("ctxStep", jobRepository)
                    .tasklet(new ContextCountingTasklet(), txManager)
                    .build();
        }

        @Bean
        public Job ctxJob(JobRepository jobRepository, Step ctxStep) {
            return new JobBuilder("ctxJob", jobRepository)
                    .start(ctxStep)
                    .build();
        }

        /*
         * ====================================================================
         * [4-4] ❌ 무한루프 — 기본적으로 주석 처리해 두었습니다.
         * ====================================================================
         *
         * 직접 보고 싶다면 @Bean 주석을 풀고 실행하세요. 단, 두 가지를 지키세요.
         *   1) Ctrl+C 로 죽일 준비를 하고 실행할 것
         *   2) 죽인 뒤 반드시 파일 하단의 "무한루프 뒤처리 SQL" 을 실행할 것
         *
         * 안 하면 BATCH_JOB_EXECUTION.STATUS 가 STARTED / END_TIME 이 NULL 로 남아
         * 같은 파라미터 재실행 시 JobExecutionAlreadyRunningException 이 납니다.
         * 프로세스는 죽었는데 DB 는 살아 있다고 믿는 상태입니다.
         *
         * @Bean
         * public Step infiniteStep(JobRepository jobRepository, PlatformTransactionManager txManager,
         *                          JdbcTemplate jdbcTemplate) {
         *     return new StepBuilder("infiniteStep", jobRepository)
         *             .tasklet((contribution, chunkContext) -> {
         *                 int deleted = jdbcTemplate.update(
         *                         "DELETE FROM settlement WHERE settle_date = '2099-01-01' LIMIT 1000");
         *                 System.out.println("[infiniteStep] deleted=" + deleted);
         *                 return RepeatStatus.CONTINUABLE;   // ← 종료 조건이 없다
         *             }, txManager)
         *             .build();
         * }
         *
         * 고치는 방법은 한 줄입니다:
         *     return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
         *
         * 판정은 반드시 "이번에 처리한 건수" 로 합니다.
         * SELECT COUNT(*) > 0 으로 판정하면 FK 나 트리거로 지워지지 않는 행이 하나만 있어도
         * 영원히 돕니다. DELETE 의 영향 행 수로 판정하면 진행이 없을 때 반드시 멈춥니다.
         */
    }

    // ========================================================================
    // [4-7] MethodInvokingTaskletAdapter
    // ========================================================================

    /** Spring Batch 의존성이 하나도 없는 평범한 서비스. 이것이 어댑터의 존재 이유입니다. */
    @Component
    public static class SettlementReportService {

        private final JdbcTemplate jdbcTemplate;

        public SettlementReportService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        public long summarize(String settleDate) {
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(net_amount), 0) FROM settlement WHERE settle_date = ?",
                    Long.class, settleDate);
            System.out.printf("[report] %s 정산 순금액 합계 = %,d%n", settleDate, total);
            return total;
        }
    }

    @Configuration
    public static class AdapterConfig {

        /*
         * ⚠️ 함정 1 — 메서드 이름이 문자열입니다.
         *    setTargetMethod("summarise") 처럼 한 글자만 틀려도 컴파일은 통과하고,
         *    기동도 되고, Step 실행 시점에야 터집니다:
         *
         *      Caused by: java.lang.IllegalArgumentException: Unable to locate method: summarise
         *          at org.springframework.beans.support.ArgumentConvertingMethodInvoker.prepare(...)
         *          at org.springframework.batch.core.step.tasklet.MethodInvokingTaskletAdapter.execute(...)
         *
         *    한 번 틀려 보고 실행해 보길 권합니다. IDE 리팩터링도 이 문자열은 안 바꿔 줍니다.
         *
         * ⚠️ 함정 2 — 반환값이 ExitStatus 로 해석됩니다.
         *    summarize() 가 long 을 돌려주므로 EXIT_CODE 가 "18842196" 같은 숫자가 됩니다.
         *    Step 10 의 Flow 분기에서 .on("COMPLETED") 를 기대하고 있었다면 분기가 조용히 안 걸립니다.
         *    반환 타입이 void 면 ExitStatus.COMPLETED 가 됩니다.
         *
         * 실무에서는 어댑터보다 람다로 서비스를 직접 호출하는 편이 더 안전합니다:
         *    .tasklet((c, ctx) -> { service.summarize(date); return RepeatStatus.FINISHED; }, txManager)
         */
        @Bean
        @StepScope
        public MethodInvokingTaskletAdapter reportTasklet(
                SettlementReportService service,
                @Value("#{jobParameters['date']}") String date) {
            MethodInvokingTaskletAdapter adapter = new MethodInvokingTaskletAdapter();
            adapter.setTargetObject(service);
            adapter.setTargetMethod("summarize");
            adapter.setArguments(new Object[]{date});
            return adapter;
        }

        @Bean
        public Step reportStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                               MethodInvokingTaskletAdapter reportTasklet) {
            return new StepBuilder("reportStep", jobRepository)
                    .tasklet(reportTasklet, txManager)
                    .build();
        }

        @Bean
        public Job reportJob(JobRepository jobRepository, Step reportStep) {
            return new JobBuilder("reportJob", jobRepository)
                    .start(reportStep)
                    .build();
        }
    }

    // ========================================================================
    // [4-8] SystemCommandTasklet
    // ========================================================================
    @Configuration
    public static class SystemCommandConfig {

        /*
         * 실행 전 준비:
         *   mkdir -p /tmp/batch && touch /tmp/batch/settlement-2025-03-01.csv
         *
         * ⚠️ 함정 — 5.0 에서 setCommand(String) 이 setCommand(String... command) 로 바뀌었습니다.
         *    4.x 는 문자열 하나를 받아 공백으로 쪼갰지만 5.x 는 각 인자를 배열 원소로 넘겨야 합니다.
         *    setCommand("gzip -f a.csv") 로 넣으면 셸이 그 이름의 실행 파일을 찾다가 실패합니다.
         *    그리고 파이프(|)·리다이렉션(>)·와일드카드(*)는 셸 기능이므로 /bin/sh -c 로 감싸야 합니다.
         *
         * 💡 setTimeout() 은 사실상 필수입니다. 기본값 0 은 무제한이라,
         *    외부 명령이 응답 없이 멈추면 배치도 영원히 멈춥니다. [4-4] 의 무한루프와 같은 결과입니다.
         */
        @Bean
        public SystemCommandTasklet archiveTasklet() {
            SystemCommandTasklet tasklet = new SystemCommandTasklet();
            tasklet.setCommand("/bin/sh", "-c", "gzip -f /tmp/batch/settlement-2025-03-01.csv");
            tasklet.setTimeout(60_000);
            tasklet.setWorkingDirectory("/tmp/batch");
            tasklet.setInterruptOnCancel(true);          // Step 중단 시 자식 프로세스도 죽임
            tasklet.setTerminationCheckInterval(1000);
            return tasklet;
        }

        @Bean
        public Step archiveStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                                SystemCommandTasklet archiveTasklet) {
            return new StepBuilder("archiveStep", jobRepository)
                    .tasklet(archiveTasklet, txManager)
                    .build();
        }

        @Bean
        public Job archiveJob(JobRepository jobRepository, Step archiveStep) {
            return new JobBuilder("archiveJob", jobRepository)
                    .start(archiveStep)
                    .build();
        }
    }

    // ========================================================================
    // [4-10] 실전 — settlement 정리 Tasklet
    // ========================================================================

    /**
     * 요구사항
     *   1. settleDate 가 있으면 그 날짜만, 없으면 전체 삭제
     *   2. 10,000행씩 나눠 지워 락을 오래 잡지 않는다 (CONTINUABLE = 커밋 지점을 내가 만든다)
     *   3. 삭제 건수를 StepContribution 에 보고한다
     *   4. 진행이 없으면 반드시 종료한다 (무한루프 방지)
     *   5. 반복 상한을 둔다 (이중 안전장치)
     *
     * 셋 중 하나라도 빼면:
     *   - 3 을 빼면 → WRITE_COUNT 가 0. 데이터는 맞는데 모니터링이 "0건 처리" 라고 거짓말합니다.
     *   - 4 를 빼면 → 무한루프. 죽여도 STATUS=STARTED 로 남아 재실행 불가.
     *   - 5 를 빼면 → 4 의 조건에 버그가 있을 때 잡아 줄 그물이 없습니다.
     */
    public static class SettlementCleanupTasklet implements Tasklet {

        private static final int BATCH_SIZE = 10_000;
        private static final int MAX_ITERATIONS = 1_000;   // 10,000 × 1,000 = 1천만 행

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
                    : jdbcTemplate.update(
                            "DELETE FROM settlement WHERE settle_date = ? LIMIT " + BATCH_SIZE,
                            settleDate);

            int total = ctx.getInt("deletedTotal", 0) + deleted;
            ctx.putInt("iteration", iteration);
            ctx.putInt("deletedTotal", total);

            contribution.incrementWriteCount(deleted);   // ★ [4-5] 의 함정. 절대 빼먹지 마세요.

            System.out.printf("[cleanup] %2d회차 deleted=%,d (누적 %,d)%n", iteration, deleted, total);

            if (deleted == 0) {
                // 0건이 나올 때까지 도는 것이 유일하게 안전한 종료 조건입니다.
                // "deleted < BATCH_SIZE 면 끝" 이라는 추측은 정확히 BATCH_SIZE 만큼 남았을 때 틀립니다.
                contribution.setExitStatus(
                        total == 0 ? new ExitStatus("NOTHING_TO_DELETE") : ExitStatus.COMPLETED);
                return RepeatStatus.FINISHED;
            }
            return RepeatStatus.CONTINUABLE;
        }
    }

    @Configuration
    public static class CleanupConfig {

        @Bean
        @StepScope
        public SettlementCleanupTasklet cleanupTasklet(
                JdbcTemplate jdbcTemplate,
                @Value("#{jobParameters['settleDate']}") String settleDate) {
            // settleDate 를 넘기지 않으면 null 이 되어 전체 삭제 분기를 탑니다.
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
    }

    /*
     * ============================================================================
     * 확인용 SQL
     * ============================================================================
     *
     * -- 반복 횟수 = COMMIT_COUNT 확인
     * SELECT STEP_NAME, STATUS, EXIT_CODE, COMMIT_COUNT, WRITE_COUNT, ROLLBACK_COUNT
     *   FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID;
     *
     * -- ExecutionContext 에 저장된 반복 상태 확인
     * SELECT s.STEP_NAME, c.SHORT_CONTEXT
     *   FROM BATCH_STEP_EXECUTION_CONTEXT c
     *   JOIN BATCH_STEP_EXECUTION s ON s.STEP_EXECUTION_ID = c.STEP_EXECUTION_ID
     *  WHERE s.STEP_NAME IN ('ctxStep', 'cleanupStep');
     *
     * -- 남은 정산 행 수
     * SELECT COUNT(*) FROM settlement;
     *
     * ── 무한루프 뒤처리 SQL ([4-4] 를 실행했다면 반드시) ─────────────────────
     * -- 먼저 매달린 실행을 찾습니다.
     * SELECT JOB_EXECUTION_ID, STATUS, START_TIME, END_TIME
     *   FROM BATCH_JOB_EXECUTION WHERE STATUS = 'STARTED';
     *
     * -- 그 ID 로 강제 종료 처리합니다.
     * UPDATE BATCH_STEP_EXECUTION
     *    SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=NOW()
     *  WHERE JOB_EXECUTION_ID = <위에서 찾은 ID>;
     * UPDATE BATCH_JOB_EXECUTION
     *    SET STATUS='FAILED', EXIT_CODE='FAILED', END_TIME=NOW()
     *  WHERE JOB_EXECUTION_ID = <위에서 찾은 ID>;
     *
     * ── 실습 데이터 복구 (settlement 를 비웠다면) ──────────────────────────
     * TRUNCATE TABLE settlement;
     * INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
     * SELECT o.order_id, o.customer_id, DATE(o.ordered_at), o.amount, c.fee_rate,
     *        ROUND(o.amount * c.fee_rate, 2), o.amount - ROUND(o.amount * c.fee_rate, 2)
     *   FROM orders o JOIN customers c ON c.customer_id = o.customer_id
     *  WHERE o.status = 'COMPLETED';
     * ============================================================================
     */
}
