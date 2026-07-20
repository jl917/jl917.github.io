package com.example.batch.step11;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Step 11 — 연습문제 정답과 해설
 *
 * 문제를 직접 풀어 본 뒤에 여십시오.
 */
public class Solution {

    // =====================================================================
    // 정답 1. skipLimit 기본값에 걸려 죽는 지점 특정
    // =====================================================================

    /*
     * (a) 예외 메시지
     *
     *   org.springframework.batch.core.step.skip.SkipLimitExceededException:
     *     Skip limit of '10' exceeded
     *
     *   실제 로그:
     *
     *   ERROR 43318 --- [main] o.s.batch.core.step.AbstractStep :
     *     Encountered an error executing step problem1Step in job settlementSkipJob
     *   org.springframework.batch.core.step.skip.SkipLimitExceededException:
     *     Skip limit of '10' exceeded
     *       at org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy
     *          .shouldSkip(LimitCheckingItemSkipPolicy.java:122)
     *
     *   ⚠️ 핵심: skipLimit 의 기본값은 **10** 입니다.
     *      .skip(...) 만 쓰고 .skipLimit(...) 을 안 쓰면 10건까지만
     *      봐주고 11번째에서 Step 전체를 실패시킵니다.
     *
     *      "skip 을 걸었으니 괜찮겠지" 하고 넘어가면, 불량이 11건 이상인
     *      날에 배치가 통째로 죽습니다. 그리고 그런 날은 반드시 옵니다.
     *
     * (b) 몇 번째 불량에서 죽는가 → **11번째**
     *
     *   LimitCheckingItemSkipPolicy 는 `skipCount >= skipLimit` 일 때
     *   예외를 던집니다. 10건까지는 skip 되고, 11번째 시도에서 터집니다.
     *
     * (c) 그 불량의 order_id → **11000**
     *
     *   불량은 order_id % 1000 = 0 인 주문입니다.
     *     1번째 불량 = order_id 1000
     *     2번째 불량 = order_id 2000
     *     ...
     *     11번째 불량 = order_id 11000
     *
     * (d) 몇 번째 청크인가 → **8번째 청크**
     *
     *   계산:
     *     COMPLETED 조건은 order_id % 10 <= 6 이므로 10개 중 7개입니다.
     *     order_id 11000 까지의 COMPLETED 건수 = 11000 × 0.7 = 7,700건
     *     청크 크기가 1,000 이므로 7,700번째 아이템은
     *       7700 / 1000 = 7.7 → **8번째 청크**(7,001~8,000번째 아이템) 안에 있습니다.
     *
     *   실제 메타데이터로 확인하면 commit_count = 7 입니다.
     *   7청크(7,000건)까지는 정상 커밋됐고 8번째 청크에서 죽었기 때문입니다.
     *
     *   +---------------+--------+------------+--------------------+--------------+
     *   | STEP_NAME     | STATUS | READ_COUNT | PROCESS_SKIP_COUNT | COMMIT_COUNT |
     *   +---------------+--------+------------+--------------------+--------------+
     *   | problem1Step  | FAILED |       8000 |                 10 |            7 |
     *   +---------------+--------+------------+--------------------+--------------+
     *
     *   "10건 skip 후 죽는다"를 아는 것과, 그게 order_id 11000 이고
     *   8번째 청크라는 것까지 계산해 내는 것은 다릅니다. 후자를 할 수 있어야
     *   장애 대응 때 "어디까지 처리됐나"를 즉시 답할 수 있습니다.
     *
     * (e) skipLimit(200) 으로 바꾸면
     *
     *   +---------------+-----------+------------+--------------------+--------------+
     *   | STEP_NAME     | STATUS    | READ_COUNT | PROCESS_SKIP_COUNT | COMMIT_COUNT |
     *   +---------------+-----------+------------+--------------------+--------------+
     *   | problem1Step  | COMPLETED |      70000 |                100 |          100 |
     *   +---------------+-----------+------------+--------------------+--------------+
     *
     *   100건 전부 skip 되고 COMPLETED 로 끝납니다.
     *   write_count 는 69,900 입니다 (70,000 - 100).
     *
     *   ⚠️ 그런데 commit_count 가 70 이 아니라 100 입니다.
     *      불량이 든 청크가 스캔 모드로 쪼개졌기 때문입니다. 11-5 의 주제입니다.
     */

