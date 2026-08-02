# Step 13 — 고급 표현식

> **학습 목표**
> - `CaseBuilder` 로 단순/복합 `CASE WHEN` 을 만들고 생성 SQL 을 확인한다
> - `case` 를 `orderBy` 에 넣어 커스텀 정렬 순서를 만들고, 그 대가로 인덱스를 잃는 것을 확인한다
> - `coalesce` / `nullif` 로 NULL 과 0 을 SQL 단계에서 처리한다
> - `Expressions` 팩토리 메서드를 용도별로 구분해 쓴다
> - `stringTemplate` 에 사용자 입력을 이어 붙이면 **SQL 인젝션이 그대로 열린다**는 것을 실제 공격 문자열로 재현한다
> - 템플릿 문자열은 컴파일 시점 상수만 쓰고 변하는 값은 `{n}` 바인딩으로 넘기는 규칙을 몸에 익힌다
>
> **선행 스텝**: [Step 12 — Spring Data JPA 통합](../step-12-spring-data/)
> **예상 소요**: 100분

---

지금까지 12개 스텝에서 다룬 것은 대부분 **JPQL 이 문법으로 지원하는 것**들이었습니다.
`where`, `join`, `group by`, `order by`, `limit`. QueryDSL 은 그것을 타입 안전하게 감쌌을 뿐입니다.

이 스텝은 그 경계 밖으로 나갑니다.
`CASE WHEN`, 문자열 함수, DB 고유 함수, 그리고 **직접 쓴 템플릿 문자열**.
표현력이 커지는 만큼 안전장치는 줄어듭니다.

이 스텝의 함정은 지금까지와 성격이 다릅니다.
Step 04 의 괄호 소실이나 Step 05 의 조용한 null 은 **틀린 결과**를 냅니다.
13-11 의 함정은 **공격자가 원하는 결과**를 냅니다.

> 📌 MySQL8 코스 [Step 12 — 내장 함수](../../mysql8/step-12-builtin-functions/) 에서 SQL 로 썼던
> `CASE`, `CONCAT`, `COALESCE`, `DATE_FORMAT` 을 이 스텝에서 QueryDSL 로 다시 씁니다.
> 같은 데이터, 같은 결과, 다른 표기입니다.

이 스텝의 모든 예제는 Q타입 기본 인스턴스를 static import 로 씁니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
```

---

## 13-1. `CaseBuilder` — 단순 case 와 복합 case

### 단순 case — 한 컬럼의 값을 매핑

등급 enum 을 한국어 라벨로 바꿉니다.
대상 경로에서 바로 `.when(...).then(...)` 을 체인하는 형태입니다.

```java
List<Tuple> result = queryFactory
        .select(customer.name,
                customer.grade
                        .when(Grade.VIP).then("최우수")
                        .when(Grade.GOLD).then("우수")
                        .otherwise("일반"))
        .from(customer)
        .orderBy(customer.id.asc())
        .limit(5)
        .fetch();
```

**결과** — `hibernate.SQL` 로그

```sql
select
    c1_0.name,
    case
        when c1_0.grade = ? then ?
        when c1_0.grade = ? then ?
        else ?
    end
from
    customers c1_0
order by
    c1_0.customer_id
limit ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [VIP]
TRACE o.h.orm.jdbc.bind : binding parameter (2:VARCHAR) <- [최우수]
TRACE o.h.orm.jdbc.bind : binding parameter (3:VARCHAR) <- [GOLD]
TRACE o.h.orm.jdbc.bind : binding parameter (4:VARCHAR) <- [우수]
TRACE o.h.orm.jdbc.bind : binding parameter (5:VARCHAR) <- [일반]
TRACE o.h.orm.jdbc.bind : binding parameter (6:INTEGER) <- [5]

김서준   | 최우수
류하나   | 최우수
안지수   | 우수
한지호   | 일반
배채영   | 최우수
```

여기서 확인할 것이 두 가지입니다.

첫째, **`when` 의 비교 대상과 `then` 의 결과값이 모두 `?` 바인딩으로 나갑니다.**
`case when c1_0.grade = 'VIP' then '최우수'` 처럼 리터럴이 SQL 에 박히지 않습니다.
이것이 13-11 에서 깨지는 안전장치의 정체입니다. 기억해 두십시오.

둘째, `case ... end` 가 `select` 절 안에 그대로 들어갑니다.
JPQL 을 거쳐 SQL 로 번역되는 것이지, QueryDSL 이 자바에서 후처리하는 것이 아닙니다.

### 복합 case — 조건식으로 분기

`new CaseBuilder()` 로 시작하면 **아무 조건식이나** `when` 에 넣을 수 있습니다.
단순 case 처럼 한 컬럼의 등치 비교에 묶이지 않습니다.

```java
StringExpression pointTier = new CaseBuilder()
        .when(customer.points.goe(10000)).then("골드")
        .when(customer.points.goe(5000)).then("실버")
        .when(customer.points.goe(1000)).then("브론즈")
        .otherwise("신규");

List<Tuple> result = queryFactory
        .select(customer.name, customer.points, pointTier)
        .from(customer)
        .orderBy(customer.points.desc())
        .limit(6)
        .fetch();
```

**결과**

```sql
select
    c1_0.name,
    c1_0.points,
    case
        when c1_0.points >= ? then ?
        when c1_0.points >= ? then ?
        when c1_0.points >= ? then ?
        else ?
    end
from
    customers c1_0
order by
    c1_0.points desc
limit ?
```

```
류하나   | 14200 | 골드
김서준   | 12800 | 골드
배채영   | 11500 | 골드
정  훈   |  9300 | 실버
오하윤   |  6100 | 실버
문시우   |  4800 | 브론즈
```

`when` 절의 순서가 곧 평가 순서입니다.
`goe(1000)` 을 맨 위로 올리면 14,200 포인트 고객도 "브론즈"가 됩니다.
SQL 의 `CASE` 와 완전히 동일한 의미론이고, **에러는 나지 않습니다.**

> 💡 **실무 팁 — case 는 표현으로 뽑아 재사용하십시오**
> 위처럼 `StringExpression pointTier = ...` 로 변수에 담으면
> `select` 와 `orderBy` 와 `groupBy` 에 같은 표현을 넣을 수 있습니다.
> 인라인으로 세 번 쓰면 셋 중 하나만 고치는 사고가 납니다.

### 타입별 반환 표현

`then` 에 무엇을 넣느냐에 따라 반환 타입이 달라집니다.

| `then` 인자 | 결과 타입 | 쓸 수 있는 연산 |
|---|---|---|
| `String` | `StringExpression` | `concat`, `like`, `lower` |
| `Integer` / `Long` | `NumberExpression<T>` | `sum`, `avg`, `add`, `desc` |
| `BigDecimal` | `NumberExpression<BigDecimal>` | `sum`, `multiply` |
| 경로 (예: `order.totalAmount`) | 그 경로의 타입 | 경로와 동일 |
| `Boolean` | `BooleanExpression` | `and`, `or`, `not` |

`then` 들의 타입이 서로 다르면 컴파일 에러입니다. 이건 QueryDSL 이 잡아 줍니다.

---

## 13-2. `case` 를 `orderBy` 에 — 커스텀 정렬 순서

등급을 VIP → GOLD → SILVER → BRONZE 순으로 정렬하고 싶습니다.
그런데 `grade` 는 `@Enumerated(EnumType.STRING)` 이므로 DB 에는 문자열로 들어 있습니다.
알파벳 순으로 정렬하면 `BRONZE, GOLD, SILVER, VIP` 가 됩니다. 원하는 순서가 아닙니다.

`case` 로 정렬용 숫자를 만들어 그것으로 정렬합니다.

```java
NumberExpression<Integer> gradeRank = new CaseBuilder()
        .when(customer.grade.eq(Grade.VIP)).then(4)
        .when(customer.grade.eq(Grade.GOLD)).then(3)
        .when(customer.grade.eq(Grade.SILVER)).then(2)
        .otherwise(1);

List<Tuple> result = queryFactory
        .select(customer.name, customer.grade, gradeRank)
        .from(customer)
        .orderBy(gradeRank.desc(), customer.name.asc())
        .fetch();
