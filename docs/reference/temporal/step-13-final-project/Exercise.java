package com.example.order;

// ============================================================================
// Step 13 — 연습문제 (6문제)
//
// Practice.java 의 타입을 재사용합니다. 아래 TODO 를 채운 뒤
//   ./gradlew test --tests 'com.example.order.ExerciseTest'
// 로 검증하십시오. 정답과 해설은 Solution.java 에 있습니다.
//
// Temporal Java SDK 1.22.3 / Java 21
// ============================================================================

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;

import com.example.order.Practice.InventoryActivity;
import com.example.order.Practice.NotificationActivity;
import com.example.order.Practice.OrderRequest;
import com.example.order.Practice.OrderStatus;
import com.example.order.Practice.PaymentActivity;
import com.example.order.Practice.ShippingActivity;

public class Exercise {

    // ------------------------------------------------------------------
    // 문제 1 — 보상 액티비티 전용 ActivityOptions 를 만드십시오.
    //
    // 조건:
    //   - StartToClose 30초
    //   - ScheduleToClose 24시간
    //   - 재시도 횟수 상한을 두지 않을 것 (보상은 반드시 언젠가 성공해야 합니다)
    //   - 초기 간격 1초, 배수 2.0, 최대 간격 5분
    // ------------------------------------------------------------------
    public static ActivityOptions compensationOptions() {
        // TODO: 여기에 작성
        return null;
    }

    // ------------------------------------------------------------------
    // 문제 2 — 결제 ActivityOptions 를 만드십시오.
    //
    // 결제는 "짧게 시도하고 빨리 포기"가 원칙입니다.
    //   - StartToClose 10초, ScheduleToClose 60초
    //   - 최대 3회
    //   - "InsufficientFunds", "CardDeclined" 는 재시도하지 말 것
    //     (잔액 부족은 3초 뒤에 다시 시도해도 결과가 같습니다)
    // ------------------------------------------------------------------
    public static ActivityOptions paymentOptions() {
        // TODO: 여기에 작성
        return null;
    }

    // ------------------------------------------------------------------
    // 문제 3 — 알림 액티비티 호출을 "실패해도 주문을 망치지 않게" 감싸십시오.
    //
    // notification.notifyCustomer(key, orderId, message) 가 실패해도
    // 이 메서드는 예외를 밖으로 던지지 않아야 합니다.
    // 단, 실패 사실은 Workflow.getLogger 로 남겨야 합니다.
    // ------------------------------------------------------------------
    public static void notifyQuietly(
            NotificationActivity notification, String orderId, String message) {
        // TODO: 여기에 작성
    }

    // ------------------------------------------------------------------
    // 문제 4 — Saga 보상 등록 순서를 바로잡으십시오.
    //
    // 아래 코드에는 버그가 두 개 있습니다.
    //   (a) 아직 일어나지 않은 일에 대한 보상이 등록됩니다.
    //   (b) 보상 대상 ID 가 잘못된 시점에 캡처됩니다.
    // 올바른 순서로 다시 작성하십시오.
    //
    // [잘못된 원본]
    //   saga.addCompensation(() -> paymentC.refund(k1, paymentId));
    //   String paymentId = payment.charge(k1, req.orderId(), req.amount());
    //   String reservationId = inventory.reserve(k2, req.orderId(), req.sku(), req.qty());
    //   saga.addCompensation(() -> inventoryC.release(k2, "rsv-fixed"));
    // ------------------------------------------------------------------
    public static void chargeAndReserve(
            Saga saga,
            PaymentActivity payment, PaymentActivity paymentC,
            InventoryActivity inventory, InventoryActivity inventoryC,
            OrderRequest req) {
        // TODO: 여기에 작성
    }

    // ------------------------------------------------------------------
    // 문제 5 — 취소 이후에도 보상이 반드시 실행되도록 감싸십시오.
    //
    // 워크플로우가 외부에서 cancel 된 상태에서 saga.compensate() 를 그냥 호출하면
    // 보상 액티비티가 스케줄되자마자 취소되어 한 줄도 실행되지 않습니다.
    // 이를 막는 API 를 사용해 compensate 를 감싸십시오.
    // ------------------------------------------------------------------
    public static void compensateSafely(Saga saga) {
        // TODO: 여기에 작성
    }

    // ------------------------------------------------------------------
    // 문제 6 — 배송 단계 이후의 취소를 거절하는 시그널 핸들러를 작성하십시오.
    //
    // 규칙:
    //   - status 가 SHIPPED 또는 COMPLETED 면 취소를 무시하고 경고 로그만 남긴다
    //   - 그 외에는 cancelRequested = true, cancelReason = reason 으로 설정한다
    //   - 시그널 핸들러에서 예외를 던지면 안 된다 (워크플로우 태스크가 계속 실패합니다)
    // ------------------------------------------------------------------
    public static class CancelHandler {
        public OrderStatus status = OrderStatus.RECEIVED;
        public boolean cancelRequested;
        public String cancelReason;

        public void onCancel(String reason) {
            // TODO: 여기에 작성
        }
    }

    // ------------------------------------------------------------------
    // 보너스 — 아래 세 문장 중 참인 것을 고르고 이유를 주석으로 쓰십시오.
    //
    //   (a) Query 핸들러 안에서 액티비티를 호출해 최신 재고를 조회해도 된다.
    //   (b) Update 의 Validator 안에서 워크플로우 상태를 변경해도 된다.
    //   (c) Signal 핸들러는 워크플로우 메서드가 리턴한 뒤 도착하면 유실될 수 있다.
    //
    // 답:
    // TODO: 여기에 작성
    // ------------------------------------------------------------------

    private Exercise() {
    }
}
