# Step 12 — Kafka Connect

> **학습 목표**
> - Connect 가 오프셋 관리·재시작·스케일아웃·스키마 변환을 코드 없이 대신해 주는 구조를 설명한다
> - 분산 모드로 기동하고 내부 토픽 3종이 왜 그런 파티션 수와 정책을 갖는지 확인한다
> - REST API 9종을 전부 호출해 커넥터를 등록·조회·수정·재시작·일시정지·삭제한다
> - FileStreamSource / FileStreamSink 로 파일 ↔ 토픽 왕복을 재현한다
> - **소스 커넥터와 싱크 커넥터의 오프셋이 서로 다른 곳에 저장된다는 것을 직접 확인한다**
> - **커넥터를 삭제해도 오프셋이 남아 "처음부터 다시 읽히지 않는" 현상을 재현한다**
> - SMT 체인으로 메시지를 변환하고, DLQ 로 잘못된 레코드를 격리한다
>
> **선행 스텝**: Step 11 — 성능 튜닝
> **예상 소요**: 100분

---

## 12-0. 실습 준비

Kafka Connect 는 `connect` 프로필에 묶여 있어 기본 기동에서 빠져 있습니다. 켭니다.

```bash
cd kafka/docker
mkdir -p connect-data
docker compose --profile connect up -d
```

**결과**

```
[+] Running 5/5
 ✔ Container kafka-1        Running
 ✔ Container kafka-2        Running
 ✔ Container kafka-3        Running
 ✔ Container kafka-ui       Running
 ✔ Container kafka-connect  Started
```

Connect 워커는 기동에 **40~70초** 걸립니다. 플러그인 스캔과 내부 토픽 생성을 하기 때문입니다. 기다리는 동안 로그를 봅니다.

```bash
docker logs -f kafka-connect 2>&1 | grep -E 'Kafka Connect started|Finished starting'
```

**결과**

```
[2024-03-11 10:20:41,882] INFO Finished starting connectors and tasks (org.apache.kafka.connect.runtime.distributed.DistributedHerder)
[2024-03-11 10:20:41,884] INFO Kafka Connect started (org.apache.kafka.connect.runtime.Connect)
```

이 두 줄이 나오면 준비 완료입니다. 이 스텝은 `curl` 과 `jq` 를 많이 씁니다. 호스트에 `jq` 가 없으면 `brew install jq` / `apt install jq` 로 설치하세요.

```bash
curl -s localhost:8083/ | jq
```

**결과**

```json
{
  "version": "7.6.1-ccs",
  "commit": "e0f2e2e2a0b1c3d4",
  "kafka_cluster_id": "MkU3OEVBNTcwNTJENDM2Qk"
}
```

`kafka_cluster_id` 가 **우리 클러스터의 ID 와 같습니다.** Connect 워커가 올바른 클러스터에 붙었다는 뜻입니다. 이 값이 다르면 부트스트랩 주소가 잘못된 것입니다.

> 💡 `version` 이 `7.6.1-ccs` 인 이유는 Connect 워커만 `confluentinc/cp-kafka-connect:7.6.1` 이미지를 쓰기 때문입니다. `-ccs` 는 Confluent Community Software 빌드를 뜻하며, 내용물은 Apache Kafka 3.6.1 과 같습니다. 브로커(3.7.1)와 버전이 달라도 Connect 는 정상 동작합니다. Kafka 프로토콜은 하위 호환이기 때문입니다.

---

## 12-1. Connect 가 대신해 주는 것

"파일을 읽어서 Kafka 에 넣는다"는 코드는 20줄이면 씁니다. 문제는 그 20줄이 운영에 들어가는 순간 아래가 전부 필요해진다는 것입니다.

| 직접 만들면 해야 하는 일 | Connect 가 하는 일 |
|---|---|
| 어디까지 읽었는지 기록 (파일 오프셋, DB 커서) | `connect-offsets` 토픽에 자동 커밋 |
| 프로세스가 죽으면 그 지점부터 재개 | 워커가 태스크를 다른 워커에 재배치하고 오프셋에서 재개 |
| 처리량이 부족하면 인스턴스 추가 | `tasks.max` 를 늘리면 태스크가 워커들에 분산 |
| 설정을 바꾸려면 재배포 | REST `PUT /connectors/{name}/config` 로 무중단 변경 |
| 필드 추가·이름 변경·라우팅 | SMT 설정 한 줄 |
| 실패한 레코드를 어디에 모을지 | `errors.deadletterqueue.topic.name` |
| 스키마 변환 (JSON ↔ Avro) | Converter 교체 |

**Connect 는 "코드를 안 쓰는 프레임워크"가 아니라 "이 일곱 가지를 안 다시 만드는 프레임워크"입니다.** 커넥터 플러그인 자체는 여전히 Java 코드지만, 여러분이 쓰는 것은 대개 이미 존재합니다.

```
                        Connect 워커 (JVM 프로세스)
   ┌──────────────────────────────────────────────────────────────┐
   │  Herder — 어느 태스크를 어느 워커가 돌릴지 결정               │
   │     │                                                        │
   │     ├── Connector(설정만 담당, 데이터 안 옮김)                │
   │     └── Task 0 / Task 1 / Task 2  ← 실제로 데이터를 옮김      │
   │              │                                               │
   │        SMT 체인 → Converter → Producer/Consumer               │
   └──────────────┼───────────────────────────────────────────────┘
                  ▼
          Kafka 클러스터 (kafka-1/2/3)
```

**커넥터(Connector)와 태스크(Task)는 다릅니다.** 커넥터는 "일을 몇 조각으로 나눌지" 계획만 세우고, 실제 데이터는 태스크가 옮깁니다. 이 구분이 12-7 의 함정 C 로 이어집니다.

---

## 12-2. 스탠드얼론 vs 분산 모드

| 항목 | 스탠드얼론 | 분산 (이 코스) |
|---|---|---|
| 실행 | `connect-standalone.sh worker.properties conn.properties` | `connect-distributed.sh worker.properties` |
| 커넥터 등록 | **프로퍼티 파일** | **REST API** |
| 오프셋 저장 | 로컬 파일 (`offset.storage.file.filename`) | **`connect-offsets` 토픽** |
| 설정 저장 | 프로퍼티 파일 | **`connect-configs` 토픽** |
| 상태 저장 | 메모리 | **`connect-status` 토픽** |
| 워커 수 | 1 | N (같은 `group.id` 면 자동 클러스터링) |
| 장애 시 | 프로세스가 죽으면 끝 | 다른 워커가 태스크를 인계 |
| 용도 | 개발, 로그 수집 에이전트(호스트마다 1개) | **운영 전부** |

**이 코스는 분산 모드입니다.** 워커가 한 대뿐이어도 분산 모드입니다. 워커 수와 모드는 무관합니다. 분산 모드로 띄운 워커 한 대에 워커를 한 대 더 붙이면(같은 `CONNECT_GROUP_ID`) 태스크가 자동으로 재분배됩니다.

> 💡 **실무 팁 — 스탠드얼론을 운영에 쓰는 유일한 정당한 경우**
> 각 서버의 로컬 파일을 읽어야 할 때(예: 호스트마다 있는 애플리케이션 로그)는 그 호스트에 스탠드얼론 워커를 하나씩 띄우는 것이 맞습니다. 분산 모드는 태스크가 **어느 워커로든 갈 수 있으므로**, "이 파일은 이 호스트에만 있다"는 전제가 깨집니다.
> 다만 요즘은 이 용도조차 Filebeat / Fluent Bit 를 씁니다. 결론적으로 **Connect 는 사실상 분산 모드만 씁니다.**

---

## 12-3. 내부 토픽 3종

Connect 가 기동하면서 토픽을 만들었습니다. 확인합니다.

```bash
kt --list | grep '^connect-'
```

**결과**

```
connect-configs
connect-offsets
connect-status
```

셋을 각각 봅니다.

```bash
kt --describe --topic connect-configs
```

**결과**

```
Topic: connect-configs	TopicId: 8dK2mQ9pTZ2vLxRnW4bYeA	PartitionCount: 1	ReplicationFactor: 3	Configs: cleanup.policy=compact
	Topic: connect-configs	Partition: 0	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
```

```bash
kt --describe --topic connect-offsets
```

**결과**

