# Step 02 — Q타입의 정체

> **학습 목표**
> - APT(Annotation Processing Tool)가 컴파일 라이프사이클의 **어느 지점**에서 도는지 설명한다
> - 생성된 `QCustomer.java` 를 열어 `NumberPath` / `StringPath` / `EnumPath` / `ListPath` 가 각각 어떤 조건 메서드를 주는지 파악한다
> - `build/generated/` 를 왜 커밋하지 않는지 이해하고 `.gitignore` 를 설정한다
> - **Q타입이 생성되지 않는 5가지 원인**을 각각 재현하고 구분해서 진단한다
> - IntelliJ 가 Gradle 이 아닌 자체 빌더로 컴파일할 때 생기는 증상을 알아본다
> - `new QCustomer("c")` 별칭이 **생성 SQL 에 어떻게 반영되는지** 눈으로 확인한다
>
> **선행 스텝**: [Step 01 — 환경 구축과 첫 쿼리](../step-01-setup/)
> **예상 소요**: 80분

---

Step 01 에서 우리는 `QCustomer.customer` 를 아무 설명 없이 썼습니다. 그것이 어디서 왔는지 묻지 않고 넘어갔습니다.

이 스텝은 그 질문에 답합니다. Q타입은 마법이 아니라 **컴파일 중에 생성되는 평범한 자바 소스 파일**입니다. 그리고 QueryDSL 을 쓰다가 겪는 문제의 절반 이상이 "Q타입이 없다" 또는 "Q타입이 옛날 것이다" 입니다. 이 스텝을 제대로 읽어 두면 그 절반을 스스로 진단할 수 있습니다.

---

## 2-1. APT 는 언제 도는가

`javac` 는 소스를 기계적으로 바이트코드로 바꾸기만 하는 도구가 아닙니다. 컴파일 도중 **애노테이션 프로세서**에게 "이 소스들 봤는데, 너 뭐 만들 거 있어?" 하고 묻는 단계가 있습니다. 이것이 APT 입니다.

순서는 이렇습니다.

```
1. javac 가 소스 파일을 파싱해서 심볼 테이블을 만든다
       ↓
2. 등록된 애노테이션 프로세서들에게 "네가 관심 있는 애노테이션이 붙은 요소" 를 넘긴다
       ↓
3. 프로세서가 새 소스 파일을 생성한다              ← QueryDSL 의 JPAAnnotationProcessor 가 여기서 QCustomer.java 를 씁니다
       ↓
4. 새로 생성된 소스가 있으면 → 1번으로 돌아간다 (라운드가 반복됨)
       ↓
5. 더 이상 생성되는 것이 없으면 → 전체를 바이트코드로 컴파일한다
```

핵심은 **3번과 5번이 같은 `compileJava` 태스크 안에서 일어난다**는 것입니다. Q타입은 별도 태스크나 별도 명령으로 만들어지지 않습니다. `./gradlew compileJava` 한 번이 곧 Q타입 생성입니다.

이 사실에서 두 가지가 따라옵니다.

- **엔티티를 고쳤으면 컴파일해야 Q타입이 갱신됩니다.** 파일만 저장해서는 아무 일도 일어나지 않습니다.
- **엔티티가 컴파일 에러면 Q타입도 안 생깁니다.** 그리고 Q타입이 없으니 그것을 쓰는 코드도 전부 에러가 납니다. 에러 100개 중 진짜는 1개인 상황이 여기서 나옵니다.

직접 봅시다.

```bash
./gradlew clean compileJava --info | grep -iE "annotation|querydsl|generated"
```

**결과**
```
> Task :compileJava
Compiling with toolchain '/Users/you/.sdkman/candidates/java/21.0.2-tem'.
Compiling with JDK Java compiler API.
Note: Annotation processing is enabled because one or more processors were found
  on the class path. Processors: com.querydsl.apt.jpa.JPAAnnotationProcessor,
  lombok.launch.AnnotationProcessorHider$AnnotationProcessor
Generated source output directory:
  /Users/you/shop-querydsl/build/generated/sources/annotationProcessor/java/main
BUILD SUCCESSFUL in 4s
```

`Processors:` 줄에 `com.querydsl.apt.jpa.JPAAnnotationProcessor` 가 보입니다. 이것이 QueryDSL 의 프로세서입니다. **이 줄이 안 보이면 Q타입은 절대 생성되지 않습니다.** 2-4 의 진단은 항상 이 줄을 확인하는 것에서 시작합니다.

> 💡 **실무 팁 — 프로세서 이름으로 모듈을 구분합니다**
> QueryDSL 에는 용도별로 프로세서가 따로 있습니다. JPA 용은 `JPAAnnotationProcessor`(`@Entity` 를 봅니다), 순수 POJO 용은 `QuerydslAnnotationProcessor`(`@QueryEntity` 를 봅니다), JDO 용은 `JDOAnnotationProcessor` 입니다.
> `annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'` 의 **`:jpa` classifier 가 바로 이 셋 중 JPA 판을 고르는 스위치**입니다. classifier 를 빠뜨리면 기본 판이 걸려서 `@Entity` 를 아예 쳐다보지 않습니다. Step 01 에서 다룬 함정이 여기서 이렇게 연결됩니다.

---

## 2-2. 생성된 QCustomer.java 뜯어보기

`build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QCustomer.java` 를 열어 봅니다. 실제로 생성된 내용입니다.

