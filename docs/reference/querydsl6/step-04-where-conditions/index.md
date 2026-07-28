# Step 04 — 조건과 동적 쿼리

> **학습 목표**
> - `where()` 가 받는 `BooleanExpression` 이 무엇이고, `customer.grade.eq(...)` 가 왜 그 타입을 돌려주는지 설명한다
> - eq/in/between/like/contains/isNull 등 조건 메서드가 각각 어떤 SQL 조각과 바인딩을 만드는지 로그로 확인한다
> - `where(a, b, c)` 의 varargs 콤마가 `and` 라는 것과, `where(null)` 이 조용히 무시된다는 것을 확인한다
> - `BooleanExpression` 을 반환하는 메서드를 조립해 `if` 문 없는 동적 쿼리를 작성한다
> - `BooleanBuilder` 와 비교해 왜 `BooleanExpression` 방식이 선호되는지 근거를 댄다
> - **`or` 를 체이닝에 섞었을 때 괄호가 사라져 결과 건수가 달라지는 것을 재현하고, 세 가지 처방을 적용한다**
> - `eq(null)` 과 `NOT IN` + NULL 에서 SQL 의 3값 논리가 그대로 적용된다는 것을 확인한다
>
> **선행 스텝**: [Step 03 — 기본 조회](../step-03-basic-query/)
> **예상 소요**: 100분

---

## 4-0. 이 스텝이 QueryDSL 을 쓰는 이유입니다

Step 03 까지의 내용은 사실 JPQL 문자열로도 다 할 수 있습니다. 타입 안전성은 이득이지만, 그것만으로 빌드 설정을 늘리고 Q타입을 생성할 만큼은 아닐 수도 있습니다.

그런데 아래 요구사항이 들어오면 이야기가 달라집니다.

> 고객 검색 화면. 등급·도시·최소 포인트 세 조건 중 **사용자가 입력한 것만** 적용해 주십시오.

JPQL 문자열로는 이렇게 됩니다.

```java
StringBuilder jpql = new StringBuilder("select c from Customer c where 1=1");
if (grade != null)     jpql.append(" and c.grade = :grade");
if (city != null)      jpql.append(" and c.city = :city");
if (minPoints != null) jpql.append(" and c.points >= :minPoints");

TypedQuery<Customer> query = em.createQuery(jpql.toString(), Customer.class);
if (grade != null)     query.setParameter("grade", grade);
if (city != null)      query.setParameter("city", city);
if (minPoints != null) query.setParameter("minPoints", minPoints);
```

`if` 가 여섯 번 나오고, 앞뒤 두 묶음이 **항상 짝을 이뤄야** 합니다. 하나만 빠뜨리면 `IllegalArgumentException` 이거나, 더 나쁘게는 조건이 조용히 빠진 SQL 입니다. 그리고 `where 1=1` 이라는, 오로지 `and` 를 편하게 붙이려고 존재하는 관용구가 등장합니다.

같은 것을 QueryDSL 로 쓰면 이렇게 됩니다.

```java
queryFactory
        .selectFrom(customer)
        .where(gradeEq(grade), cityEq(city), pointsGoe(minPoints))
        .fetch();
```

`if` 가 한 번도 없습니다. 이 스텝은 이 다섯 줄을 이해하기 위한 것이고, 그 열쇠는 **`where(null)` 이 무시된다**는 성질 하나입니다.

---

## 4-1. BooleanExpression — where() 가 받는 것

`where()` 의 시그니처는 `where(Predicate... o)` 입니다. `Predicate` 는 "참/거짓을 판정하는 표현식"을 뜻하는 QueryDSL 의 최상위 인터페이스이고, 우리가 실제로 쓰는 것은 그 구현체인 **`BooleanExpression`** 입니다.

```java
BooleanExpression cond = customer.grade.eq(Grade.VIP);

System.out.println(cond.getClass().getName());
System.out.println(cond);
```

**결과**
```
com.querydsl.core.types.dsl.BooleanOperation
customer.grade = VIP
```

`customer.grade.eq(...)` 는 SQL 을 실행하지 않습니다. **"grade 가 VIP 와 같다"는 조건을 객체로 만들어 돌려줄 뿐**입니다. Step 03 의 지연 실행과 같은 이야기입니다. 조건은 값이고, 값이므로 변수에 담고, 메서드가 돌려주고, 리스트에 넣고, 다른 조건과 합칠 수 있습니다.

```java
BooleanExpression isVip = customer.grade.eq(Grade.VIP);
BooleanExpression inSeoul = customer.city.eq("서울");

List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(isVip.and(inSeoul))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] '서울'
조회 2건 — 김서준, 류하나
```

조건을 변수에 담을 수 있다는 것이 이 스텝 전체의 토대입니다. 문자열 조립과의 결정적 차이가 여기 있습니다. 문자열은 합칠 때 띄어쓰기와 괄호를 사람이 관리해야 하지만, `BooleanExpression` 은 **트리 구조**라 합치는 방식이 코드로 명시됩니다. 다만 그 "합치는 방식"을 잘못 쓰면 4-7 의 함정이 됩니다.

Q타입 필드의 타입에 따라 쓸 수 있는 조건 메서드가 달라집니다.

| Q타입 필드 | 클래스 | 대표 메서드 |
|---|---|---|
| `customer.name` | `StringPath` | `eq`, `like`, `contains`, `startsWith`, `upper()` |
| `customer.points` | `NumberPath<Integer>` | `eq`, `goe`, `lt`, `between`, `sum()` |
| `customer.grade` | `EnumPath<Grade>` | `eq`, `ne`, `in` |
| `customer.createdAt` | `DateTimePath<LocalDateTime>` | `before`, `after`, `between` |
| `customer.orders` | `ListPath<Order, QOrder>` | `isEmpty`, `isNotEmpty`, `size()` |

`customer.points.startsWith("1")` 은 **컴파일 에러**입니다. 숫자에 문자열 연산을 걸 수 없다는 것을 컴파일러가 압니다. Step 02 에서 Q타입이 왜 필드마다 다른 클래스를 쓰는지 설명했던 이유가 이것입니다.

---

## 4-2. 조건 메서드 총정리

자주 쓰는 것을 한 표에 모읍니다. `c1_0` 은 Hibernate 6 이 붙인 별칭입니다.

| 메서드 | 생성되는 SQL 조각 | 바인딩 |
|---|---|---|
| `.eq(v)` | `c1_0.col = ?` | `v` |
| `.ne(v)` | `c1_0.col != ?` | `v` |
| `.in(a, b)` | `c1_0.col in (?, ?)` | `a`, `b` |
| `.notIn(a, b)` | `c1_0.col not in (?, ?)` | `a`, `b` |
| `.between(a, b)` | `c1_0.col between ? and ?` | `a`, `b` |
| `.goe(v)` | `c1_0.col >= ?` | `v` |
| `.gt(v)` | `c1_0.col > ?` | `v` |
| `.loe(v)` | `c1_0.col <= ?` | `v` |
| `.lt(v)` | `c1_0.col < ?` | `v` |
| `.like("김%")` | `c1_0.col like ? escape '!'` | `'김%'` |
| `.contains("김")` | `c1_0.col like ? escape '!'` | `'%김%'` |
| `.startsWith("김")` | `c1_0.col like ? escape '!'` | `'김%'` |
| `.endsWith("준")` | `c1_0.col like ? escape '!'` | `'%준'` |
| `.isNull()` | `c1_0.col is null` | 없음 |
| `.isNotNull()` | `c1_0.col is not null` | 없음 |
| `.isEmpty()` | `not exists (select 1 from ...)` | 없음 |
| `.isNotEmpty()` | `exists (select 1 from ...)` | 없음 |

