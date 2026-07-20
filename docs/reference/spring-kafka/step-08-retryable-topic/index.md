# Step 08 — @RetryableTopic 논블로킹 재시도

> **학습 목표**
> - 블로킹 재시도가 파티션을 막는 문제를 **재시도 전용 토픽**으로 푸는 구조를 설명한다
> - `@RetryableTopic` 한 줄로 자동 생성되는 토픽 목록을 `kt --list` 로 확인한다
> - 실패 메시지 한 건이 `orders` → `orders-retry-0` → `-retry-1` → `-retry-2` → `orders.DLT` 로 이동하는 전 과정을 로그로 추적한다
> - **같은 키 3건으로 순서가 깨지는 것을 재현하고 타임라인 표로 기록한다**
> - `@DltHandler` 와 `KafkaHeaders.ORIGINAL_*` 헤더로 실패 원인을 복원한다
> - 백오프가 길면 retry 토픽 안에서 **다시 블로킹**된다는 것을 파티션 pause 로그로 확인한다
> - 블로킹/논블로킹 선택 기준표를 만들고, 두 가지를 조합하는 설정을 작성한다
>
> **선행 스텝**: Step 07 — 에러 처리와 재시도
> **예상 소요**: 90분

---

## 8-0. 실습 준비

이 스텝은 컨슈머 그룹 `s08-inventory` 를 씁니다. 토픽은 `orders`(파티션 3) 하나로 시작하고, **나머지는 Spring 이 만듭니다.**

```bash
# 앱을 끈 상태에서
kcg --group s08-inventory --topic orders --reset-offsets --to-earliest --execute
kt --list
```

**결과** — 아직은 깨끗합니다

```
orders
orders.DLT
payments
```

`orders-retry-*` 가 하나도 없는 것을 확인하세요. 8-2 에서 앱을 한 번 띄우는 것만으로 네 개가 생깁니다.

---

## 8-1. 블로킹 재시도의 한계 — 그리고 발상의 전환

Step 07 의 결론은 이랬습니다. `DefaultErrorHandler` 의 백오프 대기는 **컨슈머 스레드 안의 `Thread.sleep`** 이고, 그 스레드는 `poll()` 도 겸하기 때문에 **재시도 중에는 그 파티션의 모든 일이 멈춥니다.** `FixedBackOff(10000L, 5L)` 하나로 `orders-1` 이 50초 정지했고 LAG 이 47까지 올랐습니다.

여기서 나오는 질문은 하나입니다. **"실패한 메시지를 붙잡고 있지 말고, 어딘가에 치워 두면 안 되나?"**

그게 논블로킹 재시도입니다. 실패하면 그 레코드를 **별도의 retry 토픽으로 발행**하고, 원본 토픽의 오프셋은 **즉시 커밋**합니다. 본선 컨슈머는 바로 다음 레코드로 넘어갑니다. 치워 둔 레코드는 retry 토픽을 구독하는 **다른 컨슈머**가 백오프 시간이 지난 뒤 다시 처리합니다.

```
                    ┌─────────────────────────────────────────────┐
                    │  topic: orders  (partitions=3)              │
   프로듀서 ───────▶│  [98][99][100][101][102] ...                │
                    └───────────────┬─────────────────────────────┘
                                    │ group=s08-inventory
                                    ▼
                          ┌───────────────────┐
                          │ InventoryListener │  ① 100 처리 → 예외
                          └─────────┬─────────┘  ② 100 을 retry-0 으로 발행
                                    │            ③ 오프셋 101 로 즉시 커밋
                                    │            ④ 101 처리 시작 ← 안 막힘 ★
                                    ▼
        ┌──────────────────┐  실패  ┌──────────────────┐  실패  ┌──────────────────┐
        │ orders-retry-0   │──────▶│ orders-retry-1   │──────▶│ orders-retry-2   │
        │ (1초 뒤 처리)     │       │ (2초 뒤 처리)     │       │ (4초 뒤 처리)     │
        └──────────────────┘       └──────────────────┘       └────────┬─────────┘
                                                                       │ 재시도 소진
                                                                       ▼
                                                     ┌──────────────────────────────┐
                                                     │ orders.DLT  @DltHandler 수신 │
                                                     └──────────────────────────────┘
```

**본선 파티션은 ③에서 이미 커밋되었습니다.** 재시도가 10분이 걸리든 1시간이 걸리든 `orders-1` 의 처리량에는 아무 영향이 없습니다. `max.poll.interval.ms` 초과도 없습니다.

대가는 하나입니다. **100 번 레코드가 101, 102 보다 늦게 처리됩니다.** 이것이 8-6 의 주제이고, 이 스텝에서 딱 하나만 기억해야 한다면 그 절입니다.

---

## 8-2. `@RetryableTopic` — 애노테이션 한 줄로 시작

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        kafkaTemplate = "kafkaTemplate")
@KafkaListener(topics = "orders", groupId = "s08-inventory")
public void onMessage(ConsumerRecord<String, OrderCreated> record) {
    if (record.partition() == 1 && record.offset() == 42) {
        throw new RemoteApiException("재고 API 타임아웃: " + record.value().orderId());
    }
    log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
}
```

`attempts = "4"` 는 **최초 시도를 포함한 총 호출 횟수 4번**입니다. Step 07 의 `FixedBackOff(interval, maxAttempts)` 가 "재시도 횟수"였던 것과 **세는 방식이 다릅니다.** 4번 시도 = 최초 1 + 재시도 3 = retry 토픽 3개.

값이 전부 **문자열**인 것에도 이유가 있습니다. `attempts = "${app.retry.attempts:4}"` 처럼 프로퍼티 플레이스홀더를 쓸 수 있게 하기 위해서입니다.

앱을 띄우고 다시 목록을 봅니다.

```bash
kt --list
```

**결과**

```
orders
orders-retry-1000
orders-retry-2000
orders-retry-4000
orders-dlt
orders.DLT
payments
```

토픽 4개가 생겼습니다. 그런데 이름이 예상과 다릅니다.

### 토픽 명명 규칙 — 기본값은 "지연 시간 접미사"

`@RetryableTopic` 의 `topicSuffixingStrategy` 기본값은 **`SUFFIX_WITH_DELAY_VALUE`** 입니다. retry 토픽 이름 뒤에 **인덱스가 아니라 백오프 지연값(ms)** 이 붙습니다. 그래서 `orders-retry-1000`, `-2000`, `-4000` 이 됩니다.

| 속성 | 기본값 | 결과 |
|---|---|---|
| `retryTopicSuffix` | `-retry` | `orders-retry...` |
| `dltTopicSuffix` | `-dlt` | `orders-dlt` |
| `topicSuffixingStrategy` | `SUFFIX_WITH_DELAY_VALUE` | `orders-retry-1000` |
| `topicSuffixingStrategy` | `SUFFIX_WITH_INDEX_VALUE` | `orders-retry-0` |

**이 코스의 규약은 `orders-retry-0` 과 `orders.DLT`** 입니다(코스 개요의 토픽 표). 기본값 그대로 두면 Step 07 에서 만든 `orders.DLT` 와 새로 생긴 `orders-dlt` 가 **따로 놀게 됩니다.** 맞춰 줍니다.

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        retryTopicSuffix = "-retry",
        dltTopicSuffix = ".DLT",
        kafkaTemplate = "kafkaTemplate")
```

**결과**

```
orders
orders-retry-0
orders-retry-1
orders-retry-2
orders.DLT
payments
```

`kt --describe --topic orders-retry-0` 로 파티션 수도 봅니다.

**결과**

```
Topic: orders-retry-0	TopicId: qL8vN1RhSem3xT4dPzKcAg	PartitionCount: 3	ReplicationFactor: 1	Configs: segment.bytes=1073741824
	Topic: orders-retry-0	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders-retry-0	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders-retry-0	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
```

**파티션 3, 복제 1.** `@RetryableTopic` 은 원본 토픽의 파티션 수를 읽어 오지 않습니다. `numPartitions` 기본값이 `-1` 이라 `KafkaAdmin` 의 기본(브로커 `num.partitions`, 실습 환경은 3)을 따랐을 뿐입니다. 원본이 12 파티션이면 **retry 토픽만 3 파티션이 되어** 병렬도가 조용히 1/4 로 떨어집니다. `numPartitions = "12"` 로 명시하세요.

