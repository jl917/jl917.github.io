# 실습 프로젝트 셋업

> **학습 목표**
> - `docker compose` 로 Temporal Server + PostgreSQL + Web UI 를 기동하고 정상 동작을 확인한다
> - `temporal` CLI 를 설치하고 서버에 연결한다
> - Gradle + Java 21 + Temporal Java SDK 프로젝트를 구성하고 빌드에 성공한다
> - 코스 전체에서 쓸 디렉터리 구조와 실행 태스크(`runWorker` / `runStarter`)를 준비한다
> - 기동이 안 될 때의 진단 순서를 익힌다
>
> **예상 소요**: 40분

이 문서는 코스 전체에서 딱 한 번 하는 준비 작업입니다. 여기서 만든 환경을 Step 01 부터 Step 13 까지 그대로 씁니다.

---

## 0-1. 무엇을 띄우는가

Temporal 은 "서버 하나"가 아니라 네 개의 서비스(Frontend / History / Matching / Worker)와 영속 저장소로 구성됩니다. 학습용으로는 이것들을 한 프로세스에 담은 **`auto-setup` 이미지**를 씁니다.

```
   ┌──────────────────────────────────────────────────────────┐
   │  내 노트북                                                │
   │                                                          │
   │   ┌────────────────┐        ┌──────────────────────┐     │
   │   │  내 Java 앱     │        │  temporalio/         │     │
   │   │                │ gRPC   │  auto-setup:1.22.4   │     │
   │   │  Worker        │◄──────►│                      │     │
   │   │  Client        │  :7233 │  Frontend/History/   │     │
   │   │                │        │  Matching/Worker     │     │
   │   └────────────────┘        └──────────┬───────────┘     │
   │      ↑ 내 코드는                        │ SQL             │
   │        여기서만 돈다              ┌─────▼───────┐         │
   │                                  │ PostgreSQL 15│        │
   │   ┌────────────────┐             │  :5432       │        │
   │   │  Web UI :8233  │─────────────┴──────────────┘        │
   │   └────────────────┘   (히스토리 조회)                    │
   └──────────────────────────────────────────────────────────┘
```

> 💡 **가장 중요한 사실 — Temporal Server 는 내 코드를 실행하지 않습니다.**
> 서버는 이벤트 히스토리를 저장하고, Task Queue 에 작업을 매칭해 줄 뿐입니다.
> 워크플로우 코드도 액티비티 코드도 **전부 내 Worker 프로세스에서** 돕니다.
> 이 사실이 Temporal 의 거의 모든 동작(리플레이·버저닝·Worker 튜닝)의 출발점입니다.

---

## 0-2. docker-compose.yml

`temporal/project/docker-compose.yml` 을 만듭니다.

```yaml
# temporal/project/docker-compose.yml
# Temporal Server 1.22.4 + PostgreSQL 15 + Web UI 2.21.3
services:
  postgresql:
    container_name: temporal-postgresql
    image: postgres:15
    environment:
      POSTGRES_USER: temporal
      POSTGRES_PASSWORD: temporal
    ports:
      - "5432:5432"
    volumes:
      - temporal-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U temporal"]
      interval: 5s
      timeout: 5s
      retries: 10

  temporal:
    container_name: temporal
    image: temporalio/auto-setup:1.22.4
    depends_on:
      postgresql:
        condition: service_healthy
    environment:
      - DB=postgres12                 # PostgreSQL 12+ 스키마 플러그인 (15도 이 값을 씁니다)
      - DB_PORT=5432
      - POSTGRES_USER=temporal
      - POSTGRES_PWD=temporal
      - POSTGRES_SEEDS=postgresql
      - DYNAMIC_CONFIG_FILE_PATH=config/dynamicconfig/development-sql.yaml
    ports:
      - "7233:7233"                   # gRPC — Worker/Client 가 붙는 포트
    volumes:
      - ./dynamicconfig:/etc/temporal/config/dynamicconfig
    healthcheck:
      test: ["CMD", "tctl", "--address", "temporal:7233", "workflow", "list"]
      interval: 10s
      timeout: 5s
      retries: 15
      start_period: 30s

  temporal-ui:
    container_name: temporal-ui
    image: temporalio/ui:2.21.3
    depends_on:
      - temporal
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
      - TEMPORAL_CORS_ORIGINS=http://localhost:8233
    ports:
      - "8233:8080"                   # Web UI — http://localhost:8233

volumes:
  temporal-pgdata:
```

