# Step 02 — KafkaTemplate 과 프로듀서

> **학습 목표**
> - `KafkaTemplate` 의 `send` 오버로드 5종을 구분하고, 상황별로 어느 것을 쓸지 판단한다
> - Spring Kafka 3.0 에서 바뀐 반환 타입 `CompletableFuture<SendResult<K,V>>` 를 `whenComplete`/`exceptionally` 로 처리한다
> - **브로커를 내린 채 `send()` 를 계속 호출해, 비즈니스 로직이 성공한 줄 아는 조용한 유실을 재현한다**
> - **10,000건 발행을 4가지 방식으로 측정해 `send().get()` 이 처리량을 20배 떨어뜨리는 것을 실측한다**
> - `ProducerFactory` 를 직접 만들어 설정이 다른 `KafkaTemplate` 두 개를 공존시킨다
> - 키 → 파티션 매핑을 `murmur2` 로 직접 계산해 예측하고, 실제 발행 결과와 대조한다
> - `acks` / `retries` / `enable.idempotence` 의 상호 제약을 **기동 실패와 조용한 비활성화** 두 경우로 나눠 확인한다
>
> **선행 스텝**: Step 01 — 환경 구축과 첫 메시지
> **예상 소요**: 90분

---

## 2-0. 실습 준비

Step 01 의 프로젝트를 그대로 씁니다. 코드는 `com.example.order.step02` 패키지, 프로필은 `step02` 입니다.

```bash
docker compose up -d
docker exec -it learn-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic orders
```

**결과**
```
Topic: orders	TopicId: xJ0kQm8CQ3ay6z4rN2ZbSA	PartitionCount: 3	ReplicationFactor: 1	Configs: segment.bytes=1073741824
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
```

**파티션이 3개**인 것을 반드시 확인하세요. 2-7 의 키 → 파티션 매핑 표는 파티션 수가 3일 때의 값입니다. 다르면 표가 전부 어긋납니다.

이 스텝은 **컨슈머를 쓰지 않습니다.** 발행한 것이 정말 갔는지는 콘솔 컨슈머로 확인하니, 터미널을 하나 더 띄워 두세요.

```bash
docker exec -it learn-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders \
  --property print.key=true --property print.partition=true --property print.offset=true
```

---

## 2-1. `send` 오버로드 — 5가지를 구분한다

겉보기엔 비슷하지만 **무엇을 여러분이 정하고 무엇을 Kafka 에 맡기는가**가 다릅니다.

```java
kafkaTemplate.send("orders", event);                            // ① 키 없음
kafkaTemplate.send("orders", event.orderId(), event);           // ② 키 지정 ★기본형
kafkaTemplate.send("orders", 1, event.orderId(), event);        // ③ 파티션까지 지정

// ④ ProducerRecord — 헤더·타임스탬프까지 전부 제어
ProducerRecord<String, OrderCreated> record = new ProducerRecord<>(
        "orders", null, event.createdAt().toEpochMilli(), event.orderId(), event);
record.headers().add("trace-id", "t-0001".getBytes(StandardCharsets.UTF_8));
kafkaTemplate.send(record);

// ⑤ Spring Messaging 의 Message<?>
kafkaTemplate.send(MessageBuilder.withPayload(event)
        .setHeader(KafkaHeaders.TOPIC, "orders")
        .setHeader(KafkaHeaders.KEY, event.orderId()).build());
```

| 오버로드 | 파티션 결정 | 헤더 | 타임스탬프 | 언제 쓰나 |
|---|---|---|---|---|
| `send(topic, data)` | Kafka 의 파티셔너 (키 없음) | 불가 | `send()` 호출 시각 | 순서가 상관없는 로그·지표성 이벤트 |
| `send(topic, key, data)` | `murmur2(key) % N` | 불가 | 동일 | **기본형.** 엔티티 단위 순서를 지킬 때 |
| `send(topic, partition, key, data)` | **여러분이 지정** | 불가 | 동일 | 파티션을 물리적으로 나눠 쓸 때 (2-8 필독) |
| `send(ProducerRecord)` | 레코드에 담긴 값 | **가능** | **지정 가능** | 추적 ID·스키마 버전을 붙일 때 |
| `send(Message<?>)` | `KafkaHeaders.PARTITION` | **가능** | `KafkaHeaders.TIMESTAMP` | Spring Integration / `@SendTo` 와 섞을 때 |

①②③ 은 내부에서 `ProducerRecord` 를 만들어 ④로 위임합니다. **네 가지는 편의 오버로드이고, 진짜는 `ProducerRecord` 하나입니다.**

> 💡 **실무 팁 — `defaultTopic` 을 쓰지 마세요.**
> `sendDefault(key, data)` 로 토픽을 생략하면 편해 보이지만, **어느 토픽으로 가는지 grep 으로 찾을 수 없게** 됩니다.
> 토픽 이름은 `@ConfigurationProperties` 로 주입받아 **호출부에 항상 명시**하는 편이 낫습니다.

---

## 2-2. 반환값은 `CompletableFuture<SendResult<K,V>>` 다

**Spring Kafka 3.0 에서 반환 타입이 바뀌었습니다.**

| 버전 | 반환 타입 | 콜백 |
|---|---|---|
| Spring Kafka 2.x | `ListenableFuture<SendResult<K,V>>` | `addCallback(success, failure)` |
| **Spring Kafka 3.0+** | **`CompletableFuture<SendResult<K,V>>`** | `whenComplete`, `thenAccept`, `exceptionally` |

2.x 코드를 3.x 로 올리면 `addCallback` 이 없어 **컴파일 에러**가 납니다. 이건 그나마 다행인 경우입니다 — 컴파일러가 잡아 주니까요.

```java
kafkaTemplate.send("orders", event.orderId(), event).whenComplete((result, ex) -> {
    if (ex != null) { log.error("발행 실패 key={}", event.orderId(), ex); return; }
    RecordMetadata md = result.getRecordMetadata();
    log.info("발행 성공 key={} → {}-{}@{} ts={}",
            event.orderId(), md.topic(), md.partition(), md.offset(), md.timestamp());
});
```

**결과**
```
INFO 14071 --- [ad | producer-1] c.e.order.step02.Step02Publisher         : 발행 성공 key=ORD-0001 → orders-2@0 ts=1735689660000
INFO 14071 --- [ad | producer-1] c.e.order.step02.Step02Publisher         : 발행 성공 key=ORD-0002 → orders-2@1 ts=1735689720000
INFO 14071 --- [ad | producer-1] c.e.order.step02.Step02Publisher         : 발행 성공 key=ORD-0003 → orders-0@0 ts=1735689780000
```

여기서 꼭 짚어야 할 두 가지가 있습니다.

**① 스레드가 `[main]` 이 아니라 `[ad | producer-1]` 입니다.** 콜백은 **Kafka 프로듀서의 I/O 스레드**(`kafka-producer-network-thread | producer-1`, 로그에는 뒤쪽만 남습니다)에서 실행됩니다. **콜백 안에서 무거운 일을 하면 안 됩니다.** 그 스레드가 막히면 **모든 파티션의 전송이 함께 막힙니다.** DB 쓰기나 외부 HTTP 호출이 필요하면 별도 executor 로 넘기세요.

