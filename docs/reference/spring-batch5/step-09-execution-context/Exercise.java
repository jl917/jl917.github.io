package com.example.batch.step09;

/*
 * ============================================================================
 *  Step 09 — ExecutionContext 와 스코프 : 연습문제 (6문제)
 * ============================================================================
 *
 *  각 문제의 "// 여기에 작성:" 자리를 채우세요. 정답은 Solution.java 입니다.
 *
 *  실행 전 준비
 *    mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "TRUNCATE TABLE settlement;"
 * ============================================================================
 */

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

public final class Exercise {

    private Exercise() {
    }

    // ========================================================================
    // 문제 1.
    //   countStep 이 "COMPLETED 주문 건수"와 "정산 대상 일자"를 Step Context 에
    //   넣습니다. 다음 Step(useStep) 이 그 값을 읽을 수 있게 만드세요.
    //
    //   요구사항
    //     (a) 승격 리스너 빈을 완성할 것 — 올릴 키는 "orderCount", "targetDate"
    //     (b) Step 이 FAILED 로 끝나도 승격되도록 상태를 명시할 것
    //     (c) 리스너를 올바른 곳(Job? Step?)에 부착할 것
    // ========================================================================
    @Configuration
    static class Q1 {

        private static final Logger log = LoggerFactory.getLogger(Q1.class);

        @Bean
        ExecutionContextPromotionListener q1Promotion() {
            ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
            // 여기에 작성: setKeys / setStatuses / setStrict 를 지정하세요
            return listener;
        }

        @Bean
        Step q1CountStep(JobRepository jobRepository, PlatformTransactionManager tm,
                         JdbcTemplate jdbcTemplate,
                         ExecutionContextPromotionListener q1Promotion) {
            return new StepBuilder("q1CountStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        Long cnt = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'", Long.class);
                        ExecutionContext ctx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        ctx.putLong("orderCount", cnt == null ? 0L : cnt);
                        ctx.putString("targetDate", "2025-03-01");
                        log.info("[q1CountStep] orderCount={}", cnt);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    // 여기에 작성: 승격 리스너를 부착하세요
                    .build();
        }

        @Bean
        Step q1UseStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("q1UseStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        // 여기에 작성: Job ExecutionContext 에서 orderCount 를 꺼내 로그로 찍으세요
                        // 기대 출력: [q1UseStep] orderCount = 70000
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Job q1Job(JobRepository jobRepository, Step q1CountStep, Step q1UseStep) {
            return new JobBuilder("q1Job", jobRepository)
                    .start(q1CountStep).next(q1UseStep).build();
        }
    }

    // ========================================================================
    // 문제 2.
    //   jobParameters 의 'date' 로 하루치 COMPLETED 주문만 읽는 Reader 를 완성하세요.
    //
    //   확인 방법
    //     ./gradlew bootRun --args='--spring.batch.job.name=q2Job date=2025-03-01'
    //     → READ_COUNT 가 389 여야 정답입니다.
    // ========================================================================
    @Configuration
    static class Q2 {

        @Bean
        // 여기에 작성: 이 Reader 가 jobParameters 를 볼 수 있게 하는 애노테이션
        JdbcPagingItemReader<Order> q2Reader(
                DataSource dataSource,
                /* 여기에 작성: date 파라미터를 받는 @Value 표현식 */ String date) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            // 여기에 작성: status 와 날짜 조건을 담은 WHERE 절 (named parameter :date 사용)
            provider.setSortKeys(Map.of("order_id", org.springframework.batch.item.database.Order.ASCENDING));

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("q2Reader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider);
            // 여기에 작성: named parameter 값 바인딩
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }
    }

    // ========================================================================
    // 문제 3.  [진단 문제 — 코드를 고치기 전에 먼저 실행해 보세요]
    //
    //   아래 설정을 활성화하면 애플리케이션이 아예 뜨지 않습니다.
    //
    //   (a) 어떤 예외가 나는지 클래스명과 메시지 코드를 적으세요.
    //   (b) 왜 "Job 실행 시점"이 아니라 "기동 시점"에 터지는지 설명하세요.
    //   (c) 코드를 고치세요.
    // ========================================================================
    // @Configuration      ← 재현할 때만 주석을 푸세요
    static class Q3Broken {

