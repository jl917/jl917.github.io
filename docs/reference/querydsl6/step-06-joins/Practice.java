package com.example.shop.step06;

import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.QEmployee;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QEmployee.employee;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QPayment.payment;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;

/**
 * Step 06 — 조인 : 본문 예제 모음
 *
 * 실행 전 확인 (application.yml):
 *   logging.level.org.hibernate.SQL: debug
 *   logging.level.org.hibernate.orm.jdbc.bind: trace
 *   logging.level.org.hibernate.orm.query: warn      ← 6-8 절의 HHH90003004 경고를 보려면 필수
 *   spring.jpa.properties.hibernate.generate_statistics: true
 *   spring.jpa.properties.hibernate.default_batch_fetch_size: 100
 *
 * 이 파일의 목적은 결과 검증이 아니라 "어떤 SQL 이 나가는가" 를 보는 것입니다.
 * 특히 6-8 절은 SQL 에 limit 이 "없는 것" 을 확인하는 것이 전부입니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    /** 지금까지 나간 쿼리 개수. 6-7, 6-8 절에서 씁니다. */
    private long queryCount() {
        Statistics stats = em.unwrap(Session.class)
                             .getSessionFactory()
                             .getStatistics();
        return stats.getPrepareStatementCount();
    }

    /** 영속성 컨텍스트를 비웁니다. 1차 캐시 때문에 쿼리가 안 나가는 것을 막습니다. */
    private void clearContext() {
        em.flush();
        em.clear();
    }

    // =================================================================
    // [6-1] 연관 기반 조인 vs 세타 조인
    // =================================================================

    @Test
    @DisplayName("[6-1] 연관 기반 조인 — join ... on ...")
    void associationJoin() {
        List<Tuple> result = queryFactory
                .select(order.id, customer.name)
                .from(order)
                .join(order.customer, customer)     // 연관 경로 + 별칭
                .limit(3)
                .fetch();

        result.forEach(t -> System.out.println(t.get(order.id) + " / " + t.get(customer.name)));

        // 생성 SQL:
        //   from orders o1_0
        //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
        //
        // 첫 인자 order.customer 는 "연관 경로" 입니다. @ManyToOne 매핑에 조인 조건이 이미 있으므로
        // on 을 쓸 필요가 없습니다.
        // 둘째 인자 customer 는 "별칭" 입니다. 이걸 줘야 select/where 에서 customer.name 을 쓸 수 있습니다.
    }

    @Test
    @DisplayName("[6-1] 세타 조인 — from a, b where ...")
    void thetaJoin() {
        List<Tuple> result = queryFactory
                .select(order.id, customer.name)
                .from(order, customer)                   // from 에 둘을 나열
                .where(order.customer.eq(customer))      // 조인 조건을 where 로
                .limit(3)
                .fetch();

        result.forEach(t -> System.out.println(t.get(order.id) + " / " + t.get(customer.name)));

        // 생성 SQL:
        //   from orders o1_0, customers c1_0
        //   where o1_0.customer_id = c1_0.customer_id
        //
        // 결과는 연관 기반 조인과 같지만 SQL 이 다릅니다.
        // MySQL8 코스 Step 07 의 7-7 절에서 "콤마 조인 대신 명시적 JOIN ON 을 쓰라" 고 경고한 형태입니다.
        //
        // ★ where 를 빠뜨리면 600 × 30 = 18,000 행이 나옵니다. 예외는 없습니다.
        //   연관 매핑이 있으면 언제나 연관 기반 조인을 쓰십시오.
    }

    // =================================================================
    // [6-2] join / innerJoin / leftJoin / rightJoin
    // =================================================================

    @Test
    @DisplayName("[6-2] INNER JOIN — MySQL8 코스 7-1 절과 같은 결과")
    void innerJoinBasic() {
        List<Tuple> result = queryFactory
                .select(order.id, order.orderDate, customer.name,
                        customer.grade, order.totalAmount)
                .from(order)
                .join(order.customer, customer)
                .orderBy(order.id.asc())
                .limit(5)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(order.id) + " | " + t.get(order.orderDate) + " | "
              + t.get(customer.name) + " | " + t.get(customer.grade) + " | "
              + t.get(order.totalAmount)));

        // 기대 (MySQL8 코스 7-1 과 동일):
        //   1 | 2024-02-07T13:07 | 류하나 | GOLD   | 1836000.00
        //   2 | 2024-03-15T02:14 | 정  훈 | GOLD   | 6663900.00
        //   ...
    }

    @Test
    @DisplayName("[6-2] join 과 innerJoin 은 같은 메서드다")
    void joinEqualsInnerJoin() {
        long a = queryFactory.select(order.count())
                .from(order).join(order.customer, customer).fetchOne();
        long b = queryFactory.select(order.count())
                .from(order).innerJoin(order.customer, customer).fetchOne();

        System.out.println("join = " + a + ", innerJoin = " + b);   // 600 / 600
        // 생성 SQL 도 완전히 동일합니다. SQL 에서 INNER 를 생략할 수 있는 것과 같습니다.
    }

    @Test
    @DisplayName("[6-2] LEFT JOIN — 상품이 없는 대분류도 남는다 (MySQL8 7-3)")
    void leftJoinBasic() {
        List<Tuple> result = queryFactory
                .select(category.id, category.name, product.id, product.name)
                .from(category)
                .leftJoin(category.products, product)
                .where(category.parent.isNull())          // 대분류만
                .orderBy(category.id.asc())
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(category.id) + " | " + t.get(category.name) + " | "
              + t.get(product.id) + " | " + t.get(product.name)));

        // 기대: 5건. 상품 컬럼은 전부 null (NULL 확장).
        // leftJoin 을 join 으로 바꾸면 이 5줄이 통째로 사라집니다. 직접 해 보십시오.
    }

    // =================================================================
    // [6-3] 다중 조인 — 5개 테이블 (MySQL8 7-2)
    // =================================================================

    @Test
    @DisplayName("[6-3] 5개 테이블 조인 — 별칭이 c1_0, c2_0 으로 나뉜다")
    void multiJoin() {
        List<Tuple> result = queryFactory
                .select(order.id, customer.name, product.name,
                        category.name, orderItem.quantity, orderItem.unitPrice)
                .from(order)
                .join(order.customer, customer)
                .join(order.orderItems, orderItem)
                .join(orderItem.product, product)
                .join(product.category, category)
                .orderBy(order.id.asc(), product.id.asc())
                .limit(8)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(order.id) + " | " + t.get(customer.name) + " | "
              + t.get(product.name) + " | " + t.get(category.name) + " | "
              + t.get(orderItem.quantity) + " | " + t.get(orderItem.unitPrice)));

        // customers 와 categories 가 둘 다 c 로 시작해서 Hibernate 가 c1_0, c2_0 으로 번호를 매깁니다.
        // 어느 쪽이 무엇인지는 on 절의 컬럼명으로 확인하십시오.
        //
        // ★ order_id = 1 이 두 줄인 것에 주목. 주문 1건에 상품 2개(1:N).
        //   다음 절의 사고가 여기서 시작됩니다.
    }

    // =================================================================
    // [6-4] ⚠️ fan-out — 반드시 아래 세 메서드를 이 순서로 실행하십시오
    // =================================================================

    @Test
    @DisplayName("[6-4] ① 정답 — orderItems 조인 없음")
    void fanOutCorrect() {
        Tuple t = queryFactory
                .select(order.count(), order.totalAmount.sum())
                .from(order)
                .join(order.customer, customer)
                .where(customer.id.eq(1L))
                .fetchOne();

        System.out.println("주문 수 = " + t.get(order.count())
                         + ", 총액 = " + t.get(order.totalAmount.sum()));
        // 기대: 주문 수 = 20, 총액 = 24300000.00
    }

    @Test
    @DisplayName("[6-4] ② ⚠️ orderItems 조인 한 줄 추가 — 집계식은 그대로인데 2배가 된다")
    void fanOutWrong() {
        Tuple t = queryFactory
                .select(order.count(), order.totalAmount.sum())
                .from(order)
                .join(order.customer, customer)
                .join(order.orderItems, orderItem)          // ← 이 한 줄만 추가
                .where(customer.id.eq(1L))
                .fetchOne();

        System.out.println("주문 수 = " + t.get(order.count())
                         + ", 총액 = " + t.get(order.totalAmount.sum()));
        // 기대: 주문 수 = 41, 총액 = 49860000.00
        //
        // 예외 없음. 경고 없음. 매출 리포트에 2배 숫자가 찍힐 뿐입니다.
        //
        // ★ 조인 추가는 "컬럼 추가" 가 아니라 "행의 단위를 바꾸는 일" 입니다.
        //   한 행의 의미가 "주문 하나" 에서 "주문 상품 한 줄" 로 바뀌었습니다.
        //   집계 전에 언제나 자문하십시오 — "지금 한 행은 무엇의 단위인가?"
    }

    @Test
    @DisplayName("[6-4] ③ 방어 1 — countDistinct 는 count 만 고친다")
    void fanOutCountDistinct() {
        Tuple t = queryFactory
                .select(order.countDistinct(), order.totalAmount.sum())
                .from(order)
                .join(order.customer, customer)
                .join(order.orderItems, orderItem)
                .where(customer.id.eq(1L))
                .fetchOne();

        System.out.println("주문 수 = " + t.get(order.countDistinct())
                         + ", 총액 = " + t.get(order.totalAmount.sum()));
        // 기대: 주문 수 = 20 (고쳐짐), 총액 = 49860000.00 (여전히 틀림)
        //
        // sum 에 distinct 를 붙이면 더 큰 사고가 납니다 —
        // 다른 주문인데 금액이 우연히 같으면 한 번만 더해집니다.
        // MySQL8 코스 7-11 절의 경고와 같은 이야기입니다.
    }

    @Test
    @DisplayName("[6-4] 방어 2 — 집계 쿼리를 분리한다")
    void fanOutSeparateQuery() {
        BigDecimal total = queryFactory
                .select(order.totalAmount.sum())
                .from(order)
                .where(order.customer.id.eq(1L))
                .fetchOne();

        System.out.println("총액 = " + total);   // 24300000.00 — 정답
    }

    @Test
    @DisplayName("[6-4] 방어 3 — 행 단위에 맞는 집계식 (가장 안전)")
    void fanOutLineLevelSum() {
        BigDecimal total = queryFactory
                .select(orderItem.unitPrice.multiply(orderItem.quantity).sum())
                .from(order)
                .join(order.orderItems, orderItem)
                .where(order.customer.id.eq(1L))
                .fetchOne();

        System.out.println("총액 = " + total);   // 24300000.00 — 정답
        // 집계식의 단위가 행의 단위와 같으면 fan-out 자체가 문제가 되지 않습니다.
    }

    // =================================================================
    // [6-5] ⚠️ on vs where — 조건 변수를 하나만 두고 위치만 바꿉니다
    // =================================================================

    /** 두 메서드가 공유하는 조건. 코드 차이가 메서드 이름 하나뿐임을 보이기 위한 구성입니다. */
    private static final BooleanExpression EXPENSIVE =
            product.price.goe(new BigDecimal("1000000"));

    @Test
    @DisplayName("[6-5] (A) 조건을 on 에 — 17건. 카테고리 전부 보존")
    void onVersion() {
        List<Tuple> result = queryFactory
                .select(category.name, product.name, product.price)
                .from(category)
                .leftJoin(category.products, product)
                .on(EXPENSIVE)                       // ← on
                .orderBy(category.id.asc())
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(category.name) + " | " + t.get(product.name) + " | " + t.get(product.price)));
        System.out.println("조회 " + result.size() + "건");

        // 기대: 17건 = 조건에 맞는 상품 6건 + 조건에 맞는 상품이 없는 카테고리 11건(NULL 확장)
        // 생성 SQL:
        //   left join products p1_0
        //          on c1_0.category_id = p1_0.category_id
        //         and p1_0.price >= ?
    }

    @Test
    @DisplayName("[6-5] (B) ⚠️ 조건을 where 에 — 6건. INNER JOIN 으로 퇴화")
    void whereVersion() {
        List<Tuple> result = queryFactory
                .select(category.name, product.name, product.price)
                .from(category)
                .leftJoin(category.products, product)
                .where(EXPENSIVE)                    // ← where. 변수는 위와 완전히 같다
                .orderBy(category.id.asc())
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(category.name) + " | " + t.get(product.name) + " | " + t.get(product.price)));
        System.out.println("조회 " + result.size() + "건");

        // 기대: 6건.
        // where 는 조인이 "끝난 뒤" 적용됩니다.
        // NULL 확장 행의 p1_0.price 는 NULL 이고 NULL >= 1000000 은 UNKNOWN 이라 탈락합니다.
        //
        // ★ QueryDSL 에서 특히 위험한 이유:
        //   on() 과 where() 는 둘 다 BooleanExpression 을 받습니다.
        //   위 EXPENSIVE 변수를 어느 쪽에 넘겨도 컴파일됩니다.
        //   리팩터링하다 조건을 옮기는 순간 17건이 6건이 되는데 컴파일러는 침묵합니다.
        //
        // 핵심 규칙: LEFT JOIN 에서 오른쪽 조건은 on 에, 왼쪽 조건은 where 에.
        // 예외: where 에 isNull() 을 두는 안티 조인(6-12)은 정상입니다.
    }

    // =================================================================
    // [6-6] 연관 없는 엔티티의 on 조인
    // =================================================================

    @Test
    @DisplayName("[6-6] leftJoin(엔티티).on(조건) — 인자 1개 + on")
    void joinWithoutAssociation() {
        List<Tuple> result = queryFactory
                .select(category.name, product.name)
                .from(category)
                .leftJoin(product).on(product.name.eq(category.name))   // 연관 경로 없음
                .orderBy(category.id.asc())
                .limit(5)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(category.name) + " | " + t.get(product.name)));

        // 생성 SQL: left join products p1_0 on p1_0.name = c1_0.name
        // on 절에 매핑에서 온 조건이 없습니다. 우리가 준 조건뿐입니다.
        //
        // 세타 조인(.from(category, product).where(...))으로도 같은 결과를 얻지만
        // 세타 조인은 외부 조인을 못 합니다. 위 leftJoin 은 세타 조인으로 표현할 수 없습니다.
        // join(엔티티).on(조건) 은 세타 조인의 상위 호환입니다.
    }

    // =================================================================
    // [6-7] fetch join — N+1 을 쿼리 개수로 증명
    // =================================================================

    @Test
    @DisplayName("[6-7] ⚠️ fetch join 없이 — 쿼리 11개 (1 + N)")
    void nPlusOne() {
        clearContext();
        long before = queryCount();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .limit(10)
                .fetch();

        for (Order o : orders) {
            System.out.println(o.getId() + " / " + o.getCustomer().getName());   // 프록시 초기화
        }

        System.out.println("총 쿼리 수 = " + (queryCount() - before));   // 기대: 11
        // 결과는 완벽하게 맞습니다. 느릴 뿐입니다.
        // 개발 환경에서 10건일 때는 아무도 눈치채지 못합니다.
    }

    @Test
    @DisplayName("[6-7] fetchJoin() — 쿼리 1개")
    void fetchJoinToOne() {
        clearContext();
        long before = queryCount();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()     // ← .fetchJoin()
                .limit(10)
                .fetch();

        for (Order o : orders) {
            System.out.println(o.getId() + " / " + o.getCustomer().getName());
        }

        System.out.println("총 쿼리 수 = " + (queryCount() - before));   // 기대: 1

        // 일반 join: 조인은 하지만 select 에는 왼쪽 엔티티만 담습니다.
        // fetchJoin(): 조인한 엔티티의 컬럼까지 한 번에 select 해서 영속성 컨텍스트를 채웁니다.
        //              프록시가 아니라 실제 객체가 들어 있으니 추가 쿼리가 없습니다.
        //
        // 생성 SQL 의 select 절에 c1_0.* 가 전부 들어간 것을 확인하십시오. 그것이 구분점입니다.
        //
        // 제약: fetch join 대상에는 on 을 걸 수 없습니다.
        //       연관의 일부만 로딩해 영속성 컨텍스트에 넣으면 "잘려 있는 컬렉션" 이 되기 때문입니다.
        //       fetch join 은 "연관 전체를 통째로 가져오는 것" 이라고 이해하십시오.
    }

    // =================================================================
    // [6-8] ⚠️⚠️ 이 코스 전체에서 가장 중요한 함정
    // =================================================================

    @Test
    @DisplayName("[6-8] ⚠️ 컬렉션 fetch join + 페이징 — SQL 에 limit 이 없다")
    void collectionFetchJoinWithPaging() {
        clearContext();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.orderItems, orderItem).fetchJoin()    // 컬렉션 fetch join
                .offset(0)
                .limit(10)
                .fetch();

        System.out.println("결과 = " + orders.size() + "건");

        // ★ 콘솔에서 반드시 확인할 것 두 가지:
        //
        //   1) 경고 로그
        //      WARN org.hibernate.orm.query :
        //        HHH90003004: firstResult/maxResults specified with collection fetch;
        //        applying in memory
        //
        //      Hibernate 5 에서는 코드가 달랐습니다:
        //        HHH000104: firstResult/maxResults specified with collection fetch;
        //        applying in memory!
        //      로거도 org.hibernate.hql.internal.ast.QueryTranslatorImpl 이었습니다.
        //      운영 로그 알람에는 두 코드를 모두 등록하거나 메시지 본문으로 잡으십시오.
        //
        //   2) 생성 SQL 에 limit 이 "없다"
        //      select ... from orders o1_0 join order_items oi1_0 on ...
        //      (끝. limit 절이 통째로 사라졌습니다.)
        //
        // 무슨 일이 일어났나:
        //   1:N 조인은 행을 뻥튀기합니다(6-4). 주문 600 × 상품 → 조인 결과 1,200행.
        //   여기에 limit 10 을 걸면 "주문 10건" 이 아니라 "조인 행 10개" 가 잘립니다.
        //   마지막 주문은 상품이 잘린 채로 들어옵니다 — 불완전한 엔티티입니다.
        //   그래서 Hibernate 는 페이징을 포기하고 전건을 읽어 메모리에서 자릅니다.
        //   결과의 정확성을 지키기 위해 성능을 버린 것입니다.
        //
        // 왜 못 보고 지나가나:
        //   - WARN 레벨이고 애플리케이션은 정상 동작합니다.
        //   - 결과는 정확합니다. 10건 달라면 10건 줍니다. 테스트도 통과합니다.
        //   - 개발 DB 에 주문이 600건이면 체감 차이가 없습니다.
        //   - 운영에서 60만 건이 되면 그때 OutOfMemoryError 가 납니다.
        //     주문 60만 × 상품 평균 2 = 조인 행 120만 개가 한 요청에 힙으로 들어옵니다.
        //     첫 페이지를 보든 마지막 페이지를 보든 매번 전건을 읽습니다.
    }

    @Test
    @DisplayName("[6-8] 처방 ① 배치 페치 — limit 이 SQL 에 들어간다. 쿼리 2개")
    void fix1BatchFetch() {
        clearContext();
        long before = queryCount();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()   // ToOne 만 fetch join
                .offset(0)
                .limit(10)
                .fetch();

        // 컬렉션은 지연 로딩 + default_batch_fetch_size 로 in 절 묶음 조회
        orders.forEach(o -> System.out.println(o.getId() + " → 상품 " + o.getOrderItems().size() + "개"));

        System.out.println("총 쿼리 수 = " + (queryCount() - before));   // 기대: 2

        // 2번째 쿼리:
        //   select ... from order_items oi1_0 where oi1_0.order_id in (?,?,?,?,?,?,?,?,?,?)
        //
        // default_batch_fetch_size 가 없었다면 이 쿼리가 10개로 쪼개집니다(N+1).
        // 특정 연관에만 적용하려면 엔티티에 @BatchSize(size = 100) 을 붙입니다.
    }

    @Test
    @DisplayName("[6-8] 처방 ② ToOne 만 fetch join — 경고 없음, limit 정상")
    void fix2ToOneOnly() {
        clearContext();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()   // ToOne — 행이 안 늘어난다
                .orderBy(order.id.asc())
                .offset(20)
                .limit(10)
                .fetch();

        System.out.println("결과 = " + orders.size() + "건 (WARN 없음)");

        // 생성 SQL 끝:
        //   offset ? rows fetch first ? rows only
        //   바인딩: [1] 20, [2] 10
        //
        // ★ 이 절에서 가장 중요한 구분:
        //
        //   @ManyToOne / @OneToOne  → fetch join 해도 행 수 그대로 → 페이징 안전
        //   @OneToMany / @ManyToMany → fetch join 하면 행이 늘어남 → 페이징 위험
        //
        // "fetch join 은 페이징과 못 쓴다" 는 부정확합니다.
        // "컬렉션 fetch join 은 페이징과 못 쓴다" 가 정확합니다.
    }

    @Test
    @DisplayName("[6-8] 처방 ③ 2단계 조회 — ID 페이징 후 in 으로 컬렉션 로딩")
    void fix3TwoStep() {
        clearContext();

        // 1단계 — ID 만. 조인이 없으니 fan-out 도 없고 limit 이 정확히 동작합니다.
        List<Long> ids = queryFactory
                .select(order.id)
                .from(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .orderBy(order.orderDate.desc())
                .offset(0)
                .limit(10)
                .fetch();

        System.out.println("1단계 ID = " + ids);
        // 생성 SQL: select o1_0.order_id from orders o1_0 where ...
        //           offset ? rows fetch first ? rows only

        // 2단계 — 그 ID 들로 컬렉션까지 fetch join. limit 이 없으니 경고가 안 납니다.
        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .join(order.customer, customer).fetchJoin()
                .where(order.id.in(ids))
                .orderBy(order.orderDate.desc())
                .fetch();

        System.out.println("2단계 결과 = " + orders.size() + "건 (WARN 없음)");

        // 핵심은 1단계에 fan-out 이 없다는 것입니다.
        // order.id 만 select 하면 조인이 없으니 limit 10 이 정확히 주문 10건을 자릅니다.
        // 2단계는 in 으로 딱 그 10건만 읽으므로 limit 이 필요 없고, 따라서 경고도 없습니다.
        //
        // 세 처방 비교:
        //   ① 배치 페치   : 쿼리 1+연관수, 복잡도 낮음  → 대부분의 경우. 기본값.
        //   ② ToOne 만    : 쿼리 1(+지연), 복잡도 낮음  → 컬렉션이 화면에 필요 없을 때
        //   ③ 2단계 조회  : 쿼리 2,       복잡도 중간  → 정렬·조건이 복잡할 때
    }

    // =================================================================
    // [6-9] ⚠️ 컬렉션 fetch join 은 하나만
    // =================================================================

    @Test
    @DisplayName("[6-9] ⚠️ 컬렉션 두 개 fetch join — MultipleBagFetchException")
    void multipleBagFetch() {
        try {
            queryFactory
                    .selectFrom(order)
                    .join(order.orderItems, orderItem).fetchJoin()
                    .join(order.payments, payment).fetchJoin()      // 두 번째 컬렉션
                    .fetch();

            System.out.println("예외가 안 났습니다 — 엔티티가 Set 으로 매핑돼 있는지 확인하십시오.");
        } catch (Exception e) {
            System.out.println(e.getClass().getName());
            System.out.println(e.getMessage());

            // org.hibernate.loader.MultipleBagFetchException:
            //   cannot simultaneously fetch multiple bags:
            //   [com.example.shop.entity.Order.orderItems,
            //    com.example.shop.entity.Order.payments]
        }

        // 왜 금지인가 — 카테시안 곱:
        //   주문 1건에 상품 3개, 결제 2건이면 조인 결과는 3 × 2 = 6행입니다.
        //   Hibernate 가 이 6행에서 orderItems 를 복원할 때 각 상품이 2번씩 나타나는 것을 봅니다.
        //   List 는 순서와 중복을 보존하는 컬렉션이라, 이 중복이 "진짜 데이터" 인지
        //   "조인 때문" 인지 판단할 근거가 없습니다.
        //   orderItems.size() 가 3인지 6인지 결정할 수 없으므로 예외를 던집니다.
        //
        //   "bag" 은 @OrderColumn 이 없는 List<T> 매핑을 뜻하는 Hibernate 용어입니다.
    }

    @Test
    @DisplayName("[6-9] 처방 ② 하나만 fetch join + 나머지는 배치 페치 (권장)")
    void oneCollectionFetchJoin() {
        clearContext();
        long before = queryCount();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.orderItems, orderItem).fetchJoin()   // 하나만
                .where(order.id.loe(10L))
                .fetch();

        orders.forEach(o -> System.out.println(o.getId() + " → 결제 " + o.getPayments().size() + "건"));

        System.out.println("총 쿼리 수 = " + (queryCount() - before));   // 기대: 2

        // 처방 ① 인 "List → Set" 은 예외를 없애 주지만 곱집합은 그대로입니다.
        // DB 에서 읽는 행 수가 3 × 2 = 6행인 것은 변하지 않습니다.
        // 컬렉션이 3개, 4개로 늘면 기하급수적으로 커집니다.
        // Set 은 "예외를 없애는" 것이지 "곱집합을 없애는" 것이 아닙니다.
        //
        // 규칙: 컬렉션 fetch join 은 최대 하나. 그리고 그 하나에도 페이징을 붙이지 말 것.
    }

    // =================================================================
    // [6-10] distinct() 와 fetch join — Hibernate 6 에서 달라진 것
    // =================================================================

    @Test
    @DisplayName("[6-10] Hibernate 6 은 distinct() 없이도 중복을 제거한다")
    void distinctNotNeeded() {
        clearContext();

        List<Order> withoutDistinct = queryFactory
                .selectFrom(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .where(order.id.loe(3L))
                .fetch();

        long joinRows = queryFactory
                .select(orderItem.count())
                .from(order).join(order.orderItems, orderItem)
                .where(order.id.loe(3L))
                .fetchOne();

        System.out.println("읽은 조인 행 = " + joinRows
                         + ", 결과 리스트 크기 = " + withoutDistinct.size());
        // 기대: 읽은 조인 행 = 6, 결과 리스트 크기 = 3
        //
        // Hibernate 5 에서 같은 코드를 돌리면 결과 리스트가 6이 됩니다.
        // 그래서 5 시절에는 .distinct() 가 필수였습니다.
        //
        // hibernate.query.passDistinctThrough 는 Hibernate 5 에서
        // "자바에서만 중복 제거하고 SQL 에는 distinct 를 보내지 마라" 는 뜻이었습니다.
        // Hibernate 6 에서는 이 설정이 제거됐습니다. 설정해도 무시됩니다.
    }

    @Test
    @DisplayName("[6-10] 주의 — 스칼라/DTO 조회에서는 distinct 가 여전히 유효하다")
    void distinctStillMattersForScalar() {
        List<String> cities = queryFactory
                .select(customer.city).distinct()
                .from(customer)
                .fetch();

        System.out.println(cities + " (" + cities.size() + "건)");   // 6건
        // 생성 SQL 에 distinct 가 실제로 들어갑니다.
        //
        // "Hibernate 6 이니까 distinct 는 필요 없다" 로 일반화하면 안 됩니다.
        // 정확히는 "엔티티 쿼리에서 fetch join 때문에 붙이던 distinct 가 필요 없어졌다" 입니다.
    }

    // =================================================================
    // [6-11] 셀프 조인
    // =================================================================

    @Test
    @DisplayName("[6-11] 셀프 조인 — new QEmployee(\"manager\") 로 별칭 생성 (MySQL8 7-6)")
    void selfJoin() {
        QEmployee manager = new QEmployee("manager");     // ← 별칭 생성

        List<Tuple> result = queryFactory
                .select(employee.id, employee.name, employee.position,
                        manager.name, manager.position)
                .from(employee)
                .leftJoin(employee.manager, manager)
                .orderBy(employee.id.asc())
                .limit(10)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(employee.id) + " | " + t.get(employee.name) + " | "
              + t.get(employee.position) + " | " + t.get(manager.name) + " | "
              + t.get(manager.position)));

        // 생성 SQL: left join employees m1_0 on m1_0.employee_id = e1_0.manager_id
        // 별칭 "manager" 를 Hibernate 가 m1_0 으로 변환했습니다.
        // leftJoin 이라 관리자가 없는 CEO(정한별)도 null 로 남습니다.
        //
        // ★ .from(employee).leftJoin(employee.manager, employee) 처럼 같은 별칭을 쓰면
        //   Hibernate 는 e1_0 하나만 만듭니다. 컴파일은 되지만 실행에서 깨집니다.
        //   셀프 조인에는 반드시 new QEmployee("...") 로 별도 별칭을 만드십시오.
    }

    @Test
    @DisplayName("[6-11] 셀프 조인 — 부서 내 급여 순위 (MySQL8 7-6 두 번째 예제)")
    void selfJoinRanking() {
        QEmployee higher = new QEmployee("higher");

        List<Tuple> result = queryFactory
                .select(employee.name, employee.dept, employee.salary, higher.count())
                .from(employee)
                .leftJoin(higher)
                .on(higher.dept.eq(employee.dept)
                        .and(higher.salary.gt(employee.salary)))    // 연관 없는 on 조인
                .groupBy(employee.id, employee.name, employee.dept, employee.salary)
                .orderBy(employee.dept.asc(), employee.salary.desc())
                .limit(6)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(employee.name) + " | " + t.get(employee.dept) + " | "
              + t.get(employee.salary) + " | " + t.get(higher.count())));

        // "나보다 높은 사람 수 + 1" 이 부서 내 급여 순위입니다.
        //
        // leftJoin 이 필수인 이유: 1등은 매칭 0건이라 join 이면 결과에서 사라집니다.
        // higher.count() 를 쓴 이유: count() (= count(*)) 를 쓰면 NULL 확장 행도 세어
        //   1등이 1 로 나옵니다. MySQL8 코스 7-3 절의 "COUNT(*) 함정" 과 같은 이야기입니다.
    }

    // =================================================================
    // [6-12] 안티 조인
    // =================================================================

    @Test
    @DisplayName("[6-12] 안티 조인 — 주문이 한 번도 없는 고객 (0명이 정답)")
    void antiJoinNoOrders() {
        List<Tuple> result = queryFactory
                .select(customer.id, customer.name, customer.grade)
                .from(customer)
                .leftJoin(customer.orders, order)
                .where(order.id.isNull())               // NULL 확장을 노린다
                .orderBy(customer.id.asc())
                .fetch();

        System.out.println("조회 " + result.size() + "건");   // 기대: 0
        // 시드 데이터가 고객 30명 전원에게 주문 20건씩 배정했기 때문입니다.
        // "아무것도 안 나오는 것" 이 정답인 쿼리입니다.
        // 데이터 검증에서 안티 조인이 쓰이는 전형입니다.
    }

    @Test
    @DisplayName("[6-12] 안티 조인 — 후기를 한 번도 안 쓴 고객 (26명)")
    void antiJoinNoReviews() {
        List<Tuple> result = queryFactory
                .select(customer.id, customer.name, customer.grade)
                .from(customer)
                .leftJoin(review).on(review.customer.eq(customer))
                .where(review.id.isNull())
                .orderBy(customer.id.asc())
                .fetch();

        result.stream().limit(8).forEach(t -> System.out.println(
                t.get(customer.id) + " | " + t.get(customer.name) + " | " + t.get(customer.grade)));
        System.out.println("조회 " + result.size() + "건");   // 기대: 26

        // ★ isNull() 대상은 반드시 NOT NULL 컬럼(대개 PK)이어야 합니다.
        //   where(review.title.isNull()) 로 쓰면 title 이 NULL 을 허용하므로
        //   "후기는 썼는데 제목이 비어 있는 고객" 까지 딸려옵니다.
        //
        // 6-5 절에서 "LEFT JOIN 오른쪽 조건을 where 에 두지 말라" 고 했는데
        // 여기서는 where(review.id.isNull()) 을 씁니다. 모순이 아닙니다.
        // 안티 조인은 NULL 확장 행만 골라내는 것이 목적이므로
        // 조인 후에 적용되는 where 가 정확히 필요한 도구입니다.
    }

    @Test
    @DisplayName("[6-12] 안티 조인 — 결제가 없는 주문 (60건 = PENDING 주문 수)")
    void antiJoinNoPayments() {
        long antiJoin = queryFactory
                .select(order.count())
                .from(order)
                .leftJoin(order.payments, payment)
                .where(payment.id.isNull())
                .fetchOne();

        long pending = queryFactory
                .select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.PENDING))
                .fetchOne();

        System.out.println("안티 조인 = " + antiJoin + ", PENDING = " + pending);
        // 기대: 60 / 60. MySQL8 코스 7-5 절과 동일합니다.
    }
}
