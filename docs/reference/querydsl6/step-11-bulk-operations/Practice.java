package com.example.shop.step11;

import com.example.shop.entity.Grade;
import com.example.shop.entity.Order;
import com.example.shop.entity.OrderStatus;
import com.example.shop.entity.Product;
import com.example.shop.entity.ProductStatus;
import com.example.shop.entity.Review;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
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
 * Step 11 — 벌크 연산 : 본문 예제 전체.
 *
 * <p>⚠️ 이 클래스는 데이터를 <b>변경</b>합니다.
 * {@code @Transactional} 이 붙어 있어 각 테스트 메서드가 끝나면 자동으로 롤백됩니다.
 * <b>{@code @Rollback(false)} 나 {@code @Commit} 을 절대 추가하지 마십시오.</b>
 * {@code shop} DB 는 공용이며, 커밋하는 순간 다른 스텝의 모든 예제 결과가 어긋납니다.
 *
 * <p>실행 시 콘솔의 {@code hibernate.SQL} 로그를 교재의 <b>결과</b> 블록과 한 줄씩 대조하십시오.
 * 특히 11-5 계열 메서드는 <b>SQL 이 찍히지 않는 것</b>이 결과입니다.
 */
@SpringBootTest
@Transactional
class Practice {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // =================================================================
    // [11-1] 왜 벌크 연산인가 — 40번이냐 1번이냐
    // =================================================================

    /**
     * [11-1] 더티 체킹 방식 : SELECT 1회 + UPDATE 40회.
     * <p>생성 SQL 을 세어 보십시오. flush() 시점에 update 가 상품 수만큼 나갑니다.
     */
    @Test
    @DisplayName("[11-1] 더티 체킹으로 전 상품 가격 10% 인상 — 41문장")
    void dirtyCheckingPriceRaise() {
        long start = System.nanoTime();

        List<Product> products = queryFactory
                .selectFrom(product)
                .fetch();

        for (Product p : products) {
            p.setPrice(p.getPrice().multiply(new BigDecimal("1.1")));
        }
        em.flush();

        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[11-1] 더티 체킹 대상 " + products.size() + "건 / " + ms + "ms");
        System.out.println("       예상 문장 수: SELECT 1 + UPDATE " + products.size());
    }

    /**
     * [11-1] 벌크 방식 : UPDATE 1회.
     * <p>where 가 없으므로 전체 40건이 대상입니다. 반환값이 곧 영향 행 수입니다.
     */
    @Test
    @DisplayName("[11-1] 벌크로 전 상품 가격 10% 인상 — 1문장")
    void bulkPriceRaise() {
        long start = System.nanoTime();

        long updated = queryFactory
                .update(product)
                .set(product.price, product.price.multiply(new BigDecimal("1.1")))
                .execute();

        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[11-1] 벌크 영향 행 수 = " + updated + " / " + ms + "ms");
        System.out.println("       예상 문장 수: UPDATE 1");
        // 생성 SQL: update products set price=price*?
    }

    // =================================================================
    // [11-2] update / set / where / execute
    // =================================================================

    /**
     * [11-2] 네 조각의 기본형. ON_SALE 상품 28건이 대상입니다.
     */
    @Test
    @DisplayName("[11-2] where 로 대상을 좁힌 벌크 UPDATE")
    void bulkUpdateWithWhere() {
        long updated = queryFactory
                .update(product)                                                    // ① 대상
                .set(product.price, product.price.multiply(new BigDecimal("1.1")))  // ② 무엇을
                .where(product.status.eq(ProductStatus.ON_SALE))                    // ③ 어디에
                .execute();                                                         // ④ 실행

        System.out.println("[11-2] ON_SALE 갱신 = " + updated + "건 (기대: 28)");
        // 생성 SQL: update products set price=price*? where status=?
    }

