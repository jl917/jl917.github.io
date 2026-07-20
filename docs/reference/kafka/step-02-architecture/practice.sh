#!/usr/bin/env bash
# =============================================================================
# Step 02 — 아키텍처와 저장 구조 : practice.sh
#
# 실행:
#   bash step-02-architecture/practice.sh
#
# 이 스크립트는 대부분 읽기 전용입니다. 데이터를 쓰는 구간은 두 곳뿐이며
# ([2-6] 세그먼트 롤링 유도, [2-9] 토픽 재생성) 둘 다 실행 전에 확인을 받습니다.
# =============================================================================
set -uo pipefail

BS=kafka-1:9092
DATA=/var/lib/kafka/data

# 컨테이너 안의 Kafka CLI 를 부르는 헬퍼. 첫 인자가 스크립트 이름입니다.
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
# 컨테이너 안에서 임의의 셸 명령을 실행하는 헬퍼 (ls, cat 등)
S() { docker exec kafka-1 sh -c "$1"; }

hr() { echo; echo "=============================================================="; echo "$*"; echo "=============================================================="; }

# -----------------------------------------------------------------------------
# [2-0] 실습 준비 — 토픽이 있는지 확인
# -----------------------------------------------------------------------------
hr "[2-0] 토픽 목록"
K kafka-topics.sh --bootstrap-server "$BS" --list

# -----------------------------------------------------------------------------
# [2-2] 데이터 디렉터리 들여다보기
# -----------------------------------------------------------------------------
hr "[2-2] 데이터 디렉터리 전체"
S "ls -1 $DATA"

hr "[2-2] orders 파티션 디렉터리만"
S "ls -1d $DATA/orders-*"

hr "[2-2] replication-offset-checkpoint (파티션별 High Watermark)"
# 첫 줄 = 포맷 버전, 둘째 줄 = 엔트리 수, 그 아래 '토픽 파티션 오프셋'
S "cat $DATA/replication-offset-checkpoint"

hr "[2-2] meta.properties (이 브로커의 신원)"
# cluster.id 가 docker-compose.yml 의 CLUSTER_ID 와 일치해야 합니다.
S "cat $DATA/meta.properties"

# -----------------------------------------------------------------------------
# [2-3] 파티션 디렉터리 안의 파일 5종
# -----------------------------------------------------------------------------
hr "[2-3] orders-1 디렉터리 내용"
S "ls -la $DATA/orders-1"

hr "[2-3] partition.metadata — 이 파티션의 TopicId"
S "cat $DATA/orders-1/partition.metadata"

hr "[2-3] leader-epoch-checkpoint — 리더 교체 이력"
# '에포크 시작오프셋' 형식. 리더가 안 바뀌었으면 '0 0' 한 줄뿐입니다.
S "cat $DATA/orders-1/leader-epoch-checkpoint"

# -----------------------------------------------------------------------------
# [2-4] 세그먼트 파일 직접 열어 보기
# -----------------------------------------------------------------------------
# 세그먼트 파일명(base offset)은 그동안 넣은 데이터 양에 따라 달라집니다.
# 하드코딩하지 않고 첫 세그먼트를 동적으로 찾습니다.
SEG=$(S "ls $DATA/orders-1/*.log 2>/dev/null | head -1")

hr "[2-4] 바이너리 그대로 보기 (깨져 보이는 게 정상)"
S "head -c 200 $SEG" || true
echo

hr "[2-4] kafka-dump-log.sh 로 디코딩 : $SEG"
K kafka-dump-log.sh --files "$SEG" --print-data-log

hr "[2-4] 배치 헤더만 보기 (--print-data-log 없이 — 대용량일 때 유용)"
K kafka-dump-log.sh --files "$SEG"

# -----------------------------------------------------------------------------
# [2-5] 인덱스 파일 — 희소 인덱스
# -----------------------------------------------------------------------------
IDX="${SEG%.log}.index"
TIDX="${SEG%.log}.timeindex"

hr "[2-5] .index 덤프"
# "Found 0 out of 1 entries" 경고는 데이터가 적을 때 나오는 정상 출력입니다.
K kafka-dump-log.sh --files "$IDX"

hr "[2-5] .timeindex 덤프"
K kafka-dump-log.sh --files "$TIDX"

# -----------------------------------------------------------------------------
# [2-6] 세그먼트 롤링 관찰  ★ 데이터를 씁니다 ★
# -----------------------------------------------------------------------------
hr "[2-6] 세그먼트 롤링 관찰 — BEFORE"
S "ls -la $DATA/orders-1/*.log"

