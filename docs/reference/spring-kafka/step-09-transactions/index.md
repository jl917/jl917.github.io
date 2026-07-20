# Step 09 — 트랜잭션

> **학습 목표**
> - Kafka 트랜잭션이 보장하는 것(여러 토픽·파티션 원자적 쓰기, 오프셋 커밋 포함)과 보장하지 않는 것(외부 시스템)을 구분한다
> - `transaction-id-prefix` 한 줄이 자동으로 켜는 빈 3개를 기동 로그로 확인하고, 그 한 줄이 **트랜잭션 밖 `send` 를 전부 실패시키는 것**을 재현한다
> - `executeInTransaction` 으로 `orders` 와 `payments` 두 토픽에 원자적으로 발행하고, 중간 실패 시 **둘 다 안 보이는 것**을 확인한다
> - `isolation.level` 의 기본값이 `read_uncommitted` 라서 **abort 된 메시지가 컨슈머에게 배달되는 것**을 두 컨슈머로 나란히 재현한다
> - `@Transactional` 하나로 DB 와 Kafka 를 묶었다고 믿을 때 생기는 **유령 이벤트**와 **이벤트 유실**을 각각 DB 조회 결과와 함께 재현한다
> - 트랜잭션의 처리량 비용을 실측한다 (18,400 msg/s → 6,100 msg/s)
>
> **선행 스텝**: Step 08 — @RetryableTopic 논블로킹 재시도
> **예상 소요**: 90분

---

## 9-0. 실습 준비

이 스텝은 **MySQL 을 같이 씁니다.** Step 01 이후 처음입니다. 컨테이너 두 개가 다 떠 있어야 합니다.

```bash
docker compose ps
alias mq='docker exec -i learn-kafka-mysql mysql -ulearner -plearn1234 orderdb -t -e'
mq 'SELECT * FROM orders'
```

**결과**
```
NAME                IMAGE                COMMAND                  STATUS                   PORTS
learn-kafka         apache/kafka:3.7.0   "/__cacert_entrypoin…"   Up 3 minutes (healthy)   0.0.0.0:9092->9092/tcp
learn-kafka-mysql   mysql:8.0            "docker-entrypoint.s…"   Up 3 minutes (healthy)   0.0.0.0:3307->3306/tcp
Empty set
```

`orders` 테이블이 비어 있는 상태에서 시작합니다. `mq` 별칭은 9-7 에서 계속 씁니다. 지저분해지면 `mq 'TRUNCATE TABLE orders'` 로 비우세요.

토픽은 `orders`(파티션 3)와 `payments`(파티션 1) 두 개를 씁니다. 파티션 수가 다른 두 토픽을 골랐습니다. **서로 다른 토픽, 서로 다른 파티션에 걸친 쓰기가 원자적인가**가 이 스텝의 출발점이기 때문입니다.

---

## 9-1. Kafka 트랜잭션이 보장하는 것과 아닌 것

Kafka 트랜잭션은 이름 때문에 오해를 삽니다. DB 트랜잭션을 떠올리면 "여기에 DB 저장도 같이 묶이겠지" 싶지만, **Kafka 트랜잭션의 경계는 Kafka 안쪽뿐**입니다.

**보장하는 것은 둘입니다. ① 여러 토픽·파티션에 대한 원자적 쓰기** — 한 트랜잭션 안에서 `orders-0`, `orders-2`, `payments-0` 세 파티션에 썼다면 셋이 모두 보이거나 모두 안 보입니다. **② consume-process-produce 의 오프셋 커밋까지 하나의 트랜잭션** — "읽은 것을 처리해서 다시 쓴다"에서 "썼다"와 "읽은 위치를 커밋했다"가 따로 놀면 중복 또는 유실이 생깁니다. 오프셋 커밋도 결국 `__consumer_offsets` 토픽에 쓰는 일이므로, 같은 트랜잭션에 넣는 것이 자연스럽습니다.

```
  ┌──────────────── 하나의 Kafka 트랜잭션 ─────────────────┐
  │  send("orders",   ORD-0001)         → orders-1         │
  │  send("payments", ORD-0001)         → payments-0       │
  │  sendOffsetsToTransaction(orders-1@42, s09-cpp)        │
  └──── commit → 셋 다 확정 / abort → 셋 다 무효 ──────────┘
     orderRepository.save() / restClient.post()  ← 이 바깥. 절대 안 묶임
```

**보장하지 않는 것은 단순합니다. Kafka 가 아닌 모든 것.** DB, HTTP 호출, 파일 쓰기, 캐시 갱신. 코디네이터는 여러분의 MySQL 이 롤백했는지 알 방법이 없고, 알아도 할 수 있는 일이 없습니다. **이 문장이 이 스텝 전체의 주제입니다.** 9-7 에서 코드로 재현합니다.

### `transactional.id` 와 프로듀서 펜싱

트랜잭션을 쓰려면 프로듀서에 `transactional.id` 를 줘야 합니다. 이 값은 프로듀서의 **논리적 신원**입니다. 프로듀서가 `initTransactions()` 를 호출하면 브로커의 트랜잭션 코디네이터가 그 id 에 **epoch(세대 번호)** 를 부여합니다.

같은 `transactional.id` 로 새 프로듀서가 등장하면 코디네이터는 epoch 를 1 올리고, **낡은 epoch 의 프로듀서가 보내는 요청을 전부 거절**합니다. 이것이 **펜싱(fencing)** 입니다. 죽은 줄 알았던 인스턴스가 되살아나 옛 트랜잭션을 커밋하는 좀비 시나리오를 막습니다.

거절당한 쪽은 `ProducerFencedException: There is a newer producer with the same transactionalId which fences the current one.` 을 받습니다.

> 💡 `transactional.id` 는 **재시작해도 같아야** 의미가 있습니다. 재시작마다 랜덤 값을 쓰면 펜싱이 동작하지 않고, 코디네이터에 쓰레기 id 만 쌓입니다. 반대로 **동시에 뜬 두 인스턴스가 같은 값**을 쓰면 서로를 죽입니다. 이 두 요구가 충돌하는 지점이 9-11 의 함정입니다.

> 💡 트랜잭션 코디네이터와 `__transaction_state` 토픽의 브로커 내부 동작, 2PC 마커 기록, LSO 계산은 [Kafka 코스 Step 07](../../kafka/step-07-delivery-semantics/) 을 참고하세요. 이 스텝은 **Spring 쪽에서 무엇을 켜고 무엇을 직접 정해야 하는지**만 다룹니다.

---

## 9-2. 설정 — 한 줄로 켜진다

```yaml
spring:
  kafka:
    producer:
      transaction-id-prefix: tx-order-      # ← 이 한 줄
```

이 한 줄이 하는 일이 셋입니다.

| 켜지는 것 | 내용 |
|---|---|
| `DefaultKafkaProducerFactory` | `transactionIdPrefix` 가 설정되고, **트랜잭션 프로듀서 캐시**를 유지 |
| `KafkaTemplate` | `transactional = true`. 모든 `send` 가 트랜잭션 안에서만 허용 |
| `KafkaTransactionManager` | 빈으로 **자동 등록**. `@Transactional` 로 쓸 수 있게 됨 |

`KafkaAutoConfiguration` 이 `spring.kafka.producer.transaction-id-prefix` 가 있을 때만 `KafkaTransactionManager` 빈을 만듭니다. 직접 선언할 필요가 없습니다.

