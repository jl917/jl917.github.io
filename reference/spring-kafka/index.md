# Spring Kafka 완전 학습 코스

Spring 애플리케이션에서 Kafka 를 **제대로** 쓰는 법을, **13개 스텝**으로 처음부터 끝까지 익힙니다.
모든 예제는 Spring Boot 3.2.x + Spring Kafka 3.1.x + Apache Kafka 3.7(KRaft) 환경에서 검증했고, **교재에 붙은 콘솔 로그는 여러분 화면의 로그와 형태가 일치합니다.**

> 📌 **이 코스는 Kafka 브로커를 가르치지 않습니다.**
> 파티션·복제·ISR·세그먼트·리밸런스 프로토콜 같은 **브로커 내부 동작**은 같은 사이트의 [Apache Kafka 코스](../kafka/)에서 다룹니다.
> 이 코스는 그 위에서 **Spring 이 무엇을 대신해 주고, 무엇을 여러분이 직접 정해야 하는지**에 집중합니다.
> 브로커 지식이 필요한 대목마다 Kafka 코스의 해당 스텝을 링크로 걸어 두었습니다. 먼저 읽고 오시면 이해가 훨씬 빠릅니다.

---

## 시작하기 (5분)

```bash
# 1. Kafka 단일 브로커(KRaft) + MySQL 8 기동
cd spring-kafka/project
docker compose up -d

# 2. 브로커가 떴는지 확인
docker exec -it learn-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# 3. 애플리케이션 기동
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

INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : Starting OrderServiceApplication using Java 21.0.2
INFO 12841 --- [           main] o.a.k.c.c.ConsumerConfig                 : ConsumerConfig values:
INFO 12841 --- [           main] o.a.k.c.u.AppInfoParser                  : Kafka version: 3.7.0
INFO 12841 --- [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-inventory-1, groupId=inventory] Successfully joined group with generation Generation{generationId=1}
INFO 12841 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : inventory: partitions assigned: [orders-0, orders-1, orders-2]
INFO 12841 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 2.418 seconds (process running for 2.771)
```

마지막 두 줄, 특히 `partitions assigned: [orders-0, orders-1, orders-2]` 가 보이면 성공입니다.
**이 한 줄이 이 코스에서 가장 자주 보게 될 로그입니다.** 리스너 컨테이너가 살아 있고 파티션을 할당받았다는 뜻입니다.

문제가 생기면 언제든 초기화하세요. 토픽·오프셋·DB 가 완전한 초기 상태로 돌아갑니다.

```bash
docker compose down -v && docker compose up -d
```

> 자세한 셋업(`build.gradle`, `docker-compose.yml`, `application.yml` 전문)은 [실습 프로젝트 셋업](project/)에 있습니다.

---

## 커리큘럼

### 1부 — 기초 (Step 01~05)
> Kafka 를 한 번도 안 써 봤어도 됩니다. 메시지를 보내고 받는 것부터 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 환경 구축과 첫 메시지 | 의존성, `KafkaTemplate.send`, `@KafkaListener`, **자동 설정이 몰래 만들어 주는 빈들** |
| [02](step-02-kafka-template/) | KafkaTemplate 과 프로듀서 | `send` 오버로드, **`CompletableFuture` 비동기 결과 처리**, `ProducerRecord`, **`send().get()` 성능 함정** |
| [03](step-03-listener-basics/) | @KafkaListener 기초 | `ConcurrentKafkaListenerContainerFactory`, `groupId`, **concurrency 와 파티션 수의 관계**, `topicPartitions` |
| [04](step-04-serialization/) | 직렬화와 역직렬화 | `JsonSerializer/Deserializer`, **`__TypeId__` 헤더가 만드는 패키지 결합**, trusted packages, **`ErrorHandlingDeserializer`** |
| [05](step-05-message-conversion/) | 메시지 변환과 헤더 | `@Payload/@Header/@Headers`, `ConsumerRecord` 직접 수신, `MessageConverter`, 추적 ID 전파 |

