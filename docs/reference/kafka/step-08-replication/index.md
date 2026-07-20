# Step 08 — 복제와 내구성

> **학습 목표**
> - 리더 / 팔로워 / ISR / High Watermark 의 관계를 설명하고, 팔로워가 **프로듀서처럼 fetch 로 당겨온다**는 사실을 확인한다
> - `--describe` 의 `Replicas` / `Isr` / `Leader` 를 읽고 preferred leader 를 판정한다
> - **브로커를 실제로 죽여** ISR 축소와 리더 선출을 시간순으로 관찰하고, 되살린 뒤 `kafka-leader-election.sh` 로 리더를 되돌린다
> - `acks=all` 만으로는 유실을 막지 못한다는 것을 `min.insync.replicas=1` 상태에서 재현한다
> - `unclean.leader.election.enable=true` 가 **이미 커밋된 오프셋 구간을 버리는** 순간을 LEO 숫자로 실측한다
> - RF / `min.insync.replicas` / `acks` 조합이 가용성과 내구성 사이 어디에 놓이는지 표로 정리한다
>
> **선행 스텝**: Step 07 — 전달 보장
> **예상 소요**: 120분

---

## 8-0. 실습 준비

이 스텝은 **브로커를 죽입니다.** 시작 전에 세 브로커가 전부 살아 있는지 확인하세요.

```bash
cd kafka/docker
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
```

**결과**

```
NAME       STATUS
kafka-1    Up 4 minutes (healthy)
kafka-2    Up 4 minutes (healthy)
kafka-3    Up 4 minutes (healthy)
kafka-ui   Up 4 minutes
```

하나라도 `Exited` 면 `docker compose up -d` 로 살린 뒤 진행합니다. 별칭도 다시 등록합니다.

```bash
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
alias kconf='docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka-1:9092'
```

> ⚠️ 이 스텝의 별칭은 **전부 `kafka-1` 을 경유**합니다. 실습 중에 `kafka-1` 은 죽이지 않습니다. 죽이는 대상은 `kafka-2` 와 `kafka-3` 뿐입니다.
> `kafka-1` 을 죽이면 별칭 자체가 동작하지 않아 실습이 멈춥니다. (진짜 운영이라면 부트스트랩에 세 브로커를 다 적어야 하는 이유이기도 합니다.)

실습 토픽을 만듭니다. **파티션 1개**로 만드는 것이 핵심입니다. 리더가 어디로 옮겨 가는지를 한 줄로 추적하기 위해서입니다.

```bash
kt --create --topic s08_dur --partitions 1 --replication-factor 3 \
   --config min.insync.replicas=1
```

**결과**

```
Created topic s08_dur.
```

---

## 8-1. 복제 구조 — 리더 하나, 팔로워 둘

Kafka 의 복제 단위는 **토픽이 아니라 파티션**입니다. `replication.factor=3` 이면 파티션 하나가 브로커 3대에 복사본(리플리카) 3개로 존재합니다. 그중 **정확히 하나가 리더**이고 나머지는 팔로워입니다.

```
            프로듀서                         컨슈머
               │ produce                        │ fetch
               ▼                                ▼
   ┌───────────────────────────────────────────────────────┐
   │  kafka-1                                              │
   │  ┌─────────────────────────────────────────────┐      │
   │  │  s08_dur-0   [ LEADER ]                     │      │
   │  │  로그: 0 1 2 3 ... 148 149                  │      │
   │  │  LEO=150   HW=150                           │      │
   │  └─────────────────────────────────────────────┘      │
   └───────────────▲──────────────────▲────────────────────┘
                   │ fetch            │ fetch
                   │ (팔로워가 당겨감) │
   ┌───────────────┴──────┐   ┌───────┴──────────────┐
   │  kafka-2             │   │  kafka-3             │
   │  s08_dur-0 [FOLLOWER]│   │  s08_dur-0 [FOLLOWER]│
   │  LEO=150             │   │  LEO=150             │
   └──────────────────────┘   └──────────────────────┘

   ISR = { 1, 2, 3 }   ← 리더를 "충분히 따라잡은" 리플리카 집합 (리더 자신 포함)
```

**여기서 가장 중요한 사실 하나.** 리더가 팔로워에게 데이터를 **밀어(push) 주지 않습니다.** 팔로워가 리더에게 `Fetch` 요청을 보내 **당겨(pull) 옵니다.** 컨슈머가 하는 일과 완전히 같은 메커니즘이며, 실제로 팔로워는 내부적으로 `ReplicaFetcherThread` 라는 이름의 "특별한 컨슈머"입니다.

이 사실이 실무에서 갖는 의미는 이렇습니다.

- 팔로워가 느리면 그건 **팔로워 쪽 문제**(디스크 IO, GC, 네트워크)입니다. 리더는 그저 요청에 응답할 뿐입니다.
- 팔로워의 fetch 크기 상한이 `replica.fetch.max.bytes` 이고, 팔로워 브로커가 굴리는 fetcher 스레드 수가 `num.replica.fetchers` 입니다. 복제가 못 따라가면 이 둘을 봅니다 (8-9).
- 리더가 죽으면 **가장 잘 따라온 팔로워**가 다음 리더가 됩니다. "잘 따라왔다"의 정의가 바로 ISR 입니다.

토픽 상태를 확인합니다.

```bash
kt --describe --topic s08_dur
```

**결과**

```
Topic: s08_dur	TopicId: 7hK2mR9pQzOaXvL4TdWuBg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=1,segment.bytes=1048576
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

---

## 8-2. Replicas 목록과 preferred leader

`Replicas: 1,2,3` 은 단순한 집합이 아니라 **순서 있는 목록**입니다. 그리고 **목록의 첫 번째가 preferred leader(선호 리더)** 입니다. 클러스터가 건강할 때 Kafka 는 각 파티션의 리더를 preferred leader 에 맞추려고 합니다.

파티션이 여러 개인 토픽을 만들어 배치 규칙을 봅니다.

```bash
kt --create --topic s08_place --partitions 6 --replication-factor 3
kt --describe --topic s08_place
```

**결과**

```
Topic: s08_place	TopicId: bQ8vN1sYRmqCd3XeZfHtLA	PartitionCount: 6	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576
	Topic: s08_place	Partition: 0	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: s08_place	Partition: 1	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: s08_place	Partition: 2	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: s08_place	Partition: 3	Leader: 2	Replicas: 2,1,3	Isr: 2,1,3
	Topic: s08_place	Partition: 4	Leader: 3	Replicas: 3,2,1	Isr: 3,2,1
	Topic: s08_place	Partition: 5	Leader: 1	Replicas: 1,3,2	Isr: 1,3,2
