# Step 12 — 관측성과 운영

> **학습 목표**
> - Kafka 애플리케이션에서 반드시 봐야 할 4대 신호(컨슈머 랙 / 처리 지연 / 에러·DLT / 리밸런스)를 구분해 정의한다
> - Boot 자동 설정이 등록하는 `MicrometerConsumerListener` / `MicrometerProducerListener` 가 노출하는 지표를 `/actuator/metrics` 로 직접 확인한다
> - **클라이언트가 보고하는 랙이 컨슈머와 함께 사라지는 것**을 재현하고, `AdminClient` 기반 랙 게이지를 직접 만들어 대체한다
> - `observation-enabled` 를 프로듀서·컨슈머 양쪽에 켜서 `traceparent` 헤더로 trace 가 이어지는 것을 로그와 헤더 덤프로 확인한다
> - 모든 리스너 로그에 `topic-partition@offset` 을 남기는 MDC 규약을 적용하고 before/after 를 비교한다
> - 브로커는 살아 있는데 리스너 컨테이너만 죽은 상태를 재현하고, 이를 잡아내는 커스텀 `HealthIndicator` 를 만든다
>
> **선행 스텝**: [Step 11 — 테스트](../step-11-testing/)
> **예상 소요**: 90분

---

## 12-0. 실습 준비

`build.gradle` 에는 이미 관측성 의존성이 들어 있습니다([실습 프로젝트 셋업](../project/) P-4).

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'
```

이 스텝에서만 `application.yml` 의 `management` 블록을 넓힙니다.

```yaml
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  endpoint.health:
    show-details: always            # 커스텀 HealthIndicator 의 details 를 보려면 필수
    group.readiness.include: kafkaListeners   # 12-10 에서 만듭니다
  metrics.tags.application: order-service
  tracing.sampling.probability: 1.0 # 실습이므로 전부 샘플링. 운영은 0.01~0.1

logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

컨슈머 그룹은 `s12-inventory`, `s12-notification` 을 씁니다. 실행 전 오프셋을 리셋하세요.

```bash
kcg --group s12-inventory --topic orders --reset-offsets --to-earliest --execute
```

> 💡 브로커 자체의 JMX 지표(`UnderReplicatedPartitions`, `RequestHandlerAvgIdlePercent` 등)와 브로커 모니터링 구성은
> [Kafka 코스 Step 14](../../kafka/step-14-operations/) 에서 다룹니다. 이 스텝은 **애플리케이션이 스스로 내보내는 신호**만 봅니다.

---

## 12-1. 무엇을 봐야 하는가 — 4대 신호

Kafka 애플리케이션에서 대시보드에 올릴 지표는 사실 많지 않습니다. **네 가지면 대부분의 장애를 잡습니다.**

| 신호 | 무엇을 뜻하나 | 나빠지면 생기는 일 | 대표 지표 |
|---|---|---|---|
| **컨슈머 랙** | 브로커에 쌓인 마지막 오프셋과 그룹이 커밋한 오프셋의 차이 | 처리가 생산을 못 따라감. 방치하면 `retention` 에 걸려 **메시지가 소비되기 전에 삭제** | `kafka.consumer.fetch.manager.records.lag.max` |
| **처리 지연(end-to-end)** | 프로듀서가 보낸 시각 ~ 컨슈머가 처리 완료한 시각 | 랙이 0 이어도 **한 건 처리에 5초**면 사용자 체감은 장애 | `spring.kafka.listener` 타이머 |
| **에러·DLT 유입률** | 리스너 예외 발생 건수, `orders.DLT` 로 흘러간 건수 | 조용히 버려지는 주문. **에러 로그가 없어도 DLT 는 쌓임** | DLT 토픽의 `records.consumed.rate` |
| **리밸런스 빈도** | 그룹 멤버십이 재구성된 횟수 | 리밸런스 중에는 **아무도 소비하지 않음**. 잦으면 처리량이 톱니처럼 요동 | `kafka.consumer.coordinator.rebalance.total` |

이 넷은 서로를 설명합니다. **랙이 튀면 → 처리 지연을 보고 → 에러율을 보고 → 리밸런스를 봅니다.**
리밸런스가 잦아서 랙이 튀는 경우와, 처리가 느려서 랙이 튀는 경우는 대응이 완전히 다릅니다.

```
  랙 급증
   ├─ 처리 지연 정상 + 리밸런스 급증  → 컨슈머가 계속 쫓겨남 (max.poll.interval.ms 초과, Step 07)
   ├─ 처리 지연 급증 + 에러율 0       → 다운스트림(DB·외부 API)이 느려짐
   ├─ 처리 지연 급증 + 에러율 급증    → 블로킹 재시도가 파티션을 잡고 있음 (Step 07)
   └─ 전부 정상인데 랙만 큼           → 생산량이 늘었음. concurrency/파티션 증설 검토
```

---

## 12-2. Micrometer 연동 — 자동으로 나오는 것들

Spring Boot 는 `KafkaAutoConfiguration` 에서 `MicrometerConsumerListener` 와 `MicrometerProducerListener` 를
`DefaultKafkaConsumerFactory` / `DefaultKafkaProducerFactory` 에 자동으로 붙입니다.
**여러분이 아무것도 안 해도** Kafka 클라이언트가 내부적으로 갖고 있는 JMX 지표가 Micrometer 로 넘어옵니다.

```bash
curl -s localhost:8080/actuator/metrics | jq -r '.names[]' | grep '^kafka'
```

**결과** (일부)
```
kafka.consumer.coordinator.assigned.partitions
kafka.consumer.coordinator.rebalance.total
kafka.consumer.fetch.manager.fetch.latency.avg
kafka.consumer.fetch.manager.records.consumed.rate
kafka.consumer.fetch.manager.records.lag
kafka.consumer.fetch.manager.records.lag.max
kafka.producer.record.error.rate
kafka.producer.record.send.rate
kafka.producer.request.latency.avg
```

핵심만 추리면 이렇습니다.

| 지표 | 타입 | 의미 | 볼 때 주의 |
|---|---|---|---|
| `kafka.consumer.fetch.manager.records.lag.max` | Gauge | 이 컨슈머가 담당한 파티션 중 **가장 큰 랙** | 커밋 기준이 아니라 **fetch 위치 기준** |
| `kafka.consumer.fetch.manager.records.consumed.rate` | Gauge | 초당 소비 레코드 수 | 0 이면 처리 중이거나 파티션 미할당 |
| `kafka.consumer.coordinator.rebalance.total` | Counter | 이 컨슈머가 겪은 누적 리밸런스 횟수 | **재시작하면 0으로 리셋** |
| `kafka.consumer.coordinator.assigned.partitions` | Gauge | 현재 할당된 파티션 수 | **0 이면 놀고 있는 스레드**(Step 03) |
| `kafka.producer.record.send.rate` | Gauge | 초당 전송 레코드 수 | |
| `kafka.producer.record.error.rate` | Gauge | 초당 전송 실패 수 | **0 이 아니면 즉시 알람** |

