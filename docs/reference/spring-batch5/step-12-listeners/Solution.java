package com.example.batch.step12;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterChunk;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Step 12 — 연습문제 정답과 해설
 *
 * 문제를 직접 풀어 본 뒤에 여십시오.
 */
public class Solution {

    // =====================================================================
    // 정답 1. 실패한 Job 에만 알림을 보내는 JobExecutionListener
    // =====================================================================

    public static class CorrectJobListener implements JobExecutionListener {

        private static final Logger log =
                LoggerFactory.getLogger(CorrectJobListener.class);

        private final Exercise.Notifier notifier = new Exercise.Notifier();

        @Override
        public void afterJob(JobExecution jobExecution) {
            // (d) 소요 시간은 성공/실패 무관하게 남깁니다.
            long millis = Duration.between(
                    jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
            log.info(">>> 정산 배치 종료. status={}, 소요={}ms",
                    jobExecution.getStatus(), millis);

            // (a)(b) 실패했을 때만 알림
            if (jobExecution.getStatus() != BatchStatus.FAILED) {
                return;
            }

            // (c) 알림 전송을 try-catch 로 감쌉니다.
            try {
                notifier.sendAlert(String.format(
                        "정산 배치 실패 [%s] 소요=%dms 원인=%s",
                        jobExecution.getJobInstance().getJobName(),
                        millis,
                        jobExecution.getAllFailureExceptions()));
            } catch (Exception e) {
                // 알림이 실패해도 최소한 로그에는 배치 상태를 함께 남깁니다.
                log.error(">>> 알림 전송 실패 — 배치 상태={}, 원인={}",
                        jobExecution.getStatus(),
                        jobExecution.getAllFailureExceptions(), e);
            }
        }
    }

    /*
     * (c) afterJob 의 예외가 삼켜지는 것이 왜 위험한가
     *
     *   **알림을 보내는 자리이기 때문입니다.**
     *
     *   시나리오를 따라가 보십시오.
     *     ① 정산 배치가 실패합니다.
     *     ② afterJob 이 호출되어 슬랙으로 알림을 보내려 합니다.
     *     ③ 슬랙 API 가 죽어 있어서 예외가 납니다.
     *     ④ 그 예외는 Spring Batch 가 삼킵니다. Job 은 그대로 종료됩니다.
     *     ⑤ 결과: **아무도 배치 실패를 모릅니다.**
     *
     *   배치가 실패한 것보다, 실패했는데 아무도 모르는 것이 훨씬 위험합니다.
     *   정산 배치라면 다음 날 아침 정산 담당자가 발견합니다.
     *
     *   그래서 방어책이 두 가지 필요합니다.
     *     - try-catch 로 감싸 최소한 로그에는 남긴다 (위 코드)
     *     - **알림 경로를 이중화한다** — 슬랙 + 메트릭.
     *       슬랙이 죽어도 Prometheus 의 spring_batch_job_seconds_count
     *       {status="FAILED"} 가 올라가면 알림이 갑니다.
     *       Step 14 에서 이 두 번째 경로를 만듭니다.
     *
     *   ⚠️ 그리고 절대 하지 말아야 할 것:
     *     public void afterJob(JobExecution je) {
     *         notifier.send("정산 배치가 완료되었습니다");   // 상태 분기 없음
     *     }
     *   "배치 완료" 알림이 매일 잘 오길래 안심하고 있었는데 알고 보니
     *   3일째 실패 중이었다 — 실무에서 정말 자주 일어납니다.
     */

    // =====================================================================
    // 정답 2. 애너테이션 리스너가 조용히 무시되는 세 가지 패턴
    // =====================================================================

