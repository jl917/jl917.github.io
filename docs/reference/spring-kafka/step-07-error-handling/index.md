# Step 07 — 에러 처리와 재시도

> **학습 목표**
> - 리스너에서 예외가 났을 때 `CommonErrorHandler` → `DefaultErrorHandler` 로 이어지는 처리 경로를 추적한다
> - `DefaultErrorHandler` 의 기본값이 `FixedBackOff(0L, 9L)` 이며 **소진 후 레코드를 버리고 커밋**한다는 것을 로그로 확인한다
> - `FixedBackOff` / `ExponentialBackOffWithMaxRetries` 의 시도 시각을 밀리초 단위로 실측한다
> - **블로킹 재시도가 파티션 전체를 멈추는 것을 `kcg --describe` 의 LAG 으로 측정한다**
> - 재시도할 예외와 하지 않을 예외를 분류하고, 기본 비재시도 목록 6종을 확인한다
> - `DeadLetterPublishingRecoverer` 로 `orders.DLT` 에 발행하고, 붙는 헤더 7종을 `kcc` 로 눈으로 본다
> - 백오프 총합이 `max.poll.interval.ms` 를 넘겨 컨슈머가 그룹에서 쫓겨나는 것을 재현한다
>
> **선행 스텝**: Step 06 — 오프셋 커밋과 AckMode
> **예상 소요**: 90분

---

## 7-0. 실습 준비

이 스텝은 컨슈머 그룹 `s07-inventory` 를 씁니다. 토픽은 `orders`(파티션 3) 와 `orders.DLT`(파티션 3) 입니다.

```bash
# 앱을 끈 상태에서
kcg --group s07-inventory --topic orders --reset-offsets --to-earliest --execute
kt --describe --topic orders.DLT
```

**결과**
```
Topic: orders.DLT	TopicId: 7pQm2LkATw2nR9xZbC4dLg	PartitionCount: 3	ReplicationFactor: 1	Configs: segment.bytes=1073741824
	Topic: orders.DLT	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
```

`orders.DLT` 의 **파티션 수가 3** 인 것을 반드시 확인하세요. 7-6 의 함정이 여기서 시작됩니다.

---

## 7-1. 리스너에서 예외가 나면 무슨 일이 벌어지는가

`@KafkaListener` 메서드가 예외를 던지면, 그 예외는 **애플리케이션 밖으로 나가지 않습니다.** 리스너를 호출한 것은 여러분의 코드가 아니라 Spring 의 **리스너 컨테이너**이고, 컨테이너가 그 예외를 붙잡습니다.

```
poll() → 레코드 배치 → KafkaMessageListenerContainer.doInvokeRecordListener()
                         try { listener.onMessage(record) } catch (RuntimeException e)
   ▼
CommonErrorHandler.handleOne(e, record, consumer, container)     ← 여기로 넘어간다
   ▼
DefaultErrorHandler (기본 구현)
   ① BackOff 로 대기 (컨슈머 스레드에서 Thread.sleep)
   ② consumer.seek(파티션, 실패한 오프셋)  → 다음 poll 에서 같은 레코드가 다시 온다
   ③ 재시도 소진 → ConsumerRecordRecoverer 호출 (기본은 로그만)
   ④ 그 레코드를 건너뛰고 다음으로 커밋
   ▼
다음 poll()
```

**핵심은 ②의 `seek`** 입니다. Spring 은 실패한 레코드를 메모리에 붙잡아 두고 반복 호출하는 게 아니라, **컨슈머의 읽기 위치를 실패 지점으로 되돌려** 다음 `poll()` 이 같은 레코드를 다시 가져오게 만듭니다. 그래서 재시도 중에는 **그 파티션의 다음 레코드로 절대 넘어가지 않습니다.** 이것이 7-4 의 함정으로 직결됩니다.

### Spring Kafka 2.8 의 통합 — 2.x 예제를 그대로 쓰면 안 되는 이유

인터넷에 널린 예제의 상당수가 **2.8 이전**입니다. 그 버전들은 에러 핸들러 인터페이스가 네 갈래로 쪼개져 있었습니다.

| 2.8 이전 | 2.8 이후 (현재, 3.1.4) | 비고 |
|---|---|---|
| `ErrorHandler` / `BatchErrorHandler` | `CommonErrorHandler` | 레코드용·배치용이 **하나로 합쳐짐** |
| `SeekToCurrentErrorHandler` | **`DefaultErrorHandler`** | 이름이 바뀜 |
| `SeekToCurrentBatchErrorHandler` / `RecoveringBatchErrorHandler` | `DefaultErrorHandler` | 흡수됨 |
| `container.setErrorHandler(...)` | `container.setCommonErrorHandler(...)` | 세터 이름도 바뀜 |

Spring Kafka 3.x 에는 `SeekToCurrentErrorHandler` 클래스가 **아예 없습니다.**

> ⚠️ **함정 — 블로그 예제를 복사하면 컴파일부터 안 된다**
> `factory.setErrorHandler(new SeekToCurrentErrorHandler(...))` 는 3.1.4 에서 `cannot find symbol` 입니다. 그나마 컴파일 에러라 다행입니다.
> 진짜 위험한 것은 **`setErrorHandler` 라는 이름의 다른 메서드**를 찾아 헤매다가 `KafkaListenerErrorHandler`(7-10)를 대신 붙이는 경우입니다. 이건 **컴파일도 되고 실행도 되지만 재시도도 DLT 도 전혀 동작하지 않습니다.** 검색 결과의 작성일과 버전을 반드시 확인하세요.

---

## 7-2. `DefaultErrorHandler` 의 기본 동작 — 아무 설정도 안 하면

에러 핸들러를 하나도 등록하지 않으면 Spring 이 `DefaultErrorHandler` 를 기본값으로 꽂아 둡니다. 기본 생성자는 `SeekUtils.DEFAULT_BACK_OFF`, 즉 **`new FixedBackOff(0L, 9L)`** 를 씁니다 — 간격 **0ms**, 재시도 **9회**. 최초 1회를 더해 **총 10번** 호출합니다.

`orders-1` 의 오프셋 42 번 레코드가 항상 실패하도록 만들고 실행합니다.

```java
@KafkaListener(topics = "orders", groupId = "s07-inventory")
public void onMessage(ConsumerRecord<String, OrderCreated> record) {
    if (record.partition() == 1 && record.offset() == 42) {
        throw new IllegalStateException("재고 서비스 응답 없음: " + record.value().orderId());
    }
    log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
}
```

**결과**
```
INFO  13022 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s07-inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$DefaultBehavior    : 처리 완료 orders-1@40
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$DefaultBehavior    : 처리 완료 orders-1@41
ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted for orders-1@42

org.springframework.kafka.listener.ListenerExecutionFailedException: Listener method '...Practice$DefaultBehavior.onMessage(ConsumerRecord)' threw exception
Caused by: java.lang.IllegalStateException: 재고 서비스 응답 없음: ORD-0043
	at com.example.order.step07.Practice$DefaultBehavior.onMessage(Practice.java:118)

INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$DefaultBehavior    : 처리 완료 orders-1@43
```

