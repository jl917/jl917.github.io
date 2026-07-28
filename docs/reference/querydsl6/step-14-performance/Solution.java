package com.example.shop.step14;

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
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 14 — 연습문제 정답과 해설. 이 코스의 마지막 파일입니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    Statistics stats;

    @BeforeEach
    void setUp() {
        stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
    }

    private void printStats(String label) {
        System.out.printf("[%s] 쿼리 %d개 / 엔티티 로드 %d개%n",
                label, stats.getPrepareStatementCount(), stats.getEntityLoadCount());
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 1 — N+1 재현과 진단
    // ════════════════════════════════════════════════════════════════
    //
    // 쿼리 개수는 **21개가 아닙니다.** 대개 그보다 적게 나옵니다.
    //
    // 이유: 1차 캐시(영속성 컨텍스트) 때문입니다.
    //   서로 다른 주문이 같은 고객을 가리키면, 두 번째부터는 이미 영속성 컨텍스트에
    //   그 Customer 가 있으므로 쿼리가 나가지 않습니다.
    //   고객은 30명이고 주문은 600건이므로 중복이 많습니다.
    //   주문 20건에서 서로 다른 고객이 K명이라면 쿼리는 1 + K 개입니다.
    //
    // 이 사실이 N+1 진단을 더 어렵게 만듭니다.
    //   - 데이터 분포에 따라 쿼리 개수가 달라집니다
    //   - 개발 DB(데이터가 적고 중복이 많음)에서는 훨씬 적게 나옵니다
    //   - "N+1 아닌데요? 21개가 아니라 15개인데요" 라는 오해가 생깁니다
    //
    // 판단 기준은 절대 숫자가 아니라 **"쿼리가 데이터 건수에 비례해 늘어나는가"** 입니다.
    // limit 을 20 → 100 으로 늘려 보십시오. 쿼리도 함께 늘어나면 N+1 입니다.
    //
    @Test
    @DisplayName("정답 1 — N+1 재현과 진단")
    void ans1() {
        List<Order> orders = queryFactory
                .selectFrom(order)
                .limit(20)
                .fetch();

        for (Order o : orders) {
            System.out.println(o.getId() + " → " + o.getCustomer().getGrade());
        }

        long q20 = stats.getPrepareStatementCount();
        printStats("N+1 (20건)");

        // limit 을 늘려 비례하는지 확인합니다
        stats.clear();
        em.clear();

        List<Order> more = queryFactory.selectFrom(order).limit(100).fetch();
        for (Order o : more) {
            o.getCustomer().getGrade();
        }
        long q100 = stats.getPrepareStatementCount();
        printStats("N+1 (100건)");

        System.out.printf("20건 → 쿼리 %d개, 100건 → 쿼리 %d개%n", q20, q100);
        // 늘어났다면 N+1 확정입니다.
        // 고객이 30명뿐이므로 100건에서도 최대 31개에서 멈춥니다.
        // 부모 테이블의 카디널리티가 상한이 됩니다.

        assertThat(orders).hasSize(20);
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 2 — N+1 해결 3가지 비교
    // ════════════════════════════════════════════════════════════════
    //
    // | 방법          | 쿼리 개수 | select 컬럼 수 | SQL 특징                           |
    // |--------------|---------|--------------|-----------------------------------|
    // | 해결 전       | 1 + K   | 6 / 8        | 고객 쿼리가 반복. K = 서로 다른 고객 수  |
    // | ① fetch join | 1       | 14           | join customers, 양쪽 전 컬럼         |
    // | ② batch size | 2       | 6 / 8        | where customer_id in (?,?,...)     |
    // | ③ DTO        | 1       | 3            | 필요한 컬럼만. 엔티티 로드 0개         |
    //
    // 추가 질문의 답:
    //   주문 → 주문항목(@OneToMany) + 페이징이면 **① fetch join 이 불가능**합니다.
    //   정확히는 "실행은 되는데 limit 이 SQL 에서 사라지고 전건을 메모리로 읽습니다."
    //   경고 로그 한 줄(HHH90003004)만 찍히고 결과는 정확하므로 더 위험합니다.
    //
    //   이때 ②와 ③은 그대로 동작합니다.
    //   ②는 부모 쿼리에 limit 이 그대로 붙고, 자식은 in 절로 따로 가져오기 때문입니다.
    //
    // 실무의 기본 조합:
    //   1. default_batch_fetch_size 를 전역으로 켭니다 (안전망)
    //   2. 읽기 전용 조회 API 는 DTO 직접 조회 (제대로 만든 조회)
    //   3. 수정이 필요한 흐름에서만 엔티티를 fetch join
    //
    @Test
    @DisplayName("정답 2 — N+1 해결 3가지 비교")
    void ans2() {
        // ① fetch join
        stats.clear();
        List<Order> byFetchJoin = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()
                .limit(20)
                .fetch();
        byFetchJoin.forEach(o -> o.getCustomer().getGrade());
        printStats("① fetch join");
        long q1 = stats.getPrepareStatementCount();

        // ② batch size — 코드는 그대로. application.yml 설정만.
        //    (@ManyToOne 이므로 default_batch_fetch_size 가 있어도
        //     프록시 초기화 시점에 in 절로 모입니다.)
        stats.clear();
        em.clear();
        List<Order> byBatch = queryFactory.selectFrom(order).limit(20).fetch();
        byBatch.forEach(o -> o.getCustomer().getGrade());
        printStats("② batch size");
        long q2 = stats.getPrepareStatementCount();

        // ③ DTO 직접 조회
        stats.clear();
        em.clear();
        List<Tuple> byDto = queryFactory
                .select(order.id, customer.name, customer.grade)
                .from(order)
                .join(order.customer, customer)
                .limit(20)
                .fetch();
        printStats("③ DTO");
        long q3 = stats.getPrepareStatementCount();

        System.out.printf("① %d개 / ② %d개 / ③ %d개%n", q1, q2, q3);

        assertThat(q1).isEqualTo(1);
        assertThat(q3).isEqualTo(1);
        assertThat(stats.getEntityLoadCount()).isZero();   // ③ 은 엔티티를 안 만듭니다
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 3 — exists vs count
    // ════════════════════════════════════════════════════════════════
    //
    // 두 생성 SQL
    //
    // ① count > 0
    //   select p1_0.product_id, ... from products p1_0
    //   where (select count(r1_0.review_id) from reviews r1_0
    //          where r1_0.product_id = p1_0.product_id) > ?
    //
    // ② exists
    //   select p1_0.product_id, ... from products p1_0
    //   where exists (select 1 from reviews r1_0
    //                 where r1_0.product_id = p1_0.product_id)
    //
    // EXPLAIN 의 서브쿼리 행(id=2) 비교
    //   ① | DEPENDENT SUBQUERY | reviews | ref | idx_reviews_product | rows: 5 | Using index |
    //   ② | DEPENDENT SUBQUERY | reviews | ref | idx_reviews_product | rows: 1 | Using index; FirstMatch |
    //
    // FirstMatch 의 뜻:
    //   "첫 번째 매칭 행을 찾는 즉시 그 서브쿼리 실행을 중단한다"
    //   count 는 전부 세야 답이 나오지만, exists 는 하나만 찾으면 답이 정해집니다.
    //   후기가 20건인 상품에서 ①은 20번 읽고 ②는 1번 읽습니다.
    //
    // 이것은 MySQL8 코스 Step 08 의 IN vs EXISTS 와 같은 이야기입니다.
    // QueryDSL 은 SQL 을 만드는 도구이지 SQL 의 규칙을 바꾸는 도구가 아닙니다.
    //
    @Test
    @DisplayName("정답 3 — exists vs count")
    void ans3() {
        // ① count > 0
        List<Product> byCount = queryFactory
                .selectFrom(product)
                .where(JPAExpressions
                        .select(review.count())
                        .from(review)
                        .where(review.product.eq(product))
                        .gt(0L))
                .fetch();

        // ② exists
        List<Product> byExists = queryFactory
                .selectFrom(product)
                .where(JPAExpressions
                        .selectOne()
                        .from(review)
                        .where(review.product.eq(product))
                        .exists())
                .fetch();

        System.out.println("count > 0 : " + byCount.size() + "건");
        System.out.println("exists    : " + byExists.size() + "건");

        // 상품 40개 중 후기 있는 것 16개 (후기 없는 상품 24개)
        assertThat(byCount).hasSize(16);
        assertThat(byExists).hasSize(16);

        // 흔한 오답: NOT IN 으로 "후기 없는 상품"을 찾는 것.
        //   product.id.notIn(JPAExpressions.select(review.product.id).from(review))
        //   → reviews 에 product_id 가 NULL 인 행이 하나라도 있으면 결과가 0건이 됩니다.
        //     NULL 과의 비교는 UNKNOWN 이고, NOT IN 은 UNKNOWN 을 만족으로 치지 않습니다.
        //     이 스키마에서는 NOT NULL 이라 괜찮지만, 습관적으로 NOT EXISTS 를 쓰십시오 (Step 07).
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 4 — contains vs startsWith 와 인덱스
    // ════════════════════════════════════════════════════════════════
    //
    // 두 생성 SQL 은 **똑같이 생겼습니다.**
    //   where p1_0.name like ? escape '!'
    //
    // 다른 것은 **바인딩 값**입니다.
    //   contains("노트")    → binding parameter (1:VARCHAR) <- [%노트%]
    //   startsWith("노트")  → binding parameter (1:VARCHAR) <- [노트%]
    //
    // 이것이 이 문제의 핵심입니다.
    // **SQL 문만 봐서는 차이를 알 수 없습니다.** 바인딩 로그를 봐야 합니다.
    // org.hibernate.orm.jdbc.bind: trace 를 켜 둔 이유가 이것입니다.
    //
    // 결과 건수가 다른 이유:
    //   contains 는 "게이밍 노트북 RTX4060", "보급형 노트북 15" 를 모두 찾습니다.
    //   startsWith 는 이름이 "노트"로 **시작하는** 것만 찾으므로 0건입니다.
    //   (상품명 중 "노트"로 시작하는 것이 없습니다.)
    //
    // EXPLAIN 비교 (idx_products_name 을 만든 상태)
    //   contains   | type: ALL   | key: NULL              | rows: 40 | Using where           |
    //   startsWith | type: range | key: idx_products_name | rows: 1  | Using index condition |
    //
    // 왜 같은 like 인데 계획이 다른가 (한 문장):
    //   인덱스는 값을 앞에서부터 정렬해 두었으므로, 패턴이 상수로 시작하면 시작점과 끝점을
    //   찾아 범위를 자를 수 있지만, 앞이 와일드카드면 시작점을 특정할 수 없어
    //   전 행을 읽어 하나씩 비교할 수밖에 없습니다.
    //
    // 앞뒤 모두 일치가 요구사항이면:
    //   1) FULLTEXT 인덱스 — 한국어는 ngram 파서 필요 (MySQL8 Step 15 10절)
    //   2) 검색 엔진 — 규모가 크면 정답
    //   3) 그냥 둔다 — 4만 행이면 풀스캔이 더 빠릅니다. **재고 나서 결정하십시오**
    //
    @Test
    @DisplayName("정답 4 — contains vs startsWith 와 인덱스")
    void ans4() {
        List<Product> byContains = queryFactory
                .selectFrom(product)
                .where(product.name.contains("노트"))
                .fetch();

        List<Product> byStartsWith = queryFactory
                .selectFrom(product)
                .where(product.name.startsWith("노트"))
                .fetch();

        System.out.println("contains(\"노트\"):");
        byContains.forEach(p -> System.out.println("  " + p.getName()));
        System.out.println("startsWith(\"노트\"): " + byStartsWith.size() + "건");

        assertThat(byContains).isNotEmpty();
        assertThat(byStartsWith).isEmpty();

        // 실습이 끝나면 인덱스를 되돌리십시오.
        //   ALTER TABLE products DROP INDEX idx_products_name;
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 5 — ★ 최종 프로젝트: 상품 검색 (서브쿼리 버전)
    // ════════════════════════════════════════════════════════════════
    //
    // 이 코스의 모든 것이 여기 모입니다.
    //   Step 04 동적 조건 (null 반환 → where 에서 무시)
    //   Step 05 프로젝션 (실제로는 @QueryProjection DTO)
    //   Step 06 조인 (category)
    //   Step 07 서브쿼리 (하위 카테고리, 평점, 후기 수)
    //   Step 09 count 쿼리 분리 + PageableExecutionUtils
    //   Step 10 정렬 화이트리스트
    //   Step 12 커스텀 리포지토리 구조
    //   Step 13 coalesce (avg 의 null)
    //   Step 14 EXPLAIN 검증
    //
    // count 쿼리에 join 과 서브쿼리가 없어야 하는 이유:
    //   count 는 "몇 건인가"만 알면 됩니다. 카테고리명도 평점도 필요 없습니다.
    //   join 을 넣으면 불필요한 테이블 접근이 생기고,
    //   서브쿼리를 넣으면 행마다 재실행되어 count 가 콘텐츠 쿼리보다 느려집니다.
    //
    //   단, **join 이 결과 건수에 영향을 주는 경우**에는 빼면 안 됩니다.
    //   여기서는 product.category 가 optional = false (NOT NULL FK) 이므로
    //   inner join 이 행을 줄이지 않습니다. 그래서 뺄 수 있습니다.
    //   nullable FK 였다면 inner join 이 행을 줄이므로 count 에도 있어야 합니다.
    //   ★ "join 을 빼도 되는가"는 항상 이 기준으로 판단하십시오.
    //
    // PageableExecutionUtils.getPage 는 count 쿼리를 **필요할 때만** 실행합니다.
    //   - 첫 페이지이고 결과가 pageSize 보다 적으면 → count 실행 안 함
    //   - 마지막 페이지이면 → offset + content.size() 로 계산
    // 검색 결과가 한 화면에 들어가는 경우가 대부분이므로 실전에서 효과가 큽니다.
    //
    record SearchCond(
            String keyword,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean inStockOnly,
            List<ProductStatus> statuses,
            String sortKey,
            String sortDirection
    ) {}

    private static final Map<String, OrderSpecifier<?>> SORT_ASC = Map.of(
            "price",   product.price.asc(),
            "created", product.createdAt.asc(),
            "name",    product.name.asc());

    private static final Map<String, OrderSpecifier<?>> SORT_DESC = Map.of(
            "price",   product.price.desc(),
            "created", product.createdAt.desc(),
            "name",    product.name.desc());

    private static final OrderSpecifier<?> DEFAULT_SORT = product.id.desc();

    @Test
    @DisplayName("정답 5 — 상품 검색 (서브쿼리 버전)")
    void ans5() {
        SearchCond cond = new SearchCond(
                null, null, new BigDecimal("100000"), null,
                true, List.of(ProductStatus.ON_SALE), "price", "desc");
        Pageable pageable = PageRequest.of(0, 10);

        // ── 콘텐츠 쿼리 ────────────────────────────────────────────
        List<Tuple> content = queryFactory
                .select(product.id, product.name, product.price, product.stock,
                        product.status, category.name,
                        JPAExpressions.select(review.rating.avg())
                                .from(review).where(review.product.eq(product)),
                        JPAExpressions.select(review.count())
                                .from(review).where(review.product.eq(product)))
                .from(product)
                .join(product.category, category)
                .where(keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceBetween(cond.minPrice(), cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()))
                .orderBy(toOrder(cond.sortKey(), cond.sortDirection()), product.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // ── count 쿼리 (분리) ──────────────────────────────────────
        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceBetween(cond.minPrice(), cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()));

        // 실제 리포지토리에서는:
        //   return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
        Long total = countQuery.fetchOne();

        content.forEach(t -> System.out.printf("%-24s %10s  평점 %-5s 후기 %d  [%s]%n",
                t.get(product.name), t.get(product.price),
                t.get(6, Double.class), t.get(7, Long.class), t.get(category.name)));
        System.out.println("total = " + total);

        printStats("검색 (서브쿼리)");

        assertThat(total).isNotNull();
        assertThat(content.size()).isLessThanOrEqualTo(10);
    }

    // ── 동적 조건 (Step 04, 10) ─────────────────────────────────────

    private BooleanExpression keywordContains(String keyword) {
        // null 과 빈 문자열을 모두 막습니다.
        // isBlank() 를 빠뜨리면 "" 가 넘어왔을 때 like '%%' 가 되어
        // 조건은 있으나 아무것도 걸러내지 못하는 SQL 이 나갑니다. 인덱스만 잃습니다.
        return (keyword == null || keyword.isBlank()) ? null : product.name.contains(keyword);
    }

    /** 선택한 카테고리 + 그 하위 카테고리 (Step 07) */
    private BooleanExpression categoryIn(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        // 대분류를 고르면 그 아래 소분류의 상품도 나와야 합니다.
        // categories 는 2단계(대분류 5 + 소분류 12)이므로 한 단계만 내려가면 됩니다.
        // 3단계 이상이면 재귀 CTE 가 필요하고, JPQL 로는 불가능합니다.
        //   → 네이티브 쿼리 또는 애플리케이션에서 ID 목록을 만들어 in 으로 넘깁니다.
        return product.category.id.eq(categoryId)
                .or(product.category.id.in(
                        JPAExpressions.select(category.id)
                                .from(category)
                                .where(category.parent.id.eq(categoryId))));
    }

    /** min 만 / max 만 / 둘 다 / 둘 다 null — 4가지를 모두 처리합니다. */
    private BooleanExpression priceBetween(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        if (min == null) {
            return product.price.loe(max);
        }
        if (max == null) {
            return product.price.goe(min);
        }
        return product.price.between(min, max);
        // 흔한 오답: product.price.goe(min).and(product.price.loe(max)) 를
        //           null 검사 없이 쓰는 것. min 이 null 이면 NPE 가 아니라
        //           goe(null) 이 되어 조용히 이상한 SQL 이 나갑니다.
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        // Boolean.TRUE.equals(...) 를 쓰는 이유: inStockOnly 가 null 일 수 있습니다.
        // if (inStockOnly) 로 쓰면 언박싱 NPE 입니다.
        return Boolean.TRUE.equals(inStockOnly) ? product.stock.gt(0) : null;
    }

    private BooleanExpression statusIn(List<ProductStatus> statuses) {
        // 빈 리스트를 그대로 in 에 넘기면 in () 이 되어 문법 에러이거나
        // 항상 false 인 조건이 됩니다. 반드시 isEmpty() 를 검사하십시오.
        return (statuses == null || statuses.isEmpty()) ? null : product.status.in(statuses);
    }

    /**
     * 정렬 화이트리스트 (Step 10, 13-11).
     *
     * 사용자 입력은 Map 의 **키**로만 쓰입니다.
     * SQL 에 들어가는 것은 코드에 이미 존재하는 OrderSpecifier 뿐입니다.
     *
     * 컬럼명과 정렬 방향은 바인딩 파라미터가 될 수 없습니다.
     * (order by ? 는 "상수 하나로 정렬"이지 "그 이름의 컬럼으로 정렬"이 아닙니다.)
     * 그래서 동적 정렬에서는 화이트리스트가 유일한 방어입니다.
     */
    private OrderSpecifier<?> toOrder(String sortKey, String direction) {
        if (sortKey == null) {
            return DEFAULT_SORT;
        }
        Map<String, OrderSpecifier<?>> table =
                "asc".equalsIgnoreCase(direction) ? SORT_ASC : SORT_DESC;
        return table.getOrDefault(sortKey, DEFAULT_SORT);
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 6 — 평균 평점 null 세기
    // ════════════════════════════════════════════════════════════════
    //
    // 1) 24가 아닌 이유
    //    24는 **전 상품 40개 중** 후기가 없는 것의 개수입니다.
    //    문제 5의 조건(가격 10만 이상 + 재고 있음 + ON_SALE)을 걸면 모집단이 줄어듭니다.
    //    그중 후기 없는 것만 세므로 24보다 작습니다.
    //    "24가 안 나온다"가 아니라 "24가 나오면 오히려 이상하다"가 맞습니다.
    //
    // 2) avg 는 null, count 는 0 인 이유
    //    SQL 표준의 정의입니다.
    //      COUNT  — 행을 세는 함수. 셀 행이 없으면 0. **항상 값이 있습니다.**
    //      AVG/SUM/MAX/MIN — 값을 집계하는 함수. 집계할 값이 없으면 NULL.
    //    "평균이 0" 과 "평균을 낼 수 없음" 은 다릅니다.
    //    후기가 없는 상품의 평점을 0.0 이라고 하면 "최악의 상품"으로 정렬됩니다.
    //    NULL 이 정직한 표현이고, 표시 방법은 애플리케이션이 정하는 것이 맞습니다.
    //
    // 3) 어디서 막는 것이 좋은가
    //    **DTO 의 compact constructor** 를 권합니다.
    //
    //      public record ProductSearchResponse(...) {
    //          @QueryProjection
    //          public ProductSearchResponse {
    //              avgRating = avgRating == null ? 0.0 : avgRating;
    //          }
    //      }
    //
    //    이유:
    //      - SQL 을 건드리지 않습니다. coalesce 를 넣으면 SQL 이 복잡해지고,
    //        집계값 정렬(order by avg(...))의 의미도 바뀝니다
    //        (NULL 정렬 위치가 0.0 정렬 위치로 바뀝니다)
    //      - 컨트롤러에서 막으면 DTO 를 쓰는 모든 곳에서 반복해야 합니다
    //      - DTO 가 "이 필드는 절대 null 이 아니다"를 스스로 보장합니다
    //
    //    다만 **정렬에 쓰이는 값이면 SQL 에서 처리**해야 합니다.
    //    자바에서 0.0 으로 바꿔도 정렬은 이미 DB 에서 끝났기 때문입니다.
    //
    @Test
    @DisplayName("정답 6 — 평균 평점 null 세기")
    void ans6() {
        // 전 상품 기준 — 24개
        long allNoReview = queryFactory
                .select(product.count())
                .from(product)
                .where(JPAExpressions
                        .selectOne()
                        .from(review)
                        .where(review.product.eq(product))
                        .notExists())
                .fetchOne();

        System.out.println("전 상품 중 후기 없는 것: " + allNoReview + "개");
        assertThat(allNoReview).isEqualTo(24L);

        // 문제 5의 조건을 건 경우
        long filteredNoReview = queryFactory
                .select(product.count())
                .from(product)
                .where(priceBetween(new BigDecimal("100000"), null),
                        inStock(true),
                        statusIn(List.of(ProductStatus.ON_SALE)),
                        JPAExpressions.selectOne()
                                .from(review)
                                .where(review.product.eq(product))
                                .notExists())
                .fetchOne();

        System.out.println("조건 적용 후 후기 없는 것: " + filteredNoReview + "개");
        assertThat(filteredNoReview).isLessThan(24L);

        // 참고: notExists() 를 쓴 이유는 Step 07 의 NOT IN + NULL 함정 때문입니다.
        //       product.id.notIn(select review.product.id from review) 는
        //       서브쿼리 결과에 NULL 이 하나라도 있으면 결과가 0건이 됩니다.
    }

    // ════════════════════════════════════════════════════════════════
    // 정답 7 — EXPLAIN 검증과 인덱스 추가
    // ════════════════════════════════════════════════════════════════
    //
    // | 항목  | 인덱스 전                       | 인덱스 후                        |
    // |------|-------------------------------|--------------------------------|
    // | type | ALL                           | range                          |
    // | key  | NULL                          | idx_products_cat_price         |
    // | rows | 40                            | 3                              |
    // | Extra| Using where; Using temporary; | Using index condition;         |
    // |      | Using filesort                | Using where; Using temporary;  |
    // |      |                               | Using filesort                 |
    //
    // 실행시간: 0.009 sec → 0.004 sec
    // 40행에서는 큰 의미가 없습니다. **40만 행이면 4초 → 0.05초입니다.**
    //
    // 1) 어느 컬럼으로, 왜
    //    (category_id, price) 복합 인덱스입니다.
    //
    //    선택도 조사 결과:
    //      total=40, category_id 12종, status 3종, price 34종, stock>0 이 33개
    //
    //      - status  — 3종. 선택도가 낮아 단독 인덱스는 무의미합니다
    //      - stock>0 — 40 중 33. 거의 전부라 무의미합니다
    //      - price   — 34종. 선택도가 높습니다
    //      - category_id — 12종. 중간이고 이미 인덱스가 있습니다
    //
    //    검색 화면의 주 필터는 카테고리이고, 그 안에서 가격으로 좁히고 가격으로 정렬합니다.
    //    복합 인덱스는 **등치 조건 컬럼을 앞에, 범위/정렬 컬럼을 뒤에** 두는 것이 원칙입니다.
    //    (MySQL8 Step 15 5절 — 선두 컬럼 규칙)
    //
    // 2) Using filesort 가 남는 이유
    //    group by 로 집계한 뒤 그 집계값(avg(rating))으로 정렬하기 때문입니다.
    //    집계 결과의 순서는 **계산이 끝나야 알 수 있습니다.**
    //    인덱스는 원본 컬럼 값의 순서만 제공하므로 집계값 정렬에는 쓸 수 없습니다.
    //    Using temporary 도 같은 이유입니다 (group by 처리를 위한 임시 테이블).
    //
    //    가격순 정렬로 바꾸면 filesort 가 사라질 수 있습니다.
    //    그것이 "정렬 기본값을 무엇으로 할 것인가"가 성능 결정인 이유입니다.
    //
    // 3) 새 인덱스 vs 기존 확장
    //    **기존 확장이 낫습니다.**
    //      ALTER TABLE products DROP INDEX idx_products_category;
    //      ALTER TABLE products ADD INDEX idx_products_category (category_id, price);
    //
    //    이유:
    //      - 인덱스 개수가 늘지 않습니다. INSERT/UPDATE 비용이 그대로입니다
    //      - 선두 컬럼이 category_id 로 같으므로 **기존 용도(카테고리 단독 조회)도 그대로** 동작합니다
    //      - 인덱스가 둘이면 옵티마이저가 매번 둘 중 하나를 골라야 하고,
    //        통계가 어긋나면 잘못 고를 수 있습니다
    //
    //    "인덱스를 추가한다"보다 "기존 인덱스를 확장할 수 있는가"를 먼저 보십시오.
    //
    // 인덱스 추가 전 체크리스트:
    //   1. 정말 그 쿼리가 느린가 — EXPLAIN 보다 실측이 먼저입니다
    //   2. 기존 인덱스로 안 되는가 — 확장으로 해결되는 경우가 많습니다
    //   3. 쓰기 비용을 감당할 수 있는가 — 인덱스는 INSERT/UPDATE 마다 갱신됩니다
    //
    @Test
    @DisplayName("정답 7 — EXPLAIN 검증과 인덱스 추가")
    void ans7() {
        // 쿼리를 실행해 p6spy 로그에서 완성된 SQL 을 복사하십시오.
        List<Tuple> content = queryFactory
                .select(product.id, product.name, product.price, category.name,
                        review.rating.avg(), review.count())
                .from(product)
                .join(product.category, category)
                .leftJoin(review).on(review.product.eq(product))
                .where(priceBetween(new BigDecimal("100000"), null),
                        inStock(true),
                        statusIn(List.of(ProductStatus.ON_SALE)))
                .groupBy(product.id, product.name, product.price, category.name)
                .orderBy(review.rating.avg().desc(), product.id.desc())
                .limit(10)
                .fetch();

        content.forEach(System.out::println);

        // 나머지는 MySQL 콘솔 작업입니다.
        //
        //   -- 1) 현재 계획
        //   EXPLAIN <복사한 SQL>;
        //
        //   -- 2) 현재 인덱스
        //   SHOW INDEX FROM products;
        //
        //   -- 3) 선택도
        //   SELECT COUNT(*) total, COUNT(DISTINCT category_id) cat,
        //          COUNT(DISTINCT status) st, COUNT(DISTINCT price) pr,
        //          SUM(stock > 0) in_stock
        //   FROM products;
        //
        //   -- 4) 인덱스 확장
        //   ALTER TABLE products DROP INDEX idx_products_category;
        //   ALTER TABLE products ADD INDEX idx_products_category (category_id, price);
        //
        //   -- 5) 다시 EXPLAIN
        //   EXPLAIN <복사한 SQL>;
        //
        //   -- 6) 원복
        //   ALTER TABLE products DROP INDEX idx_products_category;
        //   ALTER TABLE products ADD INDEX idx_products_category (category_id);

        assertThat(content).isNotNull();

        // ────────────────────────────────────────────────────────────
        // 이 코스의 마지막 문제입니다.
        //
        // 14개 스텝에서 배운 것은 QueryDSL 문법이 아니라
        // **자바 코드 한 줄이 SQL 한 줄로 번역되는 과정을 눈으로 좇는 습관**입니다.
        //
        // 이 습관이 있으면 QueryDSL 7.x 로 올려도, Hibernate 7 이 나와도,
        // 아예 다른 ORM 으로 옮겨도 같은 방식으로 검증할 수 있습니다.
        //
        // 문법은 문서에서 다시 찾을 수 있습니다. 습관은 문서에 없습니다.
        //
        // 이제 여러분의 프로젝트에서 org.hibernate.SQL: debug 를 켜십시오.
        // 그리고 지금 돌아가고 있는 조회 API 하나를 골라 로그를 보십시오.
        // ────────────────────────────────────────────────────────────
    }
}
