# Step 07 — 전달 보장

> **학습 목표**
> - at-most-once / at-least-once / exactly-once 를 프로듀서 설정과 커밋 시점으로 정의하고 **셋 다 직접 재현한다**
> - 멱등 프로듀서가 PID + 시퀀스 번호로 중복 배치를 걸러내는 과정을 `kafka-dump-log.sh` 로 **눈으로 확인한다**
> - 멱등성이 단일 프로듀서 세션·단일 파티션에만 유효하다는 한계를 실측한다
> - 트랜잭션 API 로 consume-transform-produce 를 구현하고, abort 시 `read_committed` 컨슈머가 무엇을 보는지 확인한다
> - 컨트롤 레코드와 LSO 때문에 **에러 없이 컨슈머가 멈춘 것처럼 보이는** 상황을 재현한다
> - "exactly-once 는 Kafka 내부 한정" 이라는 결론에 도달하고 실무 해법(at-least-once + 멱등 처리)을 설계한다
>
> **선행 스텝**: Step 06 — 오프셋 관리
> **예상 소요**: 120분

---

Step 06 에서 확인한 것은 이것입니다. **커밋을 처리 앞에 두면 유실이고, 뒤에 두면 중복입니다.** 그리고 자동 커밋은 둘 중 무엇이 될지 운에 맡깁니다.

이 스텝은 그 선택을 이름 붙이고, 세 번째 선택지가 정말로 존재하는지 확인합니다. Kafka 는 "exactly-once semantics" 를 지원한다고 말합니다. 그 말이 정확히 어디까지 참인지가 이 스텝의 주제입니다. 결론부터 말하면 **참이지만 범위가 좁습니다.**

---

## 7-0. 실습 준비

이 스텝은 토픽 두 개를 씁니다. 주문을 읽어 결제를 만드는 파이프라인을 흉내낼 것이므로 입력과 출력이 필요합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --create --topic s07_orders --partitions 1 --replication-factor 3

docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --create --topic s07_payments --partitions 1 --replication-factor 3
```

**결과**

```
Created topic s07_orders.
Created topic s07_payments.
```

파티션을 1개로 둔 이유는 Step 06 과 같습니다. 오프셋과 시퀀스 번호를 눈으로 좇는 것이 목적이라 흩어지면 곤란합니다.

`s07_orders` 에 100건을 넣습니다.

```bash
for i in $(seq 3001 3100); do
  c=$(printf "C%03d" $(( (i % 10) + 1 )))
  echo "${c}:{\"order_id\":\"O-${i}\",\"customer_id\":\"${c}\",\"amount\":39000,\"status\":\"CREATED\"}"
done | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s07_orders \
  --property parse.key=true --property key.separator=:

docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s07_orders
```

**결과**

```
s07_orders:0:100
```

---

## 7-1. 세 가지 전달 보장

"전달 보장" 은 사실 **두 개의 독립적인 질문**입니다.

- **프로듀서 → 브로커**: 보낸 메시지가 로그에 몇 번 기록되는가?
- **브로커 → 컨슈머 → 처리**: 읽은 메시지가 몇 번 처리되는가?

두 구간이 각각 유실/중복을 만들 수 있고, 흔히 말하는 세 가지 보장은 이 둘의 조합입니다.

| 보장 | 프로듀서 설정 | 컨슈머 커밋 시점 | 결과 |
|---|---|---|---|
| **at-most-once** (최대 한 번) | `acks=0` 또는 `acks=1` + `retries=0` | **처리 전에** 커밋 | 유실 가능, 중복 없음 |
| **at-least-once** (최소 한 번) | `acks=all` + `retries>0` | **처리 후에** 커밋 | 유실 없음, **중복 가능** |
| **exactly-once** (정확히 한 번) | `enable.idempotence=true` + `transactional.id` | 처리와 커밋이 **같은 트랜잭션** | 유실 없음, 중복 없음 — **단, Kafka 내부에 한해서** |

```
   at-most-once                at-least-once               exactly-once
   ───────────────             ───────────────             ───────────────
   ① 커밋                       ① 처리                       ┌ 트랜잭션 ─────┐
   ② 처리                       ② 커밋                       │ ① 처리        │
                                                            │ ② 출력 produce │
   ①과② 사이에 죽으면          ①과② 사이에 죽으면          │ ③ 오프셋 커밋  │
   → 유실                      → 중복                      └───── 원자적 ───┘
```

이 셋을 차례로 재현합니다.

---

## 7-2. at-most-once 재현 — 커밋 먼저, 처리 나중

`Practice.java` 의 `at-most-once` 시나리오입니다. 핵심은 순서 하나뿐입니다.

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
    if (records.isEmpty()) continue;

    consumer.commitSync();                       // ① 커밋을 먼저 한다

    for (ConsumerRecord<String, String> r : records) {
        process(r);                              // ② 그다음 처리
        processed++;
        if (processed == 35) die();              // 처리 도중 죽는다
    }
}
```

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-most-once'
```

**결과**

```
[setup] 그룹 s07-amo, 커밋 → 처리 순서
[batch] 40건 수신 → commitSync() 먼저 (offset=40)
처리 1건, offset=0
...
처리 35건, offset=34
[상태] 처리 35건 / 커밋 오프셋 40
=== 여기서 프로세스가 죽습니다 ===
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:9092 --describe --group s07-amo
```

**결과**

```
Consumer group 's07-amo' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s07-amo         s07_orders      0          40              100             60              -               -               -
```

재접속해서 남은 것을 처리합니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-most-once-resume'
```

**결과**

```
[resume] 그룹 s07-amo, 커밋된 오프셋 40 부터 읽습니다.
처리 1건, offset=40
...
처리 60건, offset=99
[집계] 1회차 35건 + 2회차 60건 = 95건 처리 / 발행 100건 → 유실 5건 (offset 35~39)
```

**5건이 사라졌습니다.** 오프셋 35~39 는 커밋됐지만 처리되지 않았고, 다시는 읽히지 않습니다.

> 💡 **at-most-once 가 정당한 경우도 있습니다**
> 전부 나쁜 것은 아닙니다. **메트릭 수집, 접근 로그, 실시간 대시보드 갱신** 처럼 개별 이벤트 하나의 유실이 무의미하고 처리 지연이 더 치명적인 도메인에서는 at-most-once 가 합리적입니다. 초당 수십만 건의 클릭 로그에서 5건이 빠지는 것은 통계적으로 아무 의미가 없습니다.
> 문제는 **그 선택이 의도적이어야 한다**는 것입니다. Step 06 의 자동 커밋은 이 선택을 우연히 하게 만들었습니다. 여기서는 코드에 `commitSync()` 가 루프 앞에 있으니 누구든 의도를 읽을 수 있습니다.

---

## 7-3. at-least-once 재현 — 처리 먼저, 커밋 나중

순서를 뒤집습니다.

```java
for (ConsumerRecord<String, String> r : records) {
    process(r);                              // ① 처리를 먼저
    processed++;
    if (processed == 80) die();              // 커밋 전에 죽는다
}
consumer.commitSync();                       // ② 배치를 다 처리한 뒤 커밋
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-least-once'
```

**결과**

```
[setup] 그룹 s07-alo, 처리 → 커밋 순서
[batch] 40건 수신 → 처리 → commitSync() (offset=40)
[batch] 40건 수신 → 처리 → commitSync() (offset=80)
[batch] 20건 수신 → 처리 중...
처리 81건, offset=80
...
[상태] 처리 80건 / 커밋 오프셋 80... 이 아니라 처리 도중입니다
=== 여기서 프로세스가 죽습니다 ===
```