기동 로그로 확인합니다. `ProducerConfig` 로거를 `INFO` 로 잠시 올리세요.

```yaml
logging:
  level:
    org.apache.kafka.clients.producer.ProducerConfig: INFO
```

**결과**
```
INFO 14107 --- [           main] o.a.k.c.p.ProducerConfig                 : ProducerConfig values:
	acks = -1
	enable.idempotence = true
	transaction.timeout.ms = 60000
	transactional.id = tx-order-0
INFO 14107 --- [ad | producer-1] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-1, transactionalId=tx-order-0] ProducerId set to 1000 with epoch 0
```

세 줄을 보세요.

- `transactional.id = tx-order-0` — 접두사 뒤에 **0** 이 붙었습니다. Spring 이 프로듀서를 만들 때마다 JVM 내부 카운터를 붙입니다.
- `enable.idempotence = true` — 트랜잭션은 멱등 프로듀서를 **강제**합니다. `application.yml` 에서 `false` 로 적어 뒀어도 무시되고 켜집니다.
- `ProducerId set to 1000 with epoch 0` — `initTransactions()` 가 코디네이터에게서 PID 와 epoch 를 받아 온 순간입니다. **9-11 에서 이 epoch 가 올라가는 것**을 보게 됩니다.

> ⚠️ **함정 — `transaction-id-prefix` 를 켜면 JPA 트랜잭션 매니저가 사라질 수 있다**
> Spring Boot 의 JPA 자동 설정은 `@ConditionalOnMissingBean(TransactionManager.class)` 조건으로 `JpaTransactionManager` 를 만듭니다. 그런데 `KafkaTransactionManager` 도 `TransactionManager` 입니다.
> 자동 설정 순서에 따라 **Kafka 쪽이 먼저 등록되면 JPA 트랜잭션 매니저가 아예 안 만들어집니다.** 그러면 여러분의 `@Transactional` 메서드는 조용히 **Kafka 트랜잭션 매니저 위에서** 돌고, DB 는 오토커밋으로 나갑니다. 예외도 경고도 없습니다. 롤백 테스트를 짜기 전까지 아무도 모릅니다.
> 반대로 둘 다 등록되면 `@Transactional` 이 `NoUniqueBeanDefinitionException` 으로 기동을 막습니다. 이건 그나마 다행인 경우입니다.
> **해결: 트랜잭션 매니저를 항상 이름으로 지정하세요.** 이 스텝의 모든 예제가 그렇게 되어 있습니다.
> ```java
> @Transactional("dbTxManager")     // DB
> @Transactional("kafkaTransactionManager")  // Kafka
> ```

---

## 9-3. ⚠️ 함정 — 그 한 줄을 켜는 순간 기존 `send` 가 전부 죽는다

`transaction-id-prefix` 를 추가하고 앱을 띄우면, 어제까지 잘 돌던 코드가 이렇게 됩니다.

```java
// [9-3] transaction-id-prefix 를 켠 상태에서 그냥 send
template.send("orders", "ORD-0001", OrderCreated.of(1));   // ← 폭발
```

**결과**
```
ERROR 14107 --- [           main] o.s.b.SpringApplication                  : Application run failed

java.lang.IllegalStateException: No transaction is in process; possible solutions: run the template
operation within the scope of a template.executeInTransaction() operation, start a transaction with
@Transactional before invoking the template method, run in a transaction started by a listener
container when consuming a record
	at org.springframework.kafka.core.KafkaTemplate.getTheProducer(KafkaTemplate.java:800)
	at org.springframework.kafka.core.KafkaTemplate.doSend(KafkaTemplate.java:673)
	at org.springframework.kafka.core.KafkaTemplate.send(KafkaTemplate.java:435)
```

**이것이 "트랜잭션 설정을 추가했더니 앱이 안 뜬다"의 첫 번째 원인입니다.** 예외 메시지가 친절하게 해법 세 가지를 나열해 주지만, `ApplicationRunner` 안에서 터지면 기동 실패로 보여서 설정 자체를 의심하게 됩니다.

핵심은 이겁니다. **`transactional = true` 인 `KafkaTemplate` 은 "지금 진행 중인 트랜잭션"이 없으면 프로듀서를 내주지 않습니다.** 진행 중인 트랜잭션은 셋 중 하나입니다.

1. `template.executeInTransaction(...)` 콜백 안 (9-4)
2. `@Transactional` 로 시작된 트랜잭션 안 (9-7)
3. 리스너 컨테이너가 레코드를 소비하며 시작한 트랜잭션 안 (9-6)

### 해결책 두 가지

**① `executeInTransaction` 으로 감싼다** — 권장. 9-4 에서 다룹니다.

**② `allowNonTransactional = true`** — 트랜잭션이 없을 때는 트랜잭션 없이 보내도록 허용합니다. 예외는 사라집니다.

```java
KafkaTemplate<String, Object> t = new KafkaTemplate<>(pf);
t.setAllowNonTransactional(true);     // 트랜잭션 없으면 그냥 보낸다
```

> ⚠️ **함정 — `allowNonTransactional` 은 "고쳤다"가 아니라 "예외만 껐다"**
> 이 옵션을 켜면 트랜잭션 밖 `send` 는 **트랜잭션 없이** 나갑니다. 원자성이 없다는 뜻입니다.
> 증상이 고약한 이유는, 나중에 누가 그 메서드를 `@Transactional` 안으로 옮기면 **같은 코드가 갑자기 트랜잭션 안에서 돌기 시작**한다는 점입니다. 트랜잭션 유무가 호출 문맥에 따라 조용히 바뀝니다.
> 로그도, 예외도 없습니다. 어떤 `send` 가 트랜잭션 안이었는지 사후에 알 방법이 없습니다.
> **원칙: `allowNonTransactional` 은 레거시 코드를 단계적으로 이관하는 동안에만 켜고, 이관이 끝나면 끄세요.**

---

## 9-4. `executeInTransaction` — 두 토픽에 원자적으로 발행

주문을 만들면서 `orders` 와 `payments` 두 토픽에 각각 이벤트를 넣어야 한다고 합시다. 둘 중 하나만 나가면 결제 없는 주문 또는 주문 없는 결제가 생깁니다. 콜백이 정상 리턴하면 커밋, 예외를 던지면 abort 입니다.

```java
// [9-4] 두 토픽에 원자적으로 발행 — 예외를 던져 abort 시켜 봅니다
template.executeInTransaction(t -> {
    t.send("orders",   "ORD-0002", OrderCreated.of(2));
    t.send("payments", "ORD-0002", PaymentRequested.from(OrderCreated.of(2)));
    throw new IllegalStateException("[9-4] 결제 한도 초과 — 트랜잭션을 abort 합니다");
});
```

**결과**
```
INFO 14107 --- [ad | producer-1] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-1, transactionalId=tx-order-0] Transition from state READY to IN_TRANSACTION
INFO 14107 --- [           main] c.e.o.s.Practice$ExecInTxDemo            : [9-4] send orders   ORD-0002
INFO 14107 --- [           main] c.e.o.s.Practice$ExecInTxDemo            : [9-4] send payments ORD-0002
INFO 14107 --- [ad | producer-1] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-1, transactionalId=tx-order-0] Transition from state IN_TRANSACTION to ABORTING_TRANSACTION
ERROR 14107 --- [           main] c.e.o.s.Practice$ExecInTxDemo            : [9-4] abort 되었습니다: [9-4] 결제 한도 초과 — 트랜잭션을 abort 합니다
```

