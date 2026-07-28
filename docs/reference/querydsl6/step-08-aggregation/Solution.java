package com.example.shop.step08;

import com.example.shop.entity.OrderStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.querydsl.core.group.GroupBy.groupBy;
import static com.querydsl.core.group.GroupBy.list;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 08 — 집계와 그룹핑 : 연습문제 정답과 해설
 *
 * <p>답만 보지 말고 주석을 반드시 읽으십시오.
 * 이 스텝의 함정은 대부분 "숫자가 그럴듯하게 나오는데 틀린" 형태입니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    public record OrderDto(Long orderId, BigDecimal totalAmount) {}

    // =================================================================
    // 문제 1. 도시별 고객 수와 평균 포인트 (8행)
    // =================================================================
    @Test
    @DisplayName("A1. 도시별 고객 수와 평균 포인트")
    void a1_customersByCity() {
        NumberExpression<Long>   cnt = customer.count();
        NumberExpression<Double> avg = customer.points.avg();

        List<Tuple> rows = queryFactory
                .select(customer.city, cnt, avg)
                .from(customer)
                .groupBy(customer.city)
                .orderBy(cnt.desc(), customer.city.asc())
                .fetch();

        rows.forEach(t -> System.out.printf("%-4s %2d %10.2f%n",
                t.get(customer.city), t.get(cnt), t.get(avg)));

        assertThat(rows).hasSize(8);
        assertThat(rows.get(0).get(customer.city)).isEqualTo("서울");
        assertThat(rows.get(0).get(cnt)).isEqualTo(10L);

        // 생성 SQL
        //   select c1_0.city, count(c1_0.customer_id), avg(c1_0.points)
        //   from customers c1_0
        //   group by c1_0.city
        //   order by count(c1_0.customer_id) desc, c1_0.city
        //
        // ── 해설 ────────────────────────────────────────────────
        // 세 가지가 포인트입니다.
        //
        // 1) 표현식을 변수로 뽑았습니다.
        //    cnt 를 select 절과 orderBy 와 tuple.get() 세 곳에서 씁니다.
        //    매번 customer.count() 를 새로 만들어도 동작은 하지만
        //    한 곳만 실수로 고치면 조용히 null 이 나오거나 SQL 이 달라집니다.
        //
        // 2) avg 는 Double 로 받아야 합니다.
        //    points 는 Integer 인데 avg 는 Double 입니다.
        //    NumberExpression<Integer> avg = customer.points.avg(); 는 컴파일 에러입니다.
        //    이 컴파일 에러가 나는 것이 좋은 일입니다. 타입이 다르다고 알려 준 것이니까요.
        //
        // 3) orderBy 에 집계 표현식을 그대로 넣을 수 있습니다.
        //    SQL 로는 order by count(...) desc 가 됩니다.
        //    MySQL 은 select 별칭으로도 정렬할 수 있지만 QueryDSL 에는 별칭 개념이 없으므로
        //    표현식을 그대로 재사용합니다. 오히려 이쪽이 안전합니다.
        //
        // 도시 8종은 MySQL8 코스 Step 06 의 6-4 절과 같습니다
        // (서울 10 / 부산 5 / 인천 4 / 대구 3 / 광주 2 / 대전 2 / 수원 2 / 울산 2).
    }

    // =================================================================
    // 문제 2. 전체 고객 수 vs 전화번호 있는 고객 수 (30 / 27)
    // =================================================================
    @Test
    @DisplayName("A2. count(*) 와 count(col) 의 차이")
    void a2_countWithNull() {
        NumberExpression<Long> total = customer.count();
        NumberExpression<Long> withPhone = customer.phone.count();

        Tuple t = queryFactory
                .select(total, withPhone)
                .from(customer)
                .fetchOne();

        assertThat(t.get(total)).isEqualTo(30L);
        assertThat(t.get(withPhone)).isEqualTo(27L);

        // 생성 SQL
        //   select count(c1_0.customer_id), count(c1_0.phone)
        //   from customers c1_0
        //
        // ── 해설 ────────────────────────────────────────────────
        // 왜 다른가?
        //
        // customer.count() 는 SQL 로 count(c1_0.customer_id) 가 됩니다.
        // count(*) 가 아닙니다. QueryDSL 은 엔티티 count 를 PK count 로 번역합니다.
        // customer_id 는 PK 라 NOT NULL 이므로 결과적으로 count(*) 와 같은 30 이 나옵니다.
        //
        // customer.phone.count() 는 count(c1_0.phone) 입니다.
        // SQL 의 COUNT(col) 은 NULL 을 세지 않습니다. 전화번호가 NULL 인 고객 3명이 빠져 27 입니다.
        //
        // 이 둘은 코드가 비슷하게 생겼고 반환 타입도 똑같이 Long 입니다.
        // 컴파일러가 구분해 줄 방법이 없습니다. 값만 다릅니다.
        // "고객이 몇 명인가" 를 묻는 리포트에서 phone.count() 를 쓰면 3명이 조용히 사라집니다.
        //
        // 규칙:
        //   "몇 건/몇 명인가"  → 엔티티 count (customer.count())
        //   "값이 있는 것이 몇 개인가" → 컬럼 count (customer.phone.count())
        //   "몇 종류인가"      → countDistinct (customer.city.countDistinct())
        //
        // 그리고 이것은 count 만의 문제가 아닙니다.
        // count(*) 를 제외한 모든 집계 함수가 NULL 을 건너뜁니다.
        // AVG(col) 은 정확히 SUM(col) / COUNT(col) 이므로,
        // 분모가 "전체 행 수" 가 아니라 "NULL 이 아닌 행 수" 입니다.
        // 평균을 리포트에 낼 때는 분모가 무엇인지 반드시 확인하십시오.
        // (MySQL8 코스 부록 A — NULL 완전 정복 참조)
    }

    // =================================================================
    // 문제 3. 합계 4천만 이상 고객 (8행)
    // =================================================================
    @Test
    @DisplayName("A3. where 와 having 의 분담")
    void a3_whereVsHaving() {
        NumberExpression<Long>       cnt = order.count();
        NumberExpression<BigDecimal> sum = order.totalAmount.sum();

        List<Tuple> rows = queryFactory
                .select(order.customer.id, cnt, sum)
                .from(order)
                .where(order.status.ne(OrderStatus.CANCELLED))      // ← 개별 행 조건
                .groupBy(order.customer.id)
                .having(sum.goe(new BigDecimal("40000000")))        // ← 집계 결과 조건
                .orderBy(sum.desc())
                .fetch();

        assertThat(rows).hasSize(8);

        // 생성 SQL
        //   select o1_0.customer_id, count(o1_0.order_id), sum(o1_0.total_amount)
        //   from orders o1_0
        //   where o1_0.status <> ?
        //   group by o1_0.customer_id
        //   having sum(o1_0.total_amount) >= ?
        //   order by sum(o1_0.total_amount) desc
        //
        // ── 해설 ────────────────────────────────────────────────
        // 판단 근거는 "그 조건이 개별 행에 대한 것인가, 그룹에 대한 것인가" 입니다.
        //
        //   FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY
        //           ↑                    ↑
        //      개별 행을 거름        그룹을 거름
        //
        //   "취소가 아닌 주문"     → 주문 한 건 한 건을 보고 판단 가능 → WHERE
        //   "합계가 4천만 이상"    → 그룹을 만들어 봐야 알 수 있음     → HAVING
        //
        // 반대로 쓰면 어떻게 되나?
        //
        //   having(order.status.ne(CANCELLED))
        //     → 문법적으로는 통과합니다. QueryDSL 이 막지 않습니다.
        //       하지만 CANCELLED 주문까지 전부 그룹핑한 뒤 버리므로 낭비이고,
        //       무엇보다 결과가 달라집니다. 그룹 안에 CANCELLED 가 섞인 채로 sum 이 계산되니까요.
        //       (having 은 그룹 전체에 대한 조건이므로 "이 그룹의 status" 라는 게 정의되지 않습니다.
        //        MySQL 은 ONLY_FULL_GROUP_BY 로 이것도 거부할 가능성이 높습니다.)
        //
        //   where(sum.goe(...))
        //     → 이건 아예 불가능합니다. WHERE 는 집계 전에 실행되므로
        //       그 시점에 sum(...) 이라는 값이 존재하지 않습니다.
        //       MySQL 이 "Invalid use of group function" 으로 거부합니다.
        //
        // 성능 관점도 있습니다.
        // where 로 미리 거르면 그룹핑 대상 행 수 자체가 줄고, status 에 인덱스가 있으면 탈 수도 있습니다.
        // having 은 이미 만들어진 그룹을 버리는 것이라 그런 이득이 없습니다.
        //
        // 규칙 한 줄:
        //   집계 함수가 들어가는 조건만 having. 나머지는 전부 where.
    }

    // =================================================================
    // 문제 4. 0건일 때 sum 을 0 으로 (coalesce)
    // =================================================================
    @Test
    @DisplayName("A4. coalesce 로 NULL 을 0 으로")
    void a4_coalesce() {
        BigDecimal total = queryFactory
                .select(order.totalAmount.sum().coalesce(BigDecimal.ZERO))
                .from(order)
                .where(order.shippingCity.eq("제주"))
                .fetchOne();

        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);

        // 생성 SQL
        //   select coalesce(sum(o1_0.total_amount), ?)
        //   from orders o1_0
        //   where o1_0.shipping_city = ?
        //   바인딩: [1] 0, [2] 제주
        //
        // ── 해설 ────────────────────────────────────────────────
        // 선택: coalesce (DB 쪽 처리)
        //
        // SQL 표준에서 SUM 은 대상이 0건이면 0 이 아니라 NULL 을 돌려줍니다.
        // "더할 것이 없으면 합계는 정의되지 않는다" 는 입장입니다.
        // count() 만 0건에서 0 을 돌려주고, sum / avg / max / min 은 전부 null 입니다.
        //
        // 이 null 이 자바로 넘어와서 그대로 계산에 쓰이면 NPE 입니다.
        //   BigDecimal fee = total.multiply(new BigDecimal("0.03"));  // 💥
        // 개발 데이터에는 항상 주문이 있으니 통과하고,
        // 신규 가입자나 신규 지역이 배치에 들어오는 날 터집니다.
        //
        // coalesce 방식 vs Optional 방식
        //
        //   coalesce(BigDecimal.ZERO)
        //     - DB 에서 대체됩니다. SQL 에 의도가 드러납니다.
        //     - groupBy 로 여러 행이 나올 때도 각 행마다 적용됩니다.  ← 결정적
        //   Optional.ofNullable(...).orElse(BigDecimal.ZERO)
        //     - 자바에서 대체됩니다. SQL 은 그대로입니다.
        //     - fetchOne() 으로 값 하나를 받을 때만 통합니다.
        //       List<Tuple> 로 여러 행을 받으면 행마다 일일이 처리해야 합니다.
        //
        // 그래서 coalesce 를 권합니다. 특히 leftJoin + 집계 조합에서는 사실상 필수입니다.
        //
        //   .select(customer.id, order.totalAmount.sum().coalesce(BigDecimal.ZERO))
        //   .from(customer)
        //   .leftJoin(order).on(order.customer.eq(customer))
        //   .groupBy(customer.id)
        //
        // 여기서 coalesce 를 빼면 "주문이 없는 고객" 행의 합계가 null 로 나옵니다.
        //
        // 한 가지 더: groupBy 만 쓰면 이 문제가 숨습니다.
        // 주문이 0건인 고객은 그룹 자체가 안 생기므로 null 을 볼 일이 없습니다.
        // 그러다 leftJoin 을 붙이는 순간 등장합니다. "우리는 괜찮다" 는 착각을 조심하십시오.
    }

    // =================================================================
    // 문제 5. transform 으로 Map 만들기 (Map 크기 30)
    // =================================================================
    @Test
    @DisplayName("A5. transform — 애플리케이션 메모리 그룹핑")
    void a5_transform() {
        Map<Long, List<OrderDto>> byCustomer = queryFactory
                .from(order)                                    // select() 없이 from() 으로 시작
                .innerJoin(order.customer, customer)
                .transform(groupBy(customer.id).as(
                        list(Projections.constructor(OrderDto.class, order.id, order.totalAmount))
                ));

        assertThat(byCustomer).hasSize(30);
        assertThat(byCustomer.get(1L)).hasSize(20);

        // 생성 SQL
        //   select c1_0.customer_id, o1_0.order_id, o1_0.total_amount
        //   from orders o1_0
        //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
        //
        //   ⚠️ group by 가 없습니다.
        //
        // ── 해설 ────────────────────────────────────────────────
        // group by 가 없는 이유:
        //
        // transform 은 쿼리를 바꾸는 API 가 아니라 결과 집합 변환기(ResultTransformer)입니다.
        // DB 는 조인 결과 600행을 그대로 돌려주고, QueryDSL 이 그 600행을 JVM 에서 순회하며
        // Map<Long, List<OrderDto>> 를 조립합니다.
        //
        //   DB                          네트워크        JVM
        //   600행 반환  ───────────────────────────>  600행 읽으며 Map 조립 → 30개 키
        //
        // 이름이 GroupBy.groupBy 라서 SQL 의 GROUP BY 를 만들 것 같지만 아닙니다.
        // 두 API 를 구분하십시오.
        //
        //   queryFactory.select(...).from(order).groupBy(order.customer.id)   → SQL 의 GROUP BY
        //   queryFactory.from(order).transform(GroupBy.groupBy(customer.id))  → JVM 메모리 그룹핑
        //
        // 그러면 transform 은 나쁜 API 인가? 아닙니다. 목적이 다를 뿐입니다.
        //
        // transform 의 진짜 가치는 두 가지입니다.
        //   1) 조립 코드 제거.
        //      직접 쓰면 stream().collect(Collectors.groupingBy(...)) + mapping 이 필요합니다.
        //      transform 은 그걸 쿼리 선언 안으로 밀어 넣습니다.
        //   2) fan-out 자동 정리.
        //      1:N 조인으로 부모가 중복되는 문제를 groupBy 키가 자연스럽게 흡수합니다.
        //      distinct 나 수동 Set 처리가 필요 없어집니다.
        //
        // 반대로 transform 이 해 주지 않는 것:
        //   - DB 부하를 줄여 주지 않습니다. 원본 행이 전부 넘어옵니다.
        //   - 네트워크 전송량을 줄여 주지 않습니다.
        //   - GroupBy.sum / avg / count 도 전부 JVM 에서 돕니다.
        //
        // 판단 기준 한 줄:
        //   "원본 행이 실제로 필요한가?"
        //     필요하다  → transform 이 편하다 (상세 화면, 페이징된 목록의 연관 데이터)
        //     필요없다  → DB 에서 접어라 (합계만, 건수만 필요한 경우)
        //
        // 여기서는 각 고객의 주문 20건이 실제로 다 필요하므로 transform 이 적절합니다.
        // 만약 주문이 60만 건이었다면 60만 행이 힙으로 올라옵니다. OOM 후보입니다.
    }

    // =================================================================
    // 문제 6. DB groupBy 로 건수만 (30행)
    // =================================================================
    @Test
    @DisplayName("A6. DB group by — 600행 vs 30행")
    void a6_dbGroupBy() {
        List<Tuple> counts = queryFactory
                .select(customer.id, order.count())
                .from(order)
                .innerJoin(order.customer, customer)
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        assertThat(counts).hasSize(30);
        assertThat(counts.get(0).get(order.count())).isEqualTo(20L);

        // 생성 SQL
        //   select c1_0.customer_id, count(o1_0.order_id)
        //   from orders o1_0
        //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
        //   group by c1_0.customer_id
        //   order by c1_0.customer_id
        //
        //   ← group by 가 있습니다.
        //
        // ── 해설 ────────────────────────────────────────────────
        // 넘어오는 행 수 비교
        //
        //   문제 5 (transform) → DB 가 600행 반환. JVM 이 600행을 읽어 30개 키로 접음.
        //   문제 6 (groupBy)   → DB 가 30행 반환. JVM 은 30행만 읽음.
        //
        //   600 : 30 = 20배 차이입니다.
        //
        // 그런데 이 비율은 데이터가 커질수록 벌어집니다.
        //   주문 600건    → 600행 vs 30행    (20배)
        //   주문 60만 건  → 60만행 vs 30행   (2만 배)
        //
        // 그룹 수는 고객 수에 비례해 늘지만, 원본 행 수는 주문 수에 비례해 늡니다.
        // "건수만 필요한데 transform 을 썼다" 는 실수는 데이터가 작을 때 아무 증상이 없습니다.
        // 그러다 서비스가 커지면 어느 날 힙이 터집니다. 코드는 그대로인데요.
        //
        // 그리고 DB 쪽 집계에는 부수적 이득도 있습니다.
        //   - 인덱스로 그룹핑을 처리할 수 있습니다 (idx_orders_customer).
        //   - 정렬도 DB 가 처리합니다.
        //   - 네트워크 전송량과 직렬화 비용이 줄어듭니다.
        //
        // 규칙:
        //   집계 결과만 필요하면 DB 의 groupBy.
        //   원본 행 목록이 필요하면 transform.
        //   둘 다 필요하면 두 쿼리로 나누는 것도 방법입니다.
    }

    // =================================================================
    // 문제 7. fan-out 과 count — 세 버전 (40 / 20 / 20)
    // =================================================================
    @Test
    @DisplayName("A7. fan-out 이 집계를 망가뜨린다")
    void a7_fanOut() {
        // (a) count() — 틀린 답
        NumberExpression<Long> cnt = order.count();
        List<Tuple> wrong = queryFactory
                .select(customer.id, cnt)
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .innerJoin(orderItem).on(orderItem.order.eq(order))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        // (b) countDistinct() — 고친 답
        NumberExpression<Long> cntDistinct = order.countDistinct();
        List<Tuple> fixed = queryFactory
                .select(customer.id, cntDistinct)
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .innerJoin(orderItem).on(orderItem.order.eq(order))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        // (c) 조인 제거 — 더 나은 답
        List<Tuple> best = queryFactory
                .select(customer.id, cnt)
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        assertThat(wrong.get(0).get(cnt)).isEqualTo(40L);           // 틀림
        assertThat(fixed.get(0).get(cntDistinct)).isEqualTo(20L);   // 맞음
        assertThat(best.get(0).get(cnt)).isEqualTo(20L);            // 맞음

        // 생성 SQL (a)
        //   select c1_0.customer_id, count(o1_0.order_id)
        //   from customers c1_0
        //   join orders o1_0 on o1_0.customer_id = c1_0.customer_id
        //   join order_items oi1_0 on oi1_0.order_id = o1_0.order_id
        //   group by c1_0.customer_id
        //
        // 생성 SQL (b) — count(distinct o1_0.order_id) 로 바뀜
        // 생성 SQL (c) — order_items 조인이 사라짐
        //
        // ── 해설 ────────────────────────────────────────────────
        // (a) 가 40 인 이유
        //
        // 주문 600건이 주문상세 1,200건과 1:N 조인되어 결과가 1,200행이 됩니다.
        // 주문 1건당 상세가 평균 2건이므로 각 주문이 2번씩 등장합니다.
        // count(o1_0.order_id) 는 그 1,200행을 셉니다. 고객당 20 × 2 = 40.
        //
        // 에러는 없습니다. 40 이라는 그럴듯한 숫자가 나옵니다.
        // "우리 고객은 평균 40건 주문한다" 는 리포트가 그대로 나갑니다.
        //
        // fan-out 에 대한 집계 함수별 반응
        //
        //   함수            증상                   distinct 로 고쳐지나?
        //   count(col)      행 수만큼 부풀음         ⭕ countDistinct()
        //   sum(col)        값이 반복 더해짐         ❌ 고칠 수 없음
        //   avg(col)        분모가 부풀어 왜곡       ❌ 고칠 수 없음
        //   max/min(col)    영향 없음               (반복돼도 최댓값은 같음)
        //
        // sum 이 왜 못 고쳐지는가?
        //   sumDistinct 를 쓰면 "금액이 같은 서로 다른 주문" 이 하나로 합쳐집니다.
        //   주문 A 가 10만원, 주문 B 도 10만원이면 distinct 가 둘을 하나로 봅니다.
        //   원래 20만원인데 10만원이 됩니다. 더 틀린 답입니다.
        //   시드 데이터에는 금액이 같은 주문이 실제로 여럿 있으므로 바로 드러납니다.
        //
        // 그래서 sum 의 fan-out 대처는 두 가지뿐입니다.
        //   1. 불필요한 조인을 제거한다  ← 대부분 이게 답입니다
        //   2. 서브쿼리로 분리한다
        //      .where(selectOne().from(orderItem).where(orderItem.order.eq(order)).exists())
        //      → 조인 대신 exists 로 조건만 걸면 행이 부풀지 않습니다 (Step 07)
        //
        // (c) 가 가장 좋은 이유
        //   "고객별 주문 건수" 라는 질문에 order_items 는 애초에 필요 없었습니다.
        //   countDistinct 는 잘못 짠 조인을 사후에 수습하는 것이고,
        //   조인 제거는 원인 자체를 없애는 것입니다.
        //   조인이 하나 줄었으니 실행 계획도 단순해집니다.
        //
        // 예방 습관:
        //   집계 쿼리를 쓰기 전에 "조인 후 행 수가 몇인가" 를 먼저 세어 보십시오.
        //
        //   queryFactory.select(order.count())
        //       .from(customer)
        //       .innerJoin(order).on(...)
        //       .innerJoin(orderItem).on(...)
        //       .fetchOne();          // → 1200
        //
        //   600 을 기대했는데 1200 이 나오면 그 자리에서 이상을 알아챌 수 있습니다.
        //   이 숫자를 모르면 집계 결과도 믿을 수 없습니다.
    }
}
