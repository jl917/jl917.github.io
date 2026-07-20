# Step 04 — 직렬화와 역직렬화

> **학습 목표**
> - 프로듀서의 `Serializer` 와 컨슈머의 `Deserializer` 가 각각 어느 시점에 호출되는지 설명한다
> - `JsonSerializer` 가 붙이는 `__TypeId__` 헤더를 콘솔 컨슈머로 직접 확인한다
> - `__TypeId__` 가 만드는 **패키지 결합**을 재현하고, 이미 쌓인 메시지는 되돌릴 수 없음을 확인한다
> - `spring.json.trusted.packages` 를 위반시켜 예외를 재현하고, `*` 의 위험을 판단한다
> - **깨진 메시지 하나로 파티션을 영원히 멈춰 세우고(포이즌 필), LAG 이 늘어나는 것을 실측한다**
> - `ErrorHandlingDeserializer` 를 적용해 같은 시나리오에서 나쁜 메시지 하나만 건너뛰게 만든다
>
> **선행 스텝**: Step 03 — @KafkaListener 기초
> **예상 소요**: 90분

---

## 4-0. 실습 준비

이 스텝은 **토픽을 더럽히는** 실습입니다. 손으로 넣은 깨진 JSON 이 영구히 남으므로, 전용 토픽 `s04-orders` 를 만들고 여기서만 놉니다. 컨슈머 그룹은 `s04-inventory` 입니다.

```bash
kt --create --topic s04-orders --partitions 3 --replication-factor 1
kt --create --topic s04-orders.DLT --partitions 3 --replication-factor 1
```

**결과**
```
Created topic s04-orders.
Created topic s04-orders.DLT.
```

되돌리려면 앱을 종료한 뒤 `kt --delete --topic s04-orders` 후 다시 만드세요.

> 💡 이 스텝에서만 `orders` 대신 `s04-orders` 를 씁니다. **포이즌 필을 본선 토픽에 심으면 뒤 스텝이 전부 막힙니다.**
> 실제로 운영에서 이 사고가 나면 토픽을 지울 수도 없어서 훨씬 곤란합니다 — 그 얘기를 4-4 에서 합니다.

---

## 4-1. 직렬화는 어디서 일어나는가

Kafka 브로커는 **`byte[]` 만 압니다.** 레코드의 키도 값도 헤더도 전부 바이트 배열이고, 브로커는 그 바이트가 JSON 인지 Avro 인지 사진 파일인지 **전혀 모르고, 알려고 하지도 않습니다.**
그래서 "객체 → 바이트"와 "바이트 → 객체"는 **전적으로 클라이언트의 책임**입니다.

```
 [프로듀서] OrderCreated 객체
     │  KafkaTemplate.send("s04-orders", "ORD-0001", event)
     ▼
  ValueSerializer      JsonSerializer.serialize(topic, headers, event)
   객체 → byte[]        ← Jackson ObjectMapper 가 돌고, __TypeId__ 헤더도 여기서 붙는다
     │  byte[]
     ▼
  브로커: 그냥 바이트로 저장   s04-orders-1 @ offset 42
     │  byte[]
     ▼
  ValueDeserializer    JsonDeserializer.deserialize(topic, headers, bytes)
   byte[] → 객체        ← __TypeId__ 헤더를 읽어 "어떤 클래스로" 를 결정
     │
     ▼  @KafkaListener 메서드 호출
 [컨슈머]
```

**직렬화 시점이 중요합니다.** `KafkaTemplate.send()` 는 **호출한 스레드**에서 직렬화까지 마친 뒤 배치 버퍼에 넣으므로 직렬화 예외는 `send()` 자리에서 **즉시** 터집니다.
반면 역직렬화 예외는 `poll()` 안에서 터지므로 리스너 메서드까지 **도달조차 하지 않습니다.** 이 비대칭이 4-6 의 포이즌 필을 만듭니다.

### 기본 제공 Serializer / Deserializer

| 타입 | Serializer | Deserializer | 제공처 |
|---|---|---|---|
| `String` | `StringSerializer` | `StringDeserializer` | kafka-clients |
| `Integer` / `Long` | `IntegerSerializer` / `LongSerializer` | 동명 Deserializer | kafka-clients |
| `byte[]` / `ByteBuffer` | `ByteArraySerializer` 등 | 동명 Deserializer | kafka-clients (기본값) |
| **POJO / record (JSON)** | **`JsonSerializer`** | **`JsonDeserializer`** | **spring-kafka** |
| 방어용 래퍼 | — | **`ErrorHandlingDeserializer`** | **spring-kafka** |
| Avro | `KafkaAvroSerializer` | `KafkaAvroDeserializer` | Confluent (이 코스 범위 밖) |

`JsonSerializer` / `JsonDeserializer` / `ErrorHandlingDeserializer` 는 **kafka-clients 가 아니라 spring-kafka 가 제공**합니다. 패키지가 `org.springframework.kafka.support.serializer` 인 이유입니다.

> 💡 **키 직렬화기는 거의 항상 `StringSerializer` 입니다.** 키는 파티션 배정에 쓰이므로 짧고 안정적인 문자열이 좋습니다.
> 키를 JSON 객체로 만들면 필드 순서가 바뀌는 것만으로 바이트가 달라져 **같은 논리 키가 다른 파티션으로 갑니다.** 순서 보장이 조용히 깨지는 대표 사례입니다.

---

## 4-2. `JsonSerializer` — Jackson 이 만든 JSON 을 눈으로 확인한다

`JsonSerializer` 는 내부에 `ObjectMapper` 를 하나 들고 있습니다. 별도로 주입하지 않으면
`JacksonUtils.enhancedObjectMapper()` 가 만든 mapper 를 씁니다. **Spring Boot 컨텍스트의 `ObjectMapper` 빈이 아닙니다.**

```java
// 생성자로 ObjectMapper 를 넘기면 Boot 가 설정한 mapper 를 그대로 씁니다
pf.setValueSerializer(new JsonSerializer<>(objectMapper));
```

`OrderCreated.of(1)` 을 보내고 `kcc --topic s04-orders --from-beginning --property print.key=true` 로 확인합니다.

**결과**
```
ORD-0001	{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
```

`createdAt` 이 `"2025-01-01T00:01:00Z"` 로 **문자열**입니다. 여기까지가 정상입니다.

### `JavaTimeModule` 이 없으면 벌어지는 일

`pf.setValueSerializer(new JsonSerializer<>(new ObjectMapper()))` 처럼 mapper 를 직접 만들어 넘기면 이렇게 나갑니다.

**결과**
```
ORD-0001	{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":{"epochSecond":1735689660,"nano":0}}
```

`createdAt` 이 **객체**가 되었습니다. Jackson 은 `JavaTimeModule` 이 없으면 `Instant` 를 그냥 POJO 로 보고 게터를 훑습니다.

