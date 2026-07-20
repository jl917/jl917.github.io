#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Step 10 — 직렬화와 스키마 : 연습문제 정답 + 해설
#
# 실행법:
#   bash step-10-serialization/solution.sh
#
# exercise.sh 를 직접 풀어 본 뒤에 여십시오.
# ---------------------------------------------------------------------------
set -uo pipefail

BS=kafka-1:9092
SR_URL=http://localhost:8081

K()   { docker exec kafka-1 /opt/kafka/bin/"$@"; }
SRX() { docker exec schema-registry "$@"; }

json_escape() {
  if command -v jq >/dev/null 2>&1; then jq -Rs .
  else python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; fi
}

echo "### 준비"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s10_ex --partitions 1 --replication-factor 3 2>/dev/null || true
printf 'O-2001\nO-2002\nO-2003\n' | docker exec -i kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --topic s10_ex


# ===========================================================================
# 정답 1 — IntegerDeserializer 로 읽기
# ===========================================================================
echo "### 정답 1"
timeout 20 docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server $BS --topic s10_ex --from-beginning \
  --group s10-ex \
  --value-deserializer org.apache.kafka.common.serialization.IntegerDeserializer || true

# 해설:
#   출력:
#     org.apache.kafka.common.errors.SerializationException:
#       Size of data received by IntegerDeserializer is not 4
#     Processed a total of 0 messages
#
#   'O-2001' 은 UTF-8 로 6바이트입니다. IntegerDeserializer 는 **정확히 4바이트**를
#   요구하는 고정 길이 디코더이므로, 길이가 다르면 즉시 예외를 던집니다.
#   Long 이면 8, Integer 면 4, UUID 면 36(문자열 표현) 입니다.
#
#   ⚠️ 여기서 진짜 무서운 것은 "길이가 우연히 맞는 경우" 입니다.
#      값이 'ABCD'(4바이트) 였다면 IntegerDeserializer 는 **아무 에러도 내지 않고**
#      0x41424344 = 1094861636 이라는 정수를 돌려줍니다.
#      길이가 안 맞으면 시끄럽게 실패하지만, 맞으면 조용히 쓰레기를 돌려줍니다.
#      이 코스의 정체성이 바로 이 두 번째 경우입니다. 타입은 사람이 맞춰야 합니다.
#
#   운영에서라면: 프로듀서/컨슈머의 (de)serializer 설정을 같은 저장소의
#   같은 상수로 관리하거나, Schema Registry 를 써서 타입을 데이터에 붙이십시오.


# ===========================================================================
# 정답 2 — 랙 확인과 오프셋 건너뛰기
# ===========================================================================
echo "### 정답 2 (a) 랙 확인"
K kafka-consumer-groups.sh --bootstrap-server $BS --describe --group s10-ex || true

# 해설 (a):
#   GROUP    TOPIC    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  ...
#   s10-ex   s10_ex   0          0               3               3    -   -   -
#
#   CURRENT-OFFSET 이 0 입니다. 컨슈머가 붙었다 죽기를 반복해도 이 값은 절대
#   올라가지 않습니다. 역직렬화는 **오프셋을 커밋하기 전에** 실패하기 때문입니다.
#   컨슈머가 이미 종료됐으므로 CONSUMER-ID / HOST / CLIENT-ID 는 '-' 로 나옵니다.
#   살아 있는 채로 무한 재시도 중이라면 여기에 consumer-s10-ex-1-... 이 찍힙니다.

echo "### 정답 2 (b) dry-run"
K kafka-consumer-groups.sh --bootstrap-server $BS \
  --group s10-ex --topic s10_ex:0 --reset-offsets --shift-by 1 --dry-run || true

# 해설 (b):
#   GROUP                          TOPIC                          PARTITION  NEW-OFFSET
#   s10-ex                         s10_ex                         0          1
#
#   --dry-run 은 **아무것도 바꾸지 않고** 결과만 보여줍니다.
#   --execute 는 되돌릴 수 없으므로, 운영에서는 반드시 dry-run 을 먼저 돌리십시오.
#   특히 --to-earliest 를 실수로 쓰면 그룹 전체가 처음부터 재처리를 시작합니다.
#   결제 컨슈머라면 그것만으로 사고입니다.

echo "### 정답 2 (c) 실제 적용"
K kafka-consumer-groups.sh --bootstrap-server $BS \
  --group s10-ex --topic s10_ex:0 --reset-offsets --shift-by 1 --execute || true

# 해설 (c):
#   ⚠️ 활성 멤버가 있으면 이렇게 거부됩니다:
#     Error: Assignments can only be reset if the group 's10-ex' is inactive,
#     but the current state is Stable.
#   컨슈머를 전부 내리고 다시 실행해야 합니다. 이 제약은 "두 주체가 동시에
#   오프셋을 쓰는 것"을 막기 위한 것이며, 우회 방법은 없습니다.
#
#   운영에서라면: 오프셋을 건너뛰기 전에 **버려질 메시지를 반드시 떠서 보관**하십시오.
#     K kafka-dump-log.sh --files /var/lib/kafka/data/s10_ex-0/00000000000000000000.log \
#       --print-data-log | grep 'offset: 0 '
#   건너뛴 메시지는 다시 찾을 수 없고, "무엇을 버렸는지" 를 나중에 반드시 묻습니다.


