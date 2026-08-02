# Step 10 — 동적 정렬과 검색 조건 조립

> **학습 목표**
> - 클라이언트가 보낸 `?sort=price,desc` 문자열을 `OrderSpecifier` 로 변환하는 유틸을 만든다
> - `com.querydsl.core.types.Order` 와 도메인 `Order` 엔티티의 이름 충돌을 처리한다
> - `PathBuilder` 에 사용자 입력을 그대로 넣는 것이 왜 위험한지 정확히 서술한다
> - **화이트리스트 방식**으로 정렬 키를 안전하게 제한한다
> - 검색 조건을 `record` 로 객체화하고 `BooleanExpression` 반환 메서드로 분해한다
> - `null` 반환 메서드를 `.and()` 로 이어 NPE 를 내는 사고를 재현하고 null 안전 조립을 만든다
> - `contains` 가 만드는 앞 `%` LIKE 가 인덱스를 못 타는 것을 확인한다
> - 조건 + 정렬 + 페이징을 하나의 리포지토리 메서드로 통합한다
>
> **선행 스텝**: [Step 09 — 정렬과 페이징](../step-09-sorting-paging/)
> **예상 소요**: 110분

---

## 10-0. 실습 준비

이 스텝의 코드는 `Product` 를 주로 씁니다. Q타입은 **static import** 로 씁니다.

```java
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QCustomer.customer;
```

`products` 는 40건, `categories` 는 17건(대분류 5 + 소분류 12)입니다.
상품 상태는 `ON_SALE` / `SOLD_OUT` / `HIDDEN` 세 가지입니다.

이 스텝은 **문자열이 쿼리로 바뀌는 경계**를 다룹니다.
Step 09 까지의 모든 쿼리는 컴파일 시점에 모양이 확정돼 있었습니다.
여기서부터는 **런타임에 사용자가 준 값이 쿼리의 구조 자체를 바꿉니다.**
타입 안전성이 끝나는 지점이 정확히 어디인지 확인하는 것이 이 스텝의 목표입니다.

---

## 10-1. 문제 정의 — 문자열을 어떻게 정렬로 바꾸나

상품 목록 API 를 만듭니다. 클라이언트는 이렇게 호출합니다.

```
GET /api/products?keyword=노트북&sort=price,desc&page=0&size=20
GET /api/products?categoryId=3&sort=createdAt,desc&page=0&size=20
GET /api/products?sort=stock,asc&page=1&size=20
```

Spring MVC 는 `sort=price,desc` 를 `Sort` 객체로 바인딩해 `Pageable` 에 넣어 줍니다.

```java
@GetMapping("/api/products")
public Page<ProductDto> list(ProductSearchCond cond, Pageable pageable) {
    return productRepository.search(cond, pageable);
}
```

`pageable.getSort()` 를 찍어 보면 이렇게 들어 있습니다.

**결과**
```
pageable.getSort() = price: DESC
pageable.getSort().iterator() → Sort.Order{ property='price', direction=DESC, nullHandling=NATIVE }
```

Spring Data JPA 의 기본 리포지토리라면 이 `Sort` 가 자동으로 `order by` 로 변환됩니다.
**그러나 QueryDSL 은 그렇지 않습니다.**

```java
// pageable 을 넘겼는데 정렬이 안 된다
List<Product> content = queryFactory
        .selectFrom(product)
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
limit ?, ?
```
```
바인딩: [1] 0  [2] 20
order by 절이 없습니다. sort=price,desc 는 완전히 무시됐습니다.
```

**에러가 나지 않습니다.** 그냥 정렬이 안 될 뿐입니다.
클라이언트는 정렬을 요청했다고 믿고, 서버는 아무것도 하지 않았습니다.

> 📌 [Step 09](../step-09-sorting-paging/) 의 9-4 에서 예고한 문제입니다.
> `pageable.getOffset()` 과 `getPageSize()` 는 그대로 쓸 수 있지만
> `getSort()` 는 **직접 변환해야 합니다.** 그 변환기를 만드는 것이 이 스텝의 앞부분입니다.

`orderBy` 의 시그니처를 보면 이유가 명확합니다.

```java
JPAQuery<T> orderBy(OrderSpecifier<?>... o);
```

`orderBy` 는 **`OrderSpecifier` 만 받습니다.** `String` 도 `Sort` 도 받지 않습니다.
이것이 QueryDSL 의 타입 안전성이며, 동시에 우리가 다리를 놓아야 하는 이유입니다.

---

## 10-2. `OrderSpecifier` 의 구조

`OrderSpecifier` 는 **방향 + 표현식** 두 가지로 이루어져 있습니다.

```java
public OrderSpecifier(Order order, Expression<T> target)
```

`customer.points.desc()` 가 하는 일은 정확히 이것입니다.

```java
// 아래 둘은 완전히 같습니다
OrderSpecifier<Integer> a = customer.points.desc();
OrderSpecifier<Integer> b = new OrderSpecifier<>(com.querydsl.core.types.Order.DESC, customer.points);
```

둘 다 같은 SQL 을 만듭니다.

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.points desc
```

즉 **방향을 런타임에 결정할 수 있습니다.** 이것이 동적 정렬의 출발점입니다.

```java
com.querydsl.core.types.Order direction = ascending
        ? com.querydsl.core.types.Order.ASC
        : com.querydsl.core.types.Order.DESC;

OrderSpecifier<Integer> spec = new OrderSpecifier<>(direction, customer.points);
```

### ⚠️ 이름 충돌 — `Order` 가 두 개입니다

**이 코스의 도메인에는 `Order` 엔티티가 있습니다.** 그리고 QueryDSL 의 정렬 방향 enum 도 `Order` 입니다.

| 클래스 | 정체 |
|---|---|
| `com.example.shop.entity.Order` | 주문 엔티티. 600건짜리 그 테이블 |
| `com.querydsl.core.types.Order` | `ASC` / `DESC` 두 상수만 있는 enum |
| `org.springframework.data.domain.Sort.Order` | Spring Data 의 정렬 항목 (property + direction) |

**세 개입니다.** 그리고 이 스텝의 코드는 셋 다 씁니다.

```java
import com.example.shop.entity.Order;      // 주문 엔티티
import com.querydsl.core.types.Order;      // ❌ 컴파일 에러 — 같은 이름은 하나만 import 가능
```

```
java: com.querydsl.core.types.Order is already defined in this compilation unit
```

컴파일 에러가 나므로 **모르고 지나칠 수는 없습니다.** 다만 해결 방식을 정해 둬야 합니다.

**처방 — 도메인 쪽을 import 하고 QueryDSL 쪽을 FQN 으로 씁니다.**

```java
import com.example.shop.entity.Order;                       // 도메인은 import
// com.querydsl.core.types.Order 는 import 하지 않는다

OrderSpecifier<?> spec = new OrderSpecifier<>(
        com.querydsl.core.types.Order.DESC,                 // FQN
        product.price);
```

도메인 엔티티가 코드에 훨씬 자주 등장하므로 그쪽을 짧게 쓰는 편이 읽기 좋습니다.

**대안 — 정렬 유틸 클래스에는 도메인 `Order` 를 아예 쓰지 않습니다.**

정렬 변환기는 순수 유틸이므로 `Order` 엔티티를 참조할 일이 없습니다.
그렇다면 그 파일 안에서는 QueryDSL 쪽을 import 해도 충돌이 없습니다.

```java
package com.example.shop.support;

import com.querydsl.core.types.Order;          // 이 파일에는 도메인 Order 가 없다
import com.querydsl.core.types.OrderSpecifier;

public final class QuerydslSortUtils {
    // Order.ASC / Order.DESC 를 짧게 쓸 수 있다
}
```

> 💡 **실무 팁 — 클래스 이름 충돌은 "파일 단위로 격리" 하는 것이 정석입니다**
> `import` 는 파일 단위입니다. 충돌하는 두 타입이 **같은 파일에 있을 때만** 문제가 됩니다.
> 정렬 유틸을 별도 클래스로 분리하면 충돌 자체가 사라집니다.
> FQN 을 여기저기 흩뿌리는 것보다 훨씬 깔끔합니다.
> 이 원칙은 `java.sql.Date` vs `java.util.Date`, `org.springframework...Order` 애노테이션 등
> 자바에서 반복적으로 마주치는 문제에 그대로 적용됩니다.

### `Sort.Direction` → `com.querydsl.core.types.Order` 변환

Spring 의 `Sort.Direction` 도 `ASC` / `DESC` 두 상수입니다. 매핑은 자명합니다.

```java
private static Order toQuerydslOrder(Sort.Direction direction) {
    return direction.isAscending() ? Order.ASC : Order.DESC;
}
```

---

## 10-3. `Sort` → `OrderSpecifier[]` 변환기

두 가지 방식이 있습니다. 먼저 널리 쓰이는 `PathBuilder` 방식부터 봅니다.

### 방식 A — `PathBuilder` 로 문자열 경로 해석

```java
package com.example.shop.support;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.PathBuilder;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

