package com.example.order;

// ============================================================================
// Step 13 — 연습문제 정답 + 해설
//
// 각 정답 위의 주석이 "왜 그 답인가"를 설명합니다. 문제를 풀어 본 뒤 여십시오.
//
// Temporal Java SDK 1.22.3 / Java 21
// ============================================================================

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

import com.example.order.Practice.InventoryActivity;
import com.example.order.Practice.NotificationActivity;
import com.example.order.Practice.OrderRequest;
import com.example.order.Practice.OrderStatus;
import com.example.order.Practice.PaymentActivity;

public class Solution {

    private static final Logger log = Workflow.getLogger(Solution.class);

    // ------------------------------------------------------------------
    // 정답 1 — 보상 전용 ActivityOptions
    //
    // 핵심은 setMaximumAttempts 를 "부르지 않는 것"입니다.
    // RetryOptions 의 maximumAttempts 기본값은 0 이고, 0 은 "무제한"을 뜻합니다.
    // 습관적으로 .setMaximumAttempts(3) 을 붙이면 보상이 3번 만에 포기하고,
    // 그 순간 결제는 되었는데 환불은 안 된 상태로 워크플로우가 끝납니다.
    // 이런 상태는 히스토리에는 "정상 종료"로 남기 때문에 알림도 뜨지 않습니다.
    // 전형적인 "에러 없이 조용히 잘못 동작하는" 사례입니다.
    //
    // ScheduleToClose 를 24시간으로 크게 잡는 이유는, 결제 게이트웨이가
    // 30분 장애를 겪어도 보상이 살아 있어야 하기 때문입니다.
    // 그동안 워크플로우는 히스토리에 재시도만 쌓으며 대기합니다(비용 0).
    // ------------------------------------------------------------------
    public static ActivityOptions compensationOptions() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofHours(24))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(5))
                        // setMaximumAttempts 를 지정하지 않습니다 = 무제한
                        .build())
                .build();
    }

    // ------------------------------------------------------------------
    // 정답 2 — 결제 ActivityOptions
    //
    // 결제는 다른 액티비티와 성질이 다릅니다.
    //   - 사용자가 결제창 앞에서 기다리므로 총 대기 시간(ScheduleToClose)이 짧아야 합니다.
    //   - 재시도가 무의미한 실패가 명확히 존재합니다(잔액 부족, 카드 거절).
    //     이런 실패를 재시도하면 60초 동안 3번 더 시도한 뒤 똑같이 실패하고,
    //     그동안 사용자는 아무 응답도 못 받습니다.
    //
    // doNotRetry 의 인자는 예외 "타입 이름"입니다.
    // ApplicationFailure.newFailure(message, "InsufficientFunds") 의 두 번째 인자와
    // 문자열이 정확히 일치해야 합니다. 오타가 나면 조용히 재시도됩니다.
    // ------------------------------------------------------------------
    public static ActivityOptions paymentOptions() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setDoNotRetry("InsufficientFunds", "CardDeclined")
                        .build())
                .build();
    }

    // ------------------------------------------------------------------
    // 정답 3 — 알림 실패를 삼키기
    //
    // "알림 실패가 주문 전체를 실패시킨다"는 이 코스에서 가장 흔한 사고입니다.
    // 결제·재고·배송이 전부 성공했는데 SMS 게이트웨이가 5xx 를 뱉었다는 이유로
    // ActivityFailure 가 올라오면, catch 블록이 그것을 "주문 실패"로 해석해
    // 보상을 돌립니다. 결과: 환불되고 재고가 풀리고 배송이 취소됩니다.
    // 고객은 물건을 못 받고, 원인은 "문자가 안 갔다"입니다.
    //
    // 해결은 단순합니다. 알림 호출만 try/catch 로 감싸고 예외를 로그로 흘립니다.
    // 부수적으로 알림 액티비티의 재시도 상한을 2회로 낮춰 두면
    // 실패해도 워크플로우가 오래 붙잡히지 않습니다.
    //
    // 정말로 알림이 중요하다면 삼키지 말고 "별도 워크플로우"로 분리하십시오.
    // 주문 워크플로우의 성패와 알림의 성패를 같은 트랜잭션에 묶지 않는 것이 핵심입니다.
    // ------------------------------------------------------------------
    public static void notifyQuietly(
            NotificationActivity notification, String orderId, String message) {
        try {
            notification.notifyCustomer(
                    orderId + ":notify:" + Workflow.currentTimeMillis(), orderId, message);
        } catch (ActivityFailure e) {
            log.warn("[{}] 알림 실패(무시하고 진행): {}", orderId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 정답 4 — Saga 보상 등록 순서
    //
    // 버그 (a): 보상을 액티비티 호출 "전"에 등록했습니다.
    //   charge 가 타임아웃으로 실패하면 paymentId 는 null 인데
    //   보상은 이미 등록돼 있으므로 refund(null) 이 호출됩니다.
    //   결제 시스템은 "그런 결제 없음"으로 응답하고, 보상 액티비티는 무한 재시도이므로
    //   영원히 실패를 반복합니다. 워크플로우는 24시간 뒤 ScheduleToClose 로 죽습니다.
    //
    // 버그 (b): 보상 람다가 "rsv-fixed" 라는 하드코딩 값을 씁니다.
    //   실제 예약 ID 는 reserve 가 리턴한 값이어야 합니다.
    //   람다가 캡처하는 변수는 반드시 "액티비티가 리턴한 실제 ID" 여야 합니다.
    //
    // 규칙은 한 줄입니다: **성공 직후에 등록하고, 리턴값을 캡처한다.**
    // ------------------------------------------------------------------
    public static void chargeAndReserve(
            Saga saga,
            PaymentActivity payment, PaymentActivity paymentC,
            InventoryActivity inventory, InventoryActivity inventoryC,
            OrderRequest req) {

        String k1 = req.orderId() + ":charge";
        String k1r = req.orderId() + ":refund";
        String k2 = req.orderId() + ":reserve";
        String k2r = req.orderId() + ":release";

        // 1) 호출 → 2) 성공 → 3) 리턴값을 캡처해 보상 등록
        final String paymentId = payment.charge(k1, req.orderId(), req.amount());
        saga.addCompensation(() -> paymentC.refund(k1r, paymentId));

        final String reservationId =
                inventory.reserve(k2, req.orderId(), req.sku(), req.qty());
        saga.addCompensation(() -> inventoryC.release(k2r, reservationId));
    }

    // ------------------------------------------------------------------
    // 정답 5 — detached cancellation scope
    //
    // 워크플로우가 `temporal workflow cancel` 로 외부 취소되면,
    // 그 워크플로우의 모든 CancellationScope 가 취소 상태가 됩니다.
    // 취소된 스코프 안에서 액티비티를 스케줄하면 즉시 CanceledFailure 로 끝나므로
    // saga.compensate() 는 보상 액티비티를 하나도 실행하지 못하고 리턴합니다.
    // 로그에는 아무 에러도 안 남습니다. 결제만 되고 아무것도 되돌아가지 않습니다.
    //
    // Workflow.newDetachedCancellationScope 는 부모의 취소를 상속하지 않는
    // 독립 스코프를 만듭니다. 보상은 반드시 이 안에서 돌려야 합니다.
    // .run() 을 빼먹으면 스코프가 아예 실행되지 않는다는 점도 주의하십시오.
    // ------------------------------------------------------------------
    public static void compensateSafely(Saga saga) {
        Workflow.newDetachedCancellationScope(saga::compensate).run();
    }

    // ------------------------------------------------------------------
    // 정답 6 — 취소 거절 시그널 핸들러
    //
    // 배송이 이미 출발한 뒤에는 "취소"의 의미가 달라집니다.
    // 창고에서 물건이 나갔으므로 cancelShipment 로 되돌릴 수 없고,
    // 반품 프로세스라는 별도 워크플로우가 필요합니다.
    // 그래서 SHIPPED / COMPLETED 상태에서는 취소 시그널을 무시합니다.
    //
    // 여기서 IllegalStateException 을 던지고 싶은 유혹이 강한데, 던지면 안 됩니다.
    // 시그널 핸들러에서 던진 예외는 클라이언트에게 전달되지 않고
    // Workflow Task 를 실패시킵니다. Temporal 은 실패한 Workflow Task 를
    // 무한 재시도하므로, 히스토리에 WorkflowTaskFailed 가 몇 초 간격으로 계속 쌓입니다.
    // 워크플로우는 "실행 중"으로 보이지만 한 발짝도 못 나갑니다.
    // "거절"을 클라이언트에게 알려야 한다면 Signal 이 아니라 Update 를 쓰십시오.
    // Update 는 Validator 에서 던진 예외를 호출자에게 그대로 돌려줍니다.
    // ------------------------------------------------------------------
    public static class CancelHandler {
        public OrderStatus status = OrderStatus.RECEIVED;
        public boolean cancelRequested;
        public String cancelReason;

        public void onCancel(String reason) {
            if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
                log.warn("취소 거절 — 이미 배송 단계입니다. status={} reason={}", status, reason);
                return;
            }
            this.cancelRequested = true;
            this.cancelReason = reason;
            log.info("취소 시그널 수신: {}", reason);
        }
    }

    // ------------------------------------------------------------------
    // 보너스 정답 — (c) 만 참입니다.
    //
    // (a) 거짓. Query 핸들러는 액티비티를 호출할 수 없고, 워크플로우 상태를
    //     변경해서도 안 됩니다. Query 는 히스토리에 이벤트를 남기지 않는
    //     "읽기 전용 스냅샷"이며, 리플레이 도중에도 호출될 수 있기 때문입니다.
    //     액티비티 호출을 시도하면 즉시 예외가 납니다.
    //
    // (b) 거짓. Validator 는 히스토리에 아무것도 기록하지 않은 채 실행됩니다.
    //     여기서 상태를 바꾸면 리플레이 때 그 변경이 재현되지 않아
    //     비결정성으로 이어집니다. Validator 는 순수 검증만 해야 합니다.
    //
    // (c) 참. 워크플로우 메서드가 리턴한 뒤 도착한 시그널은 갈 곳이 없습니다.
    //     경쟁 상태를 피하려면 워크플로우 종료 직전에
    //     Workflow.await(() -> Workflow.isEveryHandlerFinished()) 로
    //     핸들러가 모두 끝나기를 기다립니다(SDK 1.22 이상).
    // ------------------------------------------------------------------

    private Solution() {
    }
}
