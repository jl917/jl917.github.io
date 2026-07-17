# Step 11 — 내장 미들웨어

> **학습 목표**
> - LangChain v0 의 한계를 짚고, **v1 이 왜 미들웨어를 도입했는지** 설명한다
> - 에이전트 루프의 6개 훅(`beforeAgent` / `beforeModel` / `wrapModelCall` / `wrapToolCall` / `afterModel` / `afterAgent`)이 **언제** 불리는지 안다
> - `before` 는 순서대로, `after` 는 **역순**, `wrap` 은 **중첩**된다는 실행 규칙을 이해한다
> - 내장 미들웨어 **전체 카탈로그**에서 필요한 것을 골라 쓴다
> - `summarizationMiddleware` 로 긴 대화를 자동 압축하고, **무엇이 사라지는지** 안다
> - `modelRetryMiddleware` / `toolRetryMiddleware` / `modelFallbackMiddleware` 로 장애에 대응한다
> - `piiMiddleware` 로 가드레일을 걸고, **마스킹이 무용지물이 되는 지점**을 피한다
>
> **선행 스텝**: [Step 10 — 단기 메모리와 스레드](../step-10-memory/)
> **예상 소요**: 80분

[Step 08](../step-08-create-agent/) 에서 `createAgent` 한 줄로 에이전트를 만들었습니다. 편했지만 대신 **루프 안쪽이 블랙박스**가 되었습니다. 모델을 부르기 직전에 대화를 줄이고 싶다, 도구가 실패하면 재시도하고 싶다, 사용자 입력에서 카드번호를 지우고 싶다 — 이런 요구가 생기면 `createAgent` 의 옵션만으로는 손이 닿지 않습니다.

**미들웨어(middleware)는 LangChain v1 의 가장 중요한 신기능입니다.** 블랙박스였던 에이전트 루프의 각 지점에 훅(hook)을 걸어, 루프를 다시 짜지 않고도 동작을 바꿉니다. 이 스텝에서는 **LangChain 이 기본 제공하는 미들웨어**를 전부 훑고, 다음 [Step 12](../step-12-middleware-custom/) 에서 직접 만듭니다.

> **검증 버전**: `langchain@1.5.3`, `@langchain/core@1.2.3`, `@langchain/anthropic@1.5.1`, `@langchain/langgraph@1.4.8`
> 이 문서의 모든 시그니처는 위 버전의 타입 정의에서 확인한 것입니다.

---

## 11-1. 미들웨어가 왜 필요한가 — v0 의 한계

### v0 에서는 어떻게 했나

LangChain v0 에는 `AgentExecutor` 라는 것이 있었습니다. 에이전트 루프가 그 안에 통째로 들어 있었고, 밖에서 건드릴 수 있는 건 생성자 옵션 몇 개뿐이었습니다. "모델 호출 직전에 메시지를 줄이고 싶다" 같은 요구가 생기면 선택지는 셋이었습니다.

| v0 의 선택지 | 문제 |
|---|---|
| 옵션이 생기길 기다린다 | `maxIterations`, `earlyStoppingMethod` 처럼 **미리 정해둔 것만** 됨. 새 요구마다 프레임워크에 옵션이 하나씩 늘어남 |
| `AgentExecutor` 를 상속해서 오버라이드 | 내부 구현에 결합됨. 라이브러리가 업데이트되면 깨짐. 두 가지를 동시에 하려면 상속이 꼬임 |
| 루프를 직접 짠다 ([Step 07](../step-07-tool-loop/)) | 다 되지만 **전부 다 내가 해야 함**. 재시도, 요약, 승인을 매번 다시 구현 |

세 번째가 현실적인 답이었고, 그래서 실무 코드베이스마다 "우리 회사 에이전트 루프"가 따로 있었습니다. 그런데 그 루프들이 하는 일은 대체로 똑같았습니다 — 요약하고, 재시도하고, 승인받고, 로그 찍고.

### v1 의 답 — 루프는 고정, 지점을 연다

v1 은 루프를 다시 열어주는 대신 **루프의 각 지점에 훅을 거는 방식**을 택했습니다. 루프 자체는 `createAgent` 가 관리하고, 여러분은 "모델 호출 직전"이라는 **지점**에 함수를 꽂습니다.

```ts
import { createAgent, summarizationMiddleware, piiMiddleware } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchTool],
  middleware: [
    summarizationMiddleware({ model: "anthropic:claude-haiku-4-5", trigger: { tokens: 4000 } }),
    piiMiddleware("email", { strategy: "redact" }),
  ],
});
```

이게 v0 대비 바뀐 지점입니다.

- **조합 가능(composable)**: 요약과 PII 마스킹을 동시에 쓰려고 상속을 꼬지 않습니다. 배열에 나란히 넣습니다.
- **재사용 가능**: `summarizationMiddleware` 는 LangChain 이 만들어 놓은 것을 그대로 씁니다. 우리 회사 루프에 다시 구현하지 않습니다.
- **탈착 가능**: 배열에서 빼면 없어집니다. 미들웨어를 걷어내도 에이전트는 그대로 돕니다.

> 💡 **실무 팁**: 미들웨어는 **별도 런타임이 아닙니다.** 공식 문서 표현대로 "hooks run inside the compiled LangGraph" — 훅은 컴파일된 LangGraph 안에서 실행됩니다. 즉 미들웨어를 붙인다고 프록시 계층이 하나 더 생기거나 오버헤드가 붙는 구조가 아니라, 여러분의 함수가 그래프의 노드로 편입되는 것입니다. 그래서 미들웨어 안에서 상태(`state`)를 읽고 쓰는 게 자연스럽고, 체크포인터([Step 10](../step-10-memory/))와도 그대로 맞물립니다.

---

## 11-2. 미들웨어 실행 순서와 생명주기

미들웨어가 걸 수 있는 지점은 **6개**입니다. 타입 정의(`langchain/dist/agents/middleware/types.d.ts`)에서 확인한 정확한 이름입니다.

| 훅 | 종류 | 언제 불리나 | 대표 용도 |
|---|---|---|---|
| `beforeAgent` | 노드형 | 에이전트 실행 **시작 시 1회** | 입력 검증, 차단, 초기 상태 세팅 |
| `beforeModel` | 노드형 | **매 모델 호출 직전** (`wrapModelCall` 보다 먼저) | 대화 요약, 메시지 정리 |
| `wrapModelCall` | 래핑형 | 모델 호출을 **감싼다** | 재시도, 폴백, 모델 교체 |
| `wrapToolCall` | 래핑형 | 도구 호출을 **감싼다** | 도구 재시도, 결과 후처리 |
| `afterModel` | 노드형 | **매 모델 응답 직후** | 출력 검증, PII 마스킹 |
| `afterAgent` | 노드형 | 에이전트 종료 **직전 1회** | 최종 응답 검사, 로깅 |

`beforeAgent` / `beforeModel` 의 차이가 헷갈립니다. **`beforeAgent` 는 실행당 1회, `beforeModel` 은 루프를 돌 때마다**입니다. 도구를 3번 부르는 대화라면 `beforeModel` 은 4번 불리고 `beforeAgent` 는 1번 불립니다.

### 한 번의 실행에서 훅이 불리는 순서

```
agent.invoke()
  │
  ├─ beforeAgent            ← 1회
  │
  ├─┬─ [루프 시작] ──────────────────────────┐
  │ │                                        │
  │ ├─ beforeModel          ← 매 턴          │
  │ ├─ wrapModelCall ─→ (모델) ─→ 되돌아옴    │
  │ ├─ afterModel           ← 매 턴          │
  │ │                                        │
  │ ├─ (도구 호출이 있으면)                   │
  │ │   wrapToolCall ─→ (도구) ─→ 되돌아옴   │
  │ │   └─ 다시 루프 시작 ────────────────────┘
  │ │
  │ └─ (도구 호출이 없으면 루프 종료)
  │
  └─ afterAgent             ← 1회
```

### 여러 개를 넣으면 — 이게 이 스텝의 핵심

`middleware: [A, B, C]` 로 3개를 넣으면 순서 규칙이 **훅 종류마다 다릅니다.**

| 훅 종류 | 실행 순서 | 그림 |
|---|---|---|
| `beforeAgent`, `beforeModel` | **배열 순서대로** (앞→뒤) | `A → B → C` |
| `wrapModelCall`, `wrapToolCall` | **중첩** (앞이 바깥) | `A( B( C( 모델 ) ) )` |
| `afterModel`, `afterAgent` | **역순** (뒤→앞) | `C → B → A` |

양파 껍질을 생각하면 전부 하나의 규칙입니다. **배열 앞쪽일수록 바깥 껍질입니다.** 들어갈 때는 바깥부터(A→B→C), 나올 때는 안쪽부터(C→B→A). 웹 프레임워크의 미들웨어 스택(Express, Koa)과 같은 모델입니다.

```ts
middleware: [A, B, C]

// 들어갈 때        나올 때
A.beforeModel       C.afterModel
  B.beforeModel     B.afterModel
    C.beforeModel   A.afterModel
      (모델)
```