> ⚠️ **함정 — Web UI 포트를 8080 으로 두지 마십시오**
> Temporal UI 컨테이너는 내부적으로 8080 을 씁니다. 호스트 8080 은 스프링 부트 앱과 부딪히기 쉬우므로
> 이 코스는 **호스트 8233** 으로 매핑했습니다. Temporal CLI 의 개발 서버(`temporal server start-dev`)도
> UI 기본 포트가 8233 이라 일관됩니다. 교재의 모든 링크는 `http://localhost:8233` 기준입니다.

### 동적 설정 파일

`project/dynamicconfig/development-sql.yaml` 을 만듭니다. 학습용으로 몇 가지 한계를 조정합니다.

```yaml
# temporal/project/dynamicconfig/development-sql.yaml
limit.maxIDLength:
  - value: 255
    constraints: {}

# 히스토리 크기 경고/한계 — Step 08 에서 이 값을 직접 건드립니다
limit.historyCount.warn:
  - value: 10240
    constraints: {}
limit.historyCount.error:
  - value: 51200
    constraints: {}
limit.historySize.warn:
  - value: 10485760      # 10MB
    constraints: {}
limit.historySize.error:
  - value: 52428800      # 50MB
    constraints: {}

# Step 12 의 Search Attribute 실습에 필요
system.forceSearchAttributesCacheRefreshOnRead:
  - value: true
    constraints: {}
```

> 💡 `limit.historyCount.error: 51200` 이 Step 08 에서 다룰 **"이벤트 51,200개를 넘으면 워크플로우가 강제 종료된다"** 는 그 값입니다.
> 기본값과 동일하게 명시해 두었습니다. Step 08 에서 이 값을 작게 줄여 한계를 **몇 분 만에 재현**합니다.

---

## 0-3. 기동과 확인

```bash
cd temporal/project
docker compose up -d
```

**결과**

```
[+] Running 4/4
 ✔ Network project_default        Created                          0.1s
 ✔ Container temporal-postgresql  Healthy                         11.4s
 ✔ Container temporal             Started                         12.0s
 ✔ Container temporal-ui          Started                         12.3s
```

`auto-setup` 은 최초 1회에 PostgreSQL 스키마를 생성하고 `default` Namespace 를 등록합니다. 30초쯤 걸립니다.

```bash
docker compose logs temporal | tail -20
```

**결과**

```
temporal  | 2026-03-11T09:04:41.882Z INFO  Starting temporal-frontend  {"service":"frontend"}
temporal  | 2026-03-11T09:04:42.110Z INFO  Starting temporal-history    {"service":"history"}
temporal  | 2026-03-11T09:04:42.244Z INFO  Starting temporal-matching   {"service":"matching"}
temporal  | 2026-03-11T09:04:42.391Z INFO  Starting temporal-worker     {"service":"worker"}
temporal  | 2026-03-11T09:04:43.007Z INFO  Registering default namespace {"namespace":"default","retention":"72h0m0s"}
temporal  | 2026-03-11T09:04:43.512Z INFO  Temporal server started.
```

`Temporal server started.` 가 보이면 준비 완료입니다.

```bash
docker compose ps
```

**결과**

```
NAME                  IMAGE                            STATUS                    PORTS
temporal              temporalio/auto-setup:1.22.4     Up 42 seconds (healthy)   0.0.0.0:7233->7233/tcp
temporal-postgresql   postgres:15                      Up 54 seconds (healthy)   0.0.0.0:5432->5432/tcp
temporal-ui           temporalio/ui:2.21.3             Up 41 seconds             0.0.0.0:8233->8080/tcp
```

브라우저에서 `http://localhost:8233` 을 엽니다. **Workflows** 목록이 비어 있는 화면이 나오면 정상입니다.

---

## 0-4. temporal CLI 설치

Web UI 로 볼 수 있는 것은 CLI 로도 다 볼 수 있고, **CLI 로만 할 수 있는 것**(reset, build-id 관리 등)이 있습니다. 반드시 설치하십시오.

```bash
# macOS
brew install temporal

# Linux / macOS 공통 (스크립트)
curl -sSf https://temporal.download/cli.sh | sh
# ~/.temporalio/bin 에 설치됩니다. PATH 에 추가하세요:
export PATH="$HOME/.temporalio/bin:$PATH"
```

**설치 확인**

```bash
temporal --version
```

**결과**

```
temporal version 0.11.0 (server 1.22.4, ui 2.21.3)
```

### 기본 주소 설정

매번 `--address 127.0.0.1:7233` 을 붙이기 번거로우므로 환경 설정에 넣습니다.