```java
package com.example.shop.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;
import com.querydsl.core.types.PathMetadata;
import javax.annotation.processing.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QCustomer is a Querydsl query type for Customer
 */
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QCustomer extends EntityPathBase<Customer> {

    private static final long serialVersionUID = 1234567890L;

    public static final QCustomer customer = new QCustomer("customer");

    public final StringPath city = createString("city");

    public final DatePath<java.time.LocalDate> birthDate =
            createDate("birthDate", java.time.LocalDate.class);

    public final DateTimePath<java.time.LocalDateTime> createdAt =
            createDateTime("createdAt", java.time.LocalDateTime.class);

    public final StringPath email = createString("email");

    public final EnumPath<Grade> grade = createEnum("grade", Grade.class);

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final StringPath name = createString("name");

    public final ListPath<Order, QOrder> orders =
            this.<Order, QOrder>createList("orders", Order.class, QOrder.class, PathInits.DIRECT2);

    public final StringPath phone = createString("phone");

    public final NumberPath<Integer> points = createNumber("points", Integer.class);

    public QCustomer(String variable) {
        super(Customer.class, forVariable(variable));
    }

    public QCustomer(Path<? extends Customer> path) {
        super(path.getType(), path.getMetadata());
    }

    public QCustomer(PathMetadata metadata) {
        super(Customer.class, metadata);
    }
}
```

읽을 거리가 몇 가지 있습니다.

**① `public static final QCustomer customer = new QCustomer("customer");`**

우리가 static import 해서 쓰는 그 `customer` 입니다. 특별한 것이 없습니다. 그냥 **미리 만들어 둔 인스턴스 하나**이고, 별칭 문자열이 `"customer"` 입니다. 이 별칭이 JPQL 의 `from Customer customer` 에 그대로 들어갑니다.

**② 필드가 알파벳 순으로 정렬돼 있습니다**

엔티티에 선언한 순서(`id, email, name, phone, grade, ...`)가 아니라 `birthDate, city, createdAt, email, ...` 순입니다. QueryDSL 이 정렬해서 생성하기 때문입니다. 생성 파일을 diff 할 때 안정적이라는 장점이 있습니다.

**③ 필드 타입이 자바 타입이 아니라 `~Path` 입니다**

`private String name;` 이 `public final StringPath name;` 이 됩니다. 이 치환이 QueryDSL 의 전부라고 해도 됩니다. `String` 에는 `contains()` 가 있지만 SQL 조건을 만들지는 못합니다. `StringPath` 의 `contains()` 는 `BooleanExpression` 을 돌려줍니다.

**④ `@Generated` 애노테이션**

`javax.annotation.processing.Generated` 입니다. 정적 분석 도구(SonarQube, JaCoCo)가 이 애노테이션을 보고 **생성 코드를 커버리지·품질 측정에서 제외**합니다. Q타입 때문에 커버리지가 떨어지는 일은 없습니다.

### Path 타입별로 쓸 수 있는 메서드

어떤 Path 가 배정되느냐에 따라 쓸 수 있는 조건 메서드가 달라집니다. 이것이 "타입 안전"의 실체입니다.

| 자바 필드 타입 | 생성되는 Path | 대표 메서드 |
|---|---|---|
| `String` | `StringPath` | `eq` `ne` `in` `like` `contains` `startsWith` `endsWith` `isEmpty` `lower` `upper` `length` `concat` |
| `Integer` `Long` `BigDecimal` | `NumberPath<T>` | `eq` `ne` `in` `gt` `goe` `lt` `loe` `between` `add` `subtract` `multiply` `divide` `sum` `avg` `max` `min` |
| `LocalDate` | `DatePath<T>` | `eq` `before` `after` `between` `year()` `month()` `dayOfMonth()` |
| `LocalDateTime` | `DateTimePath<T>` | `DatePath` 의 것 + `hour()` `minute()` `second()` |
| `enum` | `EnumPath<E>` | `eq` `ne` `in` `notIn` `ordinal()` `stringValue()` |
| `Boolean` | `BooleanPath` | `eq` `isTrue()` `isFalse()` `and` `or` `not` |
| `@OneToMany List<T>` | `ListPath<T, QT>` | `isEmpty()` `isNotEmpty()` `size()` `contains()` `any()` |
| `@ManyToOne` 연관 | 대상 엔티티의 `QT` | 그 엔티티의 모든 Path 를 점으로 이어서 접근 |

이 표가 왜 중요한지는 실수해 보면 압니다.

```java
// 컴파일 에러 — StringPath 에는 gt() 가 없습니다
queryFactory.selectFrom(customer)
        .where(customer.name.gt(100))
        .fetch();
```

```
error: incompatible types: int cannot be converted to String
        .where(customer.name.gt(100))
                               ^
  method gt in class ComparableExpression<T> cannot be applied to given types
```

문자열 JPQL 이었다면 `where c.name > 100` 이 **컴파일도 되고 실행도 되어** MySQL 이 암묵적 형변환으로 이상한 답을 냈을 것입니다. QueryDSL 은 여기서 멈춥니다.

> 💡 **실무 팁 — `@ManyToOne` 은 점으로 이어집니다**
> `QOrder.order.customer.name` 처럼 연관을 타고 들어갈 수 있습니다. 이것을 **묵시적 조인**이라고 하며, JPQL 에서 자동으로 inner join 이 됩니다.
> 편하지만 **어떤 조인이 생기는지 코드에 안 보인다**는 것이 함정입니다. `leftJoin` 이 필요한 자리에 묵시적 조인이 들어가면 결과가 조용히 줄어듭니다. Step 06 에서 자세히 다룹니다.

---

## 2-3. `build/generated/` 와 `.gitignore`

생성된 Q타입은 `build/` 아래에 있습니다. `build/` 는 Gradle 의 출력 디렉터리이고, 이미 `.gitignore` 에 들어 있는 것이 보통입니다.

```bash
find build/generated -name "Q*.java" | sort
```

**결과**
```
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QCategory.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QCustomer.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QEmployee.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QOrder.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QOrderItem.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QPayment.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QProduct.java
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/QReview.java
```

엔티티 8개에 Q타입 8개. 정확히 대응합니다.

`.gitignore` 는 이렇게 둡니다.

```gitignore
# Gradle
.gradle/
build/

# QueryDSL — build/ 밖에 생성하도록 설정한 경우를 대비한 방어선
src/main/generated/
```

