package com.example.order.step05;

/*
 * ============================================================================
 * Step 05 — 메시지 변환과 헤더 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step05/Practice.java
 *
 * 사전 준비
 *   spring-kafka-lab/src/main/java/com/example/order/domain/OrderCancelled.java 를 먼저 만드세요 (본문 5-0).
 *
 *       package com.example.order.domain;
 *       import java.time.Instant;
 *       public record OrderCancelled(String orderId, String reason, Instant cancelledAt) {
 *           public static OrderCancelled of(int seq) {
 *               return new OrderCancelled("ORD-%04d".formatted(seq),
 *                       seq % 2 == 0 ? "OUT_OF_STOCK" : "USER_REQUEST",
 *                       Instant.parse("2025-01-01T00:00:00Z").plusSeconds(seq * 60L));
 *           }
 *       }
 *
 * 실행 (기본 — 정상 경로만 돕니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step05'
 *
 * 보조 프로필 (함정 재현용. 기본 프로필과 함께 켭니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step05,step05-raw'
 *       → [5-9] ProducerRecord.headers().add(byte[]) 방식으로 발행. @Headers Map 에 byte[] 가 들어옵니다.
 *   ./gradlew bootRun --args='--spring.profiles.active=step05,step05-legacy'
 *       → [5-3] 2.x 문자열 헤더 이름("kafka_receivedMessageKey")을 쓰면 key 가 영원히 null.
 *   ./gradlew bootRun --args='--spring.profiles.active=step05,step05-required'
 *       → [5-4] required=true 인 @Header 가 없을 때 MessageHandlingException 을 봅니다.
 *   ./gradlew bootRun --args='--spring.profiles.active=step05,step05-conv'
 *       → [5-6][5-7] StringJsonMessageConverter + @KafkaHandler 타입 분기.
 *   ./gradlew bootRun --args='--spring.profiles.active=step05,step05-raw,step05-fixed'
 *       → [5-9] setRawMappedHeaders 로 고친 팩토리로 같은 메시지를 다시 읽습니다.
 *
 * 로그 패턴 (traceId 를 보려면 application.yml 에 추가)
 *   logging:
 *     pattern:
 *       console: "%clr(%5p) %clr(${PID:- }){magenta} --- [%15.15t] %clr([%X{traceId:-        }]){yellow} %-40.40logger{39} : %m%n"
 *
 * 확인할 CLI
 *   kcc --topic orders --from-beginning --property print.key=true --property print.headers=true
 *   kcg --describe --group s05-inventory
 *   kcg --group s05-inventory --topic orders --reset-offsets --to-earliest --execute   (앱 종료 후)
 *   docker exec -it learn-kafka du -sh /var/lib/kafka/data/orders-0
 * ============================================================================
 */

import com.example.order.domain.OrderCancelled;
import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Step 05 의 모든 예제를 담은 단일 파일입니다.
 * 각 nested static class 는 본문 절 번호와 1:1 로 대응합니다.
 *
 * 주의: org.apache.kafka.common.header.Header 와
 *       org.springframework.messaging.handler.annotation.Header 는 이름이 같습니다.
 *       이 파일은 전자를 import 하고, 후자는 완전한 이름으로 씁니다.
 */
public final class Practice {

    private Practice() {
        // 유틸리티 홀더. 인스턴스화하지 않습니다.
    }

    // ========================================================================
    // [5-0] 공통 — 결정적인 traceId 생성기
    // ========================================================================
    //
    // 실행할 때마다 값이 바뀌면 교재의 로그와 대조할 수 없습니다.
    // seq 로부터 항상 같은 8자리 hex 를 만듭니다. (ORD-0001 → 3f7a1c8e)
    //
    static final class TraceIds {
        private TraceIds() {
        }

        static String of(int seq) {
            long h = UUID.nameUUIDFromBytes(("ORD-%04d".formatted(seq)).getBytes(StandardCharsets.UTF_8))
                    .getMostSignificantBits();
            return "%08x".formatted((int) (h >>> 32));
        }
    }

