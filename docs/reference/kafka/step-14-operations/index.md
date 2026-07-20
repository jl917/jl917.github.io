# Step 14 — 운영과 최종 프로젝트

> **학습 목표**
> - `kafka-reassign-partitions.sh` 로 파티션을 다른 브로커로 옮기고, 스로틀을 걸고 해제한다
> - 무중단 롤링 재시작 절차를 단계별 검증과 함께 수행한다
> - JMX 로 브로커 지표를 읽고, **반드시 감시해야 할 9개 지표**를 구분한다
> - 증상 → 원인 → 진단 명령 → 조치로 이어지는 **장애 플레이북**을 갖춘다
> - **종합 실습**: 주문 이벤트 파이프라인을 설계·구축하고, 장애를 주입해 **유실 0** 을 검증한다
> - 코스 전체의 "조용한 실패" 를 한 표로 정리한다
>
> **선행 스텝**: [Step 13 — Kafka Streams](../step-13-streams/)
> **예상 소요**: 150분

---

## 14-0. 실습 준비

모든 브로커가 살아 있어야 합니다. [Step 08](../step-08-replication/) 에서 브로커를 죽였다면 반드시 되살리고 시작하십시오.

```bash
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
```

**결과**

```
NAME       STATUS
kafka-1    Up 3 hours (healthy)
kafka-2    Up 3 hours (healthy)
kafka-3    Up 3 hours (healthy)
kafka-ui   Up 3 hours
```

클러스터가 건강한지 세 줄로 확인합니다. 이 코스의 마지막 스텝이니만큼, 이 세 줄을 **습관으로** 만드십시오.

```bash
kt --describe --under-replicated-partitions | wc -l   # 0
kt --describe --under-min-isr-partitions   | wc -l   # 0
kt --describe --unavailable-partitions     | wc -l   # 0
```

**결과**

```
0
0
0
```

---

## 14-1. 파티션 재할당 — 브로커 간 데이터 이동

브로커를 추가했거나, 특정 브로커에 파티션이 몰렸거나, 브로커를 빼야 할 때 파티션을 옮깁니다. `kafka-reassign-partitions.sh` 는 **3단계**로 씁니다.

먼저 실습용 토픽을 만듭니다. 일부러 **브로커 1, 2에만** 복제본을 두어 불균형을 만듭니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:9092 --create --topic s14_move \
  --partitions 3 --replica-assignment 1:2,1:2,1:2
```

**결과**

```
Created topic s14_move.
```

```bash
kt --describe --topic s14_move
```

**결과**

```
Topic: s14_move	TopicId: Lp8qWzXcQe2rT5yUiOpAsD	PartitionCount: 3	ReplicationFactor: 2	Configs: segment.bytes=1048576
	Topic: s14_move	Partition: 0	Leader: 1	Replicas: 1,2	Isr: 1,2
	Topic: s14_move	Partition: 1	Leader: 1	Replicas: 1,2	Isr: 1,2
	Topic: s14_move	Partition: 2	Leader: 1	Replicas: 1,2	Isr: 1,2
```

**kafka-3 이 완전히 놀고 있고, 리더가 전부 kafka-1 에 몰렸습니다.** 데이터를 조금 넣어 둡니다(이동할 실체가 있어야 합니다).

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s14_move --num-records 30000 --record-size 200 \
  --throughput -1 --producer-props bootstrap.servers=kafka-1:9092
```

**결과**

```
30000 records sent, 44776.119403 records/sec (8.54 MB/sec), 96.83 ms avg latency, 288.00 ms max latency, 88 ms 50th, 241 ms 95th, 277 ms 99th, 286 ms 99.9th.
```

### ① `--generate` — 후보안 만들기

옮길 토픽 목록을 JSON 으로 씁니다.

```bash
docker exec kafka-1 sh -c 'cat > /tmp/topics-to-move.json <<EOF
{"topics": [{"topic": "s14_move"}], "version": 1}
EOF'
```

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-reassign-partitions.sh \
  --bootstrap-server kafka-1:9092 \
  --topics-to-move-json-file /tmp/topics-to-move.json \
  --broker-list "1,2,3" \
  --generate
```

**결과**

```
Current partition replica assignment
{"version":1,"partitions":[{"topic":"s14_move","partition":0,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":1,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":2,"replicas":[1,2],"log_dirs":["any","any"]}]}

