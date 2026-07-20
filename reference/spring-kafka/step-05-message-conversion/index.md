# Step 05 — 메시지 변환과 헤더

> **학습 목표**
> - `@KafkaListener` 메서드가 받을 수 있는 **모든 파라미터 종류**를 표로 정리하고, 각각 언제 쓰는지 판단한다
> - 파라미터를 누가 해석하는지(`MessagingMessageListenerAdapter` → `DefaultMessageHandlerMethodFactory`) 호출 경로를 따라간다
> - Spring Kafka 3.0 에서 이름이 바뀐 `KafkaHeaders` 상수를 **런타임 null 이 나기 전에** 잡아낸다
> - `@Header` 의 `required` / `defaultValue` 로 옵셔널 헤더를 안전하게 받는다
> - `Serializer` 방식과 `MessageConverter` 방식의 변환 지점 차이를 실측 로그로 구분하고, `@KafkaHandler` 로 한 토픽의 여러 타입을 분기한다
> - **traceId 헤더를 발행 → 소비 → MDC → 재발행까지 전파**시키고, 로그 패턴 `%X{traceId}` 에 실제로 찍히는 것을 확인한다
> - 헤더가 byte[] 라서 생기는 `[B@1a2b3c` 사고를 재현하고 고친다
>
> **선행 스텝**: Step 04 — 직렬화와 역직렬화
> **예상 소요**: 90분

---

## 5-0. 실습 준비

Step 04 에서 **값(payload)이 어떻게 바이트가 되고 다시 객체가 되는지**를 봤습니다.
이 스텝은 그 바깥, **레코드에 함께 실려 오는 나머지 전부** — 키·파티션·오프셋·타임스탬프·헤더 — 를 다룹니다.

이 스텝에서만 쓰는 두 번째 이벤트 타입이 필요합니다. `domain` 패키지에 하나 추가하세요.

```java
package com.example.order.domain;

import java.time.Instant;

public record OrderCancelled(String orderId, String reason, Instant cancelledAt) {
    public static OrderCancelled of(int seq) {
        return new OrderCancelled(
                "ORD-%04d".formatted(seq),
                seq % 2 == 0 ? "OUT_OF_STOCK" : "USER_REQUEST",
                Instant.parse("2025-01-01T00:00:00Z").plusSeconds(seq * 60L));
    }
}
```