    /**
     * [11-2] 복합 조건. 재고 0 이면서 아직 ON_SALE 인 상품만 SOLD_OUT 으로.
     */
    @Test
    @DisplayName("[11-2] 복합 조건 벌크 UPDATE")
    void bulkUpdateCompositeCondition() {
        long updated = queryFactory
                .update(product)
                .set(product.status, ProductStatus.SOLD_OUT)
                .where(product.stock.eq(0)
                        .and(product.status.eq(ProductStatus.ON_SALE)))
                .execute();

        System.out.println("[11-2] 품절 처리 = " + updated + "건");
        // 생성 SQL: update products set status=? where stock=? and status=?
    }

    /**
     * [11-2] ⚠️ null 조건은 조회에서처럼 무시됩니다.
     * <p>조회에서는 편리한 기능이지만, 벌크에서는 <b>전체 테이블 갱신</b>이 됩니다.
     * where 절 자체가 사라진 SQL 이 나가는 것을 확인하십시오.
     */
    @Test
    @DisplayName("[11-2] ⚠️ null 조건 → where 절 소멸 → 전체 갱신")
    void bulkUpdateWithNullCondition() {
        Integer minStock = null;   // 파라미터가 안 넘어온 상황

        BooleanExpression cond = (minStock == null) ? null : product.stock.goe(minStock);

        long updated = queryFactory
                .update(product)
                .set(product.status, ProductStatus.ON_SALE)
                .where(cond)          // null → where 절이 통째로 사라집니다
                .execute();

        System.out.println("[11-2] ⚠️ 영향 행 수 = " + updated + " (전체 40건. 의도한 것입니까?)");
        // 생성 SQL: update products set status=?     ← where 없음
    }

    /**
     * [11-2] 방어 코드. BooleanBuilder 가 비었으면 실행을 막습니다.
     */
    @Test
    @DisplayName("[11-2] 조건 없는 벌크를 방어하는 패턴")
    void bulkUpdateGuardEmptyCondition() {
        Integer minStock = null;
        ProductStatus status = null;

        BooleanBuilder builder = new BooleanBuilder();
        if (minStock != null) builder.and(product.stock.goe(minStock));
        if (status != null)   builder.and(product.status.eq(status));

        if (!builder.hasValue()) {
            System.out.println("[11-2] 조건이 비어 실행을 거부했습니다. (의도한 방어)");
            return;
        }

        long updated = queryFactory
                .update(product)
                .set(product.status, ProductStatus.ON_SALE)
                .where(builder)
                .execute();
        System.out.println("[11-2] 갱신 = " + updated);
    }

    // =================================================================
    // [11-3] set 체이닝과 자기 참조 연산
    // =================================================================

    /**
     * [11-3] set 은 여러 번 이어 붙일 수 있습니다.
     * <p>{@code product.stock.add(10)} 은 자바 값 연산이 아니라 <b>SQL 표현식</b>입니다.
     * 그래서 {@code stock=stock+?} 이 되고 행마다 자기 값을 기준으로 계산됩니다.
     */
    @Test
    @DisplayName("[11-3] set 체이닝 + 경로 기반 산술 표현식")
    void bulkUpdateChainedSet() {
        long updated = queryFactory
                .update(product)
                .set(product.price, product.price.multiply(new BigDecimal("1.1")))
                .set(product.stock, product.stock.add(10))
                .set(product.status, ProductStatus.ON_SALE)
                .where(product.category.id.eq(7L))
                .execute();

        System.out.println("[11-3] 카테고리 7 갱신 = " + updated + "건");
        // 생성 SQL: update products set price=price*?, stock=stock+?, status=? where category_id=?
    }

