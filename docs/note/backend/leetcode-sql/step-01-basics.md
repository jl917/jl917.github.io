# STEP 1 — SQL 기초

> SELECT / WHERE / JOIN / DISTINCT 등 SQL 의 뼈대를 익힌다. 여기서 손에 붙여야 뒤가 편하다.

[← 목록으로](index.md)

---

## 1. [175] Combine Two Tables

**링크**: https://leetcode.cn/problems/combine-two-tables/  
**학습 포인트**: LEFT JOIN — 주소가 없는 사람도 결과에 나와야 한다

### 문제

`Person` 테이블

| 컬럼         | 타입    | 설명            |
|--------------|---------|-----------------|
| personId     | int     | PK              |
| lastName     | varchar | 성              |
| firstName    | varchar | 이름            |

`Address` 테이블

| 컬럼        | 타입    | 설명                        |
|-------------|---------|-----------------------------|
| addressId   | int     | PK                          |
| personId    | int     | Person 을 가리키는 FK       |
| city        | varchar | 도시                        |
| state       | varchar | 주/도                       |

모든 사람에 대해 `firstName, lastName, city, state` 를 조회한다. **주소 정보가 없는 사람이라도 반드시 결과에 포함**되어야 하며, 이 경우 `city`, `state` 는 `NULL` 로 채운다.

예시: Person 에 홍길동이 있는데 Address 에 그의 `personId` 가 없다면, 결과에는 `firstName=길동, lastName=홍, city=NULL, state=NULL` 한 행이 나와야 한다.

### 정답

```sql
SELECT
    p.firstName,
    p.lastName,
    a.city,
    a.state
FROM Person AS p
LEFT JOIN Address AS a
    ON p.personId = a.personId;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: 두 테이블을 `personId` 로 이어 붙여 사람별 정보를 한 줄로 만드는 문제다. 두 테이블을 연결하는 도구는 `JOIN` 이다.

2. **어떤 JOIN 인가**: 여기서 핵심은 "주소가 없는 사람도 나와야 한다"는 조건이다. `INNER JOIN` 을 쓰면 양쪽 테이블에 `personId` 가 모두 존재하는 행만 살아남는다. 즉 주소가 없는 사람은 결과에서 통째로 사라진다. 이건 요구사항 위반이다.

3. **그래서 LEFT JOIN**: `LEFT JOIN` 은 왼쪽 테이블(`Person`)의 모든 행을 보존한다. 오른쪽(`Address`)에서 짝을 못 찾으면 오른쪽 컬럼들을 `NULL` 로 채워서라도 그 사람을 결과에 남긴다. "왼쪽은 무조건 다 나온다"가 LEFT JOIN 의 정체성이다.

4. **기준 테이블 선택**: "모든 사람"이 기준이므로 `Person` 을 왼쪽(FROM 뒤)에 둔다. 만약 반대로 두고 싶다면 `Address RIGHT JOIN Person` 도 논리적으로 같지만, 관례상 기준 테이블을 왼쪽에 두는 LEFT JOIN 이 읽기 쉽다.

### 핵심 개념

- **LEFT JOIN**: 왼쪽 테이블의 모든 행을 유지하고, 짝이 없으면 오른쪽을 NULL 로 채운다.
- **INNER JOIN vs LEFT JOIN**: "짝이 없는 행을 버릴 것인가 남길 것인가"가 둘을 가르는 기준이다.
- **테이블 별칭(alias)**: `Person AS p` 처럼 별칭을 주면 컬럼 출처가 명확해진다.

### ⚠️ 흔한 실수

- `INNER JOIN` 을 써서 주소 없는 사람이 빠지는 실수. 에러는 안 나지만 정답이 아니다.
- `ON` 절 조건을 빠뜨리면 카테시안 곱(모든 조합)이 나온다.

### 💡 대안 / 응용

- 실무에서 "부모 테이블 기준으로 자식 유무와 관계없이 전부 뽑기"는 대시보드/리포트에서 매우 흔한 패턴이다. LEFT JOIN 을 기본기로 확실히 잡아두면 좋다.

---

## 2. [584] Find Customer Referee

**링크**: https://leetcode.cn/problems/find-customer-referee/  
**학습 포인트**: WHERE 조건과 NULL — `!= 2` 만으로는 NULL 행이 빠진다

### 문제

`Customer` 테이블

| 컬럼         | 타입    | 설명                                  |
|--------------|---------|---------------------------------------|
| id           | int     | PK                                    |
| name         | varchar | 고객 이름                             |
| referee_id   | int     | 이 고객을 추천한 사람의 id (NULL 가능)|

**추천인(referee)이 id 2 번이 아닌 고객**의 이름(`name`)을 모두 조회한다.

주의: `referee_id` 는 `NULL` 일 수 있다(아무도 추천하지 않음). 이런 고객도 "2번이 추천한 게 아니므로" 결과에 포함되어야 한다.

### 정답

```sql
SELECT name
FROM Customer
WHERE referee_id != 2
   OR referee_id IS NULL;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: `referee_id` 가 2 가 아닌 행을 고르는 단순 필터링 문제다. 자연스럽게 `WHERE referee_id != 2` 를 떠올린다.

