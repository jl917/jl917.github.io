# 실습 프로젝트 셋업

> **학습 목표**
> - QueryDSL 6.12 (`io.github.openfeign.querydsl`) 좌표를 5.x 와 구분해 정확히 쓴다
> - Gradle annotationProcessor 로 Q타입이 생성되는 위치를 확인한다
> - `shop` 스키마에 대응하는 JPA 엔티티 8개를 `ddl-auto: validate` 로 검증한다
> - 생성 SQL 과 바인딩 파라미터가 콘솔에 찍히도록 로깅을 설정한다
> - `JPAQueryFactory` 로 첫 쿼리를 날려 30건이 조회되는 것을 확인한다
>
> **선행 조건**: Java 21, Docker, [MySQL 8 코스](../../mysql8/)의 `docker/` 와 `sql/` 디렉터리
> **예상 소요**: 40분

이 문서를 끝내면 [Step 01](../step-01-setup/) 부터 [Step 14](../step-14-performance/) 까지의
모든 실습 코드를 그대로 복사해 실행할 수 있는 프로젝트가 만들어집니다.
이 디렉터리에는 실습 Java 파일을 두지 않습니다. 각 스텝 디렉터리의 `Practice.java` 를 씁니다.

---

## 0-1. 프로젝트 구조

프로젝트 루트는 어디든 상관없습니다. 이 문서는 `~/querydsl6-shop` 을 기준으로 씁니다.

```
querydsl6-shop/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat / gradle/
├── docker/                                   ← (선택) 이 프로젝트 안에 MySQL 을 둘 경우
│   └── docker-compose.yml
└── src
    ├── main
    │   ├── java/com/example/shop
    │   │   ├── ShopApplication.java
    │   │   ├── config/
    │   │   │   └── QuerydslConfig.java        ← JPAQueryFactory 빈
    │   │   └── entity/
    │   │       ├── Customer.java
    │   │       ├── Category.java
    │   │       ├── Product.java
    │   │       ├── Order.java
    │   │       ├── OrderItem.java
    │   │       ├── Payment.java
    │   │       ├── Review.java
    │   │       ├── Employee.java
    │   │       ├── Grade.java
    │   │       ├── OrderStatus.java
    │   │       ├── ProductStatus.java
    │   │       ├── PaymentMethod.java
    │   │       └── PaymentStatus.java
    │   └── resources
    │       └── application.yml
    └── test
        └── java/com/example/shop
            ├── SetupVerifyTest.java           ← 0-8 절에서 만듭니다
            ├── step01/                        ← 각 스텝의 Practice/Exercise/Solution
            ├── step02/
            └── ...
```

Q타입은 소스 디렉터리에 만들지 않습니다. 빌드 산출물입니다.

```
build/generated/sources/annotationProcessor/java/main/com/example/shop/entity/
├── QCategory.java
├── QCustomer.java
├── QEmployee.java
├── QOrder.java
├── QOrderItem.java
├── QPayment.java
├── QProduct.java
└── QReview.java
```

> 💡 **실무 팁 — Q타입을 절대 커밋하지 마십시오**
> `build/` 는 이미 `.gitignore` 대상입니다. Q타입을 `src/main/generated` 로 빼서
> 커밋하는 설정을 종종 보는데, 엔티티와 Q타입이 어긋난 채로 리뷰를 통과하는 사고가 납니다.
> 생성물은 생성물 자리에 둡니다.

---

## 0-2. `build.gradle` 전문

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.5'
    id 'io.spring.dependency-management' version '1.1.4'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