이게 왜 위험한가: **Java 컨슈머는 이걸 그대로 다시 읽을 수 있습니다.** 같은 mapper 설정이면 왕복이 되니까요. 그런데 이 토픽을 **Python·Go 컨슈머나 데이터 파이프라인이 같이 읽고 있다면**
`createdAt` 은 타임스탬프가 아니라 정체불명의 객체입니다. 그리고 이 사실은 **아무 예외도 없이** 몇 달 뒤 리포트가 이상하다는 제보로 발견됩니다.

`JacksonUtils.enhancedObjectMapper()` 와 Boot 의 `ObjectMapper` 빈은 `JavaTimeModule` 을 자동 등록하고 `WRITE_DATES_AS_TIMESTAMPS` 를 끕니다. 명시적으로 못박으려면 `spring.jackson.serialization.write-dates-as-timestamps: false` 를 쓰세요.

`BigDecimal` 은 다른 문제입니다. `amount` 는 따옴표 없이 `"amount":11000` 으로 나갔는데, JSON 의 숫자에는 정밀도 개념이 없습니다.
`11000.50` 은 `11000.5` 로 나가고 **JavaScript 컨슈머가 읽으면 IEEE 754 double 로 들어가 정밀도가 깨집니다.**
다국어 컨슈머가 붙는 토픽이면 `@JsonSerialize(using = ToStringSerializer.class)` 로 **처음부터 문자열로** 정하세요.

| 타입 | 모듈 없을 때 JSON | 모듈 있을 때 JSON | 권장 |
|---|---|---|---|
| `Instant` | `{"epochSecond":1735689660,"nano":0}` | `"2025-01-01T00:01:00Z"` | `JavaTimeModule` + `write-dates-as-timestamps: false` |
| `LocalDateTime` | `[2025,1,1,0,1]` (배열!) | `"2025-01-01T00:01:00"` | 타임존이 없으므로 이벤트에는 `Instant` 권장 |
| `BigDecimal` | `11000` | `11000` | 다국어 컨슈머면 `ToStringSerializer` |
| `enum` | `"CREATED"` (이름) | 동일 | `@JsonValue` 로 코드값 고정 권장 |

---

## 4-3. `__TypeId__` 타입 헤더 — 이 스텝의 주인공

이번엔 헤더까지 켜고 봅니다.

```bash
kcc --topic s04-orders --from-beginning \
    --property print.key=true --property print.headers=true --property print.partition=true
```

**결과**
```
Partition:1	__TypeId__:com.example.order.domain.OrderCreated	ORD-0001	{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
Partition:0	__TypeId__:com.example.order.domain.OrderCreated	ORD-0002	{"orderId":"ORD-0002","customerId":1002,"sku":"SKU-003","quantity":3,"amount":12000,"createdAt":"2025-01-01T00:02:00Z"}
Partition:2	__TypeId__:com.example.order.domain.OrderCreated	ORD-0003	{"orderId":"ORD-0003","customerId":1003,"sku":"SKU-001","quantity":4,"amount":13000,"createdAt":"2025-01-01T00:03:00Z"}
```

`__TypeId__:com.example.order.domain.OrderCreated`.
`JsonSerializer` 가 **레코드 헤더에 FQCN(패키지까지 포함한 클래스 이름)을 문자열로 박아 넣은 것**입니다.
이 헤더는 컨슈머 쪽 `JsonDeserializer` 를 위한 것입니다. 컨슈머는 바이트 배열만 받으므로 "이 JSON 을 **어떤 클래스로** 만들어야 하는가"를 알 방법이 없습니다. `__TypeId__` 가 그 답을 알려 줍니다.

| 헤더 | 언제 붙는가 | 무엇을 담는가 |
|---|---|---|
| `__TypeId__` | 항상 (값 직렬화 시) | 값 객체의 FQCN 또는 매핑된 논리 이름 |
| `__KeyTypeId__` | 키도 `JsonSerializer` 일 때 | 키 객체의 FQCN |
| `__ContentTypeId__` | 컨테이너(List 등)일 때 | 원소 타입 |

덕분에 **한 토픽에 여러 이벤트 타입을 섞어 보낼 수 있습니다.** `OrderCreated` 와 `OrderCancelled` 를 같은 토픽에 넣어도 컨슈머가 헤더를 보고 각각 맞는 클래스로 만듭니다. 편리합니다.

**그리고 바로 그 편리함이 함정입니다.**

---

## 4-4. ⚠️ 함정 — `__TypeId__` 가 만드는 패키지 결합

> ⚠️ **함정 — 컨슈머 쪽 클래스를 옮기는 순간 전 메시지가 역직렬화 실패한다**
>
> `__TypeId__` 에는 **패키지 경로가 통째로** 들어갑니다. 즉 토픽에 흐르는 메시지가
> **프로듀서 애플리케이션의 물리적 패키지 구조를 그대로 기록**하고 있는 것입니다.
> 이것은 이벤트 스키마가 아니라 **남의 코드베이스 디렉터리 구조에 대한 의존**입니다.

시나리오는 지극히 평범합니다. 재고 서비스 팀이 "주문 이벤트 클래스가 `com.example.order.domain` 에 있는데,
우리 관점에선 이게 소비하는 이벤트니까 `com.example.inventory.event` 로 옮기는 게 맞겠다"고 판단합니다.
**아주 정상적인 리팩터링입니다.** IDE 의 Move Class 한 번이면 끝나고, 컴파일도 되고, 테스트도 통과합니다.
그리고 배포하는 순간 컨슈머가 이렇게 됩니다.

```
INFO  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : s04-inventory: partitions assigned: [s04-orders-0, s04-orders-1, s04-orders-2]
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Consumer exception

java.lang.IllegalStateException: This error handler cannot process 'SerializationException's directly; please consider configuring an 'ErrorHandlingDeserializer' in the value and/or key deserializer
	at org.springframework.kafka.listener.DefaultErrorHandler.handleOtherException(DefaultErrorHandler.java:151) ~[spring-kafka-3.1.4.jar:3.1.4]
Caused by: org.apache.kafka.common.errors.RecordDeserializationException: Error deserializing key/value for partition s04-orders-1 at offset 42. If needed, please seek past the record to continue consumption.
	at org.apache.kafka.clients.consumer.internals.CompletedFetch.parseRecord(CompletedFetch.java:331) ~[kafka-clients-3.6.1.jar:na]
Caused by: org.springframework.messaging.converter.MessageConversionException: failed to resolve class name. Class not found [com.example.order.domain.OrderCreated]
	at org.springframework.kafka.support.mapping.DefaultJackson2JavaTypeMapper.getClassIdType(DefaultJackson2JavaTypeMapper.java:129) ~[spring-kafka-3.1.4.jar:3.1.4]
	at org.springframework.kafka.support.serializer.JsonDeserializer.deserialize(JsonDeserializer.java:588) ~[spring-kafka-3.1.4.jar:3.1.4]
Caused by: java.lang.ClassNotFoundException: com.example.order.domain.OrderCreated
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641) ~[na:na]
```

