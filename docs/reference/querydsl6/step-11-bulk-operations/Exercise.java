package com.example.shop.step11;

import com.example.shop.entity.Grade;
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

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;

/**
 * Step 11 — 벌크 연산 : 연습문제 7문제.
 *
 * <p>규칙
 * <ul>
 *   <li>모든 문제는 이 클래스의 {@code @Transactional} 덕분에 롤백됩니다. {@code @Commit} 금지.</li>
 *   <li>답이 맞아도 <b>생성 SQL 이 다르면 틀린 것</b>입니다. 콘솔 로그를 반드시 확인하십시오.</li>
 *   <li>3번과 7번은 <b>설명을 문자열로 출력</b>하는 것까지가 답입니다.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class Exercise {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    // -----------------------------------------------------------------
    // 문제 1. HIDDEN 이면서 재고가 0 인 상품의 상태를 SOLD_OUT 으로 바꾸십시오.
    //
    // 요구사항
    //   - 벌크 UPDATE 한 문장으로 처리할 것
    //   - 영향 행 수를 출력할 것
    //   - 생성 SQL 이 다음 형태인지 확인할 것
    //       update products set status=? where status=? and stock=?
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 1. HIDDEN + 재고 0 → SOLD_OUT")
    void exercise1() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 2. GOLD 등급 고객의 포인트를 "현재 값 * 2 + 1000" 으로 만드십시오.
    //
    // 요구사항
    //   - 자바에서 값을 계산해 상수로 넣지 말 것. 경로 표현식만 사용할 것
    //   - 생성 SQL 에 points 가 우변에 나타나야 함 (예: points=points*?+?)
    //   - 영향 행 수를 출력할 것 (GOLD 는 9명)
    //
    // 힌트: NumberPath 의 multiply / add 는 체이닝됩니다.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 2. GOLD 고객 포인트 = points * 2 + 1000")
    void exercise2() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 3. 아래 순서로 실행하고, 두 번째 find 에서 SELECT 가 나가지 않는 것을
    //         로그로 확인한 뒤 그 이유를 한 문장으로 출력하십시오.
    //
    //   ① em.find(Product.class, 1L)
    //   ② 벌크로 1번 상품 price 를 777000 으로 변경
    //   ③ em.find(Product.class, 1L)  ← 여기서 SELECT 가 나가는가?
    //
    // 요구사항
    //   - ①과 ③의 price 를 각각 출력할 것
    //   - ①과 ③이 같은 인스턴스인지(==) 출력할 것
    //   - "왜 SELECT 가 안 나갔는가" 를 System.out.println 으로 설명할 것
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 3. em.find 캐시 히트 — SQL 이 안 나가는 것을 확인")
    void exercise3() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 4. 문제 3 의 코드에 em.flush() / em.clear() 를 넣어
    //         ③에서 새 값(777000)이 나오게 고치십시오.
    //
    // 추가 요구사항
    //   - 같은 메서드 안에서, flush 없이 clear 만 했을 때 무엇이 소실되는지도 재현할 것
    //     (엔티티 값을 바꾼 뒤 flush 없이 clear → 그 변경의 UPDATE 가 로그에 없음을 확인)
    //   - 두 경우의 차이를 출력할 것
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 4. flush() → clear() 처방과, flush 를 빠뜨렸을 때")
    void exercise4() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 5. 상품 2번을 벌크로 삭제할 때 FK 제약 위반이 나는 것을 재현하고,
    //         자식부터 지우는 순서로 고쳐 3문장으로 완료하십시오.
    //
    // 요구사항
    //   - 먼저 delete(product).where(id=2) 를 try/catch 로 감싸 예외 클래스명을 출력할 것
    //   - 이후 order_items → reviews → products 순서로 삭제할 것
    //   - 각 단계의 영향 행 수를 출력할 것
    //   - 마지막에 em.flush() / em.clear() 를 부를 것
    //
    // 힌트: 예외가 난 트랜잭션은 rollback-only 로 표시될 수 있습니다.
    //       재현과 수정을 별도 메서드로 나누어도 됩니다.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 5. FK 제약 위반 재현 → 자식부터 삭제")
    void exercise5() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 6. 아래 코드는 예외가 나지 않지만 결과가 틀립니다.
    //         무엇이 틀렸는지 찾아 고치고, 잘못된 SQL 과 고친 SQL 을 주석으로 적으십시오.
    //
    //   Product p = em.find(Product.class, 1L);
    //   queryFactory.update(product)
    //           .set(product.stock, p.getStock() + 10)
    //           .where(product.status.eq(ProductStatus.ON_SALE))
    //           .execute();
    //
    // 요구사항
    //   - 고치기 전/후를 모두 실행해 ON_SALE 상품들의 stock 분포를 비교 출력할 것
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 6. 자바 값 연산 → 경로 표현식으로 수정")
    void exercise6() {
        // 여기에 작성:

    }

    // -----------------------------------------------------------------
    // 문제 7. 다음을 순서대로 수행하십시오.
    //
    //   ① CANCELLED 주문 개수를 세어 출력 (기준값)
    //   ② PENDING 이면서 2024년에 만들어진 주문을 벌크로 CANCELLED 로 변경
    //   ③ clear() 없이 CANCELLED 주문을 조회해 개수를 출력
    //   ④ em.flush(); em.clear(); 후 다시 조회해 개수를 출력
    //
    // 요구사항
    //   - ③과 ④의 개수가 왜 다른지(혹은 왜 같은지) 를 출력으로 설명할 것
    //   - 힌트: count 쿼리와 엔티티 조회는 캐시의 영향을 받는 정도가 다릅니다.
    //           엔티티를 fetch 해서 status 를 꺼내 세어 보면 차이가 드러납니다.
    // -----------------------------------------------------------------
    @Test
    @DisplayName("문제 7. clear() 전후의 조회 결과 차이")
    void exercise7() {
        // 여기에 작성:

    }
}
