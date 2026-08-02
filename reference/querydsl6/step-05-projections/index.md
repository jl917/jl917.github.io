# Step 05 — 프로젝션과 DTO

> **학습 목표**
> - 엔티티 통째 조회와 필요한 컬럼만 뽑는 프로젝션의 **생성 SQL 차이**를 눈으로 확인한다
> - `Tuple` 을 읽고, 그것을 서비스 계층 밖으로 내보내면 안 되는 이유를 설명한다
> - `Projections.bean` / `fields` / `constructor` / `@QueryProjection` 네 방식을 구분해서 쓴다
> - **필드명이 안 맞으면 예외 없이 null 이 되는** `Projections.fields` 의 함정을 재현하고 `as()` 로 고친다
> - **같은 타입 필드의 순서가 바뀌면 값이 조용히 뒤바뀌는** `Projections.constructor` 의 함정을 재현한다
> - 연산 결과와 서브쿼리 결과를 DTO 필드로 받는다
>
> **선행 스텝**: [Step 04 — 조건과 동적 쿼리](../step-04-where-conditions/)
> **예상 소요**: 90분

이 스텝은 이 코스의 정체성이 가장 선명하게 드러나는 곳입니다.
여기서 다루는 두 함정은 **컴파일 에러도, 런타임 예외도, 경고 로그도 내지 않습니다.**
그저 값이 null 이 되거나, 이름과 도시가 서로 바뀌어 들어갈 뿐입니다.

본문의 모든 예제는 아래 static import 를 전제합니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
```

---

## 5-1. 프로젝션이란 — 엔티티를 통째로 읽는 비용

프로젝션(projection)은 "조회 결과에서 어떤 값을 어떤 모양으로 꺼낼 것인가" 를 정하는 일입니다.
지금까지 우리는 [Step 03](../step-03-basic-query/) 의 `selectFrom(customer)` 처럼 **엔티티를 통째로** 읽었습니다.
편하지만 공짜가 아닙니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
```
```
조회 30건
```

컬럼 9개를 전부 읽습니다. 그리고 SQL 이 끝난 뒤에도 일이 남습니다.

| 단계 | 엔티티 조회 | 프로젝션 조회 |
|---|---|---|
| SELECT 컬럼 | 매핑된 전 컬럼 | 필요한 컬럼만 |
| 영속성 컨텍스트 등록 | **한다** (1차 캐시에 30개) | 안 한다 |
| 스냅샷 보관 | **한다** (변경 감지용 사본 30개) | 안 한다 |
| flush 시 더티 체킹 | **대상** | 대상 아님 |
| 결과 타입 | `Customer` (관리 대상) | `String`, `Tuple`, DTO |

핵심은 **스냅샷**입니다. JPA 는 변경 감지(dirty checking)를 하려고 엔티티를 읽을 때마다
그 시점의 값을 복사해 따로 보관합니다. 30건이면 사실상 객체가 60개 만들어지는 셈입니다.
600건짜리 `orders` 를 통째로 읽으면 1,200개가 됩니다.

이름과 도시만 필요하다면 이렇게 씁니다.

```java
List<Tuple> result = queryFactory
        .select(customer.name, customer.city)
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
```

컬럼이 9개에서 2개로 줄었고, 영속성 컨텍스트에는 아무것도 등록되지 않습니다.

> 💡 **실무 팁 — 조회 전용이면 프로젝션이 기본입니다**
> "이 결과를 수정해서 저장할 것인가?" 가 판단 기준입니다.
> 화면에 뿌리고 끝나는 조회라면 엔티티를 읽을 이유가 없습니다.
> 변경할 게 아닌데 엔티티로 읽으면, 쓰지도 않을 스냅샷을 만들고 flush 마다 비교당합니다.
> 반대로 값을 바꿔야 한다면 반드시 엔티티로 읽어야 합니다 — DTO 는 변경 감지 대상이 아닙니다.

---

## 5-2. 단일 컬럼 프로젝션

컬럼 하나만 뽑으면 그 컬럼의 타입이 그대로 제네릭 타입이 됩니다. `Tuple` 도 DTO 도 필요 없습니다.

```java
List<String> names = queryFactory
        .select(customer.name)
        .from(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

**결과**
```sql
select c1_0.name
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] VIP
조회 4건 — [김서준, 류하나, 정  훈, 배채영]
```

`customer.name` 은 `StringPath` 이므로 `select(...)` 의 반환 타입이 `JPAQuery<String>` 으로 결정됩니다.
`customer.points` 는 `NumberPath<Integer>` 이니 `List<Integer>` 가 나옵니다.

```java
List<Integer> points = queryFactory
        .select(customer.points)
        .from(customer)
        .orderBy(customer.points.desc())
        .limit(5)
        .fetch();
```

**결과**
```sql
select c1_0.points
from customers c1_0
order by c1_0.points desc
limit ?
```
```
바인딩: [1] 5
조회 5건 — [48200, 45100, 41800, 39600, 37400]
```

중복을 제거하려면 `selectDistinct` 를 씁니다.

```java
List<String> cities = queryFactory
        .selectDistinct(customer.city)
        .from(customer)
        .orderBy(customer.city.asc())
        .fetch();
