package com.example.batch.step07;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.support.ClassifierCompositeItemProcessor;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 07 — 연습문제 6문항.
 *
 * 규칙
 *  - "여기에 작성:" 자리를 채우세요.
 *  - 문제 3·4 는 코드보다 "예측"이 본체입니다. 실행하기 전에 반드시 주석에 예측을 적으세요.
 *  - 실행 전마다 초기화:
 *      mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
 *
 * 정답은 Solution.java.
 */
public class Exercise {

    // 공용 헬퍼 — customers 1,000행의 fee_rate 맵
    static Map<Integer, BigDecimal> loadFeeRates(JdbcTemplate jdbcTemplate) {
        Map<Integer, BigDecimal> map = new HashMap<>(1400);
        jdbcTemplate.query("SELECT customer_id, fee_rate FROM customers",
                rs -> { map.put(rs.getInt("customer_id"), rs.getBigDecimal("fee_rate")); });
        return Map.copyOf(map);
    }

    // ======================================================================
    // 문제 1 — Order -> Settlement 변환과 반올림 규칙
    // ======================================================================
    /**
     * 요구사항
     *  - 고객 등급별 fee_rate 를 적용해 Settlement 을 만든다.
     *  - settle_date 는 ordered_at 의 날짜 부분.
     *  - 실행 후 다음이 성립해야 한다:
     *      SELECT SUM(gross_amount) - SUM(fee_amount) = SUM(net_amount) FROM settlement;
     *
     * 힌트: BigDecimal.multiply 는 두 피연산자의 스케일을 더합니다(2 + 4 = 6).
     *       어느 시점에 setScale(2, HALF_UP) 을 해야 위 등식이 성립할까요?
     */
    public static class Q1_SettlementProcessor implements ItemProcessor<Order, Settlement> {

        private final Map<Integer, BigDecimal> feeRateByCustomer;

        public Q1_SettlementProcessor(JdbcTemplate jdbcTemplate) {
            this.feeRateByCustomer = loadFeeRates(jdbcTemplate);
        }

        @Override
        public Settlement process(Order order) {
            BigDecimal rate = feeRateByCustomer.get(order.customerId());
            BigDecimal gross = order.amount();

            // 여기에 작성: fee 와 net 을 계산하고 Settlement 을 반환하세요.
            //             반올림을 "어디서 한 번" 할지가 핵심입니다.

            return null;
        }
    }

    // ======================================================================
    // 문제 2 — null 필터링과 filterCount
    // ======================================================================
    /**
     * 리더는 WHERE 절 없이 orders 10만 건을 전부 읽습니다(이 조건을 되돌리지 마세요).
     * status 가 COMPLETED 가 아닌 아이템을 걸러 내세요.
     *
     * 실행 후 확인:
     *   SELECT READ_COUNT, WRITE_COUNT, FILTER_COUNT
     *   FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
     *
     * 기대값을 먼저 적어 두세요.
     *   readCount   = ______
     *   writeCount  = ______
     *   filterCount = ______
     */
    public static class Q2_CompletedOnly implements ItemProcessor<Order, Order> {

        @Override
        public Order process(Order order) {
            // 여기에 작성:

            return order;
        }
    }

    // ======================================================================
    // 문제 3 — 예외 방식으로 바꾸면 통계가 어떻게 달라지는가 (예측 문제)
    // ======================================================================
    /**
     * 아래는 문제 2 와 "같은 비즈니스 규칙"을 예외로 구현한 것입니다.
     * Step 설정에는 .faultTolerant().skip(IllegalArgumentException.class).skipLimit(50000)
     * 이 붙어 있다고 가정합니다.
     *
     * 실행하기 전에 예측을 적으세요.
     *   filterCount        = ______
     *   processSkipCount   = ______
     *   rollbackCount      = 대략 ______
     *   Job 최종 STATUS    = ______
     *
     * 그리고 "왜 rollbackCount 가 그 값인가"를 한 문장으로 적으세요.
     *   여기에 작성:
     *
     * 마지막으로: 이 구현이 왜 나쁜지 세 가지를 적으세요.
     *   (1) 여기에 작성:
     *   (2) 여기에 작성:
     *   (3) 여기에 작성:
     */
    public static class Q3_ExceptionBased implements ItemProcessor<Order, Order> {

        @Override
        public Order process(Order order) {
            if (!"COMPLETED".equals(order.status())) {
                throw new IllegalArgumentException("정산 대상 아님: " + order.order_id());
            }
            return order;
        }
    }

