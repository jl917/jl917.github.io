package com.example.order;

/*
 * Step 10 — 버저닝과 무중단 배포 / Solution.java
 *
 * Exercise.java 7문제의 정답과 해설입니다.
 * 반드시 먼저 풀어 본 뒤에 여세요.
 *
 * 실행: ./gradlew run -PmainClass=com.example.order.Solution
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;

public class Solution {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    /*
     * changeId 는 "무엇을 바꿨는지" 가 드러나는 이름으로.
     * "v2" 같은 이름을 재사용하면 두 번째 변경이 첫 번째의 마커를 읽어
     * 엉뚱한 버전을 받습니다. 한번 정하면 오타가 있어도 절대 바꾸지 않습니다.
     * 문자열을 바꾸면 기존 마커를 못 찾아 옛 실행이 전부 DEFAULT_VERSION 으로 떨어집니다.
     */
    public static final String CHANGE_ID = "addFraudCheck";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {
    }

    static final ActivityOptions OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface FraudActivity {
        @ActivityMethod void check(String customerId, long amount);
    }

    @ActivityInterface
    public interface CreditActivity {
        @ActivityMethod void verify(String customerId);
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod void notifyCustomer(String orderId, String message);
    }

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    /* ==================================================================
     * 정답 1 — NonDeterministicException 과 "event 5"
     * ==================================================================
     *
     * 예외 전문:
     * ─────────
     * io.temporal.worker.NonDeterministicException: Failure handling event 5 of type
     * 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay. Event 5 of type
     * EVENT_TYPE_ACTIVITY_TASK_SCHEDULED does not match command type COMMAND_TYPE_START_TIMER
     *     at io.temporal.internal.statemachines.WorkflowStateMachines.handleCommandEvent(...)
     *     at io.temporal.internal.statemachines.WorkflowStateMachines.handleEventImpl(...)
     *     ...
     *
     * 왜 5인가
     * ────────
     * 이벤트 1~4 는 "어떤 워크플로우 코드에서도 똑같이 생기는 고정 이벤트" 입니다.
     *
     *   1  WorkflowExecutionStarted   ← 서버가 실행 시작을 기록
     *   2  WorkflowTaskScheduled      ← 서버가 첫 Workflow Task 를 큐에 넣음
     *   3  WorkflowTaskStarted        ← Worker 가 그것을 가져감
     *   4  WorkflowTaskCompleted      ← Worker 가 Command 목록을 반환
     *
     * 즉 코드가 처음으로 발행하는 Command 는 항상 5번 자리에 옵니다.
     * 그래서 "워크플로우 메서드의 첫 줄을 바꾸면 언제나 event 5" 입니다.
     * 반대로 예외 메시지의 이벤트 번호를 보면 코드의 어느 지점이 어긋났는지
     * 역산할 수 있습니다. 이것이 이 예외를 읽는 방법입니다.
     *
     * 그리고 메시지의 "does not match command type COMMAND_TYPE_START_TIMER" 를
     * 주의 깊게 보세요. 새 코드가 fraud check 를 먼저 하면서 Command 열이
     * 한 칸씩 밀렸고, 결국 히스토리 이벤트 5(ActivityTaskScheduled)에
     * 코드의 타이머 Command 가 대응하게 된 것입니다.
     * "액티비티를 추가했는데 왜 타이머 얘기가 나오나?" 의 답입니다.
     *
     * 깨진 뒤의 상태
     * ─────────────
     *   temporal workflow describe -w order-3001
     *     Status                RUNNING       ← 죽지 않는다
     *     History Length        12            ← 늘지 않는다
     *     Pending Workflow Task:
     *       Attempt             47            ← 이것만 계속 올라간다
     *
     * Workflow Task 실패는 액티비티와 달리 최대 시도 횟수가 없습니다.
     * Temporal 은 이것을 "코드 배포로 고칠 수 있는 일시적 장애" 로 취급합니다.
     * 그래서 옛 코드를 다시 배포하면 다음 재시도에서 자동으로 이어집니다.
     */

    /* ==================================================================
     * 정답 2 — 안전/위험 분류
     * ==================================================================
     *
     * (a) charge() 안의 PG 호출 라이브러리 교체
     *     → 안전. 액티비티는 워크플로우 밖(별도 스레드/프로세스)에서 실행되고,
     *       히스토리에는 ActivityTaskCompleted 의 "결과" 만 남습니다.
     *       내부를 아무리 바꿔도 Command 열은 동일합니다.
     *
     * (b) charge → chargePayment 로 메서드명 변경
     *     → 위험. ActivityTaskScheduled 의 activityType.name 이
     *       "Charge" → "ChargePayment" 로 달라져 리플레이 시 불일치.
     *       (@ActivityMethod(name="Charge") 로 이름을 고정해 두면 안전해집니다.
     *        메서드명 리팩터링이 잦다면 처음부터 name 을 명시하세요.)
     *
     * (c) maximumAttempts 3 → 10           ⚠️ 함정
     *     → 안전. 단 "리플레이가 안 깨진다" 는 뜻일 뿐입니다.
     *       ★ 이미 ActivityTaskScheduled 로 스케줄되어 재시도 중인 액티비티는
     *         옛 정책(3회)으로 끝납니다. 재시도 정책은 스케줄 시점에
     *         이벤트 속성으로 박제되기 때문입니다.
     *       "재시도 늘려서 배포했는데 왜 여전히 3번 만에 죽나요?" 의 답.
     *
     * (d) sleep(30일) → sleep(14일)
     *     → 위험. TimerStarted 이벤트의 startToFireTimeout 속성이 달라집니다.
     *       (Command 종류는 같아도 속성이 다르면 SDK 버전에 따라 불일치 판정)
     *       이미 타이머가 시작된 실행에는 어차피 반영되지 않으므로
     *       getVersion 으로 감싸는 것이 정석입니다.
     *
     * (e) log.info("...") 한 줄 추가
     *     → 안전. Workflow.getLogger 는 Command 를 만들지 않습니다.
     *       리플레이 중에는 SDK 가 자동으로 로그를 억제하므로
     *       "같은 로그가 100번 찍히는" 일도 없습니다.
     *       (단 System.out.println 은 억제되지 않으니 쓰지 마세요.)
     *
     * (f) OrderRequest 에 couponCode 필드 추가       ⚠️ 함정
     *     → 안전. 단 조건이 있습니다.
     *       ★ 옛 히스토리의 payload 에는 couponCode 가 없으므로,
     *         역직렬화 시 기본값(null)이 들어가야 합니다.
     *         Jackson 기본 설정에서 record 는 누락 필드를 null 로 채우므로 OK.
     *         하지만 FAIL_ON_MISSING_CREATOR_PROPERTIES 를 켜 두었거나
     *         커스텀 DataConverter 를 쓴다면 역직렬화가 터집니다.
     *       ★ 그리고 그 필드를 "워크플로우에서 쓰기 시작하는" 순간
     *         옛 실행은 null 을 보게 됩니다. NPE 방어가 필요합니다.
     *
     * (g) if (amount > 100000) → if (amount > 50000)
     *     → 위험. 7만원 주문은 옛 코드에서 A 경로, 새 코드에서 B 경로를 탑니다.
     *       리플레이 시 Command 열이 통째로 달라집니다.
     *       조건 분기 변경은 "액티비티 추가" 와 같은 급의 위험입니다.
     *
     * (h) 마지막 세 줄을 private 헬퍼로 추출         ⚠️ 함정
     *     → 안전. 단 조건이 있습니다.
     *       ★ 추출 과정에서 호출 순서가 바뀌지 않았을 때만입니다.
     *         "어차피 리팩터링이니까" 하고 두 줄의 순서를 정리하거나,
     *         조건문을 합치면서 평가 순서가 바뀌면 즉시 위험 변경이 됩니다.
     *       리팩터링 PR 은 "Command 를 내는 줄의 목록" 이 완전히 동일한지
     *       diff 로 확인하고 머지하세요.
     */

    /* ==================================================================
     * 정답 3 — getVersion 으로 감싸기
     * ==================================================================
     *
     * getVersion 의 위치가 왜 메서드 최상위여야 하나
     * ─────────────────────────────────────────────
     * getVersion 은 MarkerRecorded Command 를 발행합니다.
     * 즉 그 자체가 히스토리에 흔적을 남기는 "실행 가능한 문장" 입니다.
     * 조건문 안에 넣으면 마커의 유무가 실행마다 달라지고,
     * 그 조건이 나중에 바뀌면 리플레이가 깨집니다 (정답 5 참조).
     *
     * 빈 블록을 명시적으로 쓰는 스타일을 권합니다
     * ──────────────────────────────────────────
     *   if (version == Workflow.DEFAULT_VERSION) {
     *       // 아무것도 안 함
     *   } else {
     *       fraud.check(...);
     *   }
     *
     * 이게 아래보다 낫습니다.
     *
     *   if (version != Workflow.DEFAULT_VERSION) {   // 나중에 헷갈린다
     *       fraud.check(...);
     *   }
     *
     * 이유: 버전이 3~4개로 늘면 != 조건이 뒤엉킵니다.
     *       "version >= 1 이면서 version < 3 일 때" 같은 조건을 쓰기 시작하면
     *       어느 실행이 어느 경로를 타는지 추적이 불가능해집니다.
     *       버전마다 하나의 블록을 두는 것이 유일하게 읽히는 형태입니다.
     */
    public static class Q3Solution implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Q3Solution.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // 메서드 최상위, 무조건 호출
            int version = Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
            log.info("[{}] version={}", req.orderId(), version);

            if (version == Workflow.DEFAULT_VERSION) {
                // 이 변경 이전에 시작된 실행 — 아무것도 하지 않는다
            } else {
                fraud.check(req.customerId(), req.amount());
            }

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 4 — 2차 변경
     * ==================================================================
     *
     * 세 종류 실행의 히스토리 비교
     * ───────────────────────────
     *
     * order-3001 (v1 으로 시작, 마커 없음)
     *   1  WorkflowExecutionStarted
     *   2  WorkflowTaskScheduled
     *   3  WorkflowTaskStarted
     *   4  WorkflowTaskCompleted
     *   5  ActivityTaskScheduled   [charge]     ← 마커가 없다
     *   ...
     *   → getVersion 은 DEFAULT_VERSION(-1) 반환
     *   → 리플레이 중이므로 새 마커도 쓰지 않는다
     *
     * order-3002 (문제 3 배포 후 시작, 마커 version=1)
     *   5  MarkerRecorded          [Version]
     *   6  ActivityTaskScheduled   [check]
     *   7  ActivityTaskStarted
     *   8  ActivityTaskCompleted
     *   9  WorkflowTaskScheduled
     *   ...
     *  13  ActivityTaskScheduled   [charge]
     *
     *   JSON:
     *     "markerRecordedEventAttributes": {
     *       "markerName": "Version",
     *       "details": {
     *         "changeId": { "payloads": [{ "data": "ImFkZEZyYXVkQ2hlY2si" }] },
     *         "version":  { "payloads": [{ "data": "MQ==" }] }
     *       }
     *     }
     *   Base64 디코드:  ImFkZEZyYXVkQ2hlY2si → "addFraudCheck"
     *                   MQ==                 → 1
     *
     * order-3003 (이 배포 후 시작, 마커 version=2)
     *   5  MarkerRecorded          [Version]
     *   6  ActivityTaskScheduled   [check]
     *   ...
     *  13  ActivityTaskScheduled   [verify]     ← 이게 추가됨
     *   ...
     *
     *   Base64 디코드:  Mg==  → 2
     *
     * 핵심: order-3002 는 코드가 버전 2를 지원하게 되어도
     *       영원히 version=1 을 받습니다. 마커가 히스토리에 박제됐기 때문입니다.
     *       그래서 credit.verify() 는 절대 실행되지 않습니다.
     *       "배포했는데 왜 기존 주문에는 신용조회가 안 들어가나요?" 의 답이자,
     *       바로 그것이 이 패턴의 목적입니다.
     */
    public static class Q4Solution implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Q4Solution.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final CreditActivity credit =
                Workflow.newActivityStub(CreditActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // 같은 changeId 로 maxSupported 만 확장한다
            int version = Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 2);
            log.info("[{}] version={}", req.orderId(), version);

            if (version == Workflow.DEFAULT_VERSION) {
                // 아무것도 안 함
            } else if (version == 1) {
                fraud.check(req.customerId(), req.amount());
            } else {
                // version == 2
                fraud.check(req.customerId(), req.amount());
                credit.verify(req.customerId());
            }

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 5 — getVersion 을 조건문 안에 넣으면
     * ==================================================================
     *
     * (1) 히스토리가 어떻게 달라지나
     * ─────────────────────────────
     *   amount=39000  (조건 불만족)
     *     5  ActivityTaskScheduled   [charge]      ← 마커 없음
     *    11  TimerStarted
     *
     *   amount=250000 (조건 만족)
     *     5  MarkerRecorded          [Version]     ← 마커 생김
     *     6  ActivityTaskScheduled   [check]
     *    ...
     *    13  ActivityTaskScheduled   [charge]
     *
     *   같은 코드인데 히스토리 구조가 다릅니다.
     *   이 자체는 아직 깨지지 않습니다 — 각자 자기 히스토리로 리플레이하니까요.
     *
     * (2) 왜 메트릭에서 발견되기 어려운가
     * ──────────────────────────────────
     *   ★ 이 버그는 조건이 참인 실행에서만 나타납니다.
     *   10만원 초과 주문이 전체의 1% 라면, 99%는 완벽하게 정상입니다.
     *   NonDeterminismError 카운트가 시간당 3~4건 나오는데
     *   전체 처리량이 수만 건이면 대시보드에서 노이즈로 묻힙니다.
     *   "가끔 몇 건 실패하는데 재시도되니까 괜찮겠지" 로 넘어갑니다.
     *   실제로는 그 몇 건이 재시도되는 게 아니라 영원히 멈춰 있습니다.
     *
     * (3) 임계값을 100000 → 50000 으로 바꾸면
     * ──────────────────────────────────────
     *   ★ 5만~10만원 구간의 실행들이 전부 깨집니다.
     *   그 실행들은 옛 코드에서 "조건 불만족" 이라 마커 없이 시작됐는데,
     *   새 코드로 리플레이하면 "조건 만족" 이 되어 마커를 만들려 합니다.
     *
     *     NonDeterministicException: Failure handling event 5 of type
     *     'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay. Event 5 of type
     *     EVENT_TYPE_ACTIVITY_TASK_SCHEDULED does not match command type
     *     COMMAND_TYPE_RECORD_MARKER
     *
     *   그리고 이 변경은 "비즈니스 로직의 임계값 조정" 이라
     *   워크플로우 버저닝을 검토할 생각조차 안 하게 됩니다.
     *   PR 리뷰에서 숫자 하나 바뀐 걸 보고 위험하다고 느낄 사람은 없습니다.
     *
     *   교훈: getVersion 을 조건문 안에 넣는 순간,
     *         그 조건과 관련된 모든 미래의 변경이 지뢰가 됩니다.
     */
    public static class Q5BadSolution implements OrderWorkflow {

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // ⛔ 절대 이렇게 쓰지 마세요. 재현용입니다.
            if (req.amount() > 100000) {
                int v = Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
                if (v != Workflow.DEFAULT_VERSION) {
                    fraud.check(req.customerId(), req.amount());
                }
            }
            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 6 — getVersion 을 삭제하면
     * ==================================================================
     *
     * (c) 예외 메시지
     * ──────────────
     *   io.temporal.worker.NonDeterministicException: Failure handling event 5 of type
     *   'EVENT_TYPE_MARKER_RECORDED' during replay. Event 5 of type
     *   EVENT_TYPE_MARKER_RECORDED does not match command type
     *   COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK
     *
     *   이유: order-3002 의 히스토리 이벤트 5는 MarkerRecorded 입니다.
     *         getVersion 호출 자체가 그 마커를 만들었기 때문입니다.
     *         호출을 지운 코드는 첫 Command 로 ScheduleActivityTask(Check) 를 냅니다.
     *         → 마커를 기대하는 히스토리와 어긋납니다.
     *
     *   ★ 아이러니: 옛 실행을 살리려고 넣은 코드가, 지우는 순간 그 실행들을 죽입니다.
     *
     * (d) 올바른 삭제 순서 3단계
     * ─────────────────────────
     *   1) 분기만 제거한다. getVersion 호출은 남긴다.
     *        int version = Workflow.getVersion(CHANGE_ID, 2, 2);   // min 을 올림
     *        fraud.check(...);   // if/else 없이
     *        credit.verify(...);
     *
     *      minSupported 보다 낮은 버전이 히스토리에 있으면
     *      SDK 가 UnsupportedVersion 예외를 던지므로,
     *      이 시점에 version=1 실행이 정말 없어야 합니다.
     *
     *        temporal workflow count --query \
     *          'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running" \
     *           AND StartTime < "2026-03-18T00:00:00Z"'
     *        Total: 0
     *
     *   2) 마커가 있는 실행이 전부 종료되고 Retention 도 지날 때까지 기다린다.
     *
     *        안전 시점 = 배포 시각 + 워크플로우 최장 수명 + Retention Period
     *
     *        temporal operator namespace describe default
     *          Config.WorkflowExecutionRetentionTtl   30 days 0h 0m 0s
     *
     *        30일 sleep + 30일 retention = 60일 뒤.
     *        (종료된 워크플로우도 리플레이 테스트 대상이므로 retention 을 포함시킵니다.)
     *
     *   3) 그제서야 getVersion 호출 자체를 삭제한다.
     *
     * ★ 실무 결론
     * ───────────
     *   많은 팀이 3)을 영원히 하지 않습니다. 그리고 그게 옳은 판단일 수 있습니다.
     *   getVersion 한 줄이 코드에 남아 있는 비용은
     *   "60일을 잘못 세어서 4,000개 워크플로우를 멈추는" 사고보다 훨씬 쌉니다.
     *   1)까지만 하고 (분기를 없애 가독성을 회복하고) 호출은 남겨 두세요.
     */
    public static class Q6Solution implements OrderWorkflow {

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final CreditActivity credit =
                Workflow.newActivityStub(CreditActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // 1단계: 분기만 제거. 호출은 남긴다. 마커는 계속 기록된다.
            Workflow.getVersion(CHANGE_ID, 2, 2);

            fraud.check(req.customerId(), req.amount());
            credit.verify(req.customerId());
            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 정답 7 — reset 으로 복구
     * ==================================================================
     *
     * (a) 명령
     *   temporal workflow reset --workflow-id order-3001 \
     *     --type LastWorkflowTask --reason "실습"
     *
     * (b) 출력
     *   Reset workflow successfully.
     *     WorkflowId  order-3001
     *     RunId       8e30d1f9-4a72-4b88-9c05-6f1e3d7a2b44     ← 새 RunId
     *
     *   reset 은 기존 Run 을 수정하지 않습니다. 새 Run 을 만듭니다.
     *
     * (c) 새 히스토리
     *   옛 Run 의 히스토리는 지정 지점(마지막 성공한 WorkflowTaskCompleted)까지
     *   복사되고, 그 뒤에 WorkflowExecutionContinuedAsNew 로 닫힙니다.
     *
     *     Progress:
     *        1  WorkflowExecutionStarted
     *        2  WorkflowTaskScheduled
     *        3  WorkflowTaskStarted
     *        4  WorkflowTaskCompleted
     *        5  ActivityTaskScheduled     [charge]
     *        6  ActivityTaskStarted
     *        7  ActivityTaskCompleted      ← 이미 완료된 액티비티 결과는 재사용됨
     *        8  WorkflowTaskScheduled
     *        9  WorkflowTaskStarted
     *       10  WorkflowTaskCompleted
     *       11  WorkflowExecutionContinuedAsNew    ← reset 지점
     *
     *     Result:
     *       Status: CONTINUED_AS_NEW
     *
     *   새 Run 은 이 지점부터 현재 코드로 다시 진행합니다.
     *
     * (d) FirstWorkflowTask 로 하면 무엇이 위험한가
     * ────────────────────────────────────────────
     *   ★ 맨 처음부터 다시 실행하므로 charge() 가 다시 호출됩니다.
     *     LastWorkflowTask 는 이미 완료된 액티비티의 결과를 히스토리에서
     *     재사용하지만, FirstWorkflowTask 는 그 결과마저 버리기 때문입니다.
     *
     *   → charge 가 멱등하지 않으면 고객 카드에서 39,000원이 한 번 더 빠집니다.
     *
     *   Step 09 의 "멱등 키는 workflowId 기반으로" 가 여기서 생명줄이 됩니다.
     *   orderId 를 멱등 키로 PG 에 넘겼다면, 재실행 시 PG 가
     *   "이미 승인된 건" 이라며 같은 paymentId 를 돌려주고 이중 결제가 없습니다.
     *
     *   ★ 그리고 reset 은 Saga 보상을 되돌리지 않습니다.
     *     이미 refund 가 실행됐다면 그건 그대로 남아 있고,
     *     새 Run 이 charge 를 다시 하면 "환불 후 재결제" 가 됩니다.
     *     reset 전에 히스토리를 읽어 어디까지 진행됐는지 반드시 확인하세요.
     *
     * 배치 reset
     * ─────────
     *   temporal workflow reset-batch \
     *     --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running"' \
     *     --reset-type LastWorkflowTask --reason "v2 rollback"
     *
     *   Started batch reset operation.
     *     JobId  a3f81c07-52de-4b19-8e30-9d1c4f2a6b78
     *
     *   temporal batch describe --job-id a3f81c07-52de-4b19-8e30-9d1c4f2a6b78
     *
     * 복구 우선순위 정리
     * ─────────────────
     *   ① 코드 롤백    — 보상 실행 없음(불필요), 완전 복구.  ★ 항상 이것부터
     *   ② reset        — 액티비티 재실행됨, 되감기 가능
     *   ③ cancel       — 보상 실행됨, 하지만 깨진 워크플로우엔 안 통함
     *   ④ terminate    — 보상 실행 안 됨. 최후 수단. 수동 정리 필수
     */

    public static void main(String[] args) {
        System.out.println("""
                Solution.java — 정답 7개.

                핵심 요약:
                  · event 5 = 코드가 만드는 첫 Command 의 자리 (1~4 는 고정)
                  · 안전/위험 판정 기준 = Command 의 종류·순서·개수가 바뀌는가
                  · getVersion 은 메서드 최상위에서 무조건 호출, 반환값으로만 분기
                  · MarkerRecorded 가 히스토리에 박제되므로 옛 실행은 영원히 옛 버전
                  · getVersion 호출을 지우면 그 마커와 어긋나 다시 깨진다
                  · 복구는 코드 롤백 > reset > terminate 순
                """);
    }
}