한 지표를 열어 봅니다.

```bash
curl -s 'localhost:8080/actuator/metrics/kafka.consumer.fetch.manager.records.lag.max' | jq
```

**결과**
```json
{
  "name": "kafka.consumer.fetch.manager.records.lag.max",
  "baseUnit": "records",
  "measurements": [ { "statistic": "VALUE", "value": 47.0 } ],
  "availableTags": [
    { "tag": "spring.id", "values": ["consumerFactory.s12-inventory-0"] },
    { "tag": "client.id", "values": ["consumer-s12-inventory-1", "consumer-s12-inventory-2", "consumer-s12-inventory-3"] }
  ]
}
```

`measurements[0].value` 가 **47.0** 인데, `client.id` 태그에는 값이 세 개입니다.
태그를 안 지정하면 **세 컨슈머 스레드의 값이 합산(sum)** 됩니다. `?tag=client.id:consumer-s12-inventory-2` 를 붙여 하나만 보면 **31.0** 입니다.

> ⚠️ **함정 — `lag.max` 를 태그 없이 그래프로 그리면 값이 부풀려진다**
> Micrometer 의 `/actuator/metrics` 는 같은 이름의 미터를 **합산해서** 보여 줍니다.
> `concurrency: 3` 이면 컨슈머가 3개이므로 "최대 랙"이 세 개 더해진 값이 나옵니다. 위 예에서 실제 최댓값은 31 인데 화면에는 47 이 찍혔습니다.
> Prometheus 로 긁을 때는 시계열이 `client_id` 별로 분리되므로 `max by (client_id)` 로 집계하면 됩니다.
> **Actuator 화면의 숫자를 그대로 임계값으로 쓰지 마세요.**

---

## 12-3. `/actuator/prometheus` 실제 출력

`micrometer-registry-prometheus` 가 클래스패스에 있으면 `/actuator/prometheus` 가 열립니다.
지표 이름의 `.` 은 `_` 로, 태그는 라벨로 변환됩니다.

```bash
curl -s localhost:8080/actuator/prometheus | grep -E '^kafka_consumer_fetch_manager_records_lag'
```

**결과**
```
# TYPE kafka_consumer_fetch_manager_records_lag_max gauge
kafka_consumer_fetch_manager_records_lag_max{application="order-service",client_id="consumer-s12-inventory-1",kafka_version="3.6.1",spring_id="consumerFactory.s12-inventory-0",} 12.0
kafka_consumer_fetch_manager_records_lag_max{application="order-service",client_id="consumer-s12-inventory-2",kafka_version="3.6.1",spring_id="consumerFactory.s12-inventory-1",} 31.0
# TYPE kafka_consumer_fetch_manager_records_lag gauge
kafka_consumer_fetch_manager_records_lag{application="order-service",client_id="consumer-s12-inventory-2",partition="1",topic="orders",} 31.0
```

