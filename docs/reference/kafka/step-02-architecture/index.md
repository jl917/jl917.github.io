# Step 02 — 아키텍처와 저장 구조

> **학습 목표**
> - 브로커·토픽·파티션·세그먼트의 계층 관계를 설명하고, 오프셋이 **파티션 단위**임을 이해한다
> - 브로커의 데이터 디렉터리에 직접 들어가 파티션 디렉터리와 파일 5종의 역할을 확인한다
> - `kafka-dump-log.sh` 로 **세그먼트 파일을 열어** 레코드 배치의 실제 구조를 읽는다
> - 메시지를 밀어 넣어 **세그먼트가 롤링되는 것을 직접 관찰한다**
> - 액티브 세그먼트가 삭제·압축 대상에서 제외된다는 것을 확인한다
> - KRaft 의 `__cluster_metadata` 토픽을 열어 보고 ZooKeeper 방식과 비교한다
>
> **선행 스텝**: [Step 01 — 클러스터 기동과 첫 메시지](../step-01-setup/)
> **예상 소요**: 90분

---

## 2-0. 실습 준비

Step 01 에서 만든 토픽 4개가 있어야 합니다. 확인합니다.

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

이 스텝은 **읽기 위주**입니다. 브로커 내부를 들여다보는 것이 목적이라 컨테이너 셸에 들어가서 작업하는 편이 훨씬 편합니다.

```bash
docker exec -it kafka-1 bash
```

프롬프트가 바뀌면 그 안에서 작업합니다. 나올 때는 `exit`.

```
[appuser@kafka-1 ~]$
```

교재는 두 가지 형태를 섞어 씁니다. 짧은 명령은 `docker exec kafka-1 ...` 로, 파일을 뒤지는 긴 작업은 **컨테이너 셸 안에서** 실행하는 것으로 씁니다. 프롬프트가 `[appuser@kafka-1 ~]$` 로 시작하면 셸 안이라는 뜻입니다.

---

## 2-1. 계층 구조 — 클러스터에서 레코드까지

Kafka 의 저장 구조는 다섯 겹입니다. 위에서 아래로 내려가며 실물을 확인할 것입니다.

```
클러스터 (learn-kafka)
 └── 브로커 (kafka-1, kafka-2, kafka-3)          ← 프로세스 / 서버
      └── 토픽 (orders)                           ← 논리적 이름. 물리 실체 없음
           └── 파티션 (orders-0, orders-1, orders-2)  ← 물리 디렉터리. 순서 보장 단위
                └── 세그먼트 (00000000000000000000.log)  ← 실제 파일
                     └── 레코드 배치 (RecordBatch)         ← 압축·트랜잭션 단위
                          └── 레코드 (key, value, headers, timestamp)
```

핵심은 **토픽은 이름일 뿐이고, 실물은 파티션**이라는 것입니다. `orders` 라는 디렉터리는 어디에도 없습니다. `orders-0`, `orders-1`, `orders-2` 가 있을 뿐이며, 그것도 세 브로커에 흩어져 있습니다.

### 파티션은 추가만 되는 로그입니다

```
orders-1 파티션

  오프셋:   0      1      2      3      4      5
         ┌──────┬──────┬──────┬──────┬──────┬──────┐
         │ C001 │ C001 │ C004 │ C001 │ C009 │ C004 │  ← 계속 뒤에만 붙는다
         └──────┴──────┴──────┴──────┴──────┴──────┘
           ▲                                    ▲
           │                                    │
      log-start-offset                    log-end-offset (다음에 쓸 자리)
```

- **중간에 삽입하거나 수정할 수 없습니다.** 뒤에 붙이는 것(append)만 됩니다.
- 그래서 디스크에 **순차 쓰기**만 발생합니다. HDD 에서도 빠른 이유입니다.
- 컨슈머가 읽어도 **아무것도 지워지지 않습니다.** 지우는 유일한 기준은 보존 정책입니다([Step 09](../step-09-retention-compaction/)).

> ⚠️ **함정 — "3번 메시지"라는 말은 의미가 없습니다**
> 오프셋은 **파티션 단위로 독립적으로 매겨집니다.** `orders-0` 의 오프셋 3과 `orders-1` 의 오프셋 3은 완전히 다른 메시지입니다. 토픽 전역 오프셋 같은 것은 존재하지 않습니다.
> 그래서 "오프셋 1500번 메시지를 다시 처리해 주세요" 라는 요청은 **파티션 번호가 없으면 실행할 수 없습니다.** 장애 대응 때 이것 때문에 시간을 버리는 경우가 많으니, 로그를 남길 때는 항상 `topic-partition@offset` 형태(`orders-1@1500`)로 남기십시오.

---

## 2-2. 데이터 디렉터리 들여다보기

브로커가 실제로 파일을 쓰는 곳은 `/var/lib/kafka/data` 입니다(`log.dirs` 설정).

```bash
docker exec kafka-1 ls -1 /var/lib/kafka/data
```

**결과**

```
__cluster_metadata-0
__consumer_offsets-0
__consumer_offsets-10
__consumer_offsets-13
__consumer_offsets-16
__consumer_offsets-19
...
bootstrap.checkpoint
cleaner-offset-checkpoint
dlq-0
log-start-offset-checkpoint
meta.properties
order-events-1
order-events-2
orders-0
orders-2
payments-0
payments-1
recovery-point-offset-checkpoint
replication-offset-checkpoint
```

읽어 낼 것이 많습니다.

1. **`orders-0` 과 `orders-2` 만 있고 `orders-1` 이 없습니다.** kafka-1 에는 그 두 파티션의 복제본만 있기 때문입니다… 라고 하기엔 이상합니다. 복제 계수가 3이면 모든 브로커에 세 파티션이 다 있어야 합니다. 실제로 다시 확인해 봅니다.

```bash
docker exec kafka-1 ls -1d /var/lib/kafka/data/orders-*
```

**결과**

```
/var/lib/kafka/data/orders-0
/var/lib/kafka/data/orders-1
/var/lib/kafka/data/orders-2
```

