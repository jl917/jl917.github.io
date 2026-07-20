# Step 04 — 프로듀서

> **학습 목표**
> - 프로듀서 내부 구조(직렬화 → 파티셔너 → RecordAccumulator → Sender 스레드)를 설명하고, `send()` 가 **왜 비동기인지** 이해한다
> - `acks=0/1/all` 의 처리량과 지연을 `kafka-producer-perf-test.sh` 로 **실측한다**
> - `acks=0` 으로 보내는 중 브로커를 죽여, **에러 없이 메시지가 사라지는 것을 재현한다**
> - 3.7 의 기본 파티셔너(murmur2 해시 / sticky partitioning)를 이해하고, 키 쏠림으로 생기는 hot partition 을 오프셋으로 확인한다
> - `batch.size` / `linger.ms` / `compression.type` 을 바꿔 가며 처리량·네트워크 바이트를 **실측한다**
> - `max.in.flight.requests.per.connection > 1` + 재시도가 **같은 키의 순서를 뒤바꾸는 것을 직접 재현한다**
>
> **선행 스텝**: Step 03 — 토픽과 파티션
> **예상 소요**: 120분

---

## 4-0. 실습 준비

이 스텝 전용 토픽 세 개를 만듭니다. 전부 `s04_` 로 시작하며 4-14 에서 삭제합니다.

```bash
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
alias kcg='docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092'

kt --create --topic s04_acks  --partitions 3 --replication-factor 3
kt --create --topic s04_order --partitions 1 --replication-factor 3
kt --create --topic s04_perf  --partitions 6 --replication-factor 3 \
   --config segment.bytes=268435456 --config retention.ms=3600000
```

**결과**

```
Created topic s04_acks.
Created topic s04_order.
Created topic s04_perf.
```

`s04_order` 만 **파티션이 1개**입니다. 순서 뒤바뀜(4-11)은 한 파티션 안에서 일어나는 현상이므로, 파티션이 여러 개면 "원래 순서가 안 맞는 것"과 구별이 안 되기 때문입니다.

`s04_perf` 는 `segment.bytes` 를 256MiB 로 **토픽 단위로 덮어썼습니다.** 이 클러스터의 브로커 기본값은 학습용으로 1MiB 라서, 그대로 두면 100만 건을 넣을 때 세그먼트 파일이 수백 개 생겨 측정값이 오염됩니다.

```bash
kt --describe --topic s04_acks
```

**결과**

```
Topic: s04_acks	TopicId: 7hVqK2mXQ0iBnP4tLsYwRg	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576
	Topic: s04_acks	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: s04_acks	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: s04_acks	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
```

`min.insync.replicas=2` 가 걸려 있다는 것을 기억해 두세요. 4-5 와 Step 08 에서 이 값이 결정적인 역할을 합니다.

---

## 4-1. 프로듀서 내부 — `send()` 는 **보내지 않는다**

프로듀서를 처음 쓰는 사람이 가장 크게 오해하는 지점입니다. `producer.send(record)` 를 호출해도 **그 순간 네트워크로 나가지 않습니다.** 레코드는 메모리 버퍼에 들어가고, 별도의 백그라운드 스레드가 나중에 묶어서 보냅니다.

```
  [ 애플리케이션 스레드 ]                         [ Sender 스레드 (백그라운드, 1개) ]
        │
        │ producer.send(record, callback)
        ▼
  ┌───────────────────┐
  │  Serializer       │  key/value 를 byte[] 로
  │  (key, value)     │
  └─────────┬─────────┘
            ▼
  ┌───────────────────┐
  │  Partitioner      │  키 있으면 murmur2(key) % N
  │                   │  키 없으면 sticky (한 배치 동안 한 파티션 고정)
  └─────────┬─────────┘
            ▼
  ┌──────────────────────────────────────────┐
  │  RecordAccumulator  (buffer.memory=32MiB)│
  │                                          │
  │  s04_acks-0 : [ batch ][ batch ]         │  ← 파티션마다 배치 큐
  │  s04_acks-1 : [ batch ]                  │     배치 하나 = batch.size(16KiB)
  │  s04_acks-2 : [ batch ][ batch ][ batch ]│
  └──────────────────────────────────────────┘
            │  ▲                              배치가 꽉 찼거나(batch.size)
            │  └── 버퍼가 꽉 차면 send() 가    linger.ms 가 지나면 전송 대상
            │      max.block.ms 만큼 블로킹
            ▼
        ┌────────────────────────────────────────┐
        │  Sender 스레드                          │
        │  같은 브로커로 갈 배치들을 하나의        │
        │  ProduceRequest 로 묶어서 전송           │
        │  브로커당 최대 max.in.flight(기본 5)개   │
        └───────────────┬────────────────────────┘
                        ▼
                 ┌─────────────┐
                 │  브로커      │  acks 만큼 기다렸다가 응답
                 └──────┬──────┘
                        │ ProduceResponse
                        ▼
              콜백 실행 / Future 완료
```

여기서 나오는 결론 세 가지가 이 스텝 전체를 관통합니다.

1. **`send()` 가 성공했다는 것은 "버퍼에 넣었다"는 뜻일 뿐입니다.** 브로커가 받았다는 뜻이 아닙니다. 실제 성공/실패는 콜백이나 `Future.get()` 으로만 알 수 있습니다.
2. **`send()` 는 대부분 블로킹하지 않지만, 버퍼가 꽉 차면 블로킹합니다.** `max.block.ms`(기본 60초) 동안 기다리다 `TimeoutException` 을 던집니다.
3. **처리량은 배치가 만듭니다.** 레코드 하나씩 보내면 왕복 지연이 그대로 처리량 한계가 됩니다. `batch.size` 와 `linger.ms` 가 여기에 개입합니다(4-9).

> ⚠️ **함정 — `producer.send(record)` 만 쓰고 반환값을 버리면 실패를 영영 모릅니다**
> ```java
> producer.send(new ProducerRecord<>("orders", key, value));   // 반환값 버림, 콜백 없음
> ```
> 이 코드는 **어떤 에러도 던지지 않습니다.** 직렬화 실패나 버퍼 초과 같은 즉시 발생 예외만 던지고,
> 브로커 거절·타임아웃·재시도 소진은 전부 **조용히 사라집니다.** `close()` 시점에 로그 한 줄이 남을 뿐입니다.
> **해결**: 최소한 콜백을 붙이십시오(4-13). 콜백조차 부담이면 `send()` 를 감싸는 래퍼에서 예외를 카운터로 집계하세요.

---

## 4-2. `acks` — 무엇을 기다릴 것인가

`acks` 는 **브로커가 언제 "받았다"고 응답할지**를 정합니다. 세 값뿐입니다.

```
  acks=0                     acks=1                      acks=all (= -1)
  ─────────                  ─────────                   ─────────────────
  P ──► 리더                  P ──► 리더                   P ──► 리더
     ◄── (응답 없음)              ◄── "썼다"                    │  복제 대기
     즉시 성공 처리                리더 로컬 기록 후 응답          ├──► 팔로워1 ──┐
                                                              └──► 팔로워2 ──┤
                                                                            │
                                                            ◄── "ISR 전부 썼다" ┘
```

| `acks` | 언제 성공 응답 | 유실 위험 | 처리량 | 대표 용도 |
|---|---|---|---|---|
| `0` | **보내자마자** (응답을 안 기다림) | 브로커가 죽어도, 네트워크가 끊겨도 모름 | 최고 | 메트릭, 로그 수집 |
| `1` | 리더가 **로컬 로그에 기록**한 직후 | 리더가 복제 전에 죽으면 유실 | 중간 | 일부 이벤트 스트림 |
| `all`(`-1`) | **ISR 의 모든 복제본**이 기록한 직후 | `min.insync.replicas` 를 함께 걸면 사실상 없음 | 낮음 | 주문·결제 등 금전 |

> 💡 **`acks=all` 은 "모든 복제본"이 아니라 "현재 ISR"입니다**
> RF=3 이라도 팔로워 하나가 뒤처져 ISR 에서 빠지면 ISR 은 2 가 되고, `acks=all` 은 **2개만 확인하고 성공**합니다.
> ISR 이 1까지 줄면 복제본 1개짜리 성공이 됩니다. 그래서 `min.insync.replicas=2` 를 **반드시 함께** 걸어야 합니다.
> 이 조합이 없으면 `acks=all` 은 이름값을 못 합니다. Step 08 에서 ISR 을 실제로 줄여 가며 확인합니다.

---

## 4-3. acks 실측 — 처리량과 지연