2. **NULL 의 함정**: 그런데 SQL 에서 `NULL` 은 "값이 없음/알 수 없음"을 뜻하는 특수한 상태다. `NULL != 2` 의 결과는 `TRUE` 가 아니라 **`NULL`(알 수 없음)** 이다. `WHERE` 는 조건이 `TRUE` 인 행만 통과시키므로, `NULL` 인 행은 조용히 탈락한다. 즉 `referee_id` 가 NULL 인 고객이 전부 사라진다.

3. **왜 NULL 비교가 안 되나**: "알 수 없는 값이 2 와 다른가?"라는 질문에 SQL 은 "모른다"라고 답한다. 그래서 `= NULL`, `!= NULL` 같은 비교는 항상 `NULL` 을 반환하고, 절대 참이 되지 않는다.

4. **해결**: NULL 여부는 반드시 전용 연산자 `IS NULL` / `IS NOT NULL` 로 판별한다. 따라서 조건을 `referee_id != 2 OR referee_id IS NULL` 로 확장해 NULL 행을 명시적으로 포함시킨다.

### 핵심 개념

- **3값 논리(Three-valued logic)**: 조건 결과는 `TRUE / FALSE / NULL` 세 가지다. `WHERE` 는 `TRUE` 만 통과시킨다.
- **NULL 비교**: `= NULL`, `!= NULL` 은 무의미하다. `IS NULL` / `IS NOT NULL` 을 써야 한다.

### ⚠️ 흔한 실수

- `WHERE referee_id != 2` 만 쓰는 것. 문법 에러가 없어 통과할 것 같지만 NULL 행이 전부 빠져 오답이 된다. 이 문제가 노리는 정확히 그 함정이다.

### 💡 대안 / 응용

- MySQL 의 NULL 안전 비교를 활용해 `WHERE NOT (referee_id <=> 2)` 로도 쓸 수 있다. `<=>` 는 NULL 도 정상 비교하는 연산자라 `NULL <=> 2` 는 `FALSE`, `NOT FALSE` 는 `TRUE` 가 되어 NULL 행이 살아난다.
- `WHERE IFNULL(referee_id, 0) != 2` 처럼 NULL 을 다른 값으로 치환해 처리하는 방법도 있다.

---

## 3. [595] Big Countries

**링크**: https://leetcode.cn/problems/big-countries/  
**학습 포인트**: AND / OR — 두 조건 중 하나만 만족해도 되는 경우

### 문제

`World` 테이블

| 컬럼        | 타입    | 설명           |
|-------------|---------|----------------|
| name        | varchar | 국가명 (PK)    |
| continent   | varchar | 대륙           |
| area        | int     | 면적 (km²)     |
| population  | int     | 인구           |
| gdp         | bigint  | GDP            |

**"큰 나라"** 를 뽑는다. 큰 나라의 정의는 다음 중 **하나라도** 만족하는 경우다.
- 면적(`area`)이 3,000,000 이상, **또는**
- 인구(`population`)가 25,000,000 이상

결과로 `name, population, area` 를 조회한다.

### 정답

