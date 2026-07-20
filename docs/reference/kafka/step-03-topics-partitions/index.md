# Step 03 — 토픽과 파티션

> **학습 목표**
> - 토픽의 생성·조회·변경·삭제 전체 수명주기를 CLI 로 다룬다
> - `kafka-configs.sh` 로 동적 설정을 바꾸고 `synonyms` 출력으로 **설정의 출처를 해독한다**
> - 파티션 수를 정하는 기준(처리량·병렬성·비용)을 표로 정리한다
> - 파티션을 늘려 보고 **줄이려다 실패하는 에러를 직접 재현한다**
> - **파티션 증가가 키 기반 순서 보장을 깨뜨리는 것을 murmur2 계산과 함께 실측한다**
> - 토픽 삭제가 비동기임을 `-delete` 디렉터리로 확인한다
> - 토픽 이름 규칙과 `.` / `_` 충돌을 재현한다
>
> **선행 스텝**: Step 02 — 아키텍처와 저장 구조
> **예상 소요**: 100분

---

## 3-0. 실습 준비

Step 01 의 토픽 4개가 있어야 합니다.

```bash
kt --list
```

**결과**

```
dlq
order-events
orders
payments
```

이 스텝은 `s03_` 접두사 토픽을 여러 개 만들고 마지막 절에서 전부 지웁니다. **공용 토픽 4개는 설정을 바꾸지 마세요.**

---

## 3-1. 토픽 수명주기 — 다섯 가지 동사

`kafka-topics.sh` 의 주요 동작은 다섯 개입니다.

| 동작 | 옵션 | 하는 일 |
|---|---|---|
| 생성 | `--create` | 토픽과 파티션을 만듦 |
| 조회(목록) | `--list` | 이름만 나열 |
| 조회(상세) | `--describe` | 파티션·리더·ISR·설정 |
| 변경 | `--alter` | **파티션 수만** 변경 가능 |
| 삭제 | `--delete` | 토픽 제거(비동기) |

여기서 이미 중요한 사실이 하나 있습니다. **`--alter` 로는 파티션 수밖에 못 바꿉니다.** `retention.ms` 같은 설정을 바꾸려면 `kafka-configs.sh` 를 써야 합니다(3-2). 예전 버전에서는 `kafka-topics.sh --alter --config` 가 됐지만 지금은 이렇게 거절합니다.

```bash
kt --alter --topic orders --config retention.ms=3600000
```

**결과**

```
Option "[config]" can't be used with option "[alter]"
```

실습용 토픽을 만듭니다.

```bash
kt --create --topic s03_demo \
  --partitions 3 --replication-factor 3 \
  --config retention.ms=86400000 \
  --config max.message.bytes=2097152
```

**결과**

```
Created topic s03_demo.
```

### `--describe` 의 유용한 변형들

```bash
kt --describe --topic s03_demo
```

**결과**

```
Topic: s03_demo	TopicId: mR4kT9wZQeu2nXsPvBdLyA	PartitionCount: 3	ReplicationFactor: 3	Configs: max.message.bytes=2097152,segment.bytes=1048576,retention.ms=86400000
	Topic: s03_demo	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: s03_demo	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: s03_demo	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
```

토픽을 지정하지 않으면 **전부** 나옵니다. 운영 클러스터에서는 수천 줄이 되므로 아래 필터들을 씁니다.

```bash
# 브로커 기본값과 다른 설정이 하나라도 있는 토픽만 (파티션 상세 줄 생략)
kt --describe --topics-with-overrides
```

**결과**

```
Topic: dlq	TopicId: bK9pQ4zXTgeR1sMvCwYuNA	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=2592000000
Topic: order-events	TopicId: 7hNvC2wRSmqXpLd0aBeTyQ	PartitionCount: 3	ReplicationFactor: 3	Configs: cleanup.policy=compact,min.insync.replicas=2,segment.bytes=1048576
Topic: orders	TopicId: qP3mZ8kLQ1u7nR2vXwYtBA	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
Topic: payments	TopicId: cV6tH8jFRnyU3kLdPqWxZg	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
Topic: s03_demo	TopicId: mR4kT9wZQeu2nXsPvBdLyA	PartitionCount: 3	ReplicationFactor: 3	Configs: max.message.bytes=2097152,segment.bytes=1048576,retention.ms=86400000
```

```bash
# ISR 이 Replicas 보다 적은 파티션만 = 복제가 뒤처진 곳
kt --describe --under-replicated-partitions

# 리더가 아예 없는 파티션만 = 읽기도 쓰기도 안 되는 상태
kt --describe --unavailable-partitions

# ISR 최소 개수를 못 채운 파티션만 = acks=all 쓰기가 실패하는 곳
kt --describe --under-min-isr-partitions
```

**결과** (셋 다 정상이면 아무 출력이 없습니다)

```
```

> 💡 **실무 팁 — 이 세 명령은 "출력이 없는 것이 정답"입니다**
> 모니터링 대시보드의 `UnderReplicatedPartitions` 지표가 곧 첫 번째 명령의 줄 수입니다. 브로커를 재시작하면 잠깐 값이 올라갔다가 복제가 따라잡으며 0으로 돌아옵니다.
> **롤링 재시작의 기본 절차**가 여기서 나옵니다: 브로커 하나를 내렸다 올린 뒤, `--under-replicated-partitions` 가 **빈 출력이 될 때까지 기다린 다음** 그다음 브로커로 넘어갑니다. 안 기다리고 연달아 내리면 ISR 이 `min.insync.replicas` 밑으로 떨어져 쓰기가 멈춥니다(Step 08, 14).

---

## 3-2. 설정 변경 — `kafka-configs.sh` 와 `synonyms` 해독

토픽 설정은 `kafka-configs.sh` 로 바꿉니다. **브로커 재시작 없이 즉시 적용**됩니다.

```bash
kconf --alter --entity-type topics --entity-name s03_demo \
  --add-config retention.ms=3600000
```

**결과**

```
Completed updating config for topic s03_demo.
```

확인합니다.

```bash
kconf --describe --entity-type topics --entity-name s03_demo
```

**결과**

```
Dynamic configs for topic s03_demo are:
  max.message.bytes=2097152 sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:max.message.bytes=2097152}
  retention.ms=3600000 sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:retention.ms=3600000}
```

여러 설정을 한 번에 바꾸려면 쉼표로 구분합니다.

```bash
kconf --alter --entity-type topics --entity-name s03_demo \
  --add-config 'retention.ms=7200000,segment.ms=600000,cleanup.policy=delete'
```

**결과**

```
Completed updating config for topic s03_demo.
```

되돌릴 때는 `--delete-config` 입니다. **값을 안 씁니다.**