**② 세 메서드의 역할이 다릅니다.** `thenAccept` 는 성공 시에만, `exceptionally` 는 실패 시에만 실행됩니다. **둘 다 필요하면 `whenComplete` 가 유일한 답**입니다.

`SendResult` 에서는 `getProducerRecord()`(내가 보낸 것)와 `getRecordMetadata()`(브로커가 알려준 것)를 꺼냅니다. 후자에서 `topic()` `partition()` `offset()` `timestamp()` `serializedValueSize()` 를 얻습니다.

> 💡 **`partition` 과 `offset` 은 브로커만 알 수 있는 값입니다.** `send()` 호출 직후에는 알 수 없고 **응답이 와야** 채워집니다.
> "내가 보낸 메시지의 오프셋을 로그에 남기고 싶다"는 요구에 콜백은 **유일한** 방법입니다.

---

## 2-3. ⚠️ 최대 함정 — 결과를 안 보면 유실을 모른다

이 스텝, 아니 이 코스 전체에서 가장 중요한 절입니다.

```java
// 실무에서 가장 흔히 보는 코드
public void publish(OrderCreated event) {
    kafkaTemplate.send("orders", event.orderId(), event);   // 반환값을 버린다
    log.info("주문 이벤트 발행 완료: {}", event.orderId());
    orderRepository.markPublished(event.orderId());          // 발행됐다고 DB 에 기록
}
```

이 코드는 **브로커가 죽어 있어도 예외를 던지지 않습니다.** `send()` 는 레코드를 프로듀서의 **메모리 버퍼(`buffer.memory`, 기본 32MB)에 넣고 즉시 리턴**할 뿐이기 때문입니다. 실제 전송은 I/O 스레드가 나중에 합니다.

### 재현 — 브로커를 내리고 계속 보낸다

기본값 `delivery.timeout.ms=120000` 은 2분을 기다려야 해서 실습이 지루합니다. 짧게 줄입니다.

```yaml
spring.kafka.producer.properties:
  delivery.timeout.ms: 10000      # 실습용. 운영 기본값은 120000
  request.timeout.ms: 3000
  max.block.ms: 5000
```

앱을 띄운 뒤 `docker compose stop kafka` 로 브로커를 내리고, 위 `publish()` 를 1초 간격으로 5번 호출합니다.

**결과 — 처음 5초**
```
INFO 14071 --- [           main] c.e.order.step02.Step02Publisher         : 주문 이벤트 발행 완료: ORD-0001
INFO 14071 --- [           main] c.e.order.step02.Step02Publisher         : 주문 이벤트 발행 완료: ORD-0002
INFO 14071 --- [           main] c.e.order.step02.Step02Publisher         : 주문 이벤트 발행 완료: ORD-0003
WARN 14071 --- [ad | producer-1] o.a.k.clients.NetworkClient              : [Producer clientId=producer-1] Connection to node 1 (127.0.0.1/127.0.0.1:9092) could not be established. Node may not be available.
INFO 14071 --- [           main] c.e.order.step02.Step02Publisher         : 주문 이벤트 발행 완료: ORD-0004
INFO 14071 --- [           main] c.e.order.step02.Step02Publisher         : 주문 이벤트 발행 완료: ORD-0005
```

**"발행 완료" 가 5번 다 찍혔습니다.** `markPublished` 도 5번 다 커밋됐습니다. 그런데 브로커는 꺼져 있고, **한 건도 가지 않았습니다.** `NetworkClient` 의 WARN 하나가 있지만, 이건 로그 한가운데 흘러가는 인프라 경고입니다. 비즈니스 로그는 완벽하게 성공을 말하고 있습니다.

**결과 — `delivery.timeout.ms` 인 10초가 지난 뒤**
```
ERROR 14071 --- [ad | producer-1] o.s.k.support.LoggingProducerListener    : Exception thrown when sending a message with key='ORD-0001' and payload='OrderCreated[orderId=ORD-0001, customerId=1001, sku=SKU-002, quan...' to topic orders:

org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s) for orders-2:10023 ms has passed since batch creation
```

10초가 지나서야 진실이 드러납니다. 그런데 이건 **`ERROR` 지 `Exception` 이 아닙니다.** 아무도 `catch` 하지 않습니다.

> ⚠️ **함정 — spring-kafka 는 ERROR 로그를 내지만, 비즈니스 로직은 성공한 줄 안다**
> `KafkaTemplate` 은 기본으로 `LoggingProducerListener` 를 달아 두므로 **전송 실패가 로그에 아예 안 남지는 않습니다.** 순수 `KafkaProducer` 를 콜백 없이 쓸 때보다 나은 점입니다.
> **문제는 그게 전부라는 것입니다.** 그 ERROR 는
> - 호출 스택과 **전혀 다른 스레드**에서, **수초~수분 뒤에** 찍히고
> - 예외로 전파되지 않으므로 **트랜잭션 롤백도, 재시도도, 알림도 유발하지 않으며**
> - `payload` 가 잘려 있어 **어떤 주문이었는지 복구할 단서가 부족**합니다.
>
> **증상**: "로그는 깨끗한데 컨슈머가 아무것도 못 받았다", "DB 에는 발행됨으로 찍혀 있는데 토픽에는 없다".
> **해결**: `send()` 의 반환 future 를 **반드시** 소비하세요. 최소한 `whenComplete` 로 실패를 잡아 재시도 큐나 Outbox 로 보내야 합니다.

### 같은 함정의 다른 얼굴

브로커 다운만이 원인이 아닙니다. **콜백 없이는 전부 똑같이 조용합니다.**

| 원인 | 호출부에서 보이는 것 | future 에 담기는 예외 |
|---|---|---|
| 브로커 다운 | 아무 일도 없음 | `TimeoutException: Expiring 1 record(s)` |
| **직렬화 실패** | **여기만 다름 — 즉시 던짐** | (호출 스레드에서 `SerializationException`) |
| 토픽 쓰기 권한 없음 | 아무 일도 없음 | `TopicAuthorizationException` |
| `max.request.size` 초과 | 아무 일도 없음 | `RecordTooLargeException` |
| 존재하지 않는 토픽 (자동 생성 off) | 아무 일도 없음 | `TimeoutException: Topic xxx not present in metadata after 60000 ms` |

**직렬화 실패만 호출 스레드에서 즉시 던집니다.** 직렬화는 버퍼에 넣기 *전에* 호출 스레드가 수행하기 때문입니다. 그래서 "직렬화 오류는 잡히는데 왜 다른 건 안 잡히지?" 하는 혼란이 생깁니다.

```java
CompletableFuture<SendResult<String, OrderCreated>> f =
        kafkaTemplate.send("no-such-topic", "K-1", OrderCreated.of(1));
log.info("send() 리턴 직후 isDone={}", f.isDone());   // ← false
```

**결과**
```
INFO 14071 --- [           main] c.e.order.step02.Step02Publisher         : send() 리턴 직후 isDone=false
WARN 14071 --- [ad | producer-1] o.a.k.clients.NetworkClient              : [Producer clientId=producer-1] Error while fetching metadata with correlation id 4 : {no-such-topic=UNKNOWN_TOPIC_OR_PARTITION}
ERROR 14071 --- [ad | producer-1] o.s.k.support.LoggingProducerListener    : Exception thrown when sending a message with key='K-1' and payload='OrderCreated[orderId=ORD-0001, custo...' to topic no-such-topic:

org.apache.kafka.common.errors.TimeoutException: Topic no-such-topic not present in metadata after 60000 ms.
```

