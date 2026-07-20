# Step 01 — 환경 구축과 첫 메시지

> **학습 목표**
> - `spring-kafka` 의존성 한 줄이 `KafkaAutoConfiguration` 을 통해 등록하는 빈 6종을 직접 조회해 확인한다
> - `KafkaTemplate.send(topic, key, value)` 로 `orders` 토픽에 3건을 발행하고, 각 레코드가 어느 파티션·오프셋에 앉았는지 실측한다
> - `@KafkaListener` 로 수신하고, `partitions assigned` 로그가 나오기까지의 기동 순서를 한 줄씩 해독한다
> - `@EnableKafka` 가 왜 불필요한지를 `KafkaAnnotationDrivenConfiguration` 의 위임 구조로 설명한다
> - 발행한 메시지의 행방을 콘솔 컨슈머·컨슈머 그룹 랙·리스너 로그 세 가지 방법으로 교차 확인한다
> - **`auto-offset-reset` 과 토픽 자동 생성이 "에러 없이 조용히" 만드는 두 가지 사고를 재현한다**
>
> **선행 스텝**: [실습 프로젝트 셋업](../project/)
> **예상 소요**: 90분

---

## 1-0. 실습 준비

브로커가 살아 있는지부터 확인합니다. 여기서 막히면 뒤가 전부 막힙니다.

```bash
docker compose ps
```

**결과**
```
NAME                IMAGE                COMMAND                  STATUS                   PORTS
learn-kafka         apache/kafka:3.7.0   "/__cacert_entrypoin…"   Up 2 minutes (healthy)   0.0.0.0:9092->9092/tcp
learn-kafka-mysql   mysql:8.0            "docker-entrypoint.s…"   Up 2 minutes (healthy)   0.0.0.0:3307->3306/tcp
```

두 컨테이너 모두 `(healthy)` 여야 합니다. `(health: starting)` 이면 10초쯤 더 기다리세요.

이제 토픽 목록을 봅니다. [실습 프로젝트 셋업 P-8](../project/) 의 alias 를 등록했다고 가정합니다.

```bash
kt --list
```

**결과** (앱을 한 번도 안 띄운 상태)
```
__consumer_offsets
```

`__consumer_offsets` 하나뿐입니다. 이것은 Kafka 가 **컨슈머 그룹의 커밋된 오프셋을 저장하는 내부 토픽**이며, 브로커가 자기 필요로 만든 것입니다. `orders` 는 아직 없습니다. `KafkaTopicConfig` 의 `NewTopic` 빈이 만들 것이므로 지금 없는 게 정상입니다.

---

## 1-1. 의존성 하나가 하는 일

`build.gradle` 에서 Kafka 관련 줄은 딱 하나입니다.

```groovy
implementation 'org.springframework.kafka:spring-kafka'
```

이 한 줄이 켜는 것은 `KafkaAutoConfiguration` 입니다. 자동 설정이 도는 조건은 두 가지입니다.

| 조건 | 의미 |
|---|---|
| `@ConditionalOnClass(KafkaTemplate.class)` | 클래스패스에 spring-kafka 가 있으면 |
| `@EnableConfigurationProperties(KafkaProperties.class)` | `spring.kafka.*` 를 `KafkaProperties` 로 바인딩 |

그 결과 컨텍스트에 아래 빈들이 등록됩니다. **여러분이 한 줄도 쓰지 않았는데 생기는 것들**입니다.

| 빈 이름 | 타입 | 하는 일 | 등록 조건 |
|---|---|---|---|
| `kafkaTemplate` | `KafkaTemplate` | 발행 진입점. 내부적으로 `Producer` 를 빌려 씀 | 항상 |
| `kafkaProducerFactory` | `DefaultKafkaProducerFactory` | `Producer` 인스턴스를 만들고 캐싱 | 항상 |
| `kafkaConsumerFactory` | `DefaultKafkaConsumerFactory` | `Consumer` 인스턴스를 요청마다 새로 생성 | 항상 |
| `kafkaListenerContainerFactory` | `ConcurrentKafkaListenerContainerFactory` | `@KafkaListener` 하나당 컨테이너를 찍어내는 틀 | 항상 |
| `kafkaAdmin` | `KafkaAdmin` | 기동 시 `NewTopic` 빈들을 브로커에 생성 | 항상 |
| `kafkaListenerEndpointRegistry` | `KafkaListenerEndpointRegistry` | 만들어진 컨테이너들을 보관·시작·정지 | 항상 |
| `kafkaProducerListener` | `LoggingProducerListener` | 발행 실패를 ERROR 로그로 남김 | 항상 |
| `kafkaTransactionManager` | `KafkaTransactionManager` | Kafka 트랜잭션 | **`transaction-id-prefix` 가 있을 때만** |

마지막 줄이 중요합니다. **조건이 안 맞으면 자동 설정은 예외를 던지지 않고 그냥 빈을 안 만듭니다.** `@Autowired KafkaTransactionManager` 를 썼는데 `NoSuchBeanDefinitionException` 이 나면, 버그가 아니라 **설정을 안 한 것**입니다. 1-4 에서 이 부재를 직접 눈으로 확인합니다.

> 💡 **`KafkaProducerFactory` 는 캐싱하고 `KafkaConsumerFactory` 는 캐싱하지 않습니다.**
> Kafka `Producer` 는 스레드 안전해서 하나를 공유하는 게 정석이고, `Consumer` 는 스레드 안전하지 않아 스레드마다 하나씩 필요합니다.
> 이 비대칭이 뒤 스텝의 `concurrency`(Step 03), 트랜잭션(Step 09) 설계를 전부 지배합니다.

---

## 1-2. 첫 발행

`KafkaTemplate` 을 주입받아 3건을 보냅니다.

```java
@Component
@Profile("step01")
public class FirstPublisher implements ApplicationRunner {

    private final KafkaTemplate<String, OrderCreated> kafkaTemplate;

    public FirstPublisher(KafkaTemplate<String, OrderCreated> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int seq = 1; seq <= 3; seq++) {
            OrderCreated event = OrderCreated.of(seq);
            kafkaTemplate.send("orders", event.orderId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) { log.error("발행 실패", ex); return; }
                    log.info("발행 성공 key={} -> {}-{}@{}",
                        event.orderId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                });
        }
        kafkaTemplate.flush();
    }
}
```

### 주입이 되는 이유

자동 설정이 만든 빈은 `KafkaTemplate<?, ?>` 로 선언되어 있습니다. 그런데 우리는 `KafkaTemplate<String, OrderCreated>` 로 받았습니다. 와일드카드는 어떤 구체 타입에도 대입 가능하므로 주입이 성립합니다.

