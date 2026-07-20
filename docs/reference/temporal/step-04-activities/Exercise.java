package com.example.order;

// =====================================================================================
// Step 04 — 액티비티 : Exercise (6문제)
//
// 실행 방법
//   ./gradlew run -PmainClass=com.example.order.Exercise
//
// 규칙
//   - `// TODO: 여기에 작성` 자리를 채우세요.
//   - 문제 1 과 문제 5 는 코드를 거의 쓰지 않습니다. 표와 근거를 주석으로 채우세요.
//   - 정답은 Solution.java 에 있습니다.
// =====================================================================================

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class Exercise {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod(name = "Charge") String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface BatchActivity {
        @ActivityMethod(name = "ProcessBatch") String processBatch(String batchId, int totalCount);
    }

    // =================================================================================
    // 문제 1 — 네 가지 장애 시나리오에 필요한 타임아웃을 고르고 값을 정하세요.
    //
    // 각 시나리오에 대해
    //   (1) 어떤 타임아웃이 이 장애를 잡아내는가?
    //   (2) 값을 몇으로 줄 것인가?
    //   (3) 왜 다른 타임아웃으로는 못 잡는가?
    // 를 주석으로 답하세요.
    //
    // ┌─────────────────────────────────────────────────────────────────────────────┐
    // │ 시나리오 A — 배포 사고로 Worker 가 전부 죽었다.                              │
    // │   액티비티 태스크가 Task Queue 에 계속 쌓인다. 워크플로우는 전부 Running.     │
    // │   에러도 알림도 없다. 고객이 "결제가 안 됐다"고 문의해서야 알게 됐다.         │
    // │                                                                              │
    // │   타임아웃: ______   값: ______                                              │
    // │   TODO: 여기에 작성                                                          │
    // └─────────────────────────────────────────────────────────────────────────────┘
    //
    // ┌─────────────────────────────────────────────────────────────────────────────┐
    // │ 시나리오 B — 외부 결제 API 가 응답을 안 준다.                                │
    // │   평소 p99 는 1.2초인데, 장애 때는 소켓이 열린 채 영원히 매달린다.            │
    // │                                                                              │
    // │   타임아웃: ______   값: ______                                              │
    // │   TODO: 여기에 작성                                                          │
    // └─────────────────────────────────────────────────────────────────────────────┘
    //
    // ┌─────────────────────────────────────────────────────────────────────────────┐
    // │ 시나리오 C — 2시간짜리 배치 액티비티를 도는 Worker 가 30분 만에 OOM 으로 죽음.│
    // │   서버는 이 사실을 모르고 1시간 30분을 기다린다.                             │
    // │                                                                              │
    // │   타임아웃: ______   값: ______                                              │
    // │   TODO: 여기에 작성                                                          │
    // └─────────────────────────────────────────────────────────────────────────────┘
    //
    // ┌─────────────────────────────────────────────────────────────────────────────┐
    // │ 시나리오 D — 결제 액티비티가 재시도를 계속해 하루 종일 돈다.                  │
    // │   비즈니스 규칙상 "결제는 아무리 재시도해도 30분 안에 끝나거나 실패해야 한다".│
    // │                                                                              │
    // │   타임아웃: ______   값: ______                                              │
    // │   TODO: 여기에 작성                                                          │
    // └─────────────────────────────────────────────────────────────────────────────┘
    //
    // 마지막으로, 위 네 가지를 모두 반영한 ActivityOptions 를 아래에 작성하세요.
    // =================================================================================
    public static ActivityOptions ex1Options() {
        // TODO: 여기에 작성
        return ActivityOptions.newBuilder()
                .build();
    }

    // =================================================================================
    // 문제 2 — 타임아웃을 하나도 안 준 스텁을 고치세요.
    //
    // 순서
    //   (1) 먼저 **고치지 말고** 그대로 실행해서 워크플로우가 어떻게 끝나는지 보세요.
    //   (2) Worker 콘솔의 예외 메시지를 아래 주석에 그대로 적으세요.
    //   (3) temporal workflow describe -w order-9001 의 Status 를 적으세요.
    //   (4) 그다음 고치세요.
    //
    //   예외 메시지: TODO: 여기에 작성
    //   Status:      TODO: 여기에 작성
    //   왜 이 에러는 Step 03 의 NonDeterministicException 과 달리
    //   워크플로우를 FAILED 로 만드는가? TODO: 여기에 작성
    // =================================================================================
    public static class Ex2NoTimeoutWorkflowImpl implements OrderWorkflow {

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                // TODO: 여기에 작성 — 타임아웃을 추가하세요
                ActivityOptions.newBuilder().build());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 문제 3 — 10만 건 배치에 heartbeat 와 재개 로직을 넣으세요.
    //
    // 조건
    //   - Worker 가 죽어도 처음부터 다시 하지 않고 중단 지점부터 재개할 것
    //   - 취소 요청이 오면 감지해서 정리 작업을 하고 종료할 것
    //   - HeartbeatTimeout 을 얼마로 줄지도 ex3Options() 에서 정할 것
    //
    // 주의
    //   getHeartbeatDetails() 를 호출하는 **위치**가 중요합니다.
    //   반복문 안에 넣으면 왜 안 되는지 주석으로 적으세요. → TODO: 여기에 작성
    // =================================================================================
    public static class Ex3BatchActivityImpl implements BatchActivity {

        private static final Logger log = LoggerFactory.getLogger(Ex3BatchActivityImpl.class);

        @Override
        public String processBatch(String batchId, int totalCount) {
            ActivityExecutionContext ctx = Activity.getExecutionContext();

            // TODO: 여기에 작성 — 이전 시도의 진행 상황을 꺼내세요
            int start = 0;

            for (int i = start; i < totalCount; i++) {
                processOne(batchId, i);

                // TODO: 여기에 작성 — 하트비트를 보내고, 취소를 감지하세요
            }
            return batchId + " DONE " + totalCount;
        }

        private void processOne(String batchId, int i) {
            // 건당 약 2ms 라고 가정
        }

        private void cleanup(String batchId, int processed) {
            log.info("부분 작업 정리 batchId={} processed={}", batchId, processed);
        }
    }

    public static ActivityOptions ex3Options() {
        // TODO: 여기에 작성 — StartToClose 와 HeartbeatTimeout 을 정하세요
        //       하트비트를 1,000건마다(약 2초마다) 보낸다고 가정합니다.
        return ActivityOptions.newBuilder().build();
    }

    // =================================================================================
    // 문제 4 — 멱등하지 않은 결제 액티비티를 고치세요.
    //
    // 아래 구현은 Set 으로 중복을 막으려 합니다. 이 접근은 실패합니다.
    //   (a) 왜 실패하는가? 세 가지 이유를 적으세요. → TODO: 여기에 작성
    //   (b) 멱등키는 어디서 만들어야 하는가? 왜?     → TODO: 여기에 작성
    //   (c) 올바르게 고치세요.
    // =================================================================================
    public static class Ex4PaymentActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(Ex4PaymentActivityImpl.class);

        // ✘ 이 방식은 왜 안 되는가?
        private final Set<String> chargedOrders = new HashSet<>();

        @Override
        public String charge(String orderId, long amount) {
            if (chargedOrders.contains(orderId)) {
                log.info("이미 결제됨 orderId={}", orderId);
                return "PAY-DUP";
            }
            chargedOrders.add(orderId);
            log.info("결제 실행 orderId={} amount={}", orderId, amount);
            return "PAY-" + orderId;
        }
    }

    // TODO: 여기에 작성 — 올바른 인터페이스와 구현체를 정의하세요.
    //   힌트: 인터페이스 시그니처부터 바꿔야 합니다.
    //
    // @ActivityInterface
    // public interface Ex4FixedPaymentActivity { ... }
    //
    // public static class Ex4FixedPaymentActivityImpl implements Ex4FixedPaymentActivity { ... }

    // 그리고 이 워크플로우에서 키를 만들어 넘기세요.
    public static class Ex4WorkflowImpl implements OrderWorkflow {

        @Override
        public String processOrder(OrderRequest req) {
            // TODO: 여기에 작성 — 멱등키를 만들고 액티비티에 전달하세요
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 문제 5 — 아래 액티비티 5개를 Activity / Local Activity 로 분류하고 근거를 적으세요.
    //
    //   (1) 주소 정규화 — 문자열 trim/대문자 변환. 0.1ms. 외부 호출 없음.
    //       분류: ______   근거: TODO: 여기에 작성
    //
    //   (2) 상품 이미지 리사이징 — 원본 20MB, ImageMagick 호출. 평균 45초. CPU/메모리 많이 씀.
    //       분류: ______   근거: TODO: 여기에 작성
    //
    //   (3) 쿠폰 코드 형식 검증 — 정규식 매칭. 0.05ms. 외부 호출 없음.
    //       분류: ______   근거: TODO: 여기에 작성
    //
    //   (4) 결제 — 외부 PG API 호출. 평균 1.2초. 돈이 움직인다.
    //       분류: ______   근거: TODO: 여기에 작성
    //
    //   (5) 재고 조회 — 내부 재고 서비스 gRPC 호출. 평균 30ms. 읽기 전용.
    //       분류: ______   근거: TODO: 여기에 작성
    //       ★ 이 항목은 판단이 갈립니다. 어느 쪽을 골랐든 근거를 명확히 적으세요.
    //
    //   추가로, (2)는 Task Queue 를 분리해야 합니다. 왜인지, 어떻게 하는지 적으세요.
    //   TODO: 여기에 작성
    // =================================================================================

    // =================================================================================
    // 문제 6 — 스레드 안전 문제를 찾아 고치세요.
    //
    // 아래 구현체에는 스레드 안전 문제가 **세 군데** 있습니다.
    // Worker 는 이 인스턴스 하나를 최대 200개 스레드가 동시에 호출합니다.
    // 각 문제 위에 `// [문제 N] 이유` 주석을 달고, 아래에 고친 버전을 작성하세요.
    // =================================================================================
    public static class Ex6UnsafeActivityImpl implements PaymentActivity {

        private static final Logger log = LoggerFactory.getLogger(Ex6UnsafeActivityImpl.class);

        // TODO: 여기에 작성 — 문제 지점에 주석을 다세요

        private String currentOrderId;

        private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        private HttpClient client;

        @Override
        public String charge(String orderId, long amount) {
            this.currentOrderId = orderId;

            if (client == null) {
                client = HttpClient.newHttpClient();
            }

            String at = fmt.format(new Date());
            log.info("charge orderId={} amount={} at={}", this.currentOrderId, amount, at);

            return "PAY-" + this.currentOrderId;
        }
    }

    // TODO: 여기에 작성 — 고친 버전
    //
    // public static class Ex6SafeActivityImpl implements PaymentActivity { ... }

    public static void main(String[] args) {
        System.out.println("Exercise — 각 문제의 TODO 를 채우세요.");
        System.out.println("문제 2 는 먼저 고치지 말고 한 번 실행해서 예외를 직접 보세요.");
        System.out.println("정답은 Solution.java 를 참고하세요.");
    }
}