읽어야 할 것이 세 가지입니다. ① `currentAttempts=10, maxAttempts=9` — 최초 시도가 attempt 1 이고 재시도가 9회라 **총 10회**입니다(`maxAttempts` 는 "최대 **재**시도 횟수"로 읽습니다). ② `exhausted for orders-1@42` — **어느 파티션의 어느 오프셋**이 버려졌는지 정확히 남습니다. ③ 그 다음 줄이 `처리 완료 orders-1@43` — **42 번은 사라졌고 43 번으로 넘어갔습니다.**

```bash
kcg --describe --group s07-inventory
```

**결과**
```
GROUP          TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                     CLIENT-ID
s07-inventory  orders  0          100             100             0    consumer-s07-inventory-1-a3f8   consumer-s07-inventory-1
s07-inventory  orders  1          100             100             0    consumer-s07-inventory-2-b91c   consumer-s07-inventory-2
s07-inventory  orders  2          100             100             0    consumer-s07-inventory-3-c02d   consumer-s07-inventory-3
```

**LAG 이 전부 0 입니다.** 랙만 보는 모니터링에서는 완벽하게 정상으로 보입니다. 그런데 `ORD-0043` 은 처리되지 않았고 어디에도 남아 있지 않습니다.

> ⚠️ **함정 — 기본 동작은 "10번 해 보고 버린다"**
> 아무 설정도 안 하면 실패 메시지는 **0ms 간격으로 10번 재시도된 뒤 조용히 버려집니다.** 0ms 간격이라 외부 API 가 잠깐 죽은 상황에서는 10번이 **수 밀리초 안에 전부 소진**되어, 사실상 재시도가 없는 것과 같습니다.
> **증상**: 버려진 뒤에는 `LAG=0`, 컨슈머 정상, 예외도 밖으로 안 나갑니다. **ERROR 로그 한 줄만이 유일한 흔적**이고, 그 로그마저 로그 레벨을 조정하다 놓치면 메시지가 완전히 증발합니다.
> **해결**: 백오프를 명시하고(7-3), 반드시 `DeadLetterPublishingRecoverer` 를 붙이세요(7-6). "기본값 그대로"인 코드는 운영에 나가면 안 됩니다.

---

## 7-3. 백오프 전략 — 언제 얼마나 기다릴 것인가

`DefaultErrorHandler` 의 두 번째 생성자 인자가 `BackOff` 입니다. Spring Core 의 `org.springframework.util.backoff` 패키지 타입입니다.

### `FixedBackOff` — 고정 간격

```java
// 1초 간격, 재시도 3회 (총 4번 호출)
@Bean DefaultErrorHandler fixedBackOffHandler() {
    return new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
}
```

시도 시각을 찍어 보면 — **결과**
```
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$FixedBackOffDemo   : attempt=1 t=0ms      orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$FixedBackOffDemo   : attempt=2 t=1004ms   orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$FixedBackOffDemo   : attempt=3 t=2007ms   orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$FixedBackOffDemo   : attempt=4 t=3011ms   orders-1@42
ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=1000, currentAttempts=4, maxAttempts=3} exhausted for orders-1@42
```

**총 3.011초.** 4번 호출, 사이에 1초씩 3번 대기.

### `ExponentialBackOffWithMaxRetries` — 지수 증가 + 횟수 제한

```java
@Bean DefaultErrorHandler exponentialHandler() {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
    backOff.setInitialInterval(1_000L);   // 첫 대기 1초
    backOff.setMultiplier(2.0);           // 매번 2배
    backOff.setMaxInterval(10_000L);      // 상한 10초
    return new DefaultErrorHandler(backOff);
}
```

**결과**
```
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ExpBackOffDemo     : attempt=1 t=0ms      orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ExpBackOffDemo     : attempt=2 t=1003ms   orders-1@42   (대기 1s)
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ExpBackOffDemo     : attempt=3 t=3009ms   orders-1@42   (대기 2s)
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ExpBackOffDemo     : attempt=4 t=7018ms   orders-1@42   (대기 4s)
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ExpBackOffDemo     : attempt=5 t=15031ms  orders-1@42   (대기 8s)
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ExpBackOffDemo     : attempt=6 t=25046ms  orders-1@42   (대기 10s, maxInterval 로 잘림)
ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler              : Backoff ExponentialBackOffWithMaxRetries{initialInterval=1000, multiplier=2.0, maxInterval=10000, maxRetries=5} exhausted for orders-1@42
```

1 → 2 → 4 → 8 → **10**(16이 아님, `maxInterval` 상한). 총 **25.046초**.

> ⚠️ **함정 — `ExponentialBackOff` 는 기본이 "무한 재시도"다**
> `WithMaxRetries` 없이 그냥 `ExponentialBackOff` 를 쓰면 `maxElapsedTime` 기본값이 `Long.MAX_VALUE` 입니다. 즉 **영원히 재시도합니다.** 파티션은 영원히 멈추고, `max.poll.interval.ms` 를 넘겨 리밸런스가 나고, 재할당된 컨슈머가 또 처음부터 무한 재시도합니다.
> **증상**: LAG 이 단조 증가하는데 `exhausted` 로그가 한 번도 안 찍힘. 컨슈머는 계속 살아 있음.
> **해결**: 횟수 제한은 `ExponentialBackOffWithMaxRetries(n)`, 시간 제한은 `setMaxElapsedTime(...)` 을 **반드시** 지정하세요.

| 백오프 | 생성 | 총 대기 | 용도 |
|---|---|---:|---|
| `FixedBackOff(0L, 9L)` | **기본값** | 0ms | 사실상 재시도 없음. 쓰지 말 것 |
| `FixedBackOff(1000L, 3L)` | 1초 × 3 | 3s | 짧은 순간 장애 |
| `FixedBackOff(0L, 0L)` | 재시도 안 함 | 0ms | 바로 DLT 로 보낼 때 |
| `ExponentialBackOffWithMaxRetries(5)` | 1s×2배, 상한 10s | 25s | 외부 API 회복 대기 |
| `ExponentialBackOff()` | **무제한** | ∞ | ⚠️ 사고 |

> 💡 `new DefaultErrorHandler(new FixedBackOff(0L, 0L))` 는 "재시도 없이 즉시 리커버러 호출"입니다. 블로킹 재시도를 아예 포기하고 DLT 나 `@RetryableTopic`(Step 08)에 맡기는 구성에서 자주 씁니다.

---

## 7-4. ⚠️ 핵심 함정 — 블로킹 재시도가 파티션 전체를 멈춘다

**이 스텝에서 딱 하나만 기억해야 한다면 이 절입니다.** `DefaultErrorHandler` 의 백오프 대기는 별도 스케줄러가 아니라 **컨슈머 스레드 안에서 `Thread.sleep`** 으로 이루어집니다. `KafkaMessageListenerContainer$ListenerConsumer` 의 그 스레드는 `poll()` 도 하고 리스너 호출도 하는 **단 하나의 스레드**입니다. 그 스레드가 자면 그 파티션의 모든 일이 멈춥니다.