**단, 제네릭은 컴파일 타임 장식일 뿐입니다.** 실제 직렬화기는 `application.yml` 의 `key-serializer`/`value-serializer` 가 정합니다. `KafkaTemplate<String, Integer>` 로 주입받아 `Integer` 를 보내도 컴파일은 통과하고, `JsonSerializer` 가 그것을 그냥 JSON 으로 직렬화해 버립니다. **타입 안전성은 여기까지입니다.** Step 04 에서 이 틈이 어떻게 사고로 이어지는지 봅니다.

### 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=step01'
```

**결과**
```
INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step01"
INFO 12841 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka version: 3.6.1
INFO 12841 --- [           main] c.e.o.s.Practice$FirstPublisher          : [1-2] orders 토픽으로 OrderCreated 3건 발행을 시작합니다
INFO 12841 --- [ad | producer-1] c.e.o.s.Practice$FirstPublisher          : [1-2] 발행 성공 key=ORD-0001 -> orders-1@0
INFO 12841 --- [ad | producer-1] c.e.o.s.Practice$FirstPublisher          : [1-2] 발행 성공 key=ORD-0002 -> orders-0@0
INFO 12841 --- [ad | producer-1] c.e.o.s.Practice$FirstPublisher          : [1-2] 발행 성공 key=ORD-0003 -> orders-2@0
INFO 12841 --- [           main] c.e.o.s.Practice$FirstPublisher          : [1-2] flush 완료
```

**교재의 파티션 번호와 여러분 화면이 같아야 합니다.** `ORD-0001` 은 `orders-1`, `ORD-0002` 는 `orders-0`, `ORD-0003` 은 `orders-2`. 키가 결정적이고 기본 파티셔너가 `murmur2(key) % 3` 이므로 누가 실행해도 같습니다. 다르다면 파티션 수가 3이 아닌 것입니다. `kt --describe --topic orders` 로 확인하세요.

### 스레드 이름을 보세요

발행 시작 로그는 `[main]`, 발행 성공 로그 세 줄은 `[ad | producer-1]` 입니다. 이 축약은 `kafka-producer-network-thread | producer-1` 이 잘린 것입니다.

즉 **`send()` 를 부른 스레드와 결과를 받는 스레드가 다릅니다.** `send()` 는 레코드를 버퍼에 넣고 즉시 돌아오고, 실제 전송과 응답 처리는 네트워크 스레드가 합니다. `whenComplete` 콜백도 그 스레드에서 실행됩니다.

여기서 두 가지가 따라옵니다.

- 콜백 안에서 무거운 작업을 하면 **프로듀서의 전송 스레드를 붙잡아** 전체 발행이 느려집니다.
- 콜백을 안 붙이면 실패해도 아무 일도 안 일어납니다. `kafkaProducerListener`(=`LoggingProducerListener`)가 ERROR 로그 하나는 남겨 주지만, **애플리케이션 코드는 실패를 모릅니다.**

> 💡 `flush()` 는 실습에서 로그 순서를 안정시키려고 넣었습니다. 운영 코드에서 매 건 `flush()` 하면 `linger.ms` 배치가 통째로 무력화됩니다. 이 비용을 Step 02 에서 숫자로 측정합니다.

### 콘솔 컨슈머로 확인

터미널을 하나 더 띄웁니다.

```bash
kcc --topic orders --from-beginning --property print.key=true --property print.partition=true
```

**결과**
```
Partition:1	ORD-0001	{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
Partition:0	ORD-0002	{"orderId":"ORD-0002","customerId":1002,"sku":"SKU-003","quantity":3,"amount":12000,"createdAt":"2025-01-01T00:02:00Z"}
Partition:2	ORD-0003	{"orderId":"ORD-0003","customerId":1003,"sku":"SKU-001","quantity":4,"amount":13000,"createdAt":"2025-01-01T00:03:00Z"}
```

파티션 번호가 발행 로그와 일치합니다. **애플리케이션 밖에서 확인했다는 점이 중요합니다.** 내 코드의 로그는 내 코드가 거짓말할 수 있지만, 콘솔 컨슈머는 브로커에 실제로 저장된 것만 보여 줍니다.

---

## 1-3. 첫 수신

```java
@Component
@Profile("step01")
public class InventoryListener {

    @KafkaListener(
            id = "s01-inventory-listener",
            topics = "${app.topic.orders}",
            groupId = "s01-inventory",
            concurrency = "1")
    public void onOrderCreated(ConsumerRecord<String, OrderCreated> record) {
        log.info("[1-3] 재고 차감 {}-{}@{} key={} sku={} qty={}",
                record.topic(), record.partition(), record.offset(),
                record.key(), record.value().sku(), record.value().quantity());
    }
}
```

`concurrency = "1"` 을 명시한 이유가 있습니다. `application.yml` 의 `listener.concurrency: 3` 을 그대로 쓰면 컨테이너 3개가 파티션을 하나씩 나눠 갖고, `partitions assigned` 로그가 세 줄로 쪼개집니다. Step 01 에서는 **한 줄에 세 파티션이 나열되는 모습**을 눈에 익히는 게 목적이므로 스레드를 하나로 묶었습니다. `concurrency` 와 파티션 수의 관계는 Step 03 의 주제입니다.

**결과**
```
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Discovered group coordinator 127.0.0.1:9092 (id: 2147483646 rack: null)
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] (Re-)joining group
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Successfully joined group with generation Generation{generationId=1, memberId='consumer-s01-inventory-1-9f2c1e77-...', protocol='range'}
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Notifying assignor about the new Assignment(partitions=[orders-0, orders-1, orders-2])
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Found no committed offset for partition orders-0
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.SubscriptionState            : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Resetting offset for partition orders-0 to position FetchPosition{offset=0, ...}
INFO 12841 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer     : s01-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO 12841 --- [ntainer#0-0-C-1] c.e.o.s.Practice$InventoryListener        : [1-3] 재고 차감 orders-0@0 key=ORD-0002 sku=SKU-003 qty=3
INFO 12841 --- [ntainer#0-0-C-1] c.e.o.s.Practice$InventoryListener        : [1-3] 재고 차감 orders-1@0 key=ORD-0001 sku=SKU-002 qty=2
INFO 12841 --- [ntainer#0-0-C-1] c.e.o.s.Practice$InventoryListener        : [1-3] 재고 차감 orders-2@0 key=ORD-0003 sku=SKU-001 qty=4
```

### `partitions assigned` 한 줄 해설

이 코스에서 가장 많이 보게 될 로그입니다. 앞의 다섯 줄이 여기까지 오는 과정입니다.

| 로그 | 의미 |
|---|---|
| `Discovered group coordinator` | 이 그룹을 관리할 브로커를 찾았다. 단일 노드라 자기 자신 |
| `(Re-)joining group` | 그룹 가입 요청을 보냈다 |
| `Successfully joined group with generation` | 가입 성공. `generationId=1` = 이 그룹의 1세대 |
| `Notifying assignor about the new Assignment` | 어떤 파티션을 맡을지 결정됐다 |
| `Found no committed offset for partition` | 이 그룹은 이 파티션을 **한 번도 커밋한 적이 없다** |
| `Resetting offset ... to position offset=0` | 그래서 `auto.offset.reset` 규칙을 적용해 0번부터 |
| `partitions assigned: [...]` | **Spring 컨테이너가 폴링을 시작한다** |

`generationId` 는 그룹의 멤버 구성이 바뀔 때마다 1씩 오릅니다. 이 숫자가 실습 중에 계속 올라간다면 리밸런스가 반복되고 있다는 뜻입니다.

주목할 것은 **`Found no committed offset` → `Resetting offset` 두 줄이 짝**이라는 점입니다. 이 두 줄이 나온다는 건 `auto.offset.reset` 이 **실제로 개입했다**는 뜻입니다. 커밋된 오프셋이 있으면 이 두 줄은 아예 안 나옵니다. 바로 다음 함정의 핵심입니다.

> ⚠️ **함정 — `auto-offset-reset` 기본값 `latest` 때문에 "앱을 켜기 전에 보낸 메시지"는 영원히 안 보인다**
>
> Kafka 클라이언트의 `auto.offset.reset` 기본값은 **`latest`** 입니다. 우리 `application.yml` 이 `earliest` 로 덮어썼기 때문에 위 실습이 동작한 것입니다.
>
> **증상.** `latest` 인 채로 새 컨슈머 그룹을 띄우면, 컨슈머는 "붙은 순간의 마지막 오프셋"부터 읽습니다. 그 전에 쌓여 있던 메시지는 **한 건도 안 들어옵니다.**
> 그런데 로그는 완벽하게 깨끗합니다. 예외도 없고 WARN 도 없습니다. `partitions assigned` 도 정상적으로 찍힙니다. 리스너 메서드만 안 불릴 뿐입니다.
> 그래서 사람들은 리스너 코드, 역직렬화, 토픽명, 방화벽을 차례로 의심하며 몇 시간을 씁니다.
>
> **재현.** `spring.kafka.consumer.auto-offset-reset: latest` 로 바꾼 뒤, ① 앱을 끈 상태에서 3건을 발행하고 ② 한 번도 쓴 적 없는 그룹(`s01-latest`)으로 리스너를 켭니다.
>
> **결과** (`latest` + 새 그룹)
> ```
> INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-latest-1, groupId=s01-latest] Found no committed offset for partition orders-0
> INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.SubscriptionState            : [Consumer clientId=consumer-s01-latest-1, groupId=s01-latest] Resetting offset for partition orders-0 to position FetchPosition{offset=1, ...}
> INFO 12841 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer     : s01-latest: partitions assigned: [orders-0, orders-1, orders-2]
> ```
> `offset=1` 입니다. 0번 레코드를 건너뛰었습니다. 이 한 글자가 유일한 단서입니다.
>
> **가장 헷갈리는 부분 — 이 설정은 항상 적용되는 게 아닙니다.**
> `auto.offset.reset` 은 **커밋된 오프셋이 없을 때만** 쓰입니다. 한 번이라도 커밋한 그룹은 이 값을 완전히 무시하고 커밋 지점부터 이어 읽습니다.
> 그래서 "처음 한 번은 메시지가 안 보이다가, 이후엔 잘 되는" 이상한 재현률이 나옵니다. 그룹 이름을 바꾸는 순간 다시 재현됩니다.
>
> **진단.** `kcg --describe --group s01-latest` 가 `Consumer group 's01-latest' has no active members.` 를 내거나 그룹 자체가 없다고 하면, 한 번도 커밋한 적 없다는 뜻이고 = **지금 `auto.offset.reset` 이 작동 중**입니다.
>
> **해결.** 실습은 `earliest`. 운영은 서비스 성격에 따라 고르되, **반드시 의식하고 고르세요.** 새 서비스를 붙일 때 `earliest` 면 몇 달치 과거 이벤트가 한꺼번에 쏟아집니다. 그것도 사고입니다.

---

## 1-4. 자동 설정이 만들어 준 빈들 실제로 꺼내 보기

말로만 듣지 말고 컨텍스트에서 직접 꺼냅니다.

```java
Map<String, Class<?>> targets = new LinkedHashMap<>();
targets.put("KafkaTemplate",                 KafkaTemplate.class);
targets.put("ProducerFactory",               ProducerFactory.class);
targets.put("ConsumerFactory",               ConsumerFactory.class);
targets.put("KafkaListenerContainerFactory", KafkaListenerContainerFactory.class);
targets.put("KafkaAdmin",                    KafkaAdmin.class);
targets.put("KafkaListenerEndpointRegistry", KafkaListenerEndpointRegistry.class);
targets.put("KafkaTransactionManager",       KafkaTransactionManager.class);

targets.forEach((label, type) -> {
    String[] names = ctx.getBeanNamesForType(type);   // ← 구현체가 아니라 인터페이스로 조회
    ...
});
```

**결과**
```
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] ---- spring-kafka 자동 설정 빈 목록 ----
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] KafkaTemplate                    kafkaTemplate -> org.springframework.kafka.core.KafkaTemplate
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] ProducerFactory                  kafkaProducerFactory -> org.springframework.kafka.core.DefaultKafkaProducerFactory
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] ConsumerFactory                  kafkaConsumerFactory -> org.springframework.kafka.core.DefaultKafkaConsumerFactory
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] KafkaListenerContainerFactory    kafkaListenerContainerFactory -> org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] KafkaAdmin                       kafkaAdmin -> org.springframework.kafka.core.KafkaAdmin
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] KafkaListenerEndpointRegistry    org.springframework.kafka.config.internalKafkaListenerEndpointRegistry -> org.springframework.kafka.config.KafkaListenerEndpointRegistry
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] KafkaTransactionManager          (없음 — 조건 불충족)
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] 내부 인프라                      존재=true org.springframework.kafka.config.internalKafkaListenerAnnotationProcessor
INFO 12841 --- [           main] c.e.o.s.P$AutoConfiguredBeanReport        : [1-4] 리스너 컨테이너 1개: [s01-inventory-listener]
```

읽을 것이 여럿 있습니다.

**빈 이름이 두 종류입니다.** `kafkaTemplate`, `kafkaAdmin` 처럼 짧은 이름은 `KafkaAutoConfiguration` 의 `@Bean` 메서드 이름입니다. 반면 `org.springframework.kafka.config.internalKafkaListenerEndpointRegistry` 처럼 긴 이름은 `@EnableKafka` 가 등록하는 **인프라 빈**이며, 이름이 상수로 고정되어 있습니다. 이 사실이 1-5 의 근거입니다.

| 빈 | 역할 한 줄 |
|---|---|
| `kafkaTemplate` | `send()` 진입점. `ProducerFactory` 에서 `Producer` 를 빌려 레코드를 버퍼에 넣는다 |
| `kafkaProducerFactory` | `Producer` 를 만들고 **캐싱**. 기본적으로 앱 전체가 하나를 공유 |
| `kafkaConsumerFactory` | `Consumer` 를 만든다. 캐싱하지 않음. 컨테이너 스레드마다 하나씩 |
| `kafkaListenerContainerFactory` | `@KafkaListener` 하나를 `ConcurrentMessageListenerContainer` 로 찍어낸다 |
| `kafkaAdmin` | 기동 시 `NewTopic` 빈을 모아 `AdminClient` 로 토픽 생성. 없으면 만들고 있으면 둔다 |
| `internalKafkaListenerAnnotationProcessor` | `@KafkaListener` 애노테이션을 스캔해 엔드포인트로 등록하는 `BeanPostProcessor` |
| `internalKafkaListenerEndpointRegistry` | 컨테이너 보관소. `start()`/`stop()`/`pause()` 의 조작 대상 (Step 10) |

**`KafkaTransactionManager` 가 없습니다.** `spring.kafka.producer.transaction-id-prefix` 를 설정하지 않았기 때문입니다. 자동 설정은 실패하지 않았습니다. 조건이 안 맞아서 **아무것도 안 한 것**입니다. Step 09 에서 그 프로퍼티를 넣으면 이 자리에 빈이 나타납니다.

**리스너 컨테이너가 1개**입니다. `@KafkaListener` 를 하나 붙였으니 하나입니다. `concurrency` 를 3으로 올려도 이 숫자는 1입니다. `ConcurrentMessageListenerContainer` **하나**가 내부에 `KafkaMessageListenerContainer` 3개를 품는 구조이기 때문입니다.

> 💡 **실무 팁 — 어떤 자동 설정이 켜지고 꺼졌는지 통째로 보고 싶다면**
> ```bash
> ./gradlew bootRun --args='--spring.profiles.active=step01 --debug'
> ```
> `CONDITIONS EVALUATION REPORT` 가 출력되고, `KafkaAutoConfiguration` 항목에서 어떤 `@Bean` 이 `matched` 이고 어떤 것이 `did not match` 인지 이유까지 보여 줍니다.
> `KafkaAutoConfiguration#kafkaTransactionManager: @ConditionalOnProperty (spring.kafka.producer.transaction-id-prefix) did not find property` 라는 줄을 찾아 보세요.

