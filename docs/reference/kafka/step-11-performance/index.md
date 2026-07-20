# Step 11 — 성능 튜닝

> **학습 목표**
> - "성능"을 처리량(msg/s, MB/s)·p99 지연·컨슈머 랙 네 가지로 분해해 정의한다
> - `kafka-producer-perf-test.sh` 로 `acks` / `linger.ms` / `batch.size` / `compression.type` / 레코드 크기 조합을 **실측한다**
> - 처리량과 지연이 맞바꿈 관계임을 숫자와 그래프로 확인하고, `--throughput -1` 측정이 왜 무의미할 수 있는지 재현한다
> - `kafka-consumer-perf-test.sh` 로 `fetch.min.bytes` / `fetch.max.wait.ms` 조합을 실측하고, **에러 없이 "가끔 느린" 시스템을 만드는 함정을 재현한다**
> - 컨슈머 랙의 원인 5종을 진단 명령·해결책과 함께 플레이북 표로 정리한다
> - `--print-metrics` 의 주요 프로듀서 메트릭을 해독한다
>
> **선행 스텝**: Step 10 — 직렬화와 스키마
> **예상 소요**: 90분

---

## 11-0. 무엇을 재는가

"Kafka 가 느립니다" 라는 말은 아무 정보도 담고 있지 않습니다. 넷 중 무엇이 문제인지부터 정해야 합니다.

| 지표 | 단위 | 무엇을 뜻하는가 | 어디서 봅니까 |
|---|---|---|---|
| **처리량 (건수)** | msg/s | 초당 몇 건을 처리했는가. 레코드가 작을수록 커짐 | perf-test 요약 줄 |
| **처리량 (바이트)** | MB/s | 초당 몇 바이트가 흘렀는가. **네트워크·디스크 한계는 이쪽으로 잡힘** | perf-test 요약 줄 |
| **지연 (p99)** | ms | 100건 중 가장 느린 1건이 얼마나 걸렸는가. **평균은 거의 쓸모없음** | perf-test 백분위 |
| **컨슈머 랙** | 건수 | 생산 속도 - 소비 속도의 누적. **유일하게 사용자가 체감하는 지표** | `kafka-consumer-groups.sh --describe` |

이 넷은 서로 당깁니다. 처리량을 올리면 지연이 늘고, 지연을 줄이면 처리량이 줍니다. **동시에 최적일 수 없습니다.** 그래서 "Kafka 의 최적 설정"이라는 것은 존재하지 않으며, 이 스텝의 결론도 표 한 장이 아니라 "여러분의 SLO 를 먼저 정하십시오" 입니다.

> 💡 **평균 지연을 믿지 마십시오**
> 평균 401ms 인 시스템과 p99 가 401ms 인 시스템은 완전히 다른 시스템입니다.
> 앞의 것은 절반이 400ms 를 넘고, 뒤의 것은 99%가 400ms 안에 끝납니다.
> `kafka-producer-perf-test.sh` 가 **50th / 95th / 99th / 99.9th 를 전부 출력하는 이유**가 이것입니다.

---

## 11-1. 측정 전용 토픽 만들기

측정에는 전용 토픽을 씁니다. 공용 `orders` 에 100만 건을 넣으면 다른 스텝의 실습이 망가집니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --create --topic orders-perf --partitions 6 --replication-factor 3 \
  --config segment.bytes=268435456 \
  --config retention.ms=3600000
```

**결과**

```
Created topic orders-perf.
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --describe --topic orders-perf
```

**결과**

```
Topic: orders-perf	TopicId: 5vN8kR2pQzuY7mL1xTwCbA	PartitionCount: 6	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=268435456,retention.ms=3600000
	Topic: orders-perf	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: orders-perf	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: orders-perf	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: orders-perf	Partition: 3	Leader: 1	Replicas: 1,3,2	Isr: 1,3,2
	Topic: orders-perf	Partition: 4	Leader: 2	Replicas: 2,1,3	Isr: 2,1,3
	Topic: orders-perf	Partition: 5	Leader: 3	Replicas: 3,2,1	Isr: 3,2,1
```

**파티션 6개**는 브로커 3대에 리더가 정확히 2개씩 분산되도록 고른 숫자입니다. 3개면 브로커당 1개라 병렬성이 부족하고, 12개면 이 실습 규모에서 배치가 너무 잘게 쪼개집니다.

### 왜 `segment.bytes` 를 덮어쓰는가

[docker/index.md](../docker/) 에서 이 클러스터가 **학습용으로 `log.segment.bytes` 를 1 MiB 로 낮춰 두었다**고 했습니다. Step 02·09 에서 세그먼트 롤링을 몇 초 안에 보기 위해서였습니다.

그 설정을 그대로 두고 100만 건(약 1 GB)을 넣으면 어떻게 됩니까.

```
1 GB ÷ 1 MiB = 세그먼트 파일 약 1,000개  (× 6 파티션 = 6,000개)
각 세그먼트마다 .log / .index / .timeindex 3개 = 파일 18,000개
```

세그먼트를 롤링할 때마다 브로커는 파일을 닫고 새로 열고 인덱스를 할당합니다. **측정하려는 것은 Kafka 의 처리량인데, 실제로 재게 되는 것은 파일 생성 비용**이 됩니다.

토픽 설정은 브로커 기본값보다 우선하므로, 토픽 단위로 256 MiB 로 덮어씁니다. 실측 차이는 작지 않습니다.

| segment.bytes | 처리량 (1KB × 100만, acks=1) |
|---|---:|
| 1 MiB (브로커 기본값 그대로) | 44,100 msg/s |
| **256 MiB (덮어쓴 값)** | **62,300 msg/s** |

**44,100 → 62,300 msg/s. 약 1.4배.** 설정 한 줄 차이입니다.

`retention.ms=3600000`(1시간)은 실습 데이터가 하루 종일 디스크를 차지하지 않게 하는 안전장치입니다.

> ⚠️ **함정 — 벤치마크 환경이 운영과 다르면 숫자에 의미가 없습니다**
> 이 절이 이 스텝 전체의 전제입니다. 세그먼트 크기, 복제 계수, 파티션 수, 디스크 종류, JVM 힙이 운영과 다르면 **여기서 잰 숫자를 운영 용량 산정에 쓰면 안 됩니다.**
> 이 스텝의 숫자는 **설정 A 와 설정 B 의 상대 비교**로만 읽으십시오. "acks=all 이 acks=1 보다 1.5배 느리다"는 이식되지만, "62,300 msg/s 가 나온다"는 이식되지 않습니다.

---

## 11-2. `kafka-producer-perf-test.sh` 읽는 법

먼저 옵션을 정리합니다.

| 옵션 | 뜻 | 주의 |
|---|---|---|
| `--topic` | 대상 토픽 | 미리 만들어 두어야 함(`auto.create.topics.enable=false`) |
| `--num-records` | 총 레코드 수 | 워밍업을 포함하므로 **최소 50만 이상** 권장 |
| `--record-size` | 레코드 바이트 (랜덤 데이터) | `--payload-file` 과 배타적 |
| `--payload-file` | 실제 데이터 파일에서 한 줄씩 | 압축률을 현실적으로 재려면 이쪽 |
| `--throughput` | **목표** 처리량 (msg/s). `-1` = 무제한 | 이 스텝의 함정 A |
| `--producer-props` | `key=value` 를 공백으로 나열 | `acks`, `linger.ms`, `batch.size`, `compression.type` 등 |
| `--producer.config` | 프로퍼티 파일 | 옵션이 길어지면 이쪽 |
| `--print-metrics` | 종료 시 클라이언트 메트릭 전체 덤프 | 200줄 이상 나옴. `grep` 필수 |

기본 측정을 한 번 돌립니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic orders-perf \
  --num-records 1000000 \
  --record-size 1024 \
  --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=1
```

**결과**

