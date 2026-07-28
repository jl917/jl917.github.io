package com.example.shop.step12;

import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.example.shop.entity.QProduct;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.SimpleExpression;
import com.querydsl.core.types.dsl.StringExpression;
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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.querydsl.binding.QuerydslBinderCustomizer;
import org.springframework.data.querydsl.binding.QuerydslBindings;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QProduct.product;

/**
 * Step 12 — Spring Data JPA 통합 : 연습문제 정답과 해설.
 *
 * <p>각 메서드 주석에 <b>왜 그 답인지</b>가 적혀 있습니다.
 * 특히 정답 2의 인과 사슬은 실무에서 같은 에러를 만났을 때 바로 원인을 짚기 위한 것입니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 공통 — 문제 1에서 만든 타입들
    // =================================================================

    /** 조건 DTO. QueryDSL 의존이 없어 컨트롤러 → 서비스 → 리포지토리를 그대로 통과합니다 (12-8). */
    record ProductSearchCond(String name, ProductStatus status,
                             BigDecimal minPrice, BigDecimal maxPrice) {
    }

    /** 결과 DTO. 실제 프로젝트에서는 {@code @QueryProjection} 을 붙이십시오 (Step 05). */
    record ProductSearchDto(Long productId, String name, BigDecimal price,
                            Integer stock, String categoryName) {
    }

    /** 프래그먼트 인터페이스. 시그니처에 QueryDSL 타입이 없습니다. */
    interface ProductRepositoryCustom {
        Page<ProductSearchDto> searchProducts(ProductSearchCond cond, Pageable pageable);
    }

    /**
     * 구현체. 실제 파일 이름은 {@code ProductRepositoryImpl.java} 이어야 합니다.
     * <p>생성자 주입인 것에 주의하십시오. Spring Data 가 이 클래스를 직접 인스턴스화하므로
     * 필드 주입은 null 로 남을 수 있습니다.
     */
    static class ProductRepositoryImpl implements ProductRepositoryCustom {

        private final JPAQueryFactory queryFactory;

        ProductRepositoryImpl(JPAQueryFactory queryFactory) {
            this.queryFactory = queryFactory;
        }

        @Override
        public Page<ProductSearchDto> searchProducts(ProductSearchCond cond, Pageable pageable) {
            List<ProductSearchDto> content = queryFactory
                    .select(Projections.constructor(ProductSearchDto.class,
                            product.id, product.name, product.price, product.stock, category.name))
                    .from(product)
                    .join(product.category, category)
                    .where(nameContains(cond.name()),
                            statusEq(cond.status()),
                            priceGoe(cond.minPrice()),
                            priceLoe(cond.maxPrice()))
                    .orderBy(product.id.asc())
                    .offset(pageable.getOffset())
                    .limit(pageable.getPageSize())
                    .fetch();

            JPAQuery<Long> countQuery = queryFactory
                    .select(product.count())
                    .from(product)
                    .join(product.category, category)
                    .where(nameContains(cond.name()),
                            statusEq(cond.status()),
                            priceGoe(cond.minPrice()),
                            priceLoe(cond.maxPrice()));

            return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
        }

        private BooleanExpression nameContains(String name) {
            return (name == null || name.isBlank()) ? null : product.name.contains(name);
        }

        private BooleanExpression statusEq(ProductStatus status) {
            return status == null ? null : product.status.eq(status);
        }

        private BooleanExpression priceGoe(BigDecimal min) {
            return min == null ? null : product.price.goe(min);
        }

        private BooleanExpression priceLoe(BigDecimal max) {
            return max == null ? null : product.price.loe(max);
        }
    }

    /** 메인 리포지토리. 둘 다 상속합니다. */
    interface ProductRepository extends JpaRepository<Product, Long>, ProductRepositoryCustom {
    }

    // =================================================================
    // 문제 1
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>구조는 세 층입니다.
     * <pre>
     * ProductRepository        → JpaRepository + ProductRepositoryCustom 을 둘 다 상속
     * ProductRepositoryCustom  → 커스텀 메서드 선언만
     * ProductRepositoryImpl    → QueryDSL 구현. 이름이 반드시 이래야 합니다
     * </pre>
     *
     * <p>세 가지를 짚습니다.
     *
     * <p>① <b>조건 메서드를 분리</b>합니다. {@code null} 을 반환하면 {@code where} 에서 무시되므로
     * 조합 폭발($2^4 = 16$ 가지)을 코드 한 벌로 처리할 수 있습니다.
     * {@code BooleanExpression} 을 반환 타입으로 두면 나중에 {@code .and()} 로 조합해 재사용할 수 있습니다.
     * {@code Predicate} 로 두면 조합이 안 됩니다.
     *
     * <p>② <b>{@code join(product.category, category)} 를 명시</b>합니다.
     * {@code product.category.name} 만 써도 묵시적 조인으로 동작하지만,
     * 조인 종류를 통제할 수 없고 SQL 을 예측하기 어려워집니다.
     * 카테고리는 {@code NOT NULL} FK 이므로 inner join 이 맞습니다.
     * nullable 이었다면 {@code leftJoin} 을 써야 결과가 누락되지 않습니다.
     *
     * <p>③ <b>시그니처에 QueryDSL 타입이 없습니다.</b>
     * 조건은 {@code ProductSearchCond} 라는 평범한 record 로 받습니다.
     * {@code Predicate} 를 파라미터로 두는 순간 서비스가 QueryDSL 을 알아야 합니다 (12-8).
     *
     * <p>생성 SQL
     * <pre>
     * select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock, c1_0.name
     * from products p1_0
     * join categories c1_0 on c1_0.category_id=p1_0.category_id
     * where p1_0.name like ? escape '!' and p1_0.status=? and p1_0.price&gt;=?
     * order by p1_0.product_id
     * limit ?, ?
     * </pre>
     */
    @Test
    @DisplayName("정답 1. 커스텀 리포지토리 3층 구조")
    void solution1() {
        ProductRepositoryImpl impl = new ProductRepositoryImpl(queryFactory);
        ProductSearchCond cond = new ProductSearchCond(
                "노트북", ProductStatus.ON_SALE, new BigDecimal("500000"), null);

        Page<ProductSearchDto> page = impl.searchProducts(cond, PageRequest.of(0, 10));

        System.out.println("[정답 1] total=" + page.getTotalElements());
        page.getContent().forEach(d -> System.out.println("  " + d));
    }

    // =================================================================
    // 문제 2
    // =================================================================

    /**
     * <b>정답 해설 — 에러 인과 사슬 9단계</b>
     *
     * <p>구현 클래스를 {@code ProductRepositoryQuerydsl} 로 바꾸면 <b>컴파일은 통과합니다.</b>
     * 자바 입장에서 아무 문제가 없기 때문입니다. 애플리케이션 기동 시점에 터집니다.
     *
     * <pre>
     * org.springframework.beans.factory.BeanCreationException:
     *   Error creating bean with name 'productRepository' defined in
     *   com.example.shop.repository.ProductRepository defined in @EnableJpaRepositories ...:
     *   Invocation of init method failed
     *
     * Caused by: org.springframework.data.repository.query.QueryCreationException:
     *   Could not create query for public abstract org.springframework.data.domain.Page
     *   com.example.shop.repository.ProductRepositoryCustom.searchProducts(...);
     *   Reason: Failed to create query for method ...;
     *   No property 'searchProducts' found for type 'Product'
     *
     * Caused by: org.springframework.data.mapping.PropertyReferenceException:
     *   No property 'searchProducts' found for type 'Product'
     * </pre>
     *
     * <p><b>왜 "No property found" 인가</b>
     * <pre>
     * ① Spring Data 가 ProductRepository 프록시를 만들기 시작한다
     * ② 메서드 searchProducts 를 처리할 주체를 정해야 한다
     * ③ 커스텀 구현 후보 이름을 계산한다
     *      "ProductRepositoryImpl" 또는 "ProductRepositoryCustomImpl"
     * ④ 스캔한 빈 중 그 이름이 없다  ← 진짜 원인
     *      (실제 이름은 ProductRepositoryQuerydsl)
     * ⑤ Spring Data 는 여기서 에러를 내지 않는다.
     *      "커스텀 구현이 아니구나 → 그럼 메서드 이름 쿼리겠지" 로 넘어간다   ★ 함정
     *      대부분의 리포지토리 메서드는 실제로 구현체 없이 이름으로 처리되므로
     *      "구현체가 없다" 는 것 자체는 정상적인 상황이기 때문이다.
     * ⑥ "searchProducts" 를 파싱한다. search 는 조회 접두사로 인식된다
     * ⑦ 그 뒤의 "Products" 를 Product 엔티티의 프로퍼티로 해석하려 한다
     * ⑧ Product 에 그런 프로퍼티가 없다 → PropertyReferenceException
     * ⑨ QueryCreationException → BeanCreationException → APPLICATION FAILED TO START
     * </pre>
     *
     * <p><b>핵심</b>: 에러 메시지는 ⑧ 을 말하는데 원인은 ④ 에 있습니다. 네 단계 떨어져 있습니다.
     * 그래서 메시지만 보면 "Product 엔티티에 searchProducts 필드를 추가해야 하나" 같은
     * 엉뚱한 방향으로 갑니다.
     *
     * <p><b>실무 판별법</b>: {@code No property 'xxx' found for type 'Entity'} 를 만났을 때
     * {@code xxx} 가 {@code search~}, {@code ~ByCondition} 처럼
     * <b>메서드 이름 쿼리로 만들 리 없는 형태</b>라면 구현체 미발견을 먼저 의심하십시오.
     * 확인 순서는 ① 클래스 이름 접미사 ② 패키지 위치 ③ implements 여부 ④ 주입 방식입니다.
     */
    @Test
    @DisplayName("정답 2. Impl 접미사 오타의 에러 인과")
    void solution2() {
        System.out.println("[정답 2] 인과: 구현체 미발견(④) → 메서드 이름 쿼리로 오인(⑤) "
                + "→ 'Products' 를 Product 의 프로퍼티로 파싱 시도(⑦) "
                + "→ PropertyReferenceException(⑧) → 기동 실패(⑨)");
        System.out.println("         에러 메시지는 ⑧ 을 말하지만 고쳐야 할 곳은 ④ 입니다.");
    }

    // =================================================================
    // 문제 3
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <pre>
     * &#64;Configuration
     * &#64;EnableJpaRepositories(
     *         basePackages = "com.example.shop.repository",
     *         repositoryImplementationPostfix = "Querydsl")   // 기본값은 "Impl"
     * public class JpaConfig {
     * }
     * </pre>
     *
     * <p>이렇게 하면 {@code ProductRepositoryQuerydsl} 이 커스텀 구현으로 인식됩니다.
     *
     * <p><b>권장하지 않는 이유</b>
     *
     * <p>Spring Data 를 아는 사람은 누구나 {@code ~Impl} 을 기대합니다.
     * 공식 문서, 블로그, 스택오버플로 답변, AI 도구의 제안, 사내 다른 프로젝트가 전부 그 전제로 쓰여 있습니다.
     * 접미사를 바꿔 놓으면 그 지식이 전부 어긋납니다.
     * 새로 합류한 사람이 관례대로 {@code ~Impl} 로 클래스를 만들면
     * 그 클래스는 <b>인식되지 않고</b>, 정답 2의 에러가 납니다.
     * 그리고 그 에러를 검색하면 나오는 해결책은 전부 "{@code Impl} 로 이름을 맞추라" 입니다.
     * 이미 {@code Impl} 인데 안 되는 상황이므로 <b>검색으로는 답이 안 나옵니다.</b>
     * 설정 파일을 열어 보기 전까지 원인을 알 수 없고, 그 설정이 있다는 사실 자체를 모르므로
     * 열어 볼 생각도 하지 못합니다.
     * 얻는 것은 이름 취향이고 잃는 것은 팀 전체의 디버깅 시간입니다.
     * 표준을 벗어난 대가는 대체로 이런 식으로 청구됩니다.
     */
    @Test
    @DisplayName("정답 3. repositoryImplementationPostfix 와 그 비용")
    void solution3() {
        System.out.println("[정답 3] @EnableJpaRepositories(repositoryImplementationPostfix = \"Querydsl\")");
        System.out.println("         비권장 — 관례대로 ~Impl 을 만든 사람이 겪는 에러를 "
                + "검색으로 해결할 수 없게 됩니다.");
    }

    // =================================================================
    // 문제 4
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>{@code PageableExecutionUtils.getPage(content, pageable, countSupplier)} 는
     * count 를 <b>람다로 넘겨 받아 필요할 때만</b> 실행합니다.
     * {@code new PageImpl<>(content, pageable, total)} 은 total 을 이미 계산한 값으로 받으므로
     * 무조건 count 쿼리가 나갑니다. 이 차이가 전부입니다.
     *
     * <p>생략 조건
     * <pre>
     * 첫 페이지이고 content.size() &lt; pageSize   → total = content.size()
     * 마지막 페이지 (content.size() &lt; pageSize) → total = offset + content.size()
     * 그 외                                       → count 실행
     * </pre>
     *
     * <p><b>조건 A (count 실행)</b>: ON_SALE 상품 28개를 pageSize=10 으로 조회하면
     * 첫 페이지가 10건으로 꽉 차므로 전체를 알 수 없습니다. count 가 나갑니다.
     * <pre>
     * select p1_0.product_id, ... from products p1_0 join categories c1_0 on ... limit ?, ?
     * select count(p1_0.product_id) from products p1_0 join categories c1_0 on ...
     * </pre>
     *
     * <p><b>조건 B (count 생략)</b>: HIDDEN 상품은 몇 개 안 됩니다.
     * pageSize=10 으로 첫 페이지를 요청했는데 그보다 적게 오면
     * "전체가 이만큼" 이라는 결론이 나므로 count 를 실행하지 않습니다.
     * <pre>
     * select p1_0.product_id, ... from products p1_0 join categories c1_0 on ... limit ?, ?
     * (count 쿼리 없음)
     * </pre>
     *
     * <p>검색 API 는 대부분 결과가 적으므로 실측 효과가 큽니다. 쿼리가 절반이 됩니다.
     *
     * <p><b>주의</b>: count 쿼리에서 조인을 빼는 최적화는
     * {@code where} 에 그 조인 대상이 등장하지 않을 때만 가능합니다.
     * 여기서는 조건이 전부 {@code product} 의 컬럼이므로 뺄 수 있지만,
     * 조건에 따라 갈리면 <b>조인을 유지하는 쪽이 안전합니다.</b>
     * 틀린 총 건수는 마지막 페이지가 빈 화면으로 나오는 식으로 드러납니다.
     */
    @Test
    @DisplayName("정답 4. count 쿼리 실행 / 생략")
    void solution4() {
        ProductRepositoryImpl impl = new ProductRepositoryImpl(queryFactory);

        // 조건 A — 결과가 많음 → count 실행
        Page<ProductSearchDto> a = impl.searchProducts(
                new ProductSearchCond(null, ProductStatus.ON_SALE, null, null),
                PageRequest.of(0, 10));
        System.out.println("[정답 4-A] total=" + a.getTotalElements()
                + " content=" + a.getContent().size() + " → count 쿼리가 로그에 있습니다");

        // 조건 B — 첫 페이지가 덜 참 → count 생략
        Page<ProductSearchDto> b = impl.searchProducts(
                new ProductSearchCond(null, ProductStatus.HIDDEN, null, null),
                PageRequest.of(0, 10));
        System.out.println("[정답 4-B] total=" + b.getTotalElements()
                + " content=" + b.getContent().size() + " → count 쿼리가 로그에 <없습니다>");
    }

    // =================================================================
    // 문제 5
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>{@code QuerydslPredicateExecutor} 로는 카테고리명을 결과에 포함할 수 없습니다.
     * 이유는 <b>반환 타입이 엔티티로 고정</b>이기 때문입니다.
     * <pre>
     * Page&lt;Product&gt; findAll(Predicate predicate, Pageable pageable);
     * </pre>
     * 이 시그니처는 우리가 선언한 것이 아니라 상속받은 것입니다.
     * {@code Projections} 도 {@code @QueryProjection} 도 끼워 넣을 자리가 없습니다.
     * 반환 타입을 바꾸려면 그 메서드를 우리가 선언해야 하고,
     * 그 순간 커스텀 리포지토리가 됩니다.
     *
     * <p><b>근거 SQL</b> — 조건에 연관을 쓰면 묵시적 조인이 발생합니다.
     * <pre>
     * select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
     *        p1_0.name, p1_0.price, p1_0.status, p1_0.stock
     * from products p1_0
     * join categories c1_0 on c1_0.category_id=p1_0.category_id
     * where c1_0.name=?
     * </pre>
     * 조인은 <b>일어났습니다.</b> 그런데 select 절에는 {@code p1_0.*} 만 있습니다.
     * 조인은 조건 평가에만 쓰이고 결과에는 반영되지 않습니다.
     * 그래서 카테고리명이 필요하면 지연 로딩으로 다시 꺼내야 하고, 1+N 이 됩니다.
     *
     * <p>그리고 그 조인은 <b>inner join</b> 입니다.
     * left join 이 필요해도 지정할 수단이 없습니다.
     * {@code category} 가 nullable 이었다면 이 쿼리는 조용히 행을 누락시킵니다.
     *
     * <p><b>정리 — 4가지 한계</b>
     * <pre>
     * ① 조인 제어 불가 (묵시적 조인만, inner 고정)
     * ② fetch join 불가 → 1+N 을 막을 수 없음 (@EntityGraph 붙일 자리도 없음)
     * ③ DTO 프로젝션 불가 → 반환 타입이 엔티티 고정
     * ④ Predicate 를 리포지토리 밖에서 만들게 되어 QueryDSL 의존이 서비스/컨트롤러까지 번짐
     * </pre>
     *
     * <p>단일 엔티티의 단순 조건 검색에는 코드 0줄이라는 장점이 정당합니다.
     * 요구사항이 커지면 미련 없이 커스텀 리포지토리로 옮기십시오. 두 방식은 공존할 수 있습니다.
     */
    @Test
    @DisplayName("정답 5. QuerydslPredicateExecutor 의 한계")
    void solution5() {
        List<Product> products = queryFactory
                .selectFrom(product)
                .where(product.category.name.eq("노트북"))   // 묵시적 조인
                .fetch();
        System.out.println("[정답 5] 묵시적 조인 결과 = " + products.size() + "건");
        System.out.println("         select 절에는 p1_0.* 만 있습니다. 카테고리명은 없습니다.");

        int n = 0;
        for (Product p : products) {
            p.getCategory().getName();   // 지연 로딩 → 추가 SELECT
            n++;
        }
        System.out.println("[정답 5] " + n + "건 순회 → 카테고리 SELECT 가 추가로 나갑니다 (1+N).");
    }

    // =================================================================
    // 문제 6
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>핵심은 {@code bindings.excludeUnlistedProperties(true)} 한 줄입니다.
     * 이 줄이 정책을 <b>"나열하지 않은 것은 전부 금지"</b> 로 뒤집습니다.
     *
     * <p>이 줄이 <b>없으면</b> {@code including(...)} 은
     * "이 필드들은 특별히 이렇게 처리하라" 는 의미일 뿐입니다.
     * 나머지 필드는 여전히 기본 규칙(equals)으로 <b>열려 있습니다.</b>
     * 즉 {@code ?cost=120000} 이 그대로 {@code where p1_0.cost=?} 가 됩니다.
     * "허용 목록을 썼으니 안전하겠지" 라고 착각하기 딱 좋은 지점입니다.
     *
     * <p>{@code excluding(product.cost)} 는 {@code excludeUnlistedProperties(true)} 가 있으면
     * 중복이지만, <b>의도를 코드에 남기는</b> 값어치가 있습니다.
     * 나중에 누가 {@code excludeUnlistedProperties} 를 지워도 cost 는 여전히 막힙니다.
     *
     * <p>적용 후 {@code GET /products?cost=120000&name=노트북}
     * <pre>
     * select p1_0.product_id, ... from products p1_0
     * where p1_0.name like ? escape '!'
     * 바인딩: [1] %노트북%
     * </pre>
     * {@code cost} 가 WHERE 절에 없습니다. 에러도 나지 않습니다.
     * 필터가 무시되면 결과가 더 많이 나올 뿐이므로 <b>안전한 방향의 실패</b>입니다.
     *
     * <p><b>왜 위험한가 (막지 않았을 때)</b>
     * 원가는 값을 직접 못 봐도 <b>바이너리 서치로 알아낼 수 있습니다.</b>
     * {@code ?cost=100000} → 0건, {@code ?cost=120000} → 1건.
     * 몇 번의 요청으로 원가가, 따라서 마진율이 드러납니다.
     * 로그에는 "정상 조회" 로만 남습니다.
     * 같은 방식으로 {@code Customer.phone} 은 가입 여부 확인에,
     * {@code Employee.salary} 는 급여 유출에 쓰일 수 있습니다.
     *
     * <p><b>정렬도 막아야 합니다.</b> {@code ?sort=cost,desc} 로 정렬하면
     * 값을 못 봐도 원가 <b>순위</b>를 알 수 있습니다.
     * 조건과 정렬 중 한쪽만 막으면 다른 쪽으로 샙니다 (Step 10 의 정렬 키 화이트리스트).
     */
    interface SecuredProductRepository extends
            JpaRepository<Product, Long>,
            QuerydslPredicateExecutor<Product>,
            QuerydslBinderCustomizer<QProduct> {

        @Override
        default void customize(QuerydslBindings bindings, QProduct product) {
            bindings.excludeUnlistedProperties(true);            // ★ 없으면 전부 열립니다
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

            bindings.excluding(product.cost);                    // 의도를 코드에 남깁니다
        }
    }

    @Test
    @DisplayName("정답 6. 화이트리스트 바인딩")
    void solution6() {
        // 막지 않았을 때 무엇이 유출되는지 재현합니다.
        long leaked = queryFactory.select(product.count()).from(product)
                .where(product.cost.between(new BigDecimal("100000"), new BigDecimal("150000")))
                .fetchOne();
        System.out.println("[정답 6] cost 10만~15만 구간 상품 " + leaked + "건 "
                + "— 이 숫자만으로 원가 구간을 좁혀 나갈 수 있습니다.");
        System.out.println("         excludeUnlistedProperties(true) + including(...) 으로 막으십시오.");
        System.out.println("         정렬(?sort=cost,desc)도 함께 막아야 합니다.");
    }

    // =================================================================
    // 문제 7
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p><b>@Import 없이 실행하면</b>
     * <pre>
     * org.springframework.beans.factory.UnsatisfiedDependencyException:
     *   Error creating bean with name 'productRepository':
     *   Unsatisfied dependency expressed through constructor parameter 0
     *
     * Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException:
     *   No qualifying bean of type 'com.querydsl.jpa.impl.JPAQueryFactory' available
     * </pre>
     *
     * <p>{@code @DataJpaTest} 는 <b>슬라이스 테스트</b>입니다.
     * JPA 관련 빈({@code DataSource}, {@code EntityManagerFactory}, 리포지토리)만 올리고
     * 일반 {@code @Component} / {@code @Configuration} 은 스캔하지 않습니다.
     * 그래서 우리가 만든 {@code QuerydslConfig} 가 로딩되지 않고,
     * {@code JPAQueryFactory} 빈이 존재하지 않습니다.
     * <b>애플리케이션은 정상 기동하는데 테스트만 실패</b>하므로 원인을 찾는 데 시간이 걸립니다.
     *
     * <p><b>처방 ①</b>: {@code @Import(QuerydslConfig.class)} — 가장 간단하고 명확합니다.
     *
     * <p><b>처방 ②</b>: 여러 테스트에서 반복되면 {@code @TestConfiguration} 으로 공통화합니다.
     *
     * <p><b>처방 ③</b>: {@code @SpringBootTest} — 동작하지만 느립니다.
     * 리포지토리만 검증하는데 전체 컨텍스트를 올릴 이유는 없습니다.
     *
     * <p><b>통과했는데 결과가 0건인 경우</b>
     * {@code @DataJpaTest} 는 {@code @AutoConfigureTestDatabase} 를 포함하고 있어서
     * 클래스패스에 H2 가 있으면 <b>실제 MySQL 대신 H2 로 대체</b>합니다.
     * 이 코스의 예제는 {@code shop} 데이터(상품 40개, 주문 600건)를 전제로 하므로
     * H2 로 돌리면 스키마만 생성되고 데이터가 없어 전부 0건이 나옵니다.
     * 테스트는 "통과" 하는데 아무것도 검증하지 못하는 상태입니다.
     *
     * <p><b>최종 조합</b>
     * <pre>
     * &#64;DataJpaTest
     * &#64;AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
     * &#64;Import(QuerydslConfig.class)
     * class ProductRepositoryTest {
     *     &#64;Autowired ProductRepository productRepository;
     * }
     * </pre>
     */
    @Test
    @DisplayName("정답 7. @DataJpaTest 설정")
    void solution7() {
        System.out.println("[정답 7] 실패: NoSuchBeanDefinitionException — JPAQueryFactory 빈 없음");
        System.out.println("         원인: @DataJpaTest 는 @Configuration 을 스캔하지 않는 슬라이스 테스트");
        System.out.println("         처방: @Import(QuerydslConfig.class)");
        System.out.println("         결과 0건이면: @AutoConfigureTestDatabase(replace = NONE) 추가");
    }
}