    /*
     * (a) 각각의 이유
     *
     *   ① @BeforeStep public void before()  — **인자 누락**
     *
     *      Spring Batch 는 애너테이션 리스너를 등록할 때
     *      StepListenerFactoryBean 이 메서드 시그니처를 검사합니다.
     *      @BeforeStep 은 `void (StepExecution)` 시그니처를 요구합니다.
     *      인자가 없으면 매칭에 실패하고 **그냥 등록하지 않습니다.**
     *      예외도 경고도 없습니다.
     *
     *   ② @AfterChunk public void afterChunk(StepExecution)  — **타입 불일치**
     *
     *      @AfterChunk 는 `void (ChunkContext)` 를 요구합니다.
     *      StepExecution 을 받으면 시그니처가 달라 매칭에 실패합니다.
     *      역시 조용히 무시됩니다.
     *
     *      ⚠️ 이것이 특히 헷갈리는 이유: StepExecution 과 ChunkContext
     *         둘 다 "그럴듯한" 타입이라 IDE 자동완성으로 잘못 고르기 쉽습니다.
     *         ChunkContext 에서 StepExecution 을 꺼내는 것은
     *         context.getStepContext().getStepExecution() 입니다.
     *
     *   ③ 애너테이션 없이 메서드 이름만 beforeStep  — **애너테이션 누락**
     *
     *      메서드 이름은 아무 의미가 없습니다. Spring Batch 는 이름이 아니라
     *      **애너테이션**을 봅니다. 이름이 beforeStep 이든 zzz 든
     *      @BeforeStep 이 붙어 있어야 등록됩니다.
     *
     *      역으로, StepExecutionListener 를 **구현**했다면 이름이 중요합니다
     *      (인터페이스 계약이니까). 두 방식을 섞어 생각하면 여기서 혼란이 옵니다.
     *
     * (b) 고치기 — ①의 경우
     *
     *      @BeforeStep
     *      public void before(StepExecution stepExecution) { ... }
     *
     *      인자를 추가하는 것만으로 로그가 찍히기 시작합니다.
     *      "코드는 한 글자도 안 고쳤는데 갑자기 동작한다"는 경험이
     *      이 함정의 본질을 가장 잘 보여 줍니다.
     *
     * (c) ⚠️ 어떻게 알아차릴 것인가 — 실무 방어책
     *
     *   1. **로그를 눈으로 확인한다.**
     *      가장 단순하고 가장 확실합니다. 리스너를 새로 붙였으면
     *      반드시 한 번은 실행해서 그 로그가 찍히는지 봅니다.
     *      "코드를 썼으니 돌겠지"가 이 스텝의 최대 적입니다.
     *
     *   2. **테스트를 쓴다.**
     *      spring-batch-test 의 JobLauncherTestUtils 로 Job 을 돌리고,
     *      리스너가 남긴 흔적(로그 대신 카운터나 DB 행)을 단언합니다.
     *
     *        @Test
     *        void 리스너가_호출된다() {
     *            jobLauncherTestUtils.launchStep("settlementStep");
     *            assertThat(listener.getCallCount()).isGreaterThan(0);
     *        }
     *
     *      리스너에 호출 카운터를 두면 테스트로 검증할 수 있습니다.
     *
     *   3. **인터페이스를 구현한다.** — 근본 해결책
     *      컴파일러가 시그니처를 강제합니다. 틀리면 빌드가 깨집니다.
     *
     * (d) 결론 — **인터페이스 방식을 기본으로 삼으십시오**
     *
     *   컴파일러가 잡아 주는 범위 비교:
     *
     *   | 실수 | 인터페이스 방식 | 애너테이션 방식 |
     *   |---|---|---|
     *   | 인자 누락 | 컴파일 에러 | 조용히 무시 |
     *   | 인자 타입 틀림 | 컴파일 에러 | 조용히 무시 |
     *   | 메서드명 오타 | 컴파일 에러(@Override) | 무관 |
     *   | 애너테이션 누락 | 해당 없음 | 조용히 무시 |
     *   | 리턴 타입 틀림 | 컴파일 에러 | 조용히 무시 |
     *
     *   애너테이션 방식의 장점은 "한 클래스에 여러 레벨의 리스너를
     *   모을 수 있다"는 것뿐입니다. 그 편의를 위해 **모든 실수가
     *   런타임에 조용히 무시되는 위험**을 감수할 가치는 대체로 없습니다.
     *
     *   인터페이스도 여러 개 구현할 수 있습니다.
     *     class MyListener implements StepExecutionListener, ChunkListener { }
     *   이러면 편의와 안전을 둘 다 얻습니다.
     */

    /** (b) 올바르게 고친 버전. */
    public static class FixedAnnotatedListener {

        private static final Logger log =
                LoggerFactory.getLogger(FixedAnnotatedListener.class);

        @BeforeStep
        public void before(StepExecution stepExecution) {       // 인자 추가
            log.info(">>> [고침] Step 시작: {}", stepExecution.getStepName());
        }

        @AfterChunk
        public void afterChunk(ChunkContext context) {          // 타입 수정
            log.debug(">>> [고침] 청크 완료");
        }
    }

    // =====================================================================
    // 정답 3. SkipListener vs onProcessError — 이 스텝에서 가장 중요한 문제
    // =====================================================================