    // =====================================================================
    // 정답 2. SkipPolicy 직접 구현
    // =====================================================================

    /** (a) 판정 순서가 생명입니다. 구체적인 예외를 먼저. */
    public static class CorrectSkipPolicy implements SkipPolicy {

        private static final int DATA_ERROR_LIMIT = 50;

        @Override
        public boolean shouldSkip(Throwable t, long skipCount)
                throws SkipLimitExceededException {

            // ① 인프라 장애를 "가장 먼저" 걸러냅니다.
            //    DataAccessResourceFailureException 은 DataAccessException 의
            //    하위 타입이므로, 이 검사가 아래로 내려가면 절대 도달하지 못합니다.
            if (t instanceof DataAccessResourceFailureException) {
                return false;       // 절대 skip 하지 않음 → Step 을 실패시킴
            }

            // ② 데이터 오류는 한도까지 허용
            if (t instanceof IllegalArgumentException
                    || t instanceof DataIntegrityViolationException) {
                if (skipCount >= DATA_ERROR_LIMIT) {
                    throw new SkipLimitExceededException(DATA_ERROR_LIMIT, t);
                }
                return true;
            }

            // ③ 모르는 예외는 skip 하지 않습니다.
            //    "모르면 멈춘다"가 안전한 기본값입니다.
            return false;
        }
    }

    /*
     * (b) 순서를 뒤집으면
     *
     *   if (t instanceof DataAccessException) { ... return true; }      // 먼저
     *   if (t instanceof DataAccessResourceFailureException) { ... }    // 도달 불가
     *
     *   DataAccessResourceFailureException 은 DataAccessException 을 상속하므로
     *   첫 번째 분기에 걸려 **skip 됩니다.**
     *
     *   실제로 벌어지는 일:
     *     - DB 커넥션이 끊깁니다.
     *     - 남은 모든 아이템이 전부 같은 예외로 실패합니다.
     *     - 정책이 전부 skip 이라고 답합니다.
     *     - skipLimit 에 걸릴 때까지 skip 하다가... 한도가 크면 끝까지 갑니다.
     *     - Job 이 **COMPLETED 로 끝납니다.**
     *
     *   정산이 하나도 안 됐는데 배치는 성공했다고 보고합니다.
     *   다음 날 아침 정산 담당자가 발견합니다.
     *
     *   컨테이너를 잠깐 멈춰 재현할 수 있습니다:
     *     docker compose pause mysql   (5초 뒤) unpause
     *
     *   ⚠️ 일반 원칙: **skip 대상은 화이트리스트로 좁게 지정하십시오.**
     *      "이 예외만 skip" 이라고 열거하는 것이, "이건 빼고 다 skip" 보다
     *      항상 안전합니다. 인프라 예외는 그 자체로 "지금 배치를 계속하면
     *      안 된다"는 신호입니다.
     *
     * (c) 예외 종류별 한도를 두려면
     *
     *   shouldSkip 의 `skipCount` 파라미터는 **Step 전체의 누적 skip 수**입니다.
     *   예외 종류를 구분하지 않습니다. 따라서 종류별 한도를 두려면
     *   **정책이 직접 카운터를 들어야** 합니다.
     */

    /** (c) 예외 종류별 한도를 갖는 정책. */
    public static class PerTypeSkipPolicy implements SkipPolicy {

        private final Map<Class<?>, Integer> limits = Map.of(
                IllegalArgumentException.class, 50,
                DataIntegrityViolationException.class, 200
        );

        private final Set<Class<?>> neverSkip = Set.of(
                DataAccessResourceFailureException.class
        );

