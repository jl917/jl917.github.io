package com.example.shop.step12;

import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.example.shop.entity.QProduct;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QProduct.product;

/**
 * Step 12 — Spring Data JPA 통합 : 연습문제 7문제.
 *
 * <p>규칙
 * <ul>
 *   <li>2·3·5번은 <b>설명을 쓰는</b> 문제입니다. 코드만 채우고 넘어가지 마십시오.</li>
 *   <li>2번은 실제로 애플리케이션을 띄워 기동 실패 로그를 봐야 합니다.</li>
 *   <li>중첩 타입으로 연습해도 되지만, <b>실제 프로젝트에서는 별도 파일</b>로 만드십시오.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // -----------------------------------------------------------------
    // 문제 1. ProductRepository 3층 구조를 만드십시오.
    //
    // 요구사항
    //   - ProductSearchCond(String name, ProductStatus status,
    //                       BigDecimal minPrice, BigDecimal maxPrice) record
    //   - ProductSearchDto(Long productId, String name, BigDecimal price,
    //                      Integer stock, String categoryName) record
    //   - ProductRepositoryCustom 인터페이스에
    //       Page<ProductSearchDto> searchProducts(ProductSearchCond cond, Pageable pageable)
    //   - ProductRepositoryImpl 에서 QueryDSL 로 구현
    //       · category 를 조인해 카테고리명을 포함할 것
    //       · null 조건은 무시할 것 (조건 메서드 분리)
    //       · JPAQueryFactory 는 생성자 주입
    //   - ProductRepository extends JpaRepository<Product, Long>, ProductRepositoryCustom
    //
    // 생성 SQL 이 다음 형태인지 확인할 것
    //   select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock, c1_0.name
    //   from products p1_0 join categories c1_0 on c1_0.category_id=p1_0.category_id
    //   where p1_0.name like ? escape '!' and p1_0.status=? and p1_0.price>=?
    //   order by ... limit ?, ?
    // -----------------------------------------------------------------

    // 여기에 record / 인터페이스 / 구현체를 작성:


    @Test
    @DisplayName("문제 1. 커스텀 리포지토리 3층 구조")
    void exercise1() {
        // 여기에 작성: 구현체를 직접 생성해 searchProducts 를 호출하고 결과와 SQL 을 확인

    }

    // -----------------------------------------------------------------
    // 문제 2. 1번의 구현 클래스 이름을 ProductRepositoryQuerydsl 로 바꾸고
    //         애플리케이션을 띄우십시오.
    //
    // 요구사항
    //   - 기동 로그의 예외 3단계를 그대로 옮겨 적을 것
    //       BeanCreationException → QueryCreationException → PropertyReferenceException
    //   - "No property 'searchProducts' found for type 'Product'" 라는 메시지가
    //     왜 나오는지 인과를 단계별로 설명할 것
    //   - 힌트: Spring Data 는 구현체를 못 찾으면 "에러" 를 내지 않습니다.
    //           다음 전략으로 조용히 넘어갑니다. 그 전략이 무엇입니까?
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2. Impl 접미사 오타의 에러 인과 설명")
    void exercise2() {
        // 여기에 작성: 설명을 System.out.println 으로 출력

    }

    // -----------------------------------------------------------------
    // 문제 3. 2번의 ProductRepositoryQuerydsl 이 동작하도록
    //         @EnableJpaRepositories 설정을 작성하십시오.
    //
    // 요구사항
    //   - 설정 클래스 코드를 주석 또는 문자열로 작성
    //   - 이 설정을 실무에서 권장하지 않는 이유를 한 문단으로 쓸 것
    //   - 힌트: 새로 합류한 사람이 12-3-2 의 에러를 만났을 때 어떻게 됩니까?
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 3. repositoryImplementationPostfix 설정과 그 비용")
    void exercise3() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 4. 1번의 searchProducts 에 PageableExecutionUtils 를 적용하고,
    //         count 쿼리가 나가는 경우와 안 나가는 경우를 각각 재현하십시오.
    //
    // 요구사항
    //   - 조건 A: 결과가 pageSize 보다 많은 경우 → count 쿼리가 나감
    //   - 조건 B: 첫 페이지에서 결과가 pageSize 보다 적은 경우 → count 쿼리가 안 나감
    //   - 각각의 SQL 로그와 Page 의 totalElements 를 출력할 것
    //   - 힌트: HIDDEN 상태 상품은 몇 개입니까?
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 4. PageableExecutionUtils 의 count 생략")
    void exercise4() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 5. ProductRepository 에 QuerydslPredicateExecutor<Product> 를 얹고
    //         findAll(predicate, pageable) 로 1번과 같은 검색을 시도하십시오.
    //
    // 요구사항
    //   - 카테고리명을 결과에 포함할 수 없는 이유를 설명할 것
    //   - 생성 SQL 로 근거를 제시할 것 (묵시적 조인이 어떻게 나오는지)
    //   - 카테고리를 지연 로딩으로 꺼냈을 때 SELECT 가 몇 번 나가는지 세어 볼 것
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5. QuerydslPredicateExecutor 의 한계 확인")
    void exercise5() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 6. QuerydslBinderCustomizer 를 구현해
    //         name / status / price 만 필터링 가능하게 하고 cost 를 차단하십시오.
    //
    // 요구사항
    //   - excludeUnlistedProperties(true) 를 반드시 쓸 것
    //   - name 은 contains, status 는 eq, price 는 (하나면 goe / 둘이면 between)
    //   - ?cost=120000&name=노트북 요청 시
    //     WHERE 절에 cost 가 없다는 것을 SQL 로그로 확인할 것
    //   - excludeUnlistedProperties(true) 를 뺐을 때 무엇이 달라지는지도 서술할 것
    // -----------------------------------------------------------------

    // 여기에 리포지토리 인터페이스 + customize 구현을 작성:


    @Test
    @DisplayName("문제 6. 화이트리스트 바인딩")
    void exercise6() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 7. @DataJpaTest 로 1번 리포지토리를 테스트하십시오.
    //
    // 요구사항
    //   - 먼저 @Import 없이 실행해 실패 메시지(예외 클래스명 포함)를 적을 것
    //   - 처방을 적용해 통과시킬 것
    //   - 통과했는데 결과가 0건이라면 그 원인도 해결할 것
    //   - 최종 애노테이션 조합을 적을 것
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 7. @DataJpaTest 설정")
    void exercise7() {
        // 여기에 작성:

    }
}