이제 두 토픽을 **`read_committed` 로** 확인합니다.

```bash
kcc --topic orders   --from-beginning --isolation-level read_committed --timeout-ms 3000
kcc --topic payments --from-beginning --isolation-level read_committed --timeout-ms 3000
```

**결과**
```
{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
Processed a total of 1 messages
---
{"orderId":"ORD-0001","amount":11000,"requestedAt":"2025-01-01T00:01:00Z"}
Processed a total of 1 messages
```

**`ORD-0002` 가 양쪽 어디에도 없습니다.** 두 파티션(`orders-2`, `payments-0`)에 걸친 쓰기가 통째로 무효가 되었습니다.

> 💡 **`executeInTransaction` 안에서 `send().get()` 을 부르지 마세요.**
> 트랜잭션 커밋은 콜백이 끝난 뒤에 일어나므로, 콜백 안에서 `get()` 으로 결과를 기다려도 얻는 건 "브로커가 레코드를 받았다"까지입니다. 커밋 여부가 아닙니다. 게다가 Step 02 에서 봤듯 매 건 `get()` 은 배치를 무력화해 처리량을 무너뜨립니다.
> 콜백은 `send` 만 나열하고 리턴하세요. 실패는 콜백 밖에서 예외로 받으면 됩니다.

---

## 9-5. `isolation.level` — 기본값이 배신하는 지점

9-4 에서 `--isolation-level read_committed` 를 **명시**했습니다. 왜 명시했는지가 이 절입니다.

```bash
kcc --topic orders --from-beginning --timeout-ms 3000        # 옵션 없이
```

**결과**
```
{"orderId":"ORD-0001","customerId":1001,"sku":"SKU-002","quantity":2,"amount":11000,"createdAt":"2025-01-01T00:01:00Z"}
{"orderId":"ORD-0002","customerId":1002,"sku":"SKU-003","quantity":3,"amount":12000,"createdAt":"2025-01-01T00:02:00Z"}
Processed a total of 2 messages
```

**abort 된 `ORD-0002` 가 보입니다.**

`isolation.level` 의 기본값은 **`read_uncommitted`** 입니다. 프로듀서에 트랜잭션을 아무리 정성껏 걸어도, **컨슈머가 기본 설정이면 abort 된 메시지를 그대로 받습니다.** 프로듀서 쪽만 고치고 끝냈다고 믿는 것이 이 스텝에서 가장 흔한 실수입니다.

Java 컨슈머 둘을 나란히 붙여 보면 더 분명합니다. `isolation.level` 만 다른 컨테이너 팩토리(`uncommittedFactory` / `committedFactory`) 를 만들고, `groupId` 를 나눈 `@KafkaListener` 를 하나씩 붙입니다. **`groupId` 를 안 나누면 두 리스너가 파티션을 나눠 가져 비교 자체가 성립하지 않습니다.** 그 상태로 `ORD-0011`(커밋) → `ORD-0012`(abort) → `ORD-0013`(커밋) 을 발행합니다.

**결과**
```
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s.Practice$IsolationDemo           : [9-5] read_uncommitted 수신: ORD-0011
INFO 14107 --- [ntainer#1-0-C-1] c.e.o.s.Practice$IsolationDemo           : [9-5] read_committed   수신: ORD-0011
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s.Practice$IsolationDemo           : [9-5] read_uncommitted 수신: ORD-0012      ← abort 된 메시지
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s.Practice$IsolationDemo           : [9-5] read_uncommitted 수신: ORD-0013
INFO 14107 --- [ntainer#1-0-C-1] c.e.o.s.Practice$IsolationDemo           : [9-5] read_committed   수신: ORD-0013
```

| 그룹 | ORD-0011 (커밋) | ORD-0012 (**abort**) | ORD-0013 (커밋) |
|---|:---:|:---:|:---:|
| `s09-uncommitted` (기본값) | ✅ | **✅ 받음** | ✅ |
| `s09-committed` | ✅ | ❌ 안 받음 | ✅ |

설정은 컨슈머 쪽 한 줄입니다.

```yaml
spring:
  kafka:
    consumer:
      properties:
        isolation.level: read_committed
```

> ⚠️ **함정 — 프로듀서만 트랜잭션으로 바꾸면 아무것도 달라지지 않는다**
> 프로듀서에 `transaction-id-prefix` 를 넣고, `executeInTransaction` 으로 감싸고, 실패 시 abort 까지 완벽하게 구현해도, **컨슈머가 `read_uncommitted`(기본값) 이면 abort 된 메시지가 전부 배달됩니다.**
> 증상: "롤백했는데 왜 이벤트가 처리됐지?" 로그에는 정상적인 `abort` 기록이 남아 있어서, 원인을 프로듀서 쪽에서 찾다가 시간을 다 씁니다.
> 게다가 이 설정은 **컨슈머 애플리케이션 소유자가 바꿔야** 합니다. 이벤트를 발행하는 팀과 소비하는 팀이 다르면, 발행 팀이 트랜잭션을 도입해도 소비 팀이 설정을 안 바꾸면 효과가 0 입니다.
> **트랜잭션을 도입하기로 했다면 컨슈머 목록부터 만드세요.**

### `read_committed` 의 대가 — LSO 지연

`read_committed` 컨슈머는 **LSO(Last Stable Offset)** 까지만 읽습니다. LSO 는 "아직 결론이 안 난(진행 중인) 트랜잭션이 시작된 지점" 직전입니다. 즉 진행 중인 트랜잭션이 하나라도 있으면, 그 뒤에 커밋 완료된 메시지가 아무리 쌓여도 **컨슈머는 그것을 볼 수 없습니다.**

```
파티션 orders-1
  offset:  40    41    42          43        44        45
           ✔     ✔     [T1 시작]   ✔(T2커밋)  ✔(T2커밋)  ✔(T2커밋)
                       ↑ LSO = 42
  read_uncommitted → 45 까지 읽음 / read_committed → 41 까지. T1 이 끝날 때까지 대기
```

`T1` 이 `transaction.timeout.ms`(기본 60초) 를 다 채우고 타임아웃 abort 될 때까지, 그 파티션의 `read_committed` 컨슈머는 **최대 60초 멈춥니다.** 랙 그래프에 톱니 모양이 생기고, 원인이 컨슈머 쪽에는 전혀 안 보입니다.

| | `read_uncommitted` (기본) | `read_committed` |
|---|---|---|
| abort 된 메시지 | **읽음** | 안 읽음 |
| 읽을 수 있는 상한 | High Watermark | **LSO** |
| 지연 | 없음 | 진행 중 트랜잭션에 막힘 (최대 `transaction.timeout.ms`) |
| 트랜잭션 프로듀서와 조합 | **의미 없음** | 필수 |

---

## 9-6. consume-process-produce — 오프셋까지 한 트랜잭션에

"`orders` 를 읽어서 `payments` 를 만든다"가 전형적인 형태입니다. 여기서 두 가지가 원자적이어야 합니다.

- `payments` 로의 발행
- `orders` 의 소비 오프셋 커밋

이 둘이 갈라지면, 발행 후 커밋 실패 시 **중복 발행**이, 커밋 후 발행 실패 시 **유실**이 생깁니다. Kafka 트랜잭션은 둘을 묶습니다. 방법은 **리스너 컨테이너에 `KafkaTransactionManager` 를 주는 것**입니다.

