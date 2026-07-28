# Step 01 — 환경 구축과 첫 쿼리

> **학습 목표**
> - 문자열 JPQL 의 오타가 **컴파일을 통과해 런타임에 터지는** 과정을 직접 재현하고, QueryDSL 이 그것을 컴파일 에러로 바꾸는 것을 확인한다
> - QueryDSL **5.x(`com.querydsl`)와 6.x(`io.github.openfeign.querydsl`)의 의존성 좌표 차이**를 표로 구별하고, 잘못 쓴 3가지 케이스의 에러 메시지를 각각 읽어낸다
> - 원본 QueryDSL 이 중단되고 OpenFeign 포크가 6.x 를 낸 히스토리와, Hibernate 6 에서 5.x 가 깨진 실제 원인을 설명한다
> - `JPAQueryFactory` 를 스프링 빈으로 등록하고, 그것이 왜 싱글턴으로 안전한지 `EntityManager` 프록시의 동작으로 설명한다
> - `selectFrom` 으로 첫 쿼리를 실행하고, `hibernate.SQL` 로그로 **생성 SQL 과 바인딩 파라미터**를 눈으로 확인한다
> - QueryDSL 이 만드는 것이 **SQL 이 아니라 JPQL** 임을 `.toString()` 으로 실측하고, 2단계 변환 구조를 그린다
>
> **선행 스텝**: [실습 프로젝트 셋업](../project/)
> **예상 소요**: 90분

---

## 1-1. QueryDSL 이란 — 오타가 런타임에야 터진다

JPA 로 조회를 짜는 방법은 크게 셋입니다.

| 방법 | 타입 안전 | 동적 쿼리 | 가독성 |
|---|---|---|---|
| 문자열 JPQL (`em.createQuery("select ...")`) | ✗ | ✗ (문자열 이어붙이기) | 보통 |
| Criteria API (`CriteriaBuilder`) | ○ | ○ | **최악** |
| **QueryDSL** | ○ | ○ | 좋음 |

문자열 JPQL 의 진짜 문제는 "장황하다"가 아닙니다. **틀린 코드가 빌드를 통과한다**는 것입니다.

### 오타를 하나 내 봅니다

```java
// selectFrom 이 아니라 selct — 알파벳 하나가 빠졌습니다
String jpql = "selct c from Customer c where c.grade = :grade";

List<Customer> result = em.createQuery(jpql, Customer.class)
        .setParameter("grade", Grade.VIP)
        .getResultList();
```

```bash
./gradlew compileJava
```

**결과**
```
> Task :compileJava
BUILD SUCCESSFUL in 1s
2 actionable tasks: 2 executed
```

**컴파일이 성공합니다.** 자바 컴파일러 입장에서 `jpql` 은 그냥 `String` 이고, `String` 에 오타라는 개념은 없습니다. 이 코드는 그대로 커밋되고, 리뷰를 통과하고, 배포됩니다. 그리고 **그 코드 경로를 처음 밟는 사용자**가 이걸 만납니다.

```java
// 테스트를 실행하면
@Test
void 오타난_JPQL() {
    String jpql = "selct c from Customer c where c.grade = :grade";
    em.createQuery(jpql, Customer.class).setParameter("grade", Grade.VIP).getResultList();
}
```

**결과**
```
jakarta.persistence.PersistenceException: org.hibernate.query.SyntaxException:
  At 1:0 and token 'selct', mismatched input 'selct' expecting {'delete', 'insert', 'select', 'update', 'with'}
  [selct c from Customer c where c.grade = :grade]

	at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:158)
	at org.hibernate.internal.AbstractSharedSessionContract.createQuery(AbstractSharedSessionContract.java:829)
	at com.example.shop.step01.Practice.오타난_JPQL(Practice.java:57)
Caused by: org.hibernate.query.SyntaxException: At 1:0 and token 'selct', mismatched input 'selct'
	at org.hibernate.query.hql.internal.StandardHqlTranslator.translate(StandardHqlTranslator.java:78)
```

> 💡 Hibernate 5 에서는 이 예외가 `org.hibernate.hql.internal.ast.QuerySyntaxException` 이었습니다.
> Hibernate 6 에서 HQL 파서가 ANTLR 기반으로 새로 작성되면서 `org.hibernate.query.SyntaxException` 으로 바뀌었습니다.
> 인터넷의 오래된 글에서 `QuerySyntaxException` 을 검색하고 있다면 이 차이 때문입니다.

오타뿐이 아닙니다. 문자열 JPQL 에서 런타임에야 터지는 것들:

| 실수 | 언제 발견되나 |
|---|---|
| 키워드 오타 (`selct`, `wher`) | 쿼리 실행 시 |
| 엔티티 이름 오타 (`Custmer`) | 쿼리 실행 시 |
| 필드 이름 오타 (`c.grde`) | 쿼리 실행 시 |
| 필드 타입 불일치 (`c.points = 'VIP'`) | 쿼리 실행 시 |
| 파라미터 이름 불일치 (`:grade` vs `setParameter("grad", ...)`) | 쿼리 실행 시 |
| 엔티티 필드명을 리팩터링으로 바꿈 | **아무도 모름** — 문자열은 IDE 리네임에 안 따라옵니다 |

마지막 줄이 가장 무섭습니다. `Customer.grade` 를 `Customer.memberGrade` 로 바꾸면 IDE 가 자바 코드는 전부 고쳐 주지만, 문자열 안의 `c.grade` 는 그대로 남습니다.

### 같은 실수를 QueryDSL 로

```java
List<Customer> result = queryFactory
        .selctFrom(customer)                       // 오타
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

```bash
./gradlew compileJava
```

**결과**
```
> Task :compileJava FAILED

/src/test/java/com/example/shop/step01/Practice.java:64: error: cannot find symbol
                .selctFrom(customer)
                ^
  symbol:   method selctFrom(QCustomer)
  location: variable queryFactory of type JPAQueryFactory

1 error
BUILD FAILED in 2s
```

필드명 오타도 마찬가지입니다.

```java
.where(customer.grde.eq(Grade.VIP))   // grade 가 아니라 grde
```
```
error: cannot find symbol
                .where(customer.grde.eq(Grade.VIP))
                               ^
  symbol:   variable grde
  location: variable customer of type QCustomer
```

타입 불일치도 잡힙니다.

```java
.where(customer.points.eq("VIP"))     // points 는 Integer 인데 String 을 넣음
```
```
error: incompatible types: String cannot be converted to Integer
                .where(customer.points.eq("VIP"))
                                          ^
```

**이것이 QueryDSL 의 전부입니다.** 쿼리를 문자열이 아니라 **자바 객체 그래프**로 조립하기 때문에, 쿼리에 대한 검증이 자바 컴파일러의 일이 됩니다.

> 💡 **실무 팁 — "컴파일 타임에 잡힌다"가 주는 진짜 이득은 리팩터링이다**
> 오타는 사실 테스트 한 번이면 잡힙니다. QueryDSL 의 실질적 가치는 **엔티티 스키마가 바뀌었을 때 영향받는 모든 쿼리가 빌드에서 터진다**는 것입니다.
> 컬럼 하나를 지웠는데 어느 쿼리가 깨지는지 `./gradlew compileJava` 가 목록을 뽑아 줍니다. 문자열 JPQL 은 이 목록을 절대 주지 않습니다.

---

## 1-2. 의존성 함정 — 이 코스에서 가장 중요한 절

**이 절에서 시간을 아끼면 나머지 13개 스텝에서 시간을 잃습니다.** QueryDSL 도입 실패의 압도적 다수가 의존성 좌표 문제입니다.

### 좌표 3종 비교

| 항목 | `com.querydsl` 5.0.0 (기본) | `com.querydsl` 5.0.0 `:jakarta` | **`io.github.openfeign.querydsl` 6.12** |
|---|---|---|---|
| 배포 주체 | 원본 (중단) | 원본 (중단) | **OpenFeign 포크 (유지보수 중)** |
| 최종 릴리스 | 2021-07 | 2021-07 | 2025-06-09 |
| 영속성 API | `javax.persistence` | `jakarta.persistence` | **`jakarta.persistence` (네이티브)** |
| `querydsl-jpa` classifier | 없음 | `:jakarta` | **없음** |
| `querydsl-apt` classifier | 없음 | `:jakarta` | **`:jpa`** |
| Hibernate 6 | ✗ 깨짐 | ✗ 깨짐 | ○ 완전 통합 (6.4) |
| Spring Boot 3.x | ✗ | △ (Hibernate 6 이슈) | ○ |

핵심은 **classifier 규칙이 5.x 와 6.x 에서 서로 다르다**는 것입니다.

```
5.x → jpa 도 :jakarta,  apt 도 :jakarta    (둘 다 붙는다)
6.x → jpa 는 classifier 없음,  apt 만 :jpa  (한쪽만, 그것도 다른 이름)
```

이 비대칭이 함정의 근원입니다. 5.x 설정을 아는 사람일수록 "`:jakarta` 를 양쪽에 붙이면 되겠지" 하고 틀립니다.

### 정답 — 이 코스의 build.gradle

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly    'com.mysql:mysql-connector-j'

    // ── QueryDSL 6.12 (io.github.openfeign.querydsl) ──────────────────
    implementation      'io.github.openfeign.querydsl:querydsl-jpa:6.12'      // classifier 없음
    implementation      'io.github.openfeign.querydsl:querydsl-core:6.12'
    annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'  // :jpa classifier
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
}
```