        // 예외 타입별 누적 카운터.
        // ⚠️ ConcurrentHashMap + LongAdder 를 쓰는 이유는 멀티스레드 Step
        //    (Step 13)에서도 이 정책이 안전해야 하기 때문입니다.
        //    SkipPolicy 는 여러 스레드가 동시에 호출할 수 있습니다.
        private final Map<Class<?>, LongAdder> counters = new ConcurrentHashMap<>();

        @Override
        public boolean shouldSkip(Throwable t, long skipCount)
                throws SkipLimitExceededException {

            for (Class<?> never : neverSkip) {
                if (never.isInstance(t)) {
                    return false;
                }
            }

            for (Map.Entry<Class<?>, Integer> e : limits.entrySet()) {
                if (e.getKey().isInstance(t)) {
                    LongAdder counter = counters.computeIfAbsent(
                            e.getKey(), k -> new LongAdder());
                    counter.increment();
                    if (counter.sum() > e.getValue()) {
                        throw new SkipLimitExceededException(e.getValue(), t);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    /*
     * 대안 — BinaryExceptionClassifier 조합
     *
     *   instanceof 체인 대신 Spring Retry 의 분류기를 쓸 수도 있습니다.
     *
     *     BinaryExceptionClassifier skippable = new BinaryExceptionClassifier(
     *         Map.of(IllegalArgumentException.class, true,
     *                DataAccessResourceFailureException.class, false),
     *         false);           // 기본값 false = 모르면 skip 안 함
     *
     *   BinaryExceptionClassifier 는 **가장 가까운 상위 타입**을 찾아 매칭하므로
     *   선언 순서에 의존하지 않습니다. 즉 (b) 의 순서 함정이 원천적으로
     *   없습니다. 예외 종류가 많아지면 이쪽이 안전합니다.
     */

    // =====================================================================
    // 정답 3. 스캔 모드를 피해 commit_count 를 70 으로
    // =====================================================================

    /*
     * (a) 핵심 아이디어
     *
     *   **쓰기 단계에서 터질 조건을 처리 단계에서 미리 판정해 걸러낸다.**
     *
     *   왜 이게 효과가 있는가:
     *     - 쓰기(write) 에서 예외가 나면 청크 전체가 롤백되고 스캔 모드로
     *       들어갑니다. 1,000건짜리 청크가 1,000개의 트랜잭션이 됩니다.
     *     - 처리(process) 에서 null 을 반환해 필터링하면 **예외가 아닙니다.**
     *       롤백도 없고 스캔 모드도 없습니다. 그냥 그 아이템이 빠질 뿐입니다.
     *
     *   같은 "100건을 제외한다"는 결과인데, 예외로 하느냐 필터로 하느냐에
     *   따라 commit_count 가 69,900 과 70 으로 갈립니다.
     */

    /** (b) 구현 — 청크 단위로 미리 조회해 판정. */
    public static class PreCheckProcessor implements ItemProcessor<Order, Settlement> {

        private final Set<Long> alreadySettled;   // 미리 로드한 기존 정산 order_id
        private final Practice.SettlementProcessor delegate =
                new Practice.SettlementProcessor();

        public PreCheckProcessor(Set<Long> alreadySettled) {
            this.alreadySettled = alreadySettled;
        }

        @Override
        public Settlement process(Order order) {
            if (alreadySettled.contains(order.order_id())) {
                return null;        // 필터링. 예외가 아닙니다.
            }
            return delegate.process(order);
        }
    }

    /*
     * (c) ⚠️ 함정 — 아이템마다 검증 쿼리를 날리면
     *
     *   가장 먼저 떠오르는 구현은 이것입니다.
     *
     *     if (jdbcTemplate.queryForObject(
     *             "SELECT COUNT(*) FROM settlement WHERE order_id = ?",
     *             Integer.class, order.order_id()) > 0) {
     *         return null;
     *     }
     *
     *   동작은 합니다. 그런데 **70,000번의 SELECT** 가 발생합니다.
     *   측정하면 48.3초입니다. 스캔 모드(34.712초)보다 오히려 느립니다.
     *   "고쳤는데 더 느려졌다"는 전형적인 사례입니다.
     *
     *   해결책 두 가지:
     *
     *   ① Step 시작 시 한 번에 로드 (위 PreCheckProcessor)
     *
     *      @Bean @StepScope
     *      public Set<Long> alreadySettled(DataSource ds) {
     *          return new HashSet<>(new JdbcTemplate(ds).queryForList(
     *              "SELECT order_id FROM settlement", Long.class));
     *      }
     *
     *      쿼리 1회. 100건이면 메모리도 무시할 수준입니다.
     *      ⚠️ 다만 기존 정산이 수백만 건이면 이 Set 이 메모리를 먹습니다.
     *         그 경우 ②를 쓰십시오.
     *
     *   ② 청크 단위로 묶어서 조회 (ItemProcessor 대신 ItemWriter 앞단에서)
     *
     *      1,000건의 order_id 를 모아 IN 절로 한 번에 조회합니다.
     *      쿼리 70회. 메모리는 청크 크기에 비례할 뿐입니다.
     *
     * (d) 더 나은 답 — Reader 가 아예 안 읽게 한다
     *
     *   가장 좋은 해법은 애초에 그 행들을 읽지 않는 것입니다.
     *
     *     provider.setFromClause("""
     *         FROM orders o
     *         LEFT JOIN settlement s ON s.order_id = o.order_id
     *         """);
     *     provider.setWhereClause("""
     *         WHERE o.status = 'COMPLETED' AND s.order_id IS NULL
     *         """);
     *
     *   안티 조인으로 "아직 정산되지 않은 주문"만 읽습니다.
     *
     *   세 방식의 비교:
     *
     *   | 방식 | 소요 | commit | read | 비고 |
     *   |---|---|---|---|---|
     *   | 쓰기 skip (원본) | 34.712초 | 69,900 | 70,000 | 스캔 모드 |
     *   | 아이템별 검증 쿼리 | 48.300초 | 70 | 70,000 | **더 느려짐** |
     *   | 미리 로드 + 필터 | 8.271초 | 70 | 70,000 | filter_count 100 |
     *   | Reader 에서 제외 | 6.233초 | 70 | 69,900 | **최선** |
     *
     *   34.712초 → 6.233초. 약 5.6배입니다.
     *
     *   일반 원칙: **거를 수 있으면 최대한 앞에서 거르십시오.**
     *   Reader > Processor > Writer 순으로 앞일수록 비용이 쌉니다.
     *   Writer 에서 예외로 거르는 것이 가장 비쌉니다.
     */

    // =====================================================================
    // 정답 4. retryLimit 계산
    // =====================================================================

    /*
     * (a) retryLimit = **4**
     *
     *   .retryLimit(n) 은 내부적으로 SimpleRetryPolicy(maxAttempts = n) 을
     *   만듭니다. maxAttempts 는 **최초 시도를 포함한 총 시도 횟수**입니다.
     *
     *     retryLimit(1) → 재시도 0번 (최초 시도만)
     *     retryLimit(3) → 재시도 2번
     *     retryLimit(4) → 재시도 3번   ← 정답
     *
     *   즉 재시도를 N번 하고 싶으면 retryLimit(N + 1) 입니다.
     *
     *   ⚠️ 이 off-by-one 이 실무에서 SLA 계산을 어긋나게 합니다.
     *      "3번 재시도하니까 최대 3 × 200ms 대기"라고 계산했는데
     *      실제로는 2번만 재시도하고 포기하는 식입니다.
     *
     * (b) 최악의 총 대기 시간
     *
     *   ExponentialBackOffPolicy(initialInterval=200, multiplier=2.0) 의
     *   n번째 재시도 전 대기 = initialInterval × multiplier^(n-1)
     *
     *     1번째 재시도 전: 200 × 2^0 = 200ms
     *     2번째 재시도 전: 200 × 2^1 = 400ms
     *     3번째 재시도 전: 200 × 2^2 = 800ms
     *     ─────────────────────────────────
     *     총 대기          = 1,400ms
     *
     *   retryLimit(4) = 최초 1회 + 재시도 3회이므로 대기는 3번 발생합니다.
     *   **최악의 경우 1.4초**입니다.
     *
     * (c) maxInterval 미설정 시
     *
     *   ExponentialBackOffPolicy 의 maxInterval 기본값은 30,000ms(30초)입니다.
     *   완전히 무제한은 아니지만 충분히 위험합니다.
     *
     *   retryLimit 을 10 으로 두면:
     *     200 → 400 → 800 → 1600 → 3200 → 6400 → 12800 → 25600 → 30000 → 30000
     *     총 111초입니다. 한 청크에서.
     *
     *   그리고 이것이 **청크마다** 일어납니다. 70청크 전부에서 재시도가
     *   발생하면 배치가 2시간을 넘깁니다. 야간 배치 윈도를 넘겨
     *   아침 서비스에 영향을 줍니다.
     *
     *   maxInterval 을 명시적으로 짧게(5초 정도) 잡으십시오.
     *   그리고 재시도로 안 풀리는 문제는 재시도로 풀리지 않습니다.
     *
     * (d) .retryLimit() 을 빠뜨리면 → **조용히 아무 일도 안 일어납니다**
     *
     *   FaultTolerantStepBuilder 의 retryLimit 필드 기본값은 **0** 입니다.
     *   이 상태에서는 등록한 예외가 재시도 없이 그대로 전파됩니다.
     *
     *   - 컴파일 에러 없음
     *   - 경고 로그 없음
     *   - .retry(...) 를 쓴 흔적은 코드에 남아 있음
     *
     *   "retry 를 걸어 뒀으니 데드락은 알아서 복구되겠지"라고 믿고 있는데
     *   실제로는 첫 데드락에서 배치가 죽습니다.
     *
     *   ⚠️ .retry() 와 .retryLimit() 은 **항상 짝으로** 쓰십시오.
     *      skip 의 기본값이 10 인 것과 대조적입니다 — skip 은 조금이라도
     *      동작하는데 retry 는 아예 동작하지 않습니다.
     */

    // =====================================================================
    // 정답 5. 중단과 재시작
    // =====================================================================

    /*
     * (a) failAt=30000 으로 실행한 결과
     *
     *   +------------------+--------+------------+-------------+--------------+
     *   | JOB_EXECUTION_ID | STATUS | READ_COUNT | WRITE_COUNT | COMMIT_COUNT |
     *   +------------------+--------+------------+-------------+--------------+
     *   |                1 | FAILED |      30000 |       29000 |           29 |
     *   +------------------+--------+------------+-------------+--------------+
     *
     *   read_count   = 30,000  (30,000번째를 읽고 나서 처리 중 죽음)
     *   write_count  = 29,000
     *   commit_count = 29
     *   status       = FAILED
     *
     * (b) settlement 행 수 → **29,000**
     *
     *   29개 청크(29,000건)가 정상 커밋됐고, 30번째 청크는 처리 도중
     *   예외가 나서 **통째로 롤백**됐습니다. 그 청크의 29,001~30,000번째
     *   아이템은 하나도 저장되지 않았습니다.
     *
     *   이것이 "청크 = 트랜잭션 = 롤백 단위"의 의미입니다.
     *   부분적으로 저장되는 일은 없습니다. 전부 아니면 전무입니다.
     *
     * (c) 같은 JobInstance 에 붙습니다
     *
     *   두 가지 조건이 모두 만족되기 때문입니다.
     *
     *   ① 직전 실행이 FAILED 입니다.
     *      COMPLETED 였다면 JobInstanceAlreadyCompleteException 이 나서
     *      아예 실행되지 않습니다(Step 03).
     *      FAILED 는 "재시작 가능" 상태입니다.
     *
     *   ② failAt 이 identifying = false 입니다.
     *      JOB_KEY 는 identifying 파라미터만으로 계산됩니다. failAt 이
     *      제외되므로 failAt=30000 이든 999999 든 **JOB_KEY 가 같습니다.**
     *      따라서 같은 JobInstance 로 인식되어 재시작이 됩니다.
     *
     *      ⚠️ 만약 identifying=true 였다면 failAt 값이 달라지는 순간
     *         JOB_KEY 가 바뀌어 **새 JobInstance** 가 만들어집니다.
     *         재시작이 아니라 처음부터 새로 도는 것이고, 이미 정산된
     *         29,000건과 UNIQUE 충돌이 나서 즉시 죽습니다.
     *         Practice 의 주석이 이 false 를 강조한 이유입니다.
     *
     *   메타데이터로 확인하면 JOB_INSTANCE_ID 가 같고 JOB_EXECUTION_ID 만
     *   늘어난 것이 보입니다.
     *
     *   +-----------------+------------------+-----------+------------+
     *   | JOB_INSTANCE_ID | JOB_EXECUTION_ID | STATUS    | READ_COUNT |
     *   +-----------------+------------------+-----------+------------+
     *   |               1 |                1 | FAILED    |      30000 |
     *   |               1 |                2 | COMPLETED |      41000 |
     *   +-----------------+------------------+-----------+------------+
     *
     *   1:N 관계가 눈에 보입니다. Step 01 의 다이어그램 그대로입니다.
     */

    // =====================================================================
    // 정답 6. 상태를 저장하는 Reader — 이 스텝의 결론
    // =====================================================================

    /*
     * (a) NaiveReader 로 재시작하면 read_count = **70,000**
     *
     *   재시작인데 처음부터 70,000건을 다시 읽었습니다.
     *
     *   왜냐하면 NaiveReader 는 ItemReader 만 구현했고 ItemStreamReader 가
     *   아니기 때문입니다. open/update/close 콜백이 아예 없으므로
     *   Spring Batch 는 이 Reader 의 위치를 저장할 방법도, 복원할 방법도
     *   없습니다. 매번 index = 0 에서 시작합니다.
     *
     *   그리고 결과가 갈립니다.
     *     - 비멱등 Writer(순수 INSERT): 이미 정산된 29,000건과 UNIQUE 가
     *       충돌해 즉시 DuplicateKeyException. 시끄럽게 실패합니다.
     *     - 멱등 Writer(ON DUPLICATE KEY UPDATE): **조용히 29,000건을
     *       다시 정산합니다.** 결과 데이터는 우연히 맞지만, 41,000건이면
     *       될 일을 70,000건 처리했습니다. 그리고 만약 수수료율이 그 사이
     *       바뀌었다면 이미 정산된 건이 새 요율로 덮어써집니다.
     *
     *   후자가 훨씬 위험합니다. 이 코스가 반복하는 주제입니다.
     *
     * (b) open / update / close 호출 시점
     *
     *   | 콜백 | 호출 시점 | 횟수 | 용도 |
     *   |---|---|---|---|
     *   | open(ctx) | Step 시작 직후 | 1회 | 자원 열기 + **재시작 위치 복원** |
     *   | update(ctx) | **매 청크 커밋 직전** | 청크 수만큼 | 현재 위치 기록 |
     *   | close() | Step 종료 시 | 1회 | 자원 정리 (성공/실패 무관) |
     *
     *   ⚠️ update() 가 "커밋 직전"이라는 것이 중요합니다.
     *      청크 트랜잭션과 **같은 트랜잭션 안에서** ExecutionContext 가
     *      저장되므로, 데이터 저장과 위치 기록이 원자적으로 함께 커밋됩니다.
     *      롤백되면 둘 다 롤백됩니다. 그래서 "29,000건 저장 + 위치 29,000"이
     *      항상 일관됩니다. 프로젝트 셋업에서 메타데이터와 업무 데이터를
     *      같은 DataSource 에 둔 이유가 이것입니다.
     */

    /** (b)(c)(d) 정답 구현. */
    public static class RestartableReader implements ItemStreamReader<Order> {

        // (c) 키에 Reader 이름을 접두사로 붙입니다.
        private static final String KEY_INDEX = "restartableReader.index";

        private final List<Order> orders;
        private int index = 0;

        public RestartableReader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public void open(ExecutionContext ctx) throws ItemStreamException {
            // (d) 재시작 판정 관용구
            if (ctx.containsKey(KEY_INDEX)) {
                this.index = ctx.getInt(KEY_INDEX);
            } else {
                this.index = 0;
            }
        }

        @Override
        public Order read() {
            return index >= orders.size() ? null : orders.get(index++);
        }

        @Override
        public void update(ExecutionContext ctx) throws ItemStreamException {
            ctx.putInt(KEY_INDEX, index);
        }

        @Override
        public void close() throws ItemStreamException {
            // 정리할 자원 없음. 파일이나 커넥션을 열었다면 여기서 닫습니다.
        }
    }

    /*
     * (c) 키 이름을 "index" 로 하면
     *
     *   ExecutionContext 는 **Step 전체가 공유하는 하나의 Map** 입니다.
     *   Reader 별로 분리된 공간이 아닙니다.
     *
     *   한 Step 에 Reader 가 둘이면(예: 두 파일을 번갈아 읽는 구성이나
     *   CompositeItemReader) 둘 다 "index" 키에 쓰게 되어 **서로를
     *   덮어씁니다.** 재시작하면 A Reader 가 B Reader 의 위치에서
     *   시작합니다.
     *
     *   그래서 Spring Batch 의 기본 Reader 들은 전부 setName() 을 요구하고,
     *   그 이름을 키 접두사로 씁니다.
     *
     *     new JdbcPagingItemReaderBuilder<Order>()
     *         .name("orderReader")     // ← 이것이 키 접두사가 됩니다
     *
     *   실제 저장된 값을 보면 이렇게 생겼습니다.
     *
     *     SELECT SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT ...
     *     {"@class":"java.util.HashMap","orderReader.read.count":29000}
     *
     *   ⚠️ .name() 을 빠뜨리면 ItemStreamSupport 가
     *      "ItemStream must have a name" 예외를 던집니다.
     *      단, saveState(false) 면 이름이 필요 없어 통과합니다 —
     *      상태를 저장하지 않으니 키도 필요 없기 때문입니다.
     *
     * (e) 이걸 직접 만들어야 하는가 → **아니오**
     *
     *   실무에서는 `JdbcPagingItemReader`, `JdbcCursorItemReader`,
     *   `FlatFileItemReader` 같은 기본 제공 Reader 를 쓰십시오.
     *   전부 ItemStreamReader 를 이미 올바르게 구현하고 있습니다.
     *   재시작, 이름 기반 키, 스레드 안전성 문서화까지 다 되어 있습니다.
     *
     *   직접 만들어야 하는 경우는 정말 드뭅니다.
     *   (외부 API 를 페이지네이션하며 읽는 Reader 정도)
     *
     *   그런데 왜 이 문제를 풀었는가:
     *
     *   **직접 만들 수 있게 된 다음에야, 안 만드는 선택이 의미가 있기
     *   때문입니다.**
     *
     *   ItemStreamReader 의 계약을 모르면 다음을 이해할 수 없습니다.
     *     - 왜 Reader 에 .name() 을 줘야 하는지
     *     - 왜 saveState(false) 를 켜면 재시작이 안 되는지 (Step 13)
     *     - 왜 @Bean 이 아닌 Reader 는 콜백을 못 받는지 (Step 08, 09)
     *     - 재시작 시 read_count 가 왜 41,000 인지
     *
     *   이 네 가지는 전부 open/update/close 계약에서 나옵니다.
     *   기본 Reader 를 "그냥 쓰는" 사람과 "왜 그렇게 동작하는지 아는"
     *   사람의 차이가 장애 대응에서 갈립니다.
     */
}
