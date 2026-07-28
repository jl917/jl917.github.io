package com.example.shop.step04;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
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
 * Step 04 — 조건과 동적 쿼리 : 본문 예제 전체.
 *
 * 이 스텝은 "결과 건수" 가 학습의 핵심입니다.
 * 단언에 정확한 숫자를 박아 두었으니, 숫자가 안 맞으면 shop 스키마를 다시 적재하십시오.
 *
 * 참고 데이터
 *   customers 30명 — VIP 4 / GOLD 9 / SILVER 8 / BRONZE 9, phone NULL 3명
 *   도시        — 서울 8 / 부산 6 / 인천 5 / 대구 4 / 대전 4 / 광주 3
 *   VIP  4명    — 김서준(서울) 류하나(서울) 정  훈(부산) 배채영(대구)
 *   GOLD 9명    — 안지수(서울) 한지호(서울) 오하윤(서울) 문시우(서울)
 *                 강도윤(부산) 윤서아(부산) 임하준(인천) 조은우(대전) 신지아(광주)
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [4-1] BooleanExpression — where() 가 받는 것
    // =================================================================

    @Test
    @DisplayName("[4-1] 조건은 실행이 아니라 값이다")
    void 조건은_값이다() {
        BooleanExpression cond = customer.grade.eq(Grade.VIP);

        // 이 시점까지 SQL 은 한 줄도 나가지 않았습니다.
        System.out.println(cond.getClass().getName());   // BooleanOperation
        System.out.println(cond);                        // customer.grade = VIP

        assertThat(cond).isNotNull();
    }

    @Test
    @DisplayName("[4-1] 조건을 변수에 담아 합친다")
    void 조건을_변수에_담는다() {
        BooleanExpression isVip = customer.grade.eq(Grade.VIP);
        BooleanExpression inSeoul = customer.city.eq("서울");

        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(isVip.and(inSeoul))
                .fetch();

        // 생성 SQL: ... where c1_0.grade = ? and c1_0.city = ?
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Customer::getName)
                .containsExactlyInAnyOrder("김서준", "류하나");
    }

    // =================================================================
    // [4-2] 조건 메서드 총정리
    // =================================================================

    @Test
    @DisplayName("[4-2] like 세 형제 — SQL 은 같고 바인딩만 다르다")
    void like_세_형제() {
        // 네 개를 연달아 실행합니다. 한 화면에서 로그를 비교하는 것이 목적이므로
        // 하나씩 따로 돌리지 마십시오.

        System.out.println("=== startsWith(\"김\") → 바인딩 '김%' ===");
        List<Customer> a = queryFactory.selectFrom(customer)
                .where(customer.name.startsWith("김")).fetch();

        System.out.println("=== contains(\"김\") → 바인딩 '%김%' ===");
        List<Customer> b = queryFactory.selectFrom(customer)
                .where(customer.name.contains("김")).fetch();

        System.out.println("=== endsWith(\"준\") → 바인딩 '%준' ===");
        List<Customer> c = queryFactory.selectFrom(customer)
                .where(customer.name.endsWith("준")).fetch();

        System.out.println("=== like(\"김_\") → 바인딩 '김_' (직접 넣어야 함) ===");
        List<Customer> d = queryFactory.selectFrom(customer)
                .where(customer.name.like("김_")).fetch();

        // 네 SQL 모두 : where c1_0.name like ? escape '!'
        // SQL 로그만으로는 구분할 수 없습니다. 바인딩 로거를 켜야 합니다.
        assertThat(a).hasSize(3);
        assertThat(b).hasSize(3);
        assertThat(c).hasSize(2);
        assertThat(d).isEmpty();
    }

    @Test
    @DisplayName("[4-2] between / goe — 숫자 조건")
    void 숫자_조건() {
        List<Customer> between = queryFactory.selectFrom(customer)
                .where(customer.points.between(10000, 30000)).fetch();
        // 생성 SQL: where c1_0.points between ? and ?

        assertThat(between).hasSize(9);
    }

    @Test
    @DisplayName("[4-2] in — 여러 값을 한 번에")
    void in_조건() {
        List<Customer> result = queryFactory.selectFrom(customer)
                .where(customer.grade.in(Grade.VIP, Grade.GOLD)).fetch();
        // 생성 SQL: where c1_0.grade in (?, ?)

        assertThat(result).hasSize(13);   // VIP 4 + GOLD 9

        // 컬렉션도 받습니다. 같은 SQL 입니다.
        List<Customer> same = queryFactory.selectFrom(customer)
                .where(customer.grade.in(List.of(Grade.VIP, Grade.GOLD))).fetch();
        assertThat(same).hasSize(13);
    }

    @Test
    @DisplayName("[4-2] isNull / isNotNull — 바인딩이 없다")
    void null_조건() {
        List<Customer> noPhone = queryFactory.selectFrom(customer)
                .where(customer.phone.isNull()).fetch();
        // 생성 SQL: where c1_0.phone is null   (바인딩 없음)

        List<Customer> hasPhone = queryFactory.selectFrom(customer)
                .where(customer.phone.isNotNull()).fetch();
        // 생성 SQL: where c1_0.phone is not null

        assertThat(noPhone).hasSize(3);
        assertThat(hasPhone).hasSize(27);
        // 3 + 27 = 30. isNull/isNotNull 은 전체를 정확히 둘로 나눕니다.
    }

    @Test
    @DisplayName("[4-2] isEmpty() 는 not exists 서브쿼리가 된다")
    void 컬렉션_조건() {
        List<Customer> neverOrdered = queryFactory.selectFrom(customer)
                .where(customer.orders.isEmpty()).fetch();

        // 생성 SQL:
        //   where not exists (select 1 from orders o1_0
        //                     where c1_0.customer_id = o1_0.customer_id)
        // 자바 한 줄이 서브쿼리를 만들었습니다. Step 07 에서 직접 씁니다.
        assertThat(neverOrdered).isEmpty();   // 30명 모두 주문 이력이 있습니다
    }

    // =================================================================
    // [4-3] and / or 체이닝과 varargs
    // =================================================================

    @Test
    @DisplayName("[4-3] 체이닝과 varargs 는 같은 SQL 을 만든다")
    void 체이닝과_varargs() {
        System.out.println("=== (1) 체이닝 .and() ===");
        List<Customer> chained = queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP).and(customer.city.eq("서울")))
                .fetch();

        System.out.println("=== (2) varargs 콤마 ===");
        List<Customer> varargs = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.VIP),
                        customer.city.eq("서울")
                )
                .fetch();

        // 두 SQL 모두 : where c1_0.grade = ? and c1_0.city = ?
        // ** where(a, b, c) 의 콤마는 and 입니다. or 가 아닙니다. **
        assertThat(chained).hasSize(2);
        assertThat(varargs).hasSize(2);
    }

    @Test
    @DisplayName("[4-3] 세 개 이상도 전부 and 로 묶인다")
    void varargs_셋() {
        List<Customer> result = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.GOLD),
                        customer.city.eq("서울"),
                        customer.points.goe(10000)
                )
                .fetch();

        assertThat(result).hasSize(3);
    }

    // =================================================================
    // [4-4] where(null) 은 무시된다
    // =================================================================

    @Test
    @DisplayName("[4-4] null 은 SQL 에서 통째로 사라진다")
    void where_null_은_무시된다() {
        System.out.println("=== (1) 가운데가 null ===");
        List<Customer> partial = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.GOLD),
                        null,                            // ← 사라집니다
                        customer.points.goe(10000)
                )
                .fetch();
        // 생성 SQL: where c1_0.grade = ? and c1_0.points >= ?
        // "and null" 도 "and 1=1" 도 아닙니다. 아무 흔적이 없습니다.

        System.out.println("=== (2) 전부 null ===");
        List<Customer> all = queryFactory.selectFrom(customer)
                .where(null, null, null)
                .fetch();
        // 생성 SQL: select ... from customers c1_0
        // ** where 라는 단어 자체가 없습니다. ** 이것을 로그로 확인하십시오.

        assertThat(partial).hasSize(6);
        assertThat(all).hasSize(30);
    }

    @Test
    @DisplayName("[4-4] 그러나 null.and(...) 는 그냥 NPE 입니다")
    void null_체이닝은_NPE() {
        BooleanExpression first = null;

        // 아래 주석을 풀면 NullPointerException 이 납니다.
        // 무시되는 것은 where() 의 인자이지, 체인의 첫 조각이 아닙니다.
        //
        // queryFactory.selectFrom(customer)
        //         .where(first.and(customer.city.eq("서울")))
        //         .fetch();

        assertThat(first).isNull();
    }

    // =================================================================
    // [4-5] 동적 쿼리 — BooleanExpression 반환 메서드 조립
    // =================================================================

    private BooleanExpression gradeEq(Grade grade) {
        return grade != null ? customer.grade.eq(grade) : null;
    }

    private BooleanExpression cityEq(String city) {
        // != null 이 아니라 hasText 입니다.
        // HTTP 파라미터는 미입력 시 빈 문자열로 들어오는 경우가 많습니다.
        return StringUtils.hasText(city) ? customer.city.eq(city) : null;
    }

    private BooleanExpression pointsGoe(Integer minPoints) {
        return minPoints != null ? customer.points.goe(minPoints) : null;
    }

    /** if 가 한 번도 없습니다. */
    private List<Customer> search(Grade grade, String city, Integer minPoints) {
        return queryFactory
                .selectFrom(customer)
                .where(gradeEq(grade), cityEq(city), pointsGoe(minPoints))
                .fetch();
    }

    @Test
    @DisplayName("[4-5] 하나의 메서드가 만드는 여섯 가지 SQL")
    void 동적쿼리_조합_여섯가지() {
        System.out.println("=== (1) 조건 없음 ===");
        assertThat(search(null, null, null)).hasSize(30);

        System.out.println("=== (2) 등급만 ===");
        assertThat(search(Grade.GOLD, null, null)).hasSize(9);

        System.out.println("=== (3) 도시만 ===");
        assertThat(search(null, "서울", null)).hasSize(8);

        System.out.println("=== (4) 최소 포인트만 ===");
        assertThat(search(null, null, 10000)).hasSize(12);

        System.out.println("=== (5) 등급 + 도시 ===");
        assertThat(search(Grade.GOLD, "서울", null)).hasSize(4);

        System.out.println("=== (6) 셋 다 ===");
        assertThat(search(Grade.GOLD, "서울", 10000)).hasSize(3);
    }

    @Test
    @DisplayName("[4-5] 빈 문자열이 조건으로 붙지 않는 것을 확인한다")
    void 빈문자열_처리() {
        // hasText 덕분에 "" 는 조건이 되지 않습니다.
        // city != null 로만 검사했다면 where c1_0.city = '' 가 붙어 0건이 됩니다.
        assertThat(search(null, "", null)).hasSize(30);
        assertThat(search(null, "   ", null)).hasSize(30);
    }

    /** 비즈니스 규칙을 한 곳에 모읍니다. */
    private BooleanExpression isPremium() {
        return customer.grade.in(Grade.VIP, Grade.GOLD)
                .and(customer.points.goe(10000));
    }

    @Test
    @DisplayName("[4-5] 조건 메서드끼리 합쳐 비즈니스 규칙을 만든다")
    void 조건_재사용() {
        List<Customer> premium = queryFactory.selectFrom(customer)
                .where(isPremium())
                .fetch();

        // 생성 SQL: where c1_0.grade in (?, ?) and c1_0.points >= ?
        // "우수 고객" 의 정의가 코드 한 곳에 있습니다. 규칙이 바뀌면 여기만 고칩니다.
        assertThat(premium).hasSize(10);
    }

    // =================================================================
    // [4-6] BooleanBuilder
    // =================================================================

    private List<Customer> searchWithBuilder(Grade grade, String city, Integer minPoints) {
        BooleanBuilder builder = new BooleanBuilder();

        if (grade != null) {
            builder.and(customer.grade.eq(grade));
        }
        if (StringUtils.hasText(city)) {
            builder.and(customer.city.eq(city));
        }
        if (minPoints != null) {
            builder.and(customer.points.goe(minPoints));
        }

        return queryFactory.selectFrom(customer).where(builder).fetch();
    }

    @Test
    @DisplayName("[4-6] BooleanBuilder 는 같은 SQL 을 만든다")
    void builder_방식() {
        List<Customer> byBuilder = searchWithBuilder(Grade.GOLD, "서울", 10000);
        List<Customer> byExpression = search(Grade.GOLD, "서울", 10000);

        // 생성 SQL 이 완전히 같습니다.
        // 차이는 성능이 아니라 가독성 / 재사용 / 조합성 / null 처리입니다.
        assertThat(byBuilder).hasSize(3);
        assertThat(byExpression).hasSize(3);
    }

    @Test
    @DisplayName("[4-6] 빈 빌더는 조용히 '조건 없음' 이 된다")
    void 빈_빌더의_위험() {
        BooleanBuilder empty = new BooleanBuilder();

        List<Customer> all = queryFactory.selectFrom(customer).where(empty).fetch();

        // 생성 SQL 에 where 절이 없습니다. 전체 조회입니다.
        // 30행이면 괜찮지만, 삭제/수정 쿼리에서 이러면 전체 행이 대상이 됩니다.
        assertThat(all).hasSize(30);
    }

    // =================================================================
    // [4-7] ⚠️ or 를 섞으면 괄호가 사라진다 — 이 스텝의 핵심
    // =================================================================

    @Test
    @DisplayName("[4-7] 의도 6건 / 실수① 11건 / 실수② 8건")
    void or_함정_세가지() {
        // 목표: "VIP 또는 GOLD 이면서, 서울에 사는 고객" → (VIP or GOLD) and 서울 = 6명

        System.out.println("=== 의도한 코드 — varargs 로 나눈다 ===");
        List<Customer> intended = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.VIP).or(customer.grade.eq(Grade.GOLD)),
                        customer.city.eq("서울")
                )
                .fetch();
        // where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?
        //       ^^^ 괄호가 붙었습니다

        System.out.println("=== 실수 ① — .and() 와 .or() 를 이어 붙였다 ===");
        List<Customer> mistake1 = queryFactory.selectFrom(customer)
                .where(
                        customer.city.eq("서울")
                                .and(customer.grade.eq(Grade.VIP))
                                .or(customer.grade.eq(Grade.GOLD))
                )
                .fetch();
        // where c1_0.city = ? and c1_0.grade = ? or c1_0.grade = ?
        //       괄호가 없습니다 → (서울 and VIP) or GOLD
        //       부산의 강도윤, 인천의 임하준, 광주의 신지아가 들어옵니다.

        System.out.println("=== 실수 ② — 괄호 위치를 잘못 잡았다 ===");
        List<Customer> mistake2 = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.VIP)
                                .or(customer.grade.eq(Grade.GOLD).and(customer.city.eq("서울")))
                )
                .fetch();
        // where c1_0.grade = ? or c1_0.grade = ? and c1_0.city = ?
        //       A or (B and C) → 전국 VIP 4명 + 서울 GOLD 4명

        // ** 실수 쪽 단언이 통과하는 것이 정상입니다. 버그를 재현하는 테스트입니다. **
        assertThat(intended).hasSize(6);
        assertThat(mistake1).hasSize(11);
        assertThat(mistake2).hasSize(8);

        // 셋 다 컴파일 성공, 실행 성공, 예외 없음. 결과 건수만 다릅니다.
        System.out.println("의도=" + intended.size()
                + " 실수①=" + mistake1.size()
                + " 실수②=" + mistake2.size());
    }

    /** 처방 1 — or 그룹을 이름 있는 메서드로 뽑는다. */
    private BooleanExpression isVipOrGold() {
        return customer.grade.eq(Grade.VIP).or(customer.grade.eq(Grade.GOLD));
    }

    @Test
    @DisplayName("[4-7] 처방 세 가지 — 전부 6건으로 수렴한다")
    void or_처방_세가지() {
        System.out.println("=== 처방 1 — or 그룹을 메서드로 추출 ===");
        List<Customer> fix1 = queryFactory.selectFrom(customer)
                .where(isVipOrGold(), customer.city.eq("서울"))
                .fetch();
        // where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?

        System.out.println("=== 처방 2 — Expressions.allOf / anyOf ===");
        List<Customer> fix2 = queryFactory.selectFrom(customer)
                .where(
                        Expressions.allOf(
                                Expressions.anyOf(
                                        customer.grade.eq(Grade.VIP),
                                        customer.grade.eq(Grade.GOLD)
                                ),
                                customer.city.eq("서울")
                        )
                )
                .fetch();
        // where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?

        System.out.println("=== 처방 3 — 같은 컬럼의 or 는 in 으로 ===");
        List<Customer> fix3 = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.in(Grade.VIP, Grade.GOLD),
                        customer.city.eq("서울")
                )
                .fetch();
        // where c1_0.grade in (?, ?) and c1_0.city = ?
        // or 가 아예 사라졌으니 괄호를 걱정할 일도 없습니다.

        assertThat(fix1).hasSize(6);
        assertThat(fix2).hasSize(6);
        assertThat(fix3).hasSize(6);
    }

    // =================================================================
    // [4-8] null 안전 조건 — isNull() vs eq(null)
    // =================================================================

    @Test
    @DisplayName("[4-8] eq(null) 에 의미를 기대하지 마십시오")
    void eq_null_은_쓰지_마십시오() {
        // 이 테스트는 의도적으로 아무것도 단언하지 않습니다.
        // eq(null) 의 동작은 버전과 경로에 따라 다를 수 있어
        // 고정된 기대값을 박을 수 없기 때문입니다.
        //
        // 가능한 갈래
        //   (a) 조건을 무시 (null 반환) → 전체 30건
        //   (b) 인자 검증 예외        → 즉시 실패
        //   (c) = ? 에 null 바인딩    → 3값 논리로 0건
        //
        // (a)와 (c)는 정반대 결과입니다.
        // 여러분의 환경에서 어느 갈래로 가는지 직접 확인하고 아래 주석에 적어 두십시오.
        //
        // 내 환경의 결과 = ___________

        String nullPhone = null;
        try {
            List<Customer> result = queryFactory.selectFrom(customer)
                    .where(customer.phone.eq(nullPhone))
                    .fetch();
            System.out.println("eq(null) 결과 건수 = " + result.size());
        } catch (RuntimeException e) {
            System.out.println("eq(null) 예외 = " + e.getClass().getName()
                    + " : " + e.getMessage());
        }

        // 처방 — 명시적으로 씁니다.
        List<Customer> explicit = queryFactory.selectFrom(customer)
                .where(nullPhone != null
                        ? customer.phone.eq(nullPhone)
                        : customer.phone.isNull())
                .fetch();
        assertThat(explicit).hasSize(3);
    }

    /** 조건을 빼는 것이 의도라면 이 패턴을 쓰십시오. */
    private BooleanExpression phoneEq(String phone) {
        return phone != null ? customer.phone.eq(phone) : null;
    }

    @Test
    @DisplayName("[4-8] 조건을 빼고 싶으면 null 반환을 쓴다")
    void 조건을_빼는_패턴() {
        assertThat(queryFactory.selectFrom(customer).where(phoneEq(null)).fetch())
                .hasSize(30);
    }

    // =================================================================
    // [4-9] NOT IN 과 NULL — 3값 논리는 그대로 적용된다
    // =================================================================

    @Test
    @DisplayName("[4-9] notIn 은 NULL 인 행을 통째로 누락시킨다")
    void notIn_함정() {
        List<Customer> result = queryFactory.selectFrom(customer)
                .where(customer.phone.notIn("010-1111-2222", "010-3333-4444"))
                .fetch();

        // 생성 SQL: where c1_0.phone not in (?, ?)
        //
        // 30명 - 해당 번호 2명 = 28명을 기대했지만 25건입니다.
        // phone 이 NULL 인 3명이 빠졌습니다.
        //
        //   phone NOT IN (a, b)  ≡  phone != a AND phone != b
        //   phone 이 NULL 이면 각 항이 UNKNOWN
        //   UNKNOWN AND UNKNOWN = UNKNOWN
        //   WHERE 는 UNKNOWN 을 통과시키지 않는다 → 제외
        assertThat(result).hasSize(25);
    }

    /** 처방 1 — NULL 을 명시적으로 살린다. */
    private BooleanExpression phoneNotIn(String... phones) {
        return customer.phone.notIn(phones).or(customer.phone.isNull());
    }

    @Test
    @DisplayName("[4-9] 처방 1 — or isNull() 로 보정")
    void notIn_처방_orIsNull() {
        List<Customer> result = queryFactory.selectFrom(customer)
                .where(phoneNotIn("010-1111-2222", "010-3333-4444"))
                .fetch();

        // 생성 SQL: where c1_0.phone not in (?, ?) or c1_0.phone is null
        assertThat(result).hasSize(28);
    }

    @Test
    @DisplayName("[4-9] 처방 2 — coalesce. 맞지만 인덱스를 못 탄다")
    void notIn_처방_coalesce() {
        List<Customer> result = queryFactory.selectFrom(customer)
                .where(customer.phone.coalesce("").asString()
                        .notIn("010-1111-2222", "010-3333-4444"))
                .fetch();

        // 생성 SQL: where coalesce(c1_0.phone, ?) not in (?, ?)
        // 컬럼을 함수로 감쌌으므로 인덱스를 못 씁니다.
        // "컬럼을 가공하지 말고 리터럴을 가공하라" 는 규칙 위반입니다.
        assertThat(result).hasSize(28);
    }

    // =================================================================
    // [4-10] 검색 조건 객체
    // =================================================================

    public record ProductSearchCond(
            String nameKeyword,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            ProductStatus status,
            Boolean inStockOnly
    ) {}

    private BooleanExpression nameContains(String keyword) {
        return StringUtils.hasText(keyword) ? product.name.contains(keyword) : null;
    }
    private BooleanExpression categoryEq(Long categoryId) {
        return categoryId != null ? product.category.id.eq(categoryId) : null;
    }
    private BooleanExpression priceGoe(BigDecimal min) {
        return min != null ? product.price.goe(min) : null;
    }
    private BooleanExpression priceLoe(BigDecimal max) {
        return max != null ? product.price.loe(max) : null;
    }
    private BooleanExpression statusEq(ProductStatus status) {
        return status != null ? product.status.eq(status) : null;
    }
    private BooleanExpression inStock(Boolean only) {
        // only 가 null 일 때 only == true 는 언박싱 NPE 입니다.
        // Boolean.TRUE.equals(only) 는 null 에 안전합니다.
        return Boolean.TRUE.equals(only) ? product.stock.gt(0) : null;
    }

    private List<Product> searchProducts(ProductSearchCond cond) {
        return queryFactory
                .selectFrom(product)
                .where(
                        nameContains(cond.nameKeyword()),
                        categoryEq(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        statusEq(cond.status()),
                        inStock(cond.inStockOnly())
                )
                .fetch();
    }

    @Test
    @DisplayName("[4-10] 여섯 조건 중 넷만 SQL 에 나간다")
    void 검색조건_객체() {
        List<Product> result = searchProducts(new ProductSearchCond(
                "노트북",
                null,                          // ← 사라짐
                new BigDecimal("500000"),
                null,                          // ← 사라짐
                ProductStatus.ON_SALE,
                true
        ));

        // 생성 SQL:
        //   where p1_0.name like ? escape '!'
        //     and p1_0.price >= ?
        //     and p1_0.status = ?
        //     and p1_0.stock > ?
        result.forEach(p -> System.out.println(p.getName() + " / " + p.getPrice()));
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("[4-10] 전부 null 이면 전체 조회")
    void 검색조건_객체_비어있음() {
        List<Product> all = searchProducts(
                new ProductSearchCond(null, null, null, null, null, null));
        assertThat(all).hasSize(40);
    }

    // =================================================================
    // [4-11] MySQL8 코스와 나란히
    // =================================================================

    @Test
    @DisplayName("[4-11] SQL 11개를 QueryDSL 로 옮긴다")
    void mysql8_대조() {
        assertThat(queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP)).fetch()).hasSize(4);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.grade.ne(Grade.VIP)).fetch()).hasSize(26);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.grade.in(Grade.VIP, Grade.GOLD)).fetch()).hasSize(13);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.points.between(10000, 30000)).fetch()).hasSize(9);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.points.goe(10000)).fetch()).hasSize(12);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.name.startsWith("김")).fetch()).hasSize(3);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.name.contains("김")).fetch()).hasSize(3);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.phone.isNull()).fetch()).hasSize(3);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP), customer.city.eq("서울")).fetch())
                .hasSize(2);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.grade.in(Grade.VIP, Grade.GOLD), customer.city.eq("서울"))
                .fetch()).hasSize(6);

        assertThat(queryFactory.selectFrom(customer)
                .where(customer.phone.notIn("010-1111-2222", "010-3333-4444")).fetch())
                .hasSize(25);   // ← NULL 3명 누락. 28 이 아닙니다.
    }
}
