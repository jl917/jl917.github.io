# Step 05 — 컨슈머와 컨슈머 그룹

> **학습 목표**
> - 컨슈머 그룹이 파티션을 나눠 갖는 방식과 그룹 코디네이터의 JoinGroup/SyncGroup 흐름을 설명한다
> - `kafka-consumer-groups.sh --describe` 의 **모든 컬럼을 해독**하고 랙을 읽는다
> - 파티션 3개에 컨슈머 4개를 붙여 **한 컨슈머가 아무것도 할당받지 못하는 것을 직접 확인한다**
> - 컨슈머를 죽여 리밸런싱을 **시간순으로 관찰하고**, 컨슈머 로그의 실제 메시지와 대응시킨다
> - 할당 전략 4종을 바꿔 가며 `--members` 결과를 비교하고, Cooperative 가 왜 다른지 이해한다
> - 처리가 느린 컨슈머가 `max.poll.interval.ms` 를 넘겨 **조용히 쫓겨나는 것을 재현한다**
>
> **선행 스텝**: Step 04 — 프로듀서
> **예상 소요**: 120분

---

## 5-0. 실습 준비

이 스텝 전용 토픽 두 개를 만들고 데이터를 채웁니다.

```bash
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
alias kcg='docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092'

kt --create --topic s05_orders  --partitions 3 --replication-factor 3
kt --create --topic s05_payments --partitions 3 --replication-factor 3
```

**결과**

```
Created topic s05_orders.
Created topic s05_payments.
```

각 토픽에 300건씩 넣습니다.

```bash
docker exec kafka-1 bash -c '
for i in $(seq 1 300); do
  k="C$(printf "%03d" $((RANDOM % 10 + 1)))"
  echo "$k:{\"order_id\":\"O-$((1000+i))\",\"customer_id\":\"$k\",\"amount\":$((RANDOM % 90000 + 1000)),\"status\":\"CREATED\"}"
done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
  --topic s05_orders --property parse.key=true --property key.separator=:'

docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s05_orders
```

**결과**

```
s05_orders:0:98
s05_orders:1:104
s05_orders:2:98
```

합계 300건입니다. 이 스텝의 실습은 대부분 **터미널 3~4개**를 씁니다. 미리 창을 여러 개 열어 두세요.

---

## 5-1. 컨슈머 그룹 — 파티션을 나눠 갖는 단위

컨슈머 하나가 토픽 전체를 읽는 것이 아닙니다. **같은 `group.id` 를 가진 컨슈머들이 파티션을 나눠 갖습니다.**

```
                 s05_orders  (파티션 3개)
        ┌──────────┬──────────┬──────────┐
        │    P0    │    P1    │    P2    │
        └────┬─────┴────┬─────┴────┬─────┘
             │          │          │
     ┌───────┴──┐  ┌────┴─────┐  ┌─┴────────┐
     │ consumer │  │ consumer │  │ consumer │   group.id = order-processor
     │    A     │  │    B     │  │    C     │
     └──────────┘  └──────────┘  └──────────┘

                          ↑ 같은 그룹 = 파티션을 "나눠" 갖는다 (경쟁 소비)


        ┌──────────┬──────────┬──────────┐
        │    P0    │    P1    │    P2    │
        └────┬─────┴────┬─────┴────┬─────┘
             └──────────┼──────────┘
                   ┌────┴─────┐
                   │ consumer │   group.id = audit-logger   ← 다른 그룹
                   └──────────┘

                          ↑ 다른 그룹 = 같은 메시지를 "각자" 다 받는다 (팬아웃)
```

이 두 성질이 Kafka 를 큐이면서 동시에 pub/sub 으로 만듭니다.

| 규칙 | 내용 |
|---|---|
| 한 파티션은 그룹 안에서 **정확히 한 컨슈머**에게만 할당 | 그래서 그룹 안에서는 메시지가 중복 처리되지 않습니다 |
| 한 컨슈머는 **여러 파티션**을 가질 수 있음 | 컨슈머가 파티션보다 적으면 자연스럽게 그렇게 됩니다 |
| **컨슈머 > 파티션이면 남는 컨슈머는 놉니다** | 5-6 의 함정입니다 |
| 그룹이 다르면 오프셋도 별개 | `__consumer_offsets` 에 `(group, topic, partition)` 키로 저장됩니다 |

> 💡 **비유 — 컨베이어 벨트와 작업자**
> 파티션은 컨베이어 벨트, 컨슈머는 작업자입니다. 벨트 하나에는 작업자가 한 명만 붙습니다.
> 벨트가 3개인데 작업자를 4명 세우면, 네 번째 사람은 팔짱을 끼고 서 있습니다. **아무도 이상하다고 말해 주지 않습니다.**

---

## 5-2. 그룹 코디네이터 — JoinGroup / SyncGroup

컨슈머가 그룹에 들어가는 과정은 생각보다 정교합니다. 브로커 하나가 그 그룹의 **코디네이터** 역할을 맡습니다.

```
  컨슈머 A                    코디네이터 브로커                컨슈머 B, C
     │                              │                            │
     │─ FindCoordinator ───────────►│                            │
     │◄──── "브로커 2 가 이 그룹의 코디네이터" ─┤                     │
     │                              │                            │
     │─ JoinGroup ─────────────────►│◄──────── JoinGroup ────────┤
     │   (지원 가능한 할당 전략 목록)   │                            │
     │                              │  ① 멤버를 다 모을 때까지 대기
     │                              │     (rebalance.timeout.ms
     │                              │      = max.poll.interval.ms)
     │                              │  ② 첫 번째 멤버를 리더로 지정
     │                              │
     │◄─ JoinGroupResponse ─────────┤──────────────────────────►│
     │   "당신이 리더. 멤버 목록은 A,B,C"│    "당신은 팔로워"           │
     │                              │                            │
     │  ③ 리더가 할당 전략을 실행해서   │                            │
     │     파티션 배분을 계산           │                            │
     │                              │                            │
     │─ SyncGroup (배분 결과) ──────►│◄──────── SyncGroup ────────┤
     │                              │           (빈 요청)          │
     │◄─ SyncGroupResponse ─────────┤──────────────────────────►│
     │   "A 는 P0"                   │              "B 는 P1, C 는 P2"│
     │                              │                            │
     │═ Heartbeat (3초마다) ════════►│◄════════ Heartbeat ════════┤
```

여기서 놓치기 쉬운 두 가지를 짚습니다.

1. **파티션 할당을 계산하는 것은 브로커가 아니라 "그룹 리더 컨슈머"입니다.** 코디네이터는 멤버 목록을 모아 리더에게 넘기고, 리더가 계산한 결과를 다시 뿌릴 뿐입니다. 그래서 `partition.assignment.strategy` 는 **클라이언트 설정**입니다.
2. **`group.initial.rebalance.delay.ms`** 만큼 코디네이터가 일부러 기다립니다. 브로커 기본값은 3000ms 인데, 여러 컨슈머가 동시에 뜨는 배포 상황에서 리밸런싱이 N번 연속으로 일어나는 것을 막기 위해서입니다. **이 코스의 클러스터는 학습 편의를 위해 0 으로 낮춰 두었습니다.**

코디네이터가 누구인지는 CLI 로 확인할 수 있습니다.

```bash
kcg --describe --group s05-demo --state
```

**결과** (아직 그룹이 없으므로)

```
Consumer group 's05-demo' does not exist.
```

---

## 5-3. `poll()` 루프의 실제 동작

컨슈머 코드는 거의 항상 이 모양입니다.

```java
consumer.subscribe(List.of("s05_orders"));
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
    for (ConsumerRecord<String, String> r : records) {
        process(r);
    }
    consumer.commitSync();
}
```

`poll()` 이 "브로커에 가서 메시지를 받아 온다"고 이해하면 절반만 맞습니다. 실제로는 이렇습니다.

```
  ┌─ poll(1000) 호출 ───────────────────────────────────────────┐
  │                                                             │
  │  ① 코디네이터 상태 확인 (리밸런싱 필요? 오프셋 커밋할 것?)      │
  │  ② 내부 fetch 버퍼에 이미 데이터가 있으면 → 즉시 반환          │
  │  ③ 없으면 Fetcher 가 브로커에 FetchRequest 를 보냄            │
  │       fetch.min.bytes (1) 만큼 모일 때까지 브로커가 대기       │
  │       fetch.max.wait.ms (500) 를 넘으면 있는 만큼 반환         │
  │  ④ 응답이 내부 버퍼에 통째로 들어감                            │
  │  ⑤ 그중 max.poll.records (500) 개만 꺼내서 반환                │
  │                                                             │
  └─────────────────────────────────────────────────────────────┘

  내부 버퍼:  [ 2000건 수신됨 ]
                ↓ poll() 1회차 → 500건 반환   (네트워크 호출 없음)
                ↓ poll() 2회차 → 500건 반환   (네트워크 호출 없음)
                ↓ poll() 3회차 → 500건 반환   (네트워크 호출 없음)
                ↓ poll() 4회차 → 500건 반환   (네트워크 호출 없음)
                ↓ poll() 5회차 → 버퍼가 비었으므로 FetchRequest 발생
```