```

**Replicas 의 첫 번째 숫자가 2 → 3 → 1 → 2 → 3 → 1 로 로테이션됩니다.** 우연이 아닙니다. Kafka 의 리플리카 배치 알고리즘은 두 가지를 동시에 만족시키려 합니다.

1. **리더 부하를 브로커에 고르게 분산한다.** 첫 리플리카(=preferred leader)를 브로커 목록을 따라 라운드로빈으로 배치합니다. 6 파티션 / 3 브로커면 브로커마다 리더 2개씩입니다.
2. **팔로워 배치도 고르게 한다.** 두 번째 리플리카부터는 "shift" 값을 적용해 배치하므로, 파티션 0 은 `2,3,1` 이지만 파티션 3 은 `2,1,3` 으로 **두 번째 리플리카가 다릅니다.** 브로커 하나가 죽었을 때 그 부하가 나머지 한 대에만 쏠리지 않게 하기 위한 것입니다.

여기에 `broker.rack` 이 설정돼 있으면 rack-aware 배치가 추가로 적용됩니다 (8-9).

| 용어 | 의미 | `--describe` 에서 |
|---|---|---|
| Replicas | 이 파티션의 복사본이 놓인 브로커 목록 (**순서 있음**) | `Replicas: 2,3,1` |
| preferred leader | Replicas 의 **첫 번째** 브로커 | 위 예에서 `2` |
| Leader | **지금** 리더인 브로커 | `Leader: 2` (preferred 와 같음 = 건강) |
| Isr | 리더를 충분히 따라잡은 리플리카 집합 (리더 포함) | `Isr: 2,3,1` |

> 💡 **실무 팁 — `Leader` 와 `Replicas` 첫 번째가 다르면 "리더가 쏠려 있다"는 신호**
> 브로커를 롤링 재시작하면 리더가 살아 있는 브로커로 몰립니다. 재시작이 끝나도 **리더는 자동으로 안 돌아옵니다**(8-6).
> `Leader != Replicas[0]` 인 파티션이 쌓이면 특정 브로커만 CPU 와 네트워크를 다 쓰게 됩니다. 롤링 재시작 후에는 반드시 preferred leader election 을 돌려야 합니다.

---

## 8-3. High Watermark 와 Log End Offset — 컨슈머에게 보이는 경계

리플리카마다 두 개의 오프셋이 있습니다.

- **LEO (Log End Offset)**: 그 리플리카가 **가진** 마지막 오프셋 + 1. 즉 다음에 쓸 자리.
- **HW (High Watermark)**: **ISR 의 모든 리플리카가 복제를 마친** 지점. 리더가 계산해 팔로워에게 알려 줍니다.

**컨슈머는 HW 미만만 읽을 수 있습니다.** HW 와 LEO 사이의 레코드는 리더의 디스크에 이미 있지만 컨슈머에게는 존재하지 않는 것처럼 취급됩니다.

```
                                      HW=148          LEO=150
                                        │               │
  리더 (kafka-1)   [ ... 145 146 147 | 148 149 ]        │
                                        │  ▲            │
  팔로워 (kafka-2) [ ... 145 146 147 ]  │  │ 아직 복제 안 됨
                            LEO=148     │  │
  팔로워 (kafka-3) [ ... 145 146 147 ]  │  │
                            LEO=148     │  │
                                        │  └── 컨슈머에게 안 보인다
                       컨슈머는 여기까지만 ─┘
```

왜 이렇게 할까요? **리더가 지금 죽어도 데이터가 사라지지 않게 하기 위해서입니다.** 만약 컨슈머가 오프셋 149 를 읽었는데 리더가 죽고 kafka-2 가 리더가 되면, 새 리더의 로그는 147 에서 끝납니다. 컨슈머가 읽은 148, 149 는 **없던 일**이 됩니다. HW 로 경계를 그으면 그런 일이 생기지 않습니다. **컨슈머가 읽은 것은 이미 ISR 전원이 갖고 있다**는 보장이 되는 것입니다.

메시지 150건을 넣고 실제로 확인합니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s08_dur \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF

# 나머지 149건은 perf-test 로 채웁니다
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s08_dur --num-records 149 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all
```

**결과**

```
149 records sent, 745.000 records/sec (0.14 MB/sec), 4.31 ms avg latency, 41.00 ms max latency, 3 ms 50th, 21 ms 95th, 40 ms 99th, 41 ms 99.9th.
```

이제 오프셋을 봅니다. `kafka-get-offsets.sh` 의 `--time -1` 이 **latest**(= HW), `--time -2` 가 **earliest** 입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s08_dur --time -1
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s08_dur --time -2
```

**결과**

```
s08_dur:0:150
s08_dur:0:0
```

`latest=150`, `earliest=0`. 150건이 전부 읽을 수 있는 상태입니다.

> ⚠️ **함정 — `--time -1` 은 LEO 가 아니라 HW 입니다**
> 클라이언트가 `ListOffsets` 로 얻는 "latest" 는 **리더의 HW** 입니다. LEO 가 아닙니다.
> 그래서 복제가 밀려 있는 순간(HW=148, LEO=150)에 이 명령을 실행하면 **148 이 나옵니다.**
> "프로듀서는 150건 보냈다는데 왜 148 이지?" 의 답이 이것입니다. 잘못된 게 아니라 **아직 ISR 전원이 안 받았을 뿐**입니다.
> 진짜 LEO 를 보려면 브로커의 JMX 지표(`kafka.log:type=Log,name=LogEndOffset`)나 `kafka-dump-log.sh` 로 세그먼트 파일을 직접 봐야 합니다.

---

## 8-4. ISR — 누가 "따라잡았다"고 인정받는가

ISR(In-Sync Replicas)은 **리더를 충분히 따라잡은 리플리카 집합**입니다. 리더 자신도 항상 ISR 에 포함됩니다.

기준은 딱 하나, **시간**입니다.

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `replica.lag.time.max.ms` | `30000` | 팔로워가 이 시간 안에 리더의 LEO 를 따라잡지 못하면 ISR 에서 제외 |
| `replica.fetch.wait.max.ms` | `500` | 팔로워 fetch 요청의 최대 대기 시간 |
| `replica.socket.timeout.ms` | `30000` | 팔로워 fetch 소켓 타임아웃 |

Kafka 0.9 이전에는 `replica.lag.max.messages`(건수 기준)도 있었는데 **제거되었습니다.** 이유가 교훈적입니다. 건수 기준이면 트래픽이 급증하는 순간 멀쩡한 팔로워가 순식간에 기준을 넘겨 ISR 에서 우수수 빠져나갔습니다. 트래픽 스파이크가 곧 ISR 붕괴가 되는 구조였던 것입니다. **지금은 시간 기준 하나뿐입니다.**

정확한 판정 규칙은 이렇습니다.

- 팔로워가 **리더의 LEO 까지 fetch 를 완료**하면 그 시각이 기록됩니다.
- 그 시각으로부터 `replica.lag.time.max.ms`(30초) 가 지나도록 다시 따라잡지 못하면 **ISR 에서 축출(shrink)** 됩니다.
- 축출된 팔로워가 다시 리더의 LEO 를 따라잡으면 **ISR 에 복귀(expand)** 합니다.

브로커가 그냥 죽어 버린 경우도 같은 규칙이 적용됩니다. fetch 요청이 아예 안 오니 30초 뒤 ISR 에서 빠집니다. **바로 빠지지 않는다는 점이 다음 절의 관찰 포인트입니다.**

---

## 8-5. 브로커를 죽인다 — 시간순으로 관찰하기

지금 리더는 `kafka-1` 입니다. 리더가 아닌 `kafka-2` 를 먼저 죽여 **ISR 축소만** 관찰합니다.

```bash
docker compose stop kafka-2
```

**결과**

```
[+] Stopping 1/1
 ✔ Container kafka-2  Stopped                                              10.4s
```

### 즉시 (0초)

```bash
kt --describe --topic s08_dur
```

**결과**

```
Topic: s08_dur	TopicId: 7hK2mR9pQzOaXvL4TdWuBg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=1,segment.bytes=1048576
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