```bash
./gradlew dependencies --configuration compileClasspath | grep querydsl
```

**결과**
```
+--- io.github.openfeign.querydsl:querydsl-jpa:6.12
|    \--- io.github.openfeign.querydsl:querydsl-core:6.12
|         +--- com.google.code.findbugs:jsr305:3.0.2
|         \--- com.querydsl:querydsl-codegen-utils:6.12
\--- io.github.openfeign.querydsl:querydsl-core:6.12 (*)
```

### 케이스 (a) — 6.12 에 `:jakarta` classifier 를 붙였다

5.x 습관 그대로 옮긴 가장 흔한 실수입니다.

```groovy
implementation 'io.github.openfeign.querydsl:querydsl-jpa:6.12:jakarta'   // ✗
```

```bash
./gradlew compileJava
```

**결과**
```
> Task :compileJava FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':compileJava'.
> Could not resolve all files for configuration ':compileClasspath'.
   > Could not find querydsl-jpa-6.12-jakarta.jar (io.github.openfeign.querydsl:querydsl-jpa:6.12).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/io/github/openfeign/querydsl/querydsl-jpa/6.12/querydsl-jpa-6.12-jakarta.jar
```

**증상이 명확한 편이라 오히려 다행인 케이스입니다.** "그런 아티팩트가 없다"고 말해 주니까요. 6.x 는 애초에 Jakarta 가 본체라서 `jakarta` classifier 아티팩트를 발행하지 않습니다. 붙일 게 없습니다.

### 케이스 (b) — apt 에 `:jakarta` 를 붙였다 (또는 classifier 를 안 붙였다)

```groovy
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jakarta'   // ✗ 존재하지 않음
// 또는
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12'           // ✗ 프로세서가 안 붙음
```

두 번째가 훨씬 고약합니다. `querydsl-apt:6.12` 는 **실제로 존재하는 아티팩트**라 의존성 해결이 성공합니다. 그런데 이 기본 아티팩트에는 JPA 용 프로세서를 등록하는 `META-INF/services/javax.annotation.processing.Processor` 항목이 없습니다. 즉 **APT 가 조용히 아무것도 하지 않습니다.**

```bash
./gradlew clean compileJava
```

**결과**
```
> Task :compileJava
BUILD SUCCESSFUL in 3s
```

빌드가 성공합니다. 그런데:

```bash
find build/generated -name 'Q*.java'
```

**결과**
```
(출력 없음)
```

Q타입이 하나도 없습니다. 그 상태로 `QCustomer.customer` 를 쓰는 코드를 컴파일하면:

```
error: package com.example.shop.entity does not exist
import static com.example.shop.entity.QCustomer.customer;
                                     ^
error: cannot find symbol
                .selectFrom(customer)
                            ^
  symbol:   variable customer
```

> ⚠️ **함정 — "Q타입을 못 찾는다"는 에러를 IDE 문제로 오해한다**
> `cannot find symbol: QCustomer` 를 보면 대부분 IDE 를 재시작하거나 Invalidate Caches 를 누릅니다. 그래도 안 되면 Gradle refresh 를 합니다.
> **원인이 IDE 가 아니라 `annotationProcessor` 좌표인 경우가 절반 이상입니다.** 순서를 뒤집으세요:
> ① `./gradlew clean compileJava` → ② `find build/generated -name 'Q*.java'` → ③ 파일이 없으면 **IDE 를 만지지 말고 build.gradle 을 보세요.**
> 이 진단 절차는 [Step 02 — Q타입의 정체](../step-02-qtype/) 에서 5가지 원인으로 세분해 다룹니다.

또 하나의 변종은 **6.x jpa 라이브러리에 5.x apt 를 섞는** 경우입니다.

```groovy
implementation      'io.github.openfeign.querydsl:querydsl-jpa:6.12'
annotationProcessor 'com.querydsl:querydsl-apt:5.0.0:jpa'      // ✗ 5.x apt
```

이러면 Q타입은 생성되지만 **`javax.persistence` 를 기준으로 엔티티를 스캔**합니다. 우리 엔티티는 `jakarta.persistence.@Entity` 를 쓰므로 프로세서 눈에는 엔티티가 하나도 없고, 결과는 (b) 와 같이 Q타입 0개입니다. 또는 부분 생성된 Q타입이 `javax.persistence` 를 참조해 이런 에러가 납니다.

```
error: cannot access javax.persistence.Entity
  class file for javax.persistence.Entity not found
```

### 케이스 (c) — 5.0.0 을 classifier 없이 썼다

Spring Boot 3 프로젝트에 오래된 블로그 설정을 그대로 붙였을 때입니다.

```groovy
implementation      'com.querydsl:querydsl-jpa:5.0.0'      // ✗ javax 버전
annotationProcessor 'com.querydsl:querydsl-apt:5.0.0'      // ✗ javax 버전
```

컴파일은 통과할 수도 있습니다. 터지는 것은 **런타임**입니다.

**결과**
```
java.lang.NoClassDefFoundError: javax/persistence/Entity
	at com.querydsl.jpa.impl.JPAQuery.<init>(JPAQuery.java:57)
	at com.querydsl.jpa.impl.JPAQueryFactory.selectFrom(JPAQueryFactory.java:135)
	at com.example.shop.step01.Practice.첫쿼리(Practice.java:71)
Caused by: java.lang.ClassNotFoundException: javax.persistence.Entity
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
	at java.base/jdk.internal.loader.ClassLoaders$AppClassLoader.loadClass(ClassLoaders.java:188)
	... 42 more
```

Spring Boot 3 / Jakarta EE 9+ 환경에는 `javax.persistence` 패키지 자체가 없습니다. Java EE 가 Eclipse 재단으로 이관되면서 **패키지 네임스페이스가 `javax.*` → `jakarta.*` 로 통째로 바뀌었기 때문**입니다. 이름만 바뀐 게 아니라 다른 패키지이므로, `javax.persistence.Entity` 를 참조하는 5.0.0 기본 아티팩트는 클래스로더가 찾을 수 없습니다.

### 세 케이스 요약