실제로는 세 번째 배치를 처리하다 죽었습니다. 확인합니다.

```bash
kcg --describe --group s07-alo
```

**결과**

```
Consumer group 's07-alo' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s07-alo         s07_orders      0          80              100             20              -               -               -
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-least-once-resume'
```

**결과**

```
[resume] 그룹 s07-alo, 커밋된 오프셋 80 부터 읽습니다.
재처리 1건, offset=80
...
재처리 20건, offset=99
[집계] 1회차 88건 + 2회차 20건 = 108건 처리 / 발행 100건 → 중복 8건 (offset 80~87)
```

**8건이 두 번 처리됐습니다.** 유실은 0 입니다.

> 💡 **실무 팁 — at-least-once 가 대부분의 기본값입니다**
> 실무 컨슈머의 압도적 다수가 이 형태입니다. `enable.auto.commit=false` + 처리 후 `commitSync()`. 이유는 단순합니다. **유실은 복구 불가능하지만 중복은 걸러낼 수 있기 때문입니다.**
> 걸러내는 방법은 대개 비즈니스 키입니다. `payments` 테이블의 `order_id` 에 유니크 제약을 걸어 두면 두 번째 INSERT 가 실패하고, 애플리케이션은 그 예외를 "이미 처리됨" 으로 해석하고 넘어갑니다. 아래 7-9 에서 구체적으로 다룹니다.

---

## 7-4. 멱등 프로듀서 — 브로커가 중복 배치를 걸러낸다

지금까지는 컨슈머 쪽 중복이었습니다. 프로듀서 쪽에도 중복이 있습니다.

프로듀서가 `send()` 하고 브로커가 레코드를 기록한 뒤, **ack 응답이 네트워크에서 유실되면** 프로듀서는 실패로 판단하고 재시도합니다. 브로커 입장에서는 같은 메시지를 두 번 받습니다. 로그에 두 번 기록됩니다.

`enable.idempotence=true` 는 이걸 브로커가 걸러내게 합니다.

```
   프로듀서                              브로커 (파티션 리더)
   ────────                              ──────────────────
   producerId (PID) = 1000
   producerEpoch    = 0
                                          파티션별로 마지막 시퀀스를 기억
   send(A) seq=0  ──────────────────►     PID 1000 마지막 seq = -1
                                          0 == -1 + 1 → 기록. 마지막 seq = 0
                  ◄──── ack ───X          (응답이 유실됨)

   재시도 send(A) seq=0 ────────────►     PID 1000 마지막 seq = 0
                                          0 <= 0 → 중복! 기록하지 않고
                  ◄──── ack ─────────     성공 응답만 돌려준다

   send(B) seq=1  ──────────────────►     1 == 0 + 1 → 기록
```

핵심은 **시퀀스 번호가 (PID, 파티션) 단위로 매겨진다**는 점입니다. 브로커는 파티션마다 각 PID 의 마지막 시퀀스 5개를 기억하고 있다가, 이미 본 번호가 오면 기록하지 않고 성공만 돌려줍니다.

Kafka **3.0 부터 `enable.idempotence` 의 기본값이 `true`** 입니다. 즉 아무 설정도 안 한 프로듀서는 이미 멱등 프로듀서입니다.

### 강제되는 조건

멱등성을 켜면 세 설정이 강제됩니다.

| 설정 | 강제값 | 왜 |
|---|---|---|
| `acks` | `all` | 리더만 받고 죽으면 시퀀스 상태도 같이 사라짐 |
| `retries` | `> 0` (기본 `Integer.MAX_VALUE`) | 재시도를 안 하면 멱등성이 쓸모가 없음 |
| `max.in.flight.requests.per.connection` | `<= 5` | 브로커가 기억하는 시퀀스 창이 5개 |

이 중 하나라도 어기면 **기동 시점에 명확히 실패합니다.**

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent-bad-config'
```

**결과**

```
org.apache.kafka.common.config.ConfigException: Must set acks to all in order to use the idempotent producer. Otherwise we cannot guarantee idempotence.
	at org.apache.kafka.clients.producer.KafkaProducer.configureAcks(KafkaProducer.java:562)
	at org.apache.kafka.clients.producer.KafkaProducer.<init>(KafkaProducer.java:401)
```

**에러가 나는 게 좋은 것입니다.** 이건 조용히 틀리지 않습니다.

> 💡 **`max.in.flight <= 5` 가 Step 04 의 순서 문제를 푼다**
> Step 04 에서 `max.in.flight.requests.per.connection > 1` + 재시도가 순서를 뒤바꾼다고 했습니다. 멱등성을 켜면 그 문제도 함께 해결됩니다. 브로커가 시퀀스 번호를 검사하므로, seq=3 이 먼저 도착하면 seq=2 를 받을 때까지 거부(`OutOfOrderSequenceException` 대신 내부 재정렬)합니다. **`enable.idempotence=true` 는 중복 방지이자 순서 보장입니다.** 3.0 에서 기본값을 켠 이유이기도 합니다.

### 실제 배치 헤더를 봅니다

멱등 프로듀서로 10건을 보냅니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent'
```

**결과**

```
[setup] enable.idempotence=true (3.0부터 기본값)
[send] s07_payments-0 offset=0  O-3001
[send] s07_payments-0 offset=1  O-3002
...
[send] s07_payments-0 offset=9  O-3010
[info] 프로듀서 PID 는 클라이언트 로그에 나옵니다:
       [Producer clientId=producer-1] ProducerId set to 1000 with epoch 0
```

이제 로그 파일을 직접 열어 시퀀스 번호를 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s07_payments-0/00000000000000000000.log \
  --print-data-log | head -8
