/*
 * ============================================================================
 *  Step 02 — KafkaTemplate 과 프로듀서 : Practice
 * ============================================================================
 *
 *  배치 위치 : src/main/java/com/example/order/step02/Practice.java
 *  실행      : ./gradlew bootRun --args='--spring.profiles.active=step02'
 *
 *  [필수] src/main/resources/application-step02.yml 을 만드세요.
 *         2-3 의 "조용한 유실" 재현은 delivery.timeout.ms 기본값(120초)으로는
 *         2분을 기다려야 합니다. 실습용으로 10초까지 줄입니다.
 *
 *      spring:
 *        kafka:
 *          producer:
 *            acks: all
 *            properties:
 *              enable.idempotence: true
 *              max.in.flight.requests.per.connection: 5
 *              linger.ms: 5
 *              batch.size: 16384
 *              delivery.timeout.ms: 10000     # 실습용. 운영 기본값 120000
 *              request.timeout.ms: 3000
 *              max.block.ms: 5000
 *      logging:
 *        level:
 *          com.example.order: DEBUG
 *
 *  [실행 스위치] 아래 Step02Runner 의 상수 3개로 무엇을 돌릴지 고릅니다.
 *      SILENT_LOSS_DEMO : 2-3 조용한 유실 재현  (브로커를 내린 상태에서만 의미 있음)
 *      BENCH            : 2-4 처리량 4방식 측정 (브로커가 살아 있어야 함)
 *      PARTITION_MAP    : 2-7 키 → 파티션 예측/대조
 *  ⚠️ 한 번에 하나만 true 로 두세요. 섞으면 결과가 오염됩니다.
 *
 *  [확인용 CLI]
 *      # 토픽 파티션 수 확인 (반드시 3이어야 2-7 표와 일치)
 *      docker exec -it learn-kafka /opt/kafka/bin/kafka-topics.sh \
 *        --bootstrap-server localhost:9092 --describe --topic orders
 *
 *      # 발행 결과를 눈으로 보기 (헤더/키/파티션/오프셋)
 *      docker exec -it learn-kafka /opt/kafka/bin/kafka-console-consumer.sh \
 *        --bootstrap-server localhost:9092 --topic orders --from-beginning \
 *        --property print.key=true --property print.headers=true \
 *        --property print.partition=true --property print.offset=true
 *
 *      # 2-3 재현용: 브로커 내리기 / 되돌리기
 *      docker compose stop kafka
 *      docker compose start kafka
 * ============================================================================
 */
