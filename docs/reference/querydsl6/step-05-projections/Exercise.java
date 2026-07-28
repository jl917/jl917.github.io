package com.example.shop.step05;

import com.querydsl.core.Tuple;
import com.querydsl.core.annotations.QueryProjection;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
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

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QReview.review;

/**
 * Step 05 — 프로젝션과 DTO : 연습문제 6문제
 *
 * 각 문제의 "여기에 작성:" 아래에 코드를 채워 넣고 실행하십시오.
 * 답이 맞았는지보다 **생성 SQL 이 기대한 모양인지** 를 먼저 확인하십시오.
 * 정답은 Solution.java 에 있습니다. 먼저 스스로 풀어보십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 문제 1.
    //   customers 에서 GOLD 등급 고객의 "이메일만" 뽑아 List<String> 으로 받으세요.
    //
    //   요구사항:
    //     - Tuple 도 DTO 도 쓰지 마십시오. List<String> 이 바로 나와야 합니다.
    //     - 생성 SQL 의 select 절에 email 컬럼 "하나만" 나오는지 확인하십시오.
    //     - GOLD 는 9명입니다.
    // =================================================================

    @Test
    @DisplayName("문제 1 — GOLD 고객의 이메일만")
    void ex1() {
        // 여기에 작성:

    }

    // =================================================================
    // 문제 2.
    //   (a) Tuple 로 포인트 상위 5명의 "이름과 포인트" 를 조회하고 값을 꺼내 출력하세요.
    //   (b) 같은 결과를 Projections.constructor 로 다시 작성하세요.
    //       DTO 는 아래 NamePointDto 를 쓰십시오.
    //   (c) 왜 (a) 의 Tuple 을 리포지토리 밖으로 반환하면 안 되는지
    //       메서드 아래 주석으로 3줄 이내로 적으세요.
    // =================================================================

    @Test
    @DisplayName("문제 2 — Tuple 과 constructor 를 나란히")
    void ex2() {
        // (a) Tuple 버전 — 여기에 작성:


        // (b) Projections.constructor 버전 — 여기에 작성:

    }
    // (c) Tuple 을 밖으로 내보내면 안 되는 이유:
    //     →
    //     →

    // =================================================================
    // 문제 3.
    //   아래 코드는 실행은 되지만 userName 과 homeCity 가 "둘 다" null 로 나옵니다.
    //   실행해서 직접 확인한 뒤 고치십시오.
    //
    //   제약: 생성 SQL 을 바꾸지 마십시오.
    //         (select 절은 c1_0.name, c1_0.city 그대로여야 합니다)
    //         DTO 클래스도 고치지 마십시오.
    //
    //   힌트: 함정을 하나만 고치고 안심하면 안 됩니다. 필드가 두 개입니다.
    // =================================================================

    @Test
    @DisplayName("문제 3 — 조용한 null 을 고치기")
    void ex3() {
        // ↓ 이 코드가 문제입니다. 먼저 그대로 실행해 null 을 눈으로 확인하십시오.
        List<WrongNameDto> broken = queryFactory
                .select(Projections.fields(WrongNameDto.class,
                        customer.name,
                        customer.city))
                .from(customer)
                .fetch();
        broken.stream().limit(3).forEach(System.out::println);

        // 여기에 고친 코드를 작성:

    }

    // =================================================================
    // 문제 4.
    //   OrderSummaryDto 를 @QueryProjection 으로 만들고,
    //   총액 상위 5건의 주문을 조회하세요.
    //
    //   요구사항:
    //     - DTO 시그니처: (Long orderId, String customerName, String shippingCity, BigDecimal totalAmount)
    //     - customers 와 조인해야 customerName 을 얻을 수 있습니다.
    //     - 정렬은 totalAmount 내림차순.
    //
    //   주의: customerName 과 shippingCity 가 "둘 다 String" 입니다.
    //         이 사실이 문제 5로 이어집니다.
    //
    //   DTO 는 아래 OrderSummaryDto 를 그대로 쓰고, Q타입 이름은
    //   QExercise_OrderSummaryDto 입니다 (중첩 클래스이므로 바깥 클래스명이 접두사로 붙습니다).
    // =================================================================

    @Test
    @DisplayName("문제 4 — @QueryProjection 으로 주문 요약 DTO")
    void ex4() {
        // 여기에 작성:

    }

    // =================================================================
    // 문제 5.
    //   문제 4의 DTO 파라미터 순서를
    //     (Long orderId, String shippingCity, String customerName, BigDecimal totalAmount)
    //   로 바꾸면 어떤 일이 생기는지 두 방식에서 각각 확인하세요.
    //
    //   (a) Projections.constructor 버전 — 아래 SwappedSummaryDto 를 쓰고
    //       쿼리의 표현식 순서는 (order.id, customer.name, order.shippingCity, order.totalAmount)
    //       로 "그대로" 두십시오. 실행 결과를 관찰하십시오.
    //   (b) @QueryProjection 버전 — 문제 4의 OrderSummaryDto 생성자 순서를 실제로 바꿔 보고
    //       (바꾼 뒤 compileJava 를 다시 돌려야 합니다) 컴파일 에러가 나는지 확인하십시오.
    //   (c) 두 결과의 차이와, @QueryProjection 으로도 못 막는 경우가 무엇인지
    //       주석으로 설명하세요.
    // =================================================================

    @Test
    @DisplayName("문제 5 — 순서 뒤바뀜을 두 방식에서 비교")
    void ex5() {
        // (a) Projections.constructor 버전 — 여기에 작성:


        // (b) @QueryProjection 버전은 DTO 를 실제로 고쳐서 확인하십시오.
        //     고친 뒤 ./gradlew compileTestJava 를 돌립니다.
    }
    // (c) 차이와 한계:
    //     →
    //     →
    //     →

    // =================================================================
    // 문제 6.
    //   ExpressionUtils.as 와 스칼라 서브쿼리로
    //   CustomerReviewDto(고객명, 그 고객이 쓴 후기 수) 를 조회하세요.
    //
    //   요구사항:
    //     - 고객 30명 "전원" 이 결과에 나와야 합니다.
    //     - 후기를 한 번도 안 쓴 고객은 reviewCount 가 0 이어야 합니다 (null 아님).
    //     - 후기를 쓴 고객은 4명입니다. 나머지 26명은 0 이어야 합니다.
    //     - reviewCount 내림차순, 같으면 고객 id 오름차순으로 정렬하십시오.
    //
    //   힌트: 서브쿼리에는 .as() 메서드가 없습니다.
    //         count() 는 매칭이 없으면 무엇을 돌려줄까요?
    // =================================================================

    @Test
    @DisplayName("문제 6 — 서브쿼리 결과를 DTO 필드로")
    void ex6() {
        // 여기에 작성:

    }

    // =================================================================
    // 문제에서 쓰는 DTO 들
    // =================================================================

    /** 문제 2 (b) 용 */
    public static class NamePointDto {
        private final String name;
        private final Integer points;

        public NamePointDto(String name, Integer points) {
            this.name = name;
            this.points = points;
        }

        @Override public String toString() {
            return "NamePointDto(name=" + name + ", points=" + points + ")";
        }
    }

    /** 문제 3 용 — 고치지 마십시오 */
    public static class WrongNameDto {
        private String userName;
        private String homeCity;

        @Override public String toString() {
            return "WrongNameDto(userName=" + userName + ", homeCity=" + homeCity + ")";
        }
    }

    /** 문제 4 용 */
    public static class OrderSummaryDto {
        private final Long orderId;
        private final String customerName;
        private final String shippingCity;
        private final BigDecimal totalAmount;

        @QueryProjection
        public OrderSummaryDto(Long orderId, String customerName,
                               String shippingCity, BigDecimal totalAmount) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.shippingCity = shippingCity;
            this.totalAmount = totalAmount;
        }

        @Override public String toString() {
            return "OrderSummaryDto(orderId=" + orderId + ", customerName=" + customerName
                 + ", shippingCity=" + shippingCity + ", totalAmount=" + totalAmount + ")";
        }
    }

    /** 문제 5 (a) 용 — 파라미터 순서가 (city, name) 으로 뒤집혀 있습니다 */
    public static class SwappedSummaryDto {
        private final Long orderId;
        private final String customerName;
        private final String shippingCity;
        private final BigDecimal totalAmount;

        public SwappedSummaryDto(Long orderId, String shippingCity,
                                 String customerName, BigDecimal totalAmount) {
            this.orderId = orderId;
            this.shippingCity = shippingCity;
            this.customerName = customerName;
            this.totalAmount = totalAmount;
        }

        @Override public String toString() {
            return "SwappedSummaryDto(orderId=" + orderId + ", customerName=" + customerName
                 + ", shippingCity=" + shippingCity + ", totalAmount=" + totalAmount + ")";
        }
    }

    /** 문제 6 용 */
    public static class CustomerReviewDto {
        private String userName;
        private Long reviewCount;

        @Override public String toString() {
            return "CustomerReviewDto(userName=" + userName + ", reviewCount=" + reviewCount + ")";
        }
    }
}