```

**결과**
```sql
select distinct c1_0.city
from customers c1_0
order by c1_0.city asc
```
```
조회 6건 — [광주, 대구, 대전, 부산, 서울, 인천]
```

> 📌 MySQL8 코스 [Step 04 — SELECT 기초](../../mysql8/step-04-select-basics/) 에서
> `SELECT DISTINCT city FROM customers ORDER BY city;` 로 썼던 것과 같은 SQL 입니다.

---

## 5-3. `Tuple` — 여러 컬럼을 타입 없이 받기

컬럼을 두 개 이상 나열하면 QueryDSL 은 `Tuple` 로 감싸 줍니다.

```java
List<Tuple> result = queryFactory
        .select(customer.name, customer.city, customer.points)
        .from(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();

for (Tuple t : result) {
    String name  = t.get(customer.name);
    String city  = t.get(customer.city);
    Integer pts  = t.get(customer.points);
    System.out.println(name + " / " + city + " / " + pts);
}
```

**결과**
```sql
select c1_0.name, c1_0.city, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] VIP
김서준 / 서울 / 48200
류하나 / 부산 / 45100
정  훈 / 서울 / 41800
배채영 / 인천 / 37400
```

`Tuple` 에서 값을 꺼낼 때는 **select 에 넣었던 표현식 객체를 그대로 키로** 씁니다.
`t.get(0, String.class)` 처럼 인덱스로도 꺼낼 수 있지만, 순서에 의존하게 되므로 권장하지 않습니다.
select 목록에 컬럼 하나를 끼워 넣는 순간 인덱스가 전부 밀립니다.

select 에 없는 표현식으로 꺼내면 예외가 아니라 **`null`** 이 돌아옵니다.

```java
Tuple t = result.get(0);
System.out.println(t.get(customer.email));   // select 에 없었음
```

**결과**
```
null
```

> ⚠️ **함정 — `Tuple` 을 컨트롤러나 API 응답으로 내보내지 마십시오**
> `Tuple` 은 `com.querydsl.core.Tuple` 입니다. 이걸 리포지토리 밖으로 반환하면
> **서비스와 컨트롤러가 QueryDSL 에 의존하게 됩니다.**
> 데이터 접근 기술을 바꾸려는 순간(또는 QueryDSL 6 → 7 로 올릴 때)
> 리포지토리만 고치면 될 일이 애플리케이션 전 계층으로 번집니다.
> 게다가 `Tuple` 은 값을 꺼내려면 `QCustomer.customer.name` 같은 Q타입 상수가 필요합니다 —
> 컨트롤러가 Q타입을 import 하는 순간 계층 분리는 끝난 겁니다.
>
> **`Tuple` 은 리포지토리 내부에서만 쓰고, 경계를 넘을 때는 반드시 DTO 로 변환하십시오.**
> 애초에 DTO 로 받는 게 더 간단합니다. 그게 이 스텝의 나머지 전부입니다.

---

## 5-4. `Projections.bean` — setter 로 채우기

`Projections.bean` 은 **기본 생성자로 객체를 만든 뒤 setter 를 호출해** 값을 넣습니다.

먼저 DTO 를 준비합니다.

```java
public class CustomerDto {
    private String name;
    private String city;

    public CustomerDto() {}                       // 기본 생성자 필수

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    @Override public String toString() {
        return "CustomerDto(name=" + name + ", city=" + city + ")";
    }
}
```

```java
List<CustomerDto> result = queryFactory
        .select(Projections.bean(CustomerDto.class,
                customer.name,
                customer.city))
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerDto(name=김서준, city=서울)
CustomerDto(name=이지은, city=부산)
CustomerDto(name=박철수, city=대구)
...
```

동작 순서는 이렇습니다.

1. SQL 로 `name`, `city` 두 컬럼을 읽는다
2. 행마다 `new CustomerDto()` — **기본 생성자 호출**
3. select 에 넣은 표현식의 이름(`name`, `city`)으로 **setter 이름을 조립**한다 (`setName`, `setCity`)
4. 리플렉션으로 그 setter 를 호출한다

즉 3번에서 **문자열로 이름을 맞춥니다.** 이 사실이 5-6 절의 함정으로 이어집니다.

기본 생성자가 없으면 이렇게 죽습니다.

```
com.querydsl.core.types.ExpressionException:
    com.example.shop.step05.CustomerDto.<init>()
Caused by: java.lang.NoSuchMethodException: com.example.shop.step05.CustomerDto.<init>()
```

이건 **좋은 실패**입니다. 즉시 터지니까요. 조용한 실패가 문제입니다.

---

## 5-5. `Projections.fields` — 필드에 직접 넣기

`Projections.fields` 는 setter 를 거치지 않고 **필드에 리플렉션으로 직접 대입**합니다.
`private` 필드도 `setAccessible(true)` 로 열어서 넣습니다.

```java
public class CustomerFieldDto {
    private String name;      // getter/setter 없음
    private String city;

    @Override public String toString() {
        return "CustomerFieldDto(name=" + name + ", city=" + city + ")";
    }
}
```

```java
List<CustomerFieldDto> result = queryFactory
        .select(Projections.fields(CustomerFieldDto.class,
                customer.name,
                customer.city))
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerFieldDto(name=김서준, city=서울)
CustomerFieldDto(name=이지은, city=부산)
CustomerFieldDto(name=박철수, city=대구)
...
```

생성 SQL 은 `bean` 과 **완전히 같습니다.** 차이는 자바 쪽 매핑 방식뿐입니다.

| | `bean` | `fields` |
|---|---|---|
| 값 주입 경로 | setter 호출 | 필드 직접 대입 |
| 필요한 것 | 기본 생성자 + setter | 기본 생성자 (setter 불필요) |
| 이름 매칭 대상 | setter 이름 | 필드 이름 |
| `final` 필드 | 불가 | 불가 |

`fields` 가 더 간결해 보여서 실무에서 많이 쓰입니다.
그런데 바로 그 간결함이 다음 절의 사고를 만듭니다.

---

## 5-6. ⚠️ 이 스텝의 핵심 — 이름이 안 맞으면 조용히 null

DTO 필드 이름을 `name` 이 아니라 `userName` 으로 지었다고 합시다.
화면 스펙이 `userName` 이라서, 혹은 다른 DTO 와 이름을 맞추려고 — 흔한 일입니다.

```java
public class CustomerDto {
    private String userName;    // ← 엔티티 필드는 name 인데 DTO 는 userName
    private String city;

    @Override public String toString() {
        return "CustomerDto(userName=" + userName + ", city=" + city + ")";
    }
}
```

```java
List<CustomerDto> result = queryFactory
        .select(Projections.fields(CustomerDto.class,
                customer.name,        // ← 이름이 name. DTO 는 userName
                customer.city))
        .from(customer)
        .fetch();

result.forEach(System.out::println);
```

**컴파일**: 성공합니다.
**실행**: 예외 없습니다. 경고 로그도 없습니다.

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerDto(userName=null, city=서울)
CustomerDto(userName=null, city=부산)
CustomerDto(userName=null, city=대구)
CustomerDto(userName=null, city=인천)
CustomerDto(userName=null, city=서울)
... (30건 전부 userName=null)
```

**SQL 은 완벽합니다.** `name` 컬럼을 정확히 읽어 왔습니다.
DB 에서 값이 넘어오는 것까지 성공했습니다. 그런데 그 값을 넣을 자리를 못 찾았습니다.

QueryDSL 은 `customer.name` 이라는 표현식의 이름 `"name"` 을 꺼내
`CustomerDto` 에서 `name` 이라는 필드를 찾습니다. 없습니다.
그래서 **아무것도 하지 않고 넘어갑니다.** `userName` 은 초기값 `null` 그대로 남습니다.