```bash
kconf --alter --entity-type topics --entity-name s03_demo \
  --delete-config 'retention.ms,segment.ms'
```

**결과**

```
Completed updating config for topic s03_demo.
```

### `--all` 과 `synonyms` — 설정의 출처를 읽는다

`--describe` 만 하면 **토픽에 명시적으로 설정된 것만** 나옵니다. 실제로 적용 중인 값 전부를 보려면 `--all` 을 붙입니다.

```bash
kconf --describe --entity-type topics --entity-name s03_demo --all \
  | grep -E '^\s+(retention\.ms|segment\.bytes|cleanup\.policy|min\.insync\.replicas)'
```

**결과**

```
  cleanup.policy=delete sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:cleanup.policy=delete, DEFAULT_CONFIG:log.cleanup.policy=delete}
  min.insync.replicas=2 sensitive=false synonyms={STATIC_BROKER_CONFIG:min.insync.replicas=2, DEFAULT_CONFIG:min.insync.replicas=1}
  retention.ms=604800000 sensitive=false synonyms={DEFAULT_CONFIG:log.retention.ms=604800000}
  segment.bytes=1048576 sensitive=false synonyms={STATIC_BROKER_CONFIG:log.segment.bytes=1048576, DEFAULT_CONFIG:log.segment.bytes=1073741824}
```

**`synonyms` 는 이 설정값이 어디서 왔는지를 우선순위 순으로 나열한 목록입니다. 맨 앞이 이긴 값입니다.**

| 출처 | 뜻 | 우선순위 |
|---|---|---|
| `DYNAMIC_TOPIC_CONFIG` | 이 토픽에 `kafka-configs.sh` 로 설정 | 1 (가장 높음) |
| `DYNAMIC_BROKER_CONFIG` | 이 브로커 하나에 동적 설정 | 2 |
| `DYNAMIC_DEFAULT_BROKER_CONFIG` | 모든 브로커에 동적 설정 (`--entity-default`) | 3 |
| `STATIC_BROKER_CONFIG` | `server.properties` / 환경변수 (재시작 필요) | 4 |
| `DEFAULT_CONFIG` | Kafka 내장 기본값 | 5 (가장 낮음) |

위 출력을 한 줄씩 읽어 봅니다.

- **`min.insync.replicas=2`** — `STATIC_BROKER_CONFIG` 가 이겼습니다. docker-compose 의 `KAFKA_MIN_INSYNC_REPLICAS: 2` 입니다. Kafka 내장 기본값은 1 인데 덮인 것입니다.
- **`segment.bytes=1048576`** — 역시 브로커 설정이 이겼습니다. 내장 기본값 1073741824(1GiB)가 밀렸습니다. **토픽 설정 이름(`segment.bytes`)과 브로커 설정 이름(`log.segment.bytes`)이 다른 것**에 주목하세요. 대부분의 로그 설정이 브로커 레벨에서는 `log.` 접두사가 붙습니다.
- **`retention.ms=604800000`** — 방금 `--delete-config` 로 지웠으므로 `DEFAULT_CONFIG` 하나만 남았습니다. 즉 **Kafka 내장 기본값 7일**로 되돌아갔습니다.
- **`cleanup.policy=delete`** — 우리가 명시적으로 넣은 `DYNAMIC_TOPIC_CONFIG` 가 이깁니다. 값 자체는 기본값과 같지만 출처가 다릅니다.

> ⚠️ **함정 — "설정을 지웠다"와 "설정을 기본값으로 바꿨다"는 다릅니다**
> `--delete-config retention.ms` 는 그 토픽의 오버라이드를 없애 **브로커 기본값을 따르게** 만듭니다. 지금은 브로커 기본이 7일이니 7일이 됩니다.
> 그런데 나중에 누군가 브로커 기본값을 30일로 바꾸면, 이 토픽도 **말없이 30일이 됩니다.** 반면 `--add-config retention.ms=604800000` 로 7일을 명시해 뒀다면 브로커 기본이 바뀌어도 7일을 유지합니다.
> 어느 쪽이 옳은가는 의도에 달렸습니다. "우리 조직 표준을 따른다" 면 지우는 게 맞고, "이 토픽은 반드시 7일" 이면 명시하는 게 맞습니다. **문제는 대부분의 팀이 이 차이를 의식하지 않고 지운다는 것**이고, 몇 달 뒤 브로커 기본값 변경이 예상 못 한 토픽들의 보존 기간을 바꿔 놓습니다.
> **확인법**: `--all` 출력의 `synonyms` 에 `DYNAMIC_TOPIC_CONFIG` 가 있는지 보면 됩니다. 없으면 남의 기본값에 의존하고 있는 것입니다.

동적 설정은 브로커에도 걸 수 있습니다. **재시작 없이** 바뀝니다.

```bash
# 브로커 1번에만 적용
kconf --alter --entity-type brokers --entity-name 1 \
  --add-config log.cleaner.threads=2

# 모든 브로커에 적용 (cluster-wide default)
kconf --alter --entity-type brokers --entity-default \
  --add-config log.cleaner.threads=2
```

모든 설정이 동적으로 바뀌지는 않습니다. 안 되는 것은 이렇게 거절됩니다.

```bash
kconf --alter --entity-type brokers --entity-name 1 \
  --add-config auto.create.topics.enable=true
```

**결과**

```
Error while executing config command with args '--bootstrap-server kafka-1:9092 --alter --entity-type brokers --entity-name 1 --add-config auto.create.topics.enable=true'
java.lang.IllegalArgumentException: Cannot update these configs dynamically: Set(auto.create.topics.enable)
```

---

## 3-3. 파티션 수를 어떻게 정합니까

파티션 수는 Kafka 설계에서 가장 되돌리기 어려운 결정입니다. 기준을 세웁니다.

### 기본 계산식

```
필요 파티션 수 = max( 목표처리량 / 파티션당_프로듀서_처리량,
                     목표처리량 / 파티션당_컨슈머_처리량 )
```

파티션 하나가 감당하는 처리량은 환경에 따라 다르지만, 일반적인 기준은 이렇습니다.

- 프로듀서 쪽: 파티션당 **10 MB/s** 정도 (배치·압축에 크게 좌우)
- 컨슈머 쪽: **컨슈머 스레드 하나가 처리할 수 있는 속도**가 상한. 대개 이쪽이 병목입니다

예를 들어 목표가 100 MB/s 이고 컨슈머 인스턴스 하나가 5 MB/s 를 처리한다면, 컨슈머 쪽 요구가 20개입니다. 여기에 성장 여유를 곱합니다.

### 파티션 수가 결정하는 것들

