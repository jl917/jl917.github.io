# Step 10 — 직렬화와 스키마

> **학습 목표**
> - Kafka 가 바이트만 다루며 스키마는 전적으로 클라이언트 책임이라는 대전제를 이해한다
> - 내장 Serializer/Deserializer 를 정리하고, 프로듀서·컨슈머의 타입 불일치가 만드는 **poison pill 을 직접 재현한다**
> - 같은 주문 레코드를 JSON / Avro / Protobuf 로 인코딩해 **바이트 크기를 실측한다**
> - Schema Registry 를 띄우고 REST API 로 스키마를 등록·조회·호환성 검사한다
> - Confluent 와이어 포맷(매직바이트 + 스키마 ID)을 `xxd` 로 직접 확인하고, console-consumer 가 깨져 보이는 이유를 설명한다
> - 호환성 규칙 5종을 표로 정리하고, **스키마 변경이 컨슈머를 조용히 깨뜨리는 사고를 재현한다**
>
> **선행 스텝**: Step 09 — 보존과 로그 압축
> **예상 소요**: 90분

---

## 10-0. 실습 준비 — Schema Registry 기동

이 스텝은 지금까지와 다릅니다. **브로커 3대만으로는 부족합니다.** Schema Registry 를 추가로 띄웁니다.

```bash
cd kafka/docker
docker compose --profile registry up -d
docker compose ps
```

**결과**

```
NAME              IMAGE                                STATUS                    PORTS
kafka-1           apache/kafka:3.7.1                   Up 3 minutes (healthy)    0.0.0.0:19092->19092/tcp, 0.0.0.0:19999->9999/tcp
kafka-2           apache/kafka:3.7.1                   Up 3 minutes (healthy)    0.0.0.0:29092->29092/tcp, 0.0.0.0:29999->9999/tcp
kafka-3           apache/kafka:3.7.1                   Up 3 minutes (healthy)    0.0.0.0:39092->39092/tcp, 0.0.0.0:39999->9999/tcp
kafka-ui          provectuslabs/kafka-ui:v0.7.2        Up 3 minutes              0.0.0.0:8080->8080/tcp
schema-registry   confluentinc/cp-schema-registry:7.6.1 Up 24 seconds            0.0.0.0:8081->8081/tcp
```

헬스체크는 `/subjects` 를 부르면 됩니다. 아직 등록된 스키마가 없으므로 빈 배열이 정상입니다.

```bash
curl -s localhost:8081/subjects
```

**결과**

```
[]
```

빈 배열이 아니라 `Connection refused` 가 나오면 아직 뜨는 중입니다. Schema Registry 는 내부적으로 `_schemas` 라는 **압축 토픽**에 스키마를 저장하므로, 기동 시 그 토픽을 만들고 전부 읽어들이는 데 15~30초가 걸립니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --describe --topic _schemas
```

**결과**

```
Topic: _schemas	TopicId: 7hK2mQ9xTZuA1pL4nRvYcw	PartitionCount: 1	ReplicationFactor: 3	Configs: cleanup.policy=compact,min.insync.replicas=2,segment.bytes=1048576
	Topic: _schemas	Partition: 0	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
```

**파티션 1개, `cleanup.policy=compact`.** Step 09 에서 배운 압축 토픽이 여기 그대로 쓰입니다. 파티션이 1개인 이유는 **스키마 등록 순서가 전역으로 하나여야** 버전 번호를 매길 수 있기 때문입니다.

> 💡 **실무 팁 — Schema Registry 의 상태는 전부 Kafka 안에 있습니다**
> 별도 DB 가 없습니다. Registry 컨테이너를 지웠다 다시 띄워도 `_schemas` 토픽만 살아 있으면 스키마가 전부 복원됩니다.
> 반대로 `docker compose down -v` 로 볼륨을 지우면 **등록한 스키마가 전부 사라집니다.** 운영에서 이 토픽을 실수로 지우는 것은 사고입니다.

---

## 10-1. Kafka 는 바이트만 다룬다

Kafka 브로커에게 메시지는 **키 바이트 배열과 값 바이트 배열**입니다. 그게 전부입니다.

```
   프로듀서                     브로커                       컨슈머
 ┌──────────────┐          ┌────────────┐            ┌──────────────┐
 │ Order 객체    │          │            │            │  byte[]      │
 │      ↓        │  byte[]  │  byte[]    │   byte[]   │      ↓       │
 │ Serializer   │ ───────► │  그대로 저장 │ ─────────► │ Deserializer │
 │      ↓        │          │            │            │      ↓       │
 │   byte[]     │          │  해석 안 함  │            │  Order 객체   │
 └──────────────┘          └────────────┘            └──────────────┘
       클라이언트                브로커                     클라이언트
```

이 그림에서 중요한 것은 **브로커가 가운데에서 아무것도 검증하지 않는다**는 점입니다. 브로커는 그 바이트가 JSON 인지 Avro 인지 그냥 쓰레기인지 알지 못하며, 알 필요도 없습니다. 이 설계 덕분에 Kafka 는 빠릅니다. 역직렬화·검증을 하지 않으니 **제로카피**로 디스크에서 소켓으로 바로 보낼 수 있습니다.

대가는 명확합니다. **"이 토픽에 들어 있는 값이 무엇인가"를 아는 주체가 아무 데도 없습니다.** 프로듀서가 알고, 컨슈머가 안다고 **믿을** 뿐입니다. 그 믿음이 깨지는 순간이 이 스텝의 주제입니다.

### 내장 Serializer / Deserializer

Kafka 클라이언트가 기본 제공하는 것은 여섯 가지뿐입니다.

| 타입 | Serializer | Deserializer | 인코딩 | 크기 |
|---|---|---|---|---|
| `String` | `StringSerializer` | `StringDeserializer` | UTF-8 (`serializer.encoding` 으로 변경 가능) | 가변 |
| `byte[]` | `ByteArraySerializer` | `ByteArrayDeserializer` | 그대로 통과 | 가변 |
| `Integer` | `IntegerSerializer` | `IntegerDeserializer` | 빅엔디언 고정 4바이트 | **4** |
| `Long` | `LongSerializer` | `LongDeserializer` | 빅엔디언 고정 8바이트 | **8** |
| `UUID` | `UUIDSerializer` | `UUIDDeserializer` | 표준 문자열(36자)의 UTF-8 | **36** |
| `Void` | `VoidSerializer` | `VoidDeserializer` | 항상 `null` | 0 |

지정은 프로듀서와 컨슈머가 각각 따로 합니다.

```properties
# 프로듀서
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer

# 컨슈머
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
```

**여기 이미 문제가 보입니다.** 두 설정은 서로 다른 애플리케이션의, 서로 다른 설정 파일에 있습니다. 아무도 둘을 대조하지 않습니다.

> 💡 **`kafka-console-producer.sh` 의 기본값은 String 입니다**
> 지금까지 아홉 스텝 동안 우리가 쓴 콘솔 도구는 전부 `StringSerializer` / `StringDeserializer` 를 쓰고 있었습니다.
> 그래서 JSON 을 그냥 쳐 넣어도 됐던 것입니다. JSON 을 "이해"한 것이 아니라 **UTF-8 문자열로 취급**한 것뿐입니다.

---

## 10-2. 함정 A — Deserializer 불일치가 컨슈머를 영원히 멈춰 세운다

프로듀서는 `String` 으로 쓰고, 컨슈머는 `Long` 으로 읽어 보겠습니다. 실무에서는 "주문 ID 를 문자열에서 숫자로 바꾸자"는 리팩터링 중에 흔히 발생합니다.

먼저 실습 토픽을 만들고 String 으로 몇 건 넣습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --create --topic s10_types --partitions 1 --replication-factor 3

printf 'O-1001\nO-1002\nO-1003\n' | docker exec -i kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 --topic s10_types
```

**결과** (조용히 성공합니다)

```
```

이제 컨슈머를 **`LongDeserializer`** 로 붙입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s10_types --from-beginning \
  --value-deserializer org.apache.kafka.common.serialization.LongDeserializer
```

**결과**

```
[2024-03-11 10:22:03,417] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.common.errors.SerializationException: Size of data received by LongDeserializer is not 8
Processed a total of 0 messages
```

`O-1001` 은 6바이트입니다. `LongDeserializer` 는 정확히 8바이트를 요구합니다. 콘솔 컨슈머는 여기서 **프로세스를 종료**합니다.

문제는 실제 애플리케이션 컨슈머가 **종료하지 않는다**는 것입니다.

### poison pill — 그 자리에서 무한 재시도

`KafkaConsumer.poll()` 은 역직렬화 중 예외가 나면 그 예외를 던집니다. 그런데 **오프셋을 진전시키지 않습니다.** 커밋도 안 됩니다. 애플리케이션이 예외를 잡고 다시 `poll()` 하면, 브로커는 **같은 오프셋의 같은 메시지**를 다시 줍니다.

```
poll() → 오프셋 0 fetch → 역직렬화 실패 → 예외
   ↑                                        │
   └────────── catch 하고 재시도 ────────────┘

로그: SerializationException ... (초당 수백 줄)
랙:   줄지 않음. 영원히.
```

`kafka-consumer-groups.sh` 로 보면 이렇게 보입니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
  --describe --group s10-poison
```

**결과**

```
GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                     HOST            CLIENT-ID
s10-poison      s10_types       0          0               3               3               consumer-s10-poison-1-8c41f0a3-...              /172.18.0.5     consumer-s10-poison-1
```

**CURRENT-OFFSET 이 0 에서 멈춰 있고 LAG 가 3.** 컨슈머는 살아 있고(CONSUMER-ID 가 있음), CPU 를 쓰고 있고, 아무것도 처리하지 못합니다. 이것이 **poison pill** 입니다. 메시지 하나가 파티션 전체를 막습니다.

> ⚠️ **함정 A — 역직렬화 실패는 "그 파티션 전체의 정지"입니다**
> 애플리케이션 로그에는 예외가 찍히므로 "조용한" 사고는 아닙니다. 하지만 **파급 범위**를 오해하기 쉽습니다.
> 잘못된 메시지가 `s10_types-0` 에 하나 들어갔다면, **그 파티션 뒤에 쌓인 정상 메시지 수백만 건이 전부 대기**합니다. 다른 파티션은 멀쩡히 돌기 때문에 "일부 주문만 처리가 안 된다"는 식으로 보고되고, 원인을 찾는 데 시간이 걸립니다.
> 더 나쁜 것은 **재시작으로 해결되지 않는다**는 점입니다. 컨슈머를 재배포해도 같은 오프셋부터 다시 시작하고 같은 자리에서 또 죽습니다. 운영자가 할 수 있는 응급조치는 결국 "오프셋을 손으로 건너뛰기"뿐입니다.

### 해결책 세 가지

| 방법 | 어떻게 | 장점 | 단점 |
|---|---|---|---|
| **`ErrorHandlingDeserializer` 패턴** | 실제 Deserializer 를 감싸고, 예외가 나면 `null` 을 반환. 애플리케이션이 `null` 을 보고 판단 | 컨슈머가 멈추지 않음. 가장 표준적 | 래퍼 클래스를 직접 작성해야 함(Kafka 코어에는 없음) |
| **DLQ 로 보내기** | 위 래퍼에서 원본 `byte[]` 를 `dlq` 토픽으로 보내고 오프셋 커밋 | 원본 바이트가 보존되어 **나중에 조사 가능** | DLQ 소비·재처리 파이프라인이 따로 필요 |
| **`seek()` 로 건너뛰기** | `try-catch` 로 `RecordDeserializationException` 을 잡고, `e.partition()`/`e.offset()` 을 읽어 `consumer.seek(tp, e.offset() + 1)` | 코드 몇 줄. 즉시 복구 | **메시지를 버립니다.** 무엇을 버렸는지 기록해야 함 |

응급 상황에서 CLI 로 오프셋을 건너뛰는 방법은 이렇습니다. **컨슈머를 먼저 내려야** 합니다(활성 멤버가 있으면 거부됩니다).

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
  --group s10-poison --topic s10_types:0 --reset-offsets --shift-by 1 --execute
