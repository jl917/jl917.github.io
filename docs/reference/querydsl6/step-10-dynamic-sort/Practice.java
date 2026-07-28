package com.example.shop.step10;

// ⚠️ [10-2] 이름 충돌 실험
//    아래 두 줄의 주석을 동시에 해제하면 컴파일 에러가 납니다.
//      java: com.querydsl.core.types.Order is already defined in this compilation unit
//    이 스텝에서 반드시 한 번 해 보십시오.
// import com.example.shop.entity.Order;      // 도메인 주문 엔티티
// import com.querydsl.core.types.Order;      // QueryDSL 정렬 방향 enum

import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
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
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 10 — 동적 정렬과 검색 조건 조립 : 본문 예제 실행 파일
 *
 * 이 파일은 하나로 실행할 수 있도록 유틸 클래스들을 static nested class 로 담았습니다.
 * 실제 프로젝트에서는 아래 패키지로 분리하십시오.
 *   com.example.shop.support  — PathBuilderSortUtils, SortWhitelist, Predicates
 *   com.example.shop.dto      — ProductSearchCond
 *
 * SQL 로그가 켜져 있는지 확인하십시오.
 *   logging.level.org.hibernate.SQL: debug
 *   logging.level.org.hibernate.orm.jdbc.bind: trace
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [10-1] 문제 정의 — Pageable 의 Sort 는 자동 적용되지 않는다
    // =================================================================

    @Test
    @DisplayName("[10-1] sort 를 줘도 order by 가 나가지 않는다")
    void s1_sortIsIgnored() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "price"));

        System.out.println("  pageable.getSort() = " + pageable.getSort());

        List<Product> content = queryFactory
                .selectFrom(product)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // 생성 SQL:
        //   select p1_0.product_id, ... from products p1_0 limit ?, ?
        //
        // ★ order by 절이 없습니다. sort=price,desc 가 완전히 무시됐습니다.
        //   에러가 나지 않습니다. 그냥 정렬이 안 될 뿐입니다.
        //   orderBy 의 시그니처가 이유입니다: orderBy(OrderSpecifier<?>... o)
        //   String 도 Sort 도 받지 않습니다.
        System.out.printf("  조회 %d건 (정렬 안 됨: %s ...)%n",
                content.size(), content.get(0).getName());
    }

    // =================================================================
    // [10-2] OrderSpecifier 의 구조
    // =================================================================

    @Test
    @DisplayName("[10-2] .desc() 와 new OrderSpecifier<>(Order.DESC, ...) 는 같다")
    void s2_orderSpecifierStructure() {
        // 아래 둘은 완전히 같은 SQL 을 만듭니다.
        OrderSpecifier<BigDecimal> a = product.price.desc();
        OrderSpecifier<BigDecimal> b =
                new OrderSpecifier<>(com.querydsl.core.types.Order.DESC, product.price);

        List<Product> byA = queryFactory.selectFrom(product).orderBy(a).limit(3).fetch();
        List<Product> byB = queryFactory.selectFrom(product).orderBy(b).limit(3).fetch();

        // 생성 SQL (둘 다):
        //   select p1_0.product_id, ... from products p1_0 order by p1_0.price desc limit ?
        //
        // ★ 방향을 런타임에 결정할 수 있다는 것이 동적 정렬의 출발점입니다.
        //
        // ⚠️ com.querydsl.core.types.Order 를 FQN 으로 쓴 이유는 도메인에 Order 엔티티가
        //    있기 때문입니다. 이 파일 상단의 주석 처리된 import 두 줄을 참고하십시오.
        //    Order 라는 이름은 세 개입니다:
        //      com.example.shop.entity.Order          — 주문 엔티티 (600건)
        //      com.querydsl.core.types.Order          — ASC / DESC enum
        //      org.springframework.data.domain.Sort.Order — property + direction
        System.out.printf("  a=%s  b=%s  (같은 결과: %s)%n",
                byA.get(0).getName(), byB.get(0).getName(),
                byA.get(0).getProductId().equals(byB.get(0).getProductId()));
    }

    @Test
    @DisplayName("[10-2] 방향을 런타임에 결정")
    void s2_dynamicDirection() {
        for (boolean ascending : new boolean[]{true, false}) {
            com.querydsl.core.types.Order direction = ascending
                    ? com.querydsl.core.types.Order.ASC
                    : com.querydsl.core.types.Order.DESC;

            OrderSpecifier<BigDecimal> spec = new OrderSpecifier<>(direction, product.price);

            List<Product> result = queryFactory
                    .selectFrom(product)
                    .orderBy(spec, product.productId.desc())
                    .limit(3)
                    .fetch();

            System.out.printf("  %s → %s%n", direction,
                    result.stream().map(Product::getName).toList());
        }
    }

    // =================================================================
    // [10-3] Sort -> OrderSpecifier[] 변환기 (방식 A: PathBuilder)
    // =================================================================

    @Test
    @DisplayName("[10-3] PathBuilder 방식 — 동작한다. 그리고 그것이 문제다")
    void s3_pathBuilderSort() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "price"));

        List<Product> content = queryFactory
                .selectFrom(product)
                .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                        pageable.getSort(), Product.class, "product"))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // 생성 SQL:
        //   select p1_0.product_id, ... from products p1_0
        //   order by p1_0.price desc limit ?, ?
        //
        // ⚠️ alias("product") 는 QProduct.product 의 별칭과 반드시 같아야 합니다.
        //    다르면 from 절에 없는 별칭을 참조한다는 파싱 에러가 납니다.
        //    product.getMetadata().getName() 으로 꺼내면 하드코딩을 피할 수 있습니다.
        System.out.printf("  조회 %d건 — 1위 %s (%s)%n",
                content.size(), content.get(0).getName(), content.get(0).getPrice());
    }

    // =================================================================
    // [10-3] 방식 B: 화이트리스트
    // =================================================================

    @Test
    @DisplayName("[10-3] 화이트리스트 방식 — 등록된 키만 통과")
    void s3_whitelistSort() {
        Map<String, ComparableExpressionBase<?>> sortable = new LinkedHashMap<>();
        sortable.put("price", product.price);
        sortable.put("stock", product.stock);
        sortable.put("createdAt", product.createdAt);
        sortable.put("name", product.name);

        OrderSpecifier<?> defaultOrder = product.productId.desc();

        System.out.println("--- sort=price,desc (등록됨) ---");
        queryFactory.selectFrom(product)
                .orderBy(WhitelistSortUtils.toOrderSpecifiers(
                        Sort.by(Sort.Direction.DESC, "price"), sortable, defaultOrder))
                .limit(5).fetch();
        // 생성 SQL: order by p1_0.price desc

        System.out.println("--- sort=cost,desc (미등록) ---");
        queryFactory.selectFrom(product)
                .orderBy(WhitelistSortUtils.toOrderSpecifiers(
                        Sort.by(Sort.Direction.DESC, "cost"), sortable, defaultOrder))
                .limit(5).fetch();
        // 생성 SQL: order by p1_0.product_id desc
        //
        // ★ "cost" 는 무시되고 기본 정렬로 대체됐습니다.
        //   예외가 나지 않았고, 원가가 외부에 노출되지도 않았습니다.
    }

    // =================================================================
    // [10-4] ⚠️ PathBuilder + 사용자 입력의 위험
    // =================================================================

    @Test
    @DisplayName("[10-4] ① 존재하지 않는 필드 → 런타임 예외 (500)")
    void s4_pathBuilderThrows() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "nonExistentField"));

        // 의도적으로 예외를 던지는 테스트입니다. 콘솔의 메시지를 눈으로 확인하십시오.
        assertThatThrownBy(() ->
                queryFactory.selectFrom(product)
                        .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                                pageable.getSort(), Product.class, "product"))
                        .fetch()
        ).satisfies(e -> System.out.println("  예외: " + e.getMessage()));

        // 실제 메시지 (Hibernate 6.4):
        //   org.hibernate.query.SemanticException:
        //     Could not interpret path expression 'product.nonExistentField'
        //
        // ★ PathBuilder 자체는 아무 검증도 하지 않습니다. 어떤 문자열이든 경로로 만듭니다.
        //   검증은 나중에 JPQL 을 파싱하는 Hibernate 가 합니다.
        //   컴파일 시점에도, 쿼리 조립 시점에도 아무 일이 없다가 실행 시점에 터집니다.
        //   공격이랄 것도 없이 ?sort=x,desc 하나로 500 이 납니다.
    }

    @Test
    @DisplayName("[10-4] ② 의도하지 않은 필드 노출 — 순서가 곧 정보다")
    void s4_costExposure() {
        // Product 에는 cost(원가) 필드가 있습니다. 응답 DTO 에는 없습니다.
        // 그런데 PathBuilder 방식은 정렬 키로는 받아 줍니다.

        List<Product> asc = queryFactory.selectFrom(product)
                .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                        Sort.by(Sort.Direction.ASC, "cost"), Product.class, "product"))
                .limit(5).fetch();

        List<Product> desc = queryFactory.selectFrom(product)
                .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                        Sort.by(Sort.Direction.DESC, "cost"), Product.class, "product"))
                .limit(5).fetch();

        // 생성 SQL: order by p1_0.cost asc / desc
        //
        // ★ 응답 본문에 cost 값이 없어도 "순서" 가 정보를 흘립니다.
        //   asc 와 desc 를 번갈아 호출하면 원가 순위를 완전히 복원할 수 있습니다.
        //   price 를 알고 있으니 마진 구조까지 역산됩니다.
        //   이것은 버그가 아니라 설계상 열려 있는 문입니다. 아무 에러도 나지 않습니다.
        System.out.printf("  cost asc  상위 5: %s%n",
                asc.stream().map(Product::getProductId).toList());
        System.out.printf("  cost desc 상위 5: %s%n",
                desc.stream().map(Product::getProductId).toList());
    }

    @Test
    @DisplayName("[10-4] ③ SQL 인젝션 시도 — 막히지만 그것은 우리 코드의 방어가 아니다")
    void s4_injectionAttempt() {
        Sort malicious = Sort.by(Sort.Direction.DESC, "price; DROP TABLE products--");

        assertThatThrownBy(() ->
                queryFactory.selectFrom(product)
                        .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                                malicious, Product.class, "product"))
                        .fetch()
        ).satisfies(e -> System.out.println("  예외: " + e.getMessage()));

        // 막혔습니다. 그러나 이유가 중요합니다.
        //
        //   QueryDSL 이 검사해서가 아닙니다.
        //   QueryDSL-JPA 가 만드는 것은 SQL 이 아니라 JPQL 이고,
        //   Hibernate 의 JPQL 파서가 그 문자열을 유효한 경로 표현식으로 해석하지 못한 것입니다.
        //   파서가 방어벽 역할을 우연히 해 준 것입니다.
        //
        // ★ 이 방어에 기대면 안 되는 이유
        //   ① 방어의 근거가 우리 코드가 아닙니다. 파서의 관대함이 버전에 따라 달라지면
        //      그 방어도 달라집니다. "현재 버전에서 막히더라" 는 보안 근거가 아닙니다.
        //   ② 문자열 조립 경로(Expressions.stringTemplate, Step 13)와 결합하면 실제로 열립니다.
        //      거기서는 사용자 입력이 템플릿 문자열 자체의 일부가 됩니다.
        //
        // ★ 안전한 것은 "값 바인딩" 입니다.
        //   구조(테이블·컬럼·경로·정렬 방향)는 바인딩 대상이 아닙니다.
        //   어떤 쿼리 도구를 쓰든 사용자 입력이 쿼리의 구조를 결정하게 하면 안 됩니다.
    }

    @Test
    @DisplayName("[10-4] 처방 — SortWhitelist (타이브레이커 내장)")
    void s4_sortWhitelist() {
        SortWhitelist lenient = SortWhitelist.lenient(product.productId.desc())
                .add("price", product.price)
                .add("stock", product.stock)
                .add("createdAt", product.createdAt)
                .add("name", product.name);

        System.out.println("--- sort=price,desc ---");
        queryFactory.selectFrom(product)
                .orderBy(lenient.resolve(Sort.by(Sort.Direction.DESC, "price")))
                .limit(5).fetch();
        // 생성 SQL: order by p1_0.price desc, p1_0.product_id desc
        // ★ product_id desc 가 자동으로 붙었습니다.
        //   Step 09 의 9-9 타이브레이커 규칙이 유틸에 내장된 것입니다.

        System.out.println("--- sort=cost,desc (미등록, lenient) ---");
        queryFactory.selectFrom(product)
                .orderBy(lenient.resolve(Sort.by(Sort.Direction.DESC, "cost")))
                .limit(5).fetch();
        // 생성 SQL: order by p1_0.product_id desc

        System.out.println("--- sort=cost,desc (미등록, strict) ---");
        SortWhitelist strict = SortWhitelist.strict(product.productId.desc())
                .add("price", product.price);

        assertThatThrownBy(() -> strict.resolve(Sort.by(Sort.Direction.DESC, "cost")))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> System.out.println("  예외: " + e.getMessage()));

        // 💡 공개 API 라면 strict 가 낫습니다. 클라이언트가 오타를 즉시 알 수 있습니다.
        //    내부 관리 화면이라면 lenient 가 무난합니다.
        //    어느 쪽이든 "등록되지 않은 키가 쿼리에 들어가지 않는다" 는 성질은 동일합니다.
    }

    // =================================================================
    // [10-5] ~ [10-6] 검색 조건 객체화 + 조건 메서드
    // =================================================================

    @Test
    @DisplayName("[10-6] 조합 1 — 조건 없음. where 절이 통째로 사라진다")
    void s6_noCondition() {
        ProductSearchCond cond = ProductSearchCond.empty();
        List<Product> result = search(cond);

        // 생성 SQL:
        //   select p1_0.product_id, ... from products p1_0
        //   order by p1_0.product_id desc
        //
        // ★ where 절이 아예 없습니다.
        //   where(null, null, null, null, null, null) 은 where 1=1 도, 빈 where 도 만들지 않습니다.
        //   절 자체가 사라집니다.
        System.out.printf("  조회 %d건 (전체)%n", result.size());
    }

    @Test
    @DisplayName("[10-6] 조합 2 — 상태만")
    void s6_statusOnly() {
        ProductSearchCond cond = new ProductSearchCond(
                null, null, null, null, ProductStatus.ON_SALE, null);
        List<Product> result = search(cond);

        // 생성 SQL: where p1_0.status = ?     바인딩: [1] ON_SALE
        System.out.printf("  조회 %d건%n", result.size());
    }

    @Test
    @DisplayName("[10-6] 조합 3 — 가격 범위 + 재고")
    void s6_priceRangeAndStock() {
        ProductSearchCond cond = new ProductSearchCond(
                null, null, new BigDecimal("100000"), new BigDecimal("500000"), null, true);
        List<Product> result = search(cond);

        // 생성 SQL:
        //   where p1_0.price >= ? and p1_0.price <= ? and p1_0.stock > ?
        //   바인딩: [1] 100000  [2] 500000  [3] 0
        //
        // ★ 세 조건이 and 로 이어졌고 괄호가 없습니다. and 만 있으므로 괄호가 필요 없습니다.
        System.out.printf("  조회 %d건%n", result.size());
        result.forEach(p -> System.out.printf("    %s %s%n", p.getName(), p.getPrice()));
    }

    @Test
    @DisplayName("[10-6] 조합 4 — 전부. escape '!' 에 주목")
    void s6_allConditions() {
        ProductSearchCond cond = new ProductSearchCond(
                "노트북", 7L, new BigDecimal("500000"), new BigDecimal("2500000"),
                ProductStatus.ON_SALE, true);
        List<Product> result = search(cond);

        // 생성 SQL:
        //   where p1_0.name like ? escape '!'
        //     and p1_0.category_id = ?
        //     and p1_0.price >= ? and p1_0.price <= ?
        //     and p1_0.status = ? and p1_0.stock > ?
        //   바인딩: [1] %노트북%  [2] 7  [3] 500000  [4] 2500000  [5] ON_SALE  [6] 0
        //
        // ★ escape '!' — contains() 는 %를 앞뒤로 붙이면서 사용자 입력 안의 % 와 _ 를
        //   이스케이프합니다. 사용자가 "100%" 를 검색해도 %가 와일드카드로 해석되지 않습니다.
        //   LIKE 와일드카드 인젝션은 QueryDSL 이 막아 줍니다. 값 바인딩 영역이기 때문입니다.
        //
        // ★ category_id = ? — product.category.categoryId 는 조인을 만들지 않습니다.
        //   FK 컬럼이 products 테이블에 있으므로 직접 비교합니다.
        //   product.category.name 이었다면 join categories 가 생겼을 것입니다.
        System.out.printf("  조회 %d건%n", result.size());
        result.forEach(p -> System.out.printf("    %s %s%n", p.getName(), p.getPrice()));
    }

    private List<Product> search(ProductSearchCond cond) {
        return queryFactory
                .selectFrom(product)
                .where(
                        keywordContains(cond.keyword()),
                        categoryEq(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        statusEq(cond.status()),
                        inStock(cond.inStockOnly())
                )
                .orderBy(product.productId.desc())
                .fetch();
    }

    // --- 조건 메서드 (10-6). 전부 null 을 반환할 수 있습니다. ---

    private BooleanExpression keywordContains(String keyword) {
        return (keyword == null || keyword.isBlank()) ? null : product.name.contains(keyword);
    }

    private BooleanExpression categoryEq(Long categoryId) {
        return categoryId == null ? null : product.category.categoryId.eq(categoryId);
    }

    private BooleanExpression priceGoe(BigDecimal minPrice) {
        return minPrice == null ? null : product.price.goe(minPrice);
    }

    private BooleanExpression priceLoe(BigDecimal maxPrice) {
        return maxPrice == null ? null : product.price.loe(maxPrice);
    }

    private BooleanExpression statusEq(ProductStatus status) {
        return status == null ? null : product.status.eq(status);
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        return Boolean.TRUE.equals(inStockOnly) ? product.stock.gt(0) : null;
    }

    // =================================================================
    // [10-7] 조건 재사용 — BooleanExpression 합성
    // =================================================================

    /** 판매 가능 = 판매중 + 재고 있음. null 을 반환하지 않는 "개념" 메서드. */
    private BooleanExpression purchasable() {
        return product.status.eq(ProductStatus.ON_SALE).and(product.stock.gt(0));
    }

    /** 프리미엄 판매 가능 = 판매 가능 + 50만원 이상 */
    private BooleanExpression premiumPurchasable() {
        return purchasable().and(product.price.goe(new BigDecimal("500000")));
    }

    @Test
    @DisplayName("[10-7] 개념에 이름을 붙이고 합성한다")
    void s7_composition() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(premiumPurchasable())
                .orderBy(product.price.desc(), product.productId.desc())
                .fetch();

        // 생성 SQL:
        //   where p1_0.status = ? and p1_0.stock > ? and p1_0.price >= ?
        //   order by p1_0.price desc, p1_0.product_id desc
        //   바인딩: [1] ON_SALE  [2] 0  [3] 500000
        //
        // ★ purchasable() 이라는 비즈니스 개념 하나를 정의해 두고 재사용했습니다.
        //   이름이 붙었으므로 의도가 코드에 드러납니다.
        //   BooleanBuilder 로는 할 수 없는 일입니다 (조립 과정이 메서드 안에 갇힘).
        System.out.printf("  조회 %d건%n", result.size());
        result.forEach(p -> System.out.printf("    %s %s%n", p.getName(), p.getPrice()));
    }

    // =================================================================
    // [10-8] ⚠️ null.and(...) 는 NPE
    // =================================================================

    @Test
    @DisplayName("[10-8] NPE 재현 — 앞이 null 이면 죽고 뒤가 null 이면 통과")
    void s8_nullAndNpe() {
        // ① 앞이 null → NPE
        assertThatThrownBy(() ->
                queryFactory.selectFrom(product)
                        .where(statusEq(null).and(priceGoe(new BigDecimal("500000"))))
                        .fetch()
        ).isInstanceOf(NullPointerException.class)
                .satisfies(e -> System.out.println("  ① 예외: " + e.getMessage()));

        // ② 뒤가 null → 통과
        List<Product> ok = queryFactory.selectFrom(product)
                .where(statusEq(ProductStatus.ON_SALE).and(priceGoe(null)))
                .fetch();
        // 생성 SQL: where p1_0.status = ?    바인딩: [1] ON_SALE
        System.out.printf("  ② 통과 — 조회 %d건%n", ok.size());

        // ★ 이 비대칭이 함정의 본질입니다.
        //   .and(null) 은 QueryDSL 이 인자로 받은 null 을 무시하므로 안전합니다.
        //   null.and(...) 는 그냥 자바의 NPE 입니다.
        //
        //   개발 중에는 조건을 다 채워서 테스트하므로 통과하고,
        //   운영에서 사용자가 조건 하나를 비우는 순간 500 이 납니다.
        //   그것도 어떤 조건을 비우느냐에 따라 나기도 하고 안 나기도 합니다.
    }

    @Test
    @DisplayName("[10-8] 처방 1 — Expressions.allOf 는 null 을 걸러 준다")
    void s8_expressionsAllOf() {
        BooleanExpression combined = Expressions.allOf(
                statusEq(null), priceGoe(new BigDecimal("500000")));

        List<Product> result = queryFactory.selectFrom(product).where(combined).fetch();

        // 생성 SQL: where p1_0.price >= ?    바인딩: [1] 500000
        // status 조건만 사라졌습니다. 예외 없음.
        System.out.printf("  조회 %d건%n", result.size());

        // 모든 인자가 null 이면 allOf 자체가 null 을 반환합니다.
        BooleanExpression allNull = Expressions.allOf(statusEq(null), priceGoe(null));
        System.out.println("  allOf(null, null) = " + allNull);

        // ⚠️ allOf 의 반환값에 다시 .and() 를 이으면 똑같은 NPE 가 재발합니다.
        //    allOf 는 null 을 걸러 주지만 null 을 만들지 않는다고 보장하지 않습니다.
        //    합성이 두 단계 이상이면 바깥도 allOf 로 감싸십시오.
        //
        // 💡 이 동작은 버전에 따라 확인하고 쓰십시오 (이 코스는 QueryDSL 6.12 기준).
        //    확신이 서지 않으면 아래 Predicates 처럼 직접 만드십시오.
    }

    @Test
    @DisplayName("[10-8] 처방 2 — null 안전 헬퍼를 직접 만든다")
    void s8_ownHelper() {
        BooleanExpression combined = Predicates.and(
                statusEq(null), priceGoe(new BigDecimal("500000")));

        List<Product> result = queryFactory.selectFrom(product).where(combined).fetch();

        // 생성 SQL 은 Expressions.allOf 와 동일합니다.
        // 차이는 "동작을 여러분이 통제한다" 는 것뿐이며, 그것이 이 20줄의 값어치입니다.
        System.out.printf("  조회 %d건%n", result.size());
    }

    @Test
    @DisplayName("[10-8] 처방 3 — 애초에 합성하지 않는다 (가장 단순)")
    void s8_dontCompose() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(
                        statusEq(null),
                        priceGoe(new BigDecimal("500000")),
                        priceLoe(null)
                )
                .fetch();

        // 생성 SQL: where p1_0.price >= ?
        //
        // ★ where 가 AND 로 묶고 null 을 무시합니다. NPE 가 생길 자리가 없습니다.
        //
        // 💡 규칙
        //   - null 을 반환할 수 있는 조건 메서드 → where 에 나란히 넘기기만 한다.
        //     절대 .and() 로 잇지 않는다.
        //   - null 을 반환하지 않는 개념 메서드(purchasable() 등) → 자유롭게 합성해도 된다.
        //   - 두 종류를 섞어야 한다면 → Predicates.and(...) 같은 null 안전 헬퍼를 쓴다.
        System.out.printf("  조회 %d건%n", result.size());
    }

    // =================================================================
    // [10-9] keyword 검색 — contains 와 인덱스
    // =================================================================

    @Test
    @DisplayName("[10-9] contains vs startsWith — 바인딩 값과 결과가 갈린다")
    void s9_containsVsStartsWith() {
        List<Product> byContains = queryFactory.selectFrom(product)
                .where(product.name.contains("노트북"))
                .orderBy(product.productId.desc())
                .fetch();
        // 생성 SQL: where p1_0.name like ? escape '!'    바인딩: [1] %노트북%

        List<Product> byStartsWith = queryFactory.selectFrom(product)
                .where(product.name.startsWith("노트북"))
                .orderBy(product.productId.desc())
                .fetch();
        // 생성 SQL: where p1_0.name like ? escape '!'    바인딩: [1] 노트북%

        System.out.printf("  contains   → %d건 %s%n",
                byContains.size(), byContains.stream().map(Product::getName).toList());
        System.out.printf("  startsWith → %d건 %s%n",
                byStartsWith.size(), byStartsWith.stream().map(Product::getName).toList());

        // ★ SQL 문자열은 같고 바인딩 값만 다릅니다. 그런데 성능과 결과가 완전히 갈립니다.
        //
        //   %노트북%  → 앞에 % 가 있어 인덱스를 못 탐 (type: index = 인덱스 풀스캔)
        //   노트북%   → 앞이 고정이라 인덱스 탐색 가능 (type: range)
        //
        //   100만 행 access_logs 실측 (idx_path 있는 상태):
        //     LIKE '%detail'     → type: index, rows 996151, 0.219초
        //     LIKE '/products%'  → type: range, rows 498075, 0.041초
        //
        //   그러나 결과가 다릅니다. startsWith 는 0건입니다.
        //   "게이밍 노트북 RTX4060" 은 "노트북" 으로 시작하지 않습니다.
        //   빠르지만 사용자가 기대하는 검색이 아닙니다.
        //
        // ★ products 는 40건입니다. 풀스캔이 0.000초입니다.
        //   측정되지 않은 성능 문제를 미리 최적화하지 마십시오.
        //   이 절의 목적은 "contains 를 쓰지 말라" 가 아니라 "언제 문제가 되는지 알라" 입니다.
    }

    // =================================================================
    // [10-10] 통합 — 조건 + 정렬 + 페이징
    // =================================================================

    private static final SortWhitelist SORT = SortWhitelist
            .lenient(product.productId.desc())
            .add("price", product.price)
            .add("stock", product.stock)
            .add("createdAt", product.createdAt)
            .add("name", product.name);

    @Test
    @DisplayName("[10-10] 통합 메서드 — count 쿼리가 실행되지 않는 것도 확인")
    void s10_integrated() {
        ProductSearchCond cond = new ProductSearchCond(
                "노트북", null, new BigDecimal("500000"), null, ProductStatus.ON_SALE, true);

        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "price"));

        Page<Tuple> page = searchPage(cond, pageable);

        // 콘텐츠 생성 SQL:
        //   select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock, p1_0.status, c1_0.name
        //   from products p1_0
        //   join categories c1_0 on c1_0.category_id = p1_0.category_id
        //   where p1_0.name like ? escape '!'
        //     and p1_0.price >= ? and p1_0.status = ? and p1_0.stock > ?
        //   order by p1_0.price desc, p1_0.product_id desc
        //   limit ?, ?
        //
        // count 생성 SQL (만들어졌지만 실행되지 않음):
        //   select count(p1_0.product_id) from products p1_0
        //   where p1_0.name like ? escape '!' and p1_0.price >= ?
        //     and p1_0.status = ? and p1_0.stock > ?
        //
        // ★ offset=0 이고 결과 2건 < pageSize 10 이므로 PageableExecutionUtils 가 count 를 생략합니다.
        //   로그에 count SQL 이 없는 것을 확인하십시오.
        System.out.printf("  content=%d  total=%d  totalPages=%d%n",
                page.getContent().size(), page.getTotalElements(), page.getTotalPages());
        page.getContent().forEach(t -> System.out.printf("    %s %s (%s)%n",
                t.get(product.name), t.get(product.price), t.get(category.name)));
    }

    private Page<Tuple> searchPage(ProductSearchCond cond, Pageable pageable) {
        List<Tuple> content = queryFactory
                .select(product.productId, product.name, product.price,
                        product.stock, product.status, category.name)
                .from(product)
                .join(product.category, category)
                .where(
                        keywordContains(cond.keyword()),
                        categoryEq(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        statusEq(cond.status()),
                        inStock(cond.inStockOnly())
                )
                .orderBy(SORT.resolve(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count 쿼리 — join 과 orderBy 를 뺀다 (Step 09 의 9-6)
        // where 가 categories 컬럼을 참조하지 않으므로 join 을 뺄 수 있습니다.
        // categoryEq 는 product.category.categoryId — FK 컬럼이라 조인이 필요 없습니다.
        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(
                        keywordContains(cond.keyword()),
                        categoryEq(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        statusEq(cond.status()),
                        inStock(cond.inStockOnly())
                );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // =================================================================
    // [10-11] 동적 select
    // =================================================================

    @Test
    @DisplayName("[10-11] 방식 1 — 프로젝션 분기 (권장)")
    void s11_projectionBranch() {
        System.out.println("--- 관리자 ---");
        queryFactory
                .select(product.productId, product.name, product.price, product.cost, product.stock)
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .limit(3).fetch();
        // 생성 SQL: select p1_0.product_id, p1_0.name, p1_0.price, p1_0.cost, p1_0.stock ...

        System.out.println("--- 일반 사용자 ---");
        queryFactory
                .select(product.productId, product.name, product.price, product.stock)
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .limit(3).fetch();
        // 생성 SQL: select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock ...
        //
        // ★ cost 컬럼이 SQL 에서 아예 빠졌습니다.
        //   DTO 매핑 단계에서 거르는 게 아니라 DB 에서 읽지도 않습니다.
        //   보안과 성능을 동시에 얻습니다.
    }

    @Test
    @DisplayName("[10-11] 방식 2 — Expressions.constant 로 자리를 메운다")
    void s11_constantPlaceholder() {
        boolean isAdmin = false;

        Expression<BigDecimal> costExpr = isAdmin
                ? product.cost
                : Expressions.constant(BigDecimal.ZERO);

        List<Tuple> result = queryFactory
                .select(product.productId, product.name, product.price, costExpr)
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .limit(3)
                .fetch();

        // 생성 SQL (일반 사용자):
        //   select p1_0.product_id, p1_0.name, p1_0.price from products p1_0 where ...
        //   cost 자리는 SQL 에 나가지 않고 자바 쪽에서 BigDecimal.ZERO 로 채워집니다.
        //
        // ⚠️ Expressions.constant 는 값이 SQL 로 나갈 수도, 안 나갈 수도 있습니다.
        //    프로젝션 자리에서는 사라지지만 where 절에 쓰면 바인딩 파라미터로 나갑니다.
        //    어느 쪽인지는 생성 SQL 을 보고 판단하십시오.
        //
        //    그리고 constant 로 0 을 채우는 것은 "값이 0" 이라는 거짓 정보를 전달합니다.
        //    클라이언트가 진짜 원가로 오해할 여지가 있다면 방식 1 이 낫습니다.
        System.out.printf("  조회 %d건%n", result.size());
    }

    @Test
    @DisplayName("[10-11] 방식 3 — 필드 목록으로 select 조립 (화이트리스트 필수)")
    void s11_dynamicFields() {
        List<String> fields = List.of("name", "price");

        List<Expression<?>> selects = new ArrayList<>();
        selects.add(product.productId);                            // 항상 포함
        if (fields.contains("name"))  selects.add(product.name);
        if (fields.contains("price")) selects.add(product.price);
        if (fields.contains("stock")) selects.add(product.stock);

        List<Tuple> result = queryFactory
                .select(selects.toArray(new Expression[0]))
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .limit(3)
                .fetch();

        // 생성 SQL: select p1_0.product_id, p1_0.name, p1_0.price from products p1_0 where ...
        //
        // ⚠️ 위 코드는 if (fields.contains(...)) 로 명시적 화이트리스트를 쓰고 있어 안전합니다.
        //    PathBuilder 로 fields 문자열을 그대로 경로화한다면 10-4 의 문제가 그대로 재현됩니다.
        //    정렬 키든 조회 컬럼이든, 사용자 문자열이 쿼리 구조를 결정하는 곳에는
        //    언제나 화이트리스트가 필요합니다.
        //
        // 💡 Tuple 은 타입 안전성을 잃습니다. t.get(product.stock) 은 select 에 없으면 null 입니다.
        //    대부분의 경우 방식 1 이 정답입니다.
        System.out.printf("  조회 %d건%n", result.size());
    }

    // =================================================================
    // 유틸 클래스 — 실제 프로젝트에서는 별도 패키지로 분리하십시오
    // =================================================================

    /** [10-3] 방식 A — PathBuilder 로 문자열 경로 해석. 사용자 입력에는 쓰지 마십시오. */
    static final class PathBuilderSortUtils {

        private PathBuilderSortUtils() {}

        @SuppressWarnings({"rawtypes", "unchecked"})
        static OrderSpecifier<?>[] toOrderSpecifiers(Sort sort, Class<?> entityType, String alias) {
            List<OrderSpecifier<?>> result = new ArrayList<>();
            for (Sort.Order o : sort) {
                com.querydsl.core.types.Order direction = o.isAscending()
                        ? com.querydsl.core.types.Order.ASC
                        : com.querydsl.core.types.Order.DESC;
                PathBuilder<?> path = new PathBuilder<>(entityType, alias);
                result.add(new OrderSpecifier(direction, path.get(o.getProperty())));
            }
            return result.toArray(new OrderSpecifier[0]);
        }
    }

    /** [10-3] 방식 B — 화이트리스트 Map. */
    static final class WhitelistSortUtils {

        private WhitelistSortUtils() {}

        static OrderSpecifier<?>[] toOrderSpecifiers(
                Sort sort,
                Map<String, ComparableExpressionBase<?>> allowed,
                OrderSpecifier<?> defaultOrder) {

            List<OrderSpecifier<?>> result = new ArrayList<>();
            for (Sort.Order o : sort) {
                ComparableExpressionBase<?> expr = allowed.get(o.getProperty());
                if (expr == null) {
                    continue;
                }
                result.add(o.isAscending() ? expr.asc() : expr.desc());
            }
            if (result.isEmpty()) {
                result.add(defaultOrder);
            }
            return result.toArray(new OrderSpecifier[0]);
        }
    }

    /** [10-4] 처방 — 빌더 형태의 정렬 화이트리스트. 타이브레이커를 내장합니다. */
    static final class SortWhitelist {

        private final Map<String, ComparableExpressionBase<?>> allowed = new LinkedHashMap<>();
        private final OrderSpecifier<?> defaultOrder;
        private final boolean rejectUnknown;

        private SortWhitelist(OrderSpecifier<?> defaultOrder, boolean rejectUnknown) {
            this.defaultOrder = defaultOrder;
            this.rejectUnknown = rejectUnknown;
        }

        /** 모르는 키는 조용히 무시하고 기본 정렬로 대체합니다. */
        static SortWhitelist lenient(OrderSpecifier<?> defaultOrder) {
            return new SortWhitelist(defaultOrder, false);
        }

        /** 모르는 키가 오면 IllegalArgumentException 을 던집니다 (→ 400 으로 매핑). */
        static SortWhitelist strict(OrderSpecifier<?> defaultOrder) {
            return new SortWhitelist(defaultOrder, true);
        }

        SortWhitelist add(String key, ComparableExpressionBase<?> expression) {
            allowed.put(key, expression);
            return this;
        }

        OrderSpecifier<?>[] resolve(Sort sort) {
            List<OrderSpecifier<?>> result = new ArrayList<>();
            for (Sort.Order o : sort) {
                ComparableExpressionBase<?> expr = allowed.get(o.getProperty());
                if (expr == null) {
                    if (rejectUnknown) {
                        throw new IllegalArgumentException(
                                "정렬할 수 없는 항목입니다: " + o.getProperty()
                                        + " (허용: " + allowed.keySet() + ")");
                    }
                    continue;
                }
                result.add(o.isAscending() ? expr.asc() : expr.desc());
            }
            // ★ 타이브레이커 — Step 09 의 9-9. 항상 마지막에 PK 를 덧붙여 전순서를 확정합니다.
            result.add(defaultOrder);
            return result.toArray(new OrderSpecifier[0]);
        }
    }

    /** [10-8] 처방 2 — null 안전 조건 합성 헬퍼. */
    static final class Predicates {

        private Predicates() {}

        /** null 인 조건을 건너뛰고 AND 로 합성합니다. 전부 null 이면 null 을 반환합니다. */
        static BooleanExpression and(BooleanExpression... conditions) {
            BooleanExpression result = null;
            for (BooleanExpression c : conditions) {
                if (c == null) {
                    continue;
                }
                result = (result == null) ? c : result.and(c);
            }
            return result;
        }

        /** null 인 조건을 건너뛰고 OR 로 합성합니다. */
        static BooleanExpression or(BooleanExpression... conditions) {
            BooleanExpression result = null;
            for (BooleanExpression c : conditions) {
                if (c == null) {
                    continue;
                }
                result = (result == null) ? c : result.or(c);
            }
            return result;
        }
    }

    /** [10-5] 검색 조건 객체. Java 21 record. */
    record ProductSearchCond(
            String keyword,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            ProductStatus status,
            Boolean inStockOnly
    ) {
        static ProductSearchCond empty() {
            return new ProductSearchCond(null, null, null, null, null, null);
        }
    }
}
