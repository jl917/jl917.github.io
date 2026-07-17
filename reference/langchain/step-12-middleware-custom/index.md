# Step 12 — 커스텀 미들웨어

> **학습 목표**
> - 6개 훅(`beforeAgent` / `beforeModel` / `wrapModelCall` / `afterModel` / `wrapToolCall` / `afterAgent`)이 **언제 불리고 무엇을 받고 무엇을 반환하는지** 구분한다
> - `createMiddleware()` 로 로깅·검증·계측 미들웨어를 직접 만든다
> - `wrapModelCall` 로 **동적 시스템 프롬프트 / 동적 모델 선택 / 폴백**을 구현한다
> - `wrapToolCall` 로 도구 호출에 **캐싱·권한 검사·감사 로그**를 얹는다
> - `stateSchema` 로 미들웨어 전용 상태를 추가하고, **리듀서가 왜 필요한지** 안다
> - `jumpTo` 로 루프를 건너뛰거나 조기 종료한다
>
> **선행 스텝**: [Step 11 — 내장 미들웨어](../step-11-middleware-builtin/)
> **예상 소요**: 90분

[Step 11](../step-11-middleware-builtin/) 에서 `modelRetryMiddleware`, `piiMiddleware` 같은 내장 미들웨어를 조립해 봤습니다. 편했지만 곧 벽에 부딪힙니다. "우리 회사 요금제에 맞춰 토큰 예산을 계산해라", "이 도구는 관리자만 부를 수 있다", "사내 감사 시스템에 도구 호출을 남겨라" — 이런 건 아무도 대신 만들어 주지 않습니다.

이 스텝에서는 미들웨어를 **직접 만듭니다**. 그런데 미들웨어는 이 코스에서 가장 조용히 틀리기 쉬운 영역이기도 합니다. 상태를 잘못 갱신하면 에러 없이 숫자만 틀리고, `handler` 를 안 부르면 모델이 아예 안 도는데 아무 경고도 없습니다. 그래서 이 스텝은 "이렇게 하면 됩니다" 만큼 "이렇게 하면 조용히 망합니다" 에 지면을 씁니다.

---

## 12-1. 훅 전체 — 6개가 각자 언제 뛰는가

에이전트는 이런 루프를 돕니다.

```
invoke() → [모델 호출 → 도구 호출] → [모델 호출 → 도구 호출] → ... → 최종 답변
```

미들웨어는 이 루프의 **틈새마다** 코드를 끼워 넣는 장치입니다. 훅은 성격이 다른 두 종류로 나뉩니다.

### 노드형(node-style) 훅 — 상태를 보고 상태를 고친다

`beforeAgent`, `beforeModel`, `afterModel`, `afterAgent` 네 개입니다. 시그니처가 전부 똑같습니다.

```ts
(state, runtime) => 상태업데이트 | undefined
```

`state` 를 읽고, **바꾸고 싶은 부분만** 담은 객체를 반환합니다. 바꿀 게 없으면 아무것도 반환하지 않습니다.

### 래퍼형(wrap-style) 훅 — 실행 자체를 감싼다

`wrapModelCall`, `wrapToolCall` 두 개입니다. `handler` 를 받아서 **직접 부릅니다**.

```ts
(request, handler) => 결과
```

`handler(request)` 앞뒤로 코드를 넣을 수 있고, `request` 를 바꿔서 넘길 수도 있고, 여러 번 부를 수도 있고(재시도/폴백), 아예 안 부를 수도 있습니다(캐시/차단).

### 표로 정리

| 훅 | 언제 | 받는 것 | 반환 | 호출 횟수 |
|---|---|---|---|---|
| `beforeAgent` | 에이전트 시작 직전 | `(state, runtime)` | 상태 업데이트 \| `undefined` | invoke 당 **1번** |
| `beforeModel` | 매 모델 호출 직전 | `(state, runtime)` | 상태 업데이트 \| `undefined` | 루프 바퀴수만큼 |
| `wrapModelCall` | 모델 호출을 **감쌈** | `(request, handler)` | `AIMessage` \| `Command` | 루프 바퀴수만큼 |
| `afterModel` | 모델 응답 직후, 도구 실행 전 | `(state, runtime)` | 상태 업데이트 \| `undefined` | 루프 바퀴수만큼 |
| `wrapToolCall` | 도구 호출을 **감쌈** | `(request, handler)` | `ToolMessage` \| `Command` | 도구 호출 수만큼 |
| `afterAgent` | 에이전트 종료 직후 | `(state, runtime)` | 상태 업데이트 \| `undefined` | invoke 당 **1번** |

`state` 에는 항상 `messages: BaseMessage[]` 가 있고, `responseFormat` 을 쓰면 `structuredResponse` 도 있습니다. 여기에 `stateSchema` 로 추가한 필드가 합쳐집니다(12-6).

`request` 의 내용물은 훅마다 다릅니다.

**`wrapModelCall` 의 `ModelRequest`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `model` | `AgentLanguageModelLike` | 이번에 쓸 모델. **바꿔치기 가능** |
| `messages` | `BaseMessage[]` | 모델에 보낼 메시지 |
| `systemMessage` | `SystemMessage` | 시스템 메시지. **바꿔치기 가능** |
| `systemPrompt` | `string` | 문자열 버전. **deprecated** — `systemMessage` 를 쓰세요 |
| `tools` | `(ServerTool \| ClientTool)[]` | 이번에 노출할 도구. **줄이거나 늘릴 수 있음** |
| `toolChoice` | `"auto" \| "none" \| "required" \| {...}` | 도구 강제 여부 |
| `responseFormat` | `ResponseFormatInput?` | 구조화 출력 스키마 |
| `state` | `TState & AgentBuiltInState` | 현재 상태 (읽기용) |
| `runtime` | `Runtime<TContext>` | `context`, `signal`, `writer` 등 |
| `modelSettings` | `Record<string, unknown>?` | `bindTools()` 에 넘길 추가 설정 |

**`wrapToolCall` 의 `ToolCallRequest`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `toolCall` | `ToolCall` | `{ id?, name, args, type? }` — 모델이 요청한 호출 |
| `tool` | `ClientTool \| ServerTool \| undefined` | 도구 인스턴스. 동적 등록 도구면 `undefined` |
| `state` | `TState & AgentBuiltInState` | 현재 상태 |
| `runtime` | `Runtime<TContext>` | 런타임 |

### 실행 순서 — 눈으로 확인

미들웨어를 두 개 겹치면 순서 규칙이 드러납니다.

```ts
const tracer = (label: string) =>
  createMiddleware({
    name: `Tracer-${label}`,
    beforeAgent: () => { console.log(`${label} │ beforeAgent`); return; },
    beforeModel: () => { console.log(`${label} │   beforeModel`); return; },
    wrapModelCall: async (request, handler) => {
      console.log(`${label} │     wrapModelCall  →`);
      const response = await handler(request);
      console.log(`${label} │     wrapModelCall  ←`);
      return response;
    },
    afterModel: () => { console.log(`${label} │   afterModel`); return; },
    wrapToolCall: async (request, handler) => {
      console.log(`${label} │     wrapToolCall   →  ${request.toolCall.name}`);
      const result = await handler(request);
      console.log(`${label} │     wrapToolCall   ←  ${request.toolCall.name}`);
      return result;
    },
    afterAgent: () => { console.log(`${label} │ afterAgent`); return; },
  });

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "너는 날씨 비서다. 도구로 확인한 사실만 답해라.",
  middleware: [tracer("A"), tracer("B")],   // A 가 바깥, B 가 안쪽
});

await agent.invoke({ messages: [{ role: "user", content: "서울 날씨 알려줘." }] });
```

**출력 예시** (모델이 도구를 부르느냐에 따라 바퀴 수가 달라집니다)

