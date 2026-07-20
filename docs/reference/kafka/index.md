# Apache Kafka 완전 학습 코스

브로커 기동부터 운영 플레이북까지, **14개 스텝**으로 Apache Kafka 3.7을 처음부터 끝까지 익힙니다.
실습은 **KRaft 모드 3브로커 클러스터**에서 진행하며, 모든 명령은 실제로 돌아가는 클러스터에서 검증했습니다. **교재의 출력은 여러분 터미널의 출력과 정확히 일치합니다.**

이 코스는 **Kafka 그 자체**를 다룹니다. 브로커가 메시지를 어디에 어떻게 쓰는지, 왜 순서가 뒤바뀌는지, 왜 아무 에러 없이 메시지가 사라지는지를 CLI 도구로 직접 재현합니다. Spring 통합은 별도의 Spring Kafka 코스에서 다루므로 여기서는 다루지 않습니다.

---

## 시작하기 (5분)

```bash
# 1. KRaft 3브로커 클러스터 기동
cd kafka/docker
docker compose up -d

# 2. 세 브로커가 모두 healthy 가 될 때까지 대기 (보통 30~50초)
docker compose ps

# 3. 편의 별칭 등록 — 이 코스 내내 씁니다
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
alias kcg='docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092'

# 4. 클러스터가 살아 있는지 확인
kt --list
```

**결과** (아직 토픽이 없으므로 빈 출력이 정상입니다)

```
```

브로커 3대가 서로를 인식하는지 확인합니다.

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

세 줄이 나오면 준비 완료입니다. 브라우저에서 <http://localhost:8080> 을 열면 kafka-ui 로 같은 클러스터를 눈으로 볼 수 있습니다.

실습이 꼬였다면 아래 한 줄로 완전히 초기화합니다. **볼륨까지 삭제되므로 그동안 넣은 메시지·토픽·오프셋이 전부 사라집니다.**

```bash
docker compose down -v && docker compose up -d
```

> 로컬에 Kafka CLI 를 설치했다면 `docker exec` 없이 `--bootstrap-server localhost:19092` 로도 됩니다.
> 다만 **교재는 컨테이너 안에서 실행하는 것을 기준**으로 씁니다. 브로커 이름(`kafka-1`)이 출력에 그대로 나와야 설명과 맞기 때문입니다.

---

## 커리큘럼

### 1부 — 기초: 무엇이 어디에 저장되는가 (Step 01~03)
> Kafka 를 한 번도 안 써 봐도 됩니다. 여기서 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 클러스터 기동과 첫 메시지 | `docker compose up`, 토픽 생성, 콘솔 프로듀서/컨슈머 첫 왕복 |
| [02](step-02-architecture/) | 아키텍처와 저장 구조 | 브로커/토픽/파티션/오프셋, **세그먼트 파일을 직접 열어보기**, `kafka-dump-log.sh`, KRaft vs ZooKeeper |
| [03](step-03-topics-partitions/) | 토픽과 파티션 | 생성/변경/삭제, **파티션은 늘릴 수만 있고 줄일 수 없다**, 키 기반 파티셔닝이 파티션 증가로 깨지는 함정 |

### 2부 — 클라이언트: 보내고 받는다 (Step 04~06)
> 여기부터 "조용히 틀리는" 구간이 시작됩니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [04](step-04-producer/) | 프로듀서 | `acks=0/1/all` 실측, 파티셔너, `batch.size`/`linger.ms`, 압축, **`max.in.flight` > 1 + 재시도 = 순서 뒤바뀜** |
| [05](step-05-consumer/) | 컨슈머와 컨슈머 그룹 | 파티션 할당 전략, `kafka-consumer-groups.sh` 로 랙 확인, **컨슈머 수 > 파티션 수이면 논다**, 리밸런싱 관찰 |
| [06](step-06-offsets/) | 오프셋 관리 | `auto.offset.reset`, 자동/수동 커밋, **`enable.auto.commit` 이 만드는 유실과 중복을 직접 재현**, `__consumer_offsets` 조회 |

### 3부 — 보장: 정확히 한 번은 가능한가 (Step 07~09)
> 이 코스의 심장부입니다. 메시지를 실제로 잃어버려 봅니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [07](step-07-delivery-semantics/) | 전달 보장 | at-most-once / at-least-once / **exactly-once 를 직접 재현**, 멱등 프로듀서, 트랜잭션 API, `read_committed` |
| [08](step-08-replication/) | 복제와 내구성 | `replication.factor`, ISR, `min.insync.replicas`, **브로커를 죽여 리더 선출 관찰**, **unclean 리더 선출이 데이터를 버리는 순간** |
| [09](step-09-retention-compaction/) | 보존과 로그 압축 | `retention.ms`/`bytes`, `cleanup.policy=compact` 동작 관찰, tombstone, 압축 토픽 설계 기준 |