표만 보면 다 아는 것 같지만, **`like` 세 형제는 반드시 로그로 확인해야** 합니다. 생성되는 SQL 이 셋 다 똑같기 때문입니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.name.contains("김"))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.name like ? escape '!'
```
```
바인딩: [1] '%김%'
조회 3건 — 김서준, 김도현, 김나윤
```

**SQL 에는 `%` 가 없습니다.** `%김%` 은 **바인딩 파라미터 값**으로 들어갑니다. 그래서 SQL 로그만 보면 `contains` 인지 `startsWith` 인지 `like` 인지 구분할 수 없습니다. 바인딩 로그(`org.hibernate.orm.jdbc.bind`)를 켜야 하는 이유가 이것입니다.

세 개를 나란히 실행하면 이렇게 됩니다.

| 코드 | SQL | 바인딩 값 | 결과 |
|---|---|---|---|
| `.startsWith("김")` | `c1_0.name like ? escape '!'` | `'김%'` | 3건 |
| `.contains("김")` | `c1_0.name like ? escape '!'` | `'%김%'` | 3건 |
| `.endsWith("준")` | `c1_0.name like ? escape '!'` | `'%준'` | 2건 |
| `.like("김_")` | `c1_0.name like ? escape '!'` | `'김_'` | 0건 |

마지막 `like` 는 `%` 나 `_` 를 **직접 넣어야** 합니다. `contains` 처럼 자동으로 붙여 주지 않습니다.

> 💡 **실무 팁 — `escape '!'` 는 무엇인가**
> QueryDSL 이 자동으로 붙입니다. 검색어에 `%` 나 `_` 가 들어 있을 때 그것을 와일드카드가 아닌
> 리터럴로 다루기 위한 이스케이프 문자 지정입니다. 사용자가 검색창에 `50%` 라고 입력해도
> `contains("50%")` 가 `'%50!%%'` 로 이스케이프되어 "50% 라는 글자"를 찾습니다.
> 문자열 JPQL 을 손으로 조립하면 이 처리를 직접 해야 하고, 대부분 잊습니다.

> ⚠️ **함정 — `contains` 는 인덱스를 못 씁니다**
> `'%김%'` 는 앞에 `%` 가 붙은 LIKE 이고, B+Tree 인덱스는 앞부분이 고정돼야 탈 수 있습니다.
> MySQL8 코스 [Step 15 — 인덱스](../../mysql8/step-15-indexes/) 에서 확인한 그대로입니다.
> 30행짜리 `customers` 에서는 체감이 없지만, 수십만 행 테이블에 `contains` 검색을 걸면 풀 스캔입니다.
> **`startsWith` 로 대체할 수 있으면 대체하고**, 안 되면 전문 검색(Full-Text)이나 검색 엔진을 검토하십시오.

숫자와 날짜 조건도 확인합니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.points.between(10000, 30000))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.points between ? and ?
```
```
바인딩: [1] 10000  [2] 30000
조회 9건
```

`in` 은 여러 값을 한 번에 받습니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.grade.in(Grade.VIP, Grade.GOLD))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade in (?, ?)
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD
조회 13건
```

`in` 은 컬렉션도 받습니다. `customer.grade.in(List.of(Grade.VIP, Grade.GOLD))` 도 같은 SQL 입니다. 이 형태를 4-7 의 처방에서 다시 씁니다.

`isNull` / `isNotNull` 은 바인딩이 없습니다.

```java
List<Customer> noPhone = queryFactory
        .selectFrom(customer)
        .where(customer.phone.isNull())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.phone is null
```
```
바인딩: 없음
조회 3건
```

마지막으로 컬렉션 조건입니다. `isEmpty()` 는 `exists` 서브쿼리가 됩니다.

```java
List<Customer> neverOrdered = queryFactory
        .selectFrom(customer)
        .where(customer.orders.isEmpty())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where not exists (select 1 from orders o1_0 where c1_0.customer_id = o1_0.customer_id)
```
```
조회 0건 — 30명 모두 주문 이력이 있습니다
```

한 줄짜리 자바 코드가 서브쿼리를 만들어 냈다는 점을 기억해 두십시오. [Step 07 — 서브쿼리](../step-07-subqueries/) 에서 이 구조를 직접 씁니다.

---

## 4-3. and / or 체이닝과 varargs

조건을 합치는 방법이 두 가지입니다.

**(1) 체이닝** — `.and()` / `.or()` 를 이어 붙입니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP).and(customer.city.eq("서울")))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] '서울'
조회 2건 — 김서준, 류하나
```

**(2) varargs** — `where()` 에 콤마로 나열합니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.grade.eq(Grade.VIP),
                customer.city.eq("서울")
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] '서울'
조회 2건 — 김서준, 류하나
```

**완전히 같은 SQL 입니다.** 여기서 반드시 외워야 할 규칙이 나옵니다.

> **`where(a, b, c)` 의 콤마는 `and` 입니다. `or` 가 아닙니다.**

QueryDSL 은 varargs 로 받은 조건들을 `and` 로 묶습니다. 그리고 이 규칙에 예외가 없기 때문에, **`or` 를 varargs 로 표현할 방법이 없습니다.** `or` 는 반드시 하나의 표현식 안에서 체이닝으로 만들어야 합니다. 이 비대칭이 4-7 함정의 원인입니다.

세 개 이상도 마찬가지입니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.grade.eq(Grade.GOLD),
                customer.city.eq("서울"),
                customer.points.goe(10000)
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ? and c1_0.points >= ?
```
```
바인딩: [1] Grade.GOLD  [2] '서울'  [3] 10000
조회 3건 — 안지수, 한지호, 오하윤
```

---

## 4-4. where(null) 은 무시된다

이 절이 동적 쿼리의 전부입니다. 한 줄로 요약됩니다.

> **`where()` 에 넘긴 인자가 `null` 이면 QueryDSL 은 그 조건을 없는 것처럼 취급합니다.**

직접 확인합니다. 가운데 조건을 `null` 로 넣습니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.grade.eq(Grade.GOLD),
                null,                              // ← null
                customer.points.goe(10000)
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.points >= ?
```
```
바인딩: [1] Grade.GOLD  [2] 10000
조회 6건
```

**`null` 이 있던 자리가 SQL 에서 통째로 사라졌습니다.** `and null` 도 아니고 `and 1=1` 도 아니고, 아무 흔적이 없습니다. NPE 도 나지 않습니다.

전부 `null` 이면 `where` 절 자체가 사라집니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(null, null, null)
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
```
```
바인딩: 없음
조회 30건 — 전체
```

`where` 라는 단어조차 나오지 않습니다. `where 1=1` 같은 관용구가 필요 없는 이유입니다.

