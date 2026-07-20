# Step 09 — 보존과 로그 압축

> **학습 목표**
> - Kafka 가 **컨슈머가 읽어도 메시지를 지우지 않는다**는 대전제를 이해하고, 지우는 유일한 기준이 보존 정책임을 확인한다
> - `retention.ms` / `retention.bytes` / `segment.ms` / `segment.bytes` 의 관계를 설명한다
> - **액티브 세그먼트는 절대 삭제되지 않는다**는 사실을 `ls -la` before/after 로 재현한다
> - `cleanup.policy=compact` 로 같은 키의 옛 레코드가 사라지고 **오프셋에 구멍이 생기는 것**을 `kafka-dump-log.sh` 로 직접 관찰한다
> - tombstone(값이 null 인 메시지)으로 키를 완전히 삭제하고, `delete.retention.ms` 가 왜 필요한지 설명한다
> - 압축 토픽의 설계 기준 4가지를 판정하고 `order-events` 가 왜 그 조건을 만족하는지 논증한다
>
> **선행 스텝**: Step 08 — 복제와 내구성
> **예상 소요**: 110분

---

## 9-0. 실습 준비

Step 08 에서 브로커를 죽였습니다. 세 대가 다 살아 있는지 먼저 확인합니다.

```bash
cd kafka/docker
docker exec kafka-1 /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server kafka-1:9092 | grep -cE '^kafka-[0-9]'
```

**결과**

```
3
```

`3` 이 아니면 `docker compose up -d` 로 살린 뒤 진행하세요. 이 스텝은 **디스크의 세그먼트 파일을 직접 들여다보므로** 브로커가 하나라도 빠지면 리더가 어디인지부터 헷갈립니다.

별칭을 등록합니다.

```bash
alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
alias kconf='docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka-1:9092'
```

---

## 9-1. Kafka 는 컨슈머가 읽어도 지우지 않는다

전통적인 메시지 큐(RabbitMQ, ActiveMQ, SQS)에서 메시지는 **소비되면 사라집니다.** 큐에서 꺼내는 순간 큐에서 빠집니다.

**Kafka 는 그렇지 않습니다.** 컨슈머는 로그를 읽을 뿐이고, 읽었다는 사실은 **오프셋이라는 별도의 숫자**로만 기록됩니다(Step 06). 로그 자체는 아무 영향을 받지 않습니다.

```
   컨슈머 그룹 A ──읽음──┐
                        │
   컨슈머 그룹 B ──읽음──┤        로그는 그대로:
                        ▼    ┌───┬───┬───┬───┬───┬───┬───┐
   컨슈머 그룹 C ──읽음──────►│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │
                             └───┴───┴───┴───┴───┴───┴───┘
                                          ▲       ▲   ▲
                                       그룹C    그룹A 그룹B
                                       오프셋 3  5    6
```

**여기서 나오는 결론 세 가지.**

1. 컨슈머가 100개든 0개든 디스크 사용량은 같습니다. **읽는 쪽은 저장에 아무 영향을 주지 않습니다.**
2. 나중에 붙은 컨슈머도 `--from-beginning` 으로 과거를 전부 다시 읽을 수 있습니다. 이것이 Kafka 를 "이벤트 소싱"이나 "재처리"에 쓸 수 있게 하는 근거입니다.
3. **그럼 언제 지웁니까?** 오직 **보존 정책**이 지웁니다. 컨슈머와는 무관하게, 시간이나 크기 기준으로만 지웁니다.

`cleanup.policy` 가 그 정책이고 값은 세 가지입니다.

| `cleanup.policy` | 동작 | 대표 용도 |
|---|---|---|
| `delete` (기본) | 오래되거나 용량을 넘긴 **세그먼트를 통째로 삭제** | `orders`, `payments`, `dlq` — 이벤트 스트림 |
| `compact` | 같은 키의 **옛 레코드만 제거**. 키별 최신 값은 영원히 보존 | `order-events` — 상태 스냅샷, `__consumer_offsets` |
| `compact,delete` | 둘 다 적용 | 상태 토픽인데 무한히 커지면 곤란할 때 |

---

## 9-2. `cleanup.policy=delete` — 네 설정의 관계

| 설정 | 기본값 | 의미 | 단위 |
|---|---:|---|---|
| `retention.ms` | `604800000` (7일) | 이 시간보다 오래된 세그먼트를 삭제 | 파티션 |
| `retention.bytes` | `-1` (무제한) | 파티션 크기가 이를 넘으면 오래된 세그먼트부터 삭제 | **파티션** |
| `segment.bytes` | `1073741824` (1GiB) | 세그먼트 파일이 이 크기가 되면 새 파일로 롤링 | 세그먼트 |
| `segment.ms` | `604800000` (7일) | 세그먼트가 이 시간 열려 있으면 크기와 무관하게 롤링 | 세그먼트 |
| `log.retention.check.interval.ms` | `300000` (5분) | 브로커가 삭제 대상을 **검사하는 주기** (브로커 설정) | 브로커 |

이 코스의 브로커는 `log.segment.bytes=1048576`(1MiB), `log.retention.check.interval.ms=10000`(10초) 으로 낮춰 두었습니다. 관찰을 몇 초 안에 하기 위해서입니다.

네 설정의 관계를 그림으로 보면 이렇습니다.

```
   파티션 s09_ret-0 의 디스크

   ┌──────────────┬──────────────┬──────────────┬──────────────┐
   │ 00000000.log │ 00000512.log │ 00001024.log │ 00001536.log │
   │   1 MiB      │   1 MiB      │   1 MiB      │   0.3 MiB    │
   │   닫힘        │   닫힘        │   닫힘        │ ★ 액티브 ★  │
   └──────────────┴──────────────┴──────────────┴──────────────┘
        ▲                                              ▲
        │                                              │ 지금 쓰는 중
   가장 오래됨 → retention.ms 초과 시                    │
   여기부터 통째로 삭제                          여기는 절대 안 지움

   segment.bytes / segment.ms 가 "새 파일로 넘어갈 시점" 을 정하고,
   retention.ms / retention.bytes 가 "닫힌 파일을 언제 지울지" 를 정합니다.
```

**핵심은 이 두 그룹이 서로 다른 일을 한다는 것입니다.** `segment.*` 는 **롤링**을, `retention.*` 는 **삭제**를 담당합니다. 그리고 삭제는 **세그먼트 파일 단위**로만 일어납니다. 레코드 하나를 콕 집어 지우는 기능은 `delete` 정책에 없습니다.

---

## 9-3. 핵심 개념 — 액티브 세그먼트는 절대 안 지운다

"`retention.ms=1000` 을 줬는데 1초가 지나도 메시지가 안 사라집니다." 이것은 Kafka 관련 질문 중 가장 자주 나오는 것이고, 답은 언제나 같습니다. **액티브 세그먼트(지금 쓰고 있는 파일)는 삭제 대상이 아니기 때문입니다.**

직접 재현합니다.

```bash
kt --create --topic s09_ret --partitions 1 --replication-factor 3 \
   --config retention.ms=10000
```

**결과**

```
Created topic s09_ret.
```

**보존 10초**입니다. 메시지 512건을 넣습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic s09_ret --num-records 512 --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092 acks=all
```

**결과**

```
512 records sent, 3121.951 records/sec (3.05 MB/sec), 8.42 ms avg latency, 62.00 ms max latency.
```

리더가 어디인지 확인하고, 그 브로커의 파티션 디렉터리를 봅니다.

```bash
kt --describe --topic s09_ret
```

**결과**

```
Topic: s09_ret	TopicId: xR5nQ2jVTdyBm8LcWkPfEg	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=2,retention.ms=10000,segment.bytes=1048576
	Topic: s09_ret	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

리더는 `kafka-1` 입니다.

```bash
docker exec kafka-1 ls -la /var/lib/kafka/data/s09_ret-0
```

**결과 — BEFORE**

```
total 1044
drwxr-xr-x 2 appuser appuser    4096 Mar 11 11:02 .
drwxr-xr-x 8 appuser appuser    4096 Mar 11 11:02 ..
-rw-r--r-- 1 appuser appuser 10485760 Mar 11 11:02 00000000000000000000.index
-rw-r--r-- 1 appuser appuser   544768 Mar 11 11:02 00000000000000000000.log
-rw-r--r-- 1 appuser appuser 10485752 Mar 11 11:02 00000000000000000000.timeindex
-rw-r--r-- 1 appuser appuser       10 Mar 11 11:02 leader-epoch-checkpoint
-rw-r--r-- 1 appuser appuser      43 Mar 11 11:02 partition.metadata
```