```sql
SELECT name, population, area
FROM World
WHERE area >= 3000000
   OR population >= 25000000;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: 조건 필터링 문제다. 핵심은 두 조건을 `AND` 로 묶느냐 `OR` 로 묶느냐다.

2. **AND 와 OR 구분**: 문제 문장의 "또는(하나라도 만족)"이 결정적 단서다. "둘 다 만족"이면 `AND`, "하나라도 만족"이면 `OR` 다. 여기서는 면적이 크거나 **또는** 인구가 많으면 되므로 `OR` 다.

3. **왜 AND 가 아닌가**: `AND` 로 쓰면 면적도 크고 인구도 많은 나라만 걸러진다. 면적만 큰 사막 국가나 인구만 많은 소국이 빠져서 정의에 어긋난다. "하나라도"라는 말은 곧 `OR` 다.

### 핵심 개념

- **OR**: 여러 조건 중 하나라도 참이면 행이 통과한다.
- **AND**: 모든 조건이 동시에 참이어야 통과한다.
- **조건을 문장에서 읽어내기**: "또는/하나라도" → OR, "그리고/동시에/모두" → AND.

### ⚠️ 흔한 실수

- 문제 지문을 대충 읽고 `AND` 로 묶는 실수. 결과 개수가 확 줄어드는데도 눈치 못 채기 쉽다.

### 💡 대안 / 응용

- 조건이 복잡해지면 `WHERE (A OR B) AND C` 처럼 괄호로 우선순위를 명시하는 습관을 들이면 좋다. SQL 은 `AND` 가 `OR` 보다 먼저 평가되므로, 의도와 다르게 묶이지 않도록 괄호가 안전하다.

---

## 4. [1148] Article Views I

**링크**: https://leetcode.cn/problems/article-views-i/  
**학습 포인트**: DISTINCT + ORDER BY — 자기 글을 자기가 본 사람 찾기

### 문제

`Views` 테이블

| 컬럼        | 타입    | 설명               |
|-------------|---------|--------------------|
| article_id  | int     | 글 id              |
| author_id   | int     | 글 작성자 id       |
| viewer_id   | int     | 글을 본 사람 id    |
| view_date   | date    | 조회 날짜          |

이 테이블에는 PK 가 없어 **중복 행이 있을 수 있다**.

**자기가 쓴 글을 자기가 본** 사람, 즉 `author_id = viewer_id` 인 작성자들의 `id` 를 찾는다. 결과 컬럼명은 `id` 로 하고, **중복 없이**, `id` **오름차순** 정렬한다.

### 정답

```sql
SELECT DISTINCT author_id AS id
FROM Views
WHERE author_id = viewer_id
ORDER BY id ASC;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: "작성자 == 조회자"인 행을 찾는 필터링 + 중복 제거 + 정렬 문제다.

2. **조건**: 같은 행 안에서 `author_id` 와 `viewer_id` 를 비교한다. 두 컬럼이 같으면 그 사람은 자기 글을 자기가 본 것이다. `WHERE author_id = viewer_id`.

3. **왜 DISTINCT 인가**: 한 사람이 여러 글을 자기가 봤거나, 중복 행이 존재할 수 있어서 같은 `id` 가 여러 번 나온다. 문제는 "사람 목록"을 원하므로 `DISTINCT` 로 중복을 제거한다.

4. **왜 ORDER BY 인가**: 문제가 오름차순 정렬을 명시적으로 요구한다. `ORDER BY id ASC` 로 정렬한다(`ASC` 는 기본값이라 생략 가능). `SELECT` 에서 붙인 별칭 `id` 를 `ORDER BY` 에서 바로 쓸 수 있다.

### 핵심 개념

- **DISTINCT**: 결과의 중복 행을 제거한다.
- **컬럼끼리 비교**: `WHERE` 에서 상수뿐 아니라 같은 행의 두 컬럼도 비교할 수 있다.
- **별칭과 ORDER BY**: `SELECT` 에서 정한 별칭을 `ORDER BY` 에서 참조 가능하다.

### ⚠️ 흔한 실수

- `DISTINCT` 를 빠뜨려 같은 사람이 여러 번 나오는 오답.
- 정렬을 빼먹는 실수. 채점 순서가 다르면 오답 처리된다.

### 💡 대안 / 응용

- `GROUP BY author_id` 로도 중복을 제거할 수 있다. 단순 중복 제거만 필요하면 `DISTINCT` 가 의도를 더 직관적으로 드러낸다.

---

## 5. [1683] Invalid Tweets

**링크**: https://leetcode.cn/problems/invalid-tweets/  
**학습 포인트**: CHAR_LENGTH vs LENGTH — 글자 수와 바이트 수는 다르다

### 문제

`Tweets` 테이블

| 컬럼        | 타입     | 설명                        |
|-------------|----------|-----------------------------|
| tweet_id    | int      | PK                          |
| content     | varchar  | 트윗 내용 (최대 15자 정도)  |

**내용의 글자 수가 15 자를 초과하는** 트윗을 "유효하지 않은 트윗"으로 본다. 유효하지 않은 트윗의 `tweet_id` 를 조회한다. 순서는 상관없다.

### 정답