**아무것도 안 바뀌었습니다.** `Isr: 1,2,3` 그대로입니다. 브로커는 이미 죽었는데도 말입니다.

### 10초 후

```bash
kt --describe --topic s08_dur
```

**결과**

```
Topic: s08_dur	TopicId: 7hK2mR9pQzOaXvL4TdWuBg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=1,segment.bytes=1048576
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

여전히 `1,2,3` 입니다.

### 35초 후

```bash
kt --describe --topic s08_dur
```

**결과**

```
Topic: s08_dur	TopicId: 7hK2mR9pQzOaXvL4TdWuBg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=1,segment.bytes=1048576
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,3
```

**`Isr: 1,3` 으로 줄었습니다.** `Replicas` 는 여전히 `1,2,3` 입니다. 리플리카 배치는 그대로이고 **동기 상태만 빠진 것**입니다.

`replica.lag.time.max.ms=30000` 이 정확히 이 30초입니다. 브로커 로그에서 그 순간을 확인합니다.

```bash
docker logs kafka-1 --since 2m 2>&1 | grep -i 'ISR'
```

**결과**

```
[2024-03-11 10:23:41,882] INFO [Partition s08_dur-0 broker=1] Shrinking ISR from 1,2,3 to 1,3. Leader: (highWatermark: 150, endOffset: 150). Out of sync replicas: (brokerId: 2, endOffset: 132). (kafka.cluster.Partition)
```

로그가 모든 것을 말해 줍니다. **kafka-2 는 오프셋 132 까지만 받고 죽었습니다.** 리더는 150 입니다. 18건 차이가 납니다. 이 숫자를 기억해 두세요. 8-8 에서 다시 나옵니다.

### under-replicated 파티션 확인

운영에서 가장 자주 쓰는 명령입니다.

```bash
kt --describe --under-replicated-partitions
```

**결과**

```
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,3
	Topic: s08_place	Partition: 0	Leader: 3	Replicas: 2,3,1	Isr: 3,1
	Topic: s08_place	Partition: 1	Leader: 3	Replicas: 3,1,2	Isr: 3,1
	Topic: s08_place	Partition: 2	Leader: 1	Replicas: 1,2,3	Isr: 1,3
	Topic: s08_place	Partition: 3	Leader: 1	Replicas: 2,1,3	Isr: 1,3
	Topic: s08_place	Partition: 4	Leader: 3	Replicas: 3,2,1	Isr: 3,1
	Topic: s08_place	Partition: 5	Leader: 1	Replicas: 1,3,2	Isr: 1,3
```

`|Isr| < |Replicas|` 인 파티션이 전부 나옵니다. 주목할 것은 **`s08_place` 의 파티션 0 과 3 입니다.** `Replicas: 2,3,1` 인데 `Leader: 3` 이고, `Replicas: 2,1,3` 인데 `Leader: 1` 입니다. **kafka-2 가 리더였던 파티션들은 리더가 옮겨 갔습니다.**

여기서 중요한 차이가 드러납니다.

- **ISR 축소는 30초 걸립니다.** 시간 기준 판정이기 때문입니다.
- **리더 선출은 즉시입니다.** KRaft 컨트롤러가 브로커 세션 종료를 감지하는 순간(수백 ms) 그 브로커가 리더인 파티션들의 리더를 ISR 안의 다른 리플리카로 넘깁니다.

즉 "리더가 죽었을 때"는 몇 백 밀리초의 중단이고, "팔로워가 죽었을 때"는 애초에 중단이 없습니다. 30초는 **ISR 목록이 정리되는 데 걸리는 시간**일 뿐입니다.

> 💡 **실무 팁 — `UnderReplicatedPartitions` 는 알람 1순위 지표입니다**
> JMX 의 `kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions` 는 정상 상태에서 **항상 0** 이어야 합니다.
> 0 이 아닌 상태가 몇 분 이상 지속되면 브로커가 죽었거나, 디스크가 느려 복제가 못 따라가는 중입니다.
> `UnderMinIsrPartitionCount` 는 더 심각합니다. **그 파티션은 `acks=all` 쓰기가 이미 거부되고 있습니다**(8-7).

---

## 8-6. 되살리기 — ISR 은 돌아오지만 리더는 안 돌아온다

```bash
docker compose start kafka-2
```

**결과**

```
[+] Running 1/1
 ✔ Container kafka-2  Started                                               0.6s
```

15초쯤 기다린 뒤 확인합니다.

```bash
kt --describe --topic s08_dur
```

**결과**

```
Topic: s08_dur	TopicId: 7hK2mR9pQzOaXvL4TdWuBg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=1,segment.bytes=1048576
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,3,2
```

`Isr: 1,3,2` — 세 개가 다 돌아왔습니다. **순서가 `1,2,3` 이 아니라 `1,3,2` 인 것은 ISR 이 "복귀한 순서"로 유지되는 집합이기 때문**이며 아무 의미가 없습니다. 크기만 보면 됩니다.

브로커 로그를 봅니다.

```bash
docker logs kafka-1 --since 1m 2>&1 | grep -i 'ISR'
```

**결과**

```
[2024-03-11 10:26:14,507] INFO [Partition s08_dur-0 broker=1] Expanding ISR from 1,3 to 1,3,2 (kafka.cluster.Partition)
```

`Shrinking` 의 짝인 `Expanding` 입니다. **이 두 줄만 grep 해도 클러스터의 복제 건강 이력을 대부분 재구성할 수 있습니다.**

이제 `s08_place` 를 봅니다.

```bash
kt --describe --topic s08_place
```

**결과**

```
Topic: s08_place	TopicId: bQ8vN1sYRmqCd3XeZfHtLA	PartitionCount: 6	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576
	Topic: s08_place	Partition: 0	Leader: 3	Replicas: 2,3,1	Isr: 3,1,2
	Topic: s08_place	Partition: 1	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: s08_place	Partition: 2	Leader: 1	Replicas: 1,2,3	Isr: 1,3,2
	Topic: s08_place	Partition: 3	Leader: 1	Replicas: 2,1,3	Isr: 1,3,2
	Topic: s08_place	Partition: 4	Leader: 3	Replicas: 3,2,1	Isr: 3,1,2
	Topic: s08_place	Partition: 5	Leader: 1	Replicas: 1,3,2	Isr: 1,3,2
```

**ISR 은 전부 3개로 복구되었습니다. 그런데 리더는 안 돌아왔습니다.**

- 파티션 0: `Replicas: 2,3,1` (preferred = 2) 인데 `Leader: 3`
- 파티션 3: `Replicas: 2,1,3` (preferred = 2) 인데 `Leader: 1`

**kafka-2 는 리더를 하나도 갖고 있지 않습니다.** 리더 6개가 kafka-1 과 kafka-3 에 3개씩 몰려 있습니다. Kafka 는 리더를 자동으로 되돌리지 않습니다(브로커 설정 `auto.leader.rebalance.enable` 이 기본 `true` 이긴 하지만, `leader.imbalance.check.interval.seconds` 가 기본 **300초**라 즉시는 아닙니다. 그리고 운영에서는 이 값을 끄고 수동으로 하는 곳도 많습니다).

수동으로 되돌립니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-leader-election.sh \
  --bootstrap-server kafka-1:9092 \
  --election-type preferred --all-topic-partitions
```

**결과**

```
Successfully completed leader election (PREFERRED) for partitions s08_place-0, s08_place-3
```

**이미 preferred leader 인 파티션은 대상에서 빠집니다.** 실제로 바뀐 2개만 나옵니다.