    /**
     * [11-3] ⚠️ 자바에서 값을 계산해 상수로 넣으면 전 행이 같은 값이 됩니다.
     * <p>에러도 안 나고 영향 행 수도 정상입니다. 로그를 안 보면 못 잡습니다.
     */
    @Test
    @DisplayName("[11-3] ⚠️ 자바 값 연산 vs 경로 표현식 — 결과가 다릅니다")
    void javaValueVsPathExpression() {
        Product p = em.find(Product.class, 1L);
        System.out.println("[11-3] 1번 상품 재고 = " + p.getStock());

        // ❌ 자바에서 계산 → 상수 하나가 전 행에 박힙니다
        long wrong = queryFactory
                .update(product)
                .set(product.stock, p.getStock() + 10)
                .where(product.category.id.eq(7L))
                .execute();
        System.out.println("[11-3] ❌ 상수 방식 = " + wrong + "건이 전부 같은 값이 됨");
        // 생성 SQL: update products set stock=? where category_id=?   바인딩 [1] 60

        em.flush();
        em.clear();

        // ✅ 경로 표현식 → 행마다 자기 값 기준
        long right = queryFactory
                .update(product)
                .set(product.stock, product.stock.add(10))
                .where(product.category.id.eq(7L))
                .execute();
        System.out.println("[11-3] ✅ 표현식 방식 = " + right + "건이 각자 +10");
        // 생성 SQL: update products set stock=stock+? where category_id=?   바인딩 [1] 10
    }

    // =================================================================
    // [11-4] delete
    // =================================================================

    /**
     * [11-4] 조건부 삭제. rating=1 인 후기 16건이 대상입니다.
     */
    @Test
    @DisplayName("[11-4] 낮은 평점 후기 벌크 삭제")
    void bulkDelete() {
        long deleted = queryFactory
                .delete(review)
                .where(review.rating.lt(2))
                .execute();

        System.out.println("[11-4] 삭제 = " + deleted + "건 (기대: 16)");
        // 생성 SQL: delete from reviews where rating<?
    }

    /**
     * [11-4] 서브쿼리 조건. 숨김 상품의 후기를 지웁니다.
     */
    @Test
    @DisplayName("[11-4] 서브쿼리를 조건으로 쓰는 벌크 삭제")
    void bulkDeleteWithSubquery() {
        long deleted = queryFactory
                .delete(review)
                .where(review.product.id.in(
                        JPAExpressions.select(product.id)
                                .from(product)
                                .where(product.status.eq(ProductStatus.HIDDEN))))
                .execute();

        System.out.println("[11-4] 숨김 상품 후기 삭제 = " + deleted + "건");
        // 생성 SQL:
        // delete from reviews
        // where product_id in (select p1_0.product_id from products p1_0 where p1_0.status=?)
    }

    // =================================================================
    // [11-5] ⚠️⚠️ 벌크 연산은 영속성 컨텍스트를 건너뜁니다
    // =================================================================

    /**
     * [11-5-1] 사고 ① — 갱신했는데 옛 값이 읽힙니다.
     *
     * <p><b>확인할 것</b>: 마지막 em.find 에서 <b>SELECT 가 한 줄도 안 찍힙니다.</b>
     * 1차 캐시에 Product#1 이 이미 있으므로 DB 에 갈 이유가 없기 때문입니다.
     * DB 에는 500000 이 들어 있지만 아무도 읽으러 가지 않습니다.
     */
    @Test
    @DisplayName("[11-5-1] ⚠️ em.find 는 캐시 히트 — SQL 이 아예 안 나갑니다")
    void staleReadByFind() {
        Product p = em.find(Product.class, 1L);
        System.out.println("① find: " + p.getName() + " / " + p.getPrice());

        long updated = queryFactory
                .update(product)
                .set(product.price, new BigDecimal("500000"))
                .where(product.id.eq(1L))
                .execute();
        System.out.println("② bulk updated = " + updated);

        Product again = em.find(Product.class, 1L);
        System.out.println("③ find again: " + again.getPrice() + "   ← 500000 이 아닙니다");
        System.out.println("④ same instance? " + (p == again));
        // ★ ③ 위쪽에 select 로그가 없는지 확인하십시오.
    }