---

## 1-5. `@EnableKafka` 는 왜 필요 없는가

Spring Kafka 문서와 예전 블로그 글에는 대부분 `@EnableKafka` 가 붙어 있습니다. 그런데 우리 `OrderServiceApplication` 에는 없습니다. 그래도 `@KafkaListener` 가 동작합니다.

이유는 자동 설정의 구조입니다.

```
KafkaAutoConfiguration
  └─ @Import(KafkaAnnotationDrivenConfiguration.class)
        └─ @ConditionalOnClass(EnableKafka.class)
             └─ EnableKafkaConfiguration (nested)
                   └─ @EnableKafka          ← 여기서 대신 붙여 준다
                        └─ @Import(KafkaListenerConfigurationSelector.class)
                             └─ KafkaBootstrapConfiguration
                                  ├─ internalKafkaListenerAnnotationProcessor
                                  └─ internalKafkaListenerEndpointRegistry
```

즉 `KafkaAnnotationDrivenConfiguration` 안의 중첩 설정 클래스에 `@EnableKafka` 가 이미 붙어 있고, 그것이 `KafkaBootstrapConfiguration` 을 끌어와 **`@KafkaListener` 를 스캔하는 후처리기**를 등록합니다. 1-4 에서 본 그 긴 이름의 빈 두 개가 이것입니다.

