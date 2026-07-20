package com.example.order.step08;

/*
 * ============================================================================
 * Step 08 — @RetryableTopic 논블로킹 재시도 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step08/Practice.java
 *
 * ★ 사전 준비 — application.yml 에 두 줄을 추가하세요.
 *
 *   spring.kafka.consumer.properties.spring.json.trusted.packages:
 *       "com.example.order.domain,com.example.order.step08"
 *       → [8-6] 에서 쓰는 OrderEvent 가 step08 패키지에 있기 때문입니다.
 *         빼먹으면 "The class is not in the trusted packages" 로 전부 실패합니다(Step 04).
 *
 *   logging.level.org.springframework.kafka.listener.KafkaConsumerBackoffManager: DEBUG
 *       → [8-8] 의 "Backing off partition ..." 로그를 보려면 필요합니다.
 *
 * 실행 (보조 프로필을 하나만 함께 켭니다. 예제끼리 서로 간섭합니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-default'
 *       → [8-2] 기본 접미사. orders-retry-1000 / -2000 / -4000 / orders-dlt 가 생깁니다.
 *         ★ 확인 후 반드시 kt --delete 로 지우세요. 안 지우면 이후 kt --list 가 교재와 다릅니다.
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-journey'
 *       → [8-3][8-5][8-7] 실패 한 건의 여정 추적 + @DltHandler + 컨테이너 5개 확인
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-order'
 *       → [8-6] ★★핵심★★ 같은 키 3건으로 순서가 깨지는 것을 재현
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-longbackoff'
 *       → [8-8] 긴 백오프가 retry 토픽 파티션을 pause 로 막는 것
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-hybrid'
 *       → [8-10] 짧은 블로킹 재시도 뒤에 논블로킹으로 넘기기
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-builder'
 *       → [8-10] 애노테이션 대신 RetryTopicConfiguration 빈으로 전역 설정
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,step08-noautocreate'
 *       → [8-11] autoCreateTopics=false 인데 토픽이 없을 때. 60초 타임아웃을 겪습니다.
 *
 * ★ 매 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s08-inventory   --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s08-order-state --all-topics   --reset-offsets --to-earliest --execute
 *
 * ★ 프로필을 바꿀 때는 retry 토픽을 지우세요. 접미사 전략이 다르면 이름이 섞입니다.
 *   kt --list | grep -E 'orders-retry|orders-dlt' | xargs -I{} \
 *     docker exec -i learn-kafka /opt/kafka/bin/kafka-topics.sh \
 *       --bootstrap-server localhost:9092 --delete --topic {}
 *
 * 실행 중에 확인할 CLI
 *   kt  --list                                   ← ★ 8-2 의 핵심 확인
 *   kt  --describe --topic orders-retry-0        ← 파티션 수가 원본과 같은지
 *   kcg --list                                   ← ★ 그룹이 하나뿐인 것 (8-7)
 *   kcg --describe --group s08-inventory         ← 5개 토픽이 한 그룹에
 *   kcc --topic orders-retry-1 --from-beginning --property print.headers=true
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
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Step 08 의 모든 예제를 담은 단일 파일입니다.
 * 각 nested static class 는 본문 절 번호와 1:1 로 대응합니다.
 */
public final class Practice {

    private Practice() {
        // 유틸리티 홀더. 인스턴스화하지 않습니다.
    }

    // ========================================================================
    // [공통] 실패 조건과 시도 시각 기록
    // ========================================================================
    //
    // Step 07 과 같은 orders-1@42 를 실패 지점으로 씁니다.
    // ⚠️ 단, 논블로킹에서는 재시도가 "다른 토픽" 에서 일어나므로 offset 이 달라집니다.
    //    그래서 카운터의 키를 offset 이 아니라 orderId 로 잡아야 attempt 가 이어집니다.
    static final class FailurePolicy {

        static final int  FAIL_PARTITION = 1;
        static final long FAIL_OFFSET    = 42L;

        private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
        private static final Map<String, Long>          FIRST_AT = new ConcurrentHashMap<>();

