# Agent 오픈소스 모음

Agent를 구성하는 관심사별로 오픈소스를 정리합니다. 하나의 라이브러리가 여러 분류에 걸치는 경우 중복해서 나열합니다.

## Context

Agent가 참조하는 지식·컨텍스트를 저장·검색·계층화해서 공급.

| 라이브러리 | 설명 |
|---|---|
| [volcengine/OpenViking](https://github.com/volcengine/OpenViking) | 지식·메모리·스킬을 통합한 Agent용 컨텍스트 DB. `viking://` 가상 파일시스템으로 `ls`·`grep`·`tree`처럼 탐색 가능. L0(요약)/L1(개요)/L2(전체) 계층 로딩으로 필요할 때만 상세를 읽는다. |
| [Egonex-AI/Understand-Anything](https://github.com/Egonex-AI/Understand-Anything) | 코드베이스를 대화형 지식 그래프로 변환. 구조·비즈니스 로직·의존 관계를 그래프로 만들어 Agent가 질의·탐색할 컨텍스트로 제공한다. |

## Task

작업 큐, 작업 단위 관리·재시도.

| 라이브러리 | 설명 |
|---|---|
| [crewAIInc/crewAI](https://github.com/crewAIInc/crewAI) | 자율 Agent 오케스트레이션 프레임워크. `Task`가 Agent에게 배정되는 핵심 작업 단위이며, 역할 기반 Crew(자율 팀)와 이벤트 기반 Flow(정밀 제어) 두 방식을 함께 제공한다. |

## Goal

목표 정의·추적, 목표 변경 시 영향 전파.

| 라이브러리 | 설명 |
|---|---|
| _추가 예정_ | 목표 정의·추적 전용 오픈소스는 아직 확신할 만한 후보가 없다. 아는 게 있으면 알려주면 채워 넣겠다. |

## Compress

컨텍스트/이력 압축.

| 라이브러리 | 설명 |
|---|---|
| [microsoft/LLMLingua](https://github.com/microsoft/LLMLingua) | 프롬프트·KV-Cache를 압축해 최대 20배까지 줄이면서 성능 손실을 최소화하는 압축 도구. 추론 속도 향상과 핵심 정보 보존을 동시에 노린다. |

## Memory

장단기 기억 저장·회수.

| 라이브러리 | 설명 |
|---|---|
| [mem0ai/mem0](https://github.com/mem0ai/mem0) | Agent·앱에 바로 꽂아 쓰는 메모리 인프라. 대화에서 사용자 선호·세션 상태·누적 지식을 추출해 저장하고 필요한 기억만 회수한다. |
| [getzep/zep](https://github.com/getzep/zep) | 관리형(managed) Agent 메모리 플랫폼. 오픈소스 temporal knowledge graph 엔진 Graphiti를 기반으로 장기 메모리와 시점별 사실 변화를 함께 추적한다. |
| [letta-ai/letta](https://github.com/letta-ai/letta) | 구 MemGPT. "시간이 지나며 학습·개선하는 메모리를 가진 상태 기반 Agent"를 만드는 프레임워크로, OS의 가상 메모리 페이징에서 착안한 컨텍스트 관리가 특징이다. |
| [topoteretes/cognee](https://github.com/topoteretes/cognee) | 문서·코드·대화를 자체 호스팅 지식 그래프 엔진에 저장해 세션을 넘나드는 장기 메모리로 제공. `remember`(저장)/`recall`(회수)/`improve`(보강) 3개 동작으로 구성된다. |
