package com.example.batch.step08;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.Step;
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
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Step 08 — 연습문제 7문항.
 *
 * 준비
 *   mkdir -p ./out
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
 *
 * 규칙
 *  - "여기에 작성:" 자리를 채우세요.
 *  - 문제 2·3·4·6 은 반드시 "예측 먼저, 실행 나중" 입니다.
 *
 * 정답은 Solution.java.
 */
public class Exercise {

    // ======================================================================
    // 문제 1 — 4.x writer 를 5.x 시그니처로 마이그레이션
    // ======================================================================
    /**
     * 아래는 Spring Batch 4.x 스타일 writer 입니다. 그대로 두면 컴파일되지 않습니다.
     * doWrite(List) 본문은 건드리지 말고, 시그니처만 5.x 로 바꾸세요.
     *
     * 컴파일 에러 메시지를 먼저 예측해 보세요.
     *   여기에 작성(예상 에러):
     */
    public static class Q1_LegacyWriter implements ItemWriter<Settlement> {

        // 여기에 작성: 5.x 시그니처로 write() 를 구현하세요.

        /** 4.x 시절 본문. 수정하지 마세요. */
        private void doWrite(List<? extends Settlement> items) {
            System.out.println("wrote " + items.size() + " items");
        }
    }

    // ======================================================================
    // 문제 2 — record 에 맞는 JdbcBatchItemWriter
    // ======================================================================
    /**
     * (a) 아래 writer 에서 .beanMapped() 를 쓰면 어떤 예외가 납니까?
     *     예외 클래스 : ______________________
     *     메시지 요지 : ______________________
     *     그 이유     : 여기에 작성:
     *
     * (b) settlement 테이블의 컬럼이 전부 nullable 이었다면 무슨 일이 벌어집니까?
     *     writeCount = ______, Job STATUS = ______, 데이터 = ______
     *     여기에 작성(설명):
     *
     * (c) record 에 맞는 writer 를 완성하세요.
     */
    public static class Q2_RecordWriter {

        public JdbcBatchItemWriter<Settlement> build(DataSource dataSource) {
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
                    // 여기에 작성: itemSqlParameterSourceProvider 를 람다로 채우세요.

                    .assertUpdates(true)
                    .build();
        }
    }

    // ======================================================================
    // 문제 3 — rewriteBatchedStatements 실측 (측정 절차 문제)
    // ======================================================================
    /**
     * 코드가 아니라 절차를 수행하는 문제입니다. 두 번 측정하세요.
     *
     * 각 측정마다:
     *   1) application.yml 의 datasource.url 을 (A) 또는 (B) 로 바꾼다
     *      (A) ...serverTimezone=Asia/Seoul
     *      (B) ...serverTimezone=Asia/Seoul&rewriteBatchedStatements=true
     *   2) mysql ... -e "TRUNCATE TABLE settlement;"
     *   3) mysql ... -uroot -proot1234 -e "FLUSH STATUS;"
     *   4) ./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
     *   5) mysql ... -uroot -proot1234 -e "SHOW GLOBAL STATUS LIKE 'Com_insert';"
     *
     * 결과를 채우세요.
     *   ┌───────────────┬──────────────┬─────────────┬────────────┐
     *   │               │ Step 실행시간 │ Com_insert  │ writeCount │
     *   ├───────────────┼──────────────┼─────────────┼────────────┤
     *   │ (A) 옵션 없음  │              │             │            │
     *   │ (B) 옵션 있음  │              │             │            │
     *   └───────────────┴──────────────┴─────────────┴────────────┘
     *   배수: 약 ______ 배
     *
     * (a) 두 실행의 writeCount / Job STATUS / 결과 행 수가 같습니까? 답:
     *     여기에 작성:
     * (b) 그렇다면 이 문제를 "모니터링만으로" 발견할 수 있습니까? 답과 대안:
     *     여기에 작성:
     */

    // ======================================================================
    // 문제 4 — assertUpdates 가 못 잡는 두 상황
    // ======================================================================
    /**
     * ⚠️ 이 문제는 settlement 데이터를 파괴합니다. 실행 후 TRUNCATE 하고 재생성하세요.
     *
     * (a) 아래 writer 는 WHERE 절이 없습니다.
     *     70,000건 청크를 흘리면 최종적으로 settlement 는 어떤 상태가 됩니까?
     *     여기에 작성(예측):
     *     assertUpdates(true) 가 이것을 잡습니까? 답과 이유:
     *     여기에 작성:
     *
     * (b) rewriteBatchedStatements=true 인 상태에서 UPDATE 배치가 다중 문장으로
     *     재작성되면 드라이버는 각 항목에 어떤 값을 돌려줍니까?
     *     값 = ______ (상수 이름과 숫자)
     *     JdbcBatchItemWriter 의 검사는 `updateCounts[i] == 0` 입니다.
     *     이 값이 그 검사를 통과합니까? 답과 그 의미:
     *     여기에 작성:
     *
     * (c) 두 상황 각각의 대안 방어책을 적으세요.
     *     (a) 의 방어책: 여기에 작성:
     *     (b) 의 방어책: 여기에 작성:
     */
    public static class Q4_OverUpdate {

