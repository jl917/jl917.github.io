// ============================================================================
// Step 13 — Kafka Streams / Practice.java
//
// 실행법 (Java 21 single-file source 실행. 컴파일 단계 없음):
//   docker cp Practice.java kafka-1:/tmp/
//   docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <scenario>'
//
// 의존성은 Kafka 배포판의 /opt/kafka/libs/* 뿐입니다.
//   kafka-streams-3.7.1.jar, kafka-clients-3.7.1.jar, rocksdbjni-*.jar 등이 들어 있습니다.
//
// 시나리오:
//   topology [name]  토폴로지만 출력하고 종료 (name: stateless|selectkey|count|window|join)
//   stateless        filter / map / mapValues / flatMap / branch / merge / peek
//   selectkey        selectKey → repartition 토픽 생성 관찰
//   count            groupByKey().count() → changelog 토픽과 RocksDB
//   window           1분 텀블링 윈도우. --inject-late 로 grace 초과 레코드 주입
//   suppress         suppress(untilWindowCloses) 로 최종 결과만
//   join             orders × payments 윈도우 조인
//   copartition      co-partitioning 위반 재현. --fix 로 수정판
//   eos              processing.guarantee=exactly_once_v2
//
// 사전 토픽 (practice.sh 가 만듭니다):
//   s13_orders(3) s13_payments(3) s13_out(3) s13_left(3) s13_right(6)
// ============================================================================

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class Practice {

    static final String BOOTSTRAP = "kafka-1:9092";

    static final String T_ORDERS   = "s13_orders";
    static final String T_PAYMENTS = "s13_payments";
    static final String T_OUT      = "s13_out";
    static final String T_LEFT     = "s13_left";
    static final String T_RIGHT    = "s13_right";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        String scenario = args[0];
        String opt = args.length > 1 ? args[1] : "";

        switch (scenario) {
            case "topology"    -> TopologyOnly.run(opt);
            case "stateless"   -> new Stateless().run(opt);
            case "selectkey"   -> new SelectKey().run(opt);
            case "count"       -> new Count().run(opt);
            case "window"      -> new Window().run(opt);
            case "suppress"    -> new Suppress().run(opt);
            case "join"        -> new Join().run(opt);
            case "copartition" -> new CoPartition().run(opt);
            case "eos"         -> new Eos().run(opt);
            default            -> usage();
        }
    }

    static void usage() {
        System.out.println("""
            usage: java -cp "/opt/kafka/libs/*" Practice.java <scenario>

              topology   토폴로지 설명만 출력하고 종료 (실행하지 않음)
              stateless  filter / map / mapValues / flatMap / branch / merge / peek
              selectkey  selectKey → repartition 토픽이 생기는 것을 관찰
              count      groupByKey().count() → changelog 토픽과 상태 저장소
              window     1분 텀블링 윈도우 집계 + grace period
              suppress   suppress(untilWindowCloses) 로 최종 결과만
              join       orders × payments 윈도우 조인
              copartition co-partitioning 위반 재현 (TopologyException)
              eos        processing.guarantee=exactly_once_v2

              예) java -cp "/opt/kafka/libs/*" Practice.java count
            """);
    }

    // ------------------------------------------------------------------------
    // 공통 설정
    //
    // ★ REPLICATION_FACTOR_CONFIG = 3 이 중요합니다.
    //   Streams 의 기본값은 1 입니다. 브로커 기본값(default.replication.factor=3)을
    //   따르지 않습니다. 그대로 두면 repartition/changelog 토픽이 RF=1 로 만들어지고,
    //   브로커 한 대가 죽는 순간 상태를 잃습니다. 운영 체크리스트 1번 항목입니다.
    // ------------------------------------------------------------------------
    static Properties baseProps(String appId) {
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, appId);
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        p.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        p.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);        // ★ 기본 1 → 3
        p.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams");
        p.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);
        p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);     // 관찰을 빠르게 (기본 30000)
        p.put(StreamsConfig.consumerPrefix("auto.offset.reset"), "earliest");
        return p;
    }

    // 값 JSON 에서 필드 하나를 아주 단순하게 뽑습니다.
    // 실무라면 Jackson 을 쓰지만, 여기서는 /opt/kafka/libs/* 밖의 의존성을 안 쓰기 위해
    // 문자열 파싱으로 대신합니다. 값 포맷이 고정된 실습이라 문제없습니다.
    static String field(String json, String name) {
        String needle = "\"" + name + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int s = i + needle.length();
        while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '"')) s++;
        int e = s;
        while (e < json.length() && json.charAt(e) != '"' && json.charAt(e) != ','
                && json.charAt(e) != '}') e++;
        return json.substring(s, e).trim();
    }

    static long amountOf(String json) {
        String a = field(json, "amount");
        try { return a == null ? 0L : Long.parseLong(a); } catch (Exception e) { return 0L; }
    }

    static String orderIdOf(String json) {
        String o = field(json, "order_id");
        return o == null ? "UNKNOWN" : o;
    }

    // Streams 앱을 띄우고 Ctrl+C 까지 대기합니다.
    // shutdown hook 으로 close() 를 걸어 두어야 컨슈머 그룹에서 깨끗이 빠집니다.
    // 안 그러면 13-11 의 reset 도구가 "still active" 로 거부합니다.
    static void start(Topology topology, Properties props) {
        System.out.println("[TOPOLOGY]\n" + topology.describe());
        KafkaStreams streams = new KafkaStreams(topology, props);
        CountDownLatch latch = new CountDownLatch(1);

        streams.setStateListener((now, old) ->
                System.out.printf("[STATE] %s → %s%n", old, now));
        streams.setUncaughtExceptionHandler(e -> {
            System.err.println("[FATAL] " + e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] closing...");
            streams.close(Duration.ofSeconds(10));
            latch.countDown();
        }));

        streams.start();
        System.out.println("[START] application.id = " + props.get(StreamsConfig.APPLICATION_ID_CONFIG));
        System.out.println("[START] Ctrl+C 로 종료합니다.");
        try { latch.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // 타임스탬프를 직접 지정해서 보내는 프로듀서.
    // 콘솔 프로듀서로는 타임스탬프를 못 정하므로 grace 실습은 Java 로만 가능합니다.
    static void sendAt(String topic, String key, String value, long timestampMs) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        try (Producer<String, String> prod = new KafkaProducer<>(p)) {
            prod.send(new ProducerRecord<>(topic, null, timestampMs, key, value));
            prod.flush();
            System.out.printf("[PRODUCE] %s %s ts=%s%n", key, value, Instant.ofEpochMilli(timestampMs));
        }
    }

    interface Scenario { void run(String opt) throws Exception; }

    // ========================================================================
    // topology — 실행하지 않고 describe() 만 출력
    // ========================================================================
    static class TopologyOnly {
        static void run(String which) {
            StreamsBuilder b = new StreamsBuilder();
            switch (which.isEmpty() ? "stateless" : which) {
                case "selectkey" -> {
                    b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
                     .selectKey((k, v) -> orderIdOf(v))
                     .groupByKey()
                     .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-counts")
                             .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
                     .toStream()
                     .to(T_OUT, Produced.with(Serdes.String(), Serdes.Long()));
                }
                case "count" -> {
                    b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
                     .groupByKey()
                     .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-counts")
                             .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
                     .toStream()
                     .to(T_OUT, Produced.with(Serdes.String(), Serdes.Long()));
                }
                case "window" -> {
                    b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
                     .groupByKey()
                     .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30)))
                     .aggregate(() -> 0L, (k, v, agg) -> agg + amountOf(v),
                             Materialized.<String, Long, WindowStore<Bytes, byte[]>>as("amount-per-min")
                                     .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
                     .toStream()
                     .foreach((wk, sum) -> { });
                }
                case "join" -> {
                    KStream<String, String> orders   = b.stream(T_ORDERS);
                    KStream<String, String> payments = b.stream(T_PAYMENTS);
                    orders.selectKey((k, v) -> orderIdOf(v))
                          .join(payments,
                                  (o, p) -> "{\"order\":" + o + ",\"payment\":" + p + "}",
                                  JoinWindows.ofTimeDifferenceAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                                  StreamJoined.with(Serdes.String(), Serdes.String(), Serdes.String()))
                          .to(T_OUT);
                }
                default -> {
                    b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
                     .filter((k, v) -> v.contains("CREATED"))
                     .mapValues(String::toUpperCase)
                     .to(T_OUT, Produced.with(Serdes.String(), Serdes.String()));
                }
            }
            System.out.println(b.build().describe());
            // 서브토폴로지가 2개 이상이면 그 경계마다 repartition 토픽이 끼어들고,
            // 데이터가 Kafka 를 한 번 왕복합니다. 즉 서브토폴로지 개수 = 네트워크 왕복 횟수.
        }
    }

    // ========================================================================
    // stateless — 상태를 안 쓰는 연산들
    // ========================================================================
    static class Stateless implements Scenario {
        public void run(String opt) {
            StreamsBuilder b = new StreamsBuilder();

            KStream<String, String> src = b.stream(T_ORDERS,
                    Consumed.with(Serdes.String(), Serdes.String()));

            // peek — 아무것도 안 바꾸고 들여다보기만 합니다. 디버깅용.
            KStream<String, String> seen =
                    src.peek((k, v) -> System.out.printf("[PEEK-in ] %s → %s%n", k, v));

            // filter — CANCELLED 는 버립니다.
            KStream<String, String> live = seen
                    .peek((k, v) -> {
                        if (v.contains("CANCELLED"))
                            System.out.printf("[filter  ] CANCELLED 제외됨: %s%n", orderIdOf(v));
                    })
                    .filter((k, v) -> !v.contains("CANCELLED"));

            // branch / split — 금액으로 두 갈래로 나눕니다.
            Map<String, KStream<String, String>> branches = live.split(Named.as("br-"))
                    .branch((k, v) -> amountOf(v) >= 10000, Branched.as("big"))
                    .defaultBranch(Branched.as("small"));

            KStream<String, String> big = branches.get("br-big")
                    .peek((k, v) -> System.out.printf("[branch  ] BIG   ← %s amount=%d%n", k, amountOf(v)));
            KStream<String, String> small = branches.get("br-small")
                    .peek((k, v) -> System.out.printf("[branch  ] SMALL ← %s amount=%d%n", k, amountOf(v)));

            // merge — 다시 하나로 합칩니다.
            // ★ mapValues 를 씁니다. map 이 아니라. map 은 키를 안 바꿔도
            //   "바뀌었을 수 있다"고 표시되어 다음 집계 앞에 repartition 을 유발합니다.
            big.merge(small)
               .mapValues(String::toUpperCase)
               .peek((k, v) -> System.out.printf("[merge   ] → %s%n", k))
               .to(T_OUT, Produced.with(Serdes.String(), Serdes.String()));

            // 이 토폴로지는 스테이트리스이므로 내부 토픽이 하나도 안 생깁니다.
            // kt --list 로 확인해 보세요.
            start(b.build(), baseProps("s13-stateless-app"));
        }
    }

    // ========================================================================
    // selectkey — 키를 바꾸면 repartition 토픽이 생깁니다
    // ========================================================================
    static class SelectKey implements Scenario {
        public void run(String opt) {
            StreamsBuilder b = new StreamsBuilder();

            b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
             // 키를 customer_id → order_id 로 바꿉니다.
             // ★ 이 순간 레코드는 여전히 "원래 파티션"에 있습니다. 키만 바뀌었습니다.
             //   같은 order_id 가 여러 파티션에 흩어져 있을 수 있으므로,
             //   집계 전에 Streams 가 repartition 토픽에 다시 써서 재분배합니다.
             .selectKey((k, v) -> orderIdOf(v))
             .groupByKey()
             .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-counts")
                     .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
             .toStream()
             .peek((k, c) -> System.out.printf("[COUNT] %s = %d%n", k, c))
             .to(T_OUT, Produced.with(Serdes.String(), Serdes.Long()));

            // 실행 후 생기는 토픽:
            //   s13-selectkey-app-order-counts-repartition  (cleanup.policy=delete)
            //   s13-selectkey-app-order-counts-changelog    (cleanup.policy=compact)
            start(b.build(), baseProps("s13-selectkey-app"));
        }
    }

    // ========================================================================
    // count — 집계와 상태 저장소
    // ========================================================================
    static class Count implements Scenario {
        public void run(String opt) {
            StreamsBuilder b = new StreamsBuilder();

            // groupByKey() 는 키를 안 바꾸므로 repartition 토픽이 안 생깁니다.
            // groupBy((k,v) -> ...) 였다면 생깁니다. 이 차이를 기억하세요.
            b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
             .groupByKey()
             .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-counts")
                     .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
             .toStream()
             // KTable 은 "변경로그"이므로 값이 바뀔 때마다 갱신을 내보냅니다.
             // C001 이 1, 2, 3 으로 세 번 나옵니다. 최종값 하나만 원하면 suppress 시나리오로.
             .peek((k, c) -> System.out.printf("[COUNT] %s = %d%n", k, c))
             .to(T_OUT, Produced.with(Serdes.String(), Serdes.Long()));

            // 상태는 두 곳에 있습니다:
            //   ① /tmp/kafka-streams/s13-count-app/0_0/rocksdb/order-counts/  (로컬, 빠른 읽기)
            //   ② s13-count-app-order-counts-changelog                        (Kafka, 진짜 원본)
            // 재기동 시 ① 이 없으면 ② 를 처음부터 읽어 복원합니다.
            start(b.build(), baseProps("s13-count-app"));
        }
    }

    // ========================================================================
    // window — 1분 텀블링 + grace period
    //   --inject-late 를 주면 grace 를 넘긴 레코드를 하나 넣어 "조용히 버려지는 것"을 재현
    // ========================================================================
    static class Window implements Scenario {
        public void run(String opt) throws Exception {
            boolean injectLate = "--inject-late".equals(opt);

            StreamsBuilder b = new StreamsBuilder();

            b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
             .groupByKey()
             // ofSizeAndGrace(크기, grace)
             //   grace = "윈도우가 끝난 뒤 얼마나 더 늦은 레코드를 받아 줄지"
             //   3.0 부터 grace 를 명시하도록 강제되었습니다.
             //   TimeWindows.of(size) 는 deprecated 이고 옛 기본 grace 는 24시간이었습니다.
             //   ofSizeWithNoGrace() 로 마이그레이션하면 grace 가 0 이 되어
             //   조금만 늦어도 전부 버려집니다. 3.x 마이그레이션의 대표 사고 지점입니다.
             .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30)))
             .aggregate(() -> 0L,
                     (k, v, agg) -> agg + amountOf(v),
                     Materialized.<String, Long, WindowStore<Bytes, byte[]>>as("amount-per-min")
                             .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
             .toStream()
             .foreach((wk, sum) -> System.out.printf("[WINDOW ] %s [%s ~ %s] sum=%d%n",
                     wk.key(), wk.window().startTime(), wk.window().endTime(), sum));

            Properties props = baseProps("s13-window-app");
            KafkaStreams streams = new KafkaStreams(b.build(), props);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> streams.close(Duration.ofSeconds(10))));

            System.out.println("[TOPOLOGY]\n" + b.build().describe());
            streams.start();
            Thread.sleep(12_000);   // 리밸런싱 대기

            if (injectLate) {
                // 윈도우 경계는 에폭 기준 절대 시각입니다. 지금 시각을 1분 단위로 내림합니다.
                long base = (System.currentTimeMillis() / 60000L) * 60000L;

                sendAt(T_ORDERS, "C001", "{\"order_id\":\"O-1001\",\"amount\":10000}", base + 10_000);
                Thread.sleep(3000);
                sendAt(T_ORDERS, "C001", "{\"order_id\":\"O-1002\",\"amount\":20000}", base + 40_000);
                Thread.sleep(3000);

                // 스트림 시간을 윈도우 끝 + 20초로 밀어 올립니다. (grace 30초 안 → 아직 유효)
                sendAt(T_ORDERS, "C001", "{\"order_id\":\"O-1003\",\"amount\":0}",     base + 80_000);
                Thread.sleep(2000);
                sendAt(T_ORDERS, "C001", "{\"order_id\":\"O-1004\",\"amount\":7000}",  base + 50_000);
                Thread.sleep(3000);

                // 스트림 시간을 윈도우 끝 + 45초로 밀어 올립니다. (grace 30초 초과)
                sendAt(T_ORDERS, "C001", "{\"order_id\":\"O-1005\",\"amount\":0}",     base + 105_000);
                Thread.sleep(2000);

                System.out.println("\n★ 이제 grace 를 넘긴 레코드를 넣습니다. 아무 출력도 없을 것입니다.");
                sendAt(T_ORDERS, "C001", "{\"order_id\":\"O-1006\",\"amount\":99000}", base + 55_000);
                Thread.sleep(5000);

                // dropped-records-total 지표를 읽습니다.
                // ★ 이 지표가 0 이 아니면 데이터가 조용히 버려지고 있다는 뜻입니다.
                //   예외도 안 나고 기본 로그 레벨에서는 아무것도 안 찍히므로,
                //   이 지표에 알람을 거는 것이 유일한 방어입니다.
                streams.metrics().forEach((name, metric) -> {
                    if ("dropped-records-total".equals(name.name())) {
                        System.out.printf("[METRIC ] %s (task=%s) = %s%n",
                                name.name(), name.tags().get("task-id"), metric.metricValue());
                    }
                });
                System.out.println("\n★ 99000 이 합계에 반영되지 않았습니다. 에러는 없었습니다.");
            } else {
                System.out.println("[HINT] 다른 터미널에서 kafka-console-producer.sh 로 s13_orders 에 넣어 보세요.");
                System.out.println("[HINT] grace 초과 재현은: Practice.java window --inject-late");
            }

            new CountDownLatch(1).await();
        }
    }

    // ========================================================================
    // suppress — 윈도우가 닫힌 뒤 최종 결과만
    // ========================================================================
    static class Suppress implements Scenario {
        public void run(String opt) throws Exception {
            StreamsBuilder b = new StreamsBuilder();

            b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
             .groupByKey()
             .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30)))
             .aggregate(() -> 0L,
                     (k, v, agg) -> agg + amountOf(v),
                     Materialized.<String, Long, WindowStore<Bytes, byte[]>>as("amount-suppressed")
                             .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
             // ⚠️ untilWindowCloses 는 "스트림 시간"이 윈도우끝+grace 를 넘어야 방출합니다.
             //    스트림 시간은 레코드가 들어와야 전진합니다. 트래픽이 멈추면
             //    마지막 윈도우는 영원히 방출되지 않습니다.
             //    "suppress 를 걸었더니 아무것도 안 나온다"의 열에 아홉이 이것입니다.
             //
             // ⚠️ BufferConfig.unbounded() 는 방출 전까지 모든 윈도우를 메모리에 들고 있습니다.
             //    키가 많으면 OOM 입니다. 운영에서는:
             //      Suppressed.BufferConfig.maxBytes(50_000_000L).emitEarlyWhenFull()
             .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
             .toStream()
             .foreach((wk, sum) -> System.out.printf("[FINAL  ] %s [%s ~ %s] sum=%d%n",
                     wk.key(), wk.window().startTime(), wk.window().endTime(), sum));

            System.out.println("[NOTE] 중간 결과는 안 나옵니다. 윈도우가 닫혀야 나옵니다.");
            System.out.println("[NOTE] 그리고 윈도우를 닫으려면 '다음 레코드'가 들어와야 합니다.");
            start(b.build(), baseProps("s13-suppress-app"));
        }
    }

    // ========================================================================
    // join — orders × payments 윈도우 조인
    // ========================================================================
    static class Join implements Scenario {
        public void run(String opt) {
            StreamsBuilder b = new StreamsBuilder();

            KStream<String, String> orders   = b.stream(T_ORDERS,
                    Consumed.with(Serdes.String(), Serdes.String()));
            KStream<String, String> payments = b.stream(T_PAYMENTS,
                    Consumed.with(Serdes.String(), Serdes.String()));

            // orders 의 키는 customer_id, payments 의 키는 order_id 입니다.
            // 조인하려면 키를 맞춰야 하므로 orders 쪽 키를 order_id 로 바꿉니다.
            // → 이 selectKey 때문에 repartition 토픽이 하나 생깁니다.
            orders.selectKey((k, v) -> orderIdOf(v))
                  .join(payments,
                          (o, p) -> "{\"order\":" + o + ",\"payment\":" + p + "}",
                          // KStream-KStream 조인은 윈도우가 필수입니다.
                          // "양쪽 레코드의 타임스탬프 차이가 5분 이내면 조인"이라는 뜻입니다.
                          JoinWindows.ofTimeDifferenceAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                          StreamJoined.with(Serdes.String(), Serdes.String(), Serdes.String()))
                  .peek((k, v) -> System.out.printf("[JOIN] %s%n", k))
                  .to(T_OUT, Produced.with(Serdes.String(), Serdes.String()));

            // KStream-KStream 조인은 양쪽 모두 윈도우 스토어를 만듭니다.
            //   s13-join-app-KSTREAM-JOINTHIS-...-store-changelog
            //   s13-join-app-KSTREAM-JOINOTHER-...-store-changelog
            start(b.build(), baseProps("s13-join-app"));
        }
    }

    // ========================================================================
    // copartition — co-partitioning 위반 재현
    //   s13_left(3) × s13_right(6) 을 조인하면 TopologyException 이 납니다.
    //   --fix 를 주면 repartition 으로 파티션 수를 맞춘 버전이 돌아갑니다.
    // ========================================================================
    static class CoPartition implements Scenario {
        public void run(String opt) {
            boolean fix = "--fix".equals(opt);
            StreamsBuilder b = new StreamsBuilder();

            KStream<String, String> left = b.stream(T_LEFT,
                    Consumed.with(Serdes.String(), Serdes.String()));

            KStream<String, String> right;
            if (fix) {
                System.out.println("[FIX] s13_right(6) → repartition(3) 로 맞춥니다");
                // repartition() 은 지정한 파티션 수의 새 토픽을 만들고 거기로 다시 씁니다.
                // 토픽 이름: s13-copart-app-right-fixed-repartition
                right = b.<String, String>stream(T_RIGHT,
                                Consumed.with(Serdes.String(), Serdes.String()))
                         .repartition(Repartitioned.<String, String>as("right-fixed")
                                 .withNumberOfPartitions(3)
                                 .withKeySerde(Serdes.String())
                                 .withValueSerde(Serdes.String()));
            } else {
                System.out.println("[BROKEN] s13_left(3) × s13_right(6) 을 그대로 조인합니다");
                right = b.stream(T_RIGHT, Consumed.with(Serdes.String(), Serdes.String()));
            }

            left.join(right,
                      (l, r) -> "left=" + l + " right=" + r,
                      JoinWindows.ofTimeDifferenceAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                      StreamJoined.with(Serdes.String(), Serdes.String(), Serdes.String()))
                .foreach((k, v) -> System.out.printf("[JOIN] %s  ← %s%n", k, v));

            Topology t = b.build();
            System.out.println("[TOPOLOGY]\n" + t.describe());

            // ★ 파티션 수 불일치는 build() 가 아니라 "파티션 할당 시점"에 터집니다.
            //   즉 start() 이후 첫 리밸런싱에서 이 예외가 나옵니다:
            //
            //   org.apache.kafka.streams.errors.TopologyException: Invalid topology:
            //     Following topics do not have the same number of partitions:
            //     [s13_left(3), s13_right(6)]
            //
            //   ⚠️ co-partitioning 의 세 조건 중 Streams 가 검증하는 것은 1번뿐입니다.
            //     1. 파티션 수가 같을 것              ← 검증됨 (위 예외)
            //     2. 키의 타입/직렬화가 같을 것        ← 검증 안 됨
            //     3. 파티셔너가 같을 것                ← 검증 안 됨. 조인이 그냥 안 됩니다.
            //   3번이 진짜 함정입니다. 예외도 로그도 없이 결과가 비어 있습니다.
            start(t, baseProps("s13-copart-app"));
        }
    }

    // ========================================================================
    // eos — exactly_once_v2
    // ========================================================================
    static class Eos implements Scenario {
        public void run(String opt) {
            StreamsBuilder b = new StreamsBuilder();

            b.stream(T_ORDERS, Consumed.with(Serdes.String(), Serdes.String()))
             .groupByKey()
             .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("order-counts")
                     .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long()))
             .toStream()
             .peek((k, c) -> System.out.printf("[COUNT] %s = %d%n", k, c))
             .to(T_OUT, Produced.with(Serdes.String(), Serdes.Long()));

            Properties props = baseProps("s13-eos-app");
            // ★ 이 한 줄이 Step 07 에서 손으로 짠 트랜잭션 코드를 전부 대체합니다.
            props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

            System.out.println("[CONFIG] processing.guarantee = exactly_once_v2");
            System.out.println("[CONFIG] → 내부적으로 강제되는 값:");
            System.out.println("           enable.idempotence          = true");
            System.out.println("           max.in.flight.requests...   = 5");
            System.out.println("           acks                        = all");
            System.out.println("           transactional.id            = s13-eos-app-<uuid>-<taskId>");
            System.out.println("           isolation.level             = read_committed");
            System.out.println("           commit.interval.ms          = 100   (기본 30000 에서 변경됨)");
            System.out.println();
            System.out.println("[NOTE] 보장 범위: 입력 오프셋 커밋 + 상태 갱신 + 출력 쓰기가 한 트랜잭션.");
            System.out.println("[NOTE] ⚠️ Kafka 안에서만 유효합니다. 처리 로직에서 외부 API 를 호출하면");
            System.out.println("       그것은 트랜잭션 밖이라 롤백돼도 취소되지 않습니다.");
            System.out.println("[NOTE] 다운스트림 컨슈머는 isolation.level=read_committed 여야 합니다.");
            System.out.println();

            start(b.build(), props);
        }
    }
}