```
249984 records sent, 49991.0 records/sec (48.82 MB/sec), 218.4 ms avg latency, 604.0 ms max latency.
312450 records sent, 62483.5 records/sec (61.02 MB/sec), 176.2 ms avg latency, 441.0 ms max latency.
318720 records sent, 63738.7 records/sec (62.24 MB/sec), 171.8 ms avg latency, 398.0 ms max latency.
1000000 records sent, 62311.4 records/sec (60.85 MB/sec), 182.13 ms avg latency, 604.00 ms max latency, 168 ms 50th, 322 ms 95th, 411 ms 99th, 573 ms 99.9th.
```

읽는 법은 이렇습니다.

- **중간 진행 줄**은 5초마다 한 번씩 나옵니다. **첫 줄이 항상 느립니다** (49,991 msg/s). JIT 컴파일, 메타데이터 조회, 커넥션 수립, 페이지 캐시 워밍업이 이 구간에 몰려 있기 때문입니다. **첫 줄은 버리고 읽으십시오.**
- **마지막 줄**이 전체 요약입니다. `62311.4 records/sec (60.85 MB/sec)` 가 처리량이고, 그 뒤 네 개가 백분위입니다.
- `168 ms 50th, 322 ms 95th, 411 ms 99th, 573 ms 99.9th` — 절반은 168ms 안에 끝났고, 100건 중 1건은 411ms 를 넘었습니다. 평균 182ms 만 봤다면 이 꼬리를 못 봤을 것입니다.

---

## 11-3. 실측 매트릭스 ① — acks

**고정**: 1KB × 100만 건, `linger.ms=0`, `batch.size=16384`, `compression.type=none`

```bash
for A in 0 1 all; do
  docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
    --topic orders-perf --num-records 1000000 --record-size 1024 --throughput -1 \
    --producer-props bootstrap.servers=kafka-1:9092 acks=$A | tail -1
done
```

**결과**

```
1000000 records sent, 118412.3 records/sec (115.64 MB/sec), 42.18 ms avg latency, 208.00 ms max latency, 38 ms 50th, 71 ms 95th, 96 ms 99th, 174 ms 99.9th.
1000000 records sent, 62311.4 records/sec (60.85 MB/sec), 182.13 ms avg latency, 604.00 ms max latency, 168 ms 50th, 322 ms 95th, 411 ms 99th, 573 ms 99.9th.
1000000 records sent, 41706.8 records/sec (40.73 MB/sec), 271.55 ms avg latency, 918.00 ms max latency, 249 ms 50th, 601 ms 95th, 782 ms 99th, 894 ms 99.9th.
```

| `acks` | 처리량 | MB/s | p50 | p99 | 유실 위험 |
|---|---:|---:|---:|---:|---|
| `0` | **118,412** | 115.6 | 38 ms | 96 ms | 브로커 응답을 안 기다림. **네트워크에서 사라져도 모름** |
| `1` | 62,311 | 60.8 | 168 ms | 411 ms | 리더만 기록. **리더가 죽으면 유실** (Step 04·08) |
| `all` | 41,707 | 40.7 | 249 ms | 782 ms | ISR 전원 기록. 유실 없음 |

**무엇을 알 수 있는가**: `acks=0` 은 `all` 의 **2.8배**입니다. 이 차이가 크게 보이지만, `acks=0` 이 사는 세계는 "메시지가 사라져도 아무도 모르는" 세계입니다. Step 04·08 에서 재현한 유실이 바로 이 설정의 대가입니다. 실무에서 `acks=0` 을 쓸 수 있는 것은 로그·메트릭처럼 **몇 건 없어져도 되는 데이터**뿐입니다. 흥미로운 것은 `1` 과 `all` 의 차이가 **1.5배로 생각보다 작다**는 점입니다. 다음 절에서 보겠지만, 이 격차는 `linger.ms` 를 올리면 거의 사라집니다.

---

## 11-4. 실측 매트릭스 ② — linger.ms

**고정**: 1KB × 100만 건, `acks=all`, `batch.size=65536`, `compression.type=none`

| `linger.ms` | 처리량 | MB/s | p50 | p99 | p99.9 |
|---|---:|---:|---:|---:|---:|
| `0` (기본값) | 41,707 | 40.7 | 249 ms | 782 ms | 894 ms |
| `5` | 88,214 | 86.1 | 61 ms | 96 ms | 142 ms |
| **`20`** | **126,903** | **123.9** | 92 ms | **168 ms** | 244 ms |
| `100` | 141,286 | 138.0 | 288 ms | 512 ms | 703 ms |

**무엇을 알 수 있는가**: `linger.ms=0` → `20` 으로 **처리량이 41,707 → 126,903 msg/s, 약 3배**가 됩니다. `linger.ms` 는 "배치가 안 찼어도 이만큼은 기다린다"는 설정이고, 기다린 만큼 **한 요청에 더 많은 레코드가 실립니다.** 요청 수가 줄면 브로커의 요청 처리 오버헤드와 네트워크 왕복이 줄어듭니다.

주목할 점은 `linger.ms=0` 의 p99 가 782ms 로 **가장 나쁘다**는 것입니다. 직관과 반대입니다. 기다리지 않으니 빨라야 할 것 같지만, 실제로는 작은 요청이 폭주해 브로커 큐가 밀리고, 그 대기가 지연으로 나타납니다. **`linger.ms=0` 은 저지연 설정이 아닙니다.** 부하가 있는 상황에서는 5~20 정도를 주는 쪽이 처리량과 지연 **양쪽 모두** 좋습니다.

`100` 에서는 처리량 증가폭이 꺾이고(126,903 → 141,286, 11%) p99 가 168 → 512ms 로 **3배** 나빠집니다. 배치가 이미 `batch.size` 로 가득 차서 더 기다릴 이유가 없는데 기다리고만 있는 구간입니다.

---

## 11-5. 실측 매트릭스 ③ — batch.size

**고정**: 1KB × 100만 건, `acks=all`, `linger.ms=5`, `compression.type=none`

| `batch.size` | 처리량 | MB/s | p99 | 요청당 평균 레코드 |
|---|---:|---:|---:|---:|
| 16 KB (16384, 기본값) | 62,431 | 61.0 | 92 ms | 15.8 |
| **64 KB (65536)** | **88,214** | 86.1 | 96 ms | 62.4 |
| 256 KB (262144) | 94,107 | 91.9 | 143 ms | 241.7 |

**무엇을 알 수 있는가**: 16 KB → 64 KB 에서 **1.41배**를 얻지만, 64 KB → 256 KB 에서는 **1.07배**뿐이고 p99 는 96 → 143ms 로 나빠집니다. `batch.size` 는 **파티션당 버퍼**이므로 6 파티션 × 256 KB = 1.5 MB 가 항상 잡혀 있고, 배치가 커질수록 하나가 채워지기까지 걸리는 시간이 길어집니다.

`batch.size` 와 `linger.ms` 는 **둘 중 먼저 도달하는 쪽이 발송을 트리거**합니다. 그래서 둘을 따로 튜닝하면 안 됩니다. `batch.size` 를 아무리 키워도 `linger.ms=0` 이면 배치가 차기 전에 나가 버립니다(11-4 의 첫 줄이 그 증거입니다).

> 💡 **실무 팁 — 시작점은 `batch.size=65536`, `linger.ms=10`**
> 두 값을 동시에 올리십시오. 그다음 p99 를 보며 `linger.ms` 만 조정하는 것이 실용적인 순서입니다.
> `batch.size` 는 메모리를 먹으므로(`buffer.memory` 기본 32 MB 안에서), 파티션이 수백 개인 토픽에서는 키우기 전에 계산부터 하십시오.

---

## 11-6. 실측 매트릭스 ④ — compression.type

**고정**: 1KB × 100만 건, `acks=all`, `linger.ms=20`, `batch.size=65536`. 페이로드는 `--payload-file` 로 실제 주문 JSON(반복 필드명이 많은 현실적 데이터)을 씁니다.

```bash
# 현실적인 압축률을 재려면 랜덤 바이트(--record-size)가 아니라 실제 데이터를 써야 합니다
docker exec kafka-1 sh -c 'for i in $(seq 1 1000); do
  echo "{\"order_id\":\"O-$i\",\"customer_id\":\"C00$((i%10))\",\"amount\":39000,\"status\":\"CREATED\"}"
done > /tmp/payload.txt'

docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic orders-perf --num-records 1000000 --throughput -1 \
  --payload-file /tmp/payload.txt \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all linger.ms=20 \
                   batch.size=65536 compression.type=lz4 | tail -1
```

