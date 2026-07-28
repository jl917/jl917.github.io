package com.example.shop.step07;

import com.example.shop.entity.Order;
import com.example.shop.entity.Product;
import com.example.shop.entity.QOrder;
import com.example.shop.entity.QPayment;
import com.example.shop.entity.QProduct;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.ExpressionUtils;
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
import static com.example.shop.entity.QPayment.payment;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;
import static com.querydsl.jpa.JPAExpressions.select;
import static com.querydsl.jpa.JPAExpressions.selectOne;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 07 — 서브쿼리 : 연습문제 정답과 해설
 *
 * <p>답만 보지 말고 주석을 반드시 읽으십시오.
 * 이 스텝의 함정은 대부분 "답은 맞는데 SQL 이 틀린" 형태로 나타납니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 문제 1. 전체 평균가보다 비싼 상품 (11건)
    // =================================================================
    @Test
    @DisplayName("A1. 전체 평균가보다 비싼 상품")
    void a1_aboveAveragePrice() {
        QProduct sub = new QProduct("sub");

        List<Product> result = queryFactory
                .selectFrom(product)
                .where(product.price.gt(
                        select(sub.price.avg()).from(sub)
                ))
                .orderBy(product.price.desc())
                .fetch();

        assertThat(result).hasSize(11);

        // 생성 SQL
        //   select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
        //          p1_0.name, p1_0.price, p1_0.status, p1_0.stock
        //   from products p1_0
        //   where p1_0.price > (select avg(p2_0.price) from products p2_0)
        //   order by p1_0.price desc
        //
        // ── 해설 ────────────────────────────────────────────────
        // p1_0 = 바깥 루트(product), p2_0 = 서브쿼리 루트(sub).
        // 별칭이 다르다는 것이 전부입니다. 이걸 확인하는 것이 이 문제의 목적입니다.
        //
        // 만약 new QProduct("sub") 없이 그냥 product 를 두 번 썼다면
        //   where p1_0.price > (select avg(p1_0.price) from products p1_0)
        // 이 되어 "자기 자신의 평균" = 자기 가격이 되고, price > price 는 영원히 거짓입니다.
        // 결과 0건. 에러 없음. 로그 깨끗함. 이것이 7-2 절의 함정입니다.
        //
        // avg() 의 반환 타입이 Double 인 점도 눈여겨보십시오. price 는 BigDecimal 인데
        // avg 는 Double 을 돌려줍니다. gt() 비교는 QueryDSL 이 알아서 처리하지만,
        // 값을 직접 꺼낼 때는 타입이 문제가 됩니다 (Step 08 의 8-5 절).
        //
        // 평균가는 318,582.5 이고, 그보다 비싼 상품이 40개 중 11개입니다.
        // MySQL8 코스 Step 08 의 8-1 절과 정확히 같은 숫자입니다.
    }

    // =================================================================
    // 문제 2. 후기 없는 상품 — notExists (24건)
    // =================================================================
    @Test
    @DisplayName("A2. 후기 없는 상품 — notExists")
    void a2_noReviewByNotExists() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(selectOne()
                        .from(review)
                        .where(review.product.eq(product))
                        .notExists())
                .orderBy(product.id.asc())
                .fetch();

        assertThat(result).hasSize(24);

        // 생성 SQL
        //   from products p1_0
        //   where not exists (select 1 from reviews r1_0 where r1_0.product_id = p1_0.product_id)
        //   order by p1_0.product_id
        //
        // ── 해설 ────────────────────────────────────────────────
        // 여기서는 바깥과 같은 product 인스턴스를 씁니다. 문제 1 과 정반대입니다.
        // 이유는 목적이 다르기 때문입니다.
        //   - 문제 1: 서브쿼리가 바깥과 무관하게 한 번만 계산 → 별칭 분리
        //   - 문제 2: 서브쿼리가 바깥 행마다 다시 계산 (상관) → 같은 인스턴스
        // review.product.eq(product) 의 product 가 바깥을 가리켜야 상관 서브쿼리가 됩니다.
        //
        // selectOne() 은 select 1 이 됩니다. exists 는 존재 여부만 보므로
        // 안쪽에 무엇을 select 하든 결과도 성능도 같습니다. 관례가 selectOne() 입니다.
        //
        // 상품 40개 중 후기를 가진 것이 16개이므로 24개가 답입니다.
    }

    // =================================================================
    // 문제 3. 후기 없는 상품 — notIn (방어적으로, 24건)
    // =================================================================
    @Test
    @DisplayName("A3. 후기 없는 상품 — notIn")
    void a3_noReviewByNotIn() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(product.id.notIn(
                        select(review.product.id)
                                .from(review)
                                .where(review.product.isNotNull())   // ← 방어적 필터
                ))
                .orderBy(product.id.asc())
                .fetch();

        assertThat(result).hasSize(24);

        // 생성 SQL
        //   where p1_0.product_id not in (
        //           select r1_0.product_id from reviews r1_0 where r1_0.product_id is not null)
        //
        // ── 해설 ────────────────────────────────────────────────
        // 지금 스키마에서 reviews.product_id 는 NOT NULL 입니다.
        // 그래서 is not null 을 빼도 답은 24 로 똑같이 나옵니다.
        //
        // 그런데도 붙이라고 하는 이유는 두 가지입니다.
        //
        // 1) 스키마는 바뀝니다.
        //    "이 컬럼은 NOT NULL 이니까 괜찮아" 는 오늘 기준의 판단입니다.
        //    6개월 뒤 누군가 nullable 로 바꾸는 순간, 이 쿼리는 에러 없이 0건을 돌려주기 시작합니다.
        //    그리고 아무도 모릅니다. 배치가 그냥 아무것도 안 하고 성공합니다.
        //
        // 2) 코드를 읽는 사람에게 의도를 알립니다.
        //    is not null 이 붙어 있으면 "이 사람은 NOT IN + NULL 을 알고 있구나" 가 전달됩니다.
        //
        // 다만 근본적으로는 A2 처럼 notExists 를 쓰는 쪽이 낫습니다.
        // notExists 는 방어 코드가 아예 필요 없습니다. 구조 자체가 안전하기 때문입니다.
        // 팀 컨벤션으로 "부정형 서브쿼리는 notExists" 를 정해 두는 것을 권합니다.
    }

    // =================================================================
    // 문제 4. 결제 없는 주문 — 두 방법 (각 60건)
    // =================================================================
    @Test
    @DisplayName("A4. 결제 없는 주문 — notExists / 안티 조인")
    void a4_ordersWithoutPayment() {
        // (a) notExists
        List<Order> byNotExists = queryFactory
                .selectFrom(order)
                .where(selectOne()
                        .from(payment)
                        .where(payment.order.eq(order))
                        .notExists())
                .orderBy(order.id.asc())
                .fetch();

        // (b) leftJoin + isNull (안티 조인)
        QPayment p = new QPayment("p");
        List<Order> byAntiJoin = queryFactory
                .selectFrom(order)
                .leftJoin(p).on(p.order.eq(order))
                .where(p.id.isNull())          // ← PK 에 걸어야 한다
                .orderBy(order.id.asc())
                .fetch();

        assertThat(byNotExists).hasSize(60);
        assertThat(byAntiJoin).hasSize(60);
        assertThat(byNotExists).hasSameSizeAs(byAntiJoin);

        // 생성 SQL (a)
        //   from orders o1_0
        //   where not exists (select 1 from payments p1_0 where p1_0.order_id = o1_0.order_id)
        //
        // 생성 SQL (b)
        //   from orders o1_0
        //   left join payments p1_0 on p1_0.order_id = o1_0.order_id
        //   where p1_0.payment_id is null
        //
        // ── 해설 ────────────────────────────────────────────────
        // 결제가 없는 주문 = PENDING 주문 60건입니다.
        // 주문 600건 중 540건에 결제가 1건씩 붙어 있고, PENDING 60건에는 결제가 없습니다.
        //
        // (b) 에서 가장 흔한 실수는 isNull() 을 엉뚱한 컬럼에 거는 것입니다.
        //   ⭕ p.id.isNull()        — payments 의 PK. NOT NULL 이므로 "조인 실패" 만 잡아낸다
        //   ❌ p.amount.isNull()    — amount 가 nullable 이면 "조인은 됐는데 금액이 NULL 인 행" 도 섞인다
        //   ❌ p.status.isNull()    — 같은 문제
        // 안티 조인의 isNull 은 반드시 "조인 대상 테이블의 NOT NULL 컬럼(보통 PK)" 에 걸어야 합니다.
        //
        // 어느 쪽이 나은가?
        // 결과에 payments 컬럼이 전혀 필요 없으므로 (a) 가 의도를 더 정확히 표현합니다.
        // (b) 는 조인을 하지만 조인 결과를 버립니다. 낭비이고, 1:N 이면 fan-out 위험도 있습니다.
        // 여기서는 주문:결제가 1:1 이라 부풀지 않았지만, 1:N 이었다면 distinct 가 필요했을 것입니다.
    }

    // =================================================================
    // 문제 5. 고객별 후기 수 — 서브쿼리 vs 조인 (각 30행)
    // =================================================================
    @Test
    @DisplayName("A5. 고객별 후기 수")
    void a5_reviewCountPerCustomer() {
        // (a) select 절 상관 서브쿼리
        Expression<Long> reviewCount = ExpressionUtils.as(
                select(review.count()).from(review).where(review.customer.eq(customer)),
                "reviewCount");

        List<Tuple> bySubquery = queryFactory
                .select(customer.id, customer.name, reviewCount)
                .from(customer)
                .orderBy(customer.id.asc())
                .fetch();

        // (b) leftJoin + groupBy
        List<Tuple> byJoin = queryFactory
                .select(customer.id, customer.name, review.count())
                .from(customer)
                .leftJoin(review).on(review.customer.eq(customer))
                .groupBy(customer.id, customer.name)
                .orderBy(customer.id.asc())
                .fetch();

        assertThat(bySubquery).hasSize(30);
        assertThat(byJoin).hasSize(30);

        // 생성 SQL (a)
        //   select c1_0.customer_id, c1_0.name,
        //          (select count(r1_0.review_id) from reviews r1_0
        //           where r1_0.customer_id = c1_0.customer_id)
        //   from customers c1_0
        //   order by c1_0.customer_id
        //
        // 생성 SQL (b)
        //   select c1_0.customer_id, c1_0.name, count(r1_0.review_id)
        //   from customers c1_0
        //   left join reviews r1_0 on r1_0.customer_id = c1_0.customer_id
        //   group by c1_0.customer_id, c1_0.name
        //   order by c1_0.customer_id
        //
        // ── 해설 ────────────────────────────────────────────────
        // 두 SQL 은 결과가 같지만 DB 가 하는 일이 완전히 다릅니다.
        //
        //   (a) customers 를 훑으면서 행마다 reviews 를 센다  → reviews 접근 30회
        //   (b) 한 번 조인하고 group by 로 접는다              → reviews 접근 1회
        //
        // 고객이 30명이면 차이가 없습니다. 30만 명이면 (a) 는 30만 번입니다.
        // 그런데 애플리케이션 로그에는 둘 다 "SQL 1개" 로 보입니다.
        // N+1 은 로그에서 보이지만 이건 안 보입니다. 그래서 더 위험합니다.
        //
        // (b) 에서 leftJoin 이 아니라 innerJoin 을 쓰면?
        //   → 후기를 쓴 고객 4명만 남고 26명이 사라집니다. 26행이 조용히 없어집니다.
        //   "0건인 그룹을 결과에 남길 것인가" 는 leftJoin 이냐 innerJoin 이냐로 결정됩니다.
        //
        // (a) 를 써도 되는 경우: 바깥이 페이징으로 잘려 있어 행 수가 확정적으로 작을 때.
        //   예를 들어 한 페이지 20건짜리 목록 화면이라면 서브쿼리가 20번만 돕니다. 문제없습니다.
        //
        // ExpressionUtils.as 로 만든 표현식을 반드시 변수로 뽑아 두십시오.
        // tuple.get() 에 같은 객체를 넘겨야 값을 꺼낼 수 있습니다.
        // 매번 새로 만들어도 equals 로 동작하기는 하지만, 코드가 장황해지고 오타에 취약해집니다.
    }

    // =================================================================
    // 문제 6. 자기 카테고리 평균가보다 비싼 상품 (18건)
    // =================================================================
    @Test
    @DisplayName("A6. 자기 카테고리 평균가보다 비싼 상품")
    void a6_aboveCategoryAverage() {
        QProduct sub = new QProduct("sub");

        List<Product> result = queryFactory
                .selectFrom(product)
                .where(product.price.gt(
                        select(sub.price.avg())
                                .from(sub)
                                .where(sub.category.eq(product.category))   // 상관 조건
                ))
                .orderBy(product.category.id.asc(), product.price.desc())
                .fetch();

        assertThat(result).hasSize(18);

        // 생성 SQL
        //   from products p1_0
        //   where p1_0.price > (select avg(p2_0.price) from products p2_0
        //                       where p2_0.category_id = p1_0.category_id)
        //   order by p1_0.category_id, p1_0.price desc
        //
        // ── 해설 ────────────────────────────────────────────────
        // 이 문제의 핵심은 "상관 서브쿼리인데 왜 별칭을 분리했는가" 입니다.
        //
        // 7-4 절의 표를 다시 보면 이렇게 되어 있습니다.
        //   - 바깥과 무관하게 한 번 계산 → 분리
        //   - 바깥 행마다 재계산(상관)   → 같은 인스턴스
        //
        // 그런데 여기는 상관 서브쿼리인데도 분리했습니다. 모순이 아닙니다.
        // 정확히 말하면 규칙은 이렇습니다.
        //
        //   "서브쿼리의 FROM 에 들어가는 엔티티" 와
        //   "바깥에서 참조하고 싶은 엔티티" 가 같은 타입이면 → 반드시 분리한다.
        //   그리고 상관 조건에서 바깥 쪽을 명시적으로 가리킨다.
        //
        // 여기서는 sub 가 서브쿼리의 FROM 이고, product 가 바깥입니다.
        // where(sub.category.eq(product.category)) 에서
        //   sub.category    = 서브쿼리 쪽 (p2_0.category_id)
        //   product.category = 바깥 쪽    (p1_0.category_id)
        // 두 별칭이 달라야 이 조건이 의미를 갖습니다. 같으면 항상 참인 조건이 됩니다.
        //
        // 반면 A2 의 notExists 는 서브쿼리 FROM 이 reviews 이고 바깥이 products 로
        // 타입 자체가 다릅니다. 그래서 분리할 필요가 없었습니다.
        //
        // 판단 기준을 한 문장으로:
        //   "서브쿼리의 from 에 바깥과 같은 엔티티가 오는가?" → 오면 분리.
        //
        // 결과 18건은 MySQL8 코스 Step 08 의 8-11 절과 같은 숫자입니다.
    }

    // =================================================================
    // 문제 7. 카테고리별 최고가 상품 — 우회 ① vs ② (각 12건)
    // =================================================================
    @Test
    @DisplayName("A7. 카테고리별 최고가 상품 — 두 우회")
    void a7_maxPricePerCategory() {
        // (a) 우회 ① — 상관 서브쿼리 한 방
        QProduct sub = new QProduct("sub");

        List<Product> byCorrelated = queryFactory
                .selectFrom(product)
                .where(product.price.eq(
                        select(sub.price.max())
                                .from(sub)
                                .where(sub.category.eq(product.category))
                ))
                .orderBy(product.category.id.asc(), product.id.asc())
                .fetch();

        // (b) 우회 ② — 쿼리 2회 + 애플리케이션 조합
        List<Tuple> maxRows = queryFactory
                .select(product.category.id, product.price.max())
                .from(product)
                .groupBy(product.category.id)
                .fetch();

        BooleanBuilder pairs = new BooleanBuilder();
        for (Tuple t : maxRows) {
            Long categoryId = t.get(product.category.id);
            BigDecimal maxPrice = t.get(product.price.max());
            pairs.or(product.category.id.eq(categoryId).and(product.price.eq(maxPrice)));
        }

        List<Product> byTwoQueries = queryFactory
                .selectFrom(product)
                .where(pairs)
                .orderBy(product.category.id.asc(), product.id.asc())
                .fetch();

        assertThat(byCorrelated).hasSize(12);
        assertThat(byTwoQueries).hasSize(12);
        assertThat(byCorrelated).containsExactlyElementsOf(byTwoQueries);

        // 생성 SQL (a) — 쿼리 1개
        //   from products p1_0
        //   where p1_0.price = (select max(p2_0.price) from products p2_0
        //                       where p2_0.category_id = p1_0.category_id)
        //
        // 생성 SQL (b) — 쿼리 2개
        //   [1] select p1_0.category_id, max(p1_0.price) from products p1_0 group by p1_0.category_id
        //   [2] from products p1_0
        //       where p1_0.category_id = ? and p1_0.price = ?
        //          or p1_0.category_id = ? and p1_0.price = ?
        //          or ... (12쌍)
        //
        // ── 해설 ────────────────────────────────────────────────
        // SQL 이라면 이건 인라인 뷰 한 방입니다.
        //   FROM products p JOIN (SELECT category_id, MAX(price) ... GROUP BY category_id) m ON ...
        // 하지만 JPQL 은 from 절에 엔티티만 허용하므로 그 문장을 쓸 방법이 없습니다.
        // QueryDSL-JPA 는 JPQL 로 번역되므로 제약을 그대로 물려받습니다.
        // JPAQuery.from(...) 의 시그니처가 from(EntityPath<?>...) 이라 컴파일부터 막힙니다.
        //
        // 트레이드오프
        //   (a) 쿼리 1개. DB 내부에서 서브쿼리가 카테고리 수(12)만큼 평가됨.
        //       네트워크 왕복 1회. 코드가 짧고 의도가 명확.
        //   (b) 쿼리 2개. 각각은 단순. 네트워크 왕복 2회.
        //       1단계 결과가 커지면 2단계 where 절이 폭발함.
        //
        // 어느 쪽이 나은지는 상황에 따라 다릅니다.
        //   - 그룹 수가 작고(수십) 안정적 → (a)
        //   - 그룹은 많은데 1단계에 명확한 상한(예: 페이징)이 있음 → (b)
        //   - 네트워크 지연이 큰 환경(원격 DB) → (a) 쪽이 유리
        //   - DB CPU 가 병목 → (b) 로 부담을 애플리케이션에 넘김
        //
        // (b) 의 위험은 반드시 기억하십시오.
        // maxRows 가 10만 행이면 or 조건이 10만 개 붙습니다.
        // MySQL 은 max_allowed_packet 초과로 죽거나, 실행 계획이 무너집니다.
        // 1단계 결과 크기에 상한이 없다면 배치로 쪼개야 합니다.
        //
        // 세 번째 선택지도 있었습니다: 네이티브 쿼리 또는 QueryDSL-SQL.
        // QueryDSL-SQL 은 JPQL 을 거치지 않으므로 인라인 뷰가 가능하지만,
        // Q타입을 DB 스키마에서 생성해야 하고 설정이 완전히 다릅니다.
        // 이 코스 범위 밖이지만 존재는 알아 두십시오.
        //
        // 참고: 결과 12건은 소분류 12개 각각의 최고가 상품입니다.
        // 대분류 5개에는 상품이 직접 붙어 있지 않으므로 그룹에 나타나지 않습니다.
    }
}