> ⚠️ **함정 — `Projections.fields` / `bean` 의 이름 불일치**
> 이건 이 코스에서 말하는 "조용히 틀리는 코드" 의 교과서적 사례입니다.
> - 컴파일러: 통과. `Projections.fields(Class<T>, Expression<?>...)` 는 이름을 검사할 방법이 없습니다.
> - 런타임: 예외 없음. "필드를 못 찾았다" 는 **에러가 아니라 그냥 스킵**입니다.
> - 테스트: DTO 필드가 5개인데 그중 1개만 틀렸다면, 그 필드를 검증하지 않는 테스트는 전부 통과합니다.
> - 발견 시점: 화면에 빈칸이 뜨거나, API 응답에 `"userName": null` 이 나갔을 때.
>
> **근본 원인은 `fields` 도 `bean` 도 "런타임 문자열 이름 매칭" 이라는 것입니다.**
> 타입 안전을 위해 QueryDSL 을 쓰는데, 정작 DTO 매핑 구간에서 타입 안전이 끊깁니다.

### 처방 1 — `as()` 로 별칭 붙이기

표현식에 별칭을 달아 이름을 맞춥니다.

```java
List<CustomerDto> result = queryFactory
        .select(Projections.fields(CustomerDto.class,
                customer.name.as("userName"),     // ← 별칭
                customer.city))
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerDto(userName=김서준, city=서울)
CustomerDto(userName=이지은, city=부산)
CustomerDto(userName=박철수, city=대구)
...
```

생성 SQL 은 **한 글자도 바뀌지 않았습니다.** `as()` 는 SQL 의 `AS` 가 아닙니다.
QueryDSL 내부에서 "이 표현식의 이름은 `userName` 이다" 라고 표시하는 것뿐이고,
그 이름은 DTO 필드를 찾을 때만 쓰입니다. JPQL 별칭으로도 나가지 않습니다.

### 처방 2 — `ExpressionUtils.as()`

`as()` 메서드가 없는 표현식(대표적으로 서브쿼리)에는 `ExpressionUtils.as()` 를 씁니다.

```java
List<CustomerDto> result = queryFactory
        .select(Projections.fields(CustomerDto.class,
                ExpressionUtils.as(customer.name, "userName"),
                customer.city))
        .from(customer)
        .fetch();
```

두 방식은 동등합니다. `customer.name.as("userName")` 이 더 짧으니 평소엔 이쪽을 씁니다.
`ExpressionUtils.as(JPAExpressions.select(...), "orderCount")` 형태가 필요한 경우는 5-11 절에서 봅니다.

### `bean` 도 똑같습니다

```java
public class CustomerDto {
    private String userName;
    private String city;
    public void setUserName(String v) { this.userName = v; }   // setUserName
    public void setCity(String v) { this.city = v; }
}
```

```java
queryFactory.select(Projections.bean(CustomerDto.class,
        customer.name,          // → setName 을 찾는다. 없다. 스킵.
        customer.city))
    .from(customer).fetch();
```

**결과**
```
조회 30건 — userName 전부 null
```

`bean` 은 setter 이름(`setName`)을 찾고, `fields` 는 필드 이름(`name`)을 찾습니다.
찾는 대상만 다를 뿐 **못 찾았을 때 조용히 넘어가는 것은 동일합니다.**

> 💡 **실무 팁 — 이 함정을 구조적으로 막는 법**
> 1. DTO 필드 이름을 **엔티티 필드 이름과 같게** 짓는다. 화면 이름은 프레젠테이션 계층에서 바꾼다.
> 2. 이름이 다를 수밖에 없으면 **반드시 `as()`** 를 붙인다. 예외 없이.
> 3. 가장 확실한 방법은 아예 이름 매칭을 쓰지 않는 것 — `@QueryProjection` (5-8 절)입니다.
> 4. DTO 매핑 테스트에서는 **모든 필드에 대해 `assertThat(...).isNotNull()`** 을 최소한 한 번은 돌립니다.
>    "null 이 아니다" 만 확인해도 이 함정은 전부 잡힙니다.

---

## 5-7. `Projections.constructor` — 순서와 타입으로 채우기

`Projections.constructor` 는 이름을 보지 않습니다.
**select 에 넣은 표현식의 순서와 타입에 맞는 생성자**를 찾아 호출합니다.

```java
public class CustomerDto {
    private final String userName;
    private final String city;

    public CustomerDto(String userName, String city) {
        this.userName = userName;
        this.city = city;
    }
    // final 필드 가능. setter 불필요. 기본 생성자 불필요.
}
```

```java
List<CustomerDto> result = queryFactory
        .select(Projections.constructor(CustomerDto.class,
                customer.name,      // → 1번째 생성자 인자
                customer.city))     // → 2번째 생성자 인자
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerDto(userName=김서준, city=서울)
CustomerDto(userName=이지은, city=부산)
...
```

DTO 필드가 `userName` 인데도 정상입니다. **이름을 안 보기 때문입니다.**
`as()` 도 필요 없습니다. 이름 오타에 강합니다. `final` 필드를 쓸 수 있어 불변 DTO 도 만들 수 있습니다.

타입이 안 맞으면 런타임에 즉시 터집니다.

```java
queryFactory.select(Projections.constructor(CustomerDto.class,
        customer.name,
        customer.points))       // Integer 인데 생성자 2번째 인자는 String
    .from(customer).fetch();
```

**결과**
```
com.querydsl.core.types.ExpressionException:
    No constructor found for class com.example.shop.step05.CustomerDto
    with parameters: [class java.lang.String, class java.lang.Integer]
```

이것도 **좋은 실패**입니다. 문제는 타입이 같을 때입니다.

> ⚠️ **함정 — 같은 타입 필드의 순서를 바꾸면 값이 조용히 뒤바뀝니다**
> `CustomerDto(String userName, String city)` 를 리팩터링하면서
> 생성자 파라미터 순서를 `(String city, String userName)` 으로 바꿨다고 합시다.
> IDE 의 "Change Signature" 는 호출부의 **자바 인자 순서**는 바꿔 주지만,
> `Projections.constructor(...)` 안의 **QueryDSL 표현식 순서까지 알아서 바꿔 주지는 않습니다.**

```java
// DTO 를 이렇게 고쳤다
public CustomerDto(String city, String userName) {   // ← 순서가 바뀜
    this.city = city;
    this.userName = userName;
}
```