> ⚠️ **함정 (순서가 결과를 바꾼다)**: `after` 훅이 **역순**이라는 걸 모르면 조합이 조용히 틀립니다. `middleware: [마스킹, 로깅]` 이라고 쓰면 "마스킹하고 나서 로깅하겠지"라고 읽히지만, `afterModel` 기준으로는 **로깅이 먼저 돌고 마스킹이 나중**입니다. 즉 **로그에는 마스킹 안 된 원본이 남습니다.** 에러도 경고도 없이 로그 파일에만 카드번호가 쌓입니다. `after` 훅에서 "A 다음에 B"를 원하면 배열에는 `[B, A]` 로 적어야 합니다. 11-7 에서 다시 다룹니다.

> 💡 **실무 팁**: 순서를 외우지 말고 **의도를 적으세요.** 배열 위쪽에 주석으로 `// 바깥 껍질 ← 가장 먼저 입력을 보고, 가장 나중에 출력을 본다` 라고 한 줄 남기면 리뷰어가 헷갈리지 않습니다. 헷갈릴 때는 각 미들웨어에 `console.log` 를 심어 한 번 돌려보는 게 가장 빠릅니다 — `practice.ts` 의 `[11-2]` 블록이 정확히 그걸 합니다.

---

## 11-3. 내장 미들웨어 카탈로그

`langchain@1.5.3` 이 루트(`import { ... } from "langchain"`)에서 내보내는 미들웨어 **전부**입니다. 설치된 패키지의 `dist/agents/index.d.ts` 를 직접 확인한 목록입니다.

### 컨텍스트 관리

| 미들웨어 | 하는 일 | 주요 훅 |
|---|---|---|
| `summarizationMiddleware` | 대화가 길어지면 오래된 메시지를 **요약으로 압축** | `beforeModel` |
| `contextEditingMiddleware` | 오래된 **도구 결과를 잘라냄** (`ClearToolUsesEdit`) | `beforeModel` |
| `todoListMiddleware` | `write_todos` 도구를 추가해 **계획을 세우게** 함 | 도구 추가 |

### 장애 대응

| 미들웨어 | 하는 일 | 주요 훅 |
|---|---|---|
| `modelRetryMiddleware` | 모델 호출 실패 시 **지수 백오프 재시도** | `wrapModelCall` |
| `toolRetryMiddleware` | 도구 실행 실패 시 **지수 백오프 재시도** | `wrapToolCall` |
| `modelFallbackMiddleware` | 모델이 죽으면 **다음 모델로 넘어감** | `wrapModelCall` |

### 제한 / 안전

| 미들웨어 | 하는 일 | 주요 훅 |
|---|---|---|
| `modelCallLimitMiddleware` | 모델 호출 **횟수 상한** (무한 루프 방지) | `beforeModel` |
| `toolCallLimitMiddleware` | 도구 호출 **횟수 상한** (도구별 지정 가능) | `wrapToolCall` |
| `piiMiddleware` | PII 탐지 후 **차단/삭제/마스킹/해시** | `beforeModel` / `afterModel` |
| `piiRedactionMiddleware` | 정규식 규칙(`rules`)으로 간단 마스킹 | `beforeModel` |
| `humanInTheLoopMiddleware` | 위험한 도구 호출 전 **사람 승인** | `wrapToolCall` |

### 도구 / 프롬프트 조작

| 미들웨어 | 하는 일 | 주요 훅 |
|---|---|---|
| `llmToolSelectorMiddleware` | 도구가 너무 많을 때 **LLM 이 후보를 추림** | `wrapModelCall` |
| `toolEmulatorMiddleware` | 도구를 **실행하지 않고 LLM 이 흉내** (테스트용) | `wrapToolCall` |
| `providerToolSearchMiddleware` | 도구를 **제공자 측 검색 뒤로 숨김** | `wrapModelCall` |
| `dynamicSystemPromptMiddleware` | 상태/런타임에 따라 **시스템 프롬프트를 매번 새로** | `wrapModelCall` |

### 제공자 전용

| 미들웨어 | 하는 일 |
|---|---|
| `anthropicPromptCachingMiddleware` | Anthropic 프롬프트 캐싱 |
| `bedrockPromptCachingMiddleware` | AWS Bedrock Converse 프롬프트 캐싱 |
| `openAIModerationMiddleware` | OpenAI Moderation API 로 입출력 검사 |

전부 `import { ... } from "langchain"` 한 줄로 가져옵니다. 함께 나오는 보조 심볼도 같은 곳에서 옵니다: `ClearToolUsesEdit`, `PIIDetectionError`, `ToolCallLimitExceededError`, `countTokensApproximately`.

> ⚠️ **함정 (공식 문서와 실제 API 가 다른 곳이 있다)**: 이 카탈로그를 만들며 공식 문서와 `langchain@1.5.3` 의 실제 타입이 **어긋나는 지점**을 몇 개 발견했습니다.
> - 가드레일 문서는 `piiRedactionMiddleware({ piiType, strategy })` 를 보여주지만, 실제 `piiRedactionMiddleware` 는 **`{ rules: Record<string, RegExp> }` 만 받습니다.** `piiType`/`strategy` 를 쓰려면 **`piiMiddleware`** 입니다.
> - HITL 문서 일부는 `allowAccept` / `allowEdit` / `allowRespond` 를 보여주지만 이건 **deprecated** 이고, 현재는 `allowedDecisions: ["approve", "edit", "reject"]` 입니다.
> - 문서는 결정 타입이 4개(`respond` 포함)라고 하지만, 실제 enum 은 **3개**(`approve` / `edit` / `reject`)입니다.
> - `contextEditingMiddleware` 문서의 `triggerTokens` / `keepMessages` 는 **deprecated** 이고 지금은 `trigger: { tokens }` / `keep: { messages }` 입니다.
>
> **버전이 올라가면 문서보다 `node_modules` 의 `.d.ts` 가 먼저 진실입니다.** 에디터에서 함수 이름 위에 커서를 올려 시그니처를 확인하는 습관을 들이세요. 문서를 그대로 베끼면 `tsc` 는 통과하는데(옵션 객체가 `strip` 모드라 모르는 키를 조용히 버림) **런타임에 아무 일도 안 일어나는** 상황을 만납니다.

### 가장 짧은 예제 모음

각각을 최소 형태로 한 번씩 보겠습니다. 자세한 것은 11-4 이후에 다룹니다.

```ts
import {
  createAgent,
  summarizationMiddleware,
  contextEditingMiddleware,
  ClearToolUsesEdit,
  todoListMiddleware,
  modelRetryMiddleware,
  toolRetryMiddleware,
  modelFallbackMiddleware,
  modelCallLimitMiddleware,
  toolCallLimitMiddleware,
  piiMiddleware,
  llmToolSelectorMiddleware,
  toolEmulatorMiddleware,
  dynamicSystemPromptMiddleware,
} from "langchain";

// 1. 요약 — 4000 토큰 넘으면 압축, 최근 20개는 보존
summarizationMiddleware({
  model: "anthropic:claude-haiku-4-5",
  trigger: { tokens: 4000 },
  keep: { messages: 20 },
});

// 2. 컨텍스트 편집 — 오래된 도구 결과를 "[cleared]" 로 치환
contextEditingMiddleware({
  edits: [new ClearToolUsesEdit({ trigger: { tokens: 100000 }, keep: { messages: 3 } })],
});

// 3. 할 일 목록 — write_todos 도구가 추가된다
todoListMiddleware();

// 4. 모델 재시도 — 3번까지, 1초에서 2배씩
modelRetryMiddleware({ maxRetries: 3, initialDelayMs: 1000, backoffFactor: 2.0 });

// 5. 도구 재시도 — 특정 도구에만
toolRetryMiddleware({ maxRetries: 2, tools: ["search_web"] });

// 6. 모델 폴백 — 가변 인자다 (배열 아님!)
modelFallbackMiddleware("openai:gpt-5.5", "anthropic:claude-haiku-4-5");

// 7. 모델 호출 횟수 상한
modelCallLimitMiddleware({ threadLimit: 20, runLimit: 8, exitBehavior: "end" });

// 8. 도구 호출 횟수 상한
toolCallLimitMiddleware({ toolName: "search_web", runLimit: 3, exitBehavior: "continue" });

// 9. PII — 첫 인자가 타입, 둘째가 옵션 (객체 하나 아님!)
piiMiddleware("credit_card", { strategy: "mask", applyToInput: true });

// 10. 도구 선별 — 도구가 50개일 때 LLM 이 3개로 추림
llmToolSelectorMiddleware({ model: "anthropic:claude-haiku-4-5", maxTools: 3, alwaysInclude: ["search_web"] });

// 11. 도구 에뮬레이터 — 실제로 안 부르고 LLM 이 그럴듯한 결과를 지어낸다 (테스트용)
toolEmulatorMiddleware({ tools: ["charge_payment"] });

// 12. 동적 시스템 프롬프트 — 매 모델 호출마다 새로 만든다
dynamicSystemPromptMiddleware((state) => `현재 메시지 ${state.messages.length}개. 간결히 답하라.`);
```

> ⚠️ **함정 (인자 모양이 미들웨어마다 다르다)**: 위 목록에서 두 개가 튑니다.
> - **`modelFallbackMiddleware` 는 가변 인자**입니다: `modelFallbackMiddleware(a, b)`. 배열로 `modelFallbackMiddleware([a, b])` 를 넘기면 "배열 한 개"를 모델로 취급합니다.
> - **`piiMiddleware` 는 첫 인자가 문자열**입니다: `piiMiddleware("email", { strategy })`. `piiMiddleware({ piiType: "email", strategy })` 라고 쓰면 첫 인자가 객체라 타입 에러가 나거나, 커스텀 PII 타입 이름으로 오해받습니다.
>
> 나머지는 전부 옵션 객체 하나를 받습니다. 이 두 개만 예외라고 기억하세요.