```
A │ beforeAgent
B │ beforeAgent
A │   beforeModel
B │   beforeModel
A │     wrapModelCall  →  (모델 부르기 직전)
B │     wrapModelCall  →  (모델 부르기 직전)
B │     wrapModelCall  ←  (모델이 답한 직후)
A │     wrapModelCall  ←  (모델이 답한 직후)
B │   afterModel
A │   afterModel
A │     wrapToolCall   →  get_weather
B │     wrapToolCall   →  get_weather
      [도구 실제 실행] getWeather(서울)
B │     wrapToolCall   ←  get_weather
A │     wrapToolCall   ←  get_weather
A │   beforeModel
B │   beforeModel
...
B │ afterAgent
A │ afterAgent
```

규칙 세 가지가 보입니다.

1. **before 계열은 등록 순서대로** — `A → B`
2. **after 계열은 역순** — `B → A`
3. **wrap 계열은 양파처럼 중첩** — `A→ B→ 모델 →B← A←`

`beforeModel` 은 루프를 도는 만큼 여러 번 찍혔지만 `beforeAgent` 는 맨 위에 딱 한 번인 것도 확인하세요.

> 💡 **실무 팁 — 순서를 어떻게 정하나**: `middleware: [감사, 권한, 캐시]` 처럼 **바깥에 둘수록 넓은 관심사**를 놓습니다. 감사 로그는 "차단당한 호출"까지 봐야 하니 가장 바깥, 권한 검사는 캐시보다 바깥(거부된 호출이 캐시에 저장되면 안 되니까), 캐시는 실제 도구 바로 앞. 이 순서를 뒤집으면 "권한이 없는데 캐시 HIT 로 결과가 나가는" 사고가 납니다. 배열 순서가 곧 보안 경계입니다.

---

## 12-2. `createMiddleware()` 로 첫 미들웨어 만들기

가장 먼저 만들게 되는 미들웨어는 거의 항상 로깅입니다. 에이전트가 왜 그렇게 답했는지는 "모델에 뭘 넣었고 뭐가 나왔나"를 봐야 아는데, `createAgent` 는 그걸 기본으로 보여주지 않기 때문입니다.

```ts
import { createMiddleware, AIMessage } from "langchain";

const loggingMiddleware = createMiddleware({
  name: "LoggingMiddleware",

  beforeModel: (state) => {
    console.log(`  [로그] 모델 호출 예정 — 메시지 ${state.messages.length}개`);
    return;                       // 아무것도 안 바꿈 = 통과
  },

  afterModel: (state) => {
    const last = state.messages.at(-1);
    const toolCalls = (last as AIMessage | undefined)?.tool_calls ?? [];

    if (toolCalls.length > 0) {
      console.log(`  [로그] 모델이 도구 ${toolCalls.length}개 요청: ${toolCalls.map((c) => c.name).join(", ")}`);
    } else {
      console.log(`  [로그] 모델이 최종 답변: ${last?.text.slice(0, 40)}...`);
    }
    return;
  },
});
```

`createMiddleware()` 의 설정 객체가 받는 필드는 이게 전부입니다.

| 필드 | 용도 |
|---|---|
| `name` | 미들웨어 이름. 에러 메시지와 트레이싱에 찍힙니다 |
| `stateSchema` | 미들웨어 전용 상태 (12-6) |
| `contextSchema` | 읽기 전용 호출별 메타데이터 |
| `tools` | 이 미들웨어가 추가로 등록할 도구 |
| `streamTransformers` | 스트림 변환기 |
| `beforeAgent` / `beforeModel` / `afterModel` / `afterAgent` | 노드형 훅 |
| `wrapModelCall` / `wrapToolCall` | 래퍼형 훅 |

붙여서 돌려 봅니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather, getPopulation],
  systemPrompt: "너는 도시 정보 비서다.",
  middleware: [loggingMiddleware],
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "서울과 부산의 날씨와 인구를 알려줘." }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
  [로그] 모델 호출 예정 — 메시지 1개
  [로그] 모델이 도구 4개 요청: get_weather, get_population, get_weather, get_population
  [로그] 모델 호출 예정 — 메시지 6개
  [로그] 모델이 최종 답변: 서울은 맑고 24도이며 인구는 약 938만 명...
```

메시지가 1개 → 6개로 뛴 것에 주목하세요. `AIMessage`(도구 요청) 1개 + `ToolMessage` 4개가 붙어서 그렇습니다.

> 💡 **실무 팁**: `console.log` 는 로컬 디버깅용입니다. 실무에서는 이 자리에 구조화된 로거(pino, winston)나 OpenTelemetry span 을 넣습니다. 미들웨어 하나만 바꾸면 전 에이전트에 관측이 깔린다는 게 이 패턴의 진짜 가치입니다. LangSmith 를 쓴다면 `LANGSMITH_TRACING=true` 만으로 이 정도는 자동으로 잡히니, 커스텀 로깅은 "LangSmith 가 안 잡아주는 우리만의 지표"에 쓰세요 — [Step 19](../step-19-observability-eval/) 에서 다룹니다.

---

## 12-3. `beforeModel` — 매 모델 호출 전 메시지 손보기

`beforeModel` 은 `state` 를 받아 상태 업데이트를 반환합니다. `messages` 필드는 **append 리듀서**를 쓰므로, 반환한 메시지는 기존 목록 **뒤에 붙습니다**.

모델은 지금이 몇 시인지 모릅니다. 안 알려주면 지어냅니다. 매 호출 직전에 현재 시각을 밀어 넣어 봅시다.

```ts
import { createMiddleware, SystemMessage } from "langchain";

const timeInjectorMiddleware = createMiddleware({
  name: "TimeInjectorMiddleware",

  beforeModel: (state) => {
    const now = new Date().toISOString();
    console.log(`  [시각 주입] ${now} (메시지 ${state.messages.length}개 뒤에 append)`);

    return {
      messages: [new SystemMessage(`[시스템] 현재 시각은 ${now} 입니다.`)],
    };
  },
});
```

**출력 예시**

```
  [시각 주입] 2026-07-17T04:12:33.891Z (메시지 1개 뒤에 append)
SYSTEM │ [시스템] 현재 시각은 2026-07-17T04:12:33.891Z 입니다.
HUMAN  │ 지금 몇 시인지 시스템이 알려준 대로만 말해줘.
AI     │ 2026년 7월 17일 04시 12분(UTC)입니다.
```

동작은 합니다. 그런데 주입한 `SystemMessage` 가 **`result.messages` 안에 남아 있습니다**. 체크포인터를 쓰면([Step 10](../step-10-memory/)) 이게 그대로 저장되고, 다음 턴에도 남고, 그 다음 턴에 또 하나 붙습니다. 10턴이면 낡은 타임스탬프 10개가 대화에 쌓입니다.

> ⚠️ **함정 (반환값은 "덧붙일 것"이지 "전체 목록"이 아니다)**: `beforeModel` 이 반환하는 `messages` 는 **append** 됩니다. 그래서 이렇게 쓰면 안 됩니다.
>
> ```ts
> beforeModel: (state) => {
>   return { messages: [...state.messages, new SystemMessage("...")] };  // ✗
> }
> ```
>
> 기존 목록을 통째로 다시 반환했으니 대화가 **두 배로 불어납니다**. 에러는 안 납니다 — 토큰 청구서가 두 배가 되고, 모델이 같은 질문을 두 번 본 것처럼 굴 뿐입니다. 반환할 것은 **새로 붙일 메시지만** 입니다.

> ⚠️ **함정 (messages 를 통째로 갈아끼우면 tool_call / ToolMessage 짝이 깨진다)**: "오래된 메시지를 잘라내자"는 생각으로 이런 걸 시도하게 됩니다.
>
> ```ts
> beforeModel: (state) => {
>   const recent = state.messages.slice(-4);           // 최근 4개만 남기자
>   return { messages: [new RemoveMessage({ id: REMOVE_ALL_MESSAGES }), ...recent] };  // ✗ 위험
> }
> ```
>
> 자른 경계가 하필 `AIMessage(tool_calls: [X])` 와 `ToolMessage(tool_call_id: X)` **사이**를 지나가면 짝이 깨집니다. 남은 것이 `ToolMessage` 뿐이면 "대답할 질문이 없는 대답"이, `AIMessage` 뿐이면 "답을 못 받은 질문"이 됩니다. 두 경우 다 provider 가 **400** 을 냅니다.
>
> - Anthropic: `messages.N: tool_use ids were found without tool_result blocks immediately after`
> - OpenAI: `An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'`
>
> 무서운 건 이게 **대화가 짧을 땐 안 터진다**는 겁니다. 도구를 부르기 시작하고 대화가 길어져서 자르기가 발동하는 순간, 프로덕션에서 처음 터집니다. 대화를 줄이는 일은 직접 하지 말고 내장 `summarizationMiddleware` 나 컨텍스트 편집 미들웨어를 쓰세요([Step 11](../step-11-middleware-builtin/)). 그것들은 이 짝을 지켜 줍니다.