`ClassNotFoundException: com.example.order.domain.OrderCreated`.
컨슈머 코드에는 그 클래스가 더 이상 없으니 당연합니다. 그런데 **토픽에 이미 쌓인 수백만 건의 메시지 헤더에는 그 이름이 그대로 박혀 있습니다.**

### 진짜 위험한 부분은 되돌릴 수 없다는 것

여기서 대부분 이렇게 생각합니다. "롤백하면 되지."

- 컨슈머 코드를 되돌리면? **됩니다.** 대신 리팩터링을 영원히 못 합니다.
- 프로듀서 설정을 고쳐 `__TypeId__` 를 새 이름으로 보내면? **앞으로 오는 메시지만** 고쳐집니다.
- 이미 토픽에 있는 메시지의 헤더를 고치려면? **불가능합니다.** Kafka 의 레코드는 불변이고, 브로커에는 "헤더를 수정하는 API" 가 없습니다.

즉 **토픽에 남아 있는 리텐션 기간(기본 7일) 내내 그 메시지들은 옛 이름을 주장**합니다. 컨슈머는 옛 이름과 새 이름을 **둘 다** 처리할 수 있어야 하고,
그건 클래스를 옮기지 않았다는 뜻입니다. 리텐션이 `-1`(무한)이거나 compact 토픽이면 **영원히** 그렇습니다.

### 해결책 3가지

| 방법 | 설정 | 동작 | 평가 |
|---|---|---|---|
| ① **타입 헤더를 끄고 고정 타입** | 프로듀서 `spring.json.add.type.headers=false`<br>컨슈머 `spring.json.value.default.type=<FQCN>` | 헤더 자체가 안 나감. 컨슈머가 자기가 아는 클래스로 무조건 역직렬화 | **권장.** 토픽 1개 = 타입 1개일 때 가장 단순하고 결합이 0 |
| ② **논리 타입명 매핑** | 양쪽 `spring.json.type.mapping=order:<각자의 FQCN>` | 헤더에 `order` 라는 **논리 이름**이 나감. 각 애플리케이션이 자기 클래스로 매핑 | 한 토픽에 여러 타입이 섞일 때. 클래스는 자유롭게 이동 가능 |
| ③ **컨슈머만 헤더 무시** | 컨슈머 `spring.json.use.type.headers=false`<br>또는 `deserializer.setUseTypeHeaders(false)` | 헤더는 오지만 컨슈머가 안 봄. `value.default.type` 을 씀 | 프로듀서를 못 고칠 때의 **응급 처치**. 헤더 바이트는 계속 낭비 |

**①번 설정 (권장)**

```yaml
spring.kafka.producer.properties.spring.json.add.type.headers: false   # __TypeId__ 를 아예 안 붙인다
spring.kafka.consumer.properties.spring.json.value.default.type: com.example.order.domain.OrderCreated
```

**결과** — 헤더가 사라졌습니다.
```
Partition:1	NO_HEADERS	ORD-0001	{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
```

이제 컨슈머가 클래스를 어디로 옮기든 상관없습니다. **컨슈머 자기 설정만 고치면 됩니다.**

**②번 설정 — 한 토픽에 여러 타입이 필요할 때**

```yaml
# 프로듀서 (order 서비스)
spring.json.type.mapping: order:com.example.order.domain.OrderCreated,cancel:com.example.order.domain.OrderCancelled
# 컨슈머 (inventory 서비스) — 클래스를 자기 패키지로 옮겨도 된다
spring.json.type.mapping: order:com.example.inventory.event.OrderCreated,cancel:com.example.inventory.event.OrderCancelled
```

**결과**
```
Partition:1	__TypeId__:order	ORD-0001	{"orderId":"ORD-0001","customerId":1001,...}
```

`__TypeId__:order`. **패키지가 사라졌습니다.** 이제 헤더는 "이건 주문 생성 이벤트다"라는 **계약**만 담고,
그 계약을 어떤 클래스로 구현할지는 각 애플리케이션의 자유입니다.

> 💡 **결론 — 처음부터 타입 헤더를 끄거나 논리 이름을 쓰세요.**
> `__TypeId__` 의 기본 동작(FQCN 그대로)은 **프로듀서와 컨슈머가 같은 코드베이스일 때만** 안전합니다.
> 서비스가 분리되는 순간 이건 "패키지 이름을 공개 API 로 만드는" 결정입니다.
> 그리고 이 결정은 첫 메시지를 보내는 순간 확정되며, **나중에 바꿔도 과거 메시지에는 소급되지 않습니다.**
> `project/application.yml` 의 `spring.json.add.type.headers: true` 를 지금 `false` 로 바꾸고 다음 스텝으로 가세요.

---

## 4-5. `spring.json.trusted.packages` — 왜 이런 게 있는가

`JsonDeserializer` 는 `__TypeId__` 헤더에 적힌 클래스 이름으로 `Class.forName()` 을 하고, 그 클래스의 인스턴스를 만듭니다.
여기서 잠깐 생각해 봅시다. **그 헤더는 누가 썼습니까?**

토픽에 쓸 권한이 있는 사람이라면 누구든 `__TypeId__` 에 **아무 클래스 이름이나** 넣을 수 있습니다.
클래스패스에 있는 위험한 클래스(파일을 열거나 프로세스를 실행하는 생성자를 가진 것)를 지목하면 컨슈머가 그것을 만들어 줍니다.
Java 역직렬화 취약점의 고전적 패턴이고, Jackson 의 polymorphic typing CVE 들이 전부 이 계열입니다. **원격 코드 실행(RCE)** 으로 이어질 수 있습니다.
그래서 `JsonDeserializer` 는 기본적으로 **아무 패키지도 신뢰하지 않습니다**(정확히는 `java.util`, `java.lang` 만). 설정을 지우고 실행해 봅니다.

**결과**
```
ERROR 14118 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer     : Consumer exception

Caused by: org.apache.kafka.common.errors.RecordDeserializationException: Error deserializing key/value for partition s04-orders-0 at offset 0.
Caused by: java.lang.IllegalArgumentException: The class 'com.example.order.domain.OrderCreated' is not in the trusted packages: [java.util, java.lang]. If you believe this class is safe to deserialize, please provide its name. If the serialization is only done by a trusted source, you can also enable trust all (*).
	at org.springframework.kafka.support.mapping.DefaultJackson2JavaTypeMapper.getClassIdType(DefaultJackson2JavaTypeMapper.java:134) ~[spring-kafka-3.1.4.jar:3.1.4]
```

예외 메시지가 친절하게 `(*)` 를 알려 주는 것이 문제입니다. 검색해서 이 줄을 만난 개발자는 대개 이렇게 합니다.

```yaml
spring.json.trusted.packages: "*"     # ⚠️ 절대 하지 마세요
```

