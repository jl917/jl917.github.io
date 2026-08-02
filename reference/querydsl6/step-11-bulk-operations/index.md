# Step 11 — 벌크 연산

> **학습 목표**
> - `update` / `set` / `where` / `execute` 로 한 문장짜리 대량 갱신을 작성하고 영향 행 수를 읽는다
> - `delete` 로 대량 삭제를 작성하고, 생성되는 DML 이 **한 번만** 나가는 것을 로그로 확인한다
> - **벌크 연산이 영속성 컨텍스트를 건너뛴다**는 사실을 SQL 로그로 재현한다 (SQL 이 **안 찍히는 것**까지 확인)
> - 옛 값 엔티티를 수정했을 때 **더티 체킹이 벌크 결과를 덮어쓰는** 사고를 재현하고 `flush()` → `clear()` 로 고친다
> - 벌크 DELETE 가 `cascade = REMOVE` / `orphanRemoval` 을 무시해 FK 제약 위반이 나는 경우를 재현한다
> - QueryDSL-JPA 에 `insert` 가 **없다**는 사실과 그 대안(`persist` / batch insert / JdbcTemplate)을 안다
> - 벌크 연산과 더티 체킹 중 무엇을 쓸지 행 수·비즈니스 로직·이벤트 기준으로 판단한다
>
> **선행 스텝**: [Step 10 — 동적 정렬과 검색 조건 조립](../step-10-dynamic-sort/)
> **예상 소요**: 120분

---

## 11-0. ⚠️ 먼저 읽으세요 — 이 스텝은 데이터를 바꿉니다

Step 01 부터 Step 10 까지는 전부 **조회**였습니다. 무엇을 어떻게 잘못 짜도 데이터는 그대로였습니다.
이 스텝부터는 다릅니다. `update` 와 `delete` 는 실제로 `shop` DB 의 행을 바꿉니다.

`shop` DB 는 **여러 학습자가 공유**합니다. 누군가 `products` 의 가격을 10% 올려 버리면,
Step 03 의 "27인치 4K 모니터 459000" 이 다른 사람 화면에서는 504900 으로 나옵니다.
그 순간부터 이 코스의 모든 **결과** 블록이 틀린 교재가 됩니다.

그래서 이 스텝은 두 가지 안전장치 중 **하나를 반드시** 씁니다.

### 방법 A — `@Transactional` + 롤백 (이 코스의 기본)

`Practice.java` 와 `Solution.java` 는 `@SpringBootTest` + `@Transactional` 테스트 클래스입니다.
스프링 테스트에서 `@Transactional` 은 **테스트 메서드가 끝나면 트랜잭션을 롤백**합니다.
UPDATE / DELETE 는 실제로 DB 에 나가지만(그래서 SQL 로그도 정확히 찍히고 영향 행 수도 진짜입니다),
커밋되지 않고 되돌아갑니다.

```java
@SpringBootTest
@Transactional              // ← 테스트 종료 시 자동 롤백
class Practice {
    @Autowired JPAQueryFactory queryFactory;
    @PersistenceContext EntityManager em;
}
```

> ⚠️ **함정 — `@Commit` 이나 `@Rollback(false)` 를 붙이지 마십시오**
> 인터넷 예제 중에는 "롤백되면 DB 에서 확인이 안 되니 `@Rollback(false)` 를 붙이라"는 것이 많습니다.
> 이 스텝에서 그렇게 하면 공용 데이터가 영구히 오염됩니다.
> 확인이 필요하면 롤백 전에 `queryFactory.select(...)` 로 **같은 트랜잭션 안에서** 확인하십시오.
> 트랜잭션 안에서는 자기가 바꾼 값이 그대로 보입니다.

### 방법 B — `s11_` 접두사 사본 테이블

MySQL8 코스 [Step 11 — DML](../../mysql8/step-11-dml/) 과 동일한 규칙입니다.
커밋된 상태로 데이터를 관찰하고 싶다면 사본을 만들어 그쪽만 건드립니다.

```sql
-- 구조(인덱스·제약 포함)만 복사
CREATE TABLE s11_products LIKE products;
CREATE TABLE s11_reviews  LIKE reviews;
-- 데이터 채우기
INSERT INTO s11_products SELECT * FROM products;
INSERT INTO s11_reviews  SELECT * FROM reviews;
```

다만 QueryDSL-JPA 는 **엔티티에 매핑된 테이블**만 다룹니다.
`s11_products` 를 QueryDSL 로 다루려면 별도 엔티티를 만들어야 하므로,
이 코스에서는 **방법 A(트랜잭션 롤백)를 기본**으로 하고,
방법 B 는 "커밋 후 상태를 직접 보고 싶을 때" 의 선택지로만 둡니다.

실습이 끝나면 사본을 지웁니다.

```sql
DROP TABLE IF EXISTS s11_reviews;
DROP TABLE IF EXISTS s11_products;
```

이 스텝의 모든 코드는 아래 static import 를 전제로 합니다.

```java
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
```

---

## 11-1. 왜 벌크 연산인가 — 40번이냐 1번이냐

요구사항 하나를 놓고 시작합니다.

> **"모든 상품의 가격을 10% 인상하라."**

상품은 40개입니다. JPA 의 정석대로 — 즉 **엔티티를 조회해서 값을 바꾸고 더티 체킹에 맡기는** 방식으로
써 보면 이렇습니다.

```java
List<Product> products = queryFactory
        .selectFrom(product)
        .fetch();

for (Product p : products) {
    p.setPrice(p.getPrice().multiply(new BigDecimal("1.1")));
}
em.flush();
```

**결과** — `hibernate.SQL` 로그

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
```
```sql
update products set category_id=?, cost=?, created_at=?, name=?, price=?, status=?, stock=? where product_id=?
update products set category_id=?, cost=?, created_at=?, name=?, price=?, status=?, stock=? where product_id=?
update products set category_id=?, cost=?, created_at=?, name=?, price=?, status=?, stock=? where product_id=?
... (총 40회)
```
```
문장 수: SELECT 1회 + UPDATE 40회 = 41회
소요: 0.184초
```

이제 벌크 연산입니다.

```java
long updated = queryFactory
        .update(product)
        .set(product.price, product.price.multiply(new BigDecimal("1.1")))
        .execute();
```

**결과**

```sql
update products set price=price*?
```
```
바인딩: [1] 1.1
문장 수: UPDATE 1회
반환값(updated): 40
소요: 0.007초
```

**41번 → 1번. 0.184초 → 0.007초. 약 26배.**

행이 40개일 땐 눈에 안 보입니다. 상품이 40만 개인 서비스에서
"전 상품 가격 조정" 배치를 더티 체킹으로 돌리면 SELECT 1번 + UPDATE 40만 번이 나가고,
그 40만 개 엔티티가 전부 영속성 컨텍스트(1차 캐시)에 올라앉아 힙을 먹습니다.
`OutOfMemoryError` 로 죽는 배치의 절반은 이 패턴입니다.

| 방식 | 문장 수 | 힙 사용 | 비즈니스 로직 | JPA 이벤트 |
|---|---:|---|---|---|
| 조회 + 더티 체킹 | 1 + N | 엔티티 N개 상주 | 엔티티 메서드 그대로 사용 | `@PreUpdate` 등 동작 |
| 벌크 `update` | 1 | 거의 없음 | **불가** (표현식만) | **동작 안 함** |

> 📌 MySQL8 코스 [Step 11 — DML](../../mysql8/step-11-dml/) 에서
> `UPDATE s11_products SET price = price * 1.1;` 이라고 한 줄 썼던 그 문장입니다.
> QueryDSL 벌크 연산은 결국 그 SQL 을 타입 안전하게 조립하는 도구입니다.

---

## 11-2. `update` / `set` / `where` / `execute`

벌크 갱신의 네 조각입니다.

```java
long updated = queryFactory
        .update(product)                                          // ① 대상 엔티티
        .set(product.price, product.price.multiply(new BigDecimal("1.1")))  // ② 무엇을
        .where(product.status.eq(ProductStatus.ON_SALE))          // ③ 어디에
        .execute();                                               // ④ 실행
