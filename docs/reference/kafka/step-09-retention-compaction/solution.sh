#!/usr/bin/env bash
#
# Step 09 — 보존과 로그 압축 : 연습문제 정답과 해설
#
# 실행법:
#   cd kafka/docker && bash ../step-09-retention-compaction/solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여세요.
#
set -euo pipefail

K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
BS=kafka-1:9092
DATA=/var/lib/kafka/data

hr() { echo; echo "========================================================="; echo " $*"; echo "========================================================="; }


# ══════════════════════════════════════════════════════════════════════
hr "정답 1 — retention.ms 만 걸면 안 지워진다"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_ret --partitions 1 --replication-factor 3 --config retention.ms=5000

K kafka-producer-perf-test.sh --topic s09ex_ret \
  --num-records 300 --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all

echo "-- BEFORE"
docker exec kafka-1 ls -la "$DATA/s09ex_ret-0"

echo "-- 90초 대기 (보존 시간 5초의 18배)"
sleep 90

echo "-- AFTER: 하나도 안 바뀝니다"
docker exec kafka-1 ls -la "$DATA/s09ex_ret-0"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s09ex_ret --time -2

echo
echo "정답: 안 지워집니다. 세그먼트가 하나뿐이고 그것이 액티브 세그먼트이기 때문입니다."

# 해설:
#   보존 삭제는 "세그먼트 파일 단위" 로만 일어나고,
#   ★ 액티브 세그먼트(지금 쓰고 있는 파일)는 절대 삭제 대상이 아닙니다.
#
#   300건 × 1KB = 약 300KB 이고 segment.bytes 는 1MiB 이므로 롤링되지 않았습니다.
#   segment.ms 기본값은 7일이므로 시간으로도 롤링되지 않습니다.
#   → 닫힌 세그먼트가 0개 → 삭제 후보가 0개 → 아무 일도 안 일어납니다.
#
#   ★ 브로커 로그가 조용한 것이 정상입니다.
#     log.retention.check.interval.ms=10000 이므로 브로커는 10초마다 검사하고 있습니다.
#     다만 매번 "닫힌 세그먼트 중 보존 위반" 을 찾는데 닫힌 세그먼트가 없어서
#     조용히 아무것도 안 하고 돌아갑니다. 에러도 경고도 없습니다.
#
#   이것이 실무에서 "개인정보 보존기간 1일로 설정했다" 고 보고했는데
#   실제로는 8일 넘게 남아 있는 상황의 정체입니다.
#   트래픽이 적은 토픽일수록 이 격차가 큽니다.


# ══════════════════════════════════════════════════════════════════════
hr "정답 2 — segment.ms 하나를 추가한다 (그리고 레코드를 하나 더 넣는다)"
# ══════════════════════════════════════════════════════════════════════
K kafka-configs.sh --bootstrap-server "$BS" --alter \
  --entity-type topics --entity-name s09ex_ret --add-config segment.ms=5000

echo "-- 설정 변경 직후 ls -la : 아직 아무 변화가 없습니다"
docker exec kafka-1 ls -la "$DATA/s09ex_ret-0"

echo
echo "-- ★ 새 레코드를 하나 넣어야 롤링 판정이 일어납니다"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_ret <<'EOF'
{"trigger":"roll"}
EOF

sleep 30

echo "-- AFTER: 00000000000000000000.log 가 사라졌습니다"
docker exec kafka-1 ls -la "$DATA/s09ex_ret-0"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s09ex_ret --time -2

echo
docker logs kafka-1 --since 3m 2>&1 | grep -E 'Rolled|Deleting segment|Deleted log' || true

