package com.example.order.step07;

/*
 * ============================================================================
 * Step 07 — 에러 처리와 재시도 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step07/Practice.java
 *
 * 실행 (보조 프로필을 하나만 함께 켭니다. 예제끼리 서로 간섭합니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,step07-default'
 *       → [7-2] 아무 설정 없을 때. FixedBackOff(0,9) 로 10번 시도 후 조용히 버림
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,step07-backoff'
 *       → [7-3] FixedBackOff / ExponentialBackOffWithMaxRetries 시도 시각 실측
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,step07-blocking'
 *       → [7-4] ★핵심★ 블로킹 재시도로 orders-1 만 50초 정지. LAG 을 측정할 것
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,step07-polltimeout'
 *       → [7-4] max.poll.interval.ms 초과로 LeaveGroup + 리밸런스 유발 (확인 후 즉시 종료)
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,step07-dlt'
 *       → [7-5][7-6][7-7][7-8] 예외 분류 + DLT 발행 + DLT 헤더 덤프 + DLT 리스너
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,step07-swallow'
 *       → [7-10] KafkaListenerErrorHandler 가 예외를 삼켜 재시도·DLT 가 전부 무효가 되는 것
 *
 * ★ 매 실행 전에 반드시 오프셋을 리셋하세요 (앱을 먼저 종료할 것).
 *   실패 조건이 orders-1@42 로 고정이라, 42 를 이미 지나갔으면 아무 일도 안 일어납니다.
 *
 *   kcg --group s07-inventory   --topic orders     --reset-offsets --to-earliest --execute
 *   kcg --group s07-dlt-monitor --topic orders.DLT --reset-offsets --to-earliest --execute
 *
 * 실행 중에 확인할 CLI
 *   kt  --describe --topic orders.DLT                      ← 파티션 3 인지 (7-6 함정)
 *   kcg --describe --group s07-inventory                   ← ★ 30초 간격으로 두 번 찍을 것
 *   kcc --topic orders.DLT --from-beginning \
 *       --property print.key=true --property print.partition=true --property print.headers=true
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaListenerErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 07 의 모든 예제를 담은 단일 파일입니다.
 * 각 nested static class 는 본문 절 번호와 1:1 로 대응합니다.
 */
public final class Practice {

    private Practice() {
        // 유틸리티 홀더. 인스턴스화하지 않습니다.
    }

    // ========================================================================
    // [공통] 실패 조건 — orders-1 의 오프셋 42 만 실패시킨다
    // ========================================================================
    //
    // 모든 예제가 이 정책을 공유합니다. 실패 지점을 한 곳으로 고정해야
    // "어느 파티션이 몇 초 멈췄는가" 를 깨끗하게 측정할 수 있습니다.
    static final class FailurePolicy {

        static final int  FAIL_PARTITION = 1;
        static final long FAIL_OFFSET    = 42L;

        /** 시도 횟수를 레코드별로 센다. 로그의 attempt=N 이 여기서 나온다. */
        private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
        private static final Map<String, Long>          FIRST_AT = new ConcurrentHashMap<>();

        static boolean shouldFail(ConsumerRecord<?, ?> record) {
            return record.partition() == FAIL_PARTITION && record.offset() == FAIL_OFFSET;
        }

        /** attempt 번호와 최초 시도로부터의 경과 ms 를 "attempt=2 t=1004ms" 형태로 만든다. */
        static String stamp(ConsumerRecord<?, ?> record) {
            String key = record.topic() + "-" + record.partition() + "@" + record.offset();
            int attempt = ATTEMPTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            long first  = FIRST_AT.computeIfAbsent(key, k -> System.currentTimeMillis());
            long elapsed = System.currentTimeMillis() - first;
            return "attempt=%d t=%dms %s".formatted(attempt, elapsed, key);
        }

        static void reset() {
            ATTEMPTS.clear();
            FIRST_AT.clear();
        }
    }

