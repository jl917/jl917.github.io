#!/usr/bin/env bash
# =============================================================================
# Step 03 — 연습문제 정답과 해설 (7문제)
#
# 실행법:
#   bash step-03-topics-partitions/solution.sh
#   bash -x step-03-topics-partitions/solution.sh    # 권장 (문제 6 에서 65초 멈춥니다)
#
# 각 정답 아래의 "# 해설:" 블록이 "왜 그 답인가"를 설명합니다.
# exercise.sh 를 직접 풀어 본 뒤에 여세요.
# =============================================================================
set -euo pipefail

BS=kafka-1:9092
K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

hr() { echo; echo "=== $* ==================================================="; }

PARTS() {
  K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$1" \
    --from-beginning --timeout-ms 5000 --property print.partition=true 2>/dev/null \
    | grep -oE '^Partition:[0-9]+' | sort | uniq -c
}

# =============================================================================
# 정답 1. s03_ex1 생성
# =============================================================================
hr "정답 1 — s03_ex1 생성"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03_ex1 \
  --partitions 2 --replication-factor 3 \
  --config retention.ms=600000 || true

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s03_ex1

# 해설:
#   10분 = 10 * 60 * 1000 = 600000 밀리초입니다.
#   자주 쓰는 값:
#       10분    =      600000
#       1시간   =     3600000
#       1일     =    86400000
#       7일     =   604800000
#       30일    =  2592000000
#       무제한  =          -1
#
#   A. --describe 의 Configs 에 min.insync.replicas 가 안 보이는 것이 정상입니다.
#      적용이 안 된 것이 아닙니다. Configs 는 "토픽 레벨로 명시된 오버라이드"만
#      보여 주기 때문입니다. min.insync.replicas=2 는 브로커 설정
#      (STATIC_BROKER_CONFIG)에서 오므로 여기 안 나옵니다.
#
#      실제로 적용 중인 전체 값을 보려면 --all 을 붙여야 합니다:
#          kafka-configs.sh --describe --entity-type topics \
#            --entity-name s03_ex1 --all
#
#      이 구분이 실무에서 중요합니다. "Configs 에 없으니 설정 안 된 줄 알았다"가
#      흔한 오해이고, 그래서 --all 과 synonyms 를 읽을 줄 알아야 합니다.

# =============================================================================
# 정답 2. --delete-config 전후의 synonyms
# =============================================================================
hr "정답 2 — synonyms 비교"

echo "--- (a) retention.ms=1시간 ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --alter --entity-type topics --entity-name s03_ex1 \
  --add-config retention.ms=3600000

echo "--- (b) 변경 후 synonyms ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type topics --entity-name s03_ex1 --all \
  | grep -E '^\s+retention\.ms'

echo "--- (c) 오버라이드 제거 ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --alter --entity-type topics --entity-name s03_ex1 \
  --delete-config retention.ms

echo "--- (d) 제거 후 synonyms ---"
K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type topics --entity-name s03_ex1 --all \
  | grep -E '^\s+retention\.ms'

