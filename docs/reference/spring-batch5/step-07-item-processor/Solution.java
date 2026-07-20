package com.example.batch.step07;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.support.ClassifierCompositeItemProcessor;
import org.springframework.classify.Classifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Step 07 — 연습문제 정답과 해설.
 *
 * 문제를 직접 풀어 본 뒤에 여세요.
 */
public class Solution {

    static Map<Integer, BigDecimal> loadFeeRates(JdbcTemplate jdbcTemplate) {
        Map<Integer, BigDecimal> map = new HashMap<>(1400);
        jdbcTemplate.query("SELECT customer_id, fee_rate FROM customers",
                rs -> { map.put(rs.getInt("customer_id"), rs.getBigDecimal("fee_rate")); });
        return Map.copyOf(map);   // 불변화 — 멀티스레드에서도 안전
    }

    // ======================================================================
    // 정답 1 — 반올림은 "한 번", "가장 이른 시점"에
    // ======================================================================
    /*
     * 핵심은 계산 순서입니다.
     *
     *   ✅ fee = gross.multiply(rate).setScale(2, HALF_UP);   // 여기서 2자리로 "확정"
     *      net = gross.subtract(fee);                          // 확정된 fee 로 뺀다
     *
     *   ❌ fee = gross.multiply(rate);                         // 스케일 6
     *      net = gross.subtract(fee);                          // net 도 스케일 6
     *      // DB 가 행마다 알아서 2자리로 반올림한다
     *
     * 왜 ❌ 가 문제인가:
     *   gross=1050.00, rate=0.0250 이면 fee = 26.250000, net = 1023.750000 입니다.
     *   DB 의 DECIMAL(12,2) 는 각 행을 독립적으로 반올림하므로
     *     fee -> 26.25 (그대로), net -> 1023.75 (그대로)  ← 이 경우는 우연히 맞습니다.
     *   그러나 gross=1025.00, rate=0.0350 이면 fee = 35.875000, net = 989.125000 이고
     *     fee -> 35.88 (올림), net -> 989.13 (올림)
     *   35.88 + 989.13 = 1025.01 != 1025.00.  한 건당 0.01원이 "생겨납니다".
     *
     *   ✅ 방식이면 fee = 35.88 로 먼저 확정하고 net = 1025.00 - 35.88 = 989.12 이므로
     *   언제나 gross = fee + net 이 성립합니다.
     *
     * 7만 건 기준 실제 검증 결과:
     *   +-------+---------------+--------------+---------------+
     *   | cnt   | gross         | fee          | net           |
     *   | 70000 | 3485250000.00 |  97089107.00 | 3388160893.00 |
     *   +-------+---------------+--------------+---------------+
     *   3485250000.00 - 97089107.00 = 3388160893.00  ← 정확히 일치
     *
     * 교훈: 반올림은 "회계 규칙"이지 "표시 형식"이 아닙니다.
     *       어느 시점에 몇 자리로 확정할지를 코드가 명시적으로 정해야 합니다.
     *       DB 에 맡기면, DB 는 각 컬럼을 서로 모른 채 따로 반올림합니다.
     */
    public static class A1_SettlementProcessor implements ItemProcessor<Order, Settlement> {

        private final Map<Integer, BigDecimal> feeRateByCustomer;

        public A1_SettlementProcessor(JdbcTemplate jdbcTemplate) {
            this.feeRateByCustomer = loadFeeRates(jdbcTemplate);
        }

