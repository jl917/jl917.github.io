package com.example.order.step05;

/*
 * ============================================================================
 * Step 05 — 메시지 변환과 헤더 : Exercise (7문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step05/Exercise.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step05-ex'
 *
 * 문제 6 만 따로 (일부러 깨진 코드를 먼저 보기 위한 프로필)
 *   ./gradlew bootRun --args='--spring.profiles.active=step05-ex,step05-ex6-broken'
 *   ./gradlew bootRun --args='--spring.profiles.active=step05-ex,step05-ex6-fixed'
 *
 * 사전 준비
 *   domain/OrderCancelled.java (본문 5-0) 가 있어야 문제 4·7 이 컴파일됩니다.
 *
 * 다시 풀 때 (앱 종료 후 해당 그룹만 리셋)
 *   kcg --group s05-ex1 --topic orders --reset-offsets --to-earliest --execute
 *
 * 로그 패턴 (문제 2 에 필요)
 *   logging.pattern.console: "%clr(%5p) ... [%X{traceId:-        }] ..."
 * ============================================================================
 */

import com.example.order.domain.OrderCancelled;
import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

public final class Exercise {

    private Exercise() {
    }

    // ========================================================================
    // 공통 — 문제들이 소비할 메시지를 발행합니다. 수정하지 마세요.
    // ========================================================================
    //
    // ORD-0001 : OrderCreated,   traceId 있음, retryCount 없음
    // ORD-0002 : OrderCancelled, traceId 있음
    // ORD-0003 : OrderCreated,   traceId 없음          ← 문제 5 의 판정 대상
    // ORD-0004 : OrderCreated,   traceId 있음, retryCount=2
    //
    @Component
    @Profile("step05-ex")
    public static class Fixture implements ApplicationRunner {

        private final KafkaTemplate<String, Object> template;

