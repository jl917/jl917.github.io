package com.example.shop.step11;

import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
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
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;

/**
 * Step 11 — 벌크 연산 : 연습문제 정답과 해설.
 *
 * <p>각 메서드의 주석에 <b>왜 그 답인지</b>와 <b>생성 SQL 이 어떻게 달라지는지</b>가 적혀 있습니다.
 * 답이 같아도 생성 SQL 이 다르면 틀린 것입니다.
 */
@SpringBootTest
@Transactional
class Solution {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // 문제 1. HIDDEN + 재고 0 → SOLD_OUT
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>벌크 UPDATE 의 기본형 그대로입니다. 짚어야 할 점은 세 가지입니다.
     *
     * <p>① <b>{@code where} 를 반드시 넣습니다.</b>
     * {@code where} 를 빼도 컴파일되고 실행되며, 상품 40개가 전부 SOLD_OUT 이 됩니다.
     * 벌크 연산에서 {@code where} 누락은 문법 문제가 아니라 사고입니다.
     *
     * <p>② <b>조건의 순서가 SQL 의 순서입니다.</b>
     * {@code status.eq(...).and(stock.eq(0))} 로 쓰면 {@code where status=? and stock=?} 가,
     * 반대로 쓰면 {@code where stock=? and status=?} 가 나갑니다.
     * 결과는 같지만 MySQL 이 어떤 인덱스를 고를지는 통계에 달려 있으므로,
     * 대량 UPDATE 라면 생성 SQL 을 그대로 EXPLAIN 해 보는 습관이 필요합니다.
     *
     * <p>③ <b>반환값은 조회 결과가 아니라 영향 행 수</b>입니다.
     * 0이 나왔다면 "조건에 맞는 행이 없다"는 뜻이지 실패가 아닙니다.
     * 실패는 예외로 옵니다.
     *
     * <p>생성 SQL
     * <pre>
     * update products set status=? where status=? and stock=?
     * 바인딩: [1] SOLD_OUT  [2] HIDDEN  [3] 0
     * </pre>
     */
    @Test
    @DisplayName("정답 1. HIDDEN + 재고 0 → SOLD_OUT")
    void solution1() {
        long updated = queryFactory
                .update(product)
                .set(product.status, ProductStatus.SOLD_OUT)
                .where(product.status.eq(ProductStatus.HIDDEN)
                        .and(product.stock.eq(0)))
                .execute();

        System.out.println("[정답 1] 영향 행 수 = " + updated);
    }

    // =================================================================
    // 문제 2. GOLD 고객 포인트 = points * 2 + 1000
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>핵심은 {@code customer.points.multiply(2).add(1000)} 입니다.
     * 이 표현식은 자바에서 아무것도 계산하지 않습니다.
     * "points 컬럼에 2를 곱하고 1000을 더하라"는 <b>SQL 표현식 트리</b>를 만들 뿐입니다.
     * 그래서 행마다 자기 값을 기준으로 계산됩니다.
     *
     * <p><b>틀린 답</b>은 이렇게 생겼습니다.
     * <pre>
     * Customer c = em.find(Customer.class, 3L);
     * queryFactory.update(customer)
     *         .set(customer.points, c.getPoints() * 2 + 1000)   // ❌
     *         .where(customer.grade.eq(Grade.GOLD))
     *         .execute();
     * // 생성 SQL: update customers set points=? where grade=?
     * // → GOLD 9명 전원이 "3번 고객 기준으로 계산된 같은 값" 이 됩니다.
     * </pre>
     * 예외도 안 나고 영향 행 수도 9로 정상입니다.
     * 이 차이는 <b>생성 SQL 을 봐야만</b> 드러납니다.
     * 우변에 컬럼명이 있는가({@code points=points*?+?}) 없는가({@code points=?}) 로 판별하십시오.
     *
     * <p>생성 SQL
     * <pre>
     * update customers set points=points*?+? where grade=?
     * 바인딩: [1] 2  [2] 1000  [3] GOLD
     * 영향 행 수: 9
     * </pre>
     */
    @Test
    @DisplayName("정답 2. GOLD 고객 포인트 = points * 2 + 1000")
    void solution2() {
        long updated = queryFactory
                .update(customer)
                .set(customer.points, customer.points.multiply(2).add(1000))
                .where(customer.grade.eq(Grade.GOLD))
                .execute();

        System.out.println("[정답 2] GOLD 고객 갱신 = " + updated + "명 (기대: 9)");
    }

