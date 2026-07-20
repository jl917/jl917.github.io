# Step 11 — 테스트

> **학습 목표**
> - `@EmbeddedKafka` 로 JVM 안에 브로커를 띄우고, 기동 시간과 `${spring.embedded.kafka.brokers}` 연결을 실측한다
> - `KafkaTestUtils` 의 `consumerProps` / `producerProps` / `getSingleRecord` / `getRecords` 로 리스너 없이 직접 검증한다
> - `ContainerTestUtils.waitForAssignment` 를 빠뜨렸을 때 **메시지를 놓치는 간헐 실패**를 재현하고 고친다
> - `Thread.sleep` 을 `awaitility` 로 교체해 테스트 스위트 총 시간을 **42초 → 6.8초**로 줄인다
> - Testcontainers 로 실제 브로커를 띄우고, `@EmbeddedKafka` 와의 차이를 9개 축으로 비교해 선택 기준을 만든다
> - 컨슈머 그룹 id 재사용과 **컨텍스트 캐싱**이 만드는 테스트 간 오염을 재현하고 격리한다
>
> **선행 스텝**: Step 10 — 리스너 컨테이너 제어
> **예상 소요**: 90분

---

## 11-0. 실습 준비

이 스텝의 코드는 지금까지와 달리 **`src/test/java`** 에 들어갑니다.

```
spring-kafka-lab/src/test/java/com/example/order/step11/
├── Practice.java
├── Exercise.java
└── Solution.java
```

의존성은 [실습 프로젝트 셋업](../project/)의 `build.gradle` 에 이미 들어 있습니다. 다시 확인합니다.

```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testImplementation 'org.springframework.kafka:spring-kafka-test'   // ← @EmbeddedKafka, KafkaTestUtils
testImplementation 'org.awaitility:awaitility'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.testcontainers:kafka'
```

```bash
./gradlew dependencies --configuration testRuntimeClasspath | grep -E 'spring-kafka-test|awaitility|testcontainers:kafka'
```

**결과**
```
+--- org.springframework.kafka:spring-kafka-test -> 3.1.4
|    +--- org.apache.kafka:kafka-clients:3.6.1:test
|    \--- org.apache.kafka:kafka_2.13:3.6.1
+--- org.awaitility:awaitility -> 4.2.0
+--- org.testcontainers:kafka -> 1.19.7
```

> 💡 `spring-kafka-test` 는 **브로커 본체(`kafka_2.13`)를 통째로 끌고 옵니다.** 약 70MB 입니다.
> `implementation` 이 아니라 반드시 `testImplementation` 이어야 합니다. 실수로 `implementation` 에 넣으면 운영 배포 아티팩트에 브로커가 들어갑니다.

테스트 실행:

```bash
./gradlew test --tests 'com.example.order.step11.*'
```

---

## 11-1. Kafka 테스트가 어려운 이유

일반적인 서비스 테스트는 이렇습니다.

```java
service.createOrder(req);
assertThat(repository.findById("ORD-0001")).isPresent();   // 호출이 끝나면 결과가 있다
```

Kafka 는 이 전제가 전부 깨집니다.

| 깨지는 전제 | Kafka 에서 실제로 일어나는 일 |
|---|---|
| 호출이 끝나면 결과가 있다 | `send()` 는 **버퍼에 넣고 즉시 리턴**합니다. 브로커에 도달한 시점은 나중입니다 |
| 검증 시점에 상대가 준비돼 있다 | 리스너는 **파티션을 할당받기 전**일 수 있습니다. 그 사이 발행한 메시지는 안 보입니다 |
| 테스트마다 상태가 초기화된다 | **커밋된 오프셋은 브로커에 남습니다.** 다음 테스트가 같은 그룹 id 를 쓰면 아무것도 못 받습니다 |
| 실패하면 예외가 난다 | 리스너 스레드에서 난 예외는 **테스트 스레드로 전파되지 않습니다.** 테스트는 그냥 통과합니다 |

마지막 줄이 이 스텝의 핵심입니다. 다음 테스트를 보십시오.

```java
@Test
void 주문을_발행한다() throws Exception {
    kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));
    Thread.sleep(1000);
    assertThat(inventoryListener.received).isNotNull();   // ← 통과합니다
}
```

이 테스트는 **거의 항상 통과하지만, 아무것도 검증하지 않습니다.** 리스너가 파티션을 못 받아도, 역직렬화가 실패해도, `received` 를 다른 테스트가 채워 놨어도 통과할 수 있습니다. 그리고 CI 에서 어느 날 갑자기 실패합니다.

```
INFO 14311 --- [    Test worker] o.s.k.t.EmbeddedKafkaBroker              : Started embedded Kafka broker: 127.0.0.1:52117
INFO 14311 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s11-inventory: partitions assigned: [orders-0, orders-1, orders-2]

주문을_발행한다() FAILED
    java.lang.AssertionError:
    Expecting actual not to be null
        at com.example.order.step11.SleepBasedTest.주문을_발행한다(SleepBasedTest.java:47)

1 test completed, 1 failed
```

로그를 보면 리스너는 파티션을 **받았습니다**. 그런데도 실패했습니다. `partitions assigned` 가 `send` 보다 **늦게** 찍혔기 때문입니다. 로컬에서는 1초면 충분했지만 CI 러너에서는 1.4초가 걸렸습니다.

이 스텝은 이 종류의 문제를 하나씩 재현하고 없앱니다.

---

## 11-2. `@EmbeddedKafka` — JVM 안에 브로커를 띄운다

`spring-kafka-test` 는 테스트 JVM 안에서 실제 Kafka 브로커를 기동해 줍니다. Docker 도, 별도 프로세스도 필요 없습니다.

```java
@SpringBootTest
@EmbeddedKafka(
        partitions = 3,
        topics = { "orders" },
        brokerProperties = { "listeners=PLAINTEXT://localhost:0" }
)
class EmbeddedBrokerTest {

    @Autowired
    EmbeddedKafkaBroker broker;

    @Test
    void 브로커가_떴다() {
        System.out.println("brokers = " + broker.getBrokersAsString());
        assertThat(broker.getPartitionsPerTopic()).isEqualTo(3);
    }
}
```

**결과**
```
INFO 14311 --- [    Test worker] o.s.k.t.EmbeddedKafkaKraftBroker         : Starting Kafka in KRaft mode
INFO 14311 --- [    Test worker] kafka.server.KafkaRaftServer              : [KafkaRaftServer nodeId=0] Starting broker
INFO 14311 --- [    Test worker] o.s.k.t.EmbeddedKafkaKraftBroker         : Started embedded Kafka broker: 127.0.0.1:52117
brokers = 127.0.0.1:52117
INFO 14311 --- [    Test worker] c.e.o.s.EmbeddedBrokerTest                : Started EmbeddedBrokerTest in 2.108 seconds (process running for 3.442)
```

**브로커 기동에 2.108초**가 걸렸습니다. 이 시간은 테스트 클래스마다가 아니라 **스프링 컨텍스트마다** 한 번입니다(11-10 참고).

### 포트 0 의 의미

`listeners=PLAINTEXT://localhost:0` 의 `0` 은 **OS 에게 빈 포트를 아무거나 달라**는 뜻입니다. 위 로그에서는 52117 이 잡혔습니다.