ext {
    querydslVersion = '6.12'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    // ── QueryDSL 6.x (OpenFeign 포크) ───────────────────────────────
    implementation "io.github.openfeign.querydsl:querydsl-jpa:${querydslVersion}"
    implementation "io.github.openfeign.querydsl:querydsl-core:${querydslVersion}"
    annotationProcessor "io.github.openfeign.querydsl:querydsl-apt:${querydslVersion}:jpa"
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
    // ───────────────────────────────────────────────────────────────

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testCompileOnly 'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'

    runtimeOnly 'com.mysql:mysql-connector-j'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

// ── Q타입 생성 경로 설정 ─────────────────────────────────────────────
def generatedDir = "$buildDir/generated/sources/annotationProcessor/java/main"

sourceSets {
    main {
        java {
            srcDirs += generatedDir
        }
    }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.generatedSourceOutputDirectory = file(generatedDir)
}

// clean 시 생성된 Q타입도 함께 삭제
clean {
    delete file(generatedDir)
}

tasks.named('test') {
    useJUnitPlatform()
}
```

`settings.gradle` 은 한 줄입니다.

```groovy
rootProject.name = 'querydsl6-shop'
```

### 5.x 좌표와의 대조

가장 많이 틀리는 지점입니다. **5.x 는 두 아티팩트 모두 `:jakarta` classifier 를 붙였고,
6.x 는 `querydsl-apt` 에만 `:jpa` classifier 를 붙입니다.**

| 항목 | 5.x (`com.querydsl`) | 6.x (`io.github.openfeign.querydsl`) |
|---|---|---|
| jpa | `com.querydsl:querydsl-jpa:5.0.0:jakarta` | `io.github.openfeign.querydsl:querydsl-jpa:6.12` |
| core | `com.querydsl:querydsl-core:5.0.0` | `io.github.openfeign.querydsl:querydsl-core:6.12` |
| apt | `com.querydsl:querydsl-apt:5.0.0:jakarta` | `io.github.openfeign.querydsl:querydsl-apt:6.12:jpa` |
| classifier 규칙 | jpa **있음**, apt **있음** | jpa **없음**, apt **`:jpa`** |
| persistence API | javax 기본 (jakarta 는 classifier) | jakarta 네이티브 |
| Hibernate 6 | 미지원 | 6.4 통합 |

> ⚠️ **함정 — 5.x 좌표를 그대로 옮겼을 때**
> `io.github.openfeign.querydsl:querydsl-jpa:6.12:jakarta` 처럼 존재하지 않는 classifier 를 쓰면
> 의존성 해석 단계에서 바로 실패하므로 오히려 안전합니다.
> 진짜 위험한 건 groupId 만 바꾸지 않고 **`com.querydsl:querydsl-jpa:5.0.0` (classifier 없음)** 을
> 쓰는 경우입니다. 이건 javax 를 참조하는 아티팩트라 **빌드가 성공합니다.**
> 그리고 애플리케이션 기동 중에 이렇게 죽습니다.
>
> ```
> Caused by: java.lang.NoClassDefFoundError: javax/persistence/Entity
>     at com.querydsl.jpa.JPQLTemplates.<clinit>(JPQLTemplates.java:47)
>     ...
> Caused by: java.lang.ClassNotFoundException: javax.persistence.Entity
>     at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
> ```
>
> 스택트레이스에 `javax.` 가 보이면 100% 좌표 문제입니다.
> `./gradlew dependencies --configuration runtimeClasspath | grep querydsl` 로 확인하십시오.

**결과** — 좌표가 올바를 때의 출력

```
$ ./gradlew dependencies --configuration runtimeClasspath | grep querydsl
+--- io.github.openfeign.querydsl:querydsl-jpa:6.12
|    +--- io.github.openfeign.querydsl:querydsl-core:6.12
+--- io.github.openfeign.querydsl:querydsl-core:6.12 (*)
```

`com.querydsl` 이 한 줄이라도 섞여 있으면 잘못된 것입니다.

---

## 0-3. docker compose

MySQL 8 을 `127.0.0.1:3307` 에 띄웁니다. 방법은 두 가지입니다.

### 방법 A — MySQL8 코스의 `docker/` 를 재사용 (권장)

이 코스는 MySQL8 코스와 **완전히 같은 `shop` 스키마와 데이터**를 씁니다.
이미 그 컨테이너를 띄워 본 적이 있다면 새로 만들 이유가 없습니다.

```bash
cd docs/reference/mysql8/docker
docker compose up -d
docker compose ps
```

**결과**
```
NAME           IMAGE       STATUS                   PORTS
learn-mysql8   mysql:8.0   Up 12 seconds (healthy)  33060/tcp, 0.0.0.0:3307->3306/tcp
```

`(healthy)` 가 뜰 때까지 기다립니다. 보통 10~20초입니다.
`(health: starting)` 상태에서 접속하면 커넥션이 거부됩니다.

### 방법 B — 이 프로젝트 안에 새로 두기

MySQL8 코스 파일을 건드리고 싶지 않다면 `querydsl6-shop/docker/docker-compose.yml` 을 만듭니다.

```yaml
name: querydsl6-shop

services:
  mysql:
    image: mysql:8.0
    container_name: querydsl6-mysql8
    restart: unless-stopped
    ports:
      - "3307:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_DATABASE: shop
      MYSQL_USER: learner
      MYSQL_PASSWORD: learn1234
      TZ: Asia/Seoul
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    volumes:
      - querydsl6-mysql8-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-proot1234"]
      interval: 5s
      timeout: 3s
      retries: 20

volumes:
  querydsl6-mysql8-data:
```

```bash
cd ~/querydsl6-shop/docker
docker compose up -d
```

> ⚠️ **함정 — 두 컨테이너가 같은 3307 을 잡는 경우**
> 방법 A 와 B 를 둘 다 실행하면 두 번째가 `port is already allocated` 로 실패합니다.
> 하나만 쓰십시오. 이미 떠 있는 것을 확인하려면 `docker ps --filter publish=3307`.

컨테이너 상태가 꼬이면 언제든 초기화할 수 있습니다.

```bash
docker compose down -v && docker compose up -d
```

볼륨까지 지우므로 데이터가 사라집니다. 0-4 절을 다시 실행하면 됩니다.

---

## 0-4. `shop` 스키마 적재

MySQL8 코스의 `sql/install.sh` 를 그대로 씁니다. 이 코스용 별도 스크립트는 없습니다.

```bash
cd docs/reference/mysql8/sql
./install.sh
```

**결과**
```
▶ MySQL 접속 확인 (127.0.0.1:3307)
+---------------+
| mysql_version |
+---------------+
| 8.0.46        |
+---------------+
▶ 01_schema.sql
▶ 02_seed_master.sql
▶ 03_seed_orders.sql
✅ 완료. 접속:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop
```

`--big` 옵션은 100만 행짜리 `access_logs` 를 추가로 만듭니다.
이 코스에서는 [Step 14](../step-14-performance/) 에서만 쓰므로 지금은 생략해도 됩니다.

적재가 제대로 됐는지 행 수로 검증합니다. 이 숫자는 교재 전체에서 고정입니다.

```sql
SELECT 'categories'  AS t, COUNT(*) AS rows_ FROM categories
UNION ALL SELECT 'customers',  COUNT(*) FROM customers
UNION ALL SELECT 'products',   COUNT(*) FROM products
UNION ALL SELECT 'orders',     COUNT(*) FROM orders
UNION ALL SELECT 'order_items',COUNT(*) FROM order_items
UNION ALL SELECT 'payments',   COUNT(*) FROM payments
UNION ALL SELECT 'reviews',    COUNT(*) FROM reviews
UNION ALL SELECT 'employees',  COUNT(*) FROM employees;
```

**결과**
```
+-------------+-------+
| t           | rows_ |
+-------------+-------+
| categories  |    17 |
| customers   |    30 |
| products    |    40 |
| orders      |   600 |
| order_items |  1200 |
| payments    |   540 |
| reviews     |    80 |
| employees   |    18 |
+-------------+-------+
8 rows in set (0.01 sec)
```

하나라도 다르면 `./install.sh` 를 다시 실행하십시오. 스크립트는 재실행 가능합니다.

교재의 함정 재료가 제대로 심어졌는지도 확인합니다.

```sql
SELECT
  (SELECT COUNT(*) FROM customers WHERE phone IS NULL)          AS phone_null,
  (SELECT COUNT(*) FROM customers WHERE grade = 'VIP')          AS vip,
  (SELECT COUNT(*) FROM orders    WHERE status = 'PENDING')     AS pending,
  (SELECT COUNT(*) FROM products p
    WHERE NOT EXISTS (SELECT 1 FROM reviews r
                      WHERE r.product_id = p.product_id))       AS no_review;
```

**결과**
```
+------------+-----+---------+-----------+
| phone_null | vip | pending | no_review |
+------------+-----+---------+-----------+
|          3 |   4 |      60 |        24 |
+------------+-----+---------+-----------+
```

전화번호 NULL 3명, VIP 4명, 결제 없는 PENDING 주문 60건, 후기 없는 상품 24개.
이 네 숫자는 코스 내내 반복해서 등장합니다.

---

## 0-5. `application.yml`

```yaml
spring:
  application:
    name: querydsl6-shop

  datasource:
    url: jdbc:mysql://127.0.0.1:3307/shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: learner
    password: learn1234
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
        highlight_sql: true
        use_sql_comments: false
        default_batch_fetch_size: 100

logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

각 설정이 왜 필요한지는 이 코스의 학습 방식과 직결됩니다.

| 설정 | 값 | 왜 필요한가 |
|---|---|---|
| `ddl-auto` | `validate` | 스키마를 **절대 건드리지 않고**, 엔티티 매핑이 기존 테이블과 맞는지만 검증합니다. `update` 로 두면 매핑 실수가 스키마를 조용히 바꿔 버립니다 |
| `open-in-view` | `false` | 뷰 렌더링 시점까지 영속성 컨텍스트가 열려 있으면 지연 로딩이 어디서 터지는지 흐려집니다. 끄면 N+1 이 정직하게 드러납니다 |
| `format_sql` | `true` | 한 줄짜리 SQL 은 읽을 수 없습니다. 이 코스는 SQL 을 읽는 코스입니다 |
| `highlight_sql` | `true` | 콘솔에서 SQL 키워드에 색이 들어갑니다. IDE 콘솔에서 예제 SQL 을 찾기 쉬워집니다 |
| `default_batch_fetch_size` | `100` | 지연 로딩 N+1 을 `IN (...)` 배치로 줄입니다. [Step 14](../step-14-performance/) 에서 이 값을 켜고 끄며 실측합니다 |
| `org.hibernate.SQL` | `debug` | **생성 SQL 이 콘솔에 찍힙니다.** 이게 없으면 이 코스는 성립하지 않습니다 |
| `org.hibernate.orm.jdbc.bind` | `trace` | `?` 자리에 실제로 무슨 값이 들어갔는지 보여줍니다. Hibernate 6 에서 바뀐 로거 이름입니다 |

> ⚠️ **함정 — Hibernate 5 시절의 바인딩 로거 이름**
> Hibernate 5 는 `org.hibernate.type.descriptor.sql.BasicBinder` 였습니다.
> Hibernate 6 은 **`org.hibernate.orm.jdbc.bind`** 입니다.
> 옛 이름을 쓰면 설정은 정상 적용된 것처럼 보이는데 바인딩 값이 안 찍힙니다.
> 아무 에러도 나지 않으므로 "원래 안 나오나 보다" 하고 넘어가기 쉽습니다.

**결과** — 로깅이 제대로 켜졌을 때의 콘솔

```sql
Hibernate:
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
        c1_0.grade = ?
```
```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [VIP]
```

`binding parameter` 줄이 안 보이면 로거 이름을 다시 확인하십시오.

---

## 0-6. 엔티티 전체

8개 엔티티와 5개 enum 을 모두 `com.example.shop.entity` 패키지에 만듭니다.
규칙은 전부 동일합니다.

- Lombok `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
  (`@Setter` 는 붙이지 않습니다. 이 코스는 조회 중심이고, 벌크 연산은 [Step 11](../step-11-bulk-operations/) 에서 따로 다룹니다)
- **모든 연관은 `FetchType.LAZY`**
- enum 은 `@Enumerated(EnumType.STRING)` — `ORDINAL` 은 절대 쓰지 않습니다
- 컬럼명을 `@Column(name = "...")` 로 **명시**합니다
- 금액은 전부 `BigDecimal`

### enum 5개

```java
package com.example.shop.entity;

public enum Grade {
    BRONZE, SILVER, GOLD, VIP
}
```

```java
package com.example.shop.entity;

public enum OrderStatus {
    PENDING, PAID, SHIPPED, DELIVERED, CANCELLED
}
```

```java
package com.example.shop.entity;

public enum ProductStatus {
    ON_SALE, SOLD_OUT, HIDDEN
}
```

```java
package com.example.shop.entity;

public enum PaymentMethod {
    CARD, BANK, POINT, MOBILE
}
```

```java
package com.example.shop.entity;

public enum PaymentStatus {
    DONE, REFUNDED
}
```

DB 의 ENUM 컬럼 순서와 자바 enum 상수 순서를 일부러 맞춰 두었습니다.
하지만 `@Enumerated(EnumType.STRING)` 을 쓰므로 순서는 매핑에 영향을 주지 않습니다.
순서에 의존하지 않는 것이 핵심입니다.

### `Customer`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Long id;

    @Column(name = "email", nullable = false, length = 120, unique = true)
    private String email;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** NULL 인 고객이 3명 있습니다. isNull() 실습의 재료입니다. */
    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false, length = 10)
    private Grade grade;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "city", nullable = false, length = 30)
    private String city;

    @Column(name = "points", nullable = false)
    private Integer points;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();
}
```

### `Category`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;

    /** 자기참조. NULL 이면 최상위(대분류 5개). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    private List<Category> children = new ArrayList<>();

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;
}
```

### `Product`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal cost;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ProductStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private List<Review> reviews = new ArrayList<>();
}
```

`products` 테이블에는 `attrs` JSON 컬럼이 있지만 **엔티티에 매핑하지 않습니다.**
QueryDSL 로 JSON 을 다루는 것은 이 코스의 범위 밖입니다.
매핑하지 않아도 `ddl-auto: validate` 는 통과합니다 — 그 이유는 아래 함정 블록에서 설명합니다.

### `Order`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "shipping_city", nullable = false, length = 30)
    private String shippingCity;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<OrderItem> orderItems = new ArrayList<>();

    /** PENDING 주문 60건은 이 리스트가 비어 있습니다. */
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<Payment> payments = new ArrayList<>();
}
```

> 💡 `Order` 는 SQL 예약어입니다. `@Table(name = "orders")` 로 실제 테이블명을 명시했기 때문에
> 문제가 없습니다. 클래스명만 보고 테이블을 추론하게 두면 `order` 로 생성돼 터집니다.

### `OrderItem`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 주문 시점의 가격 스냅샷. product.price 와 다를 수 있습니다. */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;
}
```

