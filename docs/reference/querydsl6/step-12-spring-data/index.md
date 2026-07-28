# Step 12 — Spring Data JPA 통합

> **학습 목표**
> - `JpaRepository` 로 표현할 수 없는 쿼리를 커스텀 리포지토리 3층 구조로 분리한다
> - `Impl` 접미사 규칙을 정확히 이해하고, 어긋났을 때 나는 에러 메시지의 **진짜 원인**을 읽는다
> - `QuerydslRepositorySupport` 와 `JPAQueryFactory` 직접 주입을 비교하고 선택 기준을 세운다
> - `QuerydslPredicateExecutor` 의 4가지 한계를 알고, 언제 써도 되는지 판단한다
> - `@QuerydslPredicate` 웹 바인딩의 보안 위험을 이해하고 화이트리스트로 막는다
> - 커스텀 리포지토리에서 `Page<OrderDto>` 를 반환하고 count 쿼리를 최적화한다
> - `@DataJpaTest` 에서 `JPAQueryFactory` 빈이 없어 실패하는 문제를 해결한다
>
> **선행 스텝**: [Step 11 — 벌크 연산](../step-11-bulk-operations/)
> **예상 소요**: 110분

---

## 12-1. 문제 — `JpaRepository` 는 어디까지 됩니까

Spring Data JPA 의 메서드 이름 쿼리는 강력합니다. 이 정도는 코드 없이 됩니다.

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByStatus(OrderStatus status);
    List<Order> findByCustomerIdAndStatusOrderByOrderDateDesc(Long customerId, OrderStatus status);
    Page<Order> findByOrderDateBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);
    long countByStatus(OrderStatus status);
}
```

그러다 이런 요구가 옵니다.

> "주문 목록 검색 화면을 만들어 주세요.
> 고객명·주문상태·도시·기간·최소금액으로 **필터링할 수 있고, 안 채운 조건은 무시**됩니다.
> 정렬은 주문일/금액/고객명 중 고르고, 페이징도 됩니다.
> 결과에는 **고객명과 결제수단이 같이** 나와야 합니다."

메서드 이름으로 쓰면 이렇게 됩니다.

```java
List<Order> findByCustomerNameContainingAndStatusAndShippingCityAndOrderDateBetweenAndTotalAmountGreaterThanEqualOrderByOrderDateDesc(...);
```

이름이 길다는 게 문제가 아닙니다. **이 메서드는 요구사항을 만족하지 못합니다.**

- 조건 5개 중 일부만 채우는 조합이 $2^5 = 32$ 가지입니다. 메서드를 32개 만들 수 없습니다
- 정렬이 3가지이므로 `Pageable` 의 `Sort` 로 받아야 하는데, 정렬 키 화이트리스트는 어디서 검증합니까 (Step 10)
- 반환 타입이 `Order` 엔티티입니다. 고객명·결제수단을 붙이려면 DTO 프로젝션이 필요합니다 (Step 05)
- `@Query` 로 JPQL 을 쓰면 동적 조건을 문자열 결합으로 만들게 됩니다. Step 04 로 돌아가는 셈입니다

**여기서부터가 QueryDSL 의 자리입니다.** 문제는 "QueryDSL 코드를 어디에 두느냐" 입니다.

`JpaRepository` 는 인터페이스입니다. 구현체는 Spring Data 가 런타임에 프록시로 만듭니다.
그 인터페이스에 QueryDSL 코드를 넣을 자리는 없습니다.
서비스 계층에 `JPAQueryFactory` 를 직접 주입해 쓸 수도 있지만,
그러면 쿼리가 서비스에 흩어지고 리포지토리는 껍데기만 남습니다.

Spring Data 는 이 문제를 위해 **커스텀 리포지토리** 라는 확장점을 제공합니다.
통합 방식은 크게 세 가지이고, 이 스텝에서 전부 다룹니다.

| 방식 | 코드량 | 유연성 | 실무 비중 |
|---|---|---|---|
| 커스텀 리포지토리 (`~Impl`) | 파일 3~4개 | 제한 없음 | **기본** |
| `QuerydslRepositorySupport` 상속 | 파일 3~4개 | 제한 없음 (`from` 시작 강제) | 페이징 편의가 필요할 때 |
| `QuerydslPredicateExecutor` | **0줄** | 매우 제한적 | 단순 CRUD 검색만 |

---

## 12-2. 커스텀 리포지토리 3층 구조

Spring Data 의 **커스텀 구현(custom implementation)** 메커니즘입니다.
파일 3개(+ DTO 1개)로 이루어집니다.

```
com.example.shop.repository
├── OrderRepository.java          ← JpaRepository + 커스텀 인터페이스 상속
├── OrderRepositoryCustom.java    ← 커스텀 메서드 선언 (프래그먼트 인터페이스)
├── OrderRepositoryImpl.java      ← QueryDSL 구현
└── OrderSearchDto.java           ← 반환 DTO
```

### ① 프래그먼트 인터페이스

```java
package com.example.shop.repository;

import com.example.shop.dto.OrderSearchCond;
import com.example.shop.dto.OrderSearchDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderRepositoryCustom {
    Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable);
}
```

### ② 구현체 — 이름이 `OrderRepositoryImpl` 이어야 합니다

```java
package com.example.shop.repository;

import com.example.shop.dto.OrderSearchCond;
import com.example.shop.dto.OrderSearchDto;
import com.example.shop.dto.QOrderSearchDto;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;

@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable) {
        List<OrderSearchDto> content = queryFactory
                .select(new QOrderSearchDto(
                        order.id, order.orderDate, order.status,
                        order.totalAmount, customer.name, order.shippingCity))
                .from(order)
                .join(order.customer, customer)
                .where(
                        customerNameContains(cond.customerName()),
                        statusEq(cond.status()),
                        cityEq(cond.city()),
                        amountGoe(cond.minAmount()))
                .orderBy(toOrderSpecifiers(pageable))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(order.count())
                .from(order)
                .join(order.customer, customer)
                .where(
                        customerNameContains(cond.customerName()),
                        statusEq(cond.status()),
                        cityEq(cond.city()),
                        amountGoe(cond.minAmount()));

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private BooleanExpression customerNameContains(String name) {
        return (name == null || name.isBlank()) ? null : customer.name.contains(name);
    }

    private BooleanExpression statusEq(OrderStatus status) {
        return status == null ? null : order.status.eq(status);
    }

    private BooleanExpression cityEq(String city) {
        return (city == null || city.isBlank()) ? null : order.shippingCity.eq(city);
    }

    private BooleanExpression amountGoe(BigDecimal amount) {
        return amount == null ? null : order.totalAmount.goe(amount);
    }
}
```

조건 메서드를 분리하는 방식은 Step 10 에서 다룬 그대로입니다.
`null` 을 반환하면 `where` 에서 무시되므로 동적 조건이 자연스럽게 조립됩니다.

### ③ 메인 리포지토리 — 둘 다 상속

```java
package com.example.shop.repository;

import com.example.shop.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository
        extends JpaRepository<Order, Long>, OrderRepositoryCustom {
    // 메서드 이름 쿼리는 그대로 쓸 수 있습니다
    List<Order> findByStatus(OrderStatus status);
}
```

### 무슨 일이 일어나는가

`OrderRepository` 는 인터페이스이고 구현체가 없습니다.
Spring Data 는 기동 시점에 이 인터페이스의 **프록시**를 만들면서, 메서드마다 처리 주체를 정합니다.

```
OrderRepository 프록시
├─ save, findById, findAll, delete ...  → SimpleJpaRepository (Spring Data 기본 구현)
├─ findByStatus(...)                    → 메서드 이름 파싱 → JPQL 생성
└─ searchOrders(cond, pageable)         → OrderRepositoryImpl 인스턴스로 위임 ★
```

세 번째 줄이 이 스텝의 주제입니다.
Spring Data 는 **"이 리포지토리에 대응하는 커스텀 구현 클래스가 있는지"** 를 찾고,
있으면 해당 메서드 호출을 그 클래스의 인스턴스에 넘깁니다.

**그 "찾는" 규칙이 클래스 이름 기반입니다.** 그리고 여기서 사고가 납니다.

**결과** — 호출 예

```java
OrderSearchCond cond = new OrderSearchCond("류하나", OrderStatus.PAID, "서울", null);
Page<OrderSearchDto> page = orderRepository.searchOrders(cond, PageRequest.of(0, 10));
```

```sql
select o1_0.order_id, o1_0.order_date, o1_0.status, o1_0.total_amount,
       c1_0.name, o1_0.shipping_city
