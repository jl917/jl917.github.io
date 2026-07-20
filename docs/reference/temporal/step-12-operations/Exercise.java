package com.example.order;

/*
 * Step 12 — 운영 : Exercise (문제지)
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Exercise
 *
 * 7문제입니다. // TODO 자리를 채우고 main() 을 실행하면 자동 채점 결과가 인쇄됩니다.
 * CLI 문제는 정답 문자열을 정확히 외울 필요가 없습니다 — 핵심 옵션 토큰만 맞으면 통과합니다.
 */

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.failure.CanceledFailure;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class Exercise {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // =====================================================================
    // 문제 1 — Namespace 생성 + Retention 30일
    //
    // orders 라는 Namespace 를 만들고, Retention 을 30일로 바꾸는 명령 두 개를
    // 개행으로 이어 붙여 반환하세요.
    // 힌트: Retention 은 시간 단위로 지정합니다. 30일 = ?h
    // =====================================================================
    static String 문제1_namespace_명령() {
        // TODO: 여기에 작성
        return "";
    }

    // =====================================================================
    // 문제 2 — Search Attribute 쿼리
    //
    // "고객 C-77 의, 금액 10만원 초과, 실패한 주문"을 찾는 --query 문자열만
    // (temporal workflow list 부분 제외하고) 반환하세요.
    //
    // 사용 가능한 Search Attribute:
    //   CustomerId (Keyword) / OrderAmount (Int) / ExecutionStatus (Keyword)
    // =====================================================================
    static String 문제2_쿼리() {
        // TODO: 여기에 작성
        return "";
    }

    // =====================================================================
    // 문제 3 — cancel 을 받아 보상 실행하기  ★ 이 스텝의 핵심
    //
    // catch (CanceledFailure e) 블록을 채워 보상 액티비티 3개를
    // release → refund → notifyCustomer 순으로 실행하세요.
    //
    // ⚠️ 그냥 부르면 즉시 취소됩니다. 검증 메서드가 이를 확인합니다.
    //    힌트: 워크플로우 전체가 하나의 CancellationScope 안에서 돕니다.
    //          catch 블록도 같은 스코프입니다. 어떻게 분리해야 할까요?
    // =====================================================================
    public static class Q3OrderWorkflowImpl implements OrderWorkflow {

        static final ActivityOptions ACT = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, ACT);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, ACT);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, ACT);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, ACT);

        private String status = "STARTED";

        @Override
        public String processOrder(OrderRequest req) {
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

                // TODO: 여기에 작성 — 보상 3개를 끝까지 실행되게 만드세요
                //   if (reservationId[0] != null) inventory.release(reservationId[0]);
                //   if (paymentId[0] != null)     payment.refund(paymentId[0]);
                //   notification.notifyCustomer(req.orderId(), "주문이 취소되었습니다");

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

    // =====================================================================
    // 문제 4 — Search Attribute 등록과 upsert
    //
    // (a) OrderStage 를 Keyword 타입으로 등록하는 CLI 명령을 반환하세요.
    // (b) STAGE 키 상수를 선언하세요.
    // (c) markStage() 를 구현하세요.
    //
    // 힌트: upsertTypedSearchAttributes 는 워크플로우 코드 안에서 부르므로
    //       결정적이어야 합니다. 조건문 안에서 부를 때는 그 조건이 리플레이 시에도
    //       같은 결과를 내는지 확인하세요 (Math.random() 같은 것 금지).
    // =====================================================================
    static String 문제4a_SA_등록_명령() {
        // TODO: 여기에 작성
        return "";
    }

    // TODO: 여기에 작성 — (b) SearchAttributeKey<String> STAGE 상수 선언
    // static final SearchAttributeKey<String> STAGE = ...;

    static void 문제4c_markStage(String stage) {
        // TODO: 여기에 작성 — (c) Workflow.upsertTypedSearchAttributes(...)
    }

    // =====================================================================
    // 문제 5 — Worker 튜닝 (데드락 방지)
    //
    // workflowCacheSize 는 2000 으로 이미 지정되어 있습니다.
    // maxWorkflowThreadCount 를 안전한 값으로 채우세요.
    //
    // 힌트: 캐시된 워크플로우 하나가 스레드를 최소 1개 점유합니다.
    //       모자라면 PotentialDeadlockException 이 납니다.
    // =====================================================================
    static WorkerFactory 문제5_factory(WorkflowClient client) {
        WorkerFactoryOptions options = WorkerFactoryOptions.newBuilder()
                .setWorkflowCacheSize(2000)
                // TODO: 여기에 작성 — setMaxWorkflowThreadCount(?)
                .build();
        return WorkerFactory.newInstance(client, options);
    }

    static int 문제5_선택한_threadCount() {
        // TODO: 여기에 작성 — 위에서 지정한 값을 그대로 반환 (채점용)
        return 0;
    }

    /*
     * =====================================================================
     * 문제 6 — 메트릭 판독
     *
     * 세 개의 스냅샷을 읽고 각각의 원인을 한 줄로 답하세요.
     * 답은 아래 세 가지 중 하나입니다: "결정성" / "Worker부족" / "캐시부족"
     *
     * ── 스냅샷 A ──────────────────────────────────────────
     * temporal_workflow_task_execution_failed_total{...}          8912.0
     * temporal_workflow_task_schedule_to_start_latency{q=0.95}    0.021
     * temporal_worker_task_slots_available{...}                   380.0
     * temporal_sticky_cache_miss_total{...}                       12.0
     *
     * ── 스냅샷 B ──────────────────────────────────────────
     * temporal_workflow_task_execution_failed_total{...}          0.0
     * temporal_workflow_task_schedule_to_start_latency{q=0.95}    6.412
     * temporal_activity_schedule_to_start_latency{q=0.95}         9.884
     * temporal_worker_task_slots_available{...}                   0.0
     * temporal_sticky_cache_miss_total{...}                       31.0
     *
     * ── 스냅샷 C ──────────────────────────────────────────
     * temporal_workflow_task_execution_failed_total{...}          0.0
     * temporal_workflow_task_schedule_to_start_latency{q=0.95}    0.412
     * temporal_workflow_task_execution_latency{q=0.95}            2.841
     * temporal_worker_task_slots_available{...}                   150.0
     * temporal_sticky_cache_miss_total{...}                       8421.0
     * =====================================================================
     */
    static String 문제6_A() {
        // TODO: 여기에 작성
        return "";
    }

    static String 문제6_B() {
        // TODO: 여기에 작성
        return "";
    }

    static String 문제6_C() {
        // TODO: 여기에 작성
        return "";
    }

    // =====================================================================
    // 문제 7 — 진단 명령 순서
    //
    // "order-1004 가 Running 인데 안 움직인다" 는 신고를 받았습니다.
    // 진단 1~4단계에 해당하는 명령을 순서대로 배열에 담아 반환하세요.
    //   1단계: 워크플로우가 무엇을 기다리는지
    //   2단계: Worker 가 붙어 있는지
    //   3단계: Worker 로그에서 예외 찾기
    //   4단계: 영향 범위 (Running 개수)
    // =====================================================================
    static String[] 문제7_진단_순서() {
        // TODO: 여기에 작성
        return new String[]{"", "", "", ""};
    }

    // =====================================================================
    // 자동 채점
    // =====================================================================
    public static void main(String[] args) {
        int pass = 0, total = 7;

        pass += check(1, 문제1_namespace_명령(),
                "namespace create", "--retention 720h");
        pass += check(2, 문제2_쿼리(),
                "CustomerId='C-77'", "OrderAmount > 100000", "ExecutionStatus='Failed'");
        pass += checkQ3();
        pass += check(4, 문제4a_SA_등록_명령(),
                "search-attribute create", "--name OrderStage", "--type Keyword");
        pass += checkQ5();
        pass += checkQ6();
        pass += checkQ7();

        System.out.printf("%n결과: %d / %d 통과%n", pass, total);
        if (pass < total) {
            System.out.println("Solution.java 로 정답과 해설을 확인하세요.");
        }
    }

    static int check(int no, String actual, String... requiredTokens) {
        for (String t : requiredTokens) {
            if (actual == null || !actual.contains(t)) {
                System.out.printf("문제 %d — FAIL (누락: %s)%n", no, t);
                return 0;
            }
        }
        System.out.printf("문제 %d — PASS%n", no);
        return 1;
    }

    static int checkQ3() {
        // Q3OrderWorkflowImpl 의 소스에 detached scope 를 썼는지는 런타임으로 알 수 없으므로,
        // 실제 검증은 Step 11 방식의 테스트로 합니다.
        // 여기서는 안내만 출력합니다.
        System.out.println("문제 3 — 수동 검증: 아래 두 가지를 모두 만족해야 정답입니다.");
        System.out.println("  (a) Workflow.newDetachedCancellationScope 를 사용했는가");
        System.out.println("  (b) 스코프를 만든 뒤 .run() 을 호출했는가 (만들기만 하면 실행되지 않습니다)");
        System.out.println("  확인법: 워크플로우를 시작해 cancel 한 뒤");
        System.out.println("          temporal workflow show -w order-XXXX 의 히스토리에");
        System.out.println("          ActivityTaskCompleted 가 3개 있으면 정답,");
        System.out.println("          ActivityTaskCanceled 가 있으면 오답입니다.");
        return 0;
    }

    static int checkQ5() {
        int t = 문제5_선택한_threadCount();
        if (t >= 4000) {
            System.out.println("문제 5 — PASS (threadCount=" + t + ")");
            return 1;
        }
        System.out.println("문제 5 — FAIL (threadCount=" + t
                + " — cacheSize 2000 의 최소 2배가 필요합니다)");
        return 0;
    }

    static int checkQ6() {
        boolean ok = "결정성".equals(문제6_A())
                && "Worker부족".equals(문제6_B())
                && "캐시부족".equals(문제6_C());
        System.out.println("문제 6 — " + (ok ? "PASS" : "FAIL"));
        return ok ? 1 : 0;
    }

    static int checkQ7() {
        String[] s = 문제7_진단_순서();
        boolean ok = s.length == 4
                && s[0].contains("workflow describe")
                && s[1].contains("task-queue describe")
                && s[2].contains("logs")
                && s[3].contains("workflow count");
        System.out.println("문제 7 — " + (ok ? "PASS" : "FAIL"));
        return ok ? 1 : 0;
    }
}