```java
// 쿼리는 그대로 두었다
List<CustomerDto> result = queryFactory
        .select(Projections.constructor(CustomerDto.class,
                customer.name,      // → 이제 city 자리에 들어간다
                customer.city))     // → 이제 userName 자리에 들어간다
        .from(customer)
        .fetch();
```

**컴파일**: 성공. **실행**: 예외 없음. 타입이 둘 다 `String` 이라 생성자를 정확히 찾습니다.

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerDto(userName=서울, city=김서준)
CustomerDto(userName=부산, city=이지은)
CustomerDto(userName=대구, city=박철수)
...
```

**이름과 도시가 통째로 바뀌었습니다.** 30건 전부.
화면에는 "서울 님, 안녕하세요" 가 뜹니다. 로그에는 아무것도 남지 않습니다.

`fields` 의 함정이 "값이 사라지는" 것이라면, `constructor` 의 함정은 "값이 뒤바뀌는" 것입니다.
null 은 눈에 띄기라도 하지만, 뒤바뀐 값은 그럴듯해 보입니다. 이쪽이 더 위험합니다.

**처방**: 이 함정은 `as()` 로 못 막습니다. 이름을 안 보니까요.
근본 해결은 **`@QueryProjection`** 입니다. 다음 절입니다.

---

## 5-8. `@QueryProjection` — 컴파일 시점에 검증하기

DTO **생성자**에 `@QueryProjection` 을 붙이면, APT 가 그 DTO 의 Q타입을 생성합니다.

```java
package com.example.shop.step05;

import com.querydsl.core.annotations.QueryProjection;

public class CustomerDto {
    private final String userName;
    private final String city;

    @QueryProjection                                   // ← 이것 하나
    public CustomerDto(String userName, String city) {
        this.userName = userName;
        this.city = city;
    }

    public String getUserName() { return userName; }
    public String getCity() { return city; }

    @Override public String toString() {
        return "CustomerDto(userName=" + userName + ", city=" + city + ")";
    }
}
```

`./gradlew compileJava` 를 돌리면 `QCustomerDto` 가 생성됩니다.

```
build/generated/sources/annotationProcessor/java/main/com/example/shop/step05/QCustomerDto.java
```

생성된 코드는 이렇게 생겼습니다 (핵심 부분만).

```java
package com.example.shop.step05;

import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Expression;
import javax.annotation.processing.Generated;

@Generated("com.querydsl.codegen.DefaultProjectionSerializer")
public class QCustomerDto extends ConstructorExpression<CustomerDto> {

    private static final long serialVersionUID = -1837294055L;

    public QCustomerDto(Expression<String> userName, Expression<String> city) {
        super(CustomerDto.class,
              new Class<?>[]{ String.class, String.class },
              userName, city);
    }
}
```

**생성자 시그니처가 `Expression<String>, Expression<String>` 으로 박혀 있습니다.**
이제 이렇게 씁니다.

```java
List<CustomerDto> result = queryFactory
        .select(new QCustomerDto(customer.name, customer.city))
        .from(customer)
        .fetch();
```

**결과**
```sql
select c1_0.name, c1_0.city
from customers c1_0
```
```
조회 30건
CustomerDto(userName=김서준, city=서울)
CustomerDto(userName=이지은, city=부산)
...
```

이제 실수를 해 봅시다.

```java
// 인자 개수가 틀림
new QCustomerDto(customer.name)
```
```
error: constructor QCustomerDto in class QCustomerDto cannot be applied to given types;
  required: Expression<String>,Expression<String>
  found:    StringPath
  reason: actual and formal argument lists differ in length
```

```java
// 타입이 틀림
new QCustomerDto(customer.name, customer.points)
```
```
error: incompatible types: NumberPath<Integer> cannot be converted to Expression<String>
```

**컴파일이 안 됩니다.** IDE 에 빨간 줄이 그어집니다.
5-7 절의 "순서 바꿔치기" 도 DTO 생성자를 고치면 `QCustomerDto` 가 재생성되므로,
타입이 다른 경우에는 컴파일 에러로 잡힙니다.
같은 타입끼리 순서를 바꾼 경우는 여전히 컴파일이 되지만,
적어도 **DTO 와 Q타입이 한 몸으로 움직인다**는 보장은 생깁니다.

### 단점 두 가지

**첫째, DTO 가 QueryDSL 에 의존합니다.**
`import com.querydsl.core.annotations.QueryProjection;` 이 DTO 파일에 들어갑니다.
이 DTO 를 API 응답이나 다른 모듈로 넘기면, QueryDSL 의존이 그 모듈까지 따라갑니다.
5-3 절에서 `Tuple` 을 내보내지 말라고 한 것과 같은 종류의 문제입니다. 정도는 훨씬 가볍지만요.

**둘째, APT 대상에 DTO 가 포함돼야 합니다.**
Q타입 생성은 애노테이션 프로세싱 결과입니다.
DTO 가 `src/main/java` 밖(예: 별도 `api` 모듈)에 있는데 그 모듈에 `annotationProcessor` 설정이 없으면
`QCustomerDto` 가 생성되지 않습니다. `cannot find symbol: class QCustomerDto` 로 나타납니다.

```groovy
// DTO 가 있는 모듈에도 이 설정이 필요합니다
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
```

> 💡 **실무 팁 — 팀에서 어떻게 정할까**
> 의존성 오염을 감수하고 컴파일 검증을 택하는 팀이 많습니다.
> 절충안은 **리포지토리 전용 DTO 는 `@QueryProjection`, 외부로 나가는 응답 DTO 는 순수 클래스**로 두고
> 경계에서 변환하는 것입니다. 클래스가 늘어나는 대신 의존이 새지 않습니다.
> 어느 쪽이든 **`Projections.fields` 를 기본값으로 삼는 것만은 피하십시오.**

---

## 5-9. 네 방식 비교

| | `bean` | `fields` | `constructor` | `@QueryProjection` |
|---|---|---|---|---|
| 검증 시점 | **런타임** | **런타임** | 런타임 | **컴파일** |
| 매칭 기준 | setter 이름 | 필드 이름 | 순서 + 타입 | 순서 + 타입 (컴파일 강제) |
| 필요한 것 | 기본 생성자 + setter | 기본 생성자 | 해당 시그니처 생성자 | 생성자 + `@QueryProjection` + APT |
| `final` 필드 | ✗ | ✗ | ✓ | ✓ |
| 이름 불일치 시 | **조용히 null** | **조용히 null** | 영향 없음 | 영향 없음 |
| 같은 타입 순서 바뀌면 | 영향 없음 | 영향 없음 | **조용히 값 뒤바뀜** | 조용히 값 뒤바뀜 (단, DTO 와 동기화됨) |
| 타입 불일치 시 | 런타임 예외 | 런타임 예외 | 런타임 예외 | **컴파일 에러** |
| 인자 개수 틀리면 | 영향 없음(무시) | 영향 없음(무시) | 런타임 예외 | **컴파일 에러** |
| DTO 의 QueryDSL 의존 | 없음 | 없음 | 없음 | **있음** |
| 권장 상황 | 레거시 JavaBean DTO | 짧은 내부 전용 DTO (`as()` 필수) | 불변 DTO, 외부 모듈 DTO | **기본값으로 권장** |

세 줄 요약입니다.

1. **`@QueryProjection` 을 기본으로 쓰십시오.** 컴파일러가 잡아 주는 게 가장 쌉니다.
2. DTO 에 QueryDSL 의존을 넣을 수 없으면 **`constructor`** 를 쓰고, 파라미터 순서를 절대 안 건드립니다.
3. `fields` / `bean` 을 쓸 거면 **모든 표현식에 `as()`** 를 붙이는 것을 팀 규칙으로 만드십시오.

---

## 5-10. 중첩 DTO — 여러 엔티티의 값을 하나로

"주문번호 + 고객명 + 상품명 + 수량" 처럼 여러 엔티티에서 값을 모으는 것도 프로젝션입니다.
표현식만 여러 엔티티에서 가져오면 됩니다.

```java
public class OrderLineDto {
    private final Long orderId;
    private final String customerName;
    private final String productName;
    private final int quantity;