---

## 11-4. 요약 미들웨어 심화 — 긴 대화 자동 압축

대화가 길어지면 컨텍스트 윈도우에 부딪힙니다. `summarizationMiddleware` 는 `beforeModel` 에서 토큰을 세고, 임계치를 넘으면 **오래된 메시지를 LLM 으로 요약해 한 덩어리로 치환**합니다.

```ts
import { createAgent, summarizationMiddleware } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchTool],
  middleware: [
    summarizationMiddleware({
      model: "anthropic:claude-haiku-4-5",  // 요약은 싼 모델로
      trigger: { tokens: 4000 },            // 4000 토큰 넘으면 발동
      keep: { messages: 20 },               // 최근 20개는 원본 유지
    }),
  ],
});
```

### 옵션 전체

| 옵션 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `model` | `string \| BaseLanguageModel` | **필수** | 요약을 만들 모델 |
| `trigger` | `ContextSize \| ContextSize[]` | — | 발동 조건. `{ fraction }` / `{ tokens }` / `{ messages }` |
| `keep` | `KeepSize` | `{ messages: 20 }` | 요약 후 남길 분량. `{ fraction }` / `{ tokens }` / `{ messages }` |
| `tokenCounter` | `(msgs) => number \| Promise<number>` | 근사 계산 | 토큰 세는 함수 |
| `summaryPrompt` | `string` | 내장 프롬프트 | 요약 지시문 |
| `trimTokensToSummarize` | `number` | `4000` | 요약 생성에 넣을 최대 토큰 |
| `summaryPrefix` | `string` | — | 요약 메시지 앞에 붙일 문구 |

`fraction` 은 **모델 컨텍스트 크기의 비율**입니다. `trigger: { fraction: 0.8 }` 이면 "이 모델 컨텍스트의 80% 를 채우면 발동". 모델을 바꿔도 알아서 따라오므로 토큰 수를 직접 박는 것보다 안전합니다.

### `trigger` 의 AND / OR — 문서에 잘 안 보이는 규칙

```ts
// (A) 객체 하나 → AND. 토큰 4000 이상 "그리고" 메시지 10개 이상일 때만 발동
trigger: { tokens: 4000, messages: 10 }

// (B) 배열 → OR. 둘 중 하나만 넘어도 발동
trigger: [{ tokens: 4000 }, { messages: 10 }]
```

타입 정의의 주석이 명시합니다 — *"Single condition: trigger if tokens >= 4000 AND messages >= 10"*, *"Multiple conditions: trigger if (...) OR (...)"*.

> ⚠️ **함정 (AND 를 OR 로 착각한다)**: `trigger: { tokens: 4000, messages: 10 }` 를 "4000 토큰이거나 10개 메시지면 요약"으로 읽으면 **틀립니다. 둘 다** 만족해야 발동합니다. 메시지 3개짜리 대화에 8000 토큰이 들어차 있으면(긴 문서를 붙여넣은 경우) `messages: 10` 을 못 넘겨서 **요약이 아예 안 돕니다.** 그리고 컨텍스트를 초과하면 조용히 잘리는 게 아니라 **제공자가 에러를 던집니다.** "요약 미들웨어를 붙였는데 왜 컨텍스트 초과 에러가 나지?"의 대표 원인입니다. OR 를 원하면 반드시 **배열**로 쓰세요.

### 무엇이 사라지는가

요약은 **손실 압축**입니다. 오래된 메시지가 자연어 요약 한 덩어리로 바뀝니다. 여기서 조용히 사라지는 게 있습니다.

**요약 전**
```
HUMAN  │ 서울 날씨 알려줘
AI     │ → tool get_weather({"city":"서울"})
TOOL   │ {"temp": 3, "condition": "맑음"}      ← 구조화된 데이터
AI     │ 서울은 3도, 맑습니다.
... (20턴 더)
```

**요약 후** (모델 응답이므로 매번 다릅니다)
```
SYSTEM │ [이전 대화 요약] 사용자가 서울 날씨를 물었고 3도 맑음이라고 답했다. 이후 ...
HUMAN  │ (최근 20개 메시지는 원본 유지)
```

`get_weather` 를 **이미 불렀다는 사실**과 `{"temp": 3}` 이라는 **정확한 값**이 자연어 문장으로 뭉개졌습니다.

> ⚠️ **함정 (요약이 도구 호출 이력을 지우면 에이전트가 같은 일을 반복한다)**: 이게 요약 미들웨어의 가장 비싼 함정입니다. 요약된 대화에는 `tool_calls` / `ToolMessage` 구조가 남지 않고 "날씨를 조회했다" 같은 **문장**만 남습니다. 모델 입장에서는 "조회했다는 얘기는 있는데 결과 구조가 안 보이니 다시 부르자"가 됩니다. 그래서 **요약 직후 같은 도구를 다시 호출하는** 현상이 나옵니다. 검색 API 라면 비용만 두 배지만, **결제나 이메일 발송처럼 부수효과가 있는 도구라면 중복 실행**입니다.
>
> 방어법:
> 1. `keep: { messages: N }` 을 넉넉히 잡아 **최근 도구 호출 쌍이 원본으로 남게** 한다.
> 2. `summaryPrompt` 를 커스터마이즈해 **"이미 호출한 도구와 그 결과값을 반드시 명시하라"** 를 넣는다.
> 3. 도구 결과만 문제라면 요약 대신 **`contextEditingMiddleware`** 를 쓴다 (아래).
> 4. 부수효과 도구는 **멱등하게** 만든다 (11-5 함정 참조).

> 💡 **실무 팁**: `summarizationMiddleware` 는 AI 메시지와 그에 딸린 ToolMessage 가 **짝으로 붙어 있게** 보장합니다 (타입 정의: *"ensuring AI/Tool message pairs remain together"*). 이건 중요합니다 — `tool_calls` 가 있는 AIMessage 만 남고 대응하는 ToolMessage 가 잘려나가면 제공자가 400 을 던지기 때문입니다([Step 07](../step-07-tool-loop/)의 `tool_call_id` 함정과 같은 뿌리). 그래서 `keep: { messages: 20 }` 이라고 해도 실제로는 짝을 맞추느라 20개보다 조금 더/덜 남을 수 있습니다. **정확히 20개를 기대하지 마세요.**

### 요약 대신 잘라내기 — `contextEditingMiddleware`

문제가 "대화 전체"가 아니라 "도구 결과가 너무 김"이라면 요약보다 이쪽이 낫습니다. 오래된 **도구 결과만** 골라 `"[cleared]"` 로 바꿉니다. LLM 을 부르지 않으므로 **공짜이고 빠릅니다.**

```ts
import { createAgent, contextEditingMiddleware, ClearToolUsesEdit } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchTool],
  middleware: [
    contextEditingMiddleware({
      edits: [
        new ClearToolUsesEdit({
          trigger: { tokens: 100000 },   // 10만 토큰 넘으면
          keep: { messages: 3 },         // 최근 도구 결과 3개는 보존
          clearToolInputs: false,        // 도구 "인자"는 남긴다 (기본값)
          excludeTools: ["get_user_profile"],  // 이 도구 결과는 절대 안 지움
          placeholder: "[cleared]",      // 치환 문구
        }),
      ],
    }),
  ],
});
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `trigger` | `{ tokens: 100000 }` | 발동 조건 (`fraction`/`tokens`/`messages`, 배열이면 OR) |
| `keep` | `{ messages: 3 }` | 보존할 최근 도구 결과 |
| `clearToolInputs` | `false` | AI 메시지의 도구 **인자**까지 지울지 |
| `excludeTools` | `[]` | 예외 도구 이름 |
| `placeholder` | `"[cleared]"` | 치환 문구 |

**요약 vs 컨텍스트 편집**

| | `summarizationMiddleware` | `contextEditingMiddleware` |
|---|---|---|
| 대상 | 대화 전체 | 도구 결과만 |
| 방법 | LLM 으로 요약 | 문자열 치환 |
| 비용 | LLM 호출 발생 | 없음 |
| 속도 | 느림 (모델 왕복) | 즉시 |
| 대화 흐름 | 뭉개짐 | **그대로 남음** |
| 도구 호출 사실 | 사라질 수 있음 | **구조는 남음** (값만 `[cleared]`) |

`clearToolInputs: false` 가 기본인 이유가 여기 있습니다. **"어떤 도구를 어떤 인자로 불렀다"는 사실은 남기고 결과값만 지우는 것** — 그래야 모델이 "이미 불렀구나"를 알고 재호출하지 않습니다. 11-4 의 요약 함정을 구조적으로 피하는 설계입니다.

> 💡 **실무 팁**: 둘 중 하나를 고르라면 **`contextEditingMiddleware` 를 먼저** 시도하세요. 긴 대화의 토큰은 대개 사람 말이 아니라 **도구가 뱉은 JSON 덩어리**입니다. 검색 결과 10건, 파일 전문, DB 조회 결과 — 이것만 걷어내도 대부분 해결되고, 대화 흐름은 손상되지 않으며, 요약 LLM 비용도 안 듭니다. 그래도 넘치면 그때 요약을 얹으세요. 둘을 같이 쓸 수도 있습니다.

---

## 11-5. 재시도와 폴백 — 장애 대응

LLM API 는 **자주 실패합니다.** 429(rate limit), 529(overloaded), 타임아웃, 5xx. 재시도하면 대개 성공합니다.

### 모델 재시도

```ts
import { createAgent, modelRetryMiddleware } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchTool],
  middleware: [
    modelRetryMiddleware({
      maxRetries: 3,          // 최초 호출 "이후" 3번 더 → 최대 4회
      initialDelayMs: 1000,   // 첫 대기 1초
      backoffFactor: 2.0,     // 1s → 2s → 4s
      maxDelayMs: 60000,      // 상한 60초
      jitter: true,           // ±25% 흔들기
      onFailure: "continue",  // 다 실패하면? (기본값)
    }),
  ],
});
```

`maxRetries` 는 **추가 시도 횟수**입니다. `3` 이면 총 4번 부릅니다 (최초 1 + 재시도 3). "3번 시도"로 읽으면 하나 어긋납니다.

`jitter: true` 는 대기 시간을 ±25% 랜덤하게 흔듭니다. 켜 두세요. 여러 요청이 동시에 429 를 맞으면 정확히 같은 시각에 재시도해서 다시 429 를 맞습니다(thundering herd). 지터가 이걸 흩뿌립니다.

### 도구 재시도

```ts
import { toolRetryMiddleware } from "langchain";

