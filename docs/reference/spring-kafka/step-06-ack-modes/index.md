# Step 06 — 오프셋 커밋과 AckMode

> **학습 목표**
> - 커밋된 오프셋이 "마지막 처리 위치"가 아니라 **다음에 읽을 위치**임을 CLI 출력으로 확인한다
> - `enable.auto.commit` 이 왜 위험한지, Spring 이 그것을 어떻게 대체하는지 설명한다
> - `AckMode` 7종(RECORD/BATCH/TIME/COUNT/COUNT_TIME/MANUAL/MANUAL_IMMEDIATE)의 커밋 시점을 로그로 구별한다
> - RECORD 와 BATCH 의 커밋 횟수·처리량 차이를 500건으로 실측한다
> - **예외를 try-catch 로 삼켰을 때 메시지가 유실되는 것**과, 던졌을 때 앞부분이 중복 처리되는 것을 각각 재현한다
> - 배치 리스너의 부분 실패를 `BatchListenerFailedException` 과 `ack.nack(index, Duration)` 으로 부분 커밋한다
>
> **선행 스텝**: Step 05 — 메시지 변환과 헤더
> **예상 소요**: 90분

---

## 6-0. 실습 준비

이 스텝은 `orders` 토픽에 **정확히 500건**이 들어 있다고 가정합니다. `./gradlew bootRun --args='--spring.profiles.active=step06'` 을 한 번 돌리면 `Practice.java` 의 `Seeder` 가 넣어 줍니다.

**결과**
```
INFO 14107 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step06"
INFO 14107 --- [           main] c.e.o.step06.Practice$Seeder             : [6-0] 시드 완료: 500건 발행, 214 ms
INFO 14107 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 2.611 seconds (process running for 2.998)
```

커밋 시점을 눈으로 보려면 `application.yml` 에 로거 하나를 추가합니다. **이 스텝의 절반은 이 로그를 읽는 일입니다.**

```yaml
logging:
  level:
    org.apache.kafka.clients.consumer.internals.ConsumerCoordinator: DEBUG
```

이 스텝의 모든 리스너는 `autoStartup="false"` 이고 보조 프로필로 하나씩 켭니다. 여러 개를 동시에 켜면 커밋 로그가 뒤섞여 **횟수를 셀 수 없습니다.**

> 💡 `__consumer_offsets` 토픽의 내부 구조(키 포맷, 컴팩션, 코디네이터 선출)는
> [Kafka 코스 Step 06](../../kafka/step-06-offsets/) 에서 다룹니다. 이 스텝은 **Spring 컨테이너가 언제 커밋을 호출하는가**만 봅니다.

---

## 6-1. 오프셋 커밋이란 무엇인가

컨슈머는 파티션을 처음부터 순서대로 읽습니다. 그런데 앱이 죽었다 살아나면 **어디부터 다시 읽어야 할까요?** 그 기록이 오프셋 커밋입니다. 커밋된 오프셋은 브로커의 내부 토픽 `__consumer_offsets` 에 `(그룹, 토픽, 파티션) → 오프셋` 형태로 저장됩니다. 컨슈머 로컬 파일이 아니라서 **다른 서버에서 같은 그룹으로 앱을 띄워도 이어서 읽습니다.**

여기서 이 스텝 내내 헷갈릴 지점 하나를 먼저 못 박습니다.

> 📌 **커밋된 오프셋 = 다음에 읽을 위치 = 마지막으로 처리한 오프셋 + 1**

오프셋 0~42 까지 43건을 처리하고 커밋했다면, 커밋되는 값은 42 가 아니라 **43** 입니다.

```bash
kcg --describe --group s06-record
```

**결과**
```
GROUP        TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                     HOST         CLIENT-ID
s06-record   orders  0          167             167             0    consumer-s06-record-1-4e21...   /172.19.0.1  consumer-s06-record-1
s06-record   orders  1          166             166             0    consumer-s06-record-1-4e21...   /172.19.0.1  consumer-s06-record-1
s06-record   orders  2          167             167             0    consumer-s06-record-1-4e21...   /172.19.0.1  consumer-s06-record-1
```

`orders-0` 의 `CURRENT-OFFSET` 이 167 인데, 실제로 존재하는 마지막 레코드의 오프셋은 **166** 입니다. 167 번 레코드는 아직 없습니다. `LOG-END-OFFSET` 역시 "다음에 쓸 위치"라서 167 이고, 그래서 `LAG = 167 - 167 = 0` 이 성립합니다.

이 규칙을 모르면 "CURRENT-OFFSET 이 43 이니 43번을 처리했다"(→ 42번까지 처리했고 43번은 **아직 안 읽었다**), "LAG 이 1 이니 처리 중인 게 1건 있다"(→ 아직 **읽지도 않은** 메시지가 1건 있다) 같은 오해에 빠집니다.

`ConsumerCoordinator` DEBUG 를 켜면 커밋 요청 자체가 로그로 찍힙니다.

```
DEBUG 14107 --- [ntainer#2-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s06-record-1, groupId=s06-record] Committing offsets: {orders-0=OffsetAndMetadata{offset=43, leaderEpoch=0, metadata=''}}
```

`offset=43`. 이 컨슈머가 방금 처리한 레코드는 `orders-0@42` 입니다.

> 💡 **실무 팁 — 커밋은 "커서"이지 "체크리스트"가 아니다**
> 커밋은 파티션당 **정수 하나**입니다. "3, 5, 7번은 처리했고 4, 6번은 실패했다" 같은 집합은 표현할 수 없습니다.
> 그래서 중간 하나가 실패하면 선택지는 둘뿐입니다. **그 앞까지만 커밋하고 멈추거나, 실패를 무시하고 넘어가거나.**
> 이 스텝의 모든 함정이 이 한 문장에서 파생됩니다.

---

## 6-2. ⚠️ `enable.auto.commit` 을 쓰면 안 되는 이유

Kafka 클라이언트에는 자동 커밋 기능이 있습니다. `enable.auto.commit=true`(클라이언트 기본값) 이면 `auto.commit.interval.ms`(기본 5000) 마다 커밋합니다.

문제는 **무엇을 기준으로 커밋하느냐** 입니다. 자동 커밋은 **"poll 로 반환했다"** 를 기준으로 합니다. **"처리에 성공했다"** 가 아닙니다. 그래서 ① 500건을 반환받고 ② 100건째 처리 중 타이머가 돌아 500건 전부가 커밋되고 ③ 250건째에서 앱이 죽으면(OOM·배포·노드 장애), 재시작 시 **250~499번 250건이 영원히 사라집니다.**

에러 로그는 한 줄도 안 남습니다. `LAG` 은 0 입니다.

> ⚠️ **함정 — 자동 커밋의 유실은 "지표가 완벽할 때" 일어난다**
> 유실이 일어나도 컨슈머 랙은 0, 예외도 0, 리스너 호출 횟수도 정상입니다.
> 발견되는 경로는 대개 "며칠 뒤 정산이 안 맞는다" 입니다. 그때는 원본 메시지의 보존 기간이 지나 복구도 안 됩니다.
> **자동 커밋은 "메시지를 잃어도 되는 파이프라인"에만 씁니다.** 그런 파이프라인은 생각보다 적습니다.

### Spring 은 이걸 대신 꺼 준다

