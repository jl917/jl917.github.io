package com.example.shop.step03;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.NonUniqueResultException;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Wildcard;
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
import java.util.Optional;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 03 — 연습문제 정답과 해설.
 *
 * 문제를 먼저 풀어 본 뒤에 여십시오.
 * 각 정답에는 "왜 그 답인가" 를 설명하는 긴 주석이 붙어 있습니다.
 */
@SpringBootTest
@Transactional
@SuppressWarnings("deprecation")
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 정답 1 — 특정 컬럼만 조회
    // =================================================================
    //
    // 확인 1) select 절 컬럼 개수 = 2  (product_name, price 두 개)
    // 확인 2) selectFrom(product) 였다면 = 9
    //         (product_id, category_id, name, price, cost, stock,
    //          status, attrs, created_at)
    //
    // 두 SQL 을 나란히 놓고 보십시오.
    //
    //   select(product.name, product.price)
    //     → select p1_0.name, p1_0.price
    //       from products p1_0
    //       where p1_0.price >= ?
    //
    //   selectFrom(product)
    //     → select p1_0.product_id, p1_0.attrs, p1_0.category_id,
    //              p1_0.cost, p1_0.created_at, p1_0.name, p1_0.price,
    //              p1_0.status, p1_0.stock
    //       from products p1_0
    //       where p1_0.price >= ?
    //
    // 여기서 중요한 것은 attrs 입니다. products.attrs 는 JSON 컬럼이고,
    // 상품마다 크기가 제각각입니다. "이름과 가격만 화면에 뿌리는" 목록 조회에서
    // selectFrom 을 쓰면 40개 상품의 JSON 전체를 매번 네트워크로 끌어옵니다.
    // 40행이라 체감이 없을 뿐, 이 습관 그대로 수만 행 테이블에 옮기면 그대로 장애가 됩니다.
    //
    // 다만 반대편도 기억하십시오. 컬럼만 뽑으면 엔티티가 아니므로
    // 영속성 컨텍스트에 올라가지 않고, 값을 바꿔도 변경 감지가 동작하지 않습니다.
    // "읽기 전용은 컬럼/DTO, 수정 대상은 엔티티" 가 기준입니다.
    // =================================================================
    @Test
    @DisplayName("정답 1 — 특정 컬럼만 조회")
    void 정답1() {
        List<Tuple> rows = queryFactory
                .select(product.name, product.price)
                .from(product)
                .where(product.price.goe(new BigDecimal("100000")))
                .fetch();

        rows.forEach(t -> System.out.println(
                t.get(product.name) + " / " + t.get(product.price)));
        assertThat(rows).isNotEmpty();
    }

    // =================================================================
    // 정답 2 — 0건이면 null
    // =================================================================
    //
    // 확인) SQL 은 정상적으로 나갔는가? = 예.
    //
    // 이 문제의 핵심은 "아무 일도 일어나지 않았다" 가 아니라는 것입니다.
    // SQL 은 정상 실행됐고, DB 는 "조건에 맞는 행이 0개" 라고 정확히 답했습니다.
    // 에러도 경고도 없습니다. QueryDSL 은 그 0건을 null 로 번역했을 뿐입니다.
    //
    // 내부적으로는 이렇게 동작합니다.
    //   Hibernate 의 getSingleResult() 는 0건이면 NoResultException 을 던집니다.
    //   QueryDSL 의 AbstractJPAQuery.fetchOne() 은 그 예외를 catch 해서 null 로 바꿉니다.
    //
    // 즉 "예외를 삼켜 null 로 만드는" 코드가 라이브러리 안에 이미 들어 있습니다.
    // 좋게 보면 편의이고, 나쁘게 보면 실패 신호를 지운 것입니다.
    // 그래서 3-9 의 Optional 관례가 필요합니다. 정답 6 을 보십시오.
    // =================================================================
    @Test
    @DisplayName("정답 2 — 0건이면 null")
    void 정답2() {
        Customer found = queryFactory
                .selectFrom(customer)
                .where(customer.email.eq("ghost@example.com"))
                .fetchOne();

        assertThat(found).isNull();
    }

    // =================================================================
    // 정답 3 — fetchOne 예외 vs fetchFirst limit  (이 스텝에서 가장 중요한 정답)
    // =================================================================
    //
    // 확인 1) (a) fetchOne 의 SQL 에 limit 이 있는가 = 아니오
    // 확인 2) (b) fetchFirst 의 SQL 에 limit 이 있는가 = 예 (limit ?, 값 1)
    // 확인 3) (a) 에서 DB 가 돌려준 행 수 = 8건
    //
    // 두 SQL 을 나란히 놓습니다.
    //
    //   fetchOne()
    //     select c1_0.customer_id, ... from customers c1_0 where c1_0.grade = ?
    //     바인딩: [1] Grade.SILVER
    //
    //   fetchFirst()
    //     select c1_0.customer_id, ... from customers c1_0 where c1_0.grade = ?
    //     limit ?
    //     바인딩: [1] Grade.SILVER  [2] 1
    //
    // 여기서 반드시 짚어야 할 것이 있습니다.
    //
    //   ** DB 는 두 경우 모두 정상 동작했습니다. **
    //
    // (a) 에서 MySQL 은 SILVER 고객 8건을 아무 불평 없이 돌려줬습니다.
    // 예외를 던진 것은 DB 도 JDBC 도 아니고, 결과를 받은 뒤 개수를 센 자바 코드입니다.
    // 예외 메시지가 그 사실을 그대로 말해 줍니다.
    //
    //   com.querydsl.core.NonUniqueResultException: Only one result is allowed for fetchOne calls
    //   Caused by: jakarta.persistence.NonUniqueResultException:
    //             Query did not return a unique result: 8 results were returned
    //
    // "8 results were returned" — 실제 건수가 찍힙니다. 운영 장애에서 이 숫자가
    // 결정적인 단서가 됩니다. "단건인 줄 알았는데 8건이었다" 는 곧
    // "UNIQUE 제약이 없거나, 조건이 부족하거나, 데이터가 중복됐다" 는 뜻이기 때문입니다.
    //
    // 그리고 예외 이름이 두 패키지에 똑같이 존재한다는 점을 조심하십시오.
    //   com.querydsl.core.NonUniqueResultException      ← 잡아야 할 것
    //   jakarta.persistence.NonUniqueResultException    ← 원인 (Caused by)
    // import 를 잘못하면 catch 가 걸리지 않고 테스트가 실패합니다.
    //
    // 마지막으로 선택 기준입니다.
    //   "2건이 나오면 데이터가 잘못된 것"  → fetchOne()  (터지는 게 낫다)
    //   "2건이 정상이고 하나만 필요"       → fetchFirst() (+ 반드시 orderBy)
    // =================================================================
    @Test
    @DisplayName("정답 3 — fetchOne 예외 vs fetchFirst limit")
    void 정답3() {
        // (a) SILVER 8명 → 예외
        assertThatThrownBy(() ->
                queryFactory
                        .selectFrom(customer)
                        .where(customer.grade.eq(Grade.SILVER))
                        .fetchOne()
        )
                .isInstanceOf(NonUniqueResultException.class)
                .hasMessageContaining("Only one result is allowed");

        // (b) 같은 조건 + fetchFirst → limit 1 이 붙어 예외 없음
        Customer first = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.SILVER))
                .fetchFirst();

        assertThat(first).isNotNull();
        assertThat(first.getGrade()).isEqualTo(Grade.SILVER);
    }

    // =================================================================
    // 정답 4 — fetchCount vs 직접 작성
    // =================================================================
    //
    // 확인) 두 SQL 이 같은가 = 예. 이 쿼리에서는 완전히 같습니다.
    //
    //   fetchCount()
    //     select count(p1_0.product_id) from products p1_0 where p1_0.status = ?
    //
    //   select(product.count())
    //     select count(p1_0.product_id) from products p1_0 where p1_0.status = ?
    //
    // 그럼 왜 하나는 deprecated 이고 하나는 권장일까요.
    // 답은 "이 쿼리에서는" 이라는 단서에 있습니다.
    //
    // fetchCount() 는 여러분이 쓴 쿼리를 받아 select 절만 기계적으로 count 로
    // 갈아 끼웁니다. from, join, group by, having, distinct 는 그대로 둡니다.
    // 단순 쿼리에서는 맞는 변환이고, 아래 셋에서 무너집니다.
    //
    //  (1) group by 가 있을 때
    //      "도시별 고객 수" 쿼리(6행)의 select 절만 count 로 바꾸면
    //      "그룹이 몇 개냐" 가 아니라 "각 그룹에 몇 행이냐" 를 그룹마다 돌려줍니다.
    //      단건을 기대한 자리에서 그대로 NonUniqueResultException 이 납니다.
    //
    //  (2) distinct 가 있을 때
    //      select distinct c1_0.city 는 6행입니다.
    //      select 절만 갈아 끼우면 select count(c1_0.city) 가 되어 distinct 가 사라지고
    //      30 이 나옵니다. 예외도 안 나고 숫자만 조용히 틀립니다. 가장 위험한 경우입니다.
    //
    //  (3) 컬렉션 조인 / fetch join 이 있을 때
    //      일대다를 조인하면 행이 뻥튀기됩니다. 그 위에서 세면 "주문 수" 가 아니라
    //      "주문 상세 수" 가 나옵니다. fetch join 이 섞이면 Hibernate 6 에서 예외가 납니다.
    //
    // 직접 작성하면 이 셋을 전부 여러분이 결정합니다. 게다가
    // "본 쿼리에는 필요하지만 count 에는 필요 없는 조인" 을 뺄 수 있습니다.
    // 실무에서 체감되는 이득은 대부분 여기서 나옵니다.
    //
    // fetchResults() 도 같은 이유로 deprecated 이며, 추가로 count 쿼리와 본 쿼리를
    // 항상 두 번 날린다는 문제가 있습니다. count 가 필요 없는 상황에서도 무조건 셉니다.
    // 그 낭비를 없애는 것이 Step 09 의 PageableExecutionUtils 입니다.
    //
    // 마이그레이션 요령: fetchCount() 를 전부 한꺼번에 바꾸려 하지 말고,
    // group by / distinct / 컬렉션 조인이 들어간 쿼리부터 찾아 고치십시오.
    // 나머지는 지금 당장 틀린 값을 내지는 않습니다.
    // =================================================================
    @Test
    @DisplayName("정답 4 — fetchCount vs 직접 작성")
    void 정답4() {
        // (a) deprecated
        long byDeprecated = queryFactory
                .selectFrom(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .fetchCount();

        // (b) 권장
        Long byManual = queryFactory
                .select(product.count())
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .fetchOne();

        System.out.println("fetchCount() = " + byDeprecated);
        System.out.println("직접 작성    = " + byManual);
        assertThat(byManual).isEqualTo(byDeprecated);
    }

    // =================================================================
    // 정답 5 — distinct 유무 비교
    // =================================================================
    //
    // 확인 1) distinct 있음 = 6건  (서울, 부산, 대구, 인천, 광주, 대전)
    // 확인 2) distinct 없음 = 600건 (주문 1건마다 도시가 하나씩)
    //
    //   select distinct o1_0.shipping_city from orders o1_0     → 6행
    //   select o1_0.shipping_city from orders o1_0              → 600행
    //
    // 차이가 100배입니다. 여기서 배울 것은 distinct 문법이 아니라
    // "select 절에 무엇을 넣느냐가 곧 네트워크로 흐르는 데이터 양" 이라는 감각입니다.
    //
    // 한 가지 경고를 덧붙입니다. 이 문제의 distinct 는 "단일 컬럼 중복 제거" 입니다.
    // 엔티티를 조회하면서 컬렉션을 조인한 뒤 붙이는 distinct 는 완전히 다른 이야기이며,
    // SQL 의 distinct 와 JPA 의 엔티티 중복 제거가 각각 따로 동작합니다.
    // Hibernate 6 부터는 후자가 기본 동작이라 distinct() 없이도 엔티티가 중복되지 않습니다.
    // 그 주제는 Step 06 에서 다룹니다. 지금 두 가지를 섞어 이해하지 마십시오.
    // =================================================================
    @Test
    @DisplayName("정답 5 — distinct 유무 비교")
    void 정답5() {
        List<String> withDistinct = queryFactory
                .select(order.shippingCity)
                .distinct()
                .from(order)
                .fetch();

        List<String> withoutDistinct = queryFactory
                .select(order.shippingCity)
                .from(order)
                .fetch();

        System.out.println("distinct 있음 = " + withDistinct.size());
        System.out.println("distinct 없음 = " + withoutDistinct.size());

        assertThat(withDistinct).hasSize(6);
        assertThat(withoutDistinct).hasSize(600);
    }

    // =================================================================
    // 정답 6 — Optional 반환
    // =================================================================
    //
    // 확인) null 반환 대신 Optional 을 쓰면 무엇이 달라지는가
    //       = "없을 때 무엇을 할지" 를 호출부가 결정하도록 컴파일러가 강제한다.
    //
    // 코드는 세 줄입니다. 중요한 것은 왜 이 세 줄이 관례가 됐는가입니다.
    //
    // fetchOne() 이 null 을 돌려주면, 그 null 은 아무 흔적도 남기지 않고
    // 호출부로 흘러갑니다. 그리고 한참 뒤에 NPE 로 터집니다.
    //
    //   [Repository]  Customer c = ... .fetchOne();   // null 발생 지점
    //   [Service]     return toDto(c);                // null 통과
    //   [Controller]  model.addAttribute(dto);        // null 통과
    //   [View/직렬화]  dto.getName()                   // ← 여기서 NPE
    //
    // 스택트레이스는 마지막 줄을 가리킵니다. 하지만 진짜 원인은 첫 줄,
    // 정확히는 "사용자가 입력한 이메일이 DB 에 없다" 는 사실입니다.
    // 발생 지점과 원인 지점이 계층 세 개만큼 떨어져 있으면,
    // 로그만 보고 원인을 찾는 데 몇 시간이 걸립니다.
    //
    // Optional 은 그 거리를 0 으로 만듭니다.
    // Optional<Customer> 를 받은 호출부는 orElseThrow / orElse / ifPresent 중
    // 무엇이든 써야 하고, 그것을 쓰는 순간 "없으면 어떡하지" 를 생각하게 됩니다.
    // 즉 Optional 의 가치는 null 을 없애는 것이 아니라
    // ** 판단을 강제하고, 실패 지점을 원인 지점으로 끌어오는 것 ** 입니다.
    //
    // 주의할 점 두 가지.
    //  - Optional 은 반환 타입에만 씁니다. 필드/파라미터/컬렉션 원소에 쓰지 않습니다.
    //  - Optional.get() 은 검사 없이 쓰면 null 과 다를 바 없습니다. orElseThrow() 를 쓰십시오.
    //
    // 그리고 List 반환에는 Optional 을 씌우지 않습니다.
    // fetch() 는 0건이면 빈 리스트를 돌려주지 null 을 돌려주지 않기 때문입니다.
    // Optional<List<T>> 는 "비어 있음" 을 표현하는 방법을 둘로 늘릴 뿐입니다.
    // =================================================================
    private Optional<Customer> findByEmail(String email) {
        return Optional.ofNullable(
                queryFactory
                        .selectFrom(customer)
                        .where(customer.email.eq(email))
                        .fetchOne()
        );
    }

    @Test
    @DisplayName("정답 6 — Optional 반환")
    void 정답6() {
        // (a) 존재하는 이메일
        Customer found = findByEmail("seojun.kim@example.com")
                .orElseThrow(CustomerNotFoundException::new);
        assertThat(found.getName()).isEqualTo("김서준");

        // (b) 없는 이메일 → 예외. NPE 가 아니라 "무엇이 없는지" 를 말하는 예외입니다.
        assertThatThrownBy(() ->
                findByEmail("ghost@example.com")
                        .orElseThrow(CustomerNotFoundException::new)
        )
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining("고객을 찾을 수 없습니다");
    }

    static class CustomerNotFoundException extends RuntimeException {
        CustomerNotFoundException() {
            super("고객을 찾을 수 없습니다");
        }
    }

    // =================================================================
    // 보너스 — Wildcard.count 와 customer.count() 의 차이
    // =================================================================
    //
    // 연습문제에는 없지만 실무에서 자주 나오는 질문입니다.
    //
    //   select(Wildcard.count)      → select count(*) from customers c1_0
    //   select(customer.count())    → select count(c1_0.customer_id) from customers c1_0
    //
    // 결과 값은 이 경우 둘 다 30 으로 같습니다. PK 는 NOT NULL 이므로
    // count(pk) 와 count(*) 가 같은 값을 냅니다.
    //
    // 달라지는 경우는 NULL 이 있는 컬럼을 셀 때입니다.
    //   select(customer.phone.count())  → select count(c1_0.phone) ...  → 27
    // customers 30명 중 phone 이 NULL 인 3명은 세지 않습니다.
    // count(컬럼) 은 "NULL 이 아닌 행" 만 센다는 SQL 의 규칙이 그대로 적용됩니다.
    // (MySQL8 코스 부록 A — NULL 완전 정복과 같은 이야기입니다.)
    //
    // 실무 선택: 전체 행 수를 셀 때는 Wildcard.count 또는 pk.count() 중
    // 팀 컨벤션을 하나로 정해 쓰십시오. 중요한 것은 "무심코 NULL 있는 컬럼을 세지 않는 것" 입니다.
    // =================================================================
    @Test
    @DisplayName("보너스 — count(*) vs count(컬럼)")
    void 보너스_카운트_차이() {
        Long star = queryFactory.select(Wildcard.count).from(customer).fetchOne();
        Long pk = queryFactory.select(customer.count()).from(customer).fetchOne();
        Long phone = queryFactory.select(customer.phone.count()).from(customer).fetchOne();

        System.out.println("count(*)        = " + star);   // 30
        System.out.println("count(pk)       = " + pk);     // 30
        System.out.println("count(phone)    = " + phone);  // 27  ← NULL 3명 제외

        assertThat(star).isEqualTo(30L);
        assertThat(pk).isEqualTo(30L);
        assertThat(phone).isEqualTo(27L);
    }
}