| 항목 | 파티션이 적으면 | 파티션이 많으면 |
|---|---|---|
| **컨슈머 병렬성** | **상한이 낮습니다.** 파티션 3개면 컨슈머 4대째부터는 놉니다(Step 05) | 높습니다 |
| **순서 보장 범위** | 넓습니다. 파티션 1개면 토픽 전체 순서 보장 | 좁습니다. 같은 키 안에서만 |
| **파일 핸들** | 적습니다 | `파티션 × 세그먼트 × 4파일`. 수만~수십만 개 |
| **브로커 메모리** | 적습니다 | 파티션당 프로듀서 버퍼(`batch.size`)·인덱스 mmap |
| **리더 선출 시간** | 짧습니다 | 브로커 장애 시 **파티션 수에 비례**해 선출이 오래 걸림 |
| **end-to-end 지연** | 낮습니다 | 복제 대상이 많아져 미세하게 증가 |
| **리밸런싱 시간** | 짧습니다 | 컨슈머 그룹 리밸런싱이 오래 걸림(Step 05) |
| **재할당 비용** | 낮습니다 | 브로커 추가 시 옮길 데이터가 많음(Step 14) |

### 실무 권장

- **처음부터 넉넉하게, 그러나 무작정 크게는 말 것.** 목표 처리량의 2~3배를 감당할 수 있게 잡습니다.
- **브로커당 파티션 복제본 2,000개 이하**를 권장 상한으로 봅니다. KRaft 는 ZooKeeper 시절보다 훨씬 여유롭지만 파일 핸들과 메모리는 그대로입니다.
- **컨슈머 인스턴스 수의 배수**로 잡으면 할당이 균등해집니다. 컨슈머 4대에 파티션 6개면 2대는 2개씩, 2대는 1개씩 받아 불균형해집니다.
- **`orders` 는 3개면 충분합니다.** 이 코스의 실습 규모에서는요.

> 💡 **실무 팁 — 파티션 수를 정하기 전에 이것부터 물어보세요**
> "이 토픽에 순서 보장이 필요한가? 필요하다면 무엇의 순서인가?"
> 주문 상태 변경은 `order_id` 단위 순서만 필요하지 전체 순서는 필요 없습니다. 그래서 파티션을 많이 둬도 됩니다.
> 반대로 "감사 로그를 시간순으로 정확히" 라면 파티션 1개여야 하고, 그러면 처리량 상한이 파티션 하나 분량으로 고정됩니다. **순서와 처리량은 맞바꾸는 관계**이며, 이 결정이 파티션 수를 결정합니다.

---

## 3-4. 파티션은 늘릴 수만 있습니다

늘려 봅니다.

```bash
kt --alter --topic s03_demo --partitions 6
```

**결과**

```
```

(성공하면 아무 출력이 없습니다.) 확인합니다.

```bash
kt --describe --topic s03_demo
```

**결과**

```
Topic: s03_demo	TopicId: mR4kT9wZQeu2nXsPvBdLyA	PartitionCount: 6	ReplicationFactor: 3	Configs: max.message.bytes=2097152,cleanup.policy=delete,segment.bytes=1048576
	Topic: s03_demo	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: s03_demo	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: s03_demo	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: s03_demo	Partition: 3	Leader: 1	Replicas: 1,3,2	Isr: 1,3,2
	Topic: s03_demo	Partition: 4	Leader: 2	Replicas: 2,1,3	Isr: 2,1,3
	Topic: s03_demo	Partition: 5	Leader: 3	Replicas: 3,2,1	Isr: 3,2,1
```

**기존 파티션 0~2 는 그대로 있고 3~5 가 추가**되었습니다. 기존 데이터는 전혀 움직이지 않습니다. 이 점이 중요합니다 — 파티션 추가는 데이터 재배치를 하지 **않습니다.**

이제 줄여 봅니다.

```bash
kt --alter --topic s03_demo --partitions 2
```

**결과**

```
Error while executing topic command : Topic currently has 6 partitions, which is higher than the requested 2.
[2024-03-11 11:04:22,916] ERROR org.apache.kafka.common.errors.InvalidPartitionsException: Topic currently has 6 partitions, which is higher than the requested 2.
 (kafka.admin.TopicCommand$)
```

같은 수를 요청해도 거절됩니다.

```bash
kt --alter --topic s03_demo --partitions 6
```

**결과**

```
Error while executing topic command : Topic already has 6 partition(s).
[2024-03-11 11:04:41,203] ERROR org.apache.kafka.common.errors.InvalidPartitionsException: Topic already has 6 partition(s).
 (kafka.admin.TopicCommand$)
```

### 왜 줄일 수 없습니까

줄인다는 것은 **파티션 하나를 통째로 없앤다**는 뜻인데, 그 안의 데이터를 어떻게 할지에 답이 없습니다.

- **버린다** → 데이터 유실. 메시징 시스템으로서 받아들일 수 없습니다.
- **다른 파티션으로 옮긴다** → 옮긴 메시지의 오프셋이 달라집니다. 컨슈머가 커밋해 둔 오프셋이 전부 무의미해지고, 순서도 뒤섞입니다.
- **옮기면서 순서를 맞춘다** → 대상 파티션의 기존 메시지 오프셋을 전부 다시 매겨야 합니다. 사실상 토픽 재작성입니다.

세 선택지 모두 Kafka 의 근본 계약(오프셋은 불변, 파티션 안에서 순서 보장)을 깹니다. 그래서 아예 막아 두었습니다.

**줄이는 유일한 방법은 새 토픽을 만들어 옮기는 것입니다.**

```bash
# 1) 목표 파티션 수로 새 토픽 생성
kt --create --topic s03_demo_v2 --partitions 2 --replication-factor 3

# 2) 데이터를 옮긴다 (MirrorMaker 2, Kafka Connect, 또는 간단한 컨슈머-프로듀서 앱)
# 3) 프로듀서를 새 토픽으로 전환
# 4) 컨슈머가 옛 토픽을 다 소진하면 옛 토픽 삭제
```

이 과정에서 **키별 순서가 어차피 깨집니다.** 파티션 수가 달라지면 같은 키가 다른 파티션으로 가기 때문입니다. 그것이 바로 다음 절의 주제입니다.

---

## 3-5. 핵심 함정 — 파티션을 늘리면 키 순서 보장이 깨집니다

이 스텝에서 가장 중요한 실습입니다. **에러도 경고도 없이** 순서 보장이 사라지는 것을 직접 봅니다.

### 파티셔너가 하는 일

프로듀서는 키가 있는 메시지의 파티션을 이렇게 정합니다.

```
partition = (murmur2(keyBytes) & 0x7fffffff) % numPartitions
```

- `murmur2` 는 Kafka 가 쓰는 해시 함수입니다. 같은 키는 항상 같은 해시값입니다.
- `& 0x7fffffff` 은 부호 비트를 지워 양수로 만듭니다. 음수에 `%` 를 하면 음수가 나와 파티션 번호로 쓸 수 없기 때문입니다.
- **`% numPartitions` — 여기에 파티션 수가 들어갑니다.**