```
컨슈머 스레드 [ntainer#0-1-C-1]  ← orders-1 담당
 t=0s    poll() → [42, 43, 44, ... 91] (50건) → 42 처리 → 예외
 t=0s    ┌ Thread.sleep(10000)     ← 43~91 은 손도 안 댐
 t=10s   │ 42 재시도 → 예외 → sleep(10000)
 ...     │ (5회 반복)
 t=50s   └ 소진 → 42 버림 → 43 처리 시작
                             ↑ 43 번은 아무 잘못 없이 50초를 기다렸다
```

`FixedBackOff(10000L, 5L)` 로 `orders-1@42` 만 실패하게 두고, 그동안 프로듀서가 초당 1건씩 계속 발행하도록 했습니다. 재시도가 한창일 때(`t=30s`) 랙을 재 봅니다.

```bash
kcg --describe --group s07-inventory
```

**결과** — 재시도 시작 30초 후
```
GROUP          TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                     HOST         CLIENT-ID
s07-inventory  orders  0          131             131             0    consumer-s07-inventory-1-a3f8   /172.19.0.1  consumer-s07-inventory-1
s07-inventory  orders  1          42              89              47   consumer-s07-inventory-2-b91c   /172.19.0.1  consumer-s07-inventory-2
s07-inventory  orders  2          128             128             0    consumer-s07-inventory-3-c02d   /172.19.0.1  consumer-s07-inventory-3
```

`orders-0` 131/131 LAG 0, `orders-2` 128/128 LAG 0. 그런데 **`orders-1` 은 CURRENT-OFFSET 이 42 에 못 박힌 채 LAG 47.** 딱 한 건의 실패가 47건을 볼모로 잡았습니다. 파티션 0 과 2 는 정상이라 서비스 전체로 보면 "일부 주문만 30초 넘게 처리가 안 되는" 상태이고, 어떤 주문이 늦는지는 **키 해시에 달려 있어서** 예측도 안 됩니다.

처리 지연을 측정하면:

| 구간 | 평균 end-to-end 지연 | 최대 지연 |
|---|---:|---:|
| 정상 시(실패 레코드 없음) | 8ms | 41ms |
| `orders-1` 재시도 중 | **24,300ms** | **50,180ms** |

**8ms → 24.3초. 3,000배 느려졌습니다.** 그리고 이 지연은 실패한 메시지가 아니라 **뒤에 줄 서 있던 정상 메시지**가 겪는 지연입니다.

### 더 나쁜 것 — `max.poll.interval.ms` 초과와 리밸런스

Kafka 컨슈머는 `poll()` 을 주기적으로 호출해야 "살아 있다"고 인정받습니다. 그 최대 간격이 `max.poll.interval.ms`, **기본 5분** 입니다. 백오프 대기 중에는 `poll()` 을 못 하므로 **재시도 총합이 5분을 넘으면 그룹에서 쫓겨납니다.**

| 백오프 설정 | 호출 횟수 | 총 대기 시간 | `max.poll.interval.ms`=300s |
|---|---:|---:|---|
| `FixedBackOff(0L, 9L)` (기본) | 10 | 0s | 안전 |
| `FixedBackOff(1000L, 3L)` | 4 | 3s | 안전 |
| `FixedBackOff(10000L, 5L)` | 6 | 50s | 안전 (그러나 파티션 50초 정지) |
| `ExponentialBackOffWithMaxRetries(5)` 1s/2.0/10s | 6 | 25s | 안전 |
| `ExponentialBackOffWithMaxRetries(8)` 1s/2.0/60s | 9 | 1+2+4+8+16+32+60+60 = **183s** | 위험선 |
| `FixedBackOff(30000L, 12L)` | 13 | **360s** | ⚠️ **초과 → 리밸런스** |
| `ExponentialBackOff()` (무제한) | ∞ | ∞ | ⚠️ **확정 사고** |

⚠️ 위 표는 **한 배치에 실패 레코드가 하나일 때**입니다. `max-poll-records: 500` 인 배치에서 여러 건이 각각 실패하면 대기가 **누적**됩니다. `FixedBackOff(10000L, 5L)` 로 배치 안에서 6건이 실패하면 50s × 6 = **300초**로 그대로 초과합니다.

`FixedBackOff(30000L, 12L)` 로 재현한 실제 로그입니다.

**결과**
```
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$PollTimeoutDemo    : attempt=1 t=0ms      orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$PollTimeoutDemo    : attempt=2 t=30006ms  orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$PollTimeoutDemo    : attempt=11 t=300071ms orders-1@42
WARN  13022 --- [ntainer#0-1-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s07-inventory-2, groupId=s07-inventory] consumer poll timeout has expired. This means the time between subsequent calls to poll() was longer than the configured max.poll.interval.ms, which typically implies that the poll loop is spending too much time processing messages.
INFO  13022 --- [ntainer#0-1-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s07-inventory-2, groupId=s07-inventory] Member consumer-s07-inventory-2-b91c sending LeaveGroup request to coordinator 127.0.0.1:9092 (id: 2147483646 rack: null) due to consumer poll timeout has expired.
ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Consumer exception

java.lang.IllegalStateException: This error handler cannot process 'org.apache.kafka.clients.consumer.CommitFailedException's; no record information is available
Caused by: org.apache.kafka.clients.consumer.CommitFailedException: Offset commit cannot be completed since the consumer is not part of an active group for auto partition assignment; it is likely that the consumer was kicked out of the group.

INFO  13022 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s07-inventory: partitions revoked: [orders-1]
INFO  13022 --- [ntainer#0-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : s07-inventory: partitions assigned: [orders-1, orders-2]
INFO  13022 --- [ntainer#0-2-C-1] c.e.o.step07.Practice$PollTimeoutDemo    : attempt=1 t=0ms      orders-1@42
```

마지막 줄을 보세요. **`attempt=1` 로 되돌아갔습니다.** 재할당받은 다른 스레드가 커밋 안 된 오프셋 42 부터 다시 읽고, **처음부터 다시 12번 재시도를 시작합니다.** 30초씩 6분을 또 기다린 뒤 또 쫓겨납니다. 무한 루프입니다.

> ⚠️ **함정 — "재시도를 넉넉히" 가 서비스 전체를 죽인다**
> "외부 API 가 5분쯤 죽을 수 있으니 30초씩 12번 재시도하자"는 판단은 **직관적으로 옳아 보이지만 최악의 설정**입니다.
> **증상 3종 세트**: ① 특정 파티션 LAG 만 폭증 ② `poll timeout has expired` + `LeaveGroup` 반복 ③ 리밸런스가 몇 분마다 나서 **정상 파티션까지 처리가 끊김**. 컨슈머는 재시작을 반복하는데 앱은 죽지 않아 알림도 안 옵니다.
> **해결**: ① 블로킹 재시도의 **총합을 `max.poll.interval.ms` 의 1/3 이하**로 제한(기본 5분이면 100초 이내) ② 그 이상 기다려야 하면 **DLT + 재발행**(7-8)이나 **`@RetryableTopic`**(Step 08) ③ `max.poll.interval.ms` 를 올려서 해결하려 들지 말 것 — **장애 감지 시간만 늘어납니다.**
> (리밸런스 프로토콜 자체는 [Kafka 코스 Step 05](../../kafka/step-05-consumer/) 를 참고하세요.)