```java
// [9-6] 컨테이너가 레코드마다 트랜잭션을 시작하도록 설정
ConcurrentKafkaListenerContainerFactory<String, Object> f = new ConcurrentKafkaListenerContainerFactory<>();
f.setConsumerFactory(cf);
f.getContainerProperties().setTransactionManager(ktm);              // Spring Kafka 3.1.x (이 코스)
// f.getContainerProperties().setKafkaAwareTransactionManager(ktm); // Spring Kafka 3.2+
```

> ⚠️ **함정 — `setTransactionManager` 는 3.2 에서 deprecated 되었다**
> Spring Kafka 3.2 부터 `ContainerProperties.setTransactionManager(PlatformTransactionManager)` 가 deprecated 되고, **`setKafkaAwareTransactionManager(KafkaAwareTransactionManager<?>)`** 가 그 자리를 대신합니다.
> 이유는 타입입니다. 기존 시그니처는 `PlatformTransactionManager` 아무거나 받았기 때문에, 실수로 **`DataSourceTransactionManager` 를 넣어도 컴파일이 되고 기동도 됩니다.** 그러면 컨테이너는 DB 트랜잭션을 시작할 뿐 **오프셋을 트랜잭션에 넣지 않습니다.** 여러분은 consume-process-produce 를 켰다고 믿지만 실제로는 안 켜져 있습니다. 로그에 아무 차이도 없습니다.
> 새 메서드는 `KafkaAwareTransactionManager` 만 받으므로 이 실수가 **컴파일 에러**가 됩니다. 업그레이드하면 반드시 바꾸세요.

리스너는 평범합니다.

```java
@KafkaListener(topics = "orders", groupId = "s09-cpp", containerFactory = "cppFactory")
void process(ConsumerRecord<String, OrderCreated> rec) {
    log.info("[9-6] consume {}@{} {}", rec.topic(), rec.offset(), rec.key());
    template.send("payments", rec.key(), PaymentRequested.from(rec.value()));   // 트랜잭션 안
}
```

`template.send` 는 컨테이너가 이미 시작해 둔 트랜잭션에 올라탑니다. 9-3 의 `IllegalStateException` 이 안 나는 세 번째 경우가 이것입니다.

**결과**
```
INFO 14107 --- [ntainer#0-0-C-1] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-2, transactionalId=tx-order-1] ProducerId set to 1001 with epoch 0
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s.Practice$CppListener             : [9-6] consume orders@0 ORD-0001
DEBUG 14107 --- [ntainer#0-0-C-1] o.s.k.t.KafkaTransactionManager          : Initiating transaction commit
DEBUG 14107 --- [ntainer#0-0-C-1] o.s.k.c.DefaultKafkaProducerFactory      : sendOffsetsToTransaction: {orders-1=OffsetAndMetadata{offset=1}} group=s09-cpp
INFO 14107 --- [ntainer#0-0-C-1] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-2, transactionalId=tx-order-1] Transition from state IN_TRANSACTION to COMMITTING_TRANSACTION
```

`sendOffsetsToTransaction: {orders-1=OffsetAndMetadata{offset=1}} group=s09-cpp` — **이 한 줄이 이 절의 증거입니다.** 컨테이너가 커밋 직전에 소비 오프셋을 트랜잭션에 밀어 넣었습니다. 여러분이 코드로 부른 적이 없는데 호출됩니다.

리스너가 예외를 던지면 발행과 오프셋 커밋이 함께 무효가 되어, 같은 레코드를 다시 읽습니다.

**결과** (리스너가 `ORD-0005` 에서 실패)
```
ERROR 14107 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Transaction rolled back
INFO 14107 --- [ntainer#0-0-C-1] o.a.k.c.p.i.TransactionManager           : Transition from state IN_TRANSACTION to ABORTING_TRANSACTION
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s.Practice$CppListener             : [9-6] consume orders@4 ORD-0005      ← 같은 오프셋 재소비
```

> 💡 **`AckMode` 는 무시됩니다.** 컨테이너에 트랜잭션 매니저가 붙으면 `ack-mode` 설정과 무관하게 오프셋은 **트랜잭션 커밋 시점에** 나갑니다. Step 06 에서 비교한 6가지 모드는 이 경우 적용되지 않습니다.

---

## 9-7. ⚠️ 핵심 함정 — `@Transactional` 은 DB 와 Kafka 를 묶지 않는다

이 코드를 본 적 있을 겁니다. 그리고 아마 "이러면 원자적이겠지" 라고 생각했을 겁니다.

```java
@Transactional("dbTxManager")
public void createOrder(OrderCreated order) {
    jdbc.update("INSERT INTO orders(order_id, customer_id, amount, status) VALUES (?,?,?,?)",
                order.orderId(), order.customerId(), order.amount(), "CREATED");
    kafkaTemplate.send("orders", order.orderId(), order);
    if (order.amount().intValue() > 12000) {
        throw new IllegalStateException("[9-7] 한도 초과 — DB 롤백");
    }
}
```

원자적이지 않습니다. 세 가지 경우로 나눠 해부합니다.

### 케이스 A — 유령 이벤트 (트랜잭션 프로듀서가 아닐 때)

`transaction-id-prefix` 가 **없는** 평범한 프로듀서입니다. 대부분의 프로젝트가 여기에 해당합니다.

`kafkaTemplate.send` 는 레코드를 프로듀서 버퍼에 넣고, 별도 IO 스레드가 `linger.ms` 뒤에 **즉시 브로커로 보냅니다.** DB 트랜잭션과 아무 관계가 없습니다. 그다음 예외가 터져 DB 가 롤백되면 —

**결과**
```
INFO 14107 --- [           main] c.e.o.s.Practice$GhostDemo               : [9-7A] INSERT orders ORD-0013 amount=13000
INFO 14107 --- [           main] c.e.o.s.Practice$GhostDemo               : [9-7A] kafka send → orders-2
ERROR 14107 --- [           main] c.e.o.s.Practice$GhostDemo               : [9-7A] 롤백: [9-7] 한도 초과 — DB 롤백
DEBUG 14107 --- [           main] o.s.j.d.DataSourceTransactionManager     : Rolling back JDBC transaction on Connection [HikariProxyConnection@...]
```

```bash
mq "SELECT order_id, status FROM orders WHERE order_id='ORD-0013'"
kcc --topic orders --from-beginning --isolation-level read_committed | grep ORD-0013
```

**결과**
```
Empty set
{"orderId":"ORD-0013","customerId":1013,"sku":"SKU-002","quantity":4,"amount":13000,"createdAt":"2025-01-01T00:13:00Z"}
```

**존재하지 않는 주문의 이벤트가 이미 발행되었습니다.** `read_committed` 로 읽어도 보입니다. 트랜잭션 프로듀서가 아니므로 abort 라는 개념 자체가 없기 때문입니다.

하류에서는 재고가 차감되고, 알림이 나가고, 정산 배치가 이 주문을 집계합니다. **DB 에는 없는 주문에 대해서.** 며칠 뒤 정산 불일치로 발견됩니다.

### 케이스 B — 순서를 뒤집으면 이벤트 유실

