# LangChain (TypeScript) 완전 학습 코스

**20개 스텝**으로 LangChain v1을 처음부터 끝까지 익힙니다. 목표는 문법 암기가 아니라 **에이전트를 만들고 운영할 수 있게 되는 것**입니다.

이 코스를 마치면 이렇게 말할 수 있어야 합니다 — "이 에이전트가 왜 저렇게 행동했는지 설명할 수 있고, 고칠 수 있다."

---

## 시작하기 (5분)

```bash
# 1. 실습 프로젝트로 이동
cd docs/reference/langchain/project

# 2. 의존성 설치 (Node.js 22+ 필요)
npm install

# 3. API 키 설정
cp .env.example .env
# .env 를 열어 ANTHROPIC_API_KEY 를 채웁니다

# 4. 첫 예제 실행
npx tsx ../step-01-setup/practice.ts
```

> API 키는 [console.anthropic.com](https://console.anthropic.com) 에서 발급합니다.
> OpenAI 로 하고 싶다면 `OPENAI_API_KEY` 를 채우고 모델 문자열만 바꾸면 됩니다 — 각 스텝에 안내가 있습니다.

자세한 셋업은 [실습 프로젝트 셋업](./project/) 을 보세요.

---

## 커리큘럼

### 1부 — 기초: 모델과 대화 (Step 01~05)

> LangChain을 한 줄도 안 써봤어도 됩니다. 여기서 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 환경 구축과 첫 모델 호출 | 패키지 지형도, `.env`, `"provider:model"`, AIMessage 해부 |
| [02](step-02-chat-models/) | 챗 모델과 파라미터 | temperature/maxTokens, invoke/batch/stream, **토큰과 비용** |
| [03](step-03-messages/) | 메시지와 콘텐츠 블록 | System/Human/AI/Tool, 콘텐츠 블록, **tool_call_id 계약** |
| [04](step-04-prompts/) | 프롬프트 설계와 템플릿 | 템플릿, few-shot, 시스템 프롬프트 원칙, **인젝션** |
| [05](step-05-structured-output/) | 구조화된 출력 | zod, `withStructuredOutput`, `responseFormat`, **`.describe()`가 곧 프롬프트** |

### 2부 — 핵심: 에이전트의 원리 (Step 06~08)

> **이 코스의 심장부입니다.** 프레임워크를 블랙박스로 두지 않습니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [06](step-06-tools/) | 도구(Tool) 정의 | `tool()`, **모델은 도구를 실행하지 않는다**, 설명이 곧 프롬프트 |
| [07](step-07-tool-loop/) | 도구 호출 루프 직접 구현 | **에이전트는 `while` 루프다** — 손으로 만들어 보기 |
| [08](step-08-create-agent/) | createAgent, 첫 에이전트 | 옵션 전부, 실행 흐름 추적, **에이전트 vs 워크플로** |

### 3부 — 실전: 에이전트를 쓸 만하게 (Step 09~15)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [09](step-09-streaming/) | 스트리밍 | streamMode 전부, **tool_call_chunks는 조각나서 온다** |
| [10](step-10-memory/) | 단기 메모리와 스레드 | checkpointer, `thread_id`, 타임트래블 |
| [11](step-11-middleware-builtin/) | 내장 미들웨어 | 요약/재시도/가드레일/HITL — **v1의 핵심 신기능** |
| [12](step-12-middleware-custom/) | 커스텀 미들웨어 | 훅 전체, `wrapModelCall`/`wrapToolCall`, 비용 추적 |
| [13](step-13-hitl/) | Human-in-the-Loop | `interrupt()`, 승인/거부/수정, **재개 시 재실행 함정** |
| [14](step-14-context-runtime/) | 컨텍스트와 런타임 | **지시/상태/컨텍스트/메모리 4구분**, 동적 프롬프트·모델 |
| [15](step-15-long-term-memory/) | 장기 메모리와 Store | Store, 시맨틱 검색, 메모리 유형 3종 |

### 4부 — 고급: 검색과 구조 (Step 16~18)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [16](step-16-retrieval-rag/) | 검색과 RAG | 청킹/임베딩/벡터스토어, **에이전틱 RAG vs 고전 RAG** |
| [17](step-17-langgraph/) | LangGraph 그래프 API | State/Node/Edge, 리듀서, **createAgent의 정체** |
| [18](step-18-multi-agent/) | 멀티 에이전트 | 서브에이전트/핸드오프/라우터, **나누면 생기는 비용** |

### 5부 — 운영 (Step 19~20)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [19](step-19-observability-eval/) | 관측·테스트·평가 | LangSmith 트레이싱, 모델 페이크, **궤적 평가** |
| [20](step-20-production/) | 프로덕션과 종합 프로젝트 | 신뢰성/비용/보안/배포 + 종합 문제와 해설 |

---

## 각 스텝의 구성

```
step-08-create-agent/
├── index.md       ← 교재 본문. 개념 + 예제 + 출력 + 함정/팁
├── practice.ts    ← 교재의 모든 예제를 그대로 담은 실행 파일
├── exercise.ts    ← 연습문제 (문제만)
└── solution.ts    ← 정답 + 해설
```

**권장 학습 방법**

1. `index.md` 를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마세요.
2. 결과가 교재와 다르면 — **정상입니다**(아래 참고). 하지만 *구조*가 다르면 멈추고 원인을 찾으세요.
3. `exercise.ts` 를 풀고 `solution.ts` 로 채점합니다.
4. 다음 스텝으로.

```bash
# 예제 파일 실행
npx tsx docs/reference/langchain/step-08-create-agent/practice.ts
```

---

## ⚠️ 이 코스가 MySQL 코스와 다른 점 — 결과가 매번 다릅니다

MySQL 코스는 "교재의 결과와 여러분 화면의 결과가 정확히 일치합니다" 라고 약속할 수 있었습니다. **여기서는 그럴 수 없습니다.**

LLM 은 확률적입니다. 같은 프롬프트에 같은 모델이라도 매번 다른 문장을 냅니다. `temperature: 0` 도 이걸 완전히 없애지 못합니다(Step 02에서 이유를 다룹니다).

그래서 이 코스의 출력 예시는 이렇게 표기합니다:

> **출력 예시** (모델 응답이므로 매번 다릅니다)

**하지만 구조는 결정적입니다.** 그리고 **이 코스가 진짜로 가르치는 건 구조입니다.**

| 매번 다른 것 (참고만) | 항상 같은 것 (여기에 집중) |
|---|---|
| 모델이 쓴 문장 | 응답 객체의 shape (`content`, `tool_calls`, `usage_metadata`) |
| 에이전트가 도구를 부른 순서 | 도구 호출 → ToolMessage → 재호출 이라는 **프로토콜** |
| 토큰 수 | `usage_metadata` 의 필드 이름 |
| 요약문의 내용 | 스트림 이벤트 타입과 발생 순서 |

결과 문장이 교재와 다르다고 당황하지 마세요. **객체 구조나 이벤트 순서가 다르면** 그때 멈추고 원인을 찾으세요.

---

## 이 코스가 특히 신경 쓴 것

**에러 없이 조용히 잘못 동작하는 것**을 잡는 데 집중했습니다. 타입 에러는 컴파일러가 잡아줍니다. 진짜 위험한 건 **아무 에러 없이 그럴듯하게 틀리는** 에이전트입니다. 예를 들면:

- `checkpointer` 를 안 주고 `thread_id` 만 주면 **메모리가 조용히 사라집니다** (Step 10)
- 모델은 도구를 **실행하지 않습니다** — JSON 을 뱉을 뿐입니다. 실행은 여러분 코드 책임입니다 (Step 06)
- 도구 `description` 이 부실하면 모델이 **그 도구를 아예 안 부릅니다** (Step 06)
- 스트리밍의 `tool_call_chunks` 는 **부분 JSON** 이라 그대로 파싱하면 터집니다 (Step 09)
- `interrupt()` 로 멈춘 뒤 재개하면 **그 이전 코드가 다시 실행됩니다** — 결제가 두 번 됩니다 (Step 13)
- 런타임 컨텍스트를 state 에 넣으면 **체크포인트에 영구 저장됩니다** — 비밀이 샙니다 (Step 14)
- Store 네임스페이스에 `user_id` 를 안 넣으면 **사용자끼리 메모리가 섞입니다** (Step 15)
- 재시도 미들웨어가 **비멱등 도구를 재실행**하면 중복 결제가 됩니다 (Step 11, 20)
- 멀티 에이전트가 단일 에이전트보다 **나쁜 경우가 흔합니다** (Step 18)

각 스텝의 `⚠️ 함정` 블록을 특히 눈여겨 보세요.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Node.js | 22+ |
| 언어 | TypeScript (ESM) |
| 실행기 | `tsx` |
| 기본 모델 | Anthropic (OpenAI 대안 병기) |
| 검증 버전 | `langchain@1.5.3`, `@langchain/core@1.2.3`, `@langchain/langgraph@1.4.8` |

> **v0 코드를 보신 적 있다면 주의하세요.** LangChain v1 은 v0 과 API 가 크게 다릅니다.
> 인터넷의 `LLMChain`, `initializeAgentExecutorWithOptions`, `RunnableSequence` 중심 예제는 대부분 v0 입니다.
> 이 코스는 전부 v1 기준이며, 모든 API 는 [공식 문서](https://docs.langchain.com/oss/javascript/langchain/overview)로 대조했습니다.

---

## 다음 코스

이 코스를 마쳤다면 → **[Deep Agents (TypeScript) 코스](../deepagent/)**

LangChain 으로 만든 에이전트에게 "리서치 보고서를 써줘" 같은 **긴 작업**을 시키면 왜 실패하는지, 그리고 계획·파일시스템·서브에이전트로 어떻게 해결하는지를 다룹니다.

## 관련 문서

- [AI — 에이전트 개요](/ai/05-agent/01-agent)
- [AI — 에이전트 패턴](/ai/05-agent/02-agentPattern)
- [AI — RAG](/ai/04-rag/01-rag)
- [AI — Function Calling](/ai/50-FunctionCalling)