`kafka-producer-perf-test.sh` 로 같은 조건에서 `acks` 만 바꿔 100만 건을 넣습니다. 레코드 크기 100바이트, 스로틀 없음(`--throughput -1`).

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 1000000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=0
```

**결과**

```
389421 records sent, 77884.2 records/sec (7.43 MB/sec), 12.4 ms avg latency, 214.0 ms max latency.
597130 records sent, 119426.0 records/sec (11.39 MB/sec), 3.1 ms avg latency, 41.0 ms max latency.
1000000 records sent, 118168.4 records/sec (11.27 MB/sec), 5.28 ms avg latency, 214.00 ms max latency, 3 ms 50th, 11 ms 95th, 28 ms 99th, 96 ms 99.9th.
```

```bash
# acks=1
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 1000000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=1
```

**결과**

```
1000000 records sent, 92408.7 records/sec (8.81 MB/sec), 172.35 ms avg latency, 611.00 ms max latency, 158 ms 50th, 322 ms 95th, 447 ms 99th, 588 ms 99.9th.
```

```bash
# acks=all
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 1000000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all
```

**결과**

```
1000000 records sent, 51203.4 records/sec (4.88 MB/sec), 388.12 ms avg latency, 883.00 ms max latency, 371 ms 50th, 702 ms 95th, 806 ms 99th, 869 ms 99.9th.
```

정리하면 이렇습니다.

| `acks` | 처리량 | 대비 | 평균 지연 | p99 지연 | 최대 지연 |
|---|---:|---:|---:|---:|---:|
| `0` | **118,168 msg/s** | 100% | 5.28 ms | 28 ms | 214 ms |
| `1` | 92,408 msg/s | 78% | 172.35 ms | 447 ms | 611 ms |
| `all` | 51,203 msg/s | **43%** | 388.12 ms | 806 ms | 883 ms |

**`acks=0` → `acks=all` 로 가면 처리량이 2.3배 떨어지고 평균 지연은 73배 늘어납니다.** 이것이 내구성의 가격입니다.

숫자 자체보다 **비율**을 기억하세요. 하드웨어가 바뀌면 절대값은 달라지지만 "all 은 대략 0 의 절반"이라는 관계는 유지됩니다.

> 💡 **실무 팁 — `acks=all` 의 지연은 배치로 상쇄할 수 있습니다**
> `acks=all` 이 느린 이유는 왕복 대기 시간이 길어서지, 브로커가 느려서가 아닙니다.
> `linger.ms=20` 과 `batch.size=64KiB` 를 함께 주면 왕복 횟수 자체가 줄어 처리량이 회복됩니다(4-9 에서 실측).
> **"내구성을 포기해서 처리량을 얻는다"는 선택은 대개 마지막 수단입니다.** 먼저 배치를 키우세요.

---

## 4-4. ⚠️ 핵심 함정 A — `acks=0` 은 브로커가 죽어도 성공한다

말로는 다들 압니다. 직접 보면 다릅니다. `acks=0` 으로 계속 보내면서 **브로커를 죽여 봅니다.**

먼저 파티션 0 의 리더를 확인합니다.

```bash
kt --describe --topic s04_acks | grep 'Partition: 0'
```

**결과**

```
	Topic: s04_acks	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

이제 **터미널 A** 에서 `acks=0` 으로 초당 2000건씩 60초 동안 보냅니다.

```bash
# [터미널 A]
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_acks --num-records 120000 --record-size 100 --throughput 2000 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=0
```

**터미널 B** 에서 10초 뒤 브로커 두 대를 내립니다. `s04_acks` 는 RF=3 이므로 두 대를 내려야 일부 파티션이 확실히 쓰기 불가 상태가 됩니다.

```bash
# [터미널 B]
docker compose stop kafka-2 kafka-3
```

**터미널 A 의 결과**

```
10015 records sent, 2003.0 records/sec (0.19 MB/sec), 1.2 ms avg latency, 18.0 ms max latency.
10000 records sent, 2000.0 records/sec (0.19 MB/sec), 1.1 ms avg latency, 14.0 ms max latency.
10000 records sent, 2000.0 records/sec (0.19 MB/sec), 1.4 ms avg latency, 33.0 ms max latency.
10000 records sent, 2000.0 records/sec (0.19 MB/sec), 1.1 ms avg latency, 12.0 ms max latency.
...
120000 records sent, 1999.8 records/sec (0.19 MB/sec), 1.21 ms avg latency, 33.00 ms max latency, 1 ms 50th, 2 ms 95th, 4 ms 99th, 12 ms 99.9th.
```

**에러가 한 줄도 없습니다.** 처리량도 그대로 2000/s 입니다. 브로커 두 대가 죽어 있는 동안에도 프로듀서는 아무 일 없다는 듯 "120000 records sent" 라고 보고했습니다.

브로커를 되살리고 **실제로 몇 건이 저장됐는지** 셉니다.

```bash
docker compose start kafka-2 kafka-3
sleep 30

docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s04_acks
```

**결과**

```
s04_acks:0:41883
s04_acks:1:22104
s04_acks:2:21993
```

합계 **85,980건.** 120,000건을 보냈고 프로듀서는 전부 성공했다고 보고했는데, **34,020건이 없습니다.** 유실률 28.4% 입니다.

> ⚠️ **함정 A — `acks=0` 에서 "sent" 는 "저장됨"이 아니라 "소켓에 썼음"입니다**
> `acks=0` 의 프로듀서는 응답을 **기다리지 않으므로**, 브로커가 그 요청을 처리했는지 알 방법이 없습니다.
> TCP 소켓에 write 가 성공했으면 성공으로 칩니다. 브로커가 죽어 연결이 끊기면 그때서야 재연결을 시도하는데,
> **끊긴 연결에 이미 실려 보낸 배치는 재시도 대상이 아닙니다.** `retries` 를 아무리 크게 잡아도 소용없습니다.
> **해결**: 유실이 허용되지 않는 토픽에는 `acks=0` 을 쓰지 마십시오. 예외는 없습니다.
> "메트릭이니까 좀 잃어도 된다" 처럼 **유실이 설계에 반영된 경우**에만 쓰는 값입니다.

> 💡 이 실습의 유실률(28.4%)은 브로커를 언제 죽였는지, 재연결이 얼마나 빨랐는지에 따라 크게 흔들립니다.
> 여러분 환경에서는 15%일 수도 40%일 수도 있습니다. **중요한 건 0% 가 아니라는 것**입니다.

---

## 4-5. ⚠️ 핵심 함정 B — `acks=1` 은 리더가 복제 전에 죽으면 유실된다

`acks=1` 은 "리더가 자기 로그에 썼다"까지만 확인합니다. 그 직후 리더가 죽으면 어떻게 될까요.

```
  프로듀서            리더(브로커1)        팔로워(브로커2)      팔로워(브로커3)
     │                    │                   │                  │
     │─ ProduceRequest ──►│                   │                  │
     │   offset 150       │                   │                  │
     │                    │ 로컬 로그 기록      │                  │
     │                    │ (offset 150)      │                  │
     │◄── "성공" ─────────│                   │                  │
     │                    │                   │                  │
     │  ✅ 콜백 실행:      │  ✱ 브로커1 크래시  │                  │
     │  "150 에 저장됨"    │  ✱ (복제 전)      │                  │
     │                    ✗                   │                  │
     │                                        │                  │
     │                       컨트롤러가 새 리더 선출 ──────────────►│
     │                                        │  브로커2 가 리더    │
     │                                        │  브로커2 의 마지막   │
     │                                        │  오프셋은 149      │
     │                                        │                   │
     │─ 다음 ProduceRequest ─────────────────►│                   │
     │                       새 리더는 150 부터 다시 씁니다.        │
     │                       원래 150 은 **덮어써지고 사라집니다.** │
```

프로듀서는 **이미 성공 콜백을 받았습니다.** 애플리케이션은 "주문 저장 완료" 로그를 남겼고, 어쩌면 사용자에게 완료 화면까지 보여 줬습니다. 그런데 그 메시지는 어디에도 없습니다.

| 항목 | `acks=1` | `acks=all` + `min.insync.replicas=2` |
|---|---|---|
| 성공 응답 시점 | 리더 로컬 기록 직후 | ISR 전체 기록 직후 |
| 리더 크래시 시 | **직전 메시지 유실 가능** | 최소 2개 복제본에 있으므로 안전 |
| ISR 이 1로 줄면 | 그대로 성공 | `NotEnoughReplicasException` 으로 **거절** |
| 유실 알림 | **없음** (이미 성공 콜백을 줬음) | 거절 예외로 명시적 실패 |

