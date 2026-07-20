#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Step 10 — 직렬화와 스키마 : 본문 실습 스크립트
#
# 실행법:
#   bash step-10-serialization/practice.sh          # 통째로
#   bash -x step-10-serialization/practice.sh       # 한 줄씩 확인하며 (권장)
#
# 전제:
#   - kafka/docker 에서 `docker compose --profile registry up -d` 가 되어 있어야 합니다.
#     (스크립트 [10-0] 이 직접 띄우고 준비될 때까지 기다립니다)
#   - jq 가 있으면 씁니다. 없으면 python3 폴백을 씁니다.
# ---------------------------------------------------------------------------
set -euo pipefail

BS=kafka-1:9092
SR_URL=http://localhost:8081

# 컨테이너 안 CLI 호출 헬퍼 : K kafka-topics.sh --list ...
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
# Schema Registry 안에 들어 있는 avro 콘솔 도구 헬퍼
SRX() { docker exec schema-registry "$@"; }

# JSON 문자열 이스케이프 (Avro 스키마를 {"schema":"..."} 안에 넣기 위해)
json_escape() {
  if command -v jq >/dev/null 2>&1; then
    jq -Rs .
  else
    python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'
  fi
}

# heredoc 으로 받은 Avro 스키마를 subject 에 등록
#   register_schema orders-value <<'EOF' ... EOF
register_schema() {
  local subject="$1"
  local body
  body=$(cat | tr -d '\n' | json_escape)
  curl -s -X POST "$SR_URL/subjects/$subject/versions" \
    -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
    -d "{\"schema\": $body}"
  echo
}

# 등록하지 않고 호환성만 검사
check_compat() {
  local subject="$1"
  local body
  body=$(cat | tr -d '\n' | json_escape)
  curl -s -X POST "$SR_URL/compatibility/subjects/$subject/versions/latest" \
    -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
    -d "{\"schema\": $body}"
  echo
}

set_compat() {
  curl -s -X PUT "$SR_URL/config/$1" \
    -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
    -d "{\"compatibility\":\"$2\"}"
  echo
}

# ===========================================================================
# [10-0] 실습 준비 — Schema Registry 기동과 헬스체크
# ===========================================================================
echo "### [10-0] Schema Registry 기동"
( cd "$(dirname "$0")/../docker" && docker compose --profile registry up -d )

echo "### [10-0] /subjects 가 응답할 때까지 최대 60초 대기"
for i in $(seq 1 60); do
  if curl -sf "$SR_URL/subjects" >/dev/null 2>&1; then
    echo "  ready after ${i}s"
    break
  fi
  sleep 1
done
curl -s "$SR_URL/subjects"; echo

# Registry 가 스키마를 저장하는 내부 토픽. cleanup.policy=compact 임을 확인합니다.
echo "### [10-0] _schemas 토픽 — 파티션 1개, compact"
K kafka-topics.sh --bootstrap-server $BS --describe --topic _schemas

# ===========================================================================
# [10-2] 함정 A — Deserializer 불일치 (poison pill)
# ===========================================================================
echo "### [10-2] 실습 토픽 생성 후 String 으로 3건 투입"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s10_types --partitions 1 --replication-factor 3 || true

printf 'O-1001\nO-1002\nO-1003\n' | docker exec -i kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --topic s10_types

# ↓ 여기는 "일부러 실패하는" 구간입니다. set -e 로 죽지 않도록 || true 를 붙입니다.
#   기대 출력:
#   org.apache.kafka.common.errors.SerializationException:
#     Size of data received by LongDeserializer is not 8
echo "### [10-2] LongDeserializer 로 읽기 — 실패가 정상입니다"
timeout 20 docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server $BS --topic s10_types --from-beginning \
  --group s10-poison \
  --value-deserializer org.apache.kafka.common.serialization.LongDeserializer || true

# 오프셋이 0 에서 멈춰 있고 LAG 가 3 인 것을 확인합니다. 이것이 poison pill 입니다.
echo "### [10-2] 랙 확인 — CURRENT-OFFSET 0 / LAG 3"
K kafka-consumer-groups.sh --bootstrap-server $BS --describe --group s10-poison || true

# 응급 복구: 오프셋을 한 칸 건너뜁니다. (컨슈머가 떠 있으면 거부됩니다)
echo "### [10-2] --shift-by 1 로 poison 메시지 건너뛰기"
K kafka-consumer-groups.sh --bootstrap-server $BS \
  --group s10-poison --topic s10_types:0 --reset-offsets --shift-by 1 --execute || true