`isDone=false` 가 핵심입니다. **`send()` 는 아직 아무것도 결정되지 않은 상태로 리턴합니다.** 그리고 그 상태가 60초 동안 이어집니다.

> 💡 **최소한의 안전장치 한 줄**
> 전 코드를 고칠 여유가 없다면, 커스텀 `ProducerListener` 를 등록해 실패를 한곳으로 모으는 것부터 하세요.
> `kafkaTemplate.setProducerListener(...)` 로 갈아 끼우면 호출부를 하나도 안 고치고 실패 카운터(Micrometer)와 알림을 붙일 수 있습니다.
> **다만 "어떤 주문이 실패했는지 되살리는" 일은 여전히 호출부의 몫입니다.**

---

## 2-4. ⚠️ 두 번째 함정 — `send().get()` 의 성능 붕괴

2-3 을 읽고 나면 자연스럽게 이 결론에 도달합니다. **"그럼 매번 `.get()` 으로 기다리면 되겠네."**
확실하긴 합니다. 그리고 **20배 느립니다.**

### 왜 느린가 — 배치가 통째로 무력화된다

Kafka 프로듀서는 레코드를 파티션별 배치 버퍼에 모읍니다. `linger.ms=5` 면 "5ms 모았다가 한 번에", `batch.size=16384` 면 "16KB 가 차면 즉시" 보냅니다. 한 번의 왕복에 수백 건이 함께 갑니다.

```
[ 정상 ]  send×N (논블로킹, 버퍼에 쌓임) ──▶ linger 경과 or batch 도달
                                      ┌──────────────────────┐
                                      │ 한 번의 요청에 300건 │──▶ 브로커
                                      └──────────────────────┘   왕복 1회 ≈ 2ms

[ get() ] send ─▶ 버퍼에 1건 ─▶ get() 블로킹 ─▶ linger 5ms 대기 ─▶ 전송 ─▶ 응답 대기 ─┐
          send ─▶ 버퍼에 1건 ─▶ ... 처음부터 다시 ◀───────────────────────────────────┘
                                      왕복 1회 = 1건. 게다가 건당 linger.ms 를 그냥 버린다
```

**`.get()` 은 그 레코드 하나의 응답을 기다립니다.** 기다리는 동안 호출 스레드가 다음 `send()` 를 못 하므로 **버퍼에 두 번째 레코드가 들어올 수 없습니다.** 배치 크기가 영원히 1이 됩니다. 게다가 `linger.ms=5` 는 이제 이득이 아니라 **건당 5ms 의 순수 페널티**입니다.

### 실측 — 10,000건 발행

```java
int N = 10_000;

// (a) fire-and-forget
long t0 = System.nanoTime();
for (int i = 1; i <= N; i++) kafkaTemplate.send("orders", "ORD-%04d".formatted(i), OrderCreated.of(i));
kafkaTemplate.flush();                          // ★ 측정 구간 안에 있어야 공정하다
long elapsedA = (System.nanoTime() - t0) / 1_000_000;

// (c) 매 건 get()
long t1 = System.nanoTime();
for (int i = 1; i <= N; i++) kafkaTemplate.send("orders", "ORD-%04d".formatted(i), OrderCreated.of(i)).get();
long elapsedC = (System.nanoTime() - t1) / 1_000_000;
```

**결과**
```
INFO 14071 --- [           main] c.e.order.step02.ThroughputBench         : (a) fire-and-forget + flush : 10000건 /   543 ms = 18,416 msg/s
INFO 14071 --- [           main] c.e.order.step02.ThroughputBench         : (b) 콜백(whenComplete)      : 10000건 /   552 ms = 18,116 msg/s
INFO 14071 --- [           main] c.e.order.step02.ThroughputBench         : (c) 매 건 send().get()      : 10000건 / 10870 ms =    920 msg/s
INFO 14071 --- [           main] c.e.order.step02.ThroughputBench         : (d) 배치 후 allOf().join()  : 10000건 /   559 ms = 17,889 msg/s
```

**18,416 msg/s → 920 msg/s. 20배 느려졌습니다.** 건당 평균 1.087ms 이며, `linger.ms` 를 0 으로 내려도 660 msg/s 대에서 크게 나아지지 않습니다. 병목은 linger 가 아니라 **왕복(RTT)을 건마다 지불하는 구조 그 자체**입니다.

측정할 때 **워밍업 1,000건을 먼저 보내세요.** 첫 `send()` 는 토픽 메타데이터 조회로 수백 ms 를 쓰고 JIT 도 아직 인터프리터 모드라, 워밍업 없이 (a)→(c) 순으로 재면 **(a) 가 (c) 보다 느리게 나오는** 황당한 결과가 나옵니다.

### 필수 비교표 — 발행 방식 4가지

| 방식 | 코드 | 처리량 | 유실 감지 | 순서 보장 | 언제 |
|---|---|---:|---|---|---|
| **fire-and-forget** | `send(...)` | **18,400 msg/s** | ❌ **불가**(로그만) | ✅ 파티션 내 보장 | 유실돼도 되는 지표·트레이스 |
| **콜백** | `send(...).whenComplete(...)` | 18,100 msg/s | ✅ 건별 가능 | ✅ 파티션 내 보장 | **대부분의 경우 정답** |
| **매 건 get()** | `send(...).get()` | **920 msg/s** | ✅ 즉시, 동기 | ✅ 보장 | 초당 수백 건 이하 + 호출부가 즉시 실패를 알아야 할 때 |
| **배치 후 flush/join** | `send()` ×N → `allOf().join()` | 17,900 msg/s | ✅ 배치 단위 | ✅ 파티션 내 보장 | 대량 마이그레이션·일괄 발행 |

**콜백이 fire-and-forget 과 거의 같다는 점이 중요합니다.** 콜백은 사실상 공짜이므로 "안전"과 "속도" 중 하나를 고를 필요가 없습니다.

> ⚠️ **함정 — "순서 보장"이 `.get()` 의 이유가 될 수는 없다**
> `.get()` 을 쓰는 두 번째 이유로 "순서 때문에"를 드는 경우가 많은데 **틀렸습니다.**
> 같은 키의 메시지는 같은 파티션으로 가고, 프로듀서는 그 파티션의 배치를 **보낸 순서대로** 전송합니다. `.get()` 없이도 순서는 지켜집니다.
> 순서를 깨뜨리는 건 `.get()` 의 부재가 아니라 **`retries>0` + `max.in.flight>1` + 멱등성 off** 조합입니다 (2-9).

### 대안 4가지

