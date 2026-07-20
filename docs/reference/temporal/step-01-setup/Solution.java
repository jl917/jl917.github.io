package com.example.order;

/*
 * ============================================================================
 * Step 01 — 환경 구축과 첫 워크플로우 : Solution.java  (정답 + 해설)
 * ============================================================================
 *
 * Exercise.java 를 먼저 풀어 본 뒤에 여세요.
 *
 * 실행 방법
 *   [터미널 1] java com.example.order.Solution$WorkerMain
 *   [터미널 2] java com.example.order.Solution$StarterMain <문제번호>
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

public class Solution {

    /* ==================================================================
     * 정답 5 — Task Queue 이름은 상수 하나로 통일한다
     * ------------------------------------------------------------------
     * 왜 이 실수가 무서운가:
     *
     *  (1) 컴파일 타임에 안 잡힌다 — 그냥 String 이기 때문이다.
     *  (2) 런타임에도 안 잡힌다 — Temporal 의 Task Queue 는 Kafka 토픽처럼
     *      "미리 등록해 두고 고르는" 자원이 아니다. 문자열을 던지면 그 순간
     *      그 이름의 큐가 존재하는 것으로 취급된다. 그래서 "그런 큐 없음"
     *      에러가 아니라, 폴러가 하나도 없는 새 큐가 조용히 생긴다.
     *  (3) 로그에도 안 남는다 — Worker 는 자기 큐만 폴링하므로 다른 큐에
     *      Task 가 쌓이는 것을 알 방법이 없다. 콘솔은 완벽히 깨끗하다.
     *
     * 증상은 "Worker 미기동"과 완전히 동일하다:
     *   History Length 2 / Pending Workflow Task: Scheduled / 영원한 RUNNING
     *
     * 유일한 방어책은 Worker 와 Client 가 "같은 상수"를 참조하게 만드는 것이다.
     * 그러면 오타는 컴파일 에러가 되어 미리 잡힌다.
     * Spring 이라면 @Value("${temporal.task-queue}") 로 설정 한 곳에서 주입해도 된다.
     * ================================================================== */
    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    /* ==================================================================
     * 정답 2 — 액티비티 2개짜리 워크플로우의 최종 히스토리는 17 이벤트
     * ------------------------------------------------------------------
     * 산식:
     *   기본 골격 5개
     *     1 WorkflowExecutionStarted
     *     2 WorkflowTaskScheduled
     *     3 WorkflowTaskStarted
     *     4 WorkflowTaskCompleted
     *     N WorkflowExecutionCompleted   (맨 마지막)
     *
     *   액티비티 1개당 6개
     *     ActivityTaskScheduled / ActivityTaskStarted / ActivityTaskCompleted  (3)
     *     + 결과를 워크플로우 코드에 전달하기 위한
     *       WorkflowTaskScheduled / Started / Completed                        (3)
     *
     *   5 + (6 x 2) = 17
     *
     * 실제 히스토리 (temporal workflow show -w order-2002):
     *    1 WorkflowExecutionStarted
     *    2 WorkflowTaskScheduled
     *    3 WorkflowTaskStarted
     *    4 WorkflowTaskCompleted
     *    5 ActivityTaskScheduled      (charge)
     *    6 ActivityTaskStarted
     *    7 ActivityTaskCompleted
     *    8 WorkflowTaskScheduled
     *    9 WorkflowTaskStarted
     *   10 WorkflowTaskCompleted
     *   11 ActivityTaskScheduled      (reserve)
     *   12 ActivityTaskStarted
     *   13 ActivityTaskCompleted
     *   14 WorkflowTaskScheduled
     *   15 WorkflowTaskStarted
     *   16 WorkflowTaskCompleted
     *   17 WorkflowExecutionCompleted
     *
     * 여기서 얻어야 할 감각:
     *   "액티비티를 순차로 N개 호출하면 Workflow Task 가 N+1번 돈다."
     *   Workflow Task 가 돌 때마다 워크플로우 코드는 처음부터 다시 실행된다(리플레이).
     *   그래서 액티비티를 잘게 쪼갤수록 히스토리가 급격히 커지고, 리플레이 비용도 커진다.
     *   병렬 실행(Async.function)으로 묶으면 Workflow Task 수를 줄일 수 있다 — Step 04.
     * ================================================================== */
    public static final int EXPECTED_EVENT_COUNT = 17;

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    /* ==================================================================
     * 정답 1 — @WorkflowMethod 를 붙인다
     * ------------------------------------------------------------------
     * 누락 시 예외:
     *   java.lang.IllegalArgumentException:
     *     Missing @WorkflowMethod annotation on interface com.example.order.Exercise$Q1Workflow
     *
     * 중요한 것은 "언제" 나느냐다. 이 예외는
     *   worker.registerWorkflowImplementationTypes(Q1WorkflowImpl.class)
     * 즉 Worker "등록 시점"에 난다. 워크플로우를 시작하기도 전이며,
     * Worker 프로세스가 아예 기동에 실패한다.
     *
     * 이런 계열의 실수는 Temporal 이 일찍, 시끄럽게 잡아 준다. 다행이다.
     * 반대로 정답 6 의 실수는 늦게, 조용히 터진다 — 그쪽이 훨씬 위험하다.
     * ================================================================== */
    @WorkflowInterface
    public interface Q1Workflow {
        @WorkflowMethod                       // ← 정답
        String processOrder(OrderRequest req);
    }

    public static class Q1WorkflowImpl implements Q1Workflow {
        private static final Logger log = Workflow.getLogger(Q1WorkflowImpl.class);
        @Override public String processOrder(OrderRequest req) {
            log.info("[{}] Q1 실행", req.orderId());
            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    // ------------------------------------------------------------------
    // 액티비티 (정답 2 에서 사용)
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // 정답 2 — 액티비티 2개
    // ------------------------------------------------------------------
    @WorkflowInterface
    public interface Q2Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q2WorkflowImpl implements Q2Workflow {
        private static final Logger log = Workflow.getLogger(Q2WorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);

        // 정답: 같은 옵션으로 두 번째 스텁을 만든다.
        // 스텁은 인터페이스 단위이므로 액티비티 인터페이스마다 하나씩 필요하다.
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            String paymentId = payment.charge(req.orderId(), req.amount());
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);

            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            log.info("[{}] 재고 예약 완료 {}", req.orderId(), reservationId);

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 6 — setStartToCloseTimeout 은 필수다
     * ------------------------------------------------------------------
     * 누락 시 예외:
     *   java.lang.IllegalStateException:
     *     Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified
     *
     * 이 스텝에서 가장 중요한 교훈이 여기 있다. 이 예외가 나는 시점은
     *   - Worker 등록 시점  ← 아니다
     *   - 컴파일 시점       ← 아니다
     *   - "워크플로우가 실제로 그 액티비티 호출 줄에 도달했을 때"  ← 여기다
     *
     * 즉, 액티비티 호출을 실제로 태우는 테스트가 없으면
     * 컴파일도 통과하고 Worker 도 멀쩡히 뜨고 배포까지 성공한다.
     * 운영에서 그 코드 경로에 처음 진입하는 주문이 터진다.
     *
     * 게다가 워크플로우는 즉시 실패로 끝나지 않는다. Workflow Task 가 실패하면
     * Temporal 은 그것을 "일시적 오류"로 보고 무한 재시도한다. 히스토리에는
     * WorkflowTaskFailed 가 계속 쌓이고, 워크플로우 상태는 RUNNING 인 채로
     * 영원히 진행되지 않는다:
     *
     *   temporal workflow describe -w order-2006
     *     Status            RUNNING
     *     Pending Workflow Task:
     *       State           Started
     *       Attempt         47                      ← 계속 늘어난다
     *       Last Failure    IllegalStateException: Both StartToCloseTimeout ...
     *
     * "에러가 나면 실패로 끝난다"는 직관이 여기서 깨진다.
     * Temporal 에서 Workflow Task 실패는 "재시도해야 할 일시적 문제"로 취급된다.
     * 코드를 고쳐서 Worker 를 재배포하면 그 순간 이어서 진행된다 — 이것이 설계 의도다.
     *
     * 정리: 액티비티 옵션에는 반드시
     *   - startToCloseTimeout      (액티비티 1회 시도의 최대 실행 시간) 또는
     *   - scheduleToCloseTimeout   (재시도 포함 전체 시한)
     * 중 최소 하나를 지정한다. 실무에서는 startToCloseTimeout 을 기본으로 두고
     * 필요하면 scheduleToCloseTimeout 을 함께 지정한다 — Step 04 에서 4종 타임아웃 정리.
     * ================================================================== */
    @WorkflowInterface
    public interface Q6Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q6WorkflowImpl implements Q6Workflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))   // ← 정답
                        .build());

        @Override public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return "order-" + req.orderId() + " COMPLETED";
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
                    Q1WorkflowImpl.class, Q2WorkflowImpl.class, Q6WorkflowImpl.class);
            worker.registerActivitiesImplementations(
                    new PaymentActivityImpl(), new InventoryActivityImpl());

            factory.start();
            System.out.println("Solution Worker started. queue=" + ORDER_TASK_QUEUE);
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
                case "4" -> q4(client);
                case "5" -> q5(client);
                case "6" -> q6(client);
                default -> System.out.println("문제 번호는 1~6 입니다.");
            }
            System.exit(0);
        }

        static void q1(WorkflowClient client) {
            Q1Workflow w = client.newWorkflowStub(Q1Workflow.class, opts("order-3001"));
            System.out.println("결과: " + w.processOrder(sample("3001")));
            System.out.println("확인: temporal workflow show -w order-3001   → 5 이벤트");
        }

        static void q2(WorkflowClient client) {
            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, opts("order-3002"));
            System.out.println("결과: " + w.processOrder(sample("3002")));
            System.out.println("예상 히스토리 길이: " + EXPECTED_EVENT_COUNT);
            System.out.println("확인: temporal workflow describe -w order-3002");
            System.out.println("      → History Length 17 이어야 정답");
        }

        /* --------------------------------------------------------------
         * 정답 3 — WorkflowClient.start 는 반드시 "메서드 참조"로
         * --------------------------------------------------------------
         * WorkflowClient.start(w::processOrder, req)   ← 정답
         * WorkflowClient.start(() -> w.processOrder(req))  ← 실행 시 실패
         *
         * 이유:
         *   newWorkflowStub 이 돌려주는 것은 동적 프록시다. SDK 는 그 프록시 위의
         *   메서드 호출을 가로채서 (a) 워크플로우 타입 이름과 (b) 인자를 추출한다.
         *   메서드 참조를 넘기면 SDK 가 통제된 시점에 한 번 호출해 그 정보를 수집한다.
         *   람다로 감싸면 호출 시점과 문맥이 달라져 수집이 실패하고
         *     IllegalArgumentException: Only workflow methods can be used ...
         *   가 난다. 컴파일은 통과하므로 이것도 "조용히 틀리는" 부류다.
         *
         * 비동기가 기본이어야 하는 이유:
         *   워크플로우는 며칠~몇 달을 도는 것이 정상이다. HTTP 요청 스레드가
         *   그동안 블로킹되면 안 된다. API 서버는 start 로 시작만 하고 즉시
         *   202 Accepted 를 돌려주고, 결과는 Query(Step 07)나 콜백으로 받는다.
         * -------------------------------------------------------------- */
        static void q3(WorkflowClient client) {
            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, opts("order-3003"));

            WorkflowExecution exec = WorkflowClient.start(w::processOrder, sample("3003"));
            System.out.println("started workflowId=" + exec.getWorkflowId()
                    + " runId=" + exec.getRunId());

            // 여기서 다른 일을 해도 된다. 결과가 필요해지면 그때 붙는다.
            Q2Workflow attached = client.newWorkflowStub(Q2Workflow.class, exec.getWorkflowId());
            String result = WorkflowStub.fromTyped(attached).getResult(String.class);
            System.out.println("결과: " + result);
        }

        /* --------------------------------------------------------------
         * 정답 4 — WorkflowId 를 지정하지 않으면 UUID 가 붙는다
         * --------------------------------------------------------------
         * 출력 예:
         *   started workflowId=8b3f1e70-2c94-4d51-a7f6-19e0b3c8d245
         *
         * 비즈니스 키(order-1001)를 쓰는 이점 세 가지:
         *
         *   이점 1) 조회가 공짜다.
         *     temporal workflow describe -w order-1001 한 줄로 끝난다.
         *     UUID 를 쓰면 "주문 1001 ↔ 워크플로우 UUID" 매핑 테이블을 따로
         *     만들고 관리해야 한다. 그 테이블이 또 다른 정합성 문제를 만든다.
         *
         *   이점 2) 중복 실행 방지가 공짜다.
         *     Temporal 은 같은 WorkflowId 로 "동시에 두 개가 RUNNING" 인 상태를
         *     허용하지 않는다. 결제 API 가 네트워크 재시도로 두 번 들어와도
         *     두 번째는 WorkflowExecutionAlreadyStarted 로 거절된다.
         *     애플리케이션 레벨 멱등키를 따로 만들 필요가 없다.
         *     세부 정책은 WorkflowIdReusePolicy 로 조절한다:
         *       ALLOW_DUPLICATE                  (기본, 이전 실행 종료 후 재사용 허용)
         *       ALLOW_DUPLICATE_FAILED_ONLY      (이전이 실패했을 때만 재사용)
         *       REJECT_DUPLICATE                 (한 번 쓴 ID 는 영영 재사용 불가)
         *     → Step 12 에서 상세히 다룬다.
         *
         *   이점 3) 장애 대응 때 사람이 읽을 수 있다.
         *     새벽 3시에 "주문 1001 이 안 끝난다"는 문의를 받았을 때,
         *     UUID 를 역추적할 필요 없이 곧바로 히스토리를 열 수 있다.
         *     Web UI 목록에서도 order-1001 이 바로 보인다.
         *
         * 주의: WorkflowId 는 네임스페이스 안에서 유일해야 한다.
         *   "order-1001" 같은 키는 주문 도메인 전체에서 충돌하지 않아야 하므로,
         *   접두사(order-, refund-, settle-)로 도메인을 구분하는 관례가 흔하다.
         * -------------------------------------------------------------- */
        static void q4(WorkflowClient client) {
            WorkflowOptions noId = WorkflowOptions.newBuilder()
                    .setTaskQueue(ORDER_TASK_QUEUE)     // setWorkflowId 를 뺐다
                    .build();

            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, noId);
            WorkflowExecution exec = WorkflowClient.start(w::processOrder, sample("3004"));

            System.out.println("자동 생성된 workflowId = " + exec.getWorkflowId());
            System.out.println("  → UUID 형태입니다. 주문번호와 아무 관계가 없습니다.");
            System.out.println("  → 이 워크플로우를 나중에 찾으려면 이 UUID 를 어딘가 저장해야 합니다.");

            Q2Workflow attached = client.newWorkflowStub(Q2Workflow.class, exec.getWorkflowId());
            System.out.println("결과: "
                    + WorkflowStub.fromTyped(attached).getResult(String.class));
        }

        // 정답 5 — 상수를 쓰면 끝. 해설은 파일 상단 ORDER_TASK_QUEUE 주석 참고.
        static void q5(WorkflowClient client) {
            Q2Workflow w = client.newWorkflowStub(Q2Workflow.class, opts("order-3005"));
            System.out.println("결과: " + w.processOrder(sample("3005")));
            System.out.println("폴러 확인: temporal task-queue describe --task-queue "
                    + ORDER_TASK_QUEUE);
        }

        static void q6(WorkflowClient client) {
            Q6Workflow w = client.newWorkflowStub(Q6Workflow.class, opts("order-3006"));
            System.out.println("결과: " + w.processOrder(sample("3006")));
            System.out.println("타임아웃을 지정했으므로 정상 완료됩니다.");
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
    }

    // ==================================================================
    // 뒷정리
    // ==================================================================
    public static class Cleanup {
        public static void main(String[] args) {
            WorkflowClient client = WorkflowClient.newInstance(
                    WorkflowServiceStubs.newLocalServiceStubs());
            for (int i = 3001; i <= 3006; i++) {
                String id = "order-" + i;
                try {
                    client.newUntypedWorkflowStub(id).terminate("solution cleanup");
                    System.out.println("terminated " + id);
                } catch (Exception e) {
                    System.out.println("skip " + id);
                }
            }
            System.exit(0);
        }
    }
}
