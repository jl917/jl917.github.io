package com.example.order.step10;

/*
 * ============================================================================
 * Step 10 — 리스너 컨테이너 제어 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step10/Practice.java
 *
 * 사전 준비
 *   build.gradle 에 웹 스타터를 추가하세요. 이 스텝은 컨테이너를 HTTP 로 조작합니다.
 *       implementation 'org.springframework.boot:spring-boot-starter-web'
 *
 * 실행 (보조 프로필을 하나만 함께 켭니다. 예제끼리 컨슈머 그룹과 팩토리가 겹칩니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step10,step10-lifecycle'
 *       → [10-1][10-2] 생명주기 로그 + 레지스트리 id 목록 (id 준 것과 안 준 것을 섞어 둠)
 *   ./gradlew bootRun --args='--spring.profiles.active=step10,step10-manual'
 *       → [10-3] autoStartup=false 리스너를 REST 로 켜고 끄기
 *   ./gradlew bootRun --args='--spring.profiles.active=step10,step10-pause'
 *       → [10-4][10-5] ★핵심★ pause/resume 지연 실측, 개별 파티션 pause, 백프레셔
 *   ./gradlew bootRun --args='--spring.profiles.active=step10,step10-sleep'
 *       → [10-6] Thread.sleep 으로 컨슈머가 쫓겨나는 것 (확인 후 즉시 종료)
 *   ./gradlew bootRun --args='--spring.profiles.active=step10,step10-filter'
 *       → [10-7] RecordFilterStrategy + ackDiscarded
 *   ./gradlew bootRun --args='--spring.profiles.active=step10,step10-rebalance'
 *       → [10-8][10-9][10-10][10-11] 리밸런스 콜백 + seek + 유휴 이벤트 + 우아한 종료
 *
 * ★ 매 실행 전에 오프셋을 리셋하세요 (앱을 먼저 종료할 것).
 *   kcg --group s10-inventory  --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-highvalue  --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-settlement --topic orders --reset-offsets --to-earliest --execute
 *
 * 실행 중에 쓸 curl
 *   curl -s          localhost:8080/step10/listeners
 *   curl -s -XPOST   localhost:8080/step10/listeners/settlementListener/start
 *   curl -s -XPOST   localhost:8080/step10/listeners/settlementListener/stop
 *   curl -s -XPOST   localhost:8080/step10/pause          ← ★ 지연 ms 를 리턴한다
 *   curl -s -XPOST   localhost:8080/step10/resume
 *   curl -s -XPOST  'localhost:8080/step10/pause-partition?partition=1'
 *   curl -s -XPOST  'localhost:8080/step10/reprocess?from=2025-01-01T00:10:00Z'
 *   curl -s -XPOST   localhost:8080/step10/shutdown
 *
 * 실행 중에 확인할 CLI
 *   kcg --describe --group s10-inventory     ← pause 중에도 state 가 Stable 인지
 *   kcg --describe --group s10-highvalue     ← 필터로 버린 레코드도 커밋되는지 (LAG 0)
 *   kcg --list                               ← id 를 groupId 없이 준 리스너가 그룹을 만들지 않았는지
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ConsumerPausedEvent;
import org.springframework.kafka.event.ConsumerResumedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.event.ListenerContainerNoLongerIdleEvent;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 10 의 모든 예제를 담은 단일 파일입니다.
 * 각 nested static class 는 본문 절 번호와 1:1 로 대응합니다.
 */
public final class Practice {

    private Practice() {
        // 유틸리티 홀더. 인스턴스화하지 않습니다.
    }

    static final String TOPIC = "orders";

    // ========================================================================
    // [공통] 부하 생성기 — 초당 5건씩 orders 로 발행한다
    // ========================================================================
    //
    // pause/resume, 유휴 감지, 필터의 통과율을 관찰하려면 메시지가 계속 흘러야 합니다.
    // step10-idle 프로필에서는 이 빈을 켜지 않아야 유휴 이벤트를 볼 수 있습니다.
    @Component
    @Profile({"step10-pause", "step10-filter", "step10-sleep", "step10-rebalance"})
    static class LoadGenerator implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