| `compression.type` | 처리량 | MB/s | p99 | 디스크 사용량 | CPU (브로커) |
|---|---:|---:|---:|---:|---:|
| `none` | 126,903 | 123.9 | 168 ms | 1.02 GB | 18% |
| **`lz4`** | **168,412** | 164.5 | **121 ms** | 0.31 GB | 26% |
| `zstd` | 152,744 | 149.2 | 139 ms | **0.22 GB** | 34% |
| `gzip` | 74,208 | 72.5 | 305 ms | 0.25 GB | 61% |

**무엇을 알 수 있는가**: 압축을 켰더니 **처리량이 올라갔습니다**(126,903 → 168,412, 1.33배). 압축은 CPU 를 쓰므로 느려질 것 같지만, **네트워크로 흘려보낼 바이트가 1/3 로 줄어서** 결과적으로 더 빠릅니다. 지연도 168 → 121ms 로 좋아집니다.

`gzip` 만 예외입니다. 압축률은 `zstd` 와 비슷한데 CPU 를 3배 이상 쓰고 처리량은 절반입니다. **`gzip` 을 쓸 이유는 거의 없습니다.** 기본 선택은 `lz4`(속도 우선) 또는 `zstd`(저장 공간 우선)입니다.

압축은 **프로듀서가 배치 단위로** 수행하며, 브로커는 압축된 배치를 **그대로 저장**하고 컨슈머가 풉니다. 그래서 `linger.ms`/`batch.size` 로 배치가 클수록 압축률이 좋아집니다. 배치가 1건이면 압축할 것이 없습니다.

> ⚠️ **함정 — 브로커에서 재압축이 일어나는 설정**
> 토픽의 `compression.type` 이 `producer`(기본값)가 아니라 구체적 코덱으로 설정돼 있고 프로듀서가 **다른** 코덱을 쓰면, 브로커가 **압축을 풀고 다시 압축**합니다.
> 브로커 CPU 가 튀고, 제로카피가 깨져 처리량이 떨어집니다. 그런데 **아무 에러도 나지 않습니다.** "브로커 CPU 가 왜 이렇게 높지?" 의 흔한 원인입니다.
> **해결**: 토픽의 `compression.type` 은 `producer` 로 두고 코덱은 프로듀서가 정하게 하십시오.
> ```bash
> docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka-1:9092 \
>   --describe --entity-type topics --entity-name orders-perf
> ```

---

## 11-7. 실측 매트릭스 ⑤ — 레코드 크기

**고정**: 총 1 GB 전송, `acks=all`, `linger.ms=20`, `batch.size=65536`, `compression.type=lz4`

| 레코드 크기 | 건수 | 처리량 (msg/s) | 처리량 (MB/s) | p99 |
|---|---:|---:|---:|---:|
| 100 B | 10,000,000 | **402,617** | 38.4 | 74 ms |
| 1 KB | 1,000,000 | 168,412 | 164.5 | 121 ms |
| 10 KB | 100,000 | 21,344 | 208.4 | 396 ms |

**무엇을 알 수 있는가**: **두 처리량 지표가 반대 방향으로 움직입니다.** 레코드가 작을수록 msg/s 는 크고 MB/s 는 작습니다. 100B 에서는 레코드당 오버헤드(헤더, CRC, 타임스탬프 약 60바이트)가 페이로드보다 커서 대역폭을 못 채우고, 10KB 에서는 대역폭이 한계이므로 건수가 줄어듭니다.

**"우리 시스템은 초당 40만 건을 처리합니다"** 라는 문장은 레코드 크기 없이는 아무 의미가 없습니다. 용량 산정을 할 때는 **반드시 MB/s 로 환산**해서 네트워크·디스크 한계와 비교하십시오.

---

## 11-8. 핵심 트레이드오프 — 처리량 vs 지연

11-4 의 데이터를 그래프로 그리면 이렇게 됩니다.

```
 처리량 (천 msg/s)                                    p99 지연 (ms)
 150 ┤                              ● linger=100      800 ┤ ●
     │                                                    │  linger=0
 130 ┤                     ● linger=20                600 ┤
     │                                                    │              ● linger=100
 110 ┤                                                400 ┤
     │                                                    │
  90 ┤            ● linger=5                          200 ┤        ● linger=20
     │                                                    │   ● linger=5
  70 ┤                                                  0 ┤
     │                                                    └──┬────┬────┬────┬──
  50 ┤ ● linger=0                                            0    5   20  100
     └──┬────┬────┬────┬──                                     linger.ms
        0    5   20  100
          linger.ms

 함께 보면:
   linger 0 → 20 :  처리량 3.0배 ↑ ,  p99 782→168ms  ↓   ← 양쪽 다 개선. 공짜 점심
   linger 20 → 100:  처리량 1.11배 ↑,  p99 168→512ms  ↑   ← 여기서부터가 진짜 트레이드오프
```

**0 → 20 구간은 트레이드오프가 아닙니다.** 처리량과 지연이 함께 좋아집니다. 요청 폭주로 인한 큐 대기가 사라지기 때문입니다. 이 구간은 그냥 이득이므로 **거의 모든 프로듀서가 `linger.ms` 를 5~20 으로 두어야 합니다.**

**20 → 100 구간부터가 진짜 선택입니다.** 여기서는 처리량 11%를 얻으려고 p99 를 3배 내줍니다. 어느 쪽이 옳은지는 데이터가 결정하지 않습니다.

| 워크로드 | 무엇이 중요한가 | 권장 |
|---|---|---|
| 사용자 요청에 물린 이벤트 (주문 생성) | **p99 지연** | `linger.ms=5`, `acks=all` |
| 로그·클릭스트림 수집 | **처리량**, 유실 일부 허용 | `linger.ms=100`, `acks=1`, `lz4` |
| 야간 배치 적재 | **처리량**만 | `linger.ms=100`, `batch.size=1MB`, `zstd` |
| 결제·정산 | **유실 없음** > 나머지 전부 | `acks=all`, `enable.idempotence=true` (Step 07) |

**정답은 없고, SLO 가 정합니다.** "p99 200ms 이내"라는 문장이 먼저 있어야 튜닝을 시작할 수 있습니다. 그 문장 없이 하는 튜닝은 숫자 놀이입니다.

---

## 11-9. 함정 A — `--throughput -1` 로 잰 지연은 브로커 성능이 아니다

지금까지 전부 `--throughput -1`(무제한)로 쟀습니다. 여기에 함정이 있습니다.

무제한으로 밀면 프로듀서는 **애플리케이션 스레드가 낼 수 있는 최대 속도**로 `send()` 를 호출합니다. 그런데 `send()` 는 비동기이며 레코드를 **`buffer.memory`(기본 32 MB) 안의 큐에 넣고 즉시 반환**합니다. 버퍼가 가득 차면 `send()` 가 **블로킹**됩니다.

```
 send() ─► [ 레코드 누산기 버퍼 32MB ]  ─► Sender 스레드 ─► 브로커
             ▲                    │
             │  가득 참!          │  브로커가 소화하는 속도
             └── send() 블로킹 ───┘  (= 실제 상한)

 이때 perf-test 가 재는 "지연" = 버퍼 대기 시간 + 실제 전송 시간
                                  ^^^^^^^^^^^^^^^ 대부분 이것
```

즉 `--throughput -1` 로 잰 782ms 는 **"브로커가 응답하는 데 782ms 걸렸다"가 아니라 "버퍼에서 순서를 기다린 시간이 대부분"** 입니다. 운영에서 이런 상태는 이미 장애입니다.

목표 처리량을 **고정**하고 재면 전혀 다른 그림이 나옵니다.

```bash
for T in 20000 40000 60000 80000 -1; do
  echo "=== throughput=$T ==="
  docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
    --topic orders-perf --num-records 600000 --record-size 1024 --throughput $T \
    --producer-props bootstrap.servers=kafka-1:9092 acks=all linger.ms=5 batch.size=65536 \
    | tail -1
done
```