    @QueryProjection
    public OrderLineDto(Long orderId, String customerName, String productName, int quantity) {
        this.orderId = orderId;
        this.customerName = customerName;
        this.productName = productName;
        this.quantity = quantity;
    }
    // getter 생략
}
```

```java
List<OrderLineDto> result = queryFactory
        .select(new QOrderLineDto(
                order.id,
                customer.name,
                product.name,
                orderItem.quantity))
        .from(orderItem)
        .join(orderItem.order, order)
        .join(order.customer, customer)
        .join(orderItem.product, product)
        .orderBy(order.id.asc(), product.id.asc())
        .limit(8)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, c1_0.name, p1_0.name, oi1_0.quantity
from order_items oi1_0
join orders o1_0 on o1_0.order_id = oi1_0.order_id
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
join products p1_0 on p1_0.product_id = oi1_0.product_id
order by o1_0.order_id asc, p1_0.product_id asc
limit ?
```
```
바인딩: [1] 8
조회 8건
OrderLineDto(orderId=1, customerName=류하나, productName=27인치 4K 모니터, quantity=3)
OrderLineDto(orderId=1, customerName=류하나, productName=원목 4인 식탁, quantity=1)
OrderLineDto(orderId=2, customerName=정  훈, productName=베이직 옥스퍼드 셔츠, quantity=2)
OrderLineDto(orderId=2, customerName=정  훈, productName=게이밍 노트북 RTX4060, quantity=3)
OrderLineDto(orderId=2, customerName=정  훈, productName=콜드브루 원액 1L, quantity=1)
OrderLineDto(orderId=3, customerName=안지수, productName=인체공학 사무용 의자, quantity=2)
OrderLineDto(orderId=4, customerName=한지호, productName=슬림핏 치노 팬츠, quantity=3)
OrderLineDto(orderId=4, customerName=한지호, productName=보급형 노트북 15, quantity=1)
```

> 📌 MySQL8 코스 [Step 07 — 조인](../../mysql8/step-07-joins/) 의 `7-2` 절과 **같은 결과**입니다.
> 거기서는 `FROM orders o JOIN customers c ... JOIN order_items oi ...` 로 썼습니다.

여기서 두 가지가 눈에 띕니다.

- `p1_0.name` 과 `c1_0.name` 이 둘 다 `name` 인데 충돌하지 않습니다.
  Hibernate 6 은 별칭 접두사로 구분하고, 순서로 결과를 읽으므로 문제가 없습니다.
- `order_id = 1` 이 두 줄입니다. 주문 1건에 상품이 2개(1:N)이기 때문입니다.
  이 "행 뻥튀기" 가 집계와 만나면 사고가 납니다.

**조인은 [Step 06](../step-06-joins/) 에서 본격적으로 다룹니다.**
`fan-out`, `on` vs `where`, `fetchJoin` 같은 주제들이 거기 있습니다.

DTO 안에 DTO 를 넣는 것도 가능합니다. `Projections` 는 중첩할 수 있습니다.

```java
List<OrderWithCustomerDto> result = queryFactory
        .select(Projections.constructor(OrderWithCustomerDto.class,
                order.id,
                order.totalAmount,
                Projections.constructor(CustomerDto.class,      // ← 중첩
                        customer.name,
                        customer.city)))
        .from(order)
        .join(order.customer, customer)
        .limit(3)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.total_amount, c1_0.name, c1_0.city
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
limit ?
```
```
바인딩: [1] 3
OrderWithCustomerDto(orderId=1, totalAmount=1836000.00, customer=CustomerDto(userName=류하나, city=부산))
OrderWithCustomerDto(orderId=2, totalAmount=6663900.00, customer=CustomerDto(userName=정  훈, city=서울))
OrderWithCustomerDto(orderId=3, totalAmount=658000.00, customer=CustomerDto(userName=안지수, city=대구))
```

SQL 은 평평합니다(컬럼 4개). 중첩은 자바 객체를 조립하는 단계에서만 일어납니다.

---

## 5-11. 서브쿼리 결과를 DTO 필드로

"고객명 + 그 고객의 주문 건수" 를 한 번에 뽑으려면 스칼라 서브쿼리가 필요합니다.
서브쿼리 표현식에는 `.as()` 메서드가 없으므로 **`ExpressionUtils.as()`** 를 씁니다.

```java
public class CustomerOrderCountDto {
    private String userName;
    private Long orderCount;
    // toString 생략
}
```

```java
List<CustomerOrderCountDto> result = queryFactory
        .select(Projections.fields(CustomerOrderCountDto.class,
                customer.name.as("userName"),
                ExpressionUtils.as(
                        JPAExpressions.select(order.count())
                                      .from(order)
                                      .where(order.customer.eq(customer)),
                        "orderCount")))
        .from(customer)
        .orderBy(customer.id.asc())
        .limit(5)
        .fetch();