# 해설:
#   (b) 의 출력:
#       retention.ms=3600000 sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:retention.ms=3600000, DEFAULT_CONFIG:log.retention.ms=604800000}
#   (d) 의 출력:
#       retention.ms=604800000 sensitive=false synonyms={DEFAULT_CONFIG:log.retention.ms=604800000}
#
#   DYNAMIC_TOPIC_CONFIG 항목이 통째로 사라졌습니다.
#
#   synonyms 는 "이 값이 어디서 왔는가"를 우선순위 순으로 나열한 목록이며,
#   맨 앞이 이긴 값입니다. 우선순위는 이렇습니다:
#       DYNAMIC_TOPIC_CONFIG          (이 토픽에 kafka-configs.sh 로 설정)
#     > DYNAMIC_BROKER_CONFIG         (이 브로커 하나에 동적 설정)
#     > DYNAMIC_DEFAULT_BROKER_CONFIG (모든 브로커에 동적 설정)
#     > STATIC_BROKER_CONFIG          (server.properties / 환경변수)
#     > DEFAULT_CONFIG                (Kafka 내장 기본값)
#
#   A. (c) 이후 이 토픽은 "7일로 고정"이 아니라 "브로커 기본값을 따름" 상태입니다.
#
#      차이가 왜 중요한가:
#        지금은 브로커 기본이 7일이라 결과가 같습니다. 그런데 나중에 누군가
#        브로커 기본값을 30일로 바꾸면, 이 토픽도 말없이 30일이 됩니다.
#        반대로 --add-config retention.ms=604800000 로 7일을 명시해 뒀다면
#        브로커 기본이 바뀌어도 7일을 유지합니다.
#
#        어느 쪽이 옳은지는 의도에 달렸습니다.
#          "우리 조직 표준을 따른다"  → 지우는 게 맞습니다
#          "이 토픽은 반드시 7일"      → 명시하는 게 맞습니다
#        문제는 대부분의 팀이 이 차이를 의식하지 않고 지운다는 것이고,
#        몇 달 뒤 브로커 기본값 변경이 예상 못 한 토픽들을 함께 바꿔 놓습니다.
#
#      확인법: --all 출력의 synonyms 에 DYNAMIC_TOPIC_CONFIG 가 있는지 보면 됩니다.
#              없으면 남의 기본값에 의존하고 있는 상태입니다.

# =============================================================================
# 정답 3. 파티션 증가와 감소
# =============================================================================
hr "정답 3 — 파티션 증가와 감소"

echo "--- (a) 2 → 4 (성공, 출력 없음) ---"
K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_ex1 --partitions 4

echo "--- (b) 4 → 3 (실패) ---"
K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_ex1 --partitions 3 || true

# 해설:
#   에러 메시지:
#       Error while executing topic command : Topic currently has 4 partitions,
#         which is higher than the requested 3.
#   예외 클래스:
#       org.apache.kafka.common.errors.InvalidPartitionsException
#
#   같은 수(4)를 요청해도 거절됩니다:
#       Topic already has 4 partition(s).
#
#   왜 줄일 수 없는가 — 세 선택지 모두 Kafka 의 근본 계약을 깨기 때문입니다.
#     (1) 파티션 안의 데이터를 버린다      → 데이터 유실. 메시징 시스템으로서 불가
#     (2) 다른 파티션으로 옮긴다            → 옮긴 메시지의 오프셋이 달라짐.
#                                            컨슈머가 커밋한 오프셋이 전부 무의미해지고
#                                            순서도 뒤섞임
#     (3) 옮기면서 순서를 다시 맞춘다       → 대상 파티션의 기존 오프셋을 전부 재부여.
#                                            사실상 토픽 재작성
#   Kafka 의 계약은 "오프셋은 불변" 과 "파티션 안에서 순서 보장" 입니다.
#   셋 다 이것을 깨므로 아예 막아 두었습니다.
#
#   정말 줄여야 한다면 마이그레이션밖에 없습니다:
#     1) 목표 파티션 수로 새 토픽(orders_v2)을 만든다
#     2) MirrorMaker 2 / Kafka Connect / 간단한 컨슈머-프로듀서 앱으로 데이터를 옮긴다
#     3) 프로듀서를 새 토픽으로 전환한다
#     4) 컨슈머가 옛 토픽을 다 소진하면 옛 토픽을 삭제한다
#   단, 이 과정에서 키별 순서는 어차피 깨집니다. 파티션 수가 달라지면
#   같은 키가 다른 파티션으로 가기 때문입니다 (정답 4).

# =============================================================================
# 정답 4. O-1001~O-1010 을 2 → 4 파티션에서
# =============================================================================
hr "정답 4 — 파티션 확장이 키 배치를 바꾼다"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03_ex2 --partitions 2 --replication-factor 3 || true

echo "--- (b) seq=1 전송 ---"
for i in $(seq 1001 1010); do
  echo "O-${i}:{\"order_id\":\"O-${i}\",\"seq\":1}"
done | Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s03_ex2 \
    --property parse.key=true --property key.separator=:

echo "--- (c) 2파티션 분포 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s03_ex2 \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true 2>/dev/null | sort
PARTS s03_ex2

echo "--- (d) 파티션 4개로 확장 ---"
K kafka-topics.sh --bootstrap-server "$BS" --alter --topic s03_ex2 --partitions 4
sleep 2