> ⚠️ **함정 — 포트를 9092 로 고정하지 마십시오**
> `listeners=PLAINTEXT://localhost:9092` 라고 쓰면, 개발자 로컬에서 `docker compose` 로 띄운 브로커와 **포트가 충돌**합니다.
> 증상은 `BindException: Address already in use` 로 나면 다행이고, 더 나쁜 경우는 **테스트가 임베디드 브로커 대신 도커 브로커에 붙어 버리는 것**입니다.
> 그러면 테스트는 통과하지만 실제로는 로컬 도커의 `orders` 토픽을 오염시키고, **도커가 안 뜬 CI 에서만 실패**합니다.
> 포트는 항상 `0` 으로 두고, 주소는 `${spring.embedded.kafka.brokers}` 로 받으십시오.

### 애플리케이션이 임베디드 브로커를 보게 만들기

`@EmbeddedKafka` 는 기동 후 **`spring.embedded.kafka.brokers`** 라는 시스템 프로퍼티에 실제 주소를 넣어 줍니다. 애플리케이션 설정을 여기에 연결해야 합니다.

```java
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = "orders", brokerProperties = "listeners=PLAINTEXT://localhost:0")
class WiredTest { ... }
```

`src/test/resources/application.yml` 에 한 번만 써 두면 모든 테스트가 물려받습니다. **이 코스는 이 방식을 씁니다.**

```yaml
spring:
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers}
    consumer:
      auto-offset-reset: earliest
```

> ⚠️ **함정 — 이 한 줄을 빼먹으면 테스트가 조용히 `localhost:9092` 로 갑니다**
> `spring.kafka.bootstrap-servers` 의 기본값은 `localhost:9092` 입니다. 임베디드 브로커는 52117 에 떠 있는데 애플리케이션은 9092 로 붙습니다.
> 로컬에서는 도커 브로커가 9092 에 떠 있으니 **테스트가 통과합니다.** CI 에는 아무것도 없으니 다음 로그만 60초 반복됩니다.
> ```
> WARN 14311 --- [ntainer#0-0-C-1] o.a.k.c.NetworkClient                    : [Consumer clientId=consumer-s11-inventory-1, groupId=s11-inventory] Connection to node -1 (localhost/127.0.0.1:9092) could not be established. Broker may not be available.
> ```
> `getBrokersAsString()` 을 한 번 출력해 포트가 임의 포트인지 확인하는 습관을 들이십시오.

---

## 11-3. `KafkaTestUtils` — 리스너 없이 직접 검증한다

리스너를 거치지 않고 **테스트 전용 컨슈머**로 토픽을 직접 읽는 것이 가장 확실한 검증입니다. 리스너 코드의 버그와 프로듀서 코드의 버그가 섞이지 않기 때문입니다.

| 메서드 | 하는 일 |
|---|---|
| `KafkaTestUtils.producerProps(brokers)` | `acks=1`, `String/String` 직렬화가 채워진 프로듀서 설정 맵 |
| `KafkaTestUtils.consumerProps(brokers, group, autoCommit)` | `auto.offset.reset=earliest` 가 이미 들어 있는 컨슈머 설정 맵 |
| `KafkaTestUtils.getSingleRecord(consumer, topic)` | 레코드 **1건**을 기다렸다 반환. 없으면 `IllegalStateException` |
| `KafkaTestUtils.getRecords(consumer, Duration)` | 그 시간 동안 받은 레코드 **전부** 반환 |
| `broker.consumeFromAnEmbeddedTopic(consumer, topic)` | 구독 + **할당 완료까지 대기**까지 한 번에 |

```java
Map<String, Object> props = KafkaTestUtils.consumerProps(
        broker.getBrokersAsString(), "s11-probe-" + UUID.randomUUID(), "true");
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

try (Consumer<String, String> consumer =
             new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                     .createConsumer()) {

    broker.consumeFromAnEmbeddedTopic(consumer, "orders");   // ← 구독 + 할당 대기

    kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));

    ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "orders");
    assertThat(record.key()).isEqualTo("ORD-0001");
}
```

**결과**
```
INFO 14311 --- [    Test worker] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s11-probe-8f2c-1, groupId=s11-probe-8f2c] Successfully joined group with generation Generation{generationId=1}
INFO 14311 --- [ad | producer-1] o.a.k.c.p.internals.TransactionManager   : [Producer clientId=producer-1] ProducerId set to 0 with epoch 0
record = ConsumerRecord(topic = orders, partition = 1, offset = 0, key = ORD-0001,
    value = {"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"})
```

**값을 `String` 으로 받은 것에 주목하십시오.** 역직렬화까지 테스트 컨슈머에 맡기면 "JSON 이 이상해서 실패한 것"과 "메시지가 안 온 것"을 구분하기 어렵습니다. **원문 JSON 을 직접 보는 것이 디버깅에 압도적으로 유리**합니다.

> 💡 **실무 팁 — `getSingleRecord` 는 딱 1건일 때만 쓰십시오.**
> 이름과 달리 "정확히 1건인지" 를 검증하지 않습니다. **먼저 도착한 1건**을 돌려줄 뿐입니다.
> "3건 보냈고 3건 와야 한다"를 검증하려면 `getRecords(consumer, Duration.ofSeconds(5))` 로 받아 `records.count()` 를 확인하십시오.

---

## 11-4. 프로듀서 테스트 — 발행한 내용을 검증한다

Step 02 에서 만든 `OrderPublisher` 를 테스트합니다. 검증할 것은 세 가지입니다: **키**, **파티션**, **본문**.

```java
@Test
void 발행한_주문의_키와_파티션과_본문을_검증한다() {
    for (int seq = 1; seq <= 3; seq++) {
        publisher.publish(OrderCreated.of(seq));
    }

    ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

    assertThat(records.count()).isEqualTo(3);
    assertThat(records).extracting(ConsumerRecord::key)
            .containsExactlyInAnyOrder("ORD-0001", "ORD-0002", "ORD-0003");

    // 같은 키는 항상 같은 파티션 — 결정적이므로 값을 못 박을 수 있습니다
    Map<String, Integer> partitionOf = new HashMap<>();
    records.forEach(r -> partitionOf.put(r.key(), r.partition()));
    assertThat(partitionOf).containsEntry("ORD-0001", 1)
                           .containsEntry("ORD-0002", 0)
                           .containsEntry("ORD-0003", 2);
}
```

**결과**
```
BUILD SUCCESSFUL in 4s
3 tests completed
```

파티션 번호를 **못 박아도 되는 이유**는 [코스 개요](../)에서 설명한 대로 `OrderCreated.of(seq)` 가 결정적이기 때문입니다. 키가 같으면 기본 파티셔너의 murmur2 해시가 같고, 파티션 수가 3 으로 같으면 결과도 같습니다.

> ⚠️ **함정 — `@EmbeddedKafka(partitions = 1)` 로 두고 파티션을 검증하지 마십시오**
> 운영 토픽은 3 파티션인데 테스트는 1 파티션이면, **키 → 파티션 매핑을 검증하는 테스트가 전부 무의미**해집니다. 전부 0 번으로 가니까요.
> 순서 보장이나 파티셔닝 로직이 걸린 테스트라면 **파티션 수를 운영과 같게** 맞추십시오.

---

## 11-5. 리스너 테스트 — `waitForAssignment` 없이는 메시지를 놓친다