# 해설:
#   정답은 segment.ms 하나입니다. retention.ms 는 이미 걸려 있으므로
#   부족한 것은 "닫힌 세그먼트를 만들어 주는 것" 뿐입니다.
#
#   ★ 그런데 설정만 바꿔서는 아무 일도 안 일어납니다. 이것이 문제 2의 진짜 함정입니다.
#     롤링 판정은 브로커가 append 경로에서 합니다. 즉 "새 레코드가 들어올 때"
#     "이 세그먼트가 segment.bytes 를 넘었나? segment.ms 가 지났나?" 를 검사합니다.
#     레코드가 안 들어오면 그 검사 자체가 실행되지 않습니다.
#
#   그래서 트래픽이 완전히 멈춘 토픽은 segment.ms 를 걸어도 롤링되지 않고,
#   결과적으로 보존 삭제도 되지 않습니다. 마지막 세그먼트는 영원히 남습니다.
#
#   ★ 브로커 로그의 세 줄이 순서를 정확히 말해 줍니다:
#     ① Rolled new log segment at offset 301
#     ② Deleting segment ... due to retention time 5000ms breach
#        based on the largest record timestamp in the segment
#     ③ Deleted log .../00000000000000000000.log.deleted
#     ①이 없으면 ②도 ③도 없습니다.
#
#   ②의 "based on the largest record timestamp" 도 중요합니다.
#   삭제 판정은 파일 수정 시각이 아니라 세그먼트 안의 최대 레코드 타임스탬프 기준입니다.
#   프로듀서가 CreateTime 을 과거로 조작하면(마이그레이션에서 흔합니다)
#   넣자마자 보존 위반 상태가 되고, 미래로 조작하면 영원히 안 지워집니다.
#   message.timestamp.type=LogAppendTime 으로 바꾸면 브로커가 수신 시각으로 덮어씁니다.
#
#   ★ 트레이드오프: segment.ms 를 짧게 하면 세그먼트 파일 수가 늘어
#     파일 핸들과 인덱스 메모리를 더 씁니다. .index 와 .timeindex 는
#     세그먼트마다 최대 10MiB 씩 미리 할당되므로 오버헤드가 작지 않습니다.
#     "정확한 보존 기간" 과 "파일 수" 의 트레이드오프입니다.


# ══════════════════════════════════════════════════════════════════════
hr "정답 3 — retention.bytes 는 파티션당 크기다"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_bytes --partitions 12 --replication-factor 3 \
  --config retention.bytes=536870912

cat <<'CALC'
  retention.bytes = 536870912 = 512 MiB   ← ★ 파티션당 (토픽 전체가 아님)
  파티션 수       = 12
  ────────────────────────────────────────
  토픽 데이터 총량 = 512 MiB × 12 = 6 GiB
  복제 계수 RF     = 3
  ────────────────────────────────────────
  클러스터 전체    = 6 GiB × 3 = 18 GiB

  여기에 실무 여유율을 더하면:
    + 액티브 세그먼트 (파티션·리플리카마다 최대 1MiB)
    + 인덱스 파일 (.index + .timeindex = 세그먼트당 최대 20MiB 사전 할당)
    + 삭제 지연분 (file.delete.delay.ms)
  → 18 GiB × 1.3 ≈ 23.4 GiB 를 산정하는 것이 안전합니다.

  산정식:  필요 디스크 = retention.bytes × 파티션 수 × RF × 1.3
CALC

# 해설:
#   가장 흔한 오답은 "512 MiB" 또는 "1.5 GiB" 입니다.
#   retention.bytes 를 토픽 전체 상한으로 오해했기 때문입니다.
#
#   ★ 더 위험한 것은 파티션 수를 나중에 늘릴 때입니다.
#     12개 → 24개로 늘리면 아무도 retention.bytes 를 안 건드렸는데
#     디스크 사용량 상한이 그 순간 36 GiB 로 두 배가 됩니다.
#     파티션 증설은 Step 03 에서 봤듯 순서 보장도 깨뜨리는데, 디스크까지 건드립니다.
#
#   ★ 그리고 retention.ms 와 retention.bytes 는 OR 조건입니다.
#     "둘 다 만족해야 삭제" 가 아니라 "둘 중 하나만 걸려도 삭제" 입니다.
#     retention.ms=7일 + retention.bytes=512MiB 인 토픽에 트래픽이 몰려
#     512 MiB 를 하루 만에 채우면 하루치만 남습니다.
#     "7일치 보관" 이라고 믿고 있던 재처리 계획이 그날 조용히 무너집니다.
#     시간 기준 보존이 계약 사항이면 retention.bytes 는 -1 로 두고
#     디스크는 용량 알람으로 관리하세요.


