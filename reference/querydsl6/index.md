# QueryDSL 6 완전 학습 코스

QueryDSL 을 **"생성되는 SQL 을 눈으로 확인하며"** 배우는 14개 스텝짜리 코스입니다.

이 코스는 "타입 안전한 쿼리를 짜는 법"을 가르치지 않습니다. 그건 결과일 뿐입니다.
이 코스가 가르치는 것은 **내가 쓴 자바 코드가 어떤 SQL 로 번역되는가**입니다.
모든 예제에는 `hibernate.SQL` 로그에 실제로 찍힌 SQL 이 **결과** 블록으로 붙어 있고,
여러분의 콘솔에도 같은 SQL 이 나와야 합니다. 다르다면 그 자리에서 멈추고 원인을 찾으십시오.

QueryDSL 에서 정말 위험한 코드는 컴파일 에러가 나는 코드가 아니라
**컴파일도 되고 실행도 되는데, 나가는 SQL 이 내가 의도한 것과 다른 코드**입니다.

검증 환경은 QueryDSL **6.12** / Spring Boot 3.2.5 / Hibernate 6.4 / Java 21 / MySQL 8.0 이고,
실습 도메인은 [MySQL 8 코스](../mysql8/)와 **동일한 `shop` 스키마**를 그대로 씁니다.

---

## ⚠️ 먼저 알아야 할 것 — QueryDSL 6 은 포크입니다

시작하기 전에 반드시 짚고 넘어가야 할 사실이 있습니다.
**여러분이 검색으로 찾게 될 대부분의 QueryDSL 자료는 이 코스와 의존성 좌표가 다릅니다.**

원본 프로젝트 `com.querydsl` 은 **2021년 7월 5.0.0 이 마지막 릴리스**였고,
**2022년 10월을 끝으로 커밋이 멈춰** 사실상 유지보수가 중단됐습니다.
그 사이 자바 생태계는 `javax.persistence` 에서 `jakarta.persistence` 로 넘어갔고
Hibernate 는 6.x 로 올라갔지만, 5.0.0 은 그 변화를 따라가지 못했습니다.