```java
// ① 콜백 — 기본형. 처리량 손실 거의 없음
kafkaTemplate.send("orders", key, event)
        .whenComplete((r, ex) -> { if (ex != null) failureSink.record(key, event, ex); });

// ② flush() — 루프가 끝난 뒤 한 번. 버퍼를 비우고 모든 응답을 기다린다
for (...) kafkaTemplate.send(...);
kafkaTemplate.flush();

// ③ 마지막 future 만 join — ⚠️ 모든 레코드가 "같은 파티션"일 때만 성립한다
CompletableFuture<SendResult<String, OrderCreated>> last = null;
for (...) last = kafkaTemplate.send("orders", sameKey, event);
last.join();

// ④ allOf — 전부 모아서 한 번에 대기. 개별 실패도 전부 볼 수 있다 ★키가 여러 개면 이것
List<CompletableFuture<SendResult<String, OrderCreated>>> futures = new ArrayList<>();
for (int i = 1; i <= N; i++) futures.add(kafkaTemplate.send("orders", key(i), OrderCreated.of(i)));
CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
long failed = futures.stream().filter(CompletableFuture::isCompletedExceptionally).count();
log.info("발행 {}건 중 실패 {}건", N, failed);   // → 발행 10000건 중 실패 0건
```

③번은 **파티션이 다르면 성립하지 않습니다.** 각 파티션의 전송은 독립적이라, 마지막 레코드가 성공해도 다른 파티션의 앞선 레코드가 실패해 있을 수 있습니다.

> ⚠️ **`flush()` 를 요청마다 호출하지 마세요.**
> `flush()` 는 **그 프로듀서 인스턴스 전체**의 버퍼를 비웁니다. `KafkaTemplate` 은 프로듀서를 공유하므로
> HTTP 요청 하나가 `flush()` 를 부르면 **다른 요청들이 쌓아 둔 배치까지 강제로 밀어냅니다.**
> 동시 요청이 많은 서버에서 이러면 `.get()` 과 똑같은 결과가 되고, 원인 찾기는 훨씬 어렵습니다.
> `flush()` 는 **배치 작업의 끝** 또는 **종료 직전**에만 쓰세요.

---

## 2-5. `ProducerFactory` — 설정이 다른 KafkaTemplate 두 개

`KafkaTemplate` 은 `ProducerFactory` 에서 프로듀서를 얻습니다. `DefaultKafkaProducerFactory` 는 **싱글턴 프로듀서**를 만들어 모든 호출자에게 같은 인스턴스를 돌려줍니다.

```java
Producer<String, OrderCreated> p1 = pf.createProducer();
Producer<String, OrderCreated> p2 = pf.createProducer();
log.info("같은 인스턴스인가? {}", p1 == p2);   // → true
```

`KafkaProducer` 는 **스레드 안전**하며, 여러 스레드가 공유하는 것이 **권장되는 사용법**입니다. 배치와 커넥션을 공유해야 효율이 나기 때문입니다.

> ⚠️ **함정 — 요청마다 `KafkaProducer` 를 새로 만들면 안 된다**
> 프로듀서 하나는 **I/O 스레드 1개 + 메타데이터 캐시 + 32MB 버퍼**를 들고 있습니다. 게다가 배치가 전혀 공유되지 않아 2-4 의 `.get()` 과 같은 성능이 나옵니다.
> 증상은 성능 저하와 **파일 디스크립터 고갈**입니다. `close()` 를 빠뜨리면 그대로 누수입니다. **`KafkaTemplate` 을 주입받아 쓰는 것이 정답입니다.**

"주문 이벤트는 절대 잃으면 안 되고, 클릭 로그는 좀 잃어도 되니 빠르게" 같은 요구는 흔합니다. 설정이 다르므로 **프로듀서를 분리**합니다.

```java
@Configuration
public class DualProducerConfig {

    @Bean   // ① 신뢰성 우선 — 주문 이벤트
    public KafkaTemplate<String, OrderCreated> reliableKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        Map<String, Object> props = base(bootstrap);          // bootstrap + 직렬화기
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "reliable");   // ★ 반드시 다르게
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean   // ② 처리량 우선 — 클릭 로그
    public KafkaTemplate<String, OrderCreated> fastKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        Map<String, Object> props = base(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);   // acks=1 이면 명시적 false
        props.put(ProducerConfig.LINGER_MS_CONFIG, 50);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "fast");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
```

**결과 — 기동 로그**
```
INFO 14071 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka version: 3.6.1
INFO 14071 --- [           main] o.a.k.c.p.i.TransactionManager           : [Producer clientId=reliable] Instantiated an idempotent producer.
INFO 14071 --- [           main] o.a.k.c.p.i.ProducerIdAndEpoch           : [Producer clientId=reliable] ProducerId set to 0 with epoch 0
```

`fast` 쪽에는 `Instantiated an idempotent producer.` 가 **없습니다.** 이 한 줄의 유무가 멱등성 활성화 여부를 알려 주는 가장 확실한 신호입니다. `clientId` 를 안 주면 둘 다 `producer-1`, `producer-2` 로 자동 부여되어 **로그와 JMX 지표에서 어느 쪽인지 구분할 수 없습니다.**

> ⚠️ **함정 — 타입이 같은 `KafkaTemplate` 빈이 둘이면 자동 주입이 실패한다**
> 자동 설정이 만든 것까지 셋이 되어, 이름 없는 주입은 `NoUniqueBeanDefinitionException: expected single matching bean but found 3` 으로 **기동 실패**합니다.
> 이건 다행히 시끄러운 실패입니다. `@Qualifier("reliableKafkaTemplate")` 로 명시하세요.
> **`@Primary` 는 권장하지 않습니다.** `fast` 쪽에 붙어 있으면 아무 생각 없이 주입받은 코드가 전부 `acks=1` 로, 멱등성 없이 발행됩니다. 컴파일도 기동도 테스트도 통과하며, 브로커가 흔들릴 때까지 아무도 모릅니다. **그게 진짜 함정입니다.**

---

## 2-6. `ProducerRecord` 직접 만들기 — 헤더와 타임스탬프

편의 오버로드로는 헤더를 못 붙입니다. 추적 ID·스키마 버전·이벤트 타입 같은 **메타데이터는 헤더가 제자리**입니다. 페이로드에 섞으면 컨슈머가 역직렬화를 해야만 읽을 수 있기 때문입니다.

```java
OrderCreated event = OrderCreated.of(7);

ProducerRecord<String, OrderCreated> record = new ProducerRecord<>(
        "orders",                                   // topic
        null,                                       // partition — null 이면 파티셔너에 맡김
        event.createdAt().toEpochMilli(),           // timestamp — 이벤트 발생 시각
        event.orderId(),                            // key
        event);                                     // value
record.headers()
      .add("trace-id",       "t-9f2c1a".getBytes(StandardCharsets.UTF_8))
      .add("event-type",     "OrderCreated".getBytes(StandardCharsets.UTF_8))
      .add("schema-version", "2".getBytes(StandardCharsets.UTF_8));

kafkaTemplate.send(record).whenComplete((r, ex) -> {
    RecordMetadata md = r.getRecordMetadata();
    log.info("→ {}-{}@{} ts={}", md.topic(), md.partition(), md.offset(), md.timestamp());
});
```

**결과** — `→ orders-0@12 ts=1735690080000`. 콘솔 컨슈머로 확인합니다.

```bash
docker exec -it learn-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders --partition 0 --offset 12 --max-messages 1 \
  --property print.key=true --property print.headers=true --property print.timestamp=true
```