> ⚠️ **함정 — 기본 DLT 접미사는 `-dlt` 이고, Step 07 의 `.DLT` 와 다른 토픽이다**
> Step 07 에서 `DeadLetterPublishingRecoverer` 가 쓰던 목적지는 `<topic>.DLT` 였습니다. `@RetryableTopic` 의 기본값은 `<topic>-dlt` 입니다. **점이냐 하이픈이냐** 하나 차이로 완전히 다른 토픽입니다.
> **증상**: 기존 DLT 모니터링·알림은 `orders.DLT` 를 보고 있는데 실패 메시지는 전부 `orders-dlt` 로 쌓입니다. 에러는 없습니다. 대시보드가 조용해서 "장애가 없어졌다"고 착각합니다.
> **해결**: `dltTopicSuffix = ".DLT"` 를 명시하거나, 팀 전체 규약을 `-dlt` 로 통일하세요. 둘 중 무엇이든 **한 프로젝트에 두 규칙이 공존하면 안 됩니다.**

---

## 8-3. 실패 메시지 한 건의 여정을 끝까지 따라가기

`orders-1@42` 하나만 항상 실패하도록 두고 전체 흐름을 봅니다. `attempts = "4"`, `delay = 1000`, `multiplier = 2.0` 입니다.

**결과** — 기동 로그

```
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-retry-0-0, orders-retry-0-1, orders-retry-0-2]
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-retry-1-0, orders-retry-1-1, orders-retry-1-2]
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-retry-2-0, orders-retry-2-1, orders-retry-2-2]
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders.DLT-0, orders.DLT-1, orders.DLT-2]
```

**리스너 하나를 선언했는데 컨테이너가 5개 떴습니다.** 본선 1 + retry 3 + DLT 1. 전부 `s08-inventory` **같은 컨슈머 그룹**입니다.

**결과** — 실패 레코드의 이동

```
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$JourneyDemo        : 처리 완료 orders-1@41
WARN  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$JourneyDemo        : ★ 실패 topic=orders attempt=1 ORD-0043
INFO  13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-0-1
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$JourneyDemo        : 처리 완료 orders-1@43   ← ★ 0.4ms 뒤. 본선은 안 막혔다
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$JourneyDemo        : 처리 완료 orders-1@44

WARN  13309 --- [ntainer#1-1-C-1] c.e.o.step08.Practice$JourneyDemo        : ★ 실패 topic=orders-retry-0 attempt=2 ORD-0043   (t=1006ms)
INFO  13309 --- [ntainer#1-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-1-1

WARN  13309 --- [ntainer#2-1-C-1] c.e.o.step08.Practice$JourneyDemo        : ★ 실패 topic=orders-retry-1 attempt=3 ORD-0043   (t=3012ms)
INFO  13309 --- [ntainer#2-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-2-1

WARN  13309 --- [ntainer#3-1-C-1] c.e.o.step08.Practice$JourneyDemo        : ★ 실패 topic=orders-retry-2 attempt=4 ORD-0043   (t=7021ms)
INFO  13309 --- [ntainer#3-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders.DLT-1
ERROR 13309 --- [ntainer#4-1-C-1] c.e.o.step08.Practice$JourneyDemo        : DLT 수신 ORD-0043 origin=orders-1@42 ex=RemoteApiException
```

읽어야 할 것이 넷입니다.

1. **스레드 이름이 매번 바뀝니다.** `[ntainer#0-1-C-1]` → `#1` → `#2` → `#3` → `#4`. 컨테이너가 다르다는 증거입니다. Step 07 에서는 재시도가 전부 같은 스레드였습니다.
2. **`처리 완료 orders-1@43` 이 실패 직후에 찍혔습니다.** 본선은 0.4ms 만에 다음 레코드로 넘어갔습니다.
3. 시각이 **1006ms → 3012ms → 7021ms**. 대기 간격이 1초 → 2초 → 4초로 두 배씩 늘었습니다.
4. 마지막은 `orders.DLT-1`. **원본 파티션 번호 1 이 그대로 유지**됩니다.

```bash
kcg --describe --group s08-inventory
```

**결과** — 재시도가 도는 도중(t=3s)에 찍은 것

```
GROUP          TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CLIENT-ID
s08-inventory  orders          0          100             100             0    consumer-s08-inventory-1
s08-inventory  orders          1          100             100             0    consumer-s08-inventory-2
s08-inventory  orders          2          100             100             0    consumer-s08-inventory-3
s08-inventory  orders-retry-0  1          1               1               0    consumer-s08-inventory-5
s08-inventory  orders-retry-1  1          1               1               0    consumer-s08-inventory-8
s08-inventory  orders-retry-2  1          0               1               1    consumer-s08-inventory-11
```

**`orders-1` 의 LAG 이 0 입니다.** Step 07 의 같은 상황에서는 47이었습니다. 밀린 것은 `orders-retry-2` 의 1건뿐입니다.

| 구간 | 본선 평균 지연 | 본선 최대 지연 | 실패 레코드의 총 지연 |
|---|---:|---:|---:|
| Step 07 블로킹 (`FixedBackOff(10s, 5)`) | 24,300ms | 50,180ms | 50,180ms |
| Step 08 논블로킹 (`attempts=4`, 1s×2배) | **8ms** | **43ms** | 7,021ms |

**본선 지연 24.3초 → 8ms.** 3,000배 빨라졌습니다. 그런데 이 숫자만 보고 "논블로킹이 우월하다"고 결론 내리면 8-6 에서 크게 다칩니다.

---

## 8-4. 백오프 전략과 토픽 개수의 관계

`@Backoff` 는 Spring Retry 의 애노테이션입니다. 네 가지 속성이 의미가 있습니다.

```java
@Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000, random = true)
```

| 속성 | 의미 | 기본값 |
|---|---|---|
| `delay` | 첫 대기(ms) | 1000 |
| `multiplier` | 배수. 1.0 이면 고정 간격 | 0 (= 고정) |
| `maxDelay` | 상한(ms). 0 이면 무제한 | 0 |
| `random` | 지터. 실제 대기를 `[delay, delay*multiplier)` 에서 무작위 선택 | false |

`random = true` 는 **동시에 실패한 수천 건이 정확히 같은 순간에 재시도하는 것(thundering herd)** 을 막습니다. 외부 API 장애 복구 직후가 정확히 그런 상황입니다.

### 시도 횟수와 토픽 개수

**retry 토픽 개수는 `attempts - 1` 이 기본**입니다. 각 재시도마다 대기 시간이 다르니 토픽도 따로 있어야 하기 때문입니다.

| 설정 | 대기 시퀀스 | retry 토픽 | 전체 토픽 수 |
|---|---|---|---:|
| `attempts=3`, `delay=1000`, `multiplier=2.0` | 1s, 2s | `-retry-0`, `-retry-1` | 4 |
| `attempts=4`, `delay=1000`, `multiplier=2.0` | 1s, 2s, 4s | `-retry-0` ~ `-2` | 5 |
| `attempts=5`, `delay=1000`, `multiplier=2.0`, `maxDelay=3000` | 1s, 2s, 3s, 3s | `-retry-0` ~ `-3` | 6 |
| `attempts=5`, `delay=1000` (multiplier 없음) | 1s, 1s, 1s, 1s | **`-retry` 1개** | 3 |

마지막 줄이 중요합니다. **`multiplier` 를 안 주면 모든 재시도의 대기가 같으므로 토픽을 나눌 이유가 없습니다.** Spring 이 알아서 `orders-retry` **하나**로 합치고 4번 순환시킵니다. 접미사에 숫자도 안 붙습니다.

### `SameIntervalTopicReuseStrategy` — 같은 간격 구간을 합칠지

`maxDelay` 로 잘린 뒷부분은 대기가 전부 같습니다. 위 세 번째 줄의 `3s, 3s` 가 그렇습니다. 이걸 토픽 하나로 합칠지 정하는 것이 `sameIntervalTopicReuseStrategy` 입니다.

