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
 * Step 01 — 연습문제 정답과 해설
 *
 * 문제를 직접 풀어 본 뒤에 여세요.
 *
 * 실행:
 *   ./gradlew test --tests 'com.example.shop.step01.Solution'
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =====================================================================
    // 정답 1. 오타의 발견 시점 비교
    //
    // 【핵심】 키워드 오타와 필드명 오타는 "다른 예외"가 납니다.
    //
    //   selct  (키워드 오타)  → org.hibernate.query.SyntaxException
    //   c.grde (필드명 오타)  → org.hibernate.query.SemanticException
    //
    // 왜 다른가?
    //   Hibernate 6 의 HQL 처리는 두 단계입니다.
    //     ① 파싱(syntax)   : 토큰이 문법에 맞는가. 'selct' 는 아예 키워드가 아니므로 여기서 실패.
    //     ② 의미 해석(semantic) : 파싱은 통과했지만 Customer 에 grde 라는 속성이 없으므로 여기서 실패.
    //   즉 필드명 오타는 "문법적으로는 올바른 문장"입니다. 그래서 파서를 통과합니다.
    //
    // Hibernate 5 를 쓰던 자료에서는 둘 다 QuerySyntaxException 이었습니다.
    // Hibernate 6 에서 ANTLR 기반으로 파서를 새로 쓰면서 예외 계층이 분화됐습니다.
    // 인터넷 검색으로 QuerySyntaxException 이 안 잡힌다면 이 때문입니다.
    //
    // (3) 발견 시점의 차이 — 이 문제의 진짜 답:
    //   문자열 JPQL 의 오타는 "그 코드 경로를 실행할 때" 발견됩니다.
    //   즉 테스트가 그 경로를 안 밟으면 배포까지 살아남고, 최초 사용자가 대신 발견합니다.
    //   QueryDSL 은 ./gradlew compileJava 단계에서 발견됩니다.
    //   실행 여부와 무관하게, 커버리지와 무관하게, 100% 발견됩니다.
    //   이 "100%" 가 QueryDSL 을 쓰는 이유의 전부입니다.
    // =====================================================================
    @Test
    @DisplayName("정답 1. 오타의 발견 시점 비교")
    void 정답1_오타의_발견시점() {
        String jpql = "select c from Customer c where c.grde = :grade";

        assertThatThrownBy(() ->
                em.createQuery(jpql, Customer.class)
                        .setParameter("grade", Grade.VIP)
                        .getResultList()
        ).satisfies(e -> {
            System.out.println("예외 클래스 : " + e.getClass().getName());
            System.out.println("메시지     : " + e.getMessage());
        });

        // QueryDSL 판 — 아래 주석을 풀면 컴파일이 실패합니다.
        //   queryFactory.selectFrom(customer).where(customer.grde.eq(Grade.VIP)).fetch();
        //   error: cannot find symbol
        //     symbol: variable grde, location: variable customer of type QCustomer
    }

    // =====================================================================
    // 정답 2. build.gradle 판정
    //
    // (a) querydsl-jpa:6.12:jakarta  →  (A) 빌드 실패
    //     6.x 는 Jakarta 를 네이티브로 삼았기 때문에 jakarta classifier 아티팩트를
    //     아예 발행하지 않습니다. Maven Central 에 그런 파일이 없습니다.
    //       Could not find querydsl-jpa-6.12-jakarta.jar
    //     5.x 습관을 그대로 옮겼을 때 나오는 가장 흔한 케이스이고,
    //     그나마 증상이 명확해서 다행인 케이스입니다.
    //
    // (b) querydsl-apt:6.12 (classifier 누락)  →  (B) 빌드 성공하지만 Q타입 미생성
    //     ★ 이 문항이 가장 많이 틀립니다. "빌드 실패"로 답하기 쉽습니다.
    //     querydsl-apt:6.12 는 실제로 존재하는 아티팩트라 의존성 해결이 성공합니다.
    //     하지만 그 기본 아티팩트에는 JPA 프로세서를 등록하는
    //     META-INF/services/javax.annotation.processing.Processor 항목이 없습니다.
    //     → APT 가 조용히 아무 일도 하지 않고, BUILD SUCCESSFUL 이 뜹니다.
    //     → 그다음 Q타입을 참조하는 코드에서 cannot find symbol 이 납니다.
    //     확인 명령: find build/generated -name 'Q*.java'  (출력이 없으면 이 케이스)
    //
    // (c) querydsl-jpa:5.0.0 (classifier 없음)  →  (C) 런타임 실패
    //     ★ 네 개 중 가장 위험합니다. 빌드가 초록불이기 때문입니다.
    //     5.0.0 기본 아티팩트는 javax.persistence 를 참조하는데,
    //     Spring Boot 3 / Jakarta EE 9+ 환경에는 그 패키지 자체가 없습니다.
    //       java.lang.NoClassDefFoundError: javax/persistence/Entity
    //     첫 쿼리를 실행하는 순간, 즉 서버가 뜬 뒤에 터집니다.
    //
    // (d) 정답 조합  →  (D) 정상
    //     jpa 는 classifier 없음, apt 만 :jpa.
    //     jakarta.persistence-api / jakarta.annotation-api 를 annotationProcessor 에
    //     추가한 것도 필수입니다. 프로세서가 엔티티를 읽을 때 이 API 가 프로세서
    //     클래스패스에 있어야 하기 때문입니다. 빠지면 Q타입이 부분적으로만 생성되거나
    //     cannot access jakarta.persistence.Entity 가 납니다.
    //
    // 【외울 것 한 줄】
    //   6.x → jpa 는 classifier 없음, apt 만 :jpa
    //   5.x → 양쪽 다 :jakarta
    //   두 classifier 의 "축"이 다르기 때문입니다.
    //     jpa 의 축 = javax냐 jakarta냐  (6.x 는 jakarta 하나뿐 → 축이 소멸)
    //     apt 의 축 = 어느 프로세서냐    (6.x 도 여전히 여러 개 → 축이 유지)
    // =====================================================================
    @Test
    @DisplayName("정답 2. build.gradle 판정")
    void 정답2_의존성_판정() {
        System.out.println("(a) A 빌드실패 / (b) B Q타입미생성 / (c) C 런타임실패 / (d) D 정상");
    }

    // =====================================================================
    // 정답 3. EntityManager 프록시 증명
    //
    // 출력 예:
    //   클래스명       : jdk.proxy2.$Proxy214
    //   인터페이스 만족 : true
    //
    // (3) 두 출력이 동시에 성립한다는 뜻:
    //   "EntityManager 인터페이스를 구현한 동적 프록시"라는 뜻입니다.
    //   타입은 EntityManager 가 맞지만, 실제 구현체(SessionImpl)는 아닙니다.
    //   호출을 받아 다른 객체에게 넘기는 중간자입니다.
    //
    //   이 프록시를 만드는 것은 스프링의 SharedEntityManagerCreator 이고,
    //   호출을 가로채는 것은 SharedEntityManagerInvocationHandler 입니다.
    //   핸들러가 하는 일은 매 호출마다 이것뿐입니다:
    //     "TransactionSynchronizationManager 에서 지금 이 스레드에 바인딩된
    //      EntityManager 를 꺼내서, 거기로 위임한다."
    //
    // (4) 왜 싱글턴 JPAQueryFactory 가 안전한가:
    //   JPAQueryFactory 가 붙들고 있는 것은 "특정 영속성 컨텍스트"가 아니라
    //   "지금 스레드의 영속성 컨텍스트를 찾아 주는 함수"이기 때문입니다.
    //   스레드 A 의 호출은 A 의 세션으로, 스레드 B 의 호출은 B 의 세션으로 갑니다.
    //   공유되는 상태가 없으므로 싱글턴으로 두어도 됩니다.
    //
    //   ★ 반대로 emf.createEntityManager() 로 만든 진짜 EntityManager 를
    //     싱글턴에 고정하면 이 분리가 사라집니다. 모든 스레드가 하나의 세션을 공유하고,
    //     ConcurrentModificationException / "Session is closed" 가 산발적으로 납니다.
    //     그리고 이 버그는 단일 스레드로 도는 테스트로는 절대 잡히지 않습니다.
    //     테스트로 못 잡는 버그이므로 "규칙"으로 막아야 합니다:
    //     애플리케이션 코드에서 emf.createEntityManager() 를 직접 호출하지 말 것.
    // =====================================================================
    @Test
    @DisplayName("정답 3. EntityManager 프록시 증명")
    void 정답3_EntityManager_프록시() {
        System.out.println("클래스명       : " + em.getClass().getName());
        System.out.println("인터페이스 만족 : " + (em instanceof EntityManager));

        assertThat(em.getClass().getName()).contains("Proxy");
        assertThat(em).isInstanceOf(EntityManager.class);

        // 프록시 뒤의 실제 세션 (확인용. 애플리케이션 코드에서 이러지 마세요)
        System.out.println("실제 세션      : "
                + em.unwrap(org.hibernate.Session.class).getClass().getName());
    }

    // =====================================================================
    // 정답 4. GOLD 등급 + 포인트 3000 이상
    //
    // 조건을 and 로 묶는 방법은 두 가지이고 결과 SQL 은 동일합니다.
    //   ① .where(A.and(B))
    //   ② .where(A, B)          ← 쉼표로 나열하면 QueryDSL 이 and 로 묶습니다
    //
    // ②를 권장합니다. 이유는 Step 04 에서 본격적으로 다루지만 미리 말하면,
    // 쉼표 나열은 인자 중 null 을 "조건 없음"으로 무시하기 때문에
    // 동적 쿼리를 조립할 때 그대로 재사용할 수 있습니다.
    //   .where(gradeEq(cond), pointsGoe(cond))   // null 이면 그 조건만 빠짐
    // .and() 체이닝은 앞이 null 이면 NullPointerException 이 납니다.
    //
    // 생성 SQL:
    //   select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
    //          c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
    //   from customers c1_0
    //   where c1_0.grade = ? and c1_0.points >= ?
    //   바인딩: [1] GOLD, [2] 3000  → 6건
    //
    // 여기서 확인할 것: 값이 SQL 문자열에 박히지 않고 ? 로 나갔다는 것입니다.
    // QueryDSL 은 상수를 자동으로 바인딩 파라미터로 만듭니다.
    // 문자열을 이어붙이는 코드가 없으므로 SQL 인젝션이 구조적으로 불가능하고,
    // DB 가 실행계획을 재사용할 수 있습니다.
    // =====================================================================
    @Test
    @DisplayName("정답 4. GOLD 등급 + 포인트 3000 이상")
    void 정답4_GOLD_포인트조회() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.GOLD),
                        customer.points.goe(3000)
                )
                .fetch();

        result.forEach(c -> System.out.println(c.getName() + " " + c.getPoints()));
        assertThat(result).hasSize(6);
    }

    // =====================================================================
    // 정답 5. JPQL vs SQL 차이 3가지   ★ 이 스텝의 핵심
    //
    // toString() 출력 (JPQL):
    //   select customer
    //   from Customer customer
    //   where customer.grade = ?1 and customer.city = ?2
    //
    // hibernate.SQL 출력 (SQL):
    //   select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
    //          c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
    //   from customers c1_0
    //   where c1_0.grade = ? and c1_0.city = ?
    //
    // 차이 1 — 별칭
    //   JPQL: customer  (우리가 static import 한 Q타입의 변수명이 그대로 별칭이 됩니다)
    //   SQL : c1_0      (Hibernate 6 이 <엔티티 첫 글자><인덱스>_<서브인덱스> 로 만듭니다)
    //   Hibernate 5 는 customer0_ 형태였습니다. 별칭 모양만 봐도 세대를 구별할 수 있습니다.
    //
    // 차이 2 — from 대상
    //   JPQL: Customer   (자바 클래스 이름)
    //   SQL : customers  (DB 테이블 이름)
    //   ★ QueryDSL 은 "customers" 라는 문자열을 단 한 번도 만들지 않습니다.
    //     테이블 이름을 아는 것은 Hibernate 이고, 매핑(@Table)에서 가져옵니다.
    //
    // 차이 3 — 파라미터 표기
    //   JPQL: ?1, ?2  (번호가 붙은 위치 파라미터)
    //   SQL : ?, ?    (JDBC 자리표시자, 번호 없음)
    //   주의: JPQL 의 ?1 이 SQL 의 첫 번째 ? 와 항상 일치한다고 가정하면 안 됩니다.
    //   in 절 확장이나 상속 판별 조건이 추가되면 SQL 쪽 ? 가 더 많아집니다.
    //   실제 바인딩 값은 org.hibernate.orm.jdbc.bind 로그로 확인하세요.
    //
    // 차이 4 (보너스) — 조회 대상의 세밀도
    //   JPQL: select customer   → "이 엔티티를 통째로"
    //   SQL : 8개 컬럼을 전부 나열
    //   엔티티를 조회하면 항상 모든 컬럼이 나갑니다. 필요한 두 컬럼만 읽고 싶다면
    //   프로젝션을 써야 하며, 그것이 Step 05 의 주제입니다.
    //
    // 【이 절이 왜 중요한가】
    //   QueryDSL 의 출력물은 JPQL 까지입니다. SQL 은 Hibernate 의 결과물입니다.
    //   따라서 JPQL 이 못 하는 것은 QueryDSL 도 못 합니다.
    //     - from 절 서브쿼리(인라인 뷰)  → 불가
    //     - UNION                        → 불가
    //     - 윈도우 함수                  → 불가 (JPA 모듈에서는)
    //   Step 07 에서 이 벽에 부딪히는데, 그때 "왜 안 되는가"의 답이 여기입니다.
    //
    // 【디버깅 요령】
    //   의도와 다른 SQL 이 나갈 때는 SQL 로그부터 보지 말고 toString() 부터 보세요.
    //     JPQL 이 이미 이상하다  → 내 QueryDSL 코드가 틀렸다
    //     JPQL 은 맞는데 SQL 이 이상하다 → 엔티티 매핑/페치 전략 문제다
    //   이 두 갈래로 나누는 것만으로 디버깅 시간이 절반이 됩니다.
    //   toString() 은 쿼리를 실행하지 않으므로 마음껏 찍어도 됩니다.
    // =====================================================================
    @Test
    @DisplayName("정답 5. JPQL vs SQL 차이 3가지")
    void 정답5_JPQL과_SQL의_차이() {
        JPAQuery<Customer> query = queryFactory
                .selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.VIP),
                        customer.city.eq("서울")
                );

        System.out.println("===== JPQL (실행 전) =====");
        System.out.println(query.toString());

        System.out.println("===== SQL (아래 hibernate.SQL 로그) =====");
        List<Customer> result = query.fetch();

        result.forEach(c -> System.out.println(c.getName()));
        assertThat(result).hasSize(2);      // 김서준, 정  훈
    }

    // =====================================================================
    // 정답 6. fetchOne 예외 재현과 대안
    //
    // (1) 재현
    //   city = "서울" 인 고객은 9명이므로 fetchOne() 은 반드시 터집니다.
    //     com.querydsl.core.NonUniqueResultException:
    //       Only one result is allowed for fetchOne calls
    //
    //   ★ 주의: QueryDSL 은 이 예외를 "결과를 다 가져온 뒤"에 던집니다.
    //     즉 9건을 DB 에서 전부 읽어 온 다음에 예외가 납니다.
    //     조건이 잘못돼서 100만 건이 매칭되면 100만 건을 읽고 나서 터집니다.
    //     예외가 났다고 해서 쿼리가 가벼웠던 게 아닙니다.
    //
    // (2) 대안 A — fetchFirst()
    //   내부적으로 limit(1) 을 붙입니다. 생성 SQL 끝에 limit ? 가 붙으므로
    //   DB 가 1건만 반환합니다. 위의 "다 읽고 터진다" 문제도 없습니다.
    //   단, 정렬이 없으면 "어느 1건"인지 DB 마음입니다.
    //   반드시 orderBy 를 함께 주세요. 이게 안 되어 있으면
    //   개발 DB 와 운영 DB 에서 다른 행이 나오는 현상이 생깁니다.
    //
    // (2) 대안 B — fetch() 후 size() 검사
    //   건수를 코드가 알고 있으므로 예외 대신 분기로 처리할 수 있습니다.
    //   "0건이면 기본값, 1건이면 그것, 2건 이상이면 로그를 남기고 첫 건" 같은
    //   비즈니스 규칙을 표현할 수 있습니다.
    //   단점은 전부 읽어 온다는 것이라, 결과가 많을 수 있으면 limit 을 함께 거세요.
    //
    // (3) 어느 쪽도 해결해 주지 못하는 것:
    //   ★ "이 조건이 정말 유일한가"는 코드가 판단해 주지 않습니다.
    //   fetchFirst() 로 바꾸면 예외는 사라지지만, 사실 2건이 나와야 정상인
    //   상황에서 조용히 1건만 쓰게 됩니다 — 에러 없이 틀린 결과를 반환하는 것이라
    //   예외가 나는 것보다 더 나쁠 수 있습니다.
    //   유일성이 보장되는 것은 DB 의 UNIQUE 제약뿐입니다.
    //   customers 테이블에는 uk_customers_email 이 있으므로 email 조회는 안전하고,
    //   city 나 name 은 제약이 없으므로 언제든 2건이 될 수 있습니다.
    //   fetchOne() 을 쓸지 말지의 기준은 "지금 데이터가 1건인가"가 아니라
    //   "스키마가 1건을 보장하는가" 입니다.
    // =====================================================================
    @Test
    @DisplayName("정답 6. fetchOne 예외 재현과 대안")
    void 정답6_fetchOne_예외와_대안() {
        // (1) 재현
        assertThatThrownBy(() ->
                queryFactory.selectFrom(customer)
                        .where(customer.city.eq("서울"))
                        .fetchOne()
        ).isInstanceOf(NonUniqueResultException.class)
         .satisfies(e -> System.out.println("예외 : " + e.getMessage()));

        // (2) 대안 A — fetchFirst + orderBy (정렬 없이 쓰지 말 것)
        Customer first = queryFactory.selectFrom(customer)
                .where(customer.city.eq("서울"))
                .orderBy(customer.id.asc())
                .fetchFirst();
        System.out.println("대안 A 첫 건 : " + first.getName());

        // (2) 대안 B — fetch 후 건수로 분기
        List<Customer> found = queryFactory.selectFrom(customer)
                .where(customer.city.eq("서울"))
                .fetch();
        System.out.println("대안 B 건수 : " + found.size());
        if (found.size() == 1) {
            System.out.println("단건 : " + found.get(0).getName());
        } else {
            System.out.println("단건이 아님 → 비즈니스 규칙으로 처리");
        }

        // (참고) 스키마가 유일성을 보장하는 조회 — email 은 UNIQUE 제약이 있습니다
        Customer byEmail = queryFactory.selectFrom(customer)
                .where(customer.email.eq("seojun.kim@example.com"))
                .fetchOne();
        System.out.println("email 단건 : " + (byEmail == null ? "null" : byEmail.getName()));
    }
}