# ===========================================================================
# [10-3] JSON 크기 실측
# ===========================================================================
echo "### [10-3] JSON 한 건의 바이트 수"
printf '%s' '{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}' | wc -c

echo "### [10-3] 필드 7개짜리 완전한 레코드"
printf '%s' '{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED","created_at":"2024-03-11T10:22:00Z","channel":"WEB"}' | wc -c

# ===========================================================================
# [10-4] Schema Registry REST API 전체
# ===========================================================================
echo "### [10-4] orders-value 스키마 v1 등록 → {\"id\":1}"
register_schema orders-value <<'AVRO'
{
  "type": "record",
  "name": "Order",
  "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"},
    {"name": "status",      "type": "string"}
  ]
}
AVRO

echo "### [10-4] subject 목록"
curl -s "$SR_URL/subjects"; echo

echo "### [10-4] 버전 목록"
curl -s "$SR_URL/subjects/orders-value/versions"; echo

echo "### [10-4] 최신 버전 전체"
curl -s "$SR_URL/subjects/orders-value/versions/latest"; echo

echo "### [10-4] ID 로 조회 — 컨슈머가 실제로 쓰는 엔드포인트"
curl -s "$SR_URL/schemas/ids/1"; echo

echo "### [10-4] 호환성 검사만 (등록하지 않음) — default 있는 필드 추가 → true"
check_compat orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"},
    {"name": "status",      "type": "string"},
    {"name": "channel",     "type": "string", "default": "WEB"}
  ]
}
AVRO

echo "### [10-4] 전역 호환성 수준"
curl -s "$SR_URL/config"; echo

echo "### [10-4] subject 단위 호환성 변경 → FULL"
set_compat orders-value FULL
curl -s "$SR_URL/config/orders-value"; echo

echo "### [10-4] subject 단위 설정 해제 → 전역값으로 복귀"
curl -s -X DELETE "$SR_URL/config/orders-value"; echo

# ===========================================================================
# [10-5] 와이어 포맷 — 매직바이트 0x00 + 스키마 ID 4바이트
# ===========================================================================
echo "### [10-5] Avro 토픽 생성"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s10_avro --partitions 1 --replication-factor 3 || true

echo "### [10-5] kafka-avro-console-producer 로 1건 쓰기"
SRX kafka-avro-console-producer \
  --bootstrap-server $BS --topic s10_avro \
  --property schema.registry.url=http://schema-registry:8081 \
  --property value.schema='{"type":"record","name":"Order","namespace":"shop.order","fields":[{"name":"order_id","type":"string"},{"name":"customer_id","type":"string"},{"name":"amount","type":"int"},{"name":"status","type":"string"}]}' <<'EOF'
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF

echo "### [10-5] 로그 세그먼트 덤프 — valueSize 가 39(=34+5) 인지 확인"
K kafka-dump-log.sh \
  --files /var/lib/kafka/data/s10_avro-0/00000000000000000000.log --print-data-log

# 전체를 xxd 하면 배치 헤더까지 쏟아지므로 끝부분 60바이트만 봅니다.
echo "### [10-5] xxd 로 실제 바이트 — 00 | 00 00 00 NN | Avro 페이로드"
docker exec kafka-1 sh -c \
  'tail -c 60 /var/lib/kafka/data/s10_avro-0/00000000000000000000.log | xxd | tail -4'

echo "### [10-5] 일반 console-consumer 로 읽으면 깨져 보입니다 (도구가 틀린 것)"
# timeout 은 셸 함수에 못 걸리므로 여기서는 K() 를 쓰지 않고 전체 명령을 씁니다.
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server $BS --topic s10_avro --from-beginning --timeout-ms 5000 || true

echo "### [10-5] kafka-avro-console-consumer 로 읽으면 정상"
docker exec schema-registry kafka-avro-console-consumer \
  --bootstrap-server $BS --topic s10_avro --from-beginning \
  --property schema.registry.url=http://schema-registry:8081 \
  --timeout-ms 5000 || true

echo "### [10-5] 이 토픽의 subject 는 TopicNameStrategy 로 자동 생성됩니다"
curl -s "$SR_URL/subjects"; echo