```

**결과**

```sql
update products set price=price*? where status=?
```
```
바인딩: [1] 1.1  [2] ON_SALE
반환값(updated): 28
```

네 조각을 하나씩 봅니다.

| 조각 | 반환 타입 | 설명 |
|---|---|---|
| `update(EntityPath)` | `JPAUpdateClause` | 대상은 **엔티티 하나**. 조인 UPDATE 는 JPQL 에 없습니다 |
| `.set(path, value)` | `JPAUpdateClause` | 체이닝 가능. 상수·표현식·서브쿼리 모두 가능 |
| `.where(predicate...)` | `JPAUpdateClause` | Step 04 의 `BooleanExpression` 을 그대로 씁니다 |
| `.execute()` | `long` | **영향받은 행 수**. 조회 결과가 아닙니다 |

`execute()` 의 반환값은 `fetch()` 처럼 데이터를 주지 않습니다. JDBC 의 `executeUpdate()` 가
돌려주는 행 수 그대로입니다. 상품 40개 중 `ON_SALE` 이 28개이므로 28 입니다.

> ⚠️ **함정 — `where` 를 빠뜨려도 컴파일됩니다**
> `.where(...)` 는 선택입니다. 빼면 **전체 행**이 대상입니다.
> ```java
> queryFactory.update(product).set(product.status, ProductStatus.HIDDEN).execute();
> ```
> 이 코드는 컴파일도 되고 예외도 안 나고, 상품 40개를 전부 숨깁니다.
> MySQL 의 `sql_safe_updates` 는 **키 조건 없는 UPDATE 를 막아 주지만**
> (MySQL8 코스 [Step 11](../../mysql8/step-11-dml/) 참고),
> 그건 세션 설정이라 애플리케이션 커넥션에는 보통 꺼져 있습니다.
> 벌크 `update` / `delete` 를 쓸 때는 **`where` 가 있는지를 코드 리뷰 체크리스트에 넣으십시오.**

`where` 에는 조회 때 쓰던 조건을 그대로 씁니다.

```java
long updated = queryFactory
        .update(product)
        .set(product.status, ProductStatus.SOLD_OUT)
        .where(product.stock.eq(0)
                .and(product.status.eq(ProductStatus.ON_SALE)))
        .execute();
```

**결과**

```sql
update products set status=? where stock=? and status=?
```
```
바인딩: [1] SOLD_OUT  [2] 0  [3] ON_SALE
반환값(updated): 3
```

`null` 조건은 조회에서와 똑같이 무시됩니다 (Step 04 4-6).

```java
BooleanExpression cond = (minStock == null) ? null : product.stock.goe(minStock);

long updated = queryFactory
        .update(product)
        .set(product.status, ProductStatus.ON_SALE)
        .where(cond)          // cond 가 null 이면 where 절 자체가 사라집니다
        .execute();
```

**결과** — `minStock == null` 일 때

```sql
update products set status=?
```
```
반환값(updated): 40   ← 전체 40건. 의도한 것이 맞습니까?
```

조회에서 `null` 조건 무시는 편리한 기능이었습니다.
**벌크 갱신에서는 그 편리함이 전체 테이블 갱신으로 바뀝니다.**
동적 조건을 벌크에 쓸 때는 `BooleanBuilder` 가 비었는지 먼저 확인하십시오.

```java
BooleanBuilder builder = new BooleanBuilder();
if (minStock != null) builder.and(product.stock.goe(minStock));
if (status != null)   builder.and(product.status.eq(status));

if (!builder.hasValue()) {
    throw new IllegalArgumentException("벌크 갱신에는 조건이 하나 이상 필요합니다");
}
```

---

## 11-3. `set` 체이닝과 자기 참조 연산

`set` 은 여러 번 이어 붙일 수 있습니다.

```java
long updated = queryFactory
        .update(product)
        .set(product.price, product.price.multiply(new BigDecimal("1.1")))
        .set(product.stock, product.stock.add(10))
        .set(product.status, ProductStatus.ON_SALE)
        .where(product.category.id.eq(7L))
        .execute();
```

**결과**

```sql
update products set price=price*?, stock=stock+?, status=? where category_id=?
```
```
바인딩: [1] 1.1  [2] 10  [3] ON_SALE  [4] 7
반환값(updated): 6
```

여기서 중요한 것은 **`product.stock.add(10)`** 입니다.
자바 코드에서는 `product.stock` 이 값처럼 생겼지만, 이건 값이 아니라 **경로(Path)** 입니다.
`add(10)` 은 `stock + 10` 이라는 **SQL 표현식**을 만듭니다.
그래서 `stock=stock+?` 이 되고, **행마다 자기 값을 기준으로** 계산됩니다.

이걸 자바 값 연산으로 착각하면 이런 코드가 나옵니다.

```java
// ❌ 의도: 재고 10 증가
Product p = em.find(Product.class, 1L);
queryFactory.update(product)
        .set(product.stock, p.getStock() + 10)     // ← 자바에서 계산해 상수로 넣음
        .where(product.category.id.eq(7L))
        .execute();
```

**결과**

```sql
update products set stock=? where category_id=?
```
```
바인딩: [1] 60      ← 1번 상품의 재고 50 + 10 이 그대로 상수가 됨
반환값(updated): 6
```

카테고리 7의 상품 6개가 **전부 재고 60** 이 됐습니다. 원래 재고가 3이든 200이든 상관없이.
에러는 안 납니다. 로그를 안 보면 못 잡습니다.

**고친 코드**

```java
queryFactory.update(product)
        .set(product.stock, product.stock.add(10))   // 경로 기반 표현식
        .where(product.category.id.eq(7L))
        .execute();
```

**결과**

```sql
update products set stock=stock+? where category_id=?
```
```
바인딩: [1] 10
반환값(updated): 6
```

사용 가능한 산술 표현식입니다.

| 표현식 | 생성 SQL | 비고 |
|---|---|---|
| `product.stock.add(10)` | `stock+?` | `NumberPath` |
| `product.stock.subtract(5)` | `stock-?` | 음수 방지 조건은 `where` 에 직접 |
| `product.price.multiply(new BigDecimal("1.1"))` | `price*?` | `BigDecimal` 은 `BigDecimal` 로 |
| `product.price.divide(2)` | `price/?` | 0 나눗셈 주의 |
| `product.name.concat("(단종)")` | `concat(name,?)` | `StringPath` |
| `Expressions.nullExpression(String.class)` | `null` | 컬럼을 NULL 로 |

`null` 로 만들 때는 타입을 잃지 않도록 `setNull` 또는 `Expressions.nullExpression` 을 씁니다.

```java
queryFactory.update(product)
        .setNull(product.createdAt)          // NULL 허용 컬럼에만
        .where(product.id.eq(999L))
        .execute();
```

> ⚠️ **함정 — `BigDecimal` 에 `double` 을 곱하지 마십시오**
> `product.price.multiply(1.1)` 처럼 `double` 리터럴을 넘기면 컴파일은 통과할 수 있지만
> `DECIMAL(10,2)` 컬럼에 부동소수 오차가 섞입니다.
> `459000 * 1.1` 이 `504900.00` 이 아니라 `504900.000000000005` 로 계산되는 종류의 문제입니다.
> **`new BigDecimal("1.1")` 처럼 문자열 생성자를 쓰십시오.**
> `new BigDecimal(1.1)` (double 생성자)도 안 됩니다 — 그 값은 `1.100000000000000088817841970012523233890533447265625` 입니다.

---

## 11-4. `delete`

`update` 와 구조가 같습니다. `set` 이 없을 뿐입니다.

```java
long deleted = queryFactory
        .delete(review)
        .where(review.rating.lt(2))
        .execute();
```

**결과**

```sql
delete from reviews where rating<?
```
```
바인딩: [1] 2
반환값(deleted): 16
```

후기 80건 중 `rating = 1` 인 16건이 지워졌습니다.

여러 조건을 조합할 수 있습니다.

```java
long deleted = queryFactory
        .delete(review)
        .where(review.rating.lt(3)
                .and(review.createdAt.before(LocalDateTime.of(2024, 7, 1, 0, 0))))
        .execute();
