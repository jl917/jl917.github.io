package com.example.batch.step06;

/*
 * ============================================================================
 * Step 06 — ItemReader / 정답과 해설
 * ============================================================================
 * 문제를 직접 풀어 본 뒤에 여세요.
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.example.batch.step06.Practice.ExternalOrder;
import com.example.batch.step06.Practice.Order;

@Configuration
public class Solution {

    @Value("${q2.page-size:1000}")
    private int pageSize;

    // ========================================================================
    // 정답 1. BeanPropertyRowMapper → DataClassRowMapper
    // ========================================================================
    //
    // ① 발생하는 예외
    //
    //    org.springframework.beans.BeanInstantiationException:
    //        Failed to instantiate [com.example.batch.step06.Practice$Order]:
    //        No default constructor found
    //      at org.springframework.beans.BeanUtils.instantiateClass(BeanUtils.java:172)
    //      at org.springframework.jdbc.core.BeanPropertyRowMapper.mapRow(...)
    //
    // ② 왜 record 에 안 되는가 — 이유 두 가지
    //
    //    (1) 생성 방식이 다릅니다.
    //        BeanPropertyRowMapper 는 "기본 생성자로 빈 객체를 만든 뒤 setter 로 채운다"는
    //        자바빈 규약을 전제합니다. record 에는 기본 생성자가 없습니다.
    //        (record 는 모든 컴포넌트를 받는 정규 생성자만 가집니다.)
    //
    //    (2) 접근자 이름이 다릅니다.
    //        record 의 접근자는 getAmount() 가 아니라 amount() 입니다.
    //        setter 는 아예 없습니다(record 는 불변입니다).
    //
    //    DataClassRowMapper 는 반대로 "생성자에 값을 넣어 한 번에 만든다"는 방식이라
    //    record 와 정확히 맞아떨어집니다. Spring 5.3 부터 있습니다.
    //
    // ③ ★ BeanPropertyRowMapper 가 조용히 틀리는 경우 — 이게 진짜 교훈입니다
    //
    //    record 를 쓰면 BeanInstantiationException 으로 시끄럽게 죽습니다.
    //    이건 운이 좋은 경우입니다. 문제는 이런 클래스입니다.
    //
    //      @Getter                       // ← @Setter 를 빠뜨렸습니다
    //      @NoArgsConstructor
    //      public class OrderDto {
    //          private Long orderId;
    //          private BigDecimal amount;
    //      }
    //
    //    BeanPropertyRowMapper 는 기본 생성자로 객체를 만드는 데 성공하고,
    //    채울 setter 를 못 찾으면 그냥 건너뜁니다. 예외가 없습니다.
    //
    //      DEBUG --- o.s.jdbc.core.BeanPropertyRowMapper : No property found for column 'amount'
    //
    //    결과: 모든 필드가 null 인 객체 70,000개.
    //          READ_COUNT 70,000, STATUS COMPLETED, 예외 0건.
    //
    //    운이 좋으면 processor 에서 NullPointerException 이 나서 알아차립니다.
    //    운이 나쁘면 — 금액 필드가 BigDecimal 이 아니라 primitive int 라면 —
    //    0 으로 초기화되어 **금액이 전부 0원인 정산서**가 조용히 만들어집니다.
    //
    //    그래서 이 코스는 record + DataClassRowMapper 를 기본으로 씁니다.
    //    생성자 매핑은 컬럼이 안 맞으면 애초에 인스턴스를 못 만들어 실패하므로,
    //    구조적으로 조용히 틀릴 여지가 없습니다.

    @Bean
    public JdbcPagingItemReader<Order> q1Reader(DataSource dataSource) {

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        provider.setSortKeys(Map.of(
                "order_id", org.springframework.batch.item.database.Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("q1Reader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(1000)
                .rowMapper(new DataClassRowMapper<>(Order.class))   // ✅
                .build();
    }

    public static final String Q1_EXCEPTION = "org.springframework.beans.BeanInstantiationException";
    public static final String Q1_SILENT_CASE =
            "기본 생성자는 있는데 setter 가 없는 클래스(@Getter 만 붙인 Lombok DTO 등). "
            + "객체 생성은 성공하고 필드 주입만 조용히 건너뛰어 전 필드 null 인 객체가 만들어진다. "
            + "primitive 필드라면 0 으로 채워져 '금액 0원 정산서'가 예외 없이 생성된다.";

    // ========================================================================
    // 정답 2. ★ pageSize=1000 → 70,000건 (유실 0)
    //           pageSize=1024 → 65,212건 (유실 4,788)
    // ========================================================================
    //
    // 대부분 "유실이 몇 건인가"를 물었으니 양쪽 다 유실이 있으리라 예상했을 겁니다.
    // 그런데 pageSize=1000 은 **유실이 전혀 없습니다.** 이게 이 문제의 핵심입니다.
    //
    // ── 데이터 구조 ──────────────────────────────────────────────────────
    //
    //   +-----------+---------------+--------------+--------------+
    //   | completed | distinct_cust | min_per_cust | max_per_cust |
    //   +-----------+---------------+--------------+--------------+
    //   |     70000 |           700 |          100 |          100 |
    //   +-----------+---------------+--------------+--------------+
    //
    //   COMPLETED 주문의 customer_id 는 700종이고, 값 하나당 **정확히 100건**입니다.
    //   편차가 없습니다(min = max = 100).
    //
    //   왜 700종인가:
    //     customer_id = ((n-1) % 1000) + 1 이고, status 는 n % 10 으로 정해집니다.
    //     10 이 1000 을 나누므로, 같은 customer_id 에 속한 n 들은 **n % 10 이 전부 같습니다.**
    //     즉 어떤 고객은 주문이 전부 COMPLETED 이고, 어떤 고객은 하나도 없습니다.
    //     COMPLETED 인 고객은 1000 × 0.7 = 700명, 각자 100건씩 → 70,000건.
    //
    // ── 왜 pageSize=1000 은 멀쩡한가 ─────────────────────────────────────
    //
    //   나머지 페이지 SQL 은 WHERE customer_id > ? 입니다.
    //   유실은 "페이지 경계가 뭉치 한가운데를 자를 때"만 발생합니다.
    //
    //   뭉치가 정확히 100건씩이고 페이지가 1,000건이면,
    //   페이지 경계는 항상 10번째 뭉치가 **끝나는 지점**에 정확히 떨어집니다.
    //
    //     페이지 1 = 고객 1~10 의 주문 1,000건 (뭉치 10개가 딱 맞게 들어감)
    //     마지막 행의 customer_id = 10, 그 값의 100건을 전부 읽은 상태
    //     페이지 2 = WHERE customer_id > 10  → 고객 11 부터. 잘려 나간 것 없음 ✅
    //
    //   1000 은 100 의 배수이므로 경계가 절대 뭉치를 자르지 않습니다.
    //
    // ── 왜 pageSize=1024 는 무너지는가 ───────────────────────────────────
    //
    //   1024 는 100 의 배수가 아닙니다. 경계가 뭉치 한가운데에 떨어집니다.
    //
    //     페이지 1 = 1,024건 = 고객 1~10 (1,000건) + 고객 11 의 24건
    //     마지막 행의 customer_id = 11, 그런데 24건만 읽음
    //     페이지 2 = WHERE customer_id > 11  → 고객 11 의 나머지 76건 증발 ❌
    //
    //   페이지마다 이런 식으로 잘려 나가 최종 65,212건, 유실 4,788건입니다.
    //
    // ── pageSize 별 실측 ─────────────────────────────────────────────────
    //
    //   | pageSize | 100의 배수? | 읽은 건수 | 유실   |
    //   |----------|-------------|-----------|--------|
    //   |      100 | ✅          |    70,000 |      0 |
    //   |      250 | ❌          |    58,350 | 11,650 |
    //   |      500 | ✅          |    70,000 |      0 |
    //   |      750 | ❌          |    65,650 |  4,350 |
    //   |     1000 | ✅          |    70,000 |      0 |
    //   |     1024 | ❌          |    65,212 |  4,788 |
    //   |     1500 | ✅          |    70,000 |      0 |
    //   |     3000 | ✅          |    70,000 |      0 |
    //
    // ── ★ 교훈 ───────────────────────────────────────────────────────────
    //
    //   이 결함은 **잠복합니다.**
    //
    //   customer_id 로 정렬하는 이 배치는 pageSize=1000 으로 몇 년이고
    //   완벽하게 돌 수 있습니다. 매일 70,000건, 매일 COMPLETED, 아무 문제 없습니다.
    //   정렬 키가 유니크하지 않다는 사실은 아무 증상도 만들지 않습니다.
    //
    //   그러다 어느 날 누군가 성능 튜닝을 합니다.
    //
    //     - pageSize(1000)
    //     + pageSize(1024)     // 2의 거듭제곱이 더 효율적일 것 같아서
    //
    //   이 diff 를 보고 "데이터 4,788건이 사라지겠군"이라고 생각할 리뷰어는 없습니다.
    //   테스트도 통과합니다 — 테스트 데이터는 보통 수십 건이라 페이지가 하나뿐이고,
    //   페이지가 하나면 경계가 없어서 유실도 없습니다.
    //
    //   결론:
    //     "지금 잘 돌아간다"는 정렬 키가 유니크하다는 증거가 아닙니다.
    //     유일성은 우연(pageSize 와 뭉치 크기의 산술적 관계)이 아니라
    //     **설계(정렬 키에 PK 를 포함)** 로 보장해야 합니다.
    //
    //     그리고 어떤 경우든 정답 7 의 검증 Step 을 붙이세요.
    //     설계로 막고, 검증으로 한 번 더 막는 겁니다.

    public static final int Q2_READ_AT_1000 = 70000;
    public static final int Q2_READ_AT_1024 = 65212;
    public static final String Q2_WHY =
            "COMPLETED 의 customer_id 는 700종이고 값 하나당 정확히 100건. "
            + "1000 은 100 의 배수라 페이지 경계가 항상 뭉치 경계와 일치해 유실 0. "
            + "1024 는 배수가 아니라 경계가 뭉치를 잘라 WHERE customer_id > ? 가 나머지를 스킵.";
    public static final String Q2_LESSON =
            "유니크하지 않은 정렬 키는 '지금 우연히 동작하는' 상태일 수 있다. "
            + "pageSize 를 바꾸는 무관해 보이는 한 줄이 잠복한 결함을 터뜨린다. "
            + "코드 리뷰로도 소량 데이터 테스트로도 잡히지 않는다.";

    // ========================================================================
    // 정답 3. LinkedHashMap 으로 (customer_id, order_id) 복합 정렬 키
    // ========================================================================
    //
    // ── 왜 LinkedHashMap 인가 ────────────────────────────────────────────
    //
    //   Map.of() 는 순회 순서를 보장하지 않습니다. 그냥 "보장하지 않는다" 정도가
    //   아니라, java.util.Map.of() 의 API 문서는 이렇게 못 박습니다.
    //
    //     "The iteration order of mappings is unspecified and is subject to change."
    //
    //   그리고 실제 구현(ImmutableCollections.MapN)은 **JVM 마다 다른 SALT 값**으로
    //   순회 시작점을 무작위화합니다. 즉 같은 코드, 같은 데이터인데도
    //   **JVM 을 재시작할 때마다 순서가 달라집니다.**
    //
    //   sortKeys 의 순서는 그대로 ORDER BY 절의 컬럼 순서가 됩니다.
    //
    //     오늘 기동:  ORDER BY customer_id ASC, order_id ASC   ✅ 정상
    //     내일 기동:  ORDER BY order_id ASC, customer_id ASC   ❌ 업무 요구사항 위반
    //
    //   두 번째 경우도 order_id 가 유니크하니 데이터 유실은 없습니다.
    //   하지만 "고객별로 묶어서 정산한다"는 업무 요구사항이 깨집니다.
    //   그리고 이건 재현이 안 되는 버그입니다 — 재시작하면 고쳐졌다가 또 재발합니다.
    //
    //   순서가 의미를 갖는 Map 에는 반드시 LinkedHashMap 을 쓰세요.
    //
    // ── 생성되는 SQL ─────────────────────────────────────────────────────
    //
    //   첫 페이지:
    //     SELECT ... FROM orders WHERE status = ?
    //     ORDER BY customer_id ASC, order_id ASC LIMIT 1000
    //
    //   나머지 페이지:
    //     SELECT ... FROM orders WHERE (status = ?)
    //       AND ((customer_id > ?) OR (customer_id = ? AND order_id > ?))
    //     ORDER BY customer_id ASC, order_id ASC LIMIT 1000
    //            ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //            "고객이 같으면 order_id 로 이어서" — 뭉치를 안 건너뜁니다
    //
    // ── 검증 ─────────────────────────────────────────────────────────────
    //
    //   | pageSize | 읽은 건수 |
    //   |----------|-----------|
    //   |      250 |    70,000 |
    //   |     1000 |    70,000 |
    //   |     1024 |    70,000 |
    //   |     3000 |    70,000 |
    //
    //   pageSize 와 무관하게 항상 70,000건입니다. 이제 튜닝해도 안전합니다.
    //
    // ── 관용구 ───────────────────────────────────────────────────────────
    //
    //   **어떤 컬럼으로 정렬하든 마지막 정렬 키로 PK 를 붙인다.**
    //
    //   비용은 사실상 없습니다. InnoDB 의 세컨더리 인덱스 리프에는 이미 PK 가
    //   들어 있으므로, (customer_id, order_id) 정렬은 idx_orders_customer 인덱스만으로
    //   추가 정렬 없이 처리됩니다. 얻는 것에 비해 공짜에 가깝습니다.

    @Bean
    public JdbcPagingItemReader<Order> q3Reader(DataSource dataSource) {

        // ✅ 순서가 의미를 갖는 Map 이므로 LinkedHashMap
        Map<String, org.springframework.batch.item.database.Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put("customer_id", org.springframework.batch.item.database.Order.ASCENDING);
        sortKeys.put("order_id", org.springframework.batch.item.database.Order.ASCENDING);

        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
        provider.setFromClause("FROM orders");
        provider.setWhereClause("status = :status");
        provider.setSortKeys(sortKeys);

        return new JdbcPagingItemReaderBuilder<Order>()
                .name("q3Reader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("status", "COMPLETED"))
                .pageSize(pageSize)
                .rowMapper(new DataClassRowMapper<>(Order.class))
                .build();
    }

    public static final String Q3_WHY_NOT_MAP_OF =
            "Map.of() 는 순회 순서를 명시적으로 보장하지 않고, 구현이 JVM 별 SALT 로 "
            + "순서를 무작위화한다. sortKeys 순서 = ORDER BY 컬럼 순서이므로 "
            + "JVM 재시작마다 정렬 기준이 바뀌는 재현 불가능한 버그가 된다.";

    // ========================================================================
    // 정답 4. 예외가 납니다 — 그리고 그게 다행입니다.
    // ========================================================================
    //
    // ① 정답: EXCEPTION
    //
    // ② 실제 관찰
    //
    //    JdbcCursorItemReader 는 하나의 ResultSet 을 열어 두고 read() 마다
    //    rs.next() 를 호출합니다. ResultSet 은 스레드 안전하지 않습니다.
    //    스레드 4개가 동시에 next() 를 부르면 커서 위치가 깨집니다.
    //
    //    실행할 때마다 다른 예외가, 다른 시점에 납니다.
    //
    //      java.sql.SQLException: Operation not allowed after ResultSet closed
    //        at com.mysql.cj.jdbc.result.ResultSetImpl.checkClosed(ResultSetImpl.java:...)
    //
    //    또는
    //
    //      java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 5
    //        at com.mysql.cj.protocol.a.result.ResultsetRowsStatic.next(...)
    //
    //    또는
    //
    //      org.springframework.batch.item.ReaderNotOpenException: Reader must be open before it can be read
    //
    //    "매번 다른 예외가 무작위 시점에 난다"는 것 자체가 동시성 버그의 징후입니다.
    //    한 번 돌려 보고 판단하지 말고 여러 번 돌려 보라고 한 이유가 이것입니다.
    //
    //    ★ 이건 그나마 다행인 경우입니다.
    //      이 코스가 계속 말하는 기준으로 보면, 시끄럽게 실패하는 건 좋은 실패입니다.
    //      정답 2 처럼 조용히 4,788건을 잃는 쪽이 훨씬 위험합니다.
    //
    // ③ 제대로 고치는 방법
    //
    //    (1) 리더를 JdbcPagingItemReader 로 바꿉니다.
    //        페이징 리더는 페이지를 읽는 순간에만 커넥션을 잡고 반납하므로
    //        여러 스레드가 각자 자기 페이지를 읽을 수 있습니다.
    //
    //    (2) 정렬 키를 유니크하게 둡니다. (정답 3)
    //        멀티스레드에서는 이게 더 중요합니다. 스레드들이 페이지를 나눠 가지므로
    //        경계가 훨씬 많아지고, 유실도 그만큼 커집니다.
    //
    //    (3) saveState(false) 를 검토합니다.
    //        멀티스레드 Step 에서는 "몇 번째까지 읽었다"는 상태가 의미를 잃습니다
    //        (스레드마다 진도가 다르므로). Spring Batch 는 이 경우 저장된 상태로
    //        재시작하면 잘못된 위치에서 시작할 수 있다고 경고합니다.
    //        재시작 대신 "실패하면 처음부터 다시" 전략을 택하고 saveState(false) 를
    //        주는 편이 정직합니다.
    //
    //    (4) 커넥션 풀 크기를 확인합니다.
    //        스레드 4개 + 메타데이터용이면 최소 5개가 동시에 필요합니다.
    //        application.yml 의 hikari.maximum-pool-size: 20 이 그래서 있습니다.
    //
    //    자세한 내용은 Step 13 에서 다룹니다.

    public static final String Q4_PREDICTION = "EXCEPTION";
    public static final String Q4_OBSERVED =
            "실행할 때마다 다른 예외가 다른 시점에 발생. SQLException: Operation not allowed "
            + "after ResultSet closed / ArrayIndexOutOfBoundsException(드라이버 내부) / "
            + "ReaderNotOpenException. ResultSet 이 스레드 안전하지 않기 때문.";
    public static final String Q4_FIX =
            "JdbcPagingItemReader 로 교체 + 유니크한 복합 정렬 키 + saveState(false) 검토 "
            + "+ 커넥션 풀이 스레드 수를 감당하는지 확인";

    // ========================================================================
    // 정답 5. OFFSET 0.138초 vs 키셋 0.004초 — 약 34배
    // ========================================================================
    //
    //   [OFFSET 방식]
    //     LIMIT 1000 OFFSET 0      → 1000 rows in set (0.004 sec)
    //     LIMIT 1000 OFFSET 35000  → 1000 rows in set (0.071 sec)
    //     LIMIT 1000 OFFSET 69000  → 1000 rows in set (0.138 sec)
    //
    //   [키셋 방식]
    //     order_id > 0      → 1000 rows in set (0.004 sec)
    //     order_id > 50000  → 1000 rows in set (0.005 sec)
    //     order_id > 98570  → 1000 rows in set (0.004 sec)
    //
    //   OFFSET 은 오프셋에 정비례해 느려집니다(0.004 → 0.071 → 0.138).
    //   "건너뛴다"는 표현이 오해를 부르는데, MySQL 은 OFFSET 만큼의 행을
    //   **실제로 읽어서 버립니다.** OFFSET 69000 은 69,000행을 읽고 버린 뒤
    //   1,000행을 반환합니다.
    //
    //   키셋은 어느 페이지든 일정합니다. WHERE order_id > 98570 은 인덱스에서
    //   시작점을 O(log n) 에 찾아 거기서부터 1,000행만 읽습니다.
    //
    // ④ 700만 건이면
    //
    //   OFFSET 은 페이지당 비용이 오프셋에 비례하므로,
    //   **전체 배치의 비용은 페이지 수의 제곱에 비례**합니다.
    //
    //     70,000건  =   70페이지 → 마지막 페이지 0.138초
    //     7,000,000건 = 7,000페이지 → 마지막 페이지는 오프셋이 100배 → 약 13.8초
    //
    //   마지막 페이지 하나가 13.8초입니다. 그리고 그 앞의 6,999페이지도 전부
    //   비례해서 느립니다. 전체를 적분하면 대략 100 × 100 = 10,000배 —
    //   70,000건에서 몇 초 걸리던 게 700만 건에서는 몇 시간이 됩니다.
    //
    //   키셋은 페이지당 비용이 일정하므로 **전체 비용이 데이터 양에 선형**입니다.
    //   100배 데이터 → 100배 시간. 예측 가능합니다.
    //
    //   이게 Spring Batch 의 JdbcPagingItemReader 가 OFFSET 대신 키셋을 쓰는
    //   이유이고, 그 대가로 "정렬 키가 유니크해야 한다"는 조건을 요구하는 이유입니다.
    //   본문 6-6 의 사고는 그 대가를 안 치른 결과입니다.
    //
    //   참고: JpaPagingItemReader 는 여전히 OFFSET(limit ?,?) 을 씁니다.
    //         대용량 배치에서 JPA 리더를 피하라는 권고의 근거 중 하나입니다.

    public static final double Q5_OFFSET_FIRST = 0.004;
    public static final double Q5_OFFSET_LAST = 0.138;
    public static final double Q5_KEYSET_LAST = 0.004;
    public static final String Q5_AT_SCALE =
            "OFFSET 은 전체 비용이 페이지 수의 제곱에 비례. 700만 건이면 마지막 페이지만 "
            + "약 13.8초, 전체로는 약 10,000배. 키셋은 데이터 양에 선형이라 100배 데이터 = 100배 시간.";

    // ========================================================================
    // 정답 6. linesToSkip(3) 이 아니라 comments("#") + linesToSkip(1)
    // ========================================================================
    //
    // ② 고치기 전 읽히는 건수: 0건 (첫 아이템에서 예외로 중단)
    // ③ 실패 모드: EXCEPTION
    //
    //    파일 구조:
    //      1: # 외부 정산 파일 v3
    //      2: # 2025-07-01 생성 — 컬럼 순서 변경 없음
    //      3: order_id,customer_id,amount,ordered_at     ← 헤더
    //      4~6: 데이터 3줄
    //
    //    linesToSkip(2) 는 1·2줄만 건너뜁니다. 그래서 3줄(헤더)을 데이터로 파싱합니다.
    //
    //      org.springframework.batch.item.file.FlatFileParseException:
    //          Parsing error at line: 3 in resource=[file [/tmp/q6-orders.csv]],
    //          input=[order_id,customer_id,amount,ordered_at]
    //        Caused by: java.lang.NumberFormatException: For input string: "order_id"
    //
    //    ★ 이번엔 시끄럽게 실패했습니다. 다행입니다.
    //
    //    하지만 **반대 방향으로 틀리면 조용합니다.** 이게 진짜 위험한 쪽입니다.
    //
    //      주석을 아예 없앤 파일(헤더 1줄 + 데이터)에 linesToSkip(2) 를 적용하면?
    //      → 헤더와 **첫 데이터 행**을 건너뜁니다.
    //      → 예외 없이, 경고 없이, 데이터 한 줄이 사라진 채 COMPLETED.
    //
    //      정산 파일에서 첫 줄이 사라지는 건 그냥 "한 건 누락"입니다.
    //      매일 한 건씩, 아무도 모르게.
    //
    // ④ 왜 linesToSkip(3) 이 정답이 아닌가
    //
    //    숫자를 3으로 바꾸면 지금 이 파일은 읽힙니다. 그런데 그 해법은
    //    **파일 형식이 또 바뀌면 또 깨집니다.** 그리고 파일을 만드는 쪽은
    //    보통 다른 팀, 다른 시스템입니다. 주석 한 줄 추가는 그쪽에서
    //    "아무 영향 없는 변경"으로 보입니다.
    //
    //    줄 수에 의존하는 설정은 본질적으로 깨지기 쉽습니다.
    //    **내용으로 판별하세요.**
    //
    //      .comments("#")      // 주석은 몇 줄이든, 어디에 있든 무시
    //      .linesToSkip(1)     // 헤더 한 줄만
    //
    //    comments() 는 파일 전체에서 해당 접두사로 시작하는 줄을 건너뜁니다.
    //    주석이 0줄이든 10줄이든, 중간에 끼어 있든 동작합니다.
    //
    //    더 견고하게 하려면 헤더를 검증까지 합니다.
    //
    //      .skippedLinesCallback(line -> {
    //          if (!line.equals("order_id,customer_id,amount,ordered_at")) {
    //              throw new IllegalStateException("헤더 형식이 바뀌었습니다: " + line);
    //          }
    //      })
    //
    //    컬럼 순서가 바뀌는 사고까지 잡힙니다. 컬럼 순서가 바뀌면
    //    DelimitedLineTokenizer 는 위치로 매핑하므로 **customer_id 와 amount 가
    //    뒤바뀐 채 조용히 파싱됩니다.** 타입이 둘 다 숫자라 예외도 안 납니다.

    @Bean
    public FlatFileItemReader<ExternalOrder> q6Reader() {

        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("orderId", "customerId", "amount", "orderedAt");
        tokenizer.setStrict(true);

        DefaultLineMapper<ExternalOrder> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fs -> new ExternalOrder(
                fs.readLong("orderId"),
                fs.readInt("customerId"),
                fs.readBigDecimal("amount"),
                LocalDateTime.parse(fs.readString("orderedAt").replace(' ', 'T'))));

        return new FlatFileItemReaderBuilder<ExternalOrder>()
                .name("q6Reader")
                .resource(new FileSystemResource("/tmp/q6-orders.csv"))
                .encoding("UTF-8")
                .comments("#")        // ✅ 주석은 몇 줄이든 무시
                .linesToSkip(1)       // ✅ 헤더 한 줄만
                .skippedLinesCallback(line -> {
                    // 헤더 형식까지 검증 — 컬럼 순서가 바뀌는 조용한 사고를 막습니다
                    if (!"order_id,customer_id,amount,ordered_at".equals(line.trim())) {
                        throw new IllegalStateException("CSV 헤더 형식이 바뀌었습니다: " + line);
                    }
                })
                .lineMapper(lineMapper)
                .build();
    }

    public static final int Q6_ROWS_BEFORE_FIX = 0;
    public static final String Q6_FAILURE_MODE = "EXCEPTION";

    // ========================================================================
    // 정답 7. 건수 + 금액을 모두 대조하고, 예외를 던져 Job 을 FAILED 로
    // ========================================================================
    //
    // ── ③ 이 함정이었습니다 ──────────────────────────────────────────────
    //
    //   contribution.setExitStatus(ExitStatus.FAILED) 만 호출하면
    //   **Step 의 종료 코드만 바뀌고 Job 은 COMPLETED 로 끝납니다.**
    //
    //     +-----------------+-----------+-----------+
    //     | STEP_NAME       | STATUS    | EXIT_CODE |
    //     +-----------------+-----------+-----------+
    //     | q7Step          | COMPLETED | FAILED    |   ← STATUS 는 COMPLETED!
    //     +-----------------+-----------+-----------+
    //     BATCH_JOB_EXECUTION.STATUS = COMPLETED
    //
    //   ExitStatus 는 "다음에 어느 Step 으로 갈지"를 정하는 흐름 제어용 값이지
    //   성공/실패 판정이 아닙니다(Step 10 에서 자세히 다룹니다).
    //
    //   Job 을 진짜로 실패시키려면 **예외를 던져야** 합니다.
    //   Tasklet 에서 던진 예외는 트랜잭션을 롤백시키고 StepExecution 의 STATUS 를
    //   FAILED 로, 나아가 JobExecution 의 STATUS 도 FAILED 로 만듭니다.
    //
    // ── ② 왜 금액까지 봐야 하는가 ────────────────────────────────────────
    //
    //   건수만 비교하면 "중복 처리 + 누락"이 동시에 일어난 경우를 놓칩니다.
    //
    //     정상: 주문 A, B, C → 정산 A, B, C          (3건, 합계 300원)
    //     사고: 주문 A, B, C → 정산 A, A, B          (3건, 합계 250원)
    //
    //   건수는 3 == 3 으로 통과하지만 C 가 누락되고 A 가 중복됐습니다.
    //   금액 합계를 대조하면 300 != 250 으로 잡힙니다.
    //
    //   실제로 이 코스에서는 settlement.order_id 에 UNIQUE 제약이 있어서
    //   중복 INSERT 는 DuplicateKeyException 으로 막힙니다. 하지만 그 제약이
    //   없는 테이블도 많고, 무엇보다 **검증은 제약에 의존하지 않아야** 합니다.
    //
    // ── 실행 결과 ────────────────────────────────────────────────────────
    //
    //   ./gradlew bootRun --args='--spring.batch.job.name=q7Job'
    //
    //   INFO  --- o.s.batch.core.job.SimpleStepHandler : Executing step: [brokenSortStep]
    //   INFO  --- o.s.batch.core.step.AbstractStep     : Step: [brokenSortStep] executed in 5s918ms
    //   INFO  --- o.s.batch.core.job.SimpleStepHandler : Executing step: [q7Step]
    //   INFO  --- c.e.b.step06.Solution                : 정산 검증 — 건수 70000/67207, 금액 3485250000.00/3346384710.00
    //   ERROR --- o.s.batch.core.step.AbstractStep     : Encountered an error executing step q7Step in job q7Job
    //
    //   java.lang.IllegalStateException: 정산 불일치 —
    //       건수: 대상 70000건, 처리 67207건 (누락 2793건) /
    //       금액: 대상 3485250000.00, 처리 3346384710.00 (차액 138865290.00)
    //
    //   INFO  --- o.s.b.c.l.s.TaskExecutorJobLauncher  : Job: [SimpleJob: [name=q7Job]] completed
    //         with the following parameters: [{}] and the following status: [FAILED] in 6s204ms
    //
    //   ★ 드디어 배치가 시끄럽게 실패합니다.
    //
    //     본문 6-6 에서 brokenSortJob 은 COMPLETED 로 끝났습니다.
    //     같은 리더, 같은 유실인데 검증 Step 하나를 붙였더니 FAILED 가 됩니다.
    //     정렬 키를 고치는 것(설계)과 검증 Step 을 붙이는 것(방어)은
    //     둘 중 하나를 고르는 게 아니라 **둘 다 해야 하는 일**입니다.
    //
    //     설계는 이번 실수를 막고, 검증은 다음 실수를 막습니다.

    static class VerificationTasklet implements Tasklet {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(VerificationTasklet.class);

        private final JdbcTemplate jdbc;

        VerificationTasklet(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {

            Integer expectedCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'", Integer.class);
            BigDecimal expectedAmount = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE status = 'COMPLETED'",
                    BigDecimal.class);

            Integer actualCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM settlement", Integer.class);
            BigDecimal actualAmount = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(gross_amount), 0) FROM settlement", BigDecimal.class);

            log.info("정산 검증 — 건수 {}/{}, 금액 {}/{}",
                    expectedCount, actualCount, expectedAmount, actualAmount);

            boolean countMismatch = !Objects.equals(expectedCount, actualCount);
            // BigDecimal 은 equals() 가 스케일까지 비교하므로 compareTo() 를 씁니다.
            // new BigDecimal("1.0").equals(new BigDecimal("1.00")) 은 false 입니다.
            boolean amountMismatch = expectedAmount == null || actualAmount == null
                    || expectedAmount.compareTo(actualAmount) != 0;

            if (countMismatch || amountMismatch) {
                // ★ 예외를 던져야 Job 이 FAILED 가 됩니다.
                //    contribution.setExitStatus(ExitStatus.FAILED) 로는 부족합니다.
                throw new IllegalStateException(
                        ("정산 불일치 — 건수: 대상 %d건, 처리 %d건 (누락 %d건) / "
                                + "금액: 대상 %s, 처리 %s (차액 %s)")
                                .formatted(expectedCount, actualCount,
                                        expectedCount - actualCount,
                                        expectedAmount, actualAmount,
                                        expectedAmount.subtract(actualAmount)));
            }

            log.info("정산 검증 통과 — {}건, {}", actualCount, actualAmount);
            return RepeatStatus.FINISHED;
        }
    }

    public static final String Q7_HOW_TO_FAIL_JOB =
            "Tasklet 에서 예외를 던져야 한다. contribution.setExitStatus(ExitStatus.FAILED) 는 "
            + "Step 의 EXIT_CODE 만 바꿀 뿐 STATUS 는 COMPLETED 로 남고 Job 도 COMPLETED 로 끝난다. "
            + "ExitStatus 는 흐름 제어용 값이지 성공/실패 판정이 아니다.";

    @Bean
    public Step q7Step(JobRepository jobRepository, PlatformTransactionManager txManager,
                       JdbcTemplate jdbcTemplate) {
        return new StepBuilder("q7Step", jobRepository)
                .tasklet(new VerificationTasklet(jdbcTemplate), txManager)
                .build();
    }

    @Bean
    public Job q7Job(JobRepository jobRepository, Step brokenSortStep, Step q7Step) {
        return new JobBuilder("q7Job", jobRepository)
                .start(brokenSortStep)
                .next(q7Step)
                .build();
    }
}