from orders o1_0
join customers c1_0 on c1_0.customer_id=o1_0.customer_id
where c1_0.name like ? escape '!' and o1_0.status=? and o1_0.shipping_city=?
order by o1_0.order_date desc
limit ?, ?
```
```
바인딩: [1] %류하나%  [2] PAID  [3] 서울  [4] 0  [5] 10
조회 3건
```

---

## 12-3. ⚠️ `Impl` 접미사 하나로 애플리케이션이 안 뜹니다

**이 절이 Step 12 의 핵심입니다.**

### 12-3-1. 규칙

> **커스텀 구현 클래스의 이름은 `<리포지토리 인터페이스 이름> + Impl` 이어야 합니다.**

`OrderRepository` 라는 리포지토리에 대해서는 **`OrderRepositoryImpl`** 입니다.

여기에 하나 더 있습니다. Spring Data 는 **프래그먼트 인터페이스 이름 + `Impl`** 도 인식합니다.
`OrderRepositoryCustom` 이 프래그먼트이므로 **`OrderRepositoryCustomImpl`** 도 동작합니다.

| 클래스 이름 | 동작 | 근거 |
|---|---|---|
| `OrderRepositoryImpl` | ✅ | 리포지토리 인터페이스 이름 + `Impl` |
| `OrderRepositoryCustomImpl` | ✅ | 프래그먼트 인터페이스 이름 + `Impl` |
| `OrderRepositoryImplementation` | ❌ | 접미사가 `Impl` 이 아님 |
| `OrderRepositoryQuerydsl` | ❌ | 접미사가 `Impl` 이 아님 |
| `OrderRepositoryIMPL` | ❌ | 대소문자가 다름 |
| `CustomOrderRepositoryImpl` | ❌ | **접두사**로 붙였음. 규칙은 접미사 |
| `OrderRepositoryImpl2` | ❌ | `Impl` 뒤에 뭔가 더 붙었음 |
| `OrdersRepositoryImpl` | ❌ | 리포지토리 이름 자체가 다름 (`Orders` vs `Order`) |

`OrderRepositoryImpl2` 같은 실수는 잘 안 합니다.
실제로 자주 나오는 것은 **`CustomOrderRepositoryImpl`** 입니다.
"커스텀 구현이니까 Custom 을 앞에 붙이자"는 자연스러운 발상인데, 규칙은 접미사입니다.

### 12-3-2. 어긋났을 때 나는 에러

`OrderRepositoryImpl` 을 `OrderRepositoryQuerydsl` 로 바꾸고 애플리케이션을 띄웁니다.
**컴파일은 통과합니다.** `OrderRepositoryQuerydsl` 은 `OrderRepositoryCustom` 을 정상적으로 구현하고 있고,
자바 입장에서 아무 문제가 없습니다.

**결과** — 기동 로그

```
***************************
APPLICATION FAILED TO START
***************************

Description:

org.springframework.beans.factory.BeanCreationException:
  Error creating bean with name 'orderRepository' defined in
  com.example.shop.repository.OrderRepository defined in @EnableJpaRepositories
  declared on JpaRepositoriesRegistrar.EnableJpaRepositoriesConfiguration:
  Invocation of init method failed

Caused by: org.springframework.data.repository.query.QueryCreationException:
  Could not create query for public abstract org.springframework.data.domain.Page
  com.example.shop.repository.OrderRepositoryCustom.searchOrders(
      com.example.shop.dto.OrderSearchCond,
      org.springframework.data.domain.Pageable);
  Reason: Failed to create query for method
  public abstract org.springframework.data.domain.Page
  com.example.shop.repository.OrderRepositoryCustom.searchOrders(...)!
  No property 'searchOrders' found for type 'Order'

Caused by: org.springframework.data.mapping.PropertyReferenceException:
  No property 'searchOrders' found for type 'Order'
    at org.springframework.data.mapping.PropertyPath.<init>(PropertyPath.java:91)
    at org.springframework.data.repository.query.parser.Part.<init>(Part.java:82)
    ...
```

### 12-3-3. 이 에러 메시지가 왜 원인을 안 가리키는가

**메시지 어디에도 "Impl 을 못 찾았다"는 말이 없습니다.**
`No property 'searchOrders' found for type 'Order'` 만 보고 있으면
"`Order` 엔티티에 `searchOrders` 필드를 추가해야 하나?" 같은 엉뚱한 생각을 하게 됩니다.

인과는 이렇습니다.

```
① Spring Data 가 OrderRepository 프록시를 만든다
        ↓
② 메서드 searchOrders 를 처리할 주체를 찾는다
        ↓
③ 커스텀 구현 클래스 후보 이름을 계산한다
     "OrderRepositoryImpl" / "OrderRepositoryCustomImpl"
        ↓
④ 스캔한 빈 중에 그 이름이 있는가?  →  없다 (실제 이름은 OrderRepositoryQuerydsl)
        ↓
⑤ "커스텀 구현이 아니구나. 그럼 메서드 이름 쿼리겠지" 라고 판단한다  ★ 여기가 함정
        ↓
⑥ "searchOrders" 를 파싱한다 → 접두사 search... 는 조회 키워드
        ↓
⑦ 그 뒤의 "Orders" 를 Order 엔티티의 프로퍼티로 해석하려 한다
        ↓
⑧ Order 에 그런 프로퍼티가 없다 → PropertyReferenceException
        ↓
⑨ QueryCreationException 으로 감싸짐 → BeanCreationException → 기동 실패
```

**⑤ 가 핵심입니다.** Spring Data 는 "구현체를 못 찾았다"고 에러를 내지 않습니다.
그건 정상적인 경우이기 때문입니다 — 대부분의 리포지토리 메서드는 구현체 없이 이름으로 처리됩니다.
그래서 조용히 다음 전략(메서드 이름 파싱)으로 넘어가고,
**그 전략이 실패한 지점의 에러만** 우리에게 보입니다.

에러 메시지는 ⑧ 을 말하고 있는데 진짜 원인은 ④ 에 있습니다. 네 단계 떨어져 있습니다.

> ⚠️ **함정 — `No property 'xxx' found for type 'Entity'` 를 보면 먼저 클래스 이름을 확인하십시오**
> 이 메시지가 나오는 원인은 두 가지뿐입니다.
> ① 진짜 메서드 이름 쿼리에서 프로퍼티 이름을 틀린 경우 (`findByCustmerName` 같은 오타)
> ② **커스텀 구현 클래스를 못 찾아서 메서드 이름 쿼리로 오인된 경우**
> 메서드 이름이 `search~`, `find~Complex`, `~ByCondition` 처럼
> **메서드 이름 쿼리로 만들 리 없는 형태**라면 ② 를 먼저 의심하십시오.
> 확인 순서: 클래스 이름 접미사 → 패키지 위치 → `implements` 여부.

### 12-3-4. 실패 원인 4가지

접미사 오타 말고도 같은 증상을 내는 원인이 몇 가지 더 있습니다.

| # | 원인 | 증상 | 확인 방법 |
|---|---|---|---|
| 1 | **접미사 오타** — `~Querydsl`, `~Implementation`, `Custom~Impl` | `QueryCreationException` / `No property ... found` | 클래스 이름이 정확히 `<리포지토리명>Impl` 인가 |
| 2 | **패키지가 다름** — `repository.impl` 하위에 둠 | 위와 동일 | `@EnableJpaRepositories` 의 `basePackages` 아래인가 |
| 3 | **`implements` 를 안 함** — 이름만 `~Impl` | 위와 동일 | `implements OrderRepositoryCustom` 이 있는가 |
| 4 | **`@Repository` + 필드 주입 혼용** | `NoSuchBeanDefinitionException` 또는 `null` 주입 | 생성자 주입인가 |

각각을 봅니다.

**① 접미사 오타** — 12-3-2 그대로입니다.

**② 패키지 위치**

```
❌ com.example.shop.repository.OrderRepository
   com.example.shop.repository.impl.OrderRepositoryImpl     ← impl 하위 패키지
