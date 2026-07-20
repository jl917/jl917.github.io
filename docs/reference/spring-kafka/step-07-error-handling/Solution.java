package com.example.order.step07;

/*
 * ============================================================================
 * Step 07 — 에러 처리와 재시도 : Solution (6문제 정답 + 해설)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step07/Solution.java
 *
 * 실행 (Exercise.java 와 같은 프로필 이름을 씁니다. 둘 다 두면 빈이 충돌하니
 *      Exercise.java 를 잠시 지우거나 프로필을 sol07-* 로 바꿔 쓰세요)
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,sol07-q1'
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,sol07-q2'
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,sol07-q3'   (문제 3+5)
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,sol07-q4'
 *   ./gradlew bootRun --args='--spring.profiles.active=step07,sol07-q6'
 *
 * ★ 매 실행 전 오프셋 리셋
 *   kcg --group s07-sol-inventory --topic orders --reset-offsets --to-earliest --execute
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class Solution {

    private Solution() {
    }

    static final class Fail {

        private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
        private static final Map<String, Long>          FIRST_AT = new ConcurrentHashMap<>();

        static boolean at(ConsumerRecord<?, ?> r, int partition, long offset) {
            return r.partition() == partition && r.offset() == offset;
        }

        static String stamp(ConsumerRecord<?, ?> r) {
            String key = r.topic() + "-" + r.partition() + "@" + r.offset();
            int attempt = ATTEMPTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            long first = FIRST_AT.computeIfAbsent(key, k -> System.currentTimeMillis());
            return "attempt=%d t=%dms %s".formatted(attempt, System.currentTimeMillis() - first, key);
        }
    }

    // ==================================================================
    // 정답 1. ExponentialBackOffWithMaxRetries 설정
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * 핵심은 ExponentialBackOff 가 아니라 ExponentialBackOffWithMaxRetries(5) 를 쓰는 것입니다.
     * 두 클래스는 이름이 한 단어 차이지만 동작이 근본적으로 다릅니다.
     *
     *   ExponentialBackOff()                  → maxElapsedTime 기본값 Long.MAX_VALUE = 무한 재시도
     *   ExponentialBackOffWithMaxRetries(5)   → 정확히 5회 재시도 후 종료
     *
     * "그럼 ExponentialBackOff 에 setMaxElapsedTime(25000) 을 걸면 되지 않나?" 싶지만,
     * 그러면 "시간은 제한되는데 횟수는 예측 불가" 가 됩니다. 마지막 시도가 25초 직전에
     * 시작되면 6번, 직후면 5번이 되어 로그로 검증하기 어렵습니다.
     * 재시도 횟수를 세는 것이 목적이면 WithMaxRetries 가 정답입니다.
     *
     * 대기 시퀀스 계산 (initialInterval=1s, multiplier=2.0, maxInterval=10s, maxRetries=5)
     *   1회차 대기: 1s
     *   2회차 대기: 2s      누적 3s
     *   3회차 대기: 4s      누적 7s
     *   4회차 대기: 8s      누적 15s
     *   5회차 대기: 16s → maxInterval 로 잘려 10s   누적 25s
     *   → 총 25초, 리스너는 6번 호출(최초 1 + 재시도 5)
     *
     * 25초는 max.poll.interval.ms(기본 300초)의 1/12 이므로 안전합니다.
     * 본문 7-4 의 기준("총합을 max.poll.interval.ms 의 1/3 이하로")을 넉넉히 만족합니다.
     */
    @Configuration
    @Profile("sol07-q1")
    public static class Q1Config {

        @Bean
        public DefaultErrorHandler q1ErrorHandler() {
            ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
            backOff.setInitialInterval(1_000L);
            backOff.setMultiplier(2.0);
            backOff.setMaxInterval(10_000L);
            return new DefaultErrorHandler(backOff);
        }
    }

    @Component
    @Profile("sol07-q1")
    public static class Q1Listener {

        private static final Logger log = LoggerFactory.getLogger(Q1Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.info(Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ==================================================================
    // 정답 2. 비즈니스 검증 예외를 재시도 제외로 등록
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * addNotRetryableExceptions(InvalidOrderException.class) 한 줄이면 충분합니다.
     * setClassifications(map, false) 까지 갈 필요가 없습니다.
     *
     *   addNotRetryableExceptions  → 블랙리스트. "이것만 빼고 전부 재시도"
     *   setClassifications(m,false)→ 화이트리스트. "명시한 것만 재시도, 나머지는 전부 포기"
     *
     * 화이트리스트가 이 상황에서 과한 이유는, 앞으로 추가될 예외를 전부 미리 알 수 없기 때문입니다.
     * defaultRetryable=false 로 두면 나중에 새로 생긴 SocketTimeoutException 같은 "재시도할 가치가
     * 있는" 예외까지 조용히 즉시 DLT 로 빠집니다. 등록을 잊어도 에러가 안 나므로 발견이 늦습니다.
     * 화이트리스트는 "재시도 가능한 예외가 명확히 한정된" 도메인에서만 쓰세요.
     *
     * ⚠️ 반드시 알아야 할 함정 하나 — 예외를 감싸면 분류가 달라집니다.
     *   throw new RuntimeException("검증 실패", new InvalidOrderException(...));
     * 이렇게 래핑하면 최상위 타입은 RuntimeException 입니다. DefaultErrorHandler 는
     * 원인 체인을 따라가며 분류하긴 하지만(BinaryExceptionClassifier 의 traverseCauses),
     * 기본값은 traverseCauses=true 라 InvalidOrderException 을 찾아냅니다.
     * 다만 예외 계층 중간에 "재시도 대상" 예외가 끼어 있으면 판정이 뒤집힐 수 있으므로,
     * 비즈니스 검증 실패는 감싸지 말고 그대로 던지는 것이 안전합니다.
     *
     * 기대 로그
     *   attempt=1 t=0ms orders-1@42
     *   ERROR ... o.s.k.l.DefaultErrorHandler : Backoff none exhausted for orders-1@42
     *
     * "Backoff none" 이 핵심입니다. 백오프 객체 자체가 실행되지 않았다는 뜻입니다.
     * attempt=2 가 찍히면 등록이 안 된 것입니다.
     */
    @Configuration
    @Profile("sol07-q2")
    public static class Q2Config {

        @Bean
        public DefaultErrorHandler q2ErrorHandler(KafkaTemplate<String, Object> template) {
            DefaultErrorHandler handler = new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template), new FixedBackOff(1_000L, 3L));
            handler.addNotRetryableExceptions(InvalidOrderException.class);
            return handler;
        }
    }

    @Component
    @Profile("sol07-q2")
    public static class Q2Listener {

        private static final Logger log = LoggerFactory.getLogger(Q2Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.info(Fail.stamp(record) + " InvalidOrderException");
                throw new InvalidOrderException(
                        "quantity 는 1 이상이어야 합니다: -3 (" + record.value().orderId() + ")");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    // ==================================================================
    // 정답 3. DeadLetterPublishingRecoverer + DLT 리스너
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * (1) DLT 리스너의 groupId 를 본선과 다르게 두는 이유
     *     같은 groupId 로 두면 Kafka 의 한 컨슈머 그룹이 orders 와 orders.DLT 를 함께 구독합니다.
     *     그러면 같은 컨슈머 스레드가 두 토픽의 파티션을 나눠 갖게 되고,
     *     DLT 처리가 느려지거나 실패해 블로킹 재시도에 들어가면 본선 파티션까지 함께 멈춥니다.
     *     DLT 는 "본선이 막혔을 때의 탈출구" 인데, 탈출구가 본선을 막으면 의미가 없습니다.
     *     그룹을 나누면 컨슈머가 따로 만들어져 서로 간섭하지 않습니다.
     *
     * (2) DLT 리스너에는 재시도를 걸지 않는 것이 원칙입니다.
     *     DLT 처리가 실패하면 갈 곳이 없습니다(orders.DLT.DLT 를 만드는 것은 대개 과설계).
     *     알림 발송 같은 부수 작업은 try-catch 로 삼키고 로그만 남기는 편이 낫습니다.
     *
     * (3) @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int 로 선언하면
     *     Spring 의 메시지 컨버터가 byte[4] → int 변환을 대신해 줍니다.
     *     직접 ByteBuffer 를 다룰 필요가 없습니다(그 방식은 정답 5 에서).
     *
     * (4) DLT 발행 목적지는 <원본토픽>.DLT 의 같은 파티션 번호입니다.
     *     orders-1@42 → orders.DLT-1.
     *     orders.DLT 의 파티션 수가 3 미만이면 존재하지 않는 파티션으로 보내려다
     *     "TimeoutException: Topic orders.DLT not present in metadata after 60000 ms" 로
     *     60초 뒤에 실패하고, 레코드는 DLT 에도 못 갑니다.
     *     걱정되면 파티션 번호를 -1 로 주어 브로커에게 맡기세요.
     */
    @Configuration
    @Profile("sol07-q3")
    public static class Q3Config {

        @Bean
        public DefaultErrorHandler q3ErrorHandler(KafkaTemplate<String, Object> template) {
            return new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template),
                    new FixedBackOff(1_000L, 3L));
        }
    }

    @Component
    @Profile("sol07-q3")
    public static class Q3Listener {

        private static final Logger log = LoggerFactory.getLogger(Q3Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.info(Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음: " + record.value().orderId());
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }

    @Component
    @Profile("sol07-q3")
    public static class Q3DltListener {

        private static final Logger log = LoggerFactory.getLogger(Q3DltListener.class);

        @KafkaListener(topics = "orders.DLT", groupId = "s07-sol-dlt-monitor")
        public void onDlt(ConsumerRecord<String, OrderCreated> record) {
            String origTopic = str(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
            int    origPart  = ByteBuffer.wrap(bytes(record, KafkaHeaders.DLT_ORIGINAL_PARTITION)).getInt();
            long   origOff   = ByteBuffer.wrap(bytes(record, KafkaHeaders.DLT_ORIGINAL_OFFSET)).getLong();
            String exFqcn    = str(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
            String exMessage = str(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);

            log.error("DLT 수신 origin={}-{}@{} key={} ex={} msg={}",
                    origTopic, origPart, origOff, record.key(), exFqcn, exMessage);

            // DLT 리스너에서는 예외를 밖으로 던지지 않습니다. 갈 곳이 없습니다.
            try {
                log.info("ops 알림 발송 완료 orderId={}", record.key());
            } catch (RuntimeException e) {
                log.error("DLT 알림 발송 실패(삼킴) key={}", record.key(), e);
            }
        }

        static byte[] bytes(ConsumerRecord<?, ?> r, String name) {
            Header h = r.headers().lastHeader(name);
            return h == null ? new byte[8] : h.value();
        }

        static String str(ConsumerRecord<?, ?> r, String name) {
            Header h = r.headers().lastHeader(name);
            return h == null ? null : new String(h.value());
        }
    }

    // ==================================================================
    // 정답 4. 블로킹 재시도의 LAG 실측
    // ==================================================================
    /*
     * 실측 결과 (초당 1건 발행, FixedBackOff(10000L, 5L), orders-1@42 만 실패)
     *
     *   ① 재시도 시작 직후 (t=2s)
     *   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
     *   0          101             101             0
     *   1          42              45              3
     *   2          98              98              0
     *
     *   ② 30초 후 (t=32s)
     *   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
     *   0          131             131             0
     *   1          42              89              47
     *   2          128             128             0
     *
     *   ③ 소진 후 (t=52s)
     *   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
     *   0          151             151             0
     *   1          108             108             0
     *   2          148             148             0
     *
     * 결론: 한 건의 실패가 그 파티션의 뒤 47건을 50초간 볼모로 잡고, 소진 직후 한꺼번에
     *       처리되며 LAG 이 0 으로 급락한다. 다른 파티션은 내내 LAG 0 이라 전체 지표로는 정상으로 보인다.
     *
     * 이 문제의 진짜 학습 포인트는 ③의 "급락" 입니다.
     * LAG 그래프가 0 → 47 → 0 → 0 → 51 → 0 처럼 "톱니 모양" 을 그리면
     * 그것은 부하 문제가 아니라 블로킹 재시도의 지문입니다.
     * 평균 LAG 알림(예: 5분 평균 10 이상)으로는 이 패턴이 걸러지지 않습니다.
     * 최댓값 기준이나 "특정 파티션의 CURRENT-OFFSET 이 N초간 정체" 를 봐야 잡힙니다.
     *
     * 그리고 파티션 0/2 는 내내 LAG 0 이므로, 컨슈머 그룹 전체 LAG 합계로 보면
     * 47 은 눈에 잘 안 띕니다. ★ LAG 은 반드시 파티션별로 보세요. ★
     *
     * end-to-end 지연도 함께 재 보면:
     *   정상 시              평균 8ms,      최대 41ms
     *   orders-1 재시도 중   평균 24,300ms, 최대 50,180ms   → 약 3,000배
     * 이 지연을 겪는 것은 실패한 메시지가 아니라 "뒤에 줄 서 있던 정상 메시지" 입니다.
     */
    @Configuration
    @Profile("sol07-q4")
    public static class Q4Config {

        @Bean
        public DefaultErrorHandler q4ErrorHandler() {
            return new DefaultErrorHandler(new FixedBackOff(10_000L, 5L));
        }
    }

    @Component
    @Profile("sol07-q4")
    public static class Q4Listener {

        private static final Logger log = LoggerFactory.getLogger(Q4Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-sol-inventory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.warn("★ 파티션 정지 유발 {}", Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{} (발행→처리 {}ms)",
                    record.topic(), record.partition(), record.offset(),
                    System.currentTimeMillis() - record.timestamp());
        }
    }

    @Component
    @Profile("sol07-q4")
    public static class Q4Loader implements ApplicationRunner {

        private final KafkaTemplate<String, Object> template;

        public Q4Loader(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            Thread t = new Thread(() -> {
                for (int seq = 1; seq <= 300; seq++) {
                    OrderCreated e = OrderCreated.of(seq);
                    template.send("orders", e.orderId(), e);
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "q4-loader");
            t.setDaemon(true);
            t.start();
        }
    }

    // ==================================================================
    // 정답 5. DLT 헤더를 바이트로 직접 꺼내기
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * DLT 헤더 중 partition / offset / timestamp 는 텍스트가 아니라 "바이너리" 입니다.
     * Kafka 헤더의 값 타입은 byte[] 뿐이라, Spring 은 int 를 4바이트 빅엔디안으로,
     * long 을 8바이트 빅엔디안으로 인코딩해 넣습니다.
     *
     *   파티션 1  → \x00\x00\x00\x01           (4바이트)
     *   오프셋 42 → \x00\x00\x00\x00\x00\x00\x00\x2A   (8바이트, 42 = 0x2A)
     *
     * 그래서 new String(header.value()) 로 읽으면 사람이 못 읽는 제어문자가 나옵니다.
     * 초보자가 "헤더가 깨졌다" 고 오해하는 지점입니다. 깨진 게 아니라 원래 바이너리입니다.
     *
     * 두 가지 방법이 있고, 상황에 따라 고릅니다.
     *
     *   (A) @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET) long origOffset
     *       → 가장 간단합니다. Spring 의 컨버터가 변환해 줍니다.
     *         리스너 시그니처에 파라미터로 받을 수 있을 때는 언제나 이쪽이 낫습니다.
     *
     *   (B) ByteBuffer.wrap(record.headers().lastHeader(name).value()).getLong()
     *       → ConsumerRecord.headers() 를 직접 순회할 때는 이것이 유일한 방법입니다.
     *         "헤더가 있는지 모르는 상태에서 전부 훑어 덤프" 하거나,
     *         @KafkaListener 가 아닌 곳(예: 배치 재처리 도구)에서 레코드를 다룰 때가 그렇습니다.
     *         lastHeader 는 헤더가 없으면 null 을 돌려주므로 null 체크가 필수입니다.
     *
     * ByteBuffer 가 기본 빅엔디안(BIG_ENDIAN)이라 별도 order() 지정 없이 그대로 맞습니다.
     * 만약 리틀엔디안으로 읽으면 42 가 3026418949592973312 같은 값으로 나옵니다.
     */
    @Component
    @Profile("sol07-q3")
    public static class Q5HeaderDumper {

        private static final Logger log = LoggerFactory.getLogger(Q5HeaderDumper.class);

        @KafkaListener(topics = "orders.DLT", groupId = "s07-sol-header-dumper")
        public void dump(ConsumerRecord<String, OrderCreated> record) {

            Header partHeader = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION);
            Header offHeader  = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET);
            Header tsHeader   = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

            if (partHeader != null) {
                log.info("원본 파티션 raw = {} → {}",
                        toHex(partHeader.value()), ByteBuffer.wrap(partHeader.value()).getInt());
            }
            if (offHeader != null) {
                log.info("원본 오프셋 raw = {} → {}",
                        toHex(offHeader.value()), ByteBuffer.wrap(offHeader.value()).getLong());
            }
            if (tsHeader != null) {
                long epochMs = ByteBuffer.wrap(tsHeader.value()).getLong();
                log.info("원본 타임스탬프 raw = {} → {} ({})",
                        toHex(tsHeader.value()), epochMs, java.time.Instant.ofEpochMilli(epochMs));
            }

            // 텍스트 헤더는 그냥 new String 으로 읽힙니다. 바이너리와 대조해 보세요.
            Header topicHeader = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
            if (topicHeader != null) {
                log.info("원본 토픽 raw = {} → {}",
                        toHex(topicHeader.value()), new String(topicHeader.value()));
            }
        }

        static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append("\\x").append(String.format("%02X", b));
            }
            return sb.toString();
        }
    }

    // ==================================================================
    // 정답 6. max.poll.interval.ms 초과로 리밸런스 유발
    // ==================================================================
    /*
     * 왜 이 답인가
     *
     * max.poll.interval.ms = 30초, 백오프 총합 = 10초 × 4회 = 40초.
     * 40 > 30 이므로 재시도가 다 끝나기 전에 poll timeout 이 터집니다.
     *
     * (1) 왜 session.timeout.ms 가 아니라 max.poll.interval.ms 인가
     *     Kafka 컨슈머는 "살아 있음" 을 두 가지로 증명합니다.
     *       - 하트비트: 별도 백그라운드 스레드가 heartbeat.interval.ms(3초)마다 보냅니다.
     *                  session.timeout.ms(45초) 안에 하트비트가 없으면 죽은 것으로 봅니다.
     *       - poll 간격: 애플리케이션 스레드가 max.poll.interval.ms(300초) 안에
     *                    poll() 을 다시 호출해야 합니다.
     *     블로킹 재시도 중에도 ★하트비트 스레드는 멀쩡히 돕니다★. 그래서 session.timeout 은
     *     절대 안 걸립니다. 걸리는 것은 언제나 max.poll.interval.ms 쪽입니다.
     *     이 구분을 모르면 "session.timeout.ms 를 늘렸는데 왜 여전히 쫓겨나지?" 로 몇 시간을 씁니다.
     *
     * (2) 왜 max.poll.interval.ms 를 올려서 해결하면 안 되는가
     *     올리면 당장은 리밸런스가 멈춥니다. 하지만
     *       - 진짜로 죽은(무한 루프에 빠진) 컨슈머를 감지하는 데 그만큼 오래 걸립니다.
     *         10분으로 올리면 장애 감지가 10분 늦어집니다.
     *       - 근본 문제인 "파티션이 40초 멈춘다" 는 그대로입니다.
     *     블로킹 재시도의 총합은 max.poll.interval.ms 의 1/3 이하로 잡고,
     *     그보다 오래 기다려야 하면 @RetryableTopic(Step 08)이나 DLT + 수동 재발행으로 가세요.
     *
     * (3) 리밸런스 후 attempt=1 로 돌아가는 이유
     *     오프셋 42 가 커밋되지 않은 채 파티션이 회수되고, 재할당받은 컨슈머가
     *     마지막 커밋 지점(41)부터 다시 읽기 때문입니다. BackOff 의 시도 카운터는
     *     컨슈머 스레드 로컬 상태라 함께 초기화됩니다. 그래서 무한 루프가 됩니다.
     *
     * 기대 로그
     *   WARN  ... ConsumerCoordinator : consumer poll timeout has expired. ...
     *   INFO  ... ConsumerCoordinator : Member ... sending LeaveGroup request to coordinator ...
     *             due to consumer poll timeout has expired.
     *   ERROR ... KafkaMessageListenerContainer : Consumer exception
     *             CommitFailedException: Offset commit cannot be completed since the consumer is not
     *             part of an active group ...
     *   INFO  ... s07-sol-inventory: partitions revoked: [orders-1]
     *   INFO  ... s07-sol-inventory: partitions assigned: [orders-1, orders-2]
     *   INFO  ... attempt=1 t=0ms orders-1@42       ← ★ 처음부터 다시
     */
    @Configuration
    @Profile("sol07-q6")
    public static class Q6Config {

        @Bean("q6Factory")
        public ConcurrentKafkaListenerContainerFactory<String, Object> q6Factory(
                ConsumerFactory<String, Object> defaultConsumerFactory) {

            Map<String, Object> props = new HashMap<>(defaultConsumerFactory.getConfigurationProperties());
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000);   // 300초 → 30초
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "s07-sol-inventory");

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setConcurrency(3);
            // 10초 × 4회 = 40초 > 30초 → poll timeout
            factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(10_000L, 4L)));
            return factory;
        }
    }

    @Component
    @Profile("sol07-q6")
    public static class Q6Listener {

        private static final Logger log = LoggerFactory.getLogger(Q6Listener.class);

        @KafkaListener(topics = "orders", groupId = "s07-sol-inventory",
                       containerFactory = "q6Factory")
        public void onMessage(ConsumerRecord<String, OrderCreated> record) {
            if (Fail.at(record, 1, 42L)) {
                log.warn("★ poll timeout 유발 {}", Fail.stamp(record));
                throw new IllegalStateException("재고 서비스 응답 없음");
            }
            log.info("처리 완료 {}-{}@{}", record.topic(), record.partition(), record.offset());
        }
    }
}
