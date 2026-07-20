#!/usr/bin/env bash
# =============================================================================
# Step 02 — 아키텍처와 저장 구조 : solution.sh  (정답 + 해설)
#
# 실행:
#   bash step-02-architecture/solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여세요.
# =============================================================================
set -uo pipefail

BS=kafka-1:9092
DATA=/var/lib/kafka/data

K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
S()  { docker exec kafka-1 sh -c "$1"; }
S2() { docker exec kafka-2 sh -c "$1"; }

hr() { echo; echo "=============================================================="; echo "$*"; echo "=============================================================="; }

# =============================================================================
# 정답 1 — kafka-2 의 payments 파티션 디렉터리 개수
# =============================================================================
hr "정답 1"

S2 "ls -1d $DATA/payments-*"
echo "개수: $(S2 "ls -1d $DATA/payments-* | wc -l")"

# 해설:
#   정답은 3입니다.
#
#   payments 는 --partitions 3 --replication-factor 3 으로 만들었습니다.
#   복제 계수 3 = 각 파티션의 복제본이 3개 = 브로커가 정확히 3대인 이 클러스터에서는
#   "모든 브로커가 모든 파티션의 복제본을 하나씩" 갖게 됩니다.
#
#   여기서 오해하기 쉬운 것:
#   "리더가 아닌 파티션은 디렉터리가 없다" 고 생각하기 쉽지만 틀립니다.
#   팔로워도 리더와 똑같이 .log/.index/.timeindex 를 전부 갖습니다.
#   팔로워는 리더에게서 데이터를 fetch 해서 자기 로그에 그대로 append 합니다.
#   그래야 리더가 죽었을 때 즉시 리더가 될 수 있습니다.
#
#   중요 — 이 "브로커 수 == 복제 계수" 는 학습 클러스터의 우연입니다.
#   브로커가 5대이고 RF 가 3이면, 파티션마다 복제본이 놓이는 브로커 3대가 다르므로
#   브로커별 파티션 디렉터리 개수가 서로 달라집니다.
#   그 배치를 조정하는 것이 Step 14 의 kafka-reassign-partitions.sh 입니다.


# =============================================================================
# 정답 2 — orders-0 첫 세그먼트의 배치 수와 레코드 수
# =============================================================================
hr "정답 2"

SEG0=$(S "ls $DATA/orders-0/*.log 2>/dev/null | head -1")
echo "대상 세그먼트: $SEG0"

echo
echo "(a) 레코드 배치 개수:"
K kafka-dump-log.sh --files "$SEG0" | grep -c '^baseOffset'

echo
echo "(b) 레코드 총 개수:"
K kafka-dump-log.sh --files "$SEG0" --print-data-log | grep -c '^|'

# 해설:
#   (a) 배치는 'baseOffset:' 으로 시작하는 줄입니다. --print-data-log 없이도 나옵니다.
#   (b) 개별 레코드는 '|' 로 시작하는 줄이며, --print-data-log 를 줘야만 출력됩니다.
#       이 옵션 없이는 배치 헤더만 나오기 때문입니다.
#
#   ★ 이 문제의 핵심은 "배치 수 != 레코드 수" 라는 것입니다.
#
#   Kafka 의 저장·전송·압축 단위는 개별 레코드가 아니라 '레코드 배치' 입니다.
#   레코드/배치 비율이 곧 배치 효율이고, 그것이 처리량을 결정합니다.
#
#     - 콘솔 프로듀서로 한 줄씩 엔터 친 구간 → count: 1  (비율 1:1, 최악)
#     - perf-test 로 밀어 넣은 구간         → count: 93 (비율 1:93, 좋음)
#
#   같은 100만 건이라도 배치가 1건씩이면 네트워크 요청이 100만 번이고,
#   100건씩이면 1만 번입니다. 100배 차이입니다.
#   이 비율을 linger.ms / batch.size 로 직접 조작하는 것이 Step 04 와 Step 11 입니다.
#
#   덤으로: 배치 헤더에 producerId/baseSequence 가 있다는 점도 확인해 두세요.
#   브로커가 중복 배치를 걸러내는 근거이며 Step 07(멱등 프로듀서)의 재료입니다.


# =============================================================================
# 정답 3 — dlq 에 세그먼트 2개 만들기
# =============================================================================
hr "정답 3"

echo "BEFORE:"
S "ls -la $DATA/dlq-0/*.log"

echo
echo "8,000건 x 200B 전송 (약 1.6MB)"
K kafka-producer-perf-test.sh \
    --topic dlq \
    --num-records 8000 \
    --record-size 200 \
    --throughput -1 \
    --producer-props bootstrap.servers="$BS"