> ⚠️ **함정 B — `acks=1` 의 유실은 프로듀서가 절대 알 수 없습니다**
> 프로듀서가 성공 응답을 받은 뒤에 벌어지는 일이므로, 재시도할 방법이 없습니다.
> 로그에도 남지 않습니다. **알아채는 시점은 며칠 뒤 정산이 안 맞을 때입니다.**
> **해결**: `acks=all` + `min.insync.replicas=2` + `replication.factor=3`. 이 셋은 세트입니다.
> 하나라도 빠지면 나머지 둘이 무력해집니다.

이 시나리오를 **실제로 브로커를 죽여 가며 재현**하는 것은 [Step 08 — 복제와 내구성](../step-08-replication/) 에서 합니다.
거기서 `acks=1` 로 보낸 메시지의 오프셋을 기록해 두고, 리더를 죽인 뒤 그 오프셋에 **다른 메시지**가 들어 있는 것을 확인합니다.

---

## 4-6. 파티셔너 — 어느 파티션으로 갈지 정하는 규칙

`ProducerRecord` 에 파티션 번호를 직접 지정하지 않으면 파티셔너가 정합니다. Kafka 3.7 의 규칙은 이렇습니다.

```
  record.partition() 이 지정됨?  ── yes ──► 그 파티션 (파티셔너 무시)
             │ no
             ▼
  partitioner.class 가 설정됨?   ── yes ──► 그 클래스의 partition() 호출
             │ no (기본값 = null)
             ▼
  키가 null 이 아니고
  partitioner.ignore.keys=false? ── yes ──► murmur2(keyBytes) & 0x7fffffff % numPartitions
             │ no
             ▼
      Sticky Partitioning
      (배치가 찰 때까지 한 파티션에 몰아 넣고, 배치가 나가면 다음 파티션으로)
```

핵심을 정확히 짚습니다.

- **`partitioner.class` 의 기본값은 `null` 입니다.** 클래스 이름이 아니라 `null` 입니다. `null` 일 때 프로듀서는 위 내장 로직을 씁니다.
- **`UniformStickyPartitioner` 와 `DefaultPartitioner` 는 3.3 부터 deprecated 되었습니다.** 명시적으로 지정하면 동작은 하지만 경고가 뜹니다. 3.3 의 KIP-794 가 sticky 로직을 프로듀서 본체로 옮기면서 이 클래스들이 불필요해졌기 때문입니다.
- **키가 있으면 `murmur2` 해시입니다.** Java 의 `String.hashCode()` 가 아닙니다. 그래서 언어가 다른 클라이언트끼리도 같은 키가 같은 파티션으로 갑니다(librdkafka 도 murmur2 를 씁니다).
- **`partitioner.ignore.keys=true`(기본 false)** 로 두면 키가 있어도 무시하고 sticky 로 분산합니다. 키를 "식별자"로만 쓰고 라우팅에는 쓰고 싶지 않을 때입니다.

### Sticky partitioning 이 왜 생겼는가

키가 없을 때 옛날(2.4 이전) 기본 동작은 **라운드로빈**이었습니다. 레코드를 파티션 0, 1, 2, 0, 1, 2... 로 돌려 가며 넣습니다. 문제는 배치입니다.

```
  라운드로빈:  P0 [r1][r4][r7]  ← 배치 3개가 각각 1/3 만 참
              P1 [r2][r5][r8]     → 작은 요청 3개를 보냄
              P2 [r3][r6][r9]

  Sticky:     P0 [r1 r2 r3 r4 r5 r6 r7 r8 r9]  ← 배치 1개가 꽉 참
              P1 (다음 배치부터)                  → 큰 요청 1개를 보냄
              P2
```

배치가 클수록 요청 수가 줄고, 압축률이 좋아지고, 지연이 낮아집니다. **분포는 배치 단위로 보면 여전히 균등합니다.** 레코드 단위로 균등하지 않을 뿐입니다.

### 커스텀 파티셔너

특정 키를 전용 파티션으로 보내야 할 때가 있습니다. 예를 들어 VIP 고객 주문을 파티션 0 에 몰아 두고 그 파티션만 별도 컨슈머로 빠르게 처리하는 식입니다.

```java
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.utils.Utils;
import java.util.List;
import java.util.Map;

public class VipPartitioner implements Partitioner {

    private static final List<String> VIP = List.of("C001", "C002");

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int numPartitions = cluster.partitionsForTopic(topic).size();
        if (keyBytes == null) {
            return 0;
        }
        String k = (String) key;
        if (VIP.contains(k)) {
            return 0;                                  // VIP 는 항상 파티션 0
        }
        // 나머지는 1..N-1 사이에 murmur2 로 분산
        int rest = numPartitions - 1;
        return 1 + (Utils.toPositive(Utils.murmur2(keyBytes)) % rest);
    }

    @Override public void close() { }
    @Override public void configure(Map<String, ?> configs) { }
}
```

```properties
partitioner.class=com.example.VipPartitioner
```

> ⚠️ **커스텀 파티셔너를 쓰면 파티션 수를 늘릴 수 없게 되는 경우가 많습니다**
> 위 코드는 `numPartitions` 를 런타임에 읽으므로 파티션이 3→6 으로 늘면 **모든 키의 목적지가 바뀝니다.**
> 같은 `customer_id` 의 옛 주문과 새 주문이 다른 파티션에 흩어지고, **순서 보장이 그 순간 깨집니다.**
> Step 03 에서 다룬 문제가 커스텀 파티셔너에서도 똑같이 재현됩니다.
> **해결**: 파티션 수를 늘릴 계획이 있으면 커스텀 파티셔너에 **고정 상수**(예: `% 12`)를 쓰고 실제 파티션은 12의 약수로 운영하거나, 애초에 기본 파티셔너를 쓰십시오.

---

## 4-7. 키 라우팅 실습 — 같은 키는 항상 같은 파티션

콘솔 프로듀서로 키를 붙여 넣고, 컨슈머로 파티션 번호를 확인합니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s04_acks \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
C002:{"order_id":"O-1002","customer_id":"C002","amount":12000,"status":"CREATED"}
C003:{"order_id":"O-1003","customer_id":"C003","amount":58000,"status":"CREATED"}
C001:{"order_id":"O-1004","customer_id":"C001","amount":7000,"status":"CREATED"}
C002:{"order_id":"O-1005","customer_id":"C002","amount":31000,"status":"CREATED"}
C001:{"order_id":"O-1006","customer_id":"C001","amount":25000,"status":"CREATED"}
EOF
```

`--property print.partition=true` 로 파티션을 함께 출력합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s04_acks --from-beginning \
  --max-messages 6 --timeout-ms 15000 \
  --property print.key=true --property print.partition=true \
  --property print.value=false
```

**결과**

```
Partition:2	C001
Partition:2	C001
Partition:2	C001
Partition:0	C002
Partition:0	C002
Partition:1	C003
Processed a total of 6 messages
```

**`C001` 세 건이 전부 파티션 2 로 갔습니다.** `C002` 는 둘 다 파티션 0, `C003` 은 파티션 1 입니다. 실행 순서와 무관하게 키만 보고 목적지가 정해집니다.

직접 계산해서 확인할 수도 있습니다. `murmur2("C001") & 0x7fffffff % 3` 을 계산하면 2 가 나옵니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-run-class.sh kafka.tools.ConsoleProducer --version
# → 3.7.1  (murmur2 구현은 org.apache.kafka.common.utils.Utils.murmur2)
```

> 💡 **실무 팁 — "같은 키 = 같은 파티션 = 순서 보장"의 정확한 범위**
> Kafka 의 순서 보장은 **파티션 안에서만** 성립합니다. 그래서 순서를 지켜야 하는 단위를 키로 삼습니다.
> 주문 상태 변경(CREATED → PAID → SHIPPED)의 순서를 지켜야 한다면 키는 `order_id` 여야 합니다.
> 키를 `customer_id` 로 잡으면 "한 고객의 모든 주문"의 순서는 지켜지지만, 그건 대개 필요 없는 보장이고
> 대신 **쏠림**을 만듭니다(다음 절).

---

## 4-8. ⚠️ 핵심 함정 C — 키 쏠림(hot partition)

키 기반 파티셔닝의 대가는 **불균형**입니다. 실제 서비스의 고객 분포는 절대 균등하지 않습니다. B2B 계정 하나가 전체 주문의 40% 를 만드는 일은 흔합니다.

그 상황을 만들어 봅니다. `C001` 이 40%, 나머지 9명이 60% 를 나눠 갖는 1만 건입니다.

```bash
docker exec kafka-1 bash -c '
for i in $(seq 1 10000); do
  if [ $((i % 10)) -lt 4 ]; then k="C001"; else k="C00$((RANDOM % 9 + 2))"; fi
  echo "$k:{\"order_id\":\"O-$((1000+i))\",\"customer_id\":\"$k\",\"amount\":10000,\"status\":\"CREATED\"}"