```sql
SELECT tweet_id
FROM Tweets
WHERE CHAR_LENGTH(content) > 15;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: 문자열 길이를 재서 임계값(15)을 넘는 행을 고르는 문제다. 문자열 길이 함수가 필요하다.

2. **왜 LENGTH 가 아니라 CHAR_LENGTH 인가**: MySQL 에는 길이 함수가 두 개 있고, 둘은 의미가 다르다.
   - `LENGTH()` 는 **바이트 수**를 센다.
   - `CHAR_LENGTH()`(= `CHARACTER_LENGTH`)는 **글자 수**를 센다.

3. **차이가 나는 이유**: UTF-8 인코딩에서 영문/숫자는 1 바이트지만, 한글은 한 글자가 3 바이트, 이모지는 4 바이트를 차지한다. 예를 들어 `"안녕"` 은 글자 수로는 2 지만 `LENGTH()` 로는 6 이 나온다. 문제는 "글자 수"를 기준으로 하므로 반드시 `CHAR_LENGTH` 를 써야 멀티바이트 문자에서도 정확하다.

4. **영문뿐이라 우연히 맞더라도**: LeetCode 데이터가 영문뿐이면 `LENGTH` 로도 통과할 수 있지만, 이는 우연이다. "글자 수"의 정확한 도구는 `CHAR_LENGTH` 이므로 이것을 기본으로 삼는다.

### 핵심 개념

- **CHAR_LENGTH**: 인코딩과 무관하게 글자(문자) 개수를 센다.
- **LENGTH**: 저장에 사용된 바이트 수를 센다. 멀티바이트 문자에서 값이 커진다.
- **인코딩 인식**: 한글/이모지가 섞인 데이터에서는 둘의 차이가 실무 버그로 이어진다.

### ⚠️ 흔한 실수

- 무심코 `LENGTH()` 를 쓰는 것. 영문 데이터로는 통과해도, 한글이 들어오면 길이를 과대 계산해 멀쩡한 글을 "유효하지 않다"고 잘못 걸러낸다.

### 💡 대안 / 응용

- 실무에서 "제목은 최대 N 글자"처럼 사용자에게 보이는 글자 수 제한을 검증할 때는 항상 `CHAR_LENGTH` 를 써야 한다. 바이트 기준 제한(`LENGTH`)은 저장 공간 관점의 다른 이야기다.

---

## 6. [1378] Replace Employee ID With The Unique Identifier

**링크**: https://leetcode.cn/problems/replace-employee-id-with-the-unique-identifier/  
**학습 포인트**: LEFT JOIN — 매핑이 없는 직원도 NULL 로 나와야 한다

### 문제

`Employees` 테이블

| 컬럼    | 타입    | 설명           |
|---------|---------|----------------|
| id      | int     | 직원 id (PK)   |
| name    | varchar | 직원 이름      |

`EmployeeUNI` 테이블

| 컬럼    | 타입    | 설명                       |
|---------|---------|----------------------------|
| id      | int     | 직원 id                    |
| unique_id | int   | 이 직원의 고유 식별자      |

각 직원의 이름과 `unique_id` 를 조회한다. **`unique_id` 가 없는 직원도 결과에 포함**하며, 이 경우 `unique_id` 는 `NULL` 로 표시한다. 결과 컬럼은 `unique_id, name`. 순서는 상관없다.

### 정답

```sql
SELECT
    eu.unique_id,
    e.name
FROM Employees AS e
LEFT JOIN EmployeeUNI AS eu
    ON e.id = eu.id;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: 직원 테이블에 고유 식별자 테이블을 이어 붙이는 문제다. 175 번과 구조가 똑같다.

2. **기준 테이블**: "모든 직원"이 나와야 하므로 `Employees` 가 기준(왼쪽)이다.

3. **왜 LEFT JOIN 인가**: 어떤 직원은 `EmployeeUNI` 에 매핑이 없을 수 있다. `INNER JOIN` 을 쓰면 매핑 없는 직원이 사라진다. 하지만 문제는 "매핑 없으면 `unique_id = NULL` 로라도 직원을 남기라"고 한다. 그러므로 왼쪽을 보존하는 `LEFT JOIN` 이 정확하다.

4. **NULL 채우기**: 짝을 못 찾은 직원의 `eu.unique_id` 는 자동으로 `NULL` 이 된다. 별도 처리가 필요 없다.

### 핵심 개념

- **LEFT JOIN 재확인**: "한쪽 전부 + 있으면 붙이고 없으면 NULL" 패턴.
- **매핑(lookup) 테이블 조인**: id 를 다른 코드/식별자로 변환하는 흔한 작업.