        @Bean
        JdbcPagingItemReader<Order> q3Reader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status='COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKeys(Map.of("order_id", org.springframework.batch.item.database.Order.ASCENDING));

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("q3Reader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider);
            reader.setParameterValues(Map.of("date", date));
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        // (a) 예외 클래스 / 메시지 코드
        //     여기에 작성:
        //
        // (b) 왜 기동 시점인가
        //     여기에 작성:
        //
        // (c) 고친 코드
        //     여기에 작성:
    }

    // ========================================================================
    // 문제 4.  [SQL 문제]
    //
    //   가장 최근 JobExecution 의 Job ExecutionContext 에 저장된 JSON 을
    //   "잘리지 않은 전문"으로 조회하는 SQL 을 쓰세요.
    //
    //   힌트: 이 테이블에는 컨텍스트 컬럼이 두 개 있고, 작은 컨텍스트는
    //         한쪽이 NULL 입니다.
    // ========================================================================
    static final String Q4_SQL = """
            -- 여기에 작성:
            """;

    // ========================================================================
    // 문제 5.
    //   아래 Reader 는 실행하면 "정상적으로 성공"합니다. 그런데 위험합니다.
    //
    //   (a) 무엇이 조용히 망가져 있습니까?
    //   (b) 그것을 SQL 로 확인하는 방법을 쓰세요.
    //   (c) 두 가지 방법으로 고치세요. 어느 쪽이 권장인지도 적으세요.
    // ========================================================================
    @Configuration
    static class Q5 {

        @Bean
        @StepScope
        ItemReader<Order> q5LeakyReader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status='COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKeys(Map.of("order_id", org.springframework.batch.item.database.Order.ASCENDING));

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("q5Reader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider);
            reader.setParameterValues(Map.of("date", date));
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        // (a) 무엇이 망가졌나
        //     여기에 작성:
        //
        // (b) 확인용 SQL
        //     여기에 작성:
        //
        // (c) 해법 1 (권장) / 해법 2
        //     여기에 작성:

        @Bean
        Step q5Step(JobRepository jobRepository, PlatformTransactionManager tm,
                    ItemReader<Order> q5LeakyReader,
                    ItemProcessor<Order, Settlement> q5Processor,
                    JdbcBatchItemWriter<Settlement> settlementWriter) {
            return new StepBuilder("q5Step", jobRepository)
                    .<Order, Settlement>chunk(1000, tm)
                    .reader(q5LeakyReader)
                    .processor(q5Processor)
                    .writer(settlementWriter)
                    // 여기에 작성: (해법 2 를 택한다면) 필요한 한 줄
                    .build();
        }
    }

    // ========================================================================
    // 문제 6.  [판단 문제 — 코드 없음]
    //
    //   아래 세 가지 빈에 각각 무슨 스코프를 붙여야 합니까?
    //   (싱글턴 / @JobScope / @StepScope 중 하나) 그리고 그 이유를 쓰세요.
    //
    //   (1) jobParameters['inputFile'] 로 경로를 받는 FlatFileItemReader
    //       답:    여기에 작성:
    //       이유:  여기에 작성:
    //
    //   (2) stepExecutionContext['minId'] / ['maxId'] 로 구간을 받는
    //       파티션 전용 JdbcPagingItemReader
    //       답:    여기에 작성:
    //       이유:  여기에 작성:
    //
    //   (3) Job 이 도는 동안 전체 Step 의 처리 건수를 누적해 마지막에
    //       한 번 리포트하는 StatsCollector
    //       답:    여기에 작성:
    //       이유:  여기에 작성:
    //       주의:  @StepScope 를 고르면 어떤 일이 벌어집니까?
    //              여기에 작성:
    // ========================================================================
}
