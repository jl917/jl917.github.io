# Step 10 — 리스너 컨테이너 제어

> **학습 목표**
> - 리스너 컨테이너가 `SmartLifecycle` 로서 뜨고 지는 순간의 로그 순서를 끝까지 추적한다
> - `KafkaListenerEndpointRegistry` 로 컨테이너를 이름으로 꺼내고, **id 를 안 주면 왜 코드가 깨지는지** 확인한다
> - `autoStartup=false` 리스너를 REST 로 켜고 끄면서 `partitions assigned` 가 그때 나오는 것을 본다
> - `pause()` 가 **즉시 멈추지 않는다**는 것을 `isPauseRequested()` 와 `isContainerPaused()` 의 시차로 실측한다
> - **pause 는 안전하고 `Thread.sleep` 은 컨슈머를 쫓아낸다**는 것을 같은 대기 시간으로 대비해 재현한다
> - `RecordFilterStrategy` 로 레코드를 버리되 **오프셋은 커밋된다**는 것과, 대역폭은 전혀 절약되지 않는다는 것을 지표로 확인한다
> - `ConsumerAwareRebalanceListener` 와 `ConsumerSeekAware` 로 리밸런스 시점에 개입하고 특정 시각부터 재처리한다
> - `ListenerContainerIdleEvent` 로 "메시지가 안 들어온다"를 감지하는 운영 알림을 만든다
>
> **선행 스텝**: Step 09 — 트랜잭션
> **예상 소요**: 90분

---

## 10-0. 실습 준비

이 스텝은 컨슈머 그룹 `s10-inventory` / `s10-notification` 을 씁니다. 토픽은 `orders`(파티션 3) 입니다.

