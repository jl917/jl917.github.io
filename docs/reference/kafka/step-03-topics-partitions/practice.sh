#!/usr/bin/env bash
# =============================================================================
# Step 03 — 토픽과 파티션 : 실습 스크립트
#
# 실행법:
#   bash step-03-topics-partitions/practice.sh
#   bash -x step-03-topics-partitions/practice.sh     # 권장 (중간에 sleep 이 깁니다)
#
# 전제:
#   Step 01 의 토픽 4개(orders/payments/order-events/dlq)가 있는 상태
#
# 주의:
#   [3-6] 구간에서 sleep 65 로 1분 넘게 멈춥니다. 토픽 삭제가 비동기임을
#   눈으로 확인하기 위한 것이니 기다리세요.
#   공용 토픽 4개의 설정은 절대 바꾸지 않습니다. 모든 변경은 s03_ 토픽에서만 합니다.
# =============================================================================
set -euo pipefail

BS=kafka-1:9092
K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

hr() { echo; echo "=== $* ==================================================="; }

# 특정 토픽의 파티션별 메시지 수를 한 줄씩 요약합니다. [3-5] 에서 반복해서 씁니다.
PARTS() {
  K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$1" \
    --from-beginning --timeout-ms 5000 --property print.partition=true 2>/dev/null \
    | grep -oE '^Partition:[0-9]+' | sort | uniq -c
}

# -----------------------------------------------------------------------------
# [3-0] 실습 준비
# -----------------------------------------------------------------------------
hr "[3-0] 실습 준비 — 공용 토픽 4개 확인"

K kafka-topics.sh --bootstrap-server "$BS" --list

# -----------------------------------------------------------------------------
# [3-1] 토픽 수명주기
# -----------------------------------------------------------------------------
hr "[3-1] --alter 로는 설정을 못 바꿉니다 (일부러 실패시키는 명령)"

# 옛 문서를 보고 이 형태를 치는 사람이 많습니다. 먼저 실패를 보여 줍니다.
K kafka-topics.sh --bootstrap-server "$BS" \
  --alter --topic orders --config retention.ms=3600000 || true

hr "[3-1] s03_demo 생성"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03_demo \
  --partitions 3 --replication-factor 3 \
  --config retention.ms=86400000 \
  --config max.message.bytes=2097152 || true

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s03_demo

hr "[3-1] --describe 의 유용한 변형들"

echo "--- 브로커 기본값과 다른 설정을 가진 토픽만 ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topics-with-overrides

echo "--- 아래 세 명령은 출력이 없어야 정상입니다 ---"
echo "(under-replicated)"
K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions
echo "(unavailable)"
K kafka-topics.sh --bootstrap-server "$BS" --describe --unavailable-partitions
echo "(under-min-isr)"
K kafka-topics.sh --bootstrap-server "$BS" --describe --under-min-isr-partitions
echo "(끝 — 위에 아무것도 안 나왔으면 정상입니다)"

# -----------------------------------------------------------------------------
# [3-2] kafka-configs.sh 와 synonyms
# -----------------------------------------------------------------------------
hr "[3-2] 동적 설정 변경"

K kafka-configs.sh --bootstrap-server "$BS" \
  --alter --entity-type topics --entity-name s03_demo \
  --add-config retention.ms=3600000

K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type topics --entity-name s03_demo

echo "--- 여러 설정을 한 번에 (쉼표 구분) ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --alter --entity-type topics --entity-name s03_demo \
  --add-config 'retention.ms=7200000,segment.ms=600000,cleanup.policy=delete'

echo "--- --delete-config 전의 synonyms 를 저장 ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type topics --entity-name s03_demo --all \
  | grep -E '^\s+retention\.ms' > /tmp/before-synonyms.txt
cat /tmp/before-synonyms.txt

echo "--- --delete-config 로 오버라이드 제거 (값을 안 씁니다) ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --alter --entity-type topics --entity-name s03_demo \
  --delete-config 'retention.ms,segment.ms'

echo "--- --delete-config 후의 synonyms ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type topics --entity-name s03_demo --all \
  | grep -E '^\s+retention\.ms' > /tmp/after-synonyms.txt
cat /tmp/after-synonyms.txt

echo "--- 차이 (DYNAMIC_TOPIC_CONFIG 항목이 사라집니다) ---"
diff /tmp/before-synonyms.txt /tmp/after-synonyms.txt || true

echo "--- 실제 적용 중인 값 전부 보기: --all ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type topics --entity-name s03_demo --all \
  | grep -E '^\s+(retention\.ms|segment\.bytes|cleanup\.policy|min\.insync\.replicas)'

echo "--- 동적으로 못 바꾸는 브로커 설정 (일부러 실패시키는 명령) ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --alter --entity-type brokers --entity-name 1 \
  --add-config auto.create.topics.enable=true || true

# -----------------------------------------------------------------------------
# [3-4] 파티션은 늘릴 수만 있습니다
# -----------------------------------------------------------------------------
hr "[3-4] 파티션 3 → 6 (성공)"

K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_demo --partitions 6
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s03_demo

hr "[3-4] 파티션 6 → 2 (실패 — 줄일 수 없습니다)"
K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_demo --partitions 2 || true