```java
@RetryableTopic(
        attempts = "6",
        backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 4000),
        sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC)
```

| 전략 | 대기 시퀀스 1s,2s,4s,4s,4s | 생성 토픽 |
|---|---|---|
| `MULTIPLE_TOPICS` (기본) | 각각 따로 | `-retry-0`, `-1`, `-2`, `-3`, `-4` (5개) |
| `SINGLE_TOPIC` | 뒤 3개를 합침 | `-retry-0`, `-1`, `-retry` (3개) |

> 💡 Spring Kafka 3.0 에서 `fixedDelayTopicStrategy`(타입 `FixedDelayStrategy`)가 **deprecated** 되고 `sameIntervalTopicReuseStrategy`(`SameIntervalTopicReuseStrategy`)로 대체됐습니다. 값 이름(`SINGLE_TOPIC` / `MULTIPLE_TOPICS`)은 같습니다. 3.1.4 에서 옛 속성을 쓰면 컴파일은 되지만 deprecation 경고가 뜨고, **두 속성을 같이 주면 새 쪽이 이깁니다.**

> ⚠️ **함정 — `attempts` 를 크게 잡으면 토픽이 그만큼 늘어난다**
> `attempts = "10"` 은 retry 토픽 **9개**를 만듭니다. 파티션 3이면 파티션 27개, 컨슈머 컨테이너 9개, 컨슈머 스레드 27개가 리스너 **하나**에서 생깁니다. 리스너가 5개면 토픽 50개, 파티션 150개입니다.
> **증상**: 기동이 눈에 띄게 느려지고(토픽 생성이 순차적입니다), 브로커의 파티션 수가 통제를 벗어나며, `kt --list` 가 읽을 수 없는 상태가 됩니다. 리밸런스 시간도 파티션 수에 비례해 늘어납니다.
> **해결**: `attempts` 는 **4~5를 넘기지 마세요.** 더 오래 기다려야 하면 횟수가 아니라 `maxDelay` 를 키우고 `SameIntervalTopicReuseStrategy.SINGLE_TOPIC` 으로 토픽을 합치세요. 다만 그 경우 8-8 의 함정에 걸립니다.

---

## 8-5. `@DltHandler` — 종착지 처리

Step 07 에서는 DLT 리스너를 `@KafkaListener(topics = "orders.DLT")` 로 따로 만들었습니다. `@RetryableTopic` 을 쓰면 **같은 클래스 안에 `@DltHandler` 메서드 하나**면 됩니다.

```java
@DltHandler
public void onDlt(OrderCreated event,
                  @Header(KafkaHeaders.ORIGINAL_TOPIC)     String topic,
                  @Header(KafkaHeaders.ORIGINAL_PARTITION) int    partition,
                  @Header(KafkaHeaders.ORIGINAL_OFFSET)    long   offset,
                  @Header(KafkaHeaders.EXCEPTION_FQCN)     String exFqcn,
                  @Header(KafkaHeaders.EXCEPTION_MESSAGE)  String exMessage) {
    log.error("DLT 수신 order={} origin={}-{}@{} ex={} msg={}",
            event.orderId(), topic, partition, offset, exFqcn, exMessage);
}
```

**헤더 이름이 Step 07 과 다릅니다.** `@RetryableTopic` 인프라는 `kafka_dlt-original-topic` 이 아니라 `kafka_original-topic` 을 씁니다.

| 상수 | 실제 헤더 | 내용 |
|---|---|---|
| `KafkaHeaders.ORIGINAL_TOPIC` | `kafka_original-topic` | `orders` (**retry 토픽이 아니라 최초 출발지**) |
| `KafkaHeaders.ORIGINAL_PARTITION` | `kafka_original-partition` | 4바이트 int |
| `KafkaHeaders.ORIGINAL_OFFSET` | `kafka_original-offset` | 8바이트 long |
| `KafkaHeaders.EXCEPTION_FQCN` | `kafka_exception-fqcn` | 예외 FQCN |
| `KafkaHeaders.EXCEPTION_MESSAGE` | `kafka_exception-message` | 예외 메시지 |
| `KafkaHeaders.EXCEPTION_STACKTRACE` | `kafka_exception-stacktrace` | 스택트레이스 전문 |
| `RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS` | `retry_topic-attempts` | 현재 시도 횟수 |
| `RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP` | `retry_topic-backoff-timestamp` | **이 시각 전에는 처리하지 말라** |
| `RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP` | `retry_topic-original-timestamp` | 최초 발행 시각 |

```bash
kcc --topic orders-retry-1 --from-beginning --property print.headers=true --property print.key=true
```

**결과** (읽기 쉽게 줄바꿈했습니다)

```
kafka_original-topic:orders,
kafka_original-partition:\x00\x00\x00\x01,
kafka_original-offset:\x00\x00\x00\x00\x00\x00\x00\x2A,
kafka_exception-fqcn:com.example.order.step08.RemoteApiException,
kafka_exception-message:재고 API 타임아웃: ORD-0043,
retry_topic-attempts:\x00\x00\x00\x03,
retry_topic-backoff-timestamp:1735689603012,
retry_topic-original-timestamp:1735689600006,
__TypeId__:com.example.order.domain.OrderCreated
	ORD-0043	{"orderId":"ORD-0043","customerId":1013,"sku":"SKU-002","quantity":2,"amount":11000,...}
```

**`retry_topic-backoff-timestamp` 가 이 스텝의 숨은 주인공**입니다. 8-8 에서 다시 봅니다. 그리고 `retry_topic-attempts` 는 **바이너리 int**, `retry_topic-backoff-timestamp` 는 **문자열로 찍힌 epoch ms** 라 형식이 섞여 있습니다. 직접 파싱하지 말고 `@Header(name = ..., required = false) byte[]` 로 받아 확인하세요.

### `DltStrategy` — DLT 처리마저 실패하면

```java
@RetryableTopic(attempts = "4", dltStrategy = DltStrategy.FAIL_ON_ERROR)
```

| 값 | 동작 |
|---|---|
| `FAIL_ON_ERROR` (기본) | `@DltHandler` 가 예외를 던지면 **그 DLT 파티션이 막힙니다.** 오프셋이 안 넘어갑니다 |
| `ALWAYS_RETRY_ON_ERROR` | DLT 처리 실패 시 **DLT 토픽으로 다시 발행**. 원인이 안 고쳐지면 무한 루프 |
| `NO_DLT` | DLT 자체를 안 만듭니다. 소진되면 **버립니다** |

> ⚠️ **함정 — `NO_DLT` 는 Step 07 의 "조용히 버린다" 로 되돌아가는 스위치다**
> "DLT 토픽이 늘어나는 게 부담스럽다"는 이유로 `dltStrategy = DltStrategy.NO_DLT` 를 켜는 경우가 있습니다. 이 순간 재시도를 소진한 메시지는 **어디에도 남지 않고 사라집니다.** `LAG` 은 0, 에러 로그는 `KafkaBackoffException` 도 아닌 평범한 WARN 한 줄입니다.
> **증상**: retry 토픽에는 메시지가 흘러간 흔적이 있는데 **최종 목적지가 없습니다.** `kt --list` 에 `orders.DLT` 가 아예 없으니 "원래 그런 설계인가" 하고 넘어가게 됩니다.
> **해결**: `NO_DLT` 는 **재시도 자체가 부가 기능인 경우**(예: 조회수 집계)에만 쓰세요. 그 경우에도 소진 시점에 카운터 지표를 올려 두어야 합니다. 기본값 `FAIL_ON_ERROR` 를 유지하는 것이 원칙입니다.

---

## 8-6. ⚠️ 핵심 함정 — 논블로킹 재시도는 메시지 순서를 깨뜨린다

**이 절이 Step 08 의 전부입니다.**

주문 `ORD-0001` 에 대해 세 이벤트를 순서대로 발행합니다. 키가 같으므로 **셋 다 같은 파티션(`orders-1`)** 에 들어가고, 오프셋은 100, 101, 102 입니다.

