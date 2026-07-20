# 실습 프로젝트 셋업

> **학습 목표**
> - Gradle(Groovy DSL) + Spring Boot 3.2 + Spring Batch 5.1 프로젝트를 처음부터 만든다
> - `docker compose` 로 MySQL 8 메타데이터 DB(`batchdb`)를 기동한다
> - 실습 도메인 스키마(`customers` 1,000행 · `orders` 100,000행 · `settlement`)를 결정론적으로 생성한다
> - 배치가 **왜 별도의 메타데이터 DB 를 필요로 하는지** 이해한다
> - `./gradlew bootRun` 이 정상 종료되는 것까지 확인한다
>
> **선행 스텝**: 없음 (이 문서가 출발점입니다)
> **예상 소요**: 30분

---

이 문서는 **Step 01 ~ Step 14 전체가 공유하는 단 하나의 프로젝트**를 만듭니다.
각 스텝은 이 프로젝트 안에 `com.example.batch.step01`, `step02` … 처럼 패키지를 하나씩 추가하는 방식으로 진행합니다.
스텝마다 프로젝트를 새로 만들지 않습니다.

---

## P-1. 왜 배치는 DB 를 하나 더 쓰는가

Spring Batch 는 **"어디까지 처리했는지"를 기억하는 프레임워크**입니다. 10만 건을 처리하다 7만 건째에서 죽었다면, 다시 돌렸을 때 7만 건째부터 이어가야 합니다. 그러려면 다음이 어딘가에 **영속**되어야 합니다.

- 이 Job 이 언제 시작해서 언제 끝났는가
- 어떤 파라미터로 실행됐는가 (같은 파라미터로 또 돌리면 안 되는가)
- 몇 번째 청크까지 커밋됐는가
- 읽은 건수 / 쓴 건수 / 건너뛴 건수는 몇인가

Spring Batch 는 이 정보를 **`BATCH_` 로 시작하는 9개의 메타데이터 테이블**에 저장합니다. 이 테이블 묶음을 관리하는 컴포넌트가 `JobRepository` 입니다.

```
     ┌──────────────────┐
     │   여러분의 Job    │
     └────────┬─────────┘
              │ "나 시작했어요" / "청크 12 커밋했어요" / "끝났어요"
              ▼
     ┌──────────────────┐
     │   JobRepository   │       ← 배치의 기억장치
     └────────┬─────────┘
              │ JDBC
              ▼
     ┌──────────────────────────────────────────┐
     │  BATCH_JOB_INSTANCE / BATCH_JOB_EXECUTION │
     │  BATCH_STEP_EXECUTION / ..._CONTEXT / ... │   ← 9개 테이블
     └──────────────────────────────────────────┘
```

> 💡 **실무 팁 — 메타데이터 DB 는 업무 DB 와 분리하는 편이 좋습니다**
> 메타데이터는 배치 인프라의 자산이고, 업무 데이터는 서비스의 자산입니다. 운영에서는 스키마를 분리하거나 아예 다른 인스턴스로 두는 경우가 많습니다.
> 다만 **한 트랜잭션으로 묶고 싶다면 같은 DataSource 여야** 합니다(그래야 청크 커밋과 메타데이터 갱신이 원자적입니다). 이 트레이드오프는 [Step 11](../step-11-fault-tolerance/) 에서 다시 다룹니다.
> 학습 편의를 위해 이 코스는 **하나의 MySQL 인스턴스, 하나의 `batchdb` 스키마**에 메타데이터와 업무 데이터를 함께 둡니다.

---

## P-2. 디렉터리 구조

먼저 최종 형태를 봅니다.

```
spring-batch5-lab/
├── docker/
│   ├── docker-compose.yml
│   └── initdb/
│       ├── 01-schema.sql          ← 업무 테이블 DDL
│       └── 02-seed.sql            ← 결정론적 시드 데이터
├── build.gradle
├── settings.gradle
├── gradle/wrapper/...
├── gradlew
└── src/main/
    ├── java/com/example/batch/
    │   ├── BatchLabApplication.java
    │   ├── domain/
    │   │   ├── Order.java
    │   │   ├── Customer.java
    │   │   └── Settlement.java
    │   ├── step01/ ... step14/     ← 스텝별 Job 설정
    └── resources/
        ├── application.yml
        └── logback-spring.xml
```