echo "--- (e) seq=2 전송 ---"
for i in $(seq 1001 1010); do
  echo "O-${i}:{\"order_id\":\"O-${i}\",\"seq\":2}"
done | Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s03_ex2 \
    --property parse.key=true --property key.separator=:
sleep 2

echo "--- (f) 4파티션 분포 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s03_ex2 \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true 2>/dev/null | sort
PARTS s03_ex2

# 해설:
#   파티셔너의 계산식:
#       partition = (murmur2(keyBytes) & 0x7fffffff) % numPartitions
#
#   O-1001 ~ O-1010 의 실제 계산 결과:
#
#     키        murmur2         & 0x7fffffff    % 2   % 4   이동
#     ------------------------------------------------------------
#     O-1001    -1783020840      364462808      0     0     유지
#     O-1002    -1677475889      470007759      1     3    ➜ 이동
#     O-1003      878812229      878812229      1     1     유지
#     O-1004    -1306355089      841128559      1     3    ➜ 이동
#     O-1005    -1475015087      672468561      1     1     유지
#     O-1006      540329714      540329714      0     2    ➜ 이동
#     O-1007     1886656773     1886656773      1     1     유지
#     O-1008    -1530422227      617061421      1     1     유지
#     O-1009     1228478338     1228478338      0     2    ➜ 이동
#     O-1010     -95682849      2051800799      1     3    ➜ 이동
#
#   이동한 키의 개수: 5개
#   이동한 키 목록: O-1002, O-1004, O-1010 (파티션 1 → 3)
#                   O-1006, O-1009       (파티션 0 → 2)
#
#   2파티션 분포: p0 = 3건(O-1001, O-1006, O-1009), p1 = 7건
#   4파티션 분포: p0 = O-1001
#                 p1 = O-1003, O-1005, O-1007, O-1008
#                 p2 = O-1006, O-1009
#                 p3 = O-1002, O-1004, O-1010
#
#   왜 이런 패턴인가 — 산수입니다.
#     4 = 2 x 2 이므로 h % 4 는 항상 h % 2 이거나 h % 2 + 2 입니다.
#     즉 키는 "제자리에 남거나 정확히 +2 만큼 밀립니다."
#     본문 3-5 의 3 → 6 확장에서 키가 제자리이거나 +3 만큼 밀렸던 것과 같은 원리입니다.
#     (6 = 2 x 3 이므로 h % 6 은 h % 3 이거나 h % 3 + 3)
#
#     이 성질 때문에 "파티션 수를 배수로 늘리면 안전하다"는 오해가 생기는데,
#     틀렸습니다. 배수로 늘려도 절반 정도의 키는 여전히 이동합니다.
#     이동하는 키가 없으려면 파티션 수가 안 바뀌어야 합니다.

# =============================================================================
# 정답 5. 이동한 키의 두 메시지가 다른 파티션에
# =============================================================================
hr "정답 5 — O-1002 의 두 메시지"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s03_ex2 \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true --property print.offset=true \
  2>/dev/null | grep 'O-1002' | sort

