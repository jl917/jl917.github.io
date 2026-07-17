# Step 08 — createAgent, 첫 에이전트

> **학습 목표**
> - `createAgent` 의 **모든 옵션**을 구분해서 쓰고, 각각을 언제 켜는지 판단한다
> - 첫 에이전트를 만들고 `invoke` 로 돌린 뒤 **`result` 객체를 해부**한다
> - `stream({ streamMode: "updates" })` 로 **모델 → 도구 → 모델** 실행 흐름을 눈으로 추적한다
> - `recursionLimit` 과 `GraphRecursionError` 로 **무한 루프를 방어**한다
> - **에이전트를 쓸 때와 워크플로를 쓸 때**를 구분한다
> - `responseFormat` 으로 최종 답변을 **구조화**한다
>
> **선행 스텝**: [Step 07 — 도구 호출 루프 직접 구현](../step-07-tool-loop/)
> **예상 소요**: 90분

[Step 07](../step-07-tool-loop/) 에서 우리는 도구 호출 루프를 손으로 만들었습니다. `while` 문을 돌리고, `response.tool_calls` 가 비었는지 확인하고, 도구를 실행해서 `ToolMessage` 로 되돌려 넣고, `tool_call_id` 를 짝지어 주고, 루프 횟수를 세서 무한 루프를 막았습니다. 동작은 했지만 매번 같은 코드를 다시 쓰는 것도 일이고, 그 과정에서 `tool_call_id` 하나만 빠뜨려도 대화가 조용히 깨졌습니다.

`createAgent` 는 **그 루프를 대신 돌려주는 함수**입니다. 이 스텝은 이 코스의 심장부입니다 — 앞으로 나올 스트리밍(Step 09), 메모리(Step 10), 미들웨어(Step 11~12), HITL(Step 13)이 전부 `createAgent` 의 옵션 하나씩입니다. 여기서 옵션 표를 제대로 익혀 두면 남은 스텝은 그 표의 칸을 하나씩 채워 나가는 일이 됩니다. 그러니 이 스텝의 목표는 "에이전트를 하나 만들어 봤다"가 아니라 **"에이전트 안에서 무슨 일이 벌어지는지 볼 수 있게 되는 것"** 입니다.

---

## 8-1. createAgent 옵션 전부

먼저 전체 지도를 봅시다. `createAgent` 는 옵션 객체 하나를 받고 **컴파일된 에이전트**를 돌려줍니다.

```ts
import { createAgent } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",   // 필수
  tools: [],                               // 사실상 필수
  systemPrompt: "...",
  responseFormat: undefined,
  checkpointer: undefined,
  contextSchema: undefined,
  stateSchema: undefined,
  middleware: [],
  name: undefined,
});
```

`model` 만 필수이고 나머지는 전부 선택입니다. 하지만 "선택"이라는 말에 속으면 안 됩니다 — `systemPrompt` 없이 만든 에이전트는 도구를 엉뚱하게 씁니다(8-2의 함정). 옵션별로 정리하면 이렇습니다.

### 핵심 옵션

| 옵션 | 타입 | 필수 | 무엇인가 | 언제 쓰나 |
|---|---|---|---|---|
| `model` | `string \| ChatModel` | ✅ | `"provider:model"` 문자열 또는 모델 인스턴스 | 항상. 파라미터(temperature 등)를 조절하려면 인스턴스로 |
| `tools` | `Tool[]` | ❌ | 모델이 호출할 수 있는 도구 목록 | 거의 항상. 비우면 그냥 챗 모델과 같다 |
| `systemPrompt` | `string \| SystemMessage` | ❌ | 매 모델 호출 앞에 붙는 시스템 메시지 | **거의 항상**. 도구 사용 규칙을 여기 적는다 |
| `responseFormat` | zod 스키마 / JSON 스키마 / `toolStrategy()` / `providerStrategy()` | ❌ | 최종 답변의 구조 | 답을 코드로 받아 써야 할 때 (8-7) |
| `checkpointer` | `BaseCheckpointSaver \| boolean` | ❌ | 대화 상태를 저장/복원 | `invoke` 간 기억이 필요할 때 → [Step 10](../step-10-memory/) |
| `contextSchema` | zod 스키마 | ❌ | **매 실행마다 주입**하는 값의 형태 (userId 등) | 도구/프롬프트가 실행 단위 값을 알아야 할 때 → [Step 14](../step-14-context-runtime/) |
| `stateSchema` | 스키마 | ❌ | 실행 **사이에 유지**되는 커스텀 상태 필드 | messages 외의 값을 상태에 얹을 때 |
| `middleware` | `Middleware[]` | ❌ | 루프 각 지점에 끼어드는 훅 | 재시도·요약·PII·승인 등 → [Step 11](../step-11-middleware-builtin/) |
| `name` | `string` | ❌ | 에이전트 식별자 | 멀티 에이전트에서 → [Step 18](../step-18-multi-agent/) |

### 나머지 옵션

표에 없으면 "안 쓴다"고 오해하기 쉬워서 전부 적습니다.

| 옵션 | 타입 | 무엇인가 | 언제 쓰나 |
|---|---|---|---|
| `store` | `BaseStore` | 스레드를 넘나드는 영속 저장소 | 장기 메모리 → [Step 15](../step-15-long-term-memory/) |
| `description` | `string` | 상위 감독자(supervisor) LLM 에게 이 에이전트를 설명하는 문구 | 멀티 에이전트에서 서브에이전트로 쓸 때 |
| `includeAgentName` | `"inline" \| undefined` | 에이전트 이름을 AIMessage 에 어떻게 노출할지 | `"inline"` 이면 `<name>...</name><content>...</content>` 형태로 본문에 삽입 |
| `signal` | `AbortSignal` | 실행 전체를 취소하는 신호 | 타임아웃·사용자 취소 |
| `version` | `"v1" \| "v2"` (기본 `"v2"`) | 도구 노드가 병렬 도구 호출을 처리하는 방식 | 아래 설명 |
| `streamTransformers` | `(() => StreamTransformer)[]` | `streamEvents(..., { version: "v3" })` 에 항상 붙는 변환기 | 비용 추적 등 도메인 스트림 → [Step 09](../step-09-streaming/) |

`version` 은 헷갈리니 한 번 짚습니다. 모델이 한 턴에 도구를 **여러 개** 부르면 그걸 어떻게 실행하느냐의 차이입니다.

- `"v1"`: 도구 노드가 `AIMessage` 하나를 통째로 받아 모든 도구 호출을 `Promise.all` 로 동시에 실행합니다. 도구가 서브그래프나 긴 비동기 작업을 부를 때 **진짜 병렬**이 필요하면 v1.
- `"v2"` (기본): 도구 호출 하나하나가 독립된 그래프 태스크가 됩니다. **도구 호출 단위 체크포인팅**, 개별 실패 격리, 도구 안에서의 `interrupt()` 지원이 필요하면 v2.

> 💡 **실무 팁**: `version` 은 기본값 `"v2"` 를 그대로 두세요. v2 라야 [Step 13](../step-13-hitl/) 의 Human-in-the-Loop 이 도구 호출 단위로 걸립니다. v1 이 필요한 순간은 "도구가 무거운 비동기 작업이고 체크포인트 쓰기 때문에 사실상 직렬화되는 게 측정으로 확인됐을 때" 정도입니다. 그 전에는 건드리지 마세요.

