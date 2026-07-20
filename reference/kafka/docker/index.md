# Docker 실습 환경

이 코스의 모든 실습은 **Docker 위에 띄운 Apache Kafka 3.7 KRaft 3브로커 클러스터**에서 진행합니다. 브로커가 3대여야 하는 이유는 분명합니다. **복제·ISR·리더 선출·`min.insync.replicas` 는 브로커가 1대면 아예 관찰할 수 없기 때문입니다.** Step 08 에서 브로커를 죽여 가며 리더가 옮겨 다니는 것을 볼 텐데, 그러려면 최소 3대가 필요합니다.

이 페이지는 그 환경이 **어떻게 구성되어 있는지**를 설명합니다. Step 01 에서 처음 띄울 때, Step 08 에서 브로커를 죽였다 살릴 때, 그리고 "왜 내 프로듀서가 브로커에 연결을 못 하지?" 싶을 때 다시 찾아오면 됩니다.

---

## 전체 구성

| 서비스 | 컨테이너 | 호스트 포트 | 프로필 | 언제 씁니까 |
|---|---|---|---|---|
| Kafka 브로커 1 | `kafka-1` | `19092`, JMX `19999` | 기본 | Step 01 ~ 14 |
| Kafka 브로커 2 | `kafka-2` | `29092`, JMX `29999` | 기본 | Step 01 ~ 14 |
| Kafka 브로커 3 | `kafka-3` | `39092`, JMX `39999` | 기본 | Step 01 ~ 14 |
| kafka-ui | `kafka-ui` | `8080` | 기본 | 전 과정 (눈으로 확인용) |
| Schema Registry | `schema-registry` | `8081` | `registry` | Step 10 |
| Kafka Connect | `kafka-connect` | `8083` | `connect` | Step 12 |

프로필이 붙은 두 서비스는 **기본 `docker compose up -d` 로는 뜨지 않습니다.** 메모리를 아끼기 위해서입니다. 필요한 스텝에서 이렇게 켭니다.

```bash
docker compose --profile registry up -d      # Step 10
docker compose --profile connect up -d       # Step 12
docker compose --profile all     up -d       # 전부
```

---

## 사용법

### 기동

```bash
cd kafka/docker
docker compose up -d
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

세 브로커가 모두 `(healthy)` 여야 합니다. `(health: starting)` 이면 조금 더 기다리세요. KRaft 는 첫 기동 시 컨트롤러 쿼럼을 구성하느라 20~40초가 걸립니다.

### 편의 별칭

이 코스는 CLI 도구를 수백 번 호출합니다. 매번 `docker exec kafka-1 /opt/kafka/bin/...` 을 치면 손목이 남아나지 않으므로 별칭을 등록합니다.

```bash
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
alias kcg='docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092'
alias kcat='docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092'
alias kconf='docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka-1:9092'
```

교재는 이 별칭과 전체 명령을 **섞어 씁니다.** 처음 나오는 명령은 전체 형태로 보여 주고, 반복될 때는 별칭을 씁니다.

> 💡 **실무 팁 — 셸에 들어가서 작업하는 편이 편할 때가 많습니다**
> ```bash
> docker exec -it kafka-1 bash
> cd /opt/kafka/bin && ls kafka-*.sh
> ```
> 컨테이너 안에서는 `--bootstrap-server localhost:9092` 로 붙으면 됩니다. Step 02 에서 세그먼트 파일을 뒤질 때는 이쪽이 훨씬 편합니다.

### 초기화

```bash
docker compose down -v && docker compose up -d
```

`-v` 가 핵심입니다. 이름 있는 볼륨(`kafka-1-data` 등)에 로그 세그먼트와 `__consumer_offsets` 가 들어 있어서, `-v` 없이 내리면 **토픽과 오프셋이 그대로 남습니다.** "분명 토픽을 지웠는데 다시 생긴다" 싶으면 십중팔구 `-v` 를 빼먹은 것입니다.

---

## 리스너 설정 — 이 파일에서 가장 중요한 부분

Kafka 를 Docker 로 처음 띄울 때 **거의 모든 사람이 여기서 막힙니다.** 증상은 늘 똑같습니다.

```
%3|1710000000.000|FAIL|rdkafka#producer-1| [thrd:kafka-1:9092/1]:
  kafka-1:9092/1: Connect to ipv4#172.18.0.3:9092 failed: Connection refused
