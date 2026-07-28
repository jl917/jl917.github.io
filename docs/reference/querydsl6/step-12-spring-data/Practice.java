package com.example.shop.step12;

import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.example.shop.entity.QProduct;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.SimpleExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.QuerydslRepositorySupport;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.querydsl.binding.QuerydslBinderCustomizer;
import org.springframework.data.querydsl.binding.QuerydslBindings;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;

/**
 * Step 12 — Spring Data JPA 통합 : 본문 예제 전체.
 *
 * <p><b>⚠️ 파일 구성에 대하여</b>
 * 이 스텝의 주인공은 리포지토리 인터페이스와 구현체이므로 원래 파일이 여러 개로 나뉩니다.
 * 학습 편의를 위해 이 파일에는 {@code static} 중첩 타입으로 담았지만,
 * <b>실제 프로젝트에서는 반드시 별도 파일로 분리하십시오.</b>
 * <pre>
 * src/main/java/com/example/shop/repository/
 * ├── OrderRepository.java
 * ├── OrderRepositoryCustom.java
 * └── OrderRepositoryImpl.java        ← 이름 규칙이 적용되는 것은 "톱레벨 클래스" 입니다
 * </pre>
 * 중첩 타입으로 두면 Spring Data 의 {@code Impl} 이름 규칙이 적용되는 방식이 달라져
 * 12-3 의 함정을 재현할 수 없습니다.
 * 아래 중첩 타입들은 <b>코드 형태를 보여 주기 위한 것</b>이며,
 * 테스트 메서드들은 {@code JPAQueryFactory} 를 직접 써서 같은 쿼리를 재현합니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [12-2] 커스텀 리포지토리 3층 구조 — 전체 코드
    // =================================================================

    /** 조건 DTO. QueryDSL 의존이 없는 평범한 record 입니다 (12-8). */
    record OrderSearchCond(String customerName, OrderStatus status,
                          String city, BigDecimal minAmount) {
    }

    /**
     * 결과 DTO. 실제 프로젝트에서는 {@code @QueryProjection} 을 붙여
     * {@code QOrderSearchDto} 를 생성해 쓰십시오 (Step 05).
     */
    record OrderSearchDto(Long orderId, LocalDateTime orderDate, OrderStatus status,
                          BigDecimal totalAmount, String customerName, String shippingCity) {
    }

    /**
     * ① 프래그먼트 인터페이스.
     * <p>실제 파일: {@code com/example/shop/repository/OrderRepositoryCustom.java}
     * <p>시그니처에 {@code Predicate} / {@code Tuple} 이 없다는 점을 보십시오 (12-8).
     */
    interface OrderRepositoryCustom {
        Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable);
        long cancelExpired(LocalDateTime cutoff);
    }

    /**
     * ② 구현체. <b>이름이 반드시 {@code OrderRepositoryImpl} 이어야 합니다.</b>
     * <p>실제 파일: {@code com/example/shop/repository/OrderRepositoryImpl.java}
     *
     * <p>동작하는 이름 : {@code OrderRepositoryImpl}, {@code OrderRepositoryCustomImpl}
     * <p>동작하지 않는 이름 :
     * {@code OrderRepositoryQuerydsl}, {@code OrderRepositoryImplementation},
     * {@code CustomOrderRepositoryImpl}, {@code OrderRepositoryIMPL}
     *
     * <p>주입은 <b>생성자 주입</b>입니다. Spring Data 가 이 클래스를 직접 인스턴스화하므로
     * 필드 주입({@code @Autowired})은 null 로 남을 수 있습니다 (12-3-4 ④).
     */
    static class OrderRepositoryImpl implements OrderRepositoryCustom {

        private final JPAQueryFactory queryFactory;
        private final EntityManager em;

        OrderRepositoryImpl(JPAQueryFactory queryFactory, EntityManager em) {
            this.queryFactory = queryFactory;
            this.em = em;
        }

        @Override
        public Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable) {
            List<OrderSearchDto> content = queryFactory
                    .select(com.querydsl.core.types.Projections.constructor(
                            OrderSearchDto.class,
                            order.id, order.orderDate, order.status,
                            order.totalAmount, customer.name, order.shippingCity))
                    .from(order)
                    .join(order.customer, customer)
                    .where(
                            customerNameContains(cond.customerName()),
                            statusEq(cond.status()),
                            cityEq(cond.city()),
                            amountGoe(cond.minAmount()))
                    .orderBy(order.orderDate.desc())
                    .offset(pageable.getOffset())
                    .limit(pageable.getPageSize())
                    .fetch();

            // count 쿼리는 아직 실행하지 않습니다. JPAQuery 를 그대로 넘깁니다 (12-9).
            JPAQuery<Long> countQuery = queryFactory
                    .select(order.count())
                    .from(order)
                    .join(order.customer, customer)
                    .where(
                            customerNameContains(cond.customerName()),
                            statusEq(cond.status()),
                            cityEq(cond.city()),
                            amountGoe(cond.minAmount()));

            return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
        }

        /**
         * 벌크 연산 (Step 11). <b>쓰기 트랜잭션이 필요합니다.</b>
         * 트랜잭션 없이 호출하면 {@code TransactionRequiredException} 이 납니다.
         */
        @Override
        public long cancelExpired(LocalDateTime cutoff) {
            em.flush();
            long updated = queryFactory
                    .update(order)
                    .set(order.status, OrderStatus.CANCELLED)
                    .where(order.status.eq(OrderStatus.PENDING)
                            .and(order.orderDate.before(cutoff)))
                    .execute();
            em.clear();
            return updated;
        }

        // 조건 메서드 분리 — null 반환 시 where 에서 무시됩니다 (Step 10)
        private BooleanExpression customerNameContains(String name) {
            return (name == null || name.isBlank()) ? null : customer.name.contains(name);
        }

        private BooleanExpression statusEq(OrderStatus status) {
            return status == null ? null : order.status.eq(status);
        }

        private BooleanExpression cityEq(String city) {
            return (city == null || city.isBlank()) ? null : order.shippingCity.eq(city);
        }

        private BooleanExpression amountGoe(BigDecimal amount) {
            return amount == null ? null : order.totalAmount.goe(amount);
        }
    }

    /**
     * ③ 메인 리포지토리. JpaRepository 와 커스텀 인터페이스를 <b>둘 다</b> 상속합니다.
     * <p>실제 파일: {@code com/example/shop/repository/OrderRepository.java}
     * <pre>
     * OrderRepository 프록시
     * ├─ save/findById/findAll ...   → SimpleJpaRepository
     * ├─ findByStatus(...)           → 메서드 이름 파싱 → JPQL
     * └─ searchOrders(...)           → OrderRepositoryImpl 로 위임 ★
     * </pre>
     */
    interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {
        List<Order> findByStatus(OrderStatus status);
    }

    /**
     * [12-2] 위 구현체와 동일한 쿼리를 직접 실행해 생성 SQL 을 확인합니다.
     */
    @Test
    @DisplayName("[12-2] 커스텀 리포지토리 구현체와 같은 쿼리")
    void customRepositoryQuery() {
        OrderRepositoryImpl impl = new OrderRepositoryImpl(queryFactory, em);
        OrderSearchCond cond = new OrderSearchCond("류하나", OrderStatus.PAID, "서울", null);

        Page<OrderSearchDto> page = impl.searchOrders(cond, PageRequest.of(0, 10));

        System.out.println("[12-2] total=" + page.getTotalElements()
                + " / content=" + page.getContent().size());
        page.getContent().forEach(d -> System.out.println("  " + d));
        // 생성 SQL:
        // select o1_0.order_id, o1_0.order_date, o1_0.status, o1_0.total_amount,
        //        c1_0.name, o1_0.shipping_city
        // from orders o1_0 join customers c1_0 on c1_0.customer_id=o1_0.customer_id
        // where c1_0.name like ? escape '!' and o1_0.status=? and o1_0.shipping_city=?
        // order by o1_0.order_date desc limit ?, ?
    }

    // =================================================================
    // [12-3] Impl 접미사 규칙
    // =================================================================

    /**
     * [12-3] 이름 규칙 정리. 실행 가능한 코드가 아니라 <b>규칙 자체</b>가 학습 대상입니다.
     *
     * <pre>
     * ✅ OrderRepositoryImpl          리포지토리 인터페이스 이름 + Impl
     * ✅ OrderRepositoryCustomImpl    프래그먼트 인터페이스 이름 + Impl
     * ❌ OrderRepositoryImplementation
     * ❌ OrderRepositoryQuerydsl
     * ❌ OrderRepositoryIMPL          대소문자 불일치
     * ❌ CustomOrderRepositoryImpl    접두사가 아니라 접미사 규칙입니다
     * ❌ OrderRepositoryImpl2
     * ❌ OrdersRepositoryImpl         리포지토리 이름 자체가 다름
     * </pre>
     *
     * <p>어긋났을 때 나는 기동 에러 (전문은 index.md 12-3-2 참고)
     * <pre>
     * org.springframework.beans.factory.BeanCreationException:
     *   Error creating bean with name 'orderRepository' ...
     * Caused by: org.springframework.data.repository.query.QueryCreationException:
     *   Could not create query for public abstract ... searchOrders(...);
     *   Reason: Failed to create query for method ...;
     *   No property 'searchOrders' found for type 'Order'
     * Caused by: org.springframework.data.mapping.PropertyReferenceException:
     *   No property 'searchOrders' found for type 'Order'
     * </pre>
     *
     * <p><b>인과 사슬</b> — 에러 메시지가 원인에서 네 단계 떨어져 있습니다.
     * <pre>
     * ① OrderRepository 프록시 생성 시작
     * ② searchOrders 를 처리할 주체를 찾음
     * ③ 후보 이름 계산: OrderRepositoryImpl / OrderRepositoryCustomImpl
     * ④ 그 이름의 빈이 없음  ← 진짜 원인
     * ⑤ "커스텀 구현이 아니구나 → 메서드 이름 쿼리겠지" 로 판단  ← 함정
     * ⑥ "searchOrders" 파싱
     * ⑦ "Orders" 를 Order 의 프로퍼티로 해석 시도
     * ⑧ 없음 → PropertyReferenceException  ← 에러 메시지가 말하는 지점
     * ⑨ QueryCreationException → BeanCreationException → 기동 실패
     * </pre>
     *
     * <p><b>실패 원인 4가지</b>
     * <pre>
     * 1. 접미사 오타          → 클래스 이름이 정확히 &lt;리포지토리명&gt;Impl 인가
     * 2. 패키지가 스캔 범위 밖 → @EnableJpaRepositories 의 basePackages 아래인가
     * 3. implements 누락       → implements OrderRepositoryCustom 이 있는가
     * 4. 필드 주입             → 생성자 주입으로 바꿀 것
     * </pre>
     *
     * <p><b>접미사 변경</b> (권장하지 않음)
     * <pre>
     * &#64;EnableJpaRepositories(
     *         basePackages = "com.example.shop.repository",
     *         repositoryImplementationPostfix = "Querydsl")   // 기본값 "Impl"
     * </pre>
     */
    @Test
    @DisplayName("[12-3] Impl 접미사 규칙 (문서화)")
    void implPostfixRule() {
        System.out.println("[12-3] 규칙: <리포지토리 인터페이스명> + Impl");
        System.out.println("       No property 'xxx' found for type 'Entity' 를 보면");
        System.out.println("       ① 클래스 이름 접미사 ② 패키지 위치 ③ implements 여부 순으로 확인하십시오.");
    }

    // =================================================================
    // [12-5] QuerydslRepositorySupport
    // =================================================================

    /**
     * [12-5] {@code QuerydslRepositorySupport} 를 상속한 버전.
     *
     * <p>제공하는 것: {@code getEntityManager()}, {@code from(...)},
     * {@code getQuerydsl().applyPagination(pageable, query)}
     *
     * <p>단점
     * <ul>
     *   <li>{@code select} 로 시작할 수 없습니다. {@code from} 뒤에 붙여야 합니다</li>
     *   <li>{@code EntityManager} 가 <b>세터 주입</b>이라 생성자에서 쓸 수 없습니다</li>
     *   <li>상속 슬롯을 소모합니다</li>
     *   <li>{@code applyPagination} 의 Sort 자동 변환에는 <b>화이트리스트가 없습니다</b></li>
     * </ul>
     */
    static class OrderRepositorySupportImpl extends QuerydslRepositorySupport
            implements OrderRepositoryCustom {

        OrderRepositorySupportImpl() {
            super(Order.class);
            // ❌ 여기서 from(order) 를 호출하면 EntityManager 가 아직 null 이라 NPE 입니다.
        }

        @Override
        public Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable) {
            JPQLQuery<Order> query = from(order)          // select 가 아니라 from 으로 시작
                    .join(order.customer, customer)
                    .where(cond.status() == null ? null : order.status.eq(cond.status()));

            // Sort → OrderSpecifier 변환 + offset/limit 을 한 번에
            JPQLQuery<Order> paged = getQuerydsl().applyPagination(pageable, query);

            List<OrderSearchDto> content = paged.select(
                            com.querydsl.core.types.Projections.constructor(
                                    OrderSearchDto.class,
                                    order.id, order.orderDate, order.status,
                                    order.totalAmount, customer.name, order.shippingCity))
                    .fetch();

            return PageableExecutionUtils.getPage(content, pageable, query::fetchCount);
            // ⚠️ fetchCount 는 QueryDSL 5.0 부터 deprecated 입니다.
            //    실무에서는 select(order.count()) 로 count 쿼리를 직접 작성하십시오 (Step 09).
        }

        @Override
        public long cancelExpired(LocalDateTime cutoff) {
            throw new UnsupportedOperationException("예제에서는 생략");
        }
    }

    /**
     * [12-5] applyPagination 이 Spring Sort 를 orderBy 로 바꿔 주는 것을 확인합니다.
     * <p>Step 10 에서는 이 변환을 손으로 했습니다. 편리하지만 <b>화이트리스트가 사라집니다.</b>
     * {@code ?sort=customer.email,desc} 같은 요청이 그대로 order by 로 나갑니다.
     */
    @Test
    @DisplayName("[12-5] applyPagination — Sort 자동 변환")
    void applyPaginationDemo() {
        Pageable pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "orderDate"));

        List<Order> content = queryFactory
                .selectFrom(order)
                .join(order.customer, customer)
                .where(order.status.eq(OrderStatus.PAID))
                .orderBy(order.orderDate.desc())          // applyPagination 이 만들어 주는 부분
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        System.out.println("[12-5] 2페이지 " + content.size() + "건");
        // 생성 SQL: ... order by o1_0.order_date desc limit ?, ?   바인딩 [2] 10 [3] 10
    }

    // =================================================================
    // [12-6] QuerydslPredicateExecutor
    // =================================================================

    /**
     * [12-6] 코드 0줄로 검색 메서드가 생깁니다.
     * <pre>
     * public interface ProductRepository extends
     *         JpaRepository&lt;Product, Long&gt;,
     *         QuerydslPredicateExecutor&lt;Product&gt; { }
     * </pre>
     * 생기는 메서드: findOne(Predicate), findAll(Predicate), findAll(Predicate, Pageable),
     * count(Predicate), exists(Predicate) ...
     *
     * <p><b>한계 4가지</b>
     * <ol>
     *   <li>조인을 명시할 수 없습니다 (묵시적 조인만. left join 불가)</li>
     *   <li>fetch join 불가 → 1+N 을 막을 수단이 없습니다</li>
     *   <li>DTO 프로젝션 불가 → 반환 타입이 엔티티로 고정</li>
     *   <li>Predicate 를 리포지토리 밖에서 만들게 되어 <b>QueryDSL 의존이 번집니다</b></li>
     * </ol>
     */
    interface ProductRepository extends
            JpaRepository<Product, Long>,
            QuerydslPredicateExecutor<Product>,
            QuerydslBinderCustomizer<QProduct> {

        /**
         * [12-7] 화이트리스트 바인딩.
         * {@code excludeUnlistedProperties(true)} 가 정책을 "나열한 것만 허용" 으로 뒤집습니다.
         */
        @Override
        default void customize(QuerydslBindings bindings, QProduct product) {
            bindings.excludeUnlistedProperties(true);                    // ★ 기본 바인딩 차단
            bindings.including(product.name, product.status, product.price);

            bindings.bind(product.name).first(StringExpression::contains);
            bindings.bind(product.status).first(SimpleExpression::eq);
            bindings.bind(product.price).all((path, values) -> {
                Iterator<? extends BigDecimal> it = values.iterator();
                BigDecimal from = it.next();
                return it.hasNext()
                        ? Optional.of(path.between(from, it.next()))
                        : Optional.of(path.goe(from));
            });

            bindings.excluding(product.cost);                            // 원가는 명시적으로 차단
        }
    }

    /**
     * [12-6] Predicate 로 검색. count 쿼리까지 자동으로 나갑니다.
     */
    @Test
    @DisplayName("[12-6] QuerydslPredicateExecutor 와 동일한 쿼리")
    void predicateExecutorEquivalent() {
        Predicate cond = product.status.eq(ProductStatus.ON_SALE)
                .and(product.price.between(new BigDecimal("10000"), new BigDecimal("500000")));

        List<Product> content = queryFactory
                .selectFrom(product)
                .where(cond)
                .orderBy(product.price.desc())
                .offset(0).limit(10)
                .fetch();

        Long total = queryFactory.select(product.count()).from(product).where(cond).fetchOne();

        System.out.println("[12-6] total=" + total + " / content=" + content.size());
    }

    /**
     * [12-6] 한계 ① — 묵시적 조인. 조인 종류를 통제할 수 없습니다.
     */
    @Test
    @DisplayName("[12-6] ⚠️ 한계 ① 묵시적 조인만 가능")
    void limitationImplicitJoin() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(product.category.name.eq("노트북"))   // 묵시적 조인 발생
                .fetch();

        System.out.println("[12-6] 묵시적 조인 결과 = " + result.size() + "건");
        // 생성 SQL:
        // select p1_0.product_id, ... from products p1_0
        // join categories c1_0 on c1_0.category_id=p1_0.category_id
        // where c1_0.name=?
        // ⚠️ inner join 입니다. left join 이 필요해도 지정할 방법이 없습니다.
    }

    /**
     * [12-6] 한계 ② — fetch join 불가 → 1+N.
     * <p>findAll(Predicate, Pageable) 은 우리가 선언한 메서드가 아니라 상속받은 메서드이므로
     * {@code @EntityGraph} 를 붙일 자리조차 없습니다.
     */
    @Test
    @DisplayName("[12-6] ⚠️ 한계 ② fetch join 불가 → 1+N")
    void limitationNoFetchJoin() {
        List<Product> products = queryFactory
                .selectFrom(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .fetch();

        int count = 0;
        for (Product p : products) {
            p.getCategory().getName();   // 지연 로딩 → 카테고리마다 SELECT
            count++;
        }
        System.out.println("[12-6] " + count + "건 순회. 카테고리 SELECT 가 몇 번 나갔는지 로그를 세십시오.");
    }

    /**
     * [12-6] 한계 ④ — Predicate 를 리포지토리 밖에서 만들면 QueryDSL 의존이 번집니다.
     * <p>아래 코드가 서비스 계층에 있다고 상상해 보십시오.
     * 서비스가 QProduct 를 import 하고 com.querydsl.core.types.Predicate 를 다루게 됩니다.
     * QueryDSL 을 걷어내려면 서비스 전체를 고쳐야 합니다 (12-8).
     */
    @Test
    @DisplayName("[12-6] ⚠️ 한계 ④ Predicate 유출")
    void limitationPredicateLeak() {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(product.status.eq(ProductStatus.ON_SALE));
        builder.and(product.price.goe(new BigDecimal("100000")));

        long count = queryFactory.select(product.count()).from(product).where(builder).fetchOne();
        System.out.println("[12-6] " + count + "건 — 이 BooleanBuilder 를 서비스가 만들면 계층이 오염됩니다.");
    }

    // =================================================================
    // [12-7] @QuerydslPredicate 웹 바인딩과 보안
    // =================================================================

    /**
     * [12-7] ⚠️ URL 쿼리스트링이 그대로 WHERE 절이 됩니다.
     *
     * <pre>
     * &#64;GetMapping("/products")
     * public Page&lt;Product&gt; search(
     *         &#64;QuerydslPredicate(root = Product.class) Predicate predicate,
     *         Pageable pageable) {
     *     return productRepository.findAll(predicate, pageable);
     * }
     * </pre>
     *
     * <p>기본 동작은 <b>엔티티의 모든 프로퍼티가 필터링 가능</b>입니다.
     * {@code ?cost=120000} 을 보내면 그대로 {@code where p1_0.cost=?} 가 나갑니다.
     * 값을 직접 못 봐도 바이너리 서치로 원가를 알아낼 수 있습니다.
     *
     * <p>유출 가능 필드 예
     * <pre>
     * Product.cost       → 원가, 마진율
     * Customer.phone     → 특정 번호의 가입 여부
     * Employee.salary    → 급여
     * </pre>
     *
     * <p>처방은 위 {@link ProductRepository#customize} 의 화이트리스트입니다.
     * 정렬 파라미터({@code ?sort=cost,desc})도 같은 경로이므로 <b>둘 다</b> 막아야 합니다.
     */
    @Test
    @DisplayName("[12-7] ⚠️ 화이트리스트 없는 웹 바인딩의 위험")
    void querydslPredicateSecurityNote() {
        // ?cost=120000 이 그대로 조건이 되는 상황을 재현합니다.
        long leaked = queryFactory.select(product.count()).from(product)
                .where(product.cost.eq(new BigDecimal("120000"))).fetchOne();
        System.out.println("[12-7] cost=120000 인 상품 " + leaked + "건 — 이 숫자가 원가를 알려 줍니다.");
        System.out.println("       bindings.excludeUnlistedProperties(true) 로 막으십시오.");
    }

    // =================================================================
    // [12-9] 페이징 통합과 count 생략
    // =================================================================

    /**
     * [12-9] 결과가 많을 때 — count 쿼리가 실행됩니다.
     */
    @Test
    @DisplayName("[12-9] count 쿼리가 실행되는 경우")
    void pageWithCountQuery() {
        OrderRepositoryImpl impl = new OrderRepositoryImpl(queryFactory, em);
        OrderSearchCond cond = new OrderSearchCond(null, OrderStatus.PAID, null, null);

        Page<OrderSearchDto> page = impl.searchOrders(cond, PageRequest.of(0, 10));

        System.out.println("[12-9] total=" + page.getTotalElements()
                + " / pages=" + page.getTotalPages() + " — count 쿼리 로그를 확인하십시오.");
    }

    /**
     * [12-9] 결과가 적을 때 — count 쿼리가 <b>나가지 않습니다.</b>
     * <p>첫 페이지에서 pageSize 보다 적게 왔으므로 전체 건수를 계산할 수 있습니다.
     */
    @Test
    @DisplayName("[12-9] count 쿼리가 생략되는 경우")
    void pageWithoutCountQuery() {
        OrderRepositoryImpl impl = new OrderRepositoryImpl(queryFactory, em);
        OrderSearchCond cond = new OrderSearchCond(null, OrderStatus.PENDING, "광주", null);

        Page<OrderSearchDto> page = impl.searchOrders(cond, PageRequest.of(0, 10));

        System.out.println("[12-9] total=" + page.getTotalElements()
                + " — count 쿼리 로그가 <없는> 것을 확인하십시오.");
    }

    /**
     * [12-9] ⚠️ count 쿼리에서 조인을 뺄 수 있는 조건.
     * <p>where 에 참여하는 조인은 <b>뺄 수 없습니다.</b> 총 건수가 달라집니다.
     */
    @Test
    @DisplayName("[12-9] count 쿼리의 조인 최적화 조건")
    void countQueryJoinOptimization() {
        // customer 조건이 없으므로 count 에서 조인을 뺄 수 있습니다
        Long withJoin = queryFactory.select(order.count()).from(order)
                .join(order.customer, customer)
                .where(order.status.eq(OrderStatus.PAID)).fetchOne();

        Long withoutJoin = queryFactory.select(order.count()).from(order)
                .where(order.status.eq(OrderStatus.PAID)).fetchOne();

        System.out.println("[12-9] withJoin=" + withJoin + " / withoutJoin=" + withoutJoin
                + " — 조건에 customer 가 없으므로 같습니다.");
        System.out.println("       where 에 customer.name 이 들어가면 조인을 뺄 수 없습니다.");
    }

    // =================================================================
    // [12-10] 테스트 설정
    // =================================================================

    /**
     * [12-10] {@code @DataJpaTest} 는 슬라이스 테스트라
     * {@code @Configuration} 을 스캔하지 않습니다.
     * 그래서 {@code QuerydslConfig} 가 안 올라오고 {@code JPAQueryFactory} 빈이 없습니다.
     *
     * <pre>
     * org.springframework.beans.factory.NoSuchBeanDefinitionException:
     *   No qualifying bean of type 'com.querydsl.jpa.impl.JPAQueryFactory' available
     * </pre>
     *
     * <p>처방
     * <pre>
     * &#64;DataJpaTest
     * &#64;AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)  // 실제 MySQL 사용
     * &#64;Import(QuerydslConfig.class)                                                 // 설정 클래스 로딩
     * class OrderRepositoryTest { }
     * </pre>
     *
     * <p>{@code replace = NONE} 이 없으면 클래스패스의 H2 로 대체되어
     * <b>모든 조회가 0건</b>이 됩니다. "통과는 하는데 결과가 비었다" 면 이것을 의심하십시오.
     */
    @Test
    @DisplayName("[12-10] @DataJpaTest 설정 (문서화)")
    void dataJpaTestNote() {
        System.out.println("[12-10] @DataJpaTest + @Import(QuerydslConfig.class)");
        System.out.println("        + @AutoConfigureTestDatabase(replace = NONE)");
    }
}
