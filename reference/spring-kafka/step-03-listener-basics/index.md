# Step 03 — @KafkaListener 기초

> **학습 목표**
> - `@KafkaListener` 애노테이션 하나가 만들어 내는 객체 사슬(엔드포인트 → 레지스트리 → 컨테이너 → 컨슈머 → 스레드)을 로그로 추적한다
> - `ConcurrentKafkaListenerContainerFactory` 를 기본 팩토리 덮어쓰기와 이름 붙인 팩토리 두 방식으로 커스터마이징한다
> - `groupId` 의 결정 우선순위를 확인하고, **둘 다 없을 때 기동이 실패하는 것**을 재현한다
> - **`concurrency` 를 파티션 수보다 크게·같게·작게 잡은 세 경우의 `partitions assigned` 로그를 직접 비교한다**
> - `topicPartitions` 로 파티션을 명시 할당하고, 그것이 컨슈머 그룹 관리를 우회한다는 사실을 확인한다
> - 리스너 처리가 `max.poll.interval.ms` 를 넘겼을 때 그룹에서 쫓겨나 재처리가 발생하는 것을 재현한다
>
> **선행 스텝**: Step 02 — KafkaTemplate 과 프로듀서
> **예상 소요**: 90분

---

## 3-0. 실습 준비

프로필 `step03` 으로 실행합니다. 컨슈머 그룹은 `s03-` 접두사를 씁니다.
```bash
./gradlew bootRun --args='--spring.profiles.active=step03'
kt --describe --topic orders
```

**결과**
```
Topic: orders	TopicId: xJ0kQm8CQ3ay6z4rN2ZbSA	PartitionCount: 3	ReplicationFactor: 1	Configs: segment.bytes=1073741824
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
```

`PartitionCount: 3` 이 아니면 3-4 절의 실습이 전혀 다른 결과를 냅니다. 다르면 여기서 멈추고 토픽을 다시 만드세요.
`Practice.java` 의 `Seeder` 가 기동 직후 `OrderCreated.of(1)` ~ `of(9)` 를 발행합니다. 키가 결정적이라 파티션 배정도 항상 같습니다.
`orders-0 ← ORD-0002, 0004, 0009` / `orders-1 ← ORD-0001, 0006, 0008` / `orders-2 ← ORD-0003, 0005, 0007`.
파티션별로 정확히 3건씩입니다. **어느 스레드가 몇 건을 받는지 세기 좋도록** 고른 숫자입니다.

---

## 3-1. `@KafkaListener` 가 실제로 만들어 내는 것

```java
@KafkaListener(topics = "orders", groupId = "s03-inventory")
public void onOrder(OrderCreated e) { log.info("재고 차감 {} {} x{}", e.orderId(), e.sku(), e.quantity()); }
```

이 두 줄이 기동 시점에 만들어 내는 것입니다.

```
  @KafkaListener 붙은 메서드
        │  KafkaListenerAnnotationBeanPostProcessor 가 스캔
        ▼
  MethodKafkaListenerEndpoint        ← "어떤 빈의 어떤 메서드를, 어떤 토픽/그룹으로" 라는 메타데이터
        ▼  registrar.registerEndpoint(endpoint, factory)
  KafkaListenerEndpointRegistry      ← 엔드포인트를 모으는 싱글턴. SmartLifecycle 로 start()
        ▼  factory.createListenerContainer(endpoint)
  ConcurrentMessageListenerContainer ← 빈 이름: ...KafkaListenerEndpointContainer#0
        ├──────────────┬──────────────┐   concurrency = 3
        ▼              ▼              ▼
  KafkaMessageListenerContainer #0-0 / #0-1 / #0-2   ← 실제 poll 루프 단위
        ▼              ▼              ▼
   KafkaConsumer + 전용 스레드 1개씩
   [ntainer#0-0-C-1] [ntainer#0-1-C-1] [ntainer#0-2-C-1]
```

핵심은 **`KafkaConsumer` 가 스레드 안전하지 않다**는 점입니다. 그래서 Spring 은 컨슈머 하나에 스레드 하나를 짝지어 두고, 그 스레드 안에서만 `poll()` → 리스너 호출 → 커밋을 순서대로 돌립니다. `concurrency=3` 은 "리스너 메서드를 3스레드로 동시 실행"이 아니라 **"컨슈머+스레드 쌍을 3벌 만든다"** 는 뜻입니다.

**결과**
```
INFO 13103 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step03"
INFO 13103 --- [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s03-inventory-1, groupId=s03-inventory] Successfully joined group with generation Generation{generationId=1, memberId='consumer-s03-inventory-1-4f9a1c02', protocol='range'}
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0]
INFO 13103 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-1]
INFO 13103 --- [ntainer#0-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-2]
INFO 13103 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 2.514 seconds (process running for 2.883)
```

### 스레드 이름 읽는 법

전체 이름은 `org.springframework.kafka.KafkaListenerEndpointContainer#0-0-C-1` 입니다. Boot 기본 로그 패턴이 `%15.15t` — **뒤에서 15글자만** 남기므로 `ntainer#0-0-C-1` 로 보입니다.

| 조각 | 의미 |
|---|---|
| `#0` | 몇 번째 `@KafkaListener` 인가 (등록 순서, 0부터) |
| `-0` | 그 컨테이너의 몇 번째 자식(=concurrency 인덱스)인가 |
| `-C-1` | Consumer 스레드 + 재시작 카운터. 컨슈머가 죽고 재생성되면 `-C-2` 가 된다 |

> 💡 **실무 팁 — `id` 를 주면 스레드 이름이 읽을 수 있게 바뀝니다**
> `@KafkaListener(id = "s03-inventory", ...)` 면 스레드 이름이 `s03-inventory-0-C-1` 이 되어 로그에 `[inventory-0-C-1]` 로 찍힙니다.
> `[ntainer#0-0-C-1]` 과 `[ntainer#1-0-C-1]` 을 눈으로 구분하느라 시간을 버리지 마세요. **리스너가 둘 이상이면 `id` 는 사실상 필수입니다.**
> 단, `id` 는 **`groupId` 의 후보로도 쓰입니다**(3-3).

---

## 3-2. `ConcurrentKafkaListenerContainerFactory` 커스터마이징

`@KafkaListener` 는 컨테이너를 직접 만들지 않고 **팩토리에게 만들어 달라고** 합니다. Boot 자동 설정이 `kafkaListenerContainerFactory` 라는 이름의 빈을 하나 만들어 두었고, `containerFactory` 를 지정하지 않은 모든 리스너가 이걸 씁니다.

### ① 기본 팩토리 덮어쓰기 — 같은 이름으로 빈을 정의하면 자동 설정이 물러납니다(`@ConditionalOnMissingBean`)