**`poll()` 호출 수와 네트워크 요청 수는 같지 않습니다.** 한 번의 fetch 로 받아 온 데이터를 여러 번의 `poll()` 이 나눠서 꺼내 갑니다.

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `max.poll.records` | 500 | **한 번의 `poll()` 이 반환할 최대 레코드 수** |
| `fetch.min.bytes` | 1 | 브로커가 응답을 만들기 위해 모을 최소 바이트 |
| `fetch.max.wait.ms` | 500 | `fetch.min.bytes` 가 안 차도 이만큼 지나면 응답 |
| `max.partition.fetch.bytes` | 1 MiB | 파티션당 한 번에 받을 최대 바이트 |
| `fetch.max.bytes` | 55 MiB | 요청 하나가 받을 최대 바이트 |

> 💡 **`max.poll.records` 는 성능이 아니라 "안전"을 위한 값입니다**
> 500건을 받아서 건당 20ms 씩 처리하면 한 루프가 **10초**입니다. 여기까지는 괜찮습니다.
> 그런데 건당 700ms 가 걸리는 처리(외부 API 호출 등)라면 한 루프가 **350초**가 되고,
> `max.poll.interval.ms`(기본 300초)를 넘겨 그룹에서 쫓겨납니다(5-10).
> **처리가 느리면 `max.poll.records` 를 먼저 줄이십시오.** 100 이나 50 으로요.

---

## 5-4. 컨슈머 그룹 만들고 랙 읽기

터미널 A 에서 컨슈머 하나를 띄웁니다.

```bash
# [터미널 A]
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s05_orders \
  --group s05-demo --from-beginning --max-messages 120
```

120건만 읽고 멈춥니다. 터미널 B 에서 그룹 상태를 봅니다.

```bash
# [터미널 B]
kcg --describe --group s05-demo
```

**결과**

```
Consumer group 's05-demo' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s05-demo        s05_orders      0          40              98              58              -               -               -
s05-demo        s05_orders      1          41              104             63              -               -               -
s05-demo        s05_orders      2          39              98              59              -               -               -
```

컨슈머가 이미 종료됐으므로 `CONSUMER-ID` / `HOST` / `CLIENT-ID` 가 `-` 이고, 맨 위에 `has no active members.` 가 붙습니다.

### 컬럼 완전 해독

| 컬럼 | 의미 | 어떻게 읽습니까 |
|---|---|---|
| `GROUP` | `group.id` | — |
| `TOPIC` | 구독 중인 토픽 | 여러 토픽을 구독하면 토픽마다 줄이 나옵니다 |
| `PARTITION` | 파티션 번호 | 파티션마다 한 줄 |
| `CURRENT-OFFSET` | **그룹이 커밋한 오프셋** — 다음에 읽을 위치 | 커밋한 적이 없으면 `-` |
| `LOG-END-OFFSET` | 파티션의 **마지막 오프셋 + 1** (HW) | 프로듀서가 넣은 총 건수와 같습니다 |
| `LAG` | `LOG-END-OFFSET - CURRENT-OFFSET` | **아직 처리하지 못한 건수** |
| `CONSUMER-ID` | 현재 이 파티션을 맡은 컨슈머의 고유 ID | 형식: `<client.id>-<UUID>` |
| `HOST` | 그 컨슈머의 IP | `/172.18.0.5` 처럼 슬래시가 붙습니다 |
| `CLIENT-ID` | `client.id` 설정값 | 안 주면 `consumer-<group.id>-<번호>` |

**`LAG` 이 이 표에서 가장 중요합니다.** 운영 알림의 대부분이 이 값에 걸립니다.

- `LAG` 이 0 근처에서 유지 → 정상
- `LAG` 이 **계속 증가** → 컨슈머가 프로듀서를 못 따라감. 컨슈머를 늘리거나 처리를 빠르게
- **한 파티션의 `LAG` 만** 큼 → Step 04 의 hot partition 이거나, 그 파티션을 맡은 컨슈머만 느림
- `LAG` 이 갑자기 급증 후 유지 → 컨슈머가 죽었거나 리밸런싱에 갇혔음

이제 컨슈머를 다시 띄워 끝까지 읽게 합니다.

```bash
# [터미널 A]
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s05_orders \
  --group s05-demo --timeout-ms 10000 > /dev/null
```

**결과**

```
[2024-03-11 10:31:44,821] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.common.errors.TimeoutException
Processed a total of 180 messages
```

`TimeoutException` 은 "10초 동안 새 메시지가 없어서 종료"라는 뜻입니다. 에러처럼 보이지만 `--timeout-ms` 를 준 결과이므로 정상입니다.

```bash
kcg --describe --group s05-demo
```

**결과**

```
Consumer group 's05-demo' has no active members.

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
s05-demo        s05_orders      0          98              98              0               -               -               -
s05-demo        s05_orders      1          104             104             0               -               -               -
s05-demo        s05_orders      2          98              98              0               -               -               -
```

`LAG` 이 전부 0 입니다. 다 따라잡았습니다.

> ⚠️ **함정 — `--from-beginning` 은 그룹에 커밋된 오프셋이 없을 때만 동작합니다**
> 위에서 두 번째 실행에는 `--from-beginning` 을 주지 않았는데도 40번 오프셋부터 이어 읽었습니다.
> 반대로 **`--from-beginning` 을 줘도** 커밋된 오프셋이 있으면 그것이 우선입니다. 처음부터가 아니라 이어서 읽습니다.
> "분명 `--from-beginning` 을 줬는데 예전 메시지가 안 나온다"의 원인이 이것입니다.
> **해결**: 그룹을 지우거나(`kcg --delete --group X`) 오프셋을 리셋합니다(`--reset-offsets`, Step 06).

---

## 5-5. `--list` / `--members` / `--state`

`kafka-consumer-groups.sh` 는 세 가지 관점을 제공합니다. 셋 다 알아 두면 장애 대응 속도가 달라집니다.

컨슈머 셋을 띄워 놓고 봅니다. 각각 다른 터미널입니다.

```bash
# [터미널 A] [터미널 B] [터미널 C] — 셋 다 실행
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s05_orders --group s05-demo
```

### `--list` — 어떤 그룹이 있는가

```bash
kcg --list
```

**결과**

```
s05-demo
console-consumer-40317
```

`console-consumer-40317` 처럼 무작위 숫자가 붙은 그룹은 **`--group` 없이 콘솔 컨슈머를 돌렸을 때** 자동 생성된 것입니다. 실습 중에 계속 쌓이므로 주기적으로 지우세요.

### `--describe --members` — 누가 무엇을 맡고 있는가

```bash
kcg --describe --group s05-demo --members
```

**결과**

```
GROUP           CONSUMER-ID                                                    HOST            CLIENT-ID                 #PARTITIONS
s05-demo        console-consumer-9f3c1e2a-4b7d-4a11-8e02-1c5f3a9d7e21          /172.18.0.4     console-consumer          1
s05-demo        console-consumer-4b7d0a11-2e91-4c33-b6a7-8f21c0d4e5b9          /172.18.0.4     console-consumer          1
s05-demo        console-consumer-c81f27de-6a04-4f52-9d18-3b6e0a1c7f44          /172.18.0.4     console-consumer          1
```

### `--describe --members --verbose` — 정확히 어느 파티션인가

```bash
kcg --describe --group s05-demo --members --verbose
```

**결과**

```
GROUP           CONSUMER-ID                                                    HOST            CLIENT-ID                 #PARTITIONS     ASSIGNMENT
s05-demo        console-consumer-9f3c1e2a-4b7d-4a11-8e02-1c5f3a9d7e21          /172.18.0.4     console-consumer          1               s05_orders(0)
s05-demo        console-consumer-4b7d0a11-2e91-4c33-b6a7-8f21c0d4e5b9          /172.18.0.4     console-consumer          1               s05_orders(1)
s05-demo        console-consumer-c81f27de-6a04-4f52-9d18-3b6e0a1c7f44          /172.18.0.4     console-consumer          1               s05_orders(2)
```

**`ASSIGNMENT` 컬럼이 이 스텝 전체에서 가장 자주 보게 될 컬럼입니다.** 파티션 3개가 컨슈머 3개에게 하나씩 갔습니다.

### `--describe --state` — 그룹이 지금 무슨 상태인가

```bash
kcg --describe --group s05-demo --state
```

**결과**

```
GROUP           COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE           #MEMBERS
s05-demo        kafka-2:9092 (2)          range                Stable          3
```

| 컬럼 | 의미 |
|---|---|
| `COORDINATOR (ID)` | 이 그룹을 관리하는 브로커. `__consumer_offsets` 의 해당 파티션 리더입니다 |
| `ASSIGNMENT-STRATEGY` | 실제로 선택된 할당 전략. 기본값은 `range` |
| `STATE` | `Empty` / `PreparingRebalance` / `CompletingRebalance` / `Stable` / `Dead` |
| `#MEMBERS` | 현재 활성 멤버 수 |