두 번째 줄은 예전 관례에 대한 방어입니다. QueryDSL 4.x 시절 예제들이 생성 위치를 `src/main/generated` 로 바꿔 두는 경우가 많았고, 그 설정이 남아 있는 프로젝트를 만날 수 있습니다.

### 왜 커밋하지 않는가

가끔 "빌드 안 해도 IDE 에서 바로 보이게" 하려고 Q타입을 커밋하는 팀이 있습니다. 하지 마십시오.

- **엔티티와 Q타입이 어긋납니다.** 누군가 엔티티에서 필드를 지우고 Q타입 재생성을 잊으면, 저장소에는 없는 필드를 가리키는 Q타입이 남습니다. 컴파일은 통과하고 실행 시점에 JPQL 파싱이 깨집니다.
- **머지 충돌이 대량으로 납니다.** 두 사람이 각자 엔티티에 필드를 추가하면 Q타입 8개가 동시에 충돌합니다. 그리고 그 충돌은 **손으로 해결할 가치가 전혀 없습니다** — 재생성하면 되니까요.
- **리뷰 노이즈입니다.** PR diff 가 생성 코드로 뒤덮입니다.

> ⚠️ **함정 — 커밋된 Q타입은 재생성돼도 사라지지 않습니다**
> 한 번 커밋된 Q타입이 `src/main/java` 아래에 있으면, APT 가 `build/generated` 에 새 Q타입을 만들어도 **둘 다 클래스패스에 올라갑니다.** 그리고 어느 쪽이 이길지는 컴파일 순서에 달렸습니다.
> 증상은 이렇습니다 — "엔티티에 필드를 추가했는데 Q타입에 안 보인다. 그런데 `clean` 하고 다시 빌드해도 여전히 안 보인다."
> `find . -name "QCustomer.java" -not -path "./build/*"` 로 `build/` 밖에 Q타입이 있는지부터 확인하십시오. 있으면 지우고 `.gitignore` 에 넣으십시오.

---

## 2-4. Q타입이 안 생기는 5가지 원인

이 절이 이 스텝의 핵심입니다. 증상은 항상 똑같습니다.

```
error: cannot find symbol
  symbol:   class QCustomer
  location: package com.example.shop.entity
```

또는 IDE 에서 `QCustomer` 가 빨간 줄로 뜹니다. 하지만 **원인은 다섯 가지이고 처방이 전부 다릅니다.** 순서대로 확인하십시오.

### 원인 1 — `@Entity` 가 없거나 `javax` 를 import 했다

JPA 용 프로세서는 `jakarta.persistence.Entity` 가 붙은 클래스만 봅니다. 두 가지로 어긋날 수 있습니다.

```java
// (a) 애노테이션 자체를 빠뜨림
public class Customer { ... }              // Q타입 안 생김

// (b) 패키지가 javax — Spring Boot 3 / Jakarta EE 9+ 에서는 틀립니다
import javax.persistence.Entity;           // Q타입 안 생김
@Entity
public class Customer { ... }
```

(b)가 특히 고약합니다. IDE 자동완성이 `javax.persistence.Entity` 를 먼저 제안하는 경우가 있고, 오래된 블로그 예제를 복사하면 그대로 들어옵니다. **컴파일은 통과합니다** — `javax.persistence` 클래스가 클래스패스에 있다면요. 그리고 Q타입만 조용히 안 생깁니다.

**확인**

```bash
grep -rn "javax.persistence" src/main/java/
```

**결과** (문제가 있는 경우)
```
src/main/java/com/example/shop/entity/Customer.java:3:import javax.persistence.Entity;
src/main/java/com/example/shop/entity/Customer.java:4:import javax.persistence.Id;
```

**처방**: `javax.persistence` → `jakarta.persistence` 전체 치환.

```bash
grep -rl "javax.persistence" src/main/java/ | xargs sed -i '' 's/javax\.persistence/jakarta.persistence/g'
```

### 원인 2 — `annotationProcessor` 좌표가 틀렸다

Step 01 에서 다룬 그 함정입니다. 다시 정리합니다.

```groovy
// 맞음
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'

// 틀림 — classifier 없음. 기본(POJO) 프로세서가 걸려 @Entity 를 안 봅니다
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12'

// 틀림 — 6.x 에는 jakarta classifier 가 없습니다 (5.x 의 표기)
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jakarta'
```

세 번째 경우는 의존성 해결 자체가 실패해서 그나마 눈에 띕니다.

**결과**
```
* What went wrong:
Execution failed for task ':compileJava'.
> Could not resolve all files for configuration ':annotationProcessor'.
   > Could not find querydsl-apt-6.12-jakarta.jar (io.github.openfeign.querydsl:querydsl-apt:6.12).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/io/github/openfeign/querydsl/querydsl-apt/6.12/querydsl-apt-6.12-jakarta.jar
```

**두 번째 경우가 진짜 함정입니다.** 빌드가 `BUILD SUCCESSFUL` 로 끝나는데 Q타입만 없습니다. 에러 메시지가 아예 없습니다.

**확인**

```bash
./gradlew dependencies --configuration annotationProcessor
```

**결과** (정상)
```
annotationProcessor - Annotation processors and their dependencies for source set 'main'.
+--- io.github.openfeign.querydsl:querydsl-apt:6.12
|    \--- io.github.openfeign.querydsl:querydsl-codegen:6.12
|         \--- io.github.openfeign.querydsl:querydsl-core:6.12
+--- jakarta.persistence:jakarta.persistence-api -> 3.1.0
\--- jakarta.annotation:jakarta.annotation-api -> 2.1.1
```

### 원인 3 — `jakarta.persistence-api` 를 annotationProcessor 에 안 넣었다

이것이 가장 헷갈리는 원인입니다.