```bash
temporal env set --env default --key address --value 127.0.0.1:7233
temporal env set --env default --key namespace --value default
temporal env get --env default
```

**결과**

```
  Property   Value
  address    127.0.0.1:7233
  namespace  default
```

### 연결 확인

```bash
temporal operator namespace list
```

**결과**

```
  NamespaceInfo.Name              temporal-system
  NamespaceInfo.Id                32049b68-7872-4094-8e63-d0dd59896a83
  NamespaceInfo.State             Registered
  NamespaceInfo.OwnerEmail        temporal-core@temporal.io
  Config.WorkflowExecutionRetentionTtl  168h0m0s

  NamespaceInfo.Name              default
  NamespaceInfo.Id                7c1b0ff0-6b7c-4a1f-9e5c-3d8a2f4b6c19
  NamespaceInfo.State             Registered
  NamespaceInfo.Description       Default namespace for Temporal Server.
  Config.WorkflowExecutionRetentionTtl  72h0m0s
```

`default` 가 `Registered` 이고 Retention 이 `72h0m0s` 이면 됩니다.

> ⚠️ **함정 — `temporal-system` 은 건드리지 마십시오**
> `temporal-system` 은 서버 자신이 내부 워크플로우(아카이빙, 배치 작업 등)를 돌리는 Namespace 입니다.
> 여기에 워크플로우를 띄우거나 설정을 바꾸면 서버가 오작동합니다. 실습은 전부 `default` 에서 합니다.

---

## 0-5. Gradle 프로젝트 구성

### 디렉터리 구조

```
temporal/project/
├── docker-compose.yml
├── dynamicconfig/
│   └── development-sql.yaml
├── build.gradle
├── settings.gradle
├── gradle/wrapper/
└── src/
    ├── main/
    │   ├── java/com/example/order/
    │   │   ├── Constants.java            ← ORDER_TASK_QUEUE 상수
    │   │   ├── OrderRequest.java         ← record DTO
    │   │   ├── OrderWorkflow.java        ← @WorkflowInterface
    │   │   ├── OrderWorkflowImpl.java
    │   │   ├── PaymentActivity.java      ← @ActivityInterface
    │   │   ├── PaymentActivityImpl.java
    │   │   ├── InventoryActivity.java
    │   │   ├── InventoryActivityImpl.java
    │   │   ├── ShippingActivity.java
    │   │   ├── ShippingActivityImpl.java
    │   │   ├── NotificationActivity.java
    │   │   ├── NotificationActivityImpl.java
    │   │   ├── OrderWorker.java          ← Worker 기동 main
    │   │   └── OrderStarter.java         ← 워크플로우 실행 main
    │   └── resources/
    │       └── logback.xml
    └── test/
        ├── java/com/example/order/
        │   └── OrderWorkflowTest.java
        └── resources/
            └── histories/                ← Step 11 리플레이 테스트용 히스토리 JSON
```

### settings.gradle

```groovy
rootProject.name = 'temporal-order-course'
```

### build.gradle 전문