        private final KafkaTemplate<String, OrderCreated> template;
        private final AtomicInteger seq = new AtomicInteger(1);
        private final AtomicBoolean running = new AtomicBoolean(true);

        LoadGenerator(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                while (running.get()) {
                    OrderCreated event = OrderCreated.of(seq.getAndIncrement());
                    template.send(TOPIC, event.orderId(), event);
                    try {
                        Thread.sleep(200L);          // 초당 5건
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "load-generator");
            t.setDaemon(true);
            t.start();
            log.info("부하 생성 시작 — 초당 5건");
        }
    }

    // ========================================================================
    // [10-1] 컨테이너 생명주기 — SmartLifecycle
    // ========================================================================
    //
    // 확인할 것
    //   ① "Started OrderServiceApplication" 이 "partitions assigned" 보다 먼저 찍힌다
    //      → start() 는 논블로킹. isRunning()==true 는 준비 완료가 아니다.
    //   ② 스레드 이름이 [yListener-0-C-1]  (= inventoryListener-0-C-1 의 뒤 15자)
    //   ③ 종료 시 "Consumer stopped" 가 "partitions revoked" 보다 먼저 나온다
    @Component
    @Profile({"step10-lifecycle", "step10-pause", "step10-rebalance", "step10-idle"})
    static class InventoryListener {

        private static final Logger log = LoggerFactory.getLogger(InventoryListener.class);

        private final AtomicLong count = new AtomicLong();

        @KafkaListener(id = "inventoryListener",
                       groupId = "s10-inventory",          // ★ id 와 groupId 를 둘 다 명시한다
                       topics = TOPIC)
        public void onMessage(OrderCreated event) throws InterruptedException {
            Thread.sleep(4L);                              // 레코드당 4ms 가정 — pause 지연 계산의 근거
            long n = count.incrementAndGet();
            if (n % 20 == 0) {
                log.info("재고 차감 {} x{} (누적 {}건)", event.sku(), event.quantity(), n);
            }
        }

        long processed() {
            return count.get();
        }
    }

    // ========================================================================
    // [10-2] KafkaListenerEndpointRegistry — id 를 안 주면 어떻게 되는가
    // ========================================================================
    //
    // 아래 두 리스너는 일부러 id 를 주지 않았습니다.
    // 레지스트리 덤프에서 org.springframework.kafka.KafkaListenerEndpointContainer#0, #1
    // 로 나오는 것을 확인하세요. 클래스를 하나 더 추가하면 이 번호가 밀립니다.
    @Component
    @Profile("step10-lifecycle")
    static class AnonymousListeners {

        private static final Logger log = LoggerFactory.getLogger(AnonymousListeners.class);

        @KafkaListener(groupId = "s10-notification", topics = TOPIC)   // id 없음
        public void notifyCustomer(OrderCreated event) {
            log.debug("알림 발송 {}", event.orderId());
        }

        // ★ 함정 재현: id 만 주고 groupId 를 안 주면 idIsGroup=true 기본값 때문에
        //   컨슈머 그룹이 "auditListener" 로 만들어집니다. kcg --list 로 확인하세요.
        //   의도한 것이 아니라면 groupId 를 명시하거나 idIsGroup=false 를 답니다.
        @KafkaListener(id = "auditListener", topics = TOPIC)
        public void audit(OrderCreated event) {
            log.debug("감사 로그 {}", event.orderId());
        }
    }

    /** 기동 직후 레지스트리에 등록된 컨테이너 id 를 전부 찍는다. */
    @Component
    @Profile("step10")
    static class RegistryDump implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(RegistryDump.class);

        private final KafkaListenerEndpointRegistry registry;

