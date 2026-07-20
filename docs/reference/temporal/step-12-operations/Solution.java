package com.example.order;

/*
 * Step 12 — 운영 : Solution (정답 + 해설)
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Solution
 *
 * Exercise.java 를 직접 풀어 본 뒤에 여세요.
 */

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.common.SearchAttributeKey;
import io.temporal.failure.CanceledFailure;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class Solution {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 1 — Namespace 생성 + Retention 30일
     * ─────────────────────────────────────────────────────────────────
     * 30일 = 720시간. Retention 은 시간 단위(h)로 지정합니다.
     *
     * 꼭 알아야 할 세 가지:
     *
     * (1) Retention 은 **종료된(Closed)** 워크플로우에만 적용됩니다.
     *     3년 도는 워크플로우는 Retention 이 72시간이어도 안전합니다.
     *     "장기 실행 워크플로우가 3일 뒤에 사라지나요?" — 아니요.
     *
     * (2) Retention 을 **줄이면** 이미 저장된 히스토리도 다음 정리 주기에 삭제됩니다.
     *     720h → 72h 로 되돌리는 순간 3일 넘은 히스토리가 전부 날아갑니다. 되돌릴 수 없습니다.
     *
     * (3) Retention 이 지나면 workflow show 도 describe 도 NotFound 입니다.
     *     사후 분석이 불가능해집니다. prod 는 최소 30일을 권장하고,
     *     규제 대응 등으로 더 오래 필요하면 Archival 을 켭니다(최대 Retention 은 90일 권장).
     */
    static String 정답1_namespace_명령() {
        return """
                temporal operator namespace create --namespace orders --retention 72h
                temporal operator namespace update --namespace orders --retention 720h""";
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 2 — Search Attribute 쿼리
     * ─────────────────────────────────────────────────────────────────
     * 쿼리 언어는 SQL 유사 문법(List Filter)입니다.
     *
     * 타입별 사용 가능한 연산자:
     *   Keyword  : = != IN     ← ★ 부분 일치(LIKE)를 못 합니다. 정확히 일치해야 합니다
     *   Text     : 토큰 단위 매칭 (전문 검색용). 정렬 불가
     *   Int/Double : = != > < >= <= BETWEEN
     *   Datetime : > < BETWEEN
     *   Bool     : = !=
     *
     * CustomerId 를 Keyword 로 만든 이유: 정확 일치 조회만 필요하고, 인덱스가 가볍습니다.
     * "C-7 로 시작하는 고객" 같은 조회가 필요하면 STARTS_WITH 를 쓰거나 Text 를 고려하세요.
     *
     * ⚠️ 주의 — 인덱싱은 비동기입니다.
     *    방금 시작한 워크플로우는 수백 ms ~ 수 초 동안 이 쿼리에 안 잡힐 수 있습니다.
     *    "워크플로우를 시작하고 곧바로 list 로 확인"하는 코드는 flaky 해집니다.
     *    확실한 확인이 필요하면 workflowId 로 describe 하세요 (이건 즉시 반영됩니다).
     */
    static String 정답2_쿼리() {
        return "CustomerId='C-77' AND OrderAmount > 100000 AND ExecutionStatus='Failed'";
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 3 — detached scope 로 보상 실행  ★ 이 스텝의 핵심
     * ─────────────────────────────────────────────────────────────────
     * 왜 그냥 부르면 안 되는가:
     *
     *   워크플로우 메서드 전체는 하나의 CancellationScope(루트 스코프) 안에서 실행됩니다.
     *   temporal workflow cancel 이 오면 서버가 WorkflowExecutionCancelRequested 이벤트를
     *   기록하고, SDK 는 루트 스코프를 "취소됨" 상태로 표시합니다.
     *   그 결과 현재 대기 중이던 지점에서 CanceledFailure 가 던져집니다.
     *
     *   여기서 중요한 점: catch 블록도 여전히 **같은 루트 스코프 안**입니다.
     *   취소된 스코프에서 새로 스케줄한 액티비티는 태어나자마자 취소 요청을 받습니다.
     *
     * 두 버전의 히스토리 대조:
     *
     *   [틀린 버전]                            [올바른 버전]
     *   11 WorkflowExecutionCancelRequested    11 WorkflowExecutionCancelRequested
     *   13 ActivityTaskScheduled  release      13 ActivityTaskScheduled  release
     *   14 ActivityTaskCancelRequested   ←!    14 ActivityTaskCompleted        ←!
     *   15 ActivityTaskCanceled                15 ActivityTaskScheduled  refund
     *   16 WorkflowExecutionCanceled           16 ActivityTaskCompleted
     *                                          17 ActivityTaskScheduled  notifyCustomer
     *                                          18 ActivityTaskCompleted
     *                                          19 WorkflowExecutionCanceled
     *
     *   Status: CANCELED                       Status: CANCELED
     *   ↑ ★ 둘 다 CANCELED 입니다.
     *
     * 이것이 이 코스가 말하는 "에러 없이 조용히 잘못 동작하는 코드"의 전형입니다.
     * 예외도, 실패 이벤트도, 경고 로그도 없습니다. 모니터링 대시보드에서는
     * "취소 처리 완료"로 정상 집계됩니다. 결제는 환불되지 않았고 재고는 잠긴 채로 남습니다.
     * 히스토리를 직접 열어 보기 전까지는 아무도 모릅니다.
     *
     * 해결: Workflow.newDetachedCancellationScope(Runnable)
     *   부모 스코프의 취소로부터 분리된 새 스코프를 만듭니다.
     *   반드시 .run() 을 불러야 실행됩니다 — 만들기만 하면 아무 일도 일어나지 않습니다.
     *   (이것도 흔한 실수입니다. 컴파일도 되고 예외도 없이 보상이 통째로 사라집니다.)
     *
     * 검증 방법 (Step 11 방식):
     *   verify(payment).refund("PAY-8821");
     *   verify(inventory).release("RSV-3310");
     *   Status 만 단언하는 테스트는 두 버전을 구분하지 못합니다.
     *
     * ⚠️ detached scope 에는 타임아웃을 짧게
     *   보상이 오래 걸리면 워크플로우가 CANCELED 로 넘어가지 못하고 매달립니다.
     *   보상 액티비티의 startToCloseTimeout 과 maximumAttempts 를
     *   정방향보다 짧고 적게 잡고, 실패하면 알림으로 사람에게 넘기는 편이 낫습니다.
     */
    public static class Q3OrderWorkflowImpl implements OrderWorkflow {

        static final ActivityOptions ACT = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

        // 보상용 옵션 — 정방향보다 짧게, 재시도도 적게
        static final ActivityOptions COMPENSATE = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .setRetryOptions(io.temporal.common.RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .build())
                .build();

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, ACT);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, ACT);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, ACT);

        private final PaymentActivity paymentCompensate =
                Workflow.newActivityStub(PaymentActivity.class, COMPENSATE);
        private final InventoryActivity inventoryCompensate =
                Workflow.newActivityStub(InventoryActivity.class, COMPENSATE);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, COMPENSATE);

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

                CancellationScope compensation = Workflow.newDetachedCancellationScope(() -> {
                    // 실행의 역순으로 보상합니다 (Step 09).
                    if (reservationId[0] != null) {
                        inventoryCompensate.release(reservationId[0]);
                    }
                    if (paymentId[0] != null) {
                        paymentCompensate.refund(paymentId[0]);
                    }
                    notification.notifyCustomer(req.orderId(), "주문이 취소되었습니다");
                });
                compensation.run();   // ★ 이 줄이 없으면 보상이 통째로 사라집니다

                throw e;              // 워크플로우는 CANCELED 로 마무리
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
     * ─────────────────────────────────────────────────────────────────
     * 정답 4 — Search Attribute 등록과 upsert
     * ─────────────────────────────────────────────────────────────────
     * 키를 static final 상수로 뽑는 이유:
     *   SearchAttributeKey.forKeyword("OrderStage") 를 호출 지점마다 새로 쓰면
     *   "OderStage" 같은 오타가 런타임에야 드러납니다.
     *   (등록되지 않은 이름으로 upsert 하면 워크플로우 태스크가 실패합니다.)
     *   상수 하나로 모으면 오타가 한 곳에만 있을 수 있고, IDE 가 자동완성해 줍니다.
     *
     * upsertTypedSearchAttributes 는 결정적입니다.
     *   히스토리에 UpsertWorkflowSearchAttributes 이벤트가 남으므로
     *   리플레이 시 같은 순서로 재현됩니다. 워크플로우 코드 안에서 안전하게 쓸 수 있습니다.
     *
     * ⚠️ 남발하지 마세요.
     *   upsert 한 번 = 히스토리 이벤트 한 개입니다.
     *   루프 안에서 매 반복마다 upsert 하면 히스토리가 폭증하고,
     *   Workflow Task 처리 시간도 같이 늘어납니다(캐시 미스 시 전부 리플레이하므로).
     *   "단계가 바뀔 때만" 부르세요.
     *
     * 판단 기준 — Search Attribute vs Query:
     *   list 로 **검색**할 일이 있다   → Search Attribute
     *   특정 한 건의 **상세**를 본다   → Query (Step 07)
     *   Search Attribute 는 인덱싱용이지 상태 저장소가 아닙니다.
     *   값 하나당 2KB, 워크플로우당 총 40KB 제한이 있고, 인덱싱이 비동기입니다.
     */
    static String 정답4a_SA_등록_명령() {
        return "temporal operator search-attribute create "
                + "--name OrderStage --type Keyword --namespace orders";
    }

    static final SearchAttributeKey<String> STAGE = SearchAttributeKey.forKeyword("OrderStage");

    static void 정답4c_markStage(String stage) {
        Workflow.upsertTypedSearchAttributes(STAGE.valueSet(stage));
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 5 — Worker 튜닝 (데드락 방지)
     * ─────────────────────────────────────────────────────────────────
     * cacheSize 2000 → threadCount 최소 4000.
     *
     * 왜인가:
     *   Sticky Cache 는 실행 중인 워크플로우 인스턴스를 메모리에 유지해,
     *   다음 Workflow Task 가 왔을 때 히스토리를 처음부터 리플레이하지 않게 합니다.
     *   그런데 캐시된 워크플로우는 **자기 스레드를 점유한 채** 대기합니다.
     *   Workflow.sleep() 이나 액티비티 완료를 기다리는 지점에서 스레드가 멈춰 있는 구조이기 때문입니다.
     *
     *   cacheSize == threadCount 로 두면, 캐시가 가득 차는 순간 스레드가 정확히 소진됩니다.
     *   여기에 자식 워크플로우나 Async.function 으로 스레드를 추가로 쓰는 워크플로우가 하나라도 있으면
     *   스레드를 못 얻어 데드락입니다.
     *
     * 규칙:
     *   maxWorkflowThreadCount >= workflowCacheSize x (워크플로우당 평균 스레드 수 + 1)
     *   - 단순 워크플로우 (자식 없음, Async 없음)     : 배수 2
     *   - 자식 워크플로우 / Async.function 여러 개    : 배수 3~4
     *
     * 부족할 때 나오는 실제 로그:
     *   ERROR i.t.i.w.WorkflowExecutionHandler -
     *     io.temporal.internal.sync.PotentialDeadlockException:
     *     Potential deadlock detected: workflow thread "workflow-method-order-2288-1"
     *     didn't yield control for over a second.
     *     Failure to yield can be caused by blocking calls or by exhausting the workflow thread pool.
     *     Current thread pool: 600/600 in use, 0 available.
     *
     *   "600/600 in use, 0 available" 이 결정적 단서입니다.
     *   블로킹 호출 때문이라고 오해하기 쉬운데, 스레드 풀 고갈일 때도 같은 예외가 납니다.
     *
     * 반대 방향의 실수 — cacheSize 를 너무 낮게:
     *   캐시 미스가 늘어 매번 전체 히스토리를 리플레이합니다.
     *   이벤트 5,000개짜리 워크플로우면 Workflow Task 하나당 5,000개를 다시 돌립니다.
     *
     *   cacheSize 600  → sticky_cache_miss 8,421건, task_execution_latency p95 = 2.841초
     *   cacheSize 2000 → sticky_cache_miss    37건, task_execution_latency p95 = 0.028초
     *   약 100배 차이입니다. 물론 threadCount 도 4000 으로 함께 올렸습니다.
     *
     * 메모리도 함께 보세요. 캐시된 워크플로우 하나가 수백 KB~수 MB 를 씁니다.
     * cacheSize 를 올리면 힙도 같이 늘려야 합니다.
     */
    static WorkerFactory 정답5_factory(WorkflowClient client) {
        WorkerFactoryOptions options = WorkerFactoryOptions.newBuilder()
                .setWorkflowCacheSize(2000)
                .setMaxWorkflowThreadCount(4000)     // cacheSize x 2
                .build();
        return WorkerFactory.newInstance(client, options);
    }

    static int 정답5_선택한_threadCount() {
        return 4000;
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 6 — 메트릭 판독
     * ─────────────────────────────────────────────────────────────────
     * [A] 결정성
     *   workflow_task_execution_failed 가 8912. 나머지는 전부 정상입니다.
     *   slots_available 380 (여유 있음), latency 0.021초 (빠름), cache_miss 12 (정상).
     *   즉 Worker 는 건강한데 워크플로우 코드 실행 자체가 실패하고 있습니다.
     *   정상 운영에서 이 지표는 0이어야 합니다. 0이 아니면 거의 항상 NonDeterministicException 입니다.
     *
     *   대응 순서:
     *     1. Worker 로그에서 NonDeterministicException 확인 (12-8 의 3단계)
     *     2. **즉시 롤백.** 이게 1순위입니다.
     *        NonDeterministicException 은 워크플로우를 망가뜨리지 않습니다.
     *        Workflow Task 가 실패할 뿐이고 히스토리는 그대로이므로,
     *        옛 코드로 돌아가면 워크플로우들이 아무 일 없었다는 듯 이어서 진행합니다.
     *     3. ★ 절대 terminate 하지 마세요. 보상이 하나도 안 돌고 영구히 방치됩니다.
     *     4. 롤백 후 Workflow.getVersion() 을 넣고 재배포.
     *        Step 11 의 리플레이 테스트를 CI 에 추가해 재발을 막습니다.
     *
     * [B] Worker부족
     *   slots_available 이 0.0 입니다. 그리고 workflow/activity 양쪽
     *   schedule_to_start_latency 가 6.4초 / 9.9초로 높습니다.
     *   Task 는 큐에 쌓이는데 Worker 가 가져갈 슬롯이 없습니다.
     *   failed 는 0이므로 코드 문제는 아닙니다.
     *
     *   대응 순서 (12-7 의 튜닝 순서):
     *     1. task-queue describe 로 Poller 가 정말 있는지 확인 (0대면 배포 문제)
     *     2. **Worker 증설이 먼저입니다.** 파라미터를 키우는 것보다
     *        프로세스를 늘리는 편이 장애 격리 면에서도 낫습니다.
     *     3. 그래도 부족하면 maxConcurrentActivityExecutionSize 증가
     *     4. 슬롯은 남는데 latency 가 높으면 그때 폴러 수(5 → 10~20) 증가
     *     5. 근본 해결: 성격이 다른 작업을 Task Queue 로 분리
     *        (3초짜리 결제와 5분짜리 리포트가 같은 큐에 있으면 리포트가 슬롯을 다 씁니다)
     *
     * [C] 캐시부족
     *   sticky_cache_miss 가 8421 로 압도적이고, workflow_task_execution_latency 가 2.841초입니다.
     *   반면 schedule_to_start 는 0.412초로 나쁘지 않고 slots 도 150개 남습니다.
     *   즉 Task 를 가져오는 건 문제없는데, 가져와서 처리하는 게 느립니다.
     *   캐시에서 밀려난 워크플로우가 매번 전체 히스토리를 리플레이하는 중입니다.
     *
     *   대응:
     *     1. workflowCacheSize 증가 (600 → 2000)
     *     2. ★ maxWorkflowThreadCount 를 **반드시 함께** 올릴 것 (정답 5)
     *        cacheSize 만 올리면 PotentialDeadlockException 으로 바뀝니다.
     *        문제를 다른 문제로 교체하는 셈입니다.
     *     3. 힙 크기도 함께 확인
     *     4. 근본적으로 히스토리가 너무 길면 Continue-As-New 를 검토 (Step 08)
     */
    static String 정답6_A() {
        return "결정성";
    }

    static String 정답6_B() {
        return "Worker부족";
    }

    static String 정답6_C() {
        return "캐시부족";
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 7 — 진단 명령 순서
     * ─────────────────────────────────────────────────────────────────
     * 순서가 중요합니다. 위에서부터 하세요.
     * 아래 단계부터 손대면 원인을 못 찾고 파라미터만 커집니다.
     *
     * 1단계 describe — 무엇을 기다리는가
     *   Pending Activities 있음 + Attempt 증가        → 외부 시스템 장애 (4단계로)
     *   Pending Activities 있음 + State Scheduled     → Worker 가 안 가져감 (2단계로)
     *   Pending Workflow Task 만 + Attempt 가 큼      → 결정성 문제 (3단계로)
     *
     * 2단계 task-queue describe — Worker 가 붙어 있는가
     *   Poller 목록이 비었으면 셋 중 하나입니다.
     *     (a) Worker 프로세스 죽음
     *     (b) Worker 가 다른 Task Queue 이름으로 붙음 ← 오타. 매우 흔합니다
     *     (c) Worker 가 다른 Namespace 에 붙음
     *   LastAccessTime 이 몇 분 전에서 멈췄으면 Worker 는 살아 있으나
     *   폴링을 못 하는 상태입니다(스레드 고갈 등).
     *
     * 3단계 Worker 로그 — 예외 실물 확인
     *   NonDeterministicException 이면 어느 eventId 에서 무엇이 어긋났는지까지 나옵니다.
     *
     * 4단계 count + 메트릭 — 영향 범위
     *   한 건인지 전체인지에 따라 조치가 완전히 달라집니다.
     *   한 건이면 개별 reset, 전체면 롤백입니다.
     *
     * 5단계 조치 — 12-8 의 표 참고.
     *   ★ 외부 시스템 장애일 때는 **아무것도 하지 않는 것**이 정답입니다.
     *     복구되면 재시도가 알아서 성공합니다. 그러라고 Temporal 을 쓰는 것입니다.
     *
     * ───────────────────────────────────────────────────────────────
     * 마지막으로 — 이 진단이 필요한 상황 자체를 예방할 수 있습니다.
     *
     * 3단계에서 NonDeterministicException 을 발견했다면, 그것은
     * Step 11 의 리플레이 테스트가 CI 에 없었다는 뜻입니다.
     * 운영 히스토리 5개를 src/test/resources/histories/ 에 커밋하고
     * 모든 PR 에서 WorkflowReplayer 를 돌렸다면, 이 사고는 3초짜리 CI 단계에서
     * 배포 전에 잡혔을 것입니다.
     *
     * 운영 대응 능력보다 중요한 것은 대응할 일을 만들지 않는 것입니다.
     * ───────────────────────────────────────────────────────────────
     */
    static String[] 정답7_진단_순서() {
        return new String[]{
                "temporal workflow describe -w order-1004 --namespace orders",
                "temporal task-queue describe --task-queue ORDER_TASK_QUEUE --namespace orders",
                "kubectl logs deploy/order-worker --since=10m | grep -E \"NonDeterministic|Deadlock|ERROR\"",
                "temporal workflow count --query \"ExecutionStatus='Running'\" --namespace orders"
        };
    }

    // =====================================================================
    public static void main(String[] args) {
        System.out.println("── 정답 1 ──\n" + 정답1_namespace_명령());
        System.out.println("\n── 정답 2 ──\ntemporal workflow list --query \""
                + 정답2_쿼리() + "\" --namespace orders");
        System.out.println("\n── 정답 3 ──\nQ3OrderWorkflowImpl 참고 "
                + "(Workflow.newDetachedCancellationScope + .run())");
        System.out.println("\n── 정답 4 ──\n" + 정답4a_SA_등록_명령());
        System.out.println("\n── 정답 5 ──\nworkflowCacheSize=2000, maxWorkflowThreadCount="
                + 정답5_선택한_threadCount());
        System.out.println("\n── 정답 6 ──\nA=" + 정답6_A() + " / B=" + 정답6_B()
                + " / C=" + 정답6_C());
        System.out.println("\n── 정답 7 ──");
        String[] steps = 정답7_진단_순서();
        for (int i = 0; i < steps.length; i++) {
            System.out.println("  " + (i + 1) + "단계: $ " + steps[i]);
        }
    }
}