# 해설:
#   기대 출력:
#       Partition:1	Offset:0	O-1002	{"order_id":"O-1002","seq":1}
#       Partition:3	Offset:0	O-1002	{"order_id":"O-1002","seq":2}
#
#   같은 키 O-1002 의 두 이벤트가 파티션 1과 3에 나뉘어 저장되었습니다.
#   전송할 때 에러는 하나도 없었습니다.
#
#   A. 네, seq=2 를 seq=1 보다 먼저 처리할 수 있습니다.
#
#      Kafka 는 파티션 안에서만 순서를 보장합니다. 파티션 1과 3 사이에는
#      아무런 순서 관계가 없습니다. 컨슈머 그룹에서 이 두 파티션은
#        - 서로 다른 컨슈머 인스턴스에 할당될 수 있고 (완전히 병렬 처리)
#        - 같은 인스턴스라도 poll() 이 반환하는 순서에 따라
#          파티션 3 의 레코드를 먼저 처리할 수 있습니다
#
#      주문 상태 머신에 만드는 사고:
#        상태가 CREATED → PAID → SHIPPED 로 흘러야 하는데, 확장 직후 어떤 주문은
#        SHIPPED 가 PAID 보다 먼저 도착합니다. 그러면
#          (a) 상태 머신이 "SHIPPED 에서 PAID 로 갈 수 없다"며 이벤트를 버리거나
#          (b) 최종 상태가 PAID 로 굳어, 실제로 배송된 주문이 미배송으로 남습니다
#        어느 쪽이든 며칠 뒤 CS 문의로 발견됩니다.
#
#      더 지독한 점:
#        이 문제는 파티션을 늘린 순간에만 생기는 것이 아닙니다.
#        옛 데이터와 새 데이터가 다른 파티션에 있는 상태가 보존 기간 내내
#        지속됩니다. orders 라면 7일간 이 상태이고, 그 사이 컨슈머를 처음부터
#        재처리하면 순서가 뒤섞인 채로 읽습니다.
#
#      해결책 (본문 3-5 참조):
#        1. 파티션 수를 처음부터 넉넉히 잡고 절대 바꾸지 않는다  ← 가장 확실
#        2. 새 토픽을 만들어 마이그레이션한다
#        3. numPartitions 에 의존하지 않는 커스텀 파티셔너를 쓴다
#
#      근본적으로는 이벤트를 순서 의존적으로 설계하지 않는 것이 낫습니다.
#      각 이벤트에 버전 번호나 타임스탬프를 넣고 컨슈머가 "더 오래된 이벤트면
#      무시" 하도록 만들면, 순서가 뒤바뀌어도 최종 상태가 맞습니다 (멱등 처리).

# =============================================================================
# 정답 6. 토픽 삭제는 비동기
# =============================================================================
hr "정답 6 — 삭제 직후의 -delete 디렉터리"

echo "--- 삭제 전 ---"
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_ex2-* 2>&1 || true

echo "--- 삭제하고 즉시 확인 ---"
K kafka-topics.sh --bootstrap-server "$BS" --delete --topic s03_ex2
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_ex2-* 2>&1 || true

echo "--- 65초 대기 ---"
sleep 65
docker exec kafka-1 ls -d /var/lib/kafka/data/s03_ex2-* 2>&1 || true

# 해설:
#   삭제 직후:
#       /var/lib/kafka/data/s03_ex2-0.8f3a2c91d47b4e6ca1057b3e2f9d6a10-delete
#       /var/lib/kafka/data/s03_ex2-1.2b7e4f18c93a4d5fb8e10c2a7d4f9e33-delete
#       /var/lib/kafka/data/s03_ex2-2.5c9d1a72e86f4b3ea2d70f4c8b1e5a27-delete
#       /var/lib/kafka/data/s03_ex2-3.7a2f5c30b91d4e8fa5c31d90e7b2f4a66-delete
#   65초 뒤:
#       ls: cannot access '/var/lib/kafka/data/s03_ex2-*': No such file or directory
#
#   삭제는 두 단계입니다:
#       (1) 브로커가 디렉터리를 "원래이름.랜덤UUID-delete" 로 rename    ← 즉시
#            ↓ file.delete.delay.ms (기본 60000 = 60초) 대기
#       (2) 백그라운드 스레드가 실제로 파일을 삭제                      ← 나중
#
#   왜 두 단계인가:
#     rename 은 원자적이고 즉시 끝나지만, 삭제는 오래 걸립니다.
#     세그먼트가 수천 개인 파티션을 지우는 데 수십 초가 걸릴 수 있고,
#     그동안 브로커의 요청 처리 스레드를 붙잡고 있으면 클러스터 전체가 느려집니다.
#     그래서 이름만 바꿔 "이건 이제 없는 것"으로 만들고 실제 삭제는 미룹니다.
#
#   왜 UUID 가 붙는가:
#     같은 이름의 토픽을 즉시 다시 만들 수 있게 하기 위해서입니다.
#     UUID 가 없다면 s03_ex2-0-delete 가 이미 있는 상태에서 또 지울 때 충돌합니다.
#
#   관련 함정 (본문 3-6):
#     토픽을 지우고 바로 같은 이름으로 만들면 TopicId 가 달라진 새 토픽입니다.
#     옛 메타데이터를 캐시한 클라이언트는 잠깐 UnknownTopicIdException 을 받고,
#     더 나쁘게는 컨슈머 그룹의 커밋된 오프셋이 그대로 남아 있어
#     새 빈 토픽에서 아무것도 안 읽거나 OFFSET_OUT_OF_RANGE 로 리셋됩니다.
#     재생성할 때는 관련 컨슈머 그룹도 함께 지우세요:
#         kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 --delete --group X
#     가능하면 다른 이름(_v2)을 쓰는 편이 훨씬 낫습니다.

