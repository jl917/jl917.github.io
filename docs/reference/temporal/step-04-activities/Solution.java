package com.example.order;

// =====================================================================================
// Step 04 — 액티비티 : Solution (6문제 정답 + 해설)
//
// 실행 방법
//   ./gradlew run -PmainClass=com.example.order.Solution
//
// Exercise.java 를 먼저 풀어 본 뒤 읽으세요.
// =====================================================================================

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.ActivityCompletionException;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Solution {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";
    public static final String MEDIA_TASK_QUEUE = "MEDIA_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // =================================================================================
    // 정답 1 — 시나리오별 타임아웃
    //
    // ┌───────────────────────────────────────────────────────────────────────────────┐
    // │ A. Worker 전멸 → **ScheduleToStart = 5분**                                     │
    // │                                                                                │
    // │   이 절의 핵심입니다. StartToClose 를 아무리 짧게 줘도 이 장애는 못 잡습니다.   │
    // │   StartToClose 는 "액티비티가 **시작한 뒤부터**" 재는 타이머인데, Worker 가     │
    // │   없으면 애초에 시작을 못 하므로 타이머가 돌지 않습니다.                        │
    // │                                                                                │
    // │   실제로 이렇게 됩니다.                                                        │
    // │     $ temporal task-queue describe --task-queue ORDER_TASK_QUEUE \             │
    // │         --task-queue-type activity                                             │
    // │     BuildId  TaskQueueType  Pollers  BacklogCount  ApproximateBacklogAge       │
    // │              ACTIVITY       0        84291         3h 42m 11s                  │
    // │                                                                                │
    // │   워크플로우는 전부 Running, 액티비티는 3시간 42분째 대기. 에러도 알림도 없음.  │
    // │   ScheduleToStart 5분이면 5분 만에 ActivityTaskTimedOut 이 터지고, 워크플로우   │
    // │   실패율 알림이 즉시 울립니다.                                                 │
    // │                                                                                │
    // │   ★ ScheduleToStart 는 성능 옵션이 아니라 **관측 장치**입니다.                 │
    // │   ★ 이 타임아웃은 재시도되지 않습니다(RETRY_STATE_NON_RETRYABLE_FAILURE).      │
    // │     재시도해도 어차피 같은 빈 큐에 다시 들어갈 뿐이기 때문입니다.               │
    // │   ★ 오타 난 Task Queue 이름도 이걸로 잡힙니다. 가장 흔한 신규 개발자 실수입니다.│
    // └───────────────────────────────────────────────────────────────────────────────┘
    //
    // ┌───────────────────────────────────────────────────────────────────────────────┐
    // │ B. 외부 API 무응답 → **StartToClose = 5초** (p99 1.2초 × 4배)                  │
    // │                                                                                │
    // │   StartToClose 는 "한 번의 시도"를 재므로 매달린 시도를 끊고 재시도합니다.      │
    // │   값은 p99 × 2~3배가 기본입니다. 여기서는 여유를 둬 4배.                        │
    // │   너무 짧으면 정상 요청까지 죽여 재시도 폭풍이 나고, 너무 길면 장애 감지가      │
    // │   늦습니다.                                                                    │
    // │   ScheduleToStart 로는 못 잡습니다 — 이미 시작한 액티비티이기 때문입니다.       │
    // └───────────────────────────────────────────────────────────────────────────────┘
    //
    // ┌───────────────────────────────────────────────────────────────────────────────┐
    // │ C. 배치 Worker OOM → **HeartbeatTimeout = 30초**                               │
    // │                                                                                │
    // │   StartToClose 2시간은 유지합니다(정상 실행에 필요하므로). 하트비트가 있으면    │
    // │   30초 만에 죽음을 감지합니다. **1시간 30분 → 30초.**                          │
    // │   덤으로 lastHeartbeatDetails 가 남아 재시도가 중단 지점부터 이어집니다.        │
    // │                                                                                │
    // │   ★ 값은 하트비트 주기의 2~3배로 잡습니다. 30초마다 뛰면 60~90초.              │
    // │   ★ HeartbeatTimeout 을 설정하지 않으면 heartbeat() 호출 자체가 무효입니다.    │
    // └───────────────────────────────────────────────────────────────────────────────┘
    //
    // ┌───────────────────────────────────────────────────────────────────────────────┐
    // │ D. 재시도 총량 제한 → **ScheduleToClose = 30분**                               │
    // │                                                                                │
    // │   ScheduleToClose 만이 "재시도를 전부 포함한 총 시간"을 잽니다.                 │
    // │   RetryOptions.setMaximumAttempts() 로도 제한할 수 있지만, 백오프 간격에 따라   │
    // │   총 시간이 들쭉날쭉합니다. **비즈니스 데드라인은 시간으로 거세요.**            │
    // │                                                                                │
    // │   터졌을 때 히스토리의 failure.cause 에 직전 시도의 실패 이유가 중첩됩니다.     │
    // │     "message": "activity ScheduleToClose timeout"                              │
    // │     "cause": { "message": "activity StartToClose timeout" }                    │
    // └───────────────────────────────────────────────────────────────────────────────┘
    // =================================================================================
    public static ActivityOptions sol1Options() {
        return ActivityOptions.newBuilder()
                .setScheduleToStartTimeout(Duration.ofMinutes(5))     // A
                .setStartToCloseTimeout(Duration.ofSeconds(5))        // B
                .setHeartbeatTimeout(Duration.ofSeconds(30))          // C (장기 액티비티에)
                .setScheduleToCloseTimeout(Duration.ofMinutes(30))    // D
                .build();
    }

    // =================================================================================
    // 정답 2 — 타임아웃 필수 규칙
    //
    //   예외 메시지
    //     io.temporal.failure.ApplicationFailure: message='Either ScheduleToCloseTimeout
    //     or StartToCloseTimeout is required', type='java.lang.IllegalStateException'
    //     Caused by: java.lang.IllegalStateException: Either ScheduleToCloseTimeout or
    //     StartToCloseTimeout is required
    //         at io.temporal.common.interceptors.ActivityOptionsUtils
    //              .validateAndBuildOptions(ActivityOptionsUtils.java:61)
    //
    //   Status: FAILED
    //
    //   왜 FAILED 가 되는가 (Step 03 의 NonDeterministicException 과의 대비)
    //     NonDeterministicException 은 SDK 가 "코드와 히스토리가 안 맞는다"고 판단한
    //     **인프라 수준의 문제**입니다. 코드를 고쳐 재배포하면 복구되므로 워크플로우를
    //     죽이지 않고 Workflow Task 만 무한 재시도합니다(Status: RUNNING).
    //
    //     반면 이 예외는 **워크플로우 코드가 던진 애플리케이션 예외**로 취급됩니다.
    //     SDK 입장에서는 "개발자가 잘못된 옵션을 넘겼다"이고, 재시도해도 결과가 같습니다.
    //     그래서 WorkflowExecutionFailed 로 닫습니다.
    //
    //   실무적 의미
    //     이 실수는 **로컬에서 한 번만 돌려 봐도 즉시 발견됩니다.** 워크플로우가 바로
    //     FAILED 로 끝나기 때문입니다. Step 03 의 결정성 위반이 무서운 이유는 정확히
    //     그 반대 — 로컬에서 100% 통과하고 운영 재배포 때만 터지기 때문입니다.
    // =================================================================================
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod(name = "Charge")
        String charge(String orderId, long amount);
    }

    public static class Sol2FixedWorkflowImpl implements OrderWorkflow {

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        // 최소한 이 둘 중 하나. 실무에서는 셋 다 주는 것이 원칙.
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .setScheduleToStartTimeout(Duration.ofMinutes(5))
                        .setScheduleToCloseTimeout(Duration.ofMinutes(30))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 정답 3 — heartbeat 와 재개
    //
    //   getHeartbeatDetails() 를 **반복문 밖에서 딱 한 번** 호출합니다.
    //
    //   왜 반복문 안에 넣으면 안 되는가
    //     (1) 이 값은 액티비티 시도가 시작될 때 서버가 ActivityTask 에 실어 함께
    //         내려 준 것입니다. 시도가 도는 동안 바뀌지 않습니다. 반복 호출은
    //         같은 값을 다시 읽는 낭비일 뿐입니다.
    //     (2) 더 나쁜 것은 로직 오류입니다. 반복문 안에서 start 를 다시 읽어 i 에
    //         대입하면 진행이 되돌아가 무한 루프가 됩니다.
    //
    //   하트비트 주기 계산
    //     건당 2ms × 1,000건 = 약 2초마다 heartbeat() 호출.
    //     SDK 는 HeartbeatTimeout 의 80% 간격으로 묶어 실제 전송하므로,
    //     HeartbeatTimeout 60초면 실제 gRPC 는 48초마다 한 번입니다.
    //     10만 건 = 100회 호출 → 실제 전송은 4~5회. 서버 부하가 아닙니다.
    //
    //     HeartbeatTimeout 은 하트비트 주기(2초)의 2~3배가 아니라, **여유롭게** 잡습니다.
    //     GC 정지나 일시적 지연으로 하트비트가 늦을 수 있기 때문입니다. 60초를 권장합니다.
    //
    //   StartToClose 계산
    //     10만 건 × 2ms = 200초. 여유를 둬 10분.
    //     ★ 하트비트가 있으면 StartToClose 를 넉넉히 줘도 안전합니다. 죽은 Worker 는
    //       하트비트가 잡아 주기 때문입니다. 하트비트가 없다면 StartToClose 를 짧게
    //       줘야 하는데, 그러면 정상 실행까지 죽이게 됩니다. 이 딜레마를 하트비트가 풉니다.
    // =================================================================================
    @ActivityInterface
    public interface BatchActivity {
        @ActivityMethod(name = "ProcessBatch")
        String processBatch(String batchId, int totalCount);
    }

    public static class Sol3BatchActivityImpl implements BatchActivity {

        private static final Logger log = LoggerFactory.getLogger(Sol3BatchActivityImpl.class);

        @Override
        public String processBatch(String batchId, int totalCount) {
            ActivityExecutionContext ctx = Activity.getExecutionContext();

            // ★ 반복문 밖에서 한 번만.
            int start = ctx.getHeartbeatDetails(Integer.class).orElse(0);
            int attempt = ctx.getInfo().getAttempt();
            log.info("배치 시작 batchId={} start={} total={} attempt={}",
                    batchId, start, totalCount, attempt);

            for (int i = start; i < totalCount; i++) {
                processOne(batchId, i);

                if (i % 1000 == 0) {
                    try {
                        ctx.heartbeat(i);
                    } catch (ActivityCompletionException e) {
                        // 취소 감지. ActivityCanceledException 이 여기로 온다.
                        // 취소 전달 경로는 하트비트뿐이라, 하트비트가 없는 액티비티는
                        // 취소 요청이 와도 끝까지 실행된다.
                        log.info("취소 감지 — {}건 처리 후 정리하고 종료", i);
                        cleanup(batchId, i);
                        throw e;   // 반드시 다시 던져야 서버가 ActivityTaskCanceled 로 기록한다
                    }
                }
            }
            return batchId + " DONE " + totalCount;
        }

        private void processOne(String batchId, int i) {}

        private void cleanup(String batchId, int processed) {
            log.info("부분 작업 정리 batchId={} processed={}", batchId, processed);
        }
    }

    public static ActivityOptions sol3Options() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(10))    // 10만 건 × 2ms = 200초 + 여유
                .setHeartbeatTimeout(Duration.ofSeconds(60))       // 2초마다 뛰는데 60초 여유
                .setScheduleToStartTimeout(Duration.ofMinutes(5))
                .build();
    }

    // =================================================================================
    // 정답 4 — 멱등성
    //
    //  (a) Set 접근이 실패하는 세 가지 이유
    //
    //      1. **Worker 가 여러 대면 Set 이 공유되지 않는다.**
    //         첫 시도를 Worker A 가, 재시도를 Worker B 가 집어 갑니다. B 의 Set 은 비어
    //         있으므로 그대로 결제합니다. 운영은 Worker 를 항상 여러 대 띄웁니다.
    //
    //      2. **Worker 재시작이면 비어 있다.**
    //         Set 은 프로세스 메모리입니다. 배포·OOM·스케일인으로 프로세스가 바뀌면
    //         전부 사라집니다. 그리고 재시도가 필요한 상황은 대개 Worker 가 죽은 상황입니다.
    //         즉 **가장 필요한 순간에 반드시 비어 있습니다.**
    //
    //      3. **애초에 액티비티는 무상태여야 한다.**
    //         Worker 는 액티비티 인스턴스 하나를 최대 200 스레드가 공유합니다.
    //         HashSet 은 스레드 안전하지도 않아 동시 쓰기에 데이터가 깨집니다.
    //
    //      추가로, orderId 를 키로 쓴 것도 문제입니다. 같은 주문에 대해 부분 환불 후
    //      재결제 같은 정당한 재시도가 필요할 수 있는데, 그것까지 막아 버립니다.
    //      키는 **"이 액티비티 호출"** 단위여야지 **"이 주문"** 단위가 아닙니다.
    //
    //  (b) 멱등키는 **워크플로우**에서 만듭니다.
    //
    //      이유는 히스토리에 있습니다. 워크플로우가 만들어 액티비티 인자로 넘기면,
    //      그 값이 ActivityTaskScheduled 이벤트의 input 에 기록됩니다.
    //
    //        "input": { "payloads": [
    //            { "data": "IjEwMDEi" },                                   ← orderId
    //            { "data": "MzkwMDA=" },                                   ← amount
    //            { "data": "IjNmMmE5YzE0LTdiMDEtNDhkNC1hZWY2LTkwMGMxZDJmODhhNCI=" } ← 멱등키
    //        ] }
    //
    //      ActivityTaskScheduled 는 재시도할 때 **새로 생기지 않습니다.** 서버가 같은
    //      이벤트의 input 으로 다시 디스패치할 뿐이라, 몇 번을 재시도해도 액티비티는
    //      같은 키를 받습니다.
    //
    //      액티비티 안에서 UUID 를 만들면 시도마다 새 키가 되어 아무 의미가 없습니다.
    //
    //      그리고 반드시 **Workflow.randomUUID()** 여야 합니다.
    //      UUID.randomUUID() 는 Worker 재시작으로 리플레이가 일어날 때 값이 바뀝니다.
    //      (Step 03 의 3-7 함정 참조)
    //
    //  (c) 올바른 구현 — 아래 두 가지 중 하나
    //      1. 외부 API 의 Idempotency-Key 헤더 (PG, 이메일 발송 등 대부분 지원)
    //      2. DB 유니크 제약 + INSERT ... ON CONFLICT DO NOTHING (내부 시스템)
    // =================================================================================
    @ActivityInterface
    public interface Sol4PaymentActivity {
        @ActivityMethod(name = "ChargeIdempotent")
        String chargeIdempotent(String orderId, long amount, String idempotencyKey);
    }

    public static class Sol4PaymentActivityImpl implements Sol4PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(Sol4PaymentActivityImpl.class);

        // 스레드 안전한 불변 의존성만 필드에 둔다.
        private final HttpClient http;

        public Sol4PaymentActivityImpl(HttpClient http) {
            this.http = http;
        }

        @Override
        public String chargeIdempotent(String orderId, long amount, String idempotencyKey) {
            log.info("charge orderId={} amount={} key={}", orderId, amount, idempotencyKey);

            // 방법 1 — PG 의 Idempotency-Key 헤더
            //   HttpRequest req = HttpRequest.newBuilder()
            //           .uri(URI.create("https://pg.example.com/v1/charges"))
            //           .header("Idempotency-Key", idempotencyKey)
            //           .POST(...)
            //           .build();
            //   같은 키가 두 번 오면 PG 가 저장된 첫 응답을 그대로 돌려준다. 돈은 한 번만.

            // 방법 2 — 내부 DB 라면 유니크 제약
            //   INSERT INTO payments (idempotency_key, order_id, amount, status)
            //   VALUES (?, ?, ?, 'CHARGED')
            //   ON CONFLICT (idempotency_key) DO NOTHING
            //   RETURNING payment_id;
            //   → 0행이 돌아오면 이미 처리된 것. 기존 payment_id 를 조회해 반환한다.

            return "PAY-" + idempotencyKey.substring(0, 8);
        }
    }

    public static class Sol4WorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Sol4WorkflowImpl.class);

        private final Sol4PaymentActivity payment = Workflow.newActivityStub(
                Sol4PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .setScheduleToCloseTimeout(Duration.ofMinutes(30))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            // ★ Workflow.randomUUID() — Run ID 시드. 리플레이해도 같은 값.
            String idemKey = Workflow.randomUUID().toString();
            log.info("[{}] 멱등키 발급 {}", req.orderId(), idemKey);

            String paymentId = payment.chargeIdempotent(req.orderId(), req.amount(), idemKey);
            log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 정답 5 — Activity / Local Activity 분류
    //
    //  (1) 주소 정규화 → **Local Activity**
    //      순수 문자열 처리, 0.1ms, 외부 호출 없음. 재시도해도 부담 없음.
    //      MarkerRecorded 1개로 끝나므로 히스토리 비용이 1/3 이하.
    //
    //  (2) 이미지 리사이징 → **Activity** (그리고 Task Queue 분리)
    //      45초는 Workflow Task Timeout(기본 10초)을 훨씬 넘습니다. Local Activity 로
    //      하면 SDK 가 Workflow Task 를 계속 연장하려다 히스토리만 지저분해지고,
    //      Worker 가 죽으면 진행 상황이 통째로 사라집니다.
    //      Activity 로 하면 하트비트로 진행 상황을 저장하고, 죽어도 이어서 할 수 있습니다.
    //
    //      Task Queue 분리가 필요한 이유
    //        이미지 변환은 CPU·메모리를 크게 씁니다. 결제 액티비티와 같은 Worker 에서
    //        돌면 배치 하나가 결제 전체를 굶깁니다(maxConcurrentActivityExecutionSize 를
    //        점유). Worker 가 OOM 으로 죽으면 그 위의 결제 액티비티도 함께 죽습니다.
    //
    //        // 워크플로우 코드에서 액티비티별로 다른 Task Queue 를 지정
    //        Workflow.newActivityStub(MediaActivity.class,
    //            ActivityOptions.newBuilder()
    //                .setTaskQueue("MEDIA_TASK_QUEUE")
    //                .setStartToCloseTimeout(Duration.ofMinutes(30))
    //                .setHeartbeatTimeout(Duration.ofSeconds(30))
    //                .build());
    //
    //        그리고 고사양 인스턴스에 MEDIA_TASK_QUEUE 만 폴링하는 Worker 를 따로 띄웁니다.
    //        워크플로우 코드는 한 줄도 안 바뀝니다.
    //
    //  (3) 쿠폰 코드 형식 검증 → **Local Activity**
    //      (1)과 같은 이유. 사실 이 정도면 **워크플로우 코드에 직접 써도** 됩니다.
    //      정규식 매칭은 결정적이므로 액티비티로 뺄 이유가 없습니다.
    //      액티비티로 빼는 것은 "결정적이지 않거나, 실패할 수 있거나, 느릴 때"입니다.
    //
    //  (4) 결제 → **Activity**
    //      외부 API + 돈이 움직임. 재시도·타임아웃·가시성이 전부 필요합니다.
    //      Local Activity 는 재시도가 Worker 메모리에서 관리되어 히스토리에 안 남고,
    //      Worker 가 죽으면 재시도 상태가 사라집니다. 돈이 걸린 일에 쓸 수 없습니다.
    //
    //  (5) 재고 조회 → **Activity 권장** (판단이 갈리는 항목)
    //
    //      Local Activity 를 고른 근거도 타당합니다: 읽기 전용, 30ms, 재시도 부담 없음.
    //      그러나 **일반 Activity 를 권합니다.**
    //
    //        - 외부 시스템(재고 서비스)을 건드립니다. 그 서비스가 장애일 때 재시도
    //          횟수·간격·타임아웃이 히스토리에 남아야 원인 분석이 됩니다.
    //          Local Activity 는 재시도가 Worker 메모리에서만 일어나 흔적이 없습니다.
    //        - 30ms 는 정상일 때의 값입니다. 장애 시에는 30초가 됩니다. 그러면
    //          Workflow Task Timeout 을 넘겨 문제가 됩니다.
    //        - 재고 서비스가 느려질 때 Task Queue 를 분리해 격리할 여지가 필요합니다.
    //
    //      기준을 한 문장으로: **"외부 시스템을 건드리면 일반 Activity."**
    //      Local Activity 는 "이 Worker 프로세스 안에서 완결되는 순수 계산"에만 씁니다.
    // =================================================================================
    @ActivityInterface
    public interface Sol5ValidationActivity {
        @ActivityMethod(name = "NormalizeAddress") String normalizeAddress(String address);
        @ActivityMethod(name = "ValidateCoupon") boolean validateCoupon(String code);
    }

    public static class Sol5WorkflowImpl implements OrderWorkflow {

        // (1)(3) 순수 계산 → Local Activity
        private final Sol5ValidationActivity validation = Workflow.newLocalActivityStub(
                Sol5ValidationActivity.class,
                LocalActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());

        // (4) 결제 → 일반 Activity
        private final Sol4PaymentActivity payment = Workflow.newActivityStub(
                Sol4PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .setScheduleToStartTimeout(Duration.ofMinutes(5))
                        .setScheduleToCloseTimeout(Duration.ofMinutes(30))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            String addr = validation.normalizeAddress(req.address());
            String key = Workflow.randomUUID().toString();
            payment.chargeIdempotent(req.orderId(), req.amount(), key);
            return req.orderId() + " COMPLETED " + addr;
        }
    }

    // =================================================================================
    // 정답 6 — 스레드 안전 문제 세 군데
    //
    //  [문제 1] private String currentOrderId;
    //      요청별 상태를 인스턴스 필드에 담았습니다. Worker 는 이 인스턴스 하나를
    //      최대 200 스레드가 동시에 호출합니다.
    //
    //        스레드 A: currentOrderId = "1001"
    //        스레드 B: currentOrderId = "1002"     ← 덮어씀
    //        스레드 A: return "PAY-" + currentOrderId   → "PAY-1002"  ★ 남의 주문
    //
    //      **다른 고객의 주문 ID 로 결제 결과가 나갑니다.** 재현이 어렵고, 부하가
    //      높을 때만 나타나며, 로그만 봐서는 원인을 찾기 힘든 최악의 버그입니다.
    //      → 지역 변수와 파라미터만 씁니다.
    //
    //  [문제 2] private final SimpleDateFormat fmt = new SimpleDateFormat(...);
    //      SimpleDateFormat 은 **스레드 안전하지 않습니다.** 내부에 Calendar 를 필드로
    //      들고 있어 동시 호출 시 서로의 중간 상태를 읽습니다. 결과는
    //        - 날짜가 뒤섞인 문자열 ("2026-03-11 09:2026")
    //        - NumberFormatException / ArrayIndexOutOfBoundsException 이 무작위로
    //      final 이라 안전해 보이는 것이 함정입니다. **참조가 불변일 뿐 객체는 가변**입니다.
    //      → java.time.format.DateTimeFormatter 는 불변이고 스레드 안전합니다.
    //        ZoneId 도 명시하세요. 안 하면 컨테이너 TZ 에 따라 결과가 달라집니다.
    //
    //  [문제 3] if (client == null) { client = HttpClient.newHttpClient(); }
    //      지연 초기화(lazy init)에 동기화가 없습니다. 두 스레드가 동시에 null 체크를
    //      통과하면 HttpClient 가 두 개 만들어지고, 하나는 참조를 잃은 채 커넥션 풀과
    //      셀렉터 스레드를 그대로 들고 있습니다. 요청이 몰릴수록 이 누수가 쌓입니다.
    //      더 나쁜 것은 client 가 volatile 도 아니라, 다른 스레드가 **부분적으로 초기화된
    //      객체**를 볼 수 있다는 점입니다.
    //      → 생성자 주입으로 바꿉니다. 액티비티 인스턴스는 Worker 등록 시 한 번만
    //        만들어지므로 지연 초기화를 할 이유가 없습니다.
    //
    //  정리: 액티비티 구현체의 필드에는 **불변이거나 스레드 안전한 것만** 둡니다.
    //        HttpClient, DateTimeFormatter, Spring Repository, DataSource 는 안전.
    //        SimpleDateFormat, StringBuilder, HashMap, 요청별 상태는 위험.
    // =================================================================================
    public static class Sol6SafeActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(Sol6SafeActivityImpl.class);

        // 불변 + 스레드 안전한 의존성만. 전부 생성자 주입.
        private final HttpClient http;
        private final DateTimeFormatter fmt;

        public Sol6SafeActivityImpl(HttpClient http) {
            this.http = http;
            this.fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Seoul"));
        }

        @Override
        public String charge(String orderId, long amount) {
            // 모든 상태는 지역 변수로
            String at = fmt.format(Instant.now());
            log.info("charge orderId={} amount={} at={}", orderId, amount, at);
            return "PAY-" + orderId;
        }
    }

    public static void main(String[] args) {
        System.out.println("Solution — 각 정답 클래스를 Worker 에 등록해 실행하세요.");
        System.out.println("정답 1 과 정답 5 의 해설은 주석에 있습니다.");
    }
}