> ⚠️ **함정 — `and(null)` 은 다릅니다**
> 무시되는 것은 **`where()` 의 인자**이지 `and()` 의 인자가 아닙니다.
>
> ```java
> BooleanExpression cond = null;
> .where(customer.grade.eq(Grade.GOLD).and(cond))   // cond 가 null
> ```
>
> QueryDSL 의 `and(null)` 은 자기 자신을 그대로 돌려주도록 구현돼 있어 이 경우도 동작하지만,
> **체인의 첫 조각이 `null` 이면** 이야기가 다릅니다.
>
> ```java
> BooleanExpression first = null;
> .where(first.and(customer.city.eq("서울")))       // ← NullPointerException
> ```
>
> `null.and(...)` 는 그냥 자바의 NPE 입니다. 동적 조건을 체이닝으로 이어 붙일 때
> "첫 조건이 없을 수도 있다"는 경우를 처리하려면 결국 `if` 가 돌아옵니다.
> **동적 조건은 체이닝이 아니라 varargs 자리에 나열하십시오.** 그것이 4-5 의 패턴입니다.

---

## 4-5. 동적 쿼리 — BooleanExpression 반환 메서드 조립

이제 4-0 의 요구사항을 완성합니다. 핵심은 **조건 하나당 메서드 하나**를 만들고, 값이 없으면 `null` 을 돌려주는 것입니다.

```java
private BooleanExpression gradeEq(Grade grade) {
    return grade != null ? customer.grade.eq(grade) : null;
}

private BooleanExpression cityEq(String city) {
    return StringUtils.hasText(city) ? customer.city.eq(city) : null;
}

private BooleanExpression pointsGoe(Integer minPoints) {
    return minPoints != null ? customer.points.goe(minPoints) : null;
}

public List<Customer> search(Grade grade, String city, Integer minPoints) {
    return queryFactory
            .selectFrom(customer)
            .where(gradeEq(grade), cityEq(city), pointsGoe(minPoints))
            .fetch();
}
```

`search` 메서드에 `if` 가 없습니다. 조건이 있으면 `BooleanExpression` 이 반환되어 `and` 로 붙고, 없으면 `null` 이 반환되어 사라집니다.

> 💡 문자열 조건은 `city != null` 이 아니라 `StringUtils.hasText(city)` 를 쓰십시오.
> HTTP 요청 파라미터는 미입력 시 `null` 이 아니라 **빈 문자열 `""`** 로 들어오는 경우가 많습니다.
> `city != null` 만 검사하면 `where c1_0.city = ''` 라는, 결과가 0건인 조건이 붙습니다.
> 예외도 안 나고 화면만 비어 있는 전형적인 "조용한 버그"입니다.

### 조합별 생성 SQL 전부

호출 인자에 따라 SQL 이 어떻게 달라지는지 전부 확인합니다. **하나의 메서드가 만들어 내는 SQL 입니다.**

**(1) `search(null, null, null)` — 조건 없음**

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
```
```
바인딩: 없음
조회 30건
```

**(2) `search(Grade.GOLD, null, null)` — 등급만**

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.GOLD
조회 9건
```

**(3) `search(null, "서울", null)` — 도시만**

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.city = ?
```
```
바인딩: [1] '서울'
조회 8건
```

**(4) `search(null, null, 10000)` — 최소 포인트만**

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.points >= ?
```
```
바인딩: [1] 10000
조회 12건
```

**(5) `search(Grade.GOLD, "서울", null)` — 등급 + 도시**

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ?
```
```
바인딩: [1] Grade.GOLD  [2] '서울'
조회 4건 — 안지수, 한지호, 오하윤, 문시우
```

**(6) `search(Grade.GOLD, "서울", 10000)` — 셋 다**

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ? and c1_0.points >= ?
```
```
바인딩: [1] Grade.GOLD  [2] '서울'  [3] 10000
조회 3건 — 안지수, 한지호, 오하윤
```

여섯 개의 SQL 이 **하나의 메서드 다섯 줄**에서 나왔습니다. `if` 는 조건 메서드 안에 하나씩, 총 세 번만 존재합니다.

### 이 패턴의 진짜 이득 — 조합 가능성

`BooleanExpression` 을 반환한다는 것은 **조건 메서드끼리 다시 합칠 수 있다**는 뜻입니다.

```java
// "서울에 사는 GOLD" 를 자주 쓴다면, 조건을 합친 메서드를 하나 더 만듭니다
private BooleanExpression seoulGold() {
    return gradeEq(Grade.GOLD).and(cityEq("서울"));
}

// "우수 고객" 의 정의를 한 곳에 모읍니다
private BooleanExpression isPremium() {
    return customer.grade.in(Grade.VIP, Grade.GOLD)
            .and(customer.points.goe(10000));
}
```

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(isPremium())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade in (?, ?) and c1_0.points >= ?
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD  [3] 10000
조회 10건
```

"우수 고객"이라는 **비즈니스 규칙이 코드 한 곳**에 있습니다. 규칙이 바뀌면 이 메서드만 고칩니다. 여러 쿼리에 흩어진 `where` 절을 찾아다닐 필요가 없습니다.

> 💡 **실무 팁 — 조건 메서드는 `null` 반환 가능성을 이름이나 주석에 남기십시오**
> `gradeEq(Grade)` 는 `null` 을 돌려줄 수 있습니다. 그것을 모르고 `.and()` 로 체이닝하면
> 4-4 의 NPE 를 만납니다. 팀 규칙으로 "동적 조건 메서드는 varargs 자리에만 넣는다" 를 정하거나,
> `@Nullable` 을 붙이십시오.

---

## 4-6. BooleanBuilder — 그리고 왜 덜 쓰는가

같은 동적 쿼리를 `BooleanBuilder` 로도 쓸 수 있습니다. 오래된 코드베이스에서 자주 보이는 형태입니다.

```java
public List<Customer> searchWithBuilder(Grade grade, String city, Integer minPoints) {
    BooleanBuilder builder = new BooleanBuilder();

    if (grade != null) {
        builder.and(customer.grade.eq(grade));
    }
    if (StringUtils.hasText(city)) {
        builder.and(customer.city.eq(city));
    }
    if (minPoints != null) {
        builder.and(customer.points.goe(minPoints));
    }

    return queryFactory
            .selectFrom(customer)
            .where(builder)
            .fetch();
}
```

**결과** — `searchWithBuilder(Grade.GOLD, "서울", 10000)`
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.city = ? and c1_0.points >= ?
```
```
바인딩: [1] Grade.GOLD  [2] '서울'  [3] 10000
조회 3건
```

**생성 SQL 이 4-5 와 완전히 같습니다.** 기능적으로 못 하는 일은 없습니다. 그럼에도 대부분의 팀이 `BooleanExpression` 방식을 표준으로 삼는 이유가 있습니다.

| 기준 | `BooleanExpression` 조립 | `BooleanBuilder` |
|---|---|---|
| **가독성** | `where(gradeEq(g), cityEq(c), pointsGoe(p))` — 조건이 한 줄에 보임 | `if` 블록을 위에서 아래로 읽어야 조건 파악 |
| **재사용** | 조건 메서드를 다른 쿼리에서 그대로 호출 | 빌더 조립 로직을 통째로 복사하거나 메서드 추출 |
| **조합 가능성** | `gradeEq(g).and(cityEq(c))` 로 메서드끼리 합침 | 빌더끼리 합치려면 `builder.and(otherBuilder)` — 결과가 무엇인지 불명확 |
| **null 처리** | `null` 반환 → `where` 가 무시. `if` 가 조건 메서드 안에 캡슐화 | `if` 를 조립부에 노출. 조건이 늘면 `if` 도 늘어남 |
| **초기값 문제** | 없음 | `new BooleanBuilder()` 는 비어 있고, 아무것도 `and` 안 하면 `where` 절이 사라짐 (의도인지 실수인지 구분 불가) |
| **테스트** | 조건 메서드를 단독으로 단언 가능 | 쿼리를 실행해야 검증 가능 |
| **`where` 절 확인** | 코드만 보면 어떤 조건이 후보인지 명확 | 런타임에 어떤 조건이 들어갔는지 추적해야 함 |

