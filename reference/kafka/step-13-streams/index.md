# Step 13 — Kafka Streams

> **학습 목표**
> - Streams 가 별도 클러스터가 아니라 Consumer + Producer 위에 얹힌 **라이브러리**임을 설명한다
> - `topology.describe()` 출력을 읽고 서브토폴로지 경계를 판별한다
> - KStream / KTable / GlobalKTable 의 차이와 조인 가능 조합을 표로 정리한다
> - **`selectKey` 뒤에 repartition 토픽이, 집계 뒤에 changelog 토픽이 자동 생성되는 것을 `kt --list` 로 직접 확인한다**
> - 윈도우 4종을 비교하고, **grace period 를 지나 도착한 레코드가 조용히 버려지는 것을 재현한다**
> - **co-partitioning 위반으로 `TopologyException` 이 나는 것을 재현한다**
> - `kafka-streams-application-reset.sh` 로 내부 토픽과 상태 저장소를 정리한다
>
> **선행 스텝**: Step 12 — Kafka Connect
> **예상 소요**: 120분

---

## 13-0. 실습 준비

이 스텝은 `Practice.java` 하나로 진행합니다. 시나리오를 인자로 골라 실행합니다.

```bash
cd docs/reference/kafka/step-13-streams
docker cp Practice.java kafka-1:/tmp/
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java'
```

**결과**

```
usage: java -cp "/opt/kafka/libs/*" Practice.java <scenario>

  topology   토폴로지 설명만 출력하고 종료 (실행하지 않음)
  stateless  filter / map / mapValues / flatMap / branch / merge / peek
  selectkey  selectKey → repartition 토픽이 생기는 것을 관찰
  count      groupByKey().count() → changelog 토픽과 상태 저장소
  window     1분 텀블링 윈도우 집계 + grace period
  suppress   suppress(untilWindowCloses) 로 최종 결과만
  join       orders × payments 윈도우 조인
  copartition co-partitioning 위반 재현 (TopologyException)
  eos        processing.guarantee=exactly_once_v2

  예) java -cp "/opt/kafka/libs/*" Practice.java count
```

`/opt/kafka/libs/` 에 `kafka-streams-3.7.1.jar` 이 이미 들어 있으므로 **별도 빌드 도구가 필요 없습니다.** 확인해 봅니다.

```bash
docker exec kafka-1 ls /opt/kafka/libs/ | grep -E 'kafka-streams|rocksdb'
```

**결과**

```
kafka-streams-3.7.1.jar
kafka-streams-examples-3.7.1.jar
rocksdbjni-7.9.2.jar
```

`rocksdbjni` 가 상태 저장소의 실체입니다. 13-6 에서 다시 봅니다.

실습 토픽을 만듭니다.

```bash
kt --create --topic s13_orders   --partitions 3 --replication-factor 3
kt --create --topic s13_payments --partitions 3 --replication-factor 3
kt --create --topic s13_out      --partitions 3 --replication-factor 3
kt --create --topic s13_left     --partitions 3 --replication-factor 3
kt --create --topic s13_right    --partitions 6 --replication-factor 3
```

**결과**

```
Created topic s13_orders.
Created topic s13_payments.
Created topic s13_out.
Created topic s13_left.
Created topic s13_right.
```

`s13_right` 만 파티션이 **6개**입니다. 13-9 의 co-partitioning 함정을 위한 의도적 설정입니다.

**시작 시점의 토픽 목록을 기록해 둡니다.** 13-7 에서 before/after 를 비교합니다.

```bash
kt --list > /tmp/topics-before.txt
cat /tmp/topics-before.txt
```

**결과**

```
__consumer_offsets
dlq
order-events
orders
payments
s13_left
s13_orders
s13_out
s13_payments
s13_right
```

---

## 13-1. Streams 는 라이브러리입니다

Kafka Streams 를 처음 보면 "Spark 나 Flink 같은 처리 클러스터"를 떠올리기 쉽습니다. 아닙니다.

| | Spark / Flink | Kafka Streams |
|---|---|---|
| 배포 형태 | 클러스터 + 잡 제출 | **일반 JAR. `java -jar` 로 실행** |
| 리소스 관리 | YARN / K8s / 자체 매니저 | 없음. 프로세스가 전부 |
| 확장 | 클러스터에 노드 추가 | **같은 `application.id` 로 프로세스를 하나 더 띄움** |
| 상태 저장 | 체크포인트 (HDFS/S3) | **로컬 RocksDB + changelog 토픽** |
| 장애 복구 | 잡 매니저가 재실행 | **컨슈머 그룹 리밸런싱** |
| 의존성 | 별도 클러스터 | **Kafka 뿐** |

Streams 애플리케이션의 실체를 벗겨 보면 이렇습니다.

```
   여러분의 Streams 앱 (평범한 JVM 프로세스)
   ┌───────────────────────────────────────────────────────────┐
   │  StreamThread 1                StreamThread 2             │
   │  ┌──────────────────┐          ┌──────────────────┐       │
   │  │ Consumer         │          │ Consumer         │       │
   │  │   ↓ poll()       │          │   ↓ poll()       │       │
   │  │ Task 0_0         │          │ Task 0_1         │       │
   │  │   → 처리 로직     │          │   → 처리 로직     │       │
   │  │   → RocksDB      │          │   → RocksDB      │       │
   │  │   ↓              │          │   ↓              │       │
   │  │ Producer         │          │ Producer         │       │
   │  └──────────────────┘          └──────────────────┘       │
   └───────────────────────────────────────────────────────────┘
             │                              │
             ▼                              ▼
      Kafka: 입력 토픽 / changelog / repartition / 출력 토픽
```

**컨슈머 그룹 ID 는 `application.id` 와 같습니다.** 그래서 Step 05 에서 배운 것이 전부 그대로 적용됩니다.

```bash
kcg --list
```

**결과** (앱을 띄운 뒤)

```
s13-count-app
```

`kcg --describe --group s13-count-app` 로 랙도 그대로 확인됩니다. **Streams 앱의 랙 모니터링은 일반 컨슈머와 완전히 동일합니다.**

> 💡 **실무 팁 — 확장은 프로세스를 더 띄우는 것뿐입니다**
> 파티션이 3개인 토픽을 처리하는 Streams 앱은 태스크가 3개 생깁니다. 프로세스 1개면 3개를 다 처리하고, 프로세스를 3개 띄우면 각각 1개씩 나눠 갖습니다. **코드도 설정도 안 바꿉니다.**
> 프로세스 4개를 띄우면 하나는 놉니다. Step 05 의 "컨슈머 수 > 파티션 수이면 논다"가 그대로입니다.
> `num.stream.threads` 로 한 프로세스 안의 스레드 수를 늘릴 수도 있습니다. 프로세스 1개 × 스레드 3개와 프로세스 3개 × 스레드 1개는 처리 병렬성 면에서 같습니다.

---

## 13-2. Topology — 무엇을 실행할지의 설계도

Streams DSL 로 쓴 코드는 실행 전에 **Topology(처리 그래프)** 로 컴파일됩니다. `describe()` 로 볼 수 있습니다.

```java
StreamsBuilder b = new StreamsBuilder();
b.stream("orders", Consumed.with(Serdes.String(), Serdes.String()))
 .filter((k, v) -> v.contains("CREATED"))
 .mapValues(v -> v.toUpperCase())
 .to("s13_out", Produced.with(Serdes.String(), Serdes.String()));

Topology topology = b.build();
System.out.println(topology.describe());
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java topology'
```

**결과**

```
Topologies:
   Sub-topology: 0
    Source: KSTREAM-SOURCE-0000000000 (topics: [orders])
      --> KSTREAM-FILTER-0000000001
    Processor: KSTREAM-FILTER-0000000001 (stores: [])
      --> KSTREAM-MAPVALUES-0000000002
      <-- KSTREAM-SOURCE-0000000000
    Processor: KSTREAM-MAPVALUES-0000000002 (stores: [])
      --> KSTREAM-SINK-0000000003
      <-- KSTREAM-FILTER-0000000001
    Sink: KSTREAM-SINK-0000000003 (topic: s13_out)
      <-- KSTREAM-MAPVALUES-0000000002
```

읽는 법:

| 표기 | 뜻 |
|---|---|
| `Sub-topology: N` | **독립적으로 실행되는 단위.** 각각 별도 태스크가 됨 |
| `Source:` | 토픽에서 읽는 노드 |
| `Processor:` | 변환 노드. `(stores: [...])` 에 쓰는 상태 저장소가 나옴 |
| `Sink:` | 토픽에 쓰는 노드 |
| `-->` | 다음 노드 |
| `<--` | 이전 노드 |
| `0000000000` | 노드 순번. **DSL 호출 순서대로** 매겨짐 |

**서브토폴로지가 하나뿐이면 데이터가 네트워크를 거치지 않습니다.** 소스에서 싱크까지 한 스레드 안에서 처리됩니다. 서브토폴로지가 나뉘는 순간(13-5) 그 사이에 **repartition 토픽**이 끼어들고, 데이터가 Kafka 를 한 번 왕복합니다.

