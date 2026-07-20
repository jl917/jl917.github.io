# Step 13 — 실전 패턴과 최종 프로젝트

> **학습 목표**
> - at-least-once 위에서 **멱등성으로 "정확히 한 번"을 만드는** 세 축(멱등 컨슈머 / 원자적 발행 / 순서 보장)을 구분한다
> - `processed_message` 처리 이력 테이블로 멱등 컨슈머를 구현하고, **메시지 ID 후보 3가지**를 비교해 하나를 고른다
> - `SELECT` 후 `INSERT` 하는 멱등 체크가 왜 경합에서 깨지는지 **두 스레드로 재현**한다
> - `OrderService` + `OutboxRelay` 로 **Transactional Outbox** 를 구현하고, `FOR UPDATE SKIP LOCKED` 로 다중 인스턴스 안전성을 확인한다
> - 순서 보장이 깨지는 5가지 경로를 표로 정리하고, `@Async` 리스너가 **순서와 커밋을 동시에 깨뜨리는 것**을 로그로 확인한다
> - 주문 서비스 전체를 조립해 **검증 시나리오 4개**를 실행하고, 배포 전 체크리스트 20항목으로 Step 01~12 를 회수한다
>
> **선행 스텝**: Step 12 — 관측성과 운영
> **예상 소요**: 150분

---

## 13-0. 실습 준비

이 스텝은 **Kafka 와 MySQL 을 동시에** 씁니다. `docker compose ps` 로 둘 다 `(healthy)` 인지 먼저 확인하세요.
MySQL 별칭을 하나 더 만들어 둡니다. 이 스텝 내내 씁니다.

```bash
alias mq='docker exec -i learn-kafka-mysql mysql -ulearner -plearn1234 orderdb -t -e'

mq "SHOW TABLES;"        # inventory / orders / outbox_event / processed_message 4개
kt --create --topic orders.outbox     --partitions 3 --replication-factor 1
kt --create --topic orders.outbox.DLT --partitions 3 --replication-factor 1
```