```java
template.send("orders", "ORD-0001", OrderEvent.of("ORD-0001", OrderState.CREATED));    // offset 100
template.send("orders", "ORD-0001", OrderEvent.of("ORD-0001", OrderState.UPDATED));    // offset 101
template.send("orders", "ORD-0001", OrderEvent.of("ORD-0001", OrderState.CANCELLED));  // offset 102
```

컨슈머는 상태를 맵에 반영합니다. 그리고 **첫 번째(CREATED)만 한 번 실패**하도록 만듭니다. 두 번째 시도에서는 성공합니다 — 실제 일시적 장애가 딱 이렇게 동작합니다.

```java
@RetryableTopic(attempts = "4", backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                dltTopicSuffix = ".DLT", kafkaTemplate = "kafkaTemplate")
@KafkaListener(topics = "orders", groupId = "s08-order-state")
public void onMessage(ConsumerRecord<String, OrderEvent> record) {
    OrderEvent e = record.value();
    if (e.status() == OrderState.CREATED && FIRST_TRY.getAndSet(false)) {
        throw new RemoteApiException("재고 API 타임아웃");   // 최초 1회만 실패
    }
    STATE.put(e.orderId(), e.status());
    log.info("상태 반영 {} → {}  (topic={} t={}ms)", e.orderId(), e.status(), record.topic(), elapsed());
}
```

**결과**

```
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : 발행 ORD-0001 CREATED → orders-1@100  (t=0ms)
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : 발행 ORD-0001 UPDATED → orders-1@101  (t=2ms)
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : 발행 ORD-0001 CANCELLED → orders-1@102  (t=3ms)
WARN  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : ★ 실패 ORD-0001 CREATED (topic=orders t=8ms)
INFO  13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-0-1
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : 상태 반영 ORD-0001 → UPDATED    (topic=orders t=12ms)
INFO  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : 상태 반영 ORD-0001 → CANCELLED  (topic=orders t=15ms)
INFO  13309 --- [ntainer#1-1-C-1] c.e.o.step08.Practice$OrderStateDemo     : 상태 반영 ORD-0001 → CREATED    (topic=orders-retry-0 t=1012ms)
INFO  13309 --- [           main] c.e.o.step08.Practice$OrderStateDemo     : 최종 상태 ORD-0001 = CREATED     ← ★★ 취소된 주문이 살아났다
```

### 타임라인

| 시각 | 토픽 | 이벤트 | 결과 | `STATE["ORD-0001"]` |
|---:|---|---|---|---|
| t=0ms | — | CREATED 발행 | `orders-1@100` | (없음) |
| t=2ms | — | UPDATED 발행 | `orders-1@101` | (없음) |
| t=3ms | — | CANCELLED 발행 | `orders-1@102` | (없음) |
| t=8ms | `orders` | CREATED 처리 | **실패** → `orders-retry-0` 발행, 오프셋 101 커밋 | (없음) |
| t=12ms | `orders` | UPDATED 처리 | 성공 | `UPDATED` |
| t=15ms | `orders` | CANCELLED 처리 | 성공 | `CANCELLED` |
| **t=1012ms** | `orders-retry-0` | **CREATED 재처리** | **성공** | **`CREATED`** ← 오염 |

**취소된 주문이 1초 뒤에 되살아났습니다.** 예외는 하나도 없고, `kcg --describe` 의 LAG 은 전부 0 이며, 로그에도 ERROR 가 없습니다. `@DltHandler` 는 호출되지 않았습니다 — 재시도가 **성공**했으니까요. 이 코스가 계속 말하는 **"에러 없이 조용히 잘못 동작하는"** 상태의 교과서적 사례입니다.

블로킹 재시도였다면 이런 일이 없습니다. `orders-1@100` 이 성공하거나 DLT 로 갈 때까지 101, 102 는 아예 poll 되지 않기 때문입니다. **Step 07 의 "파티션이 멈춘다" 는 단점은, 동시에 순서 보장이라는 장점의 다른 이름입니다.**

> ⚠️ **함정 — 논블로킹 재시도는 같은 키의 순서 보장을 전면 포기한다**
> Kafka 의 순서 보장은 **"하나의 파티션 안"** 에서만 성립합니다. `@RetryableTopic` 은 실패한 레코드를 **다른 토픽으로 옮기므로** 그 순간 보장 범위 밖으로 나갑니다. 같은 키라도 마찬가지입니다.
> **증상 3종**: ① 상태 전이가 역행합니다(취소 → 생성, 환불 → 결제). ② 카운터가 어긋납니다(재고 차감/복원 순서 뒤바뀜). ③ **재현이 안 됩니다.** 실패가 없으면 순서가 지켜지므로, 외부 API 가 흔들린 몇 분 동안만 데이터가 오염되고 로그에는 흔적이 없습니다.
> **해결 3가지**:
> ① **순서가 중요하면 논블로킹을 쓰지 마세요.** 블로킹 + 짧은 백오프 + 즉시 DLT 가 정답입니다.
> ② 이벤트에 **버전/시퀀스 번호**를 넣고 컨슈머가 `if (incoming.version() <= current.version()) return;` 으로 역행을 거부하게 만드세요(Step 13 의 멱등 컨슈머).
> ③ 이벤트를 **상태 전이(delta)가 아니라 전체 상태(snapshot)** 로 설계하면 늦게 온 오래된 스냅샷을 버릴 수 있습니다.

> 💡 판단 문장 하나로 줄이면 이렇습니다. **"이 메시지가 1초 늦게 처리돼도 결과가 같은가?"** 같으면 논블로킹, 다르면 블로킹입니다.

---

## 8-7. retry 토픽의 컨슈머 그룹과 파티션

8-3 에서 컨테이너가 5개 떴습니다. 컨슈머 그룹은 몇 개일까요.

```bash
kcg --list
```

**결과**

```
s08-inventory
```

**하나입니다.** retry 토픽과 DLT 는 본선과 **같은 그룹 ID** 를 씁니다. `kcg --describe` 를 보면 한 그룹이 5개 토픽을 구독하고 있습니다.

```bash
kcg --describe --group s08-inventory
```

**결과**

```
GROUP          TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CLIENT-ID
s08-inventory  orders          0          300             300             0    consumer-s08-inventory-1
s08-inventory  orders          1          300             300             0    consumer-s08-inventory-2
s08-inventory  orders          2          300             300             0    consumer-s08-inventory-3
s08-inventory  orders-retry-0  1          3               3               0    consumer-s08-inventory-5
s08-inventory  orders-retry-1  1          3               3               0    consumer-s08-inventory-8
s08-inventory  orders-retry-2  1          3               3               0    consumer-s08-inventory-11
s08-inventory  orders.DLT      1          3               3               0    consumer-s08-inventory-14
```
(LAG 0 인 나머지 파티션 행은 생략했습니다)

`concurrency: 3` 이 본선뿐 아니라 **retry 토픽에도 그대로 적용**되어 컨슈머가 3+3+3+3+3 = 15개 만들어졌습니다. retry 토픽에는 보통 메시지가 거의 없는데도 스레드 12개가 놀고 있습니다.

```java
@RetryableTopic(attempts = "4", concurrency = "1", ...)
```

`concurrency = "1"` 로 retry 컨테이너만 1 스레드로 줄입니다. 본선의 `concurrency` 는 그대로입니다.

**결과** — `concurrency = "1"` 적용 후

```
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-retry-0-0, orders-retry-0-1, orders-retry-0-2]
```

두 번째 줄의 파티션 3개를 **한 스레드**가 전부 맡습니다. 스레드 15개 → 6개. 다만 8-8 의 이유로 **retry 토픽의 `concurrency` 를 1로 줄이면 대기가 직렬화**되므로, 실패율이 높은 리스너에서는 신중해야 합니다.

> 💡 **실무 팁 — 컨슈머 그룹이 하나라 오프셋 리셋이 편합니다.**
> `kcg --group s08-inventory --topic orders-retry-0 --reset-offsets --to-earliest --execute` 처럼 토픽 단위로 각각 리셋해야 하고, 실습을 되돌리려면 **retry 토픽 3개 + DLT 까지 4번** 리셋해야 합니다. `--all-topics` 를 쓰면 한 번에 됩니다.

