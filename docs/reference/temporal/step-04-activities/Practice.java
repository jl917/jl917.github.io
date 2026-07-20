package com.example.order;

// =====================================================================================
// Step 04 — 액티비티 : Practice
//
// 실행 방법
//   ./gradlew run -PmainClass=com.example.order.Practice
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--timeout"        [4-3]
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--no-worker"      [4-3] S2S
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--heartbeat"      [4-4]
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--double-charge"  [4-6]
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--local"          [4-7]
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--race"           [4-8]
//
// 사전 조건
//   docker compose up -d      (Temporal Server 1.22.4, gRPC 127.0.0.1:7233)
//
// 주의
//   타임아웃 실습은 **의도적으로 실패하는** 워크플로우를 돌립니다.
//   실행 후 temporal workflow list 에 FAILED 가 쌓이는 것이 정상입니다.
// =====================================================================================

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Practice {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";
    public static final String MEDIA_TASK_QUEUE = "MEDIA_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // =================================================================================
    // [4-1] 액티비티 인터페이스 — 메서드 여러 개 가능. @ActivityMethod 는 생략 가능하다.
    //
    //   ★ @ActivityMethod(name = "...") 으로 타입명을 못 박는 이유:
    //     액티비티 타입 이름은 히스토리에 문자열로 저장된다.
    //       "activityType": { "name": "Charge" }
    //     자바 메서드명을 chargeWithFee 로 리팩터링하면, 히스토리에 "Charge" 가 적힌
    //     실행 중인 워크플로우를 리플레이할 때 SDK 가 타입을 못 찾는다.
    //       ApplicationFailure: Activity Type "Charge" is not registered with a worker.
    //     이름을 못 박아 두면 메서드명은 자유롭게 바꿀 수 있다. (Step 10 버저닝)
    // =================================================================================
    @ActivityInterface
    public interface PaymentActivity {

        @ActivityMethod(name = "Charge")
        String charge(String orderId, long amount);

        @ActivityMethod(name = "ChargeIdempotent")
        String chargeIdempotent(String orderId, long amount, String idempotencyKey);

        @ActivityMethod(name = "Refund")
        void refund(String paymentId);
    }

    @ActivityInterface
    public interface BatchActivity {
        @ActivityMethod(name = "ProcessBatch")
        String processBatch(String batchId, int totalCount);
    }

    @ActivityInterface
    public interface ValidationActivity {
        @ActivityMethod(name = "NormalizeAddress")
        String normalizeAddress(String address);
    }

    // =================================================================================
    // [4-1] 액티비티 구현체 — 평범한 POJO. 벽시계·랜덤·네트워크·스레드 전부 허용된다.
    // =================================================================================
    public static class PaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            // 벽시계 — 액티비티에서는 문제없다
            long start = System.currentTimeMillis();
            // 랜덤 — 문제없다
            String requestId = UUID.randomUUID().toString();

            log.info("charge orderId={} amount={} requestId={}", orderId, amount, requestId);
            sleepQuietly(300);   // 외부 PG 호출을 흉내

            log.info("PG 응답 {}ms", System.currentTimeMillis() - start);
            return "PAY-" + requestId.substring(0, 8);
        }

        @Override
        public String chargeIdempotent(String orderId, long amount, String idempotencyKey) {
            log.info("chargeIdempotent orderId={} amount={} key={}", orderId, amount, idempotencyKey);
            sleepQuietly(300);
            return "PAY-" + idempotencyKey.substring(0, 8);
        }

        @Override
        public void refund(String paymentId) {
            log.info("refund paymentId={}", paymentId);
        }
    }

    // ---------------------------------------------------------------------------------
    // [4-3] 일부러 느린 결제 액티비티. StartToClose 3초를 터뜨리는 데 쓴다.
    // ---------------------------------------------------------------------------------
    public static class SlowPaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(SlowPaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            log.info("느린 결제 시작 orderId={} (10초 걸린다)", orderId);
            sleepQuietly(10_000);          // StartToClose 3초 < 10초 → 타임아웃
            log.info("느린 결제 완료 orderId={}", orderId);
            return "PAY-SLOW";
        }

        @Override
        public String chargeIdempotent(String orderId, long amount, String key) {
            return charge(orderId, amount);
        }

        @Override
        public void refund(String paymentId) {}
    }

    // =================================================================================
    // [4-3] 타임아웃 4종. 각 워크플로우가 하나씩을 터뜨린다.
    // =================================================================================

    // (a) 타임아웃을 하나도 안 준다 → 워크플로우가 즉시 FAILED
    //     IllegalStateException: Either ScheduleToCloseTimeout or StartToCloseTimeout is required
    public static class NoTimeoutWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder().build());   // ✘

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // (b) StartToClose 3초 — 액티비티는 10초 걸린다 → TIMEOUT_TYPE_START_TO_CLOSE
    public static class StartToCloseWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(3))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // (c) ScheduleToStart 10초 — Worker 를 안 띄우면 큐에서 대기하다 터진다
    //     → TIMEOUT_TYPE_SCHEDULE_TO_START, startedEventId=0, RETRY_STATE_NON_RETRYABLE_FAILURE
    public static class ScheduleToStartWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setScheduleToStartTimeout(Duration.ofSeconds(10))
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // (d) ScheduleToClose 20초 — 재시도를 계속하다 전체 예산이 소진된다
    //     → TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, failure.cause 에 직전 실패가 중첩된다
    public static class ScheduleToCloseWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(3))
                        .setScheduleToCloseTimeout(Duration.ofSeconds(20))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // [4-4] Heartbeat — 10만 건 배치를 5만 건에서 죽였다가 이어서 한다.
    //
    //   KILL_AT 에 도달하면 System.exit(137) 로 스스로 죽는다 (kill -9 흉내).
    //   Worker 를 다시 띄우면 getHeartbeatDetails() 가 50000 을 돌려주어 거기서부터 재개한다.
    //
    //   ★ HeartbeatTimeout 을 설정하지 않으면 heartbeat() 호출은 아무 효과가 없다.
    //     details 도 저장되지 않는다. 이 조합을 자주 실수한다.
    // =================================================================================
    public static class BatchActivityImpl implements BatchActivity {

        private static final Logger log = LoggerFactory.getLogger(BatchActivityImpl.class);
        private static final int KILL_AT = 50_000;
        private static volatile boolean killEnabled = false;

        @Override
        public String processBatch(String batchId, int totalCount) {
            ActivityExecutionContext ctx = Activity.getExecutionContext();

            // ★ 반복문 밖에서 딱 한 번 호출한다. 이 값은 액티비티 시도가 시작될 때
            //   서버가 함께 내려 준 것이라 반복 호출할 이유가 없다.
            int start = ctx.getHeartbeatDetails(Integer.class).orElse(0);
            int attempt = ctx.getInfo().getAttempt();

            log.info("배치 시작 batchId={} start={} total={} attempt={}",
                    batchId, start, totalCount, attempt);

            for (int i = start; i < totalCount; i++) {
                processOne(batchId, i);

                if (i % 1000 == 0) {
                    try {
                        // 하트비트는 SDK 가 HeartbeatTimeout 의 80% 간격으로 묶어 보낸다.
                        // 1,000건마다 호출해도 서버 부하가 되지 않는다.
                        ctx.heartbeat(i);
                    } catch (ActivityCompletionException e) {
                        // [4-5] 취소 감지 — ActivityCanceledException 이 여기로 온다
                        log.info("취소 감지 — {}건 처리 후 정리하고 종료", i);
                        cleanupPartialWork(batchId, i);
                        throw e;   // 반드시 다시 던져야 취소가 서버에 반영된다
                    }

                    if (i % 25_000 == 0) {
                        log.info("배치 진행 {}/{}", i, totalCount);
                    }

                    if (killEnabled && i >= KILL_AT) {
                        log.error("=== {}건 지점에서 Worker 강제 종료 (kill -9 흉내) ===", i);
                        log.error("=== Worker 를 다시 띄우면 {} 부터 재개됩니다 ===", i);
                        Runtime.getRuntime().halt(137);
                    }
                }
            }
            log.info("배치 완료 {}/{}", totalCount, totalCount);
            return batchId + " DONE " + totalCount;
        }

        private void processOne(String batchId, int i) {
            // 실제 작업 흉내. 10만 건에 약 4분.
            try {
                Thread.sleep(0, 200_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void cleanupPartialWork(String batchId, int processed) {
            log.info("부분 작업 정리 batchId={} processed={}", batchId, processed);
        }

        static void enableKill() {
            killEnabled = true;
        }
    }

    public static class HeartbeatWorkflowImpl implements OrderWorkflow {

        private final BatchActivity batch = Workflow.newActivityStub(
                BatchActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofHours(2))
                        // 30초 안에 하트비트가 없으면 Worker 가 죽은 것으로 간주하고 재시도한다.
                        // 이게 없으면 StartToClose 2시간이 다 지나야 알아챈다.
                        .setHeartbeatTimeout(Duration.ofSeconds(30))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            return batch.processBatch("B-" + req.orderId(), 100_000);
        }
    }

    // =================================================================================
    // [4-6] 멱등성 — at-least-once 라는 계약
    //
    //   아래 두 구현을 --double-charge 로 나란히 돌려 보면 차이가 명확하다.
    //   NonIdempotent 는 응답 유실 후 재시도에서 39,000원을 또 깎아 78,000원이 된다.
    // =================================================================================
    public static class NonIdempotentPaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(NonIdempotentPaymentActivityImpl.class);
        // 가짜 원장. 실제로는 PG 서버의 상태다.
        static final AtomicLong LEDGER = new AtomicLong(0);
        private static final ConcurrentHashMap<String, Integer> ATTEMPTS = new ConcurrentHashMap<>();

        @Override
        public String charge(String orderId, long amount) {
            int n = ATTEMPTS.merge(orderId, 1, Integer::sum);

            // ★ 실제로 돈이 빠진다
            long total = LEDGER.addAndGet(amount);
            log.warn("💳 결제 실행 orderId={} amount={} (attempt={}) 누적={}", orderId, amount, n, total);

            if (n == 1) {
                // 첫 시도: 결제는 성공했지만 응답이 유실된다 (LB 재시작 / TCP RST / GC 정지)
                log.error("✘ 응답 유실 시뮬레이션 — 결제는 됐지만 워커가 결과를 못 받는다");
                sleepQuietly(15_000);   // StartToClose 를 넘겨 타임아웃 유발
            }
            return "PAY-" + orderId + "-" + n;
        }

        @Override
        public String chargeIdempotent(String orderId, long amount, String key) {
            return charge(orderId, amount);
        }

        @Override
        public void refund(String paymentId) {}
    }

    public static class IdempotentPaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(IdempotentPaymentActivityImpl.class);
        static final AtomicLong LEDGER = new AtomicLong(0);
        // PG 서버가 들고 있는 멱등키 → 결과 맵. 실제로는 PG 쪽 DB 다.
        private static final ConcurrentHashMap<String, String> PROCESSED = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Integer> ATTEMPTS = new ConcurrentHashMap<>();

        @Override
        public String chargeIdempotent(String orderId, long amount, String idempotencyKey) {
            int n = ATTEMPTS.merge(orderId, 1, Integer::sum);

            // ★ 이미 처리한 키면 저장된 결과를 그대로 돌려준다. 돈이 빠지지 않는다.
            String existing = PROCESSED.get(idempotencyKey);
            if (existing != null) {
                log.info("✔ 멱등키 중복 감지 key={} — 저장된 결과 반환 (결제 안 함) 누적={}",
                        idempotencyKey, LEDGER.get());
                return existing;
            }

            long total = LEDGER.addAndGet(amount);
            String paymentId = "PAY-" + idempotencyKey.substring(0, 8);
            PROCESSED.put(idempotencyKey, paymentId);
            log.warn("💳 결제 실행 orderId={} amount={} key={} (attempt={}) 누적={}",
                    orderId, amount, idempotencyKey, n, total);

            if (n == 1) {
                log.error("✘ 응답 유실 시뮬레이션");
                sleepQuietly(15_000);
            }
            return paymentId;
        }

        @Override
        public String charge(String orderId, long amount) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "멱등키 없는 charge 는 쓰지 마세요", "UnsafeChargeError");
        }

        @Override
        public void refund(String paymentId) {}
    }

    // 멱등키를 워크플로우에서 만들어 액티비티 인자로 넘긴다.
    // ★ Workflow.randomUUID() 여야 한다. UUID.randomUUID() 는 리플레이 때 값이 바뀐다.
    public static class IdempotentWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(IdempotentWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            String idemKey = Workflow.randomUUID().toString();
            log.info("[{}] 멱등키 발급 {}", req.orderId(), idemKey);
            String paymentId = payment.chargeIdempotent(req.orderId(), req.amount(), idemKey);
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);
            return req.orderId() + " COMPLETED";
        }
    }

    public static class NonIdempotentWorkflowImpl implements OrderWorkflow {

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // [4-7] Local Activity vs Activity — 히스토리 비용 비교
    //
    //   같은 액티비티를 10회 호출한다.
    //     Regular : History Length 36, History Size 8712, 1.84초
    //     Local   : History Length 16, History Size 3104, 0.21초
    // =================================================================================
    public static class ValidationActivityImpl implements ValidationActivity {

        private static final Logger log = LoggerFactory.getLogger(ValidationActivityImpl.class);

        @Override
        public String normalizeAddress(String address) {
            // 순수 문자열 처리. 밀리초 단위. 외부 시스템을 안 건드린다 → Local Activity 후보
            return address.trim().replaceAll("\\s+", " ").toUpperCase();
        }
    }

    public static class RegularActivityWorkflowImpl implements OrderWorkflow {

        private final ValidationActivity validation = Workflow.newActivityStub(
                ValidationActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            String addr = req.address();
            for (int i = 0; i < 10; i++) {
                addr = validation.normalizeAddress(addr);   // 매번 서버 왕복 + 이벤트 3개
            }
            return req.orderId() + " REGULAR " + addr;
        }
    }

    public static class LocalActivityWorkflowImpl implements OrderWorkflow {

        private final ValidationActivity validation = Workflow.newLocalActivityStub(
                ValidationActivity.class,
                LocalActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            String addr = req.address();
            for (int i = 0; i < 10; i++) {
                addr = validation.normalizeAddress(addr);   // 서버 왕복 없음, MarkerRecorded 1개
            }
            return req.orderId() + " LOCAL " + addr;
        }
    }

    // =================================================================================
    // [4-8] 액티비티는 **인스턴스**로 등록된다 → Worker 당 하나를 200 스레드가 공유한다.
    //       따라서 스레드 안전해야 한다.
    // =================================================================================
    public static class UnsafePaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(UnsafePaymentActivityImpl.class);

        // ✘ 인스턴스 필드에 요청별 상태! 200 스레드가 서로 짓밟는다.
        private String currentOrderId;
        private long total;

        @Override
        public String charge(String orderId, long amount) {
            this.currentOrderId = orderId;
            sleepQuietly(50);                    // 다른 스레드가 끼어들 틈
            this.total += amount;                // 경쟁 조건 (원자적이지 않음)

            if (!orderId.equals(this.currentOrderId)) {
                log.error("★★★ 오염 감지! 요청={} 인데 필드={} — 남의 주문을 결제한다",
                        orderId, this.currentOrderId);
            }
            return "PAY-" + this.currentOrderId;   // 남의 orderId 가 나갈 수 있다
        }

        @Override
        public String chargeIdempotent(String orderId, long amount, String key) {
            return charge(orderId, amount);
        }

        @Override
        public void refund(String paymentId) {}
    }

    // ✔ 무상태로. 공유 필드는 불변이거나 스레드 안전한 것만.
    public static class SafePaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(SafePaymentActivityImpl.class);

        // DateTimeFormatter 는 불변이고 스레드 안전하다. SimpleDateFormat 은 아니다.
        private final DateTimeFormatter fmt =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

        @Override
        public String charge(String orderId, long amount) {
            // 모든 상태는 지역 변수와 파라미터로만
            String requestId = UUID.randomUUID().toString();
            String at = fmt.format(Instant.now());
            log.info("charge orderId={} amount={} at={} requestId={}", orderId, amount, at, requestId);
            sleepQuietly(50);
            return "PAY-" + orderId + "-" + requestId.substring(0, 8);
        }

        @Override
        public String chargeIdempotent(String orderId, long amount, String key) {
            return "PAY-" + key.substring(0, 8);
        }

        @Override
        public void refund(String paymentId) {}
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =================================================================================
    // 실행 진입점
    // =================================================================================
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--default";

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        // [4-8] Worker 튜닝
        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(50)          // 기본 200
                .setMaxConcurrentWorkflowTaskExecutionSize(100)     // 기본 200
                .setMaxConcurrentLocalActivityExecutionSize(100)    // 기본 200
                .build();

        Worker worker = factory.newWorker(TASK_QUEUE, workerOptions);

        switch (mode) {
            case "--timeout" -> {
                // [4-3] StartToClose 3초 vs 10초짜리 액티비티
                worker.registerWorkflowImplementationTypes(StartToCloseWorkflowImpl.class);
                worker.registerActivitiesImplementations(new SlowPaymentActivityImpl());
                factory.start();
                start(client, "3002", "StartToClose 3초 — 10초짜리 액티비티가 터진다");
                System.out.println("확인: temporal workflow show -w order-3002 --output json \\");
                System.out.println("        | jq '.events[] | select(.eventType==\"EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT\")'");
            }
            case "--no-worker" -> {
                // [4-3] ScheduleToStart — Worker 를 띄우지 않는다
                System.out.println("Worker 를 띄우지 않습니다. 액티비티가 큐에서 대기하다 터집니다.");
                start(client, "3003", "ScheduleToStart 10초 — Worker 없음");
                System.out.println("확인: temporal task-queue describe --task-queue " + TASK_QUEUE
                        + " --task-queue-type activity");
                Thread.sleep(15_000);
                System.out.println("확인: temporal workflow show -w order-3003");
                System.exit(0);
            }
            case "--heartbeat" -> {
                // [4-4] 10만 건 배치 재개
                BatchActivityImpl.enableKill();
                worker.registerWorkflowImplementationTypes(HeartbeatWorkflowImpl.class);
                worker.registerActivitiesImplementations(new BatchActivityImpl());
                factory.start();
                start(client, "4001", "10만 건 배치 — 5만 건에서 스스로 죽는다");
                System.out.println();
                System.out.println("=== [4-4] 재현 절차 ===");
                System.out.println("1) 5만 건 지점에서 프로세스가 halt(137) 로 죽습니다.");
                System.out.println("2) 30초 뒤 HeartbeatTimeout 이 터집니다:");
                System.out.println("     temporal workflow show -w order-4001 --output json | \\");
                System.out.println("       jq '.events[] | select(.eventType==\"EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT\")'");
                System.out.println("     → timeoutType: TIMEOUT_TYPE_HEARTBEAT, lastHeartbeatDetails: 50000");
                System.out.println("3) 이 명령을 --args=\"--heartbeat-resume\" 으로 다시 실행하면");
                System.out.println("   start=50000 부터 재개하는 로그가 보입니다.");
            }
            case "--heartbeat-resume" -> {
                // 죽지 않는 Worker 로 재기동 → 5만 건부터 재개
                worker.registerWorkflowImplementationTypes(HeartbeatWorkflowImpl.class);
                worker.registerActivitiesImplementations(new BatchActivityImpl());
                factory.start();
                System.out.println("Worker 재기동. order-4001 이 5만 건부터 재개됩니다.");
            }
            case "--double-charge" -> {
                // [4-6] 멱등하지 않은 결제 → 78,000원
                worker.registerWorkflowImplementationTypes(NonIdempotentWorkflowImpl.class);
                worker.registerActivitiesImplementations(new NonIdempotentPaymentActivityImpl());
                factory.start();
                start(client, "5001", "멱등하지 않은 결제 — 39,000원이 두 번 빠진다");
                Thread.sleep(30_000);
                System.out.println();
                System.out.println("★ 원장 잔액: " + NonIdempotentPaymentActivityImpl.LEDGER.get() + "원");
                System.out.println("  기대값 39000, 실제값 78000 → 결제가 두 번 됐습니다.");
                System.out.println("  히스토리에는 아무 이상이 없습니다. temporal workflow show -w order-5001");
                System.exit(0);
            }
            case "--idempotent" -> {
                // [4-6] 멱등키로 막는다
                worker.registerWorkflowImplementationTypes(IdempotentWorkflowImpl.class);
                worker.registerActivitiesImplementations(new IdempotentPaymentActivityImpl());
                factory.start();
                start(client, "5002", "멱등키 결제 — 재시도해도 39,000원");
                Thread.sleep(30_000);
                System.out.println();
                System.out.println("★ 원장 잔액: " + IdempotentPaymentActivityImpl.LEDGER.get() + "원");
                System.exit(0);
            }
            case "--local" -> {
                // [4-7] Local Activity vs Activity
                worker.registerWorkflowImplementationTypes(
                        RegularActivityWorkflowImpl.class, LocalActivityWorkflowImpl.class);
                worker.registerActivitiesImplementations(new ValidationActivityImpl());
                factory.start();
                startTyped(client, RegularActivityWorkflowImpl.class, "6001");
                startTyped(client, LocalActivityWorkflowImpl.class, "6002");
                Thread.sleep(5_000);
                System.out.println();
                System.out.println("비교: temporal workflow describe -w order-6001   (Regular)");
                System.out.println("      temporal workflow describe -w order-6002   (Local)");
                System.out.println("      History Length 36 vs 16 / History Size 8712 vs 3104");
            }
            case "--race" -> {
                // [4-8] 스레드 안전하지 않은 액티비티 — 20건 동시 투입
                worker.registerWorkflowImplementationTypes(NonIdempotentWorkflowImpl.class);
                worker.registerActivitiesImplementations(new UnsafePaymentActivityImpl());
                factory.start();
                for (int i = 0; i < 20; i++) {
                    start(client, "7" + String.format("%03d", i), "동시 투입 " + i);
                }
                System.out.println("콘솔에 '★★★ 오염 감지!' 로그가 뜨는지 보세요.");
            }
            default -> {
                // [4-1] ~ [4-2] 기본 실습
                worker.registerWorkflowImplementationTypes(IdempotentWorkflowImpl.class);
                worker.registerActivitiesImplementations(new IdempotentPaymentActivityImpl());
                factory.start();
                start(client, "1001", "기본 실습");
                System.out.println("확인: temporal workflow show -w order-1001 --output json \\");
                System.out.println("        | jq '.events[4].activityTaskScheduledEventAttributes'");
            }
        }
    }

    private static void start(WorkflowClient client, String orderId, String desc) {
        OrderWorkflow stub = client.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("order-" + orderId)
                        .build());
        OrderRequest req = new OrderRequest(orderId, "C-77", "SKU-A", 2, 39000, " 서울시  강남구 ");
        WorkflowClient.start(stub::processOrder, req);
        System.out.println("started order-" + orderId + " — " + desc);
    }

    private static void startTyped(WorkflowClient client, Class<?> type, String orderId) {
        OrderWorkflow stub = client.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("order-" + orderId)
                        .build());
        OrderRequest req = new OrderRequest(orderId, "C-77", "SKU-A", 2, 39000, " 서울시  강남구 ");
        WorkflowClient.start(stub::processOrder, req);
        System.out.println("started order-" + orderId + " — " + type.getSimpleName());
    }
}
