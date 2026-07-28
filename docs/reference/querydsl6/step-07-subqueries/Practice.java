package com.example.shop.step07;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Employee;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.Product;
import com.example.shop.entity.QCustomer;
import com.example.shop.entity.QEmployee;
import com.example.shop.entity.QOrder;
import com.example.shop.entity.QProduct;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
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
import static com.example.shop.entity.QEmployee.employee;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QPayment.payment;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;
import static com.querydsl.jpa.JPAExpressions.select;
import static com.querydsl.jpa.JPAExpressions.selectOne;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 07 — 서브쿼리 : 본문 예제 모음
 *
 * <p>본문 7-1 ~ 7-11 절의 모든 예제를 절 번호 주석과 함께 담았습니다.
 * 각 테스트를 실행하면서 콘솔에 찍히는 hibernate.SQL 로그를 교재의 SQL 과 한 글자씩 비교하십시오.
 * 결과가 맞았다고 넘어가면 이 코스에서 배울 것이 절반으로 줄어듭니다.
 *
 * <p>실행 전 application.yml 확인:
 * <pre>
 * logging:
 *   level:
 *     org.hibernate.SQL: debug
 *     org.hibernate.orm.jdbc.bind: trace
 * </pre>
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [7-1] JPAExpressions — 서브쿼리를 만드는 정적 팩토리
    // =================================================================

    @Test
    @DisplayName("[7-1] JPAExpressions 가 돌려주는 것은 JPQLQuery = Expression 이다")
    void jpaExpressionsReturnsExpression() {
        QCustomer sub = new QCustomer("sub");

        // JPQLQuery<T> 는 동시에 Expression<T> 다. 그래서 where 안에 값처럼 들어간다.
        JPQLQuery<Double> avgPoints = JPAExpressions
                .select(sub.points.avg())
                .from(sub);

        // 이 객체 자체로는 SQL 이 나가지 않는다. 바깥 쿼리에 끼워 넣어야 실행된다.
        assertThat(avgPoints).isInstanceOf(Expression.class);

        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.points.gt(avgPoints))
                .fetch();

        System.out.println("평균 초과 고객 = " + result.size() + "명");
    }

    // =================================================================
    // [7-2] where 절 스칼라 서브쿼리 — 그리고 별칭 함정
    // =================================================================

    @Test
    @DisplayName("[7-2] 별칭 분리 — 정상 동작 (11건)")
    void aliasSeparated() {
        QCustomer sub = new QCustomer("sub");

        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.points.gt(
                        select(sub.points.avg()).from(sub)
                ))
                .orderBy(customer.points.desc())
                .fetch();

        // 생성 SQL:
        //   where c1_0.points > (select avg(c2_0.points) from customers c2_0)
        //                                    ^^^^ 바깥(c1_0) 과 다른 별칭
        result.forEach(c -> System.out.printf("%-8s %-7s %d%n", c.getName(), c.getGrade(), c.getPoints()));
        System.out.println("→ " + result.size() + "건");
    }

    @Test
    @DisplayName("[7-2] ⚠️ 별칭 충돌 — 컴파일도 되고 예외도 안 나는데 0건")
    void aliasCollision() {
        // ⚠️ 의도적으로 틀린 코드입니다. 0건이 나오는 것이 '정상' 입니다.
        List<Customer> wrong = queryFactory
                .selectFrom(customer)
                .where(customer.points.gt(
                        select(customer.points.avg()).from(customer)   // ← 바깥과 같은 인스턴스
                ))
                .fetch();

        // 생성 SQL:
        //   where c1_0.points > (select avg(c1_0.points) from customers c1_0)
        //                                    ^^^^ 바깥과 같은 별칭! 자기 자신을 가리킨다
        // → points > points 라는 영원히 거짓인 조건
        System.out.println("잘못된 결과 = " + wrong.size() + "건 (0이어야 정상)");
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("[7-2] 서로 다른 엔티티라도 같은 엔티티가 두 번 나오면 분리 필요")
    void aliasForSameEntityTwice() {
        QOrder o2 = new QOrder("o2");

        List<Order> bigOrders = queryFactory
                .selectFrom(order)
                .where(order.totalAmount.gt(
                        select(o2.totalAmount.avg()).from(o2)
                ))
                .orderBy(order.totalAmount.desc())
                .limit(5)
                .fetch();

        bigOrders.forEach(o -> System.out.println(o.getId() + " " + o.getTotalAmount()));
    }

    // =================================================================
    // [7-3] in 서브쿼리
    // =================================================================

    @Test
    @DisplayName("[7-3] in 서브쿼리 — 주문이 있는 고객 (30건, 즉 전원)")
    void inSubqueryHasOrder() {
        List<Customer> hasOrder = queryFactory
                .selectFrom(customer)
                .where(customer.id.in(
                        select(order.customer.id).from(order)
                ))
                .orderBy(customer.id.asc())
                .fetch();

        // order.customer.id 는 조인을 만들지 않는다.
        // orders.customer_id 는 이미 orders 테이블에 있는 FK 컬럼이기 때문.
        System.out.println("주문이 있는 고객 = " + hasOrder.size() + "명");
        assertThat(hasOrder).hasSize(30);   // 전원 → 필터로서 의미 없음
    }

    @Test
    @DisplayName("[7-3] in 서브쿼리 — 후기를 쓴 고객 (4건)")
    void inSubqueryReviewers() {
        List<Customer> reviewers = queryFactory
                .selectFrom(customer)
                .where(customer.id.in(
                        select(review.customer.id).from(review)
                ))
                .orderBy(customer.id.asc())
                .fetch();

        reviewers.forEach(c -> System.out.printf("%3d %-8s %s%n", c.getId(), c.getName(), c.getGrade()));
        assertThat(reviewers).hasSize(4);
    }

    @Test
    @DisplayName("[7-3] 값 목록 in 과 서브쿼리 in 은 다른 오버로드")
    void inOverloads() {
        // 값 목록 → in (?, ?)
        long a = queryFactory
                .selectFrom(customer)
                .where(customer.grade.in(Grade.VIP, Grade.GOLD))
                .fetch().size();

        // 서브쿼리 → in (select ...)
        long b = queryFactory
                .selectFrom(customer)
                .where(customer.id.in(select(review.customer.id).from(review)))
                .fetch().size();

        System.out.println("VIP+GOLD = " + a + " / 후기 작성자 = " + b);
    }

    // =================================================================
    // [7-4] exists / notExists — 상관 서브쿼리
    // =================================================================

    @Test
    @DisplayName("[7-4] exists — 후기를 쓴 고객 (4건). 여기서는 같은 인스턴스를 쓴다")
    void existsCorrelated() {
        List<Customer> hasReview = queryFactory
                .selectFrom(customer)
                .where(selectOne()
                        .from(review)
                        .where(review.customer.eq(customer))   // ← 바깥 customer 참조 = 상관
                        .exists())
                .orderBy(customer.id.asc())
                .fetch();

        // 생성 SQL:
        //   where exists (select 1 from reviews r1_0 where r1_0.customer_id = c1_0.customer_id)
        hasReview.forEach(c -> System.out.printf("%3d %-8s%n", c.getId(), c.getName()));
        assertThat(hasReview).hasSize(4);
    }

    @Test
    @DisplayName("[7-4] notExists — 후기가 하나도 없는 상품 (24건)")
    void notExistsProducts() {
        List<Product> noReview = queryFactory
                .selectFrom(product)
                .where(selectOne()
                        .from(review)
                        .where(review.product.eq(product))
                        .notExists())
                .orderBy(product.id.asc())
                .fetch();

        System.out.println("후기 없는 상품 = " + noReview.size() + "개");
        assertThat(noReview).hasSize(24);
    }

    @Test
    @DisplayName("[7-4] exists 는 BooleanExpression 이라 메서드로 뽑아 재사용된다")
    void existsAsReusableCondition() {
        List<Customer> vipWithReview = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP), hasReview())
                .fetch();

        vipWithReview.forEach(c -> System.out.println(c.getName()));
    }

    /** 서브쿼리 조건을 메서드로 뽑는다. 문자열 JPQL 로는 못 하는 일. */
    private com.querydsl.core.types.dsl.BooleanExpression hasReview() {
        return selectOne().from(review).where(review.customer.eq(customer)).exists();
    }

    // =================================================================
    // [7-5] ⚠️ NOT IN + NULL — 조용히 0건
    // =================================================================

    @Test
    @DisplayName("[7-5] ⚠️ notIn 함정 — 답은 10인데 0건이 나온다")
    void notInNullTrap() {
        QEmployee manager = new QEmployee("mgr");

        List<Employee> leaf = queryFactory
                .selectFrom(employee)
                .where(employee.id.notIn(
                        select(manager.manager.id).from(manager)
                ))
                .fetch();

        // 생성 SQL:
        //   where e1_0.employee_id not in (select m1_0.manager_id from employees m1_0)
        // 서브쿼리 결과에 NULL(CEO) 이 섞여 있어 3값 논리로 전체가 UNKNOWN → 0건
        System.out.println("부하 없는 사원(잘못된 답) = " + leaf.size() + "명");
        assertThat(leaf).isEmpty();
    }

    @Test
    @DisplayName("[7-5] 원인 확인 — 서브쿼리 결과에 null 이 섞여 있다")
    void managerIdsIncludingNull() {
        List<Long> managerIds = queryFactory
                .select(employee.manager.id)
                .from(employee)
                .fetch();

        System.out.println(managerIds);   // [null, 1, 1, 1, 2, 2, 3, ...]
        assertThat(managerIds).contains((Long) null);
    }

    @Test
    @DisplayName("[7-5] 처방 1 — 서브쿼리에서 NULL 제거 (10건)")
    void fixWithIsNotNull() {
        QEmployee manager = new QEmployee("mgr");

        List<Employee> fixed = queryFactory
                .selectFrom(employee)
                .where(employee.id.notIn(
                        select(manager.manager.id)
                                .from(manager)
                                .where(manager.manager.isNotNull())
                ))
                .orderBy(employee.id.asc())
                .fetch();

        assertThat(fixed).hasSize(10);
    }

    @Test
    @DisplayName("[7-5] 처방 2 — notExists (권장, 10건)")
    void fixWithNotExists() {
        QEmployee manager = new QEmployee("mgr");

        List<Employee> fixed = queryFactory
                .selectFrom(employee)
                .where(selectOne()
                        .from(manager)
                        .where(manager.manager.eq(employee))
                        .notExists())
                .orderBy(employee.id.asc())
                .fetch();

        // not exists 는 = 비교이므로 NULL 이면 그냥 매칭이 안 될 뿐,
        // UNKNOWN 이 바깥으로 전파되지 않는다. 구조적으로 안전하다.
        assertThat(fixed).hasSize(10);
    }

    @Test
    @DisplayName("[7-5] 처방 3 — 안티 조인 (10건)")
    void fixWithAntiJoin() {
        QEmployee manager = new QEmployee("mgr");

        List<Employee> fixed = queryFactory
                .selectFrom(employee)
                .leftJoin(manager).on(manager.manager.eq(employee))
                .where(manager.id.isNull())      // ← 조인 대상의 PK 에 걸어야 한다
                .orderBy(employee.id.asc())
                .fetch();

        assertThat(fixed).hasSize(10);
    }

    // =================================================================
    // [7-6] select 절 서브쿼리 — 편리하지만 비싸다
    // =================================================================

    @Test
    @DisplayName("[7-6] select 절 상관 서브쿼리 — 행 수만큼 실행된다")
    void selectSubqueryCost() {
        Expression<Long> orderCount = ExpressionUtils.as(
                select(order.count()).from(order).where(order.customer.eq(customer)),
                "orderCount");

        List<Tuple> rows = queryFactory
                .select(customer.id, customer.name, orderCount)
                .from(customer)
                .orderBy(customer.id.asc())
                .fetch();

        // 생성 SQL:
        //   select c1_0.customer_id, c1_0.name,
        //          (select count(o1_0.order_id) from orders o1_0
        //           where o1_0.customer_id = c1_0.customer_id)
        //   from customers c1_0
        //
        // 쿼리는 1개지만 DB 내부에서 orders 를 30번 센다.
        for (Tuple t : rows) {
            System.out.printf("%3d %-8s %d%n",
                    t.get(customer.id), t.get(customer.name), t.get(orderCount));
        }
    }

    @Test
    @DisplayName("[7-6] 같은 결과를 leftJoin + groupBy 로 — orders 를 한 번만 훑는다")
    void groupByJoinEquivalent() {
        List<Tuple> rows = queryFactory
                .select(customer.id, customer.name, order.count())
                .from(customer)
                .leftJoin(order).on(order.customer.eq(customer))   // innerJoin 이면 0건 고객이 사라진다
                .groupBy(customer.id, customer.name)
                .orderBy(customer.id.asc())
                .fetch();

        // 생성 SQL:
        //   select c1_0.customer_id, c1_0.name, count(o1_0.order_id)
        //   from customers c1_0
        //   left join orders o1_0 on o1_0.customer_id = c1_0.customer_id
        //   group by c1_0.customer_id, c1_0.name
        System.out.println("조인+groupBy = " + rows.size() + "행");
        assertThat(rows).hasSize(30);
    }

    // =================================================================
    // [7-7] ⚠️ from 절 서브쿼리(인라인 뷰)는 쓸 수 없다
    // =================================================================

    @Test
    @DisplayName("[7-7] from 절 서브쿼리는 API 자체가 없다 (주석 참고)")
    void fromClauseSubqueryIsImpossible() {
        // ❌ 아래는 컴파일이 되지 않습니다. 주석을 풀면 빌드가 깨집니다.
        //
        // queryFactory
        //         .select(...)
        //         .from(select(order.customer.id, order.count())
        //                 .from(order)
        //                 .groupBy(order.customer.id));
        //
        // JPAQuery.from(...) 의 시그니처는 from(EntityPath<?>... sources) 입니다.
        // EntityPath 는 Q타입만 구현하므로 서브쿼리는 애초에 들어가지 않습니다.

        // ❌ 문자열 JPQL 로 우회해도 런타임에 죽습니다. 주석을 풀면 예외가 납니다.
        //
        // em.createQuery("""
        //         select d.cid, d.cnt
        //         from (select o.customer.id as cid, count(o) as cnt
        //               from Order o group by o.customer.id) d
        //         """).getResultList();
        //
        // org.hibernate.query.SemanticException: Could not interpret path expression 'd.cid'
        //   → 에러 메시지는 d.cid 를 탓하지만 진짜 원인은 from (...) 자체입니다.

        System.out.println("from 절 서브쿼리: QueryDSL-JPA 는 컴파일 단계에서 막힌다");
    }

    @Test
    @DisplayName("[7-7] 우회 ① — 상관 서브쿼리로 재작성 (카테고리별 최고가, 12건)")
    void workaroundCorrelated() {
        QProduct sub = new QProduct("sub");

        List<Product> topPerCategory = queryFactory
                .selectFrom(product)
                .where(product.price.eq(
                        select(sub.price.max())
                                .from(sub)
                                .where(sub.category.eq(product.category))
                ))
                .orderBy(product.category.id.asc(), product.id.asc())
                .fetch();

        // 생성 SQL:
        //   where p1_0.price = (select max(s1_0.price) from products s1_0
        //                       where s1_0.category_id = p1_0.category_id)
        topPerCategory.forEach(p ->
                System.out.printf("%3d %-24s %s%n", p.getId(), p.getName(), p.getPrice()));
        assertThat(topPerCategory).hasSize(12);
    }

    @Test
    @DisplayName("[7-7] 우회 ② — 쿼리 2회 + 애플리케이션 조합 (12건)")
    void workaroundTwoQueries() {
        // 1단계: 카테고리별 최고가 (12행)
        List<Tuple> maxRows = queryFactory
                .select(product.category.id, product.price.max())
                .from(product)
                .groupBy(product.category.id)
                .fetch();

        // 2단계: (카테고리, 가격) 쌍으로 상품 조회
        BooleanBuilder pairs = new BooleanBuilder();
        for (Tuple t : maxRows) {
            Long categoryId = t.get(product.category.id);
            BigDecimal maxPrice = t.get(product.price.max());
            pairs.or(product.category.id.eq(categoryId).and(product.price.eq(maxPrice)));
        }

        List<Product> result = queryFactory
                .selectFrom(product)
                .where(pairs)
                .orderBy(product.category.id.asc(), product.id.asc())
                .fetch();

        // ⚠️ maxRows 가 10만 행이면 or 가 10만 개 붙습니다.
        //    1단계 결과 크기에 상한이 있는지 반드시 확인하십시오.
        System.out.println("쿼리 2회 방식 = " + result.size() + "건");
        assertThat(result).hasSize(12);
    }

    @Test
    @DisplayName("[7-7] 우회 ③ — 네이티브 쿼리는 인라인 뷰가 그대로 된다")
    void workaroundNative() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.product_id, p.name, p.price
                FROM products p
                JOIN (SELECT category_id, MAX(price) AS mx
                      FROM products GROUP BY category_id) m
                  ON m.category_id = p.category_id AND m.mx = p.price
                ORDER BY p.category_id, p.product_id
                """).getResultList();

        // 되기는 됩니다. 대신 타입 안전성과 DB 이식성을 잃습니다.
        rows.forEach(r -> System.out.println(r[0] + " " + r[1] + " " + r[2]));
        assertThat(rows).hasSize(12);
    }

    // =================================================================
    // [7-9] 서브쿼리 vs 조인 — 같은 답, 다른 SQL
    // =================================================================

    @Test
    @DisplayName("[7-9] A. exists — 후기를 받은 상품 (16건)")
    void reviewedByExists() {
        List<Product> byExists = queryFactory
                .selectFrom(product)
                .where(selectOne().from(review).where(review.product.eq(product)).exists())
                .orderBy(product.id.asc())
                .fetch();

        assertThat(byExists).hasSize(16);
    }

    @Test
    @DisplayName("[7-9] B. 조인 + distinct — 같은 16건")
    void reviewedByJoinDistinct() {
        List<Product> byJoin = queryFactory
                .selectFrom(product).distinct()
                .join(review).on(review.product.eq(product))
                .orderBy(product.id.asc())
                .fetch();

        assertThat(byJoin).hasSize(16);
    }

    @Test
    @DisplayName("[7-9] ⚠️ distinct 를 빠뜨리면 후기 수만큼 부풀어 오른다 (80건)")
    void reviewedByJoinWithoutDistinct() {
        List<Product> wrong = queryFactory
                .selectFrom(product)
                .join(review).on(review.product.eq(product))
                .fetch();

        System.out.println("distinct 없는 조인 = " + wrong.size() + "행 (16이어야 하는데 80)");
        assertThat(wrong).hasSize(80);
    }

    // =================================================================
    // [7-10] having 절 서브쿼리 / all / any
    // =================================================================

    @Test
    @DisplayName("[7-10] having 서브쿼리 — 전체 평균보다 평균이 높은 고객")
    void havingSubquery() {
        QOrder o2 = new QOrder("o2");

        List<Tuple> aboveAvg = queryFactory
                .select(order.customer.id, order.totalAmount.avg())
                .from(order)
                .groupBy(order.customer.id)
                .having(order.totalAmount.avg().gt(
                        select(o2.totalAmount.avg()).from(o2)
                ))
                .orderBy(order.customer.id.asc())
                .fetch();

        System.out.println("평균 초과 고객 = " + aboveAvg.size() + "명");
    }

    @Test
    @DisplayName("[7-10] all — 모든 주변기기보다 비싼 노트북")
    void allComparison() {
        QProduct acc = new QProduct("acc");

        List<Product> pricier = queryFactory
                .selectFrom(product)
                .where(product.category.id.eq(21L)
                        .and(product.price.gt(
                                select(acc.price).from(acc).where(acc.category.id.eq(23L)).all()
                        )))
                .orderBy(product.price.asc())
                .fetch();

        // ⚠️ 서브쿼리가 0행이면 > all 은 '항상 참' 이 됩니다. 직관과 반대입니다.
        pricier.forEach(p -> System.out.println(p.getName() + " " + p.getPrice()));
    }

    @Test
    @DisplayName("[7-10] any — 어떤 주변기기보다든 비싼 노트북")
    void anyComparison() {
        QProduct acc = new QProduct("acc");

        List<Product> pricier = queryFactory
                .selectFrom(product)
                .where(product.category.id.eq(21L)
                        .and(product.price.gt(
                                select(acc.price).from(acc).where(acc.category.id.eq(23L)).any()
                        )))
                .orderBy(product.price.asc())
                .fetch();

        // > any 는 > min 과 같습니다.
        System.out.println("any = " + pricier.size() + "건");
    }

    // =================================================================
    // [보너스] 결제가 없는 주문 — 두 방법 (연습문제 4번 미리보기)
    // =================================================================

    @Test
    @DisplayName("[보너스] 결제 없는 주문 60건 — notExists")
    void ordersWithoutPayment() {
        List<Order> pending = queryFactory
                .selectFrom(order)
                .where(selectOne().from(payment).where(payment.order.eq(order)).notExists())
                .orderBy(order.id.asc())
                .fetch();

        assertThat(pending).hasSize(60);
    }
}