**`STATE` 가 `PreparingRebalance` 에서 안 벗어나면 그룹 전체가 멈춰 있는 것입니다.** 이 상태가 몇 분씩 지속되면 5-10 의 함정을 의심하세요.

---

## 5-6. ⚠️ 핵심 함정 A — 컨슈머 수 > 파티션 수이면 남는 컨슈머는 논다

"처리량이 부족하니 컨슈머를 늘리자"는 자연스러운 대응입니다. 그런데 **파티션 수를 넘어서면 아무 효과가 없습니다.**

파티션 3개짜리 `s05_orders` 에 컨슈머를 **4개** 붙입니다. 터미널 A~D 를 각각 엽니다.

```bash
# [터미널 A] [터미널 B] [터미널 C] [터미널 D] — 넷 다 실행
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s05_orders --group s05-four
```

```bash
kcg --describe --group s05-four --members --verbose
```

**결과**

```
GROUP           CONSUMER-ID                                                    HOST            CLIENT-ID                 #PARTITIONS     ASSIGNMENT
s05-four        console-consumer-1a2b3c4d-8e5f-4a90-b712-6c3d0e9f8a51          /172.18.0.4     console-consumer          1               s05_orders(0)
s05-four        console-consumer-5e6f7a8b-9c0d-4e12-a834-7b1f2c8d5e60          /172.18.0.4     console-consumer          1               s05_orders(1)
s05-four        console-consumer-9c0d1e2f-3a4b-4c56-9d78-2e5a8b1f0c33          /172.18.0.4     console-consumer          1               s05_orders(2)
s05-four        console-consumer-d4e5f6a7-b8c9-4d01-8e23-4f7a1b6c9d22          /172.18.0.4     console-consumer          0
```

**네 번째 컨슈머의 `#PARTITIONS` 가 `0` 이고 `ASSIGNMENT` 가 비어 있습니다.**

이 컨슈머는 살아 있습니다. 하트비트도 보내고 있고, `poll()` 도 돌고 있고, 로그에 에러도 없습니다. 다만 **아무것도 받지 못합니다.**

`--describe` 로 봐도 이상한 점이 없습니다.

```bash
kcg --describe --group s05-four
```

**결과**

```
GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                             HOST            CLIENT-ID
s05-four        s05_orders      0          98              98              0               console-consumer-1a2b3c4d-8e5f-4a90-b712-6c3d0e9f8a51   /172.18.0.4     console-consumer
s05-four        s05_orders      1          104             104             0               console-consumer-5e6f7a8b-9c0d-4e12-a834-7b1f2c8d5e60   /172.18.0.4     console-consumer
s05-four        s05_orders      2          98              98              0               console-consumer-9c0d1e2f-3a4b-4c56-9d78-2e5a8b1f0c33   /172.18.0.4     console-consumer
```

**줄이 3개뿐입니다.** 네 번째 컨슈머는 이 화면에 아예 나오지 않습니다. `--members` 를 봐야만 존재를 알 수 있습니다.

> ⚠️ **함정 A — 컨슈머를 늘려도 처리량이 안 늘고, 에러도 안 납니다**
> 파티션이 병렬성의 상한입니다. 파티션 3개면 최대 동시 처리 단위는 3 입니다.
> 컨슈머를 10개 띄워도 7개는 유휴 상태로 리소스만 먹습니다. 심지어 **리밸런싱 비용은 10개분으로 늘어납니다.**
> 증상은 "인스턴스를 2배로 늘렸는데 랙이 그대로다" 입니다.
> **해결 3가지**
> 1. **파티션을 늘립니다.** `kt --alter --topic s05_orders --partitions 6`.
>    단, **키 기반 라우팅이 그 순간 깨집니다**(Step 03). 같은 `customer_id` 가 다른 파티션으로 갑니다.
> 2. **컨슈머 내부에서 병렬화합니다.** `poll()` 로 받은 레코드를 스레드 풀로 넘기는 방식입니다.
>    오프셋 커밋이 훨씬 까다로워집니다(Step 06 에서 다룹니다).
> 3. **처리 자체를 빠르게 합니다.** 대개 이게 정답입니다. 건당 처리가 500ms 걸리는 원인을 찾는 게
>    컨슈머를 늘리는 것보다 효과가 큽니다.
>
> **유휴 컨슈머가 완전히 무의미하지는 않습니다.** 다른 컨슈머가 죽으면 즉시 그 파티션을 이어받는
> **핫 스탠바이** 역할을 합니다. 의도한 것이라면 괜찮지만, "처리량을 늘리려고" 띄웠다면 잘못된 것입니다.

> 💡 **실무 팁 — 컨슈머 수는 파티션 수의 약수로 두십시오**
> 파티션 6개에 컨슈머 4개면 할당이 `2,2,1,1` 로 불균등해집니다.
> 6개에 컨슈머 3개(각 2개) 또는 6개(각 1개)면 완전히 균등합니다.
> 오토스케일링을 쓴다면 **파티션 수를 12나 24처럼 약수가 많은 수**로 잡아 두면 스케일 단위가 자유로워집니다.

---

## 5-7. 리밸런싱 관찰 — 컨슈머 하나를 죽인다

이제 터미널 A 의 컨슈머를 `Ctrl+C` 로 죽이고, 그 사이 `--describe` 를 반복 실행합니다.

**터미널 E** 에서 1초마다 상태를 찍는 루프를 돌려 두세요.

```bash
# [터미널 E]
while true; do
  date '+%H:%M:%S'
  docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka-1:9092 --describe --group s05-four --members --verbose \
    2>/dev/null | tail -n +2
  echo '---'
  sleep 1
done
```

그리고 터미널 A 를 `Ctrl+C` 합니다.

**결과** (시간순)

```
10:42:11
s05-four  console-consumer-1a2b...  /172.18.0.4  console-consumer  1  s05_orders(0)
s05-four  console-consumer-5e6f...  /172.18.0.4  console-consumer  1  s05_orders(1)
s05-four  console-consumer-9c0d...  /172.18.0.4  console-consumer  1  s05_orders(2)
s05-four  console-consumer-d4e5...  /172.18.0.4  console-consumer  0
---
10:42:12                                         ← 여기서 터미널 A 를 Ctrl+C
Warning: Consumer group 's05-four' is rebalancing.
---
10:42:13
Warning: Consumer group 's05-four' is rebalancing.
---
10:42:14
s05-four  console-consumer-5e6f...  /172.18.0.4  console-consumer  1  s05_orders(0)
s05-four  console-consumer-9c0d...  /172.18.0.4  console-consumer  1  s05_orders(1)
s05-four  console-consumer-d4e5...  /172.18.0.4  console-consumer  1  s05_orders(2)
---
```

**2초 만에 재배분이 끝났습니다.** 그리고 결정적으로, **놀고 있던 네 번째 컨슈머(`d4e5...`)가 파티션 2를 받았습니다.**

`Ctrl+C` 로 정상 종료하면 컨슈머가 `LeaveGroup` 을 보내므로 즉시 리밸런싱이 시작됩니다. 프로세스를 `kill -9` 로 강제 종료하면 코디네이터가 하트비트가 끊긴 것을 감지할 때까지(`session.timeout.ms`, 기본 45초) 기다립니다.

### 컨슈머 로그에서 벌어지는 일

살아남은 컨슈머(터미널 B)의 로그에 이런 줄이 찍힙니다. `--consumer-property` 로 로그 레벨을 올려야 보입니다.

```
[2024-03-11 10:42:12,104] INFO [Consumer clientId=console-consumer, groupId=s05-four] Attempt to heartbeat failed since group is rebalancing (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 10:42:12,107] INFO [Consumer clientId=console-consumer, groupId=s05-four] Revoke previously assigned partitions s05_orders-1 (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 10:42:12,108] INFO [Consumer clientId=console-consumer, groupId=s05-four] (Re-)joining group (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 10:42:13,995] INFO [Consumer clientId=console-consumer, groupId=s05-four] Successfully joined group with generation Generation{generationId=4, memberId='console-consumer-5e6f7a8b-9c0d-4e12-a834-7b1f2c8d5e60', protocol='range'} (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 10:42:14,001] INFO [Consumer clientId=console-consumer, groupId=s05-four] Notifying assignor about the new Assignment(partitions=[s05_orders-0]) (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 10:42:14,003] INFO [Consumer clientId=console-consumer, groupId=s05-four] Adding newly assigned partitions: s05_orders-0 (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
```

한 줄씩 해독합니다.

