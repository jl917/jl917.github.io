# Step 01 — 클러스터 기동과 첫 메시지

> **학습 목표**
> - KRaft 3브로커 클러스터를 기동하고 세 브로커가 서로를 인식하는지 확인한다
> - `kafka-topics.sh` 로 토픽을 만들고 `--describe` 출력의 **모든 컬럼을 해독한다**
> - 콘솔 프로듀서/컨슈머로 메시지를 한 번 왕복시키고, 키·파티션·오프셋을 눈으로 확인한다
> - `--from-beginning` 을 빠뜨렸을 때 **아무 에러 없이 아무것도 안 읽히는 상황을 직접 재현한다**
> - `auto.create.topics.enable=false` 덕분에 오타 토픽이 어떤 에러로 드러나는지 확인한다
> - 코스 전체에서 쓸 토픽 4개(`orders`/`payments`/`order-events`/`dlq`)를 만들고 kafka-ui 로 대조한다
>
> **선행 스텝**: 없음 (환경 구성은 [Docker 실습 환경](../docker/) 참조)
> **예상 소요**: 90분

---

## 1-0. 실습 준비 — 클러스터를 띄운다

이 코스의 모든 실습은 [`docker/docker-compose.yml`](../docker/) 위에서 돌아갑니다. 아직 안 읽었다면 리스너 절만이라도 훑고 오세요. "왜 브로커에 연결이 안 되지?" 의 90%가 거기 있습니다.

```bash
cd kafka/docker
docker compose up -d
```

**결과**

```
[+] Running 5/5
 ✔ Network kafka_default  Created                                          0.1s
 ✔ Container kafka-1      Started                                          0.9s
 ✔ Container kafka-2      Started                                          0.9s
 ✔ Container kafka-3      Started                                          0.9s
 ✔ Container kafka-ui     Started                                          1.2s
```

컨테이너가 떴다고 브로커가 **준비된 것은 아닙니다.** KRaft 는 첫 기동 시 컨트롤러 쿼럼(3표 중 2표)을 구성하고 메타데이터 로그를 초기화하느라 20~40초를 씁니다. `healthcheck` 가 그것을 대신 기다려 주므로 상태를 확인합니다.

```bash
docker compose ps
```

**결과**

```
NAME       IMAGE                            STATUS                    PORTS
kafka-1    apache/kafka:3.7.1               Up 52 seconds (healthy)   0.0.0.0:19092->19092/tcp, 0.0.0.0:19999->9999/tcp
kafka-2    apache/kafka:3.7.1               Up 52 seconds (healthy)   0.0.0.0:29092->29092/tcp, 0.0.0.0:29999->9999/tcp
kafka-3    apache/kafka:3.7.1               Up 52 seconds (healthy)   0.0.0.0:39092->39092/tcp, 0.0.0.0:39999->9999/tcp
kafka-ui   provectuslabs/kafka-ui:v0.7.2    Up 21 seconds             0.0.0.0:8080->8080/tcp
```

세 브로커가 전부 `(healthy)` 여야 다음으로 갑니다. `(health: starting)` 이면 30초 더 기다리세요. `(unhealthy)` 로 굳었다면 로그를 봅니다.

```bash
docker compose logs kafka-1 | tail -20
```

정상 기동의 마지막 줄은 이렇게 끝납니다.

```
[2024-03-11 10:20:44,102] INFO [BrokerServer id=1] Transition from STARTING to STARTED (kafka.server.BrokerServer)
[2024-03-11 10:20:44,118] INFO Kafka version: 3.7.1 (org.apache.kafka.common.utils.AppInfoParser)
[2024-03-11 10:20:44,118] INFO Kafka commitId: e2494e739ea8f0dfa1d3b1a3d9d80e6ef1f8bc3d (org.apache.kafka.common.utils.AppInfoParser)
[2024-03-11 10:20:44,119] INFO [KafkaRaftServer nodeId=1] Kafka Server started (kafka.server.KafkaRaftServer)
```

`Transition from STARTING to STARTED` 가 안 보이면 아직 기동 중이거나 쿼럼이 안 선 것입니다. 후자라면 `docker compose down -v && docker compose up -d` 로 초기화하세요.

---

## 1-1. 브로커 3대가 서로를 인식하는지 확인