그리고 **애초에 시스템 프롬프트를 동적으로 바꾸는 데 `beforeModel` 을 쓰면 안 됩니다.** 대화 기록을 오염시키니까요. 그건 다음 절의 일입니다.

---

## 12-4. `wrapModelCall` — 모델 호출 감싸기

`wrapModelCall` 은 미들웨어의 주력입니다. `handler` 를 손에 쥐고 있으므로 할 수 있는 일이 급격히 늘어납니다.

### 12-4-1. 동적 시스템 프롬프트

12-3 의 문제를 이걸로 풉니다. `request.systemMessage` 를 바꾸면 **이번 모델 호출에만** 적용되고 `state.messages` 에는 남지 않습니다.

```ts
const dynamicPromptMiddleware = createMiddleware({
  name: "DynamicPromptMiddleware",

  wrapModelCall: async (request, handler) => {
    const turn = request.messages.filter((m) => m.getType() === "human").length;
    const extra = turn > 1 ? " 사용자가 이미 여러 번 물었다. 더 짧게 답해라." : "";

    return handler({
      ...request,                                       // ← 스프레드로 나머지 보존
      systemMessage: request.systemMessage.concat(extra),
    });
  },
});
```

`request.systemMessage.concat(...)` 은 기존 시스템 메시지에 **덧붙입니다**. `createAgent({ systemPrompt })` 로 준 원본을 날리지 않습니다.

**출력 예시**

```
  [동적 프롬프트] 사용자 턴 2회 → 추가 지시 있음
AI     │ 대기 중 큰 입자가 붉은 파장을 남기고 짧은 파장을 흩뿌리기 때문입니다.

주목: result.messages 에 추가 지시가 안 보입니다.
```

12-3 과 정반대입니다. **동적 시스템 프롬프트는 `wrapModelCall` 로 하세요.**

> 💡 **실무 팁 — 캐시를 깨뜨리지 마세요**: 시스템 프롬프트에 타임스탬프처럼 매번 바뀌는 값을 넣으면 Anthropic/OpenAI 의 **프롬프트 캐시가 매번 미스**납니다. 시스템 프롬프트는 대화 맨 앞이라 캐시 효율이 가장 큰 자리인데, 거기에 밀리초 단위 시각을 박으면 캐시가 전부 무효화되어 입력 토큰 비용이 몇 배로 뜁니다. 꼭 시각을 줘야 한다면 분/시간 단위로 뭉개세요(`2026-07-17T04:00`). 반대로 캐시를 **의도적으로 걸고 싶으면** 구조화 콘텐츠에 `cache_control` 을 답니다.
>
> ```ts
> systemMessage: request.systemMessage.concat(
>   new SystemMessage({
>     content: [{
>       type: "text",
>       text: "긴 사내 규정 문서 ...",
>       cache_control: { type: "ephemeral", ttl: "5m" },
>     }],
>   }),
> )
> ```

### 12-4-2. 동적 모델 선택

짧은 대화는 싼 모델로, 길어지면 비싼 모델로 — `request.model` 을 바꿔서 넘기면 끝입니다.

```ts
import { createMiddleware, initChatModel } from "langchain";

const models = {
  cheap: await initChatModel("anthropic:claude-haiku-4-5-20251001"),
  strong: await initChatModel("anthropic:claude-sonnet-4-6"),
};

const routerMiddleware = createMiddleware({
  name: "ModelRouterMiddleware",
  wrapModelCall: (request, handler) => {
    const useStrong = request.messages.length > 4;
    console.log(`  [모델 라우팅] 메시지 ${request.messages.length}개 → ${useStrong ? "sonnet(비쌈)" : "haiku(쌈)"}`);

    return handler({ ...request, model: useStrong ? models.strong : models.cheap });
  },
});

const agent = createAgent({
  model: models.strong,     // ← 기본값일 뿐, 미들웨어가 매번 덮어씁니다
  tools: [getWeather],
  middleware: [routerMiddleware],
});
```

OpenAI 를 섞고 싶다면 `await initChatModel("openai:gpt-5.5")` 를 넣으면 됩니다 — provider 를 가로질러 라우팅할 수 있는 게 이 패턴의 장점입니다.

### 12-4-3. 폴백을 직접 구현

`handler` 를 **여러 번 부를 수 있다**는 게 `wrapModelCall` 의 핵심입니다.

```ts
const fallbackMiddleware = createMiddleware({
  name: "FallbackMiddleware",

  wrapModelCall: async (request, handler) => {
    try {
      return await handler({ ...request, model: brokenModel });   // ← await 필수
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.log(`  [폴백] 1차 모델 실패 → 2차 모델로 재시도`);
      console.log(`         원인: ${message.slice(0, 70)}...`);

      return await handler({ ...request, model: goodModel });
    }
  },
});
```

**출력 예시** (provider 에러 메시지는 버전에 따라 다릅니다)

```
  [폴백] 1차 모델 실패 → 2차 모델로 재시도
         원인: 404 {"type":"error","error":{"type":"not_found_error","message":"m...
AI     │ 안녕하세요!
```

> ⚠️ **함정 (`await` 하나로 try/catch 가 죽는다)**: 위 코드에서 `await` 를 빼고 이렇게 쓰면 폴백이 **절대 동작하지 않습니다**.
>
> ```ts
> try {
>   return handler({ ...request, model: brokenModel });   // ✗ await 없음
> } catch (error) {
>   /* 여기는 영원히 실행되지 않습니다 */
> }
> ```
>
> `handler` 는 `Promise` 를 돌려줍니다. `return handler(...)` 는 Promise 를 만들자마자 반환하고 함수를 빠져나갑니다. 모델 호출이 **나중에** 실패해도 그 rejection 은 이미 떠난 `try/catch` 를 잡을 수 없습니다. 그대로 위로 올라가 에이전트가 죽습니다.
>
> 무서운 점: 에러 메시지가 지극히 정상적입니다("404 not_found_error"). 폴백이 안 걸렸다는 단서가 어디에도 없어서, "폴백을 넣었는데 왜 안 되지?" 로 반나절을 씁니다. **`wrapModelCall` / `wrapToolCall` 안에서 `handler` 앞의 `await` 는 선택이 아닙니다.** `try/finally` 로 시간을 재는 경우도 마찬가지입니다 — `await` 가 없으면 항상 `0ms` 가 찍힙니다.

> ⚠️ **함정 (`handler` 를 안 부르면 모델이 아예 안 돈다)**: `wrapModelCall` 은 `AIMessage` 또는 `Command` 를 반환해야 합니다. 그런데 `handler` 를 **부르지 않고** `Command` 만 반환하면 어떻게 될까요?
>
> ```ts
> wrapModelCall: async (request, handler) => {
>   return new Command({ update: { myCounter: 1 } });   // ✗ handler 를 안 불렀음
> }
> ```
>
> 모델은 호출되지 않습니다. 에러도 안 납니다. 프레임워크는 `handler` 가 돌려준 마지막 `AIMessage` 를 붙여 주는데, `handler` 를 안 불렀으니 붙일 게 없습니다. 결과는 **답변이 없는 빈 턴**입니다. 에이전트가 도구 루프를 돌다가 아무 진전 없이 `recursionLimit` 까지 돌고 죽거나, 마지막 메시지가 사용자 질문인 채로 끝납니다.
>
> 반대로 `handler` 를 **부른 뒤** `Command` 를 반환하는 건 완전히 정상입니다 — 프레임워크가 `handler` 의 응답을 추적해 두었다가 알아서 붙여 줍니다. 12-8 이 정확히 이 패턴입니다. 규칙은 하나입니다: **`handler` 를 부르지 않을 거면 `AIMessage` 를 직접 만들어 반환하세요.**