---

## 7-5. 재시도할 예외 / 재시도하면 안 되는 예외

재시도가 의미 있으려면 **"다시 하면 될 수도 있는 실패"** 여야 합니다. `SocketTimeoutException` 은 다시 해 볼 만하지만 **`quantity` 가 음수인 잘못된 주문**은 100번 해도 100번 실패합니다. `DefaultErrorHandler` 는 `BinaryExceptionClassifier` 로 이를 분류합니다. 기본적으로 **모든 예외를 재시도**하되, 아래 목록만 예외입니다.

| 재시도하지 않는 기본 예외 | 왜 |
|---|---|
| `DeserializationException` | 바이트가 깨진 것. 재시도해도 같은 바이트 |
| `MessageConversionException` | 페이로드 구조가 안 맞음 |
| `ConversionException` | 타입 변환 실패 |
| `MethodArgumentResolutionException` | 리스너 시그니처와 메시지가 안 맞음 |
| `NoSuchMethodException` | 호출할 메서드가 없음 |
| `ClassCastException` | 타입이 다름 |

전부 **"코드나 데이터가 잘못된" 종류**입니다. 재시도가 논리적으로 무의미하므로 곧바로 리커버러로 넘어갑니다.

> 💡 `DeserializationException` 이 기본 비재시도 목록에 있는 덕분에, `ErrorHandlingDeserializer`(Step 04)와 조합하면 포이즌 필이 파티션을 막지 않고 즉시 DLT 로 빠집니다. 이 두 설정은 **항상 세트로** 갑니다.

### 분류 규칙 추가하기

```java
DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));

// ① 재시도 대상에서 제외 (즉시 리커버러 → DLT). 블랙리스트 방식
handler.addNotRetryableExceptions(IllegalArgumentException.class, InvalidOrderException.class);

// ② 반대로, 기본 비재시도 목록에 있어도 재시도하게 만들기 (드묾)
handler.addRetryableExceptions(SocketTimeoutException.class);

// ③ 통째로 교체. 화이트리스트 방식 — 명시한 것만 재시도, 나머지는 즉시 포기
handler.setClassifications(Map.of(
        InvalidOrderException.class, false,   // false = 재시도 안 함
        RemoteApiException.class,   true      // true  = 재시도함
), /* defaultRetryable */ false);
```

`InvalidOrderException` 을 비재시도로 등록하고 실행하면:

**결과**
```
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$ClassifyDemo       : attempt=1 t=0ms  orders-1@42  InvalidOrderException
ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler              : Backoff none exhausted for orders-1@42

com.example.order.step07.InvalidOrderException: quantity 는 1 이상이어야 합니다: -3 (ORD-0043)
	at com.example.order.step07.Practice$ClassifyDemo.onMessage(Practice.java:243)

INFO  13022 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders.DLT-1
```

`Backoff none exhausted` — **백오프가 아예 실행되지 않았습니다.** attempt 는 1번뿐이고 곧바로 DLT 로 갔습니다.

> ⚠️ **함정 — 비즈니스 검증 실패를 재시도하는 것은 낭비이자 위험**
> "잔액 부족", "재고 부족", "필수 필드 누락" 은 **입력 데이터가 원인**이라 재시도해도 결과가 같습니다. 낭비로 끝나면 다행인데 실제 위험은 둘입니다.
> ① 7-4 대로 **파티션이 멈춰** 잘못된 주문 하나가 정상 주문 수백 건을 막습니다. ② 리스너에 **부분적 부작용**(DB 업데이트 후 검증 실패)이 있으면 재시도마다 그 부작용이 반복됩니다 — 재고가 10번 차감됩니다.
> **해결**: 비즈니스 검증 예외는 전용 타입으로 만들고 `addNotRetryableExceptions` 에 등록해 **즉시 DLT** 로 보내세요.
> 판단 기준 한 줄: **"5초 뒤에 똑같이 하면 성공할 가능성이 있는가?"** 없으면 재시도 대상이 아닙니다.

---

## 7-6. `DeadLetterPublishingRecoverer` — 버리지 말고 DLT 로

재시도를 소진했을 때 호출되는 것이 `ConsumerRecordRecoverer` 이고, 기본 구현은 로그만 찍습니다. 이걸 `DeadLetterPublishingRecoverer` 로 바꾸면 **실패 레코드를 다른 토픽으로 발행**합니다.

```java
@Bean DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    DefaultErrorHandler handler = new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3L));
    handler.addNotRetryableExceptions(InvalidOrderException.class);
    return handler;
}
```

기본 목적지는 **`<원본토픽>.DLT` 의 같은 파티션 번호**입니다. `orders-1@42` 가 실패하면 → `orders.DLT-1`.

**결과**
```
ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=1000, currentAttempts=4, maxAttempts=3} exhausted for orders-1@42
INFO  13022 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Republishing failed record to orders.DLT-1
INFO  13022 --- [ntainer#0-1-C-1] c.e.o.step07.Practice$DltDemo            : 처리 완료 orders-1@43
```

LAG 은 여전히 0 이지만, 이번엔 **잃어버린 게 없습니다.** `orders.DLT-1` 에 원본이 그대로 남아 있습니다.

> ⚠️ **함정 — DLT 의 파티션 수가 원본보다 적으면 발행이 실패한다**
> `orders`(3 파티션)의 파티션 2 에서 실패했는데 `orders.DLT` 가 1 파티션이면, 존재하지 않는 파티션 2 로 보내려다 실패합니다.
> ```
> ERROR 13022 --- [ntainer#0-2-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Dead-letter publication to orders.DLT failed for: orders-2@57
> org.apache.kafka.common.errors.TimeoutException: Topic orders.DLT not present in metadata after 60000 ms.
> ```
> **60초 타임아웃 뒤에 실패**하므로 그동안 파티션이 또 멈추고, 그 레코드는 **DLT 에도 못 가고 그냥 버려집니다.** 더 고약한 것은 `orders-0` 에서 실패할 때는 **정상 동작한다**는 점입니다. 테스트에서 안 걸리고 운영에서만 터집니다.
> **해결**: DLT 파티션 수를 원본과 같게 맞추거나(권장), 파티션 결정 함수를 커스터마이징합니다.

### 목적지 커스터마이징 — 생성자의 두 번째 인자가 `BiFunction<ConsumerRecord<?,?>, Exception, TopicPartition>` 입니다