### 4부 — 확장: 스키마·성능·생태계 (Step 10~13)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [10](step-10-serialization/) | 직렬화와 스키마 | JSON/Avro/Protobuf 비교, Schema Registry, 호환성 규칙(BACKWARD/FORWARD/FULL), **스키마 변경이 컨슈머를 깨뜨리는 사례** |
| [11](step-11-performance/) | 성능 튜닝 | `kafka-producer-perf-test.sh` 로 **처리량 실측**, 처리량 vs 지연 트레이드오프, `fetch.min.bytes`, 컨슈머 랙 대응 |
| [12](step-12-connect/) | Kafka Connect | 분산 모드, 파일/JDBC 소스·싱크 커넥터, REST API, SMT, **커넥터 오프셋 저장 위치와 재시작 동작** |
| [13](step-13-streams/) | Kafka Streams | KStream/KTable, 집계와 상태 저장소, 윈도우, 조인, **changelog/repartition 내부 토픽이 자동 생성된다** |

### 5부 — 운영 (Step 14)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [14](step-14-operations/) | 운영과 최종 프로젝트 | 파티션 재할당, 롤링 재시작, JMX 지표, **장애 플레이북 표**, 종합 실습: 주문 이벤트 파이프라인 |

> 📌 Step 04·06·07·08 이 이 코스의 뼈대입니다. 네 스텝은 각각 "프로듀서가 잃는다 / 컨슈머가 잃는다 / 중복이 생긴다 / 브로커가 버린다" 를 다루며, **네 곳 모두 아무 에러도 나지 않습니다.** 시간이 없다면 이 네 스텝만이라도 실습하세요.

---

## 각 스텝의 구성

```
step-04-producer/
├── index.md        ← 교재 본문. 개념 설명 + 명령 + 실제 출력 + 함정/팁
├── practice.sh     ← 교재의 모든 명령을 그대로 담은 실행 파일
├── exercise.sh     ← 연습문제 (문제만)
├── solution.sh     ← 정답 + 해설 주석
└── Practice.java   ← CLI 로는 재현 못 하는 실습만 (Step 04·05·06·07·13)
```

**권장 학습 방법**

1. `index.md` 를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마세요.
2. 출력이 교재와 다르면 멈추고 원인을 찾으세요. (거의 항상 토픽 이름 오타이거나, 이전 스텝의 컨슈머 그룹이 살아 있는 경우입니다)
3. `exercise.sh` 를 풀고 `solution.sh` 로 채점합니다.
4. 다음 스텝으로.

```bash
# 실습 스크립트 통째로 실행
bash step-04-producer/practice.sh

# 한 단계씩 확인하며 실행 (권장)
bash -x step-04-producer/practice.sh
```

> 대부분의 실습 스크립트는 **터미널 2~3개**를 요구합니다(프로듀서 하나, 컨슈머 둘). 스크립트 안에 `# [터미널 B]` 주석으로 표시해 두었으니, 해당 구간은 별도 창에서 실행하세요.

---

## 실습 도메인 — 가상 쇼핑몰 이벤트 파이프라인

주문이 들어오고, 결제가 붙고, 상태가 바뀌고, 처리에 실패한 것이 따로 모입니다.

```
   [ 주문 API ]                    [ 결제 서비스 ]
        │ key=customer_id                │ key=order_id
        ▼                                ▼
   ┌──────────┐                    ┌───────────┐
   │  orders  │  3 파티션 / RF 3    │  payments │  3 파티션 / RF 3
   └────┬─────┘                    └─────┬─────┘
        │                                │
        │        ┌───────────────────────┘
        ▼        ▼
   ┌───────────────────┐          처리 실패
   │  주문 처리 컨슈머   │ ───────────────────► ┌───────┐
   │  (order-processor)│                      │  dlq  │  1 파티션
   └─────────┬─────────┘                      └───────┘
             │ 최신 상태만 유지
             ▼
   ┌──────────────────────────────┐
   │  order-events                │  cleanup.policy=compact
   │  key=order_id, 값=최신 상태    │  → 같은 키의 옛 값은 사라진다 (Step 09)
   └──────────────────────────────┘
```

| 토픽 | 파티션 | RF | 키 | 정책 | 어디서 씁니까 |
|---|---:|---:|---|---|---|
| `orders` | 3 | 3 | `customer_id` | delete (7일) | Step 01~14 (거의 전 과정) |
| `payments` | 3 | 3 | `order_id` | delete (7일) | Step 07, 12, 13 |
| `order-events` | 3 | 3 | `order_id` | **compact** | Step 09, 13 |
| `dlq` | 1 | 3 | `order_id` | delete (30일) | Step 06, 07, 12 |
| `orders-perf` | 6 | 3 | 없음 | delete (1시간) | Step 11 (성능 측정 전용) |

### 메시지 포맷

값은 한 줄 JSON 입니다. 콘솔 프로듀서로 손으로 칠 수 있을 만큼 짧게 유지했습니다.

```
# orders  (key: customer_id)
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}

# payments  (key: order_id)
O-1001:{"order_id":"O-1001","method":"CARD","amount":39000,"result":"APPROVED"}

# order-events  (key: order_id, 최신 상태만 살아남음)
O-1001:{"order_id":"O-1001","status":"SHIPPED","at":"2024-03-11T10:22:00Z"}
```

