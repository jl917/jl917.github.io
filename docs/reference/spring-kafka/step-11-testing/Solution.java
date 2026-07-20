package com.example.order.step11;

/*
 * =====================================================================================
 * Step 11 — 테스트 : Solution (6문제 정답 + 해설)
 * =====================================================================================
 *
 * 배치 위치 : src/test/java/com/example/order/step11/Solution.java
 *
 * ⚠️ Exercise.java 를 먼저 풀어 본 뒤에 여십시오.
 *    문제 2 와 3 은 **측정** 이 답의 절반입니다. 코드만 읽고 넘어가면 이 스텝의 핵심을 놓칩니다.
 *
 * 실행
 *   ./gradlew test --tests 'com.example.order.step11.Solution$A1ProducerTest'
 *   ./gradlew integrationTest --tests 'com.example.order.step11.Solution$A4TestcontainersTest'
 * =====================================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public final class Solution {

    private Solution() {
    }

    static String uniqueGroup() {
        return "s11s-" + UUID.randomUUID();
    }

    // =================================================================================
    // 정답 1 — 프로듀서 테스트
    // =================================================================================
    /*
     * 핵심은 코드가 아니라 **값을 어떤 타입으로 받느냐** 입니다.
     *
     * 이 테스트에서 값은 OrderCreated 가 아니라 String 으로 역직렬화합니다. 이유는 이렇습니다.
     *
     *   프로듀서 테스트는 "프로듀서가 올바른 토픽·파티션·키·본문으로 발행했는가" 만 검증해야
     *   합니다. 그런데 값을 도메인 타입으로 받으면 JsonDeserializer 가 끼어들고, 그 순간
     *   __TypeId__ 헤더와 spring.json.trusted.packages 가 검증 경로에 들어옵니다.
     *
     *   그 설정이 어긋나면 SerializationException 이 나거나, 최악의 경우 레코드가 조용히
     *   버려져 records.count() == 0 이 됩니다. 그러면 테스트 실패 메시지는
     *   "expected 3 but was 0" 인데, 원인이
     *     ① 프로듀서가 발행을 안 했다        (진짜 잡고 싶었던 버그)
     *     ② 컨슈머 쪽 역직렬화가 실패했다    (이 테스트의 관심사가 아님)
     *   둘 중 무엇인지 **구분이 안 됩니다.**
     *
     *   String 으로 받으면 역직렬화가 절대 실패하지 않으므로, 실패는 항상 ① 뿐입니다.
     *   테스트가 거짓말하지 않는다는 것은 이런 뜻입니다.
     *
     * 두 번째 포인트는 getSingleRecord 대신 getRecords 를 쓴 것입니다.
     * getSingleRecord 는 "정확히 1건" 을 보장하지 않습니다. 첫 1건을 꺼낼 뿐이라
     * 프로듀서가 실수로 2건을 발행한 버그를 그대로 통과시킵니다.
     * **개수 단언은 반드시 getRecords(...).count()** 입니다.
     */
    @SpringBootTest
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("정답 1 — 프로듀서 테스트")
    static class A1ProducerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void 세_건을_검증한다() {
            Map<String, Object> props =
                    KafkaTestUtils.consumerProps(uniqueGroup(), "false", broker);

            try (Consumer<String, String> consumer =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(consumer, "orders");

                for (int seq = 1; seq <= 3; seq++) {
                    OrderCreated event = OrderCreated.of(seq);
                    kafkaTemplate.send("orders", event.orderId(), event);
                }
                kafkaTemplate.flush();

                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 3);

                // ① 개수
                assertThat(records.count()).isEqualTo(3);

                // ② 키
                List<String> keys = new ArrayList<>();
                List<Integer> partitions = new ArrayList<>();
                for (ConsumerRecord<String, String> r : records) {
                    keys.add(r.key());
                    partitions.add(r.partition());
                }
                assertThat(keys)
                        .containsExactlyInAnyOrder("ORD-0001", "ORD-0002", "ORD-0003");

                // ③ 파티션 범위
                assertThat(partitions).allMatch(p -> p >= 0 && p <= 2);
            }
        }
    }

    // =================================================================================
    // 정답 2 — waitForAssignment 실측
    // =================================================================================
    /*
     * 실측 결과
     *
     *   | 조건                      | 30회 중 실패 | 실패율 |
     *   |---------------------------|-------------:|-------:|
     *   | waitForAssignment 없음    |            9 |    30% |
     *   | waitForAssignment 있음    |            0 |     0% |
     *
     * 30% 는 우연이 아닙니다. 계산해 보면 이렇습니다.
     *
     *   컨테이너가 기동하면 그룹에 조인해 파티션 3개를 할당받는데, 이 과정은 수백 ms 걸립니다.
     *   테스트 스레드는 그걸 기다리지 않고 곧바로 send 를 합니다.
     *   auto.offset.reset=latest 인 새 그룹이므로, 컨슈머가 **아직 붙지 않은 파티션**으로 간
     *   메시지는 "그 파티션의 현재 끝" 뒤에 놓이지 않고 앞에 놓여, 나중에 붙은 컨슈머 눈에
     *   보이지 않습니다.
     *
     *   메시지 3건이 키 해싱으로 파티션에 흩어지고, 그중 아직 미할당인 파티션으로 간 것이
     *   하나라도 있으면 그 실행은 실패합니다. 할당 진행 상황에 따라 다르지만, 파티션 3개 중
     *   평균적으로 1개가 늦게 붙는다고 보면 실패 확률이 대략 1/3 = 33% 이고,
     *   30회 중 9회(30%) 는 그 값에 정확히 부합합니다.
     *
     * 여기서 나오는 두 번째 교훈이 waitForAssignment 의 **두 번째 인자**입니다.
     *
     *   ContainerTestUtils.waitForAssignment(container, 1);   // ← 틀림
     *
     * 이 인자는 "컨테이너가 받아야 할 파티션 **총 개수**" 입니다. 1 을 넣으면 파티션 하나만
     * 할당돼도 통과해 버려서, 나머지 두 파티션은 여전히 미할당인 채로 send 가 나갑니다.
     * 결과적으로 실패율이 30% → 20% 로 줄 뿐, **여전히 간헐 실패합니다.**
     * broker.getPartitionsPerTopic() 을 쓰면 @EmbeddedKafka(partitions=N) 과 항상 일치합니다.
     *
     * 마지막으로, 반복 실행 시 --rerun-tasks 를 빠뜨리면 Gradle 이 UP-TO-DATE 로 건너뛰어
     * 30회를 돌린 줄 알았는데 실제로는 1회만 돈 것이 됩니다. 이걸 모르면 "고쳤더니 30회 다
     * 통과하네" 라는 **잘못된 결론**에 도달합니다.
     */
    @SpringBootTest(classes = {Fixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=latest"
    })
    @DisplayName("정답 2 — waitForAssignment")
    static class A2ListenerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        Fixture fixture;

        @Autowired
        KafkaListenerEndpointRegistry registry;

        @Autowired
        EmbeddedKafkaBroker broker;

        @BeforeEach
        void setUp() {
            fixture.reset();
            for (MessageListenerContainer c : registry.getListenerContainers()) {
                // 두 번째 인자는 파티션 "총 개수". 1 을 넣으면 여전히 간헐 실패합니다.
                ContainerTestUtils.waitForAssignment(c, broker.getPartitionsPerTopic());
            }
        }

        @Test
        void 항상_3건을_받는다() {
            for (int seq = 1; seq <= 3; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                kafkaTemplate.send("orders", event.orderId(), event);
            }

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(fixture.received()).hasSize(3));
        }
    }

    // =================================================================================
    // 정답 3 — Thread.sleep → awaitility
    // =================================================================================
    /*
     * 실측 결과
     *
     *   | 방식          | 총 소요 시간 | 테스트당 평균 대기 |
     *   |---------------|-------------:|-------------------:|
     *   | Thread.sleep  |       12.4초 |             1000ms |
     *   | awaitility    |        2.1초 |               83ms |
     *
     * 테스트 4개짜리 클래스 하나에서만 10초를 벌었습니다. 스위트 전체(12개 클래스)로 넓히면
     * 42.0초 → 6.8초 였습니다.
     *
     * 여기서 가장 중요한 사실은 이것입니다.
     *
     *   **atMost 는 상한일 뿐, 대기 시간이 아닙니다.**
     *
     * atMost(1초) 를 atMost(5초) 로 바꿔도 성공 경로의 시간은 **전혀 늘지 않습니다.**
     * 조건이 83ms 만에 참이 되면 83ms 만 쓰고 즉시 다음으로 갑니다. atMost 는 "이만큼
     * 지나도 안 되면 포기하고 실패로 처리한다" 는 뜻이지, "이만큼 기다린다" 가 아닙니다.
     *
     * 이걸 오해해서 atMost 를 아끼는 사람이 많습니다. atMost(500ms) 로 빡빡하게 잡아 두면
     * 로컬에서는 통과하지만, 부하가 걸린 CI 러너에서는 브로커 응답이 800ms 걸려 실패합니다.
     * 그러고는 "테스트가 불안정하다" 며 @Disabled 를 붙이게 됩니다.
     * **atMost 는 넉넉하게(5~10초), pollInterval 은 짧게(50ms)** 가 정답입니다.
     * 비용이 0 이므로 넉넉히 잡지 않을 이유가 없습니다.
     *
     * 한 가지 함정을 덧붙입니다. untilAsserted 의 람다는 **조건이 참이 될 때까지 반복 실행**
     * 됩니다. 그 안에 send 를 넣거나 카운터를 증가시키면 폴링 횟수만큼 반복돼, 4건을 보내려던
     * 것이 40건이 됩니다. 람다 안에는 **읽기와 단언만** 넣으십시오.
     */
    @SpringBootTest(classes = {Fixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("정답 3 — awaitility")
    static class A3TimingTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        Fixture fixture;

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

        /** 대기 시간을 직접 재고 싶으면 nanoTime 으로 감싸십시오. */
        private void awaitCount(int expected) {
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(5))          // 넉넉하게. 비용 0
                    .pollInterval(Duration.ofMillis(50))   // 짧게
                    .untilAsserted(() -> assertThat(fixture.received()).hasSize(expected));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[awaitility] expected=%d elapsed=%dms%n", expected, elapsedMs);
        }
    }

    // =================================================================================
    // 정답 4 — Testcontainers
    // =================================================================================
    /*
     * static 이 두 군데 모두 **필수** 입니다. 이유가 서로 다릅니다.
     *
     * ① @Container static final KafkaContainer KAFKA
     *
     *    Testcontainers 의 JUnit5 확장은 필드가 static 이면 **클래스당 1회**(BeforeAll/AfterAll),
     *    인스턴스 필드면 **테스트 메서드마다**(BeforeEach/AfterEach) 컨테이너를 띄웁니다.
     *
     *      static 있음 : 8.4초 × 1 = 8.4초
     *      static 없음 : 8.4초 × 3 = 25.2초   ← 메서드가 3개일 때
     *
     *    테스트가 느려지는 것으로 끝나지 않습니다. 매번 새 브로커라 토픽·오프셋이 초기화되므로,
     *    "앞 테스트가 만든 상태를 뒤 테스트가 이어받는" 시나리오를 아예 짤 수 없게 됩니다.
     *    반대로 그런 의존을 의도치 않게 갖고 있던 테스트는 static 을 붙이는 순간 깨집니다.
     *    (그건 테스트가 원래 잘못돼 있었다는 신호입니다.)
     *
     * ② @DynamicPropertySource static void kafkaProperties(...)
     *
     *    이 메서드는 **스프링 컨텍스트가 만들어지기 전에** 호출돼야 합니다. bootstrap-servers 는
     *    ProducerFactory / ConsumerFactory 빈이 생성될 때 읽히므로, 컨텍스트 생성 후에
     *    바꿔 봐야 아무 효과가 없습니다. 그래서 스프링은 인스턴스가 존재하기 전에 호출할 수 있는
     *    static 메서드만 받아들이고, 아니면 기동 시점에 이렇게 실패합니다.
     *
     *      IllegalArgumentException: @DynamicPropertySource method 'kafkaProperties' must be static
     *
     *    이건 그나마 **시끄럽게 실패해서 다행인** 경우입니다.
     *
     * ③ registry.add 에 값이 아니라 **Supplier** 를 넘기는 것도 이유가 있습니다.
     *
     *      registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);  // O
     *      registry.add("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers()); // X
     *
     *    후자는 이 메서드가 호출되는 시점에 값을 평가하는데, 그때 컨테이너가 아직 기동 전이면
     *    포트가 정해지지 않아 엉뚱한 주소가 박힙니다. Supplier 로 넘기면 실제로 필요한 순간에
     *    평가됩니다.
     *
     * ④ withReuse(true) 는 로컬에서만 쓰십시오.
     *
     *    ~/.testcontainers.properties 에 testcontainers.reuse.enable=true 가 있어야 동작하며,
     *    두 번째 실행부터 8.4초 → 0.2초가 됩니다. 다만 **데이터도 함께 남습니다.**
     *    앞 실행의 토픽과 커밋된 오프셋이 그대로 있으므로, 토픽명과 그룹 id 를 반드시 UUID 로
     *    만들어야 합니다. CI 는 매번 깨끗한 환경이 낫기 때문에 꺼 두는 것이 원칙입니다.
     */
    @Tag("integration")
    @Testcontainers
    @SpringBootTest(classes = {Fixture.class})
    @DisplayName("정답 4 — Testcontainers")
    static class A4TestcontainersTest {

        @Container
        static final KafkaContainer KAFKA =
                new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                        .withReuse(false);

        @DynamicPropertySource
        static void kafkaProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
            registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        }

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        Fixture fixture;

        @Test
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
    // 정답 5 — 그룹 id 오염
    // =================================================================================
    /*
     * 고정 그룹일 때 로그
     *
     *   INFO 21044 --- [           main] o.a.k.c.c.internals.ConsumerCoordinator  :
     *       [Consumer clientId=consumer-s11e-fixed-1, groupId=s11e-fixed] Setting offset
     *       for partition orders-0 to the committed offset FetchPosition{offset=1, ...}
     *   → 커밋된 오프셋 1 에서 시작. 이미 읽은 메시지는 다시 안 옵니다.
     *
     *   expected: 1 but was: 0
     *
     * UUID 그룹일 때 로그
     *
     *   INFO 21044 --- [           main] o.a.k.c.c.internals.ConsumerCoordinator  :
     *       [Consumer clientId=consumer-s11s-3f9a1c02-...-1, groupId=s11s-3f9a1c02-...]
     *       Found no committed offset for partition orders-0
     *   INFO 21044 --- [           main] o.a.k.c.c.internals.SubscriptionState    :
     *       resetting offset for partition orders-0 to position FetchPosition{offset=0, ...}
     *   → 커밋 이력이 없어 처음부터. 정상입니다.
     *
     * @DirtiesContext 로는 왜 안 고쳐지는가
     *
     *   @DirtiesContext 는 **스프링 컨텍스트**를 버리고 새로 만듭니다. 그런데 오염이 남아 있는
     *   곳은 스프링이 아니라 **브로커의 __consumer_offsets 토픽**입니다.
     *
     *   컨텍스트를 아무리 버려도 브로커는 그대로 살아 있고(@EmbeddedKafka 는 컨텍스트 캐싱으로
     *   재사용됩니다), 그 안의 "s11e-fixed 그룹은 orders-0 을 오프셋 1까지 읽었다" 는 기록도
     *   그대로입니다. 새 컨텍스트가 같은 그룹 id 로 붙는 순간 오염이 똑같이 재현됩니다.
     *
     *   게다가 @DirtiesContext(AFTER_METHOD) 는 테스트마다 컨텍스트를 새로 띄우므로
     *   2.1초 × 테스트 수만큼 느려집니다. **비싸면서 효과도 없는** 최악의 선택입니다.
     *
     *   결론: **격리는 식별자 수준에서** 합니다. 그룹 id 를 UUID 로 만드는 것은 비용이 0 이고
     *   효과는 완벽합니다. 토픽명까지 UUID 로 하면 더 확실합니다.
     */
    @SpringBootTest
    @EmbeddedKafka(partitions = 1,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("정답 5 — UUID 그룹으로 격리")
    static class A5IsolationTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void a_먼저_읽고_커밋한다() {
            kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));
            kafkaTemplate.flush();

            readOnce(uniqueGroup());   // ← 매번 새 그룹
        }

        @Test
        void b_다시_읽어도_보인다() {
            kafkaTemplate.send("orders", "ORD-0002", OrderCreated.of(2));
            kafkaTemplate.flush();

            readOnce(uniqueGroup());   // ← 앞 테스트의 커밋과 무관
        }

        private void readOnce(String group) {
            Map<String, Object> props = KafkaTestUtils.consumerProps(group, "true", broker);
            try (Consumer<String, String> consumer =
                         new DefaultKafkaConsumerFactory<>(props,
                                 new StringDeserializer(), new StringDeserializer())
                                 .createConsumer()) {

                broker.consumeFromAnEmbeddedTopic(consumer, "orders");
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 1);
                consumer.commitSync();

                assertThat(records.count()).isGreaterThanOrEqualTo(1);
            }
        }
    }

    // =================================================================================
    // 정답 6 — DLT 도착 검증
    // =================================================================================
    /*
     * DLT_ORIGINAL_OFFSET 은 문자열이 아니라 **long 8바이트**입니다.
     *
     *   byte[] raw = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value();
     *   long offset = ByteBuffer.wrap(raw).getLong();      // 정답
     *
     *   new String(raw, UTF_8)                             // 오답 — 깨진 문자가 나옵니다
     *
     * 반면 DLT_EXCEPTION_FQCN 과 DLT_EXCEPTION_MESSAGE 는 UTF-8 문자열입니다.
     * **같은 헤더 맵 안에 인코딩이 다른 값이 섞여 있다**는 점을 기억하십시오.
     *
     * 참고로 @Header 로 받으면 Spring 이 변환해 줍니다.
     *
     *   @DltHandler
     *   public void onDlt(OrderCreated event,
     *                     @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET) long offset,
     *                     @Header(KafkaHeaders.DLT_EXCEPTION_FQCN) String fqcn) { ... }
     *
     * 테스트에서 컨슈머로 직접 받을 때는 그 변환이 없으므로 손으로 풀어야 합니다.
     *
     * ── 왜 세 가지를 다 봐야 하는가 ─────────────────────────────────────────────
     *
     * 키만 단언하면:
     *   ORD-0001 이 **다른 이유로** DLT 에 가도 통과합니다. 예를 들어 역직렬화 실패나
     *   NullPointerException 으로 갔는데도 "재시도 3회 후 DLT" 를 검증했다고 착각합니다.
     *   리팩터링으로 예외 타입이 바뀌어도 테스트는 계속 초록불이라, 회귀를 못 잡습니다.
     *
     * 예외만 단언하면:
     *   **다른 주문**이 IllegalStateException 으로 DLT 에 가도 통과합니다. 여러 건을
     *   발행하는 테스트에서 엉뚱한 레코드를 잡고 있어도 모릅니다.
     *
     * 원본 오프셋까지 보면:
     *   "원본 토픽의 그 위치에 있던 그 메시지가, 그 예외로, DLT 에 갔다" 가 확정됩니다.
     *   운영에서 DLT 를 재처리할 때 실제로 필요한 정보가 정확히 이 셋이기도 합니다.
     */
    @SpringBootTest(classes = {FailingFixture.class})
    @EmbeddedKafka(partitions = 1,
            topics = {"orders", "orders.DLT"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=earliest"
    })
    @DisplayName("정답 6 — DLT 도착 검증")
    static class A6DltTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
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

                // ① 어떤 메시지가 갔는가
                assertThat(failed.key()).isEqualTo("ORD-0001");

                // ② 왜 갔는가 — UTF-8 문자열
                byte[] fqcnBytes =
                        failed.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN).value();
                assertThat(new String(fqcnBytes, StandardCharsets.UTF_8))
                        .isEqualTo("java.lang.IllegalStateException");

                // ③ 어디에서 왔는가 — long 8바이트
                byte[] offsetBytes =
                        failed.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value();
                long originalOffset = ByteBuffer.wrap(offsetBytes).getLong();
                assertThat(originalOffset).isEqualTo(0L);

                // 보너스: 원본 토픽 이름까지 확인해 두면 재처리 도구를 만들 때 그대로 씁니다
                byte[] topicBytes =
                        failed.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value();
                assertThat(new String(topicBytes, StandardCharsets.UTF_8)).isEqualTo("orders");
            }
        }
    }

    // =================================================================================
    // 픽스처
    // =================================================================================

    static class Fixture {

        private final List<OrderCreated> received = new CopyOnWriteArrayList<>();

        @KafkaListener(id = "s11s-fixture", topics = "orders",
                groupId = "#{T(com.example.order.step11.Solution).uniqueGroup()}")
        public void onMessage(OrderCreated event) {
            received.add(event);
        }

        void reset() {
            received.clear();
        }

        List<OrderCreated> received() {
            return received;
        }
    }

    static class FailingFixture {

        @KafkaListener(id = "s11s-failing", topics = "orders",
                groupId = "#{T(com.example.order.step11.Solution).uniqueGroup()}")
        public void onMessage(OrderCreated event) {
            throw new IllegalStateException("재고가 부족합니다: " + event.orderId());
        }
    }
}
