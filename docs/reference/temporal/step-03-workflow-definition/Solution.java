package com.example.order;

// =====================================================================================
// Step 03 — 워크플로우 정의와 결정성 : Solution (6문제 정답 + 해설)
//
// 실행 방법
//   ./gradlew run -PmainClass=com.example.order.Solution
//
// 각 정답 앞에 "왜 그런지"를 설명하는 긴 주석이 붙어 있습니다.
// Exercise.java 를 먼저 풀어 본 뒤 읽으세요.
// =====================================================================================

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class Solution {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String chargeIdempotent(String orderId, long amount, String idempotencyKey);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
    }

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // =================================================================================
    // 정답 1 — 위반 7군데
    //
    //  [위반 1] private static final Logger log = LoggerFactory.getLogger(...)
    //      → 결정성을 직접 깨지는 않지만, 리플레이할 때마다 같은 로그가 다시 찍힌다.
    //        히스토리 5,000 이벤트짜리 워크플로우를 리플레이하면 수천 줄이 한꺼번에 재출력되어
    //        운영 로그를 오염시키고, "이 주문이 왜 두 번 처리됐지?" 하는 오진을 부른다.
    //        Workflow.getLogger() 는 Workflow.isReplaying() 이 true 인 동안 출력을 억제한다.
    //
    //  [위반 2] private static int TOTAL_ORDERS = 0;  /  TOTAL_ORDERS++
    //      → static 필드는 Worker 프로세스에 하나뿐이고, 그 프로세스에서 동시에 도는
    //        수백 개의 워크플로우가 공유한다. 리플레이는 다른 Worker·다른 시각에 일어나므로
    //        그때 값은 0 이거나 전혀 다른 워크플로우가 올려 둔 값이다.
    //
    //  [위반 3] String idemKey = UUID.randomUUID().toString();
    //      → 가장 위험하다. 아래 "정답 2 의 결제 이중화 시나리오" 참조.
    //
    //  [위반 4] Instant now = Instant.now();
    //      → 벽시계. 첫 실행과 리플레이의 시각이 다르다.
    //
    //  [위반 5] Map<String,Integer> items = new HashMap<>();  → entrySet() 순회
    //      → HashMap 순회 순서는 명세상 보장되지 않는다. JDK 구현이 바뀌면 달라질 수 있고
    //        (JDK 7→8 에 전례가 있다) 순회 순서가 곧 ActivityTaskScheduled 의 순서다.
    //        Command 순서가 뒤바뀌면 히스토리와 어긋난다.
    //
    //  [위반 6] String carrier = Math.random() < 0.5 ? "CJ" : "LOTTE";
    //      → 이 값이 액티비티 인자로 흘러간다. 리플레이 때 다른 값이면 히스토리에 기록된
    //        입력과 달라진다.
    //
    //  [위반 7] String region = System.getenv("SHIPPING_REGION");
    //      → Worker 마다 다를 수 있다. 재배포하며 환경변수를 바꾸면 분기와 인자가 달라진다.
    //        설정값은 워크플로우 인자로 받거나 액티비티 반환값으로 받아야 히스토리에 남는다.
    //
    //  ※ `weekend` 분기(now.toString().contains("T00"))는 위반 4 의 결과이지 별도 위반이 아니다.
    //     위반의 개수를 셀 때는 "비결정적 입력을 만드는 지점"을 세는 것이 맞다.
    // =================================================================================

    // =================================================================================
    // 정답 2 — 결정적으로 고친 워크플로우
    //
    // ★ 가장 중요한 한 줄: UUID.randomUUID() → Workflow.randomUUID()
    //
    //   이 값을 잘못 고치면 "결제가 두 번 되는" 경로가 열립니다. 타임라인으로 봅니다.
    //
    //     09:12:04  워크플로우 시작. UUID.randomUUID() → "aaa-111"
    //     09:12:04  chargeIdempotent(orderId, 39000, "aaa-111") 스케줄
    //               히스토리 5: ActivityTaskScheduled  input=["1001", 39000, "aaa-111"]
    //     09:12:05  결제 게이트웨이가 "aaa-111" 로 39,000원 승인. 성공.
    //     09:12:05  히스토리 7: ActivityTaskCompleted
    //     ...
    //     09:40:00  Worker 재배포. 프로세스 사망.
    //     09:41:12  새 Worker 가 리플레이 시작.
    //               UUID.randomUUID() → "bbb-222"  ← 다른 값!
    //               코드는 chargeIdempotent(orderId, 39000, "bbb-222") 로 Command 를 만든다.
    //
    //   SDK 는 activityType 과 순서를 먼저 검사하므로 여기서 바로 예외가 나지 않을 수도 있습니다.
    //   문제는 그 다음입니다. 히스토리가 소진된 뒤 워크플로우가 새로 액티비티를 부를 때,
    //   혹은 액티비티가 타임아웃으로 재시도될 때 **결제 게이트웨이 입장에서는 처음 보는 키**가
    //   도착합니다. 게이트웨이는 이를 새 결제 요청으로 처리합니다. → 39,000원이 두 번 빠집니다.
    //
    //   Workflow.randomUUID() 는 Run ID 를 시드로 하므로 몇 번을 리플레이해도 "aaa-111" 입니다.
    //   멱등키는 반드시 이쪽입니다.
    // =================================================================================
    public static class Sol2FixedWorkflowImpl implements OrderWorkflow {

        // [위반 1 수정] 리플레이 중 출력을 억제하는 로거
        private static final Logger log = Workflow.getLogger(Sol2FixedWorkflowImpl.class);

        // [위반 2 수정] static → 인스턴스 필드.
        //   워크플로우 인스턴스는 실행마다 새로 만들어지고, 리플레이하면 같은 코드 경로를
        //   다시 밟으므로 같은 값이 재계산된다. 이것이 "워크플로우 상태"의 올바른 자리다.
        private int processedCount = 0;

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {

            // [위반 3 수정] Run ID 시드의 결정적 UUID
            String idemKey = Workflow.randomUUID().toString();

            // [위반 4 수정] 현재 Workflow Task 시작 시각 → 리플레이해도 같다
            Instant now = Instant.ofEpochMilli(Workflow.currentTimeMillis());
            boolean weekend = now.toString().contains("T00");

            // [위반 2 수정]
            processedCount++;

            // [위반 5 수정] 순회 순서가 보장되는 LinkedHashMap.
            //   TreeMap(키 정렬)이나 정렬된 List 도 좋다. 핵심은 "순서가 코드로 정해진다"는 것.
            Map<String, Integer> items = new LinkedHashMap<>();
            items.put("SKU-A", req.qty());
            items.put("SKU-B", 1);
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                inventory.reserve(req.orderId(), e.getKey(), e.getValue());
            }

            // [위반 6 수정] Run ID 시드의 Random. 리플레이하면 같은 수열이 나온다.
            Random rnd = Workflow.newRandom();
            String carrier = rnd.nextInt(100) < 50 ? "CJ" : "LOTTE";

            // [위반 7 수정] 설정값은 워크플로우 인자로 받는다.
            //   진짜로 외부 설정 서버를 읽어야 한다면 액티비티로 만들고 반환값을 쓴다.
            //   그래야 값이 ActivityTaskCompleted 에 기록되어 리플레이 때 재사용된다.
            String region = req.address();

            payment.chargeIdempotent(req.orderId(), req.amount(), idemKey);

            if (!weekend) {
                shipping.requestShipment(req.orderId(), region + "/" + carrier);
            }

            log.info("[{}] 처리 완료 count={}", req.orderId(), processedCount);
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 정답 3 — @WorkflowMethod 는 하나. 나머지는 Signal 과 Query 다.
    //
    //   @WorkflowMethod 는 "이 워크플로우 실행을 시작하는 진입점"입니다. 워크플로우를 시작하는
    //   방법이 두 가지일 수는 없으므로 하나뿐인 것이 당연합니다. 두 개를 선언하면 SDK 가
    //   "클라이언트가 start() 했을 때 어느 메서드를 부를지" 결정할 수 없습니다.
    //
    //   문제의 세 메서드는 사실 서로 다른 세 가지 상호작용입니다.
    //     processOrder  — 시작       → @WorkflowMethod
    //     applyCoupon   — 밀어넣기   → @SignalMethod  (비동기, 반환값 없음, 상태를 바꾼다)
    //     currentStatus — 조회       → @QueryMethod   (동기, 반환값 있음, 상태를 바꾸면 안 된다)
    //
    //   메서드를 지우는 것은 오답입니다. 의도를 잃어버리기 때문입니다.
    //   Signal / Query 의 상세는 Step 07 에서 다룹니다.
    // =================================================================================
    @WorkflowInterface
    public interface Sol3FixedWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);

        @SignalMethod
        void applyCoupon(String couponCode);

        @QueryMethod
        String currentStatus();
    }

    public static class Sol3FixedWorkflowImpl implements Sol3FixedWorkflow {

        private String status = "STARTED";
        private String coupon = null;

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            status = "WAITING_COUPON";
            // 쿠폰이 오거나 10초가 지날 때까지 대기 (Step 06 에서 자세히)
            Workflow.await(Duration.ofSeconds(10), () -> coupon != null);

            long amount = coupon != null ? req.amount() - 1000 : req.amount();
            status = "CHARGING";
            payment.chargeIdempotent(req.orderId(), amount, Workflow.randomUUID().toString());

            status = "COMPLETED";
            return req.orderId() + " COMPLETED";
        }

        @Override
        public void applyCoupon(String couponCode) {
            // Signal 핸들러도 워크플로우 코드다. 여기서도 결정성 규칙이 그대로 적용된다.
            this.coupon = couponCode;
        }

        @Override
        public String currentStatus() {
            // Query 핸들러는 상태를 **바꾸면 안 된다**. 읽기만 할 것.
            // Query 는 히스토리에 이벤트를 남기지 않으므로, 여기서 상태를 바꾸면
            // 그 변경이 리플레이 때 재현되지 않아 결정성이 깨진다.
            return status;
        }
    }

    // =================================================================================
    // 정답 4 — mutableSideEffect 를 씁니다.
    //
    //   sideEffect 를 고르면 두 가지가 잘못됩니다.
    //
    //   (1) 히스토리가 부풉니다.
    //       sideEffect 는 **호출할 때마다** MarkerRecorded 이벤트를 하나씩 남깁니다.
    //       반복문이 10회면 Marker 10개, 며칠짜리 워크플로우에서 6시간마다 읽으면
    //       한 달에 120개가 쌓입니다. 히스토리 크기는 Workflow Task 처리 시간에 직결됩니다.
    //
    //   (2) 값이 첫 결과에 영원히 고정됩니다.
    //       sideEffect 의 계약은 "람다는 첫 실행에만 돌고, 이후에는 기록된 값을 돌려준다"입니다.
    //       같은 코드 지점의 sideEffect 는 매번 새로 실행되긴 하지만(리플레이가 아닐 때),
    //       "플래그가 바뀌었는지"를 감지하는 장치가 없어 히스토리만 늘어납니다.
    //
    //   mutableSideEffect 는 정확히 이 상황을 위해 있습니다.
    //     - 매 실행마다 람다를 돌려 최신값을 읽는다 (리플레이 중에는 돌리지 않는다)
    //     - 직전에 기록된 값과 비교해 **다를 때만** MarkerRecorded 를 남긴다
    //     - 리플레이 때는 Marker 에 기록된 "그 시점의 값"을 돌려준다 → 결정적
    //
    //   측정: 10회 호출 중 플래그가 한 번 바뀌었다면
    //         sideEffect → MarkerRecorded 10개 / mutableSideEffect → 2개.
    // =================================================================================
    public static class FeatureFlags {
        static boolean isNewPricingEnabled() {
            return true;
        }
    }

    public static class Sol4FlagWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Sol4FlagWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            for (int i = 0; i < 10; i++) {

                // ✔ mutableSideEffect — id "newPricing" 으로 값을 추적한다.
                //   세 번째 인자는 "이전 값과 같은가"를 판정하는 함수다.
                //   같다고 판정되면 새 Marker 를 남기지 않는다.
                boolean newPricing = Workflow.mutableSideEffect(
                        "newPricing",
                        Boolean.class,
                        (a, b) -> a.equals(b),
                        FeatureFlags::isNewPricingEnabled);

                log.info("[{}] round={} newPricing={}", req.orderId(), i, newPricing);
                Workflow.sleep(Duration.ofHours(6));
            }
            return req.orderId() + " FLAG";
        }
    }

    // =================================================================================
    // 정답 5 — 병렬화와 히스토리 이벤트 수 예측
    //
    //   순차 버전의 이벤트 구성 (액티비티 3개)
    //     1  WorkflowExecutionStarted
    //     2  WorkflowTaskScheduled
    //     3  WorkflowTaskStarted
    //     4  WorkflowTaskCompleted
    //     5  ActivityTaskScheduled   (charge)
    //     6  ActivityTaskStarted
    //     7  ActivityTaskCompleted
    //     8  WorkflowTaskScheduled   ← 액티비티가 끝나 워크플로우를 깨우는 왕복
    //     9  WorkflowTaskStarted
    //    10  WorkflowTaskCompleted
    //    11  ActivityTaskScheduled   (reserve)
    //    12  ActivityTaskStarted
    //    13  ActivityTaskCompleted
    //    14  WorkflowTaskScheduled
    //    15  WorkflowTaskStarted
    //    16  WorkflowTaskCompleted
    //    17  ActivityTaskScheduled   (requestShipment)
    //    18  ActivityTaskStarted
    //    19  ActivityTaskCompleted
    //    20  WorkflowTaskScheduled
    //    21  WorkflowTaskStarted
    //    22  WorkflowTaskCompleted + WorkflowExecutionCompleted
    //   → History Length 22 (마지막 Completed 2개를 따로 세면 23)
    //
    //   병렬 버전
    //     1~4  시작 + 첫 Workflow Task
    //     5,6,7  ActivityTaskScheduled × 3  ← Command 3개가 **하나의 Workflow Task** 에 담긴다
    //     8~10   ActivityTaskStarted × 3
    //    11~13   ActivityTaskCompleted × 3
    //    14      WorkflowTaskScheduled
    //    15      WorkflowTaskStarted
    //    16      WorkflowTaskCompleted + WorkflowExecutionCompleted
    //   → History Length 약 14~17
    //
    //   핵심은 **Workflow Task 왕복이 3회 → 1회로 줄어든다**는 것입니다.
    //   왕복 한 번은 Scheduled/Started/Completed 3 이벤트 + 서버 왕복 지연(수십 ms)입니다.
    //   액티비티가 N개 독립이면 왕복이 N회 → 1회가 되므로 이득이 선형으로 커집니다.
    //
    //   실측: 순차 3.31초 → 병렬 1.12초. 히스토리 22개 → 15개.
    // =================================================================================
    public static class Sol5ParallelWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Sol5ParallelWorkflowImpl.class);

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            String key = Workflow.randomUUID().toString();

            // Async.function 은 즉시 반환한다. 아직 서버로 나가지 않았고,
            // Command 가 현재 Workflow Task 의 버퍼에 쌓일 뿐이다.
            Promise<String> pay = Async.function(
                    payment::chargeIdempotent, req.orderId(), req.amount(), key);
            Promise<String> res = Async.function(
                    inventory::reserve, req.orderId(), req.sku(), req.qty());
            Promise<String> shp = Async.function(
                    shipping::requestShipment, req.orderId(), req.address());

            // 여기서 처음으로 양보(yield)한다. 이때 버퍼의 Command 3개가 한꺼번에 서버로 나간다.
            Promise.allOf(pay, res, shp).get();

            log.info("[{}] 결제={} 예약={} 배송={}",
                    req.orderId(), pay.get(), res.get(), shp.get());
            return req.orderId() + " PAR";
        }
    }

    public static void main(String[] args) {
        System.out.println("Solution — 각 정답 클래스를 Worker 에 등록해 실행하세요.");
        System.out.println("문제 6 의 답은 파일 하단 주석에 있습니다.");
    }
}

