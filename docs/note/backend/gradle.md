# Gradle

Groovy/Kotlin DSL 기반의 유연한 자동화 빌드 도구.

## 1. 개요

### 1.1 장점

- 빌드 캐시를 통한 압도적인 성능
- Android 및 Spring Boot 표준 도구
- 복잡한 빌드 로직의 자유로운 커스텀

### 1.2 핵심 개념

| 개념 | 설명 |
|------|------|
| **Project** | 빌드의 기본 단위 |
| **Plugin** | Task의 집합체 |
| **Task** | 개별 작업 단위 |

## 2. Build Lifecycle

> Gradle의 공식 빌드 생명주기는 **3단계**(Initialization → Configuration → Execution)다. 빌드 종료는 별도 단계가 아니라 실행 결과다.

### 2.1 Initialization (초기화)

빌드에 참여할 프로젝트들을 결정하는 단계.

- **settings.gradle** 파일을 읽는다.
- 멀티 프로젝트 빌드인 경우 포함된 모든 서브 프로젝트를 탐색한다.
- 각 프로젝트에 대해 **Project 객체** 인스턴스를 메모리에 생성한다.

### 2.2 Configuration (설정)

초기화 단계에서 생성된 모든 Project 객체의 빌드 스크립트를 실행하는 단계.

- **build.gradle** 파일을 평가(Evaluate)한다.
- **플러그인(Plugins)** 을 적용하고 **저장소(Repositories)** 및 **의존성(Dependencies)** 설정을 읽는다.
- 실행할 태스크들의 의존 관계를 계산하여 **태스크 그래프(DAG, Directed Acyclic Graph)** 를 구성한다.

### 2.3 Execution (실행)

설정 단계에서 작성된 태스크 그래프를 바탕으로 실제 작업을 수행하는 단계.

- 명령행에 입력한 **태스크(Task)** 와 그 의존 관계에 있는 태스크들을 실행한다.
- 컴파일, 테스트 실행, 파일 복사 등 실제 빌드 프로세스가 이 단계에서 진행된다.
- 태스크는 병렬 또는 순차적으로 실행될 수 있다.

### 2.4 빌드 종료 (결과)

- 성공 시: `BUILD SUCCESSFUL`
- 실패 시: `BUILD FAILED` (오류 로그 출력)
- 최종 빌드 리포트 및 성능 로그가 생성될 수 있다.

## 3. 프로젝트 구조와 빌드 스크립트

> Gradle의 유연성은 정해진 표준 구조(**Convention Over Configuration**)를 따를 때 극대화된다.

### 3.1 표준 디렉토리 구조

| 경로 | 역할 |
|------|------|
| `src/main/java` | 애플리케이션 소스 코드 |
| `src/test/java` | 테스트 관련 소스 코드 |
| `build/` | 빌드 결과물 저장 (생성되는 폴더) |

### 3.2 build.gradle 핵심 블록

- **plugins**: 프로젝트 확장 기능 추가
- **repositories**: 의존성 라이브러리 저장소
- **dependencies**: 필요한 외부 라이브러리 목록

### 3.3 설정 파일 역할

| 파일 | 역할 |
|------|------|
| `settings.gradle` | 프로젝트 이름 설정 및 멀티 프로젝트 모듈 포함 관리 |
| `gradle.properties` | JVM 옵션, 버전 정보, 프로젝트 전역 환경 변수 관리 |
| `build.gradle.kts` (Kotlin DSL) | 타입 안전성 |
| `build.gradle` (Groovy DSL) | 유연성 |

## 4. Dependency Management

**주요 저장소**

- `mavenCentral`
- `google`

**표기법**: `group:name:version`

**의존성 구성**

| 구성 | 설명 |
|------|------|
| `implementation` | 내부 캡슐화 (의존성 비노출) |
| `api` | 의존성 노출 |
| `testImplementation` | 테스트 시에만 사용 |

**의존성 충돌 해결**

- `exclude`: 특정 의존성 제외
- `resolutionStrategy`: 강제 버전 지정

## 5. Gradle 명령어

| 명령어 | 설명 |
|--------|------|
| `build` / `clean` | 프로젝트 빌드 및 초기화 |
| `dependencies` | 의존성 트리 확인 |
| `--info` / `--dry-run` | 상세 로그 및 미리보기 |
| `-x [Task]` | 특정 작업 제외 실행 |

## 6. Gradle Wrapper (gradlew)

- **환경 독립성**: 로컬 설치 없이 동일 버전 실행
- **설정 파일**: `gradle-wrapper.properties`
- **권장사항**: 항상 `./gradlew` 사용

## 7. Custom Task & Dependency 정의

```groovy
tasks.register('hello') {
  doLast { println 'Hello Gradle!' }
}

// doFirst / doLast: 실행 시점 제어
// dependsOn: Task 간 실행 순서 연결
//   예: Task B가 Task A에 의존하면 A가 먼저 실행된 뒤 B가 실행된다.
```

## 8. 멀티 프로젝트 빌드

### 8.1 계층적 구조 관리

- **Root Project**: 전체 빌드 설정 제어
- **Sub Projects**: 실제 기능을 담당하는 모듈
- **settings.gradle**: `include ':sub'` 로 등록

### 8.2 공통 설정 (Shared Logic)

- **allprojects**: Root 포함 모든 프로젝트 적용
- **subprojects**: 서브 프로젝트만 일괄 적용
- 의존성 관리 및 플러그인 중복 정의 방지

### 8.3 프로젝트 간 의존성

- `implementation project(':api')`
- 모듈 간의 유기적인 결합 지원
- 빌드 순서 자동 계산 (DAG)

### 8.4 성능 및 가시성

- **Build Cache**: 변경된 부분만 선별적 빌드
- **Build Scan**: 웹 기반 빌드 프로세스 시각화
- 대규모 빌드 시간 단축 및 병목 현상 분석