        public JdbcBatchItemWriter<Settlement> dangerous(DataSource dataSource) {
            return new JdbcBatchItemWriterBuilder<Settlement>()
                    .dataSource(dataSource)
                    .sql("UPDATE settlement SET fee_rate = :feeRate")   // WHERE 가 없다
                    .itemSqlParameterSourceProvider(s -> new MapSqlParameterSource()
                            .addValue("feeRate", s.feeRate()))
                    .assertUpdates(true)
                    .build();
        }
    }

    // ======================================================================
    // 문제 5 — FlatFileItemWriter 와 header / footer
    // ======================================================================
    /**
     * out/exercise-settlement.csv 를 만드세요.
     *  - 구분자 ","
     *  - 헤더: order_id,customer_id,settle_date,gross_amount,fee_rate,fee_amount,net_amount
     *  - 푸터: # total lines above = 70000
     *  - 인코딩 UTF-8, 줄 구분자 "\n", 기존 파일은 삭제 후 새로 쓰기
     *
     * 실행 후 `wc -l out/exercise-settlement.csv` 의 결과를 예측하세요.
     *   예측 = ______줄
     *   그 이유: 여기에 작성:
     *
     * 힌트: FieldExtractor 로 BeanWrapperFieldExtractor 를 쓰면 안 되는 이유는
     *       문제 2 의 beanMapped() 와 완전히 같습니다.
     */
    public static class Q5_FileWriter {

        public FlatFileItemWriter<Settlement> build() {
            DelimitedLineAggregator<Settlement> aggregator = new DelimitedLineAggregator<>();
            aggregator.setDelimiter(",");
            // 여기에 작성: setFieldExtractor

            return new FlatFileItemWriterBuilder<Settlement>()
                    .name("exerciseFileWriter")
                    .resource(new FileSystemResource("out/exercise-settlement.csv"))
                    // 여기에 작성: encoding / lineSeparator / lineAggregator /
                    //             headerCallback / footerCallback / shouldDeleteIfExists

                    .build();
        }
    }

    // ======================================================================
    // 문제 6 — ClassifierCompositeItemWriter 와 .stream()
    // ======================================================================
    /**
     * VIP(fee_rate 0.0150) 15,000건은 파일로, 나머지 55,000건은 DB 로 보냅니다.
     *
     * 아래 Step 에는 .stream() 이 빠져 있습니다.
     *
     * 1단계: 이 상태로 그대로 실행하세요. (실패가 정상입니다)
     *   Job STATUS       = ______
     *   DB 행 수         = ______
     *   파일 줄 수       = ______
     *   예외가 났다면 클래스 = ______________________
     *   BATCH_STEP_EXECUTION_CONTEXT 에 vipFileWriter.written 키가 있습니까? ______
     *
     * 2단계: 왜 이렇게 되는지 적으세요.
     *   여기에 작성:
     *   (힌트: ClassifierCompositeItemWriter 는 무엇을 구현하지 "않는가"?
     *          CompositeItemWriter 와 비교하세요.)
     *
     * 3단계: 고치세요.
     */
    public static class Q6_RoutingStep {

        public Step build(JobRepository jobRepository, PlatformTransactionManager tx,
                          ItemReader<Order> reader,
                          ItemProcessor<Order, Settlement> processor,
                          ClassifierCompositeItemWriter<Settlement> routingWriter,
                          FlatFileItemWriter<Settlement> vipFileWriter) {
            return new StepBuilder("q6RoutingStep", jobRepository)
                    .<Order, Settlement>chunk(1000, tx)
                    .reader(reader)
                    .processor(processor)
                    .writer(routingWriter)
                    // 여기에 작성(3단계):

                    .build();
        }
    }

    // ======================================================================
    // 문제 7 — 커스텀 ItemStreamWriter 의 생명주기 (서술형)
    // ======================================================================
    /**
     * 아래 writer 를 완성하고, 다음 질문에 답하세요.
     *
     * (a) open() 을 비워 두면 무엇이 깨집니까?
     *     여기에 작성:
     * (b) update() 를 비워 두면 무엇이 깨집니까?
     *     여기에 작성:
     * (c) (a) 와 (b) 중 어느 쪽이 더 발견하기 어렵습니까? 왜입니까?
     *     여기에 작성:
     * (d) 청크가 롤백되면 이미 전송한 아이템은 어떻게 됩니까? 방어책은?
     *     여기에 작성:
     */
    public static class Q7_CustomWriter implements ItemStreamWriter<Settlement> {

        private static final String CTX_KEY = "q7Writer.sent";
        private long sent = 0;

        @Override
        public void open(ExecutionContext ctx) {
            // 여기에 작성:
        }

        @Override
        public void write(Chunk<? extends Settlement> chunk) {
            // 실제 전송 대신 카운트만 합니다.
            sent += chunk.size();
        }

        @Override
        public void update(ExecutionContext ctx) {
            // 여기에 작성:
        }

        @Override
        public void close() {
        }
    }
}
