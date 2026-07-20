# Step 06 — 오프셋 관리

> **학습 목표**
> - current offset / committed offset / log-end offset / high watermark 네 가지를 구분하고 위치 관계를 설명한다
> - `auto.offset.reset` 의 `latest` / `earliest` / `none` 을 **셋 다 직접 실행해** 차이를 확인한다
> - `__consumer_offsets` 토픽을 직접 조회해 커밋 레코드의 실제 모양을 읽는다
> - **`enable.auto.commit=true` 가 만드는 메시지 유실을 숫자로 재현한다**
> - **같은 설정이 만드는 중복 처리를 숫자로 재현한다**
> - `commitSync` / `commitAsync` / 특정 오프셋 커밋을 구분해서 쓰고, `--reset-offsets` 로 오프셋을 되감는다
>
> **선행 스텝**: Step 05 — 컨슈머와 컨슈머 그룹
> **예상 소요**: 120분

---

이 스텝은 코스의 뼈대 네 개 중 하나입니다. Step 04 가 "프로듀서가 잃는다" 였다면, 여기는 **"컨슈머가 잃는다"** 입니다.

중요한 것은 이 유실이 **에러 없이** 일어난다는 점입니다. 프로듀서는 100건을 성공적으로 보냈고, 브로커의 로그에도 100건이 있고, `kafka-consumer-groups.sh --describe` 는 `LAG 0` 을 보여줍니다. 그런데 애플리케이션이 실제로 처리한 것은 60건입니다. 아무도 실패하지 않았는데 40건이 사라졌습니다. 이 스텝에서 그 40건을 직접 만들어 봅니다.

---

## 6-0. 실습 준비

이 스텝 전용 토픽 `s06_orders` 를 만듭니다. 파티션은 **1개**로 둡니다. 오프셋의 이동을 눈으로 좇는 것이 목적이므로, 파티션이 여러 개면 숫자가 흩어져서 오히려 방해가 됩니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --create --topic s06_orders --partitions 1 --replication-factor 3
```

**결과**

```
Created topic s06_orders.
```

메시지 100건을 넣습니다. 키는 `C001`~`C010` 을 돌려 씁니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders \
  --property parse.key=true --property key.separator=: <<'EOF'
$(seq 1 100)
EOF
```

셸에서 헤어독으로 100줄을 만들기 번거로우니, 실제로는 아래 형태를 씁니다. `practice.sh` 에도 이 형태로 들어 있습니다.

```bash
for i in $(seq 1001 1100); do
  c=$(printf "C%03d" $(( (i % 10) + 1 )))
  echo "${c}:{\"order_id\":\"O-${i}\",\"customer_id\":\"${c}\",\"amount\":39000,\"status\":\"CREATED\"}"
done | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders \
  --property parse.key=true --property key.separator=:
```

100건이 들어갔는지 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders
```

**결과**

```
s06_orders:0:100
```

파티션 0 의 **다음에 쓸 오프셋**이 100 입니다. 즉 유효한 오프셋은 0~99, 총 100건입니다. 이 숫자가 이 스텝 내내 기준점이 됩니다.

---

## 6-1. 오프셋 4종 — 무엇이 어디를 가리키는가

Kafka 를 쓰면서 "오프셋" 이라는 단어를 들으면 최소 네 가지 중 하나를 뜻합니다. 이 넷을 섞어 쓰는 것이 오프셋 관련 혼란의 절반입니다.

```
   파티션 s06_orders-0 의 로그
   ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
   │ 0  │ 1  │ ...│ 59 │ 60 │ ...│ 79 │ 80 │ ...│ 99 │   ← 레코드
   └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
                       ▲              ▲              ▲   ▲
                       │              │              │   │
              committed offset   current offset   HW   LEO
                    = 60             = 80          =100 =100

     ├──── 이미 커밋됨 ────┤
                          ├─ 읽었지만 커밋 안 됨 ─┤
                                                 ├ 아직 안 읽음 ┤
```

| 이름 | 뜻 | 어디서 봅니까 | 누가 관리합니까 |
|---|---|---|---|
| **current offset** (position) | 이 컨슈머가 **다음에 poll 할** 오프셋. 메모리에만 있음 | 클라이언트 `consumer.position(tp)` | 컨슈머 (프로세스 죽으면 사라짐) |
| **committed offset** | 그룹이 **브로커에 기록한** "여기까지 처리했다" | `--describe` 의 `CURRENT-OFFSET` | 그룹 코디네이터 (`__consumer_offsets`) |
| **log-end offset (LEO)** | 파티션에 **다음에 쓸** 오프셋 = 총 건수 | `--describe` 의 `LOG-END-OFFSET`, `kafka-get-offsets.sh` | 브로커 (파티션 리더) |
| **high watermark (HW)** | **모든 ISR 이 복제 완료한** 지점. 컨슈머는 여기까지만 읽을 수 있음 | JMX, `kafka-log-dirs.sh` | 브로커 (리더가 계산) |

`LAG = LOG-END-OFFSET - CURRENT-OFFSET` 입니다. 그런데 `--describe` 의 `CURRENT-OFFSET` 은 **committed offset** 이지 current offset(position) 이 아닙니다. **이름이 헷갈리게 붙어 있습니다.** 이 스텝의 유실 재현이 성립하는 이유가 바로 여기 있습니다. CLI 가 보여주는 숫자는 "컨슈머가 실제로 처리한 지점"이 아니라 "커밋된 지점"일 뿐입니다.

> 💡 **HW 와 LEO 가 다를 때**
> 정상 상태에서는 HW = LEO 입니다. 팔로워가 복제를 다 따라잡았기 때문입니다. 팔로워가 뒤처지면 HW < LEO 가 되고, 컨슈머는 HW 까지만 읽습니다. **아직 복제되지 않은 레코드를 읽었다가 리더가 죽으면 없던 일이 되기 때문입니다.** 이 메커니즘은 Step 08 에서 브로커를 죽여 가며 다시 다룹니다.

`--describe` 로 네 숫자 중 두 개를 봅니다. 아직 컨슈머 그룹이 없으므로 먼저 하나 만들고 보겠습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders \
  --group s06-basic --from-beginning --max-messages 60
```

**결과** (마지막 줄만)

```
{"order_id":"O-1060","customer_id":"C001","amount":39000,"status":"CREATED"}
Processed a total of 60 messages
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:9092 --describe --group s06-basic
```

**결과**

```
Consumer group 's06-basic' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s06-basic       s06_orders      0          60              100             40              -               -               -
```

`CURRENT-OFFSET 60`, `LOG-END-OFFSET 100`, `LAG 40`. 60건을 읽고 커밋한 뒤 종료했으니 40건이 남았습니다. 여기까지는 직관과 맞습니다.

---

## 6-2. `auto.offset.reset` — 커밋된 오프셋이 없을 때

컨슈머가 붙었는데 **그 그룹의 커밋된 오프셋이 없으면** 어디부터 읽어야 할까요? 이때만 `auto.offset.reset` 이 쓰입니다. **커밋된 오프셋이 있으면 이 설정은 아무 영향도 주지 않습니다.** 이것이 첫 번째 오해입니다.