```

**결과**

```sql
delete from reviews where rating<? and created_at<?
```
```
바인딩: [1] 3  [2] 2024-07-01T00:00
반환값(deleted): 9
```

서브쿼리도 됩니다 (Step 07 의 `JPAExpressions`).

```java
long deleted = queryFactory
        .delete(review)
        .where(review.product.id.in(
                JPAExpressions.select(product.id)
                        .from(product)
                        .where(product.status.eq(ProductStatus.HIDDEN))))
        .execute();
```

**결과**

```sql
delete from reviews
where product_id in (select p1_0.product_id from products p1_0 where p1_0.status=?)
```
```
바인딩: [1] HIDDEN
반환값(deleted): 4
```

> 💡 **실무 팁 — 대량 DELETE 는 나눠서**
> 100만 건을 한 문장으로 지우면 InnoDB 가 그 100만 건에 락을 잡고 언두 로그를 쌓습니다.
> 복제 지연과 락 대기가 동시에 터집니다.
> `.limit()` 은 JPQL DELETE 에 없으므로, PK 범위로 잘라 반복하는 방식이 일반적입니다.
> ```java
> long total = 0, chunk;
> do {
>     chunk = queryFactory.delete(review)
>             .where(review.rating.lt(2).and(review.id.lt(cursor)))
>             .execute();
>     total += chunk;
>     em.flush(); em.clear();
> } while (chunk > 0);
> ```
> 커서를 옮기며 도는 형태로 쓰고, 청크마다 트랜잭션을 끊는 것이 안전합니다.

> 📌 MySQL8 코스 [Step 11 — DML](../../mysql8/step-11-dml/) 의
> `DELETE` vs `TRUNCATE` 대조를 다시 보십시오.
> **JPA 에는 `TRUNCATE` 가 없습니다.** 전체 삭제가 필요하면 네이티브 쿼리를 써야 하고,
> `TRUNCATE` 는 DDL 이라 트랜잭션 롤백도 되지 않습니다.

---

## 11-5. ⚠️⚠️ 벌크 연산은 영속성 컨텍스트를 건너뜁니다

**이 절이 Step 11 의 전부입니다.** 앞의 네 절은 준비운동이었습니다.

QueryDSL 벌크 연산은 JPQL 로 번역되어 **JDBC 로 곧장 DB 에 나갑니다.**
영속성 컨텍스트(1차 캐시)는 그 일이 벌어졌다는 사실을 **모릅니다.**
JPA 는 그 UPDATE 가 어떤 행을 건드렸는지 추적하지 않습니다. 추적할 방법이 없습니다.

결과적으로 **DB 와 1차 캐시가 서로 다른 값을 갖는 상태**가 만들어집니다.
그리고 그 다음에 무엇을 하느냐에 따라 세 가지 사고가 순서대로 일어납니다.

### 11-5-1. 사고 ① — 갱신했는데 옛 값이 읽힙니다

```java
// [1] 엔티티를 영속성 컨텍스트에 올립니다
Product p = em.find(Product.class, 1L);
System.out.println("① find: " + p.getName() + " / " + p.getPrice());

// [2] 벌크로 가격을 바꿉니다
long updated = queryFactory
        .update(product)
        .set(product.price, new BigDecimal("500000"))
        .where(product.id.eq(1L))
        .execute();
System.out.println("② bulk updated = " + updated);

// [3] 다시 조회합니다
Product again = em.find(Product.class, 1L);
System.out.println("③ find again: " + again.getPrice());
System.out.println("④ same instance? " + (p == again));
```

**결과** — `hibernate.SQL` 로그와 콘솔

```sql
-- [1] em.find → 1차 캐시에 없으므로 SELECT 발생
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.product_id=?
```
```sql
-- [2] 벌크 UPDATE
update products set price=? where product_id=?
```
```
① find: 27인치 4K 모니터 / 459000.00
② bulk updated = 1
③ find again: 459000.00      ← 500000 이 아닙니다
④ same instance? true
```

**[3] 에서 SELECT 가 한 줄도 찍히지 않았습니다.**
이게 핵심입니다. `em.find()` 는 **1차 캐시를 먼저 봅니다.**
`Product#1` 이 이미 거기 있으니 **DB 에 갈 이유가 없습니다.**
DB 에는 500000 이 들어 있지만 아무도 그걸 읽으러 가지 않았습니다.

DB 를 직접 확인하면 이렇습니다.

```sql
mysql> SELECT product_id, name, price FROM products WHERE product_id = 1;
+------------+---------------------+-----------+
| product_id | name                | price     |
+------------+---------------------+-----------+
|          1 | 27인치 4K 모니터    | 500000.00 |
+------------+---------------------+-----------+
```

**DB 는 500000, 애플리케이션은 459000.** 벌크 연산은 성공했습니다.
반환값도 1이었습니다. 예외도 없습니다. 그런데 값이 다릅니다.

### 11-5-2. 사고 ② — `fetch()` 로 조회해도 옛 값이 나옵니다 (더 헷갈립니다)

"`em.find` 가 캐시를 보니까 문제구나. QueryDSL 로 조회하면 되겠네" 라고 생각하기 쉽습니다.
JPQL 조회는 **항상 DB 로 나갑니다.** 그래서 SQL 로그도 찍힙니다.
그런데 결과는 똑같습니다.

```java
Product p = em.find(Product.class, 1L);
System.out.println("① find: " + p.getPrice());

queryFactory.update(product)
        .set(product.price, new BigDecimal("500000"))
        .where(product.id.eq(1L))
        .execute();

// QueryDSL 로 다시 조회 — SQL 은 분명히 나갑니다
Product fetched = queryFactory
        .selectFrom(product)
        .where(product.id.eq(1L))
        .fetchOne();

System.out.println("② fetched: " + fetched.getPrice());
System.out.println("③ same instance? " + (p == fetched));
```

**결과**

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.product_id=?
```
```sql
update products set price=? where product_id=?
```
```sql
-- ② 조회 SQL 은 정상적으로 나갔습니다
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.product_id=?
```
```
① find: 459000.00
② fetched: 459000.00     ← SQL 은 500000 을 읽어왔는데 객체는 459000
③ same instance? true    ← 새 객체가 아니라 아까 그 객체입니다
```

**SQL 은 DB 에서 500000 을 가져왔습니다. 그런데 결과 객체의 값은 459000 입니다.**

왜냐하면 JPA 는 **동일성을 보장**하기 때문입니다.
"한 영속성 컨텍스트 안에서 같은 PK 의 엔티티는 항상 같은 인스턴스여야 한다."
이건 JPA 명세의 약속이고, 이 약속 덕분에 `==` 비교가 성립하고 더티 체킹이 성립합니다.

그래서 Hibernate 는 SELECT 결과를 엔티티로 만들 때 이렇게 합니다.

1. ResultSet 에서 PK 를 읽는다 → `Product#1`
2. 1차 캐시에 `Product#1` 이 이미 있는가? → **있다**
3. 그렇다면 **ResultSet 의 나머지 컬럼은 버리고, 캐시에 있는 인스턴스를 그대로 반환한다**

3번이 범인입니다. **DB 에서 읽어온 새 값을 의도적으로 버립니다.**
"이미 관리 중인 엔티티를 DB 값으로 덮어쓰면, 사용자가 수정 중이던 변경분이 날아가므로"
그렇게 설계된 것입니다. 합리적인 설계입니다. 벌크 연산과 만나기 전까지는.

| 상황 | SELECT SQL | 반환 값 |
|---|---|---|
| 1차 캐시에 없음 | 나감 | DB 값 (정상) |
| 1차 캐시에 있음 + `em.find` | **안 나감** | 캐시 값 (옛 값) |
| 1차 캐시에 있음 + `fetch()` | **나감** | **캐시 값 (옛 값)** ← 가장 헷갈림 |

`em.find` 는 SQL 이 안 나가니 "아, 캐시구나" 하고 알아챌 여지라도 있습니다.
`fetch()` 는 SQL 이 정상적으로 나가고 심지어 새 값을 가져오는 것까지 로그에 보입니다.
그래놓고 객체는 옛 값입니다. **로그를 봐도 원인이 안 보이는 유일한 경우**입니다.