그리고 이 중첩 설정에는 조건이 하나 더 붙어 있습니다.

```java
@ConditionalOnMissingBean(name = KafkaListenerConfigUtils.KAFKA_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME)
```

**"사용자가 이미 `@EnableKafka` 를 붙였으면 나는 빠지겠다"** 는 뜻입니다.

### 직접 붙이면 어떻게 되나

```java
@Configuration
@EnableKafka
public class RedundantEnableKafkaConfig { }
```

**결과**
```
INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : The following 2 profiles are active: "step01", "step01-enable-kafka"
INFO 12841 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer     : s01-inventory: partitions assigned: [orders-0, orders-1, orders-2]
```

**아무 일도 안 일어납니다.** 에러도 없고 리스너가 두 번 등록되지도 않습니다. 후처리기 빈 이름이 상수로 고정되어 있어, Boot 쪽이 `@ConditionalOnMissingBean(name = ...)` 로 물러나기 때문입니다. 결과적으로 **정확히 하나**만 등록됩니다.

정리하면 이렇습니다.

| 상황 | `@EnableKafka` |
|---|---|
| Spring Boot + `spring-kafka` 스타터 | **불필요.** 붙여도 무해하지만 노이즈 |
| Boot 없이 순수 Spring Framework | **필수.** 안 붙이면 `@KafkaListener` 가 그냥 평범한 메서드 |
| `KafkaAutoConfiguration` 을 `exclude` 한 경우 | **필수** |

> 💡 **실무 팁 — "붙여도 무해하니 그냥 붙이자"를 권하지 않는 이유**
> `@EnableKafka` 를 직접 붙이는 순간, 위 조건식에 의해 Boot 의 `EnableKafkaConfiguration` 이 통째로 빠집니다.
> 지금은 그 안에 후처리기 등록밖에 없어서 차이가 없지만, Boot 버전이 올라가 그 클래스에 무언가가 추가되면 **여러분만 그 개선을 못 받게 됩니다.**
> 자동 설정이 해 주는 일은 자동 설정에 맡기는 편이 낫습니다.

---

## 1-6. 기동 로그 완전 해독

Step 01 의 목표 절반은 "이 로그가 정상인지 아는 것"입니다. 한 줄씩 읽습니다.

