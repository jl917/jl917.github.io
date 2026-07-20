package com.example.order.step08;

/*
 * ============================================================================
 * Step 08 — @RetryableTopic 논블로킹 재시도 : Solution (6문제 정답 + 해설)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step08/Solution.java
 *
 * 실행 (Exercise.java 와 프로필 이름이 다릅니다. 둘 다 두어도 충돌하지 않습니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,sol08-q1'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,sol08-q2'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,sol08-q3'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,sol08-q4'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,sol08-q5'
 *   ./gradlew bootRun --args='--spring.profiles.active=step08,sol08-q6'
 *
 * ★ 매 실행 전 오프셋 리셋 + retry 토픽 정리
 *   kcg --group s08-sol-inventory   --all-topics --reset-offsets --to-earliest --execute
 *   kcg --group s08-sol-order-state --all-topics --reset-offsets --to-earliest --execute
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Solution {

    private Solution() {
    }

    static final class Fail {

        static final int  PARTITION = 1;
        static final long OFFSET    = 42L;

        private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
        private static final Map<String, Long>          FIRST_AT = new ConcurrentHashMap<>();

        static boolean shouldFail(ConsumerRecord<?, ?> r) {
            if (!"orders".equals(r.topic())) {
                return true;
            }
            return r.partition() == PARTITION && r.offset() == OFFSET;
        }

        static String stamp(ConsumerRecord<?, ?> r, String orderId) {
            int attempt = ATTEMPTS.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
            long first  = FIRST_AT.computeIfAbsent(orderId, k -> System.currentTimeMillis());
            return "attempt=%d t=%dms topic=%s %s".formatted(
                    attempt, System.currentTimeMillis() - first, r.topic(), orderId);
        }
    }

    // ==================================================================
    // 정답 1. 기본 접미사 관찰
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * 코드 자체는 애노테이션 세 줄이 전부입니다. 이 문제의 목적은 "코드" 가 아니라
     * "결과를 눈으로 확인하는 것" 입니다.
     *
     * 관측 결과 (kt --list)
     *   orders
     *   orders-retry-1000
     *   orders-retry-2000
     *   orders-retry-4000
     *   orders-dlt
     *
     * ① 접미사에 붙은 숫자는 인덱스(0,1,2)가 아니라 "그 단계의 백오프 지연값(ms)" 입니다.
     *    1000 / 2000 / 4000 은 delay=1000, multiplier=2.0 의 결과와 정확히 일치합니다.
     *
     * ② 따라서 topicSuffixingStrategy 의 기본값은 SUFFIX_WITH_DELAY_VALUE 입니다.
     *    많은 블로그가 "orders-retry-0 이 생긴다" 고 쓰는데, 그건 SUFFIX_WITH_INDEX_VALUE 를
     *    명시했을 때의 이야기입니다.
     *
     * ③ ★ 가장 중요한 함의 — 백오프를 바꾸면 "토픽 이름이 바뀝니다."
     *    delay 를 1000 → 1500 으로 조정하는 순간 orders-retry-1500 / 3000 / 6000 이
     *    새로 생기고, 기존 orders-retry-1000 에 남아 있던 미처리 메시지는
     *    "아무도 구독하지 않는 토픽" 에 갇힙니다. 컨슈머가 없으니 LAG 지표에도 안 잡힙니다.
     *    에러 없이 메시지가 증발하는 전형적인 경로입니다.
     *    → 그래서 이 코스는 SUFFIX_WITH_INDEX_VALUE 를 권합니다. 백오프를 바꿔도
     *      토픽 이름이 그대로라 기존 메시지가 그대로 소비됩니다.
     *
     * ④ DLT 접미사가 "-dlt" 인 것도 확인하세요. Step 07 의 "orders.DLT" 와 다른 토픽입니다.
     *    점 하나 차이로 기존 DLT 모니터링이 전부 무력화됩니다.
     */
    @Component
    @Profile("sol08-q1")
    public static class Q1DefaultSuffix {

        private static final Logger log = LoggerFactory.getLogger(Q1DefaultSuffix.class);

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                log.warn("★ 실패 {}", Fail.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event) {
            log.error("DLT 수신 {}", event.orderId());
        }
    }

    // ==================================================================
    // 정답 2. 코스 규약에 맞추기 + 백오프 실측
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * ① topicSuffixingStrategy = SUFFIX_WITH_INDEX_VALUE
     *    정답 1에서 본 이유 때문입니다. 백오프 변경에 토픽 이름이 흔들리지 않습니다.
     *
     * ② dltTopicSuffix = ".DLT"
     *    Step 07 에서 이미 orders.DLT 를 만들었고, DLT 모니터링·알림·재발행 도구가
     *    전부 그 이름을 보고 있습니다. 토픽을 새로 파는 대신 기존 것을 재사용합니다.
     *
     * ③ retryTopicSuffix = "-retry" 는 기본값과 "같습니다." 생략해도 결과가 같습니다.
     *    그래도 명시하는 편이 낫습니다. 리뷰어가 기본값을 외우고 있을 거라고 가정하면 안 됩니다.
     *    특히 dltTopicSuffix 는 바꾸는데 retryTopicSuffix 는 기본값에 맡기면
     *    "둘 중 하나만 커스터마이징됐다" 는 인상을 주어 오해를 부릅니다.
     *
     * ④ numPartitions = "3", replicationFactor = "1" 을 반드시 명시합니다.
     *    numPartitions 기본값은 -1 이고, 이는 "브로커의 num.partitions 를 따르라" 는 뜻입니다.
     *    실습 브로커는 3이라 우연히 맞지만, 운영에서 원본이 12 파티션이면
     *    retry 토픽만 3 파티션이 되어 재시도 처리량이 조용히 1/4 로 떨어집니다.
     *
     * ⑤ 실측값
     *      attempt=1 topic=orders          t=0ms
     *      attempt=2 topic=orders-retry-0  t=1006ms    (대기 1s)
     *      attempt=3 topic=orders-retry-1  t=3012ms    (대기 2s)
     *      attempt=4 topic=orders-retry-2  t=7021ms    (대기 4s)
     *    총 7.021초입니다. Step 07 의 블로킹 25초와 비교하면 짧지만,
     *    "본선 파티션이 멈춘 시간" 으로 따지면 25,046ms 대 0ms 입니다. 이게 요점입니다.
     */
    @Component
    @Profile("sol08-q2")
    public static class Q2CourseConvention {

        private static final Logger log = LoggerFactory.getLogger(Q2CourseConvention.class);

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                log.warn("★ 실패 {}", Fail.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event) {
            log.error("DLT 수신 {}", event.orderId());
        }
    }

    // ==================================================================
    // 정답 3. @DltHandler 로 실패 원인 복원
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * ① 상수 이름이 Step 07 과 다릅니다.
     *      Step 07 (DeadLetterPublishingRecoverer 직접 사용)
     *        KafkaHeaders.DLT_ORIGINAL_TOPIC  → "kafka_dlt-original-topic"
     *      Step 08 (@RetryableTopic 인프라)
     *        KafkaHeaders.ORIGINAL_TOPIC      → "kafka_original-topic"
     *    Step 07 의 코드를 복사해 오면 @Header 가 값을 못 찾아
     *    "Could not resolve method parameter" 로 DLT 리스너 자체가 실패합니다.
     *    그리고 DltStrategy 기본값이 FAIL_ON_ERROR 이므로 그 DLT 파티션이 막힙니다.
     *
     * ② ★ ORIGINAL_TOPIC 에는 "orders" 가 찍힙니다. "orders-retry-2" 가 아닙니다.
     *    헤더는 본선에서 첫 실패가 났을 때 한 번만 붙고, 이후 retry 단계에서는
     *    덮어쓰이지 않습니다(DeadLetterPublishingRecoverer 가 기존 original 헤더가
     *    있으면 그대로 둡니다). 그래서 DLT 에서 "이 메시지가 원래 어디서 왔는가" 를
     *    정확히 복원할 수 있습니다. 재발행할 때 이 값을 목적지로 쓰면 됩니다.
     *
     * ③ ORIGINAL_PARTITION 을 int, ORIGINAL_OFFSET 을 long 으로 선언하면
     *    Spring 이 4바이트/8바이트 빅엔디안 배열을 알아서 변환해 줍니다.
     *    ConsumerRecord.headers() 로 직접 순회할 때만 ByteBuffer 가 필요합니다(Step 07).
     *
     * ④ DLT 핸들러에는 재시도를 걸지 않습니다. 여기서 또 실패하면 갈 곳이 없습니다.
     *    알림을 보내다 실패해도 예외를 밖으로 던지지 말고 로그만 남기는 편이 안전합니다.
     */
    @Component
    @Profile("sol08-q3")
    public static class Q3DltHandler {

        private static final Logger log = LoggerFactory.getLogger(Q3DltHandler.class);

        @RetryableTopic(
                attempts = "3",
                backoff = @Backoff(delay = 500, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                throw new RemoteApiException("재고 API 타임아웃: " + record.value().orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event,
                          @Header(KafkaHeaders.ORIGINAL_TOPIC)     String topic,
                          @Header(KafkaHeaders.ORIGINAL_PARTITION) int    partition,
                          @Header(KafkaHeaders.ORIGINAL_OFFSET)    long   offset,
                          @Header(KafkaHeaders.EXCEPTION_FQCN)     String exFqcn) {

            log.error("DLT 수신 {} origin={}-{}@{} ex={}",
                    event.orderId(), topic, partition, offset,
                    exFqcn.substring(exFqcn.lastIndexOf('.') + 1));
        }
    }

    // ==================================================================
    // 정답 4. ★핵심★ 순서 깨짐 재현
    // ==================================================================
    /*
     * 왜 이 답인가 — 그리고 이 문제의 결론
     *
     * 실측 타임라인
     *   | 시각      | 토픽           | 이벤트    | 결과                       | STATE     |
     *   |----------|---------------|----------|---------------------------|-----------|
     *   | t=2004ms | orders        | CREATED  | 실패 → retry-0 발행, 커밋   | (없음)     |
     *   | t=2011ms | orders        | UPDATED  | 성공                       | UPDATED   |
     *   | t=2014ms | orders        | CANCELLED| 성공                       | CANCELLED |
     *   | t=3018ms | orders-retry-0| CREATED  | ★성공★                    | CREATED   |
     *
     *   최종 상태 ORD-0002 = CREATED      ← 취소된 주문이 되살아났습니다
     *
     * 관찰 결과
     *   - ERROR 로그: 0줄. (WARN 한 줄만 있고 그건 우리가 직접 찍은 것입니다)
     *   - @DltHandler 호출: 안 됨. 재시도가 "성공" 했으니까요.
     *   - kcg --describe 의 LAG: 전부 0.
     *
     * ★ 이 문제의 역설
     *   이 데이터 오염을 만든 것은 "실패" 가 아니라 "성공" 입니다.
     *   재시도가 계속 실패해 DLT 로 갔다면 @DltHandler 가 알림을 보냈을 것이고,
     *   누군가 알아챘을 것입니다. 그런데 두 번째 시도에서 성공해 버리면
     *   시스템 어디에도 "무언가 잘못됐다" 는 신호가 남지 않습니다.
     *   모니터링이 잡을 수 없는 종류의 버그입니다.
     *
     * ★ 왜 블로킹에는 이 문제가 없는가
     *   DefaultErrorHandler 는 consumer.seek() 으로 "제자리에서" 반복합니다.
     *   offset 100 이 성공하거나 DLT 로 갈 때까지 101, 102 는 poll 조차 되지 않습니다.
     *   Step 07 의 "파티션이 멈춘다" 는 단점은, 정확히 이 순서 보장의 다른 이름입니다.
     *
     * ★ 해결 3가지 (본문 8-6)
     *   ① 순서가 중요하면 논블로킹을 쓰지 않는다. 블로킹 + 짧은 백오프 + 즉시 DLT.
     *
     *   ② 이벤트에 버전/시퀀스를 넣고 역행을 거부한다:
     *        Long current = VERSION.get(e.orderId());
     *        if (current != null && e.version() <= current) {
     *            log.warn("오래된 이벤트 무시 {} v{} <= v{}", e.orderId(), e.version(), current);
     *            return;
     *        }
     *        VERSION.put(e.orderId(), e.version());
     *      Step 13 의 멱등 컨슈머와 같은 구조입니다.
     *
     *   ③ 이벤트를 delta 가 아니라 snapshot 으로 설계한다.
     *      "생성/수정/취소" 대신 "현재 상태 전체 + updatedAt" 을 보내면
     *      늦게 온 오래된 스냅샷을 timestamp 비교만으로 버릴 수 있습니다.
     *
     * ⚠️ ②와 ③은 "오염을 막을" 뿐 "순서를 되돌리지는" 못합니다.
     *    재시도된 CREATED 는 그냥 버려집니다. 그것이 옳은 동작인지는 도메인이 정합니다.
     */
    @Component
    @Profile("sol08-q4")
    public static class Q4OrderState {

        private static final Logger log = LoggerFactory.getLogger(Q4OrderState.class);

        static final Map<String, OrderState> STATE = new ConcurrentHashMap<>();
        static final AtomicBoolean FIRST_TRY = new AtomicBoolean(true);
        static final long T0 = System.currentTimeMillis();

        static long elapsed() {
            return System.currentTimeMillis() - T0;
        }

        @RetryableTopic(
                attempts = "4",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-sol-order-state")
        public void onMessage(ConsumerRecord<String, OrderEvent> record) {
            OrderEvent event = record.value();
            if (event.status() == OrderState.CREATED && FIRST_TRY.getAndSet(false)) {
                log.warn("★ 실패 {} {} (topic={} t={}ms)",
                        event.orderId(), event.status(), record.topic(), elapsed());
                throw new RemoteApiException("재고 API 타임아웃");
            }
            STATE.put(event.orderId(), event.status());
            log.info("상태 반영 {} → {}  (topic={} t={}ms)",
                    event.orderId(), event.status(), record.topic(), elapsed());
        }

        @DltHandler
        public void onDlt(OrderEvent event) {
            log.error("DLT 수신 {} {}", event.orderId(), event.status());
        }
    }

    @Component
    @Profile("sol08-q4")
    public static class Q4Publisher implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Q4Publisher.class);

        private final KafkaTemplate<String, Object> template;

        public Q4Publisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                sleep(2_000L);
                for (OrderState s : new OrderState[]{
                        OrderState.CREATED, OrderState.UPDATED, OrderState.CANCELLED}) {
                    OrderEvent e = OrderEvent.of("ORD-0002", s);
                    template.send("orders", e.orderId(), e);
                    log.info("발행 {} {} (t={}ms)", e.orderId(), s, Q4OrderState.elapsed());
                }
                sleep(3_000L);
                log.info("최종 상태 ORD-0002 = {}   ← ★ CANCELLED 가 아니면 순서가 깨진 것입니다",
                        Q4OrderState.STATE.get("ORD-0002"));
            }, "sol-q4-publisher");
            t.setDaemon(true);
            t.start();
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================================================================
    // 정답 5. exclude + traversingCauses
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * ① exclude = { InvalidOrderException.class }
     *    include 를 비워 두면 "나열한 것 말고 전부 재시도" 가 됩니다(블랙리스트).
     *    반대로 include 만 주면 화이트리스트가 되어, 명시하지 않은 예외는
     *    전부 즉시 DLT 로 갑니다. 여기서는 RemoteApiException 을 재시도해야 하므로
     *    exclude 가 맞습니다. 새로운 예외가 추가돼도 기본이 "재시도" 라 안전합니다.
     *
     * ② ★ traversingCauses = "true" 가 이 문제의 핵심입니다.
     *    리스너가 던진 예외는 컨테이너에 도달할 때
     *      ListenerExecutionFailedException
     *        └─ caused by: InvalidOrderException
     *    으로 "감싸여" 옵니다. 기본값(false)에서는 최상위 예외 타입만 보고 분류하므로
     *    exclude 에 InvalidOrderException 을 적어도 매칭되지 않습니다.
     *    → 결과: 검증 실패인데 3번 재시도하고 retry 토픽 2개를 거칩니다.
     *      기능적으로는 결국 DLT 에 도착하므로 "동작은 한다" 고 착각하기 쉽습니다.
     *
     *    Step 07 의 addNotRetryableExceptions 도 정확히 같은 함정을 갖습니다.
     *    그쪽은 DefaultErrorHandler 가 내부적으로 cause 를 벗겨 주지만,
     *    RuntimeException 으로 한 번 더 감싸면 역시 분류가 깨집니다.
     *
     * ③ 확인 방법이 중요합니다. "DLT 에 도착했다" 만 보면 정답과 오답이 구분되지 않습니다.
     *    ★ orders-retry-0 이 비어 있는지를 봐야 합니다:
     *        kcc --topic orders-retry-0 --from-beginning --timeout-ms 5000
     *      → 아무것도 안 나오면 정답, ORD-0043 이 나오면 traversingCauses 를 빠뜨린 것입니다.
     *
     * ④ 로그에서도 구분됩니다.
     *      정답: "★ 검증 실패 ORD-0043 (topic=orders)" 가 딱 1번
     *      오답: 같은 줄이 topic=orders, orders-retry-0, orders-retry-1 로 3번
     */
    @Component
    @Profile("sol08-q5")
    public static class Q5Exclude {

        private static final Logger log = LoggerFactory.getLogger(Q5Exclude.class);

        @RetryableTopic(
                attempts = "3",
                backoff = @Backoff(delay = 1000, multiplier = 2.0),
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                retryTopicSuffix = "-retry",
                dltTopicSuffix = ".DLT",
                numPartitions = "3",
                replicationFactor = "1",
                exclude = { InvalidOrderException.class },   // ← 재시도 제외
                traversingCauses = "true",                   // ← ★ 원인 체인까지 따라간다
                kafkaTemplate = "kafkaTemplate")
        @KafkaListener(topics = "orders", groupId = "s08-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            OrderCreated event = record.value();

            if (record.partition() == 1 && record.offset() == 42) {
                log.warn("★ 검증 실패 {} (topic={})", event.orderId(), record.topic());
                throw new InvalidOrderException("quantity 는 1 이상이어야 합니다: " + event.orderId());
            }

            if (record.partition() == 2 && record.offset() == 30) {
                log.warn("★ API 실패 {} (topic={})", event.orderId(), record.topic());
                throw new RemoteApiException("재고 API 타임아웃");
            }

            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }

        @DltHandler
        public void onDlt(OrderCreated event,
                          @Header(KafkaHeaders.ORIGINAL_TOPIC) String topic,
                          @Header(KafkaHeaders.EXCEPTION_FQCN) String exFqcn) {
            log.error("DLT 수신 {} origin={} ex={}", event.orderId(), topic,
                    exFqcn.substring(exFqcn.lastIndexOf('.') + 1));
        }
    }

    // ==================================================================
    // 정답 6. RetryTopicConfiguration 빈으로 전역 설정
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * 애노테이션 속성과 빌더 메서드의 1:1 대조표
     *
     *   @RetryableTopic 속성                    | RetryTopicConfigurationBuilder
     *   ---------------------------------------|--------------------------------------------
     *   (리스너에 직접 부착)                     | .includeTopic("orders")
     *   attempts = "4"                          | .maxAttempts(4)
     *   @Backoff(delay=1000, multiplier=2.0,    | .exponentialBackoff(1000, 2.0, 10000)
     *            maxDelay=10000)                |
     *   @Backoff(delay=1000)                    | .fixedBackOff(1000)
     *   topicSuffixingStrategy                  | .suffixTopicsWithIndexValues()
     *     = SUFFIX_WITH_INDEX_VALUE             |
     *   retryTopicSuffix = "-retry"             | .retryTopicSuffix("-retry")
     *   dltTopicSuffix = ".DLT"                 | .dltSuffix(".DLT")
     *   concurrency = "1"                       | .concurrency(1)
     *   autoCreateTopics/numPartitions/         | .autoCreateTopics(true, 3, (short) 1)
     *     replicationFactor                     |
     *   exclude = { X.class }                   | .notRetryOn(X.class)
     *   include = { X.class }                   | .retryOn(X.class)
     *   traversingCauses = "true"               | .traversingCauses()
     *   dltStrategy = FAIL_ON_ERROR             | .doNotRetryOnDltFailure()
     *   dltStrategy = ALWAYS_RETRY_ON_ERROR     | .retryOnDltFailure()
     *   dltStrategy = NO_DLT                    | .doNotConfigureDlt()
     *
     * ① includeTopic("orders") 는 "이 토픽을 구독하는 모든 @KafkaListener" 에 적용됩니다.
     *    그래서 Q6Inventory 와 Q6Notification 둘 다에 재시도가 붙습니다.
     *    ⚠️ 이게 장점이자 함정입니다. 알림 리스너는 재시도가 필요 없는데도 붙어 버립니다.
     *      리스너마다 정책이 달라야 하면 애노테이션으로 돌아가거나
     *      excludeTopics 로 빼야 합니다.
     *
     * ② ★ 애노테이션과 빈이 둘 다 있으면 "애노테이션이 이깁니다."
     *    그래서 문제 지문이 "애노테이션을 전부 지운 상태에서 풀라" 고 한 것입니다.
     *    운영에서 이 규칙을 모르면 "전역 설정을 바꿨는데 왜 안 먹지?" 로 몇 시간을 씁니다.
     *    범인은 리스너 위에 남아 있는 옛 @RetryableTopic 한 줄입니다.
     *
     * ③ 컨슈머 그룹은 2개입니다(s08-sol-inventory, s08-sol-notification).
     *    retry 토픽과 DLT 는 각 리스너의 groupId 를 그대로 물려받으므로
     *    그룹이 늘어나지는 않습니다. kcg --list 로 확인하세요.
     *
     * ④ .create(template) 의 인자는 retry/DLT 발행에 쓸 KafkaTemplate 입니다.
     *    값 타입이 Object 인 템플릿을 넘겨야 여러 이벤트 타입을 한 설정으로 처리할 수 있습니다.
     */
    @Configuration
    @Profile("sol08-q6")
    public static class Q6BuilderConfig {

        @Bean
        public RetryTopicConfiguration ordersRetryTopicConfig(KafkaTemplate<String, Object> template) {
            return RetryTopicConfigurationBuilder
                    .newInstance()
                    .includeTopic("orders")
                    .maxAttempts(4)
                    .exponentialBackoff(1000, 2.0, 10_000)
                    .suffixTopicsWithIndexValues()
                    .retryTopicSuffix("-retry")
                    .dltSuffix(".DLT")
                    .concurrency(1)
                    .autoCreateTopics(true, 3, (short) 1)
                    .notRetryOn(InvalidOrderException.class)
                    .traversingCauses()
                    .doNotRetryOnDltFailure()
                    .create(template);
        }
    }

    @Component
    @Profile("sol08-q6")
    public static class Q6Inventory {

        private static final Logger log = LoggerFactory.getLogger(Q6Inventory.class);

        @KafkaListener(topics = "orders", groupId = "s08-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.shouldFail(record)) {
                log.warn("★ 실패 {}", Fail.stamp(record, record.value().orderId()));
                throw new RemoteApiException("재고 API 타임아웃");
            }
            log.info("재고 처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    @Component
    @Profile("sol08-q6")
    public static class Q6Notification {

        private static final Logger log = LoggerFactory.getLogger(Q6Notification.class);

        @KafkaListener(topics = "orders", groupId = "s08-sol-notification")
        public void notifyCustomer(ConsumerRecord<String, OrderCreated> record) {
            log.info("알림 발송 완료 {} (topic={})", record.key(), record.topic());
        }
    }
}
