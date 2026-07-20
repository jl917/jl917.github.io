# 실습 프로젝트 셋업

> **학습 목표**
> - Gradle Groovy DSL 로 Spring Boot 3.2 + spring-kafka 프로젝트를 구성한다
> - docker compose 로 KRaft 모드 단일 브로커 Kafka 3.7 과 MySQL 8 을 띄운다
> - `application.yml` 의 프로듀서/컨슈머 설정이 각각 어떤 Kafka 클라이언트 프로퍼티로 번역되는지 이해한다
> - 스텝별 프로필(`step01` ~ `step13`)로 예제를 골라 실행하는 구조를 만든다
> - 브로커·토픽·컨슈머 그룹 상태를 CLI 로 확인하는 명령 4개를 손에 익힌다
>
> **예상 소요**: 40분

---

## P-1. 디렉터리 구조

```
spring-kafka-lab/
├── build.gradle
├── settings.gradle
├── gradle/wrapper/
├── docker-compose.yml
├── init/
│   └── 01-schema.sql              ← MySQL 초기 스키마
└── src/
    ├── main/
    │   ├── java/com/example/order/
    │   │   ├── OrderServiceApplication.java
    │   │   ├── domain/OrderCreated.java
    │   │   ├── domain/OrderStatus.java
    │   │   └── config/KafkaTopicConfig.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/example/order/
```

각 스텝의 `Practice.java` / `Exercise.java` / `Solution.java` 는 이 프로젝트의
`src/main/java/com/example/order/stepNN/` 에 복사해 넣고, 프로필로 켜서 실행합니다.

---

## P-2. `docker-compose.yml` — KRaft 단일 브로커 + MySQL 8

Kafka 3.7 은 ZooKeeper 없이 **KRaft 모드**로 뜹니다. 한 컨테이너가 컨트롤러와 브로커를 겸합니다.

```yaml
services:
  kafka:
    image: apache/kafka:3.7.0
    container_name: learn-kafka
    ports:
      - "9092:9092"
    environment:
      # --- KRaft: 컨트롤러와 브로커를 한 프로세스에서 겸한다 ---
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER

      # --- 리스너: 컨테이너 내부용(INTERNAL)과 호스트용(EXTERNAL)을 분리 ---
      KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://learn-kafka:29092,EXTERNAL://127.0.0.1:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL

      # --- 단일 노드이므로 내부 토픽 복제본도 1 ---
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_DEFAULT_REPLICATION_FACTOR: 1
      KAFKA_MIN_INSYNC_REPLICAS: 1

      # --- 학습용: 그룹 리밸런스를 빨리 끝내 실습 대기시간을 줄인다 ---
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0

      # --- 존재하지 않는 토픽으로 send 하면 자동 생성 (Step 03 에서 끕니다) ---
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"

      KAFKA_LOG_DIRS: /var/lib/kafka/data
      CLUSTER_ID: 4L6g3nShT-eMCtK--X86sw
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list || exit 1"]
      interval: 5s
      timeout: 10s
      retries: 10

  mysql:
    image: mysql:8.0
    container_name: learn-kafka-mysql
    ports:
      - "3307:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_DATABASE: orderdb
      MYSQL_USER: learner
      MYSQL_PASSWORD: learn1234
      TZ: Asia/Seoul
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
      - --default-time-zone=+09:00
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-plearn1234"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  kafka-data:
  mysql-data:
```

```bash
docker compose up -d
docker compose ps
```

**결과**
```
NAME                IMAGE                COMMAND                  STATUS                   PORTS
learn-kafka         apache/kafka:3.7.0   "/__cacert_entrypoin…"   Up 12 seconds (healthy)  0.0.0.0:9092->9092/tcp
learn-kafka-mysql   mysql:8.0            "docker-entrypoint.s…"   Up 12 seconds (healthy)  0.0.0.0:3307->3306/tcp
```

> ⚠️ **함정 — `KAFKA_ADVERTISED_LISTENERS` 를 잘못 쓰면 "연결은 되는데 타임아웃"**
> 부트스트랩 연결은 성공하지만, 브로커가 클라이언트에게 **"나에게 접속하려면 이 주소를 쓰라"** 고 알려 주는 값이 advertised listener 입니다.
> 이걸 `localhost:9092` 하나로만 두면, 컨테이너 **내부**의 CLI 는 `localhost` 를 자기 자신으로 해석해 되고, 호스트의 Spring 앱도 되지만,
> **다른 컨테이너**에서 붙을 땐 자기 자신을 가리켜 실패합니다. 위처럼 INTERNAL/EXTERNAL 을 나눠 두면 두 경우 모두 됩니다.
> 증상은 예외가 아니라 **60초 침묵 후 `TimeoutException: Topic orders not present in metadata after 60000 ms`** 입니다. 원인을 찾기 아주 어렵습니다.

