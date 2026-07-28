package com.example.shop.step06;

import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.QEmployee;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QEmployee.employee;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QPayment.payment;

/**
 * Step 06 — 조인 : 연습문제 7문제
 *
 * 각 문제의 "여기에 작성:" 아래에 코드를 채워 넣고 실행하십시오.
 * 답이 맞았는지보다 "생성 SQL 이 기대한 모양인지" 를 먼저 확인하십시오.
 * 특히 문제 3과 문제 5는 SQL 을 보지 않으면 푼 의미가 없습니다.
 *
 * 정답은 Solution.java 에 있습니다. 먼저 스스로 풀어보십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    /** 지금까지 나간 쿼리 개수. 문제 4에서 씁니다. */
    private long queryCount() {
        Statistics stats = em.unwrap(Session.class)
                             .getSessionFactory()
                             .getStatistics();
        return stats.getPrepareStatementCount();
    }

    /** 영속성 컨텍스트를 비웁니다. 이걸 안 하면 1차 캐시 때문에 쿼리 개수가 틀어집니다. */
    private void clearContext() {
        em.flush();
        em.clear();
    }

    // =================================================================
    // 문제 1.
    //   orders 와 customers 를 조인해서
    //   "서울에 사는 고객의 주문" 중 금액 상위 5건을 조회하세요.
    //
    //   요구사항:
    //     - 출력: 주문번호, 고객명, 금액
    //     - customer.city 가 "서울" 인 고객만
    //     - totalAmount 내림차순 상위 5건
    //     - 생성 SQL 이 "join customers c1_0 on ..." 인지 확인하십시오.
    //       (세타 조인으로 풀지 마십시오)
    //
    //   MySQL8 코스 Step 07 연습문제 1번과 같은 문제입니다.
    // =================================================================

    @Test
    @DisplayName("문제 1 — 서울 고객의 고액 주문 상위 5건")
    void ex1() {
        // 여기에 작성:

    }

    // =================================================================
    // 문제 2.
    //   "고객 1번의 주문 수와 주문 총액" 을 구하되,
    //   orderItems 를 조인한 버전과 안 한 버전을 "둘 다" 작성해 숫자를 비교하세요.
    //
    //   (a) orderItems 조인 없이 — 정답
    //   (b) orderItems 를 조인 — 숫자가 어떻게 변하나?
    //   (c) countDistinct 를 써 보면 무엇이 고쳐지고 무엇이 안 고쳐지나?
    //   (d) 왜 그런지, 그리고 (b) 에서도 올바른 총액을 얻으려면 어떻게 해야 하는지
    //       주석으로 설명하고 그 코드를 작성하세요.
    //
    //   힌트: 조인을 추가하는 것은 "컬럼 추가" 가 아니라 "행의 단위를 바꾸는 일" 입니다.
    // =================================================================

    @Test
    @DisplayName("문제 2 — fan-out 을 숫자로 확인하고 고치기")
    void ex2() {
        // (a) orderItems 조인 없이 — 여기에 작성:


        // (b) orderItems 조인 추가 — 여기에 작성:


        // (c) countDistinct 버전 — 여기에 작성:


        // (d) 올바른 총액을 얻는 버전 — 여기에 작성:

    }
    // (d) 설명:
    //     →
    //     →

    // =================================================================
    // 문제 3. (on vs where)
    //   "모든 고객 + 그 고객의 배송완료(DELIVERED) 주문" 을 조회하는 쿼리를
    //   조건을 on 에 둔 버전과 where 에 둔 버전으로 각각 작성하고 건수를 비교하세요.
    //
    //   요구사항:
    //     - customers 를 기준(from)으로 orders 를 leftJoin
    //     - 조건: order.status = DELIVERED
    //     - 각각 결과 "건수" 를 출력하십시오
    //
    //   기대: on 버전 258건, where 버전 240건 (MySQL8 코스 7-4 절과 동일한 숫자)
    //         차이 18은 무엇을 의미할까요? 주석으로 답하세요.
    //
    //   ★ 조건을 BooleanExpression 변수로 한 번만 선언하고
    //     .on(cond) 와 .where(cond) 에 각각 넘겨 보십시오.
    //     둘 다 컴파일된다는 사실이 이 문제의 진짜 교훈입니다.
    // =================================================================

    @Test
    @DisplayName("문제 3 — on 258건 vs where 240건")
    void ex3() {
        // (a) 조건을 on 에 — 여기에 작성:


        // (b) 조건을 where 에 — 여기에 작성:

    }
    // 차이 18의 의미:
    //     →

    // =================================================================
    // 문제 4.
    //   주문 20건을 조회하면서 각 주문의 고객 이름을 출력하세요.
    //   (a) fetch join 없이  (b) fetch join 으로
    //   각각 쿼리 개수를 세어 비교하십시오.
    //
    //   기대: (a) 21개 (1 + 20)   (b) 1개
    //
    //   clearContext() 와 queryCount() 는 이미 호출해 두었습니다.
    //   그 사이를 채우십시오.
    // =================================================================

    @Test
    @DisplayName("문제 4 — N+1 을 쿼리 개수로 증명")
    void ex4() {
        // (a) fetch join 없이
        clearContext();
        long before1 = queryCount();

        // 여기에 작성:

        System.out.println("(a) 쿼리 수 = " + (queryCount() - before1));

        // (b) fetch join 으로
        clearContext();
        long before2 = queryCount();

        // 여기에 작성:

        System.out.println("(b) 쿼리 수 = " + (queryCount() - before2));
    }

    // =================================================================
    // 문제 5.
    //   아래 코드는 실행되고 결과도 맞지만 로그에 경고가 찍히고 SQL 에 limit 이 없습니다.
    //
    //   (a) 먼저 그대로 실행해 경고 코드와 SQL 을 눈으로 확인하십시오.
    //   (b) 원인을 주석으로 설명하십시오.
    //   (c) 세 가지 방법으로 각각 고치십시오 (ex5Fix1 / ex5Fix2 / ex5Fix3).
    //
    //   힌트: ① 배치 페치  ② ToOne 만 fetch join  ③ ID 페이징 후 in
    // =================================================================

    @Test
    @DisplayName("문제 5 (a) — 문제 코드를 그대로 실행해 경고를 확인")
    void ex5Broken() {
        clearContext();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .offset(0)
                .limit(20)
                .fetch();

        System.out.println("결과 = " + orders.size() + "건");

        // 콘솔에서 확인할 것:
        //   - 경고 코드:
        //   - SQL 에 limit 이 있나요?
    }
    // (b) 원인:
    //     →
    //     →

    @Test
    @DisplayName("문제 5 (c-1) — 처방 ① 배치 페치")
    void ex5Fix1() {
        clearContext();
        // 여기에 작성:

    }

    @Test
    @DisplayName("문제 5 (c-2) — 처방 ② ToOne 만 fetch join")
    void ex5Fix2() {
        clearContext();
        // 여기에 작성:

    }

    @Test
    @DisplayName("문제 5 (c-3) — 처방 ③ 2단계 조회")
    void ex5Fix3() {
        clearContext();
        // 1단계: ID 만 페이징으로 — 여기에 작성:


        // 2단계: 그 ID 들로 fetch join — 여기에 작성:

    }

    // =================================================================
    // 문제 6.
    //   Employee 셀프 조인으로 "부하 직원이 한 명도 없는 사원(말단)" 을 안티 조인으로 찾으세요.
    //
    //   요구사항:
    //     - 출력: employee_id, 이름, 직급
    //     - 결과는 10명입니다 (9~18번 사원)
    //
    //   힌트: 6-11 절은 leftJoin(employee.manager, manager) 였습니다.
    //         이번에는 조인 방향이 "반대" 입니다.
    //         자신의 employee_id 가 누군가의 manager_id 로 쓰이지 않는 사원을 찾는 것입니다.
    //
    //         그런데 employee.manager 라는 연관 경로로는 그 방향을 표현할 수 없습니다.
    //         (그 경로는 "나의 관리자" 방향입니다)
    //         Employee 에 @OneToMany subordinates 매핑이 없으므로
    //         6-6 절의 "연관 없는 on 조인" 이 필요합니다.
    // =================================================================

    @Test
    @DisplayName("문제 6 — 부하가 없는 사원 (10명)")
    void ex6() {
        QEmployee subordinate = new QEmployee("subordinate");

        // 여기에 작성:

    }

    // =================================================================
    // 문제 7.
    //   orders 를 payments 와 leftJoin 해서 "결제가 아예 없는 주문" 을 안티 조인으로 찾고,
    //   그 개수가 PENDING 주문 수와 일치하는지 검산하세요.
    //
    //   요구사항:
    //     - 안티 조인으로 센 개수와 PENDING 주문 수를 나란히 출력
    //     - 양쪽 다 60건이어야 합니다
    //     - isNull() 을 payment 의 "어느 필드" 에 걸어야 할까요? 이유를 주석으로.
    // =================================================================

    @Test
    @DisplayName("문제 7 — 결제 없는 주문 = PENDING 주문 (60 = 60)")
    void ex7() {
        // 여기에 작성:

    }
    // isNull() 을 그 필드에 건 이유:
    //     →
}