# =============================================================================
# 정답 7. . 과 _ 의 충돌
# =============================================================================
hr "정답 7 — 이름 충돌"

echo "--- (a) 점 버전 (성공) ---"
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03.audit --partitions 1 --replication-factor 3 || true

echo "--- (b) 밑줄 버전 (실패) ---"
K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s03_audit --partitions 1 --replication-factor 3 || true

# 해설:
#   에러:
#       Error while executing topic command : Topic 's03_audit' collides with
#         existing topics: s03.audit
#       org.apache.kafka.common.errors.InvalidTopicException: Topic 's03_audit'
#         collides with existing topics: s03.audit
#
#   A. JMX 메트릭 이름에서 '.' 이 '_' 로 치환되면 두 토픽의 메트릭이 같은 이름으로
#      뭉개져 구분할 수 없기 때문입니다.
#
#   자세히:
#     Kafka 는 토픽별 메트릭을 이런 이름으로 노출합니다.
#         kafka.log:type=Log,name=Size,topic=s03.audit,partition=0
#     그런데 여러 모니터링 시스템(Graphite, 일부 JMX 익스포터)이 메트릭 경로에서
#     '.' 을 계층 구분자로 쓰거나 '.' 을 '_' 로 치환합니다.
#     그러면 s03.audit 과 s03_audit 의 처리량 그래프가 하나로 섞여 버립니다.
#     Kafka 는 이 사고를 아예 막기 위해, '.' 을 '_' 로 치환했을 때 같아지는
#     이름을 금지합니다.
#
#   토픽 이름 규칙 정리:
#     - 허용 문자: 영숫자, '.', '_', '-' 만
#     - 최대 249자
#     - '.' 과 '..' 는 금지
#     - 대소문자 구분 (Orders 와 orders 는 다른 토픽)
#     - '__' 로 시작하는 것은 내부 토픽 관례 (__consumer_offsets)
#
#   권장 컨벤션:
#       <도메인>.<데이터종류>.<엔티티>.<버전>
#       예: shop.event.order-created.v1
#           shop.entity.customer.v1
#           shop.dlq.order-processor.v1
#     - 도메인/팀 접두사는 필수입니다. 토픽이 수백 개가 되면 'orders' 라는 이름을
#       두 팀이 원합니다. ACL 도 접두사 단위(shop.*)로 거는 것이 편합니다.
#     - 버전 접미사는 나중에 큰 도움이 됩니다. 스키마 호환이 깨지거나(Step 10)
#       파티션 수를 바꿔야 할 때(정답 3), .v2 로 새 토픽을 만들어 옮길 수 있습니다.
#     - '_' 를 쓰지 않는 것이 위 충돌을 피하는 실용적인 규칙입니다.
#     - 이름은 한 번 정하면 못 바꿉니다. Kafka 에는 토픽 rename 이 없습니다.

# =============================================================================
# 뒷정리
# =============================================================================
hr "뒷정리"

K kafka-topics.sh --bootstrap-server "$BS" --list | grep -E '^s03[._]' | while read -r t; do
  echo "deleting $t"
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$BS" --delete --topic "$t" || true
done

echo "--- 공용 토픽 4개만 남아야 합니다 ---"
K kafka-topics.sh --bootstrap-server "$BS" --list

echo "--- 공용 토픽 설정이 변경되지 않았는지 ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topics-with-overrides

echo
echo "--- orders 가 여전히 PartitionCount: 3 인지 반드시 확인하세요 ---"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic orders | head -1
echo "3이 아니라면 docker compose down -v 로 초기화하고 Step 01 부터 다시 하세요."