### ⚠️ 흔한 실수

- `INNER JOIN` 사용으로 매핑 없는 직원 누락.
- `ON` 조건을 `e.id = eu.unique_id` 처럼 엉뚱한 컬럼으로 잘못 연결하는 실수.

### 💡 대안 / 응용

- 코드 값을 사람이 읽을 이름으로 바꾸는 "코드 → 라벨" 변환은 리포트에서 매일 쓰는 조인이다. 원본을 다 보존해야 할 땐 LEFT JOIN 이 기본이다.

---

## 7. [1068] Product Sales Analysis I

**링크**: https://leetcode.cn/problems/product-sales-analysis-i/  
**학습 포인트**: JOIN — 판매 기록에 상품명을 붙인다

### 문제

`Sales` 테이블

| 컬럼        | 타입    | 설명                  |
|-------------|---------|-----------------------|
| sale_id     | int     | 판매 id (PK 일부)     |
| product_id  | int     | 상품 id               |
| year        | int     | 판매 연도 (PK 일부)   |
| quantity    | int     | 수량                  |
| price       | int     | 단가                  |

`Product` 테이블

| 컬럼          | 타입    | 설명            |
|---------------|---------|-----------------|
| product_id    | int     | 상품 id (PK)    |
| product_name  | varchar | 상품명          |

각 판매 기록에 대해 `product_name, year, price` 를 조회한다. 순서는 상관없다.

### 정답

```sql
SELECT
    p.product_name,
    s.year,
    s.price
FROM Sales AS s
JOIN Product AS p
    ON s.product_id = p.product_id;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: 판매 테이블에는 `product_id` 만 있고 이름이 없다. 사람이 읽을 `product_name` 을 붙이려면 `Product` 테이블과 연결해야 한다. 연결 도구는 `JOIN`.

2. **연결 키**: 두 테이블의 공통 컬럼은 `product_id` 다. `ON s.product_id = p.product_id` 로 같은 상품끼리 짝짓는다.

3. **왜 INNER JOIN(그냥 JOIN)인가**: `Sales` 의 모든 판매는 반드시 유효한 상품을 가리킨다(FK 제약). 즉 짝이 없는 판매가 없으므로, 왼쪽을 굳이 보존할 필요가 없다. `INNER JOIN` 으로 충분하다. MySQL 에서 `JOIN` 은 `INNER JOIN` 의 축약이다.

4. **기준을 Sales 로**: "각 판매 기록"이 결과 단위이므로 `Sales` 가 왼쪽에 온다. 판매가 여러 건이면 같은 상품명이 여러 번 나올 수 있는데, 이는 정상이다(중복 제거 요구 없음).

### 핵심 개념

- **INNER JOIN**: 양쪽에 짝이 있는 행만 결합한다.
- **JOIN = INNER JOIN**: MySQL 에서 키워드를 생략하면 INNER 다.
- **팩트 + 디멘션**: 사실(판매) 테이블에 속성(상품명) 테이블을 붙이는 전형적 구조.

### ⚠️ 흔한 실수

- `ON` 절을 빼먹어 카테시안 곱이 되는 실수.
- 결과에 `product_id` 를 포함시키는 등 요구 컬럼을 착각하는 실수.

### 💡 대안 / 응용

- 만약 판매가 한 건도 없는 상품까지 나열해야 한다면 `Product LEFT JOIN Sales` 로 방향을 바꾼다. "무엇을 다 보존할지"에 따라 조인 종류와 방향이 결정된다.

---

## 8. [1581] Customer Who Visited but Did Not Make Any Transactions

**링크**: https://leetcode.cn/problems/customer-who-visited-but-did-not-make-any-transactions/  
**학습 포인트**: LEFT JOIN + IS NULL(안티조인) 그리고 COUNT(*) GROUP BY

### 문제

`Visits` 테이블

| 컬럼         | 타입    | 설명            |
|--------------|---------|-----------------|
| visit_id     | int     | 방문 id (PK)    |
| customer_id  | int     | 고객 id         |

`Transactions` 테이블

| 컬럼             | 타입    | 설명                         |
|------------------|---------|------------------------------|
| transaction_id   | int     | 거래 id (PK)                 |
| visit_id         | int     | 어떤 방문에서 발생했는지     |
| amount           | int     | 거래 금액                    |

**매장을 방문했지만 거래를 한 건도 하지 않은** 고객을 찾는다. 각 고객(`customer_id`)별로 그런 "거래 없는 방문"이 몇 번이었는지 `count_no_trans` 로 함께 조회한다. 순서는 상관없다.

예시: 고객 30 이 방문했는데 그 방문에 연결된 거래가 하나도 없으면, 그 방문은 "거래 없는 방문"이다. 이런 방문 횟수를 고객별로 센다.

### 정답

```sql
SELECT
    v.customer_id,
    COUNT(*) AS count_no_trans
