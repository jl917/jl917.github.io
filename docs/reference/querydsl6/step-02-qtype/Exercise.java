package com.example.shop.step02;

import com.example.shop.entity.Customer;
import com.example.shop.entity.QCustomer;
import com.example.shop.entity.QEmployee;
import com.querydsl.core.Tuple;
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
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 02 — 연습문제 6문제.
 *
 * 이 스텝의 문제는 코드를 많이 쓰지 않습니다.
 * "관찰하고 주석에 기록하는" 문제가 절반입니다.
 *
 * 정답은 Solution.java 에 있습니다. 먼저 직접 풀어 보십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 문제 1. QProduct 의 Path 타입 확인 (코드 없음 — 관찰 문제)
    // =================================================================
    /*
     * 아래 파일을 직접 열어 보십시오.
     *
     *   build/generated/sources/annotationProcessor/java/main/
     *     com/example/shop/entity/QProduct.java
     *
     * (파일이 없으면 먼저 ./gradlew compileJava 를 실행하십시오)
     *
     * Product 엔티티의 각 필드가 어떤 Path 타입으로 생성됐는지 확인하고
     * 아래 빈칸을 채우십시오.
     *
     *   id        (Long)            ->  ________________
     *   name      (String)          ->  ________________
     *   price     (BigDecimal)      ->  ________________
     *   cost      (BigDecimal)      ->  ________________
     *   stock     (Integer)         ->  ________________
     *   status    (ProductStatus)   ->  ________________
     *   createdAt (LocalDateTime)   ->  ________________
     *   category  (@ManyToOne)      ->  ________________
     *   reviews   (@OneToMany)      ->  ________________
     *
     * 추가 질문:
     *   Q1-a. category 와 reviews 는 왜 서로 다른 것으로 매핑됐습니까?
     *         답: ______________________________________________
     *
     *   Q1-b. product.category.name 처럼 점으로 이어서 쓸 수 있는 이유는?
     *         답: ______________________________________________
     */

    // =================================================================
    // 문제 2. 컴파일 에러 관찰 (일부러 컴파일을 깨뜨리는 문제)
    // =================================================================
    @Test
    @DisplayName("문제 2 — 잘못된 조건 메서드는 컴파일 에러")
    void 문제2_컴파일에러_관찰() {
        /*
         * 아래 두 줄의 주석을 풀고 컴파일하십시오.
         * 컴파일이 실패하는 것이 정상입니다.
         *
         * 각각의 에러 메시지를 복사해 아래 주석 블록에 붙여 넣은 뒤,
         * 다시 주석 처리해서 컴파일이 되도록 되돌리십시오.
         */

        // 여기에 작성: (주석을 풀었다가 다시 닫으십시오)
        // queryFactory.selectFrom(customer).where(customer.name.goe(5)).fetch();
        // queryFactory.selectFrom(product).where(product.stock.contains("10")).fetch();

        /*
         * customer.name.goe(5) 의 에러 메시지:
         *   ______________________________________________________
         *   ______________________________________________________
         *
         * product.stock.contains("10") 의 에러 메시지:
         *   ______________________________________________________
         *   ______________________________________________________
         *
         * Q2-a. 같은 실수를 문자열 JPQL 로 했다면 언제 발견됐을까요?
         *       답: ____________________________________________
         */
    }

    // =================================================================
    // 문제 3. 셀프 조인으로 사원-관리자 조회
    // =================================================================
    @Test
    @DisplayName("문제 3 — 셀프 조인 18건")
    void 문제3_셀프조인() {
        /*
         * 요구사항:
         *   - QEmployee 를 두 개 준비합니다. 하나는 기본 인스턴스,
         *     하나는 new QEmployee("m") 으로 만든 관리자 쪽 별칭입니다.
         *   - 사원 이름과 관리자 이름을 Tuple 로 조회합니다.
         *   - 관리자가 없는 사원(김대표)도 결과에 남아야 합니다. → leftJoin
         *   - employee.id 오름차순 정렬.
         *   - 결과는 18건이어야 합니다.
         *
         * 힌트: leftJoin(e.manager, m)
         */

        // 여기에 작성:


        // 아래 단언의 주석을 풀어 확인하십시오.
        // assertThat(result).hasSize(18);

        /*
         * 실행 후 hibernate.SQL 로그를 보고 답하십시오.
         *
         * Q3-a. SQL 에 employees 테이블이 몇 번 등장합니까?  ______
         * Q3-b. 그 두 별칭은 각각 무엇입니까?  ______ , ______
         * Q3-c. leftJoin 을 innerJoin 으로 바꾸면 몇 건이 됩니까?  ______
         *       왜 그렇습니까? ______________________________
         */
    }

    // =================================================================
    // 문제 4. 별칭이 달라도 SQL 은 같다
    // =================================================================
    @Test
    @DisplayName("문제 4 — JPQL 은 다르고 SQL 은 같다")
    void 문제4_별칭과_SQL() {
        /*
         * 요구사항:
         *   - QCustomer 기본 인스턴스로 "서울 거주 고객" 쿼리를 JPAQuery 변수에 담습니다.
         *     (fetch() 를 부르지 말고 쿼리 객체만 만드십시오)
         *   - new QCustomer("x") 로 같은 조건의 쿼리를 또 하나 만듭니다.
         *   - 두 쿼리의 toString() 을 콘솔에 찍습니다. (이것이 JPQL 입니다)
         *   - 두 JPQL 이 "다르다"는 것을 단언합니다.
         *   - 그 다음 두 쿼리를 각각 fetch() 하고, hibernate.SQL 로그가
         *     "같다"는 것을 눈으로 확인합니다.
         */

        // 여기에 작성:


        // assertThat(q1.toString()).isNotEqualTo(q2.toString());

        /*
         * Q4-a. 두 JPQL 을 그대로 적어 보십시오.
         *       q1: ______________________________________________
         *       q2: ______________________________________________
         *
         * Q4-b. 두 hibernate.SQL 의 from 절을 적어 보십시오.
         *       q1: ______________________________________________
         *       q2: ______________________________________________
         *
         * Q4-c. SQL 이 같다면, 별칭을 바꾸는 것은 무엇을 위해서입니까?
         *       답: ____________________________________________
         */
    }

    // =================================================================
    // 문제 5. 컴파일하지 않으면 Q타입은 갱신되지 않는다
    // =================================================================
    /*
     * 이 문제는 테스트 코드로 자동화할 수 없습니다. 아래 절차를 수행하십시오.
     *
     * 1) src/main/java/com/example/shop/entity/Customer.java 를 열고
     *    아래 필드를 추가한 뒤 "저장만" 하십시오. (컴파일하지 마십시오)
     *
     *        @Column(name = "last_login_at")
     *        private LocalDateTime lastLoginAt;
     *
     * 2) 이 파일에서 customer.lastLoginAt 을 타이핑해 보십시오.
     *    자동완성이 됩니까?
     *       답: ______
     *    왜 그렇습니까?
     *       답: ______________________________________________
     *
     * 3) 터미널에서 ./gradlew compileJava 를 실행하십시오.
     *
     * 4) 다시 customer.lastLoginAt 을 타이핑해 보십시오.
     *    이제 됩니까?
     *       답: ______
     *
     * 5) 정리: 추가한 필드를 다시 지우고 ./gradlew compileJava 를 실행하십시오.
     *
     *    주의 — ddl-auto: validate 를 쓰고 있으므로, DB 에 없는 컬럼을
     *    엔티티에 추가한 채로 두면 애플리케이션이 뜨지 않습니다.
     *    반드시 5) 를 수행해 원상복구하십시오.
     *
     * Q5-a. "파일 저장" 과 "컴파일" 을 구분하지 않는 습관이 왜 사고를 냅니까?
     *       답: ______________________________________________
     */

    // =================================================================
    // 문제 6. :jpa classifier 를 빼서 "빌드 성공인데 Q타입 없음" 재현
    // =================================================================
    /*
     * 이 문제도 빌드를 건드리므로 절차를 직접 수행하십시오.
     *
     * ⚠️ 반드시 마지막 단계까지 수행해 원상복구하십시오.
     *    안 그러면 이후 모든 스텝이 컴파일되지 않습니다.
     *
     * 1) 먼저 정상 상태의 로그를 기록해 둡니다.
     *
     *        ./gradlew clean compileJava --info | grep -i "Processors:"
     *
     *    출력: ______________________________________________
     *
     * 2) build.gradle 을 열고 annotationProcessor 좌표에서
     *    :jpa classifier 를 제거하십시오.
     *
     *        // 원래
     *        annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
     *        // 이렇게 바꿉니다
     *        annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12'
     *
     * 3) 다시 실행하십시오.
     *
     *        ./gradlew clean compileJava --info | grep -i "Processors:"
     *
     *    출력: ______________________________________________
     *
     * Q6-a. 빌드 결과는 무엇입니까? (BUILD SUCCESSFUL / FAILED)
     *       답: ______
     *
     * Q6-b. find build/generated -name "Q*.java" | wc -l 의 결과는?
     *       답: ______
     *
     * Q6-c. 1) 과 3) 의 Processors 목록에서 무엇이 사라졌습니까?
     *       답: ______________________________________________
     *
     * Q6-d. 이 원인이 5가지 중 가장 찾기 어려운 이유는 무엇입니까?
     *       답: ______________________________________________
     *
     * 4) 원상복구 — build.gradle 에 :jpa 를 되돌리고 다시 컴파일하십시오.
     *
     *        annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
     *        ./gradlew clean compileJava
     *
     *    find build/generated -name "Q*.java" | wc -l 이 8 이면 복구 완료입니다.
     */
}