```

**결과**

```
GROUP                          TOPIC                          PARTITION  NEW-OFFSET
s10-poison                     s10_types                      0          1
```

> 💡 **실무 팁 — `RecordDeserializationException` 은 오프셋을 알려줍니다**
> Kafka 2.7 부터 역직렬화 실패는 `RecordDeserializationException` 으로 던져지며, 여기에 `topicPartition()` 과 `offset()` 이 들어 있습니다.
> **이 두 값을 반드시 로그에 남기세요.** 없으면 "몇 번째 메시지가 문제인지"를 `kafka-dump-log.sh` 로 뒤져야 합니다.

---

## 10-3. JSON vs Avro vs Protobuf

문자열 JSON 은 스키마가 없어서 위 사고를 막지 못합니다. 그래서 스키마를 **데이터와 함께 강제하는** 형식을 씁니다.

| 항목 | JSON | Avro | Protobuf |
|---|---|---|---|
| 스키마 강제 | ❌ 없음 (JSON Schema 는 선택) | ✅ 필수. 스키마 없이는 인코딩 불가 | ✅ 필수 (`.proto` 컴파일) |
| 크기 | 가장 큼 (필드명이 매 레코드마다) | **가장 작음** (필드명 없음, 순서로 매칭) | 작음 (필드 **번호** 1~2바이트) |
| 인코딩 속도 | 느림 (문자열 파싱) | 빠름 | **가장 빠름** |
| 스키마 진화 | 규칙 없음 → 런타임 사고 | **가장 강력** (writer/reader 스키마 분리, 기본값) | 강력 (필드 번호 기반, 삭제는 `reserved`) |
| 사람이 읽기 | ✅ 그대로 읽힘 | ❌ 바이너리 | ❌ 바이너리 |
| 생태계 | 어디서나 | Kafka·Hadoop 진영 표준 | gRPC·구글 진영 표준 |
| 코드 생성 | 불필요 | 선택(`GenericRecord` 로 가능) | **필수** |

핵심 차이는 **필드 이름을 어디에 두는가**입니다.

- **JSON**: 매 레코드마다 필드 이름을 문자열로 반복합니다. 100만 건이면 `"customer_id"` 라는 13바이트를 100만 번 씁니다.
- **Avro**: 필드 이름이 **스키마에만** 있습니다. 페이로드는 값만 순서대로 붙입니다. 그래서 스키마 없이는 절대 못 읽습니다.
- **Protobuf**: 필드 이름 대신 **번호**(태그)를 1~2바이트로 씁니다. 스키마 없이도 "필드 3은 varint" 정도까지는 읽힙니다.

### 같은 레코드를 세 형식으로 — 바이트 실측

주문 레코드 하나를 세 형식으로 인코딩해 크기를 잽니다.

```json
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
```

JSON 은 바로 잴 수 있습니다.

```bash
printf '%s' '{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}' | wc -c
```

**결과**

```
75
```

여기에 실무에서 흔히 붙는 필드 두 개(`created_at`, `channel`)를 더한 완전한 레코드로 비교하면 이렇습니다.

| 형식 | 인코딩 바이트 | 비고 |
|---|---:|---|
| **JSON (UTF-8)** | **118** | 필드명 7개 + 따옴표 + 구분자가 전체의 절반 |
| **Avro (binary)** | **34** | 값만. 문자열은 `길이 varint + 바이트`, 정수는 zigzag varint |
| **Avro + 와이어 헤더** | **39** | 위 34 + 매직바이트 1 + 스키마 ID 4 |
| **Protobuf** | **38** | 필드마다 `태그 varint` 1바이트가 추가됨 |
| **Protobuf + 와이어 헤더** | **44** | 위 38 + 매직 1 + ID 4 + 메시지 인덱스 1 |

**JSON 118B → Avro 34B. 약 3.5배 작습니다.** 여기에 압축(Step 04 의 `compression.type`)까지 걸면 JSON 은 반복되는 필드명 덕에 압축률이 좋아 격차가 줄지만, **압축·해제 CPU 를 매 배치마다 지불**합니다.

Avro 가 Protobuf 보다 작은 이유는 단순합니다. **Avro 는 태그조차 없습니다.** 스키마의 필드 순서대로 값만 붙이므로, 읽는 쪽이 같은 스키마를 갖고 있다는 전제가 절대적입니다. Protobuf 는 필드 번호를 갖고 있어 순서가 바뀌어도 되고 모르는 필드를 건너뛸 수 있는 대신, 필드마다 1바이트를 더 씁니다.

> 💡 **실무 팁 — 하루 1억 건이면 이 차이가 얼마입니까**
> 84바이트 × 1억 = **8.4 GB/일**. 복제 계수 3이면 디스크에 25 GB/일, 7일 보존이면 176 GB 입니다.
> 네트워크도 같은 비율로 줄고, 브로커 페이지 캐시 적중률이 올라가 처리량까지 좋아집니다. 크기는 그냥 크기가 아닙니다.

---

## 10-4. Schema Registry — REST API 전부

Schema Registry 는 **스키마를 저장하고 ID 를 발급하고 호환성을 판정하는** 서비스입니다. 브로커와는 완전히 별개이며, Kafka 는 Registry 의 존재를 모릅니다.

### 스키마 등록

Avro 스키마를 JSON 문자열로 감싸 `POST` 합니다. `schema` 필드의 값이 **문자열**이어야 하므로 이스케이프가 필요합니다.

```bash
curl -s -X POST http://localhost:8081/subjects/orders-value/versions \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"}]}"}'
```

**결과**

```json
{"id":1}
```

**`id: 1`.** 이 4바이트 정수가 앞으로 모든 메시지에 붙습니다. 스키마 본문 전체가 아니라 ID 만 붙기 때문에 Avro 가 작은 것입니다.

같은 스키마를 한 번 더 등록하면 **새 버전이 생기지 않고 같은 ID 를 돌려줍니다.** Registry 는 스키마를 정규화해서 중복을 제거합니다.

### 조회

```bash
curl -s localhost:8081/subjects
```

**결과**

```json
["orders-value"]
```

```bash
curl -s localhost:8081/subjects/orders-value/versions
```

**결과**

```json
[1]
```

```bash
curl -s localhost:8081/subjects/orders-value/versions/latest
```

**결과**

```json
{"subject":"orders-value","version":1,"id":1,"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"}]}"}
```

ID 로 직접 조회하는 엔드포인트도 있습니다. **컨슈머가 실제로 쓰는 것이 이것입니다.**

```bash
curl -s localhost:8081/schemas/ids/1
```

**결과**

```json
{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"}]}"}
```

컨슈머는 메시지에서 ID 4를 읽고 → 이 엔드포인트를 부르고 → 스키마를 받아 → 역직렬화합니다. **한 번 받으면 캐시**하므로 Registry 호출은 ID 당 한 번뿐입니다.

### 호환성 확인 (등록하지 않고 검사만)

배포 전 CI 에서 돌려야 하는 것이 이 엔드포인트입니다.

```bash
curl -s -X POST http://localhost:8081/compatibility/subjects/orders-value/versions/latest \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"},{\"name\":\"channel\",\"type\":\"string\",\"default\":\"WEB\"}]}"}'
```

**결과**

```json
{"is_compatible":true}
```

`default` 가 있는 필드를 추가했으므로 BACKWARD 호환입니다. **아직 등록되지는 않았습니다.** 이 엔드포인트는 판정만 합니다.

### 호환성 설정 조회·변경

```bash
curl -s localhost:8081/config
```

**결과** (compose 에서 `backward` 로 지정해 두었습니다)

```json
{"compatibilityLevel":"BACKWARD"}
```

subject 단위로 덮어쓸 수 있습니다.

```bash
curl -s -X PUT http://localhost:8081/config/orders-value \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"FULL"}'
```

**결과**

```json
{"compatibility":"FULL"}
```

subject 설정을 지우면 전역 설정으로 되돌아갑니다.

```bash
curl -s -X DELETE http://localhost:8081/config/orders-value
```

**결과**

```json
{"compatibility":"FULL"}
```

(삭제된 설정값을 돌려줍니다. 이후 `GET /config/orders-value` 는 404 `{"error_code":40408,"message":"Subject 'orders-value' does not have subject-level compatibility configured"}` 가 됩니다.)

### 주요 엔드포인트 정리

| 메서드 | 경로 | 용도 |
|---|---|---|
| `POST` | `/subjects/{subject}/versions` | 스키마 등록 → `{"id":N}` |
| `GET` | `/subjects` | subject 목록 |
| `GET` | `/subjects/{subject}/versions` | 버전 번호 목록 |
| `GET` | `/subjects/{subject}/versions/{v\|latest}` | 특정 버전 전체 |
| `GET` | `/schemas/ids/{id}` | ID 로 스키마 조회 (**컨슈머가 쓰는 것**) |
| `POST` | `/compatibility/subjects/{subject}/versions/latest` | 호환성 **검사만** |
| `GET`/`PUT`/`DELETE` | `/config` , `/config/{subject}` | 호환성 수준 조회/변경/해제 |
| `DELETE` | `/subjects/{subject}` | subject 소프트 삭제 (`?permanent=true` 로 완전 삭제) |

---

## 10-5. 와이어 포맷 — 매직바이트와 스키마 ID

Confluent 직렬화기가 실제로 만드는 바이트는 순수한 Avro 가 아닙니다. **앞에 5바이트가 붙습니다.**

```
 ┌──────┬──────────────────────┬───────────────────────────────┐
 │ 0x00 │  스키마 ID (4바이트)   │      Avro 페이로드 (가변)       │
 │ 매직  │   빅엔디언 int32      │      값만. 필드명 없음.         │
 └──────┴──────────────────────┴───────────────────────────────┘
   1B            4B