특히 **초기값 문제**가 실무에서 사고를 냅니다.

```java
BooleanBuilder builder = new BooleanBuilder();
// 조건을 하나도 안 붙였습니다 (버그로 if 조건이 다 false 였다고 합시다)

queryFactory.selectFrom(customer).where(builder).fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
```
```
조회 30건 — 전체 조회
```

빈 빌더는 조용히 "조건 없음"이 됩니다. 30행이면 괜찮지만, **삭제나 수정 쿼리**에서 이러면 전체 행이 대상이 됩니다. `BooleanExpression` 방식도 전부 `null` 이면 같은 결과지만, `where(gradeEq(g), cityEq(c))` 라는 코드는 "조건이 없을 수도 있다"는 것이 호출부에 그대로 드러납니다. 빌더는 조립 로직 안에 숨습니다.

> 💡 **실무 팁 — `BooleanBuilder` 를 써야 할 때도 있습니다**
> 조건 개수가 런타임에 정해지는 경우(예: 사용자가 필터를 N개 추가하는 화면)에는
> 반복문 안에서 `builder.and(...)` 를 누적하는 편이 자연스럽습니다.
> `BooleanExpression` 배열을 만들어 넘기는 것도 가능하지만, 그때는 빌더가 더 읽힙니다.
> **기본은 `BooleanExpression`, 반복 누적이 필요할 때만 빌더** 로 기억하십시오.

---

## 4-7. ⚠️ or 를 섞으면 괄호가 사라진다

이 스텝의 핵심 함정입니다. **컴파일도 되고, 예외도 안 나고, SQL 도 정상이고, 결과 건수만 다릅니다.**

### 의도한 쿼리

> "VIP 또는 GOLD 등급이면서, 서울에 사는 고객"

논리식으로 쓰면 `(VIP or GOLD) and 서울` 입니다. 데이터를 미리 확인해 둡니다.

| | 전체 | 서울 |
|---|---|---|
| VIP | 4명 | 2명 (김서준, 류하나) |
| GOLD | 9명 | 4명 (안지수, 한지호, 오하윤, 문시우) |

따라서 정답은 **6명**입니다.

### 올바른 코드 — varargs 로 나눈다

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.grade.eq(Grade.VIP).or(customer.grade.eq(Grade.GOLD)),
                customer.city.eq("서울")
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD  [3] '서울'
조회 6건 — 김서준, 류하나, 안지수, 한지호, 오하윤, 문시우
```

**괄호가 붙었습니다.** varargs 의 첫 번째 인자 전체가 하나의 표현식이므로, QueryDSL 이 `and` 로 묶으면서 괄호를 씌워 줍니다. **의도한 6건.**

### 실수 ① — `.and()` 와 `.or()` 를 이어 붙였다

가장 흔한 실수입니다. 사람은 "서울에 살고, VIP 또는 GOLD" 라고 말한 순서 그대로 씁니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.city.eq("서울")
                        .and(customer.grade.eq(Grade.VIP))
                        .or(customer.grade.eq(Grade.GOLD))
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.city = ? and c1_0.grade = ? or c1_0.grade = ?
```
```
바인딩: [1] '서울'  [2] Grade.VIP  [3] Grade.GOLD
조회 11건 — 김서준, 류하나, 안지수, 한지호, 오하윤, 문시우,
           강도윤, 윤서아, 임하준, 조은우, 신지아
```

**괄호가 하나도 없습니다.** 메서드 체이닝은 **왼쪽에서 오른쪽으로** 평가되므로 `(서울 and VIP) or GOLD` 가 됐습니다. SQL 로 넘어가서는 `and` 가 `or` 보다 우선순위가 높으니 결과가 같습니다.

- 의도: 서울 사는 VIP/GOLD → **6명**
- 실제: (서울 사는 VIP 2명) 또는 (**전국의** GOLD 9명) → **11명**

**부산의 강도윤, 인천의 임하준, 광주의 신지아가 결과에 들어왔습니다.** 서울을 조건으로 넣었는데 서울이 아닌 사람이 나옵니다.

### 실수 ② — 괄호는 쳤는데 위치를 잘못 잡았다

"괄호가 필요하다"는 것까지는 알았지만 어디에 치는지 헷갈린 경우입니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.grade.eq(Grade.VIP)
                        .or(customer.grade.eq(Grade.GOLD).and(customer.city.eq("서울")))
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? or c1_0.grade = ? and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD  [3] '서울'
조회 8건 — 김서준, 류하나, 정  훈, 배채영, 안지수, 한지호, 오하윤, 문시우
```

`A or (B and C)` 입니다. **전국의 VIP 4명** + **서울 GOLD 4명** = 8명. 부산의 정  훈, 대구의 배채영이 들어왔습니다.

### 세 SQL 을 나란히

| | 코드 | 생성 SQL 의 where 절 | 결과 |
|---|---|---|---|
| **의도** | `where(A.or(B), C)` | `where (grade = ? or grade = ?) and city = ?` | **6건** ✅ |
| 실수 ① | `where(C.and(A).or(B))` | `where city = ? and grade = ? or grade = ?` | 11건 ❌ |
| 실수 ② | `where(A.or(B.and(C)))` | `where grade = ? or grade = ? and city = ?` | 8건 ❌ |

셋 다 **컴파일 성공, 실행 성공, 예외 없음**입니다. 6 / 11 / 8. 결과 건수만 다릅니다.

> ⚠️ **함정 — 이런 버그는 테스트를 통과합니다**
> 개발 데이터가 서울 고객만으로 채워져 있으면 세 코드가 **전부 같은 결과**를 냅니다.
> 운영 데이터가 들어오는 순간 조용히 벌어집니다. 그리고 "왜 부산 고객이 서울 필터에 나오죠?" 라는
> 문의로 발견됩니다. 그때쯤이면 이미 그 쿼리를 여러 화면이 복사해 쓰고 있습니다.
>
> 근본 원인은 하나입니다. **`.and()` / `.or()` 체이닝에는 우선순위가 없고, 무조건 왼쪽부터 묶입니다.**
> 자바 코드의 `a && b || c` 는 `&&` 가 먼저지만, 메서드 체인의 `a.and(b).or(c)` 는 그냥 순서대로입니다.
> 눈은 자바 연산자 우선순위를 기대하는데 코드는 체이닝 순서를 따릅니다. 이 어긋남이 함정의 정체입니다.

### 처방 세 가지

**처방 1 — or 그룹을 별도 메서드로 뽑는다 (가장 권장)**

`or` 로 묶인 덩어리를 **이름 있는 하나의 조건**으로 만들어 varargs 자리에 넣습니다.

```java
private BooleanExpression isVipOrGold() {
    return customer.grade.eq(Grade.VIP).or(customer.grade.eq(Grade.GOLD));
}

