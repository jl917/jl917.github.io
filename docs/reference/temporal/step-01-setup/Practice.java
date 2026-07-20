package com.example.order;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 워크플로우 : Practice.java
 * ============================================================================
 *
 * 실행 방법 (터미널 2개 필요)
 *
 *   [터미널 1] Worker 기동
 *     ./gradlew runWorker
 *     (= java -cp build/libs/* com.example.order.Practice$WorkerMain)
 *
 *   [터미널 2] Client 실행
 *     ./gradlew runStarter                  # 시나리오 sync  (1-4 동기 실행)
 *     ./gradlew runStarter --args="async"   # 시나리오 async (1-4 비동기 실행)
 *     ./gradlew runStarter --args="dup"     # 시나리오 dup   (1-4 중복 실행 방지)
 *     ./gradlew runStarter --args="typo"    # 시나리오 typo  (1-9 큐 이름 오타 함정)
 *
 * 사전 조건
 *   docker compose up -d
 *   temporal operator namespace list        # default 가 Registered 인지 확인
 *
 * 버전
 *   Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 *
 * 뒷정리
 *   temporal workflow terminate -w order-1004 --reason "task queue typo"
 * ============================================================================
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class Practice {

    // ------------------------------------------------------------------
    // [1-2] Task Queue 이름은 반드시 상수로. 1-9 의 함정에 대한 유일한 방어책이다.
    //       Worker 와 Client 가 같은 상수를 참조하면 오타는 컴파일 에러가 된다.
    // ------------------------------------------------------------------
    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    // [1-9] 함정 재현용. 일부러 틀린 이름. QUEUE -> QEUEU
    public static final String TYPO_TASK_QUEUE = "ORDER_TASK_QEUEU";

    // ------------------------------------------------------------------
    // [1-7] 이 값을 false 로 두고 먼저 실행 → 히스토리 5 이벤트 (BEFORE)
    //       true 로 바꾸고 Worker 재시작 후 실행 → 히스토리 11 이벤트 (AFTER)
    //       ★ 반드시 false 를 먼저 돌려 보고 나서 true 로 바꿀 것.
    // ------------------------------------------------------------------
    public static final boolean WITH_ACTIVITY = false;

    // ==================================================================
    // [1-2] DTO — Java 21 record
    // ==================================================================
    public record OrderRequest(
            String orderId,
            String customerId,
            String sku,
            int qty,
            long amount,
            String address
    ) {}

    // ==================================================================
    // [1-2] Workflow 인터페이스
    //   @WorkflowInterface  : 이 인터페이스가 워크플로우 계약임을 표시
    //   @WorkflowMethod     : 진입점. 인터페이스당 정확히 하나
    // ==================================================================
    @WorkflowInterface
    public interface OrderWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    // ==================================================================
    // [1-7] Activity 인터페이스
    // ==================================================================
    @ActivityInterface
    public interface PaymentActivity {

        @ActivityMethod
        String charge(String orderId, long amount);

        @ActivityMethod
        void refund(String paymentId);
    }

    // ==================================================================
    // [1-7] Activity 구현
    //   - 액티비티는 리플레이되지 않으므로 일반 LoggerFactory 를 쓴다.
    //   - Worker 에는 "인스턴스"로 등록되므로 생성자 주입이 자유롭다.
    // ==================================================================
    public static class PaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            log.info("[{}] 결제 요청 amount={}", orderId, amount);
            // 실제로는 여기서 PG 사 HTTP 호출
            return "pay-" + orderId;
        }

        @Override
        public void refund(String paymentId) {
            log.info("환불 처리 paymentId={}", paymentId);
        }
    }

    // ==================================================================
    // [1-2][1-7] Workflow 구현
    //   - 반드시 기본 생성자가 있어야 한다(Worker 가 리플렉션으로 생성).
    //   - 로거는 Workflow.getLogger 를 쓴다. 이유는 Step 02 (2-8) 참고.
    //     LoggerFactory 를 쓰면 리플레이할 때마다 로그가 다시 찍힌다.
    // ==================================================================
    public static class OrderWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

        // [1-7] Activity 스텁. 이것은 프록시이며, 호출해도 실제 실행이 아니라
        //       ScheduleActivityTask Command 가 만들어진다.
        //       setStartToCloseTimeout 은 필수. 빠뜨리면 "실행 시점"에 예외가 난다.
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 워크플로우 시작 sku={} qty={}", req.orderId(), req.sku(), req.qty());

            if (WITH_ACTIVITY) {
                // [1-7] 액티비티 1개 → 히스토리에 6 이벤트 추가
                //   ActivityTaskScheduled / Started / Completed  (3개)
                //   + 결과를 워크플로우에 전달하기 위한 WorkflowTask 3종 (3개)
                String paymentId = payment.charge(req.orderId(), req.amount());
                log.info("[{}] 결제 완료 paymentId={}", req.orderId(), paymentId);
            }

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ==================================================================
    // [1-3] Worker 기동
    // ==================================================================
    public static class WorkerMain {

        public static void main(String[] args) {
            // ① 서버(127.0.0.1:7233)로의 gRPC 연결
            WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();

            // ② 그 연결 위의 클라이언트
            WorkflowClient client = WorkflowClient.newInstance(service);

            // ③ Worker 들이 공유하는 스레드풀/캐시를 관리하는 팩토리
            WorkerFactory factory = WorkerFactory.newInstance(client);

            // ④ 특정 Task Queue 를 long-poll 할 Worker
            Worker worker = factory.newWorker(ORDER_TASK_QUEUE);

            // ⑤ 워크플로우는 "클래스"를 등록한다 (매 실행마다 새 인스턴스가 생성됨)
            worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

            // ⑤-2 액티비티는 "인스턴스"를 등록한다 (공유됨. 상태를 두면 안 된다)
            if (WITH_ACTIVITY) {
                worker.registerActivitiesImplementations(new PaymentActivityImpl());
            }

            // ⑥ 폴러 스레드 기동. 블로킹하지 않으므로 main 이 끝나지 않게 유지해야 한다.
            factory.start();

            System.out.println("Worker started. Task Queue = " + ORDER_TASK_QUEUE
                    + " / WITH_ACTIVITY = " + WITH_ACTIVITY);
            System.out.println("확인: temporal task-queue describe --task-queue " + ORDER_TASK_QUEUE);

            // 폴러가 데몬 스레드가 아니므로 factory.start() 만으로 프로세스가 유지된다.
            // 명시적으로 대기하고 싶다면 아래 주석을 해제한다.
            // Runtime.getRuntime().addShutdownHook(new Thread(factory::shutdown));
        }
    }

    // ==================================================================
    // [1-4][1-9] Client — 시나리오별 실행
    // ==================================================================
    public static class StarterMain {

        public static void main(String[] args) {
            String scenario = (args.length > 0) ? args[0] : "sync";

            WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
            WorkflowClient client = WorkflowClient.newInstance(service);

            switch (scenario) {
                case "sync"  -> runSync(client);
                case "async" -> runAsync(client);
                case "dup"   -> runDuplicate(client);
                case "typo"  -> runTypo(client);
                default -> System.out.println("알 수 없는 시나리오: " + scenario
                        + " (sync | async | dup | typo)");
            }
            System.exit(0);
        }

        // ----------------------------------------------------------
        // [1-4] 동기 실행 — 워크플로우가 끝날 때까지 블로킹
        // ----------------------------------------------------------
        static void runSync(WorkflowClient client) {
            String workflowId = "order-1001";
            terminateIfRunning(client, workflowId);   // 반복 실행 안전장치

            OrderRequest req = sample("1001");

            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setTaskQueue(ORDER_TASK_QUEUE)
                    .setWorkflowId(workflowId)        // 비즈니스 키를 그대로 WorkflowId 로
                    .build();

            OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);

            long t0 = System.currentTimeMillis();
            String result = workflow.processOrder(req);   // ← 여기서 블로킹
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("결과: " + result + " (" + elapsed + "ms)");
            System.out.println("확인: temporal workflow show -w " + workflowId);
        }

        // ----------------------------------------------------------
        // [1-4] 비동기 실행 — 시작만 하고 즉시 반환
        //   워크플로우가 며칠씩 도는 것이 정상이므로 실무에서는 대개 이쪽을 쓴다.
        // ----------------------------------------------------------
        static void runAsync(WorkflowClient client) {
            String workflowId = "order-1002";
            terminateIfRunning(client, workflowId);

            OrderRequest req = sample("1002");

            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setTaskQueue(ORDER_TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build();

            OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);

            // ★ 반드시 메서드 참조(workflow::processOrder)여야 한다.
            //   람다로 감싸면 SDK 가 워크플로우 타입을 추출하지 못해 실행 시 실패한다.
            WorkflowExecution exec = WorkflowClient.start(workflow::processOrder, req);

            System.out.println("started workflowId=" + exec.getWorkflowId()
                    + " runId=" + exec.getRunId());

            // 나중에 결과가 필요해지면 다시 붙어서 기다린다.
            OrderWorkflow attached = client.newWorkflowStub(OrderWorkflow.class, workflowId);
            String result = WorkflowStub.fromTyped(attached).getResult(String.class);
            System.out.println("결과: " + result);
            System.out.println("확인: temporal workflow show -w " + workflowId);
        }

        // ----------------------------------------------------------
        // [1-4] 중복 실행 방지 — 같은 WorkflowId 로 두 번 시작
        //   두 번째 호출에서 WorkflowExecutionAlreadyStarted 가 난다.
        //   WorkflowId 를 비즈니스 키로 지정하면 멱등성이 공짜로 따라온다.
        // ----------------------------------------------------------
        static void runDuplicate(WorkflowClient client) {
            String workflowId = "order-1005";
            terminateIfRunning(client, workflowId);

            OrderRequest req = sample("1005");
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setTaskQueue(ORDER_TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build();

            OrderWorkflow w1 = client.newWorkflowStub(OrderWorkflow.class, options);
            WorkflowExecution first = WorkflowClient.start(w1::processOrder, req);
            System.out.println("1회차 시작 성공 runId=" + first.getRunId());

            try {
                OrderWorkflow w2 = client.newWorkflowStub(OrderWorkflow.class, options);
                WorkflowClient.start(w2::processOrder, req);
                System.out.println("2회차도 성공했다?! — 1회차가 이미 종료된 상태일 수 있습니다.");
            } catch (Exception e) {
                System.out.println("2회차 실패 (기대한 동작): "
                        + e.getClass().getSimpleName() + " — " + e.getMessage());
            }
        }

        // ----------------------------------------------------------
        // [1-9] 함정 — Task Queue 이름 오타
        //   컴파일도 되고 실행도 되고 예외도 없다. 그냥 영원히 매달린다.
        //   30초 뒤 스스로 포기하고 진단 명령을 출력하도록 만들어 뒀다.
        // ----------------------------------------------------------
        static void runTypo(WorkflowClient client) {
            String workflowId = "order-1004";
            terminateIfRunning(client, workflowId);

            OrderRequest req = sample("1004");

            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setTaskQueue(TYPO_TASK_QUEUE)     // ← 오타난 큐
                    .setWorkflowId(workflowId)
                    .build();

            OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);
            WorkflowClient.start(workflow::processOrder, req);

            System.out.println("시작했습니다. 30초 기다립니다... (아무 일도 안 일어날 겁니다)");
            System.out.println("  Worker 콘솔에는 아무것도 안 찍힙니다.");
            System.out.println("  다른 터미널에서 아래를 실행해 보세요:");
            System.out.println("    temporal workflow describe -w " + workflowId);
            System.out.println("      → History Length 2 / Pending Workflow Task: Scheduled");
            System.out.println("    temporal task-queue describe --task-queue " + TYPO_TASK_QUEUE);
            System.out.println("      → (no pollers)   ← 결정적 단서");
            System.out.println("    temporal task-queue describe --task-queue " + ORDER_TASK_QUEUE);
            System.out.println("      → 폴러는 멀쩡히 있음. 큐가 다를 뿐이다.");

            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            System.out.println();
            System.out.println("역시 아무 일도 안 일어났습니다. 뒷정리:");
            System.out.println("  temporal workflow terminate -w " + workflowId
                    + " --reason \"task queue typo\"");
        }

        // ----------------------------------------------------------
        // 유틸 — 같은 WorkflowId 의 기존 실행이 살아 있으면 종료시킨다.
        //   실습을 반복해서 돌릴 수 있게 하려는 것이며, 운영 코드에서 따라 하면 안 된다.
        // ----------------------------------------------------------
        static void terminateIfRunning(WorkflowClient client, String workflowId) {
            try {
                WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
                stub.terminate("practice rerun");
                System.out.println("(기존 실행 " + workflowId + " 을 정리했습니다)");
            } catch (Exception ignored) {
                // 실행이 없거나 이미 종료됨 — 정상
            }
        }

        static OrderRequest sample(String orderId) {
            return new OrderRequest(
                    orderId, "cust-77", "SKU-BLACK-TEE", 2, 39000L, "서울시 강남구 테헤란로 1");
        }
    }
}