> 💡 **실무 팁 — 모델 문자열 vs 인스턴스**: `model: "anthropic:claude-sonnet-4-6"` 은 내부적으로 `initChatModel` 을 부릅니다. 짧아서 좋지만 temperature·maxTokens 를 못 줍니다. 파라미터가 필요하면 인스턴스를 넘기세요. OpenAI 라면 `model: "openai:gpt-5.5"` 또는 `new ChatOpenAI({ model: "gpt-5.5" })` 로 이 스텝의 모든 예제가 그대로 동작합니다.
>
> ```ts
> import { ChatAnthropic } from "@langchain/anthropic";
> const agent = createAgent({
>   model: new ChatAnthropic({ model: "claude-sonnet-4-6", temperature: 0 }),
>   tools: [getWeather],
> });
> ```

---

## 8-2. 첫 에이전트 만들고 돌리기

Step 07 에서 손으로 짠 루프를 그대로 `createAgent` 로 바꿔 봅시다. 도구부터 정의합니다.

```ts
import { createAgent, tool } from "langchain";
import * as z from "zod";

const getWeather = tool(
  ({ city }) => `${city}의 날씨: 맑음, 기온 21도, 습도 45%`,
  {
    name: "get_weather",
    description: "특정 도시의 현재 날씨를 조회한다. 도시 이름은 한국어로 받는다.",
    schema: z.object({
      city: z.string().describe("날씨를 조회할 도시 이름 (예: 서울)"),
    }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt:
    "너는 날씨 안내원이다. 날씨를 물으면 반드시 get_weather 도구로 확인한 뒤 답한다. " +
    "도구 결과에 없는 정보는 추측하지 말고 모른다고 답한다.",
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "서울 날씨 어때?" }],
});

console.log(result.messages.at(-1)?.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
서울의 현재 날씨는 맑고, 기온은 21도, 습도는 45%입니다.
```

Step 07 의 `while` 루프, `tool_calls` 검사, `ToolMessage` 생성, `tool_call_id` 매칭이 **전부 사라졌습니다.** `createAgent` 가 그 루프를 내부에 갖고 있습니다.

입력 형식도 봐 둡시다. `invoke` 에 넘기는 것은 메시지 배열이 아니라 **`{ messages: [...] }` 객체**입니다. Step 07 에서 `model.invoke(messages)` 처럼 배열을 바로 넘기던 것과 다릅니다. 에이전트는 상태(state)를 다루는 그래프이고, `messages` 는 그 상태의 한 필드이기 때문입니다.

```ts
await model.invoke([{ role: "user", content: "안녕" }]);            // 챗 모델 (Step 02)
await agent.invoke({ messages: [{ role: "user", content: "안녕" }] }); // 에이전트 (지금)
```

> ⚠️ **함정 (systemPrompt 없이 만들면 도구를 엉뚱하게 쓴다)**: `systemPrompt` 를 빼고 위 에이전트를 돌리면 에러가 나지 않습니다. 대신 모델이 **도구를 안 부르고 자기 상식으로 답해 버리는** 일이 생깁니다. "서울은 보통 이맘때 20도 안팎입니다" 같은 그럴듯한 환각이 나오고, 도구가 있는데도 호출 기록이 없습니다. 반대 방향의 사고도 있습니다 — 인사만 했는데 `get_weather("서울")` 를 부르는 것. `systemPrompt` 는 장식이 아니라 **루프의 제어 장치**입니다. 최소한 (1) 이 에이전트의 역할, (2) 각 도구를 언제 부르는가, (3) 도구 결과 밖의 정보는 지어내지 말 것 — 이 세 가지는 적으세요.

> ⚠️ **함정 (도구 description 이 곧 프롬프트다)**: 위에서 `description` 에 "도시 이름은 한국어로 받는다"를 넣은 것은 장식이 아닙니다. 모델은 도구의 이름·설명·스키마의 `.describe()` 만 보고 호출 여부와 인자를 정합니다. 설명이 `"날씨"` 한 단어면 모델은 `city: "Seoul"` 로 부를 수도, 아예 안 부를 수도 있습니다. 그리고 이건 **에러가 안 납니다** — 그냥 결과가 나빠질 뿐입니다. 도구 설명은 코드 주석이 아니라 프롬프트의 일부라고 생각하세요. ([Step 06](../step-06-tools/) 참고)

---

## 8-3. 에이전트 상태 — messages 는 어떻게 쌓이는가

`result` 를 마지막 메시지만 꺼내 쓰고 버리면 에이전트 안에서 무슨 일이 있었는지 영영 모릅니다. 해부해 봅시다.

```ts
const result = await agent.invoke({
  messages: [{ role: "user", content: "서울 날씨 어때?" }],
});

console.log("result 의 키:", Object.keys(result));
console.log("메시지 개수:", result.messages.length);

for (const [i, m] of result.messages.entries()) {
  console.log(`[${i}] ${m.constructor.name}`);
  console.log(`     text       : ${JSON.stringify(m.text)}`);
  console.log(`     tool_calls : ${JSON.stringify(m.tool_calls ?? [])}`);
}
```

**출력** (구조는 결정적입니다. `text` 내용만 모델에 따라 달라집니다)
```
result 의 키: [ 'messages' ]
메시지 개수: 4
[0] HumanMessage
     text       : "서울 날씨 어때?"
     tool_calls : []
[1] AIMessage
     text       : ""
     tool_calls : [{"name":"get_weather","args":{"city":"서울"},"id":"toolu_01...","type":"tool_call"}]
[2] ToolMessage
     text       : "서울의 날씨: 맑음, 기온 21도, 습도 45%"
     tool_calls : []
[3] AIMessage
     text       : "서울의 현재 날씨는 맑고..."
     tool_calls : []
```

이 네 줄이 에이전트의 전부입니다.

| 인덱스 | 타입 | 누가 만들었나 | 의미 |
|---|---|---|---|
| 0 | `HumanMessage` | 내가 `invoke` 로 넣음 | 사용자 질문 |
| 1 | `AIMessage` | 모델 (1차 호출) | "도구를 불러야겠다" — `tool_calls` 가 차 있고 `text` 는 비었다 |
| 2 | `ToolMessage` | 에이전트가 도구를 실행 | 도구 결과. `tool_call_id` 로 [1]과 짝지어져 있다 |
| 3 | `AIMessage` | 모델 (2차 호출) | 최종 답변 — `tool_calls` 가 비었다 |

**루프 종료 조건은 "`tool_calls` 가 빈 `AIMessage`"** 입니다. Step 07 에서 우리가 손으로 쓴 `if (!response.tool_calls?.length) break;` 와 정확히 같은 조건이고, `createAgent` 는 이걸 그래프의 조건부 엣지로 갖고 있습니다.

주목할 것이 두 가지 있습니다.

**첫째, `messages` 는 덮어쓰기가 아니라 누적(append)입니다.** 내가 넣은 건 1개인데 결과는 4개입니다. 각 단계가 상태에 메시지를 **추가**하지 교체하지 않습니다. 그래서 도구를 3번 부르면 `1 + (2 × 3) + 1 = 8` 개가 됩니다. 이 누적 규칙이 다음 함정으로 이어집니다.

**둘째, `result` 의 키는 `messages` 하나뿐입니다.** `responseFormat` 을 주지 않았기 때문입니다. 주면 `structuredResponse` 가 생깁니다(8-7).

> ⚠️ **함정 (checkpointer 없으면 invoke 간 기억이 없다)**: `messages` 가 누적된다는 걸 보고 "그럼 다음 `invoke` 도 이어지겠네"라고 생각하기 쉽습니다. **아닙니다.** 누적은 **한 번의 `invoke` 안에서만** 일어납니다. 아래를 보세요.
>
> ```ts
> await agent.invoke({ messages: [{ role: "user", content: "내 이름은 지은이야" }] });
> const r = await agent.invoke({ messages: [{ role: "user", content: "내 이름이 뭐게?" }] });
> console.log(r.messages.length);  // 2 — 첫 대화가 통째로 없다
> ```
>
> 두 번째 `invoke` 의 `result.messages` 는 길이가 **2**(질문 + 답변)입니다. 첫 대화는 어디에도 없습니다. 에러도 경고도 없이 모델은 "이름을 알려주신 적이 없습니다"라고 답합니다. `checkpointer` 를 주지 않았기 때문입니다. 더 고약한 건 `checkpointer` 없이 `{ configurable: { thread_id: "abc" } }` 만 넘기는 경우입니다 — `thread_id` 는 조용히 무시되고, 기억이 남는 것처럼 코드만 보입니다. 해결은 [Step 10](../step-10-memory/) 에서 다룹니다. 지금은 **"기본 에이전트는 매 `invoke` 가 백지에서 시작한다"** 만 기억하세요.

