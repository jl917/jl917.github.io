# Step 03 — 기본 조회

> **학습 목표**
> - `select` / `from` / `where` / `fetch` 로 이루어진 QueryDSL 쿼리의 뼈대를 그리고, `selectFrom` 이 무엇의 축약인지 설명한다
> - 엔티티 전체 조회와 특정 컬럼 조회가 만들어 내는 SQL 의 차이를 로그로 대조한다
> - `fetch()` / `fetchOne()` / `fetchFirst()` / `fetchResults()` / `fetchCount()` 의 반환 타입·실행 SQL·예외 조건을 구분한다
> - `fetchOne()` 이 2건 이상에서 `NonUniqueResultException` 을 던지고 0건에서는 `null` 을 돌려준다는 비대칭을 직접 재현한다
> - `fetchCount()` / `fetchResults()` 가 deprecated 된 이유를 count 쿼리 자동 생성의 한계로 설명하고, 직접 작성한 count 쿼리로 대체한다
> - `fetch()` 를 호출하기 전까지는 SQL 이 한 줄도 나가지 않는다는 지연 실행 성질을 확인한다
>
> **선행 스텝**: [Step 02 — Q타입의 정체](../step-02-qtype/)
> **예상 소요**: 90분

---

## 3-0. 실습 준비

이 스텝부터는 모든 예제가 `JPAQueryFactory` 하나로 시작합니다. [프로젝트 셋업](../project/) 에서 만든 설정입니다.

```java
@Configuration
public class QuerydslConfig {
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }
}
```

그리고 본문의 모든 코드는 **Q타입을 static import** 한 상태를 전제합니다. Step 02 에서 본 `QCustomer.customer` 같은 기본 인스턴스를 이름만으로 쓰기 위해서입니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QOrder.order;
```

앞으로 본문에 `customer` 라고만 적혀 있으면 그것은 `QCustomer.customer` 입니다.

생성 SQL 을 보려면 로그 설정이 켜져 있어야 합니다. 이 코스는 처음부터 끝까지 **"QueryDSL 코드 → 생성 SQL"** 을 대조하며 진행하므로, 이 설정 없이는 학습이 성립하지 않습니다.

```yaml
# application.yml
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace   # Hibernate 6 의 바인딩 파라미터 로거
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
```

> 💡 **실무 팁 — Hibernate 6 에서 바인딩 로거 이름이 바뀌었습니다**
> Hibernate 5 에서는 `org.hibernate.type.descriptor.sql.BasicBinder` 였습니다.
> Hibernate 6.4 는 `org.hibernate.orm.jdbc.bind` 입니다. 5.x 시절 설정을 그대로 복사하면
> SQL 은 찍히는데 `?` 에 무엇이 들어갔는지는 영원히 안 보입니다.

실습 데이터는 MySQL8 코스와 같은 `shop` 스키마입니다. 이 스텝에서 반복해서 쓸 숫자만 미리 정리합니다.

| 테이블 | 행 수 | 이 스텝에서 쓰는 분포 |
|---|---|---|
| `customers` | 30 | VIP 4 / GOLD 9 / SILVER 8 / BRONZE 9, `phone` NULL 3명 |
| `products` | 40 | ON_SALE 위주, 가격 15,900 ~ 2,190,000 |
| `orders` | 600 | PENDING 60 / 나머지 540 |

---

## 3-1. 쿼리의 뼈대 — select / from / where / fetch

QueryDSL 쿼리는 네 조각으로 이루어집니다.

```
queryFactory
    .select( 무엇을 )
    .from  ( 어디서 )
    .where ( 어떤 조건으로 )
    .fetch ( 실행! )