> 💡 **실무 팁 — `describe()` 를 로그에 남기세요**
> 운영 중인 Streams 앱이 왜 느린지 조사할 때 가장 먼저 보는 것이 토폴로지입니다. 서브토폴로지가 몇 개인지가 곧 **네트워크 왕복 횟수**이기 때문입니다.
> 앱 기동 시 `log.info("{}", topology.describe())` 를 한 줄 넣어 두면, 코드 변경이 토폴로지를 어떻게 바꿨는지 배포마다 확인할 수 있습니다.
> <https://zz85.github.io/kafka-streams-viz/> 에 이 출력을 붙여 넣으면 그림으로도 볼 수 있습니다.

---

## 13-3. KStream vs KTable vs GlobalKTable

세 추상화의 차이가 Streams 학습의 절반입니다.

같은 입력을 셋으로 해석해 봅니다. 입력은 이렇습니다.

```
key=C001, value=100
key=C002, value=200
key=C001, value=300
```

| | KStream | KTable | GlobalKTable |
|---|---|---|---|
| 해석 | **레코드 스트림** — 독립된 사건 3개 | **변경로그 스트림** — 현재 상태 | KTable 과 같으나 복제 방식이 다름 |
| 결과 | `C001=100`, `C002=200`, `C001=300` (3건) | `C001=300`, `C002=200` (2건) | 좌동 |
| 비유 | 은행 **입출금 내역** | 은행 **잔액** | 모든 지점이 갖고 있는 잔액 사본 |
| null 값 | 그냥 값이 null 인 레코드 | **삭제 (tombstone)** | 삭제 |
| 파티셔닝 | 파티션별로 나뉨 | 파티션별로 나뉨 | **모든 인스턴스가 전체를 복제** |
| 크기 제한 | 없음 | 파티션 크기 | **인스턴스 메모리/디스크에 다 들어가야 함** |
| 만드는 법 | `builder.stream(...)` | `builder.table(...)` | `builder.globalTable(...)` |

### 조인 가능 조합

| 왼쪽 | 오른쪽 | 가능? | 조건 |
|---|---|---|---|
| KStream | KStream | ✅ | **윈도우 필수**. co-partition 필요 |
| KStream | KTable | ✅ | 윈도우 없음. co-partition 필요 |
| KStream | GlobalKTable | ✅ | **co-partition 불필요.** 키가 달라도 됨 |
| KTable | KTable | ✅ | co-partition 필요 |
| KTable | KStream | ❌ | 방향을 뒤집어 KStream-KTable 로 |
| KTable | GlobalKTable | ❌ | 지원 안 함 |
| GlobalKTable | 무엇이든 | ❌ | GlobalKTable 은 조인의 **오른쪽**에만 |

> 💡 **GlobalKTable 은 "작고 잘 안 변하는 참조 데이터"용입니다**
> 상품 카테고리 코드표, 국가 코드, 환율 같은 것입니다. 모든 인스턴스가 **전체를 복제**하므로 co-partitioning 이 필요 없고, 키가 달라도 `KeyValueMapper` 로 매핑해서 조인할 수 있습니다.
> 대신 인스턴스가 10대면 같은 데이터를 10벌 갖습니다. 주문 테이블 같은 것을 GlobalKTable 로 만들면 앱이 뜨지도 못합니다. **"이게 각 인스턴스 디스크에 다 들어가는가"가 유일한 판단 기준입니다.**

---

## 13-4. 스테이트리스 연산

상태를 안 쓰는 연산들입니다. 레코드 하나만 보고 처리하므로 빠르고, 내부 토픽도 안 만듭니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java stateless' &
sleep 15
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_orders \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
C002:{"order_id":"O-1002","customer_id":"C002","amount":500,"status":"CREATED"}
C001:{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CANCELLED"}
EOF
```

| 연산 | 하는 일 | 입력 → 출력 |
|---|---|---|
| `filter` | 조건에 맞는 것만 통과 | `(C001,{...CREATED}),(C001,{...CANCELLED})` → `CREATED` 만 |
| `filterNot` | 조건에 안 맞는 것만 | 반대 |
| `map` | **키와 값 둘 다** 바꿈 | `(C001, v)` → `(O-1001, v)` |
| `mapValues` | **값만** 바꿈. 키 유지 | `(C001, v)` → `(C001, V)` |
| `flatMap` | 하나를 0~N개로 | `(C001, "a,b,c")` → 3건 |
| `flatMapValues` | 값만 0~N개로. 키 유지 | 좌동 |
| `selectKey` | **키만** 바꿈 | `(C001, v)` → `(O-1001, v)` |
| `branch` / `split` | 조건별로 여러 스트림으로 분기 | 1스트림 → N스트림 |
| `merge` | 여러 스트림을 하나로 | N스트림 → 1스트림 |
| `peek` | 아무것도 안 바꾸고 들여다봄 | 로깅·디버깅용 |
| `foreach` | 종단. 다음 노드 없음 | 사이드 이펙트용 |

**출력** (앱 로그)

```
[PEEK-in ] C001 → {"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
[PEEK-in ] C002 → {"order_id":"O-1002","customer_id":"C002","amount":500,"status":"CREATED"}
[PEEK-in ] C001 → {"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CANCELLED"}
[branch  ] BIG   ← C001 amount=39000
[branch  ] SMALL ← C002 amount=500
[filter  ] CANCELLED 제외됨: O-1003
[merge   ] 총 2건이 s13_out 으로
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_out \
  --from-beginning --property print.key=true --timeout-ms 5000
```

**결과**

```
C001	{"ORDER_ID":"O-1001","CUSTOMER_ID":"C001","AMOUNT":39000,"STATUS":"CREATED"}
C002	{"ORDER_ID":"O-1002","CUSTOMER_ID":"C002","AMOUNT":500,"STATUS":"CREATED"}
Processed a total of 2 messages
```

> 💡 **`map` 과 `mapValues` 중에는 항상 `mapValues` 를 먼저 고려하세요**
> `mapValues` 는 키를 안 바꾼다고 Streams 에게 **약속**하는 것입니다. 그래서 파티션이 그대로 유지되고, 다음 절의 repartition 토픽이 안 생깁니다.
> `map` 은 키를 바꿀 수도 있다고 선언하는 것이라, **실제로 키를 안 바꿔도** Streams 는 "바뀌었을 수 있다"고 보고 repartition 을 겁니다.
> 같은 이유로 `flatMap` 보다 `flatMapValues` 를, `transform` 보다 `transformValues` 를 씁니다.

---

## 13-5. 함정 A 의 절반 — selectKey 뒤에 토픽이 생깁니다

지금 토픽 목록을 다시 봅니다.

```bash
kt --list
```

**결과** (13-4 실행 후. 아직 안 늘었습니다)

```
__consumer_offsets
dlq
order-events
orders
payments
s13_left
s13_orders
s13_out
s13_right
```

**스테이트리스 연산만으로는 토픽이 안 생깁니다.** 이제 `selectKey` 를 넣고 그 뒤에 집계를 붙입니다.

```java
builder.stream("s13_orders", Consumed.with(Serdes.String(), Serdes.String()))
       .selectKey((k, v) -> extractOrderId(v))   // customer_id → order_id 로 키 교체
       .groupByKey()
       .count(Materialized.as("order-counts"))
       .toStream()
       .to("s13_out", Produced.with(Serdes.String(), Serdes.Long()));
```

토폴로지를 봅니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java topology selectkey'
```

**결과**

```
Topologies:
   Sub-topology: 0
    Source: KSTREAM-SOURCE-0000000000 (topics: [s13_orders])
      --> KSTREAM-KEY-SELECT-0000000001
    Processor: KSTREAM-KEY-SELECT-0000000001 (stores: [])
      --> order-counts-repartition-filter
      <-- KSTREAM-SOURCE-0000000000
    Processor: order-counts-repartition-filter (stores: [])
      --> order-counts-repartition-sink
      <-- KSTREAM-KEY-SELECT-0000000001
    Sink: order-counts-repartition-sink (topic: order-counts-repartition)
      <-- order-counts-repartition-filter

  Sub-topology: 1
    Source: order-counts-repartition-source (topics: [order-counts-repartition])
      --> KSTREAM-AGGREGATE-0000000002
    Processor: KSTREAM-AGGREGATE-0000000002 (stores: [order-counts])
      --> KTABLE-TOSTREAM-0000000006
      <-- order-counts-repartition-source
    Processor: KTABLE-TOSTREAM-0000000006 (stores: [])
      --> KSTREAM-SINK-0000000007
      <-- KSTREAM-AGGREGATE-0000000002
    Sink: KSTREAM-SINK-0000000007 (topic: s13_out)
      <-- KTABLE-TOSTREAM-0000000006
```

**서브토폴로지가 둘로 쪼개졌습니다.** 그 경계에 `order-counts-repartition` 토픽이 있습니다.

앱을 실행하고 토픽 목록을 다시 봅니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java selectkey' &
sleep 20
kt --list
```

**결과**

```
__consumer_offsets
dlq
order-events
orders
payments
s13-selectkey-app-order-counts-changelog
s13-selectkey-app-order-counts-repartition
s13_left
s13_orders
s13_out
s13_right
```

**두 개가 새로 생겼습니다.** 아무도 만들라고 하지 않았습니다.

### 왜 repartition 이 필요합니까

Streams 의 집계는 **"같은 키는 같은 태스크가 처리한다"**는 전제 위에 있습니다. 그래야 로컬 RocksDB 하나로 키별 상태를 관리할 수 있습니다.

```
   s13_orders (key=customer_id)
   ┌─────────┬─────────┬─────────┐
   │ P0      │ P1      │ P2      │
   │ C001    │ C002    │ C003    │
   └────┬────┴────┬────┴────┬────┘
        │ selectKey(order_id) ← 키가 바뀝니다
        ▼         ▼         ▼
   O-1001 이 P0 에, O-1002 도 P0 에, O-1003 은... 어디에?
   ★ 키는 바뀌었는데 레코드는 여전히 원래 파티션에 있습니다.
     같은 order_id 가 P0 과 P2 에 흩어져 있을 수 있습니다.

        │ repartition 토픽에 다시 씁니다 (key=order_id 로 파티셔닝)
        ▼
   order-counts-repartition
   ┌─────────┬─────────┬─────────┐
   │ P0      │ P1      │ P2      │
   │ O-1003  │ O-1001  │ O-1002  │  ← 이제 같은 order_id 는 한 파티션에만
   └─────────┴─────────┴─────────┘
```

**repartition 은 데이터를 Kafka 에 한 번 다시 쓰고 다시 읽는 것입니다.** 공짜가 아닙니다. 처리량이 대략 절반이 되고 지연이 늘어납니다. 그래서 13-4 의 팁 — `mapValues` 를 쓰라 — 이 중요합니다.

repartition 토픽을 열어 봅니다.

```bash
kt --describe --topic s13-selectkey-app-order-counts-repartition
```

**결과**

```
Topic: s13-selectkey-app-order-counts-repartition	TopicId: mR8vK2nQTZ6yLpWcXbYhdA	PartitionCount: 3	ReplicationFactor: 1	Configs: cleanup.policy=delete,segment.bytes=52428800,retention.ms=-1,message.timestamp.type=CreateTime
	Topic: s13-selectkey-app-order-counts-repartition	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: s13-selectkey-app-order-counts-repartition	Partition: 1	Leader: 2	Replicas: 2	Isr: 2
	Topic: s13-selectkey-app-order-counts-repartition	Partition: 2	Leader: 3	Replicas: 3	Isr: 3
```

> ⚠️ **함정 — repartition 토픽의 RF 가 1 입니다**
> `replication.factor` 의 Streams 기본값은 **1** 입니다(3.x 기준). 브로커 기본값 `default.replication.factor=3` 을 따르지 **않습니다.**
> 즉 운영 클러스터에서 브로커 한 대가 죽으면 그 브로커가 리더였던 repartition/changelog 파티션이 **통째로 사라지고**, Streams 앱은 상태를 복구하지 못해 처음부터 다시 만들거나 아예 못 뜹니다.
> **해결**: Streams 앱 설정에 반드시 넣으세요.
> ```java
> props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);
> ```
> 이 값은 repartition 토픽과 changelog 토픽에 **모두** 적용됩니다. 운영 체크리스트의 1번 항목입니다.
> 또 `retention.ms=-1`(무한)인 것도 눈여겨보세요. repartition 토픽은 Streams 가 **소비 직후 스스로 purge** 하므로 무한 보존이어도 안 쌓입니다. 이 삭제를 담당하는 것이 `RepartitionTopics` 의 purge 로직이며, `repartition.purge.interval.ms`(기본 30초)마다 돕니다.

---

## 13-6. 집계와 상태 저장소

집계 연산은 **상태**를 씁니다.

| 연산 | 결과 타입 | 하는 일 |
|---|---|---|
| `groupByKey()` | KGroupedStream | 키를 안 바꾸고 그룹핑. **repartition 안 생김** |
| `groupBy((k,v) -> ...)` | KGroupedStream | 새 키로 그룹핑. **repartition 생김** |
| `count()` | KTable<K, Long> | 키별 개수 |
| `reduce((a,b) -> ...)` | KTable<K, V> | 같은 타입끼리 접기 |
| `aggregate(초기값, (k,v,agg) -> ...)` | KTable<K, VR> | **다른 타입으로** 접기 |

```java
KTable<String, Long> counts =
    builder.stream("s13_orders", Consumed.with(Serdes.String(), Serdes.String()))
           .groupByKey()
           .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-counts")
                  .withKeySerde(Serdes.String())
                  .withValueSerde(Serdes.Long()));