```

**결과**
```sql
select c1_0.name,
       (select count(o1_0.order_id)
          from orders o1_0
         where o1_0.customer_id = c1_0.customer_id)
from customers c1_0
order by c1_0.customer_id asc
limit ?
```
```
바인딩: [1] 5
CustomerOrderCountDto(userName=김민수, orderCount=20)
CustomerOrderCountDto(userName=이지은, orderCount=20)
CustomerOrderCountDto(userName=박철수, orderCount=20)
CustomerOrderCountDto(userName=최영희, orderCount=20)
CustomerOrderCountDto(userName=정  훈, orderCount=20)
```

시드 데이터가 고객 30명에게 주문 20건씩(30 × 20 = 600) 배정했으므로 전부 20 입니다.

`ExpressionUtils.as` 를 빼먹으면 어떻게 될까요? 5-6 절과 똑같습니다.

```java
Projections.fields(CustomerOrderCountDto.class,
        customer.name.as("userName"),
        JPAExpressions.select(order.count()).from(order)
                      .where(order.customer.eq(customer)))   // 별칭 없음
```

**결과**
```
CustomerOrderCountDto(userName=김민수, orderCount=null)
CustomerOrderCountDto(userName=이지은, orderCount=null)
...
```

서브쿼리 표현식에는 애초에 이름이라는 게 없으니, 매칭할 필드를 찾지 못하고 그냥 넘어갑니다.
SQL 은 정상적으로 나가서 값도 가져왔는데, 그 값이 버려집니다.

> 💡 이런 경우 `Projections.constructor` 나 `@QueryProjection` 이 훨씬 안전합니다.
> 이름이 없는 표현식이라도 **순서**는 언제나 있으니까요.
> 서브쿼리를 DTO 에 넣을 일이 있으면 이름 매칭 방식은 피하는 게 좋습니다.

**서브쿼리는 [Step 07](../step-07-subqueries/) 에서 본격적으로 다룹니다.**
JPA 가 FROM 절 서브쿼리를 지원하지 않는다는 제약도 거기서 이야기합니다.

---

## 5-12. 연산 결과를 DTO 로

프로젝션에 넣는 것은 컬럼만이 아닙니다. 표현식이면 무엇이든 됩니다.
`quantity × unitPrice` 로 라인별 금액을 계산해 봅시다.

```java
public class OrderItemAmountDto {
    private final Long orderId;
    private final String productName;
    private final int quantity;
    private final BigDecimal lineAmount;

    @QueryProjection
    public OrderItemAmountDto(Long orderId, String productName,
                              int quantity, BigDecimal lineAmount) {
        this.orderId = orderId;
        this.productName = productName;
        this.quantity = quantity;
        this.lineAmount = lineAmount;
    }
}
```

```java
List<OrderItemAmountDto> result = queryFactory
        .select(new QOrderItemAmountDto(
                orderItem.order.id,
                orderItem.product.name,
                orderItem.quantity,
                orderItem.quantity.multiply(orderItem.unitPrice)))   // ← 연산
        .from(orderItem)
        .join(orderItem.product, product)
        .orderBy(orderItem.quantity.multiply(orderItem.unitPrice).desc())
        .limit(5)
        .fetch();
```

**결과**
```sql
select oi1_0.order_id, p1_0.name, oi1_0.quantity,
       oi1_0.quantity * oi1_0.unit_price
from order_items oi1_0
join products p1_0 on p1_0.product_id = oi1_0.product_id
order by oi1_0.quantity * oi1_0.unit_price desc
limit ?
```
```
바인딩: [1] 5
OrderItemAmountDto(orderId=2, productName=게이밍 노트북 RTX4060, quantity=3, lineAmount=6570000.00)
OrderItemAmountDto(orderId=58, productName=게이밍 노트북 RTX4060, quantity=3, lineAmount=6570000.00)
OrderItemAmountDto(orderId=114, productName=게이밍 노트북 RTX4060, quantity=3, lineAmount=6570000.00)
OrderItemAmountDto(orderId=170, productName=게이밍 노트북 RTX4060, quantity=3, lineAmount=6570000.00)
OrderItemAmountDto(orderId=226, productName=게이밍 노트북 RTX4060, quantity=3, lineAmount=6570000.00)
```

주목할 점 두 가지입니다.

**첫째, `orderItem.order.id` 는 조인을 만들지 않습니다.**
`order_id` 는 `order_items` 테이블에 이미 있는 FK 컬럼이라, Hibernate 가 조인 없이 그대로 읽습니다.
반면 `orderItem.product.name` 은 `products` 테이블을 봐야 하므로 조인이 필요합니다.
위에서 `join(orderItem.product, product)` 를 명시하지 않았다면
Hibernate 6 가 암시적 조인(implicit join)을 만들어 넣습니다 —
어떤 종류의 조인이 만들어지는지 통제할 수 없으니, **연관을 타고 들어갈 때는 명시적으로 조인하십시오.**

**둘째, `multiply` 의 결과 타입입니다.**
`orderItem.quantity` 는 `NumberPath<Integer>`, `orderItem.unitPrice` 는 `NumberPath<BigDecimal>` 입니다.
`quantity.multiply(unitPrice)` 는 `NumberExpression<Integer>` 로 추론되는 경우가 있어
DTO 생성자가 `BigDecimal` 을 받으면 타입이 어긋날 수 있습니다.
순서를 뒤집어 **`unitPrice.multiply(quantity)`** 로 쓰면 `BigDecimal` 로 안전하게 잡힙니다.

```java
orderItem.unitPrice.multiply(orderItem.quantity)   // NumberExpression<BigDecimal>
```

**결과** — SQL 의 곱셈 순서만 바뀝니다
```sql
select oi1_0.order_id, p1_0.name, oi1_0.quantity,
       oi1_0.unit_price * oi1_0.quantity