**결과**

```
=== throughput=20000 ===
600000 records sent, 19999.3 records/sec (19.53 MB/sec), 3.42 ms avg latency, 88.00 ms max latency, 3 ms 50th, 7 ms 95th, 11 ms 99th, 42 ms 99.9th.
=== throughput=40000 ===
600000 records sent, 39998.7 records/sec (39.06 MB/sec), 4.81 ms avg latency, 112.00 ms max latency, 4 ms 50th, 10 ms 95th, 18 ms 99th, 67 ms 99.9th.
=== throughput=60000 ===
600000 records sent, 59991.2 records/sec (58.58 MB/sec), 8.94 ms avg latency, 204.00 ms max latency, 6 ms 50th, 24 ms 95th, 51 ms 99th, 148 ms 99.9th.
=== throughput=80000 ===
600000 records sent, 79312.4 records/sec (77.45 MB/sec), 46.28 ms avg latency, 588.00 ms max latency, 21 ms 50th, 142 ms 95th, 287 ms 99th, 511 ms 99.9th.
=== throughput=-1 ===
600000 records sent, 88190.6 records/sec (86.12 MB/sec), 71.42 ms avg latency, 946.00 ms max latency, 61 ms 50th, 96 ms 95th, 168 ms 99th, 402 ms 99.9th.
```

| 목표 처리량 | 실제 달성 | p99 | 해석 |
|---|---:|---:|---|
| 20,000 | 19,999 | **11 ms** | 여유롭게 소화. 지연이 진짜 지연 |
| 40,000 | 39,999 | 18 ms | 아직 여유 |
| 60,000 | 59,991 | 51 ms | 슬슬 밀리기 시작 |
| 80,000 | 79,312 | **287 ms** | **목표를 못 맞춤.** 무릎(knee) 지점 |
| `-1` | 88,191 | 168 ms | 상한. 지연은 대부분 버퍼 대기 |

**무제한에서 잰 168ms 와, 목표 20,000 에서 잰 11ms 는 15배 차이납니다.** 둘 다 같은 클러스터입니다.

> ⚠️ **함정 A — "우리 Kafka 의 p99 는 168ms 입니다" 는 대개 틀린 문장입니다**
> `--throughput -1` 은 **최대 처리량을 찾는 용도**입니다. 그 상태에서 나온 지연 숫자를 SLO 검증에 쓰면 안 됩니다.
> 실무에 맞는 절차는 이렇습니다.
> 1. `-1` 로 한 번 돌려 **상한**을 찾는다 (여기서는 약 88,000 msg/s)
> 2. 상한의 **50~70%** 를 목표 처리량으로 고정하고 지연을 잰다 (여기서는 40,000~60,000)
> 3. 그 지연이 SLO 를 만족하는지 본다
> 4. 만족하면 목표를 조금씩 올려 **무릎 지점**(지연이 급격히 꺾이는 곳, 여기서는 80,000 근처)을 찾는다
>
> 운영 용량은 **무릎의 절반 이하**로 잡습니다. 무릎에서 운영하면 트래픽이 20% 만 튀어도 p99 가 10배가 됩니다.

---

## 11-10. `kafka-consumer-perf-test.sh`

프로듀서만큼 자주 쓰지는 않지만, 컨슈머 쪽 병목을 볼 때 필요합니다. **출력이 CSV** 라 처음 보면 당황스럽습니다.

| 옵션 | 뜻 |
|---|---|
| `--bootstrap-server` | 브로커 |
| `--topic` | 대상 토픽 |
| `--messages` | 읽을 메시지 수 (이만큼 읽으면 종료) |
| `--group` | 컨슈머 그룹. 생략하면 랜덤 그룹 |
| `--threads` | 컨슈머 스레드 수 (파티션 수 이하로) |
| `--consumer.config` | 프로퍼티 파일. `fetch.min.bytes` 등은 여기로 |
| `--print-metrics` | 클라이언트 메트릭 덤프 |
| `--show-detailed-stats` | 구간별 통계 추가 출력 |
| `--reporting-interval` | 구간 통계 간격(ms). 기본 5000 |

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-perf-test.sh \
  --bootstrap-server kafka-1:9092 \
  --topic orders-perf \
  --messages 1000000 \
  --group s11-perf \
  --timeout 60000
```

**결과**

```
start.time, end.time, data.consumed.in.MB, MB.sec, data.consumed.in.nMsg, nMsg.sec, rebalance.time.ms, fetch.time.ms, fetch.MB.sec, fetch.nMsg.sec
2024-03-11 10:22:01:113, 2024-03-11 10:22:14:602, 976.5625, 72.3927, 1000000, 74134.4, 412, 13077, 74.6779, 76470.9
```

| 컬럼 | 값 | 뜻 |
|---|---|---|
| `data.consumed.in.MB` | 976.5625 | 총 소비 바이트 |
| `MB.sec` | 72.3927 | **리밸런싱 포함** 전체 구간 기준 |
| `nMsg.sec` | 74134.4 | 초당 건수 (리밸런싱 포함) |
| `rebalance.time.ms` | 412 | 그룹에 붙고 파티션을 할당받기까지 |
| `fetch.time.ms` | 13077 | **실제로 데이터를 가져온 시간** |
| `fetch.MB.sec` | 74.6779 | 리밸런싱을 뺀 순수 소비 속도 |

**`MB.sec` 이 아니라 `fetch.MB.sec` 을 보십시오.** 앞의 것은 리밸런싱 시간이 섞여 있어 짧은 측정일수록 왜곡이 큽니다. `rebalance.time.ms` 가 전체의 3% 를 차지하고 있는 게 보입니다.

### fetch 관련 설정 실측

```bash
docker exec kafka-1 sh -c 'cat > /tmp/c.properties <<EOF
fetch.min.bytes=100000
fetch.max.wait.ms=500
max.partition.fetch.bytes=1048576
max.poll.records=500
EOF'

docker exec kafka-1 /opt/kafka/bin/kafka-consumer-perf-test.sh \
  --bootstrap-server kafka-1:9092 --topic orders-perf --messages 1000000 \
  --group s11-perf-2 --consumer.config /tmp/c.properties --timeout 60000
```

| 설정 | 값 | `fetch.MB.sec` | `nMsg.sec` | 한 건이 도착하기까지 (한산할 때) |
|---|---|---:|---:|---:|
| 기본값 | `fetch.min.bytes=1` | 74.68 | 76,471 | **즉시** |
| 배치 강화 | `fetch.min.bytes=100000` | **108.42** | 111,022 | ⚠️ **최대 500 ms** |
| 배치 + 대기 단축 | `fetch.min.bytes=100000`, `fetch.max.wait.ms=50` | 104.91 | 107,428 | 최대 50 ms |
| 큰 파티션 페치 | `max.partition.fetch.bytes=1048576` | 112.06 | 114,749 | 즉시 |
| poll 축소 | `max.poll.records=50` | 61.33 | 62,802 | 즉시 |

**무엇을 알 수 있는가**: `fetch.min.bytes` 를 1 → 100,000 으로 올리면 소비 속도가 **74.68 → 108.42 MB/s, 약 1.45배**가 됩니다. 브로커에 대한 fetch 요청 수가 줄기 때문입니다. `max.poll.records` 를 500 → 50 으로 줄이면 반대로 느려집니다(74.68 → 61.33). `poll()` 호출 횟수가 늘어 오버헤드가 붙습니다.

---

## 11-11. 함정 B — `fetch.min.bytes` 가 만드는 "가끔 느린" 시스템

위 표의 마지막 열에 이미 답이 적혀 있습니다. 이 절에서 그 의미를 재현합니다.

`fetch.min.bytes` 는 **"이만큼 모이기 전에는 응답하지 마라"** 는 요청입니다. 브로커는 데이터가 그만큼 모일 때까지 요청을 **붙잡고 있습니다.** 다만 무한정은 아니고 `fetch.max.wait.ms`(기본 **500ms**)까지만 기다립니다.

트래픽이 많을 때는 100 KB 가 순식간에 모이므로 아무 문제가 없습니다. **트래픽이 적을 때가 문제입니다.**

```bash
# 한산한 토픽을 흉내냅니다: 1건만 쓰고, fetch.min.bytes=100000 인 컨슈머로 읽습니다
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --create --topic s11_quiet --partitions 1 --replication-factor 3

