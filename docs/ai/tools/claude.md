# Claude Code

## 잘 만드는 방법 (엔지니어용 실전 가이드)

이 문서는 단순 사용 팁이 아니라, **반복 가능하고 확장 가능한 개발 시스템**을 구축하기 위한 실행 가이드다.
핵심은 "잘 쓰는 것"이 아니라 **작업 방식을 코드화하고 자동화하는 것**이다.

## 1단계: End-to-End 한 사이클 완주 (Execution First)

목표: 불완전해도 좋으니 **아이디어 → 동작하는 결과물 → 배포/실행**까지 한 번 끝낸다.

원칙:

- Scope를 강제로 줄인다 (MVP)
- 외부 의존 최소화
- “동작”을 기준으로 완료 정의 (DoD)

체크리스트:

- [ ] 실행 가능한 entrypoint 존재 (CLI / HTTP / UI)
- [ ] 실패 케이스 최소 1개 처리
- [ ] 로그/에러 확인 가능

산출물:

- 동작하는 코드 + 실행 방법 README

## 2단계: 프로젝트 부트스트랩 자동화 (Bootstrap as Code)

목표: 초기 설정을 매번 손으로 하지 않고 **명령 기반으로 재현 가능하게 만든다**.

구성:

- `/init` : 프로젝트 생성
- `/dev` : 개발 환경 실행
- `/build` : 빌드
- `/test` : 테스트

권장 구조:

```
project/
  src/
  tests/
  scripts/
  .claude/
    commands/
```

핵심:

- 명령어 = 문서
- 실행 흐름을 숨기지 말고 노출

## 3단계: 명세 기반 개발 (Spec-Driven Development)

구현 전에 반드시 명세를 작성한다.

템플릿:

```
[Feature]
- 목적:
- 입력:
- 출력:

[Success Criteria]
- ...

[Failure Cases]
- ...

[Constraints]
- 성능, 비용, 외부 API 등
```

프롬프트 작성 규칙:

- 모호한 단어 금지 ("적당히", "가능하면")
- 수치화 (latency, size, timeout)
- 예제 포함 (input/output 샘플)

보너스:

- 계약 테스트(contract test)로 변환 가능

## 4단계: 설계-구현 동시 진행 (Iterative Architecture)

Big Design Up Front 금지.

사이클:

1. 최소 구조 정의
2. 구현
3. 병목/문제 발견
4. 리팩토링

패턴:

- Feature Slice 우선
- 이후 Layer 분리

기술 포인트:

- 인터페이스 먼저 정의
- 의존성 역전 (DI)
- 테스트 가능한 구조 유지

## 5단계: Skills로 작업 단위 추상화 (Operational Abstraction)

반복 작업을 "사람의 기억"이 아니라 **명령/스킬**로 승격한다.

대상:

- 코드 생성 패턴
- 테스트 작성
- 리팩토링 규칙
- 코드 리뷰 체크

구조:

```
.claude/
  skills/
    generate-api.md
    write-tests.md
    refactor-module.md
```

특성:

- Lazy load (필요 시 로드)
- 명시적 호출 (/skill-name)

효과:

- 품질 편차 감소
- 온보딩 비용 감소

## 6단계: 컨텍스트/메모리 시스템화 (Context as Infrastructure)

문맥을 매번 설명하지 않도록 **기억을 구조화**한다.

핵심 파일:

- `CLAUDE.md` : 프로젝트 규칙
- `ARCHITECTURE.md` : 구조 설명
- `CONVENTIONS.md` : 코드 스타일

포함 내용:

- 디렉토리 역할
- 빌드/테스트 명령
- 사용 라이브러리/프레임워크
- 금지 패턴
- 코드 리뷰 기준

전략:

- 세션 시작 시 자동 로드
- 작업 중 변경 사항 반영 (living doc)

## 7단계: 자동화 + 멀티 에이전트 + CI 통합 (Systemization)

### 7.1 Hooks 기반 자동화

라이프사이클 이벤트에 작업 연결:

- onSave → lint/format
- onCommit → test
- onPR → static analysis

예:

```
pre-commit:
  - lint
  - test
```

---

### 7.2 외부 시스템 연동

- GitHub Actions: CI/CD
- MCP: 도구 연결
- Browser automation: E2E
- Scheduler: 배치 작업

원칙:

- 로컬 → CI 동일 동작
- 명령 재사용

---

### 7.3 Sub Agents / Agent Teams

작업을 역할 단위로 분해:

- Backend Agent
- Frontend Agent
- Test Agent

패턴:

- 컨텍스트 분리
- 인터페이스 기반 통신
- 병렬 실행

주의:

- 구조 없이 에이전트 늘리면 복잡도 폭증

---

### 7.4 Harness (기억 + 실행 환경)

구성:

- Memory: CLAUDE.md
- Skills: 반복 작업
- Hooks: 자동화

목표:

- 사람이 아니라 시스템이 일하게 만들기

---

### 7.5 최종 단계: 개발 시스템으로 승격

반복되는 흐름을 코드 밖으로 이동:

- SDK
- CLI Tool
- CI Pipeline

결과:

- 개인 작업 방식 → 팀/조직 자산

## 핵심 정리

이 가이드는 다음 변화를 목표로 한다:

1. 감 → 명세
2. 수동 → 자동화
3. 개인 역량 → 시스템

최종 상태:

> "잘 만드는 개발자"가 아니라
> "재현 가능한 개발 시스템을 가진 엔지니어"