---

## 12-5. `wrapToolCall` — 도구 호출 가로채기

도구는 에이전트가 바깥 세계를 건드리는 유일한 통로입니다. 그래서 가로챌 이유가 많습니다.

### 12-5-1. 캐싱

같은 인자로 같은 도구를 또 부르면 실제 실행을 건너뜁니다.

```ts
const toolCache = new Map<string, string>();

const cachingMiddleware = createMiddleware({
  name: "CachingMiddleware",

  wrapToolCall: async (request, handler) => {
    const key = `${request.toolCall.name}:${JSON.stringify(request.toolCall.args)}`;

    const hit = toolCache.get(key);
    if (hit !== undefined) {
      console.log(`  [캐시 HIT] ${key} → 도구를 실행하지 않음`);
      return new ToolMessage({
        content: hit,
        tool_call_id: request.toolCall.id!,   // ← "이번" 호출의 id
        name: request.toolCall.name,
      });
    }

    console.log(`  [캐시 MISS] ${key}`);
    const result = await handler(request);

    if (ToolMessage.isInstance(result)) {
      toolCache.set(key, result.text);        // ← 내용만 저장
    }
    return result;
  },
});
```

> ⚠️ **함정 (`ToolMessage` 를 통째로 캐시하면 두 번째 호출에서 400 이 난다)**: 가장 자연스러운 구현은 이겁니다.
>
> ```ts
> const cache = new Map<string, ToolMessage>();
> wrapToolCall: async (request, handler) => {
>   const key = `${request.tool.name}:${JSON.stringify(request.toolCall.args)}`;
>   if (cache.has(key)) return cache.get(key);      // ✗ 옛날 tool_call_id 가 박혀 있음
>   const result = await handler(request);
>   cache.set(key, result);
>   return result;
> }
> ```
>
> `ToolMessage` 에는 `tool_call_id` 가 박혀 있고, 이 id 는 **호출마다 모델이 새로 만듭니다**. 캐시된 메시지를 그대로 돌려주면 "이번 턴의 `AIMessage` 가 요청한 id" 와 "돌려준 `ToolMessage` 의 id" 가 어긋납니다. 짝이 깨져서 provider 가 400 을 냅니다.
>
> 이 함정이 특히 고약한 이유: **1회차는 항상 성공합니다**(캐시 MISS 라 진짜 결과가 나가니까). 2회차부터 터지므로 단순 테스트를 통과해 버립니다. 답은 위 코드처럼 **내용(`result.text`)만 캐시하고 `ToolMessage` 는 매번 새로 만드는 것**입니다.
>
> 참고로 `request.tool` 은 `undefined` 일 수 있습니다(동적 등록 도구). 캐시 키는 `request.tool.name` 말고 **`request.toolCall.name`** 으로 만드세요.

### 12-5-2. 권한 검사

`handler` 를 **안 부르고** `ToolMessage` 를 직접 만들어 돌려주면 도구는 실행되지 않고, 모델은 "거부당했다"는 사실을 텍스트로 읽습니다.

```ts
const ALLOWED_TOOLS = new Set(["get_weather", "get_population"]);

const permissionMiddleware = createMiddleware({
  name: "PermissionMiddleware",

  wrapToolCall: (request, handler) => {
    if (!ALLOWED_TOOLS.has(request.toolCall.name)) {
      console.log(`  [권한 거부] ${request.toolCall.name} — handler 를 부르지 않고 차단`);

      return new ToolMessage({
        content: `권한 없음: '${request.toolCall.name}' 도구는 이 사용자에게 허용되지 않았습니다. 다른 방법을 찾거나 사용자에게 알리세요.`,
        tool_call_id: request.toolCall.id!,
        name: request.toolCall.name,
        status: "error",
      });
    }
    return handler(request);
  },
});
```

**출력 예시**

```
  [권한 거부] delete_record — handler 를 부르지 않고 차단
AI     │ 죄송합니다. delete_record 도구를 사용할 권한이 없어 레코드를 삭제할 수 없습니다.
         관리자에게 문의해 주세요.
```

도구는 실행되지 않았는데 에이전트는 살아서 사용자에게 사정을 설명했습니다. 이게 **거부의 올바른 모양**입니다.

> ⚠️ **함정 (미들웨어에서 던진 예외는 에이전트 전체를 죽인다)**: 거부를 이렇게 표현하고 싶어집니다.
>
> ```ts
> wrapToolCall: (request, handler) => {
>   if (!allowed) throw new Error("권한 없음");    // ✗
>   return handler(request);
> }
> ```
>
> 이러면 `agent.invoke()` 가 **통째로 실패합니다**. 부분 결과도, 답변도, 여태 돈 도구 결과도 전부 사라지고 사용자는 스택트레이스를 봅니다. 모델에게는 복구할 기회조차 없습니다 — 모델은 자기가 거부당했다는 사실을 아예 모릅니다.
>
> 던져진 에러는 `MiddlewareError` 로 감싸져서 올라옵니다. 원본 에러의 `name` 은 보존되고 `cause` 에 원본이 들어갑니다.
>
> ```ts
> try {
>   await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
> } catch (error) {
>   MiddlewareError.isInstance(error)   // → true
>   (error as Error).message            // → "여기서 던지면 어떻게 될까요?"
>   (error as Error).cause              // → 원본 Error
> }
> ```
>
> 예외로 다뤄야 할 건 "정말 복구 불가능한 것"(설정 누락, 프로그래밍 버그)뿐입니다. **"모델이 알아서 대처했으면 하는 것"은 전부 `ToolMessage` 로 돌려주세요.** 조기 종료가 목적이라면 `jumpTo: "end"` 를 쓰고요(12-7). 예외: `interrupt()` 가 던지는 `GraphInterrupt` 는 제어 흐름이라 감싸지 않고 그대로 통과합니다 — [Step 13](../step-13-hitl/) 의 HITL 이 이걸로 동작합니다.

### 12-5-3. 감사 로그

관찰만 하고 아무것도 바꾸지 않는 미들웨어입니다.

```ts
const auditMiddleware = createMiddleware({
  name: "AuditMiddleware",

  wrapToolCall: async (request, handler) => {
    const startedAt = Date.now();
    try {
      const result = await handler(request);
      console.log(`  [감사] ok    ${request.toolCall.name} ${JSON.stringify(request.toolCall.args)} (${Date.now() - startedAt}ms)`);
      return result;
    } catch (error) {
      console.log(`  [감사] FAIL  ${request.toolCall.name} (${Date.now() - startedAt}ms) — ${String(error)}`);
      throw error;                        // ← 삼키지 말고 그대로 올립니다
    }
  },
});
```

감사자는 에러를 **삼키면 안 됩니다**. `catch` 하고 `throw` 를 빼먹으면 위쪽 미들웨어가 실패를 못 보고, 도구가 실패했는데 성공한 것처럼 흘러갑니다.

### 세 개를 겹치기

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather, getPopulation, deleteRecord],
  systemPrompt: "너는 도시 정보 비서다. 사용자가 요청하면 주저하지 말고 도구를 써라.",
  middleware: [auditMiddleware, permissionMiddleware, cachingMiddleware],
});
```

순서가 곧 의미입니다: **감사(바깥) → 권한(중간) → 캐시(안쪽) → 실제 도구**.

**출력 예시**

```
─ 1회차: 서울 날씨 ─
  [캐시 MISS] get_weather:{"city":"서울"}
      [도구 실제 실행] getWeather(서울)
  [감사] ok    get_weather {"city":"서울"} (2ms)

─ 2회차: 같은 질문 (캐시 HIT 기대) ─
  [캐시 HIT] get_weather:{"city":"서울"} → 도구를 실행하지 않음
  [감사] ok    get_weather {"city":"서울"} (0ms)