```

**결과**

```sql
select
    c1_0.name,
    c1_0.grade,
    case
        when c1_0.grade = ? then ?
        when c1_0.grade = ? then ?
        when c1_0.grade = ? then ?
        else ?
    end
from
    customers c1_0
order by
    case
        when c1_0.grade = ? then ?
        when c1_0.grade = ? then ?
        when c1_0.grade = ? then ?
        else ?
    end desc,
    c1_0.name
```

```
김서준   | VIP    | 4
류하나   | VIP    | 4
배채영   | VIP    | 4
정  훈   | VIP    | 4
안지수   | GOLD   | 3
...
조회 30건 (VIP 4 / GOLD 9 / SILVER 8 / BRONZE 9)
```

의도한 순서가 나왔습니다. 그런데 생성 SQL 을 다시 보십시오.

**`case` 식이 `select` 에 한 번, `order by` 에 한 번, 두 번 통째로 들어갔습니다.**
QueryDSL 은 같은 자바 객체를 두 자리에 쓴다고 해서 별칭으로 묶어 주지 않습니다.
바인딩 파라미터도 8개가 나갑니다 (4 + 4).

> ⚠️ **함정 — `orderBy` 의 `case` 는 인덱스를 쓸 수 없습니다**
>
> [Step 09 의 9-7 절](../step-09-sorting-paging/) 에서 정렬 컬럼에 `lower()`, `substring()` 을 씌우면
> 인덱스를 못 탄다고 했습니다. `case` 도 정확히 같은 문제입니다.
>
> 인덱스는 **컬럼 값 자체**로 정렬돼 있습니다.
> `case when grade = 'VIP' then 4 ... end` 는 컬럼 값이 아니라 **계산 결과**입니다.
> 옵티마이저는 이 계산 결과의 순서를 인덱스에서 읽어낼 방법이 없으므로,
> 전 행을 읽어 계산하고 `Using filesort` 로 정렬합니다.
>
> ```sql
> EXPLAIN SELECT name, grade FROM customers
> ORDER BY CASE WHEN grade='VIP' THEN 4 WHEN grade='GOLD' THEN 3
>               WHEN grade='SILVER' THEN 2 ELSE 1 END DESC, name;
> ```
> ```
> +------+---------------+------+------+----------------+
> | type | possible_keys | key  | rows | Extra          |
> +------+---------------+------+------+----------------+
> | ALL  | NULL          | NULL |   30 | Using filesort |
> +------+---------------+------+------+----------------+
> ```
>
> `customers` 는 30행이므로 지금은 아무 문제가 없습니다.
> **30만 행이 되면 문제가 됩니다.** 그때는 아래 두 대안 중 하나를 씁니다.
>
> 1. **정렬용 숫자 컬럼을 테이블에 둡니다.** `grade_rank TINYINT` 를 만들고 인덱스를 겁니다.
>    등급 체계가 바뀌는 일은 거의 없으므로 비정규화 비용이 낮습니다.
> 2. **enum 순서를 DB ENUM 순서와 맞추고 그 순서로 정렬합니다.**
>    MySQL 의 `ENUM` 은 내부적으로 정수이므로 `ORDER BY grade` 가 정의 순서대로 정렬됩니다.
>    다만 JPA `@Enumerated(EnumType.STRING)` 로는 이 성질을 쓸 수 없습니다.
>    (`ORDINAL` 로 바꾸는 것은 이 코스에서 금지입니다. 순서가 바뀌면 데이터가 전부 어긋납니다.)

> 💡 소량 데이터에서 정렬 순서를 맞추는 것이 목적이라면 `case` 정렬로 충분합니다.
> 문제가 되는 건 **행 수**이지 `case` 그 자체가 아닙니다. 재는 습관이 판단을 대신합니다.

---

## 13-3. 조건부 집계 — case 로 만드는 피벗

[Step 08 의 8-11 절](../step-08-aggregation/) 에서 "상태별 매출을 한 행에 펼치고 싶다"고 했다가
`groupBy` 로는 세로로만 나온다는 데서 멈췄습니다. 그 완성판이 이것입니다.

`sum()` 안에 `case` 를 넣으면 **조건에 맞는 행만 더합니다.**

```java
Tuple result = queryFactory
        .select(
                new CaseBuilder()
                        .when(order.status.eq(OrderStatus.PAID)).then(order.totalAmount)
                        .otherwise(BigDecimal.ZERO).sum(),
                new CaseBuilder()
                        .when(order.status.eq(OrderStatus.DELIVERED)).then(order.totalAmount)
                        .otherwise(BigDecimal.ZERO).sum(),
                new CaseBuilder()
                        .when(order.status.eq(OrderStatus.CANCELLED)).then(order.totalAmount)
                        .otherwise(BigDecimal.ZERO).sum(),
                order.count())
        .from(order)
        .fetchOne();
```

**결과**

```sql
select
    sum(case when o1_0.status = ? then o1_0.total_amount else ? end),
    sum(case when o1_0.status = ? then o1_0.total_amount else ? end),
    sum(case when o1_0.status = ? then o1_0.total_amount else ? end),
    count(o1_0.order_id)
from
    orders o1_0
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [PAID]
TRACE o.h.orm.jdbc.bind : binding parameter (2:DECIMAL) <- [0]
TRACE o.h.orm.jdbc.bind : binding parameter (3:VARCHAR) <- [DELIVERED]
...

PAID: 41,382,000  DELIVERED: 78,914,500  CANCELLED: 9,220,000  총 600건
```

**테이블을 한 번만 읽고** 세 개의 합계를 만듭니다.
상태별로 쿼리를 세 번 날리는 것과 결과는 같지만 I/O 는 1/3 입니다.

도시별로 쪼개면 진짜 피벗이 됩니다.

```java
List<Tuple> byCity = queryFactory
        .select(order.shippingCity,
                new CaseBuilder().when(order.status.eq(OrderStatus.PAID))
                        .then(order.totalAmount).otherwise(BigDecimal.ZERO).sum(),
                new CaseBuilder().when(order.status.eq(OrderStatus.CANCELLED))
                        .then(1).otherwise(0).sum())
        .from(order)
        .groupBy(order.shippingCity)
        .orderBy(order.shippingCity.asc())
        .fetch();
```

**결과**

```sql
select
    o1_0.shipping_city,
    sum(case when o1_0.status = ? then o1_0.total_amount else ? end),
    sum(case when o1_0.status = ? then ? else ? end)
from
    orders o1_0
group by
    o1_0.shipping_city
order by
    o1_0.shipping_city
```

```
광주 | 5,102,000  | 8
대구 | 6,730,500  | 11
대전 | 4,988,000  | 7
부산 | 8,455,000  | 14
서울 | 12,904,500 | 21
인천 | 3,202,000  | 5
```

`then(1).otherwise(0).sum()` 은 **조건부 카운트**입니다.
`count(case when ... then 1 end)` 로도 되지만 `sum` 쪽이 `otherwise` 를 명시해 읽기 쉽습니다.

> 💡 **실무 팁 — 조건부 카운트에서 `otherwise(0)` 과 `otherwise(null)` 의 차이**
> `count()` 는 NULL 을 세지 않으므로 `count(case when ... then 1 else null end)` 도 같은 결과입니다.
> 하지만 `sum(case ... else 0 end)` 은 **행이 하나도 없을 때 NULL** 을 반환하고,
> `count(...)` 는 **0** 을 반환합니다. 이 차이가 13-7 의 `coalesce` 로 이어집니다.

---

## 13-4. 상수 — `Expressions.constant`

결과에 고정값을 끼워 넣고 싶을 때가 있습니다. 구분자, 버전 태그, 소스 표시 같은 것들입니다.

```java
List<Tuple> result = queryFactory
        .select(customer.name, Expressions.constant("A"))
        .from(customer)
        .limit(3)
        .fetch();
```

**결과**

```sql
select
    c1_0.name
from
    customers c1_0
