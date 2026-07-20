package com.example.order.step10;

/*
 * ============================================================================
 * Step 10 — 리스너 컨테이너 제어 : Solution (7문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step10/Solution.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step10sol,step10sol-load'
 *   문제 5(유휴 감지) 를 볼 때는 step10sol-load 를 빼세요.
 *   문제 4(리밸런스) 는 두 번째 인스턴스가 필요합니다.
 *     ./gradlew bootRun --args='--spring.profiles.active=step10sol --server.port=8081'
 *
 * 매 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s10-sol-batch  --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-sol-main   --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-sol-filter --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-sol-rebal  --topic orders --reset-offsets --to-earliest --execute
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.Consumer;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.event.ListenerContainerNoLongerIdleEvent;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Solution {

    private Solution() {
    }

    static final String TOPIC = "orders";

    @Component
    @Profile("step10sol-load")
    static class LoadGenerator implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

        private final KafkaTemplate<String, OrderCreated> template;
        private final AtomicInteger seq = new AtomicInteger(1);

        LoadGenerator(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                while (true) {
                    OrderCreated e = OrderCreated.of(seq.getAndIncrement());
                    template.send(TOPIC, e.orderId(), e);
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ie) {
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

    /* ========================================================================
     * 정답 1. autoStartup=false 리스너를 REST 로 켜고 끄기
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① autoStartup 은 String 입니다.
     *    boolean 이 아닌 이유는 "${app.listener.batch.enabled:false}" 같은
     *    프로퍼티 플레이스홀더를 쓸 수 있게 하기 위해서입니다. 환경별로 다른 값을
     *    주려면 이 형태가 유일한 방법입니다. 애노테이션 속성에는 상수만 들어갈 수
     *    있으므로 boolean 이었다면 플레이스홀더가 불가능했습니다.
     *
     * ② id 와 groupId 를 둘 다 명시해야 합니다.
     *    @KafkaListener 의 idIsGroup 기본값이 true 라서, id 만 주고 groupId 를
     *    생략하면 id 값이 그대로 group.id 가 됩니다. 그러면 컨슈머 그룹이
     *    "batchListener" 라는 이름으로 조용히 생기고, 여러분이 리셋한
     *    s10-sol-batch 그룹은 아무도 쓰지 않습니다. "리셋했는데 왜 처음부터 읽지"
     *    또는 "리셋했는데 왜 안 처음부터 읽지" 의 전형적 원인입니다.
     *
     * ③ 실측: start() 는 2ms 만에 리턴하고 isRunning() 은 즉시 true 지만,
     *    getAssignedPartitions() 는 이 시점에 빈 컬렉션입니다.
     *    partitions assigned 로그는 340ms 뒤에 나옵니다.
     *
     *      INFO ... start(batchListener) 리턴 t=2ms isRunning=true assigned=[]
     *      INFO ... [chListener-0-C-1] s10-sol-batch: partitions assigned: [orders-0, orders-1, orders-2]
     *      INFO ... [chListener-0-C-1] 할당 완료까지 t=340ms
     *
     *    "준비 완료"의 기준으로 무엇을 삼아야 하는가? 두 후보가 있습니다.
     *      - ConsumerStartedEvent : 컨슈머 스레드가 떴다는 뜻일 뿐, 파티션은 아직 없습니다.
     *      - onPartitionsAssigned : 파티션을 실제로 받았습니다.
     *    정답은 후자입니다. 앞의 것으로 판단하면 Step 11 에서 다룰 간헐 실패 테스트가
     *    그대로 만들어집니다.
     *
     * ④ getListenerContainer 는 없는 id 에 대해 예외가 아니라 null 을 리턴합니다.
     *    그래서 require() 로 감싸 "가능한 값" 목록까지 담은 예외를 던지게 했습니다.
     *    오타 하나에 NullPointerException 을 받는 것보다 훨씬 낫습니다.
     * ======================================================================== */
    @Component
    @Profile("step10sol")
    static class Answer1Listener {

        private static final Logger log = LoggerFactory.getLogger(Answer1Listener.class);

        static final AtomicLong START_AT = new AtomicLong();

        @KafkaListener(id = "batchListener",
                       groupId = "s10-sol-batch",     // ★ id 와 함께 반드시 명시
                       topics = TOPIC,
                       autoStartup = "false")         // ★ 문자열
        public void onMessage(OrderCreated event) {
            log.debug("배치 처리 {}", event.orderId());
        }
    }

    @Component
    @Profile("step10sol")
    static class Answer1AssignmentTimer {

        private static final Logger log = LoggerFactory.getLogger(Answer1AssignmentTimer.class);

        /** 팩토리 기본 리밸런스 리스너로는 못 잡으므로, 컨테이너 이벤트로 시각을 잰다. */
        @EventListener
        public void onIdleOrAssigned(org.springframework.kafka.event.ConsumerStartedEvent event) {
            long t0 = Answer1Listener.START_AT.get();
            if (t0 > 0) {
                log.info("ConsumerStartedEvent 까지 t={}ms (아직 파티션은 없습니다)",
                        System.currentTimeMillis() - t0);
            }
        }
    }

    @RestController
    @RequestMapping("/sol10/listeners")
    @Profile("step10sol")
    static class Answer1Controller {

        private static final Logger log = LoggerFactory.getLogger(Answer1Controller.class);

        private final KafkaListenerEndpointRegistry registry;

        Answer1Controller(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @PostMapping("/{id}/start")
        public String start(@PathVariable String id) {
            MessageListenerContainer c = require(id);
            long t0 = System.currentTimeMillis();
            Answer1Listener.START_AT.set(t0);

            c.start();

            long elapsed = System.currentTimeMillis() - t0;
            log.info("start({}) 리턴 t={}ms isRunning={} assigned={}",
                    id, elapsed, c.isRunning(), c.getAssignedPartitions());
            return "%s start t=%dms running=%s assigned=%s"
                    .formatted(id, elapsed, c.isRunning(), c.getAssignedPartitions());
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
                throw new IllegalArgumentException(
                        "그런 리스너 없음: " + id + " / 가능한 값: " + registry.getListenerContainerIds());
            }
            return c;
        }
    }

    /* ========================================================================
     * 정답 2. pause/resume 백프레셔
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① resume 은 반드시 컨슈머 스레드 바깥에서 호출해야 합니다.
     *    가장 흔한 오답이 이겁니다.
     *
     *      // ✗ 틀린 코드
     *      public void onMessage(OrderCreated e) {
     *          if (paused && downstreamHealthy()) {
     *              container.resume();          // 영원히 실행되지 않는다
     *          }
     *          ...
     *      }
     *
     *    pause 중에는 리스너가 호출되지 않습니다. 레코드가 인도되지 않으니까요.
     *    그러므로 "리스너 안에서 회복을 확인해 resume 한다"는 논리적으로 불가능합니다.
     *    TaskScheduler, @Scheduled, REST 엔드포인트 중 하나를 써야 합니다.
     *
     * ② pause 는 그룹을 떠나지 않습니다.
     *    같은 90초를 Thread.sleep 으로 버티면 max.poll.interval.ms 를 넘겨
     *    LeaveGroup → 리밸런스 → CommitFailedException → 같은 레코드 재처리의
     *    무한 루프에 빠집니다. pause 는 그 사이에도 poll() 을 계속 호출하고
     *    (레코드 0건) 그 덕에 poll interval 타이머가 매번 리셋됩니다.
     *
     *      DEBUG ... Received no records
     *      DEBUG ... Received no records
     *
     *    pause 중 kcg --describe 로 그룹 상태를 보면 여전히 Stable 입니다.
     *
     * ③ 실측 (max-poll-records=500, 레코드당 4ms)
     *
     *      INFO ... 연속 실패 5회 — 소비를 일시정지합니다
     *      INFO ... pause 요청 → 실제 정지 t=1843ms (그 사이 437건 더 처리됨)
     *
     *    pause() 를 부른 순간부터 실제로 멈추기까지 1.8초가 있고, 그 사이에
     *    437건이 더 처리됩니다. "pause 걸었으니 이제 안 들어온다"고 가정한
     *    배포 스크립트가 DDL 과 충돌하는 이유입니다.
     *    isContainerPaused() 가 true 가 될 때까지 반드시 기다리세요.
     *
     * ④ 왜 실패를 계속 던지는가?
     *    pause 를 걸어도 "지금 손에 든 배치"는 계속 처리됩니다. 그 레코드들이
     *    다운스트림 없이 성공 처리되면 안 되므로 예외를 그대로 다시 던져
     *    에러 핸들러(Step 07)가 재시도/DLT 를 결정하게 둡니다.
     * ======================================================================== */
    @Component
    @Profile("step10sol")
    static class Answer2BackPressure {

        private static final Logger log = LoggerFactory.getLogger(Answer2BackPressure.class);

        private final KafkaListenerEndpointRegistry registry;
        private final TaskScheduler scheduler;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean downstreamDown = new AtomicBoolean(true);

        Answer2BackPressure(KafkaListenerEndpointRegistry registry, TaskScheduler scheduler) {
            this.registry = registry;
            this.scheduler = scheduler;
            // 30초 뒤 다운스트림이 회복되는 상황을 흉내 낸다.
            scheduler.schedule(() -> {
                downstreamDown.set(false);
                log.info("(시뮬레이션) 다운스트림 회복");
            }, Instant.now().plusSeconds(30));
        }

        @KafkaListener(id = "mainListener", groupId = "s10-sol-main", topics = TOPIC)
        public void onMessage(OrderCreated event) {
            try {
                if (downstreamDown.get()) {
                    throw new IllegalStateException("재고 서비스 응답 없음: " + event.orderId());
                }
                consecutiveFailures.set(0);
                log.debug("처리 {}", event.orderId());
            } catch (RuntimeException e) {
                if (consecutiveFailures.incrementAndGet() >= 5 && paused.compareAndSet(false, true)) {
                    log.warn("연속 실패 {}회 — 소비를 일시정지합니다", consecutiveFailures.get());
                    pauseAndMeasure();
                    scheduleResume();
                }
                throw e;   // 에러 핸들러에 넘긴다
            }
        }

        /** pause 요청 → 실제 정지까지의 지연을 별도 스레드에서 측정한다. */
        private void pauseAndMeasure() {
            MessageListenerContainer c = registry.getListenerContainer("mainListener");
            long t0 = System.currentTimeMillis();
            c.pause();
            // ★ 컨슈머 스레드에서 기다리면 안 됩니다. 그 스레드가 배치를 처리해야 멈추니까요.
            //   여기서 대기하면 영원히 isContainerPaused() 가 true 가 되지 않습니다.
            scheduler.schedule(() -> {
                while (!c.isContainerPaused() && System.currentTimeMillis() - t0 < 30_000L) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                log.info("pause 요청 → 실제 정지 t={}ms", System.currentTimeMillis() - t0);
            }, Instant.now());
        }

        private void scheduleResume() {
            scheduler.schedule(() -> {
                if (downstreamDown.get()) {
                    log.info("다운스트림 응답 없음. 10초 후 재확인");
                    scheduleResume();
                    return;
                }
                log.info("다운스트림 회복 확인 — 소비를 재개합니다");
                registry.getListenerContainer("mainListener").resume();
                consecutiveFailures.set(0);
                paused.set(false);
            }, Instant.now().plusSeconds(10));
        }
    }

    /* ========================================================================
     * 정답 3. RecordFilterStrategy + ackDiscarded
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① filter() 가 true 면 버립니다.
     *    Javadoc 이 "Return true if the record should be discarded" 입니다.
     *    이름이 filter 라서 "필터를 통과하면 true" 로 읽기 쉽지만 반대입니다.
     *    조건을 뒤집어도 컴파일되고, 실행되고, 로그도 깔끔합니다.
     *    정확히 원하던 것만 버리고 나머지를 전부 처리합니다.
     *    그래서 람다 옆에 // true = discard 주석을 다는 것을 규칙으로 삼으세요.
     *
     * ② amount < 12000 을 버리면 OrderCreated.of(seq) 기준으로
     *    amount = 10000 + (seq % 7) * 1000 이므로 10000, 11000 두 값이 버려지고
     *    12000~16000 다섯 값이 통과합니다. 7건 중 5건, 약 71% 통과입니다.
     *
     * ③ ackDiscarded 실측 (100건 발행 후 kcg --describe --group s10-sol-filter)
     *
     *      ackDiscarded=true  → LAG 합계 0
     *      ackDiscarded=false → LAG 합계 4  ← 0 으로 안 떨어진다
     *
     *    여러분의 숫자가 4 가 아니어도 됩니다. 배치의 끝에 연속으로 버려진 레코드가
     *    몇 개 걸렸느냐에 따라 달라집니다. "0 이 아니다" 는 사실만 같으면 맞습니다.
     *
     *    이유: AckMode.MANUAL 에서 오프셋을 올리는 것은 ack.acknowledge() 뿐인데,
     *    필터에 걸린 레코드는 리스너를 타지 않으므로 ack 를 부를 사람이 없습니다.
     *    뒤에 통과한 레코드가 ack 되면 그 오프셋이 앞의 버려진 것까지 덮지만,
     *    배치의 마지막 몇 건이 연속으로 버려지면 그 구간이 커밋되지 않고 남습니다.
     *
     *    증상은 "LAG 이 4 에서 안 내려간다" 입니다. 처리는 정상인데 랙 알림만 웁니다.
     *    재기동하면 이미 버렸던 4건을 다시 받아 다시 버립니다.
     *
     * ④ 필터는 대역폭을 절약하지 않습니다.
     *    브로커는 이 필터의 존재를 모릅니다. 레코드는 전부 네트워크로 오고
     *    전부 역직렬화된 뒤 버려집니다. Micrometer 로 재면
     *    kafka.consumer.fetch.manager.bytes.consumed.rate 가 필터 전후로 동일합니다.
     *    진짜로 안 받으려면 발행 시점에 토픽을 나눠야 합니다.
     * ======================================================================== */
    @Configuration
    @Profile("step10sol")
    static class Answer3FilterConfig {

        static final BigDecimal THRESHOLD = new BigDecimal("12000");

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solFilteringFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);

            f.setRecordFilterStrategy(record ->
                    record.value().amount().compareTo(THRESHOLD) < 0);   // true = discard

            // ★ 이 한 줄을 빼면 LAG 이 0 으로 안 떨어집니다.
            f.setAckDiscarded(true);
            f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
            return f;
        }
    }

    @Component
    @Profile("step10sol")
    static class Answer3Listener {

        private static final Logger log = LoggerFactory.getLogger(Answer3Listener.class);

        private final AtomicLong passed = new AtomicLong();

        @KafkaListener(id = "solFilterListener", groupId = "s10-sol-filter", topics = TOPIC,
                       containerFactory = "solFilteringFactory")
        public void onHighValue(OrderCreated event, Acknowledgment ack) {
            log.info("고액 주문 {} {} (통과 {}건)",
                    event.orderId(), event.amount(), passed.incrementAndGet());
            ack.acknowledge();
        }
    }

    /* ========================================================================
     * 정답 4. ConsumerAwareRebalanceListener
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① 두 revoked 콜백이 나뉜 이유는 "커밋" 입니다.
     *
     *      onPartitionsRevokedBeforeCommit   ← 아직 이 파티션의 주인. 버퍼 flush 가능
     *      (Spring 이 오프셋 커밋)
     *      onPartitionsRevokedAfterCommit    ← 커밋 끝. 로컬 상태 정리
     *
     *    실측 로그
     *      INFO ... [revoked-before-commit] [orders-0, orders-1, orders-2] — 버퍼 flush
     *      INFO ... Revoke previously assigned partitions orders-0, orders-1, orders-2
     *      INFO ... [revoked-after-commit] [orders-0, orders-1, orders-2] — 파티션 캐시 삭제
     *      INFO ... Successfully joined group with generation Generation{generationId=2, ...}
     *      INFO ... [assigned] orders-0 position=1204
     *
     * ② onPartitionsLost 를 비워 두는 것이 정답입니다.
     *    이 콜백은 "이미 뺏긴 뒤" 통보입니다. max.poll.interval.ms 초과나 세션 만료로
     *    쫓겨났을 때 호출되고, 그 시점에 다른 컨슈머가 이미 그 파티션을 받아
     *    처리하고 있을 수 있습니다. 여기서 commitSync() 를 하면 낡은 오프셋으로
     *    남의 진행을 되돌립니다. 대개는 제너레이션 불일치로 CommitFailedException 이
     *    나지만, 타이밍이 맞으면 성공합니다. 그러면 대량 중복 처리가 발생하고
     *    로그에는 아무 흔적도 남지 않습니다.
     *
     * ③ 더 위험한 것은 Kafka 클라이언트의 기본 구현입니다.
     *    org.apache.kafka.clients.consumer.ConsumerRebalanceListener 의
     *    onPartitionsLost(Collection) default 구현은 onPartitionsRevoked 를
     *    그대로 호출합니다. 즉 ConsumerRebalanceListener 를 직접 구현하면서
     *    onPartitionsRevoked 에 커밋 로직을 넣었다면, lost 상황에 그 커밋이
     *    자동으로 실행됩니다.
     *    Spring 의 ConsumerAwareRebalanceListener 는 이 default 를
     *    "아무것도 안 함" 으로 덮어 두었습니다. 반드시 Spring 쪽 인터페이스를 쓰세요.
     *
     * ④ onPartitionsAssigned 에서 pause 를 재적용하는 이유
     *    pausePartition 은 "지금 할당된 파티션에 대한 지시" 입니다.
     *    리밸런스로 회수됐다 재할당되면 재개된 상태로 돌아옵니다.
     *    멈춰 둔 파티션 목록은 애플리케이션이 직접 들고 있다가 여기서 다시 걸어야 합니다.
     * ======================================================================== */
    @Configuration
    @Profile("step10sol")
    static class Answer4RebalanceConfig {

        private static final Logger log = LoggerFactory.getLogger(Answer4RebalanceConfig.class);

        /** 멈춰 둔 파티션. 리밸런스 후 재적용하려고 애플리케이션이 직접 기억한다. */
        static final Set<TopicPartition> PAUSED = ConcurrentHashMap.newKeySet();

        private final Map<TopicPartition, Long> partitionCache = new ConcurrentHashMap<>();

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solRebalanceFactory(
                ConsumerFactory<String, OrderCreated> cf,
                KafkaListenerEndpointRegistry registry) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);
            f.getContainerProperties().setIdleEventInterval(20_000L);      // 정답 5 에서 사용
            f.getContainerProperties().setShutdownTimeout(10_000L);        // 정답 7 에서 사용

            f.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {

                @Override
                public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer,
                                                            Collection<TopicPartition> parts) {
                    log.info("[revoked-before-commit] {} — 버퍼 flush", parts);
                }

                @Override
                public void onPartitionsRevokedAfterCommit(Consumer<?, ?> consumer,
                                                           Collection<TopicPartition> parts) {
                    log.info("[revoked-after-commit] {} — 파티션 캐시 삭제", parts);
                    parts.forEach(partitionCache::remove);
                }

                @Override
                public void onPartitionsAssigned(Consumer<?, ?> consumer,
                                                 Collection<TopicPartition> parts) {
                    parts.forEach(tp -> {
                        long pos = consumer.position(tp);
                        partitionCache.put(tp, pos);
                        log.info("[assigned] {} position={}", tp, pos);
                    });
                    // 리밸런스가 pause 상태를 초기화하므로 다시 걸어 준다.
                    MessageListenerContainer c = registry.getListenerContainer("solRebalanceListener");
                    if (c != null) {
                        PAUSED.stream().filter(parts::contains).forEach(tp -> {
                            c.pausePartition(tp);
                            log.warn("[assigned] pause 재적용 {}", tp);
                        });
                    }
                }

                @Override
                public void onPartitionsLost(Consumer<?, ?> consumer,
                                             Collection<TopicPartition> parts) {
                    // ★ 커밋하지 않는다.
                    //   이미 다른 컨슈머가 이 파티션을 가져가 처리 중일 수 있다.
                    //   여기서 commitSync() 가 성공해 버리면 그 컨슈머의 진행을
                    //   낡은 오프셋으로 되돌려 대량 중복 처리를 만든다.
                    //   seek 도 외부 호출도 금지. 로컬 상태만 버린다.
                    log.error("[LOST] {} — 커밋하지 않고 로컬 상태만 버립니다", parts);
                    parts.forEach(partitionCache::remove);
                }
            });
            return f;
        }
    }

    @Component
    @Profile("step10sol")
    static class Answer4Listener {

        private static final Logger log = LoggerFactory.getLogger(Answer4Listener.class);

        private final AtomicLong count = new AtomicLong();

        @KafkaListener(id = "solRebalanceListener", groupId = "s10-sol-rebal", topics = TOPIC,
                       containerFactory = "solRebalanceFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            long n = count.incrementAndGet();
            if (n % 20 == 0) {
                log.info("처리 {}-{}@{} (누적 {}건)",
                        record.topic(), record.partition(), record.offset(), n);
            }
        }
    }

    /* ========================================================================
     * 정답 5. ListenerContainerIdleEvent 로 유휴 감지
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① 랙 모니터링이 못 잡는 장애가 있습니다.
     *    업스트림이 죽어 발행이 아예 끊기면 LAG 은 0 이고, 컨슈머는 Stable 이고,
     *    에러 로그도 없습니다. 모든 대시보드가 초록색인데 아무 일도 일어나지 않습니다.
     *    ListenerContainerIdleEvent 는 그 침묵을 감지하는 유일한 신호입니다.
     *
     * ② @Async 가 이 문제의 핵심입니다.
     *    @EventListener 메서드는 이벤트를 발행한 스레드에서 동기 실행됩니다.
     *    이벤트를 발행하는 것은 컨슈머 스레드입니다. 즉 핸들러가 5초 걸리면
     *    그 5초 동안 poll() 이 멈춥니다. 알림 서버가 죽어 타임아웃 30초가 나면
     *    max.poll.interval.ms 를 향해 달려갑니다.
     *    "알림 시스템 장애가 카프카 리밸런스 폭풍으로 번졌다" 가 이렇게 만들어집니다.
     *
     *    로그로 확인할 수 있습니다.
     *
     *      @Async 없음:  WARN ... [lListener-0-C-1] 유휴 감지 thread=solRebalanceListener-0-C-1
     *      @Async 있음:  WARN ... [         task-1] 유휴 감지 thread=task-1
     *
     *    스레드 이름 하나의 차이가 장애 전파를 막습니다.
     *
     * ③ @Async 는 @EnableAsync 없이는 아무 일도 하지 않습니다.
     *    애노테이션만 붙이고 @EnableAsync 를 빠뜨리면 조용히 동기 실행됩니다.
     *    예외도 경고도 없습니다. 그래서 스레드 이름을 반드시 함께 찍어야 합니다.
     *
     * ④ listenerId 가 "solRebalanceListener-0", "-1", "-2" 로 나옵니다.
     *    자식 컨테이너(= 컨슈머) 단위로 발행되므로 concurrency=3 이면 3개씩 옵니다.
     *    알림을 보낼 때 이걸 그대로 보내면 같은 장애에 3배로 울립니다.
     *    listenerId 의 접미사를 떼고 집계하거나, 전체가 유휴일 때만 알리세요.
     *
     * ⑤ idleEventInterval 값은 "정상 유입 간격"의 3~5배가 적당합니다.
     *    5초에 한 번 들어오는 토픽이면 20~30초. 너무 짧으면 새벽마다 울리고
     *    너무 길면 감지가 늦습니다. 트래픽이 원래 없는 야간 배치 토픽은
     *    알림 조건에서 제외해야 합니다.
     * ======================================================================== */
    @Configuration
    @EnableAsync
    @Profile("step10sol")
    static class Answer5AsyncConfig {
    }

    @Component
    @Profile("step10sol")
    static class Answer5IdleWatcher {

        private static final Logger log = LoggerFactory.getLogger(Answer5IdleWatcher.class);

        @EventListener
        @Async
        public void onIdle(ListenerContainerIdleEvent event) {
            log.warn("유휴 감지 thread={} listenerId={} idle={}s partitions={}",
                    Thread.currentThread().getName(),
                    event.getListenerId(),
                    event.getIdleTime() / 1000,
                    event.getTopicPartitions());

            if (event.getIdleTime() > 60_000L) {
                log.error("★ orders 유입 {}초간 없음 — 업스트림 확인 필요", event.getIdleTime() / 1000);
            }
        }

        @EventListener
        @Async
        public void onNoLongerIdle(ListenerContainerNoLongerIdleEvent event) {
            log.info("유휴 해제 thread={} listenerId={} 마지막 유휴 {}s",
                    Thread.currentThread().getName(),
                    event.getListenerId(),
                    event.getIdleTime() / 1000);
        }
    }

    /* ========================================================================
     * 정답 6. ConsumerSeekAware 로 특정 타임스탬프부터 재처리
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① AbstractConsumerSeekAware 를 상속하는 것이 정답입니다.
     *    ConsumerSeekAware 를 직접 구현하면 registerSeekCallback 이 넘겨주는
     *    콜백을 스스로 관리해야 하는데, 이 콜백은 컨슈머 스레드마다 하나씩 옵니다.
     *
     *      // ✗ 틀린 코드
     *      private ConsumerSeekCallback callback;          // 필드 하나
     *      public void registerSeekCallback(ConsumerSeekCallback cb) {
     *          this.callback = cb;                          // 마지막 것만 남는다
     *      }
     *
     *    concurrency=3 이면 이 메서드가 3번 호출되고 필드에는 마지막 하나만 남습니다.
     *    그 콜백으로 orders-0, orders-1, orders-2 를 전부 seek 하면?
     *    자기가 담당하지 않는 파티션의 seek 요청은 조용히 무시되어,
     *    실제로는 파티션 3개 중 1개만 되감깁니다.
     *
     *      INFO ... Seeking to offset 10 for partition orders-2    ← 이 줄만 나온다
     *
     *    AbstractConsumerSeekAware 는 파티션별로 콜백을 맵에 담아 주므로
     *    getSeekCallbacks() 로 순회하면 세 파티션 모두 정확히 걸립니다.
     *
     *      INFO ... Seeking to offset 10 for partition orders-0
     *      INFO ... Seeking to offset 10 for partition orders-1
     *      INFO ... Seeking to offset 10 for partition orders-2
     *
     * ② onPartitionsAssigned 를 오버라이드했다면 super 를 반드시 호출해야 합니다.
     *    AbstractConsumerSeekAware 가 콜백 맵을 갱신하는 곳이 거기입니다.
     *    빠뜨리면 getSeekCallbacks() 가 영원히 비어 있고,
     *    "아직 할당된 파티션이 없습니다" 만 반복됩니다.
     *
     * ③ 여기서 seekToBeginning 을 부르면 무한 재처리입니다.
     *    onPartitionsAssigned 는 기동 시에만이 아니라 리밸런스마다 호출됩니다.
     *    배포, 스케일아웃, 짧은 네트워크 단절 전부 리밸런스입니다.
     *    되감기는 명시적 운영 명령(REST)으로만 하세요.
     *
     * ④ seekToTimestamp 는 ConsumerRecord.timestamp() 를 봅니다.
     *    페이로드 JSON 의 createdAt 필드가 아닙니다. 브로커가 인덱싱하는 것은
     *    토픽 설정 message.timestamp.type 에 따른 CreateTime(프로듀서가 찍은 시각,
     *    기본값) 또는 LogAppendTime(브로커 수신 시각)입니다.
     *    과거 이벤트를 오늘 백필한 토픽에 시각 기반 seek 을 걸면 원하는 구간을
     *    절대 못 찾습니다. 그럴 때는 오프셋으로 직접 지정하세요.
     * ======================================================================== */
    @Component
    @Profile("step10sol")
    static class Answer6ReprocessTool extends AbstractConsumerSeekAware {

        private static final Logger log = LoggerFactory.getLogger(Answer6ReprocessTool.class);

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

        @Override
        public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                         ConsumerSeekCallback callback) {
            super.onPartitionsAssigned(assignments, callback);   // ★ 반드시 호출
            log.info("[seek-aware] 할당 {}", assignments.keySet());
            // callback.seekToBeginning(...);   ← 리밸런스마다 처음부터. 하지 마세요.
        }
    }

    @RestController
    @RequestMapping("/sol10")
    @Profile("step10sol")
    static class Answer6Controller {

        private final Answer6ReprocessTool tool;

        Answer6Controller(Answer6ReprocessTool tool) {
            this.tool = tool;
        }

        @PostMapping("/reprocess")
        public String reprocess(@RequestParam String from) {
            tool.reprocessFrom(Instant.parse(from));
            return "seek requested from " + from;
        }
    }

    /* ========================================================================
     * 정답 7. 우아한 종료 — stop(Runnable) vs stopImmediate
     * ========================================================================
     *
     * 왜 이 답인가
     *
     * ① getAllListenerContainers() 를 써야 합니다.
     *    getListenerContainers() 는 @KafkaListener 로 만들어진 것만 돌려줍니다.
     *    ConcurrentMessageListenerContainer 를 @Bean 으로 직접 등록한 코드가
     *    섞여 있으면 그건 목록에서 빠집니다. "전부 멈춰라" 가 일부만 멈춥니다.
     *
     * ② stop() 은 논블로킹이고 isRunning() 은 즉시 false 가 됩니다.
     *    실제 종료는 현재 배치를 다 처리한 뒤입니다. 완료 시각을 알려면
     *    stop(Runnable) 의 콜백을 써야 합니다. pause 와 정확히 같은 구조입니다.
     *
     * ③ 실측 (max-poll-records=500, 레코드당 4ms, 100건 발행 후 종료)
     *
     *      immediate=false → stopReturned 2ms  / stopCompleted 1712ms / 재기동 후 중복 0건
     *      immediate=true  → stopReturned 1ms  / stopCompleted   38ms / 재기동 후 중복 417건
     *
     *    45배 빠른 종료의 대가가 417건의 중복입니다.
     *    stopImmediate 는 현재 레코드까지만 처리하고 배치의 나머지를 버립니다.
     *    버려진 레코드는 커밋되지 않았으므로 유실은 없습니다. 다음 기동 때 다시 옵니다.
     *    즉 stopImmediate 의 트레이드오프는 "유실 vs 중복" 이 아니라
     *    "종료 시간 vs 중복" 입니다.
     *
     * ④ shutdownTimeout 을 넘기면 커밋 없이 스레드가 버려집니다.
     *    리스너 하나가 오래 걸려 기본값 10초 안에 못 끝나면 컨테이너는 기다리기를
     *    포기합니다. 그 시점에 처리 중이던 레코드의 오프셋은 커밋되지 않습니다.
     *    배포할 때마다 특정 주문이 두 번 처리되는데 로그에는 Consumer stopped 만
     *    남아 정상 종료로 보이는 상황이 이렇게 만들어집니다.
     *
     *    쿠버네티스를 쓴다면 shutdownTimeout 은 반드시
     *    terminationGracePeriodSeconds 보다 작아야 합니다.
     *    30초 뒤 SIGKILL 이 날아오는 환경에서 shutdownTimeout 60초는 무의미합니다.
     *
     * ⑤ 결론: 빠른 종료를 택했다면 멱등성은 선택이 아니라 필수입니다.
     *    417건의 중복을 안전하게 만드는 유일한 방법은 컨슈머를 멱등하게 만드는 것이고,
     *    그 구현(processed_message 테이블, message_id UNIQUE)은 Step 13 에서 다룹니다.
     * ======================================================================== */
    @RestController
    @RequestMapping("/sol10")
    @Profile("step10sol")
    static class Answer7ShutdownController {

        private static final Logger log = LoggerFactory.getLogger(Answer7ShutdownController.class);

        private final KafkaListenerEndpointRegistry registry;

        Answer7ShutdownController(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @PostMapping("/shutdown")
        public Map<String, Object> shutdown(
                @RequestParam(defaultValue = "false") boolean immediate) throws InterruptedException {

            // ★ getListenerContainers() 가 아니라 getAllListenerContainers()
            Collection<MessageListenerContainer> all = registry.getAllListenerContainers();
            CountDownLatch latch = new CountDownLatch(all.size());
            long t0 = System.currentTimeMillis();

            for (MessageListenerContainer c : all) {
                c.getContainerProperties().setStopImmediate(immediate);
                c.stop(latch::countDown);
            }
            long returned = System.currentTimeMillis() - t0;
            log.info("stop() 리턴 t={}ms (컨테이너 {}개) isRunning={}",
                    returned, all.size(),
                    all.stream().map(MessageListenerContainer::isRunning).toList());

            boolean done = latch.await(30, TimeUnit.SECONDS);
            long completed = System.currentTimeMillis() - t0;
            log.info("컨테이너 정지 완료 t={}ms immediate={} (timeout 없이 완료={})",
                    completed, immediate, done);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stopImmediate", immediate);
            out.put("stopReturnedMs", returned);
            out.put("stopCompletedMs", completed);
            out.put("completedWithinTimeout", done);
            return out;
        }
    }

    @Configuration
    @EnableScheduling
    @Profile("step10sol")
    static class SchedulingConfig {
    }
}
