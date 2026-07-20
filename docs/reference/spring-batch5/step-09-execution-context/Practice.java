package com.example.batch.step09;

/*
 * ============================================================================
 *  Step 09 — ExecutionContext 와 스코프 : 실습 파일
 * ============================================================================
 *
 *  실행 방법
 *    ./gradlew bootRun --args='--spring.batch.job.name=step09Job date=2025-03-01'
 *
 *  주의
 *    - BrokenScopeConfig(9-7) / LeakyProxyConfig(9-8) 은 @Configuration 이 주석
 *      처리되어 있습니다. 재현할 때만 주석을 푸세요.
 *      9-7 은 "애플리케이션 기동 실패"이므로 풀어 둔 채로는 다른 실습이 전부 막힙니다.
 *    - Step09Job 실행 전에 TRUNCATE TABLE settlement; 를 권합니다.
 *      (settlement.uk_settlement_order UNIQUE 때문에 재실행 시 중복 키가 납니다)
 * ============================================================================
 */

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class Practice {

    private Practice() {
    }

    // ========================================================================
    // [9-2] Job Context 와 Step Context 는 서로 안 보입니다
    //       — 승격 없이 Step Context 에만 넣으면 다음 Step 에서 null 입니다.
    // ========================================================================
    @Configuration
    static class ContextScopeConfig {

        private static final Logger log = LoggerFactory.getLogger(ContextScopeConfig.class);

        @Bean
        Job contextScopeJob(JobRepository jobRepository,
                            Step csWriteStep, Step csReadStep) {
            return new JobBuilder("contextScopeJob", jobRepository)
                    .start(csWriteStep)
                    .next(csReadStep)
                    .build();
        }

        @Bean
        Step csWriteStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("writeStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext stepCtx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        stepCtx.putLong("completedCount", 70000L);
                        log.info("[writeStep] stepContext 에 completedCount=70000 저장");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step csReadStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("readStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext stepCtx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        ExecutionContext jobCtx = chunkContext.getStepContext()
                                .getStepExecution().getJobExecution().getExecutionContext();
                        // 둘 다 null 입니다. 이것이 이 절의 결론입니다.
                        log.info("[readStep] stepContext.completedCount = {}", stepCtx.get("completedCount"));
                        log.info("[readStep] jobContext.completedCount  = {}", jobCtx.get("completedCount"));
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }
    }

    // ========================================================================
    // [9-3] ExecutionContextPromotionListener — Step -> Job 승격
    // ========================================================================
    @Configuration
    static class PromotionConfig {

        private static final Logger log = LoggerFactory.getLogger(PromotionConfig.class);

        /**
         * 승격 리스너. Job 이 아니라 <b>Step</b> 에 붙여야 합니다.
         * afterStep 콜백에서 동작하므로, Step 이 끝나야 Job Context 에 반영됩니다.
         */
        @Bean
        ExecutionContextPromotionListener promotionListener() {
            ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
            listener.setKeys(new String[]{"completedCount", "settleDate"});
            listener.setStatuses(new String[]{"COMPLETED"});  // 기본값과 동일. 명시하는 습관을 권합니다.
            listener.setStrict(false);                        // 키가 없어도 예외를 내지 않습니다.
            return listener;
        }

        @Bean
        Job promotionJob(JobRepository jobRepository, Step pmWriteStep, Step pmReadStep) {
            return new JobBuilder("promotionJob", jobRepository)
                    .start(pmWriteStep)
                    .next(pmReadStep)
                    .build();
        }

        @Bean
        Step pmWriteStep(JobRepository jobRepository, PlatformTransactionManager tm,
                         ExecutionContextPromotionListener promotionListener) {
            return new StepBuilder("writeStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext stepCtx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        stepCtx.putLong("completedCount", 70000L);
                        stepCtx.putString("settleDate", "2025-03-01");
                        log.info("[writeStep] stepContext 에 completedCount=70000 저장");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .listener(promotionListener)   // ← 여기가 핵심
                    .build();
        }

        @Bean
        Step pmReadStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("readStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext stepCtx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        ExecutionContext jobCtx = chunkContext.getStepContext()
                                .getStepExecution().getJobExecution().getExecutionContext();
                        log.info("[readStep] stepContext.completedCount = {}", stepCtx.get("completedCount")); // null
                        log.info("[readStep] jobContext.completedCount  = {}", jobCtx.get("completedCount"));  // 70000
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }
    }

    // ========================================================================
    // [9-5] ExecutionContext 는 재시작의 근거입니다
    //       41,000번째 아이템에서 일부러 실패시키고, 같은 파라미터로 재실행합니다.
    // ========================================================================
    @Configuration
    static class RestartConfig {

        private static final Logger log = LoggerFactory.getLogger(RestartConfig.class);

        @Bean
        Job restartDemoJob(JobRepository jobRepository, Step settleStepWithTrap) {
            return new JobBuilder("restartDemoJob", jobRepository)
                    .start(settleStepWithTrap)
                    .build();
        }

        @Bean
        Step settleStepWithTrap(JobRepository jobRepository, PlatformTransactionManager tm,
                                JdbcPagingItemReader<Order> allCompletedReader,
                                ItemProcessor<Order, Settlement> boobyTrapProcessor,
                                JdbcBatchItemWriter<Settlement> settlementWriter) {
            return new StepBuilder("settleStep", jobRepository)
                    .<Order, Settlement>chunk(1000, tm)
                    .reader(allCompletedReader)
                    .processor(boobyTrapProcessor)
                    .writer(settlementWriter)
                    .build();
        }

        /**
         * 리턴 타입이 <b>구현체</b>입니다. 이래야 @StepScope 프록시가 ItemStream 을 구현하고,
         * SimpleStepBuilder 가 registerStream() 으로 등록해 재시작이 동작합니다. (9-8 참고)
         */
        @Bean
        @StepScope
        JdbcPagingItemReader<Order> allCompletedReader(DataSource dataSource) {
            MySqlPagingQueryProviderStub provider = new MySqlPagingQueryProviderStub();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status = 'COMPLETED'");
            provider.setSortKey("order_id");

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("JdbcPagingItemReader");   // ← 컨텍스트 키 접두사가 됩니다
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider.build());
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        /**
         * 41,000번째 아이템에서 터집니다.
         * 청크 1,000 이므로 40청크(=40,000건)까지 커밋되고, 41번째 청크가 롤백됩니다.
         * 따라서 컨텍스트의 read.count 는 41000 이 아니라 40000 입니다.
         */
        @Bean
        @StepScope
        ItemProcessor<Order, Settlement> boobyTrapProcessor(JdbcTemplate jdbcTemplate) {
            AtomicInteger seen = new AtomicInteger();
            return order -> {
                if (seen.incrementAndGet() == 41_000) {
                    throw new IllegalStateException("의도적 실패 — orderId=" + order.order_id());
                }
                return toSettlement(order, feeRateOf(jdbcTemplate, order.customerId()));
            };
        }
    }

    // ========================================================================
    // [9-6] @StepScope 와 늦은 바인딩
    // ========================================================================
    @Configuration
    static class ScopeConfig {

        private static final Logger log = LoggerFactory.getLogger(ScopeConfig.class);

        /**
         * #{jobParameters['date']} 는 @StepScope 가 등록한 SpEL 루트에서만 해석됩니다.
         * 이 애노테이션을 지우면 기동 시점에 EL1008E 로 실패합니다. (9-7)
         */
        @Bean
        @StepScope
        JdbcPagingItemReader<Order> dailyOrderReader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            log.debug("dailyOrderReader 생성 — date={}", date);

            MySqlPagingQueryProviderStub provider = new MySqlPagingQueryProviderStub();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status = 'COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKey("order_id");

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("dailyOrderReader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider.build());
            reader.setParameterValues(Map.of("date", date));
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        /**
         * 승격된 Job Context 값을 SpEL 로 받습니다.
         * 앞 Step 에 ExecutionContextPromotionListener 가 붙어 있어야 값이 존재합니다.
         */
        @Bean
        @StepScope
        org.springframework.batch.core.step.tasklet.Tasklet reportTasklet(
                @Value("#{jobExecutionContext['completedCount']}") Long completedCount) {
            return (contribution, chunkContext) -> {
                log.info("정산 대상 {}건에 대한 리포트 생성", completedCount);
                return RepeatStatus.FINISHED;
            };
        }

        /**
         * 파티셔닝(Step 13)에서 쓰는 형태입니다.
         * stepExecutionContext 는 @StepScope 에서만 해석됩니다. @JobScope 에서는 EL1008E 입니다.
         */
        @Bean
        @StepScope
        JdbcPagingItemReader<Order> partitionedReader(
                DataSource dataSource,
                @Value("#{stepExecutionContext['minId']}") Long minId,
                @Value("#{stepExecutionContext['maxId']}") Long maxId) {

            log.debug("partitionedReader 생성 — minId={}, maxId={}", minId, maxId);

            MySqlPagingQueryProviderStub provider = new MySqlPagingQueryProviderStub();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status='COMPLETED' AND order_id BETWEEN :minId AND :maxId");
            provider.setSortKey("order_id");

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("partitionedReader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider.build());
            reader.setParameterValues(Map.of("minId", minId, "maxId", maxId));
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }
    }

    // ========================================================================
    // [9-7] @StepScope 누락 — 기동 시점에 EL1008E
    //
    //   재현하려면 아래 @Configuration 주석을 푸세요.
    //   푼 채로 두면 애플리케이션이 아예 뜨지 않아 다른 실습이 전부 막힙니다.
    //
    //   Caused by: org.springframework.expression.spel.SpelEvaluationException:
    //     EL1008E: Property or field 'jobParameters' cannot be found on object of
    //     type 'org.springframework.beans.factory.config.BeanExpressionContext'
    // ========================================================================
    // @Configuration
    static class BrokenScopeConfig {

        @Bean
        // @StepScope   ← 이 한 줄이 없어서 실패합니다
        JdbcPagingItemReader<Order> brokenReader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            MySqlPagingQueryProviderStub provider = new MySqlPagingQueryProviderStub();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status='COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKey("order_id");

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("brokenReader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider.build());
            reader.setParameterValues(Map.of("date", date));
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }
    }

    // ========================================================================
    // [9-8] 프록시 함정 — 리턴 타입을 인터페이스로 쓰면 ItemStream 이 사라집니다
    //
    //   @StepScope == @Scope(value="step", proxyMode=ScopedProxyMode.TARGET_CLASS)
    //   프록시가 무엇을 구현할지는 @Bean 메서드의 "리턴 타입"이 정합니다.
    //
    //   재현하려면 아래 @Configuration 주석을 푸세요.
    // ========================================================================
    // @Configuration
    static class LeakyProxyConfig {

        /**
         * (A) 위험 — 리턴 타입이 인터페이스입니다.
         *     프록시가 ItemStream 을 구현하지 않아 open()/update()/close() 가 호출되지 않습니다.
         *     JdbcCursorItemReader 는 open() 에서 ResultSet 을 열므로 첫 read() 에서 터집니다.
         *
         *     org.springframework.batch.item.ReaderNotOpenException:
         *       Reader must be open before it can be read.
         */
        @Bean
        @StepScope
        ItemReader<Order> leakyReader(DataSource ds,
                                      @Value("#{jobParameters['date']}") String date) {
            JdbcCursorItemReader<Order> reader = new JdbcCursorItemReader<>();
            reader.setName("leakyReader");
            reader.setDataSource(ds);
            reader.setSql("SELECT order_id, customer_id, amount, status, ordered_at FROM orders "
                    + "WHERE status='COMPLETED' AND DATE(ordered_at)=?");
            reader.setPreparedStatementSetter(ps -> ps.setString(1, date));
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        /**
         * (B) 정답 — 리턴 타입을 구현체로 선언합니다.
         *     프록시가 JdbcCursorItemReader 를 상속하므로 ItemStream 도 함께 구현합니다.
         */
        @Bean
        @StepScope
        JdbcCursorItemReader<Order> safeReader(DataSource ds,
                                               @Value("#{jobParameters['date']}") String date) {
            JdbcCursorItemReader<Order> reader = new JdbcCursorItemReader<>();
            reader.setName("safeReader");
            reader.setDataSource(ds);
            reader.setSql("SELECT order_id, customer_id, amount, status, ordered_at FROM orders "
                    + "WHERE status='COMPLETED' AND DATE(ordered_at)=?");
            reader.setPreparedStatementSetter(ps -> ps.setString(1, date));
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        /**
         * (C) 차선책 — 인터페이스로 노출하되 .stream(...) 으로 명시 등록합니다.
         *     결국 ItemStream 타입을 알아야 하므로 (B) 가 더 낫습니다.
         */
        @Bean
        Step explicitStreamStep(JobRepository jobRepository, PlatformTransactionManager tm,
                                ItemReader<Order> leakyReader,
                                ItemProcessor<Order, Settlement> passThrough,
                                JdbcBatchItemWriter<Settlement> settlementWriter) {
            return new StepBuilder("explicitStreamStep", jobRepository)
                    .<Order, Settlement>chunk(1000, tm)
                    .reader(leakyReader)
                    .processor(passThrough)
                    .writer(settlementWriter)
                    .stream((org.springframework.batch.item.ItemStream) leakyReader)  // ← 명시 등록
                    .build();
        }
    }

    // ========================================================================
    // [9-9] 스코프 빈이 몇 번 만들어지는가
    //       @StepScope = StepExecution 마다, @JobScope = JobExecution 마다
    // ========================================================================
    @Configuration
    static class ProbeConfig {

        private static final Logger log = LoggerFactory.getLogger(ProbeConfig.class);

        // 싱글턴 설정 클래스의 필드이므로 애플리케이션 단위로 누적됩니다.
        private final AtomicInteger stepScopedCount = new AtomicInteger();
        private final AtomicInteger jobScopedCount = new AtomicInteger();

        record StepScopedProbe(String stepName) {
        }

        record JobScopedProbe(String jobName) {
        }

        @Bean
        @StepScope
        StepScopedProbe stepProbe(@Value("#{stepExecution.stepName}") String stepName) {
            log.info(">>> @StepScope 빈 생성 #{} (step={})", stepScopedCount.incrementAndGet(), stepName);
            return new StepScopedProbe(stepName);
        }

        @Bean
        @JobScope
        JobScopedProbe jobProbe(@Value("#{jobExecution.jobInstance.jobName}") String jobName) {
            log.info(">>> @JobScope  빈 생성 #{} (job={})", jobScopedCount.incrementAndGet(), jobName);
            return new JobScopedProbe(jobName);
        }

        @Bean
        Job scopeProbeJob(JobRepository jobRepository,
                          Step probeStepA, Step probeStepB, Step probeStepC) {
            return new JobBuilder("scopeProbeJob", jobRepository)
                    .start(probeStepA).next(probeStepB).next(probeStepC)
                    .build();
        }

        @Bean
        Step probeStepA(JobRepository r, PlatformTransactionManager tm,
                        StepScopedProbe stepProbe, JobScopedProbe jobProbe) {
            return probeStep("probeStepA", r, tm, stepProbe, jobProbe);
        }

        @Bean
        Step probeStepB(JobRepository r, PlatformTransactionManager tm,
                        StepScopedProbe stepProbe, JobScopedProbe jobProbe) {
            return probeStep("probeStepB", r, tm, stepProbe, jobProbe);
        }

        @Bean
        Step probeStepC(JobRepository r, PlatformTransactionManager tm,
                        StepScopedProbe stepProbe, JobScopedProbe jobProbe) {
            return probeStep("probeStepC", r, tm, stepProbe, jobProbe);
        }

        private Step probeStep(String name, JobRepository r, PlatformTransactionManager tm,
                               StepScopedProbe stepProbe, JobScopedProbe jobProbe) {
            return new StepBuilder(name, r)
                    .tasklet((contribution, chunkContext) -> {
                        // 프록시가 실제로 대상 객체를 만드는 시점은 "이 호출"입니다.
                        log.debug("{} — stepProbe={}, jobProbe={}", name, stepProbe.stepName(), jobProbe.jobName());
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }
    }

    // ========================================================================
    // [9-10] 종합 — 승격 + 스코프로 세 Step 잇기
    //
    //   prepareStep --(completedCount, settleDate)--> settleStep --(feeTotal)--> reportStep
    // ========================================================================
    @Configuration
    static class Step09Job {

        private static final Logger log = LoggerFactory.getLogger(Step09Job.class);

        @Bean
        Job step09Job(JobRepository jobRepository,
                      Step prepareStep, Step s09SettleStep, Step s09ReportStep) {
            return new JobBuilder("step09Job", jobRepository)
                    .start(prepareStep)
                    .next(s09SettleStep)
                    .next(s09ReportStep)
                    .build();
        }

        // ── 승격 리스너 두 개 (올릴 키가 다르므로 따로 만듭니다)
        @Bean
        ExecutionContextPromotionListener preparePromotion() {
            ExecutionContextPromotionListener l = new ExecutionContextPromotionListener();
            l.setKeys(new String[]{"completedCount", "settleDate"});
            l.setStatuses(new String[]{"COMPLETED"});
            return l;
        }

        @Bean
        ExecutionContextPromotionListener settlePromotion() {
            ExecutionContextPromotionListener l = new ExecutionContextPromotionListener();
            l.setKeys(new String[]{"feeTotal"});
            l.setStatuses(new String[]{"COMPLETED"});
            return l;
        }

        // ── prepareStep : COUNT(*) 를 세어 Step Context 에 넣습니다
        @Bean
        Step prepareStep(JobRepository jobRepository, PlatformTransactionManager tm,
                         JdbcTemplate jdbcTemplate,
                         ExecutionContextPromotionListener preparePromotion) {
            return new StepBuilder("prepareStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        String date = (String) chunkContext.getStepContext()
                                .getJobParameters().get("date");
                        Long cnt = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND DATE(ordered_at)=?",
                                Long.class, date);
                        ExecutionContext ctx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        ctx.putLong("completedCount", cnt == null ? 0L : cnt);
                        ctx.putString("settleDate", date);
                        log.info("[prepare] date={}, COMPLETED 건수={}", date, cnt);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .listener(preparePromotion)
                    .build();
        }

        // ── settleStep : jobParameters 와 jobExecutionContext 를 둘 다 씁니다
        @Bean
        @StepScope
        JdbcPagingItemReader<Order> settleReader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date,
                @Value("#{jobExecutionContext['completedCount']}") Long expected) {

            log.debug("settleReader 생성 — date={}, 예상 건수={}", date, expected);

            MySqlPagingQueryProviderStub provider = new MySqlPagingQueryProviderStub();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status='COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKey("order_id");

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("settleReader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider.build());
            reader.setParameterValues(Map.of("date", date));
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        @Bean
        Step s09SettleStep(JobRepository jobRepository, PlatformTransactionManager tm,
                           JdbcPagingItemReader<Order> settleReader,
                           JdbcTemplate jdbcTemplate,
                           JdbcBatchItemWriter<Settlement> settlementWriter,
                           ExecutionContextPromotionListener settlePromotion) {

            // 수수료 합계를 누적해 Step Context 에 남깁니다.
            // 주의: 누적 상태는 "빈 필드"가 아니라 ExecutionContext 에 남겨야 재시작에 안전합니다.
            return new StepBuilder("settleStep", jobRepository)
                    .<Order, Settlement>chunk(1000, tm)
                    .reader(settleReader)
                    .processor((ItemProcessor<Order, Settlement>) order ->
                            toSettlement(order, feeRateOf(jdbcTemplate, order.customerId())))
                    .writer(chunk -> {
                        settlementWriter.write(chunk);
                        // (실전에서는 StepExecutionListener 로 분리하는 편이 깔끔합니다 — Step 12)
                    })
                    .listener(new FeeTotalListener())
                    .listener(settlePromotion)
                    .build();
        }

        // ── reportStep : 승격된 값만으로 리포트
        @Bean
        Step s09ReportStep(JobRepository jobRepository, PlatformTransactionManager tm,
                           org.springframework.batch.core.step.tasklet.Tasklet s09ReportTasklet) {
            return new StepBuilder("reportStep", jobRepository)
                    .tasklet(s09ReportTasklet, tm)
                    .build();
        }

        @Bean
        @StepScope
        org.springframework.batch.core.step.tasklet.Tasklet s09ReportTasklet(
                @Value("#{jobExecutionContext['settleDate']}") String settleDate,
                @Value("#{jobExecutionContext['completedCount']}") Long completedCount,
                @Value("#{jobExecutionContext['feeTotal']}") BigDecimal feeTotal) {
            return (contribution, chunkContext) -> {
                log.info("[report] {} 정산 {}건, 수수료 합계 = {}", settleDate, completedCount, feeTotal);
                return RepeatStatus.FINISHED;
            };
        }
    }

    /**
     * [9-10] 수수료 합계를 Step ExecutionContext 에 누적하는 리스너.
     * 값을 빈 필드가 아니라 컨텍스트에 두는 이유는 9-9 의 함정 그대로입니다.
     */
    static class FeeTotalListener implements org.springframework.batch.core.StepExecutionListener {

        @Override
        public org.springframework.batch.core.ExitStatus afterStep(
                org.springframework.batch.core.StepExecution stepExecution) {
            // 실제 합계는 Writer 쪽에서 누적하거나 SQL 로 재집계합니다.
            // 여기서는 컨텍스트에 키가 존재하도록 보장하는 역할만 합니다.
            ExecutionContext ctx = stepExecution.getExecutionContext();
            if (!ctx.containsKey("feeTotal")) {
                ctx.put("feeTotal", BigDecimal.ZERO);
            }
            return stepExecution.getExitStatus();
        }
    }

    // ========================================================================
    // 공통 헬퍼
    // ========================================================================

    static Settlement toSettlement(Order order, BigDecimal feeRate) {
        BigDecimal gross = order.amount();
        BigDecimal fee = gross.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
        return new Settlement(
                order.order_id(),
                order.customerId(),
                order.orderedAt().toLocalDate(),
                gross,
                feeRate,
                fee,
                gross.subtract(fee)
        );
    }

    private static final Map<Integer, BigDecimal> FEE_CACHE = new HashMap<>();

    static BigDecimal feeRateOf(JdbcTemplate jdbcTemplate, Integer customerId) {
        return FEE_CACHE.computeIfAbsent(customerId, id -> jdbcTemplate.queryForObject(
                "SELECT fee_rate FROM customers WHERE customer_id = ?", BigDecimal.class, id));
    }

    /**
     * MySqlPagingQueryProvider 설정을 짧게 쓰기 위한 얇은 래퍼입니다.
     * 실제 프로젝트에서는 org.springframework.batch.item.database.support.MySqlPagingQueryProvider 를
     * 직접 쓰면 됩니다.
     */
    static class MySqlPagingQueryProviderStub {
        private final org.springframework.batch.item.database.support.MySqlPagingQueryProvider delegate =
                new org.springframework.batch.item.database.support.MySqlPagingQueryProvider();

        void setSelectClause(String s) { delegate.setSelectClause(s); }
        void setFromClause(String s) { delegate.setFromClause(s); }
        void setWhereClause(String s) { delegate.setWhereClause(s); }

        void setSortKey(String key) {
            delegate.setSortKeys(Map.of(key, org.springframework.batch.item.database.Order.ASCENDING));
        }

        org.springframework.batch.item.database.support.MySqlPagingQueryProvider build() {
            return delegate;
        }
    }

    // ========================================================================
    // 본문 9-4 / 9-5 / 9-8 에서 쓴 조회 SQL 모음
    //   mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "..."
    // ========================================================================
    static final String CONTEXT_INSPECT_SQL = """
            -- [9-4] Job ExecutionContext 전문 (SHORT / SERIALIZED 둘 다)
            SELECT JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT
              FROM BATCH_JOB_EXECUTION_CONTEXT
             ORDER BY JOB_EXECUTION_ID DESC LIMIT 1;

            -- [9-4] 항상 이 형태로 보는 습관을 들이세요
            SELECT JOB_EXECUTION_ID, COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) AS ctx
              FROM BATCH_JOB_EXECUTION_CONTEXT
             ORDER BY JOB_EXECUTION_ID DESC LIMIT 3;

            -- [9-5] 실패 지점과 컨텍스트의 read.count 비교
            SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT, s.COMMIT_COUNT,
                   COALESCE(c.SERIALIZED_CONTEXT, c.SHORT_CONTEXT) AS ctx
              FROM BATCH_STEP_EXECUTION s
              JOIN BATCH_STEP_EXECUTION_CONTEXT c USING (STEP_EXECUTION_ID)
             ORDER BY s.STEP_EXECUTION_ID DESC LIMIT 2;

            -- [9-8] read.count 키가 남았는지 확인 (프록시 함정 점검)
            SELECT COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) LIKE '%read.count%' AS has_read_count
              FROM BATCH_STEP_EXECUTION_CONTEXT
             ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
            """;
}
