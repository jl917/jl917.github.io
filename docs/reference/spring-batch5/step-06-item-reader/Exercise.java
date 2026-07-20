package com.example.batch.step06;

/*
 * ============================================================================
 * Step 06 — ItemReader / 연습문제 (7문제)
 * ============================================================================
 *
 * 정답은 Solution.java 에 있습니다. 먼저 직접 풀어 보세요.
 *
 * [매 실행 전 반드시 초기화]
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
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import com.example.batch.step06.Practice.Order;

@Configuration
public class Exercise {

    // ========================================================================
    // 문제 1. 아래 리더는 실행하면 예외가 납니다. 예외 이름을 확인하고 고치세요.
    //
    //   실행:
    //     ./gradlew bootRun --args='--spring.batch.job.name=q1Job'
    //
    //   ① 어떤 예외가 나나요?
    //   ② 왜 record 에는 BeanPropertyRowMapper 가 안 되나요? (이유 두 가지)
    //   ③ 고치세요.
    //
    //   그리고 생각해 볼 것:
    //     이 경우는 예외가 나서 오히려 다행입니다.
    //     BeanPropertyRowMapper 가 "예외 없이 조용히 틀리는" 경우는 언제일까요?
    // ========================================================================

    @Bean
    public JdbcPagingItemReader<Order> q1Reader(DataSource dataSource) {

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        provider.setSortKeys(Map.of(
                "order_id", org.springframework.batch.item.database.Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("q1Reader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(1000)
                // ❌ 여기가 문제입니다.
                .rowMapper(new BeanPropertyRowMapper<>(Order.class))
                // 여기에 작성: 위 줄을 지우고 올바른 rowMapper 로 바꾸세요
                .build();
    }

    /** 여기에 작성: ① 발생하는 예외의 정규화된 클래스명 */
    public static final String Q1_EXCEPTION = "";

    /** 여기에 작성: ③ BeanPropertyRowMapper 가 조용히 틀리는 경우 */
    public static final String Q1_SILENT_CASE = "";

    // ========================================================================
    // 문제 2. ★ 이 스텝의 핵심 문제입니다.
    //
    //   customer_id 를 정렬 키로 준 페이징 리더를
    //   pageSize = 1000 과 1024 로 각각 돌립니다.
    //   각각 몇 건을 읽을까요?
    //
    //   ⚠️ 반드시 실행 전에 계산하세요.
    //
    //   [계산에 필요한 재료 — 이 SQL 부터 돌리세요]
    //     mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
    //     SELECT COUNT(*) AS completed,
    //            COUNT(DISTINCT customer_id) AS distinct_cust,
    //            MIN(c) AS min_per_cust, MAX(c) AS max_per_cust
    //     FROM (SELECT customer_id, COUNT(*) c FROM orders
    //           WHERE status='COMPLETED' GROUP BY customer_id) t
    //     JOIN (SELECT COUNT(*) x FROM orders WHERE status='COMPLETED') u;"
    //
    //   [힌트]
    //     본문 6-3 의 "나머지 페이지" SQL 을 다시 보세요.
    //       WHERE (status = ?) AND ((customer_id > ?)) ... LIMIT ?
    //     페이지 경계가 "같은 customer_id 뭉치"의 어디에 떨어지느냐가 전부입니다.
    //     뭉치 크기가 균일하다면, 페이지 크기와 뭉치 크기의 관계를 따져 보세요.
    //
    //   [실행]
    //     mysql ... -e "TRUNCATE TABLE settlement;"
    //     ./gradlew bootRun --args='--spring.batch.job.name=q2Job --q2.page-size=1000'
    //     mysql ... -e "SELECT COUNT(*) FROM settlement;"
    //
    //     mysql ... -e "TRUNCATE TABLE settlement;"
    //     ./gradlew bootRun --args='--spring.batch.job.name=q2Job --q2.page-size=1024'
    //     mysql ... -e "SELECT COUNT(*) FROM settlement;"
    //
    //   두 결과가 다르다면, 그 사실이 의미하는 바를 Q2_LESSON 에 쓰세요.
    // ========================================================================

    @Value("${q2.page-size:1000}")
    private int q2PageSize;

    @Bean
    public JdbcPagingItemReader<Order> q2Reader(DataSource dataSource) {

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        // ❌ customer_id 는 유니크하지 않습니다.
        provider.setSortKeys(Map.of(
                "customer_id", org.springframework.batch.item.database.Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("q2Reader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(q2PageSize)
                .rowMapper(new org.springframework.jdbc.core.DataClassRowMapper<>(Order.class))
                .build();
    }

    /** 여기에 작성: pageSize=1000 일 때 읽을 건수 (예측) */
    public static final int Q2_READ_AT_1000 = 0;

    /** 여기에 작성: pageSize=1024 일 때 읽을 건수 (예측) */
    public static final int Q2_READ_AT_1024 = 0;

    /** 여기에 작성: 왜 두 값이 다른가 */
    public static final String Q2_WHY = "";

    /** 여기에 작성: 이 결과가 실무에 주는 교훈 */
    public static final String Q2_LESSON = "";

    // ========================================================================
    // 문제 3. 문제 2 의 리더를 고쳐서, pageSize 가 몇이든 항상 70,000건이
    //         나오게 만드세요.
    //
    //   조건: customer_id 순 정렬은 업무 요구사항이라 유지해야 합니다.
    //
    //   힌트: 자료구조 선택이 중요합니다. Map.of() 를 쓰면 안 되는 이유를
    //         java.util.Map.of() 의 API 문서에서 확인하세요.
    //
    //   검증: pageSize 를 250, 1000, 1024, 3000 으로 바꿔 가며 돌려서
    //         전부 70,000건이 나오는지 확인하세요.
    // ========================================================================

    @Bean
    public JdbcPagingItemReader<Order> q3Reader(DataSource dataSource) {

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");

        // 여기에 작성: 올바른 정렬 키 설정

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("q3Reader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(q2PageSize)
                .rowMapper(new org.springframework.jdbc.core.DataClassRowMapper<>(Order.class))
                .build();
    }

    /** 여기에 작성: Map.of() 를 쓰면 안 되는 이유 */
    public static final String Q3_WHY_NOT_MAP_OF = "";

    // ========================================================================
    // 문제 4. 커서 리더에 멀티스레드를 붙이면 무슨 일이 벌어질까요?
    //
    //   아래 Step 은 JdbcCursorItemReader 에 스레드 4개를 붙입니다.
    //   ⚠️ 예상을 먼저 적고 실행하세요.
    //
    //   ① 예외가 날까요, 아니면 예외 없이 결과만 이상해질까요?
    //   ② 실행해서 확인하고, 예외라면 어떤 예외인지 적으세요.
    //   ③ 이 Step 을 제대로 멀티스레드화하려면 무엇을 바꿔야 하나요?
    //
    //   실행:
    //     mysql ... -e "TRUNCATE TABLE settlement;"
    //     ./gradlew bootRun --args='--spring.batch.job.name=q4Job'
    //
    //   ⚠️ 여러 번 돌려 보세요. 결과가 매번 같은지도 관찰 대상입니다.
    // ========================================================================

    @Bean
    public Step q4Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                       JdbcCursorItemReader<Order> cursorReader,
                       org.springframework.batch.item.ItemProcessor<Order, Practice.Settlement> settlementProcessor,
                       org.springframework.batch.item.ItemWriter<Practice.Settlement> settlementWriter) {
        return new StepBuilder("q4Step", jobRepository)
                .<Order, Practice.Settlement>chunk(1000, txManager)
                .reader(cursorReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .taskExecutor(new org.springframework.core.task.SimpleAsyncTaskExecutor("q4-"))
                .build();
    }

    /** 여기에 작성: ① 예상 — "EXCEPTION" 또는 "SILENTLY_WRONG" */
    public static final String Q4_PREDICTION = "";

    /** 여기에 작성: ② 실제로 관찰한 결과 */
    public static final String Q4_OBSERVED = "";

    /** 여기에 작성: ③ 제대로 고치는 방법 */
    public static final String Q4_FIX = "";

    // ========================================================================
    // 문제 5. OFFSET 페이징과 키셋 페이징의 마지막 페이지 응답시간을 비교하세요.
    //
    //   배치를 돌리지 않고 mysql CLI 에서 직접 재습니다.
    //
    //   ⚠️ SQL_NO_CACHE 를 반드시 붙이세요. 안 붙이면 두 번째 실행부터
    //      캐시가 걸려 차이가 안 보입니다.
    //
    //   [OFFSET 방식]
    //     SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED'
    //       ORDER BY order_id LIMIT 1000 OFFSET 0;
    //     SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED'
    //       ORDER BY order_id LIMIT 1000 OFFSET 69000;
    //
    //   [키셋 방식]
    //     SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED'
    //       AND order_id > 0 ORDER BY order_id LIMIT 1000;
    //     SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED'
    //       AND order_id > 98570 ORDER BY order_id LIMIT 1000;
    //
    //   ④ 데이터가 700만 건으로 늘면 이 차이는 어떻게 변할까요?
    // ========================================================================

    /** 여기에 작성: OFFSET 0 의 응답시간 (초) */
    public static final double Q5_OFFSET_FIRST = 0.0;

    /** 여기에 작성: OFFSET 69000 의 응답시간 (초) */
    public static final double Q5_OFFSET_LAST = 0.0;

    /** 여기에 작성: 키셋 마지막 페이지의 응답시간 (초) */
    public static final double Q5_KEYSET_LAST = 0.0;

    /** 여기에 작성: ④ 700만 건이면 어떻게 되는가 */
    public static final String Q5_AT_SCALE = "";

    // ========================================================================
    // 문제 6. CSV 헤더가 늘어나면 데이터가 조용히 사라집니다. 재현하고 고치세요.
    //
    //   ① 아래 파일을 만드세요. 주석이 본문 예제보다 한 줄 많습니다.
    //
    //     cat > /tmp/q6-orders.csv <<'CSV'
    //     # 외부 정산 파일 v3
    //     # 2025-07-01 생성 — 컬럼 순서 변경 없음
    //     order_id,customer_id,amount,ordered_at
    //     900001,1,15000.00,2025-06-30 10:00:00
    //     900002,2,23500.00,2025-06-30 10:05:00
    //     900003,3,8700.00,2025-06-30 10:11:00
    //     CSV
    //
    //   ② 아래 리더로 읽으면 몇 건이 나올까요? 예측하고 실행하세요.
    //   ③ 예외가 나나요, 조용히 틀리나요?
    //   ④ linesToSkip 숫자를 3으로 바꾸는 것 말고 더 나은 해법이 있습니다.
    //      그 해법으로 고치세요.
    // ========================================================================

    @Bean
    public FlatFileItemReader<Practice.ExternalOrder> q6Reader() {

        org.springframework.batch.item.file.transform.DelimitedLineTokenizer tokenizer =
                new org.springframework.batch.item.file.transform.DelimitedLineTokenizer();
        tokenizer.setNames("orderId", "customerId", "amount", "orderedAt");

        org.springframework.batch.item.file.mapping.DefaultLineMapper<Practice.ExternalOrder> lineMapper =
                new org.springframework.batch.item.file.mapping.DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fs -> new Practice.ExternalOrder(
                fs.readLong("orderId"), fs.readInt("customerId"), fs.readBigDecimal("amount"),
                java.time.LocalDateTime.parse(fs.readString("orderedAt").replace(' ', 'T'))));

        return new org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder<Practice.ExternalOrder>()
                .name("q6Reader")
                .resource(new org.springframework.core.io.FileSystemResource("/tmp/q6-orders.csv"))
                .encoding("UTF-8")
                .linesToSkip(2)          // ❌ 주석이 2줄로 늘었는데 이대로입니다
                // 여기에 작성: 줄 수에 의존하지 않는 방식으로 고치세요
                .lineMapper(lineMapper)
                .build();
    }

    /** 여기에 작성: ② 고치기 전에 읽히는 건수 */
    public static final int Q6_ROWS_BEFORE_FIX = 0;

    /** 여기에 작성: ③ "EXCEPTION" 또는 "SILENTLY_WRONG" */
    public static final String Q6_FAILURE_MODE = "";

    // ========================================================================
    // 문제 7. 정산 누락을 잡아내는 검증 Tasklet 을 작성하세요.
    //
    //   요구사항:
    //     ① orders 의 COMPLETED 건수와 settlement 건수를 비교
    //     ② 금액 합계도 비교 — 건수가 같아도 금액이 다를 수 있습니다
    //        (중복 처리와 누락이 동시에 일어나면 건수는 우연히 맞습니다)
    //     ③ 불일치하면 Job 전체를 FAILED 로 만들 것
    //
    //   ⚠️ ③ 이 함정입니다. contribution.setExitStatus(ExitStatus.FAILED) 만
    //      호출하면 Step 의 종료 코드만 바뀌고 Job 은 COMPLETED 로 끝납니다.
    //      직접 확인해 보고, 어떻게 해야 Job 이 실패하는지 알아내세요.
    //
    //   검증:
    //     mysql ... -e "TRUNCATE TABLE settlement;"
    //     ./gradlew bootRun --args='--spring.batch.job.name=q7Job'
    //     → brokenSortStep 이 67,207건만 쓰므로 검증 Step 이 잡아내야 합니다.
    // ========================================================================

    static class Q7VerificationTasklet implements Tasklet {

        private final JdbcTemplate jdbc;

        Q7VerificationTasklet(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            // 여기에 작성:
            return RepeatStatus.FINISHED;
        }
    }

    @Bean
    public Step q7Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                       JdbcTemplate jdbcTemplate) {
        return new StepBuilder("q7Step", jobRepository)
                .tasklet(new Q7VerificationTasklet(jdbcTemplate), txManager)
                .build();
    }

    /** 여기에 작성: ③ Job 을 FAILED 로 만들려면? */
    public static final String Q7_HOW_TO_FAIL_JOB = "";

    // ========================================================================
    // 실행용 Job — 수정하지 마세요.
    // ========================================================================

    @Bean
    public Job q7Job(JobRepository jobRepository, Step brokenSortStep, Step q7Step) {
        return new org.springframework.batch.core.job.builder.JobBuilder("q7Job", jobRepository)
                .start(brokenSortStep)
                .next(q7Step)
                .build();
    }
}
