package com.example.order.step07;

/*
 * Step 07 — Signal · Query · Update / Solution (6문제 정답 + 해설)
 *
 * 실행:
 *   ./gradlew run -PmainClass=com.example.order.step07.Solution -Pargs=1
 *
 * 문제를 풀어 본 "뒤에" 여십시오.
 *
 * 환경: Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 */

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Solution {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record Item(String sku, int qty) {
    }

    // =====================================================================
    // 정답 1 — pause / resume 시그널 쌍
    //
    // 요령은 세 가지입니다.
    //
    // (1) 상태는 boolean 필드 하나(paused)면 충분합니다. 시그널 두 개가 각각 true/false 로
    //     세팅하고, 대기는 Workflow.await(() -> !paused) 한 줄입니다.
    //
    // (2) await 를 "각 단계 시작 전" 에 둡니다. 단계 처리 도중에 pause 가 오면 그 단계는
    //     끝까지 마치고 다음 단계 앞에서 멈춥니다. 이것이 대개 원하는 동작입니다.
    //     처리 중간에 즉시 멈추게 하려면 액티비티에 heartbeat + cancellation 을 써야 하는데
    //     그건 Step 05/12 의 주제입니다.
    //
    // (3) pause 상태로 오래 머물러도 워커 자원을 쓰지 않습니다. await 는 워커 스레드를
    //     반환하고, 시그널이 오면 서버가 다시 깨웁니다. 며칠을 멈춰 있어도 비용이 없습니다.
    //
    // 히스토리 관점: pauseRequested / resumeRequested 는 각각
    // WorkflowExecutionSignaled 로 남습니다. "누가 언제 이 주문을 멈췄나" 가 영구히 기록됩니다.
    // 이것이 "boolean 을 외부 DB 에 저장하고 워크플로우가 폴링" 하는 방식 대비 결정적 장점입니다.
    // =====================================================================

    @WorkflowInterface
    public interface PausableWorkflow {

        @WorkflowMethod
        String run(String orderId);

        @SignalMethod
        void pauseRequested();

        @SignalMethod
        void resumeRequested();

        @QueryMethod
        String getStage();
    }

    public static class PausableWorkflowImpl implements PausableWorkflow {

        private static final org.slf4j.Logger log = Workflow.getLogger(PausableWorkflowImpl.class);

        private String stage = "RECEIVED";
        private boolean paused = false;

        @Override
        public String run(String orderId) {
            String[] stages = {"PAYMENT", "INVENTORY", "SHIPPING"};

            for (String s : stages) {
                // 정답: paused 가 풀릴 때까지 대기. 이미 false 면 즉시 통과합니다.
                Workflow.await(() -> !paused);

                stage = s;
                log.info("[{}] 단계 진입 {}", orderId, s);
                Workflow.sleep(Duration.ofSeconds(5));
            }

            stage = "DONE";
            Workflow.await(() -> Workflow.isEveryHandlerFinished());
            return orderId + " DONE";
        }

        @Override
        public void pauseRequested() {
            this.paused = true;
        }

        @Override
        public void resumeRequested() {
            this.paused = false;
        }

        @Override
        public String getStage() {
            // 정답: 읽기만 합니다. 문자열 조립은 지역 계산이라 안전합니다.
            return paused ? stage + " (PAUSED)" : stage;
        }
    }

    // =====================================================================
    // 정답 2 — 진행률 Query
    //
    // 핵심은 "계산은 해도 되지만 대입은 안 된다" 입니다.
    // 백분율을 구하는 산술은 지역 변수 안에서만 일어나므로 워크플로우 상태를 건드리지 않습니다.
    //
    // 흔한 실수 두 가지:
    //   (a) 계산 결과를 캐시하겠다고 필드에 저장 → 7-6 의 함정에 그대로 빠집니다.
    //   (b) Query 안에서 Workflow.currentTimeMillis() 로 "마지막 조회 시각" 을 필드에 기록
    //       → 시간 자체는 결정적이지만, 그 필드가 히스토리에 없으므로 역시 되돌아갑니다.
    //
    // 그리고 Query 반환 타입은 자유롭습니다. String 대신 record 를 반환하면
    // CLI 가 JSON 으로 예쁘게 보여 주고, Java 클라이언트는 타입 그대로 받습니다.
    // 실무에서는 record 쪽을 권장합니다. 문자열 포맷을 워크플로우가 정할 이유가 없기 때문입니다.
    // =====================================================================

    @WorkflowInterface
    public interface ProgressWorkflow {

        @WorkflowMethod
        String run(String orderId);

        @QueryMethod
        String getProgressText();

        @QueryMethod
        Progress getProgress();

        @SignalMethod
        void stepDone();
    }

    public record Progress(int completed, int total, int percent) {
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
            // 정답: 지역 변수만 사용. this. 대입 없음.
            int pct = TOTAL == 0 ? 0 : (completed * 100) / TOTAL;
            return completed + "/" + TOTAL + " (" + pct + "%)";
        }

        @Override
        public Progress getProgress() {
            int pct = TOTAL == 0 ? 0 : (completed * 100) / TOTAL;
            return new Progress(completed, TOTAL, pct);
        }
    }

    // =====================================================================
    // 정답 3 — 잘못된 Query 진단
    //
    // (a) 세 번 조회하면 "조회 1회", "조회 2회", "조회 3회" 로 잘 올라갑니다.
    // (b) 워커 재시작 후 조회하면 "조회 1회" 로 되돌아갑니다.
    // (c) 이유:
    //
    //     Query 요청이 오면 서버는 워커에게 히스토리 전체를 주고 "리플레이한 뒤 이 Query 를
    //     호출해서 결과를 줘" 라고 합니다. 워커는 워크플로우 객체를 새로 만들어 히스토리를
    //     처음부터 재생합니다. 이때 재생되는 것은 히스토리에 있는 이벤트뿐입니다.
    //
    //       WorkflowExecutionStarted     → run() 호출
    //       WorkflowExecutionSignaled    → 시그널 핸들러 재호출  ← 시그널의 효과는 재현됨
    //       ActivityTaskCompleted        → 액티비티 반환값 주입
    //       ...
    //
    //     queryCount++ 와 auditLog.add(...) 에 해당하는 이벤트는 히스토리 어디에도 없습니다.
    //     그래서 재생 후 queryCount 는 0, auditLog 는 비어 있습니다. 그 위에서 getSummary 가
    //     한 번 호출되니 1 이 됩니다.
    //
    //     되돌아가는 시점은 워커 재시작만이 아닙니다. 워커의 스티키 캐시
    //     (WorkerOptions.setMaxCachedWorkflows, 기본 600) 에서 축출되거나,
    //     다른 워커 인스턴스가 Query 를 처리해도 똑같이 0 부터 시작합니다.
    //     즉 운영에서는 "가끔 값이 이상하다" 로 나타나 원인 추적이 매우 어렵습니다.
    //
    //     더 나쁜 것은 이 필드를 워크플로우 로직이 읽는 경우입니다.
    //     if (queryCount > 10) 같은 분기가 있으면 리플레이마다 결과가 달라져
    //     NonDeterministicException 이 나고 워크플로우가 RUNNING 상태로 영구히 멈춥니다.
    //     Status 는 RUNNING 이라 모니터링에는 정상으로 보입니다.
    //
    //     참고: SDK 가 막아 주는 것은 "커맨드 생성" 뿐입니다.
    //     Query 안에서 액티비티를 부르거나 타이머를 걸면
    //       InvalidStateException: Query method ... should not modify workflow state
    //     가 즉시 납니다. 그러나 queryCount++ 는 순수 자바 코드라 SDK 가 개입할 지점이 없습니다.
    //     "SDK 가 안 막아 주니까 괜찮다" 가 아니라 "안 막아 주니까 더 위험하다" 입니다.
    //
    // (d) 조회 횟수를 정말 히스토리에 남겨야 한다면 Query 로는 불가능합니다.
    //     Signal(기록만) 이나 Update(기록 + 결과 반환) 여야 합니다. 아래 recordView 가 그 예입니다.
    //     다만 대부분의 경우 조회수는 워크플로우 밖(API 게이트웨이 메트릭, 액세스 로그)에서
    //     세는 것이 맞습니다. 히스토리를 조회 횟수만큼 부풀리는 것은 Step 08 의
    //     "이벤트 51,200 개 강제 종료" 로 가는 지름길입니다.
    // =====================================================================

    @WorkflowInterface
    public interface GoodQueryWorkflow {

        @WorkflowMethod
        String run(String orderId);

        /** 정답: 본문이 return 문 하나. 이것이 이상적인 Query 입니다. */
        @QueryMethod
        String getSummary();

        /** (d) 조회를 정말 기록해야 한다면 Signal 이어야 합니다. 히스토리에 남습니다. */
        @SignalMethod
        void recordView(String viewer);

        /** 기록된 조회 횟수는 Query 로 읽습니다. 읽기만 하므로 안전합니다. */
        @QueryMethod
        int getViewCount();

        @SignalMethod
        void finish();
    }

    public static class GoodQueryWorkflowImpl implements GoodQueryWorkflow {

        private String stage = "RUNNING";
        private int viewCount = 0;          // 이제 이 값은 Signal 로만 변합니다 → 히스토리에 있음

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
        public void recordView(String viewer) {
            viewCount++;                    // 시그널 핸들러에서의 변경은 리플레이로 재현됩니다
        }

        @Override
        public String getSummary() {
            return stage;                   // 정답: 대입 없음
        }

        @Override
        public int getViewCount() {
            return viewCount;
        }
    }

    // =====================================================================
    // 정답 4 — signalWithStart
    //
    // BatchRequest 에 워크플로우 메서드와 시그널 메서드를 각각 add 하고 signalWithStart 를
    // 한 번 호출합니다. 서버는 이 둘을 하나의 원자적 연산으로 처리합니다.
    //
    //   - 워크플로우가 없으면: 시작하고 시그널을 큐에 넣습니다.
    //   - 이미 있으면: 시작 요청은 무시하고 시그널만 추가합니다.
    //     (WorkflowExecutionAlreadyStartedException 이 나지 않습니다. 이것이 핵심입니다.)
    //
    // 유실 없음의 증거는 히스토리 순서입니다.
    //
    //   1  WorkflowExecutionStarted
    //   2  WorkflowExecutionSignaled     ← 여기
    //   3  WorkflowTaskScheduled         ← 워크플로우 코드는 이제서야 실행 예약됨
    //   4  WorkflowTaskStarted
    //   5  WorkflowTaskCompleted
    //
    // eventId 2 가 3 보다 앞입니다. 즉 run() 의 첫 줄이 실행되기도 전에 시그널이 이미
    // 히스토리에 있습니다. 워커가 리플레이할 때 이 시그널을 반드시 처리하게 되므로
    // 첫 아이템이 사라질 물리적 여지가 없습니다.
    //
    // naive 버전이 위험한 이유: try 블록의 addItem 이 실패한 뒤 catch 에서 start 를 하는데,
    // 두 스레드가 동시에 여기 도달하면 둘 다 start 를 시도합니다. 한쪽은
    // WorkflowExecutionAlreadyStartedException 을 받고 그 스레드의 addItem 은 실행되지 않습니다.
    // 게다가 이 예외는 catch 블록 안에서 던져지므로 로그에도 잘 안 남습니다.
    //
    // 주의: signalWithStart 는 워크플로우가 "종료된 뒤" 에 호출되면 새 워크플로우를 시작합니다.
    // 장바구니가 2시간 타임아웃으로 닫힌 뒤 아이템을 담으면 빈 장바구니가 새로 생깁니다.
    // 이것이 대개 원하는 동작이지만, 아니라면 WorkflowIdReusePolicy 를 조정해야 합니다.
    // =====================================================================

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

        private final List<Item> items = new ArrayList<>();
        private boolean checkedOut = false;

        @Override
        public List<Item> run(String customerId) {
            Workflow.await(Duration.ofHours(2), () -> checkedOut);
            Workflow.await(() -> Workflow.isEveryHandlerFinished());
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

    /** 정답 4 */
    public static void addToCart(WorkflowClient client, String customerId, Item item) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(ORDER_TASK_QUEUE)
                .setWorkflowId("cart-" + customerId)
                .build();

        CartWorkflow cart = client.newWorkflowStub(CartWorkflow.class, options);

        BatchRequest batch = client.newSignalWithStartRequest();
        batch.add(cart::run, customerId);        // 워크플로우 메서드 + 인자
        batch.add(cart::addItem, item);          // 시그널 메서드 + 인자
        client.signalWithStart(batch);
    }

    // =====================================================================
    // 정답 5 — Update + Validator
    //
    // Validator 가 지켜야 할 두 가지:
    //
    // (1) 상태를 읽기만 한다. stage 를 읽어 판단하는 것은 괜찮지만 qty 를 바꾸면 안 됩니다.
    //     Validator 는 거절될 수 있고, 거절되면 히스토리에 아무것도 남지 않습니다.
    //     즉 Validator 에서의 상태 변경은 Query 에서의 상태 변경과 정확히 같은 문제입니다.
    //
    // (2) 커맨드를 만들지 않는다. 액티비티 호출로 재고를 확인하고 싶어도 불가능합니다.
    //     재고 조회가 필요하다면 워크플로우가 미리 재고를 필드에 들고 있거나,
    //     Validator 없이 Update 본문에서 검증해야 합니다(대신 히스토리에 남습니다).
    //
    // 거절 시 동작 확인:
    //
    //   $ temporal workflow describe -w ex5-order
    //     History Length    11
    //
    //   $ temporal workflow update -w ex5-order --name changeQty --input '99'
    //     Error: unable to update workflow: UpdateWorkflowExecution failed: INVALID_ARGUMENT:
    //       java.lang.IllegalArgumentException: 재고 초과: 요청 99, 재고 10
    //
    //   $ temporal workflow describe -w ex5-order
    //     History Length    11        ← 변하지 않음
    //
    // 반면 Signal 로 같은 일을 했다면 잘못된 요청도 WorkflowExecutionSignaled 로 영구히
    // 히스토리에 남고, 호출자는 성공 응답을 받은 뒤 아무 일도 안 일어난 것을 나중에 알게 됩니다.
    //
    // ⚠️ updateName 오타 함정:
    //   @UpdateValidatorMethod(updateName = "changeQuantity")   // 오타
    //   위처럼 쓰면 워커 등록 시점에 검증되지 않고, 그냥 "연결된 Update 가 없는 검증기" 가 되어
    //   호출되지 않습니다. 모든 Update 가 검증 없이 통과합니다.
    //   반드시 거절 케이스를 한 번 테스트해서 검증기가 실제로 호출되는지 확인하십시오.
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

        @SignalMethod
        void ship();
    }

    public static class QtyWorkflowImpl implements QtyWorkflow {

        private static final org.slf4j.Logger log = Workflow.getLogger(QtyWorkflowImpl.class);
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
        public void ship() {
            stage = "SHIPPED";
        }

        // 정답 5 — Validator: 읽고, 판단하고, 던지거나 조용히 리턴
        @Override
        public void validateChangeQty(int newQty) {
            if (newQty < 1) {
                throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + newQty);
            }
            if (newQty > STOCK) {
                throw new IllegalArgumentException("재고 초과: 요청 " + newQty + ", 재고 " + STOCK);
            }
            if ("SHIPPED".equals(stage)) {
                throw new IllegalArgumentException("이미 배송되어 수량을 변경할 수 없습니다");
            }
            // 여기서 this.qty = newQty 를 하면 안 됩니다. 그건 changeQty 의 몫입니다.
        }

        // 정답 5 — Update 본문: 검증을 통과한 뒤에만 호출됩니다
        @Override
        public int changeQty(int newQty) {
            int old = this.qty;
            this.qty = newQty;
            log.info("수량 변경 {} → {}", old, newQty);
            return this.qty;
        }

        @Override
        public int getQty() {
            return qty;
        }
    }

    // =====================================================================
    // 정답 6 — 드레인 패턴
    //
    // 정답 자체는 한 줄입니다.
    //
    //   Workflow.await(() -> Workflow.isEveryHandlerFinished());
    //
    // (c) 이벤트 개수 차이: 3개가 늘어납니다.
    //
    //   드레인 없음 (총 17개)                  드레인 있음 (총 20개)
    //   15 WorkflowTaskCompleted               15 WorkflowTaskCompleted
    //   16 WorkflowExecutionSignaled           16 WorkflowExecutionSignaled
    //   17 WorkflowExecutionCompleted          17 WorkflowTaskScheduled     ← 추가
    //                                          18 WorkflowTaskStarted       ← 추가
    //                                          19 WorkflowTaskCompleted     ← 추가
    //                                          20 WorkflowExecutionCompleted
    //
    //   시그널을 처리하려면 Workflow Task 를 한 번 더 돌아야 하고, 그것이 3개 이벤트입니다.
    //   이벤트 3개로 유실을 막는 것이므로 거의 언제나 남는 장사입니다.
    //
    // 드레인 없을 때의 증상이 고약합니다.
    //   - temporal workflow signal 은 "Signal workflow succeeded" 를 반환합니다.
    //   - 히스토리에 WorkflowExecutionSignaled 가 분명히 남습니다.
    //   - 워크플로우 Status 는 COMPLETED 입니다.
    //   - 워커 로그에 WARN 한 줄:
    //       "Workflow ... finished while update/signal handlers are still running.
    //        This may have interrupted the execution of the handler(s): addNote(1)"
    //   어디에도 "실패" 가 없습니다. "취소 요청은 성공했는데 물건은 배송됐다" 가 이렇게 생깁니다.
    //
    // 한계도 알아야 합니다.
    //   isEveryHandlerFinished() 는 "이미 도착한" 시그널만 구제합니다.
    //   워크플로우가 완전히 닫힌 뒤에 온 시그널은 WorkflowNotFoundException 입니다.
    //   따라서 "취소 가능 시간" 이 중요한 도메인이라면 드레인에 기대지 말고,
    //   Workflow.await(Duration.ofHours(1), ...) 같은 명시적 유예 기간을 설계에 넣으십시오.
    //
    //   또한 Step 08 의 continueAsNew 직전에도 같은 문제가 있습니다.
    //   continueAsNew 는 현재 Run 을 닫으므로, 드레인 없이 호출하면 미처리 시그널이 사라집니다.
    //   이쪽은 워크플로우가 "계속 살아 있는 것처럼 보이기" 때문에 발견이 더 어렵습니다.
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

            // 정답 6 — 미처리 시그널/업데이트 핸들러가 전부 끝날 때까지 대기
            Workflow.await(() -> Workflow.isEveryHandlerFinished());

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
    // main
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
            case "3" -> worker.registerWorkflowImplementationTypes(GoodQueryWorkflowImpl.class);
            case "4" -> worker.registerWorkflowImplementationTypes(CartWorkflowImpl.class);
            case "5" -> worker.registerWorkflowImplementationTypes(QtyWorkflowImpl.class);
            case "6" -> worker.registerWorkflowImplementationTypes(DrainWorkflowImpl.class);
            default -> worker.registerWorkflowImplementationTypes(
                    PausableWorkflowImpl.class, ProgressWorkflowImpl.class,
                    GoodQueryWorkflowImpl.class, CartWorkflowImpl.class,
                    QtyWorkflowImpl.class, DrainWorkflowImpl.class);
        }

        factory.start();
        System.out.println("Solution worker started. problem=" + problem);
    }
}