```

- **매직바이트 `0x00`**: 포맷 버전. 현재까지 `0x00` 하나뿐입니다. 이 바이트가 다르면 직렬화기가 `Unknown magic byte!` 를 던집니다.
- **스키마 ID 4바이트**: `GET /schemas/ids/{id}` 로 조회할 그 번호. 빅엔디언.

직접 보겠습니다. Avro 로 몇 건을 쓴 뒤 로그 세그먼트를 덤프합니다.

```bash
docker exec schema-registry kafka-avro-console-producer \
  --bootstrap-server kafka-1:9092 --topic s10_avro \
  --property schema.registry.url=http://schema-registry:8081 \
  --property value.schema='{"type":"record","name":"Order","namespace":"shop.order","fields":[{"name":"order_id","type":"string"},{"name":"customer_id","type":"string"},{"name":"amount","type":"int"},{"name":"status","type":"string"}]}' <<'EOF'
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s10_avro-0/00000000000000000000.log --print-data-log
```

**결과**

```
Dumping /var/lib/kafka/data/s10_avro-0/00000000000000000000.log
Log starting offset: 0
baseOffset: 0 lastOffset: 0 count: 1 baseSequence: 0 lastSequence: 0 producerId: 2000 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: false isControl: false deleteHorizonMs: OptionalLong.empty position: 0 CreateTime: 1710120121113 size: 108 magic: 2 compresscodec: none crc: 1884203551 isvalid: true
| offset: 0 CreateTime: 1710120121113 keySize: -1 valueSize: 39 sequence: 0 headerKeys: [] payload: ^@^@^@^@^BO-1001^HC001<9080>^D^NCREATED
```

`valueSize: 39` — 앞서 표에서 계산한 **34 + 5** 와 정확히 일치합니다. `payload` 앞의 `^@^@^@^@^B` 가 **`00 00 00 00 02`**, 즉 매직바이트 0x00 + 스키마 ID **2** 입니다.

바이트를 직접 보려면 `xxd` 가 확실합니다.

```bash
docker exec kafka-1 sh -c \
  'tail -c 60 /var/lib/kafka/data/s10_avro-0/00000000000000000000.log | xxd | tail -4'
```

**결과**

```
00000000: 0000 0000 0200 0000 0000 0000 0000 0000  ................
00000010: 4f2d 3130 3031 0843 3030 3180 e004 0e43  O-1001.C001....C
00000020: 5245 4154 4544                           REATED
```

- `00` — 매직바이트
- `00 00 00 02` — 스키마 ID 2
- `0c` — Avro 문자열 길이 6 의 zigzag varint (`O-1001`)
- `08` — 길이 4 (`C001`)
- `80 e0 04` — 39000 의 zigzag varint (3바이트)
- `0e 43 52 45 41 54 45 44` — 길이 7 + `CREATED`

**필드 이름이 어디에도 없습니다.** 이것이 Avro 가 작은 이유이고, 스키마 없이 절대 못 읽는 이유입니다.

### 그래서 console-consumer 로 읽으면 깨집니다

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s10_avro --from-beginning
```

**결과**

```
O-1001C001???CREATED
Processed a total of 1 messages
```

문자열 필드는 우연히 읽히고, 앞의 5바이트와 정수는 깨집니다. `StringDeserializer` 가 **모든 바이트를 UTF-8 로 억지로 해석**하기 때문입니다.

> ⚠️ **함정 — "메시지가 깨져 있어요" 의 정체는 대개 도구입니다**
> Avro 토픽을 `kafka-console-consumer.sh` 로 열면 늘 이렇게 보입니다. 데이터는 멀쩡합니다. **도구가 틀린 것입니다.**
> 여기서 "프로듀서가 이상한 걸 보낸 것 같다"고 판단해 프로듀서를 뒤지기 시작하면 하루가 갑니다.
> **해결**: `kafka-avro-console-consumer` 를 쓰십시오. Registry 주소를 주면 ID 를 조회해 제대로 디코딩합니다.

```bash
docker exec schema-registry kafka-avro-console-consumer \
  --bootstrap-server kafka-1:9092 --topic s10_avro --from-beginning \
  --property schema.registry.url=http://schema-registry:8081
```

**결과**

```
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
Processed a total of 1 messages
```

---

## 10-6. Subject 명명 전략

Registry 의 저장 단위는 **subject** 입니다. 어떤 이름으로 저장할지는 직렬화기 설정이 정합니다.

| 전략 | subject 이름 | 한 토픽에 여러 타입 | 같은 타입을 여러 토픽에 |
|---|---|---|---|
| **`TopicNameStrategy`** (기본) | `{topic}-key`, `{topic}-value` | ❌ 불가 (버전이 뒤섞임) | 토픽마다 따로 등록·진화 |
| `RecordNameStrategy` | `shop.order.Order` (레코드 풀네임) | ✅ 가능 | ✅ 스키마 하나를 공유 |
| `TopicRecordNameStrategy` | `{topic}-shop.order.Order` | ✅ 가능 | 토픽별로 따로 진화 |

기본값이 `TopicNameStrategy` 라서 지금까지 subject 가 `orders-value` 였습니다. `-value` 접미사가 붙는 이유는 **키와 값에 각각 다른 스키마를 걸 수 있기** 때문입니다.

설정은 이렇게 합니다.

```properties
value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy
```