세 개가 다 있습니다. 위 목록에서 `orders-1` 이 안 보였던 것은 출력을 줄이려고 중간을 잘라냈기 때문입니다. **복제 계수 3이면 세 브로커 각각에 모든 파티션 디렉터리가 존재합니다.**

2. **`__consumer_offsets-*` 가 잔뜩 있습니다.** 컨슈머 그룹의 오프셋을 저장하는 내부 토픽이며 기본 파티션이 **50개**입니다. [Step 06](../step-06-offsets/) 에서 직접 열어 봅니다.

3. **`__cluster_metadata-0`** — KRaft 의 심장입니다. 2-7 에서 다룹니다.

4. **체크포인트 파일 4종** — 브로커가 재시작할 때 어디서부터 복구할지 기록해 둔 파일들입니다.

| 파일 | 역할 |
|---|---|
| `replication-offset-checkpoint` | 파티션별 **High Watermark**. 어디까지 복제가 확정됐는지 |
| `recovery-point-offset-checkpoint` | 어디까지 디스크에 fsync 됐는지. 재시작 시 이 지점부터 복구 검사 |
| `log-start-offset-checkpoint` | 보존 정책으로 앞부분이 삭제된 뒤의 시작 오프셋 |
| `cleaner-offset-checkpoint` | 로그 클리너가 어디까지 압축했는지([Step 09](../step-09-retention-compaction/)) |

```bash
docker exec kafka-1 cat /var/lib/kafka/data/replication-offset-checkpoint
```

**결과**

```
0
9
orders 0 2
orders 2 2
payments 1 0
order-events 1 0
__consumer_offsets 30 0
orders 1 4
payments 0 0
order-events 2 0
dlq 0 0
```

첫 줄 `0` 은 파일 포맷 버전, 둘째 줄 `9` 는 엔트리 수, 그다음부터 `토픽 파티션 오프셋` 입니다. `orders 1 4` — orders-1 의 HW 가 4 입니다. Step 01 에서 C001 을 4건 보냈던 그 파티션입니다.

5. **`meta.properties`** — 이 브로커의 신원입니다.

```bash
docker exec kafka-1 cat /var/lib/kafka/data/meta.properties
```

**결과**

```
#
#Mon Mar 11 10:20:14 UTC 2024
node.id=1
directory.id=xR7kPqLmT2vNbW9cZaYfGh
version=1
cluster.id=MkU3OEVBNTcwNTJENDM2Qk
```

`cluster.id` 가 여기 박혀 있습니다. `docker-compose.yml` 의 `CLUSTER_ID` 를 바꾸면 이 파일과 어긋나서 브로커가 기동을 거부합니다. [Docker 실습 환경](../docker/) 에서 경고했던 `InconsistentClusterIdException` 이 바로 이 파일 때문입니다.

---

## 2-3. 파티션 디렉터리 안의 파일 5종

`orders-1` 안으로 들어갑니다.

```bash
docker exec kafka-1 ls -la /var/lib/kafka/data/orders-1
```

**결과**

```
total 20
drwxr-xr-x 2 appuser appuser  4096 Mar 11 10:24 .
drwxr-xr-x 1 appuser appuser  4096 Mar 11 10:24 ..
-rw-r--r-- 1 appuser appuser 10485760 Mar 11 10:22 00000000000000000000.index
-rw-r--r-- 1 appuser appuser      412 Mar 11 10:24 00000000000000000000.log
-rw-r--r-- 1 appuser appuser 10485756 Mar 11 10:22 00000000000000000000.timeindex
-rw-r--r-- 1 appuser appuser       10 Mar 11 10:22 leader-epoch-checkpoint
-rw-r--r-- 1 appuser appuser       43 Mar 11 10:22 partition.metadata
```

| 파일 | 크기 | 역할 |
|---|---|---|
| `*.log` | 412 B | **실제 메시지.** 레코드 배치들이 순서대로 쌓인 바이너리 파일 |
| `*.index` | 10 MiB | **오프셋 → 파일 위치** 희소 인덱스. 미리 할당(preallocate)되어 있음 |
| `*.timeindex` | 10 MiB | **타임스탬프 → 오프셋** 희소 인덱스. `--to-datetime` 리셋에 쓰임 |
| `leader-epoch-checkpoint` | 10 B | 리더가 바뀐 이력. **데이터 절단(truncation) 판단**에 씀([Step 08](../step-08-replication/)) |
| `partition.metadata` | 43 B | 이 파티션이 속한 토픽의 `TopicId` |

> 💡 **`.index` 가 10 MiB 인 이유**
> 인덱스 파일은 `log.index.size.max.bytes`(기본 10 MiB)만큼 **미리 할당**됩니다. 데이터가 4바이트뿐이어도 파일 크기는 10 MiB 로 보입니다.
> 그래서 `du -sh` 로 잰 디스크 사용량이 실제 메시지 양보다 훨씬 커 보입니다. 파티션 수가 많으면 이 오버헤드만으로도 수 GiB 가 됩니다. **파티션을 함부로 늘리면 안 되는 이유 중 하나**입니다([Step 03](../step-03-topics-partitions/)).
> 실제 점유량은 `du --apparent-size` 가 아니라 `du` 로 봐야 정확합니다(sparse file).

`partition.metadata` 를 열어 봅니다.

```bash
docker exec kafka-1 cat /var/lib/kafka/data/orders-1/partition.metadata
```

**결과**

```
version: 0
topic_id: qP3mZ8kLQ1u7nR2vXwYtBA
```

Step 01 의 `--describe` 에서 봤던 `TopicId` 와 같습니다. 토픽을 지웠다 같은 이름으로 다시 만들면 이 값이 달라지고, 브로커는 그 차이로 **옛 디렉터리의 잔여 데이터를 구분해서 버립니다.**

`leader-epoch-checkpoint` 도 봅니다.

```bash
docker exec kafka-1 cat /var/lib/kafka/data/orders-1/leader-epoch-checkpoint
```

**결과**

```
0
1
0 0
```