# ===========================================================================
# 정답 3 — 스키마 등록과 ID 재조회
# ===========================================================================
echo "### 정답 3"
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

# (a) 등록
RESP=$(curl -s -X POST "$SR_URL/subjects/payments-value/versions" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d "{\"schema\": $ESCAPED}")
echo "$RESP"        # {"id":6}  (앞 실습에서 쓴 ID 다음 번호가 붙습니다)

# (b) id 추출
if command -v jq >/dev/null 2>&1; then
  ID=$(printf '%s' "$RESP" | jq -r .id)
else
  ID=$(printf '%s' "$RESP" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
fi
echo "ID=$ID"

# (c) ID 로 재조회
curl -s "$SR_URL/schemas/ids/$ID"; echo

# 해설:
#   이 두 단계가 **컨슈머가 매 메시지마다 하는 일** 그 자체입니다.
#     ① 메시지 앞 5바이트에서 스키마 ID 를 읽는다
#     ② GET /schemas/ids/{id} 로 스키마를 가져온다
#     ③ 그 스키마로 나머지 바이트를 역직렬화한다
#   ②는 클라이언트가 캐시하므로 ID 당 **딱 한 번**만 일어납니다. 그래서
#   Schema Registry 가 잠깐 죽어도 이미 본 ID 의 메시지는 계속 처리됩니다.
#   반대로 **새 스키마 ID 가 처음 등장하는 순간** Registry 가 죽어 있으면
#   그 컨슈머는 그 자리에서 멈춥니다. Registry 는 조용한 단일 장애점입니다.
#
#   운영에서라면: Registry 를 최소 2대 이상 띄우고(리더 선출은 자동),
#   클라이언트에 여러 URL 을 콤마로 주십시오.
#     schema.registry.url=http://sr-1:8081,http://sr-2:8081


# ===========================================================================
# 정답 4 — BACKWARD 에서 기본값 없는 필드 추가
# ===========================================================================
echo "### 정답 4"
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
curl -s -X PUT "$SR_URL/config/payments-value" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"BACKWARD"}'; echo

# (b) 검사
curl -s -X POST "$SR_URL/compatibility/subjects/payments-value/versions/latest" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d "{\"schema\": $V2_ESCAPED}"; echo

# 해설:
#   결과:
#   {"is_compatible":false,"messages":["{errorType:'READER_FIELD_MISSING_DEFAULT_VALUE',
#     description:'The field 'refund_id' at path '/fields/4' in the new schema
#     has no default value.', additionalInfo:'refund_id'}"]}
#
#   BACKWARD = "새 스키마(v2)로 만든 **리더**가 옛 데이터(v1)를 읽을 수 있는가".
#   v1 로 쓴 메시지에는 refund_id 바이트가 아예 없습니다. v2 리더는 그 자리를
#   무엇으로 채울지 알 수 없고, 채울 값(default)이 지정되어 있지 않으므로 실패합니다.
#   → 그래서 errorType 이 READER_FIELD_MISSING_DEFAULT_VALUE 입니다.
#
#   고치는 법은 하나뿐입니다. 기본값을 주십시오.
#     {"name":"refund_id","type":["null","string"],"default":null}
#   union 을 쓸 때 **default 의 타입은 union 의 첫 번째 타입과 일치**해야 합니다.
#   ["string","null"] 에 default null 을 주면 스키마 파싱 단계에서 죽습니다.


# ===========================================================================
# 정답 5 — FORWARD 로 바꾸면 판정이 뒤집힌다
# ===========================================================================
echo "### 정답 5"
curl -s -X PUT "$SR_URL/config/payments-value" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"FORWARD"}'; echo

curl -s -X POST "$SR_URL/compatibility/subjects/payments-value/versions/latest" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d "{\"schema\": $V2_ESCAPED}"; echo

# 해설:
#   결과: {"is_compatible":true}
#
#   FORWARD = "옛 스키마(v1)로 만든 **리더**가 새 데이터(v2)를 읽을 수 있는가".
#   v1 리더는 자기가 모르는 refund_id 필드를 만나면 그냥 **건너뜁니다**.
#   채울 값이 필요 없으므로 default 도 필요 없습니다. 그래서 통과합니다.
#
#   한 줄 요약:
#     BACKWARD → 새 리더가 옛 데이터를 읽는다 → **컨슈머를 먼저 배포**
#     FORWARD  → 옛 리더가 새 데이터를 읽는다 → **프로듀서를 먼저 배포**
#
#   같은 변경이 어떤 수준에서는 막히고 어떤 수준에서는 통과합니다.
#   즉 호환성 수준은 "안전/불안전" 의 등급이 아니라 **배포 순서의 선언**입니다.
#   조직이 배포 순서를 통제할 수 없다면 FULL_TRANSITIVE 를 쓰는 것이
#   결국 가장 싸게 먹힙니다(기본값 있는 추가·삭제만 허용).
#
#   운영에서라면: 이 compatibility 검사를 **CI 파이프라인에 넣으십시오.**
#   빌드 단계에서 curl 한 번으로 is_compatible 을 확인하고 false 면 빌드 실패.
#   운영 배포 후에 알게 되는 것과 비용이 100배 차이납니다.

