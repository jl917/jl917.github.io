package com.example.order;

/*
 * Step 11 — 테스트 : Exercise (문제지)
 *
 * 실행 방법
 *   ./gradlew test --tests 'com.example.order.Exercise'
 *
 * 위치: src/test/java/com/example/order/Exercise.java
 *
 * 각 문제의 // TODO 자리를 채우세요. 단언문(assertThat)은 이미 적혀 있으므로
 * "무엇을 만족시켜야 하는지"는 명확합니다. 단언문을 고쳐서 통과시키지 마세요.
 *
 * 문제 7 을 풀려면 src/test/resources/histories/ 에 히스토리 JSON 이 필요합니다.
 * 운영 서버가 없다면 이렇게 만드세요.
 *   1) Practice$WorkflowTests 를 한 번 실행합니다.
 *   2) 콘솔에 출력된 testEnv.getDiagnostics() 의 히스토리 부분을 참고하거나,
 *   3) 로컬 dev 서버(temporal server start-dev)에서 워크플로우를 한 번 돌린 뒤
 *      temporal workflow show -w order-1001 --output json > src/test/resources/histories/order-1001.json
 */

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class Exercise {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    TestWorkflowEnvironment testEnv;
    Worker worker;
    WorkflowClient client;

    PaymentActivity payment;
    InventoryActivity inventory;
    ShippingActivity shipping;
    NotificationActivity notification;

    static OrderRequest req(String orderId) {
        return new OrderRequest(orderId, "C-77", "SKU-A", 2, 39000L, "서울시 강남구");
    }

    // -----------------------------------------------------------------
    // 문제 1 — TestWorkflowEnvironment 를 수동으로 구성하기
    //
    // setUp() 의 TODO 를 채워 아래 테스트가 통과하게 하세요.
    // 필요한 것: 인메모리 환경 생성, Worker 생성, WorkflowClient 획득,
    //            워크플로우 타입 등록, 액티비티 mock 등록.
    // -----------------------------------------------------------------
    @BeforeEach
    void setUp() {
        // TODO: 여기에 작성 — testEnv / worker / client 초기화
        // 힌트: TestWorkflowEnvironment.newInstance(), testEnv.newWorker(TASK_QUEUE),
        //       testEnv.getWorkflowClient()

        // TODO: 여기에 작성 — worker.registerWorkflowImplementationTypes(...)

        // TODO: 여기에 작성 — payment / inventory / shipping / notification mock 생성 후
        //       worker.registerActivitiesImplementations(...)
        //       (문제 3 을 먼저 읽고 오면 어떻게 mock 을 만들어야 하는지 알 수 있습니다)
    }

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    OrderWorkflow stub(String workflowId) {
        return client.newWorkflowStub(OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());
    }

    @Test
    @DisplayName("문제 1 — 정상 주문이 COMPLETED 를 반환한다")
    void 문제1_정상_주문() {
        when(payment.charge("2001", 39000L)).thenReturn("PAY-1111");
        when(inventory.reserve("2001", "SKU-A", 2)).thenReturn("RSV-2222");
        when(shipping.requestShipment(eq("2001"), anyString())).thenReturn("SHIP-3333");

        testEnv.start();

        String result = stub("order-2001").processOrder(req("2001"));

        assertThat(result).isEqualTo("order-2001 COMPLETED");
        verify(notification).notifyCustomer("2001", "주문이 완료되었습니다");
    }

    // -----------------------------------------------------------------
    // 문제 2 — 시간 스킵
    //
    // 30일을 기다리는 워크플로우를 2초 미만에 통과시키세요.
    // 주의: Thread.sleep 으로는 절대 통과할 수 없습니다.
    //       Practice.ReviewReminderWorkflowImpl 을 등록해서 쓰세요.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2 — 30일 대기 워크플로우가 2초 미만에 끝난다")
    void 문제2_시간_스킵() {
        // TODO: 여기에 작성 — worker 에 Practice.ReviewReminderWorkflowImpl 등록
        // 힌트: worker.registerWorkflowImplementationTypes(...)

        testEnv.start();

        // TODO: 여기에 작성 — ReviewReminderWorkflow 스텁을 만들고 remind("2002") 호출
        long before = System.currentTimeMillis();
        String result = null;   // TODO: 여기에 작성
        long wallClock = System.currentTimeMillis() - before;

        assertThat(result).isEqualTo("2002 REMINDED");
        assertThat(wallClock).isLessThan(2000);
    }

    // -----------------------------------------------------------------
    // 문제 3 — 액티비티 mock 등록 실패 고치기
    //
    // 아래 메서드는 "일부러 실패하는" 코드입니다.
    // ① 먼저 그대로 실행해서 어떤 예외가 나오는지 눈으로 확인하세요.
    //    java.lang.IllegalArgumentException: Interface annotated with @ActivityInterface
    //      can't be registered as an activity implementation: interface ...PaymentActivity
    // ② 그다음 TODO 자리를 채워 정상 등록되게 고치세요.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 3 — mock 액티비티 등록이 실패하지 않게 고친다")
    void 문제3_mock_등록() {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        Worker w = env.newWorker("EX3_QUEUE");

        // ① 아래 줄을 그대로 두고 한 번 실행해 보세요 (실패합니다)
        // w.registerActivitiesImplementations(mock(PaymentActivity.class));

        // ② TODO: 여기에 작성 — 위 줄을 고쳐 정상 등록되게 하세요
        //    힌트: Mockito 가 @ActivityInterface 어노테이션을 프록시에 복사합니다

        env.close();
        // 예외 없이 여기까지 오면 성공
        assertThat(true).isTrue();
    }

    // -----------------------------------------------------------------
    // 문제 4 — 재시도 검증
    //
    // 결제가 2번 실패한 뒤 3번째에 성공하도록 스텁하고,
    // charge 가 정확히 3번 호출되었는지 검증하세요.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 4 — 결제 2회 실패 후 성공, 호출 3회")
    void 문제4_재시도() {
        // TODO: 여기에 작성 — payment.charge("2004", 39000L) 를
        //       두 번 재시도 가능 실패 → 세 번째 성공("PAY-4444") 으로 스텁
        //       힌트: ApplicationFailure.newFailure(message, type)
        //             .thenThrow(...).thenThrow(...).thenReturn(...)

        when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-2222");
        when(shipping.requestShipment(any(), any())).thenReturn("SHIP-3333");

        testEnv.start();

        assertThat(stub("order-2004").processOrder(req("2004")))
                .isEqualTo("order-2004 COMPLETED");

        // TODO: 여기에 작성 — charge 가 3번 호출되었는지 검증
        // 힌트: verify(mock, times(n))
    }

    // -----------------------------------------------------------------
    // 문제 5 — 비동기 시작 → 시그널 → 쿼리 → 결과 회수
    //
    // 아래 4단계를 채우세요. 마지막 결과 회수가 이 문제의 핵심입니다.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5 — 시그널과 쿼리, 그리고 결과 회수")
    void 문제5_시그널_쿼리() {
        when(payment.charge(any(), anyLong())).thenReturn("PAY-1111");
        when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-2222");

        testEnv.start();
        OrderWorkflow wf = stub("order-2005");

        // ① 비동기 시작 (주어짐)
        WorkflowClient.start(wf::processOrder, req("2005"));

        // ② TODO: 여기에 작성 — 가상 시간을 1초 밀어 워크플로우를 대기 지점까지 진행
        //    (Thread.sleep 금지)

        // ③ 쿼리로 진행 중 상태 확인
        assertThat(wf.getStatus()).isEqualTo("WAITING_SHIPMENT");

        // ④ TODO: 여기에 작성 — cancelRequested 시그널 전송 후 가상 시간 1초 밀기

        assertThat(wf.getStatus()).isEqualTo("CANCELED");

        // ⑤ TODO: 여기에 작성 — 워크플로우 결과를 String 으로 회수
        //    힌트: 타입 스텁으로는 getResult 를 못 부릅니다. 언타입 스텁으로 바꾸세요.
        String result = null;   // TODO: 여기에 작성

        assertThat(result).isEqualTo("order-2005 CANCELED");
    }

    // -----------------------------------------------------------------
    // 문제 6 — 보상 순서까지 검증하기
    //
    // 아래 테스트는 이미 통과합니다. 하지만 보상 순서가 뒤바뀐 구현에서도
    // 그대로 통과합니다(둘 다 호출되기만 하면 되므로).
    // 순서까지 검증하도록 고치세요.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 6 — 보상이 역순으로 실행되는지 검증한다")
    void 문제6_보상_역순() {
        when(payment.charge("2006", 39000L)).thenReturn("PAY-1111");
        when(inventory.reserve("2006", "SKU-A", 2)).thenReturn("RSV-2222");
        when(shipping.requestShipment(eq("2006"), anyString())).thenThrow(
                ApplicationFailure.newNonRetryableFailure("배송 불가 지역", "ShippingUnavailable"));

        testEnv.start();

        assertThatThrownBy(() -> stub("order-2006").processOrder(req("2006")))
                .isInstanceOf(WorkflowFailedException.class);

        // 아래 두 줄은 "호출 여부"만 봅니다 — 순서 버그를 못 잡습니다.
        verify(inventory).release("RSV-2222");
        verify(payment).refund("PAY-1111");

        // TODO: 여기에 작성 — 위 두 줄을 순서까지 검증하도록 바꾸세요.
        //       정방향: charge → reserve → requestShipment(실패)
        //       보상  : release → refund   (나중에 한 것부터)
        //       힌트: org.mockito.InOrder
    }

    // -----------------------------------------------------------------
    // 문제 7 — 리플레이 테스트
    //
    // src/test/resources/histories/ 의 모든 .json 을 도는
    // @ParameterizedTest 를 작성하세요.
    // 디렉터리가 비어 있으면 0건으로 조용히 통과하므로 가드도 함께 넣으세요.
    // -----------------------------------------------------------------

    // TODO: 여기에 작성 — static Stream<Path> histories() 메서드
    //       힌트: Exercise.class.getResource("/histories"), Files.list(dir)

    // TODO: 여기에 작성 — @Test 가드 : histories() 가 비어 있지 않은지 단언

    // TODO: 여기에 작성 — @ParameterizedTest + @MethodSource("histories")
    //       WorkflowReplayer.replayWorkflowExecution(
    //           Files.readString(history), OrderWorkflowImpl.class);
}