    // =================================================================
    // 문제 3. em.find 캐시 히트
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>③에서 <b>SELECT 가 한 줄도 찍히지 않습니다.</b> 이유는 한 문장으로 이렇습니다.
     *
     * <blockquote>
     * {@code em.find} 는 1차 캐시를 먼저 조회하고, 거기에 해당 PK 의 엔티티가 있으면
     * DB 에 가지 않고 그 인스턴스를 그대로 돌려주기 때문입니다.
     * </blockquote>
     *
     * <p>그리고 벌크 UPDATE 는 JPQL/SQL 로 DB 에 직접 나가므로,
     * 1차 캐시는 "1번 상품의 price 가 바뀌었다"는 사실을 알 방법이 없습니다.
     * JPA 는 벌크 문장이 어떤 행을 건드렸는지 추적하지 않습니다. 추적할 수단이 없습니다.
     *
     * <p>{@code p == again} 이 {@code true} 인 것도 같은 이유입니다.
     * 한 영속성 컨텍스트 안에서 같은 PK 는 항상 같은 인스턴스라는 것이 JPA 의 <b>동일성 보장</b>입니다.
     *
     * <p><b>더 헷갈리는 변형</b>: {@code queryFactory.selectFrom(product)...fetchOne()} 으로 바꿔도
     * 결과는 같습니다. 이때는 SELECT 가 <b>정상적으로 나가고</b> DB 에서 777000 을 읽어옵니다.
     * 그런데 Hibernate 는 PK 로 1차 캐시를 조회해 이미 있는 인스턴스를 찾으면
     * <b>ResultSet 의 나머지 컬럼을 버리고</b> 캐시 인스턴스를 반환합니다.
     * SQL 로그만 봐서는 원인을 알 수 없는 유일한 경우입니다.
     *
     * <p>로그 대조
     * <pre>
     * ① select p1_0.product_id, ... from products p1_0 where p1_0.product_id=?
     * ② update products set price=? where product_id=?
     * ③ (SQL 없음)
     * </pre>
     */
    @Test
    @DisplayName("정답 3. em.find 캐시 히트 — SQL 이 안 나갑니다")
    void solution3() {
        Product p = em.find(Product.class, 1L);
        System.out.println("① find price = " + p.getPrice());

        long updated = queryFactory
                .update(product)
                .set(product.price, new BigDecimal("777000"))
                .where(product.id.eq(1L))
                .execute();
        System.out.println("② bulk updated = " + updated + " (DB 는 이제 777000)");

        Product again = em.find(Product.class, 1L);
        System.out.println("③ find again price = " + again.getPrice());
        System.out.println("   same instance? " + (p == again));

        System.out.println("[설명] em.find 는 1차 캐시를 먼저 보고, 거기 엔티티가 있으면 "
                + "DB 에 가지 않기 때문에 SELECT 가 나가지 않았고, 그래서 옛 값이 그대로 반환됐습니다. "
                + "벌크 UPDATE 는 DB 에 직행하므로 1차 캐시는 그 변경을 알지 못합니다.");
    }