`에포크 시작오프셋` 형식입니다. `0 0` — 에포크 0이 오프셋 0에서 시작했다는 뜻이고, 아직 리더가 한 번도 안 바뀌었습니다. [Step 08](../step-08-replication/) 에서 브로커를 죽이면 이 파일에 줄이 늘어나는 것을 보게 됩니다.

---

## 2-4. 세그먼트 파일을 직접 열어 보기

이제 이 스텝의 본론입니다. `.log` 파일은 바이너리라 `cat` 으로는 못 읽습니다.

```bash
docker exec kafka-1 head -c 200 /var/lib/kafka/data/orders-1/00000000000000000000.log
```

**결과**

```
▒▒▒▒▒▒▒▒▒▒▒2▒▒C001{"order_id":"O-1004","customer_id":"C001",▒▒▒▒
```

JSON 은 어렴풋이 보이지만 나머지는 깨집니다. Kafka 가 이걸 읽어 주는 도구를 제공합니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/orders-1/00000000000000000000.log \
  --print-data-log
```

**결과**

```
Dumping /var/lib/kafka/data/orders-1/00000000000000000000.log
Log starting offset: 0
baseOffset: 0 lastOffset: 0 count: 1 baseSequence: 0 lastSequence: 0 producerId: 1000 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: false isControl: false deleteHorizonMs: OptionalLong.empty position: 0 CreateTime: 1710152521113 size: 122 magic: 2 compresscodec: none crc: 2331120994 isvalid: true
| offset: 0 CreateTime: 1710152521113 keySize: -1 valueSize: 75 sequence: 0 headerKeys: [] payload: {"order_id":"O-1003","customer_id":"C001","amount":7900,"status":"CREATED"}
baseOffset: 1 lastOffset: 3 count: 3 baseSequence: 0 lastSequence: 2 producerId: 1001 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: false isControl: false deleteHorizonMs: OptionalLong.empty position: 122 CreateTime: 1710152698441 size: 290 magic: 2 compresscodec: none crc: 1884213770 isvalid: true
| offset: 1 CreateTime: 1710152698441 keySize: 4 valueSize: 76 sequence: 0 headerKeys: [] key: C001 payload: {"order_id":"O-1004","customer_id":"C001","amount":15000,"status":"CREATED"}
| offset: 2 CreateTime: 1710152698512 keySize: 4 valueSize: 73 sequence: 1 headerKeys: [] key: C001 payload: {"order_id":"O-1006","customer_id":"C001","amount":8800,"status":"PAID"}
| offset: 3 CreateTime: 1710152698587 keySize: 4 valueSize: 74 sequence: 2 headerKeys: [] key: C001 payload: {"order_id":"O-1008","customer_id":"C001","amount":3200,"status":"CREATED"}
```

### 배치 헤더 해독

`baseOffset:` 으로 시작하는 줄이 **레코드 배치** 하나입니다. 개별 메시지가 아니라 **묶음**이 저장 단위입니다.

| 필드 | 값 | 뜻 |
|---|---|---|
| `baseOffset` / `lastOffset` | 1 / 3 | 이 배치가 담은 오프셋 범위 |
| `count` | 3 | 배치 안의 레코드 수 |
| `producerId` / `producerEpoch` | 1001 / 0 | **멱등 프로듀서의 신원.** 브로커가 중복을 걸러내는 근거([Step 07](../step-07-delivery-semantics/)) |
| `baseSequence` / `lastSequence` | 0 / 2 | 프로듀서가 매긴 시퀀스. 이것도 중복 판정용 |
| `partitionLeaderEpoch` | 0 | 이 배치를 받을 당시의 리더 에포크([Step 08](../step-08-replication/)) |
| `isTransactional` | false | 트랜잭션 소속 여부([Step 07](../step-07-delivery-semantics/)) |
| `isControl` | false | 컨트롤 레코드(커밋/중단 마커)인지 |
| `position` | 122 | 파일 안에서의 **바이트 위치** |
| `magic` | 2 | 레코드 포맷 버전. v2 는 0.11 부터 |
| `compresscodec` | none | 압축 코덱. **배치 단위**로 적용됨([Step 04](../step-04-producer/)) |
| `size` | 290 | 배치 전체 바이트 |

**세 건이 하나의 배치로 묶였습니다.** 이것이 Kafka 성능의 핵심입니다. 100건을 보내도 네트워크 요청은 배치 하나이고, 압축도 배치 전체를 한 번에 하므로 압축률이 훨씬 좋습니다.

첫 배치는 `count: 1` 인데, Step 01 에서 콘솔 프로듀서로 한 줄씩 엔터를 쳤기 때문입니다. 두 번째 배치는 heredoc 으로 세 줄을 한 번에 밀어 넣어 `count: 3` 이 되었습니다.

### 개별 레코드 줄

```
| offset: 1 CreateTime: 1710152698441 keySize: 4 valueSize: 76 sequence: 0 headerKeys: [] key: C001 payload: {...}
```

- `keySize: -1` 은 **키가 null** 이라는 뜻입니다. 첫 레코드가 그렇습니다(Step 01 에서 키 없이 보냄).
- `headerKeys: []` — 헤더가 없습니다. 헤더는 [Step 12](../step-12-connect/) 의 DLQ 컨텍스트에서 씁니다.
- `CreateTime` — 프로듀서가 찍은 타임스탬프입니다. 토픽 설정 `message.timestamp.type` 을 `LogAppendTime` 으로 바꾸면 **브로커가 받은 시각**으로 대체됩니다.

> 💡 **실무 팁 — `kafka-dump-log.sh` 는 장애 분석의 최종 수단입니다**
> "컨슈머가 이상한 메시지를 받았다" 는 신고가 오면, 컨슈머 로그를 보기 전에 **로그 파일에 실제로 뭐가 들어 있는지** 확인하는 것이 가장 빠릅니다. 프로듀서가 잘못 보낸 것인지, 컨슈머가 잘못 읽은 것인지가 한 번에 갈립니다.
> `--print-data-log` 를 빼면 배치 헤더만 나와서 훨씬 빠릅니다. 대용량 세그먼트를 볼 때는 헤더만 먼저 보십시오.

---

## 2-5. 인덱스 파일 — 희소 인덱스

`.index` 도 같은 도구로 열립니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/orders-1/00000000000000000000.index
```