public final class PathBuilderSortUtils {

    private PathBuilderSortUtils() {}

    public static OrderSpecifier<?>[] toOrderSpecifiers(Sort sort, Class<?> entityType, String alias) {
        List<OrderSpecifier<?>> result = new ArrayList<>();

        for (Sort.Order o : sort) {
            Order direction = o.isAscending() ? Order.ASC : Order.DESC;
            PathBuilder<?> path = new PathBuilder<>(entityType, alias);
            result.add(new OrderSpecifier(direction, path.get(o.getProperty())));
        }
        return result.toArray(new OrderSpecifier[0]);
    }
}
```

`PathBuilder` 는 [Step 02](../step-02-qtype/) 에서 다룬 그것입니다.
**엔티티 타입과 별칭을 주면, 문자열로 경로를 만들어 주는 동적 경로 빌더**입니다.

사용하면 이렇게 됩니다.

```java
Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "price"));

List<Product> content = queryFactory
        .selectFrom(product)
        .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                pageable.getSort(), Product.class, "product"))
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.price desc
limit ?, ?
```
```
바인딩: [1] 0  [2] 20
조회 20건
1. 게이밍 노트북 RTX4060   2,190,000
2. 보급형 노트북 15          690,000
3. 27인치 4K 모니터          459,000
4. 원목 4인 식탁             459,000
5. 인체공학 사무용 의자       329,000
...
```

동작합니다. **그리고 이것이 문제입니다.** 10-4 에서 다룹니다.

> ⚠️ **`alias` 는 반드시 Q타입의 변수명과 일치해야 합니다**
> `new PathBuilder<>(Product.class, "product")` 의 `"product"` 는
> `QProduct.product` 의 별칭과 같아야 합니다. 다르면 JPQL 에 존재하지 않는 별칭이 들어가
> **`from` 절에 없는 별칭을 참조한다**는 파싱 에러가 납니다.
> `product.getMetadata().getName()` 으로 별칭을 꺼내 쓰면 하드코딩을 피할 수 있습니다.

### 방식 B — 화이트리스트 `Map`

```java
package com.example.shop.support;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WhitelistSortUtils {

    private WhitelistSortUtils() {}

    public static OrderSpecifier<?>[] toOrderSpecifiers(
            Sort sort,
            Map<String, ComparableExpressionBase<?>> allowed,
            OrderSpecifier<?> defaultOrder) {

        List<OrderSpecifier<?>> result = new ArrayList<>();

        for (Sort.Order o : sort) {
            ComparableExpressionBase<?> expr = allowed.get(o.getProperty());
            if (expr == null) {
                continue;                       // 허용 목록에 없으면 무시
            }
            result.add(o.isAscending() ? expr.asc() : expr.desc());
        }

        if (result.isEmpty()) {
            result.add(defaultOrder);           // 아무것도 없으면 기본 정렬
        }
        return result.toArray(new OrderSpecifier[0]);
    }
}
```

허용 목록은 리포지토리가 선언합니다.

```java
private static final Map<String, ComparableExpressionBase<?>> SORTABLE = Map.of(
        "price",     product.price,
        "stock",     product.stock,
        "createdAt", product.createdAt,
        "name",      product.name
);

private static final OrderSpecifier<?> DEFAULT_ORDER = product.productId.desc();
```

사용은 동일합니다.

```java
List<Product> content = queryFactory
        .selectFrom(product)
        .orderBy(WhitelistSortUtils.toOrderSpecifiers(
                pageable.getSort(), SORTABLE, DEFAULT_ORDER))
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
```

**결과** — `sort=price,desc`
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.price desc
limit ?, ?
```

**결과** — `sort=cost,desc` (허용 목록에 없음)
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.product_id desc
limit ?, ?
```
```
"cost" 는 허용 목록에 없으므로 무시되고 기본 정렬(product_id desc)로 대체됐습니다.
예외가 나지 않았고, 원가(cost)가 외부에 노출되지도 않았습니다.
```

두 방식의 차이는 다음 절에서 명확해집니다.

| 항목 | 방식 A (`PathBuilder`) | 방식 B (화이트리스트) |
|---|---|---|
| 코드량 | 적음 | 조금 더 많음 (Map 선언) |
| 새 정렬 키 추가 | 자동 (엔티티 필드면 다 됨) | Map 에 한 줄 추가 |
| 없는 필드 요청 | **런타임 예외 → 500** | 무시 (또는 400 반환) |
| 노출 범위 | **엔티티의 모든 필드** | 등록한 것만 |
| 연관 필드 정렬 | `"category.name"` 도 됨(주의 필요) | 명시적으로 등록해야 함 |
| 타입 안전성 | 없음 (raw 캐스팅) | 있음 |

---

## 10-4. ⚠️ `PathBuilder` + 사용자 입력의 위험

이 스텝의 핵심 함정입니다. **결론부터 정확히 말합니다.**

> `PathBuilder` 에 사용자 입력을 넣으면 **① 확실히 500 에러가 열리고**,
> **② 의도하지 않은 필드가 노출되며**,
> **③ 고전적 SQL 인젝션은 대체로 막히지만 그것은 QueryDSL 덕분이 아니라 JPQL 파서 덕분이고,
> 문자열 조립 경로와 결합하면 그 방어도 사라집니다.**

하나씩 확인합니다.

### ① 존재하지 않는 필드 → 런타임 예외

```java
// 클라이언트: ?sort=nonExistentField,desc
Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "nonExistentField"));

List<Product> content = queryFactory
        .selectFrom(product)
        .orderBy(PathBuilderSortUtils.toOrderSpecifiers(
                pageable.getSort(), Product.class, "product"))
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
```

**결과**
```
java.lang.IllegalArgumentException: org.hibernate.query.SemanticException:
    Could not interpret path expression 'product.nonExistentField'
	at com.querydsl.jpa.impl.AbstractJPAQuery.fetch(AbstractJPAQuery.java:...)
	...
```

`PathBuilder` 자체는 **아무 검증도 하지 않습니다.** 어떤 문자열이든 경로로 만들어 줍니다.
검증은 나중에, JPQL 을 파싱하는 Hibernate 가 합니다.
즉 **컴파일 시점에도, 쿼리 조립 시점에도 아무 일이 없다가, 실행 시점에 터집니다.**

이것만으로도 충분히 나쁩니다. 공격이랄 것도 없이 `?sort=x,desc` 하나로 500 이 납니다.
장애 알림이 울리고, 로그에 스택트레이스가 쌓이고, 심하면 그 자체가 서비스 거부입니다.

### ② 의도하지 않은 필드 노출

`Product` 에는 `cost`(원가) 필드가 있습니다. API 응답 DTO 에는 당연히 없습니다.
그런데 `PathBuilder` 방식은 **정렬 키로는 받아 줍니다.**

```java
// 클라이언트: ?sort=cost,desc
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.cost desc
limit ?, ?
```
```
조회 20건 — 원가 내림차순으로 정렬됐습니다.
```

응답 본문에 `cost` 값은 없습니다. 그러나 **순서가 곧 정보입니다.**
`sort=cost,asc` 와 `sort=cost,desc` 를 번갈아 호출하면
원가 순위를 완전히 복원할 수 있습니다. 값을 몰라도 순위는 압니다.
`price` 를 알고 있으니 마진 구조를 역산할 수도 있습니다.

이것은 버그가 아니라 **설계상 열려 있는 문**입니다. 아무 에러도 나지 않습니다.

### ③ SQL 인젝션 — 정확한 서술

여기서 흔한 오해를 정리합니다.

**"QueryDSL 은 파라미터 바인딩을 쓰니까 SQL 인젝션이 안 된다"** — 부분적으로만 맞습니다.

`where(product.name.eq(userInput))` 같은 **값**은 항상 `?` 로 바인딩됩니다. 이건 안전합니다.
`PathBuilder.get(userInput)` 은 **값이 아니라 식별자(경로)** 를 만듭니다.
식별자는 바인딩 대상이 아니라 JPQL 텍스트에 그대로 들어갑니다.

그렇다면 `?sort=price; DROP TABLE products--,desc` 는 통할까요?

```java
new PathBuilder<>(Product.class, "product").get("price; DROP TABLE products--")
```

**결과**
```
org.hibernate.query.SemanticException:
    Could not interpret path expression 'product.price; DROP TABLE products--'