```

SQL 을 쓸 줄 안다면 이미 다 아는 구조입니다. 다른 점은 `from` 이 `select` **뒤에** 온다는 것뿐입니다. JPQL 과 같은 순서이고, IDE 자동완성이 `select` 로 정한 타입을 그대로 끌고 가기 때문에 이 순서가 유리합니다.

```java
List<Customer> result = queryFactory
        .select(customer)
        .from(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.VIP
조회 4건 — 김서준, 류하나, 정  훈, 배채영
```

별칭 `c1_0` 은 Hibernate 6 이 붙인 것입니다. `<엔티티 첫 글자><인덱스>_<서브인덱스>` 규칙이고, `as` 키워드 없이 붙습니다. Hibernate 5 의 `customer0_` 와 형태가 다르므로, 인터넷에서 찾은 5.x 시절 로그와 비교할 때 헷갈리지 마십시오.

### selectFrom 은 축약입니다

`select` 와 `from` 에 같은 것이 들어가는 경우가 압도적으로 많습니다. 그래서 축약형이 있습니다.

```java
// 아래 둘은 완전히 같습니다
queryFactory.select(customer).from(customer)
queryFactory.selectFrom(customer)
```

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.VIP
조회 4건 — 김서준, 류하나, 정  훈, 배채영
```

**글자 하나 다르지 않은 같은 SQL** 입니다. `selectFrom` 은 순수한 문법 설탕이고, 성능 차이도 동작 차이도 없습니다.

다만 `select` 와 `from` 이 달라지는 순간(특정 컬럼만 뽑거나, 조인의 주체가 바뀌는 순간)에는 축약을 쓸 수 없습니다. 그때는 다시 풀어서 씁니다.

---

## 3-2. 컬럼 선택 — 엔티티 전체 vs 특정 컬럼

`select` 에 엔티티를 넣으면 그 엔티티의 **모든 컬럼**이 SQL 에 나열됩니다. 필드 하나만 필요해도 마찬가지입니다.

```java
List<String> names = queryFactory
        .select(customer.name)
        .from(customer)
        .where(customer.city.eq("서울"))
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.name
from customers c1_0
where c1_0.city = ?
```
```
바인딩: [1] '서울'
조회 8건 — 김서준, 류하나, 안지수, 한지호, 오하윤, 문시우, 최유진, 백승호
```

반환 타입이 `List<Customer>` 가 아니라 `List<String>` 입니다. `customer.name` 이 `StringPath` 이므로 컴파일러가 `String` 임을 알고 있습니다. Step 02 에서 본 Q타입 필드의 정적 타입이 여기서 값을 합니다.

같은 조회를 나란히 놓고 보겠습니다.

| 코드 | 생성 SQL 의 select 절 | 반환 타입 |
|---|---|---|
| `selectFrom(customer)` | `c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points` | `List<Customer>` |
| `select(customer.name)` | `c1_0.name` | `List<String>` |
| `select(customer.name, customer.points)` | `c1_0.name, c1_0.points` | `List<Tuple>` |

세 번째 줄, **컬럼을 두 개 이상 고르면 반환 타입이 `Tuple`** 이 됩니다.

```java
List<Tuple> rows = queryFactory
        .select(customer.name, customer.points)
        .from(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();

for (Tuple row : rows) {
    String name = row.get(customer.name);
    Integer points = row.get(customer.points);
    System.out.println(name + " / " + points);
}
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.name, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.VIP
조회 4건
김서준 / 48200
류하나 / 39100
정  훈 / 41500
배채영 / 52000
```

`Tuple` 은 "타입 없는 여러 값 묶음"입니다. 꺼낼 때 `row.get(customer.name)` 처럼 **꺼낼 때 쓴 표현식으로 다시 지정**해야 하고, 그래야 타입이 살아납니다. 편하긴 하지만 `Tuple` 을 서비스 계층 밖으로 흘려보내면 곧바로 유지보수 부채가 됩니다. 그래서 실무에서는 DTO 로 바로 받습니다. 그게 [Step 05 — 프로젝션과 DTO](../step-05-projections/) 의 주제입니다.

> 💡 **실무 팁 — 엔티티 전체 조회를 기본값으로 삼지 마십시오**
> `selectFrom(customer)` 은 편하지만 항상 전 컬럼을 읽습니다. `TEXT` 나 `JSON` 처럼 큰 컬럼이 섞인 테이블에서
> 이름 하나 보려고 전체를 읽는 것은 낭비입니다. 다만 **영속성 컨텍스트에 올려 변경 감지를 쓰려면 엔티티여야** 합니다.
> 읽기 전용 조회는 컬럼/DTO, 수정할 대상은 엔티티 — 이 기준이 실무에서 가장 오래 갑니다.

---

## 3-3. fetch 계열 완전 정리

`fetch` 로 시작하는 메서드가 다섯 개 있습니다. **어느 것을 부르느냐에 따라 나가는 SQL 이 달라집니다.** 이름이 비슷해서 대충 고르기 쉬운데, 그게 이 스텝의 첫 번째 함정입니다.

| 메서드 | 반환 타입 | 실행되는 SQL | 결과가 0건 | 결과가 2건 이상 |
|---|---|---|---|---|
| `fetch()` | `List<T>` | 작성한 쿼리 그대로 | 빈 리스트 | 전부 반환 |
| `fetchOne()` | `T` (단건) | 작성한 쿼리 그대로 | **`null` 반환** | **`NonUniqueResultException`** |
| `fetchFirst()` | `T` (단건) | 뒤에 `limit ?` 추가 | `null` 반환 | 첫 행만 반환 (예외 없음) |
| `fetchResults()` | `QueryResults<T>` | **count 쿼리 + 본 쿼리 2회** | 빈 결과 | 전부 + 총 개수 | 
| `fetchCount()` | `long` | select 절을 `count(*)` 로 바꿔 1회 | `0` | 개수 |

`fetchResults()` 와 `fetchCount()` 는 **deprecated** 입니다. 이유는 3-6 에서 따로 다룹니다.

먼저 정상 케이스부터 확인합니다.

```java
// (1) fetch — 여러 건
List<Customer> list = queryFactory
        .selectFrom(customer)
        .where(customer.city.eq("부산"))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.city = ?
```
```
바인딩: [1] '부산'
조회 6건 — 정  훈, 강도윤, 윤서아, 남기훈, 서예린, 홍지훈
```

```java
// (2) fetchOne — 단건. email 은 UNIQUE 이므로 안전합니다
Customer one = queryFactory
        .selectFrom(customer)
        .where(customer.email.eq("seojun.kim@example.com"))
        .fetchOne();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.email = ?
```
```
바인딩: [1] 'seojun.kim@example.com'
조회 1건 — 김서준 (서울, VIP, 48200p)
```

여기서 중요한 것은 **`fetchOne()` 이 SQL 에 `limit 1` 을 붙이지 않는다**는 사실입니다. 위 로그에 `limit` 이 없는 것을 확인하십시오. QueryDSL 은 조건에 맞는 행을 전부 가져온 뒤, **자바 쪽에서 개수를 세어** 2건 이상이면 예외를 던집니다.

이 설계는 의도적입니다. "단건이어야 한다"는 가정이 깨진 것을 조용히 넘기지 않고 **터뜨리기** 위해서입니다. 문제는 그 반대편입니다.

---

## 3-4. fetchOne() 이 2건이면 예외 — 그리고 0건이면 null

이 절이 Step 03 의 핵심입니다.

### 2건 이상이면 예외

GOLD 등급 고객은 9명입니다. 여기에 `fetchOne()` 을 부르면 어떻게 될까요.

```java
Customer gold = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.GOLD))
        .fetchOne();          // 9건인데 fetchOne
```

**결과** — SQL 은 정상적으로 나갑니다
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.GOLD
DB 는 9건을 정상 반환 → QueryDSL 이 개수를 세고 예외로 전환
```

```
com.querydsl.core.NonUniqueResultException: Only one result is allowed for fetchOne calls
	at com.querydsl.jpa.impl.AbstractJPAQuery.fetchOne(AbstractJPAQuery.java:275)
	at com.example.shop.step03.Practice.fetchOne_두건이상이면_예외(Practice.java:112)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:728)
	...
