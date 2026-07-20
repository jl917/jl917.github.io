package com.example.batch.step14;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;

/**
 * Step 14 — 연습문제 (6문제)
 *
 * 정답은 Solution.java. 먼저 직접 풀어 보십시오.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 이 스텝의 문제는 셸에서 하는 작업의 비중이 큽니다.
 *    bootRun 이 아니라 bootJar + java -jar 로 실습하십시오.
 *
 *      ./gradlew clean bootJar
 *
 * 사전 준비:
 *   - application.yml 의 spring.batch.job.enabled 를 false 로
 *   - Practice.SETUP_DDL 로 s14_bad_order, batch_job_lock 테이블 생성
 *   - Practice.ZombieCleaner 의 @PostConstruct 는 주석 처리된 상태 유지
 *     (문제 2 에서 좀비를 만들어야 하므로)
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Exercise {

    // =====================================================================
    // 문제 1. 종료 코드 확인하기
    //
    // ★ 코드를 거의 안 쓰는 문제입니다. 그런데 배포 전 체크리스트에서
    //    가장 자주 빠지는 항목입니다.
    //
    // (a) dailySettlementJob 을 date=2025-03-01 로 실행하고
    //     종료 코드를 확인하십시오.
    //
    //       java -jar build/libs/spring-batch5-lab-1.0.0.jar \
    //         --spring.batch.job.enabled=true \
    //         --spring.batch.job.name=dailySettlementJob date=2025-03-01
    //       echo $?
    //
    //     종료 코드: ____
    //
    // (b) 같은 명령을 한 번 더 실행하십시오 (JobInstanceAlreadyComplete).
    //     종료 코드: ____
    //
    // (c) BatchLabApplication.main 에서 SpringApplication.exit(...) 감싸기를
    //     제거하고 (b) 를 다시 하십시오.
    //     종료 코드: ____
    //
    // (d) ⚠️ (c) 의 결과가 이 문제의 핵심입니다.
    //     배치가 실패했는데 종료 코드가 무엇입니까?
    //     이것이 왜 위험합니까? 로그를 봐도 발견되지 않는 이유는?
    //
    // (e) 실습이 끝나면 main 을 원래대로 되돌리십시오.
    // =====================================================================

    // (a)(b)(c) 종료 코드
    // 여기에 작성:
    //

    // (d) 왜 위험한가
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 2. 좀비 실행 만들고, 2차 방어가 무력화되는 것 재현하기
    //
    // ★ 이 스텝에서 가장 실전적인 문제입니다.
    //
    // (a) 오래 도는 배치를 실행하십시오. 하루치(389건)는 0.4초라 너무
    //     짧으니, 전체 70,000건을 도는 Job 을 쓰거나 Processor 에
    //     Thread.sleep 을 넣으십시오.
    //
    // (b) 실행 중에 다른 터미널에서 프로세스를 강제 종료하십시오.
    //
    //       ps aux | grep spring-batch5-lab
    //       kill -9 <PID>
    //
    // (c) 메타데이터를 확인하십시오. 무엇이 남아 있습니까?
    //
    //       SELECT JOB_EXECUTION_ID, STATUS, START_TIME, END_TIME
    //       FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 3;
    //
    //     STATUS:   ____
    //     END_TIME: ____
    //
    // (d) 이제 SettlementScheduler.runNow() 를 호출하십시오.
    //     무슨 일이 벌어집니까? 로그에 무엇이 찍힙니까?
    //
    // (e) ⚠️ 핵심 질문: 이 상태를 방치하면 정산 배치는 언제 다시 돕니까?
    //     중복을 막으려던 장치가 무엇을 막고 있습니까?
    //
    // (f) ZombieCleaner 를 완성하고, @PostConstruct 를 활성화한 뒤
    //     재기동해 좀비가 정리되는 것을 확인하십시오.
    //
    // (g) ⚠️ 마지막 질문: ZOMBIE_THRESHOLD_HOURS 를 얼마로 잡아야 합니까?
    //     너무 짧게 잡으면 어떤 사고가 납니까?
    // =====================================================================

    // (c) 남아 있는 것
    // 여기에 작성:
    //

    // (d) runNow() 호출 시
    // 여기에 작성:
    //

    // (e) 정산 배치는 언제 다시 도는가
    // 여기에 작성:
    //

    // (g) 임계 시간을 얼마로, 너무 짧으면?
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 3. DB 락 기반 3차 방어 구현하고 동시 실행으로 검증하기
    //
    // (a) Practice.JobLockService 를 참고해 락 획득/해제를 구현하고,
    //     Job 실행 전후에 붙이십시오.
    //
    // (b) ⚠️ 두 프로세스를 **동시에** 띄워야 합니다.
    //     순차로 실행하면 락이 이미 해제되어 있어 경쟁 상태가
    //     재현되지 않습니다.
    //
    //       java -jar app.jar ... date=2025-03-02 &
    //       java -jar app.jar ... date=2025-03-02 &
    //       wait
    //
    //     또는 터미널 두 개를 준비해 동시에 엔터를 치십시오.
    //
    // (c) 두 프로세스의 로그를 비교하십시오. 어느 쪽이 락을 얻었습니까?
    //     진 쪽은 어떻게 종료됐습니까?
    //
    // (d) ⚠️ 까다로운 질문: 락 획득에 실패한 프로세스의 종료 코드를
    //     무엇으로 해야 합니까?
    //       - 1(실패)로 하면 무슨 문제가 생깁니까?
    //       - 0(성공)으로 하면 무슨 문제가 생깁니까?
    //     여러분의 답과 근거를 적으십시오.
    //
    // (e) 락 해제가 실패하면(프로세스가 죽으면) 어떻게 됩니까?
    //     이 위험을 어떻게 줄일 수 있습니까?
    // =====================================================================

    // (c) 로그 비교
    // 여기에 작성:
    //

    // (d) 락 실패 시 종료 코드
    // 여기에 작성:
    //

    // (e) 락 해제 실패 대비
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 4. "최근 7일간 재시도가 있었던 날" 찾는 쿼리
    //
    // (a) 하루에 dailySettlementJob 이 2회 이상 실행된 날을 찾으십시오.
    //
    // (b) 거기서 한 걸음 더 나아가십시오. 단순히 2회 이상이 아니라,
    //     **첫 실행이 실패하고 나중 실행이 성공한 날**을 찾으십시오.
    //     이것이 "조용히 넘어간 날"입니다.
    //
    // (c) ⚠️ 왜 이 쿼리가 중요합니까?
    //     재시도해서 결국 성공했다면 알림이 안 갔을 수 있습니다.
    //     그런 날이 반복되면 무엇을 의미합니까?
    // =====================================================================

    public static final String PROBLEM4_QUERY = """
            -- 여기에 작성:
            --
            """;

    // (c) 왜 중요한가
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 5. "배치가 아예 돌지 않았음"을 감지하는 알림 규칙
    //
    // ★ 정답이 하나가 아닙니다. 판단을 정당화하는 것이 핵심입니다.
    //
    // (a) dailySettlementJob 은 매일 새벽 2시에 돕니다.
    //     "돌아야 하는데 안 돌았다"를 감지하는 PromQL 을 작성하십시오.
    //     사용할 지표: spring_batch_job_seconds_count{name, status}
    //
    // (b) 시간 창(threshold)을 몇 시간으로 잡겠습니까?
    //     24시간? 26시간? 30시간? 그 근거를 대십시오.
    //
    // (c) ⚠️ 트레이드오프:
    //       - 너무 짧게 잡으면 무슨 문제가 생깁니까?
    //       - 너무 길게 잡으면 무슨 문제가 생깁니까?
    //
    // (d) absent() 를 쓰는 대안도 있습니다. 두 방식을 비교하십시오.
    //     어떤 상황에서 absent() 가 더 낫습니까?
    //
    // (e) ⚠️ 마지막 질문: "실패 알림"과 "안 돌았음 알림" 중
    //     어느 쪽이 더 중요합니까? 왜입니까?
    // =====================================================================

    public static final String PROBLEM5_ALERT_RULE = """
            groups:
              - name: batch
                rules:
                  # 여기에 작성:
                  #
            """;

    // (b) 시간 창과 근거
    // 여기에 작성:
    //

    // (c) 트레이드오프
    // 여기에 작성:
    //

    // (e) 어느 알림이 더 중요한가
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 6. 종합 실습에 검증 Step 추가하기
    //
    // ★ 이 코스의 마지막 문제입니다.
    //
    // 요구사항: 정산이 끝난 뒤, 정산 결과가 올바른지 검증하는 Step 을
    //           추가하십시오.
    //
    //   (1) orders 의 해당 날짜 COMPLETED 건수와
    //       settlement 의 해당 날짜 정산 건수를 비교합니다.
    //   (2) 두 숫자가 다르면 Job 을 실패시킵니다.
    //       단, skip 된 건수는 정상적인 차이로 인정합니다.
    //   (3) 검증 실패 시 어떤 주문이 누락됐는지 로그에 남깁니다.
    //
    // (a) 검증 Step 을 구현하십시오.
    //
    // (b) ⚠️ 함정 1 (Step 01): 검증 실패를 알리려고
    //     contribution.setExitStatus(ExitStatus.FAILED) 를 하면
    //     어떻게 됩니까? Job 이 실패합니까?
    //
    // (c) ⚠️ 함정 2 (Step 10): 검증 Step 을 흐름에 붙일 때
    //     .on("FAILED").to(...).end() 로 처리하면 어떻게 됩니까?
    //     실패가 제대로 전파됩니까?
    //
    // (d) 위 두 함정을 피해 Job 흐름을 완성하십시오.
    //
    // (e) 일부러 settlement 에서 몇 행을 지우고 검증이 실패하는지
    //     확인하십시오.
    //
    //       DELETE FROM settlement WHERE settle_date='2025-03-01' LIMIT 5;
    //
    //     그리고 java -jar ... 의 종료 코드가 1인지 확인하십시오.
    // =====================================================================

    public static Step problem6VerifyStep(JobRepository jobRepository,
                                          PlatformTransactionManager txManager,
                                          DataSource dataSource,
                                          String date) {
        return new StepBuilder("verifyStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    // 여기에 작성:
                    //
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    // (b) setExitStatus(FAILED) 로 하면?
    // 여기에 작성:
    //

    // (c) .on("FAILED").to(...).end() 로 하면?
    // 여기에 작성:
    //

    // (d) 올바른 Job 흐름
    // 여기에 작성:
    //

    // -- 검증:
    // SELECT (SELECT COUNT(*) FROM orders
    //          WHERE status='COMPLETED' AND DATE(ordered_at)='2025-03-01') AS 대상,
    //        (SELECT COUNT(*) FROM settlement
    //          WHERE settle_date='2025-03-01')                             AS 정산;
}
