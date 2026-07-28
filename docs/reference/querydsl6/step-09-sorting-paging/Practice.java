package com.example.shop.step09;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
// ⚠️ PageableExecutionUtils 의 패키지는 Spring Data 버전에 따라 다릅니다.
//    구버전: org.springframework.data.repository.support.PageableExecutionUtils
//    현행:   org.springframework.data.support.PageableExecutionUtils
//    빨간 줄이 뜨면 이 import 를 지우고 클래스명을 다시 타이핑해 IDE 자동완성을 쓰십시오.
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;

/**
 * Step 09 — 정렬과 페이징 : 본문 예제 실행 파일
 *
 * 실행 전 반드시 application.yml 에서 SQL 로그가 켜져 있는지 확인하십시오.
 *
 *   logging.level.org.hibernate.SQL: debug
 *   logging.level.org.hibernate.orm.jdbc.bind: trace
 *
 * 이 파일의 목적은 "결과가 맞는가" 가 아니라 "어떤 SQL 이 나갔는가" 입니다.
 * 테스트 메서드를 하나씩 실행하며 콘솔 로그를 교재의 SQL 과 한 글자씩 대조하십시오.
 * 전체를 한 번에 돌리면 로그가 뒤섞여 어느 SQL 이 어느 절인지 구분되지 않습니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [9-1] orderBy — 정렬의 기본
    // =================================================================

    @Test
    @DisplayName("[9-1] 단일 정렬 — points 내림차순")
    void s1_singleOrder() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(customer.points.desc())
                .fetch();

        // 생성 SQL:
        //   select c1_0.customer_id, ... from customers c1_0
        //   order by c1_0.points desc
        print("[9-1] 단일 정렬", result.size());
        result.stream().limit(5).forEach(c ->
                System.out.printf("  %s (%s, %dp)%n", c.getName(), c.getGrade(), c.getPoints()));
    }

    @Test
    @DisplayName("[9-1] 다중 정렬 — 인자 순서가 정렬 우선순위")
    void s1_multipleOrder() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(
                        customer.grade.desc(),       // 1순위
                        customer.points.desc(),      // 2순위
                        customer.customerId.asc()    // 3순위 (타이브레이커)
                )
                .fetch();

        // 생성 SQL:
        //   order by c1_0.grade desc, c1_0.points desc, c1_0.customer_id asc
        //
        // ⚠️ grade 는 MySQL ENUM 입니다. desc 는 "문자열 사전순 역순" 이 아니라
        //    ENUM 선언 순서(BRONZE,SILVER,GOLD,VIP)의 역순입니다.
        print("[9-1] 다중 정렬", result.size());
        result.stream().limit(5).forEach(c ->
                System.out.printf("  %s (%s, %dp, id=%d)%n",
                        c.getName(), c.getGrade(), c.getPoints(), c.getCustomerId()));
    }

    @Test
    @DisplayName("[9-1] OrderSpecifier 배열로 뽑아 쓰기")
    void s1_orderSpecifierArray() {
        OrderSpecifier<?>[] sortByGradeThenPoints = {
                customer.grade.desc(),
                customer.points.desc(),
                customer.customerId.asc()
        };

        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(sortByGradeThenPoints)
                .fetch();

        // 생성 SQL 은 s1_multipleOrder 와 완전히 동일합니다.
        // orderBy(OrderSpecifier<?>...) 오버로드가 배열을 그대로 받습니다.
        // 이 형태가 Step 10 의 동적 정렬로 그대로 이어집니다.
        print("[9-1] OrderSpecifier 배열", result.size());
    }

    // =================================================================
    // [9-2] NULL 정렬
    // =================================================================

    @Test
    @DisplayName("[9-2] 지정 없음 — MySQL 은 asc 에서 NULL 을 앞에 둔다")
    void s2_nullDefault() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(customer.phone.asc())
                .fetch();

        // 생성 SQL:
        //   order by c1_0.phone asc
        //
        // MySQL 은 NULL 을 가장 작은 값으로 취급 → 앞의 3건이 phone=NULL.
        // PostgreSQL / Oracle 은 asc 에서 NULL 을 뒤에 둡니다. DB 를 바꾸면 조용히 달라집니다.
        print("[9-2] NULL 기본 동작", result.size());
        result.stream().limit(5).forEach(c ->
                System.out.printf("  %s phone=%s%n", c.getName(), c.getPhone()));
    }

    @Test
    @DisplayName("[9-2] nullsLast — MySQL 에서는 case when 으로 에뮬레이션된다")
    void s2_nullsLast() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(customer.phone.asc().nullsLast())
                .fetch();

        // 생성 SQL (Hibernate 6.4 + MySQL 8):
        //   order by case when c1_0.phone is null then 1 else 0 end, c1_0.phone asc
        //
        // ★ 이 절의 핵심 — MySQL 에는 NULLS LAST 문법이 없어서 계산식으로 풀립니다.
        //   그 계산식은 인덱스로 처리할 수 없습니다 (Using filesort).
        print("[9-2] nullsLast", result.size());
        result.stream().skip(result.size() - 3).forEach(c ->
                System.out.printf("  %s phone=%s%n", c.getName(), c.getPhone()));
    }

    @Test
    @DisplayName("[9-2] nullsFirst — then 0 else 1 로 뒤집힌다")
    void s2_nullsFirst() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(customer.phone.asc().nullsFirst())
                .fetch();

        // 생성 SQL:
        //   order by case when c1_0.phone is null then 0 else 1 end, c1_0.phone asc
        print("[9-2] nullsFirst", result.size());
        result.stream().limit(3).forEach(c ->
                System.out.printf("  %s phone=%s%n", c.getName(), c.getPhone()));
    }

    // =================================================================
    // [9-3] offset / limit
    // =================================================================

    @Test
    @DisplayName("[9-3] offset + limit — MySQL 방언에서 limit ?, ?")
    void s3_offsetLimit() {
        List<Order> page = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(20)   // 0-based. 앞의 20건을 건너뛴다
                .limit(10)
                .fetch();

        // 생성 SQL:
        //   order by o1_0.order_date desc, o1_0.order_id desc
        //   limit ?, ?        바인딩: [1] 20  [2] 10
        //
        // MySQL 의 limit 은 (offset, rowcount) 순서입니다.
        // 방언에 따라 limit ? offset ? 로 나가기도 하므로 여러분의 로그를 기준으로 읽으십시오.
        print("[9-3] offset + limit", page.size());
    }

    @Test
    @DisplayName("[9-3] limit 만 — limit ? 하나")
    void s3_limitOnly() {
        List<Order> top5 = queryFactory
                .selectFrom(order)
                .orderBy(order.totalAmount.desc(), order.orderId.asc())
                .limit(5)
                .fetch();

        // 생성 SQL:
        //   order by o1_0.total_amount desc, o1_0.order_id asc
        //   limit ?           바인딩: [1] 5
        print("[9-3] limit only (TOP 5)", top5.size());
        top5.forEach(o ->
                System.out.printf("  order_id=%d  %s%n", o.getOrderId(), o.getTotalAmount()));
    }

    // =================================================================
    // [9-4] Spring Pageable 을 받아서
    // =================================================================

    @Test
    @DisplayName("[9-4] Pageable 로 offset 계산 위임")
    void s4_pageable() {
        Pageable pageable = PageRequest.of(2, 10);   // getOffset() == 20

        List<Order> result = findOrders(pageable);

        // ⚠️ pageable.getSort() 는 QueryDSL 에 자동 적용되지 않습니다.
        //    클라이언트가 ?sort=totalAmount,desc 를 보내도 아무 일도 일어나지 않고, 에러도 안 납니다.
        //    Sort -> OrderSpecifier[] 변환기는 Step 10 에서 직접 만듭니다.
        System.out.println("  pageable.getOffset() = " + pageable.getOffset());
        print("[9-4] Pageable", result.size());
    }

    private List<Order> findOrders(Pageable pageable) {
        return queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    // =================================================================
    // [9-5] Page 만들기 — 콘텐츠 쿼리 + count 쿼리
    // =================================================================

    @Test
    @DisplayName("[9-5] PageImpl — 쿼리가 2번 나간다")
    void s5_pageImpl() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Order> page = searchOrdersNaive(pageable);

        // 생성 SQL 2건:
        //   ① select ... from orders o1_0 where o1_0.status = ?
        //      order by o1_0.order_date desc, o1_0.order_id desc limit ?, ?
        //   ② select count(o1_0.order_id) from orders o1_0 where o1_0.status = ?
        //
        // order.count() 는 count(*) 가 아니라 count(o1_0.order_id) 로 번역됩니다.
        // PK 는 NOT NULL 이므로 결과값은 같지만 생성 SQL 은 다릅니다.
        System.out.printf("  content=%d  total=%d  totalPages=%d%n",
                page.getContent().size(), page.getTotalElements(), page.getTotalPages());
    }

    private Page<Order> searchOrdersNaive(Pageable pageable) {
        List<Order> content = queryFactory
                .selectFrom(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    // =================================================================
    // [9-6] count 쿼리를 따로 최적화한다
    // =================================================================

    @Test
    @DisplayName("[9-6] count 쿼리 분리 — join 과 orderBy 를 뺀다")
    void s6_pageWithSeparateCount() {
        Pageable pageable = PageRequest.of(0, 10);

        // 콘텐츠 쿼리 — 고객 이름을 함께 보여줘야 하므로 join 이 필요
        List<Object[]> content = queryFactory
                .select(order.orderId, order.orderDate, order.totalAmount, customer.name)
                .from(order)
                .join(order.customer, customer)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(t -> new Object[]{t.get(order.orderId), t.get(order.orderDate),
                        t.get(order.totalAmount), t.get(customer.name)})
                .toList();

        // count 쿼리 — join 도 orderBy 도 없다
        Long total = queryFactory
                .select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .fetchOne();

        // 생성 SQL 비교:
        //   콘텐츠 : select ... from orders o1_0
        //            join customers c1_0 on c1_0.customer_id = o1_0.customer_id
        //            where o1_0.status = ? order by ... limit ?, ?
        //   count  : select count(o1_0.order_id) from orders o1_0 where o1_0.status = ?
        //
        // ⚠️ 규칙 — "표시(select)를 위한 조인만 뺀다. 필터(where)를 위한 조인은 남긴다."
        //    where 가 customer.grade 를 참조한다면 count 쿼리에도 join 이 반드시 있어야 합니다.
        System.out.printf("  content=%d  total=%d%n", content.size(), total);
    }

    @Test
    @DisplayName("[9-6] PageableExecutionUtils — count 쿼리가 생략되는지 로그로 확인")
    void s6_pageableExecutionUtils() {
        // ★ 이 메서드의 목적은 반환값이 아니라 "count SQL 이 몇 번 찍히는가" 입니다.
        //    콘솔에서 select count(...) 로그를 직접 세십시오.

        System.out.println("=== ① PageRequest.of(0, 10) — count 실행됨 (10건이 꽉 참) ===");
        Page<Order> p1 = searchOrders(PageRequest.of(0, 10));
        System.out.printf("  total=%d%n", p1.getTotalElements());

        System.out.println("=== ② PageRequest.of(0, 500) — count 생략됨 ===");
        Page<Order> p2 = searchOrders(PageRequest.of(0, 500));
        System.out.printf("  total=%d  (offset=0 이고 결과 %d < pageSize 500 이므로 계산으로 확정)%n",
                p2.getTotalElements(), p2.getContent().size());

        System.out.println("=== ③ PageRequest.of(21, 10) — 마지막 페이지, count 생략됨 ===");
        Page<Order> p3 = searchOrders(PageRequest.of(21, 10));
        System.out.printf("  total=%d  (offset 210 + 결과 %d)%n",
                p3.getTotalElements(), p3.getContent().size());
    }

    private Page<Order> searchOrders(Pageable pageable) {
        List<Order> content = queryFactory
                .selectFrom(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.DELIVERED));

        // 세 번째 인자는 Supplier<Long> 입니다.
        // countQuery.fetchOne() 을 미리 호출하는 것이 아니라, 호출 가능한 것을 넘깁니다.
        // PageableExecutionUtils 가 필요할 때만 get() 을 부릅니다.
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // =================================================================
    // [9-7] ⚠️ 정렬 컬럼에 함수를 씌워 인덱스를 죽인다
    // =================================================================

    @Test
    @DisplayName("[9-7] 잘못된 코드 — year() 를 씌운 정렬")
    void s7_sortByFunctionKillsIndex() {
        List<Order> bad = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.year().desc(), order.orderId.desc())
                .limit(20)
                .fetch();

        // 생성 SQL:
        //   order by year(o1_0.order_date) desc, o1_0.order_id desc limit ?
        //
        // 결과는 정확합니다. 문제는 인덱스입니다.
        // 인덱스는 order_date 의 "원본 값" 으로 정렬돼 있습니다.
        // year(order_date) 는 인덱스 어디에도 저장돼 있지 않은 다른 값입니다.
        //
        // 100만 행 access_logs 에서 같은 모양의 SQL:
        //   ORDER BY YEAR(logged_at) DESC LIMIT 20  → type: ALL, Using filesort, 1.284초
        //   ORDER BY logged_at DESC LIMIT 20        → Backward index scan,        0.002초
        //   ★ 약 640배
        //
        // orders 는 600건이므로 이 테스트로는 시간 차이가 보이지 않습니다.
        // 확인할 것은 "생성 SQL 에 year(o1_0.order_date) 가 들어간다" 는 사실입니다.
        print("[9-7] 함수 정렬 (나쁨)", bad.size());
    }

    @Test
    @DisplayName("[9-7] 고친 코드 — 원본 컬럼으로 정렬")
    void s7_sortByRawColumn() {
        List<Order> good = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .limit(20)
                .fetch();

        // 생성 SQL:
        //   order by o1_0.order_date desc, o1_0.order_id desc limit ?
        //
        // order_date 내림차순은 year(order_date) 내림차순과 "연도 단위로는 동일한 순서" 입니다.
        // 애초에 함수가 필요 없었습니다.
        print("[9-7] 원본 컬럼 정렬 (좋음)", good.size());
    }

    @Test
    @DisplayName("[9-7] lower() 정렬 — 컬레이션이 이미 해결하고 있다")
    void s7_lowerIsUnnecessary() {
        List<Customer> withLower = queryFactory
                .selectFrom(customer)
                .orderBy(customer.name.lower().asc())
                .fetch();
        // 생성 SQL: order by lower(c1_0.name) asc

        List<Customer> withoutLower = queryFactory
                .selectFrom(customer)
                .orderBy(customer.name.asc())
                .fetch();
        // 생성 SQL: order by c1_0.name asc

        // shop 스키마의 컬레이션은 utf8mb4_0900_ai_ci — ci = case insensitive.
        // 즉 ORDER BY name 이 이미 대소문자를 무시합니다. lower() 는 처음부터 불필요했습니다.
        // 이 사실을 모르고 lower() 를 씌우는 것이 실무에서 가장 흔한 형태입니다.
        System.out.printf("  lower 정렬 %d건 / 원본 정렬 %d건 — 순서 동일 여부: %s%n",
                withLower.size(), withoutLower.size(),
                withLower.get(0).getCustomerId().equals(withoutLower.get(0).getCustomerId()));
    }

    // =================================================================
    // [9-8] ⚠️ 깊은 offset 의 비용 / 키셋(커서) 페이징
    // =================================================================

    @Test
    @DisplayName("[9-8] 깊은 offset — SQL 은 완벽하지만 확장되지 않는다")
    void s8_deepOffset() {
        List<Order> deep = queryFactory
                .selectFrom(order)
                .orderBy(order.orderId.desc())
                .offset(500)
                .limit(20)
                .fetch();

        // 생성 SQL:
        //   order by o1_0.order_id desc limit ?, ?     바인딩: [1] 500  [2] 20
        //
        // LIMIT 500, 20 은 DB 에게 "520개를 읽고 앞의 500개를 버려라" 라고 말합니다.
        // "정렬 결과의 501번째로 점프" 하는 방법은 DB 에 없습니다.
        //
        // 100만 행 access_logs 실측:
        //   LIMIT      0, 20  → 0.001초
        //   LIMIT 100000, 20  → 0.087초
        //   LIMIT 500000, 20  → 0.412초
        //   LIMIT 900000, 20  → 0.741초
        //   ★ offset 에 정비례. 반환 행은 늘 20건인데도.
        print("[9-8] 깊은 offset", deep.size());
    }

    @Test
    @DisplayName("[9-8] 키셋(커서) 페이징 — offset 을 where 로 바꾼다")
    void s8_keysetPaging() {
        // 첫 페이지 — lastSeenId 가 null 이므로 where 조건이 무시된다
        List<Order> first = nextPage(null, 20);
        Long cursor = first.get(first.size() - 1).getOrderId();
        // 생성 SQL: order by o1_0.order_id desc limit ?

        // 다음 페이지
        List<Order> second = nextPage(cursor, 20);
        // 생성 SQL: where o1_0.order_id < ? order by o1_0.order_id desc limit ?

        // offset 이 사라지고 where order_id < ? 가 생겼습니다.
        // 이 조건은 PK 인덱스로 즉시 탐색(seek)됩니다.
        // 몇 번째 페이지든 읽는 행은 항상 20건입니다.
        //
        // 100만 행 실측 (WHERE log_id < N ORDER BY log_id DESC LIMIT 20):
        //   N=100000 → 0.001초 / N=500000 → 0.001초 / N=900000 → 0.001초
        //   ★ 0.412초 → 0.001초, 그리고 깊이와 무관하게 일정
        System.out.printf("  1페이지 %d건 (커서=%d) → 2페이지 %d건 (커서=%d)%n",
                first.size(), cursor, second.size(),
                second.get(second.size() - 1).getOrderId());
    }

    private List<Order> nextPage(Long lastSeenOrderId, int size) {
        return queryFactory
                .selectFrom(order)
                // where 에 null 을 넘기면 QueryDSL 이 그 조건을 무시합니다 (Step 04 의 4-4).
                .where(lastSeenOrderId == null ? null : order.orderId.lt(lastSeenOrderId))
                .orderBy(order.orderId.desc())
                .limit(size)
                .fetch();
    }

    @Test
    @DisplayName("[9-8] 복합 커서 — 정렬 키가 PK 가 아닐 때")
    void s8_compositeCursor() {
        List<Order> first = nextPageByDate(null, null, 20);
        Order last = first.get(first.size() - 1);

        List<Order> second = nextPageByDate(last.getOrderDate(), last.getOrderId(), 20);

        // 생성 SQL (2페이지):
        //   where o1_0.order_date < ? or o1_0.order_date = ? and o1_0.order_id < ?
        //   order by o1_0.order_date desc, o1_0.order_id desc limit ?
        //
        // ⚠️ 괄호가 없습니다. SQL 에서 AND 가 OR 보다 우선순위가 높으므로
        //    a < ? or (a = ? and b < ?) 와 같아 의도대로입니다. 그러나 이건 운입니다.
        //    Step 04 의 4-5 에서 다뤘듯 QueryDSL 은 불필요하다고 판단한 괄호를 생략합니다.
        //    and/or 가 섞인 표현식은 반드시 생성 SQL 을 눈으로 확인하십시오.
        System.out.printf("  1페이지 %d건 → 2페이지 %d건%n", first.size(), second.size());
    }

    private List<Order> nextPageByDate(LocalDateTime lastDate, Long lastId, int size) {
        BooleanExpression cursor = (lastDate == null) ? null :
                order.orderDate.lt(lastDate)
                        .or(order.orderDate.eq(lastDate).and(order.orderId.lt(lastId)));

        return queryFactory
                .selectFrom(order)
                .where(cursor)
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .limit(size)
                .fetch();
    }

    // =================================================================
    // [9-9] ⚠️ 정렬 기준이 유일하지 않으면 행이 새거나 중복된다
    // =================================================================

    @Test
    @DisplayName("[9-9] 타이브레이커 없음 — 같은 쿼리를 5회 반복")
    void s9_missingTiebreaker() {
        // 정렬 키가 orderDate 하나뿐. 같은 날짜의 주문 순서는 보장되지 않습니다.
        for (int i = 1; i <= 5; i++) {
            List<Order> page1 = queryFactory
                    .selectFrom(order)
                    .orderBy(order.orderDate.desc())
                    .offset(0).limit(10)
                    .fetch();

            System.out.printf("  %d회차 상위 3건: %s%n", i,
                    page1.stream().limit(3).map(o -> String.valueOf(o.getOrderId())).toList());
        }

        // ⚠️ 600건 규모에서는 매번 같은 순서가 나올 가능성이 높습니다.
        //    그것은 "보장된다" 는 뜻이 아닙니다. SQL 표준은 ORDER BY 로 지정되지 않은
        //    부분의 순서를 보장하지 않습니다. 순서가 바뀌는 계기는 다음과 같습니다.
        //      - 옵티마이저가 다른 실행 계획을 고를 때 (통계 갱신, 인덱스 추가, 데이터 증가)
        //      - 정렬이 메모리에서 디스크로 넘어갈 때 (sort_buffer_size 초과)
        //      - 병렬 읽기나 버퍼 풀 상태가 달라질 때
        //
        //    1페이지 조회와 2페이지 조회는 서로 다른 SQL 실행입니다.
        //    그 사이에 순서가 뒤바뀌면 어떤 행은 어느 페이지에도 안 나오고(누락),
        //    어떤 행은 두 페이지에 모두 나옵니다(중복).
    }

    @Test
    @DisplayName("[9-9] 타이브레이커 추가 — orderBy 마지막은 언제나 PK")
    void s9_withTiebreaker() {
        List<Order> page1 = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(0).limit(10)
                .fetch();

        // 생성 SQL:
        //   order by o1_0.order_date desc, o1_0.order_id desc limit ?, ?
        //
        // order_id 는 PK 이므로 중복이 없습니다.
        // 따라서 (order_date, order_id) 조합은 모든 행에 대해 유일하고,
        // 전순서(total order)가 확정되어 실행 계획이 바뀌어도 결과 순서가 같습니다.
        //
        // 💡 코드 리뷰 규칙으로 만드십시오:
        //    "offset/limit 이 있는데 orderBy 에 PK 가 없다" 는 그 자체로 결함이다.
        print("[9-9] 타이브레이커 있음", page1.size());
        System.out.printf("  상위 3건: %s%n",
                page1.stream().limit(3).map(o -> String.valueOf(o.getOrderId())).toList());
    }

    // =================================================================
    // [9-10] Slice — count 를 아예 쓰지 않는다
    // =================================================================

    @Test
    @DisplayName("[9-10] Slice — limit(size + 1) 로 다음 페이지 존재만 판단")
    void s10_slice() {
        System.out.println("=== 첫 슬라이스 PageRequest.of(0, 10) ===");
        Slice<Order> s1 = searchSlice(PageRequest.of(0, 10));
        System.out.printf("  content=%d  hasNext=%s%n", s1.getContent().size(), s1.hasNext());

        System.out.println("=== 마지막 슬라이스 PageRequest.of(21, 10) ===");
        Slice<Order> s2 = searchSlice(PageRequest.of(21, 10));
        System.out.printf("  content=%d  hasNext=%s%n", s2.getContent().size(), s2.hasNext());

        // 생성 SQL: limit ?, ?     바인딩: [2] offset  [3] 11   ← 10 이 아니라 11
        // count 쿼리가 한 번도 나가지 않습니다.
        //
        // 💡 Slice 가 없애는 것은 count 쿼리이지 offset 비용이 아닙니다.
        //    무한 스크롤을 깊게 내려가는 화면이라면 Slice + offset 이 아니라 키셋 페이징을 쓰십시오.
    }

    private Slice<Order> searchSlice(Pageable pageable) {
        List<Order> fetched = queryFactory
                .selectFrom(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize() + 1L)      // +1
                .fetch();

        // ⚠️ fetch() 가 반환하는 리스트가 항상 수정 가능한 ArrayList 라는 보장은 없습니다.
        //    UnsupportedOperationException 을 피하려면 감싸십시오.
        List<Order> content = new ArrayList<>(fetched);

        boolean hasNext = content.size() > pageable.getPageSize();
        if (hasNext) {
            content.remove(pageable.getPageSize());
        }

        return new SliceImpl<>(content, pageable, hasNext);
    }

    // =================================================================
    // [9-11] 페이징 전략 비교 — 세 방식을 같은 조건으로 호출해 본다
    // =================================================================

    @Test
    @DisplayName("[9-11] 세 전략을 나란히 실행")
    void s11_compareStrategies() {
        Pageable pageable = PageRequest.of(0, 10);

        System.out.println("--- ① Page (count 쿼리 포함/생략) ---");
        Page<Order> page = searchOrders(pageable);
        System.out.printf("  total=%d  totalPages=%d%n", page.getTotalElements(), page.getTotalPages());

        System.out.println("--- ② Slice (count 없음) ---");
        Slice<Order> slice = searchSlice(pageable);
        System.out.printf("  hasNext=%s  (전체 건수는 모른다)%n", slice.hasNext());

        System.out.println("--- ③ 키셋 (count 없음, offset 없음) ---");
        List<Order> keyset = nextPage(null, 10);
        System.out.printf("  %d건, 다음 커서=%d%n",
                keyset.size(), keyset.get(keyset.size() - 1).getOrderId());

        // 선택 기준
        //   1. 총 건수/총 페이지 수를 표시해야 하는가?          → Page
        //   2. 아니라면, offset 이 깊어질 수 있는가?            → 키셋
        //   3. 깊어지지 않는다면                                → Slice
        //   4. Page 를 쓰더라도 count 는 직접 작성 + PageableExecutionUtils
        //   5. 어느 방식이든 orderBy 마지막은 PK
    }

    // =================================================================
    // 보조 메서드
    // =================================================================

    private void print(String label, int size) {
        System.out.printf("%s — 조회 %d건%n", label, size);
    }

    @SuppressWarnings("unused")
    private void vipOnlyExample() {
        // 다른 절에서 Grade 를 쓰는 예시가 필요할 때를 위한 자리입니다.
        queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .orderBy(customer.points.desc(), customer.customerId.asc())
                .fetch();
    }
}
