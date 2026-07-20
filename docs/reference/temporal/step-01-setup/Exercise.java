package com.example.order;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 워크플로우 : Exercise.java  (문제지)
 * ============================================================================
 *
 * 6문제입니다. `// TODO: 여기에 작성` 자리를 채우세요.
 * 정답은 Solution.java 에 있습니다. 먼저 직접 풀어 보세요.
 *
 * 실행 방법
 *   [터미널 1] java com.example.order.Exercise$WorkerMain
 *   [터미널 2] java com.example.order.Exercise$StarterMain <문제번호>
 *             예) java com.example.order.Exercise$StarterMain 2
 *
 * 뒷정리
 *   java com.example.order.Exercise$Cleanup
 *   (order-2001 ~ order-2006 을 전부 terminate 합니다)
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
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class Exercise {

    // ------------------------------------------------------------------
    // 문제 5 에서 쓰는 상수. 일부러 틀리게 만들어 뒀습니다.
    // ------------------------------------------------------------------
    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";
    public static final String WRONG_TASK_QUEUE = "ORDER_TASKQUEUE";   // ← 언더스코어 하나 빠짐

    // ------------------------------------------------------------------
    // 문제 2 — 예측값을 여기에 적으세요.
    // 액티비티 2개짜리 워크플로우의 최종 히스토리 이벤트 수는 몇 개일까요?
    // 실행 후 실제 값과 비교해서 출력해 줍니다.
    // ------------------------------------------------------------------
    public static final int EXPECTED_EVENT_COUNT = 0;   // TODO: 여기에 예측값을 작성

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    // ==================================================================
    // 문제 1 — @WorkflowMethod 가 빠져 있습니다.
    //   (a) 이대로 Worker 를 띄우면 어떤 예외가, 어느 시점에 나는지 확인하세요.
    //   (b) 고치세요.
    // ==================================================================
    @WorkflowInterface
    public interface Q1Workflow {

        // TODO: 여기에 작성 — 이 메서드에 필요한 애너테이션을 붙이세요
        String processOrder(OrderRequest req);
    }

    public static class Q1WorkflowImpl implements Q1Workflow {
        private static final Logger log = Workflow.getLogger(Q1WorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] Q1 실행", req.orderId());
            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // 문제 2 — InventoryActivity.reserve 를 추가하고 이벤트 수를 예측/검증
    //   PaymentActivity.charge 다음에 InventoryActivity.reserve 를 호출하세요.
    //   호출 전에 EXPECTED_EVENT_COUNT 에 예측값을 적어 두세요.
    // ==================================================================
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    public static class PaymentActivityImpl implements PaymentActivity {
        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);
        @Override public String charge(String orderId, long amount) {
            log.info("[{}] 결제 요청 amount={}", orderId, amount);
            return "pay-" + orderId;
        }
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);
        @Override public String reserve(String orderId, String sku, int qty) {
            log.info("[{}] 재고 예약 sku={} qty={}", orderId, sku, qty);
            return "resv-" + orderId;
        }
    }

    @WorkflowInterface
    public interface Q2Workflow {
        @io.temporal.workflow.WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class Q2WorkflowImpl implements Q2Workflow {
        private static final Logger log = Workflow.getLogger(Q2WorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);

        // TODO: 여기에 작성 — InventoryActivity 스텁을 만드세요

        @Override
        public String processOrder(OrderRequest req) {
            String paymentId = payment.charge(req.orderId(), req.amount());
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);

            // TODO: 여기에 작성 — inventory.reserve(...) 를 호출하고 결과를 로그로 남기세요

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // 문제 6 — setStartToCloseTimeout 이 빠져 있습니다.
    //   (a) 이대로 실행하면 언제(등록 시점? 실행 시점?) 무슨 예외가 나는지 확인하세요.
    //   (b) 고치세요.
    // ==================================================================
    @WorkflowInterface
    public interface Q6Workflow {
        @io.temporal.workflow.WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class Q6WorkflowImpl implements Q6Workflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        // TODO: 여기에 작성 — 필요한 타임아웃을 지정하세요
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // Worker
    // ==================================================================
    public static class WorkerMain {
        public static void main(String[] args) {
            WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
            WorkflowClient client = WorkflowClient.newInstance(service);
            WorkerFactory factory = WorkerFactory.newInstance(client);
            Worker worker = factory.newWorker(ORDER_TASK_QUEUE);

            worker.registerWorkflowImplementationTypes(
                    Q1WorkflowImpl.class, Q2WorkflowImpl.class, Q6WorkflowImpl.class);
            worker.registerActivitiesImplementations(
                    new PaymentActivityImpl(), new InventoryActivityImpl());

            factory.start();
            System.out.println("Exercise Worker started. queue=" + ORDER_TASK_QUEUE);
        }
    }

    // ==================================================================
    // Client
    // ==================================================================
    public static class StarterMain {

        public static void main(String[] args) {
            String no = (args.length > 0) ? args[0] : "1";
            WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
            WorkflowClient client = WorkflowClient.newInstance(service);

            switch (no) {
                case "1" -> q1(client);
                case "2" -> q2(client);
                case "3" -> q3(client);
                case "4" -> q4(client);
                case "5" -> q5(client);
                case "6" -> q6(client);
                default -> System.out.println("문제 번호는 1~6 입니다.");
            }
            System.exit(0);
        }

        // 문제 1 — @WorkflowMethod 누락
        static void q1(WorkflowClient client) {
            System.out.println("Worker 콘솔의 예외 메시지를 확인하세요.");
            System.out.println("힌트: 이 예외는 워크플로우를 '시작하기 전에' 납니다.");

            Q1Workflow w = client.newWorkflowStub(Q1Workflow.class, opts("order-2001"));
            System.out.println(w.processOrder(sample("2001")));
        }

        // 문제 2 — 이벤트 수 예측/검증
        static void q2(WorkflowClient client) {
            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, opts("order-2002"));
            System.out.println(w.processOrder(sample("2002")));

            long actual = client.newUntypedWorkflowStub("order-2002")
                    .getResult(String.class) != null ? countEvents(client, "order-2002") : -1;

            System.out.println("예측: " + EXPECTED_EVENT_COUNT + " / 실제: " + actual);
            System.out.println(EXPECTED_EVENT_COUNT == actual ? "정답!" : "다시 세어 보세요.");
            System.out.println("확인: temporal workflow show -w order-2002");
        }

        // 문제 3 — 동기 → 비동기로 바꾸기
        static void q3(WorkflowClient client) {
            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, opts("order-2003"));

            // TODO: 여기에 작성
            //   아래 동기 호출을 WorkflowClient.start 기반 비동기로 바꾸고,
            //   시작 직후 workflowId/runId 를 출력한 뒤,
            //   WorkflowStub.fromTyped(...).getResult(String.class) 로 결과를 받으세요.
            //   ★ 반드시 메서드 참조(w::processOrder)를 쓸 것. 람다는 실행 시 실패합니다.
            String result = w.processOrder(sample("2003"));
            System.out.println("결과: " + result);
        }

        // 문제 4 — WorkflowId 를 지정하지 않으면?
        static void q4(WorkflowClient client) {
            // TODO: 여기에 작성
            //   setWorkflowId(...) 를 "빼고" WorkflowOptions 를 만들어 실행하고,
            //   생성된 WorkflowId 가 어떤 형태인지 출력하세요.
            //   그리고 비즈니스 키를 지정했을 때의 이점 세 가지를 주석으로 적으세요.
            //
            //   이점 1:
            //   이점 2:
            //   이점 3:
            System.out.println("문제 4 를 구현하세요.");
        }

        // 문제 5 — 큐 이름 오타 진단 후 상수로 리팩터링
        static void q5(WorkflowClient client) {
            WorkflowOptions wrong = WorkflowOptions.newBuilder()
                    .setTaskQueue(WRONG_TASK_QUEUE)     // ← 틀린 큐
                    .setWorkflowId("order-2005")
                    .build();

            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, wrong);
            WorkflowClient.start(w::processOrder, sample("2005"));

            System.out.println("시작했습니다. 아무 일도 안 일어날 겁니다.");
            System.out.println("아래 두 명령의 출력 차이를 확인하세요:");
            System.out.println("  temporal task-queue describe --task-queue " + WRONG_TASK_QUEUE);
            System.out.println("  temporal task-queue describe --task-queue " + ORDER_TASK_QUEUE);

            // TODO: 여기에 작성
            //   위 wrong 옵션을 ORDER_TASK_QUEUE 상수를 쓰도록 고치고,
            //   "왜 이런 실수가 컴파일·런타임·로그 어디서도 안 잡히는지" 주석으로 설명하세요.
        }

        // 문제 6 — setStartToCloseTimeout 누락
        static void q6(WorkflowClient client) {
            System.out.println("이 실행은 실패합니다. 예외 메시지와 '언제' 났는지를 확인하세요.");
            Q6Workflow w = client.newWorkflowStub(Q6Workflow.class, opts("order-2006"));
            try {
                System.out.println(w.processOrder(sample("2006")));
            } catch (Exception e) {
                System.out.println("실패: " + e.getClass().getName());
                System.out.println("원인: " + e.getMessage());
            }
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

        static long countEvents(WorkflowClient client, String workflowId) {
            // 히스토리 길이는 CLI 로 확인하는 것이 정확합니다.
            //   temporal workflow describe -w <id>  → History Length
            // 여기서는 편의상 -1 을 돌려주고 CLI 확인을 유도합니다.
            return -1;
        }
    }

    // ==================================================================
    // 뒷정리 — order-2001 ~ order-2006 전부 terminate
    // ==================================================================
    public static class Cleanup {
        public static void main(String[] args) {
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());
            for (int i = 2001; i <= 2006; i++) {
                String id = "order-" + i;
                try {
                    WorkflowStub stub = client.newUntypedWorkflowStub(id);
                    stub.terminate("exercise cleanup");
                    System.out.println("terminated " + id);
                } catch (Exception e) {
                    System.out.println("skip " + id + " (실행 없음 또는 이미 종료)");
                }
            }
            System.exit(0);
        }
    }
}