이번에는 `@KafkaListener` 가 실제로 메시지를 처리하는지 검증합니다. 리스너는 별도 스레드에서 돌기 때문에 **동기화 장치**가 필요합니다. 고전적인 방법이 `CountDownLatch` 입니다.

```java
@Component
@Profile("step11")
static class LatchListener {
    final CountDownLatch latch = new CountDownLatch(1);
    volatile OrderCreated received;

    @KafkaListener(id = "s11-inventory", topics = "orders", groupId = "s11-inventory")
    public void onMessage(OrderCreated event) {
        this.received = event;
        latch.countDown();
    }
}
```

### 잘못된 테스트

```java
@Test
void 리스너가_메시지를_받는다() throws Exception {
    kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));

    assertThat(listener.latch.await(5, TimeUnit.SECONDS)).isTrue();   // ← 간헐 실패
    assertThat(listener.received.orderId()).isEqualTo("ORD-0001");
}
```

로컬에서 10번 돌리면 10번 통과합니다. CI 에서 돌리면 이렇게 됩니다.

**결과** (CI, 12회 중 3회 실패)
```
INFO 14311 --- [    Test worker] o.s.k.t.EmbeddedKafkaKraftBroker         : Started embedded Kafka broker: 127.0.0.1:52117
INFO 14311 --- [ad | producer-1] o.a.k.c.p.KafkaProducer                  : [Producer clientId=producer-1] Sending record to topic orders partition 1
INFO 14311 --- [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s11-inventory-1, groupId=s11-inventory] Successfully joined group with generation Generation{generationId=1}
INFO 14311 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s11-inventory: partitions assigned: [orders-0, orders-1, orders-2]

리스너가_메시지를_받는다() FAILED
    org.opentest4j.AssertionFailedError:
    expected: true
     but was: false
```

로그 순서를 보십시오. **`Sending record` 가 `partitions assigned` 보다 먼저**입니다.

그런데 왜 메시지를 놓칠까요? 메시지는 토픽에 남아 있는데요. 원인은 **`auto.offset.reset` 이 아니라 그 앞 단계**입니다. 컨슈머 그룹이 처음 조인할 때 **커밋된 오프셋이 없으면** `auto.offset.reset` 이 적용되지만, 컨테이너가 이미 한 번 조인해서 오프셋을 커밋한 상태(같은 컨텍스트에서 앞 테스트가 돌았을 때)라면 **커밋 지점 이후만** 읽습니다. 여기에 `latch` 대기 5초가 겹치면 조인 지연 + 리밸런스 시간에 그대로 잡아먹힙니다.

### 고친 테스트

```java
@BeforeEach
void 파티션_할당을_기다린다() {
    for (MessageListenerContainer container : registry.getListenerContainers()) {
        ContainerTestUtils.waitForAssignment(container, broker.getPartitionsPerTopic());
    }
}
```

**결과**
```
INFO 14311 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s11-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO 14311 --- [ad | producer-1] o.a.k.c.p.KafkaProducer                  : [Producer clientId=producer-1] Sending record to topic orders partition 1
INFO 14311 --- [ntainer#0-0-C-1] c.e.o.step11.Practice$LatchListener      : received ORD-0001

BUILD SUCCESSFUL in 6s
12 tests completed          ← 30회 반복 실행 전부 통과
```

`partitions assigned` 가 `Sending record` 보다 **앞으로 왔습니다.** 이것이 유일한 차이입니다.

> ⚠️ **함정 — `waitForAssignment(container, 1)` 로 대충 넘기지 마십시오**
> 두 번째 인자는 "**이 컨테이너가 할당받아야 할 파티션 총 개수**"입니다. `concurrency=3`, 파티션 3개면 **3** 을 넘겨야 합니다.
> `1` 을 넘기면 첫 스레드가 파티션 하나를 받는 순간 대기가 풀리고, 나머지 두 파티션은 아직 할당 전입니다.
> 그 두 파티션으로 간 메시지는 **놓칩니다.** 그리고 `OrderCreated.of(seq)` 는 키가 세 파티션에 고르게 흩어지므로, **3분의 1 확률로 실패**하는 테스트가 됩니다.
> 이것이 "10번 돌리면 7번 통과"하는 테스트의 정체입니다.

> 💡 **실무 팁 — `@KafkaListener(id = ...)` 를 반드시 붙이십시오.**
> `id` 가 없으면 컨테이너 이름이 `org.springframework.kafka.KafkaListenerEndpointContainer#0` 처럼 자동 생성돼,
> `registry.getListenerContainer("s11-inventory")` 로 특정 컨테이너를 꺼낼 수 없습니다. 테스트에서 pause/resume 하거나 개별 대기를 걸 때 반드시 필요합니다. (Step 10 참고)

---

## 11-6. `awaitility` — `Thread.sleep` 을 없앤다

`CountDownLatch` 는 "리스너가 몇 번 호출됐는가"에는 잘 맞지만, "DB 에 행이 생겼는가", "카운터가 3이 됐는가" 같은 **상태 검증**에는 맞지 않습니다. 그래서 사람들은 `Thread.sleep` 을 씁니다.

```java
kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));
Thread.sleep(1000);                                     // ← 문제의 한 줄
assertThat(inventory.stockOf("SKU-002")).isEqualTo(998);
```

### `Thread.sleep` 이 나쁜 이유는 두 가지입니다

1. **느린 환경에서는 부족합니다.** CI 러너의 CPU 는 개발 노트북의 절반 이하입니다. 로컬 200ms 짜리 처리가 CI 에서 1.4초가 됩니다. → **간헐 실패**
2. **빠른 환경에서는 낭비입니다.** 실제로 80ms 만에 끝났어도 1초를 꽉 채워 잡니다. → **테스트 100개면 100초**

두 문제를 동시에 "고치려고" `sleep(3000)` 으로 늘리면, 실패는 줄지만 스위트가 5분이 됩니다. 그리고 여전히 언젠가 실패합니다.

### `awaitility` 는 조건이 만족되면 즉시 통과합니다

```java
import static org.awaitility.Awaitility.await;

kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));

await().atMost(Duration.ofSeconds(5))
       .pollInterval(Duration.ofMillis(50))
       .untilAsserted(() -> assertThat(inventory.stockOf("SKU-002")).isEqualTo(998));
```

- `atMost(5초)` — **상한**입니다. 5초까지 기다려 주지만 보통은 훨씬 빨리 끝납니다.
- `pollInterval(50ms)` — 50ms 마다 람다를 재실행합니다.
- `untilAsserted` — 람다가 **예외 없이 끝나면** 통과. 실패하면 다시 시도.
- 5초가 지나도 안 되면 `ConditionTimeoutException` 과 함께 **마지막 AssertionError 를 그대로 보여 줍니다.**

**실패했을 때의 출력**
```
org.awaitility.core.ConditionTimeoutException:
Assertion condition defined as a lambda expression in com.example.order.step11.Practice$AwaitilityTest
expected: 998
 but was: 1000
within 5 seconds.
```

`Thread.sleep` 방식이 주는 `expected: 998 but was: 1000` 보다 **"5초를 기다렸는데도 1000 이었다"** 는 정보가 추가됩니다. 원인 파악이 훨씬 빠릅니다.

### 실측 — 스위트 전체 시간