```bash
kt --describe --topic s08_place
```

**결과**

```
Topic: s08_place	TopicId: bQ8vN1sYRmqCd3XeZfHtLA	PartitionCount: 6	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576
	Topic: s08_place	Partition: 0	Leader: 2	Replicas: 2,3,1	Isr: 3,1,2
	Topic: s08_place	Partition: 1	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: s08_place	Partition: 2	Leader: 1	Replicas: 1,2,3	Isr: 1,3,2
	Topic: s08_place	Partition: 3	Leader: 2	Replicas: 2,1,3	Isr: 1,3,2
	Topic: s08_place	Partition: 4	Leader: 3	Replicas: 3,2,1	Isr: 3,1,2
	Topic: s08_place	Partition: 5	Leader: 1	Replicas: 1,3,2	Isr: 1,3,2
```

`Leader` 가 전부 `Replicas` 의 첫 번째와 일치합니다. **리더 2개씩 균등**입니다.

> ⚠️ **함정 — 롤링 재시작 후 리더 되돌리기를 잊는다**
> 브로커 3대를 순서대로 재시작하면, 마지막에 재시작한 브로커는 **리더를 하나도 안 가진 채** 끝납니다. 앞선 두 대가 리더를 다 가져갔기 때문입니다.
> 에러는 안 납니다. 처리량도 당장은 괜찮습니다. 그런데 **브로커 두 대가 세 대 몫의 리더 트래픽을 처리**하게 됩니다.
> 트래픽이 평소의 1.5배가 되는 순간 그 두 대만 먼저 무너집니다. **원인 분석이 지독하게 어렵습니다.** 재시작은 며칠 전 일이니까요.
> **해결**: 롤링 재시작 절차의 마지막 단계에 `kafka-leader-election.sh --election-type preferred --all-topic-partitions` 를 **반드시** 넣으세요. Step 14 의 플레이북에 이 절차가 들어갑니다.

---

## 8-7. 핵심 함정 A — `acks=all` 만으로는 부족하다

Step 04 와 07 에서 `acks=all` 을 "가장 안전한 설정"으로 배웠습니다. **절반만 맞습니다.**

`acks=all` 의 정확한 뜻은 "**ISR 의 모든 리플리카**가 받았을 때 응답한다" 입니다. **ISR 이 몇 개인지는 말하지 않습니다.** ISR 이 1개면 "1개 전부가 받았다"도 `all` 입니다.

`s08_dur` 은 `min.insync.replicas=1` 로 만들었습니다. 브로커 2대를 죽여 ISR 을 1개로 만듭니다.

```bash
docker compose stop kafka-2 kafka-3
# 35초 대기
kt --describe --topic s08_dur
```

**결과**

```
Topic: s08_dur	TopicId: 7hK2mR9pQzOaXvL4TdWuBg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=1,segment.bytes=1048576
	Topic: s08_dur	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1
```

**`Isr: 1`.** 복제본이 사실상 하나뿐인 상태입니다. 여기에 `acks=all` 로 씁니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s08_dur \
  --producer-property acks=all \
  --property parse.key=true --property key.separator=: <<'EOF'
C009:{"order_id":"O-1099","customer_id":"C009","amount":51000,"status":"CREATED"}
EOF
```

**결과**

```
```

**아무 출력도 없습니다. 성공했습니다.** 프로듀서는 성공 응답을 받았고, 애플리케이션이라면 콜백에서 `RecordMetadata` 를 받았을 것입니다. "안전하게 저장됐다"고 믿을 근거를 갖게 된 것입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s08_dur --time -1
```

**결과**

```
s08_dur:0:151
```

150 → 151. 확실히 저장됐습니다. **복사본은 이 세상에 하나뿐입니다.** kafka-1 의 디스크가 지금 고장 나면 이 주문은 사라지고, 프로듀서 쪽 로그에는 "성공"만 남습니다.

### `min.insync.replicas=2` 로 바꾸면

```bash
kconf --alter --entity-type topics --entity-name s08_dur \
      --add-config min.insync.replicas=2
```

**결과**

```
Completed updating config for topic s08_dur.
```

같은 명령을 다시 실행합니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s08_dur \
  --producer-property acks=all \
  --property parse.key=true --property key.separator=: <<'EOF'
C009:{"order_id":"O-1100","customer_id":"C009","amount":22000,"status":"CREATED"}
EOF
```

**결과**

```
[2024-03-11 10:31:08,441] ERROR Error when sending message to topic s08_dur with key: 4 bytes, value: 79 bytes with error: (org.apache.kafka.clients.producer.internals.ErrorLoggingCallback)
org.apache.kafka.common.errors.NotEnoughReplicasException: The size of the current ISR Set(1) is insufficient to satisfy the min.isr requirement of 2 for partition s08_dur-0
```

**에러가 났습니다. 이게 좋은 것입니다.**

프로듀서는 실패를 알았습니다. 재시도하거나, DLQ 로 보내거나, 사용자에게 "잠시 후 다시 시도해 주세요"를 띄울 수 있습니다. **선택지가 생겼습니다.** `min.insync.replicas=1` 이었을 때는 선택지 자체가 없었습니다. 잘못됐다는 사실을 아무도 몰랐으니까요.

브로커를 되살립니다.

```bash
docker compose start kafka-2 kafka-3
```

### 조합 표 — 가용성 vs 내구성

| RF | `min.insync.replicas` | `acks` | 브로커 몇 대까지 죽어도 쓰기 가능? | 유실 위험 | 평가 |
|---:|---:|---|---|---|---|
| 3 | 1 | `0` | 3대 중 리더만 살면 | **매우 높음** (전송 확인조차 안 함) | 로그·메트릭 전용 |
| 3 | 1 | `1` | 리더만 살면 | **높음** (리더 죽으면 유실) | 권장 안 함 |
| 3 | 1 | `all` | 리더만 살면 | **높음** — 이 절의 함정 | **가장 위험한 착각** |
| 3 | **2** | `all` | **1대까지** | **낮음** (2벌 이상 보장) | ✅ **표준 권장** |
| 3 | 3 | `all` | **0대** (한 대만 죽어도 쓰기 중단) | 매우 낮음 | 가용성 손실이 큼 |
| 3 | 2 | `1` | 1대까지 | **높음** — `min.isr` 이 무시됨 | 잘못된 조합 |

두 가지를 짚어야 합니다.

**첫째, `min.insync.replicas` 는 `acks=all` 일 때만 의미가 있습니다.** `acks=1` 이면 브로커는 `min.insync.replicas` 를 아예 검사하지 않습니다. 리더가 자기 로그에 쓰는 순간 응답합니다. 표의 마지막 줄이 그래서 "잘못된 조합"입니다. **둘은 세트로 설정해야 합니다.**

**둘째, `min.insync.replicas=3` (=RF) 은 하지 마세요.** 브로커 한 대만 재시작해도 그 파티션의 쓰기가 전부 막힙니다. 롤링 재시작이 곧 장애가 됩니다. `RF=3, min.insync.replicas=2` 가 "한 대는 죽어도 되고, 데이터는 최소 2벌"이라는 균형점이고, 이 코스의 브로커 기본값이 `min.insync.replicas=2` 인 이유입니다.

> 💡 **실무 팁 — 토픽 설정이 브로커 기본값을 덮어씁니다**
> 브로커에 `min.insync.replicas=2` 를 걸어 두었어도, 토픽을 `--config min.insync.replicas=1` 로 만들면 **그 토픽만 1** 입니다.
> 이 스텝의 `s08_dur` 이 정확히 그 경우였습니다. 운영에서 "브로커 설정은 2인데 왜 유실됐지?" 의 답이 대개 이것입니다.
> 확인은 `kconf --describe --entity-type topics --entity-name <토픽>` 로 하고, **`DYNAMIC_TOPIC_CONFIG` 가 있으면 그 값이 이깁니다.**

---

## 8-8. 핵심 함정 B — unclean 리더 선출이 커밋된 데이터를 버린다

지금까지의 리더 선출은 **ISR 안에서만** 후보를 골랐습니다. 그래서 새 리더는 HW 까지의 데이터를 반드시 갖고 있었습니다. 이것을 **clean leader election** 이라고 합니다.

그럼 **ISR 이 텅 비면** 어떻게 될까요? 리더도 죽고, ISR 에 남은 팔로워도 없는 상태입니다. 두 가지 선택지가 있습니다.

1. **기다린다.** ISR 에 있던 브로커가 돌아올 때까지 그 파티션은 읽기도 쓰기도 안 됩니다. → **데이터는 안전, 가용성 상실**
2. **ISR 밖의 뒤처진 리플리카를 리더로 세운다.** 파티션은 즉시 살아납니다. 대신 그 리플리카에 없는 데이터는 **영원히 사라집니다.** → **가용성 확보, 데이터 유실**

2번이 **unclean leader election** 이며, `unclean.leader.election.enable` 로 제어합니다. **Kafka 3.7 의 기본값은 `false`(1번)** 입니다.

### 시나리오 — 단계별로

```
① 정상.  리더=1(LEO 150),  ISR={1,2},  kafka-2 는 LEO 132 로 뒤처지는 중
   ┌────────────┐  ┌────────────┐
   │ kafka-1 L  │  │ kafka-2 F  │
   │ LEO 150    │  │ LEO 132    │      HW = 132  (ISR 전원이 가진 지점)
   │ HW  132    │  │            │
   └────────────┘  └────────────┘

