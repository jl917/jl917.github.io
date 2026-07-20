package com.example.batch.step13;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Step 13 — 연습문제 (6문제)
 *
 * 정답은 Solution.java. 먼저 직접 풀어 보십시오.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 공통 준비
 *
 * 모든 측정 전에 반드시 초기화하십시오. 빼먹으면 이전 실행 결과가 남아
 * 누락을 발견할 수 없습니다.
 *
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
 *
 * 검증은 항상 이 쿼리로 합니다. distinct_orders 가 70000 이어야 정상입니다.
 *
 *   SELECT COUNT(*) rows_written, COUNT(DISTINCT order_id) distinct_orders
 *   FROM settlement;
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Exercise {

    // =====================================================================
    // 문제 1. 스레드 안전하지 않은 Reader 를 두 가지 방법으로 고치기
    //
    // 아래 BrokenListReader 는 13-3 의 NaiveOrderReader 와 같은 결함을
    // 가지고 있습니다.
    //
    // (a) 이 Reader 가 왜 스레드 안전하지 않은지 한 문장으로 설명하십시오.
    // (b) 두 가지 방법으로 고치십시오.
    //     ① Reader 내부를 고쳐서 (힌트: AtomicInteger)
    //     ② Reader 는 그대로 두고 바깥에서 감싸서 (힌트: Step 13-4)
    // (c) 두 방법의 성능을 각각 측정해 비교하십시오. 어느 쪽이 빠릅니까?
    //     그리고 그 차이가 나는 이유는 무엇입니까?
    // =====================================================================

    public static class BrokenListReader implements ItemReader<Order> {

        private final List<Order> orders;
        private int index = 0;

        public BrokenListReader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public Order read() {
            if (index >= orders.size()) {
                return null;
            }
            return orders.get(index++);
        }
    }

    // (a) 왜 스레드 안전하지 않은가?
    // 여기에 작성:
    //

    /** ① AtomicInteger 로 인덱스를 원자화한 버전. */
    public static class FixedByAtomicReader implements ItemReader<Order> {

        private final List<Order> orders;
        // 여기에 작성: 인덱스 필드를 원자적으로 바꾸십시오
        //

        public FixedByAtomicReader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public Order read() {
            // 여기에 작성:
            //
            return null;
        }
    }

    // ② SynchronizedItemStreamReader 로 감싸는 방법
    // 여기에 작성: (빈 정의 형태로)
    //

    // (c) 측정 결과
    //     ① AtomicInteger  : ____초
    //     ② Synchronized   : ____초
    //     더 빠른 쪽과 그 이유:
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 2. SynchronizedItemStreamReader 로 감싸도 여전히 틀리는 경우
    //
    // ⚠️ 이 스텝에서 가장 어려운 문제입니다.
    //
    // 아래 RunningTotalProcessor 를 문제 1 에서 고친 Reader 와 함께
    // 멀티스레드 Step(스레드 4개)에 넣고 돌리십시오.
    //
    // (a) Reader 를 완벽히 동기화했는데도 runningTotal 이 매 실행마다
    //     다르게 나옵니다. 왜입니까?
    // (b) settlement 테이블의 행 수는 70,000 이 맞습니까? 그렇다면
    //     "무엇이" 틀린 것입니까?
    // (c) 이 Processor 를 올바르게 고치십시오. 힌트: 두 갈래의 답이 있습니다.
    //     하나는 "누적을 스레드 안전하게 만드는" 것이고,
    //     다른 하나는 "애초에 누적하지 않는" 것입니다. 어느 쪽이 정답입니까?
    // =====================================================================

    public static class RunningTotalProcessor implements ItemProcessor<Order, Settlement> {

        private BigDecimal runningTotal = BigDecimal.ZERO;   // ← 공유 가변 상태

        @Override
        public Settlement process(Order order) {
            runningTotal = runningTotal.add(order.amount());
            // ... Settlement 변환 (Practice 의 SettlementProcessor 와 동일)
            return null;
        }

        public BigDecimal getRunningTotal() {
            return runningTotal;
        }
    }

    // (a) 왜 매번 다른가?
    // 여기에 작성:
    //

    // (b) settlement 행 수는 맞는가? 그렇다면 무엇이 틀렸는가?
    // 여기에 작성:
    //

    // (c) 올바른 수정
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 3. customer_id 기준 Partitioner 작성하기 (등급 스큐 고려)
    //
    // order_id 대신 customer_id 로 나누는 Partitioner 를 작성하십시오.
    //
    // 참고 — 시드 데이터의 규칙:
    //   customer_id = ((order_id - 1) % 1000) + 1     (고객 1,000명)
    //   등급        = customer_id % 4                  (BRONZE/SILVER/GOLD/VIP)
    //   따라서 고객 한 명당 주문은 정확히 100건, 그중 COMPLETED 는 70건.
    //
    // (a) customer_id 를 gridSize 개의 "연속 범위"로 나누는 Partitioner 를
    //     작성하십시오. (1~250, 251~500, ...)
    // (b) gridSize=4 로 돌린 뒤, 파티션별 등급 분포를 아래 쿼리로 확인하십시오.
    //
    //     SELECT s.customer_id % 4 AS grade_key, COUNT(*)
    //     FROM settlement s WHERE s.customer_id BETWEEN 1 AND 250
    //     GROUP BY grade_key;
    //
    //     등급이 파티션마다 고르게 섞여 있습니까? 아니라면 왜입니까?
    // (c) 등급이 고르게 섞이도록 분할 방식을 바꾸십시오.
    //     힌트: 범위(range) 분할 대신 해시(hash) 분할.
    // (d) 범위 분할과 해시 분할은 각각 어떤 상황에 적합합니까?
    // =====================================================================

    public static class CustomerIdPartitioner implements Partitioner {

        private final JdbcTemplate jdbcTemplate;

        public CustomerIdPartitioner(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @Override
        public Map<String, ExecutionContext> partition(int gridSize) {
            // 여기에 작성: (a) 연속 범위 분할
            //
            return null;
        }
    }

    // (b) 등급 분포 확인 결과와 그 이유
    // 여기에 작성:
    //

    // (c) 해시 분할 버전
    // 여기에 작성:
    //

    // (d) 범위 분할 vs 해시 분할의 적합한 상황
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 4. @StepScope 를 빼면 어떤 예외가 어느 시점에 나는가
    //
    // Practice.java 의 Partitioning.partitionedOrderReader 에서
    // @StepScope 한 줄만 주석 처리하고 실행하십시오.
    //
    // (a) 예외를 먼저 "예측"해 적으십시오. 실행하기 전에 적어야 의미가 있습니다.
    //     - 예외 클래스명:
    //     - 발생 시점: (애플리케이션 기동 중 / Job 실행 중 / 청크 처리 중)
    // (b) 실제로 실행해 확인하고, 예측과 다르면 왜 틀렸는지 적으십시오.
    // (c) 이 실패는 "시끄러운 실패"입니까 "조용한 실패"입니까?
    //     그것이 왜 다행인지 13-8 의 함정 블록과 연결해 설명하십시오.
    // =====================================================================

    // (a) 예측
    // 여기에 작성:
    //

    // (b) 실제 결과
    // 여기에 작성:
    //

    // (c) 시끄러운 실패 vs 조용한 실패
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 5. 커넥션 풀 20 에서 안전한 최대 gridSize 계산하기
    //
    // application.yml 의 설정은 maximum-pool-size: 20 입니다.
    //
    // (a) 동시에 도는 워커 하나가 커넥션을 몇 개나 필요로 합니까?
    //     (힌트: Reader 가 하나, 그리고 또 하나가 더 필요합니다. 무엇입니까?)
    // (b) 마스터 Step 과 Partitioner 자신도 커넥션을 씁니다. 몇 개를
    //     여유로 남겨야 합니까?
    // (c) 안전한 최대 동시 워커 수를 계산하고 공식으로 정리하십시오.
    // (d) 계산한 값을 13-11 의 실측표와 대조하십시오. 맞습니까?
    //
    // ⚠️ 답만 쓰지 마십시오. 근거를 실제 로그로 만들어야 합니다.
    //    application.yml 에 아래를 추가하면 30초마다 풀 상태가 찍힙니다.
    //      logging.level.com.zaxxer.hikari: DEBUG
    //    다음과 같은 줄을 찾으십시오.
    //      HikariPool - Pool stats (total=20, active=16, idle=4, waiting=12)
    //    waiting 이 0 보다 크면 이미 포화된 것입니다.
    // =====================================================================

    // (a) 워커 하나당 커넥션 수
    // 여기에 작성:
    //

    // (b) 여유분
    // 여기에 작성:
    //

    // (c) 공식과 계산
    // 여기에 작성:
    //

    // (d) 실측표와의 대조
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 6. 멀티스레드 Step 과 파티셔닝 중 무엇을 쓸 것인가 (4개 시나리오)
    //
    // 각 시나리오에 대해 ① 어느 방식을 쓸지 ② 그 이유 ③ 주의점을 적으십시오.
    //
    // 시나리오 A ─ 100만 행을 읽어 단순 계산 후 다른 테이블에 쓴다.
    //   Reader 는 JdbcPagingItemReader. 재시작이 반드시 되어야 한다.
    //
    // 시나리오 B ─ 5만 건 각각에 대해 외부 결제사 API 를 호출한다.
    //   API 응답이 건당 평균 200ms 걸린다. CPU 는 거의 안 쓴다.
    //
    // 시나리오 C ─ 하나의 거대한 CSV 파일(2GB)을 읽어 DB 에 적재한다.
    //   FlatFileItemReader 를 쓴다.
    //
    // 시나리오 D ─ 정산 배치를 8대의 서버에 나눠서 돌리고 싶다.
    //   단일 서버로는 시간 안에 끝나지 않는다.
    // =====================================================================

    // 시나리오 A
    // 여기에 작성:
    //

    // 시나리오 B
    // 여기에 작성:
    //

    // 시나리오 C
    // 여기에 작성:
    //

    // 시나리오 D
    // 여기에 작성:
    //
}