프로세서가 도는 시점에는 **프로세서 자신의 클래스패스**만 보입니다. `implementation` 에 있는 것은 안 보입니다. 그런데 QueryDSL 의 JPA 프로세서는 `jakarta.persistence.Entity` 클래스를 로드해야 "이게 엔티티다"를 판단할 수 있습니다.

```groovy
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:6.12:jpa'
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'   // ← 이게 없으면
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'     // ← 이것도
```

빠뜨리면 이런 에러가 납니다.

**결과**
```
> Task :compileJava FAILED
error: java.lang.NoClassDefFoundError: jakarta/persistence/Entity
        at com.querydsl.apt.jpa.JPAAnnotationProcessor.createConfiguration(JPAAnnotationProcessor.java:41)
        at com.querydsl.apt.AbstractQuerydslProcessor.init(AbstractQuerydslProcessor.java:75)
Caused by: java.lang.ClassNotFoundException: jakarta.persistence.Entity
```

`@Generated` 를 붙이려면 `jakarta.annotation-api` 도 필요합니다. 이쪽이 빠지면 `cannot find symbol: class Generated` 가 **생성된 Q타입 안에서** 납니다 — 에러 위치가 `build/generated/...` 라 처음 보면 당황스럽습니다.

> 💡 버전 번호를 안 적은 것은 Spring Boot 의 의존성 관리 플러그인이 채워 주기 때문입니다. Spring Boot 플러그인을 안 쓰는 프로젝트라면 버전을 명시해야 합니다.

### 원인 4 — IDE 가 Gradle 이 아닌 자체 빌더로 컴파일한다

IntelliJ 는 자체 컴파일러로 빌드할 수도, Gradle 에게 위임할 수도 있습니다. **자체 빌더 모드에서는 Gradle 의 `annotationProcessor` 설정을 그대로 따르지 않는 경우**가 있습니다.

증상이 독특합니다.

- 터미널에서 `./gradlew build` → **성공**
- IntelliJ 에서 Run → **`cannot find symbol: class QCustomer`**
- 또는 그 반대

**확인**: `./gradlew clean compileJava` 로 터미널에서만 빌드한 뒤 `find build/generated -name "Q*.java"` 로 파일 존재를 확인하십시오. 파일이 있는데 IDE 만 못 찾는다면 원인 4 입니다. (2-5 에서 설정을 다룹니다.)

### 원인 5 — `clean` 이후 generated 참조가 깨졌다

`./gradlew clean` 은 `build/` 를 통째로 지웁니다. Q타입도 같이 사라집니다. 그 상태에서 IDE 가 인덱스를 갱신하면 모든 Q타입 참조가 빨간 줄이 됩니다.

보통은 `compileJava` 한 번이면 복구됩니다. 복구되지 않는다면 `sourceSets` 설정이 옛날 방식으로 남아 있는지 보십시오.

```groovy
// QueryDSL 4.x 시절 관례 — 요즘은 대부분 불필요하고, 오히려 문제를 만듭니다
def generated = file('src/main/generated')
sourceSets {
    main.java.srcDirs += [ generated ]
}
tasks.withType(JavaCompile) {
    options.generatedSourceOutputDirectory = generated
}
clean.doLast { generated.deleteDir() }
```

Gradle 5+ 는 `build/generated/sources/annotationProcessor/java/main` 을 **자동으로 소스셋에 포함**합니다. 위 설정 없이 그냥 두는 것이 요즘 권장입니다. 위 블록이 있으면서 `clean` 태스크만 빠져 있으면, `src/main/generated` 에 옛 Q타입이 남아 2-3 의 함정과 정확히 같은 상황이 됩니다.

### 진단 순서 요약

| # | 원인 | 확인 명령 | 증상의 특징 |
|---|---|---|---|
| 1 | `@Entity` 없음 / `javax` import | `grep -rn "javax.persistence" src/` | 특정 엔티티의 Q타입만 없음 |
| 2 | apt 좌표 오류 (`:jpa` 누락) | `./gradlew dependencies --configuration annotationProcessor` | **빌드 성공인데** Q타입 전부 없음 |
| 3 | `jakarta.persistence-api` 누락 | 위와 동일 | `NoClassDefFoundError: jakarta/persistence/Entity` |
| 4 | IDE 자체 빌더 | 터미널 빌드와 IDE 빌드 결과 비교 | 터미널은 되는데 IDE 만 안 됨 |
| 5 | 옛 Q타입 잔존 / sourceSets | `find . -name "Q*.java" -not -path "./build/*"` | `clean` 해도 옛 필드가 남아 있음 |

> ⚠️ **함정 — 원인 2 만 유일하게 에러가 없습니다**
> 1, 3, 5 는 어떤 형태로든 에러 메시지를 냅니다. 4 는 터미널/IDE 를 비교하면 금방 드러납니다.
> **2 는 `BUILD SUCCESSFUL` 로 끝납니다.** 프로세서가 아예 등록되지 않았으므로 아무도 불평하지 않습니다. 그래서 "왜 Q타입이 없지?" 를 몇 시간씩 헤매게 됩니다.
> 2-1 의 `--info | grep -i annotation` 으로 **`Processors:` 목록에 `JPAAnnotationProcessor` 가 있는지**를 가장 먼저 보십시오. 이 한 줄이 원인 2 를 즉시 배제해 줍니다.

---

## 2-5. IntelliJ 설정

원인 4 를 막는 설정입니다. 세 군데를 봅니다.

### ① 빌드를 Gradle 에 위임

```
Settings (⌘,)
 → Build, Execution, Deployment
   → Build Tools
     → Gradle
       Build and run using:  Gradle        ← IntelliJ IDEA 가 아니라
       Run tests using:      Gradle        ← 이것도 Gradle
```