> ⚠️ **함정 — 한 토픽에 여러 타입을 넣으면서 기본 전략을 그대로 두는 것**
> `order-events` 토픽에 `OrderCreated` 와 `OrderShipped` 를 함께 넣는다고 합시다. 기본 전략이면 **둘 다 `order-events-value` 라는 같은 subject** 에 등록됩니다.
> `OrderCreated` 를 v1 로 등록한 뒤 `OrderShipped` 를 등록하면, Registry 는 이를 **v1 의 진화**로 보고 호환성을 검사합니다. 필드가 전혀 다르므로 대개 **409 로 거부**되고, 개발자는 "호환성을 NONE 으로 바꾸자"는 잘못된 결론에 도달합니다.
> **해결**: 한 토픽에 여러 타입을 넣을 계획이라면 처음부터 `RecordNameStrategy` 또는 `TopicRecordNameStrategy` 를 쓰거나, Avro **union 타입**(`["OrderCreated","OrderShipped"]`)을 최상위로 두십시오. 전략은 나중에 바꾸기가 매우 어렵습니다.

> 💡 **실무 팁 — `TopicRecordNameStrategy` 가 대개 안전합니다**
> `RecordNameStrategy` 는 전사에서 스키마 하나를 공유하므로, A팀이 필드를 추가하면 B팀 토픽까지 영향을 받습니다.
> `TopicRecordNameStrategy` 는 토픽 격리와 다중 타입을 둘 다 얻습니다. 대신 subject 수가 늘어납니다.

---

## 10-7. 호환성 규칙 5종 — 누구를 먼저 배포하는가

이 표가 이 스텝에서 가장 중요합니다.

| 수준 | 허용되는 변경 | 검사 대상 버전 | **먼저 배포할 쪽** | 언제 씁니까 |
|---|---|---|---|---|
| **BACKWARD** (기본) | 필드 **삭제**, **기본값 있는** 필드 추가 | 직전 1개 버전 | **컨슈머 먼저** | 컨슈머가 많고 브로커에 옛 데이터가 남아 있는 일반적 상황 |
| **BACKWARD_TRANSITIVE** | 위와 같음 | **모든** 이전 버전 | 컨슈머 먼저 | 보존 기간이 길어 아주 옛 데이터까지 읽어야 할 때 |
| **FORWARD** | 필드 **추가**, **기본값 있는** 필드 삭제 | 직전 1개 버전 | **프로듀서 먼저** | 컨슈머 배포가 느리거나 통제 불가일 때 |
| **FORWARD_TRANSITIVE** | 위와 같음 | 모든 이전 버전 | 프로듀서 먼저 | 위와 같되 장기 보존 |
| **FULL** | **기본값 있는** 필드의 추가·삭제만 | 직전 1개 버전 | 아무 쪽이나 | 배포 순서를 통제할 수 없는 조직 |
| **FULL_TRANSITIVE** | 위와 같음 | 모든 이전 버전 | 아무 쪽이나 | 가장 엄격 |
| **NONE** | **전부 허용** | 없음 | — | ⚠️ 마이그레이션 중 임시로만 |

용어가 헷갈리는 지점을 짚습니다. **BACKWARD 는 "새 스키마로 만든 리더가 옛 데이터를 읽을 수 있다"** 는 뜻입니다.

```
BACKWARD (컨슈머 먼저)                    FORWARD (프로듀서 먼저)
 ┌─────────────┐                          ┌─────────────┐
 │ 새 컨슈머 v2 │ ◄── 옛 데이터 v1 읽기 OK  │ 옛 컨슈머 v1 │ ◄── 새 데이터 v2 읽기 OK
 └─────────────┘                          └─────────────┘
 ① 컨슈머를 v2 로 올린다                    ① 프로듀서를 v2 로 올린다
 ② 그동안 프로듀서는 v1 을 계속 쓴다         ② 옛 컨슈머 v1 은 새 필드를 무시한다
 ③ 프로듀서를 v2 로 올린다                  ③ 컨슈머를 천천히 v2 로 올린다
```

**왜 BACKWARD 에서 "기본값 있는 필드 추가" 만 되는가**: v2 컨슈머가 v1 데이터를 읽으면 새 필드가 데이터에 없습니다. 그 자리를 채울 **기본값이 없으면 읽을 수가 없습니다.**

**왜 FORWARD 에서 "기본값 없는 필드 추가"가 되는가**: v1 컨슈머가 v2 데이터를 읽으면 모르는 필드는 그냥 건너뜁니다. 기본값이 필요 없습니다. 대신 **필드를 삭제**하면 v1 컨슈머가 그 필드를 찾다가 실패하므로, 삭제하는 필드에는 기본값이 있어야 합니다.

---

## 10-8. 함정 B — 스키마 변경으로 컨슈머 깨뜨리기 (직접 재현)

### (1) BACKWARD 에서 기본값 없는 필드 추가 → 409 거부

호환성을 기본값(BACKWARD)으로 되돌리고, `coupon_id` 를 **기본값 없이** 추가해 봅니다.

```bash
curl -s -X PUT http://localhost:8081/config/orders-value \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"BACKWARD"}'

curl -s -X POST http://localhost:8081/subjects/orders-value/versions \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"},{\"name\":\"coupon_id\",\"type\":\"string\"}]}"}'
```

**결과**

```json
{"error_code":409,"message":"Schema being registered is incompatible with an earlier schema for subject \"orders-value\", details: [{errorType:'READER_FIELD_MISSING_DEFAULT_VALUE', description:'The field 'coupon_id' at path '/fields/4' in the new schema has no default value.', additionalInfo:'coupon_id'}, {oldSchemaVersion: 1}, {oldSchema: '{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"}]}'}, {validateFields: 'false', compatibility: 'BACKWARD'}]"}
```

**409 Conflict.** `READER_FIELD_MISSING_DEFAULT_VALUE` 가 정확한 진단이고, `path '/fields/4'` 가 몇 번째 필드인지까지 알려줍니다. **이것이 정상 동작이고, 좋은 것입니다.** 배포 전에 막혔습니다.

기본값을 붙이면 통과합니다.

```bash
curl -s -X POST http://localhost:8081/subjects/orders-value/versions \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"},{\"name\":\"coupon_id\",\"type\":[\"null\",\"string\"],\"default\":null}]}"}'
```

**결과**

```json
{"id":3}
```

`["null","string"]` union 에 `default: null`. **nullable 필드를 만드는 Avro 의 정석**이며, union 의 **첫 번째 타입이 기본값의 타입과 같아야** 합니다. `["string","null"]` 에 `default: null` 을 주면 스키마 파싱 자체가 실패합니다.

### (2) 호환성을 NONE 으로 바꾸면 — 등록은 성공하고 컨슈머가 죽는다

이제 "409 가 귀찮으니 NONE 으로 바꾸자"는 흔한 판단을 실제로 해 봅니다.

```bash
curl -s -X PUT http://localhost:8081/config/orders-value \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"NONE"}'
```

**결과**

```json
{"compatibility":"NONE"}
```

방금 거부됐던 **기본값 없는 `coupon_id`** 를 그대로 다시 밀어 넣습니다.

```bash
curl -s -X POST http://localhost:8081/subjects/orders-value/versions \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"},{\"name\":\"coupon_id\",\"type\":\"string\"}]}"}'
```

