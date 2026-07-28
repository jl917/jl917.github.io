package com.example.shop.step01;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.querydsl.core.NonUniqueResultException;
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

import static com.example.shop.entity.QCustomer.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 01 — 환경 구축과 첫 쿼리
 *
 * 본문 1-1 ~ 1-9 의 모든 예제를 절 번호 주석과 함께 담았습니다.
 *
 * 실행:
 *   ./gradlew test --tests 'com.example.shop.step01.Practice'
 *
 * ★ 전제 — application.yml 에 SQL 로그 설정이 되어 있어야 합니다.
 *   logging:
 *     level:
 *       org.hibernate.SQL: debug
 *       org.hibernate.orm.jdbc.bind: trace
 *       org.hibernate.orm.query: trace
 *   spring:
 *     jpa:
 *       properties:
 *         hibernate:
 *           format_sql: true
 *
 * 이 설정이 없으면 콘솔에 아무것도 안 나오고, 이 스텝의 실습은 의미가 없어집니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =====================================================================
    // [1-1] QueryDSL 이란 — 오타가 런타임에야 터진다
    // =====================================================================

    /**
     * 키워드 오타(selct)는 컴파일을 통과하고 실행 시점에 SyntaxException 을 던집니다.
     * 이 테스트는 "예외가 나는 것"을 검증하므로 초록불이 정상입니다.
     */
    @Test
    @DisplayName("[1-1] 문자열 JPQL 의 키워드 오타는 런타임에 터진다")
    void 오타난_JPQL_은_런타임에_터진다() {
        String jpql = "selct c from Customer c where c.grade = :grade";   // select 오타

        assertThatThrownBy(() ->
                em.createQuery(jpql, Customer.class)
                        .setParameter("grade", Grade.VIP)
                        .getResultList()
        ).satisfies(e -> {
            System.out.println("예외 클래스 : " + e.getClass().getName());
            System.out.println("메시지     : " + e.getMessage());
        });
    }

    /**
     * 필드명 오타는 파싱은 통과하고 "의미 해석" 단계에서 실패합니다.
     * 그래서 SyntaxException 이 아니라 SemanticException 이 납니다.
     */
    @Test
    @DisplayName("[1-1] 필드명 오타는 SemanticException")
    void 필드명_오타는_SemanticException() {
        String jpql = "select c from Customer c where c.grde = :grade";   // grade 오타

        assertThatThrownBy(() ->
                em.createQuery(jpql, Customer.class)
                        .setParameter("grade", Grade.VIP)
                        .getResultList()
        ).satisfies(e -> System.out.println("예외 클래스 : " + e.getClass().getName()));
    }

    /**
     * 같은 실수를 QueryDSL 로 하면 아래 코드는 애초에 컴파일되지 않습니다.
     * 주석을 풀고 ./gradlew compileTestJava 를 돌려 직접 확인해 보세요.
     *
     *   queryFactory.selctFrom(customer)                   // cannot find symbol: selctFrom
     *   queryFactory.selectFrom(customer)
     *           .where(customer.grde.eq(Grade.VIP))        // cannot find symbol: grde
     *   queryFactory.selectFrom(customer)
     *           .where(customer.points.eq("VIP"))          // incompatible types
     */

    // =====================================================================
    // [1-4] JPAQueryFactory 빈 등록 — EntityManager 는 프록시다
    // =====================================================================

    /**
     * 스프링이 주입하는 EntityManager 는 SharedEntityManagerCreator 가 만든 프록시입니다.
     * 실제 세션(SessionImpl)이 아니라, "지금 스레드의 세션을 찾아 위임하는" 객체입니다.
     * 그래서 싱글턴 JPAQueryFactory 가 이 프록시를 필드로 붙들어도 안전합니다.
     */
    @Test
    @DisplayName("[1-4] 주입된 EntityManager 는 프록시다")
    void 주입된_EntityManager의_정체() {
        System.out.println("클래스명 : " + em.getClass().getName());
        System.out.println("인터페이스 만족 : " + (em instanceof EntityManager));

        // jdk.proxy2.$Proxy214 처럼 $Proxy 가 들어가면 정상 (숫자는 매 실행 다릅니다)
        assertThat(em.getClass().getName()).contains("Proxy");
    }

    /**
     * unwrap 으로 프록시 뒤의 실제 세션을 꺼내 볼 수 있습니다.
     * 이건 확인용이며, 애플리케이션 코드에서 이렇게 꺼내 쓰면 안 됩니다.
     */
    @Test
    @DisplayName("[1-4] 프록시 뒤의 실제 세션")
    void 프록시_뒤의_실제_세션() {
        Object session = em.unwrap(org.hibernate.Session.class);
        System.out.println("실제 세션 : " + session.getClass().getName());
        // org.hibernate.internal.SessionImpl
    }

    // =====================================================================
    // [1-5] 첫 쿼리 — selectFrom
    // =====================================================================

    /**
     * 생성 SQL:
     *   select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
     *          c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
     *   from customers c1_0
     *   where c1_0.grade = ?
     * 바인딩: [1] VIP  → 4건 (김서준, 류하나, 정  훈, 배채영)
     */
    @Test
    @DisplayName("[1-5] VIP 고객 조회 — selectFrom")
    void VIP_고객_조회() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        result.forEach(c -> System.out.println(c.getName() + " / " + c.getCity()));
        assertThat(result).hasSize(4);
    }

    /**
     * 조회 대상과 from 이 다르면 selectFrom 축약을 쓸 수 없습니다.
     * 생성 SQL: select c1_0.name from customers c1_0 where c1_0.grade = ?
     */
    @Test
    @DisplayName("[1-5] 이름만 조회 — select + from 은 나눠 쓴다")
    void 이름만_조회() {
        List<String> names = queryFactory
                .select(customer.name)
                .from(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        System.out.println(names);
        assertThat(names).hasSize(4);
    }

    /**
     * fetchOne() 은 유니크 제약이 있는 컬럼으로 조회할 때만 안전합니다.
     */
    @Test
    @DisplayName("[1-5] fetchOne — 유니크 컬럼(email)으로 단건 조회")
    void 단건_조회() {
        Customer one = queryFactory
                .selectFrom(customer)
                .where(customer.email.eq("seojun.kim@example.com"))
                .fetchOne();

        System.out.println("조회 결과 : " + (one == null ? "null" : one.getName()));
    }

    // =====================================================================
    // [1-6] SQL 로그 보는 법
    // =====================================================================

    /**
     * 이 테스트는 아무것도 단언하지 않습니다. 콘솔을 눈으로 보라는 테스트입니다.
     *
     * 아래 두 줄이 모두 보여야 이후 스텝을 정상적으로 진행할 수 있습니다.
     *   org.hibernate.SQL              : select ... from customers c1_0 where ...
     *   org.hibernate.orm.jdbc.bind    : binding parameter (1:VARCHAR) <- [VIP]
     *
     * 파라미터 줄이 안 보인다면 Hibernate 5 용 카테고리
     * (org.hibernate.type.descriptor.sql.BasicBinder) 를 설정한 것입니다.
     * Hibernate 6 에서는 org.hibernate.orm.jdbc.bind 를 써야 합니다.
     */
    @Test
    @DisplayName("[1-6] 로그 설정 확인 — 콘솔을 눈으로 볼 것")
    void 로그_설정_확인() {
        queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        System.out.println("↑ 위 콘솔에 hibernate.SQL 과 binding parameter 가 둘 다 보이는지 확인하세요.");
    }

    // =====================================================================
    // [1-7] JPQL vs QueryDSL 대조
    // =====================================================================

    @Test
    @DisplayName("[1-7] ① VIP 고객 — JPQL / QueryDSL 결과 동일")
    void 대조_VIP_고객() {
        List<Customer> byJpql = em.createQuery(
                        "select c from Customer c where c.grade = :grade", Customer.class)
                .setParameter("grade", Grade.VIP)
                .getResultList();

        List<Customer> byQuerydsl = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        assertThat(byQuerydsl).hasSameSizeAs(byJpql);
    }

    /**
     * 생성 SQL: ... where c1_0.points >= ? order by c1_0.name asc
     */
    @Test
    @DisplayName("[1-7] ② 포인트 5000 이상, 이름순")
    void 대조_포인트_정렬() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.points.goe(5000))
                .orderBy(customer.name.asc())
                .fetch();

        result.forEach(c -> System.out.println(c.getName() + " " + c.getPoints()));
    }

    /**
     * contains 는 %키워드% 를 자동으로 만들고 escape '!' 까지 붙여 줍니다.
     * 생성 SQL: ... where c1_0.name like ? escape '!'   /  바인딩 [1] %지%
     */
    @Test
    @DisplayName("[1-7] ③ 이름 부분 일치 — contains 는 escape 를 붙인다")
    void 대조_이름_부분일치() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.name.contains("지"))
                .fetch();

        result.forEach(c -> System.out.println(c.getName()));
        assertThat(result).hasSize(2);      // 안지수, 한지호
    }

    /**
     * 생성 SQL: ... where c1_0.phone is null   → 3건
     */
    @Test
    @DisplayName("[1-7] ④ 전화번호가 없는 고객")
    void 대조_전화번호_null() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.phone.isNull())
                .fetch();

        result.forEach(c -> System.out.println(c.getName()));
        assertThat(result).hasSize(3);
    }

    /**
     * 생성 SQL:
     *   select c1_0.city, count(c1_0.customer_id)
     *   from customers c1_0 group by c1_0.city order by count(c1_0.customer_id) desc
     */
    @Test
    @DisplayName("[1-7] ⑤ 도시별 고객 수 — Tuple 로 받는다")
    void 대조_도시별_고객수() {
        queryFactory
                .select(customer.city, customer.count())
                .from(customer)
                .groupBy(customer.city)
                .orderBy(customer.count().desc())
                .fetch()
                .forEach(t -> System.out.println(
                        t.get(customer.city) + "  " + t.get(customer.count())));
    }

    // =====================================================================
    // [1-8] QueryDSL 이 만드는 것은 JPQL 이지 SQL 이 아니다
    // =====================================================================

    /**
     * 출력 순서가 곧 변환 순서입니다.
     *   ① toString()  → JPQL   : select customer from Customer customer where ...
     *   ② fetch()     → SQL    : select c1_0.customer_id, ... from customers c1_0 where ...
     *
     * 차이 3가지:
     *   - 별칭     : customer (Q타입 변수명)  vs  c1_0 (Hibernate 생성)
     *   - from 대상 : Customer (클래스명)      vs  customers (테이블명)
     *   - 파라미터 : ?1, ?2 (번호 있음)        vs  ?, ? (JDBC 자리표시자)
     */
    @Test
    @DisplayName("[1-8] JPQL 과 SQL 을 나란히 본다")
    void JPQL과_SQL을_나란히() {
        JPAQuery<Customer> query = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP)
                        .and(customer.points.goe(5000)));

        System.out.println("===== JPQL (toString, 아직 실행 안 함) =====");
        System.out.println(query.toString());

        System.out.println("===== SQL (아래 hibernate.SQL 로그) =====");
        List<Customer> result = query.fetch();

        result.forEach(c -> System.out.println(c.getName() + " " + c.getPoints()));
    }

    /**
     * JPQL 이 못 하는 것은 QueryDSL 도 못 합니다.
     * from 절 서브쿼리(인라인 뷰)는 JPQL 명세에 없으므로 API 자체가 존재하지 않습니다.
     * Step 07 에서 이 벽과 우회로를 다룹니다.
     */
    @Test
    @DisplayName("[1-8] from 절 서브쿼리는 API 자체가 없다")
    void from절_서브쿼리는_불가능() {
        // queryFactory.selectFrom( <서브쿼리> )   ← 이런 오버로드가 없습니다
        System.out.println("JPQL 명세에 인라인 뷰가 없으므로 QueryDSL(JPA) 에도 없습니다.");
    }

    // =====================================================================
    // [1-9] 함정
    // =====================================================================

    /**
     * fetchOne() 은 결과가 2건 이상이면 NonUniqueResultException 을 던집니다.
     * 서울 고객은 9명이므로 반드시 터집니다.
     */
    @Test
    @DisplayName("[1-9] fetchOne 은 2건 이상이면 터진다")
    void fetchOne_은_2건이면_터진다() {
        assertThatThrownBy(() ->
                queryFactory.selectFrom(customer)
                        .where(customer.city.eq("서울"))
                        .fetchOne()
        ).isInstanceOf(NonUniqueResultException.class)
         .satisfies(e -> System.out.println("메시지 : " + e.getMessage()));
    }

    /**
     * 안전한 대안 ① — fetchFirst() 는 limit(1) 을 붙여 첫 건만 가져옵니다.
     * 생성 SQL 끝에 limit ? 가 붙습니다.
     */
    @Test
    @DisplayName("[1-9] 대안 ① fetchFirst")
    void 대안_fetchFirst() {
        Customer first = queryFactory.selectFrom(customer)
                .where(customer.city.eq("서울"))
                .orderBy(customer.id.asc())
                .fetchFirst();

        System.out.println("첫 건 : " + (first == null ? "null" : first.getName()));
    }

    /**
     * 안전한 대안 ② — fetch() 로 받아 크기를 직접 검사합니다.
     * "몇 건 나왔는지"를 코드가 알고 있으므로 예외 대신 분기로 처리할 수 있습니다.
     */
    @Test
    @DisplayName("[1-9] 대안 ② fetch 후 size 검사")
    void 대안_fetch_후_size검사() {
        List<Customer> found = queryFactory.selectFrom(customer)
                .where(customer.city.eq("서울"))
                .fetch();

        System.out.println("건수 : " + found.size());
        if (found.size() == 1) {
            System.out.println("단건 : " + found.get(0).getName());
        } else {
            System.out.println("단건이 아니므로 별도 처리 (예외 아님)");
        }
    }

    /**
     * 함정 ③ — 조회 전용 메서드에 트랜잭션이 없으면 LazyInitializationException.
     * 이 클래스는 @Transactional 이라 여기서는 정상 동작합니다.
     * 트랜잭션을 떼고 같은 코드를 호출하면 프록시 초기화에서 터집니다.
     */
    @Test
    @DisplayName("[1-9] 지연 로딩은 트랜잭션 안에서만 안전하다")
    void 지연로딩은_트랜잭션_안에서만() {
        Customer c = queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetchFirst();

        // @Transactional 덕분에 세션이 살아 있어 orders 프록시를 초기화할 수 있습니다.
        System.out.println(c.getName() + " 의 주문 수 : " + c.getOrders().size());
    }
}