```

또는

```
org.apache.kafka.common.errors.TimeoutException:
  Topic orders not present in metadata after 60000 ms.
```

원인을 이해하려면 **Kafka 클라이언트가 브로커에 붙는 2단계 절차**를 알아야 합니다.

```
  ① 부트스트랩                          ② 실제 통신
  클라이언트 ──────────► 아무 브로커     클라이언트 ──────────► 리더 브로커
             "메타데이터 주세요"                    "이 파티션에 쓸게요"
                    │                                    ▲
                    ▼                                    │
       브로커가 응답: "orders-0 의 리더는 ───────────────┘
                     advertised.listeners 에 적힌 이 주소야"
```

**핵심은 ② 에서 쓰는 주소가 여러분이 ① 에 넣은 주소가 아니라는 것입니다.** 클라이언트는 브로커가 알려 준 `advertised.listeners` 주소로 **다시 접속**합니다. 그래서 `advertised.listeners` 가 틀리면, 부트스트랩은 성공하고 실제 전송에서 실패합니다. 에러 메시지가 부트스트랩 주소와 전혀 무관한 호스트명을 가리키는 이유입니다.

### 이 클러스터의 리스너 3종

```
                       kafka-1 컨테이너
   ┌───────────────────────────────────────────────────────┐
   │                                                       │
   │  INTERNAL  0.0.0.0:9092   ← 브로커끼리, Connect, UI     │
   │            advertised → kafka-1:9092                  │
   │                                                       │
   │  CONTROLLER 0.0.0.0:9093  ← KRaft 쿼럼 전용            │
   │            (advertised 하지 않음 — 클라이언트가 쓰지 않음)│
   │                                                       │
   │  EXTERNAL  0.0.0.0:19092  ← 호스트(내 노트북)에서 접속   │
   │            advertised → localhost:19092               │
   │                                                       │
   └───────────────────────────────────────────────────────┘
                              │ 포트 매핑 19092:19092
                              ▼
                     내 노트북 localhost:19092
```

관련 설정 네 줄을 다시 봅니다.

```yaml
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT
KAFKA_LISTENERS:            INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,EXTERNAL://0.0.0.0:19092
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka-1:9092,EXTERNAL://localhost:19092
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

| 설정 | 뜻 | 틀리면 |
|---|---|---|
| `listener.security.protocol.map` | 리스너 **이름**을 보안 프로토콜에 매핑. `INTERNAL` 같은 이름은 임의이며, 여기에 등록해야 쓸 수 있음 | 기동 실패: `No security protocol defined for listener INTERNAL` |
| `listeners` | 브로커가 **바인딩할 주소**. `0.0.0.0` 이어야 컨테이너 밖에서 들어옴 | `127.0.0.1` 로 두면 호스트에서 Connection refused |
| `advertised.listeners` | 브로커가 클라이언트에게 **알려 줄 주소** | 부트스트랩만 되고 전송이 타임아웃 |
| `inter.broker.listener.name` | 브로커끼리 쓸 리스너 | 복제가 안 되고 ISR 이 안 찬다 |
| `controller.listener.names` | KRaft 컨트롤러 전용 리스너 | 쿼럼 구성 실패, 브로커가 기동 중 멈춤 |

### 자주 겪는 리스너 설정 실수 4가지

> ⚠️ **함정 1 — `advertised.listeners` 에 컨테이너 이름 하나만 쓴다**
> `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka-1:9092` 만 두면, **컨테이너 안에서는 완벽히 동작합니다.** 브로커끼리도 잘 통신하고 `docker exec` 로 도는 CLI 도 잘 됩니다.
> 그런데 호스트(내 노트북)의 IntelliJ 나 로컬 CLI 로 붙으면 실패합니다. 노트북에는 `kafka-1` 이라는 호스트명이 없기 때문입니다.
> **해결**: 리스너를 **두 개**(INTERNAL / EXTERNAL) 두고 각각 다른 주소를 광고합니다. 이 코스가 그렇게 구성한 이유입니다.
> 임시방편으로 `/etc/hosts` 에 `127.0.0.1 kafka-1` 을 넣는 방법도 있지만, 브로커가 3대면 포트가 전부 9092 라 충돌합니다. **결국 리스너를 나눠야 합니다.**