─ 3회차: 금지된 도구 요청 ─
  [권한 거부] delete_record — handler 를 부르지 않고 차단
  [감사] ok    delete_record {"id":"abc-123"} (0ms)
```

2회차에서 `[도구 실제 실행]` 이 사라졌고, 3회차에서도 감사 로그는 남았습니다(권한 미들웨어가 감사보다 **안쪽**이라서). 만약 순서를 `[권한, 감사, 캐시]` 로 뒤집었다면 차단된 호출은 감사 로그에 안 남습니다 — 보안 관점에서 최악입니다.

> 💡 **실무 팁 — 캐시 수명**: 위 `Map` 은 프로세스가 살아 있는 동안 무한히 자랍니다. 실무에서는 TTL 과 최대 크기가 필요합니다(`lru-cache` 등). 그리고 **부수효과가 있는 도구는 절대 캐시하면 안 됩니다** — `send_email` 을 캐시하면 두 번째 메일이 안 갑니다. 캐시는 "같은 입력이면 같은 출력이고 부작용이 없는" 조회성 도구에만 거세요.

---

## 12-6. 상태 확장 — `stateSchema`

미들웨어는 자기만의 필드를 에이전트 상태에 추가할 수 있습니다. 방법이 둘인데, **어느 쪽을 고르느냐가 정확성을 좌우합니다**.

### 방법 1: zod object — 리듀서 없음

```ts
import * as z from "zod";

const trackingStateSchema = z.object({
  modelCallCount: z.number().default(0),
});

const middleware = createMiddleware({
  name: "incrementAfterModel",
  stateSchema: trackingStateSchema,
  afterModel: (state) => {
    return { modelCallCount: state.modelCallCount + 1 };   // 새 총합을 반환
  },
});
```

간단합니다. 하지만 리듀서가 없어서 **나중에 쓴 값이 이깁니다**(last-write-wins).

### 방법 2: `StateSchema` + `ReducedValue` — 리듀서 있음

```ts
import { StateSchema, ReducedValue } from "@langchain/langgraph";
import * as z from "zod";

const CounterState = new StateSchema({
  // 더하기 리듀서 — 누적됩니다
  modelCallCount: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  // 덮어쓰기 리듀서
  lastModelName: new ReducedValue(z.string().default(""), {
    reducer: (_current: string, next: string) => next,
  }),
  // _ 로 시작하면 private — invoke() 결과에 안 실립니다
  _internalNote: new ReducedValue(z.string().default(""), {
    reducer: (_current: string, next: string) => next,
  }),
});

const counterMiddleware = createMiddleware({
  name: "CounterMiddleware",
  stateSchema: CounterState,

  afterModel: (state) => {
    console.log(`  [카운터] 지금까지 모델 호출 ${state.modelCallCount}회`);

    return {
      modelCallCount: 1,          // ← "증가분". "새 총합" 이 아닙니다
      lastModelName: "claude-sonnet-4-6",
      _internalNote: "이 필드는 결과에 안 보입니다",
    };
  },
});
```

리듀서가 `current + next` 이므로 반환할 것은 **증가분 `1`** 입니다. 방법 1 처럼 `state.modelCallCount + 1` 을 반환하면 리듀서가 또 더해서 값이 폭주합니다. **리듀서를 바꾸면 반환값의 의미도 같이 바뀝니다.**

**출력 예시**

```
  [카운터] 지금까지 모델 호출 0회
  [카운터] 지금까지 모델 호출 1회
modelCallCount (public)   2
lastModelName (public)    claude-sonnet-4-6
_internalNote (private)   (결과에 없음 — 의도된 동작)
```

`_internalNote` 가 결과에 없습니다. **`_` 로 시작하는 필드는 private** 이라 `invoke()` 결과에서 걸러집니다. 미들웨어 내부 플래그를 사용자에게 노출하지 않으려면 이걸 쓰세요.

### 어느 쪽을 쓸까

| | zod object | `StateSchema` + `ReducedValue` |
|---|---|---|
| 리듀서 | 없음 (last-write-wins) | 있음 |
| 반환값 의미 | 최종 값 | 리듀서 입력(증가분 등) |
| 적합한 것 | 플래그, 최신 값 하나 | **카운터, 누적기, 집계** |
| 병렬 업데이트 | ✗ 덮어써짐 | ✓ 합쳐짐 |

> ⚠️ **함정 (state 를 mutate 하면 안 된다 — 새 객체를 반환해야 리듀서가 돈다)**: 이렇게 쓰고 싶어집니다.
>
> ```ts
> afterModel: (state) => {
>   state.modelCallCount++;              // ✗ 아무 일도 일어나지 않습니다
>   state.toolCounts["get_weather"] = 3; // ✗ 더 나쁩니다
>   return;
> }
> ```
>
> LangGraph 는 훅의 **반환값**을 리듀서에 넣어 다음 상태를 만듭니다. `state` 객체를 직접 고치는 건 리듀서 파이프라인 바깥의 일이라 **그냥 무시됩니다**. 타입 에러도, 런타임 에러도 없습니다 — 카운터가 영원히 0 일 뿐입니다.
>
> 두 번째 줄은 더 위험합니다. 상태는 스냅샷으로 관리되는데 스냅샷을 mutate 하면 **체크포인터에 저장된 과거 상태까지 바뀌어** 타임트래블과 재개(resume)가 깨집니다. 리듀서 안에서도 마찬가지입니다 — `current` 를 건드리지 말고 `{ ...current }` 로 새 객체를 만드세요.
>
> **규칙: 훅은 순수 함수처럼 쓰세요. 읽기만 하고, 바꿀 것은 반환하세요.**

> ⚠️ **함정 (병렬 도구 호출에서 덮어쓰기 리듀서는 조용히 값을 잃는다)**: 도구별 호출 횟수를 세는 필드를 zod object(또는 `(_a, b) => b` 리듀서)로 만들었다고 합시다. 모델이 도구를 3개 **병렬로** 부르면 `wrapToolCall` 이 3번 거의 동시에 돌고, 각각 `{ get_weather: 1 }` 을 반환합니다. 덮어쓰기 리듀서면 최종 결과는 `{ get_weather: 1 }` — **3번 불렀는데 1로 남습니다**.
>
> 에러도 경고도 없습니다. 그냥 숫자가 틀립니다. 그리고 이건 모델이 병렬로 부를 때만 재현되므로 로컬에서는 잘 안 보입니다. **카운터·누적기는 반드시 합치기 리듀서를 쓰세요.**

---

## 12-7. 흐름 제어 — `jumpTo`

노드형 훅은 반환 객체에 `jumpTo` 를 실어 루프의 다음 목적지를 바꿀 수 있습니다. 갈 수 있는 곳은 딱 3개입니다.

| `jumpTo` | 의미 |
|---|---|
| `"model"` | 모델 호출로 되돌아간다 (다시 시키기) |
| `"tools"` | 도구 실행으로 건너뛴다 |
| `"end"` | 즉시 종료한다 |

금칙어가 있으면 모델을 아예 부르지 않고 끝내 봅시다.

```ts
const BLOCKED_WORDS = ["비밀번호", "주민등록번호"];

const guardMiddleware = createMiddleware({
  name: "GuardMiddleware",

  beforeModel: {
    canJumpTo: ["end"],                    // ← 이 선언이 없으면 동작하지 않습니다
    hook: (state) => {
      const lastHuman = [...state.messages].reverse().find((m) => m.getType() === "human");
      const text = lastHuman?.text ?? "";

      const hit = BLOCKED_WORDS.find((w) => text.includes(w));
      if (hit !== undefined) {
        console.log(`  [가드] 금칙어 '${hit}' 감지 → 모델을 부르지 않고 즉시 종료`);

        return {
          messages: [new AIMessage("죄송합니다. 해당 정보는 다룰 수 없습니다.")],
          jumpTo: "end" as const,
        };
      }
      return;
    },
  },
});
```

훅이 함수가 아니라 **`{ canJumpTo, hook }` 객체**인 것에 주목하세요.

**출력 예시**

```
─ 정상 질문 (모델이 돎) ─
AI     │ 서울은 현재 맑고 24도입니다.

