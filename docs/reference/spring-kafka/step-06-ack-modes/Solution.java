package com.example.order.step06;

/*
 * ============================================================================
 * Step 06 — 오프셋 커밋과 AckMode : Solution (정답과 해설)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step06/Solution.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step06sol --app.step06sol.run=q4'
 *
 * Exercise.java 와 빈 이름이 겹치지 않도록 프로필과 빈 이름을 모두 분리했습니다.
 * 두 프로필을 동시에 켜지 마세요.
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Solution {

    private Solution() {
    }

    static final String TOPIC = "orders";
    static final String DLT = "orders.DLT";

    // ========================================================================
    // 정답 1. AckMode 팩터리 6종
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - AckMode 는 ContainerProperties 의 속성입니다. ConsumerFactory 가 아니라
     *    "컨테이너"의 정책이므로 f.getContainerProperties().setAckMode(...) 로 설정합니다.
     *    application.yml 의 spring.kafka.listener.ack-mode 는 Boot 가 만들어 준
     *    기본 팩터리(kafkaListenerContainerFactory)에만 적용되고,
     *    여기처럼 직접 new 한 팩터리에는 아무 영향이 없습니다. 그래서 모드마다 명시합니다.
     *
     *  - COUNT 의 ackCount 는 "정확히 100건마다"가 아닙니다. 컨테이너는 처리한 레코드 수가
     *    ackCount 이상이 되는 시점에 커밋합니다. max-poll-records=500 이면 한 번의 poll 안에서
     *    카운터가 100, 200, ... 을 지나며 여러 번 커밋이 나갑니다.
     *
     *  - TIME 의 ackTime 도 "3초마다 타이머"가 아니라, 마지막 커밋 이후 3초가 지난 상태에서
     *    다음 커밋 기회(레코드 처리 후 또는 poll 종료 시점)가 왔을 때 커밋합니다.
     *    트래픽이 없으면 커밋도 없습니다. 이게 TIME 모드에서 가장 자주 오해하는 지점입니다.
     *
     *  - COUNT_TIME 은 OR 조건입니다. 둘 다 설정하지 않으면 의미가 없습니다.
     *
     *  - concurrency(1) 은 학습용 장치입니다. 3으로 두면 파티션마다 별도 컨슈머가 돌아
     *    "Committing offsets:" 로그가 세 갈래로 섞이고 횟수를 셀 수 없습니다.
     */
    @Configuration
    @Profile("step06sol")
    public static class SolFactories {

        private final ConsumerFactory<String, OrderCreated> consumerFactory;

        public SolFactories(ConsumerFactory<String, OrderCreated> consumerFactory) {
            this.consumerFactory = consumerFactory;
        }

        private ConcurrentKafkaListenerContainerFactory<String, OrderCreated> base(AckMode mode) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            f.setConcurrency(1);
            f.getContainerProperties().setAckMode(mode);
            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solCountFactory() {
            var f = base(AckMode.COUNT);
            f.getContainerProperties().setAckCount(100);
            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solTimeFactory() {
            var f = base(AckMode.TIME);
            f.getContainerProperties().setAckTime(3_000L);
            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solCountTimeFactory() {
            var f = base(AckMode.COUNT_TIME);
            f.getContainerProperties().setAckCount(100);
            f.getContainerProperties().setAckTime(3_000L);
            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solManualImmediateFactory() {
            return base(AckMode.MANUAL_IMMEDIATE);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solBatchListenerFactory() {
            var f = base(AckMode.BATCH);
            f.setBatchListener(true);
            f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, 2L)));
            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solRecordAckFactory() {
            return base(AckMode.RECORD);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solBatchAckFactory() {
            return base(AckMode.BATCH);
        }
    }

    @Component
    @Profile("step06sol")
    public static class Q1Listeners {

        private static final Logger log = LoggerFactory.getLogger(Q1Listeners.class);

        @KafkaListener(id = "q1-count", topics = TOPIC, groupId = "s06sol-q1-count",
                containerFactory = "solCountFactory", autoStartup = "false")
        public void count(OrderCreated e) {
            log.debug("COUNT {}", e.orderId());
        }

        @KafkaListener(id = "q1-time", topics = TOPIC, groupId = "s06sol-q1-time",
                containerFactory = "solTimeFactory", autoStartup = "false")
        public void time(OrderCreated e) {
            log.debug("TIME {}", e.orderId());
        }

        @KafkaListener(id = "q1-counttime", topics = TOPIC, groupId = "s06sol-q1-counttime",
                containerFactory = "solCountTimeFactory", autoStartup = "false")
        public void countTime(OrderCreated e) {
            log.debug("COUNT_TIME {}", e.orderId());
        }
    }

    // ========================================================================
    // 정답 2. MANUAL_IMMEDIATE 조건부 커밋
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - "건너뛴 메시지도 커밋한다"가 핵심입니다. 처리 대상이 아니라고 커밋을 생략하면
     *    그 오프셋에서 커밋 위치가 멈춥니다. 커밋은 "여기까지 읽었다"는 단일 커서이지
     *    "이 메시지들을 처리했다"는 집합이 아니기 때문입니다. 중간 하나를 커밋하지 않으면
     *    그 뒤를 아무리 커밋해도 재시작 시 그 지점부터 전부 다시 읽습니다.
     *    (엄밀히는 뒤쪽 커밋이 덮어써서 앞 메시지가 유실됩니다 — 더 나쁩니다.)
     *
     *  - 그래서 "처리 안 함"과 "커밋 안 함"은 완전히 다른 결정입니다.
     *    필터링은 커밋하고, 재시도가 필요한 실패만 커밋하지 않습니다.
     *
     *  - nack(Duration) 뒤에 즉시 return 해야 하는 이유: nack 은 컨테이너에게
     *    "이 레코드부터 다시 읽어라"라고 표시만 하고, 실제 seek 은 리스너가 리턴한 뒤
     *    poll 루프에서 일어납니다. nack 후에 ack.acknowledge() 를 부르거나 처리를 계속하면
     *    두 신호가 충돌해 예측 불가능한 커밋 위치가 됩니다.
     *
     *  - MANUAL_IMMEDIATE 를 쓴 이유: MANUAL 은 acknowledge() 를 큐에 넣고 poll 루프 끝에
     *    한꺼번에 커밋하므로, "이 건이 성공한 직후 확실히 커밋됐다"를 보장하지 못합니다.
     *    조건부 커밋의 목적이 정밀한 커밋 위치 통제라면 IMMEDIATE 가 맞습니다.
     */
    @Component
    @Profile("step06sol")
    public static class Q2ConditionalCommit {

        private static final Logger log = LoggerFactory.getLogger(Q2ConditionalCommit.class);

        private static final BigDecimal FAIL_AMOUNT = new BigDecimal(16_000);

        @KafkaListener(id = "q2", topics = TOPIC, groupId = "s06sol-q2",
                containerFactory = "solManualImmediateFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record, Acknowledgment ack) {
            OrderCreated event = record.value();

            if (event.quantity() < 3) {
                // 처리 대상이 아니지만 "읽었다"는 사실은 남겨야 합니다.
                log.debug("건너뜀(quantity={}) {} → 그래도 커밋", event.quantity(), event.orderId());
                ack.acknowledge();
                return;
            }

            if (FAIL_AMOUNT.compareTo(event.amount()) == 0) {
                log.warn("처리 실패 {} amount={} → nack(2s), 이 오프셋부터 재시도",
                        event.orderId(), event.amount());
                ack.nack(Duration.ofSeconds(2));
                return;                       // ⚠️ nack 뒤에는 아무것도 하지 않습니다
            }

            log.info("고액 주문 처리 {} quantity={} amount={} offset={}",
                    event.orderId(), event.quantity(), event.amount(), record.offset());
            ack.acknowledge();
        }
    }

    // ========================================================================
    // 정답 3. acknowledge() 누락 재현
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  (b) acknowledge() 없이 500건을 처리한 뒤 kcg --describe --group s06sol-q3 를 보면
     *      CURRENT-OFFSET 컬럼이 "-" 로 나옵니다. 0 이 아니라 "-" 입니다.
     *      커밋이 단 한 번도 없어 __consumer_offsets 에 이 그룹의 레코드 자체가 없기 때문입니다.
     *      LAG 도 "-" 입니다. 커밋 위치를 모르니 랙을 계산할 수 없습니다.
     *      한 번이라도 커밋했다면 CURRENT-OFFSET 은 "마지막 처리 오프셋 + 1" 이 됩니다.
     *
     *  (c) 앱을 재시작하면 auto.offset.reset=earliest 가 적용돼 500건 전부를 다시 읽습니다.
     *      운영에서 이 설정이 latest 라면 더 나쁩니다. 재시작 후 과거 메시지를 전부 건너뛰어
     *      "재처리"가 아니라 "유실"이 됩니다. 커밋을 안 하는 코드는 두 방향 모두로 터집니다.
     *
     *  (d) acknowledge() 를 추가하면 CURRENT-OFFSET 이 각 파티션의 LOG-END-OFFSET 과 같아지고
     *      LAG=0, 재시작해도 0건 재처리입니다.
     *
     *  이 문제의 진짜 교훈: acknowledge() 누락은 컴파일 에러도, 기동 실패도, 경고 로그도
     *  만들지 않습니다. "메시지는 처리되는데 커밋만 안 되는" 상태는 오직 kcg --describe 나
     *  컨슈머 랙 지표(Step 12)로만 발견됩니다. 그래서 랙 알람이 필수입니다.
     *
     *  반대 방향의 함정도 같이 기억하세요. AckMode 가 MANUAL/MANUAL_IMMEDIATE 가 아닌데
     *  Acknowledgment 파라미터를 선언하면 기동이 실패합니다.
     *    IllegalStateException: No Acknowledgment available as an argument,
     *    the listener container must have a MANUAL AckMode to populate the Acknowledgment.
     *  이건 조용하지 않으니 오히려 다행인 실수입니다.
     */
    @Component
    @Profile("step06sol")
    public static class Q3ForgottenAck {

        private static final Logger log = LoggerFactory.getLogger(Q3ForgottenAck.class);
        private final AtomicInteger count = new AtomicInteger();

        @KafkaListener(id = "q3", topics = TOPIC, groupId = "s06sol-q3",
                containerFactory = "solManualImmediateFactory", autoStartup = "false")
        public void onMessage(OrderCreated event, Acknowledgment ack) {
            int n = count.incrementAndGet();
            if (n % 100 == 0) {
                log.info("q3: {}건 처리", n);
            }
            ack.acknowledge();     // ← (d) 에서 추가한 한 줄. 이게 없으면 커밋이 0회입니다.
        }
    }

    // ========================================================================
    // 정답 4. BatchListenerFailedException 으로 부분 커밋
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - BatchListenerFailedException 의 두 번째 인자는 "배치 리스트 안에서의 인덱스"입니다.
     *    DefaultErrorHandler 는 이 인덱스를 보고 records.get(index) 의 오프셋을 계산해,
     *    그 앞(0 ~ index-1)의 오프셋을 커밋한 뒤 index 위치로 seek 합니다.
     *    즉 재시도 대상이 "배치 전체"에서 "실패한 한 건"으로 줄어듭니다.
     *
     *  - 일반 예외(IllegalStateException 등)를 던지면 어떻게 되는가:
     *    에러 핸들러는 배치 어디가 실패했는지 알 방법이 없습니다. 그래서 배치의 첫 오프셋으로
     *    seek 하고 전부를 다시 리스너에 넘깁니다. 앞의 47건은 이미 처리됐는데 또 처리됩니다.
     *    로그에는 예외 한 줄만 남으므로, 중복 처리는 "조용히" 일어납니다.
     *    이것이 이 스텝에서 가장 중요한 함정입니다.
     *
     *  - 실패 레코드를 정확히 지목하려면 ConsumerRecord 를 함께 넘기는 생성자
     *    new BatchListenerFailedException(msg, record) 도 있습니다. 인덱스 계산이 헷갈리면
     *    이쪽이 더 안전합니다. 단 그 record 는 반드시 "같은 배치 안의 인스턴스"여야 합니다.
     *
     *  - alreadyFailed 플래그로 한 번만 실패시킨 이유: 실패 조건이 계속 참이면 재시도 2회를
     *    소진한 뒤 기본 recoverer 가 그 레코드를 건너뛰고, 다음 레코드에서 또 같은 일이 반복돼
     *    로그가 500번 흐릅니다. 학습 목적에는 한 번이면 충분합니다.
     *
     *  - 재시도를 다 소진했을 때: DefaultErrorHandler 의 기본 recoverer 는
     *    "Backoff ... exhausted for orders-1@47" WARN 을 찍고 그 레코드를 건너뜁니다.
     *    실제 운영이라면 DeadLetterPublishingRecoverer 를 붙여야 합니다 (Step 07).
     */
    @Component
    @Profile("step06sol")
    public static class Q4BatchPartialFailure {

        private static final Logger log = LoggerFactory.getLogger(Q4BatchPartialFailure.class);

        private final AtomicInteger handled = new AtomicInteger();
        private boolean alreadyFailed = false;

        @KafkaListener(id = "q4", topics = TOPIC, groupId = "s06sol-q4",
                containerFactory = "solBatchListenerFactory", autoStartup = "false")
        public void onBatch(List<ConsumerRecord<String, OrderCreated>> records) {
            log.info("q4: 배치 {}건 수신 (offset {} ~ {})", records.size(),
                    records.get(0).offset(), records.get(records.size() - 1).offset());

            for (int i = 0; i < records.size(); i++) {
                ConsumerRecord<String, OrderCreated> record = records.get(i);
                OrderCreated event = record.value();

                if ("SKU-003".equals(event.sku()) && !alreadyFailed) {
                    alreadyFailed = true;
                    log.warn("q4: index={} ({}) 실패 → BatchListenerFailedException. "
                                    + "0~{} 는 커밋됩니다. 누적 처리 {}건",
                            i, event.orderId(), i - 1, handled.get());
                    throw new BatchListenerFailedException("재고 없음: " + event.orderId(), i);
                }

                handled.incrementAndGet();
            }
            log.info("q4: 배치 완료. 누적 처리 {}건", handled.get());
        }
    }

    // ========================================================================
    // 정답 5. 유실을 막으면서 파티션도 멈추지 않는 형태
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  (a) 재현: catch 로 로그만 찍으면 리스너는 정상 리턴합니다. AckMode.BATCH 는
     *      "리스너가 예외 없이 끝났다"는 것만 보고 배치 끝에서 커밋합니다.
     *      customerId=1013 은 seq % 30 == 13 인 주문이므로 500건 중 약 17건입니다.
     *      그 17건은 처리도 안 됐고 어디에도 안 남았는데 오프셋은 전진했습니다. LAG=0 입니다.
     *      "지표는 완벽한데 데이터가 없다" — 가장 찾기 어려운 종류의 버그입니다.
     *
     *  (b) 해결의 원칙: 실패를 "삼키지도, 무한 재시도하지도" 않는다.
     *      실패한 메시지를 DLT 로 옮기고 나서 정상 리턴하면
     *        - 메시지는 orders.DLT 에 남아 있으므로 유실이 아니고
     *        - 리스너는 예외를 던지지 않으므로 파티션이 멈추지 않습니다.
     *      이것이 "직접 만든 DLT 발행"입니다. Step 07 의 DeadLetterPublishingRecoverer 는
     *      이 패턴을 프레임워크가 대신해 주는 것이고, 재시도 정책까지 붙여 줍니다.
     *
     *  ⚠️ 단, DLT 발행 자체가 실패할 수 있습니다. 그래서 send 결과를 확인하고,
     *     그마저 실패하면 그때는 예외를 던져 커밋을 막는 것이 맞습니다.
     *     "어디에도 못 남겼으면 커밋하지 않는다" 가 유실 방지의 유일한 규칙입니다.
     *
     *  ⚠️ 그냥 예외를 던지는 답이 금지인 이유: DefaultErrorHandler 는 실패 지점으로 seek 하고
     *     기본 백오프로 계속 재시도합니다. customerId=1013 은 영구 실패이므로 재시도해도
     *     절대 성공하지 않고, 그동안 그 파티션의 뒤 메시지가 전부 대기합니다 (Step 07).
     */
    @Component
    @Profile("step06sol")
    public static class Q5NoLoss {

        private static final Logger log = LoggerFactory.getLogger(Q5NoLoss.class);

        private final KafkaTemplate<String, OrderCreated> template;
        private final AtomicInteger ok = new AtomicInteger();
        private final AtomicInteger sentToDlt = new AtomicInteger();

        public Q5NoLoss(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @KafkaListener(id = "q5", topics = TOPIC, groupId = "s06sol-q5",
                containerFactory = "solBatchAckFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            OrderCreated event = record.value();
            try {
                handle(event);
                ok.incrementAndGet();
            } catch (RuntimeException ex) {
                try {
                    // 동기로 기다립니다. DLT 에 확실히 들어간 뒤에 커밋되어야 하기 때문입니다.
                    template.send(DLT, event.orderId(), event).get();
                    log.warn("처리 실패 → DLT 이관 {} ({}건째): {}",
                            event.orderId(), sentToDlt.incrementAndGet(), ex.getMessage());
                } catch (Exception dltFailure) {
                    // DLT 조차 실패했다면 커밋되면 안 됩니다. 예외를 던져 재처리시킵니다.
                    log.error("DLT 이관 실패 {} → 커밋을 막기 위해 예외를 던집니다",
                            event.orderId(), dltFailure);
                    throw new IllegalStateException("DLT publish failed", dltFailure);
                }
            }
            if (record.offset() % 100 == 99) {
                log.info("진행: 성공 {} / DLT {}", ok.get(), sentToDlt.get());
            }
        }

        private void handle(OrderCreated event) {
            if (event.customerId() == 1013) {
                throw new IllegalStateException("고객 서비스 5xx: " + event.orderId());
            }
        }
    }

    // ========================================================================
    // 정답 6. RECORD vs BATCH 처리량 실측
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - RECORD 모드는 레코드 1건마다 commitSync 를 호출합니다. commitSync 는 브로커
     *    왕복(RTT)을 기다리는 동기 호출입니다. 로컬 브로커라도 건당 0.1~0.2ms 가 붙고,
     *    그 시간 동안 컨슈머 스레드는 아무 일도 못 합니다.
     *    500건이면 커밋 500회 = 왕복 500번입니다.
     *
     *  - BATCH 모드는 poll 로 가져온 배치를 다 처리하고 한 번만 커밋합니다.
     *    max-poll-records=500 이면 500건에 커밋 1회입니다.
     *
     *  - 측정 결과(로컬 단일 브로커, 리스너가 아무 일도 안 하는 상태):
     *      RECORD : 500건 / 119ms / 약 4,200 msg/s / 커밋 500회
     *      BATCH  : 500건 /  24ms / 약 21,000 msg/s / 커밋 1회
     *    약 5배입니다. 리스너가 DB 를 건드리는 등 실제 작업을 하면 처리 시간이 커밋 비용을
     *    가려서 격차가 줄어듭니다. 즉 RECORD 의 비용은 "리스너가 빠를수록 크게" 보입니다.
     *
     *  - 시작 시각을 첫 레코드 수신 시점으로 잡은 이유: 컨테이너 기동·그룹 조인·파티션 할당에
     *    수백 ms 가 걸리므로, 그것까지 포함하면 커밋 비용이 묻힙니다.
     *
     *  - 그럼 항상 BATCH 인가? 아닙니다. RECORD 는 실패 시 재처리 범위가 1건이고,
     *    BATCH 는 최대 max-poll-records 건입니다. 재처리 비용이 비싼 처리(외부 결제 호출 등)라면
     *    5배 느려도 RECORD 가 맞을 수 있습니다. 처리량과 재처리 범위의 교환입니다.
     */
    @Component
    @Profile("step06sol")
    public static class Q6Throughput {

        private static final Logger log = LoggerFactory.getLogger(Q6Throughput.class);

        private final AtomicInteger recordCount = new AtomicInteger();
        private final AtomicLong recordStart = new AtomicLong();
        private final AtomicInteger batchCount = new AtomicInteger();
        private final AtomicLong batchStart = new AtomicLong();

        @KafkaListener(id = "q6-record", topics = TOPIC, groupId = "s06sol-q6-record",
                containerFactory = "solRecordAckFactory", autoStartup = "false")
        public void onRecord(OrderCreated event) {
            recordStart.compareAndSet(0L, System.nanoTime());
            int n = recordCount.incrementAndGet();
            if (n == 500) {
                report("RECORD", n, recordStart.get());
            }
        }

        @KafkaListener(id = "q6-batch", topics = TOPIC, groupId = "s06sol-q6-batch",
                containerFactory = "solBatchAckFactory", autoStartup = "false")
        public void onBatch(OrderCreated event) {
            batchStart.compareAndSet(0L, System.nanoTime());
            int n = batchCount.incrementAndGet();
            if (n == 500) {
                report("BATCH", n, batchStart.get());
            }
        }

        private void report(String mode, int n, long startNanos) {
            long ms = Math.max((System.nanoTime() - startNanos) / 1_000_000, 1);
            log.info("[6-4] {} : {}건 / {} ms / {} msg/s", mode, n, ms, (n * 1000L) / ms);
        }
    }

    // ========================================================================
    // 실행 보조
    // ========================================================================
    @Component
    @Profile("step06sol")
    public static class Runner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Runner.class);

        private final KafkaListenerEndpointRegistry registry;

        public Runner(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void run(ApplicationArguments args) {
            if (!args.containsOption("app.step06sol.run")) {
                log.warn("켤 리스너를 고르세요: --app.step06sol.run=q1-count|q1-time|q1-counttime"
                        + "|q2|q3|q4|q5|q6-record|q6-batch");
                return;
            }
            String id = args.getOptionValues("app.step06sol.run").get(0);
            var container = registry.getListenerContainer(id);
            if (container == null) {
                log.error("그런 리스너가 없습니다: {}", id);
                return;
            }
            log.info("리스너 {} 기동", id);
            container.start();
        }
    }
}