마지막 줄이 전부입니다. **파티션 수가 바뀌면 같은 키의 결과도 바뀝니다.**

### 실습 — 3파티션에 C001~C010 을 넣습니다

```bash
kt --create --topic s03_keyed --partitions 3 --replication-factor 3
```

**결과**

```
Created topic s03_keyed.
```

고객 10명의 주문을 한 건씩 넣습니다.

```bash
for i in $(seq -w 1 10); do
  echo "C0${i}:{\"order_id\":\"O-1${i}\",\"customer_id\":\"C0${i}\",\"seq\":1}"
done | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s03_keyed \
  --property parse.key=true --property key.separator=:
```

키별 파티션 배치를 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s03_keyed \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true \
  2>/dev/null | sort -t' ' -k1
```

**결과**

```
Partition:0	C002	{"order_id":"O-102","customer_id":"C002","seq":1}
Partition:0	C010	{"order_id":"O-110","customer_id":"C010","seq":1}
Partition:1	C006	{"order_id":"O-106","customer_id":"C006","seq":1}
Partition:1	C007	{"order_id":"O-107","customer_id":"C007","seq":1}
Partition:2	C001	{"order_id":"O-101","customer_id":"C001","seq":1}
Partition:2	C003	{"order_id":"O-103","customer_id":"C003","seq":1}
Partition:2	C004	{"order_id":"O-104","customer_id":"C004","seq":1}
Partition:2	C005	{"order_id":"O-105","customer_id":"C005","seq":1}
Partition:2	C008	{"order_id":"O-108","customer_id":"C008","seq":1}
Partition:2	C009	{"order_id":"O-109","customer_id":"C009","seq":1}
```

분포가 `2 / 2 / 6` 입니다. 균등하지 않습니다. **키가 10개뿐일 때는 이게 정상입니다.** 해시는 키가 충분히 많을 때에만 고르게 퍼집니다. 이것도 실무에서 겪는 문제입니다 — 키의 카디널리티가 파티션 수보다 별로 크지 않으면 특정 파티션에만 데이터가 몰립니다.

### murmur2 계산 표 — 우연이 아니라 산수입니다

각 키의 실제 계산 결과입니다.

| 키 | `murmur2(key)` | `& 0x7fffffff` | `% 3` | `% 6` | 이동 |
|---|---:|---:|---:|---:|:---:|
| `C001` | -1879186911 | 268296737 | **2** | **5** | ➜ 이동 |
| `C002` | -60361622 | 2087122026 | **0** | **0** | 유지 |
| `C003` | -1349803875 | 797679773 | **2** | **5** | ➜ 이동 |
| `C004` | -1629686289 | 517797359 | **2** | **5** | ➜ 이동 |
| `C005` | 1426376300 | 1426376300 | **2** | **2** | 유지 |
| `C006` | -1976685625 | 170798023 | **1** | **1** | 유지 |
| `C007` | 642592957 | 642592957 | **1** | **1** | 유지 |
| `C008` | -1566271287 | 581212361 | **2** | **5** | ➜ 이동 |
| `C009` | -130706346 | 2016777302 | **2** | **2** | 유지 |
| `C010` | -183298592 | 1964185056 | **0** | **0** | 유지 |

**10개 중 4개(C001, C003, C004, C008)가 파티션 2에서 5로 이동합니다.**

산술적으로 왜 이렇게 되는지 볼 수 있습니다. 6 = 2 × 3 이므로 `h % 6` 은 항상 `h % 3` 이거나 `h % 3 + 3` 입니다. 즉 키는 **제자리에 남거나 정확히 +3 만큼 밀립니다.** 위 표에서 파티션 3, 4로 간 키가 하나도 없는 이유가 이것입니다 — 파티션 0, 1 에 있던 키들은 전부 짝수/홀수 조건상 제자리에 남았습니다.

### 이제 6파티션으로 늘리고 같은 키를 다시 넣습니다

```bash
kt --alter --topic s03_keyed --partitions 6
```

같은 고객들의 **두 번째** 주문을 보냅니다.

```bash
for i in $(seq -w 1 10); do
  echo "C0${i}:{\"order_id\":\"O-2${i}\",\"customer_id\":\"C0${i}\",\"seq\":2}"
done | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s03_keyed \
  --property parse.key=true --property key.separator=:
```

**에러가 없습니다.** 전송은 완벽하게 성공했습니다. 이제 결과를 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s03_keyed \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true \
  --property print.offset=true \
  2>/dev/null | grep -E 'C001|C003' | sort
```

**결과**

```
Partition:2	Offset:0	C001	{"order_id":"O-101","customer_id":"C001","seq":1}
Partition:2	Offset:1	C003	{"order_id":"O-103","customer_id":"C003","seq":1}
Partition:5	Offset:0	C001	{"order_id":"O-201","customer_id":"C001","seq":2}
Partition:5	Offset:1	C003	{"order_id":"O-203","customer_id":"C003","seq":2}
```

**같은 키 `C001` 의 메시지가 파티션 2와 파티션 5에 나뉘어 들어갔습니다.**

전체 분포도 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s03_keyed \
  --from-beginning --timeout-ms 5000 \
  --property print.partition=true 2>/dev/null \
  | grep -oE '^Partition:[0-9]+' | sort | uniq -c
```

**결과**

```
   4 Partition:0
   4 Partition:1
   4 Partition:2
   4 Partition:5
