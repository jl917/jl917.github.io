package com.example.shop.step04;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.Product;
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
 * Step 04 — 연습문제 정답과 해설.
 *
 * 문제를 먼저 풀어 본 뒤에 여십시오.
 * 각 정답에는 "왜 그 답인가" 를 설명하는 긴 주석이 붙어 있습니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 정답 1 — contains 의 바인딩 값
    // =================================================================
    //
    // 확인 1) like 에 바인딩된 값 = '%노트북%'
    // 확인 2) SQL 에 % 문자가 보이는가 = 아니오
    //
    // 생성 SQL
    //   select p1_0.product_id, p1_0.attrs, p1_0.category_id, p1_0.cost,
    //          p1_0.created_at, p1_0.name, p1_0.price, p1_0.status, p1_0.stock
    //   from products p1_0
    //   where p1_0.name like ? escape '!' and p1_0.price >= ?
    //   바인딩: [1] '%노트북%'  [2] 500000.00
    //
    // 여기서 배울 것이 두 가지입니다.
    //
    // 첫째, % 는 SQL 문자열이 아니라 ** 바인딩 파라미터 값 ** 으로 들어갑니다.
    // 그래서 SQL 로그를 아무리 들여다봐도 검색어가 무엇이었는지 알 수 없습니다.
    // 운영 로그에서 "이 쿼리가 왜 느리지" 를 볼 때 like ? 만 보이면 손을 쓸 수 없습니다.
    // org.hibernate.orm.jdbc.bind 로거를 반드시 켜 두십시오.
    //
    // 둘째, escape '!' 는 QueryDSL 이 자동으로 붙입니다.
    // 사용자가 검색창에 "50%" 를 입력해도 '%50!%%' 로 이스케이프되어
    // "50% 라는 글자" 를 찾습니다. JPQL 문자열을 손으로 조립하면 이 처리를
    // 직접 해야 하고, 대부분 잊습니다. 잊으면 "50%" 검색이 사실상 전체 조회가 됩니다.
    //
    // 그리고 varargs 로 나열한 이유. 두 조건 모두 and 이므로 체이닝해도 같은 SQL 이지만,
    // 여기서 or 가 하나라도 끼어드는 순간 4-7 의 함정이 시작됩니다.
    // ** and 만 있어도 varargs 를 습관으로 삼는 것 ** 이 그 함정의 예방책입니다.
    // =================================================================
    @Test
    @DisplayName("정답 1 — contains 의 바인딩 값")
    void 정답1() {
        List<Product> result = queryFactory
                .selectFrom(product)
                .where(
                        product.name.contains("노트북"),
                        product.price.goe(new BigDecimal("500000"))
                )
                .fetch();

        result.forEach(p -> System.out.println(p.getName() + " / " + p.getPrice()));
        assertThat(result).hasSize(2);
    }

    // =================================================================
    // 정답 2 — like 세 형제의 SQL 과 바인딩
    // =================================================================
    //
    //   | 코드              | 생성 SQL                              | 바인딩 | 건수 |
    //   |-------------------|---------------------------------------|--------|------|
    //   | startsWith("김")  | where c1_0.name like ? escape '!'     | '김%'  |  3   |
    //   | contains("김")    | where c1_0.name like ? escape '!'     | '%김%' |  3   |
    //   | endsWith("준")    | where c1_0.name like ? escape '!'     | '%준'  |  2   |
    //
    // 확인) 세 SQL 이 같은가 = 예. 글자 하나 다르지 않습니다.
    //       SQL 로그만으로 구분할 수 있는가 = 아니오.
    //       구분하려면 = org.hibernate.orm.jdbc.bind 로거 (Hibernate 6 기준)
    //
    // Hibernate 5 시절의 org.hibernate.type.descriptor.sql.BasicBinder 를
    // 그대로 복사해 두면 SQL 만 찍히고 바인딩은 영원히 안 보입니다.
    // 이 코스 전체가 "코드 → 생성 SQL" 대조로 진행되므로 이 설정은 필수입니다.
    //
    // ── 성능 이야기 ──
    //
    // startsWith 와 contains 는 건수가 같지만 성능은 전혀 다릅니다.
    //
    //   '김%'  → 앞부분이 고정 → B+Tree 인덱스를 탈 수 있다 (range scan)
    //   '%김%' → 앞에 % → 인덱스를 탈 수 없다 (full scan)
    //
    // MySQL8 코스 Step 15 — 인덱스에서 확인한 "인덱스를 못 타는 5가지 패턴" 중 하나입니다.
    // customers 30행에서는 체감이 0 입니다. 그래서 개발 중에 발견되지 않습니다.
    // 같은 코드가 수십만 행 테이블로 옮겨가면 그대로 장애가 됩니다.
    //
    // 실무 판단 순서
    //   1) startsWith 로 요구사항을 만족할 수 있는가? → 만족하면 그것으로.
    //   2) 안 되면 전문 검색(Full-Text) 인덱스를 검토.
    //   3) 그것도 부족하면 검색 엔진(Elasticsearch 등) 분리.
    // "일단 contains 로 해 두고 나중에 최적화" 는 대부분 나중이 오지 않습니다.
    //
    // 참고로 like("김_") 는 0건입니다. contains 와 달리 % 나 _ 를 자동으로
    // 붙여 주지 않으므로, 와일드카드를 직접 넣어야 합니다.
    // "김" 다음에 정확히 한 글자인 이름이 없으므로 0건입니다.
    // =================================================================
    @Test
    @DisplayName("정답 2 — like 세 형제의 SQL 과 바인딩")
    void 정답2() {
        System.out.println("=== startsWith(\"김\") ===");
        List<Customer> starts = queryFactory.selectFrom(customer)
                .where(customer.name.startsWith("김")).fetch();

        System.out.println("=== contains(\"김\") ===");
        List<Customer> contains = queryFactory.selectFrom(customer)
                .where(customer.name.contains("김")).fetch();

        System.out.println("=== endsWith(\"준\") ===");
        List<Customer> ends = queryFactory.selectFrom(customer)
                .where(customer.name.endsWith("준")).fetch();

        assertThat(starts).hasSize(3);
        assertThat(contains).hasSize(3);
        assertThat(ends).hasSize(2);
    }

    // =================================================================
    // 정답 3 — BooleanExpression 방식 동적 검색
    // =================================================================
    //
    // 확인) search 메서드 안에 if 가 몇 개 있는가 = 0개.
    //       if 는 조건 메서드 안에 하나씩, 총 3개만 존재합니다.
    //
    // 하나의 search 메서드가 만들어 내는 SQL 을 전부 나열합니다.
    //
    //  (1) search(null, null, null)
    //      select c1_0.customer_id, ... from customers c1_0
    //      바인딩: 없음 / 30건
    //      ** where 라는 단어 자체가 없습니다. **
    //
    //  (2) search(GOLD, null, null)
    //      ... where c1_0.grade = ?
    //      바인딩: [1] GOLD / 9건
    //
    //  (3) search(null, "서울", null)
    //      ... where c1_0.city = ?
    //      바인딩: [1] '서울' / 8건
    //
    //  (4) search(null, null, 10000)
    //      ... where c1_0.points >= ?
    //      바인딩: [1] 10000 / 12건
    //
    //  (5) search(GOLD, "서울", null)
    //      ... where c1_0.grade = ? and c1_0.city = ?
    //      바인딩: [1] GOLD [2] '서울' / 4건
    //
    //  (6) search(GOLD, "서울", 10000)
    //      ... where c1_0.grade = ? and c1_0.city = ? and c1_0.points >= ?
    //      바인딩: [1] GOLD [2] '서울' [3] 10000 / 3건
    //
    // 여섯 가지 SQL 이 다섯 줄짜리 메서드 하나에서 나왔습니다.
    // 원리는 4-4 한 줄뿐입니다. ** where() 의 인자가 null 이면 그 조건이 소거된다. **
    // "and null" 도 "and 1=1" 도 아니고 완전 소거입니다. 그래서 JPQL 문자열 조립에서
    // 쓰던 "where 1=1" 관용구가 필요 없습니다.
    //
    // ── cityEq 에 hasText 를 쓴 이유 ──
    //
    // city != null 만 검사하면 빈 문자열이 통과합니다.
    //   → where c1_0.city = ''  → 0건
    // 사용자는 도시를 입력하지 않았는데 결과가 비어 있습니다.
    // 예외도 없고 로그도 정상입니다. 화면만 비어 있습니다.
    // HTTP 요청 파라미터는 미입력 시 null 이 아니라 "" 로 들어오는 경우가 흔하므로,
    // ** 문자열 동적 조건에는 무조건 StringUtils.hasText ** 로 기억하십시오.
    // (hasText 는 공백만 있는 "   " 도 걸러 줍니다.)
    //
    // ── 이 패턴의 진짜 이득 ──
    //
    // 조건 메서드가 BooleanExpression 을 반환한다는 것은
    // ** 조건끼리 다시 합칠 수 있다 ** 는 뜻입니다.
    //
    //   private BooleanExpression isPremium() {
    //       return customer.grade.in(VIP, GOLD).and(customer.points.goe(10000));
    //   }
    //
    // "우수 고객" 이라는 비즈니스 규칙이 코드 한 곳에 있습니다.
    // 규칙이 바뀌면 이 메서드만 고칩니다. 여러 쿼리에 흩어진 where 절을
    // 찾아다닐 필요가 없습니다. BooleanBuilder 로는 이렇게 되지 않습니다 (정답 4).
    //
    // 단, 주의점 하나. gradeEq(null) 은 null 을 반환하므로
    // gradeEq(g).and(...) 처럼 체이닝하면 NPE 입니다.
    // ** 동적 조건 메서드는 varargs 자리에만 넣으십시오. **
    // =================================================================
    private BooleanExpression gradeEq(Grade grade) {
        return grade != null ? customer.grade.eq(grade) : null;
    }

    private BooleanExpression cityEq(String city) {
        return StringUtils.hasText(city) ? customer.city.eq(city) : null;
    }

    private BooleanExpression pointsGoe(Integer minPoints) {
        return minPoints != null ? customer.points.goe(minPoints) : null;
    }

    private List<Customer> search(Grade grade, String city, Integer minPoints) {
        return queryFactory
                .selectFrom(customer)
                .where(gradeEq(grade), cityEq(city), pointsGoe(minPoints))
                .fetch();
    }

    @Test
    @DisplayName("정답 3 — BooleanExpression 방식 동적 검색")
    void 정답3() {
        System.out.println("=== (1) 조건 없음 ===");
        assertThat(search(null, null, null)).hasSize(30);

        System.out.println("=== (2) 등급만 ===");
        assertThat(search(Grade.GOLD, null, null)).hasSize(9);

        System.out.println("=== (3) 도시만 ===");
        assertThat(search(null, "서울", null)).hasSize(8);

        System.out.println("=== (4) 포인트만 ===");
        assertThat(search(null, null, 10000)).hasSize(12);

        System.out.println("=== (5) 등급 + 도시 ===");
        assertThat(search(Grade.GOLD, "서울", null)).hasSize(4);

        System.out.println("=== (6) 셋 다 ===");
        assertThat(search(Grade.GOLD, "서울", 10000)).hasSize(3);

        // 빈 문자열은 조건이 되지 않습니다.
        assertThat(search(null, "", null)).hasSize(30);
        assertThat(search(null, "   ", null)).hasSize(30);
    }

    // =================================================================
    // 정답 4 — BooleanBuilder 방식
    // =================================================================
    //
    // 확인 1) 두 SQL 이 같은가 = 예. 완전히 같습니다.
    // 확인 2) searchWithBuilder 안의 if = 3개 (조립부에 노출)
    // 확인 3) 줄 수 = search 는 5줄 / searchWithBuilder 는 13줄
    // 확인 4) 조건 6개로 늘면 = search 는 +3줄(인자 3줄) / builder 는 +9줄(if 블록 3개)
    //
    // ** 성능은 같습니다. ** 그러므로 선택 기준은 성능이 아닙니다.
    //
    //   가독성   : where(gradeEq(g), cityEq(c), pointsGoe(p)) 한 줄이면
    //              후보 조건 전부가 눈에 들어옵니다. 빌더는 if 블록을
    //              위에서 아래로 읽어야 합니다.
    //
    //   재사용   : gradeEq(g) 는 다른 쿼리에서 그대로 호출됩니다.
    //              빌더는 조립 로직을 통째로 복사하거나 메서드로 추출해야 합니다.
    //
    //   조합성   : gradeEq(g).and(cityEq(c)) 로 조건끼리 합쳐
    //              isPremium() 같은 이름 있는 규칙을 만들 수 있습니다.
    //              builder.and(otherBuilder) 는 되긴 하지만 결과가
    //              무엇인지(괄호가 어떻게 묶이는지) 코드만 보고 알기 어렵습니다.
    //
    //   테스트   : gradeEq(null) == null 을 단독으로 단언할 수 있습니다.
    //              빌더는 쿼리를 실행해야 검증됩니다.
    //
    // ── 빈 빌더의 위험 ──
    //
    //   BooleanBuilder builder = new BooleanBuilder();
    //   // if 조건이 전부 false 여서 아무것도 안 붙었다면?
    //   queryFactory.selectFrom(customer).where(builder).fetch();
    //   → where 절 없음 → 전체 30건
    //
    // 조회면 느릴 뿐이지만, 이 패턴이 update/delete 로 옮겨가면 전체 행이 대상입니다.
    // BooleanExpression 방식도 전부 null 이면 결과는 같지만,
    // where(gradeEq(g), cityEq(c)) 라는 코드는 "조건이 없을 수도 있다" 는 사실이
    // ** 호출부에 그대로 드러납니다. ** 빌더는 조립 로직 안에 숨습니다.
    // 위험의 크기가 같아도, 보이는 위험이 안 보이는 위험보다 낫습니다.
    //
    // ── 그래도 빌더를 쓸 때 ──
    //
    // 조건 개수가 런타임에 정해지는 경우(사용자가 필터를 N개 추가하는 화면)에는
    // 반복문 안에서 builder.and(...) 를 누적하는 편이 자연스럽습니다.
    // ** 기본은 BooleanExpression, 반복 누적이 필요할 때만 빌더. **
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
    @DisplayName("정답 4 — BooleanBuilder 방식")
    void 정답4() {
        assertThat(searchWithBuilder(Grade.GOLD, "서울", 10000)).hasSize(3);
        assertThat(searchWithBuilder(null, null, null)).hasSize(30);

        assertThat(searchWithBuilder(Grade.GOLD, null, null).size())
                .isEqualTo(search(Grade.GOLD, null, null).size());

        // 빈 빌더는 조용히 전체 조회입니다.
        List<Customer> all = queryFactory.selectFrom(customer)
                .where(new BooleanBuilder()).fetch();
        assertThat(all).hasSize(30);
    }

    // =================================================================
    // 정답 5 — or 를 섞으면 괄호가 사라진다  (가장 중요한 정답)
    // =================================================================
    //
    // 확인 1) (a) where 절 = where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?
    // 확인 2) (b) where 절 = where c1_0.city = ? and c1_0.grade = ? or c1_0.grade = ?
    // 확인 3) (b) 결과 건수 = 11건
    // 확인 4) 서울이 아닌 고객이 나왔는가 = 예.
    //         강도윤(부산), 윤서아(부산), 임하준(인천), 조은우(대전), 신지아(광주)
    // 확인 5) 왜 = 메서드 체이닝에는 연산자 우선순위가 없고 왼쪽부터 묶이므로
    //         (서울 and VIP) or GOLD 가 되어, GOLD 는 도시 조건 없이 전부 걸린다.
    //
    // 세 코드를 나란히 놓습니다.
    //
    //   [의도]  where(A.or(B), C)
    //           where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?
    //           → (VIP or GOLD) and 서울
    //           → 서울 VIP 2 + 서울 GOLD 4 = 6건  ✅
    //
    //   [실수①] where(C.and(A).or(B))
    //           where c1_0.city = ? and c1_0.grade = ? or c1_0.grade = ?
    //           → (서울 and VIP) or GOLD
    //           → 서울 VIP 2 + 전국 GOLD 9 = 11건  ❌
    //
    //   [실수②] where(A.or(B.and(C)))
    //           where c1_0.grade = ? or c1_0.grade = ? and c1_0.city = ?
    //           → VIP or (GOLD and 서울)
    //           → 전국 VIP 4 + 서울 GOLD 4 = 8건  ❌
    //
    // 6 / 11 / 8. 셋 다 컴파일 성공, 실행 성공, 예외 없음, SQL 문법 정상입니다.
    //
    // ── 함정의 정체 ──
    //
    //   자바 연산자   :  a && b || c   →  (a && b) || c    ← && 가 우선
    //   메서드 체인   :  a.and(b).or(c) →  (a.and(b)).or(c) ← 그냥 왼쪽부터
    //
    // 이 두 줄은 우연히 같은 결과입니다. 그래서 더 헷갈립니다.
    // 진짜 문제는 사람이 자연어 순서대로 쓴다는 것입니다.
    // "서울에 살고, VIP 또는 GOLD" 라고 말한 순서 그대로
    //   city.eq("서울").and(grade.eq(VIP)).or(grade.eq(GOLD))
    // 라고 쓰면, 말의 의미와 코드의 의미가 갈라집니다.
    // 눈은 "또는" 이 VIP/GOLD 를 묶는다고 읽는데, 코드는 앞의 두 개를 먼저 묶습니다.
    //
    // ── 왜 varargs 는 안전한가 ──
    //
    // where(a, b, c) 의 콤마는 항상 and 이고, 각 인자는 하나의 완결된 표현식입니다.
    // QueryDSL 이 and 로 묶으면서 필요한 괄호를 씌워 줍니다.
    // 반대로 말하면 ** or 는 varargs 로 표현할 수 없습니다. **
    // 이 비대칭이 함정의 구조적 원인이고, 동시에 처방의 근거입니다.
    // or 그룹을 "하나의 인자" 로 만들면 됩니다.
    //
    // ── 처방 세 가지 ──
    //
    //  1) or 그룹을 이름 있는 메서드로 추출 (가장 권장)
    //       private BooleanExpression isVipOrGold() {
    //           return customer.grade.eq(VIP).or(customer.grade.eq(GOLD));
    //       }
    //       where(isVipOrGold(), customer.city.eq("서울"))
    //     or 가 메서드 안에 갇혀 밖에서 순서를 헷갈릴 여지가 없고,
    //     이름이 비즈니스 의미를 드러냅니다.
    //
    //  2) Expressions.allOf / anyOf
    //       Expressions.allOf(
    //           Expressions.anyOf(A, B),
    //           C
    //       )
    //     괄호 구조가 코드 들여쓰기와 1:1 로 대응합니다.
    //     or 그룹 자체가 동적으로 결정될 때 특히 좋습니다 (null 인자는 무시됩니다).
    //
    //  3) 같은 컬럼의 or 는 in 으로 교체
    //       where(customer.grade.in(VIP, GOLD), customer.city.eq("서울"))
    //     or 가 아예 사라지므로 괄호 문제가 발생할 수 없습니다.
    //     SQL 도 짧고 옵티마이저도 in 을 더 잘 다룹니다.
    //     ** 같은 컬럼의 or 를 보면 먼저 in 으로 바꿀 수 있는지 확인하십시오. **
    //
    // ── 마지막 경고 ──
    //
    // 개발 데이터가 서울 고객만으로 채워져 있으면 세 코드가 전부 같은 결과를 냅니다.
    // 테스트도 통과합니다. 운영 데이터에서만 벌어집니다.
    // 그리고 "왜 부산 고객이 서울 필터에 나오죠?" 라는 문의로 발견됩니다.
    // 그때쯤이면 이미 여러 화면이 그 쿼리를 복사해 쓰고 있습니다.
    // =================================================================
    private BooleanExpression isVipOrGold() {
        return customer.grade.eq(Grade.VIP).or(customer.grade.eq(Grade.GOLD));
    }

    @Test
    @DisplayName("정답 5 — or 함정과 처방")
    void 정답5() {
        System.out.println("=== (a) 올바른 varargs 방식 ===");
        List<Customer> correct = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.eq(Grade.VIP).or(customer.grade.eq(Grade.GOLD)),
                        customer.city.eq("서울")
                )
                .fetch();

        System.out.println("=== (b) .and().or() 체이닝 방식 ===");
        List<Customer> wrong = queryFactory.selectFrom(customer)
                .where(
                        customer.city.eq("서울")
                                .and(customer.grade.eq(Grade.VIP))
                                .or(customer.grade.eq(Grade.GOLD))
                )
                .fetch();

        correct.forEach(c -> System.out.println("[a] " + c.getName() + " " + c.getCity()));
        wrong.forEach(c -> System.out.println("[b] " + c.getName() + " " + c.getCity()));

        assertThat(correct).hasSize(6);
        assertThat(wrong).hasSize(11);

        // (b) 에는 서울이 아닌 고객이 섞여 있습니다.
        assertThat(wrong).extracting(Customer::getCity).contains("부산", "인천", "광주");
        assertThat(correct).extracting(Customer::getCity).containsOnly("서울");

        // ----- 처방 세 가지 — 전부 6건으로 수렴 -----
        List<Customer> fix1 = queryFactory.selectFrom(customer)
                .where(isVipOrGold(), customer.city.eq("서울"))
                .fetch();

        List<Customer> fix2 = queryFactory.selectFrom(customer)
                .where(Expressions.allOf(
                        Expressions.anyOf(
                                customer.grade.eq(Grade.VIP),
                                customer.grade.eq(Grade.GOLD)
                        ),
                        customer.city.eq("서울")
                ))
                .fetch();

        List<Customer> fix3 = queryFactory.selectFrom(customer)
                .where(
                        customer.grade.in(Grade.VIP, Grade.GOLD),
                        customer.city.eq("서울")
                )
                .fetch();

        assertThat(fix1).hasSize(6);
        assertThat(fix2).hasSize(6);
        assertThat(fix3).hasSize(6);
    }

    // =================================================================
    // 정답 6 — NOT IN 과 NULL
    // =================================================================
    //
    // 확인 1) (a) 결과 건수 = 25건
    // 확인 2) 왜 28 이 아닌가 = phone 이 NULL 인 3명이 통째로 누락되기 때문
    // 확인 3) 덜 나오는 방향(누락). 화면에 티가 안 나므로 더 위험함
    //
    // 생성 SQL
    //   where c1_0.phone not in (?, ?)
    //   바인딩: [1] '010-1111-2222'  [2] '010-3333-4444'
    //
    // 고객 30명, 해당 번호를 쓰는 사람 2명 → 상식적으로는 28명입니다.
    // 그런데 25건입니다. phone 이 NULL 인 3명이 빠졌습니다.
    //
    // ── 왜 그런가 : 네 단계 ──
    //
    //   1) NOT IN 은 이렇게 전개됩니다.
    //        phone NOT IN (a, b)  ≡  phone != a AND phone != b
    //
    //   2) phone 이 NULL 이면 각 비교의 결과는 참도 거짓도 아닌 UNKNOWN 입니다.
    //        NULL != '010-1111-2222'  →  UNKNOWN
    //      NULL 은 "값이 없음" 이 아니라 "알 수 없음" 이고,
    //      알 수 없는 것과 무엇을 비교해도 알 수 없습니다.
    //
    //   3) UNKNOWN AND UNKNOWN = UNKNOWN
    //
    //   4) WHERE 절은 ** 참인 행만 ** 통과시킵니다.
    //      UNKNOWN 은 참이 아니므로 그 행은 제외됩니다.
    //
    // 이것이 SQL 의 3값 논리(TRUE / FALSE / UNKNOWN)입니다.
    // MySQL8 코스 Step 05 — 연산자와 조건, 부록 A — NULL 완전 정복,
    // Step 08 — 서브쿼리에서 다룬 그대로이고,
    // ** QueryDSL 로 써도 하나도 달라지지 않습니다. **
    // QueryDSL 은 SQL 위의 얇은 층입니다. 타입 안전성과 조립 가능성을 얹어 줄 뿐,
    // SQL 의 의미론은 그대로 통과합니다.
    //
    // ── 이 버그가 특히 위험한 이유 ──
    //
    // 방향이 ** 누락 ** 입니다. 있어야 할 데이터가 안 나오는 쪽이라
    // 화면에서 즉시 티가 나지 않습니다.
    // "제외 목록을 걸었더니 결과가 줄었다" 는 것은 지극히 자연스러워 보입니다.
    // 30명 중 25명만 나온다는 것을 알아채려면 누군가 세어 봐야 합니다.
    //
    // 그리고 이 버그는 ** 컬럼이 NULL 을 허용하는 한 언제든 ** 생깁니다.
    // 지금은 멀쩡한 코드가 "전화번호를 선택 입력으로 바꿉시다" 라는
    // 기획 변경 한 줄로 조용히 틀리기 시작합니다.
    //
    // ── 처방 ──
    //
    //  1) or isNull() 로 명시적으로 살린다  ← 정답
    //       customer.phone.notIn(phones).or(customer.phone.isNull())
    //       → where c1_0.phone not in (?, ?) or c1_0.phone is null
    //       or 를 썼으니, 여기에 조건이 더 붙는다면 이 표현식을
    //       반드시 메서드로 뽑아 varargs 자리에 넣어야 합니다 (정답 5 참고).
    //       아래 phoneNotIn 이 이미 그렇게 돼 있습니다.
    //
    //  2) coalesce 로 NULL 을 값으로 바꾼다
    //       coalesce(c1_0.phone, '') not in (?, ?)
    //       결과는 맞지만 컬럼을 함수로 감쌌으므로 인덱스를 못 탑니다.
    //       "컬럼을 가공하지 말고 리터럴을 가공하라" 는 규칙 위반입니다.
    //
    //  3) 애초에 NOT NULL 로 설계한다
    //       가장 근본적입니다. NULL 을 허용할 이유가 정말 있는지 되묻고,
    //       없으면 NOT NULL DEFAULT '' 로 바꾸면 문제 전체가 사라집니다.
    //
    // ── 예고 ──
    //
    // 서브쿼리를 NOT IN 에 넣으면 이 함정이 훨씬 잘 숨습니다.
    // 서브쿼리 결과에 NULL 이 ** 한 행이라도 ** 섞이면 전체 결과가 0건이 됩니다.
    // Step 07 에서 notIn(subquery) 대신 notExists(subquery) 를 쓰라고 하는 이유입니다.
    // =================================================================
    private BooleanExpression phoneNotIn(String... phones) {
        return customer.phone.notIn(phones).or(customer.phone.isNull());
    }

    @Test
    @DisplayName("정답 6 — NOT IN 과 NULL")
    void 정답6() {
        System.out.println("=== (a) 그냥 notIn ===");
        List<Customer> naive = queryFactory.selectFrom(customer)
                .where(customer.phone.notIn("010-1111-2222", "010-3333-4444"))
                .fetch();

        System.out.println("=== (b) 보정한 버전 ===");
        List<Customer> fixed = queryFactory.selectFrom(customer)
                .where(phoneNotIn("010-1111-2222", "010-3333-4444"))
                .fetch();

        System.out.println("naive = " + naive.size());   // 25
        System.out.println("fixed = " + fixed.size());   // 28

        assertThat(naive).hasSize(25);
        assertThat(fixed).hasSize(28);

        // 3값 논리 확인: = NULL 과 IS NULL 은 완전히 다릅니다.
        assertThat(queryFactory.selectFrom(customer)
                .where(customer.phone.isNull()).fetch()).hasSize(3);
        assertThat(queryFactory.selectFrom(customer)
                .where(customer.phone.isNotNull()).fetch()).hasSize(27);
        // 3 + 27 = 30. isNull/isNotNull 은 전체를 정확히 둘로 나눕니다.
        // 반면 = NULL 과 != NULL 은 둘 다 0건이라 합이 0 입니다.
    }

    // =================================================================
    // 보너스 — isEmpty() 가 만드는 not exists 서브쿼리
    // =================================================================
    //
    // 연습문제에는 없지만 Step 07 로 이어지는 다리입니다.
    //
    //   customer.orders.isEmpty()
    //     → where not exists (select 1 from orders o1_0
    //                         where c1_0.customer_id = o1_0.customer_id)
    //
    //   customer.orders.isNotEmpty()
    //     → where exists (select 1 from orders o1_0
    //                     where c1_0.customer_id = o1_0.customer_id)
    //
    // 자바 한 줄이 상관 서브쿼리(correlated subquery)를 만들어 냈습니다.
    // 편하지만, 이 편함에는 대가가 있습니다.
    //
    //  - 어떤 서브쿼리가 나갈지 코드만 봐서는 알 수 없습니다.
    //  - 조건을 추가할 수 없습니다. "취소되지 않은 주문이 있는 고객" 은
    //    isNotEmpty() 로 표현할 수 없습니다.
    //
    // 그래서 Step 07 에서 JPAExpressions 로 서브쿼리를 직접 씁니다.
    //
    //   .where(JPAExpressions.selectOne()
    //           .from(order)
    //           .where(order.customer.eq(customer),
    //                  order.status.ne(OrderStatus.CANCELLED))
    //           .exists())
    //
    // isEmpty()/isNotEmpty() 는 "조건 없는 존재 여부" 에만 쓰십시오.
    // 조건이 하나라도 붙으면 서브쿼리를 직접 쓰는 편이 명확합니다.
    // =================================================================
    @Test
    @DisplayName("보너스 — isEmpty() 는 not exists 서브쿼리")
    void 보너스_컬렉션_조건() {
        List<Customer> neverOrdered = queryFactory.selectFrom(customer)
                .where(customer.orders.isEmpty()).fetch();

        List<Customer> hasOrdered = queryFactory.selectFrom(customer)
                .where(customer.orders.isNotEmpty()).fetch();

        assertThat(neverOrdered).isEmpty();     // 0명
        assertThat(hasOrdered).hasSize(30);     // 30명 모두 주문 이력 있음
    }
}
