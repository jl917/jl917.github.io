package com.example.batch.step09;

/*
 * ============================================================================
 *  Step 09 — ExecutionContext 와 스코프 : 정답과 해설
 * ============================================================================
 *  문제를 직접 풀어 본 뒤에 여세요.
 * ============================================================================
 */

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStream;
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
import java.util.concurrent.atomic.AtomicLong;

public final class Solution {

    private Solution() {
    }

    // ========================================================================
    // 정답 1.
    //
    //  핵심은 세 가지입니다.
    //
    //  (a) setKeys — 올릴 키를 "명시"해야 합니다. 전체 복사 같은 옵션은 없습니다.
    //      의도적으로 그렇게 설계되어 있습니다. Step Context 에는 프레임워크가
    //      넣은 batch.stepType / batch.taskletType 같은 키도 있는데, 그것까지
    //      Job Context 로 올라가면 재시작 검증이 엉킵니다.
    //
    //  (b) setStatuses — 여기가 이 문제의 진짜 함정입니다.
    //      기본값은 { "COMPLETED" } 뿐입니다. 즉 Step 이 FAILED 로 끝나면
    //      "아무 예외 없이" 승격이 생략됩니다.
    //      그러면 다음 Step 은 jobContext.get("orderCount") 로 null 을 받고,
    //      그것을 long 으로 언박싱하는 순간 전혀 상관없는 자리에서
    //      NullPointerException 이 납니다. 원인을 찾기가 아주 어렵습니다.
    //      → 부분 실패에서도 값이 필요하면 "FAILED" 를 명시하세요.
    //
    //  (c) setStrict — true 로 두면 지정한 키가 Step Context 에 없을 때
    //      IllegalArgumentException 을 던집니다. 조건부로만 넣는 키에는
    //      절대 쓰면 안 됩니다. 반대로 "반드시 있어야 하는" 키라면
    //      strict=true 가 조용한 null 보다 낫습니다.
    //
    //  (d) 리스너는 Job 이 아니라 Step 에 붙입니다.
    //      ExecutionContextPromotionListener 는 StepExecutionListener 이고,
    //      afterStep 콜백에서 동작하기 때문입니다.
    //      JobBuilder.listener(...) 에 넣으면 아무 일도 일어나지 않습니다.
    // ========================================================================
    @Configuration
    static class A1 {

        private static final Logger log = LoggerFactory.getLogger(A1.class);

        @Bean
        ExecutionContextPromotionListener a1Promotion() {
            ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
            listener.setKeys(new String[]{"orderCount", "targetDate"});
            listener.setStatuses(new String[]{"COMPLETED", "FAILED"});  // ← (b)
            listener.setStrict(false);                                  // ← (c)
            return listener;
        }

        @Bean
        Step a1CountStep(JobRepository jobRepository, PlatformTransactionManager tm,
                         JdbcTemplate jdbcTemplate,
                         ExecutionContextPromotionListener a1Promotion) {
            return new StepBuilder("a1CountStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        Long cnt = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'", Long.class);
                        ExecutionContext ctx = chunkContext.getStepContext()
                                .getStepExecution().getExecutionContext();
                        ctx.putLong("orderCount", cnt == null ? 0L : cnt);
                        ctx.putString("targetDate", "2025-03-01");
                        log.info("[a1CountStep] orderCount={}", cnt);   // 70000
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .listener(a1Promotion)     // ← (d) Step 에 부착
                    .build();
        }

