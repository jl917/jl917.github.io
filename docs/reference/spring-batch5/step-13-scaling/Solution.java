package com.example.batch.step13;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 13 — 연습문제 정답과 해설
 *
 * 문제를 직접 풀어 본 뒤에 여십시오.
 */
public class Solution {

    // =====================================================================
    // 정답 1. 스레드 안전하지 않은 Reader 를 두 가지 방법으로 고치기
    // =====================================================================

    /*
     * (a) 왜 스레드 안전하지 않은가
     *
     *   `orders.get(index++)` 의 index++ 가 원자적이지 않기 때문입니다.
     *
     *   index++ 는 바이트코드에서 세 단계로 나뉩니다.
     *     ① getfield index      (읽기)
     *     ② iadd 1              (더하기)
     *     ③ putfield index      (쓰기)
     *
     *   스레드 A 와 B 가 ①을 동시에 실행하면 둘 다 같은 값(예: 500)을 읽습니다.
     *   둘 다 500번 아이템을 반환하고, 둘 다 index 를 501 로 씁니다.
     *   결과적으로
     *     - 500번 아이템이 두 번 처리되고
     *     - 501번이 되었어야 할 한 자리가 통째로 사라집니다.
     *
     *   settlement.order_id 에 UNIQUE 가 걸려 있고 Writer 가 멱등이라
     *   "중복"은 흡수되어 안 보이고, "누락"만 남습니다.
     *   그래서 70,000 이 아니라 69,213 이 되는 것입니다.
     *
     *   ⚠️ 이 결함의 가장 고약한 점은 결정론적이지 않다는 것입니다.
     *      스레드 스케줄링에 의존하므로 실행할 때마다 손실 건수가 다릅니다.
     *      69213 / 68940 / 69551 ...
     *
     *      역으로, 이것이 진단 기법이 됩니다.
     *      **입력이 완전히 결정론적인데 출력이 매번 다르면, 원인은 거의 항상
     *      스레드 안전성입니다.** 프로젝트 셋업에서 RAND() 를 한 번도 쓰지
     *      않고 나머지 연산만으로 시드를 만든 이유가 바로 이것입니다.
     */

    /** ① AtomicInteger 로 인덱스를 원자화 — 빠른 특수해. */
    public static class FixedByAtomicReader implements ItemReader<Order> {

        private final List<Order> orders;
        private final AtomicInteger index = new AtomicInteger(0);

        public FixedByAtomicReader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public Order read() {
            // getAndIncrement() 는 CAS(Compare-And-Swap) 기반이라
            // 읽기+증가+쓰기가 하나의 원자적 연산으로 처리됩니다.
            int i = index.getAndIncrement();
            if (i >= orders.size()) {
                return null;
            }
            return orders.get(i);
        }
    }

    /*
     * ② SynchronizedItemStreamReader 로 감싸기 — 느린 일반해.
     *
     *   @Bean
     *   public SynchronizedItemStreamReader<Order> safeReader(DataSource ds) {
     *       SynchronizedItemStreamReader<Order> reader = new SynchronizedItemStreamReader<>();
     *       reader.setDelegate(orderCursorReader(ds));
     *       return reader;
     *   }
     *
     *   read() 전체를 synchronized 로 감쌉니다.
     *
     * (c) 측정 결과와 해석
     *
     *   ① AtomicInteger  : 18.4초
     *   ② Synchronized   : 21.6초
     *
     *   AtomicInteger 가 약 17% 빠릅니다. 이유는 두 가지입니다.
     *
     *   - CAS 는 락을 잡지 않습니다. 경합이 없으면 사실상 공짜이고,
     *     경합이 있어도 커널 레벨의 컨텍스트 스위칭이 없습니다.
     *     synchronized 는 경합 시 스레드를 블로킹시킵니다.
     *   - 임계 구역의 크기가 다릅니다. AtomicInteger 버전은 "인덱스 증가"
     *     한 순간만 원자적이면 되고, 실제 `orders.get(i)` 는 여러 스레드가
     *     동시에 할 수 있습니다. Synchronized 버전은 read() 호출 전체를
     *     직렬화하므로 delegate 의 DB 접근까지 한 줄로 세웁니다.
     *
     *   ⚠️ 그러나 AtomicInteger 해법에는 중요한 전제가 있습니다.
     *      **`orders.get(i)` 자체가 스레드 안전해야** 합니다.
     *      여기서는 불변 List 를 읽기만 하므로 안전합니다. 하지만
     *      JdbcCursorItemReader 처럼 내부에 ResultSet 커서를 들고 있는
     *      Reader 에는 이 방법을 쓸 수 없습니다. 인덱스를 원자화해 봐야
     *      ResultSet.next() 자체가 스레드 안전하지 않기 때문입니다.
     *
     *   정리하면 전형적인 "빠른 특수해 vs 느린 일반해" 트레이드오프입니다.
     *
     *     | | AtomicInteger | SynchronizedItemStreamReader |
     *     |---|---|---|
     *     | 속도 | 18.4초 | 21.6초 |
     *     | 적용 범위 | 내가 만든 Reader, 백킹 자료구조가 안전할 때만 | 모든 ItemStreamReader |
     *     | 구현 난이도 | Reader 를 직접 고쳐야 함 | 한 줄로 감싸기 |
     *
     *   실무에서는 대개 ②를 씁니다. 3.2초를 아끼려고 Reader 내부 구현에
     *   대한 가정을 코드에 심는 것은 대체로 손해입니다.
     *   그리고 애초에 둘 다 파티셔닝(11.2초)보다 느립니다 — 정답 6 참고.
     */