| 값 | 동작 | 기본값 |
|---|---|---|
| `latest` | 파티션의 **끝(LEO)** 부터. 즉 지금 이후에 들어오는 것만 | ✅ **기본** |
| `earliest` | 파티션의 **처음**부터. 남아 있는 모든 메시지 | |
| `none` | 예외를 던지고 죽는다 | |

세 가지를 전부 돌려 봅니다. 매번 **새 그룹 이름**을 쓴다는 점이 핵심입니다. 기존 그룹으로 하면 커밋된 오프셋이 있어서 이 설정이 무시됩니다.

### latest (기본값)

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders \
  --group s06-latest --timeout-ms 5000
```

**결과**

```
[2024-03-11 10:22:06,451] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.common.errors.TimeoutException
Processed a total of 0 messages
```

**0건.** 토픽에 100건이 그대로 있는데 0건입니다. 타임아웃 예외는 `--timeout-ms` 때문에 난 것이고, **오프셋 관점에서는 아무 에러도 아닙니다.** `--timeout-ms` 없이 돌리면 그냥 조용히 기다립니다.

### earliest

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders \
  --group s06-earliest --consumer-property auto.offset.reset=earliest \
  --timeout-ms 5000 | tail -1
```

**결과**

```
{"order_id":"O-1100","customer_id":"C001","amount":39000,"status":"CREATED"}
Processed a total of 100 messages
```

**100건.** `--from-beginning` 은 사실상 `--consumer-property auto.offset.reset=earliest` 의 축약형입니다.

### none

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders \
  --group s06-none --consumer-property auto.offset.reset=none \
  --timeout-ms 5000
```

**결과**

```
[2024-03-11 10:22:31,882] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.clients.consumer.NoOffsetForPartitionException: Undefined offset with no reset policy for partitions: [s06_orders-0]
Processed a total of 0 messages
```

`none` 은 **명시적으로 실패**합니다. 이게 나쁜 것 같지만, 사실 셋 중 가장 정직한 설정입니다.

> ⚠️ **함정 A — 새 그룹은 기본이 `latest` 라 과거 메시지를 조용히 건너뜁니다**
> 위 세 실행의 차이를 다시 봅니다. `latest` 는 **0건을 읽고도 아무 에러를 내지 않았습니다.** 그리고 더 나쁜 것은 그 뒤의 `--describe` 입니다.
> ```bash
> kcg --describe --group s06-latest
> ```
> **결과**
> ```
> Consumer group 's06-latest' has no active members.
>
> GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
> s06-latest      s06_orders      0          100             100             0               -               -               -
> ```
> **`LAG 0`.** 대시보드는 초록불입니다. 운영자는 "잘 처리되고 있다"고 판단합니다. 그런데 이 그룹은 **단 한 건도 처리하지 않았습니다.**
> 이 사고는 실무에서 이렇게 납니다. 신규 서비스를 배포하면서 새 `group.id` 를 붙였는데, 그 사이 쌓여 있던 미처리 주문 수천 건이 통째로 스킵됩니다. 배포는 성공했고, 랙은 0 이고, 로그도 깨끗합니다. 며칠 뒤 "왜 이 주문들이 처리가 안 됐지?" 로 발견됩니다.
> **해결**: ① 신규 그룹을 배포할 때는 `auto.offset.reset=earliest` 를 명시하거나, ② 배포 **전에** 그룹을 만들어 두고(`--reset-offsets --to-earliest --execute`) 원하는 지점을 지정합니다. ③ 정말로 "지금부터"가 맞다면 `latest` 를 **의도적으로 명시**하십시오. 기본값에 기대지 마세요. 코드를 읽는 사람이 그 결정을 볼 수 있어야 합니다.

> 💡 **실무 팁 — 운영 컨슈머에는 `none` 도 고려할 만합니다**
> `none` 은 커밋된 오프셋이 없으면 기동 자체를 실패시킵니다. "그룹이 사라졌다"는 사건을 **배포 시점에 즉시 알려 준다**는 뜻입니다. `latest` 였다면 조용히 최신부터 읽기 시작했을 것이고, `earliest` 였다면 며칠 치를 통째로 재처리했을 것입니다. 둘 다 운영에서는 사고입니다.
> 대신 최초 배포 시 오프셋을 사람이 명시적으로 초기화해야 하는 절차가 생깁니다. 이 절차를 감당할 수 있는 팀이라면 `none` 이 가장 안전합니다.

---

## 6-3. `__consumer_offsets` — 커밋은 결국 토픽에 쓰는 것이다

컨슈머 그룹의 오프셋은 어디에 저장될까요? **파일이나 별도 DB 가 아니라, `__consumer_offsets` 라는 내부 토픽입니다.** 즉 커밋은 "Kafka 에 메시지를 하나 더 쓰는 것"입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --describe --topic __consumer_offsets | head -4
```

**결과**

```
Topic: __consumer_offsets	TopicId: yTkR9wQ2Sxu1Cn8vLmPdEg	PartitionCount: 50	ReplicationFactor: 3	Configs: compression.type=producer,cleanup.policy=compact,segment.bytes=104857600
	Topic: __consumer_offsets	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: __consumer_offsets	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: __consumer_offsets	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
```

세 가지가 눈에 띕니다.

- **`PartitionCount: 50`** — `offsets.topic.num.partitions` 기본값입니다. 그룹이 많아도 병렬로 감당하기 위해서입니다.
- **`cleanup.policy=compact`** — 로그 압축입니다. 같은 키(그룹+토픽+파티션)의 **최신 값만 남깁니다.** 그래서 커밋을 100만 번 해도 토픽이 무한히 커지지 않습니다. 압축의 자세한 동작은 Step 09 에서 다룹니다.
- **`ReplicationFactor: 3`** — 브로커 1대짜리 환경에서 이 값을 3으로 두면 그룹 생성 자체가 실패합니다. Docker 환경 문서에서 언급한 그 설정입니다.

### 어느 파티션에 저장됩니까

그룹 이름의 해시로 결정됩니다.

```
partition = abs(groupId.hashCode()) % 50
```

`s06-basic` 이 어디로 가는지 계산해 봅니다.

```bash
docker exec kafka-1 sh -c 'cat > /tmp/H.java <<EOF
public class H { public static void main(String[] a) {
  System.out.println(Math.abs(a[0].hashCode()) % 50); } }
EOF
cd /tmp && java H.java s06-basic'
```

**결과**

```
17
```

**같은 그룹의 모든 오프셋 커밋은 항상 같은 파티션으로 갑니다.** 이것이 그룹 코디네이터를 정하는 방식이기도 합니다. 그 파티션의 **리더 브로커가 곧 그 그룹의 코디네이터**입니다. 그룹이 리밸런싱할 때 어느 브로커가 조율하는지가 여기서 결정됩니다.

### 실제 커밋 레코드를 봅니다

`__consumer_offsets` 의 값은 바이너리라 그냥 읽으면 깨집니다. 전용 포매터를 씁니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataMessageFormatter" \
  --from-beginning --timeout-ms 10000 | grep s06
