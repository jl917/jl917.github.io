package com.example.order.step10;

/*
 * ============================================================================
 * Step 10 — 리스너 컨테이너 제어 : Exercise (7문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step10/Exercise.java
 *
 * 사전 준비
 *   build.gradle 에 웹 스타터가 있어야 합니다.
 *       implementation 'org.springframework.boot:spring-boot-starter-web'
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step10ex'
 *   문제 4 는 두 번째 인스턴스가 필요합니다. 포트를 반드시 바꾸세요.
 *   ./gradlew bootRun --args='--spring.profiles.active=step10ex --server.port=8081'
 *
 * 매 실행 전 오프셋 리셋 (앱을 먼저 종료할 것)
 *   kcg --group s10-ex-batch    --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-ex-main     --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-ex-filter   --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-ex-rebal    --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s10-ex-seek     --topic orders --reset-offsets --to-earliest --execute
 *
 * 각 문제 끝의 "// 확인:" 줄이 콘솔에 안 나오면 답이 틀린 것입니다.
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Exercise {

    private Exercise() {
    }

    static final String TOPIC = "orders";

    // ------------------------------------------------------------------------
    // [공통] 부하 생성기 — 초당 5건. 문제 5(유휴 감지) 를 풀 때는 이 프로필을 끄세요.
    // ------------------------------------------------------------------------
    @Component
    @Profile("step10ex-load")
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

    // ========================================================================
    // 문제 1. autoStartup=false 리스너를 REST 로 켜고 끄기
    // ========================================================================
    //
    // 요구사항
    //   - id="batchListener", groupId="s10-ex-batch", topics="orders" 리스너를 만들되
    //     애플리케이션 기동 시에는 뜨지 않게 한다.
    //   - POST /ex10/listeners/{id}/start 와 /stop 을 만든다.
    //   - start() 호출 시각을 기록하고, ConsumerAwareRebalanceListener 또는
    //     로그 타임스탬프로 "partitions assigned" 까지 몇 ms 걸렸는지 측정해 남긴다.
    //   - start() 직후의 isRunning() 값과 getAssignedPartitions() 값을 함께 로그로 찍는다.
    //
    // 힌트
    //   - autoStartup 은 boolean 이 아니라 String 입니다.
    //   - 컨테이너는 KafkaListenerEndpointRegistry 로만 꺼낼 수 있습니다.
    //     getListenerContainer 는 없으면 예외가 아니라 null 을 리턴합니다.
    //
    // 확인: start(batchListener) 리턴 t=2ms isRunning=true assigned=[]
    //       그리고 수백 ms 뒤에  s10-ex-batch: partitions assigned: [orders-0, orders-1, orders-2]
    // ------------------------------------------------------------------------
    @Component
    @Profile("step10ex")
    static class Problem1Listener {

        private static final Logger log = LoggerFactory.getLogger(Problem1Listener.class);

        // 여기에 작성: @KafkaListener 선언 (id, groupId, topics, autoStartup)
        public void onMessage(OrderCreated event) {
            log.info("배치 처리 {}", event.orderId());
        }
    }

    @RestController
    @RequestMapping("/ex10/listeners")
    @Profile("step10ex")
    static class ExerciseController {

        private static final Logger log = LoggerFactory.getLogger(ExerciseController.class);

        private final KafkaListenerEndpointRegistry registry;

        ExerciseController(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @PostMapping("/{id}/start")
        public String start(@PathVariable String id) {
            // 여기에 작성:
            //   1) registry 에서 컨테이너를 꺼내고 null 이면 가능한 id 목록과 함께 예외를 던진다
            //   2) t0 을 기록하고 start() 를 호출한다
            //   3) isRunning() 과 getAssignedPartitions() 를 로그로 남긴다
            return "TODO";
        }

        @PostMapping("/{id}/stop")
        public String stop(@PathVariable String id) {
            // 여기에 작성:
            return "TODO";
        }
    }

    // ========================================================================
    // 문제 2. pause/resume 으로 백프레셔 구현하기
    // ========================================================================
    //
    // 요구사항
    //   - id="mainListener", groupId="s10-ex-main" 리스너를 만든다.
    //   - 다운스트림 호출(fakeDownstream)이 연속 5회 실패하면 컨테이너를 pause() 한다.
    //   - TaskScheduler 로 10초마다 회복 여부를 확인해, 회복되면 resume() 한다.
    //   - pause() 호출 시각부터 isContainerPaused() 가 true 가 될 때까지의 ms 를 측정해 찍는다.
    //
    // ⚠️ 함정: resume() 을 리스너 안에서 호출하려 하지 마세요.
    //    pause 중에는 리스너가 아예 호출되지 않으므로 그 코드는 영원히 실행되지 않습니다.
    //    반드시 컨슈머 스레드 바깥(TaskScheduler, REST)에서 resume 해야 합니다.
    //
    // 확인: pause 요청 → 실제 정지 t=NNNNms (그 사이 NNN건 더 처리됨)
    //       그리고 로그에 리밸런스가 한 번도 없어야 합니다.
    // ------------------------------------------------------------------------
    @Component
    @Profile("step10ex")
    static class Problem2BackPressure {

        private static final Logger log = LoggerFactory.getLogger(Problem2BackPressure.class);

        private final KafkaListenerEndpointRegistry registry;
        private final TaskScheduler scheduler;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicBoolean downstreamDown = new AtomicBoolean(true);

        Problem2BackPressure(KafkaListenerEndpointRegistry registry, TaskScheduler scheduler) {
            this.registry = registry;
            this.scheduler = scheduler;
        }

        @KafkaListener(id = "mainListener", groupId = "s10-ex-main", topics = TOPIC)
        public void onMessage(OrderCreated event) {
            try {
                fakeDownstream(event);
                consecutiveFailures.set(0);
            } catch (RuntimeException e) {
                // 여기에 작성:
                //   연속 실패가 5회에 도달하면 pause() 하고, 지연을 측정한 뒤
                //   scheduleResume() 을 호출한다.
                throw e;
            }
        }

        /** 회복까지 걸리는 시간을 흉내 낸다. 30초 뒤 자동 회복. */
        private void fakeDownstream(OrderCreated event) {
            if (downstreamDown.get()) {
                throw new IllegalStateException("재고 서비스 응답 없음: " + event.orderId());
            }
        }

        private void scheduleResume() {
            // 여기에 작성:
            //   scheduler.schedule 로 10초 뒤 회복을 확인하고,
            //   회복됐으면 resume(), 아니면 다시 스케줄한다.
        }
    }

    // ========================================================================
    // 문제 3. RecordFilterStrategy 로 12000 미만 주문 버리기 + ackDiscarded 대조
    // ========================================================================
    //
    // 요구사항
    //   - amount < 12000 인 주문을 리스너에 도달하기 전에 버린다.
    //   - AckMode.MANUAL 을 쓰고, 리스너에서 ack.acknowledge() 를 호출한다.
    //   - ackDiscarded 를 true / false 로 각각 실행해 kcg --describe 의 LAG 을 비교한다.
    //
    // ⚠️ filter() 가 true 를 리턴하면 "통과"가 아니라 "버림"입니다.
    //    조건을 반대로 쓰면 에러 없이 정확히 반대로 동작합니다.
    //
    // 관측 기록: (kcg --describe --group s10-ex-filter 의 LAG 합계를 적으세요)
    //   ackDiscarded=true  → LAG = ____
    //   ackDiscarded=false → LAG = ____
    //   두 값이 같게 나왔다면 AckMode.MANUAL 이 실제로 걸렸는지 다시 확인하세요.
    //
    // 확인: 고액 주문 ORD-0002 12000   (ORD-0001 11000 은 안 나와야 합니다)
    // ------------------------------------------------------------------------
    @Configuration
    @Profile("step10ex")
    static class Problem3FilterConfig {

        static final BigDecimal THRESHOLD = new BigDecimal("12000");

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> exFilteringFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);
            // 여기에 작성:
            //   1) setRecordFilterStrategy — true = discard 임에 주의
            //   2) setAckDiscarded
            //   3) getContainerProperties().setAckMode(MANUAL)
            return f;
        }
    }

    @Component
    @Profile("step10ex")
    static class Problem3Listener {

        private static final Logger log = LoggerFactory.getLogger(Problem3Listener.class);

        private final AtomicLong passed = new AtomicLong();

        @KafkaListener(id = "exFilterListener", groupId = "s10-ex-filter", topics = TOPIC,
                       containerFactory = "exFilteringFactory")
        public void onHighValue(OrderCreated event, Acknowledgment ack) {
            log.info("고액 주문 {} {} (통과 {}건)", event.orderId(), event.amount(), passed.incrementAndGet());
            ack.acknowledge();
        }
    }

    // ========================================================================
    // 문제 4. ConsumerAwareRebalanceListener 로 리밸런스 4단계 로깅
    // ========================================================================
    //
    // 요구사항
    //   - 네 콜백을 모두 구현해 [revoked-before-commit] / [revoked-after-commit] /
    //     [assigned] / [LOST] 태그로 로그를 남긴다.
    //   - onPartitionsAssigned 에서는 consumer.position(tp) 를 함께 찍는다.
    //   - onPartitionsLost 에서 커밋하지 않는 이유를 주석으로 남긴다.
    //
    // 실행 방법
    //   같은 프로필로 두 번째 인스턴스를 띄워 리밸런스를 유발합니다.
    //   ⚠️ 포트를 반드시 바꾸세요. 안 그러면 Port 8080 was already in use 로 안 뜹니다.
    //   ./gradlew bootRun --args='--spring.profiles.active=step10ex --server.port=8081'
    //
    // 확인: [revoked-before-commit] → [revoked-after-commit] → [assigned] 순서
    // ------------------------------------------------------------------------
    @Configuration
    @Profile("step10ex")
    static class Problem4RebalanceConfig {

        private static final Logger log = LoggerFactory.getLogger(Problem4RebalanceConfig.class);

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, OrderCreated> exRebalanceFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);

            // 문제 5 에서 쓸 유휴 이벤트 간격도 여기서 켭니다.
            f.getContainerProperties().setIdleEventInterval(20_000L);

            // 여기에 작성:
            //   f.getContainerProperties().setConsumerRebalanceListener(
            //       new ConsumerAwareRebalanceListener() { ... 네 콜백 ... });
            return f;
        }
    }

    @Component
    @Profile("step10ex")
    static class Problem4Listener {

        private static final Logger log = LoggerFactory.getLogger(Problem4Listener.class);

        private final AtomicLong count = new AtomicLong();

        @KafkaListener(id = "exRebalanceListener", groupId = "s10-ex-rebal", topics = TOPIC,
                       containerFactory = "exRebalanceFactory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            long n = count.incrementAndGet();
            if (n % 20 == 0) {
                log.info("처리 {}-{}@{} (누적 {}건)",
                        record.topic(), record.partition(), record.offset(), n);
            }
        }
    }

    // ========================================================================
    // 문제 5. ListenerContainerIdleEvent 로 유휴 감지
    // ========================================================================
    //
    // 요구사항
    //   - idleEventInterval=20000 (문제 4 의 팩토리에 이미 걸려 있습니다)
    //   - ListenerContainerIdleEvent 를 받아 listenerId / idleTime / topicPartitions 를 찍는다.
    //   - idleTime 이 60초를 넘으면 ERROR 로 경고를 남긴다.
    //   - ListenerContainerNoLongerIdleEvent 로 해제를 기록한다.
    //   - 핸들러를 @Async 로 빼고, 현재 스레드 이름을 함께 찍어 컨슈머 스레드가 아님을 확인한다.
    //
    // ⚠️ @Async 는 @EnableAsync 가 있어야 동작합니다. 아래 AsyncConfig 를 완성하세요.
    //    @Async 없이도 로그는 똑같이 나옵니다. 스레드 이름을 찍어야 차이가 보입니다.
    //
    // 실행: step10ex-load 프로필을 빼고 실행해야 유휴 이벤트가 나옵니다.
    //
    // 확인: 유휴 감지 thread=task-1 listenerId=exRebalanceListener-0 idle=20s
    //       (thread 가 exRebalanceListener-0-C-1 이면 @Async 가 안 걸린 것입니다)
    // ------------------------------------------------------------------------
    @Configuration
    // 여기에 작성: @EnableAsync
    @Profile("step10ex")
    static class AsyncConfig {
    }

    @Component
    @Profile("step10ex")
    static class Problem5IdleWatcher {

        private static final Logger log = LoggerFactory.getLogger(Problem5IdleWatcher.class);

        @EventListener
        // 여기에 작성: @Async
        public void onIdle(ListenerContainerIdleEvent event) {
            // 여기에 작성:
            //   Thread.currentThread().getName() 과 함께 listenerId, idleTime, topicPartitions 를 찍고
            //   idleTime > 60_000 이면 ERROR 로 경고한다.
        }

        @EventListener
        // 여기에 작성: @Async
        public void onNoLongerIdle(ListenerContainerNoLongerIdleEvent event) {
            // 여기에 작성:
        }
    }

    // ========================================================================
    // 문제 6. ConsumerSeekAware 로 특정 타임스탬프부터 재처리
    // ========================================================================
    //
    // 요구사항
    //   - AbstractConsumerSeekAware 를 상속한 컴포넌트를 만든다.
    //   - POST /ex10/reprocess?from=2025-01-01T00:10:00Z 로 호출하면
    //     할당된 모든 파티션을 그 시각으로 seek 한다.
    //   - OrderCreated.of(seq).createdAt = 2025-01-01T00:00:00Z + seq분 이므로
    //     00:10:00Z 는 ORD-0010 입니다.
    //
    // ⚠️ registerSeekCallback 이 넘겨주는 "단일" 콜백을 필드에 저장하면
    //    concurrency=3 에서 마지막 것 하나만 남아 파티션 1개에만 seek 이 걸립니다.
    //    getSeekCallbacks() (파티션별 맵) 를 쓰세요.
    //
    // ⚠️ seekToTimestamp 는 ConsumerRecord.timestamp() 를 봅니다.
    //    JSON 페이로드의 createdAt 필드가 아닙니다. 이 실습은 발행 시각과 페이로드 시각이
    //    다르므로, 기대한 위치로 안 가면 kcc --property print.timestamp=true 로 확인하세요.
    //
    // 확인: Seeking to offset 10 for partition orders-0
    // ------------------------------------------------------------------------
    @Component
    @Profile("step10ex")
    static class Problem6ReprocessTool extends AbstractConsumerSeekAware {

        private static final Logger log = LoggerFactory.getLogger(Problem6ReprocessTool.class);

        void reprocessFrom(Instant from) {
            // 여기에 작성:
            //   getSeekCallbacks() 를 순회하며 seekToTimestamp 를 호출한다.
            //   비어 있으면 "아직 할당된 파티션이 없습니다" 경고를 남긴다.
        }
    }

    @RestController
    @RequestMapping("/ex10")
    @Profile("step10ex")
    static class Problem6Controller {

        private final Problem6ReprocessTool tool;

        Problem6Controller(Problem6ReprocessTool tool) {
            this.tool = tool;
        }

        @PostMapping("/reprocess")
        public String reprocess(@RequestParam String from) {
            // 여기에 작성:
            return "TODO";
        }
    }

    // ========================================================================
    // 문제 7. 우아한 종료 — stop(Runnable) vs stopImmediate
    // ========================================================================
    //
    // 요구사항
    //   - POST /ex10/shutdown?immediate=false 로 모든 컨테이너를 멈추고
    //     stop() 리턴까지의 ms 와 실제 종료까지의 ms 를 각각 측정한다.
    //   - immediate=true 로 바꿔 같은 실험을 반복한다.
    //   - 각각 재기동한 뒤 "이미 처리했던 주문이 다시 온 건수"를 세어 비교한다.
    //
    // 힌트
    //   - registry.getAllListenerContainers() 를 쓰세요.
    //     getListenerContainers() 는 직접 @Bean 으로 등록한 컨테이너를 빠뜨립니다.
    //   - stopImmediate 는 ContainerProperties 에 있습니다.
    //   - 중복 건수를 세려면 처리한 orderId 를 파일이나 로그로 남겨 두었다가 대조하세요.
    //
    // 관측 기록:
    //   immediate=false → stopReturned ____ms / stopCompleted ____ms / 재기동 후 중복 ____건
    //   immediate=true  → stopReturned ____ms / stopCompleted ____ms / 재기동 후 중복 ____건
    //
    // 확인: 컨테이너 정지 완료 t=NNNNms immediate=false
    // ------------------------------------------------------------------------
    @RestController
    @RequestMapping("/ex10")
    @Profile("step10ex")
    static class Problem7ShutdownController {

        private static final Logger log = LoggerFactory.getLogger(Problem7ShutdownController.class);

        private final KafkaListenerEndpointRegistry registry;

        Problem7ShutdownController(KafkaListenerEndpointRegistry registry) {
            this.registry = registry;
        }

        @PostMapping("/shutdown")
        public Map<String, Object> shutdown(
                @RequestParam(defaultValue = "false") boolean immediate) throws InterruptedException {

            Map<String, Object> out = new LinkedHashMap<>();
            // 여기에 작성:
            //   1) getAllListenerContainers() 를 순회하며 stopImmediate 를 설정
            //   2) CountDownLatch 로 stop(Runnable) 완료를 기다린다
            //   3) stop() 리턴 시각과 완료 시각을 각각 out 에 담는다
            return out;
        }
    }

    @Configuration
    @EnableScheduling
    @Profile("step10ex")
    static class SchedulingConfig {
    }
}