이 공백을 **OpenFeign 조직의 포크**가 이어받았습니다.
좌표는 `io.github.openfeign.querydsl` 이며, 이 포크가 **6.x 를 냈습니다**.
Spring Data JPA 진영에서도 이 포크로의 전환을 공개적으로 논의했습니다
(spring-projects/spring-data-jpa **#3335**).

**6.0 의 핵심은 Hibernate 6.4 완전 통합입니다.**
5.x 는 Hibernate 6 위에서 그냥 깨졌습니다. `ByteType` 처럼 QueryDSL 이 참조하던 타입 클래스들이
hibernate-core 6.0 에서 제거됐기 때문입니다 (querydsl/querydsl **#3436**, **#3439**).
런타임에 `NoClassDefFoundError` 가 터지는 형태라, 컴파일만 보고는 알 수 없습니다.

### 좌표 비교

| 항목 | 5.x (원본, 중단) | 6.x (OpenFeign 포크, 이 코스) |
|---|---|---|
| groupId | `com.querydsl` | `io.github.openfeign.querydsl` |
| 최신 버전 | 5.0.0 (2021-07) | 6.12 (2025-06-09) |
| jpa 아티팩트 | `querydsl-jpa:5.0.0:jakarta` | `querydsl-jpa:6.12` — **classifier 없음** |
| apt 아티팩트 | `querydsl-apt:5.0.0:jakarta` | `querydsl-apt:6.12:jpa` — **`:jpa` classifier** |
| 퍼시스턴스 API | javax 기본, jakarta 는 classifier | **jakarta 네이티브** |
| Hibernate 6 | 미지원 (런타임 깨짐) | 6.4 통합 |

```groovy
// 이 코스가 쓰는 좌표
implementation 'io.github.openfeign.querydsl:querydsl-jpa:6.12'
implementation 'io.github.openfeign.querydsl:querydsl-core:6.12'
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
```

> ⚠️ **함정 — 5.x 블로그 글을 그대로 복사하는 것**
> 5.x 는 `querydsl-jpa` 와 `querydsl-apt` **양쪽 모두** `:jakarta` classifier 를 붙였습니다.
> 6.x 는 `querydsl-jpa` 에는 classifier 가 **없고**, `querydsl-apt` 에만 `:jpa` 가 붙습니다.
> 5.x 설정을 그대로 옮기면 빌드는 통과하고 애플리케이션 기동 중에
> `java.lang.NoClassDefFoundError: javax/persistence/Entity` 로 죽습니다.
> 좌표 문제는 [실습 프로젝트 셋업](project/) 의 `0-2` 절에서 표로 다시 대조합니다.

---

## 시작하기 (5분)

```bash
# 1. MySQL 8 컨테이너 기동 (mysql8 코스의 docker 디렉터리를 그대로 씁니다)
cd docs/reference/mysql8/docker
docker compose up -d

# 2. shop 스키마 + 데이터 적재 (~5초)
cd ../sql
./install.sh

# 3. 적재 확인
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop \
  -e "SELECT COUNT(*) AS customers FROM customers;"
```

**결과**
```
+-----------+
| customers |
+-----------+
|        30 |
+-----------+
```

```bash
# 4. 실습 프로젝트에서 Q타입 생성 확인
cd ~/querydsl6-shop
./gradlew clean compileJava
find build/generated -name 'Q*.java' | sort
```

**결과**
```
BUILD SUCCESSFUL in 6s

build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QCategory.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QCustomer.java
...
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QReview.java
```

`Q*.java` 8개가 나오면 준비 완료입니다.
프로젝트를 아직 만들지 않았다면 [실습 프로젝트 셋업](project/) 을 먼저 진행하십시오.
`build.gradle` 전문, 엔티티 8개 전체 코드, `application.yml`, 문제 해결표가 거기에 있습니다.

컨테이너가 꼬이면 `docker compose down -v && docker compose up -d` 후 `./install.sh` 를
다시 실행합니다. 완전한 초기 상태로 돌아갑니다.

---

## 커리큘럼

### 1부 — 기초 (Step 01~03)
> QueryDSL 을 한 줄도 안 써봤어도 됩니다. Q타입이 무엇인지부터 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 환경 구축과 첫 쿼리 | `JPAQueryFactory` 빈 등록, SQL 로그 켜기, **바인딩 파라미터까지 보이게 하기** |
| [02](step-02-qtype/) | Q타입의 정체 | APT 가 만드는 코드 읽기, `PathBuilder`, **`clean` 후 Q타입이 사라지는 이유** |
| [03](step-03-basic-query/) | 기본 조회 | `selectFrom`, `fetch/fetchOne/fetchFirst`, **`fetchOne` 의 `NonUniqueResultException`** |

### 2부 — 조건과 조회 (Step 04~06)
> 여기서 SQL 과 QueryDSL 이 어긋나기 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [04](step-04-where-conditions/) | 조건과 동적 쿼리 | `BooleanExpression`, `where(a, b)` 의 AND 조립, **`or` 괄호 소실**, NULL 조건 무시 |
| [05](step-05-projections/) | 프로젝션과 DTO | `Projections.bean/fields/constructor`, `@QueryProjection`, **필드명 불일치가 조용히 null** |
| [06](step-06-joins/) | 조인 | `join/leftJoin/on`, `fetchJoin`, **fetch join + 페이징 = 전건 메모리 로딩** |

### 3부 — 심화 쿼리 (Step 07~10)
> MySQL8 코스에서 SQL 로 배웠던 것들을 QueryDSL 로 다시 씁니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [07](step-07-subqueries/) | 서브쿼리 | `JPAExpressions`, 스칼라/`in`/`exists`, **JPA 는 FROM 절 서브쿼리를 지원하지 않음** |
| [08](step-08-aggregation/) | 집계와 그룹핑 | `groupBy/having`, `Tuple` 읽기, **`count()` 가 세는 것은 행이지 엔티티가 아님** |
| [09](step-09-sorting-paging/) | 정렬과 페이징 | `orderBy/offset/limit`, `fetchCount` 폐기 대응, **정렬 컬럼에 함수를 씌워 인덱스 죽이기** |
| [10](step-10-dynamic-sort/) | 동적 정렬과 검색 조건 조립 | `OrderSpecifier` 조립, `BooleanBuilder` vs 메서드 분리, **정렬 키 화이트리스트** |

### 4부 — 실전 (Step 11~14)
> 운영에서 실제로 사고가 나는 지점들입니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [11](step-11-bulk-operations/) | 벌크 연산 | `update/delete` 배치, **영속성 컨텍스트를 건너뛰어 옛 값이 그대로 읽히는 문제** |
| [12](step-12-spring-data/) | Spring Data JPA 통합 | 커스텀 리포지토리, `Pageable`, **`Impl` 접미사 오타 하나로 빈 등록 실패** |
| [13](step-13-advanced/) | 고급 표현식 | `CaseBuilder`, `Expressions`, `stringTemplate`, **문자열 연결로 만드는 SQL 인젝션** |
| [14](step-14-performance/) | 성능과 최종 프로젝트 | N+1 실측, `default_batch_fetch_size`, 커버링 인덱스 + `EXPLAIN` 으로 마무리 |

---

## 각 스텝의 구성

```
step-06-joins/
├── index.md        ← 교재 본문. 개념 + QueryDSL 코드 + 생성 SQL + 결과 + 함정
├── Practice.java   ← 본문의 모든 예제를 절 번호 주석과 함께 담은 테스트 클래스
├── Exercise.java   ← 연습문제 6문제 (문제만)
└── Solution.java   ← 정답 + 왜 그 답인지 설명하는 주석
```

실습 파일은 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이고
패키지는 `com.example.shop.stepNN` 입니다.
프로젝트의 `src/test/java/com/example/shop/step06/` 에 그대로 복사해 넣으면 실행됩니다.

**권장 학습 방법**

1. `index.md` 를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마십시오.
2. 콘솔에 찍힌 SQL 을 **교재의 SQL 과 한 글자씩 비교하십시오.** 이 코스에서 가장 중요한 단계입니다.
3. `Exercise.java` 를 풀고 `Solution.java` 로 채점합니다. 답이 같아도 **생성 SQL 이 다르면 틀린 것**입니다.
4. 다음 스텝으로.

---

## SQL 과 QueryDSL 을 나란히

이 코스의 차별점입니다. `shop` 스키마는 [MySQL 8 코스](../mysql8/)와 완전히 동일합니다.
같은 데이터, 같은 행 수, 같은 함정이 심어져 있습니다.
따라서 **"MySQL8 코스에서 SQL 로 이렇게 썼던 것을 QueryDSL 로는 이렇게 쓴다"** 를
같은 결과를 놓고 직접 대조할 수 있습니다.

| QueryDSL 6 | 대응하는 MySQL 8 스텝 | 나란히 볼 지점 |
|---|---|---|
| [Step 04](step-04-where-conditions/) 조건 | [Step 05 — 연산자와 조건](../mysql8/step-05-where-operators/) | `BETWEEN/IN/LIKE`, NULL 3값 논리 |
| [Step 06](step-06-joins/) 조인 | [Step 07 — JOIN](../mysql8/step-07-joins/) | `ON` vs `WHERE`, 안티 조인, 그리고 fetch join 이라는 JPA 고유 개념 |
| [Step 07](step-07-subqueries/) 서브쿼리 | [Step 08 — 서브쿼리](../mysql8/step-08-subqueries/) | `IN` vs `EXISTS`, `NOT IN` + NULL, JPA 의 FROM 절 제약 |
| [Step 08](step-08-aggregation/) 집계 | [Step 06 — 집계와 GROUP BY](../mysql8/step-06-aggregate-groupby/) | `HAVING`, `ONLY_FULL_GROUP_BY`, 조인 후 `count` 뻥튀기 |
| [Step 09](step-09-sorting-paging/) 페이징 | [Step 15 — 인덱스](../mysql8/step-15-indexes/) | `LIMIT/OFFSET` 의 비용, 정렬 컬럼과 인덱스 |
| [Step 13](step-13-advanced/) 고급 표현식 | [Step 12 — 내장 함수](../mysql8/step-12-builtin-functions/) | `CASE`, 문자열/날짜 함수, 함수가 인덱스를 죽이는 문제 |
| [Step 14](step-14-performance/) 성능 | [Step 16 — EXPLAIN과 옵티마이저](../mysql8/step-16-explain-optimizer/) | 생성된 SQL 을 그대로 `EXPLAIN` 에 넣어 읽기 |

각 스텝 본문에는 `📌 MySQL8 코스 [Step 07 — 조인](../mysql8/step-07-joins/) 에서 이렇게 썼던 SQL 입니다.`
형태의 상호 참조 블록이 붙어 있습니다.
SQL 을 이미 아는 사람은 이 대조만 따라가도 절반은 끝납니다.
SQL 을 모르는 사람은 QueryDSL 코드와 생성 SQL 을 함께 보며 SQL 을 같이 배우게 됩니다.

---

## 실습 도메인 `shop`

가상의 온라인 쇼핑몰입니다. 아래는 **JPA 엔티티 기준**의 관계도입니다.

```
                     ┌──────────────┐
                     │   Category   │◄─┐ @ManyToOne parent (대분류 ─ 소분류)
                     └──────┬───────┘  │
                            │          └──┘
                            │ @OneToMany products
   ┌────────────┐    ┌──────▼───────┐    ┌──────────────┐
   │  Customer  │    │   Product    │    │   Employee   │◄─┐ @ManyToOne manager
   └─────┬──────┘    └──────┬───────┘    └──────────────┘  │ (4단계 조직도)
         │                  │                              └──┘
         │ @OneToMany       │ @OneToMany reviews
   ┌─────▼──────┐    ┌──────▼───────┐
   │   Order    │───►│  OrderItem   │
   └─────┬──────┘    └──────────────┘
         │ @OneToMany payments
   ┌─────▼──────┐         ┌──────────────┐
   │  Payment   │         │    Review    │──► Product, Customer
   └────────────┘         └──────────────┘
```

**모든 연관은 `FetchType.LAZY` 입니다.** 이 코스에서 이건 타협 대상이 아닙니다.
`EAGER` 는 어떤 SQL 이 언제 나가는지를 예측 불가능하게 만듭니다.

| 엔티티 | 테이블 | 행 수 | 비고 |
|---|---|---:|---|
| `Category` | `categories` | 17 | 자기참조 계층 (대분류 5 + 소분류 12) |
| `Customer` | `customers` | 30 | VIP 4 / GOLD 9 / SILVER 8 / BRONZE 9. **`phone` NULL 3명** |
| `Product` | `products` | 40 | **후기 없는 상품 24개** |
| `Order` | `orders` | 600 | 2024-01-02 ~ 2025-12-30 |
| `OrderItem` | `order_items` | 1,200 | 주문당 1~3개 |
| `Payment` | `payments` | 540 | **PENDING 주문 60건에는 결제가 없음** |
| `Review` | `reviews` | 80 | `rating` 1~5 |
| `Employee` | `employees` | 18 | 자기참조 조직도 |
| (매핑 안 함) | `access_logs` | 1,000,000 | 엔티티로 매핑하지 않습니다. [Step 14](step-14-performance/) 에서만 언급 |

시드 스크립트는 `RAND()` 를 쓰지 않고 나머지 연산(`%`)으로 값을 만듭니다.
그래서 누가 몇 번을 실행하든 **완전히 동일한 데이터**가 나옵니다.
교재에 "VIP 고객 4명" 이라고 쓰여 있으면 여러분 화면에서도 4명입니다.

NULL, 빈 관계, 편향된 분포는 **의도적으로 심어 둔 함정**입니다.
전화번호가 NULL 인 고객 3명과 결제가 없는 주문 60건은
`isNull()` / `leftJoin` / `exists` 를 배울 때 그대로 재료가 됩니다.

---

## 이 코스가 특히 신경 쓴 것

QueryDSL 은 타입 안전합니다. 오타를 컴파일러가 잡아 줍니다.
그래서 오히려 **컴파일도 되고 예외도 안 나는데 의도와 다른 SQL 이 나가는** 코드가 남습니다.
문법 에러는 IDE 가 잡지만, 이런 건 운영에서 잡힙니다.

- `fetchJoin()` 에 `offset/limit` 을 같이 쓰면 **전건을 메모리로 읽어 페이징합니다.**
  경고 로그 한 줄만 찍히고 결과는 맞게 나오므로 데이터가 커질 때까지 아무도 모릅니다 ([Step 06](step-06-joins/))
- 벌크 `update` 는 영속성 컨텍스트를 건너뜁니다.
  UPDATE 는 정상 수행됐는데 **바로 뒤에서 조회하면 옛 값이 그대로 나옵니다** ([Step 11](step-11-bulk-operations/))
- `Projections.fields()` 는 필드명이 다르면 **예외 없이 그 필드만 null 로 둡니다.**
  DTO 필드명 오타 하나가 조용히 통과합니다 ([Step 05](step-05-projections/))
- `.or()` 를 이어 붙이면 괄호가 사라져 `A AND B OR C` 가 되어 버립니다.
  의도한 `A AND (B OR C)` 와 결과가 다른데 에러는 안 납니다 ([Step 04](step-04-where-conditions/))
- 커스텀 리포지토리 구현체 이름에서 **`Impl` 접미사를 한 글자만 틀려도** 빈이 등록되지 않습니다.
  에러 메시지는 엉뚱한 곳을 가리킵니다 ([Step 12](step-12-spring-data/))
- 정렬 컬럼을 `substring()`, `lower()` 로 감싸면 **인덱스를 못 탑니다.**
  30건일 땐 안 보이고 60만 건일 때 보입니다 ([Step 09](step-09-sorting-paging/))
- 조회 결과를 반복하며 연관을 건드리면 **쿼리가 1 + N 번 나갑니다.**
  결과는 완벽하게 맞습니다. 느릴 뿐입니다 ([Step 14](step-14-performance/))
- `stringTemplate` 에 사용자 입력을 문자열로 이어 붙이면 **SQL 인젝션이 그대로 열립니다.**
  QueryDSL 을 쓴다는 사실이 방어해 주지 않습니다 ([Step 13](step-13-advanced/))

각 스텝의 함정은 **잘못된 코드 → 그것이 만든 SQL → 왜 틀렸는지 → 고친 코드 → 고친 SQL**
순서로 전개됩니다. `⚠️ 함정` 블록을 특히 눈여겨 보십시오.

---

## 7.x 는 어떤가

6.x 의 마지막 릴리스는 **6.12 (2025-06-09)** 이고, 그 뒤로 **7.x 가 나왔습니다 (7.2, 2026-05)**.
이 코스는 **6.12 기준**으로 작성했고 모든 예제를 6.12 에서 확인했습니다.
7.x 로 옮길 때 확인할 것은 두 가지입니다.
첫째, **groupId 는 그대로 `io.github.openfeign.querydsl`** 입니다.
5.x → 6.x 때처럼 좌표 자체가 바뀌는 종류의 이동은 아닙니다.
둘째, **메이저 버전 승격이므로 breaking change 가 있을 수 있습니다.**
어떤 API 가 어떻게 바뀌었는지는 이 코스가 단정하지 않습니다. 릴리스 노트와
마이그레이션 가이드를 직접 확인하고, 프로젝트의 테스트로 검증하십시오.
이 코스에서 배우는 것 — 생성 SQL 을 읽고 검증하는 습관 — 은 버전과 무관하게 그대로 쓰입니다.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| QueryDSL | `io.github.openfeign.querydsl` **6.12** |
| Spring Boot | 3.2.5 |
| Hibernate | 6.4.x (Spring Boot 3.2.5 관리 버전) |
| Java | 21 |
| 빌드 | Gradle (Groovy DSL) |
| MySQL | 8.0 (검증: 8.0.46) |
| 접속 | `127.0.0.1:3307` |
| 계정 | `learner` / `learn1234` (관리용: `root` / `root1234`) |
| DB | `shop` (문자셋 `utf8mb4` / `utf8mb4_0900_ai_ci`) |
| 타임존 | `Asia/Seoul (+09:00)` |
| 실습 프로젝트 | [`project/`](project/) — `build.gradle`, 엔티티 8개, `application.yml` 전문 |

> 💡 **실무 팁 — SQL 로그를 끄지 마십시오**
> `org.hibernate.SQL: debug` 와 `org.hibernate.orm.jdbc.bind: trace` 는 이 코스 내내 켜 둡니다.
> 로그가 시끄럽다고 끄는 순간 이 코스의 학습 방식이 무너집니다. 운영에서 끄는 건 별개입니다.

---

준비가 됐다면 [실습 프로젝트 셋업](project/) 부터 시작하십시오. 프로젝트가 이미 있다면
바로 [Step 01 — 환경 구축과 첫 쿼리](step-01-setup/) 로 갑니다.