### 11-5-3. 사고 ③ — 더티 체킹이 벌크 결과를 덮어씁니다

여기가 진짜 사고입니다. 옛 값을 **읽기만** 하면 화면이 잠깐 이상한 정도입니다.
그 옛 값 객체를 **수정하면** 데이터가 망가집니다.

```java
// [1] 재고 50인 상품을 로딩
Product p = em.find(Product.class, 1L);
System.out.println("① 로딩 시점 재고: " + p.getStock());   // 50

// [2] 벌크로 전 상품 재고를 100 증가 (다른 배치가 돌았다고 가정)
queryFactory.update(product)
        .set(product.stock, product.stock.add(100))
        .execute();
System.out.println("② 벌크 완료. DB 의 1번 상품 재고는 이제 150");

// [3] 애플리케이션 로직: 주문 1개 차감
p.setStock(p.getStock() - 1);      // 50 - 1 = 49  (p 는 아직 옛 값 50)

// [4] 트랜잭션 커밋 → 더티 체킹
em.flush();
```

**결과**

```sql
-- [1]
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.product_id=?
```
```sql
-- [2]
update products set stock=stock+?
```
```sql
-- [4] 더티 체킹이 만든 UPDATE
update products
set category_id=?, cost=?, created_at=?, name=?, price=?, status=?, stock=?
where product_id=?
```
```
① 로딩 시점 재고: 50
② 벌크 완료. DB 의 1번 상품 재고는 이제 150
[4] 바인딩: ... [7] 49  [8] 1
```

커밋 후 DB 를 봅니다.

```sql
mysql> SELECT product_id, name, stock FROM products WHERE product_id = 1;
+------------+---------------------+-------+
| product_id | name                | stock |
+------------+---------------------+-------+
|          1 | 27인치 4K 모니터    |    49 |
+------------+---------------------+-------+
```

**150 이어야 할 재고가 49 가 됐습니다.** 벌크로 더한 100 이 통째로 사라졌습니다.

더티 체킹은 이렇게 동작합니다.
엔티티를 로딩할 때 **스냅샷**(로딩 시점의 값 전체)을 함께 저장해 둡니다.
`flush()` 시점에 현재 값과 스냅샷을 비교해서 달라진 게 있으면 UPDATE 를 만듭니다.
그리고 Hibernate 의 기본 UPDATE 는 **변경된 컬럼만이 아니라 전체 컬럼**을 씁니다.
(`@DynamicUpdate` 를 붙이면 달라지지만 기본은 전체 컬럼입니다.)

그러니까 `p` 의 스냅샷은 `stock=50` 이고 현재 값은 `stock=49` 입니다.
"49로 바꿔야겠군" 하고 `set stock=49` 를 씁니다.
DB 에 150 이 들어 있다는 사실은 이 계산 어디에도 들어오지 않습니다.

**한 줄 요약**: 벌크 연산 이후에도 살아 있는 옛 엔티티는 **시한폭탄**입니다.

### 11-5-4. 처방 — `flush()` 다음에 `clear()`

```java
em.flush();   // ① 쓰기 지연 SQL 을 먼저 DB 로 내보낸다
em.clear();   // ② 1차 캐시를 통째로 비운다

long updated = queryFactory
        .update(product)
        .set(product.stock, product.stock.add(100))
        .execute();

em.clear();   // ③ 벌크 이후에도 한 번 더 (혹시 남아 있을 엔티티 제거)
```

**순서가 중요합니다.** 왜 `flush` 가 먼저인지부터 봅니다.

`flush()` 를 건너뛰고 `clear()` 만 하면, 아직 DB 로 나가지 않은 변경분이
**그냥 버려집니다.** `clear()` 는 "저장"이 아니라 "포기"입니다.

```java
Product p = em.find(Product.class, 1L);
p.setPrice(new BigDecimal("400000"));   // 아직 DB 에 안 나감 (쓰기 지연)

em.clear();                              // ❌ flush 없이 clear
// → 400000 변경분은 그대로 소멸. UPDATE 가 나가지 않습니다.

queryFactory.update(product).set(product.stock, product.stock.add(100)).execute();
```

**결과**

```sql
update products set stock=stock+?
```
```
UPDATE 는 이 한 줄뿐입니다. price 를 400000 으로 바꾼 변경분의 SQL 은 없습니다.
```

반대로 `flush()` 만 하고 `clear()` 를 안 하면, 11-5-1 / 11-5-2 의 옛 값 문제가 그대로 남습니다.

| 호출 | 하는 일 | 빠뜨리면 |
|---|---|---|
| `em.flush()` | 쓰기 지연 SQL 을 DB 로 내보냄 (1차 캐시는 유지) | 변경분 소실 또는 순서 뒤집힘 |
| `em.clear()` | 1차 캐시를 비우고 모든 엔티티를 준영속으로 | 옛 값 조회 + 더티 체킹 덮어쓰기 |

처방을 적용한 뒤 11-5-1 을 다시 돌려 봅니다.

```java
Product p = em.find(Product.class, 1L);
System.out.println("① find: " + p.getPrice());       // 459000.00

queryFactory.update(product)
        .set(product.price, new BigDecimal("500000"))
        .where(product.id.eq(1L))
        .execute();

em.flush();
em.clear();                                          // ★ 처방

Product again = em.find(Product.class, 1L);
System.out.println("② find again: " + again.getPrice());
System.out.println("③ same instance? " + (p == again));
```

**결과**

```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.product_id=?
```
```sql
update products set price=? where product_id=?
```
```sql
-- ★ clear 이후이므로 1차 캐시가 비었습니다. SELECT 가 다시 나갑니다.
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.product_id=?
```
```
① find: 459000.00
② find again: 500000.00    ← 맞습니다
③ same instance? false     ← clear 로 컨텍스트가 비었으므로 새 인스턴스입니다
```

앞의 실행과 비교하면 **SELECT 한 줄이 더 찍혔다**는 것이 유일한 차이입니다.
그 한 줄이 있느냐 없느냐가 459000 과 500000 을 가릅니다.

> ⚠️ **함정 — `clear()` 이후 옛 참조는 준영속입니다**
> `em.clear()` 를 하면 그 전에 들고 있던 `p` 는 **준영속(detached)** 상태가 됩니다.
> `p` 를 계속 쓰면 지연 로딩이 `LazyInitializationException` 으로 터지고,
> `p` 를 수정해도 더티 체킹이 동작하지 않습니다.
> **`clear()` 이후에는 필요한 엔티티를 다시 조회해서 쓰십시오.**
> 옛 참조를 살리려고 `em.merge(p)` 를 부르면 **옛 값이 다시 DB 로 올라갑니다.** 최악입니다.

### 11-5-5. Spring Data JPA 의 `@Modifying` 과의 대응

Spring Data JPA 로 벌크 연산을 쓸 때는 이 처방이 애노테이션 속성으로 제공됩니다.

```java
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.price = p.price * 1.1 where p.status = :status")
    int raisePrice(@Param("status") ProductStatus status);
}
```

| Spring Data 속성 | 대응하는 수동 호출 | 기본값 |
|---|---|---|
| `flushAutomatically = true` | 벌크 **전** `em.flush()` | `false` |
| `clearAutomatically = true` | 벌크 **후** `em.clear()` | `false` |

**둘 다 기본값이 `false` 입니다.** `@Modifying` 만 붙이고 두 속성을 안 쓰면
11-5-1 부터 11-5-3 까지의 사고가 그대로 재현됩니다.
`@Modifying` 자체는 "이건 조회가 아니라 갱신이니 `executeUpdate()` 로 실행하라"는 뜻일 뿐,
컨텍스트 동기화와는 아무 상관이 없습니다.

QueryDSL 로 짠 커스텀 리포지토리에는 이 애노테이션이 없습니다.
**직접 `flush()` / `clear()` 를 부르십시오.** 잊기 쉬우므로 벌크 메서드 안에 묶어 두는 편이 낫습니다.