---

## P-3. docker compose — MySQL 8 기동

`docker/docker-compose.yml`:

```yaml
services:
  mysql:
    image: mysql:8.0.36
    container_name: batch-mysql8
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
      - --default-time-zone=+09:00
      - --local-infile=1
      - --log-bin-trust-function-creators=1
      - --innodb-buffer-pool-size=512M
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_DATABASE: batchdb
      MYSQL_USER: batch
      MYSQL_PASSWORD: batch1234
      TZ: Asia/Seoul
    ports:
      - "3308:3306"
    volumes:
      - ./initdb:/docker-entrypoint-initdb.d
      - batch-mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-proot1234"]
      interval: 5s
      timeout: 3s
      retries: 20

volumes:
  batch-mysql-data:
```

> ⚠️ **함정 — 포트 3306 을 쓰지 마세요**
> 로컬에 MySQL 이 이미 떠 있으면 `Bind for 0.0.0.0:3306 failed: port is already allocated` 로 죽습니다.
> 이 코스는 **3308** 을 씁니다. (mysql8 코스가 3307 을 쓰므로 겹치지 않게 한 칸 옮겼습니다.)
> 그리고 컨테이너 **안쪽**은 항상 3306 입니다. `3308:3306` 의 왼쪽만 바꾸는 것이며, `application.yml` 의 URL 은 3308 이어야 합니다.

기동합니다.

```bash
cd docker
docker compose up -d
docker compose ps
```

**결과**
```
NAME           IMAGE          COMMAND                  SERVICE   STATUS                    PORTS
batch-mysql8   mysql:8.0.36   "docker-entrypoint.s…"   mysql     Up 32 seconds (healthy)   33060/tcp, 0.0.0.0:3308->3306/tcp
```

`(healthy)` 가 뜰 때까지 20~30초 걸립니다. `(health: starting)` 이면 아직 초기화 중입니다.

접속 확인:

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "SELECT VERSION(), @@time_zone;"
```

**결과**
```
+-----------+-------------+
| VERSION() | @@time_zone |
+-----------+-------------+
| 8.0.36    | +09:00      |
+-----------+-------------+
```

---

## P-4. 업무 스키마 DDL — `docker/initdb/01-schema.sql`

`/docker-entrypoint-initdb.d` 에 넣은 `.sql` 은 **컨테이너 볼륨이 비어 있을 때 딱 한 번** 파일명 순서대로 실행됩니다.

```sql
-- 01-schema.sql
USE batchdb;

-- 데이터 생성 보조용 숫자 테이블 (1 ~ 300000)
DROP TABLE IF EXISTS tally;
CREATE TABLE tally (
  n INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

DROP TABLE IF EXISTS customers;
CREATE TABLE customers (
  customer_id  INT           NOT NULL PRIMARY KEY,
  name         VARCHAR(50)   NOT NULL,
  grade        VARCHAR(10)   NOT NULL,          -- BRONZE / SILVER / GOLD / VIP
  fee_rate     DECIMAL(5,4)  NOT NULL,          -- 등급별 정산 수수료율
  city         VARCHAR(20)   NOT NULL,
  joined_at    DATE          NOT NULL
) ENGINE=InnoDB;

DROP TABLE IF EXISTS products;
CREATE TABLE products (
  product_id   INT           NOT NULL PRIMARY KEY,
  name         VARCHAR(60)   NOT NULL,
  category     VARCHAR(20)   NOT NULL,
  unit_price   DECIMAL(12,2) NOT NULL
) ENGINE=InnoDB;

DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  order_id     BIGINT        NOT NULL PRIMARY KEY,
  customer_id  INT           NOT NULL,
  amount       DECIMAL(12,2) NOT NULL,
  status       VARCHAR(12)   NOT NULL,          -- COMPLETED / CANCELLED / PENDING / REFUNDED
  ordered_at   DATETIME      NOT NULL,
  KEY idx_orders_status_date (status, ordered_at),
  KEY idx_orders_customer (customer_id)
) ENGINE=InnoDB;