Caused by: jakarta.persistence.NonUniqueResultException: Query did not return a unique result: 9 results were returned
	at org.hibernate.query.spi.AbstractSelectionQuery.uniqueElement(AbstractSelectionQuery.java:504)
	at org.hibernate.query.spi.AbstractSelectionQuery.getSingleResult(AbstractSelectionQuery.java:475)
	at com.querydsl.jpa.impl.AbstractJPAQuery.fetchOne(AbstractJPAQuery.java:271)
	... 62 more
```

읽을 것이 두 가지 있습니다.

1. 겉에서 잡히는 것은 **`com.querydsl.core.NonUniqueResultException`** 입니다. QueryDSL 6 은 그룹 아이디만 `io.github.openfeign.querydsl` 로 바뀌었고 **패키지 이름은 `com.querydsl` 그대로** 입니다. import 문은 5.x 와 동일합니다.
2. 원인은 Hibernate 가 던진 **`jakarta.persistence.NonUniqueResultException`** 이고, 메시지에 `9 results were returned` 이라고 **실제 건수가 찍힙니다.** 장애 분석 때 이 숫자가 결정적인 단서입니다.

이름이 같은 예외가 두 패키지에 있다는 것에 주의하십시오. `catch (NonUniqueResultException e)` 를 쓸 때 import 를 잘못하면 잡히지 않습니다.

```java
import com.querydsl.core.NonUniqueResultException;      // QueryDSL 이 던지는 것 (잡아야 할 것)
// import jakarta.persistence.NonUniqueResultException; // Hibernate 가 던지는 것 (원인)
```

### 그런데 0건이면 예외가 아닙니다

같은 `fetchOne()` 인데, 조건에 맞는 행이 **하나도 없으면 예외가 아니라 `null`** 을 돌려줍니다.

```java
Customer nobody = queryFactory
        .selectFrom(customer)
        .where(customer.email.eq("nobody@example.com"))
        .fetchOne();

System.out.println(nobody);      // null
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.email = ?
```
```
바인딩: [1] 'nobody@example.com'
조회 0건 → 반환값 null (예외 없음)
```

`fetchOne()` 의 동작을 정리하면 **비대칭**입니다.

| 결과 건수 | `fetchOne()` 의 반응 |
|---|---|
| 0건 | `null` 반환 — **조용히 통과** |
| 1건 | 그 객체 반환 |
| 2건 이상 | `NonUniqueResultException` — 요란하게 실패 |

> ⚠️ **함정 — 0건의 null 이 진짜 위험합니다**
> 2건 예외는 스택트레이스가 남고, 테스트에서 곧바로 드러나고, 로그에 `9 results were returned` 까지 찍힙니다.
> **고치기 쉬운 실패**입니다.
> 반면 0건의 `null` 은 아무 흔적도 남기지 않습니다. 그리고 다음 줄에서 터집니다.
>
> ```java
> Customer c = queryFactory.selectFrom(customer)
>         .where(customer.email.eq(inputEmail))
>         .fetchOne();
> return c.getName();       // ← NullPointerException. 여기가 범인처럼 보입니다
> ```
>
> 스택트레이스는 `c.getName()` 을 가리키지만 진짜 원인은 **"입력한 이메일이 DB 에 없다"** 는 것입니다.
> 조회 코드와 NPE 발생 지점이 멀리 떨어져 있으면(서비스 → 컨트롤러 → 뷰) 원인 추적이 몇 시간짜리 일이 됩니다.
> 처방은 3-9 의 `Optional` 관례입니다.

---

## 3-5. fetchFirst() = limit(1).fetchOne()

"여러 건 나올 수도 있지만 아무거나 하나면 된다"는 상황이 있습니다. 그럴 때 `fetchOne()` 을 쓰면 예외가 납니다. `fetchFirst()` 가 그 자리를 채웁니다.

```java
Customer any = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.GOLD))
        .fetchFirst();          // 9건이지만 예외 없음
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
limit ?
```
```
바인딩: [1] Grade.GOLD  [2] 1
조회 1건 — 안지수 (서울, GOLD, 21800p)
```

**SQL 끝에 `limit ?` 가 붙었습니다.** 3-4 의 `fetchOne()` 로그에는 없던 줄입니다. `fetchFirst()` 는 내부적으로 정확히 `limit(1).fetchOne()` 입니다. DB 가 1건만 돌려주니 QueryDSL 이 셀 것도 없고, 따라서 예외가 날 수 없습니다.

| | `fetchOne()` | `fetchFirst()` |
|---|---|---|
| 생성 SQL | `limit` 없음 | `limit ?` (값 1) |
| 0건 | `null` | `null` |
| 1건 | 객체 | 객체 |
| 2건 이상 | `NonUniqueResultException` | 첫 행 반환 |
| DB → 앱 전송량 | 조건에 맞는 **전 행** | 1행 |
| 쓰는 상황 | UNIQUE 제약이 보장하는 조회 | 정렬 후 1등, 존재 확인, 샘플 하나 |

선택 기준은 단순합니다.

- **"두 건이 나오면 그건 데이터가 잘못된 것"** → `fetchOne()`. 예외로 잡히는 편이 낫습니다.
- **"두 건이 나오는 게 정상이고, 하나만 필요"** → `fetchFirst()`.

두 번째 경우에는 **거의 항상 `orderBy` 가 함께 있어야** 합니다. 정렬 없이 `limit 1` 이면 어떤 행이 올지는 DB 마음입니다.

```java
// 포인트가 가장 많은 고객 1명
Customer top = queryFactory
        .selectFrom(customer)
        .orderBy(customer.points.desc())
        .fetchFirst();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.points desc