limit ?
```

```
김서준 | A
류하나 | A
안지수 | A
```

**SQL 에 `'A'` 가 없습니다.**

값은 정상적으로 붙어 나오는데 SQL 에는 흔적이 없습니다.
QueryDSL 이 "이건 행마다 달라지지 않는 값이니 DB 에 보낼 이유가 없다"고 판단해
**JPQL 에서 빼고 결과 조립 단계에서 붙입니다.**

이 최적화는 상황에 따라 적용될 수도 있고 아닐 수도 있습니다.
`Expressions.constant` 를 `where` 조건이나 다른 표현식의 피연산자로 쓰면
DB 로 내려가야 하므로 SQL 에 파라미터로 나타납니다.

```java
// concat 의 인자로 쓰이면 SQL 에 나갑니다
queryFactory
        .select(customer.name.concat(Expressions.constant("-님")))
        .from(customer)
        .limit(3)
        .fetch();
```

**결과**

```sql
select
    concat(c1_0.name, ?)
from
    customers c1_0
limit ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [-님]

김서준-님
류하나-님
안지수-님
```

> ⚠️ **함정 — "SQL 에 안 보이니 안 나간 것"이 아닙니다**
> 반대 방향의 오해도 흔합니다.
> `Expressions.constant` 가 SQL 에서 사라지는 것을 보고
> "QueryDSL 은 상수를 DB 로 안 보내는구나" 라고 일반화하면 안 됩니다.
> **어디에 쓰였느냐에 따라 달라집니다.** 그리고 이 동작은 최적화이므로 버전에 따라 달라질 수 있습니다.
>
> 값이 반드시 DB 단계에서 평가돼야 한다면 (예: DB 함수의 인자)
> 상수인지 아닌지 추측하지 말고 **생성 SQL 을 직접 확인**하십시오.
> 이 코스가 매 절마다 SQL 을 붙이는 이유입니다.

---

## 13-5. 문자열 연산 — `concat` 과 `stringValue()`

### `concat`

```java
List<String> result = queryFactory
        .select(customer.city.concat("/").concat(customer.name))
        .from(customer)
        .limit(4)
        .fetch();
```

**결과**

```sql
select
    concat(concat(c1_0.city, ?), c1_0.name)
from
    customers c1_0
limit ?
```

```
서울/김서준
부산/류하나
서울/안지수
대구/한지호
```

`concat` 을 두 번 체인하면 **중첩된 `concat` 두 개**가 됩니다.
MySQL 의 `CONCAT` 은 가변 인자를 받지만 JPQL 의 `CONCAT` 은 2항이므로 이렇게 번역됩니다.
결과는 같습니다.

> ⚠️ MySQL 의 `CONCAT` 은 인자 중 하나라도 NULL 이면 **전체가 NULL** 입니다.
> `customer.phone.concat("!")` 는 전화번호가 NULL 인 3명에 대해 NULL 을 반환합니다.
> 빈 문자열이 아닙니다. 13-7 의 `coalesce` 와 조합하십시오.

### `stringValue()` — enum 과 숫자를 문자열로

이름과 등급을 이어 붙이려 하면 컴파일 에러가 납니다.

```java
// 컴파일 에러
customer.name.concat("_").concat(customer.grade);
//                              ^^^^^^^^^^^^^^^
// StringExpression.concat(Expression<String>) 인데 EnumPath<Grade> 를 넘김
```

`concat` 은 `Expression<String>` 만 받습니다. `EnumPath<Grade>` 는 `String` 이 아닙니다.
**타입 시스템이 정확하게 막은 것입니다.** 우회하지 말고 명시적으로 변환합니다.

```java
List<String> result = queryFactory
        .select(customer.name.concat("_").concat(customer.grade.stringValue()))
        .from(customer)
        .limit(4)
        .fetch();
```

**결과**

```sql
select
    concat(concat(c1_0.name, ?), cast(c1_0.grade as char))
from
    customers c1_0
limit ?
```

```
김서준_VIP
류하나_VIP
안지수_GOLD
한지호_BRONZE
```

`stringValue()` 는 SQL 의 `cast(... as char)` 로 번역됩니다.
숫자에도 똑같이 씁니다.

```java
queryFactory
        .select(customer.name.concat("(").concat(customer.points.stringValue()).concat("P)"))
        .from(customer)
        .limit(3)
        .fetch();
```

**결과**

```sql
select
    concat(concat(concat(c1_0.name, ?), cast(c1_0.points as char)), ?)
from
    customers c1_0
limit ?
```

```
김서준(12800P)
류하나(14200P)
안지수(3400P)
```

> 💡 **실무 팁 — 문자열 조립은 애플리케이션에서 하는 편이 낫습니다**
> `cast` 와 `concat` 이 중첩될수록 SQL 은 읽기 어려워지고, 그 결과 컬럼에는 인덱스도 못 탑니다.
> DTO 로 `name` 과 `points` 를 따로 받아 자바에서 조립하는 편이
> SQL 도 단순하고 포맷 변경도 쉽습니다.
> DB 에서 조립해야 하는 경우는 **그 결과로 정렬하거나 필터링할 때**뿐입니다.

### 그 밖의 문자열 메서드

| QueryDSL | 생성 SQL | 비고 |
|---|---|---|
| `.lower()` / `.upper()` | `lower(...)` / `upper(...)` | 인덱스 무력화 |
| `.trim()` | `trim(...)` | |
| `.length()` | `length(...)` → `NumberExpression<Integer>` | |
| `.substring(1, 3)` | `substring(..., 2, 2)` | **0-based → 1-based 변환됨** |
| `.startsWith("서")` | `like ? escape '!'` (`'서%'`) | 인덱스 사용 가능 |
| `.contains("노트")` | `like ? escape '!'` (`'%노트%'`) | **인덱스 사용 불가** |
| `.like("%북%")` | `like ?` | 이스케이프 없음 |
| `.indexOf("_")` | `locate(?, ...) - 1` | |

`substring` 의 인덱스 기준이 자바(0-based)이고 SQL(1-based)로 자동 변환된다는 점을 기억하십시오.
`substring(0, 2)` 는 SQL 에서 `substring(x, 1, 2)` 입니다.

---

## 13-6. 숫자 연산 — `add` / `subtract` / `multiply` / `divide`

```java
List<Tuple> result = queryFactory
        .select(product.name,
                product.price,
                product.cost,
                product.price.subtract(product.cost),
                product.price.subtract(product.cost)
                        .multiply(100)
                        .divide(product.price))
        .from(product)
        .where(product.status.eq(ProductStatus.ON_SALE))
        .orderBy(product.price.desc())
        .limit(4)
        .fetch();
```

**결과**

```sql
select
    p1_0.name,
    p1_0.price,
    p1_0.cost,
    p1_0.price - p1_0.cost,
    (p1_0.price - p1_0.cost) * ? / p1_0.price
from
    products p1_0
where
    p1_0.status = ?
order by
    p1_0.price desc
limit ?
```

```
게이밍 노트북 RTX4060 | 2190000.00 | 1533000.00 | 657000.00 | 30.000000
보급형 노트북 15      |  690000.00 |  483000.00 | 207000.00 | 30.000000
27인치 4K 모니터      |  459000.00 |  321300.00 | 137700.00 | 30.000000
원목 4인 식탁         |  459000.00 |  344250.00 | 114750.00 | 25.000000
```

연산자 우선순위가 SQL 에 그대로 반영됩니다.
`.subtract(...).multiply(100).divide(...)` 는 **체인 순서대로** 왼쪽부터 묶입니다.
괄호가 필요한 곳에는 QueryDSL 이 알아서 넣습니다 — Step 04 의 `or` 와 달리 산술 연산은 안전합니다.

> ⚠️ **함정 — `BigDecimal` 의 `divide` 와 스케일**
>
> 위 결과의 `30.000000` 을 보십시오. 소수점 여섯 자리입니다.
> 이 스케일은 **MySQL 이 정한 것**입니다. MySQL 은 `DECIMAL` 나눗셈의 결과 스케일을
> `div_precision_increment` 시스템 변수(기본 4)에 따라 결정합니다.
> QueryDSL 도 JPA 도 여기에 관여하지 않습니다.
>
> 문제는 이것을 **자바에서 `BigDecimal.divide` 한 결과와 비교할 때** 드러납니다.
>
> ```java
> // DB 에서 계산: 30.000000
> // 자바에서 계산:
> BigDecimal margin = price.subtract(cost)
>         .multiply(BigDecimal.valueOf(100))
>         .divide(price);            // ArithmeticException: Non-terminating decimal expansion
> ```
>
> 자바의 `BigDecimal.divide` 는 **스케일을 지정하지 않으면 나누어떨어지지 않을 때 예외**를 던집니다.
> DB 는 조용히 반올림하고, 자바는 예외를 던집니다. 같은 계산이 아닙니다.
>
> **처방**
> - 자바에서 나눌 때는 항상 `divide(divisor, 2, RoundingMode.HALF_UP)` 처럼 스케일과 반올림을 명시합니다.
> - DB 에서 계산한 비율을 자바 값과 `equals` 로 비교하지 마십시오.
>   `BigDecimal.equals` 는 **스케일까지 비교**합니다. `30.000000` 과 `30.00` 은 `equals` 가 `false` 입니다.
>   비교는 `compareTo(...) == 0` 으로 하십시오.
> - 금액 정산처럼 정확성이 요구되는 계산은 **한쪽에서만** 하십시오. DB 와 자바에 나눠 두면
>   반올림 지점이 두 곳이 되어 1원씩 어긋나기 시작합니다.

`divide` 로 0 을 나누면 MySQL 은 기본 설정에서 **NULL** 을 반환합니다 (`ERROR_FOR_DIVISION_BY_ZERO`
가 `sql_mode` 에 있으면 경고 또는 에러). 이 처리를 명시적으로 하는 것이 13-8 의 `nullif` 입니다.

---

## 13-7. `coalesce` — NULL 을 기본값으로

`customers` 에는 `phone` 이 NULL 인 고객이 **3명** 있습니다.
[Step 04](../step-04-where-conditions/) 에서 `isNull()` 로 찾아냈던 그 3명입니다.

```java
List<Tuple> result = queryFactory
        .select(customer.name, customer.phone, customer.phone.coalesce("번호없음"))
        .from(customer)
        .where(customer.phone.isNull())
        .fetch();