# ══════════════════════════════════════════════════════════════════════
hr "정답 4 — 압축은 오프셋에 구멍을 낸다"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_compact --partitions 1 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=10000 \
  --config min.compaction.lag.ms=0

# 4-a: 키 5개 × 4회 = 20건
{
  for v in 1 2 3 4; do
    for k in 1 2 3 4 5; do
      echo "K-${k}:{\"k\":\"K-${k}\",\"v\":${v}}"
    done
  done
} | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server "$BS" --topic s09ex_compact \
      --property parse.key=true --property key.separator=:

# 4-b: 압축 전
echo
echo "########## 압축 전 (20건) ##########"
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files "$DATA/s09ex_compact-0/00000000000000000000.log" \
  --print-data-log | grep -E '^\| offset' || true

# 4-c: 롤링 유도 + 대기
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
K-TRIGGER:{"k":"K-TRIGGER","v":0}
EOF
sleep 40

echo
echo "########## 압축 후 (5건, 오프셋 15~19) ##########"
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files "$DATA/s09ex_compact-0/00000000000000000000.log" \
  --print-data-log | grep -E '^\| offset' || true

# 4-d
echo
docker logs kafka-1 --since 2m 2>&1 | grep -iE 'size reduction|Start size|End size|dirty section' || \
  echo '(클리너 로그를 못 찾았습니다)'

# 해설:
#   압축 후 5건이 남고 오프셋은 15, 16, 17, 18, 19 입니다.
#   각 키의 마지막 쓰기(v=4 라운드)가 오프셋 15~19 에 있었기 때문입니다.
#   클리너 로그는 이렇게 나옵니다:
#     Start size: 20 messages / End size: 5 messages / 75.0% size reduction
#     dirty section = [0, 20]
#
#   ★ 핵심은 오프셋 번호가 재부여되지 않는다는 것입니다.
#     0~14 는 통째로 사라지고, 살아남은 레코드는 원래 번호를 그대로 씁니다.
#     압축은 "로그를 다시 쓰는" 것이 아니라 "안 쓰는 레코드를 빼고 옮겨 담는" 것입니다.
#
#   ★ 그래서 압축 토픽에서 "오프셋 + 1 = 다음 레코드" 를 가정하면 안 됩니다.
#     - seek(partition, 3) 은 에러가 아니라 "3 이상의 첫 레코드" 인 오프셋 15 로 갑니다.
#     - LOG-END-OFFSET 과 실제 레코드 수가 다르므로,
#       "오프셋 개수 = 메시지 개수" 를 가정한 랙 계산이 부정확해집니다.
#       압축 토픽의 랙은 "몇 건 남았나" 가 아니라 "얼마나 뒤처졌나" 로만 읽어야 합니다.
#
#   ★ 그리고 압축은 "최신 값만 보인다" 를 보장하지 않습니다.
#     위 출력에도 액티브 세그먼트의 K-TRIGGER 는 압축되지 않은 채 있습니다.
#     트래픽이 있는 실제 토픽이라면 액티브 세그먼트에 같은 키가 수십 개 있는 게 정상입니다.
#     컨슈머는 반드시 오프셋 순서대로 끝까지 읽고 같은 키를 만나면 덮어써야 합니다:
#       for (record : poll()) cache.put(record.key(), record.value());   // 나중 것이 이긴다
#     첫 번째로 만난 값을 쓰고 멈추면 옛 상태를 잡습니다. Kafka Streams 의 KTable 이
#     정확히 이 덮어쓰기 방식으로 동작합니다(Step 13).