counts.toStream().to("s13_out", Produced.with(Serdes.String(), Serdes.Long()));
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java count' &
sleep 20
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_orders \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","amount":39000}
C002:{"order_id":"O-1002","amount":12500}
C001:{"order_id":"O-1003","amount":88000}
C001:{"order_id":"O-1004","amount":5000}
EOF
sleep 5
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_out --from-beginning \
  --property print.key=true \
  --value-deserializer org.apache.kafka.common.serialization.LongDeserializer \
  --timeout-ms 5000
```

**결과**

```
C001	1
C002	1
C001	2
C001	3
Processed a total of 4 messages
```

**C001 이 1, 2, 3 으로 세 번 나옵니다.** KTable 은 "변경로그"이므로 값이 바뀔 때마다 갱신을 내보냅니다. "최종 결과 하나만" 원한다면 13-8 의 `suppress` 가 필요합니다.

### 상태 저장소 — RocksDB 와 changelog

상태는 **두 곳**에 있습니다.

```
   ① 로컬 RocksDB              ② Kafka changelog 토픽
   /tmp/kafka-streams/         s13-count-app-order-counts-changelog
     s13-count-app/
       0_0/rocksdb/order-counts/   ← 빠른 읽기용
       0_1/rocksdb/order-counts/       (프로세스가 죽으면 사라질 수 있음)
       0_2/rocksdb/order-counts/
                                  ← 진짜 원본. 재기동 시 여기서 복원
```

컨테이너 안에서 직접 봅니다.

```bash
docker exec kafka-1 find /tmp/kafka-streams -maxdepth 4 -type d | head -12
```

**결과**

```
/tmp/kafka-streams
/tmp/kafka-streams/s13-count-app
/tmp/kafka-streams/s13-count-app/0_0
/tmp/kafka-streams/s13-count-app/0_0/rocksdb
/tmp/kafka-streams/s13-count-app/0_0/rocksdb/order-counts
/tmp/kafka-streams/s13-count-app/0_1
/tmp/kafka-streams/s13-count-app/0_1/rocksdb
/tmp/kafka-streams/s13-count-app/0_1/rocksdb/order-counts
/tmp/kafka-streams/s13-count-app/0_2
/tmp/kafka-streams/s13-count-app/0_2/rocksdb
/tmp/kafka-streams/s13-count-app/0_2/rocksdb/order-counts
/tmp/kafka-streams/s13-count-app/0_0/rocksdb/order-counts/LOG
```

`0_0`, `0_1`, `0_2` 는 **태스크 ID** 입니다. `<서브토폴로지>_<파티션>` 형식입니다. 입력 토픽이 3파티션이므로 태스크가 3개입니다.

changelog 토픽을 봅니다.

```bash
kt --describe --topic s13-count-app-order-counts-changelog
```

**결과**

```
Topic: s13-count-app-order-counts-changelog	TopicId: fW3pL7kNQR2mBcXvY8tZdg	PartitionCount: 3	ReplicationFactor: 1	Configs: cleanup.policy=compact,segment.bytes=52428800,message.timestamp.type=CreateTime
	Topic: s13-count-app-order-counts-changelog	Partition: 0	Leader: 2	Replicas: 2	Isr: 2
	Topic: s13-count-app-order-counts-changelog	Partition: 1	Leader: 3	Replicas: 3	Isr: 3
	Topic: s13-count-app-order-counts-changelog	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
```

**`cleanup.policy=compact` 입니다.** repartition 토픽(`delete`)과 다릅니다.

| | repartition | changelog |
|---|---|---|
| 이름 | `<app-id>-<노드/스토어>-repartition` | `<app-id>-<스토어>-changelog` |
| 정책 | `delete` (+ Streams 가 스스로 purge) | **`compact`** |
| 담는 것 | 키를 바꾼 레코드를 다시 흘려보냄 | **상태 저장소의 모든 변경** |
| 언제 생김 | `selectKey`/`map`/`groupBy`/`join` 뒤에 집계·조인이 오면 | `count`/`reduce`/`aggregate`/`table()` 등 상태를 쓰면 |
| 지우면 | 다시 만들어짐 (데이터는 재생성됨) | **상태를 잃습니다** |
| 파티션 수 | 입력과 동일 | 입력과 동일 |

changelog 가 compact 여야 하는 이유는 명확합니다. **키별 최신 값만 있으면 상태를 완전히 복원할 수 있기 때문**입니다. `C001 → 1, 2, 3` 중 `3` 만 남아도 복원에 문제가 없습니다. delete 였다면 retention 이 지난 뒤 앱을 재기동했을 때 **상태 일부가 사라진 채로 복원**되고, 카운트가 조용히 작아집니다.

내용을 직접 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic s13-count-app-order-counts-changelog --from-beginning \
  --property print.key=true \
  --value-deserializer org.apache.kafka.common.serialization.LongDeserializer \
  --timeout-ms 5000
```

