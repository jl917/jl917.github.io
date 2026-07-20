package com.example.batch.step03;

/*
 * ============================================================================
 * Step 03 — 연습문제 정답 및 해설
 * ============================================================================
 * 문제를 직접 풀어 본 뒤에 여세요.
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
import java.time.format.DateTimeParseException;

public class Solution {

    // ========================================================================
    // 정답 1. 사라진 파라미터
    // ========================================================================
    /*
     * (a) 왜 [{}] 인가
     *
     *     `--date=2025-03-01` 의 앞에 붙은 `--` 때문입니다.
     *     Spring Boot 의 커맨드라인 인자 규약에서 `--key=value` 는 "애플리케이션 프로퍼티"입니다.
     *     Environment 에 `date=2025-03-01` 이라는 프로퍼티로 들어갈 뿐,
     *     DefaultJobParametersConverter 는 `--` 로 시작하지 않는 인자만 JobParameter 로 변환합니다.
     *     그래서 JobParameters 는 빈 상태이고 로그가 [{}] 입니다.
     *
     * (b) 왜 에러보다 위험한가  ★이 스텝에서 가장 중요한 한 문단★
     *
     *     Job 이 "성공" 하기 때문입니다.
     *       - params.getString("date") → null
     *       - SQL 이 DATE(ordered_at) = NULL 로 평가됨 → NULL 비교는 항상 UNKNOWN → 0건
     *       - Tasklet 은 예외 없이 RepeatStatus.FINISHED 반환
     *       - JobExecution.STATUS = COMPLETED, EXIT_CODE = COMPLETED
     *
     *     모니터링은 초록불이고, 알림은 울리지 않고, 스케줄러는 성공으로 기록합니다.
     *     그런데 3월 1일 정산 389건은 하나도 만들어지지 않았습니다.
     *     문제는 며칠 뒤 "3월 정산 금액이 왜 이렇게 적지?" 라는 질문으로 발견되고,
     *     그때는 이미 원인을 추적하기 어렵습니다.
     *
     *     문법 에러였다면 5초 만에 고쳤을 일입니다.
     *     조용히 틀리는 코드가 진짜 위험하다는 말이 정확히 이 상황을 가리킵니다.
     *
     *     방어책 두 가지:
     *       1) 실행 로그의 `launched with the following parameters:` 를 항상 눈으로 확인한다.
     *          [{}] 면 즉시 잘못된 것입니다.
     *       2) JobParametersValidator 로 date 를 required 로 선언한다. (정답 5 참고)
     *          그러면 0건 성공이 JobParametersInvalidException 실패로 바뀝니다.
     *
     * (c) 올바른 명령
     *
     *     ./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-01"
     *                                ^^ 이건 프로퍼티라 -- 필요       ^^ 이건 JobParameter 라 -- 없음
     *
     *     두 인자의 성격이 다르다는 점이 헷갈림의 근원입니다.
     *     spring.batch.job.name 은 "어떤 Job 을 고를지" 정하는 Boot 설정이고,
     *     date 는 "그 Job 에 무엇을 넘길지" 정하는 배치 파라미터입니다.
     */
    static final String ANSWER_1_A = "--- 를 붙이면 Spring 프로퍼티로 해석되어 JobParameters 에 들어가지 않는다";
    static final String ANSWER_1_B = "예외가 아니라 COMPLETED 로 끝나서, 0건 정산이 성공으로 기록된다";
    static final String ANSWER_1_C = "./gradlew bootRun -Dargs=\"--spring.batch.job.name=paramJob,date=2025-03-01\"";


    // ========================================================================
    // 정답 2. JobInstance 는 몇 개인가
    // ========================================================================
    /*
     * (a) BATCH_JOB_INSTANCE = 3행
     * (b) BATCH_JOB_EXECUTION = 3행
     * (c) 3번째 실행에서 JobInstanceAlreadyCompleteException
     *
     * 계산 근거 — JOB_KEY 는 "identifying 파라미터만" 으로 만들어집니다.
     *
     *   ① date=2025-03-01                      → identifying 집합 {date=2025-03-01} → 새 인스턴스 #1
     *   ② date=2025-03-02                      → {date=2025-03-02}                  → 새 인스턴스 #2
     *   ③ date=2025-03-01 + chunkSize(non-id)  → {date=2025-03-01}  ← ①과 동일!
     *                                          → 이미 COMPLETED → JobInstanceAlreadyCompleteException
     *                                          → JobExecution 도 생성되지 않음
     *   ④ date=2025-03-03 + executedBy(non-id) → {date=2025-03-03}                  → 새 인스턴스 #3
     *
     * 즉 ③ 은 인스턴스도 실행도 만들지 못하고 예외로 끝납니다.
     * 그래서 인스턴스 3, 실행 3 입니다.
     *
     * DefaultJobKeyGenerator 의 규칙:
     *   1) identifying = true 인 파라미터만 고른다
     *   2) 파라미터 이름으로 정렬한다 (순서를 바꿔 넘겨도 같은 키가 나오도록)
     *   3) "name=value;" 로 이어 붙여 MD5 해시를 만든다
     *   4) BATCH_JOB_INSTANCE.JOB_KEY 에 저장. UNIQUE(JOB_NAME, JOB_KEY) 로 DB 가 최종 방어.
     *
     * 여기서 얻는 실무적 교훈:
     *   "값을 기록하고는 싶지만 인스턴스를 나누고 싶지는 않다" 는 요구가 non-identifying 입니다.
     *   executedBy(누가 돌렸나), buildNumber(CI 번호), chunkSize(성능 튜닝값) 가 전형적입니다.
     *   BATCH_JOB_EXECUTION_PARAMS 에 IDENTIFYING='N' 으로 저장은 되므로 감사 추적이 됩니다.
     *
     * 검증:
     *   SELECT (SELECT COUNT(*) FROM BATCH_JOB_INSTANCE)  AS instances,
     *          (SELECT COUNT(*) FROM BATCH_JOB_EXECUTION) AS executions;
     *   +-----------+------------+
     *   | instances | executions |
     *   +-----------+------------+
     *   |         3 |          3 |
     *   +-----------+------------+
     */
    static final String ANSWER_2_A = "3";
    static final String ANSWER_2_B = "3";
    static final String ANSWER_2_C = "3번째, JobInstanceAlreadyCompleteException";


    // ========================================================================
    // 정답 3. 정산 배치에 써도 되는 해결책
    // ========================================================================
    /*
     * 결론: (A) 파라미터 변경만 쓴다. (B) 는 절대 쓰지 않는다. (C) 는 애초에 다른 문제를 푸는 도구다.
     *
     * ── (A) 파라미터를 바꾼다 : ✅ 정답
     *
     *    "어제치를 다시 돌리고 싶다" 는 요구는 대개 둘 중 하나입니다.
     *      i)  어제 실행이 실패했다  → 그러면 STATUS 가 FAILED 이므로 같은 파라미터로 재실행이 이미 허용됩니다.
     *          JobInstanceAlreadyCompleteException 이 났다는 건 어제 실행이 "성공" 했다는 뜻입니다.
     *      ii) 어제는 성공했지만 원본 데이터가 보정되어 다시 계산해야 한다
     *          → 이건 "재실행" 이 아니라 "정정(correction)" 입니다.
     *            settlement 의 해당 날짜를 지우고 새 파라미터(예: date + reprocessSeq)로 돌리거나,
     *            아예 별도의 정정 Job 을 만드는 것이 정석입니다.
     *
     *    어느 쪽이든 프레임워크의 방어를 우회하지 않습니다.
     *
     * ── (B) RunIdIncrementer : ❌ 절대 금지
     *
     *    run.id 가 매번 증가하므로 identifying 집합이 항상 달라집니다.
     *    → JobInstanceAlreadyCompleteException 이 영원히 발생하지 않습니다.
     *    → "중복 실행 방지" 라는 기능 자체를 껐다는 뜻입니다.
     *
     *    구체적인 사고 시나리오 (2025-03-01, COMPLETED 389건):
     *
     *      * settlement 에 UNIQUE KEY uk_settlement_order (order_id) 가 있는 경우:
     *          2회차 실행이 첫 INSERT 에서 바로 터집니다.
     *            org.springframework.dao.DuplicateKeyException:
     *              Duplicate entry '60' for key 'settlement.uk_settlement_order'
     *          STATUS=FAILED. 시끄럽지만 데이터는 안전합니다. 이게 프로젝트 셋업 P-4 의 UNIQUE 키가 있는 이유입니다.
     *
     *      * UNIQUE 키가 없는 경우:
     *          settlement 에 같은 order_id 가 두 번 들어갑니다.
     *            389행 → 778행, 정산 총액 정확히 2배, STATUS=COMPLETED.
     *          아무도 모릅니다. 월말 대사(reconciliation)에서야 발견됩니다.
     *
     *    RunIdIncrementer 가 안전한 경우는 "멱등한 Job" 뿐입니다.
     *    예) 통계 테이블을 TRUNCATE 하고 전체를 다시 채우는 Job — 몇 번을 돌려도 결과가 같습니다.
     *    누적 INSERT 하는 Job 에는 붙이면 안 됩니다.
     *
     * ── (C) allowStartIfComplete(true) : ⚠️ 이 문제의 답이 아님
     *
     *    적용 대상이 Step 이고, 효과는 "이미 성공한 JobExecution 을 재시작할 때 성공했던 Step 을
     *    건너뛰지 않고 다시 실행한다" 입니다.
     *    JobInstanceAlreadyCompleteException 은 JobExecution 이 만들어지기 "전" 단계에서
     *    SimpleJobRepository.createJobExecution() 이 던지는 예외이므로,
     *    Step 옵션으로는 도달조차 하지 않습니다. 붙여도 증상이 그대로입니다.
     *
     *    이 옵션의 진짜 용도는 "매번 돌아야 안전한 준비 Step" 입니다.
     *      Step1: 임시 테이블 TRUNCATE  ← allowStartIfComplete(true)
     *      Step2: 대량 처리            ← 여기서 실패해 재시작
     *    이때 Step1 을 건너뛰면 임시 테이블에 이전 데이터가 남은 채로 Step2 가 돌아 데이터가 섞입니다.
     */
    static final String ANSWER_3 =
            "(A)만 사용. (B)는 중복 방지를 무력화해 정산이 두 배가 될 수 있음. (C)는 Step 레벨이라 이 예외를 막지 못함";


    // ========================================================================
    // 정답 4. Incrementer 와 Validator 의 충돌
    // ========================================================================
    /*
     * (a) 예측했어야 할 예외
     *
     *     Caused by: org.springframework.batch.core.JobParametersInvalidException:
     *       The JobParameters contains keys that are not explicitly optional or required: [run.id]
     *         at org.springframework.batch.core.job.DefaultJobParametersValidator.validate(...)
     *         at org.springframework.batch.core.job.AbstractJob.execute(AbstractJob.java:311)
     *
     *     이유:
     *       DefaultJobParametersValidator 는 optionalKeys 를 "하나라도" 지정하면
     *       화이트리스트 모드로 동작합니다. required + optional 에 없는 키는 전부 거부합니다.
     *       (optionalKeys 를 아예 비워 두면 추가 키를 자유롭게 허용합니다.)
     *
     *       그런데 RunIdIncrementer 는 프레임워크가 자동으로 run.id 를 "덧붙입니다".
     *       개발자가 명시적으로 넘긴 적이 없어서 optionalKeys 에 넣는 걸 잊기 쉽습니다.
     *
     *     이 조합이 특히 고약한 이유:
     *       로컬에서 `date=...` 만 넘겨 테스트하면 통과합니다. Incrementer 는 JobLauncher 직접 호출에서는
     *       동작하지 않기 때문입니다(3-7 함정). 그런데 운영에서 JobLauncherApplicationRunner /
     *       JobOperator.startNextInstance() 로 돌리는 순간 run.id 가 붙고 Job 이 시작조차 못 합니다.
     *
     * (b) 최소 수정: optionalKeys 에 "run.id" 를 추가
     */
    @Configuration
    public static class Sol4Config {

        @Bean
        public JobParametersValidator sol4Validator() {
            DefaultJobParametersValidator validator = new DefaultJobParametersValidator(
                    new String[]{"date"},
                    new String[]{"run.id", "chunkSize"}   // ← run.id 추가
            );
            validator.afterPropertiesSet();
            return validator;
        }

        /*
         * 대안 — optionalKeys 를 아예 지정하지 않아 화이트리스트 모드를 끄는 방법:
         *
         *   new DefaultJobParametersValidator(new String[]{"date"}, new String[]{});
         *
         * 트레이드오프:
         *   화이트리스트 ON  : 오타(dat=2025-03-01)를 잡아 줍니다. 대신 프레임워크나 운영팀이
         *                      추가하는 키(run.id, jobTriggeredBy 등)마다 목록을 갱신해야 합니다.
         *   화이트리스트 OFF : 유연하지만 오타를 못 잡습니다. `dat=2025-03-01` 은
         *                      "date 없음" 으로 걸리므로 required 검사에는 걸립니다만,
         *                      선택 파라미터의 오타(dryRunn=true)는 조용히 무시됩니다.
         *
         * 정산처럼 중요한 배치라면 화이트리스트를 켜고, 키가 늘어날 때마다 목록을 관리하는 쪽을 권합니다.
         * 목록 관리 비용보다 오타로 인한 사고 비용이 훨씬 큽니다.
         */

        @Bean
        public Job sol4Job(JobRepository jobRepository, Step paramStep,
                           JobParametersValidator sol4Validator) {
            return new JobBuilder("sol4Job", jobRepository)
                    .incrementer(new RunIdIncrementer())
                    .validator(sol4Validator)
                    .start(paramStep)
                    .build();
        }
    }


    // ========================================================================
    // 정답 5. 커스텀 Validator
    // ========================================================================
    public static class Sol5Validator implements JobParametersValidator {

        private static final LocalDate MIN = LocalDate.of(2025, 1, 1);
        private static final LocalDate MAX = LocalDate.of(2025, 6, 29);

        @Override
        public void validate(JobParameters parameters) throws JobParametersInvalidException {

            if (parameters == null) {
                throw new JobParametersInvalidException("JobParameters 가 null 입니다.");
            }

            // ── date : 필수 + 형식 + 범위 ────────────────────────────────
            String rawDate = parameters.getString("date");
            if (rawDate == null || rawDate.isBlank()) {
                throw new JobParametersInvalidException(
                        "필수 파라미터 'date' 가 없습니다. 예: date=2025-03-01");
            }

            LocalDate date;
            try {
                // LocalDate.parse 는 ISO-8601(yyyy-MM-dd) 만 받습니다.
                // 그래서 20250301, 2025-3-1, 2025/03/01 이 전부 여기서 걸립니다.
                // 이 세 가지가 실무에서 사람이 손으로 실행할 때 나오는 오타 1~3위입니다.
                date = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                throw new JobParametersInvalidException(
                        "'date' 형식이 잘못되었습니다: '%s' (기대 형식: yyyy-MM-dd)".formatted(rawDate));
            }

            // 범위 검사가 왜 필요한가:
            // 형식이 맞아도 date=2024-12-31 은 orders 에 데이터가 없어 0건입니다.
            // 형식 검사만 하면 이건 여전히 "0건 정산 후 COMPLETED" 로 조용히 지나갑니다.
            // 데이터 보유 기간을 아는 것은 배치 자신이므로, 배치가 막아야 합니다.
            if (date.isBefore(MIN) || date.isAfter(MAX)) {
                throw new JobParametersInvalidException(
                        "'date' 가 데이터 보유 기간(%s ~ %s)을 벗어났습니다: %s".formatted(MIN, MAX, date));
            }

            // ── dryRun : 선택 + "true"/"false" 화이트리스트 ──────────────
            String rawDryRun = parameters.getString("dryRun");
            if (rawDryRun != null) {          // null 이면 통과 (선택 파라미터)

                // ★ 여기가 이 문제의 핵심 ★
                //
                // Boolean.parseBoolean(v) 를 쓰면 안 됩니다.
                // parseBoolean 은 "true"(대소문자 무시)가 아닌 모든 문자열을 예외 없이 false 로 만듭니다.
                //     Boolean.parseBoolean("yes")   → false
                //     Boolean.parseBoolean("1")     → false
                //     Boolean.parseBoolean("TRUE")  → true
                //     Boolean.parseBoolean("ture")  → false   ← 오타
                //
                // dryRun=yes 로 넘긴 사람은 "쓰기 없이 시뮬레이션만 한다" 고 믿습니다.
                // 그런데 parseBoolean 이 false 를 주면 배치는 dry-run 이 아니라
                // 실제 쓰기 모드로 돌아 settlement 에 389행을 INSERT 합니다.
                // 예외도 없고 경고도 없습니다. 전형적인 "조용히 틀리는" 사고입니다.
                //
                // 그래서 파싱이 아니라 화이트리스트 검사를 합니다.
                if (!"true".equals(rawDryRun) && !"false".equals(rawDryRun)) {
                    throw new JobParametersInvalidException(
                            "'dryRun' 은 'true' 또는 'false' 만 허용합니다: '%s'".formatted(rawDryRun));
                }
            }
        }
    }

    @Configuration
    public static class Sol5Config {
        @Bean
        public Job sol5Job(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("sol5Job", jobRepository)
                    .validator(new Sol5Validator())
                    .start(paramStep)
                    .build();
        }
    }
    /*
     * 실행 결과 요약
     *
     *   (파라미터 없음)          → JobParametersInvalidException: 필수 파라미터 'date' 가 없습니다. ...
     *   date=20250301           → JobParametersInvalidException: 'date' 형식이 잘못되었습니다: '20250301' ...
     *   date=2024-12-31         → JobParametersInvalidException: 'date' 가 데이터 보유 기간(2025-01-01 ~ 2025-06-29)을 ...
     *   date=2025-03-01,dryRun=yes
     *                           → JobParametersInvalidException: 'dryRun' 은 'true' 또는 'false' 만 허용합니다: 'yes'
     *   date=2025-03-11,dryRun=true
     *                           → COMPLETED, [paramStep] date=2025-03-11, COMPLETED orders=389
     */


    // ========================================================================
    // 정답 6. 경계가 있는 Incrementer
    // ========================================================================
    public static class Sol6Incrementer implements JobParametersIncrementer {

        private static final LocalDate START = LocalDate.of(2025, 3, 1);
        private static final LocalDate MAX = LocalDate.of(2025, 6, 29);

        @Override
        public JobParameters getNext(JobParameters parameters) {

            LocalDate next;
            if (parameters == null || parameters.getString("date") == null) {
                next = START;
            } else {
                next = LocalDate.parse(parameters.getString("date")).plusDays(1);
            }

            // ★ off-by-one 주의 ★
            //
            // 검사는 "다음에 실행할 날짜(next)" 에 대해 합니다. 이전 날짜가 아닙니다.
            //   next = 2025-06-29 → isAfter(MAX) 가 false → 통과 → 그날 정산이 실행됨 (요구사항)
            //   next = 2025-06-30 → isAfter(MAX) 가 true  → 예외 (요구사항)
            //
            // 흔한 실수는 `next.isAfter(MAX) || next.isEqual(MAX)` 나
            // `!next.isBefore(MAX)` 로 쓰는 것입니다. 그러면 6-29 가 실행되지 못하고
            // 하루치 정산이 통째로 누락됩니다. 그리고 이건 예외가 아니라
            // "마지막 날만 빠진 정산 결과" 로 나타나므로 발견이 늦습니다.
            if (next.isAfter(MAX)) {
                throw new IllegalStateException(
                        "정산 가능한 마지막 날짜(%s)를 넘었습니다: %s".formatted(MAX, next));
            }

            JobParameters base = (parameters == null) ? new JobParameters() : parameters;
            return new JobParametersBuilder(base)
                    .addString("date", next.toString())
                    .toJobParameters();
        }
    }

    @Configuration
    public static class Sol6Config {
        @Bean
        public Job sol6Job(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("sol6Job", jobRepository)
                    .incrementer(new Sol6Incrementer())
                    .start(paramStep)
                    .build();
        }
    }
    /*
     * ── 이 답의 운영상 단점과 대안 ─────────────────────────────────────────
     *
     * getNext() 안에서 예외를 던지면 JobExecution 이 아예 생성되지 않습니다.
     * 그 결과:
     *   - BATCH_JOB_EXECUTION 에 아무 흔적도 남지 않습니다.
     *   - 배치 모니터링 대시보드에는 "그날 아무 일도 없었던 것" 처럼 보입니다.
     *   - 스케줄러 로그를 직접 뒤져야 원인을 압니다.
     *
     * 실행 로그는 이렇게 나옵니다:
     *   ERROR 44502 --- [ main] o.s.boot.SpringApplication : Application run failed
     *   java.lang.IllegalStateException: 정산 가능한 마지막 날짜(2025-06-29)를 넘었습니다: 2025-06-30
     *       at com.example.batch.step03.Solution$Sol6Incrementer.getNext(Solution.java:...)
     *       at org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner
     *             .getNextJobParameters(JobLauncherApplicationRunner.java:245)
     *
     * 대안 (프레임워크의 방어를 이용하는 방법):
     *
     *   if (next.isAfter(MAX)) {
     *       return parameters;   // 예외 대신 "이전 파라미터를 그대로" 반환
     *   }
     *
     * 그러면 이미 COMPLETED 인 인스턴스로 실행을 시도하게 되어
     * JobInstanceAlreadyCompleteException 이 납니다.
     *   - 장점: 메시지에 어떤 파라미터가 문제인지 명확히 찍히고, 프레임워크 표준 예외라
     *           운영 도구들이 이미 알고 있습니다.
     *   - 단점: 메시지가 "마지막 날짜를 넘었다" 는 도메인 의미를 전달하지 못합니다.
     *           운영자는 "왜 이미 완료됐다는 거지?" 하고 한 번 더 헤맵니다.
     *
     * 어느 쪽이든 "조용히 아무 일도 안 일어나는" 것보다는 낫습니다.
     * 가장 나쁜 구현은 아래처럼 경계를 넘으면 START 로 되돌아가는 것입니다.
     *
     *   if (next.isAfter(MAX)) next = START;   // ❌ 절대 금지
     *
     * 이렇게 하면 예외 없이 3월 1일부터 다시 돌기 시작하고,
     * 이미 완료된 인스턴스라 JobInstanceAlreadyCompleteException 이 나거나,
     * (Incrementer 를 쓰는 Job 이라면) 정산이 다시 한 번 수행됩니다.
     */
}