```

Spring Data 는 커스텀 구현 클래스를 **리포지토리 인터페이스와 같은 패키지 또는 그 하위**에서 찾습니다.
정확히는 `@EnableJpaRepositories(basePackages = ...)` 로 지정한 스캔 범위 안이면 됩니다.
Spring Boot 자동 설정에서는 `@SpringBootApplication` 이 붙은 클래스의 패키지가 기준입니다.

실제로는 `repository.impl` 하위에 둬도 **동작하는 경우가 많습니다.**
스캔 범위 안이기 때문입니다. 문제가 되는 것은 스캔 범위 자체를 좁혀 놓은 프로젝트입니다.

```java
// 멀티 모듈에서 이런 설정을 해 둔 경우
@EnableJpaRepositories(basePackages = "com.example.shop.repository")
```

이 상태에서 구현체를 `com.example.shop.support` 에 두면 못 찾습니다.
**혼동을 줄이려면 리포지토리 인터페이스와 같은 패키지에 나란히 두십시오.**

**③ `implements` 누락**

```java
// 이름은 맞는데 구현 선언이 없음
public class OrderRepositoryImpl {          // ❌ implements OrderRepositoryCustom 없음
    public Page<OrderSearchDto> searchOrders(...) { ... }
}
```

Spring Data 는 이름으로 클래스를 찾은 뒤, **그 클래스가 프래그먼트 인터페이스를 구현하는지** 확인합니다.
안 하면 후보에서 제외됩니다. 그리고 다시 메서드 이름 쿼리로 넘어갑니다. 같은 에러가 납니다.

**④ `@Repository` 와 주입 방식**

```java
@Repository                       // 붙여도 되지만 필수는 아닙니다
public class OrderRepositoryImpl implements OrderRepositoryCustom {
    @Autowired                    // ❌ 필드 주입
    private JPAQueryFactory queryFactory;
}
```

Spring Data 는 커스텀 구현 클래스를 **자신이 직접 인스턴스화**합니다
(컴포넌트 스캔으로 만든 빈이 있으면 그것을 재사용하지만, 없으면 생성자로 만듭니다).
필드 주입은 스프링 컨테이너가 만든 빈에만 적용되므로,
Spring Data 가 직접 만든 인스턴스에서는 `queryFactory` 가 `null` 로 남을 수 있습니다.

**생성자 주입을 쓰십시오.** Spring Data 는 생성자 파라미터를 컨테이너에서 해결해 줍니다.

```java
@RequiredArgsConstructor          // ✅ 생성자 주입
public class OrderRepositoryImpl implements OrderRepositoryCustom {
    private final JPAQueryFactory queryFactory;
}
```

### 12-3-5. 접미사를 바꾸고 싶다면

팀 컨벤션이 `~Impl` 이 아니라면 접미사 자체를 바꿀 수 있습니다.

```java
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.shop.repository",
        repositoryImplementationPostfix = "Querydsl"     // 기본값은 "Impl"
)
public class JpaConfig {
}
```

이제 `OrderRepositoryQuerydsl` 이 정상 동작합니다.

다만 실무에서는 권장하지 않습니다.
Spring Data 를 아는 사람이라면 누구나 `~Impl` 을 기대하고,
설정을 바꿔 놓으면 새로 합류한 사람이 12-3-2 의 에러를 만났을 때
**검색해도 답이 안 나옵니다.** 표준을 벗어난 대가는 대체로 이런 식으로 청구됩니다.

> 💡 **실무 팁 — 기동 실패를 테스트로 미리 잡으십시오**
> 이 함정의 무서운 점은 **컴파일 시점에 전혀 드러나지 않는다**는 것입니다.
> IDE 도 조용하고 빌드도 성공합니다. 실행해야 알 수 있습니다.
> 리포지토리를 하나라도 로딩하는 `@SpringBootTest` 나 `@DataJpaTest` 가 CI 에 있으면
> 배포 전에 잡힙니다. 그런 테스트가 하나도 없는 프로젝트에서
> 이 문제는 **운영 배포 후 기동 실패**로 나타납니다.

---

## 12-4. `Impl` 에 `JPAQueryFactory` 주입하기

`QuerydslConfig` 는 Step 01 에서 만든 그대로입니다.

```java
@Configuration
public class QuerydslConfig {
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }
}
```

여기서 주입되는 `EntityManager` 는 **프록시**입니다.
스프링이 `@PersistenceContext` 를 통해 넣어 주는 이 프록시는
호출 시점의 **현재 트랜잭션에 바인딩된 진짜 EntityManager** 로 위임합니다.
그래서 `JPAQueryFactory` 를 싱글턴 빈으로 등록해도 스레드 안전합니다.

구현체에서는 생성자 주입으로 받습니다.

```java
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {
    private final JPAQueryFactory queryFactory;
    // ...
}
```

Lombok 을 안 쓴다면 생성자를 직접 씁니다.

```java
public class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public OrderRepositoryImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }
}
```

`EntityManager` 도 필요하면 (Step 11 의 `flush`/`clear` 처럼) 같이 받습니다.

```java
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private final EntityManager em;

    @Override
    public long cancelExpired(LocalDateTime cutoff) {
        em.flush();
        long updated = queryFactory
                .update(order)
                .set(order.status, OrderStatus.CANCELLED)
                .where(order.status.eq(OrderStatus.PENDING)
                        .and(order.orderDate.before(cutoff)))
                .execute();
        em.clear();
        return updated;
    }
}
```

> ⚠️ **함정 — 구현체에 `@Transactional` 을 안 붙이면 벌크가 터집니다**
> `execute()` 는 쓰기 트랜잭션을 요구합니다.
> 구현체 메서드를 트랜잭션 없이 호출하면
> `TransactionRequiredException: Executing an update/delete query` 가 납니다.
> 조회 메서드는 트랜잭션 없이도 동작하므로 이 차이를 잊기 쉽습니다.
> 트랜잭션 경계는 보통 **서비스 계층**에 둡니다. 리포지토리에 `@Transactional` 을 흩뿌리지 마십시오.

---

## 12-5. `QuerydslRepositorySupport`

Spring Data JPA 가 제공하는 추상 클래스입니다. 상속해서 씁니다.

```java
public class OrderRepositoryImpl extends QuerydslRepositorySupport
        implements OrderRepositoryCustom {

    public OrderRepositoryImpl() {
        super(Order.class);      // 도메인 클래스를 넘겨야 합니다
    }

    @Override
    public List<Order> searchSimple(OrderSearchCond cond) {
        return from(order)                       // ← select 가 아니라 from 으로 시작
                .join(order.customer, customer)
                .where(statusEq(cond.status()))
                .fetch();
    }
}
```

### 제공하는 것

| 메서드 | 설명 |
|---|---|
| `getEntityManager()` | 현재 트랜잭션의 `EntityManager` |
| `from(EntityPath)` | `JPQLQuery` 시작. 내부적으로 `new JPAQuery<>(em).from(...)` |
| `getQuerydsl()` | `Querydsl` 헬퍼 |
| `getQuerydsl().applyPagination(pageable, query)` | **`Sort` → `orderBy` 변환 + offset/limit 적용** |

`applyPagination` 이 이 클래스를 쓰는 거의 유일한 이유입니다.

```java
@Override
public Page<Order> searchPaged(OrderSearchCond cond, Pageable pageable) {
    JPQLQuery<Order> query = from(order)
            .join(order.customer, customer)
            .where(statusEq(cond.status()), cityEq(cond.city()));

    // ① Sort → OrderSpecifier 변환 + offset/limit 을 한 번에
    JPQLQuery<Order> paged = getQuerydsl().applyPagination(pageable, query);

    List<Order> content = paged.fetch();
    long total = query.fetchCount();      // ⚠️ deprecated. 아래 참고
    return new PageImpl<>(content, pageable, total);
}
```

**결과** — `PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "orderDate"))`

```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
join customers c1_0 on c1_0.customer_id=o1_0.customer_id
where o1_0.status=?
order by o1_0.order_date desc
limit ?, ?
```
```
바인딩: [1] PAID  [2] 10  [3] 10
조회 10건 (2페이지)
```

Step 10 에서는 이 변환을 손으로 했습니다.

```java
// Step 10 방식 — 화이트리스트 + 수동 변환
private OrderSpecifier<?>[] toOrderSpecifiers(Pageable pageable) {
    return pageable.getSort().stream()
            .map(o -> switch (o.getProperty()) {
                case "orderDate"   -> new OrderSpecifier<>(dir(o), order.orderDate);
                case "totalAmount" -> new OrderSpecifier<>(dir(o), order.totalAmount);
                case "customerName"-> new OrderSpecifier<>(dir(o), customer.name);
                default -> throw new IllegalArgumentException("정렬 불가: " + o.getProperty());
            })
            .toArray(OrderSpecifier[]::new);
}
```

`applyPagination` 은 이걸 자동으로 해 줍니다.
`Sort` 의 프로퍼티 이름을 엔티티 경로로 해석해서 `orderBy` 를 붙입니다.

**편리하지만 위험합니다.** 자동 변환은 **화이트리스트가 없다**는 뜻이기도 합니다.
`?sort=customer.email,desc` 같은 요청이 오면 그대로 `order by c1_0.email desc` 가 나갑니다.
정렬 가능 필드를 통제하려면 결국 검증 코드를 따로 써야 합니다 (Step 10 10-7).

### 단점

**① `select` 로 시작할 수 없습니다.**

`from(...)` 만 제공하므로 DTO 프로젝션을 쓰려면 이렇게 됩니다.

```java
// JPAQueryFactory 방식
queryFactory.select(new QOrderSearchDto(...)).from(order)...