```

**막혔습니다.** 이유는 QueryDSL 이 검사해서가 아니라,
**QueryDSL-JPA 가 만들어 내는 것이 SQL 이 아니라 JPQL 이고, Hibernate 의 JPQL 파서가
그 문자열을 유효한 경로 표현식으로 해석하지 못했기 때문**입니다.
파서가 방어벽 역할을 우연히 해 준 것입니다.

**그러나 이 방어에 기대면 안 됩니다.** 두 가지 이유가 있습니다.

**첫째, 방어의 근거가 우리 코드가 아닙니다.**
Hibernate 파서의 관대함이 버전에 따라 달라지면 그 방어도 달라집니다.
"현재 버전에서 막히더라"는 것은 보안 근거가 아닙니다.

**둘째, 문자열 조립 경로와 결합하면 실제로 열립니다.**
QueryDSL 에는 JPQL/SQL 조각을 문자열로 직접 만드는 통로가 있습니다.

```java
// Step 13 에서 다루는 Expressions.stringTemplate
// 사용자 입력을 문자열로 이어 붙이면 파서 방어가 통하지 않습니다
Expressions.stringTemplate("function('concat', {0}, '" + userInput + "')", product.name);
```

여기서는 `userInput` 이 **템플릿 문자열 자체의 일부**가 됩니다.
`Expressions.numberTemplate`, `Expressions.booleanTemplate`, 네이티브 쿼리 조합도 마찬가지입니다.
`PathBuilder` 로 "사용자 문자열을 쿼리 구조에 넣는" 습관이 자리잡으면,
그 습관은 `stringTemplate` 을 쓰는 순간 그대로 취약점이 됩니다.

> ⚠️ **"QueryDSL 이라서 안전하다" 는 잘못된 안심**
> 안전한 것은 **값 바인딩**입니다. 구조(테이블·컬럼·경로·정렬 방향)는 바인딩 대상이 아닙니다.
> 어떤 쿼리 도구를 쓰든, **사용자 입력이 쿼리의 구조를 결정하게 하면 안 됩니다.**
> 이 원칙은 QueryDSL 이든 MyBatis 든 JdbcTemplate 이든 동일합니다.
> QueryDSL 은 이 실수를 **더 어렵게** 만들어 주지만 **불가능하게** 만들지는 않습니다.
> Step 13 의 `stringTemplate` 절에서 실제로 인젝션이 성립하는 예를 봅니다.

### 처방 — 화이트리스트

**허용된 정렬 키만 등록하고, 없는 키는 기본 정렬로 대체하거나 400 을 반환합니다.**

```java
package com.example.shop.support;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정렬 키 화이트리스트.
 * 등록되지 않은 키는 절대 쿼리에 들어가지 않습니다.
 */
public final class SortWhitelist {

    private final Map<String, ComparableExpressionBase<?>> allowed = new LinkedHashMap<>();
    private final OrderSpecifier<?> defaultOrder;
    private final boolean rejectUnknown;

    private SortWhitelist(OrderSpecifier<?> defaultOrder, boolean rejectUnknown) {
        this.defaultOrder = defaultOrder;
        this.rejectUnknown = rejectUnknown;
    }

    /** 모르는 키는 조용히 무시하고 기본 정렬로 대체합니다. */
    public static SortWhitelist lenient(OrderSpecifier<?> defaultOrder) {
        return new SortWhitelist(defaultOrder, false);
    }

    /** 모르는 키가 오면 IllegalArgumentException 을 던집니다 (→ 400 으로 매핑). */
    public static SortWhitelist strict(OrderSpecifier<?> defaultOrder) {
        return new SortWhitelist(defaultOrder, true);
    }

    public SortWhitelist add(String key, ComparableExpressionBase<?> expression) {
        allowed.put(key, expression);
        return this;
    }

    public OrderSpecifier<?>[] resolve(Sort sort) {
        List<OrderSpecifier<?>> result = new ArrayList<>();

        for (Sort.Order o : sort) {
            ComparableExpressionBase<?> expr = allowed.get(o.getProperty());
            if (expr == null) {
                if (rejectUnknown) {
                    throw new IllegalArgumentException(
                            "정렬할 수 없는 항목입니다: " + o.getProperty()
                                    + " (허용: " + allowed.keySet() + ")");
                }
                continue;
            }
            result.add(o.isAscending() ? expr.asc() : expr.desc());
        }

        // ★ 타이브레이커 — Step 09 의 9-9
        //   기본 정렬(PK)을 항상 마지막에 덧붙여 전순서를 확정합니다.
        result.add(defaultOrder);

        return result.toArray(new OrderSpecifier[0]);
    }
}
```

리포지토리에서 선언합니다.

```java
private static final SortWhitelist PRODUCT_SORT = SortWhitelist
        .lenient(product.productId.desc())          // 기본 정렬 겸 타이브레이커
        .add("price",     product.price)
        .add("stock",     product.stock)
        .add("createdAt", product.createdAt)
        .add("name",      product.name);
```

**결과** — `sort=price,desc`
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.price desc, p1_0.product_id desc
limit ?, ?
```

`product_id desc` 가 자동으로 붙었습니다. **Step 09 의 타이브레이커 규칙이 유틸에 내장된 것**입니다.

**결과** — `sort=cost,desc` (미등록)
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.product_id desc
limit ?, ?
```

**결과** — `strict` 모드에서 `sort=cost,desc`
```
java.lang.IllegalArgumentException: 정렬할 수 없는 항목입니다: cost (허용: [price, stock, createdAt, name])
→ @ExceptionHandler 에서 400 Bad Request 로 매핑
```

> 💡 **`lenient` 와 `strict` 중 무엇을 쓸까**
> - 공개 API 라면 `strict` 가 낫습니다. 클라이언트가 오타를 즉시 알 수 있고,
>   "정렬을 요청했는데 조용히 무시되는" 상황이 사라집니다.
> - 내부 관리 화면이라면 `lenient` 가 무난합니다. 프론트가 예전 정렬 키를 보내도 화면은 뜹니다.
> 어느 쪽이든 **등록되지 않은 키가 쿼리에 들어가지 않는다**는 성질은 동일합니다. 그것이 핵심입니다.

---

## 10-5. 검색 조건 객체화 — `record`

정렬을 정리했으니 조건 차례입니다.
파라미터가 여섯 개를 넘어가면 메서드 시그니처가 감당하지 못합니다.

```java
// 이런 시그니처는 유지보수가 불가능합니다
public Page<Product> search(String keyword, Long categoryId, BigDecimal minPrice,
                            BigDecimal maxPrice, ProductStatus status,
                            Boolean inStockOnly, Pageable pageable) { ... }
```

Java 21 의 `record` 로 묶습니다.

```java
package com.example.shop.dto;

import com.example.shop.entity.ProductStatus;

import java.math.BigDecimal;

public record ProductSearchCond(
        String keyword,             // 상품명 검색어. null 또는 빈 문자열이면 조건 없음
        Long categoryId,            // 카테고리 ID. null 이면 전체
        BigDecimal minPrice,        // 가격 하한 (포함)
        BigDecimal maxPrice,        // 가격 상한 (포함)
        ProductStatus status,       // ON_SALE / SOLD_OUT / HIDDEN
        Boolean inStockOnly         // true 면 stock > 0 인 것만
) {
    /** 모든 조건이 비어 있는 기본 조건 */
    public static ProductSearchCond empty() {
        return new ProductSearchCond(null, null, null, null, null, null);
    }
}
```

`record` 는 생성자·`equals`·`hashCode`·`toString` 을 자동으로 만들어 줍니다.
검색 조건처럼 **값이 전부이고 동작이 없는 객체**에 정확히 맞습니다.

Spring MVC 는 `record` 도 쿼리 파라미터로 바인딩합니다.

```java
@GetMapping("/api/products")
public Page<ProductDto> list(ProductSearchCond cond, Pageable pageable) {
    return productRepository.search(cond, pageable);
}
```

**결과** — `GET /api/products?keyword=노트북&minPrice=500000&status=ON_SALE`
```
cond = ProductSearchCond[keyword=노트북, categoryId=null, minPrice=500000,
                         maxPrice=null, status=ON_SALE, inStockOnly=null]
```

**지정하지 않은 필드는 `null` 입니다.** 이 `null` 이 곧 "조건 없음"이 되며,
QueryDSL 의 `where(null)` 무시 성질과 정확히 맞물립니다.

> 💡 `record` 의 필드는 불변입니다. 조건 객체를 서비스 계층에서 몰래 수정하는 일이 원천적으로 막힙니다.
> 값을 바꿔야 한다면 새 인스턴스를 만드십시오. `record` 에 `withKeyword(String)` 같은
> 복사 메서드를 직접 추가하는 것이 관례입니다.

---

## 10-6. 조건 메서드 모음

각 조건을 **`BooleanExpression` 을 반환하는 private 메서드**로 분리합니다.
값이 없으면 **`null` 을 반환**합니다.

```java
private BooleanExpression keywordContains(String keyword) {
    return (keyword == null || keyword.isBlank()) ? null : product.name.contains(keyword);
}

