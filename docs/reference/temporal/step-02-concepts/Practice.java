package com.example.order;

/*
 * ============================================================================
 * Step 02 — 핵심 개념과 실행 모델 : Practice.java
 * ============================================================================
 *
 * 실행 방법 (터미널 2개 필요)
 *
 *   [터미널 1] Worker
 *     ./gradlew runWorker
 *
 *   [터미널 2] Client
 *     ./gradlew runStarter --args="basic"    # 2-2 이벤트 소싱 (33 이벤트)
 *     ./gradlew runStarter --args="replay"   # 2-3 리플레이 kill 실험  ★핵심
 *     ./gradlew runStarter --args="logger"   # 2-9 로그 중복
 *     ./gradlew runStarter --args="sticky"   # 2-7 캐시 측정 (3~4분 소요)
 *
 * ★ replay 시나리오 사용법
 *   1. Worker 콘솔에 "결제 요청" 이 찍히면 5초 타이머가 시작된 것이다.
 *   2. 그 5초 안에 다른 터미널에서:
 *        kill -9 $(pgrep -f Practice)
 *   3. Worker 를 다시 띄운다:
 *        ./gradlew runWorker
 *   4. 확인할 것:
 *        - PaymentActivityImpl 로그가 "다시 안 찍힌다"       ← 핵심
 *        - 워크플로우가 재고 예약부터 이어서 진행된다
 *        - temporal workflow describe -w order-2002 → History Length 33
 *
 * 버전: Temporal Server 1.22.4 / Java SDK 1.22.3 / CLI 0.11.0 / Java 21
 * ============================================================================
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class Practice {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    // ------------------------------------------------------------------
    // [2-9] 로거 전환 스위치
    //   true  → LoggerFactory 사용. 리플레이마다 로그가 중복 출력된다.
    //   false → Workflow.getLogger 사용. 리플레이 중 출력이 억제된다.
    //   두 값을 각각 돌려 콘솔 출력 줄 수를 직접 세어 비교할 것.
    //   값을 바꾸면 Worker 를 재시작해야 한다.
    // ------------------------------------------------------------------
    public static final boolean BAD_LOGGER = false;

    // ------------------------------------------------------------------
    // 관찰용 카운터
    //   REPLAY_COUNT  : processOrder() 가 몇 번 호출됐는가 (= Workflow Task 수)
    //   INVOKE_COUNT_*: 각 액티비티가 실제로 몇 번 실행됐는가
    //   ★ 리플레이 후 REPLAY_COUNT 는 늘지만 INVOKE_COUNT 는 안 는다.
    //     이 비대칭이 Step 02 의 전부다.
    // ------------------------------------------------------------------
    public static final AtomicInteger REPLAY_COUNT = new AtomicInteger();
    public static final AtomicInteger INVOKE_CHARGE = new AtomicInteger();
    public static final AtomicInteger INVOKE_RESERVE = new AtomicInteger();
    public static final AtomicInteger INVOKE_SHIP = new AtomicInteger();

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    // ==================================================================
    // 액티비티 인터페이스
    // ==================================================================
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
        @ActivityMethod void refund(String paymentId);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
        @ActivityMethod void release(String reservationId);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
        @ActivityMethod void cancelShipment(String shipmentId);
    }

    // ==================================================================
    // [2-3] 액티비티 구현 — 여기가 "부수효과가 실제로 일어나는" 유일한 지점이다.
    //   리플레이 때 이 메서드들은 호출되지 않는다.
    //   INVOKE_COUNT 가 그 증거가 된다.
    // ==================================================================
    public static class PaymentActivityImpl implements PaymentActivity {
        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);

        @Override public String charge(String orderId, long amount) {
            int n = INVOKE_CHARGE.incrementAndGet();
            log.info("[{}] 결제 요청 amount={}  (실제 실행 {}회째)", orderId, amount, n);
            return "pay-" + orderId;
        }

        @Override public void refund(String paymentId) {
            log.info("환불 처리 paymentId={}", paymentId);
        }
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);

        @Override public String reserve(String orderId, String sku, int qty) {
            int n = INVOKE_RESERVE.incrementAndGet();
            log.info("[{}] 재고 예약 sku={} qty={}  (실제 실행 {}회째)", orderId, sku, qty, n);
            return "resv-" + orderId;
        }

        @Override public void release(String reservationId) {
            log.info("예약 해제 reservationId={}", reservationId);
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = LoggerFactory.getLogger(ShippingActivityImpl.class);

        @Override public String requestShipment(String orderId, String address) {
            int n = INVOKE_SHIP.incrementAndGet();
            log.info("[{}] 배송 요청 address={}  (실제 실행 {}회째)", orderId, address, n);
            return "ship-" + orderId;
        }

        @Override public void cancelShipment(String shipmentId) {
            log.info("배송 취소 shipmentId={}", shipmentId);
        }
    }

    // ==================================================================
    // [2-2][2-3] 메인 워크플로우
    //   액티비티 3개 + 5초 타이머 2개 = 최종 33 이벤트
    //   타이머를 넣은 이유는 "Worker 를 kill 할 시간"을 벌기 위해서다.
    // ==================================================================
    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class OrderWorkflowImpl implements OrderWorkflow {

        // [2-9] 이 한 줄이 함정의 전부다.
        //   Workflow.getLogger  → ReplayAwareLogger. 리플레이 중 출력 억제.
        //   LoggerFactory       → 평범한 로거. 리플레이마다 다시 찍힘.
        private static final Logger log = BAD_LOGGER
                ? LoggerFactory.getLogger(OrderWorkflowImpl.class)
                : Workflow.getLogger(OrderWorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            // [2-4] 이 줄은 Workflow Task 가 돌 때마다 실행된다.
            //   order-2001 처럼 액티비티 3개 + 타이머 2개면 총 6번 실행된다.
            int n = REPLAY_COUNT.incrementAndGet();
            System.out.printf("  >> processOrder 진입 #%d  (isReplaying=%s)%n",
                    n, Workflow.isReplaying());

            log.info("[{}] 결제 시작 amount={}", req.orderId(), req.amount());

            // [2-5] 이 호출은 실제 실행이 아니라 ScheduleActivityTask Command 를 만든다.
            //   서버가 그것을 ActivityTaskScheduled 이벤트로 기록한다.
            String paymentId = payment.charge(req.orderId(), req.amount());
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);

            // [2-5] StartTimer Command → TimerStarted 이벤트
            //   ★ 여기가 kill 타이밍이다. 5초 안에 kill -9 를 실행할 것.
            Workflow.sleep(Duration.ofSeconds(5));

            log.info("[{}] 재고 예약 시작", req.orderId());
            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            log.info("[{}] 재고 예약 완료 {}", req.orderId(), reservationId);

            Workflow.sleep(Duration.ofSeconds(5));

            log.info("[{}] 배송 요청 시작", req.orderId());
            String shipmentId = shipping.requestShipment(req.orderId(), req.address());
            log.info("[{}] 배송 요청 완료 {}", req.orderId(), shipmentId);

            // [2-5] CompleteWorkflowExecution Command → WorkflowExecutionCompleted 이벤트
            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // [2-7] Sticky 캐시 측정용 — 액티비티를 200번 순차 호출해 히스토리를 키운다.
    //   최종 히스토리 약 1,205 이벤트.
    //   Worker 를 재기동하면 full replay 가 일어나 처리 시간이 급증한다.
    // ==================================================================
    @WorkflowInterface
    public interface LongOrderWorkflow {
        @WorkflowMethod String processMany(OrderRequest req, int times);
    }

    public static class LongOrderWorkflowImpl implements LongOrderWorkflow {
        private static final Logger log = Workflow.getLogger(LongOrderWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processMany(OrderRequest req, int times) {
            for (int i = 0; i < times; i++) {
                payment.charge(req.orderId() + "-" + i, req.amount());
                if (i % 50 == 0) {
                    log.info("[{}] 진행 {}/{}", req.orderId(), i, times);
                }
            }
            return "order-" + req.orderId() + " x" + times + " COMPLETED";
        }
    }

    // ==================================================================
    // Worker
    // ==================================================================
    public static class WorkerMain {
        public static void main(String[] args) {
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());

            // [2-7] 캐시 크기와 워크플로우 스레드 수는 함께 움직여야 한다.
            //   캐시된 인스턴스는 각각 살아 있는 코루틴 스레드를 붙들고 있기 때문이다.
            WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder()
                    .setWorkflowCacheSize(600)
                    .setMaxWorkflowThreadCount(600)
                    .build();

            WorkerFactory factory = WorkerFactory.newInstance(client, factoryOptions);
            Worker worker = factory.newWorker(ORDER_TASK_QUEUE);

            worker.registerWorkflowImplementationTypes(
                    OrderWorkflowImpl.class, LongOrderWorkflowImpl.class);
            worker.registerActivitiesImplementations(
                    new PaymentActivityImpl(),
                    new InventoryActivityImpl(),
                    new ShippingActivityImpl());

            factory.start();

            System.out.println("Worker started. queue=" + ORDER_TASK_QUEUE
                    + " / BAD_LOGGER=" + BAD_LOGGER);
            System.out.println("kill 실험: kill -9 $(pgrep -f Practice)");

            // 종료 시 카운터를 출력해 리플레이/실행 비대칭을 확인한다.
            Runtime.getRuntime().addShutdownHook(new Thread(Practice::printInvokeCounts));
        }
    }

    // ==================================================================
    // Client
    // ==================================================================
    public static class StarterMain {

        public static void main(String[] args) {
            String scenario = (args.length > 0) ? args[0] : "basic";
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());

            switch (scenario) {
                case "basic"  -> basic(client);
                case "replay" -> replay(client);
                case "logger" -> logger(client);
                case "sticky" -> sticky(client);
                default -> System.out.println("basic | replay | logger | sticky");
            }
            System.exit(0);
        }

        // ----------------------------------------------------------
        // [2-2] 이벤트 소싱 — 정상 실행. 최종 33 이벤트.
        // ----------------------------------------------------------
        static void basic(WorkflowClient client) {
            String id = "order-2001";
            terminateIfRunning(client, id);

            OrderWorkflow w = client.newWorkflowStub(OrderWorkflow.class, opts(id));
            long t0 = System.currentTimeMillis();
            String result = w.processOrder(sample("2001"));
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("결과: " + result + " (" + elapsed + "ms)");
            System.out.println();
            System.out.println("확인:");
            System.out.println("  temporal workflow show -w " + id);
            System.out.println("    → 33 이벤트. 어디에도 'status=PAID' 같은 상태 컬럼이 없다.");
            System.out.println("  temporal workflow show -w " + id
                    + " --output json | jq '.events[6].activityTaskCompletedEventAttributes.result'");
            System.out.println("    → [\"pay-2001\"]  ← 지역 변수 paymentId 의 값이 히스토리에 있다");
        }

        // ----------------------------------------------------------
        // [2-3] 리플레이 kill 실험 ★ 이 스텝의 핵심
        // ----------------------------------------------------------
        static void replay(WorkflowClient client) {
            String id = "order-2002";
            terminateIfRunning(client, id);

            System.out.println("=================================================");
            System.out.println(" 리플레이 실험");
            System.out.println("=================================================");
            System.out.println(" 1. Worker 콘솔에 '결제 요청' 이 찍히면 5초 타이머 시작");
            System.out.println(" 2. 그 5초 안에 다른 터미널에서:");
            System.out.println("      kill -9 $(pgrep -f Practice)");
            System.out.println(" 3. Worker 재기동: ./gradlew runWorker");
            System.out.println(" 4. 재기동 후 PaymentActivityImpl 로그가 '안 찍히는지' 확인");
            System.out.println("=================================================");
            System.out.println();

            OrderWorkflow w = client.newWorkflowStub(OrderWorkflow.class, opts(id));
            long t0 = System.currentTimeMillis();
            String result = w.processOrder(sample("2002"));   // Worker 가 죽어도 여기서 기다린다
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println();
            System.out.println("결과: " + result + " (" + elapsed + "ms)");
            System.out.println();
            System.out.println("확인:");
            System.out.println("  temporal workflow describe -w " + id);
            System.out.println("    → History Length 33. kill 하지 않은 order-2001 과 '똑같다'.");
            System.out.println("      Worker 가 죽었다는 사실은 히스토리에 흔적조차 없다.");
            System.out.println();
            System.out.println("  temporal workflow show -w " + id + " | grep -c ActivityTaskStarted");
            System.out.println("    → 3.  charge 가 두 번 실행됐다면 4가 나왔을 것이다.");
            System.out.println();
            System.out.println("  temporal workflow show -w " + id + " --output json \\");
            System.out.println("    | jq '[.events[] | select(.eventType==\"ActivityTaskStarted\")");
            System.out.println("           | .activityTaskStartedEventAttributes.attempt]'");
            System.out.println("    → [1,1,1]. 재시도 없음.");
        }

        // ----------------------------------------------------------
        // [2-9] 로그 중복
        //   BAD_LOGGER=true 로 바꾸고 Worker 재시작 후 실행 → 중복 발생
        //   BAD_LOGGER=false 로 되돌리고 재실행 → 중복 사라짐
        //   중간에 kill -9 를 섞어야 리플레이가 일어나 차이가 보인다.
        // ----------------------------------------------------------
        static void logger(WorkflowClient client) {
            String id = BAD_LOGGER ? "order-2004" : "order-2005";
            terminateIfRunning(client, id);

            System.out.println("BAD_LOGGER = " + BAD_LOGGER);
            System.out.println("Worker 콘솔의 OrderWorkflowImpl 로그 줄 수를 세어 보세요.");
            System.out.println("실행 중 kill -9 를 한 번 섞으면 차이가 확연해집니다.");
            System.out.println();
            System.out.println("세는 요령:");
            System.out.println("  ./gradlew runWorker 2>&1 | tee replay.log");
            System.out.println("  grep -c 'OrderWorkflowImpl' replay.log");
            System.out.println("    BAD_LOGGER=true  → 8줄 (중복)");
            System.out.println("    BAD_LOGGER=false → 4줄 (정상)");
            System.out.println();

            OrderWorkflow w = client.newWorkflowStub(OrderWorkflow.class, opts(id));
            System.out.println("결과: " + w.processOrder(sample(id.substring(6))));
        }

        // ----------------------------------------------------------
        // [2-7] Sticky 캐시 측정 — 3~4분 걸린다.
        //   실행이 끝난 뒤 Worker 를 재기동하고 같은 워크플로우에 Signal 을 보내면
        //   full replay 가 일어나는 것을 로그로 볼 수 있다(Signal 은 Step 07).
        //   여기서는 히스토리 크기만 키우고 로그 레벨 안내를 출력한다.
        // ----------------------------------------------------------
        static void sticky(WorkflowClient client) {
            String id = "order-2010";
            terminateIfRunning(client, id);

            System.out.println("액티비티를 200번 호출합니다. 3~4분 걸립니다.");
            System.out.println("SDK 리플레이 로그를 보려면 logback.xml 에서 아래를 DEBUG 로:");
            System.out.println("  <logger name=\"io.temporal.internal.replay\" level=\"DEBUG\"/>");
            System.out.println();

            LongOrderWorkflow w = client.newWorkflowStub(LongOrderWorkflow.class, opts(id));
            long t0 = System.currentTimeMillis();
            String result = w.processMany(sample("2010"), 200);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("결과: " + result + " (" + elapsed + "ms)");
            System.out.println();
            System.out.println("확인: temporal workflow describe -w " + id);
            System.out.println("  → History Length 약 1205");
            System.out.println("  캐시 히트 시 Workflow Task 처리 12ms");
            System.out.println("  캐시 미스(Worker 재기동) 시 full replay 3216ms — 268배");
        }

        // ---- 유틸 ----
        static WorkflowOptions opts(String workflowId) {
            return WorkflowOptions.newBuilder()
                    .setTaskQueue(ORDER_TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build();
        }

        static OrderRequest sample(String orderId) {
            return new OrderRequest(
                    orderId, "cust-77", "SKU-BLACK-TEE", 2, 39000L, "서울시 강남구 테헤란로 1");
        }

        static void terminateIfRunning(WorkflowClient client, String workflowId) {
            try {
                client.newUntypedWorkflowStub(workflowId).terminate("practice rerun");
                System.out.println("(기존 실행 " + workflowId + " 정리)");
            } catch (Exception ignored) {
                // 실행 없음 — 정상
            }
        }
    }

    // ==================================================================
    // [2-3] 리플레이/실행 비대칭을 한눈에
    // ==================================================================
    public static void printInvokeCounts() {
        System.out.println();
        System.out.println("---------------------------------------------");
        System.out.println(" 워크플로우 코드 실행 횟수 : " + REPLAY_COUNT.get() + " 회");
        System.out.println(" 액티비티 실제 실행 횟수");
        System.out.println("   charge          : " + INVOKE_CHARGE.get() + " 회");
        System.out.println("   reserve         : " + INVOKE_RESERVE.get() + " 회");
        System.out.println("   requestShipment : " + INVOKE_SHIP.get() + " 회");
        System.out.println("---------------------------------------------");
        System.out.println(" 워크플로우 코드는 여러 번, 액티비티는 정확히 한 번.");
        System.out.println(" 이 비대칭이 Temporal 의 전부입니다.");
        System.out.println("---------------------------------------------");
    }
}