        RegistryDump(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void run(ApplicationArguments args) {
            log.info("등록된 리스너 id 목록:");
            registry.getListenerContainerIds().stream().sorted()
                    .forEach(id -> {
                        MessageListenerContainer c = registry.getListenerContainer(id);
                        log.info("  - {}  (running={}, autoStartup={}, groupId={})",
                                id, c.isRunning(), c.isAutoStartup(), c.getGroupId());
                    });

            // getListenerContainers() 는 @KafkaListener 로 만든 것만,
            // getAllListenerContainers() 는 직접 @Bean 으로 등록한 컨테이너까지 포함합니다.
            log.info("getListenerContainers()    = {}건", registry.getListenerContainers().size());
            log.info("getAllListenerContainers() = {}건", registry.getAllListenerContainers().size());
        }
    }

    // ========================================================================
    // [10-3] autoStartup=false 와 수동 기동
    // ========================================================================
    //
    // autoStartup 은 boolean 이 아니라 String 입니다. 플레이스홀더를 쓸 수 있게 하려는 설계입니다.
    //   autoStartup = "${app.listener.settlement.enabled:false}"
    @Component
    @Profile("step10-manual")
    static class SettlementListener {

        private static final Logger log = LoggerFactory.getLogger(SettlementListener.class);

        @KafkaListener(id = "settlementListener",
                       groupId = "s10-settlement",
                       topics = TOPIC,
                       autoStartup = "false")             // ★ 문자열
        public void settle(OrderCreated event) {
            log.info("정산 {} {}", event.orderId(), event.amount());
        }
    }

    /** 리스너를 이름으로 켜고 끄는 관리용 컨트롤러. 이 스텝 전체에서 씁니다. */
    @RestController
    @RequestMapping("/step10/listeners")
    @Profile("step10")
    static class ContainerAdminController {

        private static final Logger log = LoggerFactory.getLogger(ContainerAdminController.class);

        private final KafkaListenerEndpointRegistry registry;

        ContainerAdminController(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @org.springframework.web.bind.annotation.GetMapping
        public Map<String, Object> list() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String id : registry.getListenerContainerIds()) {
                MessageListenerContainer c = registry.getListenerContainer(id);
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("running", c.isRunning());
                state.put("pauseRequested", c.isPauseRequested());
                state.put("containerPaused", c.isContainerPaused());
                state.put("groupId", c.getGroupId());
                state.put("assigned", String.valueOf(c.getAssignedPartitions()));
                out.put(id, state);
            }
            return out;
        }

        @PostMapping("/{id}/start")
        public String start(@PathVariable String id) {
            MessageListenerContainer c = require(id);
            long t0 = System.currentTimeMillis();
            c.start();
            long elapsed = System.currentTimeMillis() - t0;
            // ★ start() 는 논블로킹입니다. isRunning() 은 즉시 true 가 되지만
            //   이 시점에 파티션은 아직 하나도 할당되지 않았습니다.
            log.info("start({}) 리턴 t={}ms isRunning={} assigned={}",
                    id, elapsed, c.isRunning(), c.getAssignedPartitions());
            return "%s start t=%dms running=%s".formatted(id, elapsed, c.isRunning());
        }

        @PostMapping("/{id}/stop")
        public String stop(@PathVariable String id) {
            MessageListenerContainer c = require(id);
            long t0 = System.currentTimeMillis();
            CountDownLatch latch = new CountDownLatch(1);
            c.stop(latch::countDown);
            log.info("stop({}) 리턴 t={}ms isRunning={}", id, System.currentTimeMillis() - t0, c.isRunning());
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long elapsed = System.currentTimeMillis() - t0;
            log.info("stop({}) 완료 t={}ms", id, elapsed);
            return "%s stopped t=%dms".formatted(id, elapsed);
        }