─ 금칙어 질문 (모델을 안 부르고 조기 종료) ─
  [가드] 금칙어 '비밀번호' 감지 → 모델을 부르지 않고 즉시 종료
AI     │ 죄송합니다. 해당 정보는 다룰 수 없습니다.
```

두 번째 케이스에서 모델 호출이 **0번** 일어났습니다. 토큰도 0, 지연도 0입니다. 가드레일을 프롬프트로 거는 것보다 훨씬 싸고 확실합니다.

> ⚠️ **함정 (`canJumpTo` 없이 `jumpTo` 만 반환하면 조용히 무시된다)**: 이건 이 절에서 가장 많이 걸리는 함정입니다.
>
> ```ts
> beforeModel: (state) => {
>   if (blocked) return { messages: [...], jumpTo: "end" };   // ✗ 안 먹습니다
> }
> ```
>
> 타입 에러도 런타임 에러도 없습니다. `jumpTo` 는 그냥 무시되고 **모델이 평소대로 돕니다**. `messages` 업데이트는 적용되므로 "차단 메시지도 붙고 모델 답변도 붙는" 기괴한 결과가 나옵니다.
>
> 이유는 LangGraph 의 구조에 있습니다. `canJumpTo` 는 "이 노드에서 저기로 가는 엣지를 그래프에 깔아 달라"는 **컴파일 시점 선언**입니다. 선언이 없으면 갈 길 자체가 없어서 점프가 성립하지 않습니다. `jumpTo` 를 쓸 거면 **반드시 `{ canJumpTo: [...], hook: ... }` 형태**로 바꾸세요.
>
> 덤으로 TypeScript 함정 하나: `jumpTo: "end"` 를 그냥 쓰면 TS 가 타입을 `string` 으로 넓혀서 `JumpToTarget` 과 안 맞는다고 합니다. `jumpTo: "end" as const` 로 쓰세요.

> ⚠️ **함정 (`jumpTo: "model"` 은 무한 루프를 만들 수 있다)**: `afterModel` 에서 "답변이 마음에 안 들면 다시 시키기"를 구현하면 이런 모양이 됩니다.
>
> ```ts
> afterModel: {
>   canJumpTo: ["model"],
>   hook: (state) => {
>     if (state.messages.at(-1)!.text.length < 20) {
>       return { messages: [new SystemMessage("너무 짧다")], jumpTo: "model" as const };   // ✗ 가드 없음
>     }
>     return;
>   },
> }
> ```
>
> 모델이 계속 짧게 답하면 영원히 되돌아갑니다. `recursionLimit`(기본 25)에 닿아 `GraphRecursionError` 로 죽는데, **그때까지의 토큰은 이미 다 청구된 뒤**입니다. 되돌리기 횟수를 상태로 세고 상한을 두세요 — 연습문제 7 이 이겁니다. 원칙: **미들웨어가 루프를 만들 수 있다면, 그 루프를 끊는 조건도 미들웨어가 책임진다.**

---

## 12-8. 실전 — 비용 추적 미들웨어 + 토큰 예산 초과 시 중단

여기까지 배운 걸 전부 합칩니다. 요구사항은 이렇습니다.

- 모델 호출마다 실제 토큰 사용량을 읽어 비용을 계산한다
- 누적 토큰이 예산을 넘으면 **모델을 더 이상 부르지 않고** 종료한다
- 끝날 때 최종 리포트를 남긴다

쓰는 재료: `stateSchema`(누적) + `wrapModelCall`(계측) + `beforeModel` + `jumpTo`(중단) + `afterAgent`(리포트).

```ts
import { createAgent, createMiddleware, AIMessage } from "langchain";
import { Command, StateSchema, ReducedValue } from "@langchain/langgraph";
import * as z from "zod";

// 100만 토큰당 USD. 실제 단가는 provider 문서를 보세요.
const PRICE_PER_MTOK = { input: 3.0, output: 15.0 } as const;

const BudgetState = new StateSchema({
  inputTokens: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  outputTokens: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  costUsd: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  budgetExceeded: new ReducedValue(z.boolean().default(false), {
    reducer: (_current: boolean, next: boolean) => next,
  }),
});

const createCostTrackingMiddleware = (maxTotalTokens: number) =>
  createMiddleware({
    name: "CostTrackingMiddleware",
    stateSchema: BudgetState,

    // 1) 예산을 이미 넘겼으면 모델을 아예 부르지 않는다.
    beforeModel: {
      canJumpTo: ["end"],
      hook: (state) => {
        const used = state.inputTokens + state.outputTokens;

        if (used >= maxTotalTokens) {
          console.log(`  [예산] ${used} / ${maxTotalTokens} 토큰 — 초과. 모델 호출 중단.`);

          return {
            messages: [
              new AIMessage(
                `토큰 예산(${maxTotalTokens})을 소진해 작업을 중단했습니다. ` +
                  `지금까지 ${used} 토큰, 약 $${state.costUsd.toFixed(4)} 를 썼습니다.`,
              ),
            ],
            budgetExceeded: true,
            jumpTo: "end" as const,
          };
        }

        console.log(`  [예산] ${used} / ${maxTotalTokens} 토큰 사용 — 계속 진행`);
        return;
      },
    },

    // 2) 모델 호출을 감싸 실제 사용량을 읽는다.
    wrapModelCall: async (request, handler) => {
      const response = await handler(request);

      // usage_metadata 는 optional 입니다. 제공자가 안 주면 undefined 입니다.
      const usage = response.usage_metadata;
      if (usage === undefined) {
        console.log("  [비용] usage_metadata 없음 — 이번 호출은 집계 불가");
        return response;
      }

      const cost =
        (usage.input_tokens / 1_000_000) * PRICE_PER_MTOK.input +
        (usage.output_tokens / 1_000_000) * PRICE_PER_MTOK.output;

      console.log(`  [비용] in=${usage.input_tokens} out=${usage.output_tokens} → $${cost.toFixed(6)}`);

      // handler() 를 이미 불렀으므로 AI 메시지는 프레임워크가 알아서 붙여 줍니다.
      return new Command({
        update: {
          inputTokens: usage.input_tokens,
          outputTokens: usage.output_tokens,
          costUsd: cost,
        },
      });
    },

    // 3) 끝날 때 리포트.
    afterAgent: (state) => {
      console.log(
        `\n  [최종] 입력 ${state.inputTokens} + 출력 ${state.outputTokens} = ` +
          `${state.inputTokens + state.outputTokens} 토큰 / $${state.costUsd.toFixed(6)}` +
          `${state.budgetExceeded ? "  ← 예산 초과로 중단됨" : ""}`,
      );
      return;
    },
  });
```

일부러 예산을 아주 작게 잡아 중단이 실제로 걸리는 걸 봅니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather, getPopulation],
  systemPrompt: "너는 도시 정보 비서다. 도시마다 도구를 하나씩 따로 호출해라.",
  middleware: [createCostTrackingMiddleware(1500)],
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "서울, 부산, 제주의 날씨와 인구를 전부 조사해서 표로 정리해줘." }],
});
```

**출력 예시** (토큰 수는 모델 응답에 따라 매번 다릅니다)

```
  [예산] 0 / 1500 토큰 사용 — 계속 진행
  [비용] in=512 out=143 → $0.003681
  [예산] 655 / 1500 토큰 사용 — 계속 진행
  [비용] in=891 out=207 → $0.005778
  [예산] 1753 / 1500 토큰 — 초과. 모델 호출 중단.

  [최종] 입력 1403 + 출력 350 = 1753 토큰 / $0.009459  ← 예산 초과로 중단됨

최종 답변:
AI     │ 토큰 예산(1500)을 소진해 작업을 중단했습니다. 지금까지 1753 토큰, 약 $0.0095 를 썼습니다.

입력 토큰   1403
출력 토큰   350
비용(USD)   $0.009459
예산 초과   true
```

동작을 뜯어봅시다.