hr "[3-4] 파티션 6 → 6 (실패 — 같은 수도 거절됩니다)"
K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_demo --partitions 6 || true

# -----------------------------------------------------------------------------
# [3-5] 핵심 함정 — 파티션 증가가 키 순서 보장을 깨뜨립니다
# -----------------------------------------------------------------------------
hr "[3-5] s03_keyed 3파티션 생성"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03_keyed --partitions 3 --replication-factor 3 || true

echo "--- C001~C010 의 첫 번째 주문(seq=1) 전송 ---"
for i in $(seq -w 1 10); do
  echo "C0${i}:{\"order_id\":\"O-1${i}\",\"customer_id\":\"C0${i}\",\"seq\":1}"
done | Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s03_keyed \
    --property parse.key=true --property key.separator=:

echo "--- 3파티션에서의 키별 배치 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s03_keyed \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true 2>/dev/null | sort

echo "--- 3파티션 분포 (2 / 2 / 6 이 나옵니다. 키가 10개뿐이라 균등하지 않은 것이 정상) ---"
PARTS s03_keyed

hr "[3-5] 파티션 3 → 6 으로 확장"

K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_keyed --partitions 6

# 중요: 프로듀서가 새 메타데이터를 받아 오는 데 시간이 필요합니다.
# 이 sleep 을 빼면 프로듀서가 옛 파티션 수(3)로 계산해 함정이 재현되지 않습니다.
sleep 2

echo "--- 같은 C001~C010 의 두 번째 주문(seq=2) 전송. 에러가 하나도 안 납니다 ---"
for i in $(seq -w 1 10); do
  echo "C0${i}:{\"order_id\":\"O-2${i}\",\"customer_id\":\"C0${i}\",\"seq\":2}"
done | Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s03_keyed \
    --property parse.key=true --property key.separator=:

sleep 2

echo "--- C001 과 C003 의 두 메시지가 서로 다른 파티션에 있습니다 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s03_keyed \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true --property print.offset=true \
  2>/dev/null | grep -E 'C001|C003' | sort

echo "--- 확장 후 전체 분포 (파티션 5 가 새로 등장합니다) ---"
PARTS s03_keyed

echo
echo ">>> 여기가 이 스텝의 핵심입니다."
echo ">>> C001, C003, C004, C008 이 파티션 2 에서 5 로 이동했습니다."
echo ">>> 에러도 경고도 없었지만, 이 키들의 순서 보장은 그 시점부터 사라졌습니다."

# -----------------------------------------------------------------------------
# [3-6] 토픽 삭제의 실제 동작
# -----------------------------------------------------------------------------
hr "[3-6] 삭제 전 디렉터리 확인"

K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type brokers --entity-name 1 --all | grep 'delete.topic.enable'

docker exec kafka-1 ls -d /var/lib/kafka/data/s03_demo-* | head -3

hr "[3-6] 삭제 — 직후에 바로 확인해야 -delete 디렉터리가 보입니다"

K kafka-topics.sh --bootstrap-server "$BS" --delete --topic s03_demo
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_demo-* 2>&1 || true

echo
echo "--- file.delete.delay.ms(기본 60초) 를 기다립니다. 65초 멈춥니다 ---"
sleep 65

echo "--- 이제 사라졌어야 합니다 ---"
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_demo-* 2>&1 || true

# -----------------------------------------------------------------------------
# [3-7] 토픽 이름 규칙
# -----------------------------------------------------------------------------
hr "[3-7] 허용되지 않는 문자 (일부러 실패)"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic 'orders!' --partitions 1 --replication-factor 3 || true

hr "[3-7] . 과 _ 의 충돌"

echo "--- 먼저 점 버전을 만듭니다 (성공) ---"
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03.metrics --partitions 1 --replication-factor 3 || true

echo "--- 이제 밑줄 버전을 만듭니다 (충돌로 실패) ---"
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03_metrics --partitions 1 --replication-factor 3 || true

echo
echo ">>> 둘 다 유효한 이름인데 함께 존재할 수 없습니다."
echo ">>> JMX 메트릭 이름에서 . 이 _ 로 치환되면 두 토픽이 구분되지 않기 때문입니다."

# -----------------------------------------------------------------------------
# [3-8] 정리 — s03_ 토픽 전부 삭제
# -----------------------------------------------------------------------------
hr "[3-8] 정리"

echo "--- 지울 대상 ---"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep -E '^s03[._]' || true

K kafka-topics.sh --bootstrap-server "$BS" --list | grep -E '^s03[._]' | while read -r t; do
  echo "deleting $t"
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$BS" --delete --topic "$t" || true
done

echo "--- 공용 토픽 4개만 남아야 합니다 ---"
K kafka-topics.sh --bootstrap-server "$BS" --list

echo "--- 공용 토픽 설정이 변경되지 않았는지 확인 ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topics-with-overrides

echo
echo "--- orders 가 여전히 PartitionCount: 3 인지 반드시 확인하세요 ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic orders | head -1

echo
echo "Step 03 완료."
echo "orders 가 3파티션이 아니라면 docker compose down -v 로 초기화하고 Step 01 부터 다시 하세요."