**결과**

```
C001	1
C002	1
C001	2
C001	3
Processed a total of 4 messages
```

압축이 돌면 `C001 3` 과 `C002 1` 만 남습니다.

> 💡 **실무 팁 — 상태 복원 시간이 재기동 시간을 결정합니다**
> Streams 앱을 재기동하면 로컬 RocksDB 가 없는 경우(컨테이너 재생성, 새 인스턴스) changelog 토픽을 **처음부터 끝까지 읽어** 상태를 복원합니다. 상태가 수 GB 면 이 복원에 수십 분이 걸립니다. 그동안 앱은 데이터를 처리하지 않습니다.
> **해결 두 가지**
> 1. 상태 디렉터리를 **영속 볼륨**에 둡니다(`state.dir`). 그러면 재기동 시 로컬 것을 쓰고 델타만 따라잡습니다.
> 2. `num.standby.replicas=1` — 다른 인스턴스가 **미리 상태 사본을 유지**합니다. 장애 시 그 인스턴스가 즉시 인계받습니다. 디스크를 두 배 쓰는 대신 복구 시간이 거의 0 이 됩니다.
> 운영 Streams 앱에서 `num.standby.replicas` 는 사실상 필수 설정입니다.

---

## 13-7. 함정 A — 내부 토픽이 자동으로 생깁니다

앞의 두 절에서 이미 봤지만, 정면으로 다룹니다.

**before** (13-0 에서 기록한 것):

```
__consumer_offsets
dlq
order-events
orders
payments
s13_left
s13_orders
s13_out
s13_right
```

**after** (지금까지 앱 세 개를 띄운 뒤):

```bash
kt --list > /tmp/topics-after.txt
diff /tmp/topics-before.txt /tmp/topics-after.txt
```

**결과**

```
5a6,10
> s13-count-app-order-counts-changelog
> s13-selectkey-app-order-counts-changelog
> s13-selectkey-app-order-counts-repartition
> s13-window-app-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
> s13-window-app-KSTREAM-KEY-SELECT-0000000002-repartition
```

**다섯 개가 자동으로 생겼습니다.**

### 이름 규칙

| 패턴 | 언제 생김 | 예 |
|---|---|---|
| `<app-id>-<스토어이름>-changelog` | `Materialized.as("이름")` 으로 이름을 준 상태 저장소 | `s13-count-app-order-counts-changelog` |
| `<app-id>-<노드이름>-changelog` | 이름을 안 준 상태 저장소 (자동 생성 이름) | `s13-window-app-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog` |
| `<app-id>-<스토어이름>-repartition` | 이름 있는 스토어 앞의 키 변경 | `s13-selectkey-app-order-counts-repartition` |
| `<app-id>-<노드이름>-repartition` | 이름 없는 경우 | `s13-window-app-KSTREAM-KEY-SELECT-0000000002-repartition` |
| `<app-id>-<이름>-subscription-registration-topic` | KTable 외래키 조인 | (이 코스에서는 안 나옴) |

> 💡 **실무 팁 — 상태 저장소에는 반드시 이름을 주세요**
> `Materialized.as("order-counts")` 를 쓰면 토픽 이름이 `앱-order-counts-changelog` 로 사람이 읽을 수 있게 나옵니다. 안 주면 `앱-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog` 가 됩니다.
> 문제는 뒤쪽 숫자가 **DSL 호출 순서**라는 점입니다. 코드에서 `filter` 를 하나 추가하면 번호가 밀리고, **토픽 이름이 바뀝니다.** 새 이름의 토픽이 만들어지고 기존 상태는 고아가 됩니다. 배포 한 번에 집계가 리셋되는 것입니다.
> 이름을 주면 이 문제가 완전히 사라집니다. 운영 앱에서는 예외 없이 이름을 주세요.

### `auto.create.topics.enable=false` 인데도 생기는 이유

우리 클러스터는 자동 토픽 생성이 꺼져 있습니다(Step 01, docker/index.md 참조).

```bash
kconf --describe --entity-type brokers --entity-name 1 | grep auto.create
```

**결과**

```
  auto.create.topics.enable=false sensitive=false synonyms={STATIC_BROKER_CONFIG:auto.create.topics.enable=false, DEFAULT_CONFIG:auto.create.topics.enable=true}
```

그런데 Streams 는 만들었습니다.

> ⚠️ **함정 A — Streams 는 AdminClient 로 토픽을 직접 만듭니다**
> `auto.create.topics.enable` 은 **"프로듀서/컨슈머가 없는 토픽에 접근하면 자동 생성할까"**를 결정하는 설정입니다. Streams 는 그 경로를 안 씁니다. 기동 시 `InternalTopicManager` 가 **AdminClient 로 `CreateTopics` API 를 명시적으로 호출**합니다. 사용자가 손으로 `kt --create` 하는 것과 완전히 같은 경로입니다.
> 그래서 자동 생성을 꺼도 막히지 않습니다. Connect(Step 12)의 내부 토픽도 같은 방식입니다.
>
> **왜 문제입니까**
> 운영 클러스터에서 "누가 이 토픽 만들었지?"의 정체가 대개 이것입니다. 토픽 목록이 어느 날부터 두 배가 되어 있고, 이름은 아무도 모르는 `app-KSTREAM-AGGREGATE-STATE-STORE-0000000019-changelog` 같은 것입니다.
> 더 나쁜 것은 **앱을 지워도 토픽은 남는다**는 점입니다. Streams 앱을 내리고 코드를 지워도, 토픽·컨슈머 그룹·상태 디렉터리가 전부 남습니다. 디스크를 계속 먹습니다.
>
> **해결**
> 1. `application.id` 에 **명확한 접두사**를 씁니다. `s13-count-app` 처럼요. 내부 토픽이 전부 그 접두사로 시작하므로 소유자를 즉시 알 수 있습니다.
> 2. 상태 저장소에 **이름**을 줍니다. 위 팁 참조.
> 3. 앱을 폐기할 때는 반드시 `kafka-streams-application-reset.sh` 를 돌립니다. 다음 절입니다.
> 4. RF 를 3 으로 (`StreamsConfig.REPLICATION_FACTOR_CONFIG`). 기본값 1 은 운영에서 위험합니다.

### `kafka-streams-application-reset.sh`

앱의 흔적을 정리하는 공식 도구입니다. **앱이 완전히 멈춘 상태**여야 합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-streams-application-reset.sh \
  --bootstrap-server kafka-1:9092 \
  --application-id s13-selectkey-app \
  --input-topics s13_orders
```

**결과**

```
Reset-offsets for input topics [s13_orders]
Following input topics offsets will be reset to (for consumer group s13-selectkey-app)
Topic: s13_orders Partition: 0 Offset: 0
Topic: s13_orders Partition: 1 Offset: 0
Topic: s13_orders Partition: 2 Offset: 0
Done.
Deleting all internal/auto-created topics for application s13-selectkey-app
Deleted topic: s13-selectkey-app-order-counts-changelog
Deleted topic: s13-selectkey-app-order-counts-repartition
Done.
```

앱이 아직 돌고 있으면 이렇게 거부합니다.

```
ERROR: Java class 'kafka.tools.StreamsResetter' failed:
  java.lang.IllegalStateException: Consumer group 's13-selectkey-app' is still active
  and has following members: [s13-selectkey-app-8f2c...-StreamThread-1-consumer].
  Make sure to stop all running application instances before running the reset tool.
```

주요 옵션:

| 옵션 | 하는 일 |
|---|---|
| `--application-id` | **필수.** 정리할 앱 |
| `--input-topics` | 입력 토픽의 오프셋을 처음으로 되돌림 |
| `--intermediate-topics` | `through()` 로 쓴 중간 토픽을 비움 |
| `--to-datetime` / `--to-offset` / `--shift-by` | 처음이 아닌 특정 지점으로 |
| `--dry-run` | **실제로 안 하고 계획만 출력** |
| `--force` | 활성 멤버가 있어도 강제 (그룹에서 쫓아냄) |

> ⚠️ **함정 — reset 도구가 로컬 상태 디렉터리는 안 지웁니다**
> 이 도구는 **Kafka 쪽**만 정리합니다. 각 인스턴스의 `state.dir`(기본 `/tmp/kafka-streams/<app-id>`)에 남은 RocksDB 파일은 그대로입니다.
> 오프셋만 0 으로 되돌리고 로컬 상태는 남은 채로 앱을 재기동하면, **옛 상태에 새로 읽은 데이터가 얹혀서 카운트가 두 배가 됩니다.** 에러는 없습니다.
> **해결**: 각 인스턴스에서 `KafkaStreams.cleanUp()` 을 기동 전에 호출하거나, 디렉터리를 직접 지웁니다.
> ```bash
> docker exec kafka-1 rm -rf /tmp/kafka-streams/s13-selectkey-app
> ```
> `cleanUp()` 은 **`start()` 전에만** 호출할 수 있습니다. 운영 앱에는 `--reset` 같은 기동 플래그를 만들어 두고, 그때만 `cleanUp()` 을 부르게 하는 패턴이 흔합니다. 무조건 부르면 재기동마다 전체 복원이 일어나 기동이 몇십 분씩 걸립니다.

---

## 13-8. 윈도우

시간 구간으로 잘라 집계합니다.

| 종류 | 정의 | 겹침 | 한 레코드가 속하는 윈도우 수 | 용도 |
|---|---|---|---|---|
| **Tumbling** | 고정 크기, 겹치지 않음 | ✗ | 1 | "1분당 주문 수" |
| **Hopping** | 고정 크기 + advance 간격 | ✓ | `size / advance` 개 | "최근 5분, 1분마다 갱신" |
| **Sliding** | 레코드 기준 ± 크기 | ✓ | 가변 | "이 이벤트 앞뒤 10초 안의 것들" |
| **Session** | 비활동 간격(gap)으로 구분 | ✗ | 1 | "사용자 세션당 행동 수" |

```java
// Tumbling — 1분
TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30))

