package com.example.order.step06;

/*
 * ============================================================================
 * Step 06 — 오프셋 커밋과 AckMode : Exercise (문제지)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step06/Exercise.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step06ex'
 *
 * 준비
 *   1) Practice 의 Seeder 로 orders 토픽에 500건이 들어 있어야 합니다.
 *        ./gradlew bootRun --args='--spring.profiles.active=step06'
 *   2) 커밋 로그를 보려면 application.yml 에 아래를 넣으세요.
 *        logging.level.org.apache.kafka.clients.consumer.internals.ConsumerCoordinator: DEBUG
 *   3) 각 문제를 풀 때마다 앱을 끄고 해당 그룹의 오프셋을 리셋하면 결과가 깨끗합니다.
 *        kcg --group s06ex-q1 --topic orders --reset-offsets --to-earliest --execute
 *
 * 모든 리스너는 autoStartup="false" 입니다. 문제를 푼 뒤
 * --app.step06ex.run=q3 처럼 옵션으로 하나만 골라 켜세요 (아래 Runner 참고).
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
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Exercise {

    private Exercise() {
    }

    static final String TOPIC = "orders";

    // ========================================================================
    // 문제 1. AckMode 6종 팩터리를 완성하고 커밋 횟수를 관찰하라
    // ========================================================================
    //
    // 요구사항
    //   - COUNT 모드: 100건마다 커밋되도록 ackCount 를 설정할 것
    //   - TIME  모드: 3초 경과 후 커밋되도록 ackTime 을 설정할 것
    //   - COUNT_TIME 모드: 100건 또는 3초 중 먼저 오는 쪽
    //   - 세 팩터리 모두 concurrency 는 1 로 둘 것 (커밋 횟수를 세야 하므로)
    //   - 완성 후 ConsumerCoordinator DEBUG 로그에서 "Committing offsets:" 가
    //     각각 몇 번 찍히는지 세어 볼 것
    //
    @Configuration
    @Profile("step06ex")
    public static class Q1Factories {

        private final ConsumerFactory<String, OrderCreated> consumerFactory;

        public Q1Factories(ConsumerFactory<String, OrderCreated> consumerFactory) {
            this.consumerFactory = consumerFactory;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q1CountFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q1TimeFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q1CountTimeFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }

        // --- 문제 2~6 에서 쓸 팩터리들. 이쪽도 여러분이 채웁니다 ---

        /** 문제 2·3 용: MANUAL_IMMEDIATE, concurrency=1 */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q23ManualImmediateFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }

        /** 문제 4 용: 배치 리스너 + BATCH ack + DefaultErrorHandler(FixedBackOff 1초 2회) */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q4BatchFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }

        /** 문제 5 용: 레코드 리스너 + BATCH ack (기본값 그대로) */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q5BatchAckFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            f.setConcurrency(1);
            f.getContainerProperties().setAckMode(AckMode.BATCH);
            return f;
        }

        /** 문제 6 용: RECORD 와 BATCH 두 팩터리 */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q6RecordFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q6BatchFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            // 여기에 작성:

            return f;
        }
    }

    @Component
    @Profile("step06ex")
    public static class Q1Listeners {

        private static final Logger log = LoggerFactory.getLogger(Q1Listeners.class);

        @KafkaListener(id = "q1-count", topics = TOPIC, groupId = "s06ex-q1-count",
                containerFactory = "q1CountFactory", autoStartup = "false")
        public void count(OrderCreated e) {
            log.debug("q1-count {}", e.orderId());
        }

        @KafkaListener(id = "q1-time", topics = TOPIC, groupId = "s06ex-q1-time",
                containerFactory = "q1TimeFactory", autoStartup = "false")
        public void time(OrderCreated e) {
            log.debug("q1-time {}", e.orderId());
        }

        @KafkaListener(id = "q1-counttime", topics = TOPIC, groupId = "s06ex-q1-counttime",
                containerFactory = "q1CountTimeFactory", autoStartup = "false")
        public void countTime(OrderCreated e) {
            log.debug("q1-counttime {}", e.orderId());
        }
    }

    // ========================================================================
    // 문제 2. MANUAL_IMMEDIATE 로 "조건부 커밋"을 구현하라
    // ========================================================================
    //
    // 요구사항
    //   - quantity 가 3 이상인 주문만 "고액 주문"으로 간주해 처리하고 커밋한다
    //   - quantity 가 3 미만이면 처리는 건너뛰되, 오프셋은 커밋해야 한다
    //     (건너뛴 메시지를 재처리할 이유가 없으므로)
    //   - 처리 도중 amount 가 16000 인 주문에서는 실패로 간주해
    //     nack(Duration.ofSeconds(2)) 를 호출하고 즉시 리턴한다
    //   - 힌트: nack 뒤에 코드를 더 실행하면 안 됩니다. 왜인지 생각해 보세요.
    //
    @Component
    @Profile("step06ex")
    public static class Q2ConditionalCommit {

        private static final Logger log = LoggerFactory.getLogger(Q2ConditionalCommit.class);

        @KafkaListener(id = "q2", topics = TOPIC, groupId = "s06ex-q2",
                containerFactory = "q23ManualImmediateFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record, Acknowledgment ack) {
            OrderCreated event = record.value();
            // 여기에 작성:

        }
    }

    // ========================================================================
    // 문제 3. acknowledge() 누락을 재현하고, 재시작으로 재처리를 확인하라
    // ========================================================================
    //
    // 요구사항
    //   (a) 아래 리스너에서 acknowledge() 를 "일부러" 부르지 않은 채 실행한다.
    //   (b) 500건이 다 처리된 뒤 kcg --describe --group s06ex-q3 를 실행해
    //       CURRENT-OFFSET 과 LAG 이 어떻게 나오는지 기록한다.
    //   (c) 앱을 껐다 켜서 몇 건이 다시 처리되는지 확인한다.
    //   (d) 그 다음 acknowledge() 를 추가해 (b)(c)를 다시 하고 차이를 비교한다.
    //
    // 답안에는 "(b)에서 CURRENT-OFFSET 이 왜 그 값인지"를 주석으로 적으세요.
    //
    @Component
    @Profile("step06ex")
    public static class Q3ForgottenAck {

        private static final Logger log = LoggerFactory.getLogger(Q3ForgottenAck.class);
        private final AtomicInteger count = new AtomicInteger();

        @KafkaListener(id = "q3", topics = TOPIC, groupId = "s06ex-q3",
                containerFactory = "q23ManualImmediateFactory", autoStartup = "false")
        public void onMessage(OrderCreated event, Acknowledgment ack) {
            int n = count.incrementAndGet();
            if (n % 100 == 0) {
                log.info("q3: {}건 처리", n);
            }
            // 여기에 작성:  (먼저 비워 둔 채 실행 → 그다음 acknowledge() 추가)

        }
    }

    // ========================================================================
    // 문제 4. 배치 부분 실패를 BatchListenerFailedException 으로 처리하라
    // ========================================================================
    //
    // 요구사항
    //   - 배치에서 sku 가 "SKU-003" 인 첫 레코드를 실패로 간주한다
    //   - 실패 시 그 앞까지는 커밋되고, 실패 레코드부터만 재시도되도록 만든다
    //   - 재시도 2회를 소진하면 그 레코드는 건너뛰고(기본 recoverer 는 로그만 찍음)
    //     다음 레코드부터 계속 진행되어야 한다
    //   - 처리 누적 건수를 로그로 남겨, 재시도로 인한 중복 처리가 몇 건인지 셀 것
    //
    @Component
    @Profile("step06ex")
    public static class Q4BatchPartialFailure {

        private static final Logger log = LoggerFactory.getLogger(Q4BatchPartialFailure.class);
        private final AtomicInteger handled = new AtomicInteger();

        @KafkaListener(id = "q4", topics = TOPIC, groupId = "s06ex-q4",
                containerFactory = "q4BatchFactory", autoStartup = "false")
        public void onBatch(List<ConsumerRecord<String, OrderCreated>> records) {
            log.info("q4: 배치 {}건 수신 (offset {} ~ {})", records.size(),
                    records.get(0).offset(), records.get(records.size() - 1).offset());
            // 여기에 작성:

        }
    }

    // ========================================================================
    // 문제 5. try-catch 로 예외를 삼켜 유실을 재현하고, 고쳐라
    // ========================================================================
    //
    // 요구사항
    //   (a) 아래 리스너는 customerId 가 1013 인 주문에서 예외가 납니다.
    //       먼저 try-catch 로 감싸 로그만 찍도록 만들고 실행합니다.
    //       500건 처리 후 LAG 이 0 인데도 몇 건이 처리되지 않았는지 세어 보세요.
    //   (b) 그다음, 유실이 나지 않도록 고칩니다.
    //       단 "예외를 그냥 던진다"는 답은 금지입니다.
    //       (파티션이 멈추고 앞부분이 무한 재처리되기 때문입니다)
    //       실패한 메시지를 어디론가 보관하고 정상 리턴하는 형태로 만드세요.
    //       힌트: orders.DLT 토픽이 이미 만들어져 있습니다.
    //
    @Component
    @Profile("step06ex")
    public static class Q5SwallowedLoss {

        private static final Logger log = LoggerFactory.getLogger(Q5SwallowedLoss.class);
        private final AtomicInteger ok = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        @KafkaListener(id = "q5", topics = TOPIC, groupId = "s06ex-q5",
                containerFactory = "q5BatchAckFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            // 여기에 작성:

        }

        /** 처리 로직. 손대지 마세요. */
        private void handle(OrderCreated event) {
            if (event.customerId() == 1013) {
                throw new IllegalStateException("고객 서비스 5xx: " + event.orderId());
            }
        }
    }

    // ========================================================================
    // 문제 6. RECORD 와 BATCH 의 처리량 차이를 실측하라
    // ========================================================================
    //
    // 요구사항
    //   - 두 리스너 모두 500건을 받으면 경과 시간(ms)과 msg/s 를 로그로 남긴다
    //   - 첫 레코드 수신 시각을 시작점으로, 500번째 수신 시각을 끝점으로 잰다
    //   - 같은 시드 데이터로 두 프로필을 각각 한 번씩 실행해 수치를 비교한다
    //   - 커밋 로그("Committing offsets:") 횟수도 함께 세어 표를 만든다
    //
    @Component
    @Profile("step06ex")
    public static class Q6Throughput {

        private static final Logger log = LoggerFactory.getLogger(Q6Throughput.class);

        private final AtomicInteger recordCount = new AtomicInteger();
        private final AtomicLong recordStart = new AtomicLong();
        private final AtomicInteger batchCount = new AtomicInteger();
        private final AtomicLong batchStart = new AtomicLong();

        @KafkaListener(id = "q6-record", topics = TOPIC, groupId = "s06ex-q6-record",
                containerFactory = "q6RecordFactory", autoStartup = "false")
        public void onRecord(OrderCreated event) {
            // 여기에 작성:

        }

        @KafkaListener(id = "q6-batch", topics = TOPIC, groupId = "s06ex-q6-batch",
                containerFactory = "q6BatchFactory", autoStartup = "false")
        public void onBatch(OrderCreated event) {
            // 여기에 작성:

        }
    }

    // ========================================================================
    // 실행 보조 — --app.step06ex.run=q3 으로 리스너 하나만 켭니다
    // ========================================================================
    @Component
    @Profile("step06ex")
    public static class Runner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Runner.class);

        private final KafkaListenerEndpointRegistry registry;

        public Runner(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void run(ApplicationArguments args) {
            if (!args.containsOption("app.step06ex.run")) {
                log.warn("켤 리스너를 고르세요: --app.step06ex.run=q1-count|q1-time|q1-counttime"
                        + "|q2|q3|q4|q5|q6-record|q6-batch");
                return;
            }
            String id = args.getOptionValues("app.step06ex.run").get(0);
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
