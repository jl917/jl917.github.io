package com.example.shop.step06;

import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.QEmployee;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
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

import java.math.BigDecimal;
import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QEmployee.employee;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QPayment.payment;

/**
 * Step 06 — 조인 : 연습문제 정답과 해설
 *
 * Exercise.java 를 스스로 풀어본 "뒤에" 열어보십시오.
 * 각 정답 위 주석에 기대 결과와 생성 SQL 이 적혀 있습니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    private long queryCount() {
        Statistics stats = em.unwrap(Session.class)
                             .getSessionFactory()
                             .getStatistics();
        return stats.getPrepareStatementCount();
    }

    private void clearContext() {
        em.flush();
        em.clear();
    }

    // =================================================================
    // 정답 1 — 서울 고객의 고액 주문 상위 5건
    // =================================================================
    //
    // 해설:
    //   연관 기반 조인 join(order.customer, customer) 을 씁니다.
    //   from(order, customer).where(order.customer.eq(customer)) 로 푼 세타 조인도
    //   결과는 같지만 생성 SQL 이 "from orders o1_0, customers c1_0" 형태가 되어
    //   문제의 요구를 만족하지 못합니다.
    //
    //   조건 customer.city.eq("서울") 은 where 에 둡니다.
    //   INNER JOIN 이므로 on 에 두든 where 에 두든 결과가 같지만,
    //   "이건 조인 조건이 아니라 필터" 라는 의도를 드러내려면 where 가 맞습니다.
    //   (LEFT JOIN 이었다면 위치에 따라 결과가 달라집니다 — 정답 3 참고)
    //
    //   customer.city 는 왼쪽(from)이 아니라 조인 대상 쪽 조건인데 where 여도 되는 이유는
    //   INNER JOIN 에서는 on 필터와 where 필터가 논리적으로 동등하기 때문입니다.
    //   위치가 결과를 바꾸는 것은 OUTER JOIN 뿐입니다.
    //
    // 생성 SQL:
    //   select o1_0.order_id, c1_0.name, o1_0.total_amount
    //   from orders o1_0
    //   join customers c1_0 on c1_0.customer_id = o1_0.customer_id
    //   where c1_0.city = ?
    //   order by o1_0.total_amount desc
    //   limit ?

    @Test
    @DisplayName("정답 1 — 서울 고객의 고액 주문 상위 5건")
    void sol1() {
        List<Tuple> result = queryFactory
                .select(order.id, customer.name, order.totalAmount)
                .from(order)
                .join(order.customer, customer)
                .where(customer.city.eq("서울"))
                .orderBy(order.totalAmount.desc())
                .limit(5)
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(order.id) + " | " + t.get(customer.name) + " | " + t.get(order.totalAmount)));
    }

    // =================================================================
    // 정답 2 — fan-out 을 숫자로 확인하고 고치기
    // =================================================================
    //
    // 해설:
    //   (a) 20건 / 24,300,000  ← 정답
    //   (b) 41건 / 49,860,000  ← orderItems 조인 한 줄로 2배가 됨
    //   (c) 20건 / 49,860,000  ← countDistinct 로 count 만 고쳐짐
    //
    //   왜 이런 일이 생기나:
    //     orders : order_items 는 1:N 입니다.
    //     주문 1건에 상품이 2개면 그 주문 행이 2번 복제됩니다.
    //     고객 1번의 주문 20건에 딸린 order_items 가 41건이므로 조인 결과는 41행이고,
    //     각 주문의 total_amount 가 그 주문의 상품 개수만큼 반복해서 더해집니다.
    //
    //     ★ 조인을 추가하는 것은 "컬럼을 추가하는 일" 이 아니라 "행의 단위를 바꾸는 일" 입니다.
    //       조인 전에는 한 행이 "주문 하나" 였고, 조인 후에는 "주문 상품 한 줄" 입니다.
    //       집계식은 그대로인데 집계 대상이 바뀐 것입니다.
    //
    //   왜 countDistinct 로는 부족한가:
    //     count(distinct order_id) 는 "서로 다른 주문의 개수" 이므로 맞습니다.
    //     그런데 sum(distinct total_amount) 는 안 됩니다 —
    //     서로 다른 주문인데 금액이 우연히 같으면 한 번만 더해집니다.
    //     count 는 DISTINCT 로 고쳐지지만 sum 은 DISTINCT 로 "더 크게" 망가집니다.
    //
    //   올바른 총액을 얻는 방법 두 가지:
    //     ① 집계 쿼리를 분리한다 — fan-out 이 없는 쿼리로 따로 구한다
    //     ② 행 단위에 맞는 집계식을 쓴다 — unitPrice * quantity 를 더한다
    //
    //     ②가 더 안전합니다. 집계식의 단위가 행의 단위와 같으면
    //     fan-out 자체가 문제가 되지 않기 때문입니다.
    //     ①은 "이 쿼리에서는 조인하지 않는다" 는 규칙에 의존하는데,
    //     나중에 누가 조인을 하나 추가하면 다시 깨집니다.

    @Test
    @DisplayName("정답 2 — 네 가지 버전을 나란히")
    void sol2() {
        // (a) 정답 — orderItems 조인 없음
        Tuple a = queryFactory
                .select(order.count(), order.totalAmount.sum())
                .from(order).join(order.customer, customer)
                .where(customer.id.eq(1L))
                .fetchOne();
        System.out.println("(a) 주문 " + a.get(order.count())
                         + " / 총액 " + a.get(order.totalAmount.sum()));
        // 20 / 24300000.00

        // (b) orderItems 조인 — 2배가 된다
        Tuple b = queryFactory
                .select(order.count(), order.totalAmount.sum())
                .from(order).join(order.customer, customer)
                .join(order.orderItems, orderItem)
                .where(customer.id.eq(1L))
                .fetchOne();
        System.out.println("(b) 주문 " + b.get(order.count())
                         + " / 총액 " + b.get(order.totalAmount.sum()));
        // 41 / 49860000.00

        // (c) countDistinct — count 만 고쳐진다
        Tuple c = queryFactory
                .select(order.countDistinct(), order.totalAmount.sum())
                .from(order).join(order.customer, customer)
                .join(order.orderItems, orderItem)
                .where(customer.id.eq(1L))
                .fetchOne();
        System.out.println("(c) 주문 " + c.get(order.countDistinct())
                         + " / 총액 " + c.get(order.totalAmount.sum()));
        // 20 / 49860000.00  ← sum 은 여전히 틀림

        // (d-1) 집계 쿼리 분리
        BigDecimal d1 = queryFactory
                .select(order.totalAmount.sum())
                .from(order)
                .where(order.customer.id.eq(1L))
                .fetchOne();
        System.out.println("(d-1) 총액 " + d1);   // 24300000.00

        // (d-2) 행 단위에 맞는 집계식 — 가장 안전
        BigDecimal d2 = queryFactory
                .select(orderItem.unitPrice.multiply(orderItem.quantity).sum())
                .from(order).join(order.orderItems, orderItem)
                .where(order.customer.id.eq(1L))
                .fetchOne();
        System.out.println("(d-2) 총액 " + d2);   // 24300000.00
    }

    // =================================================================
    // 정답 3 — on 258건 vs where 240건
    // =================================================================
    //
    // 해설:
    //   숫자는 MySQL8 코스 Step 07 의 7-4 절과 정확히 같습니다.
    //   같은 데이터, 같은 SQL 이니 당연합니다.
    //   "QueryDSL 은 SQL 을 만드는 도구일 뿐" 이라는 것이 여기서 체감됩니다.
    //
    //   (a) 조건을 on 에 → 258건
    //       status = DELIVERED 는 "오른쪽 행을 매칭할지 말지" 를 결정하는 데만 쓰입니다.
    //       배송완료 주문이 없는 고객도 LEFT JOIN 규칙에 따라 NULL 확장으로 한 줄 남습니다.
    //       240(배송완료 주문) + 18(배송완료 주문이 하나도 없는 고객) = 258
    //
    //   (b) 조건을 where 에 → 240건
    //       조인이 다 끝난 뒤 필터가 적용됩니다.
    //       NULL 확장 행의 status 는 NULL 이고 NULL = 'DELIVERED' 는 UNKNOWN 이라 탈락합니다.
    //       결국 배송완료 주문만 남아 240 — LEFT JOIN 이 INNER JOIN 으로 퇴화했습니다.
    //
    //   ★ 차이 18 = "배송완료 주문이 하나도 없는 고객 18명"
    //     이 18명은 (a) 에서는 order 컬럼이 전부 null 인 행으로 남고, (b) 에서는 사라집니다.
    //
    //   ★★ 이 문제의 진짜 교훈은 아래 DELIVERED 변수입니다.
    //     BooleanExpression 하나를 .on() 에 넘기든 .where() 에 넘기든 "둘 다 컴파일됩니다."
    //     조건을 변수로 빼서 재사용하는 흔한 리팩터링이
    //     258을 240으로 바꿔 놓는데 컴파일러도 IDE 도 아무 말을 하지 않습니다.
    //     이것이 이 코스가 말하는 "조용히 틀리는 코드" 입니다.
    //
    //   핵심 규칙: LEFT JOIN 에서 오른쪽 조건은 on 에, 왼쪽 조건은 where 에.
    //     "모든 고객 + 그들의 배송완료 주문(없으면 NULL)" 을 원하면 → on
    //     "배송완료 주문이 있는 고객만" 을 원하면 → where (또는 그냥 join)

    private static final BooleanExpression DELIVERED =
            order.status.eq(OrderStatus.DELIVERED);

    /**
     * 주의 — 건수를 셀 때 count(order.id) 를 쓰면 안 됩니다.
     * count(컬럼) 은 그 컬럼이 NULL 인 행을 세지 않으므로 NULL 확장 행 18개가 빠져
     * on 버전도 240 이 나옵니다. MySQL8 코스 7-3 절의 "COUNT(*) 함정" 을 거꾸로 뒤집은 형태입니다.
     * "행 수" 를 정확히 세려면 결과 리스트의 크기를 보거나 Wildcard.count 를 씁니다.
     */
    @Test
    @DisplayName("정답 3 — 조건 변수 하나를 on 과 where 에 각각 넘긴다")
    void sol3() {
        List<Tuple> onVersion = queryFactory
                .select(customer.id, customer.name, order.id, order.status)
                .from(customer)
                .leftJoin(customer.orders, order)
                .on(DELIVERED)
                .fetch();

        List<Tuple> whereVersion = queryFactory
                .select(customer.id, customer.name, order.id, order.status)
                .from(customer)
                .leftJoin(customer.orders, order)
                .where(DELIVERED)
                .fetch();

        System.out.println("on = " + onVersion.size() + "행, where = " + whereVersion.size() + "행");
        // 기대: on = 258행, where = 240행. 차이 18.

        long nullExpanded = onVersion.stream().filter(t -> t.get(order.id) == null).count();
        System.out.println("NULL 확장 행 = " + nullExpanded + "행");   // 18
        // 이 18행이 "배송완료 주문이 하나도 없는 고객" 입니다.
    }

    // =================================================================
    // 정답 4 — N+1 을 쿼리 개수로 증명
    // =================================================================
    //
    // 해설:
    //   (a) 21개 = 1(주문 목록) + 20(주문마다 고객 프록시 초기화)
    //   (b) 1개
    //
    //   @ManyToOne 은 LAZY 이므로 order.getCustomer() 는 프록시입니다.
    //   getName() 을 호출하는 순간 초기화되면서 SELECT 가 나갑니다.
    //   같은 고객이 여러 주문에 걸쳐 있으면 1차 캐시 덕분에 쿼리가 줄어들 수도 있습니다 —
    //   20건이 20명의 서로 다른 고객이 아니면 21개보다 적게 나올 수 있습니다.
    //   그래서 clearContext() 로 시작하는 것이 중요합니다.
    //
    //   fetchJoin() 은 조인한 엔티티의 컬럼까지 한 번에 select 해서
    //   영속성 컨텍스트를 채웁니다. 프록시가 아니라 실제 객체가 들어 있으니 추가 쿼리가 없습니다.
    //
    //   일반 join 과의 차이를 SQL 로 구분하는 방법:
    //     select 절에 c1_0.* 컬럼들이 들어가 있으면 fetch join,
    //     o1_0.* 만 있으면 일반 join 입니다. SQL 의 from/join 부분은 똑같이 생겼습니다.
    //
    //   ★ 조회 전용이면 DTO 프로젝션(Step 05)이 먼저입니다.
    //     DTO 로 받으면 애초에 프록시가 없으니 N+1 도 없습니다.
    //     fetch join 은 "엔티티가 꼭 필요할 때" 의 도구입니다. 순서를 거꾸로 잡는 경우가 많습니다.

    @Test
    @DisplayName("정답 4 — 21개 vs 1개")
    void sol4() {
        // (a) fetch join 없이
        clearContext();
        long before1 = queryCount();

        List<Order> without = queryFactory
                .selectFrom(order)
                .limit(20)
                .fetch();
        without.forEach(o -> o.getCustomer().getName());

        System.out.println("(a) 쿼리 수 = " + (queryCount() - before1));   // 21

        // (b) fetch join 으로
        clearContext();
        long before2 = queryCount();

        List<Order> with = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()
                .limit(20)
                .fetch();
        with.forEach(o -> o.getCustomer().getName());

        System.out.println("(b) 쿼리 수 = " + (queryCount() - before2));   // 1
    }

    // =================================================================
    // 정답 5 — 컬렉션 fetch join + 페이징  ★ 이 파일의 하이라이트
    // =================================================================
    //
    // (b) 원인:
    //   1:N 조인은 행을 뻥튀기합니다. 주문 600건에 상품 1,200건이니 조인 결과는 1,200행입니다.
    //   여기에 limit 20 을 걸면 "주문 20건" 이 아니라 "조인 행 20개" 가 잘립니다.
    //   주문 1번에 상품 2개, 2번에 3개... 라면 20행은 주문 8~9건 정도밖에 안 되고
    //   마지막 주문은 상품이 잘린 채로 들어옵니다 — 불완전한 엔티티입니다.
    //
    //   Hibernate 는 그런 결과를 돌려줄 수 없으니 페이징을 포기하고
    //   전건을 읽어 메모리에서 자릅니다. 정확성을 지키기 위해 성능을 버린 것입니다.
    //
    //   경고 코드:
    //     Hibernate 6.x : HHH90003004  (로거 org.hibernate.orm.query)
    //     Hibernate 5.x : HHH000104    (로거 org.hibernate.hql.internal.ast.QueryTranslatorImpl)
    //   메시지는 거의 같지만 코드와 로거가 다릅니다.
    //   5.x 자료를 보고 HHH000104 로 로그 알람을 걸면 Hibernate 6 환경에서는 안 잡힙니다.
    //
    //   OOM 시나리오:
    //     주문 60만 × 상품 평균 2 = 조인 행 120만 개.
    //     엔티티 하나당 수백 바이트만 잡아도 수백 MB 가 한 요청에 힙으로 들어옵니다.
    //     동시 요청 몇 개면 힙이 터집니다.
    //     그리고 첫 페이지를 보든 마지막 페이지를 보든 매번 전건을 읽습니다.
    //
    // (c) 세 처방 비교:
    //
    //   | 처방              | 쿼리 수      | 읽는 행     | 복잡도 | 적합한 상황               |
    //   |-------------------|-------------|------------|-------|--------------------------|
    //   | ① 배치 페치        | 1 + 연관 수  | 필요한 만큼 | 낮음   | 대부분. 기본값으로 삼을 것 |
    //   | ② ToOne 만        | 1 (+지연)   | 페이지 크기 | 낮음   | 컬렉션이 화면에 없을 때    |
    //   | ③ 2단계 조회       | 2           | 페이지 크기 | 중간   | 정렬·조건이 복잡할 때      |
    //
    //   ★ 어느 처방을 쓰든 변하지 않는 사실:
    //     @ManyToOne / @OneToOne fetch join 은 페이징해도 안전합니다. 행 수가 늘지 않으니까요.
    //     @OneToMany / @ManyToMany fetch join 만 위험합니다.
    //     "fetch join 은 페이징과 못 쓴다" 는 부정확하고,
    //     "컬렉션 fetch join 은 페이징과 못 쓴다" 가 정확합니다.

    @Test
    @DisplayName("정답 5 ① — 배치 페치. limit 이 SQL 에 들어가고 쿼리 2개")
    void sol5Fix1() {
        clearContext();
        long before = queryCount();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()   // ToOne 만
                .offset(0)
                .limit(20)
                .fetch();

        orders.forEach(o -> o.getOrderItems().size());        // 컬렉션은 배치로 한 번에

        System.out.println("쿼리 수 = " + (queryCount() - before));   // 2
        // 2번째: select ... from order_items where order_id in (?,?,...20개)
        // default_batch_fetch_size 가 없으면 이게 20개로 쪼개집니다(N+1).
    }

    @Test
    @DisplayName("정답 5 ② — ToOne 만 fetch join. 경고 없음")
    void sol5Fix2() {
        clearContext();

        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.customer, customer).fetchJoin()
                .orderBy(order.id.asc())
                .offset(0)
                .limit(20)
                .fetch();

        System.out.println("결과 = " + orders.size() + "건 (WARN 없음)");
        // 생성 SQL 끝: offset ? rows fetch first ? rows only
        // 컬렉션을 화면에서 안 쓴다면 이게 가장 단순합니다.
    }

    @Test
    @DisplayName("정답 5 ③ — 2단계 조회. ID 페이징 후 in")
    void sol5Fix3() {
        clearContext();

        // 1단계 — 조인이 없으니 fan-out 도 없고 limit 이 정확히 동작합니다.
        List<Long> ids = queryFactory
                .select(order.id)
                .from(order)
                .orderBy(order.id.asc())
                .offset(0)
                .limit(20)
                .fetch();

        // 2단계 — in 으로 딱 그 20건만. limit 이 없으니 경고도 없습니다.
        List<Order> orders = queryFactory
                .selectFrom(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .where(order.id.in(ids))
                .orderBy(order.id.asc())
                .fetch();

        System.out.println("결과 = " + orders.size() + "건 (WARN 없음)");
        // 정렬이나 조건이 복잡해서 배치 페치로 부족할 때의 방법입니다.
        // 쿼리가 하나 늘지만 읽는 행은 페이지 크기만큼으로 고정됩니다.
    }

    // =================================================================
    // 정답 6 — 부하가 없는 사원 (10명)
    // =================================================================
    //
    // 해설:
    //   이 문제의 핵심은 "연관 경로로는 풀 수 없다" 는 것입니다.
    //
    //   6-11 절은 leftJoin(employee.manager, manager) 였습니다.
    //   employee.manager 는 "나의 관리자" 방향의 경로입니다.
    //   우리가 필요한 것은 "나의 부하" 방향인데,
    //   Employee 엔티티에 @OneToMany subordinates 매핑이 없으므로 탈 경로가 없습니다.
    //
    //   그래서 6-6 절의 "연관 없는 on 조인" 을 씁니다:
    //     leftJoin(subordinate).on(subordinate.manager.eq(employee))
    //   "subordinate 의 관리자가 나인 행" 을 붙이는 것입니다.
    //   조인 조건의 방향이 6-11 절과 정확히 반대입니다.
    //
    //   그다음 where(subordinate.id.isNull()) 로 "그런 행이 없었던" 사원만 남깁니다.
    //   전형적인 안티 조인입니다.
    //
    //   만약 엔티티에 @OneToMany subordinates 를 추가한다면
    //   leftJoin(employee.subordinates, subordinate) 로 더 간결하게 쓸 수 있습니다.
    //   매핑을 추가할지는 별개의 설계 판단입니다 —
    //   양방향 매핑은 편의를 주지만 관리 비용도 늘립니다.
    //
    // 생성 SQL:
    //   select e1_0.employee_id, e1_0.name, e1_0.position
    //   from employees e1_0
    //   left join employees s1_0 on s1_0.manager_id = e1_0.employee_id
    //   where s1_0.employee_id is null
    //   order by e1_0.employee_id asc
    //
    // 기대: 10건 (9~18번 사원). MySQL8 코스 Step 07 연습문제 4번과 같은 답입니다.

    @Test
    @DisplayName("정답 6 — 연관 없는 on 조인 + 안티 조인")
    void sol6() {
        QEmployee subordinate = new QEmployee("subordinate");

        List<Tuple> result = queryFactory
                .select(employee.id, employee.name, employee.position)
                .from(employee)
                .leftJoin(subordinate).on(subordinate.manager.eq(employee))   // 방향이 반대
                .where(subordinate.id.isNull())
                .orderBy(employee.id.asc())
                .fetch();

        result.forEach(t -> System.out.println(
                t.get(employee.id) + " | " + t.get(employee.name) + " | " + t.get(employee.position)));
        System.out.println("조회 " + result.size() + "건");   // 10
    }

    // =================================================================
    // 정답 7 — 결제 없는 주문 = PENDING 주문 (60 = 60)
    // =================================================================
    //
    // 해설:
    //   isNull() 은 payment.id (PK) 에 겁니다.
    //
    //   왜 PK 인가:
    //     안티 조인의 원리는 "LEFT JOIN 후 오른쪽이 NULL 확장된 행만 남기는" 것입니다.
    //     NULL 확장된 행은 오른쪽 "모든" 컬럼이 NULL 입니다.
    //     그러니 오른쪽의 어느 컬럼으로 판정해도 될 것 같지만 그렇지 않습니다.
    //
    //     NULL 을 허용하는 컬럼으로 판정하면
    //     "짝은 있는데 그 컬럼 값이 NULL 인 행" 까지 딸려옵니다.
    //     PK 는 정의상 NOT NULL 이므로 "PK 가 NULL 이다" = "짝이 없었다" 가 정확히 성립합니다.
    //
    //     우리 스키마에서 payments.status 는 NOT NULL 이라
    //     payment.status.isNull() 로 써도 결과가 우연히 같습니다.
    //     하지만 의미가 다릅니다. 나중에 status 를 nullable 로 바꾸는 순간 조용히 틀립니다.
    //     ★ 안티 조인의 isNull() 은 언제나 PK 에 겁니다. 예외 없이.
    //
    //   왜 60건인가:
    //     시드 데이터에서 PENDING 주문 60건에는 결제가 없고,
    //     나머지 540건에는 결제가 1건씩 있습니다. 600 = 60 + 540.
    //     안티 조인 결과가 PENDING 주문 수와 정확히 일치하면 데이터가 의도대로 들어간 것입니다.
    //     MySQL8 코스 7-5 절과 같은 검산입니다.
    //
    //   6-5 절의 "LEFT JOIN 오른쪽 조건을 where 에 두지 말라" 와 모순되지 않습니다.
    //   안티 조인은 NULL 확장 행만 골라내는 것이 목적이므로
    //   조인 후에 적용되는 where 가 정확히 필요한 도구입니다.
    //
    // 생성 SQL:
    //   select count(o1_0.order_id)
    //   from orders o1_0
    //   left join payments p1_0 on o1_0.order_id = p1_0.order_id
    //   where p1_0.payment_id is null

    @Test
    @DisplayName("정답 7 — 60 = 60 검산")
    void sol7() {
        long antiJoin = queryFactory
                .select(order.count())
                .from(order)
                .leftJoin(order.payments, payment)
                .where(payment.id.isNull())        // ← PK 에 건다
                .fetchOne();

        long pending = queryFactory
                .select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.PENDING))
                .fetchOne();

        System.out.println("안티 조인 = " + antiJoin + ", PENDING = " + pending);
        System.out.println(antiJoin == pending ? "일치 — 데이터 정상" : "불일치 — 시드를 확인하십시오");
        // 기대: 60 / 60

        // 실제 목록도 확인해 보면 전부 PENDING 입니다.
        List<Tuple> rows = queryFactory
                .select(order.id, order.status, order.totalAmount)
                .from(order)
                .leftJoin(order.payments, payment)
                .where(payment.id.isNull())
                .orderBy(order.id.asc())
                .limit(8)
                .fetch();

        rows.forEach(t -> System.out.println(
                t.get(order.id) + " | " + t.get(order.status) + " | " + t.get(order.totalAmount)));
        // 7, 17, 27, 37, 47, 57, 67, 77 ... 전부 PENDING
    }
}