limit ?
```
```
바인딩: [1] 1
조회 1건 — 배채영 (대구, VIP, 52000p)
```

> ⚠️ **함정 — 정렬 없는 `fetchFirst()`**
> `orderBy` 없이 `fetchFirst()` 를 쓰면 "매번 같은 행이 나오는 것처럼 보이다가" 데이터가 늘거나
> 실행 계획이 바뀌는 순간 다른 행이 나옵니다. 개발 환경에서는 잘 되고 운영에서만 틀어지는 전형적인 경로입니다.
> `fetchFirst()` 를 쓸 때는 **정렬 기준이 무엇인지 반드시 코드에 적으십시오.**

---

## 3-6. fetchCount() / fetchResults() 가 deprecated 인 이유

이 두 메서드는 QueryDSL 5.0.0 에서 deprecated 로 표시됐고 6.x 에서도 그대로 남아 있습니다. 컴파일은 되고 동작도 합니다. **그래서 더 위험합니다.**

```java
long total = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.GOLD))
        .fetchCount();            // ⚠️ deprecated
```

**결과**
```sql
select count(c1_0.customer_id)
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.GOLD
결과: 9
```

이 단순한 경우에는 잘 됩니다. 문제는 **QueryDSL 이 count 쿼리를 만드는 방식**입니다.

`fetchCount()` 는 여러분이 작성한 쿼리를 받아서 **`select` 절을 기계적으로 `count(...)` 로 갈아 끼웁니다.** 나머지(`from`, `join`, `group by`, `having`, `distinct`)는 그대로 둡니다. 단순 쿼리에서는 맞는 변환이지만, 아래 세 경우에서 무너집니다.

**(1) `group by` 가 있는 쿼리**

원 쿼리가 "도시별 고객 수"라면 결과는 6행(도시 6개)입니다. 그런데 `select` 절만 `count` 로 바꾸면 SQL 은 "각 그룹의 행 수"를 그룹마다 돌려주는 쿼리가 됩니다. 원하는 것은 "그룹이 몇 개냐"인데, 나오는 것은 "각 그룹에 몇 행이냐"입니다. 의미가 완전히 다릅니다. 이 상태에서 단건을 기대하면 그대로 `NonUniqueResultException` 이 납니다.

**(2) `distinct` 가 붙은 쿼리**

`select distinct c1_0.city` 의 결과는 6행입니다. select 절만 갈아 끼우면 `select count(c1_0.city)` 가 되어 **distinct 가 사라진 30** 이 나옵니다. 예외도 안 나고 숫자만 틀립니다.

**(3) 다중 조인 + 페치 조인**

컬렉션을 조인하면 행이 뻥튀기됩니다. count 를 그 위에서 세면 "주문 건수"가 아니라 "주문 상세 건수"가 나옵니다. 게다가 `fetch join` 이 섞인 쿼리를 count 로 바꾸면 Hibernate 6 에서 아예 예외가 납니다.

거기에 더해 `fetchResults()` 는 **쿼리를 두 번 날립니다.** count 쿼리와 본 쿼리를 항상 쌍으로 실행합니다. 페이지 1의 데이터가 20건뿐이라 count 가 필요 없는 상황에서도 무조건 셉니다.

### 대안 — count 쿼리를 직접 작성한다

```java
Long total = queryFactory
        .select(customer.count())
        .from(customer)
        .where(customer.grade.eq(Grade.GOLD))
        .fetchOne();
```

**결과**
```sql
select count(c1_0.customer_id)
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.GOLD
결과: 9
```

생성 SQL 이 `fetchCount()` 와 **동일합니다.** 차이는 "누가 그 SQL 을 결정했는가" 하나입니다. 직접 쓰면 group by 를 어떻게 처리할지, 조인을 뺄지 말지를 **여러분이** 정합니다.

`customer.count()` 는 PK 기준 count 입니다. `select(Wildcard.count)` 를 쓰면 `count(*)` 가 나갑니다.

```java
Long total = queryFactory
        .select(Wildcard.count)          // com.querydsl.core.types.dsl.Wildcard
        .from(customer)
        .fetchOne();