| 케이스 | 잘못된 좌표 | 증상 | 발견 시점 |
|---|---|---|---|
| (a) | `querydsl-jpa:6.12:jakarta` | `Could not find querydsl-jpa-6.12-jakarta.jar` | 빌드 (의존성 해결) |
| (b) | `querydsl-apt:6.12` (classifier 누락) | 빌드 성공 + **Q타입 0개** → `cannot find symbol` | 빌드 (뒤늦게) |
| (b') | `querydsl-apt:5.0.0:jpa` 혼용 | `cannot access javax.persistence.Entity` 또는 Q타입 0개 | 빌드 |
| (c) | `querydsl-jpa:5.0.0` (classifier 없음) | `NoClassDefFoundError: javax/persistence/Entity` | **런타임** |

(c) 가 가장 위험합니다. **빌드가 초록불인데 서버가 첫 쿼리에서 죽습니다.**

### 왜 이렇게 갈렸는가

원본 QueryDSL 5.0.0 은 **`javax` 가 본체이고 `jakarta` 가 부록**인 구조였습니다. 기존 사용자를 깨지 않으면서 Jakarta 전환기를 버티려고, 소스를 기계적으로 변환한 `-jakarta` classifier 아티팩트를 곁들여 냈습니다.

OpenFeign 포크 6.x 는 그 전환기가 끝난 뒤에 출발했습니다. **Jakarta 를 유일한 본체로 삼았고, `javax` 지원을 아예 버렸습니다.** 그래서 `querydsl-jpa` 에는 붙일 classifier 가 없습니다 — 변종이 하나뿐이니까요.

그럼 `querydsl-apt` 의 `:jpa` 는 무엇인가? 이건 Jakarta 여부와 **무관한 축**입니다. `querydsl-apt` 는 원래부터 여러 프로세서를 담고 있고(JPA용, JDO용, 순수 `@QueryEntity`용), classifier 로 "어느 프로세서를 서비스 등록할 것인가"를 고릅니다. JPA 엔티티를 스캔하려면 `:jpa` 를 골라야 합니다.

```
querydsl-jpa   의 classifier 축 = "javax냐 jakarta냐"  → 6.x 는 jakarta 하나뿐 → classifier 소멸
querydsl-apt   의 classifier 축 = "어느 프로세서냐"     → 6.x 도 여전히 필요  → :jpa 유지
```

두 축이 다르다는 것을 알면 비대칭이 자연스럽게 읽힙니다.

> 💡 **실무 팁 — 좌표를 의심할 때 가장 빠른 확인법**
> ```bash
> ./gradlew dependencies --configuration annotationProcessor | grep querydsl
> ```
> `querydsl-apt:6.12` 뒤에 아무 표시가 없으면 classifier 가 빠진 것입니다. 정상이라면 아래처럼 나옵니다.
> ```
> \--- io.github.openfeign.querydsl:querydsl-apt:6.12
> ```
> Gradle 은 `dependencies` 출력에 classifier 를 표시하지 않으므로, 확실히 하려면 실제 jar 파일명을 봅니다.
> ```bash
> ./gradlew -q dependencies --configuration annotationProcessor > /dev/null
> find ~/.gradle/caches/modules-2 -name 'querydsl-apt-6.12*.jar'
> ```
> ```
> .../querydsl-apt-6.12-jpa.jar     ← -jpa 가 붙어 있어야 정상
> ```

---

## 1-3. 왜 포크인가 — 원본 중단과 Hibernate 6

QueryDSL 6 을 쓰려면 "왜 그룹 ID 가 `io.github.openfeign` 인가"에 답할 수 있어야 합니다. 팀에 도입할 때 반드시 받는 질문이기도 합니다.

### 타임라인

| 시점 | 사건 |
|---|---|
| 2021-07 | 원본 `com.querydsl` **5.0.0 릴리스** — 결과적으로 마지막 정식 릴리스 |
| 2022-06 | Hibernate 6.0 GA. `ByteType` 등 구 타입 API 대거 제거 |
| 2022-10 | 원본 저장소의 **사실상 마지막 커밋** |
| 2022-11 | Spring Boot 3.0 GA — Jakarta EE 9+ 전면 전환 |
| 2023 | OpenFeign 팀이 포크를 이어받아 `io.github.openfeign.querydsl` 로 배포 시작 |
| 2024 | 6.x 계열 — **Hibernate 6.4 완전 통합** |
| 2024-11 | Spring Data JPA 가 포크 전환을 논의 (spring-projects/spring-data-jpa#3335) |
| 2025-06-09 | **6.12 릴리스** (이 코스의 기준 버전) |
| 2026-05 | 7.x 계열 (7.2) |

### 5.x 가 Hibernate 6 에서 실제로 어떻게 깨졌는가

Spring Boot 3.x 는 Hibernate 6 을 씁니다. 그런데 QueryDSL 5.0.0 의 `querydsl-jpa` 는 Hibernate 5 의 내부 타입 API 를 직접 참조합니다. 대표적으로 `HibernateHandler` / `HibernateQuery` 계열이 `org.hibernate.type.ByteType` 같은 **구 `Type` 계층**을 임포트합니다.

Hibernate 6.0 은 타입 시스템을 `BasicType`/`JavaType`/`JdbcType` 조합으로 재설계하면서 `ByteType`, `StringType`, `LongType` 같은 싱글턴 상수 클래스들을 **삭제**했습니다. 결과는 이렇습니다.

**결과**
```
java.lang.NoClassDefFoundError: org/hibernate/type/ByteType
	at com.querydsl.jpa.HibernateHandler.<clinit>(HibernateHandler.java:41)
	at com.querydsl.jpa.impl.AbstractJPAQuery.createQuery(AbstractJPAQuery.java:180)
	at com.querydsl.jpa.impl.AbstractJPAQuery.fetch(AbstractJPAQuery.java:216)
Caused by: java.lang.ClassNotFoundException: org.hibernate.type.ByteType
```

관련 이슈:
- `querydsl/querydsl#3436` — Hibernate 6 호환성 요청
- `querydsl/querydsl#3439` — 같은 맥락의 후속 보고

두 이슈 모두 **원본 저장소에서 해결되지 않은 채 남았습니다.** 유지보수가 멈춰 있었기 때문입니다.

> ⚠️ **함정 — 5.0.0:jakarta 면 Spring Boot 3 에서 괜찮다는 오해**
> `:jakarta` classifier 는 **`javax` → `jakarta` 패키지 치환만** 해결합니다. Hibernate 6 의 내부 API 변경은 전혀 다른 문제입니다.
> 즉 `com.querydsl:querydsl-jpa:5.0.0:jakarta` + Spring Boot 3.2 조합은 `NoClassDefFoundError: javax/persistence/Entity` 는 피하지만,
> **쿼리를 실행하는 순간 `NoClassDefFoundError: org/hibernate/type/ByteType` 을 만날 수 있습니다.**
> 단순 조회에서는 이 코드 경로를 안 밟아 우연히 동작하기도 해서, "되는 줄 알았는데 특정 쿼리에서만 죽는" 최악의 형태로 나타납니다.
> Spring Boot 3.x 를 쓴다면 **6.x 포크가 사실상 유일한 선택**입니다.

### 포크를 써도 되는가

실무 판단에 필요한 근거는 이 정도입니다.

- 패키지 이름은 **그대로 `com.querydsl.*`** 입니다. 그룹 ID 만 다릅니다. 즉 코드는 한 줄도 안 고쳐도 됩니다.
- 5.x → 6.x 마이그레이션은 대부분 **build.gradle 만 수정**하면 끝납니다.
- Spring Data JPA 도 이 포크로의 전환을 공식 이슈로 논의했습니다 (#3335). 커뮤니티가 사실상의 후계로 인정하고 있다는 신호입니다.
- 릴리스가 계속 나오고 있습니다 (6.12 → 7.x).

> 💡 **실무 팁 — 7.x 가 있는데 왜 6.12 인가**
> 이 코스가 6.12 를 쓰는 이유는 Spring Boot 3.2 / Hibernate 6.4 조합에서 가장 검증된 버전이기 때문입니다.
> 7.x 는 더 최신 Hibernate 를 겨냥합니다. **버전을 올릴 때는 반드시 Hibernate 버전과의 짝을 먼저 확인하세요.**
> QueryDSL 은 Hibernate 내부 API 에 의존하는 부분이 있어, "최신이니까 좋겠지"로 올리면 1-3 절의 사고를 그대로 재현하게 됩니다.

---

## 1-4. `JPAQueryFactory` 빈 등록

QueryDSL 로 쿼리를 만드는 진입점은 `JPAQueryFactory` 입니다. 이것을 스프링 빈으로 한 번 등록해 두고 어디서나 주입받아 씁니다.

```java
package com.example.shop.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuerydslConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }
}
```

이게 전부입니다. 그런데 여기서 대부분의 사람이 멈칫합니다.

> `JPAQueryFactory` 는 싱글턴 빈인데, 그 안에 `EntityManager` 를 필드로 붙들고 있다. `EntityManager` 는 스레드 안전하지 않다고 배웠는데, 이래도 되나?

**됩니다.** 이유를 정확히 알아야 합니다.

### 주입되는 것은 진짜 EntityManager 가 아니다

스프링이 `jpaQueryFactory(EntityManager em)` 의 파라미터로 넣어 주는 것은 **`SharedEntityManagerCreator` 가 만든 프록시**입니다. 실제 `EntityManager` 구현체(`SessionImpl`)가 아닙니다.

```java
@Test
void 주입된_EntityManager의_정체() {
    System.out.println(em.getClass().getName());
    System.out.println(em instanceof jakarta.persistence.EntityManager);
}
```

**결과**
```
jdk.proxy2.$Proxy214
true
```

이 프록시의 동작은 이렇습니다.

```
queryFactory.selectFrom(...)  호출
        │
        ▼
 JPAQueryFactory 의 필드 em (프록시)
        │  메서드 호출을 가로챔
        ▼
 SharedEntityManagerInvocationHandler
        │  "지금 이 스레드의 트랜잭션에 바인딩된 EntityManager 를 찾아라"
        ▼
 TransactionSynchronizationManager (ThreadLocal)
        │
        ├── 스레드 A 의 트랜잭션 → SessionImpl@1a2b   ← A 는 여기로 위임
        └── 스레드 B 의 트랜잭션 → SessionImpl@3c4d   ← B 는 여기로 위임
```

즉 **싱글턴이 붙들고 있는 것은 "지금 스레드의 EntityManager 를 찾아 주는 함수"** 이지, 특정 영속성 컨텍스트가 아닙니다. 스레드마다 다른 실제 세션으로 위임되므로 안전합니다.

`@PersistenceContext` 로 필드 주입해도 동일합니다.

```java
@PersistenceContext
private EntityManager em;      // 이것도 SharedEntityManagerCreator 프록시
```

`@PersistenceContext` 는 `PersistenceAnnotationBeanPostProcessor` 가 처리하며, 그 결과 주입되는 것 역시 같은 종류의 공유 프록시입니다. **생성자 파라미터로 받든 `@PersistenceContext` 로 받든 결과물은 같습니다.**

> ⚠️ **함정 — `EntityManagerFactory.createEntityManager()` 로 직접 만든 것을 넘기면 안 된다**
> ```java
> @Bean
> public JPAQueryFactory jpaQueryFactory(EntityManagerFactory emf) {
>     return new JPAQueryFactory(emf.createEntityManager());   // ✗ 절대 금지
> }
> ```
> 이건 진짜 `EntityManager` 를 **하나 만들어서 싱글턴에 영구 고정**하는 것입니다. 프록시가 아니므로 스레드 분리가 없습니다.
> 모든 요청이 같은 영속성 컨텍스트를 공유하고, 1차 캐시가 무한히 커지고, 트랜잭션 경계와 무관하게 동작하며, 동시 요청에서 `ConcurrentModificationException` 이나 정체불명의 `LazyInitializationException` 이 튑니다.
> **개발 중에는 요청이 한 번에 하나씩 오니까 잘 돌아갑니다.** 부하가 걸리는 운영에서 처음 터집니다.

### 트랜잭션이 없으면

`JPAQueryFactory` 로 조회만 한다면 트랜잭션 없이도 동작합니다. 공유 프록시가 트랜잭션 밖에서는 요청마다 임시 `EntityManager` 를 만들고 바로 닫기 때문입니다. 다만 그 경우 **영속성 컨텍스트가 조회 직후 닫히므로** 지연 로딩이 즉시 깨집니다.

```
org.hibernate.LazyInitializationException: could not initialize proxy
  [com.example.shop.entity.Customer#7] - no Session
```

이 코스의 실습 클래스가 전부 `@Transactional` 인 이유입니다.

---

## 1-5. 첫 쿼리 — `selectFrom`

VIP 등급 고객을 조회합니다. MySQL8 코스의 `SELECT * FROM customers WHERE grade = 'VIP'` 와 같은 쿼리입니다.

> 📌 MySQL8 코스 [Step 05 — 연산자와 조건](../../mysql8/step-05-where-operators/) 에서 이렇게 썼던 조건입니다.

```java
package com.example.shop.step01;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.shop.entity.QCustomer.customer;   // ★ static import

@SpringBootTest
@Transactional
class FirstQueryTest {

    @Autowired
    JPAQueryFactory queryFactory;

    @Test
    void VIP_고객_조회() {
        List<Customer> result = queryFactory
                .selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        result.forEach(c -> System.out.println(c.getName() + " / " + c.getCity()));
    }
}
```

`import static com.example.shop.entity.QCustomer.customer;` — **이 코스의 모든 예제는 Q타입을 static import 해서 씁니다.** `QCustomer.customer.grade` 대신 `customer.grade` 로 쓰면 쿼리가 SQL 처럼 읽힙니다. 이 관례는 [Step 02](../step-02-qtype/) 에서 이름 충돌 대처까지 다룹니다.

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

김서준 / 서울
류하나 / 부산
정  훈 / 서울
배채영 / 대구
```

### 읽어야 할 것 세 가지

**① `selectFrom(customer)` 는 `select(customer).from(customer)` 의 축약입니다.** 조회 대상과 from 절이 같을 때만 씁니다. 다르면 나눠 씁니다.

```java
// 이름만 뽑을 때는 축약할 수 없습니다
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
바인딩: [1] Grade.VIP
조회 4건 — [김서준, 류하나, 정  훈, 배채영]
```

**② 별칭이 `c1_0` 입니다.** Hibernate 6 은 `<엔티티 첫 글자><인덱스>_<서브인덱스>` 규칙으로 별칭을 붙이고, `as` 키워드를 생략합니다. Hibernate 5 는 `customer0_` 형태였습니다. 인터넷 예제의 별칭 모양만 봐도 Hibernate 세대를 구별할 수 있습니다.

**③ 값이 `?` 로 나갔습니다.** QueryDSL 은 상수를 **자동으로 바인딩 파라미터**로 만듭니다. 문자열을 이어붙이지 않으므로 SQL 인젝션이 구조적으로 불가능하고, DB 가 실행계획을 재사용할 수 있습니다. 이 동작을 바꾸는 방법은 [Step 03 — 기본 조회](../step-03-basic-query/) 에서 다룹니다.

### 결과 반환 메서드

| 메서드 | 반환 | 결과가 없을 때 | 결과가 2건 이상일 때 |
|---|---|---|---|
| `fetch()` | `List<T>` | 빈 리스트 | 정상 |
| `fetchOne()` | `T` | `null` | **`NonUniqueResultException`** |
| `fetchFirst()` | `T` | `null` | 첫 건 (`limit(1)` 을 붙임) |
| `fetchCount()` | `long` | 0 | — (**deprecated**) |

```java
Customer one = queryFactory.selectFrom(customer)
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
바인딩: [1] seojun.kim@example.com
조회 1건 — 김서준
```

> ⚠️ **함정 — `fetchOne()` 은 결과가 2건이면 예외를 던진다**
> ```java
> Customer c = queryFactory.selectFrom(customer)
>         .where(customer.city.eq("서울"))     // 서울 고객은 여럿입니다
>         .fetchOne();
> ```
> ```
> com.querydsl.core.NonUniqueResultException: Only one result is allowed for fetchOne calls
> 	at com.querydsl.jpa.impl.AbstractJPAQuery.fetchOne(AbstractJPAQuery.java:242)
> ```
> **유니크 제약이 있는 컬럼(email 등)으로 조회할 때만 `fetchOne()` 을 쓰세요.**
> 조건상 1건일 것 같지만 확신이 없다면 `fetchFirst()` 를 쓰거나, `fetch()` 로 받아 크기를 확인하는 편이 안전합니다.
> "지금은 1건인데 데이터가 늘면 2건이 될 수 있는" 조건이 가장 위험합니다. 개발 DB 에서는 절대 안 터집니다.

> 💡 `fetchCount()` 와 `fetchResults()` 는 6.x 에서 deprecated 입니다. 카운트 쿼리를 직접 조립하는 방식으로 대체되었으며, [Step 09 — 정렬과 페이징](../step-09-sorting-paging/) 에서 정확한 대체 코드를 다룹니다.

---

## 1-6. SQL 로그 보는 법

**이 코스는 "생성된 SQL 을 눈으로 보는 것"을 전제로 합니다.** 로그 설정이 안 되어 있으면 나머지 스텝의 절반이 무의미해집니다. 지금 설정하고 갑니다.

### 아무 설정도 안 한 상태

```yaml
# application.yml — 비어 있음
```

```java
queryFactory.selectFrom(customer).where(customer.grade.eq(Grade.VIP)).fetch();
```

**결과**
```
(아무 로그도 나오지 않음)
```

쿼리가 나갔는지조차 알 수 없습니다.

### ① SQL 문 출력

```yaml
logging:
  level:
    org.hibernate.SQL: debug
```

**결과**
```
2026-07-22 10:11:23.441 DEBUG 41822 --- [    Test worker] org.hibernate.SQL : select c1_0.customer_id,c1_0.city,c1_0.created_at,c1_0.email,c1_0.grade,c1_0.name,c1_0.phone,c1_0.points from customers c1_0 where c1_0.grade=?
```

SQL 은 보이는데 **한 줄로 길고, `?` 에 무엇이 들어갔는지 모릅니다.**

### ② 파라미터 바인딩 값 출력

```yaml
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

**결과**
```
2026-07-22 10:11:23.441 DEBUG --- org.hibernate.SQL : select c1_0.customer_id,c1_0.city,... where c1_0.grade=?
2026-07-22 10:11:23.443 TRACE --- org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [VIP]
```

이제 `?` 의 정체가 보입니다. `(1:VARCHAR)` 는 "1번 파라미터, JDBC 타입 VARCHAR", `<- [VIP]` 가 실제 값입니다.

> ⚠️ **함정 — `org.hibernate.type.descriptor.sql.BasicBinder` 는 Hibernate 6 에서 안 먹는다**
> 검색하면 대부분 이 카테고리를 알려 줍니다.
> ```yaml
> logging.level.org.hibernate.type.descriptor.sql.BasicBinder: trace   # Hibernate 5 전용
> ```
> **Hibernate 6 에서는 아무 효과가 없습니다.** 바인딩 로거가 `org.hibernate.orm.jdbc.bind` 로 이동했기 때문입니다.
> "설정했는데 파라미터가 안 보인다"면 십중팔구 이것입니다. Spring Boot 3.x 를 쓴다면 `org.hibernate.orm.jdbc.bind` 를 쓰세요.
> 추출된 값(SELECT 결과)까지 보려면 `org.hibernate.orm.jdbc.extract: trace` 를 추가합니다.

### ③ 줄바꿈 정렬

```yaml
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

**결과**
```
2026-07-22 10:11:23.441 DEBUG --- org.hibernate.SQL :
    select
        c1_0.customer_id,
        c1_0.city,
        c1_0.created_at,
        c1_0.email,
        c1_0.grade,
        c1_0.name,
        c1_0.phone,
        c1_0.points
    from
        customers c1_0
    where
        c1_0.grade=?
2026-07-22 10:11:23.443 TRACE --- org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [VIP]
```

조인이 여러 개 들어가는 [Step 06](../step-06-joins/) 부터는 `format_sql` 없이는 읽기 어렵습니다. **켜 두는 것을 권합니다.**

### ④ JPQL 까지 보기

QueryDSL 이 만든 **JPQL** 을 보려면 별도 카테고리가 필요합니다.

```yaml
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
    org.hibernate.orm.query: trace
```

**결과**
```
2026-07-22 10:11:23.401 TRACE --- org.hibernate.orm.query : HQL : select customer from Customer customer where customer.grade = ?1
2026-07-22 10:11:23.441 DEBUG --- org.hibernate.SQL :
    select ...
```

**JPQL 한 줄, SQL 한 줄이 나란히 찍힙니다.** 이 두 줄의 관계가 1-8 절의 주제입니다.

### ⑤ p6spy — 완성된 SQL 을 한 줄로

`?` 자리에 값이 채워진 **바로 복붙해서 실행 가능한 SQL** 을 보고 싶다면 p6spy 를 씁니다.

```groovy
implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.9.1'
```

**결과**
```
2026-07-22 10:11:23.445  INFO --- p6spy : #1753146683445 | took 3ms | statement | connection 4| url jdbc:mysql://127.0.0.1:3307/shop
select c1_0.customer_id,c1_0.city,c1_0.created_at,c1_0.email,c1_0.grade,c1_0.name,c1_0.phone,c1_0.points from customers c1_0 where c1_0.grade=?
select c1_0.customer_id,c1_0.city,c1_0.created_at,c1_0.email,c1_0.grade,c1_0.name,c1_0.phone,c1_0.points from customers c1_0 where c1_0.grade='VIP'
```

원본 SQL 과 값이 채워진 SQL 을 둘 다 주고, **실행 시간(`took 3ms`)** 까지 붙여 줍니다.

| 방식 | 장점 | 단점 |
|---|---|---|
| `hibernate.SQL` + `orm.jdbc.bind` | 의존성 추가 없음, Hibernate 가 실제로 만든 그대로 | 값이 따로 놀아 조합이 번거로움 |
| **p6spy** | 실행 가능한 완성 SQL, 실행 시간 측정 | 의존성 추가, JDBC 프록시 오버헤드 |

> ⚠️ **함정 — 운영에 `bind: trace` 나 p6spy 를 켜 둔다**
> 바인딩 로그는 **파라미터 값을 전부 평문으로 남깁니다.** 이메일, 전화번호, 주민등록번호가 로그 파일에 그대로 쌓입니다. 개인정보 유출 사고의 흔한 경로입니다.
> p6spy 는 여기에 더해 모든 JDBC 호출을 프록시로 감싸므로 성능 오버헤드도 있습니다.
> **로컬/개발 프로파일에만 켜세요.**
> ```yaml
> # application-local.yml 에만
> logging.level.org.hibernate.orm.jdbc.bind: trace
> ```

> 💡 **실무 팁 — `show-sql: true` 는 쓰지 마세요**
> ```yaml
> spring.jpa.show-sql: true    # 권장하지 않음
> ```
> 이건 `System.out` 으로 직접 출력합니다. 로그 레벨로 끌 수 없고, 로그 파일에 안 남고, 타임스탬프도 스레드 정보도 없습니다.
> 같은 결과를 `logging.level.org.hibernate.SQL=debug` 로 얻을 수 있고 그쪽이 모든 면에서 낫습니다.

---

## 1-7. JPQL vs QueryDSL 대조표

같은 쿼리 5개를 문자열 JPQL 과 QueryDSL 로 나란히 놓습니다.

### ① VIP 고객 전체

```java
// JPQL
em.createQuery("select c from Customer c where c.grade = :grade", Customer.class)
  .setParameter("grade", Grade.VIP)
  .getResultList();

// QueryDSL
queryFactory.selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

**결과** — 양쪽 모두 동일한 SQL
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

### ② 포인트 5000 이상, 이름순

```java
// JPQL
em.createQuery("""
        select c from Customer c
        where c.points >= :min
        order by c.name asc
        """, Customer.class)
  .setParameter("min", 5000)
  .getResultList();

// QueryDSL
queryFactory.selectFrom(customer)
        .where(customer.points.goe(5000))
        .orderBy(customer.name.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.points >= ?
order by c1_0.name asc
```
```
바인딩: [1] 5000
조회 11건 — 김서준, 류하나, 문시우, 배채영, 안지수, ...
```

### ③ 이름에 "지" 가 들어가는 고객

```java
// JPQL — % 를 직접 붙여야 합니다
em.createQuery("select c from Customer c where c.name like :kw", Customer.class)
  .setParameter("kw", "%지%")
  .getResultList();

// QueryDSL — contains 가 % 를 붙여 줍니다
queryFactory.selectFrom(customer)
        .where(customer.name.contains("지"))
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
바인딩: [1] %지%
조회 2건 — 안지수, 한지호
```

`escape '!'` 가 붙는 것이 QueryDSL 의 특징입니다. 검색어에 `%` 나 `_` 가 들어가도 리터럴로 취급되도록 이스케이프 문자를 지정합니다. 문자열 JPQL 로 직접 `%키워드%` 를 만들면 이 보호가 없습니다.

### ④ 전화번호가 없는 고객

```java
// JPQL
em.createQuery("select c from Customer c where c.phone is null", Customer.class)
  .getResultList();

// QueryDSL
queryFactory.selectFrom(customer)
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
조회 3건 — 오하윤, 문시우, 정  훈
```

### ⑤ 도시별 고객 수

```java
// JPQL
em.createQuery("""
        select c.city, count(c)
        from Customer c
        group by c.city
        order by count(c) desc
        """, Object[].class)
  .getResultList();

// QueryDSL
queryFactory
        .select(customer.city, customer.count())
        .from(customer)
        .groupBy(customer.city)
        .orderBy(customer.count().desc())
        .fetch();
```

**결과**
```sql
select c1_0.city, count(c1_0.customer_id)
from customers c1_0
group by c1_0.city
order by count(c1_0.customer_id) desc
```
```
서울   9
부산   6
대구   5
인천   4
광주   3
대전   3
```

JPQL 은 `Object[]` 를 돌려주므로 `row[0]`, `row[1]` 로 꺼내며 캐스팅해야 합니다. QueryDSL 은 `Tuple` 을 돌려주고 `t.get(customer.city)` 처럼 **타입이 유지된 채로** 꺼냅니다. 자세한 것은 [Step 05 — 프로젝션과 DTO](../step-05-projections/) 에서 다룹니다.

### 종합 비교

| 항목 | 문자열 JPQL | QueryDSL |
|---|---|---|
| 오타 검출 | 런타임 | **컴파일** |
| IDE 자동완성 | ✗ | **○** |
| 필드 리네임 추적 | ✗ | **○** |
| 동적 조건 | 문자열 조립 (지옥) | **`BooleanBuilder` / `null` 무시** |
| `%` 이스케이프 | 직접 | **자동** |
| 결과 타입 | `Object[]` | **`Tuple`** |
| 학습 비용 | 낮음 | 중간 |
| 빌드 복잡도 | 없음 | **APT 필요** (1-2 절의 함정) |

---

## 1-8. QueryDSL 이 만드는 것은 JPQL 이지 SQL 이 아니다

이 절이 이 스텝에서 **개념적으로 가장 중요합니다.** 여기를 대충 넘기면 [Step 07 — 서브쿼리](../step-07-subqueries/) 에서 반드시 막힙니다.

### 2단계 변환

```
  QueryDSL 자바 코드
        │  ① JPAQueryFactory / JPAQuery 가 조립
        ▼
      JPQL 문자열                     ← QueryDSL 의 출력물은 여기까지입니다
        │  ② Hibernate 의 HQL 파서 + Dialect 가 번역
        ▼
      네이티브 SQL (MySQL 방언)
        │  ③ JDBC 드라이버가 전송
        ▼
       MySQL 8
```

QueryDSL 은 **SQL 을 모릅니다.** 테이블 이름도, 컬럼 이름도, MySQL 문법도 모릅니다. QueryDSL 이 아는 것은 엔티티와 필드뿐이고, 만들어 내는 것은 JPQL 입니다. SQL 로 바꾸는 일은 전적으로 Hibernate 의 몫입니다.

### 직접 확인하기 — `.toString()`

`JPAQuery` 는 `toString()` 에 **JPQL 을 담아 줍니다.** 실행하지 않고 찍어 볼 수 있습니다.

```java
JPAQuery<Customer> query = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP)
                .and(customer.points.goe(5000)));

System.out.println(query.toString());     // 아직 실행 안 함
List<Customer> result = query.fetch();    // 여기서 실행
```

**결과** — `toString()` 출력 (JPQL)
```
select customer
from Customer customer
where customer.grade = ?1 and customer.points >= ?2
```

**결과** — `hibernate.SQL` (SQL)
```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ? and c1_0.points >= ?
```
```
바인딩: [1] Grade.VIP, [2] 5000
조회 3건 — 김서준, 류하나, 배채영
```

두 줄을 나란히 놓고 대조합니다.

| 요소 | JPQL | SQL |
|---|---|---|
| 조회 대상 | `customer` (엔티티) | `c1_0.customer_id, c1_0.city, ...` (컬럼 목록) |
| from | `Customer customer` (**클래스명**) | `customers c1_0` (**테이블명**) |
| 별칭 | `customer` (Q타입 변수명) | `c1_0` (Hibernate 생성) |
| 필드 | `customer.grade` | `c1_0.grade` (컬럼명) |
| 파라미터 | `?1`, `?2` (**번호 있음**) | `?`, `?` (JDBC 자리표시자) |

`from Customer customer` 의 `Customer` 는 **클래스 이름**입니다. `customers` 테이블이 아닙니다. QueryDSL 은 `customers` 라는 문자열을 한 번도 만들지 않습니다.

### 왜 이 구분이 중요한가

**JPQL 이 못 하는 것은 QueryDSL 도 못 합니다.** QueryDSL 은 JPQL 의 상위 집합이 아니라, JPQL 을 타입 안전하게 조립하는 도구일 뿐입니다.

| 하고 싶은 것 | SQL | JPQL | QueryDSL(JPA) |
|---|---|---|---|
| where 절 서브쿼리 | ○ | ○ | ○ |
| select 절 서브쿼리 | ○ | ○ | ○ |
| **from 절 서브쿼리 (인라인 뷰)** | ○ | **✗** | **✗** |
| 윈도우 함수 (`ROW_NUMBER()`) | ○ | ✗ | ✗ (JPA 모듈에서는) |
| `UNION` | ○ | ✗ | ✗ |
| DB 고유 함수 | ○ | △ (등록 필요) | △ |

`from` 절 서브쿼리를 못 쓰는 것은 **QueryDSL 의 한계가 아니라 JPQL 명세의 한계**입니다. JPQL 은 "엔티티를 조회하는 언어"라서 from 절에는 엔티티만 올 수 있고, "임시 결과 집합"이라는 개념이 없습니다.

```java
// ✗ 이런 API 자체가 없습니다
queryFactory.selectFrom( <서브쿼리> )
```

[Step 07](../step-07-subqueries/) 에서 이 벽에 부딪히고, 세 가지 우회로(쿼리 분리 / 조인 변환 / 네이티브 SQL)를 배웁니다. 그때 "왜 안 되는가"의 답이 바로 이 절입니다.

> 💡 **실무 팁 — `toString()` 을 디버깅 1순위로 쓰세요**
> "의도와 다른 SQL 이 나간다" 싶을 때, SQL 로그부터 보면 Hibernate 의 변환까지 섞여 있어 원인을 좁히기 어렵습니다.
> **먼저 `query.toString()` 으로 JPQL 을 봅니다.**
> - JPQL 이 이미 이상하다 → **내 QueryDSL 코드가 틀렸다**
> - JPQL 은 맞는데 SQL 이 이상하다 → **엔티티 매핑이나 페치 전략 문제다**
> 이 두 갈래로 나누는 것만으로 디버깅 시간이 절반이 됩니다.
> `.toString()` 은 쿼리를 실행하지 않으므로 테스트에서 마음껏 찍어도 됩니다.

> ⚠️ **함정 — `toString()` 의 `?1` 을 SQL 파라미터 번호로 착각한다**
> JPQL 의 `?1`, `?2` 는 **JPQL 레벨의 위치 파라미터**입니다. Hibernate 가 SQL 로 번역하는 과정에서 파라미터 개수와 순서가 **바뀔 수 있습니다.**
> 예를 들어 `in` 절이 확장되거나, 상속 매핑의 판별 컬럼 조건이 추가되면 SQL 쪽 `?` 가 더 많아집니다.
> **JPQL 의 `?1` 과 SQL 의 첫 번째 `?` 가 항상 같다고 가정하지 마세요.** 실제 바인딩 값은 `orm.jdbc.bind` 로그로 확인합니다.

---

## 1-9. 함정 모음

### 함정 ① — `JPAQueryFactory` 를 매번 `new` 로 만든다

```java
@Service
public class CustomerService {

    @PersistenceContext
    private EntityManager em;

    public List<Customer> findVips() {
        JPAQueryFactory queryFactory = new JPAQueryFactory(em);   // 호출마다 생성
        return queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();
    }
}
```

**동작은 합니다.** SQL 도 정상이고, 결과도 맞고, 스레드 안전성 문제도 없습니다. 그래서 리뷰에서 잘 안 걸립니다.

```sql
select c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
       c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade = ?
```
```
바인딩: [1] Grade.VIP
조회 4건 — 정상
```

문제는 다른 데 있습니다.

- **`JPAQueryFactory` 는 상태가 거의 없는 얇은 팩토리**입니다. 매번 만드는 것은 낭비이며, 무엇보다 불필요합니다.
- 진짜 손해는 **설정 주입 지점을 잃는 것**입니다. 예를 들어 SQL 템플릿이나 `JPQLTemplates` 를 커스터마이즈해야 할 때(네이티브 함수 등록 등), 빈이 하나면 한 곳만 고치면 됩니다. `new` 가 프로젝트 곳곳에 흩어져 있으면 전부 찾아 고쳐야 합니다.
- 테스트에서 `JPAQueryFactory` 를 갈아 끼울 수 없습니다.

```java
// 고친 코드
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final JPAQueryFactory queryFactory;   // 빈 주입

    public List<Customer> findVips() {
        return queryFactory.selectFrom(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();
    }
}
```

SQL 은 완전히 동일합니다. 바뀌는 것은 유지보수성뿐입니다.

### 함정 ② — 진짜 `EntityManager` 를 필드로 붙들어 둔다

1-4 절에서 다뤘지만 스택트레이스를 보고 갑니다.

```java
@Component
public class BadQuerySupport {

    private final JPAQueryFactory queryFactory;

    public BadQuerySupport(EntityManagerFactory emf) {
        // ✗ 프록시가 아니라 진짜 EntityManager 를 만들어 싱글턴에 고정
        this.queryFactory = new JPAQueryFactory(emf.createEntityManager());
    }
}
```

개발 중에는 완벽하게 동작합니다. 부하 테스트를 돌리면:

**결과**
```
java.util.ConcurrentModificationException
	at java.base/java.util.HashMap$KeyIterator.next(HashMap.java:1584)
	at org.hibernate.engine.internal.StatefulPersistenceContext.clear(StatefulPersistenceContext.java:222)
	at org.hibernate.internal.SessionImpl.internalClear(SessionImpl.java:394)

org.hibernate.HibernateException: Session is closed
	at org.hibernate.internal.AbstractSharedSessionContract.checkOpen(AbstractSharedSessionContract.java:429)
	at org.hibernate.internal.SessionImpl.checkOpenOrWaitingForAutoClose(SessionImpl.java:388)
```

한 스레드가 세션을 닫으면 다른 스레드가 닫힌 세션을 씁니다. **증상이 산발적이고 재현이 안 되므로 원인을 찾는 데 며칠이 걸립니다.**

> ⚠️ **함정 — 이 버그는 "테스트에서 통과"한다**
> 단위 테스트는 스레드 하나로 순차 실행됩니다. `@SpringBootTest` 도 마찬가지입니다.
> **동시성 버그를 테스트로 잡을 수 없다**는 것이 이 함정의 본질입니다. 규칙으로 막아야 합니다:
> **`emf.createEntityManager()` 를 애플리케이션 코드에서 직접 호출하지 마세요.** 스프링이 주는 프록시만 쓰세요.

### 함정 ③ — 조회 전용 메서드에 `@Transactional` 을 안 붙인다

```java
public List<Order> findOrdersWithCustomerName() {
    List<Order> orders = queryFactory.selectFrom(order).limit(5).fetch();
    // 트랜잭션 없음 → 조회 직후 영속성 컨텍스트가 닫힘
    return orders;
}

// 호출한 쪽에서
orders.get(0).getCustomer().getName();
```

**결과**
```
org.hibernate.LazyInitializationException: could not initialize proxy
  [com.example.shop.entity.Customer#12] - no Session
	at org.hibernate.proxy.AbstractLazyInitializer.initialize(AbstractLazyInitializer.java:165)
	at org.hibernate.proxy.AbstractLazyInitializer.getImplementation(AbstractLazyInitializer.java:314)
	at com.example.shop.entity.Customer$HibernateProxy.getName(Unknown Source)
```

모든 연관이 `LAZY` 이므로 (스펙상 그렇게 매핑했습니다) 프록시를 건드리는 순간 세션이 필요합니다.

**해결**: 조회 메서드에도 `@Transactional(readOnly = true)` 를 붙입니다. 읽기 전용 플래그는 플러시를 끄고 스냅샷을 만들지 않아 성능 이득도 있습니다. 애초에 필요한 데이터를 조인으로 가져오는 것이 더 나은 해법이며, [Step 06 — 조인](../step-06-joins/) 에서 다룹니다.

### 함정 ④ — `Q` 클래스를 커밋한다

```
build/generated/sources/annotationProcessor/java/main/
  └── com/example/shop/entity/QCustomer.java
```

이 파일들은 **빌드 산출물**입니다. `.gitignore` 에 넣어야 합니다. 커밋해 두면 엔티티를 바꾼 뒤 옛 Q타입이 살아남아 "분명 필드를 추가했는데 Q타입에 없다"는 유령 버그가 생깁니다. [Step 02 — Q타입의 정체](../step-02-qtype/) 에서 이 사고를 자세히 재현합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 문자열 JPQL 의 문제 | 오타·필드명·타입 불일치가 전부 **런타임**에 터진다. 리팩터링을 못 따라온다 |
| QueryDSL 의 본질 | 쿼리를 자바 객체 그래프로 조립 → 검증이 **자바 컴파일러의 일**이 된다 |
| 6.x 그룹 ID | `io.github.openfeign.querydsl` (OpenFeign 포크). 패키지는 여전히 `com.querydsl.*` |
| **classifier 규칙** | 6.x: **jpa 는 없음, apt 만 `:jpa`**. 5.x: 양쪽 다 `:jakarta` |
| 케이스 (a) | `jpa:6.12:jakarta` → `Could not find ...-jakarta.jar` (의존성 해결 실패) |
| 케이스 (b) | `apt:6.12` (classifier 누락) → 빌드 성공 + **Q타입 0개** |
| 케이스 (c) | `5.0.0` classifier 없음 → **런타임** `NoClassDefFoundError: javax/persistence/Entity` |
| 왜 비대칭인가 | jpa 의 classifier 축 = javax/jakarta (6.x 는 하나뿐), apt 의 축 = 프로세서 종류 (여전히 필요) |
| 포크 배경 | 원본 2021-07 5.0.0 이후 중단. Hibernate 6 의 `ByteType` 제거로 5.x 가 깨짐 (#3436, #3439) |
| `JPAQueryFactory` 빈 | 싱글턴으로 안전. 주입되는 `EntityManager` 는 **스레드별로 위임하는 공유 프록시** |
| 금지 패턴 | `emf.createEntityManager()` 를 싱글턴에 고정 → 운영에서만 터지는 동시성 버그 |
| `selectFrom` | `select(x).from(x)` 의 축약. 조회 대상과 from 이 같을 때만 |
| `fetchOne()` | 2건 이상이면 `NonUniqueResultException`. 유니크 컬럼에만 |
| SQL 로그 | `org.hibernate.SQL=debug` + `org.hibernate.orm.jdbc.bind=trace` + `format_sql` |
| Hibernate 6 로거 변경 | `BasicBinder`(5) → **`org.hibernate.orm.jdbc.bind`**(6). 안 보이면 이것 |
| Hibernate 6 별칭 | `c1_0` 형태, `as` 생략. Hibernate 5 는 `customer0_` |
| **2단계 변환** | QueryDSL → **JPQL** → SQL. QueryDSL 의 출력물은 JPQL 까지다 |
| `query.toString()` | 실행하지 않고 **JPQL** 을 확인. 디버깅 1순위 |
| JPQL 의 한계 = QueryDSL 의 한계 | from 절 서브쿼리·`UNION`·윈도우 함수 불가 → Step 07 의 벽 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 문자열 JPQL 에 필드명 오타(`c.grde`)를 내고 실행해 예외 클래스명과 메시지를 기록한 뒤, 같은 쿼리를 QueryDSL 로 옮겨 컴파일 에러가 나는 것을 확인하기
2. 주어진 4개의 잘못된 `build.gradle` 조각을 보고, 각각 어떤 에러가 언제(빌드/런타임) 나는지 판정하기
3. `JPAQueryFactory` 를 빈으로 등록하고, 주입된 `EntityManager` 가 프록시임을 클래스명 출력으로 증명하기
4. GOLD 등급이면서 포인트 3000 이상인 고객을 `selectFrom` 으로 조회하고 생성 SQL 을 로그에서 확인하기
5. 같은 쿼리의 JPQL(`toString()`)과 SQL(로그)을 나란히 출력하고, 별칭·테이블명·파라미터 표기의 차이 3가지를 서술하기
6. `fetchOne()` 이 `NonUniqueResultException` 을 던지는 조건을 실제로 재현하고, 안전한 대안 두 가지로 고치기

---

## 다음 단계

`selectFrom(customer)` 의 `customer` 는 도대체 어디서 왔을까요. 우리가 만든 적 없는 `QCustomer` 클래스가 빌드 디렉터리에 생겨 있고, 그 안에 `NumberPath<Long> id` 같은 필드가 들어 있습니다. 이것을 만드는 것이 애노테이션 프로세서(APT)이며, **QueryDSL 도입 실패의 나머지 절반이 "Q타입이 안 생긴다"** 입니다.

다음 스텝에서는 생성된 `QCustomer.java` 를 한 줄씩 뜯어보고, Q타입이 안 생기는 5가지 원인을 각각 재현·진단·해결합니다. 1-2 절에서 좌표를 맞췄으니, 이제 그 좌표가 실제로 무슨 일을 하는지 봅니다.

→ [Step 02 — Q타입의 정체](../step-02-qtype/)

---

## 실습 파일

이 스텝은 자바 파일 세 개로 진행합니다. 셋 다 `@SpringBootTest @Transactional` 테스트 클래스이며, 패키지는 `com.example.shop.step01` 입니다. 먼저 `Practice.java` 를 IDE 에서 열어 테스트를 하나씩 실행하며 1-1 ~ 1-9 의 모든 로그 출력을 재현하고, 그다음 `Exercise.java` 의 6문제를 직접 채운 뒤, `Solution.java` 로 정답과 해설을 대조합니다.

세 파일 모두 **SQL 로그 설정이 되어 있다는 전제**로 쓰여 있습니다. 1-6 절의 `application.yml` 설정을 먼저 적용하지 않으면 콘솔에 아무것도 안 나와 실습의 의미가 없습니다. 실행 전에 반드시 확인하세요.

```bash
./gradlew test --tests 'com.example.shop.step01.*'
```

### Practice.java

본문 1-1 ~ 1-9 의 모든 예제를 절 번호 주석(`// [1-5] 첫 쿼리 — selectFrom`)과 함께 담은 실행 파일입니다.

- `[1-1]` 의 `오타난_JPQL_은_런타임에_터진다()` 는 **의도적으로 예외를 던지는 테스트**입니다. `assertThatThrownBy` 로 예외를 잡아 클래스명과 메시지를 콘솔에 찍습니다. 실패하는 테스트가 아니라 "예외가 나는 것을 검증하는" 테스트이므로 초록불이 정상입니다.
- `[1-4]` 의 `주입된_EntityManager의_정체()` 는 `em.getClass().getName()` 을 출력합니다. `jdk.proxy2.$Proxy214` 처럼 숫자가 붙은 프록시 클래스명이 나오면 정상이며, 숫자는 실행할 때마다 다릅니다. `org.hibernate.internal.SessionImpl` 이 나온다면 프록시가 아닌 실제 세션이 주입된 것이니 설정을 의심하세요.
- `[1-6]` 의 `로그_설정_확인()` 은 쿼리 하나를 날려 놓고 아무것도 단언하지 않습니다. **콘솔을 눈으로 보라는 테스트**입니다. `org.hibernate.SQL` 줄과 `binding parameter` 줄이 둘 다 보여야 이후 스텝을 정상적으로 진행할 수 있습니다.
- `[1-8]` 의 `JPQL과_SQL을_나란히()` 는 `query.toString()` 을 먼저 출력한 뒤 `query.fetch()` 를 호출합니다. **출력 순서가 곧 변환 순서**입니다 — 콘솔에서 JPQL 한 줄이 먼저 찍히고 그 아래로 Hibernate 의 SQL 이 이어집니다.
- `[1-9]` 의 `fetchOne_은_2건이면_터진다()` 역시 예외 검증 테스트입니다. `customer.city.eq("서울")` 로 9건이 나오는 조건을 일부러 씁니다.
- `@Transactional` 이 붙어 있어 모든 테스트가 끝나면 롤백됩니다. 이 스텝은 조회만 하므로 롤백할 것도 없지만, 코스 전체의 규칙으로 통일했습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 요구사항 주석과 `// 여기에 작성:` 자리로 구성돼 있고, 그대로 실행하면 대부분 컴파일은 되지만 아무 일도 하지 않거나 단언이 실패합니다.

- **문제 1·6** 은 예외를 재현하는 문제입니다. "무엇이 어디서 터지는가"를 직접 보는 것이 목적이므로, 예외를 삼키지 말고 메시지를 콘솔에 출력하세요.
- **문제 2** 는 코드를 쓰는 문제가 아니라 **판정 문제**입니다. 4개의 `build.gradle` 조각이 주석 블록으로 들어 있고, 각각에 대해 (빌드 실패 / Q타입 미생성 / 런타임 실패 / 정상) 중 하나를 고르고 근거를 적습니다. 답을 주석으로 적는 문제이므로 실행 결과는 없습니다.
- **문제 3** 은 `em.getClass().getName()` 과 `em instanceof EntityManager` 를 둘 다 출력해 "프록시인데 인터페이스는 만족한다"를 확인하는 문제입니다.
- **문제 5** 는 이 문제지에서 가장 중요합니다. JPQL 과 SQL 의 차이를 **3가지 서술**해야 하며, 답은 별칭 표기(`customer` vs `c1_0`), from 대상(클래스명 vs 테이블명), 파라미터 표기(`?1` vs `?`) 입니다.
- 문제 4 의 조건(GOLD + 포인트 3000 이상)은 결과가 6건 나오도록 데이터가 준비돼 있습니다. 건수가 다르면 시드 데이터를 다시 넣으세요.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 예외 클래스가 `org.hibernate.query.SemanticException` (필드명 오타의 경우) 임을 짚습니다. 1-1 절의 키워드 오타(`SyntaxException`)와 **다른 예외**라는 점이 포인트입니다. 파싱은 성공했지만 의미 해석에서 실패했기 때문입니다.
- **정답 2** 의 채점 기준은 이렇습니다: (a) `jpa:6.12:jakarta` → 빌드 실패, (b) `apt:6.12` → Q타입 미생성, (c) `jpa:5.0.0` → 런타임 실패, (d) 정답 조합 → 정상. **(b) 가 "빌드 성공"으로 분류된다는 점**이 가장 자주 틀리는 부분입니다.
- **정답 3** 은 프록시 클래스명이 `jdk.proxy2.$Proxy` 로 시작하는 이유를 `SharedEntityManagerCreator` 까지 거슬러 설명하고, 왜 그것이 싱글턴 `JPAQueryFactory` 를 안전하게 만드는지 스레드별 위임 구조로 정리합니다.
- **정답 5** 는 차이 3가지에 더해 **네 번째 차이**를 보너스로 언급합니다: JPQL 은 `select customer` 로 엔티티 하나를 지정하지만 SQL 은 8개 컬럼을 전부 나열한다는 것 — 이것이 [Step 05](../step-05-projections/) 의 프로젝션으로 이어집니다.
- **정답 6** 은 대안 두 가지를 모두 제시합니다: `fetchFirst()` 로 바꾸거나, `fetch()` 로 받아 `size()` 를 검사하는 것. 그리고 **어느 쪽도 "조건이 유니크한지"를 대신 판단해 주지 않는다**는 경고로 마무리합니다.

```java file="./Solution.java"
```