```java
@Transactional
public long raisePriceOfOnSale(BigDecimal rate) {
    em.flush();                                   // ①
    long updated = queryFactory
            .update(product)
            .set(product.price, product.price.multiply(rate))
            .where(product.status.eq(ProductStatus.ON_SALE))
            .execute();
    em.clear();                                   // ②
    return updated;
}
```

---

## 11-6. 벌크 연산과 낙관적 락 (`@Version`)

이 코스의 엔티티에는 `@Version` 이 없습니다. **만약 있다면** 어떻게 되는지 짚습니다.

```java
@Entity
public class Product {
    @Id @GeneratedValue
    private Long id;
    @Version
    private Long version;     // 있다고 가정
    // ...
}
```

낙관적 락은 이렇게 동작합니다. 더티 체킹 UPDATE 는

```sql
update products set price=?, ..., version=? where product_id=? and version=?
```

처럼 **`where` 에 옛 버전을, `set` 에 새 버전을** 넣습니다.
영향 행 수가 0이면 "누가 먼저 바꿨다"는 뜻이고 `OptimisticLockException` 이 납니다.

**벌크 UPDATE 는 이 규약을 지키지 않습니다.**

```java
queryFactory.update(product)
        .set(product.price, product.price.multiply(new BigDecimal("1.1")))
        .where(product.status.eq(ProductStatus.ON_SALE))
        .execute();
```

**결과**

```sql
update products set price=price*? where status=?
```

`version` 은 `set` 에도 `where` 에도 없습니다. **버전이 올라가지 않습니다.**
그러면 어떤 일이 생기냐면, 다른 트랜잭션이 옛 버전을 들고 있어도
`where version = ?` 이 여전히 통과합니다. **낙관적 락이 조용히 무력화됩니다.**

필요하면 **직접 올려야 합니다.**

```java
queryFactory.update(product)
        .set(product.price, product.price.multiply(new BigDecimal("1.1")))
        .set(product.version, product.version.add(1L))     // ★ 수동으로 버전 증가
        .where(product.status.eq(ProductStatus.ON_SALE))
        .execute();
```

**결과**

```sql
update products set price=price*?, version=version+? where status=?
```

이렇게 하면 옛 버전을 들고 있던 트랜잭션의 더티 체킹 UPDATE 가
`where ... and version=?` 에서 0행에 걸려 `OptimisticLockException` 을 냅니다. 의도한 동작입니다.

| 항목 | 더티 체킹 | 벌크 `update` |
|---|---|---|
| `version` 증가 | 자동 | **수동 (`set` 에 직접)** |
| `where version = ?` | 자동 | 없음 (필요하면 직접) |
| `OptimisticLockException` | 발생 | 발생하지 않음 |

> ⚠️ **함정 — `@Version` 이 있는 엔티티에 벌크를 쓰면 락이 사라집니다**
> 재고 차감처럼 정확성이 중요한 로직에서 "성능 때문에" 더티 체킹을 벌크로 바꾸는 순간
> 낙관적 락이 함께 사라집니다. 코드 어디에도 락을 지웠다는 표시가 없습니다.
> `@Version` 이 붙은 엔티티에 벌크 연산을 쓸 때는 **`set(version, version.add(1))` 을 규칙으로** 삼으십시오.

---

## 11-7. 벌크 연산과 카스케이드

두 번째로 큰 함정입니다. 요지는 하나입니다.

> **벌크 DELETE 는 `cascade = REMOVE` 도 `orphanRemoval = true` 도 무시합니다.**

이유는 11-5 와 같습니다. 카스케이드는 **JPA 가 자바 코드로 구현한 기능**입니다.
`em.remove(entity)` 를 호출했을 때 Hibernate 가 연관을 순회하면서
자식들에 대해 `em.remove` 를 다시 부르는 식으로 동작합니다.
벌크 DELETE 는 그 코드를 타지 않습니다. JPQL 이 SQL 로 번역되어 DB 로 갈 뿐입니다.

### 11-7-1. 정상 동작 — `em.remove` 는 카스케이드가 돕니다

`Product` 에 이런 매핑이 있다고 합시다.

```java
@Entity
public class Product {
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Review> reviews = new ArrayList<>();
}
```

```java
Product p = em.find(Product.class, 12L);   // 후기 5건이 달린 상품
em.remove(p);
em.flush();
```

**결과**

```sql
select p1_0.product_id, p1_0.category_id, ... from products p1_0 where p1_0.product_id=?
```
```sql
-- 카스케이드를 위해 자식 컬렉션을 로딩합니다
select r1_0.product_id, r1_0.review_id, r1_0.body, r1_0.created_at,
       r1_0.customer_id, r1_0.rating, r1_0.title
from reviews r1_0
where r1_0.product_id=?
```
```sql
delete from reviews where review_id=?
delete from reviews where review_id=?
delete from reviews where review_id=?
delete from reviews where review_id=?
delete from reviews where review_id=?
delete from products where product_id=?
```
```
문장 수: SELECT 2 + DELETE 6 = 8
자식 5건이 먼저, 부모가 나중. 순서가 정확합니다.
```

JPA 가 자식을 하나씩 지우고 마지막에 부모를 지웠습니다. 느리지만 정확합니다.

### 11-7-2. 벌크 DELETE — 자식은 그대로 남습니다

```java
long deleted = queryFactory
        .delete(product)
        .where(product.id.eq(12L))
        .execute();
```

**결과**

```sql
delete from products where product_id=?
```
```
문장 수: DELETE 1
자식 SELECT 없음. 자식 DELETE 없음.
```

**JPA 는 후기를 건드리지 않았습니다.** `cascade = ALL` 도 `orphanRemoval = true` 도 무시됐습니다.

그런데 이 실행은 **성공합니다.** 왜냐하면 `reviews` 테이블의 FK 에
DB 레벨 `ON DELETE CASCADE` 가 걸려 있기 때문입니다.

```sql
CONSTRAINT fk_reviews_product
  FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE
```

**JPA 가 아니라 DB 가 자식을 지운 것입니다.**
결과는 같아 보이지만 경로가 완전히 다릅니다.

| 경로 | 자식 삭제 주체 | `@PreRemove` 콜백 | 1차 캐시 |
|---|---|---|---|
| `em.remove` + `cascade` | JPA | **호출됨** | 정리됨 |
| 벌크 `delete` + DB `ON DELETE CASCADE` | **DB** | 호출 안 됨 | **정리 안 됨** |

DB 가 지운 후기 5건은 **1차 캐시에 그대로 남아 있습니다.**
`em.find(Review.class, 51L)` 을 부르면 SQL 없이 이미 지워진 후기가 반환됩니다.
11-5 와 정확히 같은 문제입니다.

### 11-7-3. FK 제약 위반 — 이번엔 실패합니다

`order_items` 의 FK 에는 `ON DELETE CASCADE` 가 **없습니다.**

```sql
CONSTRAINT fk_order_items_product
  FOREIGN KEY (product_id) REFERENCES products(product_id)
```

`ON DELETE` 절이 없으면 MySQL 의 기본은 `RESTRICT` 입니다. 자식이 있으면 부모를 못 지웁니다.
주문된 적 있는 상품을 벌크로 지워 봅니다.

```java
long deleted = queryFactory
        .delete(product)
        .where(product.id.eq(1L))     // 27인치 4K 모니터 — 주문 상세 37건이 참조 중
        .execute();
```

**결과**

```sql
delete from products where product_id=?
```
```
org.springframework.dao.DataIntegrityViolationException:
    could not execute statement [Cannot delete or update a parent row:
    a foreign key constraint fails (`shop`.`order_items`,
    CONSTRAINT `fk_order_items_product` FOREIGN KEY (`product_id`)
    REFERENCES `products` (`product_id`))] [delete from products where product_id=?]
  at org.springframework.orm.jpa...
Caused by: java.sql.SQLIntegrityConstraintViolationException:
    Cannot delete or update a parent row: a foreign key constraint fails ...
```