```
  .   ____          _            __ _ _
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.5)

INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : Starting OrderServiceApplication using Java 21.0.2 with PID 12841
INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step01"
INFO 12841 --- [           main] o.a.k.c.a.AdminClientConfig              : AdminClientConfig values:
	bootstrap.servers = [127.0.0.1:9092]
	request.timeout.ms = 30000
	...
INFO 12841 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka version: 3.6.1
INFO 12841 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka commitId: 5e3c2b738d253ff5
INFO 12841 --- [           main] o.s.k.c.KafkaAdmin                       : Created topics: [orders, orders.DLT, payments]
INFO 12841 --- [           main] o.a.k.c.c.ConsumerConfig                 : ConsumerConfig values:
	auto.offset.reset = earliest
	enable.auto.commit = false
	group.id = s01-inventory
	max.poll.records = 500
	value.deserializer = class org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
	...
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Discovered group coordinator 127.0.0.1:9092 (id: 2147483646 rack: null)
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s01-inventory-1, groupId=s01-inventory] Successfully joined group with generation Generation{generationId=1, ..., protocol='range'}
INFO 12841 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer     : s01-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 2.418 seconds (process running for 2.771)
INFO 12841 --- [           main] o.a.k.c.p.ProducerConfig                 : ProducerConfig values:
	acks = all
	enable.idempotence = true
	linger.ms = 5
	...
INFO 12841 --- [ad | producer-1] c.e.o.s.Practice$FirstPublisher          : [1-2] 발행 성공 key=ORD-0001 -> orders-1@0
```

| 로그 | 읽는 법 |
|---|---|
| `AdminClientConfig values:` | **가장 먼저** 뜹니다. `KafkaAdmin` 이 `NewTopic` 빈을 처리하려고 AdminClient 를 엽니다 |
| `Kafka version: 3.6.1` | **클라이언트** 버전입니다. 브로커는 3.7.0. 헷갈리기 쉬운 지점 |
| `Created topics: [...]` | 없던 토픽만 만듭니다. 이미 있으면 이 줄이 안 나옵니다 |
| `ConsumerConfig values:` | 컨슈머 설정 덤프. **`group.id` 와 `auto.offset.reset` 만 봐도 절반은 진단됩니다** |
| `Discovered group coordinator ... (id: 2147483646)` | 이 큰 숫자는 `Integer.MAX_VALUE - nodeId` 로 만든 가짜 ID 입니다. 정상 |
| `protocol='range'` | 파티션 배정 전략. 기본 `RangeAssignor` |
| `partitions assigned: [...]` | **컨테이너가 폴링을 시작합니다.** 이 줄이 없으면 소비는 절대 안 됩니다 |
| `Started OrderServiceApplication in 2.418 seconds` | 컨텍스트 기동 완료 |
| `ProducerConfig values:` | **기동 완료 뒤에** 뜹니다. 프로듀서는 첫 `send()` 때 지연 생성되기 때문 |

### 순서에서 배울 것 두 가지

**첫째, `ProducerConfig` 가 `Started ...` 뒤에 나옵니다.** `DefaultKafkaProducerFactory` 는 실제 `Producer` 를 **첫 `send()` 시점에** 만듭니다. 그래서 "브로커 주소가 틀렸는데 기동은 멀쩡히 되고, 첫 발행에서야 터지는" 일이 생깁니다. 컨슈머는 반대입니다. 기동 중에 그룹에 가입하므로 브로커가 죽어 있으면 기동 단계에서 티가 납니다.

**둘째, `partitions assigned` 가 `Started ...` 보다 앞에 나옵니다.** 리스너 컨테이너는 `SmartLifecycle` 로 컨텍스트 리프레시 막바지에 시작됩니다. 즉 **애플리케이션이 "시작됨"이라고 선언하기 전에 이미 메시지를 소비하고 있을 수 있습니다.** `ApplicationRunner` 에서 초기화하는 자원을 리스너가 참조한다면 순서 사고가 납니다. 이럴 땐 `autoStartup=false` 로 컨테이너를 늦게 켭니다(Step 10).

> 💡 `application.yml` 이 `ConsumerConfig`/`ProducerConfig` 로거를 `WARN` 으로 낮춰 두었습니다. 위 덤프를 보려면 잠시 `INFO` 로 올리세요.
> ```yaml
> logging.level.org.apache.kafka.clients.consumer.ConsumerConfig: INFO
> logging.level.org.apache.kafka.clients.producer.ProducerConfig: INFO
> ```
> **처음 며칠은 켜 두는 것을 권합니다.** 내가 준 설정이 정말 클라이언트에 전달됐는지 확인할 유일한 창구이고, 1-8 의 오타 경고도 이 덤프 근처에서 나옵니다.

> 💡 브로커가 파티션을 어떤 규칙으로 나눠 주는지(`RangeAssignor` vs `CooperativeStickyAssignor`)는 [Kafka 코스 Step 03](../../kafka/step-03-topics-partitions/) 과 [Step 05](../../kafka/step-05-consumer/) 에서 다룹니다. 이 코스는 그 결과를 Spring 이 어떻게 노출하는지에 집중합니다.

---

## 1-7. 메시지가 어디로 갔는지 확인하는 3가지 방법

"보냈는데 안 온다"는 이 코스에서 가장 자주 만나는 상황입니다. 세 방향에서 확인합니다. **셋 중 둘 이상이 일치해야 믿습니다.**

### ① 콘솔 컨슈머 — 브로커에 정말 있는가

```bash
kcc --topic orders --from-beginning \
    --property print.key=true --property print.partition=true --property print.offset=true
```

**결과**
```
Partition:0	Offset:0	ORD-0002	{"orderId":"ORD-0002",...}
Partition:1	Offset:0	ORD-0001	{"orderId":"ORD-0001",...}
Partition:2	Offset:0	ORD-0003	{"orderId":"ORD-0003",...}
```

여기 안 보이면 **발행 자체가 실패**했거나 다른 토픽으로 갔습니다(1-8). 여기 보이는데 리스너가 안 받으면 **컨슈머 쪽 문제**입니다. 이 한 번의 확인으로 문제 공간이 절반으로 줄어듭니다. 콘솔 컨슈머는 매번 임시 그룹(`console-consumer-49183`)을 새로 만들므로 `s01-inventory` 의 오프셋을 오염시키지 않습니다.

### ② 컨슈머 그룹 랙 — 내 그룹이 어디까지 읽었는가

```bash
kcg --describe --group s01-inventory
```

**결과**
```
GROUP          TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                       HOST         CLIENT-ID
s01-inventory  orders  0          1               1               0    consumer-s01-inventory-1-9f2c...  /172.19.0.1  consumer-s01-inventory-1
s01-inventory  orders  1          1               1               0    consumer-s01-inventory-1-9f2c...  /172.19.0.1  consumer-s01-inventory-1
s01-inventory  orders  2          1               1               0    consumer-s01-inventory-1-9f2c...  /172.19.0.1  consumer-s01-inventory-1
```