```

**결과**

```
[s06-basic,s06_orders,0]::OffsetAndMetadata(offset=60, leaderEpoch=Optional[0], metadata=, commitTimestamp=1710120121113, expireTimestamp=None)
[s06-latest,s06_orders,0]::OffsetAndMetadata(offset=100, leaderEpoch=Optional[0], metadata=, commitTimestamp=1710120138902, expireTimestamp=None)
[s06-earliest,s06_orders,0]::OffsetAndMetadata(offset=100, leaderEpoch=Optional[0], metadata=, commitTimestamp=1710120145771, expireTimestamp=None)
```

읽는 법입니다.

- **`[s06-basic,s06_orders,0]`** — 키입니다. `[그룹, 토픽, 파티션]`. 이 키가 압축의 기준이므로, 같은 조합의 옛 커밋은 사라집니다.
- **`offset=60`** — 값입니다. `--describe` 의 `CURRENT-OFFSET 60` 과 정확히 같은 숫자입니다. **CLI 는 이 토픽을 읽어서 보여주는 것뿐입니다.**
- **`leaderEpoch=Optional[0]`** — 커밋 시점의 리더 에폭. 리더가 바뀐 뒤 옛 에폭의 오프셋으로 되감는 사고를 막는 데 씁니다.
- **`metadata=`** — 애플리케이션이 붙일 수 있는 임의 문자열입니다. `commitSync(Map<TopicPartition, OffsetAndMetadata>)` 로 직접 커밋할 때 채울 수 있습니다. 보통 비어 있습니다.
- **`expireTimestamp=None`** — 아래 함정을 보십시오.

`GroupMetadataMessageFormatter` 는 그룹 메타데이터(멤버십)만 보여주는 포매터라 버전에 따라 오프셋 레코드가 `NULL` 로 나올 수 있습니다. 오프셋만 보려면 `kafka.coordinator.group.OffsetsMessageFormatter` 를 쓰면 됩니다. 3.7 에서는 위 포매터가 두 종류를 모두 처리합니다.

> ⚠️ **함정 — `offsets.retention.minutes` 로 오프셋이 조용히 사라집니다**
> 브로커 설정 `offsets.retention.minutes` 의 기본값은 **10080분 = 7일**입니다. 그룹이 **비활성 상태로** 이 기간을 넘기면 커밋된 오프셋이 삭제됩니다.
> ```bash
> kconf --describe --entity-type brokers --entity-name 1 --all | grep offsets.retention
> ```
> **결과**
> ```
>   offsets.retention.minutes=10080 sensitive=false synonyms={DEFAULT_CONFIG:offsets.retention.minutes=10080}
> ```
> 무슨 일이 벌어지느냐면, **오프셋이 사라진 그룹은 다음 기동 때 `auto.offset.reset` 을 따릅니다.** 기본값 `latest` 라면 그 사이 쌓인 메시지를 전부 건너뜁니다. 함정 A 가 그대로 재현되는 것입니다.
> 이 사고는 특히 이런 곳에서 납니다 — 배치성 컨슈머(주 1회 정산), 스테이징 환경(연휴 동안 정지), 트래픽이 없어 스케일 인으로 파드가 0이 된 서비스. **"오랜만에 켰더니 옛날 메시지를 안 읽는다"** 의 정체입니다.
> **해결**: ① 컨슈머를 완전히 내리지 말고 최소 1개는 붙여 둡니다(활성 그룹은 만료되지 않습니다). ② 정말 오래 쉬는 그룹이면 `offsets.retention.minutes` 를 늘립니다. ③ 재기동 전에 `--reset-offsets --to-datetime` 등으로 명시적으로 지정합니다.
> 참고로 Kafka 2.1 이전에는 이 기본값이 **1440분(24시간)** 이었습니다. 주말 지나면 오프셋이 날아가던 시절입니다.

---

## 6-4. 핵심 함정 B — auto commit 이 만드는 메시지 유실

여기가 이 스텝의 심장입니다.

`enable.auto.commit` 의 기본값은 **`true`** 이고, `auto.commit.interval.ms` 의 기본값은 **5000(5초)** 입니다. 아무 설정도 안 한 컨슈머는 자동 커밋 컨슈머입니다.

문제는 **커밋 시점**입니다. 자동 커밋은 이렇게 동작합니다.

```
  poll() 호출
    │
    ├─ (내부) 마지막 커밋으로부터 5초 지났나? → 지났으면 커밋
    │        커밋하는 값 = "직전 poll 이 반환한 레코드의 마지막 오프셋 + 1"
    │        ※ 그 레코드들을 애플리케이션이 처리했는지는 확인하지 않는다
    │
    └─ 레코드 배치 반환 ──► 애플리케이션이 for 루프로 처리
                              │
                              │  ← 여기서 프로세스가 죽으면?
                              ▼
                      이미 커밋된 구간은 영영 다시 안 읽힌다
```

**커밋은 "처리 완료"의 증거가 아니라 "poll 로 넘겨줬다"의 증거일 뿐입니다.** 이 간극이 유실입니다.

### 재현

`Practice.java` 의 `autocommit-loss` 시나리오가 이걸 재현합니다. 핵심 부분입니다.

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");   // 관찰용으로 1초
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");           // 100건을 한 번에 받는다
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

int processed = 0;
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
    for (ConsumerRecord<String, String> r : records) {
        Thread.sleep(50);                      // 건당 50ms 걸리는 "무거운 처리"
        processed++;
        System.out.println("처리 " + processed + "건, offset=" + r.offset());
        if (processed == 60) {
            System.out.println("=== 여기서 프로세스가 죽습니다 ===");
            Runtime.getRuntime().halt(1);      // kill -9 와 동일. shutdown hook 도 안 돈다
        }
    }
}
```

`max.poll.records=100` 이므로 첫 `poll()` 이 100건을 통째로 반환합니다. 건당 50ms 이므로 60건 처리에 3초가 걸립니다. 그 3초 동안 백그라운드에서 `poll()` 이 다시 호출되지는 않지만 — **자동 커밋은 다음 `poll()` 에서 일어납니다.** 그렇다면 왜 유실이 날까요?

여기서 실제 순서를 정확히 봐야 합니다. `max.poll.records` 를 작게 두면 유실이 훨씬 선명해집니다. 시나리오는 `max.poll.records=10` 으로 돌립니다.

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-loss'
```

**결과** (일부 생략)

```
[setup] 그룹 s06-loss 를 earliest 로 시작합니다.
처리 1건, offset=0
처리 2건, offset=1
...
처리 10건, offset=9
[poll] 새 배치 요청 → 자동 커밋 발생 (offset=10 까지)
처리 11건, offset=10
...
처리 60건, offset=59
[poll] 새 배치 요청 → 자동 커밋 발생 (offset=100 까지)
처리 61건, offset=60
=== 여기서 프로세스가 죽습니다 ===
```

죽기 직전의 마지막 `poll()` 이 **남은 40건을 한꺼번에 가져오면서 오프셋 100 을 커밋**했습니다. 그런데 애플리케이션은 61건째를 처리하다 죽었습니다.

재시작해서 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:9092 --describe --group s06-loss
```

**결과**

```
Consumer group 's06-loss' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s06-loss        s06_orders      0          100             100             0               -               -               -
```