```

**결과**
```sql
select count(*)
from customers c1_0
```
```
결과: 30
```

그리고 count 쿼리를 직접 쓰면 **본 쿼리에는 필요하지만 count 에는 불필요한 조인을 뺄 수 있습니다.** 이것이 실무에서 체감되는 가장 큰 이득입니다. 600건짜리 주문 목록에서 count 를 낼 때 상품 조인을 빼면 그만큼 빨라집니다.

| | `fetchCount()` | 직접 작성 |
|---|---|---|
| count SQL 결정권 | QueryDSL (기계적 치환) | 개발자 |
| group by / distinct | 틀린 결과 또는 예외 | 개발자가 의도대로 |
| 불필요한 조인 제거 | 불가능 | 가능 |
| count 생략 최적화 | 불가능 (항상 실행) | 가능 |
| 상태 | deprecated | 권장 |

> 📌 페이징에서 "count 를 아예 안 날리는" 최적화는 Spring Data 의 `PageableExecutionUtils` 가 해 줍니다.
> 전체 페이지가 하나뿐이거나 마지막 페이지면 count 쿼리를 건너뜁니다.
> [Step 09 — 정렬과 페이징](../step-09-sorting-paging/) 에서 이 조합을 다룹니다.

> ⚠️ **함정 — deprecated 경고는 빌드를 막지 않습니다**
> `fetchCount()` 는 노란 줄이 그어질 뿐 컴파일이 실패하지 않습니다. 레거시 코드에서 이 호출이
> 수십 군데 남아 있는 경우가 흔하고, **그중 group by 나 distinct 가 섞인 몇 개만 조용히 틀린 숫자**를 내놓습니다.
> 마이그레이션할 때는 "전부 바꾸기"보다 **group by / distinct / 컬렉션 조인이 있는 쿼리부터** 찾아 고치십시오.

---

## 3-7. distinct()

중복을 제거합니다. `select` 뒤 어디에 붙여도 됩니다.

```java
List<String> cities = queryFactory
        .select(customer.city)
        .distinct()
        .from(customer)
        .fetch();
```

**결과**
```sql
select distinct c1_0.city
from customers c1_0
```
```
조회 6건 — 서울, 부산, 대구, 인천, 광주, 대전
```

`distinct()` 없이 같은 쿼리를 돌리면 30건이 나옵니다. 도시 이름이 중복해서 그대로 실립니다.

```java
List<String> cities = queryFactory
        .select(customer.city)
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.city
from customers c1_0
```
```
조회 30건 — 서울, 서울, 부산, 서울, 대구, 인천, ... (중복 포함)
```

`selectDistinct(...)` 라는 축약형도 있습니다. `select(...).distinct()` 와 같습니다.

```java
List<String> cities = queryFactory
        .selectDistinct(customer.city)
        .from(customer)
        .fetch();
```

> 📌 MySQL8 코스 [Step 04 — SELECT 기본](../../mysql8/step-04-select-basics/) 의 `SELECT DISTINCT city FROM customers` 와 같은 쿼리입니다.

> ⚠️ **함정 — 컬렉션 조인 + distinct 는 다른 이야기입니다**
> `selectFrom(order).join(order.orderItems)` 처럼 일대다를 조인하면 주문이 상세 개수만큼 중복됩니다.
> 여기에 `distinct()` 를 붙이면 SQL 에 `distinct` 가 붙지만, **엔티티 중복 제거는 SQL 이 아니라 JPA 가 처리**합니다.
> Hibernate 6 부터는 이 경우 애플리케이션 쪽 중복 제거가 기본 동작이라 `distinct()` 없이도 엔티티가 중복되지 않습니다.
> 이 주제는 [Step 06 — 조인](../step-06-joins/) 에서 정리합니다. 여기서는 "단일 컬럼 distinct" 만 다룹니다.

---

## 3-8. limit / offset 맛보기

상세는 [Step 09 — 정렬과 페이징](../step-09-sorting-paging/) 에서 다루지만, 조회 결과가 30건씩 쏟아지면 실습이 불편하니 여기서 최소한만 익힙니다.

```java
List<Customer> page = queryFactory
        .selectFrom(customer)
        .orderBy(customer.points.desc())
        .offset(0)
        .limit(5)
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.points desc
limit ?
```
```
바인딩: [1] 5
조회 5건 — 배채영(52000), 김서준(48200), 정  훈(41500), 류하나(39100), 안지수(21800)
```

`offset(0)` 은 SQL 에 나타나지 않습니다. 0 은 기본값이라 Hibernate 가 생략합니다. `offset(5)` 로 바꾸면 달라집니다.

```java
List<Customer> page2 = queryFactory
        .selectFrom(customer)
        .orderBy(customer.points.desc())
        .offset(5)
        .limit(5)
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.points desc
limit ?, ?
```
```
바인딩: [1] 5  [2] 5
조회 5건 — 한지호(19400), 오하윤(18800), 강도윤(17600), 문시우(16200), 윤서아(15100)
```

MySQL 방언이라 `limit ?, ?` (offset, count 순) 형태입니다. PostgreSQL 이면 `limit ? offset ?` 로 나갑니다. **같은 QueryDSL 코드가 DB 방언에 따라 다른 SQL 이 되는** 첫 사례입니다.

---

## 3-9. Optional 로 감싸기

3-4 에서 본 대로 `fetchOne()` 의 `null` 은 조용합니다. 그래서 실무 코드베이스는 거의 예외 없이 **저장소 메서드가 `Optional` 을 반환하도록** 관례를 정합니다.

```java
public Optional<Customer> findByEmail(String email) {
    return Optional.ofNullable(
            queryFactory
                    .selectFrom(customer)
                    .where(customer.email.eq(email))
                    .fetchOne()
    );
}
```

호출부는 이렇게 됩니다.

```java
Customer c = customerRepository.findByEmail(email)
        .orElseThrow(() -> new CustomerNotFoundException(email));
```

차이는 **NPE 가 나는 지점이 아니라, 없을 때 무엇을 할지 결정하도록 컴파일러가 강제한다**는 것입니다. `Optional` 을 받으면 `orElseThrow` 든 `orElse` 든 `ifPresent` 든 뭔가를 써야 하고, 그 순간 "없으면 어떡하지"를 생각하게 됩니다.

```java
// null 반환 — 호출부가 검사를 잊어도 컴파일됨
Customer c = repo.findByEmail(email);
return c.getName();                       // 런타임에 NPE