② kafka-2 가 30초 넘게 못 따라와 ISR 에서 축출.  ISR={1}
   → 리더 혼자 남았으므로 HW 가 LEO 까지 올라간다.  HW = 150
   → **컨슈머가 오프셋 149 까지 읽는다.**  "커밋된" 데이터다.
   ┌────────────┐  ┌────────────┐
   │ kafka-1 L  │  │ kafka-2    │  ISR 밖
   │ LEO 150    │  │ LEO 132    │      HW = 150
   │ HW  150    │  │            │
   └────────────┘  └────────────┘

③ kafka-1 이 죽는다.  ISR 이 텅 빈다.
   ┌────────────┐  ┌────────────┐
   │ kafka-1 ✗  │  │ kafka-2    │      ISR = {} (kafka-1 은 죽었고 kafka-2 는 ISR 밖)
   └────────────┘  └────────────┘

④ unclean=true → 컨트롤러가 kafka-2 를 리더로 세운다.
   ┌────────────┐  ┌────────────┐
   │ kafka-1 ✗  │  │ kafka-2 L  │      LEO = 132,  HW = 132
   └────────────┘  └────────────┘
   → **오프셋 132 ~ 149 의 18건이 사라졌다.**
   → 컨슈머는 이미 그 18건을 읽고 처리했다. 결제도 했고 메일도 보냈다.
   → 그런데 Kafka 에는 그 기록이 없다. 재처리도 대사도 불가능하다.
```

**④ 가 이 코스에서 가장 나쁜 종류의 사건입니다.** 컨슈머는 정상 동작했고, 프로듀서는 성공 응답을 받았고, 브로커는 살아 있습니다. 아무도 에러를 보지 못합니다. 그런데 18건이 없습니다.

### 실제로 재현하기

```bash
kt --create --topic s08_unclean --partitions 1 --replication-factor 2 \
   --replica-assignment 1:2 \
   --config min.insync.replicas=1 \
   --config unclean.leader.election.enable=true
```

**결과**

```
Created topic s08_unclean.
```

`--replica-assignment 1:2` 로 **kafka-1 과 kafka-2 에만** 배치했습니다. 리플리카가 2개여야 시나리오가 단순합니다.

150건을 넣고 오프셋을 기록합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s08_unclean --num-records 132 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all

docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s08_unclean --time -1
```

**결과**

```
132 records sent, 692.146 records/sec (0.13 MB/sec), 5.02 ms avg latency, 38.00 ms max latency.
s08_unclean:0:132
```

이제 **kafka-2 를 죽이고**, kafka-1 에만 18건을 더 넣습니다. ②단계 재현입니다.

```bash
docker compose stop kafka-2
# 35초 대기 — ISR 이 {1} 로 줄어들 때까지
kt --describe --topic s08_unclean
```

**결과**

```
Topic: s08_unclean	TopicId: mF4tZ7bXQvKe1LrNsPcHdA	PartitionCount: 1	ReplicationFactor: 2	Configs: min.insync.replicas=1,unclean.leader.election.enable=true,segment.bytes=1048576
	Topic: s08_unclean	Partition: 0	Leader: 1	Replicas: 1,2	Isr: 1
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s08_unclean --num-records 18 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all

docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s08_unclean --time -1
```

**결과 — BEFORE**

```
18 records sent, 900.000 records/sec (0.17 MB/sec), 2.11 ms avg latency, 9.00 ms max latency.
s08_unclean:0:150
```

**LEO = 150.** ISR 이 `{1}` 뿐이므로 HW 도 150 입니다. 컨슈머가 149 까지 읽을 수 있습니다 — 확인해 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s08_unclean \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1
```

**결과**

```
Processed a total of 150 messages
```

**150건 전부 읽혔습니다. 커밋된 데이터입니다.**

이제 ③④ 를 재현합니다. **kafka-1 을 죽이고 kafka-2 를 살립니다.**

```bash
docker compose stop kafka-1
docker compose start kafka-2
```

30초쯤 기다린 뒤 — 이번에는 별칭을 못 쓰므로 kafka-3 을 경유합니다.

```bash
docker exec kafka-3 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-3:9092 --describe --topic s08_unclean
```

**결과**

```
Topic: s08_unclean	TopicId: mF4tZ7bXQvKe1LrNsPcHdA	PartitionCount: 2	ReplicationFactor: 2	Configs: min.insync.replicas=1,unclean.leader.election.enable=true,segment.bytes=1048576
	Topic: s08_unclean	Partition: 0	Leader: 2	Replicas: 1,2	Isr: 2