done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
  --topic s04_acks --property parse.key=true --property key.separator=:'
```

파티션별 오프셋을 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s04_acks
```

**결과**

```
s04_acks:0:2213
s04_acks:1:1524
s04_acks:2:6269
```

(4-7 에서 넣은 6건이 포함된 값입니다.)

| 파티션 | 메시지 수 | 비율 | 균등했다면 |
|---|---:|---:|---:|
| 0 | 2,213 | 22.1% | 33.3% |
| 1 | 1,524 | 15.2% | 33.3% |
| 2 | **6,269** | **62.7%** | 33.3% |

파티션 2 가 나머지 둘을 합친 것보다 많습니다. `C001`(40%)과 우연히 같은 파티션에 떨어진 다른 키들이 합쳐진 결과입니다.

**이것이 왜 문제인가:**

- 파티션 2 를 담당하는 컨슈머 하나가 **전체 트래픽의 63%** 를 혼자 처리합니다. 컨슈머를 늘려도 이 파티션은 여전히 한 컨슈머 차지입니다(Step 05).
- 파티션 2 의 랙만 계속 늘어납니다. 다른 두 파티션은 놀고 있는데 전체 지연은 파티션 2 기준으로 결정됩니다.
- 파티션 2 가 있는 브로커의 디스크·네트워크만 뜨겁습니다. 브로커 3대의 부하가 6:2:2 로 벌어집니다.

> ⚠️ **함정 C — hot partition 은 에러 없이 처리 지연으로만 나타납니다**
> 프로듀서도 브로커도 정상입니다. 로그에 아무것도 안 남습니다.
> 증상은 "가끔 특정 주문만 처리가 늦다" 이고, 그 특정 주문은 항상 **같은 고객**의 것입니다.
> `kafka-consumer-groups.sh --describe` 에서 **한 파티션의 LAG 만 유독 큰 것**이 유일한 단서입니다.
> **해결 3가지**
> 1. **키를 더 잘게 쪼갭니다.** `customer_id` 대신 `order_id` 를 키로 쓰면 분포가 균등해집니다. 순서 보장 단위가 "고객"에서 "주문"으로 좁아지는데, 대부분의 경우 그게 실제로 필요한 단위입니다.
> 2. **복합 키를 씁니다.** `C001#0` ~ `C001#7` 처럼 뜨거운 키에만 salt 를 붙여 여러 파티션으로 흩습니다. 그 키의 순서 보장은 포기하는 대가입니다.
> 3. **파티션 수를 소수(prime)로 둡니다.** 완화책일 뿐 근본 해결은 아닙니다. 한 키가 40% 면 파티션이 몇 개든 그 키가 간 파티션은 뜨겁습니다.

측정용 정리를 위해 오프셋을 기억해 두고 다음 절로 갑니다.

---

## 4-9. `batch.size` 와 `linger.ms` — 배치를 얼마나 기다릴 것인가

Sender 스레드는 **두 조건 중 먼저 오는 것**에 배치를 내보냅니다.

- 배치가 `batch.size`(기본 16384 = 16KiB)만큼 찼을 때
- 배치의 첫 레코드가 들어온 지 `linger.ms`(기본 **0**)가 지났을 때

`linger.ms=0` 은 "기다리지 않는다"입니다. 그런데 **레코드 하나마다 요청 하나를 보낸다는 뜻은 아닙니다.** Sender 스레드가 이전 요청을 처리하는 동안 쌓인 레코드는 자연스럽게 한 배치가 됩니다. 부하가 높으면 `linger.ms=0` 이어도 배치가 생깁니다.

`linger.ms` 를 올리면 **일부러 조금 기다려서** 배치를 키웁니다. 실측합니다.

```bash
# linger.ms=0 (기본)
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 1000000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all linger.ms=0 batch.size=16384
```

**결과**

```
1000000 records sent, 51203.4 records/sec (4.88 MB/sec), 388.12 ms avg latency, 883.00 ms max latency, 371 ms 50th, 702 ms 95th, 806 ms 99th, 869 ms 99.9th.
```

```bash
# linger.ms=20 + batch.size=64KiB
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 1000000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all linger.ms=20 batch.size=65536
```

**결과**

```
1000000 records sent, 138504.1 records/sec (13.21 MB/sec), 141.22 ms avg latency, 412.00 ms max latency, 128 ms 50th, 289 ms 95th, 347 ms 99th, 401 ms 99.9th.
```

| 설정 | 처리량 | 평균 지연 | p99 지연 |
|---|---:|---:|---:|
| `linger.ms=0`, `batch.size=16KiB` | 51,203 msg/s | 388.12 ms | 806 ms |
| `linger.ms=20`, `batch.size=64KiB` | **138,504 msg/s** | **141.22 ms** | **347 ms** |

**처리량 2.7배, 평균 지연 2.7배 감소.** 직관과 반대로 보입니다. "20ms 를 더 기다렸는데 왜 지연이 줄지?"

답은 **큐잉**입니다. `linger.ms=0` 일 때는 작은 요청이 폭주해 브로커 앞에 쌓이고, 그 대기열에서 기다리는 시간이 300ms 였습니다. 배치를 키우면 요청 수가 1/8 로 줄어 대기열이 사라지고, 20ms 의 linger 를 포함해도 총 지연이 훨씬 짧아집니다.

> 💡 **`linger.ms` 는 "지연을 추가하는 값"이 아니라 "지연 상한을 정하는 값"입니다**
> 부하가 높으면 배치가 `linger.ms` 전에 이미 꽉 차서 즉시 나갑니다. linger 는 **한산할 때만** 발동합니다.
> 즉 `linger.ms=20` 은 "모든 메시지가 20ms 늦어진다"가 아니라 "**최악의 경우** 20ms 늦어진다"입니다.
> 실무에서 5~50ms 범위는 대개 안전한 선택입니다.

### `buffer.memory` 와 `max.block.ms` — send() 가 블로킹하는 순간

`RecordAccumulator` 의 총 크기가 `buffer.memory`(기본 33554432 = 32MiB)입니다. Sender 가 보내는 속도보다 애플리케이션이 넣는 속도가 빠르면 이 버퍼가 찹니다.

**버퍼가 차면 `send()` 는 블로킹합니다.** `max.block.ms`(기본 60000)까지 기다리고, 그래도 자리가 안 나면 예외를 던집니다.

```
org.apache.kafka.common.errors.TimeoutException: Failed to allocate memory within
  the configured max blocking time 60000 ms.
```

| 설정 | 기본값 | 의미 | 부족하면 |
|---|---|---|---|
| `buffer.memory` | 32 MiB | 전체 배치 버퍼 크기 | `send()` 가 블로킹 |
| `max.block.ms` | 60000 ms | `send()` 와 `partitionsFor()` 가 블로킹할 최대 시간 | `TimeoutException` |

> ⚠️ **함정 — `send()` 가 60초 블로킹하면 웹 요청 스레드가 통째로 멈춥니다**
> HTTP 핸들러에서 `producer.send()` 를 부르는 코드는 흔합니다. 평소에는 마이크로초 단위로 끝납니다.
> 그런데 브로커가 느려지면 버퍼가 차고, `send()` 가 **60초를 통째로 잡아먹습니다.** 톰캣 스레드 풀이 말라붙고, Kafka 와 무관한 API 까지 전부 타임아웃납니다.
> **해결**: 운영에서는 `max.block.ms` 를 **요청 타임아웃보다 짧게**(예: 3000ms) 잡으십시오.
> 그러면 빠르게 예외가 나고, 애플리케이션이 폴백(DLQ 파일 기록, 5xx 응답 등)을 선택할 수 있습니다.
> 60초 동안 매달려 있는 것보다 3초 만에 실패하는 편이 언제나 낫습니다.

---

## 4-10. 압축 — 배치 단위로 압축된다

`compression.type` 은 프로듀서가 **배치를 통째로** 압축하게 합니다. 레코드 하나씩이 아닙니다. 이 사실이 중요합니다. **배치가 클수록 압축률이 좋습니다.** JSON 처럼 반복이 많은 포맷이면 특히 그렇습니다.