---

## 8-8. ⚠️ 함정 — retry 토픽의 지연은 "최소" 보장일 뿐이고, 길면 다시 블로킹된다

`@Backoff(delay = 60000)` 이면 "60초 뒤에 처리된다"고 읽기 쉽습니다. 틀렸습니다. **"60초 전에는 처리되지 않는다"** 가 맞습니다.

Spring Kafka 의 논블로킹 재시도에는 **스케줄러가 없습니다.** `orders-retry-0` 컨슈머는 평범한 컨슈머라서 메시지를 즉시 `poll()` 해 옵니다. 그리고 `KafkaBackoffAwareMessageListenerAdapter` 가 `retry_topic-backoff-timestamp` 헤더를 보고 판단합니다.

```
retry-0 컨슈머 스레드 [ntainer#1-0-C-1]
  poll() → 레코드 (backoff-timestamp = now + 1000ms)
     ▼
  KafkaBackoffAwareMessageListenerAdapter
     아직 시간이 안 됐다 → KafkaBackoffException 을 던진다
     ▼
  KafkaConsumerBackoffManager
     ① consumer.pause(orders-retry-0-1)      ← ★ 그 파티션을 통째로 멈춘다
     ② consumer.seek(orders-retry-0-1, 그 오프셋)
     ▼
  1000ms 뒤 → resume → 다시 poll → 이번엔 시간이 됐다 → 리스너 호출
```

**② `pause` 가 핵심입니다.** 대기 중에는 그 retry 토픽 파티션 전체가 멈춥니다. `Thread.sleep` 이 아니라 `pause` 라서 `poll()` 은 계속 돌고 `max.poll.interval.ms` 초과는 나지 않습니다 — 이 점이 Step 07 과 결정적으로 다릅니다. 하지만 **그 파티션의 뒤 레코드가 대기한다는 사실 자체는 동일합니다.**

**결과** — `delay = 30000` 으로 두고 짧은 간격으로 3건이 실패했을 때

```
INFO  13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-0-1
INFO  13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-0-1
INFO  13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-0-1
DEBUG 13309 --- [ntainer#1-0-C-1] o.s.k.l.KafkaConsumerBackoffManager      : Backing off partition orders-retry-0-1 until 2025-01-01T00:00:30.014Z (dueTimestamp=1735689630014)
INFO  13309 --- [ntainer#1-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Paused consumption from: [orders-retry-0-1]
INFO  13309 --- [ntainer#1-0-C-1] c.e.o.step08.Practice$LongBackoffDemo    : 재처리 ORD-0043 (topic=orders-retry-0 대기 30,012ms)
DEBUG 13309 --- [ntainer#1-0-C-1] o.s.k.l.KafkaConsumerBackoffManager      : Backing off partition orders-retry-0-1 until 2025-01-01T00:00:30.019Z (dueTimestamp=1735689630019)
INFO  13309 --- [ntainer#1-0-C-1] c.e.o.step08.Practice$LongBackoffDemo    : 재처리 ORD-0044 (topic=orders-retry-0 대기 30,018ms)
INFO  13309 --- [ntainer#1-0-C-1] c.e.o.step08.Practice$LongBackoffDemo    : 재처리 ORD-0045 (topic=orders-retry-0 대기 30,021ms)
```

여기까지는 괜찮습니다. 세 건의 due time 이 거의 같아서 한 번의 pause 로 셋이 함께 풀렸습니다. 문제는 **due time 이 흩어져 있을 때**입니다. `@Backoff(random = true)` 를 켜거나, `maxDelay` 가 다른 두 리스너가 같은 retry 토픽을 공유하면 파티션 안에서 due time 이 뒤죽박죽이 됩니다. `delay = 300000`(5분) + `random = true` 로 재현했습니다.

| retry-0 파티션에 쌓인 순서 | due time | 실제 처리 시각 | **초과 지연** |
|---|---:|---:|---:|
| ORD-E | t=900s | t=900s | 0s |
| ORD-F | t=310s (이미 지남) | **t=900s** | **590s** |
| ORD-G | t=320s (이미 지남) | **t=900s** | **580s** |

**ORD-F 와 ORD-G 는 진작에 처리됐어야 하는데 앞의 ORD-E 가 파티션을 900초까지 붙잡고 있어서 함께 기다렸습니다.** 백오프 5분이 실제로는 15분이 됐습니다.

> ⚠️ **함정 — 긴 백오프는 retry 토픽 안에서 다시 블로킹이다**
> `@RetryableTopic` 의 장점은 "**본선** 파티션을 안 막는다"입니다. **retry 토픽 파티션은 그대로 막힙니다.** `maxDelay` 를 시간 단위로 크게 잡는 순간, retry 토픽이 Step 07 의 본선 토픽과 똑같은 병목이 됩니다.
> **증상**: `kcg --describe` 에서 `orders` 의 LAG 은 0인데 **`orders-retry-N` 의 LAG 만 계속 쌓입니다.** 처리는 되지만 백오프 설정값보다 훨씬 늦게 됩니다. 지연 지표를 안 보면 발견이 안 됩니다.
> **해결**:
> ① retry 토픽의 **파티션 수와 `concurrency` 를 늘려** 대기를 병렬화하세요. 파티션 하나가 막혀도 다른 파티션은 돕니다.
> ② `@Backoff(random = true)` 로 due time 을 분산시키면 **한 파티션에 몰리는** 것을 줄일 수 있지만, 위 표처럼 **역전**도 함께 만듭니다. 트레이드오프입니다.
> ③ 백오프가 **분 단위를 넘어가면 재시도 토픽이 아니라 스케줄러/배치로 설계**하세요. DLT 에 넣고 별도 잡이 주기적으로 재처리하는 편이 예측 가능합니다.

---

## 8-9. 블로킹 vs 논블로킹 — 선택 기준표

| 축 | 블로킹 (`DefaultErrorHandler`) | 논블로킹 (`@RetryableTopic`) |
|---|---|---|
| **순서 보장** | **보존됨** (`seek` 으로 제자리 반복) | **깨짐** (다른 토픽으로 이동) |
| **파티션 블로킹** | 본선 파티션이 백오프 총합만큼 정지 | 본선은 즉시 통과. **retry 토픽은 막힘** |
| **처리 지연(정상 메시지)** | 실패 1건에 뒤 전부가 대기 (실측 24.3초) | 영향 없음 (실측 8ms) |
| **최대 백오프** | `max.poll.interval.ms` 의 1/3 이하 (~100초) | 사실상 무제한 (`pause` 방식이라 poll timeout 없음) |
| **토픽 개수** | 원본 + DLT = 2 | 원본 + retry `attempts-1` + DLT = **최대 6~10** |
| **컨슈머 스레드** | `concurrency` 그대로 | `concurrency × (attempts+1)` 까지 증가 |
| **운영 복잡도** | 낮음. 토픽 2개, 그룹 1개 | 높음. 토픽/파티션 폭증, 리밸런스 시간 증가 |
| **재시도 가시성** | 로그와 `LAG` 뿐. 밖에서 안 보임 | **retry 토픽에 실물이 쌓여 `kcc` 로 눈으로 확인** |
| **재시작 시 재시도 상태** | **소실**. 처음부터 다시 | **보존**. retry 토픽에 남아 있음 |
| **적합한 실패 유형** | 짧은 순간 장애(DB 락 경합, 커넥션 재수립) | 긴 외부 장애(외부 API 다운, 서드파티 점검) |

두 줄로 요약하면 이렇습니다. **블로킹은 "정확성을 지키고 처리량을 포기"** 하고, **논블로킹은 "처리량을 지키고 정확성을 포기"** 합니다. 그리고 **재시작 시 재시도 상태 보존**은 논블로킹의 잘 안 알려진 강점입니다. 블로킹 재시도 중에 앱을 배포하면 재시도 진행 상황이 통째로 사라지고 처음부터 다시 시작하지만, retry 토픽의 메시지는 브로커에 남아 있어 새 인스턴스가 이어받습니다.

### 결정 트리