- **`wrapModelCall` 이 `Command` 를 반환하는데 AI 메시지가 사라지지 않았습니다.** `handler(request)` 를 먼저 불렀기 때문에 프레임워크가 응답을 추적해 두었다가 `messages` 에 붙여 줍니다. `handler` 를 안 불렀다면 답변 없는 빈 턴이 됐을 겁니다(12-4-3 함정).
- **예산 검사는 `beforeModel` 에서 합니다.** `wrapModelCall` 에서 검사하면 이미 늦습니다 — 거기서는 이번 호출을 막을 수 있어도 "막았다"는 사실을 상태에 남기고 루프를 끝내기가 번거롭습니다. "부를지 말지"는 노드형 훅, "부르는 방법"은 래퍼형 훅입니다.
- **`1753 / 1500` 처럼 예산을 넘겨서 멈춥니다.** 모델을 부르기 **전에는** 이번 호출이 몇 토큰을 쓸지 알 수 없기 때문입니다. 정확한 상한이 필요하면 `beforeModel` 에서 메시지를 세어 추정하거나(`model.getNumTokens()`), 예산을 실제보다 낮게 잡아 여유를 두세요.

> 💡 **실무 팁 — 단가를 코드에 박지 마세요**: `PRICE_PER_MTOK` 를 상수로 박아 두면 provider 가 가격을 바꿀 때 조용히 틀린 청구서를 만듭니다. 실무에서는 모델 이름 → 단가 맵을 설정 파일이나 원격 설정으로 빼고, **모르는 모델이면 0 이 아니라 경고를 찍으세요.** `usage_metadata` 가 `undefined` 인 경우도 마찬가지입니다 — 조용히 0으로 넘기면 "비용이 0인 에이전트"가 만들어집니다. `input_token_details.cache_read` 는 단가가 다르므로(캐시 읽기는 훨씬 쌉니다) 정확한 비용을 원하면 그것도 따로 계산해야 합니다.

> 💡 **실무 팁 — 예산은 스레드 단위입니다**: 위 미들웨어의 토큰은 `state` 에 있으므로 `thread_id` 마다 따로 셉니다([Step 10](../step-10-memory/)). "이 대화가 얼마나 썼나"에는 맞지만 "이 사용자가 이번 달에 얼마나 썼나"에는 안 맞습니다. 후자는 상태가 아니라 외부 저장소(Store, DB)에 있어야 합니다 — [Step 15](../step-15-long-term-memory/) 에서 다룹니다. **"제어에 쓰는 값"과 "기록에 남길 값"의 자리를 구분하세요.**

---

## 정리

| 훅 | 종류 | 대표 용도 |
|---|---|---|
| `beforeAgent` | 노드형 | 초기화, 사용자 정보 로드 (invoke 당 1번) |
| `beforeModel` | 노드형 | 사전 검사, 가드레일, **예산 초과 시 중단** |
| `wrapModelCall` | 래퍼형 | **동적 프롬프트/모델**, 폴백, 재시도, 계측 |
| `afterModel` | 노드형 | 응답 검증, 되돌리기, 카운터 |
| `wrapToolCall` | 래퍼형 | **캐싱, 권한, 감사**, 타임아웃, 인자 검열 |
| `afterAgent` | 노드형 | 최종 리포트, 정리 (invoke 당 1번) |

**순서**: before 계열 → 등록 순서 / after 계열 → **역순** / wrap 계열 → 양파처럼 중첩.

**상태**: 카운터·누적기는 `StateSchema` + `ReducedValue`(합치기 리듀서). `_` 로 시작하는 필드는 결과에서 숨겨집니다.

**흐름 제어**: `jumpTo` 는 `"model"` / `"tools"` / `"end"` 세 곳. 반드시 `{ canJumpTo, hook }` 형태로.

**핵심 함정 5가지**

1. **`state` 를 mutate 하면 아무 일도 안 일어난다.** `state.count++` 는 무시됩니다. 리듀서는 훅의 **반환값**만 봅니다. 새 객체를 반환하세요. 스냅샷을 mutate 하면 체크포인터의 과거 상태까지 오염됩니다.
2. **`handler` 를 안 부르면 모델이 아예 안 돈다.** `wrapModelCall` 에서 `handler` 없이 `Command` 만 반환하면 답변 없는 빈 턴이 됩니다. 에러도 안 납니다. 부르지 않을 거면 `AIMessage` 를 직접 만드세요.
3. **미들웨어에서 던진 예외는 에이전트 전체를 죽인다.** 부분 결과도 없이 `invoke()` 가 통째로 실패합니다. "거부"는 `throw` 가 아니라 `ToolMessage` 나 `jumpTo: "end"` 로 표현하세요.
4. **`beforeModel` 에서 `messages` 를 통째로 갈아끼우면 `tool_call`/`ToolMessage` 짝이 깨져 provider 400.** 대화가 짧을 땐 안 터지고, 도구를 쓰기 시작해 길어지는 순간 프로덕션에서 터집니다. 반환할 것은 **덧붙일 메시지만** 입니다.
5. **`await` 하나가 `try/catch` 를 죽인다.** `return handler(req)` 는 Promise 를 즉시 반환하므로 rejection 이 `catch` 를 그냥 통과합니다. 폴백·재시도·시간 측정이 전부 조용히 죽습니다. **`return await handler(req)`.**

**보너스 함정**: `canJumpTo` 없는 `jumpTo` 는 무시된다 / `ToolMessage` 를 통째로 캐시하면 `tool_call_id` 가 어긋나 2회차부터 400 / 병렬 도구 호출에서 덮어쓰기 리듀서는 값을 잃는다.

---

## 연습문제

1. **메시지 개수 제한** — 대화 메시지가 `maxMessages` 개 이상이면 모델을 부르지 않고 `AIMessage("대화가 너무 길어졌습니다.")` 를 넣고 종료하는 미들웨어를 만드세요. (힌트: `{ canJumpTo, hook }`)
2. **지연시간 측정** — 모델 호출이 몇 ms 걸렸는지 재서 찍는 미들웨어를 만드세요. **모델이 에러를 던져도 시간은 찍혀야 합니다.** (힌트: `try/finally`, 그리고 `await`)
3. **도구 타임아웃** — 도구가 `timeoutMs` 안에 안 끝나면 포기하고 **에러 `ToolMessage` 를 반환**하는 미들웨어를 만드세요. `throw` 하면 안 됩니다. (힌트: `Promise.race`)
4. **도구별 호출 횟수** — 어떤 도구가 몇 번 불렸는지 `{ get_weather: 3 }` 같은 객체로 누적하는 미들웨어를 만드세요. 모델이 도구를 **병렬로** 불러도 합계가 맞아야 합니다. (힌트: 합치기 리듀서)
5. **재시도** — 모델 호출이 실패하면 지수 백오프(100ms, 200ms, 400ms...)로 최대 `maxRetries` 번 재시도하는 미들웨어를 만드세요. 다 실패하면 마지막 에러를 던집니다. **`return handler(request)` 라고 쓰면 왜 재시도가 안 도는지 설명할 수 있어야 합니다.**
6. **인자 검열** — `send_email` 의 `body` 인자에서 카드번호 패턴(`/\d{4}-\d{4}-\d{4}-\d{4}/g`)을 `[REDACTED]` 로 바꾼 뒤 도구를 실행하는 미들웨어를 만드세요. **`request.toolCall.args` 를 직접 수정하면 안 됩니다** — 왜일까요?
7. **짧은 답변 되돌리기** — 모델이 도구를 안 부르고 답변이 20자 미만이면 "다시 답해라"라고 시키고 모델로 되돌아가는 미들웨어를 만드세요. **되돌리기는 최대 1번만** — 안 그러면 무한 루프입니다.
8. **속도 제한** — 모델 호출이 초당 `maxPerSecond` 회를 넘지 않게 강제하고, 총 대기 시간을 상태에 누적하는 미들웨어를 만드세요. 생각해 볼 것: "마지막 호출 시각"을 **모듈 변수에 둘까 `state` 에 둘까?** 둘의 차이는 무엇일까요?

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 13 — Human-in-the-Loop](../step-13-hitl/)

