#!/usr/bin/env bash
# =============================================================================
# Step 01 — 클러스터 기동과 첫 메시지 : 실습 스크립트
#
# 실행법:
#   bash step-01-setup/practice.sh          # 통째로 실행
#   bash -x step-01-setup/practice.sh       # 한 줄씩 에코하며 실행 (권장)
#
# 전제:
#   kafka/docker 디렉터리에서 docker compose up -d 가 가능한 상태
#
# 주의:
#   [터미널 B] 주석이 붙은 구간은 대화형이라 이 스크립트에서 실행되지 않습니다.
#   해당 명령은 별도 창에서 직접 실행하세요. 스크립트에는 같은 것을 자동으로
#   확인할 수 있는 비대화형 버전(--timeout-ms / 파이프 입력)을 넣어 두었습니다.
# =============================================================================
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }   # 표준입력을 넘길 때

hr() { echo; echo "=== $* ==================================================="; }

# -----------------------------------------------------------------------------
# [1-0] 클러스터 기동과 healthy 대기
# -----------------------------------------------------------------------------
hr "[1-0] 클러스터 기동"

# compose 파일 위치. 이 스크립트를 리포지토리 어디서 실행하든 동작하도록 계산합니다.
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"
echo "compose dir: $COMPOSE_DIR"

docker compose -f "$COMPOSE_DIR/docker-compose.yml" up -d

wait_healthy() {
  # 세 브로커가 모두 (healthy) 가 될 때까지 최대 120초 대기.
  # 이 대기 없이 바로 토픽을 만들면 Broker may not be available 로 실패합니다.
  local i cnt
  for i in $(seq 1 24); do
    cnt=$(docker compose -f "$COMPOSE_DIR/docker-compose.yml" ps | grep -c '(healthy)' || true)
    echo "  healthy brokers: $cnt/3  (${i}/24)"
    [ "$cnt" -ge 3 ] && return 0
    sleep 5
  done
  echo "브로커가 healthy 가 되지 않았습니다. docker compose logs kafka-1 을 확인하세요." >&2
  return 1
}
wait_healthy

docker compose -f "$COMPOSE_DIR/docker-compose.yml" ps

# -----------------------------------------------------------------------------
# [1-1] 브로커 3대 인식 확인 + KRaft 쿼럼 상태
# -----------------------------------------------------------------------------
hr "[1-1] 브로커 3대 확인"

# 세 줄이 나와야 정상입니다. 한 줄만 나오면 쿼럼 구성이 잘못된 것입니다.
K kafka-broker-api-versions.sh --bootstrap-server "$BS" | grep -E '^kafka-[0-9]'

echo "--- KRaft quorum status ---"
K kafka-metadata-quorum.sh --bootstrap-server "$BS" describe --status

# -----------------------------------------------------------------------------
# [1-2] 편의 별칭 — 스크립트에서는 alias 대신 함수(K)를 씁니다
# -----------------------------------------------------------------------------
hr "[1-2] 토픽 목록 (아직 비어 있음)"

K kafka-topics.sh --bootstrap-server "$BS" --list

# 참고: 대화형 셸에서는 아래 별칭을 쓰세요.
#   alias kt='docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092'
#   alias kcg='docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092'
#   alias kconf='docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka-1:9092'
#   alias kgo='docker exec kafka-1 /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka-1:9092'

# -----------------------------------------------------------------------------
# [1-3] 첫 토픽 orders 만들기
# -----------------------------------------------------------------------------
hr "[1-3] orders 토픽 생성"

# || true : 이미 있으면 TopicExistsException 이 나는데, set -e 로 죽지 않게 합니다.
#           이 스크립트를 몇 번을 다시 돌려도 안전하도록(idempotent) 만든 것입니다.
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic orders \
  --partitions 3 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2 || true

echo "--- 브로커 수보다 큰 RF 는 즉시 거절됩니다 (일부러 실패시키는 명령) ---"
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic bad-rf --partitions 1 --replication-factor 5 || true

