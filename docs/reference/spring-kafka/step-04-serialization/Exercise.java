package com.example.order.step04;

/*
 * ============================================================================
 *  Step 04 — 직렬화와 역직렬화 : Exercise (6문제)
 * ============================================================================
 *
 *  배치 위치 : src/main/java/com/example/order/step04/Exercise.java
 *  토픽/그룹 : s04-orders / s04-inventory   (Practice.java 와 동일)
 *
 *  풀이 방법
 *    1. 각 문제의 "여기에 작성:" 자리를 채웁니다. 뼈대만으로도 컴파일은 됩니다.
 *    2. 문제마다 지정된 프로필로 실행해 로그를 확인합니다.
 *         ./gradlew bootRun --args='--spring.profiles.active=step04-ex1'
 *    3. 터미널 두 개를 더 띄워 두세요. 로그만으로는 절반밖에 안 보입니다.
 *         kcc --topic s04-orders --from-beginning --property print.headers=true --property print.key=true
 *         kcg --describe --group s04-inventory
 *
 *  ⚠️ 문제 3 은 토픽에 깨진 메시지를 "영구히" 넣습니다.
 *     실습이 끝나면 아래로 토픽을 새로 만드세요.
 *         kt --delete --topic s04-orders
 *         kt --create --topic s04-orders --partitions 3 --replication-factor 1
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Exercise {

    public static final String TOPIC = "s04-orders";
    public static final String GROUP = "s04-inventory";
    public static final String BOOTSTRAP = "127.0.0.1:9092";

    private Exercise() { }


    // ========================================================================
    // 문제 1. 타입 헤더를 끄고, 컨슈머는 고정 타입으로 역직렬화하게 만드세요.
    //
    //   요구사항
    //     (a) 프로듀서가 __TypeId__ 헤더를 "붙이지 않게" 한다.
    //         → 콘솔 컨슈머에서 NO_HEADERS 가 보여야 한다.
    //     (b) 컨슈머는 헤더가 없어도 com.example.order.domain.OrderCreated 로
    //         역직렬화하게 한다.
    //     (c) 과거에 헤더가 붙어 나간 메시지가 토픽에 남아 있다는 점을 고려한다.
    //         (힌트: 컨슈머가 헤더를 "읽지 않게" 만드는 설정이 하나 더 있습니다)
    //
    //   실행 : --spring.profiles.active=step04-ex1
    //   확인 : kcc --topic s04-orders --from-beginning --property print.headers=true
    // ========================================================================

    @Configuration
    @Profile("step04-ex1")
    public static class Ex1Config {

        @Bean
        public ProducerFactory<String, Object> ex1ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            // 여기에 작성: __TypeId__ 헤더를 붙이지 않는 설정
            // props.put(...);

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

            // 여기에 작성: (b) 고정 타입 지정 + (c) 헤더를 읽지 않게 하는 설정
            // props.put(...);
            // props.put(...);

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }
    }


    // ========================================================================
    // 문제 2. 논리 타입명으로 패키지 결합을 끊으세요.
    //
    //   상황
    //     주문 서비스는 이벤트를 com.example.order.domain.OrderCreated 로 갖고 있고,
    //     재고 서비스는 같은 이벤트를 com.example.inventory.event.OrderCreated 로
    //     "자기 패키지에" 두고 싶어 합니다.
    //
    //   요구사항
    //     (a) 헤더에 나가는 __TypeId__ 값이 "order" 가 되게 한다.
    //     (b) 프로듀서와 컨슈머가 서로 다른 FQCN 을 써도 동작하게 한다.
    //     (c) 두 설정에 쓰이는 프로퍼티 "상수 이름"이 각각 무엇인지 주석으로 적는다.
    //
    //   실행 : --spring.profiles.active=step04-ex2
    //   확인 : 콘솔 컨슈머에서 __TypeId__:order 가 보여야 한다
    // ========================================================================

    @Configuration
    @Profile("step04-ex2")
    public static class Ex2Config {

        @Bean
        public ProducerFactory<String, Object> ex2ProducerFactory(ObjectMapper objectMapper) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            // 여기에 작성: 논리 이름 "order" 를 FQCN 에 매핑
            // 사용한 상수 이름:
            // props.put(...);

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

            // 여기에 작성: 컨슈머 쪽 매핑. 실습 프로젝트에는 inventory 패키지가 없으므로
            //             com.example.order.domain.OrderCreated 를 그대로 써도 되지만,
            //             "이 자리에 다른 FQCN 을 넣어도 동작한다"는 것이 요점입니다.
            // 사용한 상수 이름:
            // props.put(...);
            // props.put(JsonDeserializer.TRUSTED_PACKAGES, ...);

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }
    }


    // ========================================================================
    // 문제 3. ⚠️ 포이즌 필을 재현하고 LAG 고착을 관찰하세요.
    //
    //   요구사항
    //     (a) 콘솔 프로듀서로 s04-orders 에 JSON 이 아닌 문자열을 1건 넣는다.
    //     (b) 방어 없는 컨슈머(JsonDeserializer 직접)를 띄운다.
    //     (c) 반복되는 로그 두 줄을 그대로 적는다.
    //     (d) kcg --describe 를 "직후"와 "5분 뒤" 두 번 찍어 LAG 을 기록한다.
    //     (e) 앱을 재시작해도 같은 오프셋에서 죽는지 확인하고, 그 이유를 적는다.
    //
    //   이 문제는 코드가 아니라 "재현 절차와 관찰 결과"를 채우는 문제입니다.
    // ========================================================================

    @Component
    @Profile("step04-ex3")
    public static class Ex3PoisonPill implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex3PoisonPill.class);

        private final KafkaTemplate<String, byte[]> raw;

        public Ex3PoisonPill(KafkaTemplate<String, byte[]> raw) {
            this.raw = raw;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 여기에 작성: (a) 깨진 값을 1건 발행
            //   raw.send(TOPIC, "ORD-BAD", "...".getBytes(StandardCharsets.UTF_8));
            //   raw.flush();
            log.warn("[ex3] 포이즌 필을 심었습니다.");
        }

        // 여기에 작성: (c) 반복되는 로그 두 줄
        //   WARN  ...
        //   ERROR ...

        // 여기에 작성: (d) LAG 관찰 결과
        //   [직후]
        //   GROUP  TOPIC  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
        //   ...
        //   [5분 뒤]
        //   ...

        // 여기에 작성: (e) 재시작해도 같은 자리에서 죽는 이유 (3줄 이내)
        //
    }

    @Configuration
    @Profile("step04-ex3")
    public static class Ex3Config {

        @Bean
        public KafkaTemplate<String, byte[]> rawKafkaTemplate() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> ex3ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);  // 무방비
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
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
    // 문제 4. 문제 3 의 상황을 ErrorHandlingDeserializer 로 고치세요.
    //
    //   요구사항
    //     (a) 값 역직렬화기를 ErrorHandlingDeserializer 로 감싸고 delegate 를 지정한다.
    //     (b) "키" 역직렬화기도 함께 감싼다. 왜 필요한지 주석으로 적는다.
    //     (c) 리스너에서 실패한 레코드의 "원본 바이트"를 로그로 출력한다.
    //     (d) 오프셋을 earliest 로 리셋하고 다시 실행해, LAG 이 0 으로 수렴하는지 확인한다.
    //
    //   실행 : kcg --group s04-inventory --topic s04-orders --reset-offsets --to-earliest --execute
    //          ./gradlew bootRun --args='--spring.profiles.active=step04-ex4'
    // ========================================================================

    @Configuration
    @Profile("step04-ex4")
    public static class Ex4Config {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> ex4ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
            props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderCreated.class.getName());
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.order.domain");

            // 여기에 작성: (a) 값 + (b) 키를 ErrorHandlingDeserializer 로 감싸고 delegate 지정 (4줄)
            // props.put(...);
            // props.put(...);
            // props.put(...);
            // props.put(...);
            //
            // (b) 키도 감싸야 하는 이유:
            //

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);
            return factory;
        }
    }

    @Component
    @Profile("step04-ex4")
    public static class Ex4Listener {

        private static final Logger log = LoggerFactory.getLogger(Ex4Listener.class);

        @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = "ex4ListenerFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            // 여기에 작성: (c) 헤더에서 DeserializationException 을 꺼내
            //             원본 바이트를 UTF-8 문자열로 출력한 뒤 return 한다.
            //             (힌트: SerializationUtils.getExceptionFromHeader,
            //                    SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER,
            //                    DeserializationException.getData())

            OrderCreated event = record.value();
            log.info("[{}] p={} off={} {} {} x{}",
                    GROUP, record.partition(), record.offset(),
                    event.orderId(), event.sku(), event.quantity());
        }
    }


    // ========================================================================
    // 문제 5. trusted packages 위반을 재현하고, "*" 를 쓰지 않고 해결하세요.
    //
    //   요구사항
    //     (a) 아래 설정을 그대로 실행하면 어떤 예외가 나는지 메시지 전문을 적는다.
    //     (b) 예외 메시지가 권하는 "(*)" 를 쓰지 않고 해결한다.
    //     (c) "*" 가 왜 위험한지 2줄로 적는다.
    //     (d) 이 문제 자체가 발생하지 않게 만드는 "더 나은 답"이 무엇인지 적는다.
    //         (힌트: 문제 1 의 구성)
    //
    //   실행 : --spring.profiles.active=step04-ex5
    // ========================================================================

    @Configuration
    @Profile("step04-ex5")
    public static class Ex5Config {

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> ex5ListenerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            // TRUSTED_PACKAGES 를 일부러 비워 둡니다. 실행하면 예외가 나는 것이 정상입니다.

            // 여기에 작성: (b) "*" 를 쓰지 않는 해결책
            // props.put(...);

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }

        // 여기에 작성: (a) 예외 메시지 전문
        //

        // 여기에 작성: (c) "*" 가 위험한 이유 2줄
        //

        // 여기에 작성: (d) 더 나은 답
        //
    }


    // ========================================================================
    // 문제 6. 필드가 추가된 신버전 이벤트를 구버전 컨슈머가 읽게 만드세요.
    //
    //   상황
    //     프로듀서가 OrderCreated 에 couponCode 필드를 추가해 배포했습니다.
    //     구버전 클래스를 쓰는 컨슈머가 UnrecognizedPropertyException 으로 멈춥니다.
    //
    //   요구사항
    //     (a) 아래 v2Json 을 구버전 OrderCreated 로 역직렬화하는 mapper 를 만든다.
    //     (b) 같은 목적을 달성하는 방법 3가지를 주석으로 나열한다.
    //     (c) Spring Boot 의 ObjectMapper 빈을 주입받으면 왜 이 문제가 안 생기는지,
    //         그런데 왜 여전히 위험한지를 적는다.
    //
    //   실행 : --spring.profiles.active=step04-ex6
    // ========================================================================

    @Component
    @Profile("step04-ex6")
    public static class Ex6SchemaEvolution implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex6SchemaEvolution.class);

        private static final String V2_JSON = """
                {"orderId":"ORD-0021","customerId":1021,"sku":"SKU-001","quantity":2,\
                "amount":10000,"createdAt":"2025-01-01T00:21:00Z","couponCode":"WELCOME10"}""";

        @Override
        public void run(ApplicationArguments args) throws Exception {
            log.info("[ex6] v2 JSON = {}", V2_JSON);

            // 여기에 작성: (a) 미지의 필드를 무시하는 mapper 로 역직렬화
            // ObjectMapper mapper = ...;
            // OrderCreated event = mapper.readValue(V2_JSON, OrderCreated.class);
            // log.info("[ex6] 성공: {} {} x{}", event.orderId(), event.sku(), event.quantity());

            // 여기에 작성: (b) 같은 목적을 달성하는 방법 3가지
            //   1.
            //   2.
            //   3.

            // 여기에 작성: (c) Boot mapper 를 주입받으면 왜 괜찮고, 왜 여전히 위험한가
            //
        }
    }
}
