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

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;

/**
 * Step 14 — 연습문제 7문제. 이 코스의 마지막 연습문제입니다.
 *
 * 3, 4, 7번은 MySQL 콘솔에서 EXPLAIN 을 직접 실행해야 합니다.
 * p6spy 를 켜고(build.gradle 에 p6spy-spring-boot-starter 추가)
 * 완성된 SQL 을 복사하십시오. ? 가 있는 SQL 은 EXPLAIN 에 넣을 수 없습니다.
 *
 * 정답은 Solution.java.
 */
@SpringBootTest
@Transactional
class Exercise {

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

    // ────────────────────────────────────────────────────────────────
    // 문제 1. N+1 재현과 진단
    //
    // 주문 20건을 조회하고, 각 주문의 고객 등급(grade)을 출력하십시오.
    //
    // 요구사항:
    //   - Hibernate statistics 로 쿼리 개수를 세십시오
    //   - 출력된 숫자가 왜 그 값인지 설명하십시오
    //
    // 확인:
    //   콘솔에서 같은 SQL 이 몇 번 반복되는지 세어 보십시오.
    //   "where c1_0.customer_id = ?" 가 20번 나오면 안 됩니다. 왜일까요?
    //   (힌트: 서로 다른 주문이 같은 고객을 가리킬 수 있습니다. 1차 캐시)
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 1 — N+1 재현과 진단")
    void ex1() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 2. N+1 해결 3가지
    //
    // 문제 1을 다음 세 가지 방법으로 각각 해결하십시오.
    //   ① fetch join
    //   ② batch size (application.yml 의 default_batch_fetch_size)
    //   ③ DTO 직접 조회
    //
    // 요구사항:
    //   각 방법의 쿼리 개수와 생성 SQL 을 아래 표에 채우십시오.
    //
    //   | 방법        | 쿼리 개수 | select 컬럼 수 | SQL 특징           |
    //   |------------|----------|--------------|-------------------|
    //   | 해결 전     |          |              |                   |
    //   | ① fetch join|          |              |                   |
    //   | ② batch size|          |              |                   |
    //   | ③ DTO      |          |              |                   |
    //
    // 추가 질문:
    //   이 케이스(주문 → 고객, @ManyToOne)에서는 세 방법 모두 잘 동작합니다.
    //   주문 → 주문항목(@OneToMany) 이고 페이징이 필요하다면 어느 것이 불가능해집니까?
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 2 — N+1 해결 3가지 비교")
    void ex2() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 3. exists vs count
    //
    // "후기가 한 건이라도 있는 상품" 을 두 가지로 조회하십시오.
    //   ① 상관 서브쿼리 count() > 0
    //   ② JPAExpressions.selectOne()...exists()
    //
    // 요구사항:
    //   - 두 방법 모두 16건이 나와야 합니다 (상품 40개 중 후기 있는 것 16개)
    //   - 두 생성 SQL 을 나란히 적으십시오
    //
    // 확인 (MySQL 콘솔):
    //   각 SQL 을 EXPLAIN 에 넣고 **서브쿼리 행(id=2)의 rows 와 Extra** 를 비교하십시오.
    //   한쪽에 FirstMatch 가 뜹니다. 그것이 무슨 뜻인지 설명하십시오.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 3 — exists vs count")
    void ex3() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 4. contains vs startsWith
    //
    // 상품명에 "노트"가 들어가는 상품을 두 가지로 조회하십시오.
    //   ① product.name.contains("노트")
    //   ② product.name.startsWith("노트")
    //
    // 요구사항:
    //   - 두 생성 SQL 을 적고, 바인딩 파라미터 값의 차이를 확인하십시오
    //   - 결과 건수가 다른 이유를 설명하십시오
    //
    // 확인 (MySQL 콘솔):
    //   products.name 에 인덱스를 임시로 만들고 두 SQL 의 EXPLAIN type 을 비교하십시오.
    //     ALTER TABLE products ADD INDEX idx_products_name (name);
    //   실습이 끝나면 되돌리십시오.
    //     ALTER TABLE products DROP INDEX idx_products_name;
    //
    //   왜 같은 like 인데 계획이 다른지 한 문장으로 설명하십시오.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 4 — contains vs startsWith 와 인덱스")
    void ex4() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 5. ★ 최종 프로젝트 — 상품 검색 (서브쿼리 버전)
    //
    // 14-9 의 상품 검색을 **서브쿼리 버전(A)** 으로 직접 구현하십시오.
    //
    // 요구사항:
    //   조건 (모두 선택적. null 이면 무시)
    //     - keyword     : 상품명 부분 일치
    //     - categoryId  : 그 카테고리 + 하위 카테고리
    //     - minPrice / maxPrice
    //     - inStockOnly : true 면 stock > 0
    //     - statuses    : 다중 선택
    //   정렬 (화이트리스트)
    //     - "price" | "created" | "name", asc/desc
    //     - 평점순은 서브쿼리 버전에서 불가능합니다. 화이트리스트에 넣지 마십시오.
    //   페이징
    //     - Pageable. count 쿼리는 분리할 것
    //   응답
    //     - 상품ID, 상품명, 가격, 재고, 상태, 카테고리명, 평균 평점, 후기 수
    //
    // 확인:
    //   생성 SQL 두 개(콘텐츠 + count)를 확인하십시오.
    //   count 쿼리에 join 과 서브쿼리가 없어야 합니다. 있다면 왜 있는지 찾으십시오.
    // ────────────────────────────────────────────────────────────────
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