```

파티션 2에는 seq=1 짜리 6건 중 4건이 남고 2건(C005, C009)은 seq=2도 여기 들어와서 4건, 파티션 5에는 새로 이동한 4건이 들어갔습니다.

> ⚠️ **함정 — 파티션 증가는 그 순간부터 키 순서 보장을 영구히 깨뜨립니다**
> **무엇이 일어났습니까**: `C001` 의 이벤트가 파티션 2와 5에 나뉘어 저장되었습니다. Kafka 는 **파티션 안에서만** 순서를 보장하므로, 이제 `C001` 의 두 이벤트 사이에는 **어떤 순서 보장도 없습니다.** 컨슈머 그룹에서 파티션 2와 5는 다른 컨슈머 인스턴스가 처리할 수도 있고, 같은 인스턴스라도 폴링 순서에 따라 seq=2 를 seq=1 보다 먼저 처리할 수 있습니다.
> **왜 위험합니까**: 에러가 없습니다. 프로듀서는 성공 콜백을 받았습니다. 컨슈머는 메시지를 다 받습니다. 개수도 맞습니다. **오직 순서만 조용히 틀립니다.**
> 실제 사고 형태는 이렇습니다. 주문 상태가 `CREATED → PAID → SHIPPED` 로 흘러야 하는데, 파티션 확장 직후 어떤 주문은 `SHIPPED` 가 `PAID` 보다 먼저 처리됩니다. 상태 머신이 "SHIPPED 상태에서 PAID 로 갈 수 없다"며 이벤트를 버리거나, 최종 상태가 `PAID` 로 굳어 배송된 주문이 미배송으로 남습니다. 며칠 뒤 CS 문의로 발견됩니다.
> **더 지독한 점**: 이 문제는 **파티션을 늘린 순간에만 발생하는 것이 아닙니다.** 이미 저장된 옛 데이터와 새 데이터가 다른 파티션에 있는 상태가 **보존 기간 내내 지속**됩니다. `orders` 라면 7일간 이 상태입니다. 그 사이 컨슈머를 처음부터 재처리하면 순서가 뒤섞인 채로 읽습니다.
>
> **해결책 세 가지**
> 1. **파티션 수를 처음부터 넉넉히 잡고 절대 바꾸지 않는다** — 가장 흔하고 가장 확실합니다. 목표 처리량의 2~3배로 잡습니다. 파티션이 남아도는 비용은 순서가 깨지는 비용보다 훨씬 쌉니다.
> 2. **새 토픽을 만들어 마이그레이션한다** — `orders_v2` 를 원하는 파티션 수로 만들고, 컨슈머가 옛 토픽을 완전히 소진한 뒤 프로듀서를 전환합니다. 순서가 깨지는 구간이 없습니다. 다만 전환 절차가 복잡합니다.
> 3. **커스텀 파티셔너를 쓴다** — `Partitioner` 인터페이스를 구현해 `numPartitions` 에 의존하지 않는 매핑을 만듭니다. 예를 들어 `customer_id` 의 뒷자리 두 숫자로 0~99 버킷을 만들고, 버킷 → 파티션 매핑 테이블을 별도로 관리합니다. 파티션을 늘려도 기존 버킷의 매핑을 유지하면 순서가 보존됩니다. 구현·운영 비용이 크므로 정말 필요할 때만 씁니다.
>
> **절대 하면 안 되는 것**: "파티션 늘렸으니 컨슈머를 처음부터 다시 돌려서 정리하자" 는 도움이 안 됩니다. 데이터가 이미 두 파티션에 나뉘어 있으므로 몇 번을 다시 읽어도 순서는 복원되지 않습니다.

> 💡 **파티션을 꼭 늘려야 한다면 이렇게 확인하세요**
> ```bash
> # 늘리기 전에 현재 키 분포를 저장해 둡니다
> kt --describe --topic orders > /tmp/before.txt
> ```
> 그리고 **트래픽이 가장 적은 시간에** 늘린 뒤, 컨슈머 애플리케이션이 순서 의존적인지 점검합니다. 상태 머신이 "이전 상태와 무관하게 마지막 이벤트를 반영"하는 형태(멱등)라면 파티션 확장의 영향이 훨씬 작습니다.
> 애초에 **이벤트를 순서 의존적으로 설계하지 않는 것**이 가장 근본적인 해결책입니다. 각 이벤트에 버전 번호나 타임스탬프를 넣고, 컨슈머가 "더 오래된 이벤트면 무시" 하도록 만들면 순서가 뒤바뀌어도 최종 상태가 맞습니다.

---

## 3-6. 토픽 삭제의 실제 동작

토픽 삭제는 **즉시 파일이 지워지는 것이 아닙니다.**

먼저 삭제가 가능한 클러스터인지 확인합니다.

```bash
kconf --describe --entity-type brokers --entity-name 1 --all | grep 'delete.topic.enable'
```

**결과**

```
  delete.topic.enable=true sensitive=false synonyms={DEFAULT_CONFIG:delete.topic.enable=true}
```

`true` 가 기본값입니다(Kafka 1.0 부터). `false` 인 클러스터에서 삭제를 시도하면 이렇게 됩니다.

```
Error while executing topic command : Topic deletion is disabled.
org.apache.kafka.common.errors.TopicDeletionDisabledException: Topic deletion is disabled.
```

이제 지웁니다. **디렉터리를 감시하면서** 지워야 관찰할 수 있습니다.

```bash
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_demo-* | head -3
```

**결과**

```
/var/lib/kafka/data/s03_demo-0
/var/lib/kafka/data/s03_demo-1
/var/lib/kafka/data/s03_demo-2
```

```bash
kt --delete --topic s03_demo
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_demo-*
```

**결과** (삭제 직후 몇 초 안에 실행했다면)

```
/var/lib/kafka/data/s03_demo-0.8f3a2c91d47b4e6ca1057b3e2f9d6a10-delete
/var/lib/kafka/data/s03_demo-1.2b7e4f18c93a4d5fb8e10c2a7d4f9e33-delete
/var/lib/kafka/data/s03_demo-2.5c9d1a72e86f4b3ea2d70f4c8b1e5a27-delete
/var/lib/kafka/data/s03_demo-3.7a2f5c30b91d4e8fa5c31d90e7b2f4a66-delete
/var/lib/kafka/data/s03_demo-4.1e8b3d94f52c4a7db6f92e05c3a8d1b44-delete
/var/lib/kafka/data/s03_demo-5.4d6c8e21a73f4b95c8e04a1f6d2b7e099-delete
```

**디렉터리 이름이 `원래이름.랜덤UUID-delete` 로 바뀌었습니다.**

삭제는 두 단계입니다.

```
① 브로커가 디렉터리를 `-delete` 접미사로 rename 한다      ← 즉시
     ↓ file.delete.delay.ms (기본 60000 = 60초) 대기