**결과**

```json
{"id":4}
```

**성공했습니다.** 에러도, 경고도 없습니다. 프로듀서를 새 스키마로 배포하고, 프로듀서는 정상적으로 메시지를 보냅니다. 브로커도 정상입니다. kafka-ui 에도 아무 문제가 없어 보입니다.

그런데 **v1 스키마를 갖고 있는 옛 컨슈머**가 이 메시지를 만나면 이렇게 됩니다.

```
[2024-03-11 10:31:44,902] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.common.errors.SerializationException: Error deserializing Avro message for id 4
Caused by: org.apache.avro.AvroTypeException: Found shop.order.Order, expecting shop.order.Order, missing required field coupon_id
	at org.apache.avro.io.ResolvingDecoder.doAction(ResolvingDecoder.java:308)
	at org.apache.avro.io.parsing.Parser.advance(Parser.java:86)
	...
```

`missing required field coupon_id`. 그리고 이 예외는 **10-2 의 poison pill 과 정확히 같은 형태**입니다. 컨슈머는 그 자리에서 멈추고 무한 재시도합니다.

> ⚠️ **함정 B — `NONE` 은 문제를 없애는 게 아니라 사고 시점을 뒤로 미룹니다**
> 호환성 검사는 **배포 전에 CI 에서 실패하는** 장치입니다. `NONE` 으로 바꾸는 순간 그 장치가 사라지고, 같은 문제가 **운영 컨슈머의 런타임 예외**로 이동합니다.
> 더 나쁜 것은 **시차**입니다. 프로듀서 배포는 화요일에 하고, 그 스키마의 메시지를 처음 만나는 컨슈머는 야간 배치일 수 있습니다. 배포와 장애 사이가 멀어 원인 추적이 어려워집니다.
> **이미 등록된 나쁜 버전을 되돌리는 방법**은 소프트 삭제뿐입니다.
> ```bash
> curl -s -X DELETE localhost:8081/subjects/orders-value/versions/4
> ```
> **결과**: `4`
> 하지만 **그 스키마로 이미 쓰인 메시지는 토픽에 그대로 남아 있습니다.** 소프트 삭제해도 `GET /schemas/ids/4` 는 여전히 응답하므로 컨슈머는 여전히 그 메시지를 만나고 여전히 죽습니다. 스키마 삭제는 되돌리기가 아닙니다.
> **해결**: `NONE` 은 "이 subject 전체를 버리고 새 토픽으로 이관한다"가 확정된 마이그레이션 창구에서만 잠시 씁니다. 끝나면 즉시 되돌리십시오.

호환성을 원래대로 복구하고 갑니다.

```bash
curl -s -X PUT http://localhost:8081/config/orders-value \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"BACKWARD"}'
```

**결과**

```json
{"compatibility":"BACKWARD"}
```

### (3) FORWARD 를 써야 하는 경우

"주문 이벤트를 30개 팀이 소비하고 있고, 그중 절반은 우리가 배포 시점을 통제할 수 없다." 이럴 때 BACKWARD 는 무력합니다. 컨슈머를 먼저 올릴 방법이 없기 때문입니다.

FORWARD 로 바꾸면 **프로듀서를 먼저 올려도 됩니다.**

```bash
curl -s -X PUT http://localhost:8081/config/orders-value \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"FORWARD"}'

# FORWARD 에서는 기본값 없는 필드 추가가 허용됩니다
curl -s -X POST http://localhost:8081/compatibility/subjects/orders-value/versions/latest \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"},{\"name\":\"status\",\"type\":\"string\"},{\"name\":\"coupon_id\",\"type\":\"string\"},{\"name\":\"channel\",\"type\":\"string\"}]}"}'
```

**결과**

```json
{"is_compatible":true}
```

반대로 FORWARD 에서 **기본값 없는 필드를 삭제**하려 하면 거부됩니다.

```bash
curl -s -X POST http://localhost:8081/compatibility/subjects/orders-value/versions/latest \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"int\"}]}"}'
```

**결과**

```json
{"is_compatible":false,"messages":["{errorType:'READER_FIELD_MISSING_DEFAULT_VALUE', description:'The field 'status' at path '/fields/3' in the new schema has no default value.', additionalInfo:'status'}"]}
```

BACKWARD 와 FORWARD 는 **검사 방향만 뒤집은 같은 규칙**입니다. 그래서 에러 타입 이름도 같습니다.

> 💡 **실무 팁 — 대부분의 조직은 결국 FULL_TRANSITIVE 로 갑니다**
> BACKWARD 는 배포 순서를 지켜야 하고, FORWARD 는 반대 순서를 지켜야 합니다. **순서를 지키는 것 자체가 운영 부담**입니다.
> `FULL_TRANSITIVE` 는 "기본값 있는 필드의 추가·삭제만" 이라는 단순한 한 줄 규칙이 되고, 배포 순서를 신경 쓰지 않아도 됩니다. 제약은 세지만 사고가 없습니다.

---

## 10-9. 스키마 진화 실무 규칙

위 실습을 규칙 네 줄로 압축합니다.

| 하고 싶은 것 | 규칙 | 이유 |
|---|---|---|
| **필드 추가** | **항상 `default` 와 함께.** nullable 이면 `{"type":["null","T"],"default":null}` | 기본값이 없으면 옛 데이터를 읽을 때 채울 값이 없음 |
| **필드 삭제** | **기본값이 있는 필드만.** 없으면 먼저 기본값을 붙인 버전을 배포하고, 다음 릴리스에서 삭제 (2단계) | 옛 컨슈머가 그 필드를 찾을 때 채울 값이 필요 |
| **이름 변경** | **`aliases` 를 쓴다.** `{"name":"customer_ref","aliases":["customer_id"]}` | 이름 변경은 Avro 에서 "삭제 + 추가" 로 해석되어 양쪽 다 깨짐 |
| **타입 변경** | **금지.** 새 필드를 추가하고 옛 필드를 두 릴리스 뒤에 삭제 | `int → string` 같은 변경은 어떤 호환성 수준에서도 통과하지 않음 |

타입 변경 중 **승격(promotion)** 만은 예외적으로 허용됩니다. `int → long → float → double`, `string ↔ bytes` 방향입니다. 하지만 이것도 **한 방향으로만** 되므로 BACKWARD 와 FORWARD 중 하나만 만족합니다.

```bash
# amount 를 int → long 으로 (BACKWARD 관점에서 허용되는 승격)
curl -s -X PUT localhost:8081/config/orders-value \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d '{"compatibility":"BACKWARD"}' > /dev/null

curl -s -X POST http://localhost:8081/compatibility/subjects/orders-value/versions/latest \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"shop.order\",\"fields\":[{\"name\":\"order_id\",\"type\":\"string\"},{\"name\":\"customer_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"long\"},{\"name\":\"status\",\"type\":\"string\"}]}"}'
```

**결과**