# ===========================================================================
# [10-8] 함정 B — 스키마 변경이 컨슈머를 깨뜨린다
# ===========================================================================
echo "### [10-8] (1) BACKWARD 로 되돌리고, 기본값 없는 필드 추가 → 409 거부"
set_compat orders-value BACKWARD

register_schema orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"},
    {"name": "status",      "type": "string"},
    {"name": "coupon_id",   "type": "string"}
  ]
}
AVRO
# 기대: {"error_code":409,... errorType:'READER_FIELD_MISSING_DEFAULT_VALUE' ...}

echo "### [10-8] (1') 기본값을 붙이면 통과 — nullable 은 [\"null\",\"T\"] + default null"
register_schema orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"},
    {"name": "status",      "type": "string"},
    {"name": "coupon_id",   "type": ["null","string"], "default": null}
  ]
}
AVRO

echo "### [10-8] (2) 호환성을 NONE 으로 — 검사 장치를 스스로 꺼 봅니다"
set_compat orders-value NONE

echo "### [10-8] (2') 방금 거부됐던 그 변경을 그대로 다시 — 이번엔 성공합니다"
register_schema orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"},
    {"name": "status",      "type": "string"},
    {"name": "coupon_id",   "type": "string"}
  ]
}
AVRO
# 등록은 성공하지만, v1 스키마를 가진 옛 컨슈머는 런타임에 이렇게 죽습니다:
#   org.apache.avro.AvroTypeException: ... missing required field coupon_id
# 그리고 이 예외는 [10-2] 의 poison pill 과 완전히 같은 형태입니다.

echo "### [10-8] (2'') 호환성 즉시 복구 — NONE 을 켜 둔 채로 넘어가면 안 됩니다"
set_compat orders-value BACKWARD

echo "### [10-8] (3) FORWARD 에서는 기본값 없는 필드 추가가 허용됩니다"
set_compat orders-value FORWARD
check_compat orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"},
    {"name": "status",      "type": "string"},
    {"name": "coupon_id",   "type": "string"},
    {"name": "channel",     "type": "string"}
  ]
}
AVRO

echo "### [10-8] (3') 반대로 FORWARD 에서 기본값 없는 필드 '삭제'는 거부됩니다"
check_compat orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "int"}
  ]
}
AVRO

# ===========================================================================
# [10-9] 스키마 진화 규칙 — 타입 승격
# ===========================================================================
echo "### [10-9] BACKWARD 로 되돌리고 int → long 승격 검사 → true"
set_compat orders-value BACKWARD
check_compat orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",    "type": "string"},
    {"name": "customer_id", "type": "string"},
    {"name": "amount",      "type": "long"},
    {"name": "status",      "type": "string"}
  ]
}
AVRO

echo "### [10-9] 이름 변경은 aliases 로 — 그냥 바꾸면 삭제+추가로 해석됩니다"
check_compat orders-value <<'AVRO'
{
  "type": "record", "name": "Order", "namespace": "shop.order",
  "fields": [
    {"name": "order_id",     "type": "string"},
    {"name": "customer_ref", "type": "string", "aliases": ["customer_id"], "default": "UNKNOWN"},
    {"name": "amount",       "type": "int"},
    {"name": "status",       "type": "string"}
  ]
}
AVRO

# ===========================================================================
# [10-10] 정리 (실습 마무리)
# ===========================================================================
echo "### [10-10] subject 소프트 삭제 → 완전 삭제"
curl -s -X DELETE "$SR_URL/subjects/orders-value" || true; echo
curl -s -X DELETE "$SR_URL/subjects/orders-value?permanent=true" || true; echo
curl -s -X DELETE "$SR_URL/subjects/s10_avro-value" || true; echo
curl -s -X DELETE "$SR_URL/subjects/s10_avro-value?permanent=true" || true; echo
curl -s "$SR_URL/subjects"; echo    # [] 이어야 정상

echo "### [10-10] 실습 토픽 / 컨슈머 그룹 삭제"
K kafka-topics.sh --bootstrap-server $BS --delete --topic s10_types || true
K kafka-topics.sh --bootstrap-server $BS --delete --topic s10_avro  || true
K kafka-consumer-groups.sh --bootstrap-server $BS --delete --group s10-poison || true

echo "### [10-10] Schema Registry 는 Step 12·13 에서 쓰지 않으므로 내려도 됩니다"
echo "  ( cd kafka/docker && docker compose stop schema-registry )"

echo "=== Step 10 practice 완료 ==="