```

**결과**

```
Dumping /var/lib/kafka/data/s07_payments-0/00000000000000000000.log
Log starting offset: 0
baseOffset: 0 lastOffset: 4 count: 5 baseSequence: 0 lastSequence: 4 producerId: 1000 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: false isControl: false deleteHorizonMs: OptionalLong.empty position: 0 CreateTime: 1710120121113 size: 412 magic: 2 compresscodec: none crc: 2331120994 isvalid: true
| offset: 0 CreateTime: 1710120121113 keySize: 6 valueSize: 68 sequence: 0 headerKeys: [] key: O-3001 payload: {"order_id":"O-3001","method":"CARD","amount":39000,"result":"APPROVED"}
| offset: 1 CreateTime: 1710120121114 keySize: 6 valueSize: 68 sequence: 1 headerKeys: [] key: O-3002 payload: {"order_id":"O-3002","method":"CARD","amount":39000,"result":"APPROVED"}
| offset: 2 CreateTime: 1710120121114 keySize: 6 valueSize: 68 sequence: 2 headerKeys: [] key: O-3003 payload: {"order_id":"O-3003","method":"CARD","amount":39000,"result":"APPROVED"}
baseOffset: 5 lastOffset: 9 count: 5 baseSequence: 5 lastSequence: 9 producerId: 1000 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: false isControl: false deleteHorizonMs: OptionalLong.empty position: 412 CreateTime: 1710120121118 size: 408 magic: 2 compresscodec: none crc: 1884722310 isvalid: true
| offset: 5 CreateTime: 1710120121118 keySize: 6 valueSize: 68 sequence: 5 headerKeys: [] key: O-3006 payload: {"order_id":"O-3006","method":"CARD","amount":39000,"result":"APPROVED"}
```

배치 헤더에서 세 필드를 확인합니다.

- **`producerId: 1000`** — 브로커가 이 프로듀서 세션에 발급한 PID 입니다. `InitProducerId` 요청으로 받아 옵니다.
- **`producerEpoch: 0`** — 같은 `transactional.id` 로 새 프로듀서가 붙을 때마다 올라갑니다. 좀비 프로듀서를 쫓아내는 데 씁니다 (7-6).
- **`baseSequence: 0 lastSequence: 4`** — 이 배치가 담고 있는 시퀀스 범위입니다. 두 번째 배치는 `baseSequence: 5` 로 이어집니다. **끊김 없이 이어지는지가 브로커의 중복 판정 기준입니다.**

`isTransactional: false` 입니다. 멱등성만 켰지 트랜잭션은 아니기 때문입니다.

> ⚠️ **함정 — 멱등성은 단일 프로듀서 세션·단일 파티션 한정입니다**
> PID 는 **프로듀서 인스턴스가 살아 있는 동안만** 유효합니다. 프로세스를 재시작하면 브로커에서 **새 PID** 를 발급받습니다. 시퀀스도 0부터 다시 시작합니다.
> 그래서 이런 일이 벌어집니다. 프로듀서가 메시지를 보내고 → 브로커는 기록했는데 → ack 전에 **프로듀서 프로세스가 죽고** → 재시작한 프로듀서가 같은 메시지를 다시 보내면, **새 PID 이므로 브로커는 중복인지 알 수 없습니다.** 두 번 기록됩니다.
> ```
> baseOffset: 10 ... baseSequence: 0 lastSequence: 0 producerId: 1001 producerEpoch: 0 ...
> | offset: 10 ... sequence: 0 ... key: O-3010 payload: {"order_id":"O-3010",...}   ← 중복!
> ```
> `producerId` 가 **1000 에서 1001 로 바뀌었고**, 시퀀스가 0 으로 리셋됐습니다. 브로커에게 이건 완전히 새로운 프로듀서입니다.
> 그리고 범위 제한이 하나 더 있습니다. **시퀀스는 파티션마다 독립적**이므로, 여러 파티션에 걸친 "전부 쓰거나 전부 안 쓰거나" 는 멱등성으로 보장할 수 없습니다.
> **해결**: 세션을 넘어서는 보장이 필요하면 **트랜잭션**을 써야 합니다. `transactional.id` 를 고정하면 재시작해도 같은 정체성이 유지되기 때문입니다. 이것이 다음 절의 주제이자, **트랜잭션이 존재하는 이유**입니다.

---

## 7-5. 트랜잭션 API

트랜잭션은 **여러 파티션에 걸친 쓰기와 오프셋 커밋을 하나의 원자 단위로** 묶습니다.

```java
Properties p = new Properties();
p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-1:9092");
p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "s07-txn-1");     // ★ 이게 핵심
p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");        // transactional.id 를 주면 강제됨
p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

KafkaProducer<String, String> producer = new KafkaProducer<>(p);

producer.initTransactions();                    // ① 최초 1회. PID 를 받고 epoch 를 올린다
try {
    producer.beginTransaction();                // ② 트랜잭션 시작
    producer.send(new ProducerRecord<>("s07_payments", key, value));
    producer.send(new ProducerRecord<>("s07_payments", key2, value2));
    producer.commitTransaction();               // ③ 커밋
} catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
    producer.close();                           // 회복 불가능. 프로듀서를 버려야 한다
} catch (KafkaException e) {
    producer.abortTransaction();                // ④ 중단. 회복 가능
}
```

| 메서드 | 하는 일 |
|---|---|
| `initTransactions()` | `transactional.id` 로 PID 를 받고 **epoch 를 +1** 한다. 이전 epoch 의 프로듀서는 그 순간 좀비가 된다. 미완료 트랜잭션이 있으면 abort 한다 |
| `beginTransaction()` | 클라이언트 로컬 상태만 바꾼다. 브로커 통신 없음 |
| `send()` | 레코드에 `isTransactional=true` 마크가 붙는다. **첫 send 때 파티션을 트랜잭션에 등록**한다 |
| `sendOffsetsToTransaction()` | 컨슈머 오프셋 커밋을 **이 트랜잭션에 포함**시킨다 |
| `commitTransaction()` | 트랜잭션 코디네이터에 커밋 요청. 관련 파티션에 COMMIT 마커를 쓴다 |
| `abortTransaction()` | ABORT 마커를 쓴다. **레코드는 로그에서 지워지지 않는다** |

### 트랜잭션 커밋

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-commit'
```

**결과**

```
[setup] transactional.id=s07-txn-commit
[init] initTransactions() 완료. ProducerId set to 2000 with epoch 0
[begin] beginTransaction()
[send] s07_payments O-3011
[send] s07_payments O-3012
[send] s07_payments O-3013
[commit] commitTransaction() 완료
[확인] 이 3건은 read_committed 컨슈머에게도 보입니다.
```

### 트랜잭션 중단

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-abort'
```

**결과**

```
[setup] transactional.id=s07-txn-abort
[init] initTransactions() 완료. ProducerId set to 2001 with epoch 0
[begin] beginTransaction()
[send] s07_payments O-3014
[send] s07_payments O-3015
[send] s07_payments O-3016
[abort] abortTransaction() 완료
[확인] 이 3건은 로그에 남아 있지만 read_committed 컨슈머에게는 안 보입니다.
```

---

## 7-6. `isolation.level` — 같은 토픽을 두 컨슈머가 다르게 읽는다

방금 abort 한 3건이 실제로 어떻게 되는지 봅니다. **같은 토픽을 설정만 다르게 해서 두 번 읽습니다.**

### read_uncommitted (기본값)

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s07_payments \
  --from-beginning --property print.offset=true --timeout-ms 5000 2>/dev/null | tail -8
```

**결과**

```
Offset:11	{"order_id":"O-3012","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:12	{"order_id":"O-3013","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:14	{"order_id":"O-3014","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:15	{"order_id":"O-3015","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:16	{"order_id":"O-3016","method":"CARD","amount":39000,"result":"APPROVED"}
Processed a total of 16 messages
```

**abort 된 O-3014, O-3015, O-3016 이 그대로 보입니다.** 기본값 `read_uncommitted` 는 트랜잭션 상태를 무시합니다.

### read_committed

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s07_payments \
  --consumer-property isolation.level=read_committed \
  --from-beginning --property print.offset=true --timeout-ms 5000 2>/dev/null | tail -8