```
실패한 메시지가 5초 뒤에 똑같이 하면 성공할 가능성이 있는가?
│
├─ 없다 (검증 오류, 스키마 불일치, 잔액 부족)
│    → 재시도 금지. addNotRetryableExceptions / @RetryableTopic(exclude=...)
│      → 즉시 DLT
│
└─ 있다
     │
     └─ 이 메시지가 뒤 메시지보다 늦게 처리돼도 결과가 같은가?
          │
          ├─ 아니다 (주문 상태 전이, 계좌 잔액, 재고 증감)
          │    → 블로킹. DefaultErrorHandler
          │      + 짧은 백오프 (총합 ≤ max.poll.interval.ms / 3)
          │      + DeadLetterPublishingRecoverer
          │
          └─ 그렇다 (알림 발송, 검색 색인, 통계 집계, 캐시 갱신)
               │
               └─ 얼마나 기다려야 하나?
                    ├─ 수 초         → 블로킹도 충분. 굳이 토픽을 늘리지 말 것
                    ├─ 수십 초 ~ 수 분 → 논블로킹 @RetryableTopic ★
                    └─ 수십 분 이상   → 즉시 DLT + 별도 배치 재처리 잡
```

| 상황 | 선택 | 설정 |
|---|---|---|
| 주문 상태 전이(`OrderStatus`) | 블로킹 | `ExponentialBackOffWithMaxRetries(4)` 0.5s/2배/상한 5s + DLT |
| 계좌 잔액 증감 | 블로킹 | `FixedBackOff(500L, 3L)` + DLT. 재시도보다 **멱등성**이 우선 |
| 결제 알림 SMS 발송 | 논블로킹 | `attempts=4`, `delay=5000`, `multiplier=3.0` |
| 검색 색인 갱신 | 논블로킹 | `attempts=5`, `delay=10000`, `maxDelay=60000`, `SINGLE_TOPIC` |
| 스키마 검증 실패 | 재시도 없음 | `exclude = InvalidOrderException.class` → 즉시 DLT |
| 역직렬화 실패 | 재시도 없음 | `ErrorHandlingDeserializer` + 기본 비재시도 목록 (Step 04·07) |

---

## 8-10. 둘을 같이 쓰기 — 짧은 블로킹 뒤에 논블로킹

가장 실용적인 구성은 둘 중 하나를 고르는 게 아니라 **겹쳐 쓰는 것**입니다. 대부분의 순간 장애는 **500ms 뒤 재시도로 끝납니다.** 그런 실패까지 retry 토픽을 거치면 토픽만 지저분해지고 순서도 깨집니다.

**"컨테이너에서 짧게 2회 블로킹 재시도 → 그래도 실패하면 retry 토픽으로"** 가 정답에 가깝습니다.

```java
@Configuration
public class RetryTopicGlobalConfig extends RetryTopicConfigurationSupport {

    /** ★ 논블로킹으로 넘기기 전에 컨테이너에서 짧게 블로킹 재시도한다. */
    @Override
    protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
        blockingRetries
                .retryOn(RemoteApiException.class, SocketTimeoutException.class)
                .backOff(new FixedBackOff(500L, 2L));   // 0.5초 × 2회 = 1초
    }

    /** retry/DLT 발행기를 손보는 훅. 반환 타입이 팩토리라는 점에 주의하세요. */
    @Override
    protected Consumer<DeadLetterPublishingRecovererFactory> configureDeadLetterPublishingContainerFactory() {
        return factory -> factory.setDeadLetterPublishingRecovererCustomizer(
                recoverer -> recoverer.setStripPreviousExceptionHeaders(true));
    }
}
```

`configureBlockingRetries` 에 등록한 예외만 **먼저 블로킹으로** 재시도합니다. 여기서 성공하면 retry 토픽에 아무것도 안 남고 **순서도 유지됩니다.** 실패하면 그때 `@RetryableTopic` 의 논블로킹 경로로 넘어갑니다.

**결과** — `RemoteApiException` 이 5번째 시도에서 성공하는 경우

```
WARN  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$HybridDemo         : 실패 attempt=1 topic=orders       t=0ms
WARN  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$HybridDemo         : 실패 attempt=2 topic=orders       t=504ms    ← 블로킹
WARN  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$HybridDemo         : 실패 attempt=3 topic=orders       t=1008ms   ← 블로킹
INFO  13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders-retry-0-1
WARN  13309 --- [ntainer#1-0-C-1] c.e.o.step08.Practice$HybridDemo         : 실패 attempt=4 topic=orders-retry-0 t=2014ms  ← 논블로킹
INFO  13309 --- [ntainer#2-0-C-1] c.e.o.step08.Practice$HybridDemo         : 성공 attempt=5 topic=orders-retry-1 t=4023ms
```

본선에서 3번(0ms, 504ms, 1008ms) 시도한 뒤에야 retry 토픽으로 넘어갑니다. **1초 안에 회복되는 장애는 순서를 지키며 처리**되고, 그보다 긴 장애만 순서를 포기합니다.

> ⚠️ **함정 — 총 시도 횟수는 곱이 아니라 합이지만, 대기 시간은 곱해질 수 있다**
> 블로킹 3회 × 논블로킹 4회를 "총 12번"으로 착각하기 쉽습니다. 실제로는 **블로킹 재시도가 각 논블로킹 단계마다 반복**됩니다. retry-0 컨슈머에서 실패해도 거기서 또 블로킹 2회를 돌고 나서 retry-1 로 갑니다.
> **증상**: 예상 총 시간이 1+2+4=7초인데 실측이 11초입니다. 각 단계에서 1초씩 블로킹이 추가된 것입니다. retry 토픽 파티션도 그만큼 오래 막힙니다(8-8).
> **해결**: 블로킹 백오프는 **1초 이내 총합**으로 아주 짧게 두세요. `FixedBackOff(500L, 2L)` 정도가 상한입니다.

### 애노테이션 대신 빈으로 — `RetryTopicConfiguration`

리스너가 여러 개면 애노테이션을 복붙하게 됩니다. 빈 하나로 **토픽 단위 전역 설정**을 할 수 있습니다.

```java
@Bean
public RetryTopicConfiguration ordersRetryTopicConfig(KafkaTemplate<String, Object> template) {
    return RetryTopicConfigurationBuilder
            .newInstance()
            .includeTopic("orders")                       // 이 토픽을 구독하는 모든 리스너에 적용
            .maxAttempts(4)
            .exponentialBackoff(1000, 2.0, 10000)
            .suffixTopicsWithIndexValues()                // orders-retry-0, -1, -2
            .retryTopicSuffix("-retry")
            .dltSuffix(".DLT")
            .concurrency(1)
            .autoCreateTopics(true, 3, (short) 1)         // 파티션 3, 복제 1
            .notRetryOn(InvalidOrderException.class)      // 검증 실패는 즉시 DLT
            .doNotRetryOnDltFailure()                     // = DltStrategy.FAIL_ON_ERROR
            .create(template);
}
```

`@RetryableTopic` 애노테이션을 **하나도 안 붙여도** `orders` 를 구독하는 모든 `@KafkaListener` 에 적용됩니다. 애노테이션과 빈이 둘 다 있으면 **애노테이션이 이깁니다.**

| 방식 | 적용 범위 | 언제 |
|---|---|---|
| `@RetryableTopic` | 그 리스너 하나 | 리스너마다 정책이 다를 때 |
| `RetryTopicConfiguration` 빈 + `includeTopic` | 그 토픽을 구독하는 전부 | 팀 표준 정책 |
| `RetryTopicConfigurationSupport` 상속 | 애플리케이션 전역 인프라 | 블로킹 재시도 조합, 커스터마이저 |

---

## 8-11. ⚠️ 함정 — 토픽 자동 생성이 꺼진 운영 환경

`@RetryableTopic` 의 `autoCreateTopics` 기본값은 `"true"` 이고, 기동 시 `KafkaAdmin` 이 `NewTopic` 빈으로 retry 토픽과 DLT 를 만듭니다. 그런데 운영 클러스터는 보통 브로커의 `auto.create.topics.enable=false` 이고, 애플리케이션 계정에 **토픽 생성 ACL 이 없습니다**(`CREATE` 없이 `READ`/`WRITE` 만). `autoCreateTopics = "true"` 그대로 두고 권한만 없는 상태로 띄워 봅니다.

