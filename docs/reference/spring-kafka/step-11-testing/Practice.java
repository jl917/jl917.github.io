package com.example.order.step11;

/*
 * =====================================================================================
 * Step 11 — 테스트 : Practice
 * =====================================================================================
 *
 * 배치 위치 : src/test/java/com/example/order/step11/Practice.java
 * 실행       : ./gradlew test  (bootRun 이 아닙니다)
 *
 * 중첩 클래스를 골라 실행할 때는 '$' 로 지정합니다.
 * zsh 는 '$' 를 변수로 해석하므로 **작은따옴표로 감싸는 것이 필수**입니다.
 *
 *   ./gradlew test --tests 'com.example.order.step11.Practice$EmbeddedKafkaProducerTest'
 *   ./gradlew test --tests 'com.example.order.step11.Practice$AwaitilityTest'
 *   ./gradlew test --tests 'com.example.order.step11.Practice$SleepBasedTest'
 *
 * [11-5] BrokenListenerTest 는 **일부러 깨진 테스트**입니다.
 *        @Disabled 를 떼고 아래처럼 20~30회 반복해야 간헐 실패가 재현됩니다.
 *        --rerun-tasks 가 없으면 Gradle 이 UP-TO-DATE 로 건너뛰어 30회가 1회가 됩니다.
 *
 *   for i in $(seq 1 30); do \
 *     ./gradlew test --tests 'com.example.order.step11.Practice$BrokenListenerTest' \
 *       --rerun-tasks -q || echo "FAIL $i"; \
 *   done
 *
 * [11-7] TestcontainersKafkaTest 는 @Tag("integration") 이라 기본 test 태스크에서 제외됩니다.
 *        도커가 필요하며, 아래로 따로 실행합니다.
 *
 *   ./gradlew integrationTest
 *
 *   // build.gradle 에 추가해 두십시오
 *   // tasks.named('test')      { useJUnitPlatform { excludeTags 'integration' } }
 *   // tasks.register('integrationTest', Test) {
 *   //     useJUnitPlatform { includeTags 'integration' }
 *   //     shouldRunAfter tasks.named('test')
 *   // }
 *
 * [11-10] CacheHitTest / CacheMissTest 는 **두 클래스를 한 번에** 돌려야 의미가 있습니다.
 *
 *   ./gradlew test --tests 'com.example.order.step11.Practice$Cache*Test' -i \
 *     | grep -E 'Spring test ApplicationContext cache statistics|hitCount|missCount'
 *
 * 그룹 id 규칙 : 모든 테스트가 "s11-" + UUID.randomUUID() 로 그룹을 만듭니다.
 *               [11-9] PollutedTest 하나만 고정 그룹 id 를 쓰는데, 오염을 재현하기 위한
 *               **의도적 예외**입니다.
 * =====================================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public final class Practice {

    private Practice() {
    }

    /** 모든 테스트가 공유하는 그룹 id 생성기. 격리는 식별자 수준에서 합니다. */
    static String uniqueGroup() {
        return "s11-" + UUID.randomUUID();
    }

    // =================================================================================
    // [11-2] [11-3] [11-4] @EmbeddedKafka + KafkaTestUtils 로 프로듀서 검증
    // =================================================================================
    //
    // partitions = 3 : 운영과 같은 파티션 수. 1 로 두면 파티션 관련 버그가 안 잡힙니다.
    // listeners=PLAINTEXT://localhost:0 : **포트 0 이 핵심**입니다. OS 가 빈 포트를 골라 주므로
    //   CI 에서 테스트가 병렬로 돌아도 포트 충돌이 없습니다. 9092 로 고정하면 로컬에서 돌고 있는
    //   docker compose 의 브로커와 충돌해 "왜 내 테스트가 운영 토픽을 건드리지?" 가 됩니다.
    //
    // spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers} 를 빠뜨리면
    //   테스트가 조용히 127.0.0.1:9092(= application.yml 의 값)로 갑니다. 도커가 떠 있으면
    //   **테스트가 통과하지만 임베디드 브로커는 아무것도 안 한** 상태가 됩니다.
    //
    @SpringBootTest
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("[11-2~11-4] 프로듀서 테스트")
    static class EmbeddedKafkaProducerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        @DisplayName("발행한 3건을 테스트 컨슈머로 받아 키와 본문을 검증한다")
        void 발행한_3건을_검증한다() {
            // --- given : 테스트 전용 컨슈머. 값은 String 으로 받습니다. -------------------
            // 도메인 타입으로 역직렬화하면 __TypeId__ 헤더나 trusted packages 문제로 실패했을 때
            // "메시지가 안 왔다" 와 구분이 안 됩니다. 프로듀서 테스트는 프로듀서만 검증해야 합니다.
            Map<String, Object> props =
                    KafkaTestUtils.consumerProps(uniqueGroup(), "false", broker);
            try (Consumer<String, String> consumer =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(consumer, "orders");

                // --- when -------------------------------------------------------------
                for (int seq = 1; seq <= 3; seq++) {
                    OrderCreated event = OrderCreated.of(seq);
                    kafkaTemplate.send("orders", event.orderId(), event);
                }
                kafkaTemplate.flush();

                // --- then : getRecords 로 "개수까지" 검증합니다 -------------------------
                // getSingleRecord 는 "정확히 1건" 을 검증하지 않습니다. 첫 1건을 꺼낼 뿐이라,
                // 2건이 발행된 버그를 통과시킵니다. 개수 단언은 반드시 getRecords().count().
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 3);

                assertThat(records.count()).isEqualTo(3);

                List<String> keys = new CopyOnWriteArrayList<>();
                records.forEach(r -> keys.add(r.key()));
                assertThat(keys).containsExactlyInAnyOrder("ORD-0001", "ORD-0002", "ORD-0003");

                ConsumerRecord<String, String> first = records.iterator().next();
                assertThat(first.value()).contains("\"sku\"");
                assertThat(first.partition()).isBetween(0, 2);
            }
        }

        @Test
        @DisplayName("키가 같으면 항상 같은 파티션으로 간다")
        void 같은_키는_같은_파티션() {
            Map<String, Object> props =
                    KafkaTestUtils.consumerProps(uniqueGroup(), "false", broker);
            try (Consumer<String, String> consumer =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(consumer, "orders");

                OrderCreated event = OrderCreated.of(1);
                for (int i = 0; i < 5; i++) {
                    kafkaTemplate.send("orders", event.orderId(), event);
                }
                kafkaTemplate.flush();

                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 5);

                // 파티션은 murmur2(key) % partitionCount 로 결정되므로 5건 모두 같은 곳입니다.
                assertThat(records)
                        .extracting(ConsumerRecord::partition)
                        .containsOnly(records.iterator().next().partition());
            }
        }
    }

    // =================================================================================
    // [11-5] 깨진 리스너 테스트 — waitForAssignment 누락
    // =================================================================================
    //
    // 이 클래스는 **일부러 깨져 있습니다.** @BeforeEach 의 waitForAssignment 호출이
    // 주석 처리돼 있어, send 가 'partitions assigned' 보다 빠르면 메시지를 놓칩니다.
    //
    // 실행 절차
    //   1. @Disabled 를 뗀다
    //   2. 위 주석의 for 루프로 30회 반복 → 대략 9회 실패 (30%)
    //   3. waitForAssignment 주석을 푼다
    //   4. 다시 30회 → 0회 실패
    //
    // 왜 하필 30% 인가?
    //   파티션이 3개이고 리스너 컨테이너가 아직 일부만 할당받은 상태에서 send 하면,
    //   메시지가 "아직 할당 안 된 파티션" 으로 갈 확률이 대략 1/3 입니다.
    //   auto.offset.reset=latest 인 새 그룹이라 그 파티션에 늦게 붙으면 이미 지나간
    //   메시지를 못 봅니다. 이것이 CI 간헐 실패 1번 원인입니다.
    //
    @Disabled("의도적으로 깨진 테스트입니다. 재현할 때만 이 줄을 지우십시오.")
    @SpringBootTest(classes = {ListenerFixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=latest"
    })
    @DisplayName("[11-5] 깨진 리스너 테스트 (간헐 실패 재현)")
    static class BrokenListenerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        ListenerFixture fixture;

        @Autowired
        KafkaListenerEndpointRegistry registry;

        @Autowired
        EmbeddedKafkaBroker broker;

        @BeforeEach
        void setUp() {
            fixture.reset();

            // ↓↓↓ 이 세 줄이 빠져 있는 것이 이 테스트가 깨진 이유입니다 ↓↓↓
            // for (MessageListenerContainer c : registry.getListenerContainers()) {
            //     ContainerTestUtils.waitForAssignment(c, broker.getPartitionsPerTopic());
            // }
        }

        @Test
        @DisplayName("리스너가 3건을 받는다 (30% 확률로 실패)")
        void 리스너가_3건을_받는다() throws Exception {
            for (int seq = 1; seq <= 3; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                kafkaTemplate.send("orders", event.orderId(), event);
            }

            // 실패 시 메시지:
            //   expected: 0L but was: 1L   (latch 가 안 내려감 = 메시지를 놓침)
            assertThat(fixture.latch().await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.received()).hasSize(3);
        }
    }

    // =================================================================================
    // [11-5] 고친 리스너 테스트 — waitForAssignment 추가
    // =================================================================================
    //
    // waitForAssignment 의 두 번째 인자는 "컨테이너가 받아야 할 **파티션 총 개수**" 입니다.
    // 1 을 넣으면 파티션 하나만 할당돼도 통과해 버려서, 3분의 1 확률로 여전히 실패합니다.
    // broker.getPartitionsPerTopic() 을 쓰면 @EmbeddedKafka(partitions=N) 과 항상 일치합니다.
    //
    @SpringBootTest(classes = {ListenerFixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=latest"
    })
    @DisplayName("[11-5] 고친 리스너 테스트")
    static class FixedListenerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        ListenerFixture fixture;

        @Autowired
        KafkaListenerEndpointRegistry registry;

        @Autowired
        EmbeddedKafkaBroker broker;

        @BeforeEach
        void setUp() {
            fixture.reset();
            for (MessageListenerContainer c : registry.getListenerContainers()) {
                ContainerTestUtils.waitForAssignment(c, broker.getPartitionsPerTopic());
            }
        }

        @Test
        @DisplayName("30회를 돌려도 항상 3건을 받는다")
        void 항상_3건을_받는다() throws Exception {
            for (int seq = 1; seq <= 3; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                kafkaTemplate.send("orders", event.orderId(), event);
            }

            assertThat(fixture.latch().await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.received()).hasSize(3);
        }
    }

    // =================================================================================
    // [11-6] Thread.sleep 기반 테스트 — 느리고 불안정
    // =================================================================================
    //
    // 검증 내용은 아래 AwaitilityTest 와 **완전히 동일**합니다. 대기 방식만 다릅니다.
    // 두 클래스를 각각 실행해 'BUILD SUCCESSFUL in Ns' 를 비교하는 것이 이 파일의 하이라이트입니다.
    // 교재 기준 42.0s vs 6.8s. 여러분 머신에서 몇 초인지 아래 표에 적어 두십시오.
    //
    //   | 방식          | 총 시간 | 테스트당 평균 대기 |
    //   |---------------|--------:|-------------------:|
    //   | Thread.sleep  |         |                    |
    //   | awaitility    |         |                    |
    //
    @SpringBootTest(classes = {ListenerFixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("[11-6] Thread.sleep 방식")
    static class SleepBasedTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        ListenerFixture fixture;

        @Autowired
        KafkaListenerEndpointRegistry registry;

        @Autowired
        EmbeddedKafkaBroker broker;

        @BeforeEach
        void setUp() {
            fixture.reset();
            for (MessageListenerContainer c : registry.getListenerContainers()) {
                ContainerTestUtils.waitForAssignment(c, broker.getPartitionsPerTopic());
            }
        }

        @Test
        void 발행_1건() throws Exception {
            send(1);
            Thread.sleep(1000);                       // ← 항상 1초를 버립니다
            assertThat(fixture.received()).hasSize(1);
        }

        @Test
        void 발행_2건() throws Exception {
            send(2);
            Thread.sleep(1000);
            assertThat(fixture.received()).hasSize(2);
        }

        @Test
        void 발행_3건() throws Exception {
            send(3);
            Thread.sleep(1000);
            assertThat(fixture.received()).hasSize(3);
        }

        @Test
        void 발행_4건() throws Exception {
            send(4);
            Thread.sleep(1000);
            assertThat(fixture.received()).hasSize(4);
        }

        private void send(int count) {
            for (int seq = 1; seq <= count; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                kafkaTemplate.send("orders", event.orderId(), event);
            }
        }
    }

    // =================================================================================
    // [11-6] awaitility 기반 테스트 — 조건이 충족되면 즉시 통과
    // =================================================================================
    //
    // atMost 는 **상한**일 뿐 대기 시간이 아닙니다. 조건이 83ms 만에 참이 되면 83ms 만 씁니다.
    // 그래서 atMost 를 5초로 넉넉히 잡아도 성공 경로의 시간은 전혀 늘지 않습니다.
    // 이걸 오해해 atMost 를 작게 잡다가 느린 CI 에서 터지는 것이 흔한 실수입니다.
    //
    // ⚠️ untilAsserted 의 람다는 **여러 번 실행됩니다.** 안에서 send 하거나 카운터를
    //    증가시키면 폴링 횟수만큼 반복됩니다. 읽기와 단언만 넣으십시오.
    //
    @SpringBootTest(classes = {ListenerFixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("[11-6] awaitility 방식")
    static class AwaitilityTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        ListenerFixture fixture;

        @Autowired
        KafkaListenerEndpointRegistry registry;

        @Autowired
        EmbeddedKafkaBroker broker;

        @BeforeEach
        void setUp() {
            fixture.reset();
            for (MessageListenerContainer c : registry.getListenerContainers()) {
                ContainerTestUtils.waitForAssignment(c, broker.getPartitionsPerTopic());
            }
        }

        @Test
        void 발행_1건() {
            send(1);
            awaitCount(1);
        }

        @Test
        void 발행_2건() {
            send(2);
            awaitCount(2);
        }

        @Test
        void 발행_3건() {
            send(3);
            awaitCount(3);
        }

        @Test
        void 발행_4건() {
            send(4);
            awaitCount(4);
        }

        private void send(int count) {
            for (int seq = 1; seq <= count; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                kafkaTemplate.send("orders", event.orderId(), event);
            }
        }

        private void awaitCount(int expected) {
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertThat(fixture.received()).hasSize(expected));
        }
    }

    // =================================================================================
    // [11-7] Testcontainers — 실제 브로커 이미지
    // =================================================================================
    //
    // @Container static final 에서 **static 이 필수**입니다.
    //   static 이면  : 클래스당 1회 기동 (8.4초)
    //   static 이 없으면 : 테스트 메서드마다 기동 (8.4초 × 3 = 25초)
    //
    // @DynamicPropertySource 도 static 이어야 합니다. 스프링 컨텍스트가 만들어지기 **전에**
    // 호출돼야 bootstrap-servers 가 반영되기 때문입니다. 인스턴스 메서드로 두면
    // 컴파일은 되지만 IllegalArgumentException 이 납니다.
    //
    // withReuse(true) 는 컨테이너를 테스트 실행 간에 살려 둡니다(0.2초). 다만 **데이터도 남으므로**
    // 토픽과 그룹을 반드시 UUID 로 만드십시오. CI 에서는 꺼야 합니다.
    //
    @Tag("integration")
    @Testcontainers
    @SpringBootTest(classes = {ListenerFixture.class})
    @DisplayName("[11-7] Testcontainers 통합 테스트")
    static class TestcontainersKafkaTest {

        @Container
        static final KafkaContainer KAFKA =
                new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                        .withReuse(false);   // CI 안전을 위해 기본 false

        /** 컨텍스트 생성 전에 호출돼야 하므로 static 입니다. */
        @DynamicPropertySource
        static void kafkaProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
            registry.add("spring.kafka.consumer.group-id", Practice::uniqueGroup);
            registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        }

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        ListenerFixture fixture;

        @Test
        @DisplayName("실제 브로커에서 발행과 소비가 동작한다")
        void 실제_브로커에서_동작한다() {
            fixture.reset();

            OrderCreated event = OrderCreated.of(1);
            kafkaTemplate.send("orders", event.orderId(), event);

            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(fixture.received()).hasSize(1));

            assertThat(fixture.received().get(0).orderId()).isEqualTo("ORD-0001");
        }
    }

    // =================================================================================
    // [11-9] 테스트 간 상태 오염 — 고정 그룹 id
    // =================================================================================
    //
    // 이 파일에서 **유일하게** 고정 그룹 id 를 쓰는 클래스입니다. 의도적입니다.
    //
    // 같은 그룹 id 를 두 테스트가 쓰면, 앞 테스트가 커밋한 오프셋이 브로커의
    // __consumer_offsets 에 남아 뒤 테스트가 "이미 읽은 메시지" 를 못 받습니다.
    // 증상은 예외가 아니라 **단언 실패(0건)** 라서 원인을 찾기 어렵습니다.
    //
    // @DirtiesContext 로는 안 고쳐집니다. 컨텍스트를 새로 만들어도 브로커의
    // 커밋된 오프셋은 그대로이기 때문입니다. **격리는 식별자 수준에서** 해야 합니다.
    //
    @SpringBootTest
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("[11-9] 고정 그룹 id 오염 재현")
    static class PollutedTest {

        private static final String FIXED_GROUP = "s11-polluted-fixed";   // ← 의도적 고정

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        @DisplayName("첫 번째 테스트 — 1건을 받고 커밋한다")
        void a_첫번째_테스트() {
            kafkaTemplate.send("orders", "ORD-9001", OrderCreated.of(9001));
            kafkaTemplate.flush();

            Map<String, Object> props =
                    KafkaTestUtils.consumerProps(FIXED_GROUP, "true", broker);
            try (Consumer<String, String> consumer =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(consumer, "orders");
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 1);
                consumer.commitSync();                       // ← 여기서 오프셋이 남습니다

                assertThat(records.count()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("두 번째 테스트 — 같은 그룹이라 아무것도 못 받는다")
        void b_두번째_테스트() {
            Map<String, Object> props =
                    KafkaTestUtils.consumerProps(FIXED_GROUP, "true", broker);
            try (Consumer<String, String> consumer =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(consumer, "orders");
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3));

                // 앞 테스트가 커밋했으므로 0건입니다.
                // 그룹 id 를 uniqueGroup() 으로 바꾸면 이 단언이 깨지고(= 메시지가 보이고)
                // 그것이 정상입니다.
                assertThat(records.count()).isZero();
            }
        }
    }

    // =================================================================================
    // [11-10] 컨텍스트 캐싱 — 애트리뷰트가 같으면 브로커까지 재사용
    // =================================================================================
    //
    // 아래 두 클래스는 @EmbeddedKafka 애트리뷰트가 **다릅니다**(topics 가 다름).
    // 그래서 서로 다른 컨텍스트가 뜨고, 브로커도 각각 뜹니다.
    //
    // 반대로 애트리뷰트가 완전히 같으면 컨텍스트가 캐시되어 브로커까지 재사용됩니다(0.041초).
    // 빠른 대신, **앞 클래스가 남긴 메시지가 뒤 클래스에 보입니다.**
    // "IDE 에서 하나만 돌리면 통과하는데 ./gradlew test 로는 실패" 의 전형적 원인입니다.
    //
    // 두 클래스를 **한 번에** 돌려야 캐시 통계에 차이가 나타납니다.
    //   ./gradlew test --tests 'com.example.order.step11.Practice$Cache*Test' -i \
    //     | grep -E 'hitCount|missCount'
    //
    @SpringBootTest
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("[11-10] 캐시 적중 (EmbeddedKafkaProducerTest 와 동일 애트리뷰트)")
    static class CacheHitTest {

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void 브로커가_재사용된다() {
            // EmbeddedKafkaProducerTest 와 애트리뷰트가 같으므로 컨텍스트가 캐시에서 나옵니다.
            // 로그의 hitCount 가 올라가고, 이 클래스에서는 브로커 기동 로그가 안 보입니다.
            assertThat(broker.getBrokersAsString()).isNotBlank();
            assertThat(broker.getPartitionsPerTopic()).isEqualTo(3);
        }
    }

    @SpringBootTest
    @EmbeddedKafka(partitions = 1,                          // ← 3 이 아니라 1
            topics = {"orders", "orders.DLT"},              // ← 토픽 목록도 다름
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("[11-10] 캐시 미스 (애트리뷰트가 다름)")
    static class CacheMissTest {

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void 브로커가_새로_뜬다() {
            // 애트리뷰트가 달라 새 컨텍스트 + 새 브로커입니다. missCount 가 올라갑니다.
            assertThat(broker.getPartitionsPerTopic()).isEqualTo(1);
        }
    }

    // =================================================================================
    // [11-11] MockProducer — 브로커 없이 직렬화·파티셔닝·헤더만 검증
    // =================================================================================
    //
    // 장점 : 밀리초 단위. 브로커도 도커도 필요 없습니다.
    // 한계 : 커밋·재시도·DLT·리밸런스는 **전혀 검증하지 못합니다.** 이 셋이 필요하면
    //        @EmbeddedKafka 이상으로 가야 합니다.
    //
    @DisplayName("[11-11] MockProducer")
    static class MockProducerTest {

        @Test
        @DisplayName("헤더와 키가 의도대로 붙는지 검증한다")
        void 헤더와_키를_검증한다() {
            MockProducer<String, String> producer =
                    new MockProducer<>(true, new StringSerializer(), new StringSerializer());

            ProducerRecord<String, String> record =
                    new ProducerRecord<>("orders", "ORD-0001", "{\"orderId\":\"ORD-0001\"}");
            record.headers().add("traceId", "abc-123".getBytes(StandardCharsets.UTF_8));

            producer.send(record);

            assertThat(producer.history()).hasSize(1);
            ProducerRecord<String, String> sent = producer.history().get(0);
            assertThat(sent.key()).isEqualTo("ORD-0001");
            assertThat(sent.topic()).isEqualTo("orders");

            // 헤더 값은 byte[] 입니다. toString() 하면 [B@1a2b3c 가 찍힙니다.
            byte[] raw = sent.headers().lastHeader("traceId").value();
            assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo("abc-123");

            producer.close();
        }
    }

    // =================================================================================
    // [11-12] DLT 도착 검증
    // =================================================================================
    //
    // 도착 여부만 보면 부족합니다. **왜** 갔는지까지 봐야 합니다.
    //   - 키만 단언  → 다른 예외로 DLT 에 가도 통과
    //   - 예외만 단언 → 다른 주문이 가도 통과
    // 그래서 키 · DLT_EXCEPTION_FQCN · DLT_ORIGINAL_OFFSET 세 가지를 모두 봅니다.
    //
    @SpringBootTest(classes = {FailingListenerFixture.class})
    @EmbeddedKafka(partitions = 1,
            topics = {"orders", "orders.DLT"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=earliest"
    })
    @DisplayName("[11-12] DLT 도착 검증")
    static class DltArrivalTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        @DisplayName("3회 실패 후 orders.DLT 에 도착하고, 예외와 원본 오프셋이 헤더에 있다")
        void DLT까지_간다() {
            Map<String, Object> props =
                    KafkaTestUtils.consumerProps(uniqueGroup(), "false", broker);
            try (Consumer<String, String> dlt =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(dlt, "orders.DLT");

                kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));

                ConsumerRecord<String, String> failed =
                        KafkaTestUtils.getSingleRecord(dlt, "orders.DLT", Duration.ofSeconds(20));

                // ① 키
                assertThat(failed.key()).isEqualTo("ORD-0001");

                // ② 왜 실패했는가
                byte[] fqcn = failed.headers()
                        .lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN).value();
                assertThat(new String(fqcn, StandardCharsets.UTF_8))
                        .isEqualTo("java.lang.IllegalStateException");

                // ③ 원본 어디에서 왔는가 — long 8바이트라 ByteBuffer 로 풉니다
                byte[] offsetBytes = failed.headers()
                        .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value();
                long originalOffset = ByteBuffer.wrap(offsetBytes).getLong();
                assertThat(originalOffset).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    // =================================================================================
    // 테스트 픽스처 — 리스너와 수집 버퍼
    // =================================================================================

    /** 정상 리스너. 받은 이벤트를 모으고 latch 를 내립니다. */
    static class ListenerFixture {

        private final List<OrderCreated> received = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch latch = new CountDownLatch(3);

        @KafkaListener(id = "s11-fixture", topics = "orders",
                groupId = "#{T(com.example.order.step11.Practice).uniqueGroup()}")
        public void onMessage(OrderCreated event,
                              @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                              @Header(KafkaHeaders.OFFSET) long offset) {
            received.add(event);
            latch.countDown();
        }

        void reset() {
            received.clear();
            latch = new CountDownLatch(3);
        }

        List<OrderCreated> received() {
            return received;
        }

        CountDownLatch latch() {
            return latch;
        }
    }

    /** 항상 실패하는 리스너. DLT 실습용입니다. */
    static class FailingListenerFixture {

        private final AtomicInteger attempts = new AtomicInteger();

        @KafkaListener(id = "s11-failing", topics = "orders",
                groupId = "#{T(com.example.order.step11.Practice).uniqueGroup()}")
        public void onMessage(OrderCreated event) {
            attempts.incrementAndGet();
            throw new IllegalStateException("재고가 부족합니다: " + event.orderId());
        }

        int attempts() {
            return attempts.get();
        }
    }

    /** 컴파일 확인용 더미 참조. 실제로는 도메인 모듈의 것을 씁니다. */
    @SuppressWarnings("unused")
    private static OrderCreated sample() {
        return new OrderCreated("ORD-0001", 1001, "SKU-002", 2,
                new BigDecimal("11000"), java.time.Instant.parse("2025-01-01T00:01:00Z"));
    }
}
