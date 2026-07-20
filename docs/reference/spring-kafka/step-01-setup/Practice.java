package com.example.order.step01;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 메시지 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step01/Practice.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step01'
 *
 * 함정 재현용 보조 프로필 (필요할 때만 함께 켭니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step01,step01-typo'
 *       → [1-8] 오타 토픽(order)으로 발행. 토픽이 조용히 새로 생깁니다.
 *   ./gradlew bootRun --args='--spring.profiles.active=step01,step01-enable-kafka'
 *       → [1-5] @EnableKafka 를 직접 붙였을 때 무슨 일이 생기는지 확인.
 *
 * 실행 전/후에 확인할 CLI (project/ 의 alias 를 등록해 두었다고 가정)
 *   kt  --list
 *   kt  --describe --topic orders
 *   kcg --describe --group s01-inventory
 *   kcc --topic orders --from-beginning --property print.key=true --property print.partition=true
 *
 * 오프셋을 처음부터 다시 읽고 싶다면 (앱을 먼저 종료할 것)
 *   kcg --group s01-inventory --topic orders --reset-offsets --to-earliest --execute
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Step 01 의 모든 예제를 담은 단일 파일입니다.
 * 각 nested static class 는 본문 절 번호와 1:1 로 대응합니다.
 */
public final class Practice {

    private Practice() {
        // 유틸리티 홀더. 인스턴스화하지 않습니다.
    }

    // ========================================================================
    // [1-2] 첫 발행 — KafkaTemplate 을 주입받아 orders 토픽으로 3건 보낸다
    // ========================================================================
    //
    // KafkaAutoConfiguration 이 만들어 준 kafkaTemplate 빈은 KafkaTemplate<?, ?> 로
    // 선언되어 있지만, 와일드카드는 어떤 구체 제네릭에도 대입 가능하므로
    // KafkaTemplate<String, OrderCreated> 로 주입받을 수 있습니다.
    // 실제 직렬화기는 application.yml 의 key-serializer / value-serializer 가 정합니다.
    //
    @Component
    @Profile("step01")
    @Order(20)
    public static class FirstPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(FirstPublisher.class);

        private final KafkaTemplate<String, OrderCreated> kafkaTemplate;
        private final String ordersTopic;

        public FirstPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate,
                              @Value("${app.topic.orders}") String ordersTopic) {
            this.kafkaTemplate = kafkaTemplate;
            this.ordersTopic = ordersTopic;
        }

        @Override
        public void run(ApplicationArguments args) {
            log.info("[1-2] {} 토픽으로 OrderCreated 3건 발행을 시작합니다", ordersTopic);

            for (int seq = 1; seq <= 3; seq++) {
                OrderCreated event = OrderCreated.of(seq);

                // 키를 orderId 로 줍니다. 같은 주문은 항상 같은 파티션으로 갑니다.
                CompletableFuture<SendResult<String, OrderCreated>> future =
                        kafkaTemplate.send(ordersTopic, event.orderId(), event);

                // send() 는 즉시 리턴합니다. 성공/실패는 이 콜백에서만 알 수 있습니다.
                // (콜백을 안 붙이면 실패해도 예외 없이 조용히 지나갑니다 — Step 02 의 주제)
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[1-2] 발행 실패 key={}", event.orderId(), ex);
                        return;
                    }
                    log.info("[1-2] 발행 성공 key={} -> {}-{}@{}",
                            event.orderId(),
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                });
            }

            // 버퍼에 남은 레코드를 즉시 밀어냅니다. 실습에서 로그 순서를 안정시키려는 목적이며,
            // 운영 코드에서 매 건 flush() 하면 배치가 무력화되어 처리량이 급락합니다(Step 02).
            kafkaTemplate.flush();
            log.info("[1-2] flush 완료");
        }
    }

    // ========================================================================
    // [1-3] 첫 수신 — @KafkaListener
    // [1-7] 리스너 로그에 topic-partition@offset 을 항상 남긴다
    // ========================================================================
    //
    // concurrency 를 "1" 로 고정했습니다. application.yml 의 listener.concurrency=3 을
    // 그대로 두면 컨테이너 3개가 파티션을 하나씩 나눠 갖고 로그가 세 줄로 쪼개집니다.
    // Step 01 에서는 "partitions assigned: [orders-0, orders-1, orders-2]" 한 줄을
    // 눈에 익히는 것이 목적이므로 스레드를 하나로 둡니다.
    // concurrency 와 파티션 수의 관계는 Step 03 에서 다룹니다.
    //
    @Component
    @Profile("step01")
    public static class InventoryListener {

        private static final Logger log = LoggerFactory.getLogger(InventoryListener.class);

        @KafkaListener(
                id = "s01-inventory-listener",
                topics = "${app.topic.orders}",
                groupId = "s01-inventory",
                concurrency = "1")
        public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
            OrderCreated event = record.value();

            // [1-7] 위치 정보를 반드시 남깁니다. "메시지가 어디로 갔는가"를
            // 나중에 추적할 수 있는 가장 싼 방법입니다.
            log.info("[1-3] 재고 차감 {}-{}@{} key={} sku={} qty={}",
                    record.topic(), record.partition(), record.offset(),
                    record.key(), event.sku(), event.quantity());
        }
    }

    // ========================================================================
    // [1-4] 자동 설정이 만들어 준 빈들을 실제로 꺼내 본다
    // ========================================================================
    //
    // spring-kafka 의존성 한 줄이 컨텍스트에 무엇을 등록했는지 눈으로 확인합니다.
    // 등록되지 '않은' 빈(KafkaTransactionManager)도 함께 보여 주는 것이 핵심입니다.
    // 조건이 안 맞으면 자동 설정은 조용히 아무것도 만들지 않습니다.
    //
    @Component
    @Profile("step01")
    @Order(10)
    public static class AutoConfiguredBeanReport implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(AutoConfiguredBeanReport.class);

        private final ApplicationContext ctx;

        public AutoConfiguredBeanReport(ApplicationContext ctx) {
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
            // transaction-id-prefix 를 설정하지 않았으므로 등록되지 않습니다(Step 09).
            targets.put("KafkaTransactionManager", KafkaTransactionManager.class);

            log.info("[1-4] ---- spring-kafka 자동 설정 빈 목록 ----");
            targets.forEach((label, type) -> {
                String[] names = ctx.getBeanNamesForType(type);
                if (names.length == 0) {
                    // 자동 설정은 조건이 안 맞으면 '조용히' 빈을 안 만듭니다.
                    log.info("[1-4] {} (없음 — 조건 불충족)", pad(label));
                    return;
                }
                for (String name : names) {
                    Class<?> impl = ctx.getType(name);
                    log.info("[1-4] {} {} -> {}", pad(label), name,
                            impl == null ? "(타입 미상)" : impl.getName());
                }
            });

            // @KafkaListener 애노테이션을 실제로 스캔하는 후처리기의 빈 이름입니다.
            // 이름이 고정되어 있다는 점이 [1-5] 의 핵심 근거입니다.
            for (String infra : new String[]{
                    "org.springframework.kafka.config.internalKafkaListenerAnnotationProcessor",
                    "org.springframework.kafka.config.internalKafkaListenerEndpointRegistry"}) {
                log.info("[1-4] {} 존재={}", pad("내부 인프라"), ctx.containsBean(infra) + " " + infra);
            }

            // 리스너 컨테이너가 몇 개 떴는지도 여기서 셀 수 있습니다.
            KafkaListenerEndpointRegistry registry =
                    ctx.getBean(KafkaListenerEndpointRegistry.class);
            Collection<MessageListenerContainer> containers = registry.getListenerContainers();
            log.info("[1-4] 리스너 컨테이너 {}개: {}", containers.size(),
                    containers.stream().map(MessageListenerContainer::getListenerId).toList());
        }

        /** 로그 열을 맞추기 위한 좌측 정렬 패딩. */
        private static String pad(String s) {
            return "%-32s".formatted(s);
        }
    }

    // ========================================================================
    // [1-5] @EnableKafka 는 왜 필요 없는가
    // ========================================================================
    //
    // Boot 를 쓰면 KafkaAnnotationDrivenConfiguration 이 이미 @EnableKafka 를 붙여 줍니다.
    // 직접 한 번 더 붙여도 KafkaListenerAnnotationBeanPostProcessor 는
    // 고정된 빈 이름(internalKafkaListenerAnnotationProcessor)으로 등록되므로
    // 중복 등록되지 않고 그냥 무시됩니다. 즉 "해롭지는 않지만 불필요"합니다.
    //
    // 이 프로필을 켜고 기동해 리스너가 여전히 정확히 한 번만 메시지를 받는지 확인하세요.
    //
    @Configuration
    @Profile("step01-enable-kafka")
    @EnableKafka
    public static class RedundantEnableKafkaConfig {
        // 의도적으로 비어 있습니다. 애노테이션만이 실험 대상입니다.
    }

    // ========================================================================
    // [1-8] 함정 재현 — 오타 난 토픽명은 조용히 새 토픽을 만든다
    // ========================================================================
    //
    // "orders" 대신 "order" 로 보냅니다. 브로커의 auto.create.topics.enable=true 때문에
    // 예외 없이 성공하고, 파티션 1개짜리 새 토픽 order 가 생깁니다.
    // 리스너는 orders 만 보고 있으므로 아무 일도 일어나지 않습니다.
    //
    // 실행 후 확인:
    //   kt --list                     → order 가 새로 생겼습니다
    //   kt --describe --topic order   → PartitionCount: 1 (orders 는 3)
    // 정리:
    //   kt --delete --topic order
    //
    @Component
    @Profile("step01-typo")
    @Order(30)
    public static class TypoTopicPublisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(TypoTopicPublisher.class);

        private static final String TYPO_TOPIC = "order";   // ← 's' 가 빠졌습니다

        private final KafkaTemplate<String, OrderCreated> kafkaTemplate;

        public TypoTopicPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate) {
            this.kafkaTemplate = kafkaTemplate;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated event = OrderCreated.of(99);

            kafkaTemplate.send(TYPO_TOPIC, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[1-8] 발행 실패", ex);
                            return;
                        }
                        // 이 로그가 찍힌다는 사실 자체가 함정입니다.
                        // 오타를 냈는데 "성공"이라고 나옵니다.
                        log.warn("[1-8] 오타 토픽에 발행 '성공' {}-{}@{}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    });

            kafkaTemplate.flush();
            log.warn("[1-8] kt --list 로 '{}' 토픽이 생겼는지 확인하세요", TYPO_TOPIC);
        }
    }
}
