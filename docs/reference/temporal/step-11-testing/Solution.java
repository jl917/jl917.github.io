package com.example.order;

/*
 * Step 11 — 테스트 : Solution (정답 + 해설)
 *
 * 실행 방법
 *   ./gradlew test --tests 'com.example.order.Solution'
 *
 * 위치: src/test/java/com/example/order/Solution.java
 *
 * Exercise.java 를 직접 풀어 본 뒤에 여세요.
 * 각 정답 위에 "왜 그렇게 써야 하는가"를 설명하는 주석 블록이 붙어 있습니다.
 */

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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

class Solution {

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

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 1 — TestWorkflowEnvironment 수동 구성
     * ─────────────────────────────────────────────────────────────────
     * 순서가 중요합니다.
     *
     *   ① TestWorkflowEnvironment.newInstance()
     *      → 이 시점에 인메모리 Temporal 서비스(temporal-test-server)가 프로세스 안에서 뜹니다.
     *        Docker 도, 7233 포트도, PostgreSQL 도 필요 없습니다.
     *   ② testEnv.newWorker(TASK_QUEUE)
     *      → Worker 를 만들되 아직 폴링은 시작하지 않습니다.
     *   ③ registerWorkflowImplementationTypes / registerActivitiesImplementations
     *      → 등록은 반드시 start() 전에 끝나야 합니다. start() 후 등록하면
     *        IllegalStateException: Worker has already been started 가 납니다.
     *   ④ testEnv.start()
     *      → 이제 폴러가 뜨고 Task 를 가져가기 시작합니다. 각 @Test 안에서 부릅니다
     *        (테스트마다 다른 워크플로우 타입을 등록해야 할 수 있으므로).
     *   ⑤ @AfterEach 의 testEnv.close()
     *      → 빠뜨리면 테스트마다 인메모리 서비스와 스레드풀이 누수됩니다.
     *        테스트 100개짜리 클래스에서 OutOfMemoryError 로 이어집니다.
     *
     * 액티비티 mock 은 activityMock() 헬퍼를 거칩니다 — 이유는 정답 3 참고.
     */
    static <T> T activityMock(Class<T> type) {
        return mock(type, withSettings().withoutAnnotations());
    }