**결과**
```
CreateTime:1735690080000	trace-id:t-9f2c1a,event-type:OrderCreated,schema-version:2,__TypeId__:com.example.order.domain.OrderCreated	ORD-0007	{"orderId":"ORD-0007","customerId":1007,"sku":"SKU-002","quantity":3,"amount":10000,"createdAt":"2025-01-01T00:07:00Z"}
```

`__TypeId__` 는 우리가 붙인 게 아니라 `JsonSerializer` 가 자동으로 붙인 헤더입니다. **Step 04 의 주인공**이니 기억해 두세요.
`CreateTime:1735690080000` — **우리가 지정한 이벤트 발생 시각이 그대로 살아 있습니다.** 지정하지 않으면 `send()` 를 호출한 시각이 들어갑니다.

> ⚠️ **함정 — 토픽이 `LogAppendTime` 이면 여러분이 지정한 타임스탬프는 조용히 버려진다**
> 토픽 설정 `message.timestamp.type` 이 `LogAppendTime` 이면 **브로커가 도착 시각으로 덮어씁니다.** 에러도 경고도 없습니다.
> 재처리·마이그레이션에서 과거 이벤트를 원래 시각으로 다시 넣으려 할 때 조용히 무력화되어, `--from-timestamp` 오프셋 조회와 시계열 집계가 전부 틀어집니다.
> 확인: `kafka-configs.sh --describe --entity-type topics --entity-name orders`. 기본값은 `CreateTime` 입니다.

> 💡 헤더 값은 `byte[]` 입니다. **인코딩을 명시**하세요(`StandardCharsets.UTF_8`). 플랫폼 기본 인코딩에 맡기면 로컬과 서버에서 다르게 직렬화되어 한글 헤더가 깨집니다.

---

## 2-7. 키와 파티셔너 — 어디로 가는지 계산할 수 있다

### 키가 있으면: `murmur2(key) % partitions`

Kafka 의 기본 파티셔너는 키가 있으면 **결정적**으로 파티션을 고릅니다. 순수 함수이므로 **브로커에 물어보지 않고 계산할 수 있습니다.**

```java
int hash = Utils.murmur2(key.getBytes(StandardCharsets.UTF_8));
int partition = Utils.toPositive(hash) % numPartitions;    // toPositive = hash & 0x7fffffff
```

> ⚠️ **`Math.abs(hash) % n` 으로 쓰면 안 됩니다.** `Math.abs(Integer.MIN_VALUE)` 는 오버플로로 **자기 자신(음수)** 이 됩니다.
> 그러면 파티션 번호가 음수가 되어 `ArrayIndexOutOfBoundsException` 이 납니다. 40억분의 1이지만 하루 수억 건이면 언젠가 터집니다.
> Kafka 가 `Math.abs` 대신 `toPositive`(비트 마스크)를 쓰는 이유가 정확히 이것입니다.

**결과 — `orders` 는 파티션 3개**

| 키 | `murmur2` | `toPositive` | **% 3 (현재)** | % 4 | % 6 |
|---|---:|---:|:---:|:---:|:---:|
| `ORD-0001` | -1289372403 | 858111245 | **2** | 1 | 5 |
| `ORD-0002` | -1559091975 | 588391673 | **2** | 1 | 5 |
| `ORD-0003` | 1961410242 | 1961410242 | **0** | 2 | 0 |
| `ORD-0004` | 260207539 | 260207539 | **1** | 3 | 1 |
| `ORD-0005` | -1928704172 | 218779476 | **0** | 0 | 0 |
| `ORD-0006` | -601354663 | 1546128985 | **1** | 1 | 1 |
| `ORD-0007` | 1240079631 | 1240079631 | **0** | 3 | 3 |
| `ORD-0008` | 252067230 | 252067230 | **0** | 2 | 0 |
| `ORD-0009` | 1338609269 | 1338609269 | **2** | 1 | 5 |

실제로 발행해 콜백이 알려준 파티션과 대조합니다.

**결과**
```
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0001 예측=2 실제=2 offset=0
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0002 예측=2 실제=2 offset=1
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0003 예측=0 실제=0 offset=0
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0004 예측=1 실제=1 offset=0
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0005 예측=0 실제=0 offset=1
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0006 예측=1 실제=1 offset=1
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0007 예측=0 실제=0 offset=2
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0008 예측=0 실제=0 offset=3
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : ORD-0009 예측=2 실제=2 offset=2
```

9건 전부 일치합니다. **파티션 배정은 마법이 아니라 계산입니다.**
분포도 눈여겨보세요. 9건 중 파티션 0 에 4건, 1 에 2건, 2 에 3건입니다. **키가 균등해도 파티션은 균등하지 않습니다.** "핫 파티션"의 씨앗이 여기 있습니다.

### ⚠️ 파티션 수를 바꾸면 매핑이 통째로 바뀐다

위 표의 `% 4` 열을 보세요. 파티션을 3 → 4 로 늘리면 `ORD-0001` 은 2→1, `ORD-0003` 은 0→2, `ORD-0004` 는 1→3, `ORD-0007` 은 0→3 으로 **9개 키 중 8개가 바뀝니다.**

> ⚠️ **함정 — 파티션 증설은 순서 보장을 소급해서 깨뜨린다**
> `ORD-0001` 의 과거 이벤트는 `orders-2` 에 쌓여 있는데, 증설 직후부터 같은 주문의 새 이벤트가 `orders-1` 로 갑니다.
> **두 파티션은 독립적으로 소비되므로, 나중에 발행된 `OrderCancelled` 가 먼저 발행된 `OrderCreated` 보다 먼저 처리될 수 있습니다.**
> 에러는 없습니다. 재고가 음수가 되거나 취소된 주문이 배송되는 것으로 드러납니다.
> **해결**: 파티션 수는 처음에 넉넉히(예상 최대 컨슈머 수의 2배) 잡고 바꾸지 않습니다. 꼭 늘려야 한다면 신규 토픽을 만들어 컨슈머를 전환하는 편이 안전합니다.
> (파티션 재할당의 브로커 쪽 동작은 [Kafka 코스 Step 03](../../kafka/step-03-topics-partitions/) 참고)

### 키가 `null` 이면: sticky partitioner

| 버전 | 동작 |
|---|---|
| ~2.3 | **라운드로빈.** 레코드마다 다음 파티션. 배치가 잘게 쪼개져 비효율 |
| 2.4 ~ 3.2 | **Sticky.** 한 파티션에 배치가 찰 때까지 몰아넣고, 배치가 나가면 다음 파티션으로 |
| **3.3+** | `partitioner.class` 기본값이 **`null`** 이 되고 **내장 uniform sticky 로직**을 사용. `DefaultPartitioner` 는 deprecated |

우리 환경(kafka-clients 3.6.1)은 세 번째입니다. `partitioner.class` 를 **아예 지정하지 않는 것이 정상**이며, 예전 글을 보고 `DefaultPartitioner` 를 명시하면 deprecation 경고와 함께 구버전 동작으로 되돌아갑니다.

```java
for (int i = 1; i <= 6; i++) kafkaTemplate.send("orders", OrderCreated.of(i));  // 키 없음
```