```

**`Leader: 2`.** ISR 밖에 있던 kafka-2 가 리더가 되었습니다. 컨트롤러 로그를 봅니다.

```bash
docker logs kafka-3 --since 2m 2>&1 | grep -i 'unclean'
```

**결과**

```
[2024-03-11 10:41:22,196] INFO [Controller 3] Unclean leader election is enabled for topic s08_unclean; electing broker 2 as leader for partition s08_unclean-0 despite it not being in the ISR. (org.apache.kafka.controller.ReplicationControlManager)
```

오프셋을 다시 잽니다.

```bash
docker exec kafka-3 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-3:9092 --topic s08_unclean --time -1
```

**결과 — AFTER**

```
s08_unclean:0:132
```

**150 → 132. 18건이 증발했습니다.**

```bash
docker exec kafka-3 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-3:9092 --topic s08_unclean \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1
```

**결과**

```
Processed a total of 132 messages
```

**150건 → 132건.** 방금 전까지 읽을 수 있었던 18건이 없습니다.

kafka-1 을 되살려도 돌아오지 않습니다. 새 리더의 로그가 진실이 되었으므로, kafka-1 은 자기 로그의 132 이후를 **잘라내고**(log truncation) kafka-2 를 따라갑니다.

```bash
docker compose start kafka-1
# 30초 대기
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s08_unclean --time -1
```

**결과**

```
s08_unclean:0:132
```

```bash
docker logs kafka-1 --since 1m 2>&1 | grep -i 'truncat'
```

**결과**

```
[2024-03-11 10:43:55,018] INFO [UnifiedLog partition=s08_unclean-0, dir=/var/lib/kafka/data] Truncating to offset 132 (kafka.log.UnifiedLog)
```

**`Truncating to offset 132`.** 18건은 디스크에서 지워졌습니다.

> ⚠️ **함정 — `unclean.leader.election.enable=true` 는 "장애 대응"이 아니라 "데이터 포기"입니다**
> 이 설정을 켜는 논리는 늘 그럴듯합니다. "브로커가 다 죽어도 서비스는 살아 있어야 한다."
> 그런데 실제로 일어나는 일은 **이미 컨슈머가 처리한 데이터가 Kafka 에서 사라지는 것**입니다. 재처리도, 대사도, 원인 추적도 불가능합니다.
> 결제·주문·정산처럼 **한 건이라도 틀리면 안 되는 데이터**에서는 절대 켜지 마세요.
> Kafka 는 0.11 까지 기본값이 `true` 였고, **1.0 부터 `false` 로 바꿨습니다.** 3.7 도 `false` 입니다. **그대로 두세요.**
>
> **반대급부를 정직하게 말하면**: `false` 면 ISR 이 비었을 때 그 파티션은 **읽기도 쓰기도 완전히 멈춥니다.** 프로듀서는
> `org.apache.kafka.common.errors.TimeoutException: Topic s08_unclean not present in metadata after 60000 ms.` 로 죽고, 컨슈머는 무한 대기합니다.
> **이 정지 상태가 유실보다 낫다**는 것이 Kafka 의 기본 판단이며, 대부분의 비즈니스에서 옳습니다. 로그 수집처럼 유실이 허용되는 토픽만 예외로 검토하세요.
> 어느 쪽이든, RF=3 + `min.insync.replicas=2` 면 **애초에 ISR 이 빌 확률 자체가 극히 낮습니다.** 진짜 해결책은 unclean 설정이 아니라 복제 설계입니다.

---

## 8-9. 복제 성능과 rack awareness

복제가 못 따라가면(=ISR 이 자꾸 줄면) 볼 설정은 세 개입니다.

| 설정 | 기본값 | 의미 | 언제 올립니까 |
|---|---:|---|---|
| `num.replica.fetchers` | `1` | 브로커가 **리더 브로커 하나당** 굴리는 fetcher 스레드 수 | 파티션이 많고(수백~수천) CPU 여유가 있을 때. 2~4 가 흔한 값 |
| `replica.fetch.max.bytes` | `1048576` (1MiB) | fetch 응답의 **파티션당** 최대 크기 | `max.message.bytes` 를 키웠을 때 **반드시 함께** 키워야 함 |
| `replica.fetch.response.max.bytes` | `10485760` (10MiB) | fetch 응답 **전체** 최대 크기 | 위와 함께 |

> ⚠️ **함정 — `max.message.bytes` 만 올리면 그 파티션의 복제가 영원히 멈춥니다**
> 토픽의 `max.message.bytes` 를 5MiB 로 올리고 브로커의 `replica.fetch.max.bytes` 를 1MiB 로 두면,
> 5MiB 메시지가 들어오는 순간 **팔로워가 그 레코드를 가져올 수 없습니다.** 무한 재시도하다 ISR 에서 빠집니다.
> 리더에는 데이터가 있고 프로듀서는 성공했는데(min.isr 을 만족하는 동안), 복제만 조용히 멈춰 있습니다.
> **해결**: `max.message.bytes` ≤ `replica.fetch.max.bytes` 를 항상 유지하세요. 셋을 함께 올리는 것이 원칙입니다.

### rack awareness

`broker.rack` 을 설정하면 리플리카 배치가 **랙(또는 AZ)을 가로지르도록** 강제됩니다.

```yaml
# docker-compose.yml 예시 — 이 코스는 설정하지 않았습니다
KAFKA_BROKER_RACK: az-a     # kafka-1
KAFKA_BROKER_RACK: az-b     # kafka-2
KAFKA_BROKER_RACK: az-c     # kafka-3
```

랙이 3개이고 RF=3 이면 **리플리카 3개가 서로 다른 랙에 하나씩** 놓입니다. 랙 하나가 통째로 죽어도 파티션마다 2벌이 남으므로 `min.insync.replicas=2` 를 만족합니다. AWS 라면 `broker.rack` 에 AZ 이름을 넣는 것이 표준입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server kafka-1:9092 | grep -E '^kafka-[0-9]'
```

**결과** (이 코스는 rack 미설정이므로 `rack: null`)

```
kafka-1:9092 (id: 1 rack: null) -> (
kafka-2:9092 (id: 2 rack: null) -> (
kafka-3:9092 (id: 3 rack: null) -> (
```

> 💡 **실무 팁 — rack awareness 의 대가는 AZ 간 네트워크 비용입니다**
> 복제 트래픽이 반드시 AZ 를 넘어갑니다. 클라우드에서 AZ 간 전송은 유료이고, 처리량이 크면 이 비용이 무시할 수 없습니다.
> 그럼에도 **AZ 하나가 통째로 나가는 사건은 실제로 일어납니다.** 비용을 내고 사는 게 맞습니다.
> 컨슈머 쪽은 Kafka 2.4 의 `client.rack` + `replica.selector.class` 로 **같은 랙의 팔로워에서 읽어(follower fetching)** 이 비용을 줄일 수 있습니다.

---

## 8-10. 장애 시나리오별 대응 표

| 상황 | Kafka 의 자동 동작 | 프로듀서 | 컨슈머 | 운영자가 할 일 |
|---|---|---|---|---|
| **팔로워 1대 down** | 30초 뒤 ISR 축소. 리더 유지 | `acks=all` 은 계속 성공 (ISR 2개 ≥ min.isr 2) | 영향 없음 | 브로커 복구. `Expanding ISR` 로그 확인 |
| **리더 down** | 즉시(수백 ms) ISR 내 다른 리플리카가 리더로 | 수백 ms 재시도 후 성공. `NOT_LEADER_OR_FOLLOWER` 는 클라이언트가 자동 처리 | 메타데이터 갱신 후 새 리더로 재접속 | 복구 후 **preferred leader election 필수** |
| **ISR 이 min.isr 미만으로 축소** | 쓰기 거부 | `NotEnoughReplicasException` — **명확히 실패** | **읽기는 계속 됨** (HW 까지) | 브로커 복구가 유일한 해법. 절대 `min.insync.replicas` 를 낮춰 급한 불을 끄지 말 것 |
| **ISR 이 빈다 (unclean=false)** | 파티션 정지 | `TimeoutException` | 무한 대기 | ISR 에 있던 브로커를 되살린다. 그 디스크가 죽었다면 `kafka-leader-election.sh --election-type unclean` 을 **의식적으로** 선택 |
| **ISR 이 빈다 (unclean=true)** | 뒤처진 리플리카가 리더로 | 성공 (조용히) | 성공 (조용히) | **유실 구간을 파악해 상류에서 재전송.** 사후 대응만 가능 |
| **전 브로커 down** | 아무것도 안 됨 | `TimeoutException` | 무한 대기 | 순서대로 기동. **모두 healthy 확인 후** under-replicated 가 0 이 될 때까지 대기 |
| **디스크 full** | 브로커가 로그 디렉터리를 offline 처리 | 해당 파티션 실패 | 해당 파티션 실패 | 보존 정책 점검 (Step 09). 디스크 증설 |

