package com.example.order.step11;

/*
 * =====================================================================================
 * Step 11 — 테스트 : Exercise (6문제)
 * =====================================================================================
 *
 * 배치 위치 : src/test/java/com/example/order/step11/Exercise.java
 * 정답       : Solution.java  (먼저 풀어 보고 여십시오)
 *
 * 실행
 *   ./gradlew test --tests 'com.example.order.step11.Exercise$Q1ProducerTest'
 *
 * 문제 성격이 다르니 순서대로 풀 필요는 없습니다.
 *   - 문제 1·6 : 검증 코드를 작성하는 문제
 *   - 문제 2·3 : **측정 결과를 주석 표에 기록**하는 문제 (코드만 맞히면 절반만 푼 것입니다)
 *   - 문제 4·5 : 테스트 인프라를 구성하는 문제
 *
 * ⚠️ 문제 4 는 도커가 필요합니다. 도커가 없으면
 *    IllegalStateException: Could not find a valid Docker environment 로 실패합니다.
 *    나머지 문제까지 빨간불이 되지 않도록 @Tag("integration") 을 반드시 붙이십시오.
 *    그것이 이 문제의 요구사항 절반입니다.
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

public final class Exercise {

    private Exercise() {
    }

    static String uniqueGroup() {
        return "s11e-" + UUID.randomUUID();
    }

    // =================================================================================
    // 문제 1. @EmbeddedKafka(partitions = 3) 로 프로듀서 테스트를 작성하십시오.
    // =================================================================================
    //
    // 요구사항
    //   - ORD-0001 ~ ORD-0003 세 건을 발행한다 (OrderCreated.of(1..3))
    //   - KafkaTestUtils.getRecords 로 받아 아래 세 가지를 검증한다
    //       ① 정확히 3건인가        (getSingleRecord 로는 개수 검증이 안 됩니다)
    //       ② 키가 세 개 다 있는가
    //       ③ 각 레코드의 파티션이 0~2 범위인가
    //   - 값의 역직렬화 타입을 무엇으로 할지 스스로 정하고, **왜 그렇게 정했는지** 주석에 쓰십시오
    //
    // 확인: records.count() == 3, 키 3종이 순서 무관하게 모두 존재
    //
    @SpringBootTest
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("문제 1 — 프로듀서 테스트")
    static class Q1ProducerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void 세_건을_검증한다() {
            // 여기에 작성:
            //
            // 1) KafkaTestUtils.consumerProps(uniqueGroup(), "false", broker) 로 props 를 만들고
            // 2) DefaultKafkaConsumerFactory 로 Consumer 를 만든 뒤
            // 3) broker.consumeFromAnEmbeddedTopic(consumer, "orders") 로 구독하고
            // 4) 발행하고
            // 5) KafkaTestUtils.getRecords(consumer, Duration, 3) 으로 받아 단언하십시오
            //
            // 값 타입 선택 이유:
            //   (여기에 쓰십시오)

            org.junit.jupiter.api.Assertions.fail("아직 작성하지 않았습니다");
        }
    }

    // =================================================================================
    // 문제 2. waitForAssignment 를 일부러 빼고 30회 반복 실행해 실패 횟수를 세십시오.
    // =================================================================================
    //
    // 요구사항
    //   - 아래 @BeforeEach 에서 waitForAssignment 를 호출하지 **않은** 상태로 30회 돌린다
    //   - 실패 횟수를 아래 표에 기록한다
    //   - waitForAssignment 를 추가하고 다시 30회 돌려 기록한다
    //   - 왜 그 비율이 나오는지 주석에 설명한다
    //
    // 반복 실행 (--rerun-tasks 가 없으면 Gradle 이 UP-TO-DATE 로 건너뛰어 30회가 1회가 됩니다)
    //
    //   for i in $(seq 1 30); do \
    //     ./gradlew test --tests 'com.example.order.step11.Exercise$Q2ListenerTest' \
    //       --rerun-tasks -q || echo "FAIL $i"; \
    //   done
    //
    // 관측 기록:
    //   | 조건                      | 30회 중 실패 | 실패율 |
    //   |---------------------------|-------------:|-------:|
    //   | waitForAssignment 없음    |              |        |
    //   | waitForAssignment 있음    |              |        |
    //
    //   실패율이 그 값이 나오는 이유:
    //     (여기에 쓰십시오)
    //
    // 확인: 없음 → 실패 다수 / 있음 → 0회
    //
    @SpringBootTest(classes = {Q2Fixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=latest"
    })
    @DisplayName("문제 2 — waitForAssignment 누락 재현")
    static class Q2ListenerTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        Q2Fixture fixture;

        @Autowired
        KafkaListenerEndpointRegistry registry;

        @Autowired
        EmbeddedKafkaBroker broker;

        @BeforeEach
        void setUp() {
            fixture.reset();

            // 여기에 작성: (2단계에서만) 모든 컨테이너에 대해 waitForAssignment 를 호출하십시오.
            //             두 번째 인자에 무엇을 넣어야 하는지 신중히 고르십시오.
        }

        @Test
        void 리스너가_3건을_받는다() {
            for (int seq = 1; seq <= 3; seq++) {
                kafkaTemplate.send("orders", OrderCreated.of(seq).orderId(), OrderCreated.of(seq));
            }

            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(fixture.received()).hasSize(3));
        }
    }

    // =================================================================================
    // 문제 3. Thread.sleep(1000) 테스트 4개를 awaitility 로 교체하고 시간을 측정하십시오.
    // =================================================================================
    //
    // 요구사항
    //   - 아래 네 테스트는 전부 Thread.sleep(1000) 으로 대기한다. awaitility 로 바꾼다
    //   - ./gradlew test 의 총 소요 시간을 before/after 로 측정한다
    //   - **테스트 1개당 평균 대기 시간**도 잰다 (System.nanoTime() 으로 await 앞뒤)
    //   - atMost 를 5초로 늘리면 성공 경로 시간이 늘어나는지 확인하고 주석에 답한다
    //
    // 측정 기록:
    //   | 방식          | 총 소요 시간 | 테스트당 평균 대기 |
    //   |---------------|-------------:|-------------------:|
    //   | Thread.sleep  |              |                    |
    //   | awaitility    |              |                    |
    //
    //   atMost 를 1초 → 5초로 늘리면 성공 경로 시간은?
    //     (여기에 쓰십시오)
    //
    // 확인: 총 시간이 크게 줄고, 평균 대기가 1000ms → 수십 ms 로 떨어짐
    //
    @SpringBootTest(classes = {Q2Fixture.class})
    @EmbeddedKafka(partitions = 3,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("문제 3 — sleep 을 awaitility 로")
    static class Q3TimingTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        Q2Fixture fixture;

        @BeforeEach
        void setUp() {
            fixture.reset();
            // 여기에 작성: waitForAssignment (문제 2 에서 배운 것)
        }

        @Test
        void 발행_1건() throws Exception {
            send(1);
            Thread.sleep(1000);                       // 여기에 작성: awaitility 로 교체
            assertThat(fixture.received()).hasSize(1);
        }

        @Test
        void 발행_2건() throws Exception {
            send(2);
            Thread.sleep(1000);                       // 여기에 작성: awaitility 로 교체
            assertThat(fixture.received()).hasSize(2);
        }

        @Test
        void 발행_3건() throws Exception {
            send(3);
            Thread.sleep(1000);                       // 여기에 작성: awaitility 로 교체
            assertThat(fixture.received()).hasSize(3);
        }

        @Test
        void 발행_4건() throws Exception {
            send(4);
            Thread.sleep(1000);                       // 여기에 작성: awaitility 로 교체
            assertThat(fixture.received()).hasSize(4);
        }

        private void send(int count) {
            for (int seq = 1; seq <= count; seq++) {
                kafkaTemplate.send("orders", OrderCreated.of(seq).orderId(), OrderCreated.of(seq));
            }
        }
    }

    // =================================================================================
    // 문제 4. Testcontainers 로 confluentinc/cp-kafka:7.6.1 통합 테스트를 작성하십시오.
    // =================================================================================
    //
    // 요구사항
    //   - @Testcontainers + @Container 로 KafkaContainer 를 띄운다
    //   - @DynamicPropertySource 로 spring.kafka.bootstrap-servers 를 주입한다
    //   - @Tag("integration") 으로 기본 test 태스크에서 제외한다
    //   - build.gradle 에 integrationTest 태스크를 추가한다 (아래 주석 참고)
    //   - @Container 필드와 @DynamicPropertySource 메서드에 static 이 필요한지 판단하고
    //     그 이유를 주석에 쓴다
    //
    //   // build.gradle
    //   // tasks.named('test')      { useJUnitPlatform { excludeTags 'integration' } }
    //   // tasks.register('integrationTest', Test) {
    //   //     useJUnitPlatform { includeTags 'integration' }
    //   // }
    //
    // 확인: ./gradlew test        → 이 클래스가 실행되지 않음
    //       ./gradlew integrationTest → 이 클래스만 실행되고 통과
    //
    @Tag("integration")
    @DisplayName("문제 4 — Testcontainers 통합 테스트")
    static class Q4TestcontainersTest {

        // 여기에 작성:
        //   @Container ??? KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(...));
        //
        //   static 이 필요한가?
        //     (여기에 답을 쓰십시오)

        // 여기에 작성:
        //   @DynamicPropertySource
        //   ??? void kafkaProperties(DynamicPropertyRegistry registry) { ... }
        //
        //   static 이 필요한가? 그 이유는?
        //     (여기에 답을 쓰십시오)

        @Test
        void 실제_브로커에서_동작한다() {
            // 여기에 작성:
            org.junit.jupiter.api.Assertions.fail("아직 작성하지 않았습니다");
        }
    }

    // =================================================================================
    // 문제 5. 같은 그룹 id 로 간섭을 재현한 뒤, UUID 로 바꿔 해결하십시오.
    // =================================================================================
    //
    // 요구사항
    //   - 아래 두 테스트가 FIXED_GROUP 을 공유한다. 이 상태로 클래스 전체를 실행해
    //     두 번째 테스트가 메시지를 못 받는 것을 확인한다
    //   - 그룹 id 를 uniqueGroup() 으로 바꿔 해결한다
    //   - 두 경우의 로그를 비교해 아래에 붙인다
    //   - @DirtiesContext 로 고치려 시도해 보고, 왜 안 되는지 설명한다
    //
    // 확인: 고정 그룹: 실패 / UUID 그룹: 성공
    //       둘 다 성공했다면 오염 재현에 실패한 것이니 실행 순서를 확인하십시오
    //
    //   고정 그룹일 때 로그:
    //     (여기에 붙이십시오)
    //   UUID 그룹일 때 로그:
    //     (여기에 붙이십시오)
    //   @DirtiesContext 가 안 통하는 이유:
    //     (여기에 쓰십시오)
    //
    @SpringBootTest
    @EmbeddedKafka(partitions = 1,
            topics = {"orders"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
    @DisplayName("문제 5 — 그룹 id 오염")
    static class Q5PollutionTest {

        private static final String FIXED_GROUP = "s11e-fixed";   // 여기에 작성: 바꿔 보십시오

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void a_먼저_읽고_커밋한다() {
            kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));
            kafkaTemplate.flush();

            // 여기에 작성: FIXED_GROUP 으로 컨슈머를 만들어 1건 읽고 commitSync()
            org.junit.jupiter.api.Assertions.fail("아직 작성하지 않았습니다");
        }

        @Test
        void b_같은_그룹으로_다시_읽는다() {
            // 여기에 작성: 같은 그룹으로 읽었을 때 몇 건이 오는지 단언하십시오
            org.junit.jupiter.api.Assertions.fail("아직 작성하지 않았습니다");
        }
    }

    // =================================================================================
    // 문제 6. 3회 실패 후 orders.DLT 도착을 검증하십시오.
    // =================================================================================
    //
    // 요구사항
    //   - 리스너가 항상 IllegalStateException 을 던지게 한다
    //   - orders.DLT 에 도착한 레코드를 잡아 **세 가지를 모두** 단언한다
    //       ① 키                     : "ORD-0001"
    //       ② DLT_EXCEPTION_FQCN     : "java.lang.IllegalStateException"
    //       ③ DLT_ORIGINAL_OFFSET    : 원본 오프셋 (byte[] 8바이트 → long 으로 변환 필요)
    //   - 왜 셋을 다 봐야 하는지 주석에 쓴다 (하나만 보면 어떤 버그를 놓치는가)
    //
    // 확인: 세 단언이 모두 통과. ③ 을 그냥 문자열로 비교하면 실패합니다
    //
    @SpringBootTest(classes = {Q6FailingFixture.class})
    @EmbeddedKafka(partitions = 1,
            topics = {"orders", "orders.DLT"},
            brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
    @TestPropertySource(properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=earliest"
    })
    @DisplayName("문제 6 — DLT 도착 검증")
    static class Q6DltTest {

        @Autowired
        KafkaTemplate<String, OrderCreated> kafkaTemplate;

        @Autowired
        EmbeddedKafkaBroker broker;

        @Test
        void DLT까지_간다() {
            // 여기에 작성:
            //
            //   키만 단언하면 놓치는 버그:
            //     (여기에 쓰십시오)
            //   예외만 단언하면 놓치는 버그:
            //     (여기에 쓰십시오)

            org.junit.jupiter.api.Assertions.fail("아직 작성하지 않았습니다");
        }
    }

    // =================================================================================
    // 픽스처
    // =================================================================================

    static class Q2Fixture {

        private final List<OrderCreated> received = new CopyOnWriteArrayList<>();

        @KafkaListener(id = "s11e-fixture", topics = "orders",
                groupId = "#{T(com.example.order.step11.Exercise).uniqueGroup()}")
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

    static class Q6FailingFixture {

        @KafkaListener(id = "s11e-failing", topics = "orders",
                groupId = "#{T(com.example.order.step11.Exercise).uniqueGroup()}")
        public void onMessage(OrderCreated event) {
            throw new IllegalStateException("재고가 부족합니다: " + event.orderId());
        }
    }

    /** 문제 1·5 에서 쓸 수 있는 보조 메서드입니다. */
    static Consumer<String, String> stringConsumer(EmbeddedKafkaBroker broker, String group) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(group, "false", broker);
        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
    }

    /** 받은 레코드의 키만 뽑는 보조 메서드입니다. */
    static List<String> keysOf(ConsumerRecords<String, String> records) {
        List<String> keys = new CopyOnWriteArrayList<>();
        for (ConsumerRecord<String, String> r : records) {
            keys.add(r.key());
        }
        return keys;
    }
}
