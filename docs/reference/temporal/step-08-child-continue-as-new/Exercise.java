package com.example.order.step08;

/*
 * Step 08 — 자식 워크플로우와 Continue-As-New / Exercise (6문제)
 *
 * 실행:
 *   ./gradlew run -PmainClass=com.example.order.step08.Exercise -Pargs=1
 *
 * 정답은 Solution.java. 먼저 직접 채워 보세요.
 * 이 스텝의 문제는 대부분 "코드를 채우고 CLI 로 숫자를 재는" 형태입니다.
 *
 *   temporal workflow describe -w <id> | grep History
 *   temporal workflow show     -w <id>
 *   temporal workflow terminate -w <id> --reason "..."
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
import io.temporal.workflow.CancellationScope;
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

public class Exercise {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku,
            int qty, long amount, String address) {
    }

    // =====================================================================
    // 공통 액티비티
    // =====================================================================

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
            return "TRK-" + Integer.toHexString((orderId + address).hashCode() & 0xffffff).toUpperCase();
        }

        @Override
        public void cancelShipment(String shipmentId) {
            log.info("⚠️ 보상 실행 — 배송 취소 {}", shipmentId);
        }
    }

    // =====================================================================
    // 문제 1 — 배송을 자식 워크플로우로 분리하고 이벤트 수 세기
    //
    // ChildShipmentWorkflow 를 자식으로 호출하도록 Ex1OrderWorkflowImpl 을 완성하십시오.
    //   - workflowId 는 "ex1-shipment"
    //   - ParentClosePolicy 는 ABANDON 으로 명시 (기본값에 기대지 말 것)
    //   - taskQueue 는 ORDER_TASK_QUEUE
    //
    // 실행 후 아래 표를 채우십시오.
    //
    //   | 대상            | History Length |
    //   |-----------------|----------------|
    //   | ex1-order       |                |
    //   | ex1-shipment    |                |
    //   | 합계            |                |
    //
    // 그리고 답하십시오: 배송 로직을 부모 안에 인라인으로 넣었다면 부모 히스토리는
    // 몇 개가 되었겠습니까? 자식으로 쪼갠 것의 이득은 "총 이벤트 감소" 입니까,
    // 아니면 다른 것입니까?
    // =====================================================================

    @WorkflowInterface
    public interface ChildShipmentWorkflow {

        @WorkflowMethod
        String deliver(String orderId, String address);

        @QueryMethod
        String getStage();
    }

    public static class ChildShipmentWorkflowImpl implements ChildShipmentWorkflow {

        private String stage = "PICKING";

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .build());

        @Override
        public String deliver(String orderId, String address) {
            stage = "REQUESTING";
            String trk = shipping.requestShipment(orderId, address);

            stage = "IN_TRANSIT";
            Workflow.sleep(Duration.ofSeconds(5));

            stage = "DELIVERED";
            return trk;
        }

        @Override
        public String getStage() {
            return stage;
        }
    }

    @WorkflowInterface
    public interface Ex1OrderWorkflow {
        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class Ex1OrderWorkflowImpl implements Ex1OrderWorkflow {

        @Override
        public String processOrder(OrderRequest req) {
            // TODO: 여기에 작성 — 자식 워크플로우 스텁을 만들고 deliver 를 호출

            return req.orderId() + " SHIPPED ";
        }
    }

    // =====================================================================
    // 문제 2 — 창고 3곳 병렬 배송
    //
    // Async.function 으로 자식 3개를 병렬 실행하고 전부 끝날 때까지 기다린 뒤
    // 운송장 번호 리스트를 반환하십시오.
    //   - workflowId 는 "ex2-shipment-{warehouse}"
    //   - 자식이 시작되었음을 확인하는 패턴을 반드시 넣을 것
    //
    // 확인: temporal workflow show -w ex2-order
    //   ChildWorkflowExecutionCompleted 3개의 순서가 시작 순서와 다른지 보십시오.
    //   그런데도 결과 리스트 순서는 항상 같습니다. 왜 그렇습니까?
    // =====================================================================

    @WorkflowInterface
    public interface Ex2OrderWorkflow {
        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class Ex2OrderWorkflowImpl implements Ex2OrderWorkflow {

        @Override
        public String processOrder(OrderRequest req) {
            List<String> warehouses = List.of("WH-SEOUL", "WH-BUSAN", "WH-DAEGU");
            List<Promise<String>> results = new ArrayList<>();

            for (String warehouse : warehouses) {
                // TODO: 여기에 작성 — 자식 스텁 생성
                // TODO: 여기에 작성 — Async.function 으로 비동기 시작
                // TODO: 여기에 작성 — 자식이 시작되었음을 확인
                // TODO: 여기에 작성 — results 에 추가
            }

            // TODO: 여기에 작성 — 셋 다 끝날 때까지 대기하고 운송장 리스트 만들기

            return req.orderId() + " SHIPPED ";
        }
    }

    // =====================================================================
    // 문제 3 — ParentClosePolicy 3종 관찰
    //
    // 아래 POLICY 상수만 바꿔 가며 세 번 실행하고 표를 채우십시오.
    // 코드는 채울 것이 없습니다. 관찰이 문제입니다.
    //
    // 절차 (각 정책마다):
    //   1) POLICY 를 바꾸고 워커 재시작
    //   2) temporal workflow start --task-queue ORDER_TASK_QUEUE --type Ex3OrderWorkflow \
    //        --workflow-id ex3-order --input '"3001"'
    //   3) temporal workflow describe -w ex3-order       ← Pending Children 확인
    //   4) temporal workflow terminate -w ex3-order --reason "테스트"
    //   5) temporal workflow describe -w ex3-shipment    ← 자식 Status 기록
    //   6) 워커 로그에 "보상 실행 — 배송 취소" 가 찍혔는지 확인
    //
    //   | ParentClosePolicy | 자식 Status | 종료 이벤트 reason | 보상 액티비티 실행? |
    //   |-------------------|-------------|--------------------|---------------------|
    //   | TERMINATE         |             |                    |                     |
    //   | ABANDON           |             |                    |                     |
    //   | REQUEST_CANCEL    |             |                    |                     |
    //
    // 종료 이벤트 reason 확인:
    //   temporal workflow show -w ex3-shipment --output json | jq '.events[-1]'
    //
    // 답하십시오: 배송처럼 실물이 움직이는 자식에는 어느 정책을 써야 합니까?
    //             보상 로직이 있는 자식에는 어느 정책이 맞습니까?
    // =====================================================================

    /** ← 이 값을 TERMINATE / ABANDON / REQUEST_CANCEL 로 바꿔 가며 실행하십시오. */
    private static final ParentClosePolicy POLICY =
            ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE;

    @WorkflowInterface
    public interface Ex3ShipmentWorkflow {
        @WorkflowMethod
        String deliver(String orderId);
    }

    public static class Ex3ShipmentWorkflowImpl implements Ex3ShipmentWorkflow {

        private static final Logger log = Workflow.getLogger(Ex3ShipmentWorkflowImpl.class);

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .build());

        @Override
        public String deliver(String orderId) {
            String trk = shipping.requestShipment(orderId, "테스트 주소");

            try {
                // 3분 대기 — 이 사이에 부모를 terminate 합니다
                Workflow.sleep(Duration.ofMinutes(3));
            } catch (io.temporal.failure.CanceledFailure e) {
                // REQUEST_CANCEL 일 때만 여기로 들어옵니다.
                // 취소된 스코프에서는 새 액티비티를 못 부르므로 detached 스코프가 필요합니다.
                CancellationScope.newDetachedCancellationScope(
                        () -> shipping.cancelShipment(trk)).run();
                log.info("[{}] 취소 요청 수신 → 보상 완료", orderId);
                throw e;
            }

            return trk;
        }
    }

    @WorkflowInterface
    public interface Ex3OrderWorkflow {
        @WorkflowMethod
        String processOrder(String orderId);
    }

    public static class Ex3OrderWorkflowImpl implements Ex3OrderWorkflow {

        @Override
        public String processOrder(String orderId) {
            Ex3ShipmentWorkflow child = Workflow.newChildWorkflowStub(
                    Ex3ShipmentWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId("ex3-shipment")
                            .setTaskQueue(ORDER_TASK_QUEUE)
                            .setParentClosePolicy(POLICY)
                            .build());

            return child.deliver(orderId);
        }
    }

    // =====================================================================
    // 문제 4 — 회당 이벤트 수 구하기
    //
    // 아래 두 워크플로우를 각각 띄우고 History Length 를 100틱 단위로 기록해
    // "루프 1회당 몇 개의 이벤트가 쌓이는지" 를 계산하십시오.
    //
    //   temporal workflow start --task-queue ORDER_TASK_QUEUE --type Ex4SleepOnlyWorkflow \
    //     --workflow-id ex4-sleep --input '"4001"'
    //   temporal workflow start --task-queue ORDER_TASK_QUEUE --type Ex4WithActivityWorkflow \
    //     --workflow-id ex4-activity --input '"4002"'
    //
    //   watch -n 20 'temporal workflow describe -w ex4-sleep | grep History'
    //
    //   | 틱  | ex4-sleep History Length | ex4-activity History Length |
    //   |-----|--------------------------|-----------------------------|
    //   | 0   |                          |                             |
    //   | 100 |                          |                             |
    //   | 200 |                          |                             |
    //   | 300 |                          |                             |
    //
    //   회당 이벤트 수(sleep only)    = ______
    //   회당 이벤트 수(액티비티 포함) = ______
    //
    // 답하십시오: 51,200 이벤트 한계에 각각 몇 회 반복에서 도달합니까?
    //             (51,200 ÷ 회당 이벤트 수)
    //
    // 다 재고 나면 반드시 terminate 하십시오.
    // =====================================================================

    @WorkflowInterface
    public interface Ex4SleepOnlyWorkflow {
        @WorkflowMethod
        String run(String orderId);

        @QueryMethod
        int getTick();
    }

    public static class Ex4SleepOnlyWorkflowImpl implements Ex4SleepOnlyWorkflow {

        private static final Logger log = Workflow.getLogger(Ex4SleepOnlyWorkflowImpl.class);
        private int tick = 0;

        @Override
        public String run(String orderId) {
            while (true) {
                Workflow.sleep(Duration.ofSeconds(1));
                tick++;
                if (tick % 100 == 0) {
                    log.info("[{}] tick={} historyLength={}",
                            orderId, tick, Workflow.getInfo().getHistoryLength());
                }
            }
        }

        @Override
        public int getTick() {
            return tick;
        }
    }

    @WorkflowInterface
    public interface Ex4WithActivityWorkflow {
        @WorkflowMethod
        String run(String orderId);

        @QueryMethod
        int getTick();
    }

    public static class Ex4WithActivityWorkflowImpl implements Ex4WithActivityWorkflow {

        private static final Logger log = Workflow.getLogger(Ex4WithActivityWorkflowImpl.class);

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        private int tick = 0;

        @Override
        public String run(String orderId) {
            while (true) {
                Workflow.sleep(Duration.ofSeconds(1));
                shipping.requestShipment(orderId, "tick-" + tick);
                tick++;
                if (tick % 100 == 0) {
                    log.info("[{}] tick={} historyLength={}",
                            orderId, tick, Workflow.getInfo().getHistoryLength());
                }
            }
        }

        @Override
        public int getTick() {
            return tick;
        }
    }

    // =====================================================================
    // 문제 5 — continueAsNew 넣기
    //
    // 문제 4 의 Ex4WithActivityWorkflow 에 continueAsNew 를 넣으십시오.
    //   - 목표: 각 Run 의 히스토리를 8,000 이벤트 이하로 유지
    //   - 문제 4 에서 구한 회당 이벤트 수로 TICKS_PER_RUN 을 역산할 것
    //   - tick 카운터가 Run 을 넘어 이어져야 함
    //
    // before/after 를 비교하십시오.
    //
    //   | | continueAsNew 없음 | continueAsNew 있음 |
    //   |---|---|---|
    //   | 3,000틱 시점 History Length | | |
    //   | Run 개수 | | |
    // =====================================================================

    @WorkflowInterface
    public interface Ex5PollingWorkflow {

        @WorkflowMethod
        String poll(String orderId, int carriedTick);

        @QueryMethod
        int getTick();
    }

    public static class Ex5PollingWorkflowImpl implements Ex5PollingWorkflow {

        // TODO: 여기에 작성 — TICKS_PER_RUN 상수 (문제 4 의 회당 이벤트 수로 역산)
        private static final int TICKS_PER_RUN = 0;

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        private int tick;

        @Override
        public String poll(String orderId, int carriedTick) {
            this.tick = carriedTick;

            // TODO: 여기에 작성 — TICKS_PER_RUN 만큼 루프

            // TODO: 여기에 작성 — continueAsNew 로 새 Run 시작 (tick 을 이월)

            throw new IllegalStateException("도달할 수 없음");
        }

        @Override
        public int getTick() {
            return tick;
        }
    }

    // =====================================================================
    // 문제 6 — continueAsNew 직전 시그널 유실 재현 및 수정
    //
    // 아래 워크플로우는 드레인도 이월도 없습니다.
    // TICKS_PER_RUN 을 5 로 줄여 두어 continueAsNew 가 5초마다 일어납니다.
    //
    // (a) 그대로 실행하고 addTask 시그널을 여러 번 보내십시오.
    //       for i in 1 2 3 4 5 6 7 8 9 10; do
    //         temporal workflow signal -w ex6-poll --name addTask --input "\"task-$i\""
    //         sleep 1
    //       done
    //     temporal workflow query -w ex6-poll --type getPendingTasks
    //     → 몇 개가 남아 있습니까? 왜 전부가 아닙니까?
    //
    // (b) 워크플로우 Status 를 확인하십시오. 이상해 보입니까?
    //     temporal workflow describe -w ex6-poll
    //
    // (c) 두 가지를 고치십시오.
    //     1. 미처리 핸들러 드레인
    //     2. pendingTasks 를 인자로 이월
    //     둘 중 하나만 하면 어떤 케이스가 여전히 유실됩니까?
    // =====================================================================

    @WorkflowInterface
    public interface Ex6PollingWorkflow {

        // TODO: 여기에 작성 — 이월할 상태를 인자에 추가
        @WorkflowMethod
        String poll(String orderId, int carriedTick);

        @SignalMethod
        void addTask(String task);

        @QueryMethod
        List<String> getPendingTasks();

        @QueryMethod
        int getTick();
    }

    public static class Ex6PollingWorkflowImpl implements Ex6PollingWorkflow {

        private static final int TICKS_PER_RUN = 5;

        private int tick;
        private final List<String> pendingTasks = new ArrayList<>();

        @Override
        public String poll(String orderId, int carriedTick) {
            this.tick = carriedTick;

            // TODO: 여기에 작성 — 이월된 작업을 pendingTasks 에 복원

            for (int i = 0; i < TICKS_PER_RUN; i++) {
                Workflow.sleep(Duration.ofSeconds(1));
                tick++;
            }

            // TODO: 여기에 작성 — (1) 드레인

            Ex6PollingWorkflow next = Workflow.newContinueAsNewStub(Ex6PollingWorkflow.class);
            // TODO: 여기에 작성 — (2) pendingTasks 를 인자로 이월
            next.poll(orderId, tick);

            throw new IllegalStateException("도달할 수 없음");
        }

        @Override
        public void addTask(String task) {
            pendingTasks.add(task);
        }

        @Override
        public List<String> getPendingTasks() {
            return pendingTasks;
        }

        @Override
        public int getTick() {
            return tick;
        }
    }

    // =====================================================================
    // main
    // =====================================================================

    public static void main(String[] args) {
        String problem = args.length > 0 ? args[0] : "all";

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(ORDER_TASK_QUEUE);

        switch (problem) {
            case "1" -> worker.registerWorkflowImplementationTypes(
                    Ex1OrderWorkflowImpl.class, ChildShipmentWorkflowImpl.class);
            case "2" -> worker.registerWorkflowImplementationTypes(
                    Ex2OrderWorkflowImpl.class, ChildShipmentWorkflowImpl.class);
            case "3" -> worker.registerWorkflowImplementationTypes(
                    Ex3OrderWorkflowImpl.class, Ex3ShipmentWorkflowImpl.class);
            case "4" -> worker.registerWorkflowImplementationTypes(
                    Ex4SleepOnlyWorkflowImpl.class, Ex4WithActivityWorkflowImpl.class);
            case "5" -> worker.registerWorkflowImplementationTypes(Ex5PollingWorkflowImpl.class);
            case "6" -> worker.registerWorkflowImplementationTypes(Ex6PollingWorkflowImpl.class);
            default -> worker.registerWorkflowImplementationTypes(
                    Ex1OrderWorkflowImpl.class, Ex2OrderWorkflowImpl.class,
                    ChildShipmentWorkflowImpl.class, Ex3OrderWorkflowImpl.class,
                    Ex3ShipmentWorkflowImpl.class, Ex4SleepOnlyWorkflowImpl.class,
                    Ex4WithActivityWorkflowImpl.class, Ex5PollingWorkflowImpl.class,
                    Ex6PollingWorkflowImpl.class);
        }

        worker.registerActivitiesImplementations(new ShippingActivityImpl());
        factory.start();
        System.out.println("Exercise worker started. problem=" + problem + " POLICY=" + POLICY);
    }
}