`em.remove` 였다면 어땠을까요? **똑같이 실패합니다.**
`OrderItem` 은 `Product` 의 `cascade` 대상이 아니기 때문입니다
(`Product` 에는 `orderItems` 컬렉션 자체가 없습니다).
차이는 실패 지점입니다.

| 방식 | 실패 지점 | 로그에 남는 것 |
|---|---|---|
| `em.remove` + `flush` | 커밋/flush 시점 | 자식 SELECT 들이 먼저 찍힌 뒤 실패 |
| 벌크 `delete` | `execute()` 즉시 | DELETE 한 줄 찍고 바로 예외 |

**올바른 순서는 자식부터입니다.**

```java
// ① 주문 상세 먼저
long items = queryFactory
        .delete(orderItem)
        .where(orderItem.product.id.eq(1L))
        .execute();

// ② 후기
long reviews = queryFactory
        .delete(review)
        .where(review.product.id.eq(1L))
        .execute();

// ③ 마지막에 상품
long products = queryFactory
        .delete(product)
        .where(product.id.eq(1L))
        .execute();

em.flush();
em.clear();
```

**결과**

```sql
delete from order_items where product_id=?
```
```sql
delete from reviews where product_id=?
```
```sql
delete from products where product_id=?
```
```
items=37  reviews=5  products=1
문장 수: 3
```

카스케이드 로딩 없이 3문장으로 끝났습니다. 대신 **순서를 사람이 책임집니다.**

### 11-7-4. JPA 카스케이드와 DB `ON DELETE CASCADE` 는 다른 것입니다

혼동이 잦은 지점이라 표로 정리합니다.

| | JPA `cascade = REMOVE` / `orphanRemoval` | DB `ON DELETE CASCADE` |
|---|---|---|
| 정의 위치 | 엔티티 애노테이션 | 테이블 DDL (FK 제약) |
| 실행 주체 | Hibernate (자바) | MySQL 서버 |
| 발생 SQL | 자식 `DELETE` N개 | 없음 (DB 내부 처리) |
| 벌크 DELETE 에서 | **무시됨** | **동작함** |
| 네이티브 쿼리에서 | 무시됨 | 동작함 |
| `@PreRemove` 콜백 | 호출됨 | 호출 안 됨 |
| 1차 캐시 정리 | 됨 | **안 됨** |

> 📌 FK 제약과 `ON DELETE` 옵션(`CASCADE` / `RESTRICT` / `SET NULL`)의 동작은
> MySQL8 코스 [Step 13 — 제약 조건](../../mysql8/step-13-constraints/) 에서 다룹니다.
> `shop` 스키마에서 어느 FK 에 `ON DELETE CASCADE` 가 붙어 있는지 거기서 확인하십시오.
> 벌크 DELETE 를 쓰기 전에 **반드시 대상 테이블의 FK 를 확인해야 합니다.**

> ⚠️ **함정 — "엔티티에 cascade 걸어놨으니 괜찮겠지"**
> 가장 흔한 사고 패턴입니다.
> 개발자는 엔티티 애노테이션만 보고 "자식도 같이 지워지겠구나" 라고 판단합니다.
> 벌크로 바꾸는 순간 그 판단이 무효가 되는데, **엔티티 코드는 한 글자도 안 바뀝니다.**
> 리뷰어도 못 봅니다. 그래서 이 사고는 배포되고 나서 발견됩니다.

---

## 11-8. `insert` 는 어디에 있습니까 — 없습니다

`update` 와 `delete` 를 배웠으니 `insert` 를 찾게 됩니다.
`queryFactory.` 를 치고 자동완성을 봐도 `insert` 가 안 나옵니다.

**QueryDSL-JPA 에는 `insert` 가 없습니다.** 버그도 아니고 빠뜨린 것도 아닙니다.

이유는 JPQL 명세에 있습니다. **JPQL 에는 `INSERT ... VALUES` 문법이 없습니다.**
JPA 명세는 새 엔티티를 만드는 방법으로 `EntityManager#persist` 하나만 제공합니다.
QueryDSL-JPA 는 JPQL 을 생성하는 도구이므로, JPQL 에 없는 것을 만들 수 없습니다.

`INSERT ... SELECT` 는 사정이 조금 다릅니다.
Hibernate 는 HQL 확장으로 `insert into ... select ...` 를 지원하고
(Jakarta Persistence 3.2 부터는 명세에도 들어왔습니다),
`INSERT ... VALUES` 도 Hibernate 6.1 부터 HQL 로는 쓸 수 있습니다.
다만 **QueryDSL-JPA 의 `JPAQueryFactory` API 로는 노출되지 않습니다.**
`JPASQLQuery` 나 네이티브 경로를 쓰지 않는 한 QueryDSL 문법으로 INSERT 를 조립할 수단이 없습니다.

> ⚠️ **함정 — `queryFactory.insert(...)` 라고 쓰인 블로그 예제**
> 검색하면 나오는 `insert` 예제 대부분은 **QueryDSL-SQL**(`SQLQueryFactory`) 의 것입니다.
> QueryDSL 은 JPA 모듈과 SQL 모듈이 별개이고, SQL 모듈에는 `insert` 가 있습니다.
> 두 모듈은 Q타입 생성 방식부터 다릅니다 (JPA 는 엔티티 기반, SQL 은 DB 메타데이터 기반).
> JPA 모듈에 SQL 모듈 예제를 복사하면 컴파일이 안 됩니다.
> **컴파일이 안 되는 건 다행입니다.** 이 코스의 다른 함정들과 달리 조용히 틀리지는 않습니다.

### 대안 3가지

**① `em.persist` — 건수가 적을 때**

```java
Review r = new Review();
r.setProduct(em.getReference(Product.class, 1L));
r.setCustomer(em.getReference(Customer.class, 5L));
r.setRating(5);
r.setTitle("만족합니다");
r.setCreatedAt(LocalDateTime.now());
em.persist(r);
em.flush();
```

**결과**

```sql
insert into reviews (body, created_at, customer_id, product_id, rating, title)
values (?, ?, ?, ?, ?, ?)
```

`em.getReference` 를 쓰면 연관 엔티티를 실제로 조회하지 않고 프록시만 만들어
FK 값만 채웁니다. `em.find` 를 쓰면 SELECT 가 2번 더 나갑니다.

**② batch insert — 건수가 많을 때**

`persist` 를 반복하면 INSERT 가 건수만큼 나갑니다.
JDBC 배치로 묶으려면 설정이 필요합니다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100
        order_inserts: true
        order_updates: true
```

```java
for (int i = 0; i < 1000; i++) {
    em.persist(newReview(i));
    if (i % 100 == 0) {          // batch_size 와 맞춥니다
        em.flush();
        em.clear();
    }
}
```

**결과** — `hibernate.SQL` 로그

```sql
insert into reviews (body, created_at, customer_id, product_id, rating, title)
values (?, ?, ?, ?, ?, ?)
```
```
로그에는 INSERT 문이 1번씩만 찍히지만, 실제로는 addBatch()/executeBatch() 로 100건씩 묶여 나갑니다.
확인하려면 JDBC URL 에 rewriteBatchedStatements=true 를 붙이고
MySQL general log 나 p6spy 로 실제 전송 문장을 보십시오.
```

> ⚠️ **함정 — `@GeneratedValue(strategy = IDENTITY)` 면 배치가 안 묶입니다**
> MySQL 의 `AUTO_INCREMENT` 를 `IDENTITY` 전략으로 쓰면
> Hibernate 는 **`persist` 즉시 INSERT 를 실행**해야 합니다. 키를 알아야 영속성 컨텍스트에 넣을 수 있기 때문입니다.
> 그래서 `batch_size` 를 아무리 키워도 **배치가 비활성화됩니다.** 조용히, 경고 없이.
> 배치 INSERT 가 필요하면 `SEQUENCE` (MySQL 은 테이블 기반) 전략을 쓰거나 아래 ③ 으로 가십시오.

**③ `JdbcTemplate` — 정말 많을 때**

수십만 건 이상이면 JPA 를 벗어나는 것이 정직한 선택입니다.

```java
jdbcTemplate.batchUpdate(
    "insert into reviews (product_id, customer_id, rating, title, created_at) values (?,?,?,?,?)",
    reviews, 500,
    (ps, r) -> {
        ps.setLong(1, r.productId());
        ps.setLong(2, r.customerId());
        ps.setInt(3, r.rating());
        ps.setString(4, r.title());
        ps.setTimestamp(5, Timestamp.valueOf(r.createdAt()));
    });
