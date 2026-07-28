package com.example.shop.step13;

import com.example.shop.entity.Grade;
import com.example.shop.entity.OrderStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;

/**
 * Step 13 — 연습문제 7문제.
 *
 * 정답은 Solution.java 에 있습니다.
 * 답이 맞아도 **생성 SQL 이 다르면 틀린 것**입니다. 콘솔의 hibernate.SQL 을 반드시 확인하십시오.
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // ────────────────────────────────────────────────────────────────
    // 문제 1. 금액 구간별 주문 건수
    //
    // CaseBuilder 로 주문 금액(order.totalAmount)을 다음과 같이 분류하고,
    // 분류별 주문 건수를 세십시오.
    //   10만원 미만        → "소액"
    //   10만원 이상 50만 미만 → "중액"
    //   50만원 이상        → "고액"
    //
    // 요구사항:
    //   - 분류 표현식을 변수에 담아 select 와 groupBy 에 재사용할 것
    //   - 건수 내림차순 정렬
    //
    // 확인:
    //   생성 SQL 에 case 식이 몇 번 나오는지 세어 보십시오.
    //   select, group by, (정렬을 case 로 했다면) order by 에 각각 통째로 들어갑니다.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 1 — 금액 구간별 주문 건수")
    void ex1() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 2. 등급 순 정렬
    //
    // 고객을 VIP → GOLD → SILVER → BRONZE 순으로 정렬해 이름과 등급을 출력하십시오.
    // 같은 등급 안에서는 이름 오름차순입니다.
    //
    // 요구사항:
    //   - grade 는 @Enumerated(EnumType.STRING) 이므로 알파벳 정렬로는 원하는 순서가 안 나옵니다
    //   - 정렬용 표현식을 변수에 담아 select 와 orderBy 에 재사용할 것
    //
    // 확인:
    //   1) 생성된 SQL 을 MySQL 콘솔에 붙여 EXPLAIN 을 걸었을 때
    //      Extra 컬럼에 무엇이 뜰지 **먼저 예측하고** 확인하십시오.
    //   2) 왜 그렇게 되는지 한 문장으로 설명해 보십시오.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 2 — 등급 순 정렬과 그 대가")
    void ex2() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 3. 도시별 피벗 + NULL 방어
    //
    // 도시별로 다음 세 값을 한 행에 뽑으십시오.
    //   - shippingCity
    //   - status 가 PAID 인 주문의 totalAmount 합계
    //   - status 가 CANCELLED 인 주문의 건수
    //
    // 요구사항:
    //   - 합계가 NULL 이 되지 않도록 처리할 것 (해당 도시에 PAID 주문이 하나도 없을 수 있습니다)
    //   - 도시 이름 오름차순
    //
    // 함정:
    //   coalesce 를 붙이는 **위치**가 핵심입니다.
    //   .sum().coalesce(ZERO) 와 .coalesce(ZERO).sum() 은 전혀 다른 SQL 을 만듭니다.
    //   생성 SQL 이 coalesce(sum(...), ?) 인지 sum(coalesce(...)) 인지 확인하십시오.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 3 — 도시별 피벗 + coalesce 위치")
    void ex3() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 4. "이름(전화번호)" 문자열 — NULL 3명 포함
    //
    // 전 고객 30명에 대해 "이름(전화번호)" 형태의 문자열을 만드십시오.
    // 전화번호가 NULL 인 3명은 "이름(미등록)" 이 나와야 합니다.
    //
    // 요구사항:
    //   - 30건 전부 나와야 합니다 (NULL 인 3명이 빠지면 안 됩니다)
    //
    // 확인:
    //   먼저 coalesce 없이 concat 만으로 작성해 보고, 그 결과가 왜 NULL 인지
    //   생성 SQL 로 설명하십시오. (힌트: MySQL 의 CONCAT 과 NULL)
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 4 — concat 과 NULL")
    void ex4() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 5. 마진율 계산 — 0 나누기 방어
    //
    // 상품의 마진율 (price - cost) / price * 100 을 계산하십시오.
    //
    // 요구사항:
    //   - price 가 0 인 상품이 있어도 예외 없이 0 이 나올 것
    //   - 결과가 NULL 이 되지 않을 것
    //   - 상품명, price, cost, 마진율을 함께 출력
    //
    // 힌트:
    //   nullif 로 0 을 NULL 로 바꾸면 나눗셈 결과가 NULL 이 되고,
    //   그것을 coalesce 로 다시 0 으로 되돌립니다.
    //   "0으로 나누면 0으로 친다"는 규칙을 SQL 에 명시하는 것이 목적입니다.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 5 — nullif + coalesce 로 0 나누기 방어")
    void ex5() {
        // 여기에 작성:

    }

    // ────────────────────────────────────────────────────────────────
    // 문제 6. 안전한 템플릿 vs 위험한 템플릿 ★ 이 스텝의 핵심
    //
    // 주문 날짜를 "yyyy년 MM월" 형태로 포맷하는 코드를 두 가지 버전으로 작성하십시오.
    //
    // (a) 안전한 버전 — 포맷 문자열을 메서드 파라미터로 받되 인젝션이 불가능한 형태
    //     아래 safeFormat(String pattern) 을 구현하십시오.
    //
    // (b) 위험한 버전 — 포맷 문자열을 템플릿에 이어 붙인 형태
    //     아래 unsafeFormat(String pattern) 을 구현하십시오.
    //     **학습용입니다. 어떤 형태로도 운영 코드에 복사하지 마십시오.**
    //
    // 확인:
    //   1) 두 버전을 pattern = "%Y년 %m월" 로 각각 실행하고 생성 SQL 을 비교하십시오.
    //      한쪽에는 ? 가 있고 한쪽에는 리터럴이 박혀 있어야 합니다.
    //   2) 위험한 버전에 아래 공격 문자열을 넣고 SQL 이 어떻게 변하는지 확인하십시오.
    //      "%Y-%m') , (select email from customers where grade='VIP' limit 1"
    //   3) 안전한 버전에 같은 문자열을 넣으면 어떻게 되는지 확인하십시오.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 6 — 안전한 템플릿과 위험한 템플릿")
    void ex6() {
        String benign = "%Y년 %m월";
        String attack = "%Y-%m') , (select email from customers where grade='VIP' limit 1";

        // 여기에 작성: 아래 두 메서드를 구현하고 호출해 SQL 을 비교하십시오.

    }

    /** (a) 안전한 버전. 템플릿 문자열은 컴파일 시점 상수, 값은 {n} 인자로. */
    private List<String> safeFormat(String pattern) {
        // 여기에 작성:
        return List.of();
    }

    /** (b) 위험한 버전. ⚠️ 학습용. 절대 운영 코드에 쓰지 마십시오. */
    private List<String> unsafeFormat(String pattern) {
        // 여기에 작성:
        return List.of();
    }

    // ────────────────────────────────────────────────────────────────
    // 문제 7. 날짜 필터 두 가지 방식 비교
    //
    // 2025년 상반기(1월 1일 ~ 6월 30일) 주문을 조회하는 쿼리를
    // 두 가지 방식으로 작성하고 결과 건수가 같은지 확인하십시오.
    //
    //   ① year() + month() 를 쓰는 방식
    //   ② goe + lt 를 쓰는 방식 (경계값에 주의)
    //
    // 확인:
    //   1) 두 생성 SQL 을 나란히 적어 보십시오.
    //   2) 각각을 EXPLAIN 에 넣어 type 과 Extra 를 비교하십시오.
    //      (order_date 에 인덱스가 없다면 idx_orders_date 를 임시로 만들고 비교하십시오.
    //       실습이 끝나면 DROP INDEX 로 되돌립니다.)
    //   3) 왜 한쪽만 인덱스를 쓸 수 있는지 한 문장으로 설명하십시오.
    // ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("문제 7 — 날짜 필터와 인덱스")
    void ex7() {
        // 여기에 작성:

    }
}