**결과**
```
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : key=null → orders-1@0
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : key=null → orders-1@1
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : key=null → orders-1@2
INFO 14071 --- [ad | producer-1] c.e.order.step02.PartitionDemo           : key=null → orders-1@3   (이하 orders-1@4, orders-1@5)
```

**6건이 전부 `orders-1` 에 몰렸습니다.** 배치가 아직 안 찼기 때문입니다. 라운드로빈을 기대했다면 당황스럽겠지만 이게 정상 동작이며, 수만 건을 보내면 장기적으로 고르게 분산됩니다.

> ⚠️ **함정 — "키를 안 주면 알아서 골고루 퍼진다"는 착각**
> 소량 발행에서는 **한 파티션에 몰립니다.** 통합 테스트에서 "3개 파티션에 골고루 갔는지" 검증하는 assert 를 쓰면
> 로컬에서는 통과하다가 CI 에서 배치 타이밍이 달라지며 간헐 실패합니다 (Step 11).
> 그리고 키가 없으면 **어떤 순서 보장도 없습니다.** 같은 주문의 이벤트 두 개가 다른 파티션으로 갈 수 있습니다.

---

## 2-8. 명시적 파티션 지정과 그 위험

```java
kafkaTemplate.send("orders", 1, event.orderId(), event);   // 무조건 orders-1
```

파티션을 직접 주면 **파티셔너를 완전히 건너뜁니다.** 키는 여전히 레코드에 실려 가지만 파티션 결정에는 아무 영향이 없습니다.
정당한 용도는 **재처리**(DLT 메시지를 원래 파티션으로), **테스트**, **파티션 전용 워크로드** 정도이고, 그 외에는 거의 항상 잘못된 선택입니다.

> ⚠️ **함정 — 하드코딩한 파티션 번호는 범위를 넘으면 즉시 예외, 파티션이 늘면 조용히 편중**
> 파티션 3개인 토픽에 `send("orders", 5, key, value)` 를 하면:
> ```
> ERROR 14071 --- [ad | producer-1] o.s.k.support.LoggingProducerListener    : Exception thrown when sending a message with key='ORD-0001' and payload='OrderCreated[orderId=ORD-0001, cust...' to topic orders:
>
> java.lang.IllegalArgumentException: Invalid partition given with record: 5 is not in the range [0...3).
> ```
> 이건 **시끄러운 실패라 다행**입니다. 진짜 문제는 반대입니다.
> 파티션을 3 → 12 로 늘렸는데 코드가 `send(topic, i % 3, key, value)` 로 하드코딩돼 있으면
> **파티션 3~11 은 영원히 비어 있고**, 컨슈머 9개가 아무것도 안 받으며 놉니다. **에러도 경고도 없습니다.**
> **해결**: 파티션 수를 코드에 박지 말고 `kafkaTemplate.partitionsFor("orders").size()` 로 런타임에 조회하세요.

```java
List<PartitionInfo> infos = kafkaTemplate.partitionsFor("orders");
log.info("orders 파티션 수 = {}", infos.size());     // → orders 파티션 수 = 3
```

> 💡 `partitionsFor` 는 메타데이터가 없으면 **`max.block.ms` 까지 블로킹합니다.** 요청 경로에서 매번 부르지 마세요.
> 기동 시 한 번 조회해 캐시하되, 파티션이 늘어날 수 있음을 감안해 주기적으로 갱신하는 편이 안전합니다.

---

## 2-9. `acks` / `retries` / `enable.idempotence` — 세 설정의 상호 제약

이 셋은 **독립적이지 않습니다.** 조합에 따라 기동이 실패하기도 하고, 더 나쁘게는 **조용히 무력화**되기도 합니다.

| 설정 | kafka-clients 3.x 기본값 | 의미 |
|---|---|---|
| `acks` | **`all`** (idempotence 기본 on 때문) | `0`=응답 안 기다림 / `1`=리더만 / `all`=모든 ISR |
| `retries` | **`Integer.MAX_VALUE`** | 실질 상한은 `delivery.timeout.ms` |
| `enable.idempotence` | **`true`** (Kafka 3.0+) | 재시도로 인한 중복을 브로커가 제거 |
| `max.in.flight.requests.per.connection` | `5` | 응답을 안 받은 채 동시에 날릴 수 있는 요청 수 |

> 💡 **Spring Boot 3.x 는 `enable.idempotence` 를 설정하지 않습니다.** `true` 는 **kafka-clients 3.0 부터의 클라이언트 기본값**입니다.
> 아무것도 안 건드리면 여러분의 프로듀서는 이미 멱등 프로듀서입니다. 기동 로그의 `Instantiated an idempotent producer.` 로 확인하세요.

### ⚠️ `enable.idempotence=true` + `acks=1` → 기동 실패

```yaml
spring.kafka.producer:
  acks: 1                                  # ← 처리량 좀 올려 보려고
  properties.enable.idempotence: true      # ← 중복도 막고 싶어서
```

**결과**
```
INFO 14071 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step02"
ERROR 14071 --- [           main] o.s.boot.SpringApplication               : Application run failed

org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'step02Publisher': ...
Caused by: org.apache.kafka.common.config.ConfigException: Must set acks to all in order to use the idempotent producer. Otherwise we cannot guarantee idempotence.
	at org.apache.kafka.clients.producer.ProducerConfig.postProcessAndValidateIdempotenceConfigs(ProducerConfig.java:594)
	at org.apache.kafka.clients.producer.KafkaProducer.<init>(KafkaProducer.java:334)
```

**멱등성은 `acks=all` 을 요구합니다.** 리더만 받고 성공을 반환하면 리더가 죽었을 때 그 레코드가 사라지고, 재시도가 "중복"이 아니라 "재발행"이 되어 멱등성의 전제가 무너지기 때문입니다. 같은 종류의 제약이 셋입니다.

| 위반 조합 | 예외 메시지 |
|---|---|
| `enable.idempotence=true` + `acks=1` 또는 `0` | `Must set acks to all in order to use the idempotent producer.` |
| `enable.idempotence=true` + `retries=0` | `Must set retries to non-zero when using the idempotent producer.` |
| `enable.idempotence=true` + `max.in.flight > 5` | `Must set max.in.flight.requests.per.connection to at most 5 when using the idempotent producer.` |

### ⚠️ 진짜 함정 — `acks=1` 만 쓰면 멱등성이 조용히 꺼진다

위 셋은 **명시적으로 `enable.idempotence=true` 를 쓴 경우**입니다. `acks: 1` 만 주면?

**결과 — 기동 성공**
```
INFO 14071 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka version: 3.6.1
INFO 14071 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 1.982 seconds (process running for 2.310)
```

**아무 일도 없이 뜹니다.** `Instantiated an idempotent producer.` 가 **사라졌다는 사실을 알아채기 전까지는** 멀쩡해 보입니다.

