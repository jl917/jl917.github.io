package com.example.batch.step01;

/*
 * ============================================================================
 * Step 01 — 연습문제 (6문제)
 * ============================================================================
 *
 * 규칙
 *   - "// 여기에 작성:" 아래를 채웁니다.
 *   - 각 문제를 풀고 나면 반드시 실행해서 BATCH_* 테이블을 직접 SELECT 하세요.
 *     이 스텝의 목적은 "코드를 쓰는 것"이 아니라 "메타데이터를 읽는 것"입니다.
 *   - 정답은 Solution.java. 먼저 풀어 본 뒤에 여세요.
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.batch.job.name=<jobName>'
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

public final class Exercise {

    private Exercise() {
    }

    // =========================================================================
    // 문제 1. Step 두 개를 순서대로 실행하는 Job
    // -------------------------------------------------------------------------
    // 요구사항
    //   - Job 이름: ex1Job
    //   - Step 이름: ex1StepA, ex1StepB  (이 순서로 실행)
    //   - 각 Step 은 자기 이름을 System.out 으로 출력하고 끝난다
    //
    // 실행 후 확인할 것
    //   (a) 로그에 "Executing step:" 이 몇 번 나오는가?
    //   (b) SELECT STEP_NAME, STATUS FROM BATCH_STEP_EXECUTION
    //       WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION);
    //       → 행이 몇 개인가?
    // =========================================================================
    @Configuration
    public static class Ex1Config {

        @Bean
        public Job ex1Job(JobRepository jobRepository, Step ex1StepA, Step ex1StepB) {
            // 여기에 작성: JobBuilder 로 ex1StepA → ex1StepB 순서의 Job 을 만드세요
            return null;
        }

        @Bean
        public Step ex1StepA(JobRepository jobRepository, PlatformTransactionManager txManager) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex1StepB(JobRepository jobRepository, PlatformTransactionManager txManager) {
            // 여기에 작성:
            return null;
        }
    }

    // =========================================================================
    // 문제 2. RepeatStatus.CONTINUABLE 과 COMMIT_COUNT
    // -------------------------------------------------------------------------
    // 요구사항
    //   - Job 이름: ex2Job / Step 이름: ex2Step
    //   - Tasklet 이 CONTINUABLE 을 3번 반환한 뒤 4번째 호출에서 FINISHED 를 반환한다.
    //     (호출 횟수는 StepExecutionContext 가 아니라 필드/AtomicInteger 로 세도 됩니다)
    //   - 매 호출마다 몇 번째 호출인지 출력한다.
    //
    // ★ 실행하기 전에 예측을 적으세요. 이게 이 문제의 핵심입니다.
    //
    //   내 예측: Tasklet 호출 횟수 = ____ 회,  COMMIT_COUNT = ____
    //
    // 실행 후 확인
    //   SELECT STEP_NAME, COMMIT_COUNT, READ_COUNT, WRITE_COUNT
    //   FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = 'ex2Step';
    //
    //   실제 값: 호출 ____ 회, COMMIT_COUNT = ____
    //   예측과 다르다면, 왜 다른지 한 줄로 적으세요:
    //   →
    // =========================================================================
    @Configuration
    public static class Ex2Config {

        @Bean
        public Job ex2Job(JobRepository jobRepository, Step ex2Step) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex2Step(JobRepository jobRepository, PlatformTransactionManager txManager) {
            // 여기에 작성:
            return null;
        }
    }

    // =========================================================================
    // 문제 3. BatchStatus 는 COMPLETED, ExitStatus 만 SKIPPED
    // -------------------------------------------------------------------------
    // 요구사항
    //   - Job 이름: ex3Job / Step 이름: ex3Step
    //   - Step 이 정상 종료하되, ExitStatus 의 exitCode 는 "SKIPPED",
    //     exitDescription 은 "휴일이라 정산을 건너뜁니다" 로 만든다.
    //
    // 실행 후 확인 — 아래 결과가 나와야 정답입니다.
    //   SELECT STATUS, EXIT_CODE FROM BATCH_STEP_EXECUTION WHERE STEP_NAME='ex3Step';
    //   → STATUS = COMPLETED,  EXIT_CODE = SKIPPED
    //
    //   STATUS 까지 SKIPPED 로 바뀌었다면 잘못 푼 것입니다.
    //   (BatchStatus 는 enum 이라 SKIPPED 라는 값 자체가 없습니다)
    // =========================================================================
    @Configuration
    public static class Ex3Config {

        @Bean
        public Job ex3Job(JobRepository jobRepository, Step ex3Step) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex3Step(JobRepository jobRepository, PlatformTransactionManager txManager) {
            // 여기에 작성:
            return null;
        }
    }

    // =========================================================================
    // 문제 4. EXIT_MESSAGE 에서 예외 클래스명만 추출하는 SQL
    // -------------------------------------------------------------------------
    // 배경
    //   실패한 JobExecution 의 EXIT_MESSAGE 에는 스택트레이스가 통째로 들어갑니다.
    //   첫 줄은 "java.lang.IllegalStateException: 정산 원장이 잠겨 있습니다" 형태입니다.
    //
    // 요구사항
    //   STATUS = 'FAILED' 인 JobExecution 에 대해
    //     JOB_EXECUTION_ID | 예외 FQCN | 예외 메시지 첫 줄
    //   을 뽑는 SQL 을 작성하세요. (Practice 의 failJob 을 한 번 돌려 두고 실행)
    //
    //   힌트: SUBSTRING_INDEX 를 두 번 씁니다.
    // =========================================================================
    public static final String EX4_SQL = """
            -- 여기에 작성:
            """;

    // =========================================================================
    // 문제 5. 최근 24시간 내 3초 이상 걸린 Job 찾기
    // -------------------------------------------------------------------------
    // 요구사항
    //   BATCH_JOB_INSTANCE 와 BATCH_JOB_EXECUTION 을 조인해
    //     JOB_NAME | JOB_EXECUTION_ID | 소요 밀리초 | STATUS
    //   를 소요시간 내림차순으로 뽑되,
    //     (a) 최근 24시간 안에 시작한 것만
    //     (b) 3초(3000ms) 이상 걸린 것만
    //   으로 제한하세요.
    //
    //   ⚠️ 반드시 고려할 것 2가지가 있습니다. 무엇인지 생각하고 SQL 에 반영하세요.
    //      - 아직 안 끝난(혹은 죽어서 안 끝난 것으로 남은) 실행을 어떻게 처리할 것인가
    //      - "3초 이상"을 초 단위로 빼면 무슨 문제가 생기는가
    // =========================================================================
    public static final String EX5_SQL = """
            -- 여기에 작성:
            """;

    // =========================================================================
    // 문제 6. 타입 표기가 JobInstance 를 가르는가
    // -------------------------------------------------------------------------
    // 절차
    //   (1) ./gradlew bootRun --args='--spring.batch.job.name=helloJob runDate(string)=2025-03-01'
    //   (2) ./gradlew bootRun --args='--spring.batch.job.name=helloJob runDate=2025-03-01'
    //
    //   (2) 는 "already completed" 로 스킵될까요, 새로 실행될까요?
    //
    // 확인
    //   SELECT JOB_INSTANCE_ID, JOB_NAME, JOB_KEY FROM BATCH_JOB_INSTANCE
    //   WHERE JOB_NAME = 'helloJob';
    //
    //   SELECT JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE
    //   FROM BATCH_JOB_EXECUTION_PARAMS WHERE PARAMETER_NAME = 'runDate';
    //
    // 답을 여기에 적으세요.
    //   (a) (2)는 스킵되었나 실행되었나?                        →
    //   (b) JobInstance 가 몇 개 생겼나?                        →
    //   (c) 두 JOB_KEY 는 같은가 다른가?                        →
    //   (d) 그 이유를 한 문장으로:                              →
    //   (e) 이 현상이 정산 배치에서 만들 수 있는 사고는?        →
    // =========================================================================
}