```java
// 모든 실패를 0번 파티션으로 몰아넣는다
new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(record.topic() + ".DLT", 0));

// 파티션은 브로커가 정하게 맡긴다 (partition = -1)
new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

// 예외 종류에 따라 DLT 토픽 자체를 나눈다
new DeadLetterPublishingRecoverer(template, (record, ex) -> {
    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
    return new TopicPartition(record.topic()
            + (cause instanceof InvalidOrderException ? ".INVALID" : ".DLT"), -1);
});
```

> 💡 **실무 팁 — 파티션 번호를 `-1` 로 두면 파티션 수 불일치 문제가 사라집니다.**
> 대신 원본 파티션 정보는 헤더(7-7)로만 남고 DLT 안에서의 순서 보장은 포기합니다. DLT 는 어차피 순서대로 처리할 대상이 아니라 대부분 `-1` 이 안전합니다.

---

## 7-7. DLT 메시지에 붙는 헤더

`DeadLetterPublishingRecoverer` 는 원본 키·값·헤더를 그대로 복사하고, **진단 헤더 7종**을 추가합니다.

| `KafkaHeaders` 상수 | 실제 헤더 이름 | 내용 |
|---|---|---|
| `DLT_ORIGINAL_TOPIC` | `kafka_dlt-original-topic` | `orders` |
| `DLT_ORIGINAL_PARTITION` | `kafka_dlt-original-partition` | 4바이트 int (빅엔디안) |
| `DLT_ORIGINAL_OFFSET` | `kafka_dlt-original-offset` | 8바이트 long |
| `DLT_ORIGINAL_TIMESTAMP` | `kafka_dlt-original-timestamp` | 8바이트 long (epoch ms) |
| `DLT_EXCEPTION_FQCN` | `kafka_dlt-exception-fqcn` | 예외 클래스 FQCN |
| `DLT_EXCEPTION_MESSAGE` | `kafka_dlt-exception-message` | 예외 메시지 |
| `DLT_EXCEPTION_STACKTRACE` | `kafka_dlt-exception-stacktrace` | 전체 스택트레이스 |

```bash
kcc --topic orders.DLT --from-beginning \
    --property print.key=true --property print.partition=true --property print.headers=true
```

**결과** (읽기 쉽게 헤더에서 줄바꿈했습니다. 실제로는 한 줄입니다)
```
Partition:1	kafka_dlt-original-topic:orders,
		kafka_dlt-original-partition:\x00\x00\x00\x01,
		kafka_dlt-original-offset:\x00\x00\x00\x00\x00\x00\x00\x2A,
		kafka_dlt-original-timestamp:\x00\x00\x01\x94\x3B\x8C\x1E\x40,
		kafka_dlt-original-consumer-group:s07-inventory,
		kafka_dlt-exception-fqcn:org.springframework.kafka.listener.ListenerExecutionFailedException,
		kafka_dlt-exception-cause-fqcn:com.example.order.step07.InvalidOrderException,
		kafka_dlt-exception-message:Listener method '...$DltDemo.onMessage(...)' threw exception,
		kafka_dlt-exception-stacktrace:org.springframework.kafka.listener.ListenerExecutionFailedException: ... ,
		__TypeId__:com.example.order.domain.OrderCreated
	ORD-0043	{"orderId":"ORD-0043","customerId":1013,"sku":"SKU-002","quantity":2,"amount":11000,...}
```

주의할 점이 둘입니다. **파티션과 오프셋은 텍스트가 아니라 바이너리**입니다 — `\x00\x00\x00\x01` = 파티션 1, `\x00...\x2A` = 오프셋 42(0x2A). Java 에서는 `ByteBuffer.wrap(bytes).getInt()` / `.getLong()` 으로 읽습니다. 그리고 `__TypeId__` 도 **그대로 복사**되므로 DLT 리스너에서도 `OrderCreated` 로 역직렬화됩니다.

> ⚠️ **함정 — 스택트레이스 헤더가 메시지 크기 제한을 넘길 수 있다**
> `kafka_dlt-exception-stacktrace` 는 스택트레이스 전문입니다. Spring 프록시가 겹친 깊은 스택이면 **수십 KB** 가 됩니다. 브로커의 `message.max.bytes` 기본값은 1MB 라 보통은 넘지 않지만, **원본 페이로드가 이미 큰 경우** 합쳐서 초과합니다.
> ```
> ERROR 13022 --- [ntainer#0-1-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Dead-letter publication to orders.DLT failed for: orders-1@42
> org.apache.kafka.common.errors.RecordTooLargeException: The message is 1284119 bytes when serialized which is larger than 1048576.
> ```
> **해결**: `recoverer.setHeadersFunction(...)` 으로 스택트레이스 헤더를 잘라내거나, `setStripPreviousExceptionHeaders(true)`(기본 true)로 **재시도 반복 시 헤더가 누적되는 것**을 막습니다.

---

## 7-8. DLT 소비와 재처리

DLT 에 쌓기만 하는 것은 절반이고, 나머지 절반은 **누군가 그걸 보는 것**입니다.

```java
@KafkaListener(topics = "orders.DLT", groupId = "s07-dlt-monitor")
public void onDlt(ConsumerRecord<String, OrderCreated> record,
                  @Header(KafkaHeaders.DLT_ORIGINAL_TOPIC)     String origTopic,
                  @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int    origPartition,
                  @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET)    long   origOffset,
                  @Header(KafkaHeaders.DLT_EXCEPTION_FQCN)     String exFqcn) {
    log.error("DLT 수신 origin={}-{}@{} key={} ex={}",
            origTopic, origPartition, origOffset, record.key(), exFqcn);
    alertService.notifyOps(record.key(), exFqcn);
}
```

`@Header` 를 `int` / `long` 으로 선언하면 Spring 이 **바이트 배열을 알아서 변환**해 줍니다. 직접 `ByteBuffer` 를 다룰 필요가 없습니다.

**결과**
```
INFO  13022 --- [ntainer#1-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s07-dlt-monitor: partitions assigned: [orders.DLT-0, orders.DLT-1, orders.DLT-2]
ERROR 13022 --- [ntainer#1-1-C-1] c.e.o.step07.Practice$DltListener        : DLT 수신 origin=orders-1@42 key=ORD-0043 ex=org.springframework.kafka.listener.ListenerExecutionFailedException msg=Listener method '...' threw exception
INFO  13022 --- [ntainer#1-1-C-1] c.e.o.step07.Practice$DltListener        : ops 알림 발송 완료 orderId=ORD-0043
```

### 수동 재발행 — 외부 API 가 복구된 뒤 DLT 의 메시지를 본선으로 되돌립니다

```java
try (Consumer<String, OrderCreated> consumer = consumerFactory.createConsumer("s07-replay", "-1")) {
    consumer.subscribe(List.of("orders.DLT"));
    for (ConsumerRecord<String, OrderCreated> r : consumer.poll(Duration.ofSeconds(5))) {
        template.send("orders", r.key(), r.value());   // 원본 키를 써야 같은 파티션으로 돌아간다
    }
    consumer.commitSync();
}
```