```

**결과**

```sql
select
    c1_0.name,
    c1_0.phone,
    coalesce(c1_0.phone, ?)
from
    customers c1_0
where
    c1_0.phone is null
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [번호없음]

정  훈   | null | 번호없음
오하윤   | null | 번호없음
문시우   | null | 번호없음
조회 3건
```

`coalesce(...)` 뒤에 `.asString()` 을 붙이면 `StringExpression` 으로 되돌아와
`concat` 같은 문자열 연산을 이어 갈 수 있습니다. 붙이지 않으면 `CoalesceBuilder` 계열 타입입니다.

```java
queryFactory
        .select(customer.name.concat(" / ")
                .concat(customer.phone.coalesce("번호없음").asString()))
        .from(customer)
        .where(customer.phone.isNull())
        .fetch();
```

**결과**

```sql
select
    concat(concat(c1_0.name, ?), coalesce(c1_0.phone, ?))
from
    customers c1_0
where
    c1_0.phone is null
```

```
정  훈 / 번호없음
오하윤 / 번호없음
문시우 / 번호없음
```

### `sum()` 의 NULL — Step 08 의 미완성 처방

[Step 08 의 8-7 절](../step-08-aggregation/) 에서 이 문제를 예고했습니다.
**조건에 맞는 행이 하나도 없으면 `sum()` 은 0 이 아니라 NULL 을 반환합니다.**

```java
BigDecimal total = queryFactory
        .select(order.totalAmount.sum())
        .from(order)
        .where(order.shippingCity.eq("제주"))     // 제주 주문은 0건
        .fetchOne();

System.out.println(total);
```

**결과**

```sql
select
    sum(o1_0.total_amount)
from
    orders o1_0
where
    o1_0.shipping_city = ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [제주]

null
```

`fetchOne()` 은 **행 하나를 반환**했고, 그 행의 값이 NULL 입니다.
"결과가 없다"가 아닙니다. `total.compareTo(BigDecimal.ZERO)` 를 부르면 `NullPointerException` 입니다.

SQL 단계에서 막습니다.

```java
BigDecimal total = queryFactory
        .select(order.totalAmount.sum().coalesce(BigDecimal.ZERO))
        .from(order)
        .where(order.shippingCity.eq("제주"))
        .fetchOne();
```

**결과**

```sql
select
    coalesce(sum(o1_0.total_amount), ?)
from
    orders o1_0
where
    o1_0.shipping_city = ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:DECIMAL) <- [0]
TRACE o.h.orm.jdbc.bind : binding parameter (2:VARCHAR) <- [제주]

0
```

`coalesce` 가 **`sum` 을 감싼다**는 데 주의하십시오.
`order.totalAmount.coalesce(BigDecimal.ZERO).sum()` 은 의미가 완전히 다릅니다.
그건 "각 행의 금액이 NULL 이면 0 으로 보고 더하라"이고,
`total_amount` 는 `NOT NULL` 컬럼이므로 아무 효과도 없습니다.

| 코드 | SQL | 의미 |
|---|---|---|
| `.sum().coalesce(ZERO)` | `coalesce(sum(x), 0)` | **합계가 NULL 이면 0** ← 원하는 것 |
| `.coalesce(ZERO).sum()` | `sum(coalesce(x, 0))` | 각 행의 NULL 을 0 으로 보고 합산 |

> 💡 **실무 팁 — 집계 결과를 반환하는 리포지토리는 `coalesce` 를 기본값으로**
> "합계 조회" 메서드가 NULL 을 반환할 수 있다는 사실은 호출부에서 잊히기 쉽습니다.
> 리포지토리 안에서 `coalesce` 로 닫아 두면 호출부가 null 검사를 하지 않아도 됩니다.
> `avg()`, `max()`, `min()` 도 모두 같습니다.

`coalesce` 는 인자를 여러 개 받을 수 있습니다.

```java
Expressions.coalesce(customer.phone, customer.email, Expressions.constant("연락처없음"))
```

```sql
coalesce(c1_0.phone, c1_0.email, ?)
```

---

## 13-8. `nullif` — 0 을 NULL 로 바꿔 나눗셈을 막기

`nullif(a, b)` 는 `a` 와 `b` 가 같으면 NULL, 다르면 `a` 를 반환합니다.
가장 흔한 용도는 **0 나누기 방지**입니다.

재고 대비 후기 수 비율을 계산한다고 합시다. 재고가 0인 상품(`SOLD_OUT`)이 있습니다.

```java
// 위험한 코드
queryFactory
        .select(product.name,
                product.stock,
                product.price.divide(product.stock))
        .from(product)
        .fetch();
```

MySQL 기본 `sql_mode` (`ERROR_FOR_DIVISION_BY_ZERO` 포함)에서는 이렇게 됩니다.

**결과**

```sql
select
    p1_0.name,
    p1_0.stock,
    p1_0.price / p1_0.stock
from
    products p1_0
```

```
게이밍 노트북 RTX4060 | 12 | 182500.000000
콜드브루 원액 1L      |  0 | null              ← 0 나누기. 경고 + NULL
인체공학 사무용 의자  | 25 |  13160.000000
```

에러가 아니라 **NULL** 이 나왔습니다. 조용합니다.
계산 결과를 자바에서 `BigDecimal` 로 받아 쓰면 그 자리에서 NPE 가 납니다.

의도를 명시적으로 표현합니다.

```java
NumberExpression<BigDecimal> pricePerStock = product.price
        .divide(Expressions.nullif(product.stock, 0))
        .coalesce(BigDecimal.ZERO);

List<Tuple> result = queryFactory
        .select(product.name, product.stock, pricePerStock)
        .from(product)
        .orderBy(product.id.asc())
        .limit(4)
        .fetch();
```

**결과**

```sql
select
    p1_0.name,
    p1_0.stock,
    coalesce(p1_0.price / nullif(p1_0.stock, ?), ?)
from
    products p1_0
order by
    p1_0.product_id
limit ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:INTEGER) <- [0]
TRACE o.h.orm.jdbc.bind : binding parameter (2:DECIMAL) <- [0]