---

## P-3. MySQL 초기 스키마 — `init/01-schema.sql`

Step 09(트랜잭션)와 Step 13(Outbox·멱등 컨슈머)에서 씁니다. 컨테이너 최초 기동 시 자동 실행됩니다.

```sql
USE orderdb;

-- 주문 원장
CREATE TABLE orders (
  order_id     VARCHAR(20)  NOT NULL PRIMARY KEY,
  customer_id  INT          NOT NULL,
  amount       DECIMAL(12,2) NOT NULL,
  status       VARCHAR(20)  NOT NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

-- Step 13: Transactional Outbox
CREATE TABLE outbox_event (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  aggregate_id  VARCHAR(64)  NOT NULL,
  event_type    VARCHAR(64)  NOT NULL,
  payload       JSON         NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at  DATETIME(3)  NULL,
  KEY idx_unpublished (published_at, id)
) ENGINE=InnoDB;

-- Step 13: 멱등 컨슈머 처리 이력
CREATE TABLE processed_message (
  message_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
  consumer_group VARCHAR(64) NOT NULL,
  processed_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

-- Step 09: 재고
CREATE TABLE inventory (
  sku       VARCHAR(32) NOT NULL PRIMARY KEY,
  quantity  INT         NOT NULL
) ENGINE=InnoDB;

INSERT INTO inventory (sku, quantity) VALUES
  ('SKU-001', 1000), ('SKU-002', 1000), ('SKU-003', 1000);
```

> 💡 `outbox_event.idx_unpublished (published_at, id)` 의 컬럼 순서에 주목하세요.
> 릴레이는 `WHERE published_at IS NULL ORDER BY id LIMIT 100` 으로 폴링합니다.
> 등치 조건(`IS NULL`) 컬럼이 앞, 정렬 컬럼이 뒤 — 복합 인덱스의 정석입니다. ([MySQL 코스 Step 15](../../mysql8/step-15-indexes/) 참고)

---

## P-4. `build.gradle` 전문

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot'          version '3.2.5'
    id 'io.spring.dependency-management'   version '1.1.4'
}

group   = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- 핵심 ---
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.kafka:spring-kafka'          // 버전은 Boot BOM 이 관리 → 3.1.4

    // --- Step 09, 13: DB ---
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    runtimeOnly    'com.mysql:mysql-connector-j'

    // --- Step 12: 관측성 ---
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.micrometer:micrometer-tracing-bridge-brave'
    implementation 'io.zipkin.reporter2:zipkin-reporter-brave'

    // --- JSON (spring-kafka 의 JsonSerializer 가 Jackson 을 씁니다) ---
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'

    // --- 편의 ---
    compileOnly     'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // --- Step 11: 테스트 ---
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
    testImplementation 'org.awaitility:awaitility'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:kafka'
    testImplementation 'org.testcontainers:mysql'
}

dependencyManagement {
    imports {
        mavenBom "org.testcontainers:testcontainers-bom:1.19.7"
    }
}

tasks.named('test') {
    useJUnitPlatform()
}
```

버전 확인:

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -E 'spring-kafka|kafka-clients'
```

**결과**
```
+--- org.springframework.kafka:spring-kafka -> 3.1.4
|    +--- org.apache.kafka:kafka-clients:3.6.1
|    +--- org.springframework:spring-context:6.1.6
|    +--- org.springframework:spring-messaging:6.1.6
|    \--- org.springframework.retry:spring-retry:2.0.5
```

> ⚠️ **함정 — kafka-clients 버전과 브로커 버전은 다르다**
> 브로커는 3.7.0 인데 클라이언트는 3.6.1 입니다. Kafka 는 **양방향 호환**이라 문제없지만,
> "3.7 신기능을 쓰려는데 안 된다"면 거의 항상 **클라이언트가 구버전**이어서입니다.
> 올리려면 `ext['kafka.version'] = '3.7.0'` 을 `build.gradle` 에 추가해 Boot BOM 을 덮어씁니다.
> 단, spring-kafka 3.1.x 가 검증한 조합은 3.6.x 이므로 **이 코스는 기본값을 유지**합니다.