echo
echo "AFTER:"
S "ls -la $DATA/dlq-0/*.log"

echo
echo "두 번째 세그먼트의 base offset:"
S "ls -1 $DATA/dlq-0/*.log | tail -1 | xargs basename | sed 's/^0*//; s/\.log$//'"

# 해설:
#   왜 1MiB 가 아니라 1.6MB 를 넣었는가?
#
#   1) segment.bytes 는 1MiB(1,048,576) 이지만, 페이로드 200B 가 그대로 1건이 아닙니다.
#      레코드마다 오프셋 델타/타임스탬프 델타/키 길이/헤더 개수 등의 메타데이터가 붙고,
#      배치마다 61바이트의 배치 헤더가 붙습니다.
#      즉 파일에 쌓이는 실제 바이트는 페이로드 합계보다 큽니다.
#
#   2) 롤링은 '배치 경계' 에서만 일어납니다.
#      브로커는 "이 배치를 추가하면 segment.bytes 를 넘는가?" 를 보고 새 세그먼트를 엽니다.
#      배치 하나가 20KB 라면 최대 20KB 를 넘긴 지점에서 롤링됩니다.
#
#   3) dlq 는 파티션이 1개라 모든 데이터가 dlq-0 한 곳에 쌓입니다.
#      orders(3파티션)라면 같은 8,000건이 세 갈래로 나뉘어 파티션당 약 0.53MB 라
#      롤링이 일어나지 않습니다. ★ 파티션 수를 고려해야 한다는 게 이 문제의 함정입니다.
#
#   그래서 여유 있게 1.5~2배를 넣습니다. 4,000건(0.8MB)이면 대개 실패합니다.
#
#   두 번째 세그먼트의 base offset 은 "첫 세그먼트가 담은 마지막 오프셋 + 1" 입니다.
#   파일명이 20자리 zero-padding 이라 sed 로 앞의 0 을 떼야 숫자로 읽힙니다.
#   ★ 이 숫자는 '세그먼트 순번' 이 아니라 '오프셋' 입니다. 절대 헷갈리지 마세요.


# =============================================================================
# 정답 4 — .log vs .index, 표시 크기 vs 실제 점유량
# =============================================================================
hr "정답 4"

echo "(a) ls -la — 파일이 '주장하는' 크기(apparent size)"
S "ls -la $DATA/orders-0/ | grep -E '\.log|\.index'"

echo
echo "(b) du — 실제로 디스크를 점유한 블록 수"
S "du -h $DATA/orders-0/*.log $DATA/orders-0/*.index"

echo
echo "(참고) du --apparent-size — (a)와 같은 값이 나옵니다"
S "du -h --apparent-size $DATA/orders-0/*.log $DATA/orders-0/*.index" 2>/dev/null || echo "(busybox du 에는 --apparent-size 가 없을 수 있습니다)"

# 해설:
#   .index 는 ls 로 보면 10MiB 인데 du 로 보면 몇 KB 밖에 안 됩니다.
#
#   이유는 두 가지가 겹쳐 있습니다.
#
#   1) 미리 할당(preallocation)
#      Kafka 는 인덱스 파일을 log.index.size.max.bytes(기본 10MiB)만큼 미리 잡습니다.
#      세그먼트를 쓰는 도중에 인덱스 파일을 늘리면 mmap 을 다시 잡아야 해서 느리기 때문에,
#      아예 최대 크기로 만들어 두고 앞에서부터 채웁니다.
#      세그먼트가 롤링되어 닫힐 때 실제 사용한 크기로 잘라냅니다(truncate).
#
#   2) sparse file
#      아직 안 쓴 뒷부분은 실제 디스크 블록을 차지하지 않습니다.
#      파일 시스템이 "여기는 전부 0" 이라고만 기록해 둡니다.
#      그래서 ls(파일 길이)와 du(점유 블록)의 값이 크게 벌어집니다.
#
#   ★ 실무 함정 — 디스크 사용량을 ls 나 --apparent-size 로 재면 과대 계상됩니다.
#     파티션이 1,000개면 인덱스만으로 "10GiB 를 쓰고 있다" 는 잘못된 경보가 뜹니다.
#     실제 점유는 du(옵션 없이) 로 봐야 하고,
#     Kafka 자체 지표로는 kafka-log-dirs.sh 를 쓰는 것이 정확합니다 (Step 14).
#
#     그리고 이 미리 할당 오버헤드가 "파티션을 함부로 늘리면 안 되는 이유" 중 하나입니다.
#     액티브 세그먼트 하나당 .index + .timeindex 로 최대 20MiB 를 잡아 두기 때문입니다.
#     Step 03 에서 파티션 수 결정 기준을 다룹니다.