> ⚠️ **함정 — `trusted.packages: "*"` 는 "이 토픽에 쓸 수 있는 모두에게 내 JVM 을 맡긴다"는 뜻**
> 동작은 합니다. 에러도 사라집니다. 그래서 이 설정이 운영에 그대로 나갑니다.
> `*` 는 클래스패스의 **모든 클래스**를 역직렬화 대상으로 허용합니다.
> 여기에 Jackson 의 `enableDefaultTyping` 계열 gadget 이 하나라도 걸리면 **메시지 한 건으로 임의 코드가 실행**됩니다.
> **해결책은 정확한 패키지를 나열하는 것입니다.** 어차피 이벤트 클래스는 한두 패키지에 모여 있습니다.
> ```yaml
> spring.json.trusted.packages: "com.example.order.domain,com.example.common.event"
> ```
> 더 나은 해결책은 **4-4 의 ①번** 입니다. `spring.json.value.default.type` 으로 타입을 고정하면
> 헤더의 클래스 이름을 아예 안 읽으므로, **신뢰 문제 자체가 사라집니다.**

| 설정 | 헤더의 클래스 이름을 읽는가 | 보안 |
|---|---|---|
| `trusted.packages` 미설정 | 읽고 검사 → `java.util`,`java.lang` 외 거부 | 안전하지만 안 돌아감 |
| `trusted.packages: "com.example.order.domain"` | 읽고 검사 → 해당 패키지만 허용 | **안전** |
| `trusted.packages: "*"` | 읽고 **검사 안 함** | ⚠️ **위험** |
| `use.type.headers: false` + `value.default.type` | **안 읽음** | **가장 안전 (권장)** |

---

## 4-6. ⚠️ 포이즌 필 — 역직렬화 실패 하나가 파티션을 영원히 멈춘다

여기가 이 스텝의 핵심입니다. **정상 메시지 10건 사이에 깨진 메시지 1건을 손으로 심습니다.**

먼저 정상 메시지 10건을 보내 잘 처리되는 것을 확인합니다. 그다음 앱을 끄고,
**콘솔 프로듀서로 JSON 이 아닌 문자열을 하나 넣습니다.**

```bash
kcp --topic s04-orders --property parse.key=true --property key.separator=:
>ORD-BAD:not-a-json
>^D
```

`ORD-BAD` 라는 키는 파티션 1 로 갑니다. 그 뒤에 정상 메시지 5건을 더 보내고, 앱을 다시 켭니다.

**결과**
```
INFO  14118 --- [           main] c.e.o.OrderServiceApplication             : The following 1 profile is active: "step04"
INFO  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : s04-inventory: partitions assigned: [s04-orders-1]
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=0 ORD-0001 SKU-002 x2
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=1 ORD-0004 SKU-002 x5
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=2 ORD-0007 SKU-002 x3
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Consumer exception

java.lang.IllegalStateException: This error handler cannot process 'SerializationException's directly; please consider configuring an 'ErrorHandlingDeserializer' in the value and/or key deserializer
	at org.springframework.kafka.listener.DefaultErrorHandler.handleOtherException(DefaultErrorHandler.java:151) ~[spring-kafka-3.1.4.jar:3.1.4]
	at org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer.handleConsumerException(KafkaMessageListenerContainer.java:1966) ~[spring-kafka-3.1.4.jar:3.1.4]
Caused by: org.apache.kafka.common.errors.RecordDeserializationException: Error deserializing key/value for partition s04-orders-1 at offset 3. If needed, please seek past the record to continue consumption.
	at org.apache.kafka.clients.consumer.internals.CompletedFetch.parseRecord(CompletedFetch.java:331) ~[kafka-clients-3.6.1.jar:na]
Caused by: org.apache.kafka.common.errors.SerializationException: Can't deserialize data [[110, 111, 116, 45, 97, 45, 106, 115, 111, 110]] from topic [s04-orders]
	at org.springframework.kafka.support.serializer.JsonDeserializer.deserialize(JsonDeserializer.java:604) ~[spring-kafka-3.1.4.jar:3.1.4]
Caused by: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
 at [Source: (byte[])"not-a-json"; line: 1, column: 4]
```

`[110, 111, 116, 45, 97, 45, 106, 115, 111, 110]` 은 `not-a-json` 의 ASCII 코드입니다.

**그리고 여기서 컨슈머는 죽지 않습니다. 더 나쁩니다. 무한 루프에 들어갑니다.**

```
WARN  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Seek to current after exception; nested exception is org.apache.kafka.common.errors.RecordDeserializationException: Error deserializing key/value for partition s04-orders-1 at offset 3
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Consumer exception
WARN  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Seek to current after exception; nested exception is org.apache.kafka.common.errors.RecordDeserializationException: Error deserializing key/value for partition s04-orders-1 at offset 3
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Consumer exception
WARN  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : Seek to current after exception; nested exception is org.apache.kafka.common.errors.RecordDeserializationException: Error deserializing key/value for partition s04-orders-1 at offset 3
```

**`at offset 3` 이 계속 반복됩니다.** 초당 수십~수백 번씩 같은 스택트레이스가 찍힙니다.

무슨 일이 벌어지는지 정리하면 이렇습니다. ① `poll()` 이 배치를 가져오면서 offset 3 을 역직렬화하다 실패 →
`RecordDeserializationException`. ② **리스너 메서드는 호출조차 안 됐습니다.** 예외가 `poll()` 안에서 났기 때문입니다.
③ 컨테이너는 `seekToCurrent` 로 offset 3 으로 되감습니다. ④ 다시 `poll()` → 또 offset 3 → 또 실패 → ①로.

**오프셋 3 은 영원히 커밋되지 않습니다.** 그리고 오프셋은 순차적이므로, **offset 4, 5, 6… 도 영원히 처리되지 않습니다.**
정상적인 메시지들인데 앞에 놓인 시체 하나 때문에 통과를 못 합니다. 이것을 **포이즌 필(poison pill)** 이라고 부릅니다.

### 랙이 늘어나는 것을 확인합니다

`kcg --describe --group s04-inventory` 를 **앱 기동 5분 후**에 찍습니다. 그동안 프로듀서는 계속 메시지를 보냈습니다.
```
GROUP          TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                    HOST         CLIENT-ID
s04-inventory  s04-orders  0          312             312             0    consumer-s04-inventory-1-8f2a  /172.19.0.1  consumer-s04-inventory-1
s04-inventory  s04-orders  1          3               308             305  consumer-s04-inventory-2-c41b  /172.19.0.1  consumer-s04-inventory-2
s04-inventory  s04-orders  2          3               319             316  consumer-s04-inventory-3-1de9  /172.19.0.1  consumer-s04-inventory-3
```

| 파티션 | LAG | 상태 |
|---|---:|---|
| `s04-orders-0` | 0 | 정상 |
| `s04-orders-1` | **305** | ⚠️ **offset 3 에 고착.** CURRENT-OFFSET 이 3 에서 안 움직임 |
| `s04-orders-2` | **316** | ⚠️ 파티션 1 의 예외 루프에 휩쓸림 |

파티션 2 도 멈춘 것에 주목하세요. `concurrency: 3` 이라 컨슈머 스레드가 3개지만,
**파티션 1 담당 스레드가 예외 루프에 갇혀 CPU 를 태우면서 로그를 쏟아내면** 같은 프로세스의 다른 스레드도 GC 압박과 로그 I/O 로 함께 느려집니다.
`concurrency: 1` 이었다면 세 파티션이 **전부** 한 스레드에 묶여 있으므로 **토픽 전체가 완전히 정지**합니다.