    /** 버전 A — SkipListener. 커밋 후에 호출됩니다. */
    public static class CorrectSkipListener implements SkipListener<Order, Settlement> {

        private final JdbcTemplate jdbcTemplate;

        public CorrectSkipListener(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public void onSkipInProcess(Order item, Throwable t) {
            jdbcTemplate.update("""
                    INSERT INTO s12_bad_order (order_id, phase, reason, occurred_at)
                    VALUES (?, 'PROCESS', ?, NOW())
                    """, item.order_id(), t.getMessage());
        }
    }

    /** 버전 B — ItemProcessListener. 트랜잭션 안에서 호출됩니다. */
    public static class TransactionalProcessListener
            implements ItemProcessListener<Order, Settlement> {

        private final JdbcTemplate jdbcTemplate;

        public TransactionalProcessListener(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public void onProcessError(Order item, Exception e) {
            // 완전히 같은 INSERT 입니다. 그런데 결과가 다릅니다.
            jdbcTemplate.update("""
                    INSERT INTO s12_bad_order (order_id, phase, reason, occurred_at)
                    VALUES (?, 'PROCESS', ?, NOW())
                    """, item.order_id(), e.getMessage());
        }
    }

    /*
     * (b) 행 수
     *
     *   버전 A (SkipListener)        : **100 건**
     *   버전 B (onProcessError)      : **0 건**
     *
     *   같은 SQL, 같은 데이터, 같은 실행. 결과는 100 대 0 입니다.
     *
     * (c) 왜 다른가 — 트랜잭션 경계
     *
     *   ItemProcessListener.onProcessError 는 **청크 트랜잭션 안**에서
     *   호출됩니다. 그리고 그 직후에 무슨 일이 벌어집니까?
     *
     *     처리 중 예외 발생
     *       → onProcessError 호출 (INSERT 실행됨)
     *       → 청크 트랜잭션 **롤백**
     *       → 방금 한 INSERT 도 함께 롤백
     *       → 스캔 모드로 아이템 1건씩 재처리 (Step 11-5)
     *       → 다시 onProcessError 호출 → 다시 INSERT → 다시 롤백
     *
     *   기록하려던 행이 매번 롤백됩니다. 최종적으로 0건입니다.
     *
     *   ⚠️ 가장 고약한 점: **에러가 나지 않습니다.**
     *      INSERT 는 성공했고, 롤백도 정상 동작입니다.
     *      로그를 보면 onProcessError 가 호출된 흔적이 남아 있습니다.
     *      그런데 테이블은 비어 있습니다.
     *      "분명히 기록하는 코드를 썼는데 왜 없지?"에서 몇 시간이 갑니다.
     *
     *   SkipListener 는 **커밋 후**에 호출됩니다.
     *
     *     청크 커밋 완료
     *       → SkipListener.onSkipInProcess 호출 (INSERT 실행됨)
     *       → 이 INSERT 는 청크 트랜잭션 밖이므로 독립적으로 커밋됨
     *
     *   그래서 100건이 온전히 남습니다.
     *
     *   ⚠️ 한 가지 더: SkipListener 는 **최종 skip 확정 시 한 번만**
     *      호출됩니다. 스캔 모드로 여러 번 재처리되어도 기록은 1건입니다.
     *      onProcessError 였다면 (롤백되지 않았다고 가정해도) 재시도
     *      횟수만큼 중복 기록됐을 것입니다.
     *
     * (d) 그렇다면 onProcessError 는 언제 쓰는가
     *
     *   **롤백돼도 상관없는 것에만** 씁니다. 실질적으로는 로그 출력입니다.
     *
     *     public void onProcessError(Order item, Exception e) {
     *         log.warn("처리 실패: order_id={}, {}", item.order_id(), e.getMessage());
     *     }
     *
     *   로그는 트랜잭션의 지배를 받지 않습니다(파일/콘솔로 나가니까).
     *   그래서 롤백돼도 남습니다. 오히려 재시도마다 찍히므로
     *   "몇 번 재시도했는지"를 볼 수 있어 진단에 유용합니다.
     *
     *   정리:
     *   | 목적 | 쓸 곳 |
     *   |---|---|
     *   | 콘솔/파일 로그 | onProcessError (트랜잭션 무관) |
     *   | **DB 기록** | **SkipListener** (커밋 후) |
     *   | 외부 알림 | SkipListener 또는 afterStep |
     *   | 메트릭 카운터 | SkipListener (중복 집계 방지) |
     *
     *   일반 원칙: **부수 효과가 영속적이어야 하면 트랜잭션 밖에서 하십시오.**
     */

    // =====================================================================
    // 정답 4. afterChunk 예외 — 데이터는 남고 Step 만 실패
    // =====================================================================

    /*
     * (a)(b) 예측과 실제 — 정확히 일치합니다
     *
     *   settlement 행 수 : 34,000
     *   Step STATUS      : FAILED
     *   COMMIT_COUNT     : 34
     *   ROLLBACK_COUNT   : 1
     *   READ_COUNT       : 35,000
     *   WRITE_COUNT      : 34,000
     *
     *   +-----------------+--------+------------+-------------+--------+--------+
     *   | STEP_NAME       | STATUS | READ_COUNT | WRITE_COUNT | COMMIT | ROLLBK |
     *   +-----------------+--------+------------+-------------+--------+--------+
     *   | settlementStep  | FAILED |      35000 |       34000 |     34 |      1 |
     *   +-----------------+--------+------------+-------------+--------+--------+
     *
     * (c) 왜 모순이 아닌가
     *
     *   "데이터는 남았는데 Step 은 실패"는 **청크 단위 커밋의 당연한
     *   귀결**입니다. 모순이 아닙니다.
     *
     *   Spring Batch 의 트랜잭션 단위는 Step 전체가 아니라 **청크 하나**
     *   입니다(Step 05). 그래서 이렇게 됩니다.
     *
     *     청크 1~34  : 각각 독립적으로 커밋됨 → 34,000행 영구 저장
     *     청크 35    : afterChunk 에서 예외 → 롤백 → 1,000행 사라짐
     *     청크 36~70 : 아예 시도되지 않음
     *     Step       : 예외가 전파되어 FAILED
     *
     *   만약 Step 전체가 하나의 트랜잭션이었다면 70,000행이 통째로
     *   롤백됐을 것입니다. 그런데 그러면 7시간짜리 배치가 6시간 59분에
     *   실패했을 때 전부를 다시 해야 합니다. 청크 단위 커밋은 바로
     *   그것을 피하기 위한 설계입니다.
     *
     *   ⚠️ 그래서 "Step 이 FAILED 면 데이터가 없다"고 가정하면 안 됩니다.
     *      부분적으로 커밋된 데이터가 반드시 있습니다.
     *      정산처럼 멱등하지 않은 작업이라면, 재실행 전에 이 부분
     *      데이터를 어떻게 할지 반드시 정해 두어야 합니다.
     *
     * (d) 재실행하면 — 34,000건 다음부터 이어집니다
     *
     *   직전 실행이 FAILED 이므로 같은 파라미터로 재실행이 가능하고,
     *   같은 JobInstance 에 새 JobExecution 이 붙습니다(Step 11-10).
     *
     *   Reader 가 ExecutionContext 에 저장해 둔 위치(34,000)를 복원하므로
     *   34,001번째부터 읽습니다.
     *
     *   +------------------+-----------+------------+-------------+--------+
     *   | JOB_EXECUTION_ID | STATUS    | READ_COUNT | WRITE_COUNT | COMMIT |
     *   +------------------+-----------+------------+-------------+--------+
     *   |                1 | FAILED    |      35000 |       34000 |     34 |
     *   |                2 | COMPLETED |      36000 |       35900 |     36 |
     *   +------------------+-----------+------------+-------------+--------+
     *
     *   2회차의 READ_COUNT 36,000 = 70,000 - 34,000 입니다.
     *   settlement 최종 행 수는 34,000 + 35,900 = 69,900 (불량 100건 제외).
     *
     *   ⚠️ 단, BrokenChunkListener 를 그대로 두면 재실행에서도 35번째
     *      청크(이번엔 전체 기준 69번째)에서 또 터집니다. 재시작 실습을
     *      할 때는 리스너를 빼거나 카운터 조건을 바꾸십시오.
     */

    // =====================================================================
    // 정답 5. 리스너 실행 순서
    // =====================================================================

    /*
     * (a) 관찰된 순서
     *
     *   beforeStep 순서: A → B → C   (등록 순)
     *   afterStep  순서: A → B → C   (등록 순, **역순 아님**)
     *
     *   실제 로그:
     *     INFO ... c.e.b.step12.ListenerA : [A] beforeStep
     *     INFO ... c.e.b.step12.ListenerB : [B] beforeStep
     *     INFO ... c.e.b.step12.ListenerC : [C] beforeStep
     *     INFO ... o.s.batch.core.step.AbstractStep : Step: [...] executed in 6s 118ms
     *     INFO ... c.e.b.step12.ListenerA : [A] afterStep
     *     INFO ... c.e.b.step12.ListenerB : [B] afterStep
     *     INFO ... c.e.b.step12.ListenerC : [C] afterStep
     *
     * (b) 예상과 다를 수 있습니다
     *
     *   서블릿 필터, 스프링 인터셉터, AOP 어라운드 어드바이스에 익숙하면
     *   "before 는 정순, after 는 역순"을 기대하게 됩니다. 양파 껍질처럼요.
     *
     *   CompositeStepExecutionListener 는 **양쪽 다 정순**입니다.
     *   내부적으로 그냥 List 를 순회하며 호출할 뿐이고, after 용으로
     *   리스트를 뒤집지 않습니다.
     *
     *   즉 리스너는 "감싸는" 구조가 아니라 "나열되는" 구조입니다.
     */

    /** (c) Ordered 로 순서 뒤집기 — 값이 작을수록 먼저 호출됩니다. */
    public static class OrderedListenerC implements StepExecutionListener, Ordered {

        private static final Logger log = LoggerFactory.getLogger(OrderedListenerC.class);

        @Override
        public void beforeStep(StepExecution stepExecution) {
            log.info("[C] beforeStep");
        }

        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            log.info("[C] afterStep");
            return stepExecution.getExitStatus();   // 절대 COMPLETED 를 하드코딩하지 말 것
        }

        @Override
        public int getOrder() {
            return 1;       // A(3), B(2), C(1) → C, B, A 순
        }
    }