**`records.lag`(단수) 쪽에만 `topic` / `partition` 라벨이 붙습니다.** 파티션별 알람은 이쪽으로만 가능하고,
`lag.max` 는 파티션이 수백 개일 때 시계열 카디널리티를 줄이는 요약용입니다.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: order-service
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ['host.docker.internal:8080']
```

```sql
max by (topic) (kafka_consumer_fetch_manager_records_lag)      # 그룹 전체의 최대 랙
increase(kafka_consumer_coordinator_rebalance_total[5m])       # 5분간 리밸런스 증가량
rate(kafka_producer_record_error_total[1m]) > 0                # 프로듀서 전송 실패
```

---

## 12-4. ⚠️ 함정 — 클라이언트가 보고하는 랙은 "그 클라이언트가 아는 랙"이다

여기가 이 스텝에서 가장 중요한 대목입니다.

`kafka.consumer.fetch.manager.records.lag` 는 **컨슈머 객체 안의 카운터**입니다.
컨슈머가 브로커에서 fetch 응답을 받을 때 같이 오는 `logStartOffset`/`logEndOffset` 으로 계산해 갱신합니다.
즉 **그 컨슈머가 살아서 poll 하고 있을 때만 존재하는 값**입니다.

앱이 통째로 죽으면 그나마 스크레이프 "타깃 down" 으로 잡힙니다. 진짜 문제는 **앱은 살아 있는데 리스너 컨테이너만 죽은 경우**입니다.
Step 10 의 `registry.stop()` 이나, Step 07 의 `max.poll.interval.ms` 초과로 그룹에서 쫓겨난 상태를 재현합니다.

```bash
curl -X POST localhost:8080/admin/listeners/stop
```

**결과**
```
INFO 13418 --- [nio-8080-exec-3] o.s.k.l.KafkaMessageListenerContainer    : s12-inventory: Consumer stopped
INFO 13418 --- [ntainer#0-0-C-1] o.a.k.c.c.i.ConsumerCoordinator          : [Consumer clientId=consumer-s12-inventory-1, groupId=s12-inventory] Revoke previously assigned partitions orders-0
INFO 13418 --- [ntainer#0-0-C-1] o.a.k.c.m.Metrics                        : Metrics scheduler closed
```

이 상태에서 다시 긁으면 `grep -c kafka_consumer_fetch_manager_records_lag` 가 **0** 을 돌려줍니다.
**지표가 사라졌습니다.** 그동안 프로듀서는 계속 발행하므로 실제 랙은 무한히 커지는 중인데,
`max(kafka_consumer_fetch_manager_records_lag) > 1000` 같은 알람 룰은 **평가할 시계열이 없어서 발화하지 않습니다.**

```
실제 랙:   0 ──── 120 ──── 480 ──── 2,100 ──── 8,700 ──── ∞
보고된 랙: 0 ──── 120 ──── (지표 소멸) ─────────────────────
알람:      정상 ── 정상 ── 정상 ── 정상 ── 정상 ── 정상  ← 끝까지 안 울림
```

> ⚠️ **함정 — 랙이 무한대로 커지는 순간에 랙 지표가 없어진다**
> 컨슈머가 죽으면 그 컨슈머가 만들던 미터도 함께 등록 해제됩니다. **가장 위험한 순간에 관측이 끊깁니다.**
> 증상은 "대시보드의 랙 그래프가 뚝 끊기고 알람은 조용함" 입니다. 그래프가 0 으로 떨어지는 게 아니라 **선이 사라집니다.**
> 해결은 두 가지를 함께 걸어야 합니다.
> ① `absent(kafka_consumer_fetch_manager_records_lag{...})` 룰로 **지표 부재 자체를 알람**한다.
> ② **브로커에서 직접 조회하는 랙 게이지**를 별도로 만든다 → 12-5.
> ②가 정공법입니다. 컨슈머가 다 죽어도 앱이 살아 있으면 `AdminClient` 는 답을 줍니다.

---

## 12-5. 컨슈머 랙을 직접 노출하기 (핵심 실습)

`AdminClient` 로 **그룹이 커밋한 오프셋**을 가져오고, `KafkaConsumer.endOffsets` 로 **토픽의 마지막 오프셋**을 가져와 뺍니다.
이 계산은 `kafka-consumer-groups.sh --describe` 가 하는 일과 정확히 같습니다.

```java
@Component
@Profile("step12-lag")
public class ConsumerGroupLagMetrics {

    private static final String GROUP = "s12-inventory";

    private final AdminClient admin;                // KafkaAdmin.getConfigurationProperties() 로 생성
    private final Consumer<String, Object> probe;   // cf.createConsumer("lag-probe", "-probe")
    private final MultiGauge lagGauge;              // MultiGauge.builder("kafka.consumer.group.lag")...

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void refresh() throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed =
                admin.listConsumerGroupOffsets(GROUP)
                     .partitionsToOffsetAndMetadata()
                     .get(5, TimeUnit.SECONDS);

        if (committed.isEmpty()) {              // 그룹이 아직 커밋한 적 없음
            lagGauge.register(List.of(), true);
            return;
        }
        Map<TopicPartition, Long> end = probe.endOffsets(committed.keySet());

        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        long total = 0;
        for (var e : committed.entrySet()) {
            TopicPartition tp = e.getKey();
            long lag = Math.max(0, end.getOrDefault(tp, 0L) - e.getValue().offset());
            total += lag;
            rows.add(MultiGauge.Row.of(
                    Tags.of("group", GROUP, "topic", tp.topic(),
                            "partition", String.valueOf(tp.partition())),
                    lag));
        }
        lagGauge.register(rows, true);          // ★ true = 기존 행을 덮어쓴다
        log.info("group={} totalLag={} partitions={}", GROUP, total, rows.size());
    }
}
```

`@Scheduled` 를 쓰므로 애플리케이션 클래스에 `@EnableScheduling` 이 필요합니다. 전체 코드는 `Practice.java` 에 있습니다.

**결과** (10초마다)
```
INFO 13418 --- [   scheduling-1] c.e.o.s.ConsumerGroupLagMetrics          : group=s12-inventory totalLag=0 partitions=3
INFO 13418 --- [   scheduling-1] c.e.o.s.ConsumerGroupLagMetrics          : group=s12-inventory totalLag=284 partitions=3
INFO 13418 --- [   scheduling-1] c.e.o.s.ConsumerGroupLagMetrics          : group=s12-inventory totalLag=1176 partitions=3
```

```bash
curl -s 'localhost:8080/actuator/metrics/kafka.consumer.group.lag' | jq -c '.measurements, [.availableTags[].tag]'
```

**결과**
```json
[{"statistic":"VALUE","value":1176.0}]
["group","topic","partition","application"]
```

```bash
curl -s localhost:8080/actuator/prometheus | grep '^kafka_consumer_group_lag'
```

**결과**
```
# TYPE kafka_consumer_group_lag gauge
kafka_consumer_group_lag{application="order-service",group="s12-inventory",partition="0",topic="orders",} 391.0
kafka_consumer_group_lag{application="order-service",group="s12-inventory",partition="1",topic="orders",} 402.0
kafka_consumer_group_lag{application="order-service",group="s12-inventory",partition="2",topic="orders",} 383.0
```

이제 리스너 컨테이너를 멈춰도 지표가 살아 있습니다. 12-4 와 같은 실험을 반복합니다.

```bash
curl -X POST localhost:8080/admin/listeners/stop
sleep 30
curl -s localhost:8080/actuator/prometheus | grep '^kafka_consumer_group_lag{.*partition="1"'
```

**결과**
```
kafka_consumer_group_lag{application="order-service",group="s12-inventory",partition="1",topic="orders",} 1284.0
```

**클라이언트 지표는 사라졌지만 이쪽은 계속 올라갑니다.** 이것이 알람을 걸어야 할 지표입니다.

| | 클라이언트 지표 (`records.lag`) | AdminClient 게이지 (`consumer.group.lag`) |
|---|---|---|
| 기준 | 컨슈머의 **fetch 위치** | 그룹의 **커밋된 오프셋** |
| 컨슈머가 죽으면 | **지표 소멸** | 계속 보고됨 |
| 갱신 주기 | fetch 마다(빠름) | `@Scheduled` 주기(10초) |
| 비용 | 0 | `listConsumerGroupOffsets` + `endOffsets` 호출 |
| 용도 | 순간 처리 추이 | **알람** |

> 💡 **실무 팁 — 랙 조회 전용 컨슈머는 절대 `poll()` 하지 마세요.**
> `cf.createConsumer("lag-probe", "-probe")` 로 만든 컨슈머는 `groupId` 를 실제 그룹과 다르게 두고 `subscribe` 하지 않습니다.
> 실수로 실제 그룹 ID 로 `subscribe` 하면 **랙 측정용 컨슈머가 그룹에 합류해 리밸런스를 일으키고 파티션을 가져갑니다.**
> `endOffsets` 는 그룹 멤버십과 무관한 메타데이터 조회라 `assign`/`subscribe` 없이 동작합니다.

> 💡 `probe.endOffsets(...)` 대신 `admin.listOffsets(Map.of(tp, OffsetSpec.latest()))` 로도 같은 값을 얻습니다.
> 컨슈머 객체를 하나 덜 만들어도 되므로 운영 코드에서는 이쪽이 더 깔끔합니다. 이 교재는 두 API 의 대응을 보이려고 `endOffsets` 를 썼습니다.

---

## 12-6. `@KafkaListener` 처리 시간 측정

`ContainerProperties.setMicrometerEnabled(true)` 는 **기본값이 true** 입니다.
리스너 컨테이너는 레코드 하나를 처리할 때마다 `spring.kafka.listener` 타이머에 기록합니다.

```bash
curl -s 'localhost:8080/actuator/metrics/spring.kafka.listener' | jq
```

**결과**
```json
{
  "name": "spring.kafka.listener",
  "description": "Kafka Listener Timer",
  "baseUnit": "seconds",
  "measurements": [
    { "statistic": "COUNT",     "value": 1842.0 },
    { "statistic": "TOTAL_TIME","value": 41.196 },
    { "statistic": "MAX",       "value": 1.084 }
  ],
  "availableTags": [
    { "tag": "result",    "values": ["success", "failure"] },
    { "tag": "name",      "values": ["s12-inventory-0", "s12-inventory-1", "s12-inventory-2"] },
    { "tag": "exception", "values": ["none", "IllegalStateException"] }
  ]
}
```

평균 처리 시간은 `TOTAL_TIME / COUNT` = 41.196 / 1842 = **22.4ms**, 최댓값은 **1.084초**입니다.
실패만 따로 보려면 `?tag=result:failure` 를 붙입니다(위 데이터에서는 `COUNT=19`, `TOTAL_TIME=0.412`).

이 타이머는 **리스너 메서드 호출 구간만** 잽니다. 더 잘게 나누고 싶으면 `Timer.Sample` 을 직접 씁니다.

```java
@KafkaListener(topics = "orders", groupId = "s12-inventory")
public void onOrder(OrderCreated order) {
    Timer.Sample sample = Timer.start(registry);
    String outcome = "ok";
    try {
        inventoryService.deduct(order);          // 실제 비즈니스 구간
    } catch (RuntimeException ex) {
        outcome = "error";
        throw ex;
    } finally {
        sample.stop(registry.timer("order.inventory.deduct", "outcome", outcome));
    }
}
```

**결과**
```json
{ "name": "order.inventory.deduct",
  "measurements": [ { "statistic": "COUNT", "value": 1842.0 },
                    { "statistic": "TOTAL_TIME", "value": 38.940 },
                    { "statistic": "MAX", "value": 1.061 } ] }
```

`spring.kafka.listener` 의 41.196초 중 **38.940초가 재고 차감**입니다. 나머지 2.256초(건당 1.2ms)가 역직렬화·인터셉터·Observation 등 프레임워크 오버헤드입니다.
"Kafka 가 느리다"는 신고의 대부분은 이 5% 가 아니라 나머지 95% 쪽입니다.

> 💡 **실무 팁 — `@Timed` 를 붙였는데 지표가 안 나온다면 `TimedAspect` 빈이 없는 것입니다.**
> `@Timed` 는 AOP 기반이라 아래 빈을 직접 등록해야 동작합니다. Boot 는 자동 등록하지 않습니다.
> ```java
> @Bean
> public TimedAspect timedAspect(MeterRegistry registry) { return new TimedAspect(registry); }
> ```
> 게다가 `@KafkaListener` 메서드에 붙일 때는 프록시가 리스너 등록을 가로채면서 **어노테이션 탐지가 어긋나는 경우**가 있습니다.
> 리스너 안에서는 `Timer.Sample` 을 쓰는 편이 확실합니다.

백분위수가 필요하면 `management.metrics.distribution.percentiles-histogram.spring.kafka.listener: true` 를 켜고 `slo` 로 버킷 경계를 지정합니다.

---

## 12-7. `observationEnabled` 로 분산 추적

Step 05 에서는 `traceId` 를 헤더에 **직접 넣고 직접 꺼냈습니다.** Spring Kafka 3.x 는 이것을 프레임워크가 합니다.

```yaml
spring:
  kafka:
    template:
      observation-enabled: true
    listener:
      observation-enabled: true
```

이 두 줄이면 `KafkaTemplate.send` 와 리스너 실행이 Micrometer **Observation** 으로 감싸이고,
`micrometer-tracing-bridge-brave` 가 그것을 span 으로 바꿉니다. 프로듀서는 **W3C `traceparent` 헤더를 레코드에 심고**, 컨슈머는 그 헤더에서 컨텍스트를 복원합니다.

```java
@Profile("step12-trace")
@RestController
class OrderController {
    @PostMapping("/orders")
    public String create(@RequestParam int seq) {
        OrderCreated order = OrderCreated.of(seq);
        log.info("HTTP 요청 수신 orderId={}", order.orderId());
        kafkaTemplate.send("orders", order.orderId(), order);
        return order.orderId();
    }
}
```

```bash
curl -X POST 'localhost:8080/orders?seq=7'
```

**결과**
```
INFO  [order-service,6f8c2a1b9d4e5f30,3a1b2c4d5e6f7081] 13418 --- [nio-8080-exec-1] c.e.o.step12.OrderController        : HTTP 요청 수신 orderId=ORD-0007
INFO  [order-service,6f8c2a1b9d4e5f30,9c7d1e2f3a4b5c60] 13418 --- [ad | producer-1] c.e.o.step12.TracedProducer         : sent orders-1@318 orderId=ORD-0007
INFO  [order-service,6f8c2a1b9d4e5f30,b2c3d4e5f6a70819] 13418 --- [ntainer#0-1-C-1] c.e.o.step12.TracedListener         : 재고 차감 orderId=ORD-0007 orders-1@318
INFO  [order-service,6f8c2a1b9d4e5f30,c8d9e0f1a2b3c4d5] 13418 --- [ntainer#0-1-C-1] c.e.o.step12.TracedListener         : 알림 발송 orderId=ORD-0007
```

**대괄호 안 가운데 값(`6f8c2a1b9d4e5f30`)이 네 줄 모두 같습니다.** HTTP 요청 → 프로듀서 → 컨슈머가 하나의 trace 입니다.
세 번째 값(spanId)은 각기 다릅니다. 컨슈머 스레드는 HTTP 스레드와 아무 관계가 없는데도 이어졌습니다. 헤더 덕분입니다.

```bash
kcc --topic orders --partition 1 --offset 318 --max-messages 1 --property print.headers=true
```

**결과**
```
traceparent:00-6f8c2a1b9d4e5f306f8c2a1b9d4e5f30-9c7d1e2f3a4b5c60-01,__TypeId__:com.example.order.domain.OrderCreated	{"orderId":"ORD-0007","customerId":1007,"sku":"SKU-002","quantity":3,"amount":10000,"createdAt":"2025-01-01T00:07:00Z"}
```

`traceparent` 는 W3C 형식 `버전-traceId-parentSpanId-플래그` 입니다.
`-01` 은 **샘플링됨** 이라는 뜻입니다. `-00` 이면 수집기가 버립니다.

| 항목 | Step 05 수동 전파 | `observation-enabled` |
|---|---|---|
| 헤더 이름 | 직접 정한 `X-Trace-Id` | 표준 `traceparent` (`tracestate`, B3 도 선택 가능) |
| 심는 코드 | `ProducerRecord.headers().add(...)` 를 매번 | 없음 |
| 꺼내는 코드 | `@Header("X-Trace-Id") String` + `MDC.put` | 없음 |
| MDC 연동 | 직접 `put`/`remove` | 자동 (`%X{traceId}` 바로 사용) |
| 부모-자식 관계 | 없음. 문자열 한 개 | span 트리. 지연 구간이 분리됨 |
| Zipkin/OTel 연동 | 불가 | 그대로 전송 |

수동 전파는 "같은 요청인지"만 알려 주고, Observation 은 **"어느 구간에서 느렸는지"** 까지 알려 줍니다.
위 trace 는 총 512ms 중 `POST /orders` 48ms, `orders send` 11ms, `orders receive` 464ms 로 쪼개지고,
그 464ms 중 441ms 가 `inventory.deduct` 였습니다. 문자열 하나만 전파했다면 이 분해는 불가능합니다.

---

## 12-8. ⚠️ 함정 — 컨슈머만 켜면 trace 가 끊긴다

`observation-enabled` 는 프로듀서(`template`)와 컨슈머(`listener`)가 **별도 설정**입니다.
컨슈머만 켜 놓고 "왜 안 이어지지" 하는 경우가 가장 흔합니다.

```yaml
spring:
  kafka:
    listener:
      observation-enabled: true    # 켬
    # template.observation-enabled 를 빠뜨림  ← 기본 false
```

**결과**
```
INFO  [order-service,6f8c2a1b9d4e5f30,3a1b2c4d5e6f7081] 13418 --- [nio-8080-exec-1] c.e.o.step12.OrderController        : HTTP 요청 수신 orderId=ORD-0007
INFO  [order-service,,] 13418 --- [ad | producer-1] c.e.o.step12.TracedProducer         : sent orders-1@319 orderId=ORD-0007
INFO  [order-service,d41d8cd98f00b204,e9800998ecf8427e] 13418 --- [ntainer#0-1-C-1] c.e.o.step12.TracedListener         : 재고 차감 orderId=ORD-0007 orders-1@319
```

- 프로듀서 줄은 `[order-service,,]` — traceId 가 **비어 있습니다.**
- 컨슈머 줄은 traceId 가 있지만 **HTTP 요청과 다른 값**입니다. 새 trace 를 시작한 것입니다.

헤더를 보면 원인이 명확합니다.

```bash
kcc --topic orders --partition 1 --offset 319 --max-messages 1 --property print.headers=true
```

**결과**
```
__TypeId__:com.example.order.domain.OrderCreated	{"orderId":"ORD-0007",...}
```

`traceparent` 가 **아예 없습니다.** 컨슈머 쪽 Observation 은 "부모가 없으니 루트 span 을 만든다"고 판단합니다.
**에러도 경고도 없습니다.** trace 는 잘 그려지는데 조각조각 끊겨 있을 뿐입니다.

> ⚠️ **함정 — 이미 쌓여 있던 옛 메시지에는 `traceparent` 가 없다**
> 설정을 켜고 배포한 뒤에도, **켜기 전에 발행된 메시지**는 헤더가 없어 각자 새 trace 로 시작합니다.
> 랙이 큰 상태에서 배포하면 한동안 "끊긴 trace" 가 대량으로 섞여 보입니다. 장애가 아니라 정상입니다.
> 마찬가지로 **다른 팀 서비스가 발행한 토픽**을 구독한다면, 그쪽이 켜기 전까지는 절대 이어지지 않습니다.
> 확인 방법은 하나뿐입니다. **`print.headers=true` 로 실제 레코드에 `traceparent` 가 있는지 보는 것.**

> 💡 `@RetryableTopic`(Step 08)으로 retry 토픽을 거친 메시지는 **재발행 시점의 trace** 로 이어집니다.
> 원본 메시지와 재시도 메시지가 같은 trace 로 묶이므로, 재시도 지연이 span 트리에 그대로 드러납니다.

---

## 12-9. 로그에 파티션/오프셋 남기기 (실전 필수)

장애 대응은 결국 **"그 메시지 하나"** 를 찾는 일입니다. `topic-partition@offset` 이 없으면 찾을 방법이 없습니다.

**before**
```
INFO 13418 --- [ntainer#0-1-C-1] c.e.o.step12.InventoryListener          : 재고 차감 실패 orderId=ORD-0311
ERROR 13418 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler             : Record in retry, will be re-delivered
```

이 로그로 할 수 있는 일은 없습니다. `ORD-0311` 이 어느 파티션 몇 번 오프셋인지 모르면 `kcc --offset` 으로 꺼내 볼 수도, 그 지점부터 리셋할 수도 없습니다.

`RecordInterceptor` 로 MDC 에 넣습니다. 리스너 코드를 한 줄도 안 고쳐도 됩니다.

```java
public class MdcRecordInterceptor implements RecordInterceptor<String, Object> {

    @Override
    public ConsumerRecord<String, Object> intercept(ConsumerRecord<String, Object> record,
                                                    Consumer<String, Object> consumer) {
        MDC.put("kafka", record.topic() + "-" + record.partition() + "@" + record.offset());
        MDC.put("kafkaKey", String.valueOf(record.key()));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, Object> record, Consumer<String, Object> consumer) {
        MDC.remove("kafka");          // 반드시 지운다. 스레드 재사용
        MDC.remove("kafkaKey");
    }
}
```

컨테이너 팩토리에 `factory.setRecordInterceptor(new MdcRecordInterceptor())` 로 붙이고, 로그 패턴에 `[%X{kafka:-}]` 를 추가합니다.

```yaml
logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}] [%X{kafka:-}]"
```

**after**
```
INFO  [order-service,6f8c2a1b9d4e5f30,b2c3d4e5f6a70819] [orders-1@311] 13418 --- [ntainer#0-1-C-1] c.e.o.step12.InventoryListener : 재고 차감 실패 orderId=ORD-0311
ERROR [order-service,6f8c2a1b9d4e5f30,b2c3d4e5f6a70819] [orders-1@311] 13418 --- [ntainer#0-1-C-1] o.s.k.l.DefaultErrorHandler    : Record in retry, will be re-delivered
```

이제 `kcc --topic orders --partition 1 --offset 311 --max-messages 1` 로 문제의 원본 레코드를 바로 꺼낼 수 있습니다.

> ⚠️ **함정 — `afterRecord` 에서 MDC 를 안 지우면 남의 로그에 남의 오프셋이 찍힌다**
> 리스너 스레드(`ntainer#0-1-C-1`)는 재사용됩니다. `MDC.remove` 를 빠뜨리면 **다음 레코드가 예외로 죽어 인터셉터를 못 타는 경우**
> 직전 레코드의 오프셋이 그대로 찍힙니다. 로그는 `orders-1@311` 을 가리키는데 실제 문제는 `orders-1@312` 인 상황이 만들어집니다.
> **틀린 관측 정보는 관측 정보가 없는 것보다 나쁩니다.** `afterRecord` 는 성공/실패와 무관하게 호출되므로 여기서 지우는 것이 안전합니다.
> 배치 리스너를 쓴다면 `BatchInterceptor` 로 같은 일을 하되, 배치 전체의 범위(`orders-1@300..349`)를 넣습니다.

> 💡 **실무 팁 — 로그 규약 세 줄**
> ① 리스너의 모든 로그에 `topic-partition@offset`. ② 예외 로그에는 **메시지 키**도. ③ DLT 발행 시 원본 좌표를 `WARN` 으로 한 줄.
> 이 세 줄이면 "어떤 주문이 어디서 사라졌는가"에 대부분 답할 수 있습니다.

---

## 12-10. Actuator 헬스 — 브로커가 살아 있다는 것이 전부가 아니다

`spring-kafka` 가 클래스패스에 있으면 Boot 는 `KafkaHealthIndicator` 를 자동 등록합니다.

```bash
curl -s localhost:8080/actuator/health/kafka | jq
```

**결과**
```json
{ "status": "UP",
  "details": { "clusterId": "4L6g3nShT-eMCtK--X86sw", "brokerId": "1", "nodes": 1 } }
```

이 헬스가 하는 일은 `KafkaAdmin.describeCluster()` 호출 한 번입니다. **브로커에 붙을 수 있느냐만 봅니다.**
12-4 처럼 리스너 컨테이너를 전부 멈춘 뒤 다시 조회해도 결과는 그대로 `UP` 입니다.

```bash
curl -X POST localhost:8080/admin/listeners/stop
curl -s localhost:8080/actuator/health/kafka | jq -r '.status'    # → UP
```

> ⚠️ **함정 — 컨슈머가 한 건도 소비하지 않는데 헬스는 UP 이다**
> `KafkaHealthIndicator` 는 리스너 컨테이너를 보지 않습니다. 컨테이너가 죽었든, 파티션을 하나도 할당받지 못했든 **`UP`** 입니다.
> 쿠버네티스 readiness probe 를 `/actuator/health` 로 걸어 두면, **아무 일도 안 하는 파드가 정상 판정을 받아 계속 떠 있습니다.**
> 랙은 무한히 늘고, 12-4 때문에 랙 지표는 사라진 상태입니다. **세 개의 관측 장치가 동시에 눈을 감습니다.**
> 해결은 컨테이너 상태를 보는 `HealthIndicator` 를 직접 만드는 것입니다.

```java
@Component("kafkaListeners")            // ← 빈 이름이 곧 /actuator/health/<이름>
@Profile("step12-health")
@RequiredArgsConstructor
public class KafkaListenerHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    @Override
    public Health health() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers.isEmpty()) {
            return Health.down().withDetail("reason", "등록된 리스너 컨테이너가 없음").build();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        boolean healthy = true;

        for (MessageListenerContainer c : containers) {
            Collection<TopicPartition> assigned = c.getAssignedPartitions();
            boolean running  = c.isRunning();
            boolean hasParts = assigned != null && !assigned.isEmpty();
            healthy &= running && hasParts;

            details.put(c.getListenerId(), Map.of(
                    "running", running,
                    "assignedPartitions", assigned == null ? List.of() : assigned.stream().map(TopicPartition::toString).toList()
            ));
        }
        return (healthy ? Health.up() : Health.down()).withDetails(details).build();
    }
}
```

```bash
curl -s localhost:8080/actuator/health/kafkaListeners | jq -c
```

**결과** (정상 / 컨테이너 정지 후)
```json
{"status":"UP","details":{
  "s12-inventory-0":{"running":true,"assignedPartitions":["orders-0"]},
  "s12-inventory-1":{"running":true,"assignedPartitions":["orders-1"]},
  "s12-inventory-2":{"running":true,"assignedPartitions":["orders-2"]}}}

{"status":"DOWN","details":{
  "s12-inventory-0":{"running":false,"assignedPartitions":[]},
  "s12-inventory-1":{"running":false,"assignedPartitions":[]},
  "s12-inventory-2":{"running":false,"assignedPartitions":[]}}}
```

이제 `/actuator/health` 전체 상태도 `DOWN` 이 되고, 12-0 에서 만든 `readiness` 그룹이 이 지표만 봅니다.

> 💡 **실무 팁 — liveness 와 readiness 를 구분하세요.**
> 리스너 컨테이너가 죽었다고 **파드를 죽이면(liveness)** 재시작 → 리밸런스 → 다시 죽음의 루프에 빠질 수 있습니다.
> `readiness` 에만 넣어 트래픽에서 빼고 알람을 울리는 편이 안전합니다. Kafka 컨슈머는 HTTP 트래픽을 받지 않으므로
> **readiness DOWN 의 실질적 효과는 "알람"** 입니다. 그것으로 충분합니다.

> 💡 `getAssignedPartitions()` 는 리밸런스 진행 중 잠깐 비어 있을 수 있습니다. 운영에서는 **연속 N회 DOWN** 일 때만 알람을 울리세요.
> 리밸런스 프로토콜의 단계별 상태는 [Kafka 코스 Step 05](../../kafka/step-05-consumer/) 를 참고하세요.

---

## 12-11. 알람 기준 — 어떤 지표에 무슨 임계값을 걸 것인가

| 지표 | 임계값(예) | 왜 이 값인가 | 심각도 |
|---|---|---|---|
| `kafka_consumer_group_lag` **절대값** | 5분 연속 > 10,000 | 정상 처리량(1,000 msg/s)의 10초분. 순간 스파이크는 넘김 | Warning |
| 랙 **증가율** | `deriv(lag[10m]) > 0` 이 15분 지속 | 절대값이 작아도 **줄지 않으면** 결국 터짐. 트래픽이 적은 서비스에 필수 | Critical |
| 랙 소진 예상 시간 | `lag / rate(consumed[5m]) > 1800` | "다 처리하는 데 30분 이상" — 절대값보다 직관적 | Critical |
| DLT 유입률 | `rate(dlt_records[5m]) > 0` | DLT 는 **0 이 정상**. 한 건이라도 들어오면 사람이 봐야 함 | Critical |
| `kafka_producer_record_error_total` | `increase(...[5m]) > 0` | 프로듀서 실패는 곧 **이벤트 유실**(Step 02) | Critical |
| 리밸런스 횟수 | `increase(rebalance_total[10m]) > 3` | 배포 시 1~2회는 정상. 3회 초과는 컨슈머가 쫓겨나는 중 | Warning |
| `/actuator/health/kafkaListeners` | 3회 연속 DOWN | 리밸런스 중 순간 DOWN 을 걸러 냄 | Critical |
| 랙 지표 **부재** | `absent(kafka_consumer_group_lag)` 2분 | 12-4 의 함정 대비. 앱 자체가 죽은 경우 | Critical |

**랙 0 이 정상을 뜻하지 않습니다.**
Step 06 에서 본 것처럼, `AckMode` 를 잘못 잡으면 **처리에 실패한 메시지의 오프셋까지 커밋**됩니다.
그러면 랙은 완벽하게 0 이고, 대시보드는 초록색이고, 주문은 사라집니다.
랙 알람은 **"밀리고 있다"** 만 잡습니다. **"잘못 처리했다"** 는 DLT 유입률과 비즈니스 지표(주문 수 대비 재고 차감 수)로 잡아야 합니다.

```sql
# 발행량과 처리량의 괴리 — 조용한 유실을 잡는 가장 단순한 룰
sum(rate(kafka_producer_record_send_total{topic="orders"}[10m]))
  - sum(rate(order_inventory_deduct_seconds_count[10m])) > 1
```

---

## 12-12. 운영 CLI 치트시트

장애 상황에서 대시보드보다 빠른 것이 CLI 입니다. 별칭은 [실습 프로젝트 셋업 P-8](../project/) 에 있습니다.

### ① 랙 확인 — 가장 먼저 치는 명령

```bash
kcg --describe --group s12-inventory
```

**결과**
```
GROUP          TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG   CONSUMER-ID                    HOST         CLIENT-ID
s12-inventory  orders  0          1204            1595            391   consumer-s12-inventory-1-4f2a  /172.19.0.1  consumer-s12-inventory-1
s12-inventory  orders  1          1193            1595            402   consumer-s12-inventory-2-9b71  /172.19.0.1  consumer-s12-inventory-2
s12-inventory  orders  2          1212            1595            383   consumer-s12-inventory-3-c033  /172.19.0.1  consumer-s12-inventory-3
```

`CONSUMER-ID` 가 `-` 로 나오면 **그 파티션을 아무도 맡고 있지 않다**는 뜻입니다. 12-4 의 상황입니다.

### ② 그룹 상태와 멤버별 할당

```bash
kcg --describe --group s12-inventory --state
kcg --describe --group s12-inventory --members --verbose
```

**결과**
```
GROUP          COORDINATOR (ID)      ASSIGNMENT-STRATEGY  STATE        #MEMBERS
s12-inventory  127.0.0.1:9092 (1)    range                Stable       3

GROUP          CONSUMER-ID                    HOST         CLIENT-ID                 #PARTITIONS  ASSIGNMENT
s12-inventory  consumer-s12-inventory-1-4f2a  /172.19.0.1  consumer-s12-inventory-1  1            orders(0)
s12-inventory  consumer-s12-inventory-2-9b71  /172.19.0.1  consumer-s12-inventory-2  1            orders(1)
s12-inventory  consumer-s12-inventory-3-c033  /172.19.0.1  consumer-s12-inventory-3  1            orders(2)
```

`STATE` 는 `Stable`(정상) / `PreparingRebalance`·`CompletingRebalance`(리밸런스 중, **오래 지속되면 문제**) / `Empty`(멤버 없음, 커밋된 오프셋만 남음) / `Dead` 중 하나입니다.
`#PARTITIONS` 가 `0` 인 멤버가 있으면 **`concurrency` 가 파티션 수보다 큰 것**입니다(Step 03).

### ③ 오프셋 리셋 — 반드시 `--dry-run` 먼저

```bash
# 앱을 먼저 종료할 것. --dry-run 으로 NEW-OFFSET 을 확인한 뒤에만 --execute 로 바꾼다
kcg --group s12-inventory --topic orders --reset-offsets --to-datetime 2025-01-01T00:00:00.000 --dry-run
```

**결과**
```
GROUP          TOPIC   PARTITION  NEW-OFFSET
s12-inventory  orders  0          842
s12-inventory  orders  1          851
s12-inventory  orders  2          839
```

| 옵션 | 의미 |
|---|---|
| `--to-earliest` / `--to-latest` | 처음 / 끝으로 |
| `--to-offset 500` | 절대 오프셋(모든 파티션 동일) |
| `--shift-by -100` | 현재 위치에서 상대 이동. **되감기에 가장 안전** |
| `--to-datetime <ISO>` | 시각 기준. **장애 시작 시각으로 되감을 때** |

### ④ 메시지 개수 세기와 DLT 확인

```bash
# 파티션별 마지막 오프셋(-1). --time -2 는 시작 오프셋. 둘의 차이가 실제 남아 있는 메시지 수
docker exec -it learn-kafka /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 --topic orders --time -1

kcc --topic orders.DLT --from-beginning --property print.headers=true --max-messages 3
```

**결과**
```
orders:0:1595
orders:1:1595
orders:2:1595

kafka_dlt-original-topic:orders,kafka_dlt-exception-fqcn:java.lang.IllegalStateException	{"orderId":"ORD-0311",...}
```

> 💡 3.7 에서는 `kafka-get-offsets.sh` 를 쓰지만, 예전 문서의 `kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list ...` 도 그대로 동작합니다.
> DLT 헤더 해석은 [Step 07](../step-07-error-handling/) 에 정리돼 있습니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 4대 신호 | 컨슈머 랙 / 처리 지연 / 에러·DLT / 리밸런스. 네 개를 **함께** 봐야 원인이 갈린다 |
| Micrometer 자동 연동 | `MicrometerConsumer/ProducerListener` 가 Boot 자동 설정으로 등록. 코드 0줄 |
| `/actuator/metrics` 합산 | 같은 이름 미터를 **합산**해 보여 줌. `concurrency=3` 이면 랙이 3배로 보임 |
| 클라이언트 랙 지표 | 컨슈머가 죽으면 **지표도 사라짐**. 가장 위험한 순간에 알람이 안 울린다 |
| `AdminClient` 랙 게이지 | `listConsumerGroupOffsets` + `endOffsets` → `MultiGauge`. **알람은 이쪽에 건다** |
| 랙 조회 전용 컨슈머 | 실제 그룹 ID 로 `subscribe` 하면 **리밸런스를 일으킨다.** `endOffsets` 만 호출할 것 |
| `spring.kafka.listener` 타이머 | `micrometerEnabled` 기본 true. `@Timed` 는 `TimedAspect` 빈이 없으면 **조용히 안 나옴** |
| `observation-enabled` | `template` 과 `listener` **둘 다** 켜야 함. 프로듀서가 `traceparent` 를 심는다 |
| 끊긴 trace | 프로듀서 미설정 / 옛 메시지 / 타 서비스 발행. **헤더를 직접 보는 것이 유일한 확인법** |
| MDC `partition@offset` | `RecordInterceptor` 로 주입, `afterRecord` 에서 **반드시 제거** |
| `KafkaHealthIndicator` | `describeCluster` 만 확인. **컨테이너가 죽어도 UP** |
| 커스텀 HealthIndicator | `KafkaListenerEndpointRegistry.getListenerContainers()` → `isRunning()` + 할당 파티션 |
| 랙 0 | **정상을 뜻하지 않는다.** 잘못된 커밋이면 랙 0 인 채로 유실(Step 06) |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **반드시 실제로 `curl` 을 쳐서 출력을 확인**하세요.

1. `/actuator/prometheus` 에서 랙 관련 시계열을 전부 찾아, `records.lag` 와 `records.lag.max` 의 라벨 차이를 표로 정리하기
2. `AdminClient` + `MultiGauge` 로 `s12-ex-inventory` 그룹의 파티션별 랙 게이지를 등록하고, **리스너를 멈춘 뒤에도 값이 올라가는 것**을 확인하기
3. 리스너의 비즈니스 구간에 `Timer.Sample` 을 붙여 `order.inventory.deduct` 타이머를 만들고, `spring.kafka.listener` 의 `TOTAL_TIME` 과 비교해 프레임워크 오버헤드를 계산하기
4. `template.observation-enabled` 와 `listener.observation-enabled` 를 각각 켜고 끄며 4가지 조합의 로그 traceId 를 기록하고, `traceparent` 헤더 유무를 `kcc` 로 대조하기
5. `KafkaListenerEndpointRegistry` 를 주입받아 컨테이너별 `running` / 할당 파티션을 노출하는 `HealthIndicator` 를 만들고, 컨테이너를 멈춰 `DOWN` 을 확인하기
6. `RecordInterceptor` 로 `topic-partition@offset` 을 MDC 에 넣고, `afterRecord` 의 `MDC.remove` 를 **일부러 빼서** 잘못된 오프셋이 로그에 남는 것을 재현하기

---

## 다음 단계

이제 무엇이 잘못되고 있는지 **보이는** 상태가 됐습니다. 랙은 브로커 기준으로 잡히고, trace 는 HTTP 요청부터 컨슈머까지 이어지고, 로그 한 줄로 원본 메시지를 꺼낼 수 있습니다.

남은 것은 **애초에 잘못되지 않게 만드는 설계**입니다. 마지막 스텝에서는 재처리해도 안전한 멱등 컨슈머, DB 와 Kafka 를 진짜로 묶는 Transactional Outbox, 그리고 순서 보장이 필요한 구간을 좁히는 전략을 구현하고, 지금까지의 열두 스텝을 하나의 주문 서비스로 합칩니다.

→ [Step 13 — 실전 패턴과 최종 프로젝트](../step-13-patterns/)

---

## 실습 파일

이 스텝은 **`curl` 을 치는 시간이 코드를 쓰는 시간보다 깁니다.** `Practice.java` 를 프로필로 켜 두고, 터미널을 하나 더 띄워 `/actuator/metrics` 와 `/actuator/prometheus` 를 계속 두드리세요. 특히 12-4 → 12-5 는 **같은 실험을 두 번** 하는 구성입니다. 리스너를 멈췄을 때 한쪽 지표는 사라지고 다른 쪽은 살아 있는 것을 눈으로 봐야 이 스텝의 요점이 전달됩니다. 그다음 `Exercise.java` 의 6문제를 풀고 `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.order.step12` 패키지에 둡니다.

### Practice.java

본문 12-4 ~ 12-10 의 예제를 절 번호 주석과 함께 nested static class 로 담은 실행 파일입니다.

- 프로필 4개(`step12-lag`, `step12-trace`, `step12-mdc`, `step12-health`)로 나뉘어 있지만, **이 스텝은 여러 개를 함께 켜도 됩니다.** 관측 코드끼리는 간섭하지 않습니다. 전부 보려면 `--spring.profiles.active=step12,step12-lag,step12-trace,step12-mdc,step12-health` 로 실행하세요.
- `[12-5] ConsumerGroupLagMetrics` 가 핵심입니다. `cf.createConsumer("lag-probe", "-probe")` 로 만든 프로브 컨슈머는 **`subscribe` 도 `assign` 도 하지 않습니다.** `endOffsets` 는 메타데이터 조회라 그것만으로 동작합니다. 실수로 실제 그룹 ID 를 넘기면 리밸런스가 발생하니 그룹 ID 인자를 절대 바꾸지 마세요.
- `[12-4] ListenerAdminController` 는 `/admin/listeners/stop`, `/start`, `/status` 세 엔드포인트를 엽니다. 이게 있어야 12-4 와 12-10 의 "컨테이너만 죽은 상태"를 안전하게 재현할 수 있습니다. Step 10 의 `KafkaListenerEndpointRegistry` 를 그대로 씁니다.
- `[12-7] TracedProducer` / `TracedListener` 는 로그 한 줄만 찍습니다. **볼 것은 로그 본문이 아니라 대괄호 안의 traceId** 입니다. `application-step12-trace.yml` 에 `template.observation-enabled: true` 가 들어 있는지 반드시 확인하세요. 빠지면 12-8 의 끊긴 trace 가 재현됩니다.
- `[12-9] MdcRecordInterceptor` 에는 `afterRecord` 의 `MDC.remove` 를 주석 처리할 수 있게 표시해 두었습니다. 주석을 풀었다 막았다 하며 로그의 오프셋이 어긋나는 것을 직접 보세요.
- `LoadGenerator` 는 초당 5건씩 `orders` 로 계속 발행합니다. 랙을 벌리려면 이게 떠 있어야 합니다. `app.load.enabled=false` 로 끌 수 있습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었고, 컴파일은 되도록 뼈대를 남겨 두었습니다.

- **문제 1·4** 는 코드를 거의 안 씁니다. `// 관측 기록:` 주석의 표를 `curl` 출력으로 채우는 문제입니다. 손으로 채우지 말고 실제 출력을 붙여 넣으세요.
- **문제 2** 는 이 스텝의 핵심 실습입니다. `MultiGauge.register(rows, true)` 의 두 번째 인자를 `false` 로 두면 어떻게 되는지도 함께 확인하도록 `// 실험:` 주석을 달아 두었습니다.
- **문제 3** 은 `spring.kafka.listener` 의 `TOTAL_TIME` 에서 `order.inventory.deduct` 의 `TOTAL_TIME` 을 뺀 값을 계산합니다. 두 타이머의 `COUNT` 가 같은지 먼저 확인하세요. 다르면 비교 자체가 무의미합니다.
- **문제 6** 은 **일부러 버그를 만드는 문제**입니다. `MDC.remove` 를 뺀 상태로 예외를 던지는 레코드를 하나 섞어, 다음 레코드의 로그에 이전 오프셋이 남는 것을 관측합니다. 관측이 끝나면 반드시 원복하세요.
- 각 문제 끝의 `// 확인:` 주석에 **기대 출력 한 줄**이 적혀 있습니다. 문제 2 의 확인 줄은 `kafka_consumer_group_lag{...,partition="1",topic="orders",} 402.0` 형태입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 블록 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `records.lag` 에 `topic`/`partition` 라벨이 있고 `records.lag.max` 에는 없다는 차이를 정리합니다. 그래서 파티션 단위 알람은 `records.lag` 로만 가능하고, `lag.max` 는 `client_id` 단위 요약에 쓴다는 결론까지 적었습니다.
- **정답 2** 의 포인트는 `MultiGauge.register(rows, true)` 의 `overwrite=true` 입니다. `false` 로 두면 **이미 등록된 태그 조합의 값이 갱신되지 않아** 랙이 첫 측정값에서 얼어붙습니다. 값이 안 변하는데 에러도 없는 전형적인 조용한 버그라 실험으로 확인시킵니다.
- **정답 3** 은 두 타이머의 차이가 곧 프레임워크 오버헤드(역직렬화 + 인터셉터 + Observation)라는 계산과, `COUNT` 가 다를 수 있는 이유(재시도로 리스너가 여러 번 호출되면 `spring.kafka.listener` 의 count 만 늘어남)를 설명합니다.
- **정답 4** 는 네 가지 조합의 결과표입니다. 프로듀서만 켠 경우에도 **컨슈머 로그의 traceId 가 비는** 것이 핵심입니다. 헤더는 심겼는데 읽는 쪽이 없으므로, 헤더 유무와 trace 연결 여부가 별개라는 것을 보여 줍니다.
- **정답 5** 는 `isRunning()` 만으로 부족한 이유를 설명합니다. 컨테이너는 `running=true` 인데 `getAssignedPartitions()` 가 비어 있는 상태(파티션을 전부 뺏긴 좀비)가 실제로 존재하므로, 두 조건을 `&&` 로 묶어야 합니다. 다만 리밸런스 중 순간 DOWN 을 피하려고 알람은 연속 N회 조건을 걸라는 단서를 답니다.
- **정답 6** 은 `MDC.remove` 누락의 재현 결과입니다. `orders-1@311` 에서 예외가 나고 다음 레코드 `orders-1@312` 의 로그에도 `311` 이 찍히는 로그를 그대로 실었습니다. `afterRecord` 가 성공·실패와 무관하게 호출되므로 여기가 유일하게 안전한 제거 위치라는 결론입니다.

```java file="./Solution.java"
```