// QuerydslRepositorySupport 방식 — select 를 뒤에 붙여야 합니다
from(order).select(new QOrderSearchDto(...))...
```

동작은 합니다. 다만 SQL 을 읽는 순서(`SELECT ... FROM ...`)와 코드 순서가 어긋나
가독성이 떨어집니다. 팀에서 두 방식이 섞이면 더 나빠집니다.

**② `EntityManager` 주입 시점이 늦습니다.**

`QuerydslRepositorySupport` 는 `@Autowired setEntityManager(EntityManager)` 로
**세터 주입**을 받습니다. 즉 생성자 실행 시점에는 `EntityManager` 가 아직 `null` 입니다.

```java
public OrderRepositoryImpl() {
    super(Order.class);
    // ❌ 여기서 from(order) 를 호출하면 NPE
}
```

필드 초기화에 쿼리를 쓰는 경우는 드물지만, 생성자에서 뭔가 준비하려다 걸립니다.

**③ `fetchCount()` 가 deprecated 입니다.**

`QuerydslRepositorySupport` 의 예제 대부분이 `fetchCount()` 를 씁니다.
QueryDSL 5.0 부터 이 메서드는 deprecated 이고
(복잡한 쿼리에서 잘못된 count 를 만드는 문제 때문입니다),
6.x 에서도 그대로 deprecated 입니다.
count 는 `select(entity.count())` 로 **직접 작성**해야 합니다 (Step 09).

### 비교표

| 항목 | `JPAQueryFactory` 직접 주입 | `QuerydslRepositorySupport` 상속 |
|---|---|---|
| 시작 지점 | `select(...)` / `selectFrom(...)` 자유 | **`from(...)` 강제** |
| 페이징 | `offset`/`limit` 수동 | `applyPagination` 자동 |
| `Sort` 변환 | 수동 (화이트리스트 가능) | **자동 (화이트리스트 없음)** |
| count 쿼리 | 직접 작성 | 예제들이 deprecated `fetchCount` 사용 |
| `EntityManager` | 생성자 주입 | **세터 주입 (생성자에서 못 씀)** |
| 상속 슬롯 | 자유 (다른 클래스 상속 가능) | **소모됨** |
| 테스트 | `JPAQueryFactory` 만 mock | 상위 클래스 상태까지 고려 |
| 실무 선호 | **높음** | 낮아지는 추세 |

**이 코스는 `JPAQueryFactory` 직접 주입을 기본으로 권장합니다.**
`applyPagination` 하나 때문에 상속 슬롯을 쓰고, `select` 시작을 포기하고,
화이트리스트를 잃는 것은 수지가 맞지 않습니다.
`Sort` 변환은 Step 10 의 유틸 메서드로 재사용할 수 있고, 그쪽이 안전합니다.

---

## 12-6. `QuerydslPredicateExecutor`

**코드를 한 줄도 안 쓰는** 방식입니다.

```java
public interface ProductRepository
        extends JpaRepository<Product, Long>, QuerydslPredicateExecutor<Product> {
}
```

이것만으로 다음 메서드가 생깁니다.

```java
Optional<Product> findOne(Predicate predicate);
Iterable<Product> findAll(Predicate predicate);
Iterable<Product> findAll(Predicate predicate, Sort sort);
Iterable<Product> findAll(Predicate predicate, OrderSpecifier<?>... orders);
Page<Product> findAll(Predicate predicate, Pageable pageable);
long count(Predicate predicate);
boolean exists(Predicate predicate);
```

사용해 봅니다.

```java
BooleanExpression cond = product.status.eq(ProductStatus.ON_SALE)
        .and(product.price.between(new BigDecimal("10000"), new BigDecimal("500000")));

Page<Product> page = productRepository.findAll(
        cond, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "price")));
```

**결과**

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.status=? and p1_0.price between ? and ?
order by p1_0.price desc
limit ?, ?
```
```sql
select count(p1_0.product_id) from products p1_0
where p1_0.status=? and p1_0.price between ? and ?
```
```
바인딩: [1] ON_SALE  [2] 10000  [3] 500000
총 21건 중 10건 조회
```

count 쿼리까지 알아서 만들어 줍니다. **구현 코드는 0줄입니다.**

### 한계 4가지

**① 조인을 명시할 수 없습니다.**

`Predicate` 하나만 받으므로 `join`, `leftJoin`, `on` 을 지정할 방법이 없습니다.
연관을 조건에 쓰면 **묵시적 조인**이 발생합니다.

```java
BooleanExpression cond = product.category.name.eq("노트북");
Iterable<Product> result = productRepository.findAll(cond);
```