`LOG-END-OFFSET=1` 은 각 파티션에 1건씩 있다는 뜻이고, `CURRENT-OFFSET=1` 은 거기까지 커밋했다는 뜻입니다. `LAG=0`.

앱을 끈 상태에서 실행하면 `CONSUMER-ID` 자리가 `-` 로 바뀝니다. 커밋된 오프셋은 그대로 남아 있습니다. **오프셋은 컨슈머가 아니라 그룹에 붙어 있다**는 것을 보여 줍니다.

### ③ 리스너 로그 — 내 코드가 정말 처리했는가

①②는 브로커의 관점입니다. "내 비즈니스 로직이 실행됐는가"는 알려 주지 않습니다.

```java
log.info("[1-3] 재고 차감 {}-{}@{} key={} sku={} qty={}",
        record.topic(), record.partition(), record.offset(),
        record.key(), record.value().sku(), record.value().quantity());
```

```
INFO 12841 --- [ntainer#0-0-C-1] c.e.o.s.Practice$InventoryListener        : [1-3] 재고 차감 orders-0@0 key=ORD-0002 sku=SKU-003 qty=3
```

> 💡 **실무 팁 — 리스너 로그에 `topic-partition@offset` 을 무조건 남기세요.**
> 이 좌표 하나면 그 레코드를 언제든 다시 꺼낼 수 있습니다.
> ```bash
> kcc --topic orders --partition 0 --offset 0 --max-messages 1
> ```
> 그리고 구분자를 `-` 와 `@` 로 고정하는 데는 이유가 있습니다. **Spring Kafka 자신이 에러 로그에서 이 포맷을 씁니다.**
> ```
> WARN 12841 --- [ntainer#0-0-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=1000, currentAttempts=3, maxAttempts=3} exhausted for orders-1@42
> ```
> 애플리케이션 로그와 프레임워크 로그의 좌표 포맷이 같으면 `grep 'orders-1@42'` 한 번으로 양쪽을 동시에 훑을 수 있습니다. 장애 대응 시간이 크게 줄어듭니다.

### 세 방법의 사각지대

| 방법 | 알 수 있는 것 | **알 수 없는 것** |
|---|---|---|
| 콘솔 컨슈머 | 브로커에 레코드가 존재하는가 | 내 컨슈머가 받았는가 |
| `kcg --describe` | 내 그룹이 어디까지 커밋했는가 | 커밋된 게 **처리 성공**을 뜻하는가 |
| 리스너 로그 | 내 코드가 실행됐는가 | 그 뒤 커밋이 됐는가 |

**`LAG=0` 이 처리 성공을 뜻하지 않습니다.** 기본 `AckMode.BATCH` 는 리스너가 예외를 던져도 에러 핸들러가 처리를 끝내면 커밋합니다. 즉 **처리 실패 + 랙 0** 조합이 얼마든지 가능합니다. 이 간극이 Step 06·07 의 주제입니다.

---

## 1-8. 설정 오타는 조용히 무시된다

Kafka 를 쓰다 보면 "설정을 바꿨는데 아무 변화가 없다"는 순간이 옵니다. 대개 두 가지 중 하나입니다.

### ① 토픽명 오타 — 새 토픽이 조용히 생긴다

`orders` 대신 `order` 로 보냅니다.

```java
kafkaTemplate.send("order", event.orderId(), event)   // ← 's' 가 빠졌다
        .whenComplete((result, ex) -> { ... });
```

```bash
./gradlew bootRun --args='--spring.profiles.active=step01,step01-typo'
```

**결과**
```
WARN 12841 --- [ad | producer-1] c.e.o.s.Practice$TypoTopicPublisher       : [1-8] 오타 토픽에 발행 '성공' order-0@0
WARN 12841 --- [           main] c.e.o.s.Practice$TypoTopicPublisher       : [1-8] kt --list 로 'order' 토픽이 생겼는지 확인하세요
```

**"성공"입니다.** 예외도, 경고도 없습니다(WARN 은 우리가 일부러 찍은 것입니다).

```bash
kt --list
```

**결과**
```
__consumer_offsets
order
orders
orders.DLT
payments
```

`order` 가 새로 생겼습니다. `kt --describe --topic order` 로 보면 `PartitionCount: 1` — 브로커 기본값입니다. 원래 `orders` 는 3입니다.

> ⚠️ **함정 — 오타 난 토픽명은 예외가 아니라 새 토픽을 만든다**
>
> **원인.** 브로커의 `auto.create.topics.enable` 이 `true` 면(우리 `docker-compose.yml` 이 그렇습니다), 프로듀서가 없는 토픽의 메타데이터를 요청하는 순간 브로커가 **그 자리에서 토픽을 만들어** 줍니다. 그러니 발행은 정상적으로 성공합니다.
>
> **증상이 특히 고약한 이유.**
> - 발행 쪽 로그는 전부 초록불입니다. 개발자는 프로듀서를 의심하지 않습니다.
> - 리스너는 `orders` 를 보고 있으므로 **아무 일도 일어나지 않습니다.** "컨슈머가 안 돈다"로 오진하고 컨슈머만 몇 시간 뒤집니다.
> - 자동 생성 토픽은 **파티션 1개**라 나중에 오타를 고쳐도 잘못된 토픽에 쌓인 데이터가 남습니다.
>
> **프로듀서 쪽은 애플리케이션 설정으로 막을 수 없습니다.** 컨슈머에는 `allow.auto.create.topics=false` 가 있고 리스너 컨테이너에는 `missing-topics-fatal` 이 있지만, **프로듀서에는 대응하는 클라이언트 설정이 없습니다.** 토픽을 만들지 말지는 전적으로 브로커가 결정합니다.
>
> **해결.**
> 1. **운영 브로커에서는 `auto.create.topics.enable=false`.** 가장 확실한 방어선입니다.
> 2. 토픽명을 문자열 리터럴로 쓰지 않습니다. `@Value("${app.topic.orders}")` 나 상수로 **한 곳에서만** 관리합니다. 그러면 프로듀서와 컨슈머가 물리적으로 같은 값을 봅니다.
> 3. 리스너 쪽은 `missing-topics-fatal: true` 로 두어, 오타 난 토픽을 구독하면 **기동이 실패**하게 만듭니다. `IllegalStateException: Topic(s) [order] is/are not present and missingTopicsFatal is true` — 이건 **에러가 나서 다행인** 경우입니다.
>
> **정리.** `kt --delete --topic order`

### ② 프로퍼티 이름 오타 — WARN 한 줄로 끝난다

`linger.ms` 를 주려다 점을 빠뜨렸다고 합시다.

```yaml
spring:
  kafka:
    producer:
      properties:
        lingerms: 5        # ← linger.ms 여야 한다
```