게이밍 노트북 RTX4060 | 12 | 182500.000000
콜드브루 원액 1L      |  0 |      0.000000     ← NULL 대신 0
인체공학 사무용 의자  | 25 |  13160.000000
27인치 4K 모니터      | 18 |  25500.000000
```

`nullif` + `coalesce` 조합은 **"0으로 나누면 0으로 친다"** 는 비즈니스 규칙을 SQL 에 명시한 것입니다.
NULL 이 우연히 흘러가는 것과, 규칙에 따라 0 이 되는 것은 다릅니다.
코드를 읽는 사람이 그 차이를 볼 수 있어야 합니다.

경로에서 직접 부르는 형태도 있습니다.

```java
product.stock.nullif(0)          // nullif(p1_0.stock, ?)
customer.city.nullif("미상")      // nullif(c1_0.city, ?)
```

---

## 13-9. `Expressions` 팩토리 총정리

`Expressions` 는 **Q타입 경로로 표현할 수 없는 것**을 만들 때 쓰는 정적 팩토리입니다.
Q타입에 없는 것을 억지로 만들어 내는 도구이므로, 쓸수록 타입 안전성은 떨어집니다.

| 메서드 | 반환 | 용도 | 안전도 |
|---|---|---|---|
| `Expressions.constant(v)` | `Expression<T>` | 고정값 (13-4) | 안전 |
| `Expressions.asNumber(10)` | `NumberExpression<Integer>` | 숫자 리터럴을 표현식으로 승격 | 안전 |
| `Expressions.asString("x")` | `StringExpression` | 문자열 리터럴 승격 | 안전 |
| `Expressions.asBoolean(true)` | `BooleanExpression` | 항상 참/거짓 조건 | 안전 |
| `Expressions.nullif(a, b)` | 인자 타입 | NULL 치환 (13-8) | 안전 |
| `Expressions.allOf(a, b, c)` | `BooleanExpression` | AND 결합, **null 인자 무시** | 안전 |
| `Expressions.anyOf(a, b, c)` | `BooleanExpression` | OR 결합, **null 인자 무시** | 안전 |
| `Expressions.numberPath(...)` | `NumberPath<T>` | 별칭 등 동적 경로 | 주의 |
| `Expressions.stringTemplate(...)` | `StringExpression` | 임의 SQL/JPQL 조각 | **위험 (13-11)** |
| `Expressions.numberTemplate(...)` | `NumberExpression<T>` | 위와 동일, 숫자 반환 | **위험** |
| `Expressions.booleanTemplate(...)` | `BooleanExpression` | 위와 동일, 조건 반환 | **위험** |
| `Expressions.dateTemplate(...)` | `DateExpression<T>` | 위와 동일, 날짜 반환 | **위험** |

### `allOf` / `anyOf` — null 을 무시하는 결합

[Step 04](../step-04-where-conditions/) 의 동적 조건 조립에서 유용합니다.

```java
BooleanExpression cond = Expressions.allOf(
        cityEq(req.city()),          // null 일 수 있음
        gradeEq(req.grade()),        // null 일 수 있음
        pointsGoe(req.minPoints()));  // null 일 수 있음

List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(cond)
        .fetch();
```

세 조건이 모두 null 이면 `cond` 자체가 null 이 되고, `where(null)` 은 조건 없음이 됩니다.
`where(a, b, c)` 의 가변 인자 형태와 동일한 동작이지만,
**조건 묶음을 변수에 담아 재사용하거나 `or` 로 다시 묶어야 할 때** 는 `allOf` 가 필요합니다.

```java
// (도시 조건 AND 등급 조건) OR (VIP)
BooleanExpression cond = Expressions.anyOf(
        Expressions.allOf(cityEq(city), gradeEq(grade)),
        customer.grade.eq(Grade.VIP));
```

**결과**

```sql
where
    (c1_0.city = ? and c1_0.grade = ?)
    or c1_0.grade = ?
```

Step 04 에서 `.and().or()` 체인이 괄호를 잃었던 문제가 여기서는 발생하지 않습니다.
**결합 구조를 함수 호출의 중첩으로 표현했기 때문**입니다.
괄호를 잃을 수 없는 형태로 쓰는 것이 괄호를 잘 넣는 것보다 안전합니다.

### `asNumber` — 숫자 리터럴이 왼쪽에 와야 할 때

```java
// 100 - stock 을 표현하고 싶은데, 100 에는 subtract 가 없습니다
Expressions.asNumber(100).subtract(product.stock)
```

```sql
? - p1_0.stock
```

---

## 13-10. DB 함수 호출

JPQL 이 표준으로 지원하지 않는 DB 고유 함수를 부르는 방법입니다.

### `function(...)` 문법

JPA 2.1 표준입니다. `function('함수명', 인자...)` 형태로 씁니다.

```java
StringExpression yearMonth = Expressions.stringTemplate(
        "function('date_format', {0}, {1})", order.orderDate, "%Y-%m");

List<Tuple> result = queryFactory
        .select(yearMonth, order.count(), order.totalAmount.sum())
        .from(order)
        .groupBy(yearMonth)
        .orderBy(yearMonth.asc())
        .limit(4)
        .fetch();
```

**결과**

```sql
select
    date_format(o1_0.order_date, ?),
    count(o1_0.order_id),
    sum(o1_0.total_amount)
from
    orders o1_0
group by
    date_format(o1_0.order_date, ?)
order by
    date_format(o1_0.order_date, ?)
limit ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [%Y-%m]
TRACE o.h.orm.jdbc.bind : binding parameter (2:VARCHAR) <- [%Y-%m]
TRACE o.h.orm.jdbc.bind : binding parameter (3:VARCHAR) <- [%Y-%m]

2024-01 | 22 | 4,102,000
2024-02 | 19 | 3,554,500
2024-03 | 25 | 5,880,000
2024-04 | 21 | 4,331,000
```

`{0}`, `{1}` 자리에 넘긴 인자가 **`?` 바인딩 파라미터**로 나갔습니다. 이것이 안전한 형태입니다.
13-11 에서 이 문장으로 다시 돌아옵니다.

### Hibernate 6 에서는 `function(...)` 없이 되는 경우가 많습니다

Hibernate 6 는 HQL 이 인식하는 함수 목록을 크게 늘렸습니다.
`Dialect` 에 등록된 함수라면 `function('...')` 래퍼 없이 **이름으로 직접** 쓸 수 있는 경우가 많습니다.

```java
Expressions.stringTemplate("date_format({0}, {1})", order.orderDate, "%Y-%m")
```

다만 **어떤 함수가 등록돼 있는지는 전적으로 `Dialect` 에 달려 있습니다.**
`MySQLDialect` 가 등록한 함수와 `PostgreSQLDialect` 가 등록한 함수가 다르고,
같은 MySQL 이어도 Hibernate 버전에 따라 목록이 달라집니다.

따라서 이 코스는 다음 원칙을 씁니다.

- **`function('name', ...)` 형태를 기본으로 씁니다.** 등록 여부와 무관하게 통과하는 표준 문법입니다.
- 이름 직접 호출은 **그 프로젝트의 Dialect 와 Hibernate 버전에서 실제로 동작하는지 확인한 뒤** 씁니다.
  안 되면 `SemanticException: Could not interpret path expression` 같은 형태로 **기동 시점이 아니라
  쿼리 실행 시점에** 터집니다.

### 커스텀 함수 등록 — Hibernate 5 와 6 이 다릅니다

Dialect 에 없는 함수(예: 회사 DB 에 만든 UDF)를 쓰려면 등록이 필요합니다.
**등록 방법이 Hibernate 5 와 6 에서 바뀌었습니다.**

| | Hibernate 5 | Hibernate 6 |
|---|---|---|
| 방식 A | `Dialect` 상속 후 생성자에서 `registerFunction(...)` | `Dialect` 상속 후 함수 기여 지점 오버라이드 |
| 방식 B | `MetadataBuilder.applySqlFunction(...)` | **`FunctionContributor`** 구현 + `META-INF/services` 등록 |

Hibernate 5 에서 널리 쓰이던 `metadataBuilder.applySqlFunction("group_concat", ...)` 패턴을
Hibernate 6 에 그대로 옮기면 동작하지 않습니다.
Hibernate 6 은 `FunctionContributor` 라는 SPI 를 통해 함수를 기여받는 구조로 바뀌었습니다.

> ⚠️ **정확한 시그니처는 Hibernate 6 문서를 확인하십시오**
> `FunctionContributor` 의 메서드 시그니처, `FunctionContributions` 에서 함수를 등록하는 정확한 호출 형태,
> 그리고 `META-INF/services` 에 등록할 인터페이스 전체 이름은 Hibernate 6 의 마이너 버전에 따라
> 세부가 달라질 수 있습니다.
> 이 코스는 그 세부를 단정하지 않습니다.
> **"Hibernate 5 의 `applySqlFunction` 방식은 더 이상 쓰이지 않고, Hibernate 6 은 `FunctionContributor`
> 로 간다"** 는 방향만 기억하고, 구현 시점에 사용 중인 Hibernate 버전의 공식 문서에서
> `FunctionContributor` 항목을 확인하십시오.
> 잘못된 시그니처를 복사해 넣으면 컴파일은 되는데 함수가 등록되지 않아
> **쿼리 실행 시점에** 알 수 없는 함수 에러가 납니다.

> 💡 **실무 팁 — 커스텀 함수를 등록하기 전에 한 번 더 생각하십시오**
> DB 고유 함수를 쿼리에 넣는 순간 그 쿼리는 그 DB 에 묶입니다.
> 테스트를 H2 로 돌리고 있었다면 그 테스트부터 깨집니다.
> `date_format` 이 필요한 이유가 "월별 집계"라면, `orderDate.year()` 와 `orderDate.month()` 로
> 그룹핑하는 표준 방법이 있습니다 (13-13). 표준으로 되는 것은 표준으로 하십시오.

---

## 13-11. ⚠️ `stringTemplate` 으로 SQL 인젝션이 열립니다

**이 스텝에서 가장 중요한 절입니다.**

[Step 10](../step-10-dynamic-sort/) 에서 이렇게 썼습니다.

> QueryDSL 은 사용자 입력을 JPQL 의 바인딩 파라미터로 넘기므로, 문자열 조립 방식의
> SQL 인젝션은 구조적으로 발생하기 어렵습니다.

**이 문장은 `Expressions.*Template` 앞에서 무너집니다.**
템플릿 문자열은 QueryDSL 이 해석하지 않고 **그대로 JPQL 에 삽입**하기 때문입니다.

### 안전한 형태

```java
String pattern = "%Y-%m";      // 사용자가 정할 수 있는 값이라고 가정