이 스텝부터는 **컨테이너를 HTTP 로 제어**하므로 웹 스타터가 필요합니다. `build.gradle` 에 한 줄 추가하세요.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
```

```bash
# 앱을 끈 상태에서
kcg --group s10-inventory --topic orders --reset-offsets --to-earliest --execute
```

지금까지의 아홉 스텝은 **"메시지를 어떻게 처리할 것인가"** 였습니다. 이 스텝은 **"처리하는 그 물건을 어떻게 조종할 것인가"** 입니다. 컨테이너는 손댈 일 없는 블랙박스처럼 보이지만, 운영에 나가면 반드시 손대게 됩니다. 다운스트림 DB 가 죽었을 때 소비를 잠깐 멈춰야 하고, 배포 중에 컨슈머를 내려야 하고, 어제 오후 3시부터 다시 처리해 달라는 요청이 옵니다.

---

## 10-1. 컨테이너 생명주기 — `SmartLifecycle`

`@KafkaListener` 하나마다 `MessageListenerContainer` 하나가 만들어집니다. 기본 팩토리를 쓰면 구현체는 `ConcurrentMessageListenerContainer` 이고, 그 안에 `concurrency` 개수만큼 `KafkaMessageListenerContainer` 가 들어 있습니다. 각 `KafkaMessageListenerContainer` 가 **컨슈머 하나 + 전용 스레드 하나**를 가집니다.

```
ConcurrentMessageListenerContainer  (id = "inventoryListener")
├── KafkaMessageListenerContainer#0  → Consumer, thread inventoryListener-0-C-1
├── KafkaMessageListenerContainer#1  → Consumer, thread inventoryListener-1-C-1
└── KafkaMessageListenerContainer#2  → Consumer, thread inventoryListener-2-C-1
```

이 컨테이너들은 Spring 의 `SmartLifecycle` 빈입니다. 즉 **컨텍스트가 리프레시를 마친 뒤** `phase` 오름차순으로 `start()` 가 호출되고, 종료 시에는 **`phase` 내림차순**으로 `stop()` 이 호출됩니다.

| 메서드 / 속성 | 의미 |
|---|---|
| `isAutoStartup()` | 컨텍스트 기동 시 자동으로 `start()` 할지. `@KafkaListener(autoStartup=...)` 로 결정 |
| `getPhase()` | 시작/종료 순서. Spring Kafka 기본값 **`Integer.MAX_VALUE - 100`** |
| `start()` | 컨슈머 스레드를 띄우고 구독을 건다. **논블로킹** |
| `isRunning()` | 스레드가 살아 있는가. `start()` 직후 바로 true |
| `stop()` / `stop(Runnable)` | 컨슈머를 내린다. 콜백 버전은 실제로 멈춘 뒤 호출 |

`phase` 기본값이 거의 최대치라는 점이 중요합니다. **컨테이너가 가장 늦게 시작되고 가장 먼저 종료된다**는 뜻입니다. 리스너가 의존하는 DataSource·캐시·HTTP 클라이언트가 먼저 준비되고, 종료할 때는 컨슈머부터 끊어 새 메시지 유입을 막은 뒤 나머지를 정리합니다. 합리적인 기본값이니 특별한 이유 없이 바꾸지 마세요.

`@KafkaListener(id = "inventoryListener", topics = "orders", groupId = "s10-inventory")` 하나만 두고 기동부터 종료까지의 로그를 봅니다.

**결과** (기동)
```
INFO 14310 --- [           main] o.a.k.c.c.KafkaConsumer                  : [Consumer clientId=consumer-s10-inventory-1, groupId=s10-inventory] Subscribed to topic(s): orders
INFO 14310 --- [           main] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: Consumer started
INFO 14310 --- [           main] c.e.o.OrderServiceApplication            : Started OrderServiceApplication in 2.641 seconds (process running for 3.012)
INFO 14310 --- [yListener-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s10-inventory-1, groupId=s10-inventory] Successfully joined group with generation Generation{generationId=1, memberId='consumer-s10-inventory-1-a3f8c2', protocol='range'}
INFO 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: partitions assigned: [orders-0, orders-1, orders-2]
```

**결과** (Ctrl+C 종료)
```
INFO 14310 --- [ionShutdownHook] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: Consumer stopped
INFO 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: partitions revoked: [orders-0, orders-1, orders-2]
INFO 14310 --- [yListener-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s10-inventory-1, groupId=s10-inventory] Member consumer-s10-inventory-1-a3f8c2 sending LeaveGroup request to coordinator due to the consumer is being closed
```

세 가지를 읽어야 합니다. ① `Started OrderServiceApplication` 이 **`partitions assigned` 보다 먼저** 찍힙니다. `start()` 는 스레드만 띄우고 즉시 리턴하므로, 앱이 "떴다"고 로그를 남긴 시점에 컨슈머는 아직 그룹에 합류하지도 않았습니다. ② 스레드 이름이 `[yListener-0-C-1]` 입니다. 실제 이름은 `inventoryListener-0-C-1` 인데 로그 패턴 `%15.15t` 가 **앞을 잘라** 뒤 15자만 남긴 것입니다. ③ 종료 시 `Consumer stopped` 가 `partitions revoked` **보다 먼저** 나옵니다. 셧다운 훅 스레드가 "멈춰라"를 먼저 기록하고, 실제 정리는 컨슈머 스레드가 이어서 합니다.

> ⚠️ **함정 — `isRunning() == true` 는 "메시지를 받을 준비가 됐다"가 아니다**
> `container.start()` 는 논블로킹입니다. 스레드를 하나 띄우고 바로 리턴하므로 `isRunning()` 은 즉시 true 가 됩니다. 하지만 그 시점에 컨슈머는 **아직 그룹 조인 중**이고 파티션이 하나도 없습니다.
> **증상**: 통합 테스트에서 `container.start(); template.send(...);` 를 붙여 쓰면 메시지가 유실된 것처럼 보입니다. 사실은 조인이 끝나기 전에 보낸 메시지가 `auto.offset.reset=latest` 기준의 "과거"가 된 것입니다. 로컬에서는 통과하고 CI 에서만 실패합니다.
> **해결**: `ContainerTestUtils.waitForAssignment(container, 3)` 으로 **파티션 할당까지** 기다리세요. 운영 코드라면 `partitions assigned` 로그나 `ConsumerStartedEvent` 를 기준으로 삼습니다. (Step 11 에서 다시 다룹니다)

---

## 10-2. `KafkaListenerEndpointRegistry` — 컨테이너를 꺼내 온다

`@KafkaListener` 로 만들어진 컨테이너는 일반 빈이 아닙니다. `KafkaListenerEndpointRegistry` 라는 **레지스트리 빈이 안에 들고 있습니다.** 그래서 `@Autowired MessageListenerContainer` 로는 못 꺼냅니다. 레지스트리를 주입받아야 합니다.

| 메서드 | 리턴 | 비고 |
|---|---|---|
| `getListenerContainerIds()` | `Set<String>` | 등록된 모든 id |
| `getListenerContainer(String id)` | `MessageListenerContainer` | **없으면 `null`.** 예외가 아닙니다 |
| `getListenerContainers()` | `Collection<MessageListenerContainer>` | `@KafkaListener` 로 만든 것만 |
| `getAllListenerContainers()` | `Collection<MessageListenerContainer>` | 위 + **직접 등록한 `MessageListenerContainer` 빈**까지 |

`getListenerContainers()` 와 `getAllListenerContainers()` 의 차이가 실무에서 자주 걸립니다. `ConcurrentMessageListenerContainer` 를 `@Bean` 으로 직접 만들어 쓰는 코드가 섞여 있으면, 앞쪽 메서드로는 **그 컨테이너가 목록에 안 나옵니다.** "전부 멈춰라" 같은 관리 기능을 만들 때는 `getAllListenerContainers()` 를 쓰세요.

이제 리스너 두 개에 **id 를 주지 않고** 레지스트리를 덤프해 봅니다.

**결과** (`@KafkaListener(topics="orders", groupId="s10-inventory")` — id 없음)
```
INFO 14310 --- [           main] c.e.o.step10.Practice$RegistryDump       : 등록된 리스너 id 목록:
INFO 14310 --- [           main] c.e.o.step10.Practice$RegistryDump       :   - org.springframework.kafka.KafkaListenerEndpointContainer#0
INFO 14310 --- [           main] c.e.o.step10.Practice$RegistryDump       :   - org.springframework.kafka.KafkaListenerEndpointContainer#1
INFO 14310 --- [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: partitions assigned: [orders-0, orders-1, orders-2]
```

`#0`, `#1` 은 **빈이 스캔된 순서로 매겨지는 일련번호**입니다. 로그의 `[ntainer#0-0-C-1]` 도 여기서 나옵니다 — `org.springframework.kafka.KafkaListenerEndpointContainer#0-0-C-1` 의 뒤 15자입니다.

**결과** (`id = "inventoryListener"` 를 붙인 뒤)
```
INFO 14310 --- [           main] c.e.o.step10.Practice$RegistryDump       :   - inventoryListener
INFO 14310 --- [           main] c.e.o.step10.Practice$RegistryDump       :   - notificationListener
INFO 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: partitions assigned: [orders-0, orders-1, orders-2]
```

로그의 스레드 이름만으로 **어느 리스너인지 바로 알 수 있습니다.** 리스너가 다섯 개 붙은 서비스에서 이 차이는 디버깅 시간을 크게 줄여 줍니다.

> ⚠️ **함정 — id 를 안 주면 리스너 순서가 바뀌는 순간 코드가 깨진다**
> `registry.getListenerContainer("org.springframework.kafka.KafkaListenerEndpointContainer#0")` 으로 컨테이너를 꺼내는 코드를 짰다고 합시다. 잘 돌아갑니다. 그런데 누군가 **다른 클래스에 `@KafkaListener` 를 하나 추가**하고, 그 클래스 이름이 알파벳 순으로 앞이라 먼저 스캔되면 번호가 밀립니다. `#0` 이 다른 리스너를 가리키게 됩니다.
> **증상**: `getListenerContainer` 는 `null` 을 던지지 않고 **엉뚱한 컨테이너를 정상적으로 리턴합니다.** "재고 리스너를 멈춰라" 가 알림 리스너를 멈춥니다. 예외도 경고도 없습니다.
> **해결**: `@KafkaListener` 에는 **항상 `id` 를 명시**하세요. 자동 번호는 디버깅용이지 식별자가 아닙니다.

> ⚠️ **함정 — `id` 를 주면 그게 컨슈머 그룹이 되어 버린다**
> `@KafkaListener(id = "inventoryListener", topics = "orders")` — `groupId` 를 안 썼습니다. 이 경우 **`id` 값이 `group.id` 로 쓰입니다.** `application.yml` 의 `spring.kafka.consumer.group-id` 도, 팩토리의 기본 그룹도 무시됩니다. `idIsGroup` 속성의 기본값이 `true` 이기 때문입니다.
> **증상**: 컨슈머 그룹이 조용히 `inventoryListener` 로 생깁니다. `kcg --list` 를 보면 알 수 있지만, 보통은 **"오프셋 리셋을 했는데 왜 계속 처음부터 읽지"** 로 발견합니다. 리셋한 그룹과 실제 그룹이 다른 것입니다.
> **해결**: `@KafkaListener(id = "inventoryListener", groupId = "s10-inventory", topics = "orders")` 처럼 **둘 다 명시**하세요. 굳이 그룹을 물려받고 싶으면 `idIsGroup = false` 를 답니다.

---

## 10-3. `autoStartup=false` — 시작을 내가 정한다

기본값은 `autoStartup="true"` 입니다. 앱이 뜨면 컨테이너도 뜹니다. 하지만 **새벽 2시에만 정산 이벤트를 몰아 처리**하거나, **캐시 워밍업이 끝난 뒤 소비를 시작**하거나, **블루/그린 배포에서 전환 시점에 소비를 넘기거나**, **장애 원인을 파악할 때까지 앱만 살려 두고 소비를 멈춰야** 할 때는 곤란합니다.

`autoStartup` 은 **문자열**입니다. 프로퍼티 플레이스홀더를 쓸 수 있게 하려는 설계입니다.

```java
@KafkaListener(
        id = "settlementListener",
        topics = "orders",
        groupId = "s10-settlement",
        autoStartup = "false")                 // ← 문자열 "false"
public void settle(OrderCreated event) {
    log.info("정산 {} {}", event.orderId(), event.amount());
}
```

```java
@PostMapping("/admin/listeners/{id}/start")
public String start(@PathVariable String id) {
    MessageListenerContainer c = registry.getListenerContainer(id);
    c.start();
    return id + " running=" + c.isRunning();
}
```

```bash
curl -XPOST localhost:8080/admin/listeners/settlementListener/start
```

**결과**
```
INFO 14310 --- [nio-8080-exec-1] o.a.k.c.c.KafkaConsumer                  : [Consumer clientId=consumer-s10-settlement-1, groupId=s10-settlement] Subscribed to topic(s): orders
INFO 14310 --- [nio-8080-exec-1] o.s.k.l.KafkaMessageListenerContainer    : s10-settlement: Consumer started
INFO 14310 --- [tListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-settlement: partitions assigned: [orders-0, orders-1, orders-2]
INFO 14310 --- [tListener-0-C-1] c.e.o.step10.Practice$Settlement         : 정산 ORD-0001 11000
```

`Subscribed to topic(s)` 가 `[nio-8080-exec-1]`, 즉 **톰캣 요청 스레드**에서 찍히는 것에 주목하세요. `start()` 를 호출한 스레드가 컨슈머 스레드를 만듭니다. `autoStartup = "${app.listener.settlement.enabled:false}"` 처럼 플레이스홀더를 쓰면 환경별로 다르게 줄 수 있습니다. **문자열인 이유가 이것**입니다.

> 💡 **실무 팁 — `stop()` 은 그룹을 떠나므로 리밸런스를 유발합니다.**
> 인스턴스 3대 중 1대에서만 특정 리스너를 `stop()` 하면 나머지 2대가 리밸런스를 겪습니다. "잠깐 안 받겠다"가 목적이라면 `stop()` 이 아니라 **`pause()`** 가 맞습니다(10-4). `pause` 는 그룹 멤버십을 유지합니다.

---

## 10-4. pause / resume — 그룹에서 나가지 않고 멈추기

`pause()` 는 파티션 할당을 유지한 채 **레코드 인도만 멈춥니다.** 내부적으로 `consumer.pause(assignedPartitions)` 를 호출하고, 컨테이너는 계속 `poll()` 을 돌립니다. 다만 그 poll 은 레코드를 0건 리턴합니다.

| 메서드 | 의미 |
|---|---|
| `pause()` | **요청**을 기록. 다음 poll 사이클에 반영 |
| `resume()` | 재개 요청 |
| `isPauseRequested()` | `pause()` 가 호출되었는가. **즉시 true** |
| `isContainerPaused()` | 실제로 모든 컨슈머가 멈췄는가. **지연되어 true** |
| `isPartitionPauseRequested(tp)` | 개별 파티션 일시정지 요청 여부 |

이 둘의 차이가 이 절의 핵심입니다. `pause()` 를 부른 뒤 `isContainerPaused()` 가 true 가 될 때까지 50ms 간격으로 폴링하며 시간을 재 봅니다.

**결과** (`max-poll-records: 500`, 레코드당 처리 4ms)
```
INFO 14310 --- [nio-8080-exec-3] c.e.o.step10.Practice$PauseDemo          : pause() 리턴  t=0ms requested=true paused=false
INFO 14310 --- [yListener-1-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-002 x3
...  (이미 poll 로 가져온 배치를 계속 처리)
DEBUG 14310 --- [yListener-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Pausing consumer
DEBUG 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Pausing consumer
DEBUG 14310 --- [yListener-2-C-1] o.s.k.l.KafkaMessageListenerContainer    : Pausing consumer
INFO 14310 --- [nio-8080-exec-3] c.e.o.step10.Practice$PauseDemo          : 실제 정지 완료 t=1843ms
```

**`pause()` 호출부터 실제 정지까지 1,843ms.** `max.poll.records=500`, 레코드당 4ms 이므로 잔여 배치를 비우는 데 약 2초가 걸린 것입니다. 이 값을 줄이고 싶으면 `max-poll-records` 를 낮추면 됩니다. 100 으로 낮춰 같은 실험을 하면 **372ms** 로 줄어듭니다.

| `max-poll-records` | pause 요청 → 실제 정지 |
|---:|---:|
| 500 (기본) | 1,843ms |
| 100 | 372ms |
| 10 | 47ms |

### 백프레셔 — 실제로 쓰는 자리

가장 흔한 용도는 **다운스트림이 죽었을 때 소비를 멈추는 것**입니다. Step 07 의 블로킹 재시도로 버티면 파티션이 인질로 잡히고 `max.poll.interval.ms` 를 넘겨 리밸런스가 납니다. `pause` 는 그 위험이 없습니다.

```java
catch (DownstreamUnavailableException e) {
    if (failures.incrementAndGet() >= 5) {
        log.warn("연속 실패 {}회 — 소비를 일시정지합니다", failures.get());
        registry.getListenerContainer("inventoryListener").pause();
        healthChecker.scheduleResume();     // ★ 별도 스레드가 회복을 확인해 resume
    }
    throw e;
}
```

**결과**
```
WARN  14310 --- [yListener-1-C-1] c.e.o.step10.Practice$BackPressure       : 연속 실패 5회 — 소비를 일시정지합니다
DEBUG 14310 --- [yListener-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Pausing consumer
INFO  14310 --- [   scheduling-1] c.e.o.step10.Practice$HealthChecker      : 다운스트림 응답 없음. 10초 후 재확인
INFO  14310 --- [   scheduling-1] c.e.o.step10.Practice$HealthChecker      : 다운스트림 회복 확인 — 소비를 재개합니다
DEBUG 14310 --- [yListener-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Resuming consumer
```

`scheduleResume` 을 **별도 스레드**로 뺀 것이 핵심입니다. pause 중에는 리스너가 아예 호출되지 않으므로, 리스너 안에서 회복을 확인해 resume 하는 코드는 영원히 실행되지 않습니다. 이 3분 동안 LAG 은 쌓이지만 **컨슈머 그룹 상태는 계속 `Stable`** 입니다. 그룹을 떠나지 않았기 때문입니다.

> ⚠️ **함정 — `pause()` 를 부르고 바로 "멈췄다"고 가정하면 안 된다**
> `pause()` 는 **플래그를 세우고 즉시 리턴**합니다. 그 시점에 컨슈머 스레드는 이미 poll 로 최대 500건을 손에 쥐고 있고, 그걸 **전부 처리한 뒤에야** 멈춥니다.
> **증상**: "DB 점검 들어가니 pause 걸고 스키마 바꿔라" 라는 배포 스크립트를 만들면, pause 직후 1~2초 동안 **레코드가 계속 처리되어 DDL 과 충돌**합니다. 재현이 잘 안 되는 간헐적 오류가 됩니다.
> **해결**: `isContainerPaused()` 가 true 가 될 때까지 폴링하고 기다리세요. 대기 시간의 상한은 `max.poll.records × 레코드당 처리시간` 입니다. 정말 짧은 정지 지연이 필요하면 `max-poll-records` 를 낮추는 것이 유일한 방법입니다.

---

## 10-5. 개별 파티션 pause

컨테이너 전체가 아니라 **문제가 있는 파티션 하나만** 멈출 수 있습니다. 특정 고객사(=특정 키 → 특정 파티션)의 다운스트림만 죽었을 때 유용합니다.

```java
TopicPartition tp = new TopicPartition("orders", 1);
container.pausePartition(tp);
log.info("orders-1 정지 요청됨={}", container.isPartitionPauseRequested(tp));
// ...
container.resumePartition(tp);
```

**결과**
```
INFO 14310 --- [nio-8080-exec-2] c.e.o.step10.Practice$PartitionPause     : orders-1 정지 요청됨=true
DEBUG 14310 --- [yListener-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : Pausing partitions: [orders-1]
INFO 14310 --- [yListener-0-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-001 x2      ← orders-0 은 계속 흐른다
```

```
GROUP          TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
s10-inventory  orders  0          1620            1620            0
s10-inventory  orders  1          1198            1604            406      ← 여기만 밀린다
s10-inventory  orders  2          1631            1631            0
```

> ⚠️ **함정 — 리밸런스가 나면 파티션 pause 상태가 초기화된다**
> `pausePartition` 은 **할당된 파티션에 대한 요청**입니다. 리밸런스가 일어나 파티션이 회수됐다가 다시 할당되면, Spring 은 그 파티션을 **재개된 상태로** 되돌립니다. 로그에 `Paused consumer resumed by Kafka client` 가 찍힙니다.
> **증상**: `orders-1` 을 멈춰 두고 안심하고 있었는데, 다른 인스턴스가 배포로 재기동되면서 리밸런스가 나면 **`orders-1` 이 조용히 다시 흐르기 시작합니다.** 아무 에러도 없습니다.
> **해결**: 멈춘 파티션 목록을 **애플리케이션이 직접 상태로 들고**, `ConsumerAwareRebalanceListener.onPartitionsAssigned`(10-8) 에서 다시 `pausePartition` 을 걸어 주세요. pause 는 **일시적 지시**이지 영속 설정이 아닙니다.

---

## 10-6. pause 는 안전하고 `Thread.sleep` 은 위험하다

"소비를 잠깐 멈추고 싶다"에 대한 가장 흔한 오답이 **리스너 안에서 자는 것**입니다. 두 방식을 같은 대기 시간(90초)으로 나란히 돌려 비교합니다. `max.poll.interval.ms` 는 재현 시간을 줄이려 **60초**로 낮춥니다.

### ① `Thread.sleep` 으로 90초 대기

```java
public void onMessage(OrderCreated event) throws InterruptedException {
    if (downstreamDown) { Thread.sleep(90_000); }    // ← 절대 하지 마세요
    process(event);
}
```

**결과**
```
INFO  14310 --- [pListener-0-C-1] c.e.o.step10.Practice$SleepDemo          : 다운스트림 장애 감지 — 90초 대기 시작
WARN  14310 --- [pListener-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s10-sleep-1, groupId=s10-sleep] consumer poll timeout has expired. This means the time between subsequent calls to poll() was longer than the configured max.poll.interval.ms, which typically implies that the poll loop is spending too much time processing messages. You can address this either by increasing max.poll.interval.ms or by reducing the maximum size of batches returned in poll() with max.poll.records.
INFO  14310 --- [pListener-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s10-sleep-1, groupId=s10-sleep] Member consumer-s10-sleep-1-4e77a1 sending LeaveGroup request to coordinator due to consumer poll timeout has expired.
ERROR 14310 --- [pListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Consumer exception
org.apache.kafka.clients.consumer.CommitFailedException: Offset commit cannot be completed since the consumer is not part of an active group for auto partition assignment; it is likely that the consumer was kicked out of the group.
INFO  14310 --- [pListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-sleep: partitions assigned: [orders-0, orders-1, orders-2]
INFO  14310 --- [pListener-0-C-1] c.e.o.step10.Practice$SleepDemo          : 다운스트림 장애 감지 — 90초 대기 시작
```

**마지막 줄을 보세요.** 쫓겨나 → 재조인 → 커밋 실패로 오프셋이 안 올라감 → **같은 레코드를 다시 받아** → 또 90초 잠 → 또 쫓겨남. 무한 루프입니다. `CommitFailedException` 때문에 처리했던 앞부분도 재처리됩니다.

### ② `pause()` 로 90초 대기

```java
registry.getListenerContainer("inventoryListener").pause();
scheduler.schedule(() -> registry.getListenerContainer("inventoryListener").resume(),
                   90, TimeUnit.SECONDS);
```

**결과**
```
INFO  14310 --- [nio-8080-exec-2] c.e.o.step10.Practice$PauseDemo          : 다운스트림 장애 감지 — pause 90초
DEBUG 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Pausing consumer
DEBUG 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Received no records
DEBUG 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Received no records
INFO  14310 --- [   scheduling-1] c.e.o.step10.Practice$PauseDemo          : 90초 경과 — resume
DEBUG 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Resuming consumer
INFO  14310 --- [yListener-0-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-002 x3
```

**리밸런스가 한 번도 없습니다.** `Received no records` 가 반복되는 것이 열쇠입니다. pause 중에도 컨테이너는 `poll()` 을 계속 호출합니다. 파티션이 pause 되어 있으니 레코드는 0건이지만, **poll 은 호출됐으므로 `max.poll.interval.ms` 타이머가 리셋되고 하트비트도 나갑니다.**

| | `Thread.sleep(90s)` | `container.pause()` 90초 |
|---|---|---|
| poll 호출 | **없음** | 계속됨 (0건 리턴) |
| 하트비트 | 백그라운드 스레드가 보내지만 poll timeout 은 별개 | 정상 |
| `max.poll.interval.ms` | **초과 → LeaveGroup** | 매 poll 마다 리셋 |
| 리밸런스 | 발생. 다른 컨슈머까지 영향 | 없음 |
| 오프셋 커밋 | `CommitFailedException` | 정상 |
| 결과 | 같은 레코드 무한 재처리 | 90초 뒤 이어서 처리 |

> ⚠️ **함정 — 하트비트가 나간다고 안전한 게 아니다**
> Kafka 0.10.1 이후 하트비트는 **별도 백그라운드 스레드**가 보냅니다. 그래서 `Thread.sleep` 중에도 `session.timeout.ms`(45초)는 안 터집니다. "하트비트 잘 나가는데 왜 쫓겨나지?" 가 여기서 나옵니다.
> **증상**: `session.timeout.ms` 를 아무리 올려도 소용없습니다. 범인은 **`max.poll.interval.ms`** 이고, 이건 하트비트가 아니라 **poll 호출 간격**을 봅니다.
> **해결**: 대기가 필요하면 리스너 안에서 자지 말고 `pause()` 하세요. 정 처리가 오래 걸린다면 `max.poll.records` 를 줄여 배치당 시간을 줄이는 것이 `max.poll.interval.ms` 를 올리는 것보다 낫습니다. (Step 07 의 백오프 함정과 정확히 같은 구조입니다)

---

## 10-7. `RecordFilterStrategy` — 리스너에 도달하기 전에 버리기

관심 없는 레코드를 리스너 진입 전에 걸러 냅니다. `ConcurrentKafkaListenerContainerFactory` 에 붙입니다.

```java
f.setRecordFilterStrategy(record ->
        record.value().amount().compareTo(new BigDecimal("13000")) < 0);   // ★ true = 버린다
f.setAckDiscarded(true);
```

`OrderCreated.of(seq)` 의 `amount` 는 `10000 + (seq % 7) * 1000` 이므로 10000~16000 을 순환합니다. 13000 미만이면 버리므로 **7건 중 3건만** 통과합니다.

**결과**
```
INFO 14310 --- [eListener-0-C-1] c.e.o.step10.Practice$HighValue          : 고액 주문 ORD-0003 13000
INFO 14310 --- [eListener-0-C-1] c.e.o.step10.Practice$HighValue          : 고액 주문 ORD-0004 14000
INFO 14310 --- [eListener-0-C-1] c.e.o.step10.Practice$HighValue          : 고액 주문 ORD-0005 15000
INFO 14310 --- [eListener-0-C-1] c.e.o.step10.Practice$HighValue          : 고액 주문 ORD-0010 13000
```

`ORD-0006` ~ `ORD-0009` 는 리스너를 타지 않았습니다. 그런데 오프셋은? — `kcg --describe --group s10-highvalue`

```
GROUP          TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
s10-highvalue  orders  0          334             334             0
s10-highvalue  orders  1          333             333             0
s10-highvalue  orders  2          333             333             0
```

**LAG 0.** 필터로 버린 레코드까지 전부 커밋됐습니다. 당연합니다 — 버렸으니 다시 볼 이유가 없습니다.

| 설정 | 자동 커밋 모드(`RECORD`/`BATCH`) | 수동 ack 모드(`MANUAL`) |
|---|---|---|
| `ackDiscarded = false` (기본) | 커밋됨 | **버려진 레코드는 ack 되지 않음** |
| `ackDiscarded = true` | 커밋됨 | 버려진 레코드도 ack 됨 |

수동 ack 모드에서 `ackDiscarded=false` 이면, 필터에 걸린 레코드는 리스너를 안 타니 `ack.acknowledge()` 를 부를 사람이 없습니다. 오프셋이 그 자리에 멈춥니다.

> ⚠️ **함정 — 수동 ack + 필터 + `ackDiscarded=false` = 오프셋이 안 올라간다**
> `AckMode.MANUAL` 리스너에 필터를 붙이고 `ackDiscarded` 를 안 켜면, **버려진 레코드의 오프셋이 커밋되지 않습니다.** 뒤 레코드가 ack 되면 오프셋이 그걸 넘어가므로 결국 함께 커밋되지만, **연속으로 버려지는 구간이 배치의 끝에 걸리면** 그 구간의 오프셋이 커밋 안 된 채 남습니다.
> **증상**: LAG 이 0 으로 안 떨어지고 특정 값에서 멈춥니다. 재기동하면 이미 버렸던 레코드를 다시 받아 다시 버립니다. 처리는 정상인데 랙 알림만 계속 울립니다.
> **해결**: 수동 ack 를 쓰면서 필터를 붙일 거면 **`setAckDiscarded(true)` 를 반드시 켜세요.**

> ⚠️ **함정 — `filter()` 가 `true` 면 "통과"가 아니라 "버림"이다**
> 인터페이스 이름이 `RecordFilterStrategy` 이고 메서드가 `boolean filter(ConsumerRecord)` 라 **"필터를 통과하면 true"** 로 읽기 쉽습니다. 반대입니다. Javadoc 이 `Return true if the record should be discarded` 입니다.
> **증상**: 조건을 반대로 쓰면 **원하던 레코드만 정확히 버리고 나머지를 전부 처리**합니다. 에러는 없고, 건수도 그럴듯하고, 로그도 정상입니다. 리뷰에서 잡히지 않습니다.
> **해결**: 람다 옆에 `// true = discard` 주석을 반드시 다세요. 그리고 통과 건수를 로그로 남겨 기대치와 대조하세요.

### 필터는 대역폭을 절약하지 않는다

가장 오해가 많은 지점입니다. 이 필터는 **컨슈머 쪽 자바 코드**입니다. 브로커는 필터의 존재를 모릅니다. 레코드는 전부 네트워크로 오고, 전부 역직렬화된 뒤, 그제서야 버려집니다. Micrometer 지표로 확인하면 명확합니다.

| 지표 | 필터 없음 | 필터 있음 (70% 폐기) |
|---|---:|---:|
| `kafka.consumer.fetch.manager.bytes.consumed.rate` | 4.21 MB/s | **4.21 MB/s** |
| `kafka.consumer.fetch.manager.records.consumed.rate` | 1,840 /s | **1,840 /s** |
| 리스너 호출 횟수 | 1,840 /s | 552 /s |
| CPU (역직렬화 포함) | 38% | **36%** |

네트워크도 역직렬화도 그대로입니다. 줄어든 것은 **리스너 본문 실행**뿐입니다.

> 💡 **실무 팁 — 진짜로 안 받고 싶으면 토픽을 나누세요.**
> 발행 시점에 `orders.high` / `orders.low` 로 분리하면 컨슈머는 필요한 토픽만 구독합니다. 네트워크·역직렬화·오프셋 관리 비용이 전부 사라집니다. `RecordFilterStrategy` 는 **"리스너 본문에 `if (...) return;` 을 쓰지 않기 위한 정리 도구"** 로 생각하는 것이 정확합니다.

---

## 10-8. `ConsumerAwareRebalanceListener` — 리밸런스 순간에 끼어들기

파티션이 회수되고 할당되는 순간에 코드를 끼워 넣습니다. Kafka 클라이언트의 `ConsumerRebalanceListener` 를 Spring 이 확장해 **`Consumer` 객체까지 넘겨주는** 인터페이스입니다.

| 콜백 | 호출 시점 | 여기서 할 일 |
|---|---|---|
| `onPartitionsRevokedBeforeCommit` | 회수 직전, **커밋 전** | 처리 중인 작업 마무리, 버퍼 flush |
| `onPartitionsRevokedAfterCommit` | 회수 직전, **커밋 후** | 파티션별 캐시/상태 정리 |
| `onPartitionsAssigned` | 새 파티션 할당 직후 | `consumer.seek(...)`, 캐시 워밍업, pause 재적용 |
| `onPartitionsLost` | **이미 뺏긴 뒤** 통보 | 로컬 상태만 정리. **커밋 금지** |

```java
factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {

    @Override
    public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> c, Collection<TopicPartition> parts) {
        log.info("[revoked-before-commit] {} — 버퍼 flush", parts);
        writeBuffer.flush();                       // 아직 이 파티션의 주인이다
    }

    @Override
    public void onPartitionsRevokedAfterCommit(Consumer<?, ?> c, Collection<TopicPartition> parts) {
        log.info("[revoked-after-commit] {} — 파티션 캐시 삭제", parts);
        parts.forEach(partitionCache::remove);
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> c, Collection<TopicPartition> parts) {
        parts.forEach(tp -> log.info("[assigned] {} position={}", tp, c.position(tp)));
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> c, Collection<TopicPartition> parts) {
        log.error("[LOST] {} — 커밋하지 않고 로컬 상태만 버립니다", parts);
        parts.forEach(partitionCache::remove);
        // c.commitSync();   ← 절대 금지
    }
});
```

두 번째 인스턴스를 띄워 리밸런스를 유발합니다.

**결과** (기존 인스턴스 쪽 로그)
```
INFO  14310 --- [yListener-0-C-1] c.e.o.step10.Practice$RebalanceLogger    : [revoked-before-commit] [orders-0, orders-1, orders-2] — 버퍼 flush
INFO  14310 --- [yListener-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s10-inventory-1, groupId=s10-inventory] Revoke previously assigned partitions orders-0, orders-1, orders-2
INFO  14310 --- [yListener-0-C-1] c.e.o.step10.Practice$RebalanceLogger    : [revoked-after-commit] [orders-0, orders-1, orders-2] — 파티션 캐시 삭제
INFO  14310 --- [yListener-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-s10-inventory-1, groupId=s10-inventory] Successfully joined group with generation Generation{generationId=2, memberId='consumer-s10-inventory-1-a3f8c2', protocol='range'}
INFO  14310 --- [yListener-0-C-1] c.e.o.step10.Practice$RebalanceLogger    : [assigned] orders-0 position=1204
INFO  14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: partitions assigned: [orders-0, orders-1]
```

`revoked-before-commit` → `Revoke previously assigned partitions`(여기서 커밋) → `revoked-after-commit` 순서를 확인하세요. **커밋을 사이에 두고 두 콜백이 나뉜다**는 것이 이 인터페이스의 존재 이유입니다.

> ⚠️ **함정 — `onPartitionsLost` 에서 커밋하면 남의 진행을 되돌린다**
> `onPartitionsLost` 는 **이미 파티션을 뺏긴 뒤** 통보받는 콜백입니다. `max.poll.interval.ms` 초과나 세션 만료로 쫓겨났을 때 호출되고, 그 시점에 **다른 컨슈머가 이미 그 파티션을 받아 처리 중**일 수 있습니다. 여기서 `commitSync()` 를 하면 낡은 오프셋을 덮어씁니다.
> **증상**: 대개 `CommitFailedException` 으로 실패하지만(제너레이션이 달라서), 타이밍이 맞으면 **성공합니다.** 그러면 새 컨슈머가 이미 처리한 구간을 오프셋이 되돌려 **대량 중복 처리**가 발생합니다. 로그는 깨끗합니다.
> **해결**: `onPartitionsLost` 에서는 **로컬 상태만 정리**하세요. 커밋·seek·외부 호출 모두 금지입니다.
> 더 위험한 것은 **Kafka 클라이언트의 기본 구현**입니다. `ConsumerRebalanceListener.onPartitionsLost` 의 default 구현은 **`onPartitionsRevoked` 를 그대로 호출**합니다. `ConsumerRebalanceListener` 를 직접 구현하면서 `onPartitionsRevoked` 에 커밋 로직을 넣었다면, lost 상황에 그 커밋이 자동으로 실행됩니다. Spring 의 `ConsumerAwareRebalanceListener` 는 이 default 를 **"아무것도 안 함"으로 덮어놓았습니다.** 반드시 Spring 쪽 인터페이스를 쓰세요.

> 💡 리밸런스 프로토콜 자체(eager vs cooperative-sticky, 제너레이션, 조인 그룹 절차)는 [Kafka 코스 Step 05](../../kafka/step-05-consumer/) 를 참고하세요. 이 스텝은 **Spring 이 그 위에 얹어 준 콜백**만 다룹니다.

---

## 10-9. seek — 읽기 위치를 옮긴다

`ConsumerSeekAware` 를 구현하면 리스너가 seek 콜백을 손에 넣습니다. 콜백은 `registerSeekCallback`(컨슈머 스레드마다 1회)과 `onPartitionsAssigned`(파티션 할당 시)로 전달되고, `onIdleContainer` 에서도 받을 수 있습니다. `ConsumerSeekCallback` 이 제공하는 이동 수단은 다섯 가지입니다.

| 메서드 | 의미 |
|---|---|
| `seek(topic, partition, offset)` | 절대 오프셋 |
| `seekToBeginning(topic, partition)` | 로그 시작(retention 이 허용하는 가장 오래된 지점) |
| `seekToEnd(topic, partition)` | 로그 끝. 이후 들어오는 것만 |
| `seekRelative(topic, partition, offset, toCurrent)` | 상대 이동. `toCurrent=false` 면 begin/end 기준 |
| `seekToTimestamp(topic, partition, timestamp)` | **특정 시각 이후 첫 레코드** |

`seekToTimestamp` 가 운영에서 압도적으로 자주 쓰입니다. "어제 14시 배포 이후 것부터 다시 처리해 주세요" 가 그대로 코드가 됩니다.

```java
@Component
public class ReprocessTool extends AbstractConsumerSeekAware {
    public void reprocessFrom(Instant from) {
        getSeekCallbacks().forEach((tp, cb) ->
                cb.seekToTimestamp(tp.topic(), tp.partition(), from.toEpochMilli()));
    }
}
```

`AbstractConsumerSeekAware` 는 **파티션별 콜백을 맵으로 관리**해 주는 기반 클래스입니다. 직접 구현하면서 `registerSeekCallback` 이 넘겨주는 콜백을 필드 하나에 담으면, `concurrency=3` 에서 마지막 것만 남아 **파티션 3개 중 1개에만 seek 이 걸립니다.** 반드시 이걸 상속하세요.

```bash
curl -XPOST 'localhost:8080/admin/reprocess?from=2025-01-01T00:10:00Z'
```

**결과**
```
INFO 14310 --- [nio-8080-exec-4] c.e.o.step10.Practice$ReprocessTool      : 2025-01-01T00:10:00Z 이후로 seek 요청. 대상 파티션 [orders-0, orders-1, orders-2]
INFO 14310 --- [yListener-0-C-1] o.a.k.c.c.internals.SubscriptionState     : [Consumer clientId=consumer-s10-inventory-1, groupId=s10-inventory] Seeking to offset 10 for partition orders-0
INFO 14310 --- [yListener-0-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-002 x1        ← ORD-0010 부터 다시
INFO 14310 --- [yListener-0-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-003 x2
```

`OrderCreated.of(seq)` 의 `createdAt` 이 `2025-01-01T00:00:00Z + seq분` 이므로, `00:10:00Z` 는 정확히 `ORD-0010` 입니다.

> ⚠️ **함정 — `seekToTimestamp` 는 레코드 타임스탬프를 본다. 페이로드의 시각이 아니다**
> 브로커가 인덱싱하는 것은 `ConsumerRecord.timestamp()` 입니다. 이 값은 토픽 설정 `message.timestamp.type` 에 따라 **`CreateTime`(프로듀서가 찍은 시각, 기본값)** 이거나 **`LogAppendTime`(브로커 수신 시각)** 입니다. 여러분이 JSON 페이로드에 넣은 `createdAt` 필드와는 **아무 관련이 없습니다.**
> **증상**: 과거 이벤트를 재발행(백필)한 토픽에 `seekToTimestamp` 를 걸면, 레코드 타임스탬프는 **재발행한 오늘**이므로 원하는 구간을 못 찾고 전부 걸리거나 하나도 안 걸립니다.
> **해결**: 재발행 시 `ProducerRecord(topic, partition, timestamp, key, value)` 생성자로 **원본 시각을 명시**하거나, 타임스탬프 기반 재처리를 포기하고 오프셋으로 지정하세요.

> ⚠️ **함정 — `onPartitionsAssigned` 에서 `seekToBeginning` 을 부르면 무한 재처리다**
> ```java
> @Override
> public void onPartitionsAssigned(Map<TopicPartition, Long> a, ConsumerSeekCallback cb) {
>     a.keySet().forEach(tp -> cb.seekToBeginning(tp.topic(), tp.partition()));   // 위험
> }
> ```
> 이 콜백은 기동 시에만이 아니라 **리밸런스 때마다** 호출됩니다. 스케일아웃, 배포, 짧은 네트워크 단절 — 전부 리밸런스입니다. 그때마다 처음부터 다시 읽습니다.
> **증상**: 평소엔 멀쩡하다가 배포할 때마다 컨슈머 랙이 수십만으로 튀고 다운스트림에 중복 요청이 쏟아집니다. 원인이 배포라는 것까지는 알아내도 코드에서 이 세 줄을 찾기가 어렵습니다.
> **해결**: 되감기는 **명시적 운영 명령**(REST/Actuator)으로만 하세요. 굳이 콜백에서 해야 한다면 `AtomicBoolean` 으로 **최초 1회만** 걸리게 막습니다.

---

## 10-10. 컨테이너 이벤트 — 컨테이너가 알려 주는 것들

컨테이너는 상태 변화를 **Spring 애플리케이션 이벤트**로 발행합니다. `@EventListener` 로 받으면 됩니다.

| 이벤트 | 언제 |
|---|---|
| `ConsumerStartingEvent` / `ConsumerStartedEvent` | 컨슈머 스레드 시작 |
| `ConsumerFailedToStartEvent` | `ConsumerStartingEvent` 후 정해진 시간 내에 시작 못 함 |
| `ListenerContainerIdleEvent` / `...NoLongerIdleEvent` | `idleEventInterval` 동안 레코드 0건 / 다시 들어옴 |
| `ListenerContainerPartitionIdleEvent` | 특정 파티션만 유휴 (`idlePartitionEventInterval`) |
| `NonResponsiveConsumerEvent` | `poll()` 이 돌아오지 않음 (`monitorInterval`, `noPollThreshold`) |
| `ConsumerPausedEvent` / `ConsumerResumedEvent` | 실제로 pause/resume 됨 |
| `ConsumerStoppedEvent` / `ContainerStoppedEvent` | 컨슈머 종료(`Reason` 포함) / 컨테이너 전체 종료 |

`ConsumerStoppedEvent.getReason()` 은 `NORMAL`, `ERROR`, `FENCED`, `AUTH`, `NO_OFFSET` 중 하나입니다. **`NORMAL` 이 아닌 종료를 알림으로 보내면** "컨슈머가 조용히 죽어 있었다"를 다음 날이 아니라 즉시 압니다.

### 유휴 감지 — "메시지가 안 들어온다"를 잡는다

랙 모니터링은 **메시지가 들어올 때** 유효합니다. 업스트림이 아예 죽어 발행이 끊기면 랙은 0 이고 모든 지표가 초록색입니다. 그 침묵을 감지하는 것이 유휴 이벤트입니다.

```java
factory.getContainerProperties().setIdleEventInterval(30_000L);    // 30초
```

```java
@EventListener
public void onIdle(ListenerContainerIdleEvent event) {
    log.warn("유휴 감지 listenerId={} idle={}s partitions={}",
            event.getListenerId(), event.getIdleTime() / 1000, event.getTopicPartitions());
    if (event.getIdleTime() > 300_000L) {
        alarm.send("orders 유입 5분간 없음 — 업스트림 확인 필요");
    }
}

@EventListener
public void onNoLongerIdle(ListenerContainerNoLongerIdleEvent event) {
    log.info("유휴 해제 listenerId={} 마지막 유휴 {}s", event.getListenerId(), event.getIdleTime() / 1000);
}
```

**결과**
```
WARN 14310 --- [yListener-0-C-1] c.e.o.step10.Practice$IdleWatcher        : 유휴 감지 listenerId=inventoryListener-0 idle=30s partitions=[orders-0]
WARN 14310 --- [yListener-1-C-1] c.e.o.step10.Practice$IdleWatcher        : 유휴 감지 listenerId=inventoryListener-1 idle=30s partitions=[orders-1]
WARN 14310 --- [yListener-2-C-1] c.e.o.step10.Practice$IdleWatcher        : 유휴 감지 listenerId=inventoryListener-2 idle=30s partitions=[orders-2]
INFO 14310 --- [yListener-0-C-1] c.e.o.step10.Practice$IdleWatcher        : 유휴 해제 listenerId=inventoryListener-0 마지막 유휴 63s
```

`listenerId` 가 `inventoryListener-0`, `-1`, `-2` 로 **자식 컨테이너 단위**인 것에 주목하세요. `concurrency=3` 이면 이벤트도 3개씩 옵니다. 알림을 그대로 보내면 같은 장애에 3배로 울립니다.

> ⚠️ **함정 — 유휴 이벤트 리스너는 컨슈머 스레드 위에서 돈다**
> `@EventListener` 메서드는 이벤트를 발행한 스레드, 즉 **컨슈머 스레드**에서 동기 실행됩니다. 여기서 알림 API 를 동기 호출했는데 그게 5초 걸리면, **그 5초 동안 `poll()` 이 멈춥니다.** 알림 서버가 죽어 타임아웃 30초가 나면 `max.poll.interval.ms` 를 향해 달려갑니다.
> **증상**: "알림 시스템 장애가 났더니 카프카 컨슈머까지 리밸런스 폭풍이 났다."
> **해결**: 이벤트 핸들러에 `@Async` 를 붙이거나 큐에 넣고 리턴하세요. **컨슈머 스레드에서는 아무것도 오래 하지 않는다**가 이 스텝 전체를 관통하는 원칙입니다.

> 💡 **실무 팁 — `idleEventInterval` 은 "정상 유입 간격"의 3~5배로 잡으세요.**
> 평소 5초에 한 번 들어오는 토픽에 30초를 걸면 적당합니다. 너무 짧으면 새벽마다 알림이 울리고, 너무 길면 장애 감지가 늦습니다. 그리고 **트래픽이 원래 없는 시간대**(야간 배치 토픽)는 알림 조건에서 제외해야 합니다.

---

## 10-11. 우아한 종료

`stop()` 은 논블로킹입니다. 실제로 멈춘 시점을 알고 싶으면 콜백 버전을 씁니다.

```java
long t0 = System.currentTimeMillis();
container.stop(() -> log.info("컨테이너 정지 완료 t={}ms", System.currentTimeMillis() - t0));
log.info("stop() 리턴 t={}ms isRunning={}", System.currentTimeMillis() - t0, container.isRunning());
```

**결과**
```
INFO 14310 --- [nio-8080-exec-6] c.e.o.step10.Practice$Shutdown           : stop() 리턴 t=2ms isRunning=false
INFO 14310 --- [yListener-1-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-002 x3
INFO 14310 --- [yListener-1-C-1] c.e.o.step10.Practice$Inventory          : 재고 차감 SKU-001 x5
INFO 14310 --- [yListener-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: Consumer stopped
INFO 14310 --- [yListener-2-C-1] c.e.o.step10.Practice$Shutdown           : 컨테이너 정지 완료 t=1712ms
```

`isRunning()` 은 **2ms 만에 false** 가 되지만 실제 종료는 **1,712ms** 뒤입니다. pause 와 같은 구조입니다 — 현재 배치를 다 처리해야 끝납니다.

| 설정 / 메서드 | 기본값 | 의미 |
|---|---|---|
| `ContainerProperties.setShutdownTimeout(long)` | 10000ms | 컨슈머 스레드 종료를 기다리는 상한 |
| `ContainerProperties.setStopImmediate(boolean)` | false | true 면 **현재 레코드까지만** 처리하고 배치 나머지는 버림 |
| `stop()` | — | 현재 배치를 끝까지 처리 |
| `stop(Runnable)` | — | 위와 같되 완료 시 콜백 |

`stopImmediate=true`(`factory.getContainerProperties().setStopImmediate(true)`) 로 두면 남은 배치를 처리하지 않고 멈춥니다. **처리하지 않은 레코드는 커밋되지 않으므로 다음 기동 때 다시 옵니다.** 유실은 없지만 중복 가능성은 커집니다.

**결과**
```
INFO 14310 --- [nio-8080-exec-6] c.e.o.step10.Practice$Shutdown           : stop() 리턴 t=1ms
INFO 14310 --- [yListener-1-C-1] o.s.k.l.KafkaMessageListenerContainer    : s10-inventory: Consumer stopped
INFO 14310 --- [yListener-2-C-1] c.e.o.step10.Practice$Shutdown           : 컨테이너 정지 완료 t=38ms
```

**1,712ms → 38ms.** 대신 그 배치의 나머지 417건은 재처리 대상이 됩니다. `stopImmediate` 의 트레이드오프는 "유실 vs 중복"이 아니라 **"종료 시간 vs 중복"** 입니다.

> ⚠️ **함정 — `shutdownTimeout` 을 넘기면 커밋 없이 스레드가 버려진다**
> 리스너 하나가 오래 걸려 `shutdownTimeout`(기본 10초) 안에 못 끝나면, 컨테이너는 **기다리기를 포기**하고 종료 절차를 마칩니다. 그 시점에 처리 중이던 레코드의 오프셋은 커밋되지 않습니다.
> **증상**: 배포할 때마다 특정 주문이 두 번 처리됩니다. 재고가 두 번 차감되고, 알림이 두 번 갑니다. 배포 로그에는 `Consumer stopped` 만 남아 정상 종료로 보입니다.
> **해결**: ① 리스너 한 건의 처리 시간을 `shutdownTimeout` 보다 훨씬 짧게 유지하고, ② 그게 불가능하면 `shutdownTimeout` 을 올리되 **오케스트레이터의 `terminationGracePeriodSeconds` 보다 작게** 맞추세요. 쿠버네티스가 30초 뒤 `SIGKILL` 을 보내면 `shutdownTimeout` 60초는 아무 의미가 없습니다. ③ 근본 해법은 **멱등 컨슈머**입니다(Step 13).

> 💡 **실무 팁 — 배포 시 종료 순서**
> `SIGTERM` → 컨테이너 `stop()`(phase 가 크므로 가장 먼저) → 현재 배치 처리 → 커밋 → `LeaveGroup` → 나머지 빈 종료. `LeaveGroup` 을 보내므로 남은 인스턴스의 리밸런스가 **세션 타임아웃 45초를 기다리지 않고 즉시** 일어납니다. 이것만으로도 배포 중 랙 스파이크가 크게 줄어듭니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 컨테이너 = `SmartLifecycle` | `phase` 기본 `Integer.MAX_VALUE - 100` → **가장 늦게 시작, 가장 먼저 종료** |
| `start()` / `isRunning()` | **논블로킹.** `isRunning=true` ≠ 파티션 할당 완료 |
| 스레드 이름 | `%15.15t` 가 **앞을 자름**. `inventoryListener-0-C-1` → `[yListener-0-C-1]` |
| id 미지정 | `...KafkaListenerEndpointContainer#0`. **리스너 추가 시 번호가 밀려 엉뚱한 컨테이너를 리턴** |
| `id` + `groupId` 누락 | `idIsGroup=true` 기본값 → **id 가 컨슈머 그룹이 됨** |
| `autoStartup="false"` | 문자열(플레이스홀더용). 시작은 레지스트리로 |
| `stop()` vs `pause()` | `stop` 은 **그룹 탈퇴 → 리밸런스**. 잠깐 멈출 땐 `pause` |
| `pause()` 지연 | 요청 즉시 `isPauseRequested=true`, 실제 정지는 **1,843ms 뒤**(`max-poll-records=500`) |
| pause vs `Thread.sleep` | pause 는 **빈 poll 계속** → 안전 / sleep 은 poll 정지 → **LeaveGroup 무한 루프** |
| `pausePartition` | 리밸런스 후 **초기화됨**. `onPartitionsAssigned` 에서 재적용 |
| `RecordFilterStrategy` | `filter()==true` 는 **버림**. 오프셋은 커밋됨. 수동 ack 면 **`ackDiscarded=true` 필수** |
| 필터의 비용 | 브로커에서 안 걸림. **네트워크·역직렬화 비용 그대로**(4.21MB/s 동일) |
| 리밸런스 콜백 | `RevokedBeforeCommit` → 커밋 → `RevokedAfterCommit` → `Assigned` |
| `onPartitionsLost` | **커밋·seek 금지.** Kafka 기본 구현은 `onPartitionsRevoked` 를 호출하니 Spring 인터페이스를 쓸 것 |
| seek | `seekToTimestamp` 는 **레코드 타임스탬프** 기준. `onPartitionsAssigned` 에서 `seekToBeginning` 은 **무한 재처리** |
| `ListenerContainerIdleEvent` | 랙이 못 잡는 **"유입 중단"** 을 감지. 핸들러는 **컨슈머 스레드에서 동기 실행** |
| 종료 | `stop(Runnable)` 2ms 리턴 / 1,712ms 완료. `stopImmediate` 는 38ms 지만 **미처리 배치 재처리** |
| `shutdownTimeout` | 초과 시 **커밋 없이 포기.** 오케스트레이터 grace period 보다 짧게 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`. **반드시 직접 실행해 로그를 확인**하세요.

1. `autoStartup="false"` 리스너를 만들고 REST 로 start/stop 하기. `start()` 직후 `isRunning()` 과 `partitions assigned` 로그의 **시간 차**를 밀리초로 측정할 것
2. 다운스트림 연속 실패 5회에 `pause()`, 회복 확인 시 `resume()` 하는 백프레셔를 구현하고, 정지 요청 → 실제 정지까지의 지연을 `isPauseRequested()`/`isContainerPaused()` 로 실측하기
3. `amount < 12000` 인 주문을 `RecordFilterStrategy` 로 버리고, **`ackDiscarded` 를 켠 경우와 끈 경우의 `kcg --describe` LAG 을 표로 대조**하기 (`AckMode.MANUAL` 사용)
4. `ConsumerAwareRebalanceListener` 로 네 콜백을 모두 로깅하고, 두 번째 인스턴스를 띄워 `revoked-before-commit → revoked-after-commit → assigned` 순서를 확인하기. `onPartitionsLost` 에는 **커밋하지 않는 이유**를 주석으로 남길 것
5. `idleEventInterval=20000` 으로 `ListenerContainerIdleEvent` 를 받아, 60초 이상 유휴면 경고를 남기고 `ListenerContainerNoLongerIdleEvent` 로 해제를 기록하기. 핸들러는 `@Async` 로 뺄 것
6. `AbstractConsumerSeekAware` 를 상속해 `POST /admin/reprocess?from=...` 로 **특정 타임스탬프부터 재처리**하는 도구를 만들고, `2025-01-01T00:10:00Z` 로 호출해 `ORD-0010` 부터 다시 오는 것을 확인하기
7. `stop(Runnable)` 로 종료 완료 시각을 재고, `stopImmediate=true` 로 바꿔 같은 실험을 반복해 **소요 시간과 재기동 후 중복 처리 건수**를 비교하기

---

## 다음 단계

컨테이너를 손으로 켜고, 멈추고, 되감고, 침묵을 감지하는 법까지 익혔습니다. 그런데 이 스텝의 실습은 전부 **눈으로 로그를 보며** 확인했습니다. "pause 요청 후 1,843ms 만에 멈춘다" 를 자동으로 검증할 수는 없을까요?

다음 스텝은 테스트입니다. `@EmbeddedKafka` 와 Testcontainers 두 방식을 비교하고, 이 스텝에서 계속 마주친 **"논블로킹이라 순서가 보장되지 않는다"** 는 성질이 테스트에서 어떻게 간헐 실패로 나타나는지, `Thread.sleep` 대신 `awaitility` 와 `ContainerTestUtils.waitForAssignment` 를 어떻게 쓰는지 다룹니다.

→ [Step 11 — 테스트](../step-11-testing/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 를 보조 프로필로 하나씩 켜 가며 10-1 ~ 10-11 의 로그를 재현하되, **특히 `step10-pause` 프로필에서 `curl` 로 pause 를 건 뒤 `isContainerPaused()` 가 true 가 되기까지의 밀리초를 직접 측정**하세요. 이 스텝의 거의 모든 예제가 HTTP 로 조작하므로, 앱을 켠 채 터미널 창 두 개(`curl` 용, `kcg --describe` 용)를 함께 열어 두는 것이 좋습니다. 그다음 `Exercise.java` 의 7문제를 풀고 `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.order.step10` 패키지에 둡니다.

### Practice.java

본문 10-1 ~ 10-11 의 모든 예제를 절 번호 주석과 함께 nested static class 로 담은 실행 파일입니다.

- 예제끼리 컨슈머 그룹과 팩토리가 겹치므로 **보조 프로필로 하나씩만 켭니다.** `step10` 만 켜면 `ContainerAdminController` 와 `RegistryDump` 만 뜨고 리스너는 하나도 등록되지 않습니다. 파일 상단 주석에 프로필 6개와 각각이 재현하는 절, 그리고 함께 쓸 `curl` 명령이 정리돼 있습니다.
- `[10-2] RegistryDump` 는 `ApplicationRunner` 로 `getListenerContainerIds()` 를 통째로 찍습니다. **id 를 준 리스너와 안 준 리스너를 한 프로필에 섞어 두었으므로**, 출력에서 `inventoryListener` 와 `org.springframework.kafka.KafkaListenerEndpointContainer#0` 이 함께 보입니다. 로그의 스레드 이름과 대조하세요.
- `[10-4] PauseTimingController` 가 이 파일의 핵심입니다. `POST /step10/pause` 가 `pause()` 호출 시각을 기록하고 `isContainerPaused()` 가 true 가 될 때까지 50ms 간격으로 폴링해 **실제 정지까지의 지연을 밀리초로 리턴**합니다. `max-poll-records` 를 500 → 100 → 10 으로 바꿔 가며 세 번 측정해 본문의 표를 직접 채우세요.
- `[10-6] SleepDemo` 는 **일부러 컨슈머를 죽이는 코드**입니다. `step10-sleep` 프로필은 `max.poll.interval.ms=60000` 짜리 전용 `ConsumerFactory` 를 씁니다(기본 5분으로는 재현에 너무 오래 걸립니다). 켜 두면 60초마다 `LeaveGroup` → 재조인 → 같은 레코드 재처리가 무한 반복되므로, **로그 세 사이클만 확인하고 반드시 앱을 내리세요.**
- `[10-7] FilterConfig` 는 `filter()` 람다 바로 옆에 `// true = discard` 주석을 달아 두었습니다. 이 주석을 지우지 말고, 조건을 반대로 뒤집어 한 번 실행해 보세요. **에러 없이 정확히 반대로 동작**하는 것이 이 절의 요점입니다.
- `[10-9] ReprocessTool` 은 `AbstractConsumerSeekAware` 를 상속합니다. `getSeekCallbacks()` 로 **파티션별 콜백**을 순회하는데, 이걸 `registerSeekCallback` 이 넘겨준 단일 콜백으로 대체하면 `concurrency=3` 환경에서 **엉뚱한 컨슈머에 seek 이 걸립니다.** 두 방식을 나란히 두었으니 로그로 비교하세요.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일은 되도록 뼈대를 남겨 두었습니다.

- **문제 1·2·7** 은 컨테이너 **제어**(start/stop/pause/shutdown), **문제 3** 은 **필터**, **문제 4·6** 은 **리밸런스와 seek**, **문제 5** 는 **이벤트**입니다. 1번의 `ExerciseController` 를 먼저 완성해야 2·7 번을 호출할 수 있으니 순서대로 푸세요.
- 문제 3 은 코드만으로 끝나지 않습니다. `// 관측 기록:` 주석의 표를 `ackDiscarded=true` / `false` 두 번 실행해 **`kcg --describe --group s10-ex-filter` 의 LAG 값으로** 채워야 합니다. 두 값이 같게 나왔다면 `AckMode.MANUAL` 이 실제로 걸렸는지 다시 확인하세요.
- ⚠️ **문제 4 는 앱을 두 개 띄우는 문제입니다.** 같은 프로필로 `--server.port=8081` 을 줘 두 번째 인스턴스를 올려 리밸런스를 유발합니다. 포트를 안 바꾸면 `Port 8080 was already in use` 로 두 번째가 아예 안 뜹니다.
- 각 문제 끝의 `// 확인:` 주석에 **기대 로그 한 줄**이 적혀 있습니다. 그 줄이 콘솔에 안 나오면 답이 틀린 것입니다. 예를 들어 문제 6 의 확인 줄은 `Seeking to offset 10 for partition orders-0` 입니다.
- 문제 5 의 `@Async` 는 `@EnableAsync` 가 있어야 동작합니다. 뼈대에 주석으로 자리를 남겨 두었으니 빠뜨리지 마세요. `@Async` 없이도 로그는 똑같이 나오므로, **동작하는지 확인하려면 핸들러의 스레드 이름을 함께 찍어야 합니다.**

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `start()` 후 `isRunning()` 이 즉시 true 인데도 `partitions assigned` 는 **340ms 뒤**에 나오는 것을 보여 줍니다. `ConsumerStartedEvent` 와 `ConsumerAwareRebalanceListener.onPartitionsAssigned` 중 **어느 쪽을 "준비 완료"의 기준으로 삼아야 하는지**를 주석으로 설명합니다. 정답은 후자입니다.
- **정답 2** 의 포인트는 `resume()` 을 **컨슈머 스레드가 아닌 곳에서 호출**해야 한다는 것입니다. pause 중에는 리스너가 호출되지 않으므로, 리스너 안에서 회복을 확인하려는 코드는 영원히 실행되지 않습니다. `TaskScheduler` 로 빼는 이유가 여기 있습니다. 흔한 오답(리스너 안에서 resume)을 함께 실어 두었습니다.
- **정답 3** 은 `ackDiscarded=false` 일 때 LAG 이 **0 이 아니라 4 에서 멈추는** 실측표를 담고 있습니다. 배치 끝에 연속으로 버려진 레코드가 몇 개 남았느냐에 따라 값이 달라지므로, 여러분의 숫자가 4 가 아니어도 됩니다. **0 이 아니라는 사실**만 같으면 맞습니다.
- **정답 4** 는 `onPartitionsLost` 를 비워 두는 것이 정답입니다. 주석에 Kafka 클라이언트의 `ConsumerRebalanceListener.onPartitionsLost` **default 구현이 `onPartitionsRevoked` 를 호출한다**는 소스 레벨 사실과, Spring 의 `ConsumerAwareRebalanceListener` 가 그걸 어떻게 덮었는지를 적어 두었습니다.
- **정답 5** 는 `@Async` 핸들러의 스레드 이름이 `[         task-1]` 로, `@Async` 없을 때의 `[yListener-0-C-1]` 과 다르다는 것을 로그로 보여 줍니다. **이 한 줄의 차이가 알림 서버 장애를 카프카 장애로 번지게 하느냐 마느냐**를 가릅니다.
- **정답 6** 은 `getSeekCallbacks()`(파티션별 맵)와 `registerSeekCallback`(단일 콜백) 두 구현을 나란히 두고, `concurrency=3` 에서 후자를 쓰면 **마지막에 등록된 콜백 하나만 남아 파티션 3개 중 1개에만 seek 이 걸리는** 로그를 대비시킵니다.
- **정답 7** 은 `stop()` 1,712ms / `stopImmediate` 38ms 라는 시간 차와, 재기동 후 중복 처리 건수 **0건 vs 417건** 을 실측으로 대조합니다. 결론은 "빠른 종료를 택했다면 멱등성은 선택이 아니라 필수"이며, 그 구현은 Step 13 으로 넘깁니다.

```java file="./Solution.java"
```