**결과**
```
INFO  13022 --- [           main] c.e.o.step07.Practice$DltReplayer        : orders.DLT 에서 7건 조회
INFO  13022 --- [           main] c.e.o.step07.Practice$DltReplayer        : orders 로 재발행 ORD-0043 → orders-1@101
INFO  13022 --- [           main] c.e.o.step07.Practice$DltReplayer        : 재발행 완료 7건
```

> ⚠️ **함정 — 아무도 안 보는 DLT 는 그냥 쓰레기통이다**
> DLT 를 붙여 놓고 만족하는 팀이 많습니다. 그런데 DLT 는 **에러를 내지 않습니다.** 메시지가 쌓여도 앱은 건강하고 LAG 은 0 입니다. `retention.ms` 기본 7일이 지나면 **DLT 의 메시지도 삭제**되므로 일주일 뒤에는 증거조차 없습니다.
> **최소한 이 셋은 갖추세요**: ① **DLT 의 `LOG-END-OFFSET` 증가를 알림**으로 걸 것(Step 12 에서 Micrometer 로 노출) ② DLT 리스너로 **즉시 알림**을 보낼 것 ③ DLT 의 `retention.ms` 를 본선보다 **길게**(예: 30일) 둘 것 — `kt --alter --topic orders.DLT --config retention.ms=2592000000`

> 💡 **재발행 시 무한 루프 주의.** 원인이 안 고쳐진 상태로 재발행하면 다시 실패해 DLT 로 돌아옵니다. 재발행 횟수를 헤더(`x-replay-count`)에 심고 N 회를 넘으면 거부하는 가드를 두세요.

---

## 7-9. 컨테이너 레벨 vs 팩토리 레벨 설정

에러 핸들러를 붙일 수 있는 지점이 세 곳입니다.

| 지점 | 방법 | 적용 범위 |
|---|---|---|
| 자동 설정 | `DefaultErrorHandler` 빈 하나 등록 | Boot 가 **모든 팩토리에** 자동 주입 |
| 팩토리 | `factory.setCommonErrorHandler(handler)` | 그 팩토리를 쓰는 모든 리스너 |
| 컨테이너 | `container.setCommonErrorHandler(handler)` | 특정 리스너 하나 |

`CommonErrorHandler` 타입 빈이 **하나만** 있으면 Boot 의 `KafkaAnnotationDrivenConfiguration` 이 기본 팩토리에 자동으로 꽂아 줍니다. 대부분은 이걸로 끝납니다. 리스너마다 다른 정책이 필요하면 팩토리를 나눕니다.

```java
// 공통 조립부. backOff 만 갈아 끼운다
private ConcurrentKafkaListenerContainerFactory<String, Object> build(
        ConsumerFactory<String, Object> cf, KafkaTemplate<String, Object> t, BackOff backOff) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
    f.setConsumerFactory(cf);
    f.setConcurrency(3);
    f.setCommonErrorHandler(new DefaultErrorHandler(new DeadLetterPublishingRecoverer(t), backOff));
    return f;
}

@Bean("aggressiveRetryFactory")   // 0.5s→1s→2s→4s = 총 7.5초
ConcurrentKafkaListenerContainerFactory<String, Object> aggressive(
        ConsumerFactory<String, Object> cf, KafkaTemplate<String, Object> t) {
    var b = new ExponentialBackOffWithMaxRetries(4);
    b.setInitialInterval(500L); b.setMultiplier(2.0); b.setMaxInterval(5_000L);
    return build(cf, t, b);
}

@Bean("noRetryFactory")           // 재시도 0회. 실패하면 바로 DLT
ConcurrentKafkaListenerContainerFactory<String, Object> noRetry(
        ConsumerFactory<String, Object> cf, KafkaTemplate<String, Object> t) {
    return build(cf, t, new FixedBackOff(0L, 0L));
}

@KafkaListener(topics = "orders", groupId = "s07-inventory",
               containerFactory = "aggressiveRetryFactory")
public void inventory(OrderCreated event) { ... }

@KafkaListener(topics = "orders", groupId = "s07-notification",
               containerFactory = "noRetryFactory")
public void notify(OrderCreated event) { ... }
```

**재고 차감은 재시도할 가치가 있고(DB 락 경합·순간 타임아웃), 알림 발송은 실패하면 그냥 DLT 로 보내는 편이 낫습니다.** 알림 하나 때문에 파티션을 막을 이유가 없습니다. 런타임에 바꾸려면 `registry.getListenerContainer("id")` → `stop()` → `setCommonErrorHandler(...)` → `start()` 순서로 합니다.

> ⚠️ 컨테이너가 **실행 중일 때 `setCommonErrorHandler` 를 호출해도 반영되지 않습니다.** 이미 시작된 `ListenerConsumer` 스레드가 생성 시점의 핸들러 참조를 들고 있기 때문이며, 예외도 안 납니다. 반드시 `stop()` → 교체 → `start()` 순서로 하세요. (컨테이너 제어는 Step 10 에서 다룹니다.)

---

## 7-10. `@KafkaListener(errorHandler = ...)` — 완전히 다른 물건

`@KafkaListener` 에는 `errorHandler` 속성이 있습니다. 이름이 비슷해서 헷갈리지만 **`CommonErrorHandler` 와 전혀 다른 인터페이스**입니다.

```java
@Bean("validationErrorHandler") KafkaListenerErrorHandler validationErrorHandler() {
    return (message, exception) -> {
        log.warn("리스너 에러 핸들러 진입: {}", exception.getMessage());
        throw exception;          // ← 다시 던져야 컨테이너 에러 핸들러로 간다
    };
}

@KafkaListener(topics = "orders", groupId = "s07-inventory",
               errorHandler = "validationErrorHandler")
public void onMessage(OrderCreated event) { ... }
```

| | `KafkaListenerErrorHandler` | `CommonErrorHandler` |
|---|---|---|
| 등록 | `@KafkaListener(errorHandler = "빈이름")` | 팩토리/컨테이너/자동설정 |
| 호출 시점 | **먼저** (메시징 어댑터 레벨) | 나중 (컨테이너 레벨) |
| 받는 것 | `Message<?>` + `ListenerExecutionFailedException` | `ConsumerRecord` + `Consumer` + `Container` |
| 할 수 있는 것 | 대체 값 반환, 예외 재던지기 | 재시도(seek), 백오프, DLT 발행, 커밋 제어 |
| 예외를 삼키면 | **성공으로 처리됨** | — |

호출 순서는 이렇습니다. 리스너 예외 → `KafkaListenerErrorHandler.handleError(...)` → **값을 리턴하거나 그냥 끝내면** 컨테이너는 "성공"으로 간주해 커밋하고 끝(재시도 없음, DLT 없음), **예외를 다시 던지면** `CommonErrorHandler` 로 넘어가 재시도·DLT 를 탑니다.