FROM Visits AS v
LEFT JOIN Transactions AS t
    ON v.visit_id = t.visit_id
WHERE t.transaction_id IS NULL
GROUP BY v.customer_id;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: "방문은 했지만 거래가 없는" 경우를 찾는 문제다. 이는 "한쪽에는 있는데 다른 쪽에는 없는" 것을 찾는 전형적 **안티조인(anti-join)** 상황이다.

2. **어떻게 '없음'을 찾나**: `Visits` 를 기준으로 `Transactions` 를 `LEFT JOIN` 한다. 어떤 방문에 거래가 있으면 오른쪽 컬럼이 채워지고, 거래가 없으면 오른쪽 컬럼이 전부 `NULL` 이 된다.

3. **핵심 필터 `IS NULL`**: 그다음 `WHERE t.transaction_id IS NULL` 로 "오른쪽 짝을 못 찾은 방문"만 골라낸다. `transaction_id` 는 PK 라 실제 거래가 있으면 절대 NULL 이 아니다. 그것이 NULL 이라는 건 곧 "이 방문엔 매칭되는 거래가 없다"는 확실한 신호다. 이 `LEFT JOIN + IS NULL` 조합이 안티조인의 대표 패턴이다.

4. **집계**: 남은 것은 "거래 없는 방문" 행들이다. 이걸 고객별로 묶어(`GROUP BY v.customer_id`) 개수를 `COUNT(*)` 로 센다. 각 그룹의 행 수 = 그 고객의 거래 없는 방문 횟수다.

5. **왜 COUNT(\*)인가**: 여기서는 그룹 내 행 수 자체를 세면 되므로 `COUNT(*)` 가 자연스럽다. 어차피 NULL 을 세는 게 아니라 "필터를 통과한 방문 행 개수"를 세는 것이라 `COUNT(*)` 로 충분하다.

### 핵심 개념

- **안티조인 패턴**: `LEFT JOIN` 후 `WHERE 오른쪽PK IS NULL` → "짝이 없는 왼쪽 행"만 남긴다.
- **GROUP BY + COUNT**: 그룹으로 묶어 각 그룹의 크기를 센다.
- **NULL 을 활용한 존재 판별**: LEFT JOIN 의 NULL 은 "매칭 실패"를 뜻한다.

### ⚠️ 흔한 실수

- `IS NULL` 로 검사할 컬럼을 잘못 고르는 실수. 반드시 오른쪽 테이블에서 **NULL 이 될 수 없는 컬럼(주로 PK)** 을 검사해야 한다. `amount` 처럼 원래 NULL 가능한 컬럼을 검사하면 논리가 깨진다.
- `GROUP BY` 를 빼서 전체가 한 줄로 뭉치는 실수.

### 💡 대안 / 응용

- `NOT IN` 서브쿼리로도 가능하다.
  ```sql
  SELECT customer_id, COUNT(*) AS count_no_trans
  FROM Visits
  WHERE visit_id NOT IN (SELECT visit_id FROM Transactions)
  GROUP BY customer_id;
  ```
  단, `Transactions.visit_id` 에 NULL 이 섞이면 `NOT IN` 이 오작동할 수 있으니(10번 문제 참고) 주의한다. 그래서 실무에서는 `NOT EXISTS` 나 `LEFT JOIN ... IS NULL` 을 더 선호한다.

---

## 9. [197] Rising Temperature

**링크**: https://leetcode.cn/problems/rising-temperature/  
**학습 포인트**: Self Join + DATEDIFF — "어제보다 오늘이 더 더운 날"

### 문제

`Weather` 테이블

| 컬럼           | 타입   | 설명                 |
|----------------|--------|----------------------|
| id             | int    | PK                   |
| recordDate     | date   | 측정 날짜 (유일)     |
| temperature    | int    | 그날의 기온          |

**바로 전날(하루 전)보다 기온이 더 높은 날**의 `id` 를 모두 조회한다. 순서는 상관없다.