이렇게 두면 IDE 의 Run 버튼도 Gradle 의 `compileJava` 를 거치므로, 터미널과 IDE 가 **같은 Q타입**을 봅니다. 빌드가 조금 느려지지만 이 스텝에서 다룬 문제의 대부분이 사라집니다.

### ② 애노테이션 처리 활성화

```
Settings
 → Build, Execution, Deployment
   → Compiler
     → Annotation Processors
       ☑ Enable annotation processing
```

①을 Gradle 로 두었다면 이 설정은 IDE 자체 빌드에만 영향을 줍니다. 그래도 켜 두십시오 — Lombok 도 같은 스위치를 씁니다.

### ③ 생성 소스 디렉터리 확인

프로젝트 뷰에서 `build/generated/sources/annotationProcessor/java/main` 이 **주황색 폴더 아이콘(Generated Sources Root)** 으로 표시되는지 봅니다. 회색 일반 폴더라면 IDE 가 그 안의 소스를 인식하지 않고 있습니다.

수동으로 지정할 수 있습니다.

```
디렉터리 우클릭 → Mark Directory as → Generated Sources Root
```

다만 이것은 증상 치료입니다. Gradle 프로젝트를 다시 임포트(`⌘⇧A` → `Reload All Gradle Projects`)하면 자동으로 잡히는 것이 정상입니다.

### 그래도 안 되면

순서대로 시도합니다.

```bash
./gradlew clean compileJava
```
```
File → Invalidate Caches... → Invalidate and Restart
```

`Invalidate Caches` 는 IDE 인덱스를 통째로 다시 만듭니다. 몇 분 걸리지만, "파일은 분명히 있는데 IDE 만 못 찾는" 상황의 최종 해결책입니다.

---

## 2-6. `new QCustomer("c")` — 별칭

`QCustomer.customer` 는 별칭이 `"customer"` 인 인스턴스 하나일 뿐이라고 했습니다. 그러면 별칭이 다른 인스턴스를 만들 수도 있습니다.

```java
QCustomer c = new QCustomer("c");

List<Customer> result = queryFactory
        .selectFrom(c)
        .where(c.grade.eq(Grade.VIP))
        .fetch();
```

**결과** — 생성 JPQL
```
select c from Customer c where c.grade = ?1
```

**결과** — `hibernate.SQL`
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade=?
```
```
바인딩: [1] VARCHAR = VIP
조회 4건
```

기본 인스턴스를 썼을 때와 비교합니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP))
        .fetch();
```

**결과** — 생성 JPQL
```
select customer from Customer customer where customer.grade = ?1
```

**결과** — `hibernate.SQL`
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.grade=?
```

**JPQL 은 다르고 SQL 은 같습니다.** Hibernate 가 SQL 을 만들 때 자기 규칙(`c1_0`)으로 별칭을 다시 붙이기 때문입니다. JPQL 의 별칭은 Hibernate 에게 넘어가는 순간 사라집니다.

그렇다면 별칭을 바꾸는 것이 무슨 의미가 있을까요. **같은 엔티티가 한 쿼리에 두 번 등장할 때** 필요합니다.

### 셀프 조인

`Employee` 는 `manager` 로 자기 자신을 참조합니다. "사원과 그 관리자의 이름을 함께" 조회하려면 `employees` 테이블이 두 번 필요합니다.

```java
QEmployee e = QEmployee.employee;
QEmployee m = new QEmployee("m");          // 관리자 쪽 별칭

List<Tuple> result = queryFactory
        .select(e.name, m.name)
        .from(e)
        .leftJoin(e.manager, m)
        .orderBy(e.id.asc())
        .fetch();
```

**결과** — 생성 JPQL
```
select employee.name, m.name from Employee employee left join employee.manager as m
order by employee.id asc
```

**결과** — `hibernate.SQL`
```sql
select e1_0.name, m1_0.name
from employees e1_0
left join employees m1_0 on m1_0.employee_id=e1_0.manager_id
order by e1_0.employee_id asc
```
```
조회 18건
+----------+----------+
| 사원     | 관리자   |
+----------+----------+
| 김대표   | NULL     |
| 이본부   | 김대표   |
| 박본부   | 김대표   |
| 최팀장   | 이본부   |
| 정팀장   | 이본부   |
+----------+----------+
```

`employees` 가 `e1_0` 과 `m1_0` 으로 **두 번** 나타납니다. 별칭이 없었다면 Hibernate 는 둘을 구분하지 못합니다.

만약 두 자리에 모두 `QEmployee.employee` 를 썼다면 어떻게 될까요.

```java
// 잘못된 코드 — 두 자리에 같은 인스턴스
List<Tuple> result = queryFactory
        .select(employee.name, employee.name)      // 관리자 이름을 의도했지만
        .from(employee)
        .leftJoin(employee.manager, employee)      // 별칭 충돌
        .fetch();
```

**결과**
```
java.lang.IllegalStateException: Duplicate alias 'employee' in JPQL query
```

또는 조인이 무시되고 사원 이름이 두 번 나오는, 더 나쁜 결과가 됩니다.

### 서브쿼리

Step 07 에서 자세히 다루지만 미리 봅니다. "평균 포인트보다 포인트가 많은 고객"입니다.

```java
QCustomer sub = new QCustomer("sub");

List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.points.gt(
                JPAExpressions.select(sub.points.avg()).from(sub)
        ))
        .fetch();
```

**결과** — `hibernate.SQL`
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.points>(select avg(c2_0.points) from customers c2_0)
```
```
조회 13건
```

바깥은 `c1_0`, 서브쿼리는 `c2_0` 입니다. 별칭을 분리하지 않으면 서브쿼리가 바깥 행을 참조하는 **상관 서브쿼리**로 해석되어 완전히 다른 결과가 나옵니다.