DROP TABLE IF EXISTS order_items;
CREATE TABLE order_items (
  order_item_id BIGINT       NOT NULL PRIMARY KEY,
  order_id      BIGINT       NOT NULL,
  product_id    INT          NOT NULL,
  quantity      INT          NOT NULL,
  unit_price    DECIMAL(12,2) NOT NULL,
  KEY idx_items_order (order_id)
) ENGINE=InnoDB;

-- 배치가 채우는 결과 테이블. 처음에는 비어 있습니다.
DROP TABLE IF EXISTS settlement;
CREATE TABLE settlement (
  settlement_id BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id      BIGINT        NOT NULL,
  customer_id   INT           NOT NULL,
  settle_date   DATE          NOT NULL,
  gross_amount  DECIMAL(12,2) NOT NULL,
  fee_rate      DECIMAL(5,4)  NOT NULL,
  fee_amount    DECIMAL(12,2) NOT NULL,
  net_amount    DECIMAL(12,2) NOT NULL,
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_settlement_order (order_id),
  KEY idx_settlement_date (settle_date)
) ENGINE=InnoDB;
```

> ⚠️ **함정 — `settlement.order_id` 의 UNIQUE 제약은 의도적입니다**
> 정산은 **한 주문에 한 번만** 되어야 합니다. 배치를 두 번 돌려서 정산이 두 배가 되는 사고는 실무에서 정말 흔합니다.
> 이 UNIQUE 키가 없으면 [Step 03](../step-03-job-parameters/) 의 재실행 실습에서 조용히 중복 데이터가 쌓이고, 여러분은 **에러 없이 틀린 정산서**를 얻게 됩니다.
> 제약을 걸어 두면 최소한 `DuplicateKeyException` 으로 시끄럽게 실패합니다. **조용히 틀리느니 시끄럽게 실패하는 편이 낫습니다.**

---

## P-5. 시드 데이터 — `docker/initdb/02-seed.sql`

**`RAND()` 를 쓰지 않습니다.** 모든 값을 `order_id` 의 나머지 연산으로 만듭니다.
그래서 누가 몇 번을 실행하든 **완전히 동일한 데이터**가 나오고, 이 교재의 모든 실행 결과가 여러분 화면과 일치합니다.

```sql
-- 02-seed.sql
USE batchdb;

-- ── tally: 1 ~ 300000 (재귀 CTE)
SET SESSION cte_max_recursion_depth = 400000;
INSERT INTO tally (n)
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 300000
)
SELECT n FROM seq;

-- ── customers: 1,000행
INSERT INTO customers (customer_id, name, grade, fee_rate, city, joined_at)
SELECT
  n,
  CONCAT('CUST-', LPAD(n, 4, '0')),
  ELT(n % 4 + 1, 'BRONZE', 'SILVER', 'GOLD', 'VIP'),
  ELT(n % 4 + 1, 0.0350,  0.0300,   0.0250, 0.0200),
  ELT(n % 5 + 1, 'SEOUL', 'BUSAN', 'DAEGU', 'INCHEON', 'GWANGJU'),
  DATE_ADD('2023-01-01', INTERVAL (n % 730) DAY)
FROM tally WHERE n <= 1000;

-- ── products: 200행
INSERT INTO products (product_id, name, category, unit_price)
SELECT
  n,
  CONCAT('PROD-', LPAD(n, 4, '0')),
  ELT(n % 5 + 1, 'FASHION', 'BEAUTY', 'DIGITAL', 'LIVING', 'FOOD'),
  1000 + (n % 97) * 500
FROM tally WHERE n <= 200;