    // ========================================================================
    // [7-2] DefaultErrorHandler 의 기본 동작 — 아무 설정도 안 하면
    // ========================================================================
    //
    // CommonErrorHandler 빈을 하나도 등록하지 않습니다.
    // Spring 이 FixedBackOff(0L, 9L) 짜리 DefaultErrorHandler 를 기본으로 꽂습니다.
    //
    // 기대 로그
    //   ERROR ... o.s.k.l.DefaultErrorHandler : Backoff FixedBackOff{interval=0,
    //             currentAttempts=10, maxAttempts=9} exhausted for orders-1@42
    //   INFO  ... 처리 완료 orders-1@43        ← 42 는 사라지고 43 으로 넘어감
    //
    // 확인
    //   kcg --describe --group s07-inventory  → LAG 전부 0. 그런데 ORD-0043 은 없음.
    @Component
    @Profile("step07-default")
    public static class DefaultBehavior {

        private static final Logger log = LoggerFactory.getLogger(DefaultBehavior.class);

        @KafkaListener(topics = "orders", groupId = "s07-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("실패 유발 {}", FailurePolicy.stamp(record));
                throw new IllegalStateException(
                        "재고 서비스 응답 없음: " + record.value().orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ========================================================================
    // [7-3] 백오프 전략 — FixedBackOff 와 ExponentialBackOffWithMaxRetries
    // ========================================================================
    @Configuration
    @Profile("step07-backoff")
    public static class BackOffConfig {

        /**
         * FixedBackOff(1000L, 3L) — 1초 간격, 재시도 3회(총 4번 호출).
         * 총 대기 3초.
         *
         * ExponentialBackOffWithMaxRetries 로 바꿔 보려면 아래 메서드를 주석 처리하고
         * exponentialHandler() 의 @Bean 주석을 푸세요. 둘 다 켜면 빈이 두 개라 자동 주입이 깨집니다.
         */
        @Bean
        public DefaultErrorHandler fixedBackOffHandler() {
            return new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
        }

        /**
         * 초기 1초 → 2배씩 → 상한 10초, 재시도 5회.
         * 대기 시퀀스: 1s, 2s, 4s, 8s, 10s(16s 가 maxInterval 로 잘림) = 총 25초.
         *
         * ⚠️ ExponentialBackOff(초기, 배수) 를 그냥 쓰면 maxElapsedTime 이 Long.MAX_VALUE 라
         *    무한 재시도가 됩니다. 반드시 WithMaxRetries 를 쓰거나 setMaxElapsedTime 을 거세요.
         */
        // @Bean
        public DefaultErrorHandler exponentialHandler() {
            ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
            backOff.setInitialInterval(1_000L);
            backOff.setMultiplier(2.0);
            backOff.setMaxInterval(10_000L);
            return new DefaultErrorHandler(backOff);
        }
    }

    @Component
    @Profile("step07-backoff")
    public static class BackOffDemo {

        private static final Logger log = LoggerFactory.getLogger(BackOffDemo.class);

        @KafkaListener(topics = "orders", groupId = "s07-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.info(FailurePolicy.stamp(record));   // attempt=N t=Nms orders-1@42
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ========================================================================
    // [7-4] ★핵심 함정★ 블로킹 재시도가 파티션 전체를 멈춘다
    // ========================================================================
    //
    // FixedBackOff(10000L, 5L) → 10초 × 5회 = 그 파티션이 50초 정지.
    // 그동안 LoadGenerator 가 초당 1건씩 계속 발행하므로 LAG 이 벌어집니다.
    //
    // ★ 측정 방법
    //   1) 재시도 시작 직후    : kcg --describe --group s07-inventory
    //   2) 30초 후 다시        : kcg --describe --group s07-inventory
    //   3) 소진(50초) 후 다시  : kcg --describe --group s07-inventory
    //
    //   orders-0, orders-2 는 LAG 0 을 유지하고 orders-1 만 47 까지 치솟습니다.
    //   소진 직후 밀린 47건이 한꺼번에 처리되며 LAG 이 0 으로 급락합니다("톱니 모양").
    @Configuration
    @Profile("step07-blocking")
    public static class BlockingConfig {

        @Bean
        public DefaultErrorHandler blockingHandler() {
            // 10초 × 5회 = 50초. max.poll.interval.ms(5분) 는 안 넘지만 파티션은 50초 정지.
            return new DefaultErrorHandler(new FixedBackOff(10_000L, 5L));
        }
    }

    @Component
    @Profile("step07-blocking")
    public static class BlockingDemo {

        private static final Logger log = LoggerFactory.getLogger(BlockingDemo.class);

        @KafkaListener(topics = "orders", groupId = "s07-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("★ 파티션 정지 유발 {}", FailurePolicy.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            // end-to-end 지연을 함께 찍어 "뒤에 줄 선 정상 메시지" 가 얼마나 기다렸는지 본다.
            long lagMs = System.currentTimeMillis() - record.timestamp();
            log.info("처리 완료 {}-{}@{} (발행→처리 {}ms)",
                    record.topic(), record.partition(), record.offset(), lagMs);
        }
    }

    /** 재시도가 도는 동안에도 계속 발행해서 LAG 이 벌어지는 것을 보여 주는 부하 생성기. */
    @Component
    @Profile("step07-blocking")
    public static class LoadGenerator implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

        private final KafkaTemplate<String, Object> template;

        public LoadGenerator(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread producer = new Thread(() -> {
                for (int seq = 1; seq <= 300; seq++) {
                    OrderCreated event = OrderCreated.of(seq);
                    template.send("orders", event.orderId(), event);
                    if (seq % 50 == 0) {
                        log.info("발행 {}건", seq);
                    }
                    try {
                        Thread.sleep(1_000L);   // 초당 1건
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "load-generator");
            producer.setDaemon(true);
            producer.start();
        }
    }

    // ========================================================================
    // [7-4] max.poll.interval.ms 초과 → LeaveGroup → 리밸런스 → 처음부터 반복
    // ========================================================================
    //
    // 기본 5분으로는 재현에 6분이 걸리므로, 전용 ConsumerFactory 에서 30초로 낮춥니다.
    // 백오프 총합은 10초 × 4회 = 40초라 30초를 넘깁니다.
    //
    // 기대 로그
    //   WARN  ... ConsumerCoordinator : consumer poll timeout has expired. ...
    //   INFO  ... ConsumerCoordinator : Member ... sending LeaveGroup request ...
    //   INFO  ... KafkaMessageListenerContainer : s07-inventory: partitions revoked: [orders-1]
    //   INFO  ... KafkaMessageListenerContainer : s07-inventory: partitions assigned: [orders-1, ...]
    //   INFO  ... attempt=1 t=0ms orders-1@42     ← ★ 처음부터 다시 시작
    //
    // ⚠️ 이 프로필은 컨슈머를 계속 죽입니다. 확인 후 즉시 앱을 종료하세요.
    @Configuration
    @Profile("step07-polltimeout")
    public static class PollTimeoutConfig {

        @Bean("shortPollFactory")
        public ConcurrentKafkaListenerContainerFactory<String, Object> shortPollFactory(
                ConsumerFactory<String, Object> defaultConsumerFactory) {

            Map<String, Object> props = new HashMap<>(defaultConsumerFactory.getConfigurationProperties());
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000);   // 5분 → 30초
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "s07-inventory");

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);
            // 10초 × 4회 = 40초 > max.poll.interval.ms(30초)
            factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(10_000L, 4L)));
            return factory;
        }
    }

    @Component
    @Profile("step07-polltimeout")
    public static class PollTimeoutDemo {

        private static final Logger log = LoggerFactory.getLogger(PollTimeoutDemo.class);

        @KafkaListener(topics = "orders", groupId = "s07-inventory",
                       containerFactory = "shortPollFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("★ poll timeout 유발 {}", FailurePolicy.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ========================================================================
    // [7-5][7-6] 예외 분류 + DeadLetterPublishingRecoverer
    // ========================================================================
    @Configuration
    @Profile("step07-dlt")
    public static class DltConfig {

        /**
         * 기본 목적지는 <원본토픽>.DLT 의 "같은 파티션 번호" 입니다.
         * orders-1@42 실패 → orders.DLT-1
         *
         * ⚠️ orders.DLT 의 파티션 수가 orders 보다 적으면 존재하지 않는 파티션으로 보내려다
         *    60초 타임아웃 뒤 실패하고, 그 레코드는 DLT 에도 못 갑니다.
         *    파티션 번호를 -1 로 주면 브로커가 정하므로 이 문제가 사라집니다(아래 주석 참고).
         */
        @Bean
        public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {

            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

            // 파티션 수 불일치가 걱정되면 이렇게 -1 을 주세요. DLT 안의 순서는 포기합니다.
            // DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            //         template, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

            DefaultErrorHandler handler =
                    new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));

            // [7-5] 비즈니스 검증 실패는 100번 해도 실패한다 → 재시도하지 말고 즉시 DLT.
            //       로그에 "Backoff none exhausted" 가 찍히면 성공입니다.
            handler.addNotRetryableExceptions(InvalidOrderException.class);

            // 재시도 사이사이에 무엇을 할지 훅을 걸 수 있습니다.
            handler.setRetryListeners((record, ex, deliveryAttempt) ->
                    LoggerFactory.getLogger(DltConfig.class)
                            .warn("retry listener: {}-{}@{} attempt={} cause={}",
                                    record.topic(), record.partition(), record.offset(),
                                    deliveryAttempt, ex.getClass().getSimpleName()));
            return handler;
        }
    }

    @Component
    @Profile("step07-dlt")
    public static class DltDemo {

        private static final Logger log = LoggerFactory.getLogger(DltDemo.class);

        @KafkaListener(topics = "orders", groupId = "s07-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            OrderCreated event = record.value();

            // (A) 비즈니스 검증 실패 → 재시도 없이 즉시 DLT
            if (FailurePolicy.shouldFail(record)) {
                log.info(FailurePolicy.stamp(record) + " InvalidOrderException");
                throw new InvalidOrderException(
                        "quantity 는 1 이상이어야 합니다: -3 (" + event.orderId() + ")");
            }

            // (B) 일시적 실패 → 1초 × 3회 재시도 후 DLT (orders-2@30 에서 유발)
            if (record.partition() == 2 && record.offset() == 30L) {
                log.info(FailurePolicy.stamp(record) + " RemoteApiException");
                throw new RemoteApiException("재고 API 타임아웃");
            }

            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ========================================================================
    // [7-7][7-8] DLT 헤더 덤프 + DLT 리스너
    // ========================================================================
    //
    // ⚠️ DLT 리스너의 groupId 는 본선과 반드시 다르게 둡니다.
    //    같은 그룹이면 한 컨슈머가 orders 와 orders.DLT 를 함께 구독해,
    //    DLT 처리가 막히면 본선까지 멈춥니다.
    // ⚠️ DLT 리스너에는 재시도를 걸지 않습니다. 여기서 또 실패하면 갈 곳이 없습니다.
    @Component
    @Profile("step07-dlt")
    public static class DltListener {

        private static final Logger log = LoggerFactory.getLogger(DltListener.class);

        @KafkaListener(topics = "orders.DLT", groupId = "s07-dlt-monitor")
        public void onDlt(ConsumerRecord<String, OrderCreated> record) {

            // (1) 편한 방식 — 헤더를 직접 뒤지지 않고 유틸로 뽑는다
            String origTopic  = headerAsString(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
            int    origPart   = headerAsInt(record,    KafkaHeaders.DLT_ORIGINAL_PARTITION);
            long   origOffset = headerAsLong(record,   KafkaHeaders.DLT_ORIGINAL_OFFSET);
            long   origTs     = headerAsLong(record,   KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);
            String exFqcn     = headerAsString(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
            String exMessage  = headerAsString(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);

            log.error("DLT 수신 origin={}-{}@{} ts={} key={} ex={} msg={}",
                    origTopic, origPart, origOffset, origTs, record.key(), exFqcn, exMessage);

            // (2) 원본 헤더가 실제로는 "바이너리" 라는 것을 눈으로 확인
            Header raw = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET);
            if (raw != null) {
                log.info("  원본 오프셋 헤더 raw = {} → ByteBuffer.getLong() = {}",
                        toHex(raw.value()), ByteBuffer.wrap(raw.value()).getLong());
            }

            // (3) 스택트레이스는 길어서 앞 200자만
            String stack = headerAsString(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
            if (stack != null) {
                log.info("  stacktrace(앞 200자) = {}",
                        stack.substring(0, Math.min(200, stack.length())));
            }

            log.info("ops 알림 발송 완료 orderId={}",
                    record.value() != null ? record.value().orderId() : record.key());
        }

        static String headerAsString(ConsumerRecord<?, ?> record, String name) {
            Header h = record.headers().lastHeader(name);
            return h == null ? null : new String(h.value());
        }

        static int headerAsInt(ConsumerRecord<?, ?> record, String name) {
            Header h = record.headers().lastHeader(name);
            return h == null ? -1 : ByteBuffer.wrap(h.value()).getInt();
        }

        static long headerAsLong(ConsumerRecord<?, ?> record, String name) {
            Header h = record.headers().lastHeader(name);
            return h == null ? -1L : ByteBuffer.wrap(h.value()).getLong();
        }

        static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append("\\x").append(String.format("%02X", b));
            }
            return sb.toString();
        }
    }

    // ========================================================================
    // [7-9] 리스너별로 다른 에러 정책 — 팩토리를 나눈다
    // ========================================================================
    //
    // 재고 차감  : DB 락 경합·순간 타임아웃이라 재시도할 가치가 있다 → 지수 백오프
    // 알림 발송  : 실패해도 파티션을 막을 이유가 없다 → 재시도 없이 즉시 DLT
    @Configuration
    @Profile("step07-dlt")
    public static class PerListenerFactoryConfig {

        @Bean("aggressiveRetryFactory")
        public ConcurrentKafkaListenerContainerFactory<String, Object> aggressiveRetryFactory(
                ConsumerFactory<String, Object> cf, KafkaTemplate<String, Object> template) {

            ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
            backOff.setInitialInterval(500L);
            backOff.setMultiplier(2.0);
            backOff.setMaxInterval(5_000L);   // 0.5 + 1 + 2 + 4 = 7.5초. 5분의 1/3 훨씬 아래

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(cf);
            factory.setConcurrency(3);
            factory.setCommonErrorHandler(new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template), backOff));
            return factory;
        }

        @Bean("noRetryFactory")
        public ConcurrentKafkaListenerContainerFactory<String, Object> noRetryFactory(
                ConsumerFactory<String, Object> cf, KafkaTemplate<String, Object> template) {

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(cf);
            factory.setConcurrency(3);
            // FixedBackOff(0L, 0L) = 재시도 0회. 실패하면 곧바로 리커버러(DLT) 호출.
            factory.setCommonErrorHandler(new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template), new FixedBackOff(0L, 0L)));
            return factory;
        }
    }