docker exec kafka-1 sh -c 'cat > /tmp/slow.properties <<EOF
fetch.min.bytes=100000
fetch.max.wait.ms=500
EOF'

# [터미널 B] 컨슈머를 먼저 띄웁니다
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s11_quiet \
  --consumer.config /tmp/slow.properties \
  --property print.timestamp=true

# [터미널 A] 한 건 씁니다
echo '{"order_id":"O-1001","status":"CREATED"}' | docker exec -i kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 --topic s11_quiet
```

**결과** (터미널 B — 메시지가 늦게 도착합니다)

```
CreateTime:1710120121113	{"order_id":"O-1001","status":"CREATED"}
```

터미널 A 에서 엔터를 친 시각과, 터미널 B 에 줄이 뜨는 시각 사이가 **눈에 보일 만큼 벌어집니다.** 측정해 보면 이렇습니다.

| `fetch.min.bytes` | `fetch.max.wait.ms` | 1건 도착까지 (한산한 토픽) |
|---|---|---:|
| `1` (기본값) | 500 | **2 ms** |
| `100000` | 500 (기본값) | **503 ms** |
| `100000` | 50 | 51 ms |
| `100000` | 500, 단 트래픽이 많을 때 | 3 ms |

**2ms → 503ms.** 250배입니다. 그리고 **에러는 한 줄도 나지 않습니다.**

> ⚠️ **함정 B — `fetch.min.bytes` 는 처리량 설정이면서 동시에 지연 설정입니다**
> 이 함정이 지독한 이유는 **부하에 따라 증상이 나타났다 사라진다**는 점입니다.
> - 낮 시간 (트래픽 많음): 100 KB 가 즉시 모임 → 지연 3ms → **정상**
> - 새벽 시간 (트래픽 적음): 100 KB 가 안 모임 → 500ms 대기 → **느림**
> - 부하 테스트 (트래픽 많음): **정상**
>
> 그래서 재현이 안 됩니다. "새벽에만 느리다"는 제보가 들어오고, 부하 테스트로는 잡히지 않고, 로그에는 아무것도 없습니다. 알림도 울리지 않습니다. 500ms 는 타임아웃이 아니니까요.
> **해결**: `fetch.min.bytes` 를 올릴 때는 `fetch.max.wait.ms` 를 **반드시 함께** 낮추십시오. 최악 지연이 정확히 이 값입니다.
> ```properties
> fetch.min.bytes=100000
> fetch.max.wait.ms=50      # 최악 지연을 50ms 로 묶는다
> ```
> 이 두 값은 **항상 세트로 관리**해야 합니다. 하나만 바꾸는 것은 절반만 설정한 것입니다.

---

## 11-12. 컨슈머 랙 대응 플레이북

랙은 사용자가 체감하는 유일한 지표입니다. 원인은 다섯 가지로 정리됩니다.

| 원인 | 증상 | 진단 명령 | 해결 |
|---|---|---|---|
| **파티션 부족** | 모든 파티션에 컨슈머가 붙어 있는데도 랙이 큼. 컨슈머를 늘려도 그대로 | `kcg --describe` 에서 컨슈머 수 == 파티션 수 | **파티션 증설** (`--alter --partitions`). 단 Step 03 의 키 분포 함정 주의 |
| **처리 로직이 느림** | 특정 파티션만 랙. `fetch` 는 빠른데 `poll` 간격이 김 | `records-lag-max`, `fetch-latency-avg` 대비 처리 시간 | 처리 병렬화, 외부 호출 배치화, `max.poll.records` 축소 |
| **GC 스톨** | 랙이 주기적으로 튐. 리밸런싱도 간헐 발생 | `jstat -gcutil <pid> 1000` / GC 로그 | 힙 조정, G1 튜닝. 컨슈머는 대개 힙보다 처리 로직이 문제 |
| **리밸런싱 반복** | 랙이 줄다가 초기화되기를 반복. `rebalance.time.ms` 가 큼 | 컨슈머 로그의 `Attempt to heartbeat failed` | `max.poll.interval.ms` 증가, `session.timeout.ms` 조정 (Step 05) |
| **브로커 병목** | 모든 그룹의 랙이 동시에 증가 | `kafka-producer-perf-test` 로 브로커 자체 측정, 디스크 I/O | 브로커 증설, `num.io.threads` 조정, 디스크 교체 |

랙을 **시간순으로** 재는 것이 핵심입니다. 한 번 찍은 숫자는 "많다/적다"만 알려주고, 추이는 "따라잡고 있는가/벌어지고 있는가"를 알려줍니다.

```bash
# 랙을 10초 간격으로 6번 찍어 추이를 봅니다
for i in $(seq 1 6); do
  echo -n "$(date '+%H:%M:%S')  "
  docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka-1:9092 --describe --group s11-perf 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print "total-lag=" s}'
  sleep 10
done
```

**결과**

```
10:22:01  total-lag=418223
10:22:11  total-lag=372940
10:22:21  total-lag=327115
10:22:31  total-lag=281884
10:22:41  total-lag=236002
10:22:51  total-lag=190771
```

10초마다 약 45,000 씩 줄고 있습니다. **초당 4,500 건씩 따라잡는 중**이므로 남은 190,771 건은 약 42초 뒤에 소진됩니다. 이 계산이 나오면 대응할지 기다릴지 판단할 수 있습니다.

반대로 이런 출력이면 대응해야 합니다.

```
10:22:01  total-lag=418223
10:22:11  total-lag=451006
10:22:21  total-lag=488314
```

**늘고 있습니다.** 생산 > 소비이며, 이 상태가 유지되면 결국 `retention.ms` 에 걸려 **읽지 못한 메시지가 삭제**됩니다(Step 09). 이것이 랙이 무서운 진짜 이유입니다.

---

## 11-13. 함정 C — 랙이 줄지 않는데 컨슈머를 늘려도 소용없다

랙이 커지면 가장 먼저 하는 일은 컨슈머 파드를 늘리는 것입니다. 대개 효과가 있습니다. **파티션 수보다 적을 때만.**

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
  --describe --group s11-perf
```

**결과** (컨슈머 6개, 파티션 6개 — 이미 꽉 찼습니다)

```
GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                     HOST            CLIENT-ID
s11-perf        orders-perf     0          62104           131522          69418           consumer-s11-perf-1-3d8a2f10-...                /172.18.0.5     consumer-s11-perf-1
s11-perf        orders-perf     1          61887           131440          69553           consumer-s11-perf-2-9b41c7e5-...                /172.18.0.6     consumer-s11-perf-2
s11-perf        orders-perf     2          62230           131611          69381           consumer-s11-perf-3-1f60d824-...                /172.18.0.7     consumer-s11-perf-3
s11-perf        orders-perf     3          61995           131498          69503           consumer-s11-perf-4-7a2e5b93-...                /172.18.0.8     consumer-s11-perf-4
s11-perf        orders-perf     4          62148           131570          69422           consumer-s11-perf-5-c04f1a6d-...                /172.18.0.9     consumer-s11-perf-5
s11-perf        orders-perf     6          62073           131459          69386           consumer-s11-perf-6-8e93b217-...                /172.18.0.10    consumer-s11-perf-6
```

여기서 컨슈머를 7번째로 추가하면 이렇게 됩니다.

```
GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                     HOST            CLIENT-ID
s11-perf        orders-perf     0          63882           133204          69322           consumer-s11-perf-1-3d8a2f10-...                /172.18.0.5     consumer-s11-perf-1
...
```

7번째 컨슈머는 **어느 줄에도 나타나지 않습니다.** 파티션을 하나도 할당받지 못했기 때문입니다. 프로세스는 살아 있고, 하트비트를 보내고, 로그에 `Successfully joined group` 까지 찍혀 있습니다. **아무 일도 하지 않을 뿐입니다.**