> 💡 **실무 팁**: `m.text` 는 메시지의 텍스트를 문자열로 꺼내는 접근자입니다. `m.content` 는 provider 에 따라 문자열일 수도, 콘텐츠 블록 배열(`[{type:"text",...}, {type:"tool_use",...}]`)일 수도 있어서 그대로 `console.log` 하면 지저분합니다. 사람이 읽을 로그에는 `.text` 를, 블록 단위 처리가 필요하면 `.contentBlocks` 를 쓰세요. ([Step 03](../step-03-messages/) 참고)

---

## 8-4. 실행 흐름 추적 — stream({ streamMode: "updates" })

`invoke` 는 다 끝난 결과만 줍니다. 중간에 뭘 했는지 보려면 `stream` 을 쓰고 **`streamMode: "updates"`** 를 켭니다. 이건 토큰 스트리밍이 아닙니다 — **한 스텝이 끝날 때마다 그 스텝이 상태에 무엇을 추가했는지**를 흘려보냅니다.

```ts
for await (const chunk of await agent.stream(
  { messages: [{ role: "user", content: "서울 날씨 어때?" }] },
  { streamMode: "updates" },
)) {
  for (const [node, update] of Object.entries(chunk)) {
    console.log(`── NODE: ${node}`);
    for (const m of update?.messages ?? []) {
      if (m.tool_calls?.length) {
        for (const tc of m.tool_calls) {
          console.log(`   도구 호출 → ${tc.name}(${JSON.stringify(tc.args)})`);
        }
      } else {
        console.log(`   ${m.constructor.name}: ${m.text}`);
      }
    }
  }
}
```

**출력** (노드 이름과 순서는 결정적입니다. 도구 인자와 최종 문장은 모델에 따라 다릅니다)
```
── NODE: model_request
   도구 호출 → get_weather({"city":"서울"})
── NODE: tools
   ToolMessage: 서울의 날씨: 맑음, 기온 21도, 습도 45%
── NODE: model_request
   AIMessage: 서울의 현재 날씨는 맑고, 기온은 21도, 습도는 45%입니다.
```

**모델 → 도구 → 모델.** Step 07 에서 손으로 돌리던 루프가 그대로 보입니다.

### 청크의 정확한 모양

`updates` 청크는 **노드 이름을 키로 갖는 객체**입니다.

```ts
{ model_request: { messages: [ AIMessage ] } }
{ tools:         { messages: [ ToolMessage ] } }
{ model_request: { messages: [ AIMessage ] } }
```

그래서 `Object.entries(chunk)` 로 `[노드이름, 상태변화]` 를 꺼내는 것입니다. 값은 **그 스텝이 추가한 것만** 들어 있습니다 — 전체 `messages` 가 아니라 새로 생긴 메시지만.

`createAgent` 가 만드는 그래프의 노드는 네 개입니다.

| 노드 | 하는 일 |
|---|---|
| `__start__` | 진입점 |
| `model_request` | 모델을 호출한다 → `AIMessage` 를 추가 |
| `tools` | `tool_calls` 를 실행한다 → `ToolMessage` 를 추가 |
| `__end__` | 종료 |

`model_request` → (`tool_calls` 있으면) `tools` → `model_request` → ... → (`tool_calls` 없으면) `__end__`. 이 순환이 에이전트 루프입니다.

### streamMode 별로 무엇이 나오나

| `streamMode` | 나오는 것 | 언제 쓰나 |
|---|---|---|
| `"updates"` | 스텝마다 **추가된 상태 변화** (노드 이름이 키) | **디버깅·흐름 추적.** 이 절의 주인공 |
| `"values"` | 스텝마다 **전체 상태 스냅샷** | 매 시점의 messages 전체가 필요할 때 |
| `"messages"` | `(토큰, 메타데이터)` 튜플 | 사용자에게 글자를 흘려 보여줄 때 |
| `"custom"` | 노드 안에서 writer 로 직접 쏜 데이터 | 진행률 등 커스텀 이벤트 |
| `"debug"` | 실행 중 가용한 모든 정보 | 최후의 수단 |

여러 개를 동시에 켜려면 배열로 주고, 그러면 결과가 `[모드, 청크]` **튜플**로 바뀝니다.

```ts
for await (const [mode, chunk] of await agent.stream(input, {
  streamMode: ["updates", "messages"],
})) {
  // mode 로 분기
}
```

스트리밍은 [Step 09](../step-09-streaming/) 에서 본격적으로 다룹니다.

> 💡 **실무 팁 — updates 는 개발자용 X-ray 다**: 에이전트가 이상하게 답할 때 프롬프트부터 고치려는 충동이 듭니다. 그 전에 `updates` 를 5분만 켜 보세요. 대개 원인이 즉시 보입니다 — 도구를 아예 안 불렀거나(설명 부실), 엉뚱한 인자로 불렀거나(스키마 `.describe()` 부실), 같은 도구를 같은 인자로 반복해서 부르고 있거나(도구 결과가 모호함). 세 경우의 처방이 전부 다르므로, **보지 않고 고치는 것은 추측**입니다. 저는 새 에이전트를 만들면 `updates` 를 찍는 러너부터 짜 놓고 시작합니다.

> ⚠️ **함정 (에이전트는 확률적이다 — 같은 입력에 다른 경로)**: 위 트레이스가 항상 3줄일 거라고 가정하지 마세요. 같은 질문에 어떤 실행은 도구를 한 번, 어떤 실행은 두 번(도시 이름을 바꿔 재시도), 어떤 실행은 0번 부릅니다. `temperature: 0` 을 줘도 마찬가지입니다 — 0은 "가장 확률 높은 토큰 선택"이지 결정성 보장이 아니고, provider 쪽 배치·하드웨어 차이로 결과가 흔들립니다. 그래서 **"트레이스가 정확히 N스텝"에 의존하는 테스트는 반드시 깨집니다.** 검증은 "도구가 최소 1회 호출됐는가", "최종 답에 21이 들어 있는가" 처럼 **경로가 아니라 결과의 성질**로 하세요. 경로를 고정하고 싶다면, 그건 에이전트가 아니라 워크플로가 필요하다는 신호입니다(8-6).

---

## 8-5. recursionLimit 과 무한 루프 방어

Step 07 에서 우리는 이렇게 썼습니다.

```ts
for (let i = 0; i < 10; i++) {   // ← 손으로 만든 안전장치
  // ...
}
```

이게 없으면 모델이 도구를 계속 부르는 상황에서 프로그램이 영원히 돌고 토큰 비용이 무한히 쌓입니다. `createAgent` 에도 같은 장치가 있고, 이름은 **`recursionLimit`** 입니다. **기본값은 25** 이고, 실행 시 config 로 줍니다 — `createAgent` 옵션이 아니라 **`invoke`/`stream` 의 두 번째 인자**라는 데 주의하세요.

```ts
await agent.invoke(
  { messages: [{ role: "user", content: "..." }] },
  { recursionLimit: 10 },   // ← createAgent 가 아니라 여기
);
```

한도를 넘으면 **에러로 터집니다.** 조용히 멈추지 않습니다.

