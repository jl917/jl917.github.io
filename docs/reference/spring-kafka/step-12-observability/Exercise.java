package com.example.order.step12;

/*
 * ============================================================================
 * Step 12 — 관측성과 운영 : Exercise (6문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step12/Exercise.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step12,step12-ex'
 *
 * ★ 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s12-ex-inventory --topic orders --reset-offsets --to-earliest --execute
 *
 * ★ 이 스텝은 "코드를 쓰는 문제" 보다 "curl 을 쳐서 출력을 기록하는 문제" 가 많습니다.
 *   // 관측 기록: 주석의 표는 손으로 지어내지 말고 실제 출력을 붙여 넣으세요.
 *
 * 두드릴 엔드포인트
 *   curl -s localhost:8080/actuator/metrics | jq -r '.names[]' | grep '^kafka'
 *   curl -s localhost:8080/actuator/prometheus | grep -E 'records_lag|group_lag'
 *   curl -s localhost:8080/actuator/metrics/spring.kafka.listener | jq
 *   curl -s localhost:8080/actuator/health/kafkaListeners | jq
 *   curl -X POST localhost:8080/admin/listeners/stop
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

public final class Exercise {

    private Exercise() { }

    /* =======================================================================
     * 문제 1. /actuator/prometheus 에서 랙 지표 찾기
     * -----------------------------------------------------------------------
     * 요구사항
     *   - 앱을 띄우고 아래 명령으로 랙 관련 시계열을 전부 뽑는다.
     *       curl -s localhost:8080/actuator/prometheus | grep -E '^kafka_consumer_fetch_manager_records_lag'
     *   - kafka_consumer_fetch_manager_records_lag (단수) 와
     *     kafka_consumer_fetch_manager_records_lag_max (max) 의 라벨 차이를 표로 정리한다.
     *   - "파티션 단위로 알람을 걸려면 둘 중 무엇을 써야 하는가" 에 답한다.
     *
     * // 관측 기록: 실제 출력을 붙여 넣으세요
     * // ┌─────────────────────────────────┬──────────────────────────────────┐
     * // │ 지표 이름                        │ 라벨                              │
     * // ├─────────────────────────────────┼──────────────────────────────────┤
     * // │ ..._records_lag                 │                                  │
     * // │ ..._records_lag_max             │                                  │
     * // └─────────────────────────────────┴──────────────────────────────────┘
     * //
     * // 파티션 단위 알람에 써야 할 지표:
     * // 그렇게 판단한 이유:
     *
     * 확인: records_lag 쪽에만 topic= / partition= 라벨이 보여야 합니다.
     * ===================================================================== */

    /* =======================================================================
     * 문제 2. AdminClient 로 랙 게이지 등록하기  ★핵심★
     * -----------------------------------------------------------------------
     * 요구사항
     *   - 그룹 "s12-ex-inventory" 의 파티션별 랙을 MultiGauge 로 등록한다.
     *   - 지표 이름은 "kafka.consumer.group.lag", 태그는 group/topic/partition.
     *   - AdminClient.listConsumerGroupOffsets 로 커밋 오프셋을,
     *     probe 컨슈머의 endOffsets 로 마지막 오프셋을 가져와 뺀다.
     *   - @Scheduled(fixedDelay = 10_000) 로 갱신한다.
     *   - 등록 후 curl -X POST localhost:8080/admin/listeners/stop 으로 리스너를 멈추고,
     *     30초 뒤에도 값이 "계속 올라가는지" 확인한다.
     *
     * ⚠️ probe 컨슈머의 groupId 는 반드시 실제 그룹과 다르게 줄 것.
     *    같은 그룹으로 subscribe 하면 리밸런스가 발생합니다.
     *
     * // 실험: lagGauge.register(rows, false) 로 바꿔서 한 번 더 돌려 보세요.
     * //       값이 어떻게 되는지, 에러가 나는지 기록하세요.
     * // 관측 기록:
     *
     * 확인: curl -s localhost:8080/actuator/prometheus | grep '^kafka_consumer_group_lag'
     *       kafka_consumer_group_lag{...,partition="1",topic="orders",} 402.0
     * ===================================================================== */
    @Component
    @Profile("step12-ex")
    public static class Ex2LagMetrics {

        private static final Logger log = LoggerFactory.getLogger(Ex2LagMetrics.class);
        private static final String GROUP = "s12-ex-inventory";

        private final AdminClient admin;
        private final Consumer<?, ?> probe;
        private final MultiGauge lagGauge;

        public Ex2LagMetrics(KafkaAdmin kafkaAdmin,
                             ConsumerFactory<?, ?> consumerFactory,
                             MeterRegistry registry) {
            this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
            this.probe = consumerFactory.createConsumer("s12-ex-lag-probe", "-probe");

            // 여기에 작성: MultiGauge 를 만들어 lagGauge 에 대입하세요.
            //   MultiGauge.builder("kafka.consumer.group.lag")
            //             .description(...).baseUnit("records").register(registry)
            this.lagGauge = null;
        }

        @Scheduled(fixedDelay = 10_000L, initialDelay = 5_000L)
        public void refresh() {
            try {
                Map<TopicPartition, OffsetAndMetadata> committed =
                        admin.listConsumerGroupOffsets(GROUP)
                                .partitionsToOffsetAndMetadata()
                                .get(5, TimeUnit.SECONDS);

                if (committed.isEmpty()) {
                    return;
                }

                // 여기에 작성:
                //   ① probe.endOffsets(committed.keySet()) 로 마지막 오프셋을 가져오고
                //   ② 파티션마다 lag 을 계산해 MultiGauge.Row 를 만들고
                //   ③ lagGauge.register(rows, true) 로 등록하고
                //   ④ 총합을 log.info 로 남긴다

                List<MultiGauge.Row<?>> rows = new ArrayList<>();
                log.info("group={} rows={}", GROUP, rows.size());

            } catch (Exception ex) {
                log.warn("랙 수집 실패: {}", ex.toString());
            }
        }

        @PreDestroy
        public void close() {
            probe.close();
            admin.close();
        }
    }

    /* =======================================================================
     * 문제 3. 리스너 처리 시간 타이머 추가하기
     * -----------------------------------------------------------------------
     * 요구사항
     *   - 아래 리스너의 비즈니스 구간(deduct 호출)만 Timer.Sample 로 측정한다.
     *   - 지표 이름은 "order.inventory.deduct", 태그는 outcome=ok|error.
     *   - 예외가 나도 타이머가 기록되도록 finally 에서 stop 한다.
     *   - 그런 다음 아래 두 값을 비교해 프레임워크 오버헤드를 계산한다.
     *       curl -s localhost:8080/actuator/metrics/spring.kafka.listener | jq '.measurements'
     *       curl -s localhost:8080/actuator/metrics/order.inventory.deduct | jq '.measurements'
     *
     * // 관측 기록:
     * // spring.kafka.listener      COUNT=____  TOTAL_TIME=____  MAX=____
     * // order.inventory.deduct     COUNT=____  TOTAL_TIME=____  MAX=____
     * // 프레임워크 오버헤드(초)    = ____
     * // 두 COUNT 가 다르다면 그 이유는?
     *
     * 확인: order.inventory.deduct 의 availableTags 에 outcome 이 보여야 합니다.
     * ===================================================================== */
    @Component
    @Profile("step12-ex")
    public static class Ex3TimedListener {

        private static final Logger log = LoggerFactory.getLogger(Ex3TimedListener.class);

        private final MeterRegistry registry;

        public Ex3TimedListener(MeterRegistry registry) {
            this.registry = registry;
        }

        @KafkaListener(id = "s12-ex-inventory", topics = "orders", groupId = "s12-ex-inventory")
        public void onOrder(OrderCreated order,
                            @Header(KafkaHeaders.OFFSET) long offset) {
            // 여기에 작성: Timer.Sample 로 deduct(order) 구간만 측정하세요.
            deduct(order);

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
     * 문제 4. observation 을 켜고 trace 연결 확인하기
     * -----------------------------------------------------------------------
     * 요구사항
     *   - application-step12-ex.yml 의 아래 두 값을 바꿔 가며 4가지 조합을 시험한다.
     *       spring.kafka.template.observation-enabled
     *       spring.kafka.listener.observation-enabled
     *   - 조합마다 POST /orders?seq=7 을 치고 로그의 traceId 를 기록한다.
     *   - 조합마다 kcc 로 레코드 헤더에 traceparent 가 있는지 확인한다.
     *       kcc --topic orders --partition 1 --offset <오프셋> --max-messages 1 --property print.headers=true
     *
     * // 관측 기록:
     * // ┌──────────┬──────────┬───────────────┬───────────────┬────────────────┐
     * // │ template │ listener │ HTTP traceId  │ 컨슈머 traceId │ traceparent 헤더│
     * // ├──────────┼──────────┼───────────────┼───────────────┼────────────────┤
     * // │ true     │ true     │               │               │                │
     * // │ true     │ false    │               │               │                │
     * // │ false    │ true     │               │               │                │
     * // │ false    │ false    │               │               │                │
     * // └──────────┴──────────┴───────────────┴───────────────┴────────────────┘
     * //
     * // trace 가 하나로 이어진 조합은?
     * // "헤더는 있는데 trace 가 안 이어진" 조합이 있었는가? 왜인가?
     *
     * 확인: 둘 다 true 일 때만 HTTP 와 컨슈머의 traceId 가 같아야 합니다.
     * ===================================================================== */

    /* =======================================================================
     * 문제 5. 커스텀 HealthIndicator 로 컨테이너 상태 노출하기
     * -----------------------------------------------------------------------
     * 요구사항
     *   - 빈 이름을 "exKafkaListeners" 로 두어 /actuator/health/exKafkaListeners 로 노출한다.
     *   - KafkaListenerEndpointRegistry.getListenerContainers() 를 순회하며
     *     isRunning() 과 getAssignedPartitions() 를 details 에 담는다.
     *   - 하나라도 running=false 이거나 할당 파티션이 비어 있으면 DOWN.
     *   - 컨테이너가 하나도 등록되지 않았다면 reason 을 담아 DOWN.
     *   - 만든 뒤 POST /admin/listeners/stop 을 치고 DOWN 으로 바뀌는지 확인한다.
     *   - 같은 시각 /actuator/health/kafka 는 무엇인지도 함께 기록한다.
     *
     * // 관측 기록:
     * // 정상 시   /actuator/health/kafka = ____   /actuator/health/exKafkaListeners = ____
     * // 정지 후   /actuator/health/kafka = ____   /actuator/health/exKafkaListeners = ____
     *
     * 확인: 정지 후 kafka 는 UP, exKafkaListeners 는 DOWN 이어야 합니다.
     * ===================================================================== */
    @Component("exKafkaListeners")
    @Profile("step12-ex")
    public static class Ex5HealthIndicator implements HealthIndicator {

        private final KafkaListenerEndpointRegistry registry;

        public Ex5HealthIndicator(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Health health() {
            Collection<MessageListenerContainer> containers = registry.getListenerContainers();
            Map<String, Object> details = new LinkedHashMap<>();

            // 여기에 작성:
            //   ① containers 가 비었으면 down + reason
            //   ② 순회하며 running / assignedPartitions 를 details 에 담고
            //   ③ 하나라도 비정상이면 DOWN

            return Health.unknown().withDetails(details).build();
        }
    }

    /* =======================================================================
     * 문제 6. 리스너 로그에 partition@offset MDC 추가하기 (그리고 일부러 망가뜨리기)
     * -----------------------------------------------------------------------
     * 요구사항
     *   - RecordInterceptor 를 구현해 intercept 에서 MDC 에
     *       "kafka" = topic-partition@offset
     *     를 넣는다.
     *   - 전용 컨테이너 팩토리 "exMdcFactory" 에 붙이고, 아래 리스너에서 쓴다.
     *   - logging.pattern.level 에 [%X{kafka:-}] 를 추가해 로그를 확인한다.
     *   - ★ 그다음 afterRecord 의 MDC.remove 를 "빼고" 다시 돌린다.
     *     ORD-xx11 에서 예외가 나므로, 그 다음 레코드의 로그에
     *     이전 오프셋이 그대로 남는 것을 관측한다.
     *   - 관측이 끝나면 반드시 MDC.remove 를 원복한다.
     *
     * // 관측 기록 (remove 를 뺀 상태):
     * // 예외가 난 레코드의 실제 좌표    : orders-__@____
     * // 그 다음 레코드의 실제 좌표      : orders-__@____
     * // 그 다음 레코드 로그에 찍힌 좌표 : orders-__@____   ← 어긋났는가?
     *
     * 확인: remove 를 넣은 상태에서는 모든 로그의 [orders-N@M] 이
     *       그 로그를 만든 레코드의 좌표와 정확히 일치해야 합니다.
     * ===================================================================== */
    public static class Ex6MdcInterceptor implements RecordInterceptor<Object, Object> {

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                        Consumer<Object, Object> consumer) {
            // 여기에 작성: MDC.put("kafka", ...)
            return record;
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            // 여기에 작성: MDC.remove("kafka")
            // ★ 실험할 때는 이 줄을 주석 처리하세요.
        }
    }

    @Configuration
    @Profile("step12-ex")
    public static class Ex6Config {

        @Bean("exMdcFactory")
        public ConcurrentKafkaListenerContainerFactory<Object, Object> exMdcFactory(ConsumerFactory<?, ?> cf) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            @SuppressWarnings("unchecked")
            ConsumerFactory<Object, Object> typed = (ConsumerFactory<Object, Object>) cf;
            factory.setConsumerFactory(typed);
            factory.setConcurrency(3);

            // 여기에 작성: factory.setRecordInterceptor(...)

            return factory;
        }
    }

    @Component
    @Profile("step12-ex")
    public static class Ex6Listener {

        private static final Logger log = LoggerFactory.getLogger(Ex6Listener.class);

        @KafkaListener(id = "s12-ex-mdc", topics = "orders",
                       groupId = "s12-ex-mdc", containerFactory = "exMdcFactory")
        public void onOrder(OrderCreated order) {
            if (order.orderId().endsWith("11")) {
                log.warn("재고 차감 실패 orderId={}", order.orderId());
                throw new IllegalStateException("재고 부족: " + order.orderId());
            }
            log.info("재고 차감 orderId={}", order.orderId());
        }
    }

    /** 문제 2 에서 쓰는 태그 헬퍼. 그대로 두세요. */
    static Tags lagTags(String group, TopicPartition tp) {
        return Tags.of("group", group, "topic", tp.topic(), "partition", String.valueOf(tp.partition()));
    }

    /** 문제 3 에서 쓰는 타이머 헬퍼. 그대로 두세요. */
    static Timer deductTimer(MeterRegistry registry, String outcome) {
        return registry.timer("order.inventory.deduct", "outcome", outcome);
    }
}
