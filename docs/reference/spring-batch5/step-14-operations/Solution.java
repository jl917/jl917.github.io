package com.example.batch.step14;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Step 14 — 연습문제 정답과 해설
 *
 * 이 코스의 마지막 파일입니다. 문제를 직접 풀어 본 뒤에 여십시오.
 */
public class Solution {

    // =====================================================================
    // 정답 1. 종료 코드 확인
    // =====================================================================

    /*
     * (a) 성공 시 → **0**
     *
     *     java -jar app.jar --spring.batch.job.enabled=true \
     *       --spring.batch.job.name=dailySettlementJob date=2025-03-01
     *     echo $?
     *     → 0
     *
     * (b) 실패 시 (SpringApplication.exit 감싸기 있음) → **1**
     *
     *     같은 명령을 한 번 더 실행하면
     *     JobInstanceAlreadyCompleteException 이 나고
     *     → 1
     *
     * (c) 실패 시 (감싸기 제거) → **0**
     *
     *     main 을 이렇게 바꾸면:
     *       public static void main(String[] args) {
     *           SpringApplication.run(BatchLabApplication.class, args);
     *       }
     *
     *     Job 이 FAILED 로 끝나도 JVM 은 정상 종료로 간주해 0 을 반환합니다.
     *
     * (d) ⚠️ 왜 위험한가
     *
     *   **파이프라인이 실패를 인지하지 못합니다.**
     *
     *     - 크론: 종료 코드 0 이면 성공으로 보고 메일도 안 보냅니다.
     *     - 에어플로우: 태스크가 초록불로 뜹니다. 다음 태스크가 진행됩니다.
     *     - 쿠버네티스 Job: Succeeded 로 마킹되고 재시도하지 않습니다.
     *     - CI/CD: 배포 검증 단계가 통과합니다.
     *
     *   정산이 실패했는데 **모든 계기판이 초록색**입니다.
     *
     *   그리고 이것이 로그를 봐도 발견되지 않는 이유:
     *   **로그에는 정상적으로 FAILED 가 찍혀 있습니다.**
     *
     *     INFO ... Job: [SimpleJob: [name=dailySettlementJob]] completed
     *              with ... the following status: [FAILED]
     *
     *   로그는 진실을 말하고 있습니다. 문제는 아무도 로그를 안 본다는
     *   것입니다. 사람들은 파이프라인의 초록불을 봅니다. 그리고
     *   파이프라인은 종료 코드만 봅니다.
     *
     *   즉 **로그와 파이프라인이 서로 다른 이야기를 하고 있고, 사람은
     *   틀린 쪽을 봅니다.**
     *
     *   ⚠️ 확인 방법은 딱 하나입니다.
     *      **일부러 실패시켜 보고 echo $? 를 찍어 보십시오.**
     *      새 배치를 운영에 올리기 전 반드시 한 번 하십시오.
     *      이것이 배포 전 체크리스트에서 가장 자주 빠지는 항목입니다.
     */

    // =====================================================================
    // 정답 2. 좀비 실행 — 중복을 막으려던 장치가 실행을 막는다
    // =====================================================================

