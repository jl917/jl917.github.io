package com.example.batch.step05;

/*
 * ============================================================================
 * Step 05 — 청크 지향 처리 / 실습 코드
 * ============================================================================
 *
 * 본문 5-2 ~ 5-9 의 모든 예제를 절 번호 주석과 함께 담았습니다.
 *
 * [실행 전 초기화]
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
 *   SET FOREIGN_KEY_CHECKS = 0;
 *   DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
 *   DELETE FROM BATCH_STEP_EXECUTION;
 *   DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
 *   DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
 *   DELETE FROM BATCH_JOB_EXECUTION;
 *   DELETE FROM BATCH_JOB_INSTANCE;
 *   SET FOREIGN_KEY_CHECKS = 1;
 *   TRUNCATE TABLE settlement;
 *   SQL
 *
 * [실행]
 *   ./gradlew bootRun --args='--spring.batch.job.name=settlementChunkJob --batch.chunk-size=1000'
 *
 *   ⚠️ --spring.batch.job.name 을 반드시 지정하세요.
 *      지정하지 않으면 Boot 3.2 가 이 파일의 Job 을 전부 순차 실행합니다.
 *
 * [5-6 실측]
 *   for SIZE in 10 100 1000 10000; do
 *     mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
 *     ./gradlew bootRun --args="--spring.batch.job.name=settlementChunkJob --batch.chunk-size=$SIZE"
 *   done
 * ============================================================================
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);

    // ========================================================================
    // 도메인 — com.example.batch.domain 의 record 를 그대로 옮겨 놓은 것입니다.
    // 실제 프로젝트에서는 domain 패키지의 것을 import 해서 쓰세요.
    // ========================================================================

    public record Order(
            Long order_id,
            Integer customerId,
            BigDecimal amount,
            String status,
            LocalDateTime orderedAt
    ) {}

    public record Settlement(
            Long orderId,
            Integer customerId,
            LocalDate settleDate,
            BigDecimal grossAmount,
            BigDecimal feeRate,
            BigDecimal feeAmount,
            BigDecimal netAmount
    ) {}

    // ========================================================================
    // [5-3] 청크 Step 정의 — Spring Batch 5.1.1 시그니처
    // ========================================================================

    /**
     * 청크 크기는 하드코딩하지 않고 설정으로 뺍니다 (본문 5-9 의 실무 팁).
     * --batch.chunk-size=100 처럼 커맨드라인으로 바꿀 수 있습니다.
     */
    @Value("${batch.chunk-size:1000}")
    private int chunkSize;

    /** 5-5 의 트랜잭션 경계 관찰용. --slow=true 일 때만 켜집니다. */
    @Value("${slow:false}")
    private boolean slow;

    @Bean
    public Step settlementStep(JobRepository jobRepository,
                               PlatformTransactionManager txManager,
                               ItemReader<Order> orderReader,
                               ItemProcessor<Order, Settlement> settlementProcessor,
                               ItemWriter<Settlement> settlementWriter) {

        log.info("settlementStep 생성 — chunkSize={}, slow={}", chunkSize, slow);

        return new StepBuilder("settlementStep", jobRepository)
                // ↓ 5.x 의 핵심. 트랜잭션 매니저가 인자입니다.
                //   4.x 의 .chunk(1000) 만 쓰면 런타임에
                //   IllegalStateException: A transaction manager must be provided
                .<Order, Settlement>chunk(chunkSize, txManager)
                .reader(orderReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    @Bean
    public Job settlementChunkJob(JobRepository jobRepository, Step settlementStep) {
        return new JobBuilder("settlementChunkJob", jobRepository)
                .start(settlementStep)
                .build();
    }

    /**
     * [5-3] CompletionPolicy 오버로드 — 참고용.
     * "1,000건을 모으거나 정책이 끝났다고 하면 커밋".
     * 아이템 크기가 들쭉날쭉해서 건수로 메모리를 예측할 수 없을 때 씁니다.
     */
    @Bean
    public Step settlementPolicyStep(JobRepository jobRepository,
                                     PlatformTransactionManager txManager,
                                     ItemReader<Order> orderReader,
                                     ItemProcessor<Order, Settlement> settlementProcessor,
                                     ItemWriter<Settlement> settlementWriter) {
        return new StepBuilder("settlementPolicyStep", jobRepository)
                .<Order, Settlement>chunk(new SimpleCompletionPolicy(chunkSize), txManager)
                .reader(orderReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    // ========================================================================
    // [5-4] Reader — JdbcCursorItemReader
    //   fetchSize 를 청크 크기와 같게 맞춥니다 (5-9 의 팁).
    //   커서 리더의 한계(커넥션 점유·멀티스레드 불가)는 Step 06 에서 다룹니다.
    // ========================================================================

    @Bean
    public JdbcCursorItemReader<Order> orderReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<Order>()
                .name("orderReader")
                .dataSource(dataSource)
                .sql("""
                     SELECT order_id, customer_id, amount, status, ordered_at
                     FROM orders
                     WHERE status = 'COMPLETED'
                     ORDER BY order_id
                     """)
                .fetchSize(chunkSize)
                // record 는 자바빈이 아니므로 BeanPropertyRowMapper 가 아니라
                // DataClassRowMapper 를 씁니다. 자세한 이유는 Step 06 에서.
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    // ========================================================================
    // [5-4] Processor — 수수료율 캐시를 써서 아이템당 DB 조회를 없앱니다.
    //
    //   이 캐시가 없으면 아이템마다 SELECT 가 나가고, 70,000번의 왕복이
    //   전체 시간을 지배해 5-6 의 U자 곡선이 아예 안 보입니다.
    // ========================================================================

    @Bean
    public Map<Integer, BigDecimal> feeRateCache(JdbcTemplate jdbc) {
        Map<Integer, BigDecimal> cache = new HashMap<>();
        jdbc.query("SELECT customer_id, fee_rate FROM customers", rs -> {
            cache.put(rs.getInt("customer_id"), rs.getBigDecimal("fee_rate"));
        });
        log.info("수수료율 캐시 로드 완료 — {}건", cache.size());   // 1000건
        return cache;
    }

    static class SettlementProcessor implements ItemProcessor<Order, Settlement> {

        private final Map<Integer, BigDecimal> feeRates;

        SettlementProcessor(Map<Integer, BigDecimal> feeRates) {
            this.feeRates = feeRates;
        }

        @Override
        public Settlement process(Order item) {
            // ⚠️ 여기서 "이번 청크의 합계" 같은 걸 필드에 누적하면 안 됩니다.
            //    processor 는 아이템 하나만 봅니다 (본문 5-2 의 함정).
            BigDecimal rate = feeRates.get(item.customerId());
            BigDecimal gross = item.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(fee);

            return new Settlement(
                    item.order_id(),
                    item.customerId(),
                    item.orderedAt().toLocalDate(),
                    gross,
                    rate,
                    fee,
                    net
            );
        }
    }

    @Bean
    public ItemProcessor<Order, Settlement> settlementProcessor(Map<Integer, BigDecimal> feeRateCache) {
        return new SettlementProcessor(feeRateCache);
    }

    // ========================================================================
    // [5-4] Writer — JdbcBatchItemWriter
    //
    //   ⚠️ record 에는 BeanPropertyItemSqlParameterSourceProvider 가 안 됩니다.
    //      (record 의 접근자는 getOrderId() 가 아니라 orderId() 라서)
    //      그래서 람다로 MapSqlParameterSource 를 직접 만듭니다.
    //      Step 08 에서 정면으로 다룹니다.
    // ========================================================================

    @Bean
    public JdbcBatchItemWriter<Settlement> settlementWriter(DataSource dataSource) {
        JdbcBatchItemWriter<Settlement> writer = new JdbcBatchItemWriterBuilder<Settlement>()
                .dataSource(dataSource)
                .sql("""
                     INSERT INTO settlement
                       (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
                     VALUES
                       (:orderId, :customerId, :settleDate, :grossAmount, :feeRate, :feeAmount, :netAmount)
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
        writer.afterPropertiesSet();

        if (!slow) {
            return writer;
        }
        // [5-5] --slow=true 일 때만: 청크당 200ms 쉬어서 트랜잭션 경계를
        //       다른 터미널에서 관찰할 수 있게 합니다.
        //       ⚠️ 이 플래그를 켠 채로 5-6 의 실측을 하면 안 됩니다.
        return slowWriter(writer);
    }

    /** [5-5] 트랜잭션 경계 관찰용 래퍼. */
    static JdbcBatchItemWriter<Settlement> slowWriter(JdbcBatchItemWriter<Settlement> delegate) {
        return new JdbcBatchItemWriter<>() {
            @Override
            public void write(Chunk<? extends Settlement> chunk) throws Exception {
                delegate.write(chunk);
                Thread.sleep(200);   // 커밋 직전에 붙잡아 둡니다
            }
        };
    }

    // ========================================================================
    // [5-7] 롤백 단위가 청크임을 확인하는 Job
    //
    //   25,501번째 아이템에서 예외를 던집니다.
    //   청크 1,000 이면 → 25번째 청크까지 커밋 → settlement 25,000행.
    //   25,500 이 아니라 25,000 입니다. 롤백 단위가 아이템이 아니라 청크라서.
    // ========================================================================

    static class ExplodingProcessor implements ItemProcessor<Order, Settlement> {

        private final AtomicInteger seq = new AtomicInteger();
        private final ItemProcessor<Order, Settlement> delegate;
        private final int failAt;

        ExplodingProcessor(ItemProcessor<Order, Settlement> delegate, int failAt) {
            this.delegate = delegate;
            this.failAt = failAt;
        }

        @Override
        public Settlement process(Order item) throws Exception {
            int n = seq.incrementAndGet();
            if (n == failAt) {
                throw new IllegalStateException(
                        "의도적 실패: " + n + "번째 아이템 (order_id=" + item.order_id() + ")");
            }
            return delegate.process(item);
        }
        // ⚠️ AtomicInteger 카운터가 정확한 것은 이 Step 이 단일 스레드이기 때문입니다.
        //    Step 13 의 멀티스레드 Step 에 그대로 가져가면 25,501번째가 매번 달라집니다.
    }

    @Value("${batch.fail-at:25501}")
    private int failAt;

    @Bean
    public Step settlementFailStep(JobRepository jobRepository,
                                   PlatformTransactionManager txManager,
                                   ItemReader<Order> orderReader,
                                   ItemProcessor<Order, Settlement> settlementProcessor,
                                   ItemWriter<Settlement> settlementWriter) {
        return new StepBuilder("settlementFailStep", jobRepository)
                .<Order, Settlement>chunk(chunkSize, txManager)
                .reader(orderReader)
                .processor(new ExplodingProcessor(settlementProcessor, failAt))
                .writer(settlementWriter)
                .build();
    }

    @Bean
    public Job settlementFailJob(JobRepository jobRepository, Step settlementFailStep) {
        return new JobBuilder("settlementFailJob", jobRepository)
                .start(settlementFailStep)
                .build();
    }

    // ========================================================================
    // [5-8] Chunk<T> API — 5.0 부터 write(List) 가 write(Chunk) 로 바뀌었습니다.
    //
    //   ⚠️ 이 writer 는 실제 INSERT 를 하지 않고 로그만 남깁니다.
    //      Chunk API 관찰용이므로, 이 writer 를 쓰는 Job 을 돌리면
    //      settlement 가 비어 있는 게 정상입니다.
    // ========================================================================

    static class SettlementLogWriter implements ItemWriter<Settlement> {

        private static final Logger log = LoggerFactory.getLogger(SettlementLogWriter.class);

        @Override   // ← @Override 를 반드시 붙이세요. 4.x 의 write(List) 를
                    //   남겨 두면 컴파일러가 대신 잡아 줍니다 (본문 5-8 의 함정).
        public void write(Chunk<? extends Settlement> chunk) {

            // (1) Chunk 는 Iterable 이므로 향상된 for 문이 그대로 됩니다
            BigDecimal sum = BigDecimal.ZERO;
            for (Settlement s : chunk) {
                sum = sum.add(s.netAmount());
            }

            // (2) List 가 필요하면 getItems()
            List<? extends Settlement> items = chunk.getItems();

            // (3) size() / isEmpty()
            if (chunk.isEmpty()) {
                log.debug("빈 청크 — 마지막 사이클입니다");
                return;
            }

            log.debug("청크 {}건, 첫 주문 {}, 순액 합계 {}",
                    chunk.size(), items.get(0).orderId(), sum);
        }
    }

    @Bean
    public Step settlementLogStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager,
                                  ItemReader<Order> orderReader,
                                  ItemProcessor<Order, Settlement> settlementProcessor) {
        return new StepBuilder("settlementLogStep", jobRepository)
                .<Order, Settlement>chunk(chunkSize, txManager)
                .reader(orderReader)
                .processor(settlementProcessor)
                .writer(new SettlementLogWriter())
                .build();
    }

    @Bean
    public Job settlementLogJob(JobRepository jobRepository, Step settlementLogStep) {
        return new JobBuilder("settlementLogJob", jobRepository)
                .start(settlementLogStep)
                .build();
    }

    // ========================================================================
    // [5-8] Chunk.of() — 테스트에서 Chunk 를 손쉽게 만드는 정적 팩터리
    //
    //   실제 테스트 클래스는 src/test 에 두지만, API 형태를 보여 주기 위해
    //   여기에 예시로 남깁니다.
    // ========================================================================

    static Chunk<Settlement> sampleChunk() {
        return Chunk.of(
                new Settlement(1L, 1, LocalDate.of(2025, 1, 1),
                        new BigDecimal("1000.00"), new BigDecimal("0.0300"),
                        new BigDecimal("30.00"), new BigDecimal("970.00")),
                new Settlement(2L, 2, LocalDate.of(2025, 1, 1),
                        new BigDecimal("2000.00"), new BigDecimal("0.0250"),
                        new BigDecimal("50.00"), new BigDecimal("1950.00"))
        );
    }

    // ========================================================================
    // [5-1] 참고 — 이렇게 하면 안 되는 Tasklet 방식.
    //   70,000건을 List 로 전부 들고 하나의 트랜잭션으로 처리합니다.
    //   메모리·트랜잭션·재시작 셋 다 문제입니다. 실행하지 마세요.
    // ========================================================================

    @SuppressWarnings("unused")
    static class DontDoThis {
        void badTasklet(JdbcTemplate jdbc) {
            List<Order> all = jdbc.query(
                    "SELECT order_id, customer_id, amount, status, ordered_at "
                            + "FROM orders WHERE status = 'COMPLETED'",
                    new DataClassRowMapper<>(Order.class));
            // 70,000개가 전부 힙에. 700만 건이면 OutOfMemoryError.
            // 그리고 69,999번째에서 실패하면 처음부터 다시.
            log.warn("{}건을 통째로 들고 있습니다", all.size());
        }
    }

    // 참고: BeanPropertyItemSqlParameterSourceProvider 는 record 에 안 되므로
    //       import 만 남겨 두고 쓰지 않습니다. Step 08 에서 이유를 다룹니다.
    @SuppressWarnings("unused")
    private static final Class<?> WHY_NOT_THIS = BeanPropertyItemSqlParameterSourceProvider.class;
}
