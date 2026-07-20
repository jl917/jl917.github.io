/*
 * Step 07 — 전달 보장 : Java 실습
 *
 * at-most-once / at-least-once / exactly-once 를 전부 직접 재현합니다.
 * 이 스텝의 재현은 대부분 "타이밍" 에 달려 있어 CLI 로는 불가능합니다.
 *
 * 실행법 (Java 21 single-file source 실행):
 *
 *   docker cp Practice.java kafka-1:/tmp/
 *   docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
 *
 * 시나리오:
 *   at-most-once                    커밋 → 처리. 처리 중 죽으면 유실
 *   at-most-once-resume             재접속해서 유실 건수 집계
 *   at-least-once                   처리 → 커밋. 커밋 전 죽으면 중복
 *   at-least-once-resume            재접속해서 중복 건수 집계
 *   idempotent                      enable.idempotence=true 로 10건 발행
 *   idempotent-bad-config           acks=1 과 함께 주어 ConfigException 재현
 *   txn-commit                      트랜잭션 커밋
 *   txn-abort                       트랜잭션 중단
 *   txn-hang                        커밋하지 않고 붙잡아 LSO 를 막는다
 *   txn-consume-transform-produce   orders → payments + 오프셋을 한 트랜잭션에
 *   txn-abort-with-file             트랜잭션 안에서 파일에 쓰고 abort (롤백 안 됨)
 *
 * 환경 변수:
 *   S07_BOOTSTRAP      기본 kafka-1:9092
 *   S07_TOPIC          입력 토픽. 기본 s07_orders
 *   S07_OUT_TOPIC      출력 토픽. 기본 s07_payments
 *   S07_GROUP          컨슈머 그룹. 기본은 시나리오별
 *   S07_ACKS           프로듀서 acks. 기본 all (문제 2 에서 1 로 덮어씀)
 *   S07_DIE_AT         몇 건째 처리 후 죽을지
 *   S07_HANG_SECONDS   txn-hang 이 붙잡고 있을 시간(초). 기본 30
 *   S07_TXN_TIMEOUT    transaction.timeout.ms. 기본 60000
 *
 * 주의:
 *   유실/중복 재현은 Runtime.getRuntime().halt(1) 로 죽습니다.
 *   System.exit() 은 셧다운 훅을 돌려 컨슈머를 정상 close 하고 오프셋을 커밋하므로
 *   재현이 되지 않습니다. halt() 는 kill -9 와 같습니다.
 */

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Practice {

    static final String BOOTSTRAP  = env("S07_BOOTSTRAP", "kafka-1:9092");
    static final String IN_TOPIC   = env("S07_TOPIC", "s07_orders");
    static final String OUT_TOPIC  = env("S07_OUT_TOPIC", "s07_payments");
    static final Path   COUNT_FILE = Path.of("/tmp/s07-count.txt");
    static final Path   SIDE_FILE  = Path.of("/tmp/s07-side-effect.log");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "at-most-once"                  -> AtMostOnce.run();
            case "at-most-once-resume"           -> AtMostOnce.resume();
            case "at-least-once"                 -> AtLeastOnce.run();
            case "at-least-once-resume"          -> AtLeastOnce.resume();
            case "idempotent"                    -> Idempotent.run();
            case "idempotent-bad-config"         -> Idempotent.badConfig();
            case "txn-commit"                    -> Txn.commit();
            case "txn-abort"                     -> Txn.abort();
            case "txn-hang"                      -> Txn.hang();
            case "txn-consume-transform-produce" -> Txn.consumeTransformProduce();
            case "txn-abort-with-file"           -> Txn.abortWithFile();
            default -> { System.out.println("알 수 없는 시나리오: " + args[0]); usage(); }
        }
    }

    static void usage() {
        System.out.println("""
            사용법: java -cp "/opt/kafka/libs/*" Practice.java <시나리오>

              at-most-once / at-most-once-resume
              at-least-once / at-least-once-resume
              idempotent / idempotent-bad-config
              txn-commit / txn-abort / txn-hang
              txn-consume-transform-produce
              txn-abort-with-file
            """);
    }

    // =======================================================================
    // 공통
    // =======================================================================

    static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : v;
    }

    static int envInt(String k, int d) {
        try { return Integer.parseInt(env(k, String.valueOf(d))); }
        catch (NumberFormatException e) { return d; }
    }

    static Properties consumerProps(String group) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, env("S07_GROUP", group));
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "40");
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000");
        return p;
    }

    static Properties producerProps() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringSerializer");
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringSerializer");
        p.put(ProducerConfig.ACKS_CONFIG, env("S07_ACKS", "all"));
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");   // 3.0부터 기본값
        return p;
    }

    /** 건당 25ms 의 "처리" */
    static void process(ConsumerRecord<String, String> r) {
        try { Thread.sleep(25); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void die() {
        System.out.println("=== 여기서 프로세스가 죽습니다 ===");
        System.out.flush();
        Runtime.getRuntime().halt(1);
    }

    static void writeCount(int n) {
        try { Files.writeString(COUNT_FILE, String.valueOf(n)); }
        catch (IOException e) { System.out.println("[warn] 카운트 기록 실패: " + e.getMessage()); }
    }

    static int readCount() {
        try { return Integer.parseInt(Files.readString(COUNT_FILE).trim()); }
        catch (Exception e) { System.out.println("[warn] 1회차 카운트 없음. 0 으로 간주"); return 0; }
    }

    static long countTopic(String topic) {
        Properties p = consumerProps("s07-counter");
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "s07-counter-" + System.nanoTime());
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            List<TopicPartition> tps = c.partitionsFor(topic).stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition())).toList();
            Map<TopicPartition, Long> b = c.beginningOffsets(tps);
            Map<TopicPartition, Long> e = c.endOffsets(tps);
            long total = 0;
            for (TopicPartition tp : tps) total += e.get(tp) - b.get(tp);
            return total;
        }
    }

    static String orderIdOf(String json) {
        int i = json.indexOf("\"order_id\":\"");
        if (i < 0) return "UNKNOWN";
        int s = i + 12, e = json.indexOf('"', s);
        return e < 0 ? "UNKNOWN" : json.substring(s, e);
    }

    static String amountOf(String json) {
        int i = json.indexOf("\"amount\":");
        if (i < 0) return "0";
        int s = i + 9, e = s;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        return json.substring(s, e);
    }

    /** orders 레코드를 payments 레코드로 변환 */
    static String transform(String orderJson) {
        return "{\"order_id\":\"" + orderIdOf(orderJson) + "\",\"method\":\"CARD\",\"amount\":"
                + amountOf(orderJson) + ",\"result\":\"APPROVED\"}";
    }

    // =======================================================================
    // 7-2. at-most-once — 커밋 먼저, 처리 나중
    // =======================================================================
    static class AtMostOnce {

        static void run() {
            int dieAt = envInt("S07_DIE_AT", 35);
            Properties p = consumerProps("s07-amo");
            System.out.printf("[setup] 그룹 %s, 커밋 → 처리 순서%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG));

            int processed = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(IN_TOPIC));
                int empty = 0;
                while (empty < 15) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { empty++; continue; }
                    empty = 0;

                    // ★ 커밋을 먼저 한다. 이 한 줄의 위치가 at-most-once 를 만듭니다.
                    consumer.commitSync();
                    long committed = consumer.committed(consumer.assignment())
                            .values().stream().filter(o -> o != null)
                            .mapToLong(OffsetAndMetadata::offset).sum();
                    System.out.printf("[batch] %d건 수신 → commitSync() 먼저 (offset=%d)%n",
                            records.count(), committed);

                    for (ConsumerRecord<String, String> r : records) {
                        process(r);                       // 그다음 처리
                        processed++;
                        System.out.printf("처리 %d건, offset=%d%n", processed, r.offset());
                        if (processed >= dieAt) {
                            writeCount(processed);
                            System.out.printf("[상태] 처리 %d건 / 커밋 오프셋 %d%n", processed, committed);
                            die();
                        }
                    }
                }
            }
            writeCount(processed);
            System.out.println("[끝] 처리 " + processed + "건");
        }

        static void resume() {
            int first = readCount();
            Properties p = consumerProps("s07-amo");
            System.out.println("[resume] 그룹 " + p.get(ConsumerConfig.GROUP_ID_CONFIG)
                    + ", 커밋된 오프셋부터 읽습니다.");

            int second = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(IN_TOPIC));
                int empty = 0;
                while (empty < 10) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { empty++; continue; }
                    empty = 0;
                    consumer.commitSync();
                    for (ConsumerRecord<String, String> r : records) {
                        process(r);
                        second++;
                        System.out.printf("처리 %d건, offset=%d%n", second, r.offset());
                    }
                }
            }
            long total = countTopic(IN_TOPIC);
            long lost = total - (first + second);
            System.out.printf("[집계] 1회차 %d건 + 2회차 %d건 = %d건 처리 / 발행 %d건 → 유실 %d건%n",
                    first, second, first + second, total, Math.max(lost, 0));
        }
    }

    // =======================================================================
    // 7-3. at-least-once — 처리 먼저, 커밋 나중
    // =======================================================================
    static class AtLeastOnce {

        static void run() {
            int dieAt = envInt("S07_DIE_AT", 88);
            Properties p = consumerProps("s07-alo");
            System.out.printf("[setup] 그룹 %s, 처리 → 커밋 순서%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG));

            int processed = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(IN_TOPIC));
                int empty = 0;
                while (empty < 15) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { empty++; continue; }
                    empty = 0;

                    for (ConsumerRecord<String, String> r : records) {
                        process(r);                       // ★ 처리를 먼저
                        processed++;
                        if (processed >= dieAt) {
                            writeCount(processed);
                            System.out.printf("[상태] 처리 %d건 / 이 배치는 아직 커밋되지 않았습니다%n",
                                    processed);
                            die();
                        }
                    }
                    // ★ 배치를 다 처리한 뒤에 커밋. 이 한 줄의 위치가 at-least-once 를 만듭니다.
                    consumer.commitSync();
                    System.out.printf("[batch] %d건 수신 → 처리 → commitSync() (총 %d건)%n",
                            records.count(), processed);
                }
            }
            writeCount(processed);
            System.out.println("[끝] 처리 " + processed + "건");
        }

        static void resume() {
            int first = readCount();
            Properties p = consumerProps("s07-alo");
            System.out.println("[resume] 그룹 " + p.get(ConsumerConfig.GROUP_ID_CONFIG)
                    + ", 커밋된 오프셋부터 다시 읽습니다.");

            int second = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(IN_TOPIC));
                int empty = 0;
                while (empty < 10) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { empty++; continue; }
                    empty = 0;
                    for (ConsumerRecord<String, String> r : records) {
                        process(r);
                        second++;
                        System.out.printf("재처리 %d건, offset=%d%n", second, r.offset());
                    }
                    consumer.commitSync();
                }
            }
            long total = countTopic(IN_TOPIC);
            long dup = (first + second) - total;
            System.out.printf("[집계] 1회차 %d건 + 2회차 %d건 = %d건 처리 / 발행 %d건 → 중복 %d건%n",
                    first, second, first + second, total, Math.max(dup, 0));
        }
    }

    // =======================================================================
    // 7-4. 멱등 프로듀서
    // =======================================================================
    static class Idempotent {

        static void run() throws Exception {
            Properties p = producerProps();
            System.out.println("[setup] enable.idempotence=true (3.0부터 기본값), acks="
                    + p.get(ProducerConfig.ACKS_CONFIG));

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
                for (int i = 1; i <= 10; i++) {
                    String orderId = String.format("O-%d", 3000 + i);
                    String value = "{\"order_id\":\"" + orderId
                            + "\",\"method\":\"CARD\",\"amount\":39000,\"result\":\"APPROVED\"}";
                    RecordMetadata md = producer.send(
                            new ProducerRecord<>(OUT_TOPIC, orderId, value)).get();
                    System.out.printf("[send] %s-%d offset=%d  %s%n",
                            md.topic(), md.partition(), md.offset(), orderId);
                }
            }
            System.out.println("[info] 프로듀서 PID 는 클라이언트 로그에 나옵니다:");
            System.out.println("       [Producer clientId=producer-1] ProducerId set to 1000 with epoch 0");
            System.out.println("[info] kafka-dump-log.sh 로 producerId / baseSequence 를 확인하십시오.");
        }

        /** enable.idempotence=true + acks=1 → 프로듀서 생성 시점에 ConfigException */
        static void badConfig() {
            Properties p = producerProps();
            p.put(ProducerConfig.ACKS_CONFIG, "1");                    // ★ 금지된 조합
            p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

            System.out.println("[setup] enable.idempotence=true + acks=1 로 프로듀서를 만듭니다.");
            System.out.println("[setup] send() 를 호출하기도 전에 생성자에서 터집니다.");

            // try-with-resources 를 쓰지 않습니다. 생성 자체가 실패하기 때문입니다.
            KafkaProducer<String, String> producer = new KafkaProducer<>(p);
            producer.close();
            System.out.println("여기까지 왔다면 Kafka 버전이 예상과 다릅니다.");
        }
    }

    // =======================================================================
    // 7-5 ~ 7-9. 트랜잭션
    // =======================================================================
    static class Txn {

        static Properties txnProps(String txnId) {
            Properties p = producerProps();
            p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, txnId);
            p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");   // transactional.id 를 주면 강제
            p.put(ProducerConfig.ACKS_CONFIG, "all");                  // 트랜잭션에서는 all 만 가능
            p.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, env("S07_TXN_TIMEOUT", "60000"));
            return p;
        }

        static String payment(int n) {
            return "{\"order_id\":\"O-" + (3000 + n)
                    + "\",\"method\":\"CARD\",\"amount\":39000,\"result\":\"APPROVED\"}";
        }

        /** 7-5 : 커밋 */
        static void commit() {
            Properties p = txnProps("s07-txn-commit");
            System.out.println("[setup] transactional.id=s07-txn-commit");

            KafkaProducer<String, String> producer = new KafkaProducer<>(p);
            try {
                producer.initTransactions();
                System.out.println("[init] initTransactions() 완료");
                producer.beginTransaction();
                System.out.println("[begin] beginTransaction()");
                for (int i = 11; i <= 13; i++) {
                    String id = "O-" + (3000 + i);
                    producer.send(new ProducerRecord<>(OUT_TOPIC, id, payment(i)));
                    System.out.println("[send] " + OUT_TOPIC + " " + id);
                }
                producer.commitTransaction();
                System.out.println("[commit] commitTransaction() 완료");
                System.out.println("[확인] 이 3건은 read_committed 컨슈머에게도 보입니다.");
            } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
                // 회복 불가능. abortTransaction() 을 부르면 또 예외가 납니다. 그냥 버립니다.
                System.out.println("[fatal] 회복 불가능한 예외: " + e);
            } catch (KafkaException e) {
                producer.abortTransaction();
                System.out.println("[abort] 예외로 중단: " + e);
            } finally {
                producer.close();
            }
        }

        /** 7-5 : 중단 */
        static void abort() {
            Properties p = txnProps("s07-txn-abort");
            System.out.println("[setup] transactional.id=s07-txn-abort");

            KafkaProducer<String, String> producer = new KafkaProducer<>(p);
            try {
                producer.initTransactions();
                System.out.println("[init] initTransactions() 완료");
                producer.beginTransaction();
                System.out.println("[begin] beginTransaction()");
                for (int i = 14; i <= 16; i++) {
                    String id = "O-" + (3000 + i);
                    producer.send(new ProducerRecord<>(OUT_TOPIC, id, payment(i)));
                    System.out.println("[send] " + OUT_TOPIC + " " + id);
                }
                producer.abortTransaction();      // ★ 커밋하지 않고 중단
                System.out.println("[abort] abortTransaction() 완료");
                System.out.println("[확인] 이 3건은 로그에 남아 있지만 "
                        + "read_committed 컨슈머에게는 안 보입니다.");
            } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
                System.out.println("[fatal] 회복 불가능한 예외: " + e);
            } finally {
                producer.close();
            }
        }

        /** 7-7 : 커밋도 abort 도 하지 않고 붙잡아 LSO 를 고정시킵니다 */
        static void hang() throws Exception {
            int seconds = envInt("S07_HANG_SECONDS", 30);
            Properties p = txnProps("s07-txn-hang");
            System.out.println("[setup] transactional.id=s07-txn-hang, transaction.timeout.ms="
                    + p.get(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG));

            KafkaProducer<String, String> producer = new KafkaProducer<>(p);
            try {
                producer.initTransactions();
                producer.beginTransaction();
                System.out.println("[begin] beginTransaction()");
                for (int i = 20; i <= 21; i++) {
                    String id = "O-" + (3000 + i);
                    producer.send(new ProducerRecord<>(OUT_TOPIC, id, payment(i)));
                    System.out.println("[send] " + OUT_TOPIC + " " + id);
                }
                producer.flush();      // 브로커에 실제로 도착시킵니다. 트랜잭션은 열린 채입니다.
                System.out.printf("[hang] %d초 동안 커밋하지 않고 붙잡고 있습니다...%n", seconds);
                System.out.println("[hang] 이 동안 read_committed 컨슈머는 아무것도 못 읽습니다.");
                Thread.sleep(seconds * 1000L);
                producer.commitTransaction();
                System.out.println("[commit] commitTransaction() 완료");
            } catch (ProducerFencedException e) {
                // transaction.timeout.ms 가 지나 코디네이터가 이미 강제 abort 한 경우입니다.
                System.out.println("[fenced] 코디네이터가 트랜잭션을 이미 중단시켰습니다: " + e.getMessage());
            } catch (KafkaException e) {
                System.out.println("[error] " + e);
            } finally {
                producer.close();
            }
        }

        /** 7-8 : consume-transform-produce. 출력과 오프셋이 같은 트랜잭션에 들어갑니다 */
        static void consumeTransformProduce() {
            int dieAt = envInt("S07_DIE_AT", -1);

            Properties cp = consumerProps("s07-ctp");
            // ★ 이 두 줄이 없으면 exactly-once 가 아닙니다.
            cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            cp.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

            Properties pp = txnProps("s07-ctp");

            System.out.printf("[setup] 입력 %s / 출력 %s / transactional.id=s07-ctp%n",
                    IN_TOPIC, OUT_TOPIC);
            System.out.println("[setup] consumer: enable.auto.commit=false, "
                    + "isolation.level=read_committed");

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cp);
            KafkaProducer<String, String> producer = new KafkaProducer<>(pp);
            int processed = 0, txnNo = 0;
            long start = System.nanoTime();

            try {
                producer.initTransactions();
                System.out.println("[init] initTransactions() 완료");
                consumer.subscribe(List.of(IN_TOPIC));

                int empty = 0;
                while (empty < 12) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { empty++; continue; }
                    empty = 0;
                    txnNo++;

                    producer.beginTransaction();
                    try {
                        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                        for (ConsumerRecord<String, String> r : records) {
                            String out = transform(r.value());
                            producer.send(new ProducerRecord<>(OUT_TOPIC, orderIdOf(r.value()), out));
                            // ★ 여기도 마지막 처리 오프셋 + 1 입니다.
                            offsets.put(new TopicPartition(r.topic(), r.partition()),
                                        new OffsetAndMetadata(r.offset() + 1));
                            processed++;
                            if (dieAt > 0 && processed >= dieAt) {
                                System.out.printf("[txn #%d] %d건 처리 시점 — "
                                        + "커밋하지 않은 채 죽습니다%n", txnNo, processed);
                                die();
                            }
                        }
                        // ★ 오프셋 커밋을 트랜잭션에 포함시킵니다.
                        //    consumer.commitSync() 를 부르면 안 됩니다. 그건 트랜잭션 밖입니다.
                        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                        producer.commitTransaction();

                        long committed = offsets.values().iterator().next().offset();
                        System.out.printf("[txn #%d] %d건 읽음 → %d건 변환 → "
                                        + "sendOffsetsToTransaction({%s-0: %d}) → commit%n",
                                txnNo, records.count(), records.count(), IN_TOPIC, committed);
                    } catch (ProducerFencedException | OutOfOrderSequenceException
                             | AuthorizationException e) {
                        System.out.println("[fatal] " + e);
                        break;
                    } catch (KafkaException e) {
                        producer.abortTransaction();
                        System.out.println("[abort] " + e);
                    }
                }
                double sec = (System.nanoTime() - start) / 1e9;
                System.out.printf("[집계] 입력 %d건 → 출력 %d건. 유실 0, 중복 0%n", processed, processed);
                System.out.printf("[측정] %.2f초 → %.1f msg/s (트랜잭션 %d회)%n",
                        sec, processed / sec, txnNo);
            } finally {
                producer.close();
                consumer.close(Duration.ofSeconds(5));
            }
        }

        /** 7-9 : 트랜잭션 안에서 파일에 쓰고 abort. 파일은 롤백되지 않습니다 */
        static void abortWithFile() throws Exception {
            Properties p = txnProps("s07-txn-file");
            System.out.println("[setup] 트랜잭션 안에서 " + SIDE_FILE + " 에 append 합니다.");

            KafkaProducer<String, String> producer = new KafkaProducer<>(p);
            try {
                producer.initTransactions();
                producer.beginTransaction();

                for (int i = 1; i <= 3; i++) {
                    String orderId = String.format("O-%d", 4000 + i);

                    // ★ 외부 부수효과. 이건 Kafka 트랜잭션에 들어가지 않습니다.
                    Files.writeString(SIDE_FILE, orderId + " charged\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    System.out.println("[file] " + orderId + " charged  (파일에 기록)");

                    producer.send(new ProducerRecord<>(OUT_TOPIC, orderId,
                            "{\"order_id\":\"" + orderId + "\",\"note\":\"SIDE-EFFECT\"}"));
                    System.out.println("[send] " + OUT_TOPIC + " " + orderId);
                }

                producer.abortTransaction();
                System.out.println("[abort] abortTransaction() 완료");
                System.out.println("[확인] Kafka 메시지는 걸러지지만 " + SIDE_FILE
                        + " 의 3줄은 그대로 남습니다.");
                System.out.println("[확인] docker exec kafka-1 cat " + SIDE_FILE);
            } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
                System.out.println("[fatal] " + e);
            } finally {
                producer.close();
            }
        }
    }
}
