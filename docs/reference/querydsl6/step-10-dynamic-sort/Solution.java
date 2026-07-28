package com.example.shop.step10;

import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.Tuple;
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

/**
 * Step 10 — 동적 정렬과 검색 조건 조립 : 연습문제 정답과 해설
 *
 * 문제를 직접 풀어 본 뒤에 여십시오.
 * 코드보다 주석이 본체입니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    private static final Map<String, ComparableExpressionBase<?>> ALLOWED = new LinkedHashMap<>();
    private static final OrderSpecifier<?> DEFAULT_ORDER = product.productId.desc();

    static {
        ALLOWED.put("price", product.price);
        ALLOWED.put("stock", product.stock);
        ALLOWED.put("createdAt", product.createdAt);
        ALLOWED.put("name", product.name);
    }

    // =================================================================
    // 정답 1 — 화이트리스트 정렬 변환기 + 타이브레이커
    // =================================================================
    @Test
    @DisplayName("정답 1 — 화이트리스트 정렬 변환기")
    void solution1() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .orderBy(toOrderSpecifiers(Sort.by(Sort.Direction.DESC, "price"),
                        ALLOWED, DEFAULT_ORDER))
                .limit(5)
                .fetch();

        result.forEach(p -> System.out.printf("  %s %s%n", p.getName(), p.getPrice()));

        // 생성 SQL
        //   select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
        //          p1_0.name, p1_0.price, p1_0.status, p1_0.stock
        //   from products p1_0
        //   order by p1_0.price desc, p1_0.product_id desc
        //   limit ?
        //
        //   ★ 컬럼이 두 개입니다. product_id desc 가 타이브레이커입니다.
        //
        // 해설
        //   변환기가 하는 일은 세 가지입니다.
        //     ① Sort.Order 를 순회하며 allowed Map 에서 표현식을 찾는다
        //     ② 없으면 건너뛴다 (그 키는 절대 쿼리에 들어가지 않는다)
        //     ③ 마지막에 defaultOrder 를 무조건 덧붙인다
        //
        //   ③ 이 Step 09 의 9-9 를 유틸에 내장한 것입니다.
        //   페이징 쿼리의 orderBy 마지막이 PK 여야 한다는 규칙을 사람이 매번 기억할 수는 없습니다.
        //   변환기가 강제하면 잊을 수가 없습니다.
        //
        //   ★ 왜 ComparableExpressionBase<?> 인가
        //     .asc() / .desc() 를 가진 최소 상위 타입이기 때문입니다.
        //     StringPath, NumberPath, DateTimePath, EnumPath 가 모두 이것을 상속합니다.
        //     Expression<?> 으로 받으면 .asc() 를 부를 수 없습니다.
    }

    private OrderSpecifier<?>[] toOrderSpecifiers(
            Sort sort,
            Map<String, ComparableExpressionBase<?>> allowed,
            OrderSpecifier<?> defaultOrder) {

        List<OrderSpecifier<?>> result = new ArrayList<>();
        for (Sort.Order o : sort) {
            ComparableExpressionBase<?> expr = allowed.get(o.getProperty());
            if (expr == null) {
                continue;                                    // 미등록 키는 무시
            }
            result.add(o.isAscending() ? expr.asc() : expr.desc());
        }
        result.add(defaultOrder);                            // 타이브레이커는 언제나 마지막
        return result.toArray(new OrderSpecifier[0]);
    }

    // =================================================================
    // 정답 2 — PathBuilder vs 화이트리스트 (cost 정렬)
    // =================================================================
    @Test
    @DisplayName("정답 2 — cost 정렬을 두 방식으로")
    void solution2() {
        Sort sort = Sort.by(Sort.Direction.DESC, "cost");

        System.out.println("--- (a) PathBuilder ---");
        List<Product> byPathBuilder = queryFactory
                .selectFrom(product)
                .orderBy(pathBuilderSort(sort, Product.class, "product"))
                .limit(5)
                .fetch();

        System.out.println("--- (b) 화이트리스트 ---");
        List<Product> byWhitelist = queryFactory
                .selectFrom(product)
                .orderBy(toOrderSpecifiers(sort, ALLOWED, DEFAULT_ORDER))
                .limit(5)
                .fetch();

        System.out.printf("  pathBuilder 상위: %s%n",
                byPathBuilder.stream().map(Product::getProductId).toList());
        System.out.printf("  whitelist  상위: %s%n",
                byWhitelist.stream().map(Product::getProductId).toList());

        // (a) 생성 SQL
        //     order by p1_0.cost desc
        //
        // (b) 생성 SQL
        //     order by p1_0.product_id desc
        //     ("cost" 는 ALLOWED 에 없으므로 무시되고 기본 정렬만 남았습니다)
        //
        // (c) 전자가 왜 위험한가
        //
        //   [문장 1] Product 의 cost(원가)는 응답 DTO 에 없지만 PathBuilder 는 어떤 필드든
        //            정렬 키로 받아 주므로, 엔티티의 모든 필드가 정렬 대상으로 열려 있습니다.
        //
        //   [문장 2] 응답 본문에 cost 값이 없어도 "순서" 자체가 정보이므로,
        //            asc 와 desc 를 번갈아 호출하면 원가 순위를 완전히 복원할 수 있고
        //            price 를 알고 있으니 마진 구조까지 역산됩니다.
        //
        // ★ 이것은 버그가 아니라 설계상 열려 있는 문입니다.
        //   아무 에러도 나지 않고, 로그에도 아무 흔적이 없습니다.
        //   코드 리뷰에서 "정렬 기능 추가" 로 통과합니다.
        //
        // ★ 화이트리스트의 성질
        //   등록되지 않은 키가 쿼리에 "절대" 들어가지 않는다는 것이 전부입니다.
        //   무시할지(lenient) 400 을 던질지(strict)는 그다음 선택입니다.
        //   공개 API 라면 strict 가 낫습니다. 클라이언트가 오타를 즉시 알 수 있고
        //   "정렬을 요청했는데 조용히 무시되는" 상황이 사라집니다.
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OrderSpecifier<?>[] pathBuilderSort(Sort sort, Class<?> entityType, String alias) {
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

    // =================================================================
    // 정답 3 — 존재하지 않는 필드
    // =================================================================
    @Test
    @DisplayName("정답 3 — 존재하지 않는 필드")
    void solution3() {
        Sort sort = Sort.by(Sort.Direction.DESC, "noSuchField");

        // (a) PathBuilder 방식
        System.out.println("--- (a) PathBuilder ---");
        try {
            queryFactory.selectFrom(product)
                    .orderBy(pathBuilderSort(sort, Product.class, "product"))
                    .fetch();
            System.out.println("  예외 없음 (예상 밖)");
        } catch (Exception e) {
            System.out.println("  예외: " + e.getClass().getSimpleName());
            System.out.println("  메시지: " + e.getMessage());
        }

        // (b) 화이트리스트 방식
        System.out.println("--- (b) 화이트리스트 (lenient) ---");
        List<Product> ok = queryFactory.selectFrom(product)
                .orderBy(toOrderSpecifiers(sort, ALLOWED, DEFAULT_ORDER))
                .limit(5)
                .fetch();
        System.out.printf("  정상 조회 %d건 — 기본 정렬로 대체됨%n", ok.size());

        // (a) 실제 예외 (Hibernate 6.4)
        //     java.lang.IllegalArgumentException:
        //       org.hibernate.query.SemanticException:
        //         Could not interpret path expression 'product.noSuchField'
        //
        //     ★ 문구는 Hibernate 버전에 따라 다를 수 있습니다.
        //       여러분 콘솔의 문구를 기준으로 삼으십시오.
        //
        // (b) 생성 SQL: order by p1_0.product_id desc
        //     정상 조회됩니다. 요청한 정렬만 무시됐습니다.
        //
        // (c) HTTP 응답 코드
        //     (a) → 500 Internal Server Error
        //     (b) → 200 OK (lenient) 또는 400 Bad Request (strict)
        //
        // 해설 — 이것이 PathBuilder 방식의 "진짜" 위험입니다
        //
        //   SQL 인젝션보다 이쪽이 훨씬 자주, 훨씬 쉽게 발생합니다.
        //   공격이랄 것도 없이 ?sort=x,desc 하나로 500 이 납니다.
        //   프론트엔드가 필드명을 리네이밍했는데 백엔드에 반영이 안 됐다면 그날로 장애입니다.
        //
        //   ★ PathBuilder 자체는 아무 검증도 하지 않습니다.
        //     어떤 문자열이든 경로로 만들어 줍니다.
        //     검증은 나중에 JPQL 을 파싱하는 Hibernate 가 합니다.
        //     컴파일 시점에도, 쿼리 조립 시점에도 아무 일이 없다가 실행 시점에 터집니다.
        //     "타입 안전한 QueryDSL" 의 타입 안전성이 여기서 완전히 끊깁니다.
        //
        // ★ SQL 인젝션에 대한 정확한 서술 (이 스텝의 핵심 논지)
        //
        //   질문: ?sort=price; DROP TABLE products-- 는 통합니까?
        //   답:   대체로 막힙니다. 그러나 "우리 코드가 막은 것이 아닙니다."
        //
        //   QueryDSL-JPA 가 만드는 것은 SQL 이 아니라 JPQL 입니다.
        //   Hibernate 의 JPQL 파서가 그 문자열을 유효한 경로 표현식으로 해석하지 못해
        //   SemanticException 이 납니다. 파서가 방어벽 역할을 우연히 해 준 것입니다.
        //
        //   이 방어에 기대면 안 되는 이유는 두 가지입니다.
        //
        //     ① 방어의 근거가 우리 코드가 아닙니다.
        //        파서의 관대함이 버전에 따라 달라지면 그 방어도 달라집니다.
        //        "현재 버전에서 막히더라" 는 보안 근거가 될 수 없습니다.
        //
        //     ② 문자열 조립 경로와 결합하면 실제로 열립니다.
        //        QueryDSL 에는 JPQL/SQL 조각을 문자열로 직접 만드는 통로가 있습니다.
        //          Expressions.stringTemplate("... '" + userInput + "' ...", ...)
        //        여기서는 userInput 이 템플릿 문자열 자체의 일부가 되므로 파서 방어가 통하지 않습니다.
        //        (Step 13 에서 실제로 인젝션이 성립하는 예를 봅니다.)
        //
        //        PathBuilder 로 "사용자 문자열을 쿼리 구조에 넣는" 습관이 자리잡으면,
        //        그 습관은 stringTemplate 을 쓰는 순간 그대로 취약점이 됩니다.
        //
        //   ★ 결론 — "QueryDSL 이라서 안전하다" 는 잘못된 안심입니다.
        //     안전한 것은 값 바인딩입니다.
        //     구조(테이블·컬럼·경로·정렬 방향)는 바인딩 대상이 아닙니다.
        //     어떤 쿼리 도구를 쓰든 사용자 입력이 쿼리의 구조를 결정하게 하면 안 됩니다.
        //     QueryDSL 은 이 실수를 더 어렵게 만들어 주지만 불가능하게 만들지는 않습니다.
    }

    // =================================================================
    // 정답 4 — 조건 조합 네 가지의 생성 SQL
    // =================================================================
    @Test
    @DisplayName("정답 4 — 조건 조합 네 가지")
    void solution4() {
        System.out.println("=== ① 조건 0개 ===");
        List<Product> r1 = search(ProductSearchCond.empty());
        System.out.printf("  %d건%n", r1.size());

        System.out.println("=== ② 조건 1개 ===");
        List<Product> r2 = search(new ProductSearchCond(
                null, null, null, null, ProductStatus.ON_SALE, null));
        System.out.printf("  %d건%n", r2.size());

        System.out.println("=== ③ 조건 3개 ===");
        List<Product> r3 = search(new ProductSearchCond(
                null, null, new BigDecimal("100000"), new BigDecimal("500000"), null, true));
        System.out.printf("  %d건%n", r3.size());

        System.out.println("=== ④ 조건 6개 ===");
        List<Product> r4 = search(new ProductSearchCond(
                "노트북", 7L, new BigDecimal("500000"), new BigDecimal("2500000"),
                ProductStatus.ON_SALE, true));
        System.out.printf("  %d건%n", r4.size());

        // ① 생성 SQL
        //    select p1_0.product_id, ... from products p1_0
        //    order by p1_0.product_id desc
        //
        //    ★★ where 절이 통째로 사라졌습니다.
        //      where 1=1 이 아닙니다. 빈 where 도 아닙니다. 절 자체가 없습니다.
        //
        //      where 1=1 을 붙이는 습관은 문자열 SQL 을 손으로 조립하던 시대의 것입니다.
        //      "조건이 하나도 없을 때 where 뒤에 아무것도 없어 문법 에러가 나는 것" 을
        //      피하려고 쓰던 방편이었습니다.
        //      QueryDSL 에서는 불필요할 뿐 아니라, 옵티마이저에게 잡음이 되고
        //      실행 계획 캐시에도 도움이 되지 않습니다.
        //
        // ② where p1_0.status = ?                      바인딩: [1] ON_SALE
        //    조회 31건
        //
        // ③ where p1_0.price >= ? and p1_0.price <= ? and p1_0.stock > ?
        //    바인딩: [1] 100000  [2] 500000  [3] 0
        //    조회 9건
        //
        //    ★ 괄호가 없습니다. and 만 있으므로 괄호가 필요 없습니다.
        //      or 가 섞이면 이야기가 달라집니다 (Step 04 의 4-5).
        //
        // ④ where p1_0.name like ? escape '!'
        //      and p1_0.category_id = ?
        //      and p1_0.price >= ? and p1_0.price <= ?
        //      and p1_0.status = ? and p1_0.stock > ?
        //    바인딩: [1] %노트북%  [2] 7  [3] 500000  [4] 2500000  [5] ON_SALE  [6] 0
        //    조회 2건
        //
        // 해설
        //   ★ escape '!' — contains() 는 % 를 앞뒤로 붙이면서 사용자 입력 안의 % 와 _ 를
        //     이스케이프하기 위해 escape 문자를 지정합니다.
        //     사용자가 "100%" 를 검색해도 % 가 와일드카드로 해석되지 않습니다.
        //     LIKE 와일드카드 인젝션은 QueryDSL 이 막아 줍니다. 값 바인딩 영역이기 때문입니다.
        //     (반면 정렬 키는 구조이므로 막아 주지 않습니다 — 정답 2·3)
        //
        //   ★ category_id = ? — product.category.categoryId 는 조인을 만들지 않습니다.
        //     FK 컬럼이 products 테이블에 있으므로 p1_0.category_id 로 직접 비교합니다.
        //     product.category.name 이었다면 join categories 가 생겼을 것입니다.
        //     이 차이가 정답 7 의 "count 쿼리에서 join 을 뺄 수 있는 이유" 로 이어집니다.
        //
        //   ★ where(a, b, c) 는 인자들을 AND 로 묶고 null 인 인자를 무시합니다 (Step 04 의 4-3, 4-4).
        //     이 성질 하나가 동적 쿼리 전체의 토대입니다.
        //     if 문이 하나도 없이 2^6 = 64가지 조합이 전부 처리됩니다.
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

    // --- 조건 메서드. 전부 null 을 반환할 수 있습니다. 절대 .and() 로 잇지 마십시오. ---

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
    // 정답 5 — NPE 재현과 두 가지 처방
    // =================================================================
    @Test
    @DisplayName("정답 5 — NPE 재현과 두 가지 처방")
    void solution5() {
        BigDecimal min = new BigDecimal("500000");

        // (a) NPE 재현
        try {
            queryFactory.selectFrom(product)
                    .where(statusEq(null).and(priceGoe(min)))
                    .fetch();
            System.out.println("  (a) 예외 없음 (예상 밖)");
        } catch (NullPointerException e) {
            System.out.println("  (a) NPE 발생: " + e.getMessage());
        }

        // (b) Expressions.allOf
        List<Product> byAllOf = queryFactory.selectFrom(product)
                .where(Expressions.allOf(statusEq(null), priceGoe(min)))
                .fetch();

        // (c) 직접 만든 헬퍼
        List<Product> byOwnHelper = queryFactory.selectFrom(product)
                .where(andSafely(statusEq(null), priceGoe(min)))
                .fetch();

        // (d) 인자 순서를 뒤집으면
        List<Product> reversed = queryFactory.selectFrom(product)
                .where(statusEq(ProductStatus.ON_SALE).and(priceGoe(null)))
                .fetch();

        System.out.printf("  (b) allOf=%d  (c) ownHelper=%d  (d) reversed=%d%n",
                byAllOf.size(), byOwnHelper.size(), reversed.size());

        // (a) 실제 예외
        //     java.lang.NullPointerException: Cannot invoke
        //       "com.querydsl.core.types.dsl.BooleanExpression.and(com.querydsl.core.types.Predicate)"
        //       because the return value of "...statusEq(...)" is null
        //
        //     statusEq(null) 이 null 을 반환했고, 그 null 에 .and(...) 를 호출했습니다.
        //     QueryDSL 이 아니라 그냥 자바의 NPE 입니다.
        //
        // (b) 생성 SQL: where p1_0.price >= ?      바인딩: [1] 500000
        //     status 조건만 사라졌습니다. 예외 없음.
        //
        // (c) 생성 SQL: (b)와 완전히 동일
        //
        // (d) 예외가 나지 않습니다.
        //     생성 SQL: where p1_0.status = ?      바인딩: [1] ON_SALE
        //     .and(null) 은 QueryDSL 이 "인자로 받은 null" 을 무시하므로 안전합니다.
        //
        // ★★ 이 비대칭이 함정의 본질입니다.
        //
        //     앞이 null 이면 죽고, 뒤가 null 이면 통과합니다.
        //
        //     개발 중에는 조건을 다 채워서 테스트하므로 통과합니다.
        //     운영에서 사용자가 조건 하나를 비우는 순간 500 이 납니다.
        //     그것도 "어떤" 조건을 비우느냐에 따라 나기도 하고 안 나기도 합니다.
        //     재현 조건이 특정 파라미터 조합이라 QA 도 잘 못 잡습니다.
        //
        // ★ Expressions.allOf 의 주의점
        //   모든 인자가 null 이면 allOf 자체가 null 을 반환합니다.
        //   그 반환값에 다시 .and() 를 이으면 똑같은 NPE 가 재발합니다.
        //   allOf 는 null 을 걸러 주지만 null 을 만들지 않는다고 보장하지 않습니다.
        //   합성이 두 단계 이상이면 바깥도 allOf 로 감싸십시오.
        //     Expressions.allOf(Expressions.allOf(a, b), c)
        //
        //   그리고 이 동작은 버전에 따라 확인하고 쓰십시오 (이 코스는 QueryDSL 6.12 기준).
        //   확신이 서지 않으면 (c) 처럼 직접 만드십시오.
        //   직접 만든 헬퍼는 버전과 무관하게 여러분이 동작을 보증할 수 있습니다.
        //   20줄짜리 코드로 사는 확실성입니다.
        //
        // ★ 가장 단순한 처방 — 애초에 합성하지 않는다
        //   .where(statusEq(s), priceGoe(min), priceLoe(max))
        //   where 가 AND 로 묶고 null 을 무시합니다. NPE 가 생길 자리가 없습니다.
        //
        //   합성이 필요한 경우는 or 그룹을 만들거나 purchasable() 처럼 이름 붙은 개념을
        //   정의할 때뿐이고, 그런 개념은 대개 null 을 반환하지 않는 상수 조건입니다.
        //
        // ★ 규칙으로 정리하십시오
        //   - null 을 반환할 수 있는 조건 메서드 → where 에 나란히 넘기기만 한다
        //   - null 을 반환하지 않는 개념 메서드 → 자유롭게 합성해도 된다
        //   - 두 종류를 섞어야 한다면 → null 안전 헬퍼를 쓴다
        //   메서드 이름이나 주석으로 "이 메서드는 null 을 반환할 수 있다" 를 표시해 두십시오.
    }

    private BooleanExpression andSafely(BooleanExpression... conditions) {
        BooleanExpression result = null;
        for (BooleanExpression c : conditions) {
            if (c == null) {
                continue;
            }
            result = (result == null) ? c : result.and(c);
        }
        return result;
    }

    // =================================================================
    // 정답 6 — contains vs startsWith
    // =================================================================
    @Test
    @DisplayName("정답 6 — contains vs startsWith")
    void solution6() {
        List<Product> byContains = queryFactory.selectFrom(product)
                .where(product.name.contains("노트북"))
                .orderBy(product.productId.desc())
                .fetch();

        List<Product> byStartsWith = queryFactory.selectFrom(product)
                .where(product.name.startsWith("노트북"))
                .orderBy(product.productId.desc())
                .fetch();

        System.out.printf("  contains   → %d건 %s%n",
                byContains.size(), byContains.stream().map(Product::getName).toList());
        System.out.printf("  startsWith → %d건 %s%n",
                byStartsWith.size(), byStartsWith.stream().map(Product::getName).toList());

        // (a) 생성 SQL — 두 경우 모두 같습니다
        //     where p1_0.name like ? escape '!'
        //
        //     바인딩만 다릅니다.
        //       contains   → [1] %노트북%
        //       startsWith → [1] 노트북%
        //
        //     ★ SQL 문자열은 똑같은데 바인딩 값 하나로 성능이 완전히 갈립니다.
        //       생성 SQL 만 보고 안심할 수 없는 대표적인 경우입니다.
        //       바인딩 로그(org.hibernate.orm.jdbc.bind: trace)를 켜야 보입니다.
        //
        // (b) 결과 건수
        //     contains   → 2건 (게이밍 노트북 RTX4060, 보급형 노트북 15)
        //     startsWith → 0건
        //
        // (c) 어느 쪽이 인덱스를 타는가 — startsWith
        //
        //     인덱스는 값의 앞에서부터 정렬돼 있습니다.
        //     '노트북%' 처럼 앞이 고정된 패턴은 "노트북" 으로 시작하는 구간으로
        //     즉시 내려가 그 구간만 읽으면 됩니다 (EXPLAIN type: range).
        //     '%노트북%' 은 어디서 시작하는지 알 수 없어 전부 훑어야 합니다 (type: index).
        //
        //     전화번호부는 "김" 으로 시작하는 사람은 빨리 찾지만
        //     "수" 로 끝나는 사람은 다 뒤져야 합니다.
        //
        //     100만 행 access_logs 실측 (idx_path 있는 상태):
        //       LIKE '%detail'     → type: index, rows 996151, 0.219초
        //       LIKE '/products%'  → type: range, rows 498075, 0.041초
        //
        //     ⚠️ type: index 는 "인덱스를 탔다" 가 아닙니다.
        //       인덱스 B+Tree 를 처음부터 끝까지 훑는 것으로 사실상 전수 조사입니다.
        //       range · ref · const 여야 진짜로 탐색한 것입니다.
        //       (MySQL8 코스 Step 15 의 15-5, 15-7)
        //
        // (d) 40건짜리 테이블이라면? — contains 를 씁니다.
        //
        //     ★ 이것이 이 문제의 진짜 정답입니다.
        //
        //     products 는 40건입니다. 풀스캔이 0.000초입니다.
        //     그리고 startsWith 는 0건을 반환합니다.
        //     "게이밍 노트북 RTX4060" 은 "노트북" 으로 시작하지 않습니다.
        //     빠르지만 사용자가 기대하는 검색이 아닙니다.
        //
        //     측정되지 않은 성능 문제를 미리 최적화하지 마십시오.
        //     이 절의 목적은 "contains 를 쓰지 말라" 가 아니라
        //     "언제 문제가 되는지 알고 있으라" 입니다.
        //
        //     데이터가 커졌을 때의 선택지는 세 가지입니다.
        //       ① FULLTEXT 인덱스 (한글은 WITH PARSER ngram 필요)
        //          QueryDSL 에서는 Expressions.booleanTemplate 으로 MATCH...AGAINST 를 호출해야 하며,
        //          ★ 사용자 입력을 절대 템플릿 문자열에 이어 붙이지 마십시오 (정답 3, Step 13).
        //          반드시 바인딩 파라미터({0}, {1})로 넘기십시오.
        //       ② 다른 조건으로 후보를 먼저 좁히기
        //          where(categoryEq(cid), statusEq(ON_SALE), keywordContains(kw)) 라면
        //          옵티마이저가 category_id 인덱스로 후보를 좁힌 뒤 그 안에서만 LIKE 를 평가합니다.
        //          "카테고리를 반드시 선택하게 하는" UI 설계가 곧 성능 설계입니다.
        //       ③ 전용 검색 엔진 (형태소 분석·오타 교정·동의어·랭킹이 필요할 때)
    }

    // =================================================================
    // 정답 7 — 통합
    // =================================================================
    @Test
    @DisplayName("정답 7 — 통합 메서드")
    void solution7() {
        ProductSearchCond cond = new ProductSearchCond(
                "노트북", null, new BigDecimal("500000"), null, ProductStatus.ON_SALE, true);

        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "price"));

        Page<Tuple> page = searchPage(cond, pageable);

        System.out.printf("  content=%d total=%d totalPages=%d%n",
                page.getContent().size(), page.getTotalElements(), page.getTotalPages());
        page.getContent().forEach(t -> System.out.printf("    %s %s (%s)%n",
                t.get(product.name), t.get(product.price), t.get(category.name)));

        // 콘텐츠 생성 SQL
        //   select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock, p1_0.status, c1_0.name
        //   from products p1_0
        //   join categories c1_0 on c1_0.category_id = p1_0.category_id
        //   where p1_0.name like ? escape '!'
        //     and p1_0.price >= ?
        //     and p1_0.status = ?
        //     and p1_0.stock > ?
        //   order by p1_0.price desc, p1_0.product_id desc
        //   limit ?, ?
        //   바인딩: [1] %노트북%  [2] 500000  [3] ON_SALE  [4] 0  [5] 0  [6] 10
        //
        // count 생성 SQL — 만들어졌지만 실행되지 않습니다
        //   select count(p1_0.product_id) from products p1_0
        //   where p1_0.name like ? escape '!' and p1_0.price >= ?
        //     and p1_0.status = ? and p1_0.stock > ?
        //
        // ★ count 쿼리가 로그에 찍히지 않는 이유
        //   offset=0 이고 결과 2건이 pageSize 10 보다 작습니다.
        //   → 첫 페이지에 전부 담겼다는 뜻이므로 total = 2 로 확정됩니다.
        //   PageableExecutionUtils 가 Supplier 를 호출하지 않습니다 (Step 09 의 9-6).
        //
        // ★ count 쿼리에서 join 을 뺄 수 있는 이유
        //   where 절이 categories 테이블의 컬럼을 하나도 참조하지 않기 때문입니다.
        //   categoryEq 는 product.category.categoryId 인데, 이것은 FK 컬럼이라
        //   products 테이블 안에서 p1_0.category_id 로 직접 비교됩니다 (정답 4 참고).
        //   조인은 오직 "카테고리 이름을 화면에 표시하기 위해" 존재합니다.
        //
        //   만약 조건이 categoryNameEq(product.category.name.eq(...)) 였다면
        //   count 쿼리에도 join 이 반드시 남아야 합니다.
        //   규칙: 표시(select)를 위한 조인만 뺀다. 필터(where)를 위한 조인은 남긴다.
        //
        //   그리고 @OneToMany 컬렉션 조인이라면 행이 뻥튀기되어 count 가 커집니다.
        //   그 경우 countDistinct() 가 필요한지 반드시 확인하십시오 (Step 08 의 8-7).
        //
        // ★ 이 메서드 하나에 코스의 여러 스텝이 들어 있습니다
        //   | 요소                                   | 출처     |
        //   | where(a, b, c) 의 AND + null 무시       | Step 04 |
        //   | 프로젝션 (여기서는 Tuple, 실무는 @QueryProjection DTO) | Step 05 |
        //   | join(product.category, category)       | Step 06 |
        //   | product.count()                        | Step 08 |
        //   | count 분리 + PageableExecutionUtils     | Step 09 |
        //   | PK 타이브레이커                          | Step 09 |
        //   | 화이트리스트 정렬                        | Step 10 |
        //
        //   실무에서는 Tuple 대신 @QueryProjection DTO 를 쓰십시오.
        //   Tuple 은 타입 안전성을 잃고, t.get(...) 이 조용히 null 을 반환합니다 (Step 08 의 8-4).
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
                .orderBy(toOrderSpecifiers(pageable.getSort(), ALLOWED, DEFAULT_ORDER))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

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