private BooleanExpression categoryEq(Long categoryId) {
    return categoryId == null ? null : product.category.categoryId.eq(categoryId);
}

private BooleanExpression priceGoe(BigDecimal minPrice) {
    return minPrice == null ? null : product.price.goe(minPrice);
}

private BooleanExpression priceLoe(BigDecimal maxPrice) {
    return maxPrice == null ? null : product.price.loe(maxPrice);
}

private BooleanExpression statusEq(ProductStatus status) {
    return status == null ? null : product.status.eq(status);
}

private BooleanExpression inStock(Boolean inStockOnly) {
    return Boolean.TRUE.equals(inStockOnly) ? product.stock.gt(0) : null;
}
```

`where` 에 나란히 넘깁니다.

```java
List<Product> result = queryFactory
        .selectFrom(product)
        .where(
                keywordContains(cond.keyword()),
                categoryEq(cond.categoryId()),
                priceGoe(cond.minPrice()),
                priceLoe(cond.maxPrice()),
                statusEq(cond.status()),
                inStock(cond.inStockOnly())
        )
        .orderBy(product.productId.desc())
        .fetch();
```

> 📌 `where(a, b, c)` 는 **인자들을 `AND` 로 묶고, `null` 인 인자는 무시**합니다.
> [Step 04](../step-04-where-conditions/) 의 4-3, 4-4 에서 확인했습니다.
> 이 성질이 동적 쿼리의 토대입니다.

조건 조합에 따라 SQL 이 어떻게 달라지는지 네 가지를 봅니다.

### 조합 1 — 조건 없음

```java
ProductSearchCond cond = ProductSearchCond.empty();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
order by p1_0.product_id desc
```
```
where 절이 아예 없습니다. 조회 40건 (전체)
```

`where(null, null, null, null, null, null)` 은 `where 1=1` 도, 빈 `where` 도 만들지 않습니다.
**절 자체가 사라집니다.**

### 조합 2 — 상태만

```java
ProductSearchCond cond = new ProductSearchCond(
        null, null, null, null, ProductStatus.ON_SALE, null);
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.status = ?
order by p1_0.product_id desc
```
```
바인딩: [1] ON_SALE
조회 31건
```

### 조합 3 — 가격 범위 + 재고

```java
ProductSearchCond cond = new ProductSearchCond(
        null, null, new BigDecimal("100000"), new BigDecimal("500000"), null, true);
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.price >= ? and p1_0.price <= ? and p1_0.stock > ?
order by p1_0.product_id desc
```
```
바인딩: [1] 100000  [2] 500000  [3] 0
조회 9건 — 27인치 4K 모니터, 원목 4인 식탁, 인체공학 사무용 의자 ...
```

세 조건이 `and` 로 이어졌습니다. **괄호가 없습니다.** `and` 만 있으므로 괄호가 필요 없습니다.

### 조합 4 — 전부

```java
ProductSearchCond cond = new ProductSearchCond(
        "노트북", 7L, new BigDecimal("500000"), new BigDecimal("2500000"),
        ProductStatus.ON_SALE, true);
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.name like ? escape '!'
  and p1_0.category_id = ?
  and p1_0.price >= ?
  and p1_0.price <= ?
  and p1_0.status = ?
  and p1_0.stock > ?
order by p1_0.product_id desc
```
```
바인딩: [1] %노트북%  [2] 7  [3] 500000  [4] 2500000  [5] ON_SALE  [6] 0
조회 2건 — 게이밍 노트북 RTX4060(2,190,000), 보급형 노트북 15(690,000)
```

**`escape '!'` 에 주목하십시오.** `contains()` 는 `%` 를 앞뒤로 붙이면서
사용자 입력 안의 `%` 와 `_` 를 이스케이프하기 위해 escape 문자를 지정합니다.
즉 사용자가 `100%` 를 검색해도 `%` 가 와일드카드로 해석되지 않습니다.
**LIKE 와일드카드 인젝션은 QueryDSL 이 막아 줍니다.** 이건 값 바인딩 영역이기 때문입니다.

`category_id = ?` 도 눈여겨보십시오. `product.category.categoryId` 는 **조인을 만들지 않습니다.**
FK 컬럼이 `products` 테이블에 있으므로 `p1_0.category_id` 로 직접 비교합니다.
`product.category.name` 이었다면 `join categories` 가 생겼을 것입니다.

---

## 10-7. 조건 재사용 — `BooleanExpression` 합성

`BooleanExpression` 을 반환하는 메서드들의 진짜 이점은 **서로 합성된다**는 점입니다.

```java
/** 판매 가능한 상품 = 판매중이면서 재고가 있는 */
private BooleanExpression purchasable() {
    return product.status.eq(ProductStatus.ON_SALE).and(product.stock.gt(0));
}

/** 프리미엄 = 판매 가능하면서 50만원 이상 */
private BooleanExpression premiumPurchasable() {
    return purchasable().and(product.price.goe(new BigDecimal("500000")));
}
```

```java
List<Product> result = queryFactory
        .selectFrom(product)
        .where(premiumPurchasable())
        .orderBy(product.price.desc(), product.productId.desc())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.status = ? and p1_0.stock > ? and p1_0.price >= ?
order by p1_0.price desc, p1_0.product_id desc
```
```
바인딩: [1] ON_SALE  [2] 0  [3] 500000
조회 6건 — 게이밍 노트북 RTX4060, 보급형 노트북 15, 27인치 4K 모니터 ...
```

`purchasable()` 이라는 **비즈니스 개념 하나**를 정의해 두고,
다른 조건과 조합해 재사용했습니다. 이름이 붙었으므로 의도가 코드에 드러납니다.

### `BooleanBuilder` 와 비교

같은 것을 `BooleanBuilder` 로 쓰면 이렇게 됩니다.

```java
BooleanBuilder builder = new BooleanBuilder();
if (cond.keyword() != null && !cond.keyword().isBlank()) {
    builder.and(product.name.contains(cond.keyword()));
}
if (cond.categoryId() != null) {
    builder.and(product.category.categoryId.eq(cond.categoryId()));
}
if (cond.minPrice() != null) {
    builder.and(product.price.goe(cond.minPrice()));
}
if (cond.maxPrice() != null) {
    builder.and(product.price.loe(cond.maxPrice()));
}
if (cond.status() != null) {
    builder.and(product.status.eq(cond.status()));
}
if (Boolean.TRUE.equals(cond.inStockOnly())) {
    builder.and(product.stock.gt(0));
}

List<Product> result = queryFactory.selectFrom(product).where(builder).fetch();
```

생성 SQL 은 **완전히 같습니다.** 차이는 코드의 성질입니다.

| 항목 | `BooleanBuilder` | `BooleanExpression` 메서드 |
|---|---|---|
| null 처리 | `if` 로 감싸야 함 | 메서드가 `null` 반환, `where` 가 무시 |
| 재사용 | 불가 (조립 과정이 메서드 안에 갇힘) | **가능** — 다른 쿼리에서 그대로 호출 |
| 합성 | 불가 | **가능** — `.and()`, `.or()` 로 조합 |
| 이름 붙이기 | 불가 | **가능** — `purchasable()` 같은 도메인 언어 |
| 테스트 | 쿼리를 실행해야 함 | 메서드 단위로 SQL 조각 확인 가능 |
| 조건 개수가 아주 많을 때 | 무난 | 메서드가 많아져 파일이 길어짐 |

> 📌 [Step 04](../step-04-where-conditions/) 의 4-6 에서 두 방식을 처음 비교했습니다.
> 거기서는 "둘 다 된다" 였고, 여기서는 **"합성과 명명 때문에 `BooleanExpression` 이 낫다"** 입니다.
> 이 절의 `purchasable()` 이 그 이유의 전부입니다.

> 💡 **실무 팁 — 두 방식을 섞어도 됩니다**
> `BooleanExpression` 메서드로 조건 조각을 만들고, 복잡한 `or` 그룹만 `BooleanBuilder` 로 묶는 식입니다.
> `where(purchasable(), builder)` 처럼 나란히 넘길 수 있습니다.
> 중요한 것은 방식의 통일이 아니라, **어떤 SQL 이 나가는지 아는 것**입니다.

---

## 10-8. ⚠️ `null` 반환 메서드를 `.and()` 로 이으면 NPE

10-6 과 10-7 을 합치면 함정이 생깁니다.

**10-6 의 메서드들은 `null` 을 반환할 수 있습니다.**
**10-7 은 그 메서드들을 `.and()` 로 잇습니다.**

두 규칙이 만나면 `null.and(...)` 가 됩니다.

### 재현

```java
private BooleanExpression statusEq(ProductStatus status) {
    return status == null ? null : product.status.eq(status);
}