```

**결과**

```
Offset:9	{"order_id":"O-3010","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:10	{"order_id":"O-3011","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:11	{"order_id":"O-3012","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:12	{"order_id":"O-3013","method":"CARD","amount":39000,"result":"APPROVED"}
Processed a total of 13 messages
```

**16건 → 13건.** abort 된 3건이 사라졌고, `Offset:14, 15, 16` 이 아예 나오지 않습니다.

### 왜 오프셋에 구멍이 생깁니까

abort 해도 **메시지는 로그에서 지워지지 않습니다.** 대신 **컨트롤 레코드**라는 특수 레코드가 마커로 붙고, `read_committed` 컨슈머가 클라이언트 쪽에서 걸러냅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s07_payments-0/00000000000000000000.log \
  --print-data-log | grep -E 'isControl: true|isTransactional: true' | head -4
```

**결과**

```
baseOffset: 10 lastOffset: 12 count: 3 baseSequence: 0 lastSequence: 2 producerId: 2000 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: true isControl: false deleteHorizonMs: OptionalLong.empty position: 812 CreateTime: 1710120188211 size: 262 magic: 2 compresscodec: none crc: 802331477 isvalid: true
baseOffset: 13 lastOffset: 13 count: 1 baseSequence: -1 lastSequence: -1 producerId: 2000 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: true isControl: true deleteHorizonMs: OptionalLong.empty position: 1074 CreateTime: 1710120188245 size: 78 magic: 2 compresscodec: none crc: 3312044991 isvalid: true
baseOffset: 14 lastOffset: 16 count: 3 baseSequence: 0 lastSequence: 2 producerId: 2001 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: true isControl: false deleteHorizonMs: OptionalLong.empty position: 1152 CreateTime: 1710120191338 size: 262 magic: 2 compresscodec: none crc: 1102994387 isvalid: true
baseOffset: 17 lastOffset: 17 count: 1 baseSequence: -1 lastSequence: -1 producerId: 2001 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: true isControl: true deleteHorizonMs: OptionalLong.empty position: 1414 CreateTime: 1710120191371 size: 78 magic: 2 compresscodec: none crc: 3919872233 isvalid: true
```

마커 자체의 내용도 볼 수 있습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s07_payments-0/00000000000000000000.log \
  --print-data-log | grep endTxnMarker
```

**결과**

```
| offset: 13 CreateTime: 1710120188245 keySize: 4 valueSize: 6 sequence: -1 headerKeys: [] endTxnMarker: COMMIT coordinatorEpoch: 0
| offset: 17 CreateTime: 1710120191371 keySize: 4 valueSize: 6 sequence: -1 headerKeys: [] endTxnMarker: ABORT coordinatorEpoch: 0
```

**오프셋 13 은 COMMIT 마커, 오프셋 17 은 ABORT 마커입니다.** 이 두 오프셋은 컨트롤 레코드가 차지하고 있으며, 어떤 컨슈머에게도 데이터로 전달되지 않습니다.

이제 위 `read_committed` 출력에서 오프셋이 9, 10, 11, 12 로 이어지다 끝난 이유가 설명됩니다. 오프셋 13(COMMIT 마커), 14~16(abort 된 데이터), 17(ABORT 마커)이 전부 걸러졌기 때문입니다.

> ⚠️ **함정 — `read_committed` 컨슈머의 오프셋에는 구멍이 생깁니다**
> 이건 두 가지 방식으로 사람을 혼란시킵니다.
> **첫째, LAG 이 0 이 되지 않습니다.** 정확히는 0 이 되긴 하는데 그 과정이 이상합니다. 컨슈머가 마지막으로 읽은 레코드의 오프셋이 12 인데 `LOG-END-OFFSET` 은 18 입니다. 컨슈머 클라이언트가 걸러낸 오프셋도 내부적으로 진행시키므로 최종적으로 커밋 오프셋은 18 이 되지만, **"내가 읽은 마지막 메시지 오프셋 + 1" 과 커밋 오프셋이 일치하지 않습니다.** 오프셋 산술로 처리 건수를 추정하는 코드는 여기서 틀립니다.
> **둘째, 오프셋이 건너뜁니다.** `read_committed` 컨슈머의 로그를 보면 `offset=12` 다음이 `offset=20` 처럼 뜁니다. 트랜잭션이 많은 토픽에서는 이 간격이 큽니다. "메시지가 유실됐다" 로 오해하기 딱 좋습니다.
> **해결**: ① 처리 건수는 오프셋 차이가 아니라 **애플리케이션 카운터**로 세십시오. ② 오프셋의 연속성을 전제하는 로직(예: "이전 오프셋 + 1 이 아니면 유실") 을 만들지 마십시오. Kafka 는 애초에 오프셋 연속성을 보장하지 않습니다. 로그 압축(Step 09)에서도 같은 이유로 구멍이 생깁니다.

---

## 7-7. 함정 — `read_committed` 는 LSO 까지만 읽는다

이게 이 스텝에서 가장 관찰하기 어렵고, 실무에서 가장 당황스러운 현상입니다.

`read_committed` 컨슈머는 **LSO(Last Stable Offset)** 까지만 읽습니다. LSO 는 **아직 완료되지 않은 가장 오래된 트랜잭션의 시작 지점**입니다.

```
   파티션 로그
   ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
   │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │ 9 │
   └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
                 ▲                       ▲
                 │                       │
                LSO                     HW / LEO = 10
       (오프셋 3에서 시작한 트랜잭션이
        아직 커밋도 중단도 안 됨)

   read_uncommitted 컨슈머 → 오프셋 9까지 읽는다
   read_committed   컨슈머 → 오프셋 2까지만 읽고 **멈춘다**
```

재현합니다. 트랜잭션을 열어 메시지를 보내고 **커밋하지 않은 채 30초 동안 붙잡습니다.**

```bash
# [터미널 A]
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-hang'
```

**결과**

```
[setup] transactional.id=s07-txn-hang
[begin] beginTransaction()
[send] s07_payments O-3020
[send] s07_payments O-3021
[hang] 30초 동안 커밋하지 않고 붙잡고 있습니다...
```

그동안 터미널 B 에서 두 종류의 컨슈머를 띄웁니다.

```bash
# [터미널 B] read_uncommitted
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s07_payments \
  --property print.offset=true --timeout-ms 10000
```

**결과**

```
Offset:18	{"order_id":"O-3020","method":"CARD","amount":39000,"result":"APPROVED"}
Offset:19	{"order_id":"O-3021","method":"CARD","amount":39000,"result":"APPROVED"}
Processed a total of 2 messages
```

**즉시 보입니다.**

```bash
# [터미널 C] read_committed
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s07_payments \
  --consumer-property isolation.level=read_committed \
  --property print.offset=true --timeout-ms 10000
```

**결과**

```
[2024-03-11 10:24:11,733] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.common.errors.TimeoutException
Processed a total of 0 messages
```

**0건.** 10초를 기다렸는데 아무것도 못 받았습니다. 그리고 **에러가 없습니다.** 위 `TimeoutException` 은 `--timeout-ms` 때문에 난 것이고, 실제 애플리케이션이라면 그냥 조용히 `poll()` 이 빈 결과를 반환할 뿐입니다.

30초 뒤 터미널 A 의 트랜잭션이 커밋되면:

```
[commit] commitTransaction() 완료
```

터미널 C 를 다시 띄우면 그제야 2건이 나옵니다.

> ⚠️ **함정 — 진행 중인 트랜잭션 하나가 컨슈머 전체를 멈춰 세웁니다**
> 증상이 지독합니다. **컨슈머가 살아 있고, 리밸런싱도 안 하고, 예외도 없는데 `poll()` 이 계속 빈 결과를 반환합니다.** 랙은 계속 쌓입니다. 로그에는 아무것도 안 남습니다.
> 원인은 미완료 트랜잭션 하나입니다. 그 트랜잭션이 커밋되거나 중단될 때까지 LSO 가 그 자리에 고정되고, 그 뒤에 쓰인 **모든** 메시지가 read_committed 컨슈머에게 보이지 않습니다. **파티션 단위이므로 다른 프로듀서가 쓴 메시지까지 함께 막힙니다.**
> 언제 이런 일이 생기냐면 — 트랜잭션 프로듀서가 `commitTransaction()` 전에 죽었거나(kill -9), 트랜잭션 안에서 외부 API 호출이 오래 걸리거나, 디버거로 브레이크포인트를 걸어 뒀거나.
> **구제 장치**는 `transaction.timeout.ms` 입니다. 기본값 **60000(60초)** 이고, 이 시간이 지나면 트랜잭션 코디네이터가 해당 트랜잭션을 강제로 abort 하고 ABORT 마커를 씁니다. 그제야 LSO 가 전진합니다.
> ```bash
> kconf --describe --entity-type brokers --entity-name 1 --all | grep transaction.max.timeout
> ```
> ```
>   transaction.max.timeout.ms=900000 sensitive=false synonyms={DEFAULT_CONFIG:transaction.max.timeout.ms=900000}
> ```
> 브로커의 `transaction.max.timeout.ms`(기본 15분)가 상한이며, 프로듀서가 그보다 큰 값을 요청하면 거부됩니다.
> **해결**: ① 트랜잭션을 **짧게** 유지하십시오. 외부 API 호출을 트랜잭션 안에 넣지 마세요. ② `transaction.timeout.ms` 를 도메인의 최대 처리 시간에 맞춰 명시적으로 설정하십시오. 기본 60초는 배치 처리에는 짧고, 실시간 처리에는 지나치게 깁니다. ③ 컨슈머가 멈춘 것처럼 보이면 `--describe` 의 LAG 과 함께 **프로듀서 쪽 미완료 트랜잭션**을 의심하십시오.

---

## 7-8. consume-transform-produce — exactly-once 의 실제 형태

Kafka 의 exactly-once 가 실제로 쓰이는 곳은 여기입니다. **토픽에서 읽어 → 변환해 → 다른 토픽에 쓰는** 파이프라인입니다.

핵심은 `sendOffsetsToTransaction()` 입니다. **출력 메시지 쓰기와 입력 오프셋 커밋을 같은 트랜잭션에 넣습니다.**

```java
producer.initTransactions();

while (running) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
    if (records.isEmpty()) continue;

    producer.beginTransaction();
    try {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();

        for (ConsumerRecord<String, String> r : records) {
            String payment = transform(r.value());                    // orders → payments 변환
            producer.send(new ProducerRecord<>("s07_payments", orderIdOf(r.value()), payment));

            offsets.put(new TopicPartition(r.topic(), r.partition()),
                        new OffsetAndMetadata(r.offset() + 1));       // ★ 여기도 +1
        }

        // ★ 오프셋 커밋을 트랜잭션에 포함시킨다.
        //    consumer.commitSync() 를 부르면 안 된다. 그건 트랜잭션 밖이다.
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

        producer.commitTransaction();          // 출력 + 오프셋이 함께 확정된다
    } catch (KafkaException e) {
        producer.abortTransaction();           // 출력 + 오프셋이 함께 취소된다
    }
}
```

컨슈머 쪽 설정 두 개가 필수입니다.

```java
consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // 반드시. 자동 커밋은 트랜잭션 밖이다
consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-consume-transform-produce'
```

**결과**

```
[setup] 입력 s07_orders / 출력 s07_payments / transactional.id=s07-ctp
[setup] consumer: enable.auto.commit=false, isolation.level=read_committed
[init] initTransactions() 완료. ProducerId set to 2003 with epoch 0
[txn #1] 40건 읽음 → 40건 변환 → sendOffsetsToTransaction({s07_orders-0: 40}) → commit
[txn #2] 40건 읽음 → 40건 변환 → sendOffsetsToTransaction({s07_orders-0: 80}) → commit
[txn #3] 20건 읽음 → 20건 변환 → sendOffsetsToTransaction({s07_orders-0: 100}) → commit
[집계] 입력 100건 → 출력 100건. 유실 0, 중복 0
```

```bash
kcg --describe --group s07-ctp
```

**결과**

```
Consumer group 's07-ctp' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s07-ctp         s07_orders      0          100             100             0               -               -               -
```

여기서 프로세스를 아무 시점에나 죽여도 결과가 달라지지 않습니다. 트랜잭션 중간에 죽으면 **출력도 오프셋도 함께 취소**되므로, 재시작하면 그 배치를 통째로 다시 처리합니다. 출력은 abort 마커로 걸러지고 오프셋은 되감겨 있으니, 최종 상태는 정확히 한 번 처리한 것과 같습니다.

> 💡 **`sendOffsetsToTransaction` 이 `consumer.groupMetadata()` 를 받는 이유**
> 두 번째 인자로 그룹 ID 문자열이 아니라 `ConsumerGroupMetadata` 를 받습니다. 여기에 **그룹의 generation ID 와 member ID** 가 들어 있습니다. 트랜잭션 코디네이터가 이 값을 검증해서, **리밸런싱으로 이미 파티션을 뺏긴 좀비 컨슈머의 오프셋 커밋을 거부**합니다.
> 문자열 그룹 ID 만 받는 구버전 시그니처는 이 검증을 할 수 없어 deprecated 됐습니다. 2.5 이후 코드라면 `groupMetadata()` 쪽을 쓰십시오.

---

## 7-9. 함정 — "exactly-once" 는 Kafka 내부 한정입니다

여기까지 보면 exactly-once 가 해결책처럼 보입니다. 그런데 방금 만든 파이프라인을 조금만 현실적으로 바꿔 봅니다.

```java
producer.beginTransaction();
for (ConsumerRecord<String, String> r : records) {
    paymentGateway.charge(r.value());                    // ★ 외부 결제 API 호출
    producer.send(new ProducerRecord<>("s07_payments", ...));
}
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
producer.commitTransaction();
```

**`paymentGateway.charge()` 는 트랜잭션에 들어가지 않습니다.** `commitTransaction()` 이 실패해 abort 되어도, 이미 나간 결제는 취소되지 않습니다. 재시작하면 같은 배치를 다시 처리하고, **결제가 두 번 나갑니다.**

> ⚠️ **함정 — Kafka 트랜잭션은 Kafka 안에서만 원자적입니다**
> 트랜잭션에 포함되는 것은 딱 두 가지입니다. **① Kafka 토픽에 대한 쓰기, ② `__consumer_offsets` 에 대한 오프셋 커밋.** 그 외 전부는 밖입니다.
> - 외부 DB INSERT → 밖 (Kafka 트랜잭션이 abort 돼도 롤백되지 않음)
> - REST API 호출 → 밖
> - 파일 쓰기, 캐시 갱신, 이메일 발송 → 전부 밖
> - Redis, Elasticsearch 색인 → 밖
>
> 즉 **Kafka 트랜잭션은 분산 트랜잭션(2PC)이 아닙니다.** "Kafka → Kafka" 파이프라인에서만 exactly-once 이고, 그 파이프라인이 외부와 접촉하는 순간 보장이 끝납니다.
> 그리고 비용도 있습니다. 트랜잭션은 코디네이터 왕복과 마커 쓰기가 추가되므로 처리량이 떨어집니다. 배치 크기에 따라 다르지만, 트랜잭션 하나에 레코드가 적으면 **처리량이 절반 이하로 떨어지는 경우도 흔합니다.**
> ```
> [측정] at-least-once     : 100건 / 2.31초 → 43.3 msg/s
> [측정] exactly-once(txn) : 100건 / 4.08초 → 24.5 msg/s   (약 1.8배 느림)
> ```

### 실무 해법 — at-least-once + 멱등 처리

그래서 외부 시스템이 끼는 대부분의 실무 파이프라인은 **exactly-once 를 포기하고 at-least-once + 멱등 처리**로 갑니다. 중복은 들어오되, 두 번째부터는 아무 일도 일어나지 않게 만드는 것입니다.

멱등 처리의 핵심은 **비즈니스 키**입니다. Kafka 오프셋이 아니라 도메인의 고유 식별자를 씁니다.

```java
// ① DB 유니크 제약으로 거르기 — 가장 견고합니다
//    CREATE TABLE payments (order_id VARCHAR(32) PRIMARY KEY, ...);
try {
    jdbc.update("INSERT INTO payments (order_id, amount) VALUES (?, ?)", orderId, amount);
    paymentGateway.charge(orderId, amount);            // INSERT 성공한 경우에만 실제 결제
} catch (DuplicateKeyException e) {
    log.info("이미 처리된 주문입니다. 건너뜁니다: {}", orderId);
}

// ② 외부 API 의 멱등 키 사용 — 상대가 지원한다면 이쪽이 더 낫습니다
paymentGateway.charge(orderId, amount, /* Idempotency-Key */ orderId);
```

| 방식 | 장점 | 주의점 |
|---|---|---|
| DB 유니크 제약 | 가장 확실. DB 가 원자적으로 판정 | INSERT 와 외부 호출 사이에 죽으면 결제가 안 나감 (유실 방향) |
| 외부 API 멱등 키 | 상대 시스템이 책임짐 | 상대가 지원해야 함. 키 유효기간 확인 필요 |
| 처리 이력 테이블 | 임의 도메인에 적용 가능 | 테이블이 무한히 커짐. TTL 필요 |

> 💡 **실무 팁 — 유니크 제약과 외부 호출의 순서**
> 위 코드에서 INSERT 를 먼저 하고 결제를 나중에 했습니다. 반대로 하면 결제 후 INSERT 전에 죽었을 때 **중복 결제**가 됩니다. INSERT 를 먼저 하면 최악의 경우 **결제 누락**입니다.
> 어느 쪽이 나은지는 도메인이 정합니다. 결제는 중복보다 누락이 낫고(고객이 다시 시도하면 됨), 알림 발송은 누락보다 중복이 낫습니다. **이 판단을 코드 리뷰에서 명시적으로 하십시오.** 순서 한 줄이 도메인 정책입니다.

### 실패한 메시지는 DLQ 로

멱등 처리로도 안 되는 것 — 데이터 자체가 잘못된 메시지 — 은 재시도해도 영원히 실패합니다. 그대로 두면 컨슈머가 그 지점에서 멈춰 파티션 전체가 막힙니다("poison pill").

```java
for (ConsumerRecord<String, String> r : records) {
    try {
        process(r);
    } catch (RetriableException e) {
        throw e;                                   // 재시도 가능한 것은 다시 던져 배치를 실패시킨다
    } catch (Exception e) {
        // 재시도해도 소용없는 것은 dlq 로 보내고 넘어간다
        producer.send(new ProducerRecord<>("dlq", r.key(), r.value(),
            List.of(new RecordHeader("x-original-topic",   r.topic().getBytes()),
                    new RecordHeader("x-original-offset",  String.valueOf(r.offset()).getBytes()),
                    new RecordHeader("x-error",            e.getMessage().getBytes()))));
    }
}
consumer.commitSync();
```

**헤더에 원본 위치와 에러를 반드시 남기십시오.** DLQ 에 값만 들어 있으면 나중에 "이게 어디서 왔고 왜 실패했지" 를 알 수 없어 재처리가 불가능해집니다. 코스 개요의 `dlq` 토픽이 이 용도이며, Step 12 에서 Connect 의 DLQ 설정으로 다시 나옵니다.

---

## 7-10. EOS v2 — Streams 의 기본값

트랜잭션의 초기 구현(EOS v1, `exactly_once`)은 **입력 파티션마다 별도의 프로듀서**를 요구했습니다. 입력 파티션이 100개면 프로듀서 100개입니다. 메모리와 커넥션이 폭발했습니다.

**EOS v2(`exactly_once_v2`, Kafka 2.5 도입)** 는 `sendOffsetsToTransaction` 에 `ConsumerGroupMetadata` 를 넘기게 바꿔서, **프로듀서 하나가 여러 입력 파티션을 처리**할 수 있게 했습니다. 7-8 에서 `consumer.groupMetadata()` 를 넘긴 그 API 입니다.

```java
// Kafka Streams 설정
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
```

- `exactly_once` (v1) 은 **3.0 에서 deprecated**, `exactly_once_beta` 는 v2 의 옛 이름입니다.
- 3.7 에서는 `exactly_once_v2` 를 쓰십시오. `at_least_once` 가 Streams 의 기본값이며, `exactly_once_v2` 로 바꾸면 내부적으로 위 트랜잭션 코드를 대신 써 줍니다.
- 브로커의 `transaction.version` 피처가 트랜잭션 프로토콜 버전을 결정합니다. 3.7 클러스터는 v2 프로토콜을 지원합니다.

Kafka Streams 를 쓰면 이 스텝의 트랜잭션 코드를 직접 쓸 일이 없습니다. 설정 한 줄로 끝납니다. **Step 13 에서 그 부분을 다룹니다.**

---

## 7-11. 정리 (실습 마무리)

```bash
for g in s07-amo s07-alo s07-ctp s07-idem; do
  docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka-1:9092 --delete --group "$g" 2>/dev/null || true
done

docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:9092 --delete --topic s07_orders
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:9092 --delete --topic s07_payments
```

트랜잭션 상태는 `__transaction_state` 토픽에 남습니다. 이 토픽은 지우지 마십시오. 내부 토픽이며 `transactional.id.expiration.ms`(기본 7일)로 알아서 정리됩니다.

```bash
kt --list | grep -E '^(s07|__transaction)' || echo "정리 완료"
```

**결과**

```
__transaction_state
```

`s07_` 토픽이 안 보이면 정리된 것입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| at-most-once | 커밋 → 처리. **유실 가능, 중복 없음**. 로그·메트릭 도메인에 적합 |
| at-least-once | 처리 → 커밋. **유실 없음, 중복 가능**. **실무 기본값** |
| exactly-once | 처리·출력·오프셋을 한 트랜잭션에. **Kafka 내부에 한해서만** |
| `enable.idempotence` | **3.0부터 기본 true**. PID + 시퀀스로 브로커가 중복 배치 제거 |
| 강제 조건 | `acks=all`, `retries>0`, `max.in.flight<=5`. 위반하면 `ConfigException` |
| 멱등성 한계 | **단일 프로듀서 세션·단일 파티션**. 재시작하면 새 PID 라 중복을 못 막음 |
| `transactional.id` | 세션을 넘어 정체성 유지. `initTransactions()` 가 epoch 를 올려 좀비를 쫓아냄 |
| 트랜잭션 API | `initTransactions` → `beginTransaction` → `send`/`sendOffsetsToTransaction` → `commit`/`abort` |
| `sendOffsetsToTransaction` | `consumer.groupMetadata()` 를 넘겨야 좀비 컨슈머의 커밋을 거부할 수 있음 |
| `isolation.level` | 기본 `read_uncommitted`(abort 된 것도 보임) / `read_committed`(걸러짐) |
| 컨트롤 레코드 | abort 해도 **데이터는 로그에 남고** ABORT 마커로 걸러냄. `isControl: true` |
| 오프셋 구멍 | `read_committed` 컨슈머는 오프셋이 건너뜀. **연속성을 가정하지 말 것** |
| LSO 함정 | 미완료 트랜잭션 하나가 `read_committed` 컨슈머를 **에러 없이 멈춰 세움** |
| `transaction.timeout.ms` | 기본 60초. 이 시간 뒤 코디네이터가 강제 abort → LSO 전진 |
| exactly-once 한계 | **외부 DB/API 는 트랜잭션 밖.** 2PC 가 아님. 처리량도 약 1.8배 느림 |
| 실무 해법 | at-least-once + **비즈니스 키 멱등 처리**(DB 유니크 제약 / API 멱등 키) |
| DLQ | 재시도 불가능한 메시지는 `dlq` 로. **헤더에 원본 토픽·오프셋·에러를 남길 것** |
| EOS v2 | `exactly_once_v2`. 프로듀서 하나가 여러 입력 파티션 처리. Streams 에서 설정 한 줄 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`. **직접 실행해서 숫자를 확인**하세요.

1. at-most-once 와 at-least-once 를 같은 데이터로 돌려 유실 건수와 중복 건수를 각각 측정하기
2. `enable.idempotence=true` 에 `acks=1` 을 함께 주고 어떤 에러가 나는지, 왜 그 조합이 금지되는지 설명하기
3. `kafka-dump-log.sh` 로 `producerId` 와 `baseSequence` 를 뽑아내고, 프로듀서를 재시작한 뒤 PID 가 바뀌는 것을 확인하기
4. 트랜잭션을 abort 한 뒤 `read_uncommitted` / `read_committed` 컨슈머의 건수 차이를 재현하고, 그 차이가 컨트롤 레코드 때문임을 `--print-data-log` 로 증명하기
5. 미완료 트랜잭션을 만들어 `read_committed` 컨슈머가 멈추는 것을 재현하고, `transaction.timeout.ms` 를 줄여 회복 시간을 단축하기
6. consume-transform-produce 를 돌리는 중간에 프로세스를 죽이고, 재시작 후 출력 토픽의 건수가 정확히 입력 건수와 같은지 확인하기
7. 외부 시스템(파일 append)을 트랜잭션 안에 넣어 abort 시 그 파일이 롤백되지 않는 것을 보이고, 멱등 처리로 고치기

---

## 다음 단계

이 스텝에서 프로듀서와 컨슈머 사이의 보장을 다뤘습니다. 그런데 전제가 하나 있었습니다. **브로커가 한 번 기록한 것은 사라지지 않는다.** 이 전제가 언제 깨지는지는 아직 확인하지 않았습니다.

다음 스텝에서 `replication.factor` 와 ISR, `min.insync.replicas` 를 다루고, **브로커를 실제로 죽여 리더 선출을 관찰**합니다. 그리고 `acks=all` 로 성공 응답을 받은 메시지가 `unclean.leader.election` 한 번으로 통째로 사라지는 순간을 재현합니다.

→ [Step 08 — 복제와 내구성](../step-08-replication/)

---

## 실습 파일

이 스텝은 파일 네 개로 진행합니다. `practice.sh` 가 토픽 생성부터 정리까지 7-0 ~ 7-11 의 흐름을 담고 있고, 타이밍이 중요한 재현(at-most-once / at-least-once / 트랜잭션)은 전부 `Practice.java` 의 시나리오로 넘깁니다. `exercise.sh` 의 7문제를 푼 뒤 `solution.sh` 로 대조하십시오. 7-7 의 LSO 함정은 **터미널 3개**를 요구하므로 스크립트만 돌려서는 관찰되지 않습니다.

### practice.sh

본문 7-0 ~ 7-11 의 모든 명령을 절 번호 주석과 함께 담은 실행 스크립트입니다.

- 상단에 `BS=kafka-1:9092`, `K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }`, `KI()`(stdin 용), 그리고 `DUMP()` 헬퍼를 정의합니다. `DUMP()` 는 `kafka-dump-log.sh --files /var/lib/kafka/data/<파티션>/00000000000000000000.log --print-data-log` 를 감싼 것으로, 7-4 와 7-6 에서 반복해서 씁니다.
- `[7-0]` 은 `s07_orders` 와 `s07_payments` 를 **둘 다 파티션 1개**로 만듭니다. 시퀀스 번호와 오프셋을 눈으로 좇는 것이 목적이므로 파티션을 늘리면 `kafka-dump-log.sh` 출력이 여러 디렉터리로 흩어져 관찰이 어려워집니다.
- `[7-4]` 의 `idempotent-bad-config` 는 **의도적으로 `ConfigException` 을 내는 명령**이라 `|| true` 로 감쌌습니다. `set -e` 가 걸려 있어 없으면 스크립트가 여기서 멈춥니다.
- `[7-6]` 은 **같은 토픽을 두 번 읽습니다.** 한 번은 기본값(`read_uncommitted`), 한 번은 `--consumer-property isolation.level=read_committed`. 두 출력의 건수 차이(16 vs 13)와 `Offset:` 값의 누락이 이 절의 관찰 대상이므로, 두 명령을 붙여 놓고 눈으로 비교하십시오.
- `[7-6]` 뒤의 `grep endTxnMarker` 는 `COMMIT` 과 `ABORT` 마커를 각각 한 줄씩 뽑아냅니다. 여기서 마커가 차지한 오프셋 번호를 확인하면, 바로 위 `read_committed` 출력에서 왜 그 오프셋이 빠졌는지가 설명됩니다.
- `[7-7]` 의 LSO 재현은 **터미널 3개**가 필요해 스크립트에서는 주석 처리되어 있습니다. `# [터미널 A] txn-hang`, `# [터미널 B] read_uncommitted`, `# [터미널 C] read_committed` 로 표시했으니 직접 세 창에서 실행하십시오. 한 창에서 순차 실행하면 트랜잭션이 이미 끝난 뒤라 아무것도 안 막힙니다.
- 마지막 `[7-11]` 이 `s07-*` 그룹 4개와 `s07_` 토픽 2개를 지웁니다. **`__transaction_state` 는 지우지 않습니다.** 내부 토픽이며 삭제하면 클러스터의 트랜잭션 기능이 망가집니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- `setup()` 이 `s07x_orders` / `s07x_payments` 를 만들고 주문 **60건**을 넣습니다. 본문의 100건과 **일부러 다른 숫자**입니다. 본문의 "35 / 5 / 8" 같은 숫자를 그대로 옮겨 적으면 틀립니다.
- **문제 1·6** 은 `Practice.java` 를 여러 번 돌려 숫자를 대조하는 문제, **문제 2·3·4** 는 CLI 관찰 문제, **문제 5·7** 은 설정을 바꿔 가며 동작을 바꾸는 문제입니다.
- 문제 2 는 `S07_ACKS=1` 환경 변수로 `Practice.java` 의 프로듀서 설정을 덮어써서 `ConfigException` 을 재현합니다. 예외 메시지의 마지막 문장(`Otherwise we cannot guarantee idempotence.`)까지 정확히 확인하도록 안내합니다.
- 문제 3 은 같은 시나리오를 **두 번 연달아** 실행해야 합니다. 첫 실행의 `producerId` 와 두 번째 실행의 `producerId` 가 다르다는 것이 답이며, 한 번만 돌리면 문제 자체가 성립하지 않습니다.
- ⚠️ **문제 5 는 터미널 2개가 필요합니다.** 한쪽에서 `txn-hang` 으로 트랜잭션을 붙잡아 두고 다른 쪽에서 `read_committed` 컨슈머를 띄워야 "0건" 을 볼 수 있습니다. 그다음 `S07_TXN_TIMEOUT=10000` 으로 다시 돌려 회복 시간이 60초에서 10초로 줄어드는 것을 측정합니다.
- 문제 7 은 `Practice.java` 의 `txn-abort-with-file` 시나리오를 씁니다. 트랜잭션 안에서 `/tmp/s07-side-effect.log` 에 append 한 뒤 abort 하고, 그 파일에 줄이 그대로 남아 있는 것을 `cat` 으로 확인하는 구조입니다.
- 파일 끝의 `cleanup()` 이 `s07x_` 토픽과 `s07x-*` 그룹, 그리고 `/tmp/s07-side-effect.log` 를 지웁니다.

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답 명령과, "왜 그 답인가" 를 설명하는 긴 `# 해설:` 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 같은 60건 데이터로 at-most-once 는 **유실 N건 / 중복 0**, at-least-once 는 **유실 0 / 중복 M건** 이 나오는 것을 대조합니다. 해설이 강조하는 것은 "둘 다 정상 동작" 이라는 점입니다. 어느 쪽도 버그가 아니며, 코드가 선택한 순서의 필연적 귀결입니다.
- **정답 2** 는 `ConfigException` 전문을 보여주고, `acks=1` 이 금지되는 이유를 시퀀스 상태의 위치로 설명합니다. **시퀀스 상태는 파티션 리더의 메모리와 로그에 있으므로, 팔로워가 복제하지 않은 채 리더가 죽으면 그 상태가 함께 사라집니다.** 새 리더는 이전 시퀀스를 모르니 중복을 걸러낼 수 없습니다. 그래서 `acks=all` 이 아니면 멱등성을 "보장" 이라고 부를 수 없습니다.
- **정답 3** 은 `kafka-dump-log.sh ... | grep -oE 'producerId: [0-9]+'` 로 PID 만 추출해 두 실행을 비교합니다. `producerId: 1000` → `producerId: 1001` 로 바뀌고 `baseSequence` 가 0 으로 리셋되는 것이 답이며, 이것이 **프로듀서 재시작 후 중복을 막을 수 없는 직접적 증거**입니다.
- **정답 4** 는 건수 차이(예: 12 vs 9)를 보인 뒤, `--print-data-log | grep -c 'isControl: true'` 로 컨트롤 레코드 개수를 세어 "사라진 건수 = abort 된 데이터 + 마커 수" 라는 등식을 확인합니다. 오프셋이 연속하지 않는다는 결론까지 이어집니다.
- **정답 5** 는 기본값에서 회복까지 **60초**, `transaction.timeout.ms=10000` 에서 **10초** 가 걸리는 것을 `time` 으로 측정합니다. 다만 해설이 곧바로 경고합니다 — **타임아웃을 무작정 줄이면 정상적인 긴 트랜잭션이 강제 abort 됩니다.** 값은 도메인의 최대 처리 시간보다 여유 있게 잡아야 합니다.
- **정답 6** 은 `txn-consume-transform-produce` 를 중간에 죽인 뒤 재시작해서, 출력 토픽의 `read_committed` 건수가 **정확히 입력 건수와 같다**는 것을 보입니다. 반면 `read_uncommitted` 로 세면 abort 된 배치까지 포함되어 더 많이 나옵니다. **"어느 렌즈로 세느냐가 답을 바꾼다"** 는 것이 이 문제의 핵심입니다.
- **정답 7** 의 결론은 명확합니다. abort 후에도 `/tmp/s07-side-effect.log` 의 줄은 그대로 남아 있습니다. Kafka 트랜잭션은 파일 시스템을 롤백하지 않습니다. 고치는 방법으로 **파일에 쓰기 전에 `order_id` 로 중복을 검사**하는 멱등 처리를 제시하고, 이것이 실무에서 DB 유니크 제약이 하는 일과 같다는 것으로 마무리합니다.

```bash file="./solution.sh"
```

### Practice.java

이 스텝의 재현은 대부분 **타이밍**에 달려 있어 CLI 로는 불가능합니다. 전달 보장 세 가지와 트랜잭션 API 전체를 담은 단일 파일 Java 프로그램입니다.

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
```

- 시나리오는 첫 인자로 고릅니다: `at-most-once`, `at-most-once-resume`, `at-least-once`, `at-least-once-resume`, `idempotent`, `idempotent-bad-config`, `txn-commit`, `txn-abort`, `txn-hang`, `txn-consume-transform-produce`, `txn-abort-with-file`. 인자 없이 실행하면 목록을 출력합니다.
- `at-most-once` 와 `at-least-once` 의 코드 차이는 **`consumer.commitSync()` 가 for 루프 앞에 있느냐 뒤에 있느냐, 딱 그것뿐**입니다. 두 메서드를 나란히 읽어 보십시오. 이 한 줄의 위치가 유실과 중복을 가릅니다.
- Step 06 과 마찬가지로 `Runtime.getRuntime().halt(1)` 로 죽습니다. `System.exit()` 은 셧다운 훅을 돌려 컨슈머를 정상 close 하고 오프셋을 커밋하므로 재현이 되지 않습니다.
- `idempotent-bad-config` 는 `enable.idempotence=true` 와 `acks=1` 을 함께 주고 `new KafkaProducer<>(props)` 를 호출합니다. **생성자에서 즉시 `ConfigException` 이 납니다.** send 시점이 아니라 생성 시점이라는 것이 중요합니다. 잘못된 설정은 기동 자체를 막습니다.
- `txn-hang` 은 `beginTransaction()` 후 send 만 하고 `Thread.sleep(30_000)` 동안 커밋하지 않습니다. `S07_HANG_SECONDS` 로 시간을 조절할 수 있고, `S07_TXN_TIMEOUT` 으로 `transaction.timeout.ms` 를 바꿔 코디네이터의 강제 abort 시점을 앞당길 수 있습니다.
- `txn-consume-transform-produce` 는 **컨슈머의 `enable.auto.commit=false` 와 `isolation.level=read_committed` 를 강제로 설정**합니다. 자동 커밋이 켜져 있으면 오프셋이 트랜잭션 밖에서 커밋되어 원자성이 깨지기 때문입니다. 이 두 줄이 빠진 consume-transform-produce 코드는 exactly-once 가 아닙니다.
- `sendOffsetsToTransaction(offsets, consumer.groupMetadata())` 의 두 번째 인자에 주목하십시오. 그룹 ID 문자열이 아니라 `ConsumerGroupMetadata` 이며, generation ID 가 들어 있어 **좀비 컨슈머의 커밋을 브로커가 거부**할 수 있게 합니다. 이것이 EOS v2 의 핵심 변경점입니다.
- `txn-abort-with-file` 은 트랜잭션 안에서 `/tmp/s07-side-effect.log` 에 `Files.writeString(..., APPEND)` 를 한 뒤 `abortTransaction()` 합니다. 연습문제 7 이 이 파일을 `cat` 해서 롤백되지 않았음을 확인합니다.
- 모든 트랜잭션 시나리오가 `ProducerFencedException` / `OutOfOrderSequenceException` / `AuthorizationException` 을 **abort 하지 않고 `close()` 로 처리**합니다. 이 셋은 회복 불가능한 예외이며, `abortTransaction()` 을 호출하면 다시 예외가 납니다. 그냥 프로듀서를 버려야 합니다.

```java file="./Practice.java"
```