> 💡 **실무 팁 — 별칭 인스턴스는 `static final` 로 뽑아 두십시오**
> `new QCustomer("sub")` 를 메서드 안에서 매번 만들 필요는 없습니다. 리포지토리 클래스 상단에 `private static final QCustomer subCustomer = new QCustomer("sub");` 로 한 번 선언해 두고 재사용하는 것이 관례입니다. Q타입 인스턴스는 **불변이라 스레드 안전**합니다.

---

## 2-7. static import 관례와 이름 충돌

본문에서 우리는 계속 이렇게 씁니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QProduct.product;
```

`QCustomer.customer.name` 대신 `customer.name` 으로 짧게 쓰기 위해서입니다. 쿼리가 길어지면 이 차이가 크게 느껴집니다.

그런데 이 도메인에는 **`Order` 라는 엔티티가 있습니다.** 그리고 QueryDSL 에도 `com.querydsl.core.types.Order` 라는 enum(`ASC`/`DESC`)이 있습니다. 이름이 겹칩니다.

```java
import com.example.shop.entity.Order;          // 우리 엔티티
import com.querydsl.core.types.Order;          // QueryDSL 의 정렬 방향 — 컴파일 에러
```

```
error: a type with the same simple name is already defined by the single-type-import
```

**처방**: 자주 쓰는 쪽을 import 하고 나머지는 FQN 으로 씁니다. 엔티티를 더 자주 쓰므로 엔티티를 import 합니다.

```java
import com.example.shop.entity.Order;

// QueryDSL 의 Order 가 필요한 자리에서는 풀 이름으로
OrderSpecifier<?> spec = new OrderSpecifier<>(
        com.querydsl.core.types.Order.DESC,
        customer.points
);
```

이 충돌은 Step 10(동적 정렬)에서 본격적으로 마주칩니다. 지금 알아 두면 그때 당황하지 않습니다.

변수명 충돌도 있습니다.

```java
import static com.example.shop.entity.QOrder.order;

public void something() {
    Order order = orderRepository.findById(1L).orElseThrow();   // 지역 변수가 static import 를 가림

    queryFactory.selectFrom(order)      // 컴파일 에러 — Order 는 EntityPath 가 아닙니다
            .fetch();
}
```

지역 변수가 static import 된 이름을 **가려 버립니다(shadowing)**. 자바 언어 규칙상 지역 변수가 이깁니다.

**처방**: 지역 변수 이름을 바꾸거나(`foundOrder`), 그 메서드에서만 `QOrder.order` 로 명시합니다. 컴파일 에러로 즉시 드러나므로 위험한 함정은 아닙니다.

---

## 2-8. 엔티티를 고쳤는데 Q타입에 안 보인다

가장 흔한 일상적 사고입니다. 두 갈래로 나뉩니다.

### 갈래 A — 그냥 컴파일을 안 했다

`Customer` 에 `lastLoginAt` 필드를 추가했습니다. IDE 에서 `customer.lastLoginAt` 을 치면 자동완성이 안 됩니다.

당연합니다. **2-1 에서 본 대로 Q타입은 컴파일 중에 생성**되고, 아직 컴파일하지 않았습니다.

```bash
./gradlew compileJava
```

이걸로 끝입니다. IDE 를 Gradle 위임 모드(2-5)로 두었다면 Build 버튼을 눌러도 됩니다.

### 갈래 B — 컴파일했는데도 안 보인다

이쪽이 진짜 문제입니다. 2-3 의 함정 — `build/` 밖에 옛 Q타입이 있는 경우입니다.

```bash
find . -name "QCustomer.java" -not -path "./build/*"
```

**결과** (문제가 있는 경우)
```
./src/main/generated/com/example/shop/entity/QCustomer.java
```

누군가 예전에 커밋했거나, 옛 `sourceSets` 설정이 남아 그쪽에 생성했던 것입니다. 그리고 그 파일은 `clean` 으로도 안 지워집니다 — `build/` 가 아니니까요.

**처방**

```bash
rm -rf src/main/generated
./gradlew clean compileJava
```

그리고 `.gitignore` 에 넣고, `build.gradle` 에 옛 `sourceSets` 블록이 있으면 지웁니다.

> ⚠️ **함정 — 필드를 "지웠을" 때가 더 위험합니다**
> 필드를 추가했는데 Q타입에 없으면 **컴파일 에러**로 즉시 드러납니다. 그러나 필드를 **지웠는데** 옛 Q타입이 살아 있으면, 그 필드를 쓰는 쿼리가 **여전히 컴파일됩니다.**
> 그리고 런타임에 이렇게 터집니다.
> ```
> org.hibernate.query.SemanticException: Could not interpret path expression 'customer.lastLoginAt'
> ```
> 컴파일 타임 안전성을 믿고 있다가 런타임에 배신당하는 유일한 경로입니다. 원인은 QueryDSL 이 아니라 **저장소에 섞여 들어간 생성 코드**입니다.

---

## 2-9. `@QueryEntity` — 엔티티가 아닌 클래스의 Q타입

JPA 엔티티가 아닌 평범한 클래스에도 Q타입을 만들 수 있습니다.

```java
import com.querydsl.core.annotations.QueryEntity;

@QueryEntity
public class SearchLog {
    String keyword;
    LocalDateTime searchedAt;
}
```

다만 이것은 **JPA 프로세서가 아니라 기본 프로세서(`QuerydslAnnotationProcessor`)의 관심사**입니다. 우리 설정은 `:jpa` classifier 를 썼으므로 `@QueryEntity` 는 처리되지 않습니다.

JPA 프로세서에서 `@QueryEntity` 도 함께 처리하려면 프로세서를 추가로 등록해야 하는데, 이 코스의 범위를 벗어납니다. QueryDSL-JPA 만 쓰는 한 **`@Entity` 만 신경 쓰면 됩니다.**

한편 `@QueryProjection` 은 사정이 다릅니다. 이것은 **DTO 에 붙여 `QCustomerDto` 를 생성**하는 애노테이션이고, JPA 프로세서가 함께 처리합니다. Step 05 에서 본격적으로 다룹니다.

```java
public class CustomerDto {
    private final String name;
    private final String city;