> 💡 **실무 팁 — 급할 때 `min.insync.replicas` 를 1로 낮추는 유혹**
> ISR 부족으로 쓰기가 막히면 가장 빠른 "해결책"은 `min.insync.replicas=1` 로 낮추는 것입니다. 즉시 쓰기가 재개됩니다.
> 그리고 **그 순간부터 들어오는 모든 데이터가 복사본 1벌**입니다. 장애 중이라 그 1대가 죽을 확률이 평소보다 높은 시점에요.
> 정말 낮춰야 한다면 **그 결정을 기록하고, 브로커 복구 직후 반드시 되돌리세요.** 되돌리는 걸 잊은 토픽이 몇 달 뒤 사고를 냅니다.

---

## 8-11. 정리 (실습 마무리)

이 스텝의 토픽을 지우고, **모든 브로커가 살아 있는지 반드시 확인**합니다. 이 확인을 건너뛰면 Step 09 의 압축 실습이 이상하게 동작합니다.

```bash
kt --delete --topic s08_dur
kt --delete --topic s08_place
kt --delete --topic s08_unclean
kt --list | grep '^s08_' || echo 'OK: s08_ 토픽 없음'
```

**결과**

```
OK: s08_ 토픽 없음
```

**브로커 3대 확인 — 이 절차를 생략하지 마세요.**

```bash
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
```

**결과**

```
NAME       STATUS
kafka-1    Up 3 minutes (healthy)
kafka-2    Up 5 minutes (healthy)
kafka-3    Up 8 minutes (healthy)
kafka-ui   Up 24 minutes
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server kafka-1:9092 | grep -cE '^kafka-[0-9]'
```

**결과**

```
3
```

**`3` 이 나와야 합니다.** 마지막으로 under-replicated 파티션이 하나도 없는지 봅니다.

```bash
kt --describe --under-replicated-partitions
```

**결과** (빈 출력이 정상)

```
```

빈 출력이면 클러스터가 완전히 건강합니다. 뭔가 나오면 30초쯤 더 기다렸다 다시 확인하세요. 브로커를 막 살렸다면 복제 따라잡기가 진행 중입니다.