StringExpression expr = Expressions.stringTemplate(
        "function('date_format', {0}, {1})",
        order.orderDate,
        pattern);                       // ← 값을 인자로 넘김

queryFactory.select(expr).from(order).limit(3).fetch();
```

**결과**

```sql
select
    date_format(o1_0.order_date, ?)
from
    orders o1_0
limit ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [%Y-%m]
```

**`?` 가 보입니다.** `pattern` 이 무엇이든 그것은 값으로만 취급됩니다.
`pattern` 에 `'); DROP TABLE orders; --` 를 넣어도 그냥 그 문자열로 포맷을 시도하고 끝납니다.

### 위험한 형태

같은 결과를 내는 것처럼 보이는 코드입니다.

```java
String pattern = req.getPattern();     // ← 사용자 입력

StringExpression expr = Expressions.stringTemplate(
        "function('date_format', {0}, '" + pattern + "')",   // ★ 템플릿에 이어 붙임
        order.orderDate);

queryFactory.select(expr).from(order).limit(3).fetch();
```

`pattern = "%Y-%m"` 이면 정상 동작합니다. 테스트도 통과합니다. 코드 리뷰도 통과합니다.

```sql
select
    date_format(o1_0.order_date, '%Y-%m')
from
    orders o1_0
limit ?
```

**`?` 가 없습니다.** 값이 SQL 에 그대로 박혔습니다.
여기까지는 "리터럴이 박혔네" 정도로 보이고 결과도 맞습니다.

### 실제 공격

공격자가 이 값을 보냅니다.

```
pattern = %Y-%m') , (select email from customers where grade='VIP' limit 1
```

이 문자열이 템플릿에 이어 붙으면 템플릿은 이렇게 됩니다.

```
function('date_format', {0}, '%Y-%m') , (select email from customers where grade='VIP' limit 1')
```

QueryDSL 은 이 문자열을 검사하지 않습니다. `{0}` 만 치환하고 JPQL 로 넘깁니다.
Hibernate 는 이것을 문법적으로 유효한 JPQL 로 파싱하고, 다음 SQL 을 만듭니다.

**결과**

```sql
select
    date_format(o1_0.order_date, '%Y-%m'),
    (select c1_0.email from customers c1_0 where c1_0.grade = 'VIP' limit 1)
from
    orders o1_0
limit ?
```

```
2024-01 | seojun.kim@example.com
2024-01 | seojun.kim@example.com
2024-02 | seojun.kim@example.com
```

**월별 집계 API 가 고객 이메일을 반환했습니다.**
`select` 절이 하나 늘었으므로 `Tuple` 의 크기가 달라져 애플리케이션이 예외로 죽을 수도 있지만,
DTO 를 쓰지 않고 `Tuple` 을 그대로 JSON 으로 직렬화하는 코드였다면 **그대로 응답에 실려 나갑니다.**

더 나쁜 형태도 가능합니다.

```
pattern = %Y-%m') , (select count(*) from customers where email like 'a%
```

응답의 숫자를 보고 **블라인드로 데이터를 한 글자씩 추출**할 수 있습니다.
`a%`, `b%`, ... 를 반복하면 됩니다. 몇 분이면 전체 고객 이메일이 빠져나갑니다.

> ⚠️ **함정 — "QueryDSL 을 쓰니까 인젝션은 없다"**
>
> 이 믿음이 이 함정을 위험하게 만듭니다.
> 보안 리뷰에서 "ORM 을 쓰고 있으니 인젝션 항목은 통과"로 처리되는 경우가 많습니다.
> 정적 분석 도구도 `PreparedStatement` 와 문자열 연결을 찾도록 튜닝돼 있어
> `Expressions.stringTemplate("..." + x + "...")` 를 놓치기 쉽습니다.
>
> **`Expressions.stringTemplate`, `numberTemplate`, `booleanTemplate`, `dateTemplate` 의 첫 인자는
> QueryDSL 이 검사하지 않고 그대로 JPQL 에 넣는 원시 문자열입니다.**
> `PreparedStatement` 에 `"... where id = " + id` 를 쓰는 것과 위험도가 같습니다.

### 처방

**규칙 하나면 됩니다.**

> **템플릿 문자열은 컴파일 시점 상수여야 합니다.**
> 변하는 값은 예외 없이 `{n}` 자리의 인자로 넘깁니다.

```java
// ✅ 상수 리터럴
Expressions.stringTemplate("function('date_format', {0}, {1})", order.orderDate, pattern)

// ✅ static final 상수
private static final String DATE_FMT = "function('date_format', {0}, {1})";
Expressions.stringTemplate(DATE_FMT, order.orderDate, pattern)

// ❌ 연결 연산자
Expressions.stringTemplate("function('date_format', {0}, '" + pattern + "')", order.orderDate)

// ❌ String.format
Expressions.stringTemplate(String.format("function('date_format', {0}, '%s')", pattern), order.orderDate)

// ❌ 텍스트 블록 + 보간 흉내
Expressions.stringTemplate("""
        function('date_format', {0}, '%s')
        """.formatted(pattern), order.orderDate)
```

아래 세 개는 전부 같은 사고입니다. 형태만 다릅니다.

### 리뷰 / 정적 분석 체크리스트

| 확인 항목 | 방법 |
|---|---|
| 템플릿 첫 인자에 `+` 가 있는가 | `grep -rn 'Template("' --include=*.java` 후 `+` 검색 |
| 템플릿 첫 인자에 `format(` / `formatted(` 가 있는가 | 동일 검색 |
| 템플릿 첫 인자가 지역 변수/필드인가 | 변수라면 그 변수의 출처를 끝까지 추적 |
| `{n}` 개수와 넘긴 인자 개수가 맞는가 | 불일치는 대개 이어 붙였다는 신호 |
| 템플릿 안에 작은따옴표 `'` 가 있는가 | **리터럴을 직접 쓰고 있다는 뜻.** 대부분 `{n}` 으로 바꿀 수 있음 |
| 정렬 키 / 컬럼명을 템플릿으로 만드는가 | 화이트리스트 필수 ([Step 10](../step-10-dynamic-sort/)) |