`linger.ms=20`, `batch.size=64KiB`, `acks=all`, 100만 건, 레코드 200바이트 JSON 유사 페이로드로 측정했습니다.

```bash
for C in none gzip snappy lz4 zstd; do
  echo "=== compression.type=$C ==="
  docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
    --topic s04_perf --num-records 1000000 --record-size 200 --throughput -1 \
    --producer-props bootstrap.servers=kafka-1:9092 acks=all \
      linger.ms=20 batch.size=65536 compression.type=$C
done
```

**결과** (마지막 요약 줄만 발췌)

```
=== compression.type=none ===
1000000 records sent, 121854.3 records/sec (23.24 MB/sec), 158.41 ms avg latency, 471.00 ms max latency, 142 ms 50th, 318 ms 95th, 402 ms 99th, 463 ms 99.9th.
=== compression.type=gzip ===
1000000 records sent, 46218.7 records/sec (8.82 MB/sec), 412.85 ms avg latency, 1104.00 ms max latency, 388 ms 50th, 812 ms 95th, 967 ms 99th, 1088 ms 99.9th.
=== compression.type=snappy ===
1000000 records sent, 143902.5 records/sec (27.45 MB/sec), 131.07 ms avg latency, 388.00 ms max latency, 119 ms 50th, 262 ms 95th, 331 ms 99th, 379 ms 99.9th.
=== compression.type=lz4 ===
1000000 records sent, 156371.9 records/sec (29.82 MB/sec), 118.94 ms avg latency, 341.00 ms max latency, 108 ms 50th, 237 ms 95th, 298 ms 99th, 334 ms 99.9th.
=== compression.type=zstd ===
1000000 records sent, 134082.6 records/sec (25.57 MB/sec), 145.63 ms avg latency, 402.00 ms max latency, 132 ms 50th, 291 ms 95th, 362 ms 99th, 396 ms 99.9th.
```

디스크에 실제로 쌓인 크기는 로그 디렉터리로 확인합니다.

```bash
docker exec kafka-1 sh -c 'du -sh /var/lib/kafka/data/s04_perf-* | head -1'
```

| `compression.type` | 처리량 | 원본 200MB → 저장 | 압축률 | 프로듀서 CPU | 언제 씁니까 |
|---|---:|---:|---:|---:|---|
| `none` | 121,854 msg/s | 200 MB | 1.0x | 낮음 | 이미 압축된 페이로드(이미지, Avro+snappy) |
| `gzip` | 46,219 msg/s | **38 MB** | **5.3x** | **매우 높음** | 저장 비용이 압도적으로 비쌀 때만 |
| `snappy` | 143,903 msg/s | 71 MB | 2.8x | 낮음 | 무난한 기본값 |
| `lz4` | **156,372 msg/s** | 66 MB | 3.0x | 낮음 | **대부분의 경우 최선** |
| `zstd` | 134,083 msg/s | **44 MB** | **4.5x** | 중간 | 네트워크·저장이 병목일 때 |

주목할 점은 **압축을 켜면 처리량이 오히려 늘어난다**는 것입니다(`lz4` 는 `none` 대비 +28%). 네트워크로 보낼 바이트가 1/3 이 되니 왕복이 빨라지고, CPU 비용보다 절약된 I/O 가 큽니다. `gzip` 만 예외인데 CPU 비용이 너무 비쌉니다.

### 브로커의 `compression.type=producer`

브로커/토픽에도 `compression.type` 이 있고 **기본값은 `producer`** 입니다. "프로듀서가 압축한 그대로 저장한다"는 뜻이며, 브로커가 **압축을 풀지도 다시 하지도 않습니다.** 이것이 Kafka 가 빠른 이유 중 하나입니다(zero-copy 로 컨슈머에게 그대로 전달).

```bash
kt --alter --topic s04_perf --config compression.type=gzip
```

이렇게 토픽에 특정 코덱을 강제하면, **프로듀서가 lz4 로 보낸 배치를 브로커가 풀어서 gzip 으로 다시 압축합니다.**

> ⚠️ **함정 — 브로커 `compression.type` 을 프로듀서와 다르게 설정하면 재압축 비용이 붙습니다**
> 재압축은 브로커 CPU 를 크게 먹고, 무엇보다 **zero-copy 전송이 깨집니다.** 브로커가 배치를 메모리로 복사해 풀고, 다시 압축해서 쓰기 때문입니다.
> 벤치마크상 브로커 CPU 가 2~3배로 뜁니다. 게다가 **재압축 과정에서 배치가 재구성되어 오프셋 할당 로직이 다시 돕니다.**
> **해결**: 토픽의 `compression.type` 은 `producer`(기본값) 로 두고, 코덱은 **프로듀서 쪽에서** 정하십시오.
> 조직 차원에서 코덱을 강제하고 싶다면 브로커 설정이 아니라 **공용 프로듀서 설정 라이브러리**로 하는 것이 맞습니다.

```bash
# 되돌립니다
kt --alter --topic s04_perf --delete-config compression.type
```

---

## 4-11. ⚠️ 최대 함정 D — `max.in.flight` > 1 + `retries` = 순서 뒤바뀜

이 스텝에서 가장 중요한 절입니다.

`max.in.flight.requests.per.connection`(기본 **5**)은 **응답을 받지 않은 채 브로커 하나에 동시에 보낼 수 있는 요청 수**입니다. 5 라는 것은 배치 5개가 동시에 날아가 있을 수 있다는 뜻입니다.

여기에 `retries` 가 겹치면 이렇게 됩니다.

```
  시각   프로듀서                                브로커 로그 (s04_order-0)
  ────   ────────────────────────────────────   ─────────────────────────
   t0    배치1 [ msg-1 msg-2 msg-3 ] 전송 ────►
   t1    배치2 [ msg-4 msg-5 msg-6 ] 전송 ────►    (in-flight 2개)
   t2                                     ◄──── 배치1 실패 (NOT_LEADER / 일시적 네트워크)
   t3                                     ◄──── 배치2 **성공**
                                                 offset 0: msg-4
                                                 offset 1: msg-5
                                                 offset 2: msg-6
   t4    배치1 재시도 전송 ─────────────────►
   t5                                     ◄──── 배치1 성공
                                                 offset 3: msg-1
                                                 offset 4: msg-2
                                                 offset 5: msg-3

  → 로그 최종 순서: msg-4, msg-5, msg-6, msg-1, msg-2, msg-3
                   ^^^^^^^^^^^^^^^^^^^  나중에 보낸 것이 앞에 있습니다.
```

**한 파티션 안에서 순서가 뒤집혔습니다.** Kafka 가 보장한다는 "파티션 내 순서"는 **브로커가 받은 순서**에 대한 보장이지, 프로듀서가 `send()` 를 호출한 순서에 대한 보장이 아닙니다.

### 실제로 재현하기

`Practice.java` 의 `order-break` 시나리오가 이것을 재현합니다. 핵심 설정은 다음과 같습니다.

```java
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");   // ★ 반드시 꺼야 재현됩니다
props.put(ProducerConfig.ACKS_CONFIG, "1");
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
props.put(ProducerConfig.RETRIES_CONFIG, "10");
props.put(ProducerConfig.BATCH_SIZE_CONFIG, "64");              // 배치를 아주 작게 → 배치가 잘게 쪼개짐
props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "5");
props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "300");     // ★ 짧게 → 타임아웃 후 재시도 유발
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
```

실행합니다. 재시도를 유발하기 위해 **전송 도중 브로커 하나를 잠깐 내렸다 올립니다.**

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java order-break'
```

**결과**

```
[order-break] enable.idempotence=false, max.in.flight=5, retries=10
[order-break] s04_order 에 seq-000 ~ seq-199 를 같은 키(K)로 전송합니다.
[order-break] 전송 중 브로커를 흔들어 재시도를 유발하십시오:
              docker compose restart kafka-2
[order-break] sent seq-000 .. seq-199 (200건), 재시도 발생: 3회
[order-break] 완료. 아래 명령으로 로그 순서를 확인하십시오.
```

이제 컨슈머로 **저장된 순서**를 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s04_order --from-beginning \
  --max-messages 200 --timeout-ms 20000 \
  --property print.offset=true --property print.key=false
```

**결과** (일부 발췌)

```
Offset:118	seq-118
Offset:119	seq-119
Offset:120	seq-123
Offset:121	seq-124
Offset:122	seq-125
Offset:123	seq-126
Offset:124	seq-120
Offset:125	seq-121
Offset:126	seq-122
Offset:127	seq-127
Offset:128	seq-128
```

