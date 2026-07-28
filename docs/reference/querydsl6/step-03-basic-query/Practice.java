package com.example.shop.step03;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.querydsl.core.NonUniqueResultException;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Wildcard;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 03 — 기본 조회 : 본문 예제 전체.
 *
 * 실행 전 application.yml 에 아래가 켜져 있어야 합니다.
 *   logging.level.org.hibernate.SQL: debug
 *   logging.level.org.hibernate.orm.jdbc.bind: trace
 *
 * 이 파일의 목적은 "테스트를 통과시키는 것"이 아니라
 * "각 메서드가 만들어 내는 SQL 을 눈으로 확인하는 것" 입니다.
 * 반드시 콘솔의 hibernate.SQL 로그를 함께 보십시오.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [3-1] 쿼리의 뼈대 — select / from / where / fetch
    // =================================================================

    @Test
    @DisplayName("[3-1] selectFrom 은 select(x).from(x) 의 축약이다")
    void 쿼리의_뼈대() {
        // (1) 풀어 쓴 형태
        System.out.println("--- (1) select(customer).from(customer) ---");
        List<Customer> a = queryFactory
                .select(customer)
                .from(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        // (2) 축약 형태
        System.out.println("--- (2) selectFrom(customer) ---");
        List<Customer> b = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        // 두 SQL 로그가 글자 하나 다르지 않은 것을 위에서 확인하십시오.
        assertThat(a).hasSize(4);
        assertThat(b).hasSize(4);
        assertThat(a).extracting(Customer::getName)
                .containsExactlyInAnyOrderElementsOf(b.stream().map(Customer::getName).toList());
    }

    // =================================================================
    // [3-2] 컬럼 선택 — 엔티티 전체 vs 특정 컬럼
    // =================================================================

    @Test
    @DisplayName("[3-2] 컬럼 하나만 고르면 반환 타입이 그 컬럼 타입이 된다")
    void 컬럼_하나_선택() {
        List<String> names = queryFactory
                .select(customer.name)
                .from(customer)
                .where(customer.city.eq("서울"))
                .fetch();

        // 생성 SQL: select c1_0.name from customers c1_0 where c1_0.city = ?
        names.forEach(System.out::println);
        assertThat(names).hasSize(8);
    }

    @Test
    @DisplayName("[3-2] 컬럼을 두 개 이상 고르면 Tuple 이 된다")
    void 컬럼_여러개_선택_Tuple() {
        List<Tuple> rows = queryFactory
                .select(customer.name, customer.points)
                .from(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        // 생성 SQL: select c1_0.name, c1_0.points from customers c1_0 where c1_0.grade = ?
        for (Tuple row : rows) {
            // 꺼낼 때도 "무엇으로 select 했는지"를 그대로 지정해야 타입이 살아납니다.
            String name = row.get(customer.name);
            Integer points = row.get(customer.points);
            System.out.println(name + " / " + points);
        }
        assertThat(rows).hasSize(4);
    }

    @Test
    @DisplayName("[3-2] 엔티티 전체 조회는 매핑된 전 컬럼을 나열한다")
    void 엔티티_전체_조회() {
        List<Customer> all = queryFactory
                .selectFrom(customer)
                .fetch();

        // 생성 SQL 의 select 절에 customer_id, city, created_at, email,
        // grade, name, phone, points 가 전부 나열되는 것을 확인하십시오.
        assertThat(all).hasSize(30);
    }

    // =================================================================
    // [3-3] fetch 계열 — 정상 케이스
    // =================================================================

    @Test
    @DisplayName("[3-3] fetch() 는 List 를 돌려준다")
    void fetch_여러건() {
        List<Customer> list = queryFactory
                .selectFrom(customer)
                .where(customer.city.eq("부산"))
                .fetch();

        assertThat(list).hasSize(6);
    }

    @Test
    @DisplayName("[3-3] fetchOne() 은 단건. UNIQUE 컬럼 조회에 안전하다")
    void fetchOne_단건() {
        Customer one = queryFactory
                .selectFrom(customer)
                .where(customer.email.eq("seojun.kim@example.com"))
                .fetchOne();

        // 이 SQL 에는 limit 이 붙지 않습니다. 로그로 확인하십시오.
        assertThat(one).isNotNull();
        assertThat(one.getName()).isEqualTo("김서준");
        assertThat(one.getGrade()).isEqualTo(Grade.VIP);
    }

    // =================================================================
    // [3-4] fetchOne() 의 비대칭 — 2건이면 예외, 0건이면 null
    // =================================================================

    @Test
    @DisplayName("[3-4] fetchOne() 은 2건 이상이면 NonUniqueResultException")
    void fetchOne_두건이상이면_예외() {
        // GOLD 등급 고객은 9명입니다. 예외가 나는 것이 정상 동작인 테스트입니다.
        assertThatThrownBy(() ->
                queryFactory
                        .selectFrom(customer)
                        .where(customer.grade.eq(Grade.GOLD))
                        .fetchOne()
        )
                // 겉에서 잡히는 것은 com.querydsl.core.NonUniqueResultException 입니다.
                // jakarta.persistence 쪽을 import 하면 이 단언이 실패합니다.
                .isInstanceOf(NonUniqueResultException.class)
                .hasMessageContaining("Only one result is allowed");

        // ----- 대조: 0건이면 예외가 아니라 null -----
        Customer nobody = queryFactory
                .selectFrom(customer)
                .where(customer.email.eq("nobody@example.com"))
                .fetchOne();

        assertThat(nobody).isNull();     // 예외가 아닙니다. 조용히 null 입니다.
    }

    @Test
    @DisplayName("[3-4] 0건 null 이 먼 곳에서 NPE 로 터지는 경로")
    void null_이_NPE_로_이어지는_경로() {
        Customer c = queryFactory
                .selectFrom(customer)
                .where(customer.email.eq("nobody@example.com"))
                .fetchOne();

        // 아래 줄의 주석을 풀면 NullPointerException 이 납니다.
        // 스택트레이스는 이 줄을 가리키지만, 진짜 원인은 "그 이메일이 DB 에 없다" 입니다.
        // return c.getName();

        assertThat(c).isNull();
    }

    // =================================================================
    // [3-5] fetchFirst() = limit(1).fetchOne()
    // =================================================================

    @Test
    @DisplayName("[3-5] fetchFirst() 는 SQL 에 limit ? 를 붙인다")
    void fetchFirst_는_limit_을_붙인다() {
        Customer any = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.GOLD))
                .fetchFirst();       // 9건이지만 예외 없음

        // 생성 SQL 마지막 줄에 limit ? 가 붙은 것을 확인하십시오.
        // 3-4 의 fetchOne 로그에는 없던 줄입니다.
        assertThat(any).isNotNull();
        assertThat(any.getGrade()).isEqualTo(Grade.GOLD);
    }

    @Test
    @DisplayName("[3-5] fetchFirst() 는 거의 항상 orderBy 와 함께 써야 한다")
    void fetchFirst_는_정렬과_함께() {
        Customer top = queryFactory
                .selectFrom(customer)
                .orderBy(customer.points.desc())
                .fetchFirst();

        // 생성 SQL: ... order by c1_0.points desc limit ?
        System.out.println("포인트 1위: " + top.getName() + " / " + top.getPoints());
        assertThat(top.getName()).isEqualTo("배채영");
        assertThat(top.getPoints()).isEqualTo(52000);
    }

    // =================================================================
    // [3-6] fetchCount() / fetchResults() 가 deprecated 인 이유
    // =================================================================

    @Test
    @DisplayName("[3-6] deprecated 인 fetchCount() 의 SQL 을 확인한다")
    @SuppressWarnings("deprecation")
    void deprecated_fetchCount() {
        // ⚠️ 실무 코드에 복사하지 마십시오. SQL 을 관찰하기 위한 호출입니다.
        long total = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.GOLD))
                .fetchCount();

        // 생성 SQL: select count(c1_0.customer_id) from customers c1_0 where c1_0.grade = ?
        assertThat(total).isEqualTo(9);
    }

    @Test
    @DisplayName("[3-6] 권장 — count 쿼리를 직접 작성한다")
    void countManually() {
        Long total = queryFactory
                .select(customer.count())
                .from(customer)
                .where(customer.grade.eq(Grade.GOLD))
                .fetchOne();

        // 위 fetchCount() 와 생성 SQL 이 동일합니다.
        // 다른 것은 "누가 그 SQL 을 결정했는가" 하나입니다.
        assertThat(total).isEqualTo(9L);
    }

    @Test
    @DisplayName("[3-6] Wildcard.count 는 count(*) 를 만든다")
    void countWildcard() {
        Long total = queryFactory
                .select(Wildcard.count)
                .from(customer)
                .fetchOne();

        // 생성 SQL: select count(*) from customers c1_0
        assertThat(total).isEqualTo(30L);
    }

    // =================================================================
    // [3-7] distinct()
    // =================================================================

    @Test
    @DisplayName("[3-7] distinct() 로 중복 도시 제거")
    void distinct_적용() {
        List<String> cities = queryFactory
                .select(customer.city)
                .distinct()
                .from(customer)
                .fetch();

        // 생성 SQL: select distinct c1_0.city from customers c1_0
        System.out.println("도시: " + cities);
        assertThat(cities).hasSize(6);
    }

    @Test
    @DisplayName("[3-7] distinct() 를 빼면 30건이 그대로 실린다")
    void distinct_미적용() {
        List<String> cities = queryFactory
                .select(customer.city)
                .from(customer)
                .fetch();

        assertThat(cities).hasSize(30);
    }

    @Test
    @DisplayName("[3-7] selectDistinct 축약형")
    void selectDistinct_축약형() {
        List<String> cities = queryFactory
                .selectDistinct(customer.city)
                .from(customer)
                .fetch();

        assertThat(cities).hasSize(6);
    }

    // =================================================================
    // [3-8] limit / offset 맛보기
    // =================================================================

    @Test
    @DisplayName("[3-8] offset(0) 은 SQL 에 나타나지 않는다")
    void limit_offset_첫페이지() {
        List<Customer> page = queryFactory
                .selectFrom(customer)
                .orderBy(customer.points.desc())
                .offset(0)
                .limit(5)
                .fetch();

        // 생성 SQL: ... order by c1_0.points desc limit ?
        // offset 0 은 기본값이라 생략됩니다.
        page.forEach(c -> System.out.println(c.getName() + " " + c.getPoints()));
        assertThat(page).hasSize(5);
    }

    @Test
    @DisplayName("[3-8] offset 이 0 이 아니면 limit ?, ? 형태가 된다 (MySQL 방언)")
    void limit_offset_둘째페이지() {
        List<Customer> page = queryFactory
                .selectFrom(customer)
                .orderBy(customer.points.desc())
                .offset(5)
                .limit(5)
                .fetch();

        // 생성 SQL: ... order by c1_0.points desc limit ?, ?
        // PostgreSQL 방언이면 limit ? offset ? 로 나갑니다.
        assertThat(page).hasSize(5);
    }

    // =================================================================
    // [3-9] Optional 로 감싸기
    // =================================================================

    /** 3-9 의 관례. 단건 조회는 Optional 로 반환합니다. */
    private Optional<Customer> findByEmail(String email) {
        return Optional.ofNullable(
                queryFactory
                        .selectFrom(customer)
                        .where(customer.email.eq(email))
                        .fetchOne()
        );
    }

    @Test
    @DisplayName("[3-9] Optional 반환이 호출부에 처리를 강제한다")
    void optional_관례() {
        Customer found = findByEmail("seojun.kim@example.com")
                .orElseThrow(() -> new IllegalStateException("고객 없음"));
        assertThat(found.getName()).isEqualTo("김서준");

        assertThatThrownBy(() ->
                findByEmail("nobody@example.com")
                        .orElseThrow(() -> new IllegalStateException("고객 없음"))
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[3-9] fetch() 는 0건이어도 null 이 아니라 빈 리스트")
    void fetch_는_null_을_주지_않는다() {
        List<Customer> none = queryFactory
                .selectFrom(customer)
                .where(customer.city.eq("제주"))
                .fetch();

        assertThat(none).isNotNull();
        assertThat(none).isEmpty();
    }

    // =================================================================
    // [3-10] MySQL8 코스와 나란히
    // =================================================================

    @Test
    @DisplayName("[3-10] SQL 5개를 QueryDSL 로 옮긴다")
    void mysql8_대조() {
        // 1) SELECT * FROM customers
        List<Customer> q1 = queryFactory.selectFrom(customer).fetch();

        // 2) SELECT name, points FROM customers
        List<Tuple> q2 = queryFactory.select(customer.name, customer.points).from(customer).fetch();

        // 3) SELECT DISTINCT city FROM customers
        List<String> q3 = queryFactory.select(customer.city).distinct().from(customer).fetch();

        // 4) SELECT * FROM customers WHERE grade = 'VIP'
        //    'VIP' 오타는 SQL 에서 0건으로 드러나지만, Grade.VIPP 는 컴파일 에러입니다.
        List<Customer> q4 = queryFactory.selectFrom(customer).where(customer.grade.eq(Grade.VIP)).fetch();

        // 5) SELECT COUNT(*) FROM customers
        Long q5 = queryFactory.select(Wildcard.count).from(customer).fetchOne();

        assertThat(q1).hasSize(30);
        assertThat(q2).hasSize(30);
        assertThat(q3).hasSize(6);
        assertThat(q4).hasSize(4);
        assertThat(q5).isEqualTo(30L);
    }

    @Test
    @DisplayName("[3-10] 다른 엔티티에도 같은 규칙이 적용된다")
    void 다른_엔티티() {
        List<String> productNames = queryFactory
                .select(product.name)
                .from(product)
                .where(product.price.goe(new java.math.BigDecimal("100000")))
                .fetch();

        List<String> shippingCities = queryFactory
                .select(order.shippingCity)
                .distinct()
                .from(order)
                .fetch();

        System.out.println("10만원 이상 상품 " + productNames.size() + "개");
        System.out.println("배송 도시 " + shippingCities);
        assertThat(shippingCities).hasSize(6);
    }

    // =================================================================
    // [3-11] 함정 두 가지
    // =================================================================

    @Test
    @DisplayName("[3-11] from 을 빼먹으면 IllegalArgumentException: No sources given")
    void from_누락() {
        // 아래 주석을 풀면 컴파일은 되지만 실행 시점에 예외가 납니다.
        // 즉시 터지는 실패이므로 위험하지는 않습니다.
        //
        // queryFactory
        //         .select(customer)
        //         .where(customer.grade.eq(Grade.VIP))
        //         .fetch();

        // selectFrom 축약형을 쓰면 애초에 이 실수를 할 수 없습니다.
        List<Customer> ok = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();
        assertThat(ok).hasSize(4);
    }

    @Test
    @DisplayName("[3-11] fetch() 를 안 부르면 SQL 이 한 줄도 나가지 않는다")
    void fetch_안부르면_아무일도_없다() {
        // 이 테스트는 아무것도 단언하지 않습니다.
        // 아래 두 println 사이에 hibernate.SQL 로그가 없다는 것을
        // 눈으로 확인하는 것이 전부입니다.
        System.out.println("--- 여기부터 쿼리 조립 ---");

        JPAQuery<Customer> query = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP));

        System.out.println("--- 조립 끝, SQL 로그를 확인하십시오 ---");

        // 이제 실행합니다. 여기서야 SQL 이 나갑니다.
        System.out.println("--- fetch() 호출 ---");
        List<Customer> result = query.fetch();
        System.out.println("--- 결과 " + result.size() + "건 ---");

        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("[3-11] fetch() 누락은 실패하지 않는 실패다")
    void fetch_누락은_조용하다() {
        // 컴파일 OK, 실행 OK, SQL 0건. 조용히 아무 일도 하지 않습니다.
        // IDE 의 unused return value 경고를 켜 두면 잡을 수 있습니다.
        queryFactory.selectFrom(customer)
                .where(customer.points.loe(0));

        System.out.println("위 줄에서 SQL 이 나가지 않았습니다.");
    }
}
