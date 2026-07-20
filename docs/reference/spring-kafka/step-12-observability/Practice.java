package com.example.order.step12;

/*
 * ============================================================================
 * Step 12 — 관측성과 운영 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step12/Practice.java
 *
 * 실행
 *   이 스텝의 예제들은 서로 간섭하지 않습니다. 전부 함께 켜도 됩니다.
 *
 *   ./gradlew bootRun --args='--spring.profiles.active=step12,step12-lag,step12-trace,step12-mdc,step12-health'
 *
 *   개별로 보고 싶다면:
 *     step12-lag     → [12-4][12-5] AdminClient 랙 게이지 + 컨테이너 stop/start 엔드포인트
 *     step12-trace   → [12-7][12-8] observation 으로 trace 연결. POST /orders?seq=7
 *     step12-mdc     → [12-9]       RecordInterceptor 로 partition@offset 을 MDC 에
 *     step12-health  → [12-10]      리스너 컨테이너 상태 HealthIndicator
 *
 * ★ 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s12-inventory --topic orders --reset-offsets --to-earliest --execute
 *
 * ★ application-step12-trace.yml 에 아래 두 줄이 반드시 있어야 합니다.
 *   spring.kafka.template.observation-enabled: true
 *   spring.kafka.listener.observation-enabled: true
 *   template 쪽을 빼면 12-8 의 "끊긴 trace" 가 재현됩니다.
 *
 * ★ logging.pattern.level 에 MDC 키를 넣어야 12-9 가 보입니다.
 *   logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}] [%X{kafka:-}]"
 *
 * 실행 중에 두드릴 것
 *   curl -s localhost:8080/actuator/metrics | jq -r '.names[]' | grep '^kafka'
 *   curl -s localhost:8080/actuator/metrics/kafka.consumer.group.lag | jq
 *   curl -s localhost:8080/actuator/metrics/spring.kafka.listener | jq
 *   curl -s localhost:8080/actuator/prometheus | grep '^kafka_consumer_group_lag'
 *   curl -s localhost:8080/actuator/health/kafkaListeners | jq
 *   curl -X POST localhost:8080/admin/listeners/stop      ← ★ 12-4 의 핵심 실험
 *   curl -X POST localhost:8080/admin/listeners/start
 *
 * 실행 중에 확인할 CLI
 *   kcg --describe --group s12-inventory
 *   kcg --describe --group s12-inventory --state
 *   kcc --topic orders --partition 1 --offset 318 --max-messages 1 --property print.headers=true
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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Practice {

    private Practice() { }

    /* =======================================================================
     * [12-0] 공통 — 스케줄러 활성화
     * -----------------------------------------------------------------------
     * 12-5 의 랙 게이지가 @Scheduled 로 갱신되므로 반드시 필요합니다.
     * 빠뜨리면 게이지가 등록조차 되지 않고, 에러도 나지 않습니다.
     * ===================================================================== */
    @Configuration
    @Profile("step12")
    @EnableScheduling
    public static class SchedulingConfig {
    }

    /* =======================================================================
     * [12-0] 부하 생성기 — 랙을 벌리려면 계속 발행해야 한다
     * -----------------------------------------------------------------------
     * 초당 5건씩 orders 로 발행합니다. app.load.enabled=false 로 끕니다.
     * 12-4 의 실험(리스너를 멈추면 랙이 무한히 커진다)은 이게 떠 있어야 성립합니다.
     * ===================================================================== */
    @Component
    @Profile("step12")
    public static class LoadGenerator {

        private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

        private final KafkaTemplate<String, Object> template;
        private final AtomicInteger seq = new AtomicInteger(1);
        private final boolean enabled;

        public LoadGenerator(KafkaTemplate<String, Object> template,
                             @org.springframework.beans.factory.annotation.Value("${app.load.enabled:true}") boolean enabled) {
            this.template = template;
            this.enabled = enabled;
        }

        @Scheduled(fixedDelay = 200L, initialDelay = 3_000L)
        public void publish() {
            if (!enabled) {
                return;
            }
            OrderCreated order = OrderCreated.of(seq.getAndIncrement());
            template.send("orders", order.orderId(), order);
            if (order.orderId().endsWith("00")) {
                log.info("부하 생성 진행 orderId={}", order.orderId());
            }
        }
    }

    /* =======================================================================
     * [12-4] 리스너 컨테이너 stop/start 엔드포인트
     * -----------------------------------------------------------------------
     * "브로커는 살아 있는데 컨슈머만 죽은 상태" 를 안전하게 재현하는 장치입니다.
     * Step 10 의 KafkaListenerEndpointRegistry 를 그대로 씁니다.
     *
     *   curl -X POST localhost:8080/admin/listeners/stop
     *   curl -s     localhost:8080/actuator/prometheus | grep -c records_lag   → 0  ★
     *   curl -s     localhost:8080/actuator/prometheus | grep -c group_lag     → 3  ★
     *
     * 이 두 줄의 대비가 12-4 → 12-5 의 전부입니다.
     * ===================================================================== */
    @RestController
    @Profile("step12-lag")
    public static class ListenerAdminController {

        private static final Logger log = LoggerFactory.getLogger(ListenerAdminController.class);

        private final KafkaListenerEndpointRegistry registry;

        public ListenerAdminController(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @PostMapping("/admin/listeners/stop")
        public String stop() {
            registry.getListenerContainers().forEach(MessageListenerContainer::stop);
            log.warn("모든 리스너 컨테이너를 정지했습니다. 이제 랙 지표가 어떻게 되는지 보세요.");
            return "stopped";
        }

        @PostMapping("/admin/listeners/start")
        public String start() {
            registry.getListenerContainers().forEach(MessageListenerContainer::start);
            log.info("모든 리스너 컨테이너를 재시작했습니다.");
            return "started";
        }

        @GetMapping(value = "/admin/listeners/status", produces = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, Object> status() {
            Map<String, Object> result = new LinkedHashMap<>();
            for (MessageListenerContainer c : registry.getListenerContainers()) {
                Collection<TopicPartition> assigned = c.getAssignedPartitions();
                result.put(c.getListenerId(), Map.of(
                        "running", c.isRunning(),
                        "assigned", assigned == null ? List.of() : assigned.stream().map(TopicPartition::toString).toList()));
            }
            return result;
        }
    }

    /* =======================================================================
     * [12-5] ★핵심★ AdminClient 로 컨슈머 랙을 직접 노출한다
     * -----------------------------------------------------------------------
     * 클라이언트 지표(kafka.consumer.fetch.manager.records.lag)는 컨슈머가 죽으면
     * 함께 사라집니다. 랙이 가장 위험할 때 지표가 없어지는 것입니다(12-4).
     *
     * 여기서는 브로커에 직접 물어봅니다.
     *   ① AdminClient.listConsumerGroupOffsets(group)  → 그룹이 "커밋한" 오프셋
     *   ② KafkaConsumer.endOffsets(partitions)         → 토픽의 마지막 오프셋
     *   ③ lag = ② - ①
     * 이는 kafka-consumer-groups.sh --describe 가 하는 계산과 정확히 같습니다.
     *
     * ⚠️ probe 컨슈머는 절대 subscribe/assign 하지 않습니다.
     *    실제 그룹 ID 로 subscribe 하면 측정용 컨슈머가 그룹에 합류해
     *    리밸런스를 일으키고 파티션을 빼앗아 갑니다.
     * ===================================================================== */
    @Component
    @Profile("step12-lag")
    public static class ConsumerGroupLagMetrics {

        private static final Logger log = LoggerFactory.getLogger(ConsumerGroupLagMetrics.class);
        private static final String GROUP = "s12-inventory";

        private final AdminClient admin;
        private final Consumer<?, ?> probe;
        private final MultiGauge lagGauge;

        public ConsumerGroupLagMetrics(KafkaAdmin kafkaAdmin,
                                       ConsumerFactory<?, ?> consumerFactory,
                                       MeterRegistry registry) {
            this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
            // groupId 를 실제 그룹과 다르게 준다. endOffsets 만 쓸 것이므로 그룹 합류는 일어나지 않는다.
            this.probe = consumerFactory.createConsumer("s12-lag-probe", "-probe");
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
                    // 그룹이 아직 한 번도 커밋하지 않았다. 남아 있는 행을 비운다.
                    lagGauge.register(List.of(), true);
                    log.info("group={} 커밋된 오프셋이 없습니다", GROUP);
                    return;
                }

                Map<TopicPartition, Long> endOffsets = probe.endOffsets(committed.keySet());

                List<MultiGauge.Row<?>> rows = new ArrayList<>();
                long total = 0L;
                for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                    TopicPartition tp = e.getKey();
                    long end = endOffsets.getOrDefault(tp, 0L);
                    long lag = Math.max(0L, end - e.getValue().offset());
                    total += lag;
                    rows.add(MultiGauge.Row.of(
                            Tags.of("group", GROUP,
                                    "topic", tp.topic(),
                                    "partition", String.valueOf(tp.partition())),
                            lag));
                }
                // overwrite=true 가 핵심. false 로 두면 값이 첫 측정치에서 얼어붙는다.
                lagGauge.register(rows, true);
                log.info("group={} totalLag={} partitions={}", GROUP, total, rows.size());

            } catch (Exception ex) {
                // 랙 수집 실패가 애플리케이션을 죽여서는 안 된다. 로그만 남기고 다음 주기를 기다린다.
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
     * [12-6] 리스너 처리 시간 측정
     * -----------------------------------------------------------------------
     * ContainerProperties.setMicrometerEnabled(true) 는 기본값 true 이므로
     * spring.kafka.listener 타이머는 아무것도 안 해도 생깁니다.
     * 다만 그 타이머는 "리스너 메서드 호출 구간" 전체를 잽니다.
     * 비즈니스 구간만 따로 재려면 Timer.Sample 을 직접 씁니다.
     *
     * ⚠️ @Timed 는 TimedAspect 빈이 없으면 조용히 아무 지표도 만들지 않습니다.
     *    리스너 안에서는 Timer.Sample 이 확실합니다.
     * ===================================================================== */
    @Component
    @Profile("step12-lag")
    public static class TimedInventoryListener {

        private static final Logger log = LoggerFactory.getLogger(TimedInventoryListener.class);

        private final MeterRegistry registry;

        public TimedInventoryListener(MeterRegistry registry) {
            this.registry = registry;
        }

        @KafkaListener(id = "s12-inventory", topics = "orders", groupId = "s12-inventory")
        public void onOrder(OrderCreated order,
                            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                            @Header(KafkaHeaders.OFFSET) long offset) {
            Timer.Sample sample = Timer.start(registry);
            String outcome = "ok";
            try {
                deduct(order);
            } catch (RuntimeException ex) {
                outcome = "error";
                throw ex;
            } finally {
                sample.stop(registry.timer("order.inventory.deduct", "outcome", outcome));
            }
            if (offset % 100 == 0) {
                log.debug("재고 차감 orderId={} orders-{}@{}", order.orderId(), partition, offset);
            }
        }

        /** 처리 시간을 눈에 보이게 하려고 일부러 느리게 만든 구간입니다. */
        private void deduct(OrderCreated order) {
            try {
                Thread.sleep(20L + (order.quantity() * 3L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* =======================================================================
     * [12-7] observation 으로 분산 추적 — HTTP → 프로듀서 → 컨슈머
     * -----------------------------------------------------------------------
     *   curl -X POST 'localhost:8080/orders?seq=7'
     *
     * 세 줄의 로그에서 대괄호 안 가운데 값(traceId)이 같아야 성공입니다.
     *   INFO [order-service,6f8c2a1b9d4e5f30,3a1b2c4d5e6f7081] ... HTTP 요청 수신
     *   INFO [order-service,6f8c2a1b9d4e5f30,9c7d1e2f3a4b5c60] ... sent orders-1@318
     *   INFO [order-service,6f8c2a1b9d4e5f30,b2c3d4e5f6a70819] ... 재고 차감
     *
     * traceId 가 비어 있거나(=[order-service,,]) 다르면 12-8 의 함정입니다.
     * template.observation-enabled 가 켜져 있는지 확인하세요.
     * ===================================================================== */
    @RestController
    @Profile("step12-trace")
    public static class TracedProducer {

        private static final Logger log = LoggerFactory.getLogger(TracedProducer.class);

        private final KafkaTemplate<String, Object> template;

        public TracedProducer(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @PostMapping("/orders")
        public String create(@RequestParam int seq) {
            OrderCreated order = OrderCreated.of(seq);
            log.info("HTTP 요청 수신 orderId={}", order.orderId());
            template.send("orders", order.orderId(), order)
                    .thenAccept(result -> log.info("sent {}-{}@{} orderId={}",
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(),
                            order.orderId()));
            return order.orderId();
        }
    }

    @Component
    @Profile("step12-trace")
    public static class TracedListener {

        private static final Logger log = LoggerFactory.getLogger(TracedListener.class);

        @KafkaListener(id = "s12-trace-inventory", topics = "orders", groupId = "s12-trace-inventory")
        public void onOrder(OrderCreated order,
                            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                            @Header(KafkaHeaders.OFFSET) long offset) {
            log.info("재고 차감 orderId={} orders-{}@{}", order.orderId(), partition, offset);
        }
    }

    /* =======================================================================
     * [12-9] 로그에 topic-partition@offset 남기기
     * -----------------------------------------------------------------------
     * RecordInterceptor 로 MDC 에 넣으면 리스너 코드를 한 줄도 안 고쳐도 됩니다.
     *
     * ⚠️ afterRecord 의 MDC.remove 를 빼면 스레드가 재사용되면서
     *    직전 레코드의 오프셋이 다음 로그에 그대로 남습니다.
     *    로그는 orders-1@311 을 가리키는데 실제 문제는 orders-1@312 인 상황이 됩니다.
     *    ★ 아래 REMOVE_MDC 를 false 로 바꿔 직접 재현해 보세요. ★
     * ===================================================================== */
    public static class MdcRecordInterceptor implements RecordInterceptor<Object, Object> {

        /** ★ 실험용 스위치. false 로 두면 12-9 의 함정이 재현됩니다. */
        public static final boolean REMOVE_MDC = true;

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                        Consumer<Object, Object> consumer) {
            MDC.put("kafka", record.topic() + "-" + record.partition() + "@" + record.offset());
            MDC.put("kafkaKey", String.valueOf(record.key()));
            return record;
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            if (REMOVE_MDC) {
                MDC.remove("kafka");
                MDC.remove("kafkaKey");
            }
        }
    }

    @Configuration
    @Profile("step12-mdc")
    public static class MdcListenerConfig {

        /**
         * MDC 인터셉터를 붙인 전용 컨테이너 팩토리.
         * @KafkaListener 에서 containerFactory = "mdcFactory" 로 지정해 씁니다.
         */
        @Bean("mdcFactory")
        public ConcurrentKafkaListenerContainerFactory<Object, Object> mdcFactory(ConsumerFactory<?, ?> cf) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            @SuppressWarnings("unchecked")
            ConsumerFactory<Object, Object> typed = (ConsumerFactory<Object, Object>) cf;
            factory.setConsumerFactory(typed);
            factory.setConcurrency(3);
            factory.setRecordInterceptor(new MdcRecordInterceptor());
            return factory;
        }
    }

    @Component
    @Profile("step12-mdc")
    public static class MdcInventoryListener {

        private static final Logger log = LoggerFactory.getLogger(MdcInventoryListener.class);

        @KafkaListener(id = "s12-mdc-inventory", topics = "orders",
                       groupId = "s12-mdc-inventory", containerFactory = "mdcFactory")
        public void onOrder(OrderCreated order) {
            // 로그 본문에 파티션/오프셋을 쓰지 않았는데도 패턴의 %X{kafka} 로 찍힙니다.
            if (order.orderId().endsWith("11")) {
                log.warn("재고 차감 실패 orderId={}", order.orderId());
                throw new IllegalStateException("재고 부족: " + order.orderId());
            }
            log.info("재고 차감 orderId={}", order.orderId());
        }
    }

    /* =======================================================================
     * [12-10] 리스너 컨테이너 상태 HealthIndicator
     * -----------------------------------------------------------------------
     * Boot 의 KafkaHealthIndicator 는 describeCluster() 만 봅니다.
     * 브로커에 붙기만 하면 컨테이너가 전부 죽어 있어도 UP 입니다.
     *
     *   curl -s localhost:8080/actuator/health/kafka          → UP  (컨테이너가 죽어도)
     *   curl -s localhost:8080/actuator/health/kafkaListeners → DOWN ★
     *
     * 빈 이름 "kafkaListeners" 가 그대로 엔드포인트 경로가 됩니다.
     * ===================================================================== */
    @Component("kafkaListeners")
    @Profile("step12-health")
    public static class KafkaListenerHealthIndicator implements HealthIndicator {

        private final KafkaListenerEndpointRegistry registry;

        public KafkaListenerHealthIndicator(KafkaListenerEndpointRegistry registry) {
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
                // running=true 인데 파티션이 하나도 없는 "좀비" 상태도 비정상으로 본다.
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
     * [12-11] 알람용 보조 지표 — DLT 유입 카운터
     * -----------------------------------------------------------------------
     * DLT 는 "0 이 정상" 인 토픽입니다. 한 건이라도 들어오면 사람이 봐야 합니다.
     * 리스너를 하나 붙여 Counter 로 세면 알람 룰이 단순해집니다.
     *   rate(order_dlt_received_total[5m]) > 0
     * ===================================================================== */
    @Component
    @Profile("step12-lag")
    public static class DltCounter {

        private static final Logger log = LoggerFactory.getLogger(DltCounter.class);

        private final MeterRegistry registry;

        public DltCounter(MeterRegistry registry) {
            this.registry = registry;
        }

        @KafkaListener(id = "s12-dlt-monitor", topics = "orders.DLT", groupId = "s12-dlt-monitor")
        public void onDlt(ConsumerRecord<String, Object> record,
                          @Header(name = "kafka_dlt-original-topic", required = false) byte[] originalTopic) {
            String origin = originalTopic == null ? "unknown" : new String(originalTopic);
            registry.counter("order.dlt.received", "originalTopic", origin).increment();
            log.warn("DLT 유입 key={} {}-{}@{}", record.key(), record.topic(), record.partition(), record.offset());
        }
    }

    /* =======================================================================
     * [12-0] 부하 생성기 설정 바인딩용 (선택)
     * ===================================================================== */
    @ConfigurationProperties(prefix = "app.load")
    public static class LoadProperties {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