    // ======================================================================
    // 문제 4 — CompositeItemProcessor 의 타입 함정 (예측 + 리팩터링)
    // ======================================================================
    /**
     * (a) 아래 파이프라인은 컴파일됩니다. 실행하면 어떤 일이 벌어질까요?
     *     예외 클래스 이름 = ______________________
     *     스택트레이스가 가리키는 클래스 = ______________________
     *     실제로 고쳐야 하는 코드의 위치 = ______________________
     *
     * (b) 그다음, 같은 파이프라인을 "순서를 틀리면 컴파일이 실패하도록" 다시 쓰세요.
     */
    public static class Q4_BrokenComposite {

        public ItemProcessor<Order, Settlement> broken(JdbcTemplate jdbcTemplate) {
            CompositeItemProcessor<Order, Settlement> composite = new CompositeItemProcessor<>();
            composite.setDelegates(List.of(
                    new Q1_SettlementProcessor(jdbcTemplate),   // Order -> Settlement
                    new Q2_CompletedOnly()                      // Order 를 기대한다
            ));
            return composite;
        }

        /** (b) 컴파일러가 순서를 검사해 주는 형태로 다시 작성하세요. */
        public ItemProcessor<Order, Settlement> fixed(JdbcTemplate jdbcTemplate) {
            // 여기에 작성:

            return null;
        }
    }

    // ======================================================================
    // 문제 5 — ClassifierCompositeItemProcessor 분기
    // ======================================================================
    /**
     * 규칙
     *  - status != 'COMPLETED'  -> null (정산 제외)
     *  - fee_rate == 0.0200 (VIP) -> 수수료율에서 0.0050 을 추가 할인
     *  - 그 외                   -> 표준 처리
     *
     * 제약: classify() 안에서 DB 를 조회하면 안 됩니다. 이유도 주석으로 적으세요.
     *   여기에 작성(이유):
     *
     * 실행 후 확인:
     *   SELECT c.grade, s.fee_rate, COUNT(*) FROM settlement s
     *   JOIN customers c ON c.customer_id = s.customer_id
     *   GROUP BY c.grade, s.fee_rate ORDER BY s.fee_rate DESC;
     * VIP 행의 fee_rate 가 ______ 이어야 합니다.
     */
    public static class Q5_Classifying {

        public ItemProcessor<Order, Settlement> build(JdbcTemplate jdbcTemplate) {
            ClassifierCompositeItemProcessor<Order, Settlement> processor =
                    new ClassifierCompositeItemProcessor<>();

            // 여기에 작성: standard / vip / drop 프로세서를 만들고 classifier 를 설정하세요.

            return processor;
        }
    }

    // ======================================================================
    // 문제 6 — 상태를 가진 프로세서 리팩터링
    // ======================================================================
    /**
     * 아래 프로세서는 단일 스레드로 돌리면 정확한 값을 냅니다.
     * Job 이 COMPLETED 로 끝나도 통과한 것이 아닙니다.
     *
     * (a) 이 코드가 틀리는 두 상황을 적으세요.
     *     상황 1: 여기에 작성:
     *     상황 2: 여기에 작성:
     *
     * (b) processed 를 AtomicLong 으로 바꾸면 두 상황이 모두 해결됩니까? 답과 이유:
     *     여기에 작성:
     *
     * (c) 무상태로 리팩터링하세요. 처리 건수와 누적 합계를 각각 어디서 얻어야 합니까?
     *     처리 건수: 여기에 작성:
     *     누적 합계: 여기에 작성:
     */
    public static class Q6_Stateful implements ItemProcessor<Order, Settlement> {

        private int processed = 0;
        private BigDecimal runningTotal = BigDecimal.ZERO;
        private final ItemProcessor<Order, Settlement> delegate;

        public Q6_Stateful(ItemProcessor<Order, Settlement> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Settlement process(Order order) throws Exception {
            processed++;
            runningTotal = runningTotal.add(order.amount());
            return delegate.process(order);
        }
    }

    /** (c) 여기에 무상태 버전을 작성하세요. */
    public static class Q6_Stateless implements ItemProcessor<Order, Settlement> {

        // 여기에 작성: 필드는 전부 final + 불변이어야 합니다.

        @Override
        public Settlement process(Order order) {
            // 여기에 작성:

            return null;
        }
    }
}