echo
echo "이 단계는 orders 토픽에 20,000건(약 4MB)을 씁니다."
read -r -p "진행할까요? [y/N] " ans
if [[ "${ans:-N}" =~ ^[Yy]$ ]]; then
  hr "[2-6] 20,000건 전송 (세그먼트 롤링 유도)"
  K kafka-producer-perf-test.sh \
      --topic orders \
      --num-records 20000 \
      --record-size 200 \
      --throughput -1 \
      --producer-props bootstrap.servers="$BS"

  hr "[2-6] 세그먼트 롤링 관찰 — AFTER"
  # 세그먼트가 2개 이상이어야 합니다. 첫 세그먼트 크기가 1MiB(1048576) 근처인지 보세요.
  S "ls -la $DATA/orders-1/*.log"

  hr "[2-6] 새로 생긴 세그먼트(액티브 세그먼트) 덤프 — 첫 3줄"
  NEWSEG=$(S "ls $DATA/orders-1/*.log | tail -1")
  echo "액티브 세그먼트: $NEWSEG"
  # 'Log starting offset' 이 파일명의 base offset 과 같고, position 이 0부터 다시 시작합니다.
  K kafka-dump-log.sh --files "$NEWSEG" | head -4

  hr "[2-6] 전체 파일 목록 (.snapshot 이 새로 생긴 것을 확인)"
  S "ls -1 $DATA/orders-1/"
else
  echo "건너뜁니다."
fi

# -----------------------------------------------------------------------------
# [2-7] KRaft — __cluster_metadata
# -----------------------------------------------------------------------------
hr "[2-7] __cluster_metadata-0 디렉터리 (일반 파티션과 구조가 같습니다)"
S "ls -1 $DATA/__cluster_metadata-0"

hr "[2-7] quorum-state — 지금 액티브 컨트롤러가 누구인가"
S "cat $DATA/__cluster_metadata-0/quorum-state"
echo

hr "[2-7] 메타데이터 로그 디코딩 (브로커 등록 / 토픽 / 파티션 레코드만)"
# 전체 출력은 수백 줄입니다. 아래 grep 을 지우면 전부 볼 수 있습니다.
META=$(S "ls $DATA/__cluster_metadata-0/*.log | head -1")
K kafka-dump-log.sh --cluster-metadata-decoder --files "$META" \
  | grep -E 'REGISTER_BROKER_RECORD|TOPIC_RECORD|PARTITION_RECORD' \
  | head -20

hr "[2-7] KRaft 쿼럼 상태 (CLI 로 같은 정보 보기)"
K kafka-metadata-quorum.sh --bootstrap-server "$BS" describe --status

# -----------------------------------------------------------------------------
# [2-8] 페이지 캐시 확인
# -----------------------------------------------------------------------------
hr "[2-8] 브로커 컨테이너의 메모리 — buff/cache 가 크면 페이지 캐시가 잘 쓰이는 것"
S "free -m" || echo "(free 명령이 없는 이미지입니다 — 건너뜁니다)"

# -----------------------------------------------------------------------------
# [2-9] 정리 — orders 토픽 재생성  ★ 데이터를 지웁니다 ★
# -----------------------------------------------------------------------------
hr "[2-9] 정리"
echo "[2-6] 에서 넣은 20,000건을 지우고 orders 토픽을 새로 만듭니다."
echo "(건너뛰어도 이후 스텝은 동작하지만 Step 05 의 랙 숫자가 교재와 크게 달라집니다)"
read -r -p "orders 토픽을 재생성할까요? [y/N] " ans2
if [[ "${ans2:-N}" =~ ^[Yy]$ ]]; then
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic orders
  # 삭제는 비동기입니다. 디렉터리가 사라질 때까지 잠깐 기다립니다.
  sleep 5
  K kafka-topics.sh --bootstrap-server "$BS" --create --topic orders \
      --partitions 3 --replication-factor 3 \
      --config retention.ms=604800000 \
      --config min.insync.replicas=2

  hr "[2-9] TopicId 가 바뀐 것을 확인"
  # 이름은 같지만 TopicId 가 다릅니다 = 다른 토픽입니다.
  K kafka-topics.sh --bootstrap-server "$BS" --describe --topic orders | head -1
else
  echo "건너뜁니다."
fi

hr "practice.sh 완료"
