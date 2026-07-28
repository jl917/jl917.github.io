package com.example.shop.step01;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
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

/**
 * Step 01 — 연습문제 (6문제)
 *
 * 각 문제의 "여기에 작성:" 자리를 채우세요.
 * 정답과 해설은 Solution.java 에 있습니다. 먼저 직접 풀어 보세요.
 *
 * 실행:
 *   ./gradlew test --tests 'com.example.shop.step01.Exercise'
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =====================================================================
    // 문제 1. 오타의 발견 시점 비교
    //
    // (1) 아래 JPQL 은 필드명이 c.grade 가 아니라 c.grde 로 잘못돼 있습니다.
    //     이 쿼리를 실행해서 어떤 예외가 나는지 확인하고,
    //     예외 클래스명과 메시지를 콘솔에 출력하세요.
    //     (예외를 삼키지 말고 반드시 출력할 것. 무엇이 어디서 터지는지 보는 게 목적입니다.)
    //
    // (2) 그다음 같은 쿼리를 QueryDSL 로 옮겨 쓰되,
    //     customer.grde 라고 오타를 내 보고 컴파일이 실패하는 것을 확인하세요.
    //     확인한 뒤에는 주석 처리해 두세요(그래야 나머지 테스트가 돌아갑니다).
    //
    // (3) 두 오타가 발견되는 "시점"이 어떻게 다른지 아래 주석에 한 줄로 적으세요.
    //     답:
    // =====================================================================
    @Test
    @DisplayName("문제 1. 오타의 발견 시점 비교")
    void 문제1_오타의_발견시점() {
        String jpql = "select c from Customer c where c.grde = :grade";

        // 여기에 작성:

    }

    // =====================================================================
    // 문제 2. 잘못된 build.gradle 판정
    //
    // 아래 4개의 의존성 조각에 대해, 각각 어떤 결과가 나오는지 판정하고
    // 근거를 주석으로 적으세요. 코드를 실행하는 문제가 아니라 판정 문제입니다.
    //
    // 보기:
    //   (A) 빌드 실패 — 의존성 해결 단계에서
    //   (B) 빌드 성공하지만 Q타입이 생성되지 않음
    //   (C) 빌드 성공하지만 런타임에 실패
    //   (D) 정상
    //
    // ---------------------------------------------------------------------
    // (a)
    //   implementation      'io.github.openfeign.querydsl:querydsl-jpa:6.12:jakarta'
    //   annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
    //   판정:        근거:
    //
    // (b)
    //   implementation      'io.github.openfeign.querydsl:querydsl-jpa:6.12'
    //   annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12'
    //   판정:        근거:
    //
    // (c)
    //   implementation      'com.querydsl:querydsl-jpa:5.0.0'
    //   annotationProcessor 'com.querydsl:querydsl-apt:5.0.0'
    //   판정:        근거:
    //
    // (d)
    //   implementation      'io.github.openfeign.querydsl:querydsl-jpa:6.12'
    //   implementation      'io.github.openfeign.querydsl:querydsl-core:6.12'
    //   annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
    //   annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
    //   annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
    //   판정:        근거:
    // =====================================================================
    @Test
    @DisplayName("문제 2. build.gradle 판정 (주석으로 답을 적는 문제)")
    void 문제2_의존성_판정() {
        System.out.println("위 주석에 판정과 근거를 적었는지 확인하세요.");
    }

    // =====================================================================
    // 문제 3. 주입된 EntityManager 가 프록시임을 증명하기
    //
    // 요구사항:
    //   (1) em.getClass().getName() 을 출력하세요.
    //   (2) (em instanceof EntityManager) 를 출력하세요.
    //   (3) 두 출력이 동시에 성립한다는 것이 무슨 뜻인지 한 줄로 적으세요.
    //       답:
    //   (4) 이 사실이 왜 싱글턴 JPAQueryFactory 를 안전하게 만드는지 한 줄로 적으세요.
    //       답:
    // =====================================================================
    @Test
    @DisplayName("문제 3. EntityManager 프록시 증명")
    void 문제3_EntityManager_프록시() {
        // 여기에 작성:

    }

    // =====================================================================
    // 문제 4. GOLD 등급 + 포인트 3000 이상 고객 조회
    //
    // 요구사항:
    //   - selectFrom 을 사용할 것
    //   - 조건 두 개를 and 로 묶을 것
    //   - 결과를 이름과 포인트로 출력할 것
    //   - 콘솔의 hibernate.SQL 로그에서 생성 SQL 을 확인할 것
    //
    // 기대 결과: 6건
    // 기대 SQL:
    //   select c1_0.customer_id, c1_0.city, ... from customers c1_0
    //   where c1_0.grade = ? and c1_0.points >= ?
    // =====================================================================
    @Test
    @DisplayName("문제 4. GOLD 등급 + 포인트 3000 이상")
    void 문제4_GOLD_포인트조회() {
        // 여기에 작성:
        List<Customer> result = null;

        // result.forEach(c -> System.out.println(c.getName() + " " + c.getPoints()));
    }

    // =====================================================================
    // 문제 5. JPQL 과 SQL 을 나란히 출력하고 차이를 서술하기  ★ 이 문제지에서 가장 중요
    //
    // 요구사항:
    //   (1) VIP 이면서 도시가 "서울"인 고객을 조회하는 JPAQuery 를 만들되,
    //       바로 fetch() 하지 말고 변수에 담으세요.
    //   (2) query.toString() 으로 JPQL 을 먼저 출력하세요.
    //   (3) 그다음 fetch() 를 호출해 hibernate.SQL 로그를 띄우세요.
    //   (4) 두 출력의 차이를 3가지 적으세요.
    //       차이 1:
    //       차이 2:
    //       차이 3:
    // =====================================================================
    @Test
    @DisplayName("문제 5. JPQL vs SQL 차이 3가지")
    void 문제5_JPQL과_SQL의_차이() {
        // 여기에 작성:
        JPAQuery<Customer> query = null;

        // System.out.println(query.toString());
        // query.fetch();
    }

    // =====================================================================
    // 문제 6. fetchOne 의 NonUniqueResultException 재현과 대안
    //
    // 요구사항:
    //   (1) fetchOne() 이 NonUniqueResultException 을 던지는 조건을 만들어
    //       실제로 예외가 나는 것을 확인하고 메시지를 출력하세요.
    //       (힌트: 결과가 2건 이상 나오는 조건이면 됩니다)
    //   (2) 같은 의도를 안전하게 처리하는 방법 두 가지로 고쳐 쓰세요.
    //       대안 A:
    //       대안 B:
    //   (3) 두 대안 중 어느 쪽도 해결해 주지 못하는 것이 무엇인지 한 줄로 적으세요.
    //       답:
    // =====================================================================
    @Test
    @DisplayName("문제 6. fetchOne 예외 재현과 대안")
    void 문제6_fetchOne_예외와_대안() {
        // (1) 예외 재현 — 여기에 작성:

        // (2) 대안 A — 여기에 작성:

        // (2) 대안 B — 여기에 작성:

    }
}