> ⚠️ **함정 — 설정하지 않은 멱등성은 "충돌하면 조용히 false 로 내려간다"**
> `ProducerConfig` 는 `enable.idempotence` 가 **사용자에 의해 명시되지 않았고** 다른 설정과 충돌하면,
> 예외 대신 **조용히 `false` 로 내립니다.** "기본값이니까 사용자가 원한 게 아닐 수도 있다"는 판단입니다.
> 즉 **`acks: 1` 한 줄이 멱등성을 통째로 끕니다.** 그 결과 `retries` 로 인한 **중복 레코드**가 생기고, `max.in.flight=5` 와 만나면 **순서까지 뒤집힙니다.**
> **증상**: 평소엔 멀쩡하다가, 네트워크가 한 번 흔들린 뒤 컨슈머가 같은 주문을 두 번 처리하거나 역순으로 처리합니다.
> **확인법**: 기동 로그에서 `Instantiated an idempotent producer.` 를 찾으세요. **없으면 꺼진 것입니다.**
> **해결**: 멱등성이 필요하면 `enable.idempotence: true` 를 **명시**하세요. 그러면 충돌 시 조용히 꺼지는 대신 **기동이 실패합니다.**
> 시끄러운 실패를 사는 것이 조용한 유실을 사는 것보다 언제나 낫습니다.

### `retries` + `max.in.flight` 가 순서를 뒤집는 원리

멱등성이 꺼진 상태에서 `retries=3`, `max.in.flight=5` 라면:

```
프로듀서 → 브로커  요청A [ORD-0001 첫 번째 이벤트]  ┐
프로듀서 → 브로커  요청B [ORD-0001 두 번째 이벤트]  ┘ 동시에 in-flight

브로커: 요청A 처리 중 일시 오류 → NOT_ENOUGH_REPLICAS 응답
브로커: 요청B 정상 처리 → offset 10 에 기록          ← B 가 먼저 들어감!
프로듀서: 요청A 재시도 → offset 11 에 기록           ← A 가 나중에

결과: orders-2 [10]=두 번째 이벤트, [11]=첫 번째 이벤트   ← 같은 파티션인데 순서 역전
```

컨슈머는 이걸 알 방법이 없습니다. 멱등성이 켜져 있으면 각 레코드가 **시퀀스 번호**를 받아, 브로커가 순서 어긋난 요청을 `OUT_OF_ORDER_SEQUENCE_NUMBER` 로 거부하고 프로듀서가 순서대로 다시 보냅니다. 그래서 `max.in.flight=5` 여도 순서가 보장됩니다.

| 조합 | 중복 | 순서 | 처리량 |
|---|---|---|---|
| `idempotence=true`, `acks=all`, `in.flight≤5` | 없음 | **보장** | 높음 — **권장 기본값** |
| `idempotence=false`, `retries>0`, `in.flight=1` | 있을 수 있음 | 보장 | **낮음** (파이프라이닝 없음) |
| `idempotence=false`, `retries>0`, `in.flight>1` | 있을 수 있음 | **깨짐** ⚠️ | 높음 — **가장 위험한 조합** |
| `acks=0` | — | 보장 안 함 | 최고 — 유실 감지 자체가 불가 |

> 💡 `acks=all` 이 실제로 몇 대를 기다리는지, `min.insync.replicas` 와 어떻게 맞물리는지는
> [Kafka 코스 Step 04](../../kafka/step-04-producer/) 와 [Step 08](../../kafka/step-08-replication/) 에서 다중 브로커로 확인하세요.
> 전달 보장(at-most-once / at-least-once / exactly-once)의 전체 그림은 [Kafka 코스 Step 07](../../kafka/step-07-delivery-semantics/) 입니다.
> 우리 실습 브로커는 **단일 노드·복제본 1**이라 `acks=all` 이 사실상 `acks=1` 과 같은 비용입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `send` 오버로드 | 5종 전부 내부적으로 `ProducerRecord` 로 수렴. 헤더·타임스탬프가 필요하면 `ProducerRecord` |
| 반환 타입 | Spring Kafka **3.0 부터 `CompletableFuture`** (2.x 는 `ListenableFuture`) |
| 콜백 스레드 | `[ad \| producer-1]` — **프로듀서 I/O 스레드.** 무거운 일 금지 |
| **최대 함정** | `send()` 결과를 버리면 브로커 다운·권한 오류·크기 초과가 **예외 없이** 지나간다 |
| `LoggingProducerListener` | 실패 시 ERROR 로그는 남는다. 하지만 **비즈니스 로직은 성공한 줄 안다** |
| 직렬화 실패만 예외 | 직렬화는 호출 스레드에서 수행 → 즉시 `SerializationException` |
| **두 번째 함정** | `send().get()` → **18,400 → 920 msg/s (20배).** 배치 크기가 영원히 1 |
| 대안 | 콜백 / `flush()` / 마지막 future join(같은 파티션일 때만) / `allOf` |
| `flush()` | 프로듀서 **전체** 버퍼를 비움. 요청마다 호출하면 `.get()` 과 동일한 참사 |
| `ProducerFactory` | 프로듀서는 **싱글턴 + 스레드 안전.** 요청마다 만들지 말 것 |
| 템플릿 2개 | `NoUniqueBeanDefinitionException`. `@Qualifier` 로 명시, `@Primary` 는 피하고 `clientId` 필수 |
| 키 → 파티션 | `toPositive(murmur2(key)) % N`. **손으로 계산 가능.** `Math.abs` 금지 |
| 파티션 수 변경 | 매핑이 통째로 바뀜 → **순서 보장이 소급해서 깨짐** |
| 키 `null` | 3.3+ 내장 uniform sticky. **소량이면 한 파티션에 몰린다** |
| 명시적 파티션 | 범위 초과는 시끄럽게 실패. 파티션 증설 시 **조용히 편중** |
| `idempotence` + `acks=1` | **명시했으면 `ConfigException` 기동 실패 / 안 했으면 조용히 `false`** ⚠️ |
| 순서 역전 조합 | `idempotence=false` + `retries>0` + `max.in.flight>1` |
| 확인 한 줄 | 기동 로그의 `Instantiated an idempotent producer.` |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 직접 실행해 로그를 확인**하세요.

1. `whenComplete` 콜백으로 발행한 메시지의 `topic-partition@offset` 을 로깅하기
2. 10,000건 발행을 4가지 방식으로 측정해 처리량 비교표 만들기
3. 존재하지 않는 토픽으로 `send()` 했을 때 future 의 상태 변화를 시간 순으로 관찰하기
4. `acks`/`linger.ms` 가 다른 `KafkaTemplate` 두 개를 공존시키고 `@Qualifier` 로 골라 쓰기
5. `ORD-0010` ~ `ORD-0015` 의 파티션을 `murmur2` 로 예측하고 실제 발행 결과와 대조하기
6. `acks=1` + `enable.idempotence=true` 조합의 `ConfigException` 을 재현하고, 명시하지 않았을 때와 비교하기

---

## 다음 단계

지금까지는 보내기만 했습니다. 보낸 것이 정말 갔는지는 콘솔 컨슈머로 눈으로 확인했죠.
다음 스텝에서는 `@KafkaListener` 로 **받는 쪽**을 만듭니다. 그리고 이 스텝에서 계산한 **파티션 3개**가
`concurrency` 설정과 어떻게 맞물리는지 — `concurrency=5` 로 잡으면 남는 스레드 2개가 **경고 하나 없이 그냥 노는** 것을 확인합니다.

→ [Step 03 — @KafkaListener 기초](../step-03-listener-basics/)

---

## 실습 파일