```java
@Bean   // ← 이름이 kafkaListenerContainerFactory 인 순간 자동 설정이 비활성화된다
public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> kafkaListenerContainerFactory(
        ConsumerFactory<String, OrderCreated> cf) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, OrderCreated>();
    f.setConsumerFactory(cf);
    f.setConcurrency(3);
    return f;
}
```

전 리스너에 일괄 적용되어 편하지만, **리스너마다 다른 설정이 필요해지는 순간 막힙니다.**

### ② 이름 붙인 팩토리 여러 개 — `@Bean("fastFactory")`(concurrency=3), `@Bean("singleFactory")`(concurrency=1)

```java
@KafkaListener(topics = "orders", groupId = "s03-inventory", containerFactory = "fastFactory")
public void fast(OrderCreated e) { ... }

@KafkaListener(topics = "orders", groupId = "s03-audit", containerFactory = "singleFactory")
public void audit(OrderCreated e) { ... }
```

**결과**
```
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0]
INFO 13103 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-1]
INFO 13103 --- [ntainer#0-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-2]
INFO 13103 --- [ntainer#1-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-audit: partitions assigned: [orders-0, orders-1, orders-2]
```

`#1-0` 하나가 파티션 3개를 전부 받았습니다. `singleFactory` 의 `concurrency=1` 때문입니다.

기본 팩토리 덮어쓰기는 앱 전체가 한 가지 정책일 때 깔끔하고, 이름 붙인 팩토리는 리스너별로 concurrency·에러 핸들러·역직렬화를 분리할 수 있어 **실무에서는 대개 이쪽**입니다.

> ⚠️ **함정 — `containerFactory` 이름 오타는 기동을 실패시킵니다(다행히)**
> ```
> ERROR 13103 --- [           main] o.s.b.SpringApplication : Application run failed
> java.lang.IllegalStateException: Could not create message listener container for endpoint
>   ... No bean named 'fastFactroy' available
> ```
> 이 스텝의 다른 설정 실수와 달리 **이건 기동 시점에 시끄럽게 터집니다.** Kafka 설정 중에는 드문 경우이니 고마워하세요.

---

## 3-3. `groupId` — 우선순위와, 없을 때 벌어지는 일

그룹 ID 를 정하는 곳이 세 군데입니다. **위에서부터 이깁니다.**

| 순위 | 위치 |
|---|---|
| 1 | `@KafkaListener(groupId = "...")` |
| 2 | `@KafkaListener(id = "...")` (`idIsGroup=true` 가 기본값) |
| 3 | `spring.kafka.consumer.group-id` (`application.yml`) |

둘 다 주면 `groupId` 가 그룹 이름이 되고 `id` 는 컨테이너 빈 이름·스레드 이름으로만 쓰입니다. 가장 명시적이라 권장합니다.

```java
@KafkaListener(id = "inv", groupId = "s03-inventory", topics = "orders")
```

**결과**
```
INFO 13103 --- [       inv-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0]
```

스레드 이름은 `inv`, 그룹 이름은 `s03-inventory`. 의도한 대로입니다.

> ⚠️ **함정 — 그룹 ID 가 아무 데도 없으면 기동이 실패합니다**
> ```
> ERROR 13103 --- [           main] o.s.b.SpringApplication : Application run failed
> org.springframework.context.ApplicationContextException: Failed to start bean
>   'org.springframework.kafka.config.internalKafkaListenerEndpointRegistry';
>   nested exception is java.lang.IllegalStateException:
>   No group.id found in consumer config, container properties, or @KafkaListener annotation;
>   a group.id is required when group management is used.
> ```
> 이 코스의 `application.yml` 에는 `spring.kafka.consumer.group-id` 가 **의도적으로 없습니다.**
> 매 리스너가 자기 그룹을 명시하게 만들어, "실수로 두 리스너가 같은 그룹을 공유하는" 사고(3-7)를 구조적으로 줄이기 위해서입니다.

### 같은 그룹 vs 다른 그룹

메시지 9건이 이미 토픽에 있는 상태에서 리스너 두 개를 **같은 그룹**으로 두면(각 `concurrency=1`):

```java
@KafkaListener(id = "a", groupId = "s03-shared", topics = "orders")
public void a(OrderCreated e) { log.info("A 수신 {}", e.orderId()); }
@KafkaListener(id = "b", groupId = "s03-shared", topics = "orders")
public void b(OrderCreated e) { log.info("B 수신 {}", e.orderId()); }
```

**결과**
```
INFO 13103 --- [         a-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-shared: partitions assigned: [orders-0, orders-1]
INFO 13103 --- [         b-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-shared: partitions assigned: [orders-2]
INFO 13103 --- [         a-0-C-1] c.e.o.step03.SharedListener              : A 수신 ORD-0002
INFO 13103 --- [         a-0-C-1] c.e.o.step03.SharedListener              : A 수신 ORD-0004
INFO 13103 --- [         a-0-C-1] c.e.o.step03.SharedListener              : A 수신 ORD-0009
... (A 는 ORD-0001, 0006, 0008 까지 총 6건) ...
INFO 13103 --- [         b-0-C-1] c.e.o.step03.SharedListener              : B 수신 ORD-0003
INFO 13103 --- [         b-0-C-1] c.e.o.step03.SharedListener              : B 수신 ORD-0005
INFO 13103 --- [         b-0-C-1] c.e.o.step03.SharedListener              : B 수신 ORD-0007
```

**A 가 6건, B 가 3건.** 합쳐 9건입니다. 같은 그룹이면 **파티션을 나눠 갖고 각자 일부만** 받습니다. **다른 그룹**(`s03-inventory` / `s03-notification`)으로 바꾸면 둘 다 9건씩, 합계 18건 — 이게 팬아웃입니다.

> 💡 컨슈머 그룹은 "메시지를 나눠 처리하는 단위"이자 "오프셋을 공유하는 단위"입니다. **비즈니스 관심사 하나 = 그룹 하나**로 잡으세요. 그룹 멤버십과 파티션 배정 전략(Range/RoundRobin/CooperativeSticky)의 브로커 쪽 동작은 [Kafka 코스 Step 05](../../kafka/step-05-consumer/) 를 참고하세요.

---

## 3-4. `concurrency` 와 파티션 수의 관계 — 이 스텝의 핵심

`concurrency=N` 은 **컨슈머 N 개를 같은 그룹에 조인시키는 것**과 정확히 같습니다. 파티션 배정은 브로커가 정합니다. 파티션 3개인 `orders` 를 세 가지 설정으로 소비해 봅니다.

### 경우 ① `concurrency=3`, 파티션 3 → 1:1