**결과**

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
join categories c1_0 on c1_0.category_id=p1_0.category_id
where c1_0.name=?
```

여기서는 inner join 이 나왔습니다. 그런데 **left join 이 필요하면 방법이 없습니다.**
`category` 가 nullable 이었다면 이 쿼리는 조용히 행을 누락시킵니다.
Step 06 에서 다룬 내용입니다 — 묵시적 조인은 조인 종류를 개발자가 통제할 수 없습니다.

**② fetch join 이 불가능합니다.**

```java
Iterable<Product> products = productRepository.findAll(product.status.eq(ProductStatus.ON_SALE));
for (Product p : products) {
    System.out.println(p.getCategory().getName());   // 지연 로딩
}
```

**결과**

```sql
select p1_0.product_id, ... from products p1_0 where p1_0.status=?
```
```sql
select c1_0.category_id, c1_0.name, c1_0.parent_id, c1_0.sort_order
from categories c1_0 where c1_0.category_id=?
select c1_0.category_id, ... from categories c1_0 where c1_0.category_id=?
... (카테고리 종류 수만큼 반복)
```
```
1 + N 문제. QuerydslPredicateExecutor 에는 fetch join 을 지정할 수단이 없습니다.
```

`@EntityGraph` 를 메서드에 붙이면 되지 않느냐고 생각할 수 있지만,
`findAll(Predicate, Pageable)` 은 **우리가 선언한 메서드가 아니라 상속받은 메서드**입니다.
애노테이션을 붙일 자리가 없습니다.

**③ DTO 프로젝션이 불가능합니다.**

반환 타입이 `Product` 로 고정입니다.
`Projections` 도 `@QueryProjection` 도 쓸 수 없습니다.
고객명을 붙인 DTO 가 필요한 순간 이 방식은 끝입니다.

**④ QueryDSL 의존이 상위 계층으로 번집니다.**

가장 구조적인 문제입니다.

```java
// 서비스 계층
public Page<Product> search(ProductSearchCond cond, Pageable pageable) {
    BooleanBuilder builder = new BooleanBuilder();
    if (cond.status() != null) builder.and(product.status.eq(cond.status()));
    if (cond.minPrice() != null) builder.and(product.price.goe(cond.minPrice()));
    return productRepository.findAll(builder, pageable);   // Predicate 를 넘김
}
```

`Predicate` 를 누가 만듭니까? 리포지토리 밖입니다.
그러면 **서비스가 `QProduct` 를 import 하고, `com.querydsl.core.types.Predicate` 를 다룹니다.**
심하면 컨트롤러까지 올라갑니다 (12-7).

쿼리 기술은 리포지토리에 갇혀 있어야 교체할 수 있습니다.
`Predicate` 가 서비스 시그니처에 들어가는 순간, QueryDSL 을 걷어내려면
서비스 전체를 고쳐야 합니다.

### 언제 씁니까

| 상황 | 판단 |
|---|---|
| 단일 엔티티 + 단순 조건 검색 | ✅ 적합. 코드 0줄이 정당함 |
| 관리자 화면의 임시 조회 | ✅ 적합 |
| 조인이 필요 | ❌ 커스텀 리포지토리로 |
| fetch join / N+1 대응 필요 | ❌ 커스텀 리포지토리로 |
| DTO 반환 | ❌ 커스텀 리포지토리로 |
| 집계·서브쿼리·`CASE` | ❌ 커스텀 리포지토리로 |
| `Predicate` 를 컨트롤러에서 만들려는 계획 | ❌ 설계를 다시 보십시오 |

**실무 기본값은 커스텀 리포지토리입니다.**
`QuerydslPredicateExecutor` 는 "이 엔티티는 정말 단순 조회만 한다"고 확신할 때만 얹으십시오.
그리고 나중에 요구사항이 커지면 **미련 없이 커스텀 리포지토리로 옮기십시오.**
두 방식은 공존할 수 있습니다.

```java
public interface ProductRepository extends
        JpaRepository<Product, Long>,
        QuerydslPredicateExecutor<Product>,      // 단순 조회
        ProductRepositoryCustom {                // 복잡한 조회
}
```

---

## 12-7. `@QuerydslPredicate` 웹 바인딩

Spring Data 는 **HTTP 쿼리스트링을 `Predicate` 로 자동 변환**하는 기능도 제공합니다.

```java
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;

    @GetMapping("/products")
    public Page<Product> search(
            @QuerydslPredicate(root = Product.class) Predicate predicate,
            Pageable pageable) {
        return productRepository.findAll(predicate, pageable);
    }
}
```

```
GET /products?status=ON_SALE&stock=0&page=0&size=10
```

**결과**

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.status=? and p1_0.stock=?
limit ?, ?
```
```
바인딩: [1] ON_SALE  [2] 0
```

컨트롤러 코드가 세 줄입니다. 검색 API 가 끝났습니다.

### ⚠️ 그런데 이건 위험합니다

> ⚠️ **함정 — URL 쿼리스트링이 그대로 WHERE 절이 됩니다**
> 기본 동작은 **엔티티의 모든 프로퍼티가 필터링 가능**입니다.
> 개발자가 의도한 필드만 열리는 게 아닙니다.
> 클라이언트가 `?cost=120000` 을 보내면 그대로 `where p1_0.cost=?` 가 나갑니다.
> **`cost`(원가)는 절대 외부에 노출하면 안 되는 값입니다.**
> 값을 직접 못 봐도, **바이너리 서치로 알아낼 수 있습니다.**
> `?cost=100000` → 0건, `?cost=120000` → 1건. 이렇게 몇 번 요청하면 원가가 나옵니다.
> 이건 데이터 유출입니다. 로그에도 "정상 조회"로만 남습니다.

같은 방식으로 유출 가능한 것들입니다.

| 엔티티 | 위험 필드 | 유출되는 것 |
|---|---|---|
| `Product` | `cost` | 원가 → 마진율 |
| `Customer` | `phone`, `birthDate`, `email` | 개인정보 존재 여부 확인 |
| `Employee` | `salary` | 급여 |
| `Order` | `totalAmount` | 특정 금액대 주문 존재 여부 |

`Customer` 에 `?phone=010-1234-5678` 을 보내면
**그 번호를 쓰는 고객이 있는지 없는지**가 결과 건수로 드러납니다.
회원 가입 여부 확인은 흔한 정찰 기법입니다.

### 화이트리스트로 막기

`QuerydslBinderCustomizer` 를 리포지토리에 구현합니다.

```java
public interface ProductRepository extends
        JpaRepository<Product, Long>,
        QuerydslPredicateExecutor<Product>,
        QuerydslBinderCustomizer<QProduct> {

    @Override
    default void customize(QuerydslBindings bindings, QProduct product) {

        // ① 기본 바인딩을 전부 끄고 시작합니다  ★ 가장 중요
        bindings.excludeUnlistedProperties(true);

        // ② 허용할 필드만 명시적으로 나열합니다
        bindings.including(product.name, product.status, product.price);

        // ③ 필드별 연산자를 지정합니다
        bindings.bind(product.name).first(StringExpression::contains);
        bindings.bind(product.status).first(SimpleExpression::eq);
        bindings.bind(product.price).all((path, values) -> {
            Iterator<? extends BigDecimal> it = values.iterator();
            BigDecimal from = it.next();
            return it.hasNext()
                    ? Optional.of(path.between(from, it.next()))
                    : Optional.of(path.goe(from));
        });

        // ④ 명시적으로 차단 (①이 있으면 중복이지만, 의도를 문서화하는 값어치가 있습니다)
        bindings.excluding(product.cost);
    }
}
```

`excludeUnlistedProperties(true)` 가 핵심입니다.
**이 한 줄이 "나열하지 않은 것은 전부 금지"로 정책을 뒤집습니다.**
이게 없으면 `including` 은 "이건 특별히 이렇게 처리하라"는 의미일 뿐,
나머지 필드는 여전히 기본 규칙(equals)으로 열려 있습니다.