`spring.json.trusted.packages` 가 이미 `com.example.order.domain` 이므로 설정은 건드릴 필요 없습니다.
컨슈머 그룹은 `s05-` 접두사를 씁니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=step05'
```

**결과**
```
INFO 13405 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step05"
INFO 13405 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s05-inventory: partitions assigned: [orders-0, orders-1, orders-2]
```

---

## 5-1. 리스너 메서드가 받을 수 있는 파라미터 전부

`@KafkaListener` 메서드의 시그니처는 **자유롭습니다.** 필요한 것만 골라서 아무 순서로 선언하면 됩니다.

```java
@KafkaListener(topics = "orders", groupId = "s05-inventory")
public void onOrder(
        @Payload OrderCreated event,
        @Header(KafkaHeaders.RECEIVED_KEY) String key,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {

    log.info("topic={} p={} off={} key={} ts={} sku={}",
            topic, partition, offset, key, timestamp, event.sku());
}
```

**결과**
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$AllParams          : topic=orders p=1 off=0 key=ORD-0001 ts=1735689660000 sku=SKU-002
INFO 13405 --- [ntainer#0-1-C-1] c.e.o.step05.Practice$AllParams          : topic=orders p=0 off=0 key=ORD-0002 ts=1735689720000 sku=SKU-003
```

받을 수 있는 것 전체입니다.

| 파라미터 | 타입 | 값 | 언제 쓰는가 |
|---|---|---|---|
| (애노테이션 없음) | `OrderCreated` | 역직렬화된 페이로드 | 가장 흔한 형태. 메타데이터가 필요 없을 때 |
| `@Payload` | `OrderCreated` | 위와 동일 | 파라미터가 2개 이상일 때 **명시적으로** 페이로드를 지목 |
| `@Header(RECEIVED_KEY)` | `String` | 레코드 키 | 키 기반 라우팅·멱등 판정. **키가 null 이면 이 헤더도 없다**(5-4) |
| `@Header(RECEIVED_PARTITION)` | `int` | 파티션 번호 | 순서 보장 디버깅, 파티션별 통계 |
| `@Header(OFFSET)` | `long` | 오프셋 | 재처리 지점 기록, 중복 판정 |
| `@Header(RECEIVED_TOPIC)` | `String` | 토픽명 | 한 리스너가 `topics = {"orders", "orders.DLT"}` 처럼 여러 토픽을 볼 때 |
| `@Header(RECEIVED_TIMESTAMP)` | `long` | epoch millis | 지연(lag) 계산: `System.currentTimeMillis() - ts` |
| `@Header(TIMESTAMP_TYPE)` | `String` | `CreateTime`/`LogAppendTime` | 타임스탬프의 출처 판별 |
| `@Header(GROUP_ID)` | `String` | 컨슈머 그룹 | 공통 리스너를 여러 그룹이 공유할 때 |
| `@Header("traceId")` | `String` | **커스텀 헤더** | 추적 ID 전파(5-8) |
| `@Headers` | `Map<String,Object>` | 헤더 전체 | 이름을 미리 모를 때. ⚠️ **byte[] 가 섞인다**(5-9) |
| (애노테이션 없음) | `MessageHeaders` | 헤더 전체(불변) | `@Headers Map` 과 거의 같음. 읽기 전용 의미가 명확 |
| (애노테이션 없음) | `ConsumerRecord<K,V>` | 원본 레코드 | 메타데이터 **전부** + `serializedValueSize` 등(5-5) |
| (애노테이션 없음) | `Message<OrderCreated>` | Spring Messaging 메시지 | `getPayload()` + `getHeaders()`. 다른 Spring Messaging 컴포넌트로 넘길 때 |
| (애노테이션 없음) | `Acknowledgment` | 수동 커밋 핸들 | `AckMode.MANUAL*` 일 때만 non-null (Step 06) |
| (애노테이션 없음) | `Consumer<?,?>` | 카프카 컨슈머 | `pause()`/`seek()`/`position()` 직접 호출 (Step 10) |

> 💡 **파라미터 개수에 성능 차이는 없습니다.** 헤더는 이미 `MessageHeaders` 맵으로 만들어진 뒤 리플렉션으로 꺼내 쓰는 것이라,
> 5개를 받든 1개를 받든 브로커에서 더 가져오는 건 없습니다. **필요한 걸 다 받으세요.**

> ⚠️ `Acknowledgment` 를 선언했는데 `AckMode` 가 `BATCH`(기본값)면 **null 이 주입됩니다.** 예외가 아니라 null 입니다.
> `ack.acknowledge()` 를 부르는 순간 `NullPointerException` 이 납니다. Step 06 에서 다룹니다.

---

## 5-2. 파라미터 해석은 누가 하는가

`@KafkaListener` 는 Kafka 기능이 아니라 **Spring Messaging 기능**입니다. 경로를 따라가면 이렇습니다.

```
① KafkaListenerAnnotationBeanPostProcessor   @KafkaListener 메서드 → MethodKafkaListenerEndpoint 등록
   (이때 DefaultMessageHandlerMethodFactory 를 주입)
          ▼
② KafkaListenerEndpointRegistry              엔드포인트마다 컨테이너 생성·기동
          ▼
③ MessagingMessageListenerAdapter            ConsumerRecord ──RecordMessageConverter──▶ Message<?>
                                             (payload + KafkaHeaders.* + 커스텀 헤더)
          ▼
④ InvocableHandlerMethod                     파라미터마다 리졸버를 순서대로 물어본다
     · HeaderMethodArgumentResolver    ← @Header
     · HeadersMethodArgumentResolver   ← @Headers, MessageHeaders
     · MessageMethodArgumentResolver   ← Message<?>
     · PayloadMethodArgumentResolver   ← @Payload, 그리고 "남는 것"
          ▼
⑤ 여러분의 메서드
```

핵심은 ③ 과 ④ 사이입니다. **④ 는 Kafka 를 전혀 모릅니다.** 이미 `Message<?>` 로 번역된 것만 봅니다.
Kafka 의 파티션·오프셋이 `@Header` 로 꺼내지는 이유는, ③ 이 그것들을 `kafka_receivedPartition` 같은 **헤더 이름으로 심어 두었기 때문**입니다.

### 애노테이션 없는 파라미터가 페이로드가 되는 규칙

`PayloadMethodArgumentResolver` 는 **마지막 순서**로 등록되고, `useDefaultResolution = true` 로 동작합니다.
즉 "앞의 리졸버가 아무도 못 가져간 파라미터"를 전부 페이로드로 간주합니다. 그래서 이런 것들이 성립합니다.

```java
public void a(OrderCreated event) { }                                  // OK — 페이로드
public void b(@Payload OrderCreated event, @Header(OFFSET) long off) { } // OK — 명시
public void c(OrderCreated event, @Header(OFFSET) long off) { }          // OK — off 는 헤더가 가져감
```

`ConsumerRecord` / `Message` / `Acknowledgment` / `Consumer` 는 **앞쪽 리졸버 또는 어댑터가 특별 취급**하므로 페이로드로 오해되지 않습니다.

> ⚠️ **함정 — 애노테이션 없는 파라미터가 둘이면 조용히 잘못된 쪽이 페이로드가 된다**
> ```java
> public void bad(OrderCreated event, String key) { }   // key 에 무엇이 들어올까?
> ```
> `key` 도 `PayloadMethodArgumentResolver` 가 가져갑니다. 결과는 **둘 다 페이로드**이며,
> `String` 으로 변환하려다 `MethodArgumentNotValidException` 또는 이해 불가능한 변환 실패가 납니다.
> 기동 시점에는 아무 경고도 없고, **첫 메시지가 올 때까지 아무 일도 안 일어납니다.**
> ```
> ERROR 13405 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Error handler threw an exception
> org.springframework.kafka.listener.ListenerExecutionFailedException: Listener method could not be invoked with the incoming message
> Caused by: org.springframework.messaging.converter.MessageConversionException: Cannot convert from [com.example.order.domain.OrderCreated] to [java.lang.String] for GenericMessage [payload=OrderCreated[orderId=ORD-0001, ...]]
> ```
> **규칙 하나로 예방됩니다: 파라미터가 2개 이상이면 페이로드에 반드시 `@Payload` 를 붙이세요.**

---

## 5-3. ⚠️ `KafkaHeaders` 상수 이름이 3.0 에서 바뀌었다

이 스텝에서 가장 많이 시간을 잡아먹는 함정입니다. 인터넷의 Spring Kafka 예제는 압도적으로 **2.x** 기준입니다.

| 2.x 상수 | 2.x 문자열 값 | 3.x 상수 | 3.x 문자열 값 |
|---|---|---|---|
| `RECEIVED_MESSAGE_KEY` | `kafka_receivedMessageKey` | **`RECEIVED_KEY`** | **`kafka_receivedKey`** |
| `RECEIVED_PARTITION_ID` | `kafka_receivedPartitionId` | **`RECEIVED_PARTITION`** | **`kafka_receivedPartition`** |
| `MESSAGE_KEY` | `kafka_messageKey` | **`KEY`** | **`kafka_key`** |
| `PARTITION_ID` | `kafka_partitionId` | **`PARTITION`** | **`kafka_partition`** |
| `RECEIVED_TOPIC` | `kafka_receivedTopic` | `RECEIVED_TOPIC` (동일) | `kafka_receivedTopic` |
| `OFFSET` | `kafka_offset` | `OFFSET` (동일) | `kafka_offset` |
| `RECEIVED_TIMESTAMP` | `kafka_receivedTimestamp` | `RECEIVED_TIMESTAMP` (동일) | `kafka_receivedTimestamp` |

**상수 이름만 바뀐 게 아니라 문자열 값도 바뀌었습니다.** 여기서 두 갈래로 갈립니다.

**(A) 상수를 쓴 경우 — 컴파일 에러. 다행입니다.**

```java
@Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String key   // 2.x 예제를 복사
```
```
> Task :compileJava FAILED
Practice.java:88: error: cannot find symbol
        @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String key,
                            ^
  symbol:   variable RECEIVED_MESSAGE_KEY   location: class KafkaHeaders
```

**(B) 문자열을 직접 쓴 경우 — 런타임에 실패합니다.** 3.x 어댑터는 그 이름의 헤더를 **만들지 않습니다.**

```java
@Header("kafka_receivedMessageKey") String key          // 상수 대신 문자열
```
```
ERROR 13405 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Error handler threw an exception
org.springframework.kafka.listener.ListenerExecutionFailedException: Listener method could not be invoked with the incoming message
Caused by: org.springframework.messaging.MessageHandlingException: Missing header 'kafka_receivedMessageKey' for method parameter type [class java.lang.String]
```

여기까진 그래도 시끄럽습니다. **진짜 위험한 건 `required = false` 를 함께 복사해 온 경우입니다.**

```java
@Header(name = "kafka_receivedMessageKey", required = false) String key   // 영원히 null
```

예외도, 경고도, 로그도 없습니다. `key` 는 **모든 메시지에서 null** 입니다.
"키가 안 들어와요" 라는 버그로 며칠을 태우는 전형적인 경로입니다.

**결과** (증상)
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$LegacyHeader       : key=null off=0 payload=OrderCreated
INFO 13405 --- [ntainer#0-1-C-1] c.e.o.step05.Practice$LegacyHeader       : key=null off=0 payload=OrderCreated
```

> 💡 **실무 팁 — 헤더 이름은 절대 문자열로 쓰지 마세요.**
> `KafkaHeaders.*` 상수만 쓰면 (A) 로 떨어져 **빌드가 깨지고, 그게 최선의 결과**입니다.
> 마이그레이션 시 `grep -rn '"kafka_' src/` 한 번이면 (B) 를 전부 찾아낼 수 있습니다.
> 어떤 헤더가 실제로 들어오는지 헷갈리면 5-5 의 `ConsumerRecord` 덤프를 한 번 찍어 보세요. 추측보다 빠릅니다.

---

## 5-4. ⚠️ `@Header` 가 없을 때 — 조용히 null 인가, 예외인가

`@Header` 의 기본값은 `required = true` 입니다. 헤더가 없으면 **예외**입니다.

```java
@KafkaListener(topics = "orders", groupId = "s05-trace")
public void onOrder(@Payload OrderCreated event,
                    @Header("traceId") String traceId) {   // required 기본 true
    log.info("traceId={} order={}", traceId, event.orderId());
}
```

`traceId` 헤더 없이 발행된 메시지가 하나라도 오면:

```
ERROR 13405 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Error handler threw an exception
org.springframework.kafka.listener.ListenerExecutionFailedException: Listener method could not be invoked with the incoming message
Endpoint handler details:
Method [public void com.example.order.step05.Practice$RequiredHeader.onOrder(java.lang.Object,java.lang.String)]
Bean [com.example.order.step05.Practice$RequiredHeader@2c9c4a1e]
	at org.springframework.kafka.listener.adapter.MessagingMessageListenerAdapter.invokeHandler(MessagingMessageListenerAdapter.java:376)
	... 15 common frames omitted
Caused by: org.springframework.messaging.MessageHandlingException: Missing header 'traceId' for method parameter type [class java.lang.String]
	at org.springframework.messaging.handler.annotation.support.HeaderMethodArgumentResolver.handleMissingValue(HeaderMethodArgumentResolver.java:100)
	... 17 common frames omitted
WARN 13405 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler              : Backoff none exhausted for orders-1@3
```

**이 예외는 재시도해도 절대 성공하지 않습니다.** 헤더가 없다는 사실은 재시도로 변하지 않습니다.
DLT 가 없으면 `DefaultErrorHandler` 가 로그만 남기고 넘어가고(기본 `FixedBackOff(0, 9)` 소진 후 skip), **그 메시지는 처리되지 않은 채 오프셋만 전진**합니다. 조용한 유실입니다.

세 가지 선택지가 있습니다.

| 선언 | 헤더가 없을 때 | 쓰는 상황 |
|---|---|---|
| `@Header("traceId") String t` | **예외** (`MessageHandlingException`) | 헤더가 계약상 **반드시** 있어야 할 때 |
| `@Header(name="traceId", required=false) String t` | `null` | 있으면 쓰고 없으면 넘어감 |
| `@Header(name="traceId", defaultValue="none") String t` | `"none"` | null 체크를 없애고 싶을 때 |

**결과** (`required=false` 로 고친 뒤. traceId 없이 발행된 `ORD-0003` 도 통과합니다)
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$OptionalHeader     : traceId=3f7a1c8e retry=0 key=ORD-0001
INFO 13405 --- [ntainer#0-2-C-1] c.e.o.step05.Practice$OptionalHeader     : traceId=- retry=0 key=ORD-0003
```

> ⚠️ **함정 — 원시 타입에 `required=false` 를 붙이면 다른 예외가 난다**
> ```java
> @Header(name = "retryCount", required = false) int retryCount   // int, not Integer
> ```
> 헤더가 없으면 리졸버가 null 을 넘기고, 리플렉션 호출에서
> `IllegalArgumentException: argument type mismatch` 가 납니다. 원인 메시지가 헤더와 아무 상관없어 보여서 추적이 어렵습니다.
> **옵셔널 헤더는 반드시 래퍼 타입(`Integer`, `Long`) 이나 `defaultValue` 와 함께 쓰세요.**

> 💡 `RECEIVED_KEY` 도 옵셔널로 두는 게 안전합니다. **키 없이 발행된 레코드(`send(topic, value)`)는 이 헤더 자체가 없습니다.**
> 키를 항상 넣는 코드만 보다가, 다른 팀이 키 없이 한 건 넣는 순간 리스너 전체가 죽습니다.

---

## 5-5. `ConsumerRecord` 를 직접 받기

메타데이터를 **전부** 원본 그대로 보고 싶다면 `ConsumerRecord` 를 그냥 파라미터로 선언하면 됩니다.

```java
@KafkaListener(topics = "orders", groupId = "s05-audit")
public void audit(ConsumerRecord<String, Object> record) {
    StringBuilder headers = new StringBuilder();
    for (Header h : record.headers()) {                                  // org.apache.kafka...Header
        headers.append(h.key()).append('=')
               .append(new String(h.value(), StandardCharsets.UTF_8)).append(' ');
    }
    log.info("{}-{}@{} key={} ts={}({}) keySize={} valSize={} headers=[{}]",
            record.topic(), record.partition(), record.offset(),
            record.key(), record.timestamp(), record.timestampType(),
            record.serializedKeySize(), record.serializedValueSize(), headers.toString().trim());
}
```

**결과**
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$Audit              : orders-1@0 key=ORD-0001 ts=1735689660000(CreateTime) keySize=8 valSize=152 headers=[__TypeId__=com.example.order.domain.OrderCreated traceId=3f7a1c8e spring_json_header_types={"traceId":"java.lang.String"}]
```

`ConsumerRecord` 로만 얻을 수 있는 정보들입니다.

| 메서드 | 값 | 용도 |
|---|---|---|
| `serializedKeySize()` / `serializedValueSize()` | 바이트 수 | **메시지 크기 감시.** 5-10 의 헤더 비용 계산 |
| `timestampType()` | `CreateTime`/`LogAppendTime` | 프로듀서 시각인지 브로커 수신 시각인지 |
| `headers()` | `Headers` (iterable) | **모든 헤더**, 같은 이름 중복 포함 |
| `headers().lastHeader(k)` | `Header` or null | 같은 이름이 여럿일 때 마지막 것 |
| `leaderEpoch()` | `Optional<Integer>` | 리더 변경 추적(고급) |

> 💡 **`@Headers Map` 은 같은 이름의 헤더가 여럿이면 하나만 남깁니다.** Kafka 헤더는 **멀티맵**입니다 — 같은 키를 여러 번 넣을 수 있습니다.
> `@RetryableTopic` 이 재시도 이력을 같은 이름으로 여러 번 쌓는 것이 대표적입니다(Step 08).
> 전부 봐야 한다면 `ConsumerRecord.headers()` 를 순회하는 것 말고는 방법이 없습니다.

**언제 `ConsumerRecord` 를 쓰는가:** 로깅·감사·재처리 도구처럼 **레코드를 내용이 아니라 사물로 다루는** 코드입니다.
반대로 비즈니스 로직 리스너는 `ConsumerRecord` 를 받지 마세요. 도메인 코드에 `org.apache.kafka` 임포트가 스며듭니다.

---

## 5-6. `MessageConverter` — `Serializer` 와 무엇이 다른가

Step 04 에서는 `JsonDeserializer` 로 바이트를 객체로 만들었습니다. 그런데 5-2 의 ③ 단계에도 변환기가 하나 더 있습니다. **`RecordMessageConverter`** 입니다.

```
브로커의 바이트 ──① Deserializer──▶ ConsumerRecord<K,V> ──② RecordMessageConverter──▶ Message<?> ──▶ 리스너
                 (kafka-clients, Step 04)                  (Spring Messaging, 이 절)
```

**둘 중 한 곳에서만 JSON→객체 변환을 하면 됩니다.** 어느 쪽을 고르냐가 설계 선택입니다.

| | Serializer 방식 (Step 04) | MessageConverter 방식 |
|---|---|---|
| 변환 위치 | ① kafka-clients | ② Spring Messaging |
| 컨슈머 설정 | `value-deserializer: JsonDeserializer` | `value-deserializer: StringDeserializer` |
| 타입 결정 | `spring.json.value.default.type` 또는 `__TypeId__` 헤더 | **리스너 메서드의 파라미터 타입** |
| 한 팩토리로 여러 타입 | 어렵다 (`JsonDeserializer` 인스턴스가 타입에 묶임) | **쉽다** (메서드마다 다른 타입) |
| `@KafkaHandler` 분기 | `__TypeId__` 필요 | 파라미터 타입으로 자연스럽게 |
| 실패 지점 | poll 직후. **포이즌 필이 파티션을 막음** | 리스너 호출 시점. `DefaultErrorHandler` 가 정상 처리 |
| Kafka 표준 컨슈머와 호환 | 그대로 | 그대로 |

구현체 네 가지입니다. **컨슈머의 value-deserializer 와 짝이 맞아야 합니다.**

| Converter | 짝이 되는 Deserializer | 비고 |
|---|---|---|
| `MessagingMessageConverter` | 아무거나 | **기본값.** 페이로드를 변환하지 않고 그대로 전달(헤더 매핑만) |
| `StringJsonMessageConverter` | `StringDeserializer` | 가장 흔함 |
| `ByteArrayJsonMessageConverter` | `ByteArrayDeserializer` | 문자열 중간 변환을 생략해 조금 빠름 |
| `BytesJsonMessageConverter` | `BytesDeserializer` | `org.apache.kafka.common.utils.Bytes` |

```java
@Bean
ConcurrentKafkaListenerContainerFactory<String, String> jsonConverterFactory(
        ConsumerFactory<String, String> cf) {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(cf);
    factory.setRecordMessageConverter(new StringJsonMessageConverter());   // ← 여기
    return factory;
}

@KafkaListener(topics = "orders", groupId = "s05-conv",
               containerFactory = "jsonConverterFactory")
public void onOrder(OrderCreated event) {          // 타입은 이 시그니처가 정한다
    log.info("converted -> {}", event.orderId());
}
```

**결과**
```
INFO 13405 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s05-conv: partitions assigned: [orders-0, orders-1, orders-2]
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$ConverterListener  : converted -> ORD-0001 sku=SKU-002
```

> ⚠️ **함정 — Deserializer 와 Converter 를 둘 다 JSON 으로 두면 아무 일도 안 일어난 것처럼 보인다**
> `value-deserializer: JsonDeserializer` 를 그대로 둔 채 `StringJsonMessageConverter` 를 얹으면,
> ① 에서 이미 `OrderCreated` 가 되어 있으므로 ② 는 **"변환할 게 없네" 하고 그냥 통과**시킵니다. 예외도 로그도 없습니다.
> 그래서 컨버터를 붙였는데 5-7 의 `@KafkaHandler` 분기가 안 되는데도 **원인을 못 찾습니다.**
> 컨버터를 쓰기로 했으면 **deserializer 를 반드시 `StringDeserializer` 로 내리세요.**
> ```yaml
> spring.kafka.consumer.value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
> ```
> 반대로 두 곳 모두 켰는데 deserializer 만 String 이면 정상 동작합니다. **켜진 곳이 정확히 하나여야 합니다.**

> 💡 프로듀서 쪽에도 대칭으로 컨버터를 걸 수 있지만(`KafkaTemplate.setMessagingConverter`),
> `send(topic, key, value)` 는 컨버터를 타지 않고 **serializer 로 직행**합니다. 컨버터를 타는 건 `send(Message<?>)` 뿐입니다.

---

## 5-7. `@KafkaHandler` 와 클래스 레벨 `@KafkaListener`

한 토픽에 **여러 이벤트 타입**이 섞여 오는 경우가 있습니다(주문 도메인 이벤트를 `orders` 하나로 묶는 설계).
이때 `if (payload instanceof ...)` 를 쓰지 말고 클래스 레벨 `@KafkaListener` + `@KafkaHandler` 로 분기합니다.

```java
@Component
@Profile("step05")
@KafkaListener(topics = "orders", groupId = "s05-multi",
               containerFactory = "jsonConverterFactory")   // ← 클래스에 붙인다
public static class MultiTypeListener {

    @KafkaHandler
    public void onCreated(OrderCreated event) {
        log.info("[created]   {} sku={}", event.orderId(), event.sku());
    }

    @KafkaHandler
    public void onCancelled(OrderCancelled event) {
        log.info("[cancelled] {} reason={}", event.orderId(), event.reason());
    }

    @KafkaHandler(isDefault = true)
    public void onUnknown(Object payload,
                          @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        log.warn("[unknown]   key={} type={}", key, payload.getClass().getName());
    }
}
```

**결과**
```
INFO 13405 --- [ntainer#0-1-C-1] c.e.o.step05.Practice$MultiTypeListener  : [created]   ORD-0001 sku=SKU-002
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$MultiTypeListener  : [cancelled] ORD-0002 reason=OUT_OF_STOCK
WARN  13405 --- [ntainer#0-1-C-1] c.e.o.step05.Practice$MultiTypeListener  : [unknown]   key=ORD-0004 type=java.util.LinkedHashMap
```

마지막 줄이 흥미롭습니다. `__TypeId__` 가 없는 레코드는 컨버터가 타입을 못 정해 **`LinkedHashMap`** 으로 남고, `isDefault=true` 핸들러가 받습니다.

> ⚠️ **함정 — `isDefault` 핸들러가 없으면 예외, 있으면 조용히 삼킨다**
> 매칭되는 `@KafkaHandler` 가 없을 때:
> ```
> Caused by: org.springframework.kafka.KafkaException: No method found for class java.util.LinkedHashMap
> ```
> 시끄럽습니다. 그런데 `isDefault=true` 핸들러를 만들어 두고 거기서 아무것도 안 하면,
> **모르는 타입이 전부 그리로 빨려 들어가 조용히 사라집니다.**
> 기본 핸들러는 반드시 **WARN 이상으로 로그를 남기거나 DLT 로 넘기세요.** 삼키는 용도로 쓰면 안 됩니다.

> ⚠️ 클래스 레벨 `@KafkaListener` 를 쓰면서 같은 클래스에 **메서드 레벨 `@KafkaListener` 를 함께 두면 안 됩니다.**
> `@KafkaHandler` 가 하나도 없으면 기동 시 `IllegalStateException: No @KafkaHandler methods found` 로 즉시 실패합니다. 이건 다행인 쪽입니다.

> 💡 프로듀서에서 타입별로 다른 serializer 가 필요하면 `DelegatingByTypeSerializer(Map<Class<?>, Serializer<?>>)` 를 씁니다.
> 이 실습은 두 타입 모두 JSON 이라 기본 `JsonSerializer` 하나로 충분합니다.

---

## 5-8. 커스텀 헤더로 추적 ID 전파 (실전)

한 요청이 `HTTP → orders → inventory → payments` 로 흘러갈 때, 로그를 하나로 꿰려면 **traceId 가 메시지를 따라가야** 합니다.
페이로드에 넣지 마세요. 도메인 이벤트가 인프라 관심사로 오염됩니다. **헤더가 정답입니다.**

### 프로듀서 — 헤더 붙이기 두 가지 방법

**(A) `ProducerRecord` 에 직접**

```java
var record = new ProducerRecord<String, OrderCreated>("orders", event.orderId(), event);
record.headers().add("traceId", traceId.getBytes(StandardCharsets.UTF_8));
kafkaTemplate.send(record);
```

**(B) `Message<>` 빌더 — 권장**

```java
Message<OrderCreated> message = MessageBuilder
        .withPayload(event)
        .setHeader(KafkaHeaders.TOPIC, "orders")
        .setHeader(KafkaHeaders.KEY, event.orderId())
        .setHeader("traceId", traceId)                 // ← String 그대로
        .build();
kafkaTemplate.send(message);
```

(B) 는 `KafkaTemplate` 의 `MessagingMessageConverter` → `DefaultKafkaHeaderMapper` 를 타므로,
**String 을 UTF-8 바이트로 바꾸고 `spring_json_header_types` 에 타입을 기록**해 줍니다. 5-9 의 사고를 예방하는 것이 바로 이 부분입니다.

> 💡 (B) 는 `KafkaHeaders.TOPIC`/`KEY` 를 씁니다. **`RECEIVED_` 접두사가 붙은 것은 인바운드 전용**입니다.
> 발행할 때 `RECEIVED_KEY` 를 쓰면 그냥 이름이 `kafka_receivedKey` 인 커스텀 헤더가 하나 생길 뿐, 레코드 키는 null 입니다. 조용한 실패입니다.

### 컨슈머 — MDC 에 넣고 로그 패턴에 출력

```java
@KafkaListener(topics = "orders", groupId = "s05-inventory")
public void onOrder(@Payload OrderCreated event,
                    @Header(name = "traceId", required = false) String traceId,
                    @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                    @Header(KafkaHeaders.OFFSET) long offset) {
    MDC.put("traceId", traceId != null ? traceId : "-");
    try {
        log.info("재고 차감 시작 {} qty={} ({}@{})", event.orderId(), event.quantity(), partition, offset);
        inventory.decrease(event.sku(), event.quantity());
        log.info("재고 차감 완료 {}", event.orderId());
    } finally {
        MDC.remove("traceId");   // ← 반드시 finally
    }
}
```

`application.yml` 에 로그 패턴을 추가합니다.

```yaml
logging:
  pattern:
    console: "%clr(%5p) %clr(${PID:- }){magenta} --- [%15.15t] %clr([%X{traceId:-        }]){yellow} %-40.40logger{39} : %m%n"
```

**결과**
```
 INFO 13405 --- [ntainer#0-1-C-1] [3f7a1c8e] c.e.o.step05.Practice$Inventory          : 재고 차감 시작 ORD-0001 qty=2 (1@0)
 INFO 13405 --- [ntainer#0-1-C-1] [3f7a1c8e] c.e.o.step05.Practice$Inventory          : 재고 차감 완료 ORD-0001
 INFO 13405 --- [ntainer#0-2-C-1] [        ] c.e.o.step05.Practice$Inventory          : 재고 차감 시작 ORD-0003 qty=4 (2@0)
```

마지막 줄은 traceId 없이 발행된 메시지입니다. 대괄호가 비어 있는 것으로 **"추적 ID 를 안 붙인 프로듀서가 있다"** 를 즉시 알 수 있습니다.

> ⚠️ **함정 — `MDC.remove()` 를 빼먹으면 다음 메시지에 앞 메시지의 traceId 가 찍힌다**
> 리스너 컨테이너 스레드(`ntainer#0-1-C-1`)는 **재사용**됩니다. MDC 는 ThreadLocal 입니다.
> traceId 가 없는 메시지가 오면 `MDC.put` 이 호출되지 않아 **직전 메시지의 값이 그대로 남습니다.**
> 로그는 완벽해 보이지만 **다른 요청의 로그가 한 트레이스로 뭉칩니다.** 장애 분석에서 가장 나쁜 종류의 오염입니다.
> `finally { MDC.remove(...) }` 또는 `MDC.clear()` 를 습관화하세요. 더 나은 방법은 5-8 의 `RecordInterceptor` 화입니다.

### 재발행 시 헤더 이어받기

`orders` 를 소비해 `payments` 로 다시 발행할 때, traceId 가 끊기면 추적이 거기서 멈춥니다.

```java
@KafkaListener(topics = "orders", groupId = "s05-payment")
public void relay(ConsumerRecord<String, OrderCreated> in) {
    var out = new ProducerRecord<String, OrderCreated>("payments", in.key(), in.value());
    // 인프라 헤더는 이어받고, 타입 헤더는 새로 붙게 두는 것이 안전하다
    for (Header h : in.headers()) {
        if (h.key().equals("traceId") || h.key().startsWith("x-")) {
            out.headers().add(h);          // byte[] 그대로 복사 — 변환 불필요
        }
    }
    kafkaTemplate.send(out);
}
```

**결과**
```
 INFO 13405 --- [ntainer#0-0-C-1] [3f7a1c8e] c.e.o.step05.Practice$Relay              : payments 로 중계 ORD-0001
```

> ⚠️ **헤더를 통째로 복사하면 안 됩니다.** `__TypeId__` 와 `spring_json_header_types` 까지 따라오면,
> `payments` 토픽의 값 타입이 달라졌을 때 **컨슈머가 옛 타입으로 역직렬화하려다 실패**합니다.
> **화이트리스트로 이어받으세요.** 실무에서는 `traceId`, `x-` 접두사, `correlationId` 정도면 충분합니다.

---

## 5-9. ⚠️ 함정 — 헤더 값은 `byte[]` 다

Kafka 헤더의 타입은 `String key` + **`byte[] value`** 입니다. 그 이상은 없습니다.
문자열도, 숫자도, JSON 도, **전부 여러분이 인코딩과 디코딩을 책임져야** 합니다.

```java
byte[] raw = record.headers().lastHeader("traceId").value();   // byte[]
String traceId = new String(raw, StandardCharsets.UTF_8);      // 직접 변환
```

문제는 `@Headers Map<String,Object>` 로 받았을 때입니다.

```java
@KafkaListener(topics = "orders", groupId = "s05-headers")
public void onOrder(@Payload OrderCreated event, @Headers Map<String, Object> headers) {
    log.info("traceId={} type={}", headers.get("traceId"),
             headers.get("traceId") == null ? "-" : headers.get("traceId").getClass().getSimpleName());
}
```

**결과 — 프로듀서가 5-8 의 (A) 방식(`ProducerRecord.headers().add(...)`)으로 발행한 경우**
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$RawHeaders         : traceId=[B@6f2c0754 type=byte[]
```

**결과 — 프로듀서가 (B) 방식(`MessageBuilder.setHeader`)으로 발행한 경우**
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$RawHeaders         : traceId=3f7a1c8e type=String
```

**같은 리스너 코드인데 결과가 다릅니다.** 갈림길은 `DefaultKafkaHeaderMapper` 입니다.

- (B) 는 아웃바운드에서 `spring_json_header_types` 라는 **동반 헤더**에 `{"traceId":"java.lang.String"}` 을 함께 기록합니다.
  인바운드에서 매퍼가 이 힌트를 보고 `String` 으로 복원합니다.
- (A) 는 그 힌트가 없습니다. 매퍼는 타입을 모르니 **`byte[]` 를 그대로** 맵에 넣습니다.

### 더 나쁜 경우 — `@Header String` 에서 조용히 깨진다

```java
@Header(name = "traceId", required = false) String traceId
```

힌트가 없는 (A) 메시지에서는 `byte[]` 를 `String` 파라미터에 맞춰야 합니다.
`DefaultFormattingConversionService` 는 `byte[] → String` 전용 변환기가 없어서, 최후의 수단인 `Object → String`(즉 `toString()`)으로 떨어집니다.

**결과**
```
 INFO 13405 --- [ntainer#0-1-C-1] [[B@6f2c0754] c.e.o.step05.Practice$Inventory          : 재고 차감 시작 ORD-0001 qty=2 (1@0)
 INFO 13405 --- [ntainer#0-0-C-1] [[B@1d3a4b91] c.e.o.step05.Practice$Inventory          : 재고 차감 시작 ORD-0002 qty=3 (0@0)
```

**예외가 나지 않습니다.** 로그에는 `[B@6f2c0754` 가 찍히고, 이 값은 **JVM 재시작마다 달라지므로 트레이스로 아무 쓸모가 없습니다.**
"traceId 는 잘 들어오는데 검색이 안 되네요"의 정체입니다. 다른 언어(Go/Python) 프로듀서가 붙는 순간 100% 발생합니다.

### 해결 — 셋 중 하나

**① `DefaultKafkaHeaderMapper.setRawMappedHeaders` 로 "이 헤더는 String 으로 읽어라" 라고 알려 준다** (권장)

```java
@Bean
ConcurrentKafkaListenerContainerFactory<String, Object> rawHeaderFactory(ConsumerFactory<String, Object> cf) {
    var mapper = new DefaultKafkaHeaderMapper();
    mapper.setRawMappedHeaders(Map.of("traceId", true, "x-source", true));  // true = 인바운드에서 String 변환

    var converter = new MessagingMessageConverter();
    converter.setHeaderMapper(mapper);

    var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
    factory.setConsumerFactory(cf);
    factory.setRecordMessageConverter(converter);
    return factory;
}
```

**결과** (프로듀서가 (A) 방식이어도)
```
INFO 13405 --- [ntainer#0-0-C-1] c.e.o.step05.Practice$RawHeaders         : traceId=3f7a1c8e type=String
```

**② `byte[]` 로 받아 직접 변환한다** — `@Header(name="traceId", required=false) byte[] bytes` 로 받고 `new String(bytes, UTF_8)`. 가장 정직하고 오해가 없습니다.

**③ 프로듀서를 (B) 방식으로 통일한다** — 우리 팀 코드만 있을 때는 제일 깔끔합니다. 다만 **다른 언어 프로듀서는 통제할 수 없습니다.**

> 💡 **실무 팁 — 헤더 값은 UTF-8 문자열로만 넣는 것을 팀 규칙으로 삼으세요.**
> 숫자도 `String.valueOf(n).getBytes(UTF_8)` 로 넣습니다. `ByteBuffer.allocate(4).putInt(n)` 같은 바이너리 인코딩은
> 엔디안·언어별 차이 때문에 **다른 팀이 소비하는 순간 깨집니다.** `kafka-console-consumer --property print.headers=true` 로
> 눈으로 읽을 수 있다는 점만으로도 UTF-8 문자열의 가치는 충분합니다.

---

## 5-10. 헤더 크기와 개수의 비용

헤더는 **레코드마다 반복 전송**됩니다. 압축이 걸려 있어도 헤더 이름이 매번 실립니다.
레코드 하나의 헤더 비용은 대략 `varint(키 길이) + 키 바이트 + varint(값 길이) + 값 바이트` 입니다.

`OrderCreated` 하나를 기준으로 실측했습니다.

| 구성 | 헤더 | 레코드 크기 |
|---|---:|---:|
| 헤더 없음 (`spring.json.add.type.headers: false`) | 0개 | **209 B** |
| `__TypeId__` 만 | 1개 | 256 B |
| `+ traceId` + `spring_json_header_types` | 3개 | **431 B** |

`orders` 토픽에 10만 건을 넣고 세그먼트 크기를 재면:

```bash
docker exec -it learn-kafka du -sh /var/lib/kafka/data/orders-0
```

**결과**
```
7.1M	/var/lib/kafka/data/orders-0      # 헤더 없음
14.7M	/var/lib/kafka/data/orders-0      # 헤더 3개
```

**7.1MB → 14.7MB. 페이로드는 그대로인데 저장량이 2.07배가 됐습니다.**
네트워크·디스크·리텐션 비용이 전부 2배입니다. 헤더는 공짜가 아닙니다.

| 한계 | 설정 | 기본값 | 초과 시 |
|---|---|---:|---|
| 프로듀서 요청 | `max.request.size` | 1,048,576 | `RecordTooLargeException` (즉시, 브로커 가기 전) |
| 브로커 수신 | `message.max.bytes` | 1,048,588 | `RecordTooLargeException` (브로커가 거절) |
| 토픽별 | `max.message.bytes` | 브로커값 상속 | 위와 동일 |
| 컨슈머 fetch | `max.partition.fetch.bytes` | 1,048,576 | **작으면 그 레코드를 못 읽고 멈춤** |

**헤더 크기는 이 한계에 전부 포함됩니다.** 페이로드가 900KB 인데 헤더에 스택트레이스를 200KB 넣으면 초과합니다.

```
ERROR 13405 --- [ad | producer-1] o.s.k.c.KafkaTemplate                    : Failed to send
org.apache.kafka.common.errors.RecordTooLargeException: The message is 1160244 bytes when serialized which is larger than 1048576, which is the value of the max.request.size configuration.
```

> ⚠️ **함정 — DLT 헤더가 메시지를 한계 너머로 밀어 올린다**
> `DeadLetterPublishingRecoverer`(Step 07)는 원본 메시지에 **예외 스택트레이스를 헤더로 통째로 붙입니다**
> (`kafka_dlt-exception-stacktrace`). 스택이 깊으면 수십 KB 입니다.
> 원본이 이미 900KB 였다면 **DLT 발행이 `RecordTooLargeException` 으로 실패**하고,
> 그러면 그 메시지는 **DLT 에도 못 가고 사라집니다.** 에러 처리기가 에러를 내는 상황이라 로그도 어지럽습니다.
> `DeadLetterPublishingRecoverer.setMaxStackTraceLength(...)` 로 잘라 두세요.

> 💡 **실무 팁 — 안 쓰는 헤더부터 끄세요.**
> `spring.json.add.type.headers: false` 는 `__TypeId__`(약 47B)를 없앱니다.
> 컨슈머가 `spring.json.value.default.type` 이나 `MessageConverter` 로 타입을 정한다면 이 헤더는 순수한 낭비입니다.
> Step 04 에서 본 "패키지 결합" 문제까지 함께 사라집니다.

> 💡 헤더가 Kafka 레코드 배치 안에서 실제로 어떻게 인코딩되는지(varint, 레코드 배치 오버헤드)는
> [Kafka 코스 Step 02](../../kafka/step-02-architecture/) 를 참고하세요. 이 코스는 클라이언트 쪽 비용만 다룹니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 리스너 파라미터 | 페이로드·`@Header`·`@Headers`·`ConsumerRecord`·`Message`·`Acknowledgment`·`Consumer` 를 자유 조합 |
| 파라미터 해석 | `MessagingMessageListenerAdapter` → `DefaultMessageHandlerMethodFactory` → 리졸버 체인 |
| 애노테이션 없는 파라미터 | `PayloadMethodArgumentResolver` 가 마지막에 전부 가져감. **2개 이상이면 `@Payload` 필수** |
| `KafkaHeaders` 3.0 변경 | `RECEIVED_MESSAGE_KEY`→`RECEIVED_KEY`, `RECEIVED_PARTITION_ID`→`RECEIVED_PARTITION`. **문자열 값도 바뀜** |
| 헤더 이름 하드코딩 | 상수를 쓰면 컴파일 에러(다행), 문자열을 쓰면 **런타임 null** |
| `@Header` 기본값 | `required=true` → 없으면 `MessageHandlingException`. 옵셔널은 `required=false` + **래퍼 타입** |
| `ConsumerRecord` | 유일하게 `serializedValueSize`·중복 헤더 전체·`timestampType` 을 볼 수 있음 |
| Serializer vs Converter | ① kafka-clients vs ② Spring Messaging. **둘 중 정확히 한 곳에서만** 변환 |
| Converter 의 장점 | 타입을 **메서드 시그니처**가 결정 → 한 팩토리로 여러 타입 |
| `@KafkaHandler` | 클래스 레벨 `@KafkaListener` + 타입별 메서드. `isDefault=true` 는 **반드시 로그를 남길 것** |
| traceId 전파 | `MessageBuilder.setHeader` 로 발행 → `@Header` 수신 → MDC → `%X{traceId}`. **`finally` 로 remove** |
| 재발행 | 헤더 통째 복사 금지. `traceId`·`x-` 만 화이트리스트로 이어받기 |
| 헤더는 `byte[]` | `@Headers Map` 은 raw byte[]. `@Header String` 은 `[B@1a2b3c` 로 **조용히 깨짐** |
| 해결 | `setRawMappedHeaders(Map.of("traceId", true))` 또는 `byte[]` 로 받아 직접 디코딩 |
| 헤더 비용 | 레코드마다 반복. 3개로 **저장량 2.07배**. `max.request.size`·`message.max.bytes` 에 포함 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. 토픽·파티션·오프셋·키·타임스탬프를 **한 줄로** 남기는 리스너를 `@Header` 만으로 작성하기
2. `MessageBuilder` 로 traceId 헤더를 붙여 발행하고, 컨슈머에서 MDC 로 전파한 뒤 `finally` 로 정리하기
3. `ConsumerRecord` 로 **모든 헤더를 이름=값(UTF-8) 형태로 덤프**하기 (중복 헤더 포함)
4. 클래스 레벨 `@KafkaListener` + `@KafkaHandler` 로 `OrderCreated` / `OrderCancelled` 분기하고, `isDefault` 로 나머지 처리
5. `required=false` 와 `defaultValue` 로 옵셔널 헤더 두 개(`traceId`, `retryCount`)를 안전하게 받기
6. `[B@1a2b3c` 사고를 **재현**한 뒤 `setRawMappedHeaders` 로 고치기
7. `StringJsonMessageConverter` 를 쓰는 컨테이너 팩토리를 만들어 하나의 팩토리로 두 타입 받기

---

## 다음 단계

여기까지는 "메시지를 어떻게 꺼내 읽는가"였습니다. 그런데 읽은 다음이 진짜 문제입니다.
**언제 "다 읽었다"고 브로커에 알릴 것인가** — 이 타이밍 하나가 메시지 유실과 중복을 가릅니다.
`AckMode` 6종을 전부 비교하고, 배치 리스너의 중간이 실패했을 때 **어디까지 커밋되는지**를 오프셋으로 직접 확인합니다.

→ [Step 06 — 오프셋 커밋과 AckMode](../step-06-ack-modes/)

---

## 실습 파일

세 파일을 순서대로 씁니다. 먼저 `Practice.java` 를 프로필 `step05` 로 띄워 5-1 ~ 5-10 의 로그를 교재와 대조하고, 특히 **5-9 의 `[B@...` 출력이 여러분 화면에도 나오는지** 확인하세요(안 나오면 프로듀서가 (B) 방식으로 돌고 있는 것입니다). 그다음 `Exercise.java` 의 7문제를 풀고, `Solution.java` 로 채점합니다. 세 파일 모두 `com.example.order.step05` 패키지이며 `@Profile` 로 격리되어 있어 서로 간섭하지 않습니다.

### Practice.java

본문 전 절의 예제를 절 번호 주석과 함께 담은 단일 파일입니다.

- 파일 상단 주석에 **네 개의 보조 프로필**이 정리돼 있습니다. `step05-raw`(5-9 의 (A) 방식 프로듀서), `step05-legacy`(5-3 의 2.x 문자열 헤더), `step05-required`(5-4 의 예외 재현), `step05-conv`(5-6/5-7 의 컨버터 방식). **기본 `step05` 만 켜면 정상 경로만 돕니다.**
- `[5-1] AllParams` 와 `[5-5] Audit` 는 같은 토픽을 **다른 그룹**(`s05-inventory`, `s05-audit`)으로 읽습니다. 두 리스너의 로그가 번갈아 찍히는 것이 정상입니다.
- `[5-6] jsonConverterFactory` 빈은 `StringDeserializer` 로 재정의한 별도 `ConsumerFactory` 를 씁니다. **기본 팩토리를 건드리지 않습니다.** 5-6 의 함정("둘 다 JSON")을 피하기 위해서입니다.
- `[5-8] Inventory` 는 MDC 를 `try/finally` 로 감쌌습니다. `finally` 를 지우고 다시 돌려 보면 함정 블록의 "앞 메시지 traceId 가 남는" 현상을 직접 볼 수 있습니다. **꼭 한 번 해 보세요.**
- `[5-9] RawHeaders` 는 `@Headers Map` 과 `@Header String` 을 **동시에** 받아 한 줄에 찍습니다. `step05-raw` 프로필과 함께 켜야 `[B@...` 가 나옵니다.
- `[5-10] SizeProbe` 는 `serializedKeySize + serializedValueSize + 헤더 바이트 합` 을 계산해 레코드 실제 크기를 로그로 남깁니다. 표의 209 B / 431 B 를 여러분 환경에서 재현하는 코드입니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 컴파일되는 뼈대와 `// 여기에 작성:` 자리로 되어 있습니다.

- **문제 1·3·5** 는 리스너 시그니처를 채우는 문제, **문제 2·6·7** 은 프로듀서와 설정까지 함께 손대야 하는 문제, **문제 4** 는 클래스 구조 자체를 바꾸는 문제입니다.
- 문제 6 은 **일부러 깨진 코드를 먼저 실행**해 보라고 요구합니다. `[B@` 로 시작하는 로그가 나오는 것을 확인한 뒤에 고쳐야 학습이 됩니다. 바로 정답 코드를 쓰면 절반만 배웁니다.
- 각 문제마다 **기대 로그**를 주석으로 붙여 두었습니다. 여러분 출력과 글자 단위로 맞을 필요는 없지만, `traceId=` 뒤에 `[B@` 가 붙는지 아닌지 같은 **판정 지점**은 정확히 일치해야 합니다.
- 문제 4·7 은 `OrderCancelled` 를 씁니다. 5-0 에서 만든 `domain/OrderCancelled.java` 가 없으면 컴파일되지 않습니다.
- 컨슈머 그룹은 문제별로 `s05-ex1` ~ `s05-ex7` 로 분리했습니다. 한 문제를 다시 풀 때 **그 그룹만 오프셋을 리셋**하면 됩니다.

```java file="./Exercise.java"
```

### Solution.java

정답과 "왜 그 답인가"를 설명하는 긴 주석이 문제마다 붙어 있습니다.

- **정답 1** 은 `@Payload` 를 굳이 붙입니다. 파라미터가 6개이므로 5-2 의 규칙상 명시가 안전하고, 나중에 누가 파라미터를 하나 더 넣어도 안 깨지기 때문입니다.
- **정답 2** 의 핵심은 `MessageBuilder` 쪽이 아니라 **`finally`** 입니다. 주석에서 컨테이너 스레드 재사용과 ThreadLocal 의 관계를 다시 설명하고, `RecordInterceptor` 로 옮기는 확장안을 덧붙였습니다.
- **정답 3** 은 `@Headers Map` 이 아니라 `ConsumerRecord.headers()` 를 순회합니다. **Kafka 헤더가 멀티맵**이라 `Map` 으로 받으면 중복 헤더가 사라지기 때문이고, 이것이 문제 3 이 요구한 판정 지점입니다.
- **정답 4** 는 `isDefault=true` 핸들러에서 `log.warn` + 헤더 덤프를 남깁니다. 조용히 삼키면 안 된다는 5-7 의 함정을 코드로 지킨 것입니다.
- **정답 5** 는 `retryCount` 를 `int` 가 아니라 `Integer` + `defaultValue = "0"` 으로 받습니다. 원시 타입 + `required=false` 의 `argument type mismatch` 를 피하는 유일한 방법이고, 주석에 그 예외의 스택 위치까지 적어 두었습니다.
- **정답 6** 은 `setRawMappedHeaders(Map.of("traceId", true))` 를 씁니다. `true` 가 "인바운드에서 String 으로 변환"이라는 뜻이라는 점, `false` 로 두면 byte[] 그대로라는 점을 대조해 설명합니다.
- **정답 7** 은 `StringDeserializer` 로 별도 `ConsumerFactory` 를 만드는 부분이 진짜 답입니다. 팩토리에 컨버터만 얹고 deserializer 를 안 내리면 5-6 의 함정대로 **아무 일도 안 일어납니다.**

```java file="./Solution.java"
```
