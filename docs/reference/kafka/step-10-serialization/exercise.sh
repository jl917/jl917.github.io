#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Step 10 — 직렬화와 스키마 : 연습문제 (6문제)
#
# 실행법:
#   bash step-10-serialization/exercise.sh
#   (권장) 편집기로 열어 `# 여기에 작성:` 자리를 채운 뒤 한 블록씩 실행
#
# 전제: docker compose --profile registry up -d 로 Schema Registry 가 떠 있을 것
# 정답: solution.sh
# ---------------------------------------------------------------------------
set -uo pipefail   # 문제 풀이 중 실패해도 계속 진행하도록 -e 는 뺐습니다

BS=kafka-1:9092
SR_URL=http://localhost:8081

K()   { docker exec kafka-1 /opt/kafka/bin/"$@"; }
SRX() { docker exec schema-registry "$@"; }

json_escape() {
  if command -v jq >/dev/null 2>&1; then jq -Rs .
  else python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; fi
}

# ===========================================================================
# 준비 — 문제지가 미리 만들어 두는 것들
# ===========================================================================
echo "### 준비: s10_ex 토픽 생성 + 문자열 3건 투입"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s10_ex --partitions 1 --replication-factor 3 2>/dev/null || true
printf 'O-2001\nO-2002\nO-2003\n' | docker exec -i kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --topic s10_ex


# ===========================================================================
# 문제 1.
#   s10_ex 에는 String 으로 쓴 'O-2001' 같은 값이 들어 있습니다.
#   이것을 IntegerDeserializer 로 읽는 콘솔 컨슈머 명령을 작성하고,
#   출력되는 예외 메시지를 정확히 옮겨 적으십시오.
#   그룹 이름은 s10-ex 를 쓰십시오.
#
#   힌트: --value-deserializer 옵션. 클래스 풀네임은
#         org.apache.kafka.common.serialization.IntegerDeserializer
# ===========================================================================
echo "### 문제 1"
# 여기에 작성:


# 관찰한 예외 메시지 (주석으로 적으십시오):
#


# ===========================================================================
# 문제 2.
#   문제 1 의 컨슈머 그룹 s10-ex 의 랙을 확인하십시오.
#   CURRENT-OFFSET 이 진전하지 않는 것을 확인한 뒤,
#   오프셋을 1 칸 건너뛰어 컨슈머가 다시 돌 수 있게 복구하십시오.
#
#   ⚠️ --reset-offsets 는 그룹에 **활성 멤버가 있으면 거부**됩니다.
#      Error: Assignments can only be reset if the group 's10-ex' is inactive
#      문제 1 의 컨슈머를 반드시 먼저 종료하십시오.
#   힌트: --reset-offsets --topic s10_ex:0 --shift-by N --dry-run / --execute
# ===========================================================================
echo "### 문제 2"
# (a) 랙 확인
# 여기에 작성:


# (b) dry-run 으로 어떻게 바뀔지 먼저 확인
# 여기에 작성:


# (c) 실제 적용
# 여기에 작성:


# ===========================================================================
# 문제 3.
#   아래 heredoc 의 Payment 스키마를 payments-value subject 에 등록하고,
#   응답으로 받은 id 를 변수에 담아 /schemas/ids/{id} 로 다시 조회하십시오.
#
#   힌트: json_escape 로 스키마 본문을 문자열화한 뒤
#         {"schema": <escaped>} 로 감싸 POST 합니다.
#         id 추출은 jq -r .id 또는 grep -o '[0-9]*'
# ===========================================================================
echo "### 문제 3"
PAYMENT_SCHEMA=$(cat <<'AVRO' | tr -d '\n'
{
  "type": "record", "name": "Payment", "namespace": "shop.payment",
  "fields": [
    {"name": "order_id", "type": "string"},
    {"name": "method",   "type": "string"},
    {"name": "amount",   "type": "int"},
    {"name": "result",   "type": "string"}
  ]
}
AVRO
)
ESCAPED=$(printf '%s' "$PAYMENT_SCHEMA" | json_escape)

# (a) 등록 — {"id":N} 이 나와야 합니다
# 여기에 작성:


# (b) 발급된 id 를 변수 ID 에 담기
# 여기에 작성:
# ID=

# (c) /schemas/ids/$ID 로 재조회
# 여기에 작성:


# ===========================================================================
# 문제 4.
#   payments-value 의 호환성을 BACKWARD 로 설정한 뒤,
#   "기본값 없는 refund_id 필드 추가" 를 호환성 검사(등록 아님)에 넣으십시오.
#   is_compatible 이 어떻게 나옵니까?
#
#   힌트: PUT /config/payments-value, POST /compatibility/subjects/.../versions/latest
# ===========================================================================
echo "### 문제 4"
V2_SCHEMA=$(cat <<'AVRO' | tr -d '\n'
{
  "type": "record", "name": "Payment", "namespace": "shop.payment",
  "fields": [
    {"name": "order_id",  "type": "string"},
    {"name": "method",    "type": "string"},
    {"name": "amount",    "type": "int"},
    {"name": "result",    "type": "string"},
    {"name": "refund_id", "type": "string"}
  ]
}
AVRO
)
V2_ESCAPED=$(printf '%s' "$V2_SCHEMA" | json_escape)

# (a) 호환성을 BACKWARD 로
# 여기에 작성:


# (b) 호환성 검사
# 여기에 작성:


# 관찰한 결과 (주석):
#   is_compatible =
#   errorType     =


# ===========================================================================
# 문제 5.
#   호환성을 FORWARD 로 바꾸고 문제 4 와 **완전히 같은** 검사를 다시 하십시오.
#   판정이 뒤집힙니까? 왜 그렇습니까?
# ===========================================================================
echo "### 문제 5"
# (a) 호환성을 FORWARD 로
# 여기에 작성:


# (b) 문제 4 와 같은 검사
# 여기에 작성:


# 왜 뒤집혔는지 한 줄로 (주석):
#


# ===========================================================================
# 문제 6.
#   아래에서 문제지가 Avro 로 1건을 s10_ex_avro 토픽에 씁니다.
#   그 로그 세그먼트를 xxd 로 열어 앞 5바이트(매직 + 스키마 ID)를 읽어내고,
#   읽어낸 ID 로 Registry 를 조회하십시오.
#
#   힌트: 앞 1바이트가 0x00(매직), 다음 4바이트가 빅엔디언 스키마 ID 입니다.
#         세그먼트 경로는 /var/lib/kafka/data/s10_ex_avro-0/00000000000000000000.log
#         tail -c 60 으로 끝부분만 잘라 보는 편이 찾기 쉽습니다.
# ===========================================================================
echo "### 문제 6"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s10_ex_avro --partitions 1 --replication-factor 3 2>/dev/null || true
SRX kafka-avro-console-producer \
  --bootstrap-server $BS --topic s10_ex_avro \
  --property schema.registry.url=http://schema-registry:8081 \
  --property value.schema='{"type":"record","name":"Payment","namespace":"shop.payment","fields":[{"name":"order_id","type":"string"},{"name":"method","type":"string"},{"name":"amount","type":"int"},{"name":"result","type":"string"}]}' <<'EOF'
{"order_id":"O-2001","method":"CARD","amount":39000,"result":"APPROVED"}
EOF

# (a) xxd 로 바이트 보기
# 여기에 작성:


# (b) 읽어낸 스키마 ID 로 Registry 조회
# 여기에 작성:


# 읽어낸 ID (주석):
#


# ===========================================================================
# 정리 — 문제를 다 못 풀었어도 이 블록만 실행하면 환경이 깨끗해집니다
# ===========================================================================
echo "### 정리"
curl -s -X DELETE "$SR_URL/subjects/payments-value"                 >/dev/null 2>&1
curl -s -X DELETE "$SR_URL/subjects/payments-value?permanent=true"  >/dev/null 2>&1
curl -s -X DELETE "$SR_URL/subjects/s10_ex_avro-value"                >/dev/null 2>&1
curl -s -X DELETE "$SR_URL/subjects/s10_ex_avro-value?permanent=true" >/dev/null 2>&1
K kafka-topics.sh --bootstrap-server $BS --delete --topic s10_ex       2>/dev/null || true
K kafka-topics.sh --bootstrap-server $BS --delete --topic s10_ex_avro  2>/dev/null || true
K kafka-consumer-groups.sh --bootstrap-server $BS --delete --group s10-ex 2>/dev/null || true
echo "남은 subject:"; curl -s "$SR_URL/subjects"; echo