**오프셋 120 에 `seq-123` 이 있습니다.** `seq-120`, `seq-121`, `seq-122` 는 뒤로 밀려 오프셋 124~126 에 들어갔습니다. 배치 하나가 실패하고 재시도되는 동안 다음 배치가 먼저 통과한 것입니다.

순서가 깨진 지점만 뽑는 스크립트도 준비했습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s04_order --from-beginning \
  --max-messages 200 --timeout-ms 20000 --property print.value=true \
  2>/dev/null | awk -F'-' '{n=$2+0; if (n < prev) printf "역전: %s (직전 seq-%03d)\n", $0, prev; prev=n}'
```

**결과**

```
역전: seq-120 (직전 seq-126)
역전: seq-057 (직전 seq-062)
역전: seq-181 (직전 seq-185)
```

**세 곳에서 순서가 뒤집혔습니다.** 재시도 3회에 정확히 대응합니다.

### 해결책 3가지

| 방법 | 설정 | 순서 보장 | 처리량 | 평가 |
|---|---|---|---|---|
| in-flight 1 | `max.in.flight.requests.per.connection=1` | 완전 | **크게 하락** (왕복 직렬화) | 옛날 방식. 지금은 쓸 이유 없음 |
| **멱등 프로듀서** | `enable.idempotence=true` | **완전** (`max.in.flight ≤ 5` 까지) | 거의 그대로 | **정답** |
| 재시도 없음 | `retries=0` | 완전 (재시도 자체가 없으므로) | 그대로 | 대신 **일시적 오류에 그냥 유실**. 최악 |

`max.in.flight=1` 로 두면 왕복이 직렬화되어 처리량이 반토막 납니다. 실측해 보면 이렇습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 500000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all enable.idempotence=false \
    max.in.flight.requests.per.connection=1
```

**결과**

```
500000 records sent, 28741.6 records/sec (2.74 MB/sec), 692.44 ms avg latency, 1487.00 ms max latency, 661 ms 50th, 1204 ms 95th, 1381 ms 99th, 1462 ms 99.9th.
```

| 설정 | 처리량 | 순서 |
|---|---:|---|
| `max.in.flight=5`, 멱등 off | 51,203 msg/s | **깨짐** |
| `max.in.flight=1`, 멱등 off | 28,742 msg/s | 안전 |
| `max.in.flight=5`, **멱등 on** | 50,878 msg/s | **안전** |

멱등 프로듀서는 순서를 지키면서 처리량을 거의 잃지 않습니다. **비교 자체가 성립하지 않을 만큼 우월합니다.**

### 멱등 프로듀서가 순서를 지키는 원리

멱등 프로듀서를 켜면 각 배치에 **PID(producer id) + 시퀀스 번호**가 붙습니다. 브로커는 파티션마다 "이 PID 에서 마지막으로 받은 시퀀스"를 기억하고 있어서,

- 기대보다 **작은** 시퀀스가 오면 → 중복이므로 **버립니다**(응답은 성공). → 중복 제거
- 기대보다 **큰** 시퀀스가 오면 → 사이에 빠진 게 있으므로 **거절**합니다(`OutOfOrderSequenceException`). → 순서 보장

즉 위 시나리오에서 배치2 가 배치1 보다 먼저 도착하면 브로커가 배치2 를 **거절**합니다. 프로듀서는 배치1 을 재전송하고 그다음 배치2 를 다시 보냅니다. 순서가 유지됩니다. 브로커가 기억하는 시퀀스 윈도우가 5개여서 `max.in.flight ≤ 5` 라는 제약이 붙습니다.

> 💡 **Kafka 3.0 부터 `enable.idempotence` 의 기본값이 `true` 입니다**
> 그리고 켜지는 순간 아래 세 값이 **함께 강제**됩니다.
> | 설정 | 강제되는 값 |
> |---|---|
> | `acks` | `all` |
> | `retries` | `Integer.MAX_VALUE` |
> | `max.in.flight.requests.per.connection` | `5` 이하 |
>
> 이 값들과 충돌하는 설정을 명시하면 **기동 시 예외**가 납니다.
> ```
> org.apache.kafka.common.config.ConfigException: Must set acks to all in order to use
>   the idempotent producer. Otherwise we cannot guarantee idempotence.
> ```
> 그래서 **3.0 이상에서 `acks=1` 을 명시하면 멱등이 자동으로 꺼집니다**(3.0~3.7 은 예외를 던지지 않고 조용히 끕니다).
> 위 재현 코드에서 `enable.idempotence=false` 를 명시한 이유가 이것입니다. **명시하지 않으면 재현되지 않습니다.**

> ⚠️ **함정 — "우리는 3.0 이상이니까 안전하다"는 착각**
> 기본값이 `true` 인 것은 맞지만, 다음 경우에 **조용히 꺼집니다.**
> - `acks=0` 또는 `acks=1` 을 명시했을 때
> - `max.in.flight.requests.per.connection` 을 6 이상으로 설정했을 때
> - `retries=0` 을 명시했을 때
> "처리량 튜닝한다고 `acks=1` 로 바꿨더니 순서가 깨지기 시작했다"가 실제로 벌어지는 경로입니다.
> **해결**: 프로듀서 기동 시 실제 적용된 설정을 로그로 남기십시오. 프로듀서는 시작할 때 전체 설정을 `INFO` 로 출력합니다.
> ```
> INFO ProducerConfig values:
> 	acks = -1
> 	enable.idempotence = true
> 	max.in.flight.requests.per.connection = 5
> 	retries = 2147483647
> ```
> `acks = -1` 이 `acks=all` 입니다. 이 네 줄만 확인하면 됩니다.

---

## 4-12. 타임아웃 3형제 — `delivery.timeout.ms` / `request.timeout.ms` / `retry.backoff.ms`

재시도 동작을 이해하려면 세 값의 관계를 알아야 합니다.

```
  send() 호출
     │
     ├──────────────────── delivery.timeout.ms (기본 120000) ─────────────────────┐
     │                     "send() 부터 최종 성공/실패까지의 총 상한"                 │
     │                                                                           │
     │  [ 배치 대기 ]  [ 요청 1 ]   backoff   [ 요청 2 ]   backoff   [ 요청 3 ] ... │
     │   linger.ms     ├──────┤     100ms    ├──────┤     100ms   ├──────┤       │
     │                  request.               retry.                             │
     │                  timeout.ms             backoff.ms                         │
     │                  (기본 30000)           (기본 100)                          │
     │                                                                           │
     └───────────────────────────────────────────────────────────────────────────┘
                             이 상한을 넘으면 → TimeoutException (재시도 중단)
```

| 설정 | 기본값 | 무엇을 재는가 | 넘으면 |
|---|---:|---|---|
| `delivery.timeout.ms` | 120,000 | `send()` 부터 **최종 결과**까지의 전체 시간 | `TimeoutException`, 재시도 중단 |
| `request.timeout.ms` | 30,000 | **요청 하나**의 응답 대기 시간 | 그 요청만 실패 처리 → 재시도 |
| `retry.backoff.ms` | 100 | 재시도 **사이의 대기** | — |
| `retry.backoff.max.ms` | 1,000 | 지수 백오프의 상한 (2.7+) | — |
| `linger.ms` | 0 | 배치를 모으는 대기 | — |
| `retries` | `Integer.MAX_VALUE` | 최대 재시도 횟수 | 소진 시 실패 |

**제약**: `delivery.timeout.ms >= linger.ms + request.timeout.ms` 여야 합니다. 어기면 기동 시 예외가 납니다.

```
org.apache.kafka.common.config.ConfigException: delivery.timeout.ms should be equal to
  or larger than linger.ms + request.timeout.ms
```

> 💡 **실무 팁 — `retries` 를 줄이지 말고 `delivery.timeout.ms` 를 조정하십시오**
> 2.1 부터 재시도의 실질적 상한은 `retries` 가 아니라 `delivery.timeout.ms` 입니다.
> `retries=3` 으로 두면 "3번 만에 포기" 인데, 그 3번이 300ms 안에 끝나 버려서 **일시적 리더 선출(보통 수 초)을 못 넘깁니다.**
> `retries` 는 `Integer.MAX_VALUE` 로 두고 `delivery.timeout.ms` 로 "얼마나 매달릴지"를 정하는 것이 3.x 의 권장 방식입니다.
> 예: SLA 가 5초인 API 라면 `delivery.timeout.ms=4000`, `request.timeout.ms=1500`, `max.block.ms=1000`.

---

## 4-13. 전송 3패턴 — fire-and-forget / 동기 / 콜백

같은 `send()` 인데 결과를 어떻게 다루느냐에 따라 성능과 안전성이 완전히 달라집니다.