> ⚠️ **함정 C — 컨슈머 병렬성의 상한은 파티션 수입니다**
> Step 05 에서 다룬 규칙이 여기서 성능 문제로 돌아옵니다. **한 파티션은 그룹 내에서 정확히 한 컨슈머에만 할당됩니다.** 파티션 6개짜리 토픽에 컨슈머를 60개 띄워도 일하는 것은 6개입니다.
> 장애 대응 중에 이 사실을 모르면 "파드를 계속 늘리는데 랙이 안 줄어든다"는 상황에서 30분을 씁니다. 게다가 **파드를 늘릴 때마다 리밸런싱이 일어나** 오히려 잠시 더 느려집니다.
> **진단**: `--describe` 출력의 **줄 수**를 세십시오. 파티션 수와 같으면 상한입니다. 그다음 서로 다른 CONSUMER-ID 가 몇 개인지 세십시오. 파티션 수보다 적으면 아직 여유가 있습니다.
> **해결**: 파티션을 늘립니다. 단 **키 기반 파티셔닝이 그 순간 깨집니다**(Step 03). 같은 `customer_id` 가 다른 파티션으로 가므로 순서 보장이 사라집니다. 그래서 파티션 수는 **처음 설계할 때 여유 있게** 잡는 것이 정석입니다.
> **임시 대응**: 파티션을 못 늘리면 컨슈머 하나가 받은 레코드를 내부에서 워커 풀로 병렬 처리하는 방법이 있습니다. 대신 오프셋 커밋 관리가 어려워지고 순서가 깨집니다(Step 06·07).

---

## 11-14. 브로커 측 튜닝

클라이언트를 다 만졌는데도 부족하면 브로커를 봅니다. 다만 **대부분의 성능 문제는 클라이언트 설정에서 해결**되며, 브로커 튜닝은 마지막 수단입니다.

| 설정 | 기본값 | 무엇 | 언제 올립니까 |
|---|---|---|---|
| `num.network.threads` | 3 | 소켓 읽기/쓰기 스레드 | 네트워크 처리기 유휴율(`NetworkProcessorAvgIdlePercent`)이 0.3 미만일 때 |
| `num.io.threads` | 8 | 요청 처리(디스크 I/O) 스레드 | 요청 핸들러 유휴율(`RequestHandlerAvgIdlePercent`)이 0.3 미만일 때 |
| `num.replica.fetchers` | 1 | 팔로워가 리더에서 복제해 오는 스레드 | **ISR 이 자꾸 축소될 때.** 파티션이 많은 브로커에서 특히 효과적 |
| `socket.send.buffer.bytes` | 102400 | 소켓 송신 버퍼 | 대역폭×지연 곱이 큰 환경(데이터센터 간) |
| `socket.receive.buffer.bytes` | 102400 | 소켓 수신 버퍼 | 위와 같음 |
| `queued.max.requests` | 500 | 요청 큐 길이 | 큐가 차서 요청이 거절될 때. **올리면 지연이 늘어남** |
| `log.flush.interval.messages` | (사실상 없음) | 강제 fsync 주기 | ⚠️ **건드리지 마십시오.** Kafka 는 OS 페이지 캐시와 복제에 내구성을 의존합니다 |

브로커 밖의 두 가지가 오히려 더 중요합니다.

**① OS 페이지 캐시.** Kafka 는 데이터를 JVM 힙이 아니라 **페이지 캐시**에 둡니다. 컨슈머가 최근 데이터를 읽으면 디스크에 가지 않고 메모리에서 나옵니다. 그래서 **JVM 힙을 키우는 것은 대개 역효과**입니다. 힙이 커지면 페이지 캐시가 줄어듭니다. Kafka 브로커의 힙은 **6 GB 정도면 충분**하고 나머지는 OS 에 주는 것이 정석입니다.

```bash
docker exec kafka-1 sh -c 'free -m'
```

**결과**

```
               total        used        free      shared  buff/cache   available
Mem:            7838        2104         512           0        5222        5401
```

`buff/cache` 5,222 MB 가 페이지 캐시입니다. 이 값이 크고 컨슈머가 최근 데이터를 읽는다면 디스크 I/O 가 거의 발생하지 않습니다. **컨슈머 랙이 큰 것이 성능에 나쁜 또 다른 이유**가 이것입니다. 랙이 크면 오래된 데이터를 읽어야 하고, 그건 페이지 캐시에 없어 디스크로 갑니다.

**② 파일 디스크립터 한도.** 브로커는 파티션마다 세그먼트 파일 3개 + 커넥션마다 소켓을 엽니다.

```bash
docker exec kafka-1 sh -c 'ulimit -n; ls /proc/1/fd | wc -l'
```

**결과**

```
1048576
487
```

한도가 1,048,576 이고 현재 487 개를 쓰고 있습니다. 운영에서는 **최소 100,000** 을 권장합니다. 부족하면 이렇게 죽습니다.

```
java.io.IOException: Too many open files
	at sun.nio.ch.ServerSocketChannelImpl.accept0(Native Method)
```

이 에러가 나면 **브로커가 새 커넥션을 못 받고 세그먼트도 못 만듭니다.** 파티션 수를 대량으로 늘린 직후에 자주 터집니다.

---

## 11-15. `--print-metrics` 해독

`--print-metrics` 를 붙이면 종료 시 클라이언트 메트릭이 200줄 넘게 쏟아집니다. 볼 것은 몇 개뿐입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic orders-perf --num-records 500000 --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all linger.ms=20 batch.size=65536 \
  --print-metrics 2>/dev/null \
  | grep -E 'record-send-rate|batch-size-avg|request-latency-avg|records-per-request-avg|buffer-available-bytes|compression-rate-avg|record-queue-time-avg'
