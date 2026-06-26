# Spring Batch

배치 처리를 Spring 프레임워크 기반에서 안정적으로 개발할 수 있게 해주는 엔터프라이즈급 프레임워크.

> **배치 처리란?** 대량의 데이터를 사용자 개입 없이 일괄적으로 자동 처리하는 방식.

## 1. 개요

### 1.1 필요 상황

- 대량 데이터 정제 및 가공
- 정기적인 야간 정산 작업
- 대규모 리포트 생성 및 통계 집계
- 시스템 간 데이터 마이그레이션

### 1.2 이점

- 강력한 트랜잭션 관리 및 커밋 지점 설정
- Skip / Retry 정책을 통한 예외 처리
- 작업 상태 모니터링 및 로깅 자동화
- 실패 지점부터의 안전한 재실행(Restart)

### 1.3 단순 Scheduled와의 차이

Spring Batch는 단순 실행을 넘어, **실패 지점부터의 재처리**나 대용량 데이터를 효율적으로 다루기 위한 **청크 단위 커밋** 같은 전문 기능을 완성된 형태로 제공한다는 점이 가장 큰 차이다.

## 2. 아키텍처

### 2.1 핵심 3계층

> 상위 계층은 하위 계층의 서비스를 활용하여 동작한다.

| 계층 | 역할 |
|------|------|
| **Application Layer** | 사용자 작성 batch Job 및 커스텀 비즈니스 로직 |
| **Batch Core Layer** | Job, Step, JobLauncher 등 런타임 제어 API |
| **Infrastructure Layer** | Reader, Writer, Retry 등 기술적 공통 서비스 |

### 2.2 도메인 구조

| 개념 | 설명 |
|------|------|
| **Job** | 전체 배치 프로세스의 설계도. 무엇을 수행할지 정의 |
| **JobParameters** | Job과 함께 결합되어 실행되는 파라미터 |
| **JobInstance** | JobParameters가 조금이라도 다르면 완전히 새로운 인스턴스가 생성됨 |
| **JobExecution** | 하나의 JobInstance가 실제로 실행될 때마다 생성되는 객체. 성공/실패, 시작 시간 등 상태 정보를 담음 |

> 예: 어제 배치가 실패해 오늘 다시 실행하면, **같은 JobInstance** 내에 **두 개의 JobExecution**이 생긴다.

### 2.3 Step

- Job을 구성하는 개별 처리 단계
- 비즈니스 로직을 캡슐화
- 독립적인 실행 제어가 가능

**StepExecution** (Step 실행 시 생성되는 메타데이터)

- Read / Write / Skip Count 기록
- StartTime, EndTime, Status 저장

**Step 종류**

| 종류 | 용도 | 흐름 |
|------|------|------|
| **Chunk Step** | 대용량 처리 | ItemReader → ItemProcessor → ItemWriter |
| **Tasklet Step** | 단일 작업 | 단일 태스크 수행 (파일 삭제, DB 프로시저 호출 등) |

**Step 흐름 제어 유형**: Sequential / Conditional / Flow / Split / Partitioning

## 3. Chunk 지향 처리 (Chunk-oriented Processing)

데이터를 한 건씩 읽고 처리한 후 바로 쓰는 게 아니라, `commit-interval` 설정 값만큼 **모아서 한 번에 커밋**한다. 이 단위를 **Chunk**라고 부른다.

| 컴포넌트 | 동작 |
|----------|------|
| **ItemReader** | 데이터 소스에서 1건씩 읽기 반복 |
| **ItemProcessor** | 읽은 데이터를 비즈니스 로직으로 변환 |
| **ItemWriter** | Chunk 단위로 모인 데이터를 일괄 저장 |

- **Chunk Size = commit-interval**
- 처리 중간에 오류가 발생하면 해당 **Chunk 전체가 롤백(rollback)** 된다.

**예외 처리**

- 예외 발생 시 **Skip**으로 해당 데이터만 건너뛰거나, **Retry**로 다시 시도할 수 있다.
- Skip된 데이터는 `ItemProcessListener`나 `ItemWriteListener`를 통해 별도 로그를 남기거나 DB에 기록하는 게 일반적이다.
- 전용 테이블(**Dead Letter Table**)에 일단 모아두고, 나중에 운영팀이 수동으로 재처리하거나 원인을 분석한다.

## 4. Tasklet Step

- 단일 태스크 수행을 위한 인터페이스
- `execute()` 메서드 하나만 구현
- 복잡한 로직이 없는 단발성 작업

**활용 예시**

- 파일 삭제, 디렉토리 생성 등 작업
- 알림 발송 (Email, Slack 등)
- DB 초기화 및 데이터 사전 검증

## 5. 메타데이터 / 파라미터

### 5.1 JobParameters

- JobInstance 식별 및 파라미터 전달

### 5.2 ExecutionContext

- 실행 상태 저장 (Map 구조)
- 데이터 공유: Job ↔ Step, Step ↔ Step
- 직렬화(Serializable) 필수

> DB에 저장되므로 불필요하게 큰 객체 저장은 지양한다.

## 6. 예외 처리 메커니즘

### 6.1 Skip 메커니즘

- 특정 예외 시 해당 아이템 건너뛰기
- `skip-limit`: 최대 허용 횟수 설정
- `skippable-exception`: 예외 클래스 등록
- `no-skip-exception`: 제외할 예외 설정

### 6.2 Retry 메커니즘

- 일시적 오류 시 동일 작업 재시도
- `retry-limit`: 최대 재시도 횟수
- `backoff-policy`: 재시도 간격 제어
- 네트워크 지연, DB 데드락 처리에 유용