**결과** — `GET /products?cost=120000&name=노트북`

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.name like ? escape '!'
```
```
바인딩: [1] %노트북%
cost 파라미터는 무시됐습니다. WHERE 절에 없습니다.
```

`cost=120000` 이 조용히 버려졌습니다. 에러도 안 납니다.
필터가 무시되면 결과가 더 많이 나올 뿐이므로 **안전한 방향의 실패**입니다.

> 💡 **실무 팁 — 정렬 키에도 같은 원칙이 필요합니다**
> `Pageable` 의 `sort` 파라미터도 같은 경로입니다.
> `?sort=cost,desc` 로 정렬하면 값을 못 봐도 **순서로 원가 순위를 알 수 있습니다.**
> Step 10 의 정렬 키 화이트리스트가 여기서 다시 필요합니다.
> 조건과 정렬은 **둘 다** 막아야 합니다. 한쪽만 막으면 다른 쪽으로 새어 나갑니다.

### 판단

`@QuerydslPredicate` 는 **내부 관리자 도구** 정도에는 유용합니다.
외부에 공개되는 API 에는 권장하지 않습니다.
검색 조건은 DTO 로 명시적으로 받는 편이 훨씬 안전하고, 문서화도 쉽습니다.

```java
// 권장 — 조건을 명시적 DTO 로
@GetMapping("/products")
public Page<ProductSearchDto> search(ProductSearchCond cond, Pageable pageable) {
    return productService.search(cond, pageable);
}

public record ProductSearchCond(String name, ProductStatus status,
                                BigDecimal minPrice, BigDecimal maxPrice) {}
```

무엇을 받을 수 있는지가 **타입으로 고정**됩니다.
`cost` 는 애초에 받을 방법이 없습니다.

---

## 12-8. 계층 설계 — QueryDSL 은 어디까지 나와도 됩니까

원칙은 하나입니다.

> **`Tuple` 과 `Predicate` 는 리포지토리 밖으로 나가지 않습니다.**

Step 05 5-3 에서 `Tuple` 에 대해 같은 이야기를 했습니다.
`Tuple` 은 `tuple.get(customer.name)` 처럼 **Q타입을 알아야 값을 꺼낼 수 있습니다.**
서비스가 `Tuple` 을 받으면 서비스도 Q타입을 알아야 하고, 컴파일 타임 안전성도 사라집니다.
`Predicate` 도 같습니다. QueryDSL 타입입니다.

| 계층 | 허용 | 금지 |
|---|---|---|
| Repository (`~Impl`) | `JPAQueryFactory`, Q타입, `Tuple`, `Predicate`, `OrderSpecifier` | — |
| Repository 인터페이스 | DTO, 엔티티, `Pageable`, `Page`, 조건 DTO | **`Predicate`, `Tuple`, `OrderSpecifier`** |
| Service | 엔티티, DTO, 조건 DTO | Q타입, `Predicate`, `Tuple` |
| Controller | 요청/응답 DTO | 엔티티, Q타입, `Predicate` |

**나쁜 시그니처**

```java
// ❌ 리포지토리 인터페이스가 QueryDSL 타입을 노출
public interface OrderRepositoryCustom {
    List<Tuple> aggregateByCity(Predicate predicate);
    Page<Order> search(BooleanBuilder builder, Pageable pageable);
}
```

호출하는 쪽이 `BooleanBuilder` 를 만들어야 합니다. 서비스가 QueryDSL 을 씁니다.

**좋은 시그니처**

```java
// ✅ 도메인 언어로만 소통
public interface OrderRepositoryCustom {
    Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable);
    List<CitySalesDto> aggregateByCity(LocalDate from, LocalDate to);
    long cancelExpired(LocalDateTime cutoff);
}
```

조건은 `OrderSearchCond` 라는 **평범한 record** 로 받습니다.

```java
public record OrderSearchCond(
        String customerName,
        OrderStatus status,
        String city,
        BigDecimal minAmount) {
}
```

이 record 는 컨트롤러 → 서비스 → 리포지토리를 그대로 통과합니다.
QueryDSL 의존이 없으므로 어느 계층에서도 부담이 없습니다.
`BooleanExpression` 으로의 번역은 **`~Impl` 안에서만** 일어납니다.

```java
// ~Impl 내부 — 여기서만 QueryDSL 타입이 등장합니다
private BooleanExpression statusEq(OrderStatus status) {
    return status == null ? null : order.status.eq(status);
}
```

`Tuple` 도 같습니다. 집계 결과는 `~Impl` 안에서 DTO 로 바꿔 내보냅니다.

```java
// ❌ Tuple 을 그대로 반환
public List<Tuple> aggregateByCity(...) {
    return queryFactory.select(order.shippingCity, order.totalAmount.sum())
            .from(order).groupBy(order.shippingCity).fetch();
}

// ✅ DTO 로 변환해 반환
public List<CitySalesDto> aggregateByCity(LocalDate from, LocalDate to) {
    return queryFactory
            .select(new QCitySalesDto(order.shippingCity, order.totalAmount.sum(), order.count()))
            .from(order)
            .where(order.orderDate.between(from.atStartOfDay(), to.atTime(23, 59, 59)))
            .groupBy(order.shippingCity)
            .fetch();
}
```

> 💡 **실무 팁 — 의존 방향을 테스트로 고정하십시오**
> ArchUnit 같은 도구로 "`service` 패키지는 `com.querydsl` 을 import 할 수 없다"를
> 테스트로 박아 두면 원칙이 코드 리뷰에 의존하지 않습니다.
> ```java
> noClasses().that().resideInAPackage("..service..")
>     .should().dependOnClassesThat().resideInAPackage("com.querydsl..")
> ```
> 규칙은 문서가 아니라 실패하는 테스트일 때 지켜집니다.

---

## 12-9. 페이징 통합 — `Page<OrderDto>` 완성 코드

Step 09 에서 배운 `PageableExecutionUtils` 를 커스텀 리포지토리에 적용합니다.

### DTO

```java
package com.example.shop.dto;

import com.querydsl.core.annotations.QueryProjection;

public record OrderSearchDto(
        Long orderId,
        LocalDateTime orderDate,
        OrderStatus status,
        BigDecimal totalAmount,
        String customerName,
        String shippingCity) {

    @QueryProjection
    public OrderSearchDto {
    }
}
```

`@QueryProjection` 이 `QOrderSearchDto` 를 생성합니다 (Step 05).
컴파일 타임에 생성자 인자 타입이 검증되므로 필드명 오타로 인한 `null` 이 원천 차단됩니다.

### 구현

```java
@Override
public Page<OrderSearchDto> searchOrders(OrderSearchCond cond, Pageable pageable) {

    // ① 콘텐츠 쿼리
    List<OrderSearchDto> content = queryFactory
            .select(new QOrderSearchDto(
                    order.id,
                    order.orderDate,
                    order.status,
                    order.totalAmount,
                    customer.name,
                    order.shippingCity))
            .from(order)
            .join(order.customer, customer)
            .where(
                    customerNameContains(cond.customerName()),
                    statusEq(cond.status()),
                    cityEq(cond.city()),
                    amountGoe(cond.minAmount()))
            .orderBy(toOrderSpecifiers(pageable.getSort()))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    // ② count 쿼리 — 아직 실행하지 않습니다 (JPAQuery 를 그대로 넘깁니다)
    JPAQuery<Long> countQuery = queryFactory
            .select(order.count())
            .from(order)
            .join(order.customer, customer)     // where 에 customer 를 쓰므로 조인이 필요
            .where(
                    customerNameContains(cond.customerName()),
                    statusEq(cond.status()),
                    cityEq(cond.city()),
                    amountGoe(cond.minAmount()));

    // ③ 필요할 때만 count 를 실행합니다
    return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
}
```

**결과** — `PageRequest.of(0, 10, Sort.by(DESC, "orderDate"))`, 조건 `status=PAID`

```sql
select o1_0.order_id, o1_0.order_date, o1_0.status, o1_0.total_amount,
       c1_0.name, o1_0.shipping_city