// Hopping — 5분 크기, 1분마다 (한 레코드가 5개 윈도우에 들어감)
TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30))
           .advanceBy(Duration.ofMinutes(1))

// Sliding — 10초
SlidingWindows.ofTimeDifferenceAndGrace(Duration.ofSeconds(10), Duration.ofSeconds(5))

// Session — 5분 비활동
SessionWindows.ofInactivityGapAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30))
```

### 1분 텀블링 — 고객별 주문 금액 합계

```java
builder.stream("s13_orders", Consumed.with(Serdes.String(), Serdes.String()))
       .groupByKey()
       .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30)))
       .aggregate(() -> 0L,
                  (k, v, agg) -> agg + amountOf(v),
                  Materialized.<String, Long, WindowStore<Bytes, byte[]>>as("amount-per-min")
                              .withKeySerde(Serdes.String())
                              .withValueSerde(Serdes.Long()))
       .toStream()
       .foreach((wk, sum) -> System.out.printf("[WINDOW] %s [%s ~ %s] sum=%d%n",
                wk.key(), wk.window().startTime(), wk.window().endTime(), sum));
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java window' &
sleep 20
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_orders \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","amount":10000}
C001:{"order_id":"O-1002","amount":20000}
C002:{"order_id":"O-1003","amount":5000}
EOF
```

**결과** (앱 로그)

```
[WINDOW] C001 [2024-03-11T10:22:00Z ~ 2024-03-11T10:23:00Z] sum=10000
[WINDOW] C001 [2024-03-11T10:22:00Z ~ 2024-03-11T10:23:00Z] sum=30000
[WINDOW] C002 [2024-03-11T10:22:00Z ~ 2024-03-11T10:23:00Z] sum=5000
```

**윈도우 경계가 `10:22:00 ~ 10:23:00` 으로 깔끔합니다.** 텀블링 윈도우는 에폭(1970-01-01T00:00:00Z)부터 크기 단위로 잘린 **절대 시각**을 씁니다. 앱이 언제 시작했든 같은 경계가 나옵니다.

### 함정 B — grace period 를 지난 레코드는 조용히 버려집니다

Streams 의 윈도우는 **이벤트 시간**(레코드의 타임스탬프)으로 동작합니다. 네트워크 지연이나 재시도 때문에 레코드가 늦게 도착할 수 있는데, 언제까지 기다릴지가 `grace` 입니다.

일부러 늦은 레코드를 만듭니다. 콘솔 프로듀서로는 타임스탬프를 못 지정하므로 `Practice.java` 의 `window` 시나리오가 대신 만들어 줍니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java window --inject-late'
```

**결과**

```
[PRODUCE] C001 amount=10000  ts=2024-03-11T10:22:10Z  (윈도우 10:22:00~10:23:00, 정상)
[PRODUCE] C001 amount=20000  ts=2024-03-11T10:22:40Z  (같은 윈도우, 정상)
[WINDOW ] C001 [10:22:00 ~ 10:23:00] sum=10000
[WINDOW ] C001 [10:22:00 ~ 10:23:00] sum=30000

[PRODUCE] C001 amount=7000   ts=2024-03-11T10:22:50Z  (스트림 시간은 이미 10:23:20, grace 30초 → 10:23:30 까지 허용. 아직 유효)
[WINDOW ] C001 [10:22:00 ~ 10:23:00] sum=37000

[PRODUCE] C001 amount=99000  ts=2024-03-11T10:22:55Z  (스트림 시간 10:23:45. grace 만료됨)
   ★ 아무 출력도 없습니다. 예외도 없습니다.

[FINAL  ] C001 [10:22:00 ~ 10:23:00] sum=37000     ← 99000 이 빠졌습니다
[METRIC ] dropped-records-total = 1
```

> ⚠️ **함정 B — 늦게 도착한 레코드는 예외 없이, 로그 없이 사라집니다**
> `TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30))` 은 "윈도우가 끝난 뒤 30초까지만 늦은 레코드를 받는다"는 뜻입니다. 31초째에 온 레코드는 **버려집니다.**
> 예외도 안 던지고, 기본 로그 레벨에서는 아무것도 안 찍힙니다. 결과 토픽의 합계가 그냥 작습니다. **"Kafka 집계 결과가 DB 집계와 안 맞는다"의 가장 흔한 원인입니다.**
>
> **어떻게 알아챕니까 — 지표를 보세요.**
> ```
> kafka.streams:type=stream-task-metrics,thread-id=...,task-id=1_0
>   dropped-records-total   ← 이 값이 0 이 아니면 데이터가 버려지고 있습니다
>   dropped-records-rate
> ```
> 이 지표에 알람을 거는 것이 유일한 방어입니다. Step 14 의 JMX 절과 이어집니다.
>
> **grace 를 얼마로 잡습니까**
> - 너무 짧으면: 정상 데이터가 버려집니다.
> - 너무 길면: 최종 결과가 그만큼 늦게 나오고, 윈도우 상태를 그동안 메모리/디스크에 들고 있어야 합니다.
> - 실무 기준: **관측된 최대 지연의 1.5~2배.** 프로듀서 재시도(`delivery.timeout.ms`)와 네트워크 지연을 합쳐서 계산합니다. 처음에는 넉넉히 잡고 `dropped-records-total` 을 보며 줄이는 편이 안전합니다.
>
> ⚠️ **3.0 에서 기본값이 바뀌었습니다.** `TimeWindows.of(size)` 는 deprecated 되었고, 옛 기본 grace 는 **24시간**이었습니다. 새 `ofSizeAndGrace(size, grace)` 는 grace 를 **명시하도록 강제**합니다. 옛 코드를 마이그레이션할 때 grace 를 안 주면 `ofSizeWithNoGrace()` 로 바뀌어 **grace 0** 이 되고, 조금만 늦어도 전부 버려집니다. 3.x 마이그레이션의 대표적인 사고 지점입니다.

### suppress — 최종 결과만 내보내기

위 출력에서 `sum=10000`, `sum=30000`, `sum=37000` 이 순차적으로 나왔습니다. 다운스트림이 DB 업서트라면 세 번 쓰게 됩니다. **윈도우가 닫힌 뒤 최종값 하나만** 원한다면:

```java
.windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30)))
.aggregate(...)
.suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
.toStream()
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java suppress'
```

**결과**

```
(윈도우 진행 중 — 아무 출력 없음)
(10:23:30 — grace 만료, 윈도우 닫힘)
[FINAL] C001 [10:22:00 ~ 10:23:00] sum=37000
[FINAL] C002 [10:22:00 ~ 10:23:00] sum=5000
```

**중간 결과가 사라지고 최종 하나씩만 나옵니다.**

> ⚠️ **함정 — `suppress` 는 다음 레코드가 와야 방출합니다**
> `untilWindowCloses` 는 "스트림 시간이 윈도우 끝 + grace 를 넘으면 방출"입니다. 그런데 **스트림 시간은 레코드가 들어와야 전진합니다.** 트래픽이 멈추면 스트림 시간도 멈추고, 마지막 윈도우는 **영원히 방출되지 않습니다.**
> 개발 중에 "suppress 를 걸었더니 아무것도 안 나온다"는 열에 아홉 이것입니다. 벽시계로 30초를 기다려도 안 나옵니다. 레코드를 하나 더 넣으면 그제서야 나옵니다.
> **해결**: 저트래픽 토픽에는 **하트비트 레코드**를 주기적으로 넣거나, `suppress` 대신 중간 결과를 받아 다운스트림에서 멱등 업서트로 처리합니다. 후자가 더 견고합니다.
>
> `BufferConfig.unbounded()` 도 위험합니다. 방출 전까지 모든 윈도우를 **메모리에 들고 있습니다.** 키가 많으면 OOM 입니다. 운영에서는 상한을 주세요.
> ```java
> Suppressed.BufferConfig.maxBytes(50_000_000L).emitEarlyWhenFull()
> ```
> `emitEarlyWhenFull()` 은 버퍼가 차면 조기 방출합니다(= suppress 효과 일부 포기). 대안인 `shutDownWhenFull()` 은 앱을 죽입니다. **조용히 OOM 나는 것보다는 낫습니다만**, 대개 `emitEarlyWhenFull` 이 실용적입니다.

---

## 13-9. 조인

### orders × payments 윈도우 조인