    /*
     * (d) ⚠️ 순서를 제어할 수 있다는 것과, 순서에 의존해도 된다는 것은 다릅니다
     *
     *   순서 의존이 위험한 이유 네 가지:
     *
     *   1. **등록 지점이 흩어집니다.**
     *      리스너는 StepBuilder, JobBuilder, @Bean 자동 등록 등 여러
     *      경로로 들어옵니다. 누군가 리스너를 하나 추가하면서 순서가
     *      바뀌어도 컴파일러는 아무 말을 안 합니다.
     *
     *   2. **Ordered 와 등록 순이 섞이면 예측이 어렵습니다.**
     *      Ordered 를 구현한 것과 안 한 것이 섞이면, 안 한 쪽은
     *      LOWEST_PRECEDENCE 로 취급되어 뒤로 밀립니다.
     *      일부만 Ordered 를 붙이는 순간 순서가 직관과 어긋납니다.
     *
     *   3. **실패 시 나머지가 호출되지 않습니다.**
     *      A 가 예외를 던지면 B, C 의 afterStep 은 호출되지 않습니다.
     *      "A 가 연 자원을 C 가 닫는" 구조였다면 자원이 샙니다.
     *
     *   4. **테스트가 어렵습니다.**
     *      리스너 하나만 떼어 단위 테스트할 수 없게 됩니다.
     *
     *   원칙: **리스너는 서로 독립적이어야 합니다.**
     *   각 리스너가 자기 일만 하고, 다른 리스너의 존재나 순서를
     *   가정하지 않아야 합니다.
     *
     *   순서에 의존하는 로직이 필요하다면, 그건 리스너가 아니라
     *   **별도의 Step** 으로 만들어야 한다는 신호입니다.
     *   Step 은 순서가 명시적이고(`.next()`), 재시작·모니터링·트랜잭션이
     *   전부 프레임워크의 보호를 받습니다.
     */