    /**
     * [11-5-2] 사고 ② — fetch() 로 조회해도 옛 값이 나옵니다.
     *
     * <p><b>확인할 것</b>: 이번에는 SELECT 가 <b>정상적으로 나갑니다.</b>
     * DB 에서 500000 을 읽어옵니다. 그런데 반환된 객체의 값은 459000 입니다.
     * JPA 의 <b>동일성 보장</b> 때문에, 1차 캐시에 같은 PK 의 엔티티가 있으면
     * Hibernate 는 ResultSet 의 나머지 컬럼을 <b>버리고</b> 캐시 인스턴스를 돌려줍니다.
     * 로그를 봐도 원인이 안 보이는 유일한 경우입니다.
     */
    @Test
    @DisplayName("[11-5-2] ⚠️ fetch() 는 SQL 이 나가는데도 옛 값을 돌려줍니다")
    void staleReadByFetch() {
        Product p = em.find(Product.class, 1L);
        System.out.println("① find: " + p.getPrice());

        queryFactory.update(product)
                .set(product.price, new BigDecimal("500000"))
                .where(product.id.eq(1L))
                .execute();

        Product fetched = queryFactory
                .selectFrom(product)
                .where(product.id.eq(1L))
                .fetchOne();

        System.out.println("② fetched: " + fetched.getPrice() + "   ← SQL 은 500000 을 읽었습니다");
        System.out.println("③ same instance? " + (p == fetched) + "   ← 새 객체가 아닙니다");
    }

    /**
     * [11-5-3] 사고 ③ — 더티 체킹이 벌크 결과를 덮어씁니다. <b>진짜 사고입니다.</b>
     *
     * <p>로딩 시점 스냅샷(stock=50)과 현재 값(49)을 비교해 {@code set stock=49} 를 씁니다.
     * DB 에 150 이 들어 있다는 사실은 이 계산 어디에도 들어오지 않습니다.
     * 벌크로 더한 100 이 통째로 사라집니다.
     */
    @Test
    @DisplayName("[11-5-3] ⚠️⚠️ 더티 체킹이 벌크 결과를 덮어씁니다")
    void dirtyCheckingOverwritesBulk() {
        Product p = em.find(Product.class, 1L);
        int loaded = p.getStock();
        System.out.println("① 로딩 시점 재고: " + loaded);

        queryFactory.update(product)
                .set(product.stock, product.stock.add(100))
                .execute();
        System.out.println("② 벌크 완료. DB 의 1번 상품 재고는 이제 " + (loaded + 100));

        p.setStock(p.getStock() - 1);   // 옛 값 기준 계산
        em.flush();                      // 더티 체킹 UPDATE 발생

        em.clear();
        Product after = em.find(Product.class, 1L);
        System.out.println("③ 커밋 직전 DB 값: " + after.getStock()
                + "   ← " + (loaded + 100 - 1) + " 가 아니라 " + (loaded - 1) + " 입니다");
    }

    /**
     * [11-5-4] 처방 : flush() 다음에 clear(). <b>순서가 중요합니다.</b>
     * <p>flush 로 쓰기 지연 SQL 을 먼저 내보내고(벌크가 그것을 덮어쓰지 않도록),
     * clear 로 1차 캐시를 비웁니다.
     */
    @Test
    @DisplayName("[11-5-4] ✅ flush() → clear() 처방 적용")
    void fixWithFlushAndClear() {
        Product p = em.find(Product.class, 1L);
        System.out.println("① find: " + p.getPrice());

        queryFactory.update(product)
                .set(product.price, new BigDecimal("500000"))
                .where(product.id.eq(1L))
                .execute();

        em.flush();   // ① 쓰기 지연 SQL 먼저
        em.clear();   // ② 1차 캐시 비우기

        Product again = em.find(Product.class, 1L);
        System.out.println("② find again: " + again.getPrice() + "   ← 맞습니다");
        System.out.println("③ same instance? " + (p == again) + "   ← clear 로 새 인스턴스");
        // ★ ② 위쪽에 select 로그가 <b>다시 나타나는</b> 것을 확인하십시오.
    }

