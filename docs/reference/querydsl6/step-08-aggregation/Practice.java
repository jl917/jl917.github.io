package com.example.shop.step08;

import com.example.shop.entity.Grade;
import com.example.shop.entity.OrderStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.group.GroupBy;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
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
import java.util.Optional;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QEmployee.employee;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.querydsl.core.group.GroupBy.groupBy;
import static com.querydsl.core.group.GroupBy.list;
import static com.querydsl.jpa.JPAExpressions.selectOne;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 08 — 집계와 그룹핑 : 본문 예제 모음
 *
 * <p>본문 8-1 ~ 8-12 절의 모든 예제를 절 번호 주석과 함께 담았습니다.
 *
 * <p><b>이 스텝은 로그를 보지 않으면 배울 수 없습니다.</b>
 * 특히 8-8 절의 transform 은 "생성 SQL 에 group by 가 없다" 는 것이 핵심인데,
 * 그것은 오직 hibernate.SQL 로그로만 확인할 수 있습니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    /** 8-5 절 처방 1 — 표현식을 상수로 뽑아 둔다. Q타입 표현식은 불변이라 static final 로 안전하다. */
    private static final NumberExpression<Long>       ORDER_CNT = order.count();
    private static final NumberExpression<BigDecimal> AMT_SUM   = order.totalAmount.sum();
    private static final NumberExpression<Double>     AMT_AVG   = order.totalAmount.avg();
    private static final NumberExpression<BigDecimal> AMT_MAX   = order.totalAmount.max();
    private static final NumberExpression<BigDecimal> AMT_MIN   = order.totalAmount.min();

    /** 8-5 절 처방 2 — DTO 프로젝션용 record. avg 가 Double 이라는 사실이 시그니처에 박혀 있다. */
    public record OrderStat(Long count, BigDecimal sum, Double avg) {}

    /** 8-8 절 transform 용 DTO. */
    public record OrderDto(Long orderId, BigDecimal totalAmount) {}

    // =================================================================
    // [8-1] 집계 함수 — Path 에서 바로 나온다
    // =================================================================

    @Test
    @DisplayName("[8-1] count / sum / avg / max / min")
    void basicAggregates() {
        Tuple stats = queryFactory
                .select(ORDER_CNT, AMT_SUM, AMT_AVG, AMT_MAX, AMT_MIN)
                .from(order)
                .fetchOne();

        // 생성 SQL:
        //   select count(o1_0.order_id), sum(o1_0.total_amount), avg(o1_0.total_amount),
        //          max(o1_0.total_amount), min(o1_0.total_amount)
        //   from orders o1_0
        System.out.println("count = " + stats.get(ORDER_CNT));   // 600
        System.out.println("sum   = " + stats.get(AMT_SUM));     // 764598000.00
        System.out.println("avg   = " + stats.get(AMT_AVG));     // 1274330.0  ← Double!
        System.out.println("max   = " + stats.get(AMT_MAX));     // 6663900.00
        System.out.println("min   = " + stats.get(AMT_MIN));     // 8900.00

        assertThat(stats.get(ORDER_CNT)).isEqualTo(600L);
    }

    @Test
    @DisplayName("[8-1] 엔티티 count 는 count(*) 가 아니라 count(PK) 로 번역된다")
    void entityCountIsPkCount() {
        Long cnt = queryFactory.select(order.count()).from(order).fetchOne();

        // 생성 SQL: select count(o1_0.order_id) from orders o1_0
        //           ↑ count(*) 가 아니다. order_id 는 PK 라 NOT NULL 이므로 결과는 같다.
        assertThat(cnt).isEqualTo(600L);
    }

    // =================================================================
    // [8-2] 집계 결과는 Tuple
    // =================================================================

    @Test
    @DisplayName("[8-2] groupBy 없으면 Tuple 1건")
    void singleTuple() {
        Tuple t = queryFactory
                .select(customer.count(), customer.points.avg())
                .from(customer)
                .fetchOne();

        Long cnt = t.get(customer.count());
        Double avg = t.get(customer.points.avg());

        // 새로 만든 표현식으로도 값이 꺼내진다. Tuple 내부가 equals() 로 매칭하기 때문.
        // 동작은 하지만 장황하고 오타에 취약하다 → 변수로 뽑는 것이 관례.
        assertThat(cnt).isEqualTo(30L);
        assertThat(avg).isEqualTo(5959.0);
    }

    @Test
    @DisplayName("[8-2] 표현식을 변수로 뽑아 두는 관례")
    void expressionAsVariable() {
        NumberExpression<Double>  avgPoints = customer.points.avg();
        NumberExpression<Integer> sumPoints = customer.points.sum();
        NumberExpression<Long>    cntAll    = customer.count();

        Tuple t = queryFactory
                .select(cntAll, avgPoints, sumPoints)
                .from(customer)
                .fetchOne();

        assertThat(t.get(cntAll)).isEqualTo(30L);
        assertThat(t.get(avgPoints)).isEqualTo(5959.0);
        assertThat(t.get(sumPoints)).isEqualTo(178770);
    }

    // =================================================================
    // [8-3] groupBy
    // =================================================================

    @Test
    @DisplayName("[8-3] 등급별 고객 수와 평균 포인트 (4행)")
    void groupByGrade() {
        NumberExpression<Long>   cnt = customer.count();
        NumberExpression<Double> avg = customer.points.avg();

        List<Tuple> byGrade = queryFactory
                .select(customer.grade, cnt, avg)
                .from(customer)
                .groupBy(customer.grade)
                .orderBy(customer.grade.asc())
                .fetch();

        // 생성 SQL:
        //   select c1_0.grade, count(c1_0.customer_id), avg(c1_0.points)
        //   from customers c1_0
        //   group by c1_0.grade
        //   order by c1_0.grade
        //
        // ENUM 은 선언 순서로 정렬된다 (BRONZE < SILVER < GOLD < VIP). 알파벳 순이 아니다.
        byGrade.forEach(t -> System.out.printf("%-7s %2d %10.4f%n",
                t.get(customer.grade), t.get(cnt), t.get(avg)));

        assertThat(byGrade).hasSize(4);
    }

    @Test
    @DisplayName("[8-3] 여러 컬럼으로 그룹핑 (18행)")
    void groupByMultipleColumns() {
        List<Tuple> rows = queryFactory
                .select(customer.grade, customer.city, customer.count())
                .from(customer)
                .groupBy(customer.grade, customer.city)
                .orderBy(customer.grade.asc(), customer.city.asc())
                .fetch();

        assertThat(rows).hasSize(18);
    }

    @Test
    @DisplayName("[8-3] 주문 상태별 매출 (5행). 집계 표현식으로 정렬")
    void groupByOrderStatus() {
        NumberExpression<Long> cnt = order.count();

        List<Tuple> byStatus = queryFactory
                .select(order.status, cnt, order.totalAmount.sum(), order.totalAmount.avg())
                .from(order)
                .groupBy(order.status)
                .orderBy(cnt.desc())         // ← order by count(...) desc
                .fetch();

        byStatus.forEach(t -> System.out.printf("%-10s %3d %15s%n",
                t.get(order.status), t.get(cnt), t.get(order.totalAmount.sum())));

        assertThat(byStatus).hasSize(5);
    }

    // =================================================================
    // [8-4] having
    // =================================================================

    @Test
    @DisplayName("[8-4] having — 주문 5건 이상 (전원 통과, 30행)")
    void havingLooseCondition() {
        List<Tuple> heavy = queryFactory
                .select(order.customer.id, order.count())
                .from(order)
                .groupBy(order.customer.id)
                .having(order.count().goe(5))
                .orderBy(order.customer.id.asc())
                .fetch();

        // 시드 데이터가 규칙적이라 30명 모두 20건씩. 아무도 걸러지지 않는다.
        assertThat(heavy).hasSize(30);
    }

    @Test
    @DisplayName("[8-4] where(집계 전) + having(집계 후) — 합계 5천만 이상 (3행)")
    void whereAndHaving() {
        NumberExpression<BigDecimal> sum = order.totalAmount.sum();

        List<Tuple> bigSpenders = queryFactory
                .select(order.customer.id, order.count(), sum)
                .from(order)
                .where(order.status.ne(OrderStatus.CANCELLED))            // ① 개별 행 필터
                .groupBy(order.customer.id)
                .having(sum.goe(new BigDecimal("50000000")))              // ② 그룹 필터
                .orderBy(sum.desc())
                .fetch();

        // ⚠️ order.status.ne(...) 를 having 에 쓰면 동작은 하지만
        //    CANCELLED 주문까지 전부 그룹핑한 뒤 버린다. where 로 옮겨야 한다.
        bigSpenders.forEach(t -> System.out.printf("%3d %2d %15s%n",
                t.get(order.customer.id), t.get(order.count()), t.get(sum)));

        assertThat(bigSpenders).hasSize(3);
    }

    // =================================================================
    // [8-5] ⚠️ Tuple 다루기 — ClassCastException
    // =================================================================

    @Test
    @DisplayName("[8-5] ⚠️ avg() 는 Double 이다 — 인덱스 접근 + 캐스팅은 런타임에 터진다")
    void avgTypeTrap() {
        Tuple t = queryFactory
                .select(order.totalAmount.sum(), order.totalAmount.avg())
                .from(order)
                .fetchOne();

        // ⭕ sum 은 BigDecimal 이 맞다
        BigDecimal sum = t.get(order.totalAmount.sum());
        assertThat(sum).isNotNull();

        // ⚠️ Tuple.get(int) 의 반환 타입은 Object. 컴파일러가 손을 뗀다.
        assertThatThrownBy(() -> {
            @SuppressWarnings("unused")
            BigDecimal avg = (BigDecimal) t.get(1);
        }).isInstanceOf(ClassCastException.class)
          .hasMessageContaining("java.lang.Double");

        // ⭕ 표현식으로 꺼내면 컴파일 타임에 잡힌다.
        //    아래 줄의 주석을 풀면 컴파일 에러가 납니다. 그게 정상이고 좋은 일입니다.
        // BigDecimal wrong = t.get(order.totalAmount.avg());
        Double avg = t.get(order.totalAmount.avg());
        assertThat(avg).isEqualTo(1274330.0);
    }

    @Test
    @DisplayName("[8-5] 처방 1 — 표현식을 상수로")
    void fixWithConstants() {
        Tuple t = queryFactory
                .select(ORDER_CNT, AMT_SUM, AMT_AVG)
                .from(order)
                .fetchOne();

        Long       cnt = t.get(ORDER_CNT);
        BigDecimal sum = t.get(AMT_SUM);
        Double     avg = t.get(AMT_AVG);      // 타입이 상수 선언에 박혀 있어 헷갈릴 여지가 없다

        System.out.println(cnt + " / " + sum + " / " + avg);
    }

    @Test
    @DisplayName("[8-5] 처방 2 — DTO 프로젝션 (권장)")
    void fixWithProjection() {
        OrderStat stat = queryFactory
                .select(Projections.constructor(OrderStat.class,
                        order.count(),
                        order.totalAmount.sum(),
                        order.totalAmount.avg()))
                .from(order)
                .fetchOne();

        // record 생성자 시그니처가 컴파일 타임에 검증된다.
        // avg 를 BigDecimal 로 선언하면 그 자리에서 컴파일이 깨진다.
        System.out.println(stat);
        assertThat(stat.count()).isEqualTo(600L);
    }

    // =================================================================
    // [8-6] 집계와 NULL
    // =================================================================

    @Test
    @DisplayName("[8-6] ⚠️ count(컬럼) 은 NULL 을 세지 않는다 — 30 vs 27")
    void countIgnoresNull() {
        Tuple t = queryFactory
                .select(customer.count(),            // count(c1_0.customer_id) — PK
                        customer.phone.count(),      // count(c1_0.phone)       — NULL 제외
                        customer.city.countDistinct())
                .from(customer)
                .fetchOne();

        System.out.println("customer.count()              = " + t.get(customer.count()));
        System.out.println("customer.phone.count()        = " + t.get(customer.phone.count()));
        System.out.println("customer.city.countDistinct() = " + t.get(customer.city.countDistinct()));

        assertThat(t.get(customer.count())).isEqualTo(30L);
        assertThat(t.get(customer.phone.count())).isEqualTo(27L);   // 전화번호 NULL 3명이 빠졌다
    }

    @Test
    @DisplayName("[8-6] avg 의 분모는 count(col) 이다")
    void avgDenominator() {
        Tuple t = queryFactory
                .select(customer.count(), customer.points.avg(), customer.points.sum())
                .from(customer)
                .fetchOne();

        // points 는 NOT NULL 이므로 178770 / 30 = 5959.0 이 정확히 맞는다.
        // nullable 이었다면 분모가 줄어 평균이 올라갔을 것이다.
        assertThat(t.get(customer.points.sum())).isEqualTo(178770);
        assertThat(t.get(customer.points.avg())).isEqualTo(5959.0);
    }

    @Test
    @DisplayName("[8-6] 그룹핑 컬럼이 NULL 이면 NULL 도 하나의 그룹이 된다")
    void nullBecomesItsOwnGroup() {
        List<Tuple> byManager = queryFactory
                .select(employee.manager.id, employee.count())
                .from(employee)
                .groupBy(employee.manager.id)
                .orderBy(employee.manager.id.asc().nullsFirst())
                .fetch();

        // 첫 행이 null 그룹(CEO 1명). 이 NULL 이 Step 07 의 NOT IN 함정의 원인이었다.
        byManager.forEach(t -> System.out.println(t.get(employee.manager.id) + " " + t.get(employee.count())));
        assertThat(byManager).hasSize(9);   // NULL 그룹 1 + 관리자 8
    }

    // =================================================================
    // [8-7] sum() 이 0건일 때 null
    // =================================================================

    @Test
    @DisplayName("[8-7] ⚠️ 0건이면 sum 은 0 이 아니라 null 이다")
    void sumOfNothingIsNull() {
        BigDecimal total = queryFactory
                .select(order.totalAmount.sum())
                .from(order)
                .where(order.shippingCity.eq("제주"))     // 해당 행이 0건
                .fetchOne();

        System.out.println("sum = " + total);   // null
        assertThat(total).isNull();

        // 이 값을 그대로 쓰면 NPE:
        //   BigDecimal fee = total.multiply(new BigDecimal("0.03"));  // 💥
    }

    @Test
    @DisplayName("[8-7] 처방 1 — coalesce (DB 에서 대체)")
    void sumWithCoalesce() {
        BigDecimal total = queryFactory
                .select(order.totalAmount.sum().coalesce(BigDecimal.ZERO))
                .from(order)
                .where(order.shippingCity.eq("제주"))
                .fetchOne();

        // 생성 SQL: select coalesce(sum(o1_0.total_amount), ?) from orders o1_0 where ...
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[8-7] 처방 2 — 자바 쪽 Optional")
    void sumWithOptional() {
        BigDecimal total = Optional.ofNullable(
                queryFactory.select(order.totalAmount.sum()).from(order)
                        .where(order.shippingCity.eq("제주"))
                        .fetchOne()
        ).orElse(BigDecimal.ZERO);

        // 둘 다 유효하지만 coalesce 쪽이 SQL 에 의도가 드러나서 낫다.
        // 그리고 groupBy 로 여러 행이 나올 때는 coalesce 만 통한다.
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[8-7] leftJoin + 집계에는 coalesce 를 습관적으로")
    void leftJoinNeedsCoalesce() {
        List<Tuple> rows = queryFactory
                .select(customer.id, order.totalAmount.sum().coalesce(BigDecimal.ZERO))
                .from(customer)
                .leftJoin(order).on(order.customer.eq(customer))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        assertThat(rows).hasSize(30);
    }

    // =================================================================
    // [8-8] GroupBy.transform() — 메모리 그룹핑
    // =================================================================

    @Test
    @DisplayName("[8-8] ⚠️ transform 은 DB 의 group by 를 만들지 않는다 — 로그를 보라")
    void transformDoesNotGroupInDb() {
        Map<Long, List<OrderDto>> byCustomer = queryFactory
                .from(order)                                    // ← select() 가 없다
                .innerJoin(order.customer, customer)
                .transform(groupBy(customer.id).as(
                        list(Projections.constructor(OrderDto.class, order.id, order.totalAmount))
                ));

        // 생성 SQL:
        //   select c1_0.customer_id, o1_0.order_id, o1_0.total_amount
        //   from orders o1_0
        //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
        //
        // ⚠️ group by 가 한 글자도 없다!
        //    조인 결과 600행을 전부 JVM 으로 가져와 자바 메모리에서 묶는다.
        System.out.println("Map 크기 = " + byCustomer.size());               // 30
        System.out.println("1번 고객 주문 수 = " + byCustomer.get(1L).size()); // 20

        assertThat(byCustomer).hasSize(30);
        assertThat(byCustomer.get(1L)).hasSize(20);
    }

    @Test
    @DisplayName("[8-8] 같은 질문을 DB group by 로 — 600행 vs 30행")
    void dbGroupByComparison() {
        List<Tuple> counts = queryFactory
                .select(customer.id, order.count())
                .from(order)
                .innerJoin(order.customer, customer)
                .groupBy(customer.id)
                .fetch();

        // 생성 SQL:
        //   select c1_0.customer_id, count(o1_0.order_id)
        //   from orders o1_0
        //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
        //   group by c1_0.customer_id
        //
        // ← group by 가 있다. 네트워크로 30행만 넘어온다 (transform 은 600행).
        assertThat(counts).hasSize(30);
    }

    @Test
    @DisplayName("[8-8] GroupBy.count / sum — 전부 JVM 에서 계산된다")
    void groupByAggregatesInMemory() {
        Map<Long, Tuple> stats = queryFactory
                .from(order)
                .innerJoin(order.customer, customer)
                .transform(groupBy(customer.id).as(
                        GroupBy.count(),
                        GroupBy.sum(order.totalAmount)
                ));

        // 생성 SQL 에 여전히 group by 가 없다. sum 도 JVM 에서 돈다.
        //   GroupBy.sum(order.totalAmount)  → JVM,  전송 600행
        //   order.totalAmount.sum() + groupBy() → DB, 전송 30행
        assertThat(stats).hasSize(30);
        System.out.println("1번 고객 = " + stats.get(1L));
    }

    @Test
    @DisplayName("[8-8] transform 의 진짜 장점 — fan-out 을 자동으로 정리한다")
    void transformCleansFanOut() {
        // Order → OrderItem 1:N 조인. 주문이 상세 개수만큼 중복되지만
        // transform 이 order.id 로 묶어 주므로 중복이 자연스럽게 사라진다.
        Map<Long, List<Long>> itemsByOrder = queryFactory
                .from(orderItem)
                .innerJoin(orderItem.order, order)
                .transform(groupBy(order.id).as(list(orderItem.id)));

        assertThat(itemsByOrder).hasSize(600);   // 주문 600건
        System.out.println("주문 1번의 상세 = " + itemsByOrder.get(1L));
    }

    // =================================================================
    // [8-9] fan-out 과 집계
    // =================================================================

    @Test
    @DisplayName("[8-9] ⚠️ 불필요한 조인이 count 를 두 배로 만든다 (40)")
    void fanOutBreaksCount() {
        List<Tuple> wrong = queryFactory
                .select(customer.id, order.count())
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .innerJoin(orderItem).on(orderItem.order.eq(order))    // ← 뻥튀기
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        // 40 은 주문 건수가 아니라 주문상세 건수다. 에러 없음. 숫자만 두 배.
        System.out.println("1번 고객(잘못) = " + wrong.get(0).get(order.count()));
        assertThat(wrong.get(0).get(order.count())).isEqualTo(40L);
    }

    @Test
    @DisplayName("[8-9] 처방 1 — countDistinct (20)")
    void fanOutFixedByDistinct() {
        List<Tuple> fixed = queryFactory
                .select(customer.id, order.countDistinct())
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .innerJoin(orderItem).on(orderItem.order.eq(order))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        // 생성 SQL: count(distinct o1_0.order_id)
        assertThat(fixed.get(0).get(order.countDistinct())).isEqualTo(20L);
    }

    @Test
    @DisplayName("[8-9] 처방 2 — 불필요한 조인 제거 (더 나음, 20)")
    void fanOutFixedByRemovingJoin() {
        List<Tuple> best = queryFactory
                .select(customer.id, order.count())
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        assertThat(best.get(0).get(order.count())).isEqualTo(20L);
    }

    @Test
    @DisplayName("[8-9] ⚠️ sum 의 fan-out 은 distinct 로 고칠 수 없다")
    void fanOutBreaksSumUnfixable() {
        NumberExpression<BigDecimal> sum = order.totalAmount.sum();

        List<Tuple> wrongSum = queryFactory
                .select(customer.id, sum)
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .innerJoin(orderItem).on(orderItem.order.eq(order))
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        // 같은 주문의 total_amount 가 상세 개수만큼 반복 더해진다.
        // sumDistinct 를 써도 "금액이 같은 서로 다른 주문" 이 하나로 합쳐져 더 틀린다.
        // 답은 조인을 지우거나, 상세 조건을 exists 로 분리하는 것뿐이다.
        System.out.println("1번 고객(부풀린 합계) = " + wrongSum.get(0).get(sum));

        // 처방: exists 로 분리
        List<Tuple> fixed = queryFactory
                .select(customer.id, sum)
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .where(selectOne().from(orderItem).where(orderItem.order.eq(order)).exists())
                .groupBy(customer.id)
                .orderBy(customer.id.asc())
                .fetch();

        System.out.println("1번 고객(정상 합계) = " + fixed.get(0).get(sum));
    }

    @Test
    @DisplayName("[8-9] 조인 후 행 수를 먼저 확인하는 습관")
    void checkJoinedRowCount() {
        Long joinedRows = queryFactory
                .select(order.count())
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .innerJoin(orderItem).on(orderItem.order.eq(order))
                .fetchOne();

        // 600 을 기대했는데 1200 이다. 여기서 이상을 알아챌 수 있다.
        System.out.println("조인 후 행 수 = " + joinedRows);
        assertThat(joinedRows).isEqualTo(1200L);
    }

    // =================================================================
    // [8-10] ONLY_FULL_GROUP_BY
    // =================================================================

    @Test
    @DisplayName("[8-10] QueryDSL 은 막지 않는다. MySQL 이 ERROR 1055 를 낸다")
    void onlyFullGroupBy() {
        // QueryDSL 은 그대로 SQL 을 만들어 보낸다.
        //   select c1_0.city, c1_0.name, count(c1_0.customer_id)
        //   from customers c1_0 group by c1_0.city
        assertThatThrownBy(() ->
                queryFactory
                        .select(customer.city, customer.name, customer.count())
                        .from(customer)
                        .groupBy(customer.city)
                        .fetch()
        ).hasMessageContaining("only_full_group_by");
    }

    @Test
    @DisplayName("[8-10] PK 로 묶으면 통과한다 — MySQL 의 함수 종속성 판정")
    void functionalDependencyPasses() {
        // select(customer) 는 customers 의 모든 컬럼을 select 하지만
        // group by 가 PK 이므로 MySQL 이 함수 종속성으로 통과시킨다.
        // ⚠️ groupBy(customer.city) 로 바꾸면 즉시 ERROR 1055.
        // ⚠️ PostgreSQL 등 다른 DB 에서는 판정이 다를 수 있다 (이식성 문제).
        List<Tuple> rows = queryFactory
                .select(customer, order.count())
                .from(customer)
                .innerJoin(order).on(order.customer.eq(customer))
                .groupBy(customer.id)
                .fetch();

        assertThat(rows).hasSize(30);
    }

    @Test
    @DisplayName("[8-10] ANY_VALUE 대응은 max()/min() 로 감싸기")
    void anyValueWorkaround() {
        List<Tuple> rows = queryFactory
                .select(customer.city, customer.count(), customer.name.max())
                .from(customer)
                .groupBy(customer.city)
                .orderBy(customer.city.asc())
                .fetch();

        assertThat(rows).hasSize(8);
    }

    // =================================================================
    // [8-11] case 로 조건부 집계 — 피벗
    // =================================================================

    @Test
    @DisplayName("[8-11] 등급별 포인트를 한 행으로 피벗")
    void conditionalAggregatePivot() {
        NumberExpression<Integer> vip = new CaseBuilder()
                .when(customer.grade.eq(Grade.VIP)).then(customer.points).otherwise(0);
        NumberExpression<Integer> gold = new CaseBuilder()
                .when(customer.grade.eq(Grade.GOLD)).then(customer.points).otherwise(0);
        NumberExpression<Integer> silver = new CaseBuilder()
                .when(customer.grade.eq(Grade.SILVER)).then(customer.points).otherwise(0);
        NumberExpression<Integer> bronze = new CaseBuilder()
                .when(customer.grade.eq(Grade.BRONZE)).then(customer.points).otherwise(0);

        Tuple pivot = queryFactory
                .select(vip.sum(), gold.sum(), silver.sum(), bronze.sum())
                .from(customer)
                .fetchOne();

        // 생성 SQL:
        //   select sum(case when c1_0.grade = ? then c1_0.points else 0 end), ... (4개)
        //   from customers c1_0
        System.out.println("VIP    = " + pivot.get(vip.sum()));       // 77000
        System.out.println("GOLD   = " + pivot.get(gold.sum()));      // 68400
        System.out.println("SILVER = " + pivot.get(silver.sum()));    // 24800
        System.out.println("BRONZE = " + pivot.get(bronze.sum()));    //  8570

        assertThat(pivot.get(vip.sum())).isEqualTo(77000);
    }

    @Test
    @DisplayName("[8-11] 조건부 count — sum(case when ... then 1 else 0 end)")
    void conditionalCount() {
        NumberExpression<Integer> vipCount = new CaseBuilder()
                .when(customer.grade.eq(Grade.VIP)).then(1).otherwise(0);

        Tuple counts = queryFactory
                .select(vipCount.sum(), customer.count())
                .from(customer)
                .fetchOne();

        // ⚠️ otherwise(0) + count() 를 쓰면 0도 세어 항상 전체 행 수가 나온다.
        //    조건부 집계는 sum 을 쓰는 것이 안전하다.
        assertThat(counts.get(vipCount.sum())).isEqualTo(4);
        assertThat(counts.get(customer.count())).isEqualTo(30L);
    }
}