```java
KStream<String, String> orders   = builder.stream("s13_orders");
KStream<String, String> payments = builder.stream("s13_payments");

orders.selectKey((k, v) -> orderIdOf(v))          // customer_id → order_id
      .join(payments,
            (o, p) -> "{\"order\":" + o + ",\"payment\":" + p + "}",
            JoinWindows.ofTimeDifferenceAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30)),
            StreamJoined.with(Serdes.String(), Serdes.String(), Serdes.String()))
      .to("s13_out");
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java join' &
sleep 20
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_orders \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF
sleep 2
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_payments \
  --property parse.key=true --property key.separator=: <<'EOF'
O-1001:{"order_id":"O-1001","method":"CARD","amount":39000,"result":"APPROVED"}
EOF
sleep 3
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s13_out --from-beginning \
  --property print.key=true --timeout-ms 5000
```

**결과**

```
O-1001	{"order":{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"},"payment":{"order_id":"O-1001","method":"CARD","amount":39000,"result":"APPROVED"}}
Processed a total of 1 messages
```

### 조인 종류별 특성

| 조합 | 윈도우 | 상태 저장소 | 트리거 |
|---|---|---|---|
| KStream-KStream | **필수** | 양쪽 다 (윈도우 스토어 2개) | 어느 쪽이 와도 |
| KStream-KTable | 없음 | KTable 쪽만 | **스트림 쪽이 올 때만** |
| KTable-KTable | 없음 | 양쪽 다 | 어느 쪽이 바뀌어도 |
| KStream-GlobalKTable | 없음 | GlobalKTable (전체 복제) | 스트림 쪽이 올 때만 |

**KStream-KTable 조인은 스트림 쪽이 올 때만 발화합니다.** KTable 이 나중에 갱신돼도 이미 지나간 스트림 레코드를 다시 조인하지 않습니다. "주문이 왔는데 고객 정보가 아직 KTable 에 없어서 조인이 안 됐다"는 상황이 흔하고, 나중에 고객 정보가 와도 그 주문은 영영 조인되지 않습니다.

### 함정 C — co-partitioning

`s13_left` 는 3파티션, `s13_right` 는 6파티션입니다(13-0 에서 그렇게 만들었습니다). 조인해 봅니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java copartition'
```

**결과**

```
Exception in thread "main" org.apache.kafka.streams.errors.TopologyException: Invalid topology: Following topics do not have the same number of partitions: [s13_left(3), s13_right(6)]
	at org.apache.kafka.streams.processor.internals.InternalTopologyBuilder.verifyCopartitioning(InternalTopologyBuilder.java:1149)
	at org.apache.kafka.streams.processor.internals.StreamsPartitionAssignor.assign(StreamsPartitionAssignor.java:406)
	at Practice$CoPartition.run(Practice.java:412)
	at Practice.main(Practice.java:58)
```

> ⚠️ **함정 C — 조인하려면 co-partitioning 이 필요합니다**
> Streams 의 조인은 **"같은 키는 같은 태스크가 본다"**는 전제 위에 있습니다. 태스크 `0_1` 은 양쪽 토픽의 파티션 1 만 봅니다. 왼쪽의 `O-1001` 이 파티션 1 에 있는데 오른쪽의 `O-1001` 이 파티션 4 에 있으면, 어느 태스크도 두 레코드를 함께 볼 수 없습니다.
>
> **co-partitioning 의 세 조건**
> 1. **파티션 수가 같아야 합니다.** — 위 예외가 이것입니다. 유일하게 Streams 가 검증해 주는 조건입니다.
> 2. **키의 타입과 직렬화가 같아야 합니다.** — 한쪽이 `String "1001"`, 다른 쪽이 `Long 1001L` 이면 바이트가 달라 다른 파티션으로 갑니다.
> 3. **파티셔너가 같아야 합니다.** — ★ **이것은 검증되지 않습니다.**
>
> 3번이 진짜 함정입니다. 예를 들어 한쪽 토픽을 **커스텀 파티셔너를 쓰는 Java 프로듀서**가 채우고, 다른 쪽을 기본 파티셔너로 채웠다면, 파티션 수가 같아도 같은 키가 다른 파티션에 들어갑니다.
> **Streams 는 아무 예외도 안 냅니다.** 조인이 그냥 안 됩니다. 결과 토픽이 비어 있거나 일부만 나옵니다. 코드를 아무리 봐도 이상한 곳이 없습니다.
>
> **해결**
> - 1번(파티션 수)은 조인 전에 한쪽을 `repartition(Repartitioned.numberOfPartitions(3))` 으로 맞춥니다.
>   ```java
>   KStream<String,String> right = builder.stream("s13_right")
>       .repartition(Repartitioned.<String,String>as("right-fixed").numberOfPartitions(3));
>   ```
>   `s13-copart-app-right-fixed-repartition` 이라는 3파티션 토픽이 생기고, 그것과 조인합니다.
> - 3번(파티셔너)은 **양쪽을 같은 방식으로 쓰는 것**이 유일한 예방책입니다. Kafka 기본 파티셔너(murmur2)를 쓰거나, 정 안 되면 조인 전에 양쪽 다 `repartition()` 을 태워 Streams 의 파티셔너로 통일합니다.
> - GlobalKTable 은 이 문제를 **원천적으로 회피**합니다. 전체를 복제하므로 파티셔닝이 무의미합니다. 작은 참조 데이터라면 이게 가장 편합니다.

수정한 버전으로 다시 실행합니다.

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java copartition --fix'
```

**결과**

```
[FIX] s13_right(6) → repartition(3) 로 맞춥니다
[TOPOLOGY]
   Sub-topology: 0
    Source: KSTREAM-SOURCE-0000000001 (topics: [s13_right])
      --> right-fixed-repartition-filter
    ...
    Sink: right-fixed-repartition-sink (topic: right-fixed-repartition)

  Sub-topology: 1
    Source: KSTREAM-SOURCE-0000000000 (topics: [s13_left])
    Source: right-fixed-repartition-source (topics: [right-fixed-repartition])
    ...
[JOIN] K-1  ← left="L1" right="R1"
[JOIN] K-2  ← left="L2" right="R2"
정상 동작합니다.
```

---

## 13-10. EOS — exactly_once_v2

Step 07 에서 트랜잭션 API 로 exactly-once 를 직접 구현했습니다. Streams 에서는 **설정 한 줄**입니다.

```java
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
```

```bash
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java eos' &
sleep 20
```

**결과** (앱 로그)

```
[CONFIG] processing.guarantee = exactly_once_v2
[CONFIG] → 내부적으로 강제되는 값:
           enable.idempotence          = true
           max.in.flight.requests...   = 5
           acks                        = all
           transactional.id            = s13-eos-app-<uuid>-<taskId>
           isolation.level             = read_committed
           commit.interval.ms          = 100   (기본 30000 에서 변경됨)
[TXN] begin  → process 3 records → sendOffsetsToTransaction → commit
```

**Streams 의 EOS 가 보장하는 것**: 입력 소비 오프셋 커밋 + 상태 저장소 갱신 + 출력 토픽 쓰기, 이 셋이 **하나의 트랜잭션**으로 묶입니다. 셋 중 하나라도 실패하면 전부 롤백됩니다.

| | at_least_once (기본) | exactly_once_v2 |
|---|---|---|
| 중복 | 재시작 시 발생 가능 | 없음 |
| 상태 일관성 | 재시작 시 상태와 오프셋이 어긋날 수 있음 | 항상 일치 |
| `commit.interval.ms` 기본 | 30000 | **100** |
| 지연 | 낮음 | **커밋 간격만큼 추가** (기본 100ms) |
| 처리량 | 기준 | 대략 **-10~20%** |
| 컨슈머 요구 | 없음 | 다운스트림이 `read_committed` 여야 |

`exactly_once_v2` 는 Kafka **2.5+** 부터이며 브로커도 2.5 이상이어야 합니다. 옛 `exactly_once`(v1)는 태스크마다 프로듀서를 만들어 자원을 크게 썼는데, v2 는 **스레드당 프로듀서 하나**로 줄여 태스크가 많아도 확장됩니다. 3.0 부터 v1 은 deprecated 이고 4.0 에서 제거되었습니다. **새로 쓰면 무조건 v2 입니다.**

> ⚠️ **함정 — EOS 는 Kafka 안에서만 유효합니다**
> Streams 의 EOS 는 "Kafka → 처리 → Kafka" 경로만 보장합니다. 처리 로직 안에서 **외부 시스템을 호출하면**(REST API, DB INSERT, 이메일 발송) 그것은 트랜잭션 밖입니다.
> 트랜잭션이 롤백되면 Kafka 쪽 쓰기는 취소되지만, **이미 보낸 이메일은 취소되지 않습니다.** 재처리 시 이메일이 두 번 갑니다.
> **해결**: 외부 호출은 멱등하게 만들거나(업서트, 멱등 키), Streams 로는 Kafka 토픽까지만 쓰고 외부 반영은 싱크 커넥터에게 맡깁니다(Step 12 의 `insert.mode=upsert`). 후자가 아키텍처적으로 깔끔합니다.

---

## 13-11. 정리 (실습 마무리)

Streams 실습은 **정리할 것이 셋**입니다. 토픽, 컨슈머 그룹, **로컬 상태 디렉터리**.

먼저 실행 중인 앱을 전부 내립니다.