> ⚠️ **함정 2 — 브로커마다 EXTERNAL 포트를 같게 둔다**
> 세 브로커가 모두 `EXTERNAL://localhost:9092` 를 광고하면, 클라이언트는 **어느 파티션의 리더든 전부 localhost:9092 로 접속**합니다. 즉 항상 kafka-1 에만 붙습니다.
> 증상이 지독합니다. **에러는 안 납니다.** 다만 kafka-2, kafka-3 이 리더인 파티션에 쓰려는 요청이 kafka-1 로 가서 `NOT_LEADER_OR_FOLLOWER` 를 받고, 클라이언트가 메타데이터를 새로고침하고, 또 같은 주소로 가고... 무한 재시도하다 타임아웃납니다.
> **해결**: 브로커마다 EXTERNAL 포트를 다르게(19092 / 29092 / 39092) 하고, 포트 매핑도 **동일 번호로** 합니다. `19092:19092` 이지 `19092:9092` 가 아닙니다. 광고한 포트와 브로커가 실제로 리스닝하는 포트가 같아야 합니다.

> ⚠️ **함정 3 — CONTROLLER 리스너를 `advertised.listeners` 에 넣는다**
> KRaft 의 컨트롤러 리스너는 **광고하면 안 됩니다.** 넣으면 기동 시 이렇게 죽습니다.
> ```
> java.lang.IllegalArgumentException: requirement failed:
>   The advertised.listeners config must not contain KRaft controller listeners
>   from controller.listener.names when process.roles contains the broker role
> ```
> 컨트롤러 리스너는 클러스터 내부 합의 전용이며, 일반 클라이언트가 접속할 대상이 아니기 때문입니다.
> **해결**: `listeners` 에는 `CONTROLLER://0.0.0.0:9093` 을 넣되, `advertised.listeners` 에서는 뺍니다. 위 설정이 정확히 그 모양입니다.

> ⚠️ **함정 4 — `listeners` 에 `0.0.0.0` 대신 컨테이너 이름을 쓴다**
> `KAFKA_LISTENERS: EXTERNAL://kafka-1:19092` 로 두면 브로커가 컨테이너의 특정 인터페이스에만 바인딩합니다. 대개는 동작하지만, Docker 네트워크 구성에 따라 호스트 포트 매핑이 닿지 않아 `Connection refused` 가 납니다.
> **원칙**: `listeners` 는 **어디서 받을지**(0.0.0.0 = 전부), `advertised.listeners` 는 **어떻게 찾아오라고 할지**(구체 주소). 이 둘의 역할이 다르다는 것만 기억하면 헷갈리지 않습니다.

> 💡 **리스너가 제대로 광고되는지 확인하는 명령**
> ```bash
> docker exec kafka-1 /opt/kafka/bin/kafka-broker-api-versions.sh \
>   --bootstrap-server localhost:19092 | head -3
> ```
> **결과**
> ```
> localhost:19092 (id: 1 rack: null) -> (
> localhost:29092 (id: 2 rack: null) -> (
> localhost:39092 (id: 3 rack: null) -> (
> ```
> EXTERNAL 로 붙었으니 광고 주소도 EXTERNAL(`localhost:N9092`)로 나옵니다. `kafka-1:9092` 로 붙으면 `kafka-1:9092 (id: 1 ...)` 로 나옵니다. **부트스트랩에 쓴 리스너와 같은 계열의 주소가 나와야 정상입니다.**

---

## KRaft 설정 — ZooKeeper 가 없다

Kafka 3.3 부터 KRaft(Kafka Raft) 가 프로덕션 준비 상태가 되었고, 3.7 은 KRaft 로 새 클러스터를 만드는 것이 기본입니다. ZooKeeper 는 3.x 대에서 지원 종료 예정입니다.

```yaml
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
```

