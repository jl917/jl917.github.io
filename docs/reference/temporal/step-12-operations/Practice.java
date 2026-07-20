package com.example.order;

/*
 * Step 12 — 운영 : Practice
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Practice
 *
 * 이 파일은 두 종류의 내용을 담습니다.
 *   (1) CLI 명령 — static final String 상수. main() 이 "명령 + 기대 출력"을 인쇄합니다.
 *       터미널에 복사해 실행하고 실제 출력과 비교하세요.
 *       ★ 의도적으로 명령을 자동 실행하지 않습니다. prod 에 terminate 를 잘못 쏘는 사고를 막기 위함입니다.
 *   (2) Java 코드 — detached scope 보상, Search Attribute, WorkerOptions. 그대로 컴파일됩니다.
 *
 * 사전 준비
 *   docker compose up -d
 *   export TEMPORAL_ADDRESS=127.0.0.1:7233
 *   export TEMPORAL_NAMESPACE=orders
 */

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.failure.CanceledFailure;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class Practice {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";
    static final String NAMESPACE = "orders";

    public static void main(String[] args) {
        CLI.printAll();
        Playbook.printAll();
    }

    // =====================================================================
    // [12-1][12-2][12-3][12-4][12-5] CLI 명령 모음
    // =====================================================================
    static class CLI {

        // [12-1] Namespace
        static final String NS_CREATE =
                "temporal operator namespace create --namespace orders --retention 72h";
        static final String NS_CREATE_OUT = """
                Namespace orders successfully registered.""";

        static final String NS_DESCRIBE =
                "temporal operator namespace describe --namespace orders";
        static final String NS_DESCRIBE_OUT = """
                NamespaceInfo.Name                                orders
                NamespaceInfo.State                               Registered
                Config.WorkflowExecutionRetentionTtl              72h0m0s
                Config.HistoryArchivalState                       Disabled
                Config.VisibilityArchivalState                    Disabled""";

        // [12-2] Retention
        static final String NS_RETENTION_30D =
                "temporal operator namespace update --namespace orders --retention 720h";
        static final String NS_RETENTION_30D_OUT = """
                Namespace orders successfully updated.""";

        static final String SHOW_EXPIRED =
                "temporal workflow show -w order-0042 --namespace orders";
        static final String SHOW_EXPIRED_OUT = """
                Error: unable to get workflow execution history:
                  workflow execution not found for workflow id: order-0042
                Error Details: NotFound""";

        static final String ARCHIVAL_ENABLE = """
                temporal operator namespace update --namespace orders \\
                  --history-archival-state enabled \\
                  --history-uri "s3://temporal-archival-prod/orders\"""";
        static final String ARCHIVAL_ENABLE_OUT = """
                Namespace orders successfully updated.""";

        // [12-3] 조회
        static final String LIST_RUNNING =
                "temporal workflow list --query \"WorkflowType='OrderWorkflow' "
                        + "AND ExecutionStatus='Running'\"";
        static final String LIST_RUNNING_OUT = """
                  Status   WorkflowId    Name           StartTime             CloseTime
                  Running  order-1001    OrderWorkflow  2026-07-20T09:12:04Z
                  Running  order-1004    OrderWorkflow  2026-07-20T09:18:33Z
                  Running  order-1007    OrderWorkflow  2026-07-20T09:21:57Z""";

        static final String COUNT_RUNNING =
                "temporal workflow count --query \"WorkflowType='OrderWorkflow' "
                        + "AND ExecutionStatus='Running'\"";
        static final String COUNT_RUNNING_OUT = "Total: 1247";

        static final String DESCRIBE_STUCK = "temporal workflow describe -w order-1004";
        static final String DESCRIBE_STUCK_OUT = """
                Execution Info:
                  Workflow Id       order-1004
                  Type              OrderWorkflow
                  Task Queue        ORDER_TASK_QUEUE
                  Status            RUNNING
                  History Length    11

                Pending Activities:
                  ActivityId              2
                  ActivityType            ShippingActivity_requestShipment
                  State                   Started
                  Attempt                 7
                  MaximumAttempts         10
                  LastFailure             {"message":"connect timed out", ...}""";

        static final String SHOW_JSON =
                "temporal workflow show -w order-1001 --output json > order-1001.json";
        static final String SHOW_JSON_OUT = """
                (18432 bytes written — Step 11 의 리플레이 테스트 입력 파일이 됩니다)""";

        static final String RESET_LAST =
                "temporal workflow reset -w order-1004 --type LastWorkflowTask "
                        + "--reason \"코드 수정 후 재개\"";
        static final String RESET_LAST_OUT = """
                Reset workflow succeeded.
                  WorkflowId  order-1004
                  RunId       0b4a9c71-5d38-4e02-8f61-9a2d7c1e3b55   ← 새 RunId""";

        static final String RESET_BATCH = """
                temporal workflow reset-batch \\
                  --query "WorkflowType='OrderWorkflow' AND ExecutionStatus='Running'" \\
                  --type LastWorkflowTask --reason "INC-2291 롤백 후 재개\"""";
        static final String RESET_BATCH_OUT = """
                Batch job started.
                  JobId  9f1c4e70-3a82-4b56-8d09-27e5f3a1c604""";

        static final String TQ_DESCRIBE =
                "temporal task-queue describe --task-queue ORDER_TASK_QUEUE";
        static final String TQ_DESCRIBE_OUT = """
                Workflow Poller Info:
                  Identity                   LastAccessTime          RatePerSecond
                  41@worker-7f9c8d-xk2mq@    2026-07-20T09:34:02Z    100000
                  41@worker-7f9c8d-p8vn4@    2026-07-20T09:34:02Z    100000

                Activity Poller Info:
                  Identity                   LastAccessTime          RatePerSecond
                  41@worker-7f9c8d-xk2mq@    2026-07-20T09:34:02Z    100000
                  41@worker-7f9c8d-p8vn4@    2026-07-20T09:34:02Z    100000""";

        // [12-4] cancel vs terminate
        static final String CANCEL =
                "temporal workflow cancel -w order-1004 --reason \"고객 요청 취소\"";
        static final String CANCEL_OUT = """
                Cancel workflow succeeded.
                  WorkflowId  order-1004
                (히스토리: WorkflowExecutionCancelRequested → 보상 액티비티들 → WorkflowExecutionCanceled)""";

        static final String TERMINATE =
                "temporal workflow terminate -w order-1007 --reason \"결정성 깨져 진행 불가 (INC-2291)\"";
        static final String TERMINATE_OUT = """
                Terminate workflow succeeded.
                  WorkflowId  order-1007
                (히스토리: WorkflowExecutionTerminated 하나뿐 — 보상 없음!)""";

        // [12-5] Search Attributes
        static final String SA_CREATE = """
                temporal operator search-attribute create --name CustomerId  --type Keyword --namespace orders
                temporal operator search-attribute create --name OrderAmount --type Int     --namespace orders
                temporal operator search-attribute create --name OrderStage  --type Keyword --namespace orders""";
        static final String SA_CREATE_OUT = """
                Search attribute CustomerId successfully registered.
                Search attribute OrderAmount successfully registered.
                Search attribute OrderStage successfully registered.""";

        static final String SA_QUERY =
                "temporal workflow list --query \"CustomerId='C-77'\" --namespace orders";
        static final String SA_QUERY_OUT = """
                  Status     WorkflowId    Name           StartTime             CloseTime
                  Completed  order-1001    OrderWorkflow  2026-07-20T09:12:04Z  2026-07-20T09:12:09Z
                  Running    order-1004    OrderWorkflow  2026-07-20T09:18:33Z""";

        static final String SA_STAGE_COUNT =
                "temporal workflow count --query \"OrderStage='SHIPPING' "
                        + "AND ExecutionStatus='Running'\" --namespace orders";
        static final String SA_STAGE_COUNT_OUT = "Total: 38";

        static void printAll() {
            section("12-1 Namespace");
            pair(NS_CREATE, NS_CREATE_OUT);
            pair(NS_DESCRIBE, NS_DESCRIBE_OUT);

            section("12-2 Retention / Archival");
            pair(NS_RETENTION_30D, NS_RETENTION_30D_OUT);
            pair(SHOW_EXPIRED, SHOW_EXPIRED_OUT);
            pair(ARCHIVAL_ENABLE, ARCHIVAL_ENABLE_OUT);

            section("12-3 CLI 레퍼런스");
            pair(LIST_RUNNING, LIST_RUNNING_OUT);
            pair(COUNT_RUNNING, COUNT_RUNNING_OUT);
            pair(DESCRIBE_STUCK, DESCRIBE_STUCK_OUT);
            pair(SHOW_JSON, SHOW_JSON_OUT);
            pair(RESET_LAST, RESET_LAST_OUT);
            pair(RESET_BATCH, RESET_BATCH_OUT);
            pair(TQ_DESCRIBE, TQ_DESCRIBE_OUT);

            section("12-4 cancel vs terminate");
            pair(CANCEL, CANCEL_OUT);
            pair(TERMINATE, TERMINATE_OUT);

            section("12-5 Search Attributes");
            pair(SA_CREATE, SA_CREATE_OUT);
            pair(SA_QUERY, SA_QUERY_OUT);
            pair(SA_STAGE_COUNT, SA_STAGE_COUNT_OUT);
        }

        static void section(String title) {
            System.out.println("\n══════════ " + title + " ══════════");
        }

        static void pair(String cmd, String out) {
            System.out.println("\n$ " + cmd);
            System.out.println(out);
        }
    }

    // =====================================================================
    // [12-4] cancel 을 받았을 때의 보상 — 잘못된 버전과 올바른 버전
    // =====================================================================
    static class CancelCompensation {

        static final ActivityOptions ACT_OPTS = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

        /*
         * 잘못된 버전.
         * 워크플로우 코드 전체가 하나의 CancellationScope 안에서 돕니다.
         * cancel 이 오면 그 스코프가 "취소됨" 상태가 되고,
         * catch 블록도 같은 스코프이므로 여기서 띄우는 액티비티는 태어나자마자 취소됩니다.
         *
         * 히스토리:
         *   11 WorkflowExecutionCancelRequested
         *   13 ActivityTaskScheduled   InventoryActivity_release
         *   14 ActivityTaskCancelRequested   ← 즉시 취소
         *   15 ActivityTaskCanceled
         *   16 WorkflowExecutionCanceled
         *   Status: CANCELED      ← ★ 정상처럼 보입니다. 이게 함정입니다.
         */
        public static class WrongOrderWorkflowImpl implements OrderWorkflow {

            private final PaymentActivity payment =
                    Workflow.newActivityStub(PaymentActivity.class, ACT_OPTS);
            private final InventoryActivity inventory =
                    Workflow.newActivityStub(InventoryActivity.class, ACT_OPTS);
            private final ShippingActivity shipping =
                    Workflow.newActivityStub(ShippingActivity.class, ACT_OPTS);

            private String status = "STARTED";

            @Override
            public String processOrder(OrderRequest req) {
                String paymentId = null;
                String reservationId = null;
                try {
                    status = "PAYMENT";
                    paymentId = payment.charge(req.orderId(), req.amount());
                    status = "INVENTORY";
                    reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
                    status = "WAITING_SHIPMENT";
                    shipping.requestShipment(req.orderId(), req.address());
                    status = "DONE";
                    return req.orderId() + " COMPLETED";
                } catch (CanceledFailure e) {
                    status = "CANCELED";
                    // ↓ 즉시 취소됩니다
                    if (reservationId != null) inventory.release(reservationId);
                    if (paymentId != null) payment.refund(paymentId);
                    throw e;
                }
            }

            @Override
            public void cancelRequested(String reason) {
                status = "CANCELED";
            }

            @Override
            public String getStatus() {
                return status;
            }
        }

        /*
         * 올바른 버전.
         * Workflow.newDetachedCancellationScope 는 부모 스코프의 취소로부터
         * 분리된 새 스코프를 만듭니다. 그 안에서 띄운 액티비티는 끝까지 실행됩니다.
         *
         * 히스토리:
         *   11 WorkflowExecutionCancelRequested
         *   13 ActivityTaskScheduled   InventoryActivity_release
         *   14 ActivityTaskCompleted                                ← 완료!
         *   15 ActivityTaskScheduled   PaymentActivity_refund
         *   16 ActivityTaskCompleted                                ← 완료!
         *   19 WorkflowExecutionCanceled
         *   Status: CANCELED
         *
         * ★ 두 버전 모두 Status 가 CANCELED 입니다. 히스토리를 봐야 구분됩니다.
         */
        public static class CorrectOrderWorkflowImpl implements OrderWorkflow {

            private final PaymentActivity payment =
                    Workflow.newActivityStub(PaymentActivity.class, ACT_OPTS);
            private final InventoryActivity inventory =
                    Workflow.newActivityStub(InventoryActivity.class, ACT_OPTS);
            private final ShippingActivity shipping =
                    Workflow.newActivityStub(ShippingActivity.class, ACT_OPTS);
            private final NotificationActivity notification =
                    Workflow.newActivityStub(NotificationActivity.class, ACT_OPTS);

            private String status = "STARTED";

            @Override
            public String processOrder(OrderRequest req) {
                // 람다에서 참조하려면 effectively final 이어야 하므로 배열로 감쌉니다.
                final String[] paymentId = {null};
                final String[] reservationId = {null};
                try {
                    status = "PAYMENT";
                    paymentId[0] = payment.charge(req.orderId(), req.amount());
                    status = "INVENTORY";
                    reservationId[0] = inventory.reserve(req.orderId(), req.sku(), req.qty());
                    status = "WAITING_SHIPMENT";
                    shipping.requestShipment(req.orderId(), req.address());
                    status = "DONE";
                    return req.orderId() + " COMPLETED";

                } catch (CanceledFailure e) {
                    status = "CANCELED";
                    CancellationScope compensation =
                            Workflow.newDetachedCancellationScope(() -> {
                                if (reservationId[0] != null) inventory.release(reservationId[0]);
                                if (paymentId[0] != null) payment.refund(paymentId[0]);
                                notification.notifyCustomer(req.orderId(), "주문이 취소되었습니다");
                            });
                    compensation.run();
                    throw e;
                }
            }

            @Override
            public void cancelRequested(String reason) {
                status = "CANCELED";
            }

            @Override
            public String getStatus() {
                return status;
            }
        }
    }

    // =====================================================================
    // [12-5] Search Attributes
    // =====================================================================
    static class SearchAttrs {

        // 키를 상수로 뽑습니다. 문자열 오타를 컴파일 타임에 막기 위해서입니다.
        static final SearchAttributeKey<String> CUSTOMER_ID =
                SearchAttributeKey.forKeyword("CustomerId");
        static final SearchAttributeKey<Long> ORDER_AMOUNT =
                SearchAttributeKey.forLong("OrderAmount");
        static final SearchAttributeKey<String> ORDER_STAGE =
                SearchAttributeKey.forKeyword("OrderStage");

        // 시작 시점에 설정
        static WorkflowOptions optionsWithSearchAttributes(OrderRequest req) {
            SearchAttributes sa = SearchAttributes.newBuilder()
                    .set(CUSTOMER_ID, req.customerId())
                    .set(ORDER_AMOUNT, req.amount())
                    .build();

            return WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId("order-" + req.orderId())
                    // 1.20 이전의 setSearchAttributes(Map) 은 deprecated 입니다.
                    .setTypedSearchAttributes(sa)
                    .build();
        }

        // 실행 중 갱신 — 히스토리에 UpsertWorkflowSearchAttributes 이벤트가 남습니다(결정적).
        static void markStage(String stage) {
            Workflow.upsertTypedSearchAttributes(ORDER_STAGE.valueSet(stage));
        }
    }

    // =====================================================================
    // [12-7] Worker 튜닝
    // =====================================================================
    static class WorkerTuning {

        /*
         * 안전한 구성.
         * 규칙: maxWorkflowThreadCount >= workflowCacheSize x (워크플로우당 평균 스레드 + 1)
         *   - 단순 워크플로우(자식 없음, Async 없음): 배수 2
         *   - 자식 워크플로우 / Async.function 여러 개: 배수 3~4
         */
        static WorkerFactory buildFactory(WorkflowClient client) {
            WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder()
                    .setWorkflowCacheSize(1200)
                    .setMaxWorkflowThreadCount(2400)      // cacheSize x 2
                    .build();
            return WorkerFactory.newInstance(client, factoryOptions);
        }

        static Worker buildWorker(WorkerFactory factory) {
            WorkerOptions options = WorkerOptions.newBuilder()
                    .setMaxConcurrentWorkflowTaskExecutionSize(400)   // 기본 200
                    .setMaxConcurrentActivityExecutionSize(100)       // 결제 API 부하 고려해 낮춤
                    .setMaxConcurrentWorkflowTaskPollers(10)          // 기본 5
                    .setMaxConcurrentActivityTaskPollers(10)          // 기본 5
                    .setMaxTaskQueueActivitiesPerSecond(50.0)         // 게이트웨이 보호 (전역)
                    .build();
            return factory.newWorker(TASK_QUEUE, options);
        }

        /*
         * 위험한 구성 — 재현용.
         * cacheSize 와 threadCount 가 같습니다. 캐시가 가득 차면 스레드가 정확히 소진됩니다.
         *
         * 재현 방법: 이 팩토리로 Worker 를 띄우고, 자식 워크플로우를 동시에 600개 이상 시작하세요.
         *
         * 결과 (Worker 로그):
         *   ERROR i.t.i.w.WorkflowExecutionHandler -
         *     io.temporal.internal.sync.PotentialDeadlockException:
         *     Potential deadlock detected: workflow thread "workflow-method-order-2288-1"
         *     didn't yield control for over a second.
         *     Current thread pool: 600/600 in use, 0 available.
         */
        static WorkerFactory buildDangerousFactory(WorkflowClient client) {
            WorkerFactoryOptions bad = WorkerFactoryOptions.newBuilder()
                    .setWorkflowCacheSize(600)
                    .setMaxWorkflowThreadCount(600)       // ★ 같은 값 — 데드락 위험
                    .build();
            return WorkerFactory.newInstance(client, bad);
        }
    }

    // =====================================================================
    // [12-8] 운영 플레이북
    // =====================================================================
    static class Playbook {

        /*
         * 1단계 분기 조건
         *   Pending Activities 있음 + Attempt 증가  → 외부 시스템 장애 (4단계로)
         *   Pending Activities 있음 + State Scheduled + Attempt 1 → Worker 가 안 가져감 (2단계로)
         *   Pending Workflow Task 만 있고 Attempt 가 큼 → 결정성 문제 (3단계로)
         */
        static final String[] STEP_1 = {
                "temporal workflow describe -w order-1004 --namespace orders"
        };

        /*
         * 2단계 — Poller 가 비어 있으면 원인은 셋 중 하나
         *   (a) Worker 프로세스 죽음
         *   (b) Worker 가 다른 Task Queue 이름으로 붙음 (오타 — 매우 흔함)
         *   (c) Worker 가 다른 Namespace 에 붙음
         */
        static final String[] STEP_2 = {
                "temporal task-queue describe --task-queue ORDER_TASK_QUEUE --namespace orders"
        };

        static final String[] STEP_3 = {
                "kubectl logs deploy/order-worker --since=10m | grep -E \"NonDeterministic|Deadlock|ERROR\""
        };

        static final String[] STEP_4 = {
                "temporal workflow count --query \"ExecutionStatus='Running'\" --namespace orders",
                "curl -s localhost:8080/actuator/prometheus | grep workflow_task_execution_failed"
        };

        /*
         * 5단계 조치
         *   NonDeterministic + 다수  → 즉시 롤백. 그다음 getVersion 넣고 재배포
         *   NonDeterministic + 소수  → 롤백 후 reset --type LastWorkflowTask
         *   외부 시스템 장애         → ★ 아무것도 하지 마세요. 재시도가 알아서 성공합니다
         *   장시간 복구 불가         → cancel (보상 실행) → 나중에 재주문
         *   cancel 도 안 먹음        → describe 로 기록한 뒤 terminate, 보상은 수동
         *   Worker 없음              → Worker 배포 수정. 붙는 즉시 재개됨
         */
        static final String[] STEP_5 = {
                "kubectl rollout undo deploy/order-worker",
                "temporal workflow reset-batch "
                        + "--query \"WorkflowType='OrderWorkflow' AND ExecutionStatus='Running'\" "
                        + "--type LastWorkflowTask --reason \"INC-2291 롤백 후 재개\" --namespace orders",
                "temporal batch describe --job-id 9f1c4e70-3a82-4b56-8d09-27e5f3a1c604"
        };

        static void printAll() {
            CLI.section("12-8 운영 플레이북");
            printStep("1단계 — describe 로 무엇을 기다리는지 본다", STEP_1);
            printStep("2단계 — task-queue describe 로 Worker 확인", STEP_2);
            printStep("3단계 — Worker 로그", STEP_3);
            printStep("4단계 — 메트릭으로 범위 파악", STEP_4);
            printStep("5단계 — 조치", STEP_5);
        }

        static void printStep(String title, String[] commands) {
            System.out.println("\n[" + title + "]");
            for (String c : commands) {
                System.out.println("  $ " + c);
            }
        }
    }
}