    // =====================================================================
    // 정답 2. Synchronized 로 감싸도 여전히 틀리는 경우
    //
    // 이 문제가 이 스텝의 진짜 교훈입니다.
    // =====================================================================

    /*
     * (a) 왜 매번 다른가
     *
     *   SynchronizedItemStreamReader 는 이름 그대로 **Reader 만** 보호합니다.
     *   Processor 는 전혀 건드리지 않습니다.
     *
     *   RunningTotalProcessor.process() 안의
     *     runningTotal = runningTotal.add(order.amount());
     *   는 읽기 → 계산 → 쓰기의 3단계이고, index++ 와 정확히 같은 결함을
     *   가지고 있습니다. BigDecimal 이 불변 객체라는 사실은 아무 도움이
     *   되지 않습니다. 불변인 것은 BigDecimal 이지 `runningTotal` 필드가
     *   아니기 때문입니다.
     *
     *   4개 스레드가 동시에 더하면 갱신이 서로를 덮어써서 합계가 실제보다
     *   작게 나옵니다. 그리고 얼마나 작을지는 스케줄링에 달렸습니다.
     *
     * (b) settlement 행 수는 맞는가?
     *
     *   맞습니다. 정확히 70,000 입니다.
     *
     *   **이것이 이 문제의 핵심입니다.** Reader 를 동기화했으므로 모든
     *   아이템이 정확히 한 번씩 읽히고, 처리되고, 쓰였습니다. 저장된
     *   데이터는 완벽합니다.
     *
     *   틀린 것은 **Processor 가 곁다리로 들고 있던 집계값**입니다.
     *   그리고 이 값은 DB 에 없고 메모리에만 있어서, 검증 쿼리로는
     *   절대 잡히지 않습니다. 로그로 찍거나 리포트에 넣는 순간
     *   조용히 틀린 숫자가 됩니다.
     *
     *   "Reader 만 고치면 된다"는 오해가 여기서 깨집니다.
     *   스레드 안전성은 Reader·Processor·Writer·Listener **전부**의 문제입니다.
     *
     * (c) 올바른 수정 — 두 갈래 중 무엇이 정답인가
     *
     *   갈래 ① 누적을 스레드 안전하게 만든다
     *
     *     private final LongAdder totalCents = new LongAdder();
     *     ...
     *     totalCents.add(order.amount().movePointRight(2).longValueExact());
     *
     *     LongAdder 는 경합 시 내부 셀로 분산 누적해서 AtomicLong 보다
     *     빠릅니다. (BigDecimal 은 원자적 누적 API 가 없으므로 정수 센트로
     *     바꿔서 다룹니다.)
     *
     *   갈래 ② 애초에 누적하지 않는다  ← **이쪽이 정답입니다**
     *
     *     ItemProcessor 는 **순수 함수여야 합니다.** 입력 하나를 받아
     *     출력 하나를 내놓을 뿐, 상태를 남기지 않아야 합니다.
     *     이것은 멀티스레드 때문에 생긴 규칙이 아니라 원래의 계약입니다.
     *
     *     집계가 필요하면 집계를 담당하는 곳에서 하십시오.
     *
     *     - Step 이 끝난 뒤의 합계가 필요하다면
     *       → StepExecutionListener.afterStep() 에서 SQL 로 집계
     *         SELECT SUM(net_amount) FROM settlement WHERE ...
     *       DB 가 이미 정확한 값을 갖고 있는데 애플리케이션 메모리에서
     *       따로 세는 것은 중복이자 버그의 원천입니다.
     *
     *     - 건수만 필요하다면
     *       → StepExecution.getWriteCount() 를 쓰십시오.
     *         프레임워크가 이미 스레드 안전하게 세고 있습니다.
     *
     *   갈래 ①은 "동작하게" 만들고, 갈래 ②는 "애초에 틀릴 수 없게" 만듭니다.
     *   동시성 문제는 언제나 후자가 정답입니다. 공유 상태를 안전하게
     *   다루는 것보다 공유 상태를 없애는 것이 쉽습니다.
     */