        /** 본선(orders) 에서만 판정합니다. retry 토픽에서는 무조건 실패시켜야 여정을 끝까지 봅니다. */
        static boolean shouldFail(ConsumerRecord<?, ?> record) {
            if (!"orders".equals(record.topic())) {
                return true;                    // retry 토픽으로 넘어온 것은 계속 실패
            }
            return record.partition() == FAIL_PARTITION && record.offset() == FAIL_OFFSET;
        }

        /** "attempt=2 t=1006ms topic=orders-retry-0 ORD-0043" 형태의 도장. */
        static String stamp(ConsumerRecord<?, ?> record, String orderId) {
            int attempt = ATTEMPTS.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
            long first  = FIRST_AT.computeIfAbsent(orderId, k -> System.currentTimeMillis());
            long elapsed = System.currentTimeMillis() - first;
            return "attempt=%d t=%dms topic=%s %s".formatted(attempt, elapsed, record.topic(), orderId);
        }
    }

    // ========================================================================
    // [8-2] 기본 접미사 — 아무것도 지정하지 않으면 어떤 토픽이 생기는가
    // ========================================================================
    //
    // topicSuffixingStrategy 의 기본값은 SUFFIX_WITH_DELAY_VALUE 입니다.
    // 인덱스가 아니라 "백오프 지연값(ms)" 이 접미사로 붙습니다.
    //
    // 기대 결과 (kt --list)
    //   orders
    //   orders-retry-1000
    //   orders-retry-2000
    //   orders-retry-4000
    //   orders-dlt              ← ★ Step 07 의 orders.DLT 와 다른 토픽입니다
    //
    // ★ 확인 후 반드시 이 네 개를 kt --delete 로 지우세요.
    @Component
    @Profile("step08-default")
    public static class DefaultSuffixDemo {

        private static final Logger log = LoggerFactory.getLogger(DefaultSuffixDemo.class);

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                throw new RemoteApiException("재고 API 타임아웃: " + record.value().orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event) {
            log.error("DLT 수신 {}", event.orderId());
        }
    }

    // ========================================================================
    // [8-3][8-5][8-7] 실패 한 건의 여정 추적
    // ========================================================================
    //
    // 코스 규약(orders-retry-0/-1/-2, orders.DLT)에 맞춘 설정입니다.
    //
    // ★ 로그에서 확인할 것
    //   ① 스레드 이름이 [ntainer#0-1-C-1] → #1 → #2 → #3 → #4 로 바뀐다 (컨테이너가 다르다)
    //   ② "처리 완료 orders-1@43" 이 실패 직후 곧바로 찍힌다 (본선이 안 막혔다)
    //   ③ t=1006ms → 3012ms → 7021ms (1초 → 2초 → 4초)
    //   ④ 마지막이 orders.DLT-1 (원본 파티션 번호 1 유지)
    //
    // ★ CLI 로 확인할 것
    //   kcg --list                            → s08-inventory 하나뿐
    //   kcg --describe --group s08-inventory  → 5개 토픽이 한 그룹에
    @Component
    @Profile("step08-journey")
    public static class JourneyDemo {

        private static final Logger log = LoggerFactory.getLogger(JourneyDemo.class);

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",              // 원본과 같게. 기본값 -1 은 브로커 기본을 따릅니다
                replicationFactor = "1",
                concurrency = "1",                // [8-7] retry 컨테이너만 1 스레드로
                dltStrategy = DltStrategy.FAIL_ON_ERROR,
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            OrderCreated event = record.value();
            if (FailurePolicy.shouldFail(record)) {
                log.warn("★ 실패 {}", FailurePolicy.stamp(record, event.orderId()));
                throw new RemoteApiException("재고 API 타임아웃: " + event.orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        /**
         * [8-5] 같은 클래스 안에 @DltHandler 하나면 DLT 리스너가 됩니다.
         *
         * ⚠️ 헤더 상수가 Step 07 과 다릅니다.
         *    Step 07 : KafkaHeaders.DLT_ORIGINAL_TOPIC  → "kafka_dlt-original-topic"
         *    Step 08 : KafkaHeaders.ORIGINAL_TOPIC      → "kafka_original-topic"
         *
         * ★ topic 이 "orders-retry-2" 가 아니라 "orders" 로 찍힙니다.
         *   헤더는 최초 발행 시 한 번만 붙고 이후 단계에서 덮어쓰이지 않기 때문입니다.
         */
        @DltHandler
        public void onDlt(OrderCreated event,
                          @Header(KafkaHeaders.ORIGINAL_TOPIC)     String topic,
                          @Header(KafkaHeaders.ORIGINAL_PARTITION) int    partition,
                          @Header(KafkaHeaders.ORIGINAL_OFFSET)    long   offset,
                          @Header(KafkaHeaders.EXCEPTION_FQCN)     String exFqcn,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE)  String exMessage) {

            log.error("DLT 수신 {} origin={}-{}@{} ex={} msg={}",
                    event.orderId(), topic, partition, offset,
                    exFqcn.substring(exFqcn.lastIndexOf('.') + 1), exMessage);
        }
    }