```ts
import { GraphRecursionError } from "@langchain/langgraph";

try {
  await agent.invoke(
    { messages: [{ role: "user", content: "ping 을 계속 눌러봐" }] },
    { recursionLimit: 6 },
  );
} catch (e) {
  if (e instanceof GraphRecursionError) {
    console.log("이름       :", e.name);
    console.log("에러 코드  :", (e as any).lc_error_code);
    console.log("메시지     :", e.message);
  } else {
    throw e;
  }
}
```

**출력** (에러 메시지는 결정적입니다)
```
이름       : GraphRecursionError
에러 코드  : GRAPH_RECURSION_LIMIT
메시지     : Recursion limit of 6 reached without hitting a stop condition. You can increase the limit by setting the "recursionLimit" config key.

Troubleshooting URL: https://docs.langchain.com/oss/javascript/langgraph/GRAPH_RECURSION_LIMIT/
```

### 한도는 "스텝" 단위지 "도구 호출" 단위가 아니다

이걸 착각하면 한도를 두 배로 잘못 잡습니다. 카운트되는 것은 **그래프 노드 실행 횟수**입니다. 한 번의 도구 왕복은 `model_request` + `tools` = **2스텝**이고, 마지막 답변에 `model_request` 가 1스텝 더 듭니다.

| 도구 호출 왕복 수 | 소모 스텝 | 계산 |
|---|---|---|
| 0회 (바로 답변) | 1 | 모델 1 |
| 1회 | 3 | 모델 + 도구 + 모델 |
| 2회 | 5 | (모델 + 도구) × 2 + 모델 |
| N회 | 2N + 1 | |

기본값 25 는 **도구 왕복 12번**에 해당합니다. 반대로 "도구를 최대 3번까지만"을 원하면 `recursionLimit: 7` 입니다.

> ⚠️ **함정 (recursionLimit 초과는 에러로 터진다)**: `GraphRecursionError` 는 **부분 결과를 주지 않습니다.** `result.messages` 로 "여기까지는 했는데요"를 받는 게 아니라 `invoke` 자체가 throw 합니다. 그때까지 태운 토큰 비용은 그대로 청구되고 산출물은 0입니다. 그래서 프로덕션 에이전트는 **반드시 `try/catch` 로 감싸고** `GraphRecursionError` 를 따로 처리해야 합니다 — 사용자에게 "처리 중 문제가 발생했습니다"를 보여주든, 질문을 좁혀 재시도하든. 감싸지 않으면 사용자는 500 을 봅니다. 그리고 **한도를 올리는 게 대개 답이 아닙니다** — 25에서 터지는 에이전트는 50에서도 터집니다. 루프에 빠진 원인(도구 결과가 모호해서 모델이 같은 호출을 반복)을 `updates` 로 먼저 찾으세요.

> ⚠️ **함정 (도구가 많을수록 헤맨다)**: "도구를 많이 붙일수록 유능해진다"는 직관은 틀립니다. 도구가 20개면 모델은 매 턴 20개의 설명을 다 읽고 하나를 골라야 합니다. 설명이 겹치는 도구(`search_docs` 와 `find_document`)가 둘 있으면 모델은 둘 다 부르거나, 하나를 부르고 결과가 마음에 안 들어 다른 걸 부르고, 그러다 `recursionLimit` 에 닿습니다. 게다가 도구 스키마는 매 호출 프롬프트에 들어가므로 **입력 토큰이 매 턴 늘어납니다.** 경험칙은 **한 에이전트당 도구 5~7개**이고, 그 이상이면 (1) 도구를 합치거나, (2) 서브에이전트로 쪼개거나([Step 18](../step-18-multi-agent/)), (3) `llmToolSelectorMiddleware` 로 관련 도구만 추려서 넣습니다([Step 11](../step-11-middleware-builtin/)).

> 💡 **실무 팁**: `recursionLimit` 은 **비용 상한**이기도 합니다. 사용자 대면 챗봇처럼 "3번 안에 못 끝내면 어차피 못 끝낸다"가 성립하는 곳은 `recursionLimit: 7` 로 조여 두는 편이 낫습니다. 반대로 리서치 에이전트처럼 오래 도는 게 정상인 워크로드는 50~100 도 씁니다. 기본값 25 를 "적당히 안전한 값"이라고 믿고 방치하지 말고, **이 에이전트가 정상일 때 몇 스텝을 쓰는지 `updates` 로 세어 보고 그 2배**로 잡으세요.

---

## 8-6. 에이전트 vs 워크플로 — 언제 쓰고 언제 쓰면 안 되나

여기까지 오면 모든 걸 에이전트로 만들고 싶어집니다. 그러면 안 됩니다. LangGraph 문서는 둘을 이렇게 가릅니다.

> **워크플로**는 미리 정해진 코드 경로를 따르며 정해진 순서로 동작한다.
> **에이전트**는 동적이며, LLM 이 자기 절차와 도구 사용을 스스로 정한다.

핵심 질문 하나로 정리됩니다 — **"단계를 내가 미리 알 수 있는가?"** 알 수 있으면 워크플로, 모르면 에이전트입니다.

| | 워크플로 | 에이전트 |
|---|---|---|
| 경로를 정하는 주체 | **개발자** (코드) | **LLM** (런타임) |
| 실행 경로 | 결정적. 같은 입력 → 같은 경로 | 확률적. 같은 입력 → 다른 경로 |
| 비용 예측 | 쉽다 (LLM 호출 수가 고정) | 어렵다 (호출 수가 가변) |
| 디버깅 | 쉽다 (스택 트레이스처럼 읽힘) | 어렵다 (트레이스를 봐야 안다) |
| 테스트 | 단위 테스트 가능 | 결과의 성질로만 검증 |
| 지연시간 | 낮고 일정 | 높고 들쭉날쭉 |
| 새 상황 대응 | 못 한다 (코드에 없으면 못 함) | 한다 |
| 구현 | `StateGraph` ([Step 17](../step-17-langgraph/)) | `createAgent` (지금) |

### 에이전트를 쓰면 안 되는 경우

- **단계가 고정되어 있다.** "PDF 추출 → 번역 → 요약 → 저장". LLM 이 순서를 정할 여지가 없습니다. 에이전트로 만들면 "번역을 건너뛰기로 결정"하는 사고가 언젠가 납니다.
- **결정성·감사(audit)가 요구된다.** 금융·의료처럼 "왜 이 경로로 갔나"를 설명해야 하는 곳. "모델이 그렇게 정했습니다"는 답변이 안 됩니다.
- **지연시간 예산이 빡빡하다.** 에이전트는 최소 2번, 보통 3~5번의 모델 호출을 합니다. 200ms 안에 답해야 하면 애초에 불가능합니다.
- **LLM 이 필요 없다.** 분류 결과로 분기하는 것뿐이라면 `if` 문이 정답입니다. 에이전트는 `if` 문을 아주 비싸고 불안정하게 만든 것입니다.

### 에이전트를 써야 하는 경우

- **단계 수를 모른다.** "이 이슈의 원인을 찾아줘" — 파일을 3개 볼지 30개 볼지 시작할 때 알 수 없습니다.
- **도구 선택이 맥락에 달렸다.** 사용자 질문에 따라 DB 를 볼지, 문서를 볼지, 계산을 할지 달라집니다.
- **피드백 루프가 필요하다.** 도구 결과를 보고 다음 행동을 정해야 합니다. 검색 결과가 비면 검색어를 바꿔 다시 찾는 것.
- **경우의 수가 코드로 못 적을 만큼 많다.**

### 워크플로 패턴 다섯 가지

에이전트 대신 쓸 수 있는 정해진 형태들입니다. 이름을 알아 두면 "이건 에이전트가 아니라 라우팅이면 되겠는데"라는 판단이 빨라집니다.