```
Topic: connect-offsets	TopicId: pQ7wR1nMSA6yKcHvZ3tXbg	PartitionCount: 25	ReplicationFactor: 3	Configs: cleanup.policy=compact
	Topic: connect-offsets	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: connect-offsets	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: connect-offsets	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	...
	Topic: connect-offsets	Partition: 24	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
```

```bash
kt --describe --topic connect-status
```

**결과**

```
Topic: connect-status	TopicId: vN4jL8xKQR2mBcYtW7pZdQ	PartitionCount: 5	ReplicationFactor: 3	Configs: cleanup.policy=compact
	Topic: connect-status	Partition: 0	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: connect-status	Partition: 1	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: connect-status	Partition: 2	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: connect-status	Partition: 3	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: connect-status	Partition: 4	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

| 토픽 | 파티션 | 정책 | 담는 것 | 왜 그 파티션 수 |
|---|---:|---|---|---|
| `connect-configs` | **1 (강제)** | compact | 커넥터 설정 JSON, 태스크 배정 | 설정 변경은 **전역 순서**가 있어야 함 |
| `connect-offsets` | 25 | compact | 소스 커넥터의 소스 오프셋 | 커넥터가 많아지면 병렬로 쓰므로 |
| `connect-status` | 5 | compact | 커넥터/태스크의 RUNNING·FAILED 상태 | 상태 갱신은 잦지만 순서가 전역일 필요는 없음 |

### connect-configs 가 파티션 1개여야 하는 이유

Connect 는 설정 변경을 **로그 순서로 재생**해서 현재 상태를 만듭니다. "커넥터 A 생성 → A 설정 변경 → A 삭제 → A 재생성" 이 네 이벤트가 순서대로 읽혀야 최종 상태가 맞습니다.

파티션이 2개면 `A 삭제` 가 파티션 0 에, `A 재생성` 이 파티션 1 에 들어갈 수 있고, 워커가 파티션을 병렬로 읽으면 **재생성을 먼저 보고 삭제를 나중에 볼 수 있습니다.** 결과는 "방금 만든 커넥터가 사라짐"입니다.

그래서 Connect 는 이 토픽의 파티션 수를 **검증합니다.** 강제로 늘려 보면 워커가 기동을 거부합니다.

```
org.apache.kafka.connect.errors.ConnectException: Topic 'connect-configs' supplied
  via the 'config.storage.topic' property is required to have a single partition
  in order to guarantee consistency, but found 3 partitions.
```

### 세 토픽이 모두 compact 인 이유

셋 다 **"키별 최신 값"만 의미가 있습니다.**

- `connect-configs` 의 키는 `connector-file-source` 같은 문자열. 최신 설정 하나만 필요합니다.
- `connect-status` 의 키는 `status-connector-file-source`. 지금 RUNNING 인지만 필요합니다.
- `connect-offsets` 의 키는 `["file-source",{"filename":"/data/orders.txt"}]`. 마지막으로 읽은 지점만 필요합니다.

`delete` 정책이면 retention 이 지나는 순간 **커넥터 설정과 오프셋이 통째로 사라집니다.** 워커를 재시작하면 커넥터가 전부 없어져 있습니다. Compact 는 "최신 값은 영원히 보존"이므로 이 사고를 막습니다. (Step 09 의 압축 토픽 설계 기준이 그대로 적용된 사례입니다.)

> ⚠️ **함정 — 내부 토픽을 손으로 미리 만들면 대개 틀립니다**
> Connect 는 토픽이 없으면 AdminClient 로 알아서 만듭니다(`auto.create.topics.enable=false` 여도 만듭니다 — Step 13 의 Streams 와 같은 방식입니다).
> 그런데 "운영에서는 토픽을 사전 승인 절차로 만든다"는 규정 때문에 손으로 먼저 만드는 조직이 많고, 그때 **`cleanup.policy` 를 빼먹습니다.** 기본값은 `delete` 입니다.
> 증상은 몇 주 뒤에 나옵니다. retention 7일이 지나면 커넥터 설정이 사라지고, 워커를 재시작하는 순간 커넥터가 전부 증발합니다. **재시작하기 전까지는 멀쩡히 돌아갑니다.**
> **해결**: 손으로 만들 거면 `--config cleanup.policy=compact` 를 반드시 붙이고, `connect-configs` 는 `--partitions 1` 로 만듭니다.

---

## 12-4. REST API 전부

Connect 를 조작하는 방법은 REST API 뿐입니다. 전부 한 번씩 호출해 봅니다.

### 설치된 플러그인 목록

```bash
curl -s localhost:8083/connector-plugins | jq
```

**결과**

```json
[
  {
    "class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
    "type": "sink",
    "version": "7.6.1-ccs"
  },
  {
    "class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
    "type": "source",
    "version": "7.6.1-ccs"
  },
  {
    "class": "org.apache.kafka.connect.mirror.MirrorCheckpointConnector",
    "type": "source",
    "version": "7.6.1-ccs"
  },
  {
    "class": "org.apache.kafka.connect.mirror.MirrorHeartbeatConnector",
    "type": "source",
    "version": "7.6.1-ccs"
  },
  {
    "class": "org.apache.kafka.connect.mirror.MirrorSourceConnector",
    "type": "source",
    "version": "7.6.1-ccs"
  }
]
```

**JDBC 커넥터가 없습니다.** 기본 이미지에는 FileStream 과 MirrorMaker2 용 커넥터만 들어 있습니다. JDBC 는 12-9 에서 설정만 다룹니다.

### 커넥터 목록 (지금은 비어 있음)

```bash
curl -s localhost:8083/connectors | jq
```

**결과**

```json
[]
```

### 커넥터 등록 — `POST /connectors`

12-5 에서 실제로 씁니다. 형식은 이렇습니다.

```bash
curl -s -X POST localhost:8083/connectors \
  -H 'Content-Type: application/json' \
  -d '{ "name": "커넥터이름", "config": { ... } }' | jq
