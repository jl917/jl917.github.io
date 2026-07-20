package com.example.batch.step01;

/*
 * ============================================================================
 * Step 01 — 연습문제 정답 및 해설
 * ============================================================================
 * Exercise.java 를 먼저 풀어 본 뒤에 보세요.
 * ============================================================================
 */

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.atomic.AtomicInteger;

public final class Solution {

    private Solution() {
    }

    // =========================================================================
    // 정답 1. Step 두 개를 순서대로
    // =========================================================================
    //
    // 핵심은 .start(첫Step).next(다음Step) 입니다.
    //
    // 왜 Step 을 @Bean 파라미터로 주입받는가?
    //   ex1Job(...) 안에서 ex1StepA(jobRepository, txManager) 를 "직접 호출"해도
    //   컴파일은 됩니다. @Configuration 클래스는 CGLIB 프록시라 대개 같은 빈이 돌아오지만,
    //   프록시가 걸리지 않는 상황(@Configuration(proxyBeanMethods = false), 정적 중첩 구성 등)에서는
    //   컨테이너가 모르는 Step 인스턴스가 하나 더 만들어집니다.
    //   그 Step 은 StepExecutionListener 등록도, 프록시도 안 걸린 "그림자 Step" 입니다.
    //   → 에러는 안 나고 동작만 미묘하게 달라지는, 이 코스가 경계하는 종류의 버그입니다.
    //   메서드 파라미터로 받으면 컨테이너가 만든 그 빈임이 보장됩니다.
    //
    // 확인 결과
    //   로그에 "Executing step:" 이 2번,
    //   BATCH_STEP_EXECUTION 에 행이 2개 (ex1StepA, ex1StepB).
    //   BATCH_JOB_EXECUTION 에는 여전히 1행입니다. Step 이 늘어도 JobExecution 은 하나입니다.
    //
    @Configuration
    public static class Sol1Config {

        @Bean
        public Job ex1Job(JobRepository jobRepository, Step ex1StepA, Step ex1StepB) {
            return new JobBuilder("ex1Job", jobRepository)
                    .start(ex1StepA)
                    .next(ex1StepB)
                    .build();
        }

