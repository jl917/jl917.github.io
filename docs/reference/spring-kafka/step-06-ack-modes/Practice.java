package com.example.order.step06;

/*
 * ============================================================================
 * Step 06 — 오프셋 커밋과 AckMode : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step06/Practice.java
 *
 * 기본 실행 (시드 데이터만 넣고 아무 리스너도 켜지 않습니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step06'
 *
 * 절별 보조 프로필 — 반드시 "하나씩" 켤 것. 동시에 켜면 커밋 로그가 뒤섞입니다.
 *   step06,step06-record            → [6-3][6-4] AckMode.RECORD           (커밋 500회)
 *   step06,step06-batch             → [6-3][6-4] AckMode.BATCH (기본값)   (커밋 1회)
 *   step06,step06-time              → [6-3]      AckMode.TIME  (5초마다)
 *   step06,step06-count             → [6-3]      AckMode.COUNT (100건마다)
 *   step06,step06-swallow           → [6-5] ⚠️ 예외를 삼켜 메시지를 "유실"시킨다
 *   step06,step06-rethrow           → [6-5]    예외를 던져 앞부분이 "중복" 처리된다
 *   step06,step06-manual            → [6-6] MANUAL_IMMEDIATE + 조건부 커밋
 *   step06,step06-forget            → [6-7] ⚠️ acknowledge() 를 안 부른다 → LAG 이 안 준다
 *   step06,step06-batch-whole       → [6-8] ⚠️ 배치 부분 실패 → 100건 통째로 재처리
 *   step06,step06-batch-nack        → [6-8] ① ack.nack(index, Duration) 로 부분 커밋
 *   step06,step06-batch-failedex    → [6-8] ② BatchListenerFailedException 으로 부분 커밋
 *
 * 커밋 시점을 눈으로 보려면 application.yml 에 아래를 추가하세요 ([6-4]).
 *   logging:
 *     level:
 *       org.apache.kafka.clients.consumer.internals.ConsumerCoordinator: DEBUG
 *
 * 실행 전/후에 확인할 CLI (project/ 의 alias 를 등록해 두었다고 가정)
 *   kcg --list
 *   kcg --describe --group s06-inventory
 *   kcg --describe --group s06-manual
 *   kt  --describe --topic orders
 *
 * 오프셋을 처음부터 다시 읽고 싶다면 (앱을 먼저 종료할 것)
 *   kcg --group s06-inventory --topic orders --reset-offsets --to-earliest --execute
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 06 의 모든 예제를 담은 단일 파일입니다.
 * 각 nested static class 는 본문 절 번호와 1:1 로 대응합니다.
 *
 * 이 스텝의 리스너는 전부 concurrency=1 로 고정된 전용 팩터리를 씁니다.
 * 커밋 로그를 읽는 것이 목적인데, 스레드가 3개면 세 컨슈머의 커밋이 섞여
 * "몇 번 커밋했는가"를 셀 수 없기 때문입니다.
 */
public final class Practice {

    private Practice() {
        // 유틸리티 홀더. 인스턴스화하지 않습니다.
    }

    static final String TOPIC = "orders";

