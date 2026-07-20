package com.example.order;

/*
 * ============================================================================
 * Step 02 — 핵심 개념과 실행 모델 : Solution.java  (정답 + 해설)
 * ============================================================================
 *
 * Exercise.java 를 먼저 풀어 본 뒤에 여세요.
 *
 * 실행 방법
 *   [터미널 1] java com.example.order.Solution$WorkerMain
 *   [터미널 2] java com.example.order.Solution$StarterMain <문제번호>
 *   kill 실험: kill -9 $(pgrep -f Solution)
 * ============================================================================
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class Solution {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    /* ==================================================================
     * 정답 1 — 33 이벤트
     * ------------------------------------------------------------------
     * 산식:
     *   기본 골격                     5
     *   액티비티 3개 x 6            = 18
     *   타이머 2개 x 5              = 10
     *                               ----
     *                                 33
     *
     * 왜 액티비티 1개가 6 이벤트인가 (Step 01 복습):
     *   ActivityTaskScheduled / Started / Completed        3
     *   + 결과를 코드에 전달하기 위한 WorkflowTask 3종        3
     *
     * 왜 타이머 1개가 5 이벤트인가:
     *   TimerStarted                                        1
     *   TimerFired                                          1
     *   + 만료를 코드에 전달하기 위한 WorkflowTask 3종        3
     *   → 타이머는 "Started" 와 "Fired" 만 있고 Activity 처럼
     *     "Started"(=워커가 가져감) 단계가 없으므로 액티비티보다 1개 적다.
     *     타이머는 서버가 직접 관리하며 Worker 가 개입하지 않기 때문이다.
     *
     * 전체 히스토리:
     *    1 WorkflowExecutionStarted
     *    2- 4 WorkflowTask x3
     *    5- 7 ActivityTask x3   (charge)
     *    8-10 WorkflowTask x3
     *   11-12 Timer x2          (5초)
     *   13-15 WorkflowTask x3
     *   16-18 ActivityTask x3   (reserve)
     *   19-21 WorkflowTask x3
     *   22-23 Timer x2          (5초)
     *   24-26 WorkflowTask x3
     *   27-29 ActivityTask x3   (requestShipment)
     *   30-32 WorkflowTask x3
     *   33    WorkflowExecutionCompleted
     *
     * 검증: temporal workflow describe -w order-5001 → History Length 33
     * ================================================================== */
    public static final int EXPECTED_EVENT_COUNT = 33;

    public static final AtomicInteger INVOKE_CHARGE = new AtomicInteger();
    public static final AtomicInteger INVOKE_RESERVE = new AtomicInteger();
    public static final AtomicInteger INVOKE_SHIP = new AtomicInteger();

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    // ==================================================================
    // 액티비티
    // ==================================================================
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
    }

    @ActivityInterface
    public interface MetricsActivity {
        @ActivityMethod void countOrderStarted(String orderId);
    }

    public static class PaymentActivityImpl implements PaymentActivity {
        private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);
        @Override public String charge(String orderId, long amount) {
            log.info("[{}] 결제 요청 amount={}  (실제 실행 {}회째)",
                    orderId, amount, INVOKE_CHARGE.incrementAndGet());
            return "pay-" + orderId;
        }
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);
        @Override public String reserve(String orderId, String sku, int qty) {
            log.info("[{}] 재고 예약 sku={}  (실제 실행 {}회째)",
                    orderId, sku, INVOKE_RESERVE.incrementAndGet());
            return "resv-" + orderId;
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = LoggerFactory.getLogger(ShippingActivityImpl.class);
        @Override public String requestShipment(String orderId, String address) {
            log.info("[{}] 배송 요청  (실제 실행 {}회째)",
                    orderId, INVOKE_SHIP.incrementAndGet());
            return "ship-" + orderId;
        }
    }

    // 정답 6(b) — 카운터는 액티비티로 뺀다. 그래야 정확히 한 번 실행된다.
    public static class MetricsActivityImpl implements MetricsActivity {
        private static final Logger log = LoggerFactory.getLogger(MetricsActivityImpl.class);
        public static final AtomicInteger COUNTER = new AtomicInteger();

        @Override public void countOrderStarted(String orderId) {
            log.info("[{}] 주문 시작 카운트 = {}", orderId, COUNTER.incrementAndGet());
            // 실제로는 meterRegistry.counter("order.started").increment()
        }
    }

    /* ==================================================================
     * 정답 2 — 리플레이 때 액티비티는 재호출되지 않는다
     * ------------------------------------------------------------------
     * 확인 결과:
     *
     *   (a) 재기동 후 PaymentActivityImpl 로그가 다시 찍히는가?
     *       → 아니다. 한 줄도 안 찍힌다.
     *          워크플로우 코드는 payment.charge(...) 줄을 분명히 다시 지나가지만,
     *          그 호출은 액티비티를 실행하지 않고 히스토리의
     *          ActivityTaskCompleted.result 에서 "pay-4002" 를 꺼내 반환한다.
     *
     *   (b) INVOKE_CHARGE 값이 늘었는가?
     *       → 재기동으로 JVM 이 새로 떴으니 0 부터 다시 시작하지만,
     *          재기동 후 charge 가 다시 실행되지 않으므로 0 인 채로 남는다.
     *          (reserve/requestShipment 만 1 이 된다)
     *
     *   (c) ActivityTaskStarted 의 attempt 값은?
     *       → 전부 1.
     *          temporal workflow show -w order-5002 --output json \
     *            | jq '[.events[] | select(.eventType=="ActivityTaskStarted")
     *                   | .activityTaskStartedEventAttributes.attempt]'
     *          → [1, 1, 1]
     *          attempt 가 2 이상이면 그것은 "리플레이"가 아니라 "재시도"다.
     *          둘은 완전히 다른 개념이다:
     *            리플레이 = 워크플로우 코드 재실행. 액티비티는 실행 안 됨.
     *            재시도   = 액티비티가 실패해서 실제로 다시 실행됨.
     *
     *   (d) 최종 History Length 는?
     *       → 33. kill 하지 않은 order-5001 과 완전히 같다.
     *          Worker 가 죽었다는 사실은 히스토리에 흔적조차 남지 않는다.
     *          걸린 시간만 10초 → 91초로 늘어난다.
     * ================================================================== */
    @WorkflowInterface
    public interface Q12Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q12WorkflowImpl implements Q12Workflow {
        private static final Logger log = Workflow.getLogger(Q12WorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 결제 시작", req.orderId());
            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofSeconds(5));
            log.info("[{}] 재고 예약 시작", req.orderId());
            inventory.reserve(req.orderId(), req.sku(), req.qty());
            Workflow.sleep(Duration.ofSeconds(5));
            log.info("[{}] 배송 요청 시작", req.orderId());
            shipping.requestShipment(req.orderId(), req.address());
            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 3 — Workflow.getLogger 로 바꾸면 8줄 → 4줄
     * ------------------------------------------------------------------
     * 측정 (실행 중 kill -9 한 번 섞은 경우):
     *
     *   grep -c 'Q3WorkflowImpl' replay.log
     *     LoggerFactory 사용      : 8
     *     Workflow.getLogger 사용 : 4
     *
     * 왜 8줄이 되는가:
     *   코드에 log.info 가 4개 있는데, 워크플로우가 3번 실행되면서
     *   이미 지나간 로그가 반복 출력되기 때문이다.
     *     1회차 Workflow Task : "결제 시작"                       (1줄)
     *     2회차 Workflow Task : "결제 시작"(중복) + "결제 완료"     (2줄)
     *     3회차(재기동)       : "결제 시작"(중복) + "결제 완료"(중복)
     *                          + "재고 예약 시작"                  (3줄)
     *     4회차               : ... + "재고 예약 완료"             (2줄)
     *                                                    합계 8줄
     *
     * 내부 동작:
     *   Workflow.getLogger 는 ReplayAwareLogger 라는 래퍼를 돌려준다.
     *   대략 이런 구조다:
     *
     *     public void info(String msg, Object... args) {
     *         if (!Workflow.isReplaying()) {
     *             delegate.info(msg, args);
     *         }
     *     }
     *
     *   즉 "이미 히스토리에 있는 구간을 다시 지나가는 중"이면 출력을 버린다.
     *   그래서 각 사건이 정확히 한 번씩만 기록된다.
     *
     * 주의 — 액티비티에서는 반대다:
     *   액티비티 구현체에서 Workflow.getLogger 를 쓰면 워크플로우 컨텍스트 밖이라
     *   예외가 난다. 액티비티는 평범한 LoggerFactory 를 쓴다.
     *
     * 팀 규칙으로 강제하기 (ArchUnit 예시):
     *
     *   @ArchTest
     *   static final ArchRule 워크플로우는_Workflow_getLogger_를_쓴다 =
     *       noClasses()
     *           .that().haveSimpleNameEndingWith("WorkflowImpl")
     *           .should().callMethod(LoggerFactory.class, "getLogger", Class.class)
     *           .because("워크플로우 로그는 리플레이마다 중복 출력됩니다. "
     *                  + "Workflow.getLogger() 를 사용하세요.");
     *
     * 더 넓은 원칙:
     *   로거만의 문제가 아니다. 워크플로우 코드 안의 모든 관찰 가능성 작업이
     *   리플레이마다 반복된다 — 메트릭 카운터, 슬랙 알림, System.out.
     *   판단 기준은 하나다: "이 줄이 100번 반복 실행돼도 괜찮은가?"
     *   안 괜찮다면 액티비티로 빼라.
     * ================================================================== */
    @WorkflowInterface
    public interface Q3Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q3WorkflowImpl implements Q3Workflow {

        // 정답: Workflow.getLogger
        private static final Logger log = Workflow.getLogger(Q3WorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            log.info("[{}] 결제 시작 amount={}", req.orderId(), req.amount());
            String paymentId = payment.charge(req.orderId(), req.amount());
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);

            Workflow.sleep(Duration.ofSeconds(5));

            log.info("[{}] 재고 예약 시작", req.orderId());
            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            log.info("[{}] 재고 예약 완료 {}", req.orderId(), reservationId);

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 4 — Command 매핑
     * ------------------------------------------------------------------
     *   ID  Type                          → 반환한 Command
     *   --  ----------------------------    -----------------------------
     *    4  WorkflowTaskCompleted           → ScheduleActivityTask(charge)
     *   10  WorkflowTaskCompleted           → StartTimer(5s)
     *   15  WorkflowTaskCompleted           → ScheduleActivityTask(reserve)
     *   21  WorkflowTaskCompleted           → CompleteWorkflowExecution
     *
     * 규칙이 왜 항상 성립하는가:
     *   Worker 는 Workflow Task 를 처리한 뒤 RespondWorkflowTaskCompleted 라는
     *   단 하나의 gRPC 호출로 (a) "처리 완료" 와 (b) "그 결과 만들어진 Command 목록" 을
     *   함께 보낸다. 서버는 이것을 받아
     *     ① WorkflowTaskCompleted 이벤트를 먼저 append
     *     ② 그 다음 각 Command 를 대응하는 Event 로 변환해 순서대로 append
     *   한다. 그래서 WorkflowTaskCompleted 바로 뒤에 오는 이벤트들이
     *   반드시 그 Task 가 반환한 Command 의 결과가 된다.
     *
     * Command → Event 대응표:
     *   ScheduleActivityTask              → ActivityTaskScheduled
     *   StartTimer                        → TimerStarted
     *   CompleteWorkflowExecution         → WorkflowExecutionCompleted
     *   FailWorkflowExecution             → WorkflowExecutionFailed
     *   StartChildWorkflowExecution       → StartChildWorkflowExecutionInitiated
     *   ContinueAsNewWorkflowExecution    → WorkflowExecutionContinuedAsNew
     *   RequestCancelActivityTask         → ActivityTaskCancelRequested
     *
     * 추가 질문 정답:
     *   (a) processOrder() 는 총 몇 번 실행됐나?
     *       → 4번. WorkflowTaskCompleted 가 4개(4, 10, 15, 21)이기 때문이다.
     *          Workflow Task 하나가 곧 processOrder() 한 번 전체 실행이다.
     *
     *   (b) 액티비티는 총 몇 번 실행됐나?
     *       → 2번. ActivityTaskStarted 가 2개(6, 17)다.
     *          코드는 4번 돌았지만 액티비티는 2번만 실행됐다 — 이 비대칭이 핵심이다.
     *
     *   (c) 이벤트 11 의 TimerStarted 는 코드의 어느 줄에서 나왔나?
     *       → Workflow.sleep(Duration.ofSeconds(5)) 줄.
     *          Workflow.sleep 은 실제로 스레드를 재우지 않는다. StartTimer Command 를
     *          만들고 코루틴을 yield 시킬 뿐이다. 5초는 서버가 세고, 만료되면
     *          TimerFired 를 기록하고 새 Workflow Task 를 스케줄한다.
     *          이것이 "워크플로우가 30일을 자도 리소스를 안 먹는" 이유다.
     * ================================================================== */

    /* ==================================================================
     * 정답 5 — Workflow.isReplaying() 으로 리플레이 관찰
     * ------------------------------------------------------------------
     * kill 을 섞어 실행한 출력 예:
     *
     *   >> 진입 #1  isReplaying=false     ← 첫 실행
     *   >> 진입 #2  isReplaying=true      ← charge 결과를 받아 이어감 (앞부분은 리플레이)
     *   --- kill -9 / 재기동 ---
     *   >> 진입 #1  isReplaying=true      ← 새 JVM. 카운터도 0부터. full replay
     *   >> 진입 #2  isReplaying=true
     *
     * 읽는 법:
     *   isReplaying 은 "지금 이 코드 라인이 히스토리에 이미 기록된 구간인가" 를 나타낸다.
     *   같은 processOrder() 실행 안에서도 앞부분은 true, 새로 진행하는 뒷부분은 false 가 된다.
     *   그래서 "진입 시점의 isReplaying" 은 "이번 Workflow Task 가 리플레이로 시작했는가" 를
     *   의미할 뿐, 실행 전체의 성격을 말하지 않는다.
     *
     * 왜 kill 을 섞어야 하는가:
     *   Sticky Execution 캐시 때문이다. Worker 가 워크플로우 인스턴스를 메모리에
     *   들고 있으면 다음 Workflow Task 는 리플레이 없이 "마지막 지점부터" 이어서 실행된다.
     *   즉 정상 경로에서는 리플레이가 거의 일어나지 않는다.
     *   캐시를 날려야(= Worker 재기동) full replay 를 관찰할 수 있다.
     *
     * 주의 — 카운터로 뭔가를 판단하지 말 것:
     *   진입 횟수는 캐시 히트 여부, Worker 재기동 타이밍, 서버 부하에 따라 매번 달라진다.
     *   "몇 번 실행됐는지"에 의존하는 로직은 어떤 형태든 잘못된 설계다.
     * ================================================================== */
    @WorkflowInterface
    public interface Q5Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q5WorkflowImpl implements Q5Workflow {
        private static final AtomicInteger ENTER = new AtomicInteger();

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            // log 가 아니라 System.out 이어야 리플레이 중에도 보인다.
            // (Workflow.getLogger 는 리플레이 중 출력을 억제하므로 관찰 목적에 안 맞는다)
            System.out.printf("  >> 진입 #%d  isReplaying=%s%n",
                    ENTER.incrementAndGet(), Workflow.isReplaying());

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofSeconds(5));
            payment.charge(req.orderId() + "-2", req.amount());

            return "order-" + req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 6 — Thread.sleep 과 static 카운터
     * ------------------------------------------------------------------
     * (a) Thread.sleep(3000) → Workflow.sleep(Duration.ofSeconds(3))
     * (b) counter++          → 액티비티로 이동 (MetricsActivity)
     *
     * (c) Thread.sleep 이 구체적으로 무엇을 망가뜨리는가 — 두 가지:
     *
     *   답 1) 워크플로우 코루틴 스레드를 진짜로 블로킹한다.
     *     Workflow Task 는 workflowTaskTimeout(기본 10초) 안에 응답해야 한다.
     *     3초를 자면 예산의 30%를 그냥 태운다. 액티비티가 여러 개라 리플레이가
     *     길어지면 10초를 넘겨 WorkflowTaskTimedOut 이 나고, 서버가 그 Task 를
     *     재스케줄해 처음부터 다시 시작한다. 그러면 또 3초를 자고... 무한 루프가 된다.
     *
     *     실제 히스토리:
     *       temporal workflow show -w order-4006
     *          ...
     *          14  WorkflowTaskTimedOut       ← 10초 초과
     *          15  WorkflowTaskScheduled      ← 재스케줄
     *          16  WorkflowTaskStarted
     *          17  WorkflowTaskTimedOut       ← 또 초과
     *          ...
     *
     *     또한 Worker 의 워크플로우 스레드는 유한하다(기본 600).
     *     동시 실행 중인 워크플로우가 600개를 넘고 각각 3초씩 자면
     *     "Timeout expired while waiting for a free workflow thread" 가 난다.
     *     즉 한 워크플로우의 Thread.sleep 이 워커 전체를 마비시킨다.
     *
     *   답 2) 리플레이할 때마다 다시 3초를 잔다.
     *     Workflow.sleep 은 히스토리에 TimerStarted/TimerFired 로 남으므로
     *     리플레이 때 "이미 지났음"으로 즉시 통과한다.
     *     Thread.sleep 은 히스토리에 아무 흔적도 남기지 않으므로
     *     리플레이할 때마다 실제로 3초를 다시 잔다.
     *     Workflow Task 가 10번 돌면 30초를 낭비한다. 히스토리가 길어질수록
     *     선형으로 느려지며, 결국 workflowTaskTimeout 을 넘긴다.
     *
     * Workflow.sleep 은 왜 안전한가:
     *   스레드를 재우지 않는다. StartTimer Command 를 만들고 코루틴을 yield 한 뒤
     *   Workflow Task 를 즉시 완료한다. 워크플로우 인스턴스는 캐시에서 축출되고
     *   Worker 는 아무 리소스도 붙들지 않는다. 3초 뒤 서버가 TimerFired 를 기록하고
     *   새 Workflow Task 를 스케줄한다.
     *   이 구조 덕분에 "30일 뒤에 리마인드" 같은 워크플로우가 리소스 0 으로 대기한다.
     *
     * counter 는 왜 액티비티로 빼야 하는가:
     *   static 필드는 리플레이마다 증가하므로 실제 실행 횟수와 무관한 값이 된다.
     *   Worker 를 재기동하면 JVM 이 새로 떠서 0 으로 리셋되기까지 한다.
     *   즉 이 값은 아무것도 의미하지 않는다.
     *   게다가 워크플로우 인스턴스 간에 공유되는 가변 static 상태는
     *   결정성 자체를 깨뜨린다 — 같은 히스토리로 리플레이해도 다른 값이 나온다.
     *   정답: metrics.countOrderStarted(orderId) 로 액티비티에 위임한다.
     *         액티비티는 리플레이되지 않으므로 정확히 한 번 실행된다.
     * ================================================================== */
    @WorkflowInterface
    public interface Q6Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q6WorkflowImpl implements Q6Workflow {
        private static final Logger log = Workflow.getLogger(Q6WorkflowImpl.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);

        // 정답 (b): 카운터를 액티비티로 위임
        private final MetricsActivity metrics =
                Workflow.newActivityStub(MetricsActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            // static counter++ 대신 액티비티 호출.
            // 리플레이되지 않으므로 정확히 한 번만 증가한다.
            metrics.countOrderStarted(req.orderId());

            payment.charge(req.orderId(), req.amount());

            // 정답 (a): Thread.sleep -> Workflow.sleep
            //   스레드를 블로킹하지 않고, 히스토리에 TimerStarted/TimerFired 로 남아
            //   리플레이 때 즉시 통과한다.
            Workflow.sleep(Duration.ofSeconds(3));

            payment.charge(req.orderId() + "-2", req.amount());

            log.info("[{}] 완료", req.orderId());
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
                    Q12WorkflowImpl.class, Q3WorkflowImpl.class,
                    Q5WorkflowImpl.class, Q6WorkflowImpl.class);
            worker.registerActivitiesImplementations(
                    new PaymentActivityImpl(),
                    new InventoryActivityImpl(),
                    new ShippingActivityImpl(),
                    new MetricsActivityImpl());

            factory.start();
            System.out.println("Solution Worker started. queue=" + ORDER_TASK_QUEUE);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println();
                System.out.println("--------------------------------------------");
                System.out.println(" 액티비티 실제 실행 횟수 (리플레이해도 안 늘어남)");
                System.out.println("   charge          : " + INVOKE_CHARGE.get());
                System.out.println("   reserve         : " + INVOKE_RESERVE.get());
                System.out.println("   requestShipment : " + INVOKE_SHIP.get());
                System.out.println("   order.started   : " + MetricsActivityImpl.COUNTER.get());
                System.out.println("--------------------------------------------");
            }));
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
                case "4" -> System.out.println("정답 4 는 파일 주석의 Command 매핑표를 보세요.");
                case "5" -> q5(client);
                case "6" -> q6(client);
                default -> System.out.println("문제 번호는 1~6 입니다.");
            }
            System.exit(0);
        }

        static void q1(WorkflowClient client) {
            Q12Workflow w = client.newWorkflowStub(Q12Workflow.class, opts("order-5001"));
            System.out.println("결과: " + w.processOrder(sample("5001")));
            System.out.println("정답: " + EXPECTED_EVENT_COUNT + " 이벤트");
            System.out.println("검증: temporal workflow describe -w order-5001");
            System.out.println("      → History Length 33");
        }

        static void q2(WorkflowClient client) {
            System.out.println("★ '결제 요청' 로그가 뜨면 5초 안에: kill -9 $(pgrep -f Solution)");
            Q12Workflow w = client.newWorkflowStub(Q12Workflow.class, opts("order-5002"));
            long t0 = System.currentTimeMillis();
            System.out.println("결과: " + w.processOrder(sample("5002")));
            System.out.println("소요: " + (System.currentTimeMillis() - t0) + "ms");
            System.out.println();
            System.out.println("검증 명령:");
            System.out.println("  temporal workflow describe -w order-5002    → History Length 33");
            System.out.println("  temporal workflow show -w order-5002 --output json \\");
            System.out.println("    | jq '[.events[] | select(.eventType==\"ActivityTaskStarted\")");
            System.out.println("           | .activityTaskStartedEventAttributes.attempt]'");
            System.out.println("                                              → [1,1,1]");
        }

        static void q3(WorkflowClient client) {
            Q3Workflow w = client.newWorkflowStub(Q3Workflow.class, opts("order-5003"));
            System.out.println("결과: " + w.processOrder(sample("5003")));
            System.out.println("grep -c 'Q3WorkflowImpl' replay.log  → 4 (LoggerFactory 였다면 8)");
        }

        static void q5(WorkflowClient client) {
            System.out.println("★ 실행 중 kill -9 를 섞으세요. isReplaying 값 변화를 관찰합니다.");
            Q5Workflow w = client.newWorkflowStub(Q5Workflow.class, opts("order-5005"));
            System.out.println("결과: " + w.processOrder(sample("5005")));
        }

        static void q6(WorkflowClient client) {
            Q6Workflow w = client.newWorkflowStub(Q6Workflow.class, opts("order-5006"));
            System.out.println("결과: " + w.processOrder(sample("5006")));
            System.out.println();
            System.out.println("검증: temporal workflow show -w order-5006");
            System.out.println("  → WorkflowTaskTimedOut 이 없다. Workflow.sleep 은 스레드를 안 잡는다.");
            System.out.println("  → TimerStarted / TimerFired 가 히스토리에 남아 리플레이 때 즉시 통과한다.");
            System.out.println("  → order.started 카운터는 정확히 1 이다 (액티비티로 뺐으므로).");
        }

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
            for (int i = 5001; i <= 5006; i++) {
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