    /*
     * (c) kill -9 후 남아 있는 것
     *
     *   +------------------+---------+---------------------+----------+
     *   | JOB_EXECUTION_ID | STATUS  | START_TIME          | END_TIME |
     *   +------------------+---------+---------------------+----------+
     *   |               52 | STARTED | 2025-07-20 14:31:07 | NULL     |
     *   +------------------+---------+---------------------+----------+
     *
     *   STATUS  = 'STARTED'
     *   END_TIME = NULL
     *
     *   프로세스가 SIGKILL 로 죽었으므로 셧다운 훅도, 예외 처리도,
     *   afterJob 리스너도 실행되지 않았습니다. Spring Batch 는
     *   "시작했다"까지만 기록하고 그 뒤를 쓰지 못했습니다.
     *
     *   ⚠️ 그리고 **아무도 이 행을 정리해 주지 않습니다.**
     *      다음에 애플리케이션이 떠도, 다음 날이 되어도 그대로입니다.
     *      영원히 STARTED 로 남습니다.
     *
     * (d) runNow() 를 호출하면
     *
     *   WARN c.e.b.step14.SettlementScheduler : 이전 정산 배치가 아직
     *        실행 중입니다. 이번 실행을 건너뜁니다. running=[JobExecution:
     *        id=52, version=1, startTime=2025-07-20T14:31:07, endTime=null,
     *        ...status=STARTED...]
     *
     *   2차 방어가 작동했습니다. findRunningJobExecutions 가 52번을
     *   "실행 중"으로 보고했기 때문입니다.
     *
     *   그런데 52번은 이미 죽은 프로세스입니다. 실행 중이 아닙니다.
     *
     * (e) ⚠️ 정산 배치는 **영원히 다시 돌지 않습니다**
     *
     *   내일도, 모레도, 사람이 손으로 메타데이터를 고치기 전까지
     *   매일 새벽 2시에 "이전 배치가 실행 중"이라며 건너뜁니다.
     *
     *   **중복 실행을 막으려던 장치가 실행 자체를 막고 있습니다.**
     *
     *   이것이 이 스텝에서 가장 실전적인 함정인 이유:
     *     - 에러가 안 납니다. WARN 로그 한 줄이 전부입니다.
     *     - 배치는 "정상적으로" 건너뛰기를 수행합니다.
     *     - 실패 알림이 안 갑니다. 실패한 게 아니라 안 돈 것이니까요.
     *     - 그래서 **14-7 의 "안 돌았음 알림"이 없으면 며칠씩 모릅니다.**
     *
     *   실제로 정산이 3일 밀린 뒤 회계팀이 발견하는 식으로 드러납니다.
     */

    /** (f) 좀비 정리 SQL. */
    public static final String CLEAN_ZOMBIES_SQL = """
            -- 1단계: 좀비 탐지 (먼저 눈으로 확인하십시오)
            SELECT je.JOB_EXECUTION_ID, ji.JOB_NAME, je.START_TIME,
                   TIMESTAMPDIFF(HOUR, je.START_TIME, NOW()) AS hours_ago
            FROM BATCH_JOB_EXECUTION je
            JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
            WHERE je.STATUS IN ('STARTED','STARTING')
              AND je.END_TIME IS NULL
              AND je.START_TIME < NOW() - INTERVAL 6 HOUR;

            -- 2단계: 정리
            UPDATE BATCH_JOB_EXECUTION
               SET STATUS = 'FAILED', EXIT_CODE = 'FAILED',
                   END_TIME = NOW(), LAST_UPDATED = NOW(),
                   EXIT_MESSAGE = '좀비 실행 수동 정리'
             WHERE JOB_EXECUTION_ID = ?;

            -- 3단계: StepExecution 도 함께 정리해야 합니다.
            --        이걸 빼먹으면 재시작 시 Step 상태가 꼬입니다.
            UPDATE BATCH_STEP_EXECUTION
               SET STATUS = 'FAILED', EXIT_CODE = 'FAILED',
                   END_TIME = NOW(), LAST_UPDATED = NOW()
             WHERE JOB_EXECUTION_ID = ? AND END_TIME IS NULL;
            """;

    /*
     * (g) ⚠️ ZOMBIE_THRESHOLD_HOURS 를 얼마로 잡을 것인가
     *
     *   **배치의 최대 실행 시간보다 넉넉히** 잡아야 합니다.
     *
     *   너무 짧게 잡으면 무슨 일이 벌어지는가:
     *
     *     정상적으로 3시간째 돌고 있는 배치가 있는데
     *     임계값을 2시간으로 잡아 두면,
     *     → ZombieCleaner 가 그 배치를 좀비로 오인합니다.
     *     → 메타데이터를 FAILED 로 바꿉니다.
     *     → 그런데 **프로세스는 여전히 살아서 돌고 있습니다.**
     *     → 배치가 끝나면 자기 상태를 COMPLETED 로 쓰려 하는데
     *       메타데이터가 이미 바뀌어 있어 낙관적 락 예외가 납니다.
     *
     *         org.springframework.dao.OptimisticLockingFailureException:
     *           Attempt to update step execution id=... with wrong version
     *
     *     → 최악의 경우, 다른 인스턴스가 "이제 안 돌고 있네" 하고
     *       **같은 배치를 동시에 시작합니다.** 정산이 두 배가 됩니다.
     *
     *   즉 **좀비 정리를 잘못하면 그게 중복 실행의 원인이 됩니다.**
     *   막으려던 것을 스스로 만들어 내는 셈입니다.
     *
     *   실무 지침:
     *     - 임계값 = 배치 최대 실행 시간 × 2 + 여유
     *       (정산이 최대 1시간이면 6시간 정도)
     *     - 자동 정리는 **기동 시점에만** 하십시오. 주기적으로 돌리면
     *       실행 중인 배치와 부딪힐 위험이 커집니다.
     *     - 더 안전한 방법: 정리 대상을 **자동으로 고치지 말고 알림만**
     *       보내고, 사람이 확인 후 처리하게 하는 것. 좀비는 자주 생기는
     *       일이 아니므로 수동 처리로도 충분합니다.
     *     - 근본 해결책은 **프로세스가 살아 있는지 직접 확인**하는 것입니다.
     *       BATCH_JOB_EXECUTION 에 호스트명/PID 를 남기면(커스텀 컬럼이나
     *       ExecutionContext) 진짜 좀비인지 판정할 수 있습니다.
     */