**결과**
```
INFO 12841 --- [           main] o.a.k.c.p.ProducerConfig                 : ProducerConfig values:
	acks = all
	linger.ms = 0
	...
WARN 12841 --- [           main] o.a.k.c.p.ProducerConfig                 : The configuration 'lingerms' was supplied but isn't a known config.
INFO 12841 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka version: 3.6.1
```

`linger.ms = 0` 입니다. 우리가 준 5 는 어디에도 없습니다.

> ⚠️ **함정 — 알 수 없는 설정 프로퍼티는 WARN 만 내고 무시된다**
>
> **원인.** Kafka 클라이언트의 `AbstractConfig` 는 모르는 키를 받아도 예외를 던지지 않고, 사용되지 않은 키를 모아 WARN 한 줄로 알리고 끝냅니다. 설계 의도는 호환성입니다. 신버전 설정을 구버전 클라이언트에 줘도 앱이 죽으면 안 되고, 시리얼라이저·인터셉터가 자기 설정을 자유롭게 끼워 넣을 수 있어야 하니까요.
>
> **위험한 이유 세 가지.**
> 1. **WARN 이라 알림이 안 갑니다.** 대부분의 운영 로그 수집기는 ERROR 부터 통보합니다.
> 2. **기동 시 딱 한 번**, 그것도 수십 줄짜리 설정 덤프 한복판에서 나옵니다.
> 3. 증상이 "안 됨"이 아니라 **"기본값으로 동작함"** 입니다. 위 예에서 `linger.ms` 는 5 가 아니라 0 이 됩니다. 배치가 사실상 꺼진 상태로 도는데, 에러가 없으니 아무도 설정을 의심하지 않습니다.
> 더 나쁜 경우도 있습니다. `enableidempotence: true` 처럼 오타를 내면 멱등성이 꺼진 채로 돌다가, 네트워크가 한 번 흔들려 재시도가 일어나는 날 **중복 레코드가 생깁니다.**
>
> **해결.** 배포 체크리스트에 `2>&1 | grep "isn't a known config"` 를 넣으세요. 그리고 Boot 의 축약 프로퍼티(`spring.kafka.producer.acks` 등)는 오타를 Boot 가 잡아 주지만 **`properties:` 아래 원본 키만은 무방비**라는 것을 기억하세요. 중요한 설정은 `ProducerConfig.LINGER_MS_CONFIG` 같은 **상수**로 코드에서 지정하면 오타가 런타임 침묵 대신 컴파일 에러가 됩니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `spring-kafka` 의존성 | `KafkaAutoConfiguration` 을 켠다. 빈 7종이 자동 등록 |
| `kafkaTemplate` | `KafkaTemplate<?, ?>` 로 등록. 어떤 제네릭으로도 주입 가능하지만 **타입 안전하지 않다** |
| `ProducerFactory` vs `ConsumerFactory` | 프로듀서는 캐싱·공유, 컨슈머는 스레드마다 새로 (스레드 안전성 차이) |
| 조건부 빈 | `KafkaTransactionManager` 는 `transaction-id-prefix` 가 있어야 등록. **없으면 조용히 부재** |
| `send()` | 즉시 리턴. 결과는 `[ad \| producer-1]` 스레드의 콜백에서만 알 수 있다 |
| `@EnableKafka` | Boot 에서는 불필요. `KafkaAnnotationDrivenConfiguration` 이 대신 붙여 준다 |
| `partitions assigned` | 컨테이너가 폴링을 시작한 시점. **이 줄이 없으면 소비는 절대 안 된다** |
| 기동 순서 | AdminClient → 토픽 생성 → ConsumerConfig → 그룹 조인 → assigned → Started → **ProducerConfig** |
| `ProducerConfig` 가 늦는 이유 | 프로듀서는 첫 `send()` 때 지연 생성. 그래서 브로커 오설정이 기동 시 안 잡힌다 |
| `auto.offset.reset` | **커밋된 오프셋이 없을 때만** 적용. `latest` + 새 그룹 = 이전 메시지 영구 미수신 |
| 토픽명 오타 | 브로커가 **파티션 1개짜리 새 토픽을 조용히 생성**. 프로듀서 쪽은 앱 설정으로 못 막는다 |
| 프로퍼티 오타 | WARN 한 줄. `isn't a known config` 를 grep 하라. 증상은 "기본값으로 동작" |
| 확인 3종 | 콘솔 컨슈머 / `kcg --describe` / 리스너 로그. **`LAG=0` 은 처리 성공을 뜻하지 않는다** |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`. **반드시 콘솔 로그와 CLI 출력을 눈으로 대조**하세요.

1. `payments` 토픽으로 발행하고 `s01-payment` 그룹으로 수신하기 — 파티션 1개짜리 토픽의 `partitions assigned` 는 어떻게 다른가
2. 컨슈머 그룹 두 개(`s01-inventory`, `s01-notification`)로 같은 메시지를 **중복 소비**시키기 — 그리고 그룹 이름을 같게 주면 무엇이 달라지는가
3. `ApplicationContext.getBeanNamesForType` 으로 자동 설정 빈 목록 출력하기 — `KafkaTransactionManager` 가 **없는 이유** 설명하기
4. `auto-offset-reset` 을 `latest` 로 바꿔 **메시지 유실 재현** — 그리고 두 번째 실행부터 왜 재현되지 않는지 설명하기
5. 리스너 로그를 `topic-partition@offset` + key + timestamp 형태로 만들어 추적 가능하게 하기
6. 오타 난 토픽으로 발행할 때 **'조용히 성공'하지 않게** 만들기 — 프로듀서 쪽은 왜 못 막는가
7. `application.yml` 에 오타 난 프로퍼티(`lingerms`)를 넣고, 그것을 알려 주는 로그 한 줄 찾기

---

## 다음 단계

메시지를 보내고 받는 최소 경로를 확인했습니다. 하지만 1-2 에서 잠깐 언급한 것처럼, `send()` 는 **예외 없이 리턴해도 브로커에 안 갔을 수 있습니다.**
다음 스텝에서는 `KafkaTemplate` 의 `send` 오버로드 전부와 `CompletableFuture` 결과 처리를 파고들고, "안전하게 하려고" `send().get()` 을 붙였을 때 처리량이 어떻게 무너지는지를 숫자로 측정합니다.

→ [Step 02 — KafkaTemplate 과 프로듀서](../step-02-kafka-template/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 세 파일 모두 프로젝트의 `src/main/java/com/example/order/step01/` 에 넣고 **프로필로 골라서** 실행합니다. 먼저 `Practice.java` 를 `step01` 프로필로 띄워 1-2 ~ 1-7 을 재현하고, 함정은 보조 프로필(`step01-typo`, `step01-enable-kafka`)로 따로 켭니다. 그다음 `Exercise.java` 의 7문제를 `step01ex` 로 풀어 본 뒤 `Solution.java` 를 `step01sol` 로 돌려 대조합니다. 세 파일 모두 단일 `public final class` 안에 nested static class 를 담는 구조이며, 각 nested class 가 `@Component`/`@Configuration` 이라 컴포넌트 스캔에 그대로 걸립니다.

### Practice.java

본문 1-2 ~ 1-8 의 모든 예제를 절 번호 주석(`// [1-3] ...`)과 함께 한 파일에 모은 실행 파일입니다.