List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(isVipOrGold(), customer.city.eq("서울"))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD  [3] '서울'
조회 6건 ✅
```

`or` 가 메서드 안에 갇혀 있으므로 밖에서 체이닝 순서를 헷갈릴 여지가 없습니다. 게다가 `isVipOrGold()` 라는 이름이 비즈니스 의미를 드러냅니다.

**처방 2 — `Expressions.allOf` / `Expressions.anyOf`**

`anyOf` 는 `or`, `allOf` 는 `and` 로 묶습니다. 괄호를 라이브러리가 관리합니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                Expressions.allOf(
                        Expressions.anyOf(
                                customer.grade.eq(Grade.VIP),
                                customer.grade.eq(Grade.GOLD)
                        ),
                        customer.city.eq("서울")
                )
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where (c1_0.grade = ? or c1_0.grade = ?) and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD  [3] '서울'
조회 6건 ✅
```

중첩이 깊어지면 읽기 어려워지지만, **괄호 구조가 코드 들여쓰기와 1:1로 대응**한다는 장점이 있습니다. 조건이 동적으로 결정되는 `or` 그룹에도 잘 맞습니다. `Expressions.anyOf` 도 `null` 인자를 무시합니다.

**처방 3 — 애초에 `or` 를 쓰지 않는다**

같은 컬럼에 대한 `or` 는 대부분 `in` 으로 바꿀 수 있습니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.grade.in(Grade.VIP, Grade.GOLD),
                customer.city.eq("서울")
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade in (?, ?) and c1_0.city = ?
```
```
바인딩: [1] Grade.VIP  [2] Grade.GOLD  [3] '서울'
조회 6건 ✅
```

`or` 가 사라졌으니 괄호를 걱정할 일도 없습니다. SQL 도 더 짧고, 옵티마이저도 `in` 을 더 잘 다룹니다. **같은 컬럼의 `or` 는 `in` 으로 바꿀 수 있는지 먼저 확인하십시오.**

정리하면 이렇습니다.

| 상황 | 처방 |
|---|---|
| 같은 컬럼의 `or` | `in` 으로 교체 (처방 3) |
| 다른 컬럼의 `or` 그룹 | 별도 메서드로 추출 (처방 1) |
| `or` 그룹 자체가 동적 | `Expressions.anyOf` (처방 2) |
| **절대 하지 말 것** | `where` 안에서 `.and()` 와 `.or()` 를 섞어 체이닝 |

---

## 4-8. null 안전 조건 — isNull() vs eq(null)

`customers` 30명 중 `phone` 이 NULL 인 사람은 3명입니다. 이들을 찾는 코드입니다.

```java
List<Customer> noPhone = queryFactory
        .selectFrom(customer)
        .where(customer.phone.isNull())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.phone is null
```
```
조회 3건
```

그럼 `eq(null)` 은 어떨까요. 자바 감각으로는 "phone == null" 처럼 읽힙니다.

**SQL 의 관점부터 확인합니다.** MySQL8 코스 [Step 05 — 연산자와 조건](../../mysql8/step-05-where-operators/) 과 [부록 A — NULL 완전 정복](../../mysql8/appendix-a-null/) 에서 다룬 3값 논리입니다.

```sql
SELECT * FROM customers WHERE phone = NULL;
```

**결과**
```
Empty set (0.00 sec)
```

`= NULL` 은 참이 되지 않습니다. NULL 은 "값이 없음"이 아니라 **"알 수 없음"** 이고, "알 수 없는 것"과 무엇을 비교해도 결과는 참도 거짓도 아닌 **UNKNOWN** 입니다. `WHERE` 절은 UNKNOWN 을 통과시키지 않으므로 0건입니다. `phone IS NULL` 인 3명조차 나오지 않습니다.

**QueryDSL 쪽 이야기입니다.** `customer.phone.eq(null)` 은 컴파일이 됩니다. `eq(String)` 에 `null` 을 넘기는 것이니까요. 그러나 이때 QueryDSL 이 취하는 동작은 **버전과 경로에 따라 다를 수 있습니다.** 조건 자체를 만들지 않고 `null` 을 돌려주는 경우도, 인자 검증에서 예외를 던지는 경우도, 그대로 `= ?` 에 `null` 을 바인딩해 위 SQL 처럼 0건이 되는 경우도 보고돼 있습니다. **이 코스는 어느 하나로 단정하지 않습니다.** 여러분이 쓰는 정확한 버전에서 직접 확인하십시오.

확인해야 할 이유는, 세 동작이 **전부 다른 버그**를 만들기 때문입니다.

| 만약 이렇게 동작한다면 | 결과 |
|---|---|
| 조건을 무시 (`null` 반환) | 그 조건이 통째로 사라져 **전체 조회** |
| 예외를 던짐 | 즉시 실패 — 그나마 안전 |
| `= ?` 에 `null` 바인딩 | **0건** (3값 논리) |

"전체 조회"와 "0건"은 정반대 결과입니다. 어느 쪽이 나올지 코드를 봐서는 알 수 없다면, 그 코드는 쓰면 안 됩니다.

> ⚠️ **함정 — `eq(변수)` 에서 변수가 `null` 일 수 있다면**
> 위험한 것은 리터럴 `eq(null)` 이 아닙니다. 그건 눈에 보입니다.
> 진짜 문제는 이것입니다.
>
> ```java
> public List<Customer> findByPhone(String phone) {
>     return queryFactory.selectFrom(customer)
>             .where(customer.phone.eq(phone))     // phone 이 null 로 들어올 수 있다면?
>             .fetch();
> }
> ```
>
> 호출부가 `findByPhone(null)` 을 하는 순간 위 표의 세 갈래 중 하나로 갑니다.
> **처방은 명시적으로 쓰는 것입니다.**
>
> ```java
> .where(phone != null ? customer.phone.eq(phone) : customer.phone.isNull())
> ```
>
> 또는 그냥 "phone 이 없으면 이 조건을 빼겠다"가 의도라면 4-5 의 패턴을 쓰십시오.
>
> ```java
> private BooleanExpression phoneEq(String phone) {
>     return phone != null ? customer.phone.eq(phone) : null;
> }
> ```
>
> **지침은 하나입니다. NULL 을 찾고 싶으면 `isNull()` 을, 조건을 빼고 싶으면 `null` 반환을 쓰십시오.
> `eq(null)` 에 의미를 기대하지 마십시오.**

`isNotNull()` 도 확인합니다.

```java
List<Customer> hasPhone = queryFactory
        .selectFrom(customer)
        .where(customer.phone.isNotNull())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.phone is not null
```
```
조회 27건
```

3 + 27 = 30. `isNull()` 과 `isNotNull()` 은 전체를 정확히 둘로 나눕니다. `= NULL` 과 `!= NULL` 은 **둘 다 0건**이라 합이 0입니다. 이 대비가 3값 논리의 요약입니다.

---

## 4-9. NOT IN 과 NULL — SQL 의 3값 논리는 그대로 적용됩니다

> 📌 MySQL8 코스 [Step 08 — 서브쿼리](../../mysql8/step-08-subqueries/) 에서 다룬 함정입니다.
> **QueryDSL 로 써도 결과는 똑같습니다.** QueryDSL 은 SQL 을 만들어 줄 뿐, SQL 의 의미를 바꾸지 않습니다.

특정 번호를 쓰는 고객을 제외하고 나머지를 찾습니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.phone.notIn("010-1111-2222", "010-3333-4444"))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.phone not in (?, ?)
```
```
바인딩: [1] '010-1111-2222'  [2] '010-3333-4444'
조회 25건
```

**25건입니다. 28건을 기대했다면 틀렸습니다.**

