package com.example.batch.step08;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
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
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.item.support.CompositeItemWriter;
import org.springframework.classify.Classifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;

/**
 * Step 08 — ItemWriter 실습 전체 코드.
 *
 * 실행 전 준비
 *   mkdir -p ./out
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.batch.job.name=settlementJob'   # DB 만
 *   ./gradlew bootRun --args='--spring.batch.job.name=fileJob'         # DB + 파일
 *   ./gradlew bootRun --args='--spring.batch.job.name=routingJob'      # 분기
 *
 * ─────────────────────────────────────────────────────────────────────
 * [8-3] 8배 실측을 재현하려면 application.yml 의 URL 을 아래 두 개로 번갈아 두세요.
 *
 *  (A) 옵션 없음 — 70,000건 쓰기에 약 61.4초, Com_insert = 70000
 *   url: jdbc:mysql://127.0.0.1:3308/batchdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
 *
 *  (B) 옵션 있음 — 약 7.7초, Com_insert = 70
 *   url: jdbc:mysql://127.0.0.1:3308/batchdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true
 *
 * 측정 전마다 TRUNCATE TABLE settlement; 를 하세요.
 * 안 하면 uk_settlement_order 제약 때문에 DuplicateKeyException 이 먼저 납니다.
 * ─────────────────────────────────────────────────────────────────────
 */