        @Bean
        public Step ex1StepA(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("ex1StepA", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> ex1StepA");
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }

        @Bean
        public Step ex1StepB(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("ex1StepB", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println(">>> ex1StepB");
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // 정답 2. CONTINUABLE 3번 → 호출 4회, COMMIT_COUNT = 4
    // =========================================================================
    //
    // ★ 이 문제의 답이 4 인 것이 이 스텝에서 가장 놀라운 지점입니다.
    //
    // 흔한 오답: "Tasklet 은 한 번의 작업 단위니까 COMMIT_COUNT = 1"
    //
    // 실제 동작:
    //   TaskletStep 은 내부에 RepeatTemplate 를 두고 다음을 반복합니다.
    //
    //     while (true) {
    //         트랜잭션 시작
    //           RepeatStatus rs = tasklet.execute(contribution, chunkContext);
    //           JobRepository.update(stepExecution)      ← 메타데이터도 같은 트랜잭션
    //         커밋 (commitCount++)
    //         if (rs == FINISHED) break;
    //     }
    //
    //   즉 "Tasklet 호출 1회 = 트랜잭션 1개 = 커밋 1회" 입니다.
    //   CONTINUABLE 을 3번 반환하면 호출은 1,2,3(CONTINUABLE) + 4(FINISHED) = 4회이고,
    //   커밋도 4회입니다.
    //
    // 왜 이게 중요한가:
    //   (1) CONTINUABLE 을 쓰는 Tasklet 은 "전체가 하나의 트랜잭션"이 아닙니다.
    //       3번째 호출에서 예외가 나도 1,2번째가 한 일은 이미 커밋되어 되돌아가지 않습니다.
    //       "루프 도는 Tasklet 을 만들었는데 실패했더니 절반만 반영됐다"의 원인이 이것입니다.
    //   (2) 반대로 이 성질 덕에 대용량 삭제 같은 작업을 CONTINUABLE 로 잘라
    //       긴 트랜잭션과 락 점유를 피할 수 있습니다. (Step 04 에서 다룹니다)
    //
    // ⚠️ 아래 AtomicInteger 는 "빈 하나 = 상태 하나"라서 학습용으로만 안전합니다.
    //    Step 빈은 싱글턴이라 이 카운터는 JVM 이 살아 있는 동안 유지됩니다.
    //    같은 프로세스에서 Job 을 두 번 돌리면 카운터가 이어져 결과가 달라집니다.
    //    운영 코드에서 Tasklet 에 상태를 두면 안 되는 이유이고, Step 02 에서 다시 다룹니다.
    //    올바른 자리는 StepExecutionContext 입니다 (Step 09).
    //
    @Configuration
    public static class Sol2Config {

        @Bean
        public Job ex2Job(JobRepository jobRepository, Step ex2Step) {
            return new JobBuilder("ex2Job", jobRepository)
                    .start(ex2Step)
                    .build();
        }

        @Bean
        public Step ex2Step(JobRepository jobRepository, PlatformTransactionManager txManager) {
            AtomicInteger calls = new AtomicInteger(0);
            return new StepBuilder("ex2Step", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        int n = calls.incrementAndGet();
                        System.out.println(">>> tasklet call #" + n);
                        return (n < 4) ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // 정답 3. ExitStatus 만 SKIPPED
    // =========================================================================
    //
    // 정답 코드는 contribution.setExitStatus(new ExitStatus("SKIPPED", "...")) 한 줄입니다.
    // 중요한 것은 "왜 contribution 인가" 입니다.
    //
    //   contribution.setExitStatus(...)      ← StepContribution 에 담아 둔다.
    //                                          청크(=여기서는 Tasklet 호출) 트랜잭션이
    //                                          커밋될 때 StepExecution 에 반영된다.
    //                                          롤백되면 반영되지 않는다. ✔ 정석
    //
    //   stepExecution.setExitStatus(...)     ← StepExecution 에 즉시 쓴다.
    //                                          트랜잭션이 롤백돼도 이 값은 남는다.
    //                                          "실패한 청크가 남긴 성공 신호"가 되어
    //                                          Step 10 의 Flow 분기를 조용히 오작동시킨다.
    //
    // 즉 둘 다 "동작은" 합니다. 차이는 실패했을 때만 드러납니다.
    // 이것이 이 코스가 말하는 "에러 없이 조용히 틀리는 코드"의 전형입니다.
    //
    // 또 하나: BatchStatus 에는 SKIPPED 라는 값이 없습니다.
    //   COMPLETED / STARTING / STARTED / STOPPING / STOPPED / FAILED / ABANDONED / UNKNOWN
    //   8개가 전부이고 enum 이라 늘릴 수 없습니다.
    //   "업무적으로 의미 있는 종료 사유"는 전부 ExitStatus 쪽에 실어야 합니다.
    //
    @Configuration
    public static class Sol3Config {

        @Bean
        public Job ex3Job(JobRepository jobRepository, Step ex3Step) {
            return new JobBuilder("ex3Job", jobRepository)
                    .start(ex3Step)
                    .build();
        }

        @Bean
        public Step ex3Step(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("ex3Step", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        contribution.setExitStatus(
                                new ExitStatus("SKIPPED", "휴일이라 정산을 건너뜁니다"));
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    // =========================================================================
    // 정답 4. EXIT_MESSAGE 에서 예외 클래스명 추출
    // =========================================================================
    //
    // EXIT_MESSAGE 의 첫 줄은
    //   java.lang.IllegalStateException: 정산 원장이 잠겨 있습니다
    // 이고 그 뒤로 "\n\tat ..." 스택 프레임이 이어집니다.
    //
    //   SUBSTRING_INDEX(EXIT_MESSAGE, '\n', 1)  → 첫 줄만
    //   SUBSTRING_INDEX(첫줄, ':', 1)           → 콜론 앞 = FQCN
    //   SUBSTRING_INDEX(첫줄, ': ', -1)         → 콜론 뒤 = 메시지
    //
    // ⚠️ 한계 두 가지를 반드시 알고 쓰세요.
    //   (1) EXIT_MESSAGE 는 VARCHAR(2500) 입니다. 깊은 스택트레이스는 잘립니다.
    //       "Caused by" 가 잘려 나가면 진짜 원인을 여기서는 절대 볼 수 없습니다.
    //   (2) 성공한 실행은 EXIT_MESSAGE 가 빈 문자열이라 WHERE 로 걸러야 합니다.
    //
    // 결론: 메타데이터는 "무엇이 몇 시에 어떤 상태로 끝났나"를 보는 곳이고,
    //       "왜 그랬나"는 애플리케이션 로그에서 봐야 합니다. 대체재가 아닙니다.
    //
    public static final String EX4_SQL = """
            SELECT
              e.JOB_EXECUTION_ID,
              SUBSTRING_INDEX(SUBSTRING_INDEX(e.EXIT_MESSAGE, '\\n', 1), ':', 1)  AS exception_class,
              SUBSTRING_INDEX(SUBSTRING_INDEX(e.EXIT_MESSAGE, '\\n', 1), ': ', -1) AS exception_message,
              i.JOB_NAME,
              e.START_TIME
            FROM BATCH_JOB_EXECUTION e
            JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
            WHERE e.STATUS = 'FAILED'
              AND e.EXIT_MESSAGE <> ''
            ORDER BY e.JOB_EXECUTION_ID DESC;
            """;
    //
    // 결과 예시
    // +------------------+---------------------------------+----------------------------+----------+
    // | JOB_EXECUTION_ID | exception_class                 | exception_message          | JOB_NAME |
    // +------------------+---------------------------------+----------------------------+----------+
    // |                4 | java.lang.IllegalStateException | 정산 원장이 잠겨 있습니다  | failJob  |
    // +------------------+---------------------------------+----------------------------+----------+

    // =========================================================================
    // 정답 5. 최근 24시간 내 3초 이상 걸린 Job
    // =========================================================================
    //
    // 두 개의 함정이 있었습니다.
    //
    // (1) END_TIME IS NOT NULL 을 반드시 붙일 것
    //     kill -9 나 OOM 으로 죽은 실행은 STATUS='STARTED', END_TIME=NULL 로 남습니다(본문 1-7).
    //     이 조건이 없으면 TIMESTAMPDIFF 결과가 NULL 이 되어 비교가 UNKNOWN 이 되고,
    //     그 행은 조용히 결과에서 빠집니다. "안 나온다"는 사실조차 눈치채기 어렵습니다.
    //     차라리 좀비는 별도 쿼리로 따로 감시하는 것이 낫습니다.
    //
    // (2) 초 단위로 빼면 안 됩니다
    //     TIMESTAMPDIFF(SECOND, ...) 는 3.9초를 3 으로 내립니다.
    //     "3초 이상"을 SECOND >= 3 으로 쓰면 2.9초도 2 라서 빠지고 경계가 흐려집니다.
    //     MySQL 의 DATETIME(6) 을 살려 MICROSECOND 로 재고 1000 으로 나누는 것이 정확합니다.
    //
    // (3) 보너스 — START_TIME 기준으로 24시간을 자르되, 인덱스가 없다는 점도 기억하세요.
    //     BATCH_JOB_EXECUTION 에는 START_TIME 인덱스가 기본으로 없습니다.
    //     이력이 수십만 건 쌓이면 이 쿼리가 풀스캔입니다. 운영에서는
    //     주기적으로 오래된 메타데이터를 아카이빙하거나 인덱스를 추가합니다(Step 14).
    //
    public static final String EX5_SQL = """
            SELECT
              i.JOB_NAME,
              e.JOB_EXECUTION_ID,
              TIMESTAMPDIFF(MICROSECOND, e.START_TIME, e.END_TIME) / 1000 AS elapsed_ms,
              e.STATUS,
              e.EXIT_CODE,
              e.START_TIME
            FROM BATCH_JOB_EXECUTION e
            JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
            WHERE e.START_TIME >= NOW() - INTERVAL 24 HOUR
              AND e.END_TIME IS NOT NULL
              AND TIMESTAMPDIFF(MICROSECOND, e.START_TIME, e.END_TIME) >= 3000000
            ORDER BY elapsed_ms DESC;

            -- 좀비는 따로 봅니다 (위 쿼리에서 조용히 빠지므로)
            SELECT i.JOB_NAME, e.JOB_EXECUTION_ID, e.START_TIME, e.STATUS
            FROM BATCH_JOB_EXECUTION e
            JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
            WHERE e.END_TIME IS NULL
              AND e.STATUS IN ('STARTED', 'STARTING');
            """;

    // =========================================================================
    // 정답 6. 타입 표기는 JobInstance 를 가릅니다
    // =========================================================================
    //
    // (a) (2)는 스킵되지 않고 "새로 실행"됩니다.
    // (b) JobInstance 가 2개 생깁니다.
    // (c) 두 JOB_KEY 는 다릅니다.
    // (d) JOB_KEY 는 identifying 파라미터의 (이름, 값, 타입) 을 정렬해 이어 붙인 문자열의
    //     MD5 이기 때문입니다. 값 "2025-03-01" 이 같아도 타입이
    //     java.lang.String 과 (타입 미지정 시 추론되는) java.lang.String 으로
    //     같아 보일 수 있지만, 커맨드라인 파서는 (string) 명시 여부에 따라
    //     서로 다른 JobParameter 표현을 만들고 그 차이가 해시에 반영됩니다.
    //
    // (e) 사고 시나리오 — 이것이 이 문제의 진짜 목적입니다.
    //
    //     운영 스케줄러가 매일
    //         --spring.batch.job.name=settlementJob settleDate=2025-03-01
    //     로 정산 배치를 돌리고 있습니다.
    //     "타입을 명시하는 게 좋대"라는 리뷰 코멘트를 받아 누군가
    //         settleDate(string)=2025-03-01
    //     로 바꿉니다. 배포합니다.
    //
    //     그날 밤, 배치는 정상적으로 COMPLETED 로 끝납니다.
    //     로그도 깨끗합니다. 알람도 안 옵니다.
    //     그런데 3월 1일 정산이 "두 번" 실행되었습니다.
    //     새 JOB_KEY 라서 Spring Batch 는 이것을 완전히 새로운 일로 봤기 때문입니다.
    //
    //     settlement 테이블에 uk_settlement_order UNIQUE 제약이 있다면
    //     DuplicateKeyException 으로 시끄럽게 실패합니다 — 다행입니다.
    //     제약이 없었다면 정산 금액이 정확히 2배가 된 채로,
    //     아무 에러 없이, 아무도 모르게 넘어갑니다.
    //
    //     교훈 두 가지:
    //       1. 파라미터 타입 표기는 팀 컨벤션으로 고정하고 함부로 바꾸지 않는다.
    //       2. 제약조건은 "조용히 틀리는 것"을 "시끄럽게 실패하는 것"으로 바꿔 준다.
    //          비용을 아끼겠다고 UNIQUE 를 빼지 마세요.
    //
    // 확인 SQL
    public static final String EX6_SQL = """
            SELECT i.JOB_INSTANCE_ID, i.JOB_KEY,
                   p.PARAMETER_NAME, p.PARAMETER_TYPE, p.PARAMETER_VALUE, p.IDENTIFYING
            FROM BATCH_JOB_INSTANCE i
            JOIN BATCH_JOB_EXECUTION e  ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
            JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
            WHERE i.JOB_NAME = 'helloJob' AND p.PARAMETER_NAME = 'runDate'
            ORDER BY i.JOB_INSTANCE_ID;
            """;
}