from orders o1_0
join customers c1_0 on c1_0.customer_id=o1_0.customer_id
where o1_0.status=?
order by o1_0.order_date desc
limit ?, ?
```
```sql
select count(o1_0.order_id)
from orders o1_0
join customers c1_0 on c1_0.customer_id=o1_0.customer_id
where o1_0.status=?
```
```
바인딩(①): [1] PAID  [2] 0  [3] 10
바인딩(②): [1] PAID
결과: Page 0 / 총 152건 / 16페이지 / 콘텐츠 10건
```

### `PageableExecutionUtils` 가 count 를 생략하는 조건

`new PageImpl<>(content, pageable, total)` 을 쓰면 **항상** count 쿼리가 나갑니다.
`PageableExecutionUtils.getPage` 는 두 경우에 count 를 생략합니다.

| 상황 | count 실행 | 이유 |
|---|---|---|
| 첫 페이지이고 `content.size() < pageSize` | ❌ 생략 | 전체가 그만큼밖에 없음 → `total = content.size()` |
| 마지막 페이지 (`content.size() < pageSize`) | ❌ 생략 | `total = offset + content.size()` |
| 그 외 | ✅ 실행 | 계산 불가 |

**결과** — 조건 `status=PENDING, city=광주` (총 4건), `PageRequest.of(0, 10)`

```sql
select o1_0.order_id, o1_0.order_date, o1_0.status, o1_0.total_amount,
       c1_0.name, o1_0.shipping_city
from orders o1_0
join customers c1_0 on c1_0.customer_id=o1_0.customer_id
where o1_0.status=? and o1_0.shipping_city=?
order by o1_0.order_date desc
limit ?, ?
```
```
count 쿼리가 나가지 않았습니다.
첫 페이지에서 10건을 요청했는데 4건만 왔으므로 전체가 4건입니다.
Page: totalElements=4, totalPages=1
```

검색 결과가 적은 요청에서 쿼리가 절반으로 줄어듭니다.
검색 API 는 대부분 결과가 적으므로 실측 효과가 큽니다.

> ⚠️ **함정 — count 쿼리에서 조인을 빼면 결과가 틀립니다**
> "count 는 가벼워야 하니 조인을 빼자"는 최적화를 자주 시도합니다.
> `where` 절에 `customer.name` 이 있으면 **조인을 뺄 수 없습니다.**
> 조건에 참여하지 않는 조인만 뺄 수 있습니다.
> ```java
> // 콘텐츠 쿼리: 표시용으로 customer 를 조인
> // count 쿼리: where 에 customer 조건이 없다면 조인 제거 가능
> JPAQuery<Long> countQuery = queryFactory
>         .select(order.count())
>         .from(order)                       // join 없음
>         .where(statusEq(cond.status()));   // customer 조건이 없을 때만
> ```
> 조건에 따라 조인 필요 여부가 갈리면, **조인을 유지하는 쪽이 안전합니다.**
> 틀린 총 건수는 페이지 수가 어긋나 마지막 페이지가 빈 화면이 되는 식으로 드러납니다.

---

## 12-10. 테스트 — `@DataJpaTest` 에서 빈이 없습니다

리포지토리 테스트는 보통 `@DataJpaTest` 로 씁니다. 가볍고 롤백도 자동입니다.

```java
@DataJpaTest
class OrderRepositoryTest {

    @Autowired OrderRepository orderRepository;

    @Test
    void searchOrders() {
        Page<OrderSearchDto> page = orderRepository.searchOrders(
                new OrderSearchCond(null, OrderStatus.PAID, null, null),
                PageRequest.of(0, 10));
        assertThat(page.getContent()).isNotEmpty();
    }
}
```

**결과** — 실행하면 이렇게 됩니다.

```
org.springframework.beans.factory.UnsatisfiedDependencyException:
  Error creating bean with name 'orderRepository':
  Unsatisfied dependency expressed through constructor parameter 0

Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException:
  No qualifying bean of type 'com.querydsl.jpa.impl.JPAQueryFactory' available:
  expected at least 1 bean which qualifies as autowire candidate.
```

> ⚠️ **함정 — `@DataJpaTest` 는 `@Configuration` 을 안 읽습니다**
> `@DataJpaTest` 는 **슬라이스 테스트**입니다.
> JPA 관련 빈(`EntityManager`, `DataSource`, 리포지토리)만 올리고
> **일반 `@Component` / `@Configuration` 은 스캔하지 않습니다.**
> 그래서 우리가 만든 `QuerydslConfig` 가 로딩되지 않고,
> `JPAQueryFactory` 빈이 존재하지 않습니다.
> 애플리케이션은 정상 기동하는데 테스트만 실패하므로 원인을 찾는 데 시간이 걸립니다.

### 처방 ① — `@Import`

```java
@DataJpaTest
@Import(QuerydslConfig.class)              // ★ 설정 클래스를 명시적으로 올립니다
class OrderRepositoryTest {