```json
{"is_compatible":true}
```

반대 방향(`long → int`)은 거부됩니다.

```json
{"is_compatible":false,"messages":["{errorType:'TYPE_MISMATCH', description:'The type (path '/fields/2/type') of a field in the new schema does not match with the old schema', additionalInfo:'reader type: INT not compatible with writer type: LONG'}"]}
```

> ⚠️ **함정 — `status` 를 enum 으로 바꾸고 싶어지는 순간**
> `string status` 를 Avro enum 으로 바꾸면 타입 변경이므로 거부됩니다. 통과시키려 `NONE` 을 쓰면 10-8 (2) 가 재현됩니다.
> 게다가 **Avro enum 은 새 심볼을 추가하는 것 자체가 BACKWARD 를 깹니다** (옛 리더가 모르는 심볼을 만남). `default` 심볼을 지정해 두지 않으면 enum 은 사실상 확장 불가능한 타입입니다.
> **실무 결론**: 값 집합이 늘어날 가능성이 조금이라도 있으면 **enum 대신 string 을 쓰십시오.** Kafka 스키마에서 enum 은 거의 항상 후회합니다.

---

## 10-10. 정리 (실습 마무리)

이 스텝에서 만든 subject 와 토픽을 지웁니다. **subject 는 소프트 삭제 후 완전 삭제 2단계**입니다.

```bash
# subject 소프트 삭제
curl -s -X DELETE localhost:8081/subjects/orders-value
```

**결과** (삭제된 버전 번호 배열)

```json
[1,2,3,4,5]
```

```bash
# 완전 삭제 (소프트 삭제 후에만 가능)
curl -s -X DELETE 'localhost:8081/subjects/orders-value?permanent=true'
curl -s -X DELETE 'localhost:8081/subjects/s10_avro-value?permanent=true'
curl -s localhost:8081/subjects
```

**결과**

```json
[]
```

```bash
# 실습 토픽 삭제
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --delete --topic s10_types
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
  --delete --topic s10_avro

# 컨슈머 그룹 삭제
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 \
  --delete --group s10-poison
```

**결과**

```
Deletion of requested consumer groups ('s10-poison') was successful.
```

Schema Registry 는 Step 12·13 에서 쓰지 않으므로 내려도 됩니다. 브로커는 그대로 둡니다.

```bash
docker compose stop schema-registry
```

> 💡 완전 삭제(`?permanent=true`)를 하지 않으면 subject 이름을 **재사용할 수 없습니다.** 소프트 삭제 상태의 subject 에 같은 이름으로 등록하면 옛 버전 번호를 이어받아 혼란스러워집니다. 실습 뒤에는 완전 삭제를 권합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Kafka 와 스키마 | 브로커는 **바이트만** 다룬다. 스키마는 100% 클라이언트 책임 |
| 내장 Serializer | String / ByteArray / Integer / Long / UUID / Void — 여섯 개뿐 |
| Deserializer 불일치 | `Size of data received by LongDeserializer is not 8` → **poison pill**, 파티션 전체 정지 |
| poison pill 대응 | ErrorHandlingDeserializer 래퍼 / DLQ / `seek(offset+1)`. 재시작으로는 안 풀린다 |
| 크기 실측 | JSON 118B / Avro 34B / Protobuf 38B — **Avro 가 약 3.5배 작다** |
| Avro 가 작은 이유 | 페이로드에 **필드 이름이 없다.** 스키마 없이는 못 읽는다 |
| Schema Registry | 스키마 저장·ID 발급·호환성 판정. 상태는 `_schemas` **압축 토픽**에 |
| 와이어 포맷 | `0x00` + 스키마 ID 4바이트 + Avro 페이로드 = 헤더 **5바이트** |
| console-consumer 깨짐 | 도구가 String 으로 해석해서. `kafka-avro-console-consumer` 를 쓸 것 |
| Subject 전략 | TopicNameStrategy(기본) / RecordNameStrategy / TopicRecordNameStrategy |
| BACKWARD | 삭제 + 기본값 있는 추가. **컨슈머 먼저 배포** |
| FORWARD | 추가 + 기본값 있는 삭제. **프로듀서 먼저 배포** |
| FULL | 기본값 있는 추가·삭제만. 배포 순서 무관 |
| `NONE` | 검사를 없애는 게 아니라 **사고를 런타임으로 옮긴다** |
| 진화 규칙 | 추가는 기본값과 / 삭제는 기본값 있는 것만 / 이름은 alias / 타입 변경 금지 |
| enum | 심볼 추가가 BACKWARD 를 깬다. **string 을 쓸 것** |

---

## 연습문제

`exercise.sh` 에 6문제가 있습니다. 정답은 `solution.sh`. **직접 curl 을 돌려 응답을 확인**하세요.

1. `s10_ex` 토픽에 String 으로 쓰고 `IntegerDeserializer` 로 읽어 예외 메시지를 정확히 재현하기
2. 그 컨슈머 그룹의 랙이 줄지 않는 것을 `--describe` 로 확인하고, `--reset-offsets --shift-by` 로 복구하기
3. `payments-value` subject 에 스키마를 등록하고 발급된 ID 를 `/schemas/ids/{id}` 로 되찾기
4. BACKWARD 에서 거부되는 변경과 통과하는 변경을 각각 하나씩 만들어 `is_compatible` 을 대조하기
5. 호환성을 FORWARD 로 바꾸면 4번의 판정이 어떻게 뒤집히는지 확인하기
6. Avro 메시지를 `xxd` 로 열어 매직바이트와 스키마 ID 를 읽어내고, 그 ID 로 Registry 를 조회하기

---

## 다음 단계

스키마까지 정리했으면 이제 이 파이프라인이 **얼마나 빠른지** 재야 합니다. Avro 로 바꿔 레코드가 3.5배 작아졌다면 처리량은 얼마나 올라갈까요. `acks` 와 `linger.ms` 는 실제로 몇 배 차이를 만들까요.
다음 스텝에서는 `kafka-producer-perf-test.sh` 와 `kafka-consumer-perf-test.sh` 로 처리량과 지연을 직접 측정하고, "처리량을 3배 올렸더니 p99 지연이 늘었다"는 트레이드오프를 숫자로 확인합니다.