// Optional 반환 — 컴파일러가 처리를 요구함
Customer c = repo.findByEmail(email).orElseThrow();
return c.getName();                       // 여기서 NPE 날 수 없음
```

> 💡 **실무 팁 — `Optional` 은 반환 타입에만 쓰십시오**
> 필드, 파라미터, 컬렉션 원소로 `Optional` 을 쓰지 않는 것이 자바 진영의 합의입니다.
> 그리고 `Optional.get()` 은 검사 없이 쓰면 `null` 과 다를 바 없습니다. `orElseThrow()` 를 습관으로 삼으십시오.

`fetch()` 는 이 문제가 없습니다. **결과가 없으면 빈 리스트를 돌려주지 `null` 을 돌려주지 않습니다.** 그래서 `List` 반환 메서드에는 `Optional` 을 씌우지 않습니다.

```java
List<Customer> none = queryFactory
        .selectFrom(customer)
        .where(customer.city.eq("제주"))
        .fetch();

System.out.println(none == null);      // false
System.out.println(none.size());       // 0
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.city = ?
```
```
바인딩: [1] '제주'
조회 0건 → 빈 List (null 아님)
```

---

## 3-10. MySQL8 코스와 나란히

> 📌 MySQL8 코스 [Step 04 — SELECT 기본](../../mysql8/step-04-select-basics/) 에서 손으로 썼던 SQL 들입니다. 같은 조회를 QueryDSL 로 옮기면 이렇게 됩니다.

| # | SQL | QueryDSL |
|---|---|---|
| 1 | `SELECT * FROM customers` | `queryFactory.selectFrom(customer).fetch()` |
| 2 | `SELECT name, points FROM customers` | `queryFactory.select(customer.name, customer.points).from(customer).fetch()` |
| 3 | `SELECT DISTINCT city FROM customers` | `queryFactory.select(customer.city).distinct().from(customer).fetch()` |
| 4 | `SELECT * FROM customers WHERE grade = 'VIP'` | `queryFactory.selectFrom(customer).where(customer.grade.eq(Grade.VIP)).fetch()` |
| 5 | `SELECT COUNT(*) FROM customers` | `queryFactory.select(Wildcard.count).from(customer).fetchOne()` |

세 가지를 짚습니다.

**첫째, `SELECT *` 에 대응하는 것이 `selectFrom(entity)` 입니다.** 다만 SQL 의 `*` 는 문자 그대로 별표가 나가지만, QueryDSL 은 매핑된 컬럼을 **전부 나열**합니다. `@Transient` 필드는 빠지고, 나중에 컬럼이 추가돼도 엔티티에 매핑하지 않으면 select 절에 안 들어갑니다. `SELECT *` 보다 안전합니다.

**둘째, `'VIP'` 라는 문자열이 `Grade.VIP` 라는 enum 이 됐습니다.** SQL 에서는 오타 `'VIPP'` 가 실행 시점에야 "0건"으로 드러납니다. QueryDSL 에서는 `Grade.VIPP` 가 **컴파일 에러**입니다. Step 02 에서 말한 "컴파일 시점으로 당긴다"가 여기서 처음 실제 이득이 됩니다.

**셋째, `COUNT(*)` 는 `fetchOne()` 으로 받습니다.** 결과가 항상 1행이므로 `fetch()` 로 받아 `get(0)` 하는 것보다 명확합니다.

---

## 3-11. 함정 두 가지

### (1) from 을 빼먹으면

```java
List<Customer> result = queryFactory
        .select(customer)
        // .from(customer)  ← 빠졌습니다
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

**컴파일은 됩니다.** `select(...)` 가 돌려주는 `JPAQuery` 에 `where` 도 `fetch` 도 다 있으니까요. 실행하면 쿼리 생성 단계에서 터집니다.

```
java.lang.IllegalArgumentException: No sources given
	at com.querydsl.jpa.JPAQueryMixin.addProjection(JPAQueryMixin.java:...)
	at com.querydsl.jpa.impl.AbstractJPAQuery.createQuery(AbstractJPAQuery.java:...)
	at com.querydsl.jpa.impl.AbstractJPAQuery.fetch(AbstractJPAQuery.java:...)
```

`from` 절이 없으니 JPQL 을 만들 수가 없습니다. 다행히 **곧바로 터지는 실패**라 위험하지 않습니다. `selectFrom` 축약형을 쓰면 애초에 이 실수를 할 수 없습니다.

### (2) fetch() 를 안 부르면 — 아무 일도 일어나지 않습니다

이쪽이 진짜 함정입니다.

```java
JPAQuery<Customer> query = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP));

// 여기까지 실행. 로그를 보십시오.
```

**결과**
```
(hibernate.SQL 로그에 아무것도 찍히지 않습니다)
```

**SQL 이 한 줄도 나가지 않습니다.** `JPAQuery` 객체는 "무엇을 조회할지 적어 둔 설계도"일 뿐이고, `fetch()` / `fetchOne()` / `fetchFirst()` 중 하나를 불러야 그때 JPQL 이 만들어지고 SQL 이 실행됩니다. 이것을 **지연 실행**이라고 합니다.

```java
JPAQuery<Customer> query = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP));

System.out.println("아직 실행 전");
List<Customer> result = query.fetch();      // ← 여기서 SQL 실행
System.out.println("실행 후: " + result.size());
```