    // =================================================================
    // 문제 4. flush() → clear() 처방
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>처방은 두 줄이고, <b>순서가 의미를 갖습니다.</b>
     *
     * <p>{@code em.flush()} — 쓰기 지연 저장소에 쌓여 있던 SQL 을 DB 로 내보냅니다.
     * 벌크 연산보다 <b>먼저</b> 나가야 합니다.
     * 순서가 뒤집히면 (벌크 → flush) 더티 체킹 UPDATE 가 벌크 결과 위에 덮어써집니다. 11-5-3 의 사고입니다.
     *
     * <p>{@code em.clear()} — 1차 캐시를 비웁니다.
     * 이후의 조회는 DB 를 다시 읽으므로 벌크가 만든 값이 정확히 반영됩니다.
     *
     * <p><b>flush 없이 clear 만 하면</b> 아직 나가지 않은 변경분이 <b>그대로 버려집니다.</b>
     * {@code clear()} 는 "저장"이 아니라 "포기"입니다.
     * 아래 두 번째 블록에서, price 를 400000 으로 바꿨는데도
     * 로그에 그 UPDATE 가 <b>없다</b>는 것을 확인하십시오. 예외도 경고도 없습니다.
     *
     * <p>| 호출 | 하는 일 | 빠뜨리면 |
     * <p>| flush | 쓰기 지연 SQL 을 DB 로 | 변경분 소실 / 순서 뒤집힘 |
     * <p>| clear | 1차 캐시 비우기 | 옛 값 조회 + 더티 체킹 덮어쓰기 |
     *
     * <p>Spring Data JPA 라면 {@code @Modifying(clearAutomatically = true, flushAutomatically = true)}
     * 가 정확히 이 두 줄을 대신합니다. <b>둘 다 기본값이 false</b> 라는 점이 함정입니다.
     */
    @Test
    @DisplayName("정답 4. flush() → clear() 처방과 flush 누락")
    void solution4() {
        // --- 처방 적용 ---
        Product p = em.find(Product.class, 1L);
        System.out.println("① find price = " + p.getPrice());

        queryFactory.update(product)
                .set(product.price, new BigDecimal("777000"))
                .where(product.id.eq(1L))
                .execute();

        em.flush();
        em.clear();

        Product again = em.find(Product.class, 1L);
        System.out.println("② find again price = " + again.getPrice() + " (기대: 777000.00)");
        System.out.println("   same instance? " + (p == again) + " (기대: false)");

        // --- flush 를 빠뜨렸을 때 ---
        Product q = em.find(Product.class, 2L);
        BigDecimal before = q.getPrice();
        q.setPrice(new BigDecimal("400000"));   // 아직 DB 에 안 나감

        em.clear();                              // ❌ flush 없이 clear → 변경분 소실

        em.flush();
        em.clear();
        Product q2 = em.find(Product.class, 2L);
        System.out.println("③ 변경 전 = " + before + " / 변경 시도 = 400000 / 실제 = " + q2.getPrice());
        System.out.println("[설명] flush 없이 clear 하면 쓰기 지연 SQL 이 실행되지 않고 버려집니다. "
                + "로그에 해당 UPDATE 가 아예 없습니다.");
    }

    // =================================================================
    // 문제 5. FK 제약 위반 → 자식부터 삭제
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>{@code order_items} 의 FK 에는 {@code ON DELETE} 절이 없습니다.
     * MySQL 의 기본은 {@code RESTRICT} 이므로, 자식이 있는 상품은 지울 수 없습니다.
     * <pre>
     * CONSTRAINT fk_order_items_product
     *   FOREIGN KEY (product_id) REFERENCES products(product_id)
     * </pre>
     *
     * <p>여기서 헷갈리는 점: {@code Product} 엔티티에 {@code cascade = ALL, orphanRemoval = true} 로
     * {@code reviews} 가 매핑돼 있어도 <b>벌크 DELETE 는 그것을 무시합니다.</b>
     * 카스케이드는 Hibernate 가 자바 코드로 구현한 기능이고,
     * 벌크 DELETE 는 그 코드를 타지 않고 SQL 로 바로 나가기 때문입니다.
     *
     * <p>반면 {@code reviews} 의 FK 에는 {@code ON DELETE CASCADE} 가 있어서
     * 상품이 지워지면 후기는 <b>DB 가</b> 알아서 지웁니다.
     * 결과가 같아 보여도 경로가 다릅니다.
     * DB 가 지운 후기는 {@code @PreRemove} 가 호출되지 않고, <b>1차 캐시도 정리되지 않습니다.</b>
     *
     * <p>올바른 순서는 자식부터입니다.
     * 카스케이드 로딩이 없으니 3문장으로 끝나지만, <b>순서는 사람이 책임집니다.</b>
     *
     * <p>생성 SQL
     * <pre>
     * delete from order_items where product_id=?
     * delete from reviews where product_id=?
     * delete from products where product_id=?
     * </pre>
     *
     * <p>실무 주의: 예외가 발생한 트랜잭션은 rollback-only 로 마킹될 수 있습니다.
     * 그래서 아래에서는 재현을 별도 메서드로 분리했습니다.
     */
    @Test
    @DisplayName("정답 5-a. FK 제약 위반 재현")
    void solution5a() {
        try {
            queryFactory.delete(product)
                    .where(product.id.eq(2L))
                    .execute();
            System.out.println("[정답 5-a] 예외가 나지 않았습니다. 데이터가 초기 상태인지 확인하십시오.");
        } catch (Exception e) {
            System.out.println("[정답 5-a] 예상된 예외: " + e.getClass().getName());
            System.out.println("           Cannot delete or update a parent row: "
                    + "a foreign key constraint fails (`shop`.`order_items`, "
                    + "CONSTRAINT `fk_order_items_product` ...)");
        }
    }