### 2부 — 신뢰성 (Step 06~09)
> 이 코스의 심장부입니다. **설정 하나가 조용히 메시지를 잃거나 순서를 뒤바꾸는** 지점들을 재현합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [06](step-06-ack-modes/) | 오프셋 커밋과 AckMode | RECORD/BATCH/TIME/COUNT/MANUAL/MANUAL_IMMEDIATE 비교, **배치 리스너 부분 실패의 커밋 범위 함정** |
| [07](step-07-error-handling/) | 에러 처리와 재시도 | `DefaultErrorHandler`, `ExponentialBackOff`, `DeadLetterPublishingRecoverer`, **재시도가 파티션 전체를 멈추는 함정** |
| [08](step-08-retryable-topic/) | @RetryableTopic 논블로킹 재시도 | retry 토픽 자동 생성, `@DltHandler`, **논블로킹 재시도가 순서를 깨뜨리는 트레이드오프**, 선택 기준표 |
| [09](step-09-transactions/) | 트랜잭션 | `KafkaTransactionManager`, `executeInTransaction`, `read_committed`, **DB 트랜잭션과 Kafka 트랜잭션은 원자적이지 않다** |

### 3부 — 운영 (Step 10~13)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [10](step-10-container-control/) | 리스너 컨테이너 제어 | `KafkaListenerEndpointRegistry`, `autoStartup=false`, pause/resume, `RecordFilterStrategy`, 리밸런스 리스너 |
| [11](step-11-testing/) | 테스트 | `@EmbeddedKafka`, **Testcontainers 실제 브로커**, `awaitility`, 두 방식 비교표, **간헐 실패의 원인** |
| [12](step-12-observability/) | 관측성과 운영 | Micrometer 지표, **컨슈머 랙 노출**, Actuator, `observationEnabled` 분산 추적 |
| [13](step-13-patterns/) | 실전 패턴과 최종 프로젝트 | 멱등 컨슈머, **Transactional Outbox 구현**, 순서 보장 전략, 주문 서비스 종합 실습 |

---

## 각 스텝의 구성

```
step-07-error-handling/
├── index.md         ← 교재 본문. 개념 설명 + 코드 + 실제 콘솔 로그 + 함정/팁
├── Practice.java    ← 교재의 모든 예제를 절 번호 주석과 함께 담은 실행 파일
├── Exercise.java    ← 연습문제 (문제만, "여기에 작성:" 자리 비움)
└── Solution.java    ← 정답 + 왜 그 답인지 설명하는 긴 주석
```

**권장 학습 방법**

1. `index.md` 를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마세요.
2. **콘솔 로그를 교재와 대조하세요.** 다르면 멈추고 원인을 찾으세요. Kafka 는 "돌아가는 것처럼 보이지만 틀린" 상태가 흔합니다.
3. `Exercise.java` 를 풀고 `Solution.java` 로 채점합니다.
4. 다음 스텝으로.

```bash
# 특정 스텝의 Practice 를 프로필로 골라 실행
./gradlew bootRun --args='--spring.profiles.active=step07'

# 토픽 상태를 눈으로 확인
docker exec -it learn-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders --from-beginning --property print.headers=true
```

> 💡 **콘솔 컨슈머를 항상 켜 두세요.** 이 코스의 절반은 "내가 보낸 게 정말 갔는가"를 확인하는 일입니다.
> 터미널 창을 하나 더 띄워 `kafka-console-consumer.sh` 를 붙여 두면 디버깅 시간이 절반으로 줄어듭니다.

---

## 실습 도메인 — 주문 서비스

가상의 주문 서비스입니다. 주문이 생성되면 `orders` 토픽에 `OrderCreated` 이벤트를 발행하고, **재고**와 **알림** 두 컨슈머가 각자의 컨슈머 그룹으로 소비합니다.