    // =====================================================================
    // 정답 6. Item 레벨 리스너의 성능 영향과 로깅 전략
    // =====================================================================

    /*
     * (a) 측정표
     *
     *   | # | 구성 | 소요 | 배수 |
     *   |---|---|---|---|
     *   | ① | 리스너 없음 | 6.108초 | 1.00배 |
     *   | ② | afterRead 에 log.debug (레벨 INFO) | 6.402초 | 1.05배 |
     *   | ③ | afterRead 에 log.info | 27.310초 | **4.47배** |
     *   | ④ | afterRead 에 log.info + 문자열 concat | 31.884초 | 5.22배 |
     *
     * (b) ②와 ③의 차이 — 약 21초
     *
     *   ②는 로그가 **출력되지 않습니다.** 레벨이 INFO 인데 debug 로
     *   찍으려 했으니 `log.debug(...)` 호출은 내부에서 `isDebugEnabled()`
     *   검사 후 즉시 반환합니다. 남는 비용은 메서드 호출 70,000번뿐이고,
     *   이건 JIT 가 거의 없애 줍니다. 그래서 5% 오버헤드입니다.
     *
     *   ③은 실제로 70,000줄을 출력합니다. 줄마다
     *     - 문자열 포매팅 ({} 치환)
     *     - 타임스탬프·스레드명·로거명 렌더링
     *     - 콘솔/파일 I/O (동기)
     *   가 일어납니다. 줄당 0.3ms 만 잡아도 21초입니다.
     *
     *   ⚠️ 정산 작업 자체(6.1초)보다 **로깅이 3배 이상 오래 걸립니다.**
     *      배치가 일을 하는 게 아니라 로그를 쓰고 있습니다.
     *
     * (c) ③과 ④의 차이 — 약 4.6초
     *
     *   SLF4J 의 `{}` 플레이스홀더는 **로그 레벨이 켜져 있을 때만**
     *   문자열을 만듭니다.
     *
     *     log.info(">>> 읽음: order_id={}", item.order_id());   // 지연 평가
     *     log.info(">>> 읽음: " + item.order_id());             // 즉시 평가
     *
     *   두 번째는 **로그 레벨과 무관하게** 매번 문자열 연결과
     *   Long → String 박싱이 일어납니다. 70,000번의 불필요한
     *   객체 생성이 GC 압박으로 이어집니다.
     *
     *   ⚠️ 이 차이는 레벨이 꺼져 있을 때 훨씬 극적입니다.
     *      log.debug("..." + x) 는 레벨이 꺼져 있어도 문자열을 만듭니다.
     *      즉 ②의 5% 오버헤드가 concat 을 쓰는 순간 수십 %로 뜁니다.
     *      **{} 를 쓰는 습관이 배치에서는 성능 문제입니다.**
     *
     * (d) ⚠️ 운영 함정 — 로그 레벨을 올리는 순간
     *
     *   ②처럼 log.debug 로 짜 두면 평소에는 안전합니다(1.05배).
     *   그래서 "디버그 로그를 넉넉히 넣어 두자"는 판단이 나옵니다.
     *
     *   그런데 장애가 나서 조사하려고 로그 레벨을 DEBUG 로 올리면?
     *
     *     logging.level.com.example.batch: DEBUG
     *
     *   **6.1초짜리 배치가 27초가 됩니다.** 그리고 이건 70,000건일 때
     *   얘기입니다. 700만 건짜리 운영 배치라면 10분이 45분이 됩니다.
     *   야간 배치 윈도를 넘겨 아침 서비스에 영향을 줍니다.
     *
     *   즉 **장애를 조사하려는 행위가 더 큰 장애를 만듭니다.**
     *   그리고 이때 원인을 짐작하기 어렵습니다. 코드는 안 바뀌었고
     *   설정 한 줄만 바꿨을 뿐이니까요.
     *
     *   방어책:
     *     - Item 레벨 리스너에는 정상 경로 로그를 아예 두지 않는다.
     *     - 로그 레벨을 패키지 단위로 넓게 올리지 않는다.
     *       com.example.batch 전체가 아니라 특정 클래스만.
     *     - 비동기 Appender(logback AsyncAppender)를 쓴다.
     *       I/O 를 별도 스레드로 빼면 오버헤드가 크게 줄어듭니다.
     *
     * (e) 배치에서의 안전한 로깅 전략 — 세 줄
     *
     *   1. **아이템 단위 정상 경로에는 로그를 걸지 않는다.**
     *      Item 레벨 리스너에는 에러 콜백(onReadError, onProcessError,
     *      onWriteError)만 둡니다. 에러는 드물게 나므로 안전합니다.
     *
     *   2. **진행률은 청크 단위로, 그것도 N개마다 찍는다.**
     *      ChunkListener.afterChunk 에서 `if (n % 10 == 0)`.
     *      70,000번이 아니라 7번입니다. 오버헤드가 0에 수렴합니다.
     *
     *   3. **문자열 연결 대신 {} 를 쓰고, 요약은 afterStep 에서 한 번만.**
     *      건별 정보가 필요하면 로그가 아니라 DB 테이블에 남깁니다
     *      (SkipListener → s12_bad_order). 그게 조회도 되고 재처리도 됩니다.
     *
     *   ─────────────────────────────────────────────────────────────
     *   요약하면, 배치의 로그는 "건별 추적"이 아니라 "구간별 요약"이어야
     *   합니다. 건별 추적이 필요하면 그건 로그의 일이 아니라 데이터의
     *   일입니다.
     */
}