"그럼 send 를 마지막에 하면 되겠네" 라고 생각하기 쉽습니다. 소용없습니다. **`@Transactional` 메서드 "안"에는 커밋 시점이 없습니다.** 커밋은 메서드가 정상 리턴한 뒤 프록시가 수행하므로, 메서드 안 어디에 두든 순서는 항상 `send → commit` 입니다. 진짜로 뒤집으려면 커밋 후 콜백을 써야 합니다.

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {
        kafkaTemplate.send("orders", order.orderId(), order);   // DB 커밋 확정 후 발행
    }
});
```

이제 유령 이벤트는 없습니다. 대신 **브로커가 죽어 있거나 프로세스가 그 순간 죽으면** —

**결과**
```
INFO 14107 --- [           main] c.e.o.s.Practice$LostDemo                : [9-7B] INSERT orders ORD-0014
DEBUG 14107 --- [           main] o.s.j.d.DataSourceTransactionManager     : Initiating transaction commit
INFO 14107 --- [           main] c.e.o.s.Practice$LostDemo                : [9-7B] afterCommit — kafka send 시도
ERROR 14107 --- [           main] c.e.o.s.Practice$LostDemo                : [9-7B] 발행 실패: Topic orders not present in metadata after 60000 ms
```

```bash
mq "SELECT order_id, status FROM orders WHERE order_id='ORD-0014'"
kcc --topic orders --from-beginning --isolation-level read_committed | grep ORD-0014
```

**결과**
```
+-----------+---------+
| order_id  | status  |
+-----------+---------+
| ORD-0014  | CREATED |
+-----------+---------+
(아무것도 출력되지 않음)
```

**주문은 있는데 이벤트가 없습니다.** 재고도 안 빠지고 알림도 안 갑니다. 이쪽이 케이스 A 보다 나은 점은 딱 하나, **DB 가 진실의 원천이라 나중에 복구할 수 있다**는 것입니다. 이 관찰이 9-9 의 Outbox 패턴으로 이어집니다.

### 케이스 C — 트랜잭션 프로듀서를 쓰면? 창이 좁아질 뿐

`transaction-id-prefix` 가 **있는** 상태에서 `@Transactional("dbTxManager")` 안에서 `send` 하면, `KafkaTemplate` 은 예외를 던지지 않습니다. 9-3 의 조건("진행 중인 트랜잭션이 있는가")을 **DB 트랜잭션이 만족**시키기 때문입니다.

이때 `KafkaTemplate` 은 Kafka 트랜잭션을 열고 **DB 트랜잭션의 동기화(synchronization)로 등록**합니다. DB 가 커밋하면 Kafka 도 커밋하고, DB 가 롤백하면 Kafka 도 abort 합니다.

거의 원자적으로 보입니다. 하지만 두 구멍이 남습니다.

**구멍 ①** DB 커밋은 성공했는데 그 직후 Kafka 커밋이 실패하면? DB 는 되돌릴 수 없습니다. **케이스 B 와 같은 유실.**

**구멍 ②** DB 가 롤백해서 Kafka 도 abort 했는데, **컨슈머가 `read_uncommitted`(기본값) 면 abort 된 메시지를 읽습니다.** 9-5 그대로입니다. **케이스 A 의 유령 이벤트가 부활합니다.**

| 케이스 | 프로듀서 | 실패 지점 | 결과 |
|---|---|---|---|
| A | 일반 | DB 롤백 | **유령 이벤트** (DB 없음 / Kafka 있음) |
| B | 일반 + `afterCommit` | Kafka 발행 실패 | **이벤트 유실** (DB 있음 / Kafka 없음) |
| C | 트랜잭션 | Kafka 커밋 실패 | **이벤트 유실** (창이 좁아짐) |
| C | 트랜잭션 | DB 롤백 + 컨슈머 `read_uncommitted` | **유령 이벤트 부활** |

**결론: 어떤 조합으로도 원자성은 안 나옵니다.** 두 시스템에 걸친 원자적 커밋은 2PC(XA) 가 필요한데, **Kafka 는 XA 리소스가 아닙니다.** `KafkaTransactionManager` 는 XA 를 구현하지 않습니다.

---

## 9-8. `ChainedTransactionManager` 의 한계

한때 정답처럼 소개되던 방법이 있습니다.

```java
// Spring Framework 5.3 / Spring Kafka 2.7 부터 deprecated. 시작은 좌→우, 커밋은 우→좌.
new ChainedKafkaTransactionManager<>(kafkaTxManager, dataSourceTxManager);
```

이름이 "체인 트랜잭션 매니저" 라서 두 트랜잭션이 하나가 되는 것처럼 들립니다. 아닙니다. **하는 일은 "여러 트랜잭션을 순서대로 시작하고 역순으로 커밋"하는 것뿐입니다.** 이것을 **best-effort 1PC** 라고 부릅니다.

문제는 마지막 커밋입니다. `A 커밋 → B 커밋` 순서에서 B 가 실패하면, **A 는 이미 커밋되어 되돌릴 수 없습니다.**

| 커밋 순서 | 첫 번째 실패 | 두 번째 실패 | 남는 창 |
|---|---|---|---|
| Kafka → DB | 둘 다 롤백 ✅ | **Kafka 만 커밋됨** | 유령 이벤트 |
| DB → Kafka | 둘 다 롤백 ✅ | **DB 만 커밋됨** | 이벤트 유실 |

순서를 어떻게 잡아도 **창은 사라지지 않고 자리만 옮깁니다.** 좁아지기는 합니다 — 두 커밋 사이의 몇 밀리초로. 하지만 초당 수천 건을 처리하면 그 몇 밀리초도 매일 몇 건씩 걸립니다.

deprecated 된 이유가 이것입니다. **"쓰면 원자적이 된다"고 오해하게 만드는 API 였고, 실제로는 사용자가 여전히 실패 창을 직접 처리해야 했습니다.** Spring 은 이 API 를 없애고, 대신 "무엇을 진실의 원천으로 삼을지 먼저 정하라"는 방향(= Outbox)으로 안내합니다.

> 💡 `ChainedKafkaTransactionManager` 는 Spring Kafka 3.0 에서 **제거**되었습니다. 이 코스의 3.1.4 에는 클래스 자체가 없습니다. 구버전 프로젝트를 3.x 로 올릴 때 컴파일 에러로 만나게 됩니다. 그때 단순히 대체 API 를 찾지 말고, **9-9 의 세 가지 중 하나를 고르세요.**

---

## 9-9. 현실적인 해법 세 가지

| | ① Transactional Outbox | ② 멱등 컨슈머 + at-least-once | ③ 보상 트랜잭션 (Saga) |
|---|---|---|---|
| 구조 | DB 한 트랜잭션에 업무 데이터 + 이벤트 행을 함께 INSERT → 릴레이가 폴링해 Kafka 로 발행 | 그냥 at-least-once 로 보내고, 컨슈머가 중복을 걸러냄 | 단계별로 커밋하고, 실패하면 되돌리는 이벤트를 발행 |
| 원자성 | **DB 트랜잭션 하나뿐** → 진짜 원자적 | 없음 (중복 허용) | 없음 (결과적 일관성) |
| 보장 수준 | 유실 없음, 중복 가능 (at-least-once) | 유실 없음, 중복 제거됨 | 결과적 일관성 |
| 지연 | 릴레이 폴링 주기만큼 (+수십 ms ~ 수 초) | 없음 | 보상 단계만큼 |
| 복잡도 | 중 (릴레이 프로세스 하나 추가) | 하 (`processed_message` 테이블 + UNIQUE) | **상** (모든 단계의 역연산 설계) |
| 순서 보장 | 릴레이가 `id` 순 발행 → 유지 | 유지 | 깨짐 |
| 언제 | **이벤트를 반드시 내보내야 할 때** (기본 선택) | 중복이 무해하거나 걸러낼 수 있을 때 | 여러 서비스가 각자 DB 를 가질 때 |

**① Outbox 가 기본 선택입니다.** 아이디어는 단순합니다. Kafka 로 보내는 대신 **같은 DB 의 테이블에 이벤트를 INSERT** 합니다. 업무 데이터와 이벤트가 **하나의 DB 트랜잭션** 안에 있으니 원자적입니다. 그다음 별도 릴레이가 그 테이블을 폴링해 Kafka 로 발행하고 `published_at` 을 채웁니다.

```
  ┌ 하나의 DB 트랜잭션 ┐   릴레이 폴링
  │ INSERT orders      │──▶ WHERE published_at IS NULL ORDER BY id LIMIT 100
  │ INSERT outbox_event│──▶ KafkaTemplate.send → topic: orders (실패 시 다음 폴링에 재시도)
  └────────────────────┘