---

## P-5. `application.yml` 전문

```yaml
spring:
  application:
    name: order-service

  kafka:
    bootstrap-servers: 127.0.0.1:9092

    producer:
      key-serializer:   org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all                    # 리더 + 모든 ISR 이 받아야 성공 (단일 노드라 실질 리더 1)
      retries: 3
      properties:
        enable.idempotence: true   # 중복 없는 재시도. acks=all + max.in.flight<=5 필요
        max.in.flight.requests.per.connection: 5
        linger.ms: 5               # 5ms 모아서 배치 전송 (Step 02)
        delivery.timeout.ms: 120000
        spring.json.add.type.headers: true   # __TypeId__ 헤더 부착 (Step 04 에서 끕니다)

    consumer:
      key-deserializer:   org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      auto-offset-reset: earliest  # 실습 편의. 운영 기본값은 latest
      enable-auto-commit: false    # Spring 이 커밋을 관리 (Step 06)
      max-poll-records: 500
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "com.example.order.domain"
        spring.json.value.default.type: com.example.order.domain.OrderCreated
        max.poll.interval.ms: 300000
        session.timeout.ms: 45000
        heartbeat.interval.ms: 3000

    listener:
      ack-mode: BATCH              # 기본값. Step 06 에서 전부 비교합니다
      concurrency: 3               # 파티션 수와 맞춤 (Step 03)
      missing-topics-fatal: false
      observation-enabled: false   # Step 12 에서 켭니다

  datasource:
    url: jdbc:mysql://127.0.0.1:3307/orderdb?serverTimezone=Asia/Seoul&rewriteBatchedStatements=true
    username: learner
    password: learn1234
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: none               # 스키마는 init/01-schema.sql 이 만듭니다
    properties:
      hibernate.format_sql: true
    open-in-view: false

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      application: order-service

app:
  topic:
    orders: orders
    orders-dlt: orders.DLT
    payments: payments

logging:
  level:
    org.springframework.kafka: INFO
    org.apache.kafka.clients.consumer.ConsumerConfig: WARN   # 기동 시 설정 덤프가 길어서
    org.apache.kafka.clients.producer.ProducerConfig: WARN
    com.example.order: DEBUG
```

### `spring.kafka.*` 가 어디로 번역되는가

Spring Boot 의 `spring.kafka.*` 는 **Kafka 클라이언트의 원본 프로퍼티**로 변환됩니다. 대응이 1:1 이 아닌 것들이 있습니다.

| `application.yml` | Kafka 원본 프로퍼티 | 비고 |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `bootstrap.servers` | |
| `spring.kafka.producer.acks` | `acks` | |
| `spring.kafka.consumer.auto-offset-reset` | `auto.offset.reset` | |
| `spring.kafka.consumer.enable-auto-commit` | `enable.auto.commit` | **Spring 은 false 를 권장** |
| `spring.kafka.producer.properties.*` | 그대로 전달 | Boot 가 이름을 모르는 설정은 여기에 |
| `spring.kafka.listener.ack-mode` | **없음** | Kafka 가 아니라 **Spring 컨테이너**의 개념 |
| `spring.kafka.listener.concurrency` | **없음** | Spring 이 컨슈머 스레드를 몇 개 만들지 |

> 💡 **실무 팁 — 헷갈리면 `properties:` 아래에 원본 이름으로 쓰세요.**
> `spring.kafka.producer.properties.linger.ms` 는 무조건 `linger.ms` 로 전달됩니다.
> Boot 가 축약형을 제공하는 설정(약 20개)만 외우려 애쓰지 말고, 나머지는 원본 이름을 그대로 쓰는 게 안전합니다.
> 오타를 내면 **에러 없이 무시**되므로(Kafka 는 모르는 설정에 WARN 만 냅니다), 기동 로그의
> `The configuration 'lingerms' was supplied but isn't a known config.` 경고를 꼭 확인하세요.

---

## P-6. 애플리케이션 뼈대

### `OrderServiceApplication.java`