고객은 30명이고 그 두 번호를 쓰는 사람이 2명이니 30 − 2 = 28이 상식입니다. 그런데 **`phone` 이 NULL 인 3명이 결과에서 빠졌습니다.**

이유는 4-8 과 같습니다. `NOT IN` 은 내부적으로 이렇게 전개됩니다.

```
phone NOT IN ('010-1111-2222', '010-3333-4444')
  ≡  phone != '010-1111-2222' AND phone != '010-3333-4444'
```

`phone` 이 NULL 이면 각 비교가 UNKNOWN 이고, `UNKNOWN AND UNKNOWN` 은 UNKNOWN 입니다. `WHERE` 는 UNKNOWN 을 통과시키지 않으므로 그 행은 제외됩니다.

> ⚠️ **함정 — "제외했더니 있어야 할 행까지 사라졌다"**
> 이 버그의 특징은 **누락 방향**이라는 것입니다. 있어야 할 데이터가 안 나오는 쪽이라
> 화면에서 즉시 티가 나지 않습니다. "전체 30명인데 목록에 25명뿐" 을 알아채려면
> 누군가 세어 봐야 합니다.
>
> 그리고 이 함정은 **컬럼이 NULL 을 허용하는 한 언제든** 발생합니다.
> 지금은 안 나던 버그가 "phone 을 선택 입력으로 바꾸자"는 기획 변경 한 줄로 생겨납니다.

### 처방