    // =====================================================================
    // 정답 3. DB 락 기반 3차 방어
    // =====================================================================

    /*
     * (c) 두 프로세스의 로그
     *
     *   [서버 A]
     *   INFO c.e.b.step14.JobLockService : 락 획득 성공: dailySettlementJob/2025-03-02
     *   INFO o.s.b.c.l.s.TaskExecutorJobLauncher : Job: [FlowJob: [name=dailySettlementJob]] launched ...
     *   INFO o.s.batch.core.step.AbstractStep : Step: [dailySettlementStep] executed in 408ms
     *   INFO o.s.b.c.l.s.TaskExecutorJobLauncher : ... status: [COMPLETED] in 461ms
     *
     *   [서버 B]
     *   WARN c.e.b.step14.JobLockService : 락 획득 실패 — 다른 인스턴스가 실행 중입니다. 종료합니다.
     *
     *   B 는 Job 을 아예 시작하지 않고 종료했습니다.
     *
     *   ⚠️ 여기서 중요한 것: **1차 방어(JobInstance)는 이 경우를 못
     *      막았을 것입니다.** 두 프로세스가 동시에 시작하면 둘 다
     *      "기존 JobInstance 없음"을 확인하고 둘 다 진행합니다.
     *      DB 의 PK 제약만이 원자적으로 하나를 튕겨냅니다.
     *
     * (d) ⚠️ 락 실패 시 종료 코드 — 까다로운 질문
     *
     *   **1(실패)로 하면:**
     *     크론이 실패로 인지하고 알림을 보냅니다. 그런데 중복 실행을
     *     막은 것은 **정상 동작**입니다. 알림이 갈 이유가 없습니다.
     *     매일 오탐 알림이 오면 사람들이 알림을 무시하게 되고,
     *     결국 진짜 실패도 놓칩니다. (알림 피로)
     *
     *   **0(성공)으로 하면:**
     *     파이프라인이 "배치가 돌았다"고 인식합니다. 그런데 안 돌았습니다.
     *     14-7 의 "안 돌았음 알림"도 무력화됩니다. 종료 코드만 보면
     *     성공이니까요.
     *
     *   **권장: 별도 종료 코드(예: 3)를 쓰고 파이프라인에서 구분합니다.**
     *
     *     @Component
     *     public class LockExitCodeGenerator implements ExitCodeGenerator {
     *         public int getExitCode() { return lockAcquired ? 0 : 3; }
     *     }
     *
     *   그리고 크론/에어플로우 쪽에서:
     *     - 0 → 성공
     *     - 3 → 스킵됨. 알림 없음. 단, **연속 3회 이상이면 알림**
     *           (락이 안 풀리고 있다는 뜻)
     *     - 그 외 → 실패. 알림.
     *
     *   "연속 3회 이상이면 알림"이 중요합니다. 락 해제 실패로 인한
     *   교착을 잡아 주기 때문입니다.
     *
     * (e) 락 해제 실패 대비
     *
     *   프로세스가 죽으면 finally 블록도 실행되지 않아 락이 남습니다.
     *   좀비 실행과 똑같은 문제입니다.
     *
     *   대비책 세 가지:
     *
     *   ① **TTL 을 두십시오.**
     *      락 테이블에 acquired_at 이 있으므로, 획득 시 오래된 락을
     *      먼저 지웁니다.
     *        DELETE FROM batch_job_lock
     *         WHERE job_name = ? AND acquired_at < NOW() - INTERVAL 6 HOUR;
     *      그 다음 INSERT 를 시도합니다. 원자성을 유지하려면 이 둘을
     *      한 트랜잭션에 넣으십시오.
     *
     *   ② **holder 컬럼을 활용하십시오.**
     *      호스트명이 기록되므로, 그 호스트가 살아 있는지 확인할 수
     *      있습니다. 운영자가 조사할 때 결정적인 단서가 됩니다.
     *
     *   ③ **락 대신 Quartz 클러스터링을 쓰십시오.**
     *      isClustered: true 로 두면 Quartz 가 자체 DB 락으로 하나만
     *      실행하도록 보장하고, **TTL 관리도 알아서 합니다.**
     *      직접 만든 락보다 검증된 구현을 쓰는 편이 낫습니다.
     *
     *   ⚠️ 일반 원칙: **분산 락에는 반드시 만료 시간이 있어야 합니다.**
     *      만료 없는 락은 언젠가 반드시 교착을 만듭니다.
     */