        public Fixture(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 4; seq++) {
                String orderId = "ORD-%04d".formatted(seq);
                Object payload = (seq == 2) ? OrderCancelled.of(seq) : OrderCreated.of(seq);

                var builder = org.springframework.messaging.support.MessageBuilder
                        .withPayload(payload)
                        .setHeader(KafkaHeaders.TOPIC, "orders")
                        .setHeader(KafkaHeaders.KEY, orderId);
                if (seq != 3) {
                    builder.setHeader("traceId", "trace-%04d".formatted(seq));
                }
                if (seq == 4) {
                    builder.setHeader("retryCount", "2");
                }
                template.send(builder.build());
            }
            template.flush();
        }
    }

    // ========================================================================
    // 문제 1. 메타데이터를 한 줄로 남기는 리스너
    // ========================================================================
    //
    // 요구사항
    //   · groupId = "s05-ex1", topics = "orders"
    //   · ConsumerRecord 를 쓰지 말고 @Header 만으로 다음을 전부 받으세요:
    //       토픽 / 파티션 / 오프셋 / 키 / 타임스탬프
    //   · 페이로드는 Object 로 받고, 파라미터가 2개 이상이므로 @Payload 를 명시할 것
    //   · KafkaHeaders 상수를 쓸 것. 문자열("kafka_receivedPartitionId" 등) 금지
    //
    // 기대 로그
    //   topic=orders p=1 off=0 key=ORD-0001 ts=1735689660000 payload=OrderCreated
    //
    @Component
    @Profile("step05-ex")
    public static class Q1 {

        private static final Logger log = LoggerFactory.getLogger(Q1.class);

        // 여기에 작성:
        // @KafkaListener(...)
        // public void onOrder(...) { ... }
    }

    // ========================================================================
    // 문제 2. traceId 를 MDC 로 전파하기
    // ========================================================================
    //
    // 요구사항
    //   · groupId = "s05-ex2"
    //   · traceId 헤더를 옵셔널로 받아 MDC 에 넣고, 처리 로그 2줄을 남긴 뒤 반드시 정리할 것
    //   · traceId 가 없으면 MDC 에 "-" 를 넣을 것
    //   · 정리를 어디에서 해야 하는지 그 이유를 주석으로 한 줄 적을 것
    //
    // 기대 로그 (logging.pattern.console 에 %X{traceId} 를 넣은 뒤)
    //    INFO ... [trace-0001] ...Q2 : 처리 시작 ORD-0001
    //    INFO ... [trace-0001] ...Q2 : 처리 완료 ORD-0001
    //    INFO ... [        ]   ...Q2 : 처리 시작 ORD-0003     ← 대괄호가 비어야 정답
    //
    // ⚠️ ORD-0003 줄에 trace-0002 가 찍힌다면 정리를 안 한 것입니다.
    //
    @Component
    @Profile("step05-ex")
    public static class Q2 {

        private static final Logger log = LoggerFactory.getLogger(Q2.class);

        // 여기에 작성:
    }

    // ========================================================================
    // 문제 3. 모든 헤더를 덤프하기
    // ========================================================================
    //
    // 요구사항
    //   · groupId = "s05-ex3"
    //   · ConsumerRecord 를 받아 헤더 전체를 "이름=값(UTF-8)" 형태로 한 줄에 출력할 것
    //   · @Headers Map 을 쓰지 말 것. 왜 안 되는지 주석으로 한 줄 적을 것
    //   · 값이 null 인 헤더도 안전하게 처리할 것
    //
    // 기대 로그
    //   ORD-0001 headers=[__TypeId__=com.example.order.domain.OrderCreated, traceId=trace-0001,
    //                     spring_json_header_types={"traceId":"java.lang.String"}]
    //
    @Component
    @Profile("step05-ex")
    public static class Q3 {

        private static final Logger log = LoggerFactory.getLogger(Q3.class);

        // 여기에 작성:
    }

    // ========================================================================
    // 문제 4. @KafkaHandler 로 타입 분기
    // ========================================================================
    //
    // 요구사항
    //   · 이 클래스에 클래스 레벨 @KafkaListener 를 붙이세요 (topics="orders", groupId="s05-ex4",
    //     containerFactory = "exJsonFactory" — 문제 7 에서 만들 팩토리입니다)
    //   · OrderCreated 용 @KafkaHandler, OrderCancelled 용 @KafkaHandler 를 각각 작성
    //   · @KafkaHandler(isDefault = true) 로 나머지를 받되, WARN 으로 타입과 키를 남길 것
    //     (조용히 삼키면 안 되는 이유를 주석으로 한 줄)
    //
    // 기대 로그
    //   [created]   ORD-0001 sku=SKU-002
    //   [cancelled] ORD-0002 reason=OUT_OF_STOCK
    //
    @Component
    @Profile("step05-ex")
    // 여기에 작성: 클래스 레벨 @KafkaListener
    public static class Q4 {

        private static final Logger log = LoggerFactory.getLogger(Q4.class);

        // 여기에 작성: @KafkaHandler 3개
    }

    // ========================================================================
    // 문제 5. 옵셔널 헤더 두 개를 안전하게 받기
    // ========================================================================
    //
    // 요구사항
    //   · groupId = "s05-ex5"
    //   · traceId  : 없을 수 있음 → 없으면 null
    //   · retryCount : 없을 수 있음 → 없으면 0 (defaultValue 사용)
    //   · ⚠️ retryCount 를 int 로 선언하면 안 되는 이유를 주석으로 적을 것
    //
    // 기대 로그
    //   ORD-0001 traceId=trace-0001 retry=0
    //   ORD-0003 traceId=- retry=0
    //   ORD-0004 traceId=trace-0004 retry=2
    //
    @Component
    @Profile("step05-ex")
    public static class Q5 {

        private static final Logger log = LoggerFactory.getLogger(Q5.class);

        // 여기에 작성:
    }

    // ========================================================================
    // 문제 6. [B@1a2b3c 사고를 재현하고 고치기
    // ========================================================================
    //
    // (6-a) 먼저 아래 BrokenPublisher/BrokenListener 를 그대로 실행해 보세요.
    //       ./gradlew bootRun --args='--spring.profiles.active=step05-ex,step05-ex6-broken'
    //       traceId 가 [B@ 로 시작하는 값으로 찍히는 것을 눈으로 확인해야 합니다.
    //       ⚠️ 확인하지 않고 정답부터 쓰면 절반만 배웁니다.
    //
    // (6-b) 그다음 FixConfig 에 컨테이너 팩토리를 만들어 고치세요.
    //       힌트: DefaultKafkaHeaderMapper 의 setRawMappedHeaders(Map<String, Boolean>)
    //             MessagingMessageConverter 의 setHeaderMapper(...)
    //             factory.setRecordMessageConverter(...)
    //       고친 뒤 FixedListener 가 traceId=trace-raw-1 처럼 찍혀야 정답입니다.
    //
    @Component
    @Profile("step05-ex6-broken | step05-ex6-fixed")
    public static class BrokenPublisher implements ApplicationRunner {

        private final KafkaTemplate<String, Object> template;

        public BrokenPublisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 2; seq++) {
                var record = new org.apache.kafka.clients.producer.ProducerRecord<String, Object>(
                        "orders", "ORD-%04d".formatted(seq), OrderCreated.of(seq));
                // 날 byte[] 로 넣습니다. 동반 타입 헤더(spring_json_header_types)가 없습니다.
                record.headers().add("traceId",
                        ("trace-raw-" + seq).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                template.send(record);
            }
            template.flush();
        }
    }

    @Component
    @Profile("step05-ex6-broken")
    public static class BrokenListener {

        private static final Logger log = LoggerFactory.getLogger(BrokenListener.class);

        @org.springframework.kafka.annotation.KafkaListener(
                topics = "orders", groupId = "s05-ex6-broken")
        public void onOrder(@Payload Object event,
                            @Headers Map<String, Object> headers,
                            @Header(name = "traceId", required = false) String traceId) {
            Object v = headers.get("traceId");
            log.info("mapType={} traceId={}", v == null ? "-" : v.getClass().getSimpleName(), traceId);
        }
    }

    @Configuration
    @Profile("step05-ex6-fixed")
    public static class FixConfig {

        // 여기에 작성: @Bean ConcurrentKafkaListenerContainerFactory<String, Object> exFixedFactory(...)
    }

    @Component
    @Profile("step05-ex6-fixed")
    public static class FixedListener {

        private static final Logger log = LoggerFactory.getLogger(FixedListener.class);

        // 여기에 작성: containerFactory = "exFixedFactory" 로 같은 메시지를 다시 읽어
        //             traceId 가 String 으로 찍히는지 확인하는 리스너
    }

    // ========================================================================
    // 문제 7. StringJsonMessageConverter 로 하나의 팩토리에서 두 타입 받기
    // ========================================================================
    //
    // 요구사항
    //   · @Bean ConsumerFactory<String, String> exStringConsumerFactory(KafkaProperties)
    //       - KafkaProperties.buildConsumerProperties() 로 시작해서
    //         key/value deserializer 를 StringDeserializer 로 내릴 것
    //       - ⚠️ 이걸 안 하면 무슨 일이 생기는지 주석으로 한 줄 적을 것 (본문 5-6 의 함정)
    //   · @Bean ConcurrentKafkaListenerContainerFactory<String, String> exJsonFactory(...)
    //       - setRecordMessageConverter(new StringJsonMessageConverter())
    //   · 이 팩토리를 문제 4 의 Q4 가 씁니다.
    //
    @Configuration
    @Profile("step05-ex")
    public static class Q7Config {

        // 여기에 작성:
        // @Bean
        // public ConsumerFactory<String, String> exStringConsumerFactory(KafkaProperties properties) { ... }
        //
        // @Bean
        // public ConcurrentKafkaListenerContainerFactory<String, String> exJsonFactory(
        //         ConsumerFactory<String, String> exStringConsumerFactory) { ... }
    }
}