**결과**

```
Dumping /var/lib/kafka/data/orders-1/00000000000000000000.index
offset: 0 position: 0
Found 0 out of 1 entries. This indicates that the log format version is different from the index format version, or the index is corrupted.
```

경고 문구가 무섭게 생겼지만 **정상입니다.** 데이터가 412바이트뿐이라 인덱스 엔트리가 사실상 없기 때문입니다.

Kafka 의 인덱스는 **희소(sparse)** 합니다. 모든 레코드를 인덱싱하지 않고, `log.index.interval.bytes`(기본 4096) 바이트마다 한 번씩만 기록합니다.

```
.log 파일                          .index 파일
┌──────────────────────────┐      offset  position
│ offset 0   position 0    │  ──►    0        0
│ offset 1   position 122  │
│ offset 2   position 218  │        4KB 를 넘을 때마다 한 줄
│ ...                      │
│ offset 47  position 4103 │  ──►   47      4103
│ ...                      │
│ offset 93  position 8210 │  ──►   93      8210
└──────────────────────────┘
```

**오프셋 60을 찾는 절차**: 인덱스에서 60보다 작거나 같은 가장 큰 항목(47, position 4103)을 이진 탐색으로 찾고, 파일의 4103바이트 지점으로 점프한 뒤 **거기서부터 순차로 읽어** 60에 도달합니다.

전부 인덱싱하지 않는 이유는 명확합니다. **인덱스가 메모리에 올라가야 빠른데**, 전건 인덱싱을 하면 인덱스가 데이터만큼 커집니다. 4KB 간격이면 1 GiB 세그먼트의 인덱스가 대략 수 MiB 로 유지됩니다.

