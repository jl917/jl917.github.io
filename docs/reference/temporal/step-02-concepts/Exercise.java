package com.example.order;

/*
 * ============================================================================
 * Step 02 — 핵심 개념과 실행 모델 : Exercise.java  (문제지)
 * ============================================================================
 *
 * 6문제입니다. `// TODO: 여기에 작성` 자리를 채우세요.
 * 정답은 Solution.java 에 있습니다.
 *
 * 실행 방법
 *   [터미널 1] java com.example.order.Exercise$WorkerMain
 *              (로그를 파일로도 받으려면: ... 2>&1 | tee replay.log)
 *   [터미널 2] java com.example.order.Exercise$StarterMain <문제번호>
 *
 * kill 실험
 *   kill -9 $(pgrep -f Exercise)
 *
 * 로그 줄 세기 (문제 3)
 *   grep -c 'Q3WorkflowImpl' replay.log
 *
 * 뒷정리
 *   java com.example.order.Exercise$Cleanup
 * ============================================================================
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class Exercise {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    // ------------------------------------------------------------------
    // 문제 1 — 예측값을 여기에 적으세요.
    //   액티비티 3개 + 5초 타이머 2개짜리 워크플로우의 최종 히스토리 이벤트 수는?
    //   힌트: Step 01 에서 "액티비티 1개 = 6 이벤트" 를 배웠습니다.
    //         타이머 1개는 몇 이벤트일까요?
    // ------------------------------------------------------------------
    public static final int EXPECTED_EVENT_COUNT = 0;   // TODO: 여기에 작성

    public static final AtomicInteger INVOKE_CHARGE = new AtomicInteger();
    public static final AtomicInteger INVOKE_RESERVE = new AtomicInteger();
    public static final AtomicInteger INVOKE_SHIP = new AtomicInteger();

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    // ==================================================================
    // 액티비티
    // ==================================================================
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
    }

    public static class PaymentActivityImpl implements PaymentActivity {
        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);
        @Override public String charge(String orderId, long amount) {
            log.info("[{}] 결제 요청 amount={}  (실제 실행 {}회째)",
                    orderId, amount, INVOKE_CHARGE.incrementAndGet());
            return "pay-" + orderId;
        }
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);
        @Override public String reserve(String orderId, String sku, int qty) {
            log.info("[{}] 재고 예약 sku={}  (실제 실행 {}회째)",
                    orderId, sku, INVOKE_RESERVE.incrementAndGet());
            return "resv-" + orderId;
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = LoggerFactory.getLogger(ShippingActivityImpl.class);
        @Override public String requestShipment(String orderId, String address) {
            log.info("[{}] 배송 요청  (실제 실행 {}회째)",
                    orderId, INVOKE_SHIP.incrementAndGet());
            return "ship-" + orderId;
        }
    }

    // ==================================================================
    // 문제 1·2 — 액티비티 3개 + 타이머 2개
    //   문제 1: 이벤트 수 예측 후 실측 검증
    //   문제 2: 실행 중 kill -9 → 재기동 → 액티비티 로그가 다시 안 찍히는 것 확인
    // ==================================================================
    @WorkflowInterface
    public interface Q12Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q12WorkflowImpl implements Q12Workflow {
        private static final Logger log = Workflow.getLogger(Q12WorkflowImpl.class);

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
            log.info("[{}] 결제 시작", req.orderId());
            payment.charge(req.orderId(), req.amount());

            Workflow.sleep(Duration.ofSeconds(5));   // ★ 여기서 kill -9

            log.info("[{}] 재고 예약 시작", req.orderId());
            inventory.reserve(req.orderId(), req.sku(), req.qty());

            Workflow.sleep(Duration.ofSeconds(5));

            log.info("[{}] 배송 요청 시작", req.orderId());
            shipping.requestShipment(req.orderId(), req.address());

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // 문제 3 — 로거를 바꾸고 로그 줄 수 변화 세기
    //   지금은 LoggerFactory 를 쓰고 있어 리플레이마다 중복 출력됩니다.
    // ==================================================================
    @WorkflowInterface
    public interface Q3Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q3WorkflowImpl implements Q3Workflow {

        // TODO: 여기에 작성 — 이 로거를 리플레이 안전한 것으로 바꾸세요
        private static final Logger log = LoggerFactory.getLogger(Q3WorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 결제 시작 amount={}", req.orderId(), req.amount());
            String paymentId = payment.charge(req.orderId(), req.amount());
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);

            Workflow.sleep(Duration.ofSeconds(5));   // ★ 여기서 kill -9

            log.info("[{}] 재고 예약 시작", req.orderId());
            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            log.info("[{}] 재고 예약 완료 {}", req.orderId(), reservationId);

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 문제 4 — 지필 문제 (실행하지 않습니다)
     * ------------------------------------------------------------------
     * 아래 히스토리 발췌를 보고, 각 WorkflowTaskCompleted 가 어떤 Command 를
     * 반환했는지 우측에 적으세요.
     *
     * 규칙: WorkflowTaskCompleted 바로 다음에 오는 이벤트들이 그 Command 의 결과다.
     *
     *   ID  Type                          → 반환한 Command
     *   --  ----------------------------    -----------------------------
     *    1  WorkflowExecutionStarted        (해당 없음 — 서버가 기록)
     *    2  WorkflowTaskScheduled
     *    3  WorkflowTaskStarted
     *    4  WorkflowTaskCompleted           → TODO: 여기에 작성
     *    5  ActivityTaskScheduled
     *    6  ActivityTaskStarted
     *    7  ActivityTaskCompleted
     *    8  WorkflowTaskScheduled
     *    9  WorkflowTaskStarted
     *   10  WorkflowTaskCompleted           → TODO: 여기에 작성
     *   11  TimerStarted
     *   12  TimerFired
     *   13  WorkflowTaskScheduled
     *   14  WorkflowTaskStarted
     *   15  WorkflowTaskCompleted           → TODO: 여기에 작성
     *   16  ActivityTaskScheduled
     *   17  ActivityTaskStarted
     *   18  ActivityTaskCompleted
     *   19  WorkflowTaskScheduled
     *   20  WorkflowTaskStarted
     *   21  WorkflowTaskCompleted           → TODO: 여기에 작성
     *   22  WorkflowExecutionCompleted
     *
     * 추가 질문:
     *   (a) 이 워크플로우의 processOrder() 는 총 몇 번 실행됐습니까?
     *       TODO: 여기에 작성
     *   (b) 액티비티는 총 몇 번 실행됐습니까?
     *       TODO: 여기에 작성
     *   (c) 이벤트 11 의 TimerStarted 는 코드의 어느 줄에서 나왔습니까?
     *       TODO: 여기에 작성
     * ================================================================== */

    // ==================================================================
    // 문제 5 — Workflow.isReplaying() 으로 리플레이 횟수 세기
    //   Worker 를 재기동하지 않으면 sticky 캐시 때문에 리플레이가 거의 안 일어납니다.
    //   반드시 kill -9 를 섞어서 실행하세요.
    // ==================================================================
    @WorkflowInterface
    public interface Q5Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q5WorkflowImpl implements Q5Workflow {
        private static final Logger log = Workflow.getLogger(Q5WorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            // TODO: 여기에 작성
            //   processOrder 진입 횟수와 그때의 Workflow.isReplaying() 값을
            //   System.out 으로 출력하세요.
            //   (log 가 아니라 System.out 이어야 리플레이 중에도 보입니다)

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofSeconds(5));   // ★ 여기서 kill -9
            payment.charge(req.orderId() + "-2", req.amount());

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // 문제 6 — 위험한 코드가 왜 위험한지 히스토리로 증명하기
    //   아래 워크플로우에는 두 가지 문제가 있습니다.
    //     (a) Thread.sleep(3000)
    //     (b) static int counter++
    //   그대로 실행해 보고, 무슨 일이 벌어지는지 히스토리로 확인한 뒤 고치세요.
    // ==================================================================
    @WorkflowInterface
    public interface Q6Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q6WorkflowImpl implements Q6Workflow {
        private static final Logger log = Workflow.getLogger(Q6WorkflowImpl.class);

        // 문제의 카운터
        public static int counter = 0;

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            counter++;                             // (b) 문제의 줄
            System.out.println("counter = " + counter);

            payment.charge(req.orderId(), req.amount());

            try {
                Thread.sleep(3000);                // (a) 문제의 줄
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            payment.charge(req.orderId() + "-2", req.amount());

            // TODO: 여기에 작성
            //   (a) Thread.sleep(3000) 을 안전한 것으로 바꾸세요.
            //   (b) counter 를 어떻게 처리해야 할까요? 주석으로 답하세요.
            //       답:
            //   (c) Thread.sleep 이 구체적으로 무엇을 망가뜨립니까? 두 가지를 적으세요.
            //       답 1:
            //       답 2:

            return "order-" + req.orderId() + " counter=" + counter;
        }
    }

    // ==================================================================
    // Worker
    // ==================================================================
    public static class WorkerMain {
        public static void main(String[] args) {
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());
            WorkerFactory factory = WorkerFactory.newInstance(client);
            Worker worker = factory.newWorker(ORDER_TASK_QUEUE);

            worker.registerWorkflowImplementationTypes(
                    Q12WorkflowImpl.class, Q3WorkflowImpl.class,
                    Q5WorkflowImpl.class, Q6WorkflowImpl.class);
            worker.registerActivitiesImplementations(
                    new PaymentActivityImpl(),
                    new InventoryActivityImpl(),
                    new ShippingActivityImpl());

            factory.start();
            System.out.println("Exercise Worker started. queue=" + ORDER_TASK_QUEUE);
            System.out.println("kill 실험: kill -9 $(pgrep -f Exercise)");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println();
                System.out.println("액티비티 실제 실행 횟수");
                System.out.println("  charge          : " + INVOKE_CHARGE.get());
                System.out.println("  reserve         : " + INVOKE_RESERVE.get());
                System.out.println("  requestShipment : " + INVOKE_SHIP.get());
            }));
        }
    }

    // ==================================================================
    // Client
    // ==================================================================
    public static class StarterMain {

        public static void main(String[] args) {
            String no = (args.length > 0) ? args[0] : "1";
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());

            switch (no) {
                case "1" -> q1(client);
                case "2" -> q2(client);
                case "3" -> q3(client);
                case "4" -> System.out.println("문제 4 는 지필 문제입니다. 파일 주석을 보세요.");
                case "5" -> q5(client);
                case "6" -> q6(client);
                default -> System.out.println("문제 번호는 1~6 입니다.");
            }
            System.exit(0);
        }

        static void q1(WorkflowClient client) {
            Q12Workflow w = client.newWorkflowStub(Q12Workflow.class, opts("order-4001"));
            System.out.println("결과: " + w.processOrder(sample("4001")));
            System.out.println();
            System.out.println("예측: " + EXPECTED_EVENT_COUNT + " 이벤트");
            System.out.println("실측: temporal workflow describe -w order-4001");
            System.out.println("      → History Length 를 확인하세요.");
        }

        static void q2(WorkflowClient client) {
            System.out.println("★ Worker 콘솔에 '결제 요청' 이 찍히면 5초 안에:");
            System.out.println("    kill -9 $(pgrep -f Exercise)");
            System.out.println("  그 다음 Worker 를 재기동하세요.");
            System.out.println();
            System.out.println("확인할 것:");
            System.out.println("  (a) 재기동 후 PaymentActivityImpl 로그가 다시 찍히는가?  답: ");
            System.out.println("  (b) INVOKE_CHARGE 값이 늘었는가?                     답: ");
            System.out.println("  (c) ActivityTaskStarted 의 attempt 값은?             답: ");
            System.out.println();

            Q12Workflow w = client.newWorkflowStub(Q12Workflow.class, opts("order-4002"));
            System.out.println("결과: " + w.processOrder(sample("4002")));
        }

        static void q3(WorkflowClient client) {
            System.out.println("Worker 로그를 파일로 받으세요:");
            System.out.println("  java ... Exercise$WorkerMain 2>&1 | tee replay.log");
            System.out.println("실행 중 kill -9 를 한 번 섞고, 끝나면:");
            System.out.println("  grep -c 'Q3WorkflowImpl' replay.log");
            System.out.println();
            System.out.println("  로거 수정 전 줄 수:  (여기에 적으세요)");
            System.out.println("  로거 수정 후 줄 수:  (여기에 적으세요)");
            System.out.println();

            Q3Workflow w = client.newWorkflowStub(Q3Workflow.class, opts("order-4003"));
            System.out.println("결과: " + w.processOrder(sample("4003")));
        }

        static void q5(WorkflowClient client) {
            System.out.println("★ 실행 중 kill -9 를 반드시 섞으세요. 안 그러면 리플레이가 안 일어납니다.");
            Q5Workflow w = client.newWorkflowStub(Q5Workflow.class, opts("order-4005"));
            System.out.println("결과: " + w.processOrder(sample("4005")));
        }

        static void q6(WorkflowClient client) {
            System.out.println("이 실행은 문제가 있습니다. Worker 콘솔의 counter 값과");
            System.out.println("히스토리의 WorkflowTaskTimedOut 유무를 확인하세요.");
            Q6Workflow w = client.newWorkflowStub(Q6Workflow.class, opts("order-4006"));
            System.out.println("결과: " + w.processOrder(sample("4006")));
            System.out.println();
            System.out.println("확인: temporal workflow show -w order-4006");
            System.out.println("      → WorkflowTaskTimedOut 이 있습니까?");
        }

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
    }

    // ==================================================================
    // 뒷정리
    // ==================================================================
    public static class Cleanup {
        public static void main(String[] args) {
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());
            for (int i = 4001; i <= 4006; i++) {
                String id = "order-" + i;
                try {
                    client.newUntypedWorkflowStub(id).terminate("exercise cleanup");
                    System.out.println("terminated " + id);
                } catch (Exception e) {
                    System.out.println("skip " + id);
                }
            }
            System.exit(0);
        }
    }
}