② 백그라운드 스레드가 실제로 파일을 지운다                ← 나중
```

60초 뒤 다시 확인합니다.

```bash
sleep 65
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_demo-* 2>&1
```

**결과**

```
ls: cannot access '/var/lib/kafka/data/s03_demo-*': No such file or directory
```

사라졌습니다.

### 왜 두 단계로 합니까

**rename 은 원자적이고 즉시 끝나지만, 삭제는 오래 걸립니다.** 세그먼트가 수천 개인 파티션을 지우는 데 수십 초가 걸릴 수 있고, 그동안 브로커의 요청 처리 스레드를 붙잡고 있으면 클러스터 전체가 느려집니다. 그래서 이름만 바꿔 "이건 이제 없는 것" 으로 만들고, 실제 삭제는 백그라운드로 미룹니다.

`-delete` 접미사에 UUID 가 붙는 이유도 명확합니다. **같은 이름의 토픽을 즉시 다시 만들 수 있게** 하기 위해서입니다. UUID 가 없다면 `s03_demo-0-delete` 가 이미 있는 상태에서 또 지우면 충돌합니다.

> ⚠️ **함정 — 토픽을 지우고 바로 같은 이름으로 만들면 클라이언트가 혼란스러워합니다**
> ```bash
> kt --delete --topic s03_demo
> kt --create --topic s03_demo --partitions 3 --replication-factor 3
> ```
> 이건 성공합니다. 하지만 **`TopicId` 가 달라진 완전히 새 토픽**입니다(Step 02 의 `partition.metadata`). 그런데 옛 토픽의 메타데이터를 캐시하고 있던 클라이언트는 잠시 이런 에러를 받습니다.
> ```
> org.apache.kafka.common.errors.UnknownTopicIdException: This server does not host this topic ID.
> ```
> 대개 클라이언트가 메타데이터를 새로고침하며 스스로 복구하지만, 그 사이 몇 초간 전송이 실패합니다. 더 나쁜 것은 **컨슈머 그룹의 커밋된 오프셋이 그대로 남아 있다**는 점입니다. 새 토픽은 비어 있는데 그룹은 "오프셋 15000 까지 읽었다"고 기억하고 있어서, 컨슈머를 붙이면 아무것도 안 읽거나 `OFFSET_OUT_OF_RANGE` 로 리셋됩니다.
> **해결**: 토픽을 재생성할 때는 **관련 컨슈머 그룹도 함께 지우세요.**
> ```bash
> kcg --delete --group my-group
> ```
> 그리고 삭제 후 재생성 사이에 최소 몇 초는 두는 편이 안전합니다. 가능하면 **다른 이름을 쓰는 것**(`_v2`)이 훨씬 낫습니다.

---

## 3-7. 토픽 이름 규칙

Kafka 토픽 이름에는 제약이 있습니다.

| 규칙 | 내용 |
|---|---|
| 허용 문자 | 영숫자, `.`(점), `_`(밑줄), `-`(하이픈) **만** |
| 최대 길이 | **249자** |
| 금지 이름 | `.` 과 `..` |
| 대소문자 | 구분함 (`Orders` 와 `orders` 는 다른 토픽) |
| 예약 접두사 | `__` 로 시작하는 것은 내부 토픽 관례 (`__consumer_offsets`) |

규칙을 어기면 명확하게 실패합니다.

```bash
kt --create --topic 'orders!' --partitions 1 --replication-factor 3
```

**결과**

```
Error while executing topic command : Topic name "orders!" is illegal, it contains a character other than ASCII alphanumerics, '.', '_' and '-'
org.apache.kafka.common.errors.InvalidTopicException: Topic name "orders!" is illegal, it contains a character other than ASCII alphanumerics, '.', '_' and '-'
```

### `.` 과 `_` 의 충돌 — 이건 예상 못 합니다

```bash
kt --create --topic s03.metrics --partitions 1 --replication-factor 3
```

**결과**

```
Created topic s03.metrics.
```

이제 밑줄 버전을 만들어 봅니다.

```bash
kt --create --topic s03_metrics --partitions 1 --replication-factor 3
```

**결과**

```
Error while executing topic command : Topic 's03_metrics' collides with existing topics: s03.metrics
org.apache.kafka.common.errors.InvalidTopicException: Topic 's03_metrics' collides with existing topics: s03.metrics
```

**둘 다 유효한 이름인데 함께 존재할 수 없습니다.**

이유는 **JMX 메트릭 이름** 때문입니다. Kafka 는 토픽별 메트릭을 이런 이름으로 노출합니다.

```
kafka.log:type=Log,name=Size,topic=s03.metrics,partition=0
```

그런데 여러 모니터링 시스템(Graphite, 일부 JMX 익스포터)이 메트릭 경로에서 `.` 을 계층 구분자로 쓰거나 `.` 을 `_` 로 치환합니다. 그러면 `s03.metrics` 와 `s03_metrics` 의 메트릭이 **같은 이름으로 뭉개져** 구분이 안 됩니다. 두 토픽의 처리량 그래프가 섞여 버립니다.

Kafka 는 이 사고를 아예 막기 위해 **`.` 을 `_` 로 치환했을 때 같아지는 이름을 금지**합니다.

> 💡 **실무 팁 — 토픽 이름 컨벤션**
> 충돌을 피하고 조직 규모에서 관리 가능하게 하려면 **하나의 구분자만 쓰는 것**이 원칙입니다. 이 코스는 `-`(하이픈)을 씁니다(`order-events`).
> 널리 쓰이는 컨벤션 하나를 소개합니다.
> ```
> <도메인>.<데이터종류>.<엔티티>.<버전>
> 예: shop.event.order-created.v1
>     shop.entity.customer.v1
>     shop.dlq.order-processor.v1
> ```
> - **도메인/팀 접두사**는 필수입니다. 수백 개 토픽이 되면 `orders` 라는 이름을 두 팀이 원합니다. ACL 도 접두사 단위(`shop.*`)로 거는 것이 편합니다.
> - **버전 접미사**는 나중에 큰 도움이 됩니다. 스키마 호환이 깨지거나(Step 10) 파티션 수를 바꿔야 할 때(3-5), `.v2` 로 새 토픽을 만들어 마이그레이션할 수 있습니다.
> - **`.` 과 `-` 를 섞어 쓰되 `_` 는 쓰지 않는 것**이 위 충돌을 피하는 실용적인 규칙입니다. 단, 계층 구분에 `.` 을 쓰면 메트릭 시스템에서 경로가 깊어지니, 팀에서 한 번 정해 문서화하세요.
> - 이름은 **한 번 정하면 못 바꿉니다.** Kafka 에는 토픽 rename 이 없습니다. 새로 만들어 옮기는 것뿐입니다.

---

## 3-8. 정리 (실습 마무리)

이 스텝에서 만든 `s03_` 토픽을 전부 지웁니다.

```bash
kt --list | grep -E '^s03[._]'
```

**결과**

```
s03.metrics
s03_keyed
```

(`s03_demo` 는 3-6 에서 이미 지웠습니다. `s03_metrics` 는 충돌로 생성되지 않았습니다.)

```bash
kt --list | grep -E '^s03[._]' | while read t; do
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka-1:9092 --delete --topic "$t"
done
```

확인합니다.

```bash
kt --list
```

**결과**

```
dlq
order-events
orders
payments
```

공용 토픽 4개만 남았습니다. 설정이 변경되지 않았는지도 확인합니다.

```bash
kt --describe --topics-with-overrides
```

**결과**

```
Topic: dlq	TopicId: bK9pQ4zXTgeR1sMvCwYuNA	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=2592000000
Topic: order-events	TopicId: 7hNvC2wRSmqXpLd0aBeTyQ	PartitionCount: 3	ReplicationFactor: 3	Configs: cleanup.policy=compact,min.insync.replicas=2,segment.bytes=1048576
Topic: orders	TopicId: qP3mZ8kLQ1u7nR2vXwYtBA	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
Topic: payments	TopicId: cV6tH8jFRnyU3kLdPqWxZg	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
```

`orders` 가 여전히 **PartitionCount: 3** 인지 반드시 확인하세요. 실습 중 실수로 `orders` 를 6으로 늘렸다면 되돌릴 방법이 없고, 이후 스텝의 출력이 교재와 달라집니다. 그런 경우 `docker compose down -v && docker compose up -d` 로 초기화하고 Step 01 부터 다시 하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `kafka-topics.sh --alter` | **파티션 수만** 변경. 설정은 `kafka-configs.sh` |
| `--topics-with-overrides` | 브로커 기본값과 다른 설정을 가진 토픽만 |
| `--under-replicated-partitions` | **출력이 없어야 정상.** 롤링 재시작의 진행 기준 |
| `kafka-configs.sh --add-config` | 재시작 없이 즉시 적용. 쉼표로 여러 개 |
| `--delete-config` | 오버라이드 제거 = **브로커 기본값을 따르게 됨** (기본값으로 고정이 아님) |
| `synonyms` | 설정의 출처를 우선순위 순으로. **맨 앞이 이긴 값** |
| 우선순위 | DYNAMIC_TOPIC > DYNAMIC_BROKER > DYNAMIC_DEFAULT > STATIC_BROKER > DEFAULT |
| 파티션 수 | 컨슈머 병렬성의 **상한**. 순서 보장 범위와 맞바꿈 |
| 파티션 증가 | 가능. 기존 데이터는 **이동하지 않음** |
| 파티션 감소 | **불가능.** `InvalidPartitionsException`. 새 토픽으로 옮기는 수밖에 |
| 파티셔너 | `(murmur2(key) & 0x7fffffff) % numPartitions` |
| **키 순서 파괴** | 파티션을 늘리면 같은 키가 **다른 파티션**으로. 에러 없음. 보존 기간 내내 지속 |
| 해결책 | 넉넉히 잡고 고정 / 새 토픽 마이그레이션 / 커스텀 파티셔너 |
| 토픽 삭제 | rename → `-delete` 접미사 → `file.delete.delay.ms`(60초) 후 실제 삭제 |
| 재생성 | `TopicId` 가 바뀜. **컨슈머 그룹도 함께 지울 것** |
| 이름 규칙 | 영숫자 `.` `_` `-` 만, 249자, `.`/`_` 치환 충돌 금지 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`.

