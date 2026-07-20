package com.example.order.step01;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 메시지 : Solution (정답과 해설)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step01/Solution.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step01sol'
 *   ./gradlew bootRun --args='--spring.profiles.active=step01sol,step01sol-latest'
 *   ./gradlew bootRun --args='--spring.profiles.active=step01sol,step01sol-strict'
 *
 * Exercise 를 직접 풀어 본 뒤에 여세요.
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
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Solution {

    private Solution() {
    }

    // ========================================================================
    // 정답 1 — payments 토픽 발행 + s01-payment 그룹 수신
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - 토픽명을 코드에 하드코딩하지 않고 @Value("${app.topic.payments}") 로 받는 이유는
     *    단순한 취향이 아닙니다. Kafka 는 토픽명 오타를 예외로 알려 주지 않습니다.
     *    브로커의 auto.create.topics.enable 이 켜져 있으면 새 토픽이 조용히 생기고,
     *    발행은 '성공'합니다. 토픽명을 한 곳(application.yml)에서만 관리하면
     *    프로듀서와 컨슈머가 같은 문자열을 보게 되어 이 사고 유형이 원천 차단됩니다.
     *
     *  - send() 는 CompletableFuture 를 리턴하고 즉시 반환됩니다. whenComplete 를
     *    붙이지 않으면 브로커가 거절해도 애플리케이션은 아무것도 모릅니다.
     *    Step 01 단계에서부터 "발행 결과를 반드시 본다"를 습관으로 만드세요.
     *
     *  - payments 는 파티션이 1개입니다. 그래서 기동 로그가
     *      s01-payment: partitions assigned: [payments-0]
     *    한 줄로 끝납니다. orders(3파티션)는 세 개가 한 줄에 나열됩니다.
     *    partitions assigned 로그는 "무엇을 몇 개 할당받았는가"를 보여 주는 창이며,
     *    여기가 비어 있으면([]) 리스너는 살아 있어도 아무것도 받지 못합니다.
     */
    @Component
    @Profile("step01sol")
    @Order(10)
    public static class Sol1PaymentPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol1PaymentPublisher.class);

        private final KafkaTemplate<String, OrderCreated> kafkaTemplate;
        private final String paymentsTopic;

        public Sol1PaymentPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate,
                                    @Value("${app.topic.payments}") String paymentsTopic) {
            this.kafkaTemplate = kafkaTemplate;
            this.paymentsTopic = paymentsTopic;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated event = OrderCreated.of(101);

            kafkaTemplate.send(paymentsTopic, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[정답1] 발행 실패 key={}", event.orderId(), ex);
                            return;
                        }
                        log.info("[정답1] 발행 성공 {}-{}@{} key={}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                event.orderId());
                    });
            kafkaTemplate.flush();
        }
    }

    @Component
    @Profile("step01sol")
    public static class Sol1PaymentListener {

        private static final Logger log = LoggerFactory.getLogger(Sol1PaymentListener.class);

        @KafkaListener(
                id = "s01sol-payment",
                topics = "${app.topic.payments}",
                groupId = "s01-payment",
                concurrency = "1")
        public void onPayment(ConsumerRecord<String, OrderCreated> record) {
            log.info("[정답1] 결제 수신 {}-{}@{} key={} amount={}",
                    record.topic(), record.partition(), record.offset(),
                    record.key(), record.value().amount());
        }
    }

    // ========================================================================
    // 정답 2 — 컨슈머 그룹 두 개는 같은 메시지를 각자 전부 받는다
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - groupId 만 다르게 준 리스너를 하나 더 붙이면 끝입니다. 코드에 특별한 것은 없습니다.
     *    중요한 건 결과입니다. 3건을 발행하면 로그가 6줄 나옵니다.
     *    s01-inventory 가 3건, s01-notification 이 3건. 이것이 Kafka 의 pub/sub 입니다.
     *
     *  - 오프셋은 (그룹, 토픽, 파티션) 조합마다 따로 관리됩니다. 그래서
     *      kcg --describe --group s01-inventory
     *      kcg --describe --group s01-notification
     *    의 CURRENT-OFFSET 은 서로 완전히 독립적입니다. 한쪽을 리셋해도
     *    다른 쪽은 영향을 받지 않습니다.
     *
     *  - 반대로 groupId 를 '같게' 주고 리스너를 둘 붙이면 어떻게 될까요?
     *    두 리스너가 한 그룹의 두 멤버가 되어 파티션을 나눠 갖습니다.
     *    각 메시지는 둘 중 하나에게만 갑니다. 총 로그는 6줄이 아니라 3줄입니다.
     *    "왜 절반만 처리되지?" 라는 질문의 절반은 여기서 나옵니다.
     *    파티션이 3개인데 같은 그룹 멤버가 4개면 한 명은 아무것도 못 받고 놉니다(Step 03).
     */
    @Component
    @Profile("step01sol")
    public static class Sol2NotificationListener {

        private static final Logger log = LoggerFactory.getLogger(Sol2NotificationListener.class);

        @KafkaListener(
                id = "s01sol-notification",
                topics = "${app.topic.orders}",
                groupId = "s01-notification",
                concurrency = "1")
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            log.info("[정답2] 알림 발송 {}-{}@{} key={} customerId={}",
                    record.topic(), record.partition(), record.offset(),
                    record.key(), record.value().customerId());
        }
    }

    // ========================================================================
    // 정답 3 — 자동 설정 빈 목록
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - getBeanNamesForType 은 인터페이스로 조회해야 합니다. ProducerFactory 는
     *    인터페이스이고 실제 구현은 DefaultKafkaProducerFactory 입니다.
     *    DefaultKafkaProducerFactory.class 로 조회해도 지금은 찾아지지만,
     *    커스텀 구현으로 바꾸는 순간 조회가 깨집니다. 인터페이스로 조회하세요.
     *
     *  - KafkaTransactionManager 가 목록에 없는 이유:
     *    KafkaAutoConfiguration 의 해당 @Bean 에는
     *      @ConditionalOnProperty(name = "spring.kafka.producer.transaction-id-prefix")
     *    가 붙어 있습니다. application.yml 에 transaction-id-prefix 를 안 적었으므로
     *    조건이 불충족이고, 빈은 '조용히' 만들어지지 않습니다.
     *    이것이 Spring Boot 자동 설정의 기본 성격입니다. 실패가 아니라 부재입니다.
     *    Step 09 에서 transaction-id-prefix 를 넣는 순간 이 빈이 나타납니다.
     *
     *  - ctx.getType(name) 이 null 을 리턴할 수 있다는 점에 유의하세요.
     *    빈 정의만 있고 아직 타입이 확정되지 않은 경우입니다. 방어 코드를 넣습니다.
     */
    @Component
    @Profile("step01sol")
    @Order(20)
    public static class Sol3BeanReport implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol3BeanReport.class);

        private final ApplicationContext ctx;

        public Sol3BeanReport(ApplicationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run(ApplicationArguments args) {
            Map<String, Class<?>> targets = new LinkedHashMap<>();
            targets.put("KafkaTemplate", KafkaTemplate.class);
            targets.put("ProducerFactory", ProducerFactory.class);
            targets.put("ConsumerFactory", ConsumerFactory.class);
            targets.put("KafkaListenerContainerFactory", KafkaListenerContainerFactory.class);
            targets.put("KafkaAdmin", KafkaAdmin.class);
            targets.put("KafkaListenerEndpointRegistry", KafkaListenerEndpointRegistry.class);
            targets.put("KafkaTransactionManager", KafkaTransactionManager.class);

            targets.forEach((label, type) -> {
                String[] names = ctx.getBeanNamesForType(type);
                if (names.length == 0) {
                    log.info("[정답3] {} → (없음) 조건이 충족되지 않아 등록되지 않았습니다",
                            "%-32s".formatted(label));
                    return;
                }
                for (String name : names) {
                    Class<?> impl = ctx.getType(name);
                    log.info("[정답3] {} → {} ({})", "%-32s".formatted(label), name,
                            impl == null ? "타입 미확정" : impl.getSimpleName());
                }
            });
        }
    }

    // ========================================================================
    // 정답 4 — auto-offset-reset = latest 로 메시지 유실 재현
    // ========================================================================
    /*
     * 왜 이 답인가 — 이 스텝에서 가장 중요한 정답입니다.
     *
     *  - auto.offset.reset 은 "커밋된 오프셋이 없을 때 어디서부터 읽을지"만 정합니다.
     *    항상 적용되는 값이 아닙니다. 이 한 문장을 놓치면 실습 결과가 뒤죽박죽이 됩니다.
     *
     *  - latest 로 두고 새 그룹(s01-latest)으로 처음 붙으면, 컨슈머는
     *    "지금 이 순간의 LOG-END-OFFSET" 부터 읽기 시작합니다.
     *    그러니 앱을 켜기 전에 넣어 둔 메시지는 영원히 안 보입니다.
     *    로그는 완벽하게 깨끗합니다. 예외도 없고 경고도 없습니다.
     *    partitions assigned 도 정상적으로 찍힙니다. 그래서 코드를 의심하게 됩니다.
     *
     *  - 앱을 켠 상태에서 넣은 메시지는 정상적으로 들어옵니다.
     *    그리고 그 순간 s01-latest 그룹에 오프셋이 '커밋'됩니다.
     *    이제부터는 auto.offset.reset 이 아예 개입하지 않습니다.
     *    앱을 껐다 켠 뒤 메시지를 넣으면, 커밋된 오프셋부터 이어 읽으므로
     *    latest 인데도 "앱이 꺼진 동안 들어온 메시지"가 보입니다.
     *    문제 4(d)의 답이 이것입니다. 값이 바뀐 게 아니라 조건이 사라진 것입니다.
     *
     *  - 진단 방법: kcg --describe --group s01-latest 를 실행해서
     *    그 그룹이 아예 없다고 나오면 '한 번도 커밋한 적 없는' 상태입니다.
     *    이 경우에만 auto.offset.reset 이 작동합니다.
     *
     *  - 운영 기본값이 latest 인 것은 이유가 있습니다. 새 서비스를 붙일 때
     *    earliest 면 몇 달치 과거 메시지가 한꺼번에 쏟아집니다.
     *    "학습에는 earliest, 운영에는 대개 latest, 단 반드시 의식하고 고를 것" 입니다.
     */
    @Configuration
    @Profile("step01sol-latest")
    public static class Sol4LatestConfig {

        @Bean
        public ConsumerFactory<String, OrderCreated> latestConsumerFactory(KafkaProperties props) {
            Map<String, Object> config = new HashMap<>(props.buildConsumerProperties());
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
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
    @Profile("step01sol-latest")
    public static class Sol4LatestListener {

        private static final Logger log = LoggerFactory.getLogger(Sol4LatestListener.class);

        @KafkaListener(
                id = "s01sol-latest",
                topics = "${app.topic.orders}",
                groupId = "s01-latest",
                containerFactory = "latestContainerFactory")
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            log.info("[정답4] 수신 {}-{}@{} key={}",
                    record.topic(), record.partition(), record.offset(), record.key());
        }
    }

    // ========================================================================
    // 정답 5 — 추적 가능한 리스너 로그
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - "orders-1@42" 라는 좌표 하나가 있으면 그 레코드를 언제든 다시 찾아갈 수 있습니다.
     *      kcc --topic orders --partition 1 --offset 42 --max-messages 1
     *    반대로 좌표가 없으면 "그 메시지가 처리됐는지"를 사후에 증명할 방법이 없습니다.
     *
     *  - 구분자를 '-' 와 '@' 로 고정하는 것이 중요합니다. Spring Kafka 자신이
     *    에러 로그에서 이 포맷을 씁니다.
     *      Backoff ... exhausted for orders-1@42
     *    애플리케이션 로그와 프레임워크 로그의 좌표 포맷이 같으면
     *    한 번의 grep 으로 양쪽을 동시에 훑을 수 있습니다.
     *
     *  - record.timestamp() 는 기본적으로 프로듀서가 찍은 시각(CreateTime)입니다.
     *    브로커 도착 시각을 원하면 토픽 설정 message.timestamp.type 을 바꿔야 합니다.
     *    "내 로그 시각 - 레코드 타임스탬프" 가 그 레코드의 실제 지연(end-to-end lag)입니다.
     *    컨슈머 랙(건수)과 지연(시간)은 다른 지표이며, 둘 다 필요합니다(Step 12).
     */
    @Component
    @Profile("step01sol")
    public static class Sol5TraceableListener {

        private static final Logger log = LoggerFactory.getLogger(Sol5TraceableListener.class);

        @KafkaListener(
                id = "s01sol-trace",
                topics = "${app.topic.orders}",
                groupId = "s01-trace",
                concurrency = "1")
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            Instant produced = Instant.ofEpochMilli(record.timestamp());
            long lagMs = System.currentTimeMillis() - record.timestamp();

            log.info("[정답5] {}-{}@{} key={} ts={} lagMs={}",
                    record.topic(), record.partition(), record.offset(),
                    record.key(), produced, lagMs);
        }
    }

    // ========================================================================
    // 정답 6 — 오타 토픽을 '조용히 성공'하지 않게 만들기
    // ========================================================================
    /*
     * 왜 이 답인가 — 그리고 이 문제의 진짜 답은 "완전히 막을 수는 없다" 입니다.
     *
     *  (1) 컨슈머 쪽: allow.auto.create.topics = false
     *      컨슈머가 없는 토픽을 구독할 때 브로커에 토픽 생성을 요청하지 않게 합니다.
     *      여기에 listener.missing-topics-fatal = true 를 더하면,
     *      리스너가 구독하는 토픽이 없을 때 기동 자체가 실패합니다.
     *      "오타 난 토픽을 구독하는" 사고는 이걸로 잡힙니다.
     *      기동 로그:
     *        IllegalStateException: Topic(s) [order] is/are not present and missingTopicsFatal is true
     *
     *  (2) 프로듀서 쪽: 막을 수 있는 클라이언트 설정이 없습니다.
     *      프로듀서는 메타데이터를 조회할 뿐이고, 없는 토픽을 만들지 말지는
     *      전적으로 브로커의 auto.create.topics.enable 이 결정합니다.
     *      즉 "오타 난 토픽으로 send 하는" 사고는 애플리케이션 설정만으로는 못 막습니다.
     *
     *  (3) 그래서 실무의 정답은 셋입니다.
     *      - 운영 브로커에서는 auto.create.topics.enable = false 로 둔다 (가장 확실)
     *      - 토픽명을 문자열 리터럴로 쓰지 않는다. 설정값이나 상수 한 곳에서만 관리한다
     *      - NewTopic 빈으로 쓸 토픽을 선언해 두고, 그 목록 밖의 토픽은 코드 리뷰에서 잡는다
     *
     *  (4) 아래 코드는 (1)을 구현하고, 프로듀서 쪽은 여전히 성공한다는 사실을
     *      로그로 드러내 보여 줍니다. 이 비대칭이 이 문제의 학습 포인트입니다.
     */
    @Configuration
    @Profile("step01sol-strict")
    public static class Sol6StrictConfig {

        @Bean
        public ConsumerFactory<String, OrderCreated> strictConsumerFactory(KafkaProperties props) {
            Map<String, Object> config = new HashMap<>(props.buildConsumerProperties());
            config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
            return new DefaultKafkaConsumerFactory<>(config);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> strictContainerFactory(
                ConsumerFactory<String, OrderCreated> strictConsumerFactory) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(strictConsumerFactory);
            factory.setConcurrency(1);
            // 토픽이 없으면 기동을 실패시킵니다. application.yml 의
            // spring.kafka.listener.missing-topics-fatal 은 기본 컨테이너 팩토리에만
            // 적용되므로, 직접 만든 팩토리에는 이렇게 코드로 지정합니다.
            factory.getContainerProperties().setMissingTopicsFatal(true);
            return factory;
        }
    }

    @Component
    @Profile("step01sol-strict")
    @Order(30)
    public static class Sol6TypoTopicPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol6TypoTopicPublisher.class);

        private static final String TYPO_TOPIC = "order";

        private final KafkaTemplate<String, OrderCreated> kafkaTemplate;

        public Sol6TypoTopicPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate) {
            this.kafkaTemplate = kafkaTemplate;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated event = OrderCreated.of(99);

            kafkaTemplate.send(TYPO_TOPIC, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[정답6] 발행 실패 — 브로커가 토픽 자동 생성을 거부했습니다", ex);
                            return;
                        }
                        log.warn("[정답6] 프로듀서는 여전히 성공합니다: {}-{}@{}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                        log.warn("[정답6] 프로듀서 쪽 오타는 클라이언트 설정으로 못 막습니다. "
                                + "브로커의 auto.create.topics.enable=false 가 유일한 방어선입니다");
                    });
            kafkaTemplate.flush();
        }
    }

    // ========================================================================
    // 정답 7 — 오타 난 설정 프로퍼티는 WARN 한 줄로 끝난다
    // ========================================================================
    /*
     * 왜 이 답인가
     *
     *  - 찾아야 할 로그 한 줄은 이것입니다.
     *      WARN  o.a.k.c.p.ProducerConfig :
     *            The configuration 'lingerms' was supplied but isn't a known config.
     *
     *  - Kafka 클라이언트는 자기가 모르는 설정 키를 받으면 예외를 던지지 않습니다.
     *    AbstractConfig 가 사용되지 않은 키를 모아 WARN 으로 한 번 알리고 끝입니다.
     *    설계 의도는 호환성입니다. 신버전 설정을 구버전 클라이언트에 줘도 죽지 않아야 하고,
     *    인터셉터·시리얼라이저가 자기 설정을 자유롭게 끼워 넣을 수 있어야 하니까요.
     *
     *  - 위험한 이유는 세 가지입니다.
     *      1. WARN 이라서 대부분의 운영 로그 수집기에서 알림이 안 갑니다.
     *      2. 기동 시 딱 한 번만 나오고, 그 앞뒤가 ProducerConfig 설정 덤프라
     *         수십 줄에 파묻힙니다.
     *      3. 증상이 "안 됨"이 아니라 "기본값으로 동작함"입니다.
     *         linger.ms 를 5 로 주려다 오타를 내면 기본값 0 으로 돕니다.
     *         에러가 없으니 성능이 이상해도 설정을 의심하지 않게 됩니다.
     *
     *  - 방어책
     *      - 기동 로그에서 "isn't a known config" 를 grep 하는 것을 배포 체크리스트에 넣습니다.
     *      - 가능하면 ProducerConfig.LINGER_MS_CONFIG 같은 상수를 코드에서 씁니다.
     *        오타가 컴파일 에러가 됩니다. yml 문자열은 오타가 런타임 침묵이 됩니다.
     *      - Boot 의 축약 프로퍼티(spring.kafka.producer.acks 등)는 오타 나면
     *        Boot 가 잡아 줍니다. properties: 아래 원본 키만 무방비입니다.
     */
    @Component
    @Profile("step01sol")
    @Order(40)
    public static class Sol7UnknownConfigProbe implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol7UnknownConfigProbe.class);

        private static final String EXPECTED_WARNING =
                "The configuration 'lingerms' was supplied but isn't a known config.";

        @Override
        public void run(ApplicationArguments args) {
            log.info("[정답7] 기동 로그에서 찾아야 할 한 줄: {}", EXPECTED_WARNING);
            log.info("[정답7] 확인 명령: ./gradlew bootRun ... 2>&1 | grep \"isn't a known config\"");
        }
    }
}
