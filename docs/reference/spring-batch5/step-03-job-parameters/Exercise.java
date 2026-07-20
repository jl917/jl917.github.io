package com.example.batch.step03;

/*
 * ============================================================================
 * Step 03 — 연습문제 (6문제)
 * ============================================================================
 *
 * 정답은 Solution.java 에 있습니다. 먼저 직접 풀어 보세요.
 *
 * 놓을 위치: src/main/java/com/example/batch/step03/Exercise.java
 * 실행:      ./gradlew bootRun -Dargs="--spring.batch.job.name=<jobName>,date=..."
 *
 * 시작 전에 메타데이터를 초기화하는 것을 권합니다:
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
 *   SET FOREIGN_KEY_CHECKS = 0;
 *   DELETE FROM BATCH_STEP_EXECUTION_CONTEXT; DELETE FROM BATCH_STEP_EXECUTION;
 *   DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;  DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
 *   DELETE FROM BATCH_JOB_EXECUTION;          DELETE FROM BATCH_JOB_INSTANCE;
 *   SET FOREIGN_KEY_CHECKS = 1;
 *   SQL
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

public class Exercise {

    // ========================================================================
    // 문제 1. 사라진 파라미터
    // ------------------------------------------------------------------------
    // 아래 명령으로 paramJob 을 실행했더니 로그가 이렇게 나왔습니다.
    //
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,--date=2025-03-01"
    //
    //   ... TaskExecutorJobLauncher : Job: [SimpleJob: [name=paramJob]] launched with the
    //       following parameters: [{}]
    //   [paramStep] date=null, COMPLETED orders=0
    //   ... and the following status: [COMPLETED] in 31ms
    //
    // (a) 왜 파라미터가 [{}] 인가?
    // (b) 이 상황이 "에러가 나는 것보다 위험한" 이유는?
    // (c) 올바른 명령은?
    //
    // 답을 아래 문자열에 적고, 실제로 실행해 검증하세요.
    // ========================================================================
    static final String ANSWER_1_A = "여기에 작성: ";
    static final String ANSWER_1_B = "여기에 작성: ";
    static final String ANSWER_1_C = "여기에 작성: ";


    // ========================================================================
    // 문제 2. JobInstance 는 몇 개인가
    // ------------------------------------------------------------------------
    // 메타데이터를 완전히 비운 상태에서 paramJob 을 아래 네 번 실행합니다.
    // (실행 순서대로입니다. 3번째와 4번째는 예외가 날 수도 있습니다.)
    //
    //   ① date=2025-03-01
    //   ② date=2025-03-02
    //   ③ date=2025-03-01, chunkSize=1000(java.lang.Long, identifying=false)
    //   ④ date=2025-03-03, executedBy=jenkins-42(java.lang.String, identifying=false)
    //
    // (a) BATCH_JOB_INSTANCE 에는 몇 행이 남는가?
    // (b) BATCH_JOB_EXECUTION 에는 몇 행이 남는가?
    // (c) 예외가 나는 실행은 몇 번째이며, 예외 이름은?
    //
    // 답을 적은 뒤 반드시 SELECT COUNT(*) 로 검증하세요.
    // ========================================================================
    static final String ANSWER_2_A = "여기에 작성: ";
    static final String ANSWER_2_B = "여기에 작성: ";
    static final String ANSWER_2_C = "여기에 작성: ";


    // ========================================================================
    // 문제 3. 정산 배치에 써도 되는 해결책 고르기
    // ------------------------------------------------------------------------
    // 매일 도는 정산 배치(settlementJob, date 파라미터)가 어제치를 다시 돌려야 하는데
    // JobInstanceAlreadyCompleteException 이 납니다. 아래 세 선택지 중에서
    // "정산 배치에 써도 되는 것" 과 "쓰면 안 되는 것" 을 근거와 함께 고르세요.
    //
    //   (A) 파라미터를 바꾼다
    //   (B) Job 에 .incrementer(new RunIdIncrementer()) 를 붙인다
    //   (C) Step 에 .allowStartIfComplete(true) 를 붙인다
    //
    // 힌트: settlement 테이블에는 UNIQUE KEY uk_settlement_order (order_id) 가 있습니다.
    //       그 제약이 "없다면" 어떤 일이 벌어지는지도 함께 적으세요.
    // ========================================================================
    static final String ANSWER_3 = "여기에 작성: ";


    // ========================================================================
    // 문제 4. Incrementer 와 Validator 의 충돌
    // ------------------------------------------------------------------------
    // 아래 Job 을 실행하면 예외가 납니다.
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=ex4Job,date=2025-03-10"
    //
    // (a) 실행하기 "전에" 어떤 예외의 어떤 메시지가 날지 예측해 ANSWER_4_A 에 적으세요.
    // (b) 실행해서 확인한 뒤, 아래 ex4Validator 를 최소한으로 고쳐 통과시키세요.
    // ========================================================================
    static final String ANSWER_4_A = "여기에 작성: ";

    @Configuration
    public static class Ex4Config {

        @Bean
        public JobParametersValidator ex4Validator() {
            DefaultJobParametersValidator validator = new DefaultJobParametersValidator(
                    new String[]{"date"},          // requiredKeys
                    new String[]{"chunkSize"}      // optionalKeys — 여기에 문제가 있습니다
            );
            // 여기에 작성: optionalKeys 를 고치세요.
            validator.afterPropertiesSet();
            return validator;
        }

        @Bean
        public Job ex4Job(JobRepository jobRepository, Step paramStep,
                          JobParametersValidator ex4Validator) {
            return new JobBuilder("ex4Job", jobRepository)
                    .incrementer(new RunIdIncrementer())
                    .validator(ex4Validator)
                    .start(paramStep)
                    .build();
        }
    }


    // ========================================================================
    // 문제 5. 커스텀 Validator 작성
    // ------------------------------------------------------------------------
    // 다음을 모두 만족하는 JobParametersValidator 를 완성하세요.
    //
    //   - date : 필수. yyyy-MM-dd 형식. 2025-01-01 ~ 2025-06-29 범위 안.
    //   - dryRun : 선택. 있으면 문자열 "true" 또는 "false" 만 허용.
    //              (없어도 통과해야 합니다)
    //
    // ⚠️ 함정: Boolean.parseBoolean("yes") 는 예외 없이 false 를 돌려줍니다.
    //          "dryRun=yes 로 켰다고 믿었는데 실제로는 데이터가 쓰이는" 사고를 막아야 합니다.
    //
    // 검증 명령 (전부 예외가 나야 합니다):
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=ex5Job"
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=ex5Job,date=20250301"
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=ex5Job,date=2024-12-31"
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=ex5Job,date=2025-03-01,dryRun=yes"
    // 그리고 이건 통과해야 합니다:
    //   ./gradlew bootRun -Dargs="--spring.batch.job.name=ex5Job,date=2025-03-11,dryRun=true"
    // ========================================================================
    public static class Ex5Validator implements JobParametersValidator {

        private static final LocalDate MIN = LocalDate.of(2025, 1, 1);
        private static final LocalDate MAX = LocalDate.of(2025, 6, 29);

        @Override
        public void validate(JobParameters parameters) throws JobParametersInvalidException {
            // 여기에 작성: date 필수 / 형식 / 범위 검증

            // 여기에 작성: dryRun 선택 / "true" 또는 "false" 만 허용
        }
    }

    @Configuration
    public static class Ex5Config {
        @Bean
        public Job ex5Job(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("ex5Job", jobRepository)
                    .validator(new Ex5Validator())
                    .start(paramStep)
                    .build();
        }
    }


    // ========================================================================
    // 문제 6. 경계가 있는 Incrementer
    // ------------------------------------------------------------------------
    // 2025-03-01 부터 하루씩 전진하되, 2025-06-29 를 넘어가려 하면
    // IllegalStateException("정산 가능한 마지막 날짜(2025-06-29)를 넘었습니다") 를 던지는
    // Incrementer 를 완성하세요.
    //
    // ⚠️ 경계값 주의: date=2025-06-29 로 실행되는 것까지는 "성공" 이어야 합니다.
    //                그다음 호출에서 예외입니다. off-by-one 을 조심하세요.
    // ========================================================================
    public static class Ex6Incrementer implements JobParametersIncrementer {

        private static final LocalDate START = LocalDate.of(2025, 3, 1);
        private static final LocalDate MAX = LocalDate.of(2025, 6, 29);

        @Override
        public JobParameters getNext(JobParameters parameters) {
            // 여기에 작성: 최초 실행이면 START, 아니면 이전 date + 1일.
            //             MAX 를 넘으면 IllegalStateException.
            return new JobParametersBuilder().toJobParameters();
        }
    }

    @Configuration
    public static class Ex6Config {
        @Bean
        public Job ex6Job(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("ex6Job", jobRepository)
                    .incrementer(new Ex6Incrementer())
                    .start(paramStep)
                    .build();
        }
    }
}