```groovy
// temporal/project/build.gradle
plugins {
    id 'java'
    id 'application'
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

ext {
    temporalVersion = '1.22.3'
    junitVersion    = '5.10.1'
    mockitoVersion  = '5.8.0'
}

dependencies {
    // ---- Temporal Java SDK ----
    // temporal-sdk 하나만 넣으면 workflow/activity/client/worker 가 전부 딸려옵니다.
    implementation "io.temporal:temporal-sdk:${temporalVersion}"

    // 메트릭을 Prometheus 로 내보낼 때 필요 (Step 12)
    implementation "io.temporal:temporal-opentracing:${temporalVersion}"
    implementation 'io.micrometer:micrometer-registry-prometheus:1.12.2'

    // ---- 로깅 ----
    implementation 'ch.qos.logback:logback-classic:1.4.14'
    implementation 'org.slf4j:slf4j-api:2.0.11'

    // ---- 테스트 (Step 11) ----
    // TestWorkflowEnvironment, WorkflowReplayer 가 여기 들어 있습니다.
    testImplementation "io.temporal:temporal-testing:${temporalVersion}"
    testImplementation "org.junit.jupiter:junit-jupiter:${junitVersion}"
    testImplementation "org.mockito:mockito-core:${mockitoVersion}"
    testImplementation "org.mockito:mockito-junit-jupiter:${mockitoVersion}"
    testRuntimeOnly    'org.junit.platform:junit-platform-launcher'
}

application {
    // 기본 진입점은 Worker
    mainClass = 'com.example.order.OrderWorker'
}

test {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
        showStandardStreams = true
        exceptionFormat = 'full'
    }
}

// ---- 편의 태스크 ----

// Worker 기동:  ./gradlew runWorker
tasks.register('runWorker', JavaExec) {
    group = 'temporal'
    description = 'Temporal Worker 를 기동합니다 (Ctrl+C 로 종료)'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.example.order.OrderWorker'
    standardInput = System.in
}

// 워크플로우 실행:  ./gradlew runStarter --args="order-1001"
tasks.register('runStarter', JavaExec) {
    group = 'temporal'
    description = '워크플로우를 하나 실행합니다'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.example.order.OrderStarter'
}

// 각 스텝의 Practice/Exercise/Solution 실행:
//   ./gradlew runStep -PmainClass=step04.Practice
tasks.register('runStep', JavaExec) {
    group = 'temporal'
    description = '스텝별 실습 파일을 실행합니다 (-PmainClass=... 필요)'
    classpath = sourceSets.main.runtimeClasspath
    mainClass = project.findProperty('mainClass') ?: 'com.example.order.OrderWorker'
}

// 리플레이 테스트만 실행 (Step 11):  ./gradlew replayTest
tasks.register('replayTest', Test) {
    group = 'temporal'
    description = '히스토리 리플레이 테스트만 실행합니다'
    useJUnitPlatform {
        includeTags 'replay'
    }
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
}
```

> ⚠️ **함정 — SDK 버전과 서버 버전은 별개입니다**
> `io.temporal:temporal-sdk:1.22.3` 의 `1.22.3` 은 **SDK 버전**이지 서버 버전이 아닙니다.
> 우연히 비슷할 뿐 서로 독립적으로 올라갑니다. Temporal 은 SDK/서버 간 넓은 호환 범위를 보장하지만,
> **Worker Versioning(Step 10)처럼 서버 최소 버전을 요구하는 기능**이 있으므로 둘 다 기록해 두십시오.
> 이 코스는 **서버 1.22.4 / SDK 1.22.3** 조합에서 검증했습니다.

### logback.xml

Temporal SDK 는 로그가 상당히 수다스럽습니다. 학습에 필요한 것만 남깁니다.

```xml
<!-- src/main/resources/logback.xml -->
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- 내 코드는 전부 보기 -->
  <logger name="com.example.order" level="DEBUG"/>

  <!-- SDK 내부 로그는 핵심만 -->
  <logger name="io.temporal" level="INFO"/>
  <logger name="io.grpc" level="WARN"/>
  <logger name="io.netty" level="WARN"/>

  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

> 💡 **실무 팁 — `io.temporal.internal.replay` 를 DEBUG 로 올려 두면**
> 리플레이가 어디까지 진행됐는지 이벤트 단위로 찍힙니다. Step 03·10 에서 `NonDeterministicException` 을
> 추적할 때 결정적인 단서가 됩니다. 평소에는 로그가 너무 많으니 꺼 두고, 디버깅할 때만 켜십시오.

---

## 0-6. 빌드 확인

```bash
./gradlew build
```

**결과**

```
> Task :compileJava
> Task :processResources
> Task :classes
> Task :test
> Task :build

BUILD SUCCESSFUL in 18s
6 actionable tasks: 6 executed
```

의존성이 제대로 받아졌는지 확인합니다.

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -A2 temporal-sdk
```

**결과**

```
+--- io.temporal:temporal-sdk:1.22.3
|    +--- io.temporal:temporal-serviceclient:1.22.3
|    |    +--- io.grpc:grpc-core:1.59.0
|    |    +--- io.grpc:grpc-netty-shaded:1.59.0
|    |    \--- com.google.protobuf:protobuf-java:3.24.4
|    +--- com.fasterxml.jackson.core:jackson-databind:2.15.3
|    \--- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.3
```

`jackson-databind` 가 보입니다. Temporal 은 워크플로우/액티비티의 인자와 반환값을 **Jackson 으로 JSON 직렬화**해 히스토리에 저장합니다. 그래서 인자로 쓰는 타입은 전부 직렬화 가능해야 합니다 (Step 03 에서 자세히).

---

## 0-7. 기동이 안 될 때 — 진단 순서