toolRetryMiddleware({
  maxRetries: 2,
  tools: ["search_web", "fetch_url"],   // ← 이 도구들에만 적용
  retryOn: (error) => error.message.includes("ETIMEDOUT"),  // 이 에러만 재시도
  onFailure: "continue",
});
```

`onFailure` 는 재시도를 다 소진했을 때의 행동이고, 실제 타입은 이렇습니다.

| 값 | 동작 |
|---|---|
| `"continue"` (기본) | 에러를 **ToolMessage 로 만들어 모델에게 준다** → 모델이 보고 대처 |
| `"error"` / `"raise"` | 예외를 던져 에이전트를 중단 |
| `"return_message"` | 메시지로 되돌린다 |
| `(error) => string` | 직접 포맷팅한 문자열을 결과로 |

`"continue"` 가 기본인 게 중요합니다. 도구가 죽어도 에이전트는 안 죽고, **모델이 에러 메시지를 읽고 "다른 방법을 써보자"** 로 갑니다. 이게 에이전트다운 동작입니다.

`retryOn` 은 `(error) => boolean` 함수이거나 **에러 클래스 배열**입니다.

```ts
retryOn: [TimeoutError, RateLimitError]   // 이 클래스들만 재시도
```

> ⚠️ **함정 (재시도가 비멱등 도구를 재실행하면 중복 결제)**: `toolRetryMiddleware` 는 **도구가 무슨 일을 하는지 모릅니다.** 에러가 나면 그냥 다시 부릅니다. 문제는 **"실패했다"와 "실패한 것처럼 보인다"가 다르다**는 점입니다.
>
> ```
> 1. charge_payment({amount: 50000}) 호출
> 2. 결제 서버가 결제를 "성공적으로 처리"
> 3. 응답을 보내다가 네트워크 타임아웃 ← 클라이언트는 실패로 인식
> 4. toolRetryMiddleware 가 재시도
> 5. charge_payment({amount: 50000}) 다시 호출
> 6. 5만원이 두 번 빠져나감
> ```
>
> 에러도 안 나고, 로그도 깨끗하고, 고객만 화납니다. **읽기 도구(검색, 조회)에는 재시도를 걸고, 쓰기 도구(결제, 발송, 삭제)에는 걸지 마세요.** 그래서 `tools` 옵션이 있는 겁니다 — 전역으로 걸지 말고 **재시도해도 안전한 도구만 화이트리스트**로 지정하세요.
>
> ```ts
> // ❌ 전역 — charge_payment 도 재시도된다
> toolRetryMiddleware({ maxRetries: 3 })
>
> // ✅ 읽기 도구에만
> toolRetryMiddleware({ maxRetries: 3, tools: ["search_web", "get_order"] })
> ```
>
> 쓰기 도구에 꼭 재시도가 필요하다면 **멱등키(idempotency key)** 를 도구 인자에 넣어 서버가 중복을 걸러내게 하세요. 재시도는 같은 인자로 다시 부르므로 멱등키도 같은 값이 되고, 서버가 "이미 처리한 요청"으로 판단할 수 있습니다.

### 모델 폴백

재시도해도 안 되면 **다른 모델**로 넘어갑니다.

```ts
import { modelFallbackMiddleware } from "langchain";

// 가변 인자! 배열이 아니다.
modelFallbackMiddleware(
  "openai:gpt-5.5",              // 1순위 폴백
  "anthropic:claude-haiku-4-5",  // 2순위 폴백
);
```

`createAgent` 의 `model` 이 **주 모델**이고, 여기 적는 것들은 **주 모델이 실패했을 때 순서대로** 시도할 대상입니다. 여기 주 모델을 또 적지 마세요.

### 재시도 + 폴백 조합 — 순서가 중요하다

```ts
middleware: [
  modelFallbackMiddleware("openai:gpt-5.5"),   // 바깥
  modelRetryMiddleware({ maxRetries: 2 }),     // 안쪽
]
```

둘 다 `wrapModelCall` 이고 **앞이 바깥**이므로 `fallback( retry( 모델 ) )` 로 중첩됩니다. 동작은 이렇습니다.

```
Claude 시도 → 실패 → 1초 대기 → 재시도 → 실패 → 2초 대기 → 재시도 → 실패
  → (retry 소진, fallback 이 잡는다)
  → GPT-5.5 시도 → 성공
```

**"한 모델에 충분히 매달려 보고, 그래도 안 되면 갈아탄다"** — 이게 대개 원하는 동작입니다. 순서를 뒤집으면 어떻게 될까요?

```ts
// 뒤집으면: retry( fallback( 모델 ) )
middleware: [
  modelRetryMiddleware({ maxRetries: 2 }),
  modelFallbackMiddleware("openai:gpt-5.5"),
]
```

"Claude 실패 → 즉시 GPT 시도 → 그것도 실패 → **둘 다 다시** → ..." 가 됩니다. 재시도 1회가 **두 모델 모두를 다시 부르므로** 호출 수가 곱해집니다. 429 상황에서 이러면 두 제공자 모두에서 rate limit 을 맞습니다.

> 💡 **실무 팁**: 실무 기본 조합은 `[fallback, retry]` (fallback 이 바깥) 입니다. 429/529 는 **일시적**이라 잠깐 기다리면 풀리므로 재시도가 먼저 붙어야 하고, 제공자 전체 장애처럼 **오래 가는 문제**만 폴백까지 갑니다. 여기에 `modelCallLimitMiddleware({ runLimit: 8 })` 을 얹어 무한 루프에 상한을 두면 프로덕션 3종 세트가 완성됩니다. 폴백 모델은 **주 모델과 다른 제공자**로 고르세요 — 같은 제공자의 다른 모델은 제공자가 통째로 죽으면 같이 죽습니다.

### 호출 횟수 상한

```ts
import { modelCallLimitMiddleware, toolCallLimitMiddleware } from "langchain";

// 모델 호출 상한
modelCallLimitMiddleware({
  threadLimit: 20,      // 스레드(대화) 전체에서 20회
  runLimit: 8,          // invoke() 한 번에 8회
  exitBehavior: "end",  // 초과 시: "end"(조용히 종료) | "error"(예외)
});