```

### 나머지 엔드포인트 요약

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `GET` | `/connectors` | 커넥터 이름 목록 |
| `POST` | `/connectors` | 등록 |
| `GET` | `/connectors/{name}` | 설정 + 태스크 목록 |
| `GET` | `/connectors/{name}/config` | 설정만 |
| `PUT` | `/connectors/{name}/config` | **설정 변경 (없으면 생성)** |
| `GET` | `/connectors/{name}/status` | 커넥터/태스크 상태 |
| `POST` | `/connectors/{name}/restart?includeTasks=true&onlyFailed=true` | 재시작 |
| `POST` | `/connectors/{name}/tasks/{id}/restart` | 태스크 하나만 재시작 |
| `PUT` | `/connectors/{name}/pause` | 일시정지 |
| `PUT` | `/connectors/{name}/resume` | 재개 |
| `GET` | `/connectors/{name}/offsets` | **오프셋 조회 (3.6+)** |
| `PATCH` | `/connectors/{name}/offsets` | 오프셋 수정 (3.6+) |
| `DELETE` | `/connectors/{name}/offsets` | **오프셋 삭제 (3.6+, 커넥터가 STOPPED 여야 함)** |
| `PUT` | `/connectors/{name}/stop` | 완전 정지 (3.5+, pause 와 다름) |
| `DELETE` | `/connectors/{name}` | 삭제 |

> 💡 **`PUT /config` 는 멱등입니다**
> `POST /connectors` 는 이미 있는 이름이면 `409 Conflict` 를 냅니다. 반면 `PUT /connectors/{name}/config` 는 없으면 만들고 있으면 갱신합니다.
> 그래서 **CI/CD 에서 커넥터를 배포할 때는 항상 `PUT /config` 를 씁니다.** 배포 스크립트에 "존재 여부 확인" 분기를 넣을 필요가 없습니다.

> 💡 **`pause` 와 `stop` 은 다릅니다 (3.5+)**
> `pause` 는 태스크를 **살려 둔 채** 데이터만 안 옮깁니다. 리소스는 계속 씁니다.
> `stop` 은 태스크를 **전부 내립니다.** 설정은 남고 리소스는 반납합니다. `DELETE /offsets` 는 `stop` 상태에서만 됩니다.

---

## 12-5. FileStreamSource — 파일을 토픽으로

먼저 대상 토픽을 만들고, 읽을 파일을 준비합니다.

```bash
kt --create --topic s12_file --partitions 3 --replication-factor 3
```

**결과**

```
Created topic s12_file.
```

```bash
cd kafka/docker
cat > connect-data/orders.txt <<'EOF'
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
{"order_id":"O-1002","customer_id":"C002","amount":12500,"status":"CREATED"}
{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CREATED"}
EOF
docker exec kafka-connect cat /data/orders.txt
```

**결과**

```
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
{"order_id":"O-1002","customer_id":"C002","amount":12500,"status":"CREATED"}
{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CREATED"}
```

커넥터를 등록합니다.

```bash
curl -s -X POST localhost:8083/connectors \
  -H 'Content-Type: application/json' -d '{
  "name": "file-source",
  "config": {
    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
    "tasks.max": "1",
    "file": "/data/orders.txt",
    "topic": "s12_file",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}' | jq
```

**결과**

```json
{
  "name": "file-source",
  "config": {
    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
    "tasks.max": "1",
    "file": "/data/orders.txt",
    "topic": "s12_file",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter",
    "name": "file-source"
  },
  "tasks": [],
  "type": "source"
}
```

**`"tasks": []` 가 비어 있는 것이 정상입니다.** 등록 응답은 태스크가 배정되기 **전에** 돌아옵니다. 1~2초 뒤 상태를 봅니다.

```bash
curl -s localhost:8083/connectors/file-source/status | jq
```

**결과**

```json
{
  "name": "file-source",
  "connector": {
    "state": "RUNNING",
    "worker_id": "kafka-connect:8083"
  },
  "tasks": [
    {
      "id": 0,
      "state": "RUNNING",
      "worker_id": "kafka-connect:8083"
    }
  ],
  "type": "source"
}
```

토픽에 들어갔는지 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s12_file \
  --from-beginning --property print.offset=true --timeout-ms 5000
```

**결과**

```
Offset:0	{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
Offset:1	{"order_id":"O-1002","customer_id":"C002","amount":12500,"status":"CREATED"}
Offset:2	{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CREATED"}
Processed a total of 3 messages
```

파일에 한 줄을 더 붙이면 **꼬리를 물고 따라옵니다**(`tail -f` 와 같은 동작입니다).

```bash
echo '{"order_id":"O-1004","customer_id":"C003","amount":5000,"status":"CREATED"}' \
  >> connect-data/orders.txt
sleep 3
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s12_file
```

**결과**

```
s12_file:0:4
s12_file:1:0
s12_file:2:0
```

**전부 파티션 0 에 들어갔습니다.** FileStreamSource 는 키를 만들지 않으므로(`key=null`) 파티셔너가 스티키 파티셔닝으로 한 파티션에 몰아 넣습니다. Step 04 에서 본 그 동작입니다. 파티션을 분산시키려면 SMT 로 키를 만들어야 합니다(12-8 의 `ValueToKey`).

### 소스 오프셋을 직접 봅니다

```bash
curl -s localhost:8083/connectors/file-source/offsets | jq
```

**결과**

```json
{
  "offsets": [
    {
      "partition": {
        "filename": "/data/orders.txt"
      },
      "offset": {
        "position": 304
      }
    }
  ]
}
```

`position: 304` 는 **바이트 위치**입니다. 4줄 합계 바이트 수입니다. FileStreamSource 는 "몇 줄째"가 아니라 "몇 바이트째"를 기억합니다. 이 사실이 함정 B 에서 중요해집니다.

원본을 봐도 됩니다. `connect-offsets` 토픽을 직접 읽습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic connect-offsets \
  --from-beginning --property print.key=true --timeout-ms 5000
```

**결과**

```
["file-source",{"filename":"/data/orders.txt"}]	{"position":304}
Processed a total of 1 messages
```

키가 `[커넥터이름, 소스파티션]`, 값이 `{소스오프셋}` 입니다. compact 토픽이므로 같은 키의 옛 값은 사라지고 최신 하나만 남습니다.

---

## 12-6. FileStreamSink — 토픽을 다시 파일로

같은 데이터를 다른 파일로 뽑아냅니다.

```bash
curl -s -X POST localhost:8083/connectors \
  -H 'Content-Type: application/json' -d '{
  "name": "file-sink",
  "config": {
    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
    "tasks.max": "1",
    "topics": "s12_file",
    "file": "/data/orders-out.txt",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}' | jq -r '.name + " → " + .type'
```

**결과**

```
file-sink → sink
```

```bash
sleep 5
docker exec kafka-connect cat /data/orders-out.txt
```

**결과**

```
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
{"order_id":"O-1002","customer_id":"C002","amount":12500,"status":"CREATED"}
{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CREATED"}
{"order_id":"O-1004","customer_id":"C003","amount":5000,"status":"CREATED"}
```

**소스 설정 키는 `topic`(단수), 싱크 설정 키는 `topics`(복수)입니다.** 싱크는 여러 토픽을 한꺼번에 받을 수 있고, `topics.regex` 로 패턴 지정도 됩니다. 소스는 하나만 씁니다. 이 한 글자 차이로 `Missing required configuration "topics"` 에러를 만나는 일이 잦습니다.

---

## 12-7. 세 가지 핵심 함정

### 함정 A — 소스와 싱크의 오프셋은 다른 곳에 저장됩니다

방금 커넥터 두 개를 만들었습니다. 컨슈머 그룹 목록을 봅니다.

```bash
kcg --list
```

**결과**

```
connect-file-sink
```

**`connect-file-sink` 만 있고 `connect-file-source` 는 없습니다.**

> ⚠️ **함정 A — 소스는 `connect-offsets` 토픽, 싱크는 일반 컨슈머 그룹**
>
> | | 소스 커넥터 | 싱크 커넥터 |
> |---|---|---|
> | 오프셋 저장 위치 | **`connect-offsets` 토픽** | **컨슈머 그룹 `connect-<커넥터이름>`** (즉 `__consumer_offsets`) |
> | 조회 방법 | `GET /connectors/{n}/offsets` 또는 토픽 직접 읽기 | `kcg --describe --group connect-<이름>` |
> | 오프셋의 의미 | 소스 시스템의 위치 (파일 바이트, DB 커서, LSN) | **Kafka 토픽의 오프셋** |
> | 리셋 방법 | `DELETE /connectors/{n}/offsets` | `kcg --reset-offsets` 또는 `DELETE /offsets` |
> | 재시작 시 | 소스를 그 지점부터 다시 읽음 | 토픽을 그 오프셋부터 다시 소비 |
>
> **왜 다릅니까**: 싱크 커넥터는 그냥 컨슈머입니다. Kafka 가 이미 오프셋 관리 메커니즘(컨슈머 그룹)을 갖고 있으니 그걸 씁니다. 소스 커넥터는 반대로 **Kafka 밖의 무언가**를 읽으므로, "그 밖의 무언가의 위치"를 저장할 곳이 따로 필요합니다.
>
> **왜 문제입니까**: 오프셋을 되돌리려 할 때 **두 방법이 다릅니다.** 싱크 커넥터를 리셋하려고 `connect-offsets` 토픽을 뒤지면 아무것도 없습니다. 반대로 소스 커넥터를 리셋하려고 `kcg --reset-offsets` 를 하려 하면 그런 그룹이 없다고 나옵니다.

싱크 쪽 그룹을 확인합니다.

```bash
kcg --describe --group connect-file-sink
```

**결과**

```
GROUP             TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                     HOST            CLIENT-ID
connect-file-sink s12_file        0          4               4               0               connector-consumer-file-sink-0-a3f91e2b-...     /172.18.0.7     connector-consumer-file-sink-0
connect-file-sink s12_file        1          0               0               0               connector-consumer-file-sink-0-a3f91e2b-...     /172.18.0.7     connector-consumer-file-sink-0
connect-file-sink s12_file        2          0               0               0               connector-consumer-file-sink-0-a3f91e2b-...     /172.18.0.7     connector-consumer-file-sink-0
```

**완전히 평범한 컨슈머 그룹입니다.** Step 05·06 에서 배운 모든 것이 그대로 적용됩니다. 랙 모니터링도 똑같이 하면 됩니다.

### 함정 B — 커넥터를 삭제해도 오프셋이 남습니다

이 함정을 재현합니다. 소스 커넥터를 지웁니다.

```bash
curl -s -X DELETE localhost:8083/connectors/file-source -w '%{http_code}\n'
curl -s localhost:8083/connectors | jq
```

**결과**

```
204
[
  "file-sink"
]
```

지워졌습니다. 이제 **파일도 새로 만듭니다.** 처음부터 다시 읽히기를 기대하면서요.

```bash
cat > connect-data/orders.txt <<'EOF'
{"order_id":"O-2001","customer_id":"C005","amount":77000,"status":"CREATED"}
{"order_id":"O-2002","customer_id":"C006","amount":31000,"status":"CREATED"}
EOF
wc -c connect-data/orders.txt
```

**결과**

```
152 connect-data/orders.txt
```

같은 이름으로 커넥터를 다시 만듭니다.

```bash
curl -s -X PUT localhost:8083/connectors/file-source/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "/data/orders.txt",
  "topic": "s12_file",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' | jq -r '.name'

sleep 5
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s12_file
```

**결과**

```
file-source
s12_file:0:4
s12_file:1:0
s12_file:2:0
```

**메시지가 하나도 안 늘었습니다.** 새 파일에 2줄을 넣었는데 토픽은 그대로 4건입니다.

이유를 확인합니다.

```bash
curl -s localhost:8083/connectors/file-source/offsets | jq
```

**결과**

```json
{
  "offsets": [
    {
      "partition": {
        "filename": "/data/orders.txt"
      },
      "offset": {
        "position": 304
      }
    }
  ]
}
```

> ⚠️ **함정 B — 커넥터 삭제는 오프셋을 지우지 않습니다**
> `DELETE /connectors/file-source` 는 `connect-configs` 에서 설정만 지웁니다. `connect-offsets` 의 레코드는 **그대로 남습니다.**
> 같은 이름으로 다시 만들면 오프셋 키(`["file-source",{"filename":"/data/orders.txt"}]`)가 정확히 일치하므로 **304 바이트째부터 이어서 읽습니다.** 새 파일은 152 바이트뿐이니 읽을 것이 없고, 커넥터는 `RUNNING` 인 채로 아무것도 안 합니다.
> **에러는 나지 않습니다.** 로그에도 아무것도 안 찍힙니다. "커넥터를 다시 만들었는데 데이터가 안 나온다"는 미스터리의 정체가 이것입니다.
>
> **해결 세 가지**
> 1. **오프셋을 명시적으로 지운다** (3.6+ 권장):
>    ```bash
>    curl -s -X PUT    localhost:8083/connectors/file-source/stop
>    curl -s -X DELETE localhost:8083/connectors/file-source/offsets | jq
>    curl -s -X PUT    localhost:8083/connectors/file-source/resume
>    ```
> 2. **커넥터 이름을 바꾼다** — `file-source-v2`. 키가 달라지므로 새 오프셋이 됩니다. 가장 안전하고, 운영에서 실제로 가장 많이 쓰는 방법입니다.
> 3. 소스 파티션 키를 바꾼다 — 파일 경로를 `/data/orders-v2.txt` 로. 역시 키가 달라집니다.

1번 방법을 실행합니다.

```bash
curl -s -X PUT localhost:8083/connectors/file-source/stop -w '%{http_code}\n'
sleep 2
curl -s -X DELETE localhost:8083/connectors/file-source/offsets | jq
```

**결과**

```
204
{
  "message": "The Connect framework-managed offsets for this connector have been reset successfully. However, if this connector manages offsets externally, they will need to be manually reset in the system that the connector uses."
}
```

```bash
curl -s -X PUT localhost:8083/connectors/file-source/resume -w '%{http_code}\n'
sleep 5
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s12_file
```

**결과**

```
204
s12_file:0:6
s12_file:1:0
s12_file:2:0
```

**4 → 6.** 새 파일의 2줄이 들어왔습니다.

응답 메시지의 뒷문장(`if this connector manages offsets externally`)이 중요합니다. **Connect 가 관리하지 않는 오프셋은 Connect 가 지워 줄 수 없습니다.** Debezium 처럼 일부 커넥터는 자체 스냅샷 상태를 별도 토픽/테이블에 두기도 합니다.

### 함정 C — 커넥터는 RUNNING 인데 태스크는 죽어 있습니다

싱크 커넥터가 쓰는 파일 경로를 **쓸 수 없는 곳**으로 바꿔 태스크를 죽입니다.

```bash
curl -s -X PUT localhost:8083/connectors/file-sink/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_file",
  "file": "/proc/nope/orders-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null

sleep 5
curl -s localhost:8083/connectors/file-sink/status | jq
```

**결과**

```json
{
  "name": "file-sink",
  "connector": {
    "state": "RUNNING",
    "worker_id": "kafka-connect:8083"
  },
  "tasks": [
    {
      "id": 0,
      "state": "FAILED",
      "worker_id": "kafka-connect:8083",
      "trace": "org.apache.kafka.connect.errors.ConnectException: Couldn't find or create file '/proc/nope/orders-out.txt' for FileStreamSinkTask\n\tat org.apache.kafka.connect.file.FileStreamSinkTask.start(FileStreamSinkTask.java:80)\n\tat org.apache.kafka.connect.runtime.WorkerSinkTask.initializeAndStart(WorkerSinkTask.java:314)\n\tat org.apache.kafka.connect.runtime.WorkerTask.doRun(WorkerTask.java:206)\n\tat org.apache.kafka.connect.runtime.WorkerTask.run(WorkerTask.java:259)\n\tat org.apache.kafka.connect.runtime.AbstractWorkerSourceTask.run(AbstractWorkerSourceTask.java:77)\n\tat java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:539)\n\tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)\n\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)\n\tat java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)\n\tat java.base/java.lang.Thread.run(Thread.java:840)\nCaused by: java.io.FileNotFoundException: /proc/nope/orders-out.txt (No such file or directory)\n\t... 10 more\n"
    }
  ],
  "type": "sink"
}
```

> ⚠️ **함정 C — `connector.state` 만 보는 모니터링은 장애를 전부 놓칩니다**
> **커넥터는 `RUNNING` 입니다.** 데이터는 한 건도 안 흐르는데요.
> 12-1 에서 설명한 대로 커넥터는 "계획"만 세웁니다. 계획을 세우는 데는 아무 문제가 없었으므로 커넥터는 정상입니다. 실제로 일하는 태스크만 죽었습니다.
> 그런데 대부분의 모니터링 스크립트는 `curl .../status | jq -r '.connector.state'` 한 줄로 끝납니다. **이 스크립트는 절대 알람을 울리지 않습니다.**
>
> **해결 — 태스크 상태까지 봐야 합니다**
> ```bash
> curl -s localhost:8083/connectors/file-sink/status \
>   | jq -r '[.connector.state] + [.tasks[].state] | join(",")'
> ```
> **결과**
> ```
> RUNNING,FAILED
> ```
> 전 커넥터를 한 번에 점검하는 형태:
> ```bash
> for c in $(curl -s localhost:8083/connectors | jq -r '.[]'); do
>   s=$(curl -s localhost:8083/connectors/$c/status)
>   echo "$c $(echo "$s" | jq -r '.connector.state') tasks=$(echo "$s" | jq -r '[.tasks[].state] | join(",")')"
> done
> ```
> **결과**
> ```
> file-sink RUNNING tasks=FAILED
> file-source RUNNING tasks=RUNNING
> ```
> 운영에서는 이 출력에 `FAILED` 문자열이 있으면 알람을 겁니다. `.connector.state` 만 보지 마세요.

`trace` 필드에 스택트레이스 전문이 들어옵니다. 읽기 좋게 뽑습니다.

```bash
curl -s localhost:8083/connectors/file-sink/status | jq -r '.tasks[0].trace' | head -3
```

**결과**

```
org.apache.kafka.connect.errors.ConnectException: Couldn't find or create file '/proc/nope/orders-out.txt' for FileStreamSinkTask
	at org.apache.kafka.connect.file.FileStreamSinkTask.start(FileStreamSinkTask.java:80)
	at org.apache.kafka.connect.runtime.WorkerSinkTask.initializeAndStart(WorkerSinkTask.java:314)
```

고치고 재시작합니다. **설정을 고쳐도 죽은 태스크는 자동으로 안 살아납니다.** 명시적으로 재시작해야 합니다.

```bash
curl -s -X PUT localhost:8083/connectors/file-sink/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_file",
  "file": "/data/orders-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null

curl -s -X POST 'localhost:8083/connectors/file-sink/restart?includeTasks=true&onlyFailed=true' -w '%{http_code}\n'
sleep 5
curl -s localhost:8083/connectors/file-sink/status | jq -r '[.connector.state] + [.tasks[].state] | join(",")'
```

**결과**

```
204
RUNNING,RUNNING
```

`includeTasks=true` 가 없으면 **커넥터만 재시작하고 태스크는 FAILED 로 남습니다.** 3.0 이전에는 이 쿼리 파라미터 자체가 없어서, 태스크를 하나씩 `POST /connectors/{n}/tasks/{id}/restart` 로 살려야 했습니다.

---

## 12-8. SMT — 흐르는 도중에 메시지를 고친다

SMT(Single Message Transform)는 레코드 하나하나를 커넥터와 Kafka 사이에서 변환합니다. **체인으로 여러 개를 순서대로 적용합니다.**

```
소스 커넥터 → [SMT 1] → [SMT 2] → [SMT 3] → Converter → Kafka
Kafka → Converter → [SMT 1] → [SMT 2] → 싱크 커넥터
```

주요 SMT 를 표로 정리합니다.

| SMT | 하는 일 | 대표 설정 |
|---|---|---|
| `InsertField` | 필드 추가 (타임스탬프, 토픽명, 파티션, 고정값) | `timestamp.field`, `static.field`/`static.value` |
| `ReplaceField` | 필드 이름 변경 / 제외 / 포함 | `renames`, `exclude`, `include` |
| `MaskField` | 필드 값 마스킹 | `fields`, `replacement` |
| `ValueToKey` | **값의 필드를 키로 승격** | `fields` |
| `ExtractField` | 구조체에서 필드 하나만 꺼냄 | `field` |
| `TimestampConverter` | 타임스탬프 포맷 변환 | `target.type`, `format`, `field` |
| `RegexRouter` | **토픽 이름 변경 (라우팅)** | `regex`, `replacement` |
| `Filter` (`predicates` 와 함께) | 레코드 버리기 | `predicates`, `negate` |
| `Cast` | 타입 변환 | `spec` |

### 실제 조합 — 키 승격 + 타임스탬프 추가 + 라우팅

12-5 에서 모든 메시지가 파티션 0 에 몰렸습니다. `customer_id` 를 키로 만들어 분산시키고, 처리 시각을 붙이고, 토픽 이름 앞에 접두사를 붙입니다.

```bash
kt --create --topic s12_smt.s12_file --partitions 3 --replication-factor 3
```

**결과**

```
Created topic s12_smt.s12_file.
```

```bash
curl -s -X PUT localhost:8083/connectors/file-source-smt/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "/data/orders-smt.txt",
  "topic": "s12_file",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false",

  "transforms": "parse,mkKey,extractKey,addTs,route",

  "transforms.parse.type": "org.apache.kafka.connect.transforms.HoistField$Value",
  "transforms.parse.field": "line",

  "transforms.mkKey.type": "org.apache.kafka.connect.transforms.ValueToKey",
  "transforms.mkKey.fields": "line",

  "transforms.extractKey.type": "org.apache.kafka.connect.transforms.ExtractField$Key",
  "transforms.extractKey.field": "line",

  "transforms.addTs.type": "org.apache.kafka.connect.transforms.InsertField$Value",
  "transforms.addTs.timestamp.field": "ingested_at",

  "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
  "transforms.route.regex": "(.*)",
  "transforms.route.replacement": "s12_smt.$1"
}' | jq -r '.name'
```

**결과**

```
file-source-smt
```

`transforms` 값의 **쉼표 순서가 곧 적용 순서**입니다. `parse → mkKey → extractKey → addTs → route`. 순서를 바꾸면 결과가 달라집니다. 예를 들어 `route` 를 맨 앞에 두면 아직 변환 전인 토픽 이름에 적용되어 결과는 같지만, `addTs` 를 `mkKey` 앞에 두면 키에 `ingested_at` 이 섞여 들어갑니다.

### 변환 전 / 후 비교

**변환 전** (12-5 의 `s12_file`):

```
Partition:0	Offset:0	null	{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
```

**변환 후** (`s12_smt.s12_file`):

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s12_smt.s12_file \
  --from-beginning --property print.key=true --property print.partition=true \
  --timeout-ms 5000
```

**결과**

```
Partition:1	{"order_id":"O-3001","customer_id":"C007","amount":21000}	{"line":"{\"order_id\":\"O-3001\",...}","ingested_at":1710120245881}
Partition:2	{"order_id":"O-3002","customer_id":"C008","amount":45000}	{"line":"{\"order_id\":\"O-3002\",...}","ingested_at":1710120245883}
Processed a total of 2 messages
```

| 항목 | 전 | 후 |
|---|---|---|
| 토픽 | `s12_file` | `s12_smt.s12_file` (RegexRouter) |
| 키 | `null` | 값 문자열 (ValueToKey + ExtractField) |
| 파티션 | 전부 0 | **1, 2 로 분산** |
| 값 | 원문 문자열 | `line` + `ingested_at` 필드 (HoistField + InsertField) |

> ⚠️ **함정 — SMT 는 스키마가 있는 레코드에만 온전히 동작합니다**
> `ValueToKey` 나 `InsertField` 는 값이 **`Struct` 또는 `Map`** 이어야 필드를 다룰 수 있습니다. FileStreamSource 는 값을 **평범한 문자열**로 내보내므로, 그대로 `ValueToKey` 를 걸면 이렇게 죽습니다.
> ```
> org.apache.kafka.connect.errors.DataException: Only Struct objects supported for
>   [copying fields from value to key], found: java.lang.String
> ```
> 위 설정에서 `HoistField$Value` 를 맨 앞에 둔 이유가 이것입니다. 문자열을 `{"line": "..."}` 라는 Map 으로 한 번 감싸서 다음 SMT 들이 다룰 수 있게 만드는 것입니다.
> **실무에서는 이 문제가 거의 없습니다.** JDBC 나 Debezium 소스는 처음부터 스키마 있는 `Struct` 를 내보내기 때문입니다. 파일 소스가 유별난 것입니다.

> 💡 **실무 팁 — SMT 는 두세 개까지만**
> SMT 는 레코드마다 **순차 실행**됩니다. 다섯 개를 걸면 처리량이 눈에 띄게 떨어집니다(측정해 보면 SMT 5개 체인이 대략 15~25% 처리량을 깎습니다).
> 그리고 SMT 에는 조인도 집계도 없습니다. **"필드 하나 붙이고 이름 바꾸고 라우팅"까지가 SMT 의 영역**이고, 그 이상은 Kafka Streams(Step 13)나 ksqlDB 의 일입니다.
> "SMT 를 여덟 개 체인으로 걸었다"면 이미 잘못된 도구를 쓰고 있는 것입니다.

---

## 12-9. 에러 처리와 DLQ

싱크 커넥터가 처리할 수 없는 레코드를 만나면 기본 동작은 **태스크 중단**입니다. 한 건 때문에 파이프라인 전체가 멈춥니다.

```bash
kt --create --topic s12_dlq_src --partitions 1 --replication-factor 3
kt --create --topic s12_dlq --partitions 1 --replication-factor 3
```

**결과**

```
Created topic s12_dlq_src.
Created topic s12_dlq.
```

JSON 을 기대하는 싱크에 **깨진 JSON** 을 섞어 넣습니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s12_dlq_src <<'EOF'
{"order_id":"O-4001","amount":10000}
NOT-A-JSON
{"order_id":"O-4002","amount":20000}
EOF
```

DLQ 설정 없이 커넥터를 만듭니다.

```bash
curl -s -X PUT localhost:8083/connectors/dlq-sink/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_dlq_src",
  "file": "/data/dlq-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false"
}' >/dev/null
sleep 6
curl -s localhost:8083/connectors/dlq-sink/status | jq -r '[.connector.state] + [.tasks[].state] | join(",")'
```

**결과**

```
RUNNING,FAILED
```

```bash
curl -s localhost:8083/connectors/dlq-sink/status | jq -r '.tasks[0].trace' | head -4
```

**결과**

```
org.apache.kafka.connect.errors.DataException: Converting byte[] to Kafka Connect data failed due to serialization error:
	at org.apache.kafka.connect.json.JsonConverter.toConnectData(JsonConverter.java:333)
	at org.apache.kafka.connect.runtime.WorkerSinkTask.convertValue(WorkerSinkTask.java:545)
Caused by: org.apache.kafka.common.errors.SerializationException: com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'NOT': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
```

**정상 레코드 2건도 처리되지 않았습니다.** 한 건이 전체를 세웠습니다.

이제 DLQ 를 붙입니다.

```bash
curl -s -X PUT localhost:8083/connectors/dlq-sink/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_dlq_src",
  "file": "/data/dlq-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false",

  "errors.tolerance": "all",
  "errors.log.enable": "true",
  "errors.log.include.messages": "true",
  "errors.deadletterqueue.topic.name": "s12_dlq",
  "errors.deadletterqueue.topic.replication.factor": "3",
  "errors.deadletterqueue.context.headers.enable": "true"
}' >/dev/null

curl -s -X POST 'localhost:8083/connectors/dlq-sink/restart?includeTasks=true' -w '%{http_code}\n'
sleep 6
curl -s localhost:8083/connectors/dlq-sink/status | jq -r '[.connector.state] + [.tasks[].state] | join(",")'
```

**결과**

```
204
RUNNING,RUNNING
```

```bash
docker exec kafka-connect cat /data/dlq-out.txt
```

**결과**

```
{order_id=O-4001, amount=10000}
{order_id=O-4002, amount=20000}
```

**정상 2건이 통과했습니다.** 깨진 1건은 DLQ 로 갔습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s12_dlq --from-beginning \
  --property print.headers=true --timeout-ms 5000
```

**결과**

```
__connect.errors.topic:s12_dlq_src,__connect.errors.partition:0,__connect.errors.offset:1,__connect.errors.connector.name:dlq-sink,__connect.errors.task.id:0,__connect.errors.stage:VALUE_CONVERTER,__connect.errors.class.name:org.apache.kafka.connect.json.JsonConverter,__connect.errors.exception.class.name:org.apache.kafka.connect.errors.DataException,__connect.errors.exception.message:Converting byte[] to Kafka Connect data failed due to serialization error:	NOT-A-JSON
Processed a total of 1 messages
```

> 💡 **`errors.deadletterqueue.context.headers.enable=true` 가 이 절의 핵심입니다**
> 이게 없으면 DLQ 에는 `NOT-A-JSON` 이라는 **원본 바이트만** 들어갑니다. 왜 실패했는지, 어느 토픽·파티션·오프셋에서 왔는지 알 방법이 없습니다.
> `true` 로 두면 헤더 8종이 붙습니다.
>
> | 헤더 | 값 |
> |---|---|
> | `__connect.errors.topic` | `s12_dlq_src` |
> | `__connect.errors.partition` | `0` |
> | `__connect.errors.offset` | `1` |
> | `__connect.errors.connector.name` | `dlq-sink` |
> | `__connect.errors.task.id` | `0` |
> | `__connect.errors.stage` | `VALUE_CONVERTER` |
> | `__connect.errors.exception.class.name` | `org.apache.kafka.connect.errors.DataException` |
> | `__connect.errors.exception.message` | 예외 메시지 |
>
> **`stage` 가 특히 유용합니다.** `KEY_CONVERTER` / `VALUE_CONVERTER` / `TRANSFORMATION` / `TASK_PUT` 중 어디서 터졌는지 알려 주므로, 문제가 데이터인지 SMT 설정인지 대상 시스템인지 즉시 구분됩니다.
> 원본 오프셋을 알면 재처리도 가능합니다. `s12_dlq_src` 파티션 0 오프셋 1 을 고쳐서 다시 넣으면 됩니다.

> ⚠️ **함정 — `errors.tolerance=all` 은 DLQ 없이 쓰면 데이터를 조용히 버립니다**
> `errors.tolerance=all` 만 켜고 `errors.deadletterqueue.topic.name` 을 안 주면, 실패한 레코드가 **어디에도 남지 않고 사라집니다.** 커넥터는 `RUNNING` 이고, 랙은 0 이고, 알람은 안 울립니다.
> 이 코스가 계속 말하는 "조용한 유실"의 Connect 판입니다.
> **원칙**: `errors.tolerance=all` 을 쓸 거면 **반드시** DLQ 토픽을 함께 설정하고, DLQ 토픽에 메시지가 들어오면 알람을 겁니다. `errors.log.enable=true` 도 함께 켜서 워커 로그에도 남기세요.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `errors.tolerance` | `none` | `none` = 즉시 태스크 실패 / `all` = 무시하고 진행 |
| `errors.log.enable` | `false` | 워커 로그에 에러 기록 |
| `errors.log.include.messages` | `false` | 로그에 **레코드 내용**까지 (개인정보 주의) |
| `errors.deadletterqueue.topic.name` | 없음 | DLQ 토픽 (**싱크 커넥터만 지원**) |
| `errors.deadletterqueue.context.headers.enable` | `false` | 실패 맥락을 헤더로 |
| `errors.retry.timeout` | `0` | 재시도 총 시간(ms). `-1` 은 무한 |
| `errors.retry.delay.max.ms` | `60000` | 재시도 간격 상한 |

**DLQ 는 싱크 커넥터 전용입니다.** 소스 커넥터에는 `errors.deadletterqueue.*` 가 없습니다. 소스 쪽 에러는 `errors.tolerance` 와 로그로만 다룹니다. 소스 시스템에서 이미 잘못된 데이터를 어디에 넣어 둘 "Kafka 이전 단계"가 없기 때문입니다.

---

## 12-10. JDBC 소스/싱크 — 설정과 주의점 (선택 실습)

JDBC 커넥터는 기본 이미지에 없습니다. 설치하려면 이렇게 합니다.

```bash
docker exec kafka-connect confluent-hub install --no-prompt \
  confluentinc/kafka-connect-jdbc:10.7.6
docker exec kafka-connect bash -c 'curl -sL -o /usr/share/confluent-hub-components/confluentinc-kafka-connect-jdbc/lib/mysql-connector-j-8.3.0.jar \
  https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar'
docker restart kafka-connect
```

**설치는 선택입니다.** 이 절의 목적은 **설정 형태와 함정을 이해하는 것**이고, 실행 없이도 됩니다.

### JDBC 소스 설정

```json
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "tasks.max": "1",
  "connection.url": "jdbc:mysql://mysql:3306/shop",
  "connection.user": "learner",
  "connection.password": "learn1234",
  "table.whitelist": "orders",
  "mode": "timestamp+incrementing",
  "incrementing.column.name": "order_id",
  "timestamp.column.name": "updated_at",
  "timestamp.delay.interval.ms": "5000",
  "poll.interval.ms": "5000",
  "topic.prefix": "mysql-",
  "transforms": "mkKey,extractKey",
  "transforms.mkKey.type": "org.apache.kafka.connect.transforms.ValueToKey",
  "transforms.mkKey.fields": "order_id",
  "transforms.extractKey.type": "org.apache.kafka.connect.transforms.ExtractField$Key",
  "transforms.extractKey.field": "order_id"
}
```

### 네 가지 `mode` 비교

| mode | 쿼리 | 감지 가능 | 놓치는 것 |
|---|---|---|---|
| `bulk` | `SELECT * FROM t` | — (매번 전체) | 없음. 대신 **매번 전부 다시 보냄** |
| `incrementing` | `WHERE id > ?` | INSERT | **UPDATE, DELETE** |
| `timestamp` | `WHERE ts > ?` | INSERT, UPDATE | DELETE, **동일 타임스탬프** |
| `timestamp+incrementing` | `WHERE ts > ? OR (ts = ? AND id > ?)` | INSERT, UPDATE | DELETE |

> ⚠️ **함정 — `mode=timestamp` 는 같은 타임스탬프의 행을 놓칩니다**
> `timestamp` 모드의 쿼리는 **`WHERE updated_at > ?`** 입니다. 등호가 없습니다.
> 트랜잭션 하나가 3행을 동시에 커밋하면 세 행의 `updated_at` 이 **밀리초 단위로 같습니다.** 커넥터가 그중 첫 행을 읽고 오프셋을 `2024-03-11 10:22:00.123` 으로 저장하면, 다음 폴링의 `WHERE updated_at > '2024-03-11 10:22:00.123'` 은 **나머지 두 행을 건너뜁니다.**
> 에러는 없습니다. 랙도 0 입니다. 며칠 뒤 "DB 에는 있는데 Kafka 에는 없는 주문"이 발견됩니다.
> **해결 두 가지**
> 1. **`mode=timestamp+incrementing`** — `WHERE ts > ? OR (ts = ? AND id > ?)`. 같은 타임스탬프 안에서는 PK 로 순서를 매기므로 안 놓칩니다. **이게 정답입니다.**
> 2. `timestamp.delay.interval.ms` 를 충분히(5000ms 이상) 준다 — 커넥터가 "지금보다 5초 이전"까지만 읽게 해서, 트랜잭션이 다 커밋될 시간을 벌어 줍니다. 1번과 **함께** 씁니다.

> ⚠️ **함정 — JDBC 소스는 DELETE 를 절대 감지하지 못합니다**
> 어느 모드든 `SELECT` 로 폴링하므로, 사라진 행은 **조회 결과에 안 나올 뿐** 이벤트가 생기지 않습니다. Kafka 쪽 데이터는 삭제된 주문을 계속 갖고 있습니다.
> **해결**: ① 소프트 삭제(`deleted_at` 컬럼)로 바꾸고 UPDATE 로 감지하거나, ② **CDC(Debezium)** 를 씁니다. Debezium 은 DB 의 binlog / WAL 을 읽으므로 DELETE 도 정확히 잡고, 폴링이 아니라서 지연도 훨씬 짧습니다.
> **JDBC 소스를 쓸지 CDC 를 쓸지는 "DELETE 를 알아야 하는가"로 결정하면 대개 맞습니다.**

### JDBC 싱크 설정

```json
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "tasks.max": "2",
  "topics": "orders",
  "connection.url": "jdbc:mysql://mysql:3306/warehouse",
  "insert.mode": "upsert",
  "pk.mode": "record_key",
  "pk.fields": "order_id",
  "auto.create": "false",
  "auto.evolve": "false",
  "batch.size": "500",
  "errors.tolerance": "all",
  "errors.deadletterqueue.topic.name": "dlq",
  "errors.deadletterqueue.context.headers.enable": "true"
}
```

- `insert.mode=upsert` + `pk.mode=record_key` 조합이 **멱등 싱크**를 만듭니다. 같은 메시지를 두 번 받아도 결과가 같습니다. Step 07 의 exactly-once 논의가 실무에서 이렇게 착지합니다.
- `auto.create` / `auto.evolve` 는 **운영에서 `false`** 를 권합니다. `true` 면 커넥터가 DB 스키마를 마음대로 바꿉니다. 스키마 변경은 DBA 절차를 거쳐야 합니다.

---

## 12-11. 정리 (실습 마무리)

커넥터를 전부 지우고, 이 스텝에서 만든 토픽과 컨슈머 그룹을 정리합니다.

```bash
for c in $(curl -s localhost:8083/connectors | jq -r '.[]'); do
  curl -s -X DELETE localhost:8083/connectors/$c -w "$c deleted %{http_code}\n"
done
```

**결과**

```
dlq-sink deleted 204
file-sink deleted 204
file-source deleted 204
file-source-smt deleted 204
```

```bash
kt --list | grep -E '^s12_' | xargs -I{} docker exec kafka-1 \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --delete --topic {}
kt --list | grep -E '^s12_' || echo "s12_ 토픽 없음"
```

**결과**

```
s12_ 토픽 없음
```

싱크 커넥터가 남긴 컨슈머 그룹은 **커넥터를 지워도 안 없어집니다**(함정 A 의 연장선입니다).

```bash
kcg --list
```

**결과**

```
connect-file-sink
connect-dlq-sink
```

```bash
kcg --delete --group connect-file-sink --group connect-dlq-sink
kcg --list || echo "그룹 없음"
```

**결과**

```
Deletion of requested consumer groups ('connect-file-sink', 'connect-dlq-sink') was successful.
그룹 없음
```

내부 토픽 3종(`connect-configs`/`connect-offsets`/`connect-status`)은 **지우지 마세요.** Connect 워커를 계속 쓸 것이라면 남겨 둡니다. 완전히 초기화하려면 워커를 내리고 세 토픽을 지운 뒤 다시 띄웁니다.

```bash
# Connect 를 완전히 초기화하고 싶을 때만
docker compose stop kafka-connect
kt --delete --topic connect-configs
kt --delete --topic connect-offsets
kt --delete --topic connect-status
docker compose --profile connect up -d
```

Connect 워커 자체를 내리려면:

```bash
docker compose stop kafka-connect
```

Step 13 은 Connect 가 필요 없으므로 내려도 됩니다. 메모리가 넉넉하면 켜 둬도 무방합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Connect 의 가치 | 오프셋·재시작·스케일아웃·SMT·DLQ 를 **다시 안 만듦** |
| 커넥터 vs 태스크 | 커넥터는 계획, **태스크가 실제로 데이터를 옮김** |
| 분산 모드 | 워커 수와 무관. 설정·오프셋·상태를 **토픽에 저장** |
| `connect-configs` | **파티션 1 강제.** 설정 변경의 전역 순서 보장 |
| 내부 토픽 정책 | 셋 다 **compact.** delete 면 retention 후 커넥터가 증발 |
| REST | `PUT /config` 는 멱등 → CI/CD 는 항상 이걸 |
| `pause` vs `stop` | pause 는 태스크 유지, stop 은 태스크 내림(3.5+) |
| **함정 A** | **소스는 `connect-offsets` 토픽, 싱크는 컨슈머 그룹 `connect-<이름>`** |
| **함정 B** | **커넥터 삭제해도 오프셋 잔존 → 같은 이름으로 재생성하면 이어 읽음** |
| **함정 C** | **`connector.state=RUNNING` + `tasks[].state=FAILED` 가 가능. 태스크까지 봐야 함** |
| SMT | 체인 순서 = `transforms` 의 쉼표 순서. 2~3개까지만 |
| SMT 제약 | `Struct`/`Map` 에만 필드 조작 가능. 문자열은 `HoistField` 로 감싸야 |
| DLQ | **싱크 전용.** `context.headers.enable=true` 없으면 원인 추적 불가 |
| `errors.tolerance=all` | DLQ 없이 쓰면 **조용한 유실** |
| JDBC `mode=timestamp` | **동일 타임스탬프 행을 건너뜀** → `timestamp+incrementing` |
| JDBC 소스 | **DELETE 를 절대 못 잡음** → 소프트 삭제 또는 CDC |

---

## 연습문제

`exercise.sh` 에 6문제가 있습니다. 정답은 `solution.sh`.

1. FileStreamSource 커넥터를 등록하고, 커넥터와 **모든 태스크**의 상태를 한 줄로 뽑는 명령 작성하기
2. 커넥터를 지웠다 같은 이름으로 다시 만들었는데 데이터가 안 들어옵니다. 원인을 확인하는 명령과 해결 명령 세 줄 쓰기
3. `connect-configs` 의 `cleanup.policy` 와 파티션 수를 확인하고, 왜 그래야 하는지 답하기
4. 싱크 커넥터의 랙을 확인하기 (어느 명령을 씁니까? 소스 커넥터였다면?)
5. SMT 로 `customer_id` 를 키로 승격시켜 파티션이 분산되게 만들기
6. 깨진 레코드를 DLQ 로 보내고, 원본이 어느 토픽·파티션·오프셋에서 왔는지 헤더로 확인하기

---

## 다음 단계

Connect 는 **데이터를 옮깁니다.** 필드를 하나 붙이고 이름을 바꾸는 정도의 변환은 SMT 로 되지만, 그 이상은 안 됩니다. 조인도 집계도 윈도우도 없습니다.

"고객별 최근 1분간 주문 금액 합계" 같은 것을 Kafka 안에서 계산하려면 다른 도구가 필요합니다. Kafka Streams 입니다. 그리고 Streams 는 Connect 보다 훨씬 조용하게 **내부 토픽을 자동으로 만듭니다.**

→ [Step 13 — Kafka Streams](../step-13-streams/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 로 12-0 ~ 12-11 전 과정을 재현하고, `exercise.sh` 의 6문제를 직접 푼 뒤, `solution.sh` 로 대조합니다. 세 파일 모두 상단에 `CONNECT=localhost:8083` 과 `K()` 헬퍼를 정의하며, **`jq` 가 설치돼 있어야** 정상 동작합니다.

### practice.sh

본문의 모든 `curl` 과 CLI 명령을 절 번호 주석과 함께 담은 실행 스크립트입니다.

- 맨 앞 `wait_connect()` 함수가 `curl -sf localhost:8083/` 를 2초 간격으로 최대 60회 폴링합니다. Connect 워커는 기동에 40~70초가 걸리므로, 이 대기 없이 곧바로 커넥터를 등록하면 `Connection refused` 가 납니다. `docker compose --profile connect up -d` 직후에 스크립트를 돌려도 되게 만든 장치입니다.
- `[12-7-B]` 구간이 이 스크립트의 핵심입니다. `DELETE /connectors/file-source` → 파일 내용 교체 → `PUT /config` 로 재생성 → `kafka-get-offsets.sh` 로 **오프셋이 4에서 안 늘어난 것**을 확인하고, 그다음 `stop` → `DELETE /offsets` → `resume` 순서로 4 → 6 이 되는 것까지 한 번에 보여 줍니다. **이 구간만은 순서를 건너뛰지 마세요.** 중간을 생략하면 함정이 재현되지 않습니다.
- `[12-7-C]` 는 싱크 커넥터의 `file` 을 `/proc/nope/orders-out.txt` 로 일부러 바꿔 태스크를 죽입니다. `/proc` 아래는 컨테이너 안에서도 새 파일을 만들 수 없어 확실히 `FileNotFoundException` 이 납니다. 그 뒤 `all_states()` 헬퍼로 `RUNNING,FAILED` 를 출력하고, 설정을 되돌린 뒤 `restart?includeTasks=true&onlyFailed=true` 로 살립니다.
- `[12-8]` 의 SMT 체인은 `parse,mkKey,extractKey,addTs,route` 5단계입니다. 맨 앞 `HoistField$Value` 가 없으면 `Only Struct objects supported` 로 죽으므로 **순서를 바꾸지 마세요.** 이 구간은 별도의 `orders-smt.txt` 파일과 `s12_smt.s12_file` 토픽을 씁니다.
- `[12-9]` 는 DLQ 를 **일부러 두 번** 설정합니다. 첫 번째는 DLQ 없이 등록해 `RUNNING,FAILED` 를 보여 주고, 두 번째에 `errors.tolerance=all` + DLQ 3종 설정을 붙여 `RUNNING,RUNNING` 이 되는 것과 정상 2건이 통과하는 것을 대비시킵니다.
- 마지막 `[12-11]` 이 커넥터 전체 삭제 → `s12_` 토픽 삭제 → **컨슈머 그룹 `connect-*` 삭제**까지 합니다. 세 번째가 중요합니다. 커넥터를 지워도 싱크가 만든 그룹은 남기 때문입니다.
- `[12-10]` 의 JDBC 구간은 전부 주석 처리돼 있습니다. `confluent-hub install` 은 네트워크를 타고 수백 MB 를 받으므로 기본 실행에서 제외했습니다. 해 보고 싶으면 주석을 푸세요.

```bash file="./practice.sh"
```

### exercise.sh

6문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었고, 채점에 필요한 사전 준비(토픽 생성, 데이터 투입)는 문제지 쪽에서 미리 해 줍니다.

- **문제 1·5·6** 은 커넥터를 **직접 설계**하는 문제이고, **문제 2·3·4** 는 이미 만들어진 상태를 **관찰·진단**하는 문제입니다.
- 문제 2 는 파일이 `q2-source` 커넥터를 만들고 → 데이터를 흘리고 → 삭제하고 → 파일을 갈아엎고 → 같은 이름으로 재생성하는 데까지 **미리 해 둡니다.** 여러분이 할 일은 "왜 데이터가 안 나오는지 확인하는 명령"과 "고치는 명령"을 쓰는 것입니다. 함정 B 를 손으로 다시 겪게 만든 구성입니다.
- 문제 4 는 답이 **소스냐 싱크냐에 따라 완전히 다릅니다.** 싱크는 `kcg --describe --group connect-q4-sink`, 소스는 `curl .../offsets` 입니다. 이 두 갈래를 스스로 구분하는 것이 문제의 전부입니다.
- 문제 5 의 입력 파일 `q5.txt` 는 `customer_id` 가 `C001`~`C003` 으로 섞여 있습니다. SMT 없이 넣으면 전부 한 파티션에 몰리고, 제대로 만들면 파티션 0/1/2 에 흩어집니다. **`kafka-get-offsets.sh` 출력의 세 줄이 전부 0 이 아니어야** 정답입니다.
- 문제 6 은 `q6-src` 토픽에 정상 JSON 2건과 깨진 문자열 2건(`OOPS`, `{"unclosed":`)이 이미 들어 있습니다. DLQ 토픽에 **정확히 2건**이 들어오고, 각 건의 `__connect.errors.offset` 헤더가 원본 위치를 가리키는지 확인하면 됩니다.
- 파일 끝의 정리 블록은 `q1-source`~`q6-sink` 커넥터와 `s12ex_` 토픽, `connect-q*` 컨슈머 그룹을 전부 지웁니다. 문제를 안 풀고 정리만 돌려도 에러 없이 끝나도록 `|| true` 를 붙여 두었습니다.

```bash file="./exercise.sh"
```

### solution.sh

6문제의 정답 명령과, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 의 핵심은 `jq -r '[.connector.state] + [.tasks[].state] | join(",")'` 한 줄입니다. `.connector.state` 만 보면 함정 C 를 통째로 놓친다는 설명을 붙였고, 전 커넥터를 순회하며 `FAILED` 를 찾는 운영용 루프까지 함께 제시합니다.
- **정답 2** 는 세 줄입니다. `GET /offsets` 로 `position` 이 남아 있는 것을 확인하고, `PUT /stop` → `DELETE /offsets` → `PUT /resume`. 주석에서 **왜 `stop` 이 먼저여야 하는지**(RUNNING 상태에서는 `DELETE /offsets` 가 `409 Conflict` 를 냅니다) 와, 운영에서는 오프셋 삭제보다 **커넥터 이름 버저닝(`-v2`)이 더 안전한 이유**를 설명합니다.
- **정답 3** 은 `cleanup.policy=compact` / `PartitionCount: 1` 이고, 이유는 각각 "최신 설정만 필요하며 delete 면 retention 후 커넥터가 증발" / "설정 변경 이벤트의 전역 순서 보장"입니다. 파티션을 늘렸을 때 워커가 내는 `is required to have a single partition` 예외 전문도 주석에 넣었습니다.
- **정답 4** 는 표로 답합니다. 싱크는 `kcg --describe --group connect-<이름>` 으로 LAG 컬럼을, 소스는 `curl .../offsets` 로 소스 시스템 위치를 봅니다. **소스 커넥터에는 "랙"이라는 개념 자체가 없다**는 것이 이 문제의 진짜 답입니다. 소스가 얼마나 뒤처졌는지는 소스 시스템 쪽에서 재야 합니다.
- **정답 5** 는 `HoistField$Value` → `ValueToKey` → `ExtractField$Key` 3단 체인입니다. 첫 단계를 빼면 나오는 `DataException: Only Struct objects supported` 를 주석에 그대로 실어 두었습니다. FileStream 소스가 문자열을 내보내기 때문에 생기는, **파일 소스 특유의** 문제라는 단서도 답니다.
- **정답 6** 은 `errors.tolerance=all` + DLQ 토픽 + `context.headers.enable=true` 3종 세트이고, 검증은 `kafka-console-consumer.sh --property print.headers=true` 입니다. 마지막에 **헤더 없이 DLQ 만 켰을 때의 출력**(원본 바이트만 덜렁 있는 상태)을 나란히 보여 주며, `context.headers.enable` 이 없는 DLQ 는 "쓰레기통"일 뿐 "장애 조사 자료"가 아니라는 결론으로 마무리합니다.

```bash file="./solution.sh"
```