    // ========================================================================
    // [8-6] ★★★ 핵심 함정 ★★★ 논블로킹 재시도가 메시지 순서를 깨뜨린다
    // ========================================================================
    //
    // 같은 키 ORD-0001 로 3건을 순서대로 발행합니다.
    //   CREATED(offset 100) → UPDATED(101) → CANCELLED(102)
    // 키가 같으니 셋 다 같은 파티션에 들어가고, Kafka 는 파티션 안의 순서를 보장합니다.
    //
    // 그런데 첫 번째(CREATED)만 1회 실패하게 두면:
    //   t=8ms    CREATED 실패    → orders-retry-0 으로 발행, 오프셋 101 커밋
    //   t=12ms   UPDATED 성공    → STATE = UPDATED
    //   t=15ms   CANCELLED 성공  → STATE = CANCELLED
    //   t=1012ms CREATED 재처리 성공 → STATE = CREATED     ← ★★ 취소된 주문이 부활
    //
    // 예외 없음. LAG 0. ERROR 로그 없음. @DltHandler 도 호출 안 됨.
    // "재시도가 성공했기 때문에" 벌어진 오염입니다.
    //
    // ★ ApplicationRunner 가 3초 뒤 최종 상태를 찍습니다.
    //   마지막 줄이 "최종 상태 ORD-0001 = CREATED" 인지 반드시 확인하세요.
    @Component
    @Profile("step08-order")
    public static class OrderStateDemo {

        private static final Logger log = LoggerFactory.getLogger(OrderStateDemo.class);

        /** 주문 상태 저장소. 실무의 DB 라고 생각하세요. */
        static final Map<String, OrderState> STATE = new ConcurrentHashMap<>();

        /** 최초 CREATED 한 번만 실패시킵니다. 일시적 장애를 흉내 냅니다. */
        static final AtomicBoolean FIRST_TRY = new AtomicBoolean(true);

        static final long T0 = System.currentTimeMillis();

        static long elapsed() {
            return System.currentTimeMillis() - T0;
        }

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-order-state")
        public void onMessage(ConsumerRecord<String, OrderEvent> record) {

            OrderEvent event = record.value();

            // 최초 CREATED 만 1회 실패. 두 번째 시도(retry-0)에서는 성공합니다.
            if (event.status() == OrderState.CREATED && FIRST_TRY.getAndSet(false)) {
                log.warn("★ 실패 {} {} (topic={} t={}ms)",
                        event.orderId(), event.status(), record.topic(), elapsed());
                throw new RemoteApiException("재고 API 타임아웃");
            }

            STATE.put(event.orderId(), event.status());
            log.info("상태 반영 {} → {}  (topic={} t={}ms)",
                    event.orderId(), event.status(), record.topic(), elapsed());
        }

        @DltHandler
        public void onDlt(OrderEvent event) {
            log.error("DLT 수신 {} {}", event.orderId(), event.status());
        }
    }

    /** ORD-0001 의 세 이벤트를 순서대로 발행하고, 3초 뒤 최종 상태를 찍습니다. */
    @Component
    @Profile("step08-order")
    public static class OrderStatePublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(OrderStatePublisher.class);

        private final KafkaTemplate<String, Object> template;

        public OrderStatePublisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                sleep(2_000L);   // 리스너 5개가 전부 partitions assigned 될 때까지 기다립니다

