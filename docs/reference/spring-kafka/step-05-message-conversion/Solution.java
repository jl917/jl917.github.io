package com.example.order.step05;

/*
 * ============================================================================
 * Step 05 — 메시지 변환과 헤더 : Solution (7문제 정답 + 해설)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step05/Solution.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step05-sol'
 *   ./gradlew bootRun --args='--spring.profiles.active=step05-sol,step05-sol6'
 *
 * Exercise.java 와 컨슈머 그룹이 겹치지 않도록 s05-sol* 접두사를 씁니다.
 * 문제를 직접 풀어 본 "뒤에" 여세요.
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
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class Solution {

    private Solution() {
    }

    // ========================================================================
    // 공통 픽스처 (Exercise 와 동일)
    // ========================================================================
    @Component
    @Profile("step05-sol")
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

                var builder = MessageBuilder.withPayload(payload)
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
    // 정답 1 — 메타데이터를 @Header 만으로 전부 받기
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     * 1) 상수를 씁니다. RECEIVED_PARTITION / RECEIVED_KEY 는 Spring Kafka 3.0 에서
     *    RECEIVED_PARTITION_ID / RECEIVED_MESSAGE_KEY 로부터 이름이 바뀐 것들입니다.
     *    상수를 쓰면 2.x 예제를 복사했을 때 "컴파일 에러"로 즉시 잡힙니다.
     *    문자열("kafka_receivedPartitionId")을 쓰면 3.x 에는 그런 헤더가 없으므로
     *    required=true 면 런타임 MessageHandlingException, required=false 면 "영원히 null" 입니다.
     *    후자는 예외도 경고도 없어서 가장 오래 걸립니다.
     *
     * 2) @Payload 를 굳이 붙였습니다. 파라미터가 6개이기 때문입니다.
     *    PayloadMethodArgumentResolver 는 "앞의 리졸버가 못 가져간 파라미터 전부"를 페이로드로
     *    간주합니다(useDefaultResolution=true). 지금은 나머지가 전부 @Header 라 문제가 없지만,
     *    나중에 누가 애노테이션 없는 파라미터를 하나 더 넣는 순간 "페이로드가 둘"이 되어
     *    MessageConversionException 이 납니다. @Payload 명시는 그 사고에 대한 보험입니다.
     *
     * 3) partition 은 int, offset/timestamp 는 long 입니다. 이 세 헤더는 항상 존재하므로
     *    원시 타입으로 받아도 안전합니다. (반면 traceId 나 RECEIVED_KEY 는 없을 수 있습니다 → 정답 5)
     */
    @Component
    @Profile("step05-sol")
    public static class S1 {

        private static final Logger log = LoggerFactory.getLogger(S1.class);

        @KafkaListener(topics = "orders", groupId = "s05-sol1")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.OFFSET) long offset,
                @org.springframework.messaging.handler.annotation.Header(
                        name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_TIMESTAMP) long ts) {

            log.info("topic={} p={} off={} key={} ts={} payload={}",
                    topic, partition, offset, key, ts, event.getClass().getSimpleName());
        }
    }

    // ========================================================================
    // 정답 2 — traceId → MDC → %X{traceId}
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     * 핵심은 MessageBuilder 쪽이 아니라 finally 입니다.
     *
     * 리스너 컨테이너의 컨슈머 스레드(ntainer#0-1-C-1)는 메시지마다 새로 만들어지지 않고
     * 계속 재사용됩니다. MDC 는 ThreadLocal 입니다. 두 사실을 곱하면 이렇게 됩니다:
     *
     *   ORD-0002 처리 → MDC.put("traceId", "trace-0002")  → 로그
     *   ORD-0003 처리 → traceId 헤더가 없다 → MDC 를 건드리지 않음
     *                 → 로그에 trace-0002 가 그대로 찍힌다   ← 오염
     *
     * 예외도 경고도 없고, 로그는 오히려 "완벽해 보입니다". 그래서 장애 분석 때
     * 서로 다른 요청이 한 트레이스로 뭉쳐 있는 것을 발견하고서야 알게 됩니다.
     *
     * 대책은 둘 중 하나입니다.
     *   (a) traceId 가 없어도 항상 MDC.put 으로 덮어쓴다 (아래 정답이 이 방식, "-" 를 넣음)
     *   (b) finally 에서 MDC.remove 로 반드시 지운다      (아래 정답이 함께 씀)
     * 둘 다 하는 것이 가장 안전합니다. 리스너가 예외로 빠져나가도 (b)가 지켜 줍니다.
     *
     * 확장: 리스너마다 이 코드를 복붙하는 대신 RecordInterceptor 로 올리면
     * intercept() 에서 put, success()/failure() 에서 remove 를 한 곳에 모을 수 있습니다.
     * (RecordInterceptor 는 Step 10 에서 다룹니다.)
     */
    @Component
    @Profile("step05-sol")
    public static class S2 {

        private static final Logger log = LoggerFactory.getLogger(S2.class);

        @KafkaListener(topics = "orders", groupId = "s05-sol2")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String traceId,
                @org.springframework.messaging.handler.annotation.Header(
                        name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {

            MDC.put("traceId", traceId != null ? traceId : "-");   // (a) 항상 덮어쓴다
            try {
                log.info("처리 시작 {}", key);
                log.info("처리 완료 {}", key);
            } finally {
                MDC.remove("traceId");                              // (b) 항상 지운다
            }
        }
    }

    // ========================================================================
    // 정답 3 — ConsumerRecord.headers() 순회로 전체 덤프
    // ========================================================================
    /*
     * 왜 @Headers Map 이 아니라 ConsumerRecord 인가
     *
     * Kafka 헤더는 Map 이 아니라 "멀티맵" 입니다. 같은 이름의 헤더를 여러 번 넣을 수 있고,
     * 실제로 그렇게 쓰는 컴포넌트가 있습니다(@RetryableTopic 의 재시도 이력, Step 08).
     * @Headers Map<String,Object> 로 받으면 같은 이름의 헤더 중 하나만 남습니다.
     * "덤프" 가 목적이라면 잃어버린 헤더가 있다는 사실조차 모르게 됩니다.
     *
     * 그리고 값 디코딩은 우리가 합니다. Kafka 헤더 값의 타입은 byte[] 하나뿐입니다.
     * new String(h.value(), UTF_8) 이 없으면 [B@1a2b3c 가 찍힙니다(본문 5-9).
     * h.value() 는 null 일 수 있으므로(값 없는 헤더는 합법입니다) null 검사도 필요합니다.
     *
     * 부수적으로 serializedValueSize 도 함께 찍었습니다. 헤더 비용을 눈으로 보라는 뜻입니다
     * (본문 5-10: 헤더 3개면 저장량이 2배가 됩니다).
     */
    @Component
    @Profile("step05-sol")
    public static class S3 {

        private static final Logger log = LoggerFactory.getLogger(S3.class);

        @KafkaListener(topics = "orders", groupId = "s05-sol3")
        public void dump(ConsumerRecord<String, Object> record) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Header h : record.headers()) {
                String value = (h.value() == null) ? "(null)" : new String(h.value(), StandardCharsets.UTF_8);
                joiner.add(h.key() + "=" + value);
            }
            log.info("{} valSize={}B headers={}", record.key(), record.serializedValueSize(), joiner);
        }
    }

    // ========================================================================
    // 정답 4 — 클래스 레벨 @KafkaListener + @KafkaHandler
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     * 1) @KafkaListener 를 클래스에 붙이고 타입별 메서드에 @KafkaHandler 를 붙입니다.
     *    if (payload instanceof ...) 사슬보다 나은 이유는 확장 방향입니다.
     *    새 이벤트 타입이 생기면 메서드를 하나 추가할 뿐, 기존 메서드를 건드리지 않습니다.
     *
     * 2) containerFactory 는 정답 7 의 exJsonFactory(StringJsonMessageConverter) 입니다.
     *    타입 분기가 성립하려면 "타입이 파라미터 시그니처로 결정" 되어야 하는데,
     *    그건 MessageConverter 방식에서만 그렇습니다.
     *    value-deserializer 가 JsonDeserializer 인 채로 두면 ①단계에서 이미 타입이 고정되어
     *    분기가 의도대로 동작하지 않습니다(본문 5-6 의 함정).
     *
     * 3) isDefault=true 핸들러에서 반드시 로그를 남깁니다.
     *    이 핸들러가 없으면 KafkaException: No method found for class ... 로 시끄럽게 실패합니다.
     *    있는데 아무것도 안 하면 모르는 타입이 전부 조용히 사라집니다.
     *    "시끄러운 실패" 를 "조용한 유실" 로 바꾸는 것이 최악이므로, 기본 핸들러는
     *    WARN 이상 로그 + (실무라면) DLT 발행까지 하는 것이 원칙입니다.
     *    __TypeId__ 가 없는 레코드는 컨버터가 타입을 못 정해 LinkedHashMap 으로 넘어옵니다.
     */
    @Component
    @Profile("step05-sol")
    @KafkaListener(topics = "orders", groupId = "s05-sol4", containerFactory = "solJsonFactory")
    public static class S4 {

        private static final Logger log = LoggerFactory.getLogger(S4.class);

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
            // 조용히 삼키면 안 됩니다. 여기 들어온 메시지는 아무도 처리하지 않았는데
            // 오프셋만 전진합니다 — 에러 없는 유실입니다.
            log.warn("[unknown]   key={} type={} value={}", key, payload.getClass().getName(), payload);
        }
    }

    // ========================================================================
    // 정답 5 — 옵셔널 헤더는 래퍼 타입 + defaultValue
    // ========================================================================
    /*
     * 왜 Integer 이고 왜 defaultValue 인가
     *
     * @Header(name = "retryCount", required = false) int retryCount   ← 이렇게 쓰면 안 됩니다.
     *
     * 헤더가 없을 때 HeaderMethodArgumentResolver 는 null 을 돌려줍니다.
     * 그 null 이 int 파라미터로 들어가는 순간, 리플렉션 호출(InvocableHandlerMethod.doInvoke →
     * Method.invoke) 에서 이렇게 터집니다.
     *
     *   Caused by: java.lang.IllegalArgumentException: argument type mismatch
     *       at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(...)
     *       at org.springframework.messaging.handler.invocation.InvocableHandlerMethod.doInvoke(...)
     *
     * 메시지에 "retryCount" 도 "header" 도 없어서 원인을 찾기가 아주 어렵습니다.
     * 그래서 옵셔널 헤더는 (a) 래퍼 타입 Integer/Long 으로 받거나 (b) defaultValue 를 주거나,
     * 둘 다 하는 것이 안전합니다. 아래 정답은 둘 다 했습니다.
     *
     * defaultValue 는 문자열 "0" 입니다. 리졸버가 ConversionService 로 Integer 로 변환합니다.
     * 헤더 값도 문자열 "2" 로 들어오지만 같은 경로로 Integer 2 가 됩니다.
     *
     * traceId 는 String 이라 null 이 들어가도 예외가 안 나므로 required=false 만으로 충분합니다.
     * 다만 로그에서 null 을 "-" 로 바꿔 주는 편이 읽기 좋습니다.
     */
    @Component
    @Profile("step05-sol")
    public static class S5 {

        private static final Logger log = LoggerFactory.getLogger(S5.class);

        @KafkaListener(topics = "orders", groupId = "s05-sol5")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Header(
                        name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String traceId,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "retryCount", defaultValue = "0") Integer retryCount) {

            log.info("{} traceId={} retry={}", key, traceId == null ? "-" : traceId, retryCount);
        }
    }

    // ========================================================================
    // 정답 6 — setRawMappedHeaders 로 byte[] 헤더 고치기
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     * 증상부터 정리합니다. 프로듀서가 ProducerRecord.headers().add("traceId", bytes) 로
     * 날 byte[] 를 넣으면, Spring 의 DefaultKafkaHeaderMapper 가 아웃바운드에서 붙여 주는
     * 동반 헤더 spring_json_header_types = {"traceId":"java.lang.String"} 가 없습니다.
     * 인바운드 매퍼는 타입 힌트가 없으니 그 헤더를 byte[] 그대로 MessageHeaders 에 넣습니다.
     *
     *   · @Headers Map 으로 받으면      → 값의 실제 타입이 byte[]
     *   · @Header String 으로 받으면    → byte[] → String 전용 변환기가 없어
     *                                     최후 수단인 Object→String(toString())으로 떨어져
     *                                     "[B@6f2c0754" 가 들어옵니다. 예외가 안 납니다.
     *
     * 이 값은 JVM 재시작마다 달라지므로 로그 검색에 아무 쓸모가 없습니다.
     * 다른 언어(Go/Python) 프로듀서가 붙는 순간 반드시 겪게 됩니다.
     *
     * 해결은 인바운드 매퍼에게 "이 헤더는 String 으로 읽어라" 라고 알려 주는 것입니다.
     *
     *   mapper.setRawMappedHeaders(Map.of("traceId", true, "x-source", true));
     *
     * Map 의 value 가 Boolean 이라는 점이 헷갈리기 쉽습니다.
     *   true  = 인바운드에서 UTF-8 String 으로 변환해서 넘긴다
     *   false = byte[] 그대로 넘긴다 (동반 타입 헤더도 만들지 않는다)
     * 즉 true 가 우리가 원하는 값입니다.
     *
     * 매퍼는 컨버터에 물리고, 컨버터는 컨테이너 팩토리에 물립니다.
     *   DefaultKafkaHeaderMapper → MessagingMessageConverter → factory.setRecordMessageConverter
     * MessagingMessageConverter 는 페이로드를 변환하지 않고 그대로 통과시키므로,
     * value-deserializer 설정(JsonDeserializer)은 그대로 두어도 됩니다.
     * 이 점이 정답 7 의 StringJsonMessageConverter 와 다릅니다.
     *
     * 대안 두 가지도 알아 두세요.
     *   · @Header(name="traceId", required=false) byte[] 로 받아 직접 new String(..., UTF_8)
     *     → 가장 정직하고 오해가 없습니다. 리스너가 몇 개 없으면 이쪽이 낫습니다.
     *   · 프로듀서를 MessageBuilder.setHeader 방식으로 통일
     *     → 우리 팀 코드만 있을 때는 제일 깔끔하지만, 외부 프로듀서는 통제할 수 없습니다.
     */
    @Component
    @Profile("step05-sol6")
    public static class S6Publisher implements ApplicationRunner {

        private final KafkaTemplate<String, Object> template;

        public S6Publisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 2; seq++) {
                var record = new ProducerRecord<String, Object>(
                        "orders", "ORD-%04d".formatted(seq), OrderCreated.of(seq));
                record.headers().add("traceId",
                        ("trace-raw-" + seq).getBytes(StandardCharsets.UTF_8));
                template.send(record);
            }
            template.flush();
        }
    }

    @Configuration
    @Profile("step05-sol6")
    public static class S6Config {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> solRawHeaderFactory(
                ConsumerFactory<String, Object> consumerFactory) {

            var mapper = new DefaultKafkaHeaderMapper();
            mapper.setRawMappedHeaders(Map.of(
                    "traceId", true,      // true = 인바운드에서 UTF-8 String 으로 변환
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
    @Profile("step05-sol6")
    public static class S6Listener {

        private static final Logger log = LoggerFactory.getLogger(S6Listener.class);

        @KafkaListener(topics = "orders", groupId = "s05-sol6",
                containerFactory = "solRawHeaderFactory")
        public void onOrder(
                @org.springframework.messaging.handler.annotation.Payload Object event,
                @org.springframework.messaging.handler.annotation.Headers Map<String, Object> headers,
                @org.springframework.messaging.handler.annotation.Header(
                        name = "traceId", required = false) String traceId) {

            Object v = headers.get("traceId");
            log.info("[fixed] mapType={} traceId={}",
                    v == null ? "-" : v.getClass().getSimpleName(), traceId);
        }
    }

    // ========================================================================
    // 정답 7 — StringJsonMessageConverter 를 쓰는 컨테이너 팩토리
    // ========================================================================
    /*
     * 왜 ConsumerFactory 를 새로 만드는가 — 이게 이 문제의 진짜 답입니다.
     *
     * 컨버터만 얹고 value-deserializer 를 그대로(JsonDeserializer) 두면,
     * ① kafka-clients 레이어에서 이미 OrderCreated 객체가 만들어져 있으므로
     * ② StringJsonMessageConverter 는 "변환할 String 이 없네" 하고 그냥 통과시킵니다.
     * 예외도 로그도 없습니다. 겉으로는 잘 도는 것처럼 보이는데
     * 정답 4 의 @KafkaHandler 타입 분기가 의도대로 동작하지 않고, 원인을 찾을 단서가 없습니다.
     *
     * 그래서 이 팩토리 전용 ConsumerFactory 를 만들고 key/value 를 StringDeserializer 로 내립니다.
     * application.yml 의 기본 팩토리는 그대로 두므로 다른 리스너에 영향이 없습니다.
     *
     * KafkaProperties.buildConsumerProperties() 로 시작하는 이유는
     * bootstrap-servers / auto-offset-reset / enable-auto-commit 같은 공통 설정을
     * 손으로 다시 쓰지 않기 위해서입니다. 그 위에 필요한 것만 덮어씁니다.
     *
     * ErrorHandlingDeserializer 관련 위임 프로퍼티와 spring.json.value.default.type 은
     * StringDeserializer 에게 아무 의미가 없으므로 지웁니다. 남겨도 동작은 하지만
     * 기동 로그에 "isn't a known config" 성격의 잡음이 남습니다.
     *
     * 이 방식의 이점(본문 5-6 표):
     *   · 타입을 리스너 메서드 시그니처가 결정 → 한 팩토리로 여러 타입
     *   · 역직렬화 실패가 poll 이 아니라 리스너 호출 시점에 나므로
     *     DefaultErrorHandler 가 정상적으로 처리 → 포이즌 필이 파티션을 막지 않음
     */
    @Configuration
    @Profile("step05-sol")
    public static class S7Config {

        @Bean
        public ConsumerFactory<String, String> solStringConsumerFactory(KafkaProperties properties) {
            Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.remove("spring.deserializer.key.delegate.class");
            props.remove("spring.deserializer.value.delegate.class");
            props.remove("spring.json.value.default.type");
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, String> solJsonFactory(
                ConsumerFactory<String, String> solStringConsumerFactory) {

            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(solStringConsumerFactory);
            factory.setRecordMessageConverter(new StringJsonMessageConverter());
            factory.setConcurrency(3);
            return factory;
        }
    }
}