> ⚠️ **함정 — 재시작해도 똑같이 죽는다. 이게 진짜 무서운 점.**
> 대부분의 장애는 "일단 재시작"으로 시간을 벌 수 있습니다. **포이즌 필은 안 됩니다.**
> 커밋된 오프셋이 3 이므로, 재시작하면 컨슈머는 **다시 offset 3 부터** 읽고 **같은 자리에서 같은 이유로** 죽습니다.
> 새 컨슈머 그룹으로 바꿔도 `auto-offset-reset: earliest` 면 결국 그 레코드를 만납니다.
> 유일한 응급 조치는 **오프셋을 강제로 그 레코드 너머로 옮기는 것**입니다.
> ```bash
> # 앱을 끄고, 파티션 1 의 오프셋을 4 로 강제 이동 (offset 3 을 건너뜀)
> kcg --group s04-inventory --topic s04-orders:1 --reset-offsets --to-offset 4 --execute
> ```
> **결과**
> ```
> GROUP          TOPIC       PARTITION  NEW-OFFSET
> s04-inventory  s04-orders  1          4
> ```
> 그런데 이건 **그 메시지를 영구히 버리는 것**이고, 새벽 3시에 손으로 해야 하고,
> 깨진 메시지가 여러 개면 하나씩 반복해야 합니다. **애초에 이 상황이 오면 안 됩니다.**

---

## 4-7. `ErrorHandlingDeserializer` 로 방어한다

해법은 놀랄 만큼 간단합니다. **역직렬화기를 한 겹 감싸는 것**입니다.
`ErrorHandlingDeserializer` 는 실제 역직렬화기(delegate)를 품고 있다가, delegate 가 예외를 던지면
**예외를 밖으로 던지지 않고 삼킵니다.** 대신 ① payload 를 **`null`** 로 만들어 정상 레코드처럼 반환하고,
② 원래 예외를 **레코드 헤더**(`springDeserializerExceptionValue`)에 담습니다.

`poll()` 은 성공하고, 레코드는 컨테이너까지 무사히 도착합니다. 컨테이너는 헤더에서 예외를 발견하고
**`DefaultErrorHandler` 로 넘깁니다.** 이제 이건 "역직렬화 사고"가 아니라 **처리 가능한 에러**가 됩니다.

```yaml
spring.kafka.consumer:
  key-deserializer:   org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
  value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
  properties:
    spring.deserializer.key.delegate.class:   org.apache.kafka.common.serialization.StringDeserializer
    spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
    spring.json.value.default.type: com.example.order.domain.OrderCreated
    spring.json.trusted.packages: "com.example.order.domain"
```

**핵심은 `key-deserializer` 와 `value-deserializer` 자리에 `ErrorHandlingDeserializer` 를 두고,
진짜 역직렬화기는 `spring.deserializer.*.delegate.class` 로 내려보낸다**는 것입니다. 대응하는 상수는 이렇습니다.

| 상수 | 프로퍼티 문자열 |
|---|---|
| `ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS` | `spring.deserializer.key.delegate.class` |
| `ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS` | `spring.deserializer.value.delegate.class` |
| `SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER` | `springDeserializerExceptionKey` |
| `SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER` | `springDeserializerExceptionValue` |

### 같은 시나리오를 다시 돌립니다

오프셋을 0 으로 리셋하고, `ErrorHandlingDeserializer` 를 켠 채 실행합니다.

**결과**
```
INFO  14118 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer     : s04-inventory: partitions assigned: [s04-orders-1]
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=0 ORD-0001 SKU-002 x2
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=1 ORD-0004 SKU-002 x5
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=2 ORD-0007 SKU-002 x3
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler               : Backoff none exhausted for s04-orders-1@3

org.springframework.kafka.listener.ListenerExecutionFailedException: Listener failed
	at org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer.decorateException(KafkaMessageListenerContainer.java:2846) ~[spring-kafka-3.1.4.jar:3.1.4]
Caused by: org.springframework.kafka.support.serializer.DeserializationException: failed to deserialize; nested exception is org.apache.kafka.common.errors.SerializationException: Can't deserialize data [[110, 111, 116, 45, 97, 45, 106, 115, 111, 110]] from topic [s04-orders]
	at org.springframework.kafka.support.serializer.SerializationUtils.deserializationException(SerializationUtils.java:161) ~[spring-kafka-3.1.4.jar:3.1.4]
Caused by: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')

INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=4 ORD-0010 SKU-002 x1
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=5 ORD-0013 SKU-002 x4
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=6 ORD-0016 SKU-002 x2
```

**`off=3` 은 ERROR 한 줄로 지나가고, `off=4` 부터 정상 처리가 재개되었습니다.**
에러 로그가 **한 번만** 나온 것에 주목하세요. 4-6 에서는 같은 줄이 초당 수백 번 찍혔습니다.

**`kcg --describe --group s04-inventory` 결과**
```
GROUP          TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                    HOST         CLIENT-ID
s04-inventory  s04-orders  0          312             312             0    consumer-s04-inventory-1-8f2a  /172.19.0.1  consumer-s04-inventory-1
s04-inventory  s04-orders  1          308             308             0    consumer-s04-inventory-2-c41b  /172.19.0.1  consumer-s04-inventory-2
s04-inventory  s04-orders  2          319             319             0    consumer-s04-inventory-3-1de9  /172.19.0.1  consumer-s04-inventory-3
```

**LAG 305 → 0.** 나쁜 메시지 1건만 버려졌고 나머지 307건은 전부 처리되었습니다.

> 💡 **`DeserializationException` 은 재시도되지 않습니다 — 그게 맞습니다.**
> 로그의 `Backoff none exhausted` 를 보세요. `DefaultErrorHandler` 는 `DeserializationException`,
> `MessageConversionException`, `ClassCastException`, `MethodArgumentResolutionException` 등을
> **기본 non-retryable 목록**에 넣어 두고 재시도 없이 곧장 recoverer 로 보냅니다.
> 당연합니다 — **깨진 바이트는 100번 다시 읽어도 똑같이 깨져 있습니다.** 재시도는 시간 낭비입니다.

### 원본 바이트를 꺼내 본다

`ConsumerRecord` 로 직접 받으면 헤더에 실려 온 예외를 꺼낼 수 있습니다.

```java
@KafkaListener(topics = "s04-orders", groupId = "s04-inventory")
public void onMessage(ConsumerRecord<String, OrderCreated> record) {
    DeserializationException ex = SerializationUtils.getExceptionFromHeader(
            record, SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, new LogAccessor(log));
    if (ex != null) {
        log.error("역직렬화 실패 p={} off={} key={} raw={}", record.partition(), record.offset(),
                record.key(), new String(ex.getData(), StandardCharsets.UTF_8));
        return;   // 건너뛴다 → 오프셋은 정상 커밋됨
    }
    // 정상 처리
}
```