    // =====================================================================
    // 정답 4. 재시도가 있었던 날 찾기
    // =====================================================================

    /** (a) 단순 버전 — 하루에 2회 이상 실행된 날. */
    public static final String RETRIED_DAYS_SIMPLE = """
            SELECT DATE(je.START_TIME) AS d, COUNT(*) AS runs
            FROM BATCH_JOB_EXECUTION je
            JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
            WHERE ji.JOB_NAME = 'dailySettlementJob'
              AND je.START_TIME >= NOW() - INTERVAL 7 DAY
            GROUP BY DATE(je.START_TIME)
            HAVING runs > 1
            ORDER BY d DESC;
            """;

    /**
     * (b) 정밀 버전 — "조용히 넘어간 날".
     *
     * 첫 실행이 실패하고 나중 실행이 성공한 날만 골라냅니다.
     * 단순히 2회 이상인 것과 다릅니다. 운영자가 파라미터를 바꿔
     * 두 번 돌린 경우(둘 다 성공)는 제외됩니다.
     */
    public static final String SILENTLY_RECOVERED_DAYS = """
            SELECT DATE(je.START_TIME)                          AS d,
                   COUNT(*)                                     AS runs,
                   SUM(je.STATUS = 'FAILED')                    AS failures,
                   SUM(je.STATUS = 'COMPLETED')                 AS successes,
                   MIN(CASE WHEN je.STATUS = 'FAILED'
                            THEN LEFT(je.EXIT_MESSAGE, 60) END) AS first_error
            FROM BATCH_JOB_EXECUTION je
            JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
            WHERE ji.JOB_NAME = 'dailySettlementJob'
              AND je.START_TIME >= NOW() - INTERVAL 7 DAY
            GROUP BY DATE(je.START_TIME)
            HAVING failures > 0 AND successes > 0
            ORDER BY d DESC;
            """;

    /*
     * 결과 예시:
     *
     * +------------+------+----------+-----------+----------------------------------+
     * | d          | runs | failures | successes | first_error                      |
     * +------------+------+----------+-----------+----------------------------------+
     * | 2025-07-19 |    2 |        1 |         1 | org.springframework.dao.Deadlock |
     * | 2025-07-16 |    3 |        2 |         1 | java.lang.IllegalArgumentExcep   |
     * +------------+------+----------+-----------+----------------------------------+
     *
     * (c) ⚠️ 왜 이 쿼리가 중요한가
     *
     *   **재시도해서 결국 성공한 날은 알림이 안 갔을 가능성이 큽니다.**
     *
     *   에어플로우나 쿠버네티스의 자동 재시도(backoffLimit)를 켜 두면,
     *   1회차가 실패해도 2회차가 성공하면 태스크는 초록불입니다.
     *   사람은 아무것도 모릅니다.
     *
     *   하루 이틀이면 "일시적 데드락이었나 보다" 하고 넘어갈 수 있습니다.
     *   그런데 이런 날이 **반복되면** 그건 다른 이야기입니다.
     *
     *     - 데드락이 반복 → 다른 배치와 락 순서가 충돌하고 있음
     *     - 타임아웃이 반복 → 데이터가 늘어 배치가 한계에 근접
     *     - 특정 요일에 집중 → 주간 배치와 겹치는 스케줄 문제
     *
     *   전부 **재시도로 덮이지만 근본 원인이 있는** 경우입니다.
     *   그리고 언젠가는 재시도로도 안 되는 날이 옵니다. 대개
     *   데이터가 가장 많은 날, 즉 가장 중요한 날입니다.
     *
     *   이 쿼리를 주간 리포트에 넣으십시오. "조용한 재시도"의 추세가
     *   장애의 선행 지표입니다.
     */

