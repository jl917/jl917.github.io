package com.example.order.step04;

/*
 * ============================================================================
 *  Step 04 — 직렬화와 역직렬화 : Solution (6문제 정답 + 해설)
 * ============================================================================
 *
 *  Exercise.java 를 먼저 풀어 본 뒤에 여세요.
 *  실행 프로필은 Exercise 와 같습니다 (step04-ex1 ~ step04-ex6).
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Solution {

    public static final String TOPIC = "s04-orders";
    public static final String GROUP = "s04-inventory";
    public static final String BOOTSTRAP = "127.0.0.1:9092";

    private Solution() { }


    // ========================================================================
    // 정답 1. 타입 헤더를 끄고 고정 타입으로 역직렬화
    // ========================================================================
    /*
     * 프로듀서 : JsonSerializer.ADD_TYPE_INFO_HEADERS = false
     *            ("spring.json.add.type.headers")
     * 컨슈머   : JsonDeserializer.VALUE_DEFAULT_TYPE  = OrderCreated FQCN
     *            ("spring.json.value.default.type")
     *          + JsonDeserializer.USE_TYPE_INFO_HEADERS = false
     *            ("spring.json.use.type.headers")
     *
     * (c) 가 이 문제의 핵심입니다. 왜 컨슈머 쪽 USE_TYPE_INFO_HEADERS=false 까지 넣는가?
     *
     *   프로듀서에서 헤더를 껐다고 해서 토픽이 깨끗해지는 게 아닙니다.
     *   설정을 바꾸기 "전에" 발행된 메시지들에는 __TypeId__ 가 그대로 박혀 있고,
     *   Kafka 레코드는 불변이라 그 헤더를 수정할 방법이 없습니다.
     *   리텐션이 7일이면 7일간, compact 토픽이면 영원히 남습니다.
     *
     *   JsonDeserializer 는 기본적으로 "헤더가 있으면 헤더를 우선" 합니다.
     *   즉 USE_TYPE_INFO_HEADERS 를 끄지 않으면, 옛 메시지를 만나는 순간
     *   VALUE_DEFAULT_TYPE 을 무시하고 헤더의 FQCN 을 따라가고,
     *   그 클래스가 없으면 4-4 의 ClassNotFoundException 이 다시 터집니다.
     *
     *   정리하면: 프로듀서 설정은 "미래"를 고치고, 컨슈머 설정은 "과거"를 고칩니다.
     *   둘 다 필요합니다. 이것이 4-4 의 "되돌릴 수 없다" 와 정확히 같은 이유입니다.
     */
    @Configuration
    @Profile("step04-ex1")
    public static class Sol1Config {

        @Bean
        public ProducerFactory<String, Object> ex1ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);          // (a)

            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setValueSerializer(new JsonSerializer<>(objectMapper));
            return pf;
        }

        @Bean
        public KafkaTemplate<String, Object> ex1KafkaTemplate(ProducerFactory<String, Object> pf) {
            return new KafkaTemplate<>(pf);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> ex1ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");
            props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderCreated.class.getName());  // (b)
            props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);                      // (c)

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }
    }


    // ========================================================================
    // 정답 2. 논리 타입명으로 패키지 결합 끊기
    // ========================================================================
    /*
     * 프로듀서 : JsonSerializer.TYPE_MAPPINGS
     * 컨슈머   : JsonDeserializer.TYPE_MAPPINGS
     *   두 상수 모두 프로퍼티 문자열은 "spring.json.type.mapping" 으로 같습니다.
     *   같은 프로퍼티를 프로듀서 설정 맵에 넣으면 직렬화 방향으로,
     *   컨슈머 설정 맵에 넣으면 역직렬화 방향으로 해석됩니다.
     *
     * 형식 : "논리이름:FQCN,논리이름2:FQCN2"
     *
     *   프로듀서 : order:com.example.order.domain.OrderCreated
     *   컨슈머   : order:com.example.inventory.event.OrderCreated   ← FQCN 이 달라도 된다
     *
     * 헤더 확인:
     *   Partition:1  __TypeId__:order  ORD-0001  {"orderId":"ORD-0001",...}
     *
     * 이 문제의 핵심 문장:
     *   "헤더에 나가는 것은 order 뿐이고, 그 다섯 글자만이 두 서비스의 계약이다."
     *
     * FQCN 을 헤더에 박는 것은 사실상 "패키지 구조를 공개 API 로 승격"하는 결정입니다.
     * 논리 이름을 쓰면 계약은 이름 하나로 축소되고, 각 서비스는 자기 클래스를
     * 마음대로 옮기고 이름을 바꿀 수 있습니다. 이벤트 스키마의 버저닝
     * (order.v1 / order.v2) 도 이 이름 위에서 자연스럽게 됩니다.
     *
     * 언제 정답 1 대신 정답 2 인가?
     *   - 토픽 1개에 이벤트 타입 1개  → 정답 1 (헤더 자체를 없앤다. 더 단순)
     *   - 토픽 1개에 여러 타입이 섞임 → 정답 2 (타입 구분이 필요하므로 헤더가 있어야 한다)
     */
    @Configuration
    @Profile("step04-ex2")
    public static class Sol2Config {

        @Bean
        public ProducerFactory<String, Object> ex2ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(JsonSerializer.TYPE_MAPPINGS,
                    "order:com.example.order.domain.OrderCreated");

            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setValueSerializer(new JsonSerializer<>(objectMapper));
            return pf;
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> ex2ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            // 재고 서비스라면 여기가 com.example.inventory.event.OrderCreated 가 됩니다.
            props.put(JsonDeserializer.TYPE_MAPPINGS,
                    "order:com.example.order.domain.OrderCreated");
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }
    }


    // ========================================================================
    // 정답 3. 포이즌 필 재현과 LAG 고착
    // ========================================================================
    /*
     * (a) 재현
     *     kcp --topic s04-orders --property parse.key=true --property key.separator=:
     *     >ORD-BAD:not-a-json
     *     >^D
     *     (아래 Sol3PoisonPill 이 이것을 코드로 자동화한 것입니다)
     *
     * (c) 반복되는 로그 두 줄
     *     WARN  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer :
     *         Seek to current after exception; nested exception is
     *         org.apache.kafka.common.errors.RecordDeserializationException:
     *         Error deserializing key/value for partition s04-orders-1 at offset 3
     *     ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer :
     *         Consumer exception
     *
     *     "at offset 3" 이 매번 같은 값이라는 것이 포이즌 필의 지문입니다.
     *     오프셋이 증가하는 반복 에러는 다른 문제(예: 리스너 로직 버그)입니다.
     *
     * (d) LAG 관찰
     *     [직후]
     *     GROUP          TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
     *     s04-inventory  s04-orders  0          5               5               0
     *     s04-inventory  s04-orders  1          3               9               6
     *     s04-inventory  s04-orders  2          4               4               0
     *
     *     [5분 뒤]
     *     GROUP          TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
     *     s04-inventory  s04-orders  0          312             312             0
     *     s04-inventory  s04-orders  1          3               308             305
     *     s04-inventory  s04-orders  2          3               319             316
     *
     *     읽는 법:
     *       LOG-END-OFFSET 은 계속 올라가는데 CURRENT-OFFSET 만 3 에 얼어 있습니다.
     *       "컨슈머가 살아 있고(리밸런스도 안 일어나고) 로그도 계속 찍히는데
     *        커밋 위치만 정지" — 이 조합이면 거의 항상 역직렬화 실패입니다.
     *
     * (e) 재시작해도 같은 자리에서 죽는 이유
     *     1. 실패한 offset 3 은 커밋되지 않았으므로 그룹의 커밋 위치는 여전히 3 입니다.
     *     2. 재시작하면 컨슈머는 커밋 위치인 offset 3 부터 다시 읽습니다.
     *     3. 토픽의 그 레코드는 불변이라 여전히 "not-a-json" 이고, 똑같이 실패합니다.
     *        → 재시작은 이 장애에 대해 아무 효과가 없습니다.
     *
     *     응급 조치 (앱을 끄고):
     *       kcg --group s04-inventory --topic s04-orders:1 --reset-offsets --to-offset 4 --execute
     *     단, 이것은 그 메시지를 영구히 버리는 것입니다.
     *     제대로 된 해결은 정답 4 입니다.
     */
    @Component
    @Profile("step04-ex3")
    public static class Sol3PoisonPill implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol3PoisonPill.class);

        private final KafkaTemplate<String, byte[]> raw;

        public Sol3PoisonPill(KafkaTemplate<String, byte[]> raw) {
            this.raw = raw;
        }

        @Override
        public void run(ApplicationArguments args) {
            raw.send(TOPIC, "ORD-BAD", "not-a-json".getBytes(StandardCharsets.UTF_8));
            raw.flush();
            log.warn("[sol3] 포이즌 필을 심었습니다. kcg --describe --group {} 를 반복 실행하세요.", GROUP);
        }
    }

    @Configuration
    @Profile("step04-ex3")
    public static class Sol3Config {

        @Bean
        public KafkaTemplate<String, byte[]> rawKafkaTemplate() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }


    // ========================================================================
    // 정답 4. ErrorHandlingDeserializer 로 복구
    // ========================================================================
    /*
     * 설정은 네 줄입니다.
     *
     *   KEY_DESERIALIZER_CLASS_CONFIG   = ErrorHandlingDeserializer.class
     *   VALUE_DESERIALIZER_CLASS_CONFIG = ErrorHandlingDeserializer.class
     *   ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS   = StringDeserializer.class
     *   ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS = JsonDeserializer.class
     *
     * 프로퍼티 문자열은 각각
     *   spring.deserializer.key.delegate.class
     *   spring.deserializer.value.delegate.class
     *
     * 동작:
     *   delegate 가 예외를 던지면 ErrorHandlingDeserializer 가 그것을 삼키고
     *   ① payload 를 null 로 만들어 정상 레코드처럼 반환하고
     *   ② 예외를 헤더 springDeserializerExceptionValue 에 담습니다.
     *   덕분에 poll() 이 성공하고, 레코드가 컨테이너까지 도달하며,
     *   컨테이너가 헤더의 예외를 보고 DefaultErrorHandler 로 넘깁니다.
     *   즉 "역직렬화 사고"가 "처리 가능한 에러"로 격하됩니다.
     *
     * (b) 왜 "키"까지 감싸야 하는가 — 실무에서 가장 흔한 미완성 방어
     *
     *   대부분의 예제와 블로그가 value 쪽만 감쌉니다. 값이 JSON 이고 키는 String 이니
     *   "String 은 깨질 일이 없다"고 생각하기 때문입니다. 대체로 맞습니다.
     *   그런데 키를 JsonSerializer 나 LongSerializer 로 보내는 토픽이 하나라도 있으면,
     *   또는 다른 팀이 같은 토픽에 다른 키 타입으로 쓰기 시작하면,
     *   키 역직렬화가 실패합니다. 그리고 결과는 값이 실패했을 때와 "완전히 동일"합니다.
     *   RecordDeserializationException 은 키/값을 구분하지 않고 poll() 에서 터지며,
     *   똑같이 seekToCurrent 무한 루프에 들어가 파티션을 멈춥니다.
     *
     *   예외 메시지가 "Error deserializing key/value" 라고 둘을 함께 적는 이유가 이것입니다.
     *   방어는 양쪽 다 해야 완성됩니다. 키 delegate 를 지정하는 비용은 두 줄뿐입니다.
     *
     * (d) 결과
     *   ERROR ... o.s.k.l.DefaultErrorHandler : Backoff none exhausted for s04-orders-1@3
     *   INFO  ... c.e.o.step04.Sol4Listener   : [s04-inventory] p=1 off=4 ORD-0010 SKU-002 x1
     *
     *   LAG 305 → 0. 에러 로그는 "한 번만" 나옵니다.
     *   Backoff none 인 이유: DeserializationException 은 DefaultErrorHandler 의
     *   기본 non-retryable 목록에 있습니다. 깨진 바이트는 다시 읽어도 깨져 있으므로
     *   재시도가 무의미하고, Spring 이 그것을 알고 있습니다.
     */
    @Configuration
    @Profile("step04-ex4")
    public static class Sol4Config {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> ex4ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   ErrorHandlingDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,   StringDeserializer.class);
            props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

            props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
            props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderCreated.class.getName());
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);
            return factory;
        }
    }

    @Component
    @Profile("step04-ex4")
    public static class Sol4Listener {

        private static final Logger log = LoggerFactory.getLogger(Sol4Listener.class);
        private static final LogAccessor LOG_ACCESSOR = new LogAccessor(log);

        @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = "ex4ListenerFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            DeserializationException valueEx = SerializationUtils.getExceptionFromHeader(
                    record, SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR);
            DeserializationException keyEx = SerializationUtils.getExceptionFromHeader(
                    record, SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR);

            if (valueEx != null || keyEx != null) {
                DeserializationException ex = valueEx != null ? valueEx : keyEx;
                log.error("역직렬화 실패 p={} off={} key={} raw={}",
                        record.partition(), record.offset(), record.key(),
                        new String(ex.getData(), StandardCharsets.UTF_8));
                return;   // 오프셋은 정상 커밋되고 뒤 메시지가 흐른다
            }

            OrderCreated event = record.value();
            log.info("[{}] p={} off={} {} {} x{}",
                    GROUP, record.partition(), record.offset(),
                    event.orderId(), event.sku(), event.quantity());
        }
    }


    // ========================================================================
    // 정답 5. trusted packages
    // ========================================================================
    /*
     * (a) 예외 메시지 전문
     *
     *   Caused by: org.apache.kafka.common.errors.RecordDeserializationException:
     *       Error deserializing key/value for partition s04-orders-0 at offset 0.
     *   Caused by: java.lang.IllegalArgumentException:
     *       The class 'com.example.order.domain.OrderCreated' is not in the trusted packages:
     *       [java.util, java.lang]. If you believe this class is safe to deserialize,
     *       please provide its name. If the serialization is only done by a trusted source,
     *       you can also enable trust all (*).
     *
     * (b) 해결 — 패키지를 정확히 나열한다
     *
     *   props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");
     *
     *   여러 개면 콤마로 구분합니다:
     *     "com.example.order.domain,com.example.common.event"
     *
     * (c) "*" 가 위험한 이유 (2줄)
     *
     *   1. __TypeId__ 헤더는 "토픽에 쓸 수 있는 누구나" 조작할 수 있는 값인데,
     *      "*" 는 그 값이 지목하는 클래스패스상의 어떤 클래스든 인스턴스화를 허용합니다.
     *   2. 클래스패스에 위험한 생성자/세터를 가진 클래스(gadget)가 하나라도 있으면
     *      메시지 한 건으로 임의 코드 실행(RCE)까지 이어질 수 있습니다.
     *      Jackson 의 polymorphic typing 관련 CVE 들이 전부 이 계열입니다.
     *
     *   덧붙여, 예외 메시지가 친절하게 "(*)" 를 안내하는 것이 이 함정의 촉매입니다.
     *   에러를 없애는 가장 빠른 방법이 가장 위험한 방법이라, 그대로 운영에 나갑니다.
     *
     * (d) 더 나은 답 — 애초에 헤더의 클래스 이름을 읽지 않는다
     *
     *   props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
     *   props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderCreated.class.getName());
     *
     *   즉 정답 1 의 구성입니다. 컨슈머가 헤더의 클래스 이름을 아예 안 읽으므로
     *   "신뢰할 수 있는 패키지인가"라는 질문 자체가 성립하지 않습니다.
     *   trusted.packages 는 "헤더를 읽어야만 하는 경우"의 차선책이고,
     *   최선책은 헤더를 읽지 않는 설계입니다.
     */
    @Configuration
    @Profile("step04-ex5")
    public static class Sol5Config {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> ex5ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

            // (b) "*" 가 아니라 정확한 패키지를 나열한다
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }
    }


    // ========================================================================
    // 정답 6. 스키마 진화 — 필드 추가를 견디게 만들기
    // ========================================================================
    /*
     * (a) 미지의 필드를 무시하는 mapper
     *
     *   ObjectMapper mapper = new ObjectMapper()
     *           .registerModule(new JavaTimeModule())
     *           .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
     *
     *   JavaTimeModule 을 함께 등록하는 것을 잊지 마세요.
     *   빼면 createdAt("2025-01-01T00:21:00Z") 파싱에서 다른 예외가 납니다.
     *
     * (b) 같은 목적을 달성하는 3가지 방법
     *
     *   1. application.yml
     *        spring.jackson.deserialization.fail-on-unknown-properties: false
     *      → 애플리케이션 전역. Boot 의 ObjectMapper 빈에 적용됩니다.
     *
     *   2. mapper 직접 설정
     *        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
     *      → JsonDeserializer 에 직접 mapper 를 넘길 때 이 방법을 씁니다.
     *
     *   3. 클래스 단위 애너테이션
     *        @JsonIgnoreProperties(ignoreUnknown = true)
     *        public record OrderCreated(...) { }
     *      → mapper 설정과 무관하게 그 클래스만 관대해집니다. 이벤트 DTO 에 권장.
     *        누가 mapper 를 바꿔도 이 클래스는 안전하다는 점이 장점입니다.
     *
     * (c) Boot mapper 를 주입받으면 왜 괜찮고, 왜 여전히 위험한가
     *
     *   괜찮은 이유:
     *     Boot 의 JacksonAutoConfiguration 은 FAIL_ON_UNKNOWN_PROPERTIES 를
     *     false 로 바꾸고 JavaTimeModule 을 등록해 줍니다.
     *     spring-kafka 의 JacksonUtils.enhancedObjectMapper() 도 마찬가지입니다.
     *     즉 "그냥 두면" 안전한 쪽으로 설정돼 있습니다.
     *
     *   여전히 위험한 이유:
     *     누군가 성능 튜닝이나 커스텀 직렬화를 하려고
     *     new ObjectMapper() 를 만들어 JsonSerializer/JsonDeserializer 에 넘기는 순간,
     *     Jackson 의 기본값(FAIL_ON_UNKNOWN_PROPERTIES = true)으로 되돌아갑니다.
     *     그 코드는 배포 당시엔 아무 문제가 없습니다. 필드가 추가되는 3개월 뒤에
     *     전 컨슈머가 UnrecognizedPropertyException 으로 멈춥니다.
     *     "설정을 안 건드리면 안전한데, 건드리면 조용히 위험해지는" 전형적인 구조입니다.
     *
     *   대응: 이벤트 클래스에 @JsonIgnoreProperties(ignoreUnknown = true) 를 붙여
     *         mapper 설정에 의존하지 않게 만들고, 신버전 JSON 을 구버전 클래스로
     *         읽는 단위 테스트를 하나 두어 회귀를 막으세요.
     */
    @Component
    @Profile("step04-ex6")
    public static class Sol6SchemaEvolution implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol6SchemaEvolution.class);

        private static final String V2_JSON = """
                {"orderId":"ORD-0021","customerId":1021,"sku":"SKU-001","quantity":2,\
                "amount":10000,"createdAt":"2025-01-01T00:21:00Z","couponCode":"WELCOME10"}""";

        @Override
        public void run(ApplicationArguments args) throws Exception {
            log.info("[sol6] v2 JSON = {}", V2_JSON);

            // 방어 없는 mapper — 실패하는 것이 정상입니다
            ObjectMapper strict = new ObjectMapper().registerModule(new JavaTimeModule());
            try {
                strict.readValue(V2_JSON, OrderCreated.class);
                log.warn("[sol6] strict mapper 가 성공했습니다 — 설정을 확인하세요");
            } catch (Exception e) {
                log.error("[sol6] strict mapper 실패 (예상된 결과): {}", e.getClass().getSimpleName());
            }

            // (a) 정답 — 미지의 필드를 무시
            ObjectMapper lenient = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            OrderCreated event = lenient.readValue(V2_JSON, OrderCreated.class);
            log.info("[sol6] 성공: {} {} x{} amount={} createdAt={}",
                    event.orderId(), event.sku(), event.quantity(),
                    event.amount(), event.createdAt());
            log.info("[sol6] couponCode 는 구버전 클래스에 없으므로 조용히 버려졌습니다. "
                    + "이것이 스키마 진화에서 원하는 동작입니다.");
        }
    }
}