컨테이너가 healthy 인 것과 **브로커 3대가 하나의 클러스터를 이루는 것**은 다른 문제입니다. 세 대가 각자 1대짜리 클러스터를 만들고 있어도 healthcheck 는 통과합니다. 실제로 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server kafka-1:9092 | grep -E '^kafka-[0-9]'
```

**결과**

```
kafka-1:9092 (id: 1 rack: null) -> (
kafka-2:9092 (id: 2 rack: null) -> (
kafka-3:9092 (id: 3 rack: null) -> (
```

**세 줄이 나와야 합니다.** 이 명령은 부트스트랩 서버 하나에 붙어서 클러스터 메타데이터를 받아 오고, 거기 들어 있는 **모든 브로커**에 각각 접속해 지원 API 버전을 물어봅니다. 즉 세 줄이 나왔다는 것은 "kafka-1 이 kafka-2, kafka-3 을 클러스터 멤버로 알고 있고, 실제로 그 주소로 접속도 된다"는 뜻입니다.

한 줄만 나온다면 `KAFKA_CONTROLLER_QUORUM_VOTERS` 가 브로커마다 다르거나, 데이터 볼륨이 서로 다른 Cluster ID 로 포맷되어 있는 것입니다.

KRaft 쿼럼 자체도 확인해 둡니다. Step 02 에서 이 출력을 자세히 뜯을 것이므로 지금은 "리더가 하나 있고 투표자가 셋"만 보면 됩니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server kafka-1:9092 describe --status
```

**결과**

```
ClusterId:              MkU3OEVBNTcwNTJENDM2Qk
LeaderId:               1
LeaderEpoch:            2
HighWatermark:          412
MaxFollowerLag:         0
MaxFollowerLagTimeMs:   0
CurrentVoters:          [1,2,3]
CurrentObservers:       []
```

`CurrentVoters: [1,2,3]` 이 핵심입니다. ZooKeeper 시절이라면 `zkCli.sh ls /brokers/ids` 를 쳤을 자리인데, KRaft 에서는 클러스터가 **자기 메타데이터를 자기 토픽에 저장**하므로 외부 도구 없이 자기 자신에게 물어봅니다.

---

## 1-2. 편의 별칭 등록

이 코스는 CLI 를 수백 번 호출합니다. `docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092` 는 66글자입니다. 별칭을 등록합니다.

```bash
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh      --bootstrap-server kafka-1:9092'
alias kcg='docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092'
alias kconf='docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh  --bootstrap-server kafka-1:9092'
alias kgo='docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka-1:9092'
```

동작을 확인합니다.

```bash
kt --list
```

**결과** (토픽이 아직 없으므로 빈 출력이 정상입니다)

```
```

빈 줄이 나왔다면 성공입니다. 여기서 에러가 나면 별칭이 아니라 클러스터 문제이니 1-1 로 돌아가세요.

> 💡 **실무 팁 — 별칭은 셸을 닫으면 사라집니다**
> 이 코스를 며칠에 걸쳐 진행한다면 `~/.zshrc` 나 `~/.bashrc` 에 네 줄을 넣어 두세요.
> 스크립트(`practice.sh`) 안에서는 alias 가 기본적으로 동작하지 않으므로, 실습 스크립트들은 alias 대신 함수를 씁니다.
> ```bash
> K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
> K kafka-topics.sh --bootstrap-server kafka-1:9092 --list
> ```
> 함수는 서브셸에서도 동작하고 인자를 그대로 넘길 수 있어 스크립트에 적합합니다.

프로듀서처럼 **표준 입력을 주고받는 명령은 `-i` 가 필요**해서 별칭으로 만들기 까다롭습니다. 그래서 프로듀서/컨슈머는 이 코스 내내 전체 형태로 씁니다.

---

## 1-3. 첫 토픽 만들기 — `orders`

실습 도메인의 중심 토픽인 `orders` 를 만듭니다. 옵션 하나하나가 나중 스텝의 복선이므로 전부 명시적으로 줍니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:9092 \
  --create --topic orders \
  --partitions 3 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2
```

**결과**

```
Created topic orders.
```

| 옵션 | 값 | 뜻 | 어느 스텝에서 다룹니까 |
|---|---|---|---|
| `--partitions` | 3 | 이 토픽을 몇 조각으로 나눌지. **컨슈머 병렬성의 상한** | Step 03, Step 05 |
| `--replication-factor` | 3 | 각 파티션의 복제본 수. 3이면 브로커 2대가 죽어도 데이터가 남음 | Step 08 |
| `retention.ms` | 604800000 | 7일(=7×24×60×60×1000). 이 시간이 지난 세그먼트를 삭제 | Step 09 |
| `min.insync.replicas` | 2 | `acks=all` 일 때 **최소 몇 개의 복제본이 받아야 성공**으로 칠지 | Step 08 |

`--replication-factor 3` 은 브로커가 3대이므로 가능한 최대값입니다. 브로커보다 큰 값을 주면 즉시 거절당합니다.

```bash
kt --create --topic bad-rf --partitions 1 --replication-factor 5
```

**결과**

```
Error while executing topic command : Unable to replicate the partition 5 time(s): The target replication factor of 5 cannot be reached because only 3 broker(s) are registered.
[2024-03-11 10:23:07,441] ERROR org.apache.kafka.common.errors.InvalidReplicationFactorException: Unable to replicate the partition 5 time(s): The target replication factor of 5 cannot be reached because only 3 broker(s) are registered.
 (kafka.admin.TopicCommand$)
```

이건 **좋은 에러**입니다. 즉시, 명확하게 실패합니다. 이 코스에서 다룰 진짜 문제들은 이렇게 친절하지 않습니다.

---

## 1-4. `--describe` 출력 완전 해독

Kafka 를 운영하면서 가장 많이 보게 될 출력입니다. 지금 완전히 이해해 두면 Step 08 의 장애 실습이 훨씬 쉬워집니다.

```bash
kt --describe --topic orders
```

**결과**

```
Topic: orders	TopicId: qP3mZ8kLQ1u7nR2vXwYtBA	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: orders	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: orders	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
```

### 첫 줄 — 토픽 전체 정보

| 필드 | 값 | 의미 |
|---|---|---|
| `Topic` | `orders` | 토픽 이름 |
| `TopicId` | `qP3mZ8kLQ1u7nR2vXwYtBA` | **base64 로 인코딩된 UUID.** 토픽을 지우고 같은 이름으로 다시 만들면 **이 값이 바뀝니다.** 브로커는 이름이 아니라 이 ID 로 토픽을 식별합니다 |
| `PartitionCount` | 3 | 파티션 개수. **늘릴 수만 있습니다**(Step 03) |
| `ReplicationFactor` | 3 | 파티션당 복제본 수. 정확히는 "파티션 0의 복제본 수"를 대표로 보여 줍니다 |
| `Configs` | `min.insync.replicas=2,...` | **브로커 기본값과 다르게 지정된 설정만** 나옵니다. `retention.ms` 와 `min.insync.replicas` 는 우리가 `--config` 로 줬고, `segment.bytes=1048576` 는 docker-compose 의 브로커 설정이 토픽 레벨로 내려온 것입니다 |

`TopicId` 가 중요한 이유가 있습니다. 토픽을 삭제하고 **같은 이름으로 즉시 재생성**하면, 이름은 같지만 ID 가 다른 완전히 새 토픽입니다. 그런데 아직 옛 토픽을 캐시하고 있는 클라이언트는 잠깐 `UNKNOWN_TOPIC_ID` 를 받습니다. Step 03 의 토픽 삭제 절에서 다시 만납니다.

### 둘째 줄부터 — 파티션별 정보

들여쓰기는 **탭 한 칸**입니다. 파티션마다 한 줄입니다.

```
	Topic: orders	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
```

| 필드 | 값 | 의미 |
|---|---|---|
| `Partition` | 1 | 파티션 번호. **0부터 시작**하며 토픽 안에서만 유효합니다 |
| `Leader` | 2 | 이 파티션의 리더 브로커 ID. **모든 읽기·쓰기는 리더가 처리합니다.** 팔로워는 복제만 합니다 |
| `Replicas` | 2,3,1 | 복제본을 가진 브로커 목록. **맨 앞이 "선호 리더(preferred leader)"** 입니다 |
| `Isr` | 2,3,1 | **In-Sync Replicas.** 리더를 충분히 따라잡은 복제본 목록 |

여기서 셋을 꼭 구분하세요.

- **`Replicas` 는 "복제본이 있어야 할 곳"** 의 목록입니다. 브로커가 죽어도 이 목록은 안 변합니다.
- **`Isr` 은 "지금 실제로 따라잡고 있는 곳"** 의 목록입니다. 브로커가 죽으면 여기서 빠집니다.
- **`Leader` 는 항상 `Isr` 안에 있어야** 합니다. 리더가 죽으면 `Isr` 의 나머지 중 하나가 새 리더가 됩니다.

`Replicas` 와 `Isr` 이 다르면 문제 신호입니다. `Replicas: 2,3,1  Isr: 2,3` 이면 브로커 1이 뒤처졌거나 죽은 것입니다. **이 상태에서 `min.insync.replicas=2` 라면 아직 쓰기가 됩니다.** 하나 더 빠져 `Isr: 2` 가 되는 순간 `acks=all` 쓰기가 전부 실패합니다. Step 08 에서 브로커를 실제로 죽여 이 전이를 관찰합니다.

`Replicas` 순서에도 의미가 있습니다. Kafka 는 파티션을 만들 때 브로커에 **라운드로빈으로 리더를 분산**시킵니다. 파티션 0의 선호 리더가 1, 1번은 2, 2번은 3인 이유입니다. 이 배치 덕분에 세 브로커가 각각 파티션 하나씩의 리더를 맡아 부하가 균등해집니다. 브로커가 죽었다 살아나면 리더가 한쪽으로 쏠리는데, 이때 `kafka-leader-election.sh --election-type preferred` 로 `Replicas` 맨 앞 브로커에게 리더를 돌려줍니다(Step 14).

> 💡 **실무 팁 — 운영에서 가장 많이 치는 `--describe` 변형**
> ```bash
> kt --describe --under-replicated-partitions      # ISR 이 Replicas 보다 적은 파티션만
> kt --describe --unavailable-partitions           # 리더가 없는 파티션만 (진짜 장애)
> ```
> 정상이면 **아무것도 출력되지 않습니다.** 이 두 명령은 "출력이 없는 것이 좋은 것"인 대표적인 예입니다.
> 브로커 하나를 재시작한 뒤에 첫 번째 명령을 쳐서 출력이 사라질 때까지 기다리는 것이, 롤링 재시작의 기본 절차입니다(Step 14).

---

## 1-5. 첫 메시지 — 터미널 두 개가 필요합니다

이제 메시지를 한 번 왕복시킵니다. **터미널을 두 개 여세요.** 하나는 컨슈머(받는 쪽), 하나는 프로듀서(보내는 쪽)입니다.

### 터미널 A — 컨슈머를 먼저 켭니다

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic orders \
  --from-beginning
```

아무것도 출력되지 않고 **커서가 멈춰 있습니다.** 정상입니다. 컨슈머는 새 메시지를 기다리며 계속 폴링합니다. `Ctrl+C` 로 끄기 전까지 이 상태로 둡니다.

### 터미널 B — 프로듀서로 메시지를 보냅니다

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic orders
```

`>` 프롬프트가 뜨면 한 줄씩 칩니다. **엔터를 칠 때마다 한 건의 메시지가 전송됩니다.**

```
>{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
>{"order_id":"O-1002","customer_id":"C002","amount":15000,"status":"CREATED"}
>{"order_id":"O-1003","customer_id":"C003","amount":72000,"status":"CREATED"}
```

### 터미널 A 를 다시 봅니다

**결과**

```
{"order_id":"O-1002","customer_id":"C002","amount":15000,"status":"CREATED"}
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
{"order_id":"O-1003","customer_id":"C003","amount":72000,"status":"CREATED"}
```

메시지가 도착했습니다. 그런데 **순서가 다릅니다.** O-1002 가 O-1001 보다 먼저 나왔습니다.

이것은 버그가 아닙니다. 키 없이 보낸 메시지는 파티션에 흩어지고, **Kafka 는 파티션 안에서만 순서를 보장**하기 때문입니다. 컨슈머는 파티션 3개를 동시에 읽으며 도착한 순서대로 출력합니다. 이 코스에서 가장 자주 반복될 문장을 지금 외우세요.

> **Kafka 의 순서 보장은 파티션 단위입니다. 토픽 단위가 아닙니다.**

프로듀서 터미널은 `Ctrl+C`, 컨슈머 터미널도 `Ctrl+C` 로 끕니다. 컨슈머를 끄면 마지막에 한 줄이 나옵니다.

```
^CProcessed a total of 3 messages
```

---

## 1-6. 함정 ① — `--from-beginning` 을 빼면 아무 일도 일어나지 않는다

방금 실습에서 `--from-beginning` 을 붙였습니다. 빼면 어떻게 될까요. **터미널 A** 에서 다시 켭니다.

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic orders
```

**결과**

```
```

아무것도 안 나옵니다. 에러도 없습니다. 경고도 없습니다. 그냥 커서가 깜빡입니다. `orders` 토픽에는 방금 넣은 메시지 3건이 분명히 들어 있는데도요.

`Ctrl+C` 로 끄면 이렇게 나옵니다.

```
^CProcessed a total of 0 messages
```

> ⚠️ **함정 — 컨슈머의 기본 시작 위치는 `latest` 입니다**
> `kafka-console-consumer.sh` 는 실행할 때마다 `console-consumer-XXXXX` 라는 **임의의 새 컨슈머 그룹**을 만듭니다. 새 그룹이므로 커밋된 오프셋이 없고, 그럴 때 어디서부터 읽을지는 `auto.offset.reset` 이 정합니다. **기본값은 `latest`** — "지금 이 순간 이후에 들어오는 것부터"입니다.
> 즉 이미 저장된 메시지는 **의도적으로 건너뜁니다.** 데이터가 없는 게 아니라 안 읽는 것입니다.
> **해결**: 처음부터 읽으려면 `--from-beginning`(내부적으로 `auto.offset.reset=earliest`)을 줍니다.
> 이 함정이 지독한 이유는 **증상이 "아무 일도 안 일어남"** 이기 때문입니다. 토픽 이름을 의심하고, 방화벽을 의심하고, 프로듀서를 의심하다가 마지막에야 이 옵션을 떠올립니다. 신입 개발자의 첫날 반나절이 여기서 사라집니다.
> Step 06 에서 `auto.offset.reset` 의 세 가지 값(`earliest`/`latest`/`none`)과, **커밋된 오프셋이 있으면 이 설정이 아예 무시된다**는 더 큰 함정을 다룹니다.

이 상태에서 확인하는 법을 알아 두면 유용합니다. 컨슈머가 못 읽는 것인지, 데이터가 없는 것인지 구분합니다.

```bash
kgo --topic orders
```

**결과**

```
orders:0:1
orders:1:1
orders:2:1
```

`토픽:파티션:오프셋` 형식이며, 여기 나오는 숫자는 **다음에 쓰일 오프셋**(=현재 메시지 수)입니다. 세 파티션에 1건씩, 총 3건이 저장되어 있습니다. 데이터는 분명히 있습니다. 못 읽고 있을 뿐입니다.

---

## 1-7. 키 있는 메시지 — 파티션이 결정되는 순간

지금까지는 키 없이 보냈습니다. 실습 도메인의 `orders` 는 `customer_id` 를 키로 씁니다. 키를 주면 **같은 키가 항상 같은 파티션으로** 갑니다.

### 터미널 A — 키·파티션·오프셋을 전부 찍는 컨슈머

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic orders \
  --from-beginning \
  --property print.key=true \
  --property print.partition=true \
  --property print.offset=true \
  --property key.separator=' | '
```

### 터미널 B — 키를 붙여 보내는 프로듀서

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic orders \
  --property parse.key=true \
  --property key.separator=:
```

```
>C001:{"order_id":"O-1004","customer_id":"C001","amount":21000,"status":"CREATED"}
>C001:{"order_id":"O-1005","customer_id":"C001","amount":33000,"status":"PAID"}
>C006:{"order_id":"O-1006","customer_id":"C006","amount":58000,"status":"CREATED"}
>C002:{"order_id":"O-1007","customer_id":"C002","amount":12000,"status":"CREATED"}
```

### 터미널 A 결과

```
Partition:1	Offset:0	null | {"order_id":"O-1002","customer_id":"C002","amount":15000,"status":"CREATED"}
Partition:0	Offset:0	null | {"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
Partition:2	Offset:0	null | {"order_id":"O-1003","customer_id":"C003","amount":72000,"status":"CREATED"}
Partition:2	Offset:1	C001 | {"order_id":"O-1004","customer_id":"C001","amount":21000,"status":"CREATED"}
Partition:2	Offset:2	C001 | {"order_id":"O-1005","customer_id":"C001","amount":33000,"status":"PAID"}
Partition:1	Offset:1	C006 | {"order_id":"O-1006","customer_id":"C006","amount":58000,"status":"CREATED"}
Partition:0	Offset:1	C002 | {"order_id":"O-1007","customer_id":"C002","amount":12000,"status":"CREATED"}
```

읽어낼 것이 여럿입니다.

1. **1-5 에서 키 없이 보낸 3건은 키가 `null`** 입니다. `parse.key=true` 없이 보내면 줄 전체가 값이 되고 키는 `null` 입니다.
2. **`C001` 두 건이 모두 파티션 2** 로 갔습니다. 같은 키는 같은 파티션입니다. `C006` 은 1번, `C002` 는 0번입니다. 이 매핑은 `murmur2(key) % 파티션수` 로 결정되며 **우연이 아니라 계산된 결과**입니다. Step 03 에서 실제로 계산해 봅니다.
3. **오프셋은 파티션마다 따로 0부터** 셉니다. 파티션 2에는 오프셋 0, 1, 2 가 있고 파티션 0에도 오프셋 0, 1 이 따로 있습니다. "오프셋 1번 메시지"라는 말은 파티션을 빼면 의미가 없습니다.
4. 출력 순서는 여전히 뒤죽박죽이지만, **같은 파티션 안에서는 오프셋이 반드시 증가**합니다. 파티션 2의 C001 두 건은 보낸 순서 그대로 0→1→2 입니다.

이것이 Kafka 의 순서 보장 모델 전부입니다. **"같은 키 → 같은 파티션 → 그 안에서는 순서 보장."** 주문 상태 변경(CREATED → PAID → SHIPPED)이 뒤집히면 안 되므로 `order_id` 를 키로 쓰는 것이고, 고객별 이벤트 순서를 지키려면 `customer_id` 를 키로 쓰는 것입니다.

| `--property` | 프로듀서/컨슈머 | 하는 일 | 기본값 |
|---|---|---|---|
| `parse.key=true` | 프로듀서 | 입력 줄을 키와 값으로 쪼갬 | `false` |
| `key.separator=:` | 프로듀서 | 쪼갤 구분자 | `\t` (탭) |
| `print.key=true` | 컨슈머 | 키를 출력 | `false` |
| `print.partition=true` | 컨슈머 | 파티션 번호를 출력 | `false` |
| `print.offset=true` | 컨슈머 | 오프셋을 출력 | `false` |
| `print.timestamp=true` | 컨슈머 | `CreateTime:1710120121113` 형태로 출력 | `false` |
| `key.separator=' \| '` | 컨슈머 | 키와 값 사이 구분자 | `\t` (탭) |

> ⚠️ **함정 — `key.separator` 의 기본값은 탭입니다**
> 프로듀서에서 `parse.key=true` 만 주고 `key.separator` 를 안 주면 **탭으로 쪼갭니다.** 여러분이 `C001:{"..."}` 를 치면 탭이 없으므로 이렇게 죽습니다.
> ```
> org.apache.kafka.common.KafkaException: No key found on line 1: C001:{"order_id":"O-1004",...}
> ```
> 다행히 이건 **에러가 나는** 함정입니다. 문제는 반대 방향입니다. 값 JSON 안에 `:` 가 잔뜩 들어 있는데 `key.separator=:` 를 쓰면, 콘솔 프로듀서는 **첫 번째 `:` 에서만 쪼갭니다.** 그래서 위 예제가 정상 동작합니다. 만약 `key.separator=,` 로 두면 `C001` 이 키가 아니라 JSON 중간이 잘려 나가고, **에러 없이 이상한 키의 메시지가 저장됩니다.**
> **해결**: 키에 절대 안 나올 문자를 구분자로 고르세요. 이 코스는 `:` 를 쓰되 키를 항상 `C001`/`O-1001` 형태로 유지합니다.

---

## 1-8. 함정 ② — 오타 난 토픽 이름

컨슈머를 오타 난 토픽 이름으로 켜 봅니다.

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 \
  --topic oders \
  --from-beginning
```

**결과**

```
[2024-03-11 10:31:12,884] WARN [Consumer clientId=console-consumer, groupId=console-consumer-41207] Error while fetching metadata with correlation id 2 : {oders=UNKNOWN_TOPIC_OR_PARTITION} (org.apache.clients.NetworkClient)
[2024-03-11 10:31:13,001] ERROR Error processing message, terminating consumer process:  (kafka.tools.ConsoleConsumer$)
org.apache.kafka.common.errors.TimeoutException: Topic oders not present in metadata after 60000 ms.
Processed a total of 0 messages
```

`UNKNOWN_TOPIC_OR_PARTITION` 경고가 반복되다가 60초 뒤 타임아웃으로 죽습니다. **시끄럽고 명확합니다. 좋은 일입니다.**

> ⚠️ **함정 — `auto.create.topics.enable=true` 였다면 오타 토픽이 조용히 생깁니다**
> Kafka 의 **브로커 기본값은 `true`** 입니다. 이 코스의 docker-compose 는 일부러 `false` 로 바꿔 두었습니다.
> `true` 인 클러스터에서 위 명령을 치면 어떻게 될까요. 브로커가 `oders` 라는 토픽을 **그 자리에서 만듭니다.** `num.partitions` 와 `default.replication.factor` 기본값으로요. 컨슈머는 에러 없이 붙고, 빈 토픽이니 아무것도 안 읽히고, 여러분은 "왜 메시지가 안 오지?" 를 한참 헤맵니다.
> 더 나쁜 경우는 **프로듀서 쪽 오타**입니다. 프로듀서가 `oders` 로 보내면 그 토픽이 생기고 메시지가 거기 쌓입니다. **전송은 성공합니다.** 컨슈머는 `orders` 를 보고 있으니 아무것도 못 받습니다. 두 팀이 서로 "우리는 잘 보냈다" / "우리는 못 받았다" 로 하루를 씁니다.
> **해결**: 운영 클러스터에서도 `auto.create.topics.enable=false` 를 권장합니다. 토픽은 코드나 IaC 로 명시적으로 만드세요.
> 실제로 그런 클러스터를 물려받았다면, 이렇게 찾아냅니다.
> ```bash
> kt --list | grep -viE '^(orders|payments|order-events|dlq|__)'
> ```
> 정상 목록에 없는 이름이 나오면 대개 누군가의 오타입니다.

기존 클러스터의 설정을 확인하는 법입니다.

```bash
kconf --describe --entity-type brokers --entity-name 1 --all \
  | grep auto.create.topics.enable
```

**결과**

```
  auto.create.topics.enable=false sensitive=false synonyms={STATIC_BROKER_CONFIG:auto.create.topics.enable=false, DEFAULT_CONFIG:auto.create.topics.enable=true}
```

`synonyms` 가 설정의 출처를 알려 줍니다. `DEFAULT_CONFIG` 는 Kafka 의 내장 기본값(`true`), `STATIC_BROKER_CONFIG` 는 우리가 docker-compose 로 넣은 값(`false`)이고 **뒤쪽일수록 낮은 우선순위**입니다. Step 03 에서 이 `synonyms` 출력을 본격적으로 해독합니다.

---

## 1-9. 함정 ③ — 콘솔 프로듀서의 acks

프로듀서가 "보냈다"고 하는 시점이 정확히 언제인지는 `acks` 가 정합니다. 세 값이 있습니다.

| `acks` | 성공으로 치는 시점 | 유실 위험 |
|---|---|---|
| `0` | **보내자마자.** 브로커 응답을 안 기다림 | 브로커가 죽어 있어도 성공. 최대 |
| `1` | **리더가 자기 로그에 썼을 때.** 복제는 안 기다림 | 리더가 복제 전에 죽으면 유실 |
| `all` (=`-1`) | **ISR 의 모든 복제본이 썼을 때** | `min.insync.replicas` 와 조합하면 최소 |

여기서 정확히 짚어야 할 것이 있습니다.

> ⚠️ **함정 — "콘솔 프로듀서는 acks=1" 이라는 오래된 설명**
> 인터넷 문서 상당수가 콘솔 프로듀서 기본 acks 를 `1` 이라고 적어 두었습니다. **Kafka 3.7 에서는 사실이 아닙니다.**
> `kafka-console-producer.sh` 의 `--request-required-acks` 기본값은 **`-1`(=`all`)** 입니다. 즉 이 코스의 콘솔 프로듀서 실습은 **처음부터 가장 안전한 설정**으로 돌고 있었습니다.
> 반면 **Java 프로듀서 API 의 `acks` 기본값**은 Kafka 3.0 부터 `all` 입니다(2.x 까지는 `1` 이었습니다). 즉 "기본값이 1"은 2.x 시절의 지식입니다. 버전을 명시하지 않은 튜닝 가이드를 그대로 믿으면 안 되는 이유입니다.
> **왜 위험한가**: "기본이 1이니까 all 로 바꿔야지" 하고 `acks=all` 만 설정하고 안심하는 경우입니다. `acks=all` 은 **ISR 전체**가 받으면 성공인데, ISR 이 1개로 쪼그라들어 있으면 **복제본 1개짜리 성공**을 성공으로 칩니다. `min.insync.replicas=2` 를 함께 걸어야 비로소 의미가 있습니다(Step 08).

콘솔 프로듀서에서 acks 를 명시적으로 바꾸는 방법은 두 가지입니다.

```bash
# 방법 1 — 전용 옵션 (콘솔 프로듀서에만 있음)
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --request-required-acks 1

# 방법 2 — 임의의 프로듀서 설정을 넘김 (모든 설정에 사용 가능)
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --producer-property acks=1 \
  --producer-property compression.type=lz4 \
  --producer-property linger.ms=100
```

**방법 2 를 기억하세요.** `--producer-property` 는 Java 프로듀서의 **모든 설정**을 CLI 에서 줄 수 있게 해 줍니다. Step 04 에서 `batch.size`, `linger.ms`, `max.in.flight.requests.per.connection` 을 이 옵션으로 바꿔 가며 실측합니다. 컨슈머 쪽 대응물은 `--consumer-property` 입니다.

설정이 실제로 먹었는지는 프로듀서 기동 로그로 확인합니다.

```bash
docker exec -it kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --producer-property acks=0 2>&1 | grep -E 'acks|linger.ms'
```

**결과**

```
	acks = 0
	linger.ms = 100
```

프로듀서는 기동 시 자신의 전체 설정을 `INFO ProducerConfig values:` 로 덤프합니다. **"내 설정이 진짜 먹었나"를 확인하는 가장 확실한 방법**이며, 오타 난 설정 키는 이 목록에 안 나타나므로 바로 알 수 있습니다.

---

## 1-10. 나머지 코스 토픽 만들기

`payments`, `order-events`, `dlq` 를 만듭니다. 코스 전체에서 씁니다.

```bash
# payments — orders 와 같은 구성. 키는 order_id
kt --create --topic payments \
  --partitions 3 --replication-factor 3 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2

# order-events — 압축(compact) 토픽. 키마다 "최신 값"만 남긴다
kt --create --topic order-events \
  --partitions 3 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.insync.replicas=2

# dlq — 처리 실패 메시지. 순서가 중요하므로 파티션 1개, 보존 30일
kt --create --topic dlq \
  --partitions 1 --replication-factor 3 \
  --config retention.ms=2592000000 \
  --config min.insync.replicas=2
```

**결과**

```
Created topic payments.
Created topic order-events.
Created topic dlq.
```

전체 목록을 확인합니다.

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

`__consumer_offsets` 가 안 보이는 것이 정상입니다. `--list` 는 **내부 토픽을 숨깁니다.** 보려면 `--exclude-internal` 의 반대인... 이 아니라, 그냥 `--describe` 로 지정하거나 `--list` 에 `--exclude-internal` 을 **주지 않으면** 됩니다. 3.7 의 `--list` 는 기본적으로 내부 토픽도 보여 주지만, 아직 컨슈머 그룹을 만든 적이 없어 `__consumer_offsets` 이 **생성되지 않았습니다.** 이 토픽은 첫 컨슈머 그룹이 오프셋을 커밋하는 순간 50개 파티션으로 만들어집니다. Step 06 에서 직접 열어 봅니다.

압축 토픽만 따로 확인합니다.

```bash
kt --describe --topic order-events
```

**결과**

```
Topic: order-events	TopicId: 7hNvC2wRSmqXpLd0aBeTyQ	PartitionCount: 3	ReplicationFactor: 3	Configs: cleanup.policy=compact,min.insync.replicas=2,segment.bytes=1048576
	Topic: order-events	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: order-events	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: order-events	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
```

`Configs` 에 `cleanup.policy=compact` 가 있고, `retention.ms` 는 없습니다. 압축 토픽에는 시간 기반 보존을 안 걸었기 때문입니다.

> 💡 **`cleanup.policy` 는 세 가지 값을 가집니다**
> - `delete` (기본값) — 오래된 세그먼트를 **통째로** 삭제. 로그·메트릭처럼 "지나간 건 버려도 되는" 데이터
> - `compact` — 키마다 **최신 값 하나만** 남김. "현재 상태" 를 담는 데이터(주문 상태, 사용자 프로필)
> - `compact,delete` — 둘 다. 압축하되 아주 오래된 것은 지움
> `order-events` 는 `order_id` 마다 최신 상태만 있으면 되므로 `compact` 입니다. 압축이 실제로 옛 값을 지우는 순간은 Step 09 에서 관찰합니다.
> 여기서 지금 알아 둘 것: **압축 토픽은 키가 반드시 있어야 합니다.** 키가 `null` 인 메시지를 압축 토픽에 넣으면 압축 대상이 아니라 영원히 남고, 로그 클리너가 경고를 뱉습니다.

전체 토픽의 설정 오버라이드를 한눈에 봅니다.

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

`--topics-with-overrides` 는 **브로커 기본값과 다른 설정이 하나라도 있는 토픽만** 보여 주며, 파티션 상세 줄은 생략합니다. 운영 클러스터에서 "누가 뭘 커스터마이징해 놨나"를 훑을 때 씁니다.

---

## 1-11. kafka-ui 로 같은 것을 눈으로 확인

브라우저에서 <http://localhost:8080> 을 엽니다. CLI 로 본 것과 정확히 같은 정보를 화면으로 확인합니다.

| kafka-ui 메뉴 | 대응하는 CLI | 무엇을 확인합니까 |
|---|---|---|
| Brokers | `kafka-broker-api-versions.sh` | 브로커 3대, 각각의 파티션 수·리더 수 |
| Topics | `kt --list` | 토픽 4개 |
| Topics → orders → Overview | `kt --describe --topic orders` | 파티션 3개, Replicas/ISR |
| Topics → orders → Messages | `kafka-console-consumer.sh --from-beginning` | 저장된 메시지, 키, 파티션, 오프셋 |
| Topics → orders → Settings | `kconf --describe --entity-type topics --entity-name orders` | `retention.ms`, `min.insync.replicas` |
| Consumers | `kcg --list` | (아직 비어 있음) |

**Topics → orders → Messages** 를 열면 지금까지 넣은 7건이 보입니다. 각 행에 Partition / Offset / Key / Timestamp 가 나오는데, 1-7 에서 CLI 로 본 것과 **정확히 같은 값**입니다.

Brokers 탭에서는 파티션이 어떻게 분산되었는지가 잘 보입니다.

```
Broker  Partitions  Leaders  Online   Skew
1       4           4        4        0%
2       4           3        4        0%
3       4           3        4        0%
```

토픽 4개의 파티션 총합은 3+3+3+1 = 10 개이고, 각 파티션이 3벌 복제되므로 복제본은 30개, 브로커당 10개입니다. (위 표는 kafka-ui 버전에 따라 집계 기준이 달라 숫자가 다르게 보일 수 있습니다.) 중요한 것은 **`Skew` 가 0% 라는 것** — 세 브로커에 고르게 분산되었다는 뜻입니다.

> 💡 **실무 팁 — UI 는 확인용, 변경은 CLI 나 IaC 로**
> kafka-ui 에서도 토픽을 만들고 설정을 바꿀 수 있습니다. **학습 중에는 쓰지 마세요.** UI 로 바꾸면 무엇이 바뀌었는지 기록이 안 남고, 이 코스의 실습이 어긋납니다.
> 운영에서도 같습니다. 토픽 정의는 Terraform 이나 애플리케이션 기동 시의 AdminClient 코드로 관리하고, UI 는 조회 전용 권한으로 여는 팀이 많습니다.

---

## 1-12. CLI 도구 전체 지도

`/opt/kafka/bin` 에는 40개 가까운 스크립트가 있습니다. 이 코스에서 실제로 쓰는 것만 정리합니다.

```bash
docker exec kafka-1 ls /opt/kafka/bin/ | grep '^kafka-' | head -30
```

| 도구 | 하는 일 | 이 코스에서 |
|---|---|---|
| `kafka-topics.sh` | 토픽 생성·조회·변경·삭제 | **Step 01, 03** (전 과정에서 반복) |
| `kafka-console-producer.sh` | 표준 입력을 메시지로 전송 | Step 01, 03, 04, 09 |
| `kafka-console-consumer.sh` | 메시지를 표준 출력으로 | Step 01, 05, 06, 09 |
| `kafka-consumer-groups.sh` | 그룹 조회·랙 확인·오프셋 리셋·삭제 | **Step 05, 06** (운영 필수) |
| `kafka-configs.sh` | 토픽·브로커·클라이언트 동적 설정 | **Step 03**, 08, 09 |
| `kafka-get-offsets.sh` | 파티션별 최신/최초 오프셋 조회 | Step 01, 06, 09 |
| `kafka-dump-log.sh` | **세그먼트 파일을 사람이 읽을 수 있게 덤프** | **Step 02**, 04, 09 |
| `kafka-broker-api-versions.sh` | 브로커 목록과 지원 API 확인 | Step 01 |
| `kafka-metadata-quorum.sh` | KRaft 쿼럼 상태 | Step 01, **02**, 14 |
| `kafka-reassign-partitions.sh` | 파티션을 다른 브로커로 이동 | **Step 14** |
| `kafka-leader-election.sh` | 선호 리더로 리더 되돌리기 | Step 08, 14 |
| `kafka-producer-perf-test.sh` | 프로듀서 처리량 벤치마크 | **Step 02**(데이터 밀어넣기), **11** |
| `kafka-consumer-perf-test.sh` | 컨슈머 처리량 벤치마크 | Step 11 |
| `kafka-log-dirs.sh` | 브로커별 로그 디렉터리 크기 | Step 09, 14 |
| `kafka-transactions.sh` | 진행 중인 트랜잭션 조회·강제 중단 | Step 07 |
| `kafka-delete-records.sh` | 특정 오프셋 이전 레코드 강제 삭제 | Step 09 |
| `kafka-run-class.sh` | 임의의 Kafka 클래스 실행(JmxTool 등) | Step 14 |
| `kafka-storage.sh` | 데이터 디렉터리 포맷·Cluster ID | (docker 이미지가 자동 수행) |
| `connect-distributed.sh` | Kafka Connect 워커 | Step 12 |

이름의 규칙이 하나 있습니다. **`kafka-*.sh` 는 전부 `kafka-run-class.sh` 의 얇은 래퍼**입니다.

```bash
docker exec kafka-1 cat /opt/kafka/bin/kafka-topics.sh
```

**결과**

```sh
#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# ...
exec $(dirname $0)/kafka-run-class.sh org.apache.kafka.tools.TopicCommand "$@"
```

그래서 래퍼가 없는 기능도 `kafka-run-class.sh` 로 직접 호출할 수 있습니다. Step 14 에서 JMX 지표를 읽을 때 이 방식을 씁니다.

> ⚠️ **`--zookeeper` 옵션은 3.7 에 없습니다**
> 인터넷의 오래된 예제 대부분이 `kafka-topics.sh --zookeeper localhost:2181 --create ...` 형태입니다. **3.7 에서는 그 옵션이 아예 제거되었습니다.**
> ```
> Exception in thread "main" joptsimple.UnrecognizedOptionException: zookeeper is not a recognized option
> ```
> 전부 `--bootstrap-server` 로 대체되었습니다. ZooKeeper 를 쓰는 예제를 보면 **Kafka 2.x 이하 문서**라고 판단하고, 다른 부분의 설명도 의심하세요. `acks` 기본값처럼 조용히 바뀐 것들이 섞여 있습니다.

---

## 1-13. 정리 (실습 마무리)

이 스텝은 **코스 공용 토픽 4개를 만드는 것이 목적**이므로 삭제하지 않습니다. 다만 실습 중 만든 잘못된 토픽이 있다면 지웁니다.

```bash
kt --list
```

**결과** — 정확히 이 네 줄이어야 합니다.

```
dlq
order-events
orders
payments
```

`bad-rf` 같은 것이 남아 있다면(1-3 에서 실패했으므로 생기지 않았어야 합니다) 지웁니다.

```bash
kt --delete --topic bad-rf
```

`orders` 에 넣은 테스트 메시지 7건은 그대로 둡니다. Step 02 에서 **이 메시지들이 저장된 세그먼트 파일을 직접 열어 볼 것**이므로 지우면 안 됩니다.

현재 상태를 확인합니다.

```bash
kgo --topic orders
```

**결과**

```
orders:0:2
orders:1:2
orders:2:3
```

총 7건입니다. 다음 스텝의 출발점입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 클러스터 확인 | `docker compose ps` 로 healthy, `kafka-broker-api-versions.sh` 로 **브로커 3줄** |
| KRaft 쿼럼 | `kafka-metadata-quorum.sh describe --status` → `CurrentVoters: [1,2,3]` |
| 토픽 생성 | `--partitions` / `--replication-factor` / `--config key=value` (여러 번 가능) |
| `TopicId` | 토픽의 진짜 식별자. 지웠다 같은 이름으로 만들면 **값이 바뀐다** |
| `Leader` | 읽기·쓰기를 처리하는 브로커. 파티션마다 하나 |
| `Replicas` | 복제본이 **있어야 할** 브로커 목록. 맨 앞이 선호 리더 |
| `Isr` | **지금 따라잡고 있는** 복제본. `Replicas` 보다 적으면 이상 신호 |
| 오프셋 | **파티션 단위**로 0부터. "3번 메시지"는 파티션을 빼면 무의미 |
| 순서 보장 | **파티션 안에서만.** 같은 키 → 같은 파티션 → 순서 보장 |
| `--from-beginning` | 없으면 `latest` 부터 = **아무것도 안 읽힘, 에러도 없음** |
| `auto.create.topics.enable` | `false` 여야 오타가 `UNKNOWN_TOPIC_OR_PARTITION` 로 드러남 |
| 콘솔 프로듀서 acks | 3.7 기본은 **`-1`(all)**. `--request-required-acks` 또는 `--producer-property acks=` 로 변경 |
| `--producer-property` | Java 프로듀서의 **모든 설정**을 CLI 에서 지정. Step 04 의 주력 도구 |
| `cleanup.policy` | `delete`(기본) / `compact` / `compact,delete` |
| `--zookeeper` | **3.7 에 없음.** 그 옵션을 쓰는 문서는 2.x 시절 것 |

---

## 연습문제

`exercise.sh` 에 6문제가 있습니다. 정답은 `solution.sh`. **직접 명령을 쳐서 출력을 확인**하세요.

1. `s01_practice` 토픽을 파티션 4개, RF 2, 보존 1시간으로 만들고 `--describe` 로 검증하기
2. 브로커 3대 중 파티션 리더를 가장 많이 맡은 브로커 찾기 (`--describe` 출력 파싱)
3. `--from-beginning` 유무에 따라 읽히는 메시지 수가 달라지는 것을 `Processed a total of N messages` 로 확인하기
4. `orders` 에 `C007` 키로 3건을 넣고, 세 건이 **모두 같은 파티션**에 들어갔음을 증명하기
5. 존재하지 않는 토픽으로 프로듀서를 실행했을 때의 에러 메시지 확인하고, 컨슈머의 에러와 비교하기
6. `--producer-property` 로 `acks=0`, `compression.type=gzip` 을 주고, 기동 로그에서 두 값이 실제 적용되었는지 확인하기

---

## 다음 단계

메시지를 보내고 받았습니다. 그런데 그 메시지는 **지금 정확히 어디에 있습니까?**

다음 스텝에서는 브로커 컨테이너 안으로 들어가 `/var/lib/kafka/data` 의 파티션 디렉터리를 열고, `kafka-dump-log.sh` 로 세그먼트 파일의 **바이트 수준 구조**를 직접 봅니다. 방금 넣은 `C001` 메시지가 어느 파일 몇 번째 바이트에 어떤 배치로 들어 있는지 확인하고, 세그먼트가 1MiB 마다 롤링되는 것을 눈으로 관찰합니다. KRaft 가 메타데이터를 저장하는 `__cluster_metadata` 토픽도 같은 방법으로 열어 봅니다.

→ [Step 02 — 아키텍처와 저장 구조](../step-02-architecture/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 를 위에서부터 따라 실행하며 1-0 ~ 1-13 을 재현하고, `exercise.sh` 의 6문제를 직접 푼 뒤 `solution.sh` 로 대조합니다. 세 파일 모두 `BS=kafka-1:9092` 와 `K()` 헬퍼 함수를 공유하며, **터미널을 두 개 이상 요구하는 구간은 `# [터미널 B]` 주석으로 표시**되어 있습니다. 그 구간은 스크립트를 통째로 실행하면 막히므로 별도 창에서 손으로 실행하세요.

### practice.sh

본문 1-0 ~ 1-13 의 모든 명령을 절 번호 주석과 함께 담았습니다.

- 상단의 `K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }` 헬퍼가 파일 전체에서 쓰입니다. 별칭(`kt`, `kcg`)은 서브셸에서 동작하지 않으므로 스크립트에서는 이 함수를 씁니다. 본문에서 `kt --list` 로 쓴 곳은 스크립트에서 `K kafka-topics.sh --bootstrap-server "$BS" --list` 로 풀어 썼습니다.
- `[1-0]` 의 `wait_healthy` 함수는 `docker compose ps` 출력에 `(healthy)` 가 **3번 나올 때까지** 최대 120초를 5초 간격으로 기다립니다. 이 대기 없이 바로 토픽을 만들면 `Broker may not be available` 로 실패할 수 있습니다.
- `[1-5]`, `[1-7]` 은 **터미널 2개가 필요한 구간**이라 실행되지 않고 주석으로만 들어 있습니다. 대신 스크립트에서 자동으로 돌릴 수 있도록, 프로듀서는 `printf '...\n...' | docker exec -i` 로 파이프하고 컨슈머는 `--timeout-ms 5000` 을 붙인 **비대화형 버전**을 함께 넣었습니다. `--timeout-ms` 는 그 시간 동안 새 메시지가 없으면 컨슈머를 종료시키는 옵션으로, 스크립트에서 콘솔 컨슈머를 쓰는 유일하게 안전한 방법입니다.
- `[1-6]` 의 함정 재현은 `--from-beginning` **있는 버전과 없는 버전을 연달아 실행**하고 각각의 `Processed a total of N messages` 를 비교합니다. 7 과 0 이 나와야 정상입니다.
- `[1-8]` 의 오타 토픽 실습은 컨슈머 기본 타임아웃이 60초라 스크립트가 1분간 멈춥니다. `--consumer-property default.api.timeout.ms=5000` 을 붙여 5초로 줄여 두었습니다.
- `[1-10]` 의 토픽 생성은 전부 `|| true` 로 감싸 두었습니다. 이미 만든 뒤 다시 실행하면 `TopicExistsException` 이 나는데, 스크립트가 `set -e` 로 죽지 않고 넘어가게 하기 위해서입니다. **재실행 가능(idempotent)** 하게 만든 것입니다.
- 마지막 `[1-13]` 은 토픽을 지우지 **않고** `kt --list` 와 `kafka-get-offsets.sh` 로 상태만 검증합니다. Step 02 가 이 데이터를 그대로 쓰기 때문입니다.

```bash file="./practice.sh"
```

### exercise.sh

6문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었고, 힌트로 확인해야 할 출력 형태만 적어 두었습니다.

- **문제 1·4·6** 은 명령을 **직접 작성**하는 문제이고, **문제 2·3·5** 는 스크립트가 준비해 준 상황에서 **출력을 관찰·해석**하는 문제입니다.
- 문제 1 은 `--replication-factor 2` 를 요구합니다. 코스 공용 토픽은 전부 RF 3 이므로, RF 2 일 때 `Replicas` 에 브로커가 2개만 나오는 것을 처음 보게 됩니다. `Isr` 도 2개입니다.
- 문제 2 는 `kt --describe` 출력을 `grep -o 'Leader: [0-9]' | sort | uniq -c` 로 세는 것이 정답 경로입니다. 파티션 배치가 라운드로빈이므로 대개 균등하게 나오는데, **균등하지 않게 나오는 경우**(직전에 브로커를 재시작했다면)도 정상이라는 점을 문제 주석에서 짚어 둡니다.
- 문제 3 은 컨슈머를 두 번 실행합니다. 두 실행이 **서로 다른 임의 그룹**(`console-consumer-XXXXX`)을 쓴다는 것이 이 문제의 숨은 포인트입니다. 같은 그룹이었다면 두 번째 실행 결과가 또 달라집니다.
- 문제 4 는 `--property print.partition=true` 로 세 건의 파티션 번호가 같은지 확인하는 문제입니다. `C007` 은 파티션 1로 갑니다.
- 문제 6 은 프로듀서 기동 로그의 `ProducerConfig values:` 블록을 `grep` 하는 문제입니다. 여기서 **일부러 오타 난 설정 키**(`compresion.type`)를 하나 섞어 두었습니다. 오타 키는 로그에 나타나지 않고 `WARN The configuration 'compresion.type' was supplied but isn't a known config.` 가 뜹니다.

```bash file="./exercise.sh"
```

### solution.sh

6문제의 정답 명령과, 왜 그 답인지 설명하는 `# 해설:` 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `--config retention.ms=3600000` 입니다. 1시간을 밀리초로 환산하는 것이 포인트이며, `--config` 를 여러 번 쓸 수 있다는 것도 함께 보여 줍니다. 해설에서 RF 2 의 의미(브로커 1대 손실은 견디고 2대는 못 견딤)를 짚습니다.
- **정답 2** 는 `kt --describe | grep -oE 'Leader: [0-9]+' | sort | uniq -c` 입니다. 해설에서 "리더가 한 브로커에 몰리면 그 브로커만 CPU·네트워크를 쓴다" 는 실무 관점과, `kafka-leader-election.sh --election-type preferred` 로 되돌리는 절차를 설명합니다.
- **정답 3** 의 핵심은 두 숫자(7 과 0)가 아니라 **왜 0인가**입니다. 해설이 `console-consumer-XXXXX` 그룹이 매번 새로 생기는 것, `auto.offset.reset=latest` 가 기본인 것, 그리고 `kcg --list` 로 그 임시 그룹들이 실제로 쌓여 있는 것을 보여 줍니다.
- **정답 4** 는 `murmur2("C007") & 0x7fffffff = 642592957`, `642592957 % 3 = 1` 이라는 **실제 계산 결과**를 제시합니다. Step 03 에서 이 계산을 본격적으로 다루므로 여기서는 결론만 확인합니다.
- **정답 5** 는 프로듀서와 컨슈머의 에러가 **다르다**는 점이 답입니다. 컨슈머는 `TimeoutException: Topic oders not present in metadata after 60000 ms` 로 60초를 버티다 죽고, 프로듀서는 첫 메시지를 보내는 순간 같은 예외를 던집니다. 즉 **프로듀서는 토픽이 없어도 프롬프트가 뜹니다.** 메타데이터를 실제 전송 시점에 가져오기 때문이며, 이것이 "프로듀서를 켰는데 에러가 없어서 잘 되는 줄 알았다"의 원인입니다.
- **정답 6** 은 `acks = 0` / `compression.type = none` 이 나옵니다. gzip 이 아니라 none 인 이유가 정답의 핵심입니다 — 문제지의 키가 `compresion.type` 으로 오타 났기 때문입니다. **설정 오타는 에러가 아니라 WARN 한 줄로 지나갑니다.** 이 코스가 다루는 "조용히 틀리는" 사례의 첫 번째입니다.

```bash file="./solution.sh"
```