> ⚠️ 혹시 `docker compose stop kafka-1` 상태로 실습을 끝냈다면 `kt` 별칭 자체가 동작하지 않습니다.
> `docker compose up -d` 로 전부 살린 뒤 위 절차를 다시 실행하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 복제 단위 | 토픽이 아니라 **파티션**. RF=3 이면 리더 1 + 팔로워 2 |
| 팔로워의 동작 | 리더가 push 하지 않는다. 팔로워가 **fetch 로 당겨온다**(=특별한 컨슈머) |
| Replicas | **순서 있는 목록**. 첫 번째가 **preferred leader** |
| 배치 규칙 | 첫 리플리카는 라운드로빈, 나머지는 shift 적용 → 리더·팔로워 부하 분산 |
| LEO / HW | LEO = 가진 마지막+1, HW = **ISR 전원이 복제 완료**한 지점 |
| 컨슈머 가시성 | **HW 미만만 보인다.** 리더가 죽어도 읽은 데이터가 사라지지 않게 하기 위해 |
| `--time -1` | LEO 가 아니라 **HW** 를 반환한다 |
| ISR | 리더를 따라잡은 리플리카 집합. 기준은 **시간 하나뿐**(`replica.lag.time.max.ms`=30000) |
| ISR 축소 vs 리더 선출 | 축소는 **30초**, 리더 선출은 **즉시**(수백 ms) |
| 브로커 로그 | `Shrinking ISR from 1,2,3 to 1,3` / `Expanding ISR from 1,3 to 1,3,2` |
| 리더 복귀 | ISR 은 자동 복구되지만 **리더는 자동으로 안 돌아온다** → `kafka-leader-election.sh --election-type preferred` |
| **함정 A** | `acks=all` + `min.insync.replicas=1` = **ISR 1개짜리 성공**. 유실 위험을 아무도 모른다 |
| 권장 조합 | **RF=3 + `min.insync.replicas=2` + `acks=all`.** 1대 장애 허용, 2벌 이상 보장 |
| `min.isr`=RF | 한 대만 죽어도 쓰기 중단. **하지 말 것** |
| **함정 B** | `unclean.leader.election.enable=true` → 뒤처진 리플리카가 리더가 되며 **커밋된 구간이 사라짐**(150 → 132, 18건) |
| unclean 의 대가 | `false` 면 ISR 이 빌 때 파티션 **완전 정지**. Kafka 는 정지가 유실보다 낫다고 판단 (1.0 부터 기본 false) |
| log truncation | 옛 리더가 돌아오면 자기 로그를 **잘라내고** 새 리더를 따른다 (`Truncating to offset 132`) |
| 복제 성능 | `num.replica.fetchers`, `replica.fetch.max.bytes` ≥ `max.message.bytes` |
| rack awareness | `broker.rack` 으로 리플리카를 AZ 에 분산. 대가는 AZ 간 네트워크 비용 |
| 알람 지표 | `UnderReplicatedPartitions` = 0, `UnderMinIsrPartitionCount` = 0 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`. **브로커를 실제로 죽여 가며** 푸세요.

1. `--describe` 출력만 보고 preferred leader 와 현재 리더가 어긋난 파티션 찾아내기
2. 브로커 하나를 죽이고 ISR 이 줄어드는 데 걸리는 시간을 **초 단위로 측정**하기
3. `min.insync.replicas=1` 토픽과 `2` 토픽에 같은 조건으로 쓰고 결과를 비교하기
4. `NotEnoughReplicasException` 을 **의도적으로** 발생시키는 최소 조건 만들기
5. under-replicated 파티션 목록을 뽑아 어느 브로커가 문제인지 판정하기
6. 롤링 재시작을 흉내 내고 리더 쏠림을 preferred election 으로 해소하기
7. `unclean.leader.election.enable=true` 로 유실 건수를 **숫자로** 재현하기

---

## 다음 단계

복제는 "데이터를 몇 벌 갖고 있을 것인가"의 문제였습니다. 이제 반대 방향, **"데이터를 언제 버릴 것인가"** 를 봅니다.
Kafka 는 컨슈머가 읽었다고 메시지를 지우지 않습니다. 지우는 기준은 오직 보존 정책이며, 그 정책이 **세그먼트 단위로만** 작동한다는 사실이 수많은 오해의 근원입니다.
`cleanup.policy=compact` 로 같은 키의 옛 값이 사라지고 오프셋에 구멍이 생기는 것, tombstone 으로 키를 완전히 지우는 것까지 직접 관찰합니다.

→ [Step 09 — 보존과 로그 압축](../step-09-retention-compaction/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. 세 파일 모두 `docker compose stop` 으로 **브로커를 실제로 죽이므로**, 다른 스텝의 실습과 동시에 돌리지 마세요. 세 파일 다 마지막에 `docker compose start` 로 전부 되살리고 브로커 3대를 검증하는 블록으로 끝나지만, 중간에 `Ctrl-C` 로 멈추면 브로커가 죽은 채 남습니다. 그럴 때는 `docker compose up -d` 로 복구한 뒤 다시 시작하세요.

### practice.sh

본문 8-0 ~ 8-11 의 모든 명령을 절 번호 주석과 함께 담은 실습 스크립트입니다.

- 상단의 `K() { docker exec "$1" /opt/kafka/bin/"${@:2}"; }` 헬퍼로 **어느 브로커를 경유할지 첫 인자로 지정**합니다. 8-8 에서 `kafka-1` 을 죽인 뒤에는 `kafka-3` 을 경유해야 하기 때문에 이 형태가 필요합니다. `kt` 같은 고정 별칭으로는 그 구간을 못 돌립니다.
- `[8-5]` 구간은 `sleep 0` / `sleep 10` / `sleep 25` 로 **시간순 스냅샷 3개**를 찍습니다. 본문의 "즉시 / 10초 후 / 35초 후" 출력이 이것입니다. `replica.lag.time.max.ms=30000` 을 눈으로 확인하는 구간이므로 sleep 을 줄이지 마세요.
- `[8-7]` 은 `min.insync.replicas` 를 **1 → 2 로 바꾸기 전후로 같은 프로듀서 명령을 두 번** 실행합니다. 첫 번째는 조용히 성공하고, 두 번째는 `NotEnoughReplicasException` 으로 실패합니다. 이 대비가 함정 A 의 전부입니다.
- `[8-8]` 이 가장 깁니다. `--replica-assignment 1:2` 로 kafka-1·kafka-2 에만 배치한 `s08_unclean` 을 만들고, 132건 → (kafka-2 정지) → 18건 → (kafka-1 정지, kafka-2 기동) 순서로 진행합니다. `BEFORE_LEO` / `AFTER_LEO` 변수에 오프셋을 담아 **마지막에 `유실: N건` 을 직접 출력**합니다.
- `WAIT_ISR=35` 변수가 파일 상단에 있습니다. 노트북이 느려 ISR 축소가 35초 안에 안 끝나면 이 값을 45~60 으로 올리세요. 스크립트 곳곳의 대기가 전부 이 변수를 참조합니다.
- 마지막 `[8-11]` 블록이 `s08_` 토픽 3개를 삭제하고, `docker compose start` 로 전 브로커를 살린 뒤, `kafka-broker-api-versions.sh | grep -c` 가 **3 이 아니면 `exit 1`** 로 죽습니다. 클러스터가 깨진 채 다음 스텝으로 넘어가는 것을 막기 위한 안전장치입니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·5** 는 `--describe` 출력을 **읽는** 문제입니다. 스크립트가 토픽과 상황을 미리 만들어 주고, 여러분은 `awk` 나 `grep` 으로 조건에 맞는 줄을 골라내면 됩니다. 정답은 명령 한 줄이 아니라 **판정 기준**입니다.
- **문제 2** 는 `date +%s` 로 시작 시각을 찍고 `Isr` 필드의 원소 개수가 3에서 2로 바뀌는 순간까지 폴링해 **초 단위 실측값**을 출력하는 문제입니다. 30초 근처가 나와야 정상이며, 크게 다르면 `replica.lag.time.max.ms` 가 기본값이 아닌지 확인해 보라는 힌트가 달려 있습니다.
- **문제 3·4** 는 `s08ex_isr1` / `s08ex_isr2` 두 토픽을 문제지 쪽에서 미리 만들어 줍니다. `min.insync.replicas` 만 다르고 나머지는 동일한 쌍이라, 여러분이 채워 넣을 것은 **같은 프로듀서 명령 하나**뿐입니다. 결과가 갈리는 이유를 설명하는 것이 진짜 문제입니다.
- **문제 6** 은 `docker compose restart kafka-2 && sleep 20 && docker compose restart kafka-3` 로 롤링 재시작을 흉내 냅니다. 재시작 후 리더가 어디로 쏠렸는지 세어 보고, preferred election 전후의 **브로커별 리더 개수**를 출력하는 것이 목표입니다.
- **문제 7** 은 practice.sh 의 8-8 을 **숫자만 바꿔** 다시 하는 문제입니다(200건 + 25건). 유실 건수가 정확히 25가 나오는지 확인합니다. 이 문제만 `unclean.leader.election.enable=true` 를 씁니다.
- 파일 맨 끝에 `docker compose up -d` 와 브로커 3대 검증 블록이 있습니다. 문제를 다 못 풀고 중간에 나가더라도 **이 블록만은 실행하세요.**

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답 명령과 "왜 그 답인가"를 설명하는 긴 `# 해설:` 주석이 함께 들어 있습니다.

- **정답 1** 은 `awk` 로 `Replicas:` 다음 값의 **첫 숫자**와 `Leader:` 값을 비교합니다. 핵심은 "ISR 이 3개라도 리더가 쏠려 있을 수 있다"는 것 — under-replicated 는 0인데 리더는 불균형인 상태가 롤링 재시작 직후의 전형적인 모습입니다.
- **정답 2** 의 측정값은 **31초** 근처입니다. `replica.lag.time.max.ms=30000` 에 컨트롤러의 메타데이터 반영 시간이 1초 남짓 더해집니다. 해설은 "30초는 축소까지의 시간이지 장애 감지 시간이 아니다"를 강조하고, 리더 선출은 왜 즉시인지를 대비시켜 설명합니다.
- **정답 3·4** 는 `min.insync.replicas=2` + `acks=all` + ISR 1개가 `NotEnoughReplicasException` 의 **최소 조건 3종 세트**임을 정리합니다. 특히 `acks=1` 로 바꾸면 같은 상황에서 **에러가 사라진다**는 것을 추가 실행으로 보여 주며, `min.insync.replicas` 가 `acks=all` 없이는 무의미하다는 결론에 도달합니다.
- **정답 5** 는 `kt --describe --under-replicated-partitions` 출력에서 `Replicas` 에는 있고 `Isr` 에는 없는 브로커 ID 를 뽑아 **빈도순으로 세는** 한 줄 파이프라인입니다. 모든 줄에서 같은 ID 가 빠져 있으면 그 브로커가 범인입니다.
- **정답 6** 은 preferred election **전** 리더 분포가 `kafka-1: 4, kafka-3: 2, kafka-2: 0` 이고 **후** 가 `2,2,2` 임을 보여 줍니다. 해설은 `auto.leader.rebalance.enable` 이 기본 `true` 인데도 왜 기다리면 안 되는지(`leader.imbalance.check.interval.seconds=300`)를 설명합니다.
- **정답 7** 의 유실 건수는 **25건**이고, 해설은 `Truncating to offset 200` 로그를 함께 확인시킵니다. 마지막 문단이 이 스텝의 결론입니다 — **unclean 을 끄는 것이 답이 아니라, ISR 이 비지 않도록 RF·min.isr 을 설계하는 것이 답입니다.**
- 파일 끝의 정리 블록은 `s08ex_` 로 시작하는 토픽을 전부 삭제하고, practice.sh 와 동일한 **브로커 3대 검증**으로 마칩니다.

```bash file="./solution.sh"
```