    /**
     * [11-5-4] ⚠️ flush 없이 clear 만 하면 변경분이 <b>소실</b>됩니다.
     * <p>clear() 는 "저장"이 아니라 "포기"입니다.
     */
    @Test
    @DisplayName("[11-5-4] ⚠️ flush 없는 clear — 변경분이 조용히 사라집니다")
    void clearWithoutFlushLosesChanges() {
        Product p = em.find(Product.class, 1L);
        p.setPrice(new BigDecimal("400000"));   // 아직 DB 에 안 나감

        em.clear();                              // ❌ flush 없이 clear

        queryFactory.update(product)
                .set(product.stock, product.stock.add(100))
                .execute();
        em.flush();

        em.clear();
        Product after = em.find(Product.class, 1L);
        System.out.println("[11-5-4] price = " + after.getPrice()
                + "   ← 400000 으로 바꾼 변경분의 UPDATE 는 나가지 않았습니다");
    }

    /**
     * [11-5-5] 벌크 메서드 안에 처방을 묶어 두는 패턴.
     * <p>QueryDSL 커스텀 리포지토리에는 {@code @Modifying} 이 없으므로 직접 불러야 합니다.
     * 호출자가 잊지 않도록 메서드 안에 넣습니다.
     */
    @Test
    @DisplayName("[11-5-5] 처방을 묶어 둔 벌크 메서드")
    void bulkMethodWithBuiltInSync() {
        long updated = raisePriceOfOnSale(new BigDecimal("1.1"));
        System.out.println("[11-5-5] updated = " + updated);
    }

    private long raisePriceOfOnSale(BigDecimal rate) {
        em.flush();
        long updated = queryFactory
                .update(product)
                .set(product.price, product.price.multiply(rate))
                .where(product.status.eq(ProductStatus.ON_SALE))
                .execute();
        em.clear();
        // 벌크는 @PreUpdate / Envers 가 동작하지 않으므로 애플리케이션 로그를 직접 남깁니다.
        System.out.println("bulk price raise: rate=" + rate + ", affected=" + updated);
        return updated;
    }

    // =================================================================
    // [11-6] 벌크 연산과 낙관적 락
    // =================================================================

    /**
     * [11-6] 이 코스 엔티티에는 @Version 이 없습니다. 개념만 코드로 남깁니다.
     * <p>만약 Product 에 {@code @Version Long version} 이 있다면 이렇게 써야 합니다.
     * <pre>
     * queryFactory.update(product)
     *         .set(product.price, product.price.multiply(new BigDecimal("1.1")))
     *         .set(product.version, product.version.add(1L))   // ★ 수동 증가
     *         .where(product.status.eq(ProductStatus.ON_SALE))
     *         .execute();
     * // 생성 SQL: update products set price=price*?, version=version+? where status=?
     * </pre>
     * 이것을 빠뜨리면 낙관적 락이 <b>조용히 무력화</b>됩니다.
     * 옛 버전을 들고 있는 트랜잭션의 {@code where ... and version=?} 이 여전히 통과하기 때문입니다.
     */
    @Test
    @DisplayName("[11-6] 벌크는 @Version 을 올리지 않습니다 (개념)")
    void optimisticLockingNote() {
        System.out.println("[11-6] 벌크 UPDATE 는 version 을 set 에도 where 에도 넣지 않습니다.");
        System.out.println("       @Version 이 있는 엔티티에 벌크를 쓸 때는 set(version, version.add(1)) 을 규칙으로.");
    }

    // =================================================================
    // [11-7] 벌크 연산과 카스케이드
    // =================================================================