예시: 2015-01-01 은 10 도, 2015-01-02 는 25 도라면, 01-02 는 전날보다 더우므로 결과에 포함된다. 반면 며칠 건너뛴 비교는 안 된다. 반드시 "바로 하루 전"과 비교한다.

### 정답

```sql
SELECT w1.id
FROM Weather AS w1
JOIN Weather AS w2
    ON DATEDIFF(w1.recordDate, w2.recordDate) = 1
WHERE w1.temperature > w2.temperature;
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: 한 행(오늘)을 다른 행(어제)과 비교해야 한다. 그런데 어제 데이터도 같은 `Weather` 테이블에 들어 있다. 같은 테이블을 두 개인 것처럼 다뤄야 한다 → **셀프 조인(self join)**.

2. **셀프 조인 구성**: 같은 테이블에 별칭 두 개(`w1`, `w2`)를 준다. `w1` 을 "오늘", `w2` 를 "어제"라고 생각한다. 이렇게 하면 한 테이블에서 두 날짜 행을 나란히 놓고 비교할 수 있다.

3. **"바로 하루 전"을 어떻게 표현하나**: 날짜 차이를 계산하는 `DATEDIFF(a, b)` 는 `a - b` 를 **일(day) 단위**로 돌려준다. `DATEDIFF(w1.recordDate, w2.recordDate) = 1` 은 "w1 이 w2 보다 정확히 하루 뒤"라는 뜻이다. 이게 곧 "w1=오늘, w2=어제" 관계다.

4. **왜 날짜 뺄셈(`recordDate - 1`)이 아니라 DATEDIFF 인가**: 날짜에는 빠진 날이 있을 수 있고, 월말/월초를 넘나들면 단순 산술이 위험하다. `DATEDIFF` 는 달력상 실제 하루 차이를 정확히 계산해 주므로 안전하다. `id` 가 연속이라고 가정해 `w1.id = w2.id + 1` 로 푸는 것은 **오답**이다(id 순서와 날짜 순서가 일치한다는 보장이 없다).

5. **기온 조건**: 짝지어진 오늘/어제 중에서 `w1.temperature > w2.temperature`, 즉 오늘이 더 더운 경우만 남긴다. 남은 `w1.id` 가 답이다.

### 핵심 개념

- **Self Join**: 한 테이블에 별칭 두 개를 부여해 자기 자신과 조인한다. 행 간 비교의 기본 기법.
- **DATEDIFF(a, b)**: 두 날짜의 차이를 일수로 반환(`a - b`). 부호와 인자 순서에 주의.
- **날짜는 산술이 아니라 날짜 함수로**: 달력 규칙 때문에 전용 함수가 안전하다.

### ⚠️ 흔한 실수

- `DATEDIFF` 인자 순서를 바꿔 `= -1` 이 되어야 하는데 `= 1` 로 쓰거나 그 반대로 하는 실수. "오늘 - 어제 = 1" 로 방향을 고정해 외우면 좋다.
- `id` 연속성에 기대어 `w1.id = w2.id - 1` 로 푸는 실수. 날짜가 비어 있으면 틀린다.

### 💡 대안 / 응용

- MySQL 8 이라면 윈도 함수 `LAG` 로도 우아하게 풀린다.
  ```sql
  SELECT id FROM (
      SELECT id, temperature,
             LAG(temperature) OVER (ORDER BY recordDate) AS prev_temp,
             DATEDIFF(recordDate, LAG(recordDate) OVER (ORDER BY recordDate)) AS diff
      FROM Weather
  ) t
  WHERE temperature > prev_temp AND diff = 1;
  ```
  `LAG` 로 바로 전 행 값을 끌어와 비교하는 방식이며, 큰 테이블에서 셀프 조인보다 효율적일 수 있다.

---

## 10. [607] Sales Person

**링크**: https://leetcode.cn/problems/sales-person/  
**학습 포인트**: NOT EXISTS / NOT IN — "RED 회사에 판 적 없는 영업사원"과 NULL 함정

### 문제

`SalesPerson` 테이블

| 컬럼               | 타입    | 설명              |
|--------------------|---------|-------------------|
| sales_id           | int     | 영업사원 id (PK)  |
| name               | varchar | 이름              |
| salary, commission_rate, hire_date | ... | 기타 |

`Company` 테이블

| 컬럼        | 타입    | 설명            |
|-------------|---------|-----------------|
| com_id      | int     | 회사 id (PK)    |
| name        | varchar | 회사명          |
| city        | varchar | 도시            |

`Orders` 테이블

| 컬럼        | 타입    | 설명                       |
|-------------|---------|----------------------------|
| order_id    | int     | 주문 id (PK)               |
| order_date  | date    | 주문 날짜                  |
| com_id      | int     | 주문한 회사 id             |
| sales_id    | int     | 주문을 성사시킨 영업사원   |
| amount      | int     | 금액                       |

**"RED" 라는 회사에 단 한 번도 주문을 성사시킨 적 없는** 영업사원의 이름(`name`)을 조회한다.

### 정답

```sql
SELECT s.name
FROM SalesPerson AS s
WHERE s.sales_id NOT IN (
    SELECT o.sales_id
    FROM Orders AS o
    JOIN Company AS c ON o.com_id = c.com_id
    WHERE c.name = 'RED'
);
```

### 풀이 — 왜 이렇게 하는가

1. **무엇을 묻는가**: "RED 에 판 적 있는 사람"의 여집합, 즉 "RED 에 판 적 없는 사람"을 찾는다. 먼저 "RED 에 판 적 있는 사람 집합"을 만들고, 전체 영업사원에서 그 집합을 빼면 된다.

2. **RED 에 판 사람 집합 만들기**: `Orders` 를 `Company` 와 조인해 `Company.name = 'RED'` 인 주문만 남기고, 그 주문들의 `sales_id` 를 모은다. 이것이 "RED 와 거래한 영업사원 id 목록"이다.

3. **여집합 뽑기**: 전체 `SalesPerson` 중 이 목록에 **없는** 사람이 답이다. `NOT IN` 으로 표현한다.

4. **NOT IN 의 NULL 함정 (반드시 이해)**: 만약 서브쿼리 결과에 `NULL` 이 하나라도 섞이면, `NOT IN` 은 **전체 결과를 통째로 비워버린다**. 이유는 3값 논리다. `x NOT IN (a, b, NULL)` 은 내부적으로 `x != a AND x != b AND x != NULL` 로 풀리는데, `x != NULL` 은 `NULL`(알 수 없음)이라 전체 `AND` 결과가 `TRUE` 가 되지 못한다. 그래서 아무도 통과하지 못한다. 이 문제에서는 `Orders.sales_id` 가 NULL 이 아니므로 안전하지만, 컬럼이 NULL 가능이라면 `NOT IN` 은 위험하다.

5. **더 안전한 대안 NOT EXISTS**: 그래서 이런 "존재하지 않음" 조건은 `NOT EXISTS` 로 쓰는 것이 안전하다. `NOT EXISTS` 는 NULL 에 흔들리지 않는다(아래 대안 참고).

### 핵심 개념

- **여집합 사고**: "A 가 아닌 것" = 전체 − "A 인 것 집합".
- **NOT IN + NULL 함정**: 서브쿼리에 NULL 이 있으면 NOT IN 결과가 전부 비어버린다. 항상 서브쿼리 컬럼의 NULL 가능성을 확인한다.
- **NOT EXISTS**: NULL 안전한 안티 조건 표현. 실무 권장.

### ⚠️ 흔한 실수

- 서브쿼리 컬럼이 NULL 가능인데 `NOT IN` 을 써서 결과가 통째로 사라지는 사고. 에러가 안 나 원인을 찾기 매우 어렵다.
- `IN` 서브쿼리에서 회사명을 잘못 필터링해(예: RED 필터 누락) "주문한 적 있는 모든 사람"을 빼버리는 실수.

### 💡 대안 / 응용

- **NOT EXISTS 버전** (NULL 안전, 실무 권장):
  ```sql
  SELECT s.name
  FROM SalesPerson AS s
  WHERE NOT EXISTS (
      SELECT 1
      FROM Orders AS o
      JOIN Company AS c ON o.com_id = c.com_id
      WHERE c.name = 'RED'
        AND o.sales_id = s.sales_id
  );
  ```
  각 영업사원마다 "RED 와의 주문이 하나라도 있는가"를 확인하고, 하나도 없으면(`NOT EXISTS`) 결과에 포함한다. NULL 걱정 없이 동작한다.
- **LEFT JOIN + IS NULL 버전** (8번의 안티조인 패턴 재활용):
  ```sql
  SELECT s.name
  FROM SalesPerson AS s
  LEFT JOIN (
      SELECT DISTINCT o.sales_id
      FROM Orders AS o
      JOIN Company AS c ON o.com_id = c.com_id
      WHERE c.name = 'RED'
  ) r ON s.sales_id = r.sales_id
  WHERE r.sales_id IS NULL;
  ```

---