# =============================================================================
# 정답 5 — 토픽 생성 이력 뽑기
# =============================================================================
hr "정답 5"

META=$(S "ls $DATA/__cluster_metadata-0/*.log | head -1")
echo "대상: $META"
echo

K kafka-dump-log.sh --cluster-metadata-decoder --files "$META" \
  | grep 'TOPIC_RECORD' \
  | grep -o '"name":"[^"]*"' \
  | sed 's/"name":"//; s/"$//'

# 해설:
#   왜 grep 을 두 번 하는가?
#
#   "name" 이라는 필드는 TOPIC_RECORD 에만 있는 게 아닙니다.
#   REGISTER_BROKER_RECORD 의 endPoints 안에도 {"name":"INTERNAL", ...} 이 있습니다.
#   그래서 바로 '"name"' 을 뽑으면 INTERNAL / EXTERNAL 같은 리스너 이름이 섞여 나옵니다.
#
#   해결은 단순합니다. 먼저 TOPIC_RECORD 인 줄만 남기고, 그 안에서 name 을 뽑습니다.
#   (kafka-dump-log 는 레코드 하나를 한 줄로 출력하므로 줄 단위 필터링이 유효합니다)
#
#   ★ 결과가 '생성한 순서대로' 나온다는 점이 이 문제의 핵심입니다.
#
#   __cluster_metadata 는 append-only 로그입니다. 즉 이 출력은 단순한 목록이 아니라
#   "이 클러스터에서 토픽이 만들어진 시간 순서" 그 자체입니다.
#   같은 이름의 토픽을 지웠다 다시 만들었다면 TOPIC_RECORD 가 두 번 나오고,
#   topicId 가 서로 다릅니다. 감사(audit) 용도로 매우 유용합니다.
#
#   ZooKeeper 모드에서는 이런 이력을 얻을 수 없었습니다.
#   znode 는 '현재 상태' 만 갖고 있어서, 언제 누가 무엇을 바꿨는지가 남지 않았습니다.
#   KRaft 가 메타데이터를 로그로 바꾼 부수 효과입니다.


# =============================================================================
# 정답 6 — HW(체크포인트) vs LEO(get-offsets)
# =============================================================================
hr "정답 6"

echo "[HW - replication-offset-checkpoint 의 orders 항목]"
S "grep '^orders ' $DATA/replication-offset-checkpoint | sort -k2 -n"

echo
echo "[LEO - kafka-get-offsets.sh]"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic orders | sort

# 해설:
#   두 값의 의미가 다릅니다.
#
#     LEO (Log End Offset)  = 리더의 로그에 '쓰인' 마지막 위치 + 1
#     HW  (High Watermark)  = ISR 의 '모든' 복제본이 받아 간 위치 + 1
#
#   항상 HW <= LEO 가 성립합니다.
#   프로듀서가 방금 쓴 메시지는 LEO 에 반영되지만, 팔로워들이 아직 못 가져갔으면
#   HW 는 그대로입니다. 팔로워가 fetch 를 마치면 HW 가 따라 올라갑니다.
#
#   ★ 그리고 컨슈머는 HW 까지만 읽을 수 있습니다.
#     복제되지 않은 메시지를 컨슈머에게 보여 줬다가 리더가 죽으면,
#     "읽었는데 사라진 메시지" 가 생기기 때문입니다.
#     이 규칙이 Kafka 내구성 모델의 핵심이며 Step 08 의 주제입니다.
#
#   정상 상태(트래픽이 없을 때)에는 두 값이 같습니다.
#   차이가 지속적으로 벌어진다면 복제가 뒤처지고 있다는 뜻이고,
#   방치하면 팔로워가 ISR 에서 빠집니다(replica.lag.time.max.ms, 기본 30초).
#   ISR 이 줄면 min.insync.replicas 를 못 채워 프로듀서가 거부당할 수 있습니다.
#
#   참고 — checkpoint 파일은 즉시 갱신되지 않습니다.
#   브로커가 주기적으로(기본 5초, replica.high.watermark.checkpoint.interval.ms)
#   기록하므로, 방금 쓴 메시지가 파일에 아직 반영이 안 됐을 수 있습니다.
#   실시간 값은 파일이 아니라 kafka-get-offsets.sh 나 JMX 로 봐야 정확합니다 (Step 14).


hr "solution.sh 완료"