| 로그 | 의미 |
|---|---|
| `Attempt to heartbeat failed since group is rebalancing` | 하트비트 응답에 "리밸런싱 중" 플래그가 왔습니다. **리밸런싱을 알아채는 첫 신호**입니다 |
| `Revoke previously assigned partitions s05_orders-1` | **자기가 갖고 있던 파티션을 내놓습니다.** 이 시점부터 이 컨슈머는 아무것도 처리하지 않습니다 |
| `(Re-)joining group` | JoinGroup 요청을 보냅니다 |
| `Successfully joined group with generation Generation{generationId=4, ...}` | **generationId** 는 리밸런싱이 일어날 때마다 1씩 증가합니다. 이 값이 자주 오르면 리밸런싱 폭풍입니다 |
| `Notifying assignor about the new Assignment(partitions=[s05_orders-0])` | SyncGroup 응답으로 받은 새 할당 |
| `Adding newly assigned partitions: s05_orders-0` | 실제로 그 파티션 소비를 시작합니다 |

> 💡 **`generationId` 를 모니터링 지표로 쓰십시오**
> 정상 운영에서 `generationId` 는 배포할 때만 올라갑니다. 하루에 수십 번 오른다면
> 컨슈머가 계속 쫓겨나고 재합류하는 중이며, 그동안 처리는 사실상 멈춰 있습니다.
> JMX 로는 `kafka.consumer:type=consumer-coordinator-metrics,client-id=*` 의
> `rebalance-rate-per-hour`, `failed-rebalance-rate-per-hour` 를 보면 됩니다(Step 14).

---

## 5-8. 할당 전략 4종

`partition.assignment.strategy` 는 **컨슈머 설정**입니다(5-2 에서 본 대로 할당 계산은 리더 컨슈머가 합니다).

| 전략 | 클래스 | 방식 | 리밸런싱 | 특징 |
|---|---|---|---|---|
| **Range** (기본) | `RangeAssignor` | 토픽마다 파티션을 정렬해 컨슈머 수로 나눠 **연속 구간**을 배분 | Eager | **토픽마다 앞쪽 컨슈머에 쏠림** |
| Round Robin | `RoundRobinAssignor` | 모든 토픽의 파티션을 한 줄로 세워 돌아가며 배분 | Eager | 균등하지만 리밸런싱 때 전부 재배치 |
| Sticky | `StickyAssignor` | 균등하게 배분하되 **기존 할당을 최대한 유지** | Eager | 재배치 최소화 |
| **Cooperative Sticky** | `CooperativeStickyAssignor` | Sticky + **점진적 리밸런싱** | **Cooperative** | 영향받는 파티션만 멈춤 |

### RangeAssignor 의 쏠림 문제

Range 는 **토픽별로 독립적으로** 계산합니다. 토픽 두 개(각 3파티션)를 컨슈머 2개가 구독하면 이렇게 됩니다.

```
  s05_orders   P0 P1 P2      컨슈머 2개 → 3/2 = 1 나머지 1
  s05_payments P0 P1 P2      → 앞쪽 컨슈머가 2개, 뒤쪽이 1개

  ┌────────────┬──────────────────────────────────────┐
  │ consumer A │ s05_orders(0,1)   s05_payments(0,1)  │ ← 4개
  │ consumer B │ s05_orders(2)     s05_payments(2)    │ ← 2개
  └────────────┴──────────────────────────────────────┘
                                     토픽이 많을수록 격차가 벌어집니다.
```

직접 확인합니다. 두 토픽을 함께 구독하는 컨슈머 2개를 띄웁니다.

```bash
# [터미널 A] [터미널 B]
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --include 's05_(orders|payments)' \
  --group s05-range
```

```bash
kcg --describe --group s05-range --members --verbose
```

**결과**

```
GROUP           CONSUMER-ID                                            HOST            CLIENT-ID           #PARTITIONS     ASSIGNMENT
s05-range       console-consumer-2f8a1b3c-...                          /172.18.0.4     console-consumer    4               s05_orders(0,1), s05_payments(0,1)
s05-range       console-consumer-7d4e9c0a-...                          /172.18.0.4     console-consumer    2               s05_orders(2), s05_payments(2)
```

**4 대 2 입니다.** 컨슈머 A 가 두 배로 일합니다. 토픽이 10개면 20 대 10 이 됩니다.

RoundRobin 으로 바꾸면 어떻게 되는지 봅니다.

```bash
# [터미널 A] [터미널 B] — 기존 컨슈머를 죽이고 다시
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --include 's05_(orders|payments)' \
  --group s05-rr \
  --consumer-property partition.assignment.strategy=org.apache.kafka.clients.consumer.RoundRobinAssignor
```

```bash
kcg --describe --group s05-rr --members --verbose
```

**결과**

```
GROUP           CONSUMER-ID                                            HOST            CLIENT-ID           #PARTITIONS     ASSIGNMENT
s05-rr          console-consumer-a1b2c3d4-...                          /172.18.0.4     console-consumer    3               s05_orders(0,2), s05_payments(1)
s05-rr          console-consumer-e5f6a7b8-...                          /172.18.0.4     console-consumer    3               s05_orders(1), s05_payments(0,2)
```

**3 대 3.** 완전히 균등합니다.

```bash
kcg --describe --group s05-rr --state
```

**결과**

```
GROUP           COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE           #MEMBERS
s05-rr          kafka-3:9092 (3)          roundrobin           Stable          2
```

> ⚠️ **함정 — 그룹 안의 컨슈머들이 서로 다른 전략을 설정하면**
> 코디네이터는 **모든 멤버가 공통으로 지원하는 전략** 중 하나를 고릅니다.
> 겹치는 것이 하나도 없으면 그룹이 아예 구성되지 않고 이렇게 죽습니다.
> ```
> org.apache.kafka.common.errors.InconsistentGroupProtocolException: The group member's
>   supported protocols are incompatible with those of existing members.
> ```
> **해결**: 전략을 바꾸는 배포는 **두 단계 롤링**으로 합니다.
> ① 새 전략을 목록의 **두 번째**로 추가해서 전체 배포 (`range,roundrobin`)
> ② 첫 번째만 남기고 다시 배포 (`roundrobin`)
> 다음 절의 Cooperative 마이그레이션과 정확히 같은 절차입니다.

---

## 5-9. ⚠️ 핵심 함정 B — Eager 리밸런싱은 stop-the-world

Range / RoundRobin / Sticky 는 전부 **Eager 프로토콜**입니다. Eager 의 동작은 단순하고 잔인합니다.

```
  Eager (기본 — Range/RoundRobin/Sticky)

  시각 ──────────────────────────────────────────────────────────►
       │                                                    │
   컨슈머 A  [P0 처리중]──✂ 전부 반납 ──[      멈춤      ]──[P0 처리중]
   컨슈머 B  [P1 처리중]──✂ 전부 반납 ──[      멈춤      ]──[P1 처리중]
   컨슈머 C  [P2 처리중]──✂ 전부 반납 ──[      멈춤      ]──[P2 처리중]
   컨슈머 D  ✱ 새로 합류                 [      대기      ]──[P3 처리중]
                          ▲                                ▲
                    리밸런싱 시작                       할당 완료
                          └──── 이 구간 동안 그룹 전체가 아무것도 처리하지 않습니다 ────┘


  Cooperative (CooperativeStickyAssignor)

   컨슈머 A  [P0 처리중 ────────────────────────────────────────────]  ← 안 멈춤
   컨슈머 B  [P1 처리중 ────────────────────────────────────────────]  ← 안 멈춤
   컨슈머 C  [P2 처리중]──✂ P2 만 반납 ──[짧게 멈춤]                    ← P2 만 영향
   컨슈머 D  ✱ 새로 합류                 [대기]──────[P2 처리중]
```

Eager 는 **모든 컨슈머가 모든 파티션을 반납한 뒤** 다시 배분합니다. 컨슈머 하나가 추가되거나 제거될 뿐인데 **전원이 멈춥니다.**

멈추는 시간은 대략 `가장 느린 컨슈머의 rebalance.timeout.ms` 까지입니다. `rebalance.timeout.ms` 의 기본값은 `max.poll.interval.ms`(300초)와 같습니다. 컨슈머 하나가 긴 처리 중이면 **나머지 전원이 최대 5분을 기다립니다.**

| 항목 | Eager | Cooperative (Incremental) |
|---|---|---|
| 반납 범위 | **전체 파티션** | 이동해야 하는 파티션만 |
| 멈추는 컨슈머 | **전원** | 해당 파티션을 잃는 컨슈머만 |
| 리밸런싱 라운드 | 1회 | **2회** (반납 라운드 + 할당 라운드) |
| 도입 | 초기부터 | 2.4 (KIP-429), 3.x 에서 성숙 |
| 대규모 그룹 | 컨슈머 100개면 매 배포마다 전체 정지 | 영향 최소 |

Cooperative 로 바꿉니다.