### `Payment`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 10)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PaymentStatus status;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;
}
```

### `Review`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** DB 는 TINYINT UNSIGNED, CHECK (rating BETWEEN 1 AND 5). */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "title", length = 100)
    private String title;

    @Lob
    @Column(name = "body")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

### `Employee`

```java
package com.example.shop.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "employees")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "employee_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 자기참조. NULL 이면 최상위(대표). 4단계 조직도입니다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    @OneToMany(mappedBy = "manager", fetch = FetchType.LAZY)
    private List<Employee> subordinates = new ArrayList<>();

    @Column(name = "dept", nullable = false, length = 30)
    private String dept;

    @Column(name = "position", nullable = false, length = 30)
    private String position;

    @Column(name = "salary", nullable = false, precision = 10, scale = 2)
    private BigDecimal salary;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;
}
```

### `QuerydslConfig`

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

> 📌 패키지가 `com.querydsl.jpa.impl` 인 것에 주목하십시오.
> groupId 는 `io.github.openfeign.querydsl` 로 바뀌었지만
> **자바 패키지명은 `com.querydsl` 그대로**입니다. import 문은 5.x 와 동일합니다.
> 이 비대칭이 좌표 혼동을 더 키웁니다.

> ⚠️ **함정 — `ddl-auto: validate` 가 잡아주는 것과 못 잡는 것**
>
> `Order.totalAmount` 에서 `@Column(name = "total_amount")` 를 지우면 어떻게 될까요.
> **아무 일도 일어나지 않습니다. 그대로 통과합니다.**
> Spring Boot 의 기본 physical naming strategy 인 `CamelCaseToUnderscoresNamingStrategy` 가
> `totalAmount` → `total_amount` 로 변환해 주기 때문입니다.
>
> 문제는 이 자동 변환이 **언제 통하고 언제 안 통하는지**가 눈에 안 보인다는 점입니다.
>
> | 자바 필드 | 자동 변환 결과 | DB 컬럼 | validate |
> |---|---|---|---|
> | `totalAmount` | `total_amount` | `total_amount` | 통과 |
> | `shippingCity` | `shipping_city` | `shipping_city` | 통과 |
> | `id` | `id` | `order_id` | **실패** |
> | `orderDate` → `date` 로 개명 | `date` | `order_date` | **실패** |
>
> PK 는 거의 항상 실패합니다. `customers.customer_id` 처럼 테이블명 접두사가 붙어 있기 때문입니다.
> 그래서 이 코스는 **모든 컬럼에 `@Column(name)` 을 명시**합니다.
> 반쯤 명시하고 반쯤 규칙에 맡기면, 어느 쪽 규칙이 적용됐는지 매번 추론해야 합니다.
>
> 반대로 `validate` 가 **못 잡는 것**도 알아 두십시오.
> `products.attrs` 처럼 **DB 에만 있고 엔티티에 없는 컬럼은 검사하지 않습니다.**
> `validate` 는 "엔티티가 요구하는 컬럼이 DB 에 있는가"만 봅니다. 반대 방향은 보지 않습니다.
>
> **결과** — 매핑이 틀렸을 때의 실패 메시지
> ```
> Caused by: org.hibernate.tool.schema.spi.SchemaManagementException:
>     Schema-validation: missing column [id] in table [orders]
>     at org.hibernate.tool.schema.internal.AbstractSchemaValidator
>        .validateTable(AbstractSchemaValidator.java:135)
> ```
> `missing column [X] in table [Y]` 는 "DB 에 없다"가 아니라
> **"엔티티가 X 를 요구하는데 DB 에는 없다"** 는 뜻입니다. 고칠 곳은 대개 엔티티입니다.

---

## 0-7. Q타입 생성 확인

엔티티를 다 만들었으면 컴파일합니다.

```bash
cd ~/querydsl6-shop
./gradlew clean compileJava
```

**결과**
```
> Task :clean
> Task :compileJava