| 패턴 | 구조 | 쓰는 곳 |
|---|---|---|
| **Prompt chaining** | LLM 호출을 직렬 연결, 각 호출이 앞 출력을 받음 | 문서 번역, 초안 → 검수 |
| **Parallelization** | 여러 LLM 호출을 동시에 | 속도(하위 작업 분할) 또는 신뢰도(같은 걸 여러 번) |
| **Routing** | 입력을 분류하고 전용 처리기로 보냄 | 문의 유형별 처리 (가격 문의 vs 환불 문의) |
| **Orchestrator-worker** | 조율자가 작업을 쪼개 워커에 위임하고 결과를 합침 | 하위 작업을 미리 못 정할 때 (여러 파일 동시 수정) |
| **Evaluator-optimizer** | 한 LLM 이 생성, 다른 LLM 이 평가, 기준 충족까지 반복 | 번역 품질 개선, 반복 다듬기 |

Orchestrator-worker 와 Evaluator-optimizer 는 에이전트에 가장 가깝습니다 — 차이는 **경로의 뼈대가 코드에 있느냐**입니다. Evaluator-optimizer 는 "생성 → 평가 → 반복"이 코드로 고정돼 있고, 에이전트는 그 반복 여부조차 모델이 정합니다.

> 💡 **실무 팁 — 실무의 정답은 대개 섞는 것**: 순수 워크플로도 순수 에이전트도 아닙니다. **바깥은 워크플로, 안쪽 한 칸만 에이전트**가 가장 잘 작동합니다. 예를 들어 고객 문의 처리는 이렇게 짭니다 — 분류(워크플로 라우팅) → 단순 FAQ 면 템플릿 응답(LLM 없음) / 복잡한 조사면 에이전트 호출 → 결과 검증(워크플로) → 발송. 에이전트가 담당하는 구간이 좁을수록 비용·지연·사고가 다 같이 줄어듭니다. **"이걸 에이전트로 만들자"가 아니라 "에이전트가 꼭 필요한 구간이 어디인가"를 물으세요.**

> 💡 **실무 팁**: 판단이 안 서면 **워크플로로 먼저 짜 보세요.** 짜다가 "여기서 다음에 뭘 할지 코드로 못 적겠는데"라는 지점이 나오면 거기가 에이전트 자리입니다. 안 나오면 워크플로가 정답이었던 겁니다. 반대 순서(에이전트로 시작 → 통제가 안 돼서 워크플로로 되돌리기)는 훨씬 비쌉니다.

---

## 8-7. responseFormat 으로 구조화된 최종 답변

에이전트의 최종 답이 자연어 문장이면 사람은 읽을 수 있지만 **코드는 못 씁니다.** "기온이 21도"라는 문장에서 `21` 을 정규식으로 파내는 순간 그 코드는 깨질 운명입니다. `responseFormat` 에 zod 스키마를 주면 최종 답이 **검증된 객체**로 나옵니다.

```ts
import { createAgent, tool } from "langchain";
import * as z from "zod";

const WeatherReport = z.object({
  city: z.string().describe("조회한 도시 이름"),
  tempC: z.number().describe("섭씨 기온"),
  condition: z.enum(["맑음", "흐림", "비", "눈"]).describe("날씨 상태"),
  advice: z.string().describe("한 문장 옷차림 조언"),
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "너는 날씨 안내원이다. 반드시 get_weather 로 확인한 뒤 답한다.",
  responseFormat: WeatherReport,     // ← 이 한 줄
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "서울 날씨 알려줘" }],
});

console.log("result 의 키:", Object.keys(result));
console.log(result.structuredResponse);
console.log("기온에 5를 더하면:", result.structuredResponse.tempC + 5);
```

**출력 예시** (필드 구조는 스키마가 보장하지만, 값은 모델 응답이라 매번 다릅니다)
```
result 의 키: [ 'messages', 'structuredResponse' ]
{
  city: '서울',
  tempC: 21,
  condition: '맑음',
  advice: '가벼운 겉옷 하나면 충분한 날씨입니다.'
}
기온에 5를 더하면: 26
```

`result.structuredResponse.tempC` 가 **`number`** 입니다. 파싱도, 정규식도, `Number()` 캐스팅도 없습니다. 타입스크립트도 이걸 압니다 — zod 스키마에서 타입이 추론되므로 `result.structuredResponse.tempc` 라고 오타를 내면 컴파일 에러가 납니다.

`responseFormat` 을 주지 않으면 `structuredResponse` 는 **결과 상태에 아예 존재하지 않습니다.** 8-3 에서 `Object.keys(result)` 가 `['messages']` 하나였던 이유입니다.

### 두 가지 전략 — toolStrategy vs providerStrategy

스키마를 그대로 넘기면 LangChain 이 모델 능력을 보고 알아서 고릅니다. 명시하고 싶으면 이렇게 씁니다.

```ts
import { providerStrategy, toolStrategy } from "langchain";

responseFormat: providerStrategy(WeatherReport)   // provider 네이티브 구조화 출력 사용
responseFormat: toolStrategy(WeatherReport)       // 가짜 도구를 하나 만들어 그걸 부르게 함
```

| 전략 | 원리 | 장점 | 조건 |
|---|---|---|---|
| `providerStrategy` | provider 의 네이티브 structured output 기능 | 스키마 준수를 provider 가 강제 | 모델이 지원해야 함 |
| `toolStrategy` | 스키마를 도구처럼 바인딩하고 모델이 그 도구를 부르게 함 | **도구를 지원하는 모든 모델**에서 동작. 검증 실패 시 재시도 내장 | 도구 지원 |

직접 스키마를 넘기는 것(`responseFormat: WeatherReport`)은 대개 `providerStrategy(WeatherReport)` 와 같은 결과입니다 — provider 가 네이티브 지원하면 그걸 쓰기 때문입니다.

> ⚠️ **함정 (구조화 출력은 모델 호출을 한 번 더 쓴다)**: `responseFormat` 은 공짜가 아닙니다. 에이전트 루프가 끝난 **뒤에 별도의 LLM 호출**이 일어나 구조화된 응답을 만듭니다. 즉 지연시간과 토큰이 한 번분 더 듭니다. 그리고 이 추가 호출도 `recursionLimit` 예산을 씁니다 — 8-5의 `2N + 1` 계산에 여유를 두세요. 사람에게 문장으로 보여줄 뿐이라면 `responseFormat` 은 빼는 게 맞습니다.

> ⚠️ **함정 (zod `.optional()` vs `.nullable()` 은 provider 마다 다르다)**: `z.string().optional()` 은 JSON 스키마로 변환될 때 "required 목록에서 빠짐"이 되고, `.nullable()` 은 "타입이 `["string","null"]`"이 됩니다. OpenAI 의 strict 모드는 **모든 필드가 required 여야 한다**고 요구하므로 `.optional()` 이 거부되거나 조용히 무시될 수 있고, Anthropic 은 `.optional()` 을 받아들이지만 모델이 그 필드를 그냥 안 채우고 넘어갑니다. "값이 없을 수 있다"를 표현하려면 provider 를 넘나들며 가장 안전한 것은 **`.nullable()` 로 명시하고 `.describe()` 에 "모르면 null" 이라고 적는 것**입니다. 이것 역시 에러가 아니라 **필드가 조용히 사라지는** 형태로 나타납니다. ([Step 05](../step-05-structured-output/) 참고)