// 도구 호출 상한 — 도구별로 지정 가능
toolCallLimitMiddleware({
  toolName: "search_web",   // 생략하면 모든 도구 합산
  threadLimit: 20,
  runLimit: 3,
  exitBehavior: "continue", // 기본값: 한도 넘으면 그 도구만 막고 계속
});
```

`exitBehavior` 기본값이 다릅니다 — `modelCallLimitMiddleware` 는 `"end"`, `toolCallLimitMiddleware` 는 `"continue"`. 도구 쪽은 "이 도구만 더 못 쓰게 하고 에이전트는 계속"이 기본입니다.

`threadLimit` 은 **체크포인터가 있어야 의미가 있습니다.** 같은 `thread_id` 로 이어지는 대화 전체의 누적 횟수인데, 체크포인터가 없으면 상태가 안 남아 매번 0부터 셉니다([Step 10](../step-10-memory/)).

---

## 11-6. 가드레일 — PII 마스킹과 입출력 검증

### `piiMiddleware`

```ts
import { createAgent, piiMiddleware } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [supportTool],
  middleware: [
    piiMiddleware("email", { strategy: "redact", applyToInput: true }),
    piiMiddleware("credit_card", { strategy: "mask", applyToInput: true }),
    piiMiddleware("ip", { strategy: "hash", applyToInput: true }),
  ],
});
```

**시그니처**: `piiMiddleware(piiType, options?)` — 첫 인자가 문자열입니다.

**내장 PII 타입** (5개)

| 타입 | 탐지 대상 | 비고 |
|---|---|---|
| `"email"` | 이메일 주소 | |
| `"credit_card"` | 카드번호 | **Luhn 알고리즘으로 검증** |
| `"ip"` | IP 주소 | 유효성 검증 |
| `"mac_address"` | MAC 주소 | |
| `"url"` | URL | `http`/`https` 및 맨 URL |

**전략(strategy)** — 실제 출력 형식은 타입 정의에서 확인한 것입니다.

| 전략 | 결과 | 신원 보존 | 쓰는 곳 |
|---|---|---|---|
| `"redact"` (기본) | `[REDACTED_EMAIL]` | ✗ | 일반 컴플라이언스, 로그 정리 |
| `"mask"` | `****-****-****-1234` | ✗ | 사람이 읽는 UI, 고객 응대 |
| `"hash"` | `<email_hash:a1b2c3d4>` | **✓ (가명)** | 분석, 디버깅 |
| `"block"` | `PIIDetectionError` 예외 | — | PII 를 아예 안 받겠다 |

`hash` 만 **결정적(deterministic)** 입니다. 같은 이메일은 항상 같은 해시가 되므로, 모델이 "이 사람과 저 사람이 동일인"이라는 것은 알되 실제 값은 모릅니다.

**적용 범위**

| 옵션 | 기본값 | 검사 대상 |
|---|---|---|
| `applyToInput` | `true` | 사용자 메시지 (모델 호출 전) |
| `applyToOutput` | `false` | AI 응답 (모델 호출 후) |
| `applyToToolResults` | `false` | 도구 실행 결과 |

**커스텀 PII 타입** — 내장에 없는 것은 `detector` 로 만듭니다.

```ts
// 정규식 문자열 또는 RegExp
piiMiddleware("api_key", { detector: "sk-[a-zA-Z0-9]{32}", strategy: "block" });

// 함수도 됨 — (content: string) => PIIMatch[]
piiMiddleware("employee_id", {
  detector: (content) => {
    const matches = [];
    for (const m of content.matchAll(/EMP-\d{6}/g)) {
      matches.push({ text: m[0], start: m.index, end: m.index + m[0].length });
    }
    return matches;
  },
  strategy: "hash",
});
```

`piiType` 이 내장 5개가 아닌데 `detector` 를 안 주면 **에러를 던집니다** (`Error: If piiType is not built-in and no detector is provided`).

> ⚠️ **함정 (같은 미들웨어를 두 번 넣으면 에이전트가 아예 안 만들어진다)**: 미들웨어의 `name` 은 **한 에이전트 안에서 유일해야** 합니다. 중복이면 `createAgent` 가 즉시 던집니다.
>
> ```
> Error: Middleware PIIMiddleware[email] is defined multiple times
> ```
>
> 문제는 **공식 가드레일 문서의 "Layered Guardrails" 예제가 바로 이 형태**라는 것입니다. 입력용과 출력용을 따로 쌓는 모양이죠.
>
> ```ts
> // ❌ 문서에 나오는 형태 — langchain@1.5.3 에서 createAgent 가 던진다
> middleware: [
>   piiMiddleware("email", { strategy: "redact", applyToInput: true }),
>   piiMiddleware("email", { strategy: "redact", applyToOutput: true }),  // 💥 이름 충돌
> ]
>
> // ✅ 하나로 합친다 — 적용 범위는 옵션으로 지정하는 것이지 미들웨어를 쌓는 게 아니다
> middleware: [
>   piiMiddleware("email", {
>     strategy: "redact",
>     applyToInput: true,
>     applyToOutput: true,
>     applyToToolResults: true,
>   }),
> ]
> ```
>
> 이름은 `piiType` 에서 만들어지므로(`PIIMiddleware[email]`, `PIIMiddleware[credit_card]`) **타입이 다르면 여러 개 넣어도 됩니다.** 충돌하는 건 **같은 타입을 두 번** 넣을 때뿐입니다. 반면 `summarizationMiddleware` 는 이름이 항상 `SummarizationMiddleware` 라서 **두 번 넣는 것 자체가 불가능**합니다.
>
> 그나마 이건 **시끄럽게 죽는** 함정이라 다행입니다. 이 스텝의 다른 함정들과 달리 배포 전에 잡힙니다.

> ⚠️ **함정 (마스킹한 값을 도구가 그대로 받으면 무용지물)**: `piiMiddleware` 의 기본값은 `applyToInput: true, applyToOutput: false, applyToToolResults: false` 입니다. **입력만 봅니다.** 여기서 두 가지가 새어 나갑니다.
>
> **(1) 도구 결과로 들어오는 PII 는 안 걸러집니다.**
> ```
> HUMAN │ 3번 주문 조회해줘                      ← PII 없음. 통과
> AI    │ → tool get_order({"id": 3})
> TOOL  │ {"email": "kim@example.com", ...}     ← DB 에서 온 생 PII. 검사 안 함!
> AI    │ 주문자는 kim@example.com 입니다.       ← 모델이 그대로 뱉음. 검사 안 함!
> ```
> 사용자 입력만 지켜봐야 소용없습니다. PII 는 대개 **DB 에서 들어옵니다.** `applyToToolResults: true` 와 `applyToOutput: true` 를 켜야 막힙니다.
>
> **(2) 마스킹된 값이 도구 인자로 가면 도구가 깨집니다.** 반대 방향의 함정입니다.
> ```
> HUMAN │ kim@example.com 으로 메일 보내줘
>       │ ↓ piiMiddleware("email", { strategy: "redact" })
>       │ "[REDACTED_EMAIL] 으로 메일 보내줘"
> AI    │ → tool send_email({"to": "[REDACTED_EMAIL]"})   ← 이 주소로 발송 시도
> TOOL  │ Error: invalid email address
> ```
> **모델이 못 보는 값은 도구에도 못 넘깁니다.** 마스킹은 모델에게서 숨기는 것이지 시스템에서 지우는 게 아닙니다.
>
> 해결: 도구가 **실제 값을 알아야 한다면 PII 를 마스킹하면 안 됩니다.** 대신 도구가 **ID 나 참조키로 동작**하게 설계하세요 — 모델에게는 `user_id: 42` 만 주고, `send_email({ user_id: 42 })` 가 서버에서 이메일을 조회해 보내게 합니다. 모델은 이메일을 영영 안 봅니다. 이게 진짜 방어입니다. `strategy: "hash"` 가 유용한 것도 같은 이유입니다 — 가명이지만 **일관된 식별자**라서 도구가 조회 키로 쓸 수 있습니다.

> 💡 **실무 팁**: PII 를 정말 막아야 한다면 **세 방향을 다 켜세요.**
> ```ts
> piiMiddleware("credit_card", {
>   strategy: "mask",
>   applyToInput: true,
>   applyToOutput: true,
>   applyToToolResults: true,
> })
> ```
> 그리고 카드번호에는 `"block"` 을 진지하게 고려하세요. 마스킹된 카드번호는 쓸모가 없는데(결제는 어차피 토큰으로 함) **모델 제공자 로그에는 남을 수 있습니다.** 애초에 안 받는 게 낫습니다. `block` 은 `PIIDetectionError` 를 던지므로 호출부에서 `catch` 해서 "카드번호는 채팅에 입력하지 마세요"를 안내하세요.

### 입출력 검증 — HITL 과 커스텀 가드레일

`humanInTheLoopMiddleware` 는 위험한 도구 호출 앞에 **사람을 세웁니다.**

```ts
import { createAgent, humanInTheLoopMiddleware } from "langchain";
import { MemorySaver, Command } from "@langchain/langgraph";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchTool, sendEmailTool, deleteDbTool],
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        send_email: {
          allowedDecisions: ["approve", "edit", "reject"],
          description: "메일 발송 전 확인이 필요합니다",
        },
        delete_database: {
          allowedDecisions: ["approve", "reject"],  // 수정은 불허
        },
        search_web: false,   // 자동 승인
      },
    }),
  ],
  checkpointer: new MemorySaver(),   // ← 필수!
});
```

**결정 타입은 3개**입니다 (실제 enum: `["approve", "edit", "reject"]`).

| 결정 | 의미 | 재개 시 형태 |
|---|---|---|
| `approve` | 그대로 실행 | `{ type: "approve" }` |
| `edit` | 인자를 고쳐서 실행 | `{ type: "edit", editedAction: { name, args } }` |
| `reject` | 거부 + 모델에게 이유 전달 | `{ type: "reject", message: "..." }` |

```ts
const config = { configurable: { thread_id: "t1" } };

let result = await agent.invoke({ messages: [{ role: "user", content: "팀에 메일 보내줘" }] }, config);
// → 여기서 인터럽트. result 에 승인 요청이 담긴다.

result = await agent.invoke(
  new Command({ resume: { decisions: [{ type: "approve" }] } }),
  config,
);
```

`interruptOn` 에 **없는 도구는 자동 승인**입니다 (타입 주석: *"If a tool doesn't have an entry, it's auto-approved by default"*). 그래서 `search_web: false` 는 사실 생략해도 같습니다 — 명시성을 위해 적는 것입니다.

> ⚠️ **함정 (체크포인터 없이 HITL 을 쓰면 재개가 안 된다)**: `humanInTheLoopMiddleware` 는 실행을 **멈췄다가 나중에 이어서** 돕니다. 멈춘 지점의 상태를 어딘가 저장해야 하는데, 그게 체크포인터입니다. **`checkpointer` 를 안 주면 인터럽트는 걸리는데 `Command({ resume })` 로 돌아갈 수가 없습니다.** [Step 10](../step-10-memory/) 에서 본 것과 같은 함정입니다 — `thread_id` 만 주고 체크포인터를 안 주면 아무것도 안 남습니다. HITL 은 [Step 13](../step-13-hitl/) 에서 자세히 다룹니다.

커스텀 가드레일은 `createMiddleware` 로 만듭니다. `jumpTo` 로 루프를 건너뛸 수 있습니다.

```ts
import { createAgent, createMiddleware, AIMessage } from "langchain";