다행히 spring-kafka 를 쓰면 이 함정을 밟기가 어렵습니다. `KafkaAutoConfiguration` 이 컨슈머 팩터리를 만들 때 `enable.auto.commit` 을 **명시적으로 `false`** 로 설정하고, 커밋 책임을 리스너 컨테이너로 가져옵니다.

기동 로그의 `ConsumerConfig values:` 덤프에서 확인할 수 있습니다.

```
INFO 14107 --- [           main] o.a.k.c.c.ConsumerConfig                 : ConsumerConfig values:
	auto.commit.interval.ms = 5000
	enable.auto.commit = false
	max.poll.records = 500
```

`auto.commit.interval.ms = 5000` 이 남아 있지만 `enable.auto.commit = false` 이므로 아무 의미가 없습니다. `application.yml` 의 `spring.kafka.consumer.enable-auto-commit: false` 는 Spring 이 어차피 하는 일이지만 **의도를 남기려고** 명시한 것입니다.

그러면 커밋은 **누가** 하는가? 리스너 컨테이너입니다. 그리고 컨테이너가 "언제" 커밋할지를 정하는 것이 `AckMode` 입니다.

> 💡 `AckMode` 는 Kafka 클라이언트 프로퍼티가 아닙니다. 브로커로 전달되지 않습니다.
> **순수하게 Spring 리스너 컨테이너의 개념**이고, 컨테이너가 `commitSync`/`commitAsync` 를 호출하는 시점 정책입니다.
> `application.yml` 의 `spring.kafka.listener.ack-mode` 에 대응하는 Kafka 원본 프로퍼티가 없는 이유가 이것입니다.

---

## 6-3. AckMode 7종 완전 비교

| AckMode | 커밋 시점 | 500건 처리 시 커밋 횟수 | 실패 시 재처리 범위 | 처리량 | 주 용도 |
|---|---|---:|---|---|---|
| `RECORD` | 리스너가 레코드 1건을 예외 없이 리턴할 때마다 | 500 | 1건 | 낮음 | 재처리 비용이 비싼 처리 |
| `BATCH` **(기본값)** | poll 로 가져온 배치를 전부 처리한 뒤 | 1 | 최대 `max.poll.records` 건 | 높음 | 일반적인 기본 선택 |
| `TIME` | 마지막 커밋 후 `ackTime` 이 지난 뒤 오는 첫 커밋 기회 | 2~3 (5초 설정) | 마지막 커밋 이후 전부 | 높음 | 초고속 스트림의 커밋 부하 억제 |
| `COUNT` | 처리 건수가 `ackCount` 이상이 될 때 | 5 (100 설정) | 최대 `ackCount` 건 | 높음 | 커밋 주기를 건수로 고정 |
| `COUNT_TIME` | `ackCount` **또는** `ackTime` 중 먼저 도달 | 5 | 둘 중 작은 쪽 | 높음 | 트래픽이 들쭉날쭉할 때 |
| `MANUAL` | `ack.acknowledge()` 를 큐에 넣고, poll 루프 끝에 모아서 커밋 | 1 | 마지막 커밋 이후 전부 | 높음 | 수동 제어 + 배치 커밋 |
| `MANUAL_IMMEDIATE` | `ack.acknowledge()` 호출 즉시 `commitSync` | 호출 횟수만큼 | 마지막 ack 이후 | 낮음 | 커밋 위치를 코드로 정밀 통제 |

표에서 놓치기 쉬운 네 가지만 다시 짚습니다.

- **BATCH** 는 spring-kafka 의 **기본값**입니다. `max.poll.records=500` 이면 500건에 커밋 1회이고, **이 스텝의 주요 함정(6-5)이 여기서 나옵니다.**
- **TIME** 은 "5초마다 커밋"이 아니라 "5초가 지났으면 다음 커밋 기회에 커밋"입니다. **트래픽이 멈추면 커밋도 멈춥니다.**
- **COUNT** 는 "정확히 100건마다"가 아니라 "100건 이상 쌓이면"입니다. 한 번의 poll 안에서도 여러 번 커밋됩니다. **COUNT_TIME** 은 둘의 OR 이라 반드시 **둘 다** 설정해야 의미가 있습니다.
- **MANUAL** 의 `acknowledge()` 는 즉시 커밋하지 않고 **큐에 넣기만** 합니다. poll 루프 끝에 컨테이너가 모아서 커밋하므로 BATCH 의 성능을 유지하면서 "무엇을 ack 할지"만 코드가 정합니다.

설정은 `application.yml` 의 `spring.kafka.listener.ack-mode`(전역 기본값) 또는 팩터리별 지정 두 가지입니다. 한 앱에서 모드를 섞어 쓰려면 후자뿐입니다.

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> recordAckFactory() {
    var f = new ConcurrentKafkaListenerContainerFactory<String, OrderCreated>();
    f.setConsumerFactory(consumerFactory);
    f.setConcurrency(1);
    f.getContainerProperties().setAckMode(AckMode.RECORD);
    return f;
}

@KafkaListener(topics = "orders", groupId = "s06-record", containerFactory = "recordAckFactory")
public void onMessage(OrderCreated event) { ... }
```

> ⚠️ `containerFactory` 를 지정하지 않으면 **기본 팩터리**가 쓰입니다.
> 즉 `application.yml` 의 `ack-mode` 가 적용되고, 여러분이 만든 팩터리의 설정은 무시됩니다.
> "AckMode 를 바꿨는데 로그가 그대로"라면 십중팔구 `containerFactory` 를 안 적었기 때문입니다. 경고도 나지 않습니다.

---

## 6-4. 커밋 시점을 로그로 확인 — RECORD vs BATCH

`ConsumerCoordinator` 를 DEBUG 로 올린 상태에서 두 모드를 각각 실행합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-record --app.step06.seed=false'
```

**결과** (일부)
```
INFO  14107 --- [           main] c.e.o.step06.Practice$Starter            : 프로필 step06-record 활성 → 리스너 s06-record 기동
INFO  14107 --- [ntainer#2-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s06-record: partitions assigned: [orders-0, orders-1, orders-2]
DEBUG 14107 --- [ntainer#2-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s06-record-1, groupId=s06-record] Committing offsets: {orders-0=OffsetAndMetadata{offset=1, leaderEpoch=0, metadata=''}}
DEBUG 14107 --- [ntainer#2-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s06-record-1, groupId=s06-record] Committing offsets: {orders-0=OffsetAndMetadata{offset=2, leaderEpoch=0, metadata=''}}
...
INFO  14107 --- [ntainer#2-0-C-1] c.e.o.step06.Practice$ThroughputMeter    : [6-4] RECORD 모드: 500건 처리, 119 ms, 4201 msg/s (마지막=ORD-0500)
```