# -----------------------------------------------------------------------------
# [1-4] --describe 출력 해독
# -----------------------------------------------------------------------------
hr "[1-4] orders --describe"

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic orders

echo "--- 운영에서 가장 많이 치는 두 변형 (정상이면 아무 출력도 없습니다) ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions
K kafka-topics.sh --bootstrap-server "$BS" --describe --unavailable-partitions
echo "(위 두 명령의 출력이 비어 있으면 정상입니다)"

# -----------------------------------------------------------------------------
# [1-5] 첫 메시지 — 키 없이
# -----------------------------------------------------------------------------
hr "[1-5] 키 없는 메시지 3건 전송"

# [터미널 A] 대화형 컨슈머 (스크립트에서는 실행하지 않습니다)
#   docker exec -it kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic orders --from-beginning
#
# [터미널 B] 대화형 프로듀서 (스크립트에서는 실행하지 않습니다)
#   docker exec -it kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
#     --bootstrap-server kafka-1:9092 --topic orders

# 비대화형 대체: 표준 입력을 파이프로 밀어 넣습니다.
printf '%s\n' \
  '{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}' \
  '{"order_id":"O-1002","customer_id":"C002","amount":15000,"status":"CREATED"}' \
  '{"order_id":"O-1003","customer_id":"C003","amount":72000,"status":"CREATED"}' \
| Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic orders

echo "--- --from-beginning 으로 읽기 (--timeout-ms 로 자동 종료) ---"
# --timeout-ms : 그 시간 동안 새 메시지가 없으면 컨슈머를 끝냅니다.
#                스크립트에서 콘솔 컨슈머를 쓰는 유일하게 안전한 방법입니다.
#                종료 시 TimeoutException 을 던지므로 || true 로 받습니다.
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --from-beginning --timeout-ms 5000 2>&1 | grep -v TimeoutException || true

# -----------------------------------------------------------------------------
# [1-6] 함정 ① --from-beginning 이 없으면 아무것도 안 읽힙니다
# -----------------------------------------------------------------------------
hr "[1-6] 함정 --from-beginning 유무 비교"

echo "--- (A) --from-beginning 있음 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --from-beginning --timeout-ms 5000 2>&1 | grep 'Processed a total' || true

echo "--- (B) --from-beginning 없음  → Processed a total of 0 messages 가 나옵니다 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --timeout-ms 5000 2>&1 | grep 'Processed a total' || true

echo "--- 데이터가 없는 것이 아니라 안 읽는 것임을 증명 ---"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic orders

# -----------------------------------------------------------------------------
# [1-7] 키 있는 메시지 — 같은 키는 같은 파티션
# -----------------------------------------------------------------------------
hr "[1-7] 키 있는 메시지 4건 전송"

printf '%s\n' \
  'C001:{"order_id":"O-1004","customer_id":"C001","amount":21000,"status":"CREATED"}' \
  'C001:{"order_id":"O-1005","customer_id":"C001","amount":33000,"status":"PAID"}' \
  'C006:{"order_id":"O-1006","customer_id":"C006","amount":58000,"status":"CREATED"}' \
  'C002:{"order_id":"O-1007","customer_id":"C002","amount":12000,"status":"CREATED"}' \
| Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic orders \
    --property parse.key=true \
    --property key.separator=:

echo "--- 키/파티션/오프셋을 전부 찍어서 확인 ---"
# C001 두 건이 같은 파티션(2)에 들어갔는지 보세요.
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true \
  --property print.partition=true \
  --property print.offset=true \
  --property key.separator=' | ' 2>&1 | grep -v TimeoutException || true

# -----------------------------------------------------------------------------
# [1-8] 함정 ② 오타 난 토픽 이름
# -----------------------------------------------------------------------------
hr "[1-8] 함정 오타 토픽 (auto.create.topics.enable=false)"

# 기본 타임아웃이 60초라 스크립트가 1분 멈춥니다. 5초로 줄입니다.
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic oders \
  --from-beginning --timeout-ms 5000 \
  --consumer-property default.api.timeout.ms=5000 2>&1 | tail -5 || true

