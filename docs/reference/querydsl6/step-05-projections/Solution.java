package com.example.shop.step05;

import com.example.shop.entity.Grade;
import com.querydsl.core.Tuple;
import com.querydsl.core.annotations.QueryProjection;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
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

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QReview.review;

/**
 * Step 05 — 프로젝션과 DTO : 연습문제 정답과 해설
 *
 * Exercise.java 를 스스로 풀어본 "뒤에" 열어보십시오.
 * 각 정답 위 주석에 기대 결과와 생성 SQL 이 적혀 있습니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 정답 1 — GOLD 고객의 이메일만
    // =================================================================
    //
    // 해설:
    //   select() 에 표현식을 하나만 넣으면 그 표현식의 타입이 그대로 제네릭 타입이 됩니다.
    //   customer.email 은 StringPath 이므로 JPAQuery<String> 이 되고 List<String> 이 나옵니다.
    //   Tuple 로 감싸이는 것은 표현식이 "둘 이상" 일 때뿐입니다.
    //
    //   이 문제의 진짜 확인 포인트는 반환 타입이 아니라 생성 SQL 입니다.
    //   select c1_0.email 처럼 컬럼이 딱 하나여야 합니다.
    //   여기서 selectFrom(customer) 로 읽은 뒤 자바에서 getEmail() 을 부르면
    //   결과는 같지만 컬럼 9개를 다 읽고 엔티티 9개를 영속성 컨텍스트에 등록하고
    //   스냅샷 9벌을 만든 뒤 이메일만 쓰고 버리는 셈이 됩니다.
    //   "결과가 같으면 같은 코드" 가 아니라는 것이 이 코스의 전제입니다.
    //
    // 생성 SQL:
    //   select c1_0.email from customers c1_0 where c1_0.grade = ?
    //   바인딩: [1] GOLD
    // 기대 결과: 9건

    @Test
    @DisplayName("정답 1 — GOLD 고객의 이메일만")
    void sol1() {
        List<String> emails = queryFactory
                .select(customer.email)
                .from(customer)
                .where(customer.grade.eq(Grade.GOLD))
                .fetch();

        System.out.println(emails);
        System.out.println("조회 " + emails.size() + "건");   // 9
    }

    // =================================================================
    // 정답 2 — Tuple 과 constructor 를 나란히
    // =================================================================
    //
    // 해설:
    //   (a) 와 (b) 의 생성 SQL 은 완전히 동일합니다.
    //       select c1_0.name, c1_0.points from customers c1_0 order by c1_0.points desc limit ?
    //       즉 DB 입장에서는 아무 차이가 없습니다. 차이는 오직 "자바에서 무엇으로 받느냐" 입니다.
    //
    //   (c) Tuple 을 밖으로 내보내면 안 되는 이유는 세 가지입니다.
    //       1. Tuple 은 com.querydsl.core.Tuple 입니다. 이걸 반환 타입에 쓰는 순간
    //          서비스와 컨트롤러가 QueryDSL 에 컴파일 의존을 갖습니다.
    //          데이터 접근 기술을 바꾸거나 QueryDSL 버전을 올릴 때 전 계층이 영향을 받습니다.
    //       2. Tuple 에서 값을 꺼내려면 QCustomer.customer.name 같은 Q타입 상수가 필요합니다.
    //          컨트롤러가 Q타입을 import 하는 순간 계층 분리는 사실상 끝난 것입니다.
    //       3. Tuple 은 "무엇이 들어 있는지" 를 타입으로 알려주지 않습니다.
    //          t.get(customer.email) 이 null 을 돌려주는 것이 "값이 null" 인지
    //          "select 에 안 넣었음" 인지 호출자가 구분할 수 없습니다.
    //
    //       Tuple 은 리포지토리 내부에서만 쓰고, 경계를 넘을 때는 DTO 로 바꾸십시오.
    //       애초에 처음부터 DTO 로 받으면 변환 코드조차 필요 없습니다.

    @Test
    @DisplayName("정답 2 — Tuple 과 constructor")
    void sol2() {
        // (a) Tuple 버전
        List<Tuple> tuples = queryFactory
                .select(customer.name, customer.points)
                .from(customer)
                .orderBy(customer.points.desc())
                .limit(5)
                .fetch();

        for (Tuple t : tuples) {
            System.out.println(t.get(customer.name) + " / " + t.get(customer.points));
        }

        // (b) Projections.constructor 버전 — SQL 은 위와 완전히 동일합니다
        List<NamePointDto> dtos = queryFactory
                .select(Projections.constructor(NamePointDto.class,
                        customer.name,
                        customer.points))
                .from(customer)
                .orderBy(customer.points.desc())
                .limit(5)
                .fetch();

        dtos.forEach(System.out::println);
    }

    // =================================================================
    // 정답 3 — 조용한 null 을 고치기
    // =================================================================
    //
    // 해설:
    //   이 문제의 핵심은 "필드가 두 개" 라는 것입니다.
    //   WrongNameDto 의 필드는 userName 과 homeCity 이고,
    //   표현식의 이름은 name 과 city 입니다. 둘 다 안 맞습니다.
    //
    //   많은 사람이 customer.name.as("userName") 만 고치고 넘어갑니다.
    //   그러면 userName 은 채워지고 homeCity 는 여전히 null 입니다.
    //   출력에 값이 하나라도 보이면 "고쳤다" 고 착각하기 쉽습니다.
    //   이 함정이 위험한 이유가 정확히 여기 있습니다 —
    //   부분적으로 성공하기 때문에 부분적으로 실패한 것이 눈에 안 띕니다.
    //
    //   생성 SQL 을 바꾸지 말라는 제약이 붙은 이유는,
    //   "DTO 필드명에 맞춰 엔티티 필드를 고친다" 나 "Tuple 로 받아서 수동 매핑한다" 같은
    //   우회를 막고 as() 라는 정답으로 좁히기 위해서입니다.
    //
    //   as() 는 SQL 에 나가지 않으므로 고치기 전후의 생성 SQL 이 한 글자도 다르지 않습니다.
    //   이것을 직접 확인하는 것이 이 문제의 마지막 관문입니다.
    //
    // 생성 SQL (고치기 전/후 동일):
    //   select c1_0.name, c1_0.city from customers c1_0
    // 기대 결과: WrongNameDto(userName=김서준, homeCity=서울) ... 30건

    @Test
    @DisplayName("정답 3 — as() 를 두 곳 모두에")
    void sol3() {
        List<WrongNameDto> result = queryFactory
                .select(Projections.fields(WrongNameDto.class,
                        customer.name.as("userName"),     // ← 하나만 고치면 안 됩니다
                        customer.city.as("homeCity")))    // ← 이쪽도
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);

        long broken = result.stream()
                .filter(d -> d.toString().contains("null"))
                .count();
        System.out.println("null 이 남은 건수 = " + broken);   // 0 이어야 정답
    }

    // =================================================================
    // 정답 4 — @QueryProjection 으로 주문 요약 DTO
    // =================================================================
    //
    // 해설:
    //   @QueryProjection 을 생성자에 붙이면 APT 가 QSolution_OrderSummaryDto 를 만듭니다
    //   (중첩 클래스라 바깥 클래스명이 접두사로 붙습니다. 별도 파일이면 QOrderSummaryDto).
    //   생성된 클래스의 생성자 시그니처는
    //     (Expression<Long>, Expression<String>, Expression<String>, Expression<BigDecimal>)
    //   로 박혀 있어서, 인자 개수나 타입을 틀리면 컴파일이 안 됩니다.
    //
    //   customerName 을 얻으려면 orders 와 customers 를 조인해야 합니다.
    //   order.customer.name 처럼 연관을 타고 들어가도 동작하지만
    //   Hibernate 가 암시적 조인을 만들어 넣으므로 어떤 조인이 생길지 통제할 수 없습니다.
    //   join(order.customer, customer) 로 명시하는 습관을 들이십시오 (Step 06 에서 자세히).
    //
    // 생성 SQL:
    //   select o1_0.order_id, c1_0.name, o1_0.shipping_city, o1_0.total_amount
    //   from orders o1_0
    //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
    //   order by o1_0.total_amount desc
    //   limit ?
    //   바인딩: [1] 5

    @Test
    @DisplayName("정답 4 — @QueryProjection")
    void sol4() {
        List<OrderSummaryDto> result = queryFactory
                .select(new QSolution_OrderSummaryDto(
                        order.id,
                        customer.name,
                        order.shippingCity,
                        order.totalAmount))
                .from(order)
                .join(order.customer, customer)
                .orderBy(order.totalAmount.desc())
                .limit(5)
                .fetch();

        result.forEach(System.out::println);
    }

    // =================================================================
    // 정답 5 — 순서 뒤바뀜을 두 방식에서 비교  ★ 이 파일의 하이라이트
    // =================================================================
    //
    // 해설:
    //   (a) Projections.constructor 버전
    //       SwappedSummaryDto 의 생성자는 (Long, String shippingCity, String customerName, BigDecimal) 인데
    //       쿼리는 (order.id, customer.name, order.shippingCity, order.totalAmount) 순서 그대로입니다.
    //       두 String 파라미터의 "타입" 은 완벽히 일치하므로 생성자를 정확히 찾아 호출합니다.
    //       컴파일 성공, 실행 성공, 예외 없음. 그런데 고객명 자리에 도시가 들어갑니다.
    //
    //       기대 출력:
    //         SwappedSummaryDto(orderId=..., customerName=서울, shippingCity=정  훈, ...)
    //
    //       화면에는 "서울 님, 주문해 주셔서 감사합니다" 가 뜹니다.
    //       null 은 눈에 띄지만 뒤바뀐 값은 그럴듯해 보입니다. 이쪽이 더 오래 살아남습니다.
    //
    //   (b) @QueryProjection 버전
    //       DTO 생성자 순서를 바꾸면 QSolution_OrderSummaryDto 도 재생성되어
    //       생성자 시그니처가 (Expression<Long>, Expression<String>, Expression<String>, Expression<BigDecimal>)
    //       "그대로" 유지됩니다. 두 String 의 위치가 바뀐 것뿐이니까요.
    //       따라서 new QSolution_OrderSummaryDto(order.id, customer.name, order.shippingCity, order.totalAmount)
    //       는 여전히 컴파일됩니다.
    //
    //   (c) 결론 — @QueryProjection 의 한계
    //       @QueryProjection 이 잡아 주는 것은 "개수" 와 "타입" 입니다.
    //       ★ 같은 타입 파라미터끼리의 순서 교환은 컴파일러도 못 잡습니다. ★
    //       이건 QueryDSL 의 한계가 아니라 자바 타입 시스템의 한계입니다.
    //       String 두 개는 자바에게 구분 불가능한 값입니다.
    //
    //       그럼에도 @QueryProjection 이 나은 이유는,
    //       DTO 와 Q타입이 한 몸으로 움직여서 타입이 다른 대부분의 실수는 컴파일에서 걸리고,
    //       DTO 를 고치면 Q타입 재생성이 강제되어 "쿼리 쪽도 봐야 한다" 는 신호가 남기 때문입니다.
    //
    //       같은 타입 순서 교환까지 막으려면 타입 시스템에 정보를 더 줘야 합니다.
    //         - 값 객체를 씁니다: CustomerName, ShippingCity 를 각각 record 로 감쌉니다.
    //         - 또는 필드 순서를 절대 바꾸지 않는 것을 팀 규칙으로 못 박고,
    //           DTO 매핑 테스트에서 실제 값을 assert 합니다
    //           (예: assertThat(dto.getShippingCity()).isIn("서울","부산","대구","인천","광주","대전")).

    @Test
    @DisplayName("정답 5 (a) — constructor 는 조용히 값이 교차한다")
    void sol5a() {
        List<SwappedSummaryDto> result = queryFactory
                .select(Projections.constructor(SwappedSummaryDto.class,
                        order.id,
                        customer.name,          // → shippingCity 자리로 들어간다
                        order.shippingCity,     // → customerName 자리로 들어간다
                        order.totalAmount))
                .from(order)
                .join(order.customer, customer)
                .orderBy(order.totalAmount.desc())
                .limit(5)
                .fetch();

        result.forEach(System.out::println);
        // 예외가 안 나는 것이 이 메서드의 관전 포인트입니다.
    }

    @Test
    @DisplayName("정답 5 (b) — 타입이 다르면 @QueryProjection 이 컴파일에서 잡는다")
    void sol5b() {
        // 아래 주석을 풀면 컴파일 에러가 납니다.
        // 이것이 @QueryProjection 이 실제로 잡아 주는 범위입니다.
        //
        // new QSolution_OrderSummaryDto(order.id, customer.name, order.shippingCity);
        //   → error: constructor QSolution_OrderSummaryDto cannot be applied to given types;
        //            actual and formal argument lists differ in length
        //
        // new QSolution_OrderSummaryDto(order.id, customer.name, order.totalAmount, order.shippingCity);
        //   → error: incompatible types:
        //            NumberPath<BigDecimal> cannot be converted to Expression<String>
        //
        // 반면 customer.name 과 order.shippingCity 의 자리를 맞바꾸는 것은
        // 둘 다 Expression<String> 이므로 컴파일됩니다. 이것이 한계입니다.

        System.out.println("주석을 풀어 컴파일 에러를 직접 확인하십시오.");
    }

    // =================================================================
    // 정답 6 — 서브쿼리 결과를 DTO 필드로
    // =================================================================
    //
    // 해설:
    //   포인트가 두 개입니다.
    //
    //   1. 서브쿼리에는 .as() 메서드가 없습니다.
    //      JPAExpressions.select(...) 가 돌려주는 것은 JPQLQuery 이고
    //      StringPath 처럼 as() 를 갖고 있지 않습니다.
    //      그래서 ExpressionUtils.as(표현식, "이름") 으로 바깥에서 감싸 이름을 붙입니다.
    //      이걸 빼먹으면 5-11 절에서 본 것처럼 reviewCount 가 조용히 null 이 됩니다.
    //      SQL 은 정상적으로 나가서 값도 가져오는데 넣을 자리를 못 찾아 버려집니다.
    //
    //   2. count() 는 매칭이 없으면 0 을 돌려줍니다. COALESCE 가 필요 없습니다.
    //      이것은 스칼라 서브쿼리이기 때문입니다 — 서브쿼리가 독립적으로 실행되어
    //      "0건을 세었다" 는 결과 0 을 반환합니다.
    //      같은 것을 leftJoin 으로 하면 다릅니다.
    //        leftJoin(customer.reviews, review) 후 review.count() 를 하면
    //        NULL 확장 행 때문에 값이 1 이 되거나(count(*) 계열),
    //        MySQL8 코스 Step 07 의 7-3 절에서 본 "COUNT(*) 함정" 과 같은 문제가 생깁니다.
    //      이 대조는 Step 06 에서 다시 다룹니다.
    //
    //   3. 이름 매칭 방식(fields)을 쓸 거면 두 표현식 모두에 이름이 필요합니다.
    //      customer.name 은 as("userName"), 서브쿼리는 ExpressionUtils.as(..., "reviewCount").
    //      사실 이 쿼리는 Projections.constructor 로 쓰는 게 더 안전합니다 —
    //      서브쿼리처럼 "이름이 없는 표현식" 을 다룰 때는 순서 기반이 자연스럽습니다.
    //
    // 생성 SQL:
    //   select c1_0.name,
    //          (select count(r1_0.review_id) from reviews r1_0
    //            where r1_0.customer_id = c1_0.customer_id)
    //   from customers c1_0
    //   order by 2 desc, c1_0.customer_id asc
    //
    // 기대 결과: 30건. 상위 4명은 reviewCount > 0, 나머지 26명은 0.

    @Test
    @DisplayName("정답 6 — ExpressionUtils.as + 스칼라 서브쿼리")
    void sol6() {
        var reviewCount = JPAExpressions
                .select(review.count())
                .from(review)
                .where(review.customer.eq(customer));

        List<CustomerReviewDto> result = queryFactory
                .select(Projections.fields(CustomerReviewDto.class,
                        customer.name.as("userName"),
                        ExpressionUtils.as(reviewCount, "reviewCount")))
                .from(customer)
                .orderBy(customer.id.asc())
                .fetch();

        // 정렬 기준이 서브쿼리 결과라 SQL 로 하려면 orderBy 에 같은 서브쿼리를 한 번 더 넣어야 합니다
        // (JPQL 은 select 별칭을 order by 에서 참조할 수 없습니다).
        // 여기서는 결과 검증이 목적이므로 자바에서 정렬합니다.
        result.sort((a, b) -> Long.compare(b.getReviewCount(), a.getReviewCount()));

        result.stream().limit(8).forEach(System.out::println);

        long writers = result.stream().filter(d -> d.getReviewCount() > 0).count();
        long zeros   = result.stream().filter(d -> d.getReviewCount() == 0).count();
        System.out.println("후기 작성 고객 = " + writers + "명, 0건 고객 = " + zeros + "명");
        // 기대: 4명 / 26명. null 이 하나라도 있으면 ExpressionUtils.as 를 빠뜨린 것입니다.
    }

    /** 정답 6 의 constructor 버전 — 서브쿼리에는 이쪽이 더 안전합니다 */
    @Test
    @DisplayName("정답 6 (별해) — constructor 로 쓰면 이름이 필요 없다")
    void sol6Alternative() {
        var reviewCount = JPAExpressions
                .select(review.count())
                .from(review)
                .where(review.customer.eq(customer));

        List<ReviewCountDto> result = queryFactory
                .select(Projections.constructor(ReviewCountDto.class,
                        customer.name,
                        reviewCount))          // as() 불필요 — 순서로 매칭하므로
                .from(customer)
                .orderBy(customer.id.asc())
                .fetch();

        result.stream().limit(8).forEach(System.out::println);
        // 이름 매칭이 없으니 as() 를 빠뜨려 null 이 되는 사고 자체가 발생하지 않습니다.
        // 서브쿼리를 DTO 에 넣을 때 constructor/@QueryProjection 을 권하는 이유입니다.
    }

    // =================================================================
    // DTO 들
    // =================================================================

    public static class NamePointDto {
        private final String name;
        private final Integer points;

        public NamePointDto(String name, Integer points) {
            this.name = name;
            this.points = points;
        }

        @Override public String toString() {
            return "NamePointDto(name=" + name + ", points=" + points + ")";
        }
    }

    public static class WrongNameDto {
        private String userName;
        private String homeCity;

        @Override public String toString() {
            return "WrongNameDto(userName=" + userName + ", homeCity=" + homeCity + ")";
        }
    }

    public static class OrderSummaryDto {
        private final Long orderId;
        private final String customerName;
        private final String shippingCity;
        private final BigDecimal totalAmount;

        @QueryProjection
        public OrderSummaryDto(Long orderId, String customerName,
                               String shippingCity, BigDecimal totalAmount) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.shippingCity = shippingCity;
            this.totalAmount = totalAmount;
        }

        @Override public String toString() {
            return "OrderSummaryDto(orderId=" + orderId + ", customerName=" + customerName
                 + ", shippingCity=" + shippingCity + ", totalAmount=" + totalAmount + ")";
        }
    }

    public static class SwappedSummaryDto {
        private final Long orderId;
        private final String customerName;
        private final String shippingCity;
        private final BigDecimal totalAmount;

        public SwappedSummaryDto(Long orderId, String shippingCity,
                                 String customerName, BigDecimal totalAmount) {
            this.orderId = orderId;
            this.shippingCity = shippingCity;
            this.customerName = customerName;
            this.totalAmount = totalAmount;
        }

        @Override public String toString() {
            return "SwappedSummaryDto(orderId=" + orderId + ", customerName=" + customerName
                 + ", shippingCity=" + shippingCity + ", totalAmount=" + totalAmount + ")";
        }
    }

    public static class CustomerReviewDto {
        private String userName;
        private Long reviewCount;

        public String getUserName() { return userName; }
        public Long getReviewCount() { return reviewCount; }

        @Override public String toString() {
            return "CustomerReviewDto(userName=" + userName + ", reviewCount=" + reviewCount + ")";
        }
    }

    public static class ReviewCountDto {
        private final String userName;
        private final Long reviewCount;

        public ReviewCountDto(String userName, Long reviewCount) {
            this.userName = userName;
            this.reviewCount = reviewCount;
        }

        @Override public String toString() {
            return "ReviewCountDto(userName=" + userName + ", reviewCount=" + reviewCount + ")";
        }
    }
}