private BooleanExpression priceGoe(BigDecimal minPrice) {
    return minPrice == null ? null : product.price.goe(minPrice);
}

// 두 조건을 합성 — 그럴듯해 보입니다
private BooleanExpression statusAndPrice(ProductStatus status, BigDecimal minPrice) {
    return statusEq(status).and(priceGoe(minPrice));    // ❌
}
```

```java
List<Product> result = queryFactory
        .selectFrom(product)
        .where(statusAndPrice(null, new BigDecimal("500000")))   // status 가 null
        .fetch();
```

**결과**
```
java.lang.NullPointerException: Cannot invoke
    "com.querydsl.core.types.dsl.BooleanExpression.and(com.querydsl.core.types.Predicate)"
    because the return value of "com.example.shop.repository.ProductRepositoryImpl.statusEq(...)"
    is null
	at com.example.shop.repository.ProductRepositoryImpl.statusAndPrice(ProductRepositoryImpl.java:...)
```

`statusEq(null)` 이 `null` 을 반환했고, 그 `null` 에 `.and(...)` 를 호출했습니다.

**반대 순서라면 통과합니다.**

```java
statusEq(ProductStatus.ON_SALE).and(priceGoe(null))
```

**결과**
```sql
select p1_0.product_id, ... from products p1_0
where p1_0.status = ?
```
```
바인딩: [1] ON_SALE
조회 31건 — 예외 없음
```

`.and(null)` 은 QueryDSL 이 **인자로 받은 null 을 무시**하므로 안전합니다.
**앞이 null 이면 죽고, 뒤가 null 이면 괜찮습니다.**

이 비대칭이 함정의 본질입니다.
개발 중에는 조건을 다 채워서 테스트하므로 통과하고,
운영에서 사용자가 조건 하나를 비우는 순간 500 이 납니다.
그것도 **어떤 조건을 비우느냐에 따라** 나기도 하고 안 나기도 합니다.

### 처방 1 — `Expressions.allOf` / `anyOf`

QueryDSL 의 `Expressions.allOf(...)` 와 `anyOf(...)` 는 **`null` 인자를 걸러냅니다.**

```java
private BooleanExpression statusAndPrice(ProductStatus status, BigDecimal minPrice) {
    return Expressions.allOf(statusEq(status), priceGoe(minPrice));
}
```

```java
List<Product> result = queryFactory
        .selectFrom(product)
        .where(statusAndPrice(null, new BigDecimal("500000")))
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.price >= ?
```
```
바인딩: [1] 500000
조회 8건 — 예외 없음. status 조건만 사라졌습니다.
```

**모든 인자가 `null` 이면 `allOf` 자체가 `null` 을 반환합니다.**

```java
Expressions.allOf(statusEq(null), priceGoe(null))    // → null
```

그리고 그 `null` 을 `where` 에 넘기면 조건 없이 나갑니다. 일관됩니다.

> ⚠️ **`allOf` 가 `null` 을 반환할 수 있다는 사실을 기억하십시오**
> `Expressions.allOf(...)` 의 반환값에 다시 `.and()` 를 이으면 **똑같은 NPE 가 재발합니다.**
> `allOf` 는 null 을 걸러 주지만, null 을 만들지 않는다고 보장하지는 않습니다.
> 합성이 두 단계 이상이면 바깥도 `allOf` 로 감싸십시오.
> ```java
> Expressions.allOf(
>         Expressions.allOf(statusEq(s), priceGoe(min)),
>         categoryEq(cid));
> ```

> 💡 `Expressions.allOf` / `anyOf` 의 null 처리 동작은 **버전에 따라 확인하고 쓰십시오.**
> 이 코스는 QueryDSL 6.12 에서 위와 같이 동작하는 것을 확인했습니다.
> 확신이 서지 않는다면 아래 처방 2 처럼 **null 안전 헬퍼를 직접 만드십시오.**
> 직접 만든 헬퍼는 버전과 무관하게 여러분이 동작을 보증할 수 있습니다.

### 처방 2 — null 안전 헬퍼를 직접 만든다

```java
package com.example.shop.support;

import com.querydsl.core.types.dsl.BooleanExpression;

public final class Predicates {

    private Predicates() {}

    /** null 인 조건을 건너뛰고 AND 로 합성합니다. 전부 null 이면 null 을 반환합니다. */
    public static BooleanExpression and(BooleanExpression... conditions) {
        BooleanExpression result = null;
        for (BooleanExpression c : conditions) {
            if (c == null) {
                continue;
            }
            result = (result == null) ? c : result.and(c);
        }
        return result;
    }

    /** null 인 조건을 건너뛰고 OR 로 합성합니다. */
    public static BooleanExpression or(BooleanExpression... conditions) {
        BooleanExpression result = null;
        for (BooleanExpression c : conditions) {
            if (c == null) {
                continue;
            }
            result = (result == null) ? c : result.or(c);
        }
        return result;
    }
}
```

```java
private BooleanExpression statusAndPrice(ProductStatus status, BigDecimal minPrice) {
    return Predicates.and(statusEq(status), priceGoe(minPrice));
}
```

동작과 생성 SQL 은 `Expressions.allOf` 와 같습니다.
차이는 **동작을 여러분이 통제한다**는 것뿐이며, 그것이 이 20줄의 값어치입니다.

### 처방 3 — 애초에 합성하지 않는다

가장 단순한 답입니다. **`where` 에 나란히 넘기면 합성할 필요가 없습니다.**

```java
.where(
        statusEq(cond.status()),
        priceGoe(cond.minPrice()),
        priceLoe(cond.maxPrice())
)
```

`where` 가 AND 로 묶고 null 을 무시합니다. NPE 가 생길 자리가 없습니다.

**합성이 필요한 경우는 `or` 그룹을 만들거나, `purchasable()` 처럼 이름 붙은 개념을 정의할 때뿐입니다.**
그리고 그런 개념은 대개 **null 을 반환하지 않는 상수 조건**입니다.

> 💡 **규칙으로 정리하십시오**
> - `null` 을 반환할 수 있는 조건 메서드 → **`where` 에 나란히 넘기기만 한다. 절대 `.and()` 로 잇지 않는다.**
> - `null` 을 반환하지 않는 개념 메서드(`purchasable()` 등) → 자유롭게 합성해도 된다.
> - 두 종류를 섞어야 한다면 → `Predicates.and(...)` 같은 null 안전 헬퍼를 쓴다.
> 메서드 이름이나 주석으로 **"이 메서드는 null 을 반환할 수 있다"** 를 표시해 두면 사고가 줄어듭니다.

---

## 10-9. keyword 검색 — `contains` 와 인덱스

10-6 의 조합 4 에서 나온 SQL 을 다시 봅니다.

```sql
where p1_0.name like ? escape '!'
```
```
바인딩: [1] %노트북%
```

**앞에도 `%` 가 붙었습니다.** 이것이 문제입니다.

| QueryDSL | 생성되는 LIKE 패턴 | 인덱스 |
|---|---|---|
| `product.name.contains("노트북")` | `%노트북%` | **못 탐** |
| `product.name.startsWith("노트북")` | `노트북%` | **탐** |
| `product.name.endsWith("노트북")` | `%노트북` | 못 탐 |
| `product.name.like("노트북%")` | `노트북%` | 탐 |
| `product.name.eq("노트북")` | (LIKE 아님) `= ?` | 탐 |

> 📌 MySQL8 코스 [Step 15 — 인덱스](../../mysql8/step-15-indexes/) 의 15-7 (3) 에서 실측했습니다.
> 전화번호부는 "김"으로 **시작하는** 사람은 빨리 찾지만 "수"로 **끝나는** 사람은 다 뒤져야 합니다.
> 인덱스는 앞에서부터 정렬돼 있으므로 **앞이 고정된 패턴만** 탐색할 수 있습니다.

### 대조

`startsWith` 로 바꿔 봅니다.

```java
private BooleanExpression keywordStartsWith(String keyword) {
    return (keyword == null || keyword.isBlank()) ? null : product.name.startsWith(keyword);
}
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.name like ? escape '!'
order by p1_0.product_id desc
```
```
바인딩: [1] 노트북%
조회 0건 — "게이밍 노트북 RTX4060" 은 "노트북" 으로 시작하지 않습니다
```

**결과가 달라집니다.** 이것이 이 절의 어려운 지점입니다.
`startsWith` 는 빠르지만 **사용자가 기대하는 검색이 아닙니다.**
"노트북"을 치면 "게이밍 노트북 RTX4060"이 나와야 합니다.

100만 행 규모에서 두 패턴의 차이를 확인합니다.
(`access_logs` 의 `path` 컬럼에 `idx_path` 가 있는 상태입니다.)

```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE path LIKE '%detail';
```
```
+-------+---------------+----------+--------+--------------------------+
| type  | possible_keys | key      | rows   | Extra                    |
| index | NULL          | idx_path | 996151 | Using where; Using index |
+-------+---------------+----------+--------+--------------------------+
```
```
1 row in set (0.219 sec)
```

```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE path LIKE '/products%';
```
```
+-------+---------------+----------+--------+--------------------------+
| type  | possible_keys | key      | rows   | Extra                    |
| range | idx_path      | idx_path | 498075 | Using where; Using index |
+-------+---------------+----------+--------+--------------------------+
```
```
1 row in set (0.041 sec)
```

| 패턴 | EXPLAIN type | 실행시간 |
|---|---|---:|
| `LIKE '%detail'` | `index` (인덱스 풀스캔) | 0.219초 |
| `LIKE '/products%'` | `range` (인덱스 탐색) | 0.041초 |

> ⚠️ **`type: index` 는 "인덱스를 탔다" 가 아닙니다**
> MySQL8 코스 15-5 에서 강조한 내용입니다. `type: index` 는 인덱스 B+Tree 를 **처음부터 끝까지 훑는 것**으로,
> 사실상 전수 조사입니다. `range` · `ref` · `const` 여야 진짜로 탐색한 것입니다.
> `contains` 가 만드는 `%키워드%` 는 아무리 인덱스를 만들어도 `range` 가 되지 않습니다.

### 처방

**(1) 40건짜리 테이블이면 그냥 `contains` 를 쓰십시오.**
`products` 는 40건입니다. 풀스캔이 0.000초입니다.
**측정되지 않은 성능 문제를 미리 최적화하지 마십시오.**
이 절의 목적은 "`contains` 를 쓰지 말라"가 아니라 **"언제 문제가 되는지 알라"** 입니다.

**(2) 데이터가 커지면 FULLTEXT 인덱스로 옮기십시오.**

```sql
ALTER TABLE products ADD FULLTEXT KEY ft_name (name) WITH PARSER ngram;
```

한글은 공백 기준 토큰화로는 검색이 잘 안 되므로 `WITH PARSER ngram` 이 필요합니다.
MySQL8 코스 [Step 15](../../mysql8/step-15-indexes/) 의 15-10 에서 다룹니다.
QueryDSL 에서는 `Expressions.booleanTemplate` 으로 `MATCH ... AGAINST` 를 호출해야 하며,
**이 지점이 Step 13 의 문자열 템플릿과 만나는 곳**입니다. 사용자 입력을 절대 템플릿에 이어 붙이지 마십시오.

```java
// 바인딩 파라미터로 넘긴다. 문자열 연결이 아니다.
BooleanExpression ftMatch = Expressions.booleanTemplate(
        "function('match_against', {0}, {1}) > 0", product.name, keyword);