- `process.roles: broker,controller` — **한 프로세스가 브로커와 컨트롤러를 겸합니다(combined mode).** 학습·소규모 환경용입니다. 운영에서는 컨트롤러 3대를 분리(`process.roles: controller`)하는 것을 권장합니다.
- `controller.quorum.voters` — `노드ID@호스트:포트` 형식. **세 노드 모두 동일한 값**을 가져야 하며, 하나라도 다르면 쿼럼이 구성되지 않습니다.
- `CLUSTER_ID` — 클러스터의 고정 식별자. `apache/kafka` 이미지는 이 값으로 `kafka-storage.sh format` 을 자동 수행합니다. **값을 바꾸면 기존 볼륨과 불일치**해서 이렇게 죽습니다.
  ```
  kafka.common.InconsistentClusterIdException: The Cluster ID MkU3... doesn't
    match stored clusterId Some(abcd...) in meta.properties.
  ```
  이 코스는 값을 고정해 두었으니 건드리지 마세요. 실수로 바꿨다면 `docker compose down -v`.

KRaft 와 ZooKeeper 의 차이, 메타데이터가 어디에 저장되는지는 [Step 02](../step-02-architecture/) 에서 `__cluster_metadata` 토픽을 직접 열어 보며 다룹니다.

---

## 학습용으로 일부러 바꾼 설정

이 클러스터는 **운영 기본값과 다릅니다.** 관찰을 빠르게 하기 위해서입니다. 그대로 운영에 쓰면 안 됩니다.

| 설정 | 이 클러스터 | 운영 기본값 | 왜 바꿨습니까 |
|---|---|---|---|
| `log.segment.bytes` | **1 MiB** | 1 GiB | Step 02·09 에서 세그먼트 롤링을 **몇 초 안에** 보려고 |
| `log.cleaner.min.cleanable.ratio` | **0.01** | 0.5 | Step 09 에서 압축이 **바로** 돌게 하려고 |
| `log.cleaner.backoff.ms` | 5000 | 15000 | 위와 같은 이유 |
| `group.initial.rebalance.delay.ms` | **0** | 3000 | Step 05 에서 컨슈머 붙자마자 할당되게 하려고 |
| `num.partitions` | 3 | 1 | 파티션이 1개면 병렬성·순서 실습이 안 됨 |
| `min.insync.replicas` | **2** | 1 | Step 08 의 기본 시나리오를 위해 |
| `auto.create.topics.enable` | **false** | true | 오타로 토픽이 생기는 사고를 막기 위해 (아래 팁) |
| `log.retention.check.interval.ms` | 10000 | 300000 | Step 09 에서 보존 삭제를 기다리지 않으려고 |
| JVM 힙 | 768 MiB | 6 GiB 이상 | 노트북에서 3대를 띄우려고 |

> 💡 **실무 팁 — `auto.create.topics.enable=false` 는 운영에서도 권장입니다**
> `true` 면 컨슈머가 오타 난 토픽 이름으로 접속하는 순간 **그 이름의 토픽이 생깁니다.** 기본 파티션 수·RF 로요.
> "왜 `oders` 라는 토픽이 있지?" 는 실무에서 흔한 미스터리이고, 답은 대개 어느 개발자의 오타입니다.
> 이 코스는 `false` 로 두었으므로, 없는 토픽에 접속하면 명확하게 에러가 납니다. **에러가 나는 게 좋은 것입니다.**

> ⚠️ **함정 — 세그먼트를 1MiB 로 줄인 대가**
> Step 11 의 성능 측정에서 이 설정이 **처리량을 깎습니다.** 100만 건을 넣으면 세그먼트 파일이 수백 개 생기고, 파일 핸들과 롤링 오버헤드가 붙습니다.
> Step 11 은 그래서 `orders-perf` 토픽을 만들 때 `--config segment.bytes=268435456`(256MiB)로 **토픽 단위로 덮어씁니다.** 브로커 기본값보다 토픽 설정이 우선하기 때문입니다.
> "왜 내 벤치마크가 이상하게 느리지?" 의 원인 중 하나가 이런 잘못된 세그먼트 크기입니다.

---

## 메모리와 리소스

브로커 3대 + kafka-ui 로 대략 **2.5 GiB** 를 씁니다. Schema Registry 와 Connect 까지 켜면 **4 GiB** 근처입니다.

```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
```

**결과**