    /** 상태를 갖지 않는 올바른 Processor. Practice 의 SettlementProcessor 와 동일한 형태. */
    public static class StatelessProcessor implements ItemProcessor<Order, Settlement> {
        @Override
        public Settlement process(Order order) {
            // 필드가 없습니다. 그래서 스레드 안전성을 고민할 필요 자체가 없습니다.
            return null;    // 실제 변환은 Practice.SettlementProcessor 참고
        }
    }

    // =====================================================================
    // 정답 3. customer_id 기준 Partitioner
    // =====================================================================

    /** (a) 연속 범위 분할 — 등급 스큐가 생깁니다. */
    public static class CustomerRangePartitioner implements Partitioner {

        private final JdbcTemplate jdbcTemplate;

        public CustomerRangePartitioner(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public Map<String, ExecutionContext> partition(int gridSize) {
            Integer min = jdbcTemplate.queryForObject(
                    "SELECT MIN(customer_id) FROM customers", Integer.class);
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT MAX(customer_id) FROM customers", Integer.class);

            int targetSize = (max - min) / gridSize + 1;
            Map<String, ExecutionContext> result = new LinkedHashMap<>();
            int start = min;
            for (int i = 0; i < gridSize; i++) {
                ExecutionContext ctx = new ExecutionContext();
                int end = Math.min(start + targetSize - 1, max);
                ctx.putInt("minCustomerId", start);
                ctx.putInt("maxCustomerId", end);
                result.put("partition" + i, ctx);
                start = end + 1;
            }
            return result;
        }
    }

    /*
     * (b) 등급 분포 확인 결과
     *
     *   gridSize=4 로 나누면 파티션은 1~250, 251~500, 501~750, 751~1000 입니다.
     *
     *   SELECT customer_id % 4 AS grade_key, COUNT(*) FROM customers
     *   WHERE customer_id BETWEEN 1 AND 250 GROUP BY grade_key;
     *
     *   +-----------+----------+
     *   | grade_key | COUNT(*) |
     *   +-----------+----------+
     *   |         0 |       62 |
     *   |         1 |       63 |
     *   |         2 |       63 |
     *   |         3 |       62 |
     *   +-----------+----------+
     *
     *   ... 사실 고르게 나옵니다.
     *
     *   왜냐하면 등급이 `customer_id % 4` 로 정해지는데, 250 은 4의 배수에
     *   가까워서 어떤 연속 구간을 잡아도 나머지 0/1/2/3 이 거의 같은 수로
     *   섞이기 때문입니다.
     *
     *   ⚠️ 그러니까 이 문제의 진짜 답은 이것입니다:
     *      **우리 실습 데이터에서는 스큐가 안 생깁니다.**
     *      시드를 나머지 연산으로 만들었기 때문에, 어떤 방식으로 잘라도
     *      균등합니다. 이건 실습 데이터의 성질이지 일반적인 성질이 아닙니다.
     *
     *   실제 데이터였다면 이렇게 됩니다.
     *     - VIP 고객은 보통 초기 가입자라 customer_id 가 작은 쪽에 몰립니다.
     *     - 그런데 VIP 는 주문 건수도 많습니다.
     *     - 따라서 파티션 0 (customer_id 1~250) 이 다른 파티션의 3~5배
     *       분량을 떠안게 됩니다.
     *     - 8개 파티션 중 7개가 2초에 끝나고 하나가 40초를 도는 상황.
     *       전체 소요는 40초입니다. 병렬화의 이득이 거의 사라집니다.
     *
     *   파티션별 실제 처리량은 이 쿼리로 확인합니다.
     *
     *     SELECT STEP_NAME, READ_COUNT, WRITE_COUNT,
     *            TIMESTAMPDIFF(SECOND, START_TIME, END_TIME) AS secs
     *     FROM BATCH_STEP_EXECUTION
     *     WHERE STEP_NAME LIKE 'settlementWorkerStep:partition%'
     *     ORDER BY secs DESC;
     *
     *   READ_COUNT 가 파티션마다 2배 이상 차이 나면 스큐입니다.
     */