```

**결과**

```
1,000,000건 적재
  em.persist 반복 (batch 미적용): 측정 중단 (5분 초과)
  em.persist + batch_size=100 + SEQUENCE: 41초
  jdbcTemplate.batchUpdate(500) + rewriteBatchedStatements=true: 6.2초
```

물론 이 경로는 영속성 컨텍스트를 완전히 우회합니다.
**11-5 의 모든 문제가 그대로 적용됩니다.** 같은 트랜잭션 안에서 JPA 로 조회하지 마십시오.

| 방법 | 건수 | 영속성 컨텍스트 | 비고 |
|---|---|---|---|
| `em.persist` | ~100 | 정상 | 기본. 콜백·검증 모두 동작 |
| `persist` + batch | ~10만 | 주기적 `clear` 필요 | `IDENTITY` 전략이면 무효 |
| `JdbcTemplate` | 10만~ | **우회** | 가장 빠름. JPA 규약 전부 포기 |
| `INSERT ... SELECT` (네이티브) | 무제한 | **우회** | DB 안에서 끝남. 네트워크 왕복 0 |

> 📌 MySQL8 코스 [Step 11 — DML](../../mysql8/step-11-dml/) 의 `INSERT ... SELECT` 절을 보십시오.
> "테이블 A 의 조건에 맞는 행을 테이블 B 에 복사" 같은 작업은
> 애플리케이션으로 데이터를 끌어올릴 이유가 전혀 없습니다.
> JPA 로 안 되는 것을 억지로 하려 하지 말고 네이티브 쿼리를 쓰십시오.

---

## 11-9. 언제 벌크를 쓰고 언제 더티 체킹을 씁니까

기준은 세 가지입니다. **행 수**, **비즈니스 로직의 유무**, **이벤트의 필요 여부**.

| 판단 기준 | 더티 체킹 | 벌크 연산 |
|---|---|---|
| 대상 행 수 | ~수십 건 | 수백 건 이상 |
| 값 계산에 자바 로직이 필요한가 | **필요하면 더티 체킹** | SQL 표현식으로 표현 가능할 때만 |
| 행마다 다른 값을 넣어야 하는가 | **그렇다면 더티 체킹** | 같은 규칙이면 벌크 |
| `@PreUpdate` / 엔티티 리스너가 필요한가 | 동작함 | **동작 안 함** |
| 도메인 이벤트를 발행해야 하는가 | 엔티티 안에서 가능 | 별도로 발행해야 함 |
| `@Version` 낙관적 락 | 자동 | **수동** |
| `cascade` / `orphanRemoval` | 동작함 | **무시됨** |
| `updated_at` 자동 갱신 (`@LastModifiedDate`) | 동작함 | **동작 안 함** — `set` 에 직접 |
| 1차 캐시 정합성 | 보장 | **`flush`+`clear` 필요** |

실무 판단은 대체로 이렇게 갈립니다.

**벌크가 맞는 경우**
- "전 상품 가격 N% 인상" — 규칙이 하나, 행이 많음
- "6개월 지난 로그 삭제" — 조건만 있고 로직 없음
- "재고 0인 상품 상태를 SOLD_OUT 으로" — 단순 상태 전이
- "휴면 계정 일괄 전환" — 배치 잡, 이벤트는 별도 집계로

**더티 체킹이 맞는 경우**
- "주문 취소" — 상태 전이 + 재고 복구 + 환불 + 이벤트 발행. 로직이 엔티티 안에 있음
- "포인트 적립" — 등급별 적립률이 다르고 상한이 있음. 행마다 값이 다름
- "리뷰 작성 시 상품 평점 재계산" — 몇 건 안 됨. 정합성이 중요

**둘 다 쓰는 경우**

배치에서 흔한 패턴입니다. 대량 갱신은 벌크로, 예외 케이스만 엔티티로.

```java
@Transactional
public BatchResult closeExpiredOrders(LocalDateTime cutoff) {
    em.flush();

    // ① 대량 상태 전이는 벌크로
    long closed = queryFactory
            .update(order)
            .set(order.status, OrderStatus.CANCELLED)
            .where(order.status.eq(OrderStatus.PENDING)
                    .and(order.orderDate.before(cutoff)))
            .execute();

    em.clear();

    // ② 환불이 필요한 소수 건만 엔티티로 (로직이 필요)
    List<Order> refundTargets = queryFactory
            .selectFrom(order)
            .where(order.status.eq(OrderStatus.CANCELLED)
                    .and(order.totalAmount.gt(new BigDecimal("100000"))))
            .fetch();
    refundTargets.forEach(Order::refund);   // 엔티티 메서드 = 도메인 로직

    return new BatchResult(closed, refundTargets.size());
}
```

**결과**

```sql
update orders set status=? where status=? and order_date<?
```
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
where o1_0.status=? and o1_0.total_amount>?
```
```
closed = 43,  refunded = 6
문장 수: UPDATE 1 + SELECT 1 + (커밋 시 더티 체킹 UPDATE 6) = 8
```

`em.clear()` 가 ① 과 ② 사이에 있는 것이 중요합니다.
없으면 ② 의 SELECT 가 옛 상태(`PENDING`)를 가진 캐시 엔티티를 돌려줍니다 (11-5-2).

> 💡 **실무 팁 — 벌크 연산은 로그를 남기십시오**
> 벌크 연산은 `@PreUpdate`, `@LastModifiedBy`, Envers 감사 로그가 전부 동작하지 않습니다.
> "누가 언제 몇 건을 바꿨는지"가 어디에도 안 남습니다.
> 벌크 메서드에는 **호출자·조건·영향 행 수를 애플리케이션 로그로 직접 남기십시오.**
> ```java
> log.info("bulk price raise: rate={}, status={}, affected={}", rate, status, updated);
> ```
> 사고가 났을 때 이 한 줄이 있느냐 없느냐가 복구 가능 여부를 가릅니다.

---

## 11-10. MySQL8 코스와 나란히

같은 작업을 SQL 로 쓰면 어떻게 되는지 대조합니다.
[Step 11 — DML](../../mysql8/step-11-dml/) 의 문장들입니다.

| 작업 | MySQL 8 SQL | QueryDSL 6 |
|---|---|---|
| 조건부 갱신 | `UPDATE products SET price=price*1.1 WHERE status='ON_SALE'` | `update(product).set(product.price, product.price.multiply(...)).where(...).execute()` |
| 다중 컬럼 갱신 | `SET price=..., stock=stock+10` | `.set(a, ...).set(b, ...)` 체이닝 |
| 조건부 삭제 | `DELETE FROM reviews WHERE rating < 2` | `delete(review).where(review.rating.lt(2)).execute()` |
| 조인 UPDATE | `UPDATE orders o JOIN customers c ON ... SET ...` | **불가** — JPQL 에 조인 UPDATE 없음. 서브쿼리로 우회 |
| `INSERT ... VALUES` | 지원 | **불가** — `em.persist` |
| `INSERT ... SELECT` | 지원 | **JPAQueryFactory API 로는 불가** — 네이티브 쿼리 |
| `UPSERT` | `ON DUPLICATE KEY UPDATE` | **불가** — 네이티브 쿼리 |
| `TRUNCATE` | 지원 | **불가** — 네이티브 쿼리 (DDL 이라 롤백 불가) |
| `REPLACE` | 지원 | **불가** |
| 영향 행 수 | `ROW_COUNT()` | `execute()` 반환값 |

**조인 UPDATE 가 안 되는 것**이 실무에서 가장 자주 부딪히는 제약입니다.

```sql
-- MySQL 에서는 이렇게 씁니다
UPDATE orders o
  JOIN customers c ON o.customer_id = c.customer_id
SET o.status = 'CANCELLED'
WHERE c.grade = 'BRONZE';
```