    /**
     * [11-7-1] em.remove 는 cascade 가 돕니다 — 자식 SELECT + 자식 DELETE N개.
     */
    @Test
    @DisplayName("[11-7-1] em.remove — 카스케이드 정상 동작")
    void removeWithCascade() {
        Product p = em.find(Product.class, 12L);
        em.remove(p);
        em.flush();
        System.out.println("[11-7-1] 자식 컬렉션 SELECT → 자식 DELETE N개 → 부모 DELETE 순서를 확인하십시오.");
    }

    /**
     * [11-7-2] 벌크 DELETE — cascade / orphanRemoval 이 <b>무시</b>됩니다.
     * <p>DELETE 문 하나만 나갑니다. 자식 SELECT 도 자식 DELETE 도 없습니다.
     * 그럼에도 성공하는 이유는 reviews 의 FK 에 DB 레벨 ON DELETE CASCADE 가 있기 때문입니다.
     * <b>JPA 가 아니라 DB 가 지운 것</b>이며, 1차 캐시는 정리되지 않습니다.
     */
    @Test
    @DisplayName("[11-7-2] 벌크 DELETE — cascade 무시, DB 가 대신 처리")
    void bulkDeleteIgnoresCascade() {
        long deleted = queryFactory
                .delete(product)
                .where(product.id.eq(12L))
                .execute();
        System.out.println("[11-7-2] deleted = " + deleted + " / 자식 관련 SQL 이 없는지 확인하십시오.");
        // 생성 SQL: delete from products where product_id=?
    }

    /**
     * [11-7-3] FK 제약 위반. order_items 의 FK 에는 ON DELETE CASCADE 가 없습니다(RESTRICT).
     * <p>DataIntegrityViolationException 이 execute() 즉시 터집니다.
     */
    @Test
    @DisplayName("[11-7-3] ⚠️ 벌크 DELETE 가 FK 제약에 걸립니다")
    void bulkDeleteViolatesForeignKey() {
        try {
            queryFactory.delete(product)
                    .where(product.id.eq(1L))
                    .execute();
            System.out.println("[11-7-3] 예외가 안 났다면 데이터가 초기 상태가 아닙니다.");
        } catch (Exception e) {
            System.out.println("[11-7-3] 예상된 예외: " + e.getClass().getSimpleName());
            System.out.println("         " + e.getMessage());
        }
    }

    /**
     * [11-7-4] 올바른 순서 : 자식부터. 3문장으로 끝납니다.
     * <p>카스케이드 로딩이 없는 대신 <b>순서를 사람이 책임집니다.</b>
     */
    @Test
    @DisplayName("[11-7-4] ✅ 자식부터 지우는 순서 — 3문장")
    void bulkDeleteInCorrectOrder() {
        long items = queryFactory
                .delete(orderItem)
                .where(orderItem.product.id.eq(1L))
                .execute();

        long reviews = queryFactory
                .delete(review)
                .where(review.product.id.eq(1L))
                .execute();

        long products = queryFactory
                .delete(product)
                .where(product.id.eq(1L))
                .execute();

        em.flush();
        em.clear();

        System.out.println("[11-7-4] items=" + items + " reviews=" + reviews + " products=" + products);
    }

    // =================================================================
    // [11-8] insert — QueryDSL-JPA 에는 없습니다
    // =================================================================

    /**
     * [11-8] QueryDSL-JPA 에 insert 가 없는 이유는 JPQL 에 INSERT ... VALUES 가 없기 때문입니다.
     * <p>대안 ① : em.persist. getReference 를 쓰면 연관 엔티티 SELECT 를 피할 수 있습니다.
     */
    @Test
    @DisplayName("[11-8] insert 대안 ① em.persist")
    void insertWithPersist() {
        Review r = new Review();
        r.setProduct(em.getReference(Product.class, 1L));
        r.setCustomer(em.getReference(com.example.shop.entity.Customer.class, 5L));
        r.setRating(5);
        r.setTitle("만족합니다");
        r.setCreatedAt(LocalDateTime.now());
        em.persist(r);
        em.flush();

        System.out.println("[11-8] 생성된 review_id = " + r.getId());
        // 생성 SQL:
        // insert into reviews (body, created_at, customer_id, product_id, rating, title)
        // values (?, ?, ?, ?, ?, ?)
    }