```
  ┌──────────────────┐
  │  OrderService    │  주문 생성 → OrderCreated 이벤트 발행
  │  (Producer)      │
  └────────┬─────────┘
           │ KafkaTemplate.send("orders", orderId, event)
           │ key = orderId  → 같은 주문은 항상 같은 파티션
           ▼
  ┌─────────────────────────────────────────────┐
  │  topic: orders   (partitions=3, RF=1)       │
  │  ┌─────────┐ ┌─────────┐ ┌─────────┐        │
  │  │orders-0 │ │orders-1 │ │orders-2 │        │
  │  └─────────┘ └─────────┘ └─────────┘        │
  └───────┬──────────────────────┬──────────────┘
          │                      │
          │ group=inventory      │ group=notification
          ▼                      ▼
  ┌──────────────────┐   ┌──────────────────┐
  │ InventoryListener│   │ NotifyListener   │
  │  재고 차감       │   │  알림 발송       │
  └────────┬─────────┘   └──────────────────┘
           │ 처리 실패 (재시도 소진)
           ▼
  ┌──────────────────┐
  │ topic: orders.DLT│  ← Step 07~08 에서 만듭니다
  └──────────────────┘
```

| 토픽 | 파티션 | 용도 | 등장 스텝 |
|---|---:|---|---|
| `orders` | 3 | `OrderCreated` 이벤트 본선 | 전 스텝 |
| `orders.DLT` | 3 | 재시도를 모두 소진한 실패 메시지 | Step 07, 08 |
| `orders-retry-0` ~ `-2` | 3 | `@RetryableTopic` 이 자동 생성 | Step 08 |
| `orders.outbox` | 3 | Outbox 릴레이가 발행 | Step 13 |
| `payments` | 1 | 트랜잭션 실습용 두 번째 토픽 | Step 09 |

**MySQL 8** 도 함께 띄웁니다. Step 09 의 "DB 트랜잭션 vs Kafka 트랜잭션", Step 13 의 **Transactional Outbox** 와 **멱등 컨슈머 처리 이력 테이블**에 필요합니다.

| 테이블 | 용도 | 등장 스텝 |
|---|---|---|
| `orders` | 주문 원장 | Step 09, 13 |
| `outbox_event` | Outbox 이벤트 적재 | Step 13 |
| `processed_message` | 멱등 컨슈머의 처리 이력 (`message_id` UNIQUE) | Step 13 |

### 데이터가 항상 똑같은 이유

주문 ID 는 `RANDOM` 이 아니라 `ORD-0001` 부터 순번으로 만듭니다. 키가 결정적이므로 **파티션 배정도 항상 동일**합니다.
교재에 `orders-1` 이라고 적혀 있으면 여러분 화면에서도 `orders-1` 입니다. 다르면 파티션 수나 파티셔너 설정이 교재와 다른 것이니, 그 자리에서 멈추고 확인하세요.

---

## 실습 규칙

- **토픽은 스텝마다 새로 만들지 말고, 오프셋을 리셋하세요.** 같은 토픽을 계속 쓰면서 컨슈머 그룹만 바꾸는 것이 학습에 유리합니다.
  ```bash
  docker exec -it learn-kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group inventory --topic orders --reset-offsets --to-earliest --execute
  ```
  ⚠️ 리셋은 **해당 그룹의 컨슈머가 전부 내려간 상태**에서만 됩니다. 앱을 끄고 실행하세요.
- 컨슈머 그룹 이름은 스텝별 접두사를 씁니다: `s06-inventory`, `s07-inventory` …. 앞 스텝의 커밋된 오프셋이 뒷 스텝 실습을 오염시키는 것을 막습니다.
- 실습 흔적을 지우려면:
  ```bash
  docker exec -it learn-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list \
    | grep -E '^s[0-9]{2}-' \
    | xargs -I{} docker exec -i learn-kafka kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic {}
  ```
