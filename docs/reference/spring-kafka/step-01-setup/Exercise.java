package com.example.order.step01;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 메시지 : Exercise (문제지)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step01/Exercise.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step01ex'
 *
 * 문제별 보조 프로필
 *   step01ex-latest   문제 4 (auto-offset-reset=latest 로 메시지 유실 재현)
 *   step01ex-strict   문제 6 (오타 토픽 자동 생성 막기)
 *   step01ex-typo     문제 7 (오타 난 설정 프로퍼티가 무시되는 것 확인)
 *
 * 규칙
 *   - "여기에 작성:" 아래를 채우세요. 그 밖의 시그니처는 바꾸지 않습니다.
 *   - 매 문제 후 반드시 콘솔 로그와 CLI 출력을 눈으로 확인합니다.
 *   - 컨슈머 그룹 이름은 전부 s01- 접두사를 씁니다.
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

public final class Exercise {

    private Exercise() {
    }

    // ========================================================================
    // 문제 1. payments 토픽으로 발행하고, 전용 컨슈머 그룹으로 받으세요.
    //
    //  요구사항
    //   (a) 기동 시 payments 토픽으로 OrderCreated.of(101) 한 건을 발행합니다.
    //       토픽명은 하드코딩하지 말고 application.yml 의 app.topic.payments 를 씁니다.
    //   (b) 발행 결과(topic-partition@offset)를 로그로 남깁니다.
    //   (c) groupId = "s01-payment" 인 @KafkaListener 로 받고,
    //       역시 topic-partition@offset 을 남깁니다.
    //   (d) payments 는 파티션이 1개입니다. 기동 로그의 partitions assigned 가
    //       orders 때와 어떻게 다른지 확인하세요.
    // ========================================================================
    @Component
    @Profile("step01ex")
    @Order(10)
    public static class Ex1PaymentPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex1PaymentPublisher.class);

        private final KafkaTemplate<String, OrderCreated> kafkaTemplate;
        private final String paymentsTopic;

        public Ex1PaymentPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate,
                                   @Value("${app.topic.payments}") String paymentsTopic) {
            this.kafkaTemplate = kafkaTemplate;
            this.paymentsTopic = paymentsTopic;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 여기에 작성: payments 토픽으로 OrderCreated.of(101) 발행 + 결과 로깅
            log.info("문제 1 미구현 (topic={})", paymentsTopic);
        }
    }

    @Component
    @Profile("step01ex")
    public static class Ex1PaymentListener {

        private static final Logger log = LoggerFactory.getLogger(Ex1PaymentListener.class);

        // 여기에 작성: @KafkaListener 를 붙여 payments 토픽을 s01-payment 그룹으로 구독
        public void onPayment(ConsumerRecord<String, OrderCreated> record) {
            log.info("문제 1 미구현 record={}", record);
        }
    }

    // ========================================================================
    // 문제 2. 같은 토픽을 두 컨슈머 그룹이 각각 전부 소비하는 것을 확인하세요.
    //
    //  요구사항
    //   (a) 아래 리스너를 groupId = "s01-notification" 으로 orders 토픽에 붙입니다.
    //   (b) Practice 의 s01-inventory 리스너와 동시에 켜고, 발행한 3건을
    //       두 그룹이 각각 3건씩 = 총 6줄 로그로 받는지 확인합니다.
    //   (c) kcg --list 로 두 그룹이 모두 보이는지, kcg --describe 로
    //       각 그룹의 CURRENT-OFFSET 이 독립적으로 관리되는지 확인합니다.
    //
    //  생각해 볼 것: 같은 그룹 이름을 쓰는 리스너를 하나 더 붙이면 결과가 어떻게 달라질까요?
    // ========================================================================
    @Component
    @Profile("step01ex")
    public static class Ex2NotificationListener {

        private static final Logger log = LoggerFactory.getLogger(Ex2NotificationListener.class);

        // 여기에 작성: @KafkaListener(topics = ..., groupId = "s01-notification", concurrency = "1")
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            log.info("문제 2 미구현 record={}", record);
        }
    }

    // ========================================================================
    // 문제 3. 자동 설정이 만들어 준 빈을 직접 조회해 출력하세요.
    //
    //  요구사항
    //   (a) ApplicationContext.getBeanNamesForType 으로 아래 빈 이름을 출력합니다.
    //       KafkaTemplate / ProducerFactory / ConsumerFactory /
    //       KafkaListenerContainerFactory / KafkaAdmin / KafkaListenerEndpointRegistry
    //   (b) KafkaTransactionManager 는 왜 목록에 없는지 주석으로 한 줄 적으세요.
    //   (c) ProducerFactory 의 실제 구현 클래스 이름(getType)까지 함께 출력합니다.
    // ========================================================================
    @Component
    @Profile("step01ex")
    @Order(20)
    public static class Ex3BeanReport implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex3BeanReport.class);

        private final ApplicationContext ctx;

        public Ex3BeanReport(ApplicationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 여기에 작성: 위 6종 + KafkaTransactionManager 를 조회해 출력
            log.info("문제 3 미구현 (빈 총 개수={})", ctx.getBeanDefinitionCount());
        }
    }

    // ========================================================================
    // 문제 4. auto-offset-reset = latest 로 "메시지 유실"을 재현하세요.
    //
    //  요구사항
    //   (a) 아래 팩토리에서 AUTO_OFFSET_RESET_CONFIG 를 "latest" 로 설정합니다.
    //       나머지 설정은 KafkaProperties(=application.yml)에서 그대로 가져옵니다.
    //   (b) 리스너에 containerFactory = "latestContainerFactory" 를 지정하고
    //       groupId 는 "s01-latest" 로 둡니다. (한 번도 커밋한 적 없는 새 그룹)
    //   (c) 실행 절차
    //        1. 앱을 끈 상태에서 콘솔 프로듀서로 orders 에 메시지를 3건 넣습니다.
    //        2. 앱을 켭니다.  → 리스너에 아무것도 안 들어옵니다.
    //        3. 앱을 켠 채로 다시 3건을 넣습니다. → 이번엔 들어옵니다.
    //   (d) 그 뒤 앱을 껐다 켜고 1번을 반복하면 이번엔 메시지가 들어옵니다.
    //       왜 그런지 주석으로 설명하세요. (힌트: 커밋된 오프셋의 유무)
    // ========================================================================
    @Configuration
    @Profile("step01ex-latest")
    public static class Ex4LatestConfig {

        @Bean
        public ConsumerFactory<String, OrderCreated> latestConsumerFactory(KafkaProperties props) {
            // Boot 3.2.x 는 인자 없는 buildConsumerProperties() 입니다.
            // (SslBundles 를 받는 오버로드는 Boot 3.4 부터입니다.)
            Map<String, Object> config = new HashMap<>(props.buildConsumerProperties());
            // 여기에 작성: ConsumerConfig.AUTO_OFFSET_RESET_CONFIG 를 "latest" 로
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // ← 고치세요
            return new DefaultKafkaConsumerFactory<>(config);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> latestContainerFactory(
                ConsumerFactory<String, OrderCreated> latestConsumerFactory) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(latestConsumerFactory);
            factory.setConcurrency(1);
            return factory;
        }
    }

    @Component
    @Profile("step01ex-latest")
    public static class Ex4LatestListener {

        private static final Logger log = LoggerFactory.getLogger(Ex4LatestListener.class);

        // 여기에 작성: containerFactory = "latestContainerFactory", groupId = "s01-latest"
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            log.info("문제 4 미구현 record={}", record);
        }
    }

    // ========================================================================
    // 문제 5. 리스너 로그를 "추적 가능한" 형태로 만드세요.
    //
    //  요구사항
    //   (a) topic-partition@offset, key, 그리고 레코드 타임스탬프를 한 줄에 남깁니다.
    //   (b) 포맷은 "orders-1@42 key=ORD-0001 ts=..." 처럼 사람이 grep 할 수 있게 합니다.
    //   (c) 로그 한 줄만 보고 kcc / kcg 로 같은 레코드를 찾아갈 수 있어야 합니다.
    // ========================================================================
    @Component
    @Profile("step01ex")
    public static class Ex5TraceableListener {

        private static final Logger log = LoggerFactory.getLogger(Ex5TraceableListener.class);

        @KafkaListener(
                id = "s01ex-trace",
                topics = "${app.topic.orders}",
                groupId = "s01-trace",
                concurrency = "1")
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            // 여기에 작성: topic-partition@offset + key + timestamp 를 한 줄로
            log.info("문제 5 미구현");
        }
    }

    // ========================================================================
    // 문제 6. 오타 난 토픽으로 보냈을 때 '조용히 성공'하지 않게 만드세요.
    //
    //  요구사항
    //   (a) 존재하지 않는 토픽 "order" 로 발행을 시도합니다.
    //   (b) 기본 상태에서는 토픽이 자동 생성되며 성공합니다. 먼저 그것을 확인하세요.
    //   (c) 브로커의 auto.create.topics.enable 을 끄지 않고, 애플리케이션 쪽 설정만으로
    //       "실패하게" 만드는 방법을 찾아 적용하세요.
    //       힌트 1: 컨슈머에는 allow.auto.create.topics 프로퍼티가 있습니다.
    //       힌트 2: 리스너 컨테이너에는 missing-topics-fatal 이 있습니다.
    //       힌트 3: 프로듀서는 어떤가요? 여기서 얻는 교훈이 이 문제의 핵심입니다.
    //   (d) 확인 후 kt --delete --topic order 로 정리합니다.
    // ========================================================================
    @Component
    @Profile("step01ex-strict")
    @Order(30)
    public static class Ex6TypoTopicPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex6TypoTopicPublisher.class);

        private final KafkaTemplate<String, OrderCreated> kafkaTemplate;

        public Ex6TypoTopicPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate) {
            this.kafkaTemplate = kafkaTemplate;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 여기에 작성: "order" 토픽으로 발행하고 결과를 로깅
            log.info("문제 6 미구현 template={}", kafkaTemplate.getDefaultTopic());
        }
    }

    // ========================================================================
    // 문제 7. 오타 난 설정 프로퍼티가 어떻게 처리되는지 확인하세요.
    //
    //  요구사항
    //   (a) application.yml 의 spring.kafka.producer.properties 아래에
    //       lingerms: 5   (점이 빠진 오타) 를 추가합니다.
    //   (b) 앱을 기동해 무슨 일이 생기는지 확인합니다. 예외가 납니까?
    //   (c) 기동 로그에서 그 오타를 알려 주는 단 한 줄을 찾아 아래 상수에 적으세요.
    //   (d) 그 한 줄이 WARN 레벨이라는 사실이 왜 위험한지 주석으로 적으세요.
    // ========================================================================
    @Component
    @Profile("step01ex-typo")
    @Order(40)
    public static class Ex7UnknownConfigProbe implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex7UnknownConfigProbe.class);

        // 여기에 작성: 기동 로그에서 찾은 경고 한 줄을 그대로 붙여넣으세요.
        private static final String EXPECTED_WARNING = "(여기에 작성)";

        @Override
        public void run(ApplicationArguments args) {
            log.info("문제 7 — 기동 로그에서 찾아야 할 경고: {}", EXPECTED_WARNING);
        }
    }
}