**결과**
```
ERROR 14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : 역직렬화 실패 p=1 off=3 key=ORD-BAD raw=not-a-json
```

`ex.getData()` 로 **원본 바이트를 그대로 꺼낼 수 있습니다.** 무엇이 잘못 들어왔는지 눈으로 보는 유일한 방법입니다.

---

## 4-8. 실패한 레코드를 잃지 않기

4-7 에서 나쁜 메시지는 **로그만 남기고 사라졌습니다.** 학습 환경에선 괜찮지만, "어떤 시스템이 깨진 메시지를 보냈는가"를
나중에 조사하려면 원본이 남아 있어야 합니다. 해답은 `ErrorHandlingDeserializer` + `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 조합입니다.

```java
// 실패 레코드를 <원본토픽>.DLT 로 발행. 원본 바이트·예외·토픽·파티션·오프셋이 헤더로 붙는다
new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(0L, 0L));
```

**결과**
```
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler               : Backoff none exhausted for s04-orders-1@3
INFO  14118 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer     : Republishing failed record to s04-orders.DLT-1
INFO  14118 --- [ntainer#0-1-C-1] c.e.o.step04.Step04Listener              : [s04-inventory] p=1 off=4 ORD-0010 SKU-002 x1
```

깨진 바이트가 `s04-orders.DLT` 에 그대로 보존되고, 본선은 계속 흐릅니다.

| 구성 요소 | 역할 |
|---|---|
| `ErrorHandlingDeserializer` | 역직렬화 예외를 **잡아서 헤더로 옮긴다** (파티션 정지 방지) |
| `DefaultErrorHandler` | 헤더의 예외를 보고 **재시도 여부를 판단**한다 (역직렬화 예외는 재시도 없음) |
| `DeadLetterPublishingRecoverer` | 포기한 레코드를 **DLT 토픽으로 발행**한다 (원본 보존) |

> 💡 세 개는 **세트로 다닙니다.** 하나만 빠져도 구멍이 생깁니다.
> `ErrorHandlingDeserializer` 없이 `DeadLetterPublishingRecoverer` 만 달면 4-6 의 무한 루프를 못 막습니다.
> 역직렬화 예외는 에러 핸들러에 **도달하지도 못하기** 때문입니다.
> `DefaultErrorHandler` 의 백오프·재시도 정책, DLT 토픽 명명 규칙, `@DltHandler` 는 [Step 07](../step-07-error-handling/) 에서 본격적으로 다룹니다.

---

## 4-9. 스키마 진화 — 필드를 추가하면 무슨 일이 생기는가

이벤트는 반드시 바뀝니다. `OrderCreated` 에 `couponCode` 를 추가한다고 합시다.
**구버전 컨슈머가 신버전 메시지를 읽을 때** 무슨 일이 생기는지가 관건입니다.

| 변경 | 신버전 → 구버전 컨슈머 | 구버전 → 신버전 컨슈머 | 안전한가 |
|---|---|---|---|
| **필드 추가** (`couponCode`) | `UnrecognizedPropertyException` (기본 설정) | 새 필드가 `null` / `0` | 설정하면 **안전** |
| **필드 삭제** (`sku` 제거) | 문제없음 (안 읽음) | 해당 필드 `null`. NPE 위험 | ⚠️ **위험** |
| **필드 이름 변경** | 추가+삭제와 동일 | 양쪽 다 깨짐 | ⚠️ **하지 말 것** |
| **타입 확대/축소** (`int` ↔ `long`) | 범위를 넘으면 실패 | 동일 | 조건부 |
| **타입 변경** (`int` → `String`) | `MismatchedInputException` | 동일 | ⚠️ **하지 말 것** |
| **필드 순서 변경** | 문제없음 | 문제없음 | 안전 (JSON 은 순서 무관) |

**필드 추가**의 기본 동작을 확인합니다. 구버전 record 로 신버전 JSON 을 읽으면:

**결과**
```
ERROR 14118 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler               : Backoff none exhausted for s04-orders-1@7

Caused by: com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException: Unrecognized field "couponCode" (class com.example.order.domain.OrderCreated), not marked as ignorable (6 known properties: "orderId", "customerId", "sku", "quantity", "amount", "createdAt"])
 at [Source: (byte[])"{"orderId":"ORD-0021",...,"couponCode":"WELCOME10"}"; line: 1, column: 141]
```

**필드 하나 추가했다고 컨슈머가 전부 멈춥니다.** 프로듀서 팀은 "그냥 필드 하나 넣었을 뿐"이라고 생각합니다.

해결은 세 가지 중 하나입니다. 이벤트 DTO 라면 **③ 을 권합니다.** mapper 설정에 의존하지 않기 때문입니다.

```java
// ① 전역 (yml) : spring.jackson.deserialization.fail-on-unknown-properties: false
// ② mapper 단위 — JsonDeserializer 에 직접 mapper 를 넘길 때
mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
// ③ 클래스 단위 — 누가 mapper 를 바꿔도 이 클래스만은 안전
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreated(...) { }
```

> ⚠️ **함정 — Spring Boot 는 이걸 꺼 주지만, `new ObjectMapper()` 는 켜져 있다**
> Boot 의 `JacksonAutoConfiguration` 은 `FAIL_ON_UNKNOWN_PROPERTIES` 를 **false 로 바꿔 줍니다.**
> 그래서 Boot 의 `ObjectMapper` 빈을 주입해 쓰면 필드 추가가 안전합니다.
> 그런데 `JsonDeserializer` 가 **자체 mapper 를 만들 때**(주입 없이 기본 생성자를 쓸 때)는
> `JacksonUtils.enhancedObjectMapper()` 를 쓰는데, 이것도 `FAIL_ON_UNKNOWN_PROPERTIES` 를 끕니다.
> **문제는 여러분이 직접 `new ObjectMapper()` 를 만들어 넘길 때입니다.** 그 순간 기본값(true)으로 돌아가고,
> 3개월 뒤 프로듀서가 필드를 하나 추가하는 날 전 컨슈머가 멈춥니다.
> **명시적으로 끄고, 테스트로 못박으세요.**

**필드 삭제**는 반대 방향이라 더 조용히 위험합니다. 프로듀서가 `sku` 를 빼면 구버전 컨슈머의 `record.sku()` 가 `null` 이 되고
`sku.startsWith("SKU-")` 에서 **NPE 가 터집니다.** 이건 역직렬화 예외가 아니라 **비즈니스 로직 예외**라 `DefaultErrorHandler` 가 재시도합니다 —
그리고 100번 재시도해도 계속 null 입니다. **필드는 삭제하지 말고 deprecated 로 두세요.**

> 💡 JSON 에는 스키마 강제가 없어서 이런 사고가 **런타임에** 터집니다. Avro + Schema Registry 는 호환성 규칙을 앞단에서 검사해 "구버전 컨슈머가 읽을 수 없는 스키마"의 등록 자체를 거부합니다.
> 호환성 모드(`BACKWARD`/`FORWARD`/`FULL`)는 [Kafka 코스 Step 10](../../kafka/step-10-serialization/) 에서 다룹니다.

---

## 4-10. record 역직렬화 주의점

이 코스는 `OrderCreated` 를 **Java record** 로 씁니다. Jackson 은 **2.12 부터** record 를 정식 지원하고
Spring Boot 3.2.5 는 Jackson 2.15.4 를 가져오므로 문제없습니다. record 는 컴포넌트 이름이 클래스 파일에 항상 보존되므로 `-parameters` 옵션도 필요 없습니다.

**문제는 record 가 아니라, 기본 생성자도 `@JsonCreator` 도 없는 일반 클래스입니다.**

**결과**
```
Caused by: com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `com.example.order.step04.OrderCreatedV1` (no Creators, like default constructor, exist): cannot deserialize from Object value (no delegate- or property-based Creator)
 at [Source: (byte[])"{"orderId":"ORD-0001","customerId":1001}"; line: 1, column: 2]
	at org.springframework.kafka.support.serializer.JsonDeserializer.deserialize(JsonDeserializer.java:604) ~[spring-kafka-3.1.4.jar:3.1.4]