세그먼트가 **하나뿐**입니다. `00000000000000000000.log` 가 544 KB — 1 MiB 에 못 미쳐 롤링되지 않았고, 따라서 **이것이 액티브 세그먼트**입니다.

`retention.ms=10000` 이고 브로커의 검사 주기가 10초이니 넉넉히 **90초를 기다립니다.**

```bash
sleep 90
docker exec kafka-1 ls -la /var/lib/kafka/data/s09_ret-0
```

**결과 — AFTER (90초 후)**

```
total 1044
drwxr-xr-x 2 appuser appuser    4096 Mar 11 11:02 .
drwxr-xr-x 8 appuser appuser    4096 Mar 11 11:02 ..
-rw-r--r-- 1 appuser appuser 10485760 Mar 11 11:02 00000000000000000000.index
-rw-r--r-- 1 appuser appuser   544768 Mar 11 11:02 00000000000000000000.log
-rw-r--r-- 1 appuser appuser 10485752 Mar 11 11:02 00000000000000000000.timeindex
-rw-r--r-- 1 appuser appuser       10 Mar 11 11:02 leader-epoch-checkpoint
-rw-r--r-- 1 appuser appuser      43 Mar 11 11:02 partition.metadata
```

**하나도 안 바뀌었습니다.** 보존 시간의 9배가 지났는데도요.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s09_ret --time -2
```

**결과**

```
s09_ret:0:0
```

earliest 가 여전히 `0`. **한 건도 안 지워졌습니다.**

### 롤링을 유도하면 지워진다

`segment.ms=10000` 을 함께 겁니다. **10초마다 세그먼트를 강제로 롤링**하라는 뜻입니다.

```bash
kconf --alter --entity-type topics --entity-name s09_ret \
      --add-config segment.ms=10000
```

**결과**

```
Completed updating config for topic s09_ret.
```

설정만 바꿔서는 롤링이 안 일어납니다. **새 레코드가 들어와야** 브로커가 "이 세그먼트를 롤링할 때가 됐나?"를 판단합니다. 1건만 넣습니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_ret <<'EOF'
{"trigger":"roll"}
EOF

sleep 30
docker exec kafka-1 ls -la /var/lib/kafka/data/s09_ret-0
```

**결과 — AFTER (segment.ms 적용 후)**

```
total 20
drwxr-xr-x 2 appuser appuser    4096 Mar 11 11:05 .
drwxr-xr-x 8 appuser appuser    4096 Mar 11 11:02 ..
-rw-r--r-- 1 appuser appuser 10485760 Mar 11 11:05 00000000000000000513.index
-rw-r--r-- 1 appuser appuser        0 Mar 11 11:05 00000000000000000513.log
-rw-r--r-- 1 appuser appuser 10485752 Mar 11 11:05 00000000000000000513.timeindex
-rw-r--r-- 1 appuser appuser       10 Mar 11 11:04 leader-epoch-checkpoint
-rw-r--r-- 1 appuser appuser      43 Mar 11 11:02 partition.metadata
```

**`00000000000000000000.log` 가 사라졌습니다.** 파일 이름이 `00000000000000000513` 으로 바뀌었습니다. 세그먼트 파일 이름은 **그 파일의 첫 오프셋**을 20자리로 채운 것이므로, 이제 이 파티션은 오프셋 513 부터 시작합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s09_ret --time -2
docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:9092 --topic s09_ret --time -1
```

**결과**

```
s09_ret:0:513
s09_ret:0:513
```

**earliest 가 0 에서 513 으로 올라갔습니다.** 513건(512 + 트리거 1건)이 삭제된 것입니다. 브로커 로그에서 확인합니다.

```bash
docker logs kafka-1 --since 3m 2>&1 | grep -E 'Rolled|Deleting segment|deleted'
```

**결과**

```
[2024-03-11 11:05:12,338] INFO [UnifiedLog partition=s09_ret-0, dir=/var/lib/kafka/data] Rolled new log segment at offset 513 in 3 ms. (kafka.log.UnifiedLog)
[2024-03-11 11:05:22,401] INFO [UnifiedLog partition=s09_ret-0, dir=/var/lib/kafka/data] Deleting segment LogSegment(baseOffset=0, size=544768, lastModifiedTime=1710123742000, largestRecordTimestamp=Some(1710123741980)) due to retention time 10000ms breach based on the largest record timestamp in the segment (kafka.log.UnifiedLog)
[2024-03-11 11:05:22,405] INFO [LocalLog partition=s09_ret-0, dir=/var/lib/kafka/data] Deleted log /var/lib/kafka/data/s09_ret-0/00000000000000000000.log.deleted. (kafka.log.LocalLog)
```

세 줄이 순서를 정확히 말해 줍니다. **① 롤링(`Rolled new log segment at offset 513`) → ② 보존 위반 판정(`due to retention time 10000ms breach`) → ③ 파일 삭제.** ①이 없으면 ②도 ③도 없습니다.

> ⚠️ **함정 — `retention.ms` 만 짧게 주고 "왜 안 지워지지" 하는 것**
> 세그먼트가 아직 롤링되지 않았으면(=액티브면) `retention.ms` 를 1초로 줘도 **영원히 안 지워집니다.**
> 트래픽이 적은 토픽에서 이 일이 흔합니다. 하루에 몇 건 안 들어오는 토픽은 `segment.bytes=1GiB` 를 채우는 데 몇 년이 걸리고, `segment.ms` 기본값이 7일이니 **최소 7일은 무조건 남습니다.**
> "개인정보 보존기간 준수를 위해 `retention.ms=86400000`(1일)로 설정했다"고 보고했는데 실제로는 8일 넘게 남아 있는 상황이 여기서 나옵니다.
> **해결**: 보존 기간을 **정확히** 지켜야 하면 `segment.ms` 를 보존 기간보다 짧게(예: 보존 1일이면 `segment.ms=6시간`) 함께 설정하세요.
> 대신 세그먼트 파일 수가 늘어 파일 핸들과 인덱스 메모리를 더 씁니다. **정확성과 파일 수의 트레이드오프**입니다.

> ⚠️ **함정 — 삭제 판정은 "레코드의 타임스탬프" 기준이지 파일 수정 시각이 아닙니다**
> 위 로그의 `based on the largest record timestamp in the segment` 가 그 말입니다.
> 프로듀서가 `CreateTime` 을 과거로 조작해 보내면(과거 데이터 마이그레이션에서 흔합니다) **넣자마자 보존 기간을 넘긴 상태**가 됩니다. 롤링되는 순간 즉시 삭제됩니다.
> 반대로 미래 타임스탬프를 넣으면 **보존 기간이 지나도 안 지워집니다.** 시계가 틀어진 서버 하나가 토픽 하나를 영원히 남게 만들 수 있습니다.
> 토픽의 `message.timestamp.type` 을 `LogAppendTime` 으로 바꾸면 브로커가 수신 시각으로 덮어써서 이 문제를 없앨 수 있습니다. 대신 프로듀서가 기록한 원래 시각을 잃습니다.

---

## 9-4. `retention.bytes` 는 파티션당 크기다

`retention.bytes` 에는 별도의 함정이 있습니다. **토픽 전체 크기가 아니라 파티션 하나의 크기입니다.**

```bash
kt --create --topic s09_bytes --partitions 6 --replication-factor 3 \
   --config retention.bytes=1073741824
kt --describe --topic s09_bytes
```

**결과**

```
Topic: s09_bytes	TopicId: pW9dLc3EQmuZaN7vXsRtKg	PartitionCount: 6	ReplicationFactor: 3	Configs: min.insync.replicas=2,retention.bytes=1073741824,segment.bytes=1048576
	Topic: s09_bytes	Partition: 0	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
	Topic: s09_bytes	Partition: 1	Leader: 3	Replicas: 3,1,2	Isr: 3,1,2
	Topic: s09_bytes	Partition: 2	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
	Topic: s09_bytes	Partition: 3	Leader: 2	Replicas: 2,1,3	Isr: 2,1,3
	Topic: s09_bytes	Partition: 4	Leader: 3	Replicas: 3,2,1	Isr: 3,2,1
	Topic: s09_bytes	Partition: 5	Leader: 1	Replicas: 1,3,2	Isr: 1,3,2
