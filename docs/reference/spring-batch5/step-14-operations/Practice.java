package com.example.batch.step14;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 14 — 운영: 스케줄링 · 모니터링 · 최종 프로젝트
 *
 * 본문 14-1 ~ 14-8 의 모든 예제 + 종합 실습 Job 전체.
 * 이 코스에서 가장 긴 실습 파일입니다.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 이 스텝은 bootRun 이 아니라 bootJar + java -jar 로 실습하십시오.
 *
 *   종료 코드 확인이 실습의 절반인데, bootRun 은 Gradle 이 종료 코드를
 *   가려 버립니다.
 *
 *     ./gradlew clean bootJar
 *     java -jar build/libs/spring-batch5-lab-1.0.0.jar \
 *       --spring.batch.job.enabled=true \
 *       --spring.batch.job.name=dailySettlementJob \
 *       date=2025-03-01
 *     echo $?
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ [14-1] 먼저 application.yml 을 바꾸십시오.
 *
 *   spring:
 *     batch:
 *       job:
 *         enabled: false        # ← 이걸 안 바꾸면 이 파일의 Job 들이
 *                               #   부팅 때 우르르 돌아 실습이 뒤엉킵니다
 *     task:
 *       scheduling:
 *         pool:
 *           size: 5             # @Scheduled 기본 풀 크기 1 → 5
 *
 *   management:
 *     endpoints:
 *       web:
 *         exposure:
 *           include: health, metrics, prometheus
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);

    // =====================================================================
    // [14-2] @Scheduled — 가장 단순한 스케줄
    //
    // ⚠️ cron 이 새벽 2시라 실습 중에는 절대 돌지 않습니다.
    //    테스트하려면 runNow() 를 직접 호출하십시오.
    //
    //    cron 을 "0/10 * * * * *" 로 바꿔 두고 잊으면
    //    10초마다 정산이 돕니다. 실습 후 반드시 되돌리십시오.
    // =====================================================================

    // @Component
    public static class SettlementScheduler {

        private static final Logger log =
                LoggerFactory.getLogger(SettlementScheduler.class);

        private final JobLauncher jobLauncher;
        private final Job dailySettlementJob;
        private final JobExplorer jobExplorer;

        public SettlementScheduler(JobLauncher jobLauncher,
                                   Job dailySettlementJob,
                                   JobExplorer jobExplorer) {
            this.jobLauncher = jobLauncher;
            this.dailySettlementJob = dailySettlementJob;
            this.jobExplorer = jobExplorer;
        }

        @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
        public void runDailySettlement() throws Exception {
            runFor(LocalDate.now().minusDays(1));       // 어제치
        }

        /** 실습용 수동 실행. 스케줄을 기다리지 않고 바로 돌립니다. */
        public void runNow(LocalDate targetDate) throws Exception {
            runFor(targetDate);
        }

        private void runFor(LocalDate targetDate) throws Exception {
            // 2차 방어 — 이전 실행이 아직 도는 중이면 건너뜁니다.
            Set<JobExecution> running =
                    jobExplorer.findRunningJobExecutions("dailySettlementJob");
            if (!running.isEmpty()) {
                log.warn("이전 정산 배치가 아직 실행 중입니다. 이번 실행을 건너뜁니다. running={}",
                        running);
                return;
            }

            log.info("일일 정산 시작: date={}", targetDate);

            JobParameters params = new JobParametersBuilder()
                    // identifying — JOB_KEY 에 포함됩니다.
                    .addString("date", targetDate.toString())
                    // non-identifying — JOB_KEY 에 영향 없음.
                    // RunIdIncrementer 를 쓰면 중복 차단이 무력화되므로,
                    // 감사 추적용 값은 이렇게 false 로 넣습니다.
                    .addLong("launchedAt", System.currentTimeMillis(), false)
                    .toJobParameters();

            jobLauncher.run(dailySettlementJob, params);
        }
    }

    // =====================================================================
    // [14-5] 중복 실행 방지 — 3단 방어
    //
    // ★ 이 절이 이 스텝에서 가장 중요합니다.
    //    정산 배치가 두 번 돌면 그건 곧 돈입니다.
    // =====================================================================

    /**
     * 3차 방어 — DB 유니크 락.
     *
     * 1차(JobInstance 중복 차단)와 2차(RUNNING 확인)는 "확인 후 실행"
     * 구조라 경쟁 상태를 막지 못합니다. 확인과 실행 사이에 틈이 있습니다.
     *
     * DB 의 PK 제약에는 그 틈이 없습니다. 두 프로세스가 정확히 동시에
     * INSERT 해도 DB 가 원자적으로 하나만 통과시킵니다.
     */
    // @Component
    public static class JobLockService {

        private static final Logger log = LoggerFactory.getLogger(JobLockService.class);

        private final JdbcTemplate jdbcTemplate;

        public JobLockService(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        public static final String DDL = """
                CREATE TABLE IF NOT EXISTS batch_job_lock (
                  job_name    VARCHAR(100) NOT NULL,
                  target_date DATE         NOT NULL,
                  acquired_at DATETIME     NOT NULL,
                  holder      VARCHAR(100) NOT NULL,
                  PRIMARY KEY (job_name, target_date)   -- ← 방어의 실체
                ) ENGINE=InnoDB;
                """;

        public boolean tryAcquire(String jobName, LocalDate date) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO batch_job_lock
                          (job_name, target_date, acquired_at, holder)
                        VALUES (?, ?, NOW(), ?)
                        """, jobName, date, hostname());
                log.info("락 획득 성공: {}/{}", jobName, date);
                return true;
            } catch (DuplicateKeyException e) {
                log.warn("락 획득 실패 — 다른 인스턴스가 실행 중입니다. 종료합니다.");
                return false;
            }
        }

        public void release(String jobName, LocalDate date) {
            jdbcTemplate.update(
                    "DELETE FROM batch_job_lock WHERE job_name = ? AND target_date = ?",
                    jobName, date);
        }

        private String hostname() {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                return "unknown";
            }
        }
    }

    /**
     * 좀비 실행 정리기.
     *
     * ⚠️ 배치 서버가 kill -9 로 죽으면 BATCH_JOB_EXECUTION 에
     *    STATUS='STARTED', END_TIME=NULL 인 행이 영원히 남습니다.
     *    아무도 정리해 주지 않습니다.
     *
     *    그러면 2차 방어(findRunningJobExecutions)가 "아직 실행 중"으로
     *    판단해 **정산이 영원히 건너뛰어집니다.**
     *    중복을 막으려던 장치가 실행 자체를 막는 역설입니다.
     *
     * ⚠️ 실습 중에는 @PostConstruct 를 주석 처리해 두었습니다.
     *    연습문제 2 에서 좀비를 일부러 만들어야 하는데, 자동 정리가
     *    켜져 있으면 재현이 안 되기 때문입니다.
     */
    // @Component
    public static class ZombieCleaner {

        private static final Logger log = LoggerFactory.getLogger(ZombieCleaner.class);

        /**
         * 좀비 판정 기준 시간.
         *
         * ⚠️ 이 값의 판단이 가장 까다롭습니다.
         *    배치의 최대 실행 시간보다 넉넉히 잡아야 합니다.
         *    정상 실행 중인 배치를 좀비로 오인해 FAILED 로 만들면
         *    그게 훨씬 큰 사고입니다.
         */
        private static final int ZOMBIE_THRESHOLD_HOURS = 6;

        private final JdbcTemplate jdbcTemplate;

        public ZombieCleaner(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        // @PostConstruct      // ← 연습문제 2 를 풀 때는 주석 처리한 채로 두십시오
        public void cleanUpOnStartup() {
            List<Long> zombies = jdbcTemplate.queryForList("""
                    SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
                    WHERE STATUS IN ('STARTED','STARTING')
                      AND END_TIME IS NULL
                      AND START_TIME < NOW() - INTERVAL ? HOUR
                    """, Long.class, ZOMBIE_THRESHOLD_HOURS);

            for (Long id : zombies) {
                jdbcTemplate.update("""
                        UPDATE BATCH_JOB_EXECUTION
                           SET STATUS = 'FAILED', EXIT_CODE = 'FAILED',
                               END_TIME = NOW(), LAST_UPDATED = NOW(),
                               EXIT_MESSAGE = '좀비 실행 자동 정리 (기동 시 감지)'
                         WHERE JOB_EXECUTION_ID = ?
                        """, id);
                log.warn("좀비 실행을 정리했습니다: JOB_EXECUTION_ID={}", id);
            }
        }
    }

    // =====================================================================
    // [14-8] 종합 실습 — 일일 주문 정산 배치
    //
    // Step 01~13 의 모든 요소를 하나로 조립합니다.
    // 각 빈에 어느 스텝에서 온 요소인지 표시해 두었으니,
    // 코스를 복습하는 지도로 쓰십시오.
    //
    //   흐름:
    //     ① dailySettlementStep  (청크 500)
    //           ↓ ExitStatus
    //     ② SettlementDecider
    //        ├ NOTHING  → end()
    //        └ HAS_DATA → ③ reportStep (CSV 출력)
    // =====================================================================

    // @Configuration
    public static class DailySettlementJobConfig {

        // ── Job 조립 ─────────────────────────────────────────────────
        @Bean
        public Job dailySettlementJob(JobRepository jobRepository,
                                      Step dailySettlementStep,
                                      Step reportStep,
                                      SettlementDecider settlementDecider) {
            return new JobBuilder("dailySettlementJob", jobRepository)
                    .listener(new SettlementJobListener())          // Step 12
                    .start(dailySettlementStep)
                    .next(settlementDecider)                        // Step 10
                        .on("HAS_DATA").to(reportStep)
                    .from(settlementDecider)
                        .on("NOTHING").end()
                    .end()
                    .build();
        }

        // ── ① 정산 Step ──────────────────────────────────────────────
        @Bean
        public Step dailySettlementStep(JobRepository jobRepository,
                                        PlatformTransactionManager txManager,
                                        JdbcPagingItemReader<Order> dailyOrderReader,
                                        DataSource dataSource) {
            return new StepBuilder("dailySettlementStep", jobRepository)
                    .<Order, Settlement>chunk(500, txManager)       // Step 05
                    .reader(dailyOrderReader)                       // Step 06
                    .processor(new GradeFeeProcessor())             // Step 07
                    .writer(idempotentSettlementWriter(dataSource)) // Step 08
                    .faultTolerant()                                // Step 11
                    .skip(IllegalArgumentException.class)
                    .skipLimit(50)
                    .listener(new BadOrderSkipListener(dataSource)) // Step 12
                    .build();
        }

        /**
         * Reader — 이미 정산된 주문을 안티 조인으로 제외합니다.
         *
         * Step 11 연습문제 3 의 결론입니다. 쓰기에서 UNIQUE 충돌로
         * 터뜨리는 대신 애초에 읽지 않으면, 스캔 모드에 빠지지 않아
         * 재실행이 훨씬 빠릅니다. 그리고 이것이 중복 정산의
         * 최종 방어선 역할도 합니다.
         *
         * ⚠️ @StepScope 가 필수입니다 (Step 09).
         *    없으면 기동 시점에 jobParameters 가 없어 SpEL 이 터집니다.
         * ⚠️ 리턴 타입이 인터페이스가 아니라 구현체입니다 (Step 09).
         *    ItemReader<Order> 로 선언하면 스코프 프록시가 ItemStream 을
         *    잃어 재시작이 조용히 파손됩니다.
         */
        @Bean
        @StepScope
        public JdbcPagingItemReader<Order> dailyOrderReader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause(
                    "o.order_id, o.customer_id, o.amount, o.status, o.ordered_at");
            provider.setFromClause(
                    "FROM orders o LEFT JOIN settlement s ON s.order_id = o.order_id");
            provider.setWhereClause("""
                    WHERE o.status = 'COMPLETED'
                      AND DATE(o.ordered_at) = :date
                      AND s.order_id IS NULL
                    """);
            // ⚠️ 유니크한 정렬 키 (Step 06 의 함정).
            //    유니크하지 않으면 페이지 경계에서 데이터가 조용히 사라집니다.
            provider.setSortKeys(Map.of(
                    "o.order_id", org.springframework.batch.item.database.Order.ASCENDING));

            return new JdbcPagingItemReaderBuilder<Order>()
                    .name("dailyOrderReader")       // ExecutionContext 키 접두사 (Step 11)
                    .dataSource(dataSource)
                    .queryProvider(provider)
                    .parameterValues(Map.of("date", date))
                    .pageSize(500)
                    .rowMapper(new DataClassRowMapper<>(Order.class))
                    .build();
        }

        /**
         * Writer — 멱등 INSERT (Step 08).
         *
         * record 는 자바빈이 아니므로 beanMapped() 를 못 씁니다.
         * 람다로 직접 매핑합니다.
         */
        @Bean
        public JdbcBatchItemWriter<Settlement> idempotentSettlementWriter(
                DataSource dataSource) {
            return new JdbcBatchItemWriterBuilder<Settlement>()
                    .dataSource(dataSource)
                    .sql("""
                         INSERT INTO settlement
                           (order_id, customer_id, settle_date, gross_amount,
                            fee_rate, fee_amount, net_amount)
                         VALUES
                           (:orderId, :customerId, :settleDate, :grossAmount,
                            :feeRate, :feeAmount, :netAmount)
                         ON DUPLICATE KEY UPDATE
                           gross_amount = VALUES(gross_amount),
                           fee_amount   = VALUES(fee_amount),
                           net_amount   = VALUES(net_amount)
                         """)
                    .itemSqlParameterSourceProvider(s -> new MapSqlParameterSource()
                            .addValue("orderId", s.orderId())
                            .addValue("customerId", s.customerId())
                            .addValue("settleDate", s.settleDate())
                            .addValue("grossAmount", s.grossAmount())
                            .addValue("feeRate", s.feeRate())
                            .addValue("feeAmount", s.feeAmount())
                            .addValue("netAmount", s.netAmount()))
                    .build();
        }

        // ── ② 분기 Decider ───────────────────────────────────────────
        @Bean
        public SettlementDecider settlementDecider() {
            return new SettlementDecider();
        }

        // ── ③ 리포트 Step ────────────────────────────────────────────
        @Bean
        @StepScope
        public ReportTasklet reportTasklet(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {
            return new ReportTasklet(dataSource, date);
        }

        @Bean
        public Step reportStep(JobRepository jobRepository,
                               PlatformTransactionManager txManager,
                               ReportTasklet reportTasklet) {
            return new StepBuilder("reportStep", jobRepository)
                    .tasklet(reportTasklet, txManager)      // Step 04
                    .build();
        }
    }

    /**
     * 등급별 수수료 Processor (Step 07).
     *
     * ⚠️ 상태가 없습니다. Step 13 의 스레드 안전성 원칙입니다.
     */
    public static class GradeFeeProcessor implements ItemProcessor<Order, Settlement> {

        // customer_id % 4 → 등급 수수료율. 시드 규칙과 일치합니다.
        private static final BigDecimal[] FEE_RATES = {
                new BigDecimal("0.0350"),   // 0 → BRONZE
                new BigDecimal("0.0300"),   // 1 → SILVER
                new BigDecimal("0.0250"),   // 2 → GOLD
                new BigDecimal("0.0200")    // 3 → VIP
        };

        @Override
        public Settlement process(Order order) {
            if (order.amount().signum() < 0) {
                throw new IllegalArgumentException(
                        "정산 금액이 음수입니다: order_id=" + order.order_id());
            }
            BigDecimal feeRate = FEE_RATES[order.customerId() % 4];
            BigDecimal gross = order.amount();
            // 반올림 시점이 중요합니다 (Step 07). 곱한 뒤 즉시 2자리로 고정합니다.
            BigDecimal fee = gross.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(),
                    gross, feeRate, fee, gross.subtract(fee));
        }
    }

    /** 정산 건수에 따라 분기 (Step 10). */
    public static class SettlementDecider implements JobExecutionDecider {
        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution,
                                          StepExecution stepExecution) {
            long written = stepExecution == null ? 0 : stepExecution.getWriteCount();
            return new FlowExecutionStatus(written > 0 ? "HAS_DATA" : "NOTHING");
        }
    }

    /** 불량 주문 기록 (Step 12). 커밋 후에 호출되므로 롤백되지 않습니다. */
    public static class BadOrderSkipListener implements SkipListener<Order, Settlement> {

        private static final Logger log =
                LoggerFactory.getLogger(BadOrderSkipListener.class);

        private final JdbcTemplate jdbcTemplate;

        public BadOrderSkipListener(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public void onSkipInProcess(Order item, Throwable t) {
            log.warn("[SKIP] order_id={}, 사유={}", item.order_id(), t.getMessage());
            jdbcTemplate.update("""
                    INSERT INTO s14_bad_order (order_id, reason, occurred_at)
                    VALUES (?, ?, NOW())
                    """, item.order_id(), t.getMessage());
        }
    }

    /** Job 시작/종료 알림 (Step 12). */
    public static class SettlementJobListener implements JobExecutionListener {

        private static final Logger log =
                LoggerFactory.getLogger(SettlementJobListener.class);

        @Override
        public void beforeJob(JobExecution jobExecution) {
            log.info(">>> 일일 정산 시작. date={}",
                    jobExecution.getJobParameters().getString("date"));
        }

        @Override
        public void afterJob(JobExecution jobExecution) {
            long millis = Duration.between(
                    jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
            log.info(">>> 일일 정산 종료. status={}, 소요={}ms",
                    jobExecution.getStatus(), millis);

            // ⚠️ 상태 분기 없이 "완료" 알림을 보내면 실패해도 알림이 갑니다.
            if (jobExecution.getStatus() == BatchStatus.FAILED) {
                try {
                    log.error(">>> 정산 실패! 원인={}",
                            jobExecution.getAllFailureExceptions());
                } catch (Exception e) {
                    // afterJob 의 예외는 삼켜지므로 여기서 반드시 잡습니다.
                    log.error(">>> 알림 전송 실패", e);
                }
            }
        }
    }

    /**
     * 정산 요약을 CSV 로 내보냅니다 (Step 04 Tasklet + Step 08 파일 출력).
     *
     * ⚠️ output/ 디렉터리가 없으면 FileNotFoundException 이 납니다.
     *    아래에서 createDirectories 로 처리하지만, 권한 문제는
     *    직접 확인해야 합니다. mkdir output 을 먼저 해 두십시오.
     */
    public static class ReportTasklet
            implements org.springframework.batch.core.step.tasklet.Tasklet {

        private static final Logger log = LoggerFactory.getLogger(ReportTasklet.class);

        private final JdbcTemplate jdbcTemplate;
        private final String date;

        public ReportTasklet(DataSource dataSource, String date) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
            this.date = date;
        }

        @Override
        public RepeatStatus execute(org.springframework.batch.core.StepContribution contribution,
                                    org.springframework.batch.core.scope.context.ChunkContext ctx)
                throws Exception {

            Path dir = Path.of("output");
            Files.createDirectories(dir);
            Path file = dir.resolve("settlement-" + date + ".csv");

            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT order_id, customer_id, gross_amount, fee_rate,
                           fee_amount, net_amount
                    FROM settlement
                    WHERE settle_date = ?
                    ORDER BY order_id
                    """, date);

            try (BufferedWriter w = Files.newBufferedWriter(file)) {
                w.write("order_id,customer_id,gross_amount,fee_rate,fee_amount,net_amount");
                w.newLine();
                for (Map<String, Object> r : rows) {
                    w.write("%s,%s,%s,%s,%s,%s".formatted(
                            r.get("order_id"), r.get("customer_id"),
                            r.get("gross_amount"), r.get("fee_rate"),
                            r.get("fee_amount"), r.get("net_amount")));
                    w.newLine();
                }
            }

            // Tasklet 은 카운터를 자동으로 세지 않습니다 (Step 04).
            contribution.incrementWriteCount(rows.size());
            log.info("리포트 생성 완료: {} ({}줄)", file, rows.size() + 1);
            return RepeatStatus.FINISHED;
        }
    }

    // =====================================================================
    // [14-6] 운영 분석 SQL 모음
    //
    // 그대로 복사해 mysql 클라이언트에 붙이거나,
    // 모니터링 대시보드의 쿼리로 쓰십시오.
    // =====================================================================

    public static class OperationalQueries {

        public static final String RECENT_FAILURES = """
                SELECT ji.JOB_NAME, je.JOB_EXECUTION_ID AS exec_id, je.START_TIME,
                       je.STATUS, LEFT(je.EXIT_MESSAGE, 80) AS reason
                FROM BATCH_JOB_EXECUTION je
                JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
                WHERE je.STATUS = 'FAILED'
                ORDER BY je.START_TIME DESC
                LIMIT 5;
                """;

        public static final String DURATION_TREND = """
                SELECT DATE(je.START_TIME) AS d,
                       COUNT(*) AS runs,
                       ROUND(AVG(TIMESTAMPDIFF(SECOND, je.START_TIME, je.END_TIME)), 1) AS avg_sec,
                       MAX(TIMESTAMPDIFF(SECOND, je.START_TIME, je.END_TIME)) AS max_sec
                FROM BATCH_JOB_EXECUTION je
                JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
                WHERE ji.JOB_NAME = 'dailySettlementJob' AND je.END_TIME IS NOT NULL
                GROUP BY DATE(je.START_TIME)
                ORDER BY d DESC LIMIT 7;
                """;

        public static final String SLOWEST_STEPS = """
                SELECT se.STEP_NAME, COUNT(*) AS runs,
                       ROUND(AVG(TIMESTAMPDIFF(SECOND, se.START_TIME, se.END_TIME)), 1) AS avg_sec,
                       SUM(se.READ_COUNT) AS total_read,
                       SUM(se.WRITE_COUNT) AS total_write,
                       SUM(se.ROLLBACK_COUNT) AS rollbacks
                FROM BATCH_STEP_EXECUTION se
                WHERE se.END_TIME IS NOT NULL
                GROUP BY se.STEP_NAME
                ORDER BY avg_sec DESC;
                """;

        /**
         * 정합성 등식 검증 (Step 01).
         *   READ = WRITE + FILTER + SKIP
         * 빈 결과가 정상입니다.
         */
        public static final String INTEGRITY_CHECK = """
                SELECT se.STEP_EXECUTION_ID,
                       se.READ_COUNT, se.WRITE_COUNT, se.FILTER_COUNT,
                       se.READ_SKIP_COUNT + se.PROCESS_SKIP_COUNT
                         + se.WRITE_SKIP_COUNT AS skips,
                       se.READ_COUNT - se.WRITE_COUNT - se.FILTER_COUNT
                         - (se.READ_SKIP_COUNT + se.PROCESS_SKIP_COUNT
                            + se.WRITE_SKIP_COUNT) AS unexplained
                FROM BATCH_STEP_EXECUTION se
                WHERE se.STATUS = 'COMPLETED'
                HAVING unexplained <> 0;
                """;

        /**
         * ⚠️ 위 등식이 잡지 못하는 유실이 있습니다 (Step 13).
         *    프레임워크 카운터는 "프레임워크가 본 것"일 뿐입니다.
         *    반드시 업무 데이터 쪽에서도 검증하십시오. 이게 최종 방어선입니다.
         */
        public static final String BUSINESS_INTEGRITY_CHECK = """
                SELECT ? AS target_date,
                       (SELECT COUNT(*) FROM orders
                         WHERE status = 'COMPLETED' AND DATE(ordered_at) = ?) AS 대상,
                       (SELECT COUNT(*) FROM settlement
                         WHERE settle_date = ?)                               AS 정산;
                """;

        /** 좀비 실행 탐지 (14-5). */
        public static final String FIND_ZOMBIES = """
                SELECT je.JOB_EXECUTION_ID, ji.JOB_NAME, je.START_TIME, je.STATUS
                FROM BATCH_JOB_EXECUTION je
                JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
                WHERE je.STATUS IN ('STARTED','STARTING')
                  AND je.END_TIME IS NULL
                  AND je.START_TIME < NOW() - INTERVAL 6 HOUR;
                """;
    }

    // =====================================================================
    // 실습 준비 DDL
    // =====================================================================

    public static final String SETUP_DDL = """
            CREATE TABLE IF NOT EXISTS s14_bad_order (
              id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              order_id    BIGINT       NOT NULL,
              reason      VARCHAR(500) NOT NULL,
              occurred_at DATETIME     NOT NULL
            ) ENGINE=InnoDB;

            CREATE TABLE IF NOT EXISTS batch_job_lock (
              job_name    VARCHAR(100) NOT NULL,
              target_date DATE         NOT NULL,
              acquired_at DATETIME     NOT NULL,
              holder      VARCHAR(100) NOT NULL,
              PRIMARY KEY (job_name, target_date)
            ) ENGINE=InnoDB;
            """;
}