BUILD SUCCESSFUL in 6s
2 actionable tasks: 2 executed
```

Q타입이 생겼는지 확인합니다.

```bash
find build/generated -name 'Q*.java' | sort
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

8개입니다. enum 은 `@Entity` 가 아니므로 Q타입이 생기지 않습니다.

생성된 `QCustomer.java` 를 열어 봅니다. Q타입이 마법이 아니라
**그냥 자바 클래스**라는 것을 확인하는 것이 중요합니다.

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

    private static final long serialVersionUID = -1234567890L;

    public static final QCustomer customer = new QCustomer("customer");

    public final StringPath city = createString("city");

    public final DateTimePath<java.time.LocalDateTime> createdAt = createDateTime("createdAt", java.time.LocalDateTime.class);

    public final StringPath email = createString("email");

    public final EnumPath<Grade> grade = createEnum("grade", Grade.class);

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final StringPath name = createString("name");

    public final ListPath<Order, QOrder> orders = this.<Order, QOrder>createList("orders", Order.class, QOrder.class, PathInits.DIRECT2);

    public final StringPath phone = createString("phone");

    public final NumberPath<Integer> points = createNumber("points", Integer.class);

    public QCustomer(String variable) {
        super(Customer.class, forVariable(variable));
    }

    // ... 생성자 오버로드 생략
}
```

읽어 둘 지점이 셋 있습니다.

- `public static final QCustomer customer` — 이것이 우리가
  `import static com.example.shop.entity.QCustomer.customer;` 로 가져다 쓰는 **기본 인스턴스**입니다.
- 필드 타입이 `StringPath`, `NumberPath<Integer>`, `EnumPath<Grade>` 로 **갈라져 있습니다.**
  `customer.name.gt(...)` 가 컴파일 에러인 이유가 여기 있습니다. `StringPath` 에는 `gt` 가 없습니다.
- **필드명은 자바 필드명(`createdAt`)이지 컬럼명(`created_at`)이 아닙니다.**
  QueryDSL 은 JPQL 을 만들고, 컬럼명 변환은 Hibernate 가 합니다.

Q타입이 안 생기는 경우의 대응은 0-9 절에 정리했습니다.

---

## 0-8. 첫 실행 확인

`src/test/java/com/example/shop/SetupVerifyTest.java` 를 만듭니다.

```java
package com.example.shop;

