package com.example.order.step07;

/*
 * ============================================================================
 * Step 07 — 에러 처리와 재시도 : Exercise (6문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step07/Exercise.java
 *
 * 실행 (문제마다 보조 프로필을 하나만 켭니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,ex07-q1'
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,ex07-q2'
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,ex07-q3'   (문제 3+5 동시)
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,ex07-q4'
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,ex07-q6'
 *
 * ★ 매 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s07-ex-inventory   --topic orders     --reset-offsets --to-earliest --execute
 *   kcg --group s07-ex-dlt-monitor --topic orders.DLT --reset-offsets --to-earliest --execute
 *
 * ★ 문제 3 을 풀기 전에 반드시 확인
 *   kt --describe --topic orders.DLT     → PartitionCount 가 3 이어야 합니다.
 *                                          1 이면 7-6 의 함정에 그대로 걸립니다.
 *
 * ⚠️ 문제 6 은 컨슈머를 일부러 그룹에서 쫓아냅니다. 확인 후 앱을 반드시 종료하세요.
 *    켜 둔 채 다른 문제로 넘어가면 리밸런스가 계속 나서 다른 리스너까지 영향을 받습니다.
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class Exercise {

    private Exercise() {
    }

    // ------------------------------------------------------------------
    // 공통 — 실패 조건과 시도 시각 기록 (수정하지 마세요)
    // ------------------------------------------------------------------
    static final class Fail {

        private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
        private static final Map<String, Long>          FIRST_AT = new ConcurrentHashMap<>();

        static boolean at(ConsumerRecord<?, ?> r, int partition, long offset) {
            return r.partition() == partition && r.offset() == offset;
        }

        static String stamp(ConsumerRecord<?, ?> r) {
            String key = r.topic() + "-" + r.partition() + "@" + r.offset();
            int attempt = ATTEMPTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            long first = FIRST_AT.computeIfAbsent(key, k -> System.currentTimeMillis());
            return "attempt=%d t=%dms %s".formatted(attempt, System.currentTimeMillis() - first, key);
        }
    }

    // ==================================================================
    // 문제 1. ExponentialBackOffWithMaxRetries 설정
    // ==================================================================
    //
    // 요구사항
    //   - 초기 간격 1초, 배수 2.0, 최대 간격 10초, 재시도 5회
    //   - DefaultErrorHandler 빈으로 등록해 자동 주입되게 할 것
    //   - 리스너는 orders-1@42 에서만 실패시키고, 시도 시각을 Fail.stamp() 로 로그에 남길 것
    //
    // 힌트
    //   - ExponentialBackOff 가 아니라 ExponentialBackOffWithMaxRetries 입니다.
    //     그냥 ExponentialBackOff 는 maxElapsedTime 기본값이 Long.MAX_VALUE 라 무한 재시도입니다.
    //
    // 확인: 대기 시퀀스가 1s → 2s → 4s → 8s → 10s 이고 마지막 로그가
    //       attempt=6 t=25xxxms orders-1@42
    //       그리고 "Backoff ExponentialBackOffWithMaxRetries{...} exhausted for orders-1@42"
    @Configuration
    @Profile("ex07-q1")
    public static class Q1Config {

        @Bean
        public DefaultErrorHandler q1ErrorHandler() {
            // 여기에 작성:
            return null;
        }
    }

    @Component
    @Profile("ex07-q1")
    public static class Q1Listener {

        private static final Logger log = LoggerFactory.getLogger(Q1Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            // 여기에 작성: orders-1@42 에서만 예외를 던지고, 그 전에 Fail.stamp(record) 를 로그로 남기세요.
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ==================================================================
    // 문제 2. 비즈니스 검증 예외를 재시도 제외로 등록
    // ==================================================================
    //
    // 요구사항
    //   - 백오프는 FixedBackOff(1000L, 3L) 로 두되
    //   - InvalidOrderException 은 재시도하지 말고 즉시 리커버러로 넘길 것
    //   - 리스너는 orders-1@42 에서 InvalidOrderException 을 던질 것
    //
    // 힌트
    //   - DefaultErrorHandler 에는 addNotRetryableExceptions / addRetryableExceptions /
    //     setClassifications(map, defaultRetryable) 세 가지 방법이 있습니다. 가장 간단한 것을 고르세요.
    //   - InvalidOrderException 은 Practice.java 파일 하단에 정의돼 있습니다.
    //
    // 확인: ERROR ... o.s.k.l.DefaultErrorHandler : Backoff none exhausted for orders-1@42
    //       (attempt 가 1 번뿐이어야 합니다. 2 가 찍히면 틀린 것입니다)
    @Configuration
    @Profile("ex07-q2")
    public static class Q2Config {

        @Bean
        public DefaultErrorHandler q2ErrorHandler(KafkaTemplate<String, Object> template) {
            DefaultErrorHandler handler = new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template), new FixedBackOff(1_000L, 3L));

            // 여기에 작성:

            return handler;
        }
    }

    @Component
    @Profile("ex07-q2")
    public static class Q2Listener {

        private static final Logger log = LoggerFactory.getLogger(Q2Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            // 여기에 작성: orders-1@42 에서 InvalidOrderException 을 던지세요.
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ==================================================================
    // 문제 3. DeadLetterPublishingRecoverer + DLT 리스너
    // ==================================================================
    //
    // 요구사항
    //   - orders-1@42 를 3회 재시도(1초 간격) 후 orders.DLT 로 발행
    //   - orders.DLT 를 구독하는 리스너를 만들고, 아래 5가지를 한 줄 로그로 남길 것
    //       원본 토픽 / 원본 파티션 / 원본 오프셋 / 예외 FQCN / 예외 메시지
    //   - DLT 리스너의 groupId 는 본선(s07-ex-inventory)과 반드시 다르게 둘 것
    //
    // 힌트
    //   - @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int 처럼 선언하면
    //     Spring 이 바이트 배열을 알아서 변환해 줍니다.
    //   - 상수 이름: DLT_ORIGINAL_TOPIC / DLT_ORIGINAL_PARTITION / DLT_ORIGINAL_OFFSET /
    //               DLT_EXCEPTION_FQCN / DLT_EXCEPTION_MESSAGE
    //
    // 확인: INFO ... o.s.k.l.DeadLetterPublishingRecoverer : Republishing failed record to orders.DLT-1
    //       ERROR ... DLT 수신 origin=orders-1@42 ...
    @Configuration
    @Profile("ex07-q3")
    public static class Q3Config {

        @Bean
        public DefaultErrorHandler q3ErrorHandler(KafkaTemplate<String, Object> template) {
            // 여기에 작성:
            return null;
        }
    }

    @Component
    @Profile("ex07-q3")
    public static class Q3Listener {

        private static final Logger log = LoggerFactory.getLogger(Q3Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.info(Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음: " + record.value().orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    @Component
    @Profile("ex07-q3")
    public static class Q3DltListener {

        private static final Logger log = LoggerFactory.getLogger(Q3DltListener.class);

        // 여기에 작성: orders.DLT 를 구독하는 @KafkaListener 메서드를 만드세요.
        //             groupId 는 "s07-ex-dlt-monitor" 를 쓰세요.
    }

    // ==================================================================
    // 문제 4. 블로킹 재시도로 파티션이 멈추는 것을 LAG 으로 관측
    // ==================================================================
    //
    // 이 문제는 코드를 거의 안 씁니다. 대신 아래 표를 직접 채우세요.
    //
    // 요구사항
    //   - 백오프를 FixedBackOff(10000L, 5L) 로 설정 (10초 × 5회 = 50초 정지)
    //   - 앱을 켠 뒤 kcg --describe --group s07-ex-inventory 를 세 번 찍을 것
    //       ① 재시도 시작 직후 ② 30초 후 ③ 소진(50초) 후
    //   - Loader 가 초당 1건씩 계속 발행하므로 LOG-END-OFFSET 은 계속 늘어납니다.
    //
    // 관측 기록: (여기에 작성)
    //
    //   ① 재시도 시작 직후
    //   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
    //   0          ____            ____            ____
    //   1          ____            ____            ____
    //   2          ____            ____            ____
    //
    //   ② 30초 후
    //   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
    //   0          ____            ____            ____
    //   1          ____            ____            ____
    //   2          ____            ____            ____
    //
    //   ③ 소진 후
    //   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
    //   0          ____            ____            ____
    //   1          ____            ____            ____
    //   2          ____            ____            ____
    //
    //   결론(한 줄로): ______________________________________________
    //
    // 확인: 파티션 1 의 CURRENT-OFFSET 이 ①②에서 42 로 고정되어 있어야 합니다.
    @Configuration
    @Profile("ex07-q4")
    public static class Q4Config {

        @Bean
        public DefaultErrorHandler q4ErrorHandler() {
            // 여기에 작성: 10초 간격 5회 재시도
            return null;
        }
    }

    @Component
    @Profile("ex07-q4")
    public static class Q4Listener {

        private static final Logger log = LoggerFactory.getLogger(Q4Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.warn("★ 파티션 정지 유발 {}", Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{} (발행→처리 {}ms)",
                    record.topic(), record.partition(), record.offset(),
                    System.currentTimeMillis() - record.timestamp());
        }
    }

    /** LAG 이 벌어지는 것을 보려면 계속 발행해야 합니다. 수정하지 마세요. */
    @Component
    @Profile("ex07-q4")
    public static class Q4Loader implements ApplicationRunner {

        private final KafkaTemplate<String, Object> template;

        public Q4Loader(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                for (int seq = 1; seq <= 300; seq++) {
                    OrderCreated e = OrderCreated.of(seq);
                    template.send("orders", e.orderId(), e);
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "q4-loader");
            t.setDaemon(true);
            t.start();
        }
    }

    // ==================================================================
    // 문제 5. DLT 헤더에서 원본 오프셋을 "바이트로" 직접 꺼내기
    // ==================================================================
    //
    // 문제 3 의 DLT 리스너와 같은 프로필(ex07-q3)에서 함께 돕니다.
    //
    // 요구사항
    //   - @Header 로 편하게 받는 대신, ConsumerRecord.headers() 를 직접 뒤져
    //     DLT_ORIGINAL_OFFSET 헤더의 byte[] 를 꺼낼 것
    //   - 그 byte[] 를 16진수 문자열로 찍고, ByteBuffer 로 long 으로 변환해 함께 찍을 것
    //   - DLT_ORIGINAL_PARTITION 도 같은 방식으로 int 로 변환할 것
    //
    // 힌트
    //   - record.headers().lastHeader(name) 이 Header 를 돌려줍니다. 없으면 null.
    //   - ByteBuffer.wrap(bytes).getLong() / .getInt()
    //   - 파티션은 4바이트, 오프셋/타임스탬프는 8바이트입니다.
    //
    // 확인: 원본 오프셋 raw = \x00\x00\x00\x00\x00\x00\x00\x2A → 42
    @Component
    @Profile("ex07-q3")
    public static class Q5HeaderDumper {

        private static final Logger log = LoggerFactory.getLogger(Q5HeaderDumper.class);

        @KafkaListener(topics = "orders.DLT", groupId = "s07-ex-header-dumper")
        public void dump(ConsumerRecord<String, OrderCreated> record) {
            // 여기에 작성:
        }

        static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append("\\x").append(String.format("%02X", b));
            }
            return sb.toString();
        }
    }

    // ==================================================================
    // 문제 6. max.poll.interval.ms 초과로 리밸런스 유발
    // ==================================================================
    //
    // 요구사항
    //   - 전용 ConcurrentKafkaListenerContainerFactory 를 만들고
    //     max.poll.interval.ms 를 30초로 낮출 것
    //   - 백오프 총합이 30초를 넘도록 FixedBackOff 를 설정할 것 (예: 10초 × 4회 = 40초)
    //   - 리스너가 그 팩토리를 쓰게 할 것
    //
    // 힌트
    //   - ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG
    //   - 기본 ConsumerFactory 의 getConfigurationProperties() 를 복사해서 덮어쓰면 편합니다.
    //   - GROUP_ID_CONFIG 도 함께 넣어 주어야 합니다.
    //
    // 확인 (이 세 줄이 순서대로 나와야 합니다)
    //   WARN ... consumer poll timeout has expired. ...
    //   INFO ... Member ... sending LeaveGroup request ... due to consumer poll timeout has expired.
    //   INFO ... attempt=1 t=0ms orders-1@42            ← ★ 처음부터 다시 시작
    //
    // ⚠️ 확인했으면 앱을 반드시 종료하세요. 무한 루프입니다.
    @Configuration
    @Profile("ex07-q6")
    public static class Q6Config {

        @Bean("q6Factory")
        public ConcurrentKafkaListenerContainerFactory<String, Object> q6Factory(
                ConsumerFactory<String, Object> defaultConsumerFactory) {

            Map<String, Object> props = new HashMap<>(defaultConsumerFactory.getConfigurationProperties());

            // 여기에 작성: max.poll.interval.ms 를 30초로, group.id 를 s07-ex-inventory 로

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);

            // 여기에 작성: 총합 40초짜리 백오프를 가진 DefaultErrorHandler 를 setCommonErrorHandler 로

            return factory;
        }
    }

    @Component
    @Profile("ex07-q6")
    public static class Q6Listener {

        private static final Logger log = LoggerFactory.getLogger(Q6Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-ex-inventory",
                       containerFactory = "q6Factory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.warn("★ poll timeout 유발 {}", Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }
}
