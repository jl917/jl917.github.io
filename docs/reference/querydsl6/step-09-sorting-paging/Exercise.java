package com.example.shop.step09;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.querydsl.jpa.impl.JPAQuery;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;

/**
 * Step 09 — 정렬과 페이징 : 연습문제 7문제
 *
 * 규칙
 *   - 답이 맞아도 생성 SQL 이 다르면 틀린 것입니다. 반드시 콘솔 로그를 확인하십시오.
 *   - 주석의 "생성 SQL 을 여기에 적으십시오" 칸을 비워 두지 마십시오.
 *     그 칸을 채우는 것이 이 스텝의 학습 목표 자체입니다.
 *   - 정답은 Solution.java 에 있습니다. 반드시 먼저 풀어 보고 여십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 문제 1. 다중 정렬
    // =================================================================
    // VIP 등급 고객만 조회하되,
    //   1순위: 포인트 내림차순
    //   2순위: 이름 오름차순
    // 으로 정렬하십시오.
    //
    // 확인할 것:
    //   - 생성 SQL 의 order by 절에 컬럼이 몇 개, 어떤 순서로 나오는가
    //   - where 절이 order by 앞에 오는가
    // =================================================================
    @Test
    @DisplayName("문제 1 — VIP 를 포인트 내림차순, 이름 오름차순으로")
    void problem1() {
        // 여기에 작성:
        List<Customer> result = null;

        // 생성 SQL 을 여기에 적으십시오:
        //   select ...
        //   from ...
        //   where ...
        //   order by ...

        result.forEach(c ->
                System.out.printf("  %s %dp%n", c.getName(), c.getPoints()));
    }

    // =================================================================
    // 문제 2. NULL 정렬
    // =================================================================
    // 전체 고객을 전화번호 오름차순으로 정렬하되,
    // 전화번호가 NULL 인 3명을 "맨 뒤" 로 보내십시오.
    //
    // 이 문제의 본체는 코드가 아니라 생성 SQL 입니다.
    //   - MySQL 8 에는 NULLS LAST 문법이 없습니다.
    //   - 그렇다면 Hibernate 는 무엇을 대신 내보냅니까?
    //   - 그 SQL 은 phone 컬럼에 인덱스가 있을 때 그 인덱스를 쓸 수 있습니까?
    // =================================================================
    @Test
    @DisplayName("문제 2 — phone NULL 3명을 맨 뒤로")
    void problem2() {
        // 여기에 작성:
        List<Customer> result = null;

        // 생성 SQL 을 여기에 적으십시오 (order by 절 전문):
        //   order by ...
        //
        // 이 정렬이 인덱스를 탈 수 있습니까? 답과 이유를 한 문장으로:
        //   →

        result.stream().skip(result.size() - 5).forEach(c ->
                System.out.printf("  %s phone=%s%n", c.getName(), c.getPhone()));
    }

    // =================================================================
    // 문제 3. Page + 분리된 count 쿼리 + PageableExecutionUtils
    // =================================================================
    // 배송 완료(DELIVERED) 주문의 Page<Order> 를 반환하는 메서드를 완성하십시오.
    //
    // 요구사항
    //   (a) 콘텐츠 쿼리는 orderDate 내림차순 + orderId 내림차순으로 정렬
    //   (b) count 쿼리를 별도 JPAQuery<Long> 으로 "직접" 작성 (orderBy 없이)
    //   (c) PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne) 로 반환
    //
    // ⚠️ fetchCount() 나 fetchResults() 를 쓰면 오답입니다. deprecated 입니다.
    // ⚠️ PageableExecutionUtils 의 import 패키지는 Spring Data 버전마다 다릅니다.
    //    클래스명만 타이핑하고 IDE 자동완성을 쓰십시오.
    // =================================================================
    @Test
    @DisplayName("문제 3 — count 쿼리를 직접 작성한 Page")
    void problem3() {
        Page<Order> page = searchDeliveredOrders(PageRequest.of(0, 10));

        System.out.printf("  content=%d  total=%d  totalPages=%d%n",
                page.getContent().size(), page.getTotalElements(), page.getTotalPages());
    }

    private Page<Order> searchDeliveredOrders(Pageable pageable) {
        // 여기에 작성:
        //   ① 콘텐츠 쿼리
        //   ② count 쿼리 (JPAQuery<Long> 로 "실행하지 않고" 만들어 두기)
        //   ③ PageableExecutionUtils 로 감싸 반환

        return null;
    }

    // =================================================================
    // 문제 4. count 쿼리 생략 확인 (관찰 문제)
    // =================================================================
    // 문제 3 에서 만든 searchDeliveredOrders 를 세 가지 Pageable 로 호출하고,
    // 각각 count SQL 이 로그에 찍히는지 관찰하십시오.
    //
    //   ① PageRequest.of(0, 10)     →  count SQL 이 찍힙니까?  →
    //   ② PageRequest.of(0, 1000)   →  count SQL 이 찍힙니까?  →
    //   ③ 마지막 페이지 (직접 계산) →  count SQL 이 찍힙니까?  →
    //
    // 그리고 ②와 ③에서 total 값이 어떻게 계산됐는지 설명하십시오.
    //   ② total = ?
    //   ③ total = ?
    //
    // ⚠️ 이 문제는 assertThat 으로 검증할 수 없습니다. 콘솔을 눈으로 보십시오.
    // =================================================================
    @Test
    @DisplayName("문제 4 — count 쿼리가 생략되는 조건을 로그로 확인")
    void problem4() {
        System.out.println("=== ① PageRequest.of(0, 10) ===");
        // 여기에 작성:

        System.out.println("=== ② PageRequest.of(0, 1000) ===");
        // 여기에 작성:

        System.out.println("=== ③ 마지막 페이지 ===");
        // 여기에 작성:
    }

    // =================================================================
    // 문제 5. 함수 정렬을 인덱스가 탈 수 있는 형태로
    // =================================================================
    // 아래 코드는 컴파일도 되고 결과도 정확하지만, 100만 행에서 1.284초가 걸립니다.
    //
    //   queryFactory.selectFrom(order)
    //           .orderBy(order.orderDate.year().desc(), order.orderId.desc())
    //           .limit(20)
    //           .fetch();
    //
    // (a) 이 코드를 그대로 실행해 생성 SQL 을 기록하십시오.
    // (b) 인덱스를 탈 수 있는 형태로 고치고 생성 SQL 을 기록하십시오.
    // (c) 두 SQL 의 order by 절만 나란히 적고, 왜 (a) 가 인덱스를 못 타는지 쓰십시오.
    // =================================================================
    @Test
    @DisplayName("문제 5 — year() 정렬을 인덱스가 타게 고치기")
    void problem5() {
        // (a) 나쁜 코드 — 여기에 작성:
        List<Order> bad = null;

        // (a) 생성 SQL 의 order by 절:
        //   order by

        // (b) 고친 코드 — 여기에 작성:
        List<Order> good = null;

        // (b) 생성 SQL 의 order by 절:
        //   order by

        // (c) 왜 (a) 는 인덱스를 못 탑니까? 두 문장으로:
        //   →

        System.out.printf("  bad=%d good=%d%n", bad.size(), good.size());
    }

    // =================================================================
    // 문제 6. 키셋(커서) 페이징
    // =================================================================
    // order_id 내림차순 커서 기반 페이징 메서드를 완성하십시오.
    //
    // 요구사항
    //   - lastSeenOrderId 가 null 이면 첫 페이지 (조건 없음)
    //   - null 이 아니면 그보다 작은 order_id 만
    //   - order_id 내림차순, size 건
    //
    // 💡 힌트: where(null) 이 조건 무시로 동작한다는 Step 04 의 성질을 쓰면
    //    if 분기로 쿼리를 두 벌 만들지 않아도 됩니다.
    //
    // 그리고 첫 호출과 두 번째 호출의 생성 SQL 차이를 기록하십시오.
    // =================================================================
    @Test
    @DisplayName("문제 6 — 키셋 페이징")
    void problem6() {
        List<Order> first = nextPage(null, 20);
        Long cursor = first.get(first.size() - 1).getOrderId();
        List<Order> second = nextPage(cursor, 20);

        // 첫 호출의 생성 SQL:
        //   select ... from orders o1_0
        //   ...

        // 두 번째 호출의 생성 SQL:
        //   select ... from orders o1_0
        //   ...

        // 두 SQL 의 차이를 한 문장으로:
        //   →

        System.out.printf("  1페이지 %d건 (커서=%d) → 2페이지 %d건%n",
                first.size(), cursor, second.size());
    }

    private List<Order> nextPage(Long lastSeenOrderId, int size) {
        // 여기에 작성:
        return null;
    }

    // =================================================================
    // 문제 7. 타이브레이커
    // =================================================================
    // 아래 페이징 코드에는 결함이 있습니다.
    //
    //   queryFactory.selectFrom(order)
    //           .orderBy(order.orderDate.desc())
    //           .offset(pageable.getOffset())
    //           .limit(pageable.getPageSize())
    //           .fetch();
    //
    // (a) 결함을 고치십시오. (코드는 한 줄 수정이면 됩니다)
    // (b) 왜 필요한지 두 문장으로 설명하십시오.
    //     "행이 중복되거나 누락된다" 까지만 쓰면 절반입니다.
    //     왜 실행마다 순서가 달라질 수 있는가를 함께 쓰십시오.
    // =================================================================
    @Test
    @DisplayName("문제 7 — 타이브레이커 추가")
    void problem7() {
        Pageable pageable = PageRequest.of(0, 10);

        // (a) 고친 코드 — 여기에 작성:
        List<Order> page1 = null;

        // (b) 왜 필요합니까? 두 문장으로:
        //   →
        //   →

        System.out.printf("  %s%n",
                page1.stream().limit(5).map(o -> String.valueOf(o.getOrderId())).toList());
    }

    // =================================================================
    // 참고 — 문제를 풀 때 쓸 수 있는 상수
    // =================================================================
    //   Grade.VIP / Grade.GOLD / Grade.SILVER / Grade.BRONZE
    //   OrderStatus.PENDING / PAID / SHIPPED / DELIVERED / CANCELLED
    //
    //   customers 30명 (VIP 4 / GOLD 9 / SILVER 8 / BRONZE 9, phone NULL 3명)
    //   orders 600건 (DELIVERED 214건)
    // =================================================================

    @SuppressWarnings("unused")
    private void hintsForUnusedImports() {
        // import 가 회색으로 뜨는 것을 막기 위한 자리입니다. 답안 작성에는 필요 없습니다.
        Grade g = Grade.VIP;
        OrderStatus s = OrderStatus.DELIVERED;
        JPAQuery<Long> q = queryFactory.select(order.count()).from(order);
        System.out.println(g + " " + s + " " + q);
        System.out.println(customer.getType() + " " + em.isOpen());
    }
}