마지막 항목이 특히 중요합니다.
**컬럼명이나 정렬 방향은 바인딩 파라미터가 될 수 없습니다.**
`order by ?` 는 SQL 에서 상수 하나로 정렬하라는 뜻이지 그 이름의 컬럼으로 정렬하라는 뜻이 아닙니다.
그래서 동적 컬럼명은 구조적으로 템플릿에 넣을 수밖에 없고, **그렇기 때문에 화이트리스트가 유일한 방어**입니다.
Step 10 에서 정렬 키를 `Map<String, OrderSpecifier<?>>` 로 고정한 이유가 이것입니다.

```java
// 화이트리스트 — 입력값이 키로만 쓰이고, 값은 코드에 있는 표현식
private static final Map<String, OrderSpecifier<?>> SORT_KEYS = Map.of(
        "price",   product.price.desc(),
        "created", product.createdAt.desc(),
        "name",    product.name.asc());

OrderSpecifier<?> spec = SORT_KEYS.getOrDefault(req.sort(), product.id.desc());
```

입력이 무엇이든 SQL 에 들어가는 것은 **코드에 이미 존재하는 세 표현식 중 하나**입니다.

---

## 13-12. `booleanTemplate` 으로 조건 만들기

`where` 절에 표준 문법으로 표현할 수 없는 조건을 넣어야 할 때 씁니다.

```java
private static final String REGEXP = "function('regexp_like', {0}, {1})";

List<Product> result = queryFactory
        .selectFrom(product)
        .where(Expressions.booleanTemplate(REGEXP, product.name, "노트북|모니터"))
        .fetch();
```

**결과**

```sql
select
    p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
    p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from
    products p1_0
where
    regexp_like(p1_0.name, ?)
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [노트북|모니터]

게이밍 노트북 RTX4060
보급형 노트북 15
27인치 4K 모니터
조회 3건
```

동작합니다. 그리고 **13-11 과 완전히 같은 위험**을 가집니다.
`where` 절에 열리는 인젝션은 `select` 절에 열리는 것보다 나쁩니다.
`or 1=1` 하나로 전체 행이 노출되기 때문입니다.

```java
// ❌ 절대 하지 마십시오
Expressions.booleanTemplate("function('regexp_like', {0}, '" + userInput + "')", product.name)
```

`userInput = "x') or 1=1 and function('regexp_like', {0}, 'x"` 같은 입력이면
`where` 조건이 통째로 무력화됩니다.

> 💡 **실무 팁 — `booleanTemplate` 이 정말 필요한지 먼저 확인하십시오**
> `booleanTemplate` 을 쓰려는 상황의 대부분은 표준 방법이 있습니다.
>
> | 하려는 것 | 표준 방법 |
> |---|---|
> | 정규식 매칭 | `like` 로 충분한 경우가 많음. 아니면 애플리케이션 필터링 |
> | 대소문자 무시 비교 | `lower()` (단, 인덱스 포기) 또는 콜레이션 설정 |
> | 날짜 부분 비교 | `orderDate.year()`, `.month()` (13-13) |
> | JSON 필드 조건 | 이 코스 범위 밖. 하려면 화이트리스트된 경로만 |
> | 항상 참 조건 | `Expressions.asBoolean(true).isTrue()` |
>
> 표준으로 안 되는 것이 확인된 뒤에 템플릿을 쓰고,
> 쓸 때는 **상수 문자열 + `{n}` 인자**를 예외 없이 지키십시오.

---

## 13-13. 날짜 표현식

`DateTimePath` 는 날짜 부분을 뽑는 메서드를 제공합니다.

```java
List<Tuple> result = queryFactory
        .select(order.orderDate.year(),
                order.orderDate.month(),
                order.count(),
                order.totalAmount.sum())
        .from(order)
        .groupBy(order.orderDate.year(), order.orderDate.month())
        .orderBy(order.orderDate.year().asc(), order.orderDate.month().asc())
        .limit(4)
        .fetch();
```

**결과**

```sql
select
    extract(year from o1_0.order_date),
    extract(month from o1_0.order_date),
    count(o1_0.order_id),
    sum(o1_0.total_amount)
from
    orders o1_0
group by
    extract(year from o1_0.order_date),
    extract(month from o1_0.order_date)
order by
    extract(year from o1_0.order_date),
    extract(month from o1_0.order_date)
limit ?
```

```
2024 | 1 | 22 | 4,102,000
2024 | 2 | 19 | 3,554,500
2024 | 3 | 25 | 5,880,000
2024 | 4 | 21 | 4,331,000
```

13-10 의 `date_format` 과 결과가 같은데 **DB 고유 함수를 쓰지 않았습니다.**
`extract` 는 표준 SQL 이므로 다른 DB 로 옮겨도 그대로 동작합니다.
가능하면 이쪽을 쓰십시오.

| 메서드 | 생성 SQL |
|---|---|
| `.year()` | `extract(year from ...)` |
| `.month()` | `extract(month from ...)` |
| `.dayOfMonth()` | `extract(day from ...)` |
| `.hour()` / `.minute()` / `.second()` | `extract(hour from ...)` 등 |
| `.dayOfWeek()` | `extract(day_of_week from ...)` |
| `.week()` | `extract(week from ...)` |
| `.yearMonth()` | `extract(year from ...) * 100 + extract(month from ...)` |

`yearMonth()` 는 `202401` 같은 정수를 만듭니다. 정렬과 그룹핑을 한 컬럼으로 처리할 때 편합니다.

### `between` — 인덱스를 살리는 형태

```java
List<Order> result = queryFactory
        .selectFrom(order)
        .where(order.orderDate.between(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 31, 23, 59, 59)))
        .fetch();
```

**결과**

```sql
select
    o1_0.order_id, o1_0.customer_id, o1_0.order_date,
    o1_0.shipping_city, o1_0.status, o1_0.total_amount
from
    orders o1_0
where
    o1_0.order_date between ? and ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:TIMESTAMP) <- [2025-01-01T00:00]
TRACE o.h.orm.jdbc.bind : binding parameter (2:TIMESTAMP) <- [2025-01-31T23:59:59]

조회 24건
```

> ⚠️ **함정 — 같은 조건, 다른 실행 계획**
>
> "2025년 1월 주문" 을 표현하는 두 가지 방법입니다. 결과는 같습니다.
>
> ```java
> // A — 컬럼에 함수
> .where(order.orderDate.year().eq(2025).and(order.orderDate.month().eq(1)))
>
> // B — 범위
> .where(order.orderDate.between(
>         LocalDateTime.of(2025, 1, 1, 0, 0),
>         LocalDateTime.of(2025, 2, 1, 0, 0).minusNanos(1)))
> ```
>
> ```sql
> -- A
> where extract(year from o1_0.order_date) = ? and extract(month from o1_0.order_date) = ?
> -- B
> where o1_0.order_date between ? and ?
> ```
>
> `order_date` 에 인덱스가 있다면 **B 만 그 인덱스를 씁니다.**
> A 는 컬럼을 함수로 감쌌으므로 인덱스의 정렬 순서를 쓸 수 없습니다.
> 함수 기반 인덱스(MySQL 8.0.13+)를 별도로 만들지 않는 한 풀스캔입니다.
>
> ```
> -- A 의 EXPLAIN
> | type | key  | rows | Extra       |
> | ALL  | NULL |  600 | Using where |
>
> -- B 의 EXPLAIN (idx_orders_date 가 있다고 가정)
> | type  | key            | rows | Extra                 |
> | range | idx_orders_date|   24 | Using index condition |
> ```
>
> **집계·그룹핑에는 `year()`/`month()` 를, 필터링에는 `between` 을 쓰십시오.**
> `group by` 는 어차피 전 행을 훑으므로 함수를 써도 손해가 없지만,
> `where` 는 함수 하나로 인덱스 전체를 버리게 됩니다.
> [Step 09](../step-09-sorting-paging/) 의 정렬 컬럼 함수 문제와 정확히 같은 원리입니다.
> [Step 14 의 14-6 절](../step-14-performance/) 에서 이 네 가지 패턴을 다시 정리합니다.

`between` 의 상한을 `2025-01-31 23:59:59` 로 잡으면 `23:59:59.5` 같은 값을 놓칩니다.
`orders.order_date` 는 `DATETIME` (소수 초 없음) 이므로 이 예제에서는 문제가 없지만,
`DATETIME(6)` 컬럼이라면 **`goe(시작) and lt(다음달 1일)`** 형태가 안전합니다.