| 증상 | 원인 | 확인 방법 | 해결 |
|---|---|---|---|
| `docker compose up` 이 계속 재시작 | PostgreSQL 이 아직 준비 안 됨 | `docker compose logs postgresql` | `depends_on.condition: service_healthy` 확인. 그냥 기다려도 됩니다 |
| `temporal operator namespace list` 가 `connection refused` | 7233 포트가 안 열림 | `docker compose ps`, `lsof -i :7233` | 컨테이너가 `healthy` 될 때까지 대기 |
| `Namespace default is not found` | auto-setup 이 스키마 생성 중 | `docker compose logs temporal \| grep "Registering default"` | 30초 더 기다립니다 |
| Web UI 가 빈 화면 / CORS 에러 | `TEMPORAL_CORS_ORIGINS` 불일치 | 브라우저 콘솔 | compose 의 값이 `http://localhost:8233` 인지 확인 |
| 스키마 버전 에러 | 예전 볼륨이 남아 있음 | `docker volume ls \| grep temporal` | `docker compose down -v` 로 볼륨째 삭제 후 재기동 |
| Gradle 이 SDK 를 못 받음 | 프록시/방화벽 | `./gradlew build --info` | `mavenCentral()` 접근 확인 |
| 빌드는 되는데 `UnsupportedClassVersionError` | JDK 버전 불일치 | `./gradlew -q javaToolchains` | Java 21 툴체인 설치 (`brew install --cask temurin`) |

### 완전 초기화

```bash
docker compose down -v      # -v 가 핵심: PostgreSQL 볼륨까지 삭제
docker compose up -d
```

> ⚠️ `-v` 를 붙이면 **지금까지 실행한 모든 워크플로우 기록이 사라집니다.** Step 11 의 리플레이 테스트용으로
> 덤프해 둔 히스토리 JSON 은 `src/test/resources/histories/` 에 파일로 있으므로 안전하지만,
> 서버에만 있던 히스토리는 복구할 수 없습니다. 분석 중인 워크플로우가 있다면 먼저 덤프하십시오:
> ```bash
> temporal workflow show -w order-1001 --output json > order-1001.json
> ```

---

## 0-8. 최종 점검 체크리스트

아래 다섯 가지가 전부 통과하면 Step 01 로 넘어갈 준비가 된 것입니다.

```bash
# 1. 컨테이너 3개가 떠 있는가
docker compose ps | grep -c "Up"          # → 3

# 2. gRPC 로 서버에 붙는가
temporal operator namespace describe default | head -3

# 3. Web UI 가 응답하는가
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8233   # → 200

# 4. Java 21 인가
./gradlew -q javaToolchains | grep -A1 "Java 21"

# 5. 빌드가 통과하는가
./gradlew build -q && echo "OK"
```

**결과**

```
3
  NamespaceInfo.Name    default
  NamespaceInfo.Id      7c1b0ff0-6b7c-4a1f-9e5c-3d8a2f4b6c19
  NamespaceInfo.State   Registered
200
 | Language Version:              21
OK
```

---

## 정리

| 항목 | 핵심 |
|---|---|
| Temporal Server | 내 코드를 실행하지 않는다. 히스토리 저장 + Task Queue 매칭만 한다 |
| `auto-setup` 이미지 | 4개 서비스를 한 컨테이너에. 학습용 전용, 운영에는 쓰지 않는다 |
| gRPC `:7233` | Worker/Client 가 붙는 유일한 포트 |
| Web UI `:8233` | 히스토리를 눈으로 보는 곳. 이 코스의 주력 도구 |
| PostgreSQL | 모든 상태가 여기 있다. `down -v` 는 전부 삭제 |
| `temporal` CLI | UI 로 못 하는 것(reset, build-id)이 있으므로 필수 |
| `temporal-sdk` | 이것 하나로 workflow/activity/client/worker 전부 포함 |
| `temporal-testing` | `TestWorkflowEnvironment`, `WorkflowReplayer` (Step 11) |
| Jackson 직렬화 | 워크플로우/액티비티 인자·반환값은 JSON 직렬화 가능해야 한다 |
| Retention 72시간 | 사흘 지나면 히스토리 조회 불가. 필요한 건 미리 덤프 |

---

## 다음 단계

환경이 준비됐습니다. 이제 가장 단순한 워크플로우를 하나 만들어 실행하고, **그 실행이 이벤트 히스토리에 어떻게 기록되는지** 두 눈으로 확인합니다. Temporal 학습의 거의 전부는 "코드 한 줄이 어떤 이벤트가 되는가"를 몸에 익히는 일이고, 그 첫걸음이 Step 01 입니다.

→ [Step 01 — 환경 구축과 첫 워크플로우](../step-01-setup/)