```

릴레이가 발행 후 `published_at` 갱신 전에 죽으면 **같은 이벤트가 두 번 나갑니다.** 그래서 Outbox 는 항상 **at-least-once** 이고, 컨슈머 쪽 멱등성(②)과 **같이** 씁니다. 둘은 대안이 아니라 짝입니다.

> 💡 **Outbox 는 Step 13 에서 직접 구현합니다.** `outbox_event` 테이블과 `idx_unpublished (published_at, id)` 인덱스는 [실습 프로젝트 셋업](../project/) 의 초기 스키마에 이미 들어 있습니다.

---

## 9-10. 트랜잭션의 비용

트랜잭션은 공짜가 아닙니다. 매 트랜잭션마다 **코디네이터 왕복**이 최소 두 번(`AddPartitionsToTxn`, `EndTxn`) 발생하고, 커밋 마커가 각 파티션에 기록됩니다.

같은 코드로 100만 건을 발행하되, **한 트랜잭션에 몇 건을 넣는지(`BATCH`)** 만 바꿔 가며 측정합니다.

**결과**
```
INFO 14107 --- [           main] c.e.o.s.Practice$ThroughputBench         : [9-10] non-tx        1000000건   54,347ms  18,400 msg/s
INFO 14107 --- [           main] c.e.o.s.Practice$ThroughputBench         : [9-10] tx batch=1    1000000건 1190,476ms     840 msg/s
INFO 14107 --- [           main] c.e.o.s.Practice$ThroughputBench         : [9-10] tx batch=100  1000000건  163,934ms   6,100 msg/s
INFO 14107 --- [           main] c.e.o.s.Practice$ThroughputBench         : [9-10] tx batch=1000 1000000건   70,422ms  14,200 msg/s
```

| 방식 | 처리량 | 비트랜잭션 대비 |
|---|---:|---:|
| 비트랜잭션 | **18,400 msg/s** | 1.0x |
| 트랜잭션 1건/tx | 840 msg/s | **0.05x (22배 느림)** |
| 트랜잭션 100건/tx | **6,100 msg/s** | 0.33x (3배 느림) |
| 트랜잭션 1000건/tx | 14,200 msg/s | 0.77x |

**트랜잭션당 레코드 수가 전부입니다.** 코디네이터 왕복 비용이 트랜잭션당 고정이므로, 1건씩 커밋하면 그 고정 비용을 1건이 다 뒤집어씁니다. 100건씩 묶으면 3배 저하, 1000건씩 묶으면 거의 차이가 없습니다.

> ⚠️ **함정 — consume-process-produce 의 트랜잭션 크기는 여러분이 정하는 게 아니다**
> 9-6 처럼 컨테이너에 트랜잭션 매니저를 붙이면, 트랜잭션 경계는 **poll 단위**입니다. 레코드 리스너면 사실상 레코드 1건당 1 트랜잭션에 가깝습니다. 즉 위 표의 **840 msg/s 구간**입니다.
> 처리량이 필요하면 **배치 리스너**(`batchListener = true`)로 바꿔 한 번의 poll 로 가져온 `max-poll-records` 건을 한 트랜잭션에 넣으세요. 500건이면 위 표의 6,100 ~ 14,200 msg/s 구간으로 올라갑니다.
> 대신 배치 중 한 건이 실패하면 **배치 전체가 재처리**됩니다. Step 06 의 배치 부분 실패 문제가 그대로 돌아옵니다.

### `transaction.timeout.ms`

프로듀서의 `transaction.timeout.ms` 기본값은 **60초** 입니다. 이 시간 안에 커밋되지 않은 트랜잭션은 코디네이터가 강제로 abort 합니다. `spring.kafka.producer.properties.transaction.timeout.ms` 로 조정하되, 브로커의 `transaction.max.timeout.ms`(기본 15분)보다 크게 잡으면 기동 시 거절당합니다.

```
org.apache.kafka.common.errors.InvalidTxnTimeoutException: The transaction timeout is larger than
the maximum value allowed by the broker (as configured by transaction.max.timeout.ms).
```

> 💡 **타임아웃은 짧을수록 좋습니다.** 9-5 에서 봤듯 진행 중인 트랜잭션은 `read_committed` 컨슈머를 LSO 에서 막습니다. 타임아웃이 60초면 죽은 프로듀서 하나가 **그 파티션의 모든 `read_committed` 컨슈머를 60초 세웁니다.** 처리 시간이 뻔한 워크로드라면 10~30초로 낮추세요.

---

## 9-11. ⚠️ `transactional.id` 는 인스턴스마다 고유해야 한다

9-2 의 로그에 `transactional.id = tx-order-0` 이 찍혔습니다. `tx-order-` 는 여러분이 쓴 접두사이고, `0` 은 **`DefaultKafkaProducerFactory` 가 JVM 안에서 매기는 카운터**입니다. 이 카운터는 다른 프로세스와 조율되지 않습니다.

문제가 보입니까? **같은 애플리케이션을 두 인스턴스 띄우면, 양쪽 다 `tx-order-0` 을 씁니다.** 파드를 3개로 스케일아웃하면 셋 다 `tx-order-0` 입니다.

같은 접두사를 가진 프로듀서 팩토리 둘을 한 JVM 에 만들어 두 인스턴스를 흉내 냅니다.

```java
// [9-11] 같은 transactional.id 를 쓰는 두 프로듀서 (둘 다 tx-fence-0)
KafkaTemplate<String, Object> a = new KafkaTemplate<>(newFactory("tx-fence-"));
KafkaTemplate<String, Object> b = new KafkaTemplate<>(newFactory("tx-fence-"));