**결과**
```
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0]
INFO 13103 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-1]
INFO 13103 --- [ntainer#0-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-2]
INFO 13103 --- [ntainer#0-1-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0001 SKU-002 x2
INFO 13103 --- [ntainer#0-0-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0002 SKU-003 x3
INFO 13103 --- [ntainer#0-2-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0003 SKU-001 x4
... (총 9건, 스레드마다 3건씩) ...
```

처리량 실측: 리스너에 5ms 부하를 걸고 10,000건을 소비시켰을 때 **2.9초 (약 3,450 msg/s)**.

### 경우 ② `concurrency=5`, 파티션 3 → 두 스레드가 논다

**결과**
```
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0]
INFO 13103 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-1]
INFO 13103 --- [ntainer#0-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-2]
INFO 13103 --- [ntainer#0-3-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: []
INFO 13103 --- [ntainer#0-4-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: []
INFO 13103 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 2.702 seconds (process running for 3.061)
```

`partitions assigned: []` — **빈 대괄호 두 줄**. 이게 전부입니다.

`WARN` 도 `ERROR` 도 없습니다. 로그 레벨은 `INFO` 입니다. 앱은 정상 기동하고 메시지도 정상 처리됩니다. **처리량만 안 늘어납니다** — 같은 조건에서 **2.9초 (3,450 msg/s)**, `concurrency` 를 5로 올렸는데 **0% 개선**입니다. 스레드 2개, `KafkaConsumer` 2개, 그들의 TCP 연결과 힙 버퍼가 아무 일도 안 하며 살아 있습니다.

`kcg --describe --group s03-inventory` 에는 **멤버 5개 중 3개만** 나옵니다. 파티션이 없는 멤버는 `--members` 로만 보입니다.

```bash
kcg --describe --group s03-inventory --members
```

**결과**
```
GROUP          CONSUMER-ID                            HOST         CLIENT-ID                  #PARTITIONS
s03-inventory  consumer-s03-inventory-1-4f9a1c02...   /172.19.0.1  consumer-s03-inventory-1   3
s03-inventory  consumer-s03-inventory-2-8b2e77d1...   /172.19.0.1  consumer-s03-inventory-2   3
s03-inventory  consumer-s03-inventory-3-c0d4a9f6...   /172.19.0.1  consumer-s03-inventory-3   3
s03-inventory  consumer-s03-inventory-4-1a55b3e0...   /172.19.0.1  consumer-s03-inventory-4   0
s03-inventory  consumer-s03-inventory-5-9e71f4aa...   /172.19.0.1  consumer-s03-inventory-5   0
```

`#PARTITIONS 0` 이 두 줄 — **이 명령이 "노는 컨슈머"를 찾는 가장 빠른 방법입니다.**

> ⚠️ **함정 — `concurrency` 초과분은 경고 없이 놉니다**
> "처리가 밀린다 → `concurrency` 를 올린다"는 반사적 대응이 **아무 효과가 없는데 아무 신호도 주지 않는** 대표 사례입니다.
> **증상**: `concurrency` 를 3 → 10 으로 올렸는데 랙이 그대로. CPU 사용률도 그대로. 로그는 깨끗.
> **원인**: 파티션 수가 상한입니다. 파티션 3개짜리 토픽은 컨슈머를 아무리 늘려도 **동시에 3개만** 일합니다.
> **해결**: `concurrency` 를 올리기 전에 `kt --describe --topic <토픽>` 으로 `PartitionCount` 를 먼저 보세요. 진짜로 병렬도를 늘리려면 **파티션을 늘려야** 하는데, 파티션을 늘리면 키→파티션 매핑이 바뀌어 순서 보장이 깨집니다([실습 프로젝트 셋업](../project/) 참고). 그래서 파티션 수는 **처음에 결정하는 값**이고, `concurrency` 는 그 안에서만 조절하는 값입니다.

> 💡 Spring 이 초과분을 스스로 줄여 주는 경우가 딱 하나 있습니다. **`topicPartitions` 로 명시 할당**했을 때(3-6)입니다. 그때는 파티션 개수를 알기 때문에 `concurrency` 를 그 수까지 자동으로 낮춥니다. 그룹 관리 방식(`topics=`)에서는 Spring 이 파티션 수를 모르므로 손댈 수 없습니다.

### 경우 ③ `concurrency=2`, 파티션 3 → 한 스레드가 2개 담당

**결과**
```
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0, orders-1]
INFO 13103 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-2]
INFO 13103 --- [ntainer#0-0-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0002 SKU-003 x3   ← orders-0
INFO 13103 --- [ntainer#0-0-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0004 SKU-002 x5   ← orders-0
INFO 13103 --- [ntainer#0-0-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0009 SKU-001 x5   ← orders-0
INFO 13103 --- [ntainer#0-0-C-1] c.e.o.step03.InventoryListener           : 재고 차감 ORD-0001 SKU-002 x2   ← orders-1
... (#0-0 은 ORD-0006, 0008 까지 6건 / #0-1 은 ORD-0003, 0005, 0007 3건) ...
```

`#0-0` 이 6건, `#0-1` 이 3건. **부하가 2:1 로 기웁니다.** 처리량 실측 **4.4초 (2,270 msg/s)** — 3개일 때(2.9초)보다 느립니다. 파티션 2개를 맡은 스레드가 병목입니다.
또 하나 — `#0-0` 의 처리 순서가 orders-0 전부(`ORD-0002, 0004, 0009`) 다음에 orders-1 전부(`ORD-0001, 0006, 0008`)입니다. **한 스레드가 두 파티션을 맡아도 번갈아 가지 않고, `poll()` 이 돌려준 레코드를 파티션 단위로 몰아서** 처리합니다.

| concurrency | 파티션 | 배정 결과 | 처리량 실측 | 판단 |
|---:|---:|---|---:|---|
| 1 | 3 | `#0-0` 이 3개 전부 | 8.1초 / 1,230 msg/s | 순서가 중요할 때만 |
| 2 | 3 | 2개 + 1개 (2:1 불균형) | 4.4초 / 2,270 msg/s | 어중간 |
| **3** | **3** | **1:1** | **2.9초 / 3,450 msg/s** | **권장** |
| 5 | 3 | 1:1 + **빈 스레드 2개** | 2.9초 / 3,450 msg/s | 자원 낭비 |

> 💡 **`concurrency` 의 기본값은 1 입니다.** 이 코스는 `application.yml` 에서 `spring.kafka.listener.concurrency: 3` 으로 올려 두었습니다. 이걸 모르면 파티션을 3개로 늘려 놓고 "왜 안 빨라지지" 하게 됩니다. 파티션과 concurrency 는 **둘 다** 봐야 합니다.

---

## 3-5. 인스턴스를 늘렸을 때 — 스케일 아웃의 상한