    /** (c) 해시 분할 — 어떤 분포에서도 균등합니다. */
    public static class CustomerHashPartitioner implements Partitioner {

        @Override
        public Map<String, ExecutionContext> partition(int gridSize) {
            Map<String, ExecutionContext> result = new LinkedHashMap<>();
            for (int i = 0; i < gridSize; i++) {
                ExecutionContext ctx = new ExecutionContext();
                // WHERE customer_id % :gridSize = :remainder
                ctx.putInt("gridSize", gridSize);
                ctx.putInt("remainder", i);
                result.put("partition" + i, ctx);
            }
            return result;
        }
    }

    /*
     *   해시 분할 Reader 의 WHERE 절:
     *     WHERE status = 'COMPLETED' AND customer_id % :gridSize = :remainder
     *
     *   ⚠️ 성능상의 큰 함정이 하나 있습니다.
     *      `customer_id % 4 = 0` 은 컬럼에 함수를 씌운 형태라 **인덱스를
     *      타지 못합니다.** 각 파티션이 풀스캔을 하게 되어, 8개 파티션이면
     *      테이블을 8번 풀스캔합니다. 범위 분할보다 오히려 느려질 수 있습니다.
     *
     *      실무에서는 이 때문에 샤드 키 컬럼을 아예 따로 둡니다.
     *        ALTER TABLE orders ADD COLUMN shard_key TINYINT
     *          GENERATED ALWAYS AS (customer_id % 16) STORED,
     *          ADD INDEX idx_shard (shard_key, status);
     *      생성 컬럼에 인덱스를 걸면 해시 분할도 인덱스를 탑니다.
     *
     * (d) 범위 분할 vs 해시 분할
     *
     *   | | 범위 분할 | 해시 분할 |
     *   |---|---|---|
     *   | 인덱스 | PK 범위라 잘 탐 | 함수 조건이라 못 탐 (생성 컬럼 필요) |
     *   | 스큐 | 데이터 분포에 취약 | 항상 균등 |
     *   | 신규 데이터 | 마지막 파티션에 몰림 | 고르게 분산 |
     *   | 재실행 일관성 | gridSize 바뀌면 경계 이동 | gridSize 바뀌면 전체 재배치 |
     *
     *   실무 지침:
     *   - **기본은 범위 분할.** 인덱스를 타는 것이 압도적으로 중요합니다.
     *   - 스큐가 확인되면 → gridSize 를 크게(100) 두고 풀은 작게(8).
     *     조각이 잘게 쪼개지면 큰 조각 하나가 전체를 지배하는 일이 줄고,
     *     빈 스레드가 다음 조각을 바로 집어 갑니다. 13-9 의 #11 구성입니다.
     *   - 그래도 안 되면 → 샤드 키 생성 컬럼 + 해시 분할.
     */

    // =====================================================================
    // 정답 4. @StepScope 를 빼면
    // =====================================================================