// =====================================================================================
// 정답 6 — 스택트레이스 역추적
//
//  (a) 원인이 되는 줄: **06번 줄**
//         if (LocalDate.now().getDayOfWeek() == DayOfWeek.SATURDAY)
//
//      LocalDate.now() 는 시스템 벽시계를 읽습니다. 워크플로우에서 금지된 API 입니다.
//      결정적 대체는 다음과 같습니다.
//
//        Instant.ofEpochMilli(Workflow.currentTimeMillis())
//               .atZone(ZoneId.of("Asia/Seoul"))
//               .toLocalDate()
//               .getDayOfWeek() == DayOfWeek.SATURDAY
//
//      ※ ZoneId 를 명시하는 것도 중요합니다. ZoneId.systemDefault() 는 Worker 컨테이너의
//        TZ 환경변수에 따라 달라지므로, 컨테이너 이미지를 바꾸면 같은 문제가 재발합니다.
//
//  (b) 첫 실행: **토요일 분기(07번 줄)** 를 탔습니다.
//      근거 ── 히스토리 5번 이벤트가 EVENT_TYPE_TIMER_STARTED 입니다.
//              07번 줄의 Workflow.sleep(Duration.ofHours(24)) 만이 TimerStarted 를 만듭니다.
//              08/10번 줄의 액티비티 호출은 ActivityTaskScheduled 를 만듭니다.
//
//  (c) 리플레이: **else 분기(10번 줄)** 를 탔습니다.
//      근거 ── 에러 메시지의
//                Command CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK
//              즉 "코드가 액티비티 스케줄을 요청했다"입니다. 5번 자리에서 액티비티를 스케줄하는
//              경로는 else 분기뿐입니다. Worker 를 일요일에 재배포했다는 뜻입니다.
//
//      읽는 순서를 정리하면 이렇습니다.
//        1. "Failure handling event 5 of type 'EVENT_TYPE_TIMER_STARTED'"
//           → 히스토리 5번은 타이머. 첫 실행은 sleep 을 했다.
//        2. "Command CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK doesn't match"
//           → 지금 코드는 그 자리에서 액티비티를 부르려 한다.
//        3. 두 경로가 갈리는 지점을 코드에서 찾는다 → 06번 줄의 if.
//        4. 그 조건식에 비결정적 입력이 있는지 본다 → LocalDate.now().
//
//  (d) Status 가 RUNNING 인 이유
//      NonDeterministicException 은 **Workflow Task 를 실패시킬 뿐 워크플로우를 실패시키지
//      않습니다.** Workflow Task 는 기본적으로 무한 재시도(지수 백오프, 최대 간격 60초)되므로
//      Attempt 만 계속 올라갑니다(19까지 갔습니다).
//
//      이것은 버그이자 기능입니다. 코드 버그로 워크플로우를 FAILED 로 만들어 버리면 진행 중인
//      결제·재고 예약이 전부 날아가고 되돌릴 방법이 없습니다. 대신 멈춰 두면 개발자가 코드를
//      고쳐 배포하는 순간 그 지점부터 이어서 진행합니다. 위 워크플로우도 06번 줄만 고쳐
//      재배포하면 History Length 8 에서 멈춰 있던 상태 그대로 재개됩니다.
//
//      운영에서는 이 상태를 반드시 알림으로 잡아야 합니다. 워크플로우가 "살아 있는데 아무것도
//      안 하는" 상태라 헬스체크로는 안 보입니다. Step 12 에서 다룹니다.
//        temporal workflow list --query 'ExecutionStatus="Running"' 로 목록을 뽑고
//        describe 의 Pending Workflow Task Attempt > 3 인 것을 걸러 내는 방식이 표준입니다.
//
//  (e) 04번 줄은 위반이 아닙니다.
//         long deadline = Workflow.currentTimeMillis() + 86_400_000L;
//      Workflow.currentTimeMillis() 는 현재 Workflow Task 가 시작된 시각을 히스토리에서 읽어
//      오므로 리플레이해도 같은 값입니다. 여기에 상수를 더한 것도 당연히 결정적입니다.
//      13~17번 줄의 LinkedHashMap 순회도 순서가 보장되므로 안전합니다.
// =====================================================================================