        @Override
        public Settlement process(Order order) {
            BigDecimal rate = feeRateByCustomer.get(order.customerId());
            if (rate == null) {
                throw new IllegalStateException("등급 정보 없는 고객: " + order.customerId());
            }
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(fee);

            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(), gross, rate, fee, net);
        }
    }

    // ======================================================================
    // 정답 2 — null 반환. 기대값 readCount=100000, writeCount=70000, filterCount=30000
    // ======================================================================
    /*
     * orders 는 100,000행이고 status 분포는 COMPLETED 70,000 / CANCELLED 10,000 /
     * PENDING 10,000 / REFUNDED 10,000 입니다. 따라서 30,000건이 걸러집니다.
     *
     * 실제 BATCH_STEP_EXECUTION:
     *   READ_COUNT=100000, WRITE_COUNT=70000, FILTER_COUNT=30000,
     *   COMMIT_COUNT=101, ROLLBACK_COUNT=0, PROCESS_SKIP_COUNT=0
     *
     * COMMIT_COUNT 가 101 인 이유: 100,000 / 1,000 = 100 청크 + "더 읽을 게 없음"을
     * 확인하는 마지막 빈 커밋 1회.
     *
     * 항등식: readCount = writeCount + filterCount + skipCount
     *         100000    = 70000      + 30000       + 0
     */
    public static class A2_CompletedOnly implements ItemProcessor<Order, Order> {

        @Override
        public Order process(Order order) {
            if (!"COMPLETED".equals(order.status())) {
                return null;   // 예외가 아닙니다. 정상 흐름입니다.
            }
            return order;
        }
    }

    // ======================================================================
    // 정답 3 — 예외 방식으로 바꾸면
    // ======================================================================
    /*
     * 예측 정답:
     *   filterCount      = 0
     *   processSkipCount = 30000
     *   rollbackCount    ≈ 30000 (스킵 건수와 거의 같음)
     *   Job STATUS       = COMPLETED   (skipLimit=50000 이라 한도 안에 들어옴)
     *                      skipLimit 을 10 으로 두었다면 FAILED 입니다.
     *
     * rollbackCount 가 왜 그렇게 큰가:
     *   Spring Batch 의 스킵은 "예외가 난 아이템만 빼고 계속"이 아닙니다.
     *   process 에서 스킵 가능한 예외가 나면 프레임워크는
     *     (1) 청크 트랜잭션 전체를 롤백하고
     *     (2) 그 청크를 "아이템 하나씩 커밋하는 모드"로 재시도해
     *     (3) 문제 아이템만 골라내 스킵한 뒤 나머지를 다시 처리합니다.
     *   즉 스킵 1건이 청크 하나의 재실행을 부릅니다.
     *   30,000건이 흩어져 있으므로 거의 모든 청크가 최소 한 번씩 롤백됩니다.
     *
     * 이 구현이 나쁜 세 가지:
     *   (1) 통계가 오염된다.
     *       "정상적으로 제외한 30,000건"과 "장애로 버린 건"이 같은 칸에 섞입니다.
     *       모니터링의 skipCount 알람이 영구히 무의미해집니다.
     *   (2) skipLimit 을 넘기면 Job 이 FAILED 로 끝난다.
     *       데이터는 아무 문제가 없는데 배치가 실패합니다.
     *       그리고 skipLimit 을 데이터 분포에 맞춰 계속 키워야 하는 악순환이 생깁니다.
     *   (3) 비싸다.
     *       예외 30,000개 생성 + 스택트레이스 채우기 + 청크 롤백/재처리.
     *       측정하면 null 방식 7s034ms 대비 40초 이상으로 벌어집니다.
     *
     * 원칙 한 줄:
     *   "정상적으로 제외할 것은 null 로, 진짜 잘못된 데이터만 예외로."
     */

    // ======================================================================
    // 정답 4 — 타입 함정과 컴파일 타임 검증
    // ======================================================================
    /*
     * (a)
     *   예외 클래스        : java.lang.ClassCastException
     *                        "class ...domain.Settlement cannot be cast to class ...domain.Order"
     *   스택트레이스가 가리키는 곳 : Q2_CompletedOnly.process()  (2번째 델리게이트)
     *   실제로 고쳐야 하는 곳      : composite.setDelegates(List.of(...)) 의 "순서"
     *
     *   이 어긋남이 이 함정의 본질입니다. 터지는 위치와 원인 위치가 다릅니다.
     *   처음 보면 Q2_CompletedOnly 를 열어 놓고 한참을 들여다보게 됩니다.
     *
     *   왜 컴파일러가 못 잡는가:
     *     public void setDelegates(List<? extends ItemProcessor<?, ?>> delegates)
     *   파라미터 타입이 ItemProcessor<?, ?> 입니다. 어떤 프로세서든 들어갑니다.
     *   CompositeItemProcessor.process() 안에서는
     *     result = processItem((ItemProcessor<Object, Object>) delegate, result);
     *   로 캐스팅해 돌리므로, 실제 검사는 델리게이트 메서드 진입 시점의
     *   체크캐스트에서야 일어납니다.
     *
     *   더 위험한 변형: 두 타입이 캐스팅 가능한 관계(상속 관계, 혹은 둘 다 Map)라면
     *   예외조차 나지 않고 엉뚱한 필드를 읽어 조용히 틀린 값을 씁니다.
     *
     * (b) 아래 fixed() 처럼 직접 합성하면 컴파일러가 검사합니다.
     *     순서를 바꿔 step3 을 먼저 호출하도록 고쳐 보세요:
     *       Order a = step3.process(order);
     *     ->  error: incompatible types: Settlement cannot be converted to Order
     *     빌드 단계에서 즉시 잡힙니다.
     *
     *   대가: null 전파를 손으로 써야 합니다. 단계가 3~4개로 고정된 파이프라인이라면
     *   이 대가가 훨씬 쌉니다. 델리게이트를 런타임에 조립해야 하는 경우에만
     *   CompositeItemProcessor 를 쓰고, 그때는 반드시 아이템 1건짜리 단위 테스트를
     *   붙이세요. composite.process(sampleOrder) 한 줄이면 잡힙니다.
     */
    public static class A4_Fixed {

        public ItemProcessor<Order, Settlement> fixed(JdbcTemplate jdbcTemplate) {
            ItemProcessor<Order, Order> step1 = new A2_CompletedOnly();
            ItemProcessor<Order, Settlement> step2 = new A1_SettlementProcessor(jdbcTemplate);

            return order -> {
                Order filtered = step1.process(order);
                if (filtered == null) return null;      // null 전파를 명시적으로
                return step2.process(filtered);
            };
        }
    }

    // ======================================================================
    // 정답 5 — ClassifierCompositeItemProcessor
    // ======================================================================
    /*
     * classify() 안에서 DB 를 조회하면 안 되는 이유:
     *   classify() 는 process() 와 마찬가지로 "아이템마다 한 번" 호출됩니다.
     *   여기서 SELECT 를 날리면 7-3 에서 캐시로 없앤 7만 번의 왕복이 그대로 되살아납니다.
     *   분류는 "이미 손에 든 정보"만으로 끝나야 합니다.
     *   필요한 정보는 (a) 리더가 실어 온 필드이거나 (b) 생성 시점에 캐시된 맵이어야 합니다.
     *
     * VIP 행의 fee_rate 는 0.0150 (= 0.0200 - 0.0050) 입니다.
     *
     * 실행 결과:
     *   +--------+----------+-------+
     *   | grade  | fee_rate | cnt   |
     *   +--------+----------+-------+
     *   | BRONZE |   0.0350 | 20000 |
     *   | SILVER |   0.0300 | 15000 |
     *   | GOLD   |   0.0250 | 20000 |
     *   | VIP    |   0.0150 | 15000 |
     *   +--------+----------+-------+
     *   readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
     *
     * 참고: 아이템의 "클래스 타입"으로만 분기하면 되는 흔한 경우에는
     *   new SubclassClassifier<>(Map.of(A.class, p1, B.class, p2), defaultP)
     * 한 줄로 끝납니다.
     */
    public static class A5_Classifying {

        private static final BigDecimal VIP_RATE = new BigDecimal("0.0200");
        private static final BigDecimal VIP_DISCOUNT = new BigDecimal("0.0050");

        public ItemProcessor<Order, Settlement> build(JdbcTemplate jdbcTemplate) {

            Map<Integer, BigDecimal> rates = loadFeeRates(jdbcTemplate);

            ItemProcessor<Order, Settlement> standard = new A1_SettlementProcessor(jdbcTemplate);

            ItemProcessor<Order, Settlement> vip = order -> {
                BigDecimal rate = rates.get(order.customerId()).subtract(VIP_DISCOUNT);
                BigDecimal gross = order.amount();
                BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                return new Settlement(order.order_id(), order.customerId(),
                        order.orderedAt().toLocalDate(), gross, rate, fee, gross.subtract(fee));
            };

            ItemProcessor<Order, Settlement> drop = order -> null;

            ClassifierCompositeItemProcessor<Order, Settlement> processor =
                    new ClassifierCompositeItemProcessor<>();

            Classifier<Order, ItemProcessor<?, ? extends Settlement>> classifier = order -> {
                if (!"COMPLETED".equals(order.status())) return drop;   // filterCount 로 잡힘
                return VIP_RATE.compareTo(rates.get(order.customerId())) == 0 ? vip : standard;
            };
            processor.setClassifier(classifier);
            return processor;
        }
    }

    // ======================================================================
    // 정답 6 — 무상태 리팩터링
    // ======================================================================
    /*
     * (a) 틀리는 두 상황
     *   상황 1: 재시작.
     *     7만 건 중 5만 건까지 처리하고 죽은 뒤 재시작하면, 리더는 ExecutionContext 덕에
     *     5만 건째부터 이어가지만 processed 필드는 새 인스턴스라 0 입니다.
     *     최종 리포트가 20,000 으로 찍힙니다. 예외도 경고도 없습니다.
     *   상황 2: 멀티스레드.
     *     .taskExecutor(...) 를 붙이면 하나의 프로세서 인스턴스를 여러 스레드가 공유합니다.
     *     processed++ 는 읽고-더하고-쓰는 세 동작이라 원자적이지 않고,
     *     runningTotal = runningTotal.add(...) 도 참조 교체라 덮어쓰기가 일어납니다.
     *     BigDecimal 이 불변인 것은 도움이 되지 않습니다 — 불변인 것은 "값"이지
     *     "필드 참조"가 아니기 때문입니다.
     *     실측: processed=68847, runningTotal=3421880400.00 (기대 70000 / 3485250000.00)
     *
     * (b) AtomicLong 으로 바꾸면?
     *     아니오. 상황 2 만 해결되고 상황 1 은 그대로입니다.
     *     AtomicLong 도 JVM 힙에 있는 값이라 프로세스가 죽으면 사라집니다.
     *     "동시성 문제"와 "영속성 문제"는 다른 문제입니다.
     *     이 오답이 흔한 이유는, 멀티스레드에서 틀린 값을 보고 원인을 동시성 하나로만
     *     좁혀 버리기 때문입니다.
     *
     * (c) 어디서 얻어야 하는가
     *     처리 건수 : StepExecution.getWriteCount() / getFilterCount() / getReadCount().
     *                 프레임워크가 이미 세고 있고, BATCH_STEP_EXECUTION 에 영속되므로
     *                 재시작에도 살아남습니다. 직접 셀 이유가 없습니다.
     *     누적 합계 : StepExecution 의 ExecutionContext.
     *                 청크 커밋마다 함께 커밋되므로 재시작 시 마지막 커밋 지점의 값이
     *                 복원됩니다. (Step 09 에서 다룹니다)
     *                 리스너에서 @BeforeStep 으로 StepExecution 을 주입받아
     *                 executionContext.put("runningTotal", ...) 로 갱신합니다.
     *     최종 리포트: StepExecutionListener.afterStep() 또는 별도 Tasklet Step (Step 12).
     *
     * 규칙 한 줄:
     *   ItemProcessor 구현체의 모든 인스턴스 필드에 final 을 붙이고,
     *   그 안에 담기는 컬렉션도 불변으로 만든다.
     *   final 을 못 붙이겠다는 필드가 생기면, 그게 설계가 잘못됐다는 신호입니다.
     */
    public static class A6_Stateless implements ItemProcessor<Order, Settlement> {

        private final Map<Integer, BigDecimal> feeRateByCustomer;   // final + 불변

        public A6_Stateless(JdbcTemplate jdbcTemplate) {
            this.feeRateByCustomer = loadFeeRates(jdbcTemplate);
        }

        @Override
        public Settlement process(Order order) {
            BigDecimal rate = feeRateByCustomer.get(order.customerId());
            BigDecimal gross = order.amount();
            BigDecimal fee = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            return new Settlement(order.order_id(), order.customerId(),
                    order.orderedAt().toLocalDate(), gross, rate, fee, gross.subtract(fee));
        }
    }
}