from order_items oi1_0
join products p1_0 on p1_0.product_id = oi1_0.product_id
```

명시적으로 캐스팅하고 싶으면 `Expressions.numberTemplate` 이나 `.castToNum(BigDecimal.class)` 를 씁니다.
자세한 표현식 조작은 [Step 13](../step-13-advanced/) 에서 다룹니다.

> 💡 **실무 팁 — 금액 계산은 곱셈 순서에 신경 쓰십시오**
> 자바에서는 `int * BigDecimal` 이 안 되지만 QueryDSL 은 표현식을 조립할 뿐이라 컴파일이 됩니다.
> 그리고 결과 타입 추론이 어긋나면 DTO 매핑에서 `No constructor found` 로 늦게 터집니다.
> **`BigDecimal` 쪽을 왼쪽에** 두는 것을 습관으로 만드십시오.

---

## 5-13. MySQL8 코스와 나란히

지금까지 본 프로젝션들을 SQL 로 쓰면 이렇습니다.

| 하려는 것 | SQL | QueryDSL |
|---|---|---|
| 전 컬럼 | `SELECT * FROM customers` | `selectFrom(customer)` |
| 컬럼 하나 | `SELECT name FROM customers` | `select(customer.name).from(customer)` |
| 컬럼 여러 개 | `SELECT name, city FROM customers` | `select(customer.name, customer.city)` → `Tuple` |
| 중복 제거 | `SELECT DISTINCT city ...` | `selectDistinct(customer.city)` |
| 컬럼 별칭 | `SELECT name AS user_name ...` | `customer.name.as("userName")` — **SQL 에는 안 나감** |
| 계산 컬럼 | `SELECT quantity * unit_price ...` | `orderItem.unitPrice.multiply(orderItem.quantity)` |
| 스칼라 서브쿼리 | `SELECT (SELECT COUNT(*) ...) AS cnt` | `ExpressionUtils.as(JPAExpressions.select(...), "cnt")` |

가장 중요한 차이는 **별칭의 의미**입니다.

```sql
-- SQL: AS 는 결과셋의 컬럼 이름을 바꾼다. 클라이언트가 그 이름으로 읽는다.
SELECT name AS user_name, city FROM customers;
```

```java
// QueryDSL: as() 는 SQL 에 나가지 않는다. DTO 필드를 찾는 데만 쓰인다.
customer.name.as("userName")
```

**결과** — 생성 SQL 에 `as` 가 없습니다
```sql
select c1_0.name, c1_0.city
from customers c1_0
```

SQL 에 익숙한 사람이 가장 자주 오해하는 지점입니다.
`as("userName")` 을 써 놓고 "SQL 에 별칭이 왜 안 붙지?" 라고 묻는 것은 방향이 틀렸습니다.
QueryDSL 의 `as()` 는 **자바 객체 조립을 위한 표시**이지 SQL 문법이 아닙니다.

> 📌 MySQL8 코스 [Step 04 — SELECT 기초](../../mysql8/step-04-select-basics/) 에서
> `SELECT *` 대신 필요한 컬럼만 쓰라고 했던 이유와, 이 스텝에서 프로젝션을 쓰는 이유는 같습니다.
> 다만 JPA 에는 **영속성 컨텍스트 등록과 스냅샷** 이라는 비용이 하나 더 붙습니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 엔티티 조회 | 전 컬럼 SELECT + 영속성 컨텍스트 등록 + 스냅샷 보관. 변경할 것만 이렇게 읽는다 |
| 프로젝션 | 필요한 컬럼만. 영속성 컨텍스트에 등록되지 않는다 |
| 단일 컬럼 | `select(customer.name)` → `List<String>` |
| `Tuple` | 여러 컬럼. `t.get(customer.name)`. **리포지토리 밖으로 내보내지 말 것** |
| `Projections.bean` | 기본 생성자 + setter. **setter 이름 매칭 (런타임)** |
| `Projections.fields` | 기본 생성자. 필드 리플렉션. **필드 이름 매칭 (런타임)** |
| ⚠️ 이름 불일치 | `bean`/`fields` 에서 **예외 없이 그 필드만 null**. `as()` 로 해결 |
| `as()` | SQL 별칭이 아니다. DTO 필드를 찾기 위한 이름표 |
| `ExpressionUtils.as()` | `.as()` 가 없는 표현식(서브쿼리 등)에 별칭 부여 |
| `Projections.constructor` | 순서 + 타입. 이름 오타에 강함. `final` 필드 가능 |
| ⚠️ 순서 뒤바뀜 | 같은 타입 파라미터 순서를 바꾸면 **조용히 값이 교차**. `as()` 로 못 막음 |
| `@QueryProjection` | `QXxxDto` 생성. **컴파일 시점 타입·개수 검증**. DTO 가 QueryDSL 에 의존 |
| 중첩 DTO | `Projections` 를 중첩. SQL 은 평평하고 조립만 중첩된다 |
| 연산 프로젝션 | `unitPrice.multiply(quantity)` — `BigDecimal` 을 왼쪽에 |

**이 스텝의 함정 2가지**

1. **`fields`/`bean` 의 이름 불일치 → 조용히 null.** SQL 은 정상이고 값도 왔는데 넣을 자리를 못 찾는다.
2. **`constructor` 의 순서 뒤바뀜 → 조용히 값 교차.** 타입이 같으면 예외조차 안 난다.

둘 다 `@QueryProjection` 을 쓰면 상당 부분 사라집니다.

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. `customers` 에서 **GOLD 등급 고객의 이메일만** 뽑아 `List<String>` 으로 받으세요.
   생성 SQL 에 `email` 컬럼 하나만 나오는지 확인하십시오.
2. `Tuple` 로 **도시별 고객 이름과 포인트**를 조회하고(포인트 상위 5명),
   `Tuple` 에서 값을 꺼내 출력하세요. 그다음 같은 결과를 `Projections.constructor` 로 다시 작성하고
   **왜 `Tuple` 을 반환하면 안 되는지** 주석으로 적으세요.
3. 아래 코드는 `userName` 이 전부 null 로 나옵니다. **SQL 을 바꾸지 말고** 고치세요.
   ```java
   queryFactory.select(Projections.fields(CustomerDto.class,   // DTO 필드: userName, homeCity
           customer.name, customer.city))
       .from(customer).fetch();
   ```
4. `OrderSummaryDto(Long orderId, String customerName, String shippingCity, BigDecimal totalAmount)` 를
   **`@QueryProjection` 으로** 만들고, 금액 상위 5건의 주문을 조회하세요.
   `customerName` 과 `shippingCity` 가 **둘 다 `String`** 이라는 점에 주의하십시오.
5. 문제 4의 DTO 생성자 파라미터 순서를 `(Long orderId, String shippingCity, String customerName, BigDecimal totalAmount)` 로
   바꾸면 어떤 일이 생기는지 **`Projections.constructor` 버전과 `@QueryProjection` 버전에서 각각** 확인하고
   차이를 주석으로 설명하세요.
6. `ExpressionUtils.as` 와 스칼라 서브쿼리로
   `CustomerReviewDto(고객명, 그 고객이 쓴 후기 수)` 를 조회하세요.
   후기를 한 번도 안 쓴 고객은 `0` 이 나와야 합니다. (전체 30명 중 후기 작성자는 4명입니다)

---

## 다음 단계

프로젝션은 "무엇을 꺼낼 것인가" 였습니다.
5-10 절에서 여러 엔티티의 값을 하나의 DTO 로 모으면서 이미 `join` 을 썼는데,
그때 `order_id = 1` 이 두 줄로 나온 것을 봤습니다. 그게 조인의 본질입니다.
다음 스텝에서는 **"어디서 꺼낼 것인가"** — 조인을 다룹니다.
`on` 과 `where` 의 차이, fetch join, 그리고 이 코스에서 가장 위험한 함정인
**fetch join + 페이징**이 거기 있습니다.

→ [Step 06 — 조인](../step-06-joins/)

---

## 실습 파일

이 스텝은 자바 파일 3개로 구성됩니다.
`Practice.java` 의 예제를 `[5-1] ~ [5-13]` 순서대로 실행해 콘솔의 SQL 을 본문과 대조하고,
`Exercise.java` 의 6문제를 직접 푼 뒤, `Solution.java` 로 채점하는 흐름입니다.
세 파일 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이므로
프로젝트의 `src/test/java/com/example/shop/step05/` 에 그대로 넣고 실행하면 됩니다.

DTO 는 각 파일 안에 `static` 중첩 클래스로 들어 있습니다.
`@QueryProjection` 이 붙은 DTO 는 **테스트 소스에도 APT 가 걸려 있어야** Q타입이 생성됩니다.
`build.gradle` 에 아래 한 줄이 없으면 `cannot find symbol: QCustomerDto` 가 납니다.

```groovy
testAnnotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
```

### Practice.java

본문 5-1 ~ 5-13 의 예제를 절 번호 주석으로 묶은 파일입니다.
절 번호가 본문 소제목과 1:1 로 대응하므로, 읽다가 막히면 같은 번호 블록을 찾아 실행해 보십시오.

- `[5-1]` 은 `selectFrom(customer)` 와 `select(customer.name, customer.city)` 를 연달아 실행합니다.
  두 SQL 의 **select 절 길이 차이**를 눈으로 비교하는 것이 목적입니다.
- `[5-6]` 이 이 파일의 심장입니다. `fieldsWithWrongName()` 을 먼저 실행해
  **30건 전부 `userName=null`** 이 찍히는 것을 확인한 뒤,
  바로 아래 `fieldsWithAlias()` 를 실행해 SQL 이 **한 글자도 안 바뀌었는데** 값이 채워지는 것을 봅니다.
  이 두 메서드는 반드시 순서대로 실행하십시오.
- `[5-7]` 의 `constructorSwapped()` 는 `SwappedDto` 라는 별도 DTO 를 씁니다.
  파라미터 순서만 뒤집힌 클래스로, 실행하면 `userName=서울, city=김서준` 이 나옵니다.
  **예외가 안 나는 것**이 이 메서드의 관전 포인트입니다.
- `[5-12]` 에는 `quantity.multiply(unitPrice)` 버전이 주석으로 남아 있습니다.
  주석을 풀면 프로젝트 설정에 따라 `No constructor found ... [Long, String, Integer, Integer]` 가 날 수 있습니다.
  타입 추론이 어긋나는 지점을 직접 확인하고 싶을 때만 푸십시오.

```java file="./Practice.java"
```

### Exercise.java

본문 연습문제 6개를 담은 빈칸 채우기용 파일입니다.
각 문제는 `// 문제 N.` 주석 블록으로 구분되어 있고 `// 여기에 작성:` 아래가 비어 있습니다.