키와 값은 `--property parse.key=true --property key.separator=:` 로 구분합니다. 이 두 옵션은 코스 내내 반복해서 나옵니다.

---

## 실습 규칙

- **토픽 이름에 스텝 접두사를 붙이는 실습이 있습니다.** `s03_`, `s08_` 처럼 시작하는 토픽은 그 스텝 전용이며, 스텝 끝의 정리 절에서 삭제합니다.
- 공용 토픽(`orders`, `payments`, `order-events`, `dlq`)은 **설정을 바꾸지 마세요.** 설정 변경 실습은 전부 `sNN_` 토픽에서 합니다.
- **컨슈머 그룹도 정리 대상입니다.** 실습이 끝나면 그룹을 지우세요. 안 지우면 다음 스텝에서 "왜 메시지가 안 읽히지?"(이미 커밋된 오프셋 때문)로 헤맵니다.
  ```bash
  kcg --list
  kcg --delete --group s05-demo
  ```
- 실습 흔적을 한 번에 지우려면:
  ```bash
  kt --list | grep -E '^s[0-9]{2}_' | xargs -I{} docker exec kafka-1 \
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --delete --topic {}
  ```
- 브로커를 죽이는 실습(Step 08, 14)은 반드시 `docker compose start kafka-2` 로 되살린 뒤 다음 스텝으로 넘어가세요.

---

## 이 코스가 특히 신경 쓴 것

Kafka 는 설정을 틀려도 **에러를 내지 않습니다.** 프로듀서는 계속 `send()` 에 성공하고, 컨슈머는 계속 메시지를 받고, 브로커 로그는 조용합니다. 그런데 며칠 뒤 정산이 안 맞습니다. 이 코스는 그 순간들을 **일부러 재현**하는 데 집중했습니다.

- `acks=1` 로 보낸 직후 리더가 죽으면 **메시지가 사라집니다.** 프로듀서는 성공 콜백을 이미 받았습니다 (Step 04, 08)
- `max.in.flight.requests.per.connection > 1` + 재시도 조합은 **같은 키의 메시지 순서를 뒤바꿉니다.** 예외는 없습니다 (Step 04)
- `enable.auto.commit=true` 는 **처리하지 않은 메시지의 오프셋을 커밋해 버립니다.** 컨슈머가 죽으면 그만큼 유실됩니다 (Step 06)
- 반대로 처리 직후 죽으면 **같은 메시지를 다시 처리**합니다. 결제가 두 번 됩니다 (Step 06, 07)
- 파티션을 3개에서 6개로 늘리면 **같은 `customer_id` 가 다른 파티션으로 갑니다.** 순서 보장이 그 순간 깨집니다 (Step 03)
- `min.insync.replicas` 를 안 걸어 두면 `acks=all` 도 **복제본 1개짜리 성공**을 성공으로 칩니다 (Step 08)
- `unclean.leader.election.enable=true` 는 **이미 커밋된 메시지를 통째로 버리고** 클러스터를 살립니다 (Step 08)
- Schema Registry 의 호환성이 `NONE` 이면 필드 하나 추가가 **모든 컨슈머를 조용히 멈춰 세웁니다** (Step 10)
- Kafka Streams 는 **내부 토픽을 자동으로 만듭니다.** 운영에서 "누가 이 토픽 만들었지?" 의 정체입니다 (Step 13)

각 스텝의 `⚠️ 함정` 블록을 특히 눈여겨 보세요.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Apache Kafka | 3.7 (검증: 3.7.1, **KRaft 모드 — ZooKeeper 없음**) |
| 이미지 | `apache/kafka:3.7.1` |
| 브로커 | `kafka-1` / `kafka-2` / `kafka-3` (node.id = 1/2/3, broker+controller 겸임) |
| 내부 접속 | `kafka-1:9092,kafka-2:9092,kafka-3:9092` |
| 호스트 접속 | `localhost:19092,localhost:29092,localhost:39092` |
| Cluster ID | `MkU3OEVBNTcwNTJENDM2Qk` (고정) |
| kafka-ui | <http://localhost:8080> |
| Schema Registry | <http://localhost:8081> (`--profile registry`, Step 10) |
| Kafka Connect | <http://localhost:8083> (`--profile connect`, Step 12) |
| JMX | `localhost:19999` / `29999` / `39999` (Step 14) |
| Java | 21 (`Practice.java` 실습용) |
| 설정 | [`docker/docker-compose.yml`](./docker/) — 세그먼트 1MiB, 로그 클리너 활성 |

> 학습 편의를 위해 `log.segment.bytes` 를 **1MiB** 로, `log.cleaner.min.cleanable.ratio` 를 **0.01** 로 낮췄습니다.
> 운영 기본값은 각각 1GiB, 0.5 입니다. **세그먼트 롤링과 로그 압축을 몇 초 안에 눈으로 보기 위한 학습용 설정**이며, 운영에서 이러면 파일 핸들이 폭발합니다.
> 왜 그런지는 [Step 02](step-02-architecture/) 와 [Step 09](step-09-retention-compaction/) 에서 설명합니다.