`concurrency` 는 한 JVM 안의 이야기입니다. 인스턴스를 여러 개 띄우면 어떻게 될까요? **똑같습니다.** 브로커는 "어느 JVM 소속인가"를 모르고, 그룹에 조인한 컨슈머의 총수만 봅니다.

```
파티션 3개 · 앱 인스턴스 2개 · 각 concurrency=3  →  그룹 멤버 6명
  인스턴스 A: #0-0→orders-0  #0-1→orders-1  #0-2→orders-2   (전량 처리)
  인스턴스 B: #0-0→[]        #0-1→[]        #0-2→[]         (전부 논다)
```

두 번째 인스턴스를 띄운 순간의 A 쪽 로그입니다.

**결과**
```
INFO 13103 --- [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s03-inventory-1, groupId=s03-inventory] Request joining group due to: group is already rebalancing
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions revoked: [orders-0]
INFO 13103 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions revoked: [orders-1]
INFO 13103 --- [ntainer#0-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions revoked: [orders-2]
INFO 13103 --- [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s03-inventory-1, groupId=s03-inventory] Successfully joined group with generation Generation{generationId=2, memberId='consumer-s03-inventory-1-4f9a1c02', protocol='range'}
INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0]
... (#0-1 → orders-1, #0-2 → orders-2. 전과 동일) ...
```

`generationId` 가 1 → 2 로 올라가며 리밸런스가 일어났지만 **A 는 결국 똑같은 3개를 다시 받았습니다.** B 는 전부 `[]` — 기본 배정 전략이 `range` 라서 먼저 조인한 쪽이 앞 파티션을 다 가져갑니다.

| 파티션 | 인스턴스 | 인스턴스당 concurrency | 총 컨슈머 | 일하는 컨슈머 | 노는 컨슈머 |
|---:|---:|---:|---:|---:|---:|
| 3 | 1 | 3 | 3 | 3 | 0 |
| 3 | 1 | 5 | 5 | 3 | **2** |
| 3 | 2 | 3 | 6 | 3 | **3** |
| 3 | 3 | 1 | 3 | 3 | 0 |
| 3 | 3 | 3 | 9 | 3 | **6** |
| 12 | 4 | 3 | 12 | 12 | 0 |

**결론: 한 컨슈머 그룹의 최대 병렬도 = 토픽의 파티션 수.** 인스턴스를 늘리든 `concurrency` 를 올리든 이 선을 못 넘습니다.
> 💡 **실무 팁 — 파티션 수는 "장래의 최대 인스턴스 수 × concurrency" 로 잡습니다**
> 늘리는 게 위험하니(순서 보장 붕괴) 처음에 넉넉히 잡되, 무한정은 안 됩니다. 파티션마다 브로커 파일 핸들·메모리·리밸런스 시간이 듭니다. 감각으로는 **예상 최대 병렬도의 2~3배**가 무난합니다.
> 반대로 **노는 컨슈머를 없애고 싶다면 인스턴스 수에 맞춰 `concurrency` 를 낮추는 것**이 정답입니다. 위 표의 파티션 3 · 인스턴스 3 · `concurrency=1` 이 가장 깔끔합니다.

---

## 3-6. `topicPartitions` — 파티션 명시 할당

`topics=` 대신 `topicPartitions=` 를 쓰면 브로커의 그룹 관리를 거치지 않고 **컨슈머가 직접 파티션을 잡습니다**(`consumer.assign()`).

```java
@KafkaListener(id = "p01", groupId = "s03-partial",
        topicPartitions = @TopicPartition(topic = "orders", partitions = {"0", "1"}))
public void onlyZeroAndOne(OrderCreated e, @Header(KafkaHeaders.RECEIVED_PARTITION) int p) {
    log.info("p{} {}", p, e.orderId());
}
```

**결과**
```
INFO 13103 --- [       p01-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-partial: partitions assigned: [orders-0]
INFO 13103 --- [       p01-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-partial: partitions assigned: [orders-1]
INFO 13103 --- [       p01-0-C-1] c.e.o.step03.PartitionListener           : p0 ORD-0002
INFO 13103 --- [       p01-0-C-1] c.e.o.step03.PartitionListener           : p0 ORD-0004
INFO 13103 --- [       p01-0-C-1] c.e.o.step03.PartitionListener           : p0 ORD-0009
INFO 13103 --- [       p01-1-C-1] c.e.o.step03.PartitionListener           : p1 ORD-0001
... (p1 은 ORD-0006, 0008 까지 3건) ...
```

`orders-2` 의 `ORD-0003/0005/0007` 은 **영원히 안 옵니다.** 그게 이 설정의 의미입니다. `concurrency=5` 를 줘도 컨테이너는 2개만 생깁니다 — Spring 이 파티션 개수를 알기 때문이며, 3-4 의 함정이 여기서는 발생하지 않습니다.

### 특정 오프셋부터 읽기 — `partitionOffsets`

```java
@KafkaListener(id = "replay", groupId = "s03-replay",
        topicPartitions = @TopicPartition(topic = "orders", partitionOffsets = {
                @PartitionOffset(partition = "0", initialOffset = "1"),
                @PartitionOffset(partition = "2", initialOffset = "0")}))
public void replay(ConsumerRecord<String, OrderCreated> rec) {
    log.info("replay {}-{}@{} {}", rec.topic(), rec.partition(), rec.offset(), rec.key());
}
```

**결과**
```
INFO 13103 --- [    replay-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-replay: partitions assigned: [orders-0]
INFO 13103 --- [    replay-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-replay: partitions assigned: [orders-2]
INFO 13103 --- [    replay-0-C-1] c.e.o.step03.ReplayListener              : replay orders-0@1 ORD-0004
INFO 13103 --- [    replay-0-C-1] c.e.o.step03.ReplayListener              : replay orders-0@2 ORD-0009
INFO 13103 --- [    replay-1-C-1] c.e.o.step03.ReplayListener              : replay orders-2@0 ORD-0003
... (orders-2@1 ORD-0005, orders-2@2 ORD-0007) ...
```

`orders-0` 은 오프셋 1 부터라 `ORD-0002`(오프셋 0)를 건너뛰었습니다. `initialOffset` 은 **커밋된 오프셋을 무시하고** 그 자리에서 시작합니다. `relativeToCurrent = "true"` 를 주면 현재 커밋 위치 기준 상대값이 됩니다(`initialOffset = "-5"` → 최근 5건).