    @Test
    @DisplayName("정답 5-b. 자식부터 지우는 올바른 순서")
    void solution5b() {
        long items = queryFactory
                .delete(orderItem)
                .where(orderItem.product.id.eq(2L))
                .execute();

        long reviews = queryFactory
                .delete(review)
                .where(review.product.id.eq(2L))
                .execute();

        long products = queryFactory
                .delete(product)
                .where(product.id.eq(2L))
                .execute();

        em.flush();
        em.clear();

        System.out.println("[정답 5-b] order_items=" + items
                + " / reviews=" + reviews + " / products=" + products + " — 총 3문장");
    }

    // =================================================================
    // 문제 6. 자바 값 연산 → 경로 표현식
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>틀린 부분은 {@code .set(product.stock, p.getStock() + 10)} 한 줄입니다.
     * {@code p.getStock()} 은 <b>이미 자바 메모리에 있는 int 값</b>이고,
     * {@code + 10} 은 자바가 즉시 계산해 버립니다.
     * 그 결과 하나의 상수가 바인딩 파라미터로 들어가고, 대상 행 전부가 같은 값이 됩니다.
     *
     * <p>잘못된 SQL
     * <pre>
     * update products set stock=? where status=?
     * 바인딩: [1] 60   ← 1번 상품의 재고 50 + 10 이 상수로 고정
     * </pre>
     * ON_SALE 상품 28개가 <b>전부 재고 60</b> 이 됩니다.
     * 원래 재고가 3이든 200이든 상관없이. 예외는 없습니다.
     *
     * <p>고친 SQL
     * <pre>
     * update products set stock=stock+? where status=?
     * 바인딩: [1] 10
     * </pre>
     * 우변에 {@code stock} 이라는 컬럼명이 나타나는지가 판별 기준입니다.
     *
     * <p><b>일반화</b>: {@code product.stock} 은 값이 아니라 <b>경로(Path)</b> 입니다.
     * {@code .add()}, {@code .subtract()}, {@code .multiply()}, {@code .divide()},
     * {@code .concat()} 은 전부 SQL 표현식을 만듭니다.
     * 엔티티에서 꺼낸 값으로 자바 연산을 하는 순간 그 성질을 잃습니다.
     *
     * <p>참고로 {@code BigDecimal} 컬럼에는 반드시 {@code new BigDecimal("1.1")} 형태를 쓰십시오.
     * {@code multiply(1.1)} 같은 double 은 DECIMAL 컬럼에 부동소수 오차를 흘려보냅니다.
     */
    @Test
    @DisplayName("정답 6. 자바 값 연산 → 경로 표현식")
    void solution6() {
        // --- 잘못된 코드 재현 ---
        Product p = em.find(Product.class, 1L);
        long wrong = queryFactory
                .update(product)
                .set(product.stock, p.getStock() + 10)     // ❌
                .where(product.status.eq(ProductStatus.ON_SALE))
                .execute();
        em.flush();
        em.clear();

        List<Integer> afterWrong = queryFactory
                .select(product.stock)
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .fetch();
        System.out.println("[정답 6] ❌ " + wrong + "건 갱신 후 distinct stock 개수 = "
                + afterWrong.stream().distinct().count() + " (전부 같은 값이면 1)");

        // --- 고친 코드 ---
        long right = queryFactory
                .update(product)
                .set(product.stock, product.stock.add(10))  // ✅
                .where(product.status.eq(ProductStatus.ON_SALE))
                .execute();
        em.flush();
        em.clear();

        List<Integer> afterRight = queryFactory
                .select(product.stock)
                .from(product)
                .where(product.status.eq(ProductStatus.ON_SALE))
                .fetch();
        System.out.println("[정답 6] ✅ " + right + "건 갱신 후 distinct stock 개수 = "
                + afterRight.stream().distinct().count() + " (행마다 다르면 2 이상)");
    }

    // =================================================================
    // 문제 7. clear() 전후의 조회 결과 차이
    // =================================================================

