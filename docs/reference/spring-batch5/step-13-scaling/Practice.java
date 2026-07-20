package com.example.batch.step13;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 13 — 병렬 처리와 확장 : 본문 13-0 ~ 13-11 의 모든 예제.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 실행 방법
 *
 *   ./gradlew bootRun -Pargs=--spring.batch.job.name=settlementSingleJob
 *
 * 바깥 클래스에는 @Configuration 이 없습니다. 아래 static class 중 지금 측정할
 * 것 하나만 @Configuration 주석을 풀고 나머지는 주석 처리한 채로 돌리십시오.
 * 전부 켜 두면 Job 빈이 우르르 등록되어 측정이 뒤섞입니다.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 매 실행 전에 반드시 RESET_SQL 을 돌리십시오.
 *
 * 이 스텝에서 가장 흔한 실습 실수가 이것을 빼먹는 것입니다. settlement 에
 * 이전 실행의 70,000행이 그대로 남아 있으면, 이번 실행이 69,213건만 썼어도
 * 최종 COUNT 는 70,000 으로 보입니다. UNIQUE(order_id) 가 중복을 흡수하기
 * 때문입니다. 즉 "누락을 발견하지 못하게" 됩니다 — 13-3 의 핵심이 통째로
 * 무의미해집니다.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Practice {

    /** 매 측정 전에 실행할 초기화 SQL. */
    public static final String RESET_SQL = """
            -- mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "
            TRUNCATE TABLE settlement;
            SET FOREIGN_KEY_CHECKS = 0;
            DELETE FROM BATCH_STEP_EXECUTION_CONTEXT; DELETE FROM BATCH_STEP_EXECUTION;
            DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;  DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
            DELETE FROM BATCH_JOB_EXECUTION;          DELETE FROM BATCH_JOB_INSTANCE;
            SET FOREIGN_KEY_CHECKS = 1;
            -- "
            """;

    /** 측정 후 검증 SQL. distinct_orders 가 70000 이 아니면 무언가 잘못된 것입니다. */
    public static final String VERIFY_SQL = """
            SELECT COUNT(*)                  AS rows_written,
                   COUNT(DISTINCT order_id)  AS distinct_orders,
                   SUM(net_amount)           AS total_net
            FROM settlement;
            """;

    // =====================================================================
    // 공통 — 모든 구성이 같은 Processor / Writer 를 씁니다.
    // 비교 측정이 목적이므로 Reader 이외의 변수는 고정해야 합니다.
    // =====================================================================

    /**
     * 등급별 수수료를 적용해 Order → Settlement 로 변환합니다.
     *
     * 상태가 없습니다(필드 없음). 이것이 이 스텝 전체의 전제입니다.
     * 연습문제 2 의 RunningTotalProcessor 는 일부러 이 원칙을 깨뜨립니다.
     */
    public static class SettlementProcessor implements ItemProcessor<Order, Settlement> {

        /** customer_id % 4 → 등급 수수료율. 시드 규칙과 일치시킵니다. */
        private static final BigDecimal[] FEE_RATES = {
                new BigDecimal("0.0350"),   // n % 4 == 0 → BRONZE
                new BigDecimal("0.0300"),   // 1 → SILVER
                new BigDecimal("0.0250"),   // 2 → GOLD
                new BigDecimal("0.0200")    // 3 → VIP
        };

        @Override
        public Settlement process(Order order) {
            BigDecimal feeRate = FEE_RATES[order.customerId() % 4];
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(fee);

            return new Settlement(
                    order.order_id(),
                    order.customerId(),
                    order.orderedAt().toLocalDate(),
                    gross, feeRate, fee, net);
        }
    }

    /**
     * 멱등 Writer — INSERT ... ON DUPLICATE KEY UPDATE.
     *
     * settlement.order_id 의 UNIQUE 제약 덕분에 같은 주문을 두 번 써도 행이
     * 늘어나지 않습니다. 재시작에 안전합니다.
     *
     * ⚠️ 그런데 13-3 에서 이 멱등성이 오히려 독이 됩니다.
     *    중복은 흡수하지만 "누락"은 전혀 감지하지 못하기 때문입니다.
     */
    public static JdbcBatchItemWriter<Settlement> buildSettlementWriter(DataSource dataSource) {
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
                // record 는 자바빈이 아니므로 beanMapped() 를 쓸 수 없습니다.
                // Step 08 에서 다룬 그 이유입니다. 람다로 직접 매핑합니다.
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

    // =====================================================================
    // [13-0] 기준선 — 단일 스레드. 62.4초.
    //
    // 다른 어떤 절보다 먼저 실행하십시오. 비교 대상 없이 "18.9초가 나왔다"는
    // 아무 의미가 없습니다. 여러분의 머신에서의 기준선을 먼저 확보하십시오.
    // =====================================================================

    // @Configuration
    public static class Baseline {

        @Bean
        public Job settlementSingleJob(JobRepository jobRepository, Step settlementSingleStep) {
            return new JobBuilder("settlementSingleJob", jobRepository)
                    .start(settlementSingleStep)
                    .build();
        }

        @Bean
        public Step settlementSingleStep(JobRepository jobRepository,
                                         PlatformTransactionManager txManager,
                                         DataSource dataSource) {
            return new StepBuilder("settlementSingleStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(orderCursorReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildSettlementWriter(dataSource))
                    .build();
        }

        /**
         * JdbcCursorItemReader — 커넥션을 하나 잡고 결과를 스트리밍합니다.
         *
         * 단일 스레드에서는 가장 빠른 선택입니다. 페이징처럼 매번 새 쿼리를
         * 날리지 않기 때문입니다.
         *
         * ⚠️ 그러나 이 Reader 는 스레드 안전하지 않습니다. 13-2 에서 바로
         *    이 Reader 를 멀티스레드 Step 에 그대로 넣습니다.
         */
        @Bean
        public JdbcCursorItemReader<Order> orderCursorReader(DataSource dataSource) {
            return new JdbcCursorItemReaderBuilder<Order>()
                    .name("orderCursorReader")
                    .dataSource(dataSource)
                    .fetchSize(1000)
                    .sql("""
                         SELECT order_id, customer_id, amount, status, ordered_at
                         FROM orders
                         WHERE status = 'COMPLETED'
                         ORDER BY order_id
                         """)
                    .rowMapper(new DataClassRowMapper<>(Order.class))
                    .build();
        }
    }

    // =====================================================================
    // [13-2] 멀티스레드 Step — .taskExecutor(...) 한 줄
    //
    // 18.9초. 3.3배 빨라집니다. 그리고 787건을 잃습니다.
    // =====================================================================

    // @Configuration
    public static class MultiThread {

        @Bean
        public Job settlementMultiThreadJob(JobRepository jobRepository,
                                            Step settlementMultiThreadStep) {
            return new JobBuilder("settlementMultiThreadJob", jobRepository)
                    .start(settlementMultiThreadStep)
                    .build();
        }

        @Bean
        public Step settlementMultiThreadStep(JobRepository jobRepository,
                                              PlatformTransactionManager txManager,
                                              DataSource dataSource,
                                              TaskExecutor batchTaskExecutor) {
            return new StepBuilder("settlementMultiThreadStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(new Baseline().orderCursorReader(dataSource))   // ← 공유됩니다!
                    .processor(new SettlementProcessor())
                    .writer(buildSettlementWriter(dataSource))
                    .taskExecutor(batchTaskExecutor)        // ← 이 한 줄이 전부입니다
                    .build();
        }

        /**
         * 스레드 4개.
         *
         * 커넥션 풀이 20 이므로 4개는 여유롭습니다. 13-9 에서 이 숫자를
         * 올렸을 때 무슨 일이 벌어지는지 측정합니다.
         */
        @Bean
        public TaskExecutor batchTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(4);
            executor.setThreadNamePrefix("batch-worker-");
            executor.initialize();
            return executor;
        }
    }

    // =====================================================================
    // [13-3] 함정 재현 — 직접 만든 Reader 로 원인을 눈에 보이게
    //
    // JdbcCursorItemReader 의 내부는 복잡해서 "왜 사라졌는지"가 잘 안 보입니다.
    // 같은 결함을 가진 최소 코드를 직접 만들어 원인을 드러냅니다.
    //
    // ⚠️ 이 클래스는 "일부러 틀린 코드"입니다. index++ 를 고치지 마십시오.
    //    틀린 것을 눈으로 보는 것이 이 절의 목적입니다.
    // =====================================================================

    // @Configuration
    public static class ThreadUnsafe {

        /**
         * 스레드 안전하지 않은 Reader.
         *
         * 결함은 딱 한 줄, `return orders.get(index++)` 입니다.
         *
         * index++ 는 원자적이지 않습니다. 세 단계로 나뉩니다.
         *   ① index 를 읽는다
         *   ② 1을 더한다
         *   ③ 다시 쓴다
         *
         * 스레드 A 와 B 가 동시에 ①을 실행하면 둘 다 같은 값(예: 500)을 읽고,
         * 둘 다 500번 아이템을 처리한 뒤 index 를 501로 만듭니다.
         *   → 500번 아이템은 두 번 처리되고 (UNIQUE 가 흡수해서 안 보임)
         *   → 501번이 되었어야 할 자리가 밀려 하나가 통째로 누락됩니다.
         *
         * 이것이 787건이 사라진 이유입니다. 그리고 스레드 스케줄링에 의존하므로
         * 매 실행마다 손실 건수가 다릅니다 (69213 / 68940 / 69551).
         */
        public static class NaiveOrderReader implements ItemReader<Order> {

            private final List<Order> orders;
            private int index = 0;          // ← 여러 스레드가 공유하는 가변 상태

            public NaiveOrderReader(List<Order> orders) {
                this.orders = orders;
            }

            @Override
            public Order read() {
                if (index >= orders.size()) {
                    return null;            // null = 더 읽을 것 없음
                }
                return orders.get(index++); // ← 원자적이지 않습니다
            }
        }

        @Bean
        public Job settlementNaiveJob(JobRepository jobRepository, Step settlementNaiveStep) {
            return new JobBuilder("settlementNaiveJob", jobRepository)
                    .start(settlementNaiveStep)
                    .build();
        }

        @Bean
        public Step settlementNaiveStep(JobRepository jobRepository,
                                        PlatformTransactionManager txManager,
                                        DataSource dataSource,
                                        TaskExecutor batchTaskExecutor) {
            List<Order> all = loadAllCompletedOrders(dataSource);
            return new StepBuilder("settlementNaiveStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(new NaiveOrderReader(all))
                    .processor(new SettlementProcessor())
                    .writer(buildSettlementWriter(dataSource))
                    .taskExecutor(batchTaskExecutor)
                    .build();
        }

        /** 70,000건을 메모리에 통째로 올립니다. 실습 전용입니다. */
        static List<Order> loadAllCompletedOrders(DataSource dataSource) {
            return new JdbcTemplate(dataSource).query("""
                    SELECT order_id, customer_id, amount, status, ordered_at
                    FROM orders WHERE status = 'COMPLETED' ORDER BY order_id
                    """, new DataClassRowMapper<>(Order.class));
        }
    }

    // =====================================================================
    // [13-4] SynchronizedItemStreamReader — 해법과 그 천장
    //
    // 21.6초. 정확해지지만 단일 스레드 대비 2.9배에서 멈춥니다.
    // 읽기가 직렬화되기 때문입니다.
    // =====================================================================

    // @Configuration
    public static class Synchronized {

        @Bean
        public Job settlementSyncJob(JobRepository jobRepository, Step settlementSyncStep) {
            return new JobBuilder("settlementSyncJob", jobRepository)
                    .start(settlementSyncStep)
                    .build();
        }

        @Bean
        public Step settlementSyncStep(JobRepository jobRepository,
                                       PlatformTransactionManager txManager,
                                       DataSource dataSource,
                                       TaskExecutor batchTaskExecutor) {
            return new StepBuilder("settlementSyncStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(synchronizedOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildSettlementWriter(dataSource))
                    .taskExecutor(batchTaskExecutor)
                    .build();
        }

        /**
         * read() 전체를 락으로 감쌉니다.
         *
         * 장점: 어떤 ItemStreamReader 에도 적용됩니다. 범용 해법입니다.
         * 한계: 읽기가 완전히 직렬화됩니다. 스레드를 8개, 16개로 늘려도
         *       읽는 속도는 그대로라 3~4배가 천장입니다.
         *
         * 이 천장을 넘으려면 "읽기 자체를 나눠야" 합니다 → 파티셔닝(13-7).
         */
        @Bean
        public SynchronizedItemStreamReader<Order> synchronizedOrderReader(DataSource dataSource) {
            SynchronizedItemStreamReader<Order> reader = new SynchronizedItemStreamReader<>();
            reader.setDelegate(new Baseline().orderCursorReader(dataSource));
            return reader;
        }
    }

    // =====================================================================
    // [13-5] saveState(false) — 속도를 위해 재시작을 포기한다
    //
    // 멀티스레드 Step 에서는 사실상 필수입니다. 여러 스레드가 하나의
    // ExecutionContext 에 "어디까지 읽었는지"를 동시에 쓰면 그 값 자체가
    // 신뢰할 수 없게 되기 때문입니다.
    //
    // 저장된 값이 틀리면 재시작이 "틀린 지점"에서 재개됩니다. 차라리
    // 저장하지 않는 편이 정직합니다.
    // =====================================================================

    // @Configuration
    public static class NoSaveState {

        @Bean
        public JdbcCursorItemReader<Order> noStateOrderReader(DataSource dataSource) {
            return new JdbcCursorItemReaderBuilder<Order>()
                    .name("noStateOrderReader")
                    .dataSource(dataSource)
                    .fetchSize(1000)
                    .saveState(false)       // ← 재시작 포기 선언
                    .sql("""
                         SELECT order_id, customer_id, amount, status, ordered_at
                         FROM orders WHERE status = 'COMPLETED' ORDER BY order_id
                         """)
                    .rowMapper(new DataClassRowMapper<>(Order.class))
                    .build();
        }
    }

    // =====================================================================
    // [13-6] 병렬 Step (split) — 서로 다른 일을 동시에
    //
    // 63.0초. 1.1배밖에 안 빨라집니다.
    //
    // split 은 "같은 일을 나눠서" 하는 게 아니라 "다른 일을 동시에" 하는
    // 것입니다. 그래서 가장 긴 Step 보다 빨라질 수 없습니다.
    // 정산(62초) + 통계(6초) 를 병렬로 돌려도 62초입니다.
    // =====================================================================

    // @Configuration
    public static class ParallelSteps {

        @Bean
        public Job settlementSplitJob(JobRepository jobRepository,
                                      Step settlementSingleStep,
                                      Step dailyStatsStep) {
            return new JobBuilder("settlementSplitJob", jobRepository)
                    .start(new org.springframework.batch.core.job.builder.FlowBuilder<
                                    org.springframework.batch.core.job.flow.Flow>("splitFlow")
                            .split(new SimpleAsyncTaskExecutor("split-"))
                            .add(
                                    new org.springframework.batch.core.job.builder.FlowBuilder<
                                            org.springframework.batch.core.job.flow.Flow>("f1")
                                            .start(settlementSingleStep).build(),
                                    new org.springframework.batch.core.job.builder.FlowBuilder<
                                            org.springframework.batch.core.job.flow.Flow>("f2")
                                            .start(dailyStatsStep).build())
                            .build())
                    .end()
                    .build();
        }

        /** 정산과 무관한 별도 집계. 약 6초 걸립니다. */
        @Bean
        public Step dailyStatsStep(JobRepository jobRepository,
                                   PlatformTransactionManager txManager,
                                   DataSource dataSource) {
            return new StepBuilder("dailyStatsStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        new JdbcTemplate(dataSource).query("""
                                SELECT DATE(ordered_at) d, COUNT(*) c, SUM(amount) s
                                FROM orders WHERE status = 'COMPLETED'
                                GROUP BY DATE(ordered_at) ORDER BY d
                                """, rs -> {
                            // 실습에서는 집계 결과를 소비만 합니다.
                        });
                        return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =====================================================================
    // [13-7] [13-8] 파티셔닝 — 데이터를 나누고 Step 을 복제한다
    //
    // 11.2초. 5.6배. 그리고 재시작까지 됩니다.
    //
    // 핵심은 "공유 상태가 없다"는 것입니다. 파티션마다 자기만의 Reader 를
    // 새로 만들어 쓰기 때문에 13-3 의 index++ 문제가 원천적으로 없습니다.
    // =====================================================================

    /**
     * order_id 범위를 gridSize 조각으로 나눕니다.
     *
     * 우리 데이터는 MIN=1, MAX=100000 이고 COMPLETED 가 order_id % 10 <= 6
     * 규칙으로 균등하게 깔려 있어, gridSize=8 이면 파티션당 정확히 8,750건이
     * 됩니다. 70000 / 8 = 8750.
     *
     * ⚠️ 이 균등함은 실습 데이터가 결정론적이라 얻어진 행운입니다.
     *    실제 데이터에서는 반드시 스큐가 생깁니다 — 연습문제 3 참고.
     */
    public static class OrderIdRangePartitioner implements Partitioner {

        private final JdbcTemplate jdbcTemplate;

        public OrderIdRangePartitioner(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public Map<String, ExecutionContext> partition(int gridSize) {
            Long min = jdbcTemplate.queryForObject(
                    "SELECT MIN(order_id) FROM orders WHERE status = 'COMPLETED'", Long.class);
            Long max = jdbcTemplate.queryForObject(
                    "SELECT MAX(order_id) FROM orders WHERE status = 'COMPLETED'", Long.class);

            long targetSize = (max - min) / gridSize + 1;

            Map<String, ExecutionContext> result = new LinkedHashMap<>();
            long start = min;
            for (int i = 0; i < gridSize; i++) {
                ExecutionContext ctx = new ExecutionContext();
                long end = Math.min(start + targetSize - 1, max);
                ctx.putLong("minId", start);
                ctx.putLong("maxId", end);
                ctx.putString("partitionName", "partition" + i);
                result.put("partition" + i, ctx);
                start = end + 1;
            }
            return result;
        }
    }

    // @Configuration
    public static class Partitioning {

        @Bean
        public Job settlementPartitionJob(JobRepository jobRepository,
                                          Step settlementPartitionMasterStep) {
            return new JobBuilder("settlementPartitionJob", jobRepository)
                    .start(settlementPartitionMasterStep)
                    .build();
        }

        @Bean
        public Step settlementPartitionMasterStep(JobRepository jobRepository,
                                                  Step settlementWorkerStep,
                                                  DataSource dataSource,
                                                  TaskExecutor partitionTaskExecutor) {
            return new StepBuilder("settlementPartitionMasterStep", jobRepository)
                    .partitioner("settlementWorkerStep", new OrderIdRangePartitioner(dataSource))
                    .step(settlementWorkerStep)
                    .partitionHandler(partitionHandler(settlementWorkerStep, partitionTaskExecutor))
                    .build();
        }

        @Bean
        public TaskExecutorPartitionHandler partitionHandler(Step settlementWorkerStep,
                                                             TaskExecutor partitionTaskExecutor) {
            TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
            handler.setStep(settlementWorkerStep);
            handler.setTaskExecutor(partitionTaskExecutor);
            // ⚠️ gridSize 는 "스레드 수"가 아니라 "조각 수"입니다.
            //    동시에 몇 개가 도는지는 partitionTaskExecutor 의 풀 크기가 정합니다.
            handler.setGridSize(8);
            return handler;
        }

        @Bean
        public TaskExecutor partitionTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(8);
            executor.setMaxPoolSize(8);
            executor.setThreadNamePrefix("partition-");
            executor.initialize();
            return executor;
        }

        /**
         * [13-8] 늦은 바인딩.
         *
         * @StepScope 가 없으면 이 메서드는 기동 시점에 딱 한 번 호출되고,
         * 그때는 stepExecutionContext 가 존재하지 않습니다.
         *
         * 연습문제 4: 아래 @StepScope 한 줄만 주석 처리하고 돌려 보십시오.
         * 어떤 예외가 "어느 시점에" 나는지가 문제의 핵심입니다.
         */
        @Bean
        @StepScope
        public JdbcPagingItemReader<Order> partitionedOrderReader(
                DataSource dataSource,
                @Value("#{stepExecutionContext['minId']}") Long minId,
                @Value("#{stepExecutionContext['maxId']}") Long maxId) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause(
                    "WHERE status = 'COMPLETED' AND order_id BETWEEN :minId AND :maxId");
            // 정렬 키는 페이징 리더의 생명입니다 — Step 06 참고.
            // 주의: Spring Batch 의 정렬 방향 enum 이름도 Order 라서 우리 도메인
            // Order 와 충돌합니다. import 하지 말고 완전한 이름으로 씁니다.
            provider.setSortKeys(Map.of(
                    "order_id", org.springframework.batch.item.database.Order.ASCENDING));

            return new JdbcPagingItemReaderBuilder<Order>()
                    .name("partitionedOrderReader")
                    .dataSource(dataSource)
                    .queryProvider(provider)
                    .parameterValues(Map.of("minId", minId, "maxId", maxId))
                    .pageSize(1000)
                    .rowMapper(new DataClassRowMapper<>(Order.class))
                    .build();
        }

        @Bean
        public Step settlementWorkerStep(JobRepository jobRepository,
                                         PlatformTransactionManager txManager,
                                         JdbcPagingItemReader<Order> partitionedOrderReader,
                                         DataSource dataSource) {
            return new StepBuilder("settlementWorkerStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(partitionedOrderReader)
                    .processor(new SettlementProcessor())
                    .writer(buildSettlementWriter(dataSource))
                    .build();
        }
    }

    // =====================================================================
    // [13-9] gridSize 튜닝 — 스레드 풀과 커넥션 풀
    //
    // GRID_SIZES 를 돌며 같은 Job 을 반복 실행하는 러너입니다.
    // 전체가 약 2분 30초 걸리고, 끝나면 13-11 의 비교표와 같은 형태로
    // 콘솔에 요약을 찍습니다.
    //
    // 실행:
    //   ./gradlew bootRun -Pargs=--spring.batch.job.name=NONE,--grid.matrix=true
    // =====================================================================

    public static final int[] GRID_SIZES = {1, 4, 8, 16, 32};

    /**
     * 측정 결과 요약 출력.
     *
     * 기대 결과(13-11 과 동일한 경향):
     *   grid  1 →  62.4초   1.00배
     *   grid  4 →  17.2초   3.6배
     *   grid  8 →  11.2초   5.6배   ← 최적
     *   grid 16 →  12.9초   4.8배   ← 역전 시작 (커넥션 풀 20 포화)
     *   grid 32 →  24.6초   2.5배   ← 대기 지옥
     *
     * 병렬도는 단조 증가가 아닙니다. 최적점이 있습니다.
     */
    public static void printMatrix(Map<Integer, Long> elapsedMillisByGrid) {
        long baseline = elapsedMillisByGrid.getOrDefault(1, 62_400L);
        System.out.println("| gridSize | 소요 | 배수 |");
        System.out.println("|---|---|---|");
        for (int grid : GRID_SIZES) {
            Long ms = elapsedMillisByGrid.get(grid);
            if (ms == null) continue;
            System.out.printf("| %d | %.1f초 | %.2f배 |%n",
                    grid, ms / 1000.0, baseline / (double) ms);
        }
    }

    // =====================================================================
    // [13-10] 원격 청킹과 원격 파티셔닝 — 참고용 스케치
    //
    // ⚠️ 이 클래스는 컴파일은 되지만 실행되지 않습니다.
    //    RabbitMQ 와 spring-batch-integration 의존성이 없기 때문입니다.
    //    원격 구성의 뼈대를 읽기 위한 참고용입니다.
    //
    // 실행하려면 먼저 브로커를 띄우고 build.gradle 에 추가해야 합니다.
    //   implementation 'org.springframework.batch:spring-batch-integration'
    //   implementation 'org.springframework.boot:spring-boot-starter-amqp'
    // =====================================================================

    /*
    // @Configuration
    public static class RemoteSketch {

        // 원격 청킹 — 마스터가 "읽고", 워커가 "처리하고 쓴다".
        //   읽기는 여전히 마스터 혼자 하므로 읽기가 병목이면 효과 없음.
        //   processor 가 무거울 때(외부 API 호출 등) 유효합니다.
        //
        // 원격 파티셔닝 — 마스터가 "범위만 나눠 주고", 워커가 "읽고 처리하고 쓴다".
        //   읽기까지 분산되므로 로컬 파티셔닝의 자연스러운 확장입니다.
        //   대부분의 경우 원격 청킹보다 이쪽이 정답입니다.

        @Bean
        public MessageChannelPartitionHandler remotePartitionHandler(...) {
            MessageChannelPartitionHandler handler = new MessageChannelPartitionHandler();
            handler.setStepName("settlementWorkerStep");
            handler.setGridSize(8);
            handler.setMessagingOperations(messagingTemplate);
            return handler;
        }
    }
    */

    // =====================================================================
    // [참고] 이 스텝에서 쓴 검증 SQL 모음
    //
    // -- 누락 확인 (핵심). 70000 이 아니면 스레드 안전성 문제입니다.
    // SELECT COUNT(*) rows_written, COUNT(DISTINCT order_id) distinct_orders,
    //        SUM(net_amount) total_net FROM settlement;
    //
    // -- 메타데이터는 뭐라고 하는가 (70000/70000 이라고 거짓말합니다)
    // SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT
    // FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
    //
    // -- 파티션별 처리 건수 (스큐 확인)
    // SELECT STEP_NAME, READ_COUNT, WRITE_COUNT
    // FROM BATCH_STEP_EXECUTION
    // WHERE STEP_NAME LIKE 'settlementWorkerStep:partition%'
    // ORDER BY STEP_NAME;
    //
    // -- 어느 주문이 누락됐나
    // SELECT o.order_id FROM orders o
    // LEFT JOIN settlement s ON s.order_id = o.order_id
    // WHERE o.status = 'COMPLETED' AND s.order_id IS NULL
    // LIMIT 20;
    //
    // -- 커넥션 풀 상태 (logging.level.com.zaxxer.hikari=DEBUG 필요)
    // -- HikariPool - Pool stats (total=20, active=16, idle=4, waiting=12)
    // =====================================================================
}