> ⚠️ **함정 — 명시 할당은 그룹 관리를 우회하지만, 커밋은 여전히 그룹에 씁니다**
> `topicPartitions` 로 붙은 컨슈머는:
> - **리밸런스에 참여하지 않습니다.** 다른 인스턴스가 떠도 파티션을 뺏기지 않고, 죽어도 누가 이어받지 않습니다.
> - 그런데 **오프셋 커밋은 그대로 `groupId` 앞으로 나갑니다.** `kcg --describe --group s03-partial` 에 그대로 찍힙니다.
> - 따라서 **같은 `groupId` 를 쓰는 일반 리스너가 있으면 커밋 위치를 서로 덮어씁니다.** 한쪽이 오프셋 100 을, 다른 쪽이 3 을 커밋하면 97건이 재처리되거나 유실됩니다.
> - 인스턴스를 2대로 늘리면 **두 대가 같은 파티션을 동시에 읽습니다.** 리밸런스가 없으니 아무도 말리지 않습니다. **중복 소비**입니다.
>
> **증상**: 배포로 파드를 2개로 늘린 순간 처리 건수가 정확히 2배. 에러는 없음.
> **해결**: 명시 할당 리스너는 **전용 `groupId` 를 주고 인스턴스 1대로만** 돌리세요. 상시 서비스가 아니라 **일회성 도구**로 쓰는 것이 원칙입니다.

적절한 용도는 좁습니다 — **재처리 도구**(장애 구간 오프셋 범위를 다시 흘리는 배치), **특정 파티션 디버깅**("orders-2 만 유독 랙이 쌓인다"를 로컬에서 재현) 정도입니다.

---

## 3-7. 여러 리스너의 그룹 분리

같은 토픽을 두 관심사가 각각 전량 소비하는 팬아웃입니다.

```java
@KafkaListener(id = "inv",  groupId = "s03-inventory",    topics = "orders")
public void inventory(OrderCreated e) { log.info("재고 {}", e.orderId()); }
@KafkaListener(id = "noti", groupId = "s03-notification", topics = "orders")
public void notify(OrderCreated e) { log.info("알림 {}", e.orderId()); }
```

**결과** (concurrency=1 로 단순화)
```
INFO 13103 --- [       inv-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO 13103 --- [      noti-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-notification: partitions assigned: [orders-0, orders-1, orders-2]
INFO 13103 --- [       inv-0-C-1] c.e.o.step03.FanoutListener              : 재고 ORD-0002
INFO 13103 --- [      noti-0-C-1] c.e.o.step03.FanoutListener              : 알림 ORD-0002
... (ORD-0004 이하 9건 전부 두 리스너에 각각) ...
```

각 리스너가 9건씩, 총 18번 호출됩니다. **그룹이 다르면 오프셋도 완전히 별개**라 한쪽이 밀려도 다른 쪽에 영향이 없습니다.

### 컨테이너를 코드로 꺼내 보기 — `id` 를 준 리스너는 `KafkaListenerEndpointRegistry` 로 조회합니다

```java
@Autowired KafkaListenerEndpointRegistry registry;
registry.getListenerContainerIds().forEach(id -> {
    var c = registry.getListenerContainer(id);
    log.info("{} running={} group={} assigned={}", id, c.isRunning(), c.getGroupId(), c.getAssignedPartitions());
});
```

**결과**
```
INFO 13103 --- [           main] c.e.o.step03.RegistryReporter            : inv running=true group=s03-inventory assigned=[orders-0, orders-1, orders-2]
INFO 13103 --- [           main] c.e.o.step03.RegistryReporter            : noti running=true group=s03-notification assigned=[orders-0, orders-1, orders-2]
```

`id` 를 안 주면 `org.springframework.kafka.KafkaListenerEndpointContainer#0` 같은 자동 생성 이름이 됩니다. **순서에 의존하는 이름**이라 리스너를 하나 추가하면 번호가 밀립니다. `stop()`/`start()` 로 제어할 계획이라면 `id` 는 필수입니다(Step 10).

> ⚠️ **함정 — 같은 클래스에 같은 `groupId` 리스너를 두 개 두면 각자 일부만 받습니다**
> ```java
> @KafkaListener(topics = "orders", groupId = "s03-inventory")
> public void reserveStock(OrderCreated e) { ... }      // 재고 예약
>
> @KafkaListener(topics = "orders", groupId = "s03-inventory")   // ← 복붙하고 groupId 를 안 고침
> public void writeAuditLog(OrderCreated e) { ... }     // 감사 로그
> ```
> 의도는 "한 메시지로 두 가지 일을 한다"였겠지만, 실제로는 **컨슈머 두 개가 같은 그룹에 조인해 파티션을 나눠 갖습니다.**
> ```
> INFO 13103 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer : s03-inventory: partitions assigned: [orders-0, orders-1]
> INFO 13103 --- [ntainer#1-0-C-1] o.s.k.l.KafkaMessageListenerContainer : s03-inventory: partitions assigned: [orders-2]
> ```
> **증상**: "9건을 보냈는데 재고 예약은 6건, 감사 로그는 3건만 찍힙니다." 에러 없음, 랙 0, 커밋 정상. 랙이 0 이라 모니터링에도 안 잡힙니다. **"왜 내 리스너가 3건 중 1건만 받지?"** 의 정체가 대개 이것입니다.
> **해결**: ① 한 메서드 안에서 두 작업을 순서대로 호출한다(같은 오프셋, 권장) ② 정말 독립적이어야 하면 **그룹을 나눈다**(`s03-inventory` / `s03-audit`).
> **예방**: `id` 중복은 기동 실패로 잡히지만 `groupId` 중복은 안 잡힙니다. `groupId` 를 상수 클래스에 모아 두고 리스너마다 다른 상수를 쓰게 하는 것이 현실적인 방어책입니다.

---

## 3-8. `topicPattern` — 정규식 구독

토픽 이름을 정규식으로 구독합니다. 멀티테넌시나 `orders.v1`/`orders.v2` 처럼 접미사가 붙는 구조에서 씁니다.

```java
@KafkaListener(id = "pat", groupId = "s03-pattern", topicPattern = "orders.*")
public void anyOrders(ConsumerRecord<String, OrderCreated> rec) { log.info("{} ← {}", rec.topic(), rec.key()); }
```

**결과**
```
INFO 13103 --- [       pat-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s03-pattern-1, groupId=s03-pattern] Subscribed to pattern: 'orders.*'
INFO 13103 --- [       pat-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-pattern: partitions assigned: [orders-0, orders-1, orders-2, orders.DLT-0, orders.DLT-1, orders.DLT-2]
```

`orders.*` 는 `orders.DLT` 까지 잡습니다. **정규식이라 `.` 은 임의의 한 글자**입니다. `orders.v1`, `orders.v2` 만 원했다면 `orders\\.v[0-9]+` 로 이스케이프해야 합니다.

`kt --create --topic orders.v2 ...` 로 새 토픽을 만들면, 기본 설정에서는 **4분 뒤에야** 붙습니다.