```

**(3) 검색 요구가 본격적이면 전용 검색 엔진을 검토하십시오.**
형태소 분석, 오타 교정, 동의어, 랭킹이 필요하면 RDB 의 LIKE 나 FULLTEXT 로는 한계가 있습니다.

> 💡 **실무에서 가장 흔한 절충 — 앞뒤 `%` 를 유지하되 조건을 좁힌다**
> `contains` 는 그대로 두고, **다른 조건으로 후보를 먼저 줄입니다.**
> `where(categoryEq(cid), statusEq(ON_SALE), keywordContains(kw))` 라면
> 옵티마이저가 `category_id` 인덱스로 후보를 좁힌 뒤 그 안에서만 LIKE 를 평가합니다.
> "카테고리를 반드시 선택하게 하는" UI 설계가 곧 성능 설계인 경우가 많습니다.

---

## 10-10. 통합 — 조건 + 정렬 + 페이징

지금까지의 모든 것을 하나의 리포지토리 메서드로 합칩니다.

```java
package com.example.shop.repository;

import com.example.shop.dto.ProductListDto;
import com.example.shop.dto.ProductSearchCond;
import com.example.shop.dto.QProductListDto;
import com.example.shop.entity.ProductStatus;
import com.example.shop.support.SortWhitelist;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QProduct.product;