-- ── orders: 100,000행
INSERT INTO orders (order_id, customer_id, amount, status, ordered_at)
SELECT
  n,
  ((n - 1) % 1000) + 1,
  1000 + (n % 977) * 100,
  CASE
    WHEN n % 10 <= 6 THEN 'COMPLETED'
    WHEN n % 10 = 7  THEN 'CANCELLED'
    WHEN n % 10 = 8  THEN 'PENDING'
    ELSE                  'REFUNDED'
  END,
  DATE_ADD(DATE_ADD('2025-01-01 00:00:00', INTERVAL ((n - 1) % 180) DAY),
           INTERVAL (n % 1440) MINUTE)
FROM tally WHERE n <= 100000;

-- ── order_items: 300,000행 (주문당 정확히 3건)
INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price)
SELECT
  n,
  ((n - 1) / 3) + 1,
  (n % 200) + 1,
  (n % 3) + 1,
  1000 + (n % 97) * 500
FROM tally WHERE n <= 300000;
```

검증합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT status, COUNT(*) cnt, SUM(amount) total FROM orders GROUP BY status ORDER BY status;"
```

**결과**
```
+-----------+-------+---------------+
| status    | cnt   | total         |
+-----------+-------+---------------+
| CANCELLED | 10000 |  497830000.00 |
| COMPLETED | 70000 | 3485250000.00 |
| PENDING   | 10000 |  497890000.00 |
| REFUNDED  | 10000 |  497370000.00 |
+-----------+-------+---------------+
```

**정산 대상은 `COMPLETED` 70,000건입니다.** 이 숫자는 코스 내내 반복해서 나옵니다. 청크 크기 1,000이면 **정확히 70청크**입니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT MIN(ordered_at) first_order, MAX(ordered_at) last_order,
       COUNT(DISTINCT DATE(ordered_at)) days FROM orders;"
```

**결과**
```
+---------------------+---------------------+------+
| first_order         | last_order          | days |
+---------------------+---------------------+------+
| 2025-01-01 00:01:00 | 2025-06-29 23:59:00 |  181 |
+---------------------+---------------------+------+
```

180일치(2025-01-01 ~ 2025-06-29) 주문이 균등하게 깔려 있습니다. **하루치는 약 555건**이고, 그중 COMPLETED 는 약 389건입니다. Step 03 이후의 "일자 파라미터로 하루치만 정산" 실습이 여기에 기댑니다.

> 💡 **결정론적 데이터의 가치**
> `RAND()` 를 쓰면 "내 결과가 교재와 다른데 내가 틀린 건가, 데이터가 다른 건가?"를 구분할 수 없습니다.
> 나머지 연산만 쓰면 **다르면 무조건 내가 틀린 것**입니다. 학습에서 이 확실성은 굉장히 큽니다.

---

## P-6. `build.gradle` — 전문

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.5'
    id 'io.spring.dependency-management' version '1.1.4'
}

group = 'com.example'
version = '1.0.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ── Spring Batch 5.1.x (Boot 3.2.5 가 관리)
    implementation 'org.springframework.boot:spring-boot-starter-batch'

    // ── JDBC / JPA — Step 06 의 JpaPagingItemReader 에 필요
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    // ── Step 14 의 Micrometer 지표 노출
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'

    // ── Step 14 의 Quartz 스케줄링
    implementation 'org.springframework.boot:spring-boot-starter-quartz'

    runtimeOnly 'com.mysql:mysql-connector-j'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.batch:spring-batch-test'
}

tasks.named('test') {
    useJUnitPlatform()
}

// Job 이름을 커맨드라인으로 넘기기 편하게 (Step 03, Step 14 에서 사용)
bootRun {
    if (project.hasProperty('args')) {
        args project.property('args').split(',')
    }
}
```