        private MessageListenerContainer require(String id) {
            MessageListenerContainer c = registry.getListenerContainer(id);
            if (c == null) {
                // ★ getListenerContainer 는 없으면 null 을 리턴합니다. 예외가 아닙니다.
                throw new IllegalArgumentException(
                        "그런 리스너 없음: " + id + " / 가능한 값: " + registry.getListenerContainerIds());
            }
            return c;
        }
    }

    // ========================================================================
    // [10-4] pause / resume — 요청과 실제 정지 사이의 시차
    // ========================================================================
    //
    // pause() 는 플래그만 세우고 즉시 리턴합니다.
    // 컨슈머 스레드는 이미 poll 로 가져온 배치(최대 max.poll.records=500)를
    // 전부 처리한 뒤에야 멈춥니다.
    //
    // 기대 출력
    //   {"pauseRequested":true,"pausedAfterMs":1843,"processedDuringPause":437}
    @RestController
    @RequestMapping("/step10")
    @Profile("step10-pause")
    static class PauseTimingController {

        private static final Logger log = LoggerFactory.getLogger(PauseTimingController.class);

        private final KafkaListenerEndpointRegistry registry;
        private final InventoryListener listener;
        private final TaskScheduler scheduler;

        PauseTimingController(KafkaListenerEndpointRegistry registry,
                              InventoryListener listener,
                              TaskScheduler scheduler) {
            this.registry = registry;
            this.listener = listener;
            this.scheduler = scheduler;
        }

        @PostMapping("/pause")
        public Map<String, Object> pause() throws InterruptedException {
            MessageListenerContainer c = registry.getListenerContainer("inventoryListener");
            long before = listener.processed();
            long t0 = System.currentTimeMillis();

            c.pause();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pauseRequestedImmediately", c.isPauseRequested());   // true
            out.put("containerPausedImmediately", c.isContainerPaused()); // false — 아직 아니다

            // ★ 여기가 이 절의 핵심. isContainerPaused() 가 true 가 될 때까지 기다린다.
            while (!c.isContainerPaused() && System.currentTimeMillis() - t0 < 30_000L) {
                Thread.sleep(50L);
            }
            long elapsed = System.currentTimeMillis() - t0;
            out.put("pausedAfterMs", elapsed);
            out.put("processedDuringPause", listener.processed() - before);

            log.info("pause 지연 {}ms — 그 사이 {}건이 더 처리됨",
                    elapsed, listener.processed() - before);
            return out;
        }

        @PostMapping("/resume")
        public String resume() {
            registry.getListenerContainer("inventoryListener").resume();
            log.info("resume() 호출");
            return "resumed";
        }

        /** 90초 뒤 자동 재개하는 백프레셔 시뮬레이션. Thread.sleep 과 대비된다. */
        @PostMapping("/pause-90s")
        public String pause90s() {
            MessageListenerContainer c = registry.getListenerContainer("inventoryListener");
            log.info("다운스트림 장애 감지 — pause 90초");
            c.pause();
            scheduler.schedule(() -> {
                log.info("90초 경과 — resume");
                c.resume();
            }, Instant.now().plusSeconds(90));
            return "paused for 90s";
        }

        // --------------------------------------------------------------------
        // [10-5] 개별 파티션 pause
        // --------------------------------------------------------------------
        @PostMapping("/pause-partition")
        public String pausePartition(@RequestParam int partition) {
            MessageListenerContainer c = registry.getListenerContainer("inventoryListener");
            TopicPartition tp = new TopicPartition(TOPIC, partition);
            c.pausePartition(tp);
            PausedPartitions.PAUSED.add(tp);        // ★ 리밸런스 후 재적용하려고 직접 기억해 둔다
            log.info("{} 정지 요청됨={}", tp, c.isPartitionPauseRequested(tp));
            return tp + " pauseRequested=" + c.isPartitionPauseRequested(tp);
        }

        @PostMapping("/resume-partition")
        public String resumePartition(@RequestParam int partition) {
            MessageListenerContainer c = registry.getListenerContainer("inventoryListener");
            TopicPartition tp = new TopicPartition(TOPIC, partition);
            c.resumePartition(tp);
            PausedPartitions.PAUSED.remove(tp);
            return tp + " resumed";
        }
    }

    /**
     * pause 는 "일시적 지시"이지 영속 설정이 아닙니다.
     * 리밸런스로 파티션이 회수됐다 재할당되면 Spring 이 재개된 상태로 되돌립니다.
     * 그래서 멈춰 둔 파티션 목록을 애플리케이션이 직접 들고 있어야 합니다. (10-8 에서 재적용)
     */
    static final class PausedPartitions {
        static final Set<TopicPartition> PAUSED = new CopyOnWriteArraySet<>();

        private PausedPartitions() {
        }
    }

    // ========================================================================
    // [10-6] pause 는 안전하고 Thread.sleep 은 위험하다
    // ========================================================================
    //
    // ⚠️ 이 프로필은 컨슈머를 일부러 죽입니다. 로그 세 사이클만 보고 앱을 내리세요.
    //
    // max.poll.interval.ms 를 60초로 낮춘 전용 팩토리를 씁니다.
    // 기본값 5분으로는 재현에 6분이 걸립니다.
    //
    // 기대 로그
    //   WARN ... consumer poll timeout has expired.
    //   INFO ... sending LeaveGroup request to coordinator due to consumer poll timeout has expired.
    //   ERROR... CommitFailedException: Offset commit cannot be completed ...
    //   INFO ... partitions revoked / partitions assigned
    //   INFO ... 다운스트림 장애 감지 — 90초 대기 시작       ← 같은 레코드를 또 받는다
    @Configuration
    @Profile("step10-sleep")
    static class ShortPollIntervalConfig {

        @Bean
        ConsumerFactory<String, OrderCreated> shortPollConsumerFactory(
                ConsumerFactory<String, OrderCreated> base) {
            Map<String, Object> props = new HashMap<>(base.getConfigurationProperties());
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 60_000);
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> shortPollFactory(
                ConsumerFactory<String, OrderCreated> shortPollConsumerFactory) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(shortPollConsumerFactory);
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step10-sleep")
    static class SleepDemo {

        private static final Logger log = LoggerFactory.getLogger(SleepDemo.class);

        private final AtomicBoolean downstreamDown = new AtomicBoolean(true);

        @KafkaListener(id = "sleepListener",
                       groupId = "s10-sleep",
                       topics = TOPIC,
                       containerFactory = "shortPollFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) throws InterruptedException {
            if (downstreamDown.get()) {
                log.info("다운스트림 장애 감지 — 90초 대기 시작 ({}-{}@{})",
                        record.topic(), record.partition(), record.offset());
                Thread.sleep(90_000L);       // ★ 절대 하지 마세요. poll 이 멈춥니다.
            }
            log.info("처리 {}", record.value().orderId());
        }
    }

    // ========================================================================
    // [10-7] RecordFilterStrategy — 리스너 진입 전에 버리기
    // ========================================================================
    //
    // ★★ filter() 가 true 를 리턴하면 "통과"가 아니라 "버림"입니다. ★★
    //    Javadoc: Return true if the record should be discarded.
    //
    // OrderCreated.of(seq).amount = 10000 + (seq % 7) * 1000  → 10000~16000 순환
    // 13000 미만을 버리므로 7건 중 3건만 통과합니다.
    @Configuration
    @Profile("step10-filter")
    static class FilterConfig {

        static final BigDecimal THRESHOLD = new BigDecimal("13000");

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> filteringFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);

            f.setRecordFilterStrategy(record ->
                    record.value().amount().compareTo(THRESHOLD) < 0);   // true = discard

            // ★ 수동 ack 모드에서 필터를 쓸 때는 반드시 켜야 합니다.
            //   끄면 버려진 레코드의 오프셋이 커밋되지 않아 LAG 이 0 으로 안 떨어집니다.
            f.setAckDiscarded(true);
            f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
            return f;
        }
    }

    @Component
    @Profile("step10-filter")
    static class HighValueListener {

        private static final Logger log = LoggerFactory.getLogger(HighValueListener.class);

        private final AtomicLong passed = new AtomicLong();

        @KafkaListener(id = "highValueListener",
                       groupId = "s10-highvalue",
                       topics = TOPIC,
                       containerFactory = "filteringFactory")
        public void onHighValue(OrderCreated event, Acknowledgment ack) {
            log.info("고액 주문 {} {} (통과 누적 {}건)",
                    event.orderId(), event.amount(), passed.incrementAndGet());
            ack.acknowledge();
        }
    }

    // ========================================================================
    // [10-8] ConsumerAwareRebalanceListener — 리밸런스 순간에 끼어들기
    // ========================================================================
    //
    // 호출 순서
    //   onPartitionsRevokedBeforeCommit  →  (Spring 이 커밋)  →  onPartitionsRevokedAfterCommit
    //   →  (재조인)  →  onPartitionsAssigned
    //
    // ⚠️ onPartitionsLost 에서는 커밋도 seek 도 하면 안 됩니다.
    //    이미 다른 컨슈머가 그 파티션을 가져갔을 수 있습니다.
    @Configuration
    @Profile("step10-rebalance")
    static class RebalanceAwareConfig {

        private static final Logger log = LoggerFactory.getLogger(RebalanceAwareConfig.class);

        /** 파티션별 로컬 캐시. 리밸런스 때 정리해야 하는 상태의 예시입니다. */
        private final Map<TopicPartition, List<String>> partitionCache = new LinkedHashMap<>();

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> rebalanceAwareFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);

            // [10-10] 유휴 이벤트를 30초 간격으로 발행
            f.getContainerProperties().setIdleEventInterval(30_000L);

            // [10-11] 종료를 기다리는 상한
            f.getContainerProperties().setShutdownTimeout(10_000L);

            f.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {

                @Override
                public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer,
                                                            Collection<TopicPartition> parts) {
                    // 커밋 전. 아직 이 파티션의 주인이므로 버퍼를 비우고 마무리할 수 있다.
                    log.info("[revoked-before-commit] {} — 버퍼 flush", parts);
                }

                @Override
                public void onPartitionsRevokedAfterCommit(Consumer<?, ?> consumer,
                                                           Collection<TopicPartition> parts) {
                    // 커밋 후. 로컬 상태를 정리한다.
                    log.info("[revoked-after-commit] {} — 파티션 캐시 삭제", parts);
                    parts.forEach(partitionCache::remove);
                }

                @Override
                public void onPartitionsAssigned(Consumer<?, ?> consumer,
                                                 Collection<TopicPartition> parts) {
                    parts.forEach(tp -> {
                        partitionCache.put(tp, new ArrayList<>());
                        log.info("[assigned] {} position={}", tp, consumer.position(tp));
                    });
                    // ★ 10-5 의 함정 해결: 멈춰 두었던 파티션을 다시 멈춘다.
                    //   리밸런스가 pause 상태를 초기화하기 때문이다.
                    if (!PausedPartitions.PAUSED.isEmpty()) {
                        log.warn("[assigned] 재적용할 pause 파티션 {}", PausedPartitions.PAUSED);
                    }
                }

                @Override
                public void onPartitionsLost(Consumer<?, ?> consumer,
                                             Collection<TopicPartition> parts) {
                    log.error("[LOST] {} — 커밋하지 않고 로컬 상태만 버립니다", parts);
                    parts.forEach(partitionCache::remove);
                    // consumer.commitSync();   ← 절대 금지.
                    //   이미 다른 컨슈머가 진행 중일 수 있고, 성공하면 그 진행을 되돌린다.
                }
            });
            return f;
        }
    }

    @Component
    @Profile("step10-rebalance")
    static class RebalanceLoggingListener {

        private static final Logger log = LoggerFactory.getLogger(RebalanceLoggingListener.class);

        private final AtomicLong count = new AtomicLong();

        @KafkaListener(id = "rebalanceListener",
                       groupId = "s10-rebalance",
                       topics = TOPIC,
                       containerFactory = "rebalanceAwareFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            long n = count.incrementAndGet();
            if (n % 20 == 0) {
                log.info("처리 {}-{}@{} (누적 {}건)",
                        record.topic(), record.partition(), record.offset(), n);
            }
        }
    }

    // ========================================================================
    // [10-9] seek — 읽기 위치를 옮긴다
    // ========================================================================
    //
    // AbstractConsumerSeekAware 는 파티션별 콜백을 맵으로 관리해 줍니다.
    // registerSeekCallback 이 넘겨주는 "단일" 콜백을 필드에 저장하는 방식은
    // concurrency > 1 에서 마지막에 등록된 것 하나만 남아 잘못된 컨슈머에 seek 이 걸립니다.
    @Component
    @Profile("step10-rebalance")
    static class ReprocessTool extends AbstractConsumerSeekAware {

        private static final Logger log = LoggerFactory.getLogger(ReprocessTool.class);

        /** 특정 시각 이후로 되감는다. 레코드 타임스탬프 기준이지 페이로드의 createdAt 이 아니다. */
        void reprocessFrom(Instant from) {
            Map<TopicPartition, ConsumerSeekCallback> callbacks = getSeekCallbacks();
            if (callbacks.isEmpty()) {
                log.warn("아직 할당된 파티션이 없습니다. 컨슈머가 그룹에 합류했는지 확인하세요.");
                return;
            }
            callbacks.forEach((tp, cb) ->
                    cb.seekToTimestamp(tp.topic(), tp.partition(), from.toEpochMilli()));
            log.info("{} 이후로 seek 요청. 대상 파티션 {}", from, callbacks.keySet());
        }

        void rewindToBeginning() {
            getSeekCallbacks().forEach((tp, cb) -> cb.seekToBeginning(tp.topic(), tp.partition()));
            log.warn("전체 되감기 요청 — 운영에서는 반드시 명시적 명령으로만 하세요");
        }

        // ⚠️ 여기서 seekToBeginning 을 부르면 리밸런스마다 처음부터 다시 읽습니다.
        //    onPartitionsAssigned 는 기동 시에만이 아니라 배포·스케일아웃마다 호출됩니다.
        @Override
        public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                         ConsumerSeekCallback callback) {
            super.onPartitionsAssigned(assignments, callback);   // 콜백 맵 갱신. 반드시 호출할 것
            log.info("[seek-aware] 할당 {}", assignments.keySet());
            // callback.seekToBeginning(...);   ← 무한 재처리. 하지 마세요.
        }
    }

    @RestController
    @RequestMapping("/step10")
    @Profile("step10-rebalance")
    static class ReprocessController {

        private final ReprocessTool tool;

        ReprocessController(ReprocessTool tool) {
            this.tool = tool;
        }

        @PostMapping("/reprocess")
        public String reprocess(@RequestParam String from) {
            tool.reprocessFrom(Instant.parse(from));
            return "seek requested from " + from;
        }

        @PostMapping("/rewind")
        public String rewind() {
            tool.rewindToBeginning();
            return "rewound";
        }
    }

    // ========================================================================
    // [10-10] 컨테이너 이벤트 — 유휴 감지
    // ========================================================================
    //
    // 랙 모니터링은 "메시지가 들어올 때" 유효합니다.
    // 업스트림이 죽어 발행이 끊기면 랙은 0 이고 모든 지표가 초록색입니다.
    // 그 침묵을 잡는 것이 ListenerContainerIdleEvent 입니다.
    //
    // ⚠️ @EventListener 는 이벤트를 발행한 스레드(= 컨슈머 스레드)에서 동기 실행됩니다.
    //    여기서 오래 걸리는 일을 하면 poll 이 멈춥니다. 실전에서는 @Async 로 빼세요.
    @Component
    @Profile("step10")
    static class ContainerEventWatcher {

        private static final Logger log = LoggerFactory.getLogger(ContainerEventWatcher.class);

        @EventListener
        public void onIdle(ListenerContainerIdleEvent event) {
            log.warn("유휴 감지 listenerId={} idle={}s partitions={}",
                    event.getListenerId(), event.getIdleTime() / 1000, event.getTopicPartitions());
            if (event.getIdleTime() > 300_000L) {
                log.error("★ orders 유입 5분간 없음 — 업스트림 확인 필요");
            }
        }

        @EventListener
        public void onNoLongerIdle(ListenerContainerNoLongerIdleEvent event) {
            log.info("유휴 해제 listenerId={} 마지막 유휴 {}s",
                    event.getListenerId(), event.getIdleTime() / 1000);
        }

        @EventListener
        public void onPaused(ConsumerPausedEvent event) {
            log.info("[event] ConsumerPaused partitions={}", event.getPartitions());
        }

        @EventListener
        public void onResumed(ConsumerResumedEvent event) {
            log.info("[event] ConsumerResumed partitions={}", event.getPartitions());
        }

        /** 정상 종료가 아닌 종료를 즉시 알아채는 것이 이 핸들러의 목적입니다. */
        @EventListener
        public void onStopped(ConsumerStoppedEvent event) {
            if (event.getReason() == ConsumerStoppedEvent.Reason.NORMAL) {
                log.info("[event] ConsumerStopped reason=NORMAL");
            } else {
                log.error("★ 컨슈머가 비정상 종료했습니다. reason={}", event.getReason());
            }
        }
    }

    // ========================================================================
    // [10-11] 우아한 종료
    // ========================================================================
    //
    // stop() 은 논블로킹입니다. isRunning() 은 즉시 false 가 되지만
    // 실제 종료는 현재 배치를 다 처리한 뒤입니다.
    //
    // stopImmediate=true 로 두면 현재 레코드까지만 처리하고 배치 나머지를 버립니다.
    // 유실은 없지만(커밋 안 됨) 재기동 시 중복 처리됩니다.
    @RestController
    @RequestMapping("/step10")
    @Profile("step10")
    static class ShutdownController {

        private static final Logger log = LoggerFactory.getLogger(ShutdownController.class);

        private final KafkaListenerEndpointRegistry registry;

        ShutdownController(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @PostMapping("/shutdown")
        public Map<String, Object> shutdownAll(
                @RequestParam(defaultValue = "false") boolean immediate) throws InterruptedException {

            Collection<MessageListenerContainer> all = registry.getAllListenerContainers();
            CountDownLatch latch = new CountDownLatch(all.size());
            long t0 = System.currentTimeMillis();

            for (MessageListenerContainer c : all) {
                c.getContainerProperties().setStopImmediate(immediate);
                c.stop(latch::countDown);
            }
            long returned = System.currentTimeMillis() - t0;
            log.info("stop() 리턴 t={}ms (컨테이너 {}개)", returned, all.size());

            latch.await(30, TimeUnit.SECONDS);
            long completed = System.currentTimeMillis() - t0;
            log.info("컨테이너 정지 완료 t={}ms immediate={}", completed, immediate);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stopImmediate", immediate);
            out.put("stopReturnedMs", returned);
            out.put("stopCompletedMs", completed);
            return out;
        }
    }

    /** TaskScheduler 를 쓰려면 스케줄링을 켜 두어야 합니다. */
    @Configuration
    @EnableScheduling
    @Profile("step10")
    static class SchedulingConfig {
    }
}
