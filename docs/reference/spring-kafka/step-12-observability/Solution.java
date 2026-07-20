package com.example.order.step12;

/*
 * ============================================================================
 * Step 12 — 관측성과 운영 : Solution (6문제 정답)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step12/Solution.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step12,step12-sol'
 *
 * ★ 실행 전 오프셋 리셋
 *   kcg --group s12-sol-inventory --topic orders --reset-offsets --to-earliest --execute
 *
 * 반드시 직접 풀어 본 뒤에 여세요.
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class Solution {

    private Solution() { }

    /* =======================================================================
     * 정답 1. /actuator/prometheus 의 랙 지표 두 종류
     * =======================================================================
     *
     * $ curl -s localhost:8080/actuator/prometheus | grep -E '^kafka_consumer_fetch_manager_records_lag'
     *
     * kafka_consumer_fetch_manager_records_lag_max{application="order-service",
     *   client_id="consumer-s12-sol-inventory-2",kafka_version="3.6.1",
     *   spring_id="consumerFactory.s12-sol-inventory-1",} 31.0
     * kafka_consumer_fetch_manager_records_lag{application="order-service",
     *   client_id="consumer-s12-sol-inventory-2",partition="1",topic="orders",} 31.0
     *
     * ┌────────────────────────┬──────────────────────────────────────────────┐
     * │ 지표                    │ 라벨                                          │
     * ├────────────────────────┼──────────────────────────────────────────────┤
     * │ ..._records_lag        │ client_id, topic, partition                  │
     * │ ..._records_lag_max    │ client_id, spring_id, kafka_version (파티션 없음)│
     * └────────────────────────┴──────────────────────────────────────────────┘
     *
     * 파티션 단위 알람에는 records_lag(단수)를 씁니다. lag_max 는 파티션 라벨이
     * 없어서 "어느 파티션이 밀렸는지" 를 알 수 없고, 이름 그대로 그 컨슈머가
     * 담당한 파티션들 중 최댓값만 줍니다.
     *
     * 그러면 lag_max 는 왜 있는가? 파티션이 수백 개인 토픽에서 시계열 카디널리티를
     * 줄이려고 씁니다. 대시보드 상단의 "요약 한 줄" 용도입니다.
     *
     * ⚠️ 그리고 이 둘은 12-4 의 함정을 똑같이 갖고 있습니다. 컨슈머가 죽으면
     *    둘 다 사라집니다. 그래서 정답 2 의 게이지가 필요합니다.
     * ===================================================================== */

    /* =======================================================================
     * 정답 2. AdminClient + MultiGauge 로 랙 게이지 등록
     * =======================================================================
     *
     * 왜 이 답인가
     *
     * ① 커밋 오프셋은 AdminClient 로만 얻을 수 있습니다.
     *    컨슈머 객체의 position() 은 "그 컨슈머가 읽은 위치" 이지 "그룹이 커밋한
     *    위치" 가 아닙니다. 컨슈머가 죽어 있으면 position() 자체가 없습니다.
     *    listConsumerGroupOffsets 는 __consumer_offsets 를 읽으므로 컨슈머의
     *    생사와 무관합니다. 이것이 이 문제의 전부입니다.
     *
     * ② endOffsets 는 그룹 멤버십이 필요 없습니다.
     *    probe 컨슈머는 subscribe 도 assign 도 하지 않습니다. endOffsets 는
     *    ListOffsets API 를 직접 부르는 메타데이터 조회입니다.
     *    ⚠️ 여기서 실제 그룹 ID 로 subscribe 를 걸면 측정용 컨슈머가 그룹에
     *       합류해 리밸런스를 일으키고, 진짜 컨슈머에게서 파티션을 빼앗습니다.
     *       "관측하려다 장애를 만드는" 전형적인 사고입니다.
     *
     * ③ register(rows, true) 의 overwrite=true 가 핵심입니다.
     *    false 로 두면 이미 등록된 태그 조합(group/topic/partition)의 값이
     *    갱신되지 않습니다. 첫 측정값 그대로 얼어붙고, 예외도 경고도 없습니다.
     *
     *    실험 결과 (register(rows, false)):
     *      10:00:05  totalLag=0     → 게이지 0
     *      10:00:15  totalLag=284   → 게이지 여전히 0   ★
     *      10:00:25  totalLag=1176  → 게이지 여전히 0   ★
     *    로그의 totalLag 은 늘어나는데 /actuator/metrics 는 0 입니다.
     *    이 코스가 말하는 "에러 없이 조용히 틀린" 코드의 표본입니다.
     *
     * ④ Math.max(0, ...) 로 음수를 막습니다.
     *    커밋 오프셋과 endOffsets 를 서로 다른 시점에 읽으므로, 처리가 빠를 때
     *    아주 잠깐 음수가 나올 수 있습니다. 음수 랙은 대시보드를 망칩니다.
     *
     * ⑤ 예외를 삼키고 로그만 남깁니다.
     *    @Scheduled 메서드에서 예외를 던지면 스케줄러 로그에만 남고 다음 주기는
     *    계속 돕니다. 하지만 브로커 일시 장애 때마다 ERROR 스택트레이스가
     *    쏟아지므로 WARN 한 줄로 줄이는 편이 낫습니다.
     *
     * ⑥ 리스너를 멈춘 뒤 확인:
     *    $ curl -X POST localhost:8080/admin/listeners/stop && sleep 30
     *    $ curl -s localhost:8080/actuator/prometheus | grep -c records_lag   → 0
     *    $ curl -s localhost:8080/actuator/prometheus | grep -c group_lag     → 3  ★
     *    kafka_consumer_group_lag{...,partition="1",topic="orders",} 1284.0
     * ===================================================================== */
    @Component
    @Profile("step12-sol")
    public static class Sol2LagMetrics {

        private static final Logger log = LoggerFactory.getLogger(Sol2LagMetrics.class);
        private static final String GROUP = "s12-sol-inventory";

        private final AdminClient admin;
        private final Consumer<?, ?> probe;
        private final MultiGauge lagGauge;

        public Sol2LagMetrics(KafkaAdmin kafkaAdmin,
                              ConsumerFactory<?, ?> consumerFactory,
                              MeterRegistry registry) {
            this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
            this.probe = consumerFactory.createConsumer("s12-sol-lag-probe", "-probe");
            this.lagGauge = MultiGauge.builder("kafka.consumer.group.lag")
                    .description("committed offset 기준 파티션별 컨슈머 랙")
                    .baseUnit("records")
                    .register(registry);
        }

        @Scheduled(fixedDelay = 10_000L, initialDelay = 5_000L)
        public void refresh() {
            try {
                Map<TopicPartition, OffsetAndMetadata> committed =
                        admin.listConsumerGroupOffsets(GROUP)
                                .partitionsToOffsetAndMetadata()
                                .get(5, TimeUnit.SECONDS);

                if (committed.isEmpty()) {
                    lagGauge.register(List.of(), true);
                    log.info("group={} 커밋된 오프셋이 없습니다", GROUP);
                    return;
                }

                Map<TopicPartition, Long> endOffsets = probe.endOffsets(committed.keySet());

                List<MultiGauge.Row<?>> rows = new ArrayList<>();
                long total = 0L;
                for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                    TopicPartition tp = e.getKey();
                    long lag = Math.max(0L, endOffsets.getOrDefault(tp, 0L) - e.getValue().offset());
                    total += lag;
                    rows.add(MultiGauge.Row.of(
                            Tags.of("group", GROUP,
                                    "topic", tp.topic(),
                                    "partition", String.valueOf(tp.partition())),
                            lag));
                }
                lagGauge.register(rows, true);
                log.info("group={} totalLag={} partitions={}", GROUP, total, rows.size());

            } catch (Exception ex) {
                log.warn("랙 수집 실패 group={} : {}", GROUP, ex.toString());
            }
        }

        @PreDestroy
        public void close() {
            probe.close();
            admin.close();
        }
    }

    /* =======================================================================
     * 정답 3. Timer.Sample 로 비즈니스 구간만 측정
     * =======================================================================
     *
     * 왜 이 답인가
     *
     * ① finally 에서 stop 합니다.
     *    예외가 나도 시간이 기록되어야 "실패한 처리가 얼마나 오래 걸렸는지" 를
     *    볼 수 있습니다. 느려서 타임아웃으로 실패하는 경우가 가장 흔한데,
     *    catch 안에서만 stop 하면 그 데이터가 안 남습니다.
     *
     * ② outcome 태그를 예외 발생 시 "error" 로 바꾼 뒤 rethrow 합니다.
     *    rethrow 를 빼면 DefaultErrorHandler 가 예외를 못 받아 재시도도 DLT 도
     *    동작하지 않습니다(Step 07 의 함정과 같은 구조).
     *
     * ③ 실측 (10분 부하 후)
     *      spring.kafka.listener    COUNT=1842  TOTAL_TIME=41.196  MAX=1.084
     *      order.inventory.deduct   COUNT=1842  TOTAL_TIME=38.940  MAX=1.061
     *      프레임워크 오버헤드 = 41.196 - 38.940 = 2.256초 (건당 약 1.2ms)
     *
     *    2.256초의 정체는 역직렬화(JsonDeserializer) + RecordInterceptor +
     *    Observation 생성 + 메시지 변환입니다. 전체의 5.5% 입니다.
     *    "Kafka 가 느리다" 는 신고의 대부분은 이 5% 가 아니라 나머지 95% 입니다.
     *
     * ④ 두 COUNT 가 다를 수 있는 이유
     *    재시도가 걸리면 같은 레코드로 리스너가 여러 번 호출됩니다. 두 타이머
     *    모두 호출 횟수만큼 늘어나므로 보통은 같습니다. 하지만
     *    - 리스너 진입 전(역직렬화 단계)에서 실패하면 spring.kafka.listener 만 증가
     *    - deduct 호출 전에 return 하는 분기가 있으면 order.inventory.deduct 만 미증가
     *    두 경우에 어긋납니다. COUNT 가 다르면 TOTAL_TIME 비교는 무의미하므로
     *    반드시 먼저 확인해야 합니다.
     *
     * ⑤ @Timed 를 쓰지 않은 이유
     *    @Timed 는 TimedAspect 빈을 직접 등록해야 동작하고, @KafkaListener
     *    메서드는 프록시 대상이 되면 리스너 등록 자체가 꼬일 수 있습니다.
     *    리스너 내부에서는 Timer.Sample 이 정공법입니다.
     * ===================================================================== */
    @Component
    @Profile("step12-sol")
    public static class Sol3TimedListener {

        private static final Logger log = LoggerFactory.getLogger(Sol3TimedListener.class);

        private final MeterRegistry registry;

        public Sol3TimedListener(MeterRegistry registry) {
            this.registry = registry;
        }

        @KafkaListener(id = "s12-sol-inventory", topics = "orders", groupId = "s12-sol-inventory")
        public void onOrder(OrderCreated order,
                            @Header(KafkaHeaders.OFFSET) long offset) {
            Timer.Sample sample = Timer.start(registry);
            String outcome = "ok";
            try {
                deduct(order);
            } catch (RuntimeException ex) {
                outcome = "error";
                throw ex;                       // ★ 반드시 다시 던진다
            } finally {
                sample.stop(registry.timer("order.inventory.deduct", "outcome", outcome));
            }
            if (offset % 200 == 0) {
                log.debug("재고 차감 orderId={} offset={}", order.orderId(), offset);
            }
        }

        private void deduct(OrderCreated order) {
            try {
                Thread.sleep(20L + (order.quantity() * 3L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (order.orderId().endsWith("77")) {
                throw new IllegalStateException("재고 부족: " + order.orderId());
            }
        }
    }

    /* =======================================================================
     * 정답 4. observation-enabled 4가지 조합
     * =======================================================================
     *
     * ┌──────────┬──────────┬──────────────────┬──────────────────┬─────────────┐
     * │ template │ listener │ HTTP traceId     │ 컨슈머 traceId    │ traceparent │
     * ├──────────┼──────────┼──────────────────┼──────────────────┼─────────────┤
     * │ true     │ true     │ 6f8c2a1b9d4e5f30 │ 6f8c2a1b9d4e5f30 │ 있음  ★연결 │
     * │ true     │ false    │ 6f8c2a1b9d4e5f30 │ (비어 있음)       │ 있음        │
     * │ false    │ true     │ 6f8c2a1b9d4e5f30 │ d41d8cd98f00b204 │ 없음        │
     * │ false    │ false    │ 6f8c2a1b9d4e5f30 │ (비어 있음)       │ 없음        │
     * └──────────┴──────────┴──────────────────┴──────────────────┴─────────────┘
     *
     * 왜 이런가
     *
     * ① 둘 다 true 일 때만 이어집니다. 프로듀서가 traceparent 를 "심고",
     *    컨슈머가 그것을 "읽어야" 하나의 trace 가 됩니다. 한쪽만으로는 안 됩니다.
     *
     * ② template=true / listener=false 는 이 문제의 핵심 함정입니다.
     *    헤더는 멀쩡히 심겼는데 읽는 쪽이 없어서 컨슈머 로그의 traceId 가
     *    아예 비어 있습니다. 즉 "헤더 유무" 와 "trace 연결" 은 별개입니다.
     *    헤더만 보고 "심겼으니 됐다" 고 판단하면 안 됩니다.
     *
     * ③ template=false / listener=true 는 컨슈머가 새 trace 를 시작합니다.
     *    traceId 가 비어 있지 않으므로 로그만 보면 정상처럼 보입니다.
     *    HTTP 요청의 traceId 와 대조해야만 끊긴 것을 알 수 있습니다.
     *    이것이 가장 발견하기 어려운 조합입니다.
     *
     * ④ 헤더 확인 명령
     *    kcc --topic orders --partition 1 --offset 318 --max-messages 1 \
     *        --property print.headers=true
     *    → traceparent:00-6f8c2a1b9d4e5f306f8c2a1b9d4e5f30-9c7d1e2f3a4b5c60-01
     *    끝의 -01 은 "샘플링됨" 입니다. -00 이면 수집기가 버립니다.
     *    management.tracing.sampling.probability 를 0.1 로 두면 -00 이 90% 입니다.
     *
     * ⑤ 설정을 켜기 전에 발행된 메시지에는 헤더가 없습니다. 랙이 큰 상태에서
     *    배포하면 한동안 끊긴 trace 가 대량으로 섞이는데, 장애가 아닙니다.
     * ===================================================================== */

    /* =======================================================================
     * 정답 5. 리스너 컨테이너 HealthIndicator
     * =======================================================================
     *
     * 왜 이 답인가
     *
     * ① isRunning() 만으로는 부족합니다.
     *    컨테이너는 running=true 인데 getAssignedPartitions() 가 비어 있는
     *    상태가 실제로 존재합니다. concurrency 를 파티션 수보다 크게 잡은 경우
     *    (Step 03), 또는 리밸런스로 파티션을 전부 뺏긴 "좀비" 컨테이너입니다.
     *    이 컨테이너는 살아 있지만 한 건도 처리하지 않습니다.
     *    그래서 running && !assigned.isEmpty() 두 조건을 && 로 묶습니다.
     *
     * ② getAssignedPartitions() 는 null 을 반환할 수 있습니다.
     *    MessageListenerContainer 의 기본 구현이 null 입니다. NPE 를 막으려면
     *    null 검사가 필요합니다. HealthIndicator 안에서 예외가 나면 Actuator 가
     *    500 을 내려 "헬스체크 자체가 장애" 가 됩니다.
     *
     * ③ 빈 이름이 곧 엔드포인트 경로입니다.
     *    @Component("exKafkaListeners") → /actuator/health/exKafkaListeners
     *    Boot 는 빈 이름에서 "HealthIndicator" 접미사만 떼고 그대로 씁니다.
     *
     * ④ 실측
     *      정상 시   /actuator/health/kafka = UP    /exKafkaListeners = UP
     *      정지 후   /actuator/health/kafka = UP ★  /exKafkaListeners = DOWN
     *    브로커는 멀쩡하니 KafkaHealthIndicator 는 끝까지 UP 입니다.
     *    이 UP 을 readiness probe 로 쓰면 아무 일도 안 하는 파드가 정상 판정을
     *    받고 계속 떠 있습니다. 그동안 랙 지표는 12-4 때문에 사라져 있습니다.
     *
     * ⑤ 알람은 연속 N회 DOWN 일 때만 울리세요.
     *    리밸런스가 진행되는 수 초 동안 assignedPartitions 가 비므로,
     *    단발 DOWN 으로 알람을 걸면 배포할 때마다 울립니다.
     *
     * ⑥ liveness 가 아니라 readiness 에 넣습니다.
     *    liveness 로 걸면 컨테이너 정지 → 파드 재시작 → 리밸런스 → 또 정지의
     *    루프가 생길 수 있습니다. 컨슈머는 HTTP 트래픽을 안 받으므로 readiness
     *    DOWN 의 실질 효과는 "알람" 이고, 그것으로 충분합니다.
     * ===================================================================== */
    @Component("exKafkaListeners")
    @Profile("step12-sol")
    public static class Sol5HealthIndicator implements HealthIndicator {

        private final KafkaListenerEndpointRegistry registry;

        public Sol5HealthIndicator(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Health health() {
            Collection<MessageListenerContainer> containers = registry.getListenerContainers();
            if (containers.isEmpty()) {
                return Health.down().withDetail("reason", "등록된 리스너 컨테이너가 없음").build();
            }

            Map<String, Object> details = new LinkedHashMap<>();
            boolean healthy = true;

            for (MessageListenerContainer c : containers) {
                Collection<TopicPartition> assigned = c.getAssignedPartitions();
                boolean running = c.isRunning();
                boolean hasPartitions = assigned != null && !assigned.isEmpty();
                healthy = healthy && running && hasPartitions;

                details.put(c.getListenerId(), Map.of(
                        "running", running,
                        "assignedPartitions",
                        assigned == null ? List.of() : assigned.stream().map(TopicPartition::toString).toList()));
            }
            return (healthy ? Health.up() : Health.down()).withDetails(details).build();
        }
    }

    /* =======================================================================
     * 정답 6. RecordInterceptor 로 MDC 주입 — 그리고 remove 를 빼면 생기는 일
     * =======================================================================
     *
     * 왜 이 답인가
     *
     * ① intercept 는 리스너 호출 "직전" 에, afterRecord 는 "직후" 에 불립니다.
     *    afterRecord 는 리스너가 성공했든 예외를 던졌든 항상 호출됩니다.
     *    그래서 MDC 제거는 여기가 유일하게 안전한 자리입니다.
     *    리스너 메서드의 finally 에 넣으면, 역직렬화 단계에서 실패해 리스너가
     *    아예 호출되지 않은 경우를 못 잡습니다.
     *
     * ② 리스너 코드를 한 줄도 안 고쳐도 됩니다. 이것이 인터셉터를 쓰는 이유입니다.
     *    로그 규약을 조직 전체에 강제하려면, 각 리스너에 로깅 코드를 넣게 하는
     *    대신 공용 컨테이너 팩토리에 인터셉터 하나를 다는 것이 확실합니다.
     *
     * ③ MDC.remove 를 뺀 상태의 실측 (ORD-0311 에서 예외)
     *
     *    WARN  [order-service,...] [orders-1@311] ... : 재고 차감 실패 orderId=ORD-0311
     *    ERROR [order-service,...] [orders-1@311] ... o.s.k.l.DefaultErrorHandler : Record in retry
     *    INFO  [order-service,...] [orders-1@311] ... : 재고 차감 orderId=ORD-0312   ★ 어긋남
     *
     *    마지막 줄의 실제 레코드는 orders-1@312 인데 로그에는 311 이 찍혔습니다.
     *    ORD-0312 를 조사하려고 orders-1@311 을 꺼내 보면 엉뚱한 메시지가 나옵니다.
     *
     *    ⚠️ 이것이 이 문제의 결론입니다. 틀린 관측 정보는 없는 것보다 나쁩니다.
     *       관측 정보가 없으면 다른 방법을 찾지만, 틀린 정보는 잘못된 방향으로
     *       사람을 몇 시간씩 끌고 갑니다.
     *
     * ④ 스레드가 재사용되기 때문에 생기는 문제입니다. 리스너 스레드
     *    ntainer#0-1-C-1 은 파티션 1의 모든 레코드를 순차 처리합니다.
     *    MDC 는 ThreadLocal 이므로 지우지 않으면 다음 레코드까지 살아남습니다.
     *
     * ⑤ 배치 리스너를 쓴다면 RecordInterceptor 대신 BatchInterceptor 를 씁니다.
     *    이때 MDC 값은 배치 전체의 범위로 넣는 것이 정확합니다.
     *      "orders-1@300..349"
     *    배치 안의 개별 레코드를 특정해야 한다면 리스너 안에서 루프를 돌며
     *    직접 MDC 를 갱신해야 합니다.
     *
     * ⑥ traceId 와 함께 쓰는 것이 최종형입니다.
     *    logging.pattern.level:
     *      "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}] [%X{kafka:-}]"
     *    → INFO [order-service,6f8c2a1b9d4e5f30,b2c3d4e5f6a70819] [orders-1@311]
     *    trace 로 흐름을 보고, 좌표로 원본 메시지를 꺼냅니다. 이 둘이면 됩니다.
     * ===================================================================== */
    public static class Sol6MdcInterceptor implements RecordInterceptor<Object, Object> {

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                        Consumer<Object, Object> consumer) {
            MDC.put("kafka", record.topic() + "-" + record.partition() + "@" + record.offset());
            MDC.put("kafkaKey", String.valueOf(record.key()));
            return record;
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            // ★ 성공/실패와 무관하게 호출된다. 여기가 유일하게 안전한 제거 위치.
            MDC.remove("kafka");
            MDC.remove("kafkaKey");
        }
    }

    @Configuration
    @Profile("step12-sol")
    public static class Sol6Config {

        @Bean("solMdcFactory")
        public ConcurrentKafkaListenerContainerFactory<Object, Object> solMdcFactory(ConsumerFactory<?, ?> cf) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            @SuppressWarnings("unchecked")
            ConsumerFactory<Object, Object> typed = (ConsumerFactory<Object, Object>) cf;
            factory.setConsumerFactory(typed);
            factory.setConcurrency(3);
            factory.setRecordInterceptor(new Sol6MdcInterceptor());
            return factory;
        }
    }

    @Component
    @Profile("step12-sol")
    public static class Sol6Listener {

        private static final Logger log = LoggerFactory.getLogger(Sol6Listener.class);

        @KafkaListener(id = "s12-sol-mdc", topics = "orders",
                       groupId = "s12-sol-mdc", containerFactory = "solMdcFactory")
        public void onOrder(OrderCreated order) {
            if (order.orderId().endsWith("11")) {
                log.warn("재고 차감 실패 orderId={}", order.orderId());
                throw new IllegalStateException("재고 부족: " + order.orderId());
            }
            log.info("재고 차감 orderId={}", order.orderId());
        }
    }
}