버전을 확인합니다.

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -E 'spring-batch|spring-boot-starter-batch|mysql'
```

**결과**
```
+--- org.springframework.boot:spring-boot-starter-batch -> 3.2.5
|    +--- org.springframework.batch:spring-batch-core:5.1.1
|    |    \--- org.springframework.batch:spring-batch-infrastructure:5.1.1
\--- com.mysql:mysql-connector-j -> 8.3.0
```

**Spring Batch 5.1.1** 입니다. 이 코스의 모든 API 설명은 이 버전을 기준으로 합니다.

> ⚠️ **함정 — Spring Batch 4 예제를 그대로 붙여 넣으면 컴파일이 안 됩니다**
> 인터넷의 Spring Batch 예제 대부분은 아직 4.x 기준입니다. 5.0 에서 다음이 **깨지는 변경**으로 바뀌었습니다.
>
> | 4.x | 5.x | 비고 |
> |---|---|---|
> | `JobBuilderFactory` / `StepBuilderFactory` | **삭제됨.** `new JobBuilder(name, jobRepository)` / `new StepBuilder(name, jobRepository)` | [Step 02](../step-02-job-step/) |
> | `javax.persistence.*`, `javax.sql.*` | `jakarta.*` | Jakarta EE 9+ 전환 |
> | `@EnableBatchProcessing` **필수** | **선택.** Boot 의 자동설정이 대신함 | [Step 02](../step-02-job-step/) |
> | `JobBuilder.start(step)` 만 | 동일하나 `.repository()` 는 생성자로 이동 | |
> | `ItemWriter.write(List<T>)` | `ItemWriter.write(Chunk<? extends T> chunk)` | [Step 08](../step-08-item-writer/) |
> | `JobParameter` 원시 생성자 | 타입 파라미터 `JobParameter<T>(value, Class, identifying)` | [Step 03](../step-03-job-parameters/) |
> | `@EnableBatchProcessing` 없어도 `JobLauncherApplicationRunner` 자동 실행 | 동일 | |
>
> 이 표는 코스 전체에서 계속 참조합니다. 지금 이해가 안 돼도 괜찮습니다.

---

## P-7. `application.yml`

```yaml
spring:
  application:
    name: spring-batch5-lab

  datasource:
    url: jdbc:mysql://127.0.0.1:3308/batchdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true
    username: batch
    password: batch1234
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20        # Step 13 의 멀티스레드/파티셔닝에서 필요
      pool-name: batch-pool

  jpa:
    hibernate:
      ddl-auto: none               # 스키마는 initdb 가 만듭니다. 절대 update 로 두지 마세요.
    properties:
      hibernate:
        jdbc:
          batch_size: 1000
        format_sql: true
    open-in-view: false

  batch:
    jdbc:
      initialize-schema: always    # BATCH_* 테이블 자동 생성
      table-prefix: BATCH_
    job:
      enabled: true                # 부팅 시 Job 자동 실행 (Step 01 에서 관찰)
      # name: settlementJob        # 특정 Job 만 실행하고 싶을 때 (Step 14)

logging:
  level:
    org.springframework.batch: INFO
    org.springframework.batch.core.step.item.ChunkOrientedTasklet: DEBUG
    org.springframework.jdbc.core.JdbcTemplate: INFO
    com.example.batch: DEBUG