> 💡 **실무 팁**: 스키마의 모든 필드에 `.describe()` 를 다세요. 필드 이름만으로는 모델이 못 맞힙니다 — `advice` 만 보고는 한 문장을 쓸지 세 문단을 쓸지 모릅니다. `describe` 는 그 필드에 대한 미니 프롬프트입니다. 그리고 자유 문자열보다 `z.enum(["맑음","흐림","비","눈"])` 처럼 **선택지를 좁히면** 모델이 훨씬 안정적으로 채웁니다. "약간 흐림", "구름 조금" 같은 변주가 원천 차단되니까요.

---

## 8-8. 실전 예제 — 도구 3개짜리 쇼핑 상담 에이전트

옵션을 하나씩 봤으니 이제 합칩니다. 도구 3개(상품 검색 / 재고 조회 / 견적 계산)를 가진 실용 에이전트입니다. 8-5에서 말한 "도구 5~7개" 상한 안이고, 셋의 역할이 겹치지 않습니다.

```ts
import { createAgent, tool } from "langchain";
import * as z from "zod";

/* 가짜 카탈로그 — 실제로는 DB 나 API */
const CATALOG = [
  { id: "P1", name: "게이밍 노트북 RTX4060", category: "노트북", price: 2190000, stock: 4 },
  { id: "P2", name: "27인치 4K 모니터",      category: "주변기기", price: 459000, stock: 12 },
  { id: "P3", name: "무선 기계식 키보드",     category: "주변기기", price: 139000, stock: 0 },
  { id: "P4", name: "인체공학 사무용 의자",   category: "가구",     price: 329000, stock: 7 },
];

const searchProducts = tool(
  ({ keyword, maxPrice }) => {
    const hits = CATALOG.filter(
      (p) =>
        (p.name.includes(keyword) || p.category.includes(keyword)) &&
        (maxPrice == null || p.price <= maxPrice),
    );
    if (hits.length === 0) return "검색 결과 없음. 다른 키워드를 시도하세요.";
    return hits.map((p) => `${p.id} | ${p.name} | ${p.category} | ${p.price}원`).join("\n");
  },
  {
    name: "search_products",
    description:
      "상품명 또는 카테고리 키워드로 상품을 검색한다. 상품 ID 를 알아내려면 먼저 이 도구를 써야 한다. " +
      "재고는 알려주지 않으므로 재고가 필요하면 check_stock 을 따로 호출한다.",
    schema: z.object({
      keyword: z.string().describe("검색 키워드 (예: 모니터, 노트북, 주변기기)"),
      maxPrice: z.number().nullable().describe("가격 상한(원). 상한이 없으면 null"),
    }),
  },
);

const checkStock = tool(
  ({ productId }) => {
    const p = CATALOG.find((x) => x.id === productId);
    if (!p) return `상품 ${productId} 없음. search_products 로 올바른 ID 를 먼저 확인하세요.`;
    return p.stock === 0 ? `${p.name}: 품절` : `${p.name}: 재고 ${p.stock}개`;
  },
  {
    name: "check_stock",
    description:
      "상품 ID 로 재고 수량을 조회한다. productId 는 search_products 결과에 나온 ID(P1 형식)여야 한다.",
    schema: z.object({
      productId: z.string().describe("상품 ID. 예: P2"),
    }),
  },
);

const quote = tool(
  ({ items }) => {
    let subtotal = 0;
    const lines: string[] = [];
    for (const it of items) {
      const p = CATALOG.find((x) => x.id === it.productId);
      if (!p) return `상품 ${it.productId} 없음. 견적을 계산할 수 없습니다.`;
      const amount = p.price * it.quantity;
      subtotal += amount;
      lines.push(`${p.name} × ${it.quantity} = ${amount}원`);
    }
    const shipping = subtotal >= 500000 ? 0 : 3000;
    return [
      ...lines,
      `소계: ${subtotal}원`,
      `배송비: ${shipping}원 (50만원 이상 무료)`,
      `합계: ${subtotal + shipping}원`,
    ].join("\n");
  },
  {
    name: "quote",
    description:
      "상품 ID 와 수량 목록으로 배송비를 포함한 최종 견적을 계산한다. 금액 계산은 직접 하지 말고 반드시 이 도구를 쓴다.",
    schema: z.object({
      items: z
        .array(
          z.object({
            productId: z.string().describe("상품 ID. 예: P1"),
            quantity: z.number().int().positive().describe("수량"),
          }),
        )
        .describe("견적에 포함할 상품과 수량 목록"),
    }),
  },
);

const Recommendation = z.object({
  productIds: z.array(z.string()).describe("추천한 상품 ID 목록"),
  totalPrice: z.number().describe("quote 도구가 계산한 합계 금액(원). 직접 계산하지 말 것"),
  allInStock: z.boolean().describe("추천 상품이 모두 재고가 있으면 true"),
  reason: z.string().describe("추천 이유를 2문장 이내로"),
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchProducts, checkStock, quote],
  systemPrompt: [
    "너는 온라인 쇼핑몰 상담원이다.",
    "",
    "규칙:",
    "1. 상품 ID 는 반드시 search_products 로 먼저 확인한다. ID 를 추측하지 않는다.",
    "2. 재고를 언급하기 전에 반드시 check_stock 으로 확인한다.",
    "3. 금액 계산은 절대 직접 하지 않는다. quote 도구를 쓴다.",
    "4. 품절 상품은 추천하지 않는다.",
    "5. 도구 결과에 없는 정보는 지어내지 않고 모른다고 답한다.",
  ].join("\n"),
  responseFormat: Recommendation,
  name: "shopping_assistant",
});

for await (const chunk of await agent.stream(
  {
    messages: [
      {
        role: "user",
        content: "주변기기 중에 50만원 이하로 살 만한 거 추천해줘. 재고 있는 걸로 2개씩.",
      },
    ],
  },
  { streamMode: "updates", recursionLimit: 12 },
)) {
  for (const [node, update] of Object.entries(chunk)) {
    for (const m of (update as any)?.messages ?? []) {
      if (m.tool_calls?.length) {
        for (const tc of m.tool_calls) {
          console.log(`[${node}] → ${tc.name}(${JSON.stringify(tc.args)})`);
        }
      } else if (node === "tools") {
        console.log(`[tools] ← ${String(m.text).split("\n")[0]} ...`);
      }
    }
  }
}
```

**출력 예시** (노드 이름은 결정적이지만, **도구 호출 순서와 횟수는 매 실행 달라집니다**)
```
[model_request] → search_products({"keyword":"주변기기","maxPrice":500000})
[tools] ← P2 | 27인치 4K 모니터 | 주변기기 | 459000원 ...
[model_request] → check_stock({"productId":"P2"})
[model_request] → check_stock({"productId":"P3"})
[tools] ← 27인치 4K 모니터: 재고 12개 ...
[tools] ← 무선 기계식 키보드: 품절 ...
[model_request] → quote({"items":[{"productId":"P2","quantity":2}]})
[tools] ← 27인치 4K 모니터 × 2 = 918000원 ...
```

트레이스가 이 에이전트의 사고 과정을 그대로 보여줍니다. 검색 → 후보 둘의 재고 확인 → 품절인 P3 제외 → P2 만 견적. `systemPrompt` 의 규칙 1~4가 순서대로 지켜졌습니다.

`check_stock` 이 **한 턴에 두 번** 호출된 것에 주목하세요. 모델이 P2 와 P3 의 재고를 동시에 물어본 것이고, `createAgent` 는 이를 병렬로 실행합니다.

최종 결과는 이렇습니다.

```ts
const result = await agent.invoke(
  { messages: [{ role: "user", content: "주변기기 중에 50만원 이하로..." }] },
  { recursionLimit: 12 },
);
console.log(result.structuredResponse);
```

**출력 예시** (값은 매번 다릅니다)
```
{
  productIds: [ 'P2' ],
  totalPrice: 918000,
  allInStock: true,
  reason: '27인치 4K 모니터는 50만원 이하 주변기기 중 유일하게 재고가 있는 상품입니다. 무선 기계식 키보드는 품절이라 제외했습니다.'
}
```

