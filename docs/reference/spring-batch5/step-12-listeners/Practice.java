package com.example.batch.step12;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterChunk;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 12 — 리스너 : 본문 12-0 ~ 12-10 의 모든 예제.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 실행 방법
 *
 *   ./gradlew bootRun --args='--spring.batch.job.name=settlementListenerJob date=2025-03-01'
 *
 * 바깥 클래스에 @Configuration 이 없습니다. 지금 실습할 static class 하나만
 * @Configuration 주석을 풀고 나머지는 주석 처리한 채로 돌리십시오.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 이 스텝의 실습 원칙
 *
 *   **"코드를 썼으니 돌겠지"를 절대 가정하지 마십시오.**
 *
 *   리스너는 등록에 실패해도, 메서드 시그니처가 틀려도 조용합니다.
 *   예외도 경고도 없습니다. 그래서 매번 로그가 실제로 찍히는지
 *   눈으로 확인해야 합니다. 이것이 이 스텝의 진짜 학습 목표입니다.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);

    // =====================================================================
    // [12-0] 실습 준비 — 불량 데이터 심기
    //
    // ⚠️ 이것을 먼저 실행하지 않으면 SkipListener 가 한 번도 호출되지
    //    않아 12-6 이 통째로 빈 로그가 됩니다. 그리고 그게 "리스너가
    //    안 붙었나?" 하는 착각을 부릅니다.
    // =====================================================================

    public static class PlantBadData {

        public static final String PLANT_SQL = """
                UPDATE orders SET amount = -1.00 WHERE order_id %% 1000 = 0;
                TRUNCATE TABLE settlement;

                -- 검증: target=70000, bad_amount=100
                SELECT (SELECT COUNT(*) FROM orders WHERE status='COMPLETED') AS target,
                       (SELECT COUNT(*) FROM orders WHERE amount < 0)         AS bad_amount;
                """;
    }

    // =====================================================================
    // [12-2] JobExecutionListener — 가장 바깥
    // =====================================================================

    public static class SettlementJobListener implements JobExecutionListener {

        private static final Logger log =
                LoggerFactory.getLogger(SettlementJobListener.class);

        @Override
        public void beforeJob(JobExecution jobExecution) {
            log.info(">>> 정산 배치 시작. 파라미터={}", jobExecution.getJobParameters());
        }

        @Override
        public void afterJob(JobExecution jobExecution) {
            log.info(">>> 정산 배치 종료. status={}, 소요={}ms",
                    jobExecution.getStatus(),
                    Duration.between(jobExecution.getStartTime(),
                            jobExecution.getEndTime()).toMillis());

            // ⚠️ afterJob 은 실패해도 호출됩니다.
            //    상태를 분기하지 않으면 "배치 완료" 알림이 실패 시에도 갑니다.
            if (jobExecution.getStatus() == BatchStatus.FAILED) {
                log.error(">>> 정산 배치 실패! 원인={}",
                        jobExecution.getAllFailureExceptions());
            }
        }
    }

    // =====================================================================
    // [12-3] StepExecutionListener — ExitStatus 를 바꿀 수 있는 유일한 자리
    // =====================================================================

    public static class SettlementStepListener implements StepExecutionListener {

        private static final Logger log =
                LoggerFactory.getLogger(SettlementStepListener.class);

        @Override
        public void beforeStep(StepExecution stepExecution) {
            log.info(">>> Step 시작: {}", stepExecution.getStepName());
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            long written = stepExecution.getWriteCount();
            log.info(">>> Step 종료: read={}, write={}, filter={}, skip={}",
                    stepExecution.getReadCount(), written,
                    stepExecution.getFilterCount(), stepExecution.getSkipCount());

            if (written == 0) {
                return new ExitStatus("NOTHING_SETTLED");   // Step 10 의 분기용
            }

            // ⚠️ return ExitStatus.COMPLETED 로 하지 마십시오.
            //    다른 리스너가 설정한 커스텀 ExitStatus 를 덮어씁니다.
            //    값을 바꿀 의도가 없으면 기존 값을 그대로 돌려줍니다.
            return stepExecution.getExitStatus();
        }
    }

    // =====================================================================
    // [12-4] ChunkListener — 트랜잭션 경계 안쪽
    //
    // ⚠️ afterChunk 는 "커밋 전" 입니다.
    //    여기서 DB 에 쓰면 청크 트랜잭션에 포함되어, 롤백되면 함께
    //    사라집니다. 진행 로그를 남기려던 목적과 정반대가 됩니다.
    // =====================================================================

    public static class LoggingChunkListener implements ChunkListener {

        private static final Logger log =
                LoggerFactory.getLogger(LoggingChunkListener.class);

        private final AtomicInteger chunkNo = new AtomicInteger(0);

        @Override
        public void beforeChunk(ChunkContext context) {
            // 이 시점에 이미 트랜잭션이 시작되어 있습니다.
        }

        @Override
        public void afterChunk(ChunkContext context) {
            int n = chunkNo.incrementAndGet();
            // 70,000번이 아니라 7번만 찍습니다. 진행률 로깅의 올바른 형태입니다.
            if (n % 10 == 0) {
                log.info(">>> 청크 {} 완료", n);
            }
        }

        @Override
        public void afterChunkError(ChunkContext context) {
            // ⚠️ 이 시점에는 트랜잭션이 이미 롤백 표시된 상태입니다.
            //    여기서 DB 에 쓰려고 하면 UnexpectedRollbackException 이 납니다.
            log.warn(">>> 청크 실패. 롤백됩니다. {}",
                    context.getStepContext().getStepExecution().getSummary());
        }
    }

    // =====================================================================
    // [12-5] Item 레벨 리스너 — 성능 주의
    //
    // ⚠️ 아래 log.info 는 일부러 주석 처리해 두었습니다.
    //    주석을 풀면 70,000줄이 찍혀 6.108초짜리 배치가 27.310초가 됩니다.
    //    성능 실측을 재현할 때만 켜고, 그 외에는 꺼 두십시오.
    //
    //    | 구성 | 소요 | 배수 |
    //    |---|---|---|
    //    | 리스너 없음 | 6.108초 | 1.00배 |
    //    | afterRead 에 log.debug (레벨 꺼짐) | 6.402초 | 1.05배 |
    //    | afterRead 에 log.info | 27.310초 | 4.47배 |
    //    | afterRead 에 log.info + 문자열 concat | 31.884초 | 5.22배 |
    // =====================================================================

    public static class ItemLevelListener
            implements ItemReadListener<Order>, ItemProcessListener<Order, Settlement> {

        private static final Logger log =
                LoggerFactory.getLogger(ItemLevelListener.class);

        @Override
        public void afterRead(Order item) {
            // log.info(">>> 읽음: order_id={}", item.order_id());   // ← 4.47배 느려짐
        }

        @Override
        public void onReadError(Exception ex) {
            log.error("읽기 실패", ex);
        }

        @Override
        public void afterProcess(Order item, Settlement result) {
            if (result == null) {
                log.debug("필터링됨: order_id={}", item.order_id());
            }
        }

        @Override
        public void onProcessError(Order item, Exception e) {
            // ⚠️ 이것은 트랜잭션 "안" 입니다.
            //    여기서 DB 에 기록하면 청크와 함께 롤백됩니다.
            //    불량 데이터 기록은 SkipListener 에서 하십시오 — 연습문제 3.
            log.warn("처리 실패: order_id={}, {}", item.order_id(), e.getMessage());
        }
    }

    // =====================================================================
    // [12-6] SkipListener — 커밋 "후" 에 호출된다
    //
    // 이 "커밋 후" 성질이 결정적으로 유용합니다.
    // 불량 데이터 기록은 청크가 롤백되어도 남아야 하기 때문입니다.
    // =====================================================================

    public static class SettlementSkipListener implements SkipListener<Order, Settlement> {

        private static final Logger log =
                LoggerFactory.getLogger(SettlementSkipListener.class);

        private final JdbcTemplate jdbcTemplate;

        public SettlementSkipListener(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public void onSkipInRead(Throwable t) {
            log.warn("[SKIP-READ] {}", t.getMessage());
        }

        @Override
        public void onSkipInProcess(Order item, Throwable t) {
            log.warn("[SKIP-PROCESS] order_id={}, 사유={}",
                    item.order_id(), t.getMessage());

            // 이 시점은 청크 트랜잭션 "밖" 이므로, 청크가 롤백되어도
            // 이 기록은 남습니다. 이것이 SkipListener 를 쓰는 이유입니다.
            jdbcTemplate.update("""
                    INSERT INTO s12_bad_order (order_id, phase, reason, occurred_at)
                    VALUES (?, 'PROCESS', ?, NOW())
                    """, item.order_id(), t.getMessage());
        }

        @Override
        public void onSkipInWrite(Settlement item, Throwable t) {
            log.warn("[SKIP-WRITE] order_id={}, 사유={}", item.orderId(), t.getMessage());
            jdbcTemplate.update("""
                    INSERT INTO s12_bad_order (order_id, phase, reason, occurred_at)
                    VALUES (?, 'WRITE', ?, NOW())
                    """, item.orderId(), t.getMessage());
        }
    }

    // =====================================================================
    // [12-7] 애너테이션 방식 — 그리고 조용히 등록되지 않는 함정
    //
    // 아래 두 클래스는 둘 다 컴파일되고 둘 다 예외 없이 실행됩니다.
    // 차이는 로그가 찍히느냐 아니냐뿐입니다. 이 대조가 함정의 전부입니다.
    // =====================================================================

    /** 정상 — 시그니처가 맞습니다. 로그가 찍힙니다. */
    public static class AnnotatedListener {

        private static final Logger log =
                LoggerFactory.getLogger(AnnotatedListener.class);

        @BeforeStep
        public void before(StepExecution stepExecution) {
            log.info(">>> [애너테이션] Step 시작: {}", stepExecution.getStepName());
        }

        @AfterStep
        public ExitStatus after(StepExecution stepExecution) {
            log.info(">>> [애너테이션] Step 종료");
            return stepExecution.getExitStatus();
        }

        @AfterChunk
        public void afterChunk(ChunkContext context) {
            // ChunkContext 가 올바른 인자 타입입니다.
        }
    }

    /**
     * ⚠️ 일부러 틀린 버전. 고치지 마십시오.
     *
     * 컴파일됩니다. 등록됩니다. 예외도 안 납니다.
     * 그런데 **호출되지 않습니다.** 로그가 하나도 안 찍힙니다.
     *
     * 세 가지 "조용한 실패" 패턴이 들어 있습니다.
     */
    public static class BrokenAnnotatedListener {

        private static final Logger log =
                LoggerFactory.getLogger(BrokenAnnotatedListener.class);

        /** ① 인자 누락 — StepExecution 을 받아야 합니다. */
        @BeforeStep
        public void before() {
            log.info(">>> 이 줄은 절대 찍히지 않습니다 (인자 누락)");
        }

        /** ② 인자 타입 불일치 — ChunkContext 여야 합니다. */
        @AfterChunk
        public void afterChunk(StepExecution stepExecution) {
            log.info(">>> 이 줄도 찍히지 않습니다 (타입 불일치)");
        }

        // ③ 애너테이션 오타는 아예 컴파일이 안 되므로 여기 넣을 수 없지만,
        //    @BeforeStep 대신 스프링의 다른 @Before 계열을 잘못 import 하면
        //    컴파일은 되고 무시됩니다. import 문을 항상 확인하십시오.
    }

    // =====================================================================
    // [12-8] 리스너에서 예외를 던지면 — 리스너마다 다르다
    //
    // ★ 이 절이 이 스텝의 핵심입니다.
    //
    // ⚠️ 아래 네 개는 일부러 예외를 던지는 코드입니다.
    //    **하나씩만** 활성화해 표의 아홉 줄을 직접 채워 보십시오.
    //    네 개를 동시에 켜면 어느 것 때문에 죽었는지 알 수 없습니다.
    //
    //  | 리스너 | 예외를 던지면 | Job 최종 상태 |
    //  |---|---|---|
    //  | beforeJob            | Job 즉시 실패        | FAILED     |
    //  | afterJob             | **삼켜짐**           | COMPLETED  |
    //  | beforeStep           | Step 실패 → Job 실패 | FAILED     |
    //  | afterStep            | Step 실패 → Job 실패 | FAILED     |
    //  | beforeChunk          | 청크 실패 → Step 실패| FAILED     |
    //  | afterChunk           | 커밋은 이미 됨       | FAILED     |
    //  | afterChunkError      | 원래 예외를 가림     | FAILED     |
    //  | ItemReadListener     | 읽기 실패로 처리     | FAILED     |
    //  | SkipListener.onSkip* | **삼켜짐**           | 영향 없음  |
    // =====================================================================

    /**
     * afterJob 의 예외는 삼켜집니다.
     *
     * ERROR 로그는 찍히지만 Job 은 COMPLETED 로 끝납니다.
     *
     * ⚠️ 이게 왜 위험한가: afterJob 은 알림을 보내는 자리입니다.
     *    배치가 실패했고 → 알림을 보내려 했고 → 알림 전송이 실패했는데
     *    → 그 예외가 삼켜집니다. 결과: 아무도 배치 실패를 모릅니다.
     */
    public static class BrokenJobListener implements JobExecutionListener {
        @Override
        public void afterJob(JobExecution jobExecution) {
            throw new RuntimeException("알림 서버 접속 실패");
        }
    }

    public static class BrokenStepListener implements StepExecutionListener {
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            throw new RuntimeException("afterStep 폭발");
        }
    }

    /**
     * 35번째 청크에서 예외를 던집니다.
     *
     * 결과: settlement 에 34,000행이 남고 Step 은 FAILED.
     *   read=35000, write=34000, commit=34, rollback=1
     *
     * "데이터는 남았는데 실패"는 모순이 아니라 청크 단위 커밋의
     * 당연한 귀결입니다.
     *
     * ⚠️ 이것을 돌린 뒤에는 settlement 에 34,000행이 남아 있습니다.
     *    다음 측정 전에 반드시 TRUNCATE TABLE settlement 하십시오.
     *    이 스텝에서 가장 흔한 실습 실수입니다.
     */
    public static class BrokenChunkListener implements ChunkListener {

        private final AtomicInteger chunkNo = new AtomicInteger(0);

        @Override
        public void afterChunk(ChunkContext context) {
            if (chunkNo.incrementAndGet() == 35) {
                throw new RuntimeException("35번째 청크에서 리스너 폭발");
            }
        }
    }

    /** SkipListener 의 예외도 삼켜집니다. Job 에 영향이 없습니다. */
    public static class BrokenSkipListener implements SkipListener<Order, Settlement> {
        @Override
        public void onSkipInProcess(Order item, Throwable t) {
            throw new RuntimeException("SkipListener 폭발");
        }
    }

    // =====================================================================
    // [12-9] 리스너 실행 순서
    //
    // ⚠️ before 는 등록 순, after 도 **등록 순** 입니다.
    //    필터 체인처럼 "after 는 역순"일 거라고 가정하지 마십시오.
    // =====================================================================

    public static class FirstListener implements StepExecutionListener, Ordered {

        private static final Logger log = LoggerFactory.getLogger(FirstListener.class);

        @Override
        public void beforeStep(StepExecution stepExecution) {
            log.info("[1] beforeStep");
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            log.info("[1] afterStep");
            return stepExecution.getExitStatus();
        }

        @Override
        public int getOrder() {
            return 1;
        }
    }

    public static class SecondListener implements StepExecutionListener, Ordered {

        private static final Logger log = LoggerFactory.getLogger(SecondListener.class);

        @Override
        public void beforeStep(StepExecution stepExecution) {
            log.info("[2] beforeStep");
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            log.info("[2] afterStep");
            return stepExecution.getExitStatus();
        }

        @Override
        public int getOrder() {
            return 2;
        }
    }

    // =====================================================================
    // 리스너를 모두 붙인 Job 설정
    // =====================================================================

    // @Configuration
    public static class ListenerJobConfig {

        @Bean
        public Job settlementListenerJob(JobRepository jobRepository,
                                         Step settlementListenerStep) {
            return new JobBuilder("settlementListenerJob", jobRepository)
                    .listener(new SettlementJobListener())
                    .start(settlementListenerStep)
                    .build();
        }

        @Bean
        public Step settlementListenerStep(JobRepository jobRepository,
                                           PlatformTransactionManager txManager,
                                           DataSource dataSource) {
            return new StepBuilder("settlementListenerStep", jobRepository)
                    .<Order, Settlement>chunk(1000, txManager)
                    .reader(com.example.batch.step11.Practice.buildOrderReader(dataSource))
                    .processor(new com.example.batch.step11.Practice.SettlementProcessor())
                    .writer(com.example.batch.step11.Practice.buildStrictWriter(dataSource))
                    .faultTolerant()
                    .skip(IllegalArgumentException.class)
                    .skipLimit(200)
                    // 인터페이스 방식 — 컴파일러가 오버로드를 골라 줍니다.
                    .listener(new SettlementStepListener())
                    .listener(new LoggingChunkListener())
                    .listener(new ItemLevelListener())
                    .listener(new SettlementSkipListener(dataSource))
                    // 애너테이션 방식 — Object 오버로드로 잡힙니다.
                    .listener((Object) new AnnotatedListener())
                    .build();
        }
    }

    // =====================================================================
    // CleanUp — 실습 뒷정리
    // =====================================================================

    public static class CleanUp {

        /** 불량 주문 기록 테이블. 12-6 과 연습문제 3 에서 씁니다. */
        public static final String DDL = """
                CREATE TABLE IF NOT EXISTS s12_bad_order (
                  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  order_id    BIGINT       NOT NULL,
                  phase       VARCHAR(10)  NOT NULL,
                  reason      VARCHAR(500) NOT NULL,
                  occurred_at DATETIME     NOT NULL,
                  KEY idx_bad_order (order_id)
                ) ENGINE=InnoDB;
                """;

        /**
         * ⚠️ 이 스텝을 끝내면 반드시 실행하십시오.
         *    orders 는 Step 13·14 가 공유하는 공용 테이블입니다.
         *    음수 금액을 남겨 두면 이후 스텝의 결과가 교재와 달라집니다.
         */
        public static final String REVERT_SQL = """
                UPDATE orders o
                  JOIN (SELECT order_id, 1000 + (order_id %% 977) * 100 AS amt
                        FROM orders) s
                    ON o.order_id = s.order_id
                   SET o.amount = s.amt
                 WHERE o.amount < 0;

                TRUNCATE TABLE settlement;
                DROP TABLE IF EXISTS s12_bad_order;

                -- 검증: bad_amount 가 0 이어야 합니다.
                SELECT COUNT(*) AS bad_amount FROM orders WHERE amount < 0;
                """;

        /** 리스너가 실제로 동작했는지 확인하는 쿼리. */
        public static final String VERIFY_SQL = """
                -- SkipListener 가 기록한 불량 주문 (100건이어야 정상)
                SELECT phase, COUNT(*) FROM s12_bad_order GROUP BY phase;

                -- Step 카운터
                SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT,
                       PROCESS_SKIP_COUNT, WRITE_SKIP_COUNT,
                       COMMIT_COUNT, ROLLBACK_COUNT
                FROM BATCH_STEP_EXECUTION
                ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
                """;
    }
}