management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus
```

> ⚠️ **함정 — `initialize-schema: always` 는 학습용입니다**
> `always` 는 애플리케이션이 뜰 때마다 `schema-mysql.sql` 을 실행해 `BATCH_*` 테이블을 만듭니다(이미 있으면 `CREATE TABLE` 이 실패하지만 무시). 편하긴 한데 **운영에서는 `never` 로 두고 DDL 을 형상관리하는 것이 정석**입니다.
> 배치 메타데이터 테이블이 애플리케이션 기동 타이밍에 만들어지는 건, 스키마 변경 이력을 추적할 수 없다는 뜻이기 때문입니다.
> `never` 로 바꿀 경우 DDL 원본은 jar 안의 `org/springframework/batch/core/schema-mysql.sql` 에 있습니다.

> 💡 **`rewriteBatchedStatements=true` 를 빼먹지 마세요**
> MySQL JDBC 드라이버는 이 옵션이 **꺼져 있으면** `addBatch()` 한 1,000건을 진짜로 1,000번 왕복시킵니다. 켜면 하나의 멀티 VALUES `INSERT` 로 합칩니다.
> [Step 08](../step-08-item-writer/) 에서 이 옵션 하나로 쓰기 성능이 **약 8배** 달라지는 것을 실측합니다. Spring Batch 튜닝에서 가장 가성비 좋은 한 줄입니다.

---

## P-8. 애플리케이션 클래스와 도메인

`src/main/java/com/example/batch/BatchLabApplication.java`:

```java
package com.example.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BatchLabApplication {

    public static void main(String[] args) {
        // 배치는 웹 서버가 아닙니다. Job 이 끝나면 JVM 도 끝나야 합니다.
        // exit() 로 감싸면 종료 코드(0/1)가 Job 결과를 반영합니다 — Step 14 에서 활용합니다.
        System.exit(SpringApplication.exit(SpringApplication.run(BatchLabApplication.class, args)));
    }
}
```

> 💡 `spring-boot-starter-web` 을 넣지 않았으므로 애플리케이션은 웹 서버 없이 뜨고, `JobLauncherApplicationRunner` 가 Job 을 다 돌린 뒤 컨텍스트가 닫히며 종료됩니다. **`starter-web` 을 실수로 넣으면 Job 이 끝나도 톰캣이 살아 있어 프로세스가 안 죽습니다.** 배치 프로젝트에서 자주 나는 사고입니다.

도메인 레코드 세 개. Java 21 의 `record` 를 씁니다.

```java
package com.example.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Order(
        Long order_id,
        Integer customerId,
        BigDecimal amount,
        String status,
        LocalDateTime orderedAt
) {}
```

```java
package com.example.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Settlement(
        Long orderId,
        Integer customerId,
        LocalDate settleDate,
        BigDecimal grossAmount,
        BigDecimal feeRate,
        BigDecimal feeAmount,
        BigDecimal netAmount
) {}
```

> ⚠️ **함정 — `record` 는 `BeanPropertyRowMapper` 와 `BeanPropertyItemSqlParameterSourceProvider` 에 안 맞습니다**
> `record` 의 접근자는 `getAmount()` 가 아니라 `amount()` 입니다. 자바빈 규약이 아닙니다.
> - 읽기: `BeanPropertyRowMapper` 대신 **`DataClassRowMapper`** 를 쓰면 생성자 기반으로 매핑됩니다(Spring 5.3+).
> - 쓰기: `BeanPropertyItemSqlParameterSourceProvider` 는 **동작하지 않습니다.** 람다로 직접 `MapSqlParameterSource` 를 만드세요.
>
> 이 차이 때문에 "왜 모든 컬럼이 NULL 로 들어가지?" 하며 몇 시간 날리는 일이 흔합니다. [Step 06](../step-06-item-reader/), [Step 08](../step-08-item-writer/) 에서 정면으로 다룹니다.

돈 계산은 반드시 `BigDecimal` 입니다.

> ⚠️ `double` 로 정산 금액을 다루면 `0.1 + 0.2 != 0.3` 이 되어 **누적 오차가 정산서를 어긋나게** 합니다. 100건이면 안 보이고 7만 건이면 보입니다. `DECIMAL(12,2)` ↔ `BigDecimal` 을 고정으로 씁니다.

---

## P-9. 첫 기동 — 정상 종료 확인

이 시점에는 Job 이 하나도 없습니다. 그래도 떠야 정상입니다.

```bash
./gradlew bootRun
```

**결과**
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.5)

INFO 41211 --- [           main] c.e.batch.BatchLabApplication            : Starting BatchLabApplication using Java 21.0.2
INFO 41211 --- [           main] c.e.batch.BatchLabApplication            : No active profile set, falling back to 1 default profile: "default"
INFO 41211 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Starting...
INFO 41211 --- [           main] com.zaxxer.hikari.pool.HikariPool        : batch-pool - Added connection com.mysql.cj.jdbc.ConnectionImpl@6b7d1df8
INFO 41211 --- [           main] com.zaxxer.hikari.HikariDataSource       : batch-pool - Start completed.
INFO 41211 --- [           main] c.e.batch.BatchLabApplication            : Started BatchLabApplication in 1.842 seconds (process running for 2.103)

BUILD SUCCESSFUL in 4s
```