Proposed partition reassignment configuration
{"version":1,"partitions":[{"topic":"s14_move","partition":0,"replicas":[3,1],"log_dirs":["any","any"]},{"topic":"s14_move","partition":1,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":2,"replicas":[2,3],"log_dirs":["any","any"]}]}
```

**두 블록이 나옵니다.**

- `Current` — **반드시 파일로 저장하십시오.** 롤백용입니다.
- `Proposed` — 실행할 안. 브로커 3이 포함되어 부하가 분산되었습니다.

> 💡 **실무 팁 — `Current` 를 저장하지 않으면 롤백할 수 없습니다**
> `--generate` 는 매번 **다른 결과**를 낼 수 있습니다(무작위 시드 사용). 그래서 나중에 다시 실행해 원래 배치를 복원할 수 없습니다.
> **재할당 전에 반드시 `Current` 블록을 `rollback.json` 으로 저장**하십시오. 이것 하나로 사고 시 복구 시간이 갈립니다.

```bash
docker exec kafka-1 sh -c 'cat > /tmp/rollback.json <<EOF
{"version":1,"partitions":[{"topic":"s14_move","partition":0,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":1,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":2,"replicas":[1,2],"log_dirs":["any","any"]}]}
EOF'

docker exec kafka-1 sh -c 'cat > /tmp/reassign.json <<EOF
{"version":1,"partitions":[{"topic":"s14_move","partition":0,"replicas":[3,1],"log_dirs":["any","any"]},{"topic":"s14_move","partition":1,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":2,"replicas":[2,3],"log_dirs":["any","any"]}]}
EOF'
```

### ② `--execute` — 실행 (스로틀과 함께)

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-reassign-partitions.sh \
  --bootstrap-server kafka-1:9092 \
  --reassignment-json-file /tmp/reassign.json \
  --throttle 1048576 \
  --execute
```

**결과**

```
Current partition replica assignment

{"version":1,"partitions":[{"topic":"s14_move","partition":0,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":1,"replicas":[1,2],"log_dirs":["any","any"]},{"topic":"s14_move","partition":2,"replicas":[1,2],"log_dirs":["any","any"]}]}

Save this to use as the --reassignment-json-file option during rollback
Warning: You must run --verify periodically, until the reassignment completes, to ensure the throttle is removed.
Successfully started partition reassignments for s14_move-0,s14_move-1,s14_move-2
```

경고 문구를 눈여겨보십시오. **"`--verify` 를 주기적으로 실행해야 스로틀이 제거된다"** 고 명시하고 있습니다. 이것이 이 절의 함정입니다.

`--throttle 1048576` 은 초당 1 MiB 로 복제 대역폭을 제한합니다. 재할당은 **대량의 데이터를 복사**하므로, 제한 없이 돌리면 정상 트래픽의 대역폭과 디스크 I/O 를 잡아먹어 서비스 지연이 튑니다.

### ③ `--verify` — 완료 확인 (그리고 스로틀 해제)

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-reassign-partitions.sh \
  --bootstrap-server kafka-1:9092 \
  --reassignment-json-file /tmp/reassign.json \
  --verify
```

**결과** (진행 중)

```
Status of partition reassignment:
Reassignment of partition s14_move-0 is still in progress.
Reassignment of partition s14_move-1 is completed.
Reassignment of partition s14_move-2 is still in progress.

Clearing broker-level throttles on brokers 1,2,3
Throttle was removed.
```

잠시 뒤 다시 실행합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-reassign-partitions.sh \
  --bootstrap-server kafka-1:9092 \
  --reassignment-json-file /tmp/reassign.json --verify
```

**결과** (완료)

```
Status of partition reassignment:
Reassignment of partition s14_move-0 is completed.
Reassignment of partition s14_move-1 is completed.
Reassignment of partition s14_move-2 is completed.

Clearing broker-level throttles on brokers 1,2,3
Throttle was removed.
```

결과를 확인합니다.

```bash
kt --describe --topic s14_move
```

**결과**

```
Topic: s14_move	TopicId: Lp8qWzXcQe2rT5yUiOpAsD	PartitionCount: 3	ReplicationFactor: 2	Configs: segment.bytes=1048576
	Topic: s14_move	Partition: 0	Leader: 3	Replicas: 3,1	Isr: 1,3
	Topic: s14_move	Partition: 1	Leader: 1	Replicas: 1,2	Isr: 1,2
	Topic: s14_move	Partition: 2	Leader: 2	Replicas: 2,3	Isr: 2,3
```

리더가 3, 1, 2 로 분산되었고 kafka-3 도 일을 합니다.

> ⚠️ **함정 — `--verify` 를 안 돌리면 스로틀이 영원히 남습니다**
> 스로틀은 재할당이 끝나도 **자동으로 해제되지 않습니다.** `--verify` 가 해제해 줍니다. 안 돌리면 `leader.replication.throttled.rate` / `follower.replication.throttled.rate` 가 브로커 설정에 남아, **그 이후의 모든 복제가 1 MiB/s 로 제한됩니다.**
> 증상이 지독합니다. 평소에는 멀쩡한데, 브로커를 재시작하거나 장애가 나서 **복제를 따라잡아야 할 때** 한없이 느립니다. under-replicated 가 몇 시간씩 안 풀립니다. 에러 로그는 없습니다.
> **확인 방법**:
> ```bash
> kconf --describe --entity-type brokers --entity-name 1 | grep throttled
> ```
> **결과** (스로틀이 남아 있는 경우)
> ```
>   leader.replication.throttled.rate=1048576 sensitive=false synonyms={DYNAMIC_BROKER_CONFIG:leader.replication.throttled.rate=1048576}
>   follower.replication.throttled.rate=1048576 sensitive=false synonyms={DYNAMIC_BROKER_CONFIG:follower.replication.throttled.rate=1048576}
> ```
> **수동 해제**:
> ```bash
> for b in 1 2 3; do
>   kconf --alter --entity-type brokers --entity-name $b \
>     --delete-config leader.replication.throttled.rate,follower.replication.throttled.rate
> done
> ```
> 토픽 쪽에도 `leader.replication.throttled.replicas` 가 남을 수 있으니 함께 확인하십시오.

### preferred leader 로 되돌리기

재할당 직후에는 리더가 `Replicas` 목록의 첫 번째가 아닐 수 있습니다. 위 결과에서 파티션 0은 `Replicas: 3,1` 이고 `Leader: 3` 이라 맞지만, 브로커 재시작 후에는 어긋나는 일이 흔합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-leader-election.sh \
  --bootstrap-server kafka-1:9092 \
  --election-type preferred --all-topic-partitions
```

**결과**

```
Successfully completed leader election (PREFERRED) for partitions s14_move-0, s14_move-1, s14_move-2, orders-0, orders-1, orders-2, payments-0, payments-1, payments-2, order-events-0, order-events-1, order-events-2, dlq-0
```

이미 preferred 인 파티션이 대부분이면 이렇게 나옵니다.

```
Valid replica already elected for partitions
```

> 💡 **실무 팁 — `auto.leader.rebalance.enable` 은 기본 true 입니다**
> 브로커는 `leader.imbalance.check.interval.seconds`(기본 300초)마다 불균형을 검사하고, `leader.imbalance.per.broker.percentage`(기본 10%)를 넘으면 **자동으로** preferred election 을 수행합니다.
> 그래서 대개는 수동으로 할 필요가 없습니다. 다만 자동 실행은 **예측 불가능한 시점**에 리더를 옮기므로, 트래픽이 민감한 클러스터는 이 설정을 끄고 정해진 시간에 수동으로 수행하기도 합니다.

### 브로커 추가와 제거 — 재할당이 실제로 쓰이는 자리

재할당 명령을 배우는 진짜 이유는 브로커 대수를 바꿀 때입니다. **두 절차의 순서가 정확히 반대**라는 점이 핵심입니다.

**브로커 추가** — 브로커를 먼저 넣고, 파티션을 나중에 옮깁니다.

```
① 새 브로커를 클러스터에 조인 (node.id 새로 부여, controller.quorum.voters 갱신)
② kafka-broker-api-versions.sh 로 인식되는지 확인
③ --generate --broker-list "1,2,3,4" 로 재배치 후보 생성
④ ★ Proposed JSON 을 눈으로 검토 — RF 가 유지되는가, 한 브로커에 몰리지 않는가
⑤ --execute --throttle
⑥ --verify 를 "completed" 가 전부 나올 때까지
⑦ preferred leader election 으로 리더도 고르게
```

②의 확인 명령입니다.

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

**새 브로커를 넣어도 기존 파티션은 저절로 옮겨 오지 않습니다.** 새로 만드는 토픽만 새 브로커를 씁니다. ③~⑥을 하지 않으면 새 브로커는 몇 달이고 놀고 있습니다. "브로커를 늘렸는데 부하가 안 줄었다"의 원인이 대개 이것입니다.

**브로커 제거** — 파티션을 먼저 빼고, 브로커를 나중에 내립니다.

```
① 그 브로커에 어떤 파티션이 있는지 확인
② --generate --broker-list "남길 브로커들" 로 후보 생성
③ ★ RF 가 유지되는지 확인 (아래 함정)
④ --execute --throttle
⑤ --verify 완료까지
⑥ 그 브로커에 파티션이 0 개인지 재확인
⑦ 그제서야 브로커 종료 → KRaft 라면 controller.quorum.voters 에서도 제거
```

①의 확인 명령입니다. 브로커 3 을 빼려 한다면:

```bash
kt --describe | grep -E 'Replicas:.*\b3\b' | head -5
```

**결과**

```
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: orders	Partition: 1	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: orders	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: payments	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: payments	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
```

⑥의 재확인은 같은 명령이 **빈 출력**을 내면 통과입니다.

> ⚠️ **함정 — `--generate` 는 RF 를 유지해 주지 않습니다**
> 브로커 3 을 빼려고 `--broker-list "1,2"` 를 주면 Kafka 는 "쓸 수 있는 브로커가 2대"라고 판단하고 **RF 3 짜리 토픽을 RF 2 로 줄인 초안**을 내놓습니다.
> ```
> Proposed partition reassignment configuration
> {"version":1,"partitions":[{"topic":"s14_move","partition":0,"replicas":[2,1],"log_dirs":["any","any"]}, ...]}
> ```
> `replicas` 배열의 길이가 **3에서 2로 줄어든 것**을 놓치기 쉽습니다. 그대로 실행하면 `min.insync.replicas=2` 인 토픽이 **브로커 한 대만 죽어도 쓰기 불가**가 됩니다.
> ```
> org.apache.kafka.common.errors.NotEnoughReplicasException: The size of the current ISR Set(1) is insufficient to satisfy the min.isr requirement of 2 for partition orders-0
> ```
> **`--generate` 는 초안 생성기일 뿐입니다.** 운영에서는 출력을 그대로 쓰지 않고 `replicas` 배열을 손으로 고쳐 씁니다. 브로커를 정말 3대에서 2대로 줄이려면 RF 도 함께 내리겠다는 **의식적인 결정**이 있어야 하고, 그때는 `min.insync.replicas` 도 같이 조정해야 합니다.

> ⚠️ **함정 — 브로커를 먼저 내리면 되돌릴 수 없습니다**
> 파티션을 안 빼고 브로커를 내려도 클러스터는 계속 동작합니다. 그 브로커가 리더였던 파티션은 다른 복제본으로 리더가 넘어가니까요. **그래서 문제가 없어 보입니다.**
> 하지만 RF 3 이던 파티션이 사실상 RF 2 로 동작하게 되고, 여기서 브로커 하나가 더 죽으면 ISR 이 1 이 되어 **쓰기가 즉시 막힙니다.** 게다가 이미 내린 브로커의 디스크를 지웠다면 되돌릴 방법도 없습니다.
> **원칙은 하나입니다: 제거는 "파티션 먼저, 브로커 나중". 추가는 그 반대.**

---

## 14-2. 무중단 롤링 재시작

설정 변경이나 버전 업그레이드를 위해 브로커를 한 대씩 재시작하는 절차입니다. **순서를 지키지 않으면 데이터를 잃습니다.**

### 절차

```
① 사전 점검      under-replicated == 0 확인
       ↓          (여기서 0이 아니면 절대 시작하지 말 것)
② 한 대 정지     controlled shutdown 으로 리더를 넘기고 종료
       ↓
③ 재시작         healthy 대기
       ↓
④ 복구 확인      under-replicated 가 다시 0이 될 때까지 대기
       ↓
⑤ 리더 복원      preferred leader election
       ↓
      다음 브로커로 (①로)
```

### 실행

```bash
# ① 사전 점검 — 0이어야 진행
kt --describe --under-replicated-partitions | wc -l
```

**결과**

```
0
```

```bash
# ② kafka-2 정지
docker compose stop kafka-2
```

**결과**

```
[+] Stopping 1/1
 ✔ Container kafka-2  Stopped                                       6.4s
```

6.4초 걸렸습니다. 이것이 **controlled shutdown** 입니다. 브로커는 종료 신호를 받으면 즉시 죽지 않고, 자기가 리더인 파티션들의 리더십을 다른 브로커에 넘긴 뒤 종료합니다. 브로커 로그에 이렇게 남습니다.

```
[2024-03-11 14:22:08,114] INFO [KafkaServer id=2] Starting controlled shutdown (kafka.server.KafkaServer)
[2024-03-11 14:22:08,441] INFO [KafkaServer id=2] Controlled shutdown request returned successfully after 327ms (kafka.server.KafkaServer)
```

> ⚠️ **함정 — `docker kill` 이나 `kill -9` 는 controlled shutdown 을 건너뜁니다**
> 강제 종료하면 리더십을 넘기지 못하고 죽습니다. 그 브로커가 리더였던 파티션들은 **리더가 없는 상태**가 되고, 컨트롤러가 새 리더를 선출할 때까지(수 초) 읽기·쓰기가 **완전히 멈춥니다.**
> 게다가 `recovery-point-offset-checkpoint` 가 갱신되지 않아, 재시작 시 브로커가 **로그 복구 검사**를 수행하느라 기동이 몇 분씩 걸립니다(파티션이 많을수록 오래).
> **원칙**: 항상 `SIGTERM`(= `docker stop`, `docker compose stop`)으로 종료하십시오. `controlled.shutdown.enable` 은 기본 `true` 이고 절대 끄지 마십시오.

```bash
# 정지 중 상태 확인 — under-replicated 가 나타납니다
kt --describe --under-replicated-partitions
```

**결과**

```
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,3
	Topic: orders	Partition: 1	Leader: 3	Replicas: 2,3,1	Isr: 3,1
	Topic: orders	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1
	Topic: payments	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,3
	...
```

`Isr` 에서 2가 빠졌습니다. **정상적인 과정**입니다. 파티션 1의 리더가 2에서 3으로 넘어간 것도 보입니다.

```bash
# ③ 재시작
docker compose start kafka-2

# healthy 대기
until [ "$(docker inspect -f '{{.State.Health.Status}}' kafka-2)" = "healthy" ]; do
  sleep 2
done
echo "kafka-2 healthy"
```

**결과**

```
[+] Running 1/1
 ✔ Container kafka-2  Started                                       0.5s
kafka-2 healthy
```

```bash
# ④ 복구 확인 — 0이 될 때까지 대기
until [ "$(kt --describe --under-replicated-partitions | wc -l)" -eq 0 ]; do
  echo "복제 따라잡는 중... $(kt --describe --under-replicated-partitions | wc -l) 파티션 남음"
  sleep 3
done
echo "복제 완료"
```

**결과**

```
복제 따라잡는 중... 13 파티션 남음
복제 따라잡는 중... 7 파티션 남음
복제 따라잡는 중... 2 파티션 남음
복제 완료
```

```bash
# ⑤ 리더 복원
docker exec kafka-1 /opt/kafka/bin/kafka-leader-election.sh \
  --bootstrap-server kafka-1:9092 --election-type preferred --all-topic-partitions
```

**여기까지가 브로커 한 대**입니다. kafka-3, kafka-1 순으로 반복합니다.

> ⚠️ **함정 — ④ 를 건너뛰고 다음 브로커를 내리면 데이터를 잃습니다**
> kafka-2 가 아직 복제를 따라잡는 중인데 kafka-3 을 내리면, ISR 이 **1개**로 줄어듭니다.
> - `min.insync.replicas=2` 라면 → 프로듀서가 `NotEnoughReplicasException` 으로 거부됩니다. 서비스 장애지만 **데이터는 안전합니다.**
> - `min.insync.replicas=1` 이라면 → **쓰기가 성공합니다.** 그리고 남은 그 한 대가 죽으면 그 데이터는 사라집니다.
> ④ 의 대기 루프는 형식적인 절차가 아니라 **데이터 보호 장치**입니다. 자동화 스크립트에서 이 대기를 빼는 것이 롤링 재시작 사고의 가장 흔한 원인입니다.

---

## 14-3. JMX 지표 — 무엇을 봐야 하는가

이 클러스터는 JMX 포트를 열어 두었습니다(`19999`/`29999`/`39999`). 브로커 안에서 `JmxTool` 로 읽습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-run-class.sh kafka.tools.JmxTool \
  --object-name 'kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions' \
  --jmx-url service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi \
  --one-time
```

**결과**

```
Trying to connect to JMX url: service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi.
"time","kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions:Value"
1710165728114,0
```

`Value` 가 `0` 입니다. 정상입니다.

여러 지표를 한 번에 볼 수도 있습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-run-class.sh kafka.tools.JmxTool \
  --object-name 'kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec' \
  --jmx-url service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi \
  --reporting-interval 2000 --one-time
```

**결과**

```
"time","kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec:Count","kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec:FifteenMinuteRate","kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec:FiveMinuteRate","kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec:MeanRate","kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec:OneMinuteRate"
1710165741882,18442096,41883.2,88412.7,14028.4,102841.5
```

### 반드시 감시해야 할 9개 지표

| 지표 (MBean) | 정상값 | 벗어나면 | 심각도 |
|---|---|---|---|
| `ReplicaManager,name=UnderReplicatedPartitions` | **0** | 복제 지연 또는 브로커 다운 | 높음 |
| `ReplicaManager,name=UnderMinIsrPartitionCount` | **0** | `acks=all` 쓰기가 **거부되는 중** | 매우 높음 |
| `KafkaController,name=OfflinePartitionsCount` | **0** | 리더 없는 파티션 = **읽기·쓰기 불가** | 치명 |
| `KafkaController,name=ActiveControllerCount` | 클러스터 합 **1** | 0이면 컨트롤러 없음, 2 이상이면 split-brain | 치명 |
| `KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent` | **> 0.3** | I/O 스레드 포화 → `num.io.threads` 증설 | 높음 |
| `SocketServer,name=NetworkProcessorAvgIdlePercent` | **> 0.3** | 네트워크 스레드 포화 → `num.network.threads` 증설 | 높음 |
| `BrokerTopicMetrics,name=BytesInPerSec` / `BytesOutPerSec` | 베이스라인 대비 | 급증·급감 모두 신호 | 정보 |
| `ReplicaManager,name=IsrShrinksPerSec` | **0에 가깝게** | 반복되면 브로커 불안정·GC·디스크 문제 | 중간 |
| 컨슈머 `consumer-fetch-manager-metrics,records-lag-max` | SLO 이내 | 랙 급증 → [Step 11](../step-11-performance/) 플레이북 | 높음 |

`ActiveControllerCount` 를 세 브로커에서 각각 재 보면 KRaft 의 구조가 보입니다.

```bash
for p in 19999 29999 39999; do
  echo -n "port $p: "
  docker exec kafka-1 /opt/kafka/bin/kafka-run-class.sh kafka.tools.JmxTool \
    --object-name 'kafka.controller:type=KafkaController,name=ActiveControllerCount' \
    --jmx-url "service:jmx:rmi:///jndi/rmi://host.docker.internal:$p/jmxrmi" \
    --one-time 2>/dev/null | tail -1
done
```

**결과**

```
port 19999: 1710165801221,0
port 29999: 1710165802447,1
port 39999: 1710165803662,0
```

**합이 정확히 1** 입니다. kafka-2 가 액티브 컨트롤러입니다([Step 02](../step-02-architecture/) 의 `quorum-state` 와 일치합니다).

> ⚠️ **함정 — `ActiveControllerCount` 합계가 2 이상이면 즉시 대응해야 합니다**
> 네트워크 분단으로 두 컨트롤러가 각자 자기가 리더라고 믿는 상태(split-brain)입니다. 메타데이터가 갈라지고, 복구 후 한쪽 변경이 통째로 버려집니다.
> KRaft 는 Raft 쿼럼(과반수)으로 이것을 구조적으로 막지만, **투표자 수가 짝수이거나** 설정이 어긋나면 발생할 수 있습니다. `controller.quorum.voters` 는 반드시 **홀수**(3 또는 5)로 두십시오.

### 디스크 사용량

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-log-dirs.sh \
  --bootstrap-server kafka-1:9092 --describe --topic-list s14_move
```

**결과**

```
Querying brokers for log directories information
Received log directory information from brokers 1,2,3
{"version":1,"brokers":[{"broker":1,"logDirs":[{"logDir":"/var/lib/kafka/data","error":null,"partitions":[{"partition":"s14_move-0","size":2098176,"offsetLag":0,"isFuture":false},{"partition":"s14_move-1","size":2101248,"offsetLag":0,"isFuture":false}]}]},{"broker":2,"logDirs":[{"logDir":"/var/lib/kafka/data","error":null,"partitions":[{"partition":"s14_move-1","size":2101248,"offsetLag":0,"isFuture":false},{"partition":"s14_move-2","size":2095104,"offsetLag":0,"isFuture":false}]}]},{"broker":3,"logDirs":[{"logDir":"/var/lib/kafka/data","error":null,"partitions":[{"partition":"s14_move-0","size":2098176,"offsetLag":0,"isFuture":false},{"partition":"s14_move-2","size":2095104,"offsetLag":0,"isFuture":false}]}]}]}
```

`offsetLag: 0` 이면 그 복제본이 리더를 따라잡았다는 뜻입니다. **재할당 진행 상황을 정확히 보는 방법**이기도 합니다.

> 💡 **실무 팁 — 디스크가 가득 차면 브로커는 죽습니다**
> Kafka 는 디스크 부족을 우아하게 처리하지 않습니다. 로그 디렉터리에 쓸 수 없으면 해당 로그 디렉터리를 **오프라인으로 표시**하고, 그 안의 모든 파티션이 사용 불가가 됩니다.
> **예방**: 디스크 사용률 **70%** 에서 경보를 걸고, `retention.bytes` 로 상한을 명시하십시오. `retention.ms` 만으로는 트래픽이 급증하면 막을 수 없습니다.
> **급할 때**: `retention.ms` 를 일시적으로 낮춰 브로커가 스스로 지우게 하십시오. **절대 `rm` 하지 마십시오**([Step 02](../step-02-architecture/)).

---

## 14-4. 장애 플레이북

증상에서 출발해 조치까지 가는 표입니다. 운영 문서에 그대로 옮겨 쓸 수 있게 구성했습니다.

| # | 증상 | 원인 후보 | 진단 명령 | 조치 |
|---|---|---|---|---|
| 1 | **컨슈머 랙 급증** | 처리 지연 / 파티션 부족 / 리밸런싱 반복 / 브로커 병목 | `kcg --describe --group G` 를 30초 간격 2회 → 랙 증가 속도 | 파티션 ≥ 컨슈머면 컨슈머 증설. 아니면 파티션부터([Step 03](../step-03-topics-partitions/), [Step 11](../step-11-performance/)) |
| 2 | **under-replicated 발생** | 브로커 다운 / 네트워크 / 디스크 느림 / **스로틀 잔존** | `kt --describe --under-replicated-partitions`<br>`kconf --describe --entity-type brokers --entity-name N \| grep throttled` | 브로커 복구. 스로틀 남았으면 `--delete-config` (14-1) |
| 3 | **OfflinePartitions > 0** | ISR 전멸 / 로그 디렉터리 오프라인 | `kt --describe --unavailable-partitions`<br>브로커 로그에서 `offline` 검색 | 죽은 브로커 복구가 최우선. 불가하면 unclean 선출 판단([Step 08](../step-08-replication/)) |
| 4 | **리밸런싱 반복** | `max.poll.interval.ms` 초과 / 컨슈머 OOM / 네트워크 | 컨슈머 로그에서 `poll timeout has expired` 검색<br>`kcg --describe --state` 의 `STATE` | `max.poll.records` 축소 또는 `max.poll.interval.ms` 증대([Step 05](../step-05-consumer/)) |
| 5 | **프로듀서 타임아웃** | 리더 없음 / ISR 부족 / 네트워크 / 버퍼 포화 | 예외 종류 확인: `TimeoutException` vs `NotEnoughReplicasException` | 후자면 ISR 회복이 먼저. 전자면 `delivery.timeout.ms`·버퍼 점검([Step 04](../step-04-producer/)) |
| 6 | **디스크 가득** | 보존 정책 부재 / 트래픽 급증 / 압축 미동작 | `kafka-log-dirs.sh --describe`<br>`df -h` | `retention.ms` 일시 축소 → 삭제 확인 → 원복. **`rm` 금지** |
| 7 | **ISR 축소 반복** | GC 정지 / 디스크 I/O 포화 / `replica.lag.time.max.ms` 가 너무 짧음 | `IsrShrinksPerSec` 지표<br>브로커 로그 `Shrinking ISR` | GC 튜닝, 디스크 교체, `num.replica.fetchers` 증설 |
| 8 | **브로커 OOM** | 힙 과소/과대 / 파티션 과다 / 큰 메시지 | 컨테이너 로그 `OutOfMemoryError`<br>`docker stats` | 힙은 6 GiB 안팎 유지([Step 02](../step-02-architecture/)). 파티션 수 재검토 |
| 9 | **컨트롤러 없음** | 쿼럼 과반 상실 | `kafka-metadata-quorum.sh describe --status` | 투표자 과반이 살아야 함. 3대 중 2대 이상 복구 |
| 10 | **토픽이 안 지워짐** | `delete.topic.enable=false` / 삭제 진행 중 | `kconf --describe --entity-type brokers --entity-name 1 \| grep delete.topic`<br>`ls /var/lib/kafka/data \| grep -- -delete` | 설정 확인. 진행 중이면 `file.delete.delay.ms` 만큼 대기([Step 03](../step-03-topics-partitions/)) |
| 11 | **메시지가 조용히 사라짐** | `acks=0/1` / `min.insync.replicas=1` / auto commit | 프로듀서 설정 감사, `kcg --describe` 의 오프셋 점프 | [Step 04](../step-04-producer/)·[Step 06](../step-06-offsets/)·[Step 08](../step-08-replication/) 의 방어 설정 적용 |
| 12 | **같은 키 순서 뒤바뀜** | 파티션 증가 / `max.in.flight>1`+재시도 | 그 키의 파티션 분포 확인<br>프로듀서 `enable.idempotence` 확인 | `enable.idempotence=true`. 파티션 증가는 되돌릴 수 없음([Step 03](../step-03-topics-partitions/), [Step 04](../step-04-producer/)) |

---

## 14-5. ACL — 권한 (개념)

이 클러스터는 인증이 없는 PLAINTEXT 이므로 ACL 을 실제로 적용하지는 않습니다. 형태만 익혀 둡니다.

```bash
# 특정 사용자에게 orders 토픽 쓰기 권한
docker exec kafka-1 /opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka-1:9092 \
  --add --allow-principal User:order-api \
  --operation Write --topic orders
```

```bash
# 컨슈머 그룹까지 포함한 읽기 권한
docker exec kafka-1 /opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka-1:9092 \
  --add --allow-principal User:order-processor \
  --operation Read --topic orders \
  --group order-processor
```

```bash
# 전체 조회
docker exec kafka-1 /opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka-1:9092 --list
```

> 💡 **실무 팁 — 컨슈머는 토픽 권한만으로는 못 읽습니다**
> 컨슈머 그룹을 쓰려면 **그룹 리소스에 대한 `Read` 권한**이 별도로 필요합니다. 토픽 권한만 주고 "왜 안 되지?" 하는 것이 ACL 도입 초기의 단골 문제입니다.
> 트랜잭션을 쓴다면 `TransactionalId` 리소스 권한도 필요합니다([Step 07](../step-07-delivery-semantics/)).

---

## 14-6. 종합 실습 — 주문 이벤트 파이프라인

이 코스에서 배운 것을 하나로 묶습니다. **요구사항부터 검증까지** 전 과정을 수행합니다.

### 요구사항

| # | 요구사항 | 관련 스텝 |
|---|---|---|
| R1 | 주문 이벤트는 **절대 유실되면 안 된다** | 04, 08 |
| R2 | 같은 고객의 주문은 **순서가 보장**되어야 한다 | 03, 04 |
| R3 | 중복 처리는 허용하되, **비즈니스적으로 멱등**해야 한다 | 06, 07 |
| R4 | 처리 실패 메시지는 **DLQ 로 격리**하고 원인을 남긴다 | 06, 12 |
| R5 | 주문의 **최신 상태**를 언제든 조회할 수 있어야 한다 | 09 |
| R6 | 브로커 1대가 죽어도 **서비스가 계속**되어야 한다 | 08 |
| R7 | 컨슈머 랙을 **상시 감시**할 수 있어야 한다 | 05, 11, 14 |

### 단계 ① — 토픽 설계

| 토픽 | 파티션 | RF | min.insync | cleanup | retention | 근거 |
|---|---:|---:|---:|---|---|---|
| `orders` | 3 | 3 | **2** | delete | 7일 | R1(RF3+minISR2), R2(key=customer_id), R6 |
| `payments` | 3 | 3 | **2** | delete | 7일 | R1, R6 |
| `order-events` | 3 | 3 | **2** | **compact** | — | R5(키별 최신 상태 영구 보존) |
| `dlq` | 1 | 3 | **2** | delete | 30일 | R4(순서대로 조사해야 하므로 파티션 1개, 조사 기간 확보로 30일) |

핵심 판단 두 가지를 명시합니다.

- **`min.insync.replicas=2` 인 이유**: RF 3에 minISR 2면 **브로커 1대가 죽어도 쓰기가 계속**되고(R6), 2대가 죽으면 **쓰기를 거부해 유실을 막습니다**(R1). RF 3 + minISR 3 이면 1대만 죽어도 서비스가 멈추고, minISR 1 이면 유실 위험이 생깁니다. **2가 유일한 균형점**입니다.
- **`dlq` 파티션이 1개인 이유**: DLQ 는 처리량이 아니라 **조사 편의**가 목적입니다. 파티션이 여러 개면 시간 순서대로 훑기가 어렵습니다.

```bash
kt --describe --topic orders | head -1
kt --describe --topic order-events | head -1
kt --describe --topic dlq | head -1
```

**결과**

```
Topic: orders	TopicId: fH9wKlOpQrT4uYiAsDfGhJ	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
Topic: order-events	TopicId: RtY7uIoPQvW3xZaBcDeFgH	PartitionCount: 3	ReplicationFactor: 3	Configs: min.cleanable.dirty.ratio=0.01,cleanup.policy=compact,segment.ms=10000,min.insync.replicas=2,segment.bytes=1048576
Topic: dlq	TopicId: 8Kx2vNqTQmS0aWpLdHfGjQ	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=2592000000
```

### 단계 ② — 프로듀서 설정 확정

| 설정 | 값 | 근거 |
|---|---|---|
| `acks` | `all` | R1. `0`/`1` 은 리더 장애 시 유실([Step 04](../step-04-producer/), [Step 08](../step-08-replication/)) |
| `enable.idempotence` | `true` | R2+R3. 재시도해도 중복 배치가 안 생기고, **순서도 보장**([Step 07](../step-07-delivery-semantics/)) |
| `max.in.flight.requests.per.connection` | `5` | 멱등성이 켜져 있으면 5까지 순서 보장. 1로 낮추면 처리량만 손해 |
| `retries` | `Integer.MAX_VALUE` | 멱등성이 강제하는 값. `delivery.timeout.ms` 가 실질 상한 |
| `delivery.timeout.ms` | `120000` | 이 시간 안에 성공 못 하면 포기하고 예외. **애플리케이션이 반드시 잡아야 함** |
| `linger.ms` | `10` | R1을 해치지 않으면서 배치 효율 확보([Step 11](../step-11-performance/)) |
| `compression.type` | `lz4` | 대역폭 절감 대비 CPU 비용이 가장 균형적 |
| `key` | `customer_id` | R2. 같은 고객 = 같은 파티션 |

**주의**: `enable.idempotence=true` 는 3.0부터 기본값이지만, `acks` 를 명시적으로 `1` 로 두면 다음 예외로 기동이 실패합니다.

```
org.apache.kafka.common.config.ConfigException: Must set acks to all in order to use the idempotent producer. Otherwise we cannot guarantee idempotence.
```

**이 에러는 좋은 것입니다.** 설정 모순을 기동 시점에 잡아 줍니다.

### 단계 ③ — 컨슈머 설계

| 설정 | 값 | 근거 |
|---|---|---|
| `group.id` | `order-processor` | |
| `enable.auto.commit` | **`false`** | R1+R3. auto commit 은 유실과 중복을 **동시에** 만듦([Step 06](../step-06-offsets/)) |
| `auto.offset.reset` | `earliest` | 새 그룹이 과거 메시지를 조용히 건너뛰는 것을 방지([Step 06](../step-06-offsets/)) |
| `isolation.level` | `read_committed` | 트랜잭션 프로듀서를 쓸 경우([Step 07](../step-07-delivery-semantics/)) |
| `max.poll.records` | `100` | 처리 시간이 `max.poll.interval.ms` 를 넘지 않도록([Step 05](../step-05-consumer/)) |
| `partition.assignment.strategy` | `CooperativeStickyAssignor` | 리밸런싱 시 stop-the-world 회피([Step 05](../step-05-consumer/)) |

처리 루프의 계약은 이렇습니다.

```
poll()
  → 각 레코드마다:
      try   : 비즈니스 처리 (order_id 로 멱등하게)
      catch : dlq 로 전송 (원인 헤더 포함) — 예외를 삼키되 기록
  → 전부 끝난 뒤 commitSync()      ← 처리 후 커밋 = at-least-once
```

**처리 후 커밋**이므로 중복이 생길 수 있습니다(R3). 그래서 비즈니스 처리가 `order_id` 기준으로 멱등해야 합니다. 이것이 [Step 07](../step-07-delivery-semantics/) 의 결론 — **"exactly-once 는 Kafka 내부 한정이고, 외부 시스템까지 포함하면 at-least-once + 멱등 처리가 현실적인 답"** — 을 적용한 것입니다.

### 단계 ④ — 파이프라인 가동

주문을 흘려 넣습니다.

```bash
for i in $(seq 1001 1030); do
  c=$(printf "C%03d" $(( (i % 10) + 1 )))
  echo "$c:{\"order_id\":\"O-$i\",\"customer_id\":\"$c\",\"amount\":$(( (i * 137) % 200000 )),\"status\":\"CREATED\"}"
done | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --property parse.key=true --property key.separator=: \
  --producer-property acks=all \
  --producer-property enable.idempotence=true \
  --producer-property compression.type=lz4 \
  --producer-property linger.ms=10
```

들어간 건수를 확인합니다.

```bash
koff --topic orders
```

**결과**

```
orders:0:11
orders:1:9
orders:2:10
```

합 30건입니다.

### 단계 ⑤ — 장애 주입

**(가) 브로커 1대 정지** — R6 검증

```bash
docker compose stop kafka-2
kt --describe --topic orders
```

**결과**

```
Topic: orders	TopicId: fH9wKlOpQrT4uYiAsDfGhJ	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,3
	Topic: orders	Partition: 1	Leader: 3	Replicas: 2,3,1	Isr: 3,1
	Topic: orders	Partition: 2	Leader: 3	Replicas: 3,1,2	Isr: 3,1
```

ISR 이 2개로 줄었지만 `min.insync.replicas=2` 를 **여전히 만족**합니다. 쓰기를 시도합니다.

```bash
echo 'C001:{"order_id":"O-9001","customer_id":"C001","amount":50000,"status":"CREATED"}' \
| docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --property parse.key=true --property key.separator=: \
  --producer-property acks=all --producer-property enable.idempotence=true
```

**결과** (에러 없이 성공)

```
```

**R6 충족.** 브로커 1대가 죽어도 서비스가 계속됩니다.

한 대 더 죽이면 어떻게 될까요?

```bash
docker compose stop kafka-3
echo 'C001:{"order_id":"O-9002","customer_id":"C001","amount":60000,"status":"CREATED"}' \
| docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --property parse.key=true --property key.separator=: \
  --producer-property acks=all --producer-property enable.idempotence=true
```

**결과**

```
[2024-03-11 15:02:41,882] ERROR Error when sending message to topic orders with key: 4 bytes, value: 76 bytes with error: (org.apache.kafka.clients.producer.internals.ErrorLoggingCallback)
org.apache.kafka.common.errors.NotEnoughReplicasException: The size of the current ISR Set(1) is insufficient to satisfy the min.isr requirement of 2 for partition orders-0
```

**R1 충족.** 유실될 수 있는 상황에서 **쓰기를 거부**했습니다. 서비스는 멈췄지만 **데이터는 안전합니다.** 이것이 `min.insync.replicas=2` 를 건 이유입니다.

복구합니다.

```bash
docker compose start kafka-2 kafka-3
until [ "$(kt --describe --under-replicated-partitions | wc -l)" -eq 0 ]; do sleep 3; done
echo "복구 완료"
```

**결과**

```
복구 완료
```

**(나) 잘못된 메시지 1건** — R4 검증

```bash
echo 'C001:NOT_A_JSON' \
| docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic orders \
  --property parse.key=true --property key.separator=:
```

컨슈머는 이 레코드에서 파싱 예외를 만나고 DLQ 로 보내야 합니다. `dlq` 로 옮겨진 것을 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic dlq --from-beginning \
  --property print.key=true --property print.headers=true \
  --timeout-ms 5000 2>/dev/null
```

**결과**

```
dlq.error.class:com.example.OrderParseException,dlq.error.message:Unrecognized token 'NOT_A_JSON',dlq.origin.topic:orders,dlq.origin.partition:1,dlq.origin.offset:31	C001	NOT_A_JSON
```

**R4 충족.** 원인·출처 토픽·파티션·오프셋이 **헤더에** 남았습니다. 이 정보만 있으면 원본을 정확히 찾아 재처리할 수 있습니다.

### 단계 ⑥ — 검증 체크리스트

| # | 검증 항목 | 명령 | 기대 출력 |
|---|---|---|---|
| V1 | 브로커 3대 정상 | `docker compose ps` | 세 줄 모두 `(healthy)` |
| V2 | under-replicated 없음 | `kt --describe --under-replicated-partitions \| wc -l` | `0` |
| V3 | under-min-isr 없음 | `kt --describe --under-min-isr-partitions \| wc -l` | `0` |
| V4 | unavailable 없음 | `kt --describe --unavailable-partitions \| wc -l` | `0` |
| V5 | 스로틀 잔존 없음 | `kconf --describe --entity-type brokers --entity-name 1 \| grep -c throttled` | `0` |
| V6 | 유실 0 | `koff --topic orders` 합계 = 보낸 건수 | 일치 |
| V7 | 컨슈머 랙 0 | `kcg --describe --group order-processor` | 모든 파티션 `LAG` = `0` |
| V8 | DLQ 격리 확인 | `koff --topic dlq` | 실패 건수와 일치 |
| V9 | 압축 토픽 최신성 | `order-events` 를 `--from-beginning` 으로 읽어 키 중복 없음 | 키당 1건 |
| V10 | 리더 균형 | `kt --describe \| grep -o 'Leader: [0-9]'` 분포 | 세 브로커에 고르게 |

랙을 확인합니다.

```bash
kcg --describe --group order-processor
```

**결과**

```
GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                          HOST            CLIENT-ID
order-processor orders          0          12              12              0               consumer-order-processor-1-a3f81c04-7b2e-4d19-9f   /172.18.0.5     consumer-order-processor-1
order-processor orders          1          11              11              0               consumer-order-processor-2-c81d0f37-2a44-4e88-b1   /172.18.0.6     consumer-order-processor-2
order-processor orders          2          10              10              0               consumer-order-processor-3-9e42b715-8c60-4a03-af   /172.18.0.7     consumer-order-processor-3
```

**LAG 이 전부 0**, `CURRENT-OFFSET` = `LOG-END-OFFSET`. V7 충족입니다.

### 단계 ⑦ — 운영 인수인계 문서 템플릿

```markdown
# 주문 이벤트 파이프라인 — 운영 문서

## 구성
- 클러스터: learn-kafka (KRaft, 브로커 3대)
- 토픽: orders / payments / order-events(compact) / dlq
- 컨슈머 그룹: order-processor (인스턴스 3)

## 알람 임계값
| 지표 | 경고 | 심각 | 지속 조건 |
|---|---|---|---|
| UnderReplicatedPartitions | > 0 | > 0 | 5분 지속 |
| UnderMinIsrPartitionCount | > 0 | > 0 | 1분 지속 |
| OfflinePartitionsCount | — | > 0 | 즉시 |
| ActiveControllerCount(합) | ≠ 1 | ≠ 1 | 1분 지속 |
| 컨슈머 랙 (order-processor) | > 10,000 | > 100,000 | 5분 지속 |
| RequestHandlerAvgIdlePercent | < 0.3 | < 0.1 | 10분 지속 |
| 디스크 사용률 | > 70% | > 85% | 즉시 |
| dlq 유입 건수 | > 0 | > 100/시간 | 즉시 |

## 런북
- 랙 급증 → 플레이북 #1
- under-replicated → 플레이북 #2 (스로틀 잔존 여부 반드시 확인)
- 롤링 재시작 → 14-2 절차. **④ 복제 완료 대기를 절대 건너뛰지 말 것**
- 파티션 재할당 → 14-1. **Current 블록을 rollback.json 으로 먼저 저장**

## 절대 하지 말 것
- 세그먼트 파일 `rm`
- `orders` / `order-events` 파티션 수 변경 (키 순서가 깨짐, 되돌릴 수 없음)
- `unclean.leader.election.enable=true`
- `docker kill` / `kill -9` 로 브로커 종료
- 재할당 후 `--verify` 생략
```

---

## 14-7. 코스 전체 요약 — 조용한 실패 12가지

이 코스가 다룬 "에러 없이 잘못 동작하는" 상황을 한자리에 모읍니다.

| # | 증상 | 원인 | 스텝 | 방어 설정 |
|---|---|---|---|---|
| 1 | 컨슈머가 과거 메시지를 안 읽음 | 기본 `auto.offset.reset=latest` | [01](../step-01-setup/), [06](../step-06-offsets/) | `auto.offset.reset=earliest` |
| 2 | 오타 토픽이 저절로 생김 | `auto.create.topics.enable=true` | [01](../step-01-setup/) | `=false` |
| 3 | 같은 키 순서가 깨짐 | **파티션 증가** | [03](../step-03-topics-partitions/) | 파티션을 처음에 넉넉히, 이후 고정 |
| 4 | 복제가 영영 안 따라옴 | `max.message.bytes` 만 올리고 `replica.fetch.max.bytes` 방치 | [03](../step-03-topics-partitions/) | 4곳을 함께 조정 |
| 5 | 보낸 메시지가 사라짐 | `acks=0` / `acks=1` + 리더 장애 | [04](../step-04-producer/), [08](../step-08-replication/) | `acks=all` |
| 6 | 같은 키 순서가 뒤바뀜 | `max.in.flight>1` + 재시도 | [04](../step-04-producer/) | `enable.idempotence=true` |
| 7 | 컨슈머를 늘려도 안 빨라짐 | 컨슈머 수 > 파티션 수 | [05](../step-05-consumer/) | 파티션 ≥ 컨슈머 |
| 8 | 처리 안 한 메시지가 유실 | `enable.auto.commit=true` | [06](../step-06-offsets/) | `=false` + 처리 후 `commitSync()` |
| 9 | 같은 메시지가 재처리됨 | at-least-once 의 본질 | [06](../step-06-offsets/), [07](../step-07-delivery-semantics/) | 비즈니스 멱등 처리 |
| 10 | 커밋된 데이터가 통째로 사라짐 | `unclean.leader.election.enable=true` | [08](../step-08-replication/) | `=false` (기본값 유지) |
| 11 | `acks=all` 인데도 유실 | `min.insync.replicas=1` | [08](../step-08-replication/) | `=2` (RF 3 기준) |
| 12 | 스키마 변경이 컨슈머를 멈춤 | 호환성 `NONE` | [10](../step-10-serialization/) | `BACKWARD` 유지, 필드는 기본값과 함께 추가 |

**열두 가지 중 어느 것도 에러 로그를 남기지 않습니다.** 이것이 이 코스가 존재하는 이유입니다.

---

## 14-8. 정리 (실습 마무리)

이 스텝에서 만든 토픽을 지웁니다.

```bash
kt --list | grep -E '^s14_' | while read t; do
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka-1:9092 --delete --topic "$t"
done
```

스로틀이 남아 있지 않은지 **반드시** 확인합니다.

```bash
for b in 1 2 3; do
  echo -n "broker $b: "
  kconf --describe --entity-type brokers --entity-name $b | grep -c throttled
done
```

**결과**

```
broker 1: 0
broker 2: 0
broker 3: 0
```

모든 브로커가 살아 있고 클러스터가 건강한지 마지막으로 확인합니다.

```bash
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
kt --describe --under-replicated-partitions | wc -l
```

**결과**

```
NAME       STATUS
kafka-1    Up 4 hours (healthy)
kafka-2    Up 12 minutes (healthy)
kafka-3    Up 12 minutes (healthy)
kafka-ui   Up 4 hours
0
```

### 코스를 마쳤다면 환경 정리

```bash
cd kafka/docker
docker compose --profile all down -v
```

**결과**

```
[+] Running 9/9
 ✔ Container kafka-connect     Removed                               2.1s
 ✔ Container schema-registry   Removed                               1.8s
 ✔ Container kafka-ui          Removed                               1.2s
 ✔ Container kafka-3           Removed                               6.4s
 ✔ Container kafka-2           Removed                               6.5s
 ✔ Container kafka-1           Removed                               6.3s
 ✔ Volume docker_kafka-1-data  Removed                               0.1s
 ✔ Volume docker_kafka-2-data  Removed                               0.1s
 ✔ Volume docker_kafka-3-data  Removed                               0.1s
```

---

## 정리

| 개념 | 핵심 |
|---|---|
| 재할당 3단계 | `--generate` → `--execute` → **`--verify`** |
| `Current` 블록 | **반드시 rollback.json 으로 저장.** `--generate` 는 매번 결과가 다름 |
| `--throttle` | 재할당 대역폭 제한. **`--verify` 가 해제해 줌** |
| 스로틀 잔존 | 이후 모든 복제가 느려짐. 에러 없음. `grep throttled` 로 확인 |
| preferred election | `kafka-leader-election.sh --election-type preferred` |
| controlled shutdown | `SIGTERM` 으로만 발동. **`kill -9` 금지** |
| 롤링 재시작 | 점검 → 정지 → 재시작 → **복제 완료 대기** → 리더 복원 |
| ④ 대기 생략 | ISR 이 1로 줄어 유실 위험. **데이터 보호 장치** |
| `UnderReplicatedPartitions` | 0이어야 정상 |
| `UnderMinIsrPartitionCount` | 0이 아니면 **쓰기가 거부되는 중** |
| `OfflinePartitionsCount` | 0이 아니면 **읽기·쓰기 불가** |
| `ActiveControllerCount` | 클러스터 **합이 1**. 2 이상이면 split-brain |
| `RequestHandlerAvgIdlePercent` | 0.3 미만이면 I/O 스레드 포화 |
| `kafka-log-dirs.sh` | 파티션별 크기와 `offsetLag` |
| 디스크 대응 | `retention.ms` 축소 → 확인 → 원복. **`rm` 절대 금지** |
| 컨슈머 ACL | 토픽 권한 + **그룹 권한**이 함께 필요 |
| minISR=2 의 의미 | 1대 죽어도 서비스 계속, 2대 죽으면 **쓰기 거부로 유실 방지** |
| DLQ 설계 | 파티션 1개(조사 편의), 헤더에 원인·출처 오프셋 |
| 최종 결론 | **exactly-once 는 Kafka 내부 한정.** 실무는 at-least-once + 멱등 처리 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`.

1. `s14_ex` 토픽을 브로커 1,2 에만 배치해 만들고, 3대에 고르게 재할당하기 (rollback.json 저장 포함)
2. 재할당에 `--throttle` 을 걸고, `--verify` 없이 끝냈을 때 스로틀이 남는 것을 확인한 뒤 수동 해제하기
3. kafka-3 을 롤링 재시작하되, **복제 완료 대기 루프를 직접 작성**해 넣기
4. `UnderReplicatedPartitions` / `OfflinePartitionsCount` / `ActiveControllerCount` 를 JMX 로 읽어 한 줄 헬스체크 만들기
5. `min.insync.replicas` 를 1로 낮춘 토픽과 2인 토픽에 브로커 2대를 죽인 채 쓰기를 시도해 **결과 차이**를 기록하기
6. 종합 실습 검증 체크리스트 V1~V5 를 자동으로 검사해 실패 항목을 출력하는 스크립트 작성하기
7. 코스 전체 "조용한 실패 12가지" 중 임의의 3개를 골라, 현재 클러스터가 방어되고 있는지 실제 설정으로 감사하기

---

## 다음 단계

이 코스는 여기서 끝납니다. Kafka 자체 — 브로커, 프로토콜, 저장 구조, 보장 모델, 운영 — 를 CLI 로 직접 만져 보았습니다.

다음으로 자연스럽게 이어지는 것은 **애플리케이션 레벨 통합**입니다. `@KafkaListener`, 컨테이너 팩토리, 에러 핸들러와 재시도 토픽, `KafkaTemplate` 의 트랜잭션 연동, 테스트용 임베디드 브로커 같은 주제는 별도의 **Spring Kafka 코스**에서 다룹니다. 이 코스에서 익힌 `acks`·`min.insync.replicas`·오프셋 커밋 시점의 의미가 그대로 그 코스의 설정 값으로 이어집니다.

운영을 더 깊이 파고 싶다면 MirrorMaker 2(클러스터 간 복제), Cruise Control(자동 리밸런싱), Tiered Storage(3.6+) 가 다음 관문입니다.

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 는 14-1 ~ 14-8 을 순서대로 재현하며, **브로커를 정지시키는 구간이 세 곳**(14-2 롤링 재시작, 14-6 장애 주입 두 번) 있으므로 실행 전 확인을 받습니다. 스크립트는 어떤 경로로 끝나든 마지막에 **모든 브로커를 되살리고 클러스터 건강을 검증**합니다.

### practice.sh

본문의 모든 명령을 절 번호 주석(`# [14-2]`)과 함께 담은 실행 스크립트입니다.

- 상단에 `K()` 헬퍼와 함께 `wait_healthy()`, `wait_isr_ok()` 두 함수를 정의합니다. 전자는 컨테이너 헬스체크를, 후자는 `--under-replicated-partitions` 가 0이 될 때까지 폴링합니다. **14-2 의 ④ 단계가 이 함수 하나로 표현**되며, 실무 롤링 재시작 스크립트에 그대로 옮겨 쓸 수 있습니다.
- `trap 'docker compose start kafka-1 kafka-2 kafka-3 2>/dev/null' EXIT` 를 걸어 두었습니다. 스크립트를 `Ctrl+C` 로 중단해도 **죽인 브로커가 반드시 되살아납니다.** 브로커가 죽은 채 방치되는 것이 이 스텝에서 가장 흔한 사고이므로 안전장치를 넣었습니다.
- `[14-1]` 의 JSON 파일들은 `docker exec ... sh -c 'cat > ... <<EOF'` 로 **컨테이너 안에** 만듭니다. 호스트에 만들면 컨테이너가 못 읽습니다. 그리고 `--generate` 결과를 그대로 쓰지 않고 스크립트가 미리 정해 둔 배치를 씁니다. `--generate` 는 실행할 때마다 결과가 달라 교재와 대조가 안 되기 때문입니다.
- `[14-1]` 의 `--verify` 는 **완료될 때까지 루프**를 돕니다. 한 번만 실행하고 "still in progress" 를 보고 넘어가면 스로틀이 남습니다. 루프가 끝난 뒤 `grep throttled` 로 잔존 여부를 실제로 검사해 보여 줍니다.
- `[14-2]` 롤링 재시작은 **kafka-2 한 대만** 수행합니다. 세 대를 다 돌면 10분 이상 걸리기 때문입니다. 나머지 두 대는 같은 절차를 반복하면 된다는 주석만 남겼습니다.
- `[14-3]` 의 JMX 호출은 브로커 컨테이너 **자기 자신의 localhost:9999** 로 붙습니다. 호스트 포트(19999)로 붙으려면 `host.docker.internal` 이 필요한데 환경에 따라 동작하지 않아, 안정적인 쪽을 택했습니다. 세 브로커의 `ActiveControllerCount` 를 각각 재는 구간만 컨테이너를 바꿔 가며 호출합니다.
- `[14-6]` 단계 ⑤ 의 장애 주입은 **두 단계**입니다. 브로커 1대 정지(쓰기 성공해야 함) → 2대 정지(`NotEnoughReplicasException` 이 나야 함). 두 번째에서 **에러가 나는 것이 정답**이므로 `|| true` 로 감싸고, 에러 메시지에 `NotEnoughReplicas` 가 포함됐는지 검사해 통과/실패를 판정합니다.
- `[14-8]` 의 마지막 환경 정리(`down -v`)는 **기본적으로 실행하지 않습니다.** `read -p` 로 확인하며, 기본값이 "아니오"입니다. 실수로 클러스터를 날리면 코스 전체를 다시 세워야 하기 때문입니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1** 은 `--replica-assignment 1:2,1:2,1:2` 로 불균형 토픽을 만드는 것부터 시작합니다. 문제지가 토픽 생성까지는 해 주고, `--generate` 이후를 비워 둡니다. **`Current` 블록을 저장하는 단계를 빠뜨리면 감점**이며, 정답 스크립트가 그 이유를 설명합니다.
- **문제 2** 가 이 문제지의 핵심입니다. `--verify` 를 **일부러 생략**하고 스로틀이 남는 것을 `grep throttled` 로 확인한 뒤, `--delete-config` 로 수동 해제합니다. 해제할 설정 이름 두 개(`leader.replication.throttled.rate`, `follower.replication.throttled.rate`)를 정확히 써야 하고, 토픽 쪽 `leader.replication.throttled.replicas` 도 함께 확인해야 완전합니다.
- **문제 3** 은 대기 루프를 직접 작성하는 문제입니다. `until [ "$(... | wc -l)" -eq 0 ]` 형태가 정답이며, **`wc -l` 이 0인지로 판정**해야 한다는 게 포인트입니다. 출력 문자열의 유무로 판정하려 하면 공백 처리에서 틀립니다.
- **문제 4** 는 JMX 세 지표를 한 줄 헬스체크로 묶습니다. `ActiveControllerCount` 는 **세 브로커의 합**을 구해야 1인지 알 수 있다는 점이 함정입니다. 한 브로커만 재면 0이 나와 "컨트롤러 없음"으로 오판합니다.
- **문제 5** 는 `min.insync.replicas` 1 vs 2 의 차이를 몸으로 확인하는 문제입니다. 브로커 2대를 죽인 상태에서 minISR=1 토픽은 **쓰기가 성공**하고 minISR=2 토픽은 **거부**됩니다. 성공한 쪽이 더 위험하다는 것이 결론이며, 문제지는 두 결과를 나란히 기록하게 합니다.
- **문제 6** 은 V1~V5 를 배열로 돌며 실패 항목만 모아 출력하는 스크립트입니다. 각 검사의 "정상 조건"이 **전부 0** 이라는 공통점을 이용하면 간결해집니다.
- **문제 7** 은 정답이 열려 있습니다. 예를 들어 #5(acks), #8(auto commit), #11(minISR)을 골랐다면, 각각 `kconf --describe` 나 토픽 설정으로 현재 방어 상태를 감사합니다. 프로듀서·컨슈머 설정은 브로커에서 볼 수 없으므로 **"애플리케이션 코드를 봐야 한다"** 는 한계까지 적어야 완전한 답입니다.

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답 명령과 **왜 그 답인지** 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `--generate` 출력에서 `Current` 와 `Proposed` 를 각각 파일로 뽑는 `sed` 파이프까지 보여 줍니다. 주석은 `--generate` 가 무작위 시드를 쓰므로 **같은 명령을 다시 돌려도 원래 배치가 안 나온다**는 점, 그래서 롤백 파일이 유일한 복구 수단이라는 점을 강조합니다.
- **정답 2** 는 스로틀 잔존을 실제 `synonyms` 출력으로 보여 준 뒤 해제합니다. 주석이 이 함정의 증상을 자세히 서술합니다. 평소에는 멀쩡하고, **브로커 재시작이나 장애 복구처럼 복제를 몰아쳐야 할 때만** 드러나므로 원인 추적이 매우 어렵습니다. under-replicated 가 몇 시간씩 안 풀리는데 로그는 조용한 것이 전형적인 증상입니다.
- **정답 3** 은 대기 루프에 **타임아웃 상한**(예: 300초)을 추가한 형태를 제시합니다. 주석은 무한 루프의 위험 — 복제가 영영 안 끝나는 상황(스로틀 잔존, 디스크 포화)에서 스크립트가 영원히 멈추는 것 — 을 지적하고, 상한 초과 시 **다음 브로커로 진행하지 말고 중단**해야 한다고 못 박습니다.
- **정답 4** 는 세 브로커의 `ActiveControllerCount` 를 루프로 더해 합계를 구합니다. 주석은 KRaft 에서 이 값이 **컨트롤러 역할을 하는 노드에서만 1** 이고 나머지는 0이라는 것, 합이 0이면 쿼럼 상실, 2 이상이면 split-brain 이라는 판정 기준을 정리합니다. 그리고 `controller.quorum.voters` 를 홀수로 둬야 하는 이유(과반수 계산)를 덧붙입니다.
- **정답 5** 는 두 토픽의 결과를 표로 대조합니다. minISR=1 토픽은 `Isr: 1` 상태에서 **쓰기가 성공**하고, minISR=2 토픽은 `NotEnoughReplicasException` 으로 거부됩니다. 주석의 결론이 이 코스 전체를 요약합니다 — **"성공하는 쪽이 위험한 쪽이다."** 남은 그 한 대가 죽으면 방금 성공한 쓰기가 사라지고, 프로듀서는 이미 성공 응답을 받은 뒤입니다.
- **정답 6** 은 `checks` 배열에 `"이름|명령"` 형태로 담아 돌리는 형태입니다. 주석은 모든 검사의 정상 조건이 0이라는 규칙성을 활용한 설계이며, 새 검사를 추가할 때 배열에 한 줄만 넣으면 된다는 확장성을 설명합니다. 그리고 이 스크립트를 cron 이나 컨테이너 헬스체크에 그대로 붙일 수 있다는 점을 덧붙입니다.
- **정답 7** 은 세 가지 감사를 실제로 수행합니다. #11(minISR)은 `kt --describe --topics-with-overrides | grep min.insync` 로 브로커에서 확인 가능하지만, #5(acks)와 #8(auto commit)은 **클라이언트 설정이라 브로커에서 볼 수 없습니다.** 주석은 이 비대칭이 실무에서 중요한 함의를 갖는다고 짚습니다. 브로커 측 방어(`min.insync.replicas`)는 중앙에서 강제할 수 있지만, 클라이언트 측 설정(`acks`, `enable.auto.commit`)은 **코드 리뷰와 표준 라이브러리로만** 통제됩니다. 그래서 조직 차원의 공용 프로듀서/컨슈머 팩토리를 두는 것이 정석이라는 결론으로 마무리합니다.

```bash file="./solution.sh"
```