    // =====================================================================
    // 정답 5. "안 돌았음" 알림 규칙
    // =====================================================================

    /*
     * (a)(b) PromQL 과 시간 창 → **26시간을 권장합니다**
     *
     *   groups:
     *     - name: batch
     *       rules:
     *         - alert: SettlementJobMissing
     *           expr: |
     *             time() - max(
     *               spring_batch_job_seconds_count{
     *                 name="dailySettlementJob", status="COMPLETED"
     *               }
     *             ) > 93600
     *           for: 10m
     *           labels:
     *             severity: critical
     *           annotations:
     *             summary: "정산 배치가 26시간째 성공하지 않았습니다"
     *
     *   93600초 = 26시간
     *
     *   근거:
     *     - 배치는 매일 새벽 2시에 돕니다 → 정상 간격은 24시간
     *     - 배치 자체가 최대 1시간 걸릴 수 있음 → +1시간
     *     - 스케줄러 지연, 락 대기, 재시도 여유 → +1시간
     *     → 26시간
     *
     * (c) ⚠️ 트레이드오프
     *
     *   **너무 짧게 잡으면 (예: 24시간)**
     *     배치가 조금만 늦어도 알림이 갑니다. 새벽 2시에 도는 배치가
     *     2시 30분에 돌면 그날은 24.5시간 간격이 되어 오탐입니다.
     *     매주 두세 번씩 오탐이 오면 사람들이 알림 채널을 음소거합니다.
     *     그리고 진짜 장애를 놓칩니다. **알림 피로가 알림 부재보다
     *     위험합니다.**
     *
     *   **너무 길게 잡으면 (예: 50시간)**
     *     이틀치 정산을 놓치고 나서야 알림이 옵니다. 정산 배치라면
     *     이미 회계 마감에 영향을 준 뒤입니다.
     *     감지 지연 자체가 손실입니다.
     *
     *   26시간이면 "새벽 2시에 안 돌았으면 다음 날 새벽 4시에 알림"
     *   입니다. 하루치만 놓치고 잡히므로 복구 가능한 범위입니다.
     *
     * (d) absent() 대안 비교
     *
     *   - alert: SettlementJobNeverRan
     *     expr: absent(spring_batch_job_seconds_count{name="dailySettlementJob"})
     *     for: 1h
     *
     *   | | time() - max(...) | absent(...) |
     *   |---|---|---|
     *   | 감지 대상 | "최근에 성공한 적 없음" | "지표 자체가 없음" |
     *   | 배치가 계속 실패 중 | **감지함** | 감지 못 함(지표는 있으니까) |
     *   | 배치 앱이 아예 안 뜸 | 감지함 | **감지함** |
     *   | 새로 배포한 직후 | 오탐 위험 | 오탐 위험 |
     *
     *   **둘 다 거십시오.** 서로 다른 실패 양상을 잡습니다.
     *   absent() 는 "애플리케이션이 아예 없다"(배포 누락, 파드 스케일 0)를
     *   잡고, time()-max() 는 "떠 있는데 안 돈다"를 잡습니다.
     *
     *   ⚠️ 그리고 일회성 CLI 배치라면 두 규칙 모두 Pushgateway 가
     *      전제입니다(14-7). pull 방식으로는 30초 만에 죽는 프로세스의
     *      지표를 못 긁습니다.
     *
     * (e) ⚠️ "실패 알림"과 "안 돌았음 알림" 중 무엇이 더 중요한가
     *
     *   **"안 돌았음 알림"이 더 중요합니다.**
     *
     *   실패는 이미 시끄럽습니다.
     *     - 로그에 스택트레이스가 남습니다
     *     - EXIT_MESSAGE 에 원인이 저장됩니다
     *     - 종료 코드가 1입니다
     *     - 메트릭에 status="FAILED" 가 올라갑니다
     *   네 가지 경로 중 하나만 걸려도 발견됩니다.
     *
     *   그런데 "아예 안 돈 것"은 **아무 흔적이 없습니다.**
     *     - 로그가 없습니다 (실행이 없었으니까)
     *     - 메타데이터에 행이 안 생깁니다
     *     - 종료 코드가 없습니다 (프로세스가 없었으니까)
     *     - 메트릭이 안 올라갑니다
     *
     *   **없음을 감지하려면 없음을 감시해야 합니다.** 그리고 이건
     *   의도적으로 규칙을 만들어야만 됩니다.
     *
     *   그리고 이 코스에서 배운 것 중 "안 돌게 만드는" 원인이
     *   얼마나 많았는지 떠올려 보십시오.
     *     - @EnableBatchProcessing 으로 자동설정이 꺼짐 (Step 02)
     *     - spring.batch.job.enabled: false 로 배포 (Step 14)
     *     - 좀비 실행 때문에 2차 방어가 영원히 스킵 (Step 14)
     *     - 스케줄러 풀 크기 1이라 다른 배치에 밀림 (Step 14)
     *     - Quartz misfire 정책으로 조용히 버려짐 (Step 14)
     *
     *   전부 "에러 없이 아무 일도 안 일어나는" 사고입니다.
     *   이 코스의 주제 그 자체입니다.
     */