# 뒷정리를 위해 BACKWARD 로 복구
curl -s -X PUT "$SR_URL/config/payments-value" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"compatibility":"BACKWARD"}' >/dev/null


# ===========================================================================
# 정답 6 — 와이어 포맷 바이트 읽기
# ===========================================================================
echo "### 정답 6"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s10_ex_avro --partitions 1 --replication-factor 3 2>/dev/null || true
SRX kafka-avro-console-producer \
  --bootstrap-server $BS --topic s10_ex_avro \
  --property schema.registry.url=http://schema-registry:8081 \
  --property value.schema='{"type":"record","name":"Payment","namespace":"shop.payment","fields":[{"name":"order_id","type":"string"},{"name":"method","type":"string"},{"name":"amount","type":"int"},{"name":"result","type":"string"}]}' <<'EOF'
{"order_id":"O-2001","method":"CARD","amount":39000,"result":"APPROVED"}
EOF

# (a) xxd
docker exec kafka-1 sh -c \
  'tail -c 60 /var/lib/kafka/data/s10_ex_avro-0/00000000000000000000.log | xxd | tail -4'

# 해설 (a):
#   00000000: 0000 0000 0700 0000 0000 0000 0000 0000  ................
#   00000010: 4f2d 3230 3031 0843 4152 4480 e004 1041  O-2001.CARD....A
#   00000020: 5050 524f 5645 44                        PPROVED
#
#   앞에서부터 한 바이트씩 읽습니다.
#     00              → 매직바이트. Confluent 와이어 포맷 버전. 현재 0x00 하나뿐.
#     00 00 00 07     → 스키마 ID = 7 (빅엔디언 int32)
#     0c              → Avro 문자열 길이 6 의 zigzag varint  ("O-2001")
#     08              → 길이 4                              ("CARD")
#     80 e0 04        → 39000 의 zigzag varint (3바이트)
#     10 + 8바이트     → 길이 8 + "APPROVED"
#
#   **필드 이름이 어디에도 없습니다.** 이것이 Avro 가 JSON 의 약 1/3.5 크기인
#   이유이자, 스키마 없이는 절대 못 읽는 이유입니다.
#   빅엔디언 4바이트를 셸에서 확인하려면:  printf '%d\n' 0x00000007  → 7

# (b) 읽어낸 ID 로 조회
curl -s "$SR_URL/schemas/ids/7"; echo

# 해설 (b):
#   {"schema":"{\"type\":\"record\",\"name\":\"Payment\",...}"}
#
#   ID 가 예제와 다르게 나오는 것이 정상입니다. 스키마 ID 는 **클러스터 전역 시퀀스**라
#   앞 실습에서 몇 개를 등록했느냐에 따라 달라집니다. 중요한 것은 숫자 자체가 아니라
#   "메시지 앞 5바이트만 보면 어떤 스키마인지 알 수 있다" 는 구조입니다.
#
#   운영에서라면: 이 5바이트 덕분에 **한 토픽에 여러 버전이 섞여 있어도** 각각
#   올바르게 읽힙니다. v1 로 쓴 옛 메시지와 v3 로 쓴 새 메시지가 나란히 있어도
#   컨슈머는 메시지마다 ID 를 보고 알맞은 writer 스키마를 골라 씁니다.
#   그래서 스키마 진화가 "토픽을 비우고 다시 시작" 없이 가능한 것입니다.


# ===========================================================================
# 정리
# ===========================================================================
echo "### 정리"
curl -s -X DELETE "$SR_URL/subjects/payments-value"                   >/dev/null 2>&1
curl -s -X DELETE "$SR_URL/subjects/payments-value?permanent=true"    >/dev/null 2>&1
curl -s -X DELETE "$SR_URL/subjects/s10_ex_avro-value"                >/dev/null 2>&1
curl -s -X DELETE "$SR_URL/subjects/s10_ex_avro-value?permanent=true" >/dev/null 2>&1
K kafka-topics.sh --bootstrap-server $BS --delete --topic s10_ex      2>/dev/null || true
K kafka-topics.sh --bootstrap-server $BS --delete --topic s10_ex_avro 2>/dev/null || true
K kafka-consumer-groups.sh --bootstrap-server $BS --delete --group s10-ex 2>/dev/null || true
echo "남은 subject:"; curl -s "$SR_URL/subjects"; echo
echo "=== Step 10 solution 완료 ==="