세 파일을 순서대로 씁니다. 먼저 `Practice.java` 를 `src/main/java/com/example/order/step02/` 에 넣고 `--spring.profiles.active=step02` 로 실행하며 2-1 ~ 2-9 의 모든 로그를 재현합니다. 특히 2-3 의 브로커 다운 재현과 2-4 의 처리량 측정은 **직접 눈으로 봐야** 체감이 됩니다. 그다음 `Exercise.java` 의 6문제를 풀고, `Solution.java` 로 정답과 해설을 대조합니다.

### Practice.java

교재 본문의 모든 예제를 절 번호 주석(`// [2-4] send().get() 성능 붕괴`)과 함께 담은 단일 실행 파일입니다.

- 파일 상단 주석에 **실행 명령, 필요한 `application-step02.yml` 설정, 확인용 CLI 명령**이 전부 적혀 있습니다. 이 스텝은 `delivery.timeout.ms` 를 10초로 줄여야 2-3 을 90분 안에 끝낼 수 있으므로, 프로필 전용 yml 을 반드시 만드세요.
- 실행은 `SILENT_LOSS_DEMO` / `BENCH` / `PARTITION_MAP` 세 개의 **불리언 스위치**로 제어합니다. 전부 켜면 브로커를 내렸다 올리는 사이에 벤치마크가 섞여 결과가 오염되므로, **한 번에 하나씩** 켜서 돌리세요.
- `[2-3]` 의 `SilentLossDemo` 는 브로커가 **꺼져 있어야** 의미가 있습니다. 앱을 먼저 띄우고 `docker compose stop kafka` 를 한 뒤 스위치를 켠 채 재시작하면, "발행 완료" 5줄이 먼저 찍히고 10초 뒤 `LoggingProducerListener` 의 ERROR 가 몰려 나옵니다. 실습이 끝나면 `docker compose start kafka` 로 되돌리세요.
- `[2-4]` 의 `ThroughputBench` 는 측정 전에 **워밍업 1,000건**을 먼저 보냅니다. JIT 컴파일과 메타데이터 조회를 측정 구간에서 빼기 위한 것으로, 이걸 빼면 (a) 가 (c) 보다 느리게 나오는 황당한 결과가 나옵니다.
- `[2-5]` 의 `DualProducerConfig` 는 `@Profile("step02-dual")` 로 분리해 두었습니다. 켜면 `KafkaTemplate` 빈이 셋(자동 설정 1 + 여기 2)이 되므로, 이 프로필에서는 반드시 `@Qualifier` 로 주입받아야 합니다.
- `[2-7]` 의 `PartitionMapDemo` 는 `Utils.murmur2` 를 직접 호출해 **예측값을 먼저 로깅한 뒤** 발행 콜백의 실제 파티션과 대조합니다. 두 값이 다르다면 토픽의 파티션 수가 3이 아닌 것이니 그 자리에서 멈추세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 뼈대는 그대로 컴파일됩니다.

- **문제 1·5** 는 콜백을 붙여 **관찰**하는 문제, **문제 2·3** 은 측정과 상태 추적, **문제 4·6** 은 **설정을 직접 만들어** 결과를 확인하는 문제입니다.
- 문제 3 은 `isDone()` / `isCompletedExceptionally()` 를 **1초 간격으로 폴링**해 상태 변화 시점을 기록하는 구조입니다. 실행 전에 `kafka-configs.sh` 로 자동 토픽 생성을 끄지 않으면 토픽이 생겨 버려 문제가 성립하지 않으니, 파일 상단 주석의 명령을 그대로 따르세요(실습 후 `--delete-config` 로 되돌리는 명령도 함께 적혀 있습니다).
- 문제 6 은 **일부러 기동을 실패시키는** 문제입니다. `@Profile("step02-ex6")` 로 격리해 두었으므로 이 프로필로 켜면 앱이 뜨지 않는 것이 정상입니다. 다른 문제를 풀 때 이 프로필을 켜지 마세요. 또한 `DefaultKafkaProducerFactory` 는 **지연 생성**이라 빈 등록만으로는 예외가 안 납니다 — `createProducer()` 를 강제로 한 번 호출해야 `ConfigException` 을 볼 수 있다는 힌트가 주석에 있습니다.
- ⚠️ 문제 2 의 벤치마크는 `orders` 토픽에 **약 40,000건**(4방식 × 10,000)을 밀어 넣습니다. 뒤 스텝 실습에 방해되면 `docker compose down -v && docker compose up -d` 로 초기화하세요.
- 각 문제 아래에 **기대 출력 예시**가 주석으로 붙어 있습니다. 형태가 다르면 답이 틀린 것이니 `Solution.java` 를 열기 전에 다시 보세요.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `whenComplete` 를 씁니다. `thenAccept` 는 성공 시에만 실행돼 실패를 놓치고, `exceptionally` 는 실패 시에만 실행돼 성공을 놓칩니다. **둘 다 로깅해야 하므로 `whenComplete` 가 유일한 답**이며, 체이닝으로 흉내 내면 "발행은 성공했는데 실패 로그가 찍히는" 혼란이 생긴다는 점까지 설명합니다.
- **정답 2** 는 워밍업과 `flush()` 위치가 핵심입니다. (a) 는 `flush()` 를 **측정 구간 안**에 넣어야 공정합니다. 밖에 두면 "버퍼에 넣는 시간"만 재게 되어 200,000 msg/s 같은 무의미한 숫자 — 사실상 `ArrayDeque` 삽입 속도 — 가 나옵니다. 그 이유를 15줄로 설명합니다.
- **정답 3** 의 관찰 결과가 이 스텝의 요약입니다. `send()` 직후 `isDone=false` → 60초간 계속 `false` → `TimeoutException` 으로 `isCompletedExceptionally=true`. **그 60초 동안 호출부는 성공했다고 믿고 있었고, 그사이 DB 트랜잭션은 이미 커밋됐다**는 것이 결론입니다.
- **정답 4** 는 `@Bean` 이름을 `@Qualifier` 문자열로 쓰는 방식을 택합니다. `@Primary` 를 안 쓰는 이유를 주석이 설명합니다 — **`@Primary` 는 "실수로 주입받은 코드"가 어느 쪽을 쓰는지를 침묵으로 결정**해 버리기 때문입니다. `@Qualifier` 를 강제하면 새 코드는 반드시 선택해야만 컴파일됩니다.
- **정답 5** 는 `Utils.toPositive(Utils.murmur2(bytes)) % n` 을 그대로 씁니다. `Math.abs(hash) % n` 으로 쓰면 **`Integer.MIN_VALUE` 에서 음수가 나와** 배열 인덱스 예외가 나는 고전적 버그인데, Kafka 가 `Math.abs` 대신 `toPositive` 를 쓰는 이유가 정확히 이것입니다.
- **정답 6** 은 두 경우를 나란히 보여줍니다. `enable.idempotence=true` + `acks=1` → `ConfigException` 기동 실패, `acks=1` 만 → **기동 성공 + 멱등성 조용히 off**. 후자가 더 위험한 이유를 배포 파이프라인이 막히는 실패와 며칠 뒤 재고 음수로 드러나는 실패의 대비로 설명하고, 기동 로그에서 `Instantiated an idempotent producer.` 를 확인하는 습관을 강조합니다.

```java file="./Solution.java"
```