    @Component
    @Profile("step07-dlt")
    public static class NotificationListener {

        private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

        @KafkaListener(topics = "orders", groupId = "s07-notification",
                       containerFactory = "noRetryFactory")
        public void notifyCustomer(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("알림 발송 실패 → 재시도 없이 DLT {}-{}@{}",
                        record.topic(), record.partition(), record.offset());
                throw new IllegalStateException("SMS 게이트웨이 응답 없음");
            }
            log.info("알림 발송 완료 {}", record.key());
        }
    }

    // ========================================================================
    // [7-10] ⚠️ 일부러 잘못 만든 코드 — KafkaListenerErrorHandler 가 예외를 삼킨다
    // ========================================================================
    //
    // throw exception 이 주석 처리돼 있습니다. 이 상태로 실행하면:
    //   - "Backoff ... exhausted" 로그가 한 번도 안 찍힙니다
    //   - orders.DLT 는 영원히 비어 있습니다
    //   - LAG 은 0 이고 컨슈머는 건강합니다
    // 아래 throw 를 살려서 다시 실행하고 로그 차이를 반드시 비교하세요.
    @Configuration
    @Profile("step07-swallow")
    public static class SwallowingConfig {

        private static final Logger log = LoggerFactory.getLogger(SwallowingConfig.class);

        @Bean
        public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
            // 정교하게 설정해 두었지만 아래 리스너 에러 핸들러가 예외를 삼키면 전부 무용지물입니다.
            return new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template), new FixedBackOff(1_000L, 3L));
        }

        @Bean("swallowingErrorHandler")
        public KafkaListenerErrorHandler swallowingErrorHandler() {
            return (message, exception) -> {
                log.error("리스너 에러 핸들러 진입: {}", exception.getMessage());
                // throw exception;   // ⚠️ 이 한 줄이 없으면 컨테이너는 "성공" 으로 간주합니다
                return null;
            };
        }
    }

    @Component
    @Profile("step07-swallow")
    public static class SwallowDemo {

        private static final Logger log = LoggerFactory.getLogger(SwallowDemo.class);

        @KafkaListener(topics = "orders", groupId = "s07-inventory",
                       errorHandler = "swallowingErrorHandler")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (FailurePolicy.shouldFail(record)) {
                log.warn("실패 유발 {}", FailurePolicy.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ========================================================================
    // [7-8] DLT 수동 재발행 도구
    // ========================================================================
    //
    // 외부 API 가 복구된 뒤 DLT 의 메시지를 본선으로 되돌립니다.
    // ⚠️ 원인이 안 고쳐진 상태로 재발행하면 다시 실패해 DLT 로 돌아옵니다(무한 루프).
    //    실무에서는 x-replay-count 헤더로 재발행 횟수를 세고 N 회 초과 시 거부하세요.
    @Component
    @Profile("step07-replay")
    public static class DltReplayer implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(DltReplayer.class);

        private final ConsumerFactory<String, OrderCreated> consumerFactory;
        private final KafkaTemplate<String, Object> template;

        public DltReplayer(@Qualifier("kafkaConsumerFactory")
                           ConsumerFactory<String, OrderCreated> consumerFactory,
                           KafkaTemplate<String, Object> template) {
            this.consumerFactory = consumerFactory;
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            int replayed = replay(100);
            log.info("재발행 완료 {}건", replayed);
        }

        int replay(int max) {
            int count = 0;
            try (org.apache.kafka.clients.consumer.Consumer<String, OrderCreated> consumer =
                         consumerFactory.createConsumer("s07-replay", "-1")) {

                consumer.subscribe(java.util.List.of("orders.DLT"));
                org.apache.kafka.clients.consumer.ConsumerRecords<String, OrderCreated> records =
                        consumer.poll(Duration.ofSeconds(5));
                log.info("orders.DLT 에서 {}건 조회", records.count());

                for (ConsumerRecord<String, OrderCreated> r : records) {
                    if (count >= max) {
                        break;
                    }
                    // 원본 키를 그대로 써야 같은 파티션으로 돌아갑니다.
                    template.send("orders", r.key(), r.value());
                    log.info("orders 로 재발행 {}", r.key());
                    count++;
                }
                consumer.commitSync();
            }
            return count;
        }
    }
}

/**
 * 비즈니스 검증 실패. 재시도해도 결과가 같으므로 addNotRetryableExceptions 에 등록합니다.
 * FQCN 이 kafka_dlt-exception-cause-fqcn 헤더에 그대로 실립니다.
 */
class InvalidOrderException extends RuntimeException {
    InvalidOrderException(String message) {
        super(message);
    }
}

/** 외부 API 일시 장애. "5초 뒤에 하면 될 수도 있다" → 재시도 대상. */
class RemoteApiException extends RuntimeException {
    RemoteApiException(String message) {
        super(message);
    }
}
