package com.example.order;

/*
 * Step 11 — 테스트 : Practice
 *
 * 실행 방법
 *   전체        : ./gradlew test --tests 'com.example.order.Practice'
 *   구간만      : ./gradlew test --tests 'com.example.order.Practice$TimeSkippingTests'
 *   리플레이만  : ./gradlew test --tests 'com.example.order.Practice$ReplayTests'
 *
 * 위치: src/test/java/com/example/order/Practice.java
 *
 * 필요 의존성 (build.gradle)
 *   testImplementation 'io.temporal:temporal-testing:1.22.3'
 *   testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'
 *   testImplementation 'org.mockito:mockito-core:5.8.0'
 *   testImplementation 'org.assertj:assertj-core:3.24.2'
 *
 * Temporal Server 를 띄울 필요가 없습니다. TestWorkflowEnvironment 가
 * temporal-test-server 를 인메모리로 기동합니다. (11-1)
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class Practice {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // [11-4] 액티비티 mock 은 반드시 이 헬퍼를 거칩니다.
    //        withSettings().withoutAnnotations() 를 빠뜨리면 registerActivitiesImplementations 가
    //        IllegalArgumentException: Interface annotated with @ActivityInterface can't be
    //        registered as an activity implementation 으로 실패합니다.
    static <T> T activityMock(Class<T> type) {
        return mock(type, withSettings().withoutAnnotations());
    }

    static OrderRequest req(String orderId) {
        return new OrderRequest(orderId, "C-77", "SKU-A", 2, 39000L, "서울시 강남구");
    }

    // =====================================================================
    // [11-2][11-4][11-6][11-7] 워크플로우 테스트
    // =====================================================================
    @Nested
    @DisplayName("11-2/4/6/7 — TestWorkflowEnvironment 워크플로우 테스트")
    class WorkflowTests {

        TestWorkflowEnvironment testEnv;
        Worker worker;
        WorkflowClient client;

        PaymentActivity payment;
        InventoryActivity inventory;
        ShippingActivity shipping;
        NotificationActivity notification;

        @BeforeEach
        void setUp() {
            // [11-2] 서버 없이 인메모리 Temporal 서비스가 뜹니다.
            testEnv = TestWorkflowEnvironment.newInstance();
            worker = testEnv.newWorker(TASK_QUEUE);
            client = testEnv.getWorkflowClient();

            worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

            payment = activityMock(PaymentActivity.class);
            inventory = activityMock(InventoryActivity.class);
            shipping = activityMock(ShippingActivity.class);
            notification = activityMock(NotificationActivity.class);
            worker.registerActivitiesImplementations(payment, inventory, shipping, notification);
        }

        @AfterEach
        void tearDown() {
            testEnv.close();
        }

        OrderWorkflow stub(String workflowId) {
            return client.newWorkflowStub(OrderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId(workflowId)
                            .build());
        }

        // [11-2] 첫 테스트 — 기대 소요 0.9초 내외
        @Test
        @DisplayName("11-2 정상 주문은 COMPLETED 를 반환한다")
        void 정상_주문은_COMPLETED_를_반환한다() {
            when(payment.charge("1001", 39000L)).thenReturn("PAY-8821");
            when(inventory.reserve("1001", "SKU-A", 2)).thenReturn("RSV-3310");
            when(shipping.requestShipment(eq("1001"), anyString())).thenReturn("SHIP-5507");

            testEnv.start();

            String result = stub("order-1001").processOrder(req("1001"));

            assertThat(result).isEqualTo("order-1001 COMPLETED");
            verify(payment).charge("1001", 39000L);
            verify(inventory).reserve("1001", "SKU-A", 2);
            verify(notification).notifyCustomer("1001", "주문이 완료되었습니다");

            // 테스트 환경에도 진짜 이벤트 히스토리가 쌓입니다.
            System.out.println(testEnv.getDiagnostics());
        }

        // [11-4] 실패 주입 — 재시도 불가
        @Test
        @DisplayName("11-4 잔액 부족은 재시도 없이 즉시 실패한다")
        void 잔액_부족은_즉시_실패한다() {
            when(payment.charge("1002", 39000L)).thenThrow(
                    ApplicationFailure.newNonRetryableFailure("잔액 부족", "InsufficientFunds"));

            testEnv.start();

            assertThatThrownBy(() -> stub("order-1002").processOrder(req("1002")))
                    .isInstanceOf(WorkflowFailedException.class)
                    .hasRootCauseMessage("잔액 부족");

            verify(payment, times(1)).charge("1002", 39000L);   // 재시도 없음
            verify(inventory, never()).reserve(any(), any(), anyInt());
        }

        // [11-4] 재시도 동작 검증 — 백오프에도 시간 스킵이 적용되어 0.5초에 끝납니다.
        @Test
        @DisplayName("11-4 결제는 두 번 실패해도 세 번째에 성공한다")
        void 결제는_두번_실패해도_세번째에_성공한다() {
            when(payment.charge("1003", 39000L))
                    .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
                    .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
                    .thenReturn("PAY-8821");
            when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-3310");
            when(shipping.requestShipment(any(), any())).thenReturn("SHIP-5507");

            testEnv.start();

            assertThat(stub("order-1003").processOrder(req("1003")))
                    .isEqualTo("order-1003 COMPLETED");

            verify(payment, times(3)).charge("1003", 39000L);
        }

        // [11-6] 시그널 · 쿼리 · 결과 회수
        @Test
        @DisplayName("11-6 취소 시그널을 받으면 상태가 CANCELED 로 바뀐다")
        void 취소_시그널을_받으면_CANCELED() {
            when(payment.charge(any(), anyLong())).thenReturn("PAY-8821");
            when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-3310");

            testEnv.start();
            OrderWorkflow wf = stub("order-1004");

            // ① 비동기 시작 — 즉시 반환
            WorkflowExecution exec = WorkflowClient.start(wf::processOrder, req("1004"));
            assertThat(exec.getWorkflowId()).isEqualTo("order-1004");

            // ② 가상 시간을 밀어 워크플로우를 대기 지점까지 진행시킴 (Thread.sleep 금지!)
            testEnv.sleep(Duration.ofSeconds(1));

            // ③ 쿼리
            assertThat(wf.getStatus()).isEqualTo("WAITING_SHIPMENT");

            // ④ 시그널
            wf.cancelRequested("고객 변심");
            testEnv.sleep(Duration.ofSeconds(1));

            // ⑤ 쿼리로 반영 확인
            assertThat(wf.getStatus()).isEqualTo("CANCELED");

            // ⑥ 결과 회수 — 타입 스텁을 언타입 스텁으로 변환해야 getResult 가 가능
            String result = WorkflowStub.fromTyped(wf).getResult(String.class);
            assertThat(result).isEqualTo("order-1004 CANCELED");
        }

        // [11-7] Saga 보상 역순 검증
        @Test
        @DisplayName("11-7 배송이 실패하면 보상이 역순으로 실행된다")
        void 배송_실패시_보상은_역순() {
            when(payment.charge("1005", 39000L)).thenReturn("PAY-8821");
            when(inventory.reserve("1005", "SKU-A", 2)).thenReturn("RSV-3310");
            when(shipping.requestShipment(eq("1005"), anyString())).thenThrow(
                    ApplicationFailure.newNonRetryableFailure("배송 불가 지역", "ShippingUnavailable"));

            testEnv.start();

            assertThatThrownBy(() -> stub("order-1005").processOrder(req("1005")))
                    .isInstanceOf(WorkflowFailedException.class)
                    .hasRootCauseMessage("배송 불가 지역");

            // verify 만 쓰면 "둘 다 호출됨"만 확인됩니다. 순서 버그는 InOrder 로만 잡힙니다.
            InOrder ord = inOrder(payment, inventory, shipping);
            ord.verify(payment).charge("1005", 39000L);
            ord.verify(inventory).reserve("1005", "SKU-A", 2);
            ord.verify(shipping).requestShipment(eq("1005"), anyString());
            ord.verify(inventory).release("RSV-3310");     // 보상 1 — 나중 것 먼저
            ord.verify(payment).refund("PAY-8821");        // 보상 2 — 먼저 한 것 나중에

            verify(shipping, never()).cancelShipment(anyString());
        }
    }

    // =====================================================================
    // [11-3] 시간 스킵 — 30일이 0.4초
    // =====================================================================
    @Nested
    @DisplayName("11-3 — 시간 스킵")
    class TimeSkippingTests {

        TestWorkflowEnvironment testEnv;
        Worker worker;
        WorkflowClient client;
        NotificationActivity notification;

        @BeforeEach
        void setUp() {
            testEnv = TestWorkflowEnvironment.newInstance();
            worker = testEnv.newWorker(TASK_QUEUE);
            client = testEnv.getWorkflowClient();
            worker.registerWorkflowImplementationTypes(ReviewReminderWorkflowImpl.class);
            notification = activityMock(NotificationActivity.class);
            worker.registerActivitiesImplementations(notification);
        }

        @AfterEach
        void tearDown() {
            testEnv.close();
        }

        @Test
        @DisplayName("11-3 30일 대기 워크플로우가 2초 미만에 끝난다")
        void 삼십일_대기가_즉시_끝난다() {
            testEnv.start();

            ReviewReminderWorkflow wf = client.newWorkflowStub(ReviewReminderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId("review-1001")
                            .build());

            long before = System.currentTimeMillis();
            String result = wf.remind("1001");
            long wallClock = System.currentTimeMillis() - before;

            assertThat(result).isEqualTo("1001 REMINDED");
            // "빨리 끝났다"를 주석이 아니라 테스트 조건으로 못박습니다.
            // 시간 스킵이 꺼지면 이 단언이 깨집니다.
            assertThat(wallClock).isLessThan(2000);
            verify(notification).notifyCustomer("1001", "리뷰를 남겨 주세요");

            System.out.println("벽시계 경과 = " + wallClock + "ms / 가상 경과 = 30일");
        }

        @Test
        @DisplayName("11-3 testEnv.sleep 은 가상 시각만 민다")
        void 가상_시각을_직접_민다() {
            testEnv.start();
            long t0 = testEnv.currentTimeMillis();
            testEnv.sleep(Duration.ofDays(7));
            long t1 = testEnv.currentTimeMillis();

            assertThat(Duration.ofMillis(t1 - t0).toDays()).isEqualTo(7);
        }
    }

    // =====================================================================
    // [11-5] TestActivityEnvironment — 액티비티 단독
    // =====================================================================
    @Nested
    @DisplayName("11-5 — TestActivityEnvironment")
    class ActivityTests {

        @Test
        @DisplayName("11-5 재고 예약 액티비티가 예약 ID 를 반환한다")
        void 재고_예약_단독_테스트() {
            TestActivityEnvironment env = TestActivityEnvironment.newInstance();
            env.registerActivitiesImplementations(new InventoryActivityImpl());

            InventoryActivity stub = env.newActivityStub(InventoryActivity.class);
            String reservationId = stub.reserve("1001", "SKU-A", 2);

            assertThat(reservationId).startsWith("RSV-");
            env.close();
        }

        @Test
        @DisplayName("11-5 하트비트가 실제로 전송되는지 확인한다")
        void 하트비트_리스너로_검증() {
            TestActivityEnvironment env = TestActivityEnvironment.newInstance();
            env.registerActivitiesImplementations(new ShippingActivityImpl());

            List<Object> heartbeats = new ArrayList<>();
            env.setActivityHeartbeatListener(String.class, heartbeats::add);

            ShippingActivity stub = env.newActivityStub(ShippingActivity.class);
            String shipmentId = stub.requestShipment("1001", "서울시 강남구");

            assertThat(shipmentId).startsWith("SHIP-");
            assertThat(heartbeats).isNotEmpty();
            System.out.println("수신한 하트비트: " + heartbeats);
            env.close();
        }
    }

    // =====================================================================
    // [11-8] 리플레이 테스트
    // =====================================================================
    @Nested
    @DisplayName("11-8 — WorkflowReplayer")
    class ReplayTests {

        static Stream<Path> histories() throws Exception {
            var url = Practice.class.getResource("/histories");
            if (url == null) {
                return Stream.empty();
            }
            Path dir = Paths.get(url.toURI());
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> p.toString().endsWith(".json")).toList().stream();
            }
        }

        // 히스토리 파일이 하나도 없으면 아래 @ParameterizedTest 가 0건으로 "조용히 통과"합니다.
        // 그 함정을 막는 가드 테스트입니다.
        @Test
        @DisplayName("11-8 히스토리 리소스가 비어 있지 않다")
        void 히스토리가_존재한다() throws Exception {
            assertThat(histories().toList())
                    .as("src/test/resources/histories/*.json — dump-histories.sh 로 채우세요")
                    .isNotEmpty();
        }

        @ParameterizedTest(name = "replay {0}")
        @MethodSource("histories")
        @DisplayName("11-8 모든 운영 히스토리가 현재 코드로 리플레이된다")
        void 모든_히스토리가_리플레이된다(Path history) throws Exception {
            io.temporal.testing.WorkflowReplayer.replayWorkflowExecution(
                    Files.readString(history), OrderWorkflowImpl.class);
        }

        // 클래스패스 리소스 이름으로 직접 지정하는 단건 버전
        @Test
        @DisplayName("11-8 order-1001.json 단건 리플레이")
        void 단건_리플레이() throws Exception {
            io.temporal.testing.WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "histories/order-1001.json", OrderWorkflowImpl.class);
        }
    }

    // =====================================================================
    // 본문 11-3 예제를 이 파일만으로 돌리기 위한 픽스처
    // =====================================================================

    @WorkflowInterface
    public interface ReviewReminderWorkflow {
        @WorkflowMethod
        String remind(String orderId);
    }

    public static class ReviewReminderWorkflowImpl implements ReviewReminderWorkflow {

        private final NotificationActivity notification = Workflow.newActivityStub(
                NotificationActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build());

        @Override
        public String remind(String orderId) {
            // 실제 서버에서는 30일. 테스트 환경에서는 가상 시계가 즉시 점프합니다.
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(orderId, "리뷰를 남겨 주세요");
            return orderId + " REMINDED";
        }
    }

    // 11-5 에서 쓰는 최소 액티비티 구현 (실제 프로젝트에서는 src/main 에 있습니다)
    public static class InventoryActivityImpl implements InventoryActivity {
        @Override
        public String reserve(String orderId, String sku, int qty) {
            return "RSV-" + Math.abs((orderId + sku).hashCode() % 10000);
        }

        @Override
        public void release(String reservationId) {
            System.out.println("release " + reservationId);
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        @Override
        public String requestShipment(String orderId, String address) {
            for (int i = 1; i <= 3; i++) {
                io.temporal.activity.Activity.getExecutionContext()
                        .heartbeat("polling-" + i);
            }
            return "SHIP-" + Math.abs(orderId.hashCode() % 10000);
        }

        @Override
        public void cancelShipment(String shipmentId) {
            System.out.println("cancelShipment " + shipmentId);
        }
    }

    // 참고 — 실제 액티비티 인터페이스는 src/main 에 있습니다.
    // 이 파일 단독으로 컴파일해 보고 싶을 때만 아래 주석을 해제하세요.
    //
    // @ActivityInterface
    // public interface NotificationActivity {
    //     @ActivityMethod void notifyCustomer(String orderId, String message);
    // }
}