`Practice.java` 의 리스너 검증 테스트 12개를 두 방식으로 각각 돌렸습니다.

```bash
./gradlew test --tests 'com.example.order.step11.Practice$SleepBasedTest'
./gradlew test --tests 'com.example.order.step11.Practice$AwaitilityTest'
```

| | `Thread.sleep(1000)` × 12 | `awaitility` × 12 |
|---|---:|---:|
| 브로커 기동 | 2.1s | 2.1s |
| 테스트 실행 합계 | **39.8s** | **4.7s** |
| **총** | **42.0s** | **6.8s** |
| CI 30회 반복 실패 | **4회** | **0회** |
| 실제 평균 대기 시간/테스트 | 1000ms(고정) | **83ms** |

**42초 → 6.8초. 6.2배 빨라졌고, 간헐 실패는 사라졌습니다.** 테스트가 실제로 필요했던 시간은 평균 83ms 였고, 나머지 917ms 는 전부 낭비였습니다.

> 💡 **실무 팁 — `atMost` 는 넉넉하게, `pollInterval` 은 촘촘하게.**
> `atMost` 를 늘려도 **성공하는 테스트는 전혀 느려지지 않습니다.** 실패할 때만 그만큼 기다립니다.
> 그러니 `atMost(10초)` 로 두고 CI 여유를 확보하는 편이 낫습니다. 반대로 `pollInterval` 은 기본값이 100ms 이므로,
> 빠른 검증에는 50ms 로 줄이면 평균 대기가 절반이 됩니다.

> ⚠️ **함정 — `await()` 안에서 상태를 바꾸지 마십시오**
> `untilAsserted` 의 람다는 **여러 번 실행됩니다.** 그 안에 `consumer.poll()` 이나 `counter.incrementAndGet()` 을 넣으면
> 폴링 횟수만큼 부작용이 발생해 검증 자체가 망가집니다. 람다 안에는 **읽기와 단언만** 두십시오.

---

## 11-7. Testcontainers — 진짜 브로커로 테스트한다

`@EmbeddedKafka` 는 빠르지만 **운영에서 쓰는 그 브로커가 아닙니다.** `spring-kafka-test` 가 의존하는 `kafka_2.13:3.6.1` 이고, 운영은 3.7.0 입니다. 트랜잭션·압축·ACL 처럼 브로커 설정에 민감한 기능은 여기서 갈립니다.

Testcontainers 는 **도커로 실제 Kafka 이미지**를 띄웁니다.

```java
@SpringBootTest
@Testcontainers
class TestcontainersKafkaTest {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withReuse(true);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void 실제_브로커로_주문을_발행한다() { ... }
}
```

세 가지가 핵심입니다.

- **`@Container static`** — `static` 이 붙으면 **클래스당 1회** 기동하고 모든 테스트가 공유합니다. `static` 을 빼면 **테스트 메서드마다** 컨테이너를 새로 띄웁니다.
- **`@DynamicPropertySource`** — 컨테이너 포트는 기동 후에야 정해지므로, `application.yml` 에 정적으로 쓸 수 없습니다. 이 훅이 컨텍스트 생성 **직전**에 값을 주입합니다.
- **`withReuse(true)`** — 테스트가 끝나도 컨테이너를 지우지 않고 다음 실행에서 재사용합니다.

**결과** (첫 실행)
```
INFO 14311 --- [    Test worker] o.t.d.DockerClientProviderStrategy       : Found Docker environment with local Unix socket (unix:///var/run/docker.sock)
INFO 14311 --- [    Test worker] tc.confluentinc/cp-kafka:7.6.1           : Creating container for image: confluentinc/cp-kafka:7.6.1
INFO 14311 --- [    Test worker] tc.confluentinc/cp-kafka:7.6.1           : Container confluentinc/cp-kafka:7.6.1 is starting: 3f1a9c2b7e04
INFO 14311 --- [    Test worker] tc.confluentinc/cp-kafka:7.6.1           : Container confluentinc/cp-kafka:7.6.1 started in PT8.412S
INFO 14311 --- [    Test worker] c.e.o.s.TestcontainersKafkaTest          : Started TestcontainersKafkaTest in 9.933 seconds (process running for 12.180)
```

**8.412초.** 임베디드(2.1초)의 4배입니다.

### `withReuse(true)` 로 재사용하기

재사용을 켜려면 **테스트 코드와 사용자 설정 두 곳**을 모두 손봐야 합니다.

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

**결과** (두 번째 실행부터)
```
INFO 14311 --- [    Test worker] tc.confluentinc/cp-kafka:7.6.1           : Reusing container with ID: 3f1a9c2b7e04 and hash: 8a4d1f0c
INFO 14311 --- [    Test worker] c.e.o.s.TestcontainersKafkaTest          : Started TestcontainersKafkaTest in 1.741 seconds (process running for 2.902)
```

**8.4초 → 0.2초** (컨테이너 기동 부분). 로컬 반복 개발에서는 임베디드보다도 빠릅니다.

> ⚠️ **함정 — `withReuse(true)` 는 컨테이너를 남긴 채 데이터도 남깁니다**
> 재사용된 컨테이너에는 **앞 실행에서 발행한 메시지와 커밋된 오프셋이 그대로 남아 있습니다.**
> `orders` 토픽을 `--from-beginning` 으로 읽는 테스트라면 지난주에 넣은 메시지까지 함께 읽습니다.
> 재사용을 쓸 거라면 **토픽 이름과 그룹 id 를 매번 `UUID` 로 새로 만드십시오**(11-9).
> 그리고 CI 에서는 `testcontainers.reuse.enable` 을 켜지 마십시오. 러너는 매번 새 머신이라 이득이 없고, 재사용 해시 계산 비용만 듭니다.

> 💡 Testcontainers 1.20 부터는 `org.testcontainers.kafka.KafkaContainer`(apache/kafka 이미지, KRaft) 와
> `org.testcontainers.kafka.ConfluentKafkaContainer` 로 클래스가 나뉩니다.
> 이 코스는 BOM 이 **1.19.7** 이므로 `org.testcontainers.containers.KafkaContainer` 를 씁니다.
> 1.20+ 로 올릴 때 `org.testcontainers.containers.KafkaContainer` 는 deprecated 경고가 뜨며, KRaft 를 쓰려면
> `apache/kafka:3.7.0` 이미지와 새 클래스로 바꿔야 합니다.

---

## 11-8. `@EmbeddedKafka` vs Testcontainers

| 축 | `@EmbeddedKafka` | Testcontainers |
|---|---|---|
| **기동 시간** | **2.1초** (컨텍스트당 1회) | 8.4초 (재사용 시 0.2초) |
| **Docker 필요** | 불필요 | **필수**. 없으면 테스트 자체가 안 돎 |
| **브로커 버전** | `spring-kafka-test` 가 정하는 버전(3.6.1). **운영과 다를 수 있음** | 이미지 태그로 **운영과 정확히 일치** 가능 |
| **재현성** | JVM·라이브러리 버전에 좌우 | **이미지 해시로 고정.** 어디서 돌려도 동일 |
| **트랜잭션** | 동작하나 `transaction.state.log.replication.factor` 등을 직접 넣어야 함 | 이미지 기본 설정이 이미 단일 노드용 |
| **압축·보안(SSL/SASL)** | 실질적으로 어려움. ACL·SASL 설정 대부분 미검증 | **실제 설정 그대로** 검증 가능 |
| **KRaft** | 3.1 부터 `EmbeddedKafkaKraftBroker` 지원 (`kraft = true` 가 기본) | 이미지가 KRaft 면 그대로 |
| **병렬 테스트** | 같은 JVM 안 → **포트·스레드 충돌 위험** | 컨테이너가 격리 → 안전 |
| **CI 부하** | JVM 힙 약 +400MB | 컨테이너 1개당 약 +700MB, docker-in-docker 필요 |
| **디버깅** | **브레이크포인트가 브로커 코드까지 걸림** | 컨테이너 로그를 밖에서 봐야 함 |
| **로그 확인** | 테스트 콘솔에 그대로 섞여 나옴 | `KAFKA.getLogs()` 또는 `withLogConsumer` |

