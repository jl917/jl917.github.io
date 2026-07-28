package com.example.shop.step09;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;

/**
 * Step 09 — 정렬과 페이징 : 연습문제 정답과 해설
 *
 * 문제를 직접 풀어 본 뒤에 여십시오.
 * 각 정답에는 "왜 그 답인가" 를 설명하는 긴 주석이 붙어 있습니다.
 * 코드보다 주석이 본체입니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 정답 1 — 다중 정렬
    // =================================================================
    @Test
    @DisplayName("정답 1 — VIP 를 포인트 내림차순, 이름 오름차순으로")
    void solution1() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .orderBy(customer.points.desc(), customer.name.asc())
                .fetch();

        result.forEach(c -> System.out.printf("  %s %dp%n", c.getName(), c.getPoints()));

        // 생성 SQL
        //   select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
        //          c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
        //   from customers c1_0
        //   where c1_0.grade = ?
        //   order by c1_0.points desc, c1_0.name asc
        //
        //   바인딩: [1] VIP
        //   조회 4건 — 류하나(48200), 정  훈(41500), 배채영(37800), 김서준(30100)
        //
        // 해설
        //   orderBy 의 인자 순서가 곧 정렬 우선순위입니다.
        //   .desc() 와 .asc() 가 각각 OrderSpecifier 를 만들고, 그것이 order by 절의
        //   한 항목이 됩니다. QueryDSL 에서 정렬만큼은 SQL 과의 대응이 거의 완벽합니다.
        //
        //   ★ 그런데 이 쿼리는 페이징이 없으므로 타이브레이커가 없어도 괜찮습니다.
        //     전건을 한 번에 가져오면 페이지 사이 누락/중복 문제가 없기 때문입니다.
        //     반대로 말하면, offset/limit 이 붙는 순간 name 만으로는 부족합니다
        //     (동명이인이 있으면 순서가 흔들립니다). 문제 7 을 참고하십시오.
        //
        //   ★ where 가 order by 앞에 오는 것은 SQL 문법이지 QueryDSL 의 메서드 호출 순서와는
        //     무관합니다. .orderBy(...).where(...) 로 써도 같은 SQL 이 나갑니다.
        //     QueryDSL 은 호출을 모아 두었다가 마지막에 JPQL 을 조립합니다.
    }

    // =================================================================
    // 정답 2 — NULL 정렬
    // =================================================================
    @Test
    @DisplayName("정답 2 — phone NULL 3명을 맨 뒤로")
    void solution2() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .orderBy(customer.phone.asc().nullsLast())
                .fetch();

        result.stream().skip(result.size() - 5).forEach(c ->
                System.out.printf("  %s phone=%s%n", c.getName(), c.getPhone()));

        // 생성 SQL (Hibernate 6.4 + MySQL 8)
        //   select c1_0.customer_id, ... from customers c1_0
        //   order by case when c1_0.phone is null then 1 else 0 end, c1_0.phone asc
        //
        //   조회 30건 — 마지막 3건이 한지호, 안지수, 문시우 (phone=NULL)
        //
        // 해설 — 이 문제의 본체는 코드 한 줄이 아니라 생성 SQL 입니다.
        //
        //   ① nullsLast() 는 SQL 표준의 "nulls last" 키워드로 나가지 않았습니다.
        //      MySQL 8 에는 NULLS FIRST / NULLS LAST 문법이 아예 없습니다.
        //      그래서 Hibernate 의 MySQL 방언이
        //        case when c1_0.phone is null then 1 else 0 end
        //      이라는 정렬 키를 앞에 하나 더 끼워 넣어 흉내 냅니다.
        //      nullsFirst() 는 then 0 else 1 로 뒤집힌 형태가 됩니다.
        //
        //   ② PostgreSQL / Oracle / H2 에서는 "c1_0.phone asc nulls last" 가 그대로 나갑니다.
        //      즉 같은 자바 코드가 DB 에 따라 전혀 다른 SQL 을 만듭니다.
        //
        //   ③ 인덱스를 탈 수 있습니까? — 못 탑니다.
        //      첫 번째 정렬 키가 case 식이라는 "계산 결과" 이기 때문입니다.
        //      인덱스는 phone 의 원본 값으로 정렬돼 있으므로 이 순서를 재사용할 수 없고,
        //      EXPLAIN 에 Using filesort 가 남습니다.
        //      이유는 9-7 의 "함수를 씌우면 인덱스를 못 탄다" 와 정확히 동일합니다.
        //
        //   ④ MySQL 은 asc 정렬에서 NULL 을 가장 작은 값으로 취급해 기본적으로 앞에 놓습니다.
        //      따라서 nullsFirst() 는 MySQL 에서 "지정하지 않은 것" 과 결과가 같으면서
        //      SQL 만 더 무거워집니다. MySQL 만 쓴다고 확신한다면 nullsFirst() 를 생략하는 것이
        //      더 나은 선택입니다. 다만 그 선택은 "이 애플리케이션은 MySQL 을 벗어나지 않는다" 는
        //      약속과 짝을 이룹니다.
        //
        //   ⑤ 근본 처방은 정렬 대상 컬럼을 NOT NULL + 기본값으로 설계하거나,
        //      where col is not null 로 NULL 행을 먼저 걸러낸 뒤 정렬하는 것입니다.
    }

    // =================================================================
    // 정답 3 — Page + 분리된 count 쿼리 + PageableExecutionUtils
    // =================================================================
    @Test
    @DisplayName("정답 3 — count 쿼리를 직접 작성한 Page")
    void solution3() {
        Page<Order> page = searchDeliveredOrders(PageRequest.of(0, 10));

        System.out.printf("  content=%d  total=%d  totalPages=%d%n",
                page.getContent().size(), page.getTotalElements(), page.getTotalPages());
    }

    private Page<Order> searchDeliveredOrders(Pageable pageable) {
        // ① 콘텐츠 쿼리
        List<Order> content = queryFactory
                .selectFrom(order)
                .where(order.status.eq(OrderStatus.DELIVERED))
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // ② count 쿼리 — 여기서 fetch 하지 않습니다. 쿼리 객체만 만들어 둡니다.
        JPAQuery<Long> countQuery = queryFactory
                .select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.DELIVERED));

        // ③ Supplier 로 넘긴다
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // 해설
    //   ★ 핵심은 "count 쿼리는 콘텐츠 쿼리와 다른 쿼리다" 라는 인식입니다.
    //
    //   생성 SQL
    //     ① select o1_0.order_id, ... from orders o1_0 where o1_0.status = ?
    //        order by o1_0.order_date desc, o1_0.order_id desc limit ?, ?
    //     ② select count(o1_0.order_id) from orders o1_0 where o1_0.status = ?
    //
    //   count 쿼리에서 order by 가 사라진 것에 주목하십시오.
    //   건수를 세는 데 순서는 아무 의미가 없습니다. 정렬은 순수한 낭비입니다.
    //
    //   ★ 조인은 뺄 수 있는 경우와 없는 경우가 나뉩니다.
    //
    //     - 뺄 수 있는 경우: 조인이 "표시(select)" 를 위해서만 있을 때.
    //       예) 목록에 고객 이름을 보여주려고 join(order.customer, customer) 를 했다면,
    //           count 에는 이름이 필요 없으므로 조인을 통째로 뺍니다.
    //
    //     - 빼면 안 되는 경우: 조인이 "필터(where)" 를 위해 있을 때.
    //       예) where(customer.grade.eq(Grade.VIP)) 가 있으면 조인을 빼는 순간
    //           JPQL 파싱에서 터지거나 조건이 무시된 엉뚱한 건수가 나옵니다.
    //
    //     규칙: 표시를 위한 조인만 뺀다. 필터를 위한 조인은 남긴다.
    //
    //   ★ 컬렉션 조인은 더 위험합니다.
    //     @OneToMany 를 조인하면 행이 뻥튀기되어 count 가 커집니다 (Step 08 의 8-7).
    //     count 쿼리에 컬렉션 조인이 남아 있다면 countDistinct() 가 필요한지 반드시 확인하십시오.
    //
    //   ★ fetchCount() 를 쓰면 오답인 이유
    //     QueryDSL 5.0 부터 fetchCount() / fetchResults() 는 deprecated 입니다.
    //     이 메서드들은 원래 쿼리를 그대로 감싸 count 로 "자동 변환" 하는데,
    //     select 절이 복잡하거나 groupBy 가 있거나 조인이 여럿이면 그 변환이
    //     의도와 다른 SQL 을 만들거나 아예 실패합니다.
    //     QueryDSL 팀이 "자동 변환은 신뢰할 수 없다" 고 판단해 폐기했습니다 (Step 03 의 3-6).
    //
    //   ★ new PageImpl<>(content, pageable, total) 도 정답이지만 한 단계 덜 최적화된 답입니다.
    //     PageImpl 은 total 값을 이미 받았으므로 count 쿼리를 항상 실행한 셈입니다.
    //     PageableExecutionUtils 는 그것을 Supplier 로 미뤄 조건부로만 실행합니다 (정답 4).

    // =================================================================
    // 정답 4 — count 쿼리 생략 확인
    // =================================================================
    @Test
    @DisplayName("정답 4 — count 쿼리가 생략되는 조건")
    void solution4() {
        System.out.println("=== ① PageRequest.of(0, 10) — count 실행됨 ===");
        Page<Order> p1 = searchDeliveredOrders(PageRequest.of(0, 10));
        System.out.printf("  total=%d%n", p1.getTotalElements());

        System.out.println("=== ② PageRequest.of(0, 1000) — count 생략됨 ===");
        Page<Order> p2 = searchDeliveredOrders(PageRequest.of(0, 1000));
        System.out.printf("  total=%d%n", p2.getTotalElements());

        System.out.println("=== ③ PageRequest.of(21, 10) 마지막 페이지 — count 생략됨 ===");
        Page<Order> p3 = searchDeliveredOrders(PageRequest.of(21, 10));
        System.out.printf("  total=%d%n", p3.getTotalElements());

        // 관찰 결과
        //   ① count SQL 이 찍힙니다.
        //      offset=0 이지만 결과가 10건으로 pageSize 를 꽉 채웠습니다.
        //      "뒤에 더 있는지" 를 알 수 없으므로 세어 봐야 합니다.
        //
        //   ② count SQL 이 찍히지 않습니다.
        //      offset=0 이고 결과 214건이 pageSize 1000 보다 작습니다.
        //      → 첫 페이지에 전부 담겼다는 뜻이므로 total = 214 로 확정됩니다.
        //
        //   ③ count SQL 이 찍히지 않습니다.
        //      offset=210, 결과 4건 < pageSize 10.
        //      → 마지막 페이지이므로 total = offset(210) + 4 = 214 로 확정됩니다.
        //
        // 해설
        //   PageableExecutionUtils.getPage 의 세 번째 인자가 Supplier<Long> 이라는 점이 전부입니다.
        //   countQuery.fetchOne() 을 미리 호출해 값을 넘기는 게 아니라
        //   countQuery::fetchOne 이라는 "호출 가능한 것" 을 넘깁니다.
        //   유틸이 위 조건을 검사해 필요할 때만 get() 을 부릅니다.
        //
        //   ★ 실무 효과는 생각보다 큽니다.
        //     대부분의 목록 화면에서 사용자는 1페이지만 보고 떠납니다.
        //     그리고 검색 조건을 좁게 준 결과는 첫 페이지를 다 채우지 못하는 경우가 많습니다.
        //     그런 요청마다 count 쿼리 하나씩을 절약합니다.
        //
        //   ★ import 주의
        //     PageableExecutionUtils 의 패키지는 Spring Data 버전에 따라 다릅니다.
        //       구버전: org.springframework.data.repository.support
        //       현행:   org.springframework.data.support
        //     블로그 글을 복사하면 import 가 안 잡히는 일이 흔합니다.
        //     클래스명만 타이핑하고 IDE 자동완성을 쓰십시오.
        //
        //   ★ 더 근본적인 질문
        //     총 페이지 수를 화면에 꼭 보여줘야 합니까?
        //     무한 스크롤이나 "더 보기" UI 라면 Slice (9-10) 나 키셋 페이징 (9-8) 이 낫습니다.
        //     count 쿼리는 화면 요구사항이 강제할 때만 지불하는 비용입니다.
    }

    // =================================================================
    // 정답 5 — 함수 정렬을 인덱스가 탈 수 있는 형태로
    // =================================================================
    @Test
    @DisplayName("정답 5 — year() 정렬을 인덱스가 타게 고치기")
    void solution5() {
        // (a) 나쁜 코드
        List<Order> bad = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.year().desc(), order.orderId.desc())
                .limit(20)
                .fetch();

        // (b) 고친 코드
        List<Order> good = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.desc(), order.orderId.desc())
                .limit(20)
                .fetch();

        System.out.printf("  bad=%d good=%d%n", bad.size(), good.size());

        // (a) 생성 SQL 의 order by 절
        //     order by year(o1_0.order_date) desc, o1_0.order_id desc
        //
        // (b) 생성 SQL 의 order by 절
        //     order by o1_0.order_date desc, o1_0.order_id desc
        //
        // (c) 왜 (a) 는 인덱스를 못 탑니까?
        //
        //     인덱스는 컬럼의 "원본 값" 으로 정렬돼 있습니다.
        //     (order_date) 인덱스의 리프 노드는 2024-01-02, 2024-01-05, ... 순으로
        //     물리적으로 늘어서 있고, 그 어디에도 2024 / 2025 라는 값은 저장돼 있지 않습니다.
        //     year(order_date) 는 인덱스가 모르는 값이므로 MySQL 은 인덱스의 정렬 순서를
        //     재사용하지 못하고, 모든 행을 읽어 메모리(또는 디스크)에서 다시 정렬합니다.
        //
        //     100만 행 access_logs 실측 (idx_time (logged_at) 있는 상태):
        //       ORDER BY YEAR(logged_at) DESC LIMIT 20
        //         → type: ALL, Using filesort, rows 996151, 1.284초
        //       ORDER BY logged_at DESC LIMIT 20
        //         → type: index, Backward index scan, rows 20, 0.002초
        //       ★ 1.284초 → 0.002초. 약 640배.
        //
        // ★ 교훈 한 줄
        //   "정렬 컬럼을 가공하지 말고, 요구사항을 원본 컬럼으로 번역하라."
        //
        //   이 문제에서 year() 는 애초에 필요 없었습니다.
        //   order_date 내림차순은 year(order_date) 내림차순과 연도 단위로 동일한 순서입니다.
        //   "연도 기준 최신순" 이라는 요구를 곧이곧대로 year() 로 옮긴 것이 실수였습니다.
        //
        // ★ 정말 함수 정렬이 필요할 때
        //   대소문자 무시 정렬(lower(name))처럼 원본 컬럼으로 대체 불가해 보이는 경우가 있습니다.
        //   그러나 shop 스키마의 컬레이션은 utf8mb4_0900_ai_ci 이고 ci 는 case insensitive 입니다.
        //   즉 ORDER BY name 이 이미 대소문자를 무시합니다. lower() 는 처음부터 불필요했습니다.
        //   이것을 모르고 lower() 를 씌우는 것이 실무에서 가장 흔한 형태입니다.
        //
        //   그래도 다른 값으로 정렬해야 한다면 생성 컬럼 + 인덱스가 정석입니다.
        //     ALTER TABLE customers
        //       ADD COLUMN name_sort VARCHAR(50) AS (LOWER(name)) STORED,
        //       ADD INDEX idx_name_sort (name_sort);
        //   (MySQL8 코스 Step 14 — 뷰와 생성 컬럼)
        //   QueryDSL 에서는 이 컬럼을 @Column(insertable=false, updatable=false) 필드로 매핑하면
        //   customer.nameSort.asc() 로 타입 안전하게 정렬할 수 있습니다.
        //
        //   ⚠️ 공용 테이블(customers/orders 등)에 실제로 인덱스를 만들지 마십시오.
        //      다른 스텝의 EXPLAIN 결과가 달라집니다.
    }

    // =================================================================
    // 정답 6 — 키셋(커서) 페이징
    // =================================================================
    @Test
    @DisplayName("정답 6 — 키셋 페이징")
    void solution6() {
        List<Order> first = nextPage(null, 20);
        Long cursor = first.get(first.size() - 1).getOrderId();
        List<Order> second = nextPage(cursor, 20);

        System.out.printf("  1페이지 %d건 (커서=%d) → 2페이지 %d건%n",
                first.size(), cursor, second.size());
    }

    private List<Order> nextPage(Long lastSeenOrderId, int size) {
        return queryFactory
                .selectFrom(order)
                .where(lastSeenOrderId == null ? null : order.orderId.lt(lastSeenOrderId))
                .orderBy(order.orderId.desc())
                .limit(size)
                .fetch();
    }

    // 해설
    //   첫 호출의 생성 SQL
    //     select o1_0.order_id, ... from orders o1_0
    //     order by o1_0.order_id desc
    //     limit ?                      바인딩: [1] 20
    //
    //   두 번째 호출의 생성 SQL
    //     select o1_0.order_id, ... from orders o1_0
    //     where o1_0.order_id < ?
    //     order by o1_0.order_id desc
    //     limit ?                      바인딩: [1] 581  [2] 20
    //
    //   차이: offset 이 사라지고 where order_id < ? 가 생겼습니다.
    //
    // ★ where(null) 을 쓰는 이유
    //   QueryDSL 의 where 는 null 인자를 "조건 없음" 으로 무시합니다 (Step 04 의 4-4).
    //   그래서 if 분기로 쿼리를 두 벌 만들지 않아도 됩니다.
    //   if 분기 답안도 동작하지만, 조건이 늘어날수록 분기가 지수적으로 늘어납니다.
    //   Step 10 의 동적 조건 조립이 이 성질 위에 세워집니다.
    //
    // ★ 왜 빠른가
    //   offset 페이징의 LIMIT 100000, 20 은 DB 에게
    //   "정렬된 순서로 100,020개를 읽고 앞의 100,000개를 버려라" 라고 말합니다.
    //   DB 에는 "정렬 결과의 N번째로 점프" 하는 방법이 없습니다.
    //   B+Tree 인덱스도 "N번째 리프" 를 직접 가리키지 못합니다.
    //
    //   반면 where order_id < 581 은 PK 인덱스에서 581 지점으로 즉시 내려간 뒤
    //   거기서부터 20건만 읽습니다. 몇 번째 페이지든 읽는 행은 항상 20건입니다.
    //
    //   100만 행 실측:
    //     offset 방식: 100000 → 0.087초 / 500000 → 0.412초 / 900000 → 0.741초
    //     키셋 방식:   어느 지점이든 0.001초
    //     ★ 0.412초 → 0.001초. 그리고 깊이와 무관하게 일정.
    //
    // ★ 복합 커서 — 정렬 키가 PK 가 아닐 때
    //   order_date 로 정렬하면서 키셋을 쓰려면 커서가 복합 값이 됩니다.
    //   order_date 는 중복될 수 있으므로 PK 를 함께 들고 가야 합니다.
    //
    //   생성 SQL:
    //     where o1_0.order_date < ? or o1_0.order_date = ? and o1_0.order_id < ?
    //     order by o1_0.order_date desc, o1_0.order_id desc limit ?
    //
    //   ⚠️ 괄호가 없습니다.
    //     SQL 에서 AND 가 OR 보다 우선순위가 높으므로
    //       a < ? or (a = ? and b < ?)
    //     와 같아 의도대로 동작합니다. 그러나 이건 운입니다.
    //     Step 04 의 4-5 에서 다뤘듯 QueryDSL 은 불필요하다고 판단한 괄호를 생략하고,
    //     조건 순서가 조금만 바뀌어도 결과가 달라질 수 있습니다.
    //     키셋 조건처럼 and/or 가 섞이는 표현식은 반드시 생성 SQL 을 눈으로 확인하고,
    //     확실히 하려면 Expressions.anyOf(...) / Expressions.allOf(...) 로 그룹을 명시하십시오.
    //
    // ★ 키셋의 한계
    //   - "7페이지로 바로 가기" 가 불가능합니다. 순차 이동만 됩니다.
    //   - 정렬 조건이 바뀌면 커서 형태도 바뀝니다. 동적 정렬(Step 10)과 결합하기 까다롭습니다.
    //   - 전체 건수를 모릅니다.
    //   그래서 무한 스크롤·피드·배치 순회에는 키셋, 페이지 번호를 찍는 관리자 화면에는 offset 이
    //   보통의 선택입니다. 관리자 화면이라면 offset 이 깊어질 일 자체가 드뭅니다.

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
    // 정답 7 — 타이브레이커
    // =================================================================
    @Test
    @DisplayName("정답 7 — 타이브레이커 추가")
    void solution7() {
        Pageable pageable = PageRequest.of(0, 10);

        List<Order> page1 = queryFactory
                .selectFrom(order)
                .orderBy(order.orderDate.desc(), order.orderId.desc())   // ← PK 추가
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        System.out.printf("  %s%n",
                page1.stream().limit(5).map(o -> String.valueOf(o.getOrderId())).toList());

        // 생성 SQL
        //   order by o1_0.order_date desc, o1_0.order_id desc
        //   limit ?, ?
        //
        // (b) 왜 필요합니까?
        //
        //   [문장 1] SQL 표준은 ORDER BY 로 지정되지 않은 부분의 순서를 보장하지 않으므로,
        //            order_date 가 같은 주문들 사이의 순서는 DB 재량이며 실행마다 달라질 수 있습니다.
        //
        //   [문장 2] 1페이지 조회와 2페이지 조회는 서로 다른 SQL 실행이라, 그 사이에 순서가
        //            뒤바뀌면 어떤 행은 어느 페이지에도 나오지 않고(누락) 어떤 행은
        //            두 페이지에 모두 나옵니다(중복).
        //
        // 해설 — 왜 실행마다 순서가 달라지는가
        //   순서가 바뀌는 계기는 최소 세 가지입니다.
        //     ① 옵티마이저가 다른 실행 계획을 고를 때
        //        (통계 갱신, 인덱스 추가/삭제, 데이터가 늘어 선택도가 달라질 때)
        //     ② 정렬이 메모리에서 디스크로 넘어갈 때 (sort_buffer_size 초과)
        //        MySQL 은 이때 알고리즘 자체를 바꿉니다.
        //     ③ 버퍼 풀 상태나 읽기 순서가 달라질 때
        //
        //   ★ 600건 규모에서는 매번 같은 순서가 나올 가능성이 높습니다.
        //     그것을 "보장된다" 로 오해하는 것이 이 함정의 본질입니다.
        //     데이터가 커지고 인덱스가 추가되는 순간, 아무도 코드를 건드리지 않았는데
        //     목록 화면에서 행이 중복되기 시작합니다. 재현이 안 되므로 버그 리포트도 닫힙니다.
        //
        // ★ 처방
        //   정렬 키의 조합이 행을 유일하게 결정하도록 만듭니다.
        //   가장 쉬운 방법은 PK 를 마지막 정렬 키로 추가하는 것입니다.
        //   order_id 는 PK 이므로 중복이 없고, 따라서 (order_date, order_id) 조합은
        //   모든 행에 대해 유일합니다. 전순서(total order)가 확정됩니다.
        //
        //   비용은 order by 절에 컬럼 하나가 늘어나는 것뿐이며,
        //   대개 인덱스의 마지막 컬럼이거나 커버링되므로 추가 비용이 사실상 없습니다.
        //
        // ★ 코드 리뷰 규칙으로 만드십시오
        //   "offset/limit 이 있는데 orderBy 에 PK 가 없다" 는 그 자체로 결함입니다.
        //   정렬 키가 이미 유니크 컬럼(예: email)이라면 생략해도 되지만,
        //   그것을 판단할 시간에 PK 를 붙이는 편이 낫습니다.
        //
        // ★ 키셋 페이징에서는 훨씬 치명적입니다
        //   커서 값이 유일하지 않으면 "그 값보다 작은 것" 을 걸러낼 때
        //   같은 값을 가진 행들을 통째로 건너뜁니다. 누락이 확정적으로 발생합니다.
        //   정답 6 의 복합 커서(orderDate + orderId)가 바로 이 문제에 대한 대응입니다.
    }
}