1. `s03_ex1` 을 파티션 2, RF 3, 보존 10분으로 만들고 `--describe` 로 검증하기
2. `s03_ex1` 의 `retention.ms` 를 1시간으로 바꾼 뒤 `--delete-config` 로 되돌리고, `--all` 출력의 `synonyms` 가 어떻게 달라지는지 비교하기
3. 파티션을 2 → 4 로 늘리고, 다시 3 으로 줄이려 시도해 **정확한 에러 메시지**를 기록하기
4. `s03_ex2`(파티션 2)에 `O-1001`~`O-1010` 을 키로 넣어 파티션 분포를 세고, 4파티션으로 늘린 뒤 같은 키를 다시 넣어 **몇 개의 키가 이동했는지** 세기
5. 문제 4 에서 이동한 키 하나를 골라, 그 키의 두 메시지가 서로 다른 파티션에 있음을 `print.partition=true` 로 증명하기
6. 토픽을 지운 직후 데이터 디렉터리에서 `-delete` 접미사 디렉터리를 포착하고, 60초 뒤 사라지는 것 확인하기
7. `s03.audit` 을 만든 뒤 `s03_audit` 을 만들어 충돌 에러를 재현하고, 왜 그런지 한 문장으로 설명하기

---

## 다음 단계

여기까지가 **브로커 쪽 이야기**였습니다. 토픽이 어떻게 저장되고 파티션이 무엇을 결정하는지 알았습니다.

이제 클라이언트로 넘어갑니다. 다음 스텝부터 이 코스의 본론 — **"에러 없이 조용히 틀리는"** 구간이 시작됩니다. 프로듀서의 `acks` 를 0/1/all 로 바꿔 가며 처리량과 유실 위험을 실측하고, `batch.size`/`linger.ms`/압축의 트레이드오프를 숫자로 확인합니다. 그리고 이 스텝에서 본 "같은 키는 같은 파티션이므로 순서 보장" 이라는 약속이 **`max.in.flight.requests.per.connection > 1` 과 재시도의 조합으로 깨지는 것**을 재현합니다. 파티션이 그대로여도 순서는 뒤바뀔 수 있습니다.