**`BUILD SUCCESSFUL` 로 끝나야 합니다.** 프로세스가 안 죽고 매달려 있다면 `starter-web` 이 딸려 들어온 것입니다. `./gradlew dependencies` 로 확인하세요.

이제 메타데이터 테이블이 생겼는지 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "SHOW TABLES LIKE 'BATCH%';"
```

**결과**
```
+---------------------------------+
| Tables_in_batchdb (BATCH%)      |
+---------------------------------+
| BATCH_JOB_EXECUTION             |
| BATCH_JOB_EXECUTION_CONTEXT     |
| BATCH_JOB_EXECUTION_PARAMS      |
| BATCH_JOB_EXECUTION_SEQ         |
| BATCH_JOB_INSTANCE              |
| BATCH_JOB_SEQ                   |
| BATCH_STEP_EXECUTION            |
| BATCH_STEP_EXECUTION_CONTEXT    |
| BATCH_STEP_EXECUTION_SEQ        |
+---------------------------------+
```

**9개.** 이 테이블 하나하나가 무슨 일을 하는지는 [Step 01](../step-01-setup/) 에서 열어 봅니다.

---

## P-10. 초기화 방법

실습이 꼬이면 언제든 되돌립니다.

```bash
# (a) 메타데이터만 지우기 — 업무 데이터는 유지
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;
SET FOREIGN_KEY_CHECKS = 1;
TRUNCATE TABLE settlement;
SQL

# (b) 완전 초기화 — 볼륨까지 날리고 시드부터 다시
cd docker
docker compose down -v
docker compose up -d
```

(a) 는 3초, (b) 는 약 90초 걸립니다(10만 행 시드 생성 포함).

> 💡 **실무 팁 — (a) 를 셸 별칭으로 만들어 두세요**
> 이 코스에서 (a) 를 **수십 번** 실행하게 됩니다. 특히 [Step 03](../step-03-job-parameters/) 의 `JobInstanceAlreadyCompleteException` 실습과 [Step 11](../step-11-fault-tolerance/) 의 재시작 실습에서 그렇습니다.
> ```bash
> alias batchreset='mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb < ~/batch-reset.sql'
> ```

---

## 정리

| 항목 | 값 / 핵심 |
|---|---|
| Java | 21 (toolchain 고정) |
| Spring Boot | 3.2.5 |
| Spring Batch | **5.1.1** (Boot 가 관리) |
| 빌드 | Gradle Groovy DSL |
| DB | MySQL 8.0.36, `127.0.0.1:3308`, `batchdb` |
| 계정 | `batch` / `batch1234` (관리: `root` / `root1234`) |
| 메타데이터 | `BATCH_*` 9개 테이블, `initialize-schema: always` |
| 업무 데이터 | `customers` 1,000 · `products` 200 · `orders` **100,000** · `order_items` 300,000 · `settlement` 0 |
| 정산 대상 | `status='COMPLETED'` **70,000건** (청크 1,000 → 정확히 70청크) |
| 기간 | 2025-01-01 ~ 2025-06-29 (180일) |
| 결정론 | `RAND()` 금지, 나머지 연산만 사용 |
| 필수 JDBC 옵션 | `rewriteBatchedStatements=true` (Step 08 에서 8배 차이) |
| 금액 타입 | `DECIMAL(12,2)` ↔ `BigDecimal` 고정 |

---

## 다음 단계

프로젝트가 뜨고 메타데이터 테이블 9개가 만들어졌습니다. 하지만 아직 Job 이 하나도 없어서 그 테이블들은 전부 비어 있습니다.
다음 스텝에서 가장 단순한 Job 을 하나 만들어 실행하고, **그 한 번의 실행이 9개 테이블에 어떤 흔적을 남기는지** 행 단위로 추적합니다.

→ [Step 01 — 환경 구축과 첫 Job](../step-01-setup/)
