package com.example.batch.step05;

/*
 * ============================================================================
 * Step 05 — 청크 지향 처리 / 연습문제 (6문제)
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
 *
 *   초기화하지 않고 다시 돌리면 settlement.order_id 의 UNIQUE 제약에 걸려
 *   DuplicateKeyException 이 납니다. 이건 버그가 아니라 프로젝트 셋업이
 *   의도한 안전장치입니다 — 정산이 두 번 되느니 시끄럽게 실패하는 게 낫습니다.
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import com.example.batch.step05.Practice.Order;
import com.example.batch.step05.Practice.Settlement;

@Configuration
public class Exercise {

    // ========================================================================
    // 문제 1. 4.x 스타일 Step 정의를 5.x 로 마이그레이션하세요.
    //
    //   아래는 Spring Batch 4.3 예제 그대로입니다. 5.1.1 에서는 컴파일조차
    //   되지 않습니다(StepBuilderFactory 가 삭제됨).
    //
    //   [4.x 원본]
    //     @Bean
    //     public Step legacyStep() {
    //         return stepBuilderFactory.get("legacyStep")
    //                 .<Order, Settlement>chunk(1000)
    //                 .reader(orderReader)
    //                 .processor(settlementProcessor)
    //                 .writer(settlementWriter)
    //                 .build();
    //     }
    //
    //   힌트: 바꿔야 할 것이 두 가지입니다. 하나는 컴파일 에러로 드러나고,
    //         다른 하나는 컴파일이 통과한 뒤 런타임에 드러납니다.
    // ========================================================================

    @Bean
    public Step migratedStep(JobRepository jobRepository,
                             PlatformTransactionManager txManager,
                             ItemReader<Order> orderReader,
                             ItemProcessor<Order, Settlement> settlementProcessor,
                             ItemWriter<Settlement> settlementWriter) {

        // 여기에 작성:
        return null;
    }

    // ========================================================================
    // 문제 2. COMPLETED 주문 70,000건을 청크 크기 700 으로 처리하면
    //         BATCH_STEP_EXECUTION.COMMIT_COUNT 는 얼마일까요?
    //
    //   ⚠️ 먼저 종이에 답을 적고, 그다음 실행해서 검증하세요.
    //      실행 결과를 보고 역산하면 배우는 게 없습니다.
    //
    //   실행:
    //     ./gradlew bootRun --args='--spring.batch.job.name=settlementChunkJob --batch.chunk-size=700'
    //   검증:
    //     mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
    //       SELECT READ_COUNT, WRITE_COUNT, COMMIT_COUNT
    //       FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
    //
    //   힌트: 70000 / 700 = 100 입니다. 그런데 답은 100 이 아닙니다.
    // ========================================================================

    /** 여기에 작성: 예측한 COMMIT_COUNT 값 */
    public static final int Q2_PREDICTED_COMMIT_COUNT = 0;

    /** 여기에 작성: 왜 그 값인지 한 줄 설명 */
    public static final String Q2_REASON = "";

    // ========================================================================
    // 문제 3. 청크 크기 300 으로 70,000건을 돌려 실행시간을 실측하세요.
    //         본문 5-6 의 U자 곡선에서 300 은 어디쯤일까요?
    //
    //   실행:
    //     ./gradlew bootRun --args='--spring.batch.job.name=settlementChunkJob --batch.chunk-size=300' \
    //       | grep 'executed in'
    //
    //   본문 실측값:
    //     청크   10 → 48.2초
    //     청크  100 → 12.7초
    //     청크 1000 →  6.1초   ← 최적
    //     청크 10000 →  7.4초
    //
    //   실측한 뒤, 300 이 "왼쪽 내리막(커밋 오버헤드 지배)" 인지
    //   "오른쪽 오르막(메모리·GC 지배)" 인지 판정하고 근거를 쓰세요.
    // ========================================================================

    /** 여기에 작성: 실측 실행시간 (초) */
    public static final double Q3_MEASURED_SECONDS = 0.0;

    /** 여기에 작성: "LEFT_SLOPE" 또는 "RIGHT_SLOPE" 와 그 근거 */
    public static final String Q3_VERDICT = "";

    // ========================================================================
    // 문제 4. 실패 지점을 정하고, settlement 에 남을 건수를 미리 계산하세요.
    //
    //   Practice.java 의 settlementFailJob 은 --batch.fail-at=N 으로
    //   N번째 아이템에서 예외를 던집니다.
    //
    //   조건: --batch.chunk-size=500, --batch.fail-at=  (여러분이 정하세요)
    //
    //   ① failAt 값을 하나 정합니다 (예: 12345)
    //   ② settlement 에 남을 행 수를 계산합니다  ← 실행 전에!
    //   ③ 실행해서 맞는지 확인합니다
    //
    //   실행:
    //     ./gradlew bootRun --args='--spring.batch.job.name=settlementFailJob \
    //       --batch.chunk-size=500 --batch.fail-at=12345'
    //   검증:
    //     mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
    //       SELECT COUNT(*) FROM settlement;"
    //
    //   힌트: 커밋된 청크 수를 먼저 나눗셈으로 구하고, 청크 크기를 곱합니다.
    //         나눗셈의 분자에 주의하세요. off-by-one 이 이 문제의 핵심입니다.
    // ========================================================================

    /** 여기에 작성: 여러분이 정한 failAt */
    public static final int Q4_FAIL_AT = 0;

    /** 여기에 작성: 청크 500 일 때 settlement 에 남을 행 수 */
    public static final int Q4_EXPECTED_ROWS = 0;

    /** 여기에 작성: 계산식 (예: "(N - 1) / 500 * 500") */
    public static final String Q4_FORMULA = "";

    // ========================================================================
    // 문제 5. 아래 4.x writer 를 5.x 로 고치세요.
    //
    //   ⚠️ 이 코드는 지금 상태로도 "컴파일이 됩니다."
    //      @Override 가 없어서, write(List) 는 그냥 이름이 같은 별개의
    //      메서드가 되고 인터페이스 쪽은 구현되지 않은 채로 남습니다.
    //      그 결과 배치는 COMPLETED 로 끝나는데 settlement 는 0행입니다.
    //
    //      "에러 없이 조용히 틀리는" 전형입니다. 고치세요.
    // ========================================================================

    static class LegacyWriter implements ItemWriter<Settlement> {

        private final JdbcTemplate jdbc;

        LegacyWriter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        // ↓ 4.x 시그니처. 이대로 두면 조용히 아무것도 안 쓰입니다.
        public void write(List<? extends Settlement> items) {
            for (Settlement s : items) {
                jdbc.update("""
                            INSERT INTO settlement
                              (order_id, customer_id, settle_date, gross_amount,
                               fee_rate, fee_amount, net_amount)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                        s.orderId(), s.customerId(), s.settleDate(),
                        s.grossAmount(), s.feeRate(), s.feeAmount(), s.netAmount());
            }
        }

        // 여기에 작성: 5.x 시그니처로 고친 write 메서드
        //             (@Override 를 반드시 붙이세요)
    }

    // ========================================================================
    // 문제 6. 안전한 최대 청크 크기를 계산하세요. (실행 불필요, 계산 문제)
    //
    //   조건:
    //     - JVM 힙 최대치      : 512 MB
    //     - 입력 아이템 하나   : 약 8 KB  (주문 + order_items 3건)
    //     - 출력 아이템 하나   : 약 4 KB  (정산 레코드)
    //     - Step 은 단일 스레드
    //
    //   본문 5-6 의 판단식:
    //     chunkSize × (입력 크기 + 출력 크기) × 2  ≤  힙 / 4
    //
    //   ① 위 식으로 상한을 구하세요.
    //   ② 실제로 고를 값을 정하고, 왜 상한보다 작게 잡는지 쓰세요.
    //   ③ 이 Step 을 4스레드로 바꾸면 상한이 어떻게 달라지나요?
    // ========================================================================

    /** 여기에 작성: ① 계산으로 구한 상한 */
    public static final int Q6_UPPER_BOUND = 0;

    /** 여기에 작성: ② 실제로 고를 청크 크기 */
    public static final int Q6_CHOSEN = 0;

    /** 여기에 작성: ② 왜 상한보다 작게 잡는가 */
    public static final String Q6_WHY_SMALLER = "";

    /** 여기에 작성: ③ 4스레드일 때의 상한 */
    public static final int Q6_UPPER_BOUND_4_THREADS = 0;

    // ========================================================================
    // 아래는 문제 1 의 Step 을 실행해 보기 위한 Job 입니다. 수정하지 마세요.
    // ========================================================================

    @Bean
    public Job exerciseJob(JobRepository jobRepository, Step migratedStep) {
        return new org.springframework.batch.core.job.builder.JobBuilder("exerciseJob", jobRepository)
                .start(migratedStep)
                .build();
    }

    /** 문제 5 를 고친 뒤, Chunk.of() 로 손쉽게 확인해 볼 수 있습니다. */
    @SuppressWarnings("unused")
    private static Chunk<Settlement> smokeTestChunk() {
        return Practice.sampleChunk();
    }
}
