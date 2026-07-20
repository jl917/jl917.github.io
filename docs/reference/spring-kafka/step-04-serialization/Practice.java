package com.example.order.step04;

/*
 * ============================================================================
 *  Step 04 — 직렬화와 역직렬화 : Practice
 * ============================================================================
 *
 *  배치 위치 : src/main/java/com/example/order/step04/Practice.java
 *  토픽      : s04-orders (partitions=3)   ← 본선 orders 를 더럽히지 않기 위해 전용 토픽
 *  그룹      : s04-inventory
 *
 *  [사전 준비]
 *    kt --create --topic s04-orders --partitions 3 --replication-factor 1
 *    kt --create --topic s04-orders.DLT --partitions 3 --replication-factor 1
 *
 *  [터미널을 3개 띄우세요]
 *    (1) 앱 로그
 *    (2) kcc --topic s04-orders --from-beginning \
 *            --property print.key=true --property print.headers=true --property print.partition=true
 *    (3) kcg --describe --group s04-inventory        ← 반복 실행하며 LAG 을 관찰
 *
 * ----------------------------------------------------------------------------
 *  실행 시나리오 — 반드시 A → B → C → D 순서로
 * ----------------------------------------------------------------------------
 *
 *  A. 정상 동작 + __TypeId__ 헤더 관찰                                [4-2] [4-3]
 *     ./gradlew bootRun --args='--spring.profiles.active=step04'
 *     → (2)번 터미널에서 __TypeId__:com.example.order.domain.OrderCreated 확인
 *
 *     A'. JavaTimeModule 없는 mapper 로 보내 보기                     [4-2]
 *     ./gradlew bootRun --args='--spring.profiles.active=step04,step04-broken-json'
 *     → createdAt 이 {"epochSecond":...} 로 나가는 것 확인
 *
 *  B. ⚠️ 포이즌 필 재현 — 파티션이 영원히 멈춘다                       [4-6]
 *     ./gradlew bootRun --args='--spring.profiles.active=step04,step04-poison'
 *     → "Seek to current after exception ... at offset N" 이 무한 반복
 *     → (3)번 터미널에서 파티션 1 의 LAG 만 계속 증가
 *     → Ctrl+C 후 재시작해도 같은 오프셋에서 죽는 것을 반드시 확인할 것
 *
 *     응급 조치(앱을 끄고):
 *       kcg --group s04-inventory --topic s04-orders:1 --reset-offsets --to-offset 4 --execute
 *
 *  C. ErrorHandlingDeserializer 로 복구                              [4-7] [4-8]
 *     kcg --group s04-inventory --topic s04-orders --reset-offsets --to-earliest --execute
 *     ./gradlew bootRun --args='--spring.profiles.active=step04,step04-safe'
 *     → 나쁜 메시지 1건만 ERROR 한 줄로 지나가고 나머지는 전부 처리
 *     → LAG 이 0 으로 수렴
 *
 *  D. 타입 헤더 끄기 / 논리 타입 매핑                                 [4-4]
 *     ./gradlew bootRun --args='--spring.profiles.active=step04,step04-safe,step04-notypeheader'
 *     ./gradlew bootRun --args='--spring.profiles.active=step04,step04-safe,step04-typemapping'
 *     → (2)번 터미널에서 헤더가 NO_HEADERS / __TypeId__:order 로 바뀌는 것 확인
 *
 * ----------------------------------------------------------------------------
 *  뒷정리
 *    kt --delete --topic s04-orders && kt --create --topic s04-orders --partitions 3 --replication-factor 1
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class Practice {

    public static final String TOPIC = "s04-orders";
    public static final String DLT   = "s04-orders.DLT";
    public static final String GROUP = "s04-inventory";
    public static final String BOOTSTRAP = "127.0.0.1:9092";

    private Practice() { }

    // ========================================================================
    // [4-1] 직렬화가 어디서 일어나는가
    //
    //   프로듀서 : send() 를 호출한 스레드에서 "즉시" 직렬화 → 예외도 그 자리에서
    //   컨슈머   : poll() 내부에서 역직렬화 → 실패하면 리스너 메서드에 도달조차 못 함
    //
    //   이 비대칭이 [4-6] 포이즌 필의 원인입니다. 리스너가 호출되지 않으므로
    //   @KafkaListener 안에 try/catch 를 아무리 둘러도 소용이 없습니다.
    // ========================================================================


    // ========================================================================
    // [4-2] JsonSerializer — ObjectMapper 를 어디서 가져오는가
    // ========================================================================

    /** 정상 구성. Spring Boot 가 만든 ObjectMapper 빈을 JsonSerializer 에 주입합니다. */
    @Configuration
    @Profile("step04 & !step04-broken-json")
    public static class GoodProducerConfig {

        @Bean
        public ProducerFactory<String, Object> s04ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.ACKS_CONFIG, "all");

            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            // Boot mapper 에는 JavaTimeModule 이 이미 등록돼 있고
            // FAIL_ON_UNKNOWN_PROPERTIES / WRITE_DATES_AS_TIMESTAMPS 도 꺼져 있습니다.
            pf.setValueSerializer(new JsonSerializer<>(objectMapper));
            return pf;
        }

        @Bean
        public KafkaTemplate<String, Object> s04KafkaTemplate(ProducerFactory<String, Object> pf) {
            return new KafkaTemplate<>(pf);
        }
    }

    /**
     * ⚠️ 일부러 망가뜨린 구성. new ObjectMapper() 는 JavaTimeModule 이 없습니다.
     *
     *   결과 : {"...","createdAt":{"epochSecond":1735689660,"nano":0}}
     *
     * 예외가 전혀 나지 않는다는 점이 핵심입니다. Java 컨슈머끼리는 왕복도 되므로
     * 다른 언어 컨슈머가 붙기 전까지 아무도 모릅니다.
     */
    @Configuration
    @Profile("step04-broken-json")
    public static class BrokenMapperConfig {

        @Bean
        public ProducerFactory<String, Object> s04ProducerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setValueSerializer(new JsonSerializer<>(new ObjectMapper()));   // ⚠️ 하지 마세요
            return pf;
        }

        @Bean
        public KafkaTemplate<String, Object> s04KafkaTemplate(ProducerFactory<String, Object> pf) {
            return new KafkaTemplate<>(pf);
        }
    }


    // ========================================================================
    // [4-3] __TypeId__ 헤더 관찰용 — 정상 메시지 10건 발행
    // ========================================================================

    @Component
    @Profile("step04")
    public static class SeedProducer implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(SeedProducer.class);

        private final KafkaTemplate<String, Object> template;

        public SeedProducer(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int i = 1; i <= 10; i++) {
                OrderCreated event = OrderCreated.of(i);
                template.send(TOPIC, event.orderId(), event);
                log.info("[produce] {} amount={} createdAt={}",
                        event.orderId(), event.amount(), event.createdAt());
            }
            template.flush();
            log.info("[produce] 10건 발행 완료. 콘솔 컨슈머에서 __TypeId__ 헤더를 확인하세요.");
        }
    }


    // ========================================================================
    // [4-4] 타입 헤더 끄기 / 논리 타입 매핑
    // ========================================================================

    /**
     * ① 권장 — 타입 헤더를 아예 붙이지 않고, 컨슈머는 고정 타입으로 역직렬화.
     *    프로듀서와 컨슈머가 서로의 패키지 구조를 전혀 모르게 됩니다.
     */
    @Configuration
    @Profile("step04-notypeheader")
    public static class NoTypeHeaderConfig {

        @Bean
        public ProducerFactory<String, Object> s04ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            // "spring.json.add.type.headers" = false
            props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setValueSerializer(new JsonSerializer<>(objectMapper));
            return pf;
        }

        @Bean
        public KafkaTemplate<String, Object> s04KafkaTemplate(ProducerFactory<String, Object> pf) {
            return new KafkaTemplate<>(pf);
        }
    }

    /**
     * ② 한 토픽에 여러 이벤트 타입이 흐를 때 — 논리 이름을 계약으로 삼습니다.
     *
     *   헤더 : __TypeId__:order       ← 패키지가 사라짐
     *
     * 컨슈머 쪽은 같은 "order" 키를 자기 패키지의 클래스로 매핑하면 되므로
     * 각 서비스가 클래스를 자유롭게 옮길 수 있습니다.
     */
    @Configuration
    @Profile("step04-typemapping")
    public static class TypeMappingConfig {

        @Bean
        public ProducerFactory<String, Object> s04ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            // "spring.json.type.mapping"
            props.put(JsonSerializer.TYPE_MAPPINGS,
                    "order:com.example.order.domain.OrderCreated");

            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setValueSerializer(new JsonSerializer<>(objectMapper));
            return pf;
        }

        @Bean
        public KafkaTemplate<String, Object> s04KafkaTemplate(ProducerFactory<String, Object> pf) {
            return new KafkaTemplate<>(pf);
        }
    }


    // ========================================================================
    // [4-6] ⚠️ 포이즌 필 재현
    //
    //   콘솔 프로듀서로 손수 넣는 것과 동일한 효과를 내기 위해,
    //   byte[] 직렬화기를 쓰는 별도 KafkaTemplate 으로 JSON 이 아닌 바이트를 보냅니다.
    //
    //   ⚠️ 이 빈은 step04-poison 프로필 전용입니다. 실수로 켜지 마세요.
    //      한 번 들어간 메시지는 토픽에서 지울 수 없습니다.
    // ========================================================================

    @Configuration
    @Profile("step04-poison")
    public static class PoisonPillConfig {

        /** 값 직렬화기가 ByteArraySerializer 인 별도 템플릿 */
        @Bean
        public KafkaTemplate<String, byte[]> rawKafkaTemplate() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    @Component
    @Profile("step04-poison")
    public static class PoisonPillProducer implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(PoisonPillProducer.class);

        private final KafkaTemplate<String, byte[]> raw;

        public PoisonPillProducer(KafkaTemplate<String, byte[]> raw) {
            this.raw = raw;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            // 키 "ORD-BAD" 는 파티션 1 로 갑니다(파티션 3개, murmur2 해시).
            raw.send(TOPIC, "ORD-BAD", "not-a-json".getBytes(StandardCharsets.UTF_8));
            raw.flush();

            log.warn("[poison] s04-orders 에 'not-a-json' 을 넣었습니다. "
                    + "이제 이 파티션은 ErrorHandlingDeserializer 없이는 영원히 진행하지 못합니다.");
            log.warn("[poison] kcg --describe --group {} 를 반복 실행해 LAG 을 관찰하세요.", GROUP);
        }
    }

    /**
     * ⚠️ 방어 없는 컨슈머 — 포이즌 필에 걸리는 쪽.
     *
     *   value-deserializer 가 JsonDeserializer "직접" 입니다. 감싸지 않았습니다.
     *   깨진 레코드를 만나면 poll() 안에서 RecordDeserializationException 이 나고,
     *   컨테이너는 seekToCurrent 로 같은 오프셋으로 되감으며 무한 루프에 들어갑니다.
     *
     *   로그 :
     *     WARN  o.s.k.l.KafkaMessageListenerContainer : Seek to current after exception ... at offset 3
     *     ERROR o.s.k.l.KafkaMessageListenerContainer : Consumer exception
     *     (같은 두 줄이 초당 수백 번 반복)
     */
    @Configuration
    @Profile("step04-poison & !step04-safe")
    public static class UnsafeConsumerConfig {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> s04ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);  // ⚠️ 무방비
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");
            props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderCreated.class.getName());

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);
            return factory;
        }
    }


    // ========================================================================
    // [4-7] ErrorHandlingDeserializer — 이 스텝의 정답 설정
    // ========================================================================

    @Configuration
    @Profile("step04-safe")
    public static class SafeConsumerConfig {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> s04ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

            // --- 핵심 5줄 ---------------------------------------------------
            // 겉에는 ErrorHandlingDeserializer, 진짜 역직렬화기는 delegate 로 내려보낸다.
            // 키도 반드시 감쌀 것: 키가 깨진 메시지도 똑같이 파티션을 멈춘다.
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   ErrorHandlingDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,   StringDeserializer.class);
            props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
            // ----------------------------------------------------------------

            // 헤더의 클래스 이름을 아예 읽지 않는다 → 패키지 결합도 신뢰 문제도 동시에 소멸
            props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);   // spring.json.use.type.headers
            props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderCreated.class.getName());
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");  // "*" 금지

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);
            factory.setCommonErrorHandler(deadLetterErrorHandler());
            return factory;
        }

        // ====================================================================
        // [4-8] 실패 레코드를 DLT 로 보존한다 (자세한 내용은 Step 07)
        //
        //   FixedBackOff(0L, 0L) = 재시도 0회.
        //   어차피 DeserializationException 은 DefaultErrorHandler 의 기본
        //   non-retryable 목록에 있어 재시도되지 않지만, 의도를 코드로 못박습니다.
        // ====================================================================
        @Bean
        public DefaultErrorHandler deadLetterErrorHandler() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            KafkaTemplate<Object, Object> dltTemplate =
                    new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltTemplate);
            return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
        }
    }

    /**
     * ConsumerRecord 로 직접 받아, 헤더에 실려 온 역직렬화 예외를 꺼내 봅니다.
     * ex.getData() 가 원본 바이트이므로 "무엇이 잘못 들어왔는가"를 눈으로 볼 수 있습니다.
     */
    @Component
    @Profile("step04-safe")
    public static class Step04SafeListener {

        private static final Logger log = LoggerFactory.getLogger(Step04SafeListener.class);
        private static final LogAccessor LOG_ACCESSOR = new LogAccessor(log);

        @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = "s04ListenerFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            DeserializationException ex = SerializationUtils.getExceptionFromHeader(
                    record, SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR);

            if (ex != null) {
                log.error("역직렬화 실패 p={} off={} key={} raw={}",
                        record.partition(), record.offset(), record.key(),
                        new String(ex.getData(), StandardCharsets.UTF_8));
                return;   // 건너뛴다 → 오프셋은 정상 커밋되어 뒤 메시지가 흐른다
            }

            OrderCreated event = record.value();
            log.info("[{}] p={} off={} {} {} x{}",
                    GROUP, record.partition(), record.offset(),
                    event.orderId(), event.sku(), event.quantity());
        }
    }

    /** 방어 없는 쪽의 리스너. try/catch 를 둘러도 소용없다는 것을 보이기 위한 것입니다. */
    @Component
    @Profile("step04-poison & !step04-safe")
    public static class Step04UnsafeListener {

        private static final Logger log = LoggerFactory.getLogger(Step04UnsafeListener.class);

        @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = "s04ListenerFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            try {
                OrderCreated event = record.value();
                log.info("[{}] p={} off={} {} {} x{}",
                        GROUP, record.partition(), record.offset(),
                        event.orderId(), event.sku(), event.quantity());
            } catch (Exception e) {
                // ⚠️ 여기는 절대 실행되지 않습니다.
                //    역직렬화 예외는 poll() 안에서 나므로 이 메서드까지 오지 못합니다.
                log.error("여기는 도달하지 않습니다", e);
            }
        }
    }


    // ========================================================================
    // [4-9] 스키마 진화 — 필드 추가/삭제
    // ========================================================================

    /** 신버전 이벤트. couponCode 가 추가되었습니다. */
    public record OrderCreatedV2(
            String orderId,
            int customerId,
            String sku,
            int quantity,
            BigDecimal amount,
            Instant createdAt,
            String couponCode          // ← 새 필드
    ) {
        public static OrderCreatedV2 of(int seq) {
            OrderCreated v1 = OrderCreated.of(seq);
            return new OrderCreatedV2(v1.orderId(), v1.customerId(), v1.sku(),
                    v1.quantity(), v1.amount(), v1.createdAt(), "WELCOME10");
        }
    }

    /**
     * 구버전 컨슈머가 신버전 JSON 을 읽는 상황을 한 프로세스 안에서 재현합니다.
     *
     *   기본 ObjectMapper : UnrecognizedPropertyException: Unrecognized field "couponCode"
     *   FAIL_ON_UNKNOWN_PROPERTIES=false : couponCode 를 무시하고 정상 역직렬화
     */
    @Component
    @Profile("step04-evolution")
    public static class SchemaEvolutionDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(SchemaEvolutionDemo.class);

        @Override
        public void run(ApplicationArguments args) throws Exception {
            String v2Json = new ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(OrderCreatedV2.of(21));
            log.info("[evolution] v2 JSON = {}", v2Json);

            // (a) 방어 없는 mapper — 실패
            ObjectMapper strict = new ObjectMapper().findAndRegisterModules();
            try {
                strict.readValue(v2Json, OrderCreated.class);
                log.info("[evolution] strict mapper: 성공 (여기 오면 안 됩니다)");
            } catch (Exception e) {
                log.error("[evolution] strict mapper 실패: {}", e.getClass().getSimpleName());
                log.error("[evolution]   {}", e.getMessage());
            }

            // (b) FAIL_ON_UNKNOWN_PROPERTIES=false — 성공
            ObjectMapper lenient = new ObjectMapper()
                    .findAndRegisterModules()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            OrderCreated back = lenient.readValue(v2Json, OrderCreated.class);
            log.info("[evolution] lenient mapper 성공: {} {} x{}",
                    back.orderId(), back.sku(), back.quantity());

            // (c) 클래스 단위 해결책
            OrderCreatedTolerant tolerant = strict.readValue(v2Json, OrderCreatedTolerant.class);
            log.info("[evolution] @JsonIgnoreProperties 성공: {}", tolerant.orderId());
        }
    }

    /** 클래스 단위로 미지의 필드를 무시하도록 선언한 버전. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderCreatedTolerant(
            String orderId,
            int customerId,
            String sku,
            int quantity,
            BigDecimal amount,
            Instant createdAt
    ) { }


    // ========================================================================
    // [4-10] record 는 되고, 파라미터 생성자만 있는 일반 클래스는 안 될 수 있다
    // ========================================================================

    /**
     * 기본 생성자도 @JsonCreator 도 없고 -parameters 도 없다면:
     *
     *   InvalidDefinitionException: Cannot construct instance of `...LegacyOrder`
     *   (no Creators, like default constructor, exist)
     *
     * Spring Boot 의 Gradle 플러그인은 -parameters 를 기본으로 켜 주므로 실습에서는 통과하지만,
     * 순수 javac 로 빌드하는 모듈이나 외부 라이브러리 DTO 에서는 흔히 실패합니다.
     */
    public static final class LegacyOrder {
        private final String orderId;
        private final int customerId;

        public LegacyOrder(String orderId, int customerId) {
            this.orderId = orderId;
            this.customerId = customerId;
        }

        public String getOrderId()  { return orderId; }
        public int getCustomerId()  { return customerId; }
    }

    @Component
    @Profile("step04-legacy")
    public static class LegacyDeserializationDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(LegacyDeserializationDemo.class);

        @Override
        public void run(ApplicationArguments args) {
            String json = "{\"orderId\":\"ORD-0001\",\"customerId\":1001}";
            try {
                LegacyOrder o = new ObjectMapper().readValue(json, LegacyOrder.class);
                log.info("[legacy] 성공: {} ({}) — -parameters 가 켜져 있습니다",
                        o.getOrderId(), o.getCustomerId());
            } catch (Exception e) {
                log.error("[legacy] 실패: {}", e.getClass().getSimpleName());
                log.error("[legacy]   {}", e.getMessage());
            }
        }
    }
}