@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    private final JPAQueryFactory queryFactory;

    // 정렬 화이트리스트 — 등록되지 않은 키는 쿼리에 들어가지 않는다 (10-4)
    // 기본 정렬이 타이브레이커 역할도 한다 (Step 09 의 9-9)
    private static final SortWhitelist SORT = SortWhitelist
            .lenient(product.productId.desc())
            .add("price",     product.price)
            .add("stock",     product.stock)
            .add("createdAt", product.createdAt)
            .add("name",      product.name);

    public Page<ProductListDto> search(ProductSearchCond cond, Pageable pageable) {

        // ① 콘텐츠 쿼리 — 카테고리 이름을 함께 보여주므로 join 이 필요하다
        List<ProductListDto> content = queryFactory
                .select(new QProductListDto(
                        product.productId,
                        product.name,
                        product.price,
                        product.stock,
                        product.status,
                        category.name))
                .from(product)
                .join(product.category, category)
                .where(
                        keywordContains(cond.keyword()),
                        categoryEq(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        statusEq(cond.status()),
                        inStock(cond.inStockOnly())
                )
                .orderBy(SORT.resolve(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // ② count 쿼리 — join 과 orderBy 를 뺀다 (Step 09 의 9-6)
        //    where 가 category 테이블 컬럼을 참조하지 않으므로 join 을 뺄 수 있다.
        //    categoryEq 는 product.category.categoryId — FK 컬럼이라 조인이 필요 없다.
        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(
                        keywordContains(cond.keyword()),
                        categoryEq(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        statusEq(cond.status()),
                        inStock(cond.inStockOnly())
                );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // --- 조건 메서드 (10-6) — 전부 null 을 반환할 수 있다. 절대 .and() 로 잇지 말 것 ---

    private BooleanExpression keywordContains(String keyword) {
        return (keyword == null || keyword.isBlank()) ? null : product.name.contains(keyword);
    }

    private BooleanExpression categoryEq(Long categoryId) {
        return categoryId == null ? null : product.category.categoryId.eq(categoryId);
    }

    private BooleanExpression priceGoe(BigDecimal minPrice) {
        return minPrice == null ? null : product.price.goe(minPrice);
    }

    private BooleanExpression priceLoe(BigDecimal maxPrice) {
        return maxPrice == null ? null : product.price.loe(maxPrice);
    }

    private BooleanExpression statusEq(ProductStatus status) {
        return status == null ? null : product.status.eq(status);
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        return Boolean.TRUE.equals(inStockOnly) ? product.stock.gt(0) : null;
    }
}
```

호출합니다.

```java
ProductSearchCond cond = new ProductSearchCond(
        "노트북", null, new BigDecimal("500000"), null, ProductStatus.ON_SALE, true);

Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "price"));

Page<ProductListDto> page = productSearchRepository.search(cond, pageable);
```

**결과** — 콘텐츠 쿼리
```sql
select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock, p1_0.status, c1_0.name
from products p1_0
join categories c1_0 on c1_0.category_id = p1_0.category_id
where p1_0.name like ? escape '!'
  and p1_0.price >= ?
  and p1_0.status = ?
  and p1_0.stock > ?
order by p1_0.price desc, p1_0.product_id desc
limit ?, ?
```
```
바인딩: [1] %노트북%  [2] 500000  [3] ON_SALE  [4] 0  [5] 0  [6] 10
```

**결과** — count 쿼리
```sql
select count(p1_0.product_id)
from products p1_0
where p1_0.name like ? escape '!'
  and p1_0.price >= ?
  and p1_0.status = ?
  and p1_0.stock > ?
```
```
바인딩: [1] %노트북%  [2] 500000  [3] ON_SALE  [4] 0
```

**결과** — 반환
```
content 2건
  게이밍 노트북 RTX4060   2,190,000  재고 12  ON_SALE  노트북
  보급형 노트북 15          690,000  재고 31  ON_SALE  노트북
total 2  totalPages 1
```

**실제로는 count 쿼리가 나가지 않습니다.**
`offset=0` 이고 결과 2건이 `pageSize` 10 보다 작으므로 `PageableExecutionUtils` 가 생략합니다.
위 count SQL 은 "만들어졌지만 실행되지 않은" 쿼리입니다. 로그를 확인하십시오.

이 메서드 하나에 이 코스의 여러 스텝이 들어 있습니다.

| 요소 | 출처 |
|---|---|
| `where(a, b, c)` 의 AND + null 무시 | [Step 04](../step-04-where-conditions/) |
| `@QueryProjection` DTO (`QProductListDto`) | [Step 05](../step-05-projections/) |
| `join(product.category, category)` | [Step 06](../step-06-joins/) |
| `product.count()` | [Step 08](../step-08-aggregation/) |
| count 분리 + `PageableExecutionUtils` + PK 타이브레이커 | [Step 09](../step-09-sorting-paging/) |
| 화이트리스트 정렬 | 이 스텝 10-4 |

---

## 10-11. 동적 `select` — 조회할 컬럼이 달라질 때

조건뿐 아니라 **조회할 컬럼**이 달라져야 할 때가 있습니다.
"관리자에게는 원가(`cost`)를 보여주고 일반 사용자에게는 감춘다" 같은 요구입니다.

### 방식 1 — 프로젝션 분기 (권장)

가장 단순하고 안전합니다. **DTO 를 두 개 만듭니다.**

```java
public List<?> search(ProductSearchCond cond, boolean isAdmin) {
    if (isAdmin) {
        return queryFactory
                .select(new QAdminProductDto(
                        product.productId, product.name, product.price,
                        product.cost, product.stock))      // cost 포함
                .from(product)
                .where(conditions(cond))
                .fetch();
    }
    return queryFactory
            .select(new QPublicProductDto(
                    product.productId, product.name, product.price, product.stock))
            .from(product)
            .where(conditions(cond))
            .fetch();
}
```

**결과** — 관리자
```sql
select p1_0.product_id, p1_0.name, p1_0.price, p1_0.cost, p1_0.stock
from products p1_0
where p1_0.status = ?
```

**결과** — 일반
```sql
select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock
from products p1_0
where p1_0.status = ?
```

`cost` 컬럼이 **SQL 에서 아예 빠졌습니다.** DTO 매핑 단계에서 거르는 게 아니라
**DB 에서 읽지도 않습니다.** 보안과 성능을 동시에 얻습니다.

> 💡 반환 타입이 `List<?>` 가 되는 게 마음에 걸린다면 공통 인터페이스(`sealed interface ProductView`)를
> 두고 두 DTO 가 구현하게 하십시오. Java 21 의 sealed 계층과 패턴 매칭이 잘 어울립니다.

### 방식 2 — `Expressions.constant` 로 자리를 메운다

DTO 를 하나로 유지하고 싶을 때 씁니다. 감출 값 자리에 상수를 넣습니다.

```java
public List<AdminProductDto> search(ProductSearchCond cond, boolean isAdmin) {
    Expression<BigDecimal> costExpr = isAdmin
            ? product.cost
            : Expressions.constant(BigDecimal.ZERO);

    return queryFactory
            .select(new QAdminProductDto(
                    product.productId, product.name, product.price, costExpr, product.stock))
            .from(product)
            .where(conditions(cond))
            .fetch();
}
```

**결과** — 일반 사용자
```sql
select p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock
from products p1_0
where p1_0.status = ?
```
```
cost 자리는 SQL 에 나가지 않고 자바 쪽에서 BigDecimal.ZERO 로 채워집니다.
```

> ⚠️ **`Expressions.constant` 는 값이 SQL 로 나갈 수도, 안 나갈 수도 있습니다**
> 위 예에서는 프로젝션 자리라 SQL 에서 사라졌지만, `where` 절에 쓰면 바인딩 파라미터로 나갑니다.
> **어느 쪽인지는 생성 SQL 을 보고 판단하십시오.**
> 그리고 `constant` 로 0 을 채우는 것은 "값이 0" 이라는 **거짓 정보**를 전달합니다.
> 클라이언트가 그것을 진짜 원가로 오해할 여지가 있다면 방식 1 이 낫습니다.

### 방식 3 — `Tuple` 로 받고 나중에 조립

컬럼 목록 자체를 리스트로 만들어 넘기는 방식입니다.

```java
public List<Tuple> search(ProductSearchCond cond, List<String> fields) {
    List<Expression<?>> selects = new ArrayList<>();
    selects.add(product.productId);                          // 항상 포함
    if (fields.contains("name"))  selects.add(product.name);
    if (fields.contains("price")) selects.add(product.price);
    if (fields.contains("stock")) selects.add(product.stock);

    return queryFactory
            .select(selects.toArray(new Expression[0]))
            .from(product)
            .where(conditions(cond))
            .fetch();
}
```

**결과** — `fields = ["name", "price"]`
```sql
select p1_0.product_id, p1_0.name, p1_0.price
from products p1_0
where p1_0.status = ?
```

> ⚠️ **`fields` 를 클라이언트가 그대로 준다면 10-4 와 똑같은 문제입니다**
> 위 코드는 `if (fields.contains("name"))` 로 **명시적 화이트리스트**를 쓰고 있어서 안전합니다.
> 만약 `PathBuilder` 로 `fields` 의 문자열을 그대로 경로화한다면
> 원가 노출과 런타임 예외가 그대로 재현됩니다.
> **정렬 키든 조회 컬럼이든, 사용자 문자열이 쿼리 구조를 결정하는 곳에는 언제나 화이트리스트가 필요합니다.**

> 💡 `Tuple` 은 타입 안전성을 잃습니다. `t.get(product.price)` 처럼 꺼낼 때
> 그 컬럼이 실제로 select 에 포함됐는지는 컴파일러가 검사해 주지 않고 `null` 이 나옵니다.
> ([Step 08](../step-08-aggregation/) 의 8-4 에서 `Tuple` 읽기의 위험을 다뤘습니다.)
> 방식 3 은 GraphQL 스타일의 필드 선택 API 를 만들 때나 고려하십시오.
> 대부분의 경우 **방식 1 이 정답입니다.**

---

## 정리

| 개념 | 핵심 |
|---|---|
| `pageable.getSort()` | QueryDSL 이 자동 적용하지 않음. **직접 변환해야 함. 에러도 안 남** |
| `OrderSpecifier` | `new OrderSpecifier<>(Order.ASC, expression)` — 방향을 런타임에 결정 가능 |
| `Order` 이름 충돌 | `com.querydsl.core.types.Order` vs 도메인 `Order` 엔티티 vs `Sort.Order`. **파일 단위 격리가 정석** |
| `PathBuilder` 정렬 | 코드는 짧지만 **없는 필드 → 500**, **모든 필드 노출**, 화이트리스트 없음 |
| SQL 인젝션 | 값은 안전(바인딩). **구조(경로·컬럼·정렬)는 바인딩 대상이 아님** |
| JPQL 파서 방어 | 고전적 인젝션을 대체로 막아 주지만 **우리 코드의 방어가 아님**. `stringTemplate` 과 결합하면 열림 |
| 화이트리스트 | 허용 키만 `Map`/빌더로 등록. 미등록 키는 기본 정렬 대체(`lenient`) 또는 400(`strict`) |
| `record` 조건 객체 | Java 21. 불변 + 자동 `equals`/`toString`. 미지정 필드는 `null` = 조건 없음 |
| 조건 메서드 | `BooleanExpression` 반환, 값 없으면 `null`. `where(a,b,c)` 가 AND + null 무시 |
| `BooleanExpression` vs `BooleanBuilder` | 생성 SQL 은 동일. **재사용·합성·명명**에서 전자가 우위 |
| `null.and(...)` | **NPE**. 앞이 null 이면 죽고 뒤가 null 이면 통과하는 비대칭 |
| `Expressions.allOf` | null 인자를 걸러 AND. **전부 null 이면 자신도 null 반환** → 다시 `.and()` 하면 재발 |
| `contains` | `%키워드%` → 앞 `%` 때문에 인덱스 못 탐(`type: index`). `startsWith` 는 `range` |
| LIKE 이스케이프 | `escape '!'` 가 자동으로 붙어 사용자 입력의 `%`/`_` 를 무력화 |
| 동적 select | **프로젝션 분기가 정답.** `cost` 를 SQL 에서 아예 빼면 보안 + 성능 동시 확보 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.
**답이 맞아도 생성 SQL 이 다르면 틀린 것입니다.**

1. `Sort.by(DESC, "price")` 를 `OrderSpecifier[]` 로 바꾸는 화이트리스트 변환기를 완성하고, 기본 정렬(PK)이 항상 마지막에 붙는지 생성 SQL 로 확인하기
2. `sort=cost,desc` 를 보냈을 때 `PathBuilder` 방식과 화이트리스트 방식의 **생성 SQL 을 나란히 적고**, 왜 전자가 위험한지 두 문장으로 쓰기
3. `sort=noSuchField,desc` 를 `PathBuilder` 방식에 넣어 **실제 예외 메시지를 기록**하고, 화이트리스트 방식에서는 무엇이 일어나는지 비교하기
4. `ProductSearchCond` 를 받아 `where` 에 조건 메서드 6개를 나란히 넘기는 조회를 작성하고, **조건을 0개/1개/3개/6개 준 네 경우의 생성 SQL** 을 기록하기
5. `statusEq(null).and(priceGoe(...))` 로 NPE 를 **재현**한 뒤, `Expressions.allOf` 와 직접 만든 헬퍼 두 가지로 각각 고치기
6. `contains` 와 `startsWith` 의 생성 SQL 과 결과 건수를 대조하고, 어느 쪽이 인덱스를 타는지 그 이유와 함께 쓰기
7. 조건 + 정렬 + 페이징을 통합한 `Page<ProductListDto>` 반환 메서드를 완성하고, **count 쿼리가 실행되지 않는 호출**을 하나 만들어 로그로 확인하기

---

## 다음 단계

지금까지는 전부 **읽는** 쿼리였습니다.
그러나 "판매 중단된 상품의 상태를 일괄 변경" 이나 "1년 지난 로그 삭제" 처럼
수천·수만 건을 한 번에 바꿔야 할 때가 있습니다. 한 건씩 `save()` 하면 쿼리가 그만큼 나갑니다.

QueryDSL 은 `update` / `delete` 벌크 연산을 제공합니다.
그런데 이 연산은 **영속성 컨텍스트를 건너뜁니다.**
UPDATE 는 분명히 성공했는데, 바로 뒤에서 조회하면 **옛 값이 그대로 나옵니다.**
에러도 경고도 없습니다.

→ [Step 11 — 벌크 연산](../step-11-bulk-operations/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 실행하며
10-1 ~ 10-11 의 생성 SQL 을 콘솔에서 확인한 뒤, `Exercise.java` 의 7문제를 풀고
`Solution.java` 로 대조합니다. 세 파일 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이며
`src/test/java/com/example/shop/step10/` 에 그대로 넣으면 실행됩니다.

### Practice.java

본문의 모든 예제를 `// [10-4]` 형태의 절 번호 주석과 함께 담은 실행 파일입니다.
정렬 유틸(`PathBuilderSortUtils`, `SortWhitelist`)과 null 안전 헬퍼(`Predicates`),
조건 레코드(`ProductSearchCond`)는 **파일 하나로 실행할 수 있도록 static nested class 로 담았습니다.**
실제 프로젝트에서는 `com.example.shop.support` / `com.example.shop.dto` 패키지로 분리하십시오.

- 파일 상단의 `import com.querydsl.core.types.Order;` 가 **없다는 점**을 확인하십시오. 이 파일은 도메인 `Order` 엔티티를 쓰지 않지만, 10-2 의 이름 충돌을 재현하려면 주석 처리된 `// import com.example.shop.entity.Order;` 를 해제해 보십시오. 컴파일 에러가 즉시 납니다. **이 실험은 이 스텝에서 반드시 한 번 해 보십시오.**
- `s4_pathBuilderThrows` 는 **의도적으로 예외를 던지는 테스트**입니다. `assertThatThrownBy` 로 감싸 두었으므로 테스트는 성공하지만, 콘솔에는 `SemanticException: Could not interpret path expression 'product.nonExistentField'` 가 찍힙니다. 그 메시지를 눈으로 확인하는 것이 목적입니다.
- `s4_costExposure` 는 원가 노출을 재현합니다. **응답에 `cost` 값이 없어도 순서가 정보를 흘린다**는 것이 요점이므로, `asc` 와 `desc` 를 번갈아 호출한 결과의 `product_id` 순서를 출력해 뒤집힌 관계를 확인하십시오.
- `s8_nullAndNpe` 는 NPE 를 재현하는 테스트입니다. **인자 순서를 바꿔 가며 두 번 호출**합니다. `statusEq(null).and(priceGoe(x))` 는 죽고 `statusEq(x).and(priceGoe(null))` 는 통과합니다. 이 비대칭이 함정의 본질이므로 두 호출을 모두 실행해 보십시오.
- `s9_containsVsStartsWith` 는 40건짜리 `products` 에서 실행되므로 시간 차이가 나지 않습니다. 본문 10-9 의 0.219초 / 0.041초 는 100만 행 `access_logs` 에 SQL 을 직접 던진 것이므로 그 부분은 MySQL 클라이언트에서 재현하십시오. 이 메서드가 확인시켜 주는 것은 **바인딩 값이 `%노트북%` 인가 `노트북%` 인가**, 그리고 **결과 건수가 2건과 0건으로 갈린다**는 사실입니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 요구사항 주석과 `// 여기에 작성:` 자리로 되어 있습니다.

- **문제 2·3** 은 코드보다 **기록이 본체**입니다. 생성 SQL 과 예외 메시지를 주석 칸에 그대로 옮겨 적으십시오. 특히 문제 3 의 예외 메시지는 여러분의 Hibernate 버전에 따라 문구가 다를 수 있으므로, 교재의 문구가 아니라 **여러분 콘솔의 문구**를 적어야 합니다.
- **문제 4** 는 네 번 실행해 네 개의 SQL 을 모으는 문제입니다. 조건이 0개일 때 `where 1=1` 이 나오는지 아니면 `where` 절 자체가 사라지는지를 반드시 확인하십시오. 이것을 착각하는 사람이 많습니다.
- **문제 5** 는 먼저 NPE 를 **일부러 내야** 합니다. `assertThatThrownBy` 로 감싸지 말고 그냥 실행해 스택트레이스를 보십시오. 그다음 두 가지 방식으로 고칩니다. `Expressions.allOf` 버전과 직접 만든 `Predicates.and` 버전의 생성 SQL 이 같은지도 확인하십시오.
- **문제 7** 은 이 스텝의 종합 문제입니다. 앞의 여섯 문제에서 만든 조각을 조립하면 됩니다. "count 쿼리가 실행되지 않는 호출"은 Step 09 의 9-6 을 다시 읽으면 두 가지 조건 중 하나를 고르면 된다는 것을 알 수 있습니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과, 왜 그 답인지를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여십시오.

- **정답 2·3** 은 이 스텝의 보안 논지를 정리합니다. 특히 **"QueryDSL-JPA 는 JPQL 로 번역되므로 고전적 SQL 인젝션은 대체로 막히지만, 그 방어는 우리 코드의 것이 아니다"** 라는 구분을 길게 다룹니다. `PathBuilder` 가 위험한 실제 이유는 인젝션보다 **500 에러와 필드 노출**이라는 점, 그리고 그 습관이 `stringTemplate`(Step 13) 과 만나면 진짜 인젝션이 된다는 점을 순서대로 설명합니다.
- **정답 4** 는 조건이 0개일 때 `where` 절이 **통째로 사라진다**는 것을 강조합니다. `where 1=1` 을 붙이는 습관은 문자열 SQL 조립 시대의 것이며 QueryDSL 에서는 불필요할 뿐 아니라 옵티마이저에게 잡음이 됩니다.
- **정답 5** 는 NPE 의 비대칭(`null.and(x)` 는 죽고 `x.and(null)` 은 통과)이 왜 위험한지를 설명합니다. 개발 중에는 조건을 다 채워 테스트하므로 통과하고, 운영에서 사용자가 조건 하나를 비우는 순간 500 이 나며, 그것도 **어떤 조건을 비우느냐에 따라** 나기도 하고 안 나기도 합니다. 그리고 `Expressions.allOf` 의 반환값에 다시 `.and()` 를 이으면 같은 문제가 재발한다는 것까지 짚습니다.
- **정답 6** 은 "`contains` 를 쓰지 말라" 가 아니라 **"40건짜리 테이블에서는 `contains` 가 정답"** 이라는 결론에 도달합니다. 측정되지 않은 성능 문제를 미리 최적화하지 말라는 것이며, 동시에 데이터가 커졌을 때 무엇이 문제가 되는지(`type: index` = 인덱스 풀스캔)를 알고 있어야 한다는 것입니다.
- **정답 7** 은 통합 메서드 안에서 이 코스의 어느 스텝이 어디에 쓰였는지 표로 정리하고, count 쿼리에서 `join(product.category, category)` 를 **뺄 수 있는 이유**(where 가 category 컬럼을 참조하지 않고, `categoryEq` 는 FK 컬럼을 직접 비교하므로 조인이 필요 없다)를 설명합니다.

```java file="./Solution.java"
```
