package com.example.shop.step13;

import com.example.shop.entity.Grade;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 13 — 고급 표현식.
 *
 * 본문의 모든 예제를 절 번호 주석과 함께 담았습니다.
 * 각 메서드를 실행하면서 콘솔의 hibernate.SQL 로그를 교재의 SQL 과 한 글자씩 비교하십시오.
 *
 * 실행:
 *   ./gradlew test --tests 'com.example.shop.step13.Practice'
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // ────────────────────────────────────────────────────────────────
    // [13-1] CaseBuilder — 단순 case 와 복합 case
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-1] 단순 case — 등급을 한국어 라벨로")
    void simpleCase() {
        List<Tuple> result = queryFactory
                .select(customer.name,
                        customer.grade
                                .when(Grade.VIP).then("최우수")
                                .when(Grade.GOLD).then("우수")
                                .otherwise("일반"))
                .from(customer)
                .orderBy(customer.id.asc())
                .limit(5)
                .fetch();

        // 확인 포인트: when 의 비교값과 then 의 결과값이 모두 ? 바인딩으로 나갑니다.
        //             case when c1_0.grade = ? then ? ... else ? end
        result.forEach(t -> System.out.println(t.get(0, String.class) + " | " + t.get(1, String.class)));

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("[13-1] 복합 case — 포인트 구간별 등급")
    void complexCase() {
        StringExpression pointTier = new CaseBuilder()
                .when(customer.points.goe(10000)).then("골드")
                .when(customer.points.goe(5000)).then("실버")
                .when(customer.points.goe(1000)).then("브론즈")
                .otherwise("신규");

        List<Tuple> result = queryFactory
                .select(customer.name, customer.points, pointTier)
                .from(customer)
                .orderBy(customer.points.desc())
                .limit(6)
                .fetch();

        // when 절의 순서가 곧 평가 순서입니다.
        // goe(1000) 을 맨 위로 올리면 14200 포인트 고객도 "브론즈"가 됩니다. 에러는 안 납니다.
        result.forEach(t -> System.out.printf("%s | %d | %s%n",
                t.get(customer.name), t.get(customer.points), t.get(pointTier)));

        assertThat(result).hasSize(6);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-2] case 를 orderBy 에 — 커스텀 정렬 순서
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-2] case 정렬 — VIP → GOLD → SILVER → BRONZE")
    void caseInOrderBy() {
        NumberExpression<Integer> gradeRank = new CaseBuilder()
                .when(customer.grade.eq(Grade.VIP)).then(4)
                .when(customer.grade.eq(Grade.GOLD)).then(3)
                .when(customer.grade.eq(Grade.SILVER)).then(2)
                .otherwise(1);

        List<Tuple> result = queryFactory
                .select(customer.name, customer.grade, gradeRank)
                .from(customer)
                .orderBy(gradeRank.desc(), customer.name.asc())
                .fetch();

        // 생성 SQL 을 보십시오. case 식이 select 에 한 번, order by 에 한 번,
        // 통째로 두 번 들어갑니다. 바인딩 파라미터도 8개입니다.
        //
        // 그리고 이 SQL 을 MySQL 콘솔에 그대로 붙여 EXPLAIN 을 걸어 보십시오.
        //   Extra: Using filesort
        // 정렬 대상이 컬럼 값이 아니라 계산 결과이므로 인덱스를 쓸 수 없습니다.
        // customers 는 30행이라 지금은 문제가 없습니다. 30만 행이면 문제가 됩니다.
        result.stream().limit(6).forEach(t -> System.out.printf("%s | %s | %d%n",
                t.get(customer.name), t.get(customer.grade), t.get(gradeRank)));

        assertThat(result).hasSize(30);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-3] 조건부 집계 — case 로 만드는 피벗
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-3] 상태별 매출을 한 행에 — 테이블 한 번만 읽음")
    void conditionalAggregation() {
        Tuple result = queryFactory
                .select(
                        new CaseBuilder()
                                .when(order.status.eq(OrderStatus.PAID)).then(order.totalAmount)
                                .otherwise(BigDecimal.ZERO).sum(),
                        new CaseBuilder()
                                .when(order.status.eq(OrderStatus.DELIVERED)).then(order.totalAmount)
                                .otherwise(BigDecimal.ZERO).sum(),
                        new CaseBuilder()
                                .when(order.status.eq(OrderStatus.CANCELLED)).then(order.totalAmount)
                                .otherwise(BigDecimal.ZERO).sum(),
                        order.count())
                .from(order)
                .fetchOne();

        // 상태별로 쿼리를 세 번 날리는 것과 결과는 같지만 I/O 는 1/3 입니다.
        System.out.println(result);

        assertThat(result).isNotNull();
        assertThat(result.get(3, Long.class)).isEqualTo(600L);
    }

    @Test
    @DisplayName("[13-3] 도시별 피벗 — 조건부 합계 + 조건부 카운트")
    void pivotByCity() {
        List<Tuple> byCity = queryFactory
                .select(order.shippingCity,
                        new CaseBuilder().when(order.status.eq(OrderStatus.PAID))
                                .then(order.totalAmount).otherwise(BigDecimal.ZERO).sum(),
                        new CaseBuilder().when(order.status.eq(OrderStatus.CANCELLED))
                                .then(1).otherwise(0).sum())
                .from(order)
                .groupBy(order.shippingCity)
                .orderBy(order.shippingCity.asc())
                .fetch();

        // then(1).otherwise(0).sum() 은 조건부 카운트입니다.
        // count(case when ... then 1 end) 로도 되지만,
        // 행이 하나도 없을 때 sum 은 NULL 을, count 는 0 을 반환한다는 차이가 있습니다.
        byCity.forEach(System.out::println);

        assertThat(byCity).hasSize(6);   // 서울/부산/대구/인천/광주/대전
    }

    // ────────────────────────────────────────────────────────────────
    // [13-4] 상수 — Expressions.constant
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-4] constant 는 JPQL 에서 빠질 수 있습니다")
    void constantDisappears() {
        List<Tuple> result = queryFactory
                .select(customer.name, Expressions.constant("A"))
                .from(customer)
                .limit(3)
                .fetch();

        // 생성 SQL 에 'A' 도 ? 도 없습니다. select c1_0.name from customers c1_0 limit ?
        // 값은 결과 조립 단계에서 붙습니다.
        // 이것은 최적화이므로 버전과 사용 위치에 따라 달라질 수 있습니다.
        result.forEach(t -> System.out.println(t.get(0, String.class) + " | " + t.get(1, String.class)));

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("[13-4] 다른 표현식의 인자로 쓰이면 SQL 에 나갑니다")
    void constantAsArgument() {
        List<String> result = queryFactory
                .select(customer.name.concat(Expressions.constant("-님")))
                .from(customer)
                .limit(3)
                .fetch();

        // concat(c1_0.name, ?) — 이번에는 ? 로 나갑니다.
        // "SQL 에 안 보이니 안 나간 것" 이라고 일반화하면 안 됩니다.
        result.forEach(System.out::println);

        assertThat(result).allMatch(s -> s.endsWith("-님"));
    }

    // ────────────────────────────────────────────────────────────────
    // [13-5] 문자열 연산 — concat 과 stringValue()
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-5] concat 은 2항 — 체인하면 중첩됩니다")
    void concatChain() {
        List<String> result = queryFactory
                .select(customer.city.concat("/").concat(customer.name))
                .from(customer)
                .limit(4)
                .fetch();

        // concat(concat(c1_0.city, ?), c1_0.name)
        // JPQL 의 CONCAT 은 2항이므로 이렇게 번역됩니다. 결과는 같습니다.
        result.forEach(System.out::println);

        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("[13-5] stringValue() — enum 과 숫자를 문자열 연산에 넣기")
    void stringValue() {
        // 아래는 컴파일 에러입니다. concat 은 Expression<String> 만 받습니다.
        //   customer.name.concat("_").concat(customer.grade);
        // 타입 시스템이 정확하게 막은 것이므로 우회하지 말고 명시적으로 변환합니다.

        List<String> withGrade = queryFactory
                .select(customer.name.concat("_").concat(customer.grade.stringValue()))
                .from(customer)
                .limit(4)
                .fetch();

        // concat(concat(c1_0.name, ?), cast(c1_0.grade as char))
        withGrade.forEach(System.out::println);

        List<String> withPoints = queryFactory
                .select(customer.name.concat("(").concat(customer.points.stringValue()).concat("P)"))
                .from(customer)
                .limit(3)
                .fetch();

        withPoints.forEach(System.out::println);

        assertThat(withGrade).hasSize(4);
        assertThat(withPoints).hasSize(3);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-6] 숫자 연산
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-6] 마진과 마진율 — 체인 순서대로 좌결합")
    void arithmetic() {
        List<Tuple> result = queryFactory
                .select(product.name,
                        product.price,
                        product.cost,
                        product.price.subtract(product.cost),
                        product.price.subtract(product.cost)
                                .multiply(100)
                                .divide(product.price))
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .orderBy(product.price.desc())
                .limit(4)
                .fetch();

        // (p1_0.price - p1_0.cost) * ? / p1_0.price
        // 괄호는 QueryDSL 이 알아서 넣습니다. Step 04 의 or 와 달리 산술 연산은 안전합니다.
        //
        // 결과 스케일이 30.000000 (소수 6자리) 인 것에 주목하십시오.
        // 이 스케일은 MySQL 의 div_precision_increment(기본 4)가 정합니다.
        // 자바의 BigDecimal.divide 는 스케일을 안 주면 예외를 던집니다. 같은 계산이 아닙니다.
        result.forEach(System.out::println);

        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("[13-6] BigDecimal 비교는 compareTo 로 — equals 는 스케일까지 봅니다")
    void bigDecimalComparison() {
        BigDecimal fromDb = new BigDecimal("30.000000");
        BigDecimal literal = new BigDecimal("30.00");

        assertThat(fromDb.equals(literal)).isFalse();          // 스케일이 다르므로 false
        assertThat(fromDb.compareTo(literal)).isZero();        // 값은 같으므로 0

        // 이 차이가 "테스트는 통과하는데 운영에서 assert 가 깨지는" 사고의 원인입니다.
    }

    // ────────────────────────────────────────────────────────────────
    // [13-7] coalesce
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-7] phone NULL 3명에 기본값 붙이기")
    void coalescePhone() {
        List<Tuple> result = queryFactory
                .select(customer.name, customer.phone, customer.phone.coalesce("번호없음"))
                .from(customer)
                .where(customer.phone.isNull())
                .fetch();

        // coalesce(c1_0.phone, ?)
        result.forEach(System.out::println);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("[13-7] coalesce 뒤에 asString() 을 붙여야 문자열 연산을 이어갈 수 있습니다")
    void coalesceAsString() {
        List<String> result = queryFactory
                .select(customer.name.concat(" / ")
                        .concat(customer.phone.coalesce("번호없음").asString()))
                .from(customer)
                .where(customer.phone.isNull())
                .fetch();

        result.forEach(System.out::println);

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(s -> s.endsWith("번호없음"));
    }

    @Test
    @DisplayName("[13-7] sum() 은 대상이 없으면 0 이 아니라 NULL — Step 08 8-7 의 완성판")
    void sumReturnsNull() {
        // 제주로 배송된 주문은 0건입니다.
        BigDecimal total = queryFactory
                .select(order.totalAmount.sum())
                .from(order)
                .where(order.shippingCity.eq("제주"))
                .fetchOne();

        // fetchOne() 은 "행 하나"를 반환했고 그 값이 NULL 입니다. "결과 없음"이 아닙니다.
        System.out.println("coalesce 없이: " + total);
        assertThat(total).isNull();

        BigDecimal safe = queryFactory
                .select(order.totalAmount.sum().coalesce(BigDecimal.ZERO))
                .from(order)
                .where(order.shippingCity.eq("제주"))
                .fetchOne();

        // coalesce(sum(o1_0.total_amount), ?)
        // coalesce 가 sum 을 "감싼다"는 것이 핵심입니다.
        //   .sum().coalesce(ZERO)  → coalesce(sum(x), 0)   ← 원하는 것
        //   .coalesce(ZERO).sum()  → sum(coalesce(x, 0))   ← 전혀 다른 뜻
        System.out.println("coalesce 적용: " + safe);
        assertThat(safe).isNotNull();
        assertThat(safe.compareTo(BigDecimal.ZERO)).isZero();
    }

    // ────────────────────────────────────────────────────────────────
    // [13-8] nullif
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-8] 0 나누기를 nullif + coalesce 로 명시적으로 처리")
    void nullifDivision() {
        NumberExpression<BigDecimal> pricePerStock = product.price
                .divide(Expressions.nullif(product.stock, 0))
                .coalesce(BigDecimal.ZERO);

        List<Tuple> result = queryFactory
                .select(product.name, product.stock, pricePerStock)
                .from(product)
                .orderBy(product.id.asc())
                .limit(6)
                .fetch();

        // coalesce(p1_0.price / nullif(p1_0.stock, ?), ?)
        //
        // nullif 없이 그냥 나누면 재고 0 인 상품에서 NULL 이 나옵니다.
        // 에러가 아니라 조용한 NULL 입니다. 자바에서 받아 쓰는 순간 NPE 입니다.
        //
        // "0으로 나누면 0으로 친다"는 비즈니스 규칙을 SQL 에 명시한 것입니다.
        // NULL 이 우연히 흘러가는 것과 규칙에 따라 0 이 되는 것은 다릅니다.
        result.forEach(System.out::println);

        assertThat(result).hasSize(6);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-9] Expressions 팩토리
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-9] allOf / anyOf — null 인자를 무시하며 결합, 괄호를 잃지 않음")
    void allOfAnyOf() {
        String city = "서울";
        Grade grade = Grade.GOLD;

        // (도시 조건 AND 등급 조건) OR (VIP)
        BooleanExpression cond = Expressions.anyOf(
                Expressions.allOf(cityEq(city), gradeEq(grade)),
                customer.grade.eq(Grade.VIP));

        List<String> result = queryFactory
                .select(customer.name)
                .from(customer)
                .where(cond)
                .fetch();

        // where (c1_0.city = ? and c1_0.grade = ?) or c1_0.grade = ?
        //
        // Step 04 에서 .and().or() 체인이 괄호를 잃었던 문제가 여기서는 발생하지 않습니다.
        // 결합 구조를 함수 호출의 중첩으로 표현했기 때문입니다.
        // 괄호를 잃을 수 없는 형태로 쓰는 것이 괄호를 잘 넣는 것보다 안전합니다.
        result.forEach(System.out::println);

        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("[13-9] 모든 조건이 null 이면 결합 결과도 null → 조건 없음")
    void allOfWithAllNull() {
        BooleanExpression cond = Expressions.allOf(cityEq(null), gradeEq(null));

        List<String> result = queryFactory
                .select(customer.name)
                .from(customer)
                .where(cond)               // where(null) 은 조건 없음
                .fetch();

        assertThat(cond).isNull();
        assertThat(result).hasSize(30);
    }

    @Test
    @DisplayName("[13-9] asNumber — 숫자 리터럴이 왼쪽에 와야 할 때")
    void asNumber() {
        List<Tuple> result = queryFactory
                .select(product.name,
                        product.stock,
                        Expressions.asNumber(100).subtract(product.stock))
                .from(product)
                .limit(3)
                .fetch();

        // ? - p1_0.stock
        result.forEach(System.out::println);

        assertThat(result).hasSize(3);
    }

    private BooleanExpression cityEq(String city) {
        return city == null ? null : customer.city.eq(city);
    }

    private BooleanExpression gradeEq(Grade grade) {
        return grade == null ? null : customer.grade.eq(grade);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-10] DB 함수 호출
    // ────────────────────────────────────────────────────────────────

    /** 템플릿 문자열은 반드시 컴파일 시점 상수로 분리합니다. 13-11 의 규칙입니다. */
    private static final String DATE_FORMAT_TPL = "function('date_format', {0}, {1})";

    @Test
    @DisplayName("[13-10] function('date_format', ...) 으로 월별 집계")
    void dbFunction() {
        StringExpression yearMonth = Expressions.stringTemplate(
                DATE_FORMAT_TPL, order.orderDate, "%Y-%m");

        List<Tuple> result = queryFactory
                .select(yearMonth, order.count(), order.totalAmount.sum())
                .from(order)
                .groupBy(yearMonth)
                .orderBy(yearMonth.asc())
                .limit(4)
                .fetch();

        // date_format(o1_0.order_date, ?)
        // {0}, {1} 자리에 넘긴 인자가 ? 바인딩 파라미터로 나갔습니다. 이것이 안전한 형태입니다.
        //
        // Hibernate 6 는 Dialect 에 등록된 함수라면 function(...) 래퍼 없이도 되는 경우가 많지만,
        // 어떤 함수가 등록돼 있는지는 전적으로 Dialect 에 달려 있습니다.
        // 이 코스는 표준 문법인 function('name', ...) 을 기본으로 씁니다.
        result.forEach(System.out::println);

        assertThat(result).hasSize(4);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-11] ⚠️ stringTemplate 으로 SQL 인젝션이 열립니다
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-11] ✅ 안전 — 값을 {n} 인자로 넘김. SQL 에 ? 가 보입니다")
    void injectionSafe() {
        String pattern = "%Y-%m";        // 사용자가 정할 수 있는 값이라고 가정

        StringExpression expr = Expressions.stringTemplate(
                DATE_FORMAT_TPL,          // ← 컴파일 시점 상수
                order.orderDate,
                pattern);                 // ← 값은 인자로

        List<String> result = queryFactory
                .select(expr)
                .from(order)
                .limit(3)
                .fetch();

        // select date_format(o1_0.order_date, ?) from orders o1_0 limit ?
        //
        // pattern 이 무엇이든 그것은 "값"으로만 취급됩니다.
        // "'); DROP TABLE orders; --" 를 넣어도 그냥 그 문자열로 포맷을 시도하고 끝납니다.
        result.forEach(System.out::println);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("[13-11] ❌ 위험 — 학습용. 어떤 형태로도 운영 코드에 복사하지 마십시오")
    void injectionVulnerable() {
        // ⚠️ 이 메서드는 함정을 눈으로 확인하기 위한 것입니다.
        //    Expressions.*Template 의 첫 인자는 QueryDSL 이 검사하지 않고
        //    그대로 JPQL 에 삽입하는 원시 문자열입니다.
        //    PreparedStatement 에 "... where id = " + id 를 쓰는 것과 위험도가 같습니다.

        String benign = "%Y-%m";

        StringExpression ok = Expressions.stringTemplate(
                "function('date_format', {0}, '" + benign + "')",   // ★ 이어 붙임
                order.orderDate);

        List<String> okResult = queryFactory.select(ok).from(order).limit(3).fetch();

        // select date_format(o1_0.order_date, '%Y-%m') from orders o1_0 limit ?
        //   ↑ ? 가 없습니다. 값이 SQL 에 그대로 박혔습니다.
        //     결과는 맞으므로 테스트도 리뷰도 통과합니다.
        okResult.forEach(System.out::println);

        // 이제 공격자가 보낸 값입니다.
        String attack = "%Y-%m') , (select email from customers where grade='VIP' limit 1";

        StringExpression evil = Expressions.stringTemplate(
                "function('date_format', {0}, '" + attack + "')",
                order.orderDate);

        // 생성되는 SQL:
        //   select date_format(o1_0.order_date, '%Y-%m'),
        //          (select c1_0.email from customers c1_0 where c1_0.grade = 'VIP' limit 1)
        //   from orders o1_0
        //
        // 월별 집계 API 가 고객 이메일을 반환합니다.
        // select 절이 하나 늘어 Tuple 크기가 달라지므로 아래는 예외로 끝날 수 있지만,
        // Tuple 을 그대로 JSON 직렬화하는 코드였다면 응답에 실려 나갑니다.
        try {
            List<Tuple> leaked = queryFactory
                    .select(evil, order.orderDate)
                    .from(order)
                    .limit(3)
                    .fetch();
            leaked.forEach(t -> System.out.println("[LEAK] " + t));
        } catch (Exception e) {
            System.out.println("[LEAK] 실행 중 예외: " + e.getClass().getSimpleName()
                    + " — 예외가 나도 SQL 은 이미 만들어졌습니다: " + e.getMessage());
        }

        // 처방은 규칙 하나입니다.
        //   템플릿 문자열은 컴파일 시점 상수여야 하고,
        //   변하는 값은 예외 없이 {n} 자리의 인자로 넘깁니다.
    }

    // ────────────────────────────────────────────────────────────────
    // [13-12] booleanTemplate
    // ────────────────────────────────────────────────────────────────

    private static final String REGEXP_TPL = "function('regexp_like', {0}, {1})";

    @Test
    @DisplayName("[13-12] booleanTemplate — where 절에 열리는 인젝션은 더 나쁩니다")
    void booleanTemplate() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(Expressions.booleanTemplate(REGEXP_TPL, product.name, "노트북|모니터"))
                .fetch();

        // where regexp_like(p1_0.name, ?)
        //
        // 동작합니다. 그리고 13-11 과 완전히 같은 위험을 가집니다.
        // where 절에 열리는 인젝션은 select 절보다 나쁩니다. or 1=1 하나로 전체가 노출됩니다.
        //
        // booleanTemplate 을 쓰기 전에 표준 방법이 없는지 먼저 확인하십시오.
        //   정규식     → like 로 충분한 경우가 많음
        //   대소문자   → lower() (인덱스 포기) 또는 콜레이션
        //   날짜 부분  → orderDate.year(), .month()  ← 13-13
        result.forEach(p -> System.out.println(p.getName()));

        assertThat(result).hasSize(3);
    }

    // ────────────────────────────────────────────────────────────────
    // [13-13] 날짜 표현식
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[13-13] year()/month() 로 월별 집계 — 표준 SQL 이라 이식 가능")
    void dateParts() {
        List<Tuple> result = queryFactory
                .select(order.orderDate.year(),
                        order.orderDate.month(),
                        order.count(),
                        order.totalAmount.sum())
                .from(order)
                .groupBy(order.orderDate.year(), order.orderDate.month())
                .orderBy(order.orderDate.year().asc(), order.orderDate.month().asc())
                .limit(4)
                .fetch();

        // extract(year from o1_0.order_date), extract(month from o1_0.order_date)
        //
        // 13-10 의 date_format 과 결과가 같은데 DB 고유 함수를 쓰지 않았습니다.
        // 표준으로 되는 것은 표준으로 하십시오.
        result.forEach(System.out::println);

        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("[13-13] between vs year()+month() — 같은 결과, 다른 실행 계획")
    void dateRangeVsFunction() {
        // A — 컬럼에 함수. 인덱스를 못 씁니다.
        long byFunction = queryFactory
                .selectFrom(order)
                .where(order.orderDate.year().eq(2025)
                        .and(order.orderDate.month().eq(1)))
                .fetch().size();

        // where extract(year from o1_0.order_date) = ? and extract(month from ...) = ?
        //   EXPLAIN → type: ALL, Extra: Using where

        // B — 범위. order_date 에 인덱스가 있다면 그 인덱스를 씁니다.
        long byRange = queryFactory
                .selectFrom(order)
                .where(order.orderDate.goe(LocalDateTime.of(2025, 1, 1, 0, 0))
                        .and(order.orderDate.lt(LocalDateTime.of(2025, 2, 1, 0, 0))))
                .fetch().size();

        // where o1_0.order_date >= ? and o1_0.order_date < ?
        //   EXPLAIN → type: range, Extra: Using index condition

        System.out.println("함수 방식: " + byFunction + "건, 범위 방식: " + byRange + "건");

        // 결과는 같습니다. 실행 계획만 다릅니다.
        // 집계·그룹핑에는 year()/month() 를, 필터링에는 between / goe+lt 를 쓰십시오.
        assertThat(byFunction).isEqualTo(byRange);

        // 상한을 23:59:59 로 잡는 대신 goe + lt 를 쓰는 이유:
        // DATETIME(6) 컬럼이라면 23:59:59.5 같은 값을 놓칩니다.
    }
}
