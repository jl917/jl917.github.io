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
 * Step 10 — 동적 정렬과 검색 조건 조립 : 연습문제 7문제
 *
 * 규칙
 *   - 답이 맞아도 생성 SQL 이 다르면 틀린 것입니다.
 *   - 주석의 기록 칸(생성 SQL, 예외 메시지)을 반드시 채우십시오. 그것이 본체입니다.
 *   - 예외 메시지는 교재의 문구가 아니라 여러분 콘솔의 문구를 적어야 합니다.
 *     Hibernate 버전에 따라 문구가 다를 수 있습니다.
 *   - 정답은 Solution.java 에 있습니다. 먼저 풀고 여십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 문제 1. 화이트리스트 정렬 변환기
    // =================================================================
    // Sort 를 OrderSpecifier[] 로 바꾸는 변환기를 완성하십시오.
    //
    // 요구사항
    //   (a) allowed Map 에 등록된 키만 정렬에 사용한다
    //   (b) 등록되지 않은 키는 무시한다
    //   (c) 결과 배열의 "마지막" 에는 언제나 defaultOrder(PK)가 붙는다  ← 타이브레이커
    //
    // 그리고 sort=price,desc 로 호출해 생성 SQL 의 order by 절을 확인하십시오.
    // 컬럼이 두 개 나와야 정답입니다.
    // =================================================================
    @Test
    @DisplayName("문제 1 — 화이트리스트 정렬 변환기 + 타이브레이커")
    void problem1() {
        Map<String, ComparableExpressionBase<?>> allowed = new LinkedHashMap<>();
        allowed.put("price", product.price);
        allowed.put("stock", product.stock);
        allowed.put("name", product.name);

        OrderSpecifier<?> defaultOrder = product.productId.desc();

        List<Product> result = queryFactory
                .selectFrom(product)
                .orderBy(toOrderSpecifiers(Sort.by(Sort.Direction.DESC, "price"),
                        allowed, defaultOrder))
                .limit(5)
                .fetch();

        // 생성 SQL 의 order by 절을 여기에 적으십시오:
        //   order by

        result.forEach(p -> System.out.printf("  %s %s%n", p.getName(), p.getPrice()));
    }

    private OrderSpecifier<?>[] toOrderSpecifiers(
            Sort sort,
            Map<String, ComparableExpressionBase<?>> allowed,
            OrderSpecifier<?> defaultOrder) {
        // 여기에 작성:
        return null;
    }

    // =================================================================
    // 문제 2. PathBuilder vs 화이트리스트 — cost 정렬
    // =================================================================
    // Product 에는 cost(원가) 필드가 있습니다. API 응답에는 없습니다.
    // 클라이언트가 ?sort=cost,desc 를 보냈습니다.
    //
    // (a) PathBuilder 방식으로 실행하고 생성 SQL 을 기록하십시오.
    // (b) 화이트리스트 방식(문제 1의 변환기)으로 실행하고 생성 SQL 을 기록하십시오.
    // (c) 전자가 왜 위험한지 두 문장으로 쓰십시오.
    //     힌트: 응답 본문에 cost 값이 없는데도 정보가 새는 이유는 무엇입니까?
    // =================================================================
    @Test
    @DisplayName("문제 2 — cost 정렬을 두 방식으로")
    void problem2() {
        Sort sort = Sort.by(Sort.Direction.DESC, "cost");

        // (a) PathBuilder 방식 — 여기에 작성:
        List<Product> byPathBuilder = null;

        // (a) 생성 SQL 의 order by 절:
        //   order by

        // (b) 화이트리스트 방식 — 여기에 작성:
        List<Product> byWhitelist = null;

        // (b) 생성 SQL 의 order by 절:
        //   order by

        // (c) 전자가 왜 위험합니까? 두 문장으로:
        //   →
        //   →

        System.out.printf("  pathBuilder=%d whitelist=%d%n",
                byPathBuilder.size(), byWhitelist.size());
    }

    // =================================================================
    // 문제 3. 존재하지 않는 필드
    // =================================================================
    // ?sort=noSuchField,desc 가 들어왔습니다.
    //
    // (a) PathBuilder 방식으로 실행하고 "실제 예외 메시지" 를 기록하십시오.
    //     try-catch 로 잡아 e.getMessage() 를 출력하면 됩니다.
    // (b) 화이트리스트 방식(lenient)으로 실행하면 무슨 일이 일어납니까?
    // (c) 두 결과를 HTTP 응답 코드로 환산하면 각각 무엇입니까?
    // =================================================================
    @Test
    @DisplayName("문제 3 — 존재하지 않는 필드")
    void problem3() {
        Sort sort = Sort.by(Sort.Direction.DESC, "noSuchField");

        // (a) PathBuilder 방식 — 여기에 작성 (try-catch 로 메시지 출력):

        // (a) 실제 예외 메시지 (여러분 콘솔의 문구를 그대로):
        //   →

        // (b) 화이트리스트 방식 — 여기에 작성:

        // (b) 무슨 일이 일어났습니까?
        //   →

        // (c) HTTP 응답 코드로는?
        //   (a) →
        //   (b) →
    }

    // =================================================================
    // 문제 4. 조건 메서드 조립 — 네 가지 조합의 생성 SQL
    // =================================================================
    // ProductSearchCond 를 받아 조건 메서드 6개를 where 에 나란히 넘기는
    // 조회 메서드를 완성하고, 아래 네 경우의 생성 SQL 을 기록하십시오.
    //
    //   ① 조건 0개 (empty)
    //   ② 조건 1개 (status = ON_SALE)
    //   ③ 조건 3개 (minPrice, maxPrice, inStockOnly)
    //   ④ 조건 6개 (전부)
    //
    // ⚠️ ① 에서 where 절이 어떻게 나오는지 반드시 확인하십시오.
    //    where 1=1 이 나옵니까, 아니면 where 절 자체가 사라집니까?
    //    이것을 착각하는 사람이 많습니다.
    // =================================================================
    @Test
    @DisplayName("문제 4 — 조건 조합 네 가지의 생성 SQL")
    void problem4() {
        System.out.println("=== ① 조건 0개 ===");
        // 여기에 작성:
        // 생성 SQL:
        //   →

        System.out.println("=== ② 조건 1개 ===");
        // 여기에 작성:
        // 생성 SQL:
        //   →

        System.out.println("=== ③ 조건 3개 ===");
        // 여기에 작성:
        // 생성 SQL:
        //   →

        System.out.println("=== ④ 조건 6개 ===");
        // 여기에 작성:
        // 생성 SQL:
        //   →
    }

    private List<Product> search(ProductSearchCond cond) {
        // 여기에 작성: where 에 조건 메서드 6개를 나란히 넘기고 productId desc 로 정렬
        return null;
    }

    // --- 조건 메서드 — 값이 없으면 null 을 반환하도록 완성하십시오 ---

    private BooleanExpression keywordContains(String keyword) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression categoryEq(Long categoryId) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression priceGoe(BigDecimal minPrice) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression priceLoe(BigDecimal maxPrice) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression statusEq(ProductStatus status) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        // 여기에 작성:
        return null;
    }

    // =================================================================
    // 문제 5. null.and(...) NPE 재현과 두 가지 처방
    // =================================================================
    // (a) statusEq(null).and(priceGoe(50만원)) 을 where 에 넣어 NPE 를 "일부러" 내십시오.
    //     assertThatThrownBy 로 감싸지 말고 그냥 실행해 스택트레이스를 보십시오.
    //     확인한 뒤 주석 처리하고 (b)로 넘어가십시오.
    //
    // (b) Expressions.allOf 로 고치고 생성 SQL 을 기록하십시오.
    //
    // (c) null 안전 헬퍼를 직접 만들어 고치고, (b)와 생성 SQL 이 같은지 확인하십시오.
    //
    // (d) 인자 순서를 뒤집으면 (statusEq(ON_SALE).and(priceGoe(null)))
    //     예외가 납니까? 왜 그렇습니까?
    // =================================================================
    @Test
    @DisplayName("문제 5 — NPE 재현과 두 가지 처방")
    void problem5() {
        BigDecimal min = new BigDecimal("500000");

        // (a) NPE 재현 — 여기에 작성 (확인 후 주석 처리):

        // (b) Expressions.allOf — 여기에 작성:
        List<Product> byAllOf = null;

        // (b) 생성 SQL:
        //   →

        // (c) 직접 만든 헬퍼 — 여기에 작성:
        List<Product> byOwnHelper = null;

        // (c) 생성 SQL 이 (b)와 같습니까?
        //   →

        // (d) 인자 순서를 뒤집으면 예외가 납니까? 왜?
        //   →

        System.out.printf("  allOf=%d ownHelper=%d%n", byAllOf.size(), byOwnHelper.size());
    }

    private BooleanExpression andSafely(BooleanExpression... conditions) {
        // 여기에 작성: null 인 조건을 건너뛰고 AND 로 합성. 전부 null 이면 null 반환.
        return null;
    }

    // =================================================================
    // 문제 6. contains vs startsWith
    // =================================================================
    // "노트북" 을 키워드로 두 방식의 조회를 각각 실행하고,
    //
    //   (a) 생성 SQL 과 "바인딩 값" 을 각각 기록하십시오.
    //   (b) 결과 건수를 기록하십시오.
    //   (c) 어느 쪽이 인덱스를 탑니까? 그 이유를 쓰십시오.
    //   (d) products 는 40건입니다. 그렇다면 여러분은 어느 쪽을 쓰겠습니까? 왜?
    // =================================================================
    @Test
    @DisplayName("문제 6 — contains vs startsWith")
    void problem6() {
        // (a) contains — 여기에 작성:
        List<Product> byContains = null;

        // (a) 생성 SQL + 바인딩 값:
        //   →

        // (a) startsWith — 여기에 작성:
        List<Product> byStartsWith = null;

        // (a) 생성 SQL + 바인딩 값:
        //   →

        // (b) 결과 건수: contains =      / startsWith =

        // (c) 어느 쪽이 인덱스를 탑니까? 이유는?
        //   →

        // (d) 40건짜리 테이블이라면 어느 쪽을 쓰겠습니까? 왜?
        //   →

        System.out.printf("  contains=%d startsWith=%d%n",
                byContains.size(), byStartsWith.size());
    }

    // =================================================================
    // 문제 7. 통합 — 조건 + 정렬 + 페이징
    // =================================================================
    // 앞의 문제들에서 만든 조각을 조립해 아래를 완성하십시오.
    //
    // 요구사항
    //   (a) 콘텐츠 쿼리: 카테고리 이름을 함께 조회 (join 필요)
    //   (b) 조건: 문제 4의 조건 메서드 6개
    //   (c) 정렬: 문제 1의 화이트리스트 변환기 (타이브레이커 포함)
    //   (d) count 쿼리: join 과 orderBy 를 뺀 별도 JPAQuery<Long>
    //   (e) PageableExecutionUtils 로 반환
    //
    // 그리고 "count 쿼리가 실행되지 않는 호출" 을 하나 만들어 로그로 확인하십시오.
    // 힌트: Step 09 의 9-6 을 다시 읽으면 두 가지 조건 중 하나를 고르면 됩니다.
    // =================================================================
    @Test
    @DisplayName("문제 7 — 통합 메서드")
    void problem7() {
        ProductSearchCond cond = new ProductSearchCond(
                "노트북", null, new BigDecimal("500000"), null, ProductStatus.ON_SALE, true);

        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "price"));

        Page<Tuple> page = searchPage(cond, pageable);

        // 콘텐츠 생성 SQL:
        //   →

        // count 생성 SQL 이 로그에 찍혔습니까?
        //   →   (찍히지 않았다면 왜?)

        System.out.printf("  content=%d total=%d%n",
                page.getContent().size(), page.getTotalElements());
    }

    private Page<Tuple> searchPage(ProductSearchCond cond, Pageable pageable) {
        // 여기에 작성:
        return null;
    }

    // =================================================================
    // 참고 — 문제를 풀 때 쓸 수 있는 것
    // =================================================================
    //   ProductStatus.ON_SALE / SOLD_OUT / HIDDEN
    //   products 40건, categories 17건
    //   상품 예시: 게이밍 노트북 RTX4060(2190000), 보급형 노트북 15(690000),
    //             27인치 4K 모니터(459000), 원목 4인 식탁(459000),
    //             인체공학 사무용 의자(329000), 슬림핏 치노 팬츠(49000)
    //
    //   com.querydsl.core.types.Order 는 도메인 Order 엔티티와 이름이 충돌합니다.
    //   이 파일에는 도메인 Order 를 import 하지 않았으므로 필요하면 FQN 으로 쓰십시오.
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

    @SuppressWarnings("unused")
    private void keepImportsUsed() {
        // import 가 회색으로 뜨는 것을 막기 위한 자리입니다. 답안 작성에는 필요 없습니다.
        PathBuilder<?> pb = new PathBuilder<>(Product.class, "product");
        JPAQuery<Long> q = queryFactory.select(product.count()).from(product);
        List<OrderSpecifier<?>> l = new ArrayList<>();
        System.out.println(pb + " " + q + " " + l + " " + category + " " + em.isOpen()
                + " " + Expressions.constant(1) + " " + PageableExecutionUtils.class);
    }
}
