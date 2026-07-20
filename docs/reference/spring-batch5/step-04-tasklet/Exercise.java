package com.example.batch.step04;

/*
 * ============================================================================
 * Step 04 — 연습문제 (6문제)
 * ============================================================================
 *
 * 정답은 Solution.java 에 있습니다. 먼저 직접 풀어 보세요.
 *
 * 놓을 위치: src/main/java/com/example/batch/step04/Exercise.java
 *
 * 시작 전 준비 — settlement 를 70,000행으로 채워 둡니다:
 *   mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb <<'SQL'
 *   TRUNCATE TABLE settlement;
 *   INSERT INTO settlement (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
 *   SELECT o.order_id, o.customer_id, DATE(o.ordered_at), o.amount, c.fee_rate,
 *          ROUND(o.amount * c.fee_rate, 2), o.amount - ROUND(o.amount * c.fee_rate, 2)
 *     FROM orders o JOIN customers c ON c.customer_id = o.customer_id
 *    WHERE o.status = 'COMPLETED';
 *   SQL
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public class Exercise {

    // ========================================================================
    // 문제 1. 무한루프 찾아 고치기
    // ------------------------------------------------------------------------
    // 아래 Tasklet 은 "REFUNDED 주문에 대한 정산 행을 지운다" 는 의도로 작성되었습니다.
    // 그런데 실행하면 영원히 돕니다.
    //
    // (a) 왜 무한루프인가?
    // (b) 최소 수정으로 고치세요. (한 줄이면 됩니다)
    // (c) "남은 건수를 SELECT COUNT(*) 로 세서 0이면 종료" 로 고치는 것은 왜 나쁜 답인가?
    //
    // ⚠️ 실행하기 전에 코드를 먼저 읽으세요. 실행할 거라면 Ctrl+C 준비를 하고,
    //    죽인 뒤에는 Practice.java 하단의 "무한루프 뒤처리 SQL" 을 반드시 실행하세요.
    // ========================================================================
    static final String ANSWER_1_A = "여기에 작성: ";
    static final String ANSWER_1_C = "여기에 작성: ";

    public static class Ex1Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;

        public Ex1Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            int deleted = jdbcTemplate.update("""
                    DELETE s FROM settlement s
                      JOIN orders o ON o.order_id = s.order_id
                     WHERE o.status = 'REFUNDED'
                     LIMIT 1000
                    """);
            System.out.println("[ex1] deleted=" + deleted);

            // 여기에 작성: 종료 조건을 넣으세요.
            return RepeatStatus.CONTINUABLE;
        }
    }


    // ========================================================================
    // 문제 2. 예측하기 — 반복 · 트랜잭션 · 카운트의 대응 관계
    // ------------------------------------------------------------------------
    // 아래 Ex2Tasklet 을 실행합니다. 실행하기 "전에" 다음을 예측해 적으세요.
    //
    //   (a) BATCH_STEP_EXECUTION.COMMIT_COUNT 는?
    //   (b) BATCH_STEP_EXECUTION.WRITE_COUNT 는?
    //   (c) DataSourceTransactionManager 를 DEBUG 로 켰을 때
    //       "Creating new transaction" 로그는 몇 줄 나오는가?
    //
    // 예측을 적은 뒤 실행하고 아래 SQL 로 확인하세요.
    //   SELECT STEP_NAME, COMMIT_COUNT, WRITE_COUNT FROM BATCH_STEP_EXECUTION
    //    WHERE STEP_NAME='ex2Step';
    // ========================================================================
    static final String ANSWER_2_A = "여기에 작성: ";
    static final String ANSWER_2_B = "여기에 작성: ";
    static final String ANSWER_2_C = "여기에 작성: ";

    public static class Ex2Tasklet implements Tasklet {

        private int round = 0;

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            round++;
            contribution.incrementWriteCount(500);
            System.out.println("[ex2] round=" + round);
            return round < 3 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
        }
    }


    // ========================================================================
    // 문제 3. 인스턴스 필드 → ExecutionContext 리팩터링
    // ------------------------------------------------------------------------
    // 아래 Tasklet 은 처리한 누적 건수를 인스턴스 필드에 들고 있습니다.
    // ExecutionContext 를 쓰도록 고치세요.
    //
    // 함께 답할 것:
    //   인스턴스 필드가 위험한 이유를 "두 가지" 로 나눠 적으세요.
    //   (하나는 같은 JVM 에서의 문제, 하나는 재시작에서의 문제입니다)
    // ========================================================================
    static final String ANSWER_3 = "여기에 작성: ";

    public static class Ex3Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;
        private int processed = 0;      // 여기에 작성: 이 필드를 없애세요.

        public Ex3Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM settlement WHERE settle_date = '2025-01-01' LIMIT 100");

            // 여기에 작성: processed 대신 ExecutionContext 를 사용하도록 고치세요.
            processed += deleted;
            System.out.println("[ex3] 누적 " + processed);

            return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
        }
    }


    // ========================================================================
    // 문제 4. 조용한 버그 — WRITE_COUNT 가 0
    // ------------------------------------------------------------------------
    // 아래 Tasklet 은 정상 동작합니다. 데이터도 정확히 지워집니다.
    // 그런데 BATCH_STEP_EXECUTION.WRITE_COUNT 는 0으로 남습니다.
    //
    // (a) 고치세요.
    // (b) "데이터는 맞는데 지표가 0" 인 상황이 배치 운영에서 어떤 결과를 낳는지
    //     두세 문장으로 서술하세요. ← 이쪽이 이 문제의 본체입니다.
    // ========================================================================
    static final String ANSWER_4_B = "여기에 작성: ";

    public static class Ex4Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;

        public Ex4Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM settlement WHERE settle_date = '2025-01-02' LIMIT 1000");

            // 여기에 작성: 처리 건수를 보고하세요.

            System.out.println("[ex4] deleted=" + deleted);
            return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
        }
    }


    // ========================================================================
    // 문제 5. Tasklet 인가 청크인가
    // ------------------------------------------------------------------------
    // 아래 6개 요구를 Tasklet / 청크로 분류하고 근거를 적으세요.
    // 답이 갈릴 수 있는 항목이 하나 있습니다. 근거를 반드시 함께 쓰세요.
    //
    //   ① 어제 생성된 CSV 파일을 /archive 로 옮기고 gzip 압축한다
    //   ② orders 70,000건을 읽어 고객 등급별 수수료를 계산해 settlement 에 넣는다
    //   ③ 일자별 주문 건수·금액을 집계해 daily_summary 에 넣는다 (180일치)
    //   ④ 회원 100,000명에게 이메일을 발송한다. 실패한 건은 건너뛰고 계속 진행한다
    //   ⑤ 임시 테이블 tmp_settlement 를 비운다
    //   ⑥ orders 10만 건의 status 를 'PENDING' → 'EXPIRED' 로 일괄 변경한다
    //
    // ========================================================================
    static final String ANSWER_5_1 = "여기에 작성: ";
    static final String ANSWER_5_2 = "여기에 작성: ";
    static final String ANSWER_5_3 = "여기에 작성: ";
    static final String ANSWER_5_4 = "여기에 작성: ";
    static final String ANSWER_5_5 = "여기에 작성: ";
    static final String ANSWER_5_6 = "여기에 작성: ";


    // ========================================================================
    // 문제 6. 단일 SQL 집계 Tasklet
    // ------------------------------------------------------------------------
    // orders 를 일자별로 집계해 daily_summary 에 넣는 Tasklet 을 구현하세요.
    //
    // 먼저 테이블을 만듭니다:
    //   CREATE TABLE IF NOT EXISTS daily_summary (
    //     order_date   DATE          NOT NULL PRIMARY KEY,
    //     order_count  INT           NOT NULL,
    //     total_amount DECIMAL(14,2) NOT NULL,
    //     completed_count INT        NOT NULL
    //   ) ENGINE=InnoDB;
    //
    // 요구사항:
    //   - 실행 전에 daily_summary 를 비운다 (몇 번을 돌려도 결과가 같아야 함 = 멱등)
    //   - orders 전체를 DATE(ordered_at) 으로 GROUP BY 해서 한 번에 INSERT
    //   - INSERT 된 행 수를 StepContribution 에 보고
    //   - 반복 없이 FINISHED
    //
    // 예상 결과: 180일치이므로 WRITE_COUNT = 180
    // ========================================================================
    public static class Ex6Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;

        public Ex6Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            // 여기에 작성: daily_summary 비우기

            // 여기에 작성: INSERT ... SELECT ... GROUP BY 로 집계

            // 여기에 작성: 건수 보고 후 FINISHED 반환
            return RepeatStatus.FINISHED;
        }
    }


    // ========================================================================
    // Step / Job 배선 (문제 코드를 실행하기 위한 설정 — 수정할 필요 없습니다)
    // ========================================================================
    @Configuration
    public static class ExerciseConfig {

        @Bean
        public Step ex2Step(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("ex2Step", jobRepository)
                    .tasklet(new Ex2Tasklet(), txManager).build();
        }

        @Bean
        public Job ex2Job(JobRepository jobRepository, Step ex2Step) {
            return new JobBuilder("ex2Job", jobRepository).start(ex2Step).build();
        }

        @Bean
        public Step ex4Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                            JdbcTemplate jdbcTemplate) {
            return new StepBuilder("ex4Step", jobRepository)
                    .tasklet(new Ex4Tasklet(jdbcTemplate), txManager).build();
        }

        @Bean
        public Job ex4Job(JobRepository jobRepository, Step ex4Step) {
            return new JobBuilder("ex4Job", jobRepository).start(ex4Step).build();
        }

        @Bean
        public Step ex6Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                            JdbcTemplate jdbcTemplate) {
            return new StepBuilder("ex6Step", jobRepository)
                    .tasklet(new Ex6Tasklet(jdbcTemplate), txManager).build();
        }

        @Bean
        public Job ex6Job(JobRepository jobRepository, Step ex6Step) {
            return new JobBuilder("ex6Job", jobRepository).start(ex6Step).build();
        }
    }
}
