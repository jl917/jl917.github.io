package com.example.order.step07;

/*
 * Step 07 — Signal · Query · Update / Exercise (6문제)
 *
 * 실행:
 *   ./gradlew run -PmainClass=com.example.order.step07.Exercise -Pargs=1     ← 문제 번호
 *
 * 정답은 Solution.java 에 있습니다. 먼저 직접 채워 보세요.
 * 각 문제를 푼 뒤에는 반드시 CLI 로 히스토리를 확인하십시오.
 *
 *   temporal workflow show     -w <id>
 *   temporal workflow describe -w <id>
 *   temporal workflow query    -w <id> --type <queryName>
 *
 * 환경: Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Exercise {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record Item(String sku, int qty) {
    }

    // =====================================================================
    // 문제 1 — pause / resume 시그널 쌍
    //
    // 3단계(결제 → 재고 → 배송) 워크플로우를 만들되, 각 단계 사이에서
    // pauseRequested 시그널을 받으면 멈추고 resumeRequested 를 받으면 이어서 진행합니다.
    // getStage() Query 로 현재 단계와 일시정지 여부를 확인할 수 있어야 합니다.
    //
    // 확인:
    //   temporal workflow signal -w ex1-order --name pauseRequested
    //   temporal workflow query  -w ex1-order --type getStage      → "INVENTORY (PAUSED)"
    //   temporal workflow signal -w ex1-order --name resumeRequested
    // =====================================================================

    @WorkflowInterface
    public interface PausableWorkflow {

        @WorkflowMethod
        String run(String orderId);

        // TODO: 여기에 작성 — pauseRequested 시그널 메서드 선언

        // TODO: 여기에 작성 — resumeRequested 시그널 메서드 선언

        @QueryMethod
        String getStage();
    }

    public static class PausableWorkflowImpl implements PausableWorkflow {

        private String stage = "RECEIVED";
        private boolean paused = false;

        @Override
        public String run(String orderId) {
            String[] stages = {"PAYMENT", "INVENTORY", "SHIPPING"};

            for (String s : stages) {
                // TODO: 여기에 작성 — paused 가 풀릴 때까지 대기

                stage = s;
                Workflow.sleep(Duration.ofSeconds(5));   // 단계 처리를 흉내
            }

            stage = "DONE";
            return orderId + " DONE";
        }

        // TODO: 여기에 작성 — pauseRequested 구현 (한 줄)

        // TODO: 여기에 작성 — resumeRequested 구현 (한 줄)

        @Override
        public String getStage() {
            // TODO: 여기에 작성 — paused 이면 "STAGE (PAUSED)", 아니면 "STAGE"
            //       ⚠️ 여기서 필드를 바꾸면 안 됩니다.
            return null;
        }
    }

    // =====================================================================
    // 문제 2 — 진행률 Query
    //
    // 완료 단계 수 / 전체 단계 수를 "3/5 (60%)" 형태 문자열로 반환하는 Query 를 만드십시오.
    // 계산은 지역 변수만 사용하고, this. 로 시작하는 대입은 절대 하지 마십시오.
    // =====================================================================

    @WorkflowInterface
    public interface ProgressWorkflow {

        @WorkflowMethod
        String run(String orderId);

        @QueryMethod
        String getProgressText();

        @SignalMethod
        void stepDone();
    }

    public static class ProgressWorkflowImpl implements ProgressWorkflow {

        private static final int TOTAL = 5;
        private int completed = 0;

        @Override
        public String run(String orderId) {
            Workflow.await(Duration.ofMinutes(30), () -> completed >= TOTAL);
            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            return orderId + " DONE " + completed + "/" + TOTAL;
        }

        @Override
        public void stepDone() {
            completed++;
        }

        @Override
        public String getProgressText() {
            // TODO: 여기에 작성 — "3/5 (60%)" 형태로 반환
            return null;
        }
    }

    // =====================================================================
    // 문제 3 — 잘못된 Query 를 진단하고 고치기
    //
    // 아래 코드는 컴파일도 되고 실행도 됩니다. 에러가 나지 않는다는 것이 문제입니다.
    //
    // (a) 워커를 띄우고 아래 워크플로우를 시작한 뒤
    //     temporal workflow query -w ex3-order --type getSummary 를 3번 실행하십시오.
    // (b) 워커를 재시작하고 같은 Query 를 다시 실행하십시오. 값이 어떻게 되나요?
    // (c) 왜 그런지 리플레이 관점에서 설명하고, 코드를 고치십시오.
    // (d) "조회 횟수를 정말 기록해야 한다" 면 Query 대신 무엇을 써야 합니까?
    // =====================================================================

    @WorkflowInterface
    public interface BadQueryWorkflow {

        @WorkflowMethod
        String run(String orderId);

        @QueryMethod
        String getSummary();

        @SignalMethod
        void finish();
    }

    public static class BadQueryWorkflowImpl implements BadQueryWorkflow {

        private String stage = "RUNNING";
        private int queryCount = 0;
        private final List<String> auditLog = new ArrayList<>();

        @Override
        public String run(String orderId) {
            Workflow.await(Duration.ofMinutes(30), () -> "DONE".equals(stage));
            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            return orderId + " " + stage;
        }

        @Override
        public void finish() {
            stage = "DONE";
        }

        @Override
        public String getSummary() {
            // ⚠️ 문제의 코드 — 두 줄 모두 잘못되었습니다.
            queryCount++;
            auditLog.add("queried at " + Workflow.currentTimeMillis());

            return stage + " / 조회 " + queryCount + "회";
        }

        // TODO: 여기에 작성 — 위 getSummary 를 올바르게 고친 버전
        //       (인터페이스에 @QueryMethod 를 하나 더 추가하고 여기에 구현)
    }

    // =====================================================================
    // 문제 4 — signalWithStart 로 장바구니 만들기
    //
    // 아래 addToCartNaive 는 경쟁 조건이 있습니다.
    // 스레드 두 개가 동시에 호출하면 한쪽 아이템이 유실됩니다.
    // addToCart 를 signalWithStart 로 다시 구현하십시오.
    //
    // 확인:
    //   main 의 raceTest() 를 실행한 뒤
    //   temporal workflow query -w cart-C99 --type getItems
    //   → naive 버전은 아이템이 1개, signalWithStart 버전은 2개여야 합니다.
    //
    //   그리고 히스토리에서 eventId 2 가 WorkflowExecutionSignaled 인지 확인하십시오.
    //   temporal workflow show -w cart-C99
    // =====================================================================

    @WorkflowInterface
    public interface CartWorkflow {

        @WorkflowMethod
        List<Item> run(String customerId);

        @SignalMethod
        void addItem(Item item);

        @QueryMethod
        List<Item> getItems();
    }

    public static class CartWorkflowImpl implements CartWorkflow {

        private final List<Item> items = new ArrayList<>();

        @Override
        public List<Item> run(String customerId) {
            Workflow.await(Duration.ofMinutes(10), () -> items.size() >= 10);
            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            return items;
        }

        @Override
        public void addItem(Item item) {
            items.add(item);
        }

        @Override
        public List<Item> getItems() {
            return items;
        }
    }

    /** ⚠️ 경쟁 조건이 있는 버전. 참고용으로만 두었습니다. */
    public static void addToCartNaive(WorkflowClient client, String customerId, Item item) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(ORDER_TASK_QUEUE)
                .setWorkflowId("cart-" + customerId)
                .build();
        try {
            client.newWorkflowStub(CartWorkflow.class, "cart-" + customerId).addItem(item);
        } catch (Exception e) {
            CartWorkflow cw = client.newWorkflowStub(CartWorkflow.class, options);
            WorkflowClient.start(cw::run, customerId);
            cw.addItem(item);
        }
    }

    public static void addToCart(WorkflowClient client, String customerId, Item item) {
        // TODO: 여기에 작성 — BatchRequest + client.signalWithStart 로 구현
    }

    // =====================================================================
    // 문제 5 — Update + Validator 로 수량 변경
    //
    // changeQty Update 를 구현하십시오.
    //   - 1 미만이면 거절
    //   - 재고(STOCK = 10)를 초과하면 거절
    //   - 이미 SHIPPED 면 거절
    //   - 통과하면 qty 를 바꾸고 최종 qty 를 반환
    //
    // ⚠️ @UpdateValidatorMethod(updateName = "changeQty") 의 이름을 오타 내면
    //    검증기가 조용히 무시되고 모든 Update 가 통과합니다. 반드시 확인하십시오.
    //
    // 확인:
    //   temporal workflow describe -w ex5-order        → History Length 기록
    //   temporal workflow update -w ex5-order --name changeQty --input '99'   → 거절
    //   temporal workflow describe -w ex5-order        → History Length 가 그대로여야 함
    // =====================================================================

    @WorkflowInterface
    public interface QtyWorkflow {

        @WorkflowMethod
        String run(String orderId, int qty);

        @UpdateMethod
        int changeQty(int newQty);

        @UpdateValidatorMethod(updateName = "changeQty")
        void validateChangeQty(int newQty);

        @QueryMethod
        int getQty();
    }

    public static class QtyWorkflowImpl implements QtyWorkflow {

        private static final int STOCK = 10;

        private int qty;
        private String stage = "RECEIVED";

        @Override
        public String run(String orderId, int initialQty) {
            this.qty = initialQty;
            stage = "WAITING";
            Workflow.await(Duration.ofMinutes(30), () -> "SHIPPED".equals(stage));
            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            return orderId + " qty=" + qty;
        }

        @Override
        public void validateChangeQty(int newQty) {
            // TODO: 여기에 작성 — 세 가지 거절 조건. 상태는 절대 바꾸지 마십시오.
        }

        @Override
        public int changeQty(int newQty) {
            // TODO: 여기에 작성 — qty 를 바꾸고 최종값 반환
            return 0;
        }

        @Override
        public int getQty() {
            return qty;
        }
    }

    // =====================================================================
    // 문제 6 — 종료 직전 시그널 유실 막기
    //
    // 아래 워크플로우는 finish 시그널을 받으면 즉시 종료합니다.
    // finish 와 거의 동시에 addNote 시그널을 보내면 그 노트는 조용히 사라집니다.
    //
    // (a) 그대로 실행해서 유실을 재현하고 워커 로그의 WARN 을 확인하십시오.
    // (b) 드레인 패턴으로 고치십시오.
    // (c) 고치기 전후의 History Length 차이를 기록하십시오. 몇 개가 늘었습니까?
    // =====================================================================

    @WorkflowInterface
    public interface DrainWorkflow {

        @WorkflowMethod
        String run(String orderId);

        @SignalMethod
        void addNote(String note);

        @SignalMethod
        void finish();

        @QueryMethod
        List<String> getNotes();
    }

    public static class DrainWorkflowImpl implements DrainWorkflow {

        private final List<String> notes = new ArrayList<>();
        private boolean done = false;

        @Override
        public String run(String orderId) {
            Workflow.await(Duration.ofMinutes(10), () -> done);

            // TODO: 여기에 작성 — 미처리 핸들러 드레인

            return orderId + " DONE notes=" + notes.size();
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

    // =====================================================================
    // 더미 액티비티 (워커 등록용)
    // =====================================================================

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod
        void notifyCustomer(String orderId, String message);
    }

    public static class NotificationActivityImpl implements NotificationActivity {
        @Override
        public void notifyCustomer(String orderId, String message) {
            System.out.println("[notify] " + orderId + " : " + message);
        }
    }

    // =====================================================================
    // main — 문제 번호를 인자로 받아 해당 워커를 띄웁니다
    // =====================================================================

    public static void main(String[] args) {
        String problem = args.length > 0 ? args[0] : "all";

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(ORDER_TASK_QUEUE);

        switch (problem) {
            case "1" -> worker.registerWorkflowImplementationTypes(PausableWorkflowImpl.class);
            case "2" -> worker.registerWorkflowImplementationTypes(ProgressWorkflowImpl.class);
            case "3" -> worker.registerWorkflowImplementationTypes(BadQueryWorkflowImpl.class);
            case "4" -> worker.registerWorkflowImplementationTypes(CartWorkflowImpl.class);
            case "5" -> worker.registerWorkflowImplementationTypes(QtyWorkflowImpl.class);
            case "6" -> worker.registerWorkflowImplementationTypes(DrainWorkflowImpl.class);
            default -> worker.registerWorkflowImplementationTypes(
                    PausableWorkflowImpl.class, ProgressWorkflowImpl.class,
                    BadQueryWorkflowImpl.class, CartWorkflowImpl.class,
                    QtyWorkflowImpl.class, DrainWorkflowImpl.class);
        }

        worker.registerActivitiesImplementations(new NotificationActivityImpl());
        factory.start();
        System.out.println("Exercise worker started. problem=" + problem);
    }

    /**
     * 문제 4 의 경쟁 조건 재현용. 스레드 두 개가 동시에 첫 아이템을 담습니다.
     * naive 버전이면 한쪽이 유실되고, signalWithStart 버전이면 둘 다 들어갑니다.
     */
    public static void raceTest(WorkflowClient client) throws InterruptedException {
        Thread t1 = new Thread(() -> addToCart(client, "C99", new Item("SKU-A", 1)));
        Thread t2 = new Thread(() -> addToCart(client, "C99", new Item("SKU-B", 1)));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        System.out.println("raceTest done. temporal workflow query -w cart-C99 --type getItems");
    }
}