> ⚠️ **함정 — `KafkaListenerErrorHandler` 에서 예외를 삼키면 재시도도 DLT 도 안 탄다**
> "에러를 로그로 남기자"는 선의로 이렇게 쓰는 코드를 자주 봅니다.
> ```java
> return (message, exception) -> {
>     log.error("처리 실패", exception);
>     return null;                  // ⚠️ 여기서 끝. 컨테이너는 성공으로 안다
> };
> ```
> **증상**: `DefaultErrorHandler` 를 아무리 정교하게 설정해도 `Backoff ... exhausted` 가 한 번도 안 찍히고 `orders.DLT` 는 영원히 비어 있습니다. LAG 은 0, 에러 로그는 있는데 **재시도 흔적이 없습니다.**
> **해결**: 로그만 남기고 싶어도 마지막에 **`throw exception;`** 을 반드시 넣으세요. 애초에 로깅이 목적이라면 이 핸들러를 쓸 이유가 없습니다. `DefaultErrorHandler` 가 이미 로그를 남깁니다.

> 💡 `KafkaListenerErrorHandler` 의 정당한 용도는 **`@SendTo` 응답 대체**입니다. 요청-응답 패턴에서 처리 실패 시 에러 응답 객체를 리턴하면 그게 응답 토픽으로 나갑니다. 그 외에는 거의 쓸 일이 없습니다.

---

## 7-11. 블로킹 재시도의 한계

정리하면 `DefaultErrorHandler` 의 블로킹 재시도는 이런 물건입니다.

| 특성 | 결과 |
|---|---|
| 컨슈머 스레드에서 `Thread.sleep` | **그 파티션의 뒤 메시지 전부 대기** |
| `seek` 으로 오프셋 되돌림 | 순서는 완벽히 보존됨 ✅ |
| 총 대기 > `max.poll.interval.ms` | 그룹 이탈 → 리밸런스 → 처음부터 반복 |
| 실패 레코드 여러 건 | 대기가 누적됨 |
| 백오프를 길게 잡을수록 | 안정성이 아니라 **위험이 커짐** |

**장점은 딱 하나, 순서 보존입니다.** 실패한 레코드가 성공하거나 DLT 로 갈 때까지 그 파티션의 다음 레코드는 절대 처리되지 않습니다. "주문 생성 → 주문 수정" 순서가 뒤바뀌면 안 되는 도메인에서는 이 성질이 필수입니다. 반대로 순서가 중요하지 않다면 블로킹 재시도는 순수한 손해입니다. 그래서 나온 것이 **논블로킹 재시도** — 실패한 메시지를 별도의 retry 토픽으로 보내고 원래 파티션은 **바로 다음 메시지로 넘어갑니다.**

| | 블로킹 (`DefaultErrorHandler`) | 논블로킹 (`@RetryableTopic`) |
|---|---|---|
| 파티션 정지 | **있음** | 없음 |
| 순서 보장 | **보존** | **깨짐** |
| 긴 백오프 | 위험 (poll timeout) | 안전 (시간 단위도 가능) |
| 토픽 수 | 원본 + DLT | 원본 + retry N개 + DLT |
| 적합 | 순서가 중요, 짧은 재시도 | 순서 무관, 긴 재시도 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| 처리 경로 | 리스너 예외 → 컨테이너 → `CommonErrorHandler` → `DefaultErrorHandler` |
| 2.8 통합 | `SeekToCurrentErrorHandler`/`ErrorHandler`/`BatchErrorHandler` → **`DefaultErrorHandler`** |
| 기본 백오프 | **`FixedBackOff(0L, 9L)`** = 0ms 간격 10회 → **버리고 커밋** |
| 재시도 방식 | `consumer.seek` + **컨슈머 스레드 `Thread.sleep`** |
| 파티션 정지 | 백오프 10s × 5회 = **그 파티션 50초 정지**. LAG 47 실측 |
| poll timeout | 총 대기 > `max.poll.interval.ms`(5분) → `LeaveGroup` → 리밸런스 → **처음부터 반복**. 총합은 **1/3 이하**로 |
| `ExponentialBackOff` | 기본이 **무한**. `ExponentialBackOffWithMaxRetries(n)` 를 쓸 것 |
| 비재시도 기본 6종 | `Deserialization`/`MessageConversion`/`Conversion`/`MethodArgumentResolution`/`NoSuchMethod`/`ClassCast` |
| 분류 판단 기준 | **"5초 뒤에 똑같이 하면 성공할 가능성이 있는가?"** |
| DLT 목적지 | `<topic>.DLT` 의 **같은 파티션 번호**. 파티션 수가 적으면 60초 뒤 실패 |
| DLT 헤더 | 원본 topic/partition/offset/timestamp + 예외 fqcn/message/stacktrace |
| DLT 운영 | **아무도 안 보면 쓰레기통.** LAG 알림 + DLT 리스너 + 긴 retention |
| `KafkaListenerErrorHandler` | **먼저** 호출. 예외를 삼키면 **재시도·DLT 전부 무효** |
| 블로킹의 유일한 장점 | **순서 보존** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 직접 실행해 로그와 LAG 을 확인**하세요.

1. `ExponentialBackOffWithMaxRetries` 로 초기 1초 / 2배 / 상한 10초 / 5회 재시도 핸들러를 만들고, 시도 시각을 로그로 남겨 총 25초를 확인하기
2. 비즈니스 검증 예외(`InvalidOrderException`)를 재시도 제외로 등록하고, 로그에 `Backoff none exhausted` 가 찍히는지 확인하기
3. `DeadLetterPublishingRecoverer` 를 붙이고 `orders.DLT` 리스너를 구현해 원본 topic/partition/offset 을 로그로 남기기
4. 블로킹 재시도 중 `kcg --describe` 로 **특정 파티션만 LAG 이 치솟는 것**을 관측하고 표로 기록하기
5. DLT 헤더에서 원본 오프셋을 **바이트 배열로 직접** 꺼내 `ByteBuffer` 로 long 변환하기
6. `max.poll.interval.ms` 를 30초로 낮추고 백오프 총합을 40초로 잡아 **리밸런스를 의도적으로 유발**하고, `LeaveGroup` 로그를 확인하기

---

## 다음 단계

블로킹 재시도의 정체를 봤습니다. 순서는 지켜 주지만 **파티션을 인질로 잡고**, 백오프를 늘릴수록 리밸런스 위험이 커집니다. "10분 뒤에 다시 시도"는 이 구조로는 불가능합니다.

Spring Kafka 는 이 문제를 **재시도 전용 토픽**으로 풉니다. `@RetryableTopic` 하나면 `orders-retry-0`, `orders-retry-1` … 을 자동으로 만들고 실패 메시지를 그리로 흘려보냅니다. 원래 파티션은 즉시 다음 메시지로 넘어갑니다. 대가는 **순서 보장 포기**입니다. 그 트레이드오프와 선택 기준을 다음 스텝에서 정리합니다.