- 파일 상단 주석에 **실행 명령과 확인용 CLI 4개**가 정리돼 있습니다. 보조 프로필을 콤마로 이어 붙이는 방식(`--spring.profiles.active=step01,step01-typo`)에 주의하세요. `step01` 을 빼면 발행자와 리스너가 안 뜨고 함정 재현만 돕니다.
- `[1-2] FirstPublisher` 는 `ApplicationRunner` 이며 `@Order(20)` 입니다. `@Order(10)` 인 `AutoConfiguredBeanReport` 가 **먼저** 돌아 빈 목록을 출력한 뒤 발행이 시작되도록 순서를 고정했습니다. 순서를 바꾸면 로그가 뒤섞여 읽기 어려워집니다.
- `[1-3] InventoryListener` 는 `concurrency = "1"` 을 **명시**합니다. `application.yml` 의 `listener.concurrency: 3` 을 그대로 두면 `partitions assigned` 가 세 줄로 쪼개져 본문 로그와 달라집니다. 이 값을 3으로 바꿔 보고 로그가 어떻게 갈라지는지 직접 확인해 보는 것도 좋은 실습입니다(Step 03 예고편).
- `[1-4] AutoConfiguredBeanReport` 는 **인터페이스 타입**으로 조회합니다(`ProducerFactory.class`, 구현체인 `DefaultKafkaProducerFactory.class` 가 아님). 구현을 갈아끼워도 조회가 깨지지 않게 하려는 습관입니다. `ctx.getType(name)` 이 `null` 을 리턴할 수 있어 방어 코드가 들어 있습니다.
- `[1-5] RedundantEnableKafkaConfig` 는 **의도적으로 비어 있는** `@Configuration` 입니다. 클래스 본문이 아니라 `@EnableKafka` 애노테이션 자체가 실험 대상입니다. 이 프로필을 켜고 리스너가 여전히 **정확히 한 번만** 메시지를 받는지 확인하세요.
- `[1-8] TypoTopicPublisher` 는 `TYPO_TOPIC = "order"` 상수 하나가 전부입니다. 실행 후 반드시 `kt --delete --topic order` 로 정리하세요. 방치하면 이후 스텝의 `kt --list` 출력이 교재와 달라집니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. "여기에 작성:" 아래를 채우는 구조이며, **비워 둔 상태로도 컴파일과 기동이 됩니다.** 미구현 로그가 찍히는 것으로 자리를 확인할 수 있습니다.

- 문제 1·2·3·5·7 은 기본 프로필 `step01ex` 하나로 돌아가고, 문제 4·6 은 각각 보조 프로필 `step01ex-latest` / `step01ex-strict` 가 필요합니다. 두 문제만 별도 `ConsumerFactory` 와 `ContainerFactory` 를 새로 만들기 때문에, 기본 프로필과 섞이면 리스너가 중복으로 뜹니다.
- **문제 4 의 `Ex4LatestConfig` 는 일부러 틀린 값(`"earliest"`)이 들어 있습니다.** 그 줄을 `"latest"` 로 고치는 것이 문제의 절반이고, 나머지 절반은 (c)의 3단계 실행 절차를 그대로 밟아 "안 보임 → 보임"을 눈으로 확인하는 것입니다.
- `props.buildConsumerProperties()` 는 **인자 없는 오버로드**를 씁니다. Boot 3.4 부터 `SslBundles` 를 받는 시그니처가 추가되었으므로, 다른 버전의 예제를 복사해 오면 컴파일이 깨집니다. 이 프로젝트는 Boot 3.2.5 입니다.
- 문제 6 의 힌트 3("프로듀서는 어떤가요?")이 이 문제의 진짜 목적입니다. 컨슈머는 막을 수 있고 프로듀서는 못 막는다는 **비대칭**을 스스로 발견하게 하려는 구성입니다. 답이 안 나와도 괜찮습니다. 못 막는 게 정답입니다.
- 문제 7 은 코드를 거의 안 씁니다. `application.yml` 을 한 줄 고치고 **기동 로그에서 한 줄을 찾아 상수에 붙여넣는** 것이 전부입니다. 로그를 읽는 훈련이 목적입니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 코드 자체보다 주석이 본론입니다. 토픽명을 `@Value` 로 받는 이유가 취향이 아니라 **1-8 함정의 구조적 방어책**이라는 점, 그리고 파티션 1개짜리 토픽의 `partitions assigned: [payments-0]` 가 어떻게 다른지를 설명합니다.
- **정답 2** 는 "groupId 를 같게 주면 어떻게 되는가"까지 답합니다. 다른 그룹이면 3건씩 총 6줄, 같은 그룹이면 나눠 가져서 총 3줄입니다. "왜 절반만 처리되지?"라는 흔한 질문의 답이 여기 있습니다.
- **정답 3** 은 `KafkaTransactionManager` 가 없는 이유를 `@ConditionalOnProperty(spring.kafka.producer.transaction-id-prefix)` 로 못 박습니다. **실패가 아니라 부재**라는 것이 요지입니다.
- **정답 4** 가 이 스텝에서 가장 긴 주석입니다. `auto.offset.reset` 이 "커밋된 오프셋이 없을 때만" 쓰인다는 사실 하나로 (c)와 (d)의 서로 모순돼 보이는 결과가 전부 설명됩니다. `kcg --describe` 가 "그룹이 없다"고 답하는 것이 **"지금 이 설정이 작동 중"의 신호**라는 진단법까지 담았습니다.
- **정답 6** 의 결론은 "완전히는 못 막는다" 입니다. 컨슈머 쪽 `allow.auto.create.topics=false` + `missingTopicsFatal=true` 로 구독은 막지만, 프로듀서 쪽은 브로커의 `auto.create.topics.enable=false` 만이 유일한 방어선입니다. 코드가 그 비대칭을 로그로 직접 드러내도록 짜여 있습니다.
- **정답 7** 은 `The configuration 'lingerms' was supplied but isn't a known config.` 한 줄을 상수로 박아 두고, 그것이 WARN 인 것이 왜 위험한지를 세 가지로 나눠 설명합니다.

```java file="./Solution.java"
```