### 결론

| 상황 | 선택 |
|---|---|
| 리스너 하나의 동작, 직렬화, 파티셔닝, 에러 핸들러 | **`@EmbeddedKafka`** |
| 프로듀서→컨슈머 왕복, DLT 도착, `@RetryableTopic` | **`@EmbeddedKafka`** |
| 트랜잭션(`read_committed`), 압축, SSL/SASL, ACL | **Testcontainers** |
| 브로커 버전 업그레이드 검증, 릴리스 전 회귀 | **Testcontainers** |
| PR 마다 도는 빠른 스위트 | **`@EmbeddedKafka`** |
| 야간 빌드 / 릴리스 파이프라인 | **Testcontainers** |

**한 줄로: 단위·슬라이스 테스트는 `@EmbeddedKafka`, 통합·릴리스 검증은 Testcontainers.**

두 방식을 태그로 나눠 두면 파이프라인에서 골라 돌릴 수 있습니다.

```java
@Tag("integration")
@Testcontainers
class TestcontainersKafkaTest { ... }
```

```groovy
tasks.named('test') {
    useJUnitPlatform { excludeTags 'integration' }   // PR 빌드: 빠른 것만
}
tasks.register('integrationTest', Test) {
    useJUnitPlatform { includeTags 'integration' }   // 야간 빌드
}
```

---

## 11-9. ⚠️ 함정 — 테스트 간 상태 오염

이 스텝에서 가장 자주 겪는 문제입니다. 두 테스트가 같은 컨슈머 그룹 id 를 쓰면 이렇게 됩니다.

```java
@Test
void 테스트A() {
    kafkaTemplate.send("orders", "ORD-0001", OrderCreated.of(1));
    assertThat(KafkaTestUtils.getRecords(consumerOf("s11-shared"), Duration.ofSeconds(5)).count()).isEqualTo(1);
}

@Test
void 테스트B() {
    kafkaTemplate.send("orders", "ORD-0002", OrderCreated.of(2));
    assertThat(KafkaTestUtils.getRecords(consumerOf("s11-shared"), Duration.ofSeconds(5)).count()).isEqualTo(1);
}
```

**결과** (실행 순서에 따라 달라짐)
```
테스트A() PASSED

테스트B() FAILED
    expected: 1
     but was: 0
```

또는 반대로 `but was: 2` 가 나옵니다. 원인은 두 가지가 겹쳐 있습니다.

1. **커밋된 오프셋이 브로커에 남습니다.** 테스트A 가 `auto.commit=true` 로 오프셋 1 을 커밋했으므로, 테스트B 의 컨슈머는 오프셋 1 부터 읽습니다. 테스트A 가 보낸 메시지가 파티션 1 에, 테스트B 가 보낸 메시지가 파티션 0 에 갔다면 **B 는 0 건**을 받습니다.
2. **JUnit 5 의 메서드 실행 순서는 결정적이지만 자명하지 않습니다.** 클래스 이름 해시 기반이라 리팩터링 한 번에 순서가 바뀝니다.

### 해결 — 격리 3단계

```java
// 1단계: 그룹 id 를 테스트마다 새로 만든다  ← 가장 중요하고 가장 싸다
String group = "s11-probe-" + UUID.randomUUID();

// 2단계: 토픽도 분리한다 (파티셔닝 검증이 없다면 1 파티션으로 충분)
String topic = "orders-" + UUID.randomUUID();
broker.addTopics(new NewTopic(topic, 3, (short) 1));

// 3단계: 그래도 섞이면 컨텍스트를 버린다 (느리므로 최후 수단)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
```

**결과** (그룹 id 를 UUID 로 바꾼 뒤)
```
테스트A() PASSED
테스트B() PASSED

BUILD SUCCESSFUL in 7s
2 tests completed
```

| 격리 수단 | 비용 | 언제 |
|---|---|---|
| 그룹 id `UUID` | **0** | **항상**. 기본으로 깔고 가십시오 |
| 토픽 `UUID` + `broker.addTopics` | 토픽 생성 ~50ms | 테스트가 같은 토픽을 오염시킬 때 |
| `@DirtiesContext(AFTER_METHOD)` | **컨텍스트 재생성 2~4초 × 테스트 수** | 정말 최후 수단 |
| `@DirtiesContext(AFTER_CLASS)` | 클래스당 2~4초 | 클래스가 브로커 설정을 바꿨을 때 |

> ⚠️ **`@DirtiesContext(AFTER_METHOD)` 로 문제를 덮지 마십시오**
> 테스트 20개짜리 클래스에 이걸 붙이면 컨텍스트를 20번 새로 만들고 임베디드 브로커를 20번 다시 띄웁니다. **2.1초 × 20 = 42초** 가 추가됩니다.
> 그리고 근본 원인(그룹 id 공유)은 그대로라, 나중에 다른 테스트 클래스와 섞이면 다시 터집니다.
> **격리는 컨텍스트가 아니라 식별자 수준에서 하는 것이 원칙입니다.**

---

## 11-10. ⚠️ 함정 — 컨텍스트 캐싱과 `@EmbeddedKafka` 의 상호작용

`@EmbeddedKafka` 는 **테스트 클래스마다 브로커를 새로 띄우지 않습니다.** Spring TestContext 프레임워크가 컨텍스트를 캐싱하고, 임베디드 브로커는 그 컨텍스트에 딸린 빈이기 때문입니다.

캐시 키는 `@SpringBootTest` 의 속성, 활성 프로필, `@MockBean` 목록, 그리고 **`@EmbeddedKafka` 의 모든 애트리뷰트**로 구성됩니다.

```java
// 클래스 A
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "orders")
class ProducerTest { ... }

// 클래스 B — 애트리뷰트가 완전히 동일 → 컨텍스트 재사용, 브로커도 그대로
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "orders")
class ListenerTest { ... }

// 클래스 C — partitions 가 다름 → 새 컨텍스트, 새 브로커 (+2.1초)
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = "orders")
class SinglePartitionTest { ... }
```

**결과**
```
INFO 14311 --- [    Test worker] o.s.k.t.EmbeddedKafkaKraftBroker         : Started embedded Kafka broker: 127.0.0.1:52117
INFO 14311 --- [    Test worker] c.e.o.s.ProducerTest                     : Started ProducerTest in 2.108 seconds
INFO 14311 --- [    Test worker] c.e.o.s.ListenerTest                     : Started ListenerTest in 0.041 seconds     ← 캐시 적중. 브로커 로그 없음
INFO 14311 --- [    Test worker] o.s.k.t.EmbeddedKafkaKraftBroker         : Started embedded Kafka broker: 127.0.0.1:52133
INFO 14311 --- [    Test worker] c.e.o.s.SinglePartitionTest              : Started SinglePartitionTest in 2.077 seconds  ← 새 브로커. 포트가 다름
```

