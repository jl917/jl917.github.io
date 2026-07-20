package com.example.order.step07;

/*
 * Step 07 — Signal · Query · Update / Practice
 *
 * 실행:
 *   ./gradlew run -PmainClass=com.example.order.step07.Practice
 *
 * 이 프로세스를 띄워 둔 채로 다른 터미널에서 CLI 를 실행합니다.
 *
 *   temporal workflow start --task-queue ORDER_TASK_QUEUE --type OrderWorkflow \
 *     --workflow-id order-1001 \
 *     --input '{"orderId":"1001","customerId":"C-77","sku":"SKU-A","qty":1,"amount":39000,"address":"서울시 강남구 테헤란로 1"}'
 *
 *   temporal workflow signal -w order-1001 --name cancelRequested --input '"customer changed mind"'
 *   temporal workflow query  -w order-1001 --type getStatus
 *   temporal workflow update -w order-1001 --name changeAddress --input '"서울시 송파구 올림픽로 300"'
 *   temporal workflow show   -w order-1001
 *
 * 환경: Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
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

    // ---------------------------------------------------------------------
    // 공통 DTO
    // ---------------------------------------------------------------------

    public record OrderRequest(
            String orderId,
            String customerId,
            String sku,
            int qty,
            long amount,
            String address) {
    }

    public record OrderProgress(
            String stage,
            int completedSteps,
            int totalSteps,
            boolean cancelled) {
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
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = LoggerFactory.getLogger(ShippingActivityImpl.class);

        @Override
        public String requestShipment(String orderId, String address) {
            log.info("[{}] 배송 요청 → {}", orderId, address);
            return "ship-" + Integer.toHexString(address.hashCode() & 0xffffff);
        }

        @Override
        public void cancelShipment(String shipmentId) {
            log.info("배송 취소 {}", shipmentId);
        }
    }

    // ---------------------------------------------------------------------
    // [7-2][7-5][7-6][7-7] 주문 워크플로우
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface OrderWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);

        /** [7-2] 취소 요청. 시그널은 반환 타입이 void 여야 합니다. */
        @SignalMethod
        void cancelRequested(String reason);

        /** [7-5] 현재 단계 조회. 읽기 전용. */
        @QueryMethod
        String getStatus();

        /** [7-5] 진행률 조회. record 를 그대로 반환하면 JSON 으로 직렬화됩니다. */
        @QueryMethod
        OrderProgress getProgress();

        /**
         * [7-6] ⚠️ 일부러 잘못 만든 Query. 절대 흉내 내지 마십시오.
         *
         * 재현 절차:
         *   1) temporal workflow query -w order-1003 --type getStatusBad   → "조회 1회"
         *   2) 같은 명령 두 번 더                                            → "조회 2회", "조회 3회"
         *   3) 워커를 Ctrl+C 후 재시작
         *   4) 같은 명령 한 번 더                                            → "조회 1회"  ← 되돌아감
         *
         * viewCount++ 는 워커 메모리 안의 객체에만 일어난 일이고 히스토리에는 흔적이 없습니다.
         * 워커가 재시작되면 히스토리만으로 객체가 재구성되므로 0 에서 다시 시작합니다.
         */
        @QueryMethod
        String getStatusBad();

        /**
         * [7-6] Query 안에서 액티비티를 호출합니다.
         * 이쪽은 SDK 가 막아 줍니다:
         *   InvalidStateException: Query method getStatusWorse should not modify workflow state
         * 즉 SDK 가 막는 것은 "커맨드 생성" 이지 "필드 대입" 이 아닙니다.
         */
        @QueryMethod
        String getStatusWorse();

        /** [7-7] 배송지 변경 — 검증 후 상태 변경 + 최종값 반환. */
        @UpdateMethod
        String changeAddress(String newAddress);

        /** [7-7] 검증기. updateName 이 위 @UpdateMethod 의 이름과 정확히 같아야 합니다. */
        @UpdateValidatorMethod(updateName = "changeAddress")
        void validateChangeAddress(String newAddress);
    }

    public static class OrderWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

        private boolean cancelled = false;
        private String cancelReason = null;
        private String stage = "RECEIVED";
        private int completedSteps = 0;
        private String address;

        // [7-6] 이 필드가 문제의 근원입니다.
        private int viewCount = 0;

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            this.address = req.address();

            stage = "PAYMENT";
            String paymentId = payment.charge(req.orderId(), req.amount());
            completedSteps++;

            stage = "WAITING_WAREHOUSE";
            log.info("[{}] 창고 처리 대기 시작 (최대 30분)", req.orderId());

            // [7-2] 시그널이 오거나 30분이 지나면 깨어납니다.
            //       조건 충족으로 깨어나면 SDK 가 남은 타이머를 TimerCanceled 로 정리합니다.
            boolean signalled = Workflow.await(Duration.ofMinutes(30), () -> cancelled);

            if (signalled) {
                stage = "CANCELLING";
                log.info("[{}] 취소 요청 수신: {}", req.orderId(), cancelReason);
                payment.refund(paymentId);
                stage = "CANCELLED";
                completedSteps++;

                // [7-8] 종료 직전 시그널 드레인
                Workflow.await(() -> Workflow.isEveryHandlerFinished());
                return req.orderId() + " CANCELLED (" + cancelReason + ")";
            }

            stage = "SHIPPING";
            shipping.requestShipment(req.orderId(), this.address);
            completedSteps++;
            stage = "SHIPPED";

            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            return req.orderId() + " COMPLETED";
        }

        // [7-2] 시그널 핸들러 — 필드 대입만. 액티비티 호출도, sleep 도 금지.
        @Override
        public void cancelRequested(String reason) {
            this.cancelled = true;
            this.cancelReason = reason;
        }

        // [7-5] 이상적인 Query — 본문이 return 문 하나
        @Override
        public String getStatus() {
            return stage;
        }

        @Override
        public OrderProgress getProgress() {
            return new OrderProgress(stage, completedSteps, 3, cancelled);
        }

        // [7-6] ⚠️ 이렇게 하면 안 됩니다
        @Override
        public String getStatusBad() {
            viewCount++;                        // ← 히스토리에 남지 않는 상태 변경
            return stage + " (조회 " + viewCount + "회)";
        }

        // [7-6] ⚠️ 이쪽은 SDK 가 즉시 막습니다
        @Override
        public String getStatusWorse() {
            payment.charge("query-side-effect", 1);
            return stage;
        }

        // [7-7] Validator — 읽고, 판단하고, 던지거나 조용히 리턴. 상태 변경 금지.
        @Override
        public void validateChangeAddress(String newAddress) {
            if (newAddress == null || newAddress.isBlank()) {
                throw new IllegalArgumentException("주소가 비어 있습니다");
            }
            if (newAddress.length() > 200) {
                throw new IllegalArgumentException("주소가 너무 깁니다: " + newAddress.length());
            }
            if ("SHIPPED".equals(stage) || "CANCELLED".equals(stage)) {
                throw new IllegalArgumentException("이미 " + stage + " 상태라 변경할 수 없습니다");
            }
        }

        // [7-7] Update — 상태를 바꾸고 결과를 반환합니다.
        @Override
        public String changeAddress(String newAddress) {
            String old = this.address;
            this.address = newAddress;
            log.info("배송지 변경 {} → {}", old, newAddress);
            return this.address;
        }
    }

    // ---------------------------------------------------------------------
    // [7-4] signalWithStart 용 장바구니 워크플로우
    // ---------------------------------------------------------------------

    public record Item(String sku, int qty) {
    }

    @WorkflowInterface
    public interface CartWorkflow {

        @WorkflowMethod
        List<Item> run(String customerId);

        @SignalMethod
        void addItem(Item item);

        @SignalMethod
        void checkout();

        @QueryMethod
        List<Item> getItems();
    }

    public static class CartWorkflowImpl implements CartWorkflow {

        private static final Logger log = Workflow.getLogger(CartWorkflowImpl.class);

        private final List<Item> items = new ArrayList<>();
        private boolean checkedOut = false;

        @Override
        public List<Item> run(String customerId) {
            log.info("장바구니 시작 customer={}", customerId);

            // 체크아웃 시그널이 오거나 2시간이 지나면 종료
            Workflow.await(Duration.ofHours(2), () -> checkedOut);

            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            log.info("장바구니 종료 items={}", items.size());
            return items;
        }

        @Override
        public void addItem(Item item) {
            items.add(item);
        }

        @Override
        public void checkout() {
            checkedOut = true;
        }

        @Override
        public List<Item> getItems() {
            return items;
        }
    }

    // ---------------------------------------------------------------------
    // [7-8] 시그널 유실 재현용 워크플로우
    // ---------------------------------------------------------------------

    @WorkflowInterface
    public interface NoteWorkflow {

        @WorkflowMethod
        String run(String orderId);

        @SignalMethod
        void addNote(String note);

        @SignalMethod
        void finish();

        @QueryMethod
        List<String> getNotes();
    }

    public static class NoteWorkflowImpl implements NoteWorkflow {

        /**
         * [7-8] 이 상수를 false 로 바꾸고 같은 시나리오를 돌려 비교하십시오.
         *
         *   DRAIN = false → 종료 직전 시그널이 히스토리에는 남지만 처리되지 않음.
         *                   히스토리 17개, getNotes() 조회 불가(이미 COMPLETED).
         *                   워커 로그: "Workflow ... finished while update/signal handlers are still running"
         *   DRAIN = true  → WorkflowTaskScheduled/Started/Completed 3개가 더 붙고(총 20개) 시그널이 처리됨.
         */
        private static final boolean DRAIN = true;

        private final List<String> notes = new ArrayList<>();
        private boolean done = false;

        @Override
        public String run(String orderId) {
            Workflow.await(Duration.ofMinutes(10), () -> done);

            if (DRAIN) {
                Workflow.await(() -> Workflow.isEveryHandlerFinished());
            }
            return orderId + " DONE";
        }

        @Override
        public void addNote(String note) {
            notes.add(note);
        }

        @Override
        public void finish() {
            done = true;
        }

        @Override
        public List<String> getNotes() {
            return notes;
        }
    }

    // ---------------------------------------------------------------------
    // [7-4] signalWithStart 호출 예제 (워커와 별개로 클라이언트 쪽 코드)
    // ---------------------------------------------------------------------

    /**
     * [7-4] 없으면 시작하고 있으면 시그널만 — 원자적 연산.
     *
     * 두 번 연달아 호출하면 히스토리에서 확인할 수 있습니다.
     *   1회차: WorkflowExecutionStarted(1) → WorkflowExecutionSignaled(2) → WorkflowTaskScheduled(3)
     *   2회차: WorkflowExecutionSignaled(6) 만 추가
     *
     * eventId 2 가 eventId 3(WorkflowTaskScheduled) 보다 앞이라는 것이 핵심입니다.
     * 워크플로우 코드가 한 줄도 실행되기 전에 시그널이 이미 큐에 들어가 있으므로 첫 아이템이 유실될 수 없습니다.
     */
    public static void addToCart(WorkflowClient client, String customerId, Item item) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(ORDER_TASK_QUEUE)
                .setWorkflowId("cart-" + customerId)
                .build();

        CartWorkflow cart = client.newWorkflowStub(CartWorkflow.class, options);

        BatchRequest batch = client.newSignalWithStartRequest();
        batch.add(cart::run, customerId);
        batch.add(cart::addItem, item);
        client.signalWithStart(batch);
    }

    // ---------------------------------------------------------------------
    // main — 워커 기동
    // ---------------------------------------------------------------------

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(ORDER_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                OrderWorkflowImpl.class,
                CartWorkflowImpl.class,
                NoteWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new PaymentActivityImpl(),
                new ShippingActivityImpl());

        factory.start();
        LOG.info("Worker started. {}", ORDER_TASK_QUEUE);

        // [7-4] 장바구니 데모를 코드로 돌려 보려면 아래 주석을 해제하십시오.
        //       두 번 실행해도 워크플로우는 하나만 생기고 아이템만 쌓입니다.
        // addToCart(client, "C-77", new Item("SKU-A", 2));
        // addToCart(client, "C-77", new Item("SKU-B", 1));
    }
}