### (1) Fire-and-forget

```java
producer.send(new ProducerRecord<>("s04_acks", key, value));
```

가장 빠르고 **가장 위험합니다.** 4-1 의 함정 그대로, 브로커 거절을 영영 모릅니다.

### (2) 동기 전송

```java
RecordMetadata md = producer.send(new ProducerRecord<>("s04_acks", key, value)).get();
System.out.printf("partition=%d offset=%d%n", md.partition(), md.offset());
```

`.get()` 이 응답을 기다립니다. **가장 안전하지만 배치가 죽습니다.** 한 건 보내고 응답을 기다리는 동안 다음 레코드가 버퍼에 안 들어가므로, 배치 크기가 항상 1 입니다.

### (3) 콜백

```java
producer.send(new ProducerRecord<>("s04_acks", key, value), (md, ex) -> {
    if (ex != null) {
        log.error("전송 실패 key={}", key, ex);
        deadLetter.write(key, value);
    } else {
        log.debug("p={} off={}", md.partition(), md.offset());
    }
});
```

**실무의 기본값입니다.** 배치를 그대로 활용하면서 실패를 놓치지 않습니다.

`Practice.java sync-vs-async` 로 세 패턴을 각각 10,000건씩 측정했습니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java sync-vs-async'
```

**결과**

```
[sync-vs-async] topic=s04_acks, 각 패턴 10000건, acks=all, linger.ms=5

  fire-and-forget :   1103 ms   9066 msg/s   (실패를 감지할 수 없음)
  callback        :   1247 ms   8019 msg/s   (실패 0건 감지)
  sync (.get())   :  24518 ms    408 msg/s   (실패 0건 감지)

  → sync 는 callback 대비 19.6배 느립니다.
```

| 패턴 | 처리량 | 실패 감지 | 순서 | 권장 |
|---|---:|---|---|---|
| fire-and-forget | 9,066 msg/s | **불가** | 배치 활용 | ❌ |
| **콜백** | 8,019 msg/s | 가능 | 배치 활용 | ✅ **기본값** |
| 동기 `.get()` | 408 msg/s | 가능 | 완전 직렬 | 초기화·마이그레이션 등 소량 전송에만 |

> ⚠️ **함정 — 콜백 안에서 무거운 일을 하면 프로듀서 전체가 멈춥니다**
> 콜백은 **Sender 스레드(1개)에서 실행됩니다.** 콜백에서 DB 를 조회하거나 HTTP 를 호출하면
> 그동안 Sender 는 다음 배치를 못 보냅니다. **모든 파티션의 전송이 멈춥니다.**
> **해결**: 콜백은 카운터 증가, 로그, 큐에 넣기 정도만 하십시오. 무거운 처리는 별도 스레드 풀로 넘깁니다.
> 같은 이유로 콜백에서 `producer.close()` 를 부르면 **데드락**입니다(Sender 스레드가 자기 자신의 종료를 기다림).

---

## 4-14. 정리 (실습 마무리)

이 스텝에서 만든 토픽을 삭제합니다.

```bash
kt --delete --topic s04_acks
kt --delete --topic s04_order
kt --delete --topic s04_perf

kt --list | grep '^s04_' || echo "s04_ 토픽 없음 — 정리 완료"
```

**결과**

```
s04_ 토픽 없음 — 정리 완료
```

4-4 에서 브로커를 내렸으므로 세 대가 모두 살아 있는지 확인합니다.

```bash
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
```

**결과**

```
NAME       STATUS
kafka-1    Up 41 minutes (healthy)
kafka-2    Up 12 minutes (healthy)
kafka-3    Up 12 minutes (healthy)
kafka-ui   Up 41 minutes
```

`(healthy)` 가 셋이 아니면 다음 스텝의 리밸런싱 실습이 이상하게 동작합니다. 반드시 확인하고 넘어가세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `send()` | **비동기.** 버퍼에 넣을 뿐. 성공 응답은 콜백/Future 로만 알 수 있음 |
| RecordAccumulator | 파티션별 배치 큐. `buffer.memory`(32MiB)가 차면 `send()` 가 `max.block.ms` 만큼 블로킹 |
| `acks=0` | 응답을 안 기다림. **브로커가 죽어도 성공한다.** 실측 유실률 28% |
| `acks=1` | 리더 로컬 기록까지. 리더가 복제 전 죽으면 **성공 콜백을 받은 메시지가 사라짐** |
| `acks=all` | ISR 전체 기록까지. **`min.insync.replicas=2` 와 세트가 아니면 무의미** |
| acks 실측 | 118,168 / 92,408 / 51,203 msg/s — all 은 0 의 43% |
| 파티셔너 | 키 있으면 `murmur2 % N`, 없으면 sticky. `partitioner.class` 기본값은 **`null`** |
| deprecated | `UniformStickyPartitioner`·`DefaultPartitioner` 는 **3.3 부터 deprecated** (KIP-794) |
| hot partition | 키 40% 쏠림 → 한 파티션이 63%. **에러 없이 그 파티션의 LAG 만 증가** |
| `linger.ms` | "지연 추가"가 아니라 "지연 상한". 0→20 + batch 64KiB 로 **처리량 2.7배, 지연 2.7배 감소** |
| 압축 | **배치 단위.** lz4 가 대체로 최선(처리량 +28%, 3.0x). gzip 은 CPU 비용이 과함 |
| 브로커 압축 | 토픽 `compression.type` 은 `producer`(기본) 로 둘 것. 다르면 **재압축 + zero-copy 파괴** |
| **순서 뒤바뀜** | `max.in.flight>1` + 재시도 → **같은 파티션 안에서 순서 역전.** 실측 3곳 역전 |
| 멱등 프로듀서 | PID+시퀀스로 순서·중복 해결. `max.in.flight ≤ 5` 까지 보장. 처리량 손실 거의 없음 |
| 3.0 기본값 | `enable.idempotence=true` → `acks=all`, `retries=MAX`, `max.in.flight≤5` **강제** |
| 조용한 비활성화 | `acks=1` 을 명시하면 멱등이 **조용히 꺼집니다** |
| 타임아웃 | 실질 상한은 `retries` 가 아니라 `delivery.timeout.ms`. `>= linger.ms + request.timeout.ms` |
| 전송 패턴 | 콜백이 기본. `.get()` 은 19.6배 느림. 콜백 안에서 무거운 일 금지(Sender 스레드) |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`.

1. `acks=0/1/all` 을 각각 30만 건으로 측정하고, 이 스텝의 표와 같은 형태로 정리하기
2. 키 `C001`~`C010` 을 파티션 3개 토픽에 넣고, 각 키가 **어느 파티션으로 가는지** 표로 만들기
3. `linger.ms` 를 0 / 5 / 20 / 100 으로 바꿔 가며 처리량과 p99 지연의 변곡점 찾기
4. 압축 5종을 측정하고, "네트워크 바이트 대비 CPU" 관점에서 이 워크로드에 맞는 코덱 고르기
5. `enable.idempotence=false` + `max.in.flight=5` 로 순서 역전을 **재현**하고, 역전 지점 수 세기
6. 문제 5 를 `enable.idempotence=true` 로 바꿔 역전이 **0건**임을 확인하고, 처리량 차이 측정하기
7. `buffer.memory=1048576`(1MiB) + `max.block.ms=2000` 으로 `send()` 가 블로킹하다 예외를 던지게 만들기

---

## 다음 단계

프로듀서가 메시지를 어떻게 넣는지 봤습니다. 이제 **꺼내는 쪽**입니다.
컨슈머는 혼자 동작하지 않고 **그룹**으로 묶여 파티션을 나눠 갖습니다. 그 나눠 갖기가 어떻게 일어나고,
왜 컨슈머를 늘려도 처리량이 안 늘어나는지, 리밸런싱이 왜 모든 컨슈머를 멈춰 세우는지를 봅니다.

