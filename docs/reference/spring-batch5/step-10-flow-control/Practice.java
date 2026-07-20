package com.example.batch.step10;

/*
 * ============================================================================
 *  Step 10 — 흐름 제어 : 실습 파일
 * ============================================================================
 *
 *  실행 방법
 *    ./gradlew bootRun --args='--spring.batch.job.name=step10Job date=2025-03-01'
 *
 *  이 스텝의 한 문장
 *    on() 이 매칭하는 것은 BatchStatus 가 아니라 ExitStatus(EXIT_CODE 컬럼)입니다.
 *
 *  주의
 *    - HidingConfig(10-5) 는 "일부러 실패를 은폐하는" Job 입니다.
 *      재현하려면 settlement 테이블에 데이터가 남아 있어야 합니다(TRUNCATE 하지 마세요).
 *    - Step10Job(10-9) 실행 전에는 반대로 TRUNCATE TABLE settlement; 를 권합니다.
 * ============================================================================
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;

public final class Practice {

    private Practice() {
    }

    // ========================================================================
    // [10-2] on() 이 보는 것은 ExitStatus 입니다
    //
    //   checkStep 은 BatchStatus 는 COMPLETED 로 두고 ExitStatus 만 바꿉니다.
    //   BATCH_STEP_EXECUTION 에서 STATUS 와 EXIT_CODE 가 달라지는 것을 확인하세요.
    //
    //   실행 1: date=2025-03-01  → targetCount=389 → HAS_DATA → settleStep
    //   실행 2: date=2025-12-25  → targetCount=0   → NO_DATA  → skipNoticeStep
    // ========================================================================
    @Configuration
    static class ExitStatusConfig {

        private static final Logger log = LoggerFactory.getLogger(ExitStatusConfig.class);

        @Bean
        Job exitStatusJob(JobRepository jobRepository,
                          Step checkStep, Step esSettleStep, Step skipNoticeStep) {
            return new JobBuilder("exitStatusJob", jobRepository)
                    .start(checkStep)
                        .on("HAS_DATA").to(esSettleStep)
                    .from(checkStep)
                        .on("NO_DATA").to(skipNoticeStep)
                    .from(checkStep)
                        .on("*").fail()              // 안전망 (10-3 의 교훈)
                    .end()                           // ← FlowBuilder 를 닫습니다
                    .build();
        }

        @Bean
        Step checkStep(JobRepository jobRepository, PlatformTransactionManager tm,
                       JdbcTemplate jdbcTemplate) {
            return new StepBuilder("checkStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        String date = (String) chunkContext.getStepContext()
                                .getJobParameters().get("date");
                        Long cnt = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM orders "
                                        + "WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                                Long.class, date);
                        chunkContext.getStepContext().getStepExecution()
                                .getExecutionContext().putLong("targetCount", cnt == null ? 0L : cnt);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution stepExecution) {
                            long cnt = stepExecution.getExecutionContext().getLong("targetCount", 0L);
                            String code = (cnt == 0) ? "NO_DATA" : "HAS_DATA";
                            log.info("[checkStep] date={}, targetCount={} → ExitStatus={}",
                                    stepExecution.getJobParameters().getString("date"), cnt, code);
                            // BatchStatus 는 건드리지 않습니다. ExitStatus 만 바뀝니다.
                            return new ExitStatus(code);
                        }
                    })
                    .build();
        }

        @Bean
        Step esSettleStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("settleStep", jobRepository)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .build();
        }

        @Bean
        Step skipNoticeStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("skipNoticeStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        String date = (String) chunkContext.getStepContext()
                                .getJobParameters().get("date");
                        log.info("[skipNotice] {} 정산 대상 없음. 건너뜁니다.", date);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }
    }

    // ========================================================================
    // [10-3] 와일드카드와 매칭 우선순위
    //
    //   네 개의 전이가 모두 "COMPLETED" 에 매칭됩니다. 어디로 갈까요?
    //   → 실행하기 전에 먼저 예측하세요.
    //
    //   구체적 ────────────────────────► 포괄적
    //   "COMPLETED"  "C?MPLETED"  "COMPLETED*"  "*"
    // ========================================================================
    @Configuration
    static class WildcardConfig {

        private static final Logger log = LoggerFactory.getLogger(WildcardConfig.class);

        @Bean
        Job wildcardJob(JobRepository jobRepository, PlatformTransactionManager tm) {
            Step src = plain("wcSourceStep", jobRepository, tm, null);
            return new JobBuilder("wildcardJob", jobRepository)
                    .start(src)
                        .on("COMPLETED").to(plain("exactStep", jobRepository, tm, "정확 일치 패턴이 선택되었습니다"))
                    .from(src)
                        .on("C?MPLETED").to(plain("questionStep", jobRepository, tm, "? 패턴이 선택되었습니다"))
                    .from(src)
                        .on("COMPLETED*").to(plain("prefixStep", jobRepository, tm, "접두 패턴이 선택되었습니다"))
                    .from(src)
                        .on("*").to(plain("catchAllStep", jobRepository, tm, "전부 패턴이 선택되었습니다"))
                    .end()
                    .build();
        }

        private Step plain(String name, JobRepository r, PlatformTransactionManager tm, String msg) {
            return new StepBuilder(name, r)
                    .tasklet((c, cc) -> {
                        if (msg != null) {
                            log.info("[{}] {}", name, msg);
                        }
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }
    }

    // ========================================================================
    // [10-4] .end() / .fail() / .stopAndRestart()
    //
    //   종착점       Job BatchStatus   재시작
    //   .end()       COMPLETED         불가 (JobInstanceAlreadyCompleteException)
    //   .fail()      FAILED            가능 (실패한 Step 부터)
    //   .stopAnd...  STOPPED           가능 (지정한 Step 부터)
    // ========================================================================
    @Configuration
    static class TerminalConfig {

        private static final Logger log = LoggerFactory.getLogger(TerminalConfig.class);

        @Bean
        Job terminalJob(JobRepository jobRepository,
                        Step validateStep, Step tcSettleStep) {
            return new JobBuilder("terminalJob", jobRepository)
                    .start(validateStep)
                        .on("VALID").to(tcSettleStep)
                    .from(validateStep)
                        .on("INVALID").fail()                        // Job FAILED
                    .from(validateStep)
                        .on("NEEDS_APPROVAL").stopAndRestart(tcSettleStep)  // Job STOPPED
                    .from(tcSettleStep)
                        .on("*").end()                               // Job COMPLETED
                    .end()
                    .build();
        }

        @Bean
        Step validateStep(JobRepository jobRepository, PlatformTransactionManager tm,
                          JdbcTemplate jdbcTemplate) {
            return new StepBuilder("validateStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        String date = (String) chunkContext.getStepContext()
                                .getJobParameters().get("date");
                        BigDecimal total = jdbcTemplate.queryForObject(
                                "SELECT COALESCE(SUM(amount),0) FROM orders "
                                        + "WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                                BigDecimal.class, date);
                        chunkContext.getStepContext().getStepExecution()
                                .getExecutionContext().put("dayTotal", total);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution stepExecution) {
                            BigDecimal total = (BigDecimal) stepExecution.getExecutionContext().get("dayTotal");
                            if (total == null || total.signum() == 0) {
                                return new ExitStatus("INVALID");
                            }
                            // 하루 정산액이 1,500만 원을 넘으면 사람의 승인을 받습니다.
                            if (total.compareTo(new BigDecimal("15000000")) > 0) {
                                log.info("[validate] 승인 필요 금액 감지 → ExitStatus=NEEDS_APPROVAL");
                                return new ExitStatus("NEEDS_APPROVAL");
                            }
                            return new ExitStatus("VALID");
                        }
                    })
                    .build();
        }

        @Bean
        Step tcSettleStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("settleStep", jobRepository)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .build();
        }
    }

    // ========================================================================
    // [10-5] 실패를 은폐하는 Job  ← 이 스텝에서 가장 위험한 코드
    //
    //   settleStep 이 FAILED 인데 Job 은 COMPLETED 로 끝납니다.
    //   로그만 보면 아무 문제가 없어 보입니다. 반드시 SQL 로 확인하세요.
    //
    //     SELECT je.STATUS job_status, se.STEP_NAME, se.STATUS step_status
    //       FROM BATCH_JOB_EXECUTION je JOIN BATCH_STEP_EXECUTION se USING (JOB_EXECUTION_ID)
    //      WHERE je.JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION);
    // ========================================================================
    @Configuration
    static class HidingConfig {

        private static final Logger log = LoggerFactory.getLogger(HidingConfig.class);

        @Bean
        Job hidingJob(JobRepository jobRepository,
                      Step hcSettleStep, Step hcRecoveryStep, Step hcReportStep) {
            return new JobBuilder("hidingJob", jobRepository)
                    .start(hcSettleStep)
                        .on("COMPLETED").to(hcReportStep)
                    .from(hcSettleStep)
                        .on("FAILED").to(hcRecoveryStep).end()   // ← 실패를 지우는 한 줄
                    .from(hcReportStep)
                        .on("*").end()
                    .end()
                    .build();
        }

        @Bean
        Step hcSettleStep(JobRepository jobRepository, PlatformTransactionManager tm,
                          JdbcTemplate jdbcTemplate) {
            return new StepBuilder("settleStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        // settlement.uk_settlement_order 에 걸려 DuplicateKeyException 이 납니다.
                        jdbcTemplate.update(
                                "INSERT INTO settlement "
                                        + "(order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount) "
                                        + "SELECT order_id, customer_id, DATE(ordered_at), amount, 0.0300, "
                                        + "       ROUND(amount*0.0300,2), amount - ROUND(amount*0.0300,2) "
                                        + "  FROM orders WHERE status='COMPLETED' AND order_id <= 100");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step hcRecoveryStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("recoveryStep", jobRepository)
                    .tasklet((c, cc) -> {
                        log.info("[recovery] 부분 정산 데이터를 정리했습니다");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step hcReportStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("reportStep", jobRepository)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .build();
        }
    }

    // ========================================================================
    // [10-5] 교정판 — 복구는 하되 Job 은 실패로 끝냅니다
    //
    //   HidingConfig 와 딱 한 곳만 다릅니다: .end() → .fail()
    //   그 한 줄로 알람도 울리고 재시작도 가능해집니다.
    // ========================================================================
    @Configuration
    static class SafeConfig {

        private static final Logger log = LoggerFactory.getLogger(SafeConfig.class);

        @Bean
        Job safeJob(JobRepository jobRepository,
                    Step scSettleStep, Step scRecoveryStep, Step scReportStep) {
            return new JobBuilder("safeJob", jobRepository)
                    .start(scSettleStep)
                        .on("COMPLETED").to(scReportStep)
                    .from(scSettleStep)
                        .on("FAILED").to(scRecoveryStep)     // 복구는 하되
                    .from(scRecoveryStep)
                        .on("*").fail()                      // Job 은 실패로 끝냅니다
                    .from(scReportStep)
                        .on("*").end()
                    .end()
                    .build();
        }

        @Bean
        Step scSettleStep(JobRepository jobRepository, PlatformTransactionManager tm,
                          JdbcTemplate jdbcTemplate) {
            return new StepBuilder("settleStep", jobRepository)
                    .tasklet((c, cc) -> {
                        jdbcTemplate.update(
                                "INSERT INTO settlement "
                                        + "(order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount) "
                                        + "SELECT order_id, customer_id, DATE(ordered_at), amount, 0.0300, "
                                        + "       ROUND(amount*0.0300,2), amount - ROUND(amount*0.0300,2) "
                                        + "  FROM orders WHERE status='COMPLETED' AND order_id <= 100");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step scRecoveryStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("recoveryStep", jobRepository)
                    .tasklet((c, cc) -> {
                        log.info("[recovery] 부분 정산 데이터를 정리했습니다");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step scReportStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("reportStep", jobRepository)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .build();
        }
    }

    // ========================================================================
    // [10-6] JobExecutionDecider — 주문 건수로 정산 방식 분기
    //
    //   Decider 는 BATCH_STEP_EXECUTION 에 행을 남기지 않습니다.
    //   그리고 재시작할 때마다 "다시 평가"됩니다.
    //   그래서 판단 근거를 Job Context 에 저장해 두고 재사용합니다.
    // ========================================================================
    static class SettleModeDecider implements JobExecutionDecider {

        private static final Logger log = LoggerFactory.getLogger(SettleModeDecider.class);

        /** 이 값을 false 로 바꾸면 재시작 때마다 DB 를 다시 조회합니다 (함정 재현용). */
        static final boolean USE_CONTEXT_CACHE = true;

        private static final long BULK_THRESHOLD = 1_000L;

        private final JdbcTemplate jdbcTemplate;

        SettleModeDecider(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
            String date = jobExecution.getJobParameters().getString("date");
            ExecutionContext ctx = jobExecution.getExecutionContext();

            long count;
            if (USE_CONTEXT_CACHE && ctx.containsKey("targetCount")) {
                count = ctx.getLong("targetCount");
                log.info("컨텍스트에서 targetCount={} 재사용 → {}", count, mode(count));
            } else {
                count = queryCount(date);
                ctx.putLong("targetCount", count);
                log.info("date={}, targetCount={} → {}", date, count, mode(count));
            }
            return new FlowExecutionStatus(mode(count));
        }

        private long queryCount(String date) {
            Long c = (date == null)
                    ? jdbcTemplate.queryForObject(
                          "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'", Long.class)
                    : jdbcTemplate.queryForObject(
                          "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                          Long.class, date);
            return c == null ? 0L : c;
        }

        private String mode(long c) {
            if (c == 0) return "NO_DATA";
            return (c >= BULK_THRESHOLD) ? "BULK" : "NORMAL";
        }
    }

    @Configuration
    static class DeciderConfig {

        @Bean
        SettleModeDecider settleModeDecider(JdbcTemplate jdbcTemplate) {
            return new SettleModeDecider(jdbcTemplate);
        }

        @Bean
        Job deciderJob(JobRepository jobRepository, SettleModeDecider settleModeDecider,
                       Step dcPrepareStep, Step bulkSettleStep,
                       Step normalSettleStep, Step dcSkipNoticeStep) {
            return new JobBuilder("deciderJob", jobRepository)
                    .start(dcPrepareStep)
                    .next(settleModeDecider)
                        .on("BULK").to(bulkSettleStep)
                    .from(settleModeDecider)
                        .on("NORMAL").to(normalSettleStep)
                    .from(settleModeDecider)
                        .on("NO_DATA").to(dcSkipNoticeStep)
                    .from(settleModeDecider)
                        .on("*").fail()          // 안전망
                    .end()
                    .build();
        }

        @Bean
        Step dcPrepareStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("prepareStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step bulkSettleStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("bulkSettleStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step normalSettleStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("normalSettleStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step dcSkipNoticeStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("skipNoticeStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }

    // ========================================================================
    // [10-7] .split() — 병렬 흐름
    //
    //   로그의 스레드 이름이 agg-1 / agg-2 로 갈리는지 확인하세요.
    //   main 스레드에서 둘 다 돈다면 split 이 아니라 next 로 이어진 것입니다.
    // ========================================================================
    @Configuration
    static class SplitConfig {

        private static final Logger log = LoggerFactory.getLogger(SplitConfig.class);

        @Bean
        Flow customerAggFlow(Step customerAggStep) {
            return new FlowBuilder<Flow>("customerAggFlow").start(customerAggStep).build();
        }

        @Bean
        Flow cityAggFlow(Step cityAggStep) {
            return new FlowBuilder<Flow>("cityAggFlow").start(cityAggStep).build();
        }

        @Bean
        Job splitJob(JobRepository jobRepository,
                     Step spSettleStep, Step spReportStep,
                     Flow customerAggFlow, Flow cityAggFlow) {
            return new JobBuilder("splitJob", jobRepository)
                    .start(spSettleStep)
                    .next(new FlowBuilder<Flow>("parallelAgg")
                            .split(new SimpleAsyncTaskExecutor("agg-"))
                            .add(customerAggFlow, cityAggFlow)
                            .build())
                    .next(spReportStep)
                    .end()
                    .build();
        }

        /**
         * 흐름 개수가 늘어나면 SimpleAsyncTaskExecutor 대신 이쪽을 쓰세요.
         * SimpleAsyncTaskExecutor 는 요청마다 새 스레드를 만들고 재사용하지 않습니다.
         * 그리고 병렬 흐름 수보다 HikariCP 풀(maximum-pool-size: 20)이 커야 합니다.
         */
        static ThreadPoolTaskExecutor pooledExecutor() {
            ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
            exec.setCorePoolSize(4);
            exec.setMaxPoolSize(4);
            exec.setThreadNamePrefix("agg-");
            exec.initialize();
            return exec;
        }

        @Bean
        Step customerAggStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("customerAggStep", r)
                    .tasklet((c, cc) -> {
                        long t0 = System.currentTimeMillis();
                        Integer n = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT customer_id FROM orders "
                                        + "WHERE status='COMPLETED' GROUP BY customer_id) x", Integer.class);
                        log.info("[customerAgg] {}명 고객 집계 완료 ({}ms)", n, System.currentTimeMillis() - t0);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step cityAggStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("cityAggStep", r)
                    .tasklet((c, cc) -> {
                        long t0 = System.currentTimeMillis();
                        Integer n = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT c.city FROM orders o "
                                        + "JOIN customers c ON c.customer_id=o.customer_id "
                                        + "WHERE o.status='COMPLETED' GROUP BY c.city) x", Integer.class);
                        log.info("[cityAgg] {}개 도시 집계 완료 ({}ms)", n, System.currentTimeMillis() - t0);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step spSettleStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("settleStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step spReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("reportStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }

    // ========================================================================
    // [10-8] Flow 재사용과 FlowStep
    //
    //   .start(flow)  → Flow 안의 Step 들이 그대로 펼쳐집니다
    //   FlowStep      → 묶음 행이 하나 더 생깁니다 (settleFlowStep)
    // ========================================================================
    @Configuration
    static class FlowReuseConfig {

        @Bean
        Flow settleFlow(Step frValidateStep, Step frSettleStep, Step frVerifyStep) {
            return new FlowBuilder<Flow>("settleFlow")
                    .start(frValidateStep)
                        .on("VALID").to(frSettleStep)
                    .from(frValidateStep)
                        .on("*").fail()
                    .from(frSettleStep)
                        .on("*").to(frVerifyStep)
                    .build();
        }

        /** Flow 를 하나의 Step 으로 감쌉니다 (FlowStep). */
        @Bean
        Step settleFlowStep(JobRepository jobRepository, Flow settleFlow) {
            return new StepBuilder("settleFlowStep", jobRepository)
                    .flow(settleFlow)
                    .build();
        }

        @Bean
        Job dailyJob(JobRepository jobRepository, Flow settleFlow, Step dailyReportStep) {
            return new JobBuilder("dailyJob", jobRepository)
                    .start(settleFlow)
                    .next(dailyReportStep)
                    .end()
                    .build();
        }

        @Bean
        Job monthlyJob(JobRepository jobRepository, Step settleFlowStep,
                       Step monthlyReportStep, Step archiveStep) {
            // 같은 Flow 를 FlowStep 으로 감싸서 재사용합니다.
            return new JobBuilder("monthlyJob", jobRepository)
                    .start(settleFlowStep)
                    .next(monthlyReportStep)
                    .next(archiveStep)
                    .build();
        }

        @Bean
        Step frValidateStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("settle.validate", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution se) {
                            return new ExitStatus("VALID");
                        }
                    })
                    .build();
        }

        @Bean
        Step frSettleStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("settle.write", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step frVerifyStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("settle.verify", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step dailyReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("dailyReportStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step monthlyReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("monthlyReportStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step archiveStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("archiveStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }

    // ========================================================================
    // [10-9] 종합 — 정산 Job 의 완성형
    //
    //   prepareStep ─► Decider ─┬─ BULK    ─► bulkSettleStep    ─┬─► split(agg) ─► reportStep ─► end()
    //                            ├─ NORMAL  ─► normalSettleStep  ─┘
    //                            └─ NO_DATA ─► skipNoticeStep    ────────────────────────────► end()
    //                                           (실패 시 recoveryStep ─► fail())
    //
    //   실행 전: TRUNCATE TABLE settlement;
    // ========================================================================
    @Configuration
    static class Step10Job {

        private static final Logger log = LoggerFactory.getLogger(Step10Job.class);

        @Bean
        ExecutionContextPromotionListener s10Promotion() {
            ExecutionContextPromotionListener l = new ExecutionContextPromotionListener();
            l.setKeys(new String[]{"targetCount"});
            l.setStatuses(new String[]{"COMPLETED", "FAILED"});
            return l;
        }

        @Bean
        Job step10Job(JobRepository jobRepository, SettleModeDecider settleModeDecider,
                      Step s10PrepareStep, Step s10BulkStep, Step s10NormalStep,
                      Step s10SkipStep, Step s10RecoveryStep, Step s10ReportStep,
                      Flow s10AggFlow) {
            return new JobBuilder("step10Job", jobRepository)
                    .start(s10PrepareStep)
                    .next(settleModeDecider)
                        .on("BULK").to(s10BulkStep)
                    .from(settleModeDecider)
                        .on("NORMAL").to(s10NormalStep)
                    .from(settleModeDecider)
                        .on("NO_DATA").to(s10SkipStep)
                    .from(settleModeDecider)
                        .on("*").fail()
                    // 정산 Step 둘 다 성공하면 집계 흐름으로
                    .from(s10BulkStep).on("COMPLETED").to(s10AggFlow)
                    .from(s10NormalStep).on("COMPLETED").to(s10AggFlow)
                    // 실패하면 복구 후 Job 은 FAILED (실패를 은폐하지 않습니다)
                    .from(s10BulkStep).on("FAILED").to(s10RecoveryStep)
                    .from(s10NormalStep).on("FAILED").to(s10RecoveryStep)
                    .from(s10RecoveryStep).on("*").fail()
                    // 집계 후 리포트
                    .from(s10AggFlow).on("*").to(s10ReportStep)
                    .from(s10ReportStep).on("*").end()
                    .from(s10SkipStep).on("*").end()
                    .end()
                    .build();
        }

        @Bean
        Flow s10AggFlow(Step customerAggStep, Step cityAggStep) {
            return new FlowBuilder<Flow>("s10AggFlow")
                    .split(new SimpleAsyncTaskExecutor("agg-"))
                    .add(new FlowBuilder<Flow>("f1").start(customerAggStep).build(),
                         new FlowBuilder<Flow>("f2").start(cityAggStep).build())
                    .build();
        }

        @Bean
        Step s10PrepareStep(JobRepository r, PlatformTransactionManager tm,
                            JdbcTemplate jdbc, ExecutionContextPromotionListener s10Promotion) {
            return new StepBuilder("prepareStep", r)
                    .tasklet((contribution, chunkContext) -> {
                        String date = (String) chunkContext.getStepContext()
                                .getJobParameters().get("date");
                        Long cnt = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM orders "
                                        + "WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                                Long.class, date);
                        chunkContext.getStepContext().getStepExecution()
                                .getExecutionContext().putLong("targetCount", cnt == null ? 0L : cnt);
                        log.info("[prepare] date={}, targetCount={}", date, cnt);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .listener(s10Promotion)
                    .build();
        }

        @Bean
        Step s10BulkStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("bulkSettleStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step s10NormalStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("normalSettleStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step s10SkipStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("skipNoticeStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step s10RecoveryStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("recoveryStep", r)
                    .tasklet((c, cc) -> {
                        log.info("[recovery] 부분 정산 데이터를 정리했습니다");
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }

        @Bean
        Step s10ReportStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("reportStep", r)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext jobCtx = chunkContext.getStepContext()
                                .getStepExecution().getJobExecution().getExecutionContext();
                        String date = (String) chunkContext.getStepContext()
                                .getJobParameters().get("date");
                        long cnt = jobCtx.getLong("targetCount", 0L);
                        BigDecimal fee = jdbc.queryForObject(
                                "SELECT COALESCE(SUM(fee_amount),0) FROM settlement WHERE settle_date=?",
                                BigDecimal.class, date);
                        log.info("[report] {} 정산 {}건 ({}), 수수료 합계 {}",
                                date, cnt, cnt >= 1000 ? "BULK" : "NORMAL", fee);
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }
    }

    // ========================================================================
    // 흐름 확인용 SQL 모음
    //   mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "..."
    // ========================================================================
    static final String FLOW_INSPECT_SQL = """
            -- [10-2] STATUS 와 EXIT_CODE 는 다른 값입니다
            SELECT STEP_NAME, STATUS, EXIT_CODE
              FROM BATCH_STEP_EXECUTION
             ORDER BY STEP_EXECUTION_ID DESC LIMIT 4;

            -- [10-5] 실패 은폐 확인 — job_status 가 COMPLETED 인데 step_status 가 FAILED 라면 사고입니다
            SELECT je.JOB_EXECUTION_ID, je.STATUS AS job_status, je.EXIT_CODE AS job_exit,
                   se.STEP_NAME, se.STATUS AS step_status, se.EXIT_CODE AS step_exit
              FROM BATCH_JOB_EXECUTION je
              JOIN BATCH_STEP_EXECUTION se USING (JOB_EXECUTION_ID)
             WHERE je.JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
             ORDER BY se.STEP_EXECUTION_ID;

            -- 운영에서 주기적으로 돌려야 하는 감사 쿼리
            SELECT je.JOB_EXECUTION_ID, je.STATUS, COUNT(*) AS failed_steps
              FROM BATCH_JOB_EXECUTION je
              JOIN BATCH_STEP_EXECUTION se USING (JOB_EXECUTION_ID)
             WHERE je.STATUS = 'COMPLETED' AND se.STATUS = 'FAILED'
             GROUP BY je.JOB_EXECUTION_ID, je.STATUS;

            -- [10-6] Decider 는 행을 남기지 않습니다 (settleModeDecider 라는 이름이 없어야 정상)
            SELECT STEP_NAME, READ_COUNT, WRITE_COUNT
              FROM BATCH_STEP_EXECUTION
             ORDER BY STEP_EXECUTION_ID DESC LIMIT 3;

            -- [10-8] FlowStep 을 쓰면 묶음 행(settleFlowStep)이 하나 더 생깁니다
            SELECT STEP_NAME, STATUS, EXIT_CODE
              FROM BATCH_STEP_EXECUTION
             WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
             ORDER BY STEP_EXECUTION_ID;
            """;
}