    @BeforeEach
    void setUp() {
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
    @DisplayName("정답 1 — 정상 주문이 COMPLETED 를 반환한다")
    void 정답1_정상_주문() {
        when(payment.charge("2001", 39000L)).thenReturn("PAY-1111");
        when(inventory.reserve("2001", "SKU-A", 2)).thenReturn("RSV-2222");
        when(shipping.requestShipment(eq("2001"), anyString())).thenReturn("SHIP-3333");

        testEnv.start();

        String result = stub("order-2001").processOrder(req("2001"));

        assertThat(result).isEqualTo("order-2001 COMPLETED");
        verify(notification).notifyCustomer("2001", "주문이 완료되었습니다");
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 2 — 시간 스킵
     * ─────────────────────────────────────────────────────────────────
     * 워크플로우 코드는 Workflow.sleep(Duration.ofDays(30)) 을 그대로 둡니다.
     * 테스트 쪽에서 아무것도 특별히 하지 않아도 30일이 즉시 지나갑니다.
     *
     * 발동 조건은 단 하나입니다.
     *   "등록된 모든 Worker 가 처리할 Task 가 없어 유휴 상태가 되면,
     *    가상 시계를 다음 타이머의 발화 시각으로 즉시 점프시킨다."
     *
     * 그래서 다음 두 가지가 이 테스트를 망칩니다.
     *
     *   (a) Thread.sleep(...) 를 넣는 것
     *       → 벽시계가 진짜로 흐릅니다. 가상 시계와 무관합니다.
     *         CI 가 그만큼 느려지고, 느린 머신에서는 대기 시간이 부족해 flaky 해집니다.
     *         대기가 필요하면 언제나 testEnv.sleep(Duration) 을 쓰세요 — 비용이 0입니다.
     *
     *   (b) TestEnvironmentOptions.newBuilder().setUseTimeskipping(false)
     *       → 시간 스킵을 끕니다. 이 옵션을 켠 채로 이 테스트를 돌리면
     *         정말로 30일을 기다립니다(사실상 CI 타임아웃).
     *         이 옵션은 "외부 스레드에서 시그널을 보내는 동안 시계가 점프해 버리면 곤란한"
     *         테스트에만 쓰고, 긴 대기가 있는 테스트와는 반드시 클래스를 분리하세요.
     *
     * wallClock 단언을 남겨 두는 이유: 누군가 나중에 시간 스킵을 끄거나
     * Thread.sleep 을 끼워 넣으면 이 테스트가 즉시 실패해 알려 줍니다.
     */
    @Test
    @DisplayName("정답 2 — 30일 대기 워크플로우가 2초 미만에 끝난다")
    void 정답2_시간_스킵() {
        worker.registerWorkflowImplementationTypes(Practice.ReviewReminderWorkflowImpl.class);
        testEnv.start();

        Practice.ReviewReminderWorkflow wf = client.newWorkflowStub(
                Practice.ReviewReminderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("review-2002")
                        .build());

        long before = System.currentTimeMillis();
        String result = wf.remind("2002");
        long wallClock = System.currentTimeMillis() - before;

        assertThat(result).isEqualTo("2002 REMINDED");
        assertThat(wallClock).isLessThan(2000);
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 3 — withSettings().withoutAnnotations()
     * ─────────────────────────────────────────────────────────────────
     * 틀린 코드:
     *     w.registerActivitiesImplementations(mock(PaymentActivity.class));
     *
     * 실제 에러:
     *     java.lang.IllegalArgumentException: Interface annotated with @ActivityInterface
     *       can't be registered as an activity implementation:
     *       interface com.example.order.PaymentActivity
     *
     * 왜 이 메시지가 혼란스러운가:
     *   우리는 인터페이스를 등록한 적이 없습니다. mock 객체를 등록했습니다.
     *   그런데 Mockito 는 기본적으로 모킹 대상의 어노테이션을 **생성한 프록시 클래스에 복사**합니다.
     *   그래서 프록시 클래스에 @ActivityInterface 가 클래스 레벨로 붙어 버립니다.
     *
     *   Temporal SDK 의 registerActivitiesImplementations 는 등록된 객체의 클래스를 보고
     *   "@ActivityInterface 가 직접 붙어 있으면 그건 인터페이스(=계약)이지 구현이 아니다"
     *   라고 판단합니다. 정상적인 방어 로직인데, mock 이 그 조건에 걸린 것입니다.
     *
     * 해결:
     *     mock(PaymentActivity.class, withSettings().withoutAnnotations())
     *
     * 재발 방지:
     *   프로젝트 공용 테스트 유틸에 헬퍼를 하나 두고 팀 규칙으로 강제하세요.
     *     static <T> T activityMock(Class<T> type) {
     *         return mock(type, withSettings().withoutAnnotations());
     *     }
     *   @Mock 어노테이션 방식(MockitoExtension)도 같은 문제가 있으므로,
     *   액티비티만큼은 필드 주입 대신 이 헬퍼로 만드는 편이 안전합니다.
     */
    @Test
    @DisplayName("정답 3 — mock 액티비티가 정상 등록된다")
    void 정답3_mock_등록() {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        Worker w = env.newWorker("EX3_QUEUE");

        w.registerActivitiesImplementations(activityMock(PaymentActivity.class));

        env.close();
        assertThat(true).isTrue();
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 4 — 재시도 검증
     * ─────────────────────────────────────────────────────────────────
     * Mockito 의 연쇄 스텁 .thenThrow().thenThrow().thenReturn() 은
     * 호출 순서대로 다른 동작을 합니다. 즉 1회차 실패, 2회차 실패, 3회차 성공입니다.
     *
     * verify(payment, times(3)) 의 3 은 "재시도 2회 + 성공 1회"입니다.
     * 히스토리로 보면 이렇게 됩니다.
     *
     *    5 ActivityTaskScheduled  PaymentActivity.charge
     *    6 ActivityTaskStarted
     *    7 ActivityTaskFailed     (Transient)   ← 1회차
     *      ... RetryPolicy 에 따라 1초 대기 ...
     *    8 ActivityTaskStarted
     *    9 ActivityTaskFailed     (Transient)   ← 2회차
     *      ... 2초 대기 (backoffCoefficient=2.0) ...
     *   10 ActivityTaskStarted
     *   11 ActivityTaskCompleted  PAY-4444      ← 3회차
     *
     * 주목할 점: ActivityTaskScheduled 는 한 번만 생깁니다. 재시도는 같은 Scheduled
     * 이벤트 아래에서 Started/Failed 가 반복되는 형태입니다.
     *
     * 그리고 이 테스트는 백오프 1초 + 2초 = 3초를 기다려야 하는데도 0.5초에 끝납니다.
     * **재시도 백오프 대기에도 시간 스킵이 적용되기 때문**입니다. 이 덕분에
     * "maximumAttempts=10, maximumInterval=1분" 같은 현실적인 재시도 정책도
     * 테스트로 검증할 수 있습니다.
     */
    @Test
    @DisplayName("정답 4 — 결제 2회 실패 후 성공, 호출 3회")
    void 정답4_재시도() {
        when(payment.charge("2004", 39000L))
                .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
                .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
                .thenReturn("PAY-4444");
        when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-2222");
        when(shipping.requestShipment(any(), any())).thenReturn("SHIP-3333");

        testEnv.start();

        assertThat(stub("order-2004").processOrder(req("2004")))
                .isEqualTo("order-2004 COMPLETED");

        verify(payment, times(3)).charge("2004", 39000L);
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 5 — 비동기 시작 · 시그널 · 쿼리 · 결과 회수
     * ─────────────────────────────────────────────────────────────────
     * ① 왜 비동기 시작인가
     *    wf.processOrder(req) 는 워크플로우 완료까지 블로킹합니다.
     *    그 상태로는 시그널을 보낼 스레드가 없습니다.
     *    WorkflowClient.start(wf::processOrder, req) 는 WorkflowExecution
     *    (workflowId + runId)만 돌려주고 즉시 반환합니다.
     *
     * ② 왜 testEnv.sleep 을 끼우는가
     *    시그널은 히스토리에 기록된 뒤 "다음 Workflow Task" 에서 처리됩니다.
     *    시그널 직후 곧바로 쿼리하면 아직 반영 전일 수 있습니다.
     *    testEnv.sleep(1초) 는 가상 시각만 밀 뿐이라 비용이 0이면서
     *    Workflow Task 를 한 번 돌게 만듭니다. Thread.sleep 과 달리 CI 를 느리게 하지 않습니다.
     *
     * ③ 결과 회수 — 이 문제의 핵심
     *    타입 스텁(OrderWorkflow)에는 getResult 메서드가 없습니다.
     *    WorkflowStub.fromTyped(wf) 로 언타입 스텁으로 바꾼 뒤
     *    getResult(String.class) 를 부릅니다.
     *    (같은 workflowId 로 client.newUntypedWorkflowStub("order-2005") 를
     *     새로 만들어도 되지만, fromTyped 가 runId 까지 정확히 물고 있어 안전합니다.)
     */
    @Test
    @DisplayName("정답 5 — 시그널과 쿼리, 그리고 결과 회수")
    void 정답5_시그널_쿼리() {
        when(payment.charge(any(), anyLong())).thenReturn("PAY-1111");
        when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-2222");

        testEnv.start();
        OrderWorkflow wf = stub("order-2005");

        WorkflowExecution exec = WorkflowClient.start(wf::processOrder, req("2005"));
        assertThat(exec.getWorkflowId()).isEqualTo("order-2005");

        testEnv.sleep(Duration.ofSeconds(1));
        assertThat(wf.getStatus()).isEqualTo("WAITING_SHIPMENT");

        wf.cancelRequested("고객 변심");
        testEnv.sleep(Duration.ofSeconds(1));
        assertThat(wf.getStatus()).isEqualTo("CANCELED");

        String result = WorkflowStub.fromTyped(wf).getResult(String.class);
        assertThat(result).isEqualTo("order-2005 CANCELED");
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 6 — InOrder 로 보상 순서 검증
     * ─────────────────────────────────────────────────────────────────
     * 문제지에 있던 버전:
     *     verify(inventory).release("RSV-2222");
     *     verify(payment).refund("PAY-1111");
     *
     * 이건 "둘 다 한 번씩 호출되었다"만 봅니다. 보상 순서를 뒤집은 구현
     * (refund 먼저 → release 나중) 에서도 그대로 통과합니다.
     * 즉 실무에서 가장 위험한 버그(보상 순서 오류)를 못 잡습니다.
     *
     * 왜 순서가 중요한가:
     *   Saga 보상은 "실행의 역순"이어야 합니다. 결제 → 재고예약 → 배송 순으로 했다면
     *   보상은 재고해제 → 환불 순입니다. 순서가 뒤집히면
     *   "환불은 끝났는데 재고 해제 도중 장애" 같은 상황에서
     *   고객은 돈을 돌려받았는데 재고는 잠긴 채 남습니다.
     *
     * InOrder 버전으로 바꾸면, 보상 순서를 뒤집은 구현에서 이렇게 실패합니다.
     *
     *     org.mockito.exceptions.verification.VerificationInOrderFailure:
     *     Verification in order failure
     *     Wanted but not invoked:
     *     inventoryActivity.release("RSV-2222");
     *     Wanted anywhere AFTER following interaction:
     *     paymentActivity.refund("PAY-1111");
     *
     * 마지막 verify(shipping, never()).cancelShipment(...) 도 의미가 있습니다.
     * 배송은 애초에 성공한 적이 없으므로 배송 취소 보상은 호출되면 안 됩니다.
     * "성공하지 않은 단계는 보상하지 않는다"는 Saga 의 기본 규칙을 못박는 단언입니다.
     */
    @Test
    @DisplayName("정답 6 — 보상이 역순으로 실행된다")
    void 정답6_보상_역순() {
        when(payment.charge("2006", 39000L)).thenReturn("PAY-1111");
        when(inventory.reserve("2006", "SKU-A", 2)).thenReturn("RSV-2222");
        when(shipping.requestShipment(eq("2006"), anyString())).thenThrow(
                ApplicationFailure.newNonRetryableFailure("배송 불가 지역", "ShippingUnavailable"));

        testEnv.start();

        assertThatThrownBy(() -> stub("order-2006").processOrder(req("2006")))
                .isInstanceOf(WorkflowFailedException.class)
                .hasRootCauseMessage("배송 불가 지역");

        InOrder ord = inOrder(payment, inventory, shipping);
        ord.verify(payment).charge("2006", 39000L);
        ord.verify(inventory).reserve("2006", "SKU-A", 2);
        ord.verify(shipping).requestShipment(eq("2006"), anyString());
        ord.verify(inventory).release("RSV-2222");
        ord.verify(payment).refund("PAY-1111");

        verify(shipping, never()).cancelShipment(anyString());
    }

    /*
     * ─────────────────────────────────────────────────────────────────
     * 정답 7 — 리플레이 테스트
     * ─────────────────────────────────────────────────────────────────
     * @MethodSource 가 참조하는 메서드는 static 이어야 하고,
     * Stream / Iterable / 배열을 반환해야 합니다. 여기서는 Stream<Path> 입니다.
     *
     * Files.list 는 스트림을 닫아야 하므로 try-with-resources 로 감싸고
     * toList() 로 즉시 소비한 뒤 다시 스트림으로 만듭니다.
     * (그냥 return Files.list(dir) 하면 파일 핸들이 누수됩니다.)
     *
     * ★ 가드 테스트를 반드시 함께 두세요.
     *   히스토리 디렉터리가 비면 @ParameterizedTest 는 "0건 실행"으로 끝나고,
     *   JUnit 설정에 따라 조용히 통과합니다. 즉 **리플레이 검증이 사라진 줄도 모르고**
     *   CI 초록불을 보게 됩니다. 리플레이 테스트를 무력화하는 가장 흔한 방식입니다.
     *
     * ★ Step 12 로 이어지는 숙제
     *   히스토리는 Namespace 의 Retention Period 안에서만 조회할 수 있습니다.
     *   기본 72시간이므로, 3일 지난 워크플로우는 temporal workflow show 가 NotFound 입니다.
     *   덤프 스크립트를 매일 도는 크론에 넣거나 Archival 을 켜세요.
     *
     * ★ 왜 이 테스트가 대체 불가능한가
     *   위 정답 1~6 의 워크플로우 테스트는 전부 "새 히스토리를 새 코드로" 실행합니다.
     *   새 코드끼리는 언제나 일관되므로, Workflow.getVersion() 가드를 빼먹어도 통과합니다.
     *   운영에 이미 떠 있는 워크플로우와의 호환성은 오직 이 테스트만 검증합니다.
     */
    static Stream<Path> histories() throws Exception {
        var url = Solution.class.getResource("/histories");
        if (url == null) {
            return Stream.empty();
        }
        Path dir = Paths.get(url.toURI());
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).toList().stream();
        }
    }

    @Test
    @DisplayName("정답 7-a — 히스토리 리소스가 비어 있지 않다 (가드)")
    void 정답7_가드() throws Exception {
        assertThat(histories().toList())
                .as("src/test/resources/histories/*.json 이 비면 리플레이 검증이 무력화됩니다")
                .isNotEmpty();
    }

    @ParameterizedTest(name = "replay {0}")
    @MethodSource("histories")
    @DisplayName("정답 7-b — 모든 운영 히스토리가 현재 코드로 리플레이된다")
    void 정답7_리플레이(Path history) throws Exception {
        WorkflowReplayer.replayWorkflowExecution(
                Files.readString(history), OrderWorkflowImpl.class);
    }
}