```

"1 GiB 로 제한했다"고 생각하기 쉽지만 실제 계산은 이렇습니다.

| 항목 | 계산 | 결과 |
|---|---|---:|
| 파티션당 상한 | `retention.bytes` | 1 GiB |
| 파티션 수 | 6 | |
| 토픽 데이터 총량 | 1 GiB × 6 | **6 GiB** |
| 복제 계수 | 3 | |
| **클러스터 전체 디스크 사용량** | 6 GiB × 3 | **18 GiB** |

**설정한 값의 18배입니다.** 파티션을 나중에 6개에서 12개로 늘리면(Step 03) 그 순간 **36 GiB** 가 됩니다. 설정은 아무도 안 건드렸는데 디스크 사용량이 두 배가 되는 것입니다.

> 💡 **실무 팁 — 디스크 상한 계산식**
> ```
> 필요 디스크 = retention.bytes × 파티션 수 × RF × (여유율 1.3 정도)
> ```
> 여유율은 액티브 세그먼트, 인덱스 파일(`.index`, `.timeindex` 는 각각 최대 10 MiB 씩 미리 할당됩니다), 삭제 지연분을 위한 것입니다.
> 위 예에서 `.index` 와 `.timeindex` 만으로도 세그먼트당 20 MiB 가 잡혀 있는 것을 9-3 의 `ls -la` 에서 봤습니다. **작은 토픽이 많으면 이 오버헤드가 데이터보다 커집니다.**

> ⚠️ **함정 — `retention.ms` 와 `retention.bytes` 는 OR 조건입니다**
> 둘 다 설정하면 "둘 다 만족해야 삭제"가 아니라 **"둘 중 하나만 걸려도 삭제"** 입니다.
> `retention.ms=7일`, `retention.bytes=1GiB` 인 토픽에 트래픽이 몰려 1 GiB 를 하루 만에 채우면 **하루치만 남습니다.**
> "7일치 보관"이라고 믿고 있던 재처리 계획이 그 순간 무너집니다. 트래픽이 늘어난 날에만, 조용히요.
> **해결**: 시간 기준 보존이 계약 사항이라면 `retention.bytes` 는 **넉넉히 잡거나 아예 -1(무제한)** 로 두고, 디스크는 용량 알람으로 관리하세요.

---

## 9-5. `cleanup.policy=compact` — 같은 키의 옛 값이 사라진다

압축(compaction)은 **삭제와 완전히 다른 메커니즘**입니다. 오래된 것을 지우는 게 아니라, **같은 키의 옛 레코드를 지우고 키별 최신 값만 남깁니다.**

```
   압축 전 (오프셋 0~11)
   ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
   │ 0  │ 1  │ 2  │ 3  │ 4  │ 5  │ 6  │ 7  │ 8  │ 9  │ 10 │ 11 │
   │O-1 │O-2 │O-1 │O-3 │O-2 │O-1 │O-4 │O-3 │O-2 │O-1 │O-4 │O-3 │
   │CRE │CRE │PAID│CRE │PAID│SHIP│CRE │PAID│SHIP│DONE│PAID│SHIP│
   └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘

   압축 후 — 키별 마지막 값만 남는다
   ┌────┬────┬────┬────┐
   │ 8  │ 9  │ 10 │ 11 │   ← ★ 오프셋에 구멍이 생겼다 (0~7 이 없다)
   │O-2 │O-1 │O-4 │O-3 │
   │SHIP│DONE│PAID│SHIP│
   └────┴────┴────┴────┘
```

**오프셋 번호가 바뀌지 않는다는 점이 핵심입니다.** 살아남은 레코드는 원래 오프셋을 그대로 유지하고, 사라진 자리는 **빈 채로 남습니다.** 오프셋 8 다음이 9 이던 것이, 압축 후에는 오프셋 0 다음이 바로 8 입니다.

토픽을 만들고 실제로 봅니다.

```bash
kt --create --topic s09_compact --partitions 1 --replication-factor 3 \
   --config cleanup.policy=compact \
   --config min.cleanable.dirty.ratio=0.01 \
   --config segment.ms=10000 \
   --config min.compaction.lag.ms=0
```

**결과**

```
Created topic s09_compact.
```

같은 키 4개(`O-1001` ~ `O-1004`)로 12건을 씁니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-1001:{"order_id":"O-1001","status":"CREATED","at":"2024-03-11T10:00:00Z"}
O-1002:{"order_id":"O-1002","status":"CREATED","at":"2024-03-11T10:01:00Z"}
O-1001:{"order_id":"O-1001","status":"PAID","at":"2024-03-11T10:02:00Z"}
O-1003:{"order_id":"O-1003","status":"CREATED","at":"2024-03-11T10:03:00Z"}
O-1002:{"order_id":"O-1002","status":"PAID","at":"2024-03-11T10:04:00Z"}
O-1001:{"order_id":"O-1001","status":"SHIPPED","at":"2024-03-11T10:05:00Z"}
O-1004:{"order_id":"O-1004","status":"CREATED","at":"2024-03-11T10:06:00Z"}
O-1003:{"order_id":"O-1003","status":"PAID","at":"2024-03-11T10:07:00Z"}
O-1002:{"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
O-1001:{"order_id":"O-1001","status":"DELIVERED","at":"2024-03-11T10:09:00Z"}
O-1004:{"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
O-1003:{"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
EOF
```

### 압축 전 — 컨슈머 출력

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact --from-beginning \
  --property print.key=true --property print.offset=true --timeout-ms 5000