**결과**
```
아직 실행 전
```
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.VIP
실행 후: 4
```

이 성질 자체는 좋은 것입니다. 조건을 조립하는 동안에는 DB 를 건드리지 않으니 [Step 04 — 조건과 동적 쿼리](../step-04-where-conditions/) 의 동적 쿼리가 가능해집니다. 문제는 **`fetch()` 를 빠뜨려도 컴파일 에러도 런타임 에러도 없다**는 것입니다.

```java
// 삭제 대상을 "조회했다고 생각했지만" 실제로는 아무것도 안 한 코드
public void deactivateInactive() {
    queryFactory.selectFrom(customer)
            .where(customer.points.loe(0));      // fetch() 없음
    // 컴파일 OK, 실행 OK, SQL 0건. 조용히 아무 일도 안 합니다.
}
```

> ⚠️ **함정 — `fetch()` 누락은 "실패하지 않는 실패"입니다**
> 예외도 안 나고 로그도 안 남습니다. 배포 후 "왜 데이터가 그대로지?" 로 발견됩니다.
> IDE 의 "결과값이 사용되지 않음(unused return value)" 경고를 켜 두면 잡을 수 있습니다.
> IntelliJ 는 `JPAQuery` 를 반환하는 체인을 그냥 버리면 회색으로 흐리게 표시합니다. 그 회색을 무시하지 마십시오.
> 반대로, **쿼리를 조립해 두고 나중에 실행하는 것은 정상적인 사용법**입니다.
> 조립과 실행을 분리해 쓸 때는 변수에 담아 두었는지 확인하십시오.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 쿼리 뼈대 | `select` → `from` → `where` → `fetch`. SQL 과 순서만 다르고 의미는 같다 |
| `selectFrom(x)` | `select(x).from(x)` 의 축약. 생성 SQL 완전히 동일 |
| 엔티티 조회 | 매핑된 전 컬럼이 select 절에 나열됨. 변경 감지가 필요할 때만 |
| 컬럼 1개 | 반환 타입이 그 컬럼의 타입 (`List<String>` 등) |
| 컬럼 2개 이상 | `List<Tuple>`. `row.get(customer.name)` 으로 꺼냄. DTO 로 대체할 것 (Step 05) |
| `fetch()` | `List<T>`. 0건이면 **빈 리스트**, `null` 아님 |
| `fetchOne()` | 0건 → `null`, 1건 → 객체, 2건+ → `NonUniqueResultException`. `limit` 안 붙음 |
| `fetchFirst()` | `limit(1).fetchOne()`. 2건 이상이어도 예외 없음. `orderBy` 와 함께 쓸 것 |
| 0건 `null` | 예외보다 위험하다. 조용히 통과해 먼 곳에서 NPE 로 터진다 |
| `fetchCount()` | deprecated. select 절 기계적 치환 → group by/distinct/조인에서 틀린다 |
| `fetchResults()` | deprecated. 항상 count + 본 쿼리 2회 실행 |
| count 대안 | `select(customer.count()).from(customer).fetchOne()`. `Wildcard.count` 면 `count(*)` |
| `distinct()` | 단일 컬럼 중복 제거. `selectDistinct(...)` 축약형 존재 |
| `limit`/`offset` | MySQL 방언에서 `limit ?, ?`. `offset(0)` 은 SQL 에 안 나감 |
| `Optional` | 단건 조회 저장소 메서드의 표준 반환 타입. `orElseThrow()` 를 습관으로 |
| 지연 실행 | `fetch` 계열을 부르기 전까지 SQL 은 나가지 않는다. 누락해도 에러가 없다 |
| `from` 누락 | `IllegalArgumentException: No sources given`. 즉시 터지므로 안전한 실패 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **각 문제를 풀고 나서 반드시 `hibernate.SQL` 로그를 눈으로 확인**하십시오. 이 스텝의 목적은 "돌아가는 코드"가 아니라 "내가 만든 SQL 을 아는 것"입니다.

1. `products` 에서 가격이 100,000원 이상인 상품의 **이름과 가격만** 조회하고, 생성 SQL 의 select 절에 컬럼이 몇 개 나오는지 확인하기
2. 이메일로 고객 1명을 찾는 메서드를 `fetchOne()` 으로 작성하고, 존재하지 않는 이메일을 넣었을 때 예외가 아니라 `null` 이 나오는 것을 단언(assert)하기
3. `grade = SILVER` 인 고객(8명)에 `fetchOne()` 을 호출해 `NonUniqueResultException` 이 발생함을 확인하고, 같은 쿼리를 `fetchFirst()` 로 바꿔 SQL 에 `limit ?` 가 추가되는 것을 로그로 대조하기
4. `fetchCount()` 로 ON_SALE 상품 수를 센 뒤, 같은 결과를 `select(product.count())` 로 직접 작성해 **두 SQL 이 같은지** 로그로 비교하기
5. `orders` 의 `shippingCity` 를 중복 없이 조회하고, `distinct()` 를 뺐을 때와 건수가 어떻게 달라지는지 기록하기
6. 2번의 메서드를 `Optional<Customer>` 반환으로 리팩터링하고, 호출부에서 `orElseThrow()` 로 커스텀 예외를 던지도록 고치기

---

## 다음 단계

지금까지의 `where` 는 조건이 하나뿐이었습니다. 실무 쿼리는 조건이 여러 개이고, 그중 몇 개는 **사용자가 입력했을 때만** 붙어야 합니다. QueryDSL 이 JPQL 문자열 조립을 이긴 결정적인 지점이 바로 여기입니다.

다음 스텝에서는 `BooleanExpression` 을 조립해 동적 쿼리를 만들고, `where(null)` 이 무시된다는 성질 하나로 `if` 문 없이 조건을 붙였다 뗐다 하는 법을 익힙니다. 그리고 `or` 를 섞는 순간 괄호가 사라지는 — 컴파일도 되고 예외도 안 나는데 결과 건수가 두 배가 되는 — 함정을 재현합니다.

→ [Step 04 — 조건과 동적 쿼리](../step-04-where-conditions/)

---

## 실습 파일

이 스텝은 자바 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 실행하며 3-1 ~ 3-11 의 모든 생성 SQL 을 눈으로 확인하고, `Exercise.java` 의 6문제를 직접 푼 뒤, `Solution.java` 로 정답과 해설을 대조합니다.

세 파일 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스입니다. `@Transactional` 이 붙어 있으므로 테스트가 끝나면 롤백되고, 여러 번 돌려도 데이터가 변하지 않습니다. 조회 전용 스텝이라 큰 의미는 없지만 이후 스텝(Step 11 벌크 연산)에서 같은 형태를 유지하기 위해 지금부터 맞춰 둡니다.

### Practice.java

본문(3-1 ~ 3-11)의 모든 예제를 절 번호 주석과 함께 담은 실습 파일입니다.

- `[3-1]` 은 `select(customer).from(customer)` 와 `selectFrom(customer)` 를 **연달아 실행**합니다. 두 개의 SQL 로그가 글자 하나 다르지 않은 것을 직접 보는 것이 목적이므로, 둘을 따로 돌리지 말고 한 메서드 안에서 이어 실행하십시오.
- `[3-4]` 의 `fetchOne_두건이상이면_예외()` 는 `assertThatThrownBy(...)` 로 `NonUniqueResultException` 을 단언합니다. **예외가 나는 것이 정상 동작**인 테스트이므로 빨간 줄이 떠도 놀라지 마십시오. 같은 메서드 아래쪽에서 0건 케이스가 `null` 을 돌려주는 것을 대조합니다.
- `[3-6]` 의 `fetchCount()` 호출에는 `@SuppressWarnings("deprecation")` 이 붙어 있습니다. **일부러 deprecated 메서드를 호출해 그 SQL 을 보기 위한** 코드이며, 실무 코드에 복사하면 안 됩니다. 바로 아래 `countManually()` 가 권장 형태입니다.
- `[3-11]` 의 `fetch_안부르면_아무일도_없다()` 는 **아무것도 단언하지 않습니다.** 콘솔에 `"--- 여기부터 쿼리 조립 ---"` 과 `"--- 조립 끝, SQL 로그를 확인하십시오 ---"` 를 찍어 두었으니, 그 두 줄 사이에 `hibernate.SQL` 로그가 없다는 것을 눈으로 확인하는 것이 이 테스트의 전부입니다.
- `[3-11]` 의 `from` 누락 예제는 주석 처리돼 있습니다. 주석을 풀면 `IllegalArgumentException` 이 나는 것을 확인할 수 있습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 요구사항 주석과 `// 여기에 작성:` 자리로 구성돼 있습니다.

