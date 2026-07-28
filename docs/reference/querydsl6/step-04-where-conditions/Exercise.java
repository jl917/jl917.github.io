package com.example.shop.step04;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Product;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 04 — 연습문제 6개.
 *
 * 규칙
 *  - 각 문제의 "여기에 작성:" 아래를 채웁니다.
 *  - 모든 문제에서 생성 SQL 과 결과 건수를 함께 확인하십시오.
 *  - 주석 안의 빈칸(___)을 직접 채우는 것이 문제의 일부입니다.
 *  - 정답은 Solution.java 에 있습니다. 먼저 풀어 보십시오.
 *
 * ─────────────────────────────────────────────────────────────
 * 참고 데이터 (단언에 넣을 숫자를 계산할 때 쓰십시오)
 *
 *   customers 30명
 *     등급 : VIP 4 / GOLD 9 / SILVER 8 / BRONZE 9
 *     도시 : 서울 8 / 부산 6 / 인천 5 / 대구 4 / 대전 4 / 광주 3
 *     phone NULL : 3명
 *
 *   VIP  4명 — 김서준(서울) 류하나(서울) 정  훈(부산) 배채영(대구)
 *   GOLD 9명 — 안지수(서울) 한지호(서울) 오하윤(서울) 문시우(서울)
 *              강도윤(부산) 윤서아(부산) 임하준(인천) 조은우(대전) 신지아(광주)
 *
 *   products 40개 — 게이밍 노트북 RTX4060(2190000), 보급형 노트북 15(690000) 등
 * ─────────────────────────────────────────────────────────────
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // -----------------------------------------------------------------
    // 문제 1. contains + goe 를 조합하고 바인딩 값 확인하기
    //
    // 요구사항
    //  - products 에서 이름에 "노트북" 이 들어가고 price >= 500000 인 상품을 조회합니다.
    //  - 두 조건을 varargs 로 나열합니다 (체이닝 금지).
    //  - 실행 후 바인딩 로그를 보고 아래 빈칸을 채우십시오.
    //
    // 확인 1) like 에 바인딩된 값 = ___________
    // 확인 2) SQL 에 % 문자가 보이는가 (예/아니오) = ___
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 1 — contains 의 바인딩 값")
    void 문제1() {
        List<Product> result = null;

        // 여기에 작성:


        result.forEach(p -> System.out.println(p.getName() + " / " + p.getPrice()));
        assertThat(result).hasSize(2);
    }

    // -----------------------------------------------------------------
    // 문제 2. like 세 형제를 실행하고 표를 채우기
    //
    // 요구사항
    //  - customer.name 에 대해 startsWith("김") / contains("김") / endsWith("준")
    //    세 쿼리를 연달아 실행합니다.
    //  - 아래 표의 빈칸을 로그를 보고 채우십시오. 이 표를 채우는 것이 문제의 본체입니다.
    //
    //   | 코드              | 생성 SQL                     | 바인딩 | 건수 |
    //   |-------------------|------------------------------|--------|------|
    //   | startsWith("김")  | ____________________________ | ______ | ____ |
    //   | contains("김")    | ____________________________ | ______ | ____ |
    //   | endsWith("준")    | ____________________________ | ______ | ____ |
    //
    // 확인) 세 SQL 이 같은가 = ___
    //       그렇다면 SQL 로그만으로 셋을 구분할 수 있는가 = ___
    //       구분하려면 무엇을 켜야 하는가 = ___________________
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2 — like 세 형제의 SQL 과 바인딩")
    void 문제2() {
        List<Customer> starts = null;
        List<Customer> contains = null;
        List<Customer> ends = null;

        // 여기에 작성:


        System.out.println("startsWith = " + starts.size());
        System.out.println("contains   = " + contains.size());
        System.out.println("endsWith   = " + ends.size());
    }

    // -----------------------------------------------------------------
    // 문제 3. BooleanExpression 방식 동적 검색 메서드 작성하기
    //
    // 요구사항
    //  - 아래 gradeEq / cityEq / pointsGoe 세 메서드를 완성합니다.
    //    값이 없으면 null 을 반환해야 합니다.
    //    cityEq 는 빈 문자열도 "값 없음" 으로 취급해야 합니다 (힌트: StringUtils.hasText).
    //  - search(...) 를 완성합니다. if 를 쓰지 마십시오.
    //  - 테스트에서 조건 조합 4가지를 호출하고 각각의 SQL 을 로그로 남깁니다.
    //
    // 확인) search 메서드 안에 if 가 몇 개 있는가 = ___
    // -----------------------------------------------------------------
    private BooleanExpression gradeEq(Grade grade) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression cityEq(String city) {
        // 여기에 작성:
        return null;
    }

    private BooleanExpression pointsGoe(Integer minPoints) {
        // 여기에 작성:
        return null;
    }

    private List<Customer> search(Grade grade, String city, Integer minPoints) {
        // 여기에 작성:
        return List.of();
    }

    @Test
    @DisplayName("문제 3 — BooleanExpression 방식 동적 검색")
    void 문제3() {
        System.out.println("=== (1) 조건 없음 ===");
        assertThat(search(null, null, null)).hasSize(30);

        System.out.println("=== (2) 등급만 ===");
        assertThat(search(Grade.GOLD, null, null)).hasSize(9);

        System.out.println("=== (3) 등급 + 도시 ===");
        assertThat(search(Grade.GOLD, "서울", null)).hasSize(4);

        System.out.println("=== (4) 셋 다 ===");
        assertThat(search(Grade.GOLD, "서울", 10000)).hasSize(3);

        // 빈 문자열도 조건이 되지 않아야 합니다.
        assertThat(search(null, "", null)).hasSize(30);
    }

    // -----------------------------------------------------------------
    // 문제 4. 같은 기능을 BooleanBuilder 로 작성하고 비교하기
    //
    // 요구사항
    //  - 문제 3 과 동일한 동작을 BooleanBuilder 로 구현합니다.
    //  - 두 방식의 결과 건수가 같은지 단언합니다.
    //  - 생성 SQL 을 로그에서 비교합니다.
    //
    // 확인 1) 두 SQL 이 같은가 = ___
    // 확인 2) searchWithBuilder 안에 if 를 몇 번 썼는가 = ___
    // 확인 3) 문제 3 의 search 는 몇 줄, 이쪽은 몇 줄인가 = ___ / ___
    // 확인 4) 조건이 3개에서 6개로 늘면 각각 몇 줄이 늘어나는가 = ___ / ___
    // -----------------------------------------------------------------
    private List<Customer> searchWithBuilder(Grade grade, String city, Integer minPoints) {
        BooleanBuilder builder = new BooleanBuilder();

        // 여기에 작성:


        return queryFactory.selectFrom(customer).where(builder).fetch();
    }

    @Test
    @DisplayName("문제 4 — BooleanBuilder 방식")
    void 문제4() {
        assertThat(searchWithBuilder(Grade.GOLD, "서울", 10000)).hasSize(3);
        assertThat(searchWithBuilder(null, null, null)).hasSize(30);

        // 두 방식이 같은 결과를 내는지 확인합니다.
        assertThat(searchWithBuilder(Grade.GOLD, null, null).size())
                .isEqualTo(search(Grade.GOLD, null, null).size());
    }

    // -----------------------------------------------------------------
    // 문제 5. ⚠️ or 함정 재현하기 — 이 스텝의 시험입니다
    //
    // 목표 쿼리: "VIP 또는 GOLD 이면서, 서울에 사는 고객"
    //
    // 요구사항
    //  (a) varargs 로 나눈 올바른 코드를 작성합니다.
    //      → where(A.or(B), C) 형태. 결과 6건을 단언하십시오.
    //
    //  (b) .and() 와 .or() 를 이어 붙인 코드를 작성합니다.
    //      → where(C.and(A).or(B)) 형태.
    //      ** 기대 건수를 미리 알려 주지 않습니다. **
    //      직접 실행해 몇 건인지 확인하고 그 숫자를 단언에 넣으십시오.
    //
    //  (c) (b) 의 생성 SQL 을 아래 주석에 그대로 옮겨 적으십시오.
    //
    // 확인 1) (a) 의 where 절 = _______________________________________
    // 확인 2) (b) 의 where 절 = _______________________________________
    // 확인 3) (b) 결과 건수 = ___
    // 확인 4) (b) 에서 서울이 아닌 고객이 나왔는가 = ___
    //         나왔다면 누구인가 = _______________________________
    // 확인 5) 왜 그렇게 되는가 (한 문장) = ___________________________
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5 — or 를 섞으면 괄호가 사라진다")
    void 문제5() {
        System.out.println("=== (a) 올바른 varargs 방식 ===");
        List<Customer> correct = null;

        // (a) 여기에 작성:


        System.out.println("=== (b) .and().or() 체이닝 방식 ===");
        List<Customer> wrong = null;

        // (b) 여기에 작성:


        correct.forEach(c -> System.out.println("[a] " + c.getName() + " " + c.getCity()));
        wrong.forEach(c -> System.out.println("[b] " + c.getName() + " " + c.getCity()));

        assertThat(correct).hasSize(6);
        // assertThat(wrong).hasSize(__);   // ← 직접 확인한 숫자를 넣으십시오
    }

    // -----------------------------------------------------------------
    // 문제 6. NOT IN + NULL 함정을 재현하고 고치기
    //
    // 요구사항
    //  (a) phone 이 "010-1111-2222" 와 "010-3333-4444" 가 아닌 고객을
    //      notIn 으로 조회합니다. 결과 건수를 확인하십시오.
    //      고객 30명 중 그 두 번호를 쓰는 사람이 2명이므로 상식적으로는 28명입니다.
    //
    //  (b) 아래 phoneNotIn 메서드를 완성해 28건이 나오도록 고칩니다.
    //
    // 확인 1) (a) 결과 건수 = ___
    // 확인 2) 왜 28 이 아닌가 = _______________________________________
    // 확인 3) 이 버그는 데이터가 더 나오는 방향인가, 덜 나오는 방향인가 = ___
    //         그것이 왜 더 위험한가 = ___________________________
    // -----------------------------------------------------------------
    private BooleanExpression phoneNotIn(String... phones) {
        // 여기에 작성:
        return null;
    }

    @Test
    @DisplayName("문제 6 — NOT IN 과 NULL")
    void 문제6() {
        System.out.println("=== (a) 그냥 notIn ===");
        List<Customer> naive = null;

        // (a) 여기에 작성:


        System.out.println("=== (b) 보정한 버전 ===");
        List<Customer> fixed = queryFactory.selectFrom(customer)
                .where(phoneNotIn("010-1111-2222", "010-3333-4444"))
                .fetch();

        System.out.println("naive = " + naive.size());
        System.out.println("fixed = " + fixed.size());

        assertThat(naive).hasSize(25);
        assertThat(fixed).hasSize(28);
    }
}