**결과** — 기동 로그

```
INFO  13309 --- [           main] o.s.k.core.KafkaAdmin                    : Could not configure topics
org.springframework.kafka.KafkaException: Failed to create topics; nested exception is
	java.util.concurrent.ExecutionException: org.apache.kafka.common.errors.TopicAuthorizationException:
	Authorization failed for topics [orders-retry-0, orders-retry-1, orders-retry-2, orders.DLT]
INFO  13309 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s08-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO  13309 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 3.104 seconds
```

**`KafkaAdmin` 이 실패했는데 애플리케이션은 정상 기동합니다.** `fatalIfBrokerNotAvailable` 기본값이 `false` 이기 때문입니다. `partitions assigned` 도 `Started` 도 정상이고, 본선 처리는 몇 시간이고 잘 돌아갑니다 — **최초의 실패가 발생하는 순간까지는.**

**결과** — 첫 실패 발생 시

```
WARN  13309 --- [ntainer#0-1-C-1] c.e.o.step08.Practice$JourneyDemo        : ★ 실패 topic=orders attempt=1 ORD-0043
ERROR 13309 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Dead-letter publication to orders-retry-0 failed for: orders-1@42
org.apache.kafka.common.errors.TimeoutException: Topic orders-retry-0 not present in metadata after 60000 ms.
ERROR 13309 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Error handler threw an exception
org.springframework.kafka.KafkaException: Dead-letter publication failed; nested exception is ...
```

**60초 침묵 뒤 실패**하고, 그 60초 동안 `orders-1` 파티션은 완전히 멈춥니다. 논블로킹을 쓰는 이유였던 "파티션을 안 막는다"가 정반대로 뒤집혔습니다. 게다가 발행이 실패했으므로 **그 레코드는 retry 토픽에도 DLT 에도 못 갑니다.** Step 07 의 "조용히 버림" 과 같은 결말입니다.

**해결**은 토픽을 **미리 만들고 자동 생성을 끄는 것**입니다.

```bash
for i in 0 1 2; do
  kt --create --topic orders-retry-$i --partitions 3 --replication-factor 1
done
kt --create --topic orders.DLT --partitions 3 --replication-factor 1
```

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = ".DLT",
        autoCreateTopics = "false",        // ★ 운영 필수
        kafkaTemplate = "kafkaTemplate")
