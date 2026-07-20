package com.example.order.step08;

/*
 * Step 08 — 자식 워크플로우와 Continue-As-New / Practice
 *
 * 실행:
 *   ./gradlew run -PmainClass=com.example.order.step08.Practice
 *
 * 이 프로세스를 띄워 둔 채로 다른 터미널에서 CLI 를 실행합니다.
 *
 *   [8-2] 자식 워크플로우 히스토리 분리
 *     temporal workflow start --task-queue ORDER_TASK_QUEUE --type OrderWorkflow \
 *       --workflow-id order-2001 \
 *       --input '{"orderId":"2001","customerId":"C-12","sku":"SKU-C","qty":2,"amount":78000,"address":"서울시 마포구 양화로 45"}'
 *     temporal workflow show -w order-2001        ← 부모: 20개
 *     temporal workflow show -w shipment-2001     ← 자식: 22개 (완전히 별개)
 *
 *   [8-5] 히스토리 폭증 재현 (continueAsNew 없음)
 *     temporal workflow start --task-queue ORDER_TASK_QUEUE --type BadPollingWorkflow \
 *       --workflow-id poll-bad --input '"9001"'
 *     watch -n 10 'temporal workflow describe -w poll-bad | grep History'
 *
 *   [8-6] continueAsNew 적용
 *     temporal workflow start --task-queue ORDER_TASK_QUEUE --type GoodPollingWorkflow \
 *       --workflow-id poll-good --input '"9002"' --input '0' --input '[]'
 *     temporal workflow describe -w poll-good     ← History Length 가 8 로 리셋됨
 *
 *   [8-9] 정리
 *     temporal workflow terminate -w poll-bad  --reason "실습 정리"
 *     temporal workflow terminate -w poll-good --reason "실습 정리"
 *
 * 환경: Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Practice {

    private static final Logger LOG = LoggerFactory.getLogger(Practice.class);

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(
            String orderId,
            String customerId,
            String sku,
            int qty,
            long amount,
            String address) {
    }

    // ---------------------------------------------------------------------
    // 액티비티
    // ---------------------------------------------------------------------

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod
        String charge(String orderId, long amount);

        @ActivityMethod
        void refund(String paymentId);
    }

    public static class PaymentActivityImpl implements PaymentActivity {
        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            log.info("[{}] 결제 amount={}", orderId, amount);
            return "pay-" + Integer.toHexString(orderId.hashCode() & 0xffffff);
        }

        @Override
        public void refund(String paymentId) {
            log.info("환불 {}", paymentId);
        }
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod
        String requestShipment(String orderId, String address);

        @ActivityMethod
        void cancelShipment(String shipmentId);

        @ActivityMethod
        String confirmDelivery(String trackingNo);
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = LoggerFactory.getLogger(ShippingActivityImpl.class);

        @Override
        public String requestShipment(String orderId, String address) {
            log.info("[{}] 배송 요청 → {}", orderId, address);
            return "TRK-" + Integer.toHexString((orderId + address).hashCode() & 0xffffff).toUpperCase();
        }

        @Override
        public void cancelShipment(String shipmentId) {
            log.info("배송 취소 {}", shipmentId);
        }

        @Override
        public String confirmDelivery(String trackingNo) {
            log.info("배송 완료 확인 {}", trackingNo);
            return trackingNo;
        }
    }

    // ---------------------------------------------------------------------
    // [8-1][8-2] 자식 워크플로우 — 배송
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface ShipmentWorkflow {

        @WorkflowMethod
        String deliver(String orderId, String address);

        /** 자식 워크플로우는 시그널을 받을 수 있습니다. 액티비티는 못 합니다. */
        @SignalMethod
        void expedite();

        @QueryMethod
        String getShipmentStage();
    }

    public static class ShipmentWorkflowImpl implements ShipmentWorkflow {

        private static final Logger log = Workflow.getLogger(ShipmentWorkflowImpl.class);

        private String stage = "PICKING";
        private boolean expedited = false;

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .build());

        @Override
        public String deliver(String orderId, String address) {
            stage = "REQUESTING";
            String trackingNo = shipping.requestShipment(orderId, address);

            // 실제 배송은 며칠 걸립니다. 자식 워크플로우라 타이머가 자연스럽습니다.
            // (액티비티였다면 heartbeat 로 버티거나 폴링해야 합니다.)
            stage = "IN_TRANSIT";
            Duration transit = expedited ? Duration.ofSeconds(2) : Duration.ofSeconds(5);
            Workflow.await(transit, () -> expedited);

            stage = "DELIVERING";
            String result = shipping.confirmDelivery(trackingNo);
            stage = "DELIVERED";

            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            log.info("[{}] 배송 완료 {}", orderId, result);
            return result;
        }

        @Override
        public void expedite() {
            this.expedited = true;
        }

        @Override
        public String getShipmentStage() {
            return stage;
        }
    }

    // ---------------------------------------------------------------------
    // [8-1][8-3] 부모 워크플로우
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface OrderWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class OrderWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());

            // [8-1] 자식 워크플로우 실행.
            //       ParentClosePolicy 를 명시합니다. 기본값(TERMINATE)에 기대지 않습니다.
            //       배송은 실물이 움직이므로 부모가 죽어도 끝까지 가야 합니다 → ABANDON.
            ShipmentWorkflow shipment = Workflow.newChildWorkflowStub(
                    ShipmentWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId("shipment-" + req.orderId())
                            .setTaskQueue(ORDER_TASK_QUEUE)
                            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                            .setWorkflowExecutionTimeout(Duration.ofDays(7))
                            .build());

            String trackingNo = shipment.deliver(req.orderId(), req.address());

            log.info("[{}] 주문 완료 tracking={}", req.orderId(), trackingNo);
            return req.orderId() + " SHIPPED " + trackingNo;
        }
    }

    // ---------------------------------------------------------------------
    // [8-3] 자식 병렬 실행
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface MultiWarehouseWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class MultiWarehouseWorkflowImpl implements MultiWarehouseWorkflow {

        private static final Logger log = Workflow.getLogger(MultiWarehouseWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            List<String> warehouses = List.of("WH-SEOUL", "WH-BUSAN", "WH-DAEGU");
            List<Promise<String>> results = new ArrayList<>();

            for (String warehouse : warehouses) {
                ShipmentWorkflow child = Workflow.newChildWorkflowStub(
                        ShipmentWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("shipment-" + req.orderId() + "-" + warehouse)
                                .setTaskQueue(ORDER_TASK_QUEUE)
                                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                                .build());

                // [8-3] Async.function 은 즉시 반환합니다. 자식은 백그라운드로 진행.
                Promise<String> promise = Async.function(child::deliver, req.orderId(), warehouse);

                // [8-3] 자식이 "시작되었음"(RunId 확정)을 먼저 확인합니다.
                //       이 줄을 지우면 자식 ID 중복 같은 오류가 훨씬 나중에(결과를 get 할 때) 드러납니다.
                Workflow.getWorkflowExecution(child).get();

                results.add(promise);
            }

            // 셋 다 끝날 때까지 대기
            Promise.allOf(results).get();

            List<String> trackingNos = results.stream().map(Promise::get).toList();
            log.info("[{}] 병렬 배송 완료 {}", req.orderId(), trackingNos);
            return req.orderId() + " SHIPPED " + trackingNos;
        }
    }

    // ---------------------------------------------------------------------
    // [8-5] ⚠️ continueAsNew 없는 무한 루프 — 절대 이렇게 배포하지 마십시오
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface BadPollingWorkflow {

        @WorkflowMethod
        String pollForever(String orderId);

        @QueryMethod
        int getTickCount();
    }

    /**
     * [8-5] 히스토리 폭증 재현용.
     *
     * 한 회당 이벤트 4개(TimerStarted / TimerFired / WorkflowTaskScheduled·Started·Completed 중
     * TimerStarted 가 앞 Task 에 묶여 실질 4개)가 쌓입니다.
     *
     *   100 틱  → History Length   412 / History Size    52,180
     *   1,000틱 → History Length 4,012 / History Size   508,944
     *   5,000틱 → History Length 20,012 / History Size 2,539,104   ← 서버 경고 로그
     *  12,800틱 → History Length 51,201                            ← 강제 종료
     *
     * 서버 경고 확인:
     *   docker compose logs temporal | grep -i "history size exceeds"
     *
     * 강제 종료 이벤트 확인:
     *   temporal workflow show -w poll-bad --output json | jq '.events[-1]'
     *   → reason: "Workflow exceeded history event limit: 51200"
     */
    public static class BadPollingWorkflowImpl implements BadPollingWorkflow {

        private static final Logger log = Workflow.getLogger(BadPollingWorkflowImpl.class);

        private int count = 0;

        @Override
        public String pollForever(String orderId) {
            while (true) {
                Workflow.sleep(Duration.ofSeconds(1));
                count++;

                if (count % 100 == 0) {
                    log.info("[{}] tick {} historyLength={} historySize={}",
                            orderId, count,
                            Workflow.getInfo().getHistoryLength(),
                            Workflow.getInfo().getHistorySize());
                }
            }
        }

        @Override
        public int getTickCount() {
            return count;
        }
    }

    // ---------------------------------------------------------------------
    // [8-6][8-7][8-8] continueAsNew 를 적용한 버전
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface GoodPollingWorkflow {

        /**
         * 이월할 상태를 전부 인자로 받습니다.
         * 필드는 새 Run 에서 사라지므로, 살아남아야 하는 것은 반드시 인자여야 합니다.
         */
        @WorkflowMethod
        String poll(String orderId, int carriedCount, List<String> carriedTasks);

        @SignalMethod
        void addTask(String task);

        @SignalMethod
        void finish();

        @QueryMethod
        int getTickCount();

        @QueryMethod
        List<String> getPendingTasks();
    }

    public static class GoodPollingWorkflowImpl implements GoodPollingWorkflow {

        private static final Logger log = Workflow.getLogger(GoodPollingWorkflowImpl.class);

        /** [8-7] 처리 건수 기준 임계값. 회당 4 이벤트 × 1000 = 약 4,000 이벤트. */
        private static final int TICKS_PER_RUN = 1000;

        /**
         * [8-8] 이 상수를 false 로 바꾸면 시그널 유실을 재현합니다.
         *
         *   DRAIN = false → continueAsNew 직전 시그널이 조용히 사라짐.
         *                   히스토리에는 WorkflowExecutionSignaled 가 남고,
         *                   워크플로우 Status 는 계속 RUNNING.
         *                   유일한 흔적은 이전 Run 워커 로그의 WARN 한 줄.
         *                   확인: temporal workflow query -w poll-good --type getPendingTasks → []
         *
         *   DRAIN = true  → WorkflowTaskScheduled/Started/Completed 3개가 더 붙고 시그널이 처리됨.
         *                   확인: ... --type getPendingTasks → ["urgent-task"]
         */
        private static final boolean DRAIN = true;

        private int count;
        private final List<String> pendingTasks = new ArrayList<>();
        private boolean finished = false;

        @Override
        public String poll(String orderId, int carriedCount, List<String> carriedTasks) {
            this.count = carriedCount;
            if (carriedTasks != null) {
                this.pendingTasks.addAll(carriedTasks);       // [8-8] 이월된 작업을 이어받는다
            }

            log.info("[{}] Run 시작 carriedCount={} carriedTasks={}",
                    orderId, carriedCount, this.pendingTasks.size());

            for (int i = 0; i < TICKS_PER_RUN; i++) {
                Workflow.sleep(Duration.ofSeconds(1));
                count++;

                if (finished) {
                    Workflow.await(() -> Workflow.isEveryHandlerFinished());
                    return orderId + " DONE ticks=" + count;
                }
            }

            // [8-8] (a) 미처리 시그널 핸들러 드레인
            if (DRAIN) {
                Workflow.await(() -> Workflow.isEveryHandlerFinished());
            }

            // [8-6] 히스토리를 리셋하고 새 Run 으로 넘어간다.
            //       이 호출은 반환하지 않습니다 — 현재 Run 이 여기서 종료됩니다.
            // [8-8] (b) 처리 못 한 작업 큐를 인자로 이월
            GoodPollingWorkflow next = Workflow.newContinueAsNewStub(GoodPollingWorkflow.class);
            next.poll(orderId, count, new ArrayList<>(pendingTasks));

            throw new IllegalStateException("도달할 수 없음");
        }

        @Override
        public void addTask(String task) {
            pendingTasks.add(task);
        }

        @Override
        public void finish() {
            finished = true;
        }

        @Override
        public int getTickCount() {
            return count;
        }

        @Override
        public List<String> getPendingTasks() {
            return pendingTasks;
        }
    }

    // ---------------------------------------------------------------------
    // [8-7] 히스토리 길이 기준 임계값 버전
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface AdaptivePollingWorkflow {

        @WorkflowMethod
        String poll(String orderId, int carriedCount);

        @QueryMethod
        int getTickCount();
    }

    /**
     * [8-7] 회당 이벤트 수가 일정하지 않을 때(시그널이 몇 개 올지, 액티비티가 몇 번 재시도할지
     * 모를 때)는 히스토리 길이를 직접 재는 편이 안전합니다.
     *
     * getHistoryLength() 는 리플레이 시에도 그 시점의 값을 그대로 재현하므로 조건문에 써도
     * 결정성이 깨지지 않습니다. System.currentTimeMillis() 와 다릅니다.
     *
     * isContinueAsNewSuggested() 는 서버가 경고 임계값(10,240 이벤트 / 10MB) 근처에서
     * true 로 바꿔 줍니다. 가장 안전한 판단 기준입니다.
     */
    public static class AdaptivePollingWorkflowImpl implements AdaptivePollingWorkflow {

        private static final Logger log = Workflow.getLogger(AdaptivePollingWorkflowImpl.class);
        private static final int HISTORY_THRESHOLD = 8_000;

        private int count;

        @Override
        public String poll(String orderId, int carriedCount) {
            this.count = carriedCount;

            while (true) {
                Workflow.sleep(Duration.ofSeconds(1));
                count++;

                boolean tooBig = Workflow.getInfo().getHistoryLength() > HISTORY_THRESHOLD;
                boolean suggested = Workflow.getInfo().isContinueAsNewSuggested();

                if (tooBig || suggested) {
                    log.info("[{}] continueAsNew 임계값 도달 length={} suggested={}",
                            orderId, Workflow.getInfo().getHistoryLength(), suggested);
                    break;
                }
            }

            Workflow.await(() -> Workflow.isEveryHandlerFinished());

            AdaptivePollingWorkflow next =
                    Workflow.newContinueAsNewStub(AdaptivePollingWorkflow.class);
            next.poll(orderId, count);

            throw new IllegalStateException("도달할 수 없음");
        }

        @Override
        public int getTickCount() {
            return count;
        }
    }

    // ---------------------------------------------------------------------
    // main
    // ---------------------------------------------------------------------

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(ORDER_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                OrderWorkflowImpl.class,
                ShipmentWorkflowImpl.class,
                MultiWarehouseWorkflowImpl.class,
                BadPollingWorkflowImpl.class,
                GoodPollingWorkflowImpl.class,
                AdaptivePollingWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new PaymentActivityImpl(),
                new ShippingActivityImpl());

        factory.start();
        LOG.info("Worker started. {}", ORDER_TASK_QUEUE);
    }
}