**LAG 0.** 그리고 같은 그룹으로 다시 컨슈머를 띄우면:

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-loss-resume'
```

**결과**

```
[resume] 그룹 s06-loss 로 재접속합니다.
[resume] 30초 동안 0건 수신. 처리할 메시지가 없습니다.
[집계] 총 처리 건수: 60 / 발행 건수: 100 → 유실 40건
```

**100건 중 60건만 처리됐고, 커밋은 100 까지입니다. 40건이 사라졌습니다.**

> ⚠️ **함정 B — `enable.auto.commit=true` + 무거운 처리 = 유실**
> 위 재현에서 **어떤 예외도 던져지지 않았습니다.** 브로커 로그도 깨끗하고, `--describe` 는 `LAG 0` 이며, 프로듀서는 100건 전송에 성공했습니다. 관측 가능한 모든 지표가 정상입니다. 유실된 40건은 **애플리케이션 안에서만** 없어졌습니다.
> 위험도는 두 값의 곱으로 결정됩니다. **`max.poll.records` × 건당 처리 시간**이 `auto.commit.interval.ms` 보다 크면 언제든 이 창이 열립니다. 기본값 조합(`max.poll.records=500`, `interval=5000ms`)에서 건당 10ms 만 걸려도 5초를 넘깁니다. **흔한 조합입니다.**
> **해결**: ① `enable.auto.commit=false` 로 두고 **처리 후 직접 커밋**합니다. 이것이 유일하게 확실한 해법입니다. ② 자동 커밋을 유지해야 한다면 `max.poll.records` 를 처리 속도에 맞게 줄여 배치 하나를 `auto.commit.interval.ms` 안에 끝낼 수 있게 합니다. 다만 이건 처리 시간이 튀는 순간 다시 깨지는 미봉책입니다.

---

## 6-5. 핵심 함정 C — 같은 auto commit 이 중복도 만든다

방금과 정반대 상황입니다. 이번에는 처리를 **다 하고** 커밋 **전에** 죽습니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-dup'
```

이 시나리오는 `auto.commit.interval.ms=30000`(30초)으로 두고, 100건을 빠르게 처리한 뒤 죽습니다.

**결과**

```
[setup] 그룹 s06-dup, auto.commit.interval.ms=30000
처리 1건, offset=0
...
처리 40건, offset=39
[poll] 새 배치 요청 → 30초가 안 지나 커밋하지 않음
...
처리 100건, offset=99
[상태] 처리 완료 100건 / 마지막 커밋 오프셋 40
=== 여기서 프로세스가 죽습니다 ===
```

```bash
kcg --describe --group s06-dup
```

**결과**

```
Consumer group 's06-dup' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s06-dup         s06_orders      0          40              100             60              -               -               -
```

**100건을 처리했는데 커밋은 40 입니다.** 재시작하면:

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-dup-resume'
```

**결과**

```
[resume] 그룹 s06-dup 로 재접속합니다. 커밋된 오프셋 40 부터 읽습니다.
재처리 1건, offset=40
...
재처리 60건, offset=99
[집계] 1회차 100건 + 2회차 60건 = 160건 처리 / 발행 100건 → 중복 60건
```

**60건이 두 번 처리됐습니다.**

> ⚠️ **함정 C — 같은 설정이 유실도 중복도 만듭니다**
> 함정 B 와 C 는 **동일한 설정**에서 나옵니다. 차이는 오직 "커밋 타이밍과 처리 타이밍 중 무엇이 앞섰나" 뿐이고, 그건 프로세스가 언제 죽었느냐에 달렸습니다. **즉 자동 커밋은 유실이냐 중복이냐를 운에 맡깁니다.**
> 이게 왜 심각한지는 도메인을 넣어 보면 분명합니다. `s06_orders` 를 결제 요청 토픽이라고 하면, 함정 B 는 **결제 40건이 누락**이고 함정 C 는 **결제 60건이 이중 청구**입니다. 어느 쪽도 "재시작하면 알아서 맞춰집니다" 로 넘어갈 수 없습니다.
> **해결**: 자동 커밋을 끄면 **최소한 어느 쪽인지는 고를 수 있습니다.** 처리 후 커밋하면 중복(at-least-once), 커밋 후 처리하면 유실(at-most-once)입니다. 그다음 중복을 감당할 방법(멱등 처리)을 마련하는 것이 실무의 정석입니다. 이 선택지 전체를 Step 07 에서 다룹니다.

> 💡 **실무 팁 — 중복은 유실보다 낫습니다**
> 둘 중 하나를 골라야 한다면 거의 항상 **at-least-once(중복)** 입니다. 유실된 메시지는 복구할 방법이 없지만, 중복은 **비즈니스 키로 걸러낼 수 있기 때문입니다.** 주문 ID 로 유니크 제약을 걸어 두면 두 번째 처리가 그냥 실패합니다. Step 07 에서 이 패턴을 구체적으로 다룹니다.

---

## 6-6. 수동 커밋 — commitSync / commitAsync

`enable.auto.commit=false` 로 두면 커밋 책임이 애플리케이션으로 옵니다. 세 가지 방법이 있습니다.

### commitSync()

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
    for (ConsumerRecord<String, String> r : records) {
        process(r);                       // 처리를 먼저
    }
    consumer.commitSync();                // 배치를 다 처리한 뒤 커밋
}
```