    // =====================================================================
    // 정답 6. 검증 Step — 이 코스의 마지막 코드
    // =====================================================================

    /** (a) 검증 Step. */
    public static Step verifyStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager,
                                  DataSource dataSource,
                                  String date) {

        Logger log = LoggerFactory.getLogger("VerifyStep");

        return new StepBuilder("verifyStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

                    Integer target = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM orders
                            WHERE status = 'COMPLETED' AND DATE(ordered_at) = ?
                            """, Integer.class, date);

                    Integer settled = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM settlement WHERE settle_date = ?
                            """, Integer.class, date);

                    Integer skipped = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM s14_bad_order
                            WHERE DATE(occurred_at) = CURDATE()
                            """, Integer.class);

                    log.info("검증: 대상={}, 정산={}, skip={}", target, settled, skipped);

                    // (2) skip 된 건수는 정상적인 차이로 인정합니다.
                    int expected = target - skipped;
                    if (settled != expected) {
                        // (3) 어떤 주문이 누락됐는지 남깁니다.
                        List<Long> missing = jdbc.queryForList("""
                                SELECT o.order_id FROM orders o
                                LEFT JOIN settlement s ON s.order_id = o.order_id
                                WHERE o.status = 'COMPLETED'
                                  AND DATE(o.ordered_at) = ?
                                  AND s.order_id IS NULL
                                LIMIT 20
                                """, Long.class, date);
                        log.error("정산 누락 감지! 기대={}, 실제={}, 누락 예시={}",
                                expected, settled, missing);

                        // ⚠️ (b) 의 핵심: ExitStatus 가 아니라 **예외를 던져야** 합니다.
                        throw new IllegalStateException(
                                "정산 검증 실패: 기대=%d, 실제=%d".formatted(expected, settled));
                    }

                    log.info("검증 통과");
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    /*
     * (b) ⚠️ 함정 1 — setExitStatus(ExitStatus.FAILED) 로는 실패하지 않습니다
     *
     *   contribution.setExitStatus(ExitStatus.FAILED) 를 해도
     *   **BatchStatus 는 COMPLETED 로 남습니다.** (Step 01-5)
     *
     *   결과:
     *     - Job 이 COMPLETED 로 기록됩니다
     *     - 종료 코드가 0 입니다
     *     - 크론이 성공으로 인지합니다
     *     - **재실행이 차단됩니다** (JobInstanceAlreadyComplete)
     *
     *   검증에 실패했는데 "성공적으로 완료"로 남고, 심지어 다시 돌릴
     *   수도 없습니다. 최악의 조합입니다.
     *
     *   ExitStatus 는 "다음에 어느 Step 으로 갈지"를 정하는 꼬리표일 뿐
     *   실패 판정 장치가 아닙니다.
     *
     *   **진짜로 실패시키려면 예외를 던져야 합니다.**
     *
     * (c) ⚠️ 함정 2 — .on("FAILED").to(...).end() 는 실패를 은폐합니다
     *
     *   검증 실패 시 알림 Step 을 붙이고 싶어서 이렇게 쓰기 쉽습니다.
     *
     *     .start(dailySettlementStep)
     *     .next(verifyStep)
     *         .on("FAILED").to(alertStep).end()      // ← 위험
     *         .on("*").end()
     *
     *   그러면 **Step 은 FAILED 인데 Job 은 COMPLETED** 가 됩니다.
     *   (Step 10 의 핵심 함정)
     *
     *   .end() 는 "이 흐름을 성공으로 종료한다"는 뜻이기 때문입니다.
     *   결과적으로:
     *     - 종료 코드 0
     *     - 모니터링 침묵
     *     - 재시작 봉쇄
     *
     *   검증 Step 을 붙인 목적이 통째로 사라집니다. 오히려 검증이
     *   없느니만 못합니다 — 검증했다는 착각을 주니까요.
     *
     * (d) 올바른 Job 흐름 — 두 함정을 모두 피합니다
     */

    public static Job dailySettlementJobWithVerify(JobRepository jobRepository,
                                                   Step dailySettlementStep,
                                                   Step reportStep,
                                                   Step verifyStep,
                                                   Step alertStep) {
        return new JobBuilder("dailySettlementJob", jobRepository)
                .start(dailySettlementStep)
                .next(reportStep)
                .next(verifyStep)
                    // 검증 실패 시 알림 Step 을 거치되,
                    // 반드시 .fail() 로 끝내 실패를 전파합니다.
                    .on("FAILED").to(alertStep).on("*").fail()
                .from(verifyStep)
                    .on("*").end()
                .end()
                .build();
    }

    /*
     * 핵심은 `.on("FAILED").to(alertStep).on("*").fail()` 입니다.
     *
     *   - alertStep 은 실행됩니다 (알림은 갑니다)
     *   - 그 뒤 .fail() 이 Job 을 FAILED 로 만듭니다
     *   - 종료 코드 1
     *   - 재시작 가능
     *   - 모니터링 알림 발생
     *
     * (e) 검증 확인
     *
     *   mysql> DELETE FROM settlement WHERE settle_date='2025-03-01' LIMIT 5;
     *   Query OK, 5 rows affected
     *
     *   $ java -jar app.jar ... date=2025-03-01 attempt=verify
     *
     *   INFO  VerifyStep : 검증: 대상=389, 정산=384, skip=0
     *   ERROR VerifyStep : 정산 누락 감지! 기대=389, 실제=384,
     *                      누락 예시=[10, 1010, 2010, 3010, 4010]
     *   ERROR o.s.batch.core.step.AbstractStep : Encountered an error
     *         executing step verifyStep in job dailySettlementJob
     *   java.lang.IllegalStateException: 정산 검증 실패: 기대=389, 실제=384
     *   INFO  o.s.b.c.l.s.TaskExecutorJobLauncher : Job: [FlowJob:
     *         [name=dailySettlementJob]] completed ... status: [FAILED]
     *
     *   $ echo $?
     *   1
     *
     *   ─────────────────────────────────────────────────────────────
     *   이 마지막 문제에서 이 코스의 두 교훈이 합쳐집니다.
     *
     *     Step 01 — 실패시키려면 ExitStatus 가 아니라 예외를 던져라
     *     Step 10 — .end() 로 잡으면 실패가 은폐된다
     *
     *   그리고 그 위에 Step 14 의 교훈이 얹힙니다.
     *
     *     종료 코드 1 을 눈으로 확인하기 전까지,
     *     그 배치가 실패를 제대로 보고한다고 믿지 마십시오.
     *
     *   ─────────────────────────────────────────────────────────────
     *   마지막으로, 이 코스의 한 문장을 다시 남깁니다.
     *
     *     배치가 COMPLETED 로 끝났다는 사실은,
     *     그 배치가 옳게 동작했다는 뜻이 아닙니다.
     *
     *   수고하셨습니다.
     *   ─────────────────────────────────────────────────────────────
     */
}