`.timeindex` 도 같은 구조이며, `타임스탬프 → 오프셋` 을 담습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/orders-1/00000000000000000000.timeindex
```

**결과**

```
Dumping /var/lib/kafka/data/orders-1/00000000000000000000.timeindex
timestamp: 0 offset: 0
Found 0 out of 1 entries. This indicates that the log format version is different from the index format version, or the index is corrupted.
```

이 파일 덕분에 `kafka-consumer-groups.sh --reset-offsets --to-datetime` 이 동작합니다([Step 06](../step-06-offsets/)).

> ⚠️ **함정 — 세그먼트 파일을 손으로 지우면 안 됩니다**
> 디스크가 가득 찼을 때 `.log` 파일을 `rm` 하고 싶은 유혹이 옵니다. **절대 하지 마십시오.**
> `.log` 를 지우면 `.index`, `.timeindex`, 체크포인트 파일들과 어긋나고, 브로커는 재시작 시 손상된 로그로 판단해 **파티션 전체를 복구하거나 오프라인으로 만듭니다.**
> **올바른 방법**은 토픽의 `retention.ms` 를 잠깐 낮춰 브로커가 스스로 지우게 하는 것입니다([Step 09](../step-09-retention-compaction/)).
> ```bash
> kconf --alter --entity-type topics --entity-name orders --add-config retention.ms=60000
> # 삭제된 뒤 원복
> kconf --alter --entity-type topics --entity-name orders --delete-config retention.ms
> ```

---

## 2-6. 세그먼트 롤링을 직접 관찰하기

파티션은 하나의 거대한 파일이 아니라 **세그먼트 여러 개**로 나뉩니다. 지금은 세그먼트가 하나뿐입니다.

```bash
docker exec kafka-1 ls -1 /var/lib/kafka/data/orders-1/*.log
```

**결과**

```
/var/lib/kafka/data/orders-1/00000000000000000000.log
```

이 클러스터는 `log.segment.bytes` 가 **1 MiB** 로 낮춰져 있습니다([Docker 실습 환경](../docker/)). 1 MiB 를 넘기면 새 세그먼트가 생길 것입니다. 데이터를 밀어 넣습니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic orders \
  --num-records 20000 \
  --record-size 200 \
  --throughput -1 \
  --producer-props bootstrap.servers=kafka-1:9092
```

**결과**

```
20000 records sent, 41841.004184 records/sec (7.98 MB/sec), 118.42 ms avg latency, 402.00 ms max latency, 105 ms 50th, 298 ms 95th, 371 ms 99th, 399 ms 99.9th.
```

20,000건 × 200바이트 ≈ 4 MB 를 3개 파티션에 나눴으니 파티션당 1.3 MB 정도입니다. 다시 봅니다.

```bash
docker exec kafka-1 ls -1 /var/lib/kafka/data/orders-1/
```

**결과**

```
00000000000000000000.index
00000000000000000000.log
00000000000000000000.timeindex
00000000000000004821.index
00000000000000004821.log
00000000000000004821.snapshot
00000000000000004821.timeindex
leader-epoch-checkpoint
partition.metadata
```

**세그먼트가 두 개가 되었습니다.** 크기도 확인합니다.

```bash
docker exec kafka-1 ls -la /var/lib/kafka/data/orders-1/*.log
```

**결과**

```
-rw-r--r-- 1 appuser appuser 1048269 Mar 11 10:41 /var/lib/kafka/data/orders-1/00000000000000000000.log
-rw-r--r-- 1 appuser appuser  341882 Mar 11 10:41 /var/lib/kafka/data/orders-1/00000000000000004821.log
```

첫 세그먼트가 **1,048,269 바이트** — 1 MiB(1,048,576)에 거의 닿았습니다. 여기서 롤링이 일어났습니다.

### 파일 이름의 의미

> ⚠️ **함정 — `.log` 파일 이름은 순번이 아니라 base offset 입니다**
> `00000000000000004821.log` 는 "4821번째 세그먼트"가 아니라 **"오프셋 4821부터 시작하는 세그먼트"** 입니다. 20자리 zero-padding 된 base offset 이며, 그래서 파일명 정렬이 곧 오프셋 순서가 됩니다.
> 이걸 순번으로 오해하면 "세그먼트가 4821개나 생겼다"고 착각해 엉뚱한 대응을 하게 됩니다.

`.snapshot` 파일이 새로 보이는데, 이것은 **프로듀서 상태 스냅샷**입니다. 멱등 프로듀서의 `producerId` → 시퀀스 매핑을 담고 있어, 브로커 재시작 시 중복 판정 상태를 복구합니다([Step 07](../step-07-delivery-semantics/)).

### 새 세그먼트를 dump 해 봅니다

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files /var/lib/kafka/data/orders-1/00000000000000004821.log | head -4
```

**결과**

```
Dumping /var/lib/kafka/data/orders-1/00000000000000004821.log
Log starting offset: 4821
baseOffset: 4821 lastOffset: 4913 count: 93 baseSequence: 4821 lastSequence: 4913 producerId: 1002 producerEpoch: 0 partitionLeaderEpoch: 0 isTransactional: false isControl: false deleteHorizonMs: OptionalLong.empty position: 0 CreateTime: 1710152841003 size: 19204 magic: 2 compresscodec: none crc: 3018472118 isvalid: true
baseOffset: 4914 lastOffset: 5006 count: 93 baseSequence: 4914 lastSequence: 5006 partitionLeaderEpoch: 0 ...
```

`Log starting offset: 4821` 이고 `position: 0` 부터 다시 시작합니다. **세그먼트마다 파일 내 위치는 0부터입니다.**

배치의 `count: 93` 도 눈여겨보십시오. perf-test 는 빠르게 밀어 넣으므로 93건씩 묶였습니다. 콘솔 프로듀서의 `count: 1` 과 대조적입니다. **배치 크기가 처리량을 좌우한다**는 것이 [Step 04](../step-04-producer/) 와 [Step 11](../step-11-performance/) 의 주제입니다.

### 액티브 세그먼트

가장 마지막 세그먼트, 즉 지금 쓰고 있는 세그먼트를 **액티브 세그먼트**라고 합니다. 여기서는 `00000000000000004821.log` 입니다.

> 💡 **액티브 세그먼트는 삭제되지도, 압축되지도 않습니다**
> 보존 정책(`retention.ms`)이 아무리 짧아도 액티브 세그먼트는 건드리지 않습니다. 지금도 쓰고 있는 파일이기 때문입니다.
> 이 사실이 [Step 09](../step-09-retention-compaction/) 에서 결정적입니다. `retention.ms=1000` 을 걸어도 메시지가 안 사라지는 것을 보게 될 텐데, 원인이 바로 이것입니다. **세그먼트가 롤링되어야 삭제 대상이 됩니다.**
> 그래서 보존을 확실히 하려면 `segment.ms`(시간 기준 롤링)를 함께 설정합니다.

세그먼트가 롤링되는 조건은 셋 중 하나입니다.

| 조건 | 설정 | 기본값 | 이 클러스터 |
|---|---|---|---|
| 크기 초과 | `segment.bytes` | 1 GiB | **1 MiB** |
| 시간 경과 | `segment.ms` | 7일 | 7일 |
| 인덱스 가득 | `segment.index.bytes` | 10 MiB | 10 MiB |

---

## 2-7. KRaft — 메타데이터는 어디에 있는가

`__cluster_metadata-0` 디렉터리가 있었습니다. 이것이 KRaft 의 실체입니다.

```bash
docker exec kafka-1 ls -1 /var/lib/kafka/data/__cluster_metadata-0
```

**결과**

```
00000000000000000000.index
00000000000000000000.log
00000000000000000000.timeindex
leader-epoch-checkpoint
partition.metadata
quorum-state
```

**일반 파티션과 구조가 똑같습니다.** 메타데이터도 그냥 Kafka 로그입니다. 이것이 KRaft 의 핵심 아이디어입니다. 열어 봅니다.

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --cluster-metadata-decoder \
  --files /var/lib/kafka/data/__cluster_metadata-0/00000000000000000000.log | head -30
```

**결과**

```
Dumping /var/lib/kafka/data/__cluster_metadata-0/00000000000000000000.log
Log starting offset: 0
baseOffset: 0 lastOffset: 0 count: 1 ... isControl: true ...
| offset: 0 CreateTime: 1710152414221 keySize: 4 valueSize: 19 sequence: -1 headerKeys: [] endTxnMarker: LEADER_CHANGE
baseOffset: 1 lastOffset: 1 count: 1 ...
| offset: 1 CreateTime: 1710152414665 keySize: -1 valueSize: 33 sequence: -1 headerKeys: [] payload: {"type":"REGISTER_BROKER_RECORD","version":0,"data":{"brokerId":1,"incarnationId":"xR7kPqLmT2vNbW9cZaYfGh","brokerEpoch":7,"endPoints":[{"name":"INTERNAL","host":"kafka-1","port":9092,"securityProtocol":0}],"rack":null,"fenced":true}}
| offset: 2 ... payload: {"type":"REGISTER_BROKER_RECORD","version":0,"data":{"brokerId":2,...}}
| offset: 3 ... payload: {"type":"REGISTER_BROKER_RECORD","version":0,"data":{"brokerId":3,...}}
| offset: 8 ... payload: {"type":"TOPIC_RECORD","version":0,"data":{"name":"orders","topicId":"qP3mZ8kLQ1u7nR2vXwYtBA"}}
| offset: 9 ... payload: {"type":"PARTITION_RECORD","version":0,"data":{"partitionId":0,"topicId":"qP3mZ8kLQ1u7nR2vXwYtBA","replicas":[1,2,3],"isr":[1,2,3],"leader":1,"leaderEpoch":0,"partitionEpoch":0}}
| offset: 10 ... payload: {"type":"PARTITION_RECORD","version":0,"data":{"partitionId":1,"topicId":"qP3mZ8kLQ1u7nR2vXwYtBA","replicas":[2,3,1],"isr":[2,3,1],"leader":2,"leaderEpoch":0,"partitionEpoch":0}}
```

Step 01 에서 `--describe` 로 봤던 정보가 **여기 로그 레코드로 그대로 들어 있습니다.** `TOPIC_RECORD` 로 토픽이 만들어지고, `PARTITION_RECORD` 로 각 파티션의 `replicas`/`isr`/`leader` 가 기록됩니다.

메타데이터 변경은 **모두 이 로그에 append 됩니다.** 브로커는 이 로그를 읽어 자기 상태를 갱신합니다. `--describe` 가 보여 주는 것은 이 로그를 재생한 결과입니다.

`quorum-state` 도 봅니다.

```bash
docker exec kafka-1 cat /var/lib/kafka/data/__cluster_metadata-0/quorum-state
```

**결과**

```
{"clusterId":"","leaderId":2,"leaderEpoch":4,"votedId":-1,"appliedOffset":0,"currentVoters":[{"voterId":1},{"voterId":2},{"voterId":3}],"data_version":0}
```

Step 01 의 `kafka-metadata-quorum.sh` 출력과 같은 내용입니다. `leaderId: 2` — kafka-2 가 액티브 컨트롤러입니다.

### KRaft vs ZooKeeper

| 항목 | ZooKeeper 모드 (~3.x, 폐기 예정) | KRaft 모드 (3.3+ 프로덕션, 4.0 부터 유일) |
|---|---|---|
| 메타데이터 저장 | ZooKeeper znode | **`__cluster_metadata` 토픽** (Kafka 자신) |
| 운영 컴포넌트 | Kafka + ZooKeeper **2종** | Kafka **1종** |
| 컨트롤러 | 브로커 중 1대가 겸임 | 전용 컨트롤러 쿼럼(3 또는 5대) |
| 컨트롤러 장애 복구 | znode 를 **전부 다시 읽음** — 파티션 수에 비례해 수십 초~수 분 | 로그를 **이어서 읽음** — 보통 **1초 미만** |
| 메타데이터 전파 | 컨트롤러가 브로커에 **푸시**(`UpdateMetadata` RPC) | 브로커가 로그를 **풀** |
| 파티션 확장 한계 | 수만 개 수준에서 컨트롤러 병목 | **수백만 파티션** 목표 |
| 설정 | `zookeeper.connect` | `process.roles`, `controller.quorum.voters` |

Step 01 에서 `LeaderAndIsr` / `UpdateMetadata` / `StopReplica` API 가 `UNSUPPORTED` 로 나왔던 이유가 이제 명확합니다. **푸시 방식이 사라졌기 때문**입니다.

> 💡 **실무 팁 — 새 클러스터는 무조건 KRaft 입니다**
> Kafka 3.7 은 ZooKeeper 모드를 아직 지원하지만 **deprecated** 이고, 4.0 부터 제거되었습니다. 지금 새로 구축한다면 선택의 여지가 없습니다.
> 기존 ZooKeeper 클러스터의 마이그레이션 경로(`zookeeper.metadata.migration.enable`)가 3.6+ 에 있지만, 무중단 마이그레이션은 난이도가 높습니다. **가능하면 새 클러스터를 세우고 MirrorMaker 2 로 옮기는 편**이 안전합니다.

---

## 2-8. 왜 Kafka 는 빠른가 — 페이지 캐시와 zero-copy

지금까지 본 구조가 성능으로 이어지는 지점을 정리합니다.

### 1) 순차 쓰기

파티션은 append-only 이므로 디스크 헤드가 움직이지 않습니다. 순차 쓰기는 랜덤 쓰기보다 **수백 배** 빠릅니다.

### 2) 페이지 캐시에 의존

Kafka 는 자체 캐시를 거의 두지 않고 **OS 페이지 캐시**를 씁니다. 브로커 JVM 힙이 6 GiB 여도, 서버 메모리 64 GiB 중 나머지 전부가 캐시로 쓰입니다.

```bash
docker exec kafka-1 free -m
```

**결과**

```
               total        used        free      shared  buff/cache   available
Mem:            7838        1904         412          12        5521        5622
```

`buff/cache` 가 5.5 GiB 입니다. 최근 쓴 세그먼트는 여기 올라와 있어 **컨슈머가 읽을 때 디스크를 안 칩니다.**

> 💡 **실무 팁 — 브로커 힙을 크게 잡지 마십시오**
> "메모리가 많으니 힙을 32 GiB 주자"는 잘못된 직관입니다. 힙을 키우면 **페이지 캐시가 줄어들고**, GC 정지도 길어집니다.
> 권장은 힙 **6 GiB 안팎**이고 나머지는 OS 에 맡기는 것입니다. Kafka 는 힙에 큰 것을 올려 두지 않는 설계입니다.

### 3) zero-copy (sendfile)

컨슈머에게 데이터를 보낼 때, 보통이라면 `디스크 → 커널 버퍼 → 애플리케이션 버퍼 → 소켓 버퍼 → NIC` 4단계 복사가 일어납니다. Kafka 는 `sendfile(2)` 시스템 콜로 **커널 버퍼에서 소켓 버퍼로 바로** 보냅니다.

```
일반 방식                        zero-copy (sendfile)
디스크                            디스크
  │                                 │
  ▼                                 ▼
커널 페이지 캐시                   커널 페이지 캐시
  │  (복사)                          │
  ▼                                 │ (복사 없이 바로)
JVM 힙 버퍼                         │
  │  (복사)                          │
  ▼                                 ▼
소켓 버퍼 ──► NIC                  소켓 버퍼 ──► NIC
```

**단, 조건이 있습니다.** 브로커가 데이터를 손대지 않아야 합니다. 프로듀서가 보낸 배치를 **그대로** 전달할 때만 zero-copy 가 성립합니다.

> ⚠️ **함정 — 브로커에서 재압축이 일어나면 zero-copy 가 깨집니다**
> 토픽의 `compression.type` 이 프로듀서와 다르면(예: 프로듀서 `lz4`, 토픽 `gzip`) 브로커가 **압축을 풀고 다시 압축**합니다. 그 순간 zero-copy 도, CPU 절약도 사라집니다.
> 그래서 토픽 설정은 `compression.type=producer`(기본값)로 두어 **프로듀서가 압축한 그대로 저장**하게 하는 것이 정석입니다. [Step 04](../step-04-producer/) 에서 압축을 다룰 때 다시 나옵니다.

---

## 2-9. 정리 (실습 마무리)

2-6 에서 `orders` 토픽에 20,000건을 밀어 넣었습니다. 이후 스텝의 실습에 방해가 되므로 그대로 두어도 되지만, 깔끔하게 시작하고 싶다면 토픽을 다시 만듭니다.

```bash
kt --delete --topic orders
kt --create --topic orders --partitions 3 --replication-factor 3 \
   --config retention.ms=604800000 --config min.insync.replicas=2
```

**결과**

```
Created topic orders.
```

삭제 후 재생성하면 `TopicId` 가 바뀝니다. 확인해 봅니다.

```bash
kt --describe --topic orders | head -1
```

**결과**

```
Topic: orders	TopicId: fH9wKlOpQrT4uYiAsDfGhJ	PartitionCount: 3	ReplicationFactor: 3	Configs: min.insync.replicas=2,segment.bytes=1048576,retention.ms=604800000
```

`qP3mZ8kLQ1u7nR2vXwYtBA` → `fH9wKlOpQrT4uYiAsDfGhJ`. 이름은 같지만 **다른 토픽**입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 토픽 | 이름일 뿐. 물리 실체는 **파티션 디렉터리** |
| 파티션 | append-only 로그. **순서 보장과 병렬성의 단위** |
| 오프셋 | **파티션 단위.** 토픽 전역 오프셋은 없음 → 로그는 `topic-partition@offset` 으로 |
| 데이터 디렉터리 | `/var/lib/kafka/data`. 파티션마다 디렉터리 하나 |
| `.log` | 실제 메시지. **파일명 = base offset**(순번 아님) |
| `.index` / `.timeindex` | **희소** 인덱스. 4KB 마다 한 줄. 10 MiB 미리 할당 |
| `partition.metadata` | 그 파티션의 `TopicId`. 지웠다 만들면 값이 바뀜 |
| `leader-epoch-checkpoint` | 리더 교체 이력. 데이터 절단 판단에 사용 |
| 레코드 배치 | 저장·압축·트랜잭션의 **실제 단위**. 개별 레코드가 아님 |
| `producerId`/`sequence` | 멱등 프로듀서의 중복 판정 근거 |
| 세그먼트 롤링 | `segment.bytes` / `segment.ms` / 인덱스 가득 중 하나 |
| **액티브 세그먼트** | 지금 쓰는 세그먼트. **절대 삭제·압축되지 않음** |
| `__cluster_metadata` | KRaft 메타데이터. **그냥 Kafka 토픽** |
| KRaft 이점 | 컨트롤러 복구 수분 → **1초 미만**, 운영 컴포넌트 2종 → 1종 |
| 성능의 근원 | 순차 쓰기 + **페이지 캐시** + zero-copy |
| 재압축 | zero-copy 를 깨뜨림. 토픽은 `compression.type=producer` 로 |

---

## 연습문제

`exercise.sh` 에 6문제가 있습니다. 정답은 `solution.sh`.

1. `payments` 토픽의 파티션 디렉터리가 kafka-2 에 몇 개 있는지 세고, 왜 그 개수인지 설명하기
2. `orders-0` 의 첫 세그먼트를 dump 해서 **배치 개수**와 **총 레코드 수**를 각각 구하기
3. 메시지를 밀어 넣어 `dlq` 토픽에 세그먼트를 2개 이상 만들고, 두 번째 세그먼트의 base offset 확인하기
4. 어떤 파티션의 `.log` 실제 크기와 `.index` 파일 크기를 비교하고, 왜 인덱스가 더 큰지 설명하기
5. `__cluster_metadata` 를 디코딩해 `TOPIC_RECORD` 만 골라내 토픽 생성 이력 뽑기
6. `replication-offset-checkpoint` 에서 `orders` 파티션들의 HW 를 읽고 `kafka-get-offsets.sh` 결과와 대조하기

---

## 다음 단계

저장 구조를 봤으니 이제 그 구조를 **설계**할 차례입니다. 파티션을 몇 개로 할지, 나중에 바꿀 수 있는지, 바꾸면 무엇이 깨지는지를 다룹니다. 특히 **파티션은 늘릴 수만 있고 줄일 수 없다**는 제약과, 파티션을 늘리는 순간 키 기반 순서 보장이 조용히 깨지는 문제를 직접 재현합니다.

→ [Step 03 — 토픽과 파티션](../step-03-topics-partitions/)

---

## 실습 파일

이 스텝은 셸 스크립트 세 개로 진행합니다. `practice.sh` 는 2-1 ~ 2-9 의 모든 조회 명령을 담고 있으며, 대부분이 **읽기 전용**이라 몇 번을 돌려도 안전합니다. 예외는 2-6 의 세그먼트 롤링 유도(20,000건 전송)와 2-9 의 토픽 재생성뿐이고, 두 구간은 스크립트 안에서 확인 프롬프트로 감싸 두었습니다.

### practice.sh

본문의 모든 명령을 절 번호 주석(`# [2-4]`)과 함께 담은 실행 스크립트입니다.

- 상단에 `K()` 헬퍼와 함께 `DATA=/var/lib/kafka/data` 를 정의합니다. 데이터 디렉터리 경로가 본문에 수십 번 나오므로 변수로 뺐습니다. 다른 이미지를 쓰거나 `log.dirs` 를 바꿨다면 이 한 줄만 고치면 됩니다.
- `[2-3]` 과 `[2-4]` 는 **파티션 디렉터리를 하드코딩하지 않습니다.** `SEG=$(docker exec kafka-1 sh -c "ls $DATA/orders-1/*.log | head -1")` 로 첫 세그먼트 경로를 동적으로 찾습니다. 여러분의 세그먼트 base offset 이 교재와 다를 수 있기 때문입니다(밀어 넣은 데이터 양에 따라 달라집니다).
- `[2-6]` 의 `kafka-producer-perf-test.sh` 는 이 스크립트에서 **유일하게 데이터를 쓰는 구간**입니다. `orders` 토픽에 20,000건이 들어가므로, 이후 스텝을 깨끗한 상태로 시작하고 싶다면 `[2-9]` 를 반드시 실행하십시오. 스크립트는 실행 전 `read -p` 로 한 번 확인합니다.
- `[2-6]` 뒤의 세그먼트 확인은 `ls -la ... /*.log` 를 **전송 전과 후 두 번** 실행합니다. 이 before/after 대조가 이 절의 핵심이므로 출력 두 개를 나란히 두고 비교하십시오.
- `[2-7]` 의 `--cluster-metadata-decoder` 는 출력이 매우 깁니다(수백 줄). 스크립트는 `grep -E 'TOPIC_RECORD|PARTITION_RECORD|REGISTER_BROKER'` 로 걸러 필요한 세 종류만 보여 줍니다. 전체를 보고 싶으면 그 파이프를 지우십시오.
- `[2-9]` 의 토픽 재생성은 `read -p` 확인 후에만 실행됩니다. `n` 을 입력하면 건너뜁니다. 20,000건을 남겨 두어도 이후 스텝에 치명적이지는 않지만, [Step 05](../step-05-consumer/) 의 랙 실습에서 숫자가 교재와 크게 달라집니다.

```bash file="./practice.sh"
```

### exercise.sh

6문제의 문제지입니다. 각 문제는 `# 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1** 은 `docker exec kafka-2 ls` 로 **kafka-1 이 아닌 브로커**를 봐야 한다는 게 포인트입니다. 습관적으로 kafka-1 을 치면 답은 맞지만 문제의 의도를 놓칩니다. 정답 개수는 3이며, 복제 계수가 3이므로 모든 브로커에 모든 파티션이 있기 때문입니다.
- **문제 2** 는 `kafka-dump-log.sh` 출력에서 `^baseOffset` 줄 수(배치 수)와 `^|` 줄 수(레코드 수)를 각각 세는 문제입니다. `--print-data-log` 를 빼면 레코드 줄이 안 나와 후자를 셀 수 없다는 점이 함정입니다.
- **문제 3** 은 `dlq` 가 파티션 1개짜리라 롤링에 필요한 데이터량이 `orders`(3파티션)보다 적다는 점을 이용합니다. 대략 1.2 MB 이상이면 됩니다. 문제지는 `--num-records` 를 비워 두었고, 너무 적게 넣으면 세그먼트가 하나뿐이라 실패합니다.
- **문제 4** 는 `ls -la`(apparent size)와 `du -h`(실제 점유)의 차이를 보는 문제입니다. `.index` 는 10 MiB 로 보이지만 sparse file 이라 실제 점유는 훨씬 작습니다. **두 명령의 결과가 다른 것**이 정답의 핵심입니다.
- **문제 5** 는 `--cluster-metadata-decoder` 출력에 `grep -o '"name":"[^"]*"'` 를 걸어 토픽 이름만 뽑는 형태입니다. `TOPIC_RECORD` 안에만 `name` 이 있는 게 아니라 `endPoints` 에도 있으므로, `grep 'TOPIC_RECORD'` 로 먼저 줄을 거른 뒤 이름을 뽑아야 정확합니다.
- **문제 6** 은 checkpoint 파일의 HW 와 `kafka-get-offsets.sh` 의 LEO 를 비교합니다. **평상시엔 두 값이 같습니다.** 다르다면 복제가 진행 중이라는 뜻이며, 이 관찰이 [Step 08](../step-08-replication/) 의 출발점이 됩니다.

```bash file="./exercise.sh"
```

### solution.sh

6문제의 정답 명령과 **왜 그 답인지** 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `ls -1d $DATA/payments-* | wc -l` 로 3을 얻습니다. 주석은 "복제 계수 = 브로커 수"인 이 클러스터에서만 모든 브로커가 모든 파티션을 갖는다는 점, 브로커가 5대이고 RF 가 3이면 브로커마다 파티션 수가 달라진다는 점을 설명합니다.
- **정답 2** 는 `grep -c '^baseOffset'` 과 `grep -c '^|'` 두 명령입니다. 주석이 강조하는 것은 **배치 수 ≠ 레코드 수**이며, 그 비율(레코드/배치)이 곧 배치 효율이라는 점입니다. 콘솔 프로듀서로 넣은 구간은 1:1 이고 perf-test 구간은 1:93 입니다. [Step 11](../step-11-performance/) 에서 이 숫자를 직접 조작하게 됩니다.
- **정답 3** 은 `--num-records 8000 --record-size 200` 으로 약 1.6 MB 를 넣습니다. 주석은 "왜 1 MiB 가 아니라 1.6 MB 인가"를 설명합니다. 레코드 오버헤드(키·헤더·배치 헤더)가 붙어 실제 파일이 페이로드보다 크고, 롤링은 배치 경계에서만 일어나므로 여유를 둬야 하기 때문입니다.
- **정답 4** 는 `ls -la` 와 `du -h --apparent-size` 와 `du -h` 셋을 나란히 실행합니다. 주석이 sparse file 개념을 설명하고, **모니터링에서 디스크 사용량을 `ls` 로 재면 과대 계상된다**는 실무 함정을 짚습니다.
- **정답 5** 는 `--cluster-metadata-decoder ... | grep TOPIC_RECORD | grep -o '"name":"[^"]*"'` 입니다. 결과가 토픽 생성 **순서대로** 나온다는 점이 핵심입니다. 메타데이터 로그는 append-only 이므로 이 출력이 곧 클러스터의 변경 이력입니다. 주석은 이것이 감사(audit) 용도로 유용하다는 점을 덧붙입니다.
- **정답 6** 은 두 출력을 정렬해 `diff` 로 비교하는 형태입니다. 주석은 **HW ≤ LEO 가 항상 성립**하고, 정상 상태에서는 같으며, 차이가 나면 그 파티션의 복제가 뒤처졌다는 신호라고 설명합니다. 그리고 컨슈머는 **HW 까지만 읽을 수 있다**는 것이 [Step 08](../step-08-replication/) 의 핵심임을 예고합니다.

```bash file="./solution.sh"
```