브로커의 응답을 **기다립니다.** 실패하면 내부적으로 재시도하고, 재시도가 소진되면 `CommitFailedException` 을 던집니다. 즉 **실패를 알 수 있습니다.**

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java manual-sync'
```

**결과**

```
[setup] 그룹 s06-manual, enable.auto.commit=false
[batch] 10건 수신 → 처리 → commitSync() 완료 (offset=10)
[batch] 10건 수신 → 처리 → commitSync() 완료 (offset=20)
...
[batch] 10건 수신 → 처리 → commitSync() 완료 (offset=100)
[집계] 처리 100건, 커밋 100. 유실 0, 중복 0
[측정] 총 소요 6.42초 (커밋 10회, 커밋당 평균 18.4ms)
```

커밋 10회에 184ms 를 썼습니다. 배치가 커서 티가 안 나지만, **배치 하나가 작으면 커밋 왕복이 처리량을 지배합니다.**

### commitAsync()

```java
consumer.commitAsync();   // 요청만 보내고 즉시 반환
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java manual-async'
```

**결과**

```
[집계] 처리 100건, 커밋 100. 유실 0, 중복 0
[측정] 총 소요 5.31초 (커밋 10회, 블로킹 시간 0.9ms)
```

**6.42초 → 5.31초.** 커밋 대기가 사라진 만큼 빨라졌습니다. 대신 실패를 모릅니다. 콜백을 붙이면 알 수는 있습니다.

```java
consumer.commitAsync((offsets, exception) -> {
    if (exception != null) {
        log.error("커밋 실패: {}", offsets, exception);
    }
});
```

### 두 방식 비교

| | `commitSync()` | `commitAsync()` |
|---|---|---|
| 블로킹 | 응답까지 대기 | 즉시 반환 |
| 처리량 | 낮음 | 높음 |
| 자동 재시도 | **있음** (`default.api.timeout.ms` 내) | **없음** |
| 실패 인지 | 예외 | 콜백을 붙여야만 |
| 순서 역전 위험 | 없음 | **있음** (아래) |
| 언제 씁니까 | 종료 직전, 리밸런싱 직전 | 정상 루프 |

> ⚠️ **함정 — `commitAsync` 를 재시도하면 오프셋이 뒤로 갑니다**
> `commitAsync` 는 재시도하지 않습니다. **이건 버그가 아니라 의도된 설계입니다.**
> 오프셋 100 커밋이 실패하고, 그 사이 오프셋 200 커밋이 성공했다고 합시다. 여기서 100 을 재시도해 성공하면 **커밋된 오프셋이 200 에서 100 으로 되돌아갑니다.** 그 뒤 리밸런싱이 일어나면 100~199 를 통째로 재처리합니다.
> 그래서 콜백 안에서 `commitAsync` 를 다시 호출하는 코드는 위험합니다. 굳이 재시도하려면 커밋마다 단조 증가하는 시퀀스 번호를 두고, **더 큰 번호가 이미 성공했으면 재시도를 포기**해야 합니다. 대부분의 경우 그럴 가치가 없습니다.
> **해결**: 정상 루프는 `commitAsync`(실패해도 다음 커밋이 덮어씀), **종료 직전에만 `commitSync`.** 아래 정석 패턴입니다.

### 정석 패턴 — try / finally

```java
try {
    while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> r : records) {
            process(r);
        }
        consumer.commitAsync();          // 정상 루프에서는 비동기 (빠름, 실패해도 다음 커밋이 덮음)
    }
} catch (WakeupException e) {
    // consumer.wakeup() 으로 정상 종료를 요청받음. 무시한다.
} finally {
    try {
        consumer.commitSync();           // 마지막 한 번은 반드시 동기 커밋
    } finally {
        consumer.close();                // close() 는 리밸런싱을 즉시 트리거한다
    }
}
```

**핵심은 `finally` 의 `commitSync()` 입니다.** 정상 루프의 `commitAsync` 는 실패해도 다음 커밋이 덮어 주지만, **마지막 커밋에는 다음이 없습니다.** 여기가 실패하면 그만큼이 중복 처리됩니다. `close()` 를 안쪽 `finally` 에 두는 것도 중요합니다. 커밋이 예외를 던져도 컨슈머는 닫혀야 합니다.

### 특정 오프셋 커밋

배치 중간에도 커밋하고 싶으면 오프셋을 직접 지정합니다.

```java
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();

for (ConsumerRecord<String, String> r : records) {
    process(r);
    offsets.put(new TopicPartition(r.topic(), r.partition()),
                new OffsetAndMetadata(r.offset() + 1, "processed-by-worker-3"));   // ← +1
    if (count++ % 10 == 0) {
        consumer.commitSync(offsets);
        offsets.clear();
    }
}
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java manual-per-record'
```

**결과**

```
[setup] 그룹 s06-per-record, 10건마다 특정 오프셋 커밋
처리 offset=0..9 → commitSync({s06_orders-0: 10})
처리 offset=10..19 → commitSync({s06_orders-0: 20})
...
처리 offset=90..99 → commitSync({s06_orders-0: 100})
[집계] 처리 100건, 커밋 100
```

`metadata` 에 넣은 문자열은 `__consumer_offsets` 에서 확인할 수 있습니다.

```
[s06-per-record,s06_orders,0]::OffsetAndMetadata(offset=100, leaderEpoch=Optional[0], metadata=processed-by-worker-3, commitTimestamp=1710120199431, expireTimestamp=None)
```

> ⚠️ **함정 — 커밋할 오프셋은 "마지막 처리 오프셋 + 1" 입니다**
> `r.offset()` 이 아니라 **`r.offset() + 1`** 입니다. 커밋된 오프셋의 의미가 "마지막으로 처리한 레코드" 가 아니라 **"다음에 읽어야 할 위치"** 이기 때문입니다.
> `+1` 을 빼먹으면 재시작할 때마다 **마지막 레코드 한 건이 항상 다시 처리됩니다.** 그리고 이것은 눈에 잘 띄지 않습니다. 100건 중 1건이라 로그를 훑어봐서는 모르고, `--describe` 의 LAG 은 0 이 아니라 **1** 로 나옵니다. "왜 랙이 항상 1 이지?" 는 십중팔구 이 실수입니다.
> `commitSync()` 를 인자 없이 호출하면 클라이언트가 알아서 `+1` 을 해 주므로 이 실수가 없습니다. **직접 오프셋을 지정할 때만 조심하면 됩니다.**

---

## 6-7. 오프셋 리셋 — `kafka-consumer-groups.sh --reset-offsets`

이미 커밋된 오프셋을 바꾸는 도구입니다. 재처리, 스킵, 특정 시점으로 되감기에 씁니다.

| 옵션 | 뜻 |
|---|---|
| `--to-earliest` | 파티션의 가장 오래된 오프셋으로 |
| `--to-latest` | 파티션의 끝으로 (전부 스킵) |
| `--to-offset N` | 절대 오프셋 N 으로 |
| `--shift-by N` | 현재 커밋에서 N 만큼 이동 (음수 가능) |
| `--to-datetime YYYY-MM-DDTHH:mm:SS.sss` | 그 시각 이후 첫 메시지로 |
| `--by-duration PnDTnHnMnS` | 지금으로부터 그만큼 전으로 (예: `PT1H`) |
| `--from-file FILE` | CSV 파일로 파티션별 지정 |
| `--to-current` | 현재 커밋 유지 (검증용) |

범위는 `--all-topics` 또는 `--topic NAME` / `--topic NAME:0,1` 로 지정합니다.

### `--dry-run` 이 기본입니다

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:9092 \
  --group s06-basic --topic s06_orders \
  --reset-offsets --to-earliest --dry-run
```

**결과**

```
GROUP                          TOPIC                          PARTITION  NEW-OFFSET
s06-basic                      s06_orders                     0          0
```

**아직 아무것도 바뀌지 않았습니다.** `--dry-run` 을 생략해도 동일하게 dry-run 입니다. 실제로 적용하려면 `--execute` 를 명시해야 합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:9092 \
  --group s06-basic --topic s06_orders \
  --reset-offsets --to-earliest --execute
