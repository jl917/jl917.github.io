package com.example.shop.step02;

import com.example.shop.entity.Customer;
import com.example.shop.entity.QCustomer;
import com.example.shop.entity.QEmployee;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 02 — 연습문제 정답과 해설.
 *
 * 문제를 풀어 본 "뒤에" 여십시오.
 * 이 스텝은 관찰형 문제가 많은 만큼, 정답 코드보다 주석이 훨씬 깁니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 정답 1. QProduct 의 Path 타입
    // =================================================================
    /*
     * 생성된 QProduct.java 의 필드는 다음과 같습니다.
     *
     *   자바 필드 타입                생성된 Path
     *   ---------------------------  --------------------------------------
     *   Long id                      NumberPath<Long>
     *   String name                  StringPath
     *   BigDecimal price             NumberPath<BigDecimal>
     *   BigDecimal cost              NumberPath<BigDecimal>
     *   Integer stock                NumberPath<Integer>
     *   ProductStatus status         EnumPath<ProductStatus>
     *   LocalDateTime createdAt      DateTimePath<LocalDateTime>
     *   Category category            QCategory              ← 다른 Q타입!
     *   List<Review> reviews         ListPath<Review, QReview>
     *
     * Q1-a. category 와 reviews 는 왜 서로 다른 것으로 매핑됐습니까?
     *
     *   답: 다중성(cardinality)이 다르기 때문입니다.
     *
     *   @ManyToOne 은 "하나"를 가리킵니다. 그래서 대상 엔티티의 Q타입
     *   (QCategory) 자체가 필드가 됩니다. 그 안에 다시 name, sortOrder 같은
     *   Path 가 들어 있으므로 점으로 계속 이어집니다.
     *
     *   @OneToMany 는 "여럿"을 가리킵니다. 여럿을 하나의 Path 로 표현할 수
     *   없으므로 ListPath 라는 컬렉션 전용 타입이 됩니다. ListPath 에는
     *   name 같은 필드가 없고 대신 isEmpty(), size(), any(), contains()
     *   처럼 "컬렉션에 대해 물을 수 있는 것"만 있습니다.
     *
     *   이 차이가 중요한 이유:
     *     product.category.name        → 됩니다 (하나이므로 name 이 유일)
     *     product.reviews.rating       → 안 됩니다 (여럿 중 누구의 rating?)
     *     product.reviews.any().rating → 됩니다 (아무거나 하나라도)
     *
     * Q1-b. product.category.name 처럼 점으로 이어서 쓸 수 있는 이유는?
     *
     *   답: category 필드의 타입이 QCategory 이고, QCategory 에 StringPath name
     *   이 있기 때문입니다. 평범한 자바 필드 접근일 뿐 특별한 문법이 아닙니다.
     *
     *   다만 이렇게 이어 쓰면 JPQL 에서 "묵시적 조인" 이 생깁니다.
     *   그리고 묵시적 조인은 항상 inner join 입니다.
     *   leftJoin 이 필요한 자리에 이걸 쓰면 결과가 조용히 줄어듭니다.
     *   Step 06 에서 자세히 다룹니다.
     */
    @Test
    @DisplayName("정답 1 — Path 타입을 코드로 확인")
    void 정답1() {
        assertThat(product.id.getClass().getSimpleName()).isEqualTo("NumberPath");
        assertThat(product.name.getClass().getSimpleName()).isEqualTo("StringPath");
        assertThat(product.price.getClass().getSimpleName()).isEqualTo("NumberPath");
        assertThat(product.status.getClass().getSimpleName()).isEqualTo("EnumPath");
        assertThat(product.createdAt.getClass().getSimpleName()).isEqualTo("DateTimePath");
        assertThat(product.category.getClass().getSimpleName()).isEqualTo("QCategory");
        assertThat(product.reviews.getClass().getSimpleName()).isEqualTo("ListPath");

        // @ManyToOne 은 점으로 계속 이어집니다
        assertThat(product.category.name.getClass().getSimpleName()).isEqualTo("StringPath");
    }

    // =================================================================
    // 정답 2. 컴파일 에러 관찰
    // =================================================================
    /*
     * customer.name.goe(5) 의 에러 메시지:
     *
     *   error: incompatible types: int cannot be converted to String
     *           queryFactory.selectFrom(customer).where(customer.name.goe(5)).fetch();
     *                                                                    ^
     *
     *   customer.name 은 StringPath 입니다. StringPath 는
     *   ComparableExpression<String> 을 상속하므로 goe() 가 존재하기는 합니다.
     *   다만 시그니처가 goe(String) 이라서 int 를 넘길 수 없습니다.
     *
     * product.stock.contains("10") 의 에러 메시지:
     *
     *   error: cannot find symbol
     *     symbol:   method contains(String)
     *     location: variable stock of type NumberPath<Integer>
     *
     *   이쪽은 메서드 자체가 없습니다. contains() 는 StringPath 와
     *   CollectionExpression 에만 있습니다. 숫자에 "포함" 이라는 개념이
     *   없으니 당연합니다.
     *
     * Q2-a. 같은 실수를 문자열 JPQL 로 했다면 언제 발견됐을까요?
     *
     *   답: 그 코드가 실행되는 시점입니다. 그리고 더 나쁜 경우에는
     *       "영원히 발견되지 않습니다."
     *
     *   em.createQuery("select c from Customer c where c.name >= 5")
     *
     *   이 JPQL 은 컴파일을 통과합니다 — 그냥 문자열이니까요.
     *   실행하면 Hibernate 가 파싱하면서 타입 불일치를 잡아낼 수도 있고,
     *   못 잡고 SQL 로 내려보낼 수도 있습니다.
     *
     *   SQL 로 내려가면 MySQL 이 암묵적 형변환을 합니다.
     *   MySQL 에서 문자열과 숫자를 비교하면 "문자열을 숫자로" 바꿉니다.
     *   '류하나' 를 숫자로 바꾸면 0 이 됩니다. '5' 로 시작하는 이름이
     *   없다면 결과는 전부 0 >= 5 = false, 즉 0건입니다.
     *
     *   에러도 없고 경고도 없고 결과만 비어 있습니다.
     *   "왜 검색이 안 되지?" 를 몇 시간 헤매게 되는 전형적인 경로입니다.
     *
     *   QueryDSL 은 이것을 컴파일러에게 맡깁니다. 이것이 이 라이브러리를
     *   쓰는 가장 큰 이유입니다.
     */
    @Test
    @DisplayName("정답 2 — 올바른 타입으로 고친 형태")
    void 정답2() {
        // 이름은 문자열로 비교합니다
        List<Customer> byName = queryFactory
                .selectFrom(customer)
                .where(customer.name.goe("ㅇ"))
                .fetch();

        // 재고는 숫자로 비교합니다
        Long lowStock = queryFactory
                .select(product.count())
                .from(product)
                .where(product.stock.loe(10))
                .fetchOne();

        System.out.println("=== 정답 2 ===");
        System.out.println("이름이 'ㅇ' 이상인 고객: " + byName.size() + "명");
        System.out.println("재고 10 이하 상품: " + lowStock + "개");

        assertThat(byName).isNotNull();
        assertThat(lowStock).isNotNull();
    }

    // =================================================================
    // 정답 3. 셀프 조인
    // =================================================================
    @Test
    @DisplayName("정답 3 — 셀프 조인 18건")
    void 정답3() {
        QEmployee e = QEmployee.employee;
        QEmployee m = new QEmployee("m");

        List<Tuple> result = queryFactory
                .select(e.name, m.name)
                .from(e)
                .leftJoin(e.manager, m)
                .orderBy(e.id.asc())
                .fetch();

        System.out.println("=== 정답 3 — 사원-관리자 " + result.size() + "건 ===");
        result.forEach(t -> {
            String mgr = t.get(m.name);
            System.out.printf("  %-10s <- %s%n", t.get(e.name), mgr == null ? "(최상위)" : mgr);
        });

        assertThat(result).hasSize(18);

        /*
         * Q3-a. SQL 에 employees 테이블이 몇 번 등장합니까?
         *
         *   답: 2번입니다.
         *
         *     select e1_0.name, m1_0.name
         *     from employees e1_0
         *     left join employees m1_0 on m1_0.employee_id=e1_0.manager_id
         *     order by e1_0.employee_id asc
         *
         * Q3-b. 그 두 별칭은 각각 무엇입니까?
         *
         *   답: e1_0 (사원 쪽), m1_0 (관리자 쪽) 입니다.
         *
         *   주의할 점 — 우리가 자바에서 준 별칭은 "employee" 와 "m" 이었습니다.
         *   그런데 SQL 에는 e1_0 과 m1_0 이 나옵니다. Hibernate 가 SQL 을
         *   만들 때 자기 규칙으로 다시 붙이기 때문입니다.
         *
         *   즉 우리가 별칭을 준 이유는 "SQL 의 별칭을 정하기 위해서" 가 아니라
         *   "JPQL 단계에서 두 Employee 를 구분하기 위해서" 입니다.
         *   정답 4 에서 이 이야기가 완성됩니다.
         *
         * Q3-c. leftJoin 을 innerJoin 으로 바꾸면 몇 건이 됩니까? 왜?
         *
         *   답: 17건입니다.
         *
         *   김대표는 manager_id 가 NULL 입니다. inner join 은 양쪽에 짝이
         *   있는 행만 남기므로, 관리자가 없는 김대표가 탈락합니다.
         *
         *   이것이 조인 선택이 "취향" 이 아니라 "요구사항" 인 이유입니다.
         *   "모든 사원과 그 관리자" 를 원했다면 leftJoin 이 맞고,
         *   innerJoin 을 쓰면 대표이사가 조직도에서 조용히 사라집니다.
         *   MySQL8 코스 Step 07 의 안티조인과 같은 이야기입니다.
         */
    }

    // =================================================================
    // 정답 4. 별칭이 달라도 SQL 은 같다  ← 이 스텝에서 가장 중요한 정답
    // =================================================================
    @Test
    @DisplayName("정답 4 — JPQL 은 다르고 SQL 은 같다")
    void 정답4() {
        QCustomer x = new QCustomer("x");

        JPAQuery<Customer> q1 = queryFactory
                .selectFrom(customer)
                .where(customer.city.eq("서울"));

        JPAQuery<Customer> q2 = queryFactory
                .selectFrom(x)
                .where(x.city.eq("서울"));

        System.out.println("=== 정답 4 — JPQL 비교 ===");
        System.out.println("q1: " + q1);
        System.out.println("q2: " + q2);
        System.out.println("=== 아래 두 hibernate.SQL 을 대조하십시오 ===");

        List<Customer> r1 = q1.fetch();
        List<Customer> r2 = q2.fetch();

        assertThat(q1.toString()).isNotEqualTo(q2.toString());
        assertThat(r1).hasSameSizeAs(r2);

        /*
         * Q4-a. 두 JPQL:
         *
         *   q1: select customer from Customer customer where customer.city = ?1
         *   q2: select x from Customer x where x.city = ?1
         *
         *   완전히 다릅니다. 별칭이 그대로 드러납니다.
         *
         * Q4-b. 두 hibernate.SQL 의 from 절:
         *
         *   q1: from customers c1_0
         *   q2: from customers c1_0
         *
         *   글자 하나 다르지 않습니다.
         *
         *   전문을 보면 이렇습니다 (둘 다 동일):
         *
         *     select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
         *            c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
         *     from customers c1_0
         *     where c1_0.city=?
         *
         * Q4-c. SQL 이 같다면, 별칭을 바꾸는 것은 무엇을 위해서입니까?
         *
         *   답: "같은 엔티티를 한 쿼리 안에서 두 번 쓰기 위해서" 입니다.
         *       SQL 을 바꾸려는 목적이 전혀 아닙니다.
         *
         *   이것이 이 스텝의 핵심 결론입니다. 정리하면:
         *
         *   1) QueryDSL 은 JPQL 을 만듭니다. 별칭은 JPQL 의 것입니다.
         *   2) Hibernate 는 그 JPQL 을 받아 SQL 을 만들면서
         *      별칭을 c1_0, c2_0, e1_0, m1_0 ... 으로 새로 붙입니다.
         *   3) 따라서 JPQL 별칭은 SQL 에 전달되지 않습니다.
         *   4) 그럼에도 별칭이 필요한 이유는, JPQL 단계에서 같은 엔티티가
         *      두 번 등장할 때 둘을 구분할 방법이 별칭뿐이기 때문입니다.
         *
         *   별칭을 분리하지 않고 같은 인스턴스를 두 자리에 쓰면:
         *
         *     java.lang.IllegalStateException: Duplicate alias 'employee' in JPQL query
         *
         *   또는 더 나쁘게, 조인이 무시되고 같은 값이 두 번 나옵니다.
         *
         *   별칭이 반드시 필요한 세 자리:
         *     - 셀프 조인 (정답 3)
         *     - 서브쿼리 (Step 07)
         *     - 같은 엔티티를 서로 다른 조건으로 두 번 조인할 때 (Step 06)
         */
    }

    // =================================================================
    // 정답 5. 컴파일하지 않으면 Q타입은 갱신되지 않는다
    // =================================================================
    /*
     * 2) customer.lastLoginAt 자동완성이 됩니까?
     *
     *   답: 안 됩니다.
     *
     *   왜: Q타입은 컴파일 중에 생성됩니다(2-1). 파일을 저장한 것만으로는
     *   컴파일이 일어나지 않았으므로 QCustomer.java 는 여전히 옛날 것입니다.
     *   IDE 가 참조하는 것도 그 옛 파일입니다.
     *
     * 4) ./gradlew compileJava 후에는?
     *
     *   답: 됩니다. APT 가 다시 돌면서 QCustomer.java 를 새로 썼고,
     *   그 안에 DateTimePath<LocalDateTime> lastLoginAt 이 추가됐습니다.
     *
     * Q5-a. "파일 저장" 과 "컴파일" 을 구분하지 않는 습관이 왜 사고를 냅니까?
     *
     *   답: 대부분의 자바 코드는 그 구분이 필요 없기 때문입니다.
     *
     *   평소에는 IDE 가 백그라운드에서 계속 컴파일해 주므로, 저장하면
     *   곧바로 반영되는 것처럼 느껴집니다. 그래서 "저장 = 반영" 이라는
     *   감각이 생깁니다.
     *
     *   그런데 코드 생성이 끼어들면 이 감각이 깨집니다. 생성물은
     *   생성기가 다시 돌아야 갱신되고, 생성기는 빌드 도구가 돌려야
     *   돕니다. IDE 를 Gradle 위임 모드로 두지 않으면(2-5) 이 연결고리가
     *   끊어져서 "저장했는데 왜 안 되지" 상태가 됩니다.
     *
     *   같은 이유로 Lombok, MapStruct, Immutables 를 쓰는 프로젝트에서도
     *   똑같은 혼란이 생깁니다. 처방도 같습니다 — 빌드를 Gradle 에 맡기고,
     *   생성물이 이상하면 일단 컴파일부터 하십시오.
     *
     *   그리고 이 문제의 진짜 위험한 형태는 "필드를 지웠을 때" 입니다.
     *   추가는 컴파일 에러로 드러나지만, 삭제는 옛 Q타입이 살아 있는 한
     *   컴파일을 통과하고 런타임에 터집니다:
     *
     *     org.hibernate.query.SemanticException:
     *       Could not interpret path expression 'customer.lastLoginAt'
     *
     *   2-8 의 함정 블록이 이 이야기입니다.
     */

    // =================================================================
    // 정답 6. :jpa classifier 를 빼면 "빌드 성공인데 Q타입 없음"
    // =================================================================
    /*
     * 1) 정상 상태의 Processors 목록:
     *
     *   Note: Annotation processing is enabled because one or more processors
     *     were found on the class path. Processors:
     *     com.querydsl.apt.jpa.JPAAnnotationProcessor,
     *     lombok.launch.AnnotationProcessorHider$AnnotationProcessor
     *
     * 3) :jpa 를 뺀 상태의 Processors 목록:
     *
     *   Note: Annotation processing is enabled because one or more processors
     *     were found on the class path. Processors:
     *     lombok.launch.AnnotationProcessorHider$AnnotationProcessor
     *
     * Q6-a. 빌드 결과는?
     *   답: BUILD SUCCESSFUL 입니다. 이것이 문제의 핵심입니다.
     *
     * Q6-b. find build/generated -name "Q*.java" | wc -l 의 결과는?
     *   답: 0 입니다.
     *
     * Q6-c. Processors 목록에서 무엇이 사라졌습니까?
     *   답: com.querydsl.apt.jpa.JPAAnnotationProcessor 입니다.
     *
     *   왜 사라지는가: querydsl-apt 아티팩트는 하나지만, 그 안에
     *   프로세서가 여러 개 들어 있고 classifier 로 어느 것을 등록할지
     *   고릅니다.
     *
     *     :jpa  → JPAAnnotationProcessor      (@Entity 를 봅니다)
     *     (없음) → QuerydslAnnotationProcessor (@QueryEntity 를 봅니다)
     *     :jdo  → JDOAnnotationProcessor       (@PersistenceCapable 을 봅니다)
     *
     *   classifier 를 빼면 기본 프로세서가 걸립니다. 그 프로세서는
     *   @QueryEntity 만 찾는데 우리 엔티티에는 @Entity 밖에 없으니
     *   "처리할 것이 하나도 없네" 하고 조용히 끝냅니다.
     *
     *   프로세서 입장에서는 정상 동작입니다. 그래서 아무도 에러를
     *   내지 않습니다.
     *
     * Q6-d. 이 원인이 5가지 중 가장 찾기 어려운 이유는?
     *
     *   답: 유일하게 에러 메시지가 없기 때문입니다.
     *
     *   원인 1 (@Entity 누락/javax) → 특정 Q타입만 없어서 범위가 좁혀집니다
     *   원인 3 (jakarta-api 누락)   → NoClassDefFoundError 로 즉시 터집니다
     *   원인 4 (IDE 자체 빌더)      → 터미널/IDE 비교로 금방 드러납니다
     *   원인 5 (옛 Q타입 잔존)      → find 한 번으로 확인됩니다
     *
     *   원인 2 만 BUILD SUCCESSFUL 로 끝나고, 그 다음에 나오는 에러는
     *   "cannot find symbol: class QCustomer" 라는, 원인과 아무 관계없어
     *   보이는 메시지입니다. 그래서 사람들이 sourceSets 를 고치고,
     *   IDE 캐시를 지우고, 프로젝트를 다시 임포트하면서 몇 시간을 씁니다.
     *
     *   그러므로 Q타입이 없을 때 가장 먼저 할 일은 이것입니다:
     *
     *     ./gradlew compileJava --info | grep -i "Processors:"
     *
     *   이 한 줄이 원인 2 와 3 을 즉시 배제해 줍니다.
     */

    // =================================================================
    // 보너스 — Q타입 문제 진단 체크리스트
    // =================================================================
    /*
     * 실무에서 "QCustomer 를 찾을 수 없다" 를 만났을 때 이 순서로 확인하십시오.
     * 위에서부터 순서대로 하는 것이 중요합니다. 비용이 싼 것부터입니다.
     *
     * ── 0단계: 그냥 컴파일을 안 한 것 아닌가 (30초)
     *
     *     ./gradlew compileJava
     *
     *   이걸로 해결되는 경우가 실제로 가장 많습니다.
     *
     * ── 1단계: 프로세서가 등록돼 있는가 (30초)  ← 원인 2, 3 을 한 번에 배제
     *
     *     ./gradlew clean compileJava --info | grep -i "Processors:"
     *
     *   JPAAnnotationProcessor 가 없다 → build.gradle 의 :jpa classifier 확인
     *   NoClassDefFoundError 가 난다   → jakarta.persistence-api 를
     *                                     annotationProcessor 에 추가
     *
     * ── 2단계: 생성물이 실제로 있는가 (10초)
     *
     *     find build/generated -name "Q*.java" | wc -l
     *
     *   8 이 나오면 생성은 정상입니다 → 4단계(IDE)로 건너뛰십시오.
     *   0 이면 → 3단계.
     *
     * ── 3단계: 엔티티가 엔티티로 보이는가 (1분)  ← 원인 1
     *
     *     grep -rn "javax.persistence" src/main/java/
     *     grep -rLn "@Entity" src/main/java/com/example/shop/entity/
     *
     *   javax 가 하나라도 나오면 jakarta 로 전체 치환하십시오.
     *
     * ── 4단계: build/ 밖에 옛 Q타입이 있는가 (10초)  ← 원인 5
     *
     *     find . -name "Q*.java" -not -path "./build/*" -not -path "./.git/*"
     *
     *   하나라도 나오면 지우고 .gitignore 에 추가하십시오.
     *   (Practice.java 의 옛_Q타입이_있는지_확인한다() 가 이것을 자동화합니다)
     *
     * ── 5단계: IDE 문제인가 (5분)  ← 원인 4
     *
     *   Settings → Build Tools → Gradle
     *     Build and run using: Gradle
     *     Run tests using:     Gradle
     *
     *   그래도 안 되면 File → Invalidate Caches → Invalidate and Restart
     *
     * 5단계까지 왔는데도 안 된다면, 그때는 Q타입 문제가 아니라
     * 다른 문제일 가능성이 높습니다. 에러 메시지를 처음부터 다시 읽으십시오.
     */
}