`wrapToolCall` 로 도구를 가로챌 수 있다는 걸 배웠습니다. 그럼 "가로챈 다음 **사람에게 물어보고** 결정한다"도 가능하겠죠. 그게 HITL 이고, `interrupt()` 로 구현됩니다. 12-5 에서 "`GraphInterrupt` 만은 `MiddlewareError` 로 안 감싸진다"고 한 게 바로 이 때문입니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(12-1 ~ 12-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 훅이 뛰는 순서를 눈으로 확인하고, 그다음 `exercise.ts` 의 8문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `ANTHROPIC_API_KEY` 가 필요하고 실제로 모델을 호출합니다. 실행은 프로젝트 루트에서 `npx tsx docs/reference/langchain/step-12-middleware-custom/practice.ts` 입니다. OpenAI 로 바꾸려면 모델 문자열을 `"openai:gpt-5.5"` 로 고치고 `OPENAI_API_KEY` 를 넣으면 됩니다 — 미들웨어 코드는 한 줄도 안 바뀝니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[12-1] ~ [12-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다. 전부 돌리면 30~60초 걸리니, 특정 절만 보고 싶으면 맨 아래 `main()` 의 호출부를 주석 처리하세요.

- `[12-1]` 의 `tracer(label)` 이 이 파일의 백미입니다. 6개 훅에 전부 로그만 심은 미들웨어를 **두 개 겹쳐**(`middleware: [tracer("A"), tracer("B")]`) 놓아서, "before 는 `A→B` / after 는 `B→A` / wrap 은 `A→ B→ 모델 →B← A←`" 라는 규칙이 출력에 그대로 그려집니다. 표로 외우려 하지 말고 이 출력을 한 번 보세요.
- `[12-4-3]` 의 `initChatModel("anthropic:claude-이런-모델-없음")` 은 오타가 아니라 **의도된 것**입니다. 존재하지 않는 모델 이름은 생성 시점에는 통과하고 `invoke` 시점에 provider 가 404 를 던지므로, 폴백이 실제로 발동하는 걸 API 키만으로 재현할 수 있습니다.
- `[12-5]` 의 `middleware: [auditMiddleware, permissionMiddleware, cachingMiddleware]` 순서를 바꿔 가며 돌려 보세요. `[권한, 감사, 캐시]` 로 뒤집으면 차단된 `delete_record` 호출이 감사 로그에서 사라집니다. 배열 순서가 보안 경계라는 게 실감납니다.
- `[12-5]` 의 도구들은 실행될 때 `[도구 실제 실행]` 을 찍습니다. 2회차에서 이 줄이 **안 찍히는 것**이 캐시가 먹었다는 증거이고, `delete_record` 에서 이 줄이 **찍히면** 권한 검사가 뚫린 것입니다.
- `[12-9]` 는 본문에 없는 보너스 절로, 미들웨어에서 `throw` 했을 때 `MiddlewareError.isInstance(error)` 가 `true` 가 되고 `error.cause` 에 원본이 담기는 걸 직접 확인합니다. 12-5 의 "예외는 에이전트를 죽인다" 함정을 손으로 겪어 보는 자리입니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고, 그 아래 `createMiddleware({ name })` 만 남아 있으니 훅을 채워 넣으면 됩니다.

- `main()` 안의 검증 코드가 **전부 주석 처리되어 있습니다.** 문제를 하나 풀 때마다 해당 블록의 주석을 풀어 확인하세요. 그냥 실행하면 섹션 제목만 8개 찍히고 끝납니다 — 정상입니다.
- `slowSearch` 도구는 일부러 `setTimeout` 으로 3초를 채웁니다. 문제 3 에서 `timeoutMs=1000` 을 주면 **반드시** 타임아웃되도록 만들어 둔 것이라, 네트워크 상황과 무관하게 재현됩니다.
- `sendEmail` 도구는 문제 6 의 검열 대상입니다. `body` 에 카드번호가 섞여 들어오도록 프롬프트를 짜 두었습니다.
- 문제 5 의 힌트에 `⚠️ 이 문제의 진짜 함정` 이라고 적어 둔 대목이 이 파일에서 가장 중요합니다. 답을 보기 전에 `await` 를 뺀 버전과 넣은 버전을 **둘 다 돌려서** 로그가 몇 줄 찍히는지 세어 보세요.
- 문제 8 의 "모듈 변수 vs state" 는 코드를 짜는 문제가 아니라 **설계를 고르는 문제**입니다. 양쪽으로 다 만들어 보고 `thread_id` 를 바꿔 가며 돌리면 차이가 드러납니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답과 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어 보세요. 각 정답 위 주석에 "왜 이렇게 짜야 하는가"와 "이렇게 짜면 조용히 틀린다"가 함께 적혀 있고, `main()` 의 각 블록 위에는 기대 결과가 달려 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 5]` 가 이 파일의 하이라이트입니다. `return await handler(request)` 에서 **`await` 하나가 미들웨어의 전부**입니다. 이걸 빼면 `[재시도] 시도 1/3` 만 찍히고 끝납니다 — `2/3`, `3/3` 이 안 찍히는 게 유일한 단서인데, 에러 메시지는 정상이라 알아채기가 매우 어렵습니다. 공식 문서의 재시도 예제조차 이 모양이니 남 얘기가 아닙니다.
- `[정답 3]` 은 `Promise.race` 를 **판별 유니온**(`{ kind: "done", value }` / `{ kind: "timeout" }`)으로 감쌉니다. `Symbol` 센티널로 race 하면 TS 가 타입을 좁혀 주지 못해 결국 `as` 캐스팅을 쓰게 되기 때문입니다. 그리고 `Promise.resolve(handler(request))` 로 감싼 이유도 주석에 있습니다 — `handler` 의 반환 타입은 `PromiseOrValue<...>` 라서 **Promise 가 아닐 수도** 있습니다. `handler(request).then(...)` 이라고 쓰면 `tsc` 가 `Property 'then' does not exist` 로 잡아 줍니다. 덤으로: `Promise.race` 는 진 쪽을 **취소하지 않습니다.** 도구는 백그라운드에서 3초를 마저 채웁니다.
- `[정답 4]` 의 리듀서는 `{ ...current }` 로 새 객체를 만든 뒤 병합합니다. 덮어쓰기 리듀서(`(_a, b) => b`)로 바꿔서 돌려 보면 도시 3개를 조회했는데 카운트가 `1` 로 남는 걸 볼 수 있습니다 — 모델이 병렬로 부를 때만 재현되므로 운이 나쁘면 몇 번 돌려야 합니다. 그리고 `wrapToolCall` 에서 `Command` 를 반환할 때는 **`messages: [result]` 를 직접 실어 줘야 합니다.** `wrapModelCall` 과 달리 프레임워크가 대신 붙여 주지 않습니다.
- `[정답 6]` 의 `CARD_PATTERN.lastIndex = 0` 은 있어야 합니다. `/g` 플래그가 붙은 정규식은 `test()` 가 `lastIndex` 를 남기므로, 리셋하지 않으면 **같은 문자열인데도 다음 호출에서 `false`** 가 나옵니다. 미들웨어와 무관한 순수 JS 함정이지만 검열이 조용히 새는 원인이 되므로 넣어 두었습니다.
- `[정답 8]` 의 결론은 "마지막 호출 시각은 **모듈 변수**, 총 대기 시간은 **state**" 입니다. rate limit 은 프로세스가 provider 를 때리는 속도에 대한 제약이라 대화 단위가 아니고, `state` 에 두면 `thread_id` 마다 리셋되어 사용자 100명이 동시에 100번 때립니다 — 429 를 막으려고 만든 미들웨어가 429 를 못 막습니다. 반대로 총 대기 시간은 "이 대화가 얼마나 기다렸나"라 `state` 가 맞습니다. 주석에 적었듯 **모듈 변수 방식도 서버 인스턴스가 여러 대면 안 통합니다** — 진짜 프로덕션에는 Redis 가 필요합니다.

```ts file="./solution.ts"
```
