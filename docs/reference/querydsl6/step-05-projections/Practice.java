package com.example.shop.step05;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Projections;
import com.querydsl.core.annotations.QueryProjection;
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
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;

/**
 * Step 05 — 프로젝션과 DTO : 본문 예제 모음
 *
 * 실행 전 확인:
 *   application.yml 에 아래 두 줄이 켜져 있어야 SQL 과 바인딩이 모두 보입니다.
 *     logging.level.org.hibernate.SQL: debug
 *     logging.level.org.hibernate.orm.jdbc.bind: trace
 *
 *   build.gradle 에 아래가 있어야 @QueryProjection DTO 의 Q타입이 테스트 소스에서 생성됩니다.
 *     testAnnotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
 *
 * 이 파일의 목적은 "결과가 맞는지" 확인하는 것이 아니라
 * "어떤 SQL 이 나가는지" 를 눈으로 보는 것입니다. 콘솔의 SQL 을 본문과 한 줄씩 대조하십시오.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [5-1] 프로젝션이란 — 엔티티 통째 조회의 비용
    // =================================================================

    @Test
    @DisplayName("[5-1] 엔티티 통째 조회 — 전 컬럼 SELECT + 영속성 컨텍스트 등록")
    void entityFullFetch() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .fetch();

        System.out.println("조회 " + result.size() + "건");

        // 영속성 컨텍스트에 실제로 들어갔는지 확인합니다.
        // 엔티티로 읽으면 true, 프로젝션으로 읽으면 애초에 엔티티가 아니므로 확인 대상이 아닙니다.
        System.out.println("영속 상태? " + em.contains(result.get(0)));
    }

    @Test
    @DisplayName("[5-1] 프로젝션 — 필요한 컬럼만. select 절이 짧아진 것을 확인")
    void projectionTwoColumns() {
        List<Tuple> result = queryFactory
                .select(customer.name, customer.city)
                .from(customer)
                .fetch();

        System.out.println("조회 " + result.size() + "건");
        // 생성 SQL:
        //   select c1_0.name, c1_0.city from customers c1_0
        // 바로 위 entityFullFetch() 의 SQL 과 select 절 길이를 비교하십시오.
    }

    // =================================================================
    // [5-2] 단일 컬럼 프로젝션
    // =================================================================

    @Test
    @DisplayName("[5-2] 단일 컬럼 — List<String> 이 바로 나온다")
    void singleColumn() {
        List<String> names = queryFactory
                .select(customer.name)
                .from(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        System.out.println(names);
        // 기대: [김서준, 류하나, 정  훈, 배채영]  (VIP 4명)
    }

    @Test
    @DisplayName("[5-2] 단일 컬럼 — 숫자 타입")
    void singleColumnNumber() {
        List<Integer> points = queryFactory
                .select(customer.points)
                .from(customer)
                .orderBy(customer.points.desc())
                .limit(5)
                .fetch();

        System.out.println(points);
    }

    @Test
    @DisplayName("[5-2] selectDistinct — SQL 에 distinct 가 붙는다")
    void singleColumnDistinct() {
        List<String> cities = queryFactory
                .selectDistinct(customer.city)
                .from(customer)
                .orderBy(customer.city.asc())
                .fetch();

        System.out.println(cities);
        // 기대: [광주, 대구, 대전, 부산, 서울, 인천]
    }

    // =================================================================
    // [5-3] Tuple
    // =================================================================

    @Test
    @DisplayName("[5-3] Tuple — select 에 넣은 표현식을 그대로 키로 쓴다")
    void tupleProjection() {
        List<Tuple> result = queryFactory
                .select(customer.name, customer.city, customer.points)
                .from(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        for (Tuple t : result) {
            System.out.println(t.get(customer.name) + " / "
                             + t.get(customer.city) + " / "
                             + t.get(customer.points));
        }
    }

    @Test
    @DisplayName("[5-3] Tuple — select 에 없는 표현식으로 꺼내면 예외가 아니라 null")
    void tupleMissingKey() {
        Tuple t = queryFactory
                .select(customer.name, customer.city)
                .from(customer)
                .fetchFirst();

        // email 은 select 에 넣지 않았습니다. 예외가 아니라 null 이 나옵니다.
        System.out.println("email = " + t.get(customer.email));   // null
    }

    // =================================================================
    // [5-4] Projections.bean — setter 기반
    // =================================================================

    @Test
    @DisplayName("[5-4] Projections.bean — 기본 생성자 + setter 로 채운다")
    void projectionBean() {
        List<BeanDto> result = queryFactory
                .select(Projections.bean(BeanDto.class,
                        customer.name,
                        customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);
        System.out.println("조회 " + result.size() + "건");
    }

    // =================================================================
    // [5-5] Projections.fields — 필드 직접 주입
    // =================================================================

    @Test
    @DisplayName("[5-5] Projections.fields — getter/setter 없이 필드에 직접 넣는다")
    void projectionFields() {
        List<FieldDto> result = queryFactory
                .select(Projections.fields(FieldDto.class,
                        customer.name,
                        customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);
        // 생성 SQL 은 projectionBean() 과 완전히 동일합니다. 차이는 자바 매핑뿐입니다.
    }

    // =================================================================
    // [5-6] ⚠️ 핵심 함정 — 이름이 안 맞으면 조용히 null
    //       아래 두 메서드는 반드시 이 순서로 실행하십시오.
    // =================================================================

    @Test
    @DisplayName("[5-6] ⚠️ 이름 불일치 — 예외도 경고도 없이 userName 만 null")
    void fieldsWithWrongName() {
        List<CustomerDto> result = queryFactory
                .select(Projections.fields(CustomerDto.class,
                        customer.name,     // 표현식 이름은 "name". DTO 필드는 userName.
                        customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(5).forEach(System.out::println);
        System.out.println("조회 " + result.size() + "건");

        // 기대 출력:
        //   CustomerDto(userName=null, city=서울)
        //   CustomerDto(userName=null, city=부산)
        //   ... 30건 전부 userName=null
        //
        // SQL 은 완벽합니다. name 컬럼을 정확히 읽어 왔습니다.
        // 그 값을 넣을 필드를 못 찾아서 "아무것도 하지 않고" 넘어간 것입니다.

        long nullCount = result.stream().filter(d -> d.getUserName() == null).count();
        System.out.println("userName 이 null 인 건수 = " + nullCount + " / " + result.size());
    }

    @Test
    @DisplayName("[5-6] 처방 1 — as() 별칭. 생성 SQL 은 한 글자도 안 바뀐다")
    void fieldsWithAlias() {
        List<CustomerDto> result = queryFactory
                .select(Projections.fields(CustomerDto.class,
                        customer.name.as("userName"),   // ← 이것 하나
                        customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(5).forEach(System.out::println);

        // 생성 SQL: select c1_0.name, c1_0.city from customers c1_0
        // 바로 위 fieldsWithWrongName() 과 SQL 을 비교하십시오. 동일합니다.
        // as() 는 SQL 의 AS 가 아닙니다. DTO 필드를 찾기 위한 이름표일 뿐입니다.
    }

    @Test
    @DisplayName("[5-6] 처방 2 — ExpressionUtils.as() (as() 메서드가 없는 표현식용)")
    void fieldsWithExpressionUtils() {
        List<CustomerDto> result = queryFactory
                .select(Projections.fields(CustomerDto.class,
                        ExpressionUtils.as(customer.name, "userName"),
                        customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);
        // customer.name.as("userName") 과 완전히 동등합니다. 짧은 쪽을 쓰면 됩니다.
    }

    @Test
    @DisplayName("[5-6] bean 도 똑같다 — setter 이름을 못 찾으면 조용히 스킵")
    void beanWithWrongName() {
        List<BeanWrongNameDto> result = queryFactory
                .select(Projections.bean(BeanWrongNameDto.class,
                        customer.name,     // setName 을 찾는다. DTO 에는 setUserName 뿐이다.
                        customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);
        // 기대: userName=null 이 30건.
        // fields 는 필드 이름을, bean 은 setter 이름을 찾습니다.
        // 찾는 대상만 다를 뿐 "못 찾으면 조용히 넘어간다" 는 동일합니다.
    }

    // =================================================================
    // [5-7] Projections.constructor — 순서와 타입
    // =================================================================

    @Test
    @DisplayName("[5-7] constructor — 이름을 안 보므로 as() 없이도 정상")
    void projectionConstructor() {
        List<CtorDto> result = queryFactory
                .select(Projections.constructor(CtorDto.class,
                        customer.name,     // → 1번째 생성자 인자
                        customer.city))    // → 2번째 생성자 인자
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);
        // DTO 필드가 userName 인데도 정상입니다. 이름을 안 보기 때문입니다.
    }

    @Test
    @DisplayName("[5-7] constructor — 타입이 다르면 런타임에 즉시 터진다 (좋은 실패)")
    void projectionConstructorTypeMismatch() {
        try {
            queryFactory
                    .select(Projections.constructor(CtorDto.class,
                            customer.name,
                            customer.points))   // Integer 인데 생성자 2번째는 String
                    .from(customer)
                    .fetch();
        } catch (Exception e) {
            System.out.println(e.getClass().getName());
            System.out.println(e.getMessage());
            // com.querydsl.core.types.ExpressionException:
            //   No constructor found for class ... with parameters: [String, Integer]
        }
    }

    @Test
    @DisplayName("[5-7] ⚠️ 같은 타입 순서 뒤바뀜 — 예외 없이 값이 교차한다")
    void constructorSwapped() {
        // SwappedDto 의 생성자는 (String city, String userName) 순서입니다.
        // 쿼리는 (name, city) 순서 그대로 두었습니다.
        List<SwappedDto> result = queryFactory
                .select(Projections.constructor(SwappedDto.class,
                        customer.name,     // → city 자리에 들어간다
                        customer.city))    // → userName 자리에 들어간다
                .from(customer)
                .fetch();

        result.stream().limit(5).forEach(System.out::println);

        // 기대 출력:
        //   SwappedDto(userName=서울, city=김서준)
        //   SwappedDto(userName=부산, city=이지은)
        //   ... 30건 전부 교차
        //
        // ★ 이 메서드의 관전 포인트는 "예외가 안 난다" 는 것입니다.
        //   타입이 둘 다 String 이라 생성자를 정확히 찾아 호출합니다.
        //   null 은 눈에 띄기라도 하지만 뒤바뀐 값은 그럴듯해 보입니다. 이쪽이 더 위험합니다.
        //   그리고 이 함정은 as() 로 못 막습니다 — 애초에 이름을 안 보니까요.
    }

    // =================================================================
    // [5-8] @QueryProjection — 컴파일 시점 검증
    // =================================================================

    @Test
    @DisplayName("[5-8] @QueryProjection — new QCustomerQpDto(...) 로 컴파일 시점에 검증")
    void queryProjection() {
        List<CustomerQpDto> result = queryFactory
                .select(new QPractice_CustomerQpDto(customer.name, customer.city))
                .from(customer)
                .fetch();

        result.stream().limit(3).forEach(System.out::println);

        // 중첩 클래스의 Q타입 이름은 QPractice_CustomerQpDto 형태가 됩니다
        // (바깥 클래스명 + '_' + 중첩 클래스명). 별도 파일로 빼면 QCustomerQpDto 입니다.
        //
        // 아래 두 줄의 주석을 풀면 "컴파일이 안 됩니다". 그것이 이 절의 전부입니다.
        //   new QPractice_CustomerQpDto(customer.name);                    // 인자 개수 부족
        //   new QPractice_CustomerQpDto(customer.name, customer.points);   // 타입 불일치
    }

    // =================================================================
    // [5-10] 중첩 DTO 프로젝션
    // =================================================================

    @Test
    @DisplayName("[5-10] 여러 엔티티의 값을 하나의 DTO 로 — 조인 필요")
    void nestedProjection() {
        List<OrderLineDto> result = queryFactory
                .select(new QPractice_OrderLineDto(
                        order.id,
                        customer.name,
                        product.name,
                        orderItem.quantity))
                .from(orderItem)
                .join(orderItem.order, order)
                .join(order.customer, customer)
                .join(orderItem.product, product)
                .orderBy(order.id.asc(), product.id.asc())
                .limit(8)
                .fetch();

        result.forEach(System.out::println);

        // order_id = 1 이 두 줄인 것에 주목하십시오.
        // 주문 1건에 상품이 2개(1:N)라서 주문 헤더 정보가 반복됩니다.
        // 이 "행 뻥튀기(fan-out)" 는 Step 06 의 6-4 절에서 본격적으로 다룹니다.
    }

    @Test
    @DisplayName("[5-10] Projections 중첩 — SQL 은 평평하고 조립만 중첩된다")
    void projectionInsideProjection() {
        List<OrderWithCustomerDto> result = queryFactory
                .select(Projections.constructor(OrderWithCustomerDto.class,
                        order.id,
                        order.totalAmount,
                        Projections.constructor(CtorDto.class,
                                customer.name,
                                customer.city)))
                .from(order)
                .join(order.customer, customer)
                .limit(3)
                .fetch();

        result.forEach(System.out::println);
        // 생성 SQL 의 select 절은 컬럼 4개로 평평합니다.
        // 중첩은 자바 객체를 조립하는 단계에서만 일어납니다.
    }

    // =================================================================
    // [5-11] 서브쿼리 결과를 DTO 필드로
    // =================================================================

    @Test
    @DisplayName("[5-11] ExpressionUtils.as + 스칼라 서브쿼리")
    void subqueryProjection() {
        List<CustomerOrderCountDto> result = queryFactory
                .select(Projections.fields(CustomerOrderCountDto.class,
                        customer.name.as("userName"),
                        ExpressionUtils.as(
                                JPAExpressions.select(order.count())
                                              .from(order)
                                              .where(order.customer.eq(customer)),
                                "orderCount")))
                .from(customer)
                .orderBy(customer.id.asc())
                .limit(5)
                .fetch();

        result.forEach(System.out::println);
        // 기대: 전원 orderCount=20 (고객 30명 × 20건 = 주문 600건)
    }

    @Test
    @DisplayName("[5-11] ⚠️ 서브쿼리에 별칭을 안 붙이면 — orderCount 가 null")
    void subqueryProjectionWithoutAlias() {
        List<CustomerOrderCountDto> result = queryFactory
                .select(Projections.fields(CustomerOrderCountDto.class,
                        customer.name.as("userName"),
                        JPAExpressions.select(order.count())
                                      .from(order)
                                      .where(order.customer.eq(customer))))   // 별칭 없음
                .from(customer)
                .orderBy(customer.id.asc())
                .limit(5)
                .fetch();

        result.forEach(System.out::println);
        // 기대: orderCount=null 이 5건.
        // 서브쿼리 표현식에는 애초에 이름이라는 게 없으니 매칭할 필드를 못 찾습니다.
        // SQL 은 정상적으로 나가서 값도 가져왔는데, 그 값이 버려집니다.
        //
        // → 서브쿼리를 DTO 에 넣을 때는 이름 매칭 방식(fields/bean)을 피하는 게 안전합니다.
        //   이름이 없는 표현식이라도 "순서" 는 언제나 있으니까요.
    }

    // =================================================================
    // [5-12] 연산 결과를 DTO 로
    // =================================================================

    @Test
    @DisplayName("[5-12] 곱셈 프로젝션 — BigDecimal 을 왼쪽에 두는 것이 안전")
    void arithmeticProjection() {
        List<OrderItemAmountDto> result = queryFactory
                .select(new QPractice_OrderItemAmountDto(
                        orderItem.order.id,
                        orderItem.product.name,
                        orderItem.quantity,
                        orderItem.unitPrice.multiply(orderItem.quantity)))   // BigDecimal 먼저
                .from(orderItem)
                .join(orderItem.product, product)
                .orderBy(orderItem.unitPrice.multiply(orderItem.quantity).desc())
                .limit(5)
                .fetch();

        result.forEach(System.out::println);

        // 생성 SQL:
        //   select oi1_0.order_id, p1_0.name, oi1_0.quantity,
        //          oi1_0.unit_price * oi1_0.quantity
        //   from order_items oi1_0
        //   join products p1_0 on p1_0.product_id = oi1_0.product_id
        //   order by oi1_0.unit_price * oi1_0.quantity desc
        //   limit ?
        //
        // orderItem.order.id 는 조인을 만들지 않습니다 — order_id 는 order_items 의 FK 컬럼이라
        // 그대로 읽으면 됩니다. 반면 orderItem.product.name 은 products 를 봐야 하므로
        // 조인이 필요하고, 명시하지 않으면 Hibernate 가 암시적 조인을 만들어 넣습니다.
        // 어떤 조인이 생길지 통제할 수 없으니 항상 명시적으로 조인하십시오.

        // 아래 주석을 풀면 quantity(Integer) 를 왼쪽에 둔 버전입니다.
        // 타입 추론이 NumberExpression<Integer> 로 잡히면
        // "No constructor found ... [Long, String, Integer, Integer]" 가 날 수 있습니다.
        // 타입 추론이 어긋나는 지점을 직접 확인하고 싶을 때만 푸십시오.
        //
        // queryFactory.select(new QPractice_OrderItemAmountDto(
        //         orderItem.order.id, orderItem.product.name, orderItem.quantity,
        //         orderItem.quantity.multiply(orderItem.unitPrice)))
        //     .from(orderItem).join(orderItem.product, product).limit(5).fetch();
    }

    // =================================================================
    // [5-13] MySQL8 코스 대조 — as() 는 SQL 에 나가지 않는다
    // =================================================================

    @Test
    @DisplayName("[5-13] as() 는 SQL 별칭이 아니다 — 생성 SQL 에 as 가 없는 것을 확인")
    void aliasIsNotSqlAlias() {
        queryFactory
                .select(customer.name.as("userName"), customer.city)
                .from(customer)
                .limit(3)
                .fetch();

        // 생성 SQL:
        //   select c1_0.name, c1_0.city from customers c1_0 limit ?
        //
        // SQL 에는 as 가 없습니다.
        // SQL 의 AS 는 결과셋 컬럼 이름을 바꾸지만,
        // QueryDSL 의 as() 는 자바 객체 조립을 위한 표시일 뿐입니다.
        // MySQL8 코스에서 SELECT name AS user_name 을 쓰던 감각으로 접근하면 오해합니다.
    }

    @Test
    @DisplayName("[5-13] 참고 — 후기 작성 고객 수 (연습문제 6번 검산용)")
    void reviewWriterCount() {
        Long writers = queryFactory
                .select(customer.countDistinct())
                .from(review)
                .join(review.customer, customer)
                .fetchOne();

        System.out.println("후기를 쓴 고객 수 = " + writers);   // 기대: 4
    }

    // =================================================================
    // DTO 들 — 본문에 등장한 것을 모두 중첩 클래스로 담았습니다
    // =================================================================

    /** [5-4] bean 용 — 기본 생성자 + setter 필수 */
    public static class BeanDto {
        private String name;
        private String city;

        public BeanDto() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        @Override public String toString() {
            return "BeanDto(name=" + name + ", city=" + city + ")";
        }
    }

    /** [5-5] fields 용 — getter/setter 불필요 */
    public static class FieldDto {
        private String name;
        private String city;

        @Override public String toString() {
            return "FieldDto(name=" + name + ", city=" + city + ")";
        }
    }

    /** [5-6] 이름이 안 맞는 DTO — 엔티티는 name, 여기는 userName */
    public static class CustomerDto {
        private String userName;
        private String city;

        public String getUserName() { return userName; }
        public String getCity() { return city; }

        @Override public String toString() {
            return "CustomerDto(userName=" + userName + ", city=" + city + ")";
        }
    }

    /** [5-6] bean 버전 — setUserName 만 있고 setName 이 없다 */
    public static class BeanWrongNameDto {
        private String userName;
        private String city;

        public BeanWrongNameDto() {}

        public void setUserName(String userName) { this.userName = userName; }
        public void setCity(String city) { this.city = city; }

        @Override public String toString() {
            return "BeanWrongNameDto(userName=" + userName + ", city=" + city + ")";
        }
    }

    /** [5-7] constructor 용 — final 필드 가능, 기본 생성자 불필요 */
    public static class CtorDto {
        private final String userName;
        private final String city;

        public CtorDto(String userName, String city) {
            this.userName = userName;
            this.city = city;
        }

        @Override public String toString() {
            return "CtorDto(userName=" + userName + ", city=" + city + ")";
        }
    }

    /** [5-7] 파라미터 순서가 뒤집힌 DTO — (city, userName) */
    public static class SwappedDto {
        private final String userName;
        private final String city;

        public SwappedDto(String city, String userName) {   // ← 순서 주의
            this.city = city;
            this.userName = userName;
        }

        @Override public String toString() {
            return "SwappedDto(userName=" + userName + ", city=" + city + ")";
        }
    }

    /** [5-8] @QueryProjection 용 */
    public static class CustomerQpDto {
        private final String userName;
        private final String city;

        @QueryProjection
        public CustomerQpDto(String userName, String city) {
            this.userName = userName;
            this.city = city;
        }

        @Override public String toString() {
            return "CustomerQpDto(userName=" + userName + ", city=" + city + ")";
        }
    }

    /** [5-10] 여러 엔티티의 값을 모은 DTO */
    public static class OrderLineDto {
        private final Long orderId;
        private final String customerName;
        private final String productName;
        private final int quantity;

        @QueryProjection
        public OrderLineDto(Long orderId, String customerName, String productName, int quantity) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.productName = productName;
            this.quantity = quantity;
        }

        @Override public String toString() {
            return "OrderLineDto(orderId=" + orderId + ", customerName=" + customerName
                 + ", productName=" + productName + ", quantity=" + quantity + ")";
        }
    }

    /** [5-10] DTO 안에 DTO */
    public static class OrderWithCustomerDto {
        private final Long orderId;
        private final BigDecimal totalAmount;
        private final CtorDto customer;

        public OrderWithCustomerDto(Long orderId, BigDecimal totalAmount, CtorDto customer) {
            this.orderId = orderId;
            this.totalAmount = totalAmount;
            this.customer = customer;
        }

        @Override public String toString() {
            return "OrderWithCustomerDto(orderId=" + orderId
                 + ", totalAmount=" + totalAmount + ", customer=" + customer + ")";
        }
    }

    /** [5-11] 서브쿼리 결과를 담는 DTO */
    public static class CustomerOrderCountDto {
        private String userName;
        private Long orderCount;

        @Override public String toString() {
            return "CustomerOrderCountDto(userName=" + userName + ", orderCount=" + orderCount + ")";
        }
    }

    /** [5-12] 연산 결과를 담는 DTO */
    public static class OrderItemAmountDto {
        private final Long orderId;
        private final String productName;
        private final int quantity;
        private final BigDecimal lineAmount;

        @QueryProjection
        public OrderItemAmountDto(Long orderId, String productName,
                                  int quantity, BigDecimal lineAmount) {
            this.orderId = orderId;
            this.productName = productName;
            this.quantity = quantity;
            this.lineAmount = lineAmount;
        }

        @Override public String toString() {
            return "OrderItemAmountDto(orderId=" + orderId + ", productName=" + productName
                 + ", quantity=" + quantity + ", lineAmount=" + lineAmount + ")";
        }
    }
}