> ⚠️ **함정 (병렬 도구 호출은 순서를 보장하지 않는다)**: 위 트레이스에서 `check_stock(P2)` 와 `check_stock(P3)` 가 나란히 나갔습니다. 이때 `ToolMessage` 가 **호출한 순서대로 돌아온다는 보장이 없습니다.** P3 결과가 P2 보다 먼저 올 수 있습니다. 순서에 의존하는 코드(`toolMessages[0]` 이 P2 라고 가정)는 대부분의 경우 우연히 맞다가 어느 날 틀립니다. 짝을 맞추려면 **`tool_call_id` 로 매칭**하세요. 더 나아가, 도구가 부수효과를 갖는다면(재고 차감, 결제) 병렬 실행 자체가 위험합니다 — 그런 도구는 순서가 보장되어야 하므로 하나의 도구로 합치거나 워크플로로 빼는 게 맞습니다.

> 💡 **실무 팁 — 도구의 에러 메시지도 프롬프트다**: `check_stock` 이 없는 ID 를 받았을 때 `"상품 P9 없음"` 이 아니라 `"상품 P9 없음. search_products 로 올바른 ID 를 먼저 확인하세요."` 라고 답하게 한 것을 보세요. 모델은 이 문장을 읽고 실제로 `search_products` 를 부릅니다 — **도구의 반환값이 다음 행동을 유도하는 프롬프트**입니다. 반대로 도구가 예외를 던져 버리면 모델은 아무것도 못 배우고 같은 실수를 반복합니다. **도구 안에서 예상 가능한 실패는 throw 하지 말고, "무엇이 잘못됐고 다음에 뭘 해야 하는지" 를 문자열로 반환**하세요. 이 습관 하나가 `recursionLimit` 사고의 절반을 없앱니다.

---

## 정리

이 스텝에서 Step 07 의 수작업 루프가 전부 사라졌습니다. 대응 관계를 봅시다.

| Step 07 (손으로) | Step 08 (createAgent) |
|---|---|
| `while (true) { ... }` | 내장 루프 (`model_request` ↔ `tools`) |
| `if (!res.tool_calls?.length) break;` | 조건부 엣지 → `__end__` |
| `toolsByName[tc.name].invoke(tc.args)` | `tools` 노드 |
| `new ToolMessage({ tool_call_id: tc.id, ... })` | 자동 (id 매칭 포함) |
| `messages.push(...)` | 상태 누적 (append) |
| `for (let i = 0; i < 10; i++)` | `recursionLimit` (기본 25) |
| 루프 안에서 `console.log` | `stream({ streamMode: "updates" })` |
| `JSON.parse(res.text)` | `responseFormat` → `result.structuredResponse` |

**옵션 요약**

| 옵션 | 한 줄 요약 |
|---|---|
| `model` | `"anthropic:claude-sonnet-4-6"` 또는 인스턴스 |
| `tools` | 5~7개까지. 역할이 겹치면 안 됨 |
| `systemPrompt` | 역할 + 도구 사용 규칙 + 환각 금지. 생략하면 사고 남 |
| `responseFormat` | 답을 코드로 쓸 때만. 모델 호출 1회 추가 |
| `checkpointer` | invoke 간 기억 (Step 10) |
| `contextSchema` | 실행마다 주입하는 값 (Step 14) |
| `middleware` | 루프 훅 (Step 11~12) |
| `name` | 멀티 에이전트 식별 (Step 18) |

**결정적인 것 / 비결정적인 것** — 이 구분이 에이전트 코딩의 핵심입니다.

| 결정적 (믿고 코드 짜도 됨) | 비결정적 (믿으면 안 됨) |
|---|---|
| 노드 이름 (`model_request`, `tools`) | 도구 호출 순서와 횟수 |
| updates 청크 모양 `{ 노드: { messages: [...] } }` | 최종 문장의 표현 |
| 루프 종료 조건 (`tool_calls` 빈 AIMessage) | 트레이스 스텝 수 |
| `structuredResponse` 의 필드 구조 | `structuredResponse` 의 값 |
| `GraphRecursionError` 의 이름과 코드 | 어떤 도구를 고를지 |

**핵심 함정 3가지**

1. **에이전트는 확률적이다.** 같은 입력에 다른 경로로 간다. `temperature: 0` 도 결정성을 보장하지 않는다. **경로가 아니라 결과의 성질로 검증하라.** 경로를 고정해야 한다면 그건 워크플로가 필요하다는 신호다.
2. **`systemPrompt` 와 도구 `description` 이 루프의 제어 장치다.** 둘이 부실하면 에러 없이 조용히 나쁜 결과가 나온다 — 도구를 안 부르고 환각하거나, 엉뚱한 도구를 부르거나, 같은 도구를 반복해 `recursionLimit` 에 닿는다.
3. **`recursionLimit` 초과는 `GraphRecursionError` 로 터지며 부분 결과가 없다.** 태운 토큰은 그대로, 산출물은 0. 반드시 `try/catch` 로 감싸고, **한도를 올리기 전에 `updates` 로 원인을 먼저 보라.**

**같이 기억할 것**: `checkpointer` 없으면 `invoke` 간 기억이 없다 — `thread_id` 만 줘도 조용히 무시된다 (Step 10). 도구가 많을수록 헤맨다 — 5~7개가 상한. 병렬 도구 호출은 순서 보장이 없다 — `tool_call_id` 로 매칭하라.

---

## 연습문제

1. `get_weather` 도구 하나를 가진 에이전트를 만들되 **`systemPrompt` 를 주지 말고** "서울 날씨 어때?" 를 물으세요. `stream({ streamMode: "updates" })` 로 트레이스를 찍고, **도구가 호출되었는지** 확인한 뒤 결과를 주석으로 적으세요. 그다음 `systemPrompt` 를 넣고 다시 돌려 차이를 관찰하세요.
2. 8-2 의 에이전트를 `invoke` 로 돌린 뒤 `result.messages` 를 순회하며 각 메시지의 **타입 이름 / `text` / `tool_calls` 개수**를 표처럼 출력하세요. 메시지가 왜 4개인지 주석으로 설명하세요.
3. 같은 에이전트를 **연속 두 번** `invoke` 하세요 — 첫 번째는 "내 이름은 지은이야", 두 번째는 "내 이름이 뭐야?". 두 번째 `result.messages.length` 를 출력하고, 모델이 이름을 기억하지 못하는 이유를 주석으로 쓰세요.
4. `recursionLimit` 을 **3** 으로 주고 도구를 반드시 부르게 되는 질문을 던져 `GraphRecursionError` 를 일부러 내세요. `try/catch` 로 잡아 `e.name` 과 `(e as any).lc_error_code` 를 출력하세요. 그다음 "도구 왕복 1회에 3스텝"이라는 8-5의 계산에 따라 **성공하는 최소 `recursionLimit`** 을 찾아 주석으로 적으세요.
5. `responseFormat` 에 `z.object({ tempC: z.number(), summary: z.string() })` 를 주고 에이전트를 돌린 뒤, `Object.keys(result)` 를 **`responseFormat` 이 있을 때와 없을 때** 각각 출력해 비교하세요. `result.structuredResponse.tempC + 1` 이 문자열 연결이 아니라 덧셈이 되는지 확인하세요.
6. 다음 세 요구사항 각각에 대해 **에이전트인가 워크플로인가**를 고르고 이유를 2문장으로 쓰세요.
   - (a) 업로드된 PDF 를 텍스트 추출 → 영어로 번역 → 3문장 요약 → DB 저장
   - (b) 사내 위키·DB·달력 중 필요한 곳을 찾아가며 "다음 주 팀 회의 준비해줘" 를 처리
   - (c) 고객 문의를 "환불 / 배송 / 기타" 로 분류해 각각 다른 템플릿으로 응답