이 스텝은 **REST 엔드포인트**를 씁니다. `build.gradle` 에 웹 스타터를 추가하세요. (Step 12 에서 Actuator 웹 엔드포인트를 열었다면 이미 있습니다.)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
```

> 💡 실습 도중 상태가 꼬이면 **DB 만** 되돌리세요. 토픽까지 지우면 재현이 어려워집니다.
> ```bash
> mq "TRUNCATE processed_message; TRUNCATE outbox_event; DELETE FROM orders;
>     UPDATE inventory SET quantity = 1000;"
> ```

---

## 13-1. 지금까지의 결론 — 정확히 한 번은 "설정"이 아니라 "설계"다

Step 07 부터 Step 12 까지, 우리는 계속 같은 벽에 부딪혔습니다.

- Step 06 — 커밋 시점을 바꿔도 **처리와 커밋 사이의 틈**은 없어지지 않습니다
- Step 07 — 재시도는 **같은 메시지를 여러 번 처리**한다는 뜻입니다
- Step 08 — 논블로킹 재시도는 순서를 포기하는 대신 **중복 가능성을 늘립니다**
- Step 09 — `KafkaTransactionManager` 는 Kafka 안에서만 원자적이고, **DB 와는 원자적이지 않습니다**
- Step 12 — 랙이 0 이어도 처리가 성공했다는 뜻은 아닙니다

결론은 하나입니다. **Kafka 로 만들 수 있는 현실적인 보장은 at-least-once 입니다.** exactly-once-semantics(EOS)는 "Kafka → 처리 → Kafka" 로 닫힌 경로에서만 성립하고, 우리처럼 **DB 와 외부 API 가 끼면 성립하지 않습니다.**

그래서 실무의 답은 이렇게 뒤집힙니다.

> **"메시지가 한 번만 오게 만든다"가 아니라, "여러 번 와도 결과가 같게 만든다."**

이것이 멱등성(idempotency)입니다. 이 스텝은 그 멱등성을 **세 축**으로 나눠 각각 구현합니다.

| 축 | 질문 | 해법 | 절 |
|---|---|---|---|
| ① 멱등 컨슈머 | 같은 메시지를 두 번 받으면? | 처리 이력 테이블 + UNIQUE 제약 | 13-2 ~ 13-4 |
| ② 원자적 발행 | DB 는 커밋됐는데 발행이 실패하면? | Transactional Outbox | 13-5 |
| ③ 순서 보장 | 이벤트가 뒤집혀 도착하면? | 키 설계 = 순서 설계 | 13-6, 13-7 |

세 축은 **독립적이지 않습니다.** ② 를 도입하면 중복 발행이 늘어나므로 ① 이 필수가 되고, ① 을 병렬화하면 ③ 이 깨집니다. 순서대로 봅니다.

---

## 13-2. 멱등 컨슈머 — 처리 이력 테이블

가장 단순하고 가장 널리 쓰이는 구현입니다. **"이 메시지를 처리한 적이 있는가"를 DB 에 기록**합니다.

```sql
CREATE TABLE processed_message (
  message_id     VARCHAR(64) NOT NULL PRIMARY KEY,
  consumer_group VARCHAR(64) NOT NULL,
  processed_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
```

### 메시지 ID 를 무엇으로 할 것인가

여기서 대부분의 설계가 갈립니다. 후보는 셋입니다.

| 후보 | 형태 | 장점 | 치명적 문제 |
|---|---|---|---|
| ① `topic-partition-offset` | `orders-1-42` | 추가 작업 0. 항상 존재 | **재발행하면 오프셋이 바뀝니다.** DLT 재처리·Outbox 재발행·토픽 마이그레이션에서 같은 사건이 다른 ID 가 되어 중복 처리됩니다 |
| ② 비즈니스 키 | `ORD-0007:OrderCreated` | 사람이 읽을 수 있음. 재발행에 강함 | **같은 타입이 여러 번 정당하게 발생**하면 못 씁니다(`StockAdjusted` 를 두 번 보내면 두 번째가 스킵됨). 이벤트 타입 설계에 종속됩니다 |
| ③ **프로듀서가 만든 ID** | UUID 또는 outbox 행 PK | 사건 하나 = ID 하나. 재발행해도 **동일**. 토픽·파티션과 무관 | 프로듀서가 헤더에 실어 줘야 합니다(= 규약이 필요) |

**③ 을 씁니다.** 우리는 13-5 에서 Outbox 를 만들 것이므로, **`outbox_event.id` 를 그대로 메시지 ID 로** 씁니다. 릴레이가 재발행해도 행 PK 는 안 바뀌므로 ③ 의 조건을 정확히 만족합니다. Outbox 없이 직접 발행할 때는 `UUID.randomUUID()` 를 헤더에 실으면 됩니다.

```java
public static final String HDR_MESSAGE_ID = "messageId";

ProducerRecord<String, OrderCreated> record =
        new ProducerRecord<>("orders.outbox", null, event.orderId(), event);
record.headers().add(HDR_MESSAGE_ID, ("OBX-" + row.id()).getBytes(StandardCharsets.UTF_8));
kafkaTemplate.send(record);
```

### 구현 — INSERT 를 먼저 시도한다

```java
private static final String INSERT_MARK = """
        INSERT INTO processed_message (message_id, consumer_group)
        VALUES (?, ?)
        """;

boolean markProcessed(String messageId, String group) {
    try {
        jdbc.update(INSERT_MARK, group + ":" + messageId, group);
        return true;                     // 처음 보는 메시지
    } catch (DuplicateKeyException e) {
        return false;                    // 이미 처리함 → 스킵
    }
}
```

`SELECT` 가 없습니다. **INSERT 를 먼저 던지고, 실패하면 중복으로 간주**합니다.

**결과** (같은 메시지를 두 번 소비)
```
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s13.IdempotentInventoryListener    : consume messageId=OBX-7 order=ORD-0007 sku=SKU-002 qty=3
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s13.IdempotentInventoryListener    : deducted SKU-002 -3 (remaining=997)
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s13.IdempotentInventoryListener    : consume messageId=OBX-7 order=ORD-0007 sku=SKU-002 qty=3
WARN 14107 --- [ntainer#0-0-C-1] c.e.o.s13.IdempotentInventoryListener    : duplicate messageId=OBX-7 group=s13-inventory — skipped
```

두 번째는 `deducted` 로그가 없습니다. 재고도 997 그대로입니다.

> ⚠️ **함정 — `processed_message` 의 PK 가 `message_id` 하나뿐이다**
> 프로젝트 스키마의 PK 는 `message_id` 단독입니다. 그런데 컨슈머 그룹은 여럿입니다(`s13-inventory`, `s13-notification`).
> 재고 그룹이 `OBX-7` 을 먼저 처리해 INSERT 해 버리면, **알림 그룹이 그 메시지를 처음 보는데도 `DuplicateKeyException` 이 나서 스킵**됩니다.
> 팬아웃 컨슈머의 절반이 조용히 아무 일도 안 하게 됩니다. 예외도, 랙도, 아무 신호가 없습니다.
> **해결 A** — 저장 값을 `group + ":" + messageId` 로 만들어 그룹별로 다른 키가 되게 합니다(위 코드).
> **해결 B** — 스키마를 고칠 수 있다면 이쪽이 정석입니다.
> ```sql
> ALTER TABLE processed_message DROP PRIMARY KEY,
>   ADD PRIMARY KEY (message_id, consumer_group);
> ```
> 이 코스는 스키마를 고정해야 하므로 **해결 A** 로 갑니다. 실무에서는 B 를 쓰세요.

### 왜 `SELECT` 로 먼저 확인하면 안 되는가

이렇게 쓰고 싶어집니다.

```java
// ❌ 틀린 코드
Integer cnt = jdbc.queryForObject(
        "SELECT COUNT(*) FROM processed_message WHERE message_id = ?", Integer.class, key);
if (cnt != null && cnt > 0) {
    log.warn("duplicate — skipped");
    return;
}
deductStock(event);                                        // ← ①
jdbc.update("INSERT INTO processed_message ...", key, group);  // ← ②
```

읽기엔 자연스럽지만 **`SELECT` 와 `INSERT` 사이가 비어 있습니다.** 컨슈머 스레드가 3개(= `concurrency: 3`)이거나, 인스턴스가 2대이거나, 재시도로 같은 메시지가 겹치면 이렇게 됩니다.

```
스레드 A                          스레드 B
────────────────────────────────────────────────────
SELECT → 0건                      
                                  SELECT → 0건        ← 아직 A 가 INSERT 하기 전
deductStock()  (-3)               
                                  deductStock()  (-3) ← 두 번 차감
INSERT OK                         
                                  INSERT → DuplicateKey (이미 늦음)
```

**결과** (같은 메시지를 두 스레드가 동시에 소비하도록 강제한 재현)
```
INFO 14107 --- [       pool-2-t-1] c.e.o.s13.RaceDemo                       : [A] select count=0
INFO 14107 --- [       pool-2-t-2] c.e.o.s13.RaceDemo                       : [B] select count=0
INFO 14107 --- [       pool-2-t-1] c.e.o.s13.RaceDemo                       : [A] deducted SKU-002 -3
INFO 14107 --- [       pool-2-t-2] c.e.o.s13.RaceDemo                       : [B] deducted SKU-002 -3
INFO 14107 --- [       pool-2-t-1] c.e.o.s13.RaceDemo                       : [A] insert ok
WARN 14107 --- [       pool-2-t-2] c.e.o.s13.RaceDemo                       : [B] insert duplicate — but stock already deducted twice
```

`mq "SELECT quantity FROM inventory WHERE sku='SKU-002';"` → **994**. 997 이어야 하는데 6개가 빠졌습니다.
100회 반복 실험에서 **11회** 중복 차감이 발생했습니다.

INSERT-first 방식으로 같은 실험을 하면 **0회**입니다. 판정을 애플리케이션이 아니라 **DB 의 UNIQUE 인덱스**에 맡겼기 때문입니다. UNIQUE 제약은 원자적이고, 경합 구간이 존재하지 않습니다.

> 💡 **실무 팁 — "확인하고 하기"가 아니라 "하고 나서 실패를 처리하기"**
> `SELECT`-then-`INSERT`, `exists()`-then-`save()`, `containsKey()`-then-`put()` 은 전부 같은 종류의 버그입니다.
> 동시성 판정은 **제약 조건(UNIQUE / PK)** 이나 **원자적 연산**에 위임하고, 애플리케이션은 실패를 받아 처리하는 쪽이 항상 안전합니다.

---

## 13-3. ⚠️ 함정: 멱등 처리와 비즈니스 처리가 다른 트랜잭션이면 소용없다

INSERT-first 로 바꿔도, **이력 기록과 실제 처리가 다른 트랜잭션이면** 여전히 깨집니다.

```java
// ❌ 틀린 코드 — 트랜잭션이 둘로 쪼개져 있다
@KafkaListener(topics = "orders.outbox", groupId = "s13-inventory")
public void onMessage(OrderCreated e, @Header("messageId") String messageId) {
    if (!markProcessed(messageId, GROUP)) return;   // 트랜잭션 #1 — 커밋됨
    inventoryService.deduct(e.sku(), e.quantity()); // 트랜잭션 #2 — 여기서 죽으면?
}
```

`markProcessed` 가 커밋된 직후 프로세스가 죽으면 이렇게 됩니다.

| 시점 | `processed_message` | `inventory` | 결과 |
|---|---|---|---|
| `markProcessed` 커밋 | `OBX-7` 있음 | 1000 (미차감) | — |
| 프로세스 kill | `OBX-7` 있음 | 1000 | 오프셋 미커밋 |
| 재시작 → 같은 메시지 재소비 | `OBX-7` 있음 → **스킵** | 1000 | **재고가 영원히 안 빠짐** |

**결과**
```
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s13.SplitTxDemo                    : marked OBX-7 (tx#1 committed)
ERROR 14107 --- [ntainer#0-0-C-1] c.e.o.s13.SplitTxDemo                   : simulated crash before deduct
...앱 재시작...
INFO 14107 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s13-inventory: partitions assigned: [orders.outbox-0, orders.outbox-1, orders.outbox-2]
WARN 14107 --- [ntainer#0-0-C-1] c.e.o.s13.SplitTxDemo                    : duplicate messageId=OBX-7 group=s13-inventory — skipped
```

DB 를 확인하면 `processed_message` 는 1행, `inventory.SKU-002` 는 **1000 그대로**입니다.

**이력은 남았는데 처리는 안 됐습니다.** 그리고 이력이 남았으므로 **다시는 처리되지 않습니다.** 메시지 하나가 조용히 증발한 것입니다. 로그에 ERROR 도 없습니다(재시작 후 로그는 `skipped` 뿐입니다).

반대 순서(처리 먼저, 이력 나중)도 대칭적으로 깨집니다. 재고는 빠졌는데 이력이 없으니, 재소비 시 **두 번 차감**됩니다.

### 해결 — 하나의 트랜잭션으로 묶는다

```java
@KafkaListener(topics = "orders.outbox", groupId = "s13-inventory")
public void onMessage(OrderCreated e, @Header(HDR_MESSAGE_ID) String messageId) {
    inventoryTx.consumeOnce(messageId, GROUP, e);
}

// 별도 빈이어야 합니다 (자기 호출은 프록시를 안 탑니다)
@Transactional                                     // ← 이력 + 비즈니스가 한 트랜잭션
public void consumeOnce(String messageId, String group, OrderCreated e) {
    try {
        jdbc.update(INSERT_MARK, group + ":" + messageId, group);
    } catch (DuplicateKeyException dup) {
        log.warn("duplicate messageId={} group={} — skipped", messageId, group);
        return;                                    // 롤백 아님. 정상 종료
    }
    int updated = jdbc.update(
            "UPDATE inventory SET quantity = quantity - ? WHERE sku = ? AND quantity >= ?",
            e.quantity(), e.sku(), e.quantity());
    if (updated == 0) {
        throw new OutOfStockException(e.sku());    // ← 롤백 → 이력도 함께 사라짐
    }
}
```

핵심은 마지막 줄입니다. 재고가 부족해 예외가 나면 **`processed_message` INSERT 도 함께 롤백**됩니다. 그래서 재시도가 정상적으로 동작하고, 재시도를 다 소진하면 DLT 로 갑니다(Step 07). 만약 이력만 별도 트랜잭션이었다면 **첫 시도에서 이력이 남아 재시도가 전부 스킵**되고, DLT 에도 안 갑니다.

**결과** (재고 부족 케이스)
```
INFO 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx                    : marked OBX-31 (tx started)
WARN 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx                    : out of stock SKU-001 need=4 → rollback
INFO 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx                    : marked OBX-31 (tx started)     ← 재시도 1
WARN 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx                    : out of stock SKU-001 need=4 → rollback   (재시도 2 도 동일)
ERROR 14107 --- [ntainer#0-2-C-1] o.s.k.l.DefaultErrorHandler             : Backoff FixedBackOff{interval=1000, currentAttempts=3, maxAttempts=3} exhausted for orders.outbox-2@31
INFO 14107 --- [ntainer#0-2-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Successful dead-letter publication: orders.outbox.DLT-2@0
```

`marked` 가 3번 찍혔습니다. 매번 INSERT 하고 매번 롤백했다는 뜻입니다. 이것이 정상입니다.

> 💡 **실무 팁 — `@Transactional` 은 리스너 메서드에 직접 붙여도 됩니다.**
> 단, `DefaultErrorHandler` 의 재시도는 트랜잭션 **밖**에서 돌아야 합니다. 리스너 메서드에 `@Transactional` 을 붙이면
> 매 시도마다 새 트랜잭션이 열리고 닫히므로 문제없습니다. 반대로 `KafkaTransactionManager` 로 감싸면
> 재시도 전체가 한 Kafka 트랜잭션에 들어가 롤백 범위가 달라집니다(Step 09 참고).

---

## 13-4. ⚠️ 함정: 처리 이력 테이블이 무한히 커진다

`processed_message` 는 **삭제하는 코드를 아무도 안 짜면 영원히 자랍니다.**

초당 500건 처리 서비스라면 하루 4,320만 행입니다. 한 달이면 13억 행. PK 인덱스가 메모리를 벗어나는 순간, **INSERT 한 건마다 디스크 랜덤 I/O** 가 생기고 컨슈머 처리량이 붕괴합니다.

| 행 수 | PK 인덱스 크기(추정) | `markProcessed` p99 | 컨슈머 처리량 |
|---:|---:|---:|---:|
| 100만 | 46 MB | 0.8 ms | 4,200 msg/s |
| 1,000만 | 460 MB | 1.4 ms | 3,900 msg/s |
| 1억 | 4.6 GB | 27 ms | **310 msg/s** |

1억 행 구간에서 **13배** 느려집니다. 에러는 안 납니다. 그냥 느려집니다.

### 보관 기간을 정하는 기준

> **보관 기간 ≥ "같은 메시지가 다시 올 수 있는 최대 기간"**

그 최대 기간은 대개 **토픽 retention** 입니다. 오프셋을 최과거로 리셋하는 최악의 경우, retention 안에 있는 모든 메시지가 다시 옵니다.
`kt --describe --topic orders.outbox --all | grep retention` 으로 확인하면 `retention.ms=604800000`, 즉 7일입니다.
여유를 둬 **14일** 보관으로 정합니다. Outbox 재발행·DLT 재처리 지연까지 흡수하려면 retention 의 2배가 안전합니다.

### 해결 A — TTL 배치 삭제

```java
@Scheduled(cron = "0 10 3 * * *")           // 매일 03:10
public void purge() {
    int total = 0, deleted;
    do {
        deleted = jdbc.update(                          // ★ LIMIT 로 쪼갠다
                "DELETE FROM processed_message WHERE processed_at < ? LIMIT 5000",
                Timestamp.from(Instant.now().minus(14, ChronoUnit.DAYS)));
        total += deleted;
    } while (deleted == 5000);
    log.info("purged {} rows from processed_message", total);
}
```

**결과**
```
INFO 14107 --- [   scheduling-1] c.e.o.s13.ProcessedMessagePurger         : purged 4318227 rows from processed_message
```

한 번에 수천만 행을 지우면 **긴 트랜잭션이 undo 로그를 부풀리고 복제 지연을 만듭니다.** `LIMIT` 분할이 핵심입니다.

### 해결 B — 파티셔닝 후 파티션 DROP

행이 억 단위라면 `DELETE` 자체가 부담입니다. 날짜로 RANGE 파티셔닝하고 **파티션을 통째로 DROP** 하면 즉시 끝납니다.

```sql
ALTER TABLE processed_message
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (message_id, processed_at),      -- 파티션 키는 PK 에 포함돼야 합니다
  PARTITION BY RANGE (TO_DAYS(processed_at)) (
    PARTITION p20250101 VALUES LESS THAN (TO_DAYS('2025-01-02')),
    PARTITION p20250102 VALUES LESS THAN (TO_DAYS('2025-01-03')),
    PARTITION pmax      VALUES LESS THAN MAXVALUE
  );

ALTER TABLE processed_message DROP PARTITION p20250101;   -- 0.02 sec
```

`DELETE` 4백만 행이 41초 걸린 자리에서, `DROP PARTITION` 은 **0.02초**입니다.

> 💡 파티션 키가 PK 에 포함돼야 한다는 제약, 파티션 프루닝, `pmax` 관리 등 파티셔닝의 전체 그림은
> [MySQL 코스 Step 21](../../mysql8/step-21-partitioning/) 을 참고하세요.

---

## 13-5. Transactional Outbox 패턴

Step 09 에서 미뤄 둔 문제입니다. **"DB 커밋과 Kafka 발행을 어떻게 원자적으로 만드는가."**

답은 **"만들지 않는다"** 입니다. Kafka 를 트랜잭션에서 **완전히 빼내고**, 발행 의도만 DB 에 같이 저장합니다.

```
┌──────────────────────────────────────────────────────────┐
│  OrderService.createOrder()      @Transactional          │
│   ① INSERT INTO orders        (비즈니스 상태)             │
│   ② INSERT INTO outbox_event  (발행 의도)                 │
│   ↑ 같은 DB, 같은 트랜잭션 → 둘 다 되거나 둘 다 안 되거나  │
│   ↑ Kafka 호출이 여기에 전혀 없다  ★핵심★                 │
└────────────────────────┬─────────────────────────────────┘
                         │ COMMIT →  outbox_event(published_at IS NULL)
                         ▼          @Scheduled(fixedDelay=500)
              ┌──────────────────────┐  SELECT ... FOR UPDATE SKIP LOCKED
              │   OutboxRelay        │  ③ kafkaTemplate.send().join()
              │                      │  ④ UPDATE published_at = NOW(3)
              └──────────┬───────────┘
                         ▼
                 topic: orders.outbox
```

### 프로듀서 쪽 — `OrderService`

```java
@Service
public class OrderService {

    @Transactional                                   // DataSourceTransactionManager
    public String createOrder(OrderCreated e) {
        jdbc.update("INSERT INTO orders (order_id, customer_id, amount, status)"
                        + " VALUES (?, ?, ?, 'CREATED')",
                e.orderId(), e.customerId(), e.amount());

        jdbc.update("INSERT INTO outbox_event (aggregate_id, event_type, payload)"
                        + " VALUES (?, 'OrderCreated', ?)",
                e.orderId(), toJson(e));

        log.info("order {} created (outbox staged)", e.orderId());
        return e.orderId();
    }
}
```

**`kafkaTemplate` 이 이 클래스에 아예 주입되지 않은 것**이 이 패턴의 전부입니다. Kafka 가 죽어 있어도 주문 생성은 성공합니다. 발행은 나중에 릴레이가 책임집니다.

```bash
curl -s -XPOST localhost:8080/orders/1     # 릴레이는 정지시켜 둔 상태
mq "SELECT id, aggregate_id, event_type, published_at FROM outbox_event;"
```

**결과** (before)
```
+----+--------------+--------------+--------------+
| id | aggregate_id | event_type   | published_at |
+----+--------------+--------------+--------------+
|  1 | ORD-0001     | OrderCreated | NULL         |
+----+--------------+--------------+--------------+
```

### 릴레이 쪽 — `OutboxRelay`

```java
@Component
public class OutboxRelay {

    private static final String PICK = """
            SELECT id, aggregate_id, event_type, payload
            FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """;

    @Scheduled(fixedDelay = 500)
    @Transactional                                    // SELECT ~ UPDATE 가 한 트랜잭션
    public void relay() {
        List<OutboxRow> rows = jdbc.query(PICK, OUTBOX_ROW_MAPPER);
        if (rows.isEmpty()) return;

        for (OutboxRow row : rows) {
            OrderCreated event = fromJson(row.payload());
            ProducerRecord<String, OrderCreated> rec =
                    new ProducerRecord<>("orders.outbox", null, row.aggregateId(), event);
            rec.headers().add(HDR_MESSAGE_ID, ("OBX-" + row.id()).getBytes(UTF_8));
            rec.headers().add("eventType", row.eventType().getBytes(UTF_8));

            kafkaTemplate.send(rec).join();           // ★ 발행 확인까지 대기
            jdbc.update("UPDATE outbox_event SET published_at = NOW(3) WHERE id = ?", row.id());
        }
        log.info("relayed {} events (id {}..{})",
                rows.size(), rows.get(0).id(), rows.get(rows.size() - 1).id());
    }
}
```

**결과**
```
INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay                    : relayed 1 events (id 1..1)
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.InventoryTx                    : marked OBX-1 (tx started)
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.InventoryTx                    : deducted SKU-002 -2 (remaining=998)
```

**결과** (after — `SELECT id, created_at, published_at FROM outbox_event`)
```
+----+-------------------------+-------------------------+
| id | created_at              | published_at            |
+----+-------------------------+-------------------------+
|  1 | 2025-06-02 14:31:07.204 | 2025-06-02 14:31:07.463 |
+----+-------------------------+-------------------------+
```

**발행 지연 259ms.** `fixedDelay=500` 이므로 평균 250ms, 최대 500ms + 발행 시간입니다. 100건 측정 결과 p50 = 261ms, p99 = 638ms 였습니다.

> 💡 `kafkaTemplate.send(rec).join()` 의 `join()` 을 빼면 안 됩니다. Step 02 에서 봤듯 `send` 는 예외 없이 리턴하고,
> 실패는 `CompletableFuture` 안에만 있습니다. 확인 없이 `published_at` 을 갱신하면 **발행 안 된 이벤트가 발행됨으로 표시**됩니다.
> 릴레이는 배치 처리량보다 **정확성**이 중요한 자리이므로 `join()` 의 비용을 감수합니다.

### `FOR UPDATE SKIP LOCKED` — 다중 인스턴스 안전장치

서비스를 2대 띄우면 릴레이도 2개입니다. 둘이 같은 행을 집으면 **모든 이벤트가 2번 발행**됩니다.

`SKIP LOCKED` 는 "다른 트랜잭션이 잠근 행은 **기다리지 말고 건너뛰라**"는 뜻입니다.

| 방식 | 인스턴스 A | 인스턴스 B | 결과 |
|---|---|---|---|
| 잠금 없음 | id 1~100 선택 | id 1~100 선택 | **전량 중복 발행** |
| `FOR UPDATE` (SKIP 없음) | id 1~100 잠금 | **A 가 커밋할 때까지 블로킹** | 중복은 없지만 처리량 절반, 락 대기 타임아웃 위험 |
| `FOR UPDATE SKIP LOCKED` | id 1~100 잠금 | id 101~200 선택 | **중복 없이 병렬** |

**결과** (인스턴스 2대, `SKIP LOCKED` 있음 / `--server.port` 만 다르게 기동)
```
[instance-A] INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay : relayed 100 events (id 1..100)
[instance-B] INFO 14231 --- [   scheduling-1] c.e.o.s13.OutboxRelay : relayed 100 events (id 101..200)
[instance-A] INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay : relayed 100 events (id 201..300)
```

`SKIP LOCKED` 를 빼고 같은 실험을 한 뒤 `kcc --topic orders.outbox --from-beginning | wc -l` 를 세면 **573** 이 나옵니다.
300건이 573건이 됐습니다. **273건이 중복**입니다.

> ⚠️ **함정 — Outbox 는 exactly-once 가 아니라 at-least-once 다**
> `kafkaTemplate.send().join()` 이 성공한 직후, `UPDATE published_at` 을 하기 **전에** 프로세스가 죽으면?
> 그 이벤트는 `published_at IS NULL` 로 남아 있으므로 **재시작 후 다시 발행됩니다.**
> 이건 버그가 아니라 이 패턴의 **정의된 동작**입니다. 발행과 갱신을 원자적으로 만들 방법은 없습니다(그게 가능했다면 애초에 Outbox 가 필요 없었습니다).
> **그래서 13-2 의 멱등 컨슈머가 선택이 아니라 필수입니다.** Outbox 를 도입하면서 멱등 컨슈머를 안 만들면,
> **중복을 줄이려고 도입한 패턴이 중복을 늘리는** 결과가 됩니다.
> 재현: 릴레이의 `send().join()` 과 `UPDATE` 사이에 `if (row.id() == 7) System.exit(1);` 을 넣고 재시작해 보세요.

### 폴링 릴레이 vs CDC(Debezium)

| 항목 | 폴링 릴레이 (이 스텝) | CDC — Debezium |
|---|---|---|
| 발행 지연 | p50 261ms / p99 638ms (`fixedDelay=500`) | p50 12ms / p99 34ms (binlog tail) |
| DB 부하 | 유휴 시에도 초당 2회 인덱스 스캔. 인스턴스 수만큼 배수 | 애플리케이션 쿼리 0. binlog 읽기만 |
| `published_at` 갱신 | 필요 (쓰기 부하 + 테이블 청소 필요) | 불필요. **outbox 행을 INSERT 직후 DELETE 해도 됨** |
| 순서 보장 | `ORDER BY id` 로 **인스턴스 내에서만** 보장. `SKIP LOCKED` 로 인스턴스 간 순서는 뒤섞임 | binlog 순서 = 커밋 순서. **전역 순서 보장** |
| 운영 복잡도 | 낮음. 코드 40줄, 추가 인프라 0 | 높음. Kafka Connect 클러스터 + binlog 권한 + 스키마 관리 |
| 장애 지점 | 애플리케이션과 운명 공동체 | 별도 컴포넌트가 하나 늘어남 |
| 적합한 규모 | ~수천 TPS | 수만 TPS 이상, 또는 순서가 엄격한 도메인 |

> 💡 **처음엔 폴링으로 시작하세요.** 코드 40줄로 원자성 문제가 해결됩니다.
> CDC 는 지연이 문제가 되거나 릴레이의 DB 부하가 눈에 띌 때 옮겨 가면 됩니다. 컨슈머 쪽 코드는 **하나도 안 바뀝니다.**
> Kafka Connect 와 Debezium 커넥터 설정은 [Kafka 코스 Step 12](../../kafka/step-12-connect/) 에서 다룹니다.

---

## 13-6. 순서 보장 전략 — 키 설계가 곧 순서 설계

Kafka 가 보장하는 순서는 딱 하나입니다.

> **하나의 파티션 안에서, 프로듀서가 보낸 순서대로 컨슈머가 읽는다.**

그 외에는 아무것도 보장되지 않습니다. 그리고 이 하나뿐인 보장마저 다섯 가지 방법으로 깨집니다.

| # | 경로 | 무엇이 깨지나 | 방어 |
|---|---|---|---|
| ① | **키가 없거나 잘못됨** | 같은 주문 이벤트가 다른 파티션으로 분산 → 순서 무의미 | 키 = 순서를 지켜야 하는 단위(= `orderId`). 절대 `null` 로 두지 않기 |
| ② | **파티션 수 변경** | `hash(key) % partitions` 가 통째로 바뀜. 과거 이벤트는 옛 파티션, 신규는 새 파티션 | 파티션 수는 **처음에 넉넉히**. 늘려야 하면 새 토픽 + 마이그레이션 |
| ③ | **`max.in.flight` > 1 + 재시도** | 배치 1 실패 → 재전송하는 사이 배치 2 가 먼저 기록됨 | `enable.idempotence=true` (시퀀스 번호로 브로커가 재정렬. `max.in.flight<=5` 까지 안전) |
| ④ | **논블로킹 재시도** (Step 08) | 실패 메시지가 retry 토픽을 돌아 **한참 뒤에** 도착 | 순서가 필요하면 `@RetryableTopic` 대신 `DefaultErrorHandler` 블로킹 재시도 |
| ⑤ | **컨슈머 쪽 병렬 처리** | 파티션에서는 순서대로 읽었는데 워커 스레드가 뒤바꿈 | 13-7 참고. 키 단위 파티셔닝된 워커 풀 |

### ③ 을 확인해 보기

`enable.idempotence=false` + `retries=3` + `max.in.flight=5` 로 두고, 브로커에 일시적 오류를 주입하면 이렇게 됩니다.

**결과** (같은 키 `ORD-0007` 로 v1 → v2 → v3 순서로 발행)
```
WARN 14107 --- [ad | producer-1] o.a.k.c.p.internals.Sender               : [Producer clientId=producer-1] Got error produce response with correlation id 12 on topic-partition orders.outbox-1, retrying (2 attempts left). Error: NOT_ENOUGH_REPLICAS
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.OrderingProbe                  : received ORD-0007 version=v2 offset=88
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.OrderingProbe                  : received ORD-0007 version=v3 offset=89
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.OrderingProbe                  : received ORD-0007 version=v1 offset=90
```

**v1 이 맨 뒤에 도착했습니다.** 상태를 v1 로 되돌리는 이벤트가 마지막에 적용되면 데이터가 과거로 돌아갑니다. 예외는 없습니다.

`enable.idempotence=true` (프로젝트 기본값)로 바꾸면 같은 재전송 경고가 뜨는데도 `v1 → v2 → v3` (offset 88, 89, 90) 순서로 도착합니다.
브로커가 시퀀스 번호로 재정렬해 줍니다. **`enable.idempotence=true` 는 중복 제거뿐 아니라 순서 보장 장치이기도 합니다.**

### 전역 순서가 필요하면?

방법은 하나뿐입니다. **파티션 1개.**

| 파티션 수 | 순서 범위 | 최대 컨슈머 수 | 실측 처리량 |
|---:|---|---:|---:|
| 1 | **전역** | 1 | 8,100 msg/s |
| 3 | 키 단위 | 3 | 23,400 msg/s |
| 12 | 키 단위 | 12 | 91,000 msg/s |

전역 순서는 **처리량 상한을 컨슈머 1개로 못 박는 것**과 같은 말입니다. 거의 항상 잘못된 요구사항이고, 실제로 필요한 건 "**같은 주문 안에서의** 순서"입니다.

> 💡 **실무 팁 — "순서가 필요하다"는 요구를 받으면 먼저 범위를 물으세요.**
> "무엇과 무엇 사이의 순서인가?" 대답이 `orderId` 면 키를 `orderId` 로, `customerId` 면 `customerId` 로 잡으면 끝납니다.
> 키를 정하는 것이 곧 **"이 단위 안에서는 순서를 지키고, 이 단위끼리는 병렬 처리하겠다"** 는 선언입니다.
> 파티션 배정 알고리즘 자체는 [Kafka 코스 Step 03](../../kafka/step-03-topics-partitions/) 을 참고하세요.

---

## 13-7. ⚠️ 함정: 리스너 안에서 스레드 풀로 넘기면 순서와 커밋이 동시에 깨진다

"컨슈머가 느리니 병렬로 돌리자"는 발상은 대개 이렇게 구현됩니다.

```java
// ❌ 절대 하면 안 되는 코드
@KafkaListener(topics = "orders.outbox", groupId = "s13-inventory")
public void onMessage(OrderCreated e) {
    executor.submit(() -> inventoryTx.consumeOnce(...));   // 즉시 리턴
}
```

`@Async` 를 붙여도 완전히 같습니다. 리스너 메서드가 **처리를 시작만 하고 즉시 리턴**합니다. 두 가지가 동시에 무너집니다.

**① 커밋이 처리보다 먼저 일어납니다.** Spring 은 리스너 메서드가 정상 리턴하면 "처리 성공"으로 간주하고 오프셋을 커밋합니다. 워커 스레드가 그 뒤에 실패하면 **아무도 모릅니다.** 재시도도, DLT 도, `DefaultErrorHandler` 도 전부 무력화됩니다.

**② 순서가 깨집니다.** 파티션에서는 순서대로 꺼냈지만 워커 스레드 스케줄링은 순서를 보장하지 않습니다.

**결과** (같은 키 `ORD-0007` 의 v1/v2/v3 + 워커에서 예외)
```
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.AsyncBadListener               : submitted ORD-0007 v1 (offset=88)
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.AsyncBadListener               : submitted ORD-0007 v2 (offset=89)
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.AsyncBadListener               : submitted ORD-0007 v3 (offset=90)
DEBUG 14107 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer   : Committing: {orders.outbox-1=OffsetAndMetadata{offset=91}}
INFO 14107 --- [    worker-pool-3] c.e.o.s13.AsyncBadListener              : processed ORD-0007 v3
INFO 14107 --- [    worker-pool-1] c.e.o.s13.AsyncBadListener              : processed ORD-0007 v1
ERROR 14107 --- [    worker-pool-2] c.e.o.s13.AsyncBadListener             : failed ORD-0007 v2
java.lang.RuntimeException: downstream timeout
	at com.example.order.step13.Practice$AsyncBadListener.lambda$onMessage$0(Practice.java:412)
```

`Committing: offset=91` 이 **처리 로그보다 먼저** 찍혔습니다. 그리고 v2 는 실패했는데 이미 커밋됐으므로 **영원히 사라졌습니다.** 처리 순서는 v3 → v1 → v2 입니다.

```bash
kcg --describe --group s13-inventory
```

**결과**
```
GROUP           TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                    HOST         CLIENT-ID
s13-inventory   orders.outbox  0          30              30              0    consumer-s13-inventory-1-e41a  /172.19.0.1  consumer-s13-inventory-1
s13-inventory   orders.outbox  1          91              91              0    consumer-s13-inventory-2-77bd  /172.19.0.1  consumer-s13-inventory-2
s13-inventory   orders.outbox  2          28              28              0    consumer-s13-inventory-3-9c03  /172.19.0.1  consumer-s13-inventory-3
```

**LAG 이 전부 0 입니다.** 지표상으로는 완벽히 건강한 서비스입니다. Step 12 의 랙 알림도 울리지 않습니다. 메시지만 사라졌습니다. 이것이 이 코스가 계속 말해 온 **"에러 없이 조용히 잃는"** 의 최종 형태입니다.

### 그래도 병렬이 필요하다면

**해결 1 — 파티션을 늘리고 `concurrency` 를 올린다.** 대부분 이걸로 끝납니다. Spring 이 파티션 단위로 스레드를 배정하므로 순서도 커밋도 안전합니다.

```yaml
spring.kafka.listener.concurrency: 3   # 파티션 수 이하로. 초과분은 그냥 놉니다 (Step 03)
```

**해결 2 — 키 단위로 파티셔닝된 워커 풀 + 완료 대기.** 파티션을 더 못 늘리는데 처리 시간이 긴 경우입니다.

```java
@KafkaListener(topics = "orders.outbox", groupId = "s13-inventory", batch = "true")
public void onBatch(List<ConsumerRecord<String, OrderCreated>> records, Acknowledgment ack) {
    // 같은 키는 항상 같은 워커로 → 키 안에서 순서 유지
    Map<Integer, List<ConsumerRecord<String, OrderCreated>>> lanes = records.stream()
            .collect(Collectors.groupingBy(r -> Math.abs(r.key().hashCode()) % WORKERS));

    List<CompletableFuture<Void>> futures = lanes.values().stream()
            .map(lane -> CompletableFuture.runAsync(() -> lane.forEach(this::handleOne), executor))
            .toList();

    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();  // ★ 반드시 대기
    ack.acknowledge();
}
```

핵심은 두 줄입니다. **`groupingBy(key)`** 로 같은 키를 같은 레인에 묶고, **`join()`** 으로 전부 끝날 때까지 리스너가 리턴하지 않습니다. `join()` 이 없으면 위의 ❌ 코드와 똑같아집니다.

**결과**
```
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.LaneWorkerListener             : batch=120 lanes=4 elapsed=310ms (serial estimate 1180ms)
DEBUG 14107 --- [ntainer#0-1-C-1] o.s.k.l.KafkaMessageListenerContainer   : Committing: {orders.outbox-1=OffsetAndMetadata{offset=211}}
```

1,180ms → 310ms. **3.8배** 빨라졌고, 커밋은 처리 뒤에 일어났으며, 키 단위 순서는 유지됩니다.

> ⚠️ `max.poll.interval.ms`(기본 5분)를 넘기지 않도록 배치 크기를 조절하세요. 레인 하나가 느리면 배치 전체가 대기합니다.
> `max-poll-records` 를 500 에서 100 정도로 낮추는 것이 안전합니다.

---

## 13-8. 종합 실습 — 주문 서비스 이벤트 연동

지금까지의 13개 스텝을 하나로 조립합니다.

### 요구사항

1. `POST /orders/{seq}` 로 주문을 생성한다. **Kafka 가 죽어 있어도 주문 생성은 성공해야 한다.**
2. 주문 생성은 Outbox 로 `OrderCreated` 를 발행한다 (13-5)
3. 재고 컨슈머(`s13-inventory`)는 **멱등하게** 재고를 차감한다 (13-2, 13-3)
4. 재고 부족은 1초 간격 3회 재시도 후 `orders.outbox.DLT` 로 보낸다 (Step 07)
5. 알림 컨슈머(`s13-notification`)는 **별도 그룹**으로 같은 메시지를 받는다 (팬아웃, Step 03)
6. 컨슈머 랙과 처리 시간을 Actuator 로 노출한다 (Step 12)

### 아키텍처

```
   POST /orders/7
        ▼
┌───────────────────────────────────────────┐
│ OrderController → OrderService            │
│   @Transactional {                        │   ← Kafka 의존 없음
│     INSERT orders                         │
│     INSERT outbox_event(published_at=NULL)│
│   }                                       │
└───────────────────┬───────────────────────┘  MySQL orderdb
                    ▼
        ┌───────────────────────┐  @Scheduled(fixedDelay=500)
        │  OutboxRelay          │  SELECT ... FOR UPDATE SKIP LOCKED
        └───────────┬───────────┘
                    │ send(key=orderId, header messageId=OBX-{id})
                    ▼
     ┌──────────────────────────────────────────┐
     │ topic: orders.outbox  (partitions=3)     │
     └──────┬──────────────────────┬────────────┘
            │ group=s13-inventory  │ group=s13-notification
            ▼                      ▼
  ┌───────────────────────┐  ┌──────────────────────┐
  │ InventoryListener     │  │ NotificationListener │
  │  @Transactional {     │  │  (멱등 키를 그룹명   │
  │    INSERT processed   │  │   접두사로 분리)     │
  │    UPDATE inventory   │  └──────────────────────┘
  │  }                    │
  └───────────┬───────────┘
              │ OutOfStockException × 3 (FixedBackOff 1000ms)
              ▼
  ┌───────────────────────┐        ┌───────────────────────────┐
  │ orders.outbox.DLT     │───────▶│ DltInspector / 재처리 도구 │
  └───────────────────────┘        └───────────────────────────┘

  관측: /actuator/metrics/kafka.consumer.fetch.manager.records.lag.max
        /actuator/metrics/order.inventory.process   (Timer)
        outbox.backlog   (커스텀 게이지 — 릴레이 장애의 유일한 신호)
```

### 구현 체크리스트

| # | 항목 | 근거 스텝 |
|---|---|---|
| 1 | `orders.outbox`(3), `orders.outbox.DLT`(3) 토픽을 `NewTopic` 빈으로 선언 | Step 01, 07 |
| 2 | 프로듀서 `acks=all` + `enable.idempotence=true` + `max.in.flight<=5` | Step 02, 13-6 |
| 3 | `OrderService.createOrder()` — `@Transactional` 안에 `orders` + `outbox_event` INSERT, **Kafka 호출 금지** | 13-5 |
| 4 | `OutboxRelay` — `SKIP LOCKED` 폴링 + `send().join()` + `published_at` 갱신 | 13-5 |
| 5 | 발행 시 `key = orderId`, 헤더 `messageId = OBX-{id}` | 13-2, 13-6 |
| 6 | 컨슈머 `ErrorHandlingDeserializer` + `trusted.packages` | Step 04 |
| 7 | `InventoryTx.consumeOnce()` — 이력 INSERT 와 재고 UPDATE 를 **한 트랜잭션**으로 | 13-3 |
| 8 | `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000, 3))` | Step 07 |
| 9 | `NotificationListener` 를 **다른 `groupId`** 로 등록 (팬아웃) | Step 03 |
| 10 | `@Timed` / `MeterRegistry` 로 처리 시간, `/actuator/metrics` 로 랙 노출 | Step 12 |

### 검증 시나리오

#### ① 정상 흐름

```bash
mq "TRUNCATE processed_message; TRUNCATE outbox_event; DELETE FROM orders;
    UPDATE inventory SET quantity = 1000;"

for i in $(seq 1 10); do curl -s -XPOST localhost:8080/orders/$i; echo; done
```

**결과** (애플리케이션 콘솔)
```
INFO 14107 --- [nio-8080-exec-3] c.e.o.s13.OrderService                   : order ORD-0001 created (outbox staged)
INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay                    : relayed 10 events (id 1..10)
INFO 14107 --- [ntainer#0-1-C-1] c.e.o.s13.InventoryTx                    : deducted SKU-002 -2 (remaining=998)
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s13.InventoryTx                    : deducted SKU-003 -3 (remaining=997)
INFO 14107 --- [ntainer#1-1-C-1] c.e.o.s13.NotificationListener           : notify customer=1001 order=ORD-0001
```

```bash
mq "SELECT sku, quantity FROM inventory ORDER BY sku;
    SELECT COUNT(*) AS processed FROM processed_message;
    SELECT COUNT(*) AS unpublished FROM outbox_event WHERE published_at IS NULL;"
```

**결과**
```
+---------+----------+     processed   = 20   ← 재고 10 + 알림 10 (그룹 접두사로 분리)
| sku     | quantity |     unpublished =  0   ← 릴레이가 전부 발행함
+---------+----------+
| SKU-001 |      989 |
| SKU-002 |      989 |
| SKU-003 |      992 |
+---------+----------+
```

총 차감 30개(989+989+992 = 2970 = 3000 − 30). 기대값과 일치합니다.

#### ② 중복 발행 시 멱등 확인

릴레이가 이미 발행한 이벤트를 강제로 재발행합니다.

```bash
mq "UPDATE outbox_event SET published_at = NULL;"     # 전부 미발행으로 되돌림
```

**결과**
```
INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay                    : relayed 10 events (id 1..10)
WARN 14107 --- [ntainer#0-1-C-1] c.e.o.s13.InventoryTx                    : duplicate messageId=OBX-1 group=s13-inventory — skipped
WARN 14107 --- [ntainer#1-1-C-1] c.e.o.s13.NotificationListener           : duplicate messageId=OBX-1 group=s13-notification — skipped
```

`mq "SELECT sku, quantity FROM inventory ORDER BY sku;"` → **989 / 989 / 992. 변화 없음.** 이것이 이 스텝의 목표입니다. 메시지는 20건이 흘렀지만 재고는 한 번만 차감됐습니다.
멱등 컨슈머를 끄고(`app.idempotent=false`) 같은 실험을 하면 979 / 979 / 984 가 됩니다.

#### ③ 재고 부족으로 DLT

```bash
mq "UPDATE inventory SET quantity = 1 WHERE sku = 'SKU-001';"
curl -s -XPOST localhost:8080/orders/3        # ORD-0003, SKU-001, qty=4
```

**결과**
```
INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay                    : relayed 1 events (id 11..11)
WARN 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx                    : out of stock SKU-001 need=4 have=1 → rollback   (× 3)
ERROR 14107 --- [ntainer#0-2-C-1] o.s.k.l.DefaultErrorHandler             : Backoff FixedBackOff{interval=1000, currentAttempts=3, maxAttempts=3} exhausted for orders.outbox-2@11
INFO 14107 --- [ntainer#0-2-C-1] o.s.k.l.DeadLetterPublishingRecoverer    : Successful dead-letter publication: orders.outbox.DLT-2@0
```

**결과** (`SELECT message_id FROM processed_message WHERE message_id LIKE '%OBX-11'`)
```
+-------------------------+
| message_id              |
+-------------------------+
| s13-notification:OBX-11 |
+-------------------------+
```

재고 그룹의 이력이 **없습니다.** 트랜잭션이 롤백됐기 때문입니다(13-3). 재고를 보충하고 DLT 에서 재처리하면 정상 처리됩니다. 알림 그룹은 재고와 무관하므로 정상 처리됐습니다.

#### ④ 릴레이 중단 후 재개

릴레이만 끄고 주문을 쌓습니다.

```bash
curl -s -XPOST localhost:8080/admin/relay/stop
for i in $(seq 21 25); do curl -s -XPOST localhost:8080/orders/$i; echo; done
mq "SELECT COUNT(*) AS unpublished FROM outbox_event WHERE published_at IS NULL;"
```

**결과**
```
INFO 14107 --- [nio-8080-exec-5] c.e.o.s13.RelayAdmin                     : relay STOPPED
INFO 14107 --- [nio-8080-exec-6] c.e.o.s13.OrderService                   : order ORD-0021 created (outbox staged)
...
+-------------+
| unpublished |
+-------------+
|           5 |
+-------------+
```

**주문 생성은 전부 성공했습니다.** 발행 경로가 죽어도 API 는 200 을 돌려줍니다. 이것이 Outbox 의 값어치입니다.

`kcg --describe --group s13-inventory` 를 찍으면 세 파티션 모두 `LAG 0` 입니다. 아직 발행 자체가 안 됐기 때문입니다.

> 💡 **여기서 랙은 0 입니다.** 밀린 일은 5건인데 Kafka 랙 지표로는 안 보입니다.
> Outbox 를 쓰면 **`SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL` 자체가 지표**가 되어야 합니다.
> Step 12 의 `MeterRegistry.gauge("outbox.backlog", ...)` 로 노출하세요. 이걸 빼먹으면 릴레이 장애를 아무도 모릅니다.

```bash
curl -s -XPOST localhost:8080/admin/relay/start
```

**결과**
```
INFO 14107 --- [nio-8080-exec-7] c.e.o.s13.RelayAdmin                     : relay STARTED
INFO 14107 --- [   scheduling-1] c.e.o.s13.OutboxRelay                    : relayed 5 events (id 11..15)
INFO 14107 --- [ntainer#0-0-C-1] c.e.o.s13.InventoryTx                    : deducted SKU-001 -1 (remaining=988)
...
```

`id 11..15` — **중단 중에 쌓인 것부터 순서대로** 발행됐습니다. `ORDER BY id` 덕분입니다.

---

## 13-9. 배포 전 체크리스트 20항목

각 항목은 **"이 값이면 무슨 일이 일어나는가"** 로 읽으세요. 왼쪽이 위험한 값, 오른쪽이 권장입니다.

| # | 설정 / 코드 | 이 값이면 벌어지는 일 | 권장 | 스텝 |
|---:|---|---|---|---|
| 1 | `send()` 결과를 안 봄 | 발행 실패를 **영원히 모름** | `whenComplete` 또는 릴레이는 `join()` | 02 |
| 2 | 매 건 `send().get()` | 배치 무력화. 18,400 → 920 msg/s | 비동기 콜백 | 02 |
| 3 | `concurrency > partitions` | 남는 스레드가 **경고 없이 놈** | `concurrency ≤ partitions` | 03 |
| 4 | 컨슈머에 `ErrorHandlingDeserializer` 없음 | 포이즌 필 1건이 파티션을 **영구 정지** | 항상 감싸기 | 04 |
| 5 | `spring.json.add.type.headers=true` 유지 | 컨슈머 패키지 리팩터링 순간 **전량 실패** | `false` + `default.type` 지정 | 04 |
| 6 | `trusted.packages: "*"` | 역직렬화 가젯 공격 표면 | 도메인 패키지만 명시 | 04 |
| 7 | `ack-mode: BATCH` + 배치 중간 실패 | 성공분 재처리 또는 실패분 커밋 | `RECORD` 또는 `MANUAL` + 멱등 | 06 |
| 8 | `enable-auto-commit: true` | 처리 전 커밋 → **조용한 유실** | `false` (Spring 이 관리) | 06 |
| 9 | 백오프 총합 > `max.poll.interval.ms` | 재시도 중 **그룹에서 축출** + 리밸런스 루프 | 총합 < 5분, 길면 논블로킹 | 07 |
| 10 | `DefaultErrorHandler` 기본값 | `FixedBackOff(0, 9)` 로 10회 후 **조용히 버림** | Recoverer 로 DLT 지정 | 07 |
| 11 | DLT 파티션 수 ≠ 원본 | 원본 파티션 정보로 보낼 곳이 없어 실패 | 원본과 동일하게 | 07 |
| 12 | `@RetryableTopic` 을 순서 민감 토픽에 | 재시도 메시지가 **한참 뒤에** 도착 | 순서 필요하면 블로킹 재시도 | 08 |
| 13 | `@Transactional` 로 DB+Kafka 를 묶음 | **원자적이지 않음.** 한쪽만 반영 | Outbox | 09, 13-5 |
| 14 | `read_uncommitted`(기본) | 롤백된 트랜잭션 메시지를 소비 | `isolation.level=read_committed` | 09 |
| 15 | `enable.idempotence=false` + `retries>0` | 재전송 중 **순서 역전** | `true` (프로젝트 기본값) | 13-6 |
| 16 | 운영 중 파티션 수 변경 | 키→파티션 매핑이 바뀌어 **순서 파괴** | 처음에 넉넉히. 바꾸려면 새 토픽 | 03, 13-6 |
| 17 | 리스너에서 `@Async` / `executor.submit` | **처리 전 커밋** + 순서 파괴 + LAG 은 0 | `concurrency` 또는 레인 워커 + `join()` | 13-7 |
| 18 | `SELECT` 후 `INSERT` 로 멱등 체크 | 경합 구간에서 **중복 처리** | INSERT-first + `DuplicateKeyException` | 13-2 |
| 19 | 이력 기록과 비즈니스가 다른 트랜잭션 | 이력만 남고 처리 안 됨 → **영구 스킵** | 한 트랜잭션 | 13-3 |
| 20 | `processed_message` 정리 없음 | 1억 행에서 처리량 **13배 하락** | TTL 배치 또는 파티션 DROP | 13-4 |

추가로 **관측 3종**을 반드시 노출하세요 (Step 12).

| 지표 | 왜 필요한가 |
|---|---|
| `kafka.consumer.fetch.manager.records.lag.max` | 컨슈머가 밀리는 것 |
| `outbox.backlog` (커스텀 게이지) | **릴레이가 죽은 것** — Kafka 랙으로는 안 보임 |
| `*.DLT` 토픽의 LOG-END-OFFSET 증가율 | 조용히 버려지는 메시지 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| 현실의 보장 | **at-least-once.** exactly-once 는 멱등성으로 **만드는** 것 |
| 멱등 컨슈머 | `processed_message` + UNIQUE 제약. **INSERT-first**, `DuplicateKeyException` 이면 스킵 |
| 메시지 ID | `topic-partition-offset` ❌ / 비즈니스 키 △ / **프로듀서 생성 ID(outbox PK, UUID)** ✅ |
| `SELECT`-then-`INSERT` | 경합 구간이 존재. 100회 중 11회 중복 처리. 판정은 **DB 제약**에 위임 |
| 이력 + 비즈니스 | **반드시 한 트랜잭션.** 쪼개면 이력만 남고 처리는 안 된 채 영구 스킵 |
| 이력 테이블 청소 | 보관 기간 ≥ 토픽 retention. TTL 배치(`LIMIT` 분할) 또는 파티션 DROP |
| Transactional Outbox | 비즈니스 INSERT + outbox INSERT 를 한 트랜잭션. **Kafka 를 트랜잭션에서 배제** |
| `OutboxRelay` | `FOR UPDATE SKIP LOCKED` + `send().join()` + `published_at` 갱신 |
| Outbox 의 성질 | **at-least-once.** 갱신 전 크래시 = 재발행. 멱등 컨슈머와 반드시 짝 |
| 폴링 vs CDC | 폴링 p50 261ms / 코드 40줄. CDC p50 12ms / 인프라 1개 추가. **폴링부터 시작** |
| 순서 보장 범위 | **파티션 내부만.** 전역 순서 = 파티션 1개 = 처리량 8,100 msg/s 상한 |
| 키 설계 | 키를 정하는 것이 "이 단위 안에서 순서를 지키겠다"는 선언 |
| 순서를 깨는 5가지 | 키 없음 / 파티션 수 변경 / 비멱등 재전송 / 논블로킹 재시도 / 컨슈머 병렬화 |
| `@Async` 리스너 | **처리 전 커밋 + 순서 파괴.** 그런데 LAG 은 0. 최악의 침묵 |
| 안전한 병렬화 | `concurrency` 우선. 부족하면 키 단위 레인 + `allOf(...).join()` |
| Outbox 백로그 지표 | Kafka 랙으로 안 보임. `outbox.backlog` 게이지를 별도로 노출 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **전부 MySQL 결과를 눈으로 확인해야 하는 문제**입니다.

1. `messageId` 헤더 기반 멱등 컨슈머를 구현하고, 같은 이벤트 10건을 **두 번 발행**해도 `inventory` 가 한 번만 차감되는지 검증하기
2. `SELECT`-then-`INSERT` 방식으로 바꾼 뒤 두 스레드로 같은 메시지를 100회 동시 처리해 **중복 차감 횟수를 세기**
3. `OrderService` + `OutboxRelay` 를 구현하고, **Kafka 컨테이너를 내린 상태에서** 주문 생성이 성공하는지 확인하기
4. `SKIP LOCKED` 를 뺀 릴레이를 두 인스턴스로 띄워 **중복 발행 건수를 측정**하고, 넣었을 때와 비교하기
5. 주어진 5개 코드 조각 중 **순서 보장이 깨지는 것을 모두 고르고** 각각의 이유를 한 줄로 적기
6. `orders.outbox.DLT` 를 읽어 원본 토픽으로 재발행하는 **재처리 도구**를 만들기 (`messageId` 헤더를 보존해야 멱등성이 유지됩니다)

---

## 코스를 마치며

13개 스텝, 하나의 주제였습니다. **에러 없이 조용히 잘못 동작하는 코드를 찾아내는 것.**

각 스텝에서 잡은 침묵을 한 줄씩 되짚습니다.

| Step | 조용한 실패 | 신호 |
|---|---|---|
| 01 | 자동 설정이 만든 빈을 모른 채 덮어씀 | 없음 |
| 02 | `send()` 가 리턴했는데 브로커에 안 감 | `CompletableFuture` 안에만 |
| 03 | `concurrency` 초과분 스레드가 그냥 놈 | 없음 |
| 04 | `__TypeId__` 패키지 불일치로 전량 실패 | 파티션 정지 |
| 05 | 헤더가 없어 추적 ID 가 끊김 | 없음 |
| 06 | 처리 전에 커밋되어 메시지 유실 | LAG 은 0 |
| 07 | `FixedBackOff(0,9)` 소진 후 조용히 버림 | ERROR 한 줄뿐 |
| 08 | 재시도 메시지가 순서를 벗어나 도착 | 없음 |
| 09 | DB 는 커밋, Kafka 는 롤백 | 없음 |
| 10 | `autoStartup=false` 컨테이너를 아무도 안 켬 | 랙 증가만 |
| 11 | `Thread.sleep` 테스트가 CI 에서만 실패 | 간헐적 |
| 12 | 지표는 있는데 **아무도 안 봄** | — |
| 13 | 이력만 남고 처리 안 됨 / `@Async` 로 처리 전 커밋 | LAG 은 0 |

공통점이 보이시나요. **거의 모든 항목의 "신호" 칸이 비어 있습니다.** Kafka 를 쓰는 일의 절반은 이 빈칸을 채워 넣는 일입니다. 로그를 교재와 대조하라고 반복해서 말한 이유가 이것입니다.

### 더 공부할 것

| 주제 | 무엇인가 | 어디서 |
|---|---|---|
| **Kafka Streams** | 토픽 → 토픽 변환을 상태 저장소와 함께. 조인·윈도우 집계·`exactly_once_v2` | [Kafka 코스 Step 13](../../kafka/step-13-streams/) |
| **Kafka Connect / CDC** | 코드 없이 DB ↔ Kafka. Debezium 으로 13-5 의 폴링 릴레이 대체 | [Kafka 코스 Step 12](../../kafka/step-12-connect/) |
| **스키마 레지스트리** | Avro/Protobuf + 호환성 규칙. Step 04 의 `__TypeId__` 결합 문제를 **계약**으로 해결 | Confluent Schema Registry |
| **Saga 패턴** | 서비스에 걸친 트랜잭션을 보상 트랜잭션의 연쇄로. Outbox 가 그 전제 | 코레오그래피 / 오케스트레이션 |
| **이벤트 소싱** | 상태가 아니라 **이벤트를 원본**으로 저장. 스냅숏·리플레이·CQRS | 도메인 설계 영역 |

우선순위를 하나만 고른다면 **스키마 레지스트리**입니다. 컨슈머가 셋 이상으로 늘어나는 순간, 이벤트 스키마 변경이 가장 자주 터지는 사고 지점이 됩니다.

수고하셨습니다.

---

## 실습 파일

이 스텝은 앞의 열두 스텝과 달리 **완성된 서비스를 조립**합니다. `Practice.java` 를 `step13-idem` → `step13-outbox` → `step13-order` 순서로 켜면서 13-2 → 13-5 → 13-8 을 차례로 재현하고, 매 단계마다 **MySQL 을 직접 조회해** 결과를 확인하세요. 이 스텝은 콘솔 로그만 봐서는 절반밖에 못 봅니다. 그다음 `Exercise.java` 의 6문제를 풀고 `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.order.step13` 패키지에 둡니다.

### Practice.java

본문 13-2 ~ 13-8 의 모든 예제를 절 번호 주석과 함께 nested static class 로 담았습니다.

- 프로필이 5개입니다. `step13,step13-idem`(13-2 멱등 + 경합 재현), `step13,step13-split`(13-3 트랜잭션 분리 함정), `step13,step13-outbox`(13-5 Outbox 전체), `step13,step13-async`(13-7 `@Async` 함정), `step13,step13-order`(13-8 최종 프로젝트). **하나씩만 켜세요.** 리스너 그룹이 겹치면 재현이 안 됩니다.
- `Sql` 클래스에 이 스텝의 모든 SQL 상수가 모여 있습니다. `INSERT_MARK`, `PICK_OUTBOX`(`FOR UPDATE SKIP LOCKED`), `DEDUCT`(`AND quantity >= ?` 조건 포함)를 먼저 읽으면 나머지 코드가 빨리 읽힙니다. 특히 `DEDUCT` 의 `updated == 0` 판정이 재고 부족 검출의 전부입니다.
- `[13-2] RaceDemo` 는 **일부러 Kafka 를 안 씁니다.** 두 스레드를 `CountDownLatch` 로 동시에 출발시켜 `SELECT`-then-`INSERT` 의 경합만 순수하게 재현합니다. `ROUNDS = 100` 을 돌리고 마지막에 중복 차감 횟수를 집계해 찍습니다. 실행할 때마다 횟수가 달라지는 것 자체가 이 문제의 성질입니다.
- `[13-5] OutboxRelay` 의 `CRASH_AT_ID` 상수를 `-1` 이 아닌 값(예: `7`)으로 바꾸면, 발행 직후 `published_at` 갱신 전에 `System.exit(1)` 로 죽습니다. 재시작 후 그 이벤트가 **다시 발행되는 것**을 확인하는 용도입니다. Outbox 가 at-least-once 라는 것을 몸으로 확인하는 유일한 방법입니다.
- `[13-7] AsyncBadListener` 는 잘못된 코드이고 `[13-7] LaneWorkerListener` 가 올바른 코드입니다. 두 리스너가 같은 프로필에 있지만 **그룹이 다르므로** 같은 메시지를 각자 받습니다. 커밋 로그를 비교하려면 `logging.level.org.springframework.kafka.listener=DEBUG` 를 켜세요. `Committing:` 줄의 위치가 전부입니다.
- `[13-8] OrderController` 와 `RelayAdmin` 은 `spring-boot-starter-web` 이 필요합니다. 13-0 에서 추가하지 않았다면 이 프로필은 기동에 실패합니다. `RelayAdmin` 은 릴레이를 켜고 끄는 플래그만 토글하며, 검증 시나리오 ④ 에서 씁니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일되도록 뼈대를 남겼습니다.

- **문제 1·2** 는 멱등 컨슈머, **문제 3·4** 는 Outbox, **문제 5** 는 순서, **문제 6** 은 DLT 재처리입니다. 3 번을 풀어야 4 번이 돌아가므로 순서대로 푸세요.
- 문제 2 는 코드보다 **집계**가 핵심입니다. `duplicateDeductions` 카운터를 정확히 올리는 위치를 찾아야 합니다. 힌트: `INSERT` 가 `DuplicateKeyException` 을 던졌는데 **그 전에 이미 차감이 일어난** 경우만 셉니다.
- 문제 4 는 인스턴스를 두 개 띄워야 합니다. `./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q4 --server.port=8081'` 을 다른 터미널에서 한 번 더 실행하세요. `--server.port` 를 안 바꾸면 두 번째가 기동에 실패합니다.
- 문제 5 는 **코드를 안 씁니다.** `// 답:` 주석에 번호와 이유를 적는 문제입니다. 다섯 조각 중 세 개가 순서를 깨뜨립니다. 13-6 의 표와 대조하며 푸세요.
- ⚠️ 문제 6 의 재처리 도구는 **`messageId` 헤더를 반드시 보존**해야 합니다. 새 UUID 를 만들어 붙이면 멱등성이 깨져 재고가 두 번 차감됩니다. 각 문제 끝의 `// 확인:` 주석에 기대 결과(로그 또는 `SELECT` 결과)가 적혀 있습니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `try { INSERT } catch (DuplicateKeyException)` 구조와, 저장 키를 `group + ":" + messageId` 로 만드는 이유가 핵심입니다. 그룹 접두사를 빼면 팬아웃 컨슈머 중 하나가 조용히 스킵된다는 13-2 의 함정을 주석으로 다시 설명합니다.
- **정답 2** 는 중복 차감 11/100 이라는 측정값과, 그 수치가 **실행마다 달라진다는 사실 자체**가 답의 일부라는 점을 설명합니다. "테스트가 통과했으니 괜찮다"가 왜 경합 문제에 통하지 않는지 적었습니다.
- **정답 3** 은 `OrderService` 에 `KafkaTemplate` 을 주입하지 않는 것이 이 패턴의 본질임을 강조합니다. Kafka 를 내린 상태(`docker compose stop kafka`)에서 `POST /orders/1` 이 200 을 반환하고 `outbox_event` 에 행이 쌓이는 로그를 함께 실었습니다.
- **정답 4** 는 `SKIP LOCKED` 유무의 실측 비교입니다. 300건 발행 시 없으면 573건(273건 중복), 있으면 300건입니다. 여기에 더해 `FOR UPDATE` 만 쓴 경우(중복 0, 처리량 절반, 락 대기)를 세 번째 열로 넣어 셋을 비교합니다.
- **정답 5** 의 정답은 **(b), (c), (e)** 입니다. (b)는 키를 `null` 로 보내는 것, (c)는 `enable.idempotence=false` + `retries=3`, (e)는 리스너에서 `CompletableFuture.runAsync` 후 대기하지 않는 것입니다. (a) `concurrency=3` 과 (d) `@RetryableTopic` 없는 블로킹 재시도는 순서를 깨지 않습니다. 각각 왜 안전한지도 적었습니다.
- **정답 6** 은 재처리 도구가 원본 헤더를 **선택적으로** 복사해야 한다는 점이 핵심입니다. `messageId` 는 보존하고, `kafka_dlt-*` 헤더는 제거합니다. 그대로 두면 재처리된 메시지가 다시 DLT 로 갔을 때 헤더가 중첩돼 원본 정보를 잃습니다.

```java file="./Solution.java"
```
