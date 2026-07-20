package com.example.batch.step04;

/*
 * ============================================================================
 * Step 04 — 연습문제 정답 및 해설
 * ============================================================================
 * 문제를 직접 풀어 본 뒤에 여세요.
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
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public class Solution {

    // ========================================================================
    // 정답 1. 무한루프 찾아 고치기
    // ========================================================================
    /*
     * (a) 왜 무한루프인가
     *
     *     반환값이 항상 RepeatStatus.CONTINUABLE 입니다. 종료 조건이 아예 없습니다.
     *     REFUNDED 주문에 해당하는 정산 행(10,000건 중 실제로는 0건 — settlement 에는
     *     COMPLETED 만 들어 있으므로)을 다 지운 뒤에도, deleted=0 을 찍으며 영원히 돕니다.
     *
     *     여기서 무서운 것은 "느려지지도 않는다" 는 점입니다.
     *     매 반복이 DELETE 한 번 + 커밋 한 번이라 초당 수천 회 돌면서
     *     로그를 쏟아내고 CPU 를 먹고 커넥션을 계속 빌렸다 돌려줍니다.
     *
     *     그리고 죽인 뒤가 더 문제입니다:
     *       BATCH_JOB_EXECUTION.STATUS = 'STARTED', END_TIME = NULL 로 남습니다.
     *       재실행하면 JobExecutionAlreadyRunningException 이 나서 손으로 UPDATE 해야 합니다.
     *
     * (b) 최소 수정 — 한 줄
     */
    public static class Sol1Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;

        public Sol1Tasklet(JdbcTemplate jdbcTemplate) {
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
            contribution.incrementWriteCount(deleted);
            System.out.println("[sol1] deleted=" + deleted);

            // ★ 이 한 줄이 정답입니다.
            return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
        }
    }
    /*
     * (c) "SELECT COUNT(*) 로 세서 0이면 종료" 가 왜 나쁜 답인가
     *
     *     이렇게 고쳤다고 해 봅시다:
     *
     *       Integer remaining = jdbcTemplate.queryForObject(
     *           "SELECT COUNT(*) FROM settlement s JOIN orders o ON ... WHERE o.status='REFUNDED'",
     *           Integer.class);
     *       return remaining > 0 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
     *
     *     겉보기에는 멀쩡합니다. 그런데 "남아 있지만 지워지지 않는 행" 이 하나라도 있으면
     *     remaining 은 영원히 1 이고, DELETE 는 영원히 0건이고, 루프는 영원히 돕니다.
     *     그런 행이 생기는 경우:
     *       - 외래키 제약으로 삭제가 막힌 행
     *       - BEFORE DELETE 트리거가 조건부로 삭제를 막는 경우
     *       - DELETE 의 조인 조건과 COUNT 의 조인 조건이 미묘하게 다른 경우 (가장 흔함)
     *
     *     원칙:
     *       종료 판정은 "아직 남았는가" 가 아니라 "이번에 진전이 있었는가" 로 합니다.
     *       진전이 없으면 다음 반복도 진전이 없습니다. 그러면 멈춰야 합니다.
     *
     *     부가 효과로 쿼리도 하나 줄어듭니다. DELETE 는 이미 영향 행 수를 돌려주므로
     *     COUNT 를 따로 칠 이유가 없습니다.
     */
    static final String ANSWER_1_A = "항상 CONTINUABLE 을 반환해 종료 조건이 없음";
    static final String ANSWER_1_C = "지워지지 않는 행이 하나라도 있으면 COUNT 는 계속 >0 이라 여전히 무한루프";


    // ========================================================================
    // 정답 2. 반복 · 트랜잭션 · 카운트의 대응 관계
    // ========================================================================
    /*
     * (a) COMMIT_COUNT = 3
     * (b) WRITE_COUNT  = 1500   (500 × 3)
     * (c) "Creating new transaction" = 3줄
     *
     * 실제 확인 결과:
     *
     *   +---------+-----------+--------------+------------+-------------+----------------+
     *   | STEP_NAME | STATUS  | COMMIT_COUNT | READ_COUNT | WRITE_COUNT | ROLLBACK_COUNT |
     *   +---------+-----------+--------------+------------+-------------+----------------+
     *   | ex2Step   | COMPLETED |          3 |          0 |        1500 |              0 |
     *   +---------+-----------+--------------+------------+-------------+----------------+
     *
     * 이 문제의 요지는 다음 셋이 정확히 1:1:1 로 대응한다는 것입니다.
     *
     *   Tasklet.execute() 호출 횟수  =  트랜잭션 수  =  COMMIT_COUNT
     *
     * 이유는 TaskletStep 의 구조에 있습니다.
     *
     *   stepOperations.iterate(() -> {              ← RepeatTemplate 의 반복
     *       transactionTemplate.execute(status -> { ← 트랜잭션이 반복 "안쪽" 에 있다
     *           RepeatStatus rs = tasklet.execute(contribution, chunkContext);
     *           jobRepository.update(stepExecution);
     *           return rs;
     *       });
     *   });
     *
     * transactionTemplate.execute 가 루프 안쪽이므로 반복 = 트랜잭션입니다.
     * 만약 바깥쪽이었다면 CONTINUABLE 반복 전체가 하나의 거대한 트랜잭션이 되었을 것이고,
     * "대량 작업을 잘게 커밋한다" 는 CONTINUABLE 의 존재 이유가 사라졌을 것입니다.
     *
     * WRITE_COUNT 가 1500 인 것도 같은 구조 때문입니다.
     * StepContribution 은 트랜잭션 단위의 임시 집계기이고, 커밋 시점에 StepExecution 으로
     * 합산됩니다. 3번 커밋되며 500씩 세 번 더해져 1500 이 됩니다.
     */
    static final String ANSWER_2_A = "3";
    static final String ANSWER_2_B = "1500";
    static final String ANSWER_2_C = "3";


    // ========================================================================
    // 정답 3. 인스턴스 필드 → ExecutionContext
    // ========================================================================
    public static class Sol3Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;
        // private int processed = 0;   ← 삭제

        public Sol3Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            ExecutionContext ctx = chunkContext.getStepContext()
                    .getStepExecution().getExecutionContext();

            int deleted = jdbcTemplate.update(
                    "DELETE FROM settlement WHERE settle_date = '2025-01-01' LIMIT 100");

            int processed = ctx.getInt("processed", 0) + deleted;
            ctx.putInt("processed", processed);
            contribution.incrementWriteCount(deleted);

            System.out.println("[sol3] 누적 " + processed);
            return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
        }
    }
    /*
     * 인스턴스 필드가 위험한 이유 — 두 가지로 나뉩니다.
     *
     * ① 같은 JVM 안에서: Tasklet Bean 은 기본적으로 싱글턴입니다.
     *
     *    한 애플리케이션 컨텍스트에서 같은 Job 을 두 번 실행하면 두 번째 실행이
     *    첫 번째 실행이 남긴 값을 이어받습니다.
     *    문제 3 의 Ex3Tasklet 이라면 두 번째 실행의 "누적" 로그가 0 이 아니라
     *    이전 값부터 시작합니다. 로그만 이상해지는 게 아니라, 만약 그 값으로
     *    종료를 판정했다면 두 번째 실행이 즉시 끝나 버립니다.
     *
     *    bootRun 은 매번 새 JVM 이라 잘 안 보입니다. 그래서 로컬에서는 멀쩡하다가
     *    통합 테스트(@SpringBatchTest 로 한 컨텍스트에서 여러 번 실행)나
     *    상주형 스케줄러(Quartz, Step 14)에서 처음 드러납니다. 발견이 늦습니다.
     *
     * ② 재시작에서: 필드는 재시작하면 0으로 초기화됩니다.
     *
     *    ExecutionContext 는 BATCH_STEP_EXECUTION_CONTEXT 에 직렬화되어 저장되므로
     *    재시작 시 복원됩니다. 필드는 그렇지 않습니다.
     *
     *    정산 배치에서 이것이 무슨 뜻인지 구체적으로 보면:
     *      "마지막으로 처리한 order_id" 를 필드에 들고 있다가 3만 건째에서 죽었다고 합시다.
     *      재시작하면 필드가 0이 되어 1번 주문부터 다시 처리합니다.
     *      settlement 에 UNIQUE 키가 없다면 앞의 3만 건이 통째로 중복 정산됩니다.
     *      예외도 없고, STATUS 는 COMPLETED 입니다.
     *
     *    "재시작 가능한 배치" 를 만들고 싶다면 반복 상태는 반드시 ExecutionContext 입니다.
     *
     * 참고: ExecutionContext 는 두 종류입니다.
     *   - Step 범위: stepExecution.getExecutionContext()               ← 이 문제의 정답
     *   - Job 범위:  stepExecution.getJobExecution().getExecutionContext()  ← Step 간 값 공유용
     *   Step 안의 반복 상태는 Step 범위가 맞습니다. Step 09 에서 자세히 다룹니다.
     */
    static final String ANSWER_3 =
            "① 싱글턴이라 같은 JVM 두 번째 실행에서 값이 이어짐 ② 재시작 시 초기화되어 처리 구간을 다시 처리함";


    // ========================================================================
    // 정답 4. 조용한 버그 — WRITE_COUNT 가 0
    // ========================================================================
    public static class Sol4Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;

        public Sol4Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM settlement WHERE settle_date = '2025-01-02' LIMIT 1000");

            contribution.incrementWriteCount(deleted);   // ★ (a) 정답은 이 한 줄

            System.out.println("[sol4] deleted=" + deleted);
            return deleted == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
        }
    }
    /*
     * (b) "데이터는 맞는데 지표가 0" 이 낳는 결과
     *
     * 이것이 이 코스가 말하는 "에러 없이 조용히 틀리는" 의 한 형태입니다.
     * 코드가 틀린 것도 아니고 데이터가 틀린 것도 아닙니다. 틀린 것은 "관측" 입니다.
     * 그런데 배치 운영은 전적으로 관측에 의존합니다.
     *
     * 피해 세 가지:
     *
     *   ① 진짜 장애와 구분할 수 없다.
     *      어느 날 파라미터 실수로 정말 0건이 처리되었다고 합시다.
     *      대시보드는 어제도 0, 오늘도 0 입니다. 아무도 이상하다고 느끼지 못합니다.
     *      "0건이 정상인 배치" 로 학습된 모니터링은 진짜 0건을 잡아내지 못합니다.
     *
     *   ② 처리량 기반 알림·SLA 가 전부 무의미해진다.
     *      "WRITE_COUNT 가 평소의 50% 미만이면 알림" 같은 규칙을 걸 수 없습니다.
     *      Spring Batch 의 메타데이터를 그대로 지표로 쓰는 도구(Spring Batch Admin,
     *      Micrometer 의 spring.batch.item.write, Step 14 의 Prometheus 노출)가
     *      전부 이 컬럼을 봅니다.
     *
     *   ③ "일 안 하는 배치" 로 오인되어 정리 대상이 된다.
     *      배치 목록을 정리할 때 "처리 건수 0인 Job" 은 1순위 삭제 후보입니다.
     *      실제로는 매일 7만 건을 지우고 있는 배치가 그렇게 사라질 수 있습니다.
     *
     * 청크 Step 은 프레임워크가 read/write/filter 를 자동으로 세어 주기 때문에
     * 이 문제가 없습니다. Tasklet 은 "무엇을 처리로 볼지" 를 프레임워크가 알 수 없으므로
     * 개발자가 직접 보고해야 합니다. Tasklet 의 가장 흔한 실수가 이것입니다.
     */
    static final String ANSWER_4_B =
            "진짜 0건 장애와 구분 불가 / 처리량 기반 알림·SLA 무력화 / 일 안 하는 배치로 오인되어 삭제 후보가 됨";


    // ========================================================================
    // 정답 5. Tasklet 인가 청크인가
    // ========================================================================
    /*
     * ① CSV 파일 이동 + gzip 압축 ................................ Tasklet
     *    항목 단위 반복이 아니라 단발성 작업입니다. 읽을 "아이템" 자체가 없습니다.
     *    SystemCommandTasklet 이나 java.nio.file.Files 로 처리합니다.
     *    (setTimeout 을 잊지 마세요 — 4-8)
     *
     * ② orders 70,000건 → 등급별 수수료 계산 → settlement ......... 청크
     *    항목마다 계산이 다릅니다(BRONZE 3.5% / SILVER 3.0% / GOLD 2.5% / VIP 2.0%).
     *    Processor 가 항목 단위로 개입해야 하고, 재시작 지점도 필요합니다.
     *    청크 1,000 이면 정확히 70청크입니다. Step 05~08 에서 이걸 만듭니다.
     *
     *    ※ 솔직히 말하면 이것도 INSERT ... SELECT JOIN 한 줄로 됩니다(4-0 에서 그렇게 했습니다).
     *      청크로 만드는 이유는 "항목마다 외부 API 호출" 이나 "항목 단위 스킵" 같은
     *      요구가 붙을 때를 대비한 구조를 배우기 위해서입니다.
     *
     * ③ 일자별 집계 → daily_summary (180일치) ................... Tasklet
     *    GROUP BY 한 방입니다. 집계는 DB 가 압도적으로 잘합니다. 문제 6 이 이것입니다.
     *
     * ④ 회원 100,000명 이메일 발송, 실패는 건너뛰기 .............. 청크
     *    "실패한 건은 건너뛰고 계속" 이 결정적입니다.
     *    .faultTolerant().skip(MailException.class).skipLimit(100) 이 필요하고,
     *    이건 항목 단위로만 동작합니다(Step 11).
     *    또한 외부 시스템 호출이라 SQL 로는 애초에 불가능합니다.
     *
     * ⑤ 임시 테이블 TRUNCATE .................................... Tasklet
     *    SQL 한 줄. 논쟁의 여지가 없습니다.
     *    준비 Step 이므로 .allowStartIfComplete(true) 를 붙이는 것도 함께 고려하세요(3-5).
     *
     * ⑥ orders 10만 건 status 일괄 변경 ......................... Tasklet (단, 쪼개서)
     *    ★ 답이 갈리는 항목입니다.
     *
     *    "10만 건이니까 청크" 라고 답하기 쉽습니다. 틀렸습니다.
     *    UPDATE orders SET status='EXPIRED' WHERE status='PENDING' 한 줄이면 됩니다.
     *    청크로 만들면 10만 건을 읽어서(네트워크 왕복) 자바 객체로 만들고(GC)
     *    다시 10만 건을 쓰는(네트워크 왕복) 셈이라, DB 안에서 끝날 일을
     *    굳이 애플리케이션까지 왕복시키는 낭비입니다.
     *
     *    다만 "그냥 Tasklet" 도 정답이 아닙니다.
     *    UPDATE 한 방으로 10만 행에 락을 걸면 그동안 서비스 쿼리가 대기합니다.
     *    운이 나쁘면 Lock wait timeout exceeded 로 서비스가 죽습니다.
     *
     *    정답은 CONTINUABLE + LIMIT 로 쪼개는 Tasklet 입니다:
     *
     *      int updated = jdbcTemplate.update(
     *          "UPDATE orders SET status='EXPIRED' WHERE status='PENDING' LIMIT 5000");
     *      contribution.incrementWriteCount(updated);
     *      return updated == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
     *
     *    20회 반복 = 20번 커밋. 락은 매번 짧게 잡혔다 풀립니다.
     *    "대량 DML 은 Tasklet 으로, 단 잘게 나눠서" 가 이 스텝의 결론 중 하나입니다.
     */
    static final String ANSWER_5_1 = "Tasklet — 단발성 파일 작업, 아이템이 없음";
    static final String ANSWER_5_2 = "청크 — 항목별 계산·재시작 지점 필요";
    static final String ANSWER_5_3 = "Tasklet — GROUP BY 단일 SQL";
    static final String ANSWER_5_4 = "청크 — 항목 단위 스킵 필요, 외부 시스템 호출";
    static final String ANSWER_5_5 = "Tasklet — SQL 한 줄";
    static final String ANSWER_5_6 = "Tasklet, 단 CONTINUABLE + LIMIT 로 쪼개서 락 시간을 줄일 것";


    // ========================================================================
    // 정답 6. 단일 SQL 집계 Tasklet
    // ========================================================================
    public static class Sol6Tasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;

        public Sol6Tasklet(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {

            // 멱등성 확보 — 몇 번을 돌려도 결과가 같아야 합니다.
            // 이 한 줄 덕분에 이 Job 은 RunIdIncrementer 를 붙여도 안전합니다(3-5 참고).
            jdbcTemplate.update("DELETE FROM daily_summary");

            int inserted = jdbcTemplate.update("""
                    INSERT INTO daily_summary (order_date, order_count, total_amount, completed_count)
                    SELECT DATE(ordered_at)                                  AS order_date,
                           COUNT(*)                                          AS order_count,
                           SUM(amount)                                       AS total_amount,
                           SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_count
                      FROM orders
                     GROUP BY DATE(ordered_at)
                    """);

            contribution.incrementWriteCount(inserted);
            System.out.printf("[sol6] daily_summary %d행 생성%n", inserted);

            return RepeatStatus.FINISHED;
        }
    }
    /*
     * 실행 결과
     *
     *   INFO ... SimpleStepHandler : Executing step: [ex6Step]
     *   [sol6] daily_summary 180행 생성
     *   INFO ... AbstractStep      : Step: [ex6Step] executed in 312ms
     *
     *   +-----------+-----------+--------------+-------------+
     *   | STEP_NAME | STATUS    | COMMIT_COUNT | WRITE_COUNT |
     *   +-----------+-----------+--------------+-------------+
     *   | ex6Step   | COMPLETED |            1 |         180 |
     *   +-----------+-----------+--------------+-------------+
     *
     *   mysql> SELECT * FROM daily_summary ORDER BY order_date LIMIT 3;
     *   +------------+-------------+--------------+-----------------+
     *   | order_date | order_count | total_amount | completed_count |
     *   +------------+-------------+--------------+-----------------+
     *   | 2025-01-01 |         555 |  27612500.00 |             389 |
     *   | 2025-01-02 |         556 |  27689000.00 |             389 |
     *   | 2025-01-03 |         555 |  27544500.00 |             389 |
     *   +------------+-------------+--------------+-----------------+
     *
     *   180일 × 약 555건 ≈ 100,000건. 프로젝트 셋업의 숫자와 맞습니다.
     *
     * ── 왜 이걸 청크로 만들면 안 되는가 ─────────────────────────────────────
     *
     * 같은 일을 청크로 만든다고 상상해 봅시다.
     *
     *   Reader:    orders 100,000건을 페이징으로 읽는다
     *   Processor: 일자별로 집계한다  ← ★ 여기서 무너집니다
     *   Writer:    daily_summary 에 쓴다
     *
     * Processor 는 항목 하나를 받아 항목 하나를 돌려주는 인터페이스입니다.
     * 집계는 "여러 항목을 하나로 접는" 연산이라 이 모델에 맞지 않습니다.
     * 억지로 하려면 Processor 나 Writer 안에 Map<LocalDate, Summary> 를 들고
     * 100,000건을 전부 누적한 뒤 마지막에 한꺼번에 써야 합니다.
     *
     * 그 순간 청크의 최대 장점인 "메모리 상한" 이 무너집니다.
     * 청크 크기를 1,000 으로 잡아도 누적 Map 은 계속 커지므로 메모리는
     * 데이터 전체 크기에 비례합니다. 청크를 쓰는 의미가 사라진 것입니다.
     * (게다가 청크 경계에서 커밋되므로 중간에 실패하면 반쯤 집계된 상태가 남습니다.)
     *
     * DB 는 GROUP BY 를 정렬이나 해시로 스트리밍 처리하도록 수십 년간 최적화되어 있습니다.
     * 애플리케이션이 그것보다 잘할 이유가 없습니다.
     *
     * 이 스텝의 판단 기준을 다시 적습니다:
     *
     *     "SQL 한 줄로 되는가?" 를 먼저 물어라. 되면 Tasklet 이다.
     *
     * 청크는 강력하지만 공짜가 아닙니다. Reader/Processor/Writer 세 클래스,
     * 재시작 상태 관리, 커밋 간격 튜닝이 따라옵니다.
     * 그 비용을 낼 이유가 있을 때만 내는 것이 좋은 설계입니다.
     */


    // ========================================================================
    // Step / Job 배선
    // ========================================================================
    @Configuration
    public static class SolutionConfig {

        @Bean
        public Step sol1Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                             JdbcTemplate jdbcTemplate) {
            return new StepBuilder("sol1Step", jobRepository)
                    .tasklet(new Sol1Tasklet(jdbcTemplate), txManager).build();
        }

        @Bean
        public Job sol1Job(JobRepository jobRepository, Step sol1Step) {
            return new JobBuilder("sol1Job", jobRepository).start(sol1Step).build();
        }

        @Bean
        public Step sol3Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                             JdbcTemplate jdbcTemplate) {
            return new StepBuilder("sol3Step", jobRepository)
                    .tasklet(new Sol3Tasklet(jdbcTemplate), txManager).build();
        }

        @Bean
        public Job sol3Job(JobRepository jobRepository, Step sol3Step) {
            return new JobBuilder("sol3Job", jobRepository).start(sol3Step).build();
        }

        @Bean
        public Step sol4Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                             JdbcTemplate jdbcTemplate) {
            return new StepBuilder("sol4Step", jobRepository)
                    .tasklet(new Sol4Tasklet(jdbcTemplate), txManager).build();
        }

        @Bean
        public Job sol4Job(JobRepository jobRepository, Step sol4Step) {
            return new JobBuilder("sol4Job", jobRepository).start(sol4Step).build();
        }

        @Bean
        public Step sol6Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                             JdbcTemplate jdbcTemplate) {
            return new StepBuilder("sol6Step", jobRepository)
                    .tasklet(new Sol6Tasklet(jdbcTemplate), txManager).build();
        }

        @Bean
        public Job sol6Job(JobRepository jobRepository, Step sol6Step) {
            return new JobBuilder("sol6Job", jobRepository).start(sol6Step).build();
        }
    }

    /*
     * ── 문제 6 실행 전 DDL ──────────────────────────────────────────────────
     * CREATE TABLE IF NOT EXISTS daily_summary (
     *   order_date      DATE          NOT NULL PRIMARY KEY,
     *   order_count     INT           NOT NULL,
     *   total_amount    DECIMAL(14,2) NOT NULL,
     *   completed_count INT           NOT NULL
     * ) ENGINE=InnoDB;
     */
}