`ListenerTest` 는 **0.041초**에 떴습니다. 브로커 시작 로그가 아예 없습니다. 포트도 `ProducerTest` 와 같은 52117 입니다.

### 재사용될 때 앞 클래스의 메시지가 보인다

문제는 여기서 생깁니다. `ProducerTest` 가 `orders` 에 3건을 넣고 끝났다면, `ListenerTest` 가 시작될 때 **그 3건이 토픽에 그대로 있습니다.**

```java
// ListenerTest
@Test
void 주문_하나만_처리된다() {
    kafkaTemplate.send("orders", "ORD-0009", OrderCreated.of(9));
    await().atMost(Duration.ofSeconds(5))
           .untilAsserted(() -> assertThat(listener.count.get()).isEqualTo(1));
}
```

**결과**
```
ConditionTimeoutException: Assertion condition ...
expected: 1
 but was: 4                    ← ProducerTest 가 남긴 3건 + 내가 보낸 1건
within 5 seconds.
```

> ⚠️ **컨텍스트 캐싱은 성능에는 축복이고 격리에는 저주입니다.**
> 이 실패는 `ListenerTest` 를 **단독으로 실행하면 재현되지 않습니다.** `./gradlew test` 로 전체를 돌릴 때만 납니다.
> "IDE 에서는 되는데 `./gradlew test` 에서만 실패한다"의 대표적 원인입니다.
> **해결책은 캐시를 깨는 것이 아니라(그러면 느려집니다), 토픽과 그룹을 테스트마다 새로 만드는 것입니다.**

캐시 상태를 눈으로 보려면 로그 레벨을 올리십시오.

```yaml
logging:
  level:
    org.springframework.test.context.cache: DEBUG
```

**결과**
```
DEBUG 14311 --- [    Test worker] o.s.t.c.c.DefaultContextCache            : Spring test ApplicationContext cache statistics: [DefaultContextCache@1f2 size = 2, maxSize = 32, parentContextCount = 0, hitCount = 1, missCount = 2]
```

`hitCount = 1, missCount = 2` — 컨텍스트를 두 번 만들었고 한 번 재사용했다는 뜻입니다. `missCount` 가 테스트 클래스 수만큼 나온다면 **캐시가 전혀 안 먹고 있다**는 신호이고, 스위트 시간이 클래스 수 × 2.1초 만큼 늘어납니다.

---

## 11-11. `MockProducer` / `MockConsumer` — 브로커 없이

브로커가 아예 필요 없는 경우도 있습니다. **직렬화 결과**, **파티션 계산**, **헤더 부착** 처럼 클라이언트 안에서 끝나는 로직입니다. `kafka-clients` 가 제공하는 `MockProducer` / `MockConsumer` 를 쓰면 **밀리초 단위**로 끝납니다.

```java
@Test
void 주문_키가_직렬화되고_파티션이_계산된다() {
    MockProducer<String, OrderCreated> mock = new MockProducer<>(
            true,                                  // autoComplete: send 즉시 완료 처리
            new StringSerializer(),
            new JsonSerializer<OrderCreated>());

    OrderPublisher publisher = new OrderPublisher(new KafkaTemplate<>(new MockProducerFactory<>(mock)));
    publisher.publish(OrderCreated.of(1));

    List<ProducerRecord<String, OrderCreated>> sent = mock.history();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).key()).isEqualTo("ORD-0001");
    assertThat(sent.get(0).topic()).isEqualTo("orders");
    assertThat(sent.get(0).headers().lastHeader("trace-id")).isNotNull();
}
```

**결과**
```
BUILD SUCCESSFUL in 0s
1 test completed          ← 브로커 기동 없음. 총 실행 12ms
```

`MockConsumer` 는 레코드를 직접 밀어 넣어 처리 로직만 검증할 때 씁니다.

```java
MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
TopicPartition tp = new TopicPartition("orders", 0);

consumer.assign(List.of(tp));
consumer.updateBeginningOffsets(Map.of(tp, 0L));
consumer.addRecord(new ConsumerRecord<>("orders", 0, 0L, "ORD-0001", "{\"orderId\":\"ORD-0001\"}"));

ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
assertThat(records.count()).isEqualTo(1);
```

### 한계 — 무엇을 검증할 수 없는가

| 검증하려는 것 | Mock 으로 가능? |
|---|---|
| 키·값 직렬화 결과 | **가능** |
| 파티션 계산(파티셔너) | **가능** (`MockProducer(cluster, ...)` 로 파티션 수를 주면) |
| 헤더 부착 | **가능** |
| 리스너 메서드의 인자 바인딩 | 불가 (컨테이너가 없음) |
| 오프셋 커밋 시점(`AckMode`) | **불가** |
| 재시도·`DefaultErrorHandler`·DLT | **불가** |
| 리밸런스 동작 | **불가** (`consumer.rebalance()` 로 흉내만 낼 수 있음) |
| 트랜잭션 | **불가** |

> 💡 **판단 기준: "이 로직이 브로커와 대화하는가?"**
> 대화하지 않으면 Mock, 대화하면 `@EmbeddedKafka`. `AckMode`·재시도·DLT 는 전부 **컨테이너와 브로커의 협업**이므로 Mock 으로는 한 줄도 검증되지 않습니다.
> Mock 으로 "커밋됐다"를 검증하는 테스트를 보면 거의 항상 **아무것도 검증하지 않는 테스트**입니다.

---

## 11-12. 에러 처리와 DLT 테스트

Step 07 에서 만든 `DeadLetterPublishingRecoverer` 가 실제로 `orders.DLT` 에 메시지를 넣는지 검증합니다.

```java
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = { "orders", "orders.DLT" },
               brokerProperties = "listeners=PLAINTEXT://localhost:0")
class DltTest {

    @Test
    void 세_번_실패하면_DLT로_간다() {
        kafkaTemplate.send("orders", "ORD-0042", OrderCreated.of(42));

        // 1) 재시도가 3번 일어났는가
        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(listener.attempts.get()).isEqualTo(3));

        // 2) DLT 에 도착했는가
        ConsumerRecord<String, String> dlt = KafkaTestUtils.getSingleRecord(dltConsumer, "orders.DLT");
        assertThat(dlt.key()).isEqualTo("ORD-0042");

        // 3) 원인 예외가 헤더에 실렸는가  ← 여기까지 봐야 합니다
        assertThat(new String(dlt.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN).value()))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(ByteBuffer.wrap(dlt.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong())
                .isEqualTo(0L);
    }
}
```

**결과**
```
WARN 14311 --- [ntainer#0-0-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=100, currentAttempts=3, maxAttempts=2} exhausted for orders-1@0
ERROR 14311 --- [ntainer#0-0-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Publishing to orders.DLT-1 for orders-1@0
INFO 14311 --- [    Test worker] c.e.o.step11.Practice$DltTest             : DLT record: key=ORD-0042, exception=java.lang.IllegalStateException

BUILD SUCCESSFUL in 8s
```