a.executeInTransaction(t -> t.send("orders", "FENCE-1", OrderCreated.of(101)));  // A: epoch 0
b.executeInTransaction(t -> t.send("orders", "FENCE-2", OrderCreated.of(102)));  // B: epoch 1 — A 를 펜싱
a.executeInTransaction(t -> t.send("orders", "FENCE-3", OrderCreated.of(103)));  // A: 사망
```

**결과**
```
INFO 14107 --- [ad | producer-3] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-3, transactionalId=tx-fence-0] ProducerId set to 2000 with epoch 0
INFO 14107 --- [           main] c.e.o.s.Practice$FencingDemo             : [9-11] A 발행 성공 FENCE-1
INFO 14107 --- [ad | producer-4] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-4, transactionalId=tx-fence-0] ProducerId set to 2000 with epoch 1
INFO 14107 --- [           main] c.e.o.s.Practice$FencingDemo             : [9-11] B 발행 성공 FENCE-2
ERROR 14107 --- [ad | producer-3] o.a.k.c.p.i.TransactionManager           : [Producer clientId=producer-3, transactionalId=tx-fence-0] Aborting producer batches due to fatal error
ERROR 14107 --- [           main] c.e.o.s.Practice$FencingDemo             : [9-11] A 사망: org.apache.kafka.common.errors.ProducerFencedException: There is a newer producer with the same transactionalId which fences the current one.
```

`epoch 0` → `epoch 1` 로 올라간 순간 A 가 죽었습니다. **PID 는 2000 으로 같고 epoch 만 다릅니다.** 이것이 펜싱의 정체입니다.

운영에서의 증상은 이렇습니다. 파드 2개를 띄우면 **하나는 정상, 하나는 계속 `ProducerFencedException` 으로 재시작**합니다. 스케일아웃할수록 상황이 나빠집니다. 로그에는 명확한 예외가 찍히지만, 원인이 "인스턴스 수" 라는 걸 연결짓기까지 시간이 걸립니다.

### 해결 — 접두사에 인스턴스 식별자를 넣는다

```yaml
spring:
  kafka:
    producer:
      transaction-id-prefix: tx-order-${HOSTNAME:local}-
