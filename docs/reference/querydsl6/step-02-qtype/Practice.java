package com.example.shop.step02;

import com.example.shop.entity.Customer;
import com.example.shop.entity.Grade;
import com.example.shop.entity.QCustomer;
import com.example.shop.entity.QEmployee;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QProduct.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 02 — Q타입의 정체 : 본문 예제 전체.
 *
 * 실행 전 application.yml 에 아래가 켜져 있어야 합니다.
 *   logging.level.org.hibernate.SQL: debug
 *   logging.level.org.hibernate.orm.jdbc.bind: trace
 *
 * 이 스텝은 다른 스텝과 성격이 다릅니다.
 * 쿼리를 작성하는 것보다 "빌드 산출물을 확인하고 고장을 재현하는" 비중이 큽니다.
 * 파일 시스템을 뒤지는 테스트가 몇 개 섞여 있으니, 반드시 프로젝트 루트에서
 *   ./gradlew test --tests '*step02.Practice'
 * 로 실행하십시오. IDE 에서 직접 실행하면 작업 디렉터리가 달라 경로를 못 찾을 수 있습니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [2-1] APT 는 언제 도는가
    // =================================================================

    /**
     * APT 는 별도 태스크가 아니라 compileJava 안에서 돕니다.
     * 이 테스트는 그 사실을 코드로 확인할 수는 없고(빌드 시점의 일이므로),
     * 대신 "이미 생성이 끝나 있다"는 결과만 확인합니다.
     *
     * 생성 과정 자체를 보려면 터미널에서:
     *   ./gradlew clean compileJava --info | grep -iE "annotation|querydsl|generated"
     *
     * 출력의 "Processors:" 줄에 com.querydsl.apt.jpa.JPAAnnotationProcessor 가
     * 있어야 합니다. 없으면 Q타입은 절대 생성되지 않습니다.
     */
    @Test
    @DisplayName("[2-1] Q타입 클래스가 클래스패스에 올라와 있다")
    void qtype이_클래스패스에_있다() throws ClassNotFoundException {
        // 문자열로 클래스를 찾습니다. import 로 찾으면 컴파일 타임에 확인되어 버리므로
        // "런타임에 실제로 존재하는가" 를 보기 위해 일부러 리플렉션을 씁니다.
        Class<?> qCustomer = Class.forName("com.example.shop.entity.QCustomer");

        System.out.println("=== [2-1] Q타입 클래스 확인 ===");
        System.out.println("클래스     : " + qCustomer.getName());
        System.out.println("상위 클래스 : " + qCustomer.getSuperclass().getName());

        assertThat(qCustomer.getSuperclass().getSimpleName()).isEqualTo("EntityPathBase");
    }

    // =================================================================
    // [2-2] 생성된 QCustomer.java 뜯어보기
    // =================================================================

    /**
     * 자바 필드 타입이 어떤 Path 로 치환됐는지 확인합니다.
     * 이 치환이 QueryDSL 타입 안전성의 실체입니다.
     */
    @Test
    @DisplayName("[2-2] 필드 타입별로 다른 Path 가 생성된다")
    void path_타입을_확인한다() {
        System.out.println("=== [2-2] QCustomer 의 Path 타입 ===");
        System.out.println("id        (Long)          -> " + customer.id.getClass().getSimpleName());
        System.out.println("name      (String)        -> " + customer.name.getClass().getSimpleName());
        System.out.println("points    (Integer)       -> " + customer.points.getClass().getSimpleName());
        System.out.println("grade     (Grade enum)    -> " + customer.grade.getClass().getSimpleName());
        System.out.println("birthDate (LocalDate)     -> " + customer.birthDate.getClass().getSimpleName());
        System.out.println("createdAt (LocalDateTime) -> " + customer.createdAt.getClass().getSimpleName());
        System.out.println("orders    (List<Order>)   -> " + customer.orders.getClass().getSimpleName());

        System.out.println();
        System.out.println("=== QProduct 의 Path 타입 ===");
        System.out.println("price     (BigDecimal)    -> " + product.price.getClass().getSimpleName());
        System.out.println("status    (enum)          -> " + product.status.getClass().getSimpleName());
        System.out.println("category  (@ManyToOne)    -> " + product.category.getClass().getSimpleName());
        System.out.println("reviews   (@OneToMany)    -> " + product.reviews.getClass().getSimpleName());

        // @ManyToOne 은 대상 엔티티의 Q타입이 됩니다. 그래서 점으로 계속 이어집니다.
        assertThat(product.category.name.getClass().getSimpleName()).isEqualTo("StringPath");
    }

    /**
     * 타입 안전성 — 잘못된 조건 메서드는 컴파일이 안 됩니다.
     *
     * 아래 두 줄의 주석을 풀면 컴파일이 실패합니다. 그것이 정상 동작입니다.
     * 에러 메시지를 확인한 뒤 다시 주석으로 되돌리십시오.
     *
     * 주석을 풀었을 때:
     *
     *   error: incompatible types: int cannot be converted to String
     *           .where(customer.name.goe(5))
     *                                   ^
     *
     *   error: cannot find symbol
     *     symbol:   method contains(String)
     *     location: variable points of type NumberPath<Integer>
     *
     * 문자열 JPQL 이었다면 두 경우 모두 컴파일을 통과하고,
     * MySQL 이 암묵적 형변환으로 엉뚱한 답을 냈을 것입니다.
     */
    @Test
    @DisplayName("[2-2] 잘못된 조건은 컴파일 에러 (주석 처리됨)")
    void 잘못된_조건은_컴파일이_안된다() {
        // queryFactory.selectFrom(customer).where(customer.name.goe(5)).fetch();
        // queryFactory.selectFrom(customer).where(customer.points.contains("100")).fetch();

        System.out.println("[2-2] 위 두 줄의 주석을 풀어 컴파일 에러를 직접 확인하십시오.");
    }

    // =================================================================
    // [2-3] build/generated/ 와 .gitignore
    // =================================================================

    /**
     * 생성된 Q타입 파일을 셉니다. 엔티티 8개이므로 8개가 나와야 합니다.
     *
     * 주의: ./gradlew test 로 실행할 때만 의미가 있습니다.
     *      IDE 에서 직접 실행하면 작업 디렉터리가 달라 경로를 못 찾을 수 있습니다.
     */
    @Test
    @DisplayName("[2-3] 생성된 Q타입은 엔티티 개수와 같다")
    void 생성된_Q타입_파일을_센다() throws IOException {
        Path generated = Paths.get("build/generated/sources/annotationProcessor/java/main");

        if (!Files.exists(generated)) {
            System.out.println("[2-3] build/generated 를 찾을 수 없습니다.");
            System.out.println("      프로젝트 루트에서 ./gradlew test 로 실행하십시오.");
            return;
        }

        try (Stream<Path> paths = Files.walk(generated)) {
            List<String> qTypes = paths
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("Q") && n.endsWith(".java"))
                    .sorted()
                    .toList();

            System.out.println("=== [2-3] 생성된 Q타입 " + qTypes.size() + "개 ===");
            qTypes.forEach(n -> System.out.println("  " + n));

            // 엔티티 8개: Category, Customer, Employee, Order, OrderItem, Payment, Product, Review
            assertThat(qTypes).contains(
                    "QCategory.java", "QCustomer.java", "QEmployee.java", "QOrder.java",
                    "QOrderItem.java", "QPayment.java", "QProduct.java", "QReview.java"
            );
        }
    }

    // =================================================================
    // [2-6] new QCustomer("c") — 별칭
    // =================================================================

    /**
     * 이 스텝에서 가장 중요한 테스트입니다.
     *
     * 별칭을 바꾸면 JPQL 은 달라지지만 SQL 은 같습니다.
     * Hibernate 가 SQL 을 만들 때 자기 규칙(c1_0)으로 별칭을 다시 붙이기 때문입니다.
     *
     * 콘솔에서 확인할 것:
     *   - 아래 println 이 찍은 JPQL 두 줄 : 다릅니다 (customer vs c)
     *   - 그 사이에 찍히는 hibernate.SQL 두 줄 : 같습니다 (둘 다 c1_0)
     */
    @Test
    @DisplayName("[2-6] 별칭이 달라도 생성 SQL 은 같다")
    void 별칭이_달라도_SQL은_같다() {
        QCustomer c = new QCustomer("c");

        JPAQuery<Customer> q1 = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP));

        JPAQuery<Customer> q2 = queryFactory
                .selectFrom(c)
                .where(c.grade.eq(Grade.VIP));

        System.out.println("=== [2-6] JPQL 비교 ===");
        System.out.println("기본 별칭 : " + q1);
        System.out.println("커스텀    : " + q2);
        System.out.println("=== 아래 두 개의 hibernate.SQL 은 같습니다 ===");

        List<Customer> r1 = q1.fetch();
        List<Customer> r2 = q2.fetch();

        // JPQL 은 다릅니다
        assertThat(q1.toString()).isNotEqualTo(q2.toString());
        // 결과는 같습니다
        assertThat(r1).hasSize(4);
        assertThat(r2).hasSize(4);
    }

    /**
     * 셀프 조인 — 별칭이 반드시 필요한 대표 사례.
     *
     * employees 테이블이 SQL 에 두 번 등장합니다 (e1_0, m1_0).
     * 별칭 없이 같은 인스턴스를 두 자리에 쓰면 Duplicate alias 예외가 납니다.
     */
    @Test
    @DisplayName("[2-6] 셀프 조인에는 별칭이 필수다")
    void 셀프조인_사원과_관리자() {
        QEmployee e = QEmployee.employee;
        QEmployee m = new QEmployee("m");        // 관리자 쪽 별칭

        List<Tuple> result = queryFactory
                .select(e.name, m.name)
                .from(e)
                .leftJoin(e.manager, m)
                .orderBy(e.id.asc())
                .fetch();

        System.out.println("=== [2-6] 사원-관리자 " + result.size() + "건 ===");
        System.out.printf("%-10s | %-10s%n", "사원", "관리자");
        System.out.println("-----------|-----------");
        for (Tuple t : result) {
            String managerName = t.get(m.name);
            System.out.printf("%-10s | %-10s%n", t.get(e.name),
                    managerName == null ? "(없음)" : managerName);
        }

        assertThat(result).hasSize(18);

        // 최상위(김대표)는 관리자가 없으므로 null 입니다.
        // leftJoin 이라서 남았습니다. innerJoin 이었다면 17건이 됩니다.
        long nullManagers = result.stream().filter(t -> t.get(m.name) == null).count();
        assertThat(nullManagers).isEqualTo(1);
    }

    /**
     * 서브쿼리 — 바깥과 서브쿼리의 별칭을 반드시 분리해야 합니다.
     *
     * 생성 SQL 에서 바깥은 c1_0, 서브쿼리는 c2_0 입니다.
     * (Step 07 에서 본격적으로 다룹니다. 여기서는 별칭 분리의 필요성만 봅니다.)
     */
    @Test
    @DisplayName("[2-6] 서브쿼리는 별칭을 분리한다")
    void 서브쿼리_별칭_분리() {
        QCustomer sub = new QCustomer("sub");

        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.points.gt(
                        JPAExpressions.select(sub.points.avg()).from(sub)
                ))
                .fetch();

        System.out.println("=== [2-6] 평균 포인트 초과 고객 " + result.size() + "명 ===");
        result.forEach(x -> System.out.printf("  %-8s %,d점%n", x.getName(), x.getPoints()));

        assertThat(result).hasSize(13);
    }

    // =================================================================
    // [2-8] 엔티티를 고쳤는데 Q타입에 안 보인다
    // =================================================================

    /**
     * build/ 밖에 Q타입이 있으면 clean 으로도 지워지지 않고,
     * 엔티티와 어긋난 채로 살아남습니다. 0개가 나와야 정상입니다.
     *
     * 하나라도 나오면:
     *   rm -rf src/main/generated
     *   ./gradlew clean compileJava
     * 하고 .gitignore 에 추가하십시오.
     */
    @Test
    @DisplayName("[2-8] build/ 밖에 옛 Q타입이 없어야 한다")
    void 옛_Q타입이_있는지_확인한다() throws IOException {
        Path root = Paths.get(".");

        try (Stream<Path> paths = Files.walk(root, 12)) {
            List<Path> strays = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/build/"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("Q") && n.endsWith(".java");
                    })
                    .toList();

            System.out.println("=== [2-8] build/ 밖의 Q타입 " + strays.size() + "개 ===");
            if (strays.isEmpty()) {
                System.out.println("  (없음 — 정상입니다)");
            } else {
                strays.forEach(p -> System.out.println("  " + p + "   <-- 지우십시오"));
            }

            assertThat(strays)
                    .as("build/ 밖의 Q타입은 엔티티와 어긋난 채 살아남습니다. 2-3 의 함정 블록을 보십시오.")
                    .isEmpty();
        }
    }

    // =================================================================
    // [2-9] @QueryProjection 으로 생성되는 Q타입
    // =================================================================

    /**
     * @QueryProjection 이 붙은 DTO 도 :jpa 프로세서가 함께 처리합니다.
     * Step 05 에서 본격적으로 다루므로 여기서는 존재 확인만 합니다.
     *
     * DTO 를 아직 만들지 않았다면 이 테스트는 건너뜁니다.
     */
    @Test
    @DisplayName("[2-9] @QueryProjection DTO 의 Q타입 (Step 05 예고)")
    void queryProjection_Q타입() {
        try {
            Class<?> qDto = Class.forName("com.example.shop.dto.QCustomerDto");
            System.out.println("=== [2-9] " + qDto.getName() + " 생성됨 ===");
            System.out.println("상위 클래스: " + qDto.getSuperclass().getSimpleName());
        } catch (ClassNotFoundException ex) {
            System.out.println("[2-9] QCustomerDto 가 아직 없습니다. Step 05 에서 만듭니다.");
        }
    }
}