    @QueryProjection                       // ← 이건 :jpa 프로세서가 처리합니다
    public CustomerDto(String name, String city) {
        this.name = name;
        this.city = city;
    }
}
```

컴파일하면 `QCustomerDto` 가 생성됩니다. 확인해 봅니다.

```bash
./gradlew compileJava && find build/generated -name "QCustomerDto.java"
```

**결과**
```
build/generated/sources/annotationProcessor/java/main/com/example/shop/dto/QCustomerDto.java
```

> 💡 APT 에는 `querydsl.entityAccessors`, `querydsl.useFields` 같은 옵션들이 있습니다. `compileJava { options.compilerArgs += ['-Aquerydsl.entityAccessors=true'] }` 형태로 넘깁니다.
> 다만 옵션마다 버전별로 동작이 달라진 이력이 있으므로, **필요해지기 전에는 건드리지 마십시오.** 기본값으로 충분합니다. 정말 필요하다면 쓰는 버전의 문서를 확인하고 적용하십시오.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Q타입 생성 시점 | `compileJava` **중**. 별도 태스크가 아님. 엔티티 수정 후 컴파일 필수 |
| 생성 위치 | `build/generated/sources/annotationProcessor/java/main/` |
| `QCustomer.customer` | 별칭이 `"customer"` 인 **미리 만들어 둔 인스턴스**. 마법 아님 |
| Path 타입 | 자바 타입 → `StringPath`/`NumberPath`/`EnumPath`/... 로 치환. 이것이 타입 안전의 실체 |
| `@Generated` | 정적 분석 도구가 생성 코드를 커버리지에서 제외하는 표식 |
| 커밋 금지 | 엔티티와 어긋남 · 머지 충돌 · 리뷰 노이즈. `build/` 밖 Q타입은 **`clean` 으로도 안 지워짐** |
| 원인 1 | `@Entity` 누락 또는 `javax.persistence` import → 해당 Q타입만 없음 |
| 원인 2 | apt 좌표 `:jpa` 누락 → **빌드 성공인데 Q타입 전부 없음.** 유일하게 에러가 없는 원인 |
| 원인 3 | `jakarta.persistence-api` 를 annotationProcessor 에 미등록 → `NoClassDefFoundError` |
| 원인 4 | IDE 자체 빌더 → 터미널은 되는데 IDE 만 실패 |
| 원인 5 | `build/` 밖 옛 Q타입 잔존 → `clean` 해도 옛 필드가 남음 |
| 진단 첫걸음 | `./gradlew compileJava --info \| grep -i annotation` 으로 `JPAAnnotationProcessor` 등록 확인 |
| `new QCustomer("c")` | 셀프 조인 · 서브쿼리처럼 **같은 엔티티가 두 번 등장**할 때 필수 |
| 별칭과 SQL | JPQL 별칭은 바뀌지만 Hibernate 가 `c1_0` 으로 다시 붙이므로 **SQL 은 동일** |
| 이름 충돌 | 도메인 `Order` vs `com.querydsl.core.types.Order` → 한쪽을 FQN 으로 (Step 10 에서 재등장) |
| 필드 삭제의 위험 | 옛 Q타입이 살아 있으면 컴파일은 통과하고 **런타임에 `SemanticException`** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. `QProduct.java` 를 직접 열어 `price`, `stock`, `status`, `category`, `reviews` 필드가 각각 어떤 Path 타입으로 생성됐는지 확인하고, 주석의 빈칸을 채우기
2. `customer.points` 에 `contains("100")` 을, `customer.name` 에 `goe(5)` 를 호출하는 코드를 작성해 **각각 어떤 컴파일 에러가 나는지** 기록하기 (주석 처리된 코드의 주석을 푸는 방식)
3. `new QEmployee("m")` 으로 셀프 조인을 작성해 사원-관리자 이름 쌍 18건을 조회하고, 생성 SQL 에 `employees` 가 **몇 번** 등장하는지 확인하기
4. `QCustomer.customer` 와 `new QCustomer("sub")` 로 같은 조건의 쿼리를 각각 실행해 **JPQL 은 다르고 SQL 은 같다**는 것을 로그로 대조하기
5. `Customer` 엔티티에 필드를 하나 추가한 뒤 **컴파일하지 않고** Q타입에서 그 필드를 찾아보고, 컴파일 후 다시 확인하기 (2-8 갈래 A 재현)
6. `build.gradle` 의 `annotationProcessor` 에서 `:jpa` classifier 를 제거하고 `./gradlew clean compileJava` 를 실행해 **빌드가 성공하는데 Q타입이 없는** 상태를 재현한 뒤, `--info` 로그에서 `Processors:` 줄이 어떻게 달라지는지 기록하기

---

## 다음 단계

이제 Q타입이 무엇이고 어디서 오는지 알았습니다. `customer.name` 이 `StringPath` 라는 것, 그래서 `contains()` 는 되고 `goe(5)` 는 안 된다는 것까지 봤습니다.

다음 스텝에서는 그 Path 들을 실제 쿼리로 조립합니다. `select` / `from` / `where` 로 뼈대를 세우고, `fetch()` 계열 메서드 다섯 개를 구분해서 씁니다. 특히 `fetchOne()` 이 결과가 2건이면 예외를 던지고 0건이면 조용히 `null` 을 돌려주는 **비대칭 동작**이, 어떻게 엉뚱한 위치의 NPE 로 이어지는지 재현합니다.

→ [Step 03 — 기본 조회](../step-03-basic-query/)

---

## 실습 파일

이 스텝은 다른 스텝과 성격이 조금 다릅니다. 쿼리를 작성하는 것보다 **빌드 산출물을 확인하고 고장을 재현하는** 비중이 큽니다. `Practice.java` 의 절반은 쿼리이고 절반은 파일 시스템 확인 코드입니다.

세 파일 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스로, Step 03 이후와 형태를 맞췄습니다.

### Practice.java

본문(2-1 ~ 2-9)의 예제를 절 번호 주석과 함께 담았습니다.

- `[2-2]` 의 `path_타입을_확인한다()` 는 `customer.name.getClass().getSimpleName()` 을 찍습니다. `StringPath`, `NumberPath`, `EnumPath` 가 콘솔에 그대로 나오는 것을 보는 것이 목적입니다.
- `[2-2]` 의 컴파일 에러 예제는 **주석 처리돼 있습니다.** 주석을 풀면 컴파일이 실패하는 것이 정상 동작입니다. 확인한 뒤 다시 주석으로 되돌리십시오.
- `[2-3]` 의 `생성된_Q타입_파일을_센다()` 는 `Files.walk` 로 `build/generated` 를 뒤져 `Q*.java` 개수를 셉니다. 8 이 나와야 합니다. 이 테스트는 **`./gradlew test` 로 실행할 때만 의미가 있습니다** — IDE 에서 직접 실행하면 작업 디렉터리가 달라 경로를 못 찾을 수 있습니다.
- `[2-6]` 의 `별칭이_달라도_SQL은_같다()` 는 두 쿼리를 연달아 실행하고 각각의 `toString()`(JPQL)을 콘솔에 찍습니다. **JPQL 두 줄은 다르고, 그 아래 hibernate.SQL 두 줄은 같다**는 것을 눈으로 대조하는 것이 전부입니다.
- `[2-6]` 의 셀프 조인은 결과 18건을 사원-관리자 표로 출력합니다. `김대표` 의 관리자가 `null` 인 것이 정상입니다.
- `[2-8]` 의 `옛_Q타입이_있는지_확인한다()` 는 프로젝트 루트에서 `build/` 를 제외하고 `Q*.java` 를 찾습니다. **0개가 나와야 정상**이며, 하나라도 나오면 2-3 의 함정 상황입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 이 스텝의 문제는 코드를 많이 쓰지 않습니다. **관찰하고 주석에 기록하는** 문제가 절반입니다.

- **문제 1** 은 코드를 한 줄도 안 씁니다. `QProduct.java` 를 열어 보고 주석의 `// price → ______Path` 빈칸을 채우십시오. 파일 경로가 주석에 적혀 있습니다.
- **문제 2** 는 **일부러 컴파일 에러를 내는** 문제입니다. 주석 처리된 두 줄의 주석을 풀고, 컴파일 에러 메시지를 복사해 아래 주석 블록에 붙여 넣은 뒤, 다시 주석 처리하십시오.
- **문제 3·4** 는 실제 쿼리를 작성합니다. 문제 4 는 `assertThat` 으로 두 JPQL 문자열이 **다르다**는 것을 단언하는 것이 요점입니다.
- **문제 5·6** 은 빌드를 건드리는 문제라 테스트 코드로 자동화하기 어렵습니다. 수행 절차가 주석으로 단계별로 적혀 있고, 관찰 결과를 기록할 빈 주석 자리가 준비돼 있습니다. **문제 6 을 수행한 뒤에는 반드시 `build.gradle` 을 원상복구**하십시오 — 안 그러면 이후 스텝이 전부 컴파일되지 않습니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과 해설입니다. 관찰형 문제가 많은 만큼 **정답 코드보다 주석이 훨씬 깁니다.**