package com.example.order.step02;

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Practice {

    private Practice() {
    }

    private static final String TOPIC = "orders";

    // ========================================================================
    // 실행 진입점 — 스위치로 무엇을 돌릴지 고른다
    // ========================================================================
    @Component
    @Profile("step02")
    public static class Step02Runner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Step02Runner.class);

        /** 2-3. 브로커를 내린 상태에서만 의미가 있습니다. */
        private static final boolean SILENT_LOSS_DEMO = false;
        /** 2-4. 브로커가 살아 있어야 합니다. orders 토픽에 40,000건이 쌓입니다. */
        private static final boolean BENCH = false;
        /** 2-7. 키 → 파티션 예측/대조. 기본으로 켜 둡니다. */
        private static final boolean PARTITION_MAP = true;

        private final KafkaTemplate<String, OrderCreated> template;

        public Step02Runner(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            log.info("===== Step 02 시작 =====");

            new SendOverloads(template).run();          // 2-1
            new FutureBasics(template).run();           // 2-2
            new HeaderDemo(template).run();             // 2-6
            new PartitionInfoDemo(template).run();      // 2-8

            if (SILENT_LOSS_DEMO) {
                new SilentLossDemo(template).run();     // 2-3
            }
            if (BENCH) {
                new ThroughputBench(template).run();    // 2-4
            }
            if (PARTITION_MAP) {
                new PartitionMapDemo(template).run();   // 2-7
            }

            log.info("===== Step 02 끝 =====");
        }
    }

    // ========================================================================
    // [2-1] send 오버로드 5종
    // ========================================================================
    public static class SendOverloads {

        private static final Logger log = LoggerFactory.getLogger(SendOverloads.class);
        private final KafkaTemplate<String, OrderCreated> template;

        public SendOverloads(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() {
            OrderCreated event = OrderCreated.of(1);

            // ① 토픽 + 값. 키가 null → 파티셔너가 알아서(sticky) 고른다
            template.send(TOPIC, event);

            // ② 토픽 + 키 + 값. ★ 기본형. 같은 키 = 같은 파티션 = 순서 보장
            template.send(TOPIC, event.orderId(), event);

            // ③ 파티션까지 지정. 파티셔너를 건너뛴다 (2-8 의 위험 참고)
            template.send(TOPIC, 1, event.orderId(), event);

            // ④ ProducerRecord. 헤더·타임스탬프까지 전부 제어 (2-6)
            ProducerRecord<String, OrderCreated> record = new ProducerRecord<>(
                    TOPIC, null, event.createdAt().toEpochMilli(), event.orderId(), event);
            record.headers().add("trace-id", "t-0001".getBytes(StandardCharsets.UTF_8));
            template.send(record);

            // ⑤ Spring Messaging 의 Message<?>
            Message<OrderCreated> message = MessageBuilder.withPayload(event)
                    .setHeader(KafkaHeaders.TOPIC, TOPIC)
                    .setHeader(KafkaHeaders.KEY, event.orderId())
                    .setHeader("trace-id", "t-0001")
                    .build();
            template.send(message);

            log.info("[2-1] 오버로드 5종 발행 요청 완료 (아직 갔는지는 모른다!)");
        }
    }

    // ========================================================================
    // [2-2] CompletableFuture<SendResult> — whenComplete / thenAccept / exceptionally
    //       Spring Kafka 3.0 부터 ListenableFuture 가 아니라 CompletableFuture
    // ========================================================================
    public static class FutureBasics {

        private static final Logger log = LoggerFactory.getLogger(FutureBasics.class);
        private final KafkaTemplate<String, OrderCreated> template;

        public FutureBasics(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() throws Exception {
            OrderCreated event = OrderCreated.of(3);

            // (1) whenComplete — 성공/실패를 한곳에서. ★ 가장 자주 쓰는 형태
            CompletableFuture<SendResult<String, OrderCreated>> future =
                    template.send(TOPIC, event.orderId(), event);

            log.info("[2-2] send() 리턴 직후 isDone={}", future.isDone());   // 거의 항상 false

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("[2-2] 발행 실패 key={}", event.orderId(), ex);
                    return;
                }
                RecordMetadata md = result.getRecordMetadata();
                // ⚠️ 이 로그의 스레드명은 [main] 이 아니라 [ad | producer-1] 입니다.
                //    콜백은 프로듀서 I/O 스레드에서 실행됩니다. 무거운 일을 하면 전체 전송이 막힙니다.
                log.info("[2-2] 발행 성공 key={} → {}-{}@{} ts={} bytes={}",
                        event.orderId(), md.topic(), md.partition(), md.offset(),
                        md.timestamp(), md.serializedValueSize());
            });

            // (2) thenAccept + exceptionally — 성공/실패를 분리해서 쓰고 싶을 때
            template.send(TOPIC, "ORD-0004", OrderCreated.of(4))
                    .thenAccept(r -> log.info("[2-2] thenAccept offset={}", r.getRecordMetadata().offset()))
                    .exceptionally(ex -> {
                        log.error("[2-2] exceptionally", ex);
                        return null;
                    });

            // (3) join() — 동기 대기. 여기서만 예외가 호출 스레드로 전파된다.
            //     ⚠️ 이걸 루프 안에서 하면 2-4 의 성능 붕괴가 일어납니다.
            SendResult<String, OrderCreated> sync =
                    template.send(TOPIC, "ORD-0005", OrderCreated.of(5)).get(10, TimeUnit.SECONDS);
            log.info("[2-2] 동기 확인 → {}-{}@{}",
                    sync.getRecordMetadata().topic(),
                    sync.getRecordMetadata().partition(),
                    sync.getRecordMetadata().offset());

            // (4) 보낸 원본도 꺼낼 수 있다
            log.info("[2-2] 보낸 레코드 key={} value={}",
                    sync.getProducerRecord().key(), sync.getProducerRecord().value().orderId());
        }
    }

    // ========================================================================
    // [2-3] ⚠️ 최대 함정 — 결과를 안 보면 유실을 모른다
    //
    //  실행 전에 반드시:  docker compose stop kafka
    //  실행 후  되돌리기: docker compose start kafka
    // ========================================================================
    public static class SilentLossDemo {

        private static final Logger log = LoggerFactory.getLogger(SilentLossDemo.class);
        private final KafkaTemplate<String, OrderCreated> template;

        public SilentLossDemo(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() throws Exception {
            log.warn("[2-3] 브로커가 꺼져 있는지 확인하세요. (docker compose stop kafka)");

            // (A) 나쁜 코드 — 반환값을 버린다. 실무에서 가장 흔하다.
            for (int i = 1; i <= 5; i++) {
                OrderCreated event = OrderCreated.of(i);
                template.send(TOPIC, event.orderId(), event);      // 반환값 버림
                log.info("[2-3][나쁜코드] 주문 이벤트 발행 완료: {}", event.orderId());
                // 실무에서는 여기서 orderRepository.markPublished(...) 같은 DB 갱신이 일어난다.
                // 브로커가 죽어 있어도 이 줄은 실행된다. 그게 유실의 정체다.
                Thread.sleep(1000);
            }

            // (B) 좋은 코드 — 콜백으로 실패를 잡는다.
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger fail = new AtomicInteger();
            for (int i = 6; i <= 10; i++) {
                OrderCreated event = OrderCreated.of(i);
                template.send(TOPIC, event.orderId(), event)
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                fail.incrementAndGet();
                                log.error("[2-3][좋은코드] 발행 실패 key={} cause={}",
                                        event.orderId(), ex.getClass().getSimpleName());
                                // 여기서 Outbox 테이블 적재 / 재시도 큐 투입 / 알림을 해야 한다.
                            } else {
                                ok.incrementAndGet();
                            }
                        });
            }

            // delivery.timeout.ms(10초) 가 지나야 실패가 확정된다. 넉넉히 기다린다.
            log.info("[2-3] delivery.timeout.ms 경과를 기다립니다 (약 12초)...");
            Thread.sleep(12_000);
            log.info("[2-3] 콜백 집계 — 성공 {}건 / 실패 {}건", ok.get(), fail.get());

            // (C) 존재하지 않는 토픽 — future 상태를 시간 순으로 관찰
            CompletableFuture<SendResult<String, OrderCreated>> f =
                    template.send("no-such-topic", "K-1", OrderCreated.of(1));
            log.info("[2-3] no-such-topic: send() 직후 isDone={}", f.isDone());
            try {
                f.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                log.error("[2-3] no-such-topic: 결국 실패 — {}", e.getCause().toString());
            } catch (Exception e) {
                log.error("[2-3] no-such-topic: 15초 안에도 안 끝남 (isDone={})", f.isDone());
            }

            log.warn("[2-3] 실습 끝. docker compose start kafka 로 브로커를 되살리세요.");
        }
    }

    // ========================================================================
    // [2-4] ⚠️ 두 번째 함정 — send().get() 의 성능 붕괴
    //       4가지 방식으로 10,000건씩 발행해 처리량을 비교한다.
    // ========================================================================
    public static class ThroughputBench {

        private static final Logger log = LoggerFactory.getLogger(ThroughputBench.class);
        private static final int N = 10_000;

        private final KafkaTemplate<String, OrderCreated> template;

        public ThroughputBench(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() throws Exception {
            warmUp();   // ★ 이걸 빼면 (a) 가 (c) 보다 느리게 나오는 황당한 결과가 나온다

            long a = fireAndForget();
            long b = withCallback();
            long c = blockingGet();
            long d = batchThenJoin();

            report("(a) fire-and-forget + flush ", a);
            report("(b) 콜백(whenComplete)      ", b);
            report("(c) 매 건 send().get()      ", c);
            report("(d) 배치 후 allOf().join()  ", d);
            log.info("[2-4] (a) 대비 (c) 는 {}배 느립니다.", c / Math.max(a, 1));
        }

        /** JIT 컴파일 + 메타데이터 조회를 측정 구간 밖으로 밀어낸다. */
        private void warmUp() {
            for (int i = 1; i <= 1_000; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i));
            }
            template.flush();
            log.info("[2-4] 워밍업 1,000건 완료");
        }

        /** (a) 반환값을 버리고 루프를 돈 뒤, flush() 로 버퍼를 비우고 응답까지 기다린다. */
        private long fireAndForget() {
            long t0 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i));
            }
            // ★ flush() 는 반드시 측정 구간 안에 있어야 공정합니다.
            //   밖에 두면 "버퍼에 넣는 시간"만 재게 되어 200,000 msg/s 같은 허구가 나옵니다.
            template.flush();
            return elapsedMs(t0);
        }

        /** (b) 콜백을 붙인다. (a) 대비 손실이 거의 없다. */
        private long withCallback() {
            AtomicInteger fail = new AtomicInteger();
            long t0 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i))
                        .whenComplete((r, ex) -> {
                            if (ex != null) fail.incrementAndGet();
                        });
            }
            template.flush();
            long ms = elapsedMs(t0);
            log.info("[2-4] (b) 실패 {}건", fail.get());
            return ms;
        }

        /** (c) ★ 문제의 코드. 건마다 왕복(RTT)을 지불하므로 배치 크기가 영원히 1이 된다. */
        private long blockingGet() throws Exception {
            long t0 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i)).get();
            }
            return elapsedMs(t0);
        }

        /** (d) 전부 모아서 한 번에 대기. 개별 실패도 전부 셀 수 있다. */
        private long batchThenJoin() {
            List<CompletableFuture<SendResult<String, OrderCreated>>> futures = new ArrayList<>(N);
            long t0 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                futures.add(template.send(TOPIC, key(i), OrderCreated.of(i)));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            long ms = elapsedMs(t0);
            long failed = futures.stream().filter(CompletableFuture::isCompletedExceptionally).count();
            log.info("[2-4] (d) 발행 {}건 중 실패 {}건", N, failed);
            return ms;
        }

        private static String key(int i) {
            return "ORD-%04d".formatted(i);
        }

        private static long elapsedMs(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }

        private static void report(String label, long ms) {
            long tps = ms == 0 ? 0 : (N * 1000L) / ms;
            log.info("[2-4] {}: {}건 / {} ms = {} msg/s", label, N, ms, String.format("%,d", tps));
        }
    }

    // ========================================================================
    // [2-5] ProducerFactory 와 커스텀 설정 — 설정이 다른 KafkaTemplate 두 개
    //
    //  ⚠️ 이 @Configuration 을 켜면 KafkaTemplate 타입 빈이 3개(자동설정 1 + 여기 2)가 됩니다.
    //     이름 없는 @Autowired KafkaTemplate 주입은 NoUniqueBeanDefinitionException 으로 실패합니다.
    //     반드시 @Qualifier 로 골라 쓰세요.
    // ========================================================================
    @Configuration
    @Profile("step02-dual")
    public static class DualProducerConfig {

        private Map<String, Object> base(String bootstrap) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return props;
        }

        /** ① 신뢰성 우선 — 주문 이벤트처럼 절대 잃으면 안 되는 것 */
        @Bean
        public KafkaTemplate<String, OrderCreated> reliableKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = base(bootstrap);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
            props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
            // ★ clientId 를 반드시 다르게. 안 주면 로그/JMX 에서 어느 쪽인지 구분할 수 없다.
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "reliable");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        /** ② 처리량 우선 — 좀 잃어도 되는 클릭 로그 */
        @Bean
        public KafkaTemplate<String, OrderCreated> fastKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = base(bootstrap);
            props.put(ProducerConfig.ACKS_CONFIG, "1");
            // ★ acks=1 이면 enable.idempotence 를 명시적으로 false 로 내려야 한다.
            //   생략하면 클라이언트가 조용히 false 로 내리지만(2-9), 의도를 코드에 남기는 편이 낫다.
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
            props.put(ProducerConfig.LINGER_MS_CONFIG, 50);
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "fast");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    /** 위 두 템플릿을 @Qualifier 로 골라 쓰는 예. step02-dual 프로필에서만 동작한다. */
    @Component
    @Profile("step02-dual")
    public static class DualProducerDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(DualProducerDemo.class);

        private final KafkaTemplate<String, OrderCreated> reliable;
        private final KafkaTemplate<String, OrderCreated> fast;
        private final ProducerFactory<String, OrderCreated> pf;

        public DualProducerDemo(@Qualifier("reliableKafkaTemplate") KafkaTemplate<String, OrderCreated> reliable,
                                @Qualifier("fastKafkaTemplate") KafkaTemplate<String, OrderCreated> fast,
                                ProducerFactory<String, OrderCreated> pf) {
            this.reliable = reliable;
            this.fast = fast;
            this.pf = pf;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 프로듀서는 싱글턴이고 스레드 안전하다 — 매번 만들지 말 것
            Producer<String, OrderCreated> p1 = pf.createProducer();
            Producer<String, OrderCreated> p2 = pf.createProducer();
            log.info("[2-5] createProducer() 두 번 → 같은 인스턴스인가? {}", p1 == p2);
            p1.close();     // DefaultKafkaProducerFactory 가 감싼 프록시라 실제로 닫히지 않는다
            p2.close();

            reliable.send(TOPIC, "ORD-0001", OrderCreated.of(1))
                    .whenComplete((r, ex) -> log.info("[2-5] reliable → {}-{}@{}",
                            r.getRecordMetadata().topic(),
                            r.getRecordMetadata().partition(),
                            r.getRecordMetadata().offset()));

            fast.send(TOPIC, "ORD-0002", OrderCreated.of(2))
                    .whenComplete((r, ex) -> log.info("[2-5] fast → {}-{}@{}",
                            r.getRecordMetadata().topic(),
                            r.getRecordMetadata().partition(),
                            r.getRecordMetadata().offset()));

            // 기동 로그에서 확인하세요:
            //   [Producer clientId=reliable] Instantiated an idempotent producer.   ← 있음
            //   [Producer clientId=fast]     ...                                    ← 없음
        }
    }

    // ========================================================================
    // [2-6] ProducerRecord 직접 만들기 — 헤더 부착, 타임스탬프 지정
    // ========================================================================
    public static class HeaderDemo {

        private static final Logger log = LoggerFactory.getLogger(HeaderDemo.class);
        private final KafkaTemplate<String, OrderCreated> template;

        public HeaderDemo(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() {
            OrderCreated event = OrderCreated.of(7);

            ProducerRecord<String, OrderCreated> record = new ProducerRecord<>(
                    TOPIC,
                    null,                                       // partition: null → 파티셔너에게 맡김
                    event.createdAt().toEpochMilli(),           // timestamp: 이벤트 발생 시각
                    event.orderId(),                            // key
                    event                                       // value
            );
            // ★ 헤더 값은 byte[] 입니다. 인코딩을 반드시 명시하세요.
            //   플랫폼 기본 인코딩에 맡기면 로컬/서버에서 다르게 직렬화되어 한글이 깨집니다.
            record.headers()
                    .add("trace-id", "t-9f2c1a".getBytes(StandardCharsets.UTF_8))
                    .add("event-type", "OrderCreated".getBytes(StandardCharsets.UTF_8))
                    .add("schema-version", "2".getBytes(StandardCharsets.UTF_8));

            template.send(record).whenComplete((r, ex) -> {
                if (ex != null) {
                    log.error("[2-6] 발행 실패", ex);
                    return;
                }
                RecordMetadata md = r.getRecordMetadata();
                log.info("[2-6] → {}-{}@{} ts={}", md.topic(), md.partition(), md.offset(), md.timestamp());
            });

            // ⚠️ 토픽의 message.timestamp.type 이 LogAppendTime 이면
            //    위에서 지정한 타임스탬프는 브로커가 도착 시각으로 덮어씁니다. 에러도 경고도 없습니다.
            //    확인: kafka-configs.sh --describe --entity-type topics --entity-name orders
        }
    }

    // ========================================================================
    // [2-7] 키와 파티셔너 — murmur2 로 예측하고 실제와 대조
    // ========================================================================
    public static class PartitionMapDemo {

        private static final Logger log = LoggerFactory.getLogger(PartitionMapDemo.class);
        private final KafkaTemplate<String, OrderCreated> template;

        public PartitionMapDemo(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() throws Exception {
            int numPartitions = template.partitionsFor(TOPIC).size();
            log.info("[2-7] {} 의 파티션 수 = {} (3이 아니면 교재의 표와 어긋납니다)", TOPIC, numPartitions);

            // (A) 키가 있을 때 — 결정적. 브로커에 안 물어보고 계산할 수 있다.
            for (int i = 1; i <= 9; i++) {
                OrderCreated event = OrderCreated.of(i);
                String key = event.orderId();
                int predicted = predictPartition(key, numPartitions);

                template.send(TOPIC, key, event).whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("[2-7] 발행 실패 key={}", key, ex);
                        return;
                    }
                    int actual = r.getRecordMetadata().partition();
                    log.info("[2-7] {} 예측={} 실제={} offset={} {}",
                            key, predicted, actual, r.getRecordMetadata().offset(),
                            predicted == actual ? "" : "  ← ★불일치! 파티션 수를 확인하세요");
                });
            }
            template.flush();
            Thread.sleep(500);

            // (B) 파티션 수를 바꾸면 매핑이 통째로 바뀐다 — 순서 보장이 소급해서 깨진다
            log.info("[2-7] --- 파티션 수별 매핑 비교 (표 재현) ---");
            log.info("[2-7] {}", "key       murmur2        toPositive     %3  %4  %6");
            for (int i = 1; i <= 9; i++) {
                String key = "ORD-%04d".formatted(i);
                int hash = Utils.murmur2(key.getBytes(StandardCharsets.UTF_8));
                int pos = Utils.toPositive(hash);
                // SLF4J 의 {} 는 정렬 지정자를 지원하지 않으므로 String.format 으로 미리 만든다
                log.info("[2-7] {}", String.format("%-9s %,13d  %,13d   %d   %d   %d",
                        key, hash, pos, pos % 3, pos % 4, pos % 6));
            }

            // (C) 키가 null 이면 — 3.3+ 내장 uniform sticky.
            //     소량이면 "골고루"가 아니라 한 파티션에 몰립니다.
            log.info("[2-7] --- 키 없이 6건 (sticky 확인) ---");
            for (int i = 1; i <= 6; i++) {
                template.send(TOPIC, OrderCreated.of(i)).whenComplete((r, ex) -> {
                    if (ex == null) {
                        log.info("[2-7] key=null → {}-{}@{}",
                                r.getRecordMetadata().topic(),
                                r.getRecordMetadata().partition(),
                                r.getRecordMetadata().offset());
                    }
                });
            }
            template.flush();
        }

        /**
         * Kafka 기본 파티셔너와 동일한 계산.
         * ★ Math.abs(hash) % n 으로 쓰면 안 됩니다.
         *   Math.abs(Integer.MIN_VALUE) 는 여전히 음수라서 음수 파티션이 나옵니다.
         *   Kafka 가 toPositive(= x & 0x7fffffff)를 쓰는 이유가 정확히 이것입니다.
         */
        public static int predictPartition(String key, int numPartitions) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            return Utils.toPositive(Utils.murmur2(bytes)) % numPartitions;
        }
    }

    // ========================================================================
    // [2-8] 명시적 파티션 지정과 그 위험
    // ========================================================================
    public static class PartitionInfoDemo {

        private static final Logger log = LoggerFactory.getLogger(PartitionInfoDemo.class);
        private final KafkaTemplate<String, OrderCreated> template;

        public PartitionInfoDemo(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        public void run() {
            // ★ 파티션 수를 코드에 박지 말고 런타임에 조회한다.
            //   단, partitionsFor 는 메타데이터가 없으면 max.block.ms 까지 블로킹합니다.
            //   요청 경로에서 매번 부르지 말고 기동 시 한 번 조회해 캐시하세요.
            List<PartitionInfo> infos = template.partitionsFor(TOPIC);
            log.info("[2-8] {} 파티션 수 = {}", TOPIC, infos.size());
            infos.stream()
                    .sorted((x, y) -> Integer.compare(x.partition(), y.partition()))
                    .forEach(p -> log.info("[2-8]   partition={} leader={}", p.partition(), p.leader().id()));

            // 정당한 용도: 재처리, 테스트, 파티션 전용 워크로드
            template.send(TOPIC, 2, "ORD-0001", OrderCreated.of(1))
                    .whenComplete((r, ex) -> log.info("[2-8] 명시적 파티션 2 → 실제 {}",
                            r.getRecordMetadata().partition()));

            // ⚠️ 범위를 벗어나면 IllegalArgumentException 이 future 에 담깁니다.
            //    이건 시끄러운 실패라 다행입니다. 진짜 위험한 건 파티션을 늘렸는데
            //    코드가 % 3 으로 하드코딩돼 있어 뒤쪽 파티션이 영원히 비는 경우입니다.
            template.send(TOPIC, 99, "ORD-0002", OrderCreated.of(2))
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.error("[2-8] 범위 밖 파티션 → {}", ex.getCause() == null
                                    ? ex.toString() : ex.getCause().toString());
                        }
                    });
        }
    }

    // ========================================================================
    // [2-9] acks / retries / enable.idempotence 의 상호 제약
    //
    //  아래 설정을 application-step02.yml 에 넣고 기동해 보세요.
    //
    //  (A) 기동 실패 — 명시적으로 켠 멱등성이 acks 와 충돌
    //      spring.kafka.producer.acks: 1
    //      spring.kafka.producer.properties.enable.idempotence: true
    //      → org.apache.kafka.common.config.ConfigException:
    //          Must set acks to all in order to use the idempotent producer.
    //
    //  (B) ⚠️ 진짜 함정 — 조용히 꺼짐
    //      spring.kafka.producer.acks: 1        (enable.idempotence 는 안 씀)
    //      → 기동 성공. 그러나 멱등성은 false 로 내려간다. 경고 한 줄 없다.
    //      → retries 로 인한 중복 + max.in.flight=5 로 인한 순서 역전이 가능해진다.
    //      확인법: 기동 로그에서 다음 한 줄을 찾으세요. 없으면 꺼진 것입니다.
    //          [Producer clientId=producer-1] Instantiated an idempotent producer.
    //
    //  다른 위반 조합들:
    //      enable.idempotence=true + retries=0        → Must set retries to non-zero ...
    //      enable.idempotence=true + max.in.flight>5  → Must set max.in.flight ... at most 5 ...
    //
    //  브로커 쪽 ack/ISR 동작은 이 코스가 아니라 Kafka 코스 소관입니다:
    //      ../../kafka/step-04-producer/
    //      ../../kafka/step-07-delivery-semantics/
    //      ../../kafka/step-08-replication/
    // ========================================================================
    public static final class IdempotenceNotes {
        private IdempotenceNotes() {
        }
    }
}