```
NAME              MEM USAGE / LIMIT     CPU %
kafka-1           612.4MiB / 7.654GiB   1.82%
kafka-2           598.1MiB / 7.654GiB   1.64%
kafka-3           604.7MiB / 7.654GiB   1.71%
kafka-ui          341.2MiB / 7.654GiB   0.31%
```

Docker Desktop 의 메모리 할당이 4 GiB 이하라면 **Settings → Resources → Memory 를 6 GiB 이상**으로 올리세요. 부족하면 브로커가 OOM 으로 조용히 재시작하며, 증상은 "가끔 컨슈머가 리밸런싱한다" 처럼 나타나 원인을 찾기 어렵습니다.

---

## 실습 파일

이 디렉터리에는 파일이 하나뿐입니다. `docker-compose.yml` 이 이 코스 실습 환경의 전부이며, Step 01 에서 한 번 띄운 뒤 Step 14 까지 계속 씁니다. 프로필로 분리한 Schema Registry / Kafka Connect 도 같은 파일 안에 있습니다.

### docker-compose.yml

Step 01 에서 `docker compose up -d` 로 실행하는 **메인 정의**입니다.

- `x-kafka-common` / `&kafka-env` **YAML 앵커**로 세 브로커의 공통 설정을 한 곳에 모았습니다. 브로커마다 다른 것은 `KAFKA_NODE_ID`, `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`, 포트 매핑 **네 가지뿐**입니다. 설정을 바꿀 일이 생기면 대개 앵커 쪽 한 곳만 고치면 됩니다.
- `KAFKA_CONTROLLER_QUORUM_VOTERS` 는 **세 브로커가 완전히 같은 문자열**을 가집니다(앵커에 있으므로 자동으로 그렇게 됩니다). 이 값이 어긋나면 쿼럼이 안 서고 브로커가 기동 중 무한 대기합니다.
- `healthcheck` 는 `kafka-broker-api-versions.sh` 를 5초 간격으로 최대 20회 시도합니다. 즉 **최대 100초**를 기다려 줍니다. `kafka-ui` 는 `condition: service_healthy` 로 세 브로커를 모두 기다린 뒤에 뜨므로, UI 가 늦게 뜬다고 놀라지 마세요.
- `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3` — `__consumer_offsets` 의 복제 계수입니다. 브로커 1대짜리 예제에서 이 값을 3으로 두면 **컨슈머 그룹을 만들 때 처음 실패**합니다(복제본을 배치할 브로커가 없어서). 3대이므로 3이 맞습니다. Step 06 에서 이 토픽을 직접 열어 봅니다.
- `KAFKA_LOG_SEGMENT_BYTES: 1048576` — **1 MiB**. 위 표에서 설명한 학습용 축소값입니다. Step 02 에서 `00000000000000000000.log` 다음에 `00000000000000000523.log` 가 생기는 것을 몇 초 만에 보기 위한 것입니다.
- `KAFKA_OPTS` 의 JMX 원격 설정은 **인증도 TLS 도 끄고** 있습니다. Step 14 에서 `jconsole` 이나 `kafka-run-class.sh kafka.tools.JmxTool` 로 붙기 위한 학습용 구성이며, **운영에서 이 상태로 포트를 열면 원격 코드 실행 경로가 됩니다.**
- `schema-registry` 와 `kafka-connect` 는 `profiles` 로 묶여 있어 기본 기동에서 제외됩니다. `kafka-ui` 는 두 서비스의 주소를 **미리 알고 있으므로**, 나중에 프로필로 켜면 UI 에 자동으로 나타납니다. UI 를 재시작할 필요가 없습니다.
- `kafka-connect` 의 `volumes: ./connect-data:/data` 는 Step 12 의 FileStream 커넥터가 읽고 쓸 디렉터리입니다. 이 디렉터리는 Step 12 스크립트가 만들므로 미리 만들어 둘 필요는 없습니다.
- `volumes:` 아래 세 개의 **이름 있는 볼륨**이 로그 세그먼트와 KRaft 메타데이터를 담습니다. `docker compose down` 만으로는 안 지워집니다. 초기화하려면 반드시 `-v`.

```yaml file="./docker-compose.yml"
```

---

## 다음 단계

환경을 이해했으면 실제로 띄우고 첫 메시지를 왕복시켜 봅니다.

→ [Step 01 — 클러스터 기동과 첫 메시지](../step-01-setup/)
