package com.example.shop.step03;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.Tuple;
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
import java.util.Optional;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 03 — 연습문제 6개.
 *
 * 규칙
 *  - 각 문제의 "여기에 작성:" 아래를 채웁니다.
 *  - 코드를 완성한 뒤 반드시 콘솔의 hibernate.SQL 로그를 확인하고,
 *    주석 안의 빈칸(___)을 직접 채우십시오. 답을 주석에 적는 것이 문제의 일부입니다.
 *  - 정답은 Solution.java 에 있습니다. 먼저 풀어 보십시오.
 */
@SpringBootTest
@Transactional
@SuppressWarnings("deprecation")   // 문제 4 에서 fetchCount() 를 일부러 호출합니다
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // -----------------------------------------------------------------
    // 문제 1. 특정 컬럼만 조회하고 select 절 컬럼 수를 확인하기
    //
    // 요구사항
    //  - products 에서 price >= 100000 인 상품의 "이름과 가격만" 조회합니다.
    //  - 반환 타입은 List<Tuple> 이 됩니다.
    //  - 실행 후 hibernate.SQL 로그를 보고 아래 빈칸을 채우십시오.
    //
    // 확인 1) select 절에 나온 컬럼 개수 = ___
    // 확인 2) selectFrom(product) 로 바꿨다면 몇 개였을까 = ___
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 1 — 특정 컬럼만 조회")
    void 문제1() {
        List<Tuple> rows = null;

        // 여기에 작성:


        rows.forEach(t -> System.out.println(
                t.get(product.name) + " / " + t.get(product.price)));
        assertThat(rows).isNotEmpty();
    }

    // -----------------------------------------------------------------
    // 문제 2. fetchOne() 이 0건에서 null 을 돌려주는 것을 단언하기
    //
    // 요구사항
    //  - 존재하지 않는 이메일("ghost@example.com")로 고객 1명을 조회합니다.
    //  - fetchOne() 을 사용합니다.
    //  - 결과가 예외가 아니라 null 임을 assertThat(...).isNull() 로 단언합니다.
    //
    // 확인) SQL 은 정상적으로 나갔는가? (예/아니오) = ___
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2 — 0건이면 null")
    void 문제2() {
        Customer found = null;

        // 여기에 작성:


        assertThat(found).isNull();
    }

    // -----------------------------------------------------------------
    // 문제 3. fetchOne() 의 예외와 fetchFirst() 의 limit 를 대조하기
    //
    // 요구사항
    //  (a) grade = SILVER 인 고객(8명)에 fetchOne() 을 호출해
    //      NonUniqueResultException 이 발생함을 확인합니다.
    //      힌트: assertThatThrownBy(() -> ...).isInstanceOf(...)
    //      주의: import 는 com.querydsl.core.NonUniqueResultException 입니다.
    //  (b) 같은 조건을 fetchFirst() 로 바꿔 예외 없이 1건이 나오는지 확인합니다.
    //
    // 확인 1) (a)의 SQL 에 limit 이 있는가 = ___
    // 확인 2) (b)의 SQL 에 limit 이 있는가 = ___
    // 확인 3) (a)에서 DB 가 돌려준 행 수는 몇 건인가 (예외 메시지에 찍힙니다) = ___
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 3 — fetchOne 예외 vs fetchFirst limit")
    void 문제3() {
        // (a) 여기에 작성:


        // (b) 여기에 작성:
        Customer first = null;


        assertThat(first).isNotNull();
        assertThat(first.getGrade()).isEqualTo(Grade.SILVER);
    }

    // -----------------------------------------------------------------
    // 문제 4. fetchCount() 와 직접 작성한 count 쿼리를 비교하기
    //
    // 요구사항
    //  (a) status = ON_SALE 인 상품 수를 deprecated 인 fetchCount() 로 셉니다.
    //  (b) 같은 결과를 select(product.count()) 로 직접 작성해 셉니다.
    //  (c) 두 값이 같은지 단언합니다.
    //
    // 확인) 두 SQL 이 같은가 = ___
    //       같다면, 어떤 조건에서 달라지는가 = ___________________
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 4 — fetchCount vs 직접 작성")
    void 문제4() {
        long byDeprecated = -1;
        Long byManual = null;

        // (a) 여기에 작성:


        // (b) 여기에 작성:


        assertThat(byManual).isEqualTo(byDeprecated);
    }

    // -----------------------------------------------------------------
    // 문제 5. distinct 유무에 따른 건수 차이를 기록하기
    //
    // 요구사항
    //  - orders 의 shippingCity 를 중복 없이 조회합니다 (distinct 사용).
    //  - 같은 쿼리를 distinct 없이 한 번 더 실행합니다.
    //  - 두 결과의 size() 를 콘솔에 찍습니다.
    //
    // 확인 1) distinct 있음 = ___ 건
    // 확인 2) distinct 없음 = ___ 건
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5 — distinct 유무 비교")
    void 문제5() {
        List<String> withDistinct = null;
        List<String> withoutDistinct = null;

        // 여기에 작성:


        System.out.println("distinct 있음 = " + withDistinct.size());
        System.out.println("distinct 없음 = " + withoutDistinct.size());
        assertThat(withDistinct.size()).isLessThan(withoutDistinct.size());
    }

    // -----------------------------------------------------------------
    // 문제 6. Optional 반환으로 리팩터링하기
    //
    // 요구사항
    //  - 아래 findByEmail 메서드를 완성합니다.
    //    fetchOne() 의 결과를 Optional.ofNullable(...) 로 감싸 반환합니다.
    //  - 테스트에서
    //      (a) 존재하는 이메일 → orElseThrow() 로 꺼내 이름이 "김서준" 인지 확인
    //      (b) 없는 이메일 → orElseThrow(CustomerNotFoundException::new) 가
    //          예외를 던지는지 확인
    //
    // 확인) null 반환 대신 Optional 을 쓰면 무엇이 달라지는가 = ___________________
    // -----------------------------------------------------------------
    private Optional<Customer> findByEmail(String email) {
        // 여기에 작성:
        return Optional.empty();
    }

    @Test
    @DisplayName("문제 6 — Optional 반환")
    void 문제6() {
        // (a) 여기에 작성:


        // (b) 여기에 작성:


    }

    /** 문제 6 에서 사용할 예외입니다. 수정할 필요 없습니다. */
    static class CustomerNotFoundException extends RuntimeException {
        CustomerNotFoundException() {
            super("고객을 찾을 수 없습니다");
        }

        CustomerNotFoundException(String email) {
            super("고객을 찾을 수 없습니다: " + email);
        }
    }
}