```

`no Creators, like default constructor, exist`. Jackson 이 객체를 만들 방법을 못 찾은 것입니다.

| 상황 | 동작 | 대응 |
|---|---|---|
| `record` (Java 16+) | **그냥 됨** | 없음 |
| 기본 생성자 + setter 가진 클래스 | 그냥 됨 | 없음 (불변성은 포기) |
| 파라미터 생성자만, `-parameters` 있음 | 됨 (Boot Gradle 플러그인이 기본 적용) | 없음 |
| 파라미터 생성자만, `-parameters` 없음 | ⚠️ **`no Creators` 실패** | `@JsonCreator` + `@JsonProperty` 명시 |
| Lombok `@Value` / `@Builder` | ⚠️ 상황에 따라 실패 | `@Jacksonized` 추가 |

> ⚠️ **함정 — record 안에 컴팩트 생성자로 검증을 넣으면 역직렬화 중에 터진다**
> ```java
> public record OrderCreated(String orderId, ..., int quantity, ...) {
>     public OrderCreated { if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive"); }
> }
> ```
> 좋은 습관처럼 보이지만, 이 검증은 **역직렬화 시에도 실행**됩니다.
> 과거에 `quantity=0` 인 메시지가 하나라도 토픽에 들어갔다면, 그 순간 **`ValueInstantiationException` 으로 4-6 과 똑같은 상황**이 됩니다.
> 그리고 이건 `ErrorHandlingDeserializer` 로 잡히긴 하지만, **정상 데이터인데 검증 규칙이 나중에 강화된 경우**라면
> 멀쩡한 메시지를 통째로 DLT 로 버리게 됩니다.
> **이벤트 DTO 에는 검증을 넣지 말고, 도메인 객체로 변환할 때 검증하세요.**

---

## 정리

| 개념 | 핵심 |
|---|---|
| 브로커 | `byte[]` 만 안다. 직렬화는 **전적으로 클라이언트 책임** |
| 직렬화 시점 | 프로듀서는 `send()` 호출 스레드에서 **즉시**, 컨슈머는 `poll()` 안에서 |
| `JsonSerializer` | Jackson `ObjectMapper` 사용. Boot mapper 를 **주입**하는 게 안전 |
| `JavaTimeModule` | 없으면 `Instant` 가 `{"epochSecond":...}` 로 나감. **예외 없이 조용히** |
| `__TypeId__` | 값 클래스의 **FQCN 이 헤더에 박힌다**. 컨슈머가 역직렬화 대상 클래스를 정하는 근거 |
| ⚠️ 패키지 결합 | 컨슈머가 클래스를 옮기면 `ClassNotFoundException` 으로 **전 메시지 실패** |
| ⚠️ 되돌릴 수 없음 | **쌓인 메시지의 헤더는 수정 불가.** 리텐션 기간 내내 옛 이름을 주장 |
| 해결책 (권장) | `spring.json.add.type.headers=false` + `spring.json.value.default.type` |
| 해결책 (다중 타입) | `spring.json.type.mapping=order:<FQCN>` 로 **논리 이름** 부여 |
| `trusted.packages` | 임의 클래스 역직렬화 = **RCE 위험**. `*` 는 금지, 패키지를 나열할 것 |
| ⚠️ **포이즌 필** | 역직렬화 실패 1건 → `seekToCurrent` 무한 루프 → **그 파티션 영구 정지** |
| 포이즌 필의 증상 | 같은 `at offset N` 이 초당 수백 번. **재시작해도 같은 자리에서 죽음.** 응급 조치는 `kcg --reset-offsets --to-offset N+1` (= 메시지 버림) |
| `ErrorHandlingDeserializer` | 예외를 **헤더로 옮기고** payload 를 null 로. `spring.deserializer.value.delegate.class`. `DeserializationException` 은 **non-retryable** |
| 3종 세트 | `ErrorHandlingDeserializer` + `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` |
| 스키마 진화 | 필드 **추가**는 `FAIL_ON_UNKNOWN_PROPERTIES=false` 로 안전. **삭제·이름변경은 금지** |
| record | Jackson 2.12+ 지원. 일반 클래스는 `no Creators` 주의. **DTO 에 검증 넣지 말 것** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.
**콘솔 컨슈머와 `kcg --describe` 를 띄워 놓고** 푸세요. 로그만으로는 절반밖에 안 보입니다.

1. 타입 헤더를 끄고 `value.default.type` 만으로 역직렬화가 되게 만들기
2. `spring.json.type.mapping` 으로 `order` 라는 논리 이름을 부여하고, 컨슈머 쪽 클래스를 다른 패키지로 옮겨도 동작시키기
3. 콘솔 프로듀서로 깨진 JSON 을 넣어 **포이즌 필을 재현**하고, `kcg --describe` 로 LAG 고착을 확인하기
4. `ErrorHandlingDeserializer` 를 적용해 3번 상황에서 나쁜 메시지 하나만 건너뛰게 만들기
5. `trusted.packages` 위반을 재현하고, `*` 를 쓰지 않고 해결하기
6. 필드가 추가된 신버전 이벤트를 구버전 컨슈머가 읽게 만들기 (`FAIL_ON_UNKNOWN_PROPERTIES`)

---

## 다음 단계

직렬화를 통과한 바이트가 이제 객체가 되었습니다. 그런데 `@KafkaListener` 메서드는 그 객체 말고도
**키·파티션·오프셋·타임스탬프·커스텀 헤더**를 받을 수 있습니다. 그 변환을 담당하는 것이 `MessageConverter` 입니다.
다음 스텝에서는 `@Payload`/`@Header`/`@Headers` 로 무엇을 꺼내는지, 그리고 추적 ID 를 프로듀서에서 컨슈머까지 헤더로 전파하는 방법을 만듭니다.

→ [Step 05 — 메시지 변환과 헤더](../step-05-message-conversion/)

---

## 실습 파일

세 파일을 순서대로 씁니다. 먼저 `Practice.java` 를 프로필 `step04` 로 띄워 4-2 ~ 4-10 의 모든 현상을 재현하고, 그다음 `Exercise.java` 의 6문제를 직접 채운 뒤 `Solution.java` 로 정답과 근거를 대조합니다.
세 파일 모두 `src/main/java/com/example/order/step04/` 에 놓고, **토픽은 `s04-orders`, 그룹은 `s04-inventory`** 를 공유합니다.

### Practice.java

교재 본문의 모든 예제를 절 번호 주석(`// [4-6] 포이즌 필 재현`)과 함께 담은 단일 실행 파일입니다.

- 파일 상단 주석에 **실행 시나리오 A~D** 가 적혀 있습니다. A(정상) → B(포이즌 필) → C(ErrorHandlingDeserializer 복구) → D(타입 매핑) 순서로, 각 시나리오마다 `--args` 의 프로필 조합이 다릅니다. **순서를 지키세요.** B 를 건너뛰고 C 로 가면 "고쳤다"는 실감이 없습니다.
- `[4-2]` 의 `BrokenMapperConfig` 는 일부러 `new ObjectMapper()` 를 넘겨 `Instant` 가 `{"epochSecond":...}` 로 나가게 만듭니다. `step04-broken-json` 프로필일 때만 켜지므로 평소에는 영향이 없습니다.
- `[4-6]` 의 `PoisonPillProducer` 는 **`byte[]` 직렬화기를 쓰는 별도 `KafkaTemplate`** 으로 `not-a-json` 을 직접 보냅니다. 콘솔 프로듀서를 쓰는 것과 동일한 효과이며, 실습을 자동화하려고 이렇게 했습니다. ⚠️ 이 빈은 `step04-poison` 프로필 전용입니다. 실수로 켜지 마세요.
- `[4-7]` 의 `SafeConsumerConfig` 가 이 스텝의 정답 설정입니다. `ErrorHandlingDeserializer` 를 키·값 양쪽에 두고 delegate 를 내려보내는 5줄이 핵심이고, 나머지는 전부 부수적입니다.
- `[4-7]` 의 `Step04SafeListener` 는 `ConsumerRecord` 로 직접 받아 `SerializationUtils.getExceptionFromHeader` 로 예외를 꺼냅니다. `ex.getData()` 가 **원본 바이트**이므로, 무엇이 잘못 들어왔는지 확인하는 데 이 3줄이 가장 유용합니다.
- `[4-9]`/`[4-10]` 의 `OrderCreatedV2`, `LegacyOrder` 는 nested record/class 로 정의돼 있습니다. 스키마 진화와 `no Creators` 를 **한 프로세스 안에서** 재현하기 위한 것이라 실제 도메인에는 두지 마세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 요구사항과 `// 여기에 작성:` 자리로 구성돼 있습니다.

- **문제 1·2·5·6** 은 **설정을 채우는** 문제입니다(`Map<String, Object>` 에 프로퍼티 키/값을 넣거나 `application.yml` 조각을 쓰는 형태). 프로퍼티 **문자열 상수 이름**을 외우는 것이 목적이므로, IDE 자동완성에 의존하지 말고 `JsonSerializer` / `JsonDeserializer` / `ErrorHandlingDeserializer` 의 상수를 직접 찾아보세요.
- **문제 3** 은 코드가 아니라 **재현 절차**를 완성하는 문제입니다. 메서드 본문에 CLI 명령과 관찰 결과를 주석으로 적게 되어 있고, 실제로 터미널에서 돌려 본 사람만 채울 수 있습니다. `kcg --describe` 를 **두 번**(직후, 5분 뒤) 찍어 LAG 변화를 기록하세요.
- **문제 4** 는 문제 3 에서 만든 고장난 상태를 **고치는** 문제입니다. 문제 3 을 건너뛰면 4번의 "before/after" 비교가 성립하지 않습니다.
- 문제 5 는 일부러 `trusted.packages` 를 **비운 상태**로 시작합니다. 실행하면 `IllegalArgumentException` 이 나는 것이 정상이고, 그 예외 메시지에서 유혹적인 `(*)` 안내를 직접 읽는 것이 이 문제의 목적입니다.
- 모든 문제는 뼈대만으로도 **컴파일이 됩니다.** 컴파일 에러가 나면 채운 코드가 잘못된 것입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 블록 주석이 붙어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 프로듀서에 `JsonSerializer.ADD_TYPE_INFO_HEADERS=false`, 컨슈머에 `JsonDeserializer.VALUE_DEFAULT_TYPE` 을 넣습니다. 주석은 "왜 컨슈머 쪽 `USE_TYPE_INFO_HEADERS=false` 도 함께 넣는 게 안전한가"를 설명합니다 — **과거에 헤더가 붙어 나간 메시지가 토픽에 남아 있기 때문**입니다. 이게 4-4 의 "되돌릴 수 없다"와 정확히 같은 이유입니다.
- **정답 2** 는 `spring.json.type.mapping` 을 **양쪽에 서로 다른 FQCN 으로** 설정합니다. 프로듀서는 `order:com.example.order.domain.OrderCreated`, 컨슈머는 `order:com.example.inventory.event.OrderCreated`. 주석의 핵심 문장은 "헤더에 나가는 것은 `order` 뿐이고, 그 세 글자만이 두 서비스의 계약이다" 입니다.
- **정답 3** 은 재현 절차와 **관찰된 LAG 표**를 담고 있습니다. `CURRENT-OFFSET` 이 3 에서 멈춰 있는데 `LOG-END-OFFSET` 만 올라가는 것이 포이즌 필의 지문입니다. 주석은 "왜 재시작이 소용없는가"를 오프셋 커밋 관점에서 3줄로 정리합니다.
- **정답 4** 의 설정은 4줄뿐이지만, 주석은 **왜 `key-deserializer` 에도 `ErrorHandlingDeserializer` 를 씌우는가**를 따로 설명합니다. 값이 아니라 **키**가 깨진 메시지도 똑같이 파티션을 멈추기 때문이며, 이걸 빼먹은 설정이 실무에서 가장 흔한 미완성 방어입니다.
- **정답 5** 는 `spring.json.trusted.packages: "com.example.order.domain"` 입니다. `*` 를 쓰지 않는 이유를 gadget chain 관점에서 설명하고, **더 나은 답은 애초에 헤더를 안 읽는 것**(정답 1의 구성)이라는 결론으로 정답 1 과 연결합니다.
- **정답 6** 은 `FAIL_ON_UNKNOWN_PROPERTIES=false` 를 세 가지 방법(`application.yml` / mapper 직접 설정 / `@JsonIgnoreProperties`)으로 제시하고, **Boot mapper 를 주입받으면 이미 꺼져 있다**는 사실과 **`new ObjectMapper()` 를 직접 만들면 다시 켜진다**는 함정을 대조합니다.

```java file="./Solution.java"
```