→ [Step 11 — 성능 튜닝](../step-11-performance/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 는 10-0 부터 10-10 까지의 모든 명령을 절 번호 주석과 함께 담고 있으며, **Avro 스키마는 전부 heredoc 으로 curl 에 넘기는 형태**입니다. `-d @-` 로 표준입력을 읽게 해서, 본문의 이스케이프 지옥(`\"type\":\"record\"`)을 피했습니다. 그다음 `exercise.sh` 의 6문제를 직접 채우고, `solution.sh` 로 대조합니다.

### practice.sh

본문의 모든 명령을 순서대로 실행하는 스크립트입니다. Schema Registry 가 떠 있어야 하므로 맨 위에서 헬스체크를 먼저 합니다.

- 상단 헬퍼 `K()` 는 `docker exec kafka-1 /opt/kafka/bin/"$@"` 형태이고, `SR()` 은 `curl -s -X "$1" "http://localhost:8081$2"` 로 Registry 호출을 감쌉니다. 본문의 긴 curl 이 스크립트에서는 한 줄로 줄어듭니다.
- `[10-0]` 구간은 `docker compose --profile registry up -d` 후 **`/subjects` 가 200 을 돌려줄 때까지 최대 60초 폴링**합니다. Registry 는 `_schemas` 토픽을 전부 읽고 나서야 준비되므로, 이 대기 없이 바로 다음 명령을 쏘면 `Connection refused` 가 납니다.
- `[10-2]` 는 **의도적으로 실패하는 구간**입니다. `LongDeserializer` 컨슈머 호출에 `|| true` 를 붙여 두었습니다(`set -e` 때문에 스크립트가 거기서 죽지 않도록). 에러 메시지가 나오는 것이 정상이고, 그게 이 절의 목적입니다.
- `[10-4]` 부터 모든 스키마는 `register_schema()` 함수를 통해 등록합니다. 이 함수는 **heredoc 으로 받은 Avro JSON 을 `jq -Rs .` 로 문자열 이스케이프**한 뒤 `{"schema": ...}` 로 감싸 POST 합니다. `jq` 가 없는 환경을 위해 `python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'` 폴백도 넣어 두었습니다.
- `[10-5]` 의 `xxd` 덤프는 `tail -c 60` 으로 세그먼트 끝부분만 잘라 봅니다. 전체를 `xxd` 하면 레코드 배치 헤더까지 쏟아져 나와 매직바이트를 찾기 어렵습니다.
- `[10-8]` 은 **(1) 409 거부 → (2) NONE 으로 우회 → (3) FORWARD 검증** 순서로 배치돼 있습니다. (2) 를 지나면 subject 에 나쁜 버전이 남으므로, 이 구간 끝에서 반드시 호환성을 BACKWARD 로 되돌립니다. 중간에 스크립트를 멈췄다면 `curl -s localhost:8081/config/orders-value` 로 확인하세요.
- 맨 끝 `[10-10]` 이 subject 를 소프트 삭제 → 완전 삭제하고, `s10_types` / `s10_avro` 토픽과 `s10-poison` 그룹을 지웁니다. 여러 번 다시 돌려도 안전하도록 삭제는 전부 `|| true` 로 감쌌습니다.

```bash file="./practice.sh"
```

### exercise.sh

6문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었고, 준비물(토픽 생성, 메시지 투입)은 문제지가 미리 해 줍니다.

- **문제 1·2** 는 poison pill 실습입니다. 문제지가 `s10_ex` 토픽을 만들고 문자열 `O-2001` 세 건을 넣어 둡니다. 여러분은 `IntegerDeserializer` 로 읽는 명령과, 랙을 확인하는 `--describe` 명령을 채웁니다.
- **문제 2 의 함정**: `--reset-offsets` 는 **그룹에 활성 멤버가 있으면 거부**됩니다. 문제지 주석이 이를 경고하고 있으니, 컨슈머를 먼저 종료하고 실행하세요. 거부되면 `Error: Assignments can only be reset if the group 's10-ex' is inactive` 가 나옵니다.
- **문제 3** 은 `payments-value` subject 를 다룹니다. 스키마 본문은 문제지에 heredoc 으로 준비돼 있고, 여러분이 채우는 것은 **POST 하는 부분과, 응답에서 `id` 를 뽑아 재조회하는 부분**입니다.
- **문제 4·5** 는 같은 스키마 변경을 BACKWARD 와 FORWARD 에서 각각 검사해 **판정이 뒤집히는 것**을 보는 문제입니다. 변경 내용은 "기본값 없는 `refund_id` 추가" 로 고정돼 있어, 4번은 `false`, 5번은 `true` 가 나와야 합니다.
- **문제 6** 은 문제지가 Avro 로 한 건을 쓴 뒤, 여러분이 `xxd` 명령과 ID 조회 curl 을 채웁니다. 힌트로 "5번째 바이트까지가 헤더" 라는 주석이 붙어 있습니다.
- 파일 끝의 정리 블록은 `s10_ex` / `s10_ex_avro` 토픽과 `payments-value` / `s10_ex_avro-value` subject 를 지웁니다. 문제를 다 못 풀었어도 이 블록만 실행하면 환경이 깨끗해집니다.

```bash file="./exercise.sh"
```

### solution.sh

6문제의 정답 명령과, 왜 그 답인지 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 의 해설은 `IntegerDeserializer` 가 왜 "not 4" 가 아니라 정확히 `Size of data received by IntegerDeserializer is not 4` 를 던지는지, 그리고 `O-2001` 이 6바이트라 4와 다르다는 산수를 짚습니다. 만약 우연히 4바이트짜리 값을 넣었다면 **에러 없이 쓰레기 정수가 나온다**는 점도 함께 다룹니다. 이쪽이 훨씬 위험합니다.
- **정답 2** 는 `--reset-offsets --shift-by 1 --execute` 를 쓰되, **`--dry-run` 으로 먼저 확인하라**는 절차를 강조합니다. `--execute` 는 되돌릴 수 없고, 잘못 쏘면 그룹 전체의 오프셋이 날아갑니다. `--to-earliest` 를 실수로 쓰면 전량 재처리가 시작됩니다.
- **정답 3** 은 `curl ... | jq -r .id` 로 ID 를 뽑아 변수에 담고 `/schemas/ids/$ID` 를 부릅니다. 해설은 **컨슈머가 정확히 이 두 단계를 한다**는 것과, 클라이언트가 이 결과를 캐시하므로 Registry 가 잠깐 죽어도 이미 본 ID 는 계속 처리된다는 점을 설명합니다.
- **정답 4·5** 의 핵심 해설은 한 문장입니다: **"BACKWARD 는 새 리더가 옛 데이터를 읽는 것, FORWARD 는 옛 리더가 새 데이터를 읽는 것."** 기본값 없는 필드 추가는 전자를 깨고 후자는 안 깹니다. 표로 정리한 10-7 을 다시 참조하도록 안내합니다.
- **정답 6** 은 `xxd` 출력의 첫 5바이트 `00 00 00 00 07` 을 어떻게 읽는지 바이트 단위로 해설하고, 빅엔디언 4바이트 정수를 셸에서 계산하는 방법(`printf '%d\n' 0x00000007`)까지 보여줍니다.
- 각 정답 블록 끝에 **"운영에서라면"** 주석이 붙어 있습니다. 예를 들어 정답 2 에는 "실무에서는 오프셋을 건너뛰기 전에 그 메시지를 `kafka-dump-log.sh` 로 떠서 보관하라" 는 절차가 적혀 있습니다. 버린 메시지는 되찾을 수 없습니다.

```bash file="./solution.sh"
```