→ [Step 08 — @RetryableTopic 논블로킹 재시도](../step-08-retryable-topic/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 를 프로필로 하나씩 켜 가며 7-2 ~ 7-10 의 로그를 재현하고, 특히 **`step07-blocking` 프로필을 켠 채 `kcg --describe` 를 30초 간격으로 두 번 찍어** 7-4 의 LAG 표를 직접 만들어 보세요. 그다음 `Exercise.java` 의 6문제를 풀고 `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.order.step07` 패키지에 두고, 실패 조건은 전부 `orders-1` 의 특정 오프셋으로 고정해 두었습니다.

### Practice.java

본문 7-2 ~ 7-10 의 모든 예제를 절 번호 주석과 함께 nested static class 로 담은 실행 파일입니다.

- 예제끼리 서로 간섭하므로 **보조 프로필로 하나씩만 켭니다.** `step07` 만 켜면 아무 리스너도 안 뜨고, `step07,step07-default` 처럼 두 번째 프로필을 함께 줘야 해당 절의 리스너가 등록됩니다. 파일 상단 주석에 프로필 6개의 목록과 각각이 재현하는 절이 정리돼 있습니다.
- `FailurePolicy` 가 모든 예제의 공통 실패 조건입니다. `orders-1` 의 오프셋 42 만 실패시키므로, 오프셋을 리셋하지 않고 여러 번 돌리면 42 번이 이미 지나가 아무 일도 안 일어납니다. **매 실행 전 `kcg --group s07-inventory --topic orders --reset-offsets --to-earliest --execute` 를 돌리세요.**
- `[7-4] BlockingDemo` 가 이 파일의 핵심입니다. `FixedBackOff(10000L, 5L)` 로 50초를 막는 동안 `LoadGenerator` 가 초당 1건씩 `orders` 로 계속 발행합니다. 이 두 컴포넌트가 **동시에 떠 있어야** LAG 이 벌어지는 것을 볼 수 있습니다.
- `[7-4] PollTimeoutDemo` 는 `step07-polltimeout` 프로필에서 `max.poll.interval.ms` 를 **30초로 낮춘 전용 `ConsumerFactory`** 를 씁니다. 기본 5분으로는 재현에 6분이 걸리기 때문입니다. 이 프로필을 켜면 컨슈머가 30초마다 그룹에서 쫓겨나는 로그가 반복되므로, 확인 후 반드시 앱을 내리세요.
- `[7-7] HeaderDumper` 는 DLT 레코드의 헤더를 **바이트 배열 그대로** 16진수로 찍습니다. `@Header(DLT_ORIGINAL_OFFSET) long` 로 받는 편한 방식과, `ByteBuffer.wrap(...).getLong()` 로 직접 푸는 방식을 나란히 두어 헤더가 실제로는 바이너리라는 것을 확인시킵니다.
- `[7-10] SwallowingErrorHandler` 는 **일부러 잘못 만든 코드**입니다. `throw exception` 이 주석 처리돼 있어 재시도도 DLT 도 동작하지 않습니다. 주석을 풀었을 때와 안 풀었을 때의 로그 차이를 반드시 비교하세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일은 되도록 뼈대를 남겨 두었습니다.

- **문제 1·2·6** 은 `DefaultErrorHandler` 를 **설정**하는 문제이고, **문제 3·4·5** 는 DLT 발행 결과를 **관찰**하는 문제입니다. 3 번의 DLT 리스너가 있어야 5 번의 헤더 문제를 풀 수 있으니 순서대로 푸세요.
- 문제 4 는 코드를 거의 안 씁니다. 대신 `// 관측 기록:` 주석 표를 채우는 문제입니다. `kcg --describe --group s07-ex-inventory` 를 **재시도 시작 직후 / 30초 후 / 소진 후** 세 번 찍어 CURRENT-OFFSET 과 LAG 을 적어 넣으세요.
- ⚠️ **문제 6 은 컨슈머를 일부러 죽이는 문제입니다.** `max.poll.interval.ms=30000` 짜리 전용 팩토리를 만들고 백오프 총합을 40초로 잡아 `LeaveGroup` 을 유발합니다. 다 확인했으면 **앱을 반드시 종료**하세요. 켜 둔 채 다른 문제로 넘어가면 리밸런스가 계속 나서 다른 리스너까지 영향을 받습니다.
- 각 문제 끝의 `// 확인:` 주석에 **기대 로그 한 줄**이 적혀 있습니다. 그 줄이 콘솔에 안 나오면 답이 틀린 것입니다. 예를 들어 문제 2 의 확인 줄은 `Backoff none exhausted for orders-1@42` 입니다.
- 문제 3 은 `orders.DLT` 의 파티션 수가 3 이라는 전제로 되어 있습니다. `kt --describe --topic orders.DLT` 로 먼저 확인하세요. 1 파티션이면 7-6 의 함정에 그대로 걸립니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `ExponentialBackOffWithMaxRetries(5)` 를 쓰는 것이 핵심입니다. `ExponentialBackOff` 에 `setMaxElapsedTime` 만 걸면 "시간은 제한되지만 횟수는 예측 불가"가 되어 로그로 검증하기 어렵습니다. 1+2+4+8+10 = 25초라는 총합 계산 과정을 주석에 적어 두었습니다.
- **정답 2** 의 포인트는 `addNotRetryableExceptions(InvalidOrderException.class)` 만으로 충분하고 `setClassifications` 까지 갈 필요가 없다는 판단입니다. 화이트리스트 방식(`defaultRetryable=false`)이 왜 이 상황에서 과한지, 그리고 **예외를 감싸면(`RuntimeException` 으로 래핑) 분류가 안 먹는 이유**를 함께 설명합니다.
- **정답 3** 은 DLT 리스너의 `groupId` 를 본선과 **다르게** 두는 이유를 설명합니다. 같은 그룹으로 두면 `orders` 와 `orders.DLT` 를 한 컨슈머가 함께 구독해 DLT 처리 실패가 본선을 막습니다. 또 DLT 리스너에는 **재시도를 걸지 않는 것**이 원칙임을 적었습니다.
- **정답 4** 는 실측표입니다. `t=0s` LAG 3 → `t=30s` LAG 47 → `t=50s` LAG 0 으로, **소진 직후 밀린 47건이 한꺼번에 처리되며 LAG 이 급락**하는 패턴을 보여 줍니다. 이 "톱니 모양"이 블로킹 재시도의 지문이라는 점이 이 문제의 결론입니다.
- **정답 5** 는 `ByteBuffer.wrap(bytes).getLong()` 로 오프셋을 푸는 코드와, 그럴 필요 없이 `@Header(...) long` 으로 받으면 Spring 이 변환해 준다는 사실을 나란히 둡니다. 다만 `ConsumerRecord.headers()` 로 직접 순회할 때는 **직접 변환이 유일한 방법**이라는 단서를 답니다.
- **정답 6** 은 `max.poll.interval.ms=30000` + `FixedBackOff(10000L, 4L)`(총 40초) 조합입니다. 왜 `session.timeout.ms` 가 아니라 `max.poll.interval.ms` 가 원인인지, 그리고 **이 설정을 올려서 해결하려 들면 장애 감지가 늦어질 뿐**이라는 결론까지 주석으로 남겼습니다.

```java file="./Solution.java"
```
