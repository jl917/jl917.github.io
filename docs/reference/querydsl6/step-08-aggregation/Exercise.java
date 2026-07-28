package com.example.shop.step08;

import com.example.shop.entity.OrderStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
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
import java.util.Map;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.querydsl.core.group.GroupBy.groupBy;
import static com.querydsl.core.group.GroupBy.list;

/**
 * Step 08 — 집계와 그룹핑 : 연습문제 7문제
 *
 * <p>각 문제의 "여기에 작성:" 아래를 채우십시오. 정답은 Solution.java 에 있습니다.
 *
 * <p><b>건수가 맞아도 생성 SQL 이 의도한 모양이 아니면 틀린 것입니다.</b>
 * 특히 문제 4(coalesce)와 문제 5(group by 부재)는 로그를 봐야만 확인할 수 있습니다.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    /** 문제 5 에서 쓸 DTO. */
    public record OrderDto(Long orderId, BigDecimal totalAmount) {}

    // -----------------------------------------------------------------
    // 문제 1.
    //   도시별 고객 수와 평균 포인트를 조회하십시오.
    //
    //   요구사항
    //     - 정렬: 고객 수 내림차순, 같으면 도시 오름차순
    //     - 집계 표현식을 변수로 뽑아 두고 tuple.get(변수) 로 꺼낼 것
    //       (Tuple.get(int) 는 쓰지 마십시오)
    //     - avg 를 어떤 타입으로 받아야 하는지 주의
    //
    //   기대 결과: 8행. 서울 10명이 첫 행
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 1. 도시별 고객 수와 평균 포인트 (8행)")
    void q1_customersByCity() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 2.
    //   전체 고객 수와 전화번호가 있는 고객 수를 한 쿼리로 조회하십시오.
    //
    //   요구사항
    //     - 두 값을 하나의 Tuple 로 받을 것
    //     - 생성 SQL 에서 두 count 가 어떻게 다르게 번역되는지 확인
    //     - 왜 값이 다른지를 아래에 주석으로 직접 쓰십시오
    //
    //   기대 결과: 30 / 27
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2. 전체 고객 수 vs 전화번호 있는 고객 수 (30 / 27)")
    void q2_countWithNull() {
        // 여기에 작성:

        // 왜 다른가? (직접 쓰십시오)
        //

    }

    // -----------------------------------------------------------------
    // 문제 3.
    //   취소(CANCELLED)를 제외한 주문 합계가 4천만 원 이상인 고객을 조회하십시오.
    //
    //   요구사항
    //     - customer_id, 주문 건수, 합계를 select
    //     - 정렬: 합계 내림차순
    //     - "취소 제외" 조건과 "합계 4천만 이상" 조건 중
    //       어느 것이 where 이고 어느 것이 having 인지 스스로 판단할 것
    //     - 판단 근거를 주석으로 쓰십시오
    //
    //   기대 결과: 8행
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 3. 합계 4천만 이상 고객 (8행)")
    void q3_whereVsHaving() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 4.
    //   shipping_city 가 '제주' 인 주문의 합계를 구하십시오.
    //
    //   요구사항
    //     - 해당 주문이 0건이지만 결과가 null 이 아니라 0 이어야 함
    //     - 생성 SQL 에 coalesce 가 들어갔는지 로그로 확인할 것
    //     - (참고) 자바 Optional 로 처리하면 SQL 에는 coalesce 가 없습니다.
    //       둘 다 유효하지만 다른 답입니다. 어느 쪽을 골랐는지 주석으로 밝히십시오.
    //
    //   기대 결과: 0
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 4. 0건일 때 sum 을 0 으로 (coalesce)")
    void q4_coalesce() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 5.
    //   고객별 주문 목록을 Map<Long, List<OrderDto>> 로 받으십시오.
    //
    //   요구사항
    //     - GroupBy.transform 을 쓸 것
    //     - transform 은 select() 없이 from() 으로 시작합니다
    //     - 실행 후 로그에서 group by 를 찾아보십시오. 없습니다.
    //       왜 없는지를 주석으로 쓰십시오.
    //     - 네트워크로 몇 행이 넘어오는지 생각해 보십시오
    //
    //   기대 결과: Map 크기 30, 각 고객의 리스트 크기 20
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5. transform 으로 Map 만들기 (Map 크기 30)")
    void q5_transform() {
        // 여기에 작성:

        // 생성 SQL 에 group by 가 없는 이유는?
        //

    }

    // -----------------------------------------------------------------
    // 문제 6.
    //   문제 5 와 같은 조인에서 "고객별 주문 건수" 만 필요하다면
    //   어떻게 써야 하는지 작성하십시오.
    //
    //   요구사항
    //     - DB 의 groupBy 를 쓸 것
    //     - 생성 SQL 에 group by 가 있는지 확인
    //     - 문제 5 와 비교해 네트워크로 넘어오는 행 수를 주석으로 정리하십시오
    //
    //   기대 결과: 30행. (문제 5 는 600행이 넘어오고, 이 문제는 30행)
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 6. DB groupBy 로 건수만 (30행)")
    void q6_dbGroupBy() {
        // 여기에 작성:

        // 넘어오는 행 수 비교:
        //   문제 5 (transform)  →
        //   문제 6 (groupBy)    →

    }

    // -----------------------------------------------------------------
    // 문제 7.
    //   고객 → 주문 → 주문상세를 모두 조인한 상태에서
    //   "고객별 주문 건수" 를 구하십시오.
    //
    //   요구사항 — 세 버전을 모두 작성할 것
    //     (a) count() 로 써서 틀린 답(40)을 직접 확인
    //     (b) countDistinct() 로 고쳐서 20 을 얻기
    //     (c) 불필요한 조인을 제거해서 20 을 얻기
    //     - (a) 를 건너뛰지 마십시오. 틀린 답을 직접 만들어 보는 것이 목적입니다.
    //     - 세 버전의 생성 SQL 을 나란히 비교하십시오
    //
    //   기대 결과: (a) 40, (b) 20, (c) 20
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 7. fan-out 과 count — 세 버전 (40 / 20 / 20)")
    void q7_fanOut() {
        // 여기에 작성: (a) count() — 틀린 답

        // 여기에 작성: (b) countDistinct() — 고친 답

        // 여기에 작성: (c) 조인 제거 — 더 나은 답

    }
}