→ [Step 04 — 프로듀서](../step-04-producer/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 로 3-0 ~ 3-8 을 재현하고, `exercise.sh` 의 7문제를 푼 뒤 `solution.sh` 로 대조합니다. 이 스텝의 스크립트는 **토픽을 만들고 지우는 것이 실습의 본체**라 다른 스텝보다 뒷정리 구간이 깁니다. 터미널 2개가 필요한 구간은 없습니다.

### practice.sh

본문 3-0 ~ 3-8 의 모든 명령을 절 번호 주석과 함께 담았습니다.

- 상단의 `K()` / `Ki()` 헬퍼와 `BS=kafka-1:9092` 는 Step 01 과 같습니다. 추가로 `PARTS()` 헬퍼가 있는데, 특정 토픽의 파티션별 메시지 수를 `Partition:N` 으로 세어 한 줄로 요약합니다. 3-5 의 분포 확인에서 반복해서 씁니다.
- `[3-1]` 의 첫 명령은 **일부러 실패하는** `kt --alter --topic orders --config ...` 입니다. `Option "[config]" can't be used with option "[alter]"` 를 직접 보게 하려는 것이며, `|| true` 로 감쌌습니다. 옛 문서를 보고 이 형태를 치는 사람이 많아 먼저 보여 줍니다.
- `[3-2]` 는 `--add-config` → `--describe` → `--add-config`(여러 개) → `--delete-config` → `--describe --all` 순서로 배치돼 있습니다. **`--delete-config` 전후의 `synonyms` 를 비교하는 것이 이 구간의 목적**이므로, 두 출력을 각각 `/tmp/before-synonyms.txt`, `/tmp/after-synonyms.txt` 에 저장하고 `diff` 를 찍습니다.
- `[3-4]` 는 성공하는 `--partitions 6`, 실패하는 `--partitions 2`, 실패하는 `--partitions 6`(같은 수) 세 가지를 연달아 실행합니다. 뒤 두 개는 `|| true` 로 감쌌으며, 에러 메시지 전문이 출력에 남습니다.
- `[3-5]` 가 이 스크립트의 핵심입니다. `s03_keyed` 를 3파티션으로 만들고 → `seq -w 1 10` 루프로 C001~C010 을 넣고 → 분포를 세고 → 6파티션으로 늘리고 → **같은 키를 다시 넣고** → 다시 분포를 셉니다. 중간에 `sleep 2` 가 두 번 들어 있는데, 파티션 확장 후 프로듀서가 새 메타데이터를 받아 오는 데 시간이 필요하기 때문입니다. **이 sleep 을 빼면 프로듀서가 옛 파티션 수(3)로 계산해 버려 함정이 재현되지 않습니다.**
- `[3-6]` 의 삭제 관찰은 `kt --delete` 직후 **즉시** `ls -d` 를 실행합니다. 그다음 `sleep 65` 로 60초를 기다리고 다시 확인해 디렉터리가 사라진 것을 봅니다. 스크립트가 1분 넘게 멈추므로 `bash -x` 로 진행을 보며 돌리는 편이 낫습니다.
- `[3-7]` 은 `orders!`(문자 위반), `s03.metrics` 생성 후 `s03_metrics`(충돌) 두 가지 실패를 재현합니다. 둘 다 `|| true` 입니다.
- 마지막 `[3-8]` 은 `s03[._]` 로 시작하는 토픽을 **while 루프로 전부 삭제**하고, `kt --list` 가 정확히 4줄인지, `orders` 의 `PartitionCount` 가 여전히 3 인지 검증합니다. 후자가 특히 중요합니다 — 실습 중 `orders` 를 잘못 늘렸다면 되돌릴 방법이 없기 때문입니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·2·3·6·7** 은 명령을 직접 작성하는 문제이고, **문제 4·5** 는 스크립트가 토픽과 데이터를 준비해 준 뒤 **결과를 세고 해석**하는 문제입니다.
- 문제 2 는 `synonyms` 를 두 번 찍어 비교하게 합니다. `--delete-config` 후에 `DYNAMIC_TOPIC_CONFIG` 항목이 사라지고 `DEFAULT_CONFIG` 만 남는 것이 답이며, "값이 기본값으로 **고정**된 것이 아니라 기본값을 **따르게** 된 것" 이라는 차이를 답으로 적게 합니다.
- 문제 3 은 에러 메시지를 그대로 기록하게 합니다. `Topic currently has 4 partitions, which is higher than the requested 3.` 과 예외 클래스 이름 `InvalidPartitionsException` 두 가지를 다 적어야 정답입니다. 예외 이름을 아는 것이 검색과 로그 알림 설정에서 실제로 유용하기 때문입니다.
- 문제 4·5 는 본문의 `C001~C010` / 3→6 과 **다른 조건**(`O-1001~O-1010` / 2→4)을 씁니다. 본문 표를 그대로 베낄 수 없게 한 것이며, 직접 세어 봐야 답이 나옵니다.
- 문제 6 은 타이밍이 중요합니다. 문제지 주석에 "삭제 명령과 `ls` 사이에 다른 명령을 넣지 마세요" 라고 명시해 두었습니다. `file.delete.delay.ms` 가 60초라 여유는 있지만, 셸 히스토리를 뒤지다 놓치는 경우가 많습니다.
- 문제 7 은 명령 두 줄과 **한 문장짜리 설명**을 요구합니다. "JMX 메트릭 이름에서 `.` 이 `_` 로 치환되면 두 토픽이 구분되지 않기 때문" 이 정답입니다.

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답과 `# 해설:` 주석입니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `--config retention.ms=600000`(10분)입니다. 해설에서 자주 쓰는 밀리초 값(10분/1시간/1일/7일/30일/무제한 -1)을 표로 정리하고, `--describe` 의 `Configs` 에는 **토픽 레벨로 명시한 것만** 나오므로 `min.insync.replicas` 가 안 보이는 것이 정상이라고 짚습니다.
- **정답 2** 의 핵심은 `diff` 결과입니다. 삭제 전에는 `synonyms={DYNAMIC_TOPIC_CONFIG:retention.ms=3600000, DEFAULT_CONFIG:log.retention.ms=604800000}` 이고, 삭제 후에는 `synonyms={DEFAULT_CONFIG:log.retention.ms=604800000}` 입니다. 해설이 이 차이를 "브로커 기본값이 바뀌면 이 토픽도 따라 바뀌게 된 상태" 로 해석하고, 본문 3-2 의 함정과 연결합니다.
- **정답 3** 은 에러 메시지 전문과 예외 클래스입니다. 해설에서 **왜** 줄일 수 없는지 세 선택지(버린다/옮긴다/재작성한다)를 다시 정리하고, 실무에서 정말 줄여야 할 때의 마이그레이션 절차 4단계를 적습니다.
- **정답 4** 는 실제 계산 결과를 표로 제시합니다. `O-1001`~`O-1010` 의 `murmur2 & 0x7fffffff` 값과 `% 2`, `% 4` 를 나열하면 **10개 중 5개가 이동**합니다 — `O-1002`·`O-1004`·`O-1010` 이 파티션 1에서 3으로, `O-1006`·`O-1009` 가 0에서 2로 갑니다. 해설에서 4 = 2×2 이므로 키는 제자리이거나 정확히 **+2 만큼** 밀린다는 것을 설명합니다. 본문의 3→6(+3) 과 같은 원리입니다.
- **정답 5** 는 `grep` 으로 한 키의 두 줄을 뽑아 `Partition:` 값이 다른 것을 보여 줍니다. 해설이 여기서 실무 시나리오를 구체적으로 씁니다 — 그 두 메시지를 **다른 컨슈머 인스턴스가 동시에** 처리할 수 있고, 상태 머신이 역순 전이를 만나 이벤트를 버리거나 최종 상태가 틀어진다는 것입니다.
- **정답 6** 은 `-delete` 접미사 디렉터리 목록과, 65초 뒤의 `No such file or directory` 입니다. 해설에서 rename → 지연 삭제 2단계 구조와 그 이유(rename 은 원자적·즉시, 삭제는 오래 걸려 브로커 스레드를 붙잡음)를 설명하고, UUID 가 붙는 이유(같은 이름 재생성 대비)까지 짚습니다.
- **정답 7** 은 충돌 에러 전문과 한 문장 설명입니다. 해설이 JMX 메트릭 이름(`kafka.log:type=Log,name=Size,topic=s03.audit,partition=0`)을 실제로 보여 주고, Graphite 같은 시스템이 `.` 을 계층 구분자로 쓰기 때문에 두 토픽이 뭉개진다는 것을 설명합니다. 마지막에 토픽 이름 컨벤션 권장안(`<도메인>.<종류>.<엔티티>.<버전>`, `_` 는 쓰지 않기)으로 마무리합니다.

```bash file="./solution.sh"
```