**3번 검증한 것에 주목하십시오.** "DLT 에 뭔가 왔다"만 보는 테스트는 절반짜리입니다. 엉뚱한 예외로 DLT 에 가도 통과하기 때문입니다.

### `@RetryableTopic` 테스트는 느립니다

Step 08 의 `@RetryableTopic` 은 기본 백오프가 **1초 × 3회** 입니다. 테스트 하나에 3초 이상이 걸리고, `orders-retry-0/1/2` 토픽 생성 시간까지 더해집니다.

```java
@RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2.0))
@KafkaListener(topics = "orders", groupId = "s11-retry")
public void onMessage(OrderCreated event) { throw new IllegalStateException("boom"); }
```

**결과**
```
DLT까지_간다() PASSED (7.612s)      ← 1s + 2s + retry 토픽 생성 + DLT 발행
```

### 해결 — 테스트 전용 짧은 백오프 프로필

백오프 값을 **프로퍼티로 외부화**하고, 테스트에서만 짧게 덮어씁니다.

```java
@RetryableTopic(
        attempts = "${app.retry.attempts:3}",
        backoff = @Backoff(delayExpression = "${app.retry.delay:1000}",
                           multiplierExpression = "${app.retry.multiplier:2.0}"))
```

```yaml
# src/test/resources/application.yml
app:
  retry:
    attempts: 3
    delay: 50        # 1000ms → 50ms
    multiplier: 1.0  # 지수 증가 끄기
```

**결과**
```
DLT까지_간다() PASSED (1.284s)      ← 7.612s → 1.284s. 5.9배
```

> ⚠️ **함정 — 백오프를 0 으로 만들지 마십시오**
> `delay: 0` 으로 두면 재시도 토픽 리스너가 **백오프 없이 즉시 재소비**해서, "재시도 간격이 지켜지는가"를 검증하는 테스트가 항상 통과합니다.
> 더 나쁜 것은 `@RetryableTopic` 의 논블로킹 재시도에서 백오프가 0 이면 **파티션 정지 없이 무한 루프에 가까운 속도**로 돌아,
> 임베디드 브로커에 초당 수천 건이 쌓이고 테스트 JVM 이 OOM 으로 죽는 경우입니다.
> **50ms 정도가 적당합니다.** 빠르면서도 "간격이 있다"는 사실은 유지됩니다.

> 💡 재시도 토픽의 동작 원리와 순서 보장 트레이드오프는 [Step 08](../step-08-retryable-topic/) 을 참고하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Kafka 테스트의 전제 붕괴 | 비동기 / 할당 대기 / 오프셋 잔존 / **리스너 예외가 테스트로 전파 안 됨** |
| `@EmbeddedKafka` | JVM 안에 브로커. **2.1초**. 포트는 반드시 `0` |
| 주소 연결 | `spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}`. **빠뜨리면 조용히 9092 로 감** |
| `KafkaTestUtils` | `consumerProps` / `producerProps` / `getSingleRecord` / `getRecords`. **값은 `String` 으로 받는 게 유리** |
| `getSingleRecord` | "정확히 1건" 을 검증하지 **않음**. 개수 검증은 `getRecords().count()` |
| `waitForAssignment` | 빠뜨리면 **`send` 가 `partitions assigned` 보다 빨라 메시지 유실**. CI 간헐 실패 1번 원인 |
| `waitForAssignment` 인자 | 컨테이너가 받아야 할 **파티션 총 개수**. `1` 을 넣으면 3분의 1 확률로 실패 |
| `Thread.sleep` | 느린 CI 엔 부족, 빠른 로컬엔 낭비. **12개 테스트 39.8초** |
| `awaitility` | `atMost` 는 상한일 뿐. **42초 → 6.8초**, 평균 대기 1000ms → 83ms |
| `await` 람다 | **여러 번 실행됨.** 부작용 금지, 읽기와 단언만 |
| Testcontainers | 실제 이미지. 8.4초 (재사용 0.2초). `@Container static` + `@DynamicPropertySource` |
| `withReuse(true)` | 데이터도 남음. **토픽·그룹을 UUID 로**. CI 에서는 끌 것 |
| 선택 기준 | 단위·슬라이스 = **EmbeddedKafka** / 트랜잭션·보안·릴리스 = **Testcontainers** |
| 상태 오염 | 커밋된 오프셋이 브로커에 남음. **그룹 id `UUID` 가 비용 0 의 1순위 해법** |
| `@DirtiesContext` | 최후 수단. `AFTER_METHOD` 는 **2.1초 × 테스트 수** |
| 컨텍스트 캐싱 | `@EmbeddedKafka` 애트리뷰트가 같으면 **브로커까지 재사용**(0.041초). 앞 클래스 메시지가 보임 |
| "IDE 는 되는데 gradle 은 실패" | 컨텍스트 캐싱 + 토픽 공유의 전형적 증상 |
| `MockProducer/Consumer` | 직렬화·파티셔닝·헤더는 검증 가능. **커밋·재시도·DLT·리밸런스는 불가** |
| DLT 테스트 | 도착만 보지 말고 **`DLT_EXCEPTION_FQCN` 까지** 검증 |
| `@RetryableTopic` 테스트 | 백오프를 프로퍼티로 빼고 테스트에서 **50ms**. 7.6초 → 1.3초. **0 은 금지** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 직접 실행해 시간과 로그를 확인**하세요.

1. `@EmbeddedKafka(partitions = 3)` 로 프로듀서 테스트를 작성하고, `ORD-0001`~`ORD-0003` 의 키·파티션·본문을 `KafkaTestUtils.getRecords` 로 검증하기
2. `waitForAssignment` 를 **일부러 빼고** 리스너 테스트를 30회 반복 실행해 실패 횟수를 세고, 추가한 뒤 다시 세어 표로 기록하기
3. `Thread.sleep(1000)` 으로 작성된 테스트 4개를 `awaitility` 로 교체하고, `./gradlew test` 의 총 소요 시간을 before/after 로 측정하기
4. Testcontainers 로 `confluentinc/cp-kafka:7.6.1` 을 띄워 통합 테스트를 작성하고, `@Tag("integration")` 으로 분리해 기본 `test` 태스크에서 제외하기
5. 두 테스트가 같은 그룹 id 를 쓰게 만들어 **간섭을 재현**한 뒤, 그룹 id 를 `UUID` 로 바꿔 해결하고 두 경우의 로그를 비교하기
6. 리스너가 3회 실패하도록 만들고 `orders.DLT` 도착을 검증하되, **키·`DLT_EXCEPTION_FQCN`·`DLT_ORIGINAL_OFFSET` 세 가지를 모두** 단언하기

---

## 다음 단계

테스트가 거짓말하지 않게 만드는 법을 봤습니다. `waitForAssignment` 와 `awaitility` 두 가지만 몸에 배어도 간헐 실패의 대부분이 사라집니다.

하지만 테스트를 아무리 잘 짜도 운영에서 일어나는 일은 다 잡지 못합니다. 컨슈머가 조용히 밀리기 시작하는 것, 리밸런스가 5분마다 반복되는 것, 특정 파티션만 처리가 느려지는 것 — 전부 **테스트로는 안 보이고 지표로만 보입니다.** 다음 스텝에서 Micrometer 로 컨슈머 랙과 처리 지연을 노출하고, Actuator 와 분산 추적을 붙입니다.