```java
package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

`@SpringBootApplication` 만 있으면 `KafkaAutoConfiguration` 이 켜집니다. `@EnableKafka` 는 **Boot 를 쓰면 불필요**합니다
(자동 설정이 `@EnableKafka` 를 대신 붙여 줍니다. Step 01 에서 확인합니다).

### `domain/OrderCreated.java`

이 코스 전체가 이 이벤트 하나로 돌아갑니다. **Java 21 record** 를 씁니다.

```java
package com.example.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCreated(
        String orderId,
        int customerId,
        String sku,
        int quantity,
        BigDecimal amount,
        Instant createdAt
) {
    public static OrderCreated of(int seq) {
        return new OrderCreated(
                "ORD-%04d".formatted(seq),
                1000 + (seq % 30),
                "SKU-%03d".formatted(1 + (seq % 3)),
                1 + (seq % 5),
                new BigDecimal(10_000 + (seq % 7) * 1_000),
                Instant.parse("2025-01-01T00:00:00Z").plusSeconds(seq * 60L)
        );
    }
}
```

`of(seq)` 는 `RANDOM` 을 쓰지 않고 **나머지 연산으로만** 값을 만듭니다.
그래서 누가 몇 번을 실행하든 `ORD-0007` 의 내용은 항상 같고, **키가 같으니 파티션 배정도 항상 같습니다.**

> ⚠️ **함정 — record 를 JsonDeserializer 로 역직렬화하려면 조건이 있다**
> Jackson 은 2.12+ 부터 record 를 지원하지만, **파라미터 이름 정보**가 클래스 파일에 있어야 합니다.
> Gradle 은 Java 21 에서 record 컴포넌트 이름을 항상 보존하므로 그냥 동작하지만,
> **일반 클래스**를 쓴다면 기본 생성자가 없을 때 `-parameters` 컴파일 옵션이나 `@JsonCreator` 가 필요합니다.
> 증상은 `InvalidDefinitionException: Cannot construct instance ... no Creators` 입니다. Step 04 에서 다룹니다.

### `config/KafkaTopicConfig.java` — 토픽을 코드로 만든다

```java
package com.example.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ordersTopic() {
        return TopicBuilder.name("orders")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic ordersDltTopic() {
        return TopicBuilder.name("orders.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentsTopic() {
        return TopicBuilder.name("payments")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
```

`NewTopic` 빈이 있으면 Spring 이 기동 시 `KafkaAdmin` 으로 토픽을 만듭니다. **이미 있으면 건드리지 않습니다.**

> ⚠️ **함정 — `NewTopic` 은 파티션을 늘리기만 하고 줄이지 못한다**
> 토픽이 이미 2개 파티션으로 있는데 `partitions(3)` 으로 바꾸면 3개로 **늘어납니다**.
> 반대로 `partitions(1)` 로 바꾸면 **아무 일도 안 일어납니다.** 에러도 없습니다.
> 그런데 파티션을 늘리는 것도 안전하지 않습니다. **키 → 파티션 매핑이 통째로 바뀌어**
> 같은 주문 ID 의 이벤트가 전과 다른 파티션으로 가고, **순서 보장이 깨집니다.**
> 파티션 수는 처음에 넉넉히 잡고 나중에 바꾸지 않는 것이 원칙입니다.
> (파티션 재할당의 브로커 쪽 동작은 [Kafka 코스 Step 03](../../kafka/step-03-topics-partitions/) 참고)

---

## P-7. 스텝별 프로필로 예제 골라 실행하기

스텝마다 리스너가 다르므로, 전부 켜 두면 서로 간섭합니다. **프로필로 분리**합니다.

```java
@Component
@Profile("step07")                 // ← 이 프로필일 때만 빈 등록
public class Step07Listener { ... }
```

```bash
./gradlew bootRun --args='--spring.profiles.active=step07'
```

**결과**
```
INFO 13022 --- [           main] c.e.o.OrderServiceApplication            : The following 1 profile is active: "step07"
INFO 13022 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s07-inventory: partitions assigned: [orders-0, orders-1, orders-2]
```

> 💡 활성 프로필이 로그 두 번째 줄에 항상 찍힙니다. **예제가 안 도는 것 같으면 이 줄부터 보세요.**
> 프로필 오타(`step7` vs `step07`)면 리스너가 아예 등록되지 않고, **에러 없이 조용히 아무것도 안 합니다.**

---

## P-8. CLI 명령 4개

이 코스 내내 쓰게 될 명령입니다. 별칭으로 만들어 두면 편합니다.

```bash
alias kt='docker exec -it learn-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092'
alias kcg='docker exec -it learn-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092'
alias kcc='docker exec -it learn-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092'
alias kcp='docker exec -it learn-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092'
```

### ① 토픽 확인

```bash
kt --describe --topic orders
```

**결과**
```
Topic: orders	TopicId: xJ0kQm8CQ3ay6z4rN2ZbSA	PartitionCount: 3	ReplicationFactor: 1	Configs: segment.bytes=1073741824
	Topic: orders	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: orders	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
```

### ② 컨슈머 그룹 랙 확인 — **가장 중요한 명령**

```bash
kcg --describe --group inventory
```

**결과**
```
GROUP      TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                            HOST         CLIENT-ID
inventory  orders  0          12              12              0    consumer-inventory-1-a3f8...           /172.19.0.1  consumer-inventory-1
inventory  orders  1          10              10              0    consumer-inventory-2-b91c...           /172.19.0.1  consumer-inventory-2
inventory  orders  2          8               8               0    consumer-inventory-3-c02d...           /172.19.0.1  consumer-inventory-3
```

- `CURRENT-OFFSET` : 그 그룹이 **커밋한** 위치
- `LOG-END-OFFSET` : 토픽에 쌓인 마지막 위치
- `LAG` = 둘의 차이. **밀린 메시지 수**

> 💡 **`LAG` 이 0 인데 처리가 안 된 것 같다면, 커밋만 되고 처리는 실패한 것입니다.**
> Step 06 에서 다룰 `AckMode` 설정 실수의 전형적 증상입니다. 랙만 보고 안심하면 안 됩니다.

### ③ 메시지 헤더까지 보며 소비

```bash
kcc --topic orders --from-beginning \
    --property print.key=true --property print.headers=true --property print.partition=true
```

**결과**
```
Partition:1	__TypeId__:com.example.order.domain.OrderCreated	ORD-0001	{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
Partition:0	__TypeId__:com.example.order.domain.OrderCreated	ORD-0002	{"orderId":"ORD-0002","customerId":1002,"sku":"SKU-003","quantity":3,"amount":12000,"createdAt":"2025-01-01T00:02:00Z"}
```

`__TypeId__` 헤더가 보이시나요? **Step 04 의 주인공**입니다. 저 패키지 경로가 컨슈머와 안 맞으면 전부 실패합니다.

### ④ 오프셋 리셋 (실습 재시작용)

```bash
# 앱을 먼저 종료할 것
kcg --group inventory --topic orders --reset-offsets --to-earliest --execute
```

**결과**
```
GROUP      TOPIC   PARTITION  NEW-OFFSET
inventory  orders  0          0
inventory  orders  1          0
inventory  orders  2          0
```

> ⚠️ **함정 — 컨슈머가 살아 있으면 리셋이 거부된다**
> 앱을 켜 둔 채 리셋하면 `Error: Assignments can only be reset if the group 'inventory' is inactive, but the current state is Stable.` 가 납니다.
> 이건 그나마 **에러가 나서 다행인** 경우입니다. 앱을 끄고 다시 실행하세요.

---

## 정리

| 항목 | 핵심 |
|---|---|
| Kafka 3.7 KRaft | ZooKeeper 없음. `PROCESS_ROLES=broker,controller` 한 컨테이너 |
| advertised listeners | INTERNAL/EXTERNAL 분리. 잘못되면 **예외 없이 60초 타임아웃** |
| `spring-kafka` 버전 | Boot BOM 이 관리 → 3.1.4 / kafka-clients 3.6.1 (브로커 3.7 과 호환) |
| `spring.kafka.*` | 대부분 Kafka 원본 프로퍼티로 번역. 모르면 `properties:` 에 원본 이름으로 |
| 오타 난 설정 | **에러 없이 무시.** 기동 로그의 `isn't a known config` 경고를 볼 것 |
| `NewTopic` 빈 | 없으면 생성, 있으면 그대로. **파티션은 늘기만 하고 줄지 않음** |
| 파티션 수 변경 | 키→파티션 매핑이 바뀌어 **순서 보장이 깨짐**. 처음에 넉넉히 |
| 스텝 프로필 | `--spring.profiles.active=stepNN`. 오타 나면 조용히 아무것도 안 함 |
| `kcg --describe` | `LAG` 확인. **랙 0 이 처리 성공을 뜻하지는 않는다** |

---

## 다음 단계

프로젝트가 떴으니 이제 메시지를 실제로 주고받습니다.
`KafkaTemplate` 으로 한 건 보내고 `@KafkaListener` 로 받아 보면서, **Spring Boot 자동 설정이 여러분 몰래 만들어 준 빈들**의 목록을 직접 꺼내 확인합니다.

→ [Step 01 — 환경 구축과 첫 메시지](../step-01-setup/)