echo "--- 브로커의 auto.create.topics.enable 확인 (synonyms 로 출처를 봅니다) ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type brokers --entity-name 1 --all \
  | grep auto.create.topics.enable

# -----------------------------------------------------------------------------
# [1-9] 콘솔 프로듀서의 acks — 3.7 기본값은 -1(all)
# -----------------------------------------------------------------------------
hr "[1-9] --producer-property 로 설정을 바꾸고 실제 적용 여부 확인"

# 프로듀서는 기동 시 ProducerConfig values: 로 전체 설정을 덤프합니다.
# "내 설정이 진짜 먹었나"를 확인하는 가장 확실한 방법입니다.
printf '%s\n' 'C001:{"order_id":"O-1008","customer_id":"C001","amount":9000,"status":"CREATED"}' \
| Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic orders \
    --property parse.key=true --property key.separator=: \
    --producer-property acks=0 \
    --producer-property linger.ms=100 2>&1 \
| grep -E '^\s+(acks|linger\.ms|compression\.type) ' || true

# -----------------------------------------------------------------------------
# [1-10] 나머지 코스 토픽 생성
# -----------------------------------------------------------------------------
hr "[1-10] payments / order-events / dlq 생성"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic payments --partitions 3 --replication-factor 3 \
  --config retention.ms=604800000 --config min.insync.replicas=2 || true

# order-events 는 compact. 키마다 최신 값 하나만 남습니다 (Step 09).
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic order-events --partitions 3 --replication-factor 3 \
  --config cleanup.policy=compact --config min.insync.replicas=2 || true

# dlq 는 순서가 중요하므로 파티션 1개, 보존 30일.
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic dlq --partitions 1 --replication-factor 3 \
  --config retention.ms=2592000000 --config min.insync.replicas=2 || true

K kafka-topics.sh --bootstrap-server "$BS" --list

echo "--- compact 토픽 확인 (Configs 에 cleanup.policy=compact) ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic order-events

echo "--- 브로커 기본값과 다른 설정을 가진 토픽만 ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topics-with-overrides

# -----------------------------------------------------------------------------
# [1-11] kafka-ui — 브라우저로 확인 (스크립트에서 할 일 없음)
# -----------------------------------------------------------------------------
hr "[1-11] kafka-ui"
echo "브라우저에서 http://localhost:8080 을 열고 Topics → orders → Messages 를 확인하세요."
echo "위 [1-7] 의 CLI 출력과 정확히 같은 Partition/Offset/Key 가 보여야 합니다."

# -----------------------------------------------------------------------------
# [1-12] CLI 도구 지도
# -----------------------------------------------------------------------------
hr "[1-12] /opt/kafka/bin 의 도구 목록"

K bash -c 'ls /opt/kafka/bin/ | grep "^kafka-" | head -30' 2>/dev/null \
  || docker exec kafka-1 sh -c 'ls /opt/kafka/bin/ | grep "^kafka-" | head -30'

echo "--- kafka-*.sh 는 전부 kafka-run-class.sh 의 얇은 래퍼입니다 ---"
docker exec kafka-1 tail -1 /opt/kafka/bin/kafka-topics.sh

# -----------------------------------------------------------------------------
# [1-13] 정리 — 토픽은 지우지 않습니다. Step 02 가 이 데이터를 씁니다.
# -----------------------------------------------------------------------------
hr "[1-13] 정리 및 상태 검증"

echo "--- 토픽은 정확히 4개여야 합니다 ---"
K kafka-topics.sh --bootstrap-server "$BS" --list

echo "--- orders 의 파티션별 메시지 수 (합계 8건: 1-5 의 3건 + 1-7 의 4건 + 1-9 의 1건) ---"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic orders

echo
echo "Step 01 완료. orders 의 메시지는 Step 02 에서 세그먼트 파일로 직접 열어 봅니다."
echo "지우지 마세요."