- **정답 1** 은 `QProduct` 의 필드 전체를 표로 정리한 주석입니다. 특히 `category` 가 `QCategory` 로, `reviews` 가 `ListPath<Review, QReview>` 로 생성된다는 점 — 즉 `@ManyToOne` 과 `@OneToMany` 가 전혀 다른 것으로 매핑된다는 점을 강조합니다.
- **정답 2** 는 두 컴파일 에러 메시지 전문과, **왜 이것이 좋은 소식인지**를 설명합니다. 같은 실수를 문자열 JPQL 로 했을 때 MySQL 이 어떤 암묵적 형변환을 해서 어떤 엉뚱한 답을 내는지까지 주석으로 적어 두었습니다.
- **정답 3** 의 요점은 SQL 에 `employees` 가 **두 번** 나온다는 것입니다. `e1_0` 과 `m1_0`. 별칭 없이 같은 인스턴스를 두 번 썼을 때 나는 `Duplicate alias` 예외도 주석에 적어 두었습니다.
- **정답 4** 는 이 스텝에서 가장 중요한 정답입니다. "JPQL 별칭은 Hibernate 에게 넘어가는 순간 사라진다"는 것을 두 로그를 나란히 붙여 보여줍니다. 그래서 **별칭을 바꾸는 것은 SQL 을 바꾸려는 목적이 아니라, 같은 엔티티를 두 번 쓰기 위한 수단**이라는 결론으로 끝냅니다.
- **정답 5** 는 "컴파일하지 않으면 아무 일도 안 일어난다"는 당연한 사실을 확인하는 것이지만, 주석에서는 그 당연함이 **왜 실무에서 그렇게 자주 사고를 내는지** — 파일 저장과 컴파일을 구분하지 않는 습관 — 를 짚습니다.
- **정답 6** 은 `--info` 로그의 `Processors:` 줄을 정상/비정상 두 경우로 나란히 붙여 두었습니다. 정상에는 `JPAAnnotationProcessor` 가 있고 비정상에는 Lombok 만 있습니다. **이 한 줄의 차이가 몇 시간을 절약합니다.**
- 파일 맨 아래 `// 보너스` 구간에 2-4 의 5가지 원인을 진단 순서대로 정리한 체크리스트를 주석으로 담아 두었습니다. 실무에서 Q타입 문제를 만났을 때 이 부분만 다시 열어 보면 됩니다.

```java file="./Solution.java"
```
