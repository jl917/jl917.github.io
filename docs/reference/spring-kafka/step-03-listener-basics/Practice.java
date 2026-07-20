package com.example.order.step03;

/*
 * ============================================================================
 * Step 03 — @KafkaListener 기초 / Practice
 * ============================================================================
 *
 * 배치할 위치: src/main/java/com/example/order/step03/Practice.java
 *
 * [실행]
 *   # 3-1 ~ 3-9 기본 실습 (concurrency 는 기본 3)
 *   ./gradlew bootRun --args='--spring.profiles.active=step03'
 *
 *   # 3-4 경우 ② — concurrency 5 (파티션 3 초과). partitions assigned: [] 를 관찰
 *   ./gradlew bootRun --args='--spring.profiles.active=step03 --app.demo.concurrency=5'
 *
 *   # 3-4 경우 ③ — concurrency 2
 *   ./gradlew bootRun --args='--spring.profiles.active=step03 --app.demo.concurrency=2'
 *
 *   # 3-9 배치 리스너 단독
 *   ./gradlew bootRun --args='--spring.profiles.active=step03,step03-batch'
 *
 *   # 3-10 느린 리스너 단독 (건당 20초 sleep. 다른 실습이 멈추니 반드시 마지막에 단독으로)
 *   ./gradlew bootRun --args='--spring.profiles.active=step03-slow'
 *
 * [확인할 CLI 명령 3개]
 *   alias kt='docker exec -it learn-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092'
 *   alias kcg='docker exec -it learn-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092'
 *
 *   ① kt  --describe --topic orders
 *        → PartitionCount 가 3 인지 확인. 3 이 아니면 교재의 모든 로그가 달라진다.
 *
 *   ② kcg --describe --group s03-inventory --members
 *        → #PARTITIONS 가 0 인 멤버를 찾는 유일한 방법. 3-4 의 "노는 컨슈머" 확인용.
 *          (--members 없이 --describe 만 하면 파티션 없는 멤버는 아예 안 보인다)
 *
 *   ③ kcg --list
 *        → s03-inventory / s03-notification / s03-partial ... 그룹이 몇 개 생겼는지
 *
 * [정리]
 *   실습 흔적을 지우려면 앱을 끄고:
 *   kcg --group s03-inventory --topic orders --reset-offsets --to-earliest --execute
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.PartitionOffset;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Practice {

    private Practice() {
    }

    // ========================================================================
    // [3-0] 실습 데이터 — 기동 직후 OrderCreated.of(1) ~ of(9) 를 발행한다.
    //       키가 결정적이므로 파티션 배정도 항상 같다.
    //         orders-0 ← ORD-0002, ORD-0004, ORD-0009
    //         orders-1 ← ORD-0001, ORD-0006, ORD-0008
    //         orders-2 ← ORD-0003, ORD-0005, ORD-0007
    // ========================================================================
    @Component
    @Profile({"step03", "step03-slow", "step03-batch"})
    public static class Seeder implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Seeder.class);

        private final KafkaTemplate<String, Object> template;

        public Seeder(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 9; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                template.send("orders", event.orderId(), event);
            }
            template.flush();
            log.info("seed 완료: ORD-0001 ~ ORD-0009 (9건)");
        }
    }

    // ========================================================================
    // [3-2] 컨테이너 팩토리 — 이름 붙인 팩토리를 여러 개 만든다.
    //       기본 팩토리(kafkaListenerContainerFactory)를 덮어쓰는 방식은
    //       전 리스너에 일괄 적용되므로 이 실습 파일에서는 쓰지 않는다.
    //
    // [3-4] app.demo.concurrency 로 concurrency 를 바꿔 가며 재기동한다.
    //       기본 3 / 5 로 주면 두 스레드가 놀고 / 2 로 주면 한 스레드가 2개를 맡는다.
    // ========================================================================
    @Configuration
    @Profile({"step03", "step03-batch"})
    public static class ContainerFactoryConfig {

        /** 3-4 의 주인공. 기본 3, 실행 인자로 5 또는 2 를 준다. */
        @Bean("fastFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> fastFactory(
                ConsumerFactory<String, OrderCreated> cf,
                @Value("${app.demo.concurrency:3}") int concurrency) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(concurrency);
            return f;
        }

        /** 순서가 중요한 리스너용. 파티션이 몇 개든 스레드는 하나. */
        @Bean("singleFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> singleFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(1);
            return f;
        }

        /** [3-9] 배치 리스너 전용. setBatchListener(true) 한 줄이 전부다. */
        @Bean("batchFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);
            f.setBatchListener(true);
            return f;
        }
    }

    // ========================================================================
    // [3-1][3-4] 기본 리스너 — 스레드 이름과 partitions assigned 로그를 관찰한다.
    //            id 를 주었으므로 스레드 이름이 [inventory-0-C-1] 로 찍힌다.
    // ========================================================================
    @Component
    @Profile("step03")
    public static class InventoryListener {

        private static final Logger log = LoggerFactory.getLogger(InventoryListener.class);

        @KafkaListener(id = "inventory", groupId = "s03-inventory",
                topics = "orders", containerFactory = "fastFactory")
        public void onOrder(OrderCreated e) {
            log.info("재고 차감 {} {} x{}", e.orderId(), e.sku(), e.quantity());
        }
    }

    // ========================================================================
    // [3-2] 이름 붙인 팩토리 선택 — singleFactory 를 쓰므로 concurrency 1.
    //       fastFactory 를 쓰는 위 리스너와 스레드 수가 다른 것을 로그로 비교한다.
    // ========================================================================
    @Component
    @Profile("step03")
    public static class AuditListener {

        private static final Logger log = LoggerFactory.getLogger(AuditListener.class);

        @KafkaListener(id = "audit", groupId = "s03-audit",
                topics = "orders", containerFactory = "singleFactory")
        public void onOrder(OrderCreated e) {
            log.info("감사 로그 {}", e.orderId());
        }
    }

    // ========================================================================
    // [3-7] 그룹 분리 — 위 s03-inventory 와 다른 그룹이므로 9건을 전량 다시 받는다.
    //       두 리스너의 수신 건수를 합치면 18 이 된다. 이것이 팬아웃.
    // ========================================================================
    @Component
    @Profile("step03")
    public static class NotificationListener {

        private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

        @KafkaListener(id = "notification", groupId = "s03-notification",
                topics = "orders", containerFactory = "singleFactory")
        public void onOrder(OrderCreated e) {
            log.info("알림 발송 {} → customer {}", e.orderId(), e.customerId());
        }
    }

    // ========================================================================
    // [3-6] 파티션 명시 할당 — orders-0 과 orders-1 만 소비한다.
    //       orders-2 의 ORD-0003 / 0005 / 0007 은 영원히 오지 않는다.
    //
    //       ⚠️ 명시 할당은 그룹 관리를 우회한다(리밸런스 없음).
    //          그런데 커밋은 여전히 groupId 앞으로 나가므로 전용 그룹을 쓴다.
    //          인스턴스를 2대 띄우면 두 대가 같은 파티션을 동시에 읽는다(중복 소비).
    // ========================================================================
    @Component
    @Profile("step03")
    public static class PartitionListener {

        private static final Logger log = LoggerFactory.getLogger(PartitionListener.class);

        @KafkaListener(id = "p01", groupId = "s03-partial",
                topicPartitions = @TopicPartition(topic = "orders", partitions = {"0", "1"}))
        public void onlyZeroAndOne(OrderCreated e,
                                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
            log.info("p{} {}", partition, e.orderId());
        }
    }

    // ========================================================================
    // [3-6] 특정 오프셋부터 읽기 — 재처리 도구의 원형.
    //
    //       ⚠️ initialOffset 은 "커밋된 오프셋"을 무시한다.
    //          그래서 몇 번을 재기동해도 매번 같은 메시지를 다시 읽는다.
    //          버그가 아니라 의도된 동작이다.
    //          orders-0 은 오프셋 1 부터라 ORD-0002(오프셋 0)를 건너뛴다.
    // ========================================================================
    @Component
    @Profile("step03")
    public static class ReplayListener {

        private static final Logger log = LoggerFactory.getLogger(ReplayListener.class);

        @KafkaListener(id = "replay", groupId = "s03-replay",
                topicPartitions = @TopicPartition(topic = "orders", partitionOffsets = {
                        @PartitionOffset(partition = "0", initialOffset = "1"),
                        @PartitionOffset(partition = "2", initialOffset = "0")}))
        public void replay(ConsumerRecord<String, OrderCreated> rec) {
            log.info("replay {}-{}@{} {}", rec.topic(), rec.partition(), rec.offset(), rec.key());
        }
    }

    // ========================================================================
    // [3-8] 정규식 구독 — orders.* 는 orders.DLT 까지 잡는다.
    //       정규식이라 '.' 은 임의의 한 글자다. orders 하나만 원했다면 잘못된 패턴이다.
    //       새 토픽을 만들어도 metadata.max.age.ms(기본 5분) 뒤에야 붙는다.
    // ========================================================================
    @Component
    @Profile("step03")
    public static class PatternListener {

        private static final Logger log = LoggerFactory.getLogger(PatternListener.class);

        @KafkaListener(id = "pat", groupId = "s03-pattern",
                topicPattern = "orders.*", containerFactory = "singleFactory")
        public void anyOrders(ConsumerRecord<String, OrderCreated> rec) {
            log.info("{}-{} ← {}", rec.topic(), rec.partition(), rec.key());
        }
    }

    // ========================================================================
    // [3-9] 배치 리스너 — 시그니처가 반드시 List<T> 여야 한다.
    //       배치 크기의 상한은 spring.kafka.consumer.max-poll-records(기본 500).
    //       하한은 없다. 토픽에 3건뿐이면 3건짜리 배치가 온다.
    //
    //       ⚠️ 팩토리만 batch 로 바꾸고 시그니처를 단건으로 두면 기동이 실패한다.
    //          반대로 시그니처만 List 로 바꾸면 원소 1개짜리 리스트가 계속 온다(무증상).
    // ========================================================================
    @Component
    @Profile("step03-batch")
    public static class BatchListener {

        private static final Logger log = LoggerFactory.getLogger(BatchListener.class);

        @KafkaListener(id = "batch", groupId = "s03-batch",
                topics = "orders", containerFactory = "batchFactory")
        public void onBatch(List<OrderCreated> events) {
            log.info("배치 {}건: {}", events.size(),
                    events.stream().map(OrderCreated::orderId).toList());
        }
    }

    // ========================================================================
    // [3-10] 느린 리스너 — max.poll.interval.ms 초과를 재현한다.
    //
    //        max-poll-records=3, max.poll.interval.ms=30000, 건당 20초 sleep.
    //        → 3건 × 20초 = 60초 > 30초 이므로 세 번째 레코드 처리 중 그룹에서 쫓겨난다.
    //
    //        관찰할 로그 순서:
    //          1) 처리 시작/완료 ORD-0002, ORD-0004  (커밋은 배치 끝에 하므로 아직 안 됨)
    //          2) [ad | s03-slow-1 ] ... sending LeaveGroup request ... poll timeout has expired
    //          3) 처리 완료 ORD-0009
    //          4) CommitFailedException  (이미 그룹에서 쫓겨나 커밋 자격이 없음)
    //          5) (Re-)joining group → partitions assigned → 처리 시작 ORD-0002  ← 원점
    //
    //        ⚠️ 근본 원인이 그대로면 이 사이클이 영원히 반복된다.
    //           해결 순서: max-poll-records 축소 → 리스너 최적화 → max.poll.interval.ms 증가
    // ========================================================================
    @Configuration
    @Profile("step03-slow")
    public static class SlowConsumerConfig {

        /** 기본 ConsumerFactory 를 쓰지 않고, poll 관련 설정만 바꾼 전용 팩토리를 만든다. */
        @Bean("slowConsumerFactory")
        public ConsumerFactory<String, OrderCreated> slowConsumerFactory(KafkaProperties properties) {
            Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties());
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 3);
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000);
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean("slowFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> slowFactory(
                ConsumerFactory<String, OrderCreated> slowConsumerFactory) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(slowConsumerFactory);
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step03-slow")
    public static class SlowListener {

        private static final Logger log = LoggerFactory.getLogger(SlowListener.class);

        @KafkaListener(id = "slow", groupId = "s03-slow",
                topics = "orders", containerFactory = "slowFactory")
        public void slow(OrderCreated e) throws InterruptedException {
            log.info("처리 시작 {}", e.orderId());
            Thread.sleep(20_000L);
            log.info("처리 완료 {}", e.orderId());
        }
    }

    // ========================================================================
    // [3-7] KafkaListenerEndpointRegistry 조회
    //       "지금 어떤 리스너가 켜져 있고 어느 파티션을 받았는가"를 한 번에 출력한다.
    //       예제가 안 도는 것 같으면 이 출력부터 보라.
    //
    //       id 를 주지 않은 리스너는
    //       org.springframework.kafka.KafkaListenerEndpointContainer#0 같은 이름이 된다.
    //       순서에 의존하는 이름이라 리스너를 하나 추가하면 번호가 밀린다.
    // ========================================================================
    @Component
    @Profile({"step03", "step03-slow", "step03-batch"})
    public static class RegistryReporter {

        private static final Logger log = LoggerFactory.getLogger(RegistryReporter.class);

        private final KafkaListenerEndpointRegistry registry;

        public RegistryReporter(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void report() throws InterruptedException {
            Thread.sleep(3_000L);   // 최초 파티션 할당이 끝나기를 기다린다
            log.info("--- 등록된 리스너 컨테이너 ---");
            for (String id : registry.getListenerContainerIds()) {
                var c = registry.getListenerContainer(id);
                log.info("{} running={} group={} assigned={}",
                        id, c.isRunning(), c.getGroupId(), c.getAssignedPartitions());
            }
        }
    }
}