```

> ⚠️ **함정 — `autoCreateTopics = "false"` 는 "토픽이 없어도 기동된다" 는 뜻이다**
> `false` 로 두면 Spring 은 토픽 생성을 시도하지 않을 뿐, **존재 여부를 검증하지도 않습니다.** 토픽 이름에 오타가 있어도(`dltTopicSuffix = ".DTL"`) 기동은 성공하고, 첫 실패가 날 때까지 아무도 모릅니다.
> **증상**: 배포 후 며칠 뒤 첫 장애에서 `Topic ... not present in metadata after 60000 ms` 와 함께 파티션이 60초 단위로 멈춥니다. 하필 **장애 대응 중**에 두 번째 장애가 터지는 셈입니다.
> **해결**: ① 사전 생성한 토픽 목록을 `kt --list` 로 **배포 체크리스트에 넣으세요.** ② 애플리케이션 기동 시 `AdminClient.describeTopics(...)` 로 존재를 검증하고 없으면 **기동을 실패시키세요.** 조용히 뜨는 것보다 안 뜨는 게 낫습니다. ③ 스테이징에서 **일부러 실패 메시지를 하나 흘려** 전체 경로를 한 번 태워 보세요. 이것이 유일하게 확실한 검증입니다.

> 💡 토픽 생성 권한·`auto.create.topics.enable`·retention 같은 브로커 쪽 설정은 [Kafka 코스 Step 09](../../kafka/step-09-retention-compaction/) 와 [Step 14](../../kafka/step-14-operations/) 를 참고하세요. retry 토픽의 `retention.ms` 는 **백오프 최대값보다 넉넉히** 잡아야 합니다. 백오프 7일인데 retention 이 3일이면 재처리 전에 메시지가 삭제됩니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 논블로킹의 아이디어 | 실패 레코드를 **retry 토픽으로 옮기고 원본은 즉시 커밋**. 본선 파티션 안 막힘 |
| `attempts` | **최초 시도 포함 총 호출 횟수.** `attempts=4` → retry 토픽 3개 |
| 토픽 명명 기본값 | `<topic>-retry-<지연ms>`, `<topic>-dlt`. **`SUFFIX_WITH_DELAY_VALUE` 가 기본** |
| 코스 규약 맞추기 | `topicSuffixingStrategy=SUFFIX_WITH_INDEX_VALUE`, `dltTopicSuffix=".DLT"` |
| 컨테이너 수 | 본선 1 + retry `attempts-1` + DLT 1. **컨슈머 그룹은 하나** |
| 실측 | 본선 지연 **24.3초(블로킹) → 8ms(논블로킹)** |
| **핵심 함정** | **순서가 깨진다.** 재시도된 CREATED 가 CANCELLED 뒤에 도착해 **취소된 주문이 부활** |
| 순서 깨짐의 특징 | 예외 없음, LAG 0, ERROR 로그 없음, **재현 불가** |
| `@DltHandler` | 같은 클래스에 두면 DLT 처리. 헤더는 `kafka_original-*` (Step 07 의 `kafka_dlt-*` 와 다름) |
| `DltStrategy` | `FAIL_ON_ERROR`(기본) / `ALWAYS_RETRY_ON_ERROR` / **`NO_DLT`(= 조용히 버림)** |
| 백오프의 실체 | 스케줄러 없음. `backoff-timestamp` 헤더 + **파티션 `pause`** |
| 긴 백오프 | **retry 토픽 안에서 다시 블로킹.** 분 단위를 넘으면 배치로 설계할 것 |
| 지연 보장 | "정확히 N초 뒤"가 아니라 **"N초 전에는 아님"** |
| 조합 | `configureBlockingRetries` 로 짧게 블로킹 → 실패 시 논블로킹 |
| 전역 설정 | `RetryTopicConfiguration` 빈 + `includeTopic`. 애노테이션이 우선 |
| 운영 함정 | `autoCreateTopics` 실패해도 **기동은 성공.** 첫 실패 때 60초 타임아웃 |
| 선택 기준 한 줄 | **"이 메시지가 1초 늦게 처리돼도 결과가 같은가?"** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 직접 실행해 `kt --list` 와 로그를 확인**하세요.

1. `@RetryableTopic(attempts = "4", backoff = @Backoff(delay = 1000, multiplier = 2.0))` 를 붙이고 기동한 뒤, `kt --list` 로 생성된 토픽 이름을 그대로 적기. 기본 접미사 전략이 무엇인지 이름에서 역추론하기
2. 코스 규약(`orders-retry-0` ~ `-2`, `orders.DLT`)에 맞도록 접미사와 접미사 전략을 지정하고, 지수 백오프 1s → 2s → 4s 의 시도 시각을 실측하기
3. `@DltHandler` 를 구현해 `KafkaHeaders.ORIGINAL_TOPIC` / `ORIGINAL_PARTITION` / `ORIGINAL_OFFSET` / `EXCEPTION_FQCN` 을 로그로 남기기. **원본 토픽이 `orders-retry-2` 가 아니라 `orders` 로 찍히는 것**을 확인하기
4. **같은 키 `ORD-0001` 의 3건(생성 → 수정 → 취소)으로 순서 깨짐을 재현하고**, 본문 8-6 형태의 타임라인 표를 채우기. 최종 상태가 `CREATED` 임을 확인하기
5. `InvalidOrderException` 을 `exclude` 에 등록해 **재시도 없이 즉시 DLT** 로 보내기. retry 토픽에 아무것도 안 쌓이는 것을 `kcc` 로 확인하기
6. 애노테이션을 전부 지우고 `RetryTopicConfiguration` 빈 하나로 같은 설정을 재현하기. `includeTopic("orders")` 로 두 리스너에 동시 적용되는 것을 확인하기

---

## 다음 단계

논블로킹 재시도는 파티션 블로킹 문제를 확실히 해결하지만, **순서 보장이라는 값을 치릅니다.** 두 방식 중 무엇을 쓸지는 성능이 아니라 **도메인의 성격**이 정합니다.

그런데 8-6 의 오염을 근본적으로 막으려면 재시도 설정만으로는 부족합니다. "재고를 차감하고 이벤트를 발행하는 것"이 **하나의 원자적 단위**여야 합니다. 다음 스텝에서 Kafka 트랜잭션을 다룹니다. 그리고 거기서 이 코스의 가장 흔한 오해 하나를 깹니다 — **`@Transactional` 하나로 DB 와 Kafka 를 묶을 수 있다는 믿음**입니다.

→ [Step 09 — 트랜잭션](../step-09-transactions/)

---

## 실습 파일

이 스텝도 Java 파일 세 개로 진행합니다. `Practice.java` 를 보조 프로필로 하나씩 켜 가며 8-2 ~ 8-11 의 로그를 재현하고, **특히 `step08-order` 프로필로 8-6 의 순서 깨짐을 눈으로 확인**하세요. 그다음 `Exercise.java` 의 6문제를 풀고 `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.order.step08` 패키지에 둡니다. ⚠️ **프로필을 바꿀 때마다 retry 토픽을 지우세요.** 접미사 전략이 다른 프로필끼리 토픽이 섞이면 로그를 읽을 수 없게 됩니다.

### Practice.java

본문 8-2 ~ 8-11 의 모든 예제를 절 번호 주석과 함께 nested static class 로 담은 실행 파일입니다.

- 프로필은 7개입니다. `step08-default`(기본 접미사 확인) / `step08-journey`(여정 추적) / `step08-order`(★ 순서 깨짐) / `step08-longbackoff`(retry 토픽 재블로킹) / `step08-hybrid`(블로킹+논블로킹) / `step08-builder`(빈 설정) / `step08-noautocreate`(운영 함정). 파일 상단 주석에 각각이 재현하는 절과 `application.yml` 에 추가할 두 줄이 정리돼 있습니다.
- **`step08-default` 는 반드시 제일 먼저 한 번만 실행하세요.** 기본 접미사로 `orders-retry-1000`, `orders-dlt` 를 만드는 프로필입니다. 확인 후 `kt --delete` 로 지워야 이후 프로필의 `kt --list` 결과가 교재와 일치합니다.
- `[8-6] OrderStateDemo` 가 이 파일의 핵심입니다. `ORD-0001` 로 3건을 발행하고 `AtomicBoolean FIRST_TRY` 로 **첫 CREATED 만 1회 실패**시킵니다. `STATE` 맵의 최종값을 `ApplicationRunner` 가 3초 뒤에 찍으므로, **마지막 줄이 `CANCELLED` 가 아니라 `CREATED` 인지** 반드시 확인하세요.
- `[8-8] LongBackoffDemo` 는 `delay = 30000` 으로 두고 3건을 연속 실패시킵니다. `logging.level.org.springframework.kafka.listener.KafkaConsumerBackoffManager=DEBUG` 를 켜야 `Backing off partition ...` 로그가 보입니다. 파일 주석에 그 설정 줄이 적혀 있습니다.
- `[8-11] NoAutoCreateDemo` 는 `autoCreateTopics = "false"` 로 두고 **토픽을 일부러 만들지 않은** 상태로 실행합니다. 60초 타임아웃을 실제로 겪어 보는 프로필이라 인내가 필요합니다. 확인 후 사전 생성 스크립트를 돌리고 다시 실행해 정상 동작을 대조하세요.
- `RemoteApiException` / `InvalidOrderException` 은 Step 07 과 같은 이름이지만 **`step08` 패키지에 다시 선언**했습니다. 두 스텝의 파일을 같은 프로젝트에 두면 패키지가 달라 충돌하지 않습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일은 되도록 뼈대를 남겨 두었습니다.

- **문제 1·2** 는 토픽 이름을 **관찰**하는 문제이고, **문제 3·5** 는 애노테이션 속성을 **설정**하는 문제, **문제 4** 는 순서 깨짐을 **재현**하는 문제, **문제 6** 은 빈으로 **재구성**하는 문제입니다. 순서대로 푸세요.
- ⚠️ **문제 1 과 문제 2 는 접미사 전략이 달라 서로 다른 토픽을 만듭니다.** 문제 1 을 확인한 뒤 `kt --delete --topic orders-retry-1000` 식으로 반드시 지우고 문제 2 로 넘어가세요. 안 지우면 `kt --list` 에 7개가 섞여 보입니다.
- **문제 4 는 코드를 거의 안 씁니다.** `// 관측 기록:` 주석의 타임라인 표를 채우는 문제입니다. 각 행의 "실제 처리 시각"과 "`STATE` 값"을 로그에서 옮겨 적고, 마지막 줄에 **최종 상태**를 적으세요.
- 각 문제 끝의 `// 확인:` 주석에 **기대 로그 한 줄**이 적혀 있습니다. 예를 들어 문제 5 의 확인 줄은 `Republishing failed record to orders.DLT-1` 이고, **`orders-retry-0` 이 로그에 한 번도 나오면 안 됩니다.**
- 문제 6 은 `@RetryableTopic` 애노테이션을 **전부 지운 상태**에서 풀어야 합니다. 애노테이션이 남아 있으면 그쪽이 이겨서 빈이 무시되고, 답이 틀렸는지 맞았는지 구분이 안 됩니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 코드보다 관찰이 핵심입니다. `orders-retry-1000/2000/4000` 이라는 이름에서 **접미사가 인덱스가 아니라 지연값**이라는 것, 따라서 백오프를 바꾸면 **토픽 이름 자체가 바뀐다**는 결론을 이끌어 냅니다. 운영 중 백오프 변경이 왜 위험한지도 여기서 설명합니다.
- **정답 2** 는 `SUFFIX_WITH_INDEX_VALUE` + `dltTopicSuffix = ".DLT"` 조합입니다. `retryTopicSuffix` 는 기본값과 같아 생략해도 되지만 **명시하는 편이 낫다**는 이유(팀원이 기본값을 외우고 있지 않다)를 주석에 적었습니다.
- **정답 3** 의 포인트는 `ORIGINAL_TOPIC` 이 **`orders-retry-2` 가 아니라 `orders`** 로 찍힌다는 점입니다. 헤더는 최초 발행 시 한 번만 붙고 이후 단계에서 덮어쓰이지 않기 때문입니다. Step 07 의 `DLT_ORIGINAL_TOPIC` 과 상수 이름이 다른 이유도 함께 설명합니다.
- **정답 4** 는 실측 타임라인표와 결론입니다. 최종 상태가 `CREATED` 인 것, 그리고 **이 문제를 실패로 만드는 것은 예외가 아니라 성공**이라는 역설을 짚습니다. 해결책 세 가지(블로킹 전환 / 버전 필드 / 스냅샷 이벤트)를 코드 스케치로 붙였습니다.
- **정답 5** 는 `exclude = { InvalidOrderException.class }` 하나면 충분하다는 것입니다. `traversingCauses = "true"` 가 왜 필요한지 — 리스너 예외는 `ListenerExecutionFailedException` 으로 **감싸여** 오므로 원인 체인을 따라가야 분류가 맞는다는 점 — 을 자세히 적었습니다. Step 07 의 `addNotRetryableExceptions` 와 같은 함정입니다.
- **정답 6** 은 `RetryTopicConfigurationBuilder` 체인입니다. `.suffixTopicsWithIndexValues()`, `.autoCreateTopics(true, 3, (short) 1)`, `.notRetryOn(...)` 이 각각 애노테이션의 어느 속성에 대응하는지 1:1 대조표를 주석 표로 넣었습니다. 그리고 애노테이션이 빈보다 우선한다는 규칙도 마지막에 적었습니다.

```java file="./Solution.java"
```
