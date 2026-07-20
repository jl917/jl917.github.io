package com.example.batch.step07;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.support.ClassifierCompositeItemProcessor;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.batch.item.validator.BeanValidatingItemProcessor;
import org.springframework.batch.item.validator.ValidatingItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.classify.Classifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 07 — ItemProcessor 실습 전체 코드.
 *
 * 실행:
 *   ./gradlew bootRun --args='--spring.batch.job.name=filteringJob'
 *   ./gradlew bootRun --args='--spring.batch.job.name=classifyingJob'
 *
 * 초기화(실행 전마다):
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
 */
@Configuration
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);

    // ======================================================================
    // [7-2] Order -> Settlement 변환과 BigDecimal 반올림 규칙
    // ======================================================================

    /**
     * 수수료율을 상수로 고정한 최소 형태. 변환의 뼈대와 반올림 규칙만 보여 줍니다.
     * 실제 Job 이 쓰는 것은 [7-3] 의 GradeFeeSettlementProcessor 입니다.
     */
    public static class FlatRateSettlementProcessor implements ItemProcessor<Order, Settlement> {

        private static final BigDecimal FLAT_RATE = new BigDecimal("0.0300");

        @Override
        public Settlement process(Order order) {
            BigDecimal gross = order.amount();

            // 규칙: fee 를 먼저 2자리로 "확정" 하고, net 은 확정된 fee 로 뺀다.
            // multiply 는 스케일이 더해지므로(2 + 4 = 6) 여기서 잘라 두지 않으면
            // net 도 6자리가 되고, DB 가 행마다 반올림해 SUM 이 어긋난다.
            BigDecimal fee = gross.multiply(FLAT_RATE).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(fee);

            return new Settlement(
                    order.order_id(),
                    order.customerId(),
                    order.orderedAt().toLocalDate(),
                    gross,
                    FLAT_RATE,
                    fee,
                    net);
        }
    }

    /** [7-2] 반올림 동작을 눈으로 확인하는 보조 메서드. main 에서 호출해도 됩니다. */
    public static void demonstrateRounding() {
        BigDecimal gross = new BigDecimal("1100.00");
        BigDecimal rate = new BigDecimal("0.0300");

        System.out.println(gross.multiply(rate));                                // 33.000000
        System.out.println(gross.multiply(rate).setScale(2, RoundingMode.HALF_UP)); // 33.00

        try {
            // setScale(int) 단독은 RoundingMode.UNNECESSARY 입니다.
            new BigDecimal("1050.00").multiply(new BigDecimal("0.0250")).setScale(2);
        } catch (ArithmeticException e) {
            System.out.println("ArithmeticException: " + e.getMessage()); // Rounding necessary
        }
    }

    // ======================================================================
    // [7-3] 등급별 수수료율 — 7만 번 조회를 1번으로
    // ======================================================================

    /**
     * ❌ 나쁜 예. 아이템마다 SELECT 를 날립니다. 7만 건이면 7만 왕복(약 41.9초).
     * 41.9초 -> 5.2초 비교를 재현하고 싶을 때만 파이프라인에 잠깐 끼워 넣으세요.
     */
    public static class SlowLookupProcessor implements ItemProcessor<Order, Settlement> {

        private final JdbcTemplate jdbcTemplate;

        public SlowLookupProcessor(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public Settlement process(Order order) {
            BigDecimal rate = jdbcTemplate.queryForObject(
                    "SELECT fee_rate FROM customers WHERE customer_id = ?",
                    BigDecimal.class, order.customerId());
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(), gross, rate, fee, gross.subtract(fee));
        }
    }

    /** ✅ 좋은 예. 1,000행짜리 마스터를 생성자에서 한 번만 읽어 불변 맵으로 들고 있습니다. */
    public static class GradeFeeSettlementProcessor implements ItemProcessor<Order, Settlement> {

        // final + 불변 컬렉션 = [7-10] 의 무상태 원칙
        private final Map<Integer, BigDecimal> feeRateByCustomer;

        public GradeFeeSettlementProcessor(JdbcTemplate jdbcTemplate) {
            this.feeRateByCustomer = loadFeeRates(jdbcTemplate);
        }

        @Override
        public Settlement process(Order order) {
            BigDecimal rate = feeRateByCustomer.get(order.customerId());
            if (rate == null) {
                // 데이터가 진짜로 잘못된 경우 -> 예외. null 필터링과 구분합니다([7-4]).
                throw new IllegalStateException("등급 정보 없는 고객: " + order.customerId());
            }
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(), gross, rate, fee, gross.subtract(fee));
        }
    }

    /** VIP 전용. 표준 수수료율에서 0.0050 을 추가 할인합니다. ([7-8] 분기용) */
    public static class VipSettlementProcessor implements ItemProcessor<Order, Settlement> {

        private static final BigDecimal VIP_DISCOUNT = new BigDecimal("0.0050");
        private final Map<Integer, BigDecimal> feeRateByCustomer;

        public VipSettlementProcessor(JdbcTemplate jdbcTemplate) {
            this.feeRateByCustomer = loadFeeRates(jdbcTemplate);
        }

        @Override
        public Settlement process(Order order) {
            BigDecimal base = feeRateByCustomer.get(order.customerId());
            BigDecimal rate = base.subtract(VIP_DISCOUNT);      // 0.0200 -> 0.0150
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(), gross, rate, fee, gross.subtract(fee));
        }
    }

    /** customers 1,000행을 통째로 읽어 불변 맵으로 만듭니다. */
    static Map<Integer, BigDecimal> loadFeeRates(JdbcTemplate jdbcTemplate) {
        Map<Integer, BigDecimal> map = new HashMap<>(1400);
        jdbcTemplate.query("SELECT customer_id, fee_rate FROM customers",
                rs -> { map.put(rs.getInt("customer_id"), rs.getBigDecimal("fee_rate")); });
        return Map.copyOf(map);
    }

    // ======================================================================
    // [7-4] null 반환 = 필터링
    // ======================================================================

    /** COMPLETED 가 아니면 null 을 반환합니다. 예외가 아니라 null 입니다. */
    public static class CompletedOnlyProcessor implements ItemProcessor<Order, Order> {

        @Override
        public Order process(Order order) {
            if (!"COMPLETED".equals(order.status())) {
                return null;    // writer 로 내려가지 않는다 -> filterCount 증가
            }
            return order;
        }
    }

    /** 최소 금액 미만 필터. CompositeItemProcessor 중간 단계 예시입니다. */
    public static class MinAmountFilterProcessor implements ItemProcessor<Order, Order> {

        private final BigDecimal minAmount;

        public MinAmountFilterProcessor(BigDecimal minAmount) {
            this.minAmount = minAmount;
        }

        @Override
        public Order process(Order order) {
            return order.amount().compareTo(minAmount) < 0 ? null : order;
        }
    }

    /** [7-4] StepExecution 의 카운터를 그대로 찍는 리스너. */
    @Bean
    public StepExecutionListener countLogger() {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution se) {
                log.info("readCount={}, writeCount={}, filterCount={}, skipCount={}",
                        se.getReadCount(), se.getWriteCount(),
                        se.getFilterCount(), se.getSkipCount());
                log.info("commitCount={}, rollbackCount={}, processSkipCount={}",
                        se.getCommitCount(), se.getRollbackCount(), se.getProcessSkipCount());
                return se.getExitStatus();
            }
        };
    }

    // ======================================================================
    // [7-6] CompositeItemProcessor — 직렬 파이프라인
    // ======================================================================

    /**
     * ✅ 올바른 순서: 필터를 앞으로, 비싼 변환을 뒤로.
     * 30,000건이 1단계에서 걸러지므로 GradeFee... 는 70,000번만 돕니다.
     */
    @Bean
    public ItemProcessor<Order, Settlement> settlementPipeline(JdbcTemplate jdbcTemplate) {
        // 타입 사슬을 지역 변수로 명시해 두면 눈으로 검증할 수 있습니다([7-7] 방어책 1).
        ItemProcessor<Order, Order> step1 = new CompletedOnlyProcessor();
        ItemProcessor<Order, Order> step2 = new MinAmountFilterProcessor(new BigDecimal("1000"));
        ItemProcessor<Order, Settlement> step3 = new GradeFeeSettlementProcessor(jdbcTemplate);
        // Order -> Order -> Order -> Settlement

        // 5.1 부터는 가변인자 생성자도 제공됩니다.
        return new CompositeItemProcessor<>(step1, step2, step3);
    }

    // ======================================================================
    // [7-7] 제네릭 타입 불일치 — 컴파일은 되는데 런타임에 터진다
    // ======================================================================

    /**
     * ❌ 델리게이트 순서가 뒤집혔습니다. setDelegates 의 파라미터가
     *    List&lt;? extends ItemProcessor&lt;?, ?&gt;&gt; 라서 컴파일러가 아무 말도 하지 않습니다.
     *
     * 실행하면:
     *   java.lang.ClassCastException: class com.example.batch.domain.Settlement
     *       cannot be cast to class com.example.batch.domain.Order
     *       at com.example.batch.step07.Practice$CompletedOnlyProcessor.process(...)
     *
     * 스택트레이스는 CompletedOnlyProcessor 를 가리키지만, 틀린 곳은 아래 setDelegates 입니다.
     *
     * Job 빈으로 등록하지 않았으므로 그냥 빌드해도 안전합니다.
     * 재현하려면 filteringStep 의 .processor(...) 자리에 손으로 바꿔 끼우세요.
     */
    public ItemProcessor<Order, Settlement> brokenPipeline(JdbcTemplate jdbcTemplate) {
        CompositeItemProcessor<Order, Settlement> composite = new CompositeItemProcessor<>();
        composite.setDelegates(List.of(
                new GradeFeeSettlementProcessor(jdbcTemplate),  // Order -> Settlement
                new CompletedOnlyProcessor()                    // Order 를 기대 -> 폭발
        ));
        return composite;
    }

    /**
     * ✅ 방어책 2 — 직접 합성. null 전파를 손으로 써야 하지만,
     *    순서를 틀리면 "incompatible types" 로 컴파일이 실패합니다.
     */
    public ItemProcessor<Order, Settlement> safePipeline(JdbcTemplate jdbcTemplate) {
        ItemProcessor<Order, Order> step1 = new CompletedOnlyProcessor();
        ItemProcessor<Order, Order> step2 = new MinAmountFilterProcessor(new BigDecimal("1000"));
        ItemProcessor<Order, Settlement> step3 = new GradeFeeSettlementProcessor(jdbcTemplate);

        return order -> {
            Order a = step1.process(order);
            if (a == null) return null;
            Order b = step2.process(a);
            if (b == null) return null;
            return step3.process(b);
        };
    }

    // ======================================================================
    // [7-8] ClassifierCompositeItemProcessor — 분기
    // ======================================================================

    @Bean
    public ItemProcessor<Order, Settlement> classifyingProcessor(JdbcTemplate jdbcTemplate) {

        ItemProcessor<Order, Settlement> standard = new GradeFeeSettlementProcessor(jdbcTemplate);
        ItemProcessor<Order, Settlement> vip = new VipSettlementProcessor(jdbcTemplate);
        ItemProcessor<Order, Settlement> drop = order -> null;   // 정산 대상 아님

        // 분류에 필요한 정보는 "미리 캐시된 맵" 이어야 합니다.
        // classify() 안에서 DB 를 조회하면 [7-3] 에서 없앤 왕복이 되살아납니다.
        Map<Integer, BigDecimal> rates = loadFeeRates(jdbcTemplate);
        BigDecimal vipRate = new BigDecimal("0.0200");

        ClassifierCompositeItemProcessor<Order, Settlement> processor =
                new ClassifierCompositeItemProcessor<>();

        Classifier<Order, ItemProcessor<?, ? extends Settlement>> classifier = order -> {
            if (!"COMPLETED".equals(order.status())) return drop;
            return vipRate.compareTo(rates.get(order.customerId())) == 0 ? vip : standard;
        };
        processor.setClassifier(classifier);
        return processor;
    }

    // ======================================================================
    // [7-9] ValidatingItemProcessor / BeanValidatingItemProcessor
    // ======================================================================

    /**
     * setFilter(false) — 기본값. 위반 시 ValidationException 을 던집니다.
     *   -> "이건 버그다"
     * setFilter(true)  — 위반 시 null 을 반환합니다.
     *   -> "이건 대상이 아니다" (filterCount 로 잡힘)
     */
    @Bean
    public ValidatingItemProcessor<Order> orderValidator() {
        ValidatingItemProcessor<Order> validator = new ValidatingItemProcessor<>(order -> {
            if (order.amount() == null || order.amount().signum() <= 0) {
                throw new ValidationException("금액이 0 이하: order_id=" + order.order_id());
            }
            if (order.orderedAt() == null) {
                throw new ValidationException("주문일시 없음: order_id=" + order.order_id());
            }
        });
        validator.setFilter(false);
        return validator;
    }

    /**
     * Jakarta Bean Validation 기반.
     * ⚠️ @Bean 이 아니면 afterPropertiesSet() 이 호출되지 않아 내부 Validator 가 null 입니다.
     *    new 로 만들어 쓴다면 반드시 손으로 afterPropertiesSet() 을 부르세요.
     */
    @Bean
    public BeanValidatingItemProcessor<ValidatedSettlement> beanValidator() throws Exception {
        BeanValidatingItemProcessor<ValidatedSettlement> p = new BeanValidatingItemProcessor<>();
        p.setFilter(false);
        p.afterPropertiesSet();
        return p;
    }

    /** 검증 애너테이션을 붙인 record. 실제 도메인의 Settlement 에 그대로 옮겨도 됩니다. */
    public record ValidatedSettlement(
            @NotNull Long orderId,
            @NotNull Integer customerId,
            @NotNull BigDecimal grossAmount,
            @NotNull BigDecimal feeRate,
            @NotNull BigDecimal feeAmount,
            @NotNull BigDecimal netAmount) {
    }

    // ======================================================================
    // [7-10] 상태를 가진 프로세서 — 하면 안 되는 예
    // ======================================================================

    /**
     * ❌ 상태를 가진 프로세서.
     *
     * (1) 재시작하면 processed 가 0 부터 시작합니다. 5만 건까지 처리하고 죽으면
     *     재시작 후 최종 리포트가 20,000 이라고 찍힙니다. 에러 없이 틀립니다.
     * (2) .taskExecutor(...) 를 붙이는 순간 여러 스레드가 같은 인스턴스를 씁니다.
     *     processed++ 는 원자적이지 않고, runningTotal 참조 교체도 경쟁 상태입니다.
     *
     * 재현: filteringStep 에 아래 한 줄을 추가하고 여러 번 돌려 보세요.
     *     .taskExecutor(new SimpleAsyncTaskExecutor("batch-"))
     * 매번 다른 값이 나오는 것이 관찰 포인트입니다.
     */
    public static class CountingProcessor implements ItemProcessor<Order, Settlement> {

        private int processed = 0;                            // ← 상태
        private BigDecimal runningTotal = BigDecimal.ZERO;     // ← 상태
        private final ItemProcessor<Order, Settlement> delegate;

        public CountingProcessor(ItemProcessor<Order, Settlement> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Settlement process(Order order) throws Exception {
            processed++;
            runningTotal = runningTotal.add(order.amount());
            return delegate.process(order);
        }

        public int getProcessed() { return processed; }
        public BigDecimal getRunningTotal() { return runningTotal; }
    }

    /**
     * △ AtomicLong 으로 바꾸면 (2) 멀티스레드 문제는 사라집니다.
     *   그러나 (1) 재시작 시 0 부터 세는 문제는 그대로입니다. 정답이 아닙니다.
     *   누적값을 재시작에도 이어가려면 ExecutionContext 에 넣어야 합니다(Step 09).
     */
    public static class AtomicCountingProcessor implements ItemProcessor<Order, Settlement> {

        private final AtomicLong processed = new AtomicLong();
        private final ItemProcessor<Order, Settlement> delegate;

        public AtomicCountingProcessor(ItemProcessor<Order, Settlement> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Settlement process(Order order) throws Exception {
            processed.incrementAndGet();
            return delegate.process(order);
        }
    }

    /**
     * ✅ 정답 — 세지 마세요. 프레임워크가 이미 세고 있습니다([7-4]).
     *   건수: StepExecution.getWriteCount()
     *   누적: ExecutionContext (Step 09)
     *   리포트: StepExecutionListener.afterStep() (Step 12)
     */

    // ======================================================================
    // Reader / Writer / Step / Job
    // ======================================================================

    /** [7-4] status 조건 없이 10만 건 전부 읽습니다. 필터링은 프로세서가 합니다. */
    @Bean
    public ItemReader<Order> allOrdersReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<Order>()
                .name("allOrdersReader")
                .dataSource(dataSource)
                .sql("SELECT order_id, customer_id, amount, status, ordered_at "
                        + "FROM orders ORDER BY order_id")
                .rowMapper((rs, rowNum) -> new Order(
                        rs.getLong("order_id"),
                        rs.getInt("customer_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getTimestamp("ordered_at").toLocalDateTime()))
                .fetchSize(1000)
                .build();
    }

    /** Step 08 에서 자세히 다룹니다. record 라 beanMapped() 대신 람다를 씁니다. */
    @Bean
    public JdbcBatchItemWriter<Settlement> settlementWriter(DataSource dataSource) {
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
                .assertUpdates(true)
                .build();
    }

    @Bean
    public Step filteringStep(JobRepository jobRepository,
                              PlatformTransactionManager tx,
                              ItemReader<Order> allOrdersReader,
                              ItemProcessor<Order, Settlement> settlementPipeline,
                              ItemWriter<Settlement> settlementWriter,
                              StepExecutionListener countLogger) {
        return new StepBuilder("filteringStep", jobRepository)
                .<Order, Settlement>chunk(1000, tx)
                .reader(allOrdersReader)
                .processor(settlementPipeline)
                .writer(settlementWriter)
                .listener(countLogger)
                // [7-10] 함정 재현용. 평소에는 주석 처리해 둡니다.
                // .taskExecutor(new SimpleAsyncTaskExecutor("batch-"))
                .build();
    }

    @Bean
    public Step classifyingStep(JobRepository jobRepository,
                                PlatformTransactionManager tx,
                                ItemReader<Order> allOrdersReader,
                                ItemProcessor<Order, Settlement> classifyingProcessor,
                                ItemWriter<Settlement> settlementWriter,
                                StepExecutionListener countLogger) {
        return new StepBuilder("classifyingStep", jobRepository)
                .<Order, Settlement>chunk(1000, tx)
                .reader(allOrdersReader)
                .processor(classifyingProcessor)
                .writer(settlementWriter)
                .listener(countLogger)
                .build();
    }

    @Bean
    public Job filteringJob(JobRepository jobRepository, Step filteringStep) {
        return new JobBuilder("filteringJob", jobRepository)
                .start(filteringStep)
                .build();
    }

    @Bean
    public Job classifyingJob(JobRepository jobRepository, Step classifyingStep) {
        return new JobBuilder("classifyingJob", jobRepository)
                .start(classifyingStep)
                .build();
    }

    /** SimpleAsyncTaskExecutor import 를 실제로 쓰는 자리(멀티스레드 재현용). */
    static SimpleAsyncTaskExecutor batchExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("batch-");
        executor.setConcurrencyLimit(4);
        return executor;
    }
}