# ══════════════════════════════════════════════════════════════════════
hr "정답 5 — tombstone(-1) 과 빈 문자열(0)"
# ══════════════════════════════════════════════════════════════════════
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_compact \
  --property parse.key=true --property key.separator=: \
  --property null.marker=NULL <<'EOF'
K-1:NULL
K-2:
EOF

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
K-TRIGGER2:{"k":"K-TRIGGER2","v":0}
EOF
sleep 20

echo
echo "-- valueSize: -1 (tombstone) 과 valueSize: 0 (빈 문자열) 이 나란히 보여야 합니다"
SEG=$(docker exec kafka-1 sh -c "ls $DATA/s09ex_compact-0/*.log | sort | tail -2 | head -1")
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh --files "$SEG" \
  --print-data-log | grep -E '^\| offset' || true

sleep 40
echo
echo "-- 압축 후 컨슈머: K-1 은 사라지고 K-2 는 빈 값으로 남습니다"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s09ex_compact \
  --from-beginning --property print.key=true --property print.offset=true \
  --timeout-ms 5000 2>&1 || true

# 해설:
#   K-1 은 사라지고, K-2 는 "빈 값" 으로 남습니다.
#
#   ★ 값이 null 인 레코드만 tombstone 입니다. 빈 문자열은 유효한 값입니다.
#     dump-log 에서:
#       valueSize: -1  → tombstone. 압축 시 그 키를 통째로 제거
#       valueSize:  0  → 길이 0 인 문자열. 그 키의 "최신 값" 으로 살아남음
#
#   실무 사고 시나리오가 정확히 여기서 나옵니다.
#   "삭제 이벤트를 보냈는데 왜 안 지워지지?" → 값을 빈 문자열로 보냈기 때문입니다.
#   에러는 없습니다. 컨슈머는 빈 값을 "유효한 상태" 로 받아 캐시에 넣습니다.
#   그러면 삭제된 줄 알았던 레코드가 빈 껍데기로 살아 있게 됩니다.
#   JSON 파서가 빈 문자열에서 예외를 던지면 그나마 다행이고,
#   null 체크만 하는 코드라면 아무도 눈치채지 못합니다.
#
#   ★ 3.7 의 kafka-console-producer.sh 는 --property null.marker=<문자열> 을 지원합니다.
#     지정한 문자열이 값 자리에 오면 그 레코드를 null 값으로 보냅니다.
#     옛 버전에서는 kafkacat -Z 를 쓰거나 자바로
#     new ProducerRecord<>(topic, key, null) 을 보내야 했습니다.
#
#   ★ delete.retention.ms (기본 24시간) 가 왜 필요한가:
#     tombstone 은 두 단계로 처리됩니다.
#       ① 1차 압축: 그 키의 옛 값들이 사라지고 tombstone 만 남는다
#          → 이 상태를 delete.retention.ms 동안 유지해
#            뒤늦게 붙는 컨슈머도 "삭제됐다" 를 읽을 수 있게 한다
#       ② delete.retention.ms 경과 후: tombstone 자신도 삭제
#
#     ★ 그래서 delete.retention.ms 보다 오래 멈춰 있던 컨슈머는
#       삭제 사실을 영영 못 봅니다. 이미 캐시를 갖고 있다가 30시간 만에 재기동하면
#       마지막 커밋 오프셋부터 이어 읽는데, 그 사이 tombstone 이 사라졌으므로
#       캐시에 삭제된 키가 그대로 남습니다.
#       "이미 취소한 주문이 목록에 계속 보인다" 의 정체입니다. 에러는 없습니다.
#
#       대응 세 가지:
#         ① 압축 토픽 컨슈머의 "마지막 커밋 시각" 에 알람 (랙이 아니라 시각)
#         ② 오래 멈췄던 컨슈머는 오프셋을 이어받지 말고 처음부터 재구축
#            (압축 토픽은 키 수 = 레코드 수라 이게 쌉니다)
#         ③ delete.retention.ms 를 컨슈머 최대 중단 시간보다 넉넉하게