- **문제 1·5** 는 생성 SQL 을 관찰하는 문제입니다. 코드를 완성한 뒤 로그를 보고, 주석의 `// 확인: select 절 컬럼 개수 = ___` 같은 빈칸을 직접 채우십시오. 답을 코드가 아니라 **주석에 적는** 것이 이 두 문제의 핵심입니다.
- **문제 2·3** 은 `fetchOne()` 의 비대칭 동작(0건 `null` / 2건 예외)을 각각 단언합니다. 문제 3 은 `assertThatThrownBy` 를 쓰도록 힌트를 달아 두었습니다.
- **문제 4** 는 deprecated 메서드를 **일부러 호출**하는 문제입니다. 클래스에 `@SuppressWarnings("deprecation")` 이 이미 붙어 있어 경고 없이 컴파일됩니다.
- **문제 6** 은 유일하게 메서드를 새로 만드는 문제입니다. `Optional<Customer> findByEmail(String email)` 시그니처와 `CustomerNotFoundException` 중첩 클래스가 파일 하단에 미리 준비돼 있습니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여십시오.

- **정답 1** 의 요점은 select 절 컬럼이 **2개**라는 것입니다. `selectFrom(product)` 이었다면 9개가 나옵니다. 주석에서 두 SQL 을 나란히 적어 비교합니다.
- **정답 3** 은 이 스텝에서 가장 중요한 정답입니다. `fetchOne()` 의 SQL 에는 `limit` 이 없고 `fetchFirst()` 에는 `limit ?` 가 붙는다는 것, 그리고 **DB 는 두 경우 모두 정상 동작했고 예외는 QueryDSL 이 자바 쪽에서 던진 것**이라는 점을 주석으로 설명합니다.
- **정답 4** 는 두 SQL 이 같다는 사실 자체보다, "**이 쿼리에서는** 같지만 group by 나 distinct 가 붙는 순간 달라진다"는 조건부 결론을 강조합니다. 왜 deprecated 인지가 여기서 완성됩니다.
- **정답 6** 은 `Optional.ofNullable(...)` 로 감싸는 코드 세 줄보다 **주석이 훨씬 깁니다.** "NPE 가 나는 위치와 원인이 발생한 위치가 다르다"는 것이 왜 디버깅 비용인지를 3-4 의 함정 블록보다 자세히 풀어씁니다.
- 파일 맨 아래 `// 보너스` 구간에 `Wildcard.count` 와 `customer.count()` 가 만드는 SQL 차이(`count(*)` vs `count(c1_0.customer_id)`)를 정리해 두었습니다. 연습문제에는 없지만 실무에서 자주 묻는 질문입니다.

```java file="./Solution.java"
```
