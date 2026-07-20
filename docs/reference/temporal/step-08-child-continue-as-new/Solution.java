package com.example.order.step08;

/*
 * Step 08 — 자식 워크플로우와 Continue-As-New / Solution (6문제 정답 + 해설)
 *
 * 실행:
 *   ./gradlew run -PmainClass=com.example.order.step08.Solution -Pargs=1
 *
 * 문제를 풀어 본 "뒤에" 여십시오.
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

public class Solution {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku,
            int qty, long amount, String address) {
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
            return "TRK-" + Integer.toHexString((orderId + address).hashCode() & 0xffffff).toUpperCase();
        }

        @Override
        public void cancelShipment(String shipmentId) {
            log.info("⚠️ 보상 실행 — 배송 취소 {}", shipmentId);
        }
    }

    // =====================================================================
    // 정답 1 — 자식 워크플로우 분리와 이벤트 수
    //
    // 측정 결과:
    //
    //   | 대상          | History Length |
    //   |---------------|----------------|
    //   | ex1-order     | 20             |
    //   | ex1-shipment  | 22             |
    //   | 합계          | 42             |
    //
    // 배송 로직을 부모에 인라인으로 넣었다면 부모 하나가 약 38개가 되었을 것입니다
    // (자식의 22개에서 WorkflowExecutionStarted/Completed 등 자식 고유 오버헤드 4개를 빼고,
    //  부모의 자식 관련 이벤트 3개도 없어지므로).
    //
    // 즉 자식으로 쪼개면 총 이벤트는 42 > 38 로 오히려 "늘어납니다".
    // 그런데도 쪼개는 이유는 총량이 아니라 "하나의 히스토리 크기" 때문입니다.
    //
    //   인라인: 히스토리 1개 × 38 이벤트
    //   자식:   히스토리 2개 × (20, 22) 이벤트
    //
    // 8-5 의 한계값(51,200 / 50MB)은 "실행 하나당" 적용됩니다. 총합이 아닙니다.
    // 주문 하나가 배송 100건을 만드는 경우를 생각해 보십시오.
    //
    //   인라인: 히스토리 1개 × 3,800 이벤트   ← 배송 1,400건이면 한계 초과
    //   자식:   히스토리 101개 × (평균 22)     ← 한계 없음
    //
    // 자식 워크플로우의 진짜 효용은 이것입니다. 그 외에
    //   - shipment-2001 만 따로 조회/시그널/취소 가능
    //   - 배송팀이 별도 태스크 큐로 소유하고 별도 배포 가능
    //   - 며칠짜리 타이머가 자연스러움 (액티비티였다면 heartbeat 지옥)
    //
    // 반대로 이 다섯 가지 중 하나도 해당하지 않으면 액티비티를 쓰십시오.
    // 자식 1,000개는 서버에 워크플로우 실행 1,001개를 만들고, 보존 기간 동안
    // 그만큼의 스토리지와 조회 부하를 발생시킵니다.
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
            // 정답 1 — ParentClosePolicy 를 명시합니다.
            // 배송은 실물이 움직이므로 부모가 죽어도 끝까지 가야 합니다 → ABANDON.
            ChildShipmentWorkflow child = Workflow.newChildWorkflowStub(
                    ChildShipmentWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId("ex1-shipment")
                            .setTaskQueue(ORDER_TASK_QUEUE)
                            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                            .setWorkflowExecutionTimeout(Duration.ofDays(7))
                            .build());

            String trk = child.deliver(req.orderId(), req.address());
            return req.orderId() + " SHIPPED " + trk;
        }
    }

    // =====================================================================
    // 정답 2 — 병렬 자식 실행
    //
    // Async.function 이 Promise 를 즉시 반환하므로 루프가 막히지 않고 자식 3개가
    // 거의 동시에 시작됩니다. 히스토리를 보면:
    //
    //   11 StartChildWorkflowExecutionInitiated  (WH-SEOUL)
    //   12 ChildWorkflowExecutionStarted         (WH-SEOUL)
    //   16 StartChildWorkflowExecutionInitiated  (WH-BUSAN)
    //   17 ChildWorkflowExecutionStarted         (WH-BUSAN)
    //   ...
    //   26 ChildWorkflowExecutionCompleted       (WH-BUSAN)   ← 완료 순서가 다름
    //   27 ChildWorkflowExecutionCompleted       (WH-SEOUL)
    //   28 ChildWorkflowExecutionCompleted       (WH-DAEGU)
    //
    // 완료 순서(BUSAN → SEOUL → DAEGU)가 시작 순서와 다른 것이 진짜 병렬 실행의 증거입니다.
    //
    // "그런데도 결과 리스트 순서가 항상 같은 이유" 는 결과를 results 리스트의 인덱스로
    // 꺼내기 때문입니다. Promise.get() 은 그 Promise 에 해당하는 자식의 결과만 돌려주므로,
    // 어느 자식이 먼저 끝났는지와 무관하게 리스트 순서는 시작 순서 그대로입니다.
    // 이것이 리플레이 결정성이 유지되는 이유이기도 합니다. 만약 "먼저 끝난 순서대로"
    // 결과를 모았다면 리플레이마다 순서가 달라져 NonDeterministicException 이 났을 것입니다.
    // (Promise.anyOf 를 쓸 때 이 함정이 실제로 나타납니다.)
    //
    // Workflow.getWorkflowExecution(child).get() 이 필요한 이유:
    //   Async.function 은 "시작 요청" 만 하고 반환합니다. 이 시점에 자식이 정말 시작됐는지,
    //   RunId 가 무엇인지 모릅니다. getWorkflowExecution(...).get() 은
    //   ChildWorkflowExecutionStarted 이벤트가 히스토리에 기록될 때까지 기다립니다.
    //   덕분에 자식 ID 중복(WorkflowExecutionAlreadyStartedException) 같은 오류가
    //   "루프 안 그 자리에서" 드러납니다. 이 줄이 없으면 오류가 Promise.allOf(...).get()
    //   시점에야 나타나 어느 자식이 문제였는지 추적하기 어려워집니다.
    //
    // 자식이 많을 때는 50~100개씩 배치로 끊으십시오. 1,000개를 한꺼번에 던지면
    // 부모 히스토리에 5,000개 이상이 쌓이고 서버에 동시 시작 요청이 폭주합니다.
    // =====================================================================

    @WorkflowInterface
    public interface Ex2OrderWorkflow {
        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    public static class Ex2OrderWorkflowImpl implements Ex2OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Ex2OrderWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            List<String> warehouses = List.of("WH-SEOUL", "WH-BUSAN", "WH-DAEGU");
            List<Promise<String>> results = new ArrayList<>();

            for (String warehouse : warehouses) {
                ChildShipmentWorkflow child = Workflow.newChildWorkflowStub(
                        ChildShipmentWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("ex2-shipment-" + warehouse)
                                .setTaskQueue(ORDER_TASK_QUEUE)
                                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                                .build());

                Promise<String> promise = Async.function(child::deliver, req.orderId(), warehouse);

                // 자식이 시작되었음(RunId 확정)을 확인
                Workflow.getWorkflowExecution(child).get();

                results.add(promise);
            }

            Promise.allOf(results).get();

            List<String> trackingNos = results.stream().map(Promise::get).toList();
            log.info("[{}] 병렬 배송 완료 {}", req.orderId(), trackingNos);
            return req.orderId() + " SHIPPED " + trackingNos;
        }
    }

    // =====================================================================
    // 정답 3 — ParentClosePolicy 관찰 결과
    //
    //   | ParentClosePolicy | 자식 Status | 종료 이벤트 reason        | 보상 액티비티 |
    //   |-------------------|-------------|---------------------------|---------------|
    //   | TERMINATE (기본)  | TERMINATED  | "by parent close policy"  | 실행 안 됨    |
    //   | ABANDON           | RUNNING     | (종료 이벤트 없음)         | 해당 없음     |
    //   | REQUEST_CANCEL    | CANCELED    | WorkflowExecutionCanceled | 실행됨        |
    //
    // TERMINATE 가 왜 위험한가:
    //   부모를 terminate 하면 자식이 즉사합니다. 자식의 자식(손자)까지 재귀적으로 죽습니다.
    //   워크플로우 코드에 아무 기회도 주지 않으므로 보상 로직이 있어도 실행되지 않습니다.
    //   "주문 워크플로우가 이상해서 지웠더니 배송 200건이 같이 사라졌다" 가 실제 사고 유형입니다.
    //   그런데 이것이 기본값입니다. ChildWorkflowOptions 에 아무것도 안 쓰면 TERMINATE 입니다.
    //
    // 배송처럼 실물이 움직이는 자식 → ABANDON
    //   부모의 운명과 무관하게 끝까지 가야 합니다. 이미 택배사에 인계된 물건은
    //   워크플로우를 죽인다고 되돌아오지 않습니다.
    //
    // 보상 로직이 있는 자식 → REQUEST_CANCEL
    //   자식에게 취소 "요청" 을 보냅니다. 자식은 CanceledFailure 를 받고
    //   detached cancellation scope 안에서 보상을 실행한 뒤 스스로 끝냅니다.
    //   이것이 Step 09 Saga 의 기반입니다.
    //
    //   ⚠️ 주의: 취소된 스코프 안에서는 새 액티비티를 호출할 수 없습니다.
    //   반드시 CancellationScope.newDetachedCancellationScope(...) 로 감싸야
    //   보상 액티비티가 실행됩니다. 이걸 빼먹으면 보상 코드가 있는데도
    //   아무 일 없이 자식이 죽습니다 — 조용한 실패의 전형입니다.
    //
    // 운영 절차:
    //   terminate 전에 반드시 describe 로 Pending Children 을 확인하십시오.
    //   그리고 terminate 대신 cancel 을 먼저 시도하십시오.
    //   cancel 은 워크플로우에 정리할 기회를 주고, terminate 는 주지 않습니다.
    // =====================================================================

    private static final ParentClosePolicy POLICY =
            ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL;

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
                Workflow.sleep(Duration.ofMinutes(3));
            } catch (io.temporal.failure.CanceledFailure e) {
                // detached 로 감싸지 않으면 이 액티비티 호출도 즉시 취소됩니다.
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
    // 정답 4 — 회당 이벤트 수
    //
    // 측정 결과:
    //
    //   | 틱  | ex4-sleep | ex4-activity |
    //   |-----|-----------|--------------|
    //   | 0   | 8         | 8            |
    //   | 100 | 412       | 812          |
    //   | 200 | 812       | 1,612        |
    //   | 300 | 1,212     | 2,412        |
    //
    //   회당 이벤트 수(sleep only)    = (1212 - 412) / 200 = 4
    //   회당 이벤트 수(액티비티 포함) = (2412 - 812) / 200 = 8
    //
    // 내역:
    //
    //   sleep only 4개
    //     TimerStarted            (앞 WorkflowTaskCompleted 에 묶여 나옴)
    //     TimerFired
    //     WorkflowTaskScheduled
    //     WorkflowTaskStarted
    //     WorkflowTaskCompleted
    //     → 실질 4개 (TimerStarted 가 앞 Task 의 커맨드로 붙음)
    //
    //   액티비티 포함 8개
    //     위 4개
    //     + ActivityTaskScheduled
    //     + ActivityTaskStarted
    //     + ActivityTaskCompleted
    //     + WorkflowTaskScheduled/Started/Completed 중 실질 1개
    //     → 실질 8개
    //
    // 51,200 한계 도달 시점:
    //
    //   sleep only    : 51,200 / 4 = 12,800회
    //   액티비티 포함 : 51,200 / 8 =  6,400회
    //
    // 1초 간격이면 sleep only 는 약 3시간 33분, 액티비티 포함은 약 1시간 47분에 죽습니다.
    // 실제 운영에서는 폴링 간격이 1분·10분이므로 며칠~몇 주 뒤에 죽습니다.
    // 배포하고 한참 지난 뒤라 원인을 코드와 연결하기 매우 어렵습니다.
    //
    // 액티비티가 재시도되면 회당 이벤트가 더 늘어납니다. 재시도 3회면
    // ActivityTaskStarted/Failed 가 3쌍 추가되어 회당 14개가 됩니다.
    // 즉 "회당 이벤트 수" 는 고정값이 아니라 최악 케이스로 잡아야 합니다.
    // 그래서 정답 5 가 고정 상수 대신 isContinueAsNewSuggested() 를 함께 쓰는 것입니다.
    // =====================================================================

    // =====================================================================
    // 정답 5 — continueAsNew 임계값 역산
    //
    // 목표 히스토리 8,000 이벤트, 회당 8개 → TICKS_PER_RUN = 1,000
    //
    //   | | continueAsNew 없음 | continueAsNew 있음 |
    //   |---|---|---|
    //   | 3,000틱 시점 History Length | 24,008 | 8,008 (3번째 Run) |
    //   | Run 개수 | 1 | 3 |
    //   | Workflow Task 처리 시간 | 1.9초 | 290ms |
    //   | 12,800틱 시점 | TERMINATED | 정상 (13번째 Run) |
    //
    // 고정 상수의 한계:
    //   액티비티 재시도가 많은 날에는 회당 8개가 아니라 14개가 되어 1,000회면 14,000 이벤트,
    //   경고 임계값을 넘습니다. 그래서 아래 구현은 고정 상수와
    //   isContinueAsNewSuggested() 를 OR 로 묶었습니다. 둘 중 먼저 걸리는 쪽에서 자릅니다.
    //
    //   getHistoryLength() 를 조건문에 쓰는 것은 안전합니다. 리플레이 시에도
    //   그 시점의 히스토리 위치를 그대로 재현하기 때문입니다.
    //   System.currentTimeMillis() 나 Math.random() 과는 성질이 다릅니다.
    //
    // 임계값 선택 가이드:
    //   1,000 이벤트  → Task 35ms.  Run 교체가 너무 잦음
    //   5,000 이벤트  → Task 180ms. 권장
    //   10,000 이벤트 → Task 380ms. 경고 임계값 직전, 여유 없음
    //   25,000 이벤트 → Task 1.9초. 위험
    // =====================================================================

    @WorkflowInterface
    public interface Ex5PollingWorkflow {

        @WorkflowMethod
        String poll(String orderId, int carriedTick);

        @QueryMethod
        int getTick();
    }

    public static class Ex5PollingWorkflowImpl implements Ex5PollingWorkflow {

        private static final Logger log = Workflow.getLogger(Ex5PollingWorkflowImpl.class);

        /** 목표 8,000 이벤트 ÷ 회당 8개 = 1,000회 */
        private static final int TICKS_PER_RUN = 1_000;
        private static final int HISTORY_THRESHOLD = 8_000;

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        private int tick;

        @Override
        public String poll(String orderId, int carriedTick) {
            this.tick = carriedTick;
            log.info("[{}] Run 시작 carriedTick={}", orderId, carriedTick);

            for (int i = 0; i < TICKS_PER_RUN; i++) {
                Workflow.sleep(Duration.ofSeconds(1));
                shipping.requestShipment(orderId, "tick-" + tick);
                tick++;

                // 고정 상수만 믿지 않습니다. 재시도가 많으면 회당 이벤트가 늘어나므로
                // 서버의 권고나 실측 길이가 먼저 걸리면 그 자리에서 자릅니다.
                if (Workflow.getInfo().getHistoryLength() > HISTORY_THRESHOLD
                        || Workflow.getInfo().isContinueAsNewSuggested()) {
                    log.info("[{}] 임계값 도달 length={} tick={}",
                            orderId, Workflow.getInfo().getHistoryLength(), tick);
                    break;
                }
            }

            Workflow.await(() -> Workflow.isEveryHandlerFinished());

            Ex5PollingWorkflow next = Workflow.newContinueAsNewStub(Ex5PollingWorkflow.class);
            next.poll(orderId, tick);           // tick 을 이월

            throw new IllegalStateException("도달할 수 없음");
        }

        @Override
        public int getTick() {
            return tick;
        }
    }

    // =====================================================================
    // 정답 6 — continueAsNew 직전 시그널 유실 (이 스텝의 핵심)
    //
    // (a) 시그널 10개를 1초 간격으로 보내면 TICKS_PER_RUN=5 이므로 5초마다 Run 이 바뀝니다.
    //     수정 전 getPendingTasks 는 대개 0~2개만 남습니다. 두 가지 이유가 겹칩니다.
    //
    //     이유 1 — 드레인 없음:
    //       continueAsNew 직전에 도착한 시그널은 히스토리에 WorkflowExecutionSignaled 로
    //       기록되지만, 워크플로우가 그것을 처리하지 않고 Run 을 닫습니다.
    //       핸들러가 실행되지 않으므로 pendingTasks 에 들어가지도 못합니다.
    //
    //     이유 2 — 이월 없음:
    //       핸들러가 정상 실행되어 pendingTasks 에 들어갔더라도, 새 Run 은 새 객체입니다.
    //       필드는 전부 초기화되므로 pendingTasks 가 빈 리스트로 시작합니다.
    //       즉 이전 Run 이 모아 둔 작업이 전부 사라집니다.
    //
    // (b) Status 는 RUNNING 입니다. 아무것도 이상해 보이지 않습니다.
    //     이것이 7-8 의 유실보다 나쁜 이유입니다.
    //     7-8 에서는 최소한 워크플로우가 COMPLETED 로 닫히기라도 했습니다.
    //     여기서는 워크플로우가 멀쩡히 돌고 있고, 시그널 전송도 성공했고,
    //     히스토리에도 WorkflowExecutionSignaled 가 남아 있습니다.
    //     유일한 흔적은 이전 Run 의 워커 로그 WARN 한 줄인데,
    //     그 Run 은 보존 기간(기본 3일)이 지나면 사라집니다.
    //
    //       WARN i.t.i.sync.WorkflowExecuteRunnable - Workflow ex6-poll finished while
    //         update/signal handlers are still running. This may have interrupted the
    //         execution of the handler(s): addTask(1)
    //
    // (c) 둘 중 하나만 하면:
    //
    //     드레인만 하고 이월 안 함:
    //       핸들러는 정상 실행되어 pendingTasks 에 들어갑니다. 그런데 새 Run 에서
    //       필드가 초기화되므로 전부 사라집니다. 결과적으로 여전히 0개입니다.
    //       "처리는 했지만 다음 Run 으로 넘기지 못한" 케이스입니다.
    //
    //     이월만 하고 드레인 안 함:
    //       이미 pendingTasks 에 들어간 것은 넘어갑니다. 그러나 continueAsNew 직전
    //       도착해서 아직 핸들러가 안 돈 시그널은 여전히 사라집니다.
    //       "도착했지만 처리 못 한" 케이스입니다.
    //
    //     둘 다 해야 완성입니다.
    //       Workflow.await(() -> Workflow.isEveryHandlerFinished());   ← 도착분 처리
    //       next.poll(orderId, tick, new ArrayList<>(pendingTasks));   ← 처리분 이월
    //
    // 추가 주의:
    //   - 이월 리스트에 new ArrayList<>(...) 로 복사본을 넘깁니다.
    //     원본을 그대로 넘겨도 직렬화되므로 동작은 같지만, 의도를 명확히 하기 위함입니다.
    //   - 이월 상태가 커지면 새 Run 의 WorkflowExecutionStarted 이벤트가 커집니다.
    //     리스트가 수천 개로 자란다면 외부 저장소에 두고 키만 넘기십시오.
    //     안 그러면 히스토리를 자르려던 continueAsNew 가 오히려 큰 이벤트를 만듭니다.
    //   - 진행 중인 액티비티가 있으면 continueAsNew 가 취소해 버립니다.
    //     Async.function 으로 띄운 액티비티는 반드시 get() 으로 먼저 기다리십시오.
    //   - Query 는 현재 Run 만 답합니다. Query 로 노출할 상태는 반드시 이월 인자에 넣으십시오.
    // =====================================================================

    @WorkflowInterface
    public interface Ex6PollingWorkflow {

        /** 정답 6 — 이월할 상태를 인자에 추가 */
        @WorkflowMethod
        String poll(String orderId, int carriedTick, List<String> carriedTasks);

        @SignalMethod
        void addTask(String task);

        @QueryMethod
        List<String> getPendingTasks();

        @QueryMethod
        int getTick();
    }

    public static class Ex6PollingWorkflowImpl implements Ex6PollingWorkflow {

        private static final Logger log = Workflow.getLogger(Ex6PollingWorkflowImpl.class);
        private static final int TICKS_PER_RUN = 5;

        private int tick;
        private final List<String> pendingTasks = new ArrayList<>();

        @Override
        public String poll(String orderId, int carriedTick, List<String> carriedTasks) {
            this.tick = carriedTick;

            // 정답 6 — 이월된 작업 복원
            if (carriedTasks != null) {
                this.pendingTasks.addAll(carriedTasks);
            }
            log.info("[{}] Run 시작 tick={} carried={}", orderId, carriedTick, pendingTasks.size());

            for (int i = 0; i < TICKS_PER_RUN; i++) {
                Workflow.sleep(Duration.ofSeconds(1));
                tick++;
            }

            // 정답 6 (1) — 미처리 시그널 핸들러 드레인
            Workflow.await(() -> Workflow.isEveryHandlerFinished());

            // 정답 6 (2) — pendingTasks 를 인자로 이월
            Ex6PollingWorkflow next = Workflow.newContinueAsNewStub(Ex6PollingWorkflow.class);
            next.poll(orderId, tick, new ArrayList<>(pendingTasks));

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
            case "5" -> worker.registerWorkflowImplementationTypes(Ex5PollingWorkflowImpl.class);
            case "6" -> worker.registerWorkflowImplementationTypes(Ex6PollingWorkflowImpl.class);
            default -> worker.registerWorkflowImplementationTypes(
                    Ex1OrderWorkflowImpl.class, Ex2OrderWorkflowImpl.class,
                    ChildShipmentWorkflowImpl.class, Ex3OrderWorkflowImpl.class,
                    Ex3ShipmentWorkflowImpl.class, Ex5PollingWorkflowImpl.class,
                    Ex6PollingWorkflowImpl.class);
        }

        worker.registerActivitiesImplementations(new ShippingActivityImpl());
        factory.start();
        System.out.println("Solution worker started. problem=" + problem + " POLICY=" + POLICY);
    }
}
