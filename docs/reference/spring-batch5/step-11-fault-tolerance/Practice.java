package com.example.batch.step11;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 11 — 내결함성: skip · retry · 재시작 : 본문 11-0 ~ 11-12 의 모든 예제.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 실행 방법
 *
 *   ./gradlew bootRun --args='--spring.batch.job.name=settlementSkipJob date=2025-03-01'
 *
 * 바깥 클래스에 @Configuration 이 없습니다. 지금 실습할 static class 하나만
 * @Configuration 주석을 풀고 나머지는 주석 처리한 채로 돌리십시오.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 실습 순서를 반드시 지키십시오.
 *
 *   1. [11-0] PlantBadData.PLANT_SQL 을 먼저 실행합니다.
 *      이것을 빼먹으면 11-2 이후의 skip 이 전부 0건으로 나와서
 *      실습이 통째로 성립하지 않습니다.
 *
 *   2. 각 측정 전에 TRUNCATE TABLE settlement 를 합니다.
 *      빠뜨리면 앞 실행 결과가 남아 skip 수가 달라집니다.
 *
 *   3. 실습이 끝나면 CleanUp.REVERT_SQL 로 orders 를 원상 복구합니다.
 *      orders 는 이후 모든 스텝이 공유하는 공용 테이블입니다.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Practice {

    // =====================================================================
    // [11-0] 불량 데이터 심기
    //
    // main 에서 실행하지 마십시오. 문서의 mysql 명령과 같은 내용임을
    // 확인하는 용도입니다. mysql 클라이언트로 직접 실행하십시오.
    // =====================================================================

    public static class PlantBadData {

        /**
         * (A) Processor 에서 터질 불량: 금액이 음수인 주문 100건.
         * (B) Writer 에서 터질 중복: settlement 에 미리 100행.
         *
         * order_id % 1000 = 0 인 주문은 order_id % 10 = 0 도 만족하므로
         * 100건 전부가 status='COMPLETED' 입니다.
         *
         * ⚠️ 핵심: 불량 100건이 70청크에 "고르게 흩어져" 있습니다.
         *    COMPLETED 700건마다 1건이므로, 청크 1,000 이면
         *    70청크 전부가 최소 1건의 불량을 품습니다.
         *    11-5 에서 이 배치가 왜 5.7배나 느려지는지의 열쇠입니다.
         */
        public static final String PLANT_SQL = """
                UPDATE orders SET amount = -1.00 WHERE order_id %% 1000 = 0;

                TRUNCATE TABLE settlement;
                INSERT INTO settlement
                  (order_id, customer_id, settle_date, gross_amount,
                   fee_rate, fee_amount, net_amount)
                SELECT o.order_id, o.customer_id, DATE(o.ordered_at),
                       1000.00, 0.0300, 30.00, 970.00
                FROM orders o
                WHERE o.status = 'COMPLETED' AND o.order_id %% 1000 = 0;

                -- 검증: target=70000, bad_amount=100, preloaded=100
                SELECT (SELECT COUNT(*) FROM orders WHERE status='COMPLETED') AS target,
                       (SELECT COUNT(*) FROM orders WHERE amount < 0)         AS bad_amount,
                       (SELECT COUNT(*) FROM settlement)                      AS preloaded;
                """;
    }

    // =====================================================================
    // 공통 — Reader / Processor / Writer
    // =====================================================================

    /** 날짜 파라미터 없이 COMPLETED 전체(70,000)를 읽습니다. */
    public static JdbcPagingItemReader<Order> buildOrderReader(DataSource dataSource) {
        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
        provider.setFromClause("FROM orders");
        provider.setWhereClause("WHERE status = 'COMPLETED'");
        // 정렬 키가 없으면 페이지가 밀려 데이터가 유실됩니다 — Step 06 참고.
        provider.setSortKeys(Map.of(
                "order_id", org.springframework.batch.item.database.Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("orderReader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .pageSize(1000)
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    /**
     * 등급별 수수료 적용. 금액이 음수면 예외를 던집니다.
     *
     * 11-0 에서 심은 100건의 음수 금액이 여기서 터집니다.
     */
    public static class SettlementProcessor implements ItemProcessor<Order, Settlement> {

        private static final BigDecimal[] FEE_RATES = {
                new BigDecimal("0.0350"),   // BRONZE
                new BigDecimal("0.0300"),   // SILVER
                new BigDecimal("0.0250"),   // GOLD
                new BigDecimal("0.0200")    // VIP
        };

        @Override
        public Settlement process(Order order) {
            if (order.amount().signum() < 0) {
                throw new IllegalArgumentException(
                        "정산 금액이 음수입니다: order_id=" + order.order_id()
                                + ", amount=" + order.amount());
            }
            BigDecimal feeRate = FEE_RATES[order.customerId() % 4];
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(),
                    gross, feeRate, fee, gross.subtract(fee));
        }
    }

    /**
     * 비멱등 Writer — 순수 INSERT.
     *
     * ⚠️ ON DUPLICATE KEY UPDATE 를 일부러 쓰지 않습니다.
     *    UNIQUE 충돌이 DuplicateKeyException 으로 터져야 skip 실습이
     *    가능하기 때문입니다. Step 13 의 멱등 Writer 와 대조하십시오.
     */
    public static JdbcBatchItemWriter<Settlement> buildStrictWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Settlement>()
                .dataSource(dataSource)
                .sql("""
                     INSERT INTO settlement
                       (order_id, customer_id, settle_date, gross_amount,
                        fee_rate, fee_amount, net_amount)
                     VALUES
                       (:orderId, :customerId, :settleDate, :grossAmount,
                        :feeRate, :feeAmount, :netAmount)
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

    // =====================================================================
    // [11-1] 아무 대비 없는 Step — 첫 예외에서 통째로 멈춘다
    //
    // 결과: FAILED. read_count 는 1000 근처에서 멈추고
    //       settlement 에는 아무것도 안 남습니다(청크가 롤백되므로).
    // =====================================================================

    // @Configuration
    public static class NoTolerance {

        @Bean
        public Job settlementNoToleranceJob(JobRepository jobRepository, Step noToleranceStep) {
            return new JobBuilder("settlementNoToleranceJob", jobRepository)
                    .start(noToleranceStep)
                    .build();
        }

        @Bean
        public Step noToleranceStep(JobRepository jobRepository,
                                    PlatformTransactionManager txManager,
                                    DataSource dataSource) {
            return new StepBuilder("noToleranceStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildStrictWriter(dataSource))
                    // .faultTolerant() 가 없습니다. 첫 예외에서 Step 전체가 죽습니다.
                    .build();
        }
    }

    // =====================================================================
    // [11-2] .faultTolerant() 와 .skip()
    //
    // 11-1 과 완전히 같고 세 줄만 추가했습니다. 이 쌍을 순서대로 돌려
    // FAILED / COMPLETED 를 대조하십시오.
    // =====================================================================

    // @Configuration
    public static class SkipBasics {

        @Bean
        public Job settlementSkipJob(JobRepository jobRepository, Step skipStep) {
            return new JobBuilder("settlementSkipJob", jobRepository)
                    .start(skipStep)
                    .build();
        }

        @Bean
        public Step skipStep(JobRepository jobRepository,
                             PlatformTransactionManager txManager,
                             DataSource dataSource) {
            return new StepBuilder("skipStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildStrictWriter(dataSource))
                    .faultTolerant()
                    .skip(IllegalArgumentException.class)
                    .skipLimit(200)             // 기본값은 10 입니다 — 11-3
                    .build();
        }
    }

    // =====================================================================
    // [11-4] 커스텀 SkipPolicy — 조건을 코드로 쓴다
    //
    // ⚠️ instanceof 판정 순서가 핵심입니다.
    //    구체적인 예외를 먼저, 넓은 예외를 나중에 검사해야 합니다.
    //    순서를 뒤집으면 인프라 장애가 데이터 오류로 오인되어 조용히
    //    skip 됩니다 — 연습문제 2 의 함정입니다.
    // =====================================================================

    public static class SettlementSkipPolicy implements SkipPolicy {

        private final int dataErrorLimit;

        public SettlementSkipPolicy(int dataErrorLimit) {
            this.dataErrorLimit = dataErrorLimit;
        }

        @Override
        public boolean shouldSkip(Throwable t, long skipCount)
                throws SkipLimitExceededException {

            // ① 인프라 장애는 절대 skip 하지 않습니다.
            //    커넥션이 끊긴 상황에서 계속 진행하면 나머지 전부를
            //    "실패로 건너뛰고" COMPLETED 로 끝냅니다.
            if (t instanceof org.springframework.dao.DataAccessResourceFailureException) {
                return false;
            }

            // ② 데이터 오류는 한도까지 skip 합니다.
            if (t instanceof IllegalArgumentException
                    || t instanceof DataIntegrityViolationException) {
                if (skipCount >= dataErrorLimit) {
                    throw new SkipLimitExceededException(dataErrorLimit, t);
                }
                return true;
            }

            // ③ 그 외 예외는 모릅니다 → 안전하게 실패시킵니다.
            //    "모르는 예외는 skip 하지 않는다"가 안전한 기본값입니다.
            return false;
        }
    }

    // =====================================================================
    // [11-5] skip 의 진짜 비용 — 청크 롤백과 스캔 모드
    //
    // ★ 이 스텝의 핵심 측정입니다.
    //
    // 아래 세 Job 을 이 순서대로 돌려야 6.108 / 34.712 / 6.594 가 나옵니다.
    // 각 실행 전에 반드시 TRUNCATE TABLE settlement 하십시오.
    //
    //   ① baselineJob        — 불량 없음        →  6.108초, commit 70, rollback 0
    //   ② writeSkipJob       — 흩어진 skip 100  → 34.712초, commit 69900, rollback 100
    //   ③ clusteredSkipJob   — 뭉친 skip 100    →  6.594초, commit 1069, rollback 1
    //
    // ②가 5.7배 느린 이유:
    //   skip 이 나면 그 청크는 통째로 롤백되고, Spring Batch 는 "누가
    //   범인인지" 모르므로 **아이템을 1건씩 다시 처리**합니다(스캔 모드).
    //   1,000건짜리 청크가 1,000개의 트랜잭션으로 쪼개집니다.
    //   불량이 70청크 전부에 흩어져 있으므로 70청크 × 1000 = 70,000번의
    //   커밋에 가까워집니다. commit_count 69,900 이 그 증거입니다.
    //
    // ③이 빠른 이유:
    //   불량 100건이 1개 청크에 몰려 있으면 그 청크 하나만 스캔 모드로
    //   들어갑니다. 69청크는 정상 속도로 통과합니다.
    //   → **skip 비용은 skip 건수가 아니라 "오염된 청크 수"에 비례합니다.**
    // =====================================================================

    // @Configuration
    public static class ScanModeDemo {

        @Bean
        public Job settlementWriteSkipJob(JobRepository jobRepository, Step writeSkipStep) {
            return new JobBuilder("settlementWriteSkipJob", jobRepository)
                    .start(writeSkipStep)
                    .build();
        }

        @Bean
        public Step writeSkipStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager,
                                  DataSource dataSource) {
            return new StepBuilder("writeSkipStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildStrictWriter(dataSource))
                    .faultTolerant()
                    // DuplicateKeyException 의 상위 타입입니다.
                    .skip(DataIntegrityViolationException.class)
                    .skipLimit(200)
                    .build();
        }

        /**
         * ③ 뭉친 skip — 불량을 1개 청크에 몰아 넣은 상태에서 돌립니다.
         *
         * 사전 SQL (order_id 1~100 에 몰기):
         *   TRUNCATE TABLE settlement;
         *   INSERT INTO settlement (order_id, customer_id, settle_date,
         *                           gross_amount, fee_rate, fee_amount, net_amount)
         *   SELECT order_id, customer_id, DATE(ordered_at), 1000.00, 0.0300, 30.00, 970.00
         *   FROM orders WHERE status='COMPLETED' AND order_id <= 1000;
         */
        @Bean
        public Job settlementClusteredSkipJob(JobRepository jobRepository, Step writeSkipStep) {
            return new JobBuilder("settlementClusteredSkipJob", jobRepository)
                    .start(writeSkipStep)
                    .build();
        }
    }

    // =====================================================================
    // [11-7] .retry() 와 backoff
    // =====================================================================

    /**
     * 지수 백오프. 200 → 400 → 800 ms.
     *
     * ⚠️ maxInterval 을 반드시 거십시오. 없으면 재시도가 많은 Step 에서
     *    대기 시간이 폭주합니다(200 → 400 → ... → 수 분).
     */
    public static BackOffPolicy exponentialBackOff() {
        ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
        policy.setInitialInterval(200L);
        policy.setMultiplier(2.0);
        policy.setMaxInterval(5_000L);
        return policy;
    }

    /**
     * 세 번째 청크의 처음 두 번의 시도에서만 데드락을 던지는 Writer.
     *
     * 진짜 MySQL 데드락을 재현하려면 커넥션 두 개로 교차 갱신을 해야 하지만,
     * 여기서는 재시도 로그를 보는 것이 목적이므로 예외를 직접 던집니다.
     * 던지는 예외 타입은 실제 Spring 이 변환해 주는 것과 동일합니다.
     */
    public static class FlakyWriter implements ItemWriter<Settlement> {

        private final ItemWriter<Settlement> delegate;
        private final AtomicInteger chunkCounter = new AtomicInteger(0);
        private final AtomicInteger failuresLeft = new AtomicInteger(2);

        public FlakyWriter(ItemWriter<Settlement> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(Chunk<? extends Settlement> chunk) throws Exception {
            int current = chunkCounter.incrementAndGet();
            if (current == 3 && failuresLeft.getAndDecrement() > 0) {
                throw new DeadlockLoserDataAccessException(
                        "Deadlock found when trying to get lock; try restarting transaction",
                        new java.sql.SQLException("Deadlock found", "40001", 1213));
            }
            delegate.write(chunk);
        }
    }

    // @Configuration
    public static class RetryDemo {

        @Bean
        public Job settlementRetryJob(JobRepository jobRepository, Step retryStep) {
            return new JobBuilder("settlementRetryJob", jobRepository)
                    .start(retryStep)
                    .build();
        }

        @Bean
        public Step retryStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager,
                              DataSource dataSource) {
            return new StepBuilder("retryStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(new FlakyWriter(buildStrictWriter(dataSource)))
                    .faultTolerant()
                    .retry(DeadlockLoserDataAccessException.class)
                    .retry(org.springframework.dao.CannotAcquireLockException.class)
                    // ⚠️ retryLimit(3) 은 "재시도 3번"이 아니라 "총 시도 3회"입니다.
                    //    그리고 .retry() 만 쓰고 이 줄을 빠뜨리면 기본값 0이라
                    //    아무 재시도도 일어나지 않습니다. 항상 짝으로 쓰십시오.
                    .retryLimit(3)
                    .backOffPolicy(exponentialBackOff())
                    .skip(DataIntegrityViolationException.class)
                    .skipLimit(200)
                    .build();
        }
    }

    // =====================================================================
    // [11-9] noRollback — 롤백시키지 않을 예외
    //
    // 기본적으로 어떤 예외든 청크 트랜잭션을 롤백시킵니다.
    // "이 예외는 데이터에 영향이 없으니 롤백할 필요 없다"고 선언하면
    // 스캔 모드로 들어가지 않아 성능이 유지됩니다.
    // =====================================================================

    // @Configuration
    public static class NoRollbackDemo {

        @Bean
        public Step noRollbackStep(JobRepository jobRepository,
                                   PlatformTransactionManager txManager,
                                   DataSource dataSource) {
            return new StepBuilder("noRollbackStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildStrictWriter(dataSource))
                    .faultTolerant()
                    .skip(ValidationWarning.class)
                    .skipLimit(1000)
                    // 이 예외는 DB 를 건드리기 전에 나므로 롤백이 불필요합니다.
                    .noRollback(ValidationWarning.class)
                    .build();
        }
    }

    /** 데이터에 영향을 주지 않는 검증 경고. */
    public static class ValidationWarning extends RuntimeException {
        public ValidationWarning(String message) {
            super(message);
        }
    }

    // =====================================================================
    // [11-10] 재시작 — 중단 지점을 이어받는다
    //
    // failAt 번째 아이템에서 일부러 죽였다가, 같은 파라미터로 다시 실행해
    // 중단 지점부터 이어가는 것을 확인합니다.
    //
    // ⚠️ failAt 은 identifying = false 여야 합니다.
    //    true 면 파라미터가 달라져 "새 JobInstance" 가 만들어지고,
    //    재시작이 아니라 처음부터 새로 도는 것이 됩니다.
    //    이 false 하나가 실습의 성립 조건입니다.
    // =====================================================================

    // @Configuration
    public static class RestartDemo {

        @Bean
        public Job settlementRestartJob(JobRepository jobRepository, Step restartStep) {
            return new JobBuilder("settlementRestartJob", jobRepository)
                    .start(restartStep)
                    .build();
        }

        @Bean
        public Step restartStep(JobRepository jobRepository,
                                PlatformTransactionManager txManager,
                                DataSource dataSource,
                                ItemProcessor<Order, Settlement> failingProcessor) {
            return new StepBuilder("restartStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(failingProcessor)
                    .writer(buildStrictWriter(dataSource))
                    .build();
        }

        /**
         * failAt 번째 아이템에서 죽습니다.
         *
         * 실행 예:
         *   1회차: --spring.batch.job.name=settlementRestartJob failAt=30000
         *          → 30,000번째에서 FAILED. commit 29 (29,000건 커밋됨)
         *   2회차: --spring.batch.job.name=settlementRestartJob failAt=999999
         *          → 29,000번째부터 재개. read_count 41,000
         *
         *   ⚠️ 2회차에서 failAt 값을 바꿔도 같은 JobInstance 입니다.
         *      identifying=false 이기 때문입니다.
         */
        @Bean
        @StepScope
        public ItemProcessor<Order, Settlement> failingProcessor(
                @Value("#{jobParameters['failAt'] ?: 999999L}") Long failAt) {

            SettlementProcessor delegate = new SettlementProcessor();
            AtomicInteger counter = new AtomicInteger(0);

            return order -> {
                if (counter.incrementAndGet() >= failAt) {
                    throw new IllegalStateException(
                            "의도적 실패: " + failAt + "번째 아이템");
                }
                return delegate.process(order);
            };
        }
    }

    // =====================================================================
    // [11-11] 함정 — Reader 가 상태를 저장하지 않으면
    //
    // 같은 Job 을 Reader 만 바꿔 두 번 돌려
    // read_count 30,000 vs 70,000 을 비교하는 것이 목적입니다.
    //
    //   NaiveReader   → 재시작해도 처음부터 다시 읽습니다.
    //                   이미 처리한 29,000건을 또 처리하려다
    //                   UNIQUE 충돌로 죽거나(비멱등 Writer),
    //                   조용히 중복 정산합니다(멱등 Writer).
    //   StatefulReader → ExecutionContext 에 위치를 저장해 이어받습니다.
    // =====================================================================

    /**
     * ⚠️ 일부러 틀린 Reader. ItemStreamReader 가 아니라 ItemReader 만
     *    구현했으므로 open/update/close 콜백이 아예 없습니다.
     *    Spring Batch 는 이 Reader 의 위치를 저장할 방법이 없습니다.
     */
    public static class NaiveReader implements
            org.springframework.batch.item.ItemReader<Order> {

        private final List<Order> orders;
        private int index = 0;

        public NaiveReader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public Order read() {
            return index >= orders.size() ? null : orders.get(index++);
        }
    }

    /**
     * 상태를 저장하는 Reader.
     *
     * 세 콜백의 역할:
     *   open(ctx)   — Step 시작 시 1회. 재시작이면 저장된 위치를 복원합니다.
     *   update(ctx) — 청크 커밋마다. 현재 위치를 기록합니다.
     *   close()     — Step 종료 시 1회. 자원 정리.
     *
     * ⚠️ ExecutionContext 의 키에 Reader 이름을 접두사로 붙이는 것이 중요합니다.
     *    안 붙이면 한 Step 에 Reader 가 둘일 때 서로의 키를 덮어씁니다.
     */
    public static class StatefulReader implements ItemStreamReader<Order> {

        private static final String KEY_INDEX = "statefulReader.index";

        private final List<Order> orders;
        private int index = 0;

        public StatefulReader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public void open(ExecutionContext ctx) throws ItemStreamException {
            // 재시작 판정의 관용구입니다.
            if (ctx.containsKey(KEY_INDEX)) {
                this.index = ctx.getInt(KEY_INDEX);
                System.out.println(">>> 재시작: " + index + "번째부터 이어갑니다");
            } else {
                this.index = 0;
            }
        }

        @Override
        public Order read() {
            return index >= orders.size() ? null : orders.get(index++);
        }

        @Override
        public void update(ExecutionContext ctx) throws ItemStreamException {
            ctx.putInt(KEY_INDEX, index);
        }

        @Override
        public void close() throws ItemStreamException {
            // 정리할 자원 없음
        }
    }

    /** 70,000건을 메모리에 올립니다. 실습 전용입니다. */
    public static List<Order> loadCompletedOrders(DataSource dataSource) {
        return new JdbcTemplate(dataSource).query("""
                SELECT order_id, customer_id, amount, status, ordered_at
                FROM orders WHERE status = 'COMPLETED' ORDER BY order_id
                """, new DataClassRowMapper<>(Order.class));
    }

    // =====================================================================
    // [11-12] allowStartIfComplete 와 startLimit
    // =====================================================================

    // @Configuration
    public static class RestartPolicyDemo {

        /**
         * allowStartIfComplete(true)
         *   — 이미 COMPLETED 인 Step 도 재실행합니다.
         *     "매번 처음부터 다시 해야 하는" 정리/초기화 Step 에 씁니다.
         *
         * ⚠️ 정산 Step 에는 절대 붙이지 마십시오.
         *    재시작할 때마다 처음부터 다시 정산해서 중복이 쌓입니다.
         */
        @Bean
        public Step cleanupStep(JobRepository jobRepository,
                                PlatformTransactionManager txManager,
                                DataSource dataSource) {
            return new StepBuilder("cleanupStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        new JdbcTemplate(dataSource).update("TRUNCATE TABLE settlement");
                        return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                    }, txManager)
                    .allowStartIfComplete(true)
                    .build();
        }

        /**
         * startLimit(n) — 이 Step 을 최대 n 번까지만 시도합니다.
         *
         * 기본값은 Integer.MAX_VALUE 입니다. 제한을 두면 "고칠 수 없는
         * 실패를 무한히 재시도하는" 상황을 막을 수 있습니다.
         * n 번을 넘기면 StartLimitExceededException 이 납니다.
         */
        @Bean
        public Step limitedStep(JobRepository jobRepository,
                                PlatformTransactionManager txManager,
                                DataSource dataSource) {
            return new StepBuilder("limitedStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(buildOrderReader(dataSource))
                    .processor(new SettlementProcessor())
                    .writer(buildStrictWriter(dataSource))
                    .startLimit(3)
                    .build();
        }
    }

    // =====================================================================
    // CleanUp — 실습 뒷정리
    // =====================================================================

    public static class CleanUp {

        /**
         * orders 를 원래 금액으로 되돌립니다.
         *
         * ⚠️ orders 는 이후 모든 스텝이 공유하는 공용 테이블입니다.
         *    이 스텝을 끝내면 반드시 실행하십시오. 음수 금액을 남겨 두면
         *    Step 12·13·14 의 결과가 교재와 달라집니다.
         */
        public static final String REVERT_SQL = """
                UPDATE orders o
                  JOIN (SELECT order_id, 1000 + (order_id %% 977) * 100 AS amt
                        FROM orders) s
                    ON o.order_id = s.order_id
                   SET o.amount = s.amt
                 WHERE o.amount < 0;

                TRUNCATE TABLE settlement;

                -- 검증: bad_amount 가 0 이어야 합니다.
                SELECT COUNT(*) AS bad_amount FROM orders WHERE amount < 0;
                """;

        public static final String RESET_METADATA_SQL = """
                SET FOREIGN_KEY_CHECKS = 0;
                DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
                DELETE FROM BATCH_STEP_EXECUTION;
                DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
                DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
                DELETE FROM BATCH_JOB_EXECUTION;
                DELETE FROM BATCH_JOB_INSTANCE;
                SET FOREIGN_KEY_CHECKS = 1;
                """;

        /** 카운터 확인용. 모든 측정 뒤에 이 쿼리를 돌리십시오. */
        public static final String VERIFY_SQL = """
                SELECT STEP_NAME, STATUS,
                       READ_COUNT, WRITE_COUNT, FILTER_COUNT,
                       READ_SKIP_COUNT, PROCESS_SKIP_COUNT, WRITE_SKIP_COUNT,
                       COMMIT_COUNT, ROLLBACK_COUNT,
                       TIMESTAMPDIFF(SECOND, START_TIME, END_TIME) AS secs
                FROM BATCH_STEP_EXECUTION
                ORDER BY STEP_EXECUTION_ID DESC
                LIMIT 5;
                """;
    }
}