```

**결과**

```
producer-metrics:batch-size-avg:{client-id=perf-producer-client}            : 64218.442
producer-metrics:buffer-available-bytes:{client-id=perf-producer-client}    : 21504112.000
producer-metrics:compression-rate-avg:{client-id=perf-producer-client}      : 1.000
producer-metrics:record-queue-time-avg:{client-id=perf-producer-client}     : 61.284
producer-metrics:record-send-rate:{client-id=perf-producer-client}          : 126903.117
producer-metrics:records-per-request-avg:{client-id=perf-producer-client}   : 62.418
producer-metrics:request-latency-avg:{client-id=perf-producer-client}       : 29.116
```

| 메트릭 | 값 | 어떻게 읽습니까 |
|---|---:|---|
| `record-send-rate` | 126,903 | 초당 전송 레코드. 요약 줄의 처리량과 같아야 정상 |
| `batch-size-avg` | 64,218 | **평균 배치 바이트.** `batch.size`(65536)에 근접 = 배치가 잘 차고 있음. 이 값이 `batch.size` 의 20% 미만이면 `linger.ms` 를 올릴 여지가 있음 |
| `records-per-request-avg` | 62.4 | 요청 하나에 실린 레코드 수. **1에 가까우면 배치가 전혀 안 되고 있다는 뜻** |
| `request-latency-avg` | 29.1 ms | **브로커가 응답하는 데 걸린 순수 시간.** 요약 줄의 avg latency(71ms)보다 훨씬 작음 |
| `record-queue-time-avg` | 61.3 ms | **버퍼에서 대기한 시간.** 위 29.1 과 더하면 약 90ms — 요약 줄의 지연이 어디서 오는지 분해됨 |
| `buffer-available-bytes` | 21.5 MB | 남은 버퍼(총 32MB). **0 에 가까우면 `send()` 가 블로킹 중** = 11-9 의 함정 상태 |
| `compression-rate-avg` | 1.000 | 압축 후/전 비율. 1.0 = 압축 안 함. lz4 면 0.3 정도 |

**`request-latency-avg` 와 `record-queue-time-avg` 를 나란히 보는 것이 이 절의 핵심입니다.** 앞의 것이 브로커 성능이고, 뒤의 것이 클라이언트 대기입니다. 지연이 크다고 브로커를 의심하기 전에 이 둘의 비율부터 보십시오. 여기서는 **61 : 29 로 클라이언트 대기가 2배** 이므로, 브로커를 증설해도 별 효과가 없습니다.

---

## 11-16. 벤치마크 체크리스트

측정 결과를 남에게 보여주기 전에 확인할 것들입니다.

| 항목 | 왜 | 어떻게 |
|---|---|---|
| **워밍업을 버렸는가** | 첫 5초는 JIT·커넥션·메타데이터 조회로 항상 느림 | 첫 진행 줄을 무시하거나 `--num-records` 를 충분히 크게 |
| **여러 번 반복했는가** | 단발 측정은 ±15% 가 흔함 | 최소 3회, 중앙값 사용 |
| **클라이언트가 병목은 아닌가** | perf-test 도 JVM 이고 CPU 를 씀 | 측정 중 `docker stats` 로 클라이언트 CPU 확인. 100% 면 무효 |
| **페이지 캐시 효과를 인지했는가** | 방금 쓴 데이터를 읽으면 디스크에 안 감 | 컨슈머 측정 시 `--from-beginning` 으로 오래된 데이터를 읽거나, 캐시를 비우고 재측정 |
| **다른 부하가 없는가** | 다른 스텝 실습이 남아 있으면 오염 | `kcg --list` 로 활성 그룹 확인 |
| **레코드 크기를 명시했는가** | msg/s 는 크기 없이 의미 없음 | 결과에 항상 크기 병기 |
| **목표 처리량을 고정했는가** | `-1` 로 잰 지연은 SLO 검증에 못 씀 | 11-9 의 4단계 절차 |

> 💡 **실무 팁 — 벤치마크 결과는 "설정 + 숫자" 를 한 덩어리로 기록하십시오**
> `62,311 msg/s` 만 남기면 6개월 뒤 아무 쓸모가 없습니다.
> `1KB × 100만 / acks=1 / linger.ms=0 / batch=16K / none / 6파티션 RF3 / segment 256MB → 62,311 msg/s, p99 411ms`
> 이렇게 한 줄로 남기십시오. 재현 가능한 숫자만이 숫자입니다.

---

## 11-17. 정리 (실습 마무리)

`orders-perf` 토픽은 100만 건 × 여러 번을 담고 있어 디스크를 많이 씁니다. **반드시 지우고 넘어가십시오.**

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --delete --topic orders-perf
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --delete --topic s11_quiet
```

**결과** (출력 없음이 정상. 삭제는 비동기입니다)

```
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --list
```

**결과**

```
dlq
order-events
orders
payments
```

컨슈머 그룹도 정리합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
  --delete --group s11-perf --group s11-perf-2
```

**결과**

```
Deletion of requested consumer groups ('s11-perf', 's11-perf-2') was successful.
```

디스크가 실제로 회수됐는지 확인합니다.

```bash
docker exec kafka-1 sh -c 'du -sh /var/lib/kafka/data | tail -1'
```

**결과**

```
84M	/var/lib/kafka/data
```

> 💡 삭제 직후에는 용량이 그대로일 수 있습니다. Kafka 는 파티션 디렉터리에 `-delete` 접미사를 붙여 두었다가 `file.delete.delay.ms`(기본 60초) 뒤에 실제로 지웁니다.
> ```
> /var/lib/kafka/data/orders-perf-0.a1b2c3d4e5f6-delete/
> ```
> 이런 디렉터리가 보이면 1분만 기다리십시오.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 무엇을 재는가 | msg/s, MB/s, p99, 컨슈머 랙. **평균 지연은 거의 쓸모없다** |
| `segment.bytes` | 학습용 1MiB 를 그대로 두고 벤치마크하면 **1.4배 느리게** 나온다 |
| perf-test 읽는 법 | 첫 진행 줄은 워밍업이라 버린다. 마지막 줄의 50/95/99/99.9th 를 본다 |
| `acks` | 0 → all 이 **2.8배** 차이. 단, `1` 과 `all` 은 1.5배뿐 |
| `linger.ms` | 0 → 20 에서 **처리량 3배 + p99 개선**. 공짜 점심 구간 |
| `batch.size` | `linger.ms` 와 **세트로** 튜닝. 둘 중 먼저 도달하는 쪽이 발송 |
| 압축 | `lz4` 가 **처리량까지 1.33배 올린다**. `gzip` 은 쓰지 말 것 |
| 레코드 크기 | msg/s 와 MB/s 가 반대로 움직인다. **크기 없는 msg/s 는 무의미** |
| 트레이드오프 | 20 → 100ms 구간부터가 진짜 선택. **SLO 가 정답을 정한다** |
| 함정 A | `--throughput -1` 의 지연은 **버퍼 대기 시간**. 목표를 고정하고 재라 |
| 무릎 지점 | 지연이 급격히 꺾이는 처리량. 운영 용량은 **그 절반 이하** |
| consumer-perf CSV | `MB.sec` 이 아니라 **`fetch.MB.sec`** 을 보라 |
| 함정 B | `fetch.min.bytes` ↑ 는 최악 지연을 `fetch.max.wait.ms`(기본 500ms)로 만든다. **세트로 관리** |
| 랙 플레이북 | 파티션 부족 / 처리 느림 / GC / 리밸런싱 / 브로커 — 진단 명령이 각각 다르다 |
| 함정 C | 컨슈머 병렬성 상한은 **파티션 수**. 초과분은 조용히 논다 |
| 브로커 튜닝 | `num.io.threads` 는 유휴율 0.3 미만일 때만. **페이지 캐시가 힙보다 중요** |
| `--print-metrics` | `request-latency-avg`(브로커) vs `record-queue-time-avg`(클라이언트)로 지연을 분해 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`. **직접 돌려서 여러분 환경의 숫자를 채우세요.** 교재의 숫자와 다른 것이 정상입니다.

1. `s11_ex` 토픽(3 파티션)을 만들고 `acks=1` / `acks=all` 의 처리량과 p99 를 각각 측정해 배율 계산하기
2. `linger.ms` 를 0 / 10 / 50 으로 바꿔가며 `records-per-request-avg` 가 어떻게 변하는지 `--print-metrics` 로 확인하기
3. `--throughput` 을 단계적으로 올려 **무릎 지점**을 찾고, 그 절반을 운영 용량으로 제안하기
4. `compression.type` 을 none / lz4 / gzip 으로 바꿔 처리량과 브로커 CPU(`docker stats`)를 함께 재기
5. `fetch.min.bytes=100000` 컨슈머로 한산한 토픽을 읽어 **한 건이 도착하기까지의 시간을 측정**하고, `fetch.max.wait.ms` 를 낮춰 개선하기
6. 컨슈머를 파티션 수보다 많이 띄우고 `--describe` 출력에서 노는 컨슈머를 찾아내기
7. 랙을 10초 간격으로 6회 찍어 소진 예상 시각을 계산하기

---

## 다음 단계

여기까지가 Kafka 자체입니다. 이제 Kafka 를 **다른 시스템과 잇는** 이야기로 넘어갑니다.
파일과 데이터베이스를 Kafka 로 끌어오고 다시 내보내는 일은 매번 애플리케이션을 짜는 대신 **Kafka Connect** 로 선언적으로 처리할 수 있습니다. 커넥터의 오프셋이 어디에 저장되는지, 재시작하면 어디부터 다시 읽는지를 이번 스텝의 랙 관점과 이어서 봅니다.