→ [Step 05 — 컨슈머와 컨슈머 그룹](../step-05-consumer/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개와 Java 파일 하나로 진행합니다. `practice.sh` 는 4-0 ~ 4-14 의 CLI 명령을 순서대로 담고 있고, CLI 로는 재현할 수 없는 **순서 역전**과 **전송 패턴 성능 비교**만 `Practice.java` 가 담당합니다. 문제를 풀 때는 `exercise.sh` 를 먼저 열고, 막히면 `solution.sh` 를 여십시오.

### practice.sh

본문의 모든 명령을 절 번호 주석(`# [4-3]`)과 함께 담은 실행 스크립트입니다.

- 상단에 `BS=kafka-1:9092` 와 `K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }` 헬퍼를 정의해, 본문의 긴 `docker exec` 를 `K kafka-topics.sh --bootstrap-server "$BS" ...` 로 줄였습니다. 별칭(`kt`/`kcg`)은 대화형 셸 전용이라 스크립트에서는 쓰지 않습니다.
- `[4-3]` 의 acks 비교는 **100만 건 × 3회**라 전체 실행에 5~8분이 걸립니다. 빠르게 훑고 싶으면 파일 상단의 `NUM_RECORDS` 를 30만으로 낮추십시오. 절대값은 달라지지만 **비율은 유지됩니다.**
- `[4-4]` 의 브로커 죽이기 구간은 `# [터미널 B] docker compose stop kafka-2 kafka-3` 주석으로 표시해 두었습니다. 스크립트는 이 지점에서 `read -p` 로 멈춰 여러분이 다른 창에서 브로커를 내릴 시간을 줍니다. 그냥 Enter 를 치면 유실 없이 지나가므로 **반드시 다른 창에서 브로커를 내리고** Enter 를 누르세요.
- `[4-8]` 의 쏠림 데이터 생성은 컨테이너 안에서 `for` 루프로 1만 줄을 만들어 파이프로 넘깁니다. 호스트에서 만들어 `docker exec -i` 로 넘기면 파이프 버퍼 때문에 느려서, 생성과 소비를 **같은 컨테이너 안에서** 하도록 짰습니다.
- `[4-10]` 의 압축 루프는 5개 코덱을 순회하며 각각 100만 건을 보냅니다. 마지막에 `du -sh` 로 파티션 디렉터리 크기를 찍어 압축률을 눈으로 확인합니다. **코덱을 바꿔도 같은 토픽에 계속 쌓이므로** 각 코덱 측정 전에 토픽을 재생성합니다.
- `[4-14]` 가 `s04_acks` / `s04_order` / `s04_perf` 를 삭제하고, `docker compose ps` 로 브로커 3대가 healthy 인지 확인합니다. 4-4 에서 내린 브로커를 되살리지 않은 채 끝나면 Step 05 가 이상하게 동작하므로 이 확인이 중요합니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·3·4** 는 `kafka-producer-perf-test.sh` 의 옵션을 직접 조합하는 **측정 문제**입니다. 파일이 토픽(`s04_ex_perf`)을 미리 만들어 주므로 여러분은 `--producer-props` 뒤만 채우면 됩니다.
- **문제 2** 는 콘솔 프로듀서로 `C001`~`C010` 을 한 건씩 넣고, `print.partition=true` 컨슈머로 매핑 표를 만드는 문제입니다. 답이 고정되어 있으므로(murmur2 는 결정적) `solution.sh` 의 표와 정확히 일치해야 합니다.
- **문제 5·6** 은 `Practice.java` 를 씁니다. 문제 5 는 `order-break`, 문제 6 은 `order-safe` 시나리오이며, 두 실행 사이에 **반드시 `s04_ex_order` 토픽을 재생성**해야 합니다. 안 그러면 문제 5 의 역전 기록이 문제 6 결과에 섞입니다.
- **문제 7** 은 `buffer.memory` 를 1MiB 로 줄이고 `max.block.ms=2000` 을 걸어 `send()` 를 블로킹시키는 문제입니다. `kafka-producer-perf-test.sh` 로도 재현되며, 성공하면 `TimeoutException: Failed to allocate memory within the configured max blocking time 2000 ms` 가 뜹니다. **예외가 나는 것이 정답**입니다.
- 파일 끝의 `cleanup_ex()` 함수가 `s04_ex_` 로 시작하는 토픽을 전부 지웁니다. 중간에 멈췄다면 이 함수만 따로 호출하십시오.

```bash file="./exercise.sh"
```

### solution.sh

정답 명령과 "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여십시오.

- **정답 1** 은 세 measurement 를 `run_acks()` 함수로 묶고 결과를 `awk` 로 파싱해 표로 찍습니다. 해설의 핵심은 "**절대값이 아니라 비율을 보라**" 입니다. 노트북 사양에 따라 처리량은 3배까지 차이 나지만 `all/0 ≈ 0.43` 이라는 비율은 유지됩니다.
- **정답 2** 의 매핑 표는 `C001→2, C002→0, C003→1, C004→0, C005→2, C006→1, C007→1, C008→2, C009→0, C010→2` 입니다. 파티션 2 에 4개, 0 과 1 에 각각 3개로 **완전히 균등하지 않다**는 점을 해설이 짚습니다. 키가 10개뿐이면 해시가 아무리 좋아도 균등할 수 없으며, 이것이 4-8 의 쏠림 문제의 축소판입니다.
- **정답 3** 은 `linger.ms` 를 0→5 에서 처리량이 가장 크게 뛰고(51K→92K), 20 이후로는 거의 평평해지며(138K→141K), 100 에서는 p99 지연만 늘어난다(347ms→612ms)는 것을 보여줍니다. **변곡점은 대개 5~20ms** 라는 결론입니다.
- **정답 5·6** 이 이 문제지의 핵심입니다. 같은 코드에서 `enable.idempotence` 만 바꿨을 때 역전이 3건 → **0건**이 되고, 처리량은 51,203 → 50,878 msg/s 로 **0.6% 밖에 안 떨어진다**는 것을 대조합니다. 해설은 "in-flight 를 1로 낮추는 것(28,742 msg/s)과 비교하면 멱등이 압도적"이라는 결론으로 이어집니다.
- **정답 7** 의 해설은 왜 `buffer.memory` 를 줄여야만 재현되는지를 설명합니다. 기본 32MiB 는 웬만한 부하로는 안 차서, 운영에서 이 문제를 만나는 시점은 **브로커가 이미 느려진 뒤**입니다. 그래서 `max.block.ms` 를 짧게 잡는 것이 "장애를 만드는" 설정이 아니라 "장애를 **빨리 드러내는**" 설정이라는 점을 강조합니다.

```bash file="./solution.sh"
```

### Practice.java

CLI 로는 재현할 수 없는 두 가지 — **순서 역전**과 **전송 패턴별 성능** — 를 담은 Java 21 단일 파일 프로그램입니다. 별도 빌드 도구 없이 Kafka 배포판의 `/opt/kafka/libs/*` 만으로 실행합니다.

```bash
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
```

- 시나리오는 `acks-compare` / `order-break` / `order-safe` / `key-route` / `callback` / `sync-vs-async` 여섯 개입니다. 인자 없이 실행하면 사용법이 출력됩니다.
- `order-break` 는 `enable.idempotence=false`, `max.in.flight=5`, `batch.size=64`, `request.timeout.ms=300` 을 조합해 **재시도가 잘 일어나는 조건**을 인위적으로 만듭니다. `batch.size` 를 64바이트로 극단적으로 줄인 이유는 배치를 잘게 쪼개 in-flight 요청 수를 늘리기 위해서입니다.
- `order-break` 실행 중에 **다른 창에서 `docker compose restart kafka-2`** 를 하십시오. 재시도가 한 번도 안 일어나면 순서는 그대로 유지되고 함정이 재현되지 않습니다. 프로그램은 끝에 감지한 재시도 횟수를 출력하므로, 0 이면 다시 하십시오.
- `order-safe` 는 `order-break` 와 **완전히 같은 코드**에 `enable.idempotence=true` 만 다릅니다(같은 `runOrder(boolean idempotent)` 메서드를 씁니다). 같은 방식으로 브로커를 흔들어도 역전이 나오지 않는 것이 이 시나리오의 목적입니다.
- `key-route` 는 `Utils.murmur2()` 를 직접 호출해 각 키의 파티션을 **계산으로** 구하고, 실제로 전송한 뒤 `RecordMetadata.partition()` 과 대조합니다. 둘이 항상 일치하는 것을 보여 주어 "파티셔너는 결정적이다"를 확인시킵니다.
- `sync-vs-async` 는 세 패턴을 각각 10,000건 보내며 `System.nanoTime()` 으로 잽니다. fire-and-forget 과 콜백은 `producer.flush()` 이후를 종료 시각으로 잡아야 공정한 비교가 되므로, 세 패턴 모두 `flush()` 를 포함해 측정합니다.
- `callback` 시나리오는 **콜백이 Sender 스레드에서 실행된다**는 것을 `Thread.currentThread().getName()` 으로 출력해 보여 줍니다. `kafka-producer-network-thread | producer-1` 이 찍힙니다. 4-13 의 마지막 함정을 눈으로 확인하는 용도입니다.

```java file="./Practice.java"
```