**결과**
```
INFO 13103 --- [       pat-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-pattern: partitions revoked: [orders-0, orders-1, orders-2, orders.DLT-0, orders.DLT-1, orders.DLT-2]
INFO 13103 --- [       pat-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-pattern: partitions assigned: [orders-0, orders-1, orders-2, orders.DLT-0, orders.DLT-1, orders.DLT-2, orders.v2-0]
```

**토픽을 만든 뒤 최대 5분이 지나야 붙습니다.** 컨슈머가 메타데이터를 갱신하는 주기 `metadata.max.age.ms` 가 300000ms 이기 때문입니다.
`spring.kafka.consumer.properties.metadata.max.age.ms: 10000` 으로 10초까지 줄일 수 있습니다.

> ⚠️ 이 값을 낮추면 **모든 컨슈머가 10초마다 메타데이터를 요청**합니다. 컨슈머가 수백 개면 브로커에 부담이고, 새 토픽을 발견할 때마다 **리밸런스가 일어나** 그동안 처리가 멈춥니다(`partitions revoked` 로그가 그 증거입니다).
> 실습에서는 10초로 낮춰도 되지만, 운영에서는 기본값을 두고 **"새 토픽은 5분 뒤부터 소비된다"를 전제로 설계**하는 편이 낫습니다.

---

## 3-9. 배치 리스너 맛보기

기본은 레코드 하나씩 리스너를 호출합니다. `setBatchListener(true)` 를 켜면 `poll()` 이 가져온 레코드를 **`List` 로 한 번에** 넘깁니다.

```java
@Bean("batchFactory")
public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchFactory(
        ConsumerFactory<String, OrderCreated> cf) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, OrderCreated>();
    f.setConsumerFactory(cf);
    f.setConcurrency(3);
    f.setBatchListener(true);              // ← 이 한 줄
    return f;
}

@KafkaListener(id = "batch", groupId = "s03-batch", topics = "orders", containerFactory = "batchFactory")
public void onBatch(List<OrderCreated> events) {
    log.info("배치 {}건: {}", events.size(), events.stream().map(OrderCreated::orderId).toList());
}
```

**결과**
```
INFO 13103 --- [     batch-0-C-1] c.e.o.step03.BatchListener               : 배치 3건: [ORD-0002, ORD-0004, ORD-0009]
INFO 13103 --- [     batch-1-C-1] c.e.o.step03.BatchListener               : 배치 3건: [ORD-0001, ORD-0006, ORD-0008]
INFO 13103 --- [     batch-2-C-1] c.e.o.step03.BatchListener               : 배치 3건: [ORD-0003, ORD-0005, ORD-0007]
```

**한 배치 안의 레코드가 항상 같은 파티션에서 온 것은 아닙니다.** 여기서는 스레드마다 파티션이 하나뿐이라 그렇게 보일 뿐, 한 스레드가 파티션 2개를 맡으면 두 파티션의 레코드가 한 리스트에 섞여 옵니다.
배치 크기의 **상한**은 `spring.kafka.consumer.max-poll-records`(기본 500)이고 하한은 없습니다 — 토픽에 3건뿐이면 3건짜리 배치가 옵니다. 10,000건 소비 실측입니다(건당 0.5ms 작업, `concurrency=3`).

| 방식 | `max-poll-records` | 소요 | 처리량 |
|---|---:|---:|---:|
| 레코드 리스너 | 500 | 7.2초 | 1,390 msg/s |
| 배치 리스너 | 500 | 1.9초 | 5,260 msg/s |
| 배치 리스너 | 50 | 2.4초 | 4,170 msg/s |

**3.8배 빨라졌습니다.** 이득은 리스너 호출 오버헤드가 아니라 **커밋 횟수**와, DB 접근을 `saveAll` 로 묶을 수 있다는 데서 옵니다.

> ⚠️ 배치 리스너로 바꾸면 **메서드 시그니처가 반드시 `List<T>` 여야 합니다.** 단건으로 두면 기동 시 터집니다.
> ```
> ERROR 13103 --- [           main] o.s.b.SpringApplication : Application run failed
> java.lang.IllegalStateException: A batch listener must return a List of results
>   or the method must accept a List of ConsumerRecord/Message/payload
> ```
> 반대로 팩토리는 그대로 두고 시그니처만 `List` 로 바꾸면, 리스트에 **원소 하나짜리**가 계속 들어옵니다. **이건 에러가 안 납니다.**

> 💡 배치 리스너의 진짜 이야기는 **부분 실패**입니다. 5건 중 3번째가 터졌을 때 어디까지 커밋되는가 — 여기서 조용한 유실과 재처리가 발생합니다. `AckMode` 와 `BatchListenerFailedException`, `nack(index, sleep)` 은 [Step 06](../step-06-ack-modes/) 에서 전부 다룹니다.

---

## 3-10. 리스너가 느리면 무슨 일이 벌어지는가

컨슈머는 살아 있다는 신호를 두 가지로 보냅니다.

| 신호 | 주체 | 관련 설정 | 기본값 |
|---|---|---|---:|
| 하트비트 | 백그라운드 스레드 | `heartbeat.interval.ms` / `session.timeout.ms` | 3s / 45s |
| 폴 주기 | **리스너 스레드 본인** | `max.poll.interval.ms` | 300s |

리스너가 오래 걸려도 하트비트는 백그라운드에서 계속 나갑니다. 그래서 `session.timeout.ms` 로는 안 걸립니다. 대신 **`poll()` 을 다시 부르기까지의 간격**이 `max.poll.interval.ms` 를 넘으면, 컨슈머 자신이 "나 너무 오래 걸렸다"고 판단해 **스스로 그룹을 나갑니다.** 재현하기 쉽게 30초로 낮춥니다.

```yaml
spring: { kafka: { consumer: { max-poll-records: 3, properties: { max.poll.interval.ms: 30000 } } } }
```

```java
@KafkaListener(id = "slow", groupId = "s03-slow", topics = "orders")
public void slow(OrderCreated e) throws InterruptedException {
    log.info("처리 시작 {}", e.orderId());
    Thread.sleep(20_000);                      // 건당 20초
    log.info("처리 완료 {}", e.orderId());
}
```

한 번의 poll 이 3건을 가져오므로 3건 × 20초 = 60초 > 30초 입니다.