const contentFilter = (banned: string[]) =>
  createMiddleware({
    name: "ContentFilter",
    beforeAgent: {
      canJumpTo: ["end"],        // ← 선언해야 jumpTo 가 먹는다
      hook: (state) => {
        const first = state.messages[0];
        if (first === undefined || first.getType() !== "human") return;
        const text = first.text.toLowerCase();
        if (banned.some((k) => text.includes(k))) {
          return {
            messages: [new AIMessage("해당 요청은 처리할 수 없습니다.")],
            jumpTo: "end" as const,
          };
        }
        return;
      },
    },
  });
```

`jumpTo` 목적지는 **3개**입니다 (`JUMP_TO_TARGETS = ["model", "tools", "end"]`).

> ⚠️ **함정 (`canJumpTo` 를 빠뜨리면 `jumpTo` 가 조용히 무시된다)**: `jumpTo: "end"` 를 반환해도 훅을 `{ canJumpTo: [...], hook: ... }` 형태로 선언하지 않으면 점프가 안 먹습니다. 그래프를 컴파일할 때 **`canJumpTo` 에 선언된 목적지로만 엣지가 만들어지기** 때문입니다. 훅을 그냥 함수로 넘기면(`beforeAgent: (state) => ...`) 점프 엣지가 없으니 반환한 `jumpTo` 가 갈 곳이 없습니다. **에러가 안 납니다.** 필터를 걸었다고 믿고 있는데 차단이 안 되는, 보안상 최악의 조용한 실패입니다. 자세한 건 [Step 12](../step-12-middleware-custom/) 에서 다룹니다.

---

## 11-7. 미들웨어 조합 — 순서가 결과를 바꾼다

이제 11-2 의 순서 규칙이 실제로 무엇을 망가뜨리는지 봅니다.

### 사례 1 — 요약과 PII, 어느 쪽이 먼저인가

```ts
// (A) PII 가 바깥
middleware: [
  piiMiddleware("email", { strategy: "redact" }),
  summarizationMiddleware({ model: "anthropic:claude-haiku-4-5", trigger: { tokens: 4000 } }),
]

// (B) 요약이 바깥
middleware: [
  summarizationMiddleware({ model: "anthropic:claude-haiku-4-5", trigger: { tokens: 4000 } }),
  piiMiddleware("email", { strategy: "redact" }),
]
```

둘 다 `beforeModel` 이고 **`beforeModel` 은 배열 순서대로** 도니까:

- **(A)**: PII 마스킹 → 요약. 요약 모델은 **마스킹된 텍스트**를 봅니다.
- **(B)**: 요약 → PII 마스킹. 요약 모델은 **생 PII 를 그대로** 봅니다.

**(B) 는 PII 유출입니다.** 요약 미들웨어는 별도 모델(`claude-haiku`)을 부르는데, 그 호출에 마스킹 안 된 이메일이 실려 갑니다. 최종적으로 주 모델에게 갈 때는 마스킹되니 **결과만 보면 멀쩡해 보입니다.** 마스킹이 잘 되고 있다고 믿게 되죠. 그런데 요약 모델 제공자의 로그에는 원본이 남았습니다.

> ⚠️ **함정 (요약 후 PII 마스킹 vs 반대 — 결과가 다르다)**: **PII 미들웨어를 요약보다 앞(바깥)에 두세요.** 규칙은 간단합니다 — **"데이터를 외부로 내보내는 미들웨어보다, 데이터를 정화하는 미들웨어가 먼저"**. 요약 미들웨어는 LLM 을 부르므로 **외부 전송**입니다. 같은 이유로 `llmToolSelectorMiddleware`(별도 LLM 호출), `openAIModerationMiddleware`(외부 API) 도 PII 뒤에 와야 합니다. 이 순서가 틀려도 **에러는 절대 안 납니다.** 대화는 잘 되고, 마스킹도 잘 되는 것처럼 보이고, 컴플라이언스 감사 때 다른 회사 로그에서 발견됩니다.

### 사례 2 — 마스킹과 로깅

11-2 에서 예고한 함정입니다. `afterModel` 은 **역순**입니다.

```ts
// ❌ 의도: "마스킹하고 로깅"
middleware: [maskingMiddleware, loggingMiddleware]
// 실제 afterModel 순서: logging → masking
// → 로그에 원본이 남는다

// ✅ afterModel 에서 masking 을 먼저 돌리려면 배열에서는 뒤로
middleware: [loggingMiddleware, maskingMiddleware]
// 실제 afterModel 순서: masking → logging
```

배열 순서가 **읽는 순서와 반대**로 동작하는 유일한 지점이라 실수가 잦습니다.

### 사례 3 — 재시도와 승인

```ts
middleware: [
  humanInTheLoopMiddleware({ interruptOn: { charge_payment: { allowedDecisions: ["approve", "reject"] } } }),
  toolRetryMiddleware({ maxRetries: 3 }),
]
```

둘 다 `wrapToolCall` 이고 **앞이 바깥**이므로 `hitl( retry( 도구 ) )`:

```
사람이 승인 → [ 결제 시도 → 실패 → 재시도 → 실패 → 재시도 ] → 결과
              └─ 사람은 한 번 승인했는데 결제는 3번 시도됐다 ─┘
```

승인은 **한 번**, 실행은 **여러 번**. 11-5 의 중복 결제 함정이 HITL 을 뚫고 재현됩니다. 사람이 "승인"을 눌렀으니 감사 로그도 깨끗합니다.

뒤집으면 `retry( hitl( 도구 ) )` 가 되어 **재시도마다 사람에게 다시 물어봅니다.** 안전하지만 실패할 때마다 승인 팝업이 뜹니다.

정답은 순서가 아니라 **결제 도구에 재시도를 안 거는 것**입니다.

```ts
middleware: [
  humanInTheLoopMiddleware({ interruptOn: { charge_payment: { allowedDecisions: ["approve", "reject"] } } }),
  toolRetryMiddleware({ maxRetries: 3, tools: ["search_web", "get_order"] }),  // 읽기 도구만
]
```

### 권장 배치 순서

실무에서 쓸 만한 기본 순서입니다. **위쪽이 바깥 껍질**입니다.

```ts
middleware: [
  // 1. 입구 방어 — 가장 먼저 입력을 본다
  contentFilterMiddleware(["..."]),
  piiMiddleware("credit_card", { strategy: "block", applyToInput: true }),
  piiMiddleware("email", { strategy: "redact", applyToInput: true, applyToOutput: true, applyToToolResults: true }),

  // 2. 상한 — 폭주 방지
  modelCallLimitMiddleware({ runLimit: 10, exitBehavior: "end" }),

  // 3. 장애 대응 — fallback 이 retry 보다 바깥
  modelFallbackMiddleware("openai:gpt-5.5"),
  modelRetryMiddleware({ maxRetries: 2 }),

  // 4. 컨텍스트 관리 — PII 정화 "이후"에 와야 한다
  contextEditingMiddleware({ edits: [new ClearToolUsesEdit({ trigger: { fraction: 0.7 } })] }),
  summarizationMiddleware({ model: "anthropic:claude-haiku-4-5", trigger: [{ tokens: 8000 }, { messages: 40 }] }),

  // 5. 도구 단계 — 승인이 재시도보다 바깥
  humanInTheLoopMiddleware({ interruptOn: { send_email: { allowedDecisions: ["approve", "edit", "reject"] } } }),
  toolRetryMiddleware({ maxRetries: 2, tools: ["search_web"] }),
]
```

이유를 한 줄씩 정리하면:

| 위치 | 이유 |
|---|---|
| PII 가 맨 위 | `beforeModel` 이 순서대로 도니, 정화가 요약(외부 LLM 호출)보다 먼저여야 함 |
| 상한이 그다음 | 폭주를 일찍 끊는 게 싸다 |
| fallback > retry | 한 모델에 매달려 보고 갈아탄다 |
| contextEditing > summarization | 싼 것부터 시도, 그래도 넘치면 요약 |
| HITL > toolRetry | (그래도 쓰기 도구엔 재시도 자체를 걸지 말 것) |

> 💡 **실무 팁**: 미들웨어를 5개 이상 쌓으면 순서를 머리로 추적할 수 없습니다. **각 미들웨어에 왜 이 위치인지 주석 한 줄**을 붙이세요. 위 예시처럼요. 그리고 조합을 바꿀 때는 `practice.ts` 의 `[11-2]` 처럼 **로그 미들웨어를 끼워 실제 순서를 눈으로 확인**하고 넘어가세요. 순서 버그는 테스트로 안 잡힙니다 — 결과가 "그럴듯하게" 나오기 때문입니다.

---

## 11-8. 종합 — 프로덕션 에이전트

지금까지의 것을 하나로 묶습니다.

```ts
import {
  createAgent,
  tool,
  piiMiddleware,
  modelCallLimitMiddleware,
  modelFallbackMiddleware,
  modelRetryMiddleware,
  contextEditingMiddleware,
  ClearToolUsesEdit,
  summarizationMiddleware,
  humanInTheLoopMiddleware,
  toolRetryMiddleware,
  todoListMiddleware,
} from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import * as z from "zod";