    /**
     * [11-8] 대안 ② : batch insert. batch_size 와 맞춰 주기적으로 flush/clear 합니다.
     * <p>⚠️ @GeneratedValue(strategy = IDENTITY) 면 배치가 <b>조용히 무효화</b>됩니다.
     * Hibernate 가 키를 알아야 하므로 persist 즉시 INSERT 를 실행하기 때문입니다.
     */
    @Test
    @DisplayName("[11-8] insert 대안 ② batch insert 패턴")
    void insertWithBatch() {
        int batchSize = 100;
        for (int i = 0; i < 300; i++) {
            Review r = new Review();
            r.setProduct(em.getReference(Product.class, 1L));
            r.setCustomer(em.getReference(com.example.shop.entity.Customer.class, 5L));
            r.setRating((i % 5) + 1);
            r.setTitle("배치 후기 " + i);
            r.setCreatedAt(LocalDateTime.now());
            em.persist(r);

            if (i > 0 && i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();
        em.clear();
        System.out.println("[11-8] batch insert 300건 완료 (IDENTITY 전략이면 배치로 안 묶입니다)");
    }

    // =================================================================
    // [11-9] 벌크 + 더티 체킹 혼합 배치
    // =================================================================

    /**
     * [11-9] 대량 상태 전이는 벌크로, 로직이 필요한 소수 건만 엔티티로.
     * <p>①과 ② 사이의 {@code em.clear()} 가 핵심입니다.
     * 없으면 ②의 SELECT 가 옛 상태(PENDING)를 가진 캐시 엔티티를 돌려줍니다.
     */
    @Test
    @DisplayName("[11-9] 벌크 + 더티 체킹 혼합 배치")
    void mixedBatch() {
        LocalDateTime cutoff = LocalDateTime.of(2025, 1, 1, 0, 0);

        em.flush();

        long closed = queryFactory
                .update(order)
                .set(order.status, OrderStatus.CANCELLED)
                .where(order.status.eq(OrderStatus.PENDING)
                        .and(order.orderDate.before(cutoff)))
                .execute();

        em.clear();   // ★ 없으면 아래 조회가 옛 값을 돌려줍니다

        List<Order> refundTargets = queryFactory
                .selectFrom(order)
                .where(order.status.eq(OrderStatus.CANCELLED)
                        .and(order.totalAmount.gt(new BigDecimal("100000"))))
                .fetch();

        System.out.println("[11-9] closed=" + closed + ", refundTargets=" + refundTargets.size());
    }

    // =================================================================
    // [11-10] MySQL8 코스와 나란히 — 조인 UPDATE 우회
    // =================================================================

    /**
     * [11-10] JPQL 에는 조인 UPDATE 가 없습니다. 서브쿼리로 우회합니다.
     * <pre>
     * -- MySQL
     * UPDATE orders o JOIN customers c ON o.customer_id = c.customer_id
     * SET o.status = 'CANCELLED' WHERE c.grade = 'BRONZE';
     * </pre>
     */
    @Test
    @DisplayName("[11-10] 조인 UPDATE 를 서브쿼리로 우회")
    void joinUpdateWorkaround() {
        long updated = queryFactory
                .update(order)
                .set(order.status, OrderStatus.CANCELLED)
                .where(order.customer.id.in(
                        JPAExpressions.select(customer.id)
                                .from(customer)
                                .where(customer.grade.eq(Grade.BRONZE))))
                .execute();

        System.out.println("[11-10] BRONZE 고객 주문 취소 = " + updated + "건");
        // 생성 SQL:
        // update orders set status=?
        // where customer_id in (select c1_0.customer_id from customers c1_0 where c1_0.grade=?)
    }
}