```java
.where(order.orderDate.goe(LocalDateTime.of(2025, 1, 1, 0, 0))
        .and(order.orderDate.lt(LocalDateTime.of(2025, 2, 1, 0, 0))))
```

```sql
where o1_0.order_date >= ? and o1_0.order_date < ?
```

---

## 13-14. 정리 — 표현식을 고르는 순서

이 스텝에서 다룬 도구들을 안전한 순서로 나열하면 이렇게 됩니다.
**위에서부터 시도하고, 안 될 때만 아래로 내려가십시오.**

| 순위 | 도구 | 타입 안전 | 이식성 | 인젝션 위험 |
|---|---|---|---|---|
| 1 | Q타입 경로 메서드 (`.eq`, `.concat`, `.year()`) | 완전 | 높음 | 없음 |
| 2 | `CaseBuilder`, `coalesce`, `nullif` | 완전 | 높음 | 없음 |
| 3 | `Expressions.constant` / `asNumber` / `allOf` / `anyOf` | 완전 | 높음 | 없음 |
| 4 | `Expressions.*Template` + **상수 문자열** + `{n}` 인자 | 부분 | 낮음 | 없음 |
| 5 | `Expressions.*Template` + 조립된 문자열 | 없음 | 낮음 | **높음** |

**5번은 선택지가 아닙니다.** 표에 넣은 것은 그것을 알아보기 위해서입니다.

4번까지 내려왔다면 다음을 함께 남기십시오.

- 왜 표준 방법으로 안 되는지 주석 한 줄
- 템플릿 상수를 `private static final` 로 분리
- 그 쿼리를 검증하는 테스트 (DB 를 바꿀 때 여기서 깨져야 합니다)

---

## 정리

| 개념 | 핵심 |
|---|---|
| 단순 case | `path.when(v).then(r).otherwise(d)` — 비교값과 결과값 모두 `?` 바인딩 |
| 복합 case | `new CaseBuilder().when(조건식)...` — 임의 조건 분기 |
| case 정렬 | `orderBy` 에 넣으면 `select` 와 `order by` 에 **두 번** 들어가고 **인덱스를 못 씀** |
| 조건부 집계 | `new CaseBuilder().when(...).then(금액).otherwise(ZERO).sum()` — 한 번 읽고 피벗 |
| `constant` | 최적화로 **JPQL 에서 빠질 수 있음.** 쓰인 위치에 따라 다르니 SQL 로 확인 |
| `concat` | 2항. 체인하면 중첩 `concat`. NULL 이 섞이면 **전체 NULL** |
| `stringValue()` | `cast(x as char)`. enum/숫자를 문자열 연산에 넣을 때 필수 |
| 산술 | 체인 순서대로 좌결합. 괄호는 QueryDSL 이 넣어 줌 |
| `BigDecimal.divide` | DB 는 반올림, 자바는 예외. `compareTo` 로 비교하고 스케일을 명시 |
| `coalesce` | `.sum().coalesce(ZERO)` 가 맞고 `.coalesce(ZERO).sum()` 은 다른 뜻 |
| `nullif` | 0 을 NULL 로 → `coalesce` 로 다시 0. "0으로 나누면 0" 규칙을 SQL 에 명시 |
| `allOf`/`anyOf` | null 인자를 무시하며 결합. 괄호를 잃을 수 없는 구조 |
| DB 함수 | `function('name', {0}, {1})` 이 표준. 이름 직접 호출은 Dialect 의존 |
| 커스텀 함수 | Hibernate 5 `applySqlFunction` → Hibernate 6 **`FunctionContributor`**. 시그니처는 문서 확인 |
| **템플릿 인젝션** | **템플릿 문자열은 컴파일 시점 상수만. 값은 예외 없이 `{n}` 으로** |
| 인젝션 신호 | 템플릿 첫 인자의 `+`, `format(`, 작은따옴표 리터럴 |
| 날짜 | 집계는 `year()`/`month()`, 필터는 `between` / `goe`+`lt` |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `CaseBuilder` 로 주문 금액을 `10만원 미만 → "소액"`, `10만~50만 → "중액"`, `50만 이상 → "고액"` 으로
   분류하고, 분류별 주문 건수를 세십시오. 생성 SQL 에 `case` 가 몇 번 나오는지 확인하십시오.
2. 고객을 등급 순(VIP → GOLD → SILVER → BRONZE)으로 정렬해 이름과 등급을 출력하십시오.
   그리고 그 SQL 을 `EXPLAIN` 에 넣었을 때 `Extra` 에 무엇이 뜨는지 예측한 뒤 확인하십시오.
3. 도시별로 `PAID` 매출 합계와 `CANCELLED` 건수를 한 행에 뽑는 쿼리를 조건부 집계로 작성하십시오.
   단, 매출 합계가 NULL 이 되지 않도록 처리하십시오.
4. 전화번호가 NULL 인 고객 3명을 포함해 **전 고객**의 `"이름(전화번호)"` 문자열을 만드십시오.
   NULL 인 경우 `"이름(미등록)"` 이 나와야 합니다.
   `concat` 만 쓰면 왜 NULL 이 나오는지 SQL 로 설명하십시오.
5. 상품의 마진율(`(price - cost) / price * 100`)을 소수점 둘째 자리까지 계산하되,
   `price` 가 0 인 경우에도 예외 없이 0 이 나오도록 작성하십시오.
6. `Expressions.stringTemplate` 으로 주문 날짜를 `yyyy년 MM월` 형태로 포맷하는 코드를 작성하십시오.
   **포맷 문자열을 메서드 파라미터로 받되 인젝션이 불가능한 형태**여야 합니다.
   그리고 인젝션이 가능한 잘못된 버전도 함께 작성해 두 SQL 을 비교하십시오.
7. 2025년 상반기(1~6월) 주문을 조회하는 쿼리를 ① `year()`+`month()` 방식과
   ② `goe`+`lt` 방식 두 가지로 작성하고, 생성 SQL 과 `EXPLAIN` 결과를 대조하십시오.

---

## 다음 단계

표현식은 여기까지입니다. 이제 남은 것은 **그 표현식들이 만든 SQL 이 실제로 얼마나 빠른가**입니다.

Step 14 에서는 지금까지 14개 스텝에서 만든 모든 쿼리를 성능 관점에서 다시 봅니다.
N+1 을 실측으로 진단하고 세 가지 방법으로 해결하며, 생성된 SQL 을 그대로 `EXPLAIN` 에 넣어 읽습니다.
그리고 이 코스의 모든 것을 쓰는 **상품 검색 API** 를 처음부터 끝까지 만듭니다.

→ [Step 14 — 성능과 최종 프로젝트](../step-14-performance/)

---

## 실습 파일

`Practice.java` 를 먼저 실행해 본문의 SQL 이 그대로 나오는지 확인하십시오.
특히 13-4 의 `constant` 가 SQL 에서 사라지는 것과, 13-11 의 두 SQL 차이는
**직접 콘솔에서 봐야** 의미가 전달됩니다.

### Practice.java

- 13-1 ~ 13-13 의 모든 예제를 절 번호 주석과 함께 담았습니다.
- `injectionSafe()` 와 `injectionVulnerable()` 을 나란히 두었습니다.
  두 메서드가 만드는 SQL 을 콘솔에서 비교하십시오.
  **`injectionVulnerable()` 은 학습용입니다. 어떤 형태로도 운영 코드에 복사하지 마십시오.**
- `caseInOrderBy()` 를 실행한 뒤 그 SQL 을 MySQL 콘솔에 붙여 `EXPLAIN` 을 걸어 보십시오.

```java file="./Practice.java"
```

### Exercise.java

- 7문제. 각 문제에 요구사항과 기대 결과가 주석으로 적혀 있습니다.
- 6번은 **안전한 버전과 위험한 버전을 모두** 작성하는 문제입니다.
  위험한 버전을 직접 써 봐야 그 형태를 리뷰에서 알아볼 수 있습니다.

```java file="./Exercise.java"
```

### Solution.java

- 정답과 함께 **왜 그 형태인지**, 그리고 **흔한 오답이 왜 틀렸는지**를 주석으로 설명합니다.
- 3번과 5번은 `coalesce` 의 위치가 핵심입니다. 답이 맞아도 SQL 이 다르면 틀린 것입니다.

```java file="./Solution.java"
```