    /*
     * (a)(b) 예외와 시점
     *
     *   org.springframework.expression.spel.SpelEvaluationException:
     *     EL1007E: Property or field 'minId' cannot be found on null
     *
     *   발생 시점은 **애플리케이션 기동 중**입니다. Job 실행 중이 아닙니다.
     *
     *   실제 로그:
     *
     *   ERROR 44821 --- [  main] o.s.boot.SpringApplication : Application run failed
     *   org.springframework.beans.factory.UnsatisfiedDependencyException:
     *     Error creating bean with name 'partitionedOrderReader' defined in ...
     *     Failed to convert value of type 'null' ...
     *   Caused by: org.springframework.expression.spel.SpelEvaluationException:
     *     EL1007E: Property or field 'minId' cannot be found on null
     *
     *   이유는 단순합니다. @StepScope 가 없으면 이 @Bean 메서드는 싱글턴이라
     *   **컨텍스트 기동 시점에 딱 한 번** 호출됩니다. 그 시점에는 Job 이
     *   시작도 안 했으므로 stepExecutionContext 자체가 존재하지 않습니다.
     *   SpEL 이 null 위에서 'minId' 를 찾으려다 터집니다.
     *
     *   @StepScope 를 붙이면 스코프 프록시가 끼어들어, 실제 빈 생성이
     *   **각 Step 실행 시점으로 미뤄집니다.** 그때는 파티션이 만들어져
     *   stepExecutionContext 에 minId/maxId 가 들어 있습니다.
     *   이것이 "늦은 바인딩(late binding)"입니다.
     *
     * (c) 시끄러운 실패 vs 조용한 실패
     *
     *   이것은 **시끄러운 실패**이고, 그래서 다행입니다.
     *
     *   - 애플리케이션이 아예 뜨지 않습니다.
     *   - 배포 파이프라인에서 즉시 걸립니다.
     *   - 운영에 나갈 수가 없습니다.
     *
     *   13-8 의 함정 블록이 지적한 반대 사례와 대조해 보십시오.
     *   파라미터를 SpEL 대신 `new HashMap<>()` 같은 빈 값으로 넘기도록
     *   잘못 짜면, 예외 없이 `WHERE order_id BETWEEN null AND null` 이
     *   되어 **0건을 조회하고 Job 은 COMPLETED 로 끝납니다.**
     *   settlement 는 비어 있는데 배치는 성공했다고 보고합니다.
     *
     *   같은 실수(늦은 바인딩 실패)인데 한쪽은 기동조차 안 되고 한쪽은
     *   조용히 아무것도 안 합니다. 후자가 비교할 수 없이 위험합니다.
     *
     *   이 코스가 반복해서 말하는 것이 이것입니다:
     *   **문법 에러는 금방 고치지만, 에러 없이 조용히 틀리는 코드가
     *   진짜 위험합니다.**
     */

    // =====================================================================
    // 정답 5. 커넥션 풀 20 에서 안전한 최대 gridSize
    // =====================================================================