    /**
     * <b>정답 해설</b>
     *
     * <p>이 문제의 핵심은 <b>"무엇을 조회하느냐에 따라 캐시의 영향이 다르다"</b> 는 것입니다.
     *
     * <p>③ 처럼 {@code select(order.count())} 로 세면 <b>새 값이 나옵니다.</b>
     * count 는 스칼라 값이라 엔티티 인스턴스를 만들 필요가 없고,
     * 따라서 1차 캐시와 대조하는 과정 자체가 없기 때문입니다. DB 가 준 숫자를 그대로 씁니다.
     *
     * <p>그런데 엔티티를 {@code fetch()} 해서 {@code getStatus()} 로 세면 <b>옛 값이 섞입니다.</b>
     * 벌크 전에 1차 캐시에 올라와 있던 주문들은
     * SELECT 가 새 값을 읽어왔는데도 캐시 인스턴스(옛 status)로 대체되기 때문입니다.
     * <b>같은 트랜잭션에서 count 와 목록의 숫자가 어긋나는</b> 상황이 여기서 만들어집니다.
     * 화면에 "총 43건" 이라고 찍히는데 목록에는 40건만 나오는 종류의 버그입니다.
     *
     * <p>④ 처럼 {@code flush()} + {@code clear()} 를 넣으면 1차 캐시가 비어 있으므로
     * 모든 엔티티가 DB 값으로 새로 만들어집니다. count 와 목록이 일치합니다.
     *
     * <p>정리
     * <pre>
     * 조회 형태              | SELECT 발생 | 캐시 영향 | 벌크 후 정확한가
     * ----------------------|------------|----------|----------------
     * em.find (캐시 히트)    | 안 남      | 있음     | ❌ 옛 값
     * selectFrom(엔티티)     | 남         | 있음     | ❌ 옛 값 (가장 헷갈림)
     * select(스칼라/count)   | 남         | 없음     | ✅ 새 값
     * clear() 이후 모든 조회  | 남         | 없음     | ✅ 새 값
     * </pre>
     */
    @Test
    @DisplayName("정답 7. clear() 전후의 조회 결과 차이")
    void solution7() {
        // ① 기준값
        Long base = queryFactory.select(order.count()).from(order)
                .where(order.status.eq(OrderStatus.CANCELLED)).fetchOne();
        System.out.println("① 기준 CANCELLED 수 = " + base);

        // 캐시에 PENDING 주문들을 미리 올려 둡니다 (실무에서는 앞선 로직이 이미 올려놓습니다)
        List<Order> preloaded = queryFactory.selectFrom(order)
                .where(order.status.eq(OrderStatus.PENDING))
                .limit(50)
                .fetch();
        System.out.println("   캐시에 올려 둔 PENDING 주문 = " + preloaded.size() + "건");

        // ② 벌크로 상태 전이
        long changed = queryFactory
                .update(order)
                .set(order.status, OrderStatus.CANCELLED)
                .where(order.status.eq(OrderStatus.PENDING)
                        .and(order.orderDate.between(
                                LocalDateTime.of(2024, 1, 1, 0, 0),
                                LocalDateTime.of(2024, 12, 31, 23, 59, 59))))
                .execute();
        System.out.println("② 벌크 변경 = " + changed + "건");

        // ③ clear() 없이 조회
        Long countBefore = queryFactory.select(order.count()).from(order)
                .where(order.status.eq(OrderStatus.CANCELLED)).fetchOne();
        long entityCountBefore = queryFactory.selectFrom(order).fetch().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .count();
        System.out.println("③ clear 전 — count 쿼리 = " + countBefore
                + " / 엔티티로 센 값 = " + entityCountBefore);

        // ④ flush + clear 후 조회
        em.flush();
        em.clear();
        Long countAfter = queryFactory.select(order.count()).from(order)
                .where(order.status.eq(OrderStatus.CANCELLED)).fetchOne();
        long entityCountAfter = queryFactory.selectFrom(order).fetch().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .count();
        System.out.println("④ clear 후 — count 쿼리 = " + countAfter
                + " / 엔티티로 센 값 = " + entityCountAfter);

        System.out.println("[설명] count 는 스칼라라 1차 캐시를 거치지 않아 새 값이 나옵니다. "
                + "엔티티 조회는 동일성 보장 때문에 캐시에 있던 옛 status 를 돌려줍니다. "
                + "그래서 clear 전에는 count 와 목록의 숫자가 어긋납니다. "
                + "clear 후에는 캐시가 비어 둘이 일치합니다.");
    }
}