@Configuration
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);

    // ======================================================================
    // [8-1] 5.x 시그니처 — void write(Chunk<? extends T>)
    // ======================================================================

    /**
     * 4.x 는 write(List<? extends T> items) 였습니다. 5.0 에서 Chunk 로 바뀌었고
     * 이것은 소스 호환성이 깨지는 변경입니다. 4.x 코드를 붙여 넣으면
     *   error: ... does not override abstract method write(Chunk<? extends Settlement>)
     * 로 컴파일이 실패합니다. 컴파일러가 잡아 주는 "좋은 실패" 입니다.
     */
    public static class SettlementLogWriter implements ItemWriter<Settlement> {

        @Override
        public void write(Chunk<? extends Settlement> chunk) {
            log.debug("chunk size={} first={} last={}",
                    chunk.size(),
                    chunk.getItems().get(0).orderId(),
                    chunk.getItems().get(chunk.size() - 1).orderId());
        }
    }

    /** 4.x 코드를 옮길 때의 최소 어댑터. 본문은 그대로 두고 getItems() 만 끼웁니다. */
    public static class MigratedWriter implements ItemWriter<Settlement> {

        @Override
        public void write(Chunk<? extends Settlement> chunk) {
            doWrite(chunk.getItems());
        }

        /** 4.x 시절 그대로의 본문. */
        private void doWrite(List<? extends Settlement> items) {
            log.debug("{} items", items.size());
        }
    }

    /** ItemWriter 는 5.x 에서 @FunctionalInterface 입니다. 람다로도 됩니다. */
    static ItemWriter<Settlement> lambdaWriter() {
        return chunk -> chunk.forEach(s -> log.trace("{}", s.orderId()));
    }

    // ======================================================================
    // [8-2] JdbcBatchItemWriter — record 와 beanMapped()
    // ======================================================================

    /**
     * ❌ record 에는 통하지 않습니다.
     *
     * beanMapped() 는 BeanPropertyItemSqlParameterSourceProvider 를 쓰고,
     * 이것은 자바빈 규약(getOrderId())을 기대합니다. record 의 접근자는 orderId() 입니다.
     *
     * 실행 결과:
     *   org.springframework.dao.InvalidDataAccessApiUsageException:
     *     No value supplied for the SQL parameter 'orderId': Invalid property 'orderId'
     *     of bean class [com.example.batch.domain.Settlement]
     *
     * ⚠️ 이 예외가 나는 것은 settlement 의 컬럼들이 NOT NULL 이기 때문입니다.
     *    nullable 테이블이었다면 예외 없이 전 컬럼 NULL 인 70,000행이 들어가고
     *    writeCount=70000, STATUS=COMPLETED 로 모든 지표가 정상으로 보입니다.
     *    결과 테이블을 NOT NULL 로 만드는 것은 "조용한 실패를 시끄럽게 만드는 장치" 입니다.
     *
     * @Bean 이 아니므로 그냥 빌드해도 안전합니다.
     * 재현하려면 settlementJdbcWriter 자리에 손으로 바꿔 끼우세요.
     */
    public JdbcBatchItemWriter<Settlement> brokenBeanMappedWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Settlement>()
                .dataSource(dataSource)
                .sql(INSERT_SQL)
                .beanMapped()
                .build();
    }

    static final String INSERT_SQL = """
            INSERT INTO settlement
              (order_id, customer_id, settle_date, gross_amount,
               fee_rate, fee_amount, net_amount)
            VALUES
              (:orderId, :customerId, :settleDate, :grossAmount,
               :feeRate, :feeAmount, :netAmount)
            """;

    /**
     * ✅ record 의 정답. 장황하지만 컴파일 타임에 검증됩니다.
     * record 의 컴포넌트 이름을 바꾸면 여기서 컴파일 에러가 납니다.
     * beanMapped() 는 리팩터링을 따라오지 못합니다.
     */
    @Bean
    public JdbcBatchItemWriter<Settlement> settlementJdbcWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Settlement>()
                .dataSource(dataSource)
                .sql(INSERT_SQL)
                .itemSqlParameterSourceProvider(s -> new MapSqlParameterSource()
                        .addValue("orderId",     s.orderId())
                        .addValue("customerId",  s.customerId())
                        .addValue("settleDate",  s.settleDate())
                        .addValue("grossAmount", s.grossAmount())
                        .addValue("feeRate",     s.feeRate())
                        .addValue("feeAmount",   s.feeAmount())
                        .addValue("netAmount",   s.netAmount()))
                // [8-4] 기본값 true. UPDATE writer 에서 "0건 갱신"을 잡아 줍니다.
                .assertUpdates(true)
                .build();
    }

    // ======================================================================
    // [8-4] assertUpdates — 무엇을 잡고 무엇을 못 잡는가
    // ======================================================================

    /**
     * UPDATE writer. WHERE 에 걸리는 행이 없으면 갱신 건수가 0 이고,
     * assertUpdates(true) 가 EmptyResultDataAccessException 을 던집니다.
     *
     *   Item 137 of 1000 did not update any rows: [Settlement[orderId=999999, ...]]
     *
     * ⚠️ 구멍 두 개
     *  (1) rewriteBatchedStatements=true 로 UPDATE 배치가 다중 문장으로 재작성되면
     *      드라이버가 Statement.SUCCESS_NO_INFO(-2) 를 돌려줍니다.
     *      JdbcBatchItemWriter 의 검사는 `updateCounts[i] == 0` 뿐이라 -2 는 통과합니다.
     *      성능 옵션을 켜는 순간 안전장치가 조용히 꺼집니다.
     *  (2) "1건 기대했는데 70,000건 갱신됨"은 잡지 못합니다.
     *      WHERE 절을 빠뜨린 UPDATE 는 갱신 건수가 0 이 아니기 때문입니다.
     *      옵션으로 못 막습니다. UPDATE 의 WHERE 에 유니크 키가 있는지 리뷰하세요.
     */
    public JdbcBatchItemWriter<Settlement> settlementUpdateWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Settlement>()
                .dataSource(dataSource)
                .sql("UPDATE settlement SET net_amount = :netAmount WHERE order_id = :orderId")
                .itemSqlParameterSourceProvider(s -> new MapSqlParameterSource()
                        .addValue("orderId", s.orderId())
                        .addValue("netAmount", s.netAmount()))
                .assertUpdates(true)
                .build();
    }

    // ======================================================================
    // [8-5] FlatFileItemWriter — CSV
    // ======================================================================

    /**
     * BeanWrapperFieldExtractor 는 beanMapped() 와 같은 이유로 record 에 통하지 않습니다.
     * FieldExtractor 를 람다로 씁니다.
     *
     * headerCallback 은 open() 에서, footerCallback 은 close() 에서 실행됩니다([8-7]).
     * 이 사실이 [8-8] 함정의 뿌리입니다.
     */
    @Bean
    public FlatFileItemWriter<Settlement> settlementFileWriter() {
        return csvWriter("settlementFileWriter", "out/settlement.csv",
                "# generated by settlementJob");
    }

    @Bean
    public FlatFileItemWriter<Settlement> vipFileWriter() {
        return csvWriter("vipFileWriter", "out/vip-settlement.csv",
                "# vip settlements: generated by fileJob");
    }

    private static FlatFileItemWriter<Settlement> csvWriter(String name, String path, String footer) {
        DelimitedLineAggregator<Settlement> aggregator = new DelimitedLineAggregator<>();
        aggregator.setDelimiter(",");
        aggregator.setFieldExtractor(s -> new Object[]{
                s.orderId(), s.customerId(), s.settleDate(),
                s.grossAmount(), s.feeRate(), s.feeAmount(), s.netAmount()
        });

        return new FlatFileItemWriterBuilder<Settlement>()
                .name(name)                       // ExecutionContext 키의 접두사가 됩니다
                .resource(new FileSystemResource(path))
                .encoding("UTF-8")                // 플랫폼 기본값에 맡기지 마세요
                .lineSeparator("\n")              // 윈도우에서 \r 이 섞이는 사고 방지
                .lineAggregator(aggregator)
                .headerCallback(w -> w.write(
                        "order_id,customer_id,settle_date,gross_amount,"
                                + "fee_rate,fee_amount,net_amount"))
                .footerCallback(w -> w.write(footer))
                .shouldDeleteIfExists(true)
                .transactional(true)              // 기본값. 청크 커밋 시점에 flush
                .build();
    }

    // ======================================================================
    // [8-6] CompositeItemWriter — 같은 청크를 여러 곳에
    // ======================================================================

    /**
     * 모든 델리게이트가 같은 청크를 받습니다.
     * writeCount 는 70000 한 번만 올라갑니다. 두 곳에 썼다고 140000 이 되지 않습니다.
     *
     * ✅ CompositeItemWriter 는 ItemStreamWriter 를 구현하므로
     *    open/update/close 를 모든 델리게이트에 전파합니다. .stream() 이 필요 없습니다.
     */
    @Bean
    public ItemWriter<Settlement> dualWriter(JdbcBatchItemWriter<Settlement> settlementJdbcWriter,
                                             FlatFileItemWriter<Settlement> settlementFileWriter) {
        CompositeItemWriter<Settlement> writer = new CompositeItemWriter<>();
        writer.setDelegates(List.of(settlementJdbcWriter, settlementFileWriter));
        return writer;
    }

    // ======================================================================
    // [8-6][8-8] ClassifierCompositeItemWriter — 아이템별 분기
    // ======================================================================

    /**
     * VIP(fee_rate 0.0150) 15,000건은 파일로, 나머지 55,000건은 DB 로.
     *
     * ❌ ClassifierCompositeItemWriter 는 ItemStream 을 구현하지 않습니다.
     *    안에 숨은 vipFileWriter 의 open/update/close 가 한 번도 불리지 않습니다.
     *    -> 파일 0줄, 또는 WriterNotOpenException.
     *    -> routingStep 의 .stream(vipFileWriter) 로 손수 등록해야 합니다.
     *
     * 이름이 비슷한 CompositeItemWriter 는 전파하는데 이쪽은 안 합니다.
     * 이 비대칭이 함정의 본질입니다.
     */
    @Bean
    public ItemWriter<Settlement> routingWriter(JdbcBatchItemWriter<Settlement> settlementJdbcWriter,
                                                FlatFileItemWriter<Settlement> vipFileWriter) {
        ClassifierCompositeItemWriter<Settlement> writer = new ClassifierCompositeItemWriter<>();
        BigDecimal vipRate = new BigDecimal("0.0150");

        Classifier<Settlement, ItemWriter<? super Settlement>> classifier =
                s -> vipRate.compareTo(s.feeRate()) == 0 ? vipFileWriter : settlementJdbcWriter;

        writer.setClassifier(classifier);
        return writer;
    }

    // ======================================================================
    // [8-9] 커스텀 ItemStreamWriter
    // ======================================================================

    /**
     * 외부 API 로 쓰는 writer. 실제 엔드포인트가 없으므로 그대로 실행하면
     * 커넥션 예외가 납니다. 구조를 읽는 용도입니다.
     *
     * 규칙 셋
     *  (1) write() 안에서 아이템 단위 루프로 원격 호출 금지 — [8-3] 의 8배 문제와 동형입니다.
     *  (2) open() 에서 복원하고 update() 에서 저장 — 안 하면 재시작 시 중복 전송됩니다.
     *  (3) 멱등하게 — 청크가 롤백되면 같은 청크가 다시 옵니다.
     *      REST 호출은 롤백되지 않습니다. 이미 보낸 것은 보낸 것입니다.
     *      order_id 같은 멱등 키를 실어 보내고 수신 측이 중복을 무시하게 하세요.
     */
    public static class SettlementApiWriter implements ItemStreamWriter<Settlement> {

        private static final String CTX_KEY = "settlementApiWriter.sent";

        private final RestClient restClient;
        private long sent = 0;

        public SettlementApiWriter(RestClient restClient) {
            this.restClient = restClient;
        }

        @Override
        public void open(ExecutionContext ctx) {
            this.sent = ctx.getLong(CTX_KEY, 0L);      // (2) 재시작 복원
        }

        @Override
        public void write(Chunk<? extends Settlement> chunk) {
            restClient.post().uri("/settlements/bulk")  // (1) 청크 통째로 한 번에
                    .body(chunk.getItems())
                    .retrieve().toBodilessEntity();
            sent += chunk.size();
        }

        @Override
        public void update(ExecutionContext ctx) {
            ctx.putLong(CTX_KEY, sent);                // (2) 청크 커밋마다 저장
        }

        @Override
        public void close() {
        }
    }

    // ======================================================================
    // Reader / Processor / Listener
    // ======================================================================

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

    /** Step 07 의 파이프라인을 그대로 씁니다. 10만 건 읽고 3만 건 필터 -> 7만 건 출력. */
    @Bean
    public ItemProcessor<Order, Settlement> settlementPipeline(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        var step1 = new com.example.batch.step07.Practice.CompletedOnlyProcessor();
        var step2 = new com.example.batch.step07.Practice.GradeFeeSettlementProcessor(jdbcTemplate);
        return order -> {
            Order filtered = step1.process(order);
            return filtered == null ? null : step2.process(filtered);
        };
    }

    @Bean
    public StepExecutionListener countLogger() {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution se) {
                log.info("readCount={}, writeCount={}, filterCount={}, skipCount={}",
                        se.getReadCount(), se.getWriteCount(),
                        se.getFilterCount(), se.getSkipCount());
                return se.getExitStatus();
            }
        };
    }

    // ======================================================================
    // Step / Job
    // ======================================================================

    /** [8-3] 8배 측정용. DB 에만 씁니다. */
    @Bean
    public Step settlementStep(JobRepository jobRepository, PlatformTransactionManager tx,
                               ItemReader<Order> allOrdersReader,
                               ItemProcessor<Order, Settlement> settlementPipeline,
                               JdbcBatchItemWriter<Settlement> settlementJdbcWriter,
                               StepExecutionListener countLogger) {
        return new StepBuilder("settlementStep", jobRepository)
                .<Order, Settlement>chunk(1000, tx)
                .reader(allOrdersReader)
                .processor(settlementPipeline)
                .writer(settlementJdbcWriter)
                .listener(countLogger)
                .build();
    }

    /** [8-6] DB + 파일. CompositeItemWriter 라 .stream() 이 필요 없습니다. */
    @Bean
    public Step dualStep(JobRepository jobRepository, PlatformTransactionManager tx,
                         ItemReader<Order> allOrdersReader,
                         ItemProcessor<Order, Settlement> settlementPipeline,
                         ItemWriter<Settlement> dualWriter,
                         StepExecutionListener countLogger) {
        return new StepBuilder("dualStep", jobRepository)
                .<Order, Settlement>chunk(1000, tx)
                .reader(allOrdersReader)
                .processor(settlementPipeline)
                .writer(dualWriter)
                .listener(countLogger)
                .build();
    }

    /**
     * [8-8] 함정 재현 스텝.
     *
     * 순서를 지키세요.
     *  1. .stream(vipFileWriter) 가 주석인 채로 실행 -> 파일 0줄 / WriterNotOpenException
     *     그리고 BATCH_STEP_EXECUTION_CONTEXT 에 vipFileWriter.written 키가 없는 것 확인
     *  2. 주석을 풀고 다시 실행 -> 15,002줄, 컨텍스트에 vipFileWriter.written=15000
     *
     * 고쳐진 코드만 보면 이 함정은 배워지지 않습니다.
     */
    @Bean
    public Step routingStep(JobRepository jobRepository, PlatformTransactionManager tx,
                            ItemReader<Order> allOrdersReader,
                            ItemProcessor<Order, Settlement> settlementPipeline,
                            ItemWriter<Settlement> routingWriter,
                            FlatFileItemWriter<Settlement> vipFileWriter,
                            StepExecutionListener countLogger) {
        return new StepBuilder("routingStep", jobRepository)
                .<Order, Settlement>chunk(1000, tx)
                .reader(allOrdersReader)
                .processor(settlementPipeline)
                .writer(routingWriter)
                // .stream(vipFileWriter)      // ← 1단계에서는 주석, 2단계에서 해제
                .listener(countLogger)
                .build();
    }

    @Bean
    public Job settlementJob(JobRepository jobRepository, Step settlementStep) {
        return new JobBuilder("settlementJob", jobRepository).start(settlementStep).build();
    }

    @Bean
    public Job fileJob(JobRepository jobRepository, Step dualStep) {
        return new JobBuilder("fileJob", jobRepository).start(dualStep).build();
    }

    @Bean
    public Job routingJob(JobRepository jobRepository, Step routingStep) {
        return new JobBuilder("routingJob", jobRepository).start(routingStep).build();
    }
}