# ══════════════════════════════════════════════════════════════════════
hr "정답 6 — 키가 null 이면 클리너가 압축을 거부한다"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_nullkey --partitions 1 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=10000

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_nullkey \
  --property parse.key=true --property key.separator=: <<'EOF'
A:{"k":"A","v":1}
B:{"k":"B","v":1}
A:{"k":"A","v":2}
B:{"k":"B","v":2}
A:{"k":"A","v":3}
EOF

echo "-- 이번에는 키 없이 3건 (parse.key 를 쓰지 않습니다)"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_nullkey <<'EOF'
{"no":"key1"}
{"no":"key2"}
{"no":"key3"}
EOF

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09ex_nullkey \
  --property parse.key=true --property key.separator=: <<'EOF'
TRIGGER:{"k":"TRIGGER","v":0}
EOF
sleep 40

echo
docker logs kafka-1 --since 2m 2>&1 | grep -i 'skipping compaction' || \
  echo '(경고를 못 찾았습니다 — 대기 시간을 늘려 보세요)'

echo
echo "-- 레코드가 하나도 안 줄었는지 확인"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s09ex_nullkey \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1 || true

# 해설:
#   로그에 이런 경고가 남습니다:
#     WARN Cleaner 0: Skipping compaction of s09ex_nullkey-0
#          due to record with null key at offset 5 (kafka.log.LogCleaner)
#
#   ★ 에러가 아니라 경고입니다. 그래서 더 위험합니다.
#     - 프로듀서는 정상입니다. 계속 성공합니다.
#     - 컨슈머도 정상입니다. 모든 레코드를 다 받습니다.
#     - 브로커는 살아 있고, under-replicated 도 0 이고, 랙도 정상입니다.
#     - 다만 그 파티션의 압축만 조용히 멈춥니다.
#       → 토픽이 계속 커집니다. 한 달 뒤 디스크가 찹니다.
#       → 그때 원인을 찾으려면 한 달 전 로그의 WARN 한 줄을 찾아야 합니다.
#
#   ★ 압축 토픽에 키 없는 레코드를 넣을 수 있다는 것 자체가 함정입니다.
#     브로커는 그것을 거부하지 않습니다. 압축이 불가능해질 뿐입니다.
#
#   이 지뢰를 가장 자주 밟는 경로는 "delete 토픽을 나중에 compact 로 바꾸는 것" 입니다.
#     kconf --alter --add-config cleanup.policy=compact
#   이 명령은 즉시 적용되고 재시작도 필요 없습니다.
#   그 토픽에 키 없는 레코드가 하나라도 섞여 있으면 압축이 그 지점에서 멈춥니다.
#   그리고 키가 있는 레코드들은 옛 값이 사라져 이력이 손상됩니다. 되돌릴 수 없습니다.
#
#   ★ 원칙: cleanup.policy 변경은 새 토픽을 만들어 마이그레이션하는 것입니다.
#     운영 중인 토픽의 정책을 바꾸지 마세요.