import com.example.shop.entity.Grade;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.shop.entity.QCustomer.customer;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SetupVerifyTest {

    @Autowired
    JPAQueryFactory queryFactory;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("고객은 30명이다")
    void customerCount() {
        List<com.example.shop.entity.Customer> all = queryFactory
                .selectFrom(customer)
                .fetch();

        assertThat(all).hasSize(30);
    }

    @Test
    @DisplayName("VIP 고객은 4명이다")
    void vipCount() {
        List<String> names = queryFactory
                .select(customer.name)
                .from(customer)
                .where(customer.grade.eq(Grade.VIP))
                .fetch();

        assertThat(names).hasSize(4);
    }
}
```

`import static com.example.shop.entity.QCustomer.customer;` — 이 코스는 Q타입 기본 인스턴스를
**항상 static import** 로 가져다 씁니다. 본문 예제도 전부 이 관례를 따릅니다.

```bash
./gradlew test --tests 'com.example.shop.SetupVerifyTest'
```

**결과** — 콘솔에 찍힌 SQL

```sql
Hibernate:
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
```
```
BUILD SUCCESSFUL in 9s
```

두 번째 테스트의 SQL 입니다.

```sql
Hibernate:
    select
        c1_0.name
    from
        customers c1_0
    where
        c1_0.grade = ?
