package com.example.batch.step06;

/*
 * ============================================================================
 * Step 06 — ItemReader / 실습 코드
 * ============================================================================
 *
 * 본문 6-1 ~ 6-9 의 모든 리더 설정을 절 번호 주석과 함께 담았습니다.
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
 * [Job 목록]  ⚠️ --spring.batch.job.name 을 반드시 지정하세요.
 *   cursorJob      6-2  JdbcCursorItemReader
 *   pagingJob      6-3  JdbcPagingItemReader (정렬 키 = order_id, 정상)
 *   brokenSortJob  6-6  정렬 키 = ordered_at  → 67,207건만 읽고 COMPLETED  ★
 *   fixedSortJob   6-6  정렬 키 = (ordered_at, order_id) → 70,000건 회복
 *   jpaJob         6-8  JpaPagingItemReader
 *   csvJob         6-9  FlatFileItemReader
 *
 * [★ 이 스텝의 핵심 체험 — 순서를 지키세요]
 *   mysql ... -e "TRUNCATE TABLE settlement;"
 *   ./gradlew bootRun --args='--spring.batch.job.name=brokenSortJob'
 *   mysql ... -e "SELECT COUNT(*) FROM settlement;"          → 67207  ← 직접 확인!
 *
 *   mysql ... -e "TRUNCATE TABLE settlement;"
 *   ./gradlew bootRun --args='--spring.batch.job.name=fixedSortJob'
 *   mysql ... -e "SELECT COUNT(*) FROM settlement;"          → 70000
 * ============================================================================
 */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);

    private static final String SELECT_CLAUSE = "order_id, customer_id, amount, status, ordered_at";
    private static final int PAGE_SIZE = 1000;
    private static final int CHUNK_SIZE = 1000;

    // ========================================================================
    // 도메인 — com.example.batch.domain 의 record 를 옮겨 놓은 것입니다.
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
            java.time.LocalDate settleDate,
            BigDecimal grossAmount,
            BigDecimal feeRate,
            BigDecimal feeAmount,
            BigDecimal netAmount
    ) {}

    /** [6-9] CSV 전용 레코드. */
    public record ExternalOrder(
            Long orderId,
            Integer customerId,
            BigDecimal amount,
            LocalDateTime orderedAt
    ) {}

    // ========================================================================
    // [6-2] JdbcCursorItemReader — 쿼리 1회, 커넥션 점유, 멀티스레드 불가
    // ========================================================================

    @Bean
    public JdbcCursorItemReader<Order> cursorReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<Order>()
                // ⚠️ name 은 ExecutionContext 키의 접두사입니다.
                //    Job 전체에서 유일해야 합니다. 중복되면 재시작이 조용히 망가집니다.
                .name("cursorReader")
                .dataSource(dataSource)
                .sql("""
                     SELECT order_id, customer_id, amount, status, ordered_at
                     FROM orders
                     WHERE status = ?
                     ORDER BY order_id
                     """)
                .queryArguments("COMPLETED")
                // ⚠️ MySQL 은 기본 설정에서 fetchSize 를 무시하고 결과셋 전체를 끌어옵니다.
                //    진짜 스트리밍이 필요하면 JDBC URL 에 useCursorFetch=true 를 붙이거나
                //    .fetchSize(Integer.MIN_VALUE) 를 쓰세요 (단, 그 커넥션으로 다른 쿼리 불가).
                .fetchSize(CHUNK_SIZE)
                // record 는 자바빈이 아니므로 BeanPropertyRowMapper 가 아니라 DataClassRowMapper
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    @Bean
    public Step cursorStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                           JdbcCursorItemReader<Order> cursorReader,
                           ItemProcessor<Order, Settlement> settlementProcessor,
                           ItemWriter<Settlement> settlementWriter) {
        return new StepBuilder("cursorStep", jobRepository)
                .<Order, Settlement>chunk(CHUNK_SIZE, txManager)
                .reader(cursorReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    @Bean
    public Job cursorJob(JobRepository jobRepository, Step cursorStep) {
        return new JobBuilder("cursorJob", jobRepository).start(cursorStep).build();
    }

    // ========================================================================
    // [6-3] JdbcPagingItemReader — 정상 버전. 정렬 키가 PK 라 유니크합니다.
    // ========================================================================

    @Bean
    public JdbcPagingItemReader<Order> pagingReader(DataSource dataSource) {

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause(SELECT_CLAUSE);
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        // ✅ order_id 는 PK 이므로 유니크합니다. 이게 정답입니다.
        provider.setSortKeys(Map.of(
                "order_id", org.springframework.batch.item.database.Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("pagingReader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(PAGE_SIZE)
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    /**
     * [6-3] SqlPagingQueryProviderFactoryBean — DataSource 메타데이터로 DB 를 감지해
     * 알맞은 PagingQueryProvider 를 고릅니다. MySQL 이면 MySqlPagingQueryProvider.
     * 로컬과 운영의 DB 가 다를 때 유용하지만, 단일 DB 라면 명시적인 편이 읽기 쉽습니다.
     */
    @Bean
    public SqlPagingQueryProviderFactoryBean autoDetectedProvider(DataSource dataSource) {
        SqlPagingQueryProviderFactoryBean factory = new SqlPagingQueryProviderFactoryBean();
        factory.setDataSource(dataSource);
        factory.setSelectClause(SELECT_CLAUSE);
        factory.setFromClause("FROM orders");
        factory.setWhereClause("status = :status");
        factory.setSortKeys(Map.of(
                "order_id", org.springframework.batch.item.database.Order.ASCENDING));
        return factory;
    }

    @Bean
    public Step pagingStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                           JdbcPagingItemReader<Order> pagingReader,
                           ItemProcessor<Order, Settlement> settlementProcessor,
                           ItemWriter<Settlement> settlementWriter) {
        return new StepBuilder("pagingStep", jobRepository)
                .<Order, Settlement>chunk(CHUNK_SIZE, txManager)
                .reader(pagingReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    @Bean
    public Job pagingJob(JobRepository jobRepository, Step pagingStep) {
        return new JobBuilder("pagingJob", jobRepository).start(pagingStep).build();
    }

    // ========================================================================
    // [6-6] ★ 고장 난 버전 — 정렬 키가 유니크하지 않습니다.
    //
    //   ordered_at 은 COMPLETED 기준 1,008종뿐이고 값 하나당 69~70건이 뭉쳐 있습니다.
    //   나머지 페이지 SQL 이 WHERE ordered_at > ? 이므로,
    //   페이지 경계에 걸친 뭉치의 뒷부분이 통째로 스킵됩니다.
    //
    //   결과: 70,000건 중 67,207건만 읽고 STATUS=COMPLETED.
    //         2,793건(약 1.38억 원)이 예외도 경고도 없이 증발합니다.
    // ========================================================================

    @Bean
    public JdbcPagingItemReader<Order> brokenSortReader(DataSource dataSource) {

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause(SELECT_CLAUSE);
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        // ❌ 유니크하지 않은 정렬 키. 이 한 줄이 2,793건을 삼킵니다.
        provider.setSortKeys(Map.of(
                "ordered_at", org.springframework.batch.item.database.Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("brokenSortReader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(PAGE_SIZE)
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    @Bean
    public Step brokenSortStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                               JdbcPagingItemReader<Order> brokenSortReader,
                               ItemProcessor<Order, Settlement> settlementProcessor,
                               ItemWriter<Settlement> settlementWriter) {
        return new StepBuilder("brokenSortStep", jobRepository)
                .<Order, Settlement>chunk(CHUNK_SIZE, txManager)
                .reader(brokenSortReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    /**
     * brokenSortJob 은 검증 Step 없이 끝나므로 COMPLETED 로 성공합니다.
     * 검증 Step 을 붙인 버전은 아래 verifiedBrokenSortJob 입니다 — 그건 FAILED 로 끝납니다.
     */
    @Bean
    public Job brokenSortJob(JobRepository jobRepository, Step brokenSortStep) {
        return new JobBuilder("brokenSortJob", jobRepository).start(brokenSortStep).build();
    }

    // ========================================================================
    // [6-6] ✅ 고친 버전 — 복합 정렬 키. 마지막 키가 PK 라 전체가 유니크해집니다.
    //
    //   생성되는 SQL:
    //     WHERE (status = ?) AND ((ordered_at > ?) OR (ordered_at = ? AND order_id > ?))
    //     ORDER BY ordered_at ASC, order_id ASC LIMIT 1000
    // ========================================================================

    @Bean
    public JdbcPagingItemReader<Order> fixedSortReader(DataSource dataSource) {

        // ⚠️ 반드시 LinkedHashMap 입니다. Map.of() 는 순회 순서를 보장하지 않아
        //    ORDER BY 절의 컬럼 순서가 JVM 재시작마다 달라질 수 있습니다.
        Map<String, org.springframework.batch.item.database.Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put("ordered_at", org.springframework.batch.item.database.Order.ASCENDING);
        sortKeys.put("order_id", org.springframework.batch.item.database.Order.ASCENDING);  // 타이브레이커

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause(SELECT_CLAUSE);
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        provider.setSortKeys(sortKeys);

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("fixedSortReader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(PAGE_SIZE)
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    @Bean
    public Step fixedSortStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                              JdbcPagingItemReader<Order> fixedSortReader,
                              ItemProcessor<Order, Settlement> settlementProcessor,
                              ItemWriter<Settlement> settlementWriter) {
        return new StepBuilder("fixedSortStep", jobRepository)
                .<Order, Settlement>chunk(CHUNK_SIZE, txManager)
                .reader(fixedSortReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    @Bean
    public Job fixedSortJob(JobRepository jobRepository, Step fixedSortStep) {
        return new JobBuilder("fixedSortJob", jobRepository).start(fixedSortStep).build();
    }

    // ========================================================================
    // [6-6] 해결책 ③ — 건수 검증 Tasklet
    //
    //   정렬 키를 아무리 조심해도 사람은 실수합니다.
    //   대상 건수와 결과 건수를 대조하는 Step 을 배치 끝에 붙이면
    //   "조용한 유실"이 "시끄러운 실패"가 됩니다.
    // ========================================================================

    static class VerificationTasklet implements Tasklet {

        private final JdbcTemplate jdbc;

        VerificationTasklet(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            Integer expected = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'", Integer.class);
            Integer actual = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM settlement", Integer.class);

            log.info("정산 검증 — 대상 {}건, 처리 {}건", expected, actual);

            if (!java.util.Objects.equals(expected, actual)) {
                // 예외를 던져야 Job 전체가 FAILED 가 됩니다.
                // ExitStatus 만 바꾸면 Job 은 여전히 COMPLETED 입니다.
                throw new IllegalStateException(
                        "정산 누락 감지: 대상 %d건, 처리 %d건, 누락 %d건"
                                .formatted(expected, actual, expected - actual));
            }
            return RepeatStatus.FINISHED;
        }
    }

    @Bean
    public Step verificationStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                                 JdbcTemplate jdbcTemplate) {
        return new StepBuilder("verificationStep", jobRepository)
                .tasklet(new VerificationTasklet(jdbcTemplate), txManager)
                .build();
    }

    /**
     * 고장 난 리더 + 검증 Step. 이 Job 은 FAILED 로 끝납니다.
     *
     *   ./gradlew bootRun --args='--spring.batch.job.name=verifiedBrokenSortJob'
     *
     *   ERROR --- o.s.batch.core.step.AbstractStep : Encountered an error executing step verificationStep
     *   java.lang.IllegalStateException: 정산 누락 감지: 대상 70000건, 처리 67207건, 누락 2793건
     */
    @Bean
    public Job verifiedBrokenSortJob(JobRepository jobRepository,
                                     Step brokenSortStep, Step verificationStep) {
        return new JobBuilder("verifiedBrokenSortJob", jobRepository)
                .start(brokenSortStep)
                .next(verificationStep)
                .build();
    }

    // ========================================================================
    // [6-8] JpaPagingItemReader — OFFSET 방식 + 영속 엔티티
    //
    //   ⚠️ ORDER BY 를 JPQL 에 직접 씁니다. sortKeys 설정이 없으므로
    //      프레임워크가 유니크성에 대해 알려 줄 방법이 더 없습니다.
    //      ORDER BY 를 빼먹으면 누락과 중복이 동시에 납니다.
    // ========================================================================

    @Entity
    @Table(name = "orders")
    public static class OrderEntity {
        @Id
        @Column(name = "order_id")
        private Long orderId;

        @Column(name = "customer_id")
        private Integer customerId;

        private BigDecimal amount;
        private String status;

        @Column(name = "ordered_at")
        private LocalDateTime orderedAt;

        protected OrderEntity() {}

        public Long getOrderId() { return orderId; }
        public Integer getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }
        public LocalDateTime getOrderedAt() { return orderedAt; }

        // ⚠️ setter 를 두면 processor 에서 실수로 호출했을 때
        //    더티 체킹으로 UPDATE 가 나갑니다. 배치의 읽기 엔티티에는
        //    setter 를 두지 않는 편이 안전합니다.
    }

    @Bean
    public JpaPagingItemReader<OrderEntity> jpaReader(EntityManagerFactory emf) {
        return new JpaPagingItemReaderBuilder<OrderEntity>()
                .name("jpaReader")
                .entityManagerFactory(emf)
                .queryString("""
                             SELECT o FROM OrderEntity o
                             WHERE o.status = :status
                             ORDER BY o.orderId ASC
                             """)   // ← 유니크한 정렬 키를 직접 명시
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(PAGE_SIZE)
                .build();
    }

    @Bean
    public Step jpaStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                        JpaPagingItemReader<OrderEntity> jpaReader) {
        return new StepBuilder("jpaStep", jobRepository)
                .<OrderEntity, OrderEntity>chunk(CHUNK_SIZE, txManager)
                .reader(jpaReader)
                // 읽기만 확인하는 Job 이라 writer 는 로그만 남깁니다.
                .writer(chunk -> log.debug("JPA 청크 {}건, 첫 주문 {}",
                        chunk.size(), chunk.getItems().get(0).getOrderId()))
                .build();
    }

    @Bean
    public Job jpaJob(JobRepository jobRepository, Step jpaStep) {
        return new JobBuilder("jpaJob", jobRepository).start(jpaStep).build();
    }

    // ========================================================================
    // [6-9] FlatFileItemReader — CSV
    //
    //   실행 전 파일을 만드세요:
    //     cat > /tmp/external-orders.csv <<'CSV'
    //     # 외부 정산 파일 v2 — 2025-06-30 생성
    //     order_id,customer_id,amount,ordered_at
    //     900001,1,15000.00,2025-06-30 10:00:00
    //     900002,2,23500.00,2025-06-30 10:05:00
    //     900003,3,8700.00,2025-06-30 10:11:00
    //     CSV
    // ========================================================================

    @Bean
    public FlatFileItemReader<ExternalOrder> csvReader() {

        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(",");
        tokenizer.setNames("orderId", "customerId", "amount", "orderedAt");
        tokenizer.setStrict(true);   // 컬럼 수가 다르면 예외

        DefaultLineMapper<ExternalOrder> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSet -> new ExternalOrder(
                fieldSet.readLong("orderId"),
                fieldSet.readInt("customerId"),
                fieldSet.readBigDecimal("amount"),
                LocalDateTime.parse(fieldSet.readString("orderedAt").replace(' ', 'T'))
        ));

        return new FlatFileItemReaderBuilder<ExternalOrder>()
                .name("csvReader")
                .resource(new FileSystemResource("/tmp/external-orders.csv"))
                // ⚠️ encoding 기본값은 JVM 기본 인코딩입니다. 반드시 명시하세요.
                .encoding("UTF-8")
                // ⚠️ linesToSkip(2) 는 "주석 1줄 + 헤더 1줄"을 가정합니다.
                //    주석이 한 줄 늘면 첫 데이터 행이 조용히 사라집니다.
                //    아래 comments() 방식이 훨씬 안전합니다.
                .comments("#")       // 주석은 몇 줄이든 알아서 무시
                .linesToSkip(1)      // 헤더 한 줄만
                .lineMapper(lineMapper)
                // .strict(false) 는 쓰지 마세요. 파일이 없어도 배치가 성공합니다.
                .build();
    }

    @Bean
    public Step csvStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                        FlatFileItemReader<ExternalOrder> csvReader) {
        return new StepBuilder("csvStep", jobRepository)
                .<ExternalOrder, ExternalOrder>chunk(10, txManager)
                .reader(csvReader)
                .writer(chunk -> chunk.forEach(o -> log.debug("읽음: {}", o)))
                .build();
    }

    @Bean
    public Job csvJob(JobRepository jobRepository, Step csvStep) {
        return new JobBuilder("csvJob", jobRepository).start(csvStep).build();
    }

    // ========================================================================
    // 공용 Processor / Writer — Step 05 와 동일합니다.
    // ========================================================================

    @Bean
    public Map<Integer, BigDecimal> feeRateCache(JdbcTemplate jdbc) {
        Map<Integer, BigDecimal> cache = new java.util.HashMap<>();
        jdbc.query("SELECT customer_id, fee_rate FROM customers",
                rs -> { cache.put(rs.getInt("customer_id"), rs.getBigDecimal("fee_rate")); });
        return cache;
    }

    @Bean
    public ItemProcessor<Order, Settlement> settlementProcessor(Map<Integer, BigDecimal> feeRateCache) {
        return item -> {
            BigDecimal rate = feeRateCache.get(item.customerId());
            BigDecimal gross = item.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
            return new Settlement(item.order_id(), item.customerId(),
                    item.orderedAt().toLocalDate(), gross, rate, fee, gross.subtract(fee));
        };
    }

    @Bean
    public ItemWriter<Settlement> settlementWriter(DataSource dataSource) {
        var writer = new org.springframework.batch.item.database.builder
                .JdbcBatchItemWriterBuilder<Settlement>()
                .dataSource(dataSource)
                .sql("""
                     INSERT INTO settlement
                       (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
                     VALUES
                       (:orderId, :customerId, :settleDate, :grossAmount, :feeRate, :feeAmount, :netAmount)
                     """)
                .itemSqlParameterSourceProvider(s ->
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                                .addValue("orderId", s.orderId())
                                .addValue("customerId", s.customerId())
                                .addValue("settleDate", s.settleDate())
                                .addValue("grossAmount", s.grossAmount())
                                .addValue("feeRate", s.feeRate())
                                .addValue("feeAmount", s.feeAmount())
                                .addValue("netAmount", s.netAmount()))
                .build();
        writer.afterPropertiesSet();
        return writer;
    }

    // ========================================================================
    // [6-6] 교육용 계측 — 페이지 경계에서 몇 건이 유실되는지 세어 로그로 남깁니다.
    //
    //   ⚠️ 운영 코드에 넣을 것이 아닙니다. 아이템마다 SQL 을 날립니다.
    //      다만 이런 계측 없이는 유실을 눈으로 볼 방법이 없다는 점 자체가
    //      이 함정의 무서움입니다.
    // ========================================================================

    static class PageBoundaryLogger
            implements org.springframework.batch.core.ItemReadListener<Order> {

        private static final Logger log = LoggerFactory.getLogger(PageBoundaryLogger.class);

        private final JdbcTemplate jdbc;
        private final int pageSize;
        private int readSoFar = 0;
        private int page = 0;
        private int totalLost = 0;
        private LocalDateTime lastSeen;
        private int sameValueSeenInPage = 0;

        PageBoundaryLogger(JdbcTemplate jdbc, int pageSize) {
            this.jdbc = jdbc;
            this.pageSize = pageSize;
        }

        @Override
        public void afterRead(Order item) {
            readSoFar++;
            if (item.orderedAt().equals(lastSeen)) {
                sameValueSeenInPage++;
            } else {
                lastSeen = item.orderedAt();
                sameValueSeenInPage = 1;
            }

            if (readSoFar % pageSize == 0) {          // 페이지 경계
                page++;
                Integer total = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM orders WHERE status='COMPLETED' AND ordered_at = ?",
                        Integer.class, lastSeen);
                int lost = (total == null ? 0 : total) - sameValueSeenInPage;
                totalLost += lost;
                log.debug("페이지 {} 마지막 ordered_at={}, 같은 값 총 {}건 중 {}건만 읽음 → {}건 유실",
                        page, lastSeen, total, sameValueSeenInPage, lost);
            }
        }

        public void summary() {
            log.debug("총 {}페이지, 누적 유실 {}건", page, totalLost);
        }
    }
}