- **`auto.offset.reset` 은 실습 내내 `earliest`** 입니다. 기본값 `latest` 로 두면 "앱을 켜기 전에 보낸 메시지"가 안 보여서, 코드가 틀린 건지 설정이 그런 건지 구분이 안 됩니다.

---

## 이 코스가 특히 신경 쓴 것

**에러 없이 조용히 잘못 동작하는 Kafka 코드**를 잡는 데 집중했습니다.
Kafka 는 실패해도 예외를 안 던지는 경우가 많습니다. 로그는 깨끗한데 메시지가 사라지거나 순서가 뒤바뀝니다. 예를 들면:

- `kafkaTemplate.send(...)` 가 **예외 없이 리턴해도 브로커에 안 갔을 수 있습니다.** `CompletableFuture` 를 안 보면 영원히 모릅니다 (Step 02)
- 그렇다고 `send().get()` 을 매번 붙이면 **처리량이 20배 떨어집니다.** 배치가 통째로 무력화됩니다 (Step 02)
- `JsonDeserializer` 는 `__TypeId__` 헤더의 **패키지 경로까지 일치**해야 합니다. 컨슈머 쪽 패키지를 리팩터링하는 순간 전 메시지가 실패합니다 (Step 04)
- 역직렬화 실패 메시지 하나가 **파티션을 영원히 멈춰 세웁니다**(포이즌 필). `ErrorHandlingDeserializer` 없이는 재시작해도 같은 자리에서 죽습니다 (Step 04)
- `AckMode.BATCH`(기본값) 에서 배치 중간이 실패하면, **성공한 앞부분까지 재처리**되거나 **실패한 뒤가 커밋**됩니다 (Step 06)
- `DefaultErrorHandler` 의 블로킹 재시도는 **그 파티션의 뒤 메시지 전부를 대기**시킵니다. 백오프 10초 × 3회면 파티션이 30초 멈춥니다 (Step 07)
- `@RetryableTopic` 은 파티션을 안 막지만, 대신 **메시지 순서 보장을 포기**합니다. 재시도된 메시지는 늦게 도착합니다 (Step 08)
- `@Transactional` 하나로 DB 와 Kafka 를 묶었다고 믿으면 안 됩니다. **둘은 원자적이지 않습니다** (Step 09)
- `concurrency` 를 파티션 수보다 크게 잡으면 **남는 스레드는 그냥 놉니다.** 로그에 경고도 없습니다 (Step 03)
- `@EmbeddedKafka` 테스트가 **로컬에선 통과하고 CI 에서만 실패**하는 건 대개 타이밍입니다. `Thread.sleep` 이 원인입니다 (Step 11)

각 스텝의 `⚠️ 함정` 블록을 특히 눈여겨 보세요.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Java | 21 (검증: Temurin 21.0.2) |
| Spring Boot | 3.2.5 |
| Spring Kafka | 3.1.4 (Boot 가 관리) |
| Apache Kafka | 3.7.0 (KRaft 모드, 단일 브로커) |
| kafka-clients | 3.6.1 (Spring Kafka 3.1.x 기본) |
| 빌드 | Gradle 8.7, **Groovy DSL** |
| 브로커 | `127.0.0.1:9092` |
| MySQL | 8.0, `127.0.0.1:3307`, `learner` / `learn1234`, DB `orderdb` |
| 기본 토픽 | `orders` (partitions=3, replication-factor=1) |

> 실습 브로커는 **단일 노드·복제본 1개**입니다. `acks=all` 을 걸어도 실제로는 리더 하나만 응답합니다.
> 복제·ISR·`min.insync.replicas` 의 진짜 동작은 [Kafka 코스 Step 08](../kafka/step-08-replication/) 에서 다중 브로커로 확인하세요.
> 이 코스에서는 **클라이언트 쪽 설정이 무엇을 의미하는지**에 집중합니다.
