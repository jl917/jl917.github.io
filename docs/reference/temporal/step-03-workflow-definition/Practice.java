package com.example.order;

// =====================================================================================
// Step 03 — 워크플로우 정의와 결정성 : Practice
//
// 실행 방법
//   ./gradlew run -PmainClass=com.example.order.Practice
//   ./gradlew run -PmainClass=com.example.order.Practice --args="--broken"   ← 3-5 재현
//
// 사전 조건
//   docker compose up -d          (Temporal Server 1.22.4, gRPC 127.0.0.1:7233)
//   temporal operator namespace describe default
//
// 이 파일은 본문 3-1 ~ 3-7 의 모든 예제를 담고 있습니다.
// [3-3] NonDeterministicWorkflowImpl 은 반면교사입니다. 등록되어 있지 않으니 읽기만 하세요.
// =====================================================================================

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class Practice {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // =================================================================================
    // [3-1] DTO — Java 21 record. Jackson 이 컴포넌트 이름을 JSON 키로 쓴다.
    //       필드명을 바꾸면 실행 중인 워크플로우의 히스토리 JSON 과 키가 안 맞아
    //       없는 키가 null / 0 으로 역직렬화된다. 에러 없이 값이 사라진다.
    // =================================================================================
    public record OrderRequest(
            String orderId,
            String customerId,
            String sku,
            int qty,
            long amount,
            String address
    ) {}

    // =================================================================================
    // [3-1] 액티비티 인터페이스 — 워크플로우가 호출할 대상.
    //       액티비티 구현체 안에서는 무엇을 해도 좋다(랜덤/시간/IO/스레드 전부 허용).
    // =================================================================================
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod
        String charge(String orderId, long amount);

        @ActivityMethod
        String chargeIdempotent(String orderId, long amount, String idempotencyKey);

        @ActivityMethod
        void refund(String paymentId);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod
        String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod
        String requestShipment(String orderId, String address);
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod
        void notifyCustomer(String orderId, String message);
    }

    // ---------------------------------------------------------------------------------
    // 액티비티 구현체 — 일반 POJO. 제약이 하나도 없다.
    // ---------------------------------------------------------------------------------
    public static class PaymentActivityImpl implements PaymentActivity {
        private static final Logger log = org.slf4j.LoggerFactory.getLogger(PaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            log.info("charge orderId={} amount={}", orderId, amount);
            sleepQuietly(300);                                  // 액티비티에서는 Thread.sleep 허용
            return "PAY-" + (10000 + new Random().nextInt(90000));   // 랜덤도 허용
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

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = org.slf4j.LoggerFactory.getLogger(InventoryActivityImpl.class);

        @Override
        public String reserve(String orderId, String sku, int qty) {
            log.info("reserve orderId={} sku={} qty={}", orderId, sku, qty);
            sleepQuietly(300);
            return "RSV-" + (40000 + new Random().nextInt(9000));
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = org.slf4j.LoggerFactory.getLogger(ShippingActivityImpl.class);

        @Override
        public String requestShipment(String orderId, String address) {
            log.info("requestShipment orderId={} address={}", orderId, address);
            sleepQuietly(300);
            return "SHP-" + (70000 + new Random().nextInt(9000));
        }
    }

    public static class NotificationActivityImpl implements NotificationActivity {
        private static final Logger log = org.slf4j.LoggerFactory.getLogger(NotificationActivityImpl.class);

        @Override
        public void notifyCustomer(String orderId, String message) {
            log.info("notifyCustomer orderId={} message={}", orderId, message);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =================================================================================
    // [3-1] 워크플로우 인터페이스 — @WorkflowMethod 는 정확히 하나.
    // =================================================================================
    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    // ---------------------------------------------------------------------------------
    // [3-1] @WorkflowMethod 가 둘이면 Worker 등록에서 터진다.
    //       주석을 풀고 registerWorkflowImplementationTypes 에 넣어 보면
    //         java.lang.IllegalArgumentException: Duplicated @WorkflowMethod: ...
    //       가 즉시 발생한다.
    // ---------------------------------------------------------------------------------
    // @WorkflowInterface
    // public interface BadWorkflow {
    //     @WorkflowMethod String a(String s);
    //     @WorkflowMethod String b(String s);   // 두 번째 → 등록 실패
    // }

    // ---------------------------------------------------------------------------------
    // [3-1] 정상 구현체 — public no-arg 생성자 필수.
    //       SDK 가 리플렉션으로 매 워크플로우 실행마다 새 인스턴스를 만든다.
    // ---------------------------------------------------------------------------------
    public static class OrderWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        public OrderWorkflowImpl() {}

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 주문 처리 시작 amount={}", req.orderId(), req.amount());
            String paymentId = payment.charge(req.orderId(), req.amount());
            log.info("[{}] 결제 완료 paymentId={}", req.orderId(), paymentId);
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // [3-3] 반면교사 — 결정성 위반 9종을 한 클래스에 몰아넣었다.
    //       ★ 절대 등록하지 말고 읽기만 할 것. 각 줄이 어떤 리플레이 시나리오에서 깨지는지
    //          주석으로 붙였다. [3-4] DeterministicWorkflowImpl 과 줄 단위로 대응된다.
    // =================================================================================
    public static class NonDeterministicWorkflowImpl implements OrderWorkflow {

        // ✘ (7) static 가변 상태 — Worker 프로세스에 하나뿐. 다른 Worker 에서 리플레이하면
        //        비어 있거나 다른 워크플로우가 오염시킨 값이 들어 있다.
        private static final Map<String, Integer> RETRY_COUNT = new ConcurrentHashMap<>();

        // ✘ (10) 일반 SLF4J 로거 — 리플레이할 때마다 같은 로그가 중복 출력된다.
        private static final Logger log =
                org.slf4j.LoggerFactory.getLogger(NonDeterministicWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        @Override
        public String processOrder(OrderRequest req) {

            // ✘ (1) 벽시계 — 첫 실행 09:12 는 true, 리플레이 09:40 은 false.
            //        분기가 뒤집혀 ScheduleActivityTask ↔ StartTimer 가 어긋난다.
            long now = System.currentTimeMillis();
            boolean businessHours = (now / 3_600_000) % 24 < 18;

            // ✘ (2) Instant.now() 도 같은 문제. 액티비티 인자로 흘러가면 히스토리와 입력이 달라진다.
            Instant startedAt = Instant.now();

            // ✘ (3) Math.random() — 첫 실행 0.31 → "CJ", 리플레이 0.77 → "LOTTE".
            String carrier = Math.random() < 0.5 ? "CJ" : "LOTTE";

            // ✘ (4) UUID.randomUUID() — 리플레이마다 새 값. 멱등키로 쓰면 결제가 두 번 될 수 있다.
            String idemKey = UUID.randomUUID().toString();

            // ✘ (5) 직접 스레드 — SDK 의 결정적 스케줄러 밖. 스케줄링 순서가 실행마다 다르다.
            //        게다가 워크플로우 컨텍스트가 없어 IllegalStateException 이 삼켜질 수 있다.
            CompletableFuture<String> f =
                    CompletableFuture.supplyAsync(() -> payment.charge(req.orderId(), req.amount()));

            // ✘ (6) 외부 IO — 리플레이 때마다 부수효과가 재실행된다.
            //        (여기서는 예시로만 표기. 실제 HTTP 호출은 액티비티에서 해야 한다.)
            //        String rate = new java.net.URL("https://fx.example.com/krw").getContent().toString();

            // ✘ (7) static 상태 갱신
            RETRY_COUNT.merge(req.orderId(), 1, Integer::sum);

            // ✘ (8) HashMap 순회 — 해시 순서는 JDK 구현이 바뀌면 달라진다.
            //        Command 순서가 뒤바뀌어 히스토리와 어긋난다.
            Map<String, Integer> items = new HashMap<>();
            items.put("SKU-A", 2);
            items.put("SKU-B", 1);
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                inventory.reserve(req.orderId(), e.getKey(), e.getValue());
            }

            // ✘ (9) 환경변수 — Worker 마다 다를 수 있고, 재배포로 값이 바뀌면 분기가 달라진다.
            String region = System.getenv("SHIPPING_REGION");

            // ✘ (11) Thread.sleep — Worker 스레드를 실제로 블로킹한다.
            //         Workflow Task 타임아웃(기본 10초)을 유발한다.
            sleepQuietly(2000);

            if (businessHours) {
                shipping.requestShipment(req.orderId(), req.address() + "/" + carrier + "/" + region);
            }

            log.info("[{}] startedAt={} idemKey={}", req.orderId(), startedAt, idemKey);
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // [3-4] 대응하는 결정적 구현. 위 클래스와 줄 단위로 비교하며 읽을 것.
    // =================================================================================
    public static class DeterministicWorkflowImpl implements OrderWorkflow {

        // ✔ (10) Workflow.getLogger — 리플레이 중 출력을 억제한다.
        private static final Logger log = Workflow.getLogger(DeterministicWorkflowImpl.class);

        // ✔ (7) static 대신 인스턴스 필드. 워크플로우 인스턴스는 실행마다 새로 만들어지고,
        //        리플레이하면 같은 코드 경로로 같은 값이 다시 계산된다.
        private int retryCount = 0;

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        @Override
        public String processOrder(OrderRequest req) {

            // ✔ (1) 현재 Workflow Task 가 시작된 시각. 히스토리에서 읽으므로 리플레이해도 같다.
            long now = Workflow.currentTimeMillis();
            boolean businessHours = (now / 3_600_000) % 24 < 18;

            // ✔ (2) Instant 가 필요하면 위 값에서 만든다.
            Instant startedAt = Instant.ofEpochMilli(now);

            // ✔ (3) Run ID 를 시드로 하는 Random. 리플레이해도 같은 수열이 나온다.
            Random rnd = Workflow.newRandom();
            String carrier = rnd.nextInt(100) < 50 ? "CJ" : "LOTTE";

            // ✔ (4) Run ID 시드의 결정적 UUID. 멱등키는 반드시 이쪽.
            String idemKey = Workflow.randomUUID().toString();

            // ✔ (5) Async.function — SDK 의 결정적 스케줄러가 관리한다.
            Promise<String> payFuture =
                    Async.function(payment::chargeIdempotent, req.orderId(), req.amount(), idemKey);

            // ✔ (6) 외부 값이 필요하면 sideEffect 로 히스토리에 못 박거나, 액티비티로 뺀다.
            String requestId = Workflow.sideEffect(String.class, () -> UUID.randomUUID().toString());

            // ✔ (7) 인스턴스 필드
            retryCount++;

            // ✔ (8) 순회 순서가 보장되는 LinkedHashMap (또는 TreeMap / 정렬된 List)
            Map<String, Integer> items = new LinkedHashMap<>();
            items.put("SKU-A", 2);
            items.put("SKU-B", 1);
            List<String> reservations = new ArrayList<>();
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                reservations.add(inventory.reserve(req.orderId(), e.getKey(), e.getValue()));
            }

            // ✔ (9) 설정값은 워크플로우 인자나 액티비티 반환값으로 받는다.
            //        여기서는 요청에 담긴 주소를 그대로 쓴다.
            String region = req.address();

            // ✔ (11) Workflow.sleep — TimerStarted / TimerFired 이벤트로 히스토리에 남는다.
            Workflow.sleep(Duration.ofSeconds(2));

            String paymentId = payFuture.get();

            if (businessHours) {
                shipping.requestShipment(req.orderId(), region + "/" + carrier);
            }

            log.info("[{}] startedAt={} idemKey={} requestId={} paymentId={} rsv={} retry={}",
                    req.orderId(), startedAt, idemKey, requestId, paymentId, reservations, retryCount);
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // [3-4] Workflow.currentTimeMillis() 는 같은 Workflow Task 안에서 전진하지 않는다.
    //       이 워크플로우를 실행하면 "경과 = 0ms" 가 찍힌다.
    // =================================================================================
    public static class ClockWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(ClockWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            long t1 = Workflow.currentTimeMillis();

            // CPU 로 무거운 계산 (약 1초)
            long acc = 0;
            for (long i = 0; i < 400_000_000L; i++) {
                acc += i % 7;
            }

            long t2 = Workflow.currentTimeMillis();
            log.info("경과 = {}ms (acc={})", t2 - t1, acc);   // → 0ms

            // 액티비티를 호출해 Workflow Task 를 한 번 끊으면 그때 시각이 전진한다.
            Workflow.sleep(Duration.ofSeconds(1));
            long t3 = Workflow.currentTimeMillis();
            log.info("타이머 후 경과 = {}ms", t3 - t1);        // → 1000ms 이상

            return req.orderId() + " CLOCK";
        }
    }

    // =================================================================================
    // [3-4] mutableSideEffect — 같은 id 로 반복 호출해도 값이 바뀔 때만 Marker 를 남긴다.
    // =================================================================================
    public static class ConfigCache {
        // 실제로는 외부 설정 서버를 읽는다고 가정. 워크플로우에서 직접 호출하면 위반이다.
        static int currentRateLimit() {
            return 100;
        }
    }

    public static class MutableSideEffectWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(MutableSideEffectWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            for (int i = 0; i < 5; i++) {
                int limit = Workflow.mutableSideEffect(
                        "rateLimit",
                        Integer.class,
                        (a, b) -> a.equals(b),                // 같으면 새 Marker 를 안 남긴다
                        ConfigCache::currentRateLimit);
                log.info("[{}] round={} limit={}", req.orderId(), i, limit);
                Workflow.sleep(Duration.ofSeconds(1));
            }
            // 5번 호출했지만 값이 계속 100 이므로 MarkerRecorded 는 1개만 생긴다.
            return req.orderId() + " MSE";
        }
    }

    // =================================================================================
    // [3-5] 위반 재현의 주인공. 벽시계로 분기해 서로 다른 Command 를 만든다.
    //
    //   1단계: UTC 18시 이후에 시작 → else 분기 → TimerStarted 가 히스토리에 못 박힌다
    //   2단계: 30분 타이머 도는 동안 Worker 재시작 (다음 날 오전이라고 가정)
    //   3단계: 리플레이 시 businessHours 가 true 로 뒤집혀 ScheduleActivityTask 를 만든다
    //          → NonDeterministicException
    //   4단계: temporal workflow describe -w order-2002 로 Attempt 가 증가하는 것을 관찰
    //   5단계: System.currentTimeMillis() → Workflow.currentTimeMillis() 로 고치고 재배포
    // =================================================================================
    public static class BrokenTimeWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(BrokenTimeWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

        @Override
        public String processOrder(OrderRequest req) {
            // ✘ 여기 한 줄이 전부다. 로컬 테스트는 100% 통과한다.
            long now = System.currentTimeMillis();
            boolean businessHours = (now / 3_600_000) % 24 < 18;

            if (businessHours) {
                log.info("[{}] 영업시간 — 즉시 결제", req.orderId());
                payment.charge(req.orderId(), req.amount());        // → ScheduleActivityTask
            } else {
                log.info("[{}] 영업시간 외 — 30분 대기", req.orderId());
                Workflow.sleep(Duration.ofMinutes(30));             // → StartTimer
                payment.charge(req.orderId(), req.amount());
            }
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // [3-7] 순차 vs 병렬. 두 워크플로우를 각각 실행한 뒤
    //       temporal workflow show 로 History Length 를 비교할 것.
    //       순차 22개 → 병렬 14개 정도가 나온다.
    // =================================================================================
    public static class SequentialWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(SequentialWorkflowImpl.class);

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 순차 시작", req.orderId());
            String paymentId = payment.charge(req.orderId(), req.amount());
            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            String shipmentId = shipping.requestShipment(req.orderId(), req.address());
            log.info("[{}] 결제={} 예약={} 배송={}", req.orderId(), paymentId, reservationId, shipmentId);
            return req.orderId() + " SEQ";
        }
    }

    public static class ParallelWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(ParallelWorkflowImpl.class);

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 병렬 시작", req.orderId());

            // 결제와 재고 예약은 서로 독립이므로 동시에 시작한다.
            // 두 Command 가 같은 Workflow Task 에 담겨 나가므로 왕복이 한 번 줄어든다.
            Promise<String> payFuture =
                    Async.function(payment::charge, req.orderId(), req.amount());
            Promise<String> resFuture =
                    Async.function(inventory::reserve, req.orderId(), req.sku(), req.qty());

            // 여기가 양보 지점(yield point). 이 순간 다른 워크플로우 스레드가 돌 수 있다.
            Promise.allOf(payFuture, resFuture).get();

            String paymentId = payFuture.get();
            String reservationId = resFuture.get();

            // 배송은 앞의 두 결과가 있어야 하므로 순차로 남긴다.
            String shipmentId = shipping.requestShipment(req.orderId(), req.address());

            log.info("[{}] 결제={} 예약={} 배송={}", req.orderId(), paymentId, reservationId, shipmentId);
            return req.orderId() + " PAR";
        }
    }

    // =================================================================================
    // 실행 진입점
    // =================================================================================
    public static void main(String[] args) {
        boolean broken = args.length > 0 && "--broken".equals(args[0]);

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        // ★ 한 Task Queue 에 같은 워크플로우 타입을 두 번 등록할 수 없다.
        //   실습할 구현체 하나만 남기고 나머지는 주석 처리한 뒤 Worker 를 재기동할 것.
        if (broken) {
            worker.registerWorkflowImplementationTypes(BrokenTimeWorkflowImpl.class);   // [3-5]
        } else {
            worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);        // [3-1]
            // worker.registerWorkflowImplementationTypes(DeterministicWorkflowImpl.class);      // [3-4]
            // worker.registerWorkflowImplementationTypes(ClockWorkflowImpl.class);              // [3-4]
            // worker.registerWorkflowImplementationTypes(MutableSideEffectWorkflowImpl.class);  // [3-4]
            // worker.registerWorkflowImplementationTypes(SequentialWorkflowImpl.class);         // [3-7]
            // worker.registerWorkflowImplementationTypes(ParallelWorkflowImpl.class);           // [3-7]
        }

        // 액티비티는 인스턴스를 등록한다 → 스레드 안전해야 한다 (Step 04 의 4-8)
        worker.registerActivitiesImplementations(
                new PaymentActivityImpl(),
                new InventoryActivityImpl(),
                new ShippingActivityImpl(),
                new NotificationActivityImpl());

        factory.start();
        System.out.println("Worker started. taskQueue=" + TASK_QUEUE + " broken=" + broken);

        // 워크플로우 하나를 시작해 본다.
        String orderId = broken ? "2002" : "1001";
        OrderRequest req = new OrderRequest(orderId, "C-77", "SKU-A", 2, 39000, "서울시 강남구");

        OrderWorkflow stub = client.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("order-" + orderId)
                        .build());

        // 비동기로 시작해 Worker 를 계속 살려 둔다.
        WorkflowClient.start(stub::processOrder, req);
        System.out.println("started workflowId=order-" + orderId);

        if (broken) {
            System.out.println();
            System.out.println("=== [3-5] 재현 절차 ===");
            System.out.println("1) 위 워크플로우가 TimerStarted 를 남겼는지 확인:");
            System.out.println("     temporal workflow show -w order-2002");
            System.out.println("2) 이 프로세스를 Ctrl+C 로 종료한다 (Worker 재배포 시뮬레이션).");
            System.out.println("3) 시스템 시각을 다음 날 오전으로 옮기거나, 조건식을 뒤집어 재기동한다.");
            System.out.println("4) Worker 콘솔에 NonDeterministicException 이 뜨는 것을 확인한다.");
            System.out.println("5) temporal workflow describe -w order-2002 로 Attempt 증가를 관찰한다.");
            System.out.println("6) System.currentTimeMillis() → Workflow.currentTimeMillis() 로 고치고 재기동.");
        }
    }
}