        @Bean
        Step a1UseStep(JobRepository jobRepository, PlatformTransactionManager tm) {
            return new StepBuilder("a1UseStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        ExecutionContext jobCtx = chunkContext.getStepContext()
                                .getStepExecution().getJobExecution().getExecutionContext();

                        // 방어적으로 읽습니다. containsKey 검사 없이 getLong 하면
                        // 키가 없을 때 0 이 반환되어 "0건 처리" 로 조용히 틀립니다.
                        if (!jobCtx.containsKey("orderCount")) {
                            throw new IllegalStateException("orderCount 가 승격되지 않았습니다");
                        }
                        log.info("[a1UseStep] orderCount = {}", jobCtx.getLong("orderCount"));
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Job a1Job(JobRepository jobRepository, Step a1CountStep, Step a1UseStep) {
            return new JobBuilder("a1Job", jobRepository)
                    .start(a1CountStep).next(a1UseStep).build();
        }
    }

    // ========================================================================
    // 정답 2.
    //
    //  @StepScope 가 있어야 #{jobParameters['date']} 가 해석됩니다.
    //  그리고 리턴 타입은 JdbcPagingItemReader<Order> — 구현체여야 합니다.
    //  (인터페이스로 쓰면 정답 5 의 문제가 그대로 재현됩니다.)
    //
    //  WHERE 절에는 named parameter(:date)를 쓰고, 실제 값은
    //  setParameterValues 로 바인딩합니다.
    //  문자열을 직접 이어 붙이지 마세요 —
    //    provider.setWhereClause("... = '" + date + "'")
    //  는 SQL 인젝션이자, 바인드 변수를 못 써서 MySQL 쿼리 캐시/플랜 재사용도
    //  못 하게 만듭니다.
    //
    //  검증: READ_COUNT 가 389 여야 합니다.
    //    하루 주문 약 555건 x COMPLETED 70% = 389건.
    //    이 숫자는 프로젝트 시드가 결정론적이라 항상 같습니다.
    // ========================================================================
    @Configuration
    static class A2 {

        @Bean
        @StepScope                                     // ← 정답의 절반
        JdbcPagingItemReader<Order> a2Reader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status = 'COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKeys(Map.of("order_id", org.springframework.batch.item.database.Order.ASCENDING));

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("a2Reader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider);
            reader.setParameterValues(Map.of("date", date));
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }
    }

    /*
     * ========================================================================
     * 정답 3.
     *
     *  (a) 예외
     *      org.springframework.beans.factory.BeanExpressionException:
     *        Expression parsing failed
     *      Caused by: org.springframework.expression.spel.SpelEvaluationException:
     *        EL1008E: Property or field 'jobParameters' cannot be found on object
     *        of type 'org.springframework.beans.factory.config.BeanExpressionContext'
     *
     *      기억할 문자열은 "EL1008E" 와 "jobParameters cannot be found" 입니다.
     *
     *  (b) 왜 기동 시점인가
     *      @StepScope 가 없으면 그 빈은 싱글턴입니다. 싱글턴은 애플리케이션
     *      컨텍스트가 refresh 될 때, 즉 "Job 이 시작되기 훨씬 전"에 생성됩니다.
     *      그 시점의 SpEL 평가 루트는 BeanExpressionContext 이고,
     *      거기에는 jobParameters 라는 프로퍼티가 존재하지 않습니다.
     *
     *      jobParameters / jobExecutionContext / stepExecutionContext 는
     *      StepScope · JobScope 가 빈을 만들 때 SpEL 컨텍스트에 등록해 주는
     *      것들입니다. 스코프가 없으면 등록하는 주체 자체가 없습니다.
     *
     *      타임라인:
     *        [애플리케이션 기동] 싱글턴 생성 ── jobParameters 없음 → EL1008E
     *        [Job 실행]         파라미터 확정
     *        [Step 시작]        StepScope 가 빈 생성 ── 여기서는 있음
     *
     *  (c) 고친 코드 = A2 와 동일합니다. @StepScope 한 줄을 붙이면 됩니다.
     *
     *  참고 — 어떤 스코프가 어떤 표현식을 볼 수 있는가
     *
     *    표현식                          싱글턴   @JobScope   @StepScope
     *    #{jobParameters[...]}             X         O            O
     *    #{jobExecutionContext[...]}       X         O            O
     *    #{stepExecutionContext[...]}      X         X            O
     *    #{stepExecution...}               X         X            O
     *    #{jobExecution...}                X         O            O
     *
     *  @JobScope 빈에 #{stepExecutionContext[...]} 를 쓰면 역시 EL1008E 입니다.
     *  당연합니다 — JobScope 빈이 만들어지는 시점에는 아직 StepExecution 이
     *  없기 때문입니다.
     *
     *  마지막으로, 이 에러는 "착한 에러"입니다. 애플리케이션이 아예 뜨지 않아
     *  100% 발견됩니다. 정답 5 의 함정은 그렇지 않습니다.
     * ========================================================================
     */

    // ========================================================================
    // 정답 4.
    //
    //  BATCH_JOB_EXECUTION_CONTEXT 에는 컨텍스트 컬럼이 두 개 있습니다.
    //
    //    SHORT_CONTEXT       VARCHAR(2500)  항상 채워짐. 넘치면 앞부분만 잘려 들어감
    //    SERIALIZED_CONTEXT  TEXT           2500자를 "넘을 때만" 채워짐
    //
    //  JdbcExecutionContextDao 는 읽을 때 SERIALIZED_CONTEXT 가 있으면 그것을,
    //  없으면 SHORT_CONTEXT 를 씁니다. 따라서 사람이 조회할 때도 같은 규칙을
    //  따라야 "전문"을 보게 됩니다.
    //
    //  SERIALIZED_CONTEXT 만 보고 "비어 있네" 하는 실수가 정말 흔합니다.
    //  반대로 SHORT_CONTEXT 만 보면 큰 컨텍스트가 잘린 채로 보입니다.
    // ========================================================================
    static final String A4_SQL = """
            SELECT JOB_EXECUTION_ID,
                   COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) AS job_context
              FROM BATCH_JOB_EXECUTION_CONTEXT
             ORDER BY JOB_EXECUTION_ID DESC
             LIMIT 1\\G

            -- 결과 예시
            -- *************************** 1. row ***************************
            -- JOB_EXECUTION_ID: 12
            --      job_context: {"@class":"java.util.HashMap",
            --                    "completedCount":["java.lang.Long",70000],
            --                    "settleDate":"2025-03-01"}
            --
            -- 참고 1. 값에 타입이 함께 붙는 이유는 Jackson default typing 때문입니다.
            --         덕분에 역직렬화 때 Long 이 Integer 로 바뀌는 사고가 없습니다.
            -- 참고 2. Spring Batch 4.x 는 자바 직렬화(BLOB)라 사람이 읽을 수 없었습니다.
            --         5.x 는 Jackson2ExecutionContextStringSerializer 가 기본입니다.
            """;

    // ========================================================================
    // 정답 5.
    //
    //  (a) 무엇이 조용히 망가졌나 — 재시작입니다.
    //
    //      @StepScope 는 @Scope(value="step", proxyMode=ScopedProxyMode.TARGET_CLASS)
    //      입니다. TARGET_CLASS 프록시가 "무엇을 구현할지"는 @Bean 메서드의
    //      리턴 타입이 결정합니다. 리턴 타입이 ItemReader<Order> 이면
    //      프록시도 ItemReader 만 구현하고 ItemStream 은 구현하지 않습니다.
    //
    //      SimpleStepBuilder.reader(r) 는 내부적으로
    //          if (r instanceof ItemStream) registerStream((ItemStream) r);
    //      를 하는데, 위 프록시는 이 검사를 통과하지 못합니다.
    //      → open() / update() / close() 가 한 번도 호출되지 않습니다.
    //
    //      JdbcCursorItemReader 였다면 open() 이 없어 ResultSet 이 안 열리므로
    //      ReaderNotOpenException 으로 시끄럽게 죽습니다. 운이 좋은 경우입니다.
    //      그런데 JdbcPagingItemReader 는 open() 없이도 첫 페이지를 그냥 조회해
    //      옵니다. 즉 70,000건을 전부 정상 처리하고 COMPLETED 로 끝납니다.
    //      아무 에러도 없습니다.
    //
    //      사라진 것은 update() 입니다. 매 청크 커밋마다
    //      "q5Reader.read.count" 를 컨텍스트에 적어 두던 그 콜백입니다.
    //      평소에는 아무 문제가 없다가, 장애로 재시작하는 날 처음부터 다시
    //      읽습니다. 이미 정산된 건을 다시 INSERT 하려다
    //      settlement.uk_settlement_order 에 걸려 DuplicateKeyException 이 나거나,
    //      UNIQUE 가 없었다면 정산이 두 배로 찍힙니다.
    //
    //  (b) 확인용 SQL — read.count 키가 남았는지 봅니다.
    //
    //      SELECT COALESCE(SERIALIZED_CONTEXT, SHORT_CONTEXT) AS ctx
    //        FROM BATCH_STEP_EXECUTION_CONTEXT
    //       ORDER BY STEP_EXECUTION_ID DESC LIMIT 1\G
    //
    //      정상이면 "q5Reader.read.count":70000 이 보입니다.
    //      깨졌으면 batch.stepType / batch.taskletType 두 개만 있습니다.
    //
    //      운영 배포 전 체크리스트에 이 한 줄을 넣으세요.
    //
    //  (c) 해법 두 가지
    // ========================================================================
    @Configuration
    static class A5 {

        /**
         * 해법 1 (권장) — 리턴 타입을 구현체로 선언합니다.
         * 프록시가 JdbcPagingItemReader 를 상속하므로 ItemStream 도 함께
         * 구현하게 되고, reader() 가 알아서 registerStream 합니다.
         * 코드에 아무 추가 규칙이 필요 없다는 것이 이 방법의 가치입니다.
         */
        @Bean
        @StepScope
        JdbcPagingItemReader<Order> a5Reader(
                DataSource dataSource,
                @Value("#{jobParameters['date']}") String date) {

            MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
            provider.setSelectClause("SELECT order_id, customer_id, amount, status, ordered_at");
            provider.setFromClause("FROM orders");
            provider.setWhereClause("WHERE status='COMPLETED' AND DATE(ordered_at) = :date");
            provider.setSortKeys(Map.of("order_id", org.springframework.batch.item.database.Order.ASCENDING));

            JdbcPagingItemReader<Order> reader = new JdbcPagingItemReader<>();
            reader.setName("a5Reader");
            reader.setDataSource(dataSource);
            reader.setQueryProvider(provider);
            reader.setParameterValues(Map.of("date", date));
            reader.setPageSize(1000);
            reader.setRowMapper(new DataClassRowMapper<>(Order.class));
            return reader;
        }

        @Bean
        Step a5Step(JobRepository jobRepository, PlatformTransactionManager tm,
                    JdbcPagingItemReader<Order> a5Reader,
                    ItemProcessor<Order, Settlement> a5Processor,
                    JdbcBatchItemWriter<Settlement> settlementWriter) {
            return new StepBuilder("a5Step", jobRepository)
                    .<Order, Settlement>chunk(1000, tm)
                    .reader(a5Reader)          // ItemStream 이 자동 등록됩니다
                    .processor(a5Processor)
                    .writer(settlementWriter)
                    .build();
        }

        /**
         * 해법 2 (차선책) — .stream(...) 으로 명시 등록합니다.
         *
         * 왜 차선책인가:
         *   1. 등록을 잊기 쉽습니다. 잊어도 에러가 안 나므로 리뷰에서도 안 잡힙니다.
         *   2. ItemStream 으로 캐스팅해야 하는데, 그러려면 결국 구현체가
         *      ItemStream 임을 알고 있어야 합니다. 그럴 바에는 해법 1 이 낫습니다.
         *   3. Reader 를 다른 Step 에서 재사용할 때 그 Step 에서도 똑같이
         *      .stream(...) 을 써야 합니다. 규칙이 전파되어야 하는 설계는 약합니다.
         *
         * 인터페이스 리턴이 불가피한 경우(예: 조건에 따라 서로 다른 구현체를
         * 반환해야 할 때)에만 쓰세요.
         */
        Step a5StepAlternative(JobRepository jobRepository, PlatformTransactionManager tm,
                               org.springframework.batch.item.ItemReader<Order> leaky,
                               ItemProcessor<Order, Settlement> a5Processor,
                               JdbcBatchItemWriter<Settlement> settlementWriter) {
            return new StepBuilder("a5StepAlt", jobRepository)
                    .<Order, Settlement>chunk(1000, tm)
                    .reader(leaky)
                    .processor(a5Processor)
                    .writer(settlementWriter)
                    .stream((ItemStream) leaky)     // ← 명시 등록
                    .build();
        }
    }

    /*
     * ========================================================================
     * 정답 6.
     *
     *  판단 기준은 한 줄로 요약됩니다.
     *
     *    stepExecutionContext 가 필요하다        → @StepScope
     *    Job 전체에서 인스턴스가 하나여야 한다     → @JobScope
     *    실행 시점 값이 전혀 필요 없다            → 싱글턴 (스코프 없음)
     *
     *  (1) jobParameters['inputFile'] 로 경로를 받는 FlatFileItemReader
     *      답: @StepScope
     *      이유: jobParameters 를 SpEL 로 받아야 하므로 스코프가 필요합니다.
     *            @JobScope 로도 파라미터 자체는 읽히지만, Reader 는
     *            ItemStream 상태(읽은 줄 수)를 StepExecution 단위로 관리해야
     *            하므로 @StepScope 가 맞습니다.
     *            그리고 리턴 타입은 반드시 FlatFileItemReader<T> 로 —
     *            ItemReader<T> 로 쓰면 정답 5 의 함정에 그대로 빠집니다.
     *            FlatFileItemReader 는 open() 에서 파일을 열기 때문에
     *            이 경우엔 ReaderNotOpenException 으로 시끄럽게 죽습니다.
     *
     *  (2) stepExecutionContext['minId'] / ['maxId'] 를 받는 파티션 Reader
     *      답: @StepScope (다른 선택지가 없습니다)
     *      이유: stepExecutionContext 는 @StepScope 에서만 해석됩니다.
     *            @JobScope 를 붙이면 EL1008E 로 기동에 실패합니다.
     *            파티셔닝은 파티션마다 별도의 StepExecution 을 만들고 각각의
     *            Step Context 에 minId/maxId 를 넣어 주는 구조이므로,
     *            "StepExecution 마다 새 인스턴스"라는 @StepScope 의 성질이
     *            그대로 요구사항입니다. (Step 13 에서 다룹니다)
     *
     *  (3) Job 전체의 처리 건수를 누적하는 StatsCollector
     *      답: @JobScope
     *      이유: JobExecution 하나당 인스턴스가 하나여야 누적이 성립합니다.
     *            싱글턴으로 두면 애플리케이션이 여러 Job 을 돌릴 때 값이
     *            섞이고, 특히 같은 Job 을 두 번 실행하면 이전 실행의 숫자가
     *            남습니다.
     *
     *      @StepScope 를 고르면 벌어지는 일:
     *            Step 이 시작될 때마다 새 인스턴스가 만들어집니다.
     *            즉 누적 카운터가 매 Step 마다 0 으로 초기화됩니다.
     *            마지막 Step 의 건수만 리포트되는데, 그 값도 "그럴듯한 숫자"라
     *            아무도 이상함을 눈치채지 못합니다. 예외도 로그도 없습니다.
     *            이것이 이 코스가 계속 말하는 "에러 없이 조용히 틀리는 코드"의
     *            전형입니다.
     *
     *      더 나은 대안:
     *            누적값을 빈 필드가 아니라 ExecutionContext 에 두는 것입니다.
     *            그래야 DB 에 남고, 재시작 후에도 복원됩니다.
     *            빈 필드는 스코프가 무엇이든 프로세스가 죽으면 사라집니다.
     * ========================================================================
     */
    @Configuration
    static class A6 {

        /** (3) 의 정답 형태 — @JobScope 로 JobExecution 당 하나. */
        @Bean
        @JobScope
        StatsCollector statsCollector(
                @Value("#{jobExecution.jobInstance.jobName}") String jobName) {
            return new StatsCollector(jobName);
        }
    }

    static class StatsCollector {
        private final String jobName;
        private final AtomicLong total = new AtomicLong();

        StatsCollector(String jobName) {
            this.jobName = jobName;
        }

        void add(long n) {
            total.addAndGet(n);
        }

        long total() {
            return total.get();
        }

        String jobName() {
            return jobName;
        }
    }
}