    @Autowired OrderRepository orderRepository;
    // ...
}
```

이제 통과합니다. 가장 간단하고 명확한 방법입니다.

### 처방 ② — 테스트 전용 설정 클래스

여러 테스트에서 반복되면 공통 설정으로 뺍니다.

```java
@TestConfiguration
public class TestQuerydslConfig {
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }
}
```

```java
@DataJpaTest
@Import(TestQuerydslConfig.class)
class OrderRepositoryTest { }
```

### 처방 ③ — `@SpringBootTest`

전체 컨텍스트를 올리므로 아무 설정 없이 동작합니다.
대신 느립니다. 리포지토리만 테스트하는데 웹 서버까지 올릴 이유는 없습니다.

```java
@SpringBootTest
@Transactional
class OrderRepositoryTest { }
```

### 처방 비교

| 방법 | 컨텍스트 | 속도 | 권장도 |
|---|---|---|---|
| `@DataJpaTest` + `@Import(QuerydslConfig.class)` | JPA 슬라이스 | 빠름 | **권장** |
| `@DataJpaTest` + `@TestConfiguration` | JPA 슬라이스 | 빠름 | 설정이 갈릴 때 |
| `@SpringBootTest` | 전체 | 느림 | 통합 테스트용 |

> ⚠️ **함정 — `@DataJpaTest` 는 기본으로 임베디드 DB 를 씁니다**
> `@DataJpaTest` 는 `@AutoConfigureTestDatabase` 를 포함하고 있어서
> **클래스패스에 H2 가 있으면 실제 MySQL 대신 H2 를 씁니다.**
> 이 코스의 예제는 `shop` 데이터(고객 30명, 주문 600건)를 전제로 하므로
> H2 로 돌리면 전부 0건이 나옵니다.
> 실제 MySQL 을 쓰려면 대체를 꺼야 합니다.
> ```java
> @DataJpaTest
> @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
> @Import(QuerydslConfig.class)
> class OrderRepositoryTest { }
> ```
> "테스트가 통과하는데 결과가 전부 비어 있다"면 이것을 의심하십시오.

---

## 12-11. 정리 — 세 가지 통합 방식

| 항목 | 커스텀 리포지토리 | `QuerydslRepositorySupport` | `QuerydslPredicateExecutor` |
|---|---|---|---|
| 필요한 파일 | 3개 (+DTO) | 3개 (+DTO) | **0개** |
| 조인 제어 | 완전 | 완전 | ❌ 묵시적 조인만 |
| fetch join | ✅ | ✅ | ❌ |
| DTO 프로젝션 | ✅ | ✅ (`from` 뒤에 `select`) | ❌ |
| 서브쿼리·집계 | ✅ | ✅ | ❌ |
| 페이징 | 수동 (`offset`/`limit`) | `applyPagination` | ✅ 자동 |
| `Sort` → `orderBy` | 수동 (화이트리스트 가능) | 자동 (화이트리스트 없음) | 자동 |
| count 최적화 | `PageableExecutionUtils` | 예제는 deprecated `fetchCount` | 자동 (항상 실행) |
| 상속 슬롯 | 자유 | **소모** | 자유 |
| 계층 오염 | 없음 | 없음 | **`Predicate` 유출** |
| 주 실패 원인 | **`Impl` 접미사** | `EntityManager` 세터 주입 | 요구사항 확장 시 한계 |
| 실무 권장 | **기본** | 조건부 | 단순 CRUD 검색만 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| 커스텀 리포지토리 3층 | `~Repository` / `~RepositoryCustom` / `~RepositoryImpl` |
| 이름 규칙 | **`<리포지토리 인터페이스명>Impl`**. 프래그먼트명 + `Impl` 도 가능 |
| 실패 에러 | `QueryCreationException` + `No property 'xxx' found for type 'Entity'` |
| 에러의 인과 | 구현체 미발견 → **메서드 이름 쿼리로 오인** → 프로퍼티 파싱 실패 |
| 접미사 변경 | `@EnableJpaRepositories(repositoryImplementationPostfix = "...")` — 비권장 |
| 다른 실패 원인 | 패키지 범위 밖 / `implements` 누락 / 필드 주입 |
| 주입 | **생성자 주입**. `@RequiredArgsConstructor` |
| `QuerydslRepositorySupport` | `from` 으로 시작 강제, `applyPagination` 제공, 상속 슬롯 소모 |
| `applyPagination` | `Sort` → `OrderSpecifier` 자동 변환. **화이트리스트 없음** |
| `QuerydslPredicateExecutor` | 코드 0줄. 조인·fetch join·DTO **전부 불가** |
| `Predicate` 유출 | 서비스/컨트롤러가 QueryDSL 에 의존하게 됨 |
| `@QuerydslPredicate` | URL 쿼리스트링 = WHERE 절. **`cost` 같은 필드가 그대로 열림** |
| 화이트리스트 | `bindings.excludeUnlistedProperties(true)` + `including(...)` |
| 계층 원칙 | `Tuple` 과 `Predicate` 는 **리포지토리 밖으로 안 나감** |
| 조건 전달 | 평범한 record (`OrderSearchCond`) 로 |
| 페이징 | `PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne)` |
| count 생략 조건 | 첫 페이지에서 덜 채워졌거나 마지막 페이지일 때 |
| count 조인 | `where` 에 참여하는 조인은 **뺄 수 없음** |
| `@DataJpaTest` | `@Configuration` 미스캔 → `@Import(QuerydslConfig.class)` |
| `@DataJpaTest` DB | 기본 임베디드 대체 → `@AutoConfigureTestDatabase(replace = NONE)` |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `ProductRepository` / `ProductRepositoryCustom` / `ProductRepositoryImpl` 3층 구조를 만들고,
   상품명·상태·가격범위로 검색하는 `searchProducts(cond, pageable)` 를 구현하십시오.
   반환은 `Page<ProductSearchDto>` 이며 카테고리명이 포함돼야 합니다.
2. 1번의 구현 클래스 이름을 `ProductRepositoryQuerydsl` 로 바꾸고 애플리케이션을 띄우십시오.
   기동 로그의 예외 3단계(`BeanCreationException` → `QueryCreationException` → `PropertyReferenceException`)를
   그대로 적고, **왜 "No property found" 라는 메시지가 나오는지** 설명하십시오.
3. 2번을 `@EnableJpaRepositories(repositoryImplementationPostfix = ...)` 로 동작하게 만드십시오.
   그리고 이 설정을 실무에서 권장하지 않는 이유를 한 문단으로 쓰십시오.
4. 1번의 `searchProducts` 에 `PageableExecutionUtils` 를 적용하고,
   **count 쿼리가 나가는 경우와 안 나가는 경우**를 각각 재현해 SQL 로그로 확인하십시오.
5. `ProductRepository` 에 `QuerydslPredicateExecutor<Product>` 를 얹고
   `findAll(predicate, pageable)` 로 같은 검색을 시도하십시오.
   **카테고리명을 결과에 포함할 수 없는 이유**를 설명하고, 생성 SQL 로 근거를 제시하십시오.
6. `QuerydslBinderCustomizer` 를 구현해 `name`, `status`, `price` 만 필터링 가능하게 하고
   `cost` 를 차단하십시오. `?cost=120000` 요청이 WHERE 절에 나타나지 않는 것을 SQL 로그로 확인하십시오.
7. `@DataJpaTest` 로 1번 리포지토리를 테스트하십시오.
   먼저 `@Import` 없이 실행해 실패 메시지를 확인하고, 처방을 적용해 통과시키십시오.
   결과가 0건으로 나온다면 그 원인도 함께 해결하십시오.

---

## 다음 단계

이제 QueryDSL 이 애플리케이션 구조 안에 자리를 잡았습니다.
남은 것은 **표현력**입니다.

지금까지 쓴 조건과 프로젝션은 컬럼과 상수의 조합이었습니다.
실무 쿼리에는 조건부 분기(`CASE WHEN`), 상수 표현식, DB 고유 함수,
그리고 QueryDSL 이 직접 지원하지 않는 문법을 끼워 넣어야 하는 순간이 옵니다.

다음 스텝에서는 `CaseBuilder`, `Expressions`, `stringTemplate` 을 다루고,
그중 `stringTemplate` 에 사용자 입력을 문자열로 이어 붙였을 때
**QueryDSL 을 쓰고 있는데도 SQL 인젝션이 그대로 열리는** 경우를 재현합니다.
타입 안전성은 문자열 앞에서 멈춥니다.

→ [Step 13 — 고급 표현식](../step-13-advanced/)

---

## 실습 파일

이 스텝은 리포지토리 인터페이스와 구현체가 주인공이라 파일이 여러 개로 나뉩니다.
실습 파일에서는 **하나의 파일 안에 `static` 중첩 타입으로** 담고,
실제 프로젝트에서 어디에 두어야 하는지를 주석으로 표시했습니다.

**실제 프로젝트에서는 반드시 별도 파일로 분리하십시오.**
중첩 타입으로 두면 12-3 의 이름 규칙이 적용되는 방식이 달라져
이 스텝의 함정을 재현할 수 없습니다.
`src/main/java/com/example/shop/repository/` 에 파일 3개를 만들고,
테스트는 `src/test/java/com/example/shop/step12/` 에 두는 구성을 권장합니다.

### Practice.java

- `[12-2]` 커스텀 리포지토리 3층 구조 — 인터페이스 / 구현체 / 메인 리포지토리 전체 코드
- `[12-3]` `Impl` 접미사 규칙과 실패 케이스 4가지 (동작하지 않는 이름들을 주석으로 나열)
- `[12-4]` 생성자 주입, `EntityManager` 병용, 벌크 메서드의 트랜잭션 요구
- `[12-5]` `QuerydslRepositorySupport` 버전 구현과 `applyPagination`
- `[12-6]` `QuerydslPredicateExecutor` 사용과 4가지 한계 재현
- `[12-7]` `QuerydslBinderCustomizer` 화이트리스트
- `[12-9]` `PageableExecutionUtils` 로 count 생략 확인

```java file="./Practice.java"
```

### Exercise.java

7문제입니다. 2·3·5번은 **설명을 쓰는** 문제이므로 코드만 채우고 넘어가지 마십시오.
2번은 실제로 애플리케이션을 띄워 기동 실패를 봐야 합니다.

```java file="./Exercise.java"
```

### Solution.java

정답과 해설입니다.
특히 2번의 예외 인과 사슬은 실무에서 이 메시지를 만났을 때 바로 원인을 짚을 수 있도록
9단계로 나눠 주석에 적어 두었습니다.

```java file="./Solution.java"
```