```

**결과**

```
GROUP                          TOPIC                          PARTITION  NEW-OFFSET
s06-basic                      s06_orders                     0          0
```

출력이 같습니다. **`--dry-run` 과 `--execute` 의 출력이 구분되지 않습니다.** 실제로 적용됐는지는 `--describe` 로 확인해야 합니다.

```bash
kcg --describe --group s06-basic
```

**결과**

```
Consumer group 's06-basic' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s06-basic       s06_orders      0          0               100             100             -               -               -
```

`CURRENT-OFFSET 0`, `LAG 100`. 되감겼습니다.

### 그룹이 살아 있으면 실패합니다

컨슈머를 하나 띄워 둔 채로 리셋을 시도합니다.

```bash
# [터미널 B]
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s06_orders --group s06-basic
```

```bash
# [터미널 A]
kcg --group s06-basic --topic s06_orders --reset-offsets --to-earliest --execute
```

**결과**

```
Error: Assignments can only be reset if the group 's06-basic' is inactive, but the current state is Stable.
```

> ⚠️ **함정 — 리셋하려면 컨슈머를 전부 내려야 합니다**
> 이건 안전장치입니다. 살아 있는 컨슈머가 오프셋을 계속 커밋하는 중에 외부에서 되감으면, 되감은 값이 즉시 덮어씌워지거나 컨슈머가 같은 구간을 두 번 읽는 등 예측할 수 없는 상태가 됩니다.
> 문제는 운영에서의 절차입니다. **"장애 복구를 위해 1시간 전으로 되감아야 하는데, 그러려면 서비스를 내려야 한다"** 는 상황이 됩니다. 파드를 0으로 스케일 인하고 → 리셋하고 → 다시 올리는 순서를 밟아야 하며, 그동안 랙은 계속 쌓입니다.
> 그리고 함정이 하나 더 있습니다. `--describe` 로 상태가 `Empty` 로 보여도 **리밸런싱 중이거나 세션 타임아웃(`session.timeout.ms`, 기본 45초)이 만료되기 전이면 여전히 실패**합니다. 컨슈머를 내린 직후에 바로 리셋하면 이 에러를 보게 됩니다. 상태가 `Empty` 로 굳을 때까지 기다리세요.
> ```bash
> kcg --describe --group s06-basic --state
> ```
> ```
> GROUP           COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE           #MEMBERS
> s06-basic       kafka-1:9092 (1)                               Empty           0
> ```
> **해결**: 애플리케이션 안에서 `seek()` 로 처리하면 서비스를 안 내려도 됩니다 (다음 절).

### 시간 기반 리셋

운영에서 가장 자주 쓰는 형태입니다. "1시간 전부터 다시" 를 이렇게 씁니다.

```bash
kcg --group s06-basic --topic s06_orders --reset-offsets --by-duration PT1H --dry-run
```

**결과**

```
GROUP                          TOPIC                          PARTITION  NEW-OFFSET
s06-basic                      s06_orders                     0          0
```

이 실습에서는 메시지를 방금 넣었으므로 1시간 전 = 로그의 시작이라 0 이 나옵니다. 절대 시각으로도 됩니다.

```bash
kcg --group s06-basic --topic s06_orders \
  --reset-offsets --to-datetime 2024-03-11T10:22:00.000 --dry-run
```

> 💡 **`--to-datetime` 의 시간대는 브로커의 시간대입니다**
> 값에 타임존을 붙이지 않으면 **브로커 JVM 의 기본 시간대**로 해석합니다. 컨테이너가 UTC 이고 사람이 KST 로 생각하고 있으면 9시간이 어긋나고, 그 결과 **9시간 치를 재처리하거나 9시간 치를 건너뜁니다.** 명시적으로 `2024-03-11T10:22:00.000+09:00` 처럼 오프셋을 붙이는 편이 안전합니다.

---

## 6-8. `seek()` — 애플리케이션 안에서 되감기

`--reset-offsets` 는 밖에서 오프셋을 바꾸는 것이고, `seek()` 는 컨슈머가 스스로 위치를 바꾸는 것입니다.

```java
consumer.subscribe(List.of("s06_orders"));
consumer.poll(Duration.ofMillis(0));            // ← 파티션 할당을 받기 위한 빈 poll (필수)

TopicPartition tp = new TopicPartition("s06_orders", 0);
consumer.seek(tp, 30);                          // 오프셋 30 으로
// consumer.seekToBeginning(List.of(tp));       // 처음으로
// consumer.seekToEnd(List.of(tp));             // 끝으로
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java seek'
```

**결과**

```
[assign] 할당된 파티션: [s06_orders-0]
[seek] s06_orders-0 → offset 30
읽음 offset=30 key=C001 O-1031
읽음 offset=31 key=C002 O-1032
...
읽음 offset=39 key=C010 O-1040
[seekToEnd] s06_orders-0 → offset 100
[poll] 5초 동안 0건. 끝으로 이동했으므로 새 메시지만 옵니다.
[seekToBeginning] s06_orders-0 → offset 0
읽음 offset=0 key=C002 O-1001
[집계] seek 로 위치를 3번 바꿨습니다. 커밋은 하지 않았습니다.
```

| | `--reset-offsets` | `seek()` |
|---|---|---|
| 실행 주체 | 외부 CLI / AdminClient | 컨슈머 프로세스 자신 |
| 그룹 활성 상태 | **비활성이어야 함** | 상관없음 |
| 즉시 커밋 | **함** (`__consumer_offsets` 에 씀) | **안 함** (다음 커밋 때 반영) |
| 파티션 지정 | 그룹의 전체/일부 토픽 | 자신에게 할당된 파티션만 |
| 용도 | 운영 개입, 일괄 재처리 | 애플리케이션 로직 (특정 시점부터 시작, 에러 후 되감기) |

**`seek()` 는 커밋하지 않는다는 점이 핵심입니다.** seek 한 위치에서 읽다가 커밋 없이 죽으면 원래 커밋 지점으로 돌아갑니다. 반대로 `--reset-offsets` 는 즉시 `__consumer_offsets` 에 기록되므로 되돌릴 수 없습니다.

> 💡 **실무 팁 — `offsetsForTimes()` 로 시간을 오프셋으로 바꿉니다**
> `seek()` 는 숫자 오프셋만 받으므로, "1시간 전부터" 를 하려면 시각을 오프셋으로 변환해야 합니다.
> ```java
> long ts = System.currentTimeMillis() - 3_600_000;
> Map<TopicPartition, OffsetAndTimestamp> found =
>     consumer.offsetsForTimes(Map.of(tp, ts));
> OffsetAndTimestamp oat = found.get(tp);
> if (oat != null) consumer.seek(tp, oat.offset());
> else             consumer.seekToEnd(List.of(tp));   // 그 시각 이후 메시지가 없으면 null
> ```
> **반환값이 `null` 일 수 있다는 점을 반드시 처리하세요.** 그 시각 이후에 쓰인 메시지가 하나도 없으면 null 입니다. NPE 로 컨슈머가 기동 실패하는 흔한 원인입니다.

---

## 6-9. 정리 (실습 마무리)

이 스텝에서 만든 토픽과 그룹을 전부 지웁니다. **특히 컨슈머 그룹을 안 지우면 다음 스텝에서 "왜 메시지가 안 읽히지?" 로 헤맵니다.**

```bash
for g in s06-basic s06-latest s06-earliest s06-loss s06-dup s06-manual s06-async s06-per-record s06-seek; do
  docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka-1:9092 --delete --group "$g" 2>/dev/null || true
done

docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:9092 --delete --topic s06_orders
```

확인합니다.

```bash
kcg --list | grep s06 || echo "남은 s06 그룹 없음"
kt --list | grep s06 || echo "남은 s06 토픽 없음"
```

**결과**

```
남은 s06 그룹 없음
남은 s06 토픽 없음
```

`--delete --group` 은 그룹이 활성 상태면 실패합니다.

```
Error: Deletion of some consumer groups failed:
* Group 's06-basic' could not be deleted due to: java.util.concurrent.ExecutionException: org.apache.kafka.common.errors.GroupNotEmptyException: The group is not empty.
```

컨슈머가 남아 있는지 확인하고 내린 뒤 다시 시도하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| current offset | 컨슈머가 다음에 poll 할 위치. **메모리에만 있음** |
| committed offset | 브로커에 기록된 "여기까지". `--describe` 의 `CURRENT-OFFSET` 이 **이것** |
| log-end offset | 파티션에 다음에 쓸 오프셋 = 총 건수 |
| high watermark | 모든 ISR 이 복제한 지점. 컨슈머는 여기까지만 읽음 |
| `LAG` | `LOG-END-OFFSET - CURRENT-OFFSET`. **처리량이 아니라 커밋량 기준** |
| `auto.offset.reset` | **커밋된 오프셋이 없을 때만** 적용. 기본 `latest` |
| 함정 A | 새 그룹은 기본 `latest` → 과거 메시지를 조용히 스킵, LAG 은 0 |
| `__consumer_offsets` | 파티션 50개, `compact`. 그룹의 커밋이 **토픽에 저장**됨 |
| 그룹 → 파티션 | `abs(groupId.hashCode()) % 50`. 그 파티션의 리더 = 그룹 코디네이터 |
| `offsets.retention.minutes` | 기본 7일. 비활성 그룹의 오프셋이 **조용히 만료**됨 |
| 함정 B | `enable.auto.commit=true` → 처리 전 커밋 → **유실**. 100건 중 60건만 처리, LAG 0 |
| 함정 C | 같은 설정 → 처리 후 커밋 전 사망 → **중복**. 100건 처리, 커밋 40, 60건 재처리 |
| `commitSync` | 블로킹, 자동 재시도, 예외로 실패 인지. **종료 직전에 필수** |
| `commitAsync` | 논블로킹, 재시도 없음. 정상 루프용. **재시도하면 오프셋 역전** |
| 정석 패턴 | 루프는 `commitAsync`, `finally` 에서 `commitSync` 후 `close` |
| 특정 오프셋 커밋 | 값은 **마지막 처리 오프셋 + 1**. 빼먹으면 LAG 이 항상 1 |
| `--reset-offsets` | 기본이 `--dry-run`. 적용은 `--execute`. **그룹이 비어 있어야 함** |
| `seek()` | 컨슈머 스스로 이동. **커밋하지 않음**. 그룹 활성 상태에서도 가능 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`. **직접 실행해서 숫자를 확인**하세요.

1. 새 그룹으로 `latest` / `earliest` 를 각각 붙여 읽은 건수와 `--describe` 의 LAG 을 비교하기
2. `__consumer_offsets` 에서 특정 그룹의 커밋 레코드만 뽑아내고, 그 그룹이 몇 번 파티션에 저장되는지 계산하기
3. `Practice.java autocommit-loss` 를 돌린 뒤 "처리 건수 ≠ 커밋 오프셋" 을 숫자로 증명하기
4. 자동 커밋을 유지하면서 유실 창을 없애도록 `max.poll.records` 와 `auto.commit.interval.ms` 를 조정하기
5. 특정 오프셋 커밋에서 `+1` 을 빼먹었을 때 LAG 이 어떻게 되는지 재현하고 설명하기
6. 활성 그룹을 `--reset-offsets` 로 되감으려 시도해 에러를 재현하고, 성공하도록 절차 고치기
7. `--reset-offsets --shift-by -30` 과 `seek(tp, position-30)` 의 결과 차이를 `--describe` 로 비교하기

---

## 다음 단계

이 스텝에서 유실과 중복을 각각 재현했습니다. 그런데 아직 "그래서 어떻게 해야 하나" 에는 답하지 않았습니다. 커밋을 처리 앞에 두면 유실이고 뒤에 두면 중복이라면, 둘 다 없게 만들 수는 없을까요?

다음 스텝에서 at-most-once / at-least-once / exactly-once 를 정의하고 **셋 다 직접 재현**합니다. 그리고 멱등 프로듀서와 트랜잭션 API 로 Kafka 가 어디까지 보장해 주는지, 그리고 **어디부터는 보장해 주지 않는지**를 확인합니다.

→ [Step 07 — 전달 보장](../step-07-delivery-semantics/)

---

## 실습 파일

이 스텝은 파일 네 개로 진행합니다. 먼저 `practice.sh` 를 위에서부터 따라 실행하며 6-0 ~ 6-9 의 모든 관찰을 재현하고, 유실·중복 재현 구간에서는 `Practice.java` 를 컨테이너에 복사해 시나리오별로 돌립니다. 그다음 `exercise.sh` 의 7문제를 직접 풀고 `solution.sh` 로 대조합니다. 네 파일 모두 `s06_orders` 토픽과 `s06-*` 그룹만 건드리며, 공용 토픽(`orders` 등)은 손대지 않습니다.

### practice.sh

본문 6-0 ~ 6-9 의 모든 명령을 절 번호 주석과 함께 담은 실행 스크립트입니다.

- 상단에 `BS=kafka-1:9092` 와 `K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }` 헬퍼를 정의해, 이후 모든 호출이 `K kafka-topics.sh --bootstrap-server "$BS" ...` 형태로 짧아집니다. `KI()` 는 stdin 을 넘겨야 하는 프로듀서 전용(`docker exec -i`)입니다.
- `[6-0]` 의 메시지 100건 생성은 `for i in $(seq 1001 1100)` 루프로 `C001`~`C010` 키를 순환시켜 만듭니다. **이 100 이라는 숫자가 이후 모든 절의 기준값**이므로 건수를 바꾸면 본문의 "60/40" 같은 숫자가 전부 어긋납니다.
- `[6-2]` 는 `s06-latest` / `s06-earliest` / `s06-none` **세 개의 서로 다른 그룹**으로 붙습니다. 같은 그룹을 재사용하면 커밋된 오프셋이 생겨 `auto.offset.reset` 이 무시되므로 차이가 안 보입니다. 스크립트가 그룹 이름을 다르게 둔 이유입니다.
- `[6-2]` 의 `none` 구간은 **의도적으로 실패하는 명령**이라 `|| true` 로 감쌌습니다. `set -e` 가 걸려 있어서 이게 없으면 스크립트가 여기서 멈춥니다.
- `[6-4]` `[6-5]` 는 `docker cp Practice.java kafka-1:/tmp/` 로 파일을 넣은 뒤 `autocommit-loss` → `autocommit-loss-resume` → `autocommit-dup` → `autocommit-dup-resume` 순으로 **네 번 호출**합니다. `-resume` 시나리오를 건너뛰면 유실/중복 건수 집계가 출력되지 않습니다.
- `[6-7]` 의 `--reset-offsets` 는 `--dry-run` 을 먼저 보여주고 `--execute` 를 뒤에 둡니다. 활성 그룹 에러를 재현하는 구간은 `# [터미널 B]` 주석으로 표시했으며, 스크립트를 통째로 돌리면 그 부분은 **주석 처리된 채 건너뜁니다.** 직접 두 창에서 실행해야 에러를 볼 수 있습니다.
- 마지막 `[6-9]` 가 `s06-*` 그룹 9개와 `s06_orders` 토픽을 지웁니다. 중간에 멈췄다면 이 블록만 따로 실행하세요.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었고, 준비 데이터는 스크립트가 미리 만들어 줍니다.