```bash
# [터미널 A] [터미널 B] [터미널 C]
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s05_orders --group s05-coop \
  --consumer-property partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

```bash
kcg --describe --group s05-coop --state
```

**결과**

```
GROUP           COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE           #MEMBERS
s05-coop        kafka-2:9092 (2)          cooperative-sticky   Stable          3
```

터미널 C 를 죽이고 로그를 보면 Eager 와 다른 줄이 찍힙니다.

```
[2024-03-11 11:03:22,417] INFO [Consumer clientId=console-consumer, groupId=s05-coop] Request joining group due to: group is already rebalancing (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 11:03:22,419] INFO [Consumer clientId=console-consumer, groupId=s05-coop] Successfully joined group with generation Generation{generationId=3, memberId='console-consumer-...', protocol='cooperative-sticky'} (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 11:03:22,423] INFO [Consumer clientId=console-consumer, groupId=s05-coop] Updating assignment with
	Assigned partitions:                       [s05_orders-0, s05_orders-2]
	Current owned partitions:                  [s05_orders-0]
	Added partitions (assigned - owned):       [s05_orders-2]
	Revoked partitions (owned - assigned):     []
 (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 11:03:22,425] INFO [Consumer clientId=console-consumer, groupId=s05-coop] Adding newly assigned partitions: s05_orders-2 (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
```

**`Revoked partitions: []` 가 핵심입니다.** 이 컨슈머는 `s05_orders-0` 을 계속 처리하면서 `s05_orders-2` 를 **추가로** 받았습니다. Eager 였다면 `Revoke previously assigned partitions s05_orders-0` 이 먼저 찍히고 처리가 끊겼을 것입니다.

> ⚠️ **함정 B — Cooperative 로 바꾸려면 반드시 2단계 롤링이 필요합니다**
> Eager 와 Cooperative 는 **프로토콜 자체가 다릅니다.** 그룹 안에 두 종류가 섞이면 리밸런싱이 실패합니다.
> 한 번에 바꾸면 배포 도중 그룹이 이 상태에 빠집니다.
> ```
> org.apache.kafka.common.errors.InconsistentGroupProtocolException
> ```
> **올바른 절차 (KIP-429 권장)**
> ```properties
> # 1단계 배포 — 두 전략을 모두 지원. 실제 선택은 여전히 cooperative 가 아닌 쪽
> partition.assignment.strategy=\
>   org.apache.kafka.clients.consumer.CooperativeStickyAssignor,\
>   org.apache.kafka.clients.consumer.RangeAssignor
>
> # (전체 인스턴스가 1단계로 교체된 것을 --state 로 확인한 뒤)
>
> # 2단계 배포 — cooperative 만 남김
> partition.assignment.strategy=\
>   org.apache.kafka.clients.consumer.CooperativeStickyAssignor
> ```
> 1단계에서는 모든 멤버의 공통 전략이 `range` 이므로 여전히 Eager 로 동작합니다.
> 2단계 배포가 시작되면 공통 전략이 `cooperative-sticky` 로 바뀌면서 전환됩니다.
> **`--state` 의 `ASSIGNMENT-STRATEGY` 컬럼으로 각 단계를 확인하며 진행하십시오.**

> 💡 Cooperative 를 쓰면 `ConsumerRebalanceListener` 의 `onPartitionsRevoked()` 가
> **잃는 파티션에 대해서만** 호출되고, 새로 `onPartitionsLost()` 콜백이 추가됩니다.
> 리스너에서 오프셋을 커밋하는 코드가 있다면 Cooperative 전환 시 반드시 함께 점검해야 합니다(Step 06).

---

## 5-10. ⚠️ 핵심 함정 C — `max.poll.interval.ms` 초과로 조용히 쫓겨남

가장 자주 만나고 가장 원인 찾기 어려운 함정입니다.

컨슈머는 **두 개의 시계**로 감시받습니다.

- **하트비트 스레드**: 백그라운드 스레드가 `heartbeat.interval.ms`(3초)마다 코디네이터에 신호를 보냅니다. `session.timeout.ms`(45초) 동안 신호가 없으면 **죽은 것으로 판정**됩니다.
- **처리 스레드**: `poll()` 을 호출하는 스레드입니다. `poll()` 간격이 `max.poll.interval.ms`(**300초 = 5분**)를 넘으면 **살아 있어도 쫓겨납니다.**

두 번째가 함정입니다. 하트비트는 별도 스레드가 보내므로 **처리가 아무리 오래 걸려도 하트비트는 정상**입니다. 코디네이터 입장에서 이 컨슈머는 멀쩡히 살아 있습니다. 그런데 `poll()` 을 안 부르니 쫓아냅니다.

`Practice.java` 의 `slow-consumer` 시나리오가 이것을 재현합니다. `max.poll.interval.ms` 를 10초로 줄이고, 레코드마다 4초씩 자게 만들었습니다.

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java slow-consumer'
```

**결과**

```
[slow-consumer] group=s05-slow, max.poll.interval.ms=10000, max.poll.records=5
[slow-consumer] 레코드마다 4000ms 씩 처리합니다. 5건 × 4초 = 20초 > 10초 → 쫓겨납니다.

  poll() → 5건 수신 (s05_orders-0)
    처리 O-1001 ... (4000ms)
    처리 O-1002 ... (4000ms)
    처리 O-1003 ... (4000ms)
[2024-03-11 11:21:38,904] WARN [Consumer clientId=s05-slow-1, groupId=s05-slow] consumer poll timeout has expired. This means the time between subsequent calls to poll() was longer than the configured max.poll.interval.ms, which typically implies that the poll loop is spending too much time processing messages. You can address this either by increasing max.poll.interval.ms or by reducing the maximum size of batches returned in poll() with max.poll.records. (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
[2024-03-11 11:21:38,906] INFO [Consumer clientId=s05-slow-1, groupId=s05-slow] Member s05-slow-1-8c2f0d19-4a63-41e7-9b52-7e0a1f3c6d84 sending LeaveGroup request to coordinator kafka-2:9092 (id: 2147483645 rack: null) due to consumer poll timeout has expired. (org.apache.kafka.clients.consumer.internals.ConsumerCoordinator)
    처리 O-1004 ... (4000ms)
    처리 O-1005 ... (4000ms)
  commitSync() 시도...
  ❌ CommitFailedException: Offset commit cannot be completed since the consumer is not
     part of an active group for auto partition assignment; it is likely that the consumer
     was kicked out of the group.
  poll() → 5건 수신 (s05_orders-0)     ← ★ 같은 5건을 다시 받았습니다
    처리 O-1001 ... (4000ms)
```

일어난 일을 정리하면 이렇습니다.

1. `poll()` 이 5건을 반환합니다.
2. 처리에 20초가 걸립니다. 10초 시점에 코디네이터가 이 컨슈머를 **제거**합니다.
3. 하트비트 스레드가 알아채고 **스스로 `LeaveGroup` 을 보냅니다.**
4. 처리가 끝나고 `commitSync()` 를 부르면 **`CommitFailedException`** 이 납니다. 이미 그룹 밖이라 커밋할 권한이 없습니다.
5. 컨슈머가 그룹에 다시 합류하고, 커밋되지 않은 **같은 5건을 다시 받습니다.**
6. 다시 20초가 걸리고... **무한 반복입니다.**

**진행이 전혀 없습니다.** 그런데 로그만 보면 컨슈머는 계속 메시지를 처리하고 있습니다. `LAG` 은 줄지 않고, `generationId` 만 계속 올라갑니다.

```bash
kcg --describe --group s05-slow --state
```

**결과** (몇 초 간격으로 반복 실행)

```
GROUP           COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE                 #MEMBERS
s05-slow        kafka-2:9092 (2)          range                PreparingRebalance    0
```

`STATE` 가 `PreparingRebalance` 와 `Stable` 을 오갑니다. `#MEMBERS` 가 0 이 되는 순간이 반복됩니다.

### `session.timeout.ms` vs `max.poll.interval.ms`

| 항목 | `session.timeout.ms` | `max.poll.interval.ms` |
|---|---|---|
| 기본값 | 45,000 (45초) | 300,000 (**5분**) |
| 무엇을 감시 | **하트비트** 스레드의 신호 | **`poll()` 호출** 간격 |
| 어느 스레드 | 백그라운드 하트비트 스레드 | 애플리케이션 처리 스레드 |
| 초과 시 감지자 | **코디네이터**가 감지 | **컨슈머 자신**이 감지 |
| 초과 시 동작 | 코디네이터가 멤버 제거 | 컨슈머가 스스로 `LeaveGroup` 전송 |
| 대표 원인 | 프로세스 죽음, GC 정지, 네트워크 단절 | **처리가 느림** |
| 조정 대상 | 대개 손대지 않음 | 처리 시간에 맞춰 조정 |
| 제약 | `heartbeat.interval.ms` 의 3배 이상 권장 | `session.timeout.ms` 보다 크게 |

> ⚠️ **함정 C — 이 문제는 "에러"로 보이지 않습니다**
> 컨슈머 프로세스는 죽지 않습니다. 로그에는 `WARN` 한 줄과 `CommitFailedException` 이 있을 뿐이고,
> 그 사이사이 정상적인 처리 로그가 계속 찍힙니다. **메트릭상 처리량도 정상으로 보입니다** — 같은 메시지를 반복 처리하니까요.
> 유일한 단서는 **`LAG` 이 줄지 않는 것**과 **`generationId` 가 계속 오르는 것**입니다.
> **해결 순서 (위에서부터)**
> 1. **`max.poll.records` 를 줄입니다.** 500 → 50 이면 한 루프가 1/10 이 됩니다. **가장 먼저 시도할 것.**
> 2. **처리를 빠르게 합니다.** 건당 4초가 걸리는 원인(동기 HTTP 호출, N+1 쿼리)을 없앱니다.
> 3. **`max.poll.interval.ms` 를 늘립니다.** 마지막 수단입니다. 이 값을 늘리면
>    진짜로 컨슈머가 멈췄을 때 감지가 그만큼 늦어지고, **Eager 리밸런싱의 정지 시간도 함께 늘어납니다**(`rebalance.timeout.ms` 가 이 값을 따라갑니다).
> 4. **처리를 별도 스레드 풀로 넘기고 `pause()`/`resume()` 으로 흐름을 제어합니다.** 정석이지만 오프셋 관리가 복잡해집니다(Step 06).

---

## 5-11. 세 타이머의 관계

`heartbeat.interval.ms` / `session.timeout.ms` / `max.poll.interval.ms` 를 한 그림으로 정리합니다.

```
   [ 하트비트 스레드 ]  ─ 백그라운드. 처리와 무관하게 계속 돕니다.
        │
        ├─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─♥─►
        │  ▲                                                 heartbeat.interval.ms = 3000
        │  └─ 3초 간격
        │
        │  ♥ 가 45초 동안 끊기면 → 코디데이터가 멤버 제거
        │  ├────────── session.timeout.ms = 45000 ──────────┤
        │
   [ 처리 스레드 ]  ─ poll() → 처리 → poll() → 처리 ...
        │
        ├─[poll]──처리(2s)──[poll]──처리(3s)──[poll]──처리(280s)────✗
        │                                            ▲
        │                                            │
        │  poll() 간격이 300초를 넘으면 → 컨슈머 자신이 LeaveGroup
        │  ├──────────── max.poll.interval.ms = 300000 ────────────┤
        │
        │  리밸런싱 시 코디네이터가 이 멤버를 기다려 주는 시간도 같은 값입니다.
        │  ├──────────── rebalance.timeout.ms (= max.poll.interval.ms) ─┤
```

| 설정 | 기본값 | 권장 조정 |
|---|---:|---|
| `heartbeat.interval.ms` | 3,000 | `session.timeout.ms` 의 **1/3 이하**로 유지 |
| `session.timeout.ms` | 45,000 | 대개 기본값. 네트워크가 불안정하면 늘림 |
| `max.poll.interval.ms` | 300,000 | **처리 시간 × `max.poll.records` × 안전계수 2** |
| `rebalance.timeout.ms` | = `max.poll.interval.ms` | 별도 설정 불가 (컨슈머에서는 파생값) |

> 💡 **계산 예시**
> 건당 처리 200ms, `max.poll.records=500` 이면 한 루프 = 100초.
> 안전계수 2를 곱해 `max.poll.interval.ms=200000` 이면 충분합니다.
> 반대로 건당 처리가 3초라면 `500 × 3초 = 1500초` 로 기본값(300초)을 훌쩍 넘습니다.
> 이 경우 `max.poll.interval.ms` 를 1500초로 늘리는 것이 아니라 **`max.poll.records` 를 50으로 줄여** `150초` 로 맞추는 것이 정답입니다.

---

## 5-12. 정적 멤버십 — 롤링 재시작에 리밸런싱을 없앤다

배포할 때마다 리밸런싱이 일어나는 것은 낭비입니다. 컨슈머 10개를 하나씩 재시작하면 **리밸런싱이 20번**(내려갈 때 10번, 올라올 때 10번) 일어납니다. Eager 라면 그때마다 전원이 멈춥니다.

**정적 멤버십(KIP-345, 2.3+)** 은 컨슈머에 고정 ID 를 부여해 이 문제를 없앱니다.

```properties
group.instance.id=order-processor-3
session.timeout.ms=60000
```

`group.instance.id` 가 있으면 컨슈머는 **정적 멤버**가 됩니다.

- 종료 시 `LeaveGroup` 을 **보내지 않습니다.** 코디네이터는 이 멤버가 "잠깐 자리를 비웠다"고 간주합니다.
- `session.timeout.ms` 안에 **같은 `group.instance.id` 로 다시 합류하면**, 코디네이터가 **이전과 똑같은 파티션을 그대로 돌려줍니다.** 리밸런싱이 일어나지 않습니다.
- `session.timeout.ms` 를 넘기면 그때 리밸런싱이 시작됩니다.

```
  동적 멤버십 (기본)                        정적 멤버십
  ─────────────────                        ──────────────
  컨슈머 3 종료
    → LeaveGroup 즉시 전송                   → LeaveGroup 안 보냄
    → 리밸런싱 (전원 정지)                    → 아무 일 없음. P2 는 잠시 소비 중단만
  컨슈머 3 재기동
    → JoinGroup                             → JoinGroup (같은 group.instance.id)
    → 리밸런싱 (전원 정지)                    → "너 P2 였지" 하고 그대로 반환
                                              → 리밸런싱 없음
  총 리밸런싱 2회                            총 리밸런싱 0회
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java static-member'
```

**결과**

```
[static-member] group.instance.id=s05-static-1 로 합류합니다.
  assigned = [s05_orders-0, s05_orders-1, s05_orders-2]
  generationId 를 확인하십시오: kcg --describe --group s05-static --state
  15초 후 종료합니다. 종료 뒤 30초 안에 다시 실행하면 리밸런싱 없이 같은 파티션을 받습니다.
```

종료 직후 상태를 봅니다.

```bash
kcg --describe --group s05-static --state
```

**결과**

```
GROUP           COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE           #MEMBERS
s05-static      kafka-1:9092 (1)          range                Stable          1
```

**컨슈머가 종료됐는데도 `STATE` 가 `Stable` 이고 `#MEMBERS` 가 1 입니다.** 코디네이터는 이 멤버가 여전히 있다고 믿고 있습니다. `--members` 로 보면 이렇습니다.

```bash
kcg --describe --group s05-static --members --verbose
```

**결과**

```
GROUP           CONSUMER-ID                                            HOST            CLIENT-ID           #PARTITIONS     ASSIGNMENT
s05-static      s05-static-1-6b8e2f04-1a37-4c95-b0d2-9e4f7a1c3b58      /172.18.0.4     s05-static-1        3               s05_orders(0,1,2)
```

`CONSUMER-ID` 가 `s05-static-1-<UUID>` 로 시작합니다. 앞부분이 `group.instance.id` 입니다. 다시 실행하면 이 ID 로 인식되어 리밸런싱 없이 같은 할당을 받습니다.

> ⚠️ **함정 — 정적 멤버십은 진짜 장애의 감지도 늦춥니다**
> 컨슈머가 진짜로 죽어도 `session.timeout.ms` 동안은 리밸런싱이 일어나지 않습니다.
> 그동안 그 컨슈머가 맡던 파티션은 **아무도 소비하지 않습니다.** 랙이 그만큼 쌓입니다.
> `session.timeout.ms=600000`(10분) 처럼 크게 잡으면 롤링 재시작은 완벽해지지만,
> 진짜 크래시 때 **10분간 그 파티션이 방치**됩니다.
> **해결**: `session.timeout.ms` 는 "배포 시 한 인스턴스가 내려갔다 올라오는 시간 + 여유" 로 잡으십시오.
> 컨테이너 재시작이 30초면 60,000ms 정도가 적당합니다. 10분은 과합니다.
> 그리고 **의도적으로 축소할 때는 `--delete-offsets` 가 아니라 `kafka-consumer-groups.sh --delete --group X`** 로
> 남은 정적 멤버를 정리해야 합니다. 안 그러면 없는 인스턴스가 파티션을 계속 붙잡고 있습니다.

> 💡 **`group.instance.id` 는 인스턴스마다 고유해야 합니다**
> 쿠버네티스라면 StatefulSet 의 파드 서수(`order-processor-0`, `-1`, ...)를 그대로 쓰면 됩니다.
> Deployment 는 파드 이름이 매번 바뀌므로 정적 멤버십과 궁합이 나쁩니다.
> 같은 `group.instance.id` 를 가진 인스턴스가 **동시에 두 개** 뜨면 나중에 뜬 쪽이 먼저 뜬 쪽을 쫓아냅니다.
> ```
> org.apache.kafka.common.errors.FencedInstanceIdException: The broker rejected this
>   static consumer since another consumer with the same group.instance.id has registered
>   with a different member.id.
> ```

---

## 5-13. 오프셋 리셋은 다음 스텝에서

`kafka-consumer-groups.sh` 에는 `--reset-offsets` 라는 강력한 옵션이 있습니다.

```bash
kcg --reset-offsets --group s05-demo --topic s05_orders --to-earliest --dry-run
```

**결과**

```
GROUP                          TOPIC                          PARTITION  NEW-OFFSET
s05-demo                       s05_orders                     0          0
s05-demo                       s05_orders                     1          0
s05-demo                       s05_orders                     2          0
```

`--to-earliest` / `--to-latest` / `--to-offset` / `--to-datetime` / `--shift-by` / `--by-duration` 등 옵션이 많고, `--dry-run` 과 `--execute` 의 차이가 중요합니다. **오프셋 관리 전체를 [Step 06](../step-06-offsets/) 에서 다루므로 여기서는 존재만 확인하고 넘어갑니다.**

한 가지만 기억하세요. **`--reset-offsets` 는 그룹에 활성 멤버가 있으면 실패합니다.**

```
Error: Assignments can only be reset if the group 's05-demo' is inactive, but the current state is Stable.
```

---

## 5-14. 정리 (실습 마무리)

이 스텝에서 만든 토픽과 **그룹**을 모두 삭제합니다. 그룹 정리가 특히 중요합니다. 남겨 두면 다음 스텝에서 "왜 메시지가 안 읽히지?"(이미 커밋된 오프셋 때문)로 헤맵니다.

컨슈머를 전부 종료한 뒤 실행하세요.

```bash
kcg --list
```

**결과**

```
s05-demo
s05-four
s05-range
s05-rr
s05-coop
s05-slow
s05-static
console-consumer-40317
```

```bash
for G in s05-demo s05-four s05-range s05-rr s05-coop s05-slow s05-static; do
  kcg --delete --group "$G" 2>/dev/null || echo "$G 삭제 실패 (활성 멤버가 남아 있는지 확인)"
done

kt --delete --topic s05_orders
kt --delete --topic s05_payments
```

**결과**

```
Deletion of requested consumer groups ('s05-demo') was successful.
Deletion of requested consumer groups ('s05-four') was successful.
Deletion of requested consumer groups ('s05-range') was successful.
Deletion of requested consumer groups ('s05-rr') was successful.
Deletion of requested consumer groups ('s05-coop') was successful.
Deletion of requested consumer groups ('s05-slow') was successful.
Deletion of requested consumer groups ('s05-static') was successful.
```

확인합니다.

```bash
kcg --list | grep '^s05-' || echo "s05- 그룹 없음 — 정리 완료"
kt --list | grep '^s05_' || echo "s05_ 토픽 없음 — 정리 완료"
```

**결과**

```
s05- 그룹 없음 — 정리 완료
s05_ 토픽 없음 — 정리 완료
```

> ⚠️ **그룹 삭제가 실패하면 활성 멤버가 남아 있는 것입니다**
> ```
> Error: Deletion of some consumer groups failed:
> * Group 's05-coop' could not be deleted due to: GroupNotEmptyException
> ```
> 백그라운드에 컨슈머가 살아 있습니다. 정적 멤버십(`s05-static`)은 종료 후에도
> `session.timeout.ms` 동안 멤버로 남으므로 **잠시 기다렸다가** 다시 시도하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 컨슈머 그룹 | 같은 `group.id` = 파티션을 **나눠** 가짐. 다른 그룹 = 각자 **전부** 받음 |
| 파티션 할당 | 한 파티션은 그룹 안에서 **정확히 한 컨슈머**에게만 |
| 그룹 코디네이터 | 브로커 하나가 담당. `__consumer_offsets` 의 해당 파티션 리더 |
| 할당 계산 주체 | 브로커가 아니라 **리더 컨슈머**. 그래서 전략이 클라이언트 설정 |
| `poll()` | 내부 fetch 버퍼에서 `max.poll.records`(500) 만큼 꺼내 감. **호출 수 ≠ 네트워크 요청 수** |
| `--describe` | `LAG = LOG-END-OFFSET - CURRENT-OFFSET`. 컨슈머 없으면 ID/HOST/CLIENT 가 `-` |
| `--members --verbose` | `ASSIGNMENT` 컬럼으로 **누가 어느 파티션**인지 확인 |
| `--state` | `COORDINATOR` / `ASSIGNMENT-STRATEGY` / `STATE` / `#MEMBERS` |
| **컨슈머 > 파티션** | 남는 컨슈머의 `#PARTITIONS` 가 **0**. **에러 없이 그냥 논다** |
| 병렬성 상한 | **파티션 수.** 컨슈머를 늘려도 그 이상은 안 됨 |
| 리밸런싱 관찰 | `Warning: ... is rebalancing.` → 2초 뒤 재배분. 유휴 컨슈머가 파티션을 이어받음 |
| `generationId` | 리밸런싱마다 +1. **자주 오르면 리밸런싱 폭풍** |
| 할당 전략 | Range(기본, **토픽마다 앞쪽 쏠림**) / RoundRobin / Sticky / **CooperativeSticky** |
| **Eager 리밸런싱** | **전원이 전부 반납하고 멈춤.** 최대 `rebalance.timeout.ms`(=`max.poll.interval.ms`) |
| Cooperative | 이동하는 파티션만 반납. `Revoked partitions: []` 가 증거. **2단계 롤링 필수** |
| **`max.poll.interval.ms`** | 처리가 느려 초과하면 **스스로 LeaveGroup** → `CommitFailedException` → 같은 메시지 재수신 → 무한 반복 |
| session vs max.poll | 전자는 **하트비트 스레드**(코디네이터가 감지), 후자는 **처리 스레드**(컨슈머가 감지) |
| 해결 우선순위 | `max.poll.records` 축소 → 처리 최적화 → `max.poll.interval.ms` 증가 |
| 정적 멤버십 | `group.instance.id` 로 롤링 재시작 시 리밸런싱 0회. 대신 **진짜 장애 감지도 늦어짐** |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`.

1. 파티션 3개 토픽에 컨슈머를 1 → 2 → 3 → 4 개로 늘려 가며 `--members --verbose` 로 할당 변화 기록하기
2. 컨슈머 하나를 `kill -9` 로 죽였을 때와 `Ctrl+C` 로 죽였을 때 **리밸런싱까지 걸리는 시간** 비교하기
3. `--describe` 출력에서 `LAG` 이 가장 큰 파티션을 찾는 한 줄 명령 만들기
4. 같은 그룹에 `RangeAssignor` 와 `RoundRobinAssignor` 를 섞어 붙여 어떤 에러가 나는지 확인하기
5. 두 토픽을 구독하는 컨슈머 2개로 Range 와 RoundRobin 의 할당 불균형 비교하기
6. `max.poll.interval.ms` 를 초과시켜 `CommitFailedException` 을 재현하고, `max.poll.records` 만 줄여 해결하기
7. `group.instance.id` 를 준 컨슈머를 껐다 켜면서 `generationId` 가 **오르지 않는 것** 확인하기

---

## 다음 단계

컨슈머가 어디까지 읽었는지는 **오프셋**이 기억합니다. 그런데 그 오프셋을 **언제 커밋하느냐**가
유실과 중복을 가릅니다. `enable.auto.commit=true` 가 어떻게 처리하지 않은 메시지의 오프셋을 커밋해 버리는지,
그래서 컨슈머가 죽으면 왜 메시지가 사라지는지를 직접 재현합니다.

→ [Step 06 — 오프셋 관리](../step-06-offsets/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개와 Java 파일 하나로 진행합니다. 컨슈머 실습은 **여러 프로세스를 동시에 띄웠다 죽여야** 하므로, `practice.sh` 는 백그라운드 실행(`&`)과 PID 관리를 적극적으로 씁니다. CLI 로는 제어할 수 없는 `max.poll.interval.ms` 초과와 정적 멤버십은 `Practice.java` 가 담당합니다.

### practice.sh

본문의 모든 명령을 절 번호 주석(`# [5-6]`)과 함께 담은 실행 스크립트입니다.

- 상단에 `BS=kafka-1:9092`, `K()` 헬퍼와 함께 **`start_consumer()` / `stop_all()`** 두 함수를 정의했습니다. `start_consumer <그룹> [추가옵션...]` 은 콘솔 컨슈머를 백그라운드로 띄우고 PID 를 배열에 담아 두며, `stop_all` 이 전부 정리합니다. 본문에서 "터미널 A~D 를 여세요" 라고 한 부분이 스크립트에서는 이 함수 호출로 대체됩니다.
- `[5-6]` 의 함정 A 구간은 `start_consumer s05-four` 를 **네 번** 호출한 뒤 `--members --verbose` 를 찍습니다. 출력에서 `#PARTITIONS` 가 `0` 인 줄을 `grep` 으로 뽑아 강조하므로, 네 번째 컨슈머가 노는 것이 한눈에 보입니다.
- `[5-7]` 의 리밸런싱 관찰은 `kill -TERM` 으로 컨슈머 하나를 죽인 직후 **0.5초 간격으로 8번** `--members --verbose` 를 찍습니다. `Warning: Consumer group ... is rebalancing.` 이 나타났다 사라지고 할당이 바뀌는 것을 타임스탬프와 함께 볼 수 있습니다.
- `[5-8]` 은 `s05_orders` 와 `s05_payments` 를 함께 구독하는 컨슈머 2개를 Range 와 RoundRobin 으로 각각 띄워 할당 개수를 비교합니다. Range 는 `4:2`, RoundRobin 은 `3:3` 이 나와야 정상입니다. 두 그룹 이름(`s05-range`, `s05-rr`)을 다르게 둔 이유는 **같은 그룹에서 전략을 섞으면 `InconsistentGroupProtocolException`** 이 나기 때문입니다(연습문제 4번의 주제).
- `[5-10]` 은 `Practice.java slow-consumer` 를 **60초 타임아웃**으로 실행합니다. 이 시나리오는 스스로 끝나지 않고 무한 반복하도록 만들어 두었으므로(그게 함정의 본질이므로) `timeout 60` 으로 감쌌습니다. 실행 중에 다른 창에서 `kcg --describe --group s05-slow --state` 를 반복하면 `STATE` 가 `PreparingRebalance` 와 `Stable` 을 오가는 것이 보입니다.
- `[5-14]` 가 `s05-` 로 시작하는 **모든 그룹**과 `s05_` 토픽을 삭제합니다. 정적 멤버십 그룹은 종료 후에도 `session.timeout.ms` 동안 멤버로 남아 삭제가 실패하므로, 스크립트가 `sleep 35` 를 넣고 재시도합니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- 파일이 `s05_ex_orders`(3파티션)와 `s05_ex_payments`(3파티션)를 미리 만들고 각각 200건을 채워 줍니다. 문제를 풀다 데이터가 부족해지면 상단의 `seed()` 함수만 다시 호출하십시오.
- **문제 1·5** 는 `--members --verbose` 출력을 표로 옮겨 적는 관찰 문제입니다. 답이 환경마다 달라 보이지만, **할당 개수의 분포**(문제 1: `3 → 2,1 → 1,1,1 → 1,1,1,0`, 문제 5: Range `4:2` vs RoundRobin `3:3`)는 항상 같습니다. 그 분포를 맞히는 것이 정답 조건입니다.
- **문제 2** 는 `kill -9` 와 `kill -TERM` 의 차이를 재는 문제입니다. `-TERM`(Ctrl+C 와 같음)은 컨슈머가 `LeaveGroup` 을 보내 **1~2초** 만에 리밸런싱이 끝나고, `-9` 는 코디네이터가 하트비트 단절을 감지할 때까지 **`session.timeout.ms`(45초)** 를 기다립니다. 문제지에는 측정 루프의 뼈대만 있고, `date +%s` 로 시각을 찍는 부분을 여러분이 채웁니다.
- **문제 4** 는 **일부러 에러를 내는** 문제입니다. 같은 그룹에 서로 다른 전략의 컨슈머를 붙이면 나중에 붙은 쪽이 `InconsistentGroupProtocolException` 으로 죽습니다. 콘솔 컨슈머는 이 예외를 stderr 로 뱉고 종료하므로, `2>&1 | grep` 으로 잡아야 보입니다.
- **문제 6** 은 `Practice.java slow-consumer` 를 두 번 실행합니다. 첫 번째는 기본값(`max.poll.records=5`, 처리 4초)으로 `CommitFailedException` 을 재현하고, 두 번째는 `MAX_POLL_RECORDS` 환경변수를 `1` 로 줘서 한 루프가 4초로 줄어 문제가 사라지는 것을 확인합니다. **설정 하나만 바꿔 해결된다**는 것이 이 문제의 요점입니다.
- 파일 끝의 `cleanup_ex()` 가 `s05_ex_` 토픽과 `s05-ex-` 그룹을 모두 지웁니다.

```bash file="./exercise.sh"
```

### solution.sh

정답 명령과 "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여십시오.

- **정답 1** 의 해설이 이 스텝의 핵심을 압축합니다. 컨슈머 4개일 때 할당이 `1,1,1,0` 이 되는데, 여기서 강조하는 것은 "네 번째가 노는 것"보다 **"`--describe` 화면에는 그 컨슈머가 아예 나오지 않는다"** 는 점입니다. 파티션 기준으로 출력되기 때문이며, 그래서 `--members` 를 봐야만 알 수 있습니다.
- **정답 2** 의 측정값은 `kill -TERM` 이 **약 1.2초**, `kill -9` 가 **약 46초**입니다. 해설은 이 차이가 곧 "왜 컨테이너에 `terminationGracePeriodSeconds` 를 충분히 줘야 하는가" 의 답이라고 이어집니다. 그레이스 기간이 짧아 `SIGKILL` 이 날아가면 매 배포마다 45초씩 파티션이 방치됩니다.
- **정답 3** 은 `awk 'NR>1 && $6 != "-" {print $6, $0}' | sort -rn | head -1` 입니다. `LAG` 이 6번째 컬럼이라는 것과, 헤더 줄(`NR>1`)과 `-` 를 걸러야 한다는 것이 포인트입니다. 해설은 이 한 줄이 실무에서 **hot partition 을 찾는 첫 번째 명령**이라는 점을 짚습니다(Step 04 의 함정 C 와 연결).
- **정답 4** 의 에러 메시지는 `InconsistentGroupProtocolException: The group member's supported protocols are incompatible with those of existing members.` 입니다. 해설은 여기서 **전략 변경 배포의 2단계 롤링**으로 확장합니다. 목록에 두 전략을 모두 넣은 상태로 한 번 배포하고, 그다음 하나만 남기는 절차이며, Eager → Cooperative 전환에도 **똑같이** 적용됩니다.
- **정답 6** 이 이 문제지에서 가장 중요합니다. `max.poll.records` 를 5에서 1로 줄이는 것만으로 해결되는 것을 보여 준 뒤, **왜 `max.poll.interval.ms` 를 늘리는 게 마지막 수단인지** 를 설명합니다. 이 값을 늘리면 `rebalance.timeout.ms` 가 함께 늘어나 **Eager 리밸런싱의 정지 시간까지 늘어나기** 때문입니다. 5-9 와 5-10 이 여기서 만납니다.
- **정답 7** 은 `generationId` 를 `--state` 대신 컨슈머 로그의 `Successfully joined group with generation Generation{generationId=N` 에서 뽑습니다. 정적 멤버는 재기동해도 이 값이 **그대로**이고, 동적 멤버는 2씩 오릅니다. 해설은 `session.timeout.ms` 를 크게 잡을 때의 대가(진짜 크래시 감지 지연)를 함께 경고합니다.

```bash file="./solution.sh"
```

### Practice.java

CLI 로는 제어할 수 없는 컨슈머 동작 — **`poll()` 루프 타이밍**, **`max.poll.interval.ms` 초과**, **할당 전략 비교**, **정적 멤버십** — 을 담은 Java 21 단일 파일 프로그램입니다.

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
```

- 시나리오는 `consume` / `slow-consumer` / `assignor` / `static-member` 네 개입니다. 인자 없이 실행하면 사용법이 출력됩니다.
- `consume` 은 `ConsumerRebalanceListener` 를 붙여 `onPartitionsRevoked` / `onPartitionsAssigned` 가 **언제 어떤 파티션으로** 호출되는지 찍습니다. 이 프로그램을 두 개 띄우고 하나를 죽이면, 5-7 의 로그 흐름을 콜백 수준에서 볼 수 있습니다.
- `slow-consumer` 는 `max.poll.interval.ms=10000`, `max.poll.records=5`, 레코드당 `Thread.sleep(4000)` 으로 **의도적으로 타임아웃을 유발**합니다. 5건 × 4초 = 20초 > 10초이므로 반드시 쫓겨납니다. `commitSync()` 를 `try/catch` 로 감싸 `CommitFailedException` 을 잡아 출력하고, **같은 메시지를 다시 받는 것**까지 보여 준 뒤 계속 반복합니다.
- `slow-consumer` 는 환경변수로 조정할 수 있습니다. `MAX_POLL_RECORDS=1` 을 주면 한 루프가 4초로 줄어 문제가 사라지고, `PROCESS_MS=1000` 을 주면 5초가 되어 역시 사라집니다. **설정 하나로 해결되는 것을 직접 확인하라**는 의도입니다.
- `assignor` 는 같은 그룹에 컨슈머 **3개를 스레드로** 띄우고, `partition.assignment.strategy` 를 인자로 받아 할당 결과를 표로 출력합니다. `Practice.java assignor range` / `roundrobin` / `sticky` / `cooperative-sticky` 로 네 전략을 한 프로세스 안에서 비교할 수 있어, 터미널을 여러 개 열 필요가 없습니다.
- `static-member` 는 `group.instance.id=s05-static-1`, `session.timeout.ms=60000` 으로 합류해 할당을 출력하고 15초 뒤 종료합니다. **종료 후 30초 안에 다시 실행하면** 코디네이터가 같은 파티션을 그대로 돌려주며, 로그의 `generationId` 가 오르지 않는 것으로 확인할 수 있습니다.
- 모든 시나리오가 `Runtime.getRuntime().addShutdownHook` 으로 `consumer.wakeup()` 을 호출해, `Ctrl+C` 시 `poll()` 을 깨우고 `close()` 로 정상 종료합니다. 정상 종료해야 `LeaveGroup` 이 나가고 리밸런싱이 즉시 일어납니다 — 연습문제 2번의 `kill -9` 와 비교되는 지점입니다.

```java file="./Practice.java"
```