7. 8-8 의 쇼핑 에이전트에서 `check_stock` 의 `description` 을 `"재고 조회"` 로 **줄이고** 돌려 보세요. 트레이스가 어떻게 달라지는지(도구를 안 부르거나, 잘못된 `productId` 로 부르거나) 관찰해 주석으로 적으세요. 그다음 원래 설명으로 되돌리세요.
8. 8-8 의 에이전트에 도구를 **하나 더** 추가하세요 — `compare_products(idA, idB)` 로 두 상품을 비교해 표 문자열을 반환합니다. `description` 을 `search_products` 와 **일부러 비슷하게**(예: `"상품을 찾아 비교한다"`) 써 보고, 모델이 헷갈려 두 도구를 다 부르거나 잘못 고르는지 트레이스로 확인하세요. 그다음 설명을 명확히 갈라("이미 아는 두 상품 ID 를 비교한다. 검색은 search_products 를 쓴다") 다시 관찰하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 09 — 스트리밍](../step-09-streaming/)

이 스텝에서 `streamMode: "updates"` 로 스텝 단위 흐름을 봤습니다. Step 09 에서는 `"messages"` 모드로 **토큰 단위** 스트리밍을 다루고, 스트리밍 중 도구 호출 청크가 **조각난 부분 JSON 으로 온다**는 함정을 만납니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(8-1 ~ 8-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 트레이스를 눈으로 확인하고, `exercise.ts` 의 8개 문제를 직접 푼 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 자기완결적이라 복사해서 바로 돌릴 수 있습니다. 실행 전에 `project/.env` 에 `ANTHROPIC_API_KEY` 가 있어야 합니다.

```bash
npx tsx docs/reference/langchain/step-08-create-agent/practice.ts
```

OpenAI 를 쓴다면 각 파일 상단의 `MODEL` 상수를 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하면 나머지 코드는 그대로 동작합니다. 파일 상단에 상수 한 곳으로 모아 둔 이유가 이것입니다.

### practice.ts

본문을 따라가며 실행할 예제를 `[8-1] ~ [8-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 대응하므로 본문을 읽다 막히면 같은 번호 블록을 찾아 돌려 보면 됩니다.

- `[8-3]` 은 `result.messages` 를 순회하며 **타입 / text / tool_calls** 를 한 줄씩 찍습니다. "4개 메시지"의 정체를 직접 보는 것이 이 블록의 목적입니다. 이어서 `checkpointer` 없이 두 번 `invoke` 해서 `messages.length` 가 2로 나오는 **기억 상실**을 재현합니다 — 본문 8-3 함정의 실물입니다.
- `[8-4]` 의 `traceAgent()` 헬퍼가 이 파일에서 가장 재사용 가치가 높습니다. `updates` 청크를 `Object.entries` 로 풀어 노드 이름과 도구 호출을 정렬해 찍습니다. 실무에서 새 에이전트를 만들 때마다 이 함수부터 복사해 쓰세요.
- `[8-5]` 는 `recursionLimit: 4` 로 **일부러** `GraphRecursionError` 를 냅니다. 에러가 나는 게 정상이고, `try/catch` 안에 있으니 파일 실행은 계속됩니다. 잡힌 에러의 `name` / `lc_error_code` / `message` 를 그대로 출력하므로 본문 8-5의 메시지와 대조해 보세요.
- `[8-7]` 은 `responseFormat` 을 **뺀 결과와 넣은 결과의 `Object.keys(result)`** 를 나란히 찍습니다. `['messages']` vs `['messages', 'structuredResponse']` 를 눈으로 확인하는 것이 핵심입니다.
- `[8-8]` 은 도구 3개 + `responseFormat` + `recursionLimit` 을 전부 결합한 완성형입니다. 이 블록만 따로 떼어 실무 에이전트의 출발점으로 써도 됩니다.
- 모델을 실제로 호출하므로 전체 실행에 **API 비용과 30초 내외의 시간**이 듭니다. 특정 블록만 보고 싶으면 파일 하단의 `main()` 에서 원하는 함수만 남기고 주석 처리하세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 그대로 옮겨 담은 파일입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 구현부가 비어 있으니 거기에 직접 코드를 써 넣고 실행해 검증하면 됩니다.

- `[문제 1]` 과 `[문제 7]`, `[문제 8]` 은 **관찰이 곧 답**인 문제입니다. 코드를 "맞게" 쓰는 게 목표가 아니라 트레이스를 보고 무슨 일이 벌어졌는지 주석으로 적는 게 목표입니다. `// → 관찰 결과:` 자리를 비워 뒀습니다.
- `[문제 4]` 는 에러가 나야 정답인 문제입니다. `GraphRecursionError` 가 안 나면 문제를 잘못 푼 것입니다.
- `[문제 6]` 은 코드가 아니라 주석으로만 답하는 문제입니다. (a)/(b)/(c) 아래 빈 주석 줄에 판단과 이유를 쓰세요.
- 파일에는 도구 정의(`getWeather`)와 `traceAgent` 헬퍼가 **미리 채워져 있습니다.** 매번 다시 쓰지 않고 문제 자체에 집중하라는 의도입니다.
- 파일을 그대로 실행하면 대부분 아무것도 출력되지 않습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 1]` 의 관찰 결과는 **실행마다 다를 수 있습니다.** `systemPrompt` 없이도 도구를 잘 부르는 실행이 나올 수 있습니다 — 그게 바로 본문 8-4 함정("확률적")의 실증입니다. 해설은 "5번 돌려서 몇 번 도구를 불렀는지 세어 보라"고 안내합니다. 한 번 돌려 보고 "잘 되네"라고 결론 내리는 게 이 코스에서 가장 위험한 습관입니다.
- `[정답 3]` 의 정답은 `2` 입니다. 그리고 해설에서 `{ configurable: { thread_id: "x" } }` 를 넣어도 여전히 2 라는 것을 **추가로 확인시킵니다** — `checkpointer` 가 없으면 `thread_id` 는 조용히 무시됩니다. Step 10 을 미리 읽지 않고도 "왜 안 되는지"를 정확히 알고 넘어가게 하려는 장치입니다.
- `[정답 4]` 의 최소 `recursionLimit` 은 **3** 입니다(모델 + 도구 + 모델). 다만 모델이 도구를 두 번 부르기로 하면 3으로도 터지므로, 해설은 "정답은 3이지만 실무에서는 여유를 둬라"로 마무리합니다. 이 문제의 진짜 교훈은 숫자가 아니라 **한도가 도구 호출 수가 아니라 노드 스텝 수를 센다**는 것입니다.
- `[정답 6]` 이 이 파일의 하이라이트입니다. (a) 워크플로 — 4단계가 코드로 다 적힙니다. (b) 에이전트 — 어느 소스를 몇 번 볼지 시작할 때 모릅니다. (c) **함정입니다.** 라우팅 워크플로가 정답이고, 심지어 분류에만 LLM 을 쓰고 응답은 템플릿이면 충분합니다. 에이전트라고 답했다면 본문 8-6의 "LLM 이 필요 없다" 항목을 다시 읽으세요.
- `[정답 8]` 은 설명이 겹치는 도구를 붙였을 때와 갈랐을 때의 트레이스를 **연달아** 출력합니다. 겹칠 때는 모델이 `compare_products` 를 ID 없이 부르려다 실패하고 `search_products` 로 되돌아가는 경로가 자주 관찰됩니다 — 도구 왕복이 늘어나 `recursionLimit` 예산을 갉아먹는 것까지 확인해 보세요.

```ts file="./solution.ts"
```