**결과**
```
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 시작 ORD-0002
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 완료 ORD-0002
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 시작 ORD-0004
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 완료 ORD-0004
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 시작 ORD-0009
INFO 13103 --- [ad | s03-slow-1 ] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s03-slow-1, groupId=s03-slow] Member consumer-s03-slow-1-7d3e0f11 sending LeaveGroup request to coordinator 127.0.0.1:9092 (id: 2147483646 rack: null) due to consumer poll timeout has expired. This means the time between subsequent calls to poll() was longer than the configured max.poll.interval.ms, which typically implies that the poll loop is spending too much time processing messages.
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 완료 ORD-0009
ERROR 13103 --- [      slow-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Consumer exception
java.lang.IllegalStateException: This error handler cannot process 'org.apache.kafka.clients.consumer.CommitFailedException's; no record information is available
Caused by: org.apache.kafka.clients.consumer.CommitFailedException: Offset commit cannot be completed since the consumer is not part of an active group for auto partition assignment; it is likely that the consumer was kicked out of the group.
INFO 13103 --- [      slow-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s03-slow-1, groupId=s03-slow] (Re-)joining group
INFO 13103 --- [      slow-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s03-slow: partitions assigned: [orders-0]
INFO 13103 --- [      slow-0-C-1] c.e.o.step03.SlowListener                : 처리 시작 ORD-0002
```

읽어야 할 순서가 있습니다.

1. `ORD-0002`, `ORD-0004` 는 처리를 마쳤습니다. 하지만 **커밋은 배치 끝에 하므로 아직 안 됐습니다**(`AckMode.BATCH`).
2. 세 번째 레코드를 처리하던 중 60초를 넘겨 **`LeaveGroup request` 를 스스로 보냅니다.** 로그 레벨은 `INFO` 이고, 스레드는 리스너 스레드가 아니라 하트비트 스레드 `[ad | s03-slow-1 ]` 입니다.
3. 리스너는 그것도 모르고 `ORD-0009` 를 마칩니다.
4. 커밋하려는 순간 `CommitFailedException`. **이미 그룹에서 쫓겨났으니 커밋할 자격이 없습니다.**
5. 다시 조인해 `orders-0` 을 받고, **커밋이 안 된 `ORD-0002` 부터 다시 처리합니다.**

> ⚠️ **함정 — `max.poll.interval.ms` 초과는 조용히 무한 재처리 루프를 만듭니다**
> 위 로그의 마지막 줄이 `처리 시작 ORD-0002` 입니다. **원점입니다.**
> 느린 근본 원인이 그대로면 이 사이클이 영원히 반복됩니다. 60초마다 리밸런스 → 커밋 실패 → 재처리.
> **증상**: 랙이 줄지 않고, 같은 주문 ID 가 로그에 반복해서 찍히고, `kcg --describe` 의 `CURRENT-OFFSET` 이 제자리입니다. 애플리케이션은 죽지 않습니다.
> 부작용이 더 나쁩니다 — **이미 성공한 처리를 반복**하므로, 리스너가 멱등하지 않으면 재고가 세 번 차감됩니다.
> **해결(권장 순서)**:
> 1. `max-poll-records` 를 줄인다 — 한 배치가 짧아져 poll 간격이 줄어듭니다. **부작용이 가장 적습니다.**
> 2. 리스너를 빠르게 만든다 — 외부 API 호출·DB 왕복을 배치화하거나 타임아웃을 건다.
> 3. `max.poll.interval.ms` 를 늘린다 — **마지막 수단.** 진짜로 죽은 컨슈머를 감지하는 시간도 같이 늘어납니다.
> 4. 처리를 별도 스레드풀로 넘긴다 — poll 은 계속 돌지만, **커밋 시점이 처리 완료와 어긋나 메시지 유실 위험**이 생깁니다. Step 06 을 읽고 결정하세요.

> 💡 적정값 계산식은 단순합니다. **`max.poll.records` × (레코드 1건의 최악 처리시간) × 안전계수 2**
> 건당 최악 200ms, `max-poll-records=500` 이면 500 × 0.2s × 2 = **200초** — 기본값 300초 안이라 안전합니다. 건당 최악이 2초로 늘면 2000초가 필요한데, 이때는 `max.poll.interval.ms` 를 올릴 게 아니라 **`max-poll-records` 를 50 으로 줄이는** 것이 옳습니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `@KafkaListener` | 애노테이션 → 엔드포인트 → 레지스트리 → `Concurrent...Container` → N개 자식 컨테이너 |
| 자식 컨테이너 1개 | `KafkaConsumer` 1개 + 전용 스레드 1개. 컨슈머는 스레드 안전하지 않다 |
| 스레드 이름 | `[ntainer#<리스너>-<자식>-C-<세대>]`. `id` 를 주면 읽을 수 있게 바뀐다 |
| `groupId` 우선순위 | 애노테이션 `groupId` > 애노테이션 `id` > `spring.kafka.consumer.group-id` |
| 그룹 ID 부재 | **기동 실패**. `No group.id found in consumer config...` |
| 같은 그룹 / 다른 그룹 | 같으면 파티션을 나눠 각자 일부만, 다르면 각자 전량(팬아웃) |
| **`concurrency` > 파티션** | **초과분은 `partitions assigned: []`. 경고 없음, 처리량 개선 0%** |
| `concurrency` < 파티션 | 한 스레드가 여러 파티션. 부하 불균형 |
| 병렬도 상한 | **= 파티션 수.** 인스턴스를 늘려도 못 넘는다 |
| 노는 컨슈머 찾기 | `kcg --describe --group X --members` 의 `#PARTITIONS 0` |
| `topicPartitions` | 그룹 관리 우회. 리밸런스 없음, **커밋은 그룹에 씀**, 인스턴스 2대면 중복 소비 |
| `partitionOffsets` | 커밋 위치를 무시하고 지정 오프셋부터. 재처리 도구용 |
| `topicPattern` | 새 토픽 인식까지 최대 `metadata.max.age.ms`(기본 5분). 발견 시 리밸런스 |
| 배치 리스너 | `setBatchListener(true)` + `List<T>` 를 **동시에**. 크기 상한은 `max-poll-records` |
| `max.poll.interval.ms` 초과 | `LeaveGroup request ... poll timeout has expired` → 커밋 실패 → **무한 재처리** |
| 우선 대응 | `max.poll.interval.ms` 를 늘리기 전에 **`max-poll-records` 를 줄일 것** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **로그를 직접 대조**하세요.

1. `concurrency` 를 파티션보다 크게 잡고 `partitions assigned: []` 를 로그와 CLI 양쪽에서 확인하기
2. 두 컨슈머 그룹으로 팬아웃 구현하고, 총 호출 횟수가 2배가 되는 것을 세기
3. `topicPartitions` 로 파티션 1만 소비하는 리스너 만들기
4. 이름 붙인 컨테이너 팩토리 두 개(`fast` / `ordered`)를 만들어 리스너별로 선택하기
5. 4번의 `fast` 리스너를 배치 리스너로 전환하기
6. 느린 리스너로 `max.poll.interval.ms` 초과를 유발하고 재처리를 관측하기

---

## 다음 단계