const getOrder = tool(
  async ({ orderId }) => JSON.stringify({ orderId, status: "배송중", email: "kim@example.com" }),
  {
    name: "get_order",
    description: "주문 ID로 주문 상태를 조회합니다. 조회 전용이라 여러 번 불러도 안전합니다.",
    schema: z.object({ orderId: z.number().describe("주문 번호") }),
  },
);

const refundOrder = tool(
  async ({ orderId }) => `주문 ${orderId} 환불 완료`,
  {
    name: "refund_order",
    description: "주문을 환불합니다. 실제로 돈이 나가므로 되돌릴 수 없습니다.",
    schema: z.object({ orderId: z.number().describe("주문 번호") }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getOrder, refundOrder],
  systemPrompt: "당신은 고객 지원 상담원입니다. 환불은 반드시 주문 상태를 확인한 뒤에만 진행하세요.",
  middleware: [
    // 정화가 가장 바깥 — 요약 LLM 이 생 PII 를 못 보게
    piiMiddleware("email", {
      strategy: "redact",
      applyToInput: true,
      applyToOutput: true,
      applyToToolResults: true,   // get_order 가 뱉는 이메일도 막는다
    }),
    // 폭주 상한
    modelCallLimitMiddleware({ runLimit: 10, exitBehavior: "end" }),
    // 장애 대응 — fallback 바깥, retry 안쪽
    modelFallbackMiddleware("openai:gpt-5.5"),
    modelRetryMiddleware({ maxRetries: 2, initialDelayMs: 1000, jitter: true }),
    // 컨텍스트 — 싼 것 먼저, 그래도 넘치면 요약
    contextEditingMiddleware({
      edits: [new ClearToolUsesEdit({ trigger: { fraction: 0.7 }, keep: { messages: 3 } })],
    }),
    summarizationMiddleware({
      model: "anthropic:claude-haiku-4-5",
      trigger: [{ tokens: 8000 }, { messages: 40 }],   // 배열 = OR
      keep: { messages: 20 },
    }),
    // 위험한 도구는 사람 승인
    humanInTheLoopMiddleware({
      interruptOn: {
        refund_order: { allowedDecisions: ["approve", "reject"], description: "환불을 승인하시겠습니까?" },
        // get_order 는 항목이 없으므로 자동 승인
      },
    }),
    // 재시도는 읽기 도구에만! refund_order 는 제외
    toolRetryMiddleware({ maxRetries: 2, tools: ["get_order"] }),
    // 계획 도구
    todoListMiddleware(),
  ],
  checkpointer: new MemorySaver(),   // HITL 과 threadLimit 에 필수
});
```

이 설정에 담긴 판단을 정리하면:

| 결정 | 이유 |
|---|---|
| `piiMiddleware` 를 맨 위에 | 요약 LLM 이 생 PII 를 못 보게 (11-7 사례 1) |
| `applyToToolResults: true` | PII 는 사용자 입력이 아니라 **DB** 에서 온다 (11-6 함정) |
| `trigger` 를 **배열**로 | OR 의미. 객체 하나면 AND 라 안 터진다 (11-4 함정) |
| `toolRetryMiddleware` 에 `tools: ["get_order"]` | `refund_order` 재시도 = **중복 환불** (11-5 함정) |
| `modelFallback` 이 `modelRetry` 보다 위 | 한 모델에 매달려 보고 갈아탄다 (11-5) |
| `checkpointer` | HITL 재개와 `threadLimit` 에 필수 (11-6 함정) |

> 💡 **실무 팁**: 이걸 처음부터 다 넣지 마세요. **`createAgent` 만으로 시작해서, 문제가 생길 때마다 하나씩 추가**하는 게 맞습니다. 컨텍스트가 터지면 그때 `contextEditingMiddleware`, 429 를 맞으면 그때 `modelRetryMiddleware`. 미들웨어를 미리 다 넣으면 무엇이 무엇을 하는지 아무도 모르는 상태가 되고, 버그가 나면 8개 중 어디가 범인인지 이분 탐색을 하게 됩니다. 미들웨어는 **탈착이 쉽다는 게 장점**이니, 그 장점을 살려 필요할 때 붙이세요.

> 💡 **OpenAI 로 쓰려면**: 모델 문자열만 바꾸면 전부 그대로 동작합니다.
> ```ts
> model: "openai:gpt-5.5",
> middleware: [
>   summarizationMiddleware({ model: "openai:gpt-5.4-mini", trigger: { tokens: 8000 } }),
>   modelFallbackMiddleware("anthropic:claude-sonnet-4-6"),   // 폴백은 다른 제공자로
> ]
> ```
> 위 카탈로그에서 **제공자 전용 3개**(`anthropicPromptCachingMiddleware`, `bedrockPromptCachingMiddleware`, `openAIModerationMiddleware`)만 제공자를 탑니다. 나머지는 전부 제공자 무관입니다. `OPENAI_API_KEY` 를 `.env` 에 넣어야 합니다.

---

## 정리

| 훅 | 호출 빈도 | 여러 개일 때 순서 |
|---|---|---|
| `beforeAgent` | 실행당 1회 | 배열 순서대로 |
| `beforeModel` | 모델 호출마다 | 배열 순서대로 |
| `wrapModelCall` | 모델 호출마다 | 중첩 (앞이 바깥) |
| `wrapToolCall` | 도구 호출마다 | 중첩 (앞이 바깥) |
| `afterModel` | 모델 응답마다 | **역순** |
| `afterAgent` | 실행당 1회 | **역순** |

**내장 미들웨어 한눈에** (전부 `import { ... } from "langchain"`)

| 분류 | 미들웨어 |
|---|---|
| 컨텍스트 | `summarizationMiddleware`, `contextEditingMiddleware`, `todoListMiddleware` |
| 장애 대응 | `modelRetryMiddleware`, `toolRetryMiddleware`, `modelFallbackMiddleware` |
| 제한/안전 | `modelCallLimitMiddleware`, `toolCallLimitMiddleware`, `piiMiddleware`, `piiRedactionMiddleware`, `humanInTheLoopMiddleware` |
| 도구/프롬프트 | `llmToolSelectorMiddleware`, `toolEmulatorMiddleware`, `providerToolSearchMiddleware`, `dynamicSystemPromptMiddleware` |
| 제공자 전용 | `anthropicPromptCachingMiddleware`, `bedrockPromptCachingMiddleware`, `openAIModerationMiddleware` |

**시그니처 예외 2개**
- `modelFallbackMiddleware(a, b)` — 가변 인자 (배열 아님)
- `piiMiddleware("email", { ... })` — 첫 인자가 문자열 (객체 아님)

**핵심 함정 3가지**

1. **순서가 결과를 바꾼다.** `beforeModel` 은 순서대로, `afterModel` 은 **역순**, `wrap` 은 중첩. PII 마스킹을 요약보다 뒤에 두면 요약 LLM 에 **생 PII 가 전송**된다. 에러는 안 난다.
2. **요약은 정보를 잃는다.** 도구 호출 이력이 자연어로 뭉개지면 모델이 **같은 도구를 다시 부른다.** `keep` 을 넉넉히 잡거나, 요약 대신 `contextEditingMiddleware` 를 쓴다.
3. **재시도가 비멱등 도구를 재실행하면 중복 결제.** "실패"와 "실패처럼 보임"은 다르다. `toolRetryMiddleware({ tools: [...] })` 로 **읽기 도구만** 화이트리스트에 넣는다.

**추가 함정**
- 미들웨어 `name` 은 에이전트 안에서 **유일**해야 한다. 같은 PII 타입을 두 번 넣으면 `createAgent` 가 던진다 (공식 문서 예제가 이 형태다).
- `trigger: { tokens, messages }` 는 **AND**. OR 를 원하면 배열.
- `piiMiddleware` 기본값은 **입력만** 검사. PII 는 대개 도구 결과로 들어온다.
- 마스킹된 값을 도구가 받으면 도구가 깨진다. 도구는 **ID 로 동작**하게 설계할 것.
- 체크포인터 없이 HITL / `threadLimit` 은 동작하지 않는다.
- `canJumpTo` 없이 `jumpTo` 를 반환하면 **조용히 무시**된다.
- 공식 문서와 실제 `.d.ts` 가 어긋나는 곳이 있다. `.d.ts` 가 진실이다.

---

## 연습문제

1. 미들웨어 3개(`A`, `B`, `C`)를 `createMiddleware` 로 만들어 각각 `beforeModel` 과 `afterModel` 에서 자기 이름을 `console.log` 하게 하세요. `middleware: [A, B, C]` 로 에이전트를 만들어 실행하고, **출력 순서를 예측한 뒤 확인**하세요. `afterModel` 이 역순으로 나오나요?
2. `summarizationMiddleware` 를 `trigger: { tokens: 100, messages: 100 }` 으로 설정하고 짧은 대화를 여러 번 주고받으세요. 요약이 발동하나요? 발동하지 않는다면 **왜** 그런지 설명하고, 발동하도록 고치세요.
3. `piiMiddleware("email", { strategy: "redact" })` 를 걸고, **이메일을 결과에 포함하는 도구**(`get_user`)를 만들어 호출시키세요. 최종 응답에 이메일이 그대로 나오나요? 옵션 하나로 막아 보세요.
4. `toolRetryMiddleware` 를 걸고 **호출될 때마다 카운터를 올리는 도구**를 만드세요. 그 도구가 항상 에러를 던지게 한 뒤 `maxRetries: 3` 으로 실행해, 카운터가 몇이 되는지 확인하세요. `maxRetries` 값과 같나요, 다른가요? 왜 그런가요?
5. `modelFallbackMiddleware` 를 **배열로** 호출(`modelFallbackMiddleware(["openai:gpt-5.5"])`)해 보고 타입 에러를 확인한 뒤, 올바른 가변 인자 형태로 고치세요.
6. `piiMiddleware("employee_id", { detector: ..., strategy: "hash" })` 로 `EMP-123456` 형식의 사번을 탐지하는 커스텀 PII 를 만드세요. 같은 사번이 두 번 나오면 **같은 해시**가 되는지 확인하세요.
7. `[piiMiddleware, summarizationMiddleware]` 와 `[summarizationMiddleware, piiMiddleware]` 두 조합을 만들고, 요약 모델에 **어떤 텍스트가 전달되는지** 확인할 로그 미들웨어를 끼워 차이를 관찰하세요. 어느 쪽이 PII 유출인가요?
8. 공식 가드레일 문서의 "Layered Guardrails" 예제처럼 `piiMiddleware("email", { applyToInput: true })` 와 `piiMiddleware("email", { applyToOutput: true })` 를 **한 에이전트에 나란히** 넣어보세요. 무슨 일이 일어나나요? 에러 메시지를 읽고 올바른 형태로 고치세요. 그리고 `piiMiddleware("email")` 과 `piiMiddleware("credit_card")` 는 왜 같이 넣어도 되는지 설명하세요.
9. 11-8 의 종합 예제에서 `toolRetryMiddleware` 의 `tools` 옵션을 **제거**하면 어떤 시나리오에서 무엇이 잘못되는지, 실행 흐름을 단계별로 적으세요. (코드 실행 없이 서술)

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 12 — 커스텀 미들웨어](../step-12-middleware-custom/)

내장 미들웨어로 안 되는 것은 직접 만듭니다. `createMiddleware` 로 6개 훅을 구현하고, `jumpTo` / `canJumpTo` 로 루프를 제어하고, 미들웨어가 자기 상태(`stateSchema`)를 갖게 하는 법을 다룹니다. 이 스텝에서 본 `contentFilterMiddleware` 가 거기서 완성됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(11-1 ~ 11-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 미들웨어가 실제로 어떤 순서로 도는지 눈으로 확인하고, `exercise.ts` 의 8문제를 직접 풀어본 뒤, `solution.ts` 로 채점하는 흐름입니다. 세 파일 모두 `project/` 에서 실행되며, `ANTHROPIC_API_KEY` 가 `.env` 에 있어야 합니다.

```bash
npx tsx docs/reference/langchain/step-11-middleware-builtin/practice.ts
```

### practice.ts

본문의 `[11-1] ~ [11-8]` 을 그대로 옮긴 파일입니다. 절 번호가 본문 소제목과 1:1 대응하므로, 읽다가 막히면 같은 번호 블록을 실행해 보면 됩니다.

- **`[11-2]` 가 이 파일의 핵심입니다.** `traceMiddleware(name)` 로 만든 세 개의 로그 미들웨어를 `[A, B, C]` 로 넣고 한 번 실행합니다. `beforeModel` 이 `A→B→C`, `afterModel` 이 `C→B→A` 로 찍히는 것을 **직접 눈으로** 확인하세요. 이 출력 하나가 11-7 의 모든 함정의 근거입니다.
- `[11-4]` 는 `trigger: { tokens: 200, messages: 100 }`(AND, 발동 안 함)와 `trigger: [{ tokens: 200 }, { messages: 100 }]`(OR, 발동함)를 **연달아** 실행합니다. 같은 대화인데 요약 발동 여부가 갈리는 것이 관찰 포인트입니다.
- `[11-5]` 의 `flakyTool` 은 **모듈 스코프 카운터**를 씁니다. 처음 두 번은 에러를 던지고 세 번째에 성공하도록 만들어 `toolRetryMiddleware` 가 실제로 재시도하는 것을 보여줍니다. 카운터가 `maxRetries` 와 정확히 일치하지 않는 이유(최초 1회 + 재시도 N회)를 확인하세요.
- `[11-6]` 의 `getUserTool` 은 **결과 안에 이메일을 담아** 돌려줍니다. `applyToToolResults` 를 켠 것과 끈 것을 나란히 실행해, 기본값(`false`)에서는 이메일이 그대로 새어 나오는 것을 보여줍니다.
- `[11-6c]` 는 **API 키 없이도 도는 유일한 블록**입니다. 미들웨어의 `.name` 을 직접 출력하고, 같은 PII 타입을 두 번 넣어 `createAgent` 가 던지는 것을 `try/catch` 로 잡아 보여줍니다. 공식 문서 예제를 그대로 베끼면 여기서 죽습니다.
- `[11-8]` 의 종합 에이전트는 `humanInTheLoopMiddleware` 를 포함하므로 **인터럽트에서 멈춥니다.** `Command({ resume })` 로 재개하는 부분까지 들어 있습니다. `MemorySaver` 를 주석 처리하면 재개가 안 되는 것도 확인해 보세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 9개를 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래 `// TODO:` 자리가 비어 있습니다.