```bash
docker exec kafka-1 pkill -f 'Practice' || true
sleep 5
kcg --list
```

**결과**

```
s13-count-app
s13-eos-app
s13-join-app
s13-selectkey-app
s13-stateless-app
s13-window-app
```

각 앱을 reset 도구로 정리합니다.

```bash
for app in s13-stateless-app s13-selectkey-app s13-count-app s13-window-app s13-join-app s13-eos-app; do
  docker exec kafka-1 /opt/kafka/bin/kafka-streams-application-reset.sh \
    --bootstrap-server kafka-1:9092 --application-id "$app" \
    --input-topics s13_orders 2>&1 | tail -2
done
```

**결과**

```
Deleted topic: s13-stateless-app-... (없으면 아무것도 안 나옴)
Done.
Deleted topic: s13-selectkey-app-order-counts-changelog
Deleted topic: s13-selectkey-app-order-counts-repartition
Done.
Deleted topic: s13-count-app-order-counts-changelog
Done.
Deleted topic: s13-window-app-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
Deleted topic: s13-window-app-KSTREAM-KEY-SELECT-0000000002-repartition
Done.
Deleted topic: s13-join-app-KSTREAM-JOINTHIS-0000000009-store-changelog
Deleted topic: s13-join-app-KSTREAM-JOINOTHER-0000000010-store-changelog
Deleted topic: s13-join-app-KSTREAM-KEY-SELECT-0000000002-repartition
Done.
Deleted topic: s13-eos-app-order-counts-changelog
Done.
```

**reset 도구는 컨슈머 그룹을 안 지웁니다.** 오프셋만 0 으로 되돌립니다. 그룹도 지웁니다.

```bash
for g in $(kcg --list | grep '^s13-'); do kcg --delete --group "$g"; done
kcg --list
```

**결과**

```
Deletion of requested consumer groups ('s13-stateless-app') was successful.
Deletion of requested consumer groups ('s13-selectkey-app') was successful.
Deletion of requested consumer groups ('s13-count-app') was successful.
Deletion of requested consumer groups ('s13-window-app') was successful.
Deletion of requested consumer groups ('s13-join-app') was successful.
Deletion of requested consumer groups ('s13-eos-app') was successful.
```

**로컬 상태 디렉터리를 지웁니다.** 이걸 안 하면 다음에 같은 `application.id` 로 띄웠을 때 옛 상태가 되살아납니다.

```bash
docker exec kafka-1 du -sh /tmp/kafka-streams 2>/dev/null || echo "없음"
docker exec kafka-1 rm -rf /tmp/kafka-streams
docker exec kafka-1 ls /tmp/kafka-streams 2>&1 || echo "삭제 완료"
```

**결과**

```
3.4M	/tmp/kafka-streams
ls: cannot access '/tmp/kafka-streams': No such file or directory
삭제 완료
```

실습 토픽을 지웁니다.

```bash
kt --list | grep -E '^s13' | xargs -I{} docker exec kafka-1 \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --delete --topic {}
kt --list | grep -E '^s13' || echo "s13 토픽 없음"
```

**결과**

```
s13 토픽 없음
```

```bash
docker exec kafka-1 rm -f /tmp/Practice.java /tmp/topics-before.txt /tmp/topics-after.txt
```

---

## 정리

| 개념 | 핵심 |
|---|---|
| Streams 의 정체 | **라이브러리.** Consumer + Producer + 로컬 상태. 별도 클러스터 없음 |
| 확장 | 같은 `application.id` 로 프로세스를 더 띄우면 끝 |
| `application.id` | **= 컨슈머 그룹 ID.** 랙 모니터링이 일반 컨슈머와 동일 |
| Topology | 서브토폴로지 개수 = **Kafka 네트워크 왕복 횟수** |
| KStream | 레코드 스트림. 사건의 나열 |
| KTable | 변경로그 스트림. 키별 현재 상태. null = 삭제 |
| GlobalKTable | 전체 복제. **co-partition 불필요.** 작은 참조 데이터만 |
| `map` vs `mapValues` | `map` 은 키를 안 바꿔도 **repartition 을 유발** |
| **함정 A** | **내부 토픽이 자동 생성됨. `auto.create.topics.enable=false` 여도 AdminClient 로 직접 만듦** |
| 이름 규칙 | `<app-id>-<이름>-repartition` / `<app-id>-<이름>-changelog` |
| repartition | `delete` 정책. Streams 가 스스로 purge. **RF 기본 1** |
| changelog | **`compact` 정책.** 상태의 진짜 원본. 지우면 상태를 잃음 |
| 상태 저장소 | 로컬 RocksDB + changelog 토픽 이중화 |
| reset 도구 | Kafka 쪽만 정리. **로컬 `state.dir` 은 따로 지워야 함** |
| 윈도우 4종 | Tumbling / Hopping / Sliding / Session |
| **함정 B** | **grace 지난 레코드는 예외·로그 없이 버려짐. `dropped-records-total` 로만 감지** |
| `suppress` | 최종 결과만. **다음 레코드가 와야 방출됨** (저트래픽에서 안 나옴) |
| **함정 C** | **조인은 co-partitioning 필요. 파티션 수만 검증되고 파티셔너 불일치는 조용히 실패** |
| EOS | `exactly_once_v2` 한 줄. **Kafka 안에서만 유효.** 외부 호출은 별도 멱등화 |

---

## 연습문제

`exercise.sh` 에 6문제가 있습니다. 정답은 `solution.sh`.

1. `selectKey` 를 쓴 앱과 안 쓴 앱의 `kt --list` 차이를 확인하고, 생긴 토픽 이름을 설명하기
2. 어떤 앱이 만든 내부 토픽인지 이름만 보고 판별하기 (5개 중 소유 앱 맞히기)
3. changelog 토픽의 `cleanup.policy` 를 확인하고, `delete` 였다면 무슨 일이 생기는지 답하기
4. `kafka-streams-application-reset.sh` 로 앱을 완전히 정리하기 (**로컬 상태 포함**)
5. 파티션 수가 다른 두 토픽을 조인해 `TopologyException` 을 재현하고 고치기
6. 윈도우 집계 결과가 실제보다 작습니다. 어느 지표를 보고 어떻게 고칩니까?

---

## 다음 단계

Connect 로 데이터를 들여오고, Streams 로 가공했습니다. 이제 이 모든 것이 **운영에서 계속 돌아가게** 만들 차례입니다.

파티션을 옮기고, 브로커를 무중단으로 재시작하고, JMX 지표에 알람을 걸고, 장애가 나면 플레이북을 따라 대응합니다. 마지막으로 Step 01~13 을 전부 동원해 주문 이벤트 파이프라인을 처음부터 끝까지 구축하고, **브로커를 죽여 가며 유실 0 을 검증**합니다.

→ [Step 14 — 운영과 최종 프로젝트](../step-14-operations/)

---

## 실습 파일

이 스텝은 파일 네 개로 진행합니다. 중심은 `Practice.java` 이고, 셸 스크립트 셋은 그것을 띄우고 관찰하고 정리하는 역할입니다. 순서는 `practice.sh` → `exercise.sh` → `solution.sh` 이며, `Practice.java` 는 세 스크립트가 모두 `docker cp` 로 컨테이너에 넣어 씁니다.

### Practice.java

시나리오 9종을 인자로 골라 실행하는 단일 파일 Java 프로그램입니다. Java 21 의 **single-file source 실행**(`java Practice.java <arg>`)을 쓰므로 컴파일 단계가 없고, 의존성은 `/opt/kafka/libs/*` 뿐입니다.

- 각 시나리오는 `Scenario` 인터페이스를 구현한 **static 중첩 클래스**입니다. `Stateless`, `SelectKey`, `Count`, `Window`, `Suppress`, `Join`, `CoPartition`, `Eos`, `TopologyOnly` 아홉 개이고, `main` 의 `switch` 가 인자로 고릅니다.
- `TopologyOnly` 는 **앱을 실행하지 않고** `topology.describe()` 만 출력하고 끝납니다. 13-2 와 13-5 의 서브토폴로지 출력을 재현할 때 씁니다. `Practice.java topology selectkey` 처럼 두 번째 인자로 어느 토폴로지를 볼지 고릅니다.
- 모든 시나리오가 공통 `baseProps(appId)` 를 씁니다. 여기에 `REPLICATION_FACTOR_CONFIG=3` 이 들어 있습니다. **Streams 기본값은 1** 이라 그대로 두면 브로커 한 대만 죽어도 내부 토픽이 사라지는데, 그것을 막는 설정입니다. 13-5 의 함정 블록에서 설명한 그 한 줄입니다.
- `Window` 시나리오는 `--inject-late` 플래그를 받습니다. 이 플래그가 있으면 **프로듀서가 타임스탬프를 직접 지정해서** grace 를 넘긴 레코드를 하나 넣습니다. 콘솔 프로듀서로는 타임스탬프를 못 정하기 때문에 Java 로만 재현할 수 있는 실습입니다. 실행 끝에 `dropped-records-total` 지표를 읽어 출력하므로, **1 이 찍히는 것**이 함정 B 의 증거입니다.
- `CoPartition` 은 인자 없이 실행하면 `s13_left(3)` × `s13_right(6)` 조인을 시도해 `TopologyException` 을 그대로 터뜨립니다. `--fix` 를 주면 `repartition(Repartitioned.numberOfPartitions(3))` 로 맞춘 버전이 돌아가며, 두 경우의 토폴로지 출력을 나란히 볼 수 있게 `describe()` 를 먼저 찍습니다.
- `Eos` 는 실행 시 `processing.guarantee=exactly_once_v2` 가 **강제로 바꾸는 설정 5종**(idempotence, acks, transactional.id, isolation.level, commit.interval.ms)을 로그로 출력합니다. Step 07 에서 손으로 설정했던 것들이 한 줄로 대체되는 것을 확인하는 용도입니다.
- 모든 시나리오에 `Runtime.getRuntime().addShutdownHook(...)` 으로 `streams.close(Duration.ofSeconds(10))` 가 걸려 있습니다. `Ctrl+C` 나 `pkill` 로 죽여도 컨슈머 그룹에서 깨끗이 빠져나가므로, 13-11 의 reset 도구가 "still active" 로 거부하는 일이 줄어듭니다.