`offset=1`, `2`, `3` … 이 **한 줄씩** 늘어납니다. `2>&1 | grep -c 'Committing offsets'` 로 세어 보면 정확히 **500** 줄입니다. 이제 BATCH.

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-batch --app.step06.seed=false'
```

**결과**
```
INFO  14107 --- [ntainer#1-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s06-batch: partitions assigned: [orders-0, orders-1, orders-2]
DEBUG 14107 --- [ntainer#1-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s06-batch-1, groupId=s06-batch] Committing offsets: {orders-0=OffsetAndMetadata{offset=167, leaderEpoch=0, metadata=''}, orders-1=OffsetAndMetadata{offset=166, leaderEpoch=0, metadata=''}, orders-2=OffsetAndMetadata{offset=167, leaderEpoch=0, metadata=''}}
INFO  14107 --- [ntainer#1-0-C-1] c.e.o.step06.Practice$ThroughputMeter    : [6-4] BATCH 모드: 500건 처리, 24 ms, 20833 msg/s (마지막=ORD-0500)
```

**커밋 로그가 한 줄입니다.** 그리고 그 한 줄에 세 파티션이 전부 들어 있습니다. 500건을 한 번의 `poll()` 로 다 가져왔기 때문입니다(`max.poll.records=500`).

| 항목 | RECORD | BATCH | 배수 |
|---|---:|---:|---:|
| 커밋 횟수 | 500 | 1 | 500× |
| 500건 소요 시간 | 119 ms | 24 ms | — |
| 처리량 | 4,201 msg/s | 20,833 msg/s | **약 5배** |
| 앱이 죽었을 때 재처리 | 최대 1건 | 최대 500건 | — |

**4,200 msg/s → 20,800 msg/s. 약 5배입니다.** 리스너가 아무 일도 안 하는 상태라 커밋 비용이 그대로 드러난 수치입니다.

> 💡 **RECORD 의 비용은 리스너가 빠를수록 크게 보인다**
> 리스너가 DB 를 한 번 건드려 건당 2ms 를 쓴다면, 500건 처리에 1,000ms 가 듭니다.
> 여기에 커밋 500회(약 95ms)가 붙어도 전체의 10% 미만입니다. **격차는 5배에서 1.1배로 줄어듭니다.**
> "RECORD 는 느리다"는 절대적 명제가 아니라, **여러분의 리스너 처리 시간에 비례해 희석되는 오버헤드**입니다.
> 처리가 무겁고 재처리가 비싸다면 RECORD 를 쓰는 것이 합리적입니다.

TIME 과 COUNT 도 같은 방법으로 확인합니다. `step06-count`(ackCount=100)로 돌리면 커밋 로그가 정확히 5줄이고, 세 파티션 오프셋의 합이 100, 200, 300 … 으로 늘어납니다.

```
DEBUG 14107 --- [ntainer#4-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : ... Committing offsets: {orders-0=OffsetAndMetadata{offset=34, ...}, orders-1=OffsetAndMetadata{offset=33, ...}, orders-2=OffsetAndMetadata{offset=33, ...}}
```

---

## 6-5. ⚠️ 핵심 함정 — BATCH 에서 배치 중간이 실패하면

이 절이 이 스텝의 본론입니다. `AckMode.BATCH` 는 기본값이므로, 여러분이 아무것도 설정하지 않았다면 지금 이 상태입니다.

상황을 단순화합니다. `poll()` 이 10건(오프셋 0~9)을 가져왔고, 5번째(오프셋 4)에서 예외가 납니다.

```
poll() → [0][1][2][3][4][5][6][7][8][9]
          ✓  ✓  ✓  ✓  ✗  ?  ?  ?  ?  ?
                      └─ 여기서 예외
```

**커밋은 어디까지 되어야 할까요?** 답은 "여러분이 예외를 어떻게 다루느냐"에 따라 완전히 달라집니다. 두 시나리오를 실제로 돌려 봅니다.

### 시나리오 A — 예외를 던진다 → 앞부분이 **중복** 처리된다

```java
public void onMessage(ConsumerRecord<String, OrderCreated> record) {   // AckMode.BATCH
    log.info("[6-5] 처리 {} (누적 호출 {}회) partition={} offset={}", ...);
    if (seq == 5) {
        throw new IllegalStateException("재고 서비스 응답 없음: " + record.value().orderId());
    }
}
```

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-rethrow --app.step06.seed=false'
```

**결과**
```
INFO  14107 --- [ntainer#5-0-C-1] c.e.o.step06.Practice$RethrowingListener : [6-5] 처리 ORD-0002 (누적 호출 1회) partition=0 offset=0
INFO  14107 --- [ntainer#5-0-C-1] c.e.o.step06.Practice$RethrowingListener : [6-5] 처리 ORD-0003 (누적 호출 2회) partition=0 offset=1
INFO  14107 --- [ntainer#5-0-C-1] c.e.o.step06.Practice$RethrowingListener : [6-5] 처리 ORD-0005 (누적 호출 3회) partition=0 offset=2
ERROR 14107 --- [ntainer#5-0-C-1] o.s.k.l.DefaultErrorHandler              : Error handler threw an exception
java.lang.IllegalStateException: 재고 서비스 응답 없음: ORD-0005
INFO  14107 --- [ntainer#5-0-C-1] c.e.o.step06.Practice$RethrowingListener : [6-5] 처리 ORD-0002 (누적 호출 4회) partition=0 offset=0
INFO  14107 --- [ntainer#5-0-C-1] c.e.o.step06.Practice$RethrowingListener : [6-5] 처리 ORD-0003 (누적 호출 5회) partition=0 offset=1
INFO  14107 --- [ntainer#5-0-C-1] c.e.o.step06.Practice$RethrowingListener : [6-5] 처리 ORD-0005 (누적 호출 6회) partition=0 offset=2
```

`ORD-0002`(offset 0) 와 `ORD-0003`(offset 1) 이 **두 번씩 처리됐습니다.** 실패한 것은 offset 2 인데 offset 0 부터 다시 읽었습니다. `DefaultErrorHandler`(지정하지 않아도 컨테이너가 기본으로 붙입니다)는 예외를 받으면 **실패한 레코드의 오프셋으로 `consumer.seek()`** 을 호출하는데, `AckMode.BATCH` 에서는 배치가 끝나야 커밋되므로 offset 0, 1 은 **커밋된 적이 없어** 함께 딸려 옵니다.

**유실은 없습니다. 대신 중복이 있습니다.** 이것이 at-least-once 입니다.

### 시나리오 B — 예외를 삼킨다 → 실패한 건이 **유실**된다

이제 진짜 위험한 쪽입니다. 실무에서 훨씬 자주 보는 코드이기도 합니다.

```java
public void onMessage(ConsumerRecord<String, OrderCreated> record) {   // AckMode.BATCH
    try {
        handle(record.value());
    } catch (RuntimeException ex) {
        // ⚠️ 이 한 줄이 메시지를 지웁니다.
        log.error("[6-5] 처리 실패, 일단 넘어갑니다: {}", record.value().orderId(), ex);
    }
}
```

이 코드를 쓴 사람의 의도는 나쁘지 않습니다. "한 건 실패했다고 파티션 전체를 멈출 순 없으니 로그를 남기고 넘어가자." 문제는 **넘어간다 = 커밋된다** 라는 사실을 인지하지 못한 것입니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-swallow --app.step06.seed=false'
```

**결과**
```
ERROR 14107 --- [ntainer#6-0-C-1] c.e.o.step06.Practice$SwallowingListener : [6-5] 처리 실패, 일단 넘어갑니다: ORD-0005 (1건째)
java.lang.IllegalStateException: 재고 서비스 응답 없음: ORD-0005
...
INFO  14107 --- [ntainer#6-0-C-1] c.e.o.step06.Practice$SwallowingListener : [6-5] 진행 상황: 성공 400 / 유실 100
DEBUG 14107 --- [ntainer#6-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : ... Committing offsets: {orders-0=OffsetAndMetadata{offset=167, ...}, orders-1=OffsetAndMetadata{offset=166, ...}, orders-2=OffsetAndMetadata{offset=167, ...}}
```

500건 중 100건이 실패했는데 **커밋은 500건 전부에 대해 정상적으로 나갔습니다.**

```bash
kcg --describe --group s06-swallow
```

**결과**
```
GROUP        TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                      HOST         CLIENT-ID
s06-swallow  orders  0          167             167             0    consumer-s06-swallow-1-8b3f...   /172.19.0.1  consumer-s06-swallow-1
s06-swallow  orders  1          166             166             0    consumer-s06-swallow-1-8b3f...   /172.19.0.1  consumer-s06-swallow-1
s06-swallow  orders  2          167             167             0    consumer-s06-swallow-1-8b3f...   /172.19.0.1  consumer-s06-swallow-1
```

**LAG 이 전부 0 입니다.** 모니터링 대시보드는 초록색입니다. 그런데 재고 100건이 차감되지 않았습니다.

> ⚠️ **함정 — "예외를 잡아서 로그만 찍는" 코드가 메시지를 지운다**
> **왜 위험한가**: 리스너가 정상 리턴하면 컨테이너는 "성공"으로 간주하고 커밋합니다. 컨테이너에게 "처리 실패"를 알리는 유일한 수단은 **예외를 던지는 것**뿐입니다. catch 로 삼키면 그 신호가 사라집니다.
> **증상**: 컨슈머 랙 0, 리스너 호출 횟수 정상, ERROR 로그는 있지만 아무도 안 봄. 며칠 뒤 "데이터가 비어 있다"로 발견.
> **해결**: 실패를 삼킬 거라면 **반드시 다른 곳에 남기고** 삼켜야 합니다. ① DLT 토픽으로 발행한 뒤 리턴(Step 07 의 `DeadLetterPublishingRecoverer` 가 자동화합니다) ② 실패 테이블에 INSERT 한 뒤 리턴 ③ 재시도가 의미 있는 실패라면 예외를 던지고 에러 핸들러에 맡긴다.
> **"아무 데도 안 남기고 catch 로 넘어가는" 코드만 금지**입니다. 그건 삭제와 같습니다.

두 시나리오를 나란히 놓으면 이렇습니다.

| | 예외를 던짐 | 예외를 삼킴 |
|---|---|---|
| 커밋 | 실패 지점 앞까지만 (BATCH 는 아예 안 됨) | 배치 전체가 커밋됨 |
| 실패 메시지 | 재시도 후 DLT 또는 로그 | **사라짐** |
| 성공한 앞부분 | **중복 처리** | 정상 |
| 컨슈머 랙 | 재시도 동안 증가 → 눈에 보임 | **항상 0** |
| 발견 난이도 | 쉬움 (로그·랙에 드러남) | 매우 어려움 |

**중복은 시끄럽고 유실은 조용합니다.** 그래서 Kafka 의 기본 전략은 at-least-once, 즉 "중복을 감수하고 유실을 막는" 쪽입니다(6-9).

---

## 6-6. `MANUAL` vs `MANUAL_IMMEDIATE`

커밋 시점을 코드가 직접 정하고 싶으면 수동 모드를 씁니다. 리스너 시그니처에 `Acknowledgment ack` 를 추가하면 컨테이너가 주입하고, `ack.acknowledge()` 로 커밋합니다. 두 모드의 차이는 `acknowledge()` 가 **언제 실제 커밋으로 바뀌는가** 입니다.

| | `MANUAL` | `MANUAL_IMMEDIATE` |
|---|---|---|
| `acknowledge()` 동작 | 큐에 적재만 | 즉시 `commitSync` |
| 실제 커밋 시점 | poll 루프 한 바퀴가 끝날 때 | 호출한 그 자리 |
| 브로커 왕복 | 배치당 1회 | ack 호출마다 1회 |
| 처리량 | BATCH 수준 | RECORD 수준 |
| 정밀도 | "이 배치에서 이것들을 ack 했다" | "지금 이 순간 커밋됐다" |

**MANUAL 을 쓰는 경우**: 배치 전체를 처리하지만 일부만 ack 하고 싶을 때. 성능은 BATCH 와 같습니다.
**MANUAL_IMMEDIATE 를 쓰는 경우**: "이 건이 외부 시스템에 확실히 반영된 직후 커밋" 처럼 커밋 순간이 의미를 가질 때.

### `nack` — "이 레코드부터 다시 읽어라"

`acknowledge()` 의 반대가 `nack()` 입니다. Spring Kafka 3.x 의 시그니처는 `Duration` 을 받습니다.

```java
ack.nack(Duration.ofSeconds(1));          // 레코드 리스너용: 현재 레코드부터 재처리, 1초 대기
ack.nack(47, Duration.ofSeconds(1));      // 배치 리스너용: index 47 부터 재처리, 0~46 은 커밋
```

> ⚠️ **함정 — `nack` 이후에 코드를 더 실행하면 안 된다**
> `nack()` 은 즉시 seek 하지 않습니다. 컨테이너에 "리턴하면 여기로 되돌려라"고 표시만 하고, 실제 seek 은 리스너가 리턴한 뒤 poll 루프에서 일어납니다.
> 그래서 `nack()` 뒤에 `acknowledge()` 를 호출하거나 다음 레코드를 계속 처리하면 두 신호가 충돌합니다.
> **`nack()` 다음 줄은 항상 `return`** 이라고 외워 두세요.
> 그리고 Spring Kafka 2.x 의 `nack(long sleepMillis)` 은 3.x 에서 **제거**됐습니다. 2.x 코드를 옮겨오면 `nack(1000)` 이 `nack(int index, ...)` 로 해석되거나 컴파일 에러가 납니다. 3.x 에는 `nack(Duration)` 과 `nack(int, Duration)` 두 개뿐입니다.

실행해 봅니다. `ORD-0007` 만 첫 시도에서 실패하도록 꾸며 두었습니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-manual --app.step06.seed=false'
```

**결과**
```
INFO 14107 --- [ntainer#7-0-C-1] c.e.o.step06.Practice$ManualAckListener  : [6-6] 처리 성공 → 즉시 커밋 ORD-0004 offset=1
WARN 14107 --- [ntainer#7-0-C-1] c.e.o.step06.Practice$ManualAckListener  : [6-6] 처리 실패 → nack, 1초 후 이 오프셋부터 다시 읽습니다 ORD-0007 offset=2
INFO 14107 --- [ntainer#7-0-C-1] c.e.o.step06.Practice$ManualAckListener  : [6-6] 처리 성공 → 즉시 커밋 ORD-0007 offset=2
INFO 14107 --- [ntainer#7-0-C-1] c.e.o.step06.Practice$ManualAckListener  : [6-6] 처리 성공 → 즉시 커밋 ORD-0010 offset=3
```

`ORD-0007` 이 1초 뒤 재시도되어 성공했고, **앞의 `ORD-0004` 는 다시 처리되지 않았습니다.** 이미 `acknowledge()` 로 커밋됐기 때문입니다. 6-5 의 시나리오 A 와 결정적으로 다른 점입니다.

---

## 6-7. ⚠️ 함정 — `Acknowledgment` 를 받고 `acknowledge()` 를 안 부르면

수동 모드로 바꾼 직후 가장 흔한 사고입니다.

```java
@KafkaListener(id = "s06-forget", ..., containerFactory = "manualAckFactory")
public void onMessage(OrderCreated event, Acknowledgment ack) {
    log.debug("[6-7] 처리 {}", event.orderId());
    // ⚠️ ack.acknowledge(); 를 부르지 않았습니다.
}
```

컴파일됩니다. 기동됩니다. 500건 전부 정상 처리됩니다. 경고 로그도 없습니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-forget --app.step06.seed=false'
```

**결과**
```
INFO 14107 --- [ntainer#8-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s06-forget: partitions assigned: [orders-0, orders-1, orders-2]
INFO 14107 --- [ntainer#8-0-C-1] c.e.o.step06.Practice$ForgetfulListener  : [6-7] 500건 처리했지만 커밋은 0회입니다. kcg --describe --group s06-forget 확인
```

`Committing offsets:` 로그는 **한 줄도 없습니다.** 앱을 끄고 `kcg --describe --group s06-forget` 으로 확인합니다.

**결과**
```
Consumer group 's06-forget' has no active members.

GROUP       TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
s06-forget  orders  0          -               167             -    -            -     -
s06-forget  orders  1          -               166             -    -            -     -
s06-forget  orders  2          -               167             -    -            -     -
```

`CURRENT-OFFSET` 이 `0` 이 아니라 **`-`** 입니다. 커밋이 한 번도 없어서 `__consumer_offsets` 에 이 그룹의 레코드 자체가 존재하지 않습니다. `LAG` 도 계산할 수 없어 `-` 입니다. 앱을 재시작하면 `auto.offset.reset=earliest` 가 적용돼 **500건을 처음부터 전부 다시 처리**하고, 재시작할 때마다 계속 그렇습니다.

> ⚠️ **함정 — `acknowledge()` 누락은 어떤 신호도 내지 않는다**
> **왜 위험한가**: 정상 처리처럼 보이는 시간이 임의로 길 수 있습니다. 배포 전까지는 아무 문제가 없습니다.
> **증상**: 재시작·배포·리밸런스 때마다 같은 메시지가 대량 재처리됩니다. 멱등하지 않은 처리라면 중복 결제·중복 알림이 됩니다. 운영 중이라면 `LAG` 이 줄지 않고 계속 쌓입니다.
> **해결**: ① 수동 모드라면 리스너의 **모든 종료 경로**(early return, 필터링, catch 블록)에서 ack 를 호출했는지 점검 ② `finally` 블록에서 ack 하는 것은 **금지**입니다. 실패해도 커밋되어 6-5 의 유실과 같아집니다 ③ 애초에 수동 모드가 꼭 필요한지 재검토. 대부분의 리스너는 `BATCH` 또는 `RECORD` + `DefaultErrorHandler` 로 충분합니다.

반대 실수도 있습니다. **AckMode 가 수동이 아닌데(예: `batchAckFactory`) `Acknowledgment` 를 선언**하면 어떻게 될까요?

**결과**
```
***************************
APPLICATION FAILED TO START
***************************

Description:

java.lang.IllegalStateException: No Acknowledgment available as an argument, the listener container must have a MANUAL AckMode to populate the Acknowledgment.
```

**기동이 실패합니다.** 이건 다행인 실수입니다. 조용하지 않으니까요. 이 코스에서 반복하는 원칙 그대로입니다 — **터지는 실수보다 조용한 실수가 훨씬 위험합니다.**

---

## 6-8. 배치 리스너의 부분 실패 (이 스텝의 하이라이트)

배치 리스너는 `List<ConsumerRecord<K, V>>` 로 한꺼번에 받습니다(`factory.setBatchListener(true)`). DB 벌크 INSERT 처럼 묶어서 처리하면 훨씬 빠르기 때문에 실무에서 자주 씁니다. 그런데 **배치 중간에서 실패하면 무슨 일이 일어날까요?** 47번 인덱스에서 일반 예외를 던져 봅니다.

### 기본 동작 — 배치 전체가 재처리된다

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-batch-whole --app.step06.seed=false'
```

**결과**
```
INFO  14107 --- [ntainer#9-0-C-1] c.e.o.s.Practice$BatchWholeRetryListener : [6-8] 배치 수신 167건 (offset 0 ~ 166)
ERROR 14107 --- [ntainer#9-0-C-1] c.e.o.s.Practice$BatchWholeRetryListener : [6-8] index=47 에서 실패. 지금까지 누적 처리 47건
WARN  14107 --- [ntainer#9-0-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=1000, currentAttempts=0, maxAttempts=2} exhausted? no. Retrying batch
INFO  14107 --- [ntainer#9-0-C-1] c.e.o.s.Practice$BatchWholeRetryListener : [6-8] 배치 수신 167건 (offset 0 ~ 166)
ERROR 14107 --- [ntainer#9-0-C-1] c.e.o.s.Practice$BatchWholeRetryListener : [6-8] index=47 에서 실패. 지금까지 누적 처리 94건
INFO  14107 --- [ntainer#9-0-C-1] c.e.o.s.Practice$BatchWholeRetryListener : [6-8] 배치 수신 167건 (offset 0 ~ 166)
ERROR 14107 --- [ntainer#9-0-C-1] c.e.o.s.Practice$BatchWholeRetryListener : [6-8] index=47 에서 실패. 지금까지 누적 처리 141건
WARN  14107 --- [ntainer#9-0-C-1] o.s.k.l.DefaultErrorHandler              : Backoff FixedBackOff{interval=1000, currentAttempts=2, maxAttempts=2} exhausted for orders-0@0
```

`배치 수신 167건 (offset 0 ~ 166)` 이 **세 번** 찍혔습니다. 누적 처리는 47 → 94 → 141 로 늘었습니다. **매번 0~46 번 47건을 다시 처리한 것입니다.**

일반 예외(`IllegalStateException`)를 던졌기 때문입니다. 에러 핸들러 입장에서는 리스트 어디가 실패했는지 알 방법이 없습니다. 그래서 **배치의 첫 오프셋으로 seek** 하고 통째로 다시 넘깁니다.

> ⚠️ **함정 — `BatchListenerFailedException` 이 아닌 예외를 던지면 배치 전체가 조용히 중복 처리된다**
> **왜 위험한가**: 로그에는 예외 스택 한 개와 재시도 안내만 남습니다. "재시도 중"이라는 정보는 있어도 "47건이 이미 처리됐고 또 처리된다"는 정보는 어디에도 없습니다.
> **증상**: 재고가 2배로 차감되거나, 알림이 두 번 발송되거나, 집계가 부풀려집니다. 배치 크기가 500 이면 최대 499건이 중복됩니다.
> **해결**: 아래 세 가지 중 하나. ①②는 실패 위치를 프레임워크에 알려 주는 것이고 ③은 배치를 포기하는 것입니다.

### 해결 ① `ack.nack(index, Duration)` — 앞부분을 직접 커밋

`AckMode.MANUAL` + 배치 리스너입니다.

```java
for (int i = 0; i < records.size(); i++) {
    if (i == 47) {
        ack.nack(i, Duration.ofSeconds(1));   // 0~46 커밋, 47 부터 재시도
        return;                               // ⚠️ 반드시 즉시 리턴
    }
    totalHandled.incrementAndGet();
}
ack.acknowledge();                            // 끝까지 갔으면 배치 전체 커밋
```

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-batch-nack --app.step06.seed=false'
```

**결과**
```
INFO  14107 --- [ntainer#10-0-C-1] c.e.o.step06.Practice$BatchNackListener : [6-8] 배치 수신 167건 (offset 0 ~ 166)
WARN  14107 --- [ntainer#10-0-C-1] c.e.o.step06.Practice$BatchNackListener : [6-8] index=47 실패 → nack(47, 1s). 0~46 은 커밋됩니다. 누적 47건
DEBUG 14107 --- [ntainer#10-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator : ... Committing offsets: {orders-0=OffsetAndMetadata{offset=47, leaderEpoch=0, metadata=''}}
INFO  14107 --- [ntainer#10-0-C-1] c.e.o.step06.Practice$BatchNackListener : [6-8] 배치 수신 120건 (offset 47 ~ 166)
INFO  14107 --- [ntainer#10-0-C-1] c.e.o.step06.Practice$BatchNackListener : [6-8] 배치 전체 커밋. 누적 처리 167건
```

두 번째 배치가 **`offset 47` 부터 120건**입니다. 0~46 은 다시 오지 않았습니다. 누적 처리도 47 + 120 = 167 로 정확히 한 번씩입니다.

커밋 로그의 `offset=47` 도 6-1 의 규칙 그대로입니다. **0~46 을 처리했으니 다음 위치는 47.**

### 해결 ② `BatchListenerFailedException(message, index)`

수동 ack 없이, 예외에 인덱스를 실어 보냅니다. `DefaultErrorHandler` 가 나머지를 처리합니다.

```java
throw new BatchListenerFailedException("결제 승인 거절: " + record.value().orderId(), i);
```

```bash
./gradlew bootRun --args='--spring.profiles.active=step06,step06-batch-failedex --app.step06.seed=false'
```

**결과**
```
INFO  14107 --- [ntainer#11-0-C-1] c.e.o.s.Practice$BatchFailedExceptionListener : [6-8] 배치 수신 167건 (offset 0 ~ 166)
WARN  14107 --- [ntainer#11-0-C-1] c.e.o.s.Practice$BatchFailedExceptionListener : [6-8] index=47 실패 → BatchListenerFailedException. 누적 47건
DEBUG 14107 --- [ntainer#11-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator      : ... Committing offsets: {orders-0=OffsetAndMetadata{offset=47, leaderEpoch=0, metadata=''}}
WARN  14107 --- [ntainer#11-0-C-1] o.s.k.l.DefaultErrorHandler                  : Retrying batch from index 47
INFO  14107 --- [ntainer#11-0-C-1] c.e.o.s.Practice$BatchFailedExceptionListener : [6-8] 배치 수신 120건 (offset 47 ~ 166)
INFO  14107 --- [ntainer#11-0-C-1] c.e.o.s.Practice$BatchFailedExceptionListener : [6-8] 배치 완료. 누적 처리 167건
```

`Retrying batch from index 47` — 에러 핸들러가 인덱스를 인지했다는 증거입니다. 결과는 ①과 동일합니다. 인덱스 계산이 헷갈리면 `new BatchListenerFailedException("결제 승인 거절", record)` 처럼 `ConsumerRecord` 를 넘기는 생성자가 더 안전합니다(같은 배치 안의 인스턴스여야 합니다).

### 해결 ③ 배치 리스너를 포기하고 레코드 리스너로

가장 단순하고, 놀랍게도 자주 정답입니다(`setBatchListener(false)` + `AckMode.RECORD`). 배치 리스너의 이점은 "묶어서 처리"할 때만 나옵니다. `for` 문으로 한 건씩 도는 배치 리스너라면 **레코드 리스너와 처리 로직이 같으면서 실패 처리만 어려워진** 것입니다. 그럴 바엔 레코드 리스너가 낫습니다.

### 세 방법 비교

| | ① `ack.nack(i, d)` | ② `BatchListenerFailedException` | ③ 레코드 리스너 |
|---|---|---|---|
| AckMode | `MANUAL` 필수 | `BATCH` 로 충분 | `RECORD` 권장 |
| 에러 핸들러 | 불필요 | `DefaultErrorHandler` 필요 | 필요 |
| 재시도 정책 | 직접 구현 | 백오프·DLT 를 프레임워크가 제공 | 프레임워크가 제공 |
| 벌크 처리 | 가능 | 가능 | **불가** |
| 코드 복잡도 | 중간 (ack 누락 위험) | 낮음 | 가장 낮음 |
| 권장 | 커밋을 정밀 통제할 때 | **기본 선택** | 벌크 이점이 없을 때 |

> 💡 **실무 팁 — 배치 리스너를 쓰기로 했다면 `BatchListenerFailedException` 을 규약으로 정하라**
> 팀 컨벤션에 "배치 리스너에서 던지는 예외는 반드시 `BatchListenerFailedException` 으로 감싼다"를 넣으세요.
> 리스너 안에서 호출하는 서비스 계층이 던진 일반 예외가 그대로 빠져나가면 그 순간 통째 재처리가 됩니다.
> 방어적으로는 배치 루프 전체를 `try-catch` 로 감싸고, 잡은 예외를 **현재 인덱스와 함께** 다시 던지는 형태가 안전합니다.
> ```java
> for (int i = 0; i < records.size(); i++) {
>     try { handle(records.get(i)); }
>     catch (Exception e) { throw new BatchListenerFailedException(e.getMessage(), i); }
> }
> ```

---

## 6-9. 정확히 한 번 처리는 커밋만으로 안 된다

**"커밋을 정확한 시점에 하면 exactly-once 가 되지 않나?"** 안 됩니다. **"메시지를 처리한다"(MySQL 에 쓰기)와 "오프셋을 커밋한다"(Kafka 에 쓰기)는 서로 다른 두 시스템에 대한 두 번의 쓰기**이기 때문입니다. 그 사이에 프로세스가 죽으면 순서를 어떻게 잡아도 한쪽이 깨집니다.

| 순서 | 중간에 죽으면 |
|---|---|
| 처리 → 커밋 | 처리는 됐는데 커밋 안 됨 → **재처리(중복)** |
| 커밋 → 처리 | 커밋은 됐는데 처리 안 됨 → **유실** |

둘 중 하나를 골라야 하고, 세상은 **중복(at-least-once)** 을 고릅니다. 유실보다 중복이 낫기 때문입니다.

그럼 중복은? **처리 쪽을 멱등(idempotent)하게** 만듭니다. 같은 메시지를 두 번 처리해도 결과가 한 번 처리한 것과 같도록. Step 13 의 `processed_message` 테이블(`message_id` UNIQUE)에 먼저 INSERT 하고, `DuplicateKeyException` 이 나면 건너뛰는 식입니다.

> 💡 **결론 — 이 스텝에서 배운 것은 "유실을 막는 법"이지 "중복을 없애는 법"이 아니다**
> AckMode 를 아무리 정교하게 맞춰도 중복은 남습니다. 리밸런스 한 번이면 마지막 커밋 이후가 전부 재처리됩니다.
> **중복은 커밋 설정이 아니라 처리 로직의 멱등성으로 해결합니다.** Step 13 에서 멱등 컨슈머를 구현합니다.
> Kafka 트랜잭션(Step 09)이 "exactly-once semantics"를 제공하지만, 그것도 **Kafka → Kafka** 경로에 한정됩니다. DB 가 끼면 다시 멱등성 문제입니다.

> 💡 at-least-once / at-most-once / exactly-once 의 정의와 브로커 측 보증은
> [Kafka 코스 Step 07](../../kafka/step-07-delivery-semantics/) 을 참고하세요.

---

## 6-10. 커밋 실패 처리

커밋도 네트워크 호출이라 실패합니다. 가장 흔한 것이 **리밸런스 중 커밋**입니다.

```
ERROR 14107 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Commit cannot be completed since the group has already rebalanced and assigned the partitions to another member.
org.apache.kafka.clients.consumer.CommitFailedException: Offset commit cannot be completed since the consumer is not part of an active group for auto partition assignment; it is likely that the consumer was kicked out of the group.
```

리스너가 배치를 처리하는 데 `max.poll.interval.ms`(기본 5분)보다 오래 걸리면, 브로커는 그 컨슈머가 죽었다고 판단하고 파티션을 다른 멤버에게 넘깁니다. 뒤늦게 처리를 마친 컨슈머가 커밋하려 하면 **"너는 이미 그 파티션의 주인이 아니다"** 라며 거부당합니다. **그 배치는 다른 컨슈머가 이미 처리 중이므로 중복 처리입니다.** 유실은 아니지만 정확히 6-9 의 상황입니다.

**① 애초에 리밸런스를 안 나게 한다 (근본 해결)** — 한 번의 poll 로 가져오는 양을 줄이는 것이 가장 효과적입니다. 500건 × 건당 1초 = 500초 > 300초라 리밸런스가 나는 것이므로, `max-poll-records` 를 50 으로 줄이면 50초로 끝납니다.

```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 50            # 500 → 50. 한 배치를 짧게
      properties:
        max.poll.interval.ms: 300000  # 처리가 정말 오래 걸린다면 늘린다
```

**② 커밋 자체의 재시도를 조절한다** — `f.getContainerProperties().setSyncCommits(true)` / `setCommitRetries(5)`.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `syncCommits` | `true` | `commitSync` 사용. `false` 면 `commitAsync` — 빠르지만 **실패를 모른 채 넘어갑니다** |
| `commitCallback` | 없음 | `syncCommits=false` 일 때 커밋 결과를 받는 콜백. **비동기 커밋을 쓴다면 필수** |
| `commitRetries` | 3 | `RetriableCommitFailedException` 재시도 횟수 (3.0+) |

> ⚠️ **함정 — `syncCommits=false` 는 커밋 실패를 침묵시킨다**
> `commitAsync` 는 결과를 기다리지 않으므로 처리량에 유리합니다. 하지만 커밋이 실패해도 **콜백을 등록하지 않으면 아무도 모릅니다.** 그 상태로 앱이 재시작되면 커밋되지 않은 구간이 전부 재처리됩니다(6-7 의 `acknowledge()` 누락과 증상이 같습니다).
> 비동기 커밋을 쓸 거라면 `setCommitCallback((offsets, ex) -> { if (ex != null) log.error(...); })` 을 반드시 함께 다세요.

> 💡 `CommitFailedException` 은 **잡아도 할 일이 없습니다.** 이미 파티션을 잃었으므로 재커밋도 불가능합니다. "리밸런스가 났다"는 신호로 읽고 원인(느린 처리, `max.poll.interval.ms`, 네트워크)을 찾는 것이 맞습니다.
> 리밸런스 프로토콜 자체는 [Kafka 코스 Step 05](../../kafka/step-05-consumer/) 를 참고하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 커밋된 오프셋 | **다음에 읽을 위치** = 마지막 처리 오프셋 + 1. `CURRENT-OFFSET=43` 이면 42까지 처리 |
| 커밋 저장소 | 브로커의 `__consumer_offsets` 토픽. 그룹 단위이므로 서버가 바뀌어도 이어짐 |
| 커밋의 한계 | 파티션당 **정수 하나**. "3,5는 성공 4는 실패" 같은 집합은 표현 불가 |
| `enable.auto.commit` | 처리와 무관하게 시간 기준으로 커밋 → **유실**. Spring 이 `false` 로 꺼 줌 |
| `AckMode` | Kafka 설정이 아니라 **Spring 컨테이너**의 커밋 시점 정책 |
| `RECORD` | 건당 커밋. 500건에 커밋 500회, 4,200 msg/s. 재처리 최대 1건 |
| `BATCH`(기본) | 배치 끝에 1회 커밋. 20,800 msg/s(**약 5배**). 재처리 최대 `max.poll.records` 건 |
| `TIME`/`COUNT` | "N ms 마다"가 아니라 "N ms 지난 뒤 첫 기회". 트래픽이 멈추면 커밋도 멈춤 |
| `MANUAL` | `acknowledge()` 는 큐에 적재, poll 루프 끝에 커밋. 성능은 BATCH 급 |
| `MANUAL_IMMEDIATE` | `acknowledge()` 즉시 `commitSync`. 정밀하지만 RECORD 급 비용 |
| ⚠️ 예외를 삼킴 | 리스너가 정상 리턴 → **커밋됨 → 유실**. LAG 은 0. 가장 찾기 어려운 버그 |
| ⚠️ 예외를 던짐 | 실패 지점으로 seek → 앞의 성공분까지 **중복** 처리. 시끄럽지만 안전 |
| ⚠️ `acknowledge()` 누락 | 커밋 0회. `CURRENT-OFFSET` 이 `-`. 재시작하면 전부 재처리 |
| 수동 모드 오설정 | AckMode 가 MANUAL 이 아닌데 `Acknowledgment` 선언 → **기동 실패**(다행) |
| ⚠️ 배치 부분 실패 | 일반 예외를 던지면 **배치 통째 재처리**. 500건 배치면 최대 499건 중복 |
| 배치 부분 실패 해결 | ① `ack.nack(i, Duration)` ② `BatchListenerFailedException(msg, i)` ③ 레코드 리스너 |
| `nack` 규칙 | 3.x 는 `nack(Duration)` / `nack(int, Duration)`. **다음 줄은 항상 `return`** |
| exactly-once | 커밋 설정으로는 불가능. at-least-once + **멱등 처리**가 현실적 답 (Step 13) |
| `CommitFailedException` | 리밸런스로 파티션을 잃은 것. 잡아도 할 일 없음. 원인은 느린 처리 |
| `syncCommits=false` | 커밋 실패를 침묵시킴. 쓸 거면 `commitCallback` 필수 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 로그와 `kcg --describe` 를 직접 확인**하세요.

1. `COUNT`(100건) / `TIME`(3초) / `COUNT_TIME` 팩터리를 완성하고, 각 모드의 커밋 로그가 몇 번 찍히는지 세기
2. `MANUAL_IMMEDIATE` 로 조건부 커밋 구현 — 필터링된 메시지도 커밋해야 하는 이유 설명하기
3. `acknowledge()` 를 누락한 채 실행 → `CURRENT-OFFSET` 이 `-` 인 것 확인 → 재시작해 전량 재처리 재현
4. 배치 리스너에서 `BatchListenerFailedException` 으로 부분 커밋 구현 — 중복 처리 건수 세기
5. `try-catch` 로 예외를 삼켜 유실을 재현한 뒤, **예외를 던지지 않고** 유실을 없애도록 수정
6. `RECORD` vs `BATCH` 의 처리량과 커밋 횟수를 500건으로 실측해 표로 정리

---

## 다음 단계

커밋 범위를 통제하는 법은 익혔지만, "실패했을 때 몇 번 재시도하고, 끝내 실패하면 어디에 버릴 것인가"는 아직 열려 있습니다. 6-5 에서 잠깐 등장한 `DefaultErrorHandler` 를 정면으로 다룹니다. `FixedBackOff` 와 `ExponentialBackOff`, `DeadLetterPublishingRecoverer` 로 DLT 를 구성하고, **블로킹 재시도가 파티션 전체를 멈춰 세우는 함정**을 재현합니다.

→ [Step 07 — 에러 처리와 재시도](../step-07-error-handling/)

---

## 실습 파일

세 파일을 순서대로 씁니다. 먼저 `Practice.java` 를 프로필별로 하나씩 켜 가며 6-0 ~ 6-10 의 커밋 로그를 전부 눈으로 확인하고, 그다음 `Exercise.java` 의 6문제를 풀어 본 뒤, `Solution.java` 로 정답과 "왜 그 답인지"를 대조합니다. 세 파일 모두 `com.example.order.step06` 패키지이고 프로필이 서로 다르므로(`step06` / `step06ex` / `step06sol`) 함께 두어도 충돌하지 않습니다. **다만 동시에 켜지는 마세요.** 커밋 로그가 뒤섞이면 이 스텝은 아무것도 배울 수 없습니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 실행 파일입니다.

- `AckModeFactories` 가 7종 AckMode 팩터리 + 배치용 3종 + `[6-10]` 의 `resilientCommitFactory` 를 한 자리에 모아 둡니다. 모든 팩터리가 `setConcurrency(1)` 인 것에 주목하세요. **커밋 횟수를 세는 것이 목적이라 스레드가 3개면 로그가 세 갈래로 섞여 셀 수 없습니다.**
- 모든 리스너가 `autoStartup = "false"` 이고, `Starter` 가 활성 프로필을 보고 해당 리스너 **하나만** 기동합니다. `--spring.profiles.active=step06,step06-record` 처럼 항상 `step06` 과 보조 프로필을 함께 주세요. `step06` 이 빠지면 팩터리 빈이 등록되지 않아 리스너가 기동에 실패합니다.
- `Seeder` 는 `--app.step06.seed=false` 로 끌 수 있습니다. 첫 실행에서 500건을 넣은 뒤로는 이 옵션을 붙이는 게 좋습니다. 안 그러면 실행할 때마다 500건씩 쌓여 6-4 의 측정 수치가 달라집니다.
- `[6-5]` 의 `SwallowingListener` 와 `RethrowingListener` 는 **같은 실패 조건**(seq % 5 == 0 / seq == 5)을 서로 다르게 처리하는 대조군 쌍입니다. 두 프로필을 각각 실행해 로그를 나란히 놓고 비교하세요. 유실과 중복이 한 눈에 구별됩니다.
- `[6-8]` 의 `BatchNackListener` / `BatchFailedExceptionListener` 에 있는 `alreadyFailedOnce` 플래그는 학습용 장치입니다. 실패 조건이 계속 참이면 재시도 소진 → 다음 레코드 → 또 실패가 반복돼 로그가 끝없이 흐릅니다. 한 번만 실패시켜 커밋 범위 변화를 깨끗하게 관찰합니다. `[6-7]` 의 `ForgetfulListener` 안에는 `// ack.acknowledge();` 가 주석 처리돼 있으니, 먼저 그대로 실행해 `CURRENT-OFFSET` 이 `-` 인 것을 확인하고 그다음 주석을 풀어 차이를 보세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `--app.step06ex.run=q3` 처럼 옵션으로 리스너를 하나만 골라 켜는 구조입니다.

- **문제 1** 은 팩터리를 채우는 문제입니다. `f.setConsumerFactory(...)` 까지만 되어 있고 `concurrency` 와 `AckMode`, `ackCount`/`ackTime` 은 비어 있습니다. 여기를 안 채우면 AckMode 기본값(BATCH)으로 동작해 커밋 로그가 1줄만 나옵니다 — **그것 자체가 힌트입니다.**
- **문제 2·4·5** 는 리스너 본문을 채우는 문제이고, **문제 3·6** 은 "실행하고 관찰한 뒤 기록하는" 문제입니다. 특히 문제 3 은 코드를 두 번(누락 상태 / 추가 상태) 실행해야 답이 완성됩니다.
- 문제마다 컨슈머 그룹이 `s06ex-q1-count`, `s06ex-q2` … 로 분리돼 있습니다. 같은 문제를 다시 풀 때는 앱을 끄고 해당 그룹만 리셋하세요. `kcg --group s06ex-q3 --topic orders --reset-offsets --to-earliest --execute`
- ⚠️ **문제 5 의 `handle()` 메서드는 손대지 마세요.** `customerId == 1013` 에서 예외를 던지는 것이 문제의 전제입니다. 이 조건은 `seq % 30 == 13` 이므로 500건 중 약 17건이 걸립니다. "17건이 사라졌다"를 세는 것이 문제의 목표입니다.
- 문제 6 의 시작 시각은 `AtomicLong.compareAndSet(0L, System.nanoTime())` 으로 **첫 레코드 수신 시점**을 잡아야 합니다. 컨테이너 기동·그룹 조인에 수백 ms 가 걸려서, 그것까지 포함하면 커밋 비용 차이가 묻힙니다.

```java file="./Exercise.java"
```

### Solution.java

정답과, "왜 그 답인지"를 설명하는 긴 블록 주석이 함께 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 의 핵심은 `AckMode` 가 `ContainerProperties` 의 속성이라는 것입니다. `application.yml` 의 `ack-mode` 는 Boot 가 만든 **기본 팩터리에만** 적용되므로, 직접 `new` 한 팩터리에는 매번 명시해야 합니다. 그리고 `ackCount=100` 은 "정확히 100건마다"가 아니라 "100건 이상 쌓이면"이라는 점을 주석으로 짚습니다.
- **정답 2** 는 "처리 안 함"과 "커밋 안 함"이 완전히 다른 결정이라는 것을 설명합니다. 필터링으로 건너뛴 메시지도 **반드시 커밋해야** 합니다. 커밋은 커서라서, 중간 하나를 커밋하지 않고 뒤를 커밋하면 앞 메시지가 유실됩니다.
- **정답 3** 은 `CURRENT-OFFSET` 이 `0` 이 아니라 `-` 로 나오는 이유(그룹의 오프셋 레코드 자체가 없음)와, `auto.offset.reset` 이 `latest` 였다면 재처리가 아니라 **유실**이 됐을 것이라는 더 나쁜 시나리오를 함께 설명합니다.
- **정답 4** 는 `BatchListenerFailedException` 의 index 인자가 어떻게 쓰이는지(`records.get(index)` 의 오프셋을 계산해 그 앞을 커밋하고 seek), 그리고 **일반 예외를 던지면 왜 조용한 중복이 되는지**를 대조해 설명합니다. 인덱스 계산이 불안하면 `ConsumerRecord` 를 넘기는 생성자를 쓰라는 대안도 답니다.
- **정답 5** 는 "예외를 그냥 던진다"가 금지인 이유(영구 실패라 재시도해도 성공하지 않고 파티션이 멈춤)를 설명하고, DLT 발행 후 정상 리턴하는 형태를 제시합니다. 특히 **`template.send(DLT, ...).get()` 으로 동기 대기**하고, DLT 발행마저 실패하면 그때는 예외를 던지는 구조가 중요합니다. **"어디에도 못 남겼으면 커밋하지 않는다"** 가 유실 방지의 유일한 규칙입니다.
- **정답 6** 은 실측 수치(RECORD 119ms/4,200 msg/s/커밋 500회 vs BATCH 24ms/20,800 msg/s/커밋 1회)와 함께, 리스너가 무거워질수록 이 격차가 희석된다는 점 — 즉 **RECORD 가 항상 나쁜 선택은 아니라는 것**을 설명합니다.

```java file="./Solution.java"
```