→ [Step 12 — 관측성과 운영](../step-12-observability/)

---

## 실습 파일

이 스텝의 세 파일은 지금까지와 달리 **`src/test/java/com/example/order/step11/`** 에 넣습니다. `bootRun` 이 아니라 `./gradlew test` 로 돌립니다. 먼저 `Practice.java` 를 통째로 한 번 실행해 11-5 의 실패/성공 로그와 11-6 의 시간 차이를 눈으로 확인하고, 그다음 `Exercise.java` 의 6문제를 풀어 `Solution.java` 로 대조하십시오. 특히 문제 2 와 3 은 **30회 반복 실행과 시간 측정**이 답의 절반이므로, 코드만 맞히고 넘어가면 이 스텝의 핵심을 놓칩니다.

### Practice.java

본문 11-2 ~ 11-12 의 모든 예제를 절 번호 주석과 함께 nested static 테스트 클래스로 담았습니다.

- 클래스별로 골라 실행합니다. `./gradlew test --tests 'com.example.order.step11.Practice$AwaitilityTest'` 처럼 `$` 로 중첩 클래스를 지정하십시오. zsh 에서는 `$` 를 이스케이프해야 하므로 **작은따옴표로 감싸는 것이 필수**입니다.
- `[11-5] BrokenListenerTest` 는 **일부러 깨진 테스트**입니다. `waitForAssignment` 를 부르는 `@BeforeEach` 가 주석 처리돼 있습니다. `@Disabled` 를 떼고 20~30회 반복해 실패를 재현한 뒤, 주석을 풀고 다시 돌리십시오. 한 번만 돌려서는 재현되지 않을 수 있습니다.
- `[11-6] SleepBasedTest` 와 `[11-6] AwaitilityTest` 는 **검증 내용이 완전히 동일**하고 대기 방식만 다릅니다. 두 클래스를 각각 실행해 `BUILD SUCCESSFUL in Ns` 를 비교하는 것이 이 파일의 하이라이트입니다. 교재의 42.0s vs 6.8s 가 여러분 머신에서 몇 초인지 적어 두십시오.
- `[11-7] TestcontainersKafkaTest` 는 `@Tag("integration")` 이 붙어 있어 **기본 `test` 태스크에서 제외**됩니다. `./gradlew integrationTest` 로 따로 돌리십시오. 도커가 없으면 이 클래스만 실패하고 나머지는 정상 동작합니다.
- `[11-10] CacheHitTest` / `CacheMissTest` 는 `@EmbeddedKafka` 애트리뷰트가 각각 같고 다릅니다. **두 클래스를 한 번에 돌려야** 컨텍스트 캐시 로그(`hitCount` / `missCount`)에 차이가 나타납니다. 따로 돌리면 아무 의미가 없습니다.
- 모든 테스트가 그룹 id 를 `"s11-" + UUID.randomUUID()` 로 만듭니다. `[11-9] PollutedTest` **하나만** 고정 그룹 id 를 쓰는데, 이것이 오염을 재현하기 위한 의도적 예외입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일은 되도록 뼈대를 남겼습니다.

- **문제 1·6** 은 검증 코드를 작성하는 문제, **문제 2·3** 은 **측정 결과를 주석 표에 기록**하는 문제, **문제 4·5** 는 테스트 인프라를 구성하는 문제입니다. 성격이 다르니 순서대로 풀 필요는 없습니다.
- 문제 2 는 반복 실행이 핵심입니다. `for i in $(seq 1 30); do ./gradlew test --tests '...Q2ListenerTest' --rerun-tasks -q || echo "FAIL $i"; done` 를 그대로 쓰면 됩니다. `--rerun-tasks` 가 없으면 Gradle 이 **UP-TO-DATE 로 건너뛰어 30회가 1회가 됩니다.**
- 문제 3 의 `// 측정 기록:` 표에는 before/after 총 시간뿐 아니라 **테스트 1개당 평균 대기 시간**도 적게 돼 있습니다. `System.nanoTime()` 으로 `await` 앞뒤를 재면 나옵니다.
- ⚠️ **문제 4 는 도커가 필요합니다.** 도커가 없으면 `IllegalStateException: Could not find a valid Docker environment` 로 실패합니다. 이때 나머지 문제까지 빨간불이 되지 않도록 `@Tag("integration")` 을 반드시 붙이십시오. 그것이 이 문제의 요구사항 절반입니다.
- 각 문제 끝의 `// 확인:` 주석에 **기대 로그 또는 기대 수치**가 적혀 있습니다. 문제 5 의 확인 줄은 `고정 그룹: 실패 / UUID 그룹: 성공` 입니다. 둘 다 성공했다면 오염 재현에 실패한 것이니 실행 순서를 확인하십시오.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 들어 있습니다. 풀어 본 **뒤에** 여십시오.

- **정답 1** 은 값을 `OrderCreated` 가 아니라 `String` 으로 역직렬화해 받는 것이 포인트입니다. 도메인 타입으로 받으면 `__TypeId__` 헤더나 trusted packages 문제로 실패했을 때 "메시지가 안 왔다"와 구분이 안 됩니다. **프로듀서 테스트는 프로듀서만 검증해야 합니다.**
- **정답 2** 는 실측표입니다. `waitForAssignment` 없이 30회 중 **9회 실패**(30%), 추가 후 **0회**. 30% 라는 숫자가 우연이 아니라 **파티션 3개 중 리스너가 아직 못 받은 파티션으로 갈 확률**이라는 점을 주석에서 계산해 보입니다.
- **정답 3** 은 `Thread.sleep` 총 12.4초 → `awaitility` 총 2.1초입니다. 여기서 중요한 것은 `atMost(5초)` 로 늘려도 **성공 경로의 시간이 전혀 늘지 않는다**는 사실입니다. 이걸 오해해서 `atMost` 를 작게 잡다가 CI 에서 터지는 사례를 함께 적었습니다.
- **정답 4** 는 `@Container static final` 에서 `static` 이 왜 필수인지를 설명합니다. `static` 을 빼면 테스트 메서드 3개짜리 클래스가 컨테이너를 3번 띄워 **8.4초 × 3 = 25초**가 됩니다. `@DynamicPropertySource` 가 `static` 이어야 하는 이유(컨텍스트 생성 전에 호출됨)도 함께 다룹니다.
- **정답 5** 는 오염 재현 로그와 해결 로그를 나란히 둡니다. 핵심은 `@DirtiesContext` 로 덮는 것이 왜 나쁜 답인지입니다. 컨텍스트를 새로 만들어도 **브로커의 `__consumer_offsets` 는 그대로**이므로, 그룹 id 가 같으면 컨텍스트를 아무리 버려도 오염이 재현됩니다. 이 사실이 "격리는 식별자 수준에서" 라는 원칙의 근거입니다.
- **정답 6** 은 `DLT_ORIGINAL_OFFSET` 을 `ByteBuffer.wrap(...).getLong()` 으로 푸는 코드와, `@Header` 로 받으면 Spring 이 변환해 준다는 대비를 보여 줍니다. 그리고 **왜 세 가지를 다 봐야 하는지** — 키만 보면 다른 예외로 DLT 에 가도 통과하고, 예외만 보면 다른 주문이 가도 통과한다 — 를 실패 시나리오로 설명합니다.

```java file="./Solution.java"
```