                for (OrderState status : new OrderState[]{
                        OrderState.CREATED, OrderState.UPDATED, OrderState.CANCELLED}) {

                    OrderEvent event = OrderEvent.of("ORD-0001", status);
                    template.send("orders", event.orderId(), event);
                    log.info("발행 {} {}  (t={}ms)",
                            event.orderId(), status, OrderStateDemo.elapsed());
                }

                sleep(3_000L);   // 백오프 1초 + 여유
                log.info("최종 상태 {} = {}   ← ★ CANCELLED 가 아니면 순서가 깨진 것입니다",
                        "ORD-0001", OrderStateDemo.STATE.get("ORD-0001"));
            }, "order-state-publisher");
            t.setDaemon(true);
            t.start();
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========================================================================
    // [8-8] ⚠️ 함정 — 긴 백오프는 retry 토픽 안에서 다시 블로킹이다
    // ========================================================================
    //
    // 백오프는 스케줄러가 아닙니다. retry 토픽 컨슈머는 메시지를 즉시 poll 해 오고,
    // KafkaBackoffAwareMessageListenerAdapter 가 retry_topic-backoff-timestamp 헤더를
    // 보고 "아직 시간이 안 됐다" 면 KafkaBackoffException 을 던집니다.
    // 그러면 KafkaConsumerBackoffManager 가 그 파티션을 pause + seek 합니다.
    //
    // ★ pause 이므로 poll() 자체는 계속 돕니다 → max.poll.interval.ms 초과는 안 납니다.
    //   하지만 그 파티션의 뒤 레코드는 그대로 기다립니다. Step 07 과 같은 병목입니다.
    //
    // ★ 로그를 보려면 application.yml 에 이 줄이 필요합니다.
    //   logging.level.org.springframework.kafka.listener.KafkaConsumerBackoffManager: DEBUG
    //
    // 기대 로그
    //   DEBUG ... KafkaConsumerBackoffManager : Backing off partition orders-retry-0-1 until ...
    //   INFO  ... KafkaMessageListenerContainer : Paused consumption from: [orders-retry-0-1]
    @Component
    @Profile("step08-longbackoff")
    public static class LongBackoffDemo {

        private static final Logger log = LoggerFactory.getLogger(LongBackoffDemo.class);

        private static final Map<String, Long> SENT_AT = new ConcurrentHashMap<>();

        @RetryableTopic(
                attempts = "3",
                // 30초. random=true 로 두면 due time 이 흩어져 "역전" 이 생깁니다(본문 8-8 표).
                backoff = @Backoff(delay = 30_000, multiplier = 1.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                // multiplier=1.0 이라 모든 단계의 대기가 같습니다.
                // SINGLE_TOPIC 으로 두면 orders-retry 하나로 합쳐집니다.
                sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {

            String orderId = record.value().orderId();

            if ("orders".equals(record.topic())) {
                // 본선에서 연속 3건(ORD-0043, 0044, 0045)을 실패시켜 retry 토픽에 몰아넣습니다.
                if (record.partition() == 1 && record.offset() >= 42 && record.offset() <= 44) {
                    SENT_AT.put(orderId, System.currentTimeMillis());
                    log.warn("★ 실패 {} → retry 토픽으로", orderId);
                    throw new RemoteApiException("재고 API 타임아웃");
                }
                log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
                return;
            }

            // retry 토픽에서는 성공시키고, 실제로 몇 ms 기다렸는지 찍습니다.
            long waited = System.currentTimeMillis() - SENT_AT.getOrDefault(orderId, System.currentTimeMillis());
            log.info("재처리 {} (topic={} 대기 {}ms)   ← 설정값 30,000ms 와 비교하세요",
                    orderId, record.topic(), waited);
        }

        @DltHandler
        public void onDlt(OrderCreated event) {
            log.error("DLT 수신 {}", event.orderId());
        }
    }

    // ========================================================================
    // [8-10] 블로킹 + 논블로킹 조합
    // ========================================================================
    //
    // 대부분의 순간 장애는 500ms 뒤 재시도로 끝납니다.
    // 그런 실패까지 retry 토픽을 거치면 토픽만 지저분해지고 순서도 깨집니다.
    //
    // configureBlockingRetries 에 등록한 예외는 "먼저 컨테이너에서 블로킹으로" 재시도합니다.
    // 여기서 성공하면 retry 토픽에 아무것도 안 남고 순서도 유지됩니다.
    //
    // ⚠️ 총 시도는 합이지만 "대기 시간" 은 곱해집니다.
    //    retry-0 컨슈머에서도 블로킹 2회를 다시 돌고 나서 retry-1 로 갑니다.
    //    그래서 블로킹 백오프는 총합 1초 이내로 아주 짧게 두어야 합니다.
    @Configuration
    @Profile("step08-hybrid")
    public static class HybridRetryConfig extends RetryTopicConfigurationSupport {

        /** ★ 논블로킹으로 넘기기 전에 컨테이너에서 0.5초 × 2회만 블로킹 재시도. */
        @Override
        protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
            blockingRetries
                    .retryOn(RemoteApiException.class)
                    .backOff(new FixedBackOff(500L, 2L));
        }

        /**
         * DLT/retry 발행기를 커스터마이징하는 훅입니다.
         * 반환 타입이 Consumer&lt;DeadLetterPublishingRecovererFactory&gt; 인 것에 주의하세요.
         * 실제 recoverer 를 손대려면 팩토리의 customizer 를 한 겹 더 등록해야 합니다.
         */
        @Override
        protected Consumer<DeadLetterPublishingRecovererFactory> configureDeadLetterPublishingContainerFactory() {
            return factory -> factory.setDeadLetterPublishingRecovererCustomizer(
                    recoverer -> recoverer.setStripPreviousExceptionHeaders(true));
        }
    }

    @Component
    @Profile("step08-hybrid")
    public static class HybridDemo {

        private static final Logger log = LoggerFactory.getLogger(HybridDemo.class);

        /** 5번째 시도에서 성공하도록 만듭니다. 블로킹 3회 → 논블로킹 2회에 걸칩니다. */
        private static final AtomicInteger ATTEMPT = new AtomicInteger();

        @RetryableTopic(
                attempts = "3",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {

            if (!(record.partition() == 1 && record.offset() == 42) && "orders".equals(record.topic())) {
                log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
                return;
            }

            String orderId = record.value().orderId();
            int attempt = ATTEMPT.incrementAndGet();

            if (attempt < 5) {
                log.warn("실패 attempt={} topic={} {}", attempt, record.topic(), orderId);
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("성공 attempt={} topic={} {}", attempt, record.topic(), orderId);
        }

        @DltHandler
        public void onDlt(OrderCreated event) {
            log.error("DLT 수신 {}", event.orderId());
        }
    }

    // ========================================================================
    // [8-10] 애노테이션 대신 RetryTopicConfiguration 빈으로 전역 설정
    // ========================================================================
    //
    // includeTopic("orders") 하나로, orders 를 구독하는 "모든" @KafkaListener 에
    // 같은 재시도 정책이 적용됩니다. 애노테이션을 하나도 안 붙여도 됩니다.
    //
    // ⚠️ 애노테이션과 빈이 둘 다 있으면 애노테이션이 이깁니다.
    @Configuration
    @Profile("step08-builder")
    public static class BuilderConfig {

        @Bean
        public RetryTopicConfiguration ordersRetryTopicConfig(KafkaTemplate<String, Object> template) {
            return RetryTopicConfigurationBuilder
                    .newInstance()
                    .includeTopic("orders")
                    .maxAttempts(4)                          // = attempts = "4"
                    .exponentialBackoff(1000, 2.0, 10_000)   // = @Backoff(1000, 2.0, maxDelay=10000)
                    .suffixTopicsWithIndexValues()           // = SUFFIX_WITH_INDEX_VALUE
                    .retryTopicSuffix("-retry")
                    .dltSuffix(".DLT")
                    .concurrency(1)
                    .autoCreateTopics(true, 3, (short) 1)    // = numPartitions/replicationFactor
                    .notRetryOn(InvalidOrderException.class) // = exclude
                    .doNotRetryOnDltFailure()                // = DltStrategy.FAIL_ON_ERROR
                    .create(template);
        }
    }

    /** 애노테이션이 하나도 없습니다. 위 빈이 대신 적용됩니다. */
    @Component
    @Profile("step08-builder")
    public static class BuilderInventoryListener {

        private static final Logger log = LoggerFactory.getLogger(BuilderInventoryListener.class);

        @KafkaListener(topics = "orders", groupId = "s08-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("★ 실패 {}", FailurePolicy.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("재고 처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    /** 같은 토픽을 구독하는 두 번째 리스너. 여기에도 같은 정책이 적용됩니다. */
    @Component
    @Profile("step08-builder")
    public static class BuilderNotificationListener {

        private static final Logger log = LoggerFactory.getLogger(BuilderNotificationListener.class);

        @KafkaListener(topics = "orders", groupId = "s08-notification")
        public void notifyCustomer(ConsumerRecord<String, OrderCreated> record) {
            log.info("알림 발송 완료 {}", record.key());
        }
    }

    // ========================================================================
    // [8-11] ⚠️ 함정 — 토픽 자동 생성이 꺼진 운영 환경
    // ========================================================================
    //
    // autoCreateTopics = "false" 는 "토픽을 만들지 않는다" 일 뿐,
    // "토픽이 있는지 검증한다" 가 아닙니다. 없어도 기동은 성공합니다.
    //
    // ★ 재현 순서
    //   1) retry/DLT 토픽을 전부 지웁니다.
    //   2) 이 프로필로 기동합니다 → 정상 기동. partitions assigned 도 찍힙니다.
    //   3) 첫 실패가 나면 60초 침묵 뒤:
    //      ERROR ... DeadLetterPublishingRecoverer : Dead-letter publication to
    //              orders-retry-0 failed for: orders-1@42
    //      org.apache.kafka.common.errors.TimeoutException:
    //              Topic orders-retry-0 not present in metadata after 60000 ms.
    //   4) 그 60초 동안 orders-1 파티션은 완전히 멈춥니다. 논블로킹의 의미가 사라집니다.
    //   5) 아래 스크립트로 토픽을 만들고 다시 실행해 정상 동작과 대조하세요.
    //
    //      for i in 0 1 2; do
    //        kt --create --topic orders-retry-$i --partitions 3 --replication-factor 1
    //      done
    //      kt --create --topic orders.DLT --partitions 3 --replication-factor 1
    @Component
    @Profile("step08-noautocreate")
    public static class NoAutoCreateDemo {

        private static final Logger log = LoggerFactory.getLogger(NoAutoCreateDemo.class);

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                autoCreateTopics = "false",        // ★ 운영 필수. 대신 사전 생성이 전제입니다
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("★ 실패 {} — 여기서 60초를 기다리게 됩니다",
                        FailurePolicy.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event) {
            log.error("DLT 수신 {}", event.orderId());
        }
    }
}

// ============================================================================
// 이 스텝에서 쓰는 보조 타입들
// ============================================================================

/** 외부 API 일시 장애. "5초 뒤에 하면 될 수도 있다" → 재시도 대상. */
class RemoteApiException extends RuntimeException {
    RemoteApiException(String message) {
        super(message);
    }
}

/**
 * 비즈니스 검증 실패. 100번 해도 100번 실패합니다.
 * @RetryableTopic(exclude = InvalidOrderException.class) 로 즉시 DLT 로 보냅니다.
 */
class InvalidOrderException extends RuntimeException {
    InvalidOrderException(String message) {
        super(message);
    }
}

/** 주문 상태. [8-6] 의 순서 깨짐을 보여 주기 위한 최소 집합입니다. */
enum OrderState {
    CREATED, UPDATED, CANCELLED
}

/**
 * [8-6] 전용 이벤트. OrderCreated 에는 상태 필드가 없어서 따로 둡니다.
 *
 * ⚠️ 이 타입은 com.example.order.step08 패키지에 있으므로 application.yml 의
 *    spring.json.trusted.packages 에 "com.example.order.step08" 을 추가해야 합니다.
 *    빼먹으면 "The class ... is not in the trusted packages" 로 전부 실패합니다(Step 04).
 */
record OrderEvent(String orderId, OrderState status, BigDecimal amount, Instant at) {

    static OrderEvent of(String orderId, OrderState status) {
        return new OrderEvent(orderId, status, new BigDecimal("11000"), Instant.now());
    }
}