- `[문제 3]` 만 예외적으로 **틀린 코드가 이미 작성되어 있습니다.**
  여러분이 할 일은 새로 쓰는 게 아니라, 그 코드를 실행해 null 을 직접 확인한 뒤 고치는 것입니다.
  `// SQL 은 바꾸지 마십시오` 라는 제약이 붙어 있습니다 — 답은 하나로 좁혀집니다.
- `[문제 5]` 는 DTO 를 두 벌 만들어야 합니다. 귀찮지만 이 문제가 이 스텝의 결론입니다.
  두 버전을 실제로 컴파일해 보면 `@QueryProjection` 쪽만 IDE 가 빨간 줄을 긋는 경우를 볼 수 있습니다.
- `[문제 6]` 의 "후기 작성자 4명" 은 MySQL8 코스 [Step 07](../../mysql8/step-07-joins/) 의
  `7-11` 절 결과와 같은 데이터입니다. 숫자가 다르면 시드가 최신이 아닙니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과 해설 주석을 담은 파일입니다. `Exercise.java` 를 스스로 풀어본 **뒤에** 열어보십시오.
각 정답 위 주석에 기대 결과와 생성 SQL 이 함께 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 3]` 의 핵심은 `city` 에도 `as("homeCity")` 가 필요하다는 것입니다.
  `name` 만 고치고 넘어가면 `homeCity` 가 null 로 남습니다.
  함정을 하나 고쳤다고 안심하면 안 되는 이유를 보여주는 문제입니다.
- `[정답 5]` 가 이 파일의 하이라이트입니다.
  `Projections.constructor` 버전은 **컴파일도 실행도 성공하고 값만 뒤바뀝니다.**
  `@QueryProjection` 버전은 DTO 를 고치는 순간 `QOrderSummaryDto` 가 재생성되므로
  `new QOrderSummaryDto(order.id, customer.name, order.shippingCity, order.totalAmount)` 자체는
  여전히 컴파일됩니다 — **같은 타입끼리는 컴파일러도 못 잡습니다.**
  이 한계까지 정확히 이해하는 것이 이 문제의 목표입니다.
- `[정답 6]` 은 `JPAExpressions.select(review.count()).from(review).where(review.customer.eq(customer))` 를
  `ExpressionUtils.as(..., "reviewCount")` 로 감쌉니다.
  `count()` 는 매칭이 없으면 `0` 을 돌려주므로 `COALESCE` 가 필요 없습니다 —
  같은 것을 `leftJoin` 으로 하면 `null` 이 나온다는 점을 [Step 06](../step-06-joins/) 에서 대조합니다.

```java file="./Solution.java"
```