```java file="./Practice.java"
```

### practice.sh

본문 13-0 ~ 13-11 의 모든 명령을 절 번호 주석과 함께 담았습니다. `Practice.java` 를 컨테이너에 복사하고, 시나리오를 백그라운드로 띄우고, 데이터를 넣고, 결과를 확인하고, 마지막에 전부 정리합니다.

- 맨 앞에서 `docker cp Practice.java kafka-1:/tmp/` 를 한 번만 수행하고, 이후 모든 시나리오가 그 파일을 씁니다. 스크립트를 어느 디렉터리에서 실행해도 되도록 `SCRIPT_DIR` 을 `BASH_SOURCE` 로 계산합니다.
- `run_app()` 헬퍼가 시나리오를 백그라운드로 띄우고 PID 를 배열에 모읍니다. Streams 앱은 기동에 **15~20초**가 걸리므로(리밸런싱 + 내부 토픽 생성 + 상태 복원) 각 `run_app` 뒤에 `sleep 20` 이 붙어 있습니다. 이걸 줄이면 프로듀서가 먼저 데이터를 넣어 버려 앱이 못 받습니다.
- `[13-0]` 에서 `kt --list > /tmp/topics-before.txt` 로 **시작 시점 토픽 목록을 기록**합니다. `[13-7]` 의 `diff` 가 이 파일을 씁니다. 이 before/after 대비가 함정 A 의 핵심 증거이므로 중간부터 실행하면 의미가 없습니다.
- `s13_right` 만 `--partitions 6` 으로 만드는 것이 의도적입니다. `[13-9]` 의 `copartition` 시나리오가 `TopologyException` 을 내려면 파티션 수가 달라야 합니다.
- `[13-6]` 은 `docker exec kafka-1 find /tmp/kafka-streams -maxdepth 4 -type d` 로 **RocksDB 디렉터리를 직접 보여 줍니다.** `0_0`/`0_1`/`0_2` 가 태스크 ID(`<서브토폴로지>_<파티션>`)라는 것을 눈으로 확인하는 구간입니다.
- `[13-11]` 정리가 **세 단계**입니다. ① 앱 종료(`pkill -f Practice`) ② `kafka-streams-application-reset.sh` 를 앱마다 실행 ③ **`rm -rf /tmp/kafka-streams`**. ③ 을 빼먹으면 다음 실행 때 옛 상태가 되살아나 카운트가 두 배가 됩니다. reset 도구가 로컬 상태를 안 지운다는 함정을 스크립트로 방어한 것입니다.
- 컨슈머 그룹 삭제도 별도로 합니다. reset 도구는 오프셋만 0 으로 되돌리고 그룹은 남기기 때문입니다.

```bash file="./practice.sh"
```

### exercise.sh

6문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었고, 관찰에 필요한 앱은 문제지 쪽에서 미리 띄워 줍니다.

- **문제 1·2·3** 은 내부 토픽을 **관찰·판별**하는 문제, **문제 4·5·6** 은 직접 **조치**하는 문제입니다.
- 문제 1 은 `stateless` 와 `selectkey` 두 시나리오를 차례로 띄우고 그 사이사이에 `kt --list` 를 찍습니다. 여러분이 할 일은 세 스냅숏을 비교해 **어느 연산 뒤에** 토픽이 생겼는지 짚고, 생긴 토픽 이름의 각 부분이 무엇을 뜻하는지 쓰는 것입니다.
- 문제 2 는 토픽 이름 5개를 주고 소유 앱과 종류(repartition/changelog)를 맞히는 **순수 지필 문제**입니다. 그중 하나는 `KSTREAM-AGGREGATE-STATE-STORE-0000000003` 처럼 자동 생성 이름이라, "이런 이름이 나오면 `Materialized.as()` 를 안 준 것"이라는 결론까지 끌어내는 것이 목표입니다.
- 문제 4 는 함정이 있습니다. `kafka-streams-application-reset.sh` 만 돌리고 앱을 다시 띄우면 **카운트가 이어집니다.** 로컬 `state.dir` 을 안 지웠기 때문입니다. 문제지가 그 상태까지 만들어 두고 "왜 카운트가 1부터 시작 안 합니까?"를 묻습니다.
- 문제 5 는 `s13ex_a`(2파티션)와 `s13ex_b`(4파티션)를 만들어 둡니다. 예외를 재현한 뒤 `repartition(Repartitioned.numberOfPartitions(2))` 로 고치는 것이 정답이며, `Practice.java copartition --fix` 를 참고 구현으로 볼 수 있습니다.
- 문제 6 은 `Practice.java window --inject-late` 를 실행해 둔 상태에서 시작합니다. 출력의 합계가 실제 투입 금액보다 작다는 것을 확인하고, **어느 JMX 지표**를 봐야 하는지(`dropped-records-total`) 와 **어느 설정**을 고쳐야 하는지(grace 확대)를 답하면 됩니다.
- 파일 끝의 정리 블록은 앱 종료 → reset → 그룹 삭제 → `rm -rf /tmp/kafka-streams` → `s13ex_` 토픽 삭제 순서이며, 문제를 안 풀고 정리만 돌려도 에러가 안 나도록 전부 `|| true` 가 붙어 있습니다.

```bash file="./exercise.sh"
```

### solution.sh

6문제의 정답 명령과 긴 해설 주석입니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `diff` 결과가 `<app-id>-order-counts-repartition` 과 `<app-id>-order-counts-changelog` 두 개라는 것이고, 해설에서 이름을 세 조각(`앱ID` / `스토어 또는 노드 이름` / `종류`)으로 분해합니다. `stateless` 시나리오에서는 **아무 토픽도 안 생긴다**는 대비가 답의 절반입니다.
- **정답 2** 는 표로 답합니다. `-repartition` 은 키가 바뀐 뒤 다시 뿌리려고, `-changelog` 는 상태 저장소를 백업하려고 생깁니다. 자동 생성 이름(`KSTREAM-...-0000000003`)의 숫자가 **DSL 호출 순서**이므로, 코드에 `filter` 하나만 추가해도 번호가 밀려 **토픽 이름이 바뀌고 기존 상태가 고아가 된다**는 것이 이 문제의 진짜 교훈입니다.
- **정답 3** 은 `cleanup.policy=compact` 이고, `delete` 였다면 retention 이 지난 뒤 앱을 재기동할 때 **상태 일부가 사라진 채 복원**되어 카운트가 조용히 작아진다는 설명입니다. repartition 토픽은 반대로 `delete` 가 맞는 이유(Streams 가 소비 직후 purge 하므로)도 나란히 씁니다.
- **정답 4** 의 핵심은 **세 줄**입니다. `pkill` → `kafka-streams-application-reset.sh` → `rm -rf /tmp/kafka-streams/<app-id>`. 세 번째가 없으면 오프셋만 0 이 되고 로컬 RocksDB 는 남아 **옛 상태에 새 데이터가 얹힙니다.** 앱이 살아 있을 때 reset 을 돌리면 나오는 `Consumer group ... is still active` 예외 전문도 주석에 넣었습니다.
- **정답 5** 는 예외 메시지(`Following topics do not have the same number of partitions: [s13ex_a(2), s13ex_b(4)]`)를 먼저 보여 주고, `repartition(Repartitioned.numberOfPartitions(2))` 로 고칩니다. 그리고 **co-partitioning 의 세 조건 중 Streams 가 검증하는 것은 1번(파티션 수)뿐**이며, 3번(파티셔너 불일치)은 예외 없이 조인이 그냥 안 되는 침묵의 실패라는 점을 길게 설명합니다.
- **정답 6** 은 `kafka.streams:type=stream-task-metrics` 의 `dropped-records-total` 을 JmxTool 로 읽는 명령과, grace 를 `Duration.ofSeconds(30)` → `Duration.ofMinutes(5)` 로 늘리는 코드 변경입니다. 해설에서 grace 를 "관측된 최대 지연의 1.5~2배"로 잡는 기준과, 3.0 에서 `TimeWindows.of()` 가 deprecated 되며 **마이그레이션 시 grace 가 0 이 되어 버리는 사고**를 경고합니다.

```bash file="./solution.sh"
```
