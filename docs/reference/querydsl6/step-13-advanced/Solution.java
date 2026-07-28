package com.example.shop.step13;

import com.example.shop.entity.Grade;
import com.example.shop.entity.OrderStatus;
import com.querydsl.core.Tuple;
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
 * Step 13 — 연습문제 정답과 해설.
 *
 * 답이 맞아도 생성 SQL 이 다르면 틀린 것입니다.
 * 각 문제의 주석에 "흔한 오답"을 함께 적어 두었습니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // ════════════════════════════════════════════════════════════════
    // 문제 1 — 금액 구간별 주문 건수
    // ════════════════════════════════════════════════════════════════
    //
    // 핵심은 "분류 표현식을 변수에 담아 재사용"입니다.
    // select 와 groupBy 에 인라인으로 두 번 쓰면, 나중에 구간 경계를 바꿀 때
    // 한쪽만 고치는 사고가 납니다. 두 case 가 달라지면 group by 가 select 와
    // 어긋나 ONLY_FULL_GROUP_BY 에서 에러가 나거나, 더 나쁘게는 조용히 틀린 집계가 됩니다.
    //
    // 생성 SQL 에서 case 식은 select 절과 group by 절에 **통째로 두 번** 들어갑니다.
    // QueryDSL 은 같은 자바 객체를 두 자리에 썼다고 해서 별칭으로 묶어 주지 않습니다.
    // (MySQL 은 select 별칭을 group by 에서 참조할 수 있지만, JPQL 은 그 문법을 만들지 않습니다.)
    //
    // 정렬을 건수(count) 로 하면 order by 에는 case 가 들어가지 않습니다.
    // 이것이 이 문제에서 정렬 기준을 건수로 잡은 이유입니다. 표현식 반복이 하나 줄어듭니다.
    //
    @Test
    @DisplayName("정답 1 — 금액 구간별 주문 건수")
    void ans1() {
        StringExpression amountTier = new CaseBuilder()
                .when(order.totalAmount.lt(new BigDecimal("100000"))).then("소액")
                .when(order.totalAmount.lt(new BigDecimal("500000"))).then("중액")
                .otherwise("고액");

        List<Tuple> result = queryFactory
                .select(amountTier, order.count())
                .from(order)
                .groupBy(amountTier)
                .orderBy(order.count().desc())
                .fetch();

        // 생성 SQL
        //   select
        //       case when o1_0.total_amount < ? then ?
        //            when o1_0.total_amount < ? then ?
        //            else ? end,
        //       count(o1_0.order_id)
        //   from orders o1_0
        //   group by
        //       case when o1_0.total_amount < ? then ?
        //            when o1_0.total_amount < ? then ?
        //            else ? end
        //   order by count(o1_0.order_id) desc
        //
        // 바인딩 파라미터 10개. case 하나당 5개씩입니다.

        result.forEach(t -> System.out.printf("%s | %d%n", t.get(0, String.class), t.get(1, Long.class)));

        long total = result.stream().mapToLong(t -> t.get(1, Long.class)).sum();
        assertThat(total).isEqualTo(600L);

        // 흔한 오답 ①: when 순서를 뒤집는 것.
        //   .when(lt(500000)).then("중액").when(lt(100000)).then("소액")
        //   → 5만원 주문도 "중액"이 됩니다. case 는 위에서부터 첫 매치에서 멈춥니다. 에러는 안 납니다.
        //
        // 흔한 오답 ②: BigDecimal 대신 int 리터럴을 넘기는 것.
        //   .when(order.totalAmount.lt(100000))
        //   → NumberExpression<BigDecimal>.lt(Number) 오버로드가 있어 컴파일은 됩니다.
        //     동작도 하지만, 금액 비교에 int 를 섞는 습관은 정산 코드에서 사고를 냅니다.
        //     금액은 끝까지 BigDecimal 로 다루십시오.
    }

    // ════════════════════════════════════════════════════════════════
    // 문제 2 — 등급 순 정렬과 그 대가
    // ════════════════════════════════════════════════════════════════
    //
    // EXPLAIN 예측의 정답: Extra 에 "Using filesort" 가 뜹니다. type 은 ALL 입니다.
    //
    // 이유 한 문장:
    //   인덱스는 "컬럼 값" 순으로 정렬돼 있는데, 정렬 대상이 컬럼 값이 아니라
    //   case 식의 "계산 결과"이므로 옵티마이저가 인덱스의 순서를 재활용할 방법이 없습니다.
    //
    // customers 는 30행이라 지금은 아무 문제가 없습니다.
    // Step 09 의 정렬 컬럼 함수 문제, Step 14 의 인덱스 죽이는 패턴 4가지와 같은 원리입니다.
    //
    // 30만 행 규모에서 이 정렬이 필요하다면 대안은 둘입니다.
    //   1) grade_rank TINYINT 컬럼을 두고 인덱스를 겁니다. 등급 체계는 거의 안 바뀌므로
    //      비정규화 비용이 낮습니다.
    //   2) 정렬을 애플리케이션에서 합니다. 페이징이 없다면 이쪽이 더 간단할 때가 많습니다.
    //      단, 페이징이 있으면 불가능합니다 — 전 행을 가져와야 하기 때문입니다.
    //
    // @Enumerated(EnumType.ORDINAL) 로 바꾸는 것은 답이 아닙니다.
    // enum 상수 사이에 하나를 끼워 넣는 순간 기존 데이터가 전부 어긋납니다.
    //
    @Test
    @DisplayName("정답 2 — 등급 순 정렬")
    void ans2() {
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

        // 생성 SQL 에서 case 식이 select 와 order by 에 두 번 들어갑니다.
        // 바인딩 파라미터 8개 (4 + 4).
        //
        // EXPLAIN SELECT name, grade FROM customers
        // ORDER BY CASE WHEN grade='VIP' THEN 4 ... END DESC, name;
        //   +------+---------------+------+------+----------------+
        //   | type | possible_keys | key  | rows | Extra          |
        //   | ALL  | NULL          | NULL |   30 | Using filesort |
        //   +------+---------------+------+------+----------------+

        result.stream().limit(6).forEach(System.out::println);

        assertThat(result).hasSize(30);
        assertThat(result.get(0).get(customer.grade)).isEqualTo(Grade.VIP);
        assertThat(result.get(29).get(customer.grade)).isEqualTo(Grade.BRONZE);
    }

    // ════════════════════════════════════════════════════════════════
    // 문제 3 — 도시별 피벗 + coalesce 위치
    // ════════════════════════════════════════════════════════════════
    //
    // 이 문제의 전부는 coalesce 를 **어디에** 붙이느냐입니다.
    //
    //   ✅ .sum().coalesce(BigDecimal.ZERO)   → coalesce(sum(case ...), ?)
    //      "합계 결과가 NULL 이면 0"
    //
    //   ❌ .coalesce(BigDecimal.ZERO).sum()   → sum(coalesce(case ..., ?))
    //      "각 행의 값이 NULL 이면 0 으로 보고 더하라"
    //      case 에 otherwise(ZERO) 가 이미 있으므로 각 행의 값은 절대 NULL 이 아닙니다.
    //      즉 이 coalesce 는 **아무 일도 하지 않습니다.**
    //      그런데 결과는 맞게 나옵니다 — 이 예제에서는 모든 도시에 PAID 주문이 있기 때문입니다.
    //      제주처럼 PAID 주문이 0건인 도시가 생기는 순간 NULL 이 흘러나갑니다.
    //
    // 이것이 "답이 맞아도 SQL 이 다르면 틀린 것"의 전형적인 사례입니다.
    // 두 코드는 현재 데이터에서 동일한 출력을 내지만, 하나는 방어가 되어 있고 하나는 아닙니다.
    //
    // 참고: groupBy 로 묶었으므로 각 그룹에는 최소 1행이 있습니다.
    //       따라서 sum(case ... else 0 end) 은 이 쿼리에서는 NULL 이 될 수 없습니다.
    //       그래도 coalesce 를 붙이는 이유는, 나중에 where 절이 추가되어
    //       "그룹은 있는데 조건에 맞는 행이 없는" 상황이 생겨도 깨지지 않게 하기 위해서입니다.
    //       리포지토리가 반환하는 집계값은 NULL 을 반환하지 않는 것이 계약으로 낫습니다.
    //
    @Test
    @DisplayName("정답 3 — 도시별 피벗 + coalesce 위치")
    void ans3() {
        NumberExpression<BigDecimal> paidSum = new CaseBuilder()
                .when(order.status.eq(OrderStatus.PAID)).then(order.totalAmount)
                .otherwise(BigDecimal.ZERO)
                .sum()
                .coalesce(BigDecimal.ZERO);      // ★ sum 을 감쌉니다

        NumberExpression<Integer> cancelledCount = new CaseBuilder()
                .when(order.status.eq(OrderStatus.CANCELLED)).then(1)
                .otherwise(0)
                .sum()
                .coalesce(0);

        List<Tuple> result = queryFactory
                .select(order.shippingCity, paidSum, cancelledCount)
                .from(order)
                .groupBy(order.shippingCity)
                .orderBy(order.shippingCity.asc())
                .fetch();

        // 생성 SQL
        //   select
        //       o1_0.shipping_city,
        //       coalesce(sum(case when o1_0.status = ? then o1_0.total_amount else ? end), ?),
        //       coalesce(sum(case when o1_0.status = ? then ? else ? end), ?)
        //   from orders o1_0
        //   group by o1_0.shipping_city
        //   order by o1_0.shipping_city
        //
        // coalesce 가 sum 을 바깥에서 감싸고 있는지 반드시 확인하십시오.

        result.forEach(System.out::println);

        assertThat(result).hasSize(6);
        assertThat(result).allSatisfy(t -> {
            assertThat(t.get(paidSum)).isNotNull();
            assertThat(t.get(cancelledCount)).isNotNull();
        });
    }

    // ════════════════════════════════════════════════════════════════
    // 문제 4 — concat 과 NULL
    // ════════════════════════════════════════════════════════════════
    //
    // 먼저 틀린 버전이 왜 틀렸는지입니다.
    //
    //   customer.name.concat("(").concat(customer.phone).concat(")")
    //   → concat(concat(concat(c1_0.name, ?), c1_0.phone), ?)
    //
    // MySQL 의 CONCAT 은 **인자 중 하나라도 NULL 이면 전체가 NULL** 입니다.
    // 따라서 phone 이 NULL 인 3명은 결과가 통째로 NULL 이 됩니다.
    // 이름조차 사라집니다. 행은 30건 나오지만 그중 3건이 null 입니다.
    //
    // 이것이 조용한 실패의 전형입니다.
    //   - 예외가 나지 않습니다
    //   - 건수도 30건으로 맞습니다
    //   - null 을 그대로 응답에 실으면 프론트에서 "undefined(undefined)" 같은 게 보입니다
    //
    // 처방은 concat 에 넣기 **전에** NULL 을 없애는 것입니다.
    // coalesce 뒤에는 .asString() 이 필요합니다. coalesce 의 반환 타입이
    // StringExpression 이 아니라서 concat 을 바로 이어 붙일 수 없기 때문입니다.
    //
    @Test
    @DisplayName("정답 4 — concat 과 NULL")
    void ans4() {
        // (a) 틀린 버전 — phone 이 NULL 인 3명이 통째로 null 이 됩니다
        List<String> wrong = queryFactory
                .select(customer.name.concat("(").concat(customer.phone).concat(")"))
                .from(customer)
                .fetch();

        long nullCount = wrong.stream().filter(s -> s == null).count();
        System.out.println("틀린 버전의 null 개수: " + nullCount);   // 3
        assertThat(nullCount).isEqualTo(3);

        // (b) 정답 — concat 에 넣기 전에 coalesce 로 NULL 을 없앱니다
        List<String> correct = queryFactory
                .select(customer.name
                        .concat("(")
                        .concat(customer.phone.coalesce("미등록").asString())
                        .concat(")"))
                .from(customer)
                .fetch();

        // 생성 SQL
        //   select concat(concat(concat(c1_0.name, ?), coalesce(c1_0.phone, ?)), ?)
        //   from customers c1_0

        correct.stream().limit(5).forEach(System.out::println);

        assertThat(correct).hasSize(30);
        assertThat(correct).doesNotContainNull();
        assertThat(correct).anyMatch(s -> s.endsWith("(미등록)"));

        // 흔한 오답: coalesce 를 맨 바깥에 붙이는 것.
        //   customer.name.concat("(").concat(customer.phone).concat(")").coalesce("미등록")
        //   → coalesce(concat(...), ?)
        //   결과는 3명 전부 "미등록" 이 됩니다. 이름이 사라집니다.
        //   NULL 은 concat 안쪽에서 이미 전체를 삼켰기 때문입니다.
        //   NULL 방어는 **NULL 이 생기는 지점에서** 해야 합니다.
    }

    // ════════════════════════════════════════════════════════════════
    // 문제 5 — nullif + coalesce 로 0 나누기 방어
    // ════════════════════════════════════════════════════════════════
    //
    // 세 단계입니다.
    //   1) nullif(price, 0)  — 분모가 0 이면 NULL 로 바꿉니다
    //   2) 나눗셈            — 분모가 NULL 이면 결과도 NULL 입니다 (에러 아님)
    //   3) coalesce(..., 0)  — NULL 을 0 으로 되돌립니다
    //
    // nullif 없이 그냥 나누면 MySQL 은 (sql_mode 에 따라) 경고와 함께 NULL 을 반환합니다.
    // 결과는 같아 보이지만 의미가 다릅니다.
    //   - nullif 없이: "우연히 NULL 이 나왔다"
    //   - nullif 있음: "0으로 나누는 경우는 0으로 친다는 규칙이 코드에 있다"
    // 코드를 읽는 사람이 이 차이를 볼 수 있어야 합니다.
    //
    // 소수점 둘째 자리 처리:
    //   MySQL 의 DECIMAL 나눗셈은 div_precision_increment(기본 4)에 따라 스케일이 정해집니다.
    //   자바에서 BigDecimal 로 받은 뒤 setScale(2, RoundingMode.HALF_UP) 하는 편이 예측 가능합니다.
    //   DB 에서 round() 를 부르려면 DB 함수 호출(13-10)이 필요하고, 이식성이 떨어집니다.
    //
    @Test
    @DisplayName("정답 5 — nullif + coalesce 로 0 나누기 방어")
    void ans5() {
        NumberExpression<BigDecimal> marginRate = product.price
                .subtract(product.cost)
                .multiply(100)
                .divide(Expressions.nullif(product.price, BigDecimal.ZERO))
                .coalesce(BigDecimal.ZERO);

        List<Tuple> result = queryFactory
                .select(product.name, product.price, product.cost, marginRate)
                .from(product)
                .orderBy(product.id.asc())
                .fetch();

        // 생성 SQL
        //   select
        //       p1_0.name, p1_0.price, p1_0.cost,
        //       coalesce((p1_0.price - p1_0.cost) * ? / nullif(p1_0.price, ?), ?)
        //   from products p1_0
        //   order by p1_0.product_id

        result.stream().limit(5).forEach(t -> System.out.printf("%s | %s | %s | %s%n",
                t.get(product.name), t.get(product.price), t.get(product.cost),
                t.get(marginRate)));

        assertThat(result).hasSize(40);
        assertThat(result).allSatisfy(t -> assertThat(t.get(marginRate)).isNotNull());

        // 자바에서 소수점 둘째 자리로 맞추기
        result.stream().limit(3).forEach(t -> {
            BigDecimal rate = t.get(marginRate).setScale(2, java.math.RoundingMode.HALF_UP);
            System.out.println(t.get(product.name) + " → " + rate + "%");
        });

        // 흔한 오답: 자바에서 divide 를 부르는 것.
        //   price.subtract(cost).multiply(100).divide(price)
        //   → ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result
        //   자바의 BigDecimal.divide 는 스케일을 안 주면 나누어떨어지지 않을 때 예외입니다.
        //   DB 는 조용히 반올림하고 자바는 예외를 던집니다. 같은 계산이 아닙니다.
    }

    // ════════════════════════════════════════════════════════════════
    // 문제 6 — 안전한 템플릿과 위험한 템플릿  ★ 이 스텝의 핵심
    // ════════════════════════════════════════════════════════════════
    //
    // 규칙은 하나입니다.
    //   **템플릿 문자열은 컴파일 시점 상수여야 하고, 변하는 값은 예외 없이 {n} 인자로 넘긴다.**
    //
    // 안전한 버전의 SQL:
    //   select date_format(o1_0.order_date, ?) from orders o1_0 limit ?
    //   binding parameter (1:VARCHAR) <- [%Y년 %m월]
    //   → pattern 이 무엇이든 "값"입니다. 공격 문자열을 넣어도 그 문자열로 포맷을 시도하고 끝납니다.
    //
    // 위험한 버전의 SQL (benign):
    //   select date_format(o1_0.order_date, '%Y년 %m월') from orders o1_0 limit ?
    //   → ? 가 없습니다. 값이 SQL 에 박혔습니다. 결과는 맞으므로 테스트도 리뷰도 통과합니다.
    //
    // 위험한 버전의 SQL (attack):
    //   select date_format(o1_0.order_date, '%Y-%m'),
    //          (select c1_0.email from customers c1_0 where c1_0.grade = 'VIP' limit 1)
    //   from orders o1_0
    //   → 월별 집계 API 가 고객 이메일을 반환합니다.
    //
    // Step 10 에서 "QueryDSL 은 JPQL 로 번역되니 대체로 안전하다"고 했던 안심이 여기서 깨집니다.
    // Expressions.*Template 의 첫 인자는 QueryDSL 이 검사하지 않고 그대로 JPQL 에 넣는
    // 원시 문자열입니다. PreparedStatement 에 "... where id = " + id 를 쓰는 것과 같습니다.
    //
    // 리뷰 체크리스트:
    //   - 템플릿 첫 인자에 + 가 있는가
    //   - 템플릿 첫 인자에 format( / formatted( 가 있는가
    //   - 템플릿 첫 인자가 변수인가 (변수라면 출처를 끝까지 추적)
    //   - 템플릿 안에 작은따옴표 ' 가 있는가 (리터럴을 직접 쓰고 있다는 뜻)
    //   - {n} 개수와 인자 개수가 맞는가 (불일치는 이어 붙였다는 신호)
    //
    // 컬럼명·정렬 방향은 바인딩 파라미터가 될 수 없습니다 (order by ? 는 상수 정렬입니다).
    // 그래서 동적 컬럼명은 구조적으로 템플릿에 넣을 수밖에 없고,
    // **화이트리스트가 유일한 방어**입니다. Step 10 의 SORT_KEYS Map 이 그것입니다.

    /** 템플릿은 컴파일 시점 상수. 이 필드가 방어의 전부입니다. */
    private static final String DATE_FORMAT_TPL = "function('date_format', {0}, {1})";

    @Test
    @DisplayName("정답 6 — 안전한 템플릿과 위험한 템플릿")
    void ans6() {
        String benign = "%Y년 %m월";
        String attack = "%Y-%m') , (select email from customers where grade='VIP' limit 1";

        System.out.println("── 안전한 버전 (정상 입력) ──");
        safeFormat(benign).forEach(System.out::println);

        System.out.println("── 안전한 버전 (공격 입력) ──");
        // 공격 문자열이 그냥 포맷 문자열로 취급됩니다. 유출 없음.
        safeFormat(attack).forEach(System.out::println);

        System.out.println("── 위험한 버전 (정상 입력) — SQL 에 ? 가 없습니다 ──");
        unsafeFormat(benign).forEach(System.out::println);

        System.out.println("── 위험한 버전 (공격 입력) — SQL 이 변조됩니다 ──");
        try {
            unsafeFormat(attack).forEach(System.out::println);
        } catch (Exception e) {
            System.out.println("실행 예외: " + e.getClass().getSimpleName());
            System.out.println("예외가 나도 변조된 SQL 은 이미 만들어졌습니다.");
        }

        assertThat(safeFormat(benign)).hasSize(3);
    }

    /** (a) 안전한 버전. */
    private List<String> safeFormat(String pattern) {
        StringExpression expr = Expressions.stringTemplate(
                DATE_FORMAT_TPL,      // 컴파일 시점 상수
                order.orderDate,
                pattern);             // 값은 {1} 자리 인자로 → ? 바인딩

        return queryFactory.select(expr).from(order).limit(3).fetch();
    }

    /**
     * (b) 위험한 버전.
     *
     * ⚠️ 학습용입니다. 이 형태를 어떤 이유로도 운영 코드에 복사하지 마십시오.
     *    이 메서드가 존재하는 유일한 이유는 "리뷰에서 이 형태를 알아보기 위해서"입니다.
     */
    private List<String> unsafeFormat(String pattern) {
        StringExpression expr = Expressions.stringTemplate(
                "function('date_format', {0}, '" + pattern + "')",   // ★ 사고 지점
                order.orderDate);

        return queryFactory.select(expr).from(order).limit(3).fetch();
    }

    // ════════════════════════════════════════════════════════════════
    // 문제 7 — 날짜 필터와 인덱스
    // ════════════════════════════════════════════════════════════════
    //
    // 이유 한 문장:
    //   인덱스는 컬럼 값 순으로 정렬돼 있는데, extract(...) 는 컬럼을 감싼 계산 결과이므로
    //   그 인덱스의 순서로 범위를 잘라낼 수 없습니다. 반면 >= / < 는 컬럼 값 자체에 대한
    //   범위 조건이므로 인덱스에서 시작점과 끝점을 바로 찾을 수 있습니다.
    //
    // 두 생성 SQL
    //   ① where extract(year from o1_0.order_date) = ?
    //       and extract(month from o1_0.order_date) between ? and ?
    //   ② where o1_0.order_date >= ? and o1_0.order_date < ?
    //
    // EXPLAIN 비교 (ALTER TABLE orders ADD INDEX idx_orders_date (order_date); 를 건 상태)
    //   ①  | type | key  | rows | Extra       |
    //      | ALL  | NULL |  600 | Using where |
    //
    //   ②  | type  | key             | rows | Extra                 |
    //      | range | idx_orders_date |  148 | Using index condition |
    //
    // 실습이 끝나면 되돌리십시오: DROP INDEX idx_orders_date ON orders;
    //
    // 경계값 주의:
    //   "6월 30일까지"를 lt(2025-06-30 23:59:59) 로 잡으면 23:59:59.5 를 놓칩니다.
    //   orders.order_date 는 DATETIME(소수 초 없음) 이라 이 예제에서는 문제가 없지만,
    //   DATETIME(6) 컬럼이라면 조용히 몇 건이 빠집니다.
    //   **goe(시작) + lt(다음 구간 시작)** 형태를 습관으로 쓰십시오. 경계 계산이 필요 없습니다.
    //
    @Test
    @DisplayName("정답 7 — 날짜 필터와 인덱스")
    void ans7() {
        // ① year() + month() — 인덱스를 못 씁니다
        long byFunction = queryFactory
                .selectFrom(order)
                .where(order.orderDate.year().eq(2025)
                        .and(order.orderDate.month().between(1, 6)))
                .fetch().size();

        // ② goe + lt — 인덱스를 쓸 수 있습니다
        long byRange = queryFactory
                .selectFrom(order)
                .where(order.orderDate.goe(LocalDateTime.of(2025, 1, 1, 0, 0))
                        .and(order.orderDate.lt(LocalDateTime.of(2025, 7, 1, 0, 0))))
                .fetch().size();

        System.out.println("① 함수 방식: " + byFunction + "건");
        System.out.println("② 범위 방식: " + byRange + "건");

        assertThat(byFunction).isEqualTo(byRange);

        // 정리:
        //   집계·그룹핑에는 year()/month() — 어차피 전 행을 훑으므로 손해가 없습니다
        //   필터링에는 between / goe+lt — where 의 함수 하나가 인덱스 전체를 버리게 합니다
        //
        // 이 원리는 Step 09(정렬 컬럼 함수)와 Step 14 의 "인덱스를 죽이는 4가지 패턴"에서
        // 같은 형태로 반복됩니다.
    }
}