- `[문제 1]` 은 `traceMiddleware` 의 껍데기만 주어져 있습니다. `console.log` 를 채우기 전에 **예상 순서를 주석으로 먼저 적고** 실행하세요. 예측과 결과가 다르면 그게 배운 것입니다.
- `[문제 4]` 는 카운터 변수와 실패하는 도구가 이미 준비되어 있습니다. 여러분이 할 일은 `maxRetries: 3` 을 걸고 **최종 카운터 값을 예측**한 뒤 실행해 맞추는 것입니다.
- `[문제 5]` 는 **일부러 타입 에러가 나는 코드**가 주석으로 들어 있습니다. 주석을 풀면 `tsc --noEmit` 이 실패합니다. 에러 메시지를 읽어보고 다시 주석 처리한 뒤 올바른 형태를 아래에 쓰세요.
- `[문제 8]` 은 **API 키 없이 풀 수 있습니다.** `createAgent` 는 네트워크를 타지 않으므로 에러가 즉시 납니다.
- `[문제 9]` 는 코드가 아니라 **서술형**입니다. 파일 맨 아래 주석 블록에 실행 흐름을 단계별로 적으세요.
- 파일을 그대로 실행하면 대부분 아무것도 출력되지 않습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

9문제의 정답과 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 2]` 의 핵심은 `trigger: { tokens: 100, messages: 100 }` 이 **AND** 라는 것입니다. 짧은 대화는 `messages: 100` 을 절대 못 넘기므로 토큰이 아무리 많아도 요약이 안 돕니다. 배열로 바꾸면 OR 가 되어 발동합니다.
- `[정답 4]` 의 카운터는 **4** 입니다. `maxRetries: 3` 은 "3번 시도"가 아니라 "최초 1회 + 재시도 3회"이기 때문입니다. 이 오프셋 하나가 실무에서 재시도 예산 계산을 어긋나게 합니다.
- `[정답 6]` 은 `detector` 함수가 `PIIMatch[]`(`{ text, start, end }`)를 돌려줘야 한다는 게 포인트입니다. `matchAll` 의 `m.index` 를 `start` 로, `m.index + m[0].length` 를 `end` 로 씁니다. `strategy: "hash"` 는 **결정적**이라 같은 사번은 항상 같은 해시(`<employee_id_hash:...>`)가 됩니다.
- `[정답 7]` 이 이 파일의 하이라이트입니다. `beforeModel` 이 배열 순서대로 돌기 때문에 `[summarization, pii]` 로 두면 **요약 LLM 이 생 PII 를 먼저 봅니다.** 정답 코드는 요약 모델 자리에 "받은 텍스트를 그대로 출력하는" 가짜 모델을 끼워 무엇이 전달되는지 직접 보여줍니다. 결과만 보면 두 조합이 똑같아 보인다는 점이 이 함정의 무서운 부분입니다.
- `[정답 8]` 은 `createAgent` 가 던지는 `Middleware PIIMiddleware[email] is defined multiple times` 를 `try/catch` 로 잡아 보여주고, 이어서 (C) 합친 형태와 (D) 타입이 다른 여러 개가 **둘 다 성공**하는 것을 대조합니다. 적용 범위는 **옵션으로 지정하는 것이지 미들웨어를 쌓는 게 아니라는** 게 결론입니다.
- `[정답 9]` 는 서술형 모범답안입니다. `refund_order` 가 타임아웃 → 서버는 이미 환불 처리 → 재시도 → **중복 환불** 순서를 단계별로 적었습니다. 사람이 승인을 한 번만 눌렀다는 점, 그래서 감사 로그가 깨끗하다는 점까지 짚습니다.

```ts file="./solution.ts"
```