    // ========================================================================
    // [6-0] 실습 준비 — orders 토픽에 결정적 시드 데이터 500건을 넣는다
    // ========================================================================
    //
    // OrderCreated.of(seq) 는 난수를 쓰지 않으므로 몇 번을 실행하든 같은 값이고,
    // 키가 같으니 파티션 배정도 항상 같습니다.
    // 이미 500건이 들어 있다면 이 러너를 건너뛰도록 --app.step06.seed=false 를 주세요.
    //
    @Component
    @Profile("step06")
    @Order(1)
    public static class Seeder implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Seeder.class);

        private final KafkaTemplate<String, OrderCreated> template;

        public Seeder(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            if (args.containsOption("app.step06.seed")
                    && "false".equals(args.getOptionValues("app.step06.seed").get(0))) {
                log.info("[6-0] 시드 생략 (--app.step06.seed=false)");
                return;
            }
            long t0 = System.nanoTime();
            for (int seq = 1; seq <= 500; seq++) {
                OrderCreated event = OrderCreated.of(seq);
                template.send(TOPIC, event.orderId(), event);
            }
            template.flush();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[6-0] 시드 완료: 500건 발행, {} ms", ms);
        }
    }

    // ========================================================================
    // [6-3] AckMode 6종을 각각 별도 컨테이너 팩터리로 만든다
    // ========================================================================
    //
    // application.yml 의 spring.kafka.listener.ack-mode 는 "기본 팩터리"에만 적용됩니다.
    // 한 애플리케이션 안에서 모드를 비교하려면 팩터리를 따로 만들고
    // @KafkaListener(containerFactory = "...") 로 지목하는 것이 가장 확실합니다.
    //
    // 주의: AckMode 는 Kafka 클라이언트가 아니라 "Spring 리스너 컨테이너"의 개념입니다.
    //       브로커에 전달되는 설정이 아니라, 컨테이너가 commitSync/commitAsync 를
    //       "언제 호출할지"를 정하는 정책입니다.
    //
    @Configuration
    @Profile("step06")
    public static class AckModeFactories {

        private final ConsumerFactory<String, OrderCreated> consumerFactory;

        public AckModeFactories(ConsumerFactory<String, OrderCreated> consumerFactory) {
            this.consumerFactory = consumerFactory;
        }

        private ConcurrentKafkaListenerContainerFactory<String, OrderCreated> base(AckMode mode) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(this.consumerFactory);
            f.setConcurrency(1);                       // 커밋 횟수를 세려면 스레드 1개여야 합니다
            f.getContainerProperties().setAckMode(mode);
            return f;
        }

        /** 레코드 1건 처리할 때마다 커밋. 가장 안전하고 가장 느립니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> recordAckFactory() {
            return base(AckMode.RECORD);
        }

        /** poll 로 가져온 배치를 다 처리하면 한 번 커밋. spring-kafka 의 기본값입니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchAckFactory() {
            return base(AckMode.BATCH);
        }

        /** ackTime(ms) 이 지난 뒤 오는 첫 커밋 기회에 커밋. "5초마다"가 아니라 "5초 지나면"입니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> timeAckFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = base(AckMode.TIME);
            f.getContainerProperties().setAckTime(5_000L);
            return f;
        }

        /** ackCount 건 이상 처리하면 커밋. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> countAckFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = base(AckMode.COUNT);
            f.getContainerProperties().setAckCount(100);
            return f;
        }

        /** 시간 OR 건수 중 먼저 도달하는 쪽에서 커밋. 둘 다 설정해야 의미가 있습니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> countTimeAckFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = base(AckMode.COUNT_TIME);
            f.getContainerProperties().setAckCount(100);
            f.getContainerProperties().setAckTime(5_000L);
            return f;
        }

        /** acknowledge() 를 큐에 넣고, poll 루프가 한 바퀴 끝날 때 모아서 커밋. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> manualAckFactory() {
            return base(AckMode.MANUAL);
        }

        /** acknowledge() 호출 즉시 commitSync. 커밋 지점을 코드로 완전히 통제합니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> manualImmediateAckFactory() {
            return base(AckMode.MANUAL_IMMEDIATE);
        }

        // --------------------------------------------------------------------
        // [6-8] 배치 리스너 전용 팩터리 3종
        // --------------------------------------------------------------------

        private ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchBase(AckMode mode) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = base(mode);
            f.setBatchListener(true);      // List<T> 로 받는 리스너
            return f;
        }

        /** 일반 예외를 던지는 배치 리스너 — 배치 전체가 재처리됩니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchWholeFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = batchBase(AckMode.BATCH);
            // 재시도 2회로 제한. 무한 재시도면 로그가 끝없이 흐릅니다.
            f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, 2L)));
            return f;
        }

        /** ack.nack(index, Duration) 을 쓰는 배치 리스너 — MANUAL 이어야 Acknowledgment 를 받습니다. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchNackFactory() {
            return batchBase(AckMode.MANUAL);
        }

        /** BatchListenerFailedException 을 던지는 배치 리스너. */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchFailedExFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = batchBase(AckMode.BATCH);
            f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, 2L)));
            return f;
        }

        // --------------------------------------------------------------------
        // [6-10] 커밋 실패 처리 옵션을 켠 팩터리
        // --------------------------------------------------------------------
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> resilientCommitFactory() {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f = base(AckMode.RECORD);
            f.getContainerProperties().setSyncCommits(true);   // 기본값 true. 명시해 둡니다
            f.getContainerProperties().setCommitRetries(5);    // 재시도 가능한 커밋 실패를 5회까지
            return f;
        }
    }

    // ========================================================================
    // [6-4] 커밋 횟수와 처리량을 실제로 센다 — RECORD vs BATCH
    // ========================================================================
    //
    // 리스너 자체는 아무 일도 하지 않고 건수만 셉니다.
    // 500건을 다 받으면 경과 시간과 msg/s 를 찍습니다.
    // 이 숫자를 ConsumerCoordinator 의 DEBUG 커밋 로그 횟수와 함께 보세요.
    //
    @Component
    @Profile({"step06-record", "step06-batch"})
    public static class ThroughputMeter {

        private static final Logger log = LoggerFactory.getLogger(ThroughputMeter.class);

        private final AtomicInteger count = new AtomicInteger();
        private final AtomicLong startNanos = new AtomicLong();

        @KafkaListener(
                id = "s06-record",
                topics = TOPIC,
                groupId = "s06-record",
                containerFactory = "recordAckFactory",
                autoStartup = "false")   // 프로필로 켜기 위해 아래 Starter 가 시작시킵니다
        public void onRecordMode(OrderCreated event) {
            tick("RECORD", event);
        }

        @KafkaListener(
                id = "s06-batch",
                topics = TOPIC,
                groupId = "s06-batch",
                containerFactory = "batchAckFactory",
                autoStartup = "false")
        public void onBatchMode(OrderCreated event) {
            tick("BATCH", event);
        }

        private void tick(String mode, OrderCreated event) {
            startNanos.compareAndSet(0L, System.nanoTime());
            int n = count.incrementAndGet();
            if (n == 500) {
                long ms = (System.nanoTime() - startNanos.get()) / 1_000_000;
                log.info("[6-4] {} 모드: {}건 처리, {} ms, {} msg/s (마지막={})",
                        mode, n, ms, (n * 1000L) / Math.max(ms, 1), event.orderId());
            }
        }
    }

    /**
     * autoStartup=false 로 만든 컨테이너를, 활성 프로필에 맞는 것만 골라 기동합니다.
     * 이렇게 하지 않으면 프로필 하나 켤 때 모든 리스너가 함께 떠서 커밋 로그가 뒤섞입니다.
     */
    @Component
    @Profile("step06")
    @Order(2)
    public static class Starter implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Starter.class);

        private final org.springframework.kafka.config.KafkaListenerEndpointRegistry registry;
        private final org.springframework.core.env.Environment env;

        public Starter(org.springframework.kafka.config.KafkaListenerEndpointRegistry registry,
                       org.springframework.core.env.Environment env) {
            this.registry = registry;
            this.env = env;
        }

        @Override
        public void run(ApplicationArguments args) {
            start("step06-record", "s06-record");
            start("step06-batch", "s06-batch");
            start("step06-time", "s06-time");
            start("step06-count", "s06-count");
            start("step06-swallow", "s06-swallow");
            start("step06-rethrow", "s06-rethrow");
            start("step06-manual", "s06-manual");
            start("step06-forget", "s06-forget");
            start("step06-batch-whole", "s06-batch-whole");
            start("step06-batch-nack", "s06-batch-nack");
            start("step06-batch-failedex", "s06-batch-failedex");
        }

        private void start(String profile, String listenerId) {
            if (!env.acceptsProfiles(org.springframework.core.env.Profiles.of(profile))) {
                return;
            }
            var container = registry.getListenerContainer(listenerId);
            if (container == null) {
                log.warn("리스너 {} 가 등록되지 않았습니다. 프로필을 확인하세요.", listenerId);
                return;
            }
            log.info("프로필 {} 활성 → 리스너 {} 기동", profile, listenerId);
            container.start();
        }
    }

    // ========================================================================
    // [6-3] TIME / COUNT 모드 관찰용 리스너
    // ========================================================================
    @Component
    @Profile({"step06-time", "step06-count"})
    public static class TimeCountListeners {

        private static final Logger log = LoggerFactory.getLogger(TimeCountListeners.class);

        @KafkaListener(id = "s06-time", topics = TOPIC, groupId = "s06-time",
                containerFactory = "timeAckFactory", autoStartup = "false")
        public void onTime(OrderCreated event) {
            log.debug("[6-3] TIME  {}", event.orderId());
        }

        @KafkaListener(id = "s06-count", topics = TOPIC, groupId = "s06-count",
                containerFactory = "countAckFactory", autoStartup = "false")
        public void onCount(OrderCreated event) {
            log.debug("[6-3] COUNT {}", event.orderId());
        }
    }

    // ========================================================================
    // [6-5] ⚠️ 핵심 함정 — 예외를 삼키면 메시지가 조용히 사라진다
    // ========================================================================
    //
    // seq 가 5의 배수인 주문을 "처리 실패"로 간주합니다.
    // Swallowing 은 try-catch 로 로그만 찍습니다. 리스너는 정상 리턴하고,
    // AckMode.BATCH 는 배치 끝에서 그대로 커밋합니다.
    //   → 실패한 100건은 어디에도 남지 않고 오프셋만 전진합니다. 유실입니다.
    //   → LAG 은 0 입니다. 지표만 보면 완벽하게 정상으로 보입니다.
    //
    @Component
    @Profile("step06-swallow")
    public static class SwallowingListener {

        private static final Logger log = LoggerFactory.getLogger(SwallowingListener.class);

        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger dropped = new AtomicInteger();

        @KafkaListener(id = "s06-swallow", topics = TOPIC, groupId = "s06-swallow",
                containerFactory = "batchAckFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            try {
                handle(record.value());
                processed.incrementAndGet();
            } catch (RuntimeException ex) {
                // ⚠️ 이 한 줄이 메시지를 지웁니다.
                log.error("[6-5] 처리 실패, 일단 넘어갑니다: {} ({}건째)",
                        record.value().orderId(), dropped.incrementAndGet(), ex);
            }
            if (record.offset() % 100 == 99) {
                log.info("[6-5] 진행 상황: 성공 {} / 유실 {}", processed.get(), dropped.get());
            }
        }

        private void handle(OrderCreated event) {
            int seq = Integer.parseInt(event.orderId().substring(4));
            if (seq % 5 == 0) {
                throw new IllegalStateException("재고 서비스 응답 없음: " + event.orderId());
            }
        }
    }

    // ========================================================================
    // [6-5] 대조군 — 예외를 그대로 던지면 배치 앞부분이 중복 처리된다
    // ========================================================================
    //
    // 예외를 던지면 DefaultErrorHandler 가 실패 지점으로 seek 합니다.
    // 실패 레코드 앞의 성공분은 커밋되지 않았으므로 함께 다시 읽힙니다.
    //   → 유실은 없지만 중복이 생깁니다. 이것이 at-least-once 의 대가입니다.
    //
    @Component
    @Profile("step06-rethrow")
    public static class RethrowingListener {

        private static final Logger log = LoggerFactory.getLogger(RethrowingListener.class);

        private final AtomicInteger invocations = new AtomicInteger();

        @KafkaListener(id = "s06-rethrow", topics = TOPIC, groupId = "s06-rethrow",
                containerFactory = "batchAckFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            int n = invocations.incrementAndGet();
            OrderCreated event = record.value();
            int seq = Integer.parseInt(event.orderId().substring(4));
            log.info("[6-5] 처리 {} (누적 호출 {}회) partition={} offset={}",
                    event.orderId(), n, record.partition(), record.offset());
            if (seq == 5) {
                throw new IllegalStateException("재고 서비스 응답 없음: " + event.orderId());
            }
        }
    }

    // ========================================================================
    // [6-6] MANUAL_IMMEDIATE — 조건부 커밋
    // ========================================================================
    //
    // Acknowledgment 파라미터는 AckMode 가 MANUAL 또는 MANUAL_IMMEDIATE 일 때만
    // 주입됩니다. 그 외 모드에서 선언하면 "기동 자체가 실패"합니다 ([6-7] 참고).
    //
    // 아래는 "외부 시스템 호출이 성공했을 때만 커밋"하는 전형적인 형태입니다.
    // 실패 시 nack(Duration) 을 부르면 그 레코드부터 다시 읽고, 지정한 시간만큼 쉽니다.
    //
    @Component
    @Profile("step06-manual")
    public static class ManualAckListener {

        private static final Logger log = LoggerFactory.getLogger(ManualAckListener.class);

        private final AtomicInteger attempts = new AtomicInteger();

        @KafkaListener(id = "s06-manual", topics = TOPIC, groupId = "s06-manual",
                containerFactory = "manualImmediateAckFactory", autoStartup = "false")
        public void onMessage(ConsumerRecord<String, OrderCreated> record, Acknowledgment ack) {
            OrderCreated event = record.value();
            int seq = Integer.parseInt(event.orderId().substring(4));

            // ORD-0007 만 두 번째 시도에서 성공하도록 꾸며 둔 시나리오
            boolean ok = seq != 7 || attempts.incrementAndGet() >= 2;

            if (ok) {
                log.info("[6-6] 처리 성공 → 즉시 커밋 {} offset={}", event.orderId(), record.offset());
                ack.acknowledge();     // MANUAL_IMMEDIATE: 여기서 바로 commitSync 가 나갑니다
            } else {
                log.warn("[6-6] 처리 실패 → nack, 1초 후 이 오프셋부터 다시 읽습니다 {} offset={}",
                        event.orderId(), record.offset());
                ack.nack(Duration.ofSeconds(1));   // Spring Kafka 3.x 시그니처
            }
        }
    }

    // ========================================================================
    // [6-7] ⚠️ 함정 — Acknowledgment 를 받고도 acknowledge() 를 안 부른다
    // ========================================================================
    //
    // 컴파일도 되고 기동도 되고 로그도 깨끗합니다. 메시지도 다 처리됩니다.
    // 다만 커밋이 한 번도 일어나지 않아, 재시작하면 처음부터 전부 다시 읽습니다.
    // kcg --describe --group s06-forget 로 LAG 이 500 에서 안 줄어드는 것을 확인하세요.
    //
    @Component
    @Profile("step06-forget")
    public static class ForgetfulListener {

        private static final Logger log = LoggerFactory.getLogger(ForgetfulListener.class);

        private final AtomicInteger count = new AtomicInteger();

        @KafkaListener(id = "s06-forget", topics = TOPIC, groupId = "s06-forget",
                containerFactory = "manualAckFactory", autoStartup = "false")
        public void onMessage(OrderCreated event, Acknowledgment ack) {
            int n = count.incrementAndGet();
            log.debug("[6-7] 처리 {} ({}건째)", event.orderId(), n);
            // ⚠️ ack.acknowledge(); 를 부르지 않았습니다.
            //    아래 한 줄의 주석을 풀면 정상 동작합니다.
            // ack.acknowledge();
            if (n % 100 == 0) {
                log.info("[6-7] {}건 처리했지만 커밋은 0회입니다. kcg --describe --group s06-forget 확인", n);
            }
        }
    }

    // ========================================================================
    // [6-8] 배치 리스너의 부분 실패 — ①②③ 세 가지 대응
    // ========================================================================

    /** ⚠️ 대조군: 일반 예외 → 인덱스 정보가 없어 배치 전체가 재처리됩니다. */
    @Component
    @Profile("step06-batch-whole")
    public static class BatchWholeRetryListener {

        private static final Logger log = LoggerFactory.getLogger(BatchWholeRetryListener.class);

        private final AtomicInteger totalHandled = new AtomicInteger();

        @KafkaListener(id = "s06-batch-whole", topics = TOPIC, groupId = "s06-batch-whole",
                containerFactory = "batchWholeFactory", autoStartup = "false")
        public void onBatch(List<ConsumerRecord<String, OrderCreated>> records) {
            log.info("[6-8] 배치 수신 {}건 (offset {} ~ {})", records.size(),
                    records.get(0).offset(), records.get(records.size() - 1).offset());
            for (int i = 0; i < records.size(); i++) {
                OrderCreated event = records.get(i).value();
                if (i == 47) {
                    log.error("[6-8] index={} 에서 실패. 지금까지 누적 처리 {}건",
                            i, totalHandled.get());
                    throw new IllegalStateException("결제 승인 거절: " + event.orderId());
                }
                totalHandled.incrementAndGet();   // 0~46 을 처리했다고 기록
            }
        }
    }

    /** ① ack.nack(index, Duration): 0 ~ index-1 을 커밋하고 index 부터 다시 읽습니다. */
    @Component
    @Profile("step06-batch-nack")
    public static class BatchNackListener {

        private static final Logger log = LoggerFactory.getLogger(BatchNackListener.class);

        private final AtomicInteger totalHandled = new AtomicInteger();
        private boolean alreadyFailedOnce = false;

        @KafkaListener(id = "s06-batch-nack", topics = TOPIC, groupId = "s06-batch-nack",
                containerFactory = "batchNackFactory", autoStartup = "false")
        public void onBatch(List<ConsumerRecord<String, OrderCreated>> records, Acknowledgment ack) {
            log.info("[6-8] 배치 수신 {}건 (offset {} ~ {})", records.size(),
                    records.get(0).offset(), records.get(records.size() - 1).offset());

            for (int i = 0; i < records.size(); i++) {
                OrderCreated event = records.get(i).value();
                if (i == 47 && !alreadyFailedOnce) {
                    alreadyFailedOnce = true;
                    log.warn("[6-8] index={} 실패 → nack(47, 1s). 0~46 은 커밋됩니다. 누적 {}건",
                            i, totalHandled.get());
                    ack.nack(i, Duration.ofSeconds(1));
                    return;                          // ⚠️ nack 뒤에는 반드시 즉시 리턴할 것
                }
                totalHandled.incrementAndGet();
                log.debug("[6-8] 처리 {}", event.orderId());
            }
            ack.acknowledge();                       // 배치를 다 처리했으면 전체 커밋
            log.info("[6-8] 배치 전체 커밋. 누적 처리 {}건", totalHandled.get());
        }
    }

    /** ② BatchListenerFailedException(message, index): DefaultErrorHandler 가 앞부분을 커밋해 줍니다. */
    @Component
    @Profile("step06-batch-failedex")
    public static class BatchFailedExceptionListener {

        private static final Logger log = LoggerFactory.getLogger(BatchFailedExceptionListener.class);

        private final AtomicInteger totalHandled = new AtomicInteger();
        private boolean alreadyFailedOnce = false;

        @KafkaListener(id = "s06-batch-failedex", topics = TOPIC, groupId = "s06-batch-failedex",
                containerFactory = "batchFailedExFactory", autoStartup = "false")
        public void onBatch(List<ConsumerRecord<String, OrderCreated>> records) {
            log.info("[6-8] 배치 수신 {}건 (offset {} ~ {})", records.size(),
                    records.get(0).offset(), records.get(records.size() - 1).offset());

            for (int i = 0; i < records.size(); i++) {
                ConsumerRecord<String, OrderCreated> record = records.get(i);
                if (i == 47 && !alreadyFailedOnce) {
                    alreadyFailedOnce = true;
                    log.warn("[6-8] index={} 실패 → BatchListenerFailedException. 누적 {}건",
                            i, totalHandled.get());
                    // 두 번째 인자가 "배치 안에서 실패한 위치"입니다. 이 정보 하나로
                    // DefaultErrorHandler 가 0~46 을 커밋하고 47 부터만 재시도합니다.
                    throw new BatchListenerFailedException(
                            "결제 승인 거절: " + record.value().orderId(), i);
                }
                totalHandled.incrementAndGet();
            }
            log.info("[6-8] 배치 완료. 누적 처리 {}건", totalHandled.get());
        }
    }

    // ========================================================================
    // [6-10] 커밋 실패를 관찰하기 위한 보조 — 리밸런스 콜백에서 커밋 위치 찍기
    // ========================================================================
    //
    // 리밸런스가 일어나면 파티션을 뺏긴 컨슈머의 커밋은 CommitFailedException 이 됩니다.
    // 여기서는 revoke 시점의 position 을 남겨, 되돌아간 오프셋을 확인할 수 있게 합니다.
    //
    @Component
    @Profile("step06")
    public static class CommitObserver {

        private static final Logger log = LoggerFactory.getLogger(CommitObserver.class);

        private final ObjectProvider<Consumer<?, ?>> unused;   // 문서화 목적의 자리표시자

        public CommitObserver(ObjectProvider<Consumer<?, ?>> unused) {
            this.unused = unused;
            log.debug("[6-10] CommitObserver 준비됨 (provider={})", this.unused);
        }
    }
}