JPQL 에는 이 문법이 없습니다. **서브쿼리로 우회합니다.**

```java
long updated = queryFactory
        .update(order)
        .set(order.status, OrderStatus.CANCELLED)
        .where(order.customer.id.in(
                JPAExpressions.select(customer.id)
                        .from(customer)
                        .where(customer.grade.eq(Grade.BRONZE))))
        .execute();
```

**결과**

```sql
update orders set status=?
where customer_id in (select c1_0.customer_id from customers c1_0 where c1_0.grade=?)
```
```
바인딩: [1] CANCELLED  [2] BRONZE
반환값(updated): 178
```

결과는 같지만 실행 계획이 다를 수 있습니다.
MySQL 옵티마이저가 `IN (subquery)` 를 세미 조인으로 변환하는지는
**생성된 SQL 을 그대로 `EXPLAIN` 에 넣어 확인**하십시오
([Step 16 — EXPLAIN과 옵티마이저](../../mysql8/step-16-explain-optimizer/)).
QueryDSL 이 만든 SQL 이라고 해서 실행 계획을 안 봐도 되는 것은 아닙니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `update().set().where().execute()` | 한 문장으로 대량 갱신. 반환값은 **영향 행 수** |
| `delete().where().execute()` | `set` 만 없을 뿐 구조 동일 |
| `where` 생략 | **전체 행이 대상**. 컴파일도 실행도 정상 |
| `null` 조건 | 조회처럼 무시됨 → 벌크에서는 **전체 갱신**이 됨 |
| `stock.add(10)` | 경로 기반 표현식 → `stock=stock+?`. 자바 값 연산과 구별 |
| `BigDecimal` | 반드시 `new BigDecimal("1.1")`. `double` 금지 |
| **영속성 컨텍스트 우회** | 벌크는 DB 로 직행. 1차 캐시는 모름 |
| `em.find` 옛 값 | 캐시 히트 → **SQL 이 아예 안 나감** |
| `fetch()` 옛 값 | SQL 은 나가지만 **동일성 보장** 때문에 캐시 값 반환 |
| 더티 체킹 덮어쓰기 | 옛 값 엔티티를 수정하면 벌크 결과가 **사라짐** |
| 처방 | `em.flush()` **다음에** `em.clear()`. 순서 중요 |
| `@Modifying` | `flushAutomatically` / `clearAutomatically` — **둘 다 기본 false** |
| `@Version` | 벌크는 버전을 안 올림 → `set(version, version.add(1))` 수동 |
| `cascade` / `orphanRemoval` | 벌크 DELETE 에서 **무시됨** |
| DB `ON DELETE CASCADE` | 벌크에서도 **동작함**. 단 1차 캐시는 안 치움 |
| FK `RESTRICT` | 자식부터 지워야 함. 순서를 사람이 책임짐 |
| `insert` | **QueryDSL-JPA 에 없음**. `em.persist` / batch / `JdbcTemplate` |
| `IDENTITY` 전략 | batch insert 가 **조용히 무효화**됨 |
| 감사 로그 | 벌크는 `@PreUpdate` / Envers 미동작 → 애플리케이션 로그 필수 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `HIDDEN` 상태이면서 재고가 0인 상품의 상태를 `SOLD_OUT` 으로 바꾸는 벌크 UPDATE 를 작성하고,
   영향 행 수와 생성 SQL 을 확인하십시오.
2. `GOLD` 등급 고객의 포인트를 **현재 값의 2배 + 1000** 으로 만드는 벌크 UPDATE 를 작성하십시오.
   자바에서 값을 계산하지 말고 **경로 표현식만** 쓰십시오.
3. `em.find(Product.class, 1L)` → 벌크 UPDATE → `em.find(Product.class, 1L)` 순서로 실행했을 때
   두 번째 `find` 에서 **SELECT 가 나가지 않는 것**을 로그로 확인하고, 그 이유를 한 문장으로 쓰십시오.
4. 3번 코드에 `em.flush()` / `em.clear()` 를 넣어 새 값이 나오게 고치십시오.
   `flush` 없이 `clear` 만 했을 때 무엇이 소실되는지도 코드로 재현하십시오.
5. 상품 하나를 벌크로 삭제할 때 FK 제약 위반이 나는 상황을 재현하고,
   자식부터 지우는 순서로 고쳐 3문장으로 완료하십시오. 각 단계의 영향 행 수를 출력하십시오.
6. 다음 코드의 문제를 찾아 고치십시오. 예외는 나지 않지만 결과가 틀립니다.
   ```java
   Product p = em.find(Product.class, 1L);
   queryFactory.update(product)
           .set(product.stock, p.getStock() + 10)
           .where(product.status.eq(ProductStatus.ON_SALE))
           .execute();
   ```
7. `PENDING` 상태이고 2024년에 만들어진 주문을 벌크로 `CANCELLED` 로 바꾼 뒤,
   같은 트랜잭션에서 `CANCELLED` 주문을 조회해 개수를 세십시오.
   **`clear()` 를 넣기 전과 후의 개수 차이**를 설명하십시오.

---

## 다음 단계

벌크 연산까지 오면서 QueryDSL 문법은 대부분 다뤘습니다.
그런데 지금까지의 코드는 전부 테스트 클래스 안에서 `queryFactory` 를 직접 쓰는 형태였습니다.
실제 애플리케이션에서는 이 쿼리들이 **리포지토리 계층**에 들어가야 하고,
그 리포지토리는 Spring Data JPA 의 `JpaRepository` 와 공존해야 합니다.

다음 스텝에서는 그 통합 방법 세 가지를 비교하고,
그중 가장 널리 쓰이는 커스텀 리포지토리 방식에서
**클래스 이름 접미사 하나 때문에 애플리케이션이 기동하지 않는** 함정을 다룹니다.
에러 메시지가 원인을 전혀 가리키지 않는 종류의 함정입니다.

→ [Step 12 — Spring Data JPA 통합](../step-12-spring-data/)

---

## 실습 파일

세 파일은 `src/test/java/com/example/shop/step11/` 에 그대로 복사해 넣으면 실행됩니다.
**모두 `@Transactional` 이 붙어 있어 테스트 종료 시 롤백됩니다.**
`@Rollback(false)` 나 `@Commit` 을 추가하지 마십시오.

먼저 `Practice.java` 를 실행해 콘솔의 SQL 로그를 교재와 한 줄씩 대조하십시오.
특히 11-5 계열 메서드는 **SQL 이 찍히지 않는 것**을 확인해야 합니다.
"아무것도 안 나오는 것"이 결과인 유일한 스텝입니다.

### Practice.java

- `[11-1]` 더티 체킹 40회 vs 벌크 1회 — 문장 수를 세어 출력합니다
- `[11-2]` `update/set/where/execute` 기본형과 `where` 누락 시나리오
- `[11-3]` `set` 체이닝, 경로 표현식 vs 자바 값 연산의 차이
- `[11-4]` `delete` 기본형과 서브쿼리 조건
- `[11-5]` **영속성 컨텍스트 우회 3종 재현** — `em.find` 캐시 히트 / `fetch()` 동일성 보장 / 더티 체킹 덮어쓰기
- `[11-5-4]` `flush()` → `clear()` 처방 적용 전후 대조
- `[11-7]` `cascade` 무시와 FK 제약 위반, 자식부터 지우는 순서
- `[11-9]` 벌크 + 더티 체킹 혼합 배치 패턴

```java file="./Practice.java"
```

### Exercise.java

7문제입니다. 각 문제에는 요구사항과 `// 여기에 작성:` 자리가 있습니다.
3번과 7번은 **로그를 보고 설명을 쓰는** 문제입니다. 코드만 채우고 넘어가지 마십시오.

```java file="./Exercise.java"
```

### Solution.java

정답과 함께, 왜 그 답인지를 설명하는 주석이 문제마다 붙어 있습니다.
특히 3·4·7번은 생성 SQL 이 어떻게 달라지는지를 주석에 적어 두었습니다.
답이 같아도 **생성 SQL 이 다르면 틀린 것**입니다.

```java file="./Solution.java"
```