```
```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [VIP]
조회 4건 — 김서준, 류하나, 정  훈, 배채영
```

여기서 확인할 것.

- 별칭이 `c1_0` 입니다. Hibernate 6 은 `<엔티티첫글자><인덱스>_<서브인덱스>` 로 별칭을 만들고,
  **`as` 키워드 없이** 붙입니다. 이 코스의 모든 생성 SQL 이 이 형태입니다.
- `selectFrom(customer)` 는 전 컬럼을, `select(customer.name)` 은 **필요한 컬럼만** 읽습니다.
  QueryDSL 코드 한 글자 차이가 SQL 을 바꿉니다.
- `orders` 연관은 `LAZY` 이므로 SQL 에 조인이 전혀 없습니다.

두 테스트가 통과하고 위 SQL 이 그대로 보이면 셋업 완료입니다.

---

## 0-9. 문제 해결

| 에러 메시지 | 원인 | 해결 |
|---|---|---|
| `cannot find symbol: class QCustomer` | annotationProcessor 가 안 돌았거나 `clean` 직후 상태 | `./gradlew clean compileJava` 후 IDE 에서 Gradle 프로젝트 새로고침. IntelliJ 는 `Settings > Build Tools > Gradle > Build and run using: Gradle` 로 두는 편이 안전합니다 |
| `NoClassDefFoundError: javax/persistence/Entity` | 5.x 좌표(`com.querydsl`) 를 쓰고 있음 | `build.gradle` 을 0-2 절 표대로 교정. `./gradlew dependencies --configuration runtimeClasspath \| grep querydsl` 로 `com.querydsl` 이 없는지 확인 |
| `Unable to load class ...QCustomer` / Q타입은 있는데 IDE 만 빨간 줄 | 생성 디렉터리가 소스 루트로 인식되지 않음 | `build.gradle` 의 `sourceSets.main.java.srcDirs += generatedDir` 확인 후 Gradle 새로고침 |
| `SchemaManagementException: missing column [id] in table [orders]` | 엔티티 필드에 `@Column(name)` 누락 (PK 에서 가장 흔함) | 해당 필드에 `@Column(name = "order_id")` 명시. 0-6 절 함정 블록 참고 |
| `SchemaManagementException: missing table [shop.customers]` | 스키마가 적재되지 않았거나 다른 DB 에 붙음 | 0-4 절 `./install.sh` 재실행. `datasource.url` 의 DB 이름이 `shop` 인지 확인 |
| `Communications link failure` / `Connection refused: 3307` | 컨테이너 미기동 또는 아직 `health: starting` | `docker compose ps` 로 `(healthy)` 확인 후 재시도. 포트 충돌은 `docker ps --filter publish=3307` |
| `Access denied for user 'learner'@'...'` | 계정/비밀번호 불일치, 또는 볼륨은 남고 환경변수만 바뀐 상태 | `docker compose down -v && docker compose up -d` 후 `./install.sh`. MySQL 은 **최초 기동 시에만** `MYSQL_USER` 를 만듭니다 |
| `NoSuchBeanDefinitionException: ... JPAQueryFactory` | `QuerydslConfig` 가 컴포넌트 스캔 범위 밖 | `com.example.shop` 하위(예: `com.example.shop.config`)에 두었는지 확인 |
| `LazyInitializationException` | 트랜잭션 밖에서 지연 로딩 접근 | 테스트에 `@Transactional` 이 붙어 있는지 확인. `open-in-view: false` 라 웹 계층에서도 동일하게 터집니다 |
| SQL 은 찍히는데 `binding parameter` 가 안 보임 | Hibernate 5 시절 로거 이름 사용 | `org.hibernate.orm.jdbc.bind: trace` 로 교정 (0-5 절) |

> 💡 **실무 팁 — 막히면 초기화가 가장 빠릅니다**
> 이 코스의 DB 는 언제든 버려도 되는 학습용입니다.
> 원인 추적에 10분 이상 쓰지 말고 `docker compose down -v && docker compose up -d && ./install.sh`
> 를 돌리십시오. 데이터는 결정론적으로 생성되므로 **항상 똑같은 상태**로 돌아옵니다.

---

## 정리

| 항목 | 핵심 |
|---|---|
| 좌표 | `io.github.openfeign.querydsl` 6.12. `querydsl-jpa` 는 classifier 없음, `querydsl-apt` 는 `:jpa` |
| 자바 패키지 | groupId 는 바뀌었지만 **import 는 여전히 `com.querydsl.*`** |
| 5.x 를 쓰면 | 빌드는 통과하고 기동 시 `NoClassDefFoundError: javax/persistence/Entity` |
| Q타입 위치 | `build/generated/sources/annotationProcessor/java/main/...` — 커밋하지 않음 |
| `clean` | 생성 디렉터리를 함께 지우도록 `clean { delete ... }` 설정 |
| `ddl-auto` | `validate`. 스키마를 바꾸지 않고 매핑만 검증 |
| 네이밍 | 모든 컬럼에 `@Column(name)` 명시. 자동 변환에 반쯤 의존하지 않음 |
| `validate` 의 한계 | 엔티티에 없는 DB 컬럼(`products.attrs`)은 검사하지 않음 |
| 연관 | 전부 `FetchType.LAZY`. 예외 없음 |
| enum | `@Enumerated(EnumType.STRING)`. `ORDINAL` 금지 |
| 금액 | 전부 `BigDecimal`. `double` 은 정산을 어긋나게 합니다 |
| 로깅 | `org.hibernate.SQL: debug` + `org.hibernate.orm.jdbc.bind: trace` (Hibernate 6 기준 이름) |
| 검증 기준 | `customers` 30, `orders` 600, VIP 4명, `phone` NULL 3명, PENDING 60건, 후기 없는 상품 24개 |
| 별칭 | Hibernate 6 은 `c1_0` 형태로, `as` 없이 붙입니다 |

---

## 다음 단계

프로젝트가 준비됐습니다. 이제 `JPAQueryFactory` 하나로 쿼리를 짜기 시작합니다.
Step 01 에서는 SQL 로그를 더 정교하게 읽는 법과, 같은 결과를 내는 여러 QueryDSL 표현이
**서로 다른 SQL 을 만드는** 첫 사례를 봅니다.

→ [Step 01 — 환경 구축과 첫 쿼리](../step-01-setup/)