# ══════════════════════════════════════════════════════════════════════
hr "정답 7 — 압축 적합성 판정"
# ══════════════════════════════════════════════════════════════════════
cat <<'JUDGE'
  설계 기준:  ① 키 필수  ② 카디널리티 유한  ③ 값이 전체 상태(델타 금지)  ④ 순서 의존 금지

  (A) user-profile-snapshot        ✅ 적합
      ① 키 = user_id                                    OK
      ② 사용자 수는 유한                                 OK
      ③ 값 = 프로필 전체 JSON (델타 아님)                 OK
      ④ "현재 프로필" 만 필요, 변경 이력 불필요            OK
      → 교과서적인 압축 토픽입니다. 탈퇴 시 tombstone 으로 키를 지웁니다.

  (B) page-view-log                ❌ 부적합 (① ④ 위반)
      ① 키가 없다 → 클리너가 압축을 아예 거부 (정답 6 참조)
      ④ 페이지뷰는 "몇 번 봤는가" 가 데이터의 본질이라
         같은 키의 옛 레코드를 지우면 집계가 불가능
      → cleanup.policy=delete + 적절한 retention.ms 가 맞습니다.

  (C) account-balance-delta        ❌ 부적합 (③ 위반 — 가장 위험한 케이스)
      ③ 값이 델타(+10000)입니다.
         압축 후 마지막 delta 하나만 남으면 컨슈머가 계산한 잔액이 틀립니다.
           +10000, +5000, -3000  →  압축 후 -3000 만 남음
           컨슈머 계산: -3000원.  실제: 12000원.  에러 없음, 숫자만 틀림.
      → 값을 "잔액 전체" 로 바꾸면(account-balance-snapshot) 적합해집니다.
         또는 이벤트 스트림으로 두고 cleanup.policy=delete 를 씁니다.

  (D) session-heartbeat            ⚠️ 조건부 적합 (② 위반)
      ① 키 = session_id                                  OK
      ③ 값 = last_seen 전체 상태                          OK
      ④ 마지막 상태만 필요                                OK
      ② 세션은 계속 새로 생기므로 키 카디널리티가 무한히 증가
      → cleanup.policy=compact 만 걸면 토픽이 무한 성장합니다.
         compact,delete + retention.ms 로 상한을 줘야 합니다.
         (Kafka Streams 의 윈도우 상태 저장소가 정확히 이 조합을 씁니다 — Step 13)
JUDGE

# 해설:
#   ★ 이 스텝의 결론은 한 문장으로 정리됩니다.
#     압축은 "이벤트 스트림" 이 아니라 "상태 스냅샷" 을 위한 도구입니다.
#
#   (B) 와 (C) 는 이벤트 스트림입니다. 각 레코드가 "무슨 일이 있었는가" 를 말하고
#   그 이력 자체가 데이터입니다. 압축은 이력을 지우므로 근본적으로 상충합니다.
#
#   (A) 와 (D) 는 상태 스냅샷입니다. 각 레코드가 "지금 어떤 상태인가" 를 말하고
#   옛 레코드는 버려도 됩니다. 압축이 정확히 이것을 위해 만들어졌습니다.
#
#   ★ 판정이 가장 어려운 것은 (C) 입니다. 키도 있고 카디널리티도 유한해서
#     ①②④ 만 보면 통과합니다. ③ 하나가 걸리는데, 그 결과가
#     "에러 없이 숫자만 틀리는" 형태라 발견이 가장 늦습니다.
#     압축 토픽 설계 리뷰에서 ③ 을 가장 먼저 물어야 하는 이유입니다.
#
#   ★ 이 코스의 order-events 토픽은 네 조건을 모두 만족합니다.
#     키 = order_id, 주문 수 유한, 값 = 주문의 현재 상태 전체, 최종 상태만 필요.
#     Kafka 자신의 __consumer_offsets 도 마찬가지입니다.
#     키 = (그룹, 토픽, 파티션), 유한, 값 = 커밋 오프셋 절대값, 마지막 커밋만 필요.


# ══════════════════════════════════════════════════════════════════════
hr "뒷정리 — s09ex_ 토픽 삭제"
# ══════════════════════════════════════════════════════════════════════
for t in s09ex_ret s09ex_bytes s09ex_compact s09ex_nullkey; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" 2>/dev/null || true
done
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s09ex_' \
  && { echo 'FAIL: s09ex_ 토픽이 남아 있습니다'; exit 1; } || echo 'OK: s09ex_ 토픽 없음'

docker compose ps --format 'table {{.Name}}\t{{.Status}}'
echo "완료. → Step 10"
