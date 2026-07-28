package com.example.shop.step14;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 14 — 성능과 최종 프로젝트.
 *
 * 이 파일은 두 부분입니다.
 *   1) 14-1 ~ 14-8 의 측정 코드
 *   2) 14-9 의 최종 프로젝트 (상품 검색 API)
 *
 * 측정 코드를 먼저 돌리십시오. 숫자를 직접 보지 않으면 이 스텝은 읽은 것에 그칩니다.
 *
 * 사전 설정 (application.yml):
 *   spring.jpa.properties.hibernate.generate_statistics: true
 *   logging.level.org.hibernate.stat: debug
 *
 * 실행:
 *   ./gradlew test --tests 'com.example.shop.step14.Practice'
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    Statistics stats;

    @BeforeEach
    void statisticsSetup() {
        // [14-1 ⑤] Hibernate statistics 로 쿼리 개수를 셉니다.
        stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
    }

    private void printStats(String label) {
        System.out.printf("[%s] 쿼리 %d개 / 엔티티 로드 %d개 / 컬렉션 페치 %d개%n",
                label,
                stats.getQueryExecutionCount() + stats.getPrepareStatementCount(),
                stats.getEntityLoadCount(),
                stats.getCollectionFetchCount());
    }

    // ════════════════════════════════════════════════════════════════
    // [14-1] 생성 SQL 확인법
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[14-1 ④] JPAQuery.toString() 으로 JPQL 보기 — DB 에 나가지 않습니다")
    void jpqlToString() {
        JPAQuery<Customer> query = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP)
                        .and(customer.points.goe(10000)))
                .orderBy(customer.points.desc());

        System.out.println("── JPQL ──");
        System.out.println(query);        // toString(). fetch() 를 부르지 않아도 됩니다.

        // select customer
        // from Customer customer
        // where customer.grade = ?1 and customer.points >= ?2
        // order by customer.points desc

        // Step 04 의 or 괄호 문제는 SQL 까지 안 가고 여기서 잡힙니다.
        System.out.println("── 괄호가 사라진 JPQL ──");
        System.out.println(queryFactory.selectFrom(customer)
                .where(customer.city.eq("서울")
                        .and(customer.grade.eq(Grade.VIP))
                        .or(customer.points.goe(10000))));

        // where customer.city = ?1 and customer.grade = ?2 or customer.points >= ?3
        //   ↑ 괄호가 없습니다. 한 줄로 확인할 수 있습니다.

        assertThat(query.toString()).contains("order by customer.points desc");
    }

    // ════════════════════════════════════════════════════════════════
    // [14-2] N+1 진단
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[14-2] N+1 — 주문 10건에 쿼리 11개. 결과는 완벽하게 맞습니다")
    void nPlusOne() {
        List<Order> orders = queryFactory
                .selectFrom(order)
                .limit(10)
                .fetch();

        for (Order o : orders) {
            // 여기서 지연 로딩이 터집니다. 주문마다 1번씩.
            System.out.println(o.getId() + " → " + o.getCustomer().getName());
        }

        printStats("N+1");
        // executing 11 JDBC statements
        //
        // 결과는 완벽하게 맞습니다. 고객 이름도 전부 정확합니다. 느릴 뿐입니다.
        // 그리고 10건에서는 느린 것도 안 느껴집니다.
        //
        // 600건을 전부 돌면 601개. 각 쿼리가 1ms 라도 0.6초입니다.
        // 네트워크 왕복이 있으면 5ms × 600 = 3초입니다.
        //
        // ⚠️ 유일한 발견 방법은 쿼리 개수를 세는 것입니다. 눈으로 로그를 보다가는 놓칩니다.

        assertThat(orders).hasSize(10);
    }

    // ════════════════════════════════════════════════════════════════
    // [14-3] N+1 해결 3가지
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[14-3 ①] fetch join — 쿼리 1개")
    void fetchJoinSolution() {
        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()
                .limit(10)
                .fetch();

        for (Order o : orders) {
            System.out.println(o.getId() + " → " + o.getCustomer().getName());
        }

        printStats("fetch join");
        // executing 1 JDBC statements
        //
        // customers 의 전 컬럼이 select 절에 함께 실려 왔습니다.
        // @ManyToOne 이므로 행이 늘지 않습니다. 이 경우 fetch join 이 최선입니다.

        assertThat(orders).hasSize(10);
    }

    @Test
    @DisplayName("[14-3 ①] ⚠️ 컬렉션 fetch join + 페이징 = 전건 메모리 로딩")
    void fetchJoinWithPagingTrap() {
        // ⚠️ 이 코드는 함정을 확인하기 위한 것입니다.
        //    콘솔에 아래 경고가 찍히는지 보십시오.
        //      HHH90003004: firstResult/maxResults specified with collection fetch;
        //                   applying in memory
        //    그리고 생성 SQL 에 limit 이 **없는** 것을 확인하십시오.
        //
        //    조인으로 행이 1200건으로 뻥튀기되면 limit 10 을 SQL 에 붙일 수 없습니다.
        //    "주문 10건"이 아니라 "조인 결과 10행"이 되어 버리기 때문입니다.
        //    그래서 Hibernate 는 1200행을 전부 메모리로 읽고 자바에서 잘라냅니다.
        //
        //    경고 로그 한 줄만 찍히고 결과는 정확합니다. 이것이 위험한 이유입니다.
        //    orders 가 600건이면 1200행이지만, 60만 건이면 120만 행을 힙에 올립니다.

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.orderItems, com.example.shop.entity.QOrderItem.orderItem).fetchJoin()
                .offset(0).limit(10)
                .fetch();

        printStats("collection fetch join + paging");
        System.out.println("조회된 주문: " + orders.size() + "건 (limit 10 인데도)");

        // 컬렉션 fetch join 은 하나만 가능합니다.
        // orderItems 와 payments 를 동시에 하면 MultipleBagFetchException 입니다.
    }

    @Test
    @DisplayName("[14-3 ②] batch size — 쿼리 2개, where id in (?,?,...). 페이징과 공존")
    void batchSizeSolution() {
        // application.yml 의 default_batch_fetch_size: 100 이 적용된 상태입니다.
        // 코드는 아무것도 바꾸지 않습니다.

        List<Order> orders = queryFactory
                .selectFrom(order)
                .limit(10)
                .fetch();

        for (Order o : orders) {
            System.out.println(o.getId() + " → 항목 " + o.getOrderItems().size() + "개");
        }

        printStats("batch size");
        // executing 2 JDBC statements
        //   ① select ... from orders o1_0 limit ?
        //   ② select ... from order_items oi1_0 where oi1_0.order_id in (?,?,?,?,?,?,?,?,?,?)
        //
        // 첫 지연 로딩이 발생하는 순간 Hibernate 가
        // "어차피 이 컬렉션들도 곧 필요하겠지" 하고 최대 batch_fetch_size 개를 모읍니다.
        //
        // 그리고 limit 이 SQL 에 그대로 있습니다. 페이징과 공존합니다.
        //
        // batch_fetch_size 별 부모 1000건일 때 쿼리 수:
        //   없음 → 1001,  10 → 101,  100 → 11,  1000 → 2
        // 실무 기본값은 100~1000 입니다.

        assertThat(orders).hasSize(10);
    }

    @Test
    @DisplayName("[14-3 ③] DTO 직접 조회 — 쿼리 1개, 필요한 컬럼만, 엔티티 없음")
    void dtoSolution() {
        List<Tuple> result = queryFactory
                .select(order.id, order.orderDate, order.totalAmount,
                        customer.name, customer.grade)
                .from(order)
                .join(order.customer, customer)
                .limit(10)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(order.id) + " → " + t.get(customer.name)));

        printStats("DTO");
        // executing 1 JDBC statements, 그리고 컬럼이 5개뿐입니다.
        // fetch join(①)은 양쪽 엔티티의 전 컬럼 13개를 읽었습니다.
        //
        // 대신 잃는 것:
        //   - 영속성 컨텍스트가 관리하지 않습니다. 변경 감지가 없습니다.
        //   - 지연 로딩이 없습니다. DTO 에 없는 필드는 그냥 없습니다.
        //   - DTO 클래스가 늘어납니다.
        //
        // 실무의 기본 조합:
        //   1. default_batch_fetch_size 를 전역으로 켭니다 (안전망)
        //   2. 읽기 전용 조회 API 는 DTO 직접 조회
        //   3. 수정이 필요한 흐름에서만 엔티티를 fetch join

        assertThat(result).hasSize(10);
        assertThat(stats.getEntityLoadCount()).isZero();   // 엔티티를 안 만듭니다
    }

    // ════════════════════════════════════════════════════════════════
    // [14-4] exists vs count
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[14-4] exists vs count — 0.180초 vs 0.002초")
    void existsVsCount() {
        // ❌ count > 0 — 전부 셉니다
        long t1 = System.nanoTime();
        List<Customer> byCount = queryFactory
                .selectFrom(customer)
                .where(JPAExpressions
                        .select(order.count())
                        .from(order)
                        .where(order.customer.eq(customer))
                        .gt(0L))
                .fetch();
        long e1 = System.nanoTime() - t1;

        // where (select count(o1_0.order_id) from orders o1_0
        //        where o1_0.customer_id = c1_0.customer_id) > ?
        //
        // count 는 조건에 맞는 행을 끝까지 다 셉니다.
        // 어떤 고객이 주문 200건을 가지고 있어도 200건을 전부 세고 나서 > 0 을 판정합니다.

        em.clear();

        // ✅ exists — 첫 행에서 멈춥니다
        long t2 = System.nanoTime();
        List<Customer> byExists = queryFactory
                .selectFrom(customer)
                .where(JPAExpressions
                        .selectOne()
                        .from(order)
                        .where(order.customer.eq(customer))
                        .exists())
                .fetch();
        long e2 = System.nanoTime() - t2;

        // where exists (select 1 from orders o1_0 where o1_0.customer_id = c1_0.customer_id)
        //
        // EXISTS 는 첫 매칭 행을 찾는 순간 서브쿼리를 끝냅니다.
        // EXPLAIN 에서 서브쿼리의 rows 가 20 → 1 로 줄고 Extra 에 FirstMatch 가 뜹니다.

        System.out.printf("count > 0 : %d건, %.3f초%n", byCount.size(), e1 / 1e9);
        System.out.printf("exists    : %d건, %.3f초%n", byExists.size(), e2 / 1e9);

        assertThat(byCount).hasSize(30);
        assertThat(byExists).hasSize(30);

        // 여러 번 돌려 평균을 보십시오. 첫 실행에는 JIT 워밍업과 캐시 효과가 섞입니다.
    }

    @Test
    @DisplayName("[14-4] 단건 존재 확인 — selectOne 은 컬럼을 하나도 안 읽습니다")
    void existsSingle() {
        String email = "seojun.kim@example.com";

        // ❌ 엔티티를 만들고 버립니다 (8컬럼)
        boolean bad = queryFactory.selectFrom(customer)
                .where(customer.email.eq(email))
                .fetchFirst() != null;

        // ✅ 1만 읽습니다
        Integer found = queryFactory.selectOne()
                .from(customer)
                .where(customer.email.eq(email))
                .fetchFirst();
        boolean good = found != null;

        // ❌ select c1_0.customer_id, c1_0.city, ... (8컬럼) from customers c1_0 where ... limit ?
        // ✅ select 1 from customers c1_0 where c1_0.email = ? limit ?

        System.out.println("bad=" + bad + ", good=" + good);
        assertThat(good).isEqualTo(bad);

        // 참고: fetchCount() / fetchResults() 는 5.x 부터 deprecated 입니다.
        //       group by / having / distinct 가 있는 쿼리에서 count 쿼리를 잘못 만듭니다.
        //       select(x.count()) 로 직접 쓰십시오 (Step 09).
    }

    // ════════════════════════════════════════════════════════════════
    // [14-5] 필요한 컬럼만
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[14-5] 엔티티 전체 vs 프로젝션 — 40행에서는 차이가 없습니다")
    void selectOnlyNeeded() {
        // A — 엔티티 전체 (8컬럼)
        List<Product> entities = queryFactory.selectFrom(product).fetch();
        printStats("엔티티");

        stats.clear();
        em.clear();

        // B — 필요한 두 컬럼만
        List<Tuple> tuples = queryFactory
                .select(product.name, product.price)
                .from(product)
                .fetch();
        printStats("프로젝션");

        // 40행에서는 차이가 오차 범위입니다. 그래서 개발 중에는 절대 보이지 않습니다.
        //
        // 엔티티 조회에는 컬럼 읽기 말고도 비용이 있습니다.
        //   - 1차 캐시 등록
        //   - 스냅샷 저장 (변경 감지용 원본 복사) → 메모리 두 배
        //   - 플러시 시 전 엔티티 비교
        //
        // 읽기 전용 조회에서 이 셋은 전부 낭비입니다.
        // 14-8 의 readOnly = true 가 뒤 두 개를 없앱니다.

        assertThat(entities).hasSize(40);
        assertThat(tuples).hasSize(40);
    }

    // ════════════════════════════════════════════════════════════════
    // [14-6] 인덱스를 죽이는 4가지 패턴
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[14-6] 패턴 2 — contains vs startsWith. 같은 like, 다른 계획")
    void likePatterns() {
        // ❌ contains → '%노트북%'. 인덱스는 앞에서부터 비교하므로 시작점을 못 찾습니다.
        List<Product> byContains = queryFactory
                .selectFrom(product)
                .where(product.name.contains("노트북"))
                .fetch();

        // where p1_0.name like ? escape '!'    -- '%노트북%'
        //   EXPLAIN → type: ALL

        // ✅ startsWith → '노트북%'. 인덱스 사용 가능.
        List<Product> byStartsWith = queryFactory
                .selectFrom(product)
                .where(product.name.startsWith("노트북"))
                .fetch();

        // where p1_0.name like ? escape '!'    -- '노트북%'
        //   EXPLAIN → type: range (name 인덱스가 있다면)

        System.out.println("contains: " + byContains.size()
                + "건, startsWith: " + byStartsWith.size() + "건");

        // 앞뒤 모두 일치가 요구사항이면 선택지는 셋입니다.
        //   1) FULLTEXT 인덱스 (한국어는 ngram 파서 필요)
        //   2) Elasticsearch 등 검색 엔진
        //   3) 그냥 둔다 — 4만 행이면 풀스캔이 더 빠릅니다. 재고 나서 결정하십시오.
    }

    @Test
    @DisplayName("[14-6] 패턴 4 — or 남발 vs in")
    void orVsIn() {
        // ❌ 다른 컬럼을 or 로 묶으면 어느 인덱스로도 커버가 안 됩니다
        List<Product> byOr = queryFactory
                .selectFrom(product)
                .where(product.name.eq("노트북")
                        .or(product.status.eq(ProductStatus.SOLD_OUT))
                        .or(product.stock.eq(0)))
                .fetch();

        // where p1_0.name = ? or p1_0.status = ? or p1_0.stock = ?
        //   EXPLAIN → type: ALL

        // ✅ 같은 컬럼의 or 는 in 으로
        List<Product> byIn = queryFactory
                .selectFrom(product)
                .where(product.status.in(ProductStatus.SOLD_OUT, ProductStatus.HIDDEN))
                .fetch();

        // where p1_0.status in (?,?)      -- 인덱스 사용 가능

        System.out.println("or: " + byOr.size() + "건, in: " + byIn.size() + "건");

        // 다른 컬럼이면 쿼리를 쪼개 각각 자기 인덱스를 쓰게 하고 자바에서 합칩니다.
        // ⚠️ JPQL 에는 UNION 이 없습니다. Hibernate 6 의 HQL 은 지원하지만
        //    QueryDSL-JPA 로는 만들 수 없습니다.
    }

    // ════════════════════════════════════════════════════════════════
    // [14-9] 종합 실습 — 상품 검색 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 검색 조건.
     *
     * ⚠️ 필드명이 sortKey / sortDirection 입니다. sort 가 아닙니다.
     *    Spring Data 의 Pageable 이 ?sort=price,desc 를 자동 파싱하므로,
     *    같은 이름을 쓰면 쿼리 파라미터를 둘이 나눠 갖게 되어 예측 불가능해집니다.
     */
    record ProductSearchCond(
            String keyword,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean inStockOnly,
            List<ProductStatus> statuses,
            String sortKey,          // "price" | "rating" | "created" | "name"
            String sortDirection     // "asc" | "desc"
    ) {}

    /**
     * 응답 DTO.
     *
     * 실제 프로젝트에서는 @QueryProjection 을 붙여 QProductSearchResponse 를 생성하고
     * new QProductSearchResponse(...) 로 조립합니다.
     * 이 테스트 파일에서는 APT 대상이 아니므로 Tuple 로 받은 뒤 매핑합니다.
     */
    record ProductSearchResponse(
            Long productId,
            String name,
            BigDecimal price,
            Integer stock,
            ProductStatus status,
            String categoryName,
            Double avgRating,
            Long reviewCount
    ) {}

    // ── 정렬 화이트리스트 (Step 10) ─────────────────────────────────
    // 사용자 입력은 이 Map 의 "키"로만 쓰입니다.
    // SQL 에 들어가는 것은 코드에 이미 존재하는 표현식뿐입니다.
    private static final Map<String, OrderSpecifier<?>> SORT_ASC = Map.of(
            "price",   product.price.asc(),
            "created", product.createdAt.asc(),
            "name",    product.name.asc(),
            "rating",  review.rating.avg().asc());

    private static final Map<String, OrderSpecifier<?>> SORT_DESC = Map.of(
            "price",   product.price.desc(),
            "created", product.createdAt.desc(),
            "name",    product.name.desc(),
            "rating",  review.rating.avg().desc());

    private static final OrderSpecifier<?> DEFAULT_SORT = product.id.desc();

    @Test
    @DisplayName("[14-9] 버전 A — 서브쿼리로 평점/후기 수")
    void searchWithSubquery() {
        ProductSearchCond cond = new ProductSearchCond(
                "노트", null, new BigDecimal("500000"), null,
                true, null, "price", "desc");
        Pageable pageable = PageRequest.of(0, 10);

        List<Tuple> content = queryFactory
                .select(product.id, product.name, product.price, product.stock,
                        product.status, category.name,
                        // 평균 평점 — 상관 서브쿼리
                        JPAExpressions.select(review.rating.avg())
                                .from(review).where(review.product.eq(product)),
                        // 후기 수 — 상관 서브쿼리
                        JPAExpressions.select(review.count())
                                .from(review).where(review.product.eq(product)))
                .from(product)
                .join(product.category, category)
                .where(keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()))
                .orderBy(toOrder(cond.sortKey(), cond.sortDirection(), false), product.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count 쿼리 (분리). join 도 서브쿼리도 없습니다.
        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()));

        // 실제 리포지토리에서는 PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne)
        // 로 감쌉니다. 결과가 한 페이지 안에 들어가면 count 쿼리가 아예 실행되지 않습니다 (Step 09).
        Long total = countQuery.fetchOne();

        content.forEach(t -> System.out.printf("%s | %s | 평점 %s | 후기 %d%n",
                t.get(product.name), t.get(product.price),
                t.get(6, Double.class), t.get(7, Long.class)));
        System.out.println("total = " + total);

        printStats("검색 A (서브쿼리)");

        // maxPrice 와 statuses 가 null 이었으므로 SQL 에 아예 없습니다.
        // where 1=1 같은 것도 없습니다. 이것이 동적 쿼리의 이점입니다.
        //
        // ⚠️ 버전 A 로는 "평점순 정렬"을 할 수 없습니다.
        //    서브쿼리 결과로 order by 를 하는 것은 QueryDSL 로 표현하기 어렵고 성능도 나쁩니다.
        //    요구사항에 평점순이 있으므로 최종 선택은 버전 B 입니다.

        assertThat(total).isNotNull();
    }

    @Test
    @DisplayName("[14-9] 버전 B — left join + group by. 집계값 정렬이 됩니다 (최종안)")
    void searchWithGroupBy() {
        ProductSearchCond cond = new ProductSearchCond(
                null, null, null, null,
                true, List.of(ProductStatus.ON_SALE), "rating", "desc");
        Pageable pageable = PageRequest.of(0, 10);

        List<Tuple> content = queryFactory
                .select(product.id, product.name, product.price, product.stock,
                        product.status, category.name,
                        review.rating.avg(),
                        review.count())
                .from(product)
                .join(product.category, category)
                .leftJoin(review).on(review.product.eq(product))   // ★ left join 필수
                .where(keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()))
                .groupBy(product.id, product.name, product.price, product.stock,
                        product.status, category.name)             // ★ 전부 나열
                .orderBy(toOrder(cond.sortKey(), cond.sortDirection(), true), product.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(product.count())
                .from(product)
                .where(keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()))
                .fetchOne();

        // ★ leftJoin 이므로 후기 없는 상품 24개도 결과에 남습니다.
        //   그 경우 avg 는 null, count 는 0 입니다.
        //   avg() 는 대상 행이 없으면 NULL, count() 는 0 을 반환합니다 (13-7).
        content.forEach(t -> System.out.printf("%s | 평점 %s | 후기 %d%n",
                t.get(product.name), t.get(review.rating.avg()), t.get(review.count())));
        System.out.println("total = " + total);

        printStats("검색 B (group by)");

        // A vs B
        //   페이지 작으면(10~20)         → A (서브쿼리가 페이지 안의 행에만 실행됨)
        //   페이지 크면(1000+)           → B (서브쿼리 2000번 vs 조인 1번)
        //   집계 대상이 여러 개           → B
        //   정렬 키가 집계값(평점순)      → B  ← 요구사항이 이것이므로 최종 선택
        //
        // 위 표는 "이럴 것이다"의 정리이지 정답표가 아닙니다.
        // 여러분의 데이터로 EXPLAIN ANALYZE 를 돌려 확인하십시오.

        assertThat(total).isNotNull();
    }

    // ── 동적 조건 (Step 04, 10) ─────────────────────────────────────
    // null 을 반환하면 where 에서 무시됩니다.
    // BooleanBuilder 대신 메서드로 분리하면 재사용과 조합이 됩니다.

    private BooleanExpression keywordContains(String keyword) {
        return (keyword == null || keyword.isBlank()) ? null : product.name.contains(keyword);
    }

    /** 선택한 카테고리 + 그 하위 카테고리 (Step 07) */
    private BooleanExpression categoryIn(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return product.category.id.eq(categoryId)
                .or(product.category.id.in(
                        JPAExpressions.select(category.id)
                                .from(category)
                                .where(category.parent.id.eq(categoryId))));
    }

    private BooleanExpression priceGoe(BigDecimal min) {
        return min == null ? null : product.price.goe(min);
    }

    private BooleanExpression priceLoe(BigDecimal max) {
        return max == null ? null : product.price.loe(max);
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        return Boolean.TRUE.equals(inStockOnly) ? product.stock.gt(0) : null;
    }

    private BooleanExpression statusIn(List<ProductStatus> statuses) {
        return (statuses == null || statuses.isEmpty()) ? null : product.status.in(statuses);
    }

    /**
     * 정렬 화이트리스트 (Step 10).
     * 입력은 Map 의 키로만 쓰입니다. SQL 에 들어가는 것은 코드에 있는 표현식뿐입니다.
     *
     * @param allowAggregate 집계값 정렬(rating)을 허용할지. 버전 A 에서는 false.
     */
    private OrderSpecifier<?> toOrder(String sortKey, String direction, boolean allowAggregate) {
        if (sortKey == null) {
            return DEFAULT_SORT;
        }
        if (!allowAggregate && "rating".equals(sortKey)) {
            return DEFAULT_SORT;      // 서브쿼리 버전에서는 집계값 정렬 불가
        }
        Map<String, OrderSpecifier<?>> table =
                "asc".equalsIgnoreCase(direction) ? SORT_ASC : SORT_DESC;
        return table.getOrDefault(sortKey, DEFAULT_SORT);
    }

    @Test
    @DisplayName("[14-9] 정렬 화이트리스트 — 어떤 입력이 와도 SQL 은 코드에 있는 것만")
    void sortWhitelist() {
        // 정상 입력
        assertThat(toOrder("price", "desc", true)).isEqualTo(product.price.desc());

        // 없는 키 → 기본 정렬
        assertThat(toOrder("salary", "desc", true)).isEqualTo(DEFAULT_SORT);

        // 인젝션 시도 → 기본 정렬. 문자열이 SQL 로 가지 않습니다.
        assertThat(toOrder("price desc, (select email from customers limit 1)", "desc", true))
                .isEqualTo(DEFAULT_SORT);

        // ⚠️ 컬럼명·정렬 방향은 바인딩 파라미터가 될 수 없습니다.
        //    order by ? 는 "상수 하나로 정렬"이지 "그 이름의 컬럼으로 정렬"이 아닙니다.
        //    그래서 동적 컬럼명은 구조적으로 화이트리스트가 유일한 방어입니다 (13-11).
    }
}