- 파일 맨 앞의 `setup()` 함수가 `s06x_orders` 토픽(파티션 1개)을 만들고 메시지 **50건**을 넣습니다. 본문의 100건과 **일부러 다른 숫자**를 썼습니다. 본문 답을 그대로 옮겨 적으면 안 맞게 하려는 의도입니다.
- **문제 1·2·7** 은 관찰 문제로 명령만 채우면 되고, **문제 3·4·5** 는 `Practice.java` 를 인자를 바꿔 가며 돌려야 합니다. 문제 4 는 특히 `--consumer-property` 로 설정을 바꿔 가며 유실이 나지 않는 조합을 **찾는** 문제입니다.
- 문제 2 의 파티션 계산은 컨테이너 안에서 `java H.java <groupId>` 를 돌리도록 `H.java` 를 문제지 쪽에서 미리 생성해 둡니다. 로컬 JDK 가 없어도 풀 수 있습니다.
- ⚠️ **문제 6 은 터미널 2개가 필요합니다.** 한쪽에서 컨슈머를 띄워 그룹을 `Stable` 로 만들어 둔 상태에서 다른 쪽에서 리셋을 시도해야 `Assignments can only be reset if the group ... is inactive` 를 볼 수 있습니다. 한 창에서 순차 실행하면 이미 컨슈머가 종료된 뒤라 에러가 재현되지 않습니다.
- 문제 5 는 `+1` 실수를 재현하는 문제라 `Practice.java` 의 `manual-per-record` 를 **일부러 잘못 고쳐서** 돌리게 합니다. 고친 파일을 `PracticeBug.java` 로 저장하도록 안내하며, 원본은 건드리지 않습니다.
- 파일 끝의 `cleanup()` 은 `s06x_` 토픽과 `s06x-*` 그룹을 지웁니다. 문제를 다 못 풀었어도 이 함수는 실행하고 넘어가세요.

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답 명령과, "왜 그 답인가"를 설명하는 긴 `# 해설:` 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 의 핵심은 숫자 대비입니다. `latest` 그룹은 `Processed a total of 0 messages` + `LAG 0`, `earliest` 그룹은 `Processed a total of 50 messages` + `LAG 0`. **두 그룹의 LAG 이 똑같이 0 인데 처리량은 0 과 50** 이라는 점을 해설이 강조합니다.
- **정답 2** 는 `abs("s06x-a".hashCode()) % 50` 결과가 파티션 번호이며, `kafka-console-consumer.sh --topic __consumer_offsets --partition N` 으로 **그 파티션만 읽으면** 전체 50개를 훑지 않아도 된다는 것을 보여줍니다. 운영에서 특정 그룹의 커밋을 확인할 때 쓰는 실전 기법입니다.
- **정답 3** 은 "처리 건수는 애플리케이션 로그에서, 커밋 오프셋은 `--describe` 에서" 라는 **서로 다른 두 출처를 대조**하는 것이 답입니다. 한 곳만 봐서는 유실을 절대 발견할 수 없다는 것이 이 문제의 교훈입니다.
- **정답 4** 는 `max.poll.records=5` + 건당 50ms = 배치당 250ms 로 `auto.commit.interval.ms=5000` 안에 충분히 끝나게 만드는 조합을 제시합니다. 다만 해설이 곧바로 **"이건 처리 시간이 튀면 무너지는 미봉책"** 이라고 못 박고, 근본 해법은 `enable.auto.commit=false` 라고 결론냅니다.
- **정답 5** 는 `+1` 을 빼먹으면 `--describe` 의 LAG 이 **정확히 1** 로 고정된다는 것을 보여줍니다. "LAG 이 항상 1 인 그룹" 은 실무에서 이 버그의 진단 신호이며, 파티션이 3개면 LAG 이 3 으로 나온다는 것까지 설명합니다.
- **정답 6** 은 컨슈머 종료 → `--describe --state` 가 `Empty` 가 될 때까지 대기 → `--execute` 라는 3단계 절차입니다. `session.timeout.ms`(기본 45000) 때문에 **종료 직후에는 여전히 실패**할 수 있다는 점을 대기 루프로 처리합니다.
- **정답 7** 의 결론은 "`--reset-offsets --shift-by -30` 은 `__consumer_offsets` 를 즉시 바꾸므로 `--describe` 에 바로 반영되고, `seek()` 는 커밋하지 않으므로 `--describe` 는 그대로다" 입니다. seek 후 `commitSync()` 를 호출해야 비로소 같아진다는 것까지 확인합니다.

```bash file="./solution.sh"
```

### Practice.java

CLI 로는 재현할 수 없는 실습 — **처리와 커밋 사이의 타이밍** — 을 담은 단일 파일 Java 프로그램입니다. Java 21 의 single-file source 실행을 쓰므로 별도 빌드가 필요 없습니다.

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
```

- 시나리오는 첫 번째 인자로 고릅니다: `autocommit-loss`, `autocommit-loss-resume`, `autocommit-dup`, `autocommit-dup-resume`, `manual-sync`, `manual-async`, `manual-per-record`, `seek`. 인자 없이 실행하면 목록을 출력하고 종료합니다.
- `autocommit-loss` 와 `autocommit-dup` 은 **`Runtime.getRuntime().halt(1)`** 로 죽습니다. `System.exit()` 이 아닌 이유가 중요합니다. `System.exit()` 은 셧다운 훅을 실행하고 컨슈머를 정상 close 하면서 **오프셋을 커밋해 버리므로** 유실/중복이 재현되지 않습니다. `halt()` 는 `kill -9` 와 같아서 아무것도 정리하지 않습니다.
- `autocommit-loss` 는 `auto.commit.interval.ms=1000`, `max.poll.records=10`, 건당 `Thread.sleep(50)` 조합입니다. 이 셋의 곱이 유실 창의 크기를 결정하므로, 값을 바꿔 가며 유실 건수가 어떻게 변하는지 직접 실험해 보십시오.
- `autocommit-dup` 은 반대로 `auto.commit.interval.ms=30000` 으로 두어 **커밋이 거의 일어나지 않게** 만듭니다. 그래서 100건을 다 처리하고도 커밋은 40 에 머뭅니다.
- `-resume` 시나리오는 같은 `group.id` 로 다시 붙어 남은 것을 읽고, **1회차 처리 건수를 `/tmp/s06-count.txt` 에 기록해 둔 값과 합산**해서 유실/중복 건수를 출력합니다. 그래서 반드시 `-resume` 가 아닌 시나리오를 먼저 돌려야 합니다.
- `manual-per-record` 의 커밋 값은 `new OffsetAndMetadata(r.offset() + 1, "processed-by-worker-3")` 입니다. **`+1` 과 metadata 문자열**이 이 시나리오의 관찰 대상이며, 실행 후 `__consumer_offsets` 를 조회하면 그 문자열이 그대로 보입니다.
- `seek` 시나리오는 `consumer.poll(Duration.ofMillis(0))` 을 **먼저 한 번 호출**한 뒤 seek 합니다. 이 빈 poll 이 없으면 파티션이 아직 할당되지 않아 `IllegalStateException: No current assignment for partition s06_orders-0` 이 납니다. seek 관련 가장 흔한 실수입니다.
- 모든 시나리오가 `finally` 에서 `consumer.close(Duration.ofSeconds(5))` 를 호출하지만, `halt()` 로 죽는 두 시나리오만 예외적으로 그 경로에 도달하지 않습니다. 그게 이 실습의 전부입니다.

```java file="./Practice.java"
```