    /*
     * (a) 워커 하나당 커넥션 수 = 2
     *
     *   ① Reader 의 커넥션 — JdbcPagingItemReader 가 페이지를 조회할 때
     *   ② 청크 트랜잭션의 커넥션 — Writer 의 INSERT 와 JobRepository 의
     *      메타데이터 갱신(BATCH_STEP_EXECUTION UPDATE)이 같은 트랜잭션에
     *      묶여 하나를 더 씁니다.
     *
     *   ②를 놓치기 쉽습니다. "Reader 하나니까 워커당 1개"라고 계산하면
     *   실제의 절반으로 잡게 되어 반드시 풀이 마릅니다.
     *
     *   ⚠️ 커서 기반 Reader 는 더 나쁩니다. JdbcCursorItemReader 는
     *      Step 이 끝날 때까지 커넥션을 **계속 붙들고 있습니다.**
     *      페이징은 페이지마다 반납하지만 커서는 반납하지 않습니다.
     *      파티셔닝에 페이징 Reader 를 쓰는 이유 중 하나입니다.
     *
     * (b) 여유분 = 4
     *
     *   - 마스터 Step 자신 : 1
     *   - Partitioner 의 MIN/MAX 초기 쿼리 : 1
     *   - JobRepository 의 Job 레벨 메타데이터 갱신 여유 : 2
     *
     * (c) 공식
     *
     *   최대 동시 워커 수 = (maximum-pool-size - 여유분) / 워커당 커넥션 수
     *                    = (20 - 4) / 2
     *                    = 8
     *
     *   따라서 **동시 워커 8개가 상한**입니다.
     *
     *   여기서 gridSize 와 풀 크기를 구분해야 합니다.
     *     - 동시에 도는 워커 수 = TaskExecutor 의 corePoolSize → 8 이 상한
     *     - gridSize = 조각 수 → 8 보다 커도 됩니다. 순차로 처리될 뿐입니다.
     *
     * (d) 실측표와의 대조 — 정확히 맞습니다
     *
     *   #8  파티셔닝 grid 8  → 11.2초  5.6배  ← 최적
     *   #9  파티셔닝 grid 16 → 12.9초  4.8배  ← 역전
     *   #10 파티셔닝 grid 32 → 24.6초  2.5배  ← 대기 지옥
     *
     *   계산상의 상한 8 에서 최적점이 나오고, 그 이상에서 성능이 떨어집니다.
     *   grid 16 일 때 Hikari 로그를 보면 근거가 보입니다.
     *
     *     HikariPool - Pool stats (total=20, active=20, idle=0, waiting=12)
     *
     *   active=20 으로 풀이 완전히 소진됐고 12개 요청이 대기 중입니다.
     *   워커들이 일을 하는 게 아니라 커넥션을 기다리며 서로를 막고 있습니다.
     *   grid 32 에서는 이 대기가 심해져 단일 스레드의 2.5배까지 떨어집니다.
     *
     *   ⚠️ 최악의 경우는 성능 저하가 아니라 데드락입니다.
     *      워커가 Reader 용 커넥션 1개를 잡은 채 트랜잭션용 2번째 커넥션을
     *      기다리는데, 모든 워커가 같은 상태면 아무도 진행하지 못합니다.
     *      결국 이렇게 죽습니다.
     *        HikariPool - Connection is not available,
     *        request timed out after 30000ms
     *      "워커당 2개"를 정확히 계산해야 하는 진짜 이유입니다.
     *
     *   #11 파티셔닝 grid 100 / 풀 8 → 11.9초 5.2배
     *
     *   이 구성이 실무에서 권장됩니다. 최적(11.2초)과 거의 같으면서
     *   스큐에 훨씬 강합니다. 조각이 잘아서 한 조각이 오래 걸려도 전체를
     *   지배하지 않고, 빈 스레드가 즉시 다음 조각을 집어 갑니다.
     *   **gridSize 는 크게, 풀은 계산된 상한으로.**
     */

    // =====================================================================
    // 정답 6. 4개 시나리오 판정
    //
    // 이 표가 실무에서 그대로 쓸 수 있는 의사결정 체크리스트입니다.
    // =====================================================================