**처방 1 — NULL 을 명시적으로 살린다**

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(
                customer.phone.notIn("010-1111-2222", "010-3333-4444")
                        .or(customer.phone.isNull())
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.phone not in (?, ?) or c1_0.phone is null
```
```
바인딩: [1] '010-1111-2222'  [2] '010-3333-4444'
조회 28건 ✅
```

`or` 를 썼습니다. 4-7 을 배웠으니, 여기에 조건이 하나라도 더 붙는다면 **이 표현식을 메서드로 뽑아야** 한다는 것도 아실 겁니다.

```java
private BooleanExpression phoneNotIn(String... phones) {
    return customer.phone.notIn(phones).or(customer.phone.isNull());
}
```

**처방 2 — `coalesce` 로 NULL 을 값으로 바꾼다**

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.phone.coalesce("").asString()
                .notIn("010-1111-2222", "010-3333-4444"))
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where coalesce(c1_0.phone, ?) not in (?, ?)
```
```
바인딩: [1] ''  [2] '010-1111-2222'  [3] '010-3333-4444'
조회 28건 ✅
```

결과는 맞지만 **컬럼을 함수로 감쌌으므로 인덱스를 못 탑니다.** MySQL8 코스 [Step 15 — 인덱스](../../mysql8/step-15-indexes/) 에서 "컬럼을 가공하지 말고 리터럴을 가공하라"고 했던 그 규칙입니다. 30행이면 상관없지만 큰 테이블에서는 처방 1 을 쓰십시오.

**처방 3 — 애초에 컬럼을 NOT NULL 로 설계한다**

가장 근본적인 해결입니다. NULL 을 허용할 이유가 정말 있는지 되묻고, 없으면 `NOT NULL DEFAULT ''` 로 바꿉니다. 3값 논리 문제 전체가 사라집니다.

> 📌 서브쿼리를 `NOT IN` 에 넣으면 이 함정이 훨씬 잘 숨습니다.
> 서브쿼리 결과에 NULL 이 **한 행이라도** 섞이면 전체 결과가 0건이 됩니다.
> [Step 07 — 서브쿼리](../step-07-subqueries/) 에서 `notIn(subquery)` 대신
> `notExists(subquery)` 를 쓰라는 이유가 여기 있습니다.

---

## 4-10. 검색 조건 객체로 정리하기

조건이 세 개일 때는 파라미터를 나열해도 괜찮습니다. 여섯 개가 되면 호출부가 이렇게 됩니다.

```java
search(null, "서울", null, 10000, null, ProductStatus.ON_SALE);
```

어느 `null` 이 무엇인지 알 수 없습니다. 그래서 조건을 객체로 묶습니다.

```java
public record ProductSearchCond(
        String nameKeyword,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        ProductStatus status,
        Boolean inStockOnly
) {}
```

```java
public List<Product> search(ProductSearchCond cond) {
    return queryFactory
            .selectFrom(product)
            .where(
                    nameContains(cond.nameKeyword()),
                    categoryEq(cond.categoryId()),
                    priceGoe(cond.minPrice()),
                    priceLoe(cond.maxPrice()),
                    statusEq(cond.status()),
                    inStock(cond.inStockOnly())
            )
            .fetch();
}

private BooleanExpression nameContains(String keyword) {
    return StringUtils.hasText(keyword) ? product.name.contains(keyword) : null;
}
private BooleanExpression categoryEq(Long categoryId) {
    return categoryId != null ? product.category.id.eq(categoryId) : null;
}
private BooleanExpression priceGoe(BigDecimal min) {
    return min != null ? product.price.goe(min) : null;
}
private BooleanExpression priceLoe(BigDecimal max) {
    return max != null ? product.price.loe(max) : null;
}
private BooleanExpression statusEq(ProductStatus status) {
    return status != null ? product.status.eq(status) : null;
}
private BooleanExpression inStock(Boolean only) {
    return Boolean.TRUE.equals(only) ? product.stock.gt(0) : null;
}
```

`search(new ProductSearchCond("노트북", null, new BigDecimal("500000"), null, ProductStatus.ON_SALE, true))` 를 호출하면 이렇게 됩니다.

**결과**
```sql
select p1_0.product_id, p1_0.attrs, p1_0.category_id, p1_0.cost,
       p1_0.created_at, p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.name like ? escape '!'
  and p1_0.price >= ?
  and p1_0.status = ?
  and p1_0.stock > ?
```
```
바인딩: [1] '%노트북%'  [2] 500000.00  [3] ProductStatus.ON_SALE  [4] 0
조회 2건 — 게이밍 노트북 RTX4060(2190000), 보급형 노트북 15(690000)
```

여섯 개 조건 중 넷만 SQL 에 나갔습니다. `categoryId` 와 `maxPrice` 는 `null` 이라 사라졌습니다.

`inStock` 의 `Boolean.TRUE.equals(only)` 표현을 눈여겨보십시오. `only` 가 `null` 일 때 `only == true` 는 NPE, `only` 는 언박싱 NPE 입니다. `Boolean.TRUE.equals(only)` 는 `null` 에 안전합니다. `Boolean` 을 조건에 쓸 때는 이 관용구를 쓰십시오.

> 📌 이 패턴을 저장소 계층으로 옮기고 Spring Data JPA 의 커스텀 리포지토리와 결합하는 것이
> [Step 10 — 동적 정렬](../step-10-dynamic-sort/) 과 [Step 12 — Spring Data 통합](../step-12-spring-data/) 의 주제입니다.
> 정렬 조건까지 동적으로 만들면 `OrderSpecifier` 를 다뤄야 하고, 거기에는 여기와 다른 함정이 있습니다.

---

## 4-11. MySQL8 코스와 나란히

> 📌 MySQL8 코스 [Step 05 — 연산자와 조건](../../mysql8/step-05-where-operators/) 의 SQL 을 QueryDSL 로 옮긴 표입니다.

| # | SQL | QueryDSL |
|---|---|---|
| 1 | `WHERE grade = 'VIP'` | `.where(customer.grade.eq(Grade.VIP))` |
| 2 | `WHERE grade <> 'VIP'` | `.where(customer.grade.ne(Grade.VIP))` |
| 3 | `WHERE grade IN ('VIP','GOLD')` | `.where(customer.grade.in(Grade.VIP, Grade.GOLD))` |
| 4 | `WHERE points BETWEEN 10000 AND 30000` | `.where(customer.points.between(10000, 30000))` |
| 5 | `WHERE points >= 10000` | `.where(customer.points.goe(10000))` |
| 6 | `WHERE name LIKE '김%'` | `.where(customer.name.startsWith("김"))` |
| 7 | `WHERE name LIKE '%김%'` | `.where(customer.name.contains("김"))` |
| 8 | `WHERE phone IS NULL` | `.where(customer.phone.isNull())` |
| 9 | `WHERE grade = 'VIP' AND city = '서울'` | `.where(customer.grade.eq(Grade.VIP), customer.city.eq("서울"))` |
| 10 | `WHERE (grade='VIP' OR grade='GOLD') AND city='서울'` | `.where(customer.grade.in(Grade.VIP, Grade.GOLD), customer.city.eq("서울"))` |
| 11 | `WHERE phone NOT IN (...)` | `.where(customer.phone.notIn(...))` — **NULL 행 누락 주의** |

세 가지를 짚습니다.

**첫째, `>=` 가 `goe` 입니다.** greater or equal 의 약자입니다. `gt`(greater than), `loe`(less or equal), `lt`(less than) 도 같은 규칙입니다. 처음에는 어색하지만, `>=` 를 문자열로 조립하다 `>` 를 오타 내는 것보다 훨씬 낫습니다.

**둘째, 10번 줄이 이 스텝의 결론입니다.** SQL 에서는 괄호를 손으로 칩니다. 손으로 치는 것이므로 빠뜨리면 문법 에러가 나거나, 최소한 눈에 보입니다. QueryDSL 에서는 괄호가 **체이닝 구조로부터 유도**되므로 눈에 보이지 않습니다. 그래서 `in` 으로 바꾸거나 varargs 로 나누는 처방이 필요합니다.

**셋째, 11번의 `NOT IN` 은 QueryDSL 로 써도 SQL 과 똑같이 동작합니다.** 이 표 전체를 관통하는 메시지이기도 합니다. **QueryDSL 은 SQL 위의 얇은 층입니다.** 타입 안전성과 조립 가능성을 얹어 줄 뿐, SQL 의 의미론(3값 논리, 연산자 우선순위, 인덱스 사용 여부)은 그대로 통과합니다. SQL 을 모르면 QueryDSL 도 제대로 못 씁니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `BooleanExpression` | `where()` 가 받는 조건 객체. 실행이 아니라 값. 변수에 담고 메서드로 반환 가능 |
| 조건 메서드 | `eq/ne/in/notIn/between/goe/gt/loe/lt/like/contains/startsWith/endsWith/isNull/isNotNull/isEmpty/isNotEmpty` |
| `contains("김")` | SQL 은 `like ? escape '!'`, 바인딩이 `'%김%'`. **SQL 로그만으로는 구분 불가** |
| `contains` 성능 | 앞 `%` LIKE 는 인덱스를 못 탄다. 가능하면 `startsWith` |
| `where(a, b, c)` | 콤마는 **`and`**. `or` 는 varargs 로 표현할 수 없다 |
| `where(null)` | 조용히 무시. `and null` 도 `1=1` 도 아닌 **완전 소거**. 동적 쿼리의 토대 |
| `null.and(...)` | 그냥 NPE. 동적 조건은 체이닝이 아니라 varargs 자리에 |
| 동적 쿼리 패턴 | 조건당 `BooleanExpression` 반환 메서드 하나. 값 없으면 `null` 반환 |
| 조건 재사용 | 메서드끼리 `.and()` 로 합쳐 비즈니스 규칙을 한 곳에 (`isPremium()`) |
| 문자열 조건 | `!= null` 이 아니라 `StringUtils.hasText()`. 빈 문자열이 조건으로 붙는 것을 막는다 |
| `BooleanBuilder` | 같은 SQL 을 만들지만 가독성·재사용·조합성이 떨어짐. 빈 빌더 = 조건 없음(위험) |
| **`or` 체이닝** | **`.and().or()` 에 우선순위 없음. 왼쪽부터 묶임 → 괄호가 사라진다** |
| `or` 함정 결과 | 의도 6건 / 실수① 11건 / 실수② 8건. 셋 다 컴파일·실행 성공 |
| `or` 처방 | ① or 그룹을 메서드로 추출 ② `Expressions.anyOf/allOf` ③ 같은 컬럼이면 `in` |
| `eq(null)` | 동작이 상황에 따라 다를 수 있다. **의미를 기대하지 말 것.** NULL 을 찾으려면 `isNull()` |
| `= NULL` | SQL 에서 항상 UNKNOWN → 0건. `IS NULL` 과 완전히 다르다 |
| `NOT IN` + NULL | NULL 인 행이 통째로 누락된다 (28건 기대 → 25건). `or isNull()` 로 보정 |
| 검색 조건 객체 | 파라미터 4개 이상이면 `record` 로 묶는다. `Boolean` 은 `Boolean.TRUE.equals()` |
| 큰 원칙 | QueryDSL 은 SQL 위의 얇은 층. SQL 의 의미론은 그대로 통과한다 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **모든 문제에서 생성 SQL 과 결과 건수를 함께 확인**하십시오. 이 스텝은 "돌아가는 코드"와 "맞는 코드"의 차이를 배우는 스텝입니다.

1. `products` 에서 이름에 "노트북"이 들어가고 가격이 500,000원 이상인 상품을 조회하고, 생성 SQL 의 `like` 에 바인딩된 값이 무엇인지 로그에서 확인하기
2. `customer.name.startsWith("김")` / `contains("김")` / `endsWith("준")` 세 쿼리를 실행해 **SQL 은 같은데 바인딩만 다르다**는 것을 표로 기록하기
3. 등급·도시·최소 포인트 세 조건을 받는 동적 검색 메서드를 `BooleanExpression` 반환 방식으로 작성하고, 조건 조합 4가지의 생성 SQL 을 로그로 남기기
4. 3번과 동일한 기능을 `BooleanBuilder` 로 작성해 **생성 SQL 이 같은지** 확인하고, 두 방식의 코드 줄 수를 비교하기
5. **"VIP 또는 GOLD 이면서 서울"** 을 (a) 올바른 varargs 방식 (b) `.and().or()` 체이닝 방식 두 가지로 작성하고, 두 결과 건수가 각각 6건과 11건임을 단언하기. 그리고 (b)의 생성 SQL 에 괄호가 없다는 것을 주석에 적기
6. `phone` 이 특정 두 번호가 아닌 고객을 `notIn` 으로 조회해 25건이 나오는 것을 확인하고, `.or(customer.phone.isNull())` 을 붙여 28건으로 고치기

---

## 다음 단계

지금까지 조회 결과는 엔티티 아니면 `Tuple` 이었습니다. `Tuple` 은 편하지만 `row.get(customer.name)` 이라고 매번 적어야 하고, 컨트롤러까지 흘러가면 QueryDSL 의존성이 화면 계층까지 번집니다.

다음 스텝에서는 조회 결과를 **DTO 로 바로 받는** 세 가지 방법(`Projections.bean` / `fields` / `constructor`)과 `@QueryProjection` 을 비교합니다. 그리고 "필드 이름이 다르면 조용히 `null` 이 채워지는" — 이 코스에서 가장 잡기 어려운 종류의 — 함정을 재현합니다.

→ [Step 05 — 프로젝션과 DTO](../step-05-projections/)

---

## 실습 파일

이 스텝은 자바 파일 세 개로 진행합니다. `Practice.java` 로 4-1 ~ 4-11 의 모든 생성 SQL 을 확인하고, `Exercise.java` 의 6문제를 직접 푼 뒤, `Solution.java` 로 정답과 해설을 대조합니다.

Step 03 과 마찬가지로 `@SpringBootTest` + `@Transactional` 테스트 클래스입니다. 이 스텝은 특히 **결과 건수**가 학습의 핵심이므로, 세 파일 모두 단언(assert)에 정확한 숫자를 박아 두었습니다. 숫자가 안 맞으면 데이터가 다른 것이니 `shop` 스키마를 다시 적재하십시오.

### Practice.java

본문(4-1 ~ 4-11)의 모든 예제를 절 번호 주석과 함께 담은 실습 파일입니다.

- `[4-2]` 의 `like_세_형제()` 는 `startsWith` / `contains` / `endsWith` / `like` 를 **연달아 실행**합니다. 네 개의 SQL 로그가 `c1_0.name like ? escape '!'` 로 전부 동일하고 **바인딩 값만 다르다**는 것을 한 화면에서 보는 것이 목적이므로, 하나씩 따로 돌리지 마십시오.
- `[4-4]` 의 `where_null_은_무시된다()` 는 `where(cond, null, cond)` 와 `where(null, null, null)` 을 둘 다 실행합니다. 후자의 SQL 에 **`where` 라는 단어 자체가 없다**는 것을 확인하는 것이 이 절의 전부입니다.
- `[4-5]` 의 `동적쿼리_조합_여섯가지()` 는 하나의 `search(...)` 메서드를 인자만 바꿔 여섯 번 호출합니다. 콘솔에 `=== (3) 도시만 ===` 같은 구분선을 찍어 두었으니, SQL 로그를 그 구분선과 짝지어 읽으십시오.
- `[4-7]` 의 `or_함정_세가지()` 가 이 파일의 핵심입니다. 의도(6건) / 실수①(11건) / 실수②(8건) 을 한 메서드 안에서 연달아 실행하고 각각의 건수를 단언합니다. **실수 쪽 단언이 통과하는 것이 정상**입니다. 버그를 재현하는 테스트이기 때문입니다. 이어지는 `or_처방_세가지()` 가 셋 다 6건으로 수렴하는 것을 보여 줍니다.
- `[4-8]` 의 `eq_null_은_쓰지_마십시오()` 는 **의도적으로 아무것도 단언하지 않습니다.** `eq(null)` 의 동작이 버전에 따라 다를 수 있어 고정된 기대값을 박을 수 없기 때문입니다. 대신 결과를 콘솔에 찍어 두었으니, 여러분의 환경에서 어느 갈래(전체 조회 / 예외 / 0건)로 가는지 직접 확인하고 주석에 적어 두십시오.
- `[4-9]` 의 `notIn_함정()` 은 25건을 단언합니다. 28건이 아닙니다. 이 숫자가 틀리면 `phone` NULL 이 3명이 아닌 것이니 데이터를 확인하십시오.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 요구사항 주석과 `// 여기에 작성:` 자리로 구성돼 있습니다.

- **문제 2** 는 코드보다 **주석 채우기**가 본체입니다. 파일에 표 뼈대가 주석으로 그려져 있고, 여러분이 바인딩 값과 결과 건수를 채워 넣습니다. "SQL 이 같다"는 것을 손으로 적어 봐야 각인됩니다.
- **문제 3·4** 는 같은 기능을 두 방식으로 구현합니다. 문제 4 의 `BooleanBuilder` 버전은 `// if 를 몇 번 썼는지 세어 보십시오: ___` 라는 빈칸으로 끝납니다. 코드 줄 수 비교가 문제의 일부입니다.
- **문제 5 가 이 스텝의 시험입니다.** (a)와 (b)의 결과 건수를 각각 단언하는데, **(b)의 기대값 11 을 미리 알려 주지 않습니다.** 직접 실행해 몇 건인지 확인하고 그 숫자를 넣으십시오. 답을 보고 넣으면 배우는 것이 없습니다.
- **문제 6** 은 `notIn` 의 25건을 먼저 확인한 뒤 28건으로 고치는 2단계 문제입니다. 파일 하단에 `phoneNotIn(String... phones)` 메서드 시그니처가 비어 있는 채로 준비돼 있습니다.
- 파일 상단에 `Grade`, `ProductStatus` enum 과 도시별/등급별 인원 표가 주석으로 붙어 있습니다. 단언에 넣을 숫자를 계산할 때 참고하십시오.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여십시오.

- **정답 2** 는 네 줄짜리 표로 시작합니다. 요점은 "SQL 로그만 보고 디버깅하면 `contains` 와 `startsWith` 를 구분할 수 없다" 는 것이고, 그래서 `org.hibernate.orm.jdbc.bind` 로거를 켜라는 결론으로 이어집니다. 덧붙여 앞 `%` 가 인덱스를 죽인다는 것을 MySQL8 Step 15 와 연결해 설명합니다.
- **정답 3** 은 코드보다 **여섯 개의 생성 SQL 을 주석에 전부 나열**한 것이 본체입니다. 하나의 메서드가 조건 조합에 따라 여섯 가지 SQL 을 만든다는 것을 눈으로 훑을 수 있게 했습니다.
- **정답 4** 의 결론은 "SQL 은 같다" 입니다. 그래서 선택 기준은 성능이 아니라 **가독성·재사용·조합성**이라는 점, 그리고 빈 `BooleanBuilder` 가 조용히 전체 조회가 되는 위험을 주석에서 다시 짚습니다.
- **정답 5 가 가장 깁니다.** 세 코드의 where 절을 나란히 적고, 왜 11건인지를 "부산의 강도윤, 인천의 임하준, 광주의 신지아가 들어왔다" 는 구체적인 이름으로 설명합니다. 그리고 **"자바 연산자 `a && b || c` 는 `&&` 가 먼저지만, 메서드 체인 `a.and(b).or(c)` 는 그냥 왼쪽부터"** 라는 한 줄이 이 함정의 정체라는 것을 강조합니다. 처방 세 가지의 코드와 SQL 도 함께 있습니다.
- **정답 6** 은 3값 논리를 `NOT IN` 전개식으로 풀어 씁니다. `phone NOT IN (a, b)` 가 `phone != a AND phone != b` 이고, NULL 이면 각 항이 UNKNOWN, `UNKNOWN AND UNKNOWN` 은 UNKNOWN, `WHERE` 는 UNKNOWN 을 통과시키지 않는다 — 이 네 단계를 명시합니다. 그리고 "이 버그는 **누락 방향**이라 화면에서 티가 안 난다" 는 경고로 마무리합니다.
- 파일 맨 아래 `// 보너스` 구간에 `isEmpty()` 가 만드는 `not exists` 서브쿼리와, 그것을 Step 07 에서 어떻게 직접 쓰게 되는지를 미리 적어 두었습니다.

```java file="./Solution.java"
```