```

Kubernetes 라면 `HOSTNAME` 이 파드 이름이므로 파드마다 다릅니다. 그런데 `Deployment` 의 파드 이름은 재시작마다 바뀌므로, **재시작 후 펜싱이 동작하지 않습니다.** 죽은 파드의 미완료 트랜잭션은 `transaction.timeout.ms` 뒤에 타임아웃 abort 될 때까지 남습니다.

| 접두사 전략 | 인스턴스 간 충돌 | 재시작 시 신원 유지 | 비고 |
|---|---|---|---|
| `tx-order-` (고정) | **충돌 → 펜싱 사망** | 유지 | 단일 인스턴스만 가능 |
| `tx-order-${HOSTNAME}-` (Deployment) | 없음 | **안 됨** | 타임아웃 abort 에 의존 |
| `tx-order-${HOSTNAME}-` (StatefulSet) | 없음 | **유지** ✅ | `pod-0`, `pod-1` 고정 |
| `tx-order-${random.uuid}-` | 없음 | **안 됨** | 코디네이터에 쓰레기 id 축적 |

> 💡 **트랜잭션 프로듀서를 쓰는 애플리케이션은 StatefulSet 이 정석입니다.** 파드 이름이 고정되어야 재시작 후에도 같은 `transactional.id` 로 돌아와, 이전 세대의 미완료 트랜잭션을 즉시 정리할 수 있습니다.
> consume-process-produce(9-6)만 쓴다면 이 고민이 덜합니다. Spring Kafka 3.x 의 EOS 모드는 **V2** 이고, V2 는 컨슈머 그룹 메타데이터로 좀비를 걸러내므로 파티션별 `transactional.id` 를 만들 필요가 없습니다. 그래도 **인스턴스 간 고유성은 여전히 필요합니다.**

---

## 정리

| 개념 | 핵심 |
|---|---|
| Kafka 트랜잭션 보장 | 여러 토픽·파티션 원자적 쓰기 + **소비 오프셋 커밋까지** |
| Kafka 트랜잭션 **비**보장 | DB·HTTP 등 **Kafka 밖 전부** |
| `transaction-id-prefix` | 한 줄로 `KafkaTransactionManager` 자동 등록 + `KafkaTemplate` 트랜잭션 모드 |
| 켠 직후 증상 | 트랜잭션 밖 `send` 가 **`IllegalStateException: No transaction is in process`** |
| `allowNonTransactional` | 예외만 끔. 트랜잭션 유무가 **호출 문맥에 따라 조용히 바뀜** |
| `executeInTransaction` | 콜백 전체가 한 트랜잭션. 예외 → abort. 안에서 `get()` 금지 |
| `isolation.level` 기본값 | **`read_uncommitted`** → abort 된 메시지를 **받음** |
| `read_committed` 대가 | LSO 까지만 읽음. 진행 중 트랜잭션에 **최대 `transaction.timeout.ms` 만큼 막힘** |
| consume-process-produce | 컨테이너에 `KafkaTransactionManager` → `sendOffsetsToTransaction` 자동 호출 |
| 3.2 API 변경 | `setTransactionManager` deprecated → **`setKafkaAwareTransactionManager`** (타입으로 실수 차단) |
| `@Transactional` + Kafka | **원자적이지 않음.** 유령 이벤트 또는 이벤트 유실 |
| `ChainedTransactionManager` | best-effort 1PC. **창이 좁아질 뿐 사라지지 않음.** 3.0 에서 제거 |
| 현실적 해법 | **Outbox**(기본) + **멱등 컨슈머**(짝), Saga(다중 서비스) |
| 트랜잭션 비용 | 1건/tx **840 msg/s** vs 비트랜잭션 18,400 msg/s. 100건/tx 면 6,100 |
| `transactional.id` | **인스턴스마다 고유 + 재시작 시 동일.** 충돌하면 `ProducerFencedException` |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 직접 실행해 로그와 `mq` / `kcc` 출력을 확인**하세요.

1. `transaction-id-prefix` 를 켜고, 트랜잭션 밖 `send` 가 던지는 예외의 **전문**을 로그로 남긴 뒤, `executeInTransaction` 으로 감싸 해결하기
2. `executeInTransaction` 안에서 `orders` 와 `payments` 에 각각 발행하고 **세 번째 줄에서 예외**를 던져, `--isolation-level read_committed` 로 **양쪽 다 비어 있는 것** 확인하기
3. 격리 수준만 다른 컨슈머 두 그룹(`s09-ex-uncommitted` / `s09-ex-committed`)을 붙여, abort 된 메시지를 한쪽만 받는 표를 완성하기
4. `@Transactional("dbTxManager")` + 일반 프로듀서로 **유령 이벤트**를 재현하고, `mq 'SELECT ...'` 는 `Empty set` 인데 `kcc` 에는 메시지가 있는 것을 기록하기
5. 리스너 컨테이너에 `KafkaTransactionManager` 를 붙여 consume-process-produce 를 구성하고, `sendOffsetsToTransaction` 로그를 찾아내기
6. 같은 접두사를 가진 프로듀서 팩토리 두 개로 **`ProducerFencedException` 을 재현**하고, epoch 가 0 → 1 로 올라가는 로그를 캡처하기

---

## 다음 단계

트랜잭션의 경계를 봤습니다. Kafka 안에서는 강력하지만, **DB 를 넘는 순간 아무것도 보장하지 않습니다.** 그 사실을 인정하고 Outbox 로 가는 것이 결론이었습니다. 실제 구현은 Step 13 에서 합니다.

지금까지는 리스너가 알아서 뜨고 알아서 도는 것을 전제로 했습니다. 하지만 운영에서는 "배포 직후 잠깐 소비를 멈추고 싶다", "DB 가 죽었으니 컨슈머를 일시 정지하고 싶다", "특정 메시지는 리스너에 도달하기 전에 걸러내고 싶다" 같은 요구가 나옵니다. 다음 스텝에서 **리스너 컨테이너를 코드로 직접 제어**합니다.

→ [Step 10 — 리스너 컨테이너 제어](../step-10-container-control/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 를 보조 프로필로 하나씩 켜 가며 9-3 ~ 9-11 을 재현하되, **9-7 은 반드시 `mq 'SELECT * FROM orders'` 와 `kcc` 를 나란히 띄운 채** 실행하세요. 이 스텝의 핵심은 로그가 아니라 **"DB 에는 없는데 토픽에는 있다"는 두 화면의 불일치**입니다. 그다음 `Exercise.java` 의 6문제를 풀고 `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.order.step09` 패키지에 둡니다.

### Practice.java

본문 9-3 ~ 9-11 의 모든 예제를 절 번호 주석과 함께 nested static class 로 담은 실행 파일입니다.

- **보조 프로필 8개**로 예제를 하나씩 켭니다. `step09` 만 켜면 공통 빈(`dbTxManager`, `JdbcTemplate`, 비트랜잭션 템플릿)만 뜨고 아무 예제도 안 돕니다. 파일 상단 주석에 프로필 목록과 각각이 재현하는 절이 정리돼 있습니다.
- **트랜잭션 매니저는 전부 이름으로 지정**했습니다. `@Transactional("dbTxManager")` 처럼요. 9-2 의 함정(JPA 트랜잭션 매니저가 조용히 사라지는 문제)을 피하기 위한 것이고, 이 파일에서 `@Transactional` 을 이름 없이 쓴 곳은 한 군데도 없습니다.
- `plainTemplate` 빈이 9-7 의 핵심 장치입니다. `application.yml` 의 `transaction-id-prefix` 와 무관한 **비트랜잭션 프로듀서 팩토리**를 따로 만들어 둔 것으로, "대부분의 프로젝트가 쓰는 평범한 프로듀서"를 한 앱 안에서 재현하기 위한 것입니다. 9-7 케이스 A/B 는 이 템플릿을 씁니다.
- `[9-7] GhostEventDemo` 와 `[9-7] LostEventDemo` 는 각각 `ORD-0013`, `ORD-0014` 로 **주문 ID 를 고정**해 두었습니다. 실행 전 `mq 'DELETE FROM orders WHERE order_id IN ("ORD-0013","ORD-0014")'` 로 지우고 시작하세요. 두 번째 실행부터는 PK 중복으로 INSERT 가 먼저 터져 시나리오가 성립하지 않습니다.
- `[9-10] ThroughputBench` 는 기본 100만 건이라 4분 가까이 걸립니다. `-Dbench.total=100000` 으로 줄여 실행해도 **배수 관계는 그대로** 나옵니다. 절대값이 아니라 `batch=1` 과 `batch=1000` 의 비율을 보세요.
- `[9-11] FencingDemo` 는 프로듀서 팩토리를 코드로 두 개 만들어 같은 `transactional.id` 를 쓰게 합니다. `application.yml` 의 `tx-order-` 와 섞이지 않도록 `tx-fence-` 접두사를 따로 썼고, 데모 끝에 두 팩토리를 `destroy()` 합니다. 안 하면 앱 종료 시 미완료 트랜잭션이 남아 다음 실습의 `read_committed` 컨슈머를 60초 막습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일은 되도록 뼈대를 남겨 두었습니다.

- **문제 1·2·6** 은 프로듀서 쪽 트랜잭션을 **설정**하는 문제, **문제 3·4** 는 결과를 **관찰**하고 표를 채우는 문제, **문제 5** 는 컨테이너를 **구성**하는 문제입니다. 순서대로 푸세요. 2번의 abort 된 메시지가 있어야 3번의 격리 수준 비교가 성립합니다.
- 문제 3·4 는 코드보다 관찰이 중요합니다. `// 관측 기록:` 주석의 표를 `mq` 와 `kcc` 출력으로 직접 채워 넣으세요. **특히 문제 4 는 "DB Empty set / Kafka 1건" 이라는 두 줄을 적는 것이 정답의 전부입니다.**
- ⚠️ **문제 4 는 실행 전 `mq 'DELETE FROM orders WHERE order_id="ORD-9004"'` 를 반드시 돌리세요.** 두 번째 실행부터는 PK 중복 예외가 `send` 보다 먼저 터져서, 유령 이벤트가 아니라 그냥 아무 일도 안 일어납니다. "재현이 안 된다"의 90%가 이 원인입니다.
- 문제 5 의 확인 방법은 로그 grep 입니다. `o.s.k.t.KafkaTransactionManager` 를 `DEBUG` 로 올리고 `sendOffsetsToTransaction` 를 찾으세요. 이 줄이 없으면 트랜잭션 매니저가 컨테이너에 안 붙은 것입니다.
- 각 문제 끝의 `// 확인:` 주석에 **기대 로그 한 줄**이 적혀 있습니다. 예를 들어 문제 6 의 확인 줄은 `ProducerId set to 2000 with epoch 1` 입니다. 이 줄이 안 나오면 두 팩토리의 접두사가 실제로는 다른 것입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `executeInTransaction` 을 쓰는 것이 답이고, `setAllowNonTransactional(true)` 는 **오답에 가깝다**는 점을 설명합니다. 예외가 사라져서 고쳐진 것처럼 보이지만 원자성은 그대로 없고, 나중에 그 코드가 `@Transactional` 안으로 옮겨지면 동작이 조용히 바뀐다는 것이 이유입니다.
- **정답 2** 의 포인트는 검증을 `kcc` 에 `--isolation-level read_committed` 를 **붙여서** 해야 한다는 것입니다. 옵션 없이 확인하면 abort 된 메시지가 보여서 "트랜잭션이 안 걸렸다"고 오진하게 됩니다. 실제로 이 문제를 틀리는 대부분의 이유가 코드가 아니라 **검증 명령**입니다.
- **정답 3** 은 두 컨슈머 팩토리를 만들 때 `ConsumerConfig.ISOLATION_LEVEL_CONFIG` 의 값이 **문자열 `"read_committed"`** 여야 한다는 점을 짚습니다. enum 이나 대문자를 넣으면 `ConfigException` 없이 무시되지 않고 기동 시 예외가 나므로 그나마 다행이지만, `groupId` 를 안 나누면 **한쪽만 메시지를 받아** 비교 자체가 성립하지 않습니다.
- **정답 4** 는 코드 3줄과 관측 기록 2줄이 전부입니다. 대신 주석에서 "왜 `send` 를 마지막 줄로 옮겨도 안 되는가"를 다룹니다. `@Transactional` 메서드 **안**에는 커밋 시점이 없기 때문이며, 진짜로 뒤집으려면 `TransactionSynchronization.afterCommit` 이 필요하고 그러면 이번엔 유실이 생긴다는 것 — 즉 **트레이드오프의 이동**이 이 문제의 결론입니다.
- **정답 5** 는 `setTransactionManager` 와 `setKafkaAwareTransactionManager` 를 나란히 두고, 3.1.4 에서는 전자를 쓰되 3.2 이상이면 후자로 바꾸라고 안내합니다. 특히 전자에 `DataSourceTransactionManager` 를 넣어도 **컴파일도 기동도 되지만 오프셋이 트랜잭션에 안 들어간다**는 점을, 로그에 `sendOffsetsToTransaction` 이 없는 것으로 확인하는 방법과 함께 설명합니다.
- **정답 6** 은 팩토리 두 개를 만드는 코드와, 왜 이것이 "파드 2개"와 같은 상황인지를 설명합니다. `DefaultKafkaProducerFactory` 의 카운터가 **JVM 로컬**이라 다른 프로세스와 조율되지 않는다는 것이 핵심이고, `${HOSTNAME}` 을 붙이는 해법과 그때 생기는 "재시작하면 신원이 바뀐다"는 새 문제까지 주석으로 이어 놓았습니다.

```java file="./Solution.java"
```