    /*
     * 시나리오 A ─ 100만 행, 단순 계산, 재시작 필수
     *
     *   → **파티셔닝**
     *
     *   재시작이 반드시 되어야 한다는 조건에서 이미 멀티스레드 Step 은
     *   탈락입니다. 멀티스레드 Step 은 saveState(false) 가 사실상 필수라
     *   재시작을 포기해야 하기 때문입니다(13-5).
     *
     *   파티셔닝은 각 워커 Step 이 독립된 StepExecution 을 가지므로
     *   ExecutionContext 가 정상적으로 저장되고, 실패한 파티션만 골라
     *   재시작됩니다. 이미 COMPLETED 인 파티션은 건너뜁니다.
     *
     *   주의점: PK 범위로 나누되 스큐를 확인할 것. gridSize 는 크게(100),
     *   풀은 (pool-4)/2 로.
     *
     *
     * 시나리오 B ─ 5만 건, 건당 외부 API 200ms, CPU 거의 안 씀
     *
     *   → **멀티스레드 Step**  ← 멀티스레드가 정답인 유일한 시나리오
     *
     *   이 경우가 특별한 이유는 **병목이 DB 도 CPU 도 아니라 네트워크
     *   대기**이기 때문입니다.
     *
     *   단일 스레드면 5만 건 × 200ms = 약 2시간 47분입니다. 스레드가
     *   대기하는 동안 아무 일도 안 합니다. 스레드를 32개로 늘리면 한
     *   스레드가 응답을 기다리는 동안 다른 31개가 각자 요청을 날립니다.
     *   이론상 32배, 약 5분입니다.
     *
     *   여기서는 커넥션 풀 계산이 병목이 아닙니다. 워커들이 대부분의
     *   시간을 DB 밖(HTTP 대기)에서 보내기 때문에, 스레드 수를 커넥션
     *   풀보다 훨씬 크게 잡아도 됩니다.
     *
     *   주의점 세 가지:
     *   - Reader 를 반드시 동기화하거나 스레드 안전한 것으로 쓸 것.
     *   - 재시작이 안 되므로, 실패 시 처음부터 다시 돌 것을 감수하거나
     *     Writer 를 멱등으로 만들어 중복 호출을 흡수할 것.
     *   - **상대방 API 의 rate limit 를 반드시 확인할 것.** 32 스레드로
     *     때리다 차단당하면 배치가 아니라 사고입니다.
     *
     *
     * 시나리오 C ─ 2GB CSV 하나, FlatFileItemReader
     *
     *   → **둘 다 아님. 단일 스레드로 두거나, 파일을 먼저 쪼갤 것.**
     *
     *   FlatFileItemReader 는 파일 포인터라는 본질적인 순차 상태를
     *   가집니다. 여러 스레드가 하나의 파일 스트림을 공유하면 줄이
     *   섞이거나 잘립니다. Synchronized 로 감싸면 정확해지지만 읽기가
     *   완전히 직렬화되어 이득이 거의 없습니다 — 그리고 이 시나리오에서는
     *   읽기가 바로 병목입니다.
     *
     *   제대로 하려면 파일을 물리적으로 나눕니다.
     *     split -l 250000 big.csv part_
     *   그리고 **파일 하나당 파티션 하나**를 배정합니다. MultiResourcePartitioner
     *   가 정확히 이 용도의 기본 제공 Partitioner 입니다.
     *
     *   교훈: 나눌 수 없는 자원(단일 파일 스트림)을 억지로 병렬화하지 말고,
     *   자원 자체를 나눌 것.
     *
     *
     * 시나리오 D ─ 8대 서버에 분산
     *
     *   → **원격 파티셔닝** (원격 청킹 아님)
     *
     *   여러 JVM 으로 나가는 순간 TaskExecutorPartitionHandler 는 쓸 수
     *   없습니다. MessageChannelPartitionHandler 로 바꾸고 RabbitMQ 나
     *   Kafka 로 파티션 정보를 워커 서버들에 보냅니다.
     *
     *   원격 청킹이 아니라 원격 파티셔닝인 이유:
     *   - 원격 청킹은 **마스터가 혼자 읽어서** 워커에 아이템을 뿌립니다.
     *     읽기가 분산되지 않으므로, 읽기가 병목이면 8대를 붙여도 소용없고
     *     오히려 직렬화 비용만 늘어납니다.
     *   - 원격 파티셔닝은 마스터가 **범위만** 알려 주고 각 워커가 스스로
     *     읽습니다. 읽기까지 8대로 분산됩니다.
     *
     *   원격 청킹이 맞는 경우는 시나리오 B 같은 때입니다. 읽기는 가벼운데
     *   건당 처리가 무거운 경우.
     *
     *   주의점: 마스터와 워커가 **같은 JobRepository(같은 DB)를 봐야**
     *   합니다. 그래야 워커의 StepExecution 상태를 마스터가 취합할 수
     *   있습니다. 이 지점에서 메타데이터 DB 가 단일 장애점이 됩니다.
     *
     *
     * ─────────────────────────────────────────────────────────────────
     * 종합 의사결정 순서 (13-11 의 결론)
     *
     *   1. 먼저 단일 스레드로 튜닝하십시오.
     *      인덱스, 청크 크기, rewriteBatchedStatements, fetchSize.
     *      대부분의 "느린 배치"는 병렬화 이전에 여기서 해결됩니다.
     *      62.4초를 30초로 줄이는 인덱스 하나가 8스레드보다 쌉니다.
     *
     *   2. 그래도 부족하면 로컬 파티셔닝.
     *      멀티스레드 Step 보다 빠르고(5.6배 vs 3.3배) 재시작까지 됩니다.
     *      멀티스레드 Step 은 시나리오 B 같은 I/O 대기형에만 고려하십시오.
     *
     *   3. 한 대로 안 되면 원격 파티셔닝.
     *      운영 복잡도가 급격히 올라가므로 정말 필요할 때만.
     * ─────────────────────────────────────────────────────────────────
     */
}