```

**결과**

```
Partition:0	Offset:0	O-1001	{"order_id":"O-1001","status":"CREATED","at":"2024-03-11T10:00:00Z"}
Partition:0	Offset:1	O-1002	{"order_id":"O-1002","status":"CREATED","at":"2024-03-11T10:01:00Z"}
Partition:0	Offset:2	O-1001	{"order_id":"O-1001","status":"PAID","at":"2024-03-11T10:02:00Z"}
Partition:0	Offset:3	O-1003	{"order_id":"O-1003","status":"CREATED","at":"2024-03-11T10:03:00Z"}
Partition:0	Offset:4	O-1002	{"order_id":"O-1002","status":"PAID","at":"2024-03-11T10:04:00Z"}
Partition:0	Offset:5	O-1001	{"order_id":"O-1001","status":"SHIPPED","at":"2024-03-11T10:05:00Z"}
Partition:0	Offset:6	O-1004	{"order_id":"O-1004","status":"CREATED","at":"2024-03-11T10:06:00Z"}
Partition:0	Offset:7	O-1003	{"order_id":"O-1003","status":"PAID","at":"2024-03-11T10:07:00Z"}
Partition:0	Offset:8	O-1002	{"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
Partition:0	Offset:9	O-1001	{"order_id":"O-1001","status":"DELIVERED","at":"2024-03-11T10:09:00Z"}
Partition:0	Offset:10	O-1004	{"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
Partition:0	Offset:11	O-1003	{"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
Processed a total of 12 messages
```

**12건.** 세그먼트 파일도 직접 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s09_compact-0/00000000000000000000.log \
  --print-data-log | grep -E '^\| offset'
```

**결과 — 압축 전**

```
| offset: 0 CreateTime: 1710123600000 keySize: 6 valueSize: 68 sequence: 0 headerKeys: [] key: O-1001 payload: {"order_id":"O-1001","status":"CREATED","at":"2024-03-11T10:00:00Z"}
| offset: 1 CreateTime: 1710123660000 keySize: 6 valueSize: 68 sequence: 1 headerKeys: [] key: O-1002 payload: {"order_id":"O-1002","status":"CREATED","at":"2024-03-11T10:01:00Z"}
| offset: 2 CreateTime: 1710123720000 keySize: 6 valueSize: 65 sequence: 2 headerKeys: [] key: O-1001 payload: {"order_id":"O-1001","status":"PAID","at":"2024-03-11T10:02:00Z"}
| offset: 3 CreateTime: 1710123780000 keySize: 6 valueSize: 68 sequence: 3 headerKeys: [] key: O-1003 payload: {"order_id":"O-1003","status":"CREATED","at":"2024-03-11T10:03:00Z"}
| offset: 4 CreateTime: 1710123840000 keySize: 6 valueSize: 65 sequence: 4 headerKeys: [] key: O-1002 payload: {"order_id":"O-1002","status":"PAID","at":"2024-03-11T10:04:00Z"}
| offset: 5 CreateTime: 1710123900000 keySize: 6 valueSize: 68 sequence: 5 headerKeys: [] key: O-1001 payload: {"order_id":"O-1001","status":"SHIPPED","at":"2024-03-11T10:05:00Z"}
| offset: 6 CreateTime: 1710123960000 keySize: 6 valueSize: 68 sequence: 6 headerKeys: [] key: O-1004 payload: {"order_id":"O-1004","status":"CREATED","at":"2024-03-11T10:06:00Z"}
| offset: 7 CreateTime: 1710124020000 keySize: 6 valueSize: 65 sequence: 7 headerKeys: [] key: O-1003 payload: {"order_id":"O-1003","status":"PAID","at":"2024-03-11T10:07:00Z"}
| offset: 8 CreateTime: 1710124080000 keySize: 6 valueSize: 68 sequence: 8 headerKeys: [] key: O-1002 payload: {"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
| offset: 9 CreateTime: 1710124140000 keySize: 6 valueSize: 70 sequence: 9 headerKeys: [] key: O-1001 payload: {"order_id":"O-1001","status":"DELIVERED","at":"2024-03-11T10:09:00Z"}
| offset: 10 CreateTime: 1710124200000 keySize: 6 valueSize: 65 sequence: 10 headerKeys: [] key: O-1004 payload: {"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
| offset: 11 CreateTime: 1710124260000 keySize: 6 valueSize: 68 sequence: 11 headerKeys: [] key: O-1003 payload: {"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
```

### 클리너를 돌리기

**로그 클리너는 액티브 세그먼트를 건드리지 않습니다.** `delete` 와 똑같은 제약입니다. 롤링을 유도해야 합니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-9999:{"order_id":"O-9999","status":"TRIGGER","at":"2024-03-11T10:12:00Z"}
EOF

sleep 40
```

브로커 로그에서 클리너 스레드를 확인합니다.

```bash
docker logs kafka-1 --since 2m 2>&1 | grep -iE 'Cleaner|cleaned log'
```

**결과**

```
[2024-03-11 11:14:03,772] INFO Cleaner 0: Beginning cleaning of log s09_compact-0. (kafka.log.LogCleaner)
[2024-03-11 11:14:03,774] INFO Cleaner 0: Building offset map for s09_compact-0... (kafka.log.LogCleaner)
[2024-03-11 11:14:03,801] INFO Cleaner 0: Build offset map for log s09_compact-0 for 1 segments in 0.0 seconds. 0.0m messages read (0.4 MB/sec). (kafka.log.LogCleaner)
[2024-03-11 11:14:03,829] INFO Cleaner 0: Cleaning log s09_compact-0 (cleaning prior to Mon Mar 11 11:14:00 UTC 2024, discarding tombstones prior to upper bound deletion horizon Sun Mar 10 11:14:00 UTC 2024)... (kafka.log.LogCleaner)
[2024-03-11 11:14:03,884] INFO [kafka-log-cleaner-thread-0]: Log cleaner thread 0 cleaned log s09_compact-0 (dirty section = [0, 12])
	0.0 MB of log processed in 0.1 seconds (0.0 MB/sec).
	Indexed 0.0 MB in 0.0 seconds (0.0 Mb/sec, 42.9% of total time)
	Buffer utilization: 0.0%
	Cleaned 0.0 MB in 0.0 seconds (0.0 Mb/sec, 57.1% of total time)
	Start size: 0.0 MB (12 messages)
	End size: 0.0 MB (4 messages)
	66.7% size reduction (66.7% fewer messages)
 (kafka.log.LogCleaner)
```

**`dirty section = [0, 12]`** — 오프셋 0부터 12 직전까지가 "아직 압축 안 된 구간(dirty)"이었다는 뜻입니다.
**`Start size: 12 messages → End size: 4 messages`, `66.7% size reduction`.** 12건이 4건이 되었습니다.

### 압축 후 — 오프셋에 구멍이 생겼다

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s09_compact-0/00000000000000000000.log \
  --print-data-log | grep -E '^\| offset'
```

**결과 — 압축 후**

```
| offset: 8 CreateTime: 1710124080000 keySize: 6 valueSize: 68 sequence: 8 headerKeys: [] key: O-1002 payload: {"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
| offset: 9 CreateTime: 1710124140000 keySize: 6 valueSize: 70 sequence: 9 headerKeys: [] key: O-1001 payload: {"order_id":"O-1001","status":"DELIVERED","at":"2024-03-11T10:09:00Z"}
| offset: 10 CreateTime: 1710124200000 keySize: 6 valueSize: 65 sequence: 10 headerKeys: [] key: O-1004 payload: {"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
| offset: 11 CreateTime: 1710124260000 keySize: 6 valueSize: 68 sequence: 11 headerKeys: [] key: O-1003 payload: {"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
```

**오프셋 0~7 이 통째로 없습니다.** 남은 것은 8, 9, 10, 11 — 각 키의 마지막 값입니다. 그리고 **번호는 8부터 시작합니다.** 0부터 다시 매기지 않았습니다.

컨슈머로도 확인합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact --from-beginning \
  --property print.key=true --property print.offset=true --timeout-ms 5000
```

**결과**

```
Partition:0	Offset:8	O-1002	{"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
Partition:0	Offset:9	O-1001	{"order_id":"O-1001","status":"DELIVERED","at":"2024-03-11T10:09:00Z"}
Partition:0	Offset:10	O-1004	{"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
Partition:0	Offset:11	O-1003	{"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
Partition:0	Offset:12	O-9999	{"order_id":"O-9999","status":"TRIGGER","at":"2024-03-11T10:12:00Z"}
Processed a total of 5 messages
```

**12건 → 5건**(압축된 4건 + 아직 액티브 세그먼트에 있는 트리거 1건)입니다.

> ⚠️ **함정 — 압축 토픽에서 "오프셋 + 1 = 다음 레코드"를 가정하면 안 됩니다**
> 컨슈머가 오프셋 8 을 읽고 다음에 9 를 기대하는 것은 괜찮지만, **오프셋 3 을 읽으려 하면 아무것도 못 얻습니다.** 그 자리는 비어 있습니다.
> `seek(partition, 3)` 을 하면 에러가 나는 게 아니라 **오프셋 8 부터 읽힙니다.** Kafka 는 "요청한 오프셋 이상의 첫 레코드"를 돌려주기 때문입니다.
> "오프셋 개수 = 메시지 개수"를 가정한 랙 모니터링도 압축 토픽에서는 부정확합니다. LOG-END-OFFSET 과 실제 레코드 수가 다르기 때문입니다.

### 압축 관련 설정

| 설정 | 기본값 | 의미 | 이 코스 |
|---|---:|---|---|
| `min.cleanable.dirty.ratio` | `0.5` | dirty 비율이 이 값을 넘어야 클리너가 그 로그를 청소 대상으로 고름 | 브로커 **0.01** |
| `min.compaction.lag.ms` | `0` | 레코드가 쓰인 뒤 **최소 이 시간은 압축되지 않음**. 컨슈머에게 읽을 시간을 보장 | 실습에서 0 |
| `max.compaction.lag.ms` | `Long.MAX_VALUE` | dirty 비율과 무관하게 **이 시간 안에는 반드시** 압축. GDPR 삭제 보장 등에 필요 | 미설정 |
| `delete.retention.ms` | `86400000` (24시간) | **tombstone 을 유지하는 시간** (9-6) | 실습에서 축소 |
| `segment.ms` | 7일 | 액티브 세그먼트 롤링 주기. **압축의 전제 조건** | 실습에서 10초 |
| `log.cleaner.threads` | `1` | 클리너 스레드 수 (브로커 설정) | 기본 |
| `log.cleaner.backoff.ms` | `15000` | 청소할 로그가 없을 때 대기 시간 (브로커 설정) | 브로커 **5000** |

`min.cleanable.dirty.ratio=0.5` 가 기본이라는 것은, **로그의 절반이 중복이 되어야 청소를 시작한다**는 뜻입니다. 이 코스가 0.01 로 낮춘 이유이며, 운영에서 0.5 그대로 두면 "압축 토픽인데 왜 안 줄지?"의 답이 대개 "아직 dirty 가 50% 가 안 됐다"입니다.

---

## 9-6. tombstone — 값이 null 이면 삭제 표식

압축은 "키별 최신 값"을 남깁니다. 그럼 **키 자체를 지우려면** 어떻게 할까요? **값이 `null` 인 메시지**를 씁니다. 이것을 **tombstone(묘비)** 이라고 합니다.

```
   O-1001:{"status":"DELIVERED"}   ← 일반 레코드
   O-1001:<null>                   ← tombstone. "이 키는 이제 없다"

   압축 후 → 키 O-1001 이 로그에서 완전히 사라진다
```

`kafka-console-producer.sh` 로 tombstone 을 쓰려면 `null.marker` 옵션이 필요합니다. Kafka **3.7 의 `kafka-console-producer.sh` 는 `--property null.marker=<문자열>`** 를 지원합니다. 지정한 문자열이 값 자리에 오면 그 레코드의 값을 `null` 로 보냅니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact \
  --property parse.key=true --property key.separator=: \
  --property null.marker=NULL <<'EOF'
O-1001:NULL
EOF
```

**결과**

```
```

> ⚠️ **함정 — `null.marker` 없이 값을 비우면 tombstone 이 아닙니다**
> `O-1001:` 처럼 콜론 뒤를 비우면 값이 `null` 이 아니라 **길이 0 인 빈 문자열**이 됩니다. 완전히 다른 것입니다.
> `kafka-dump-log.sh` 로 보면 tombstone 은 `valueSize: -1`, 빈 문자열은 `valueSize: 0` 으로 나옵니다. **-1 이어야 진짜 tombstone 입니다.**
> 빈 문자열 레코드는 압축 후에도 그 키의 "최신 값"으로 남습니다. 삭제가 안 되고, 컨슈머는 빈 값을 유효한 상태로 받아 갑니다.
> `null.marker` 를 지원하지 않는 옛 버전에서는 `kafkacat -Z` 를 쓰거나 자바 프로듀서로 `new ProducerRecord<>(topic, key, null)` 을 보내야 했습니다. 3.7 은 CLI 로 가능합니다.

tombstone 이 제대로 들어갔는지 확인합니다. 롤링을 유도한 뒤 덤프합니다.

```bash
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-8888:{"order_id":"O-8888","status":"TRIGGER2","at":"2024-03-11T10:14:00Z"}
EOF

sleep 15
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/s09_compact-0/00000000000000000012.log \
  --print-data-log | grep -E '^\| offset'
```

**결과** (tombstone 은 `valueSize: -1`)

```
| offset: 12 CreateTime: 1710124320000 keySize: 6 valueSize: 68 sequence: 0 headerKeys: [] key: O-9999 payload: {"order_id":"O-9999","status":"TRIGGER","at":"2024-03-11T10:12:00Z"}
| offset: 13 CreateTime: 1710124380000 keySize: 6 valueSize: -1 sequence: 0 headerKeys: [] key: O-1001 payload: 
| offset: 14 CreateTime: 1710124440000 keySize: 6 valueSize: 68 sequence: 0 headerKeys: [] key: O-8888 payload: {"order_id":"O-8888","status":"TRIGGER2","at":"2024-03-11T10:14:00Z"}
```

`offset: 13` 의 `valueSize: -1` 이 tombstone 입니다. `payload:` 뒤가 비어 있습니다.

`delete.retention.ms` 를 짧게 줘서 tombstone 이 사라지는 것까지 봅니다.

```bash
kconf --alter --entity-type topics --entity-name s09_compact \
      --add-config delete.retention.ms=10000

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-7777:{"order_id":"O-7777","status":"TRIGGER3","at":"2024-03-11T10:16:00Z"}
EOF

sleep 45
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka-1:9092 --topic s09_compact --from-beginning \
  --property print.key=true --property print.offset=true --timeout-ms 5000
```

**결과**

```
Partition:0	Offset:8	O-1002	{"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
Partition:0	Offset:10	O-1004	{"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
Partition:0	Offset:11	O-1003	{"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
Partition:0	Offset:12	O-9999	{"order_id":"O-9999","status":"TRIGGER","at":"2024-03-11T10:12:00Z"}
Partition:0	Offset:14	O-8888	{"order_id":"O-8888","status":"TRIGGER2","at":"2024-03-11T10:14:00Z"}
Partition:0	Offset:16	O-7777	{"order_id":"O-7777","status":"TRIGGER3","at":"2024-03-11T10:16:00Z"}
Processed a total of 6 messages
```

**`O-1001` 이 완전히 사라졌습니다.** 오프셋 9(DELIVERED)도, 오프셋 13(tombstone)도 없습니다. 키가 존재했다는 흔적조차 남지 않았습니다.

### `delete.retention.ms` 가 왜 24시간이나 되는가

tombstone 은 두 단계로 처리됩니다.

```
   ① 1차 압축:  O-1001 의 옛 값들이 삭제되고 tombstone 만 남는다
                → 뒤늦게 붙는 컨슈머도 "O-1001 은 삭제됐다" 를 읽을 수 있다
                → 이 상태를 delete.retention.ms 동안 유지

   ② 2차 압축:  delete.retention.ms 가 지나면 tombstone 자신도 삭제
                → 이제 O-1001 은 로그에서 완전히 사라진다
```

**①에서 tombstone 을 바로 안 지우는 이유가 중요합니다.** 압축 토픽의 전형적인 사용법은 "컨슈머가 처음부터 다 읽어 로컬 캐시(맵)를 만드는 것"입니다. 만약 tombstone 을 즉시 지우면, **삭제 직후에 붙은 컨슈머는 `O-1001` 이라는 키를 아예 못 봅니다.** 그 경우 캐시에 없으니 문제없어 보이지만, **이미 캐시를 갖고 있다가 잠깐 재연결한 컨슈머**는 "O-1001 이 삭제됐다"는 사실을 영영 못 받습니다.

기본값 24시간은 "컨슈머가 하루 안에는 이 tombstone 을 읽을 것"이라는 가정입니다.

> ⚠️ **함정 — `delete.retention.ms` 보다 오래 멈춰 있던 컨슈머는 삭제를 영영 못 봅니다**
> 시나리오는 이렇습니다.
> ```
> 09:00  컨슈머 A 가 캐시를 구축. cache = { O-1001: DELIVERED, O-1002: SHIPPED, ... }
> 10:00  컨슈머 A 배포로 30시간 중단 (롤백 대응, 장애, 긴 배포 파이프라인 등)
> 11:00  O-1001 에 tombstone 이 쓰이고, 압축이 돌아 옛 값들이 사라짐
> 35:00  delete.retention.ms(24시간) 경과 → tombstone 도 삭제
> 40:00  컨슈머 A 재기동. 마지막 커밋 오프셋부터 이어 읽음
>        → tombstone 이 이미 없으므로 삭제 사실을 읽지 못함
>        → cache 에 O-1001: DELIVERED 가 그대로 남는다
> ```
> **에러는 없습니다.** 컨슈머는 정상 동작하고, 로그도 깨끗하고, 랙도 0 입니다. 그런데 삭제된 주문이 캐시에 살아 있습니다.
> 실무에서는 "이미 취소한 주문이 목록에 계속 보인다", "탈퇴한 회원에게 메일이 계속 간다" 같은 증상으로 나타납니다.
> **해결 세 가지**:
> ① 압축 토픽을 읽는 컨슈머가 `delete.retention.ms` 보다 오래 멈추지 않도록 알람을 겁니다(랙이 아니라 **마지막 커밋 시각**을 봐야 합니다).
> ② 오래 멈췄던 컨슈머는 오프셋을 이어받지 말고 **처음부터 다시 읽어 캐시를 재구축**합니다. 압축 토픽은 그게 싸므로(키 수 = 레코드 수) 실행 가능한 선택지입니다.
> ③ `delete.retention.ms` 를 컨슈머 최대 중단 시간보다 넉넉하게(예: 7일) 늘립니다. 대신 tombstone 이 그만큼 오래 로그에 남습니다.

---

## 9-7. 함정 — 압축 토픽은 "최신 값"을 보장하지 않는다

압축 토픽을 처음 접하면 "이 토픽을 읽으면 키별 최신 값 하나씩 나온다"고 이해하기 쉽습니다. **틀렸습니다.**

**압축은 "언젠가는 옛 값을 지운다"는 보장일 뿐, "지금 읽으면 최신 값만 나온다"는 보장이 아닙니다.** 클리너가 언제 돌지는 `min.cleanable.dirty.ratio`, 세그먼트 롤링, 클리너 스레드의 부하에 달려 있어 예측할 수 없습니다.

방금 실습에서도 확인했습니다. 압축 직후의 컨슈머 출력에 **액티브 세그먼트의 레코드는 압축되지 않은 채 그대로** 나왔습니다. 트래픽이 있는 실제 토픽이라면 액티브 세그먼트에 같은 키가 수십 개 있는 것이 정상입니다.

```
   컨슈머가 실제로 보게 되는 것 (압축된 구간 + 액티브 구간)

   ┌──────────────────────────┬─────────────────────────────────┐
   │  압축 완료 구간           │  액티브 세그먼트 (압축 안 됨)      │
   │  O-1002:SHIPPED          │  O-1001:PAID                    │
   │  O-1004:PAID             │  O-1003:CREATED                 │
   │                          │  O-1001:SHIPPED   ← 같은 키 중복  │
   │                          │  O-1001:DELIVERED ← 이게 최신     │
   └──────────────────────────┴─────────────────────────────────┘
```

> ⚠️ **함정 — 압축 토픽을 읽으면서 "첫 번째로 만난 값"을 쓰면 옛 상태를 잡습니다**
> 위 그림에서 `O-1001` 을 찾는 컨슈머가 처음 만난 `PAID` 를 쓰고 멈추면 **틀린 상태**를 갖게 됩니다. 실제 최신은 `DELIVERED` 입니다.
> **해결**: 압축 토픽은 반드시 **오프셋 순서대로 끝까지 읽고, 같은 키를 만나면 덮어쓰는 방식**으로 소비해야 합니다.
> ```
> for (record : consumer.poll()) {
>     cache.put(record.key(), record.value());   // 나중 것이 이긴다
> }
> ```
> 이 패턴은 우연히 잘 되는 게 아니라 **압축 토픽의 유일하게 올바른 소비 방식**입니다. Kafka Streams 의 `KTable` 이 정확히 이렇게 동작합니다(Step 13).
> 그리고 이 패턴이 성립하려면 **파티션 내 순서 보장**이 전제입니다. 같은 키가 항상 같은 파티션으로 가야 하고(키 기반 파티셔닝), 파티션 수를 늘리면 그 전제가 깨집니다(Step 03).

---

## 9-8. `compact,delete` 조합

두 정책을 동시에 걸 수 있습니다. **압축도 하고, 오래된 세그먼트 삭제도 합니다.**

```bash
kt --create --topic s09_both --partitions 1 --replication-factor 3 \
   --config cleanup.policy=compact,delete \
   --config retention.ms=604800000 \
   --config segment.ms=3600000
kt --describe --topic s09_both
```

**결과**

```
Topic: s09_both	TopicId: dY6kMz1CQvaTfR3nXbHeLw	PartitionCount: 1	ReplicationFactor: 3	Configs: min.insync.replicas=2,cleanup.policy=compact,delete,segment.ms=3600000,retention.ms=604800000,segment.bytes=1048576
	Topic: s09_both	Partition: 0	Leader: 2	Replicas: 2,3,1	Isr: 2,3,1
```

언제 쓸까요? **상태 토픽인데 무한히 커지면 곤란할 때**입니다.

`compact` 만 걸면 키 하나당 레코드 하나씩 **영원히** 남습니다. 키가 유한하면 괜찮지만(주문 ID 는 유한합니다), 키가 계속 늘어나면(예: 세션 ID, 요청 ID) 토픽이 무한 성장합니다. `delete` 를 함께 걸면 "7일 지난 것은 최신 값이라도 버린다"가 되어 상한이 생깁니다.

| 정책 | 남는 것 | 크기 상한 |
|---|---|---|
| `delete` | 최근 N일 / N바이트의 **모든** 레코드 | 있음 |
| `compact` | **모든 키**의 최신 값 | 없음 (키 수에 비례해 무한 성장 가능) |
| `compact,delete` | 최근 N일 안의 키들의 최신 값 | 있음 |

> 💡 **실무 팁 — Kafka Streams 의 윈도우 상태 저장소가 `compact,delete` 를 씁니다**
> 윈도우 키는 시간이 지날수록 계속 새로 생기므로 `compact` 만으로는 무한 성장합니다.
> Streams 는 changelog 토픽을 `cleanup.policy=compact,delete` + `retention.ms = 윈도우 크기 + grace period` 로 자동 생성합니다. Step 13 에서 그 토픽을 직접 봅니다.

---

## 9-9. 압축 토픽 설계 기준 — `order-events` 는 왜 적합한가

압축 토픽은 아무 데나 쓰면 안 됩니다. **네 가지 조건을 전부 만족해야** 합니다.

| # | 조건 | 위반하면 | `order-events` 는? |
|---|---|---|---|
| 1 | **키가 반드시 있어야 한다** | 키가 `null` 인 레코드는 압축 대상이 아니라 **영원히 쌓입니다** | ✅ 키 = `order_id` |
| 2 | **키 카디널리티가 유한해야 한다** | 키가 무한히 늘면 토픽도 무한 성장 (`compact,delete` 로 완화) | ✅ 주문 수는 유한. 완료 주문은 tombstone 으로 정리 가능 |
| 3 | **값이 "전체 상태"여야 한다 (델타 금지)** | 옛 레코드가 사라지므로 **누적 계산이 불가능** | ✅ 값 = 주문의 현재 상태 전체 |
| 4 | **순서 의존 처리를 하면 안 된다** | 중간 이력이 사라져 상태 전이 추적 불가 | ✅ "최종 상태만 알면 된다"는 용도 |

### 3번이 가장 자주 위반됩니다

```
   ❌ 델타를 압축 토픽에 쓰면
   O-1001: {"amount_delta": +10000}
   O-1001: {"amount_delta": +5000}
   O-1001: {"amount_delta": -3000}
   → 압축 후 마지막 하나만 남는다: {"amount_delta": -3000}
   → 컨슈머가 계산한 금액: -3000원.  실제: 12000원.
   → 에러 없음. 숫자만 틀림.

   ✅ 전체 상태를 쓰면
   O-1001: {"amount": 10000}
   O-1001: {"amount": 15000}
   O-1001: {"amount": 12000}
   → 압축 후: {"amount": 12000}.  정확합니다.
```

**압축은 "옛 레코드를 버려도 된다"를 전제로 합니다.** 그 전제는 값이 **전체 상태**일 때만 성립합니다. 델타는 옛 레코드가 있어야 의미가 있으므로 압축과 근본적으로 상충합니다.

> ⚠️ **함정 — `delete` 토픽을 나중에 `compact` 로 바꾸면 과거가 조용히 사라집니다**
> 운영 중인 `delete` 토픽에 `kconf --alter --add-config cleanup.policy=compact` 를 걸면 **즉시 적용됩니다.** 재시작도 필요 없습니다.
> 그리고 다음 클리너 사이클에서 같은 키의 옛 레코드가 사라집니다. **되돌릴 수 없습니다.**
> 그 토픽에 델타나 이벤트 이력이 들어 있었다면 그 순간 데이터가 손상됩니다. 에러는 나지 않고, 컨슈머는 계속 잘 돌아갑니다.
> **해결**: `cleanup.policy` 변경은 **새 토픽을 만들어 마이그레이션**하는 것이 원칙입니다. 기존 토픽의 정책을 바꾸지 마세요.
> 키가 `null` 인 레코드가 섞여 있으면 더 나쁩니다. 클리너가 `null` 키를 만나면 그 파티션의 청소를 **거부**하고 로그에 경고를 남깁니다.
> ```
> [2024-03-11 11:20:31,904] WARN Cleaner 0: Skipping compaction of s09_bad-0 due to record with null key at offset 47 (kafka.log.LogCleaner)
> ```

### 압축 토픽의 대표 사례 — `__consumer_offsets`

Kafka 자신이 압축 토픽을 씁니다. Step 06 에서 본 `__consumer_offsets` 가 그것입니다.

```bash
kt --describe --topic __consumer_offsets | head -2
```

**결과**

```
Topic: __consumer_offsets	TopicId: cJ4mB8fXQpqLd2VrNsWtHg	PartitionCount: 50	ReplicationFactor: 3	Configs: compression.type=producer,cleanup.policy=compact,segment.bytes=104857600
	Topic: __consumer_offsets	Partition: 0	Leader: 1	Replicas: 1,2,3	Isr: 1,2,3
```

네 조건을 전부 만족합니다. **키** = `(그룹, 토픽, 파티션)`, **카디널리티** 유한, **값** = 커밋된 오프셋(전체 상태, 델타 아님), **순서 의존** 없음(마지막 커밋만 필요). 그룹을 삭제하면 tombstone 이 쓰여 그 키가 사라집니다. 교과서적인 압축 토픽 설계입니다.

---

## 9-10. 정리 (실습 마무리)

```bash
kt --delete --topic s09_ret
kt --delete --topic s09_bytes
kt --delete --topic s09_compact
kt --delete --topic s09_both
kt --list | grep '^s09_' || echo 'OK: s09_ 토픽 없음'
```

**결과**

```
OK: s09_ 토픽 없음
```

토픽 삭제는 **비동기**입니다. 명령은 즉시 돌아오지만 디스크에서 실제로 지워지는 데 `file.delete.delay.ms`(기본 60초)가 걸립니다. 확인해 봅니다.

```bash
docker exec kafka-1 ls /var/lib/kafka/data/ | grep 's09_' || echo '디스크에서도 정리됨'
```

**결과** (60초 안에 실행하면 `.delete` 접미사가 붙은 디렉터리가 보입니다)

```
s09_compact-0.a1b2c3d4e5f6789012345678-delete
```

이 디렉터리는 60초 뒤 자동으로 사라집니다. **기다리면 됩니다.**

```bash
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
```

**결과**

```
NAME       STATUS
kafka-1    Up 47 minutes (healthy)
kafka-2    Up 47 minutes (healthy)
kafka-3    Up 47 minutes (healthy)
kafka-ui   Up 47 minutes
```

---

## 정리

| 개념 | 핵심 |
|---|---|
| 대전제 | **컨슈머가 읽어도 안 지운다.** 지우는 기준은 보존 정책뿐 |
| `cleanup.policy` | `delete`(기본) / `compact` / `compact,delete` |
| `segment.*` vs `retention.*` | 전자는 **롤링**, 후자는 **삭제**. 역할이 다르다 |
| **액티브 세그먼트** | **절대 삭제·압축되지 않는다.** `retention.ms=1000` 이어도 안 지워지는 이유 |
| 롤링 유도 | `segment.ms` 를 걸고 **새 레코드가 들어와야** 롤링 판정이 일어난다 |
| 삭제 순서 | ① `Rolled new log segment` → ② `retention time breach` → ③ `Deleted log ...deleted` |
| 삭제 기준 시각 | 파일 수정 시각이 아니라 **세그먼트 내 최대 레코드 타임스탬프** |
| `retention.bytes` | **파티션당** 크기. 실제 디스크 = 값 × 파티션 수 × RF |
| `retention.ms` + `bytes` | **OR 조건**. 하나만 걸려도 삭제된다 |
| 압축 동작 | 같은 키의 옛 레코드 제거, **오프셋에 구멍이 생기고 번호는 유지** |
| 클리너 로그 | `Beginning cleaning of log` / `dirty section = [0, 12]` / `66.7% size reduction` |
| `min.cleanable.dirty.ratio` | 기본 **0.5** — 로그 절반이 중복이 돼야 청소 시작 |
| `min/max.compaction.lag.ms` | 최소 보장 읽기 시간 / 최대 압축 지연 상한 |
| tombstone | 값이 **`null`**(`valueSize: -1`). `--property null.marker=NULL` 로 생성 |
| 빈 문자열 ≠ null | `valueSize: 0` 은 tombstone 이 아니라 유효한 값 |
| `delete.retention.ms` | 기본 **24시간**. tombstone 을 유지해 뒤늦은 컨슈머가 삭제를 알게 함 |
| **함정 A** | 그보다 오래 멈춘 컨슈머는 **삭제를 영영 못 보고** 잘못된 캐시를 유지 |
| **함정 B** | 압축은 **"최신 값만 보인다"를 보장하지 않는다.** 오프셋 순서대로 읽어 **마지막 값**을 취해야 함 |
| `compact,delete` | 상태 토픽에 크기 상한을 준다. Streams 윈도우 저장소가 이 조합 |
| 압축 설계 기준 | ① 키 필수 ② 카디널리티 유한 ③ **값이 전체 상태(델타 금지)** ④ 순서 의존 금지 |
| 정책 변경 | `delete` → `compact` 는 **되돌릴 수 없다.** 새 토픽으로 마이그레이션할 것 |
| 대표 사례 | `__consumer_offsets` — 네 조건을 모두 만족하는 교과서적 압축 토픽 |

---

## 연습문제

`exercise.sh` 에 7문제가 있습니다. 정답은 `solution.sh`. **`ls -la` 와 `kafka-dump-log.sh` 로 직접 확인**하세요.

1. `retention.ms=5000` 만 걸고 90초를 기다려도 안 지워지는 것을 재현하고, 원인을 한 줄로 설명하기
2. 위 토픽을 실제로 지워지게 만드는 **최소한의 추가 설정**을 찾기
3. 파티션 12개 / RF 3 / `retention.bytes=512MiB` 토픽의 **클러스터 전체 디스크 사용량** 계산하기
4. 같은 키 5개로 20건을 쓰고 압축을 유도해 `kafka-dump-log.sh` 로 **오프셋 구멍**을 확인하기
5. `null.marker` 로 tombstone 을 쓰고, `valueSize: -1` 인지 확인하기 (빈 문자열과 구별)
6. 키가 `null` 인 레코드를 압축 토픽에 넣고 클리너가 어떻게 반응하는지 로그로 확인하기
7. 주어진 토픽 4개가 압축에 적합한지 설계 기준 4가지로 판정하기

---

## 다음 단계

여기까지가 3부 "보장"입니다. 메시지가 어떻게 유실되고, 중복되고, 복제되고, 사라지는지를 전부 직접 재현했습니다.
4부부터는 **메시지의 내용물**로 관심을 옮깁니다. 지금까지 값은 그냥 문자열 JSON 이었지만, 실제 시스템에서는 스키마가 필요합니다.
필드 하나를 추가했을 뿐인데 모든 컨슈머가 조용히 멈추는 사고를 Schema Registry 의 호환성 규칙으로 막는 법을 봅니다.

→ [Step 10 — 직렬화와 스키마](../step-10-serialization/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. Step 08 과 달리 브로커를 죽이지 않으므로 안전하지만, **`sleep` 이 많습니다.** 보존 삭제와 로그 압축은 브로커의 백그라운드 스레드가 하는 일이라 "명령을 치면 즉시 결과"가 나오지 않기 때문입니다. 세 파일 다 통째로 돌리면 5~8분쯤 걸립니다. `sleep` 을 줄이면 관찰 대상이 아직 안 일어나 출력이 교재와 달라집니다.

### practice.sh

본문 9-0 ~ 9-10 의 모든 명령을 절 번호 주석과 함께 담은 실습 스크립트입니다.

- 상단에 `K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }` 와 `DATA=/var/lib/kafka/data` 를 정의합니다. 이 스텝은 CLI 뿐 아니라 **컨테이너 안의 파일 시스템**을 계속 들여다보므로, `docker exec kafka-1 ls -la "$DATA/s09_ret-0"` 형태가 반복됩니다.
- `[9-3]` 이 이 파일의 핵심입니다. `ls -la` 를 **세 번** 찍습니다 — ① 메시지 512건 직후 ② `retention.ms=10000` 으로 90초 대기 후(**변화 없음**) ③ `segment.ms=10000` 추가 + 트리거 1건 + 30초 후(**`00000000000000000000.log` 소멸**). 이 세 스냅샷의 대비가 "액티브 세그먼트는 안 지운다"의 전부입니다.
- 파일 상단의 `WAIT_RET=90` / `WAIT_CLEAN=40` 두 변수가 대기 시간을 통제합니다. 노트북이 느려 관찰이 안 되면 이 값을 1.5배씩 올리세요. 반대로 줄이면 삭제·압축이 아직 안 일어나 "함정을 재현하지 못한" 상태로 통과합니다.
- `[9-5]` 는 같은 키 4개로 12건을 쓴 뒤 `kafka-dump-log.sh --print-data-log | grep '^| offset'` 을 **압축 전후로 두 번** 실행합니다. 두 출력을 나란히 놓으면 오프셋 0~7 이 사라지고 8~11 만 남은 것이 바로 보입니다. `grep` 으로 배치 헤더 줄을 걸러 내는 것이 포인트입니다 — 걸러 내지 않으면 `baseOffset:` 줄에 묻혀 안 보입니다.
- `[9-6]` 의 tombstone 구간은 `--property null.marker=NULL` 을 씁니다. 스크립트는 그 직후 `valueSize: -1` 을 `grep` 으로 확인하고, **없으면 경고를 출력**합니다. `null.marker` 를 지원하지 않는 옛 CLI 를 쓰면 여기서 걸립니다.
- 압축을 유도하는 트리거 메시지(`O-9999` / `O-8888` / `O-7777`)가 세 번 나옵니다. 전부 **액티브 세그먼트를 롤링시키기 위한 것**이며 실습 데이터가 아닙니다. 압축 후 컨슈머 출력에 이 키들이 섞여 나오는 것이 정상입니다.
- 마지막 `[9-10]` 이 `s09_` 토픽 4개를 삭제하고, `ls /var/lib/kafka/data | grep s09_` 로 **디스크에 `-delete` 접미사 디렉터리가 남아 있는지**까지 보여 줍니다. 토픽 삭제가 비동기라는 것을 눈으로 확인하는 구간입니다.

```bash file="./practice.sh"
```

### exercise.sh

7문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·2 는 한 쌍**입니다. 1번에서 `retention.ms=5000` 만 걸고 90초를 기다려 "안 지워지는 것"을 재현하고, 2번에서 그 토픽을 실제로 지워지게 만드는 **최소한의 추가 설정**을 찾습니다. 정답이 `segment.ms` 하나뿐이라는 것, 그리고 설정만으로는 부족하고 **레코드를 하나 더 넣어야** 롤링이 판정된다는 것이 이 쌍의 학습 포인트입니다.
- **문제 3** 은 명령이 아니라 **계산 문제**입니다. 스크립트가 `s09ex_bytes` 토픽(파티션 12, RF 3, `retention.bytes=536870912`)을 만들어 주고, 여러분은 클러스터 전체 디스크 사용량을 계산해 `echo` 로 출력하면 됩니다. `retention.bytes` 가 파티션당이라는 것을 놓치면 18 GiB 라고 답하게 됩니다.
- **문제 4** 는 `s09ex_compact` 에 키 5개(`K-1` ~ `K-5`)로 20건을 쓰고 압축을 유도하는 문제입니다. 압축 후 남는 레코드는 5건이고, 오프셋은 15~19 근처에 몰려 있어야 합니다. `kafka-dump-log.sh` 명령의 **파일 경로를 직접 만들어야** 하는 것도 문제의 일부입니다(세그먼트 파일 이름 = 첫 오프셋 20자리).
- **문제 5** 는 tombstone 과 빈 문자열을 **둘 다** 써서 `valueSize: -1` 과 `valueSize: 0` 을 나란히 확인하는 문제입니다. 두 줄이 함께 보여야 정답이며, 한쪽만 나오면 `null.marker` 설정을 잘못 준 것입니다.
- **문제 6** 은 압축 토픽에 **키 없이**(`parse.key` 를 끄고) 메시지를 넣고 클리너 로그를 확인합니다. `Skipping compaction ... due to record with null key at offset N` 경고가 나와야 하며, 그 파티션의 압축이 그 지점부터 **멈춘다**는 것이 관찰 목표입니다.
- **문제 7** 은 스크립트를 돌리는 문제가 아니라 **판정 문제**입니다. `user-profile-snapshot` / `page-view-log` / `account-balance-delta` / `session-heartbeat` 네 토픽의 설명이 주석으로 주어지고, 설계 기준 4가지에 비추어 압축 적합 여부와 그 이유를 주석으로 채웁니다.
- 파일 끝에 `s09ex_` 토픽을 전부 삭제하는 정리 블록이 있습니다. 브로커를 죽이지 않으므로 Step 08 만큼 위험하지는 않지만, 압축 토픽을 남겨 두면 클리너 스레드가 계속 돌아 다음 스텝의 로그가 지저분해집니다.

```bash file="./exercise.sh"
```

### solution.sh

7문제의 정답 명령과 "왜 그 답인가"를 설명하는 긴 `# 해설:` 주석이 함께 들어 있습니다.

- **정답 1** 의 해설은 `Deleting segment ... due to retention time breach` 로그가 **왜 안 나오는지**에서 출발합니다. 브로커는 10초마다 검사하고 있고, 매번 "삭제 후보 = 닫힌 세그먼트"를 찾는데 **닫힌 세그먼트가 하나도 없어서** 아무 일도 안 일어난다는 것입니다. 로그가 조용한 것이 정상 동작이라는 점을 강조합니다.
- **정답 2** 는 `segment.ms=5000` 하나를 추가하는 것이 답이고, **`kconf --alter` 직후 `ls -la` 를 찍어 봐야 아직 아무 변화가 없다**는 것을 함께 보여 줍니다. 롤링 판정은 브로커가 **append 경로에서** 하기 때문에 새 레코드가 들어와야 합니다. 이 한 줄이 문제 1·2 를 관통하는 핵심입니다.
- **정답 3** 의 계산은 `512 MiB × 12 파티션 × 3 RF = 18 GiB` 이고, 여기에 인덱스 오버헤드(`.index` + `.timeindex` 가 세그먼트당 최대 20 MiB)와 액티브 세그먼트를 더해 **여유율 1.3 을 곱한 23.4 GiB** 를 실무 산정치로 제시합니다. 파티션을 24개로 늘리면 그 순간 두 배가 된다는 경고를 덧붙입니다.
- **정답 4** 는 압축 후 `Start size: 20 messages → End size: 5 messages`, `75.0% size reduction` 로그를 함께 확인시킵니다. 해설의 핵심은 **오프셋 번호가 재부여되지 않는다**는 것 — `seek(3)` 이 에러가 아니라 "3 이상의 첫 레코드"로 이동한다는 동작까지 설명합니다.
- **정답 5** 는 `valueSize: -1`(tombstone) 과 `valueSize: 0`(빈 문자열) 두 줄을 나란히 출력합니다. 해설은 빈 문자열 레코드가 **압축 후에도 그 키의 최신 값으로 살아남아** 컨슈머가 빈 값을 유효한 상태로 캐시에 넣게 된다는 결과까지 추적합니다. "삭제한 줄 알았는데 안 지워진" 실무 사고의 정체입니다.
- **정답 6** 의 관찰 결과는 `WARN Cleaner 0: Skipping compaction of s09ex_nullkey-0 due to record with null key at offset N` 입니다. 해설은 이것이 **경고일 뿐 에러가 아니라는 점**을 강조합니다 — 프로듀서도 컨슈머도 정상이고, 다만 그 파티션의 압축만 조용히 멈춰 토픽이 계속 커집니다. `delete` 토픽을 `compact` 로 바꿀 때 가장 자주 밟는 지뢰입니다.
- **정답 7** 의 판정은 이렇습니다. `user-profile-snapshot` **적합**(네 조건 모두 만족), `page-view-log` **부적합**(키 없음 + 순서·이력이 데이터의 본질), `account-balance-delta` **부적합**(③ 델타 위반 — 압축하면 잔액이 틀림), `session-heartbeat` **조건부 적합**(키는 있으나 카디널리티가 무한 → `compact,delete` 필요). 마지막 문단이 이 스텝의 결론입니다 — **압축은 "이벤트 스트림"이 아니라 "상태 스냅샷"을 위한 도구입니다.**

```bash file="./solution.sh"
```
