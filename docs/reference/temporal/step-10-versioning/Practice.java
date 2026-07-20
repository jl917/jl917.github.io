package com.example.order;

/*
 * Step 10 — 버저닝과 무중단 배포 / Practice.java
 *
 * ⚠️ 이 파일은 순서를 반드시 지켜야 합니다.
 *
 *   1) ./gradlew run -PmainClass=com.example.order.Practice --args="--phase=1"
 *      → v1 Worker 기동 + order-2001 시작. TimerStarted 에서 멈춘 히스토리가 만들어집니다.
 *      → temporal workflow show -w order-2001  로 이벤트 11까지 확인하세요.
 *      → Ctrl+C 로 Worker 를 내립니다.
 *
 *   2) ./gradlew run -PmainClass=com.example.order.Practice --args="--phase=2"
 *      → v2 Worker(액티비티 한 줄 추가) 기동.
 *      → order-2001 의 Workflow Task 가 v2 Worker 에게 배정되며
 *        NonDeterministicException 이 콘솔에 쏟아집니다.
 *      → temporal workflow describe -w order-2001  의 Pending Workflow Task Attempt 를 보세요.
 *
 *   3) ./gradlew run -PmainClass=com.example.order.Practice --args="--phase=3"
 *      → getVersion 으로 감싼 Worker 기동. order-2001 이 다시 진행됩니다.
 *      → 새로 시작된 order-2002 에는 MarkerRecorded 가 남습니다.
 *
 * 환경: Java 21 / temporal-sdk 1.22.3 / Temporal Server 1.22.4 / CLI 0.11.0
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;

public class Practice {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // getVersion 의 changeId. 한번 정하면 절대 바꾸지 않는다.
    public static final String CHANGE_ADD_FRAUD_CHECK = "addFraudCheck";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {
    }

    static final ActivityOptions OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build();

    // ------------------------------------------------------------------
    // 액티비티
    // ------------------------------------------------------------------
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface FraudActivity {
        @ActivityMethod void check(String customerId, long amount);
    }

    @ActivityInterface
    public interface CreditActivity {
        @ActivityMethod void verify(String customerId);
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod void notifyCustomer(String orderId, String message);
    }

    // ------------------------------------------------------------------
    // 워크플로우 인터페이스 — 타입명 버저닝(10-4 ②) 예시를 위해 둘로 나눔
    // ------------------------------------------------------------------
    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    @WorkflowInterface
    public interface OrderWorkflowV2 {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // ==================================================================
    // [10-1] v1 — 최초 배포된 코드
    //
    // 이 코드로 order-2001 이 시작되면 히스토리는 이렇게 만들어진다:
    //   1 WorkflowExecutionStarted
    //   2 WorkflowTaskScheduled
    //   3 WorkflowTaskStarted
    //   4 WorkflowTaskCompleted
    //   5 ActivityTaskScheduled   [charge]   ← 코드가 만드는 첫 Command 가 여기 온다
    //   ...
    //  11 TimerStarted            [30일]
    // ==================================================================
    public static class V1Impl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(V1Impl.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] v1 실행", req.orderId());

            String paymentId = payment.charge(req.orderId(), req.amount());   // 이벤트 5

            Workflow.sleep(Duration.ofDays(30));                              // 이벤트 11

            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED (paymentId=" + paymentId + ")";
        }
    }

    // ==================================================================
    // [10-1] v2 — V1Impl 과 "딱 한 줄" 차이. 이 한 줄이 사고를 낸다.
    //
    // 이 코드로 order-2001 의 히스토리를 리플레이하면:
    //   히스토리 이벤트 5 = ActivityTaskScheduled(activityType=Charge)
    //   코드가 만드는 첫 Command = ScheduleActivityTask(Check)
    //   → 타입은 같지만 activityType 이 다르고,
    //     그 뒤로 Command 열이 통째로 한 칸씩 밀려 결국 타이머와 충돌한다.
    //
    // io.temporal.worker.NonDeterministicException: Failure handling event 5 of type
    // 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay. Event 5 of type
    // EVENT_TYPE_ACTIVITY_TASK_SCHEDULED does not match command type COMMAND_TYPE_START_TIMER
    // ==================================================================
    public static class V2Impl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(V2Impl.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] v2 실행", req.orderId());

            fraud.check(req.customerId(), req.amount());   // ← 추가된 한 줄. 이게 전부다.

            String paymentId = payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED (paymentId=" + paymentId + ")";
        }
    }

    // ==================================================================
    // [10-3] 정답 — Workflow.getVersion 으로 감싼 구현
    //
    // 동작:
    //   order-2001 (v1 배포 전 시작, 마커 없음) → version = -1 → fraud check 안 함
    //   order-2002 (이 배포 후 시작)            → version =  1 → fraud check 함
    //                                              + MarkerRecorded(Version) 히스토리에 기록
    // ==================================================================
    public static class VersionedImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(VersionedImpl.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final CreditActivity credit =
                Workflow.newActivityStub(CreditActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {

            // ⚠️ getVersion 은 메서드 최상위에서 "무조건" 호출한다.
            //    조건문/루프 안에 넣으면 그 자체가 비결정적이 된다 (함정 2).
            int version = Workflow.getVersion(
                    CHANGE_ADD_FRAUD_CHECK, Workflow.DEFAULT_VERSION, 2);

            log.info("[{}] version={}", req.orderId(), version);

            // 반환값으로만 분기한다.
            if (version == Workflow.DEFAULT_VERSION) {
                // v1 이전 실행 — 아무것도 하지 않는다.
                // 빈 블록을 명시적으로 두는 편이, 버전이 3~4개로 늘었을 때 읽기 좋다.
            } else if (version == 1) {
                fraud.check(req.customerId(), req.amount());
            } else {
                // version == 2 : 2차 변경으로 신용 조회가 추가됨
                fraud.check(req.customerId(), req.amount());
                credit.verify(req.customerId());
            }

            String paymentId = payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED (paymentId=" + paymentId + ")";
        }
    }

    // ==================================================================
    // [10-3 함정 2] 잘못된 예 — getVersion 이 조건문 안에 있다
    //
    // 저가 주문(amount <= 100000)에서는 마커가 안 생기고,
    // 고가 주문에서만 마커가 생긴다.
    // → 99%의 실행은 정상이고 1%만 깨진다. 메트릭에서 노이즈로 묻힌다.
    // → 나중에 임계값을 100000 → 50000 으로 바꾸면
    //   그 사이 구간(5만~10만원)의 실행들이 전부 깨진다.
    //
    // 절대 이렇게 쓰지 마세요. 재현용으로만 남겨 둡니다.
    // ==================================================================
    public static class BadVersionedImpl implements OrderWorkflow {

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            if (req.amount() > 100000) {
                // ⛔ 절대 금지 — getVersion 이 MarkerRecorded Command 를 발행하므로
                //    이 조건에 따라 마커 유무가 달라진다.
                int v = Workflow.getVersion(
                        CHANGE_ADD_FRAUD_CHECK, Workflow.DEFAULT_VERSION, 1);
                if (v != Workflow.DEFAULT_VERSION) {
                    fraud.check(req.customerId(), req.amount());
                }
            }
            String paymentId = payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED (paymentId=" + paymentId + ")";
        }
    }

    // ==================================================================
    // [10-4 ②] 타입명 버저닝 — 새 타입으로 신규 실행만 받는다
    //
    // V1Impl 은 절대 건드리지 않고 그대로 등록해 둔다.
    // 옛 실행은 OrderWorkflow 타입으로 계속 처리되고,
    // 신규 실행만 OrderWorkflowV2 로 시작한다.
    //
    // 확인:  temporal workflow list
    //        → Type 컬럼에 OrderWorkflow 와 OrderWorkflowV2 가 공존한다
    //        temporal workflow count --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running"'
    //        → 0 이 되면 V1Impl 을 삭제해도 된다
    // ==================================================================
    public static class OrderWorkflowV2Impl implements OrderWorkflowV2 {

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final CreditActivity credit =
                Workflow.newActivityStub(CreditActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // 분기가 하나도 없다. 이게 타입명 버저닝의 장점이다.
            fraud.check(req.customerId(), req.amount());
            credit.verify(req.customerId());
            String paymentId = payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED (paymentId=" + paymentId + ")";
        }
    }

    // ==================================================================
    // [10-5] Worker Versioning (Build ID) — 실험적 기능
    //
    // 셀프호스팅에서는 dynamicconfig/development.yaml 에 아래를 넣어야 동작합니다:
    //
    //   frontend.workerVersioningDataAPIs:
    //     - value: true
    //   frontend.workerVersioningWorkflowAPIs:
    //     - value: true
    //   worker.buildIdScavengerEnabled:
    //     - value: true
    //
    // 그리고 Worker 를 띄우기 "전에" 서버에 Build ID 를 등록합니다:
    //
    //   temporal task-queue update-build-ids add-new-default \
    //     --build-id v1.2.0 --task-queue ORDER_TASK_QUEUE
    //
    //   temporal task-queue get-build-ids --task-queue ORDER_TASK_QUEUE
    //     BuildIds          DefaultForSet  IsDefaultSet
    //     [v1.0.0]          v1.0.0         false
    //     [v1.1.0]          v1.1.0         false
    //     [v1.2.0]          v1.2.0         true
    //
    // 호환 변경(버그 수정 등)이라면 새 세트가 아니라 기존 세트에 붙입니다:
    //
    //   temporal task-queue update-build-ids add-new-compatible \
    //     --build-id v1.0.1 --existing-compatible-build-id v1.0.0 \
    //     --task-queue ORDER_TASK_QUEUE
    // ==================================================================
    static WorkerOptions buildWorkerOptions(String buildId, boolean versioningEnabled) {
        if (!versioningEnabled) {
            return WorkerOptions.newBuilder().build();
        }
        return WorkerOptions.newBuilder()
                .setBuildId(buildId)
                .setUseBuildIdForVersioning(true)
                .build();
    }

    // ==================================================================
    // 액티비티 구현
    // ==================================================================
    public static class PaymentActivityImpl implements PaymentActivity {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(PaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            log.info("[charge] orderId={} amount={}", orderId, amount);
            return "pay_" + Integer.toHexString(orderId.hashCode() & 0xffff);
        }
    }

    public static class FraudActivityImpl implements FraudActivity {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(FraudActivityImpl.class);

        @Override
        public void check(String customerId, long amount) {
            log.info("[fraudCheck] customerId={} amount={} → PASS", customerId, amount);
        }
    }

    public static class CreditActivityImpl implements CreditActivity {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(CreditActivityImpl.class);

        @Override
        public void verify(String customerId) {
            log.info("[creditVerify] customerId={} → OK", customerId);
        }
    }

    public static class NotificationActivityImpl implements NotificationActivity {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(NotificationActivityImpl.class);

        @Override
        public void notifyCustomer(String orderId, String message) {
            log.info("[notifyCustomer] orderId={} message={}", orderId, message);
        }
    }

    // ==================================================================
    // [10-6] 배포 체크리스트 — 복붙해서 쓰는 용도
    // ==================================================================
    static void printDeploymentChecklist() {
        System.out.println("""
                ─────────────────────────────────────────────────────────────────
                 배포 체크리스트 (Step 10-6)
                ─────────────────────────────────────────────────────────────────
                 1. 변경 분류: Command 를 내는 줄의 목록이 바뀌었는가?
                 2. 위험하면 getVersion 으로 감쌌는가? (메서드 최상위, 무조건 호출)
                 3. 리플레이 테스트가 CI 에 있는가?  ← 이게 없으면 나머지는 무의미
                      temporal workflow show -w order-2001 --output json \\
                        > src/test/resources/histories/order-2001.json
                 4. 성공/실패/시그널/재시도 경로 히스토리를 각각 확보했는가?
                 5. 영향 범위 카운트:
                      temporal workflow count \\
                        --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running"'
                 6. 카나리 Worker 1대 먼저 배포
                 7. 메트릭 감시 (5분간 0 이어야 함):
                      sum(rate(temporal_workflow_task_execution_failed\\
                        {failure_reason="NonDeterminismError"}[1m])) by (workflow_type)
                 8. Pending Workflow Task 의 Attempt 확인:
                      temporal workflow list --query 'ExecutionStatus="Running"' --fields long
                 9. 롤백 기준: 카나리 5분 내 NonDeterminismError 1건이라도 → 즉시 롤백
                10. 전체 배포 후 5번 재실행. Running 개수가 정체되면 신호.
                11. getVersion 분기 제거 티켓 예약 (최장 수명 + Retention 뒤 날짜)
                ─────────────────────────────────────────────────────────────────
                """);
    }

    // ==================================================================
    // main
    // ==================================================================
    public static void main(String[] args) throws Exception {
        int phase = 1;
        for (String a : args) {
            if (a.startsWith("--phase=")) {
                phase = Integer.parseInt(a.substring("--phase=".length()));
            }
        }

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        // Build ID 버저닝은 기본 비활성. 동적 설정을 켠 뒤 true 로 바꾸세요.
        Worker worker = factory.newWorker(
                TASK_QUEUE, buildWorkerOptions("v1." + phase + ".0", false));

        switch (phase) {
            case 1 -> {
                System.out.println("=== phase 1: v1 Worker 기동 ===");
                worker.registerWorkflowImplementationTypes(V1Impl.class);
            }
            case 2 -> {
                System.out.println("=== phase 2: v2 Worker 기동 — 여기서 예외가 터집니다 ===");
                worker.registerWorkflowImplementationTypes(V2Impl.class);
            }
            case 3 -> {
                System.out.println("=== phase 3: getVersion 버전 Worker 기동 — 복구됩니다 ===");
                worker.registerWorkflowImplementationTypes(
                        VersionedImpl.class, OrderWorkflowV2Impl.class);
            }
            default -> throw new IllegalArgumentException("phase 는 1, 2, 3 중 하나");
        }

        worker.registerActivitiesImplementations(
                new PaymentActivityImpl(),
                new FraudActivityImpl(),
                new CreditActivityImpl(),
                new NotificationActivityImpl());
        factory.start();

        if (phase == 1) {
            OrderRequest req = new OrderRequest(
                    "2001", "C-11", "SKU-1", 1, 39000, "서울시 강남구");
            OrderWorkflow wf = client.newWorkflowStub(
                    OrderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId("order-2001")
                            .build());
            WorkflowClient.start(wf::processOrder, req);
            System.out.println("order-2001 시작. 몇 초 뒤 확인하세요:");
            System.out.println("  temporal workflow show -w order-2001");
            System.out.println("이벤트 11 TimerStarted 까지 나오면 Ctrl+C 로 내리고 phase=2 로 가세요.");
        }

        if (phase == 3) {
            OrderRequest req = new OrderRequest(
                    "2002", "C-12", "SKU-2", 1, 52000, "서울시 마포구");
            OrderWorkflow wf = client.newWorkflowStub(
                    OrderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId("order-2002")
                            .build());
            WorkflowClient.start(wf::processOrder, req);
            System.out.println("order-2002 시작. MarkerRecorded 를 확인하세요:");
            System.out.println("  temporal workflow show -w order-2002 --output json "
                    + "| jq '.events[] | select(.eventType==\"EVENT_TYPE_MARKER_RECORDED\")'");
        }

        printDeploymentChecklist();
        System.out.println("Worker 실행 중. Ctrl+C 로 종료하세요.");
        Thread.currentThread().join();
    }
}