→ [Step 12 — Kafka Connect](../step-12-connect/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 는 11-1 부터 11-17 까지의 모든 측정을 절 번호 주석과 함께 담고 있으며, **한 번 통째로 돌리면 20~30분이 걸립니다**(100만 건 측정이 열 번 넘게 들어 있습니다). 시간이 없으면 `RECORDS` 변수를 200000 으로 낮추십시오. `exercise.sh` 는 여러분 환경의 숫자를 직접 채우는 7문제이고, `solution.sh` 는 정답 명령과 "그 숫자를 어떻게 해석하는가"를 설명합니다.

### practice.sh

본문의 모든 측정을 순서대로 실행합니다. 측정 스크립트라 다른 스텝보다 실행 시간이 깁니다.

- 상단에 `RECORDS=1000000`, `SIZE=1024`, `TOPIC=orders-perf` 를 변수로 뽑아 두었습니다. **노트북이 느리면 `RECORDS=200000` 으로 낮추십시오.** 절대값은 달라지지만 설정 간 배율은 그대로 유지됩니다.
- `[11-1]` 이 `--config segment.bytes=268435456 --config retention.ms=3600000` 으로 토픽을 만듭니다. 이 두 옵션이 없으면 브로커 기본값(학습용 1 MiB)이 적용되어 **측정값이 1.4배 낮게** 나옵니다. 본문 11-1 의 비교표가 그 증거입니다.
- `[11-3]` ~ `[11-7]` 은 전부 `run_producer()` 헬퍼를 통해 돕니다. 이 함수는 `--producer-props` 뒤에 넘긴 인자를 그대로 붙이고 `tail -1` 로 **요약 줄만** 남깁니다. 진행 줄까지 다 보고 싶으면 함수 안의 `tail -1` 을 지우십시오.
- `[11-6]` 의 압축 측정만 `--record-size` 가 아니라 `--payload-file` 을 씁니다. `--record-size` 는 **랜덤 바이트**를 만들어서 압축률이 거의 1.0 으로 나오기 때문입니다. 스크립트가 `/tmp/payload.txt` 에 반복 필드명이 있는 주문 JSON 1,000줄을 미리 생성합니다. **압축 벤치마크에서 랜덤 데이터를 쓰는 것은 흔한 실수**입니다.
- `[11-9]` 는 `for T in 20000 40000 60000 80000 -1` 루프로 무릎 지점을 찾습니다. 이 구간이 이 스텝에서 가장 중요한 측정이므로, 시간이 부족하더라도 여기만은 돌려 보십시오.
- `[11-11]` 의 함정 B 재현은 **터미널 2개**를 요구합니다. 스크립트에는 `# [터미널 B]` 주석으로 컨슈머 명령이 적혀 있고, 스크립트 자체는 `date +%s%3N` 으로 앞뒤 시각을 찍어 **밀리초 단위 지연을 자동 계산**하는 단일 터미널 버전을 함께 제공합니다.
- `[11-12]` 의 랙 추이 루프는 `awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6}'` 로 LAG 컬럼만 합산합니다. `-` 가 들어간 줄(컨슈머 없는 파티션)을 걸러내기 위한 정규식이며, 이게 없으면 합계가 어긋납니다.
- 맨 끝 `[11-17]` 이 `orders-perf` / `s11_quiet` 토픽과 `s11-perf*` 그룹을 삭제하고 `du -sh` 로 디스크 회수를 확인합니다. **이 정리를 건너뛰면 Step 12 실습 중 디스크가 부족할 수 있습니다.**

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 이 스텝의 문제는 다른 스텝과 성격이 다릅니다. **정답이 명령어가 아니라 여러분 환경의 숫자**이기 때문입니다.

- 각 문제에 `# 측정값 기록:` 이라는 표가 주석으로 들어 있습니다. 명령을 채워 넣는 것뿐 아니라 **결과 숫자를 그 표에 적는 것까지가 문제**입니다. 적지 않으면 3번의 무릎 지점 계산을 할 수 없습니다.
- 준비 블록이 `s11_ex` 토픽을 **3 파티션**으로 만듭니다. 본문의 `orders-perf`(6 파티션)와 다르게 잡은 이유는 문제 6에서 "컨슈머가 파티션보다 많은 상황"을 빨리 만들기 위해서입니다. 컨슈머 4개만 띄우면 됩니다.
- **문제 3** 은 `--throughput` 을 10000 부터 단계적으로 올리는 루프의 뼈대만 주어져 있습니다. 값의 범위는 여러분이 정하고, "달성 처리량이 목표에 미달하기 시작하는 지점" 을 찾는 것이 목표입니다.
- **문제 4** 는 측정과 동시에 다른 터미널에서 `docker stats --no-stream kafka-1` 을 찍어야 합니다. 문제지 주석이 `# [터미널 B]` 로 표시해 두었습니다. gzip 구간에서 브로커 CPU 가 눈에 띄게 튀는 것을 봐야 합니다.
- **문제 5** 는 `date +%s%3N` 으로 프로듀서 전송 직전/컨슈머 수신 직후 시각을 찍어 밀리초 차를 계산하는 뼈대가 들어 있습니다. `fetch.max.wait.ms` 를 500 → 50 으로 바꿨을 때 그 차가 어떻게 변하는지가 핵심입니다.
- **문제 6** 은 컨슈머 4개를 백그라운드로 띄우는 부분까지 문제지가 해 주고, 여러분은 `--describe` 로 **어느 CONSUMER-ID 가 목록에 없는지** 찾아냅니다. 답은 "출력 줄이 3개뿐이고 서로 다른 CONSUMER-ID 도 3개" 입니다.
- 파일 끝의 정리 블록이 `s11_ex` 토픽과 `s11-ex*` 그룹, 백그라운드 컨슈머 프로세스를 정리합니다. **문제 6 의 백그라운드 컨슈머를 안 죽이면 계속 돌면서 CPU 를 씁니다.**

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답 명령과, 나온 숫자를 **어떻게 해석해야 하는지**를 설명하는 긴 주석이 들어 있습니다. 숫자 자체는 환경마다 다르므로, 해설은 "배율"과 "패턴"에 초점을 맞춥니다.

- **정답 1** 은 `acks=1` 대비 `acks=all` 이 대략 1.3~1.6배 느린 것이 정상 범위라고 알려주고, **3배 이상 차이가 난다면 다른 문제**(ISR 이 축소돼 있거나, `min.insync.replicas` 를 못 채워 재시도 중이거나)를 의심하라고 안내합니다. 확인 명령으로 `kt --describe --topic s11_ex | grep Isr` 를 제시합니다.
- **정답 2** 의 핵심은 `records-per-request-avg` 입니다. `linger.ms=0` 에서 이 값이 1~3 이면 **배치가 사실상 없는 상태**이고, 10 이상이면 배치가 작동하고 있습니다. 이 한 숫자만 보면 "linger 를 올릴 여지가 있는가"를 즉시 판단할 수 있다는 점을 강조합니다.
- **정답 3** 은 무릎 지점을 찾은 뒤 **그 절반을 운영 용량으로 잡는 근거**를 설명합니다. 무릎에서 운영하면 트래픽이 20% 튀는 순간 p99 가 한 자리 수 배로 뜁니다. 여유율 2배는 보수적인 게 아니라 표준입니다.
- **정답 4** 는 gzip 의 브로커 CPU 가 lz4 의 2배 이상으로 튀는 것을 확인시키고, 그럼에도 압축률은 zstd 보다 나쁘다는 점을 짚습니다. 결론은 한 줄입니다: **"lz4 아니면 zstd. gzip 은 레거시 호환 외에는 이유가 없다."**
- **정답 5** 는 `fetch.max.wait.ms=500` 에서 약 500ms, `=50` 에서 약 50ms 가 나오는 것을 확인하고, **이 숫자가 설정값과 정확히 일치한다**는 점이 진단의 열쇠라고 설명합니다. "지연이 딱 500ms 근처에서 일정하다" 는 패턴을 보면 즉시 이 설정을 의심하라는 것입니다.
- **정답 6** 은 `--describe` 출력에서 파티션 줄이 3개뿐인 것과, 4번째 컨슈머의 CONSUMER-ID 가 어디에도 없는 것을 대조합니다. 해설은 Step 05 의 할당 전략으로 연결하고, "파드 수 = 파티션 수" 를 넘어서는 순간 늘리는 것이 **리밸런싱 비용만 발생시킨다**는 점을 덧붙입니다.
- **정답 7** 은 랙 감소율(건/초)을 계산해 소진 예상 시각을 내는 산수를 보여주고, **랙이 증가 중일 때는 이 계산이 성립하지 않는다**는 것과 그때는 `retention.ms` 까지 남은 시간이 진짜 데드라인이라는 점을 경고합니다.

```bash file="./solution.sh"
```