리스너가 메시지를 받는 구조는 잡았습니다. 그런데 `OrderCreated` 객체가 어떻게 바이트에서 객체로 되살아났는지는 그냥 넘어갔습니다.
다음 스텝에서 `JsonSerializer`/`JsonDeserializer` 의 내부를 열어, `__TypeId__` 헤더가 만들어 내는 **패키지 경로 결합**과, 역직렬화 실패 메시지 하나가 **파티션을 영원히 멈춰 세우는 포이즌 필** 문제를 다룹니다.

→ [Step 04 — 직렬화와 역직렬화](../step-04-serialization/)

---

## 실습 파일

세 파일을 순서대로 씁니다. 먼저 `Practice.java` 를 `src/main/java/com/example/order/step03/` 에 넣고 프로필 `step03` 으로 실행해 3-1 ~ 3-10 의 로그를 재현합니다. 이 파일은 **한 번에 다 켜면 서로 간섭하므로** 내부 프로필(`step03`, `step03-slow`, `step03-batch`)로 나눠 두었습니다. 그다음 `Exercise.java` 의 6문제를 직접 채워 넣어 보고, 마지막으로 `Solution.java` 로 정답과 해설을 대조합니다.

### Practice.java

교재 본문의 모든 예제를 절 번호 주석(`// [3-4] concurrency=5`)과 함께 담은 단일 실행 파일입니다.

- 파일 상단 주석에 **실행 명령과 함께 확인할 CLI 명령 3개**(`kt --describe`, `kcg --describe --members`, `kcg --list`)가 적혀 있습니다. 특히 `--members` 는 3-4 의 "노는 컨슈머"를 확인하는 유일한 방법이니 반드시 실행하세요.
- `Seeder` 가 `ApplicationRunner` 로 기동 직후 `OrderCreated.of(1)` ~ `of(9)` 를 발행합니다. 키가 결정적이라 파티션 배정이 항상 같으므로, **교재의 `ORD-0002 → orders-0` 이 여러분 화면에서도 그대로**여야 합니다. 다르면 파티션 수가 3이 아닙니다.
- `[3-4]` 의 `ConcurrencyDemoConfig` 는 `app.demo.concurrency` 프로퍼티(기본 3)로 concurrency 를 바꿉니다. `--args='--spring.profiles.active=step03 --app.demo.concurrency=5'` 로 재기동해 세 경우를 차례로 관찰하는 구조라, 코드를 고칠 필요가 없습니다.
- `[3-6]` 의 `ReplayListener` 는 `initialOffset` 을 쓰므로 **커밋된 오프셋을 무시합니다.** 몇 번을 재기동해도 같은 메시지를 다시 읽습니다. 버그가 아니라 의도된 동작입니다.
- `[3-10]` 의 `SlowListener` 는 `@Profile("step03-slow")` 로 분리돼 있습니다. 건당 20초를 자므로 **켜면 다른 실습이 사실상 멈춥니다.** 마지막에 단독으로 켜세요. 이 프로필은 `max.poll.interval.ms=30000`, `max-poll-records=3` 을 `@Bean` 으로 덮어씁니다.
- `RegistryReporter` 가 기동 완료 후 `KafkaListenerEndpointRegistry` 의 컨테이너 목록·그룹·할당 파티션을 한 번 출력합니다. **어떤 리스너가 지금 켜져 있는지 헷갈릴 때 이 출력부터 보세요.**

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 뼈대는 그대로 컴파일됩니다.

- **문제 1·6** 은 설정을 바꾸고 **로그를 관찰**하는 문제입니다. 답을 코드로 쓰는 게 아니라 관찰 결과를 `// 관찰 기록:` 자리에 적는 형태입니다.
- **문제 2·3·4·5** 는 실제로 애노테이션과 `@Bean` 을 작성하는 문제입니다. 컴파일이 되더라도 로그를 확인하기 전까지는 정답이 아닙니다 — 이 스텝의 오답은 대부분 **컴파일도 되고 기동도 되는** 종류입니다.
- 문제 4 의 `orderedFactory` 는 `concurrency` 를 **1로** 두는 것이 요구사항입니다. 3으로 둬도 컴파일되고 동작하지만 요구사항(순서 보장)을 만족하지 못합니다.
- 문제 6 은 `Thread.sleep` 을 쓰므로 **실행에 2~3분이 걸립니다.** 중간에 끊지 말고 `LeaveGroup request` 로그가 뜰 때까지 기다리세요.
- 모든 문제의 컨슈머 그룹은 `s03e-` 접두사를 씁니다. `Practice.java` 의 `s03-` 그룹과 오프셋이 섞이지 않게 하기 위해서입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석입니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 의 핵심은 코드가 아니라 로그입니다. `partitions assigned: []` 가 2줄 나오고 `WARN` 이 없다는 것, 그리고 `kcg --describe --members` 의 `#PARTITIONS 0` 이 유일한 발견 수단이라는 것을 주석으로 정리했습니다.
- **정답 2** 는 `groupId` 만 다르게 준 리스너 두 개입니다. `id` 를 함께 붙인 이유(스레드 이름 가독성)와, `id` 만 주고 `groupId` 를 생략해도 동작하지만 **그룹 이름과 컨테이너 이름이 결합돼 나중에 바꾸기 어려워지는** 문제를 설명합니다.
- **정답 3** 은 `@TopicPartition(topic="orders", partitions={"1"})` 입니다. `concurrency` 를 5로 줘도 컨테이너가 **1개만** 생기는 이유 — Spring 이 파티션 개수를 알기 때문 — 를 3-4 의 함정과 대비해 적었습니다. 전용 `groupId` 를 쓰는 이유(커밋 충돌 회피)도 함께 있습니다.
- **정답 4** 는 `fastFactory(concurrency=3)` / `orderedFactory(concurrency=1)` 두 빈입니다. `@Bean` 메서드 이름이 곧 빈 이름이므로 `containerFactory` 문자열과 **정확히 일치**해야 하고, 틀리면 `No bean named ... available` 로 기동이 실패한다는 점을 강조합니다.
- **정답 5** 는 `f.setBatchListener(true)` 와 시그니처를 `List<OrderCreated>` 로 바꾸는 두 가지를 **동시에** 해야 한다는 것이 답입니다. 한쪽만 하면 `A batch listener must return a List of results` 로 기동 실패하거나, 원소 1개짜리 리스트가 계속 오는 무증상 오작동이 됩니다.
- **정답 6** 은 `max-poll-records=3` + `max.poll.interval.ms=30000` + 건당 20초 sleep 조합입니다. 왜 `session.timeout.ms` 로는 안 걸리는지(하트비트는 별도 스레드에서 계속 나감), 그리고 해결책 우선순위가 **`max-poll-records` 축소 → 리스너 최적화 → `max.poll.interval.ms` 증가** 순인 이유를 정리했습니다.

```java file="./Solution.java"
```
