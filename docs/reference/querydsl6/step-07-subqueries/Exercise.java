package com.example.shop.step07;

import com.example.shop.entity.Order;
import com.example.shop.entity.Product;
import com.example.shop.entity.QProduct;
import com.querydsl.core.Tuple;
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
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QPayment.payment;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;
import static com.querydsl.jpa.JPAExpressions.select;
import static com.querydsl.jpa.JPAExpressions.selectOne;

/**
 * Step 07 — 서브쿼리 : 연습문제 7문제
 *
 * <p>각 문제의 "여기에 작성:" 아래를 채우십시오.
 * 정답은 Solution.java 에 있습니다.
 *
 * <p><b>채점 기준은 건수가 아니라 생성 SQL 입니다.</b>
 * 건수가 맞아도 나가는 SQL 이 의도한 모양이 아니면 틀린 것입니다.
 * 실행할 때마다 콘솔의 hibernate.SQL 로그를 반드시 확인하십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // -----------------------------------------------------------------
    // 문제 1.
    //   전체 상품 평균가보다 비싼 상품을 조회하십시오.
    //
    //   요구사항
    //     - JPAExpressions 로 서브쿼리를 만들 것
    //     - 바깥과 서브쿼리의 별칭을 분리할 것 (QProduct sub = new QProduct("sub"))
    //     - price 내림차순 정렬
    //     - 실행 후 생성 SQL 에서 p1_0 / p2_0 이 각각 어디를 가리키는지 확인할 것
    //
    //   기대 결과: 11건
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 1. 전체 평균가보다 비싼 상품 (11건)")
    void q1_aboveAveragePrice() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 2.
    //   후기를 한 번도 받지 못한 상품을 notExists 로 조회하십시오.
    //
    //   요구사항
    //     - selectOne().from(review).where(...).notExists() 형태를 쓸 것
    //     - product.id 오름차순 정렬
    //
    //   기대 결과: 24건
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2. 후기 없는 상품 — notExists (24건)")
    void q2_noReviewByNotExists() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 3.
    //   문제 2 와 같은 질문을 notIn 으로 다시 푸십시오.
    //
    //   요구사항
    //     - reviews.product_id 는 현재 스키마에서 NOT NULL 이므로
    //       그냥 써도 답은 맞습니다. 하지만 스키마는 바뀝니다.
    //     - 7-5 절에서 배운 대로 NULL 함정을 피하도록 방어적으로 작성하십시오.
    //     - 작성 후 생성 SQL 에 is not null 조건이 들어갔는지 확인할 것
    //
    //   기대 결과: 24건 (문제 2 와 동일)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 3. 후기 없는 상품 — notIn (방어적으로, 24건)")
    void q3_noReviewByNotIn() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 4.
    //   결제(Payment)가 하나도 없는 주문을 두 가지 방법으로 조회하십시오.
    //
    //   요구사항
    //     (a) notExists
    //     (b) leftJoin + isNull  (안티 조인)
    //     - 두 결과의 건수가 같은지 직접 비교할 것
    //     - (b) 에서 isNull() 을 어느 컬럼에 걸어야 하는지 주의할 것
    //       (힌트: 조인 대상의 NOT NULL 컬럼 = PK)
    //
    //   기대 결과: 둘 다 60건 (PENDING 주문)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 4. 결제 없는 주문 — 두 방법 (각 60건)")
    void q4_ordersWithoutPayment() {
        // 여기에 작성: (a) notExists

        // 여기에 작성: (b) leftJoin + isNull

        // 여기에 작성: 두 결과 건수 비교

    }

    // -----------------------------------------------------------------
    // 문제 5.
    //   고객 목록과 "각 고객이 쓴 후기 수" 를 조회하십시오.
    //
    //   요구사항
    //     (a) select 절 상관 서브쿼리 + ExpressionUtils.as(...) 로 작성
    //         - 표현식을 변수로 뽑아 두고 tuple.get(변수) 로 꺼낼 것
    //     (b) 같은 결과를 leftJoin + groupBy 로 다시 작성
    //     - 두 생성 SQL 을 나란히 놓고 비교할 것
    //     - (b) 에서 innerJoin 을 쓰면 어떤 고객이 사라지는지 확인할 것
    //
    //   기대 결과: 둘 다 30행. 후기를 쓴 4명만 0 이 아닌 값
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5. 고객별 후기 수 — 서브쿼리 vs 조인 (각 30행)")
    void q5_reviewCountPerCustomer() {
        // 여기에 작성: (a) select 절 상관 서브쿼리

        // 여기에 작성: (b) leftJoin + groupBy

    }

    // -----------------------------------------------------------------
    // 문제 6.
    //   자기가 속한 카테고리의 평균가보다 비싼 상품을 조회하십시오.
    //
    //   요구사항
    //     - 상관 서브쿼리를 쓸 것
    //     - 별칭을 분리해야 하는지 아닌지 스스로 판단하십시오.
    //       힌트는 7-4 절의 표에 있습니다.
    //       "바깥 행을 참조하는 경로" 와 "집계 대상" 이 서로 다른 역할임에 주목.
    //     - category.id, price 내림차순 정렬
    //
    //   기대 결과: 18건
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 6. 자기 카테고리 평균가보다 비싼 상품 (18건)")
    void q6_aboveCategoryAverage() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 7.
    //   "카테고리별 최고가 상품" 을 7-7 절의 우회 ① 과 ② 두 방식으로 구현하십시오.
    //
    //   요구사항
    //     (a) 우회 ① — 상관 서브쿼리 한 방
    //     (b) 우회 ② — 쿼리 2회 + 애플리케이션에서 BooleanBuilder 로 조합
    //     - 두 결과가 같은지 확인
    //     - 콘솔 로그에서 (a) 와 (b) 각각 SQL 이 몇 번 나가는지 세어 볼 것
    //
    //   기대 결과: 둘 다 12건. (a) 는 쿼리 1개, (b) 는 쿼리 2개
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 7. 카테고리별 최고가 상품 — 우회 ① vs ② (각 12건)")
    void q7_maxPricePerCategory() {
        // 여기에 작성: (a) 상관 서브쿼리

        // 여기에 작성: (b) 쿼리 2회 + 조합

    }
}