    // ========================================================================
    // [5-8] 프로듀서 (기본) — MessageBuilder 로 traceId 를 붙여 발행한다
    // ========================================================================
    //
    // MessageBuilder 로 만든 Message<?> 를 send 하면 KafkaTemplate 의
    // MessagingMessageConverter → DefaultKafkaHeaderMapper 를 탑니다.
    // 그 결과 traceId 가 UTF-8 바이트로 실리고, 동반 헤더
    // spring_json_header_types = {"traceId":"java.lang.String"} 가 함께 붙습니다.
    // 이 동반 헤더 덕분에 컨슈머가 byte[] 가 아니라 String 으로 복원합니다. ([5-9] 참고)
    //
    @Component
    @Profile("step05 & !step05-raw")
    public static class Publisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Publisher.class);

        private final KafkaTemplate<String, Object> template;

        public Publisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 4; seq++) {
                String orderId = "ORD-%04d".formatted(seq);
                String traceId = TraceIds.of(seq);

                Object payload = (seq == 2) ? OrderCancelled.of(seq) : OrderCreated.of(seq);

                // seq == 3 은 일부러 traceId 를 붙이지 않습니다. ([5-4] 옵셔널 헤더 확인용)
                var builder = MessageBuilder
                        .withPayload(payload)
                        .setHeader(KafkaHeaders.TOPIC, "orders")
                        .setHeader(KafkaHeaders.KEY, orderId);
                if (seq != 3) {
                    builder.setHeader("traceId", traceId);
                }

                Message<?> message = builder.build();
                template.send(message);
                log.info("발행 {} traceId={}", orderId, seq != 3 ? traceId : "(없음)");
            }
            template.flush();
        }
    }

    // ========================================================================
    // [5-9] 프로듀서 (raw) — ProducerRecord 에 byte[] 헤더를 직접 붙인다
    // ========================================================================
    //
    // 이쪽 경로는 KafkaTemplate 의 메시지 컨버터를 타지 않습니다.
    // 따라서 spring_json_header_types 동반 헤더가 없고, 컨슈머는 타입을 모릅니다.
    // 다른 언어(Go/Python) 프로듀서가 붙었을 때와 동일한 상황입니다.
    //
    @Component
    @Profile("step05-raw")
    public static class RawPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(RawPublisher.class);

        private final KafkaTemplate<String, Object> template;

        public RawPublisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 4; seq++) {
                String orderId = "ORD-%04d".formatted(seq);
                Object payload = (seq == 2) ? OrderCancelled.of(seq) : OrderCreated.of(seq);

                var record = new ProducerRecord<String, Object>("orders", orderId, payload);
                record.headers().add("traceId",
                        TraceIds.of(seq).getBytes(StandardCharsets.UTF_8));   // ← 날 byte[]
                record.headers().add("x-source", "raw-publisher".getBytes(StandardCharsets.UTF_8));

                template.send(record);
                log.info("raw 발행 {} (동반 타입 헤더 없음)", orderId);
            }
            template.flush();
        }
    }

    // ========================================================================
    // [5-1] 리스너가 받을 수 있는 파라미터 전부
    // ========================================================================
    //
    // 파라미터가 2개 이상이므로 페이로드에 @Payload 를 명시합니다. ([5-2] 의 규칙)
    // 파라미터를 많이 받는다고 브로커에서 더 가져오지 않습니다. 헤더는 이미
    // MessageHeaders 맵으로 만들어진 뒤 리플렉션으로 꺼내 쓰는 것뿐입니다.
    //
    @Component
    @Profile("step05")
    public static class AllParams {

        private static final Logger log = LoggerFactory.getLogger(AllParams.class);

        @KafkaListener(topics = "orders", groupId = "s05-allparams")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_KEY) String key,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.OFFSET) long offset,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.GROUP_ID) String groupId) {

            log.info("topic={} p={} off={} key={} ts={} group={} payload={}",
                    topic, partition, offset, key, timestamp, groupId,
                    event.getClass().getSimpleName());
        }
    }

    // ========================================================================
    // [5-3] 함정 — 2.x 문자열 헤더 이름을 그대로 복사한 경우
    // ========================================================================
    //
    // 3.x 는 kafka_receivedKey 라는 이름으로 헤더를 심습니다.
    // kafka_receivedMessageKey 라는 헤더는 존재하지 않으므로:
    //   · required = true  → MessageHandlingException: Missing header '...'
    //   · required = false → 영원히 null. 예외도 경고도 없습니다.
    // 상수(KafkaHeaders.RECEIVED_KEY)만 썼다면 컴파일 에러로 즉시 잡혔을 문제입니다.
    //
    // 마이그레이션 점검:  grep -rn '"kafka_' src/
    //
    @Component
    @Profile("step05-legacy")
    public static class LegacyHeader {

        private static final Logger log = LoggerFactory.getLogger(LegacyHeader.class);

        @KafkaListener(topics = "orders", groupId = "s05-legacy")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "kafka_receivedMessageKey", required = false) String key,   // ← 2.x 이름
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.OFFSET) long offset) {

            log.info("key={} off={} payload={}", key, offset, event.getClass().getSimpleName());
        }
    }

    // ========================================================================
    // [5-4] required = true (기본값) — 헤더가 없으면 예외
    // ========================================================================
    //
    // seq == 3 메시지에는 traceId 가 없습니다. 그 메시지에서만 터집니다.
    // 재시도해도 헤더가 생기지 않으므로 백오프는 무의미하고,
    // DefaultErrorHandler 가 재시도를 소진한 뒤 skip 하면
    // "처리되지 않았는데 오프셋만 전진" 하는 조용한 유실이 됩니다.
    //
    @Component
    @Profile("step05-required")
    public static class RequiredHeader {

        private static final Logger log = LoggerFactory.getLogger(RequiredHeader.class);

        @KafkaListener(topics = "orders", groupId = "s05-required")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header("traceId") String traceId) {

            log.info("traceId={} payload={}", traceId, event.getClass().getSimpleName());
        }
    }

    // ========================================================================
    // [5-4] required = false / defaultValue — 옵셔널 헤더의 올바른 선언
    // ========================================================================
    //
    // ⚠️ retryCount 를 int 로 선언하고 required=false 를 붙이면,
    //    헤더가 없을 때 null 이 전달되어 리플렉션 호출에서
    //    IllegalArgumentException: argument type mismatch 가 납니다.
    //    래퍼 타입(Integer) 또는 defaultValue 를 반드시 함께 쓰세요.
    //
    @Component
    @Profile("step05")
    public static class OptionalHeader {

        private static final Logger log = LoggerFactory.getLogger(OptionalHeader.class);

        @KafkaListener(topics = "orders", groupId = "s05-optional")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String traceId,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "retryCount", defaultValue = "0") Integer retryCount,
                @org.springframework.messaging.handler.annotation.Header(
                        name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {

            log.info("traceId={} retry={} key={}", traceId == null ? "-" : traceId, retryCount, key);
        }
    }

    // ========================================================================
    // [5-5] ConsumerRecord 를 직접 받기 — 메타데이터 전부
    // ========================================================================
    //
    // ConsumerRecord 로만 얻을 수 있는 것:
    //   · serializedKeySize / serializedValueSize   (크기 감시)
    //   · timestampType                             (CreateTime vs LogAppendTime)
    //   · headers() 순회                            (Kafka 헤더는 멀티맵. @Headers Map 은 중복을 잃는다)
    //
    // 로깅·감사·재처리 도구에 쓰세요. 비즈니스 리스너에는 쓰지 마세요.
    // 도메인 코드에 org.apache.kafka 임포트가 스며듭니다.
    //
    @Component
    @Profile("step05")
    public static class Audit {

        private static final Logger log = LoggerFactory.getLogger(Audit.class);

        @KafkaListener(topics = "orders", groupId = "s05-audit")
        public void audit(ConsumerRecord<String, Object> record) {
            StringBuilder headers = new StringBuilder();
            for (Header h : record.headers()) {
                headers.append(h.key()).append('=')
                        .append(new String(h.value(), StandardCharsets.UTF_8))
                        .append(' ');
            }
            log.info("{}-{}@{} key={} ts={}({}) keySize={} valSize={} headers=[{}]",
                    record.topic(), record.partition(), record.offset(),
                    record.key(), record.timestamp(), record.timestampType(),
                    record.serializedKeySize(), record.serializedValueSize(),
                    headers.toString().trim());
        }
    }

    // ========================================================================
    // [5-6] MessageConverter 방식 — Serializer 를 String 으로 내리고 컨버터가 변환
    // ========================================================================
    //
    // ⚠️ 가장 흔한 실수: value-deserializer 를 JsonDeserializer 로 둔 채
    //    StringJsonMessageConverter 만 얹는 것. ①에서 이미 객체가 되어 있으므로
    //    ②는 "변환할 게 없네" 하고 통과합니다. 예외도 로그도 없습니다.
    //    그래서 [5-7] 의 @KafkaHandler 분기가 안 되는데 원인을 못 찾습니다.
    //
    // 그래서 여기서는 이 팩토리 전용 ConsumerFactory 를 새로 만들고,
    // key/value deserializer 를 StringDeserializer 로 명시적으로 내립니다.
    // 기본 팩토리(application.yml)는 건드리지 않습니다.
    //
    @Configuration
    @Profile("step05-conv")
    public static class ConverterConfig {

        @Bean
        public ConsumerFactory<String, String> stringConsumerFactory(KafkaProperties properties) {
            Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            // ErrorHandlingDeserializer 의 위임 설정은 여기선 불필요하므로 제거합니다.
            props.remove("spring.deserializer.value.delegate.class");
            props.remove("spring.deserializer.key.delegate.class");
            props.remove("spring.json.value.default.type");
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, String> jsonConverterFactory(
                ConsumerFactory<String, String> stringConsumerFactory) {

            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(stringConsumerFactory);
            // ② Spring Messaging 레이어에서 JSON → 객체. 타입은 메서드 시그니처가 정한다.
            factory.setRecordMessageConverter(new StringJsonMessageConverter());
            factory.setConcurrency(3);
            return factory;
        }
    }

    @Component
    @Profile("step05-conv")
    public static class ConverterListener {

        private static final Logger log = LoggerFactory.getLogger(ConverterListener.class);

        // 파라미터 타입이 곧 변환 대상 타입입니다. JsonDeserializer 설정을 바꾸지 않아도
        // 같은 팩토리로 다른 리스너가 다른 타입을 받을 수 있습니다.
        @KafkaListener(topics = "orders", groupId = "s05-conv",
                containerFactory = "jsonConverterFactory")
        public void onOrder(OrderCreated event) {
            log.info("converted -> {} sku={}", event.orderId(), event.sku());
        }
    }

    // ========================================================================
    // [5-7] @KafkaHandler — 한 토픽에 여러 이벤트 타입이 섞여 올 때
    // ========================================================================
    //
    // @KafkaListener 를 "클래스"에 붙이고, 타입별 메서드에 @KafkaHandler 를 붙입니다.
    // instanceof 분기보다 낫습니다: 타입이 늘어도 기존 메서드를 건드리지 않습니다.
    //
    // ⚠️ isDefault=true 핸들러는 "모르는 타입 전부"를 받습니다.
    //    거기서 아무것도 안 하면 메시지가 조용히 사라집니다.
    //    반드시 WARN 이상으로 로그를 남기거나 DLT 로 보내세요.
    //    반대로 isDefault 핸들러가 아예 없으면
    //    KafkaException: No method found for class ... 로 시끄럽게 실패합니다.
    //
    @Component
    @Profile("step05-conv")
    @KafkaListener(topics = "orders", groupId = "s05-multi",
            containerFactory = "jsonConverterFactory")
    public static class MultiTypeListener {

        private static final Logger log = LoggerFactory.getLogger(MultiTypeListener.class);

        @KafkaHandler
        public void onCreated(OrderCreated event) {
            log.info("[created]   {} sku={}", event.orderId(), event.sku());
        }

        @KafkaHandler
        public void onCancelled(OrderCancelled event) {
            log.info("[cancelled] {} reason={}", event.orderId(), event.reason());
        }

        @KafkaHandler(isDefault = true)
        public void onUnknown(Object payload,
                              @org.springframework.messaging.handler.annotation.Header(
                                      name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
            log.warn("[unknown]   key={} type={} value={}", key, payload.getClass().getName(), payload);
        }
    }

    // ========================================================================
    // [5-8] traceId → MDC → 로그 패턴 %X{traceId}
    // ========================================================================
    //
    // ⚠️ finally 의 MDC.remove 를 빼면 어떻게 되는지 꼭 한 번 지워서 실행해 보세요.
    //    리스너 컨테이너 스레드(ntainer#0-1-C-1)는 재사용되고 MDC 는 ThreadLocal 입니다.
    //    traceId 가 없는 메시지가 오면 MDC.put 이 호출되지 않아
    //    직전 메시지의 traceId 가 그대로 남습니다.
    //    로그는 완벽해 보이는데 서로 다른 요청이 한 트레이스로 뭉칩니다.
    //
    @Component
    @Profile("step05")
    public static class Inventory {

        private static final Logger log = LoggerFactory.getLogger(Inventory.class);

        @KafkaListener(topics = "orders", groupId = "s05-inventory")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String traceId,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.OFFSET) long offset) {

            MDC.put("traceId", traceId != null ? traceId : "-");
            try {
                if (event instanceof OrderCreated created) {
                    log.info("재고 차감 시작 {} qty={} ({}@{})",
                            created.orderId(), created.quantity(), partition, offset);
                    log.info("재고 차감 완료 {}", created.orderId());
                } else {
                    log.info("재고 대상 아님 {} ({}@{})", event.getClass().getSimpleName(), partition, offset);
                }
            } finally {
                MDC.remove("traceId");   // ← 반드시 finally
            }
        }
    }

    // ========================================================================
    // [5-8] 재발행 시 헤더 이어받기
    // ========================================================================
    //
    // ⚠️ in.headers() 를 통째로 복사하면 __TypeId__ 와 spring_json_header_types 까지 따라갑니다.
    //    payments 토픽의 값 타입이 다르면 컨슈머가 옛 타입으로 역직렬화하려다 실패합니다.
    //    traceId / x- 접두사 / correlationId 정도만 화이트리스트로 이어받으세요.
    //    byte[] 를 그대로 복사하므로 인코딩 변환이 전혀 필요 없습니다.
    //
    @Component
    @Profile("step05")
    public static class Relay {

        private static final Logger log = LoggerFactory.getLogger(Relay.class);

        private final KafkaTemplate<String, Object> template;

        public Relay(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @KafkaListener(topics = "orders", groupId = "s05-payment")
        public void relay(ConsumerRecord<String, Object> in) {
            var out = new ProducerRecord<String, Object>("payments", in.key(), in.value());
            for (Header h : in.headers()) {
                if ("traceId".equals(h.key()) || "correlationId".equals(h.key()) || h.key().startsWith("x-")) {
                    out.headers().add(h);
                }
            }
            template.send(out);

            Header t = in.headers().lastHeader("traceId");
            MDC.put("traceId", t == null ? "-" : new String(t.value(), StandardCharsets.UTF_8));
            try {
                log.info("payments 로 중계 {}", in.key());
            } finally {
                MDC.remove("traceId");
            }
        }
    }

    // ========================================================================
    // [5-9] 함정 — 헤더 값은 byte[] 다
    // ========================================================================
    //
    // step05-raw 프로필과 함께 켜면 아래가 찍힙니다.
    //   mapValue=[B@6f2c0754 mapType=byte[] asString=[B@6f2c0754
    //
    // 기본 프로필(MessageBuilder 발행)이면 이렇게 찍힙니다.
    //   mapValue=3f7a1c8e mapType=String asString=3f7a1c8e
    //
    // 차이는 spring_json_header_types 동반 헤더의 유무입니다.
    // @Header String 이 [B@... 로 찍히는 이유:
    //   DefaultFormattingConversionService 에 byte[] → String 전용 변환기가 없어서
    //   최후 수단인 Object → String (즉 toString()) 으로 떨어집니다. 예외가 안 납니다.
    //   그 값은 JVM 재시작마다 달라지므로 트레이스로 아무 쓸모가 없습니다.
    //
    @Component
    @Profile("step05")
    public static class RawHeaders {

        private static final Logger log = LoggerFactory.getLogger(RawHeaders.class);

        @KafkaListener(topics = "orders", groupId = "s05-headers")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Headers Map<String, Object> headers,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String asString,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) byte[] asBytes) {

            Object v = headers.get("traceId");
            log.info("mapValue={} mapType={} asString={} asBytes={}",
                    v, v == null ? "-" : v.getClass().getSimpleName(), asString,
                    asBytes == null ? "-" : new String(asBytes, StandardCharsets.UTF_8));
        }
    }

    // ========================================================================
    // [5-9] 해결 — DefaultKafkaHeaderMapper.setRawMappedHeaders
    // ========================================================================
    //
    // Map<String, Boolean> 의 value 가 true 면 "인바운드에서 UTF-8 String 으로 변환" 입니다.
    // false 로 두면 byte[] 그대로입니다.
    // 동반 타입 헤더가 없는 외부 프로듀서(다른 언어)를 상대할 때의 정석 해법입니다.
    //
    @Configuration
    @Profile("step05-fixed")
    public static class RawHeaderMapperConfig {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> rawHeaderFactory(
                ConsumerFactory<String, Object> consumerFactory) {

            var mapper = new DefaultKafkaHeaderMapper();
            mapper.setRawMappedHeaders(Map.of(
                    "traceId", true,
                    "x-source", true));

            var converter = new MessagingMessageConverter();
            converter.setHeaderMapper(mapper);

            var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
            factory.setConsumerFactory(consumerFactory);
            factory.setRecordMessageConverter(converter);
            factory.setConcurrency(3);
            return factory;
        }
    }

    @Component
    @Profile("step05-fixed")
    public static class FixedRawHeaders {

        private static final Logger log = LoggerFactory.getLogger(FixedRawHeaders.class);

        @KafkaListener(topics = "orders", groupId = "s05-headers-fixed",
                containerFactory = "rawHeaderFactory")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Headers Map<String, Object> headers,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String traceId) {

            Object v = headers.get("traceId");
            log.info("[fixed] mapValue={} mapType={} asString={}",
                    v, v == null ? "-" : v.getClass().getSimpleName(), traceId);
        }
    }

    // ========================================================================
    // [5-10] 헤더 비용 실측 — 레코드 하나의 실제 크기
    // ========================================================================
    //
    // 헤더는 매 레코드마다 반복 전송됩니다. 이름까지 매번 실립니다.
    // 헤더 하나의 비용 ≈ varint(키 길이) + 키 바이트 + varint(값 길이) + 값 바이트
    //
    // 아래 계산은 varint 를 무시한 하한값이지만, 표의 209 B / 431 B 를 재현하기에 충분합니다.
    // 줄이는 법: spring.json.add.type.headers: false 로 __TypeId__(약 47 B)를 없앤다.
    //
    @Component
    @Profile("step05")
    public static class SizeProbe {

        private static final Logger log = LoggerFactory.getLogger(SizeProbe.class);

        @KafkaListener(topics = "orders", groupId = "s05-size")
        public void probe(ConsumerRecord<String, Object> record) {
            int headerBytes = 0;
            var breakdown = new LinkedHashMap<String, Integer>();
            for (Header h : record.headers()) {
                int size = h.key().getBytes(StandardCharsets.UTF_8).length
                        + (h.value() == null ? 0 : h.value().length);
                headerBytes += size;
                breakdown.put(h.key(), size);
            }
            int keySize = Math.max(record.serializedKeySize(), 0);
            int valSize = Math.max(record.serializedValueSize(), 0);

            log.info("{}@{} key={}B value={}B headers={}B({}개) total≈{}B {}",
                    record.partition(), record.offset(), keySize, valSize,
                    headerBytes, breakdown.size(), keySize + valSize + headerBytes, breakdown);
        }
    }
}