    @Test
    @DisplayName("문제 5 — 상품 검색 (서브쿼리 버전)")
    void ex5() {
        SearchCond cond = new SearchCond(
                null, null, new BigDecimal("100000"), null,
                true, List.of(ProductStatus.ON_SALE), "price", "desc");
        Pageable pageable = PageRequest.of(0, 10);

        // 여기에 작성:

    }

    /** 동적 조건 메서드들을 여기에 작성하십시오. null 을 반환하면 where 에서 무시됩니다. */
    private BooleanExpression keywordContains(String keyword) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression categoryIn(Long categoryId) {
        // 여기에 작성: 선택한 카테고리 + 그 하위 카테고리 (Step 07 의 서브쿼리)
        return null;
    }

    private BooleanExpression priceBetween(BigDecimal min, BigDecimal max) {
        // 여기에 작성: min 만, max 만, 둘 다, 둘 다 null 인 4가지 경우를 모두 처리
        return null;
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression statusIn(List<ProductStatus> statuses) {
        // 여기에 작성:
        return null;
    }

    private OrderSpecifier<?> toOrder(String sortKey, String direction) {
        // 여기에 작성: Map 화이트리스트 (Step 10)
        return product.id.desc();
    }

    // ────────────────────────────────────────────────────────────────
    // 문제 6. 평균 평점이 null 인 상품 세기
    //
    // 문제 5의 검색 결과에서 **평균 평점이 null 인 상품이 몇 개**인지 세십시오.
    //
    // 요구사항:
    //   - 검색 조건 없이(전 상품) 세면 24가 나와야 합니다
    //   - 문제 5의 조건(가격 10만 이상, 재고 있음, ON_SALE)을 걸면 24가 아닙니다
    //
    // 질문:
    //   1) 그 숫자가 24가 아닌 이유를 설명하십시오
    //   2) avg() 는 null 을 반환하는데 count() 는 0 을 반환합니다. 왜 다릅니까?
    //   3) 프론트가 null 을 못 받는다면 어디서 막는 것이 좋습니까?
    //      (SQL 의 coalesce / DTO 의 compact constructor / 컨트롤러)
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 6 — 평균 평점 null 세기")
    void ex6() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 7. EXPLAIN 으로 검증하고 인덱스 추가
    //
    // 문제 5의 콘텐츠 쿼리를 EXPLAIN 에 넣고, 인덱스를 하나 추가해 개선하십시오.
    //
    // 절차:
    //   1) p6spy 로그에서 완성된 SQL 을 복사합니다
    //   2) EXPLAIN 을 걸어 type / rows / Extra 를 기록합니다
    //   3) SHOW INDEX FROM products; 로 현재 인덱스를 확인합니다
    //   4) 선택도를 조사합니다:
    //        SELECT COUNT(*) total, COUNT(DISTINCT category_id) cat,
    //               COUNT(DISTINCT status) st, COUNT(DISTINCT price) pr,
    //               SUM(stock > 0) in_stock
    //        FROM products;
    //   5) 인덱스를 추가하고 EXPLAIN 을 다시 겁니다
    //   6) 실습이 끝나면 인덱스를 삭제합니다
    //
    // 표로 정리하십시오:
    //   | 항목  | 인덱스 전 | 인덱스 후 |
    //   |------|----------|----------|
    //   | type |          |          |
    //   | key  |          |          |
    //   | rows |          |          |
    //   | Extra|          |          |
    //
    // 질문:
    //   1) 어느 컬럼으로 인덱스를 만들었고 왜 그 컬럼입니까?
    //   2) Using filesort 가 남아 있다면 왜 인덱스로 없앨 수 없습니까?
    //   3) 새 인덱스를 추가하는 것과 기존 idx_products_category 를 확장하는 것 중
    //      어느 쪽이 낫습니까? 이유는?
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 7 — EXPLAIN 검증과 인덱스 추가")
    void ex7() {
        // 여기에 작성: 쿼리를 실행해 p6spy 로그에서 SQL 을 복사하십시오.
        //             나머지는 MySQL 콘솔 작업입니다.

    }
}
