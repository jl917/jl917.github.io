package com.example.order.step08;

/*
 * ============================================================================
 * Step 08 — @RetryableTopic 논블로킹 재시도 : Exercise (6문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step08/Exercise.java
 *
 * ★ 사전 준비 — application.yml
 *   spring.kafka.consumer.properties.spring.json.trusted.packages:
 *       "com.example.order.domain,com.example.order.step08"
 *
 * 실행 (문제마다 보조 프로필을 하나만 켭니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,ex08-q1'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,ex08-q2'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,ex08-q3'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,ex08-q4'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,ex08-q5'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,ex08-q6'
 *
 * ★ 매 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s08-ex-inventory   --all-topics --reset-offsets --to-earliest --execute
 *   kcg --group s08-ex-order-state --all-topics --reset-offsets --to-earliest --execute
 *
 * ⚠️ 문제 1 과 문제 2 는 접미사 전략이 달라 "서로 다른 토픽" 을 만듭니다.
 *    문제 1 을 확인한 뒤 반드시 지우고 문제 2 로 넘어가세요.
 *      kt --delete --topic orders-retry-1000
 *      kt --delete --topic orders-retry-2000
 *      kt --delete --topic orders-retry-4000
 *      kt --delete --topic orders-dlt
 *    안 지우면 kt --list 에 7개가 섞여 보여 답을 확인할 수 없습니다.
 *
 * ⚠️ 문제 6 은 @RetryableTopic 애노테이션을 "전부 지운 상태" 에서 풀어야 합니다.
 *    애노테이션이 남아 있으면 그쪽이 이겨서 빈이 무시되고, 정답 여부를 구분할 수 없습니다.
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
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Exercise {

    private Exercise() {
    }

    // ------------------------------------------------------------------
    // 공통 — 실패 조건과 시도 시각 (수정하지 마세요)
    // ------------------------------------------------------------------
    static final class Fail {

        static final int  PARTITION = 1;
        static final long OFFSET    = 42L;

        private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
        private static final Map<String, Long>          FIRST_AT = new ConcurrentHashMap<>();

        /** 본선에서는 orders-1@42 만, retry 토픽에서는 전부 실패시킵니다. */
        static boolean shouldFail(ConsumerRecord<?, ?> r) {
            if (!"orders".equals(r.topic())) {
                return true;
            }
            return r.partition() == PARTITION && r.offset() == OFFSET;
        }

        static String stamp(ConsumerRecord<?, ?> r, String orderId) {
            int attempt = ATTEMPTS.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
            long first  = FIRST_AT.computeIfAbsent(orderId, k -> System.currentTimeMillis());
            return "attempt=%d t=%dms topic=%s %s".formatted(
                    attempt, System.currentTimeMillis() - first, r.topic(), orderId);
        }
    }

    // ==================================================================
    // 문제 1. @RetryableTopic 을 붙이고 생성된 토픽 이름을 관찰하기
    // ==================================================================
    //
    // 요구사항
    //   - attempts = "4"
    //   - backoff  = @Backoff(delay = 1000, multiplier = 2.0)
    //   - kafkaTemplate = "kafkaTemplate"
    //   - 접미사나 접미사 전략은 "아무것도 지정하지 말 것" (기본값을 보는 것이 목적)
    //
    // 기동 후 `kt --list` 결과를 아래에 그대로 옮겨 적으세요.
    //
    //   // 관측 기록 (kt --list):
    //   //   orders
    //   //   ______________________
    //   //   ______________________
    //   //   ______________________
    //   //   ______________________
    //   //
    //   // Q. 접미사에 붙은 숫자는 무엇인가?  →  ______________________
    //   // Q. 그렇다면 topicSuffixingStrategy 의 기본값은?  →  ______________________
    //   // Q. 운영 중에 delay 를 1000 → 1500 으로 바꾸면 어떻게 되는가? → ______________________
    //
    // 확인: kt --list 에 "orders-dlt" 가 보여야 합니다 (orders.DLT 가 아닙니다)
    @Component
    @Profile("ex08-q1")
    public static class Q1DefaultSuffix {

        private static final Logger log = LoggerFactory.getLogger(Q1DefaultSuffix.class);

        // 여기에 작성: @RetryableTopic 을 붙이세요

        @KafkaListener(topics = "orders", groupId = "s08-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                log.warn("★ 실패 {}", Fail.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ==================================================================
    // 문제 2. 코스 규약에 맞추고 백오프 시각을 실측하기
    // ==================================================================
    //
    // 요구사항
    //   - retry 토픽 이름이 orders-retry-0, orders-retry-1, orders-retry-2 가 되도록
    //   - DLT 이름이 orders.DLT 가 되도록 (Step 07 과 같은 토픽을 재사용합니다)
    //   - 파티션 3, 복제 1 로 만들어지도록 명시
    //   - 백오프 1s → 2s → 4s
    //
    // 로그의 t=Nms 를 아래에 적으세요.
    //   // 관측 기록:
    //   //   attempt=1 topic=orders          t=_______ms
    //   //   attempt=2 topic=orders-retry-0  t=_______ms
    //   //   attempt=3 topic=orders-retry-1  t=_______ms
    //   //   attempt=4 topic=orders-retry-2  t=_______ms
    //
    // 확인: kt --list 에 orders-retry-0/-1/-2 와 orders.DLT 가 있고 orders-dlt 는 없어야 합니다
    @Component
    @Profile("ex08-q2")
    public static class Q2CourseConvention {

        private static final Logger log = LoggerFactory.getLogger(Q2CourseConvention.class);

        // 여기에 작성: @RetryableTopic (topicSuffixingStrategy, retryTopicSuffix,
        //              dltTopicSuffix, numPartitions, replicationFactor 를 명시)

        @KafkaListener(topics = "orders", groupId = "s08-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                log.warn("★ 실패 {}", Fail.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ==================================================================
    // 문제 3. @DltHandler 로 실패 원인 복원하기
    // ==================================================================
    //
    // 요구사항
    //   - 같은 클래스 안에 @DltHandler 메서드를 만들 것
    //   - @Header 로 다음 넷을 받아 로그로 남길 것
    //       KafkaHeaders.ORIGINAL_TOPIC      (String)
    //       KafkaHeaders.ORIGINAL_PARTITION  (int)
    //       KafkaHeaders.ORIGINAL_OFFSET     (long)
    //       KafkaHeaders.EXCEPTION_FQCN      (String)
    //
    //   // Q. ORIGINAL_TOPIC 에 무엇이 찍혔는가?  →  ______________________
    //   //    "orders-retry-2" 를 예상했다면 왜 틀렸는지 한 줄로 설명하세요.
    //   //    → ______________________________________________________
    //
    // 확인: "DLT 수신 ORD-0043 origin=orders-1@42" 가 찍혀야 합니다
    @Component
    @Profile("ex08-q3")
    public static class Q3DltHandler {

        private static final Logger log = LoggerFactory.getLogger(Q3DltHandler.class);

        @RetryableTopic(
                attempts = "3",
                backoff = @Backoff(delay = 500, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                throw new RemoteApiException("재고 API 타임아웃: " + record.value().orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        // 여기에 작성: @DltHandler 메서드
    }

    // ==================================================================
    // 문제 4. ★핵심★ 같은 키 3건으로 순서 깨짐을 재현하기
    // ==================================================================
    //
    // 요구사항
    //   - 리스너에 @RetryableTopic 을 붙일 것 (attempts=4, delay=1000, multiplier=2.0,
    //     인덱스 접미사, dltTopicSuffix=".DLT")
    //   - 아래 onMessage 는 이미 "첫 CREATED 만 1회 실패" 하도록 되어 있습니다. 수정하지 마세요.
    //   - Q4Publisher 가 ORD-0002 의 3건을 순서대로 발행합니다.
    //
    // 로그를 보고 타임라인 표를 채우세요. 코드는 거의 안 씁니다.
    //
    //   // 관측 기록:
    //   // | 시각      | 토픽            | 이벤트    | 결과            | STATE          |
    //   // |----------|----------------|----------|----------------|----------------|
    //   // | t=____ms | orders         | CREATED  | ______________ | ______________ |
    //   // | t=____ms | orders         | UPDATED  | ______________ | ______________ |
    //   // | t=____ms | orders         | CANCELLED| ______________ | ______________ |
    //   // | t=____ms | ______________ | CREATED  | ______________ | ______________ |
    //   //
    //   // 최종 상태 ORD-0002 = ______________
    //   //
    //   // Q. ERROR 로그가 몇 줄 찍혔는가?  →  ______
    //   // Q. @DltHandler 는 호출됐는가?    →  ______
    //   // Q. kcg --describe 의 LAG 은?     →  ______
    //
    // 확인: 마지막 줄이 "최종 상태 ORD-0002 = CREATED" 여야 합니다 (CANCELLED 가 아닙니다)
    @Component
    @Profile("ex08-q4")
    public static class Q4OrderState {

        private static final Logger log = LoggerFactory.getLogger(Q4OrderState.class);

        static final Map<String, OrderState> STATE = new ConcurrentHashMap<>();
        static final AtomicBoolean FIRST_TRY = new AtomicBoolean(true);
        static final long T0 = System.currentTimeMillis();

        static long elapsed() {
            return System.currentTimeMillis() - T0;
        }

        // 여기에 작성: @RetryableTopic

        @KafkaListener(topics = "orders", groupId = "s08-ex-order-state")
        public void onMessage(ConsumerRecord<String, OrderEvent> record) {
            OrderEvent event = record.value();
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

    @Component
    @Profile("ex08-q4")
    public static class Q4Publisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Q4Publisher.class);

        private final KafkaTemplate<String, Object> template;

        public Q4Publisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                sleep(2_000L);
                for (OrderState s : new OrderState[]{
                        OrderState.CREATED, OrderState.UPDATED, OrderState.CANCELLED}) {
                    OrderEvent e = OrderEvent.of("ORD-0002", s);
                    template.send("orders", e.orderId(), e);
                    log.info("발행 {} {} (t={}ms)", e.orderId(), s, Q4OrderState.elapsed());
                }
                sleep(3_000L);
                log.info("최종 상태 ORD-0002 = {}", Q4OrderState.STATE.get("ORD-0002"));
            }, "q4-publisher");
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

    // ==================================================================
    // 문제 5. 특정 예외는 재시도 없이 즉시 DLT
    // ==================================================================
    //
    // 요구사항
    //   - InvalidOrderException 은 재시도하지 말고 곧바로 orders.DLT 로 보낼 것
    //   - RemoteApiException 은 정상적으로 3번 재시도할 것
    //   - 힌트 1: @RetryableTopic 의 exclude 속성
    //   - 힌트 2: 리스너 예외는 ListenerExecutionFailedException 으로 "감싸여" 옵니다.
    //             원인 체인을 따라가게 하는 속성이 하나 더 필요합니다.
    //
    //   // Q. 힌트 2 의 속성 이름은?  →  ______________________
    //
    // 확인: orders-1@42(InvalidOrderException) 에 대해
    //         "Republishing failed record to orders.DLT-1" 이 곧바로 찍히고
    //       ★ "orders-retry-0" 이 로그에 단 한 번도 나오면 안 됩니다.
    //       kcc --topic orders-retry-0 --from-beginning 결과가 비어 있어야 합니다.
    @Component
    @Profile("ex08-q5")
    public static class Q5Exclude {

        private static final Logger log = LoggerFactory.getLogger(Q5Exclude.class);

        // 여기에 작성: @RetryableTopic (exclude 와 원인 체인 탐색 속성 포함)

        @KafkaListener(topics = "orders", groupId = "s08-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            OrderCreated event = record.value();

            // (A) 검증 실패 → 재시도 없이 즉시 DLT 여야 합니다
            if (record.partition() == 1 && record.offset() == 42) {
                log.warn("★ 검증 실패 {} (topic={})", event.orderId(), record.topic());
                throw new InvalidOrderException("quantity 는 1 이상이어야 합니다: " + event.orderId());
            }

            // (B) 외부 API 실패 → 정상적으로 재시도돼야 합니다
            if (record.partition() == 2 && record.offset() == 30) {
                log.warn("★ API 실패 {} (topic={})", event.orderId(), record.topic());
                throw new RemoteApiException("재고 API 타임아웃");
            }

            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event,
                          @Header(KafkaHeaders.EXCEPTION_FQCN) String exFqcn) {
            log.error("DLT 수신 {} ex={}", event.orderId(),
                    exFqcn.substring(exFqcn.lastIndexOf('.') + 1));
        }
    }

    // ==================================================================
    // 문제 6. RetryTopicConfiguration 빈으로 전역 설정하기
    // ==================================================================
    //
    // 요구사항
    //   - 아래 두 리스너에 @RetryableTopic 이 하나도 없습니다. 그대로 두세요.
    //   - RetryTopicConfiguration 빈 하나로 문제 2 와 같은 정책을 적용할 것
    //       includeTopic("orders") / maxAttempts 4 / 지수 1s·2배·상한 10s
    //       인덱스 접미사 / retry 접미사 "-retry" / DLT 접미사 ".DLT"
    //       concurrency 1 / 토픽 자동 생성 파티션 3 복제 1
    //       InvalidOrderException 은 재시도 제외
    //
    //   // Q. 두 리스너(s08-ex-inventory, s08-ex-notification) 모두에 적용됐는가? → ______
    //   // Q. 컨슈머 그룹은 몇 개인가? (kcg --list)                              → ______
    //
    // 확인: kt --list 에 orders-retry-0/-1/-2, orders.DLT 가 생겨야 합니다
    @Configuration
    @Profile("ex08-q6")
    public static class Q6BuilderConfig {

        @Bean
        public RetryTopicConfiguration ordersRetryTopicConfig(KafkaTemplate<String, Object> template) {
            // 여기에 작성: RetryTopicConfigurationBuilder 체인
            return RetryTopicConfigurationBuilder.newInstance().create(template);
        }
    }

    @Component
    @Profile("ex08-q6")
    public static class Q6Inventory {

        private static final Logger log = LoggerFactory.getLogger(Q6Inventory.class);

        @KafkaListener(topics = "orders", groupId = "s08-ex-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                log.warn("★ 실패 {}", Fail.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("재고 처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    @Component
    @Profile("ex08-q6")
    public static class Q6Notification {

        private static final Logger log = LoggerFactory.getLogger(Q6Notification.class);

        @KafkaListener(topics = "orders", groupId = "s08-ex-notification")
        public void notifyCustomer(ConsumerRecord<String, OrderCreated> record) {
            log.info("알림 발송 완료 {} (topic={})", record.key(), record.topic());
        }
    }
}
