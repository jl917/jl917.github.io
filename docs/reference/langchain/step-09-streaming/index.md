# Step 09 — 스트리밍

> **학습 목표**
> - `model.stream()` 으로 토큰을 받고 **`concat` 으로 청크를 합친다**
> - `agent.stream()` 의 **streamMode 5종**(`values` / `updates` / `messages` / `custom` / `debug`)을 구분해서 쓴다
> - `streamMode: ["updates", "messages"]` 로 **여러 모드를 동시에 구독**한다
> - `streamEvents({ version: "v3" })` 의 **프로젝션**으로 텍스트·추론·도구 호출을 따로 뽑는다
> - **`tool_call_chunks` 가 부분 JSON 으로 온다**는 것을 알고 누적해서 쓴다
> - 도구 안에서 `config.writer` 로 **진행상황을 내보내고**, SSE 로 브라우저까지 흘린다
>
> **선행 스텝**: [Step 08 — createAgent 첫 에이전트](../step-08-create-agent/)
> **예상 소요**: 80분

[Step 08](../step-08-create-agent/) 에서 만든 에이전트는 `invoke()` 로 부릅니다. 질문을 던지고, 모델이 생각하고, 도구를 부르고, 다시 생각하고, 최종 답을 만들 때까지 — 그 20초 동안 화면에는 아무것도 없습니다. 사용자는 앱이 죽은 줄 압니다. 실제로 에이전트는 잘 돌고 있는데도요.

스트리밍은 이 20초를 **0.4초처럼 느끼게** 만드는 기술입니다. 그리고 에이전트에서는 단순히 "글자가 하나씩 나오는 것" 이상입니다. 어떤 도구를 부르기로 결정했는지, 도구가 지금 몇 퍼센트 진행됐는지, 모델이 무슨 생각을 하고 있는지 — 이 모든 중간 상태를 밖으로 꺼내는 통로가 스트리밍입니다. 이 스텝에서는 그 통로를 전부 열어 보고, 각각이 **무엇을 언제 내보내는지** 정확히 구분합니다.

---

## 9-1. 왜 스트리밍인가 — 체감 지연(TTFT)

같은 프롬프트를 `invoke()` 와 `stream()` 으로 각각 부르고, **첫 출력이 나오기까지의 시간**을 재 봅시다.

```ts
import { ChatAnthropic } from "@langchain/anthropic";

const model = new ChatAnthropic({ model: "claude-sonnet-4-6" });

// (A) invoke — 다 만들 때까지 아무것도 없다
const t0 = Date.now();
const res = await model.invoke("한국의 사계절을 각 두 문장씩 설명해 주세요.");
console.log(`invoke │ 첫 출력까지 ${Date.now() - t0}ms`);

// (B) stream — 첫 청크가 오는 순간을 찍는다
const t1 = Date.now();
let ttft = -1;
const stream = await model.stream("한국의 사계절을 각 두 문장씩 설명해 주세요.");
for await (const chunk of stream) {
  if (ttft === -1) ttft = Date.now() - t1;   // ← 첫 청크에서만
  process.stdout.write(chunk.text);
}
console.log(`\nstream │ 첫 출력까지 ${ttft}ms, 전체 ${Date.now() - t1}ms`);
```

**출력 예시** (모델 응답이므로 매번 다릅니다 — 숫자는 네트워크·모델 부하에 따라 크게 변합니다)
```
invoke │ 첫 출력까지 8420ms
stream │ 첫 출력까지 512ms, 전체 8630ms
```

주목할 점은 **전체 시간은 거의 같다**는 것입니다. 스트리밍은 빠르지 않습니다. 오히려 청크마다 오버헤드가 붙어 아주 조금 느립니다. 바뀐 건 하나뿐입니다 — **사용자가 아무것도 못 보고 기다리는 시간이 8.4초에서 0.5초로 줄었습니다.**

이 첫 출력까지의 시간을 **TTFT**(Time To First Token)라고 부릅니다. 사용자 만족도를 좌우하는 건 전체 완료 시간(total latency)이 아니라 TTFT 입니다. 8초짜리 응답도 0.5초에 시작해서 흘러나오면 "빠르다"고 느끼고, 3초짜리 응답도 3초 동안 빈 화면이면 "느리다"고 느낍니다.

> 💡 **실무 팁**: 관측 대시보드에 **TTFT 와 total latency 를 따로 찍으세요.** 하나로 뭉쳐 놓으면 "p95 지연 12초"라는 숫자만 남는데, 그게 "12초 동안 빈 화면"인지 "0.4초에 시작해서 12초 동안 흘렀는지"는 전혀 다른 문제입니다. 전자는 장애고 후자는 정상입니다. [Step 19](../step-19-observability-eval/) 에서 다시 다룹니다.

---

## 9-2. 모델 레벨 스트리밍 — AIMessageChunk 와 concat

에이전트로 가기 전에 **모델 하나**를 스트리밍하는 것부터 정확히 이해해야 합니다. 여기서 배우는 `concat` 이 이 스텝 전체를 관통합니다.

```ts
const stream = await model.stream("스트리밍을 한 문장으로 설명해 주세요.");

for await (const chunk of stream) {
  console.log(chunk.constructor.name, JSON.stringify(chunk.text));
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AIMessageChunk ""
AIMessageChunk "스트"
AIMessageChunk "리밍은 "
AIMessageChunk "응답이 완성"
AIMessageChunk "되기를 기다리지 "
AIMessageChunk "않고 생성되는 "
AIMessageChunk "즉시 조각조각 "
AIMessageChunk "전달하는 방식입니다."
AIMessageChunk ""
```

여기서 결정적인 사실 하나. `invoke()` 는 `AIMessage` 를 돌려주지만 `stream()` 은 **`AIMessageChunk`** 를 돌려줍니다. 이름이 다른 건 우연이 아닙니다. `AIMessageChunk` 는 "**합쳐질 것을 전제로 만들어진 조각**"입니다. 그래서 `.concat()` 메서드를 가지고 있습니다.

### concat — 조각을 하나의 메시지로

```ts
import { AIMessageChunk } from "@langchain/core/messages";

let full: AIMessageChunk | null = null;

const stream = await model.stream("스트리밍의 장점을 한 문장으로 말해 주세요.");
for await (const chunk of stream) {
  full = full === null ? chunk : full.concat(chunk);
}

console.log(full?.text);
console.log(JSON.stringify(full?.contentBlocks, null, 2));
console.log(JSON.stringify(full?.usage_metadata));
```

**출력 예시** (모델 응답이므로 텍스트는 매번 다릅니다. 단, **구조는 항상 이 모양입니다**)
```
스트리밍은 사용자가 기다린다고 느끼는 시간을 크게 줄여 줍니다.

[
  {
    "type": "text",
    "text": "스트리밍은 사용자가 기다린다고 느끼는 시간을 크게 줄여 줍니다."
  }
]

{"input_tokens":24,"output_tokens":31,"total_tokens":55}
```

`full = full === null ? chunk : full.concat(chunk)` — 이 한 줄이 관용구입니다. 첫 청크는 그대로 쓰고, 이후로는 병합합니다.

`concat` 이 하는 일은 텍스트 이어붙이기가 전부가 아닙니다.

| 필드 | concat 이 하는 일 |
|---|---|
| `content` / `contentBlocks` | 같은 블록끼리 **인덱스로 매칭해서** 병합 (텍스트 블록은 이어붙이고, 새 블록이면 추가) |
| `tool_call_chunks` | `index` 별로 args 문자열을 이어붙이고, **완성된 것만 `tool_calls` 로 승격** |
| `usage_metadata` | 청크마다 흩어져 온 토큰 수를 **합산** |
| `response_metadata` | 마지막 청크에 실려 오는 `stop_reason`, `model` 등을 병합 |
| `id` | 첫 청크의 것을 유지 |

> ⚠️ **함정 (이 스텝 최대의 함정)**: 청크의 **`.content` 를 문자열로 이어붙이지 마세요.**
>
> ```ts
> let text = "";
> for await (const chunk of stream) {
>   text += chunk.content;   // ⚠️ 조용히 깨진다
> }
> ```
>
> 이 코드는 **평범한 텍스트 응답에서는 잘 동작합니다.** 그게 위험한 이유입니다. `content` 는 `string` 일 수도 있고 **콘텐츠 블록 배열**일 수도 있습니다([Step 03](../step-03-messages/)). 추론(reasoning) 블록이나 도구 호출이 섞이는 순간 `content` 는 배열이 되고, `+=` 는 `"[object Object]"` 를 만들어 냅니다. 게다가 문자열로 이어붙이면 **`usage_metadata` 는 영원히 얻을 수 없습니다** — 토큰 수는 마지막 청크에만 실려 오는데, 문자열에는 그걸 담을 자리가 없으니까요.
>
> 규칙: **화면에 흘릴 때만 `chunk.text`** (이건 항상 문자열이라 안전), **누적은 반드시 `concat`.** 둘을 섞어 쓰세요.
>
> ```ts
> let full: AIMessageChunk | null = null;
> for await (const chunk of stream) {
>   process.stdout.write(chunk.text);          // 표시용 — text 는 안전
>   full = full === null ? chunk : full.concat(chunk);  // 누적용 — concat
> }
> ```

> 💡 **실무 팁 — OpenAI 로 바꾸기**: `stream()` 의 사용법은 제공자와 완전히 무관합니다. `new ChatAnthropic({ model: "claude-sonnet-4-6" })` 를 `new ChatOpenAI({ model: "gpt-5.5" })`(`@langchain/openai`)로 바꿔도 위 코드는 한 글자도 안 바뀝니다. 다만 **청크의 잘게 쪼개진 정도**(한 청크에 몇 글자가 오는가)는 제공자마다 다릅니다. 청크 개수에 의존하는 로직을 짜면 제공자를 바꿀 때 깨집니다.

### 스트리밍을 끄고 싶을 때

라우팅용 작은 모델처럼 **토큰이 사용자에게 보이면 안 되는 모델**이 있습니다. 두 가지 방법이 있습니다.

```ts
import { ChatOpenAI } from "@langchain/openai";

// (A) 모델 자체를 비스트리밍으로
const model = new ChatOpenAI({ model: "gpt-5.5", streaming: false });

// (B) 실행은 스트리밍으로 하되 스트림에서만 제외 — "nostream" 태그
const internalModel = new ChatAnthropic({
  model: "claude-haiku-4-5-20251001",
}).withConfig({ tags: ["nostream"] });
```

(B)의 `nostream` 태그가 실무에서 훨씬 유용합니다. 모델은 정상 동작하지만 그 출력만 스트림에서 빠집니다.

---

## 9-3. 에이전트 스트리밍 — streamMode 전부 해부

에이전트는 모델 하나가 아닙니다. 여러 노드(모델 노드, 도구 노드)가 번갈아 도는 그래프입니다. 그래서 "무엇을 스트리밍할 것인가"라는 질문이 새로 생깁니다. 그 답이 **`streamMode`** 입니다.

```ts
import { createAgent, tool } from "langchain";
import * as z from "zod";

const getWeather = tool(
  async ({ city }) => `${city}의 날씨는 맑음, 기온 21도입니다.`,
  {
    name: "get_weather",
    description: "도시 이름을 받아 현재 날씨를 반환합니다.",
    schema: z.object({ city: z.string() }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "당신은 간결하게 답하는 날씨 비서입니다.",
});

const question = { messages: [{ role: "user", content: "서울 날씨 알려줘" }] };
```

지원되는 모드는 다음과 같습니다.

| streamMode | 무엇을 | 언제 | 페이로드 모양 |
|---|---|---|---|
| `values` | **전체 상태** 스냅샷 | 스텝이 끝날 때마다 | `{ messages: [ ...누적 전부... ] }` |
| `updates` | 그 스텝이 **바꾼 것만** | 스텝이 끝날 때마다 | `{ 노드이름: { messages: [...새로 추가된 것] } }` |
| `messages` | **LLM 토큰** | 토큰이 생성될 때마다 | `[messageChunk, metadata]` 튜플 |
| `custom` | `writer` 로 보낸 **임의 데이터** | 도구/노드가 writer 를 부를 때 | 개발자가 정한 그대로 |
| `tools` | 도구 **생명주기 이벤트** | 도구 시작/중간/종료/에러 | `{ name, input \| data \| output \| error, toolCallId }` |
| `debug` | 실행 중 **모든 정보** | 계속 | 내부 구조 전부 |

하나씩 실제로 돌려 봅시다.

### updates — 스텝이 끝날 때마다 "바뀐 것"만

```ts
for await (const chunk of await agent.stream(question, { streamMode: "updates" })) {
  for (const [node, update] of Object.entries(chunk)) {
    console.log(`노드 ${node}:`);
    console.log(JSON.stringify(update, null, 2));
  }
}
```

**출력 예시** (모델 응답이므로 텍스트/ID 는 매번 다릅니다. **구조는 결정적입니다**)
```
노드 model:
{
  "messages": [
    {
      "content": [
        { "type": "tool_use", "id": "toolu_01abc...", "name": "get_weather",
          "input": { "city": "서울" } }
      ],
      "tool_calls": [
        { "name": "get_weather", "args": { "city": "서울" }, "id": "toolu_01abc...", "type": "tool_call" }
      ]
    }
  ]
}
노드 tools:
{
  "messages": [
    { "content": "서울의 날씨는 맑음, 기온 21도입니다.", "name": "get_weather",
      "tool_call_id": "toolu_01abc..." }
  ]
}
노드 model:
{
  "messages": [
    { "content": "서울은 현재 맑고 기온은 21도입니다." }
  ]
}
```

이게 **에이전트의 진행 상황**입니다. "모델이 get_weather 를 부르기로 했다 → 도구가 답을 줬다 → 모델이 최종 답을 만들었다". UI 에 "🔍 날씨 조회 중..." 같은 상태 표시를 띄우려면 이 모드를 씁니다.

주목할 점: chunk 는 **노드 이름으로 한 겹 감싸여** 있습니다. `chunk.messages` 가 아니라 `chunk.model.messages` 입니다. 그래서 `Object.entries()` 로 풀어야 합니다.

### values — 스텝마다 "전체 상태"

```ts
for await (const chunk of await agent.stream(question, { streamMode: "values" })) {
  console.log(`messages 길이 = ${chunk.messages.length}`);
}
```

**출력 예시**
```
messages 길이 = 1
messages 길이 = 2
messages 길이 = 3
messages 길이 = 4
```

`values` 는 매번 **누적된 전체 상태**를 줍니다. 1(사용자) → 2(도구 호출) → 3(도구 결과) → 4(최종 답). 대화가 길어질수록 payload 가 커집니다.

> ⚠️ **함정**: **`values` 를 네트워크로 그대로 흘리지 마세요.** 스텝 10 짜리 대화라면 마지막 스냅샷은 메시지 10개를 통째로 담고 있고, 그걸 스텝마다 다시 보내면 **O(n²)** 만큼의 데이터가 흐릅니다. 첨부 이미지나 긴 도구 결과가 섞이면 수 MB 가 반복 전송됩니다. 네트워크로 보낼 땐 `updates`(델타)를, 최종 상태 한 번만 필요하면 `invoke()` 나 `streamEvents` 의 `stream.output` 을 쓰세요.

### messages — LLM 토큰

```ts
for await (const [token, metadata] of await agent.stream(question, {
  streamMode: "messages",
})) {
  console.log(`[${metadata.langgraph_node}] ${JSON.stringify(token.text)}`);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[model] ""
[model] "서울"
[model] "은 현재 "
[model] "맑고 "
[model] "기온은 21"
[model] "도입니다."
[tools] "서울의 날씨는 맑음, 기온 21도입니다."
```

**항목이 튜플**입니다 — `[messageChunk, metadata]`. `metadata` 에는 어느 노드에서 나왔는지(`langgraph_node`), 어떤 태그가 붙었는지(`tags`) 등이 들어 있습니다.

```ts
// 필터링 예시
if (metadata.langgraph_node === "model") { /* 모델 토큰만 */ }
if (metadata.tags?.includes("joke")) { /* 특정 태그가 붙은 호출만 */ }
```

> ⚠️ **함정**: **`streamMode: "messages"` 는 "최종 메시지"가 아니라 "토큰"입니다.** 이름 때문에 `values` 나 `updates` 처럼 완성된 메시지 객체가 올 거라고 오해하기 쉽습니다. 실제로 오는 건 **한 글자 ~ 몇 글자짜리 `AIMessageChunk`** 입니다. 여기서 `token.tool_calls` 를 읽으면 대부분 빈 배열이고, `token.usage_metadata` 도 대부분 없습니다. 완성된 메시지가 필요하면 `updates` 를 쓰거나, 토큰을 직접 `concat` 해서 조립해야 합니다.
>
> 그리고 위 출력의 마지막 줄을 보세요 — **`[tools]` 노드의 ToolMessage 도 함께 흘러나옵니다.** 필터 없이 그대로 화면에 찍으면 도구 결과 원문이 사용자에게 노출됩니다. `metadata.langgraph_node === "model"` 로 걸러야 합니다.

### custom / tools / debug

`custom` 은 9-7 에서 자세히 다룹니다. `tools` 모드는 도구의 생명주기를 이벤트로 줍니다.

| 이벤트 | 발생 시점 | 페이로드 필드 |
|---|---|---|
| `on_tool_start` | 도구 호출 시작 | `name`, `input`, `toolCallId` |
| `on_tool_event` | 도구가 중간 데이터를 `yield` 할 때 | `name`, `data`, `toolCallId` |
| `on_tool_end` | 도구가 결과 반환 | `name`, `output`, `toolCallId` |
| `on_tool_error` | 도구가 예외를 던짐 | `name`, `error`, `toolCallId` |

`on_tool_event` 를 내려면 도구를 **async generator**(`async function*`)로 만들어 `yield` 해야 합니다.

`debug` 는 실행 중 모든 정보를 쏟아냅니다.

```ts
for await (const chunk of await agent.stream(question, { streamMode: "debug" })) {
  console.log(JSON.stringify(chunk).slice(0, 200));
}
```

> 💡 **실무 팁**: `debug` 는 **로컬 디버깅 전용**입니다. 프로덕션에서 사용자에게 흘리면 시스템 프롬프트, 내부 노드 구조, 체크포인트 ID 같은 게 전부 새어 나갑니다. 그리고 양이 압도적으로 많아 네트워크가 죽습니다. 프로덕션 관측은 `debug` 가 아니라 LangSmith 를 쓰세요([Step 19](../step-19-observability-eval/)).

---

## 9-4. 여러 모드 동시 구독

실전 UI 는 보통 두 가지가 동시에 필요합니다 — **토큰**(글자가 흐르는 것)과 **진행 상황**("도구 조회 중..."). `streamMode` 에 **배열**을 주면 됩니다.

```ts
for await (const [mode, chunk] of await agent.stream(question, {
  streamMode: ["updates", "messages", "custom"],
})) {
  switch (mode) {
    case "messages": {
      const [token] = chunk;              // chunk 가 다시 [token, metadata] 튜플
      process.stdout.write(token.text);
      break;
    }
    case "updates":
      console.log(`\n[스텝 완료: ${Object.keys(chunk).join(", ")}]`);
      break;
    case "custom":
      console.log(`\n[진행] ${JSON.stringify(chunk)}`);
      break;
  }
}
```

**출력 예시** (모델 응답이므로 순서와 텍스트는 매번 다릅니다)
```
[스텝 완료: model]
[진행] {"type":"progress","data":"서울 관측소 조회 중..."}
[진행] {"type":"progress","data":"서울 관측 데이터 수신 완료"}
[스텝 완료: tools]
서울은 현재 맑고 기온은 21도입니다.
[스텝 완료: model]
```

> ⚠️ **함정 (튜플 모양이 바뀐다)**: 배열을 주는 **순간 항목의 모양이 달라집니다.**
>
> | streamMode | 항목 모양 |
> |---|---|
> | `"updates"` | `chunk` |
> | `"messages"` | `[token, metadata]` |
> | `["updates", "messages"]` | `["updates", chunk]` 또는 `["messages", [token, metadata]]` |
>
> 단일 모드로 짠 코드를 그대로 두고 배열만 추가하면 `chunk` 자리에 **문자열 `"messages"`** 가 들어옵니다. `chunk.text` 는 `undefined` 가 되고, 화면에 `undefined` 가 줄줄이 찍힙니다 — **에러는 안 납니다.** 그리고 `mode === "messages"` 일 때 chunk 는 **다시 튜플**이라 튜플이 두 겹입니다. 이 이중 구조에서 헷갈리는 사람이 많습니다.

> 💡 **실무 팁**: 실전 조합은 대체로 **`["messages", "custom"]`** 입니다. `messages` 로 글자를 흘리고 `custom` 으로 진행률을 흘립니다. `updates` 까지 넣으면 "도구를 부르기로 결정했다"는 시점도 잡을 수 있지만, 그 정보는 `custom` 으로 직접 내보내는 게 페이로드도 작고 스키마도 내가 통제할 수 있어 더 좋습니다.

---

## 9-5. streamEvents — 세밀한 이벤트와 프로젝션

`stream()` 이 "채널을 고르는" API 라면 `streamEvents()` 는 **실행 중 일어나는 모든 일을 이벤트로 보는** API 입니다. LangChain v1 에서는 `version: "v3"` 를 쓰며, 여기서 **프로젝션(projection)** 이라는 개념이 등장합니다.

```ts
const stream = await agent.streamEvents(question, { version: "v3" });
```

이 `stream` 객체는 두 가지로 쓸 수 있습니다.

### (1) 원본 이벤트 — for await 로 직접

```ts
const stream = await agent.streamEvents(question, { version: "v3" });
for await (const event of stream) {
  console.log(event.method, JSON.stringify(event.params?.namespace));
}
```

이벤트 봉투(envelope)의 구조는 `{ method, params: { namespace, data } }` 입니다. `namespace` 가 어느 그래프(서브에이전트 포함)에서 나온 이벤트인지 알려 줍니다.

### (2) 프로젝션 — 필요한 것만 뽑아 쓰기

원본 이벤트를 직접 파싱하는 건 고통스럽습니다. 그래서 `stream` 객체는 미리 가공된 **프로젝션**을 제공합니다.

| 프로젝션 | 무엇을 주는가 |
|---|---|
| `for await (const e of stream)` | 원본 프로토콜 이벤트 (봉투 포함) |
| `stream.messages` | **LLM 호출 1건 = 메시지 스트림 1개** |
| `stream.values` | 에이전트 상태 스냅샷 |
| `stream.output` | **최종 상태** (Promise) |
| `stream.toolCalls` | 도구 실행 생명주기 |
| `stream.subgraphs` | 중첩 그래프 실행 (서브에이전트) |
| `stream.extensions` | 커스텀 transformer 프로젝션 |

그리고 `stream.messages` 로 얻은 각 `message` 는 다시 하위 프로젝션을 가집니다.

| 메시지 프로젝션 | 무엇을 주는가 |
|---|---|
| `message.text` | 텍스트 델타 |
| `message.reasoning` | 추론(thinking) 델타 |
| `message.toolCalls` | 도구 호출 인자 조각 |
| `message.output` | 완성된 메시지 객체 |
| `message.usage` | 토큰 사용량 |

```ts
const stream = await agent.streamEvents(question, { version: "v3" });

for await (const message of stream.messages) {
  for await (const delta of message.text) {
    process.stdout.write(delta);
  }
  console.log("\n--- 메시지 1건 종료 ---");
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```

--- 메시지 1건 종료 ---
서울은 현재 맑고 기온은 21도입니다.
--- 메시지 1건 종료 ---
```

첫 메시지가 비어 있는 게 정상입니다 — 도구를 부르기로 한 메시지에는 텍스트가 없고 도구 호출만 있습니다.

### 추론 토큰 — thinking 을 따로 뽑기

`reasoning` 프로젝션이 진가를 발휘하는 곳입니다. 확장 사고(extended thinking)를 켠 모델의 "생각"과 "답"을 **분리해서** 흘릴 수 있습니다.

```ts
import { ChatAnthropic } from "@langchain/anthropic";

const agent = createAgent({
  model: new ChatAnthropic({
    model: "claude-sonnet-4-6",
    thinking: { type: "enabled", budget_tokens: 5000 },
  }),
  tools: [getWeather],
});

const stream = await agent.streamEvents(question, { version: "v3" });
for await (const message of stream.messages) {
  for await (const token of message.reasoning) {
    process.stdout.write(`[thinking] ${token}`);
  }
  for await (const token of message.text) {
    process.stdout.write(token);
  }
}
```

이걸 `streamMode: "messages"` 로 하려면 청크의 `contentBlocks` 를 뒤져서 `type === "reasoning"` 인 블록을 직접 골라내야 합니다. 프로젝션이 그 일을 대신해 줍니다.

### 도구 호출 — 두 겹의 시야

```ts
await Promise.all([
  (async () => {
    for await (const message of stream.messages) {
      for await (const chunk of message.toolCalls) {
        console.log("인자 조각", chunk);        // 조각 단위 (부분 JSON)
      }
    }
  })(),
  (async () => {
    for await (const call of stream.toolCalls) {
      console.log(call.name, call.input);      // 완성된 호출 단위
    }
  })(),
]);
```

`message.toolCalls` 는 **조각**을, `stream.toolCalls` 는 **완성된 호출**을 줍니다. 대부분의 경우 후자만 있으면 됩니다.

### 서브에이전트만 골라 보기

```ts
for await (const subagent of stream.subgraphs) {
  if (subagent.name !== "weather_agent") continue;
  // 이 서브에이전트의 스트림만 처리
}
```

[Step 18 — 멀티 에이전트](../step-18-multi-agent/) 에서 다시 씁니다.

### stream.output — 최종 상태

```ts
const stream = await agent.streamEvents(question, { version: "v3" });

for await (const message of stream.messages) {
  for await (const delta of message.text) process.stdout.write(delta);
}

const finalState = await stream.output;   // ← 스트림을 다 소비한 뒤에
console.log(finalState.messages.length);
```

> ⚠️ **함정**: `stream.output` 은 **스트림을 끝까지 소비해야** 해결(resolve)됩니다. 스트림을 구독하지 않고 `await stream.output` 만 하면 무한정 기다릴 수 있습니다. "토큰은 흘리고 마지막에 전체 결과도 저장하고 싶다"는 흔한 요구는, **먼저 다 소비하고 그다음에 `await stream.output`** 순서로 짜야 합니다.

> 💡 **실무 팁 — `stream()` vs `streamEvents()`**: 판단 기준은 단순합니다.
> - **채팅 UI 에 글자 흘리기 + 진행률** → `stream({ streamMode: ["messages", "custom"] })`. 가볍고 충분합니다.
> - **추론과 답을 분리**, **서브에이전트별 필터**, **커스텀 transformer** → `streamEvents({ version: "v3" })`.
>
> `streamEvents` 는 더 많은 걸 볼 수 있는 만큼 이벤트 양도 많습니다. 필요 없으면 `stream()` 으로 충분합니다.

---

## 9-6. 도구 호출 스트리밍 — tool_call_chunks

여기가 스트리밍에서 가장 많이 사고가 나는 지점입니다.

모델이 도구를 부를 때 인자는 JSON 입니다: `{"city": "서울"}`. 그런데 스트리밍에서 이 JSON 은 **완성된 채로 오지 않습니다.** 문자 단위로 쪼개져서 옵니다.

```ts
const modelWithTools = model.bindTools([getWeather]);
const stream = await modelWithTools.stream("서울과 부산 날씨를 각각 알려줘");

for await (const chunk of stream) {
  for (const tc of chunk.tool_call_chunks ?? []) {
    console.log(`index=${tc.index} name=${tc.name ?? "(없음)"} args=${JSON.stringify(tc.args)}`);
  }
}
```

**출력 예시** (쪼개지는 위치는 제공자와 호출마다 다릅니다. **필드 이름과 "쪼개진다"는 사실은 결정적입니다**)
```
index=0 name=get_weather args=""
index=0 name=(없음) args="{\"ci"
index=0 name=(없음) args="ty\": \"서"
index=0 name=(없음) args="울\"}"
index=1 name=get_weather args=""
index=1 name=(없음) args="{\"city\""
index=1 name=(없음) args=": \"부산\"}"
```

`ToolCallChunk` 의 필드는 다음과 같습니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `index` | `number` | **몇 번째 도구 호출인가.** 병렬 호출을 구분하는 열쇠 |
| `name` | `string \| undefined` | 도구 이름. **보통 첫 조각에만 실려 옵니다** |
| `args` | `string` | **부분 JSON 문자열.** 완성된 객체가 아닙니다 |
| `id` | `string \| undefined` | 도구 호출 ID |
| `type` | `string` | `"tool_call_chunk"` |

> ⚠️ **함정 (부분 JSON)**: **`tc.args` 를 `JSON.parse` 하면 터집니다.**
>
> ```ts
> for await (const chunk of stream) {
>   for (const tc of chunk.tool_call_chunks ?? []) {
>     const args = JSON.parse(tc.args);   // ⚠️ SyntaxError: Unexpected end of JSON input
>   }
> }
> ```
>
> `args` 는 `'{"ci'` 같은 **JSON 의 앞부분**입니다. 그 자체로는 유효한 JSON 이 아닙니다. 더 나쁜 경우는 짧은 인자가 우연히 한 청크에 다 들어와서 **로컬에서는 잘 되다가 프로덕션에서 인자가 길어지는 순간 터지는** 것입니다. 완성될 때까지 `index` 별로 이어붙여야 합니다.

### concat 이 이걸 대신 해 준다

```ts
let full: AIMessageChunk | null = null;
for await (const chunk of stream) {
  full = full === null ? chunk : full.concat(chunk);
}

console.log(JSON.stringify(full?.tool_calls, null, 2));
```

**출력 예시** (인자 값은 모델이 정하지만 **구조는 결정적입니다**)
```json
[
  { "name": "get_weather", "args": { "city": "서울" }, "id": "toolu_01abc...", "type": "tool_call" },
  { "name": "get_weather", "args": { "city": "부산" }, "id": "toolu_01def...", "type": "tool_call" }
]
```

`concat` 은 `index` 로 조각을 묶고, args 를 이어붙이고, **JSON 이 완성된 것만** 파싱해서 `tool_calls` 에 올립니다. 미완성이면 `tool_call_chunks` 에만 남습니다. 그래서 **`tool_calls` 는 "완성된 것", `tool_call_chunks` 는 "조각"** 이라고 외우면 됩니다.

### 손으로 누적한다면

원리를 이해하기 위해 직접 짜 보면 이렇습니다 (실무에서는 `concat` 을 쓰세요).

```ts
const acc = new Map<number, { name: string; raw: string }>();

for await (const chunk of stream) {
  for (const tc of chunk.tool_call_chunks ?? []) {
    const index = tc.index ?? 0;
    const prev = acc.get(index) ?? { name: "", raw: "" };

    acc.set(index, {
      name: prev.name !== "" ? prev.name : (tc.name ?? ""),   // ← undefined 로 덮어쓰기 금지
      raw: prev.raw + (tc.args ?? ""),                        // ← index 별로 이어붙이기
    });

    const cur = acc.get(index)!;
    try {
      const args = JSON.parse(cur.raw);
      console.log(`index=${index} 완성!`, cur.name, args);
    } catch {
      // 아직 미완성 — 정상입니다. 다음 조각을 기다립니다.
    }
  }
}
```

세 군데가 함정입니다.

1. **`index` 로 묶어야 한다.** 병렬 호출에서 index 0, 1 의 조각이 번갈아 옵니다. index 를 무시하고 한 문자열에 몰아 붙이면 `{"city": "서울"}{"city": "부산"}` 이 되어 **영원히 파싱되지 않습니다.**
2. **`name` 을 무조건 대입하면 안 된다.** `acc.name = tc.name` 으로 쓰면 두 번째 조각의 `undefined` 가 이름을 지웁니다.
3. **파싱 실패는 에러가 아니다.** "아직 안 왔다"는 뜻입니다. 삼키고 넘어가야 합니다.

> 💡 **실무 팁 — 부분 인자를 UI 에 보여주고 싶다면**: "지금 어떤 인자로 도구를 부르는 중인가"를 실시간으로 보여주고 싶은 요구가 종종 있습니다. `JSON.parse` 는 못 쓰니, **부분 JSON 파서**(`partial-json`, `best-effort-json-parser` 같은 라이브러리)를 쓰거나, 아예 **완성될 때까지 스피너만 보여주는** 편이 안전합니다. 후자를 권합니다 — 반쯤 파싱된 인자를 화면에 띄우면 `{"city": "서` 같은 게 깜빡이며 지나가서 오히려 조잡해 보입니다.

---

## 9-7. 커스텀 데이터 스트리밍

도구가 5초 동안 DB 를 뒤지고 있을 때, 사용자에게는 아무 소식이 없습니다. 토큰도 안 나옵니다(모델이 대기 중이니까). 이 구간을 메우는 게 **`config.writer`** 입니다.

```ts
import { type LangGraphRunnableConfig } from "@langchain/langgraph";

const queryDatabase = tool(
  async (input: { query: string }, config: LangGraphRunnableConfig) => {
    config.writer?.({ type: "progress", data: "0/100 건 조회" });
    // ... 실제 조회 ...
    config.writer?.({ type: "progress", data: "100/100 건 조회 완료" });
    return "조회 결과입니다.";
  },
  {
    name: "query_database",
    description: "데이터베이스를 조회합니다.",
    schema: z.object({ query: z.string() }),
  },
);
```

구독은 `streamMode: "custom"` 입니다.

```ts
for await (const chunk of await agent.stream(question, { streamMode: "custom" })) {
  console.log(chunk);
}
```

**출력**
```
{ type: 'progress', data: '0/100 건 조회' }
{ type: 'progress', data: '100/100 건 조회 완료' }
```

이건 **모델을 거치지 않습니다.** 내가 보낸 객체가 그대로 나옵니다. 그래서 구조가 완전히 결정적이고, 스키마도 내가 정합니다.

> ⚠️ **함정 (writer 가 조용히 사라진다)**: `writer` 는 **도구 함수의 두 번째 인자**로만 들어옵니다.
>
> ```ts
> // ⚠️ config 를 안 받으면 진행률을 내보낼 방법이 없다
> const t = tool(async (input) => { ... }, { ... });
>
> // ✅ 두 번째 인자를 받아야 한다
> const t = tool(async (input, config: LangGraphRunnableConfig) => { ... }, { ... });
> ```
>
> 첫 번째 형태도 **타입 에러가 안 나고 도구도 잘 동작합니다.** 진행률만 없습니다. "왜 custom 스트림에 아무것도 안 나오지?" 로 한참 헤매게 되는 전형적인 케이스입니다.
>
> 그리고 `config.writer?.()` 처럼 **옵셔널 호출**을 쓰세요. `invoke()` 경로나 일부 실행 컨텍스트에서는 `writer` 가 없을 수 있고, 그때 `config.writer(...)` 는 `TypeError` 를 던져 도구 자체를 실패시킵니다.

> 💡 **실무 팁 — writer 는 구독자가 없어도 안전합니다**: `streamMode` 에 `custom` 이 없으면 `writer` 로 보낸 데이터는 그냥 버려집니다. 에러도 아니고 경고도 없습니다. 그러니 **도구에는 진행률을 넉넉히 심어 두고**, 구독 여부는 호출하는 쪽에서 결정하게 하세요. 도구를 재사용할 때 훨씬 유연해집니다.

> 💡 **실무 팁 — 스키마를 미리 정하세요**: `writer` 는 아무 객체나 받습니다. 그래서 팀이 커지면 `{ msg: "..." }`, `{ text: "..." }`, `{ type: "progress", ... }` 가 뒤섞입니다. 프론트엔드는 이걸 전부 분기해야 합니다. **`{ type: string, ...payload }` 같은 태그드 유니온**을 코스 초반에 정하고 zod 로 검증하세요.

---

## 9-8. 서버로 내보내기 — SSE

지금까지는 터미널에 찍었습니다. 이제 브라우저까지 보냅니다. **SSE**(Server-Sent Events)가 표준적인 선택입니다 — 단방향, HTTP 위, 브라우저 기본 지원(`EventSource`).

```ts
import { createServer } from "node:http";

const server = createServer(async (req, res) => {
  if (req.url !== "/chat") { res.writeHead(404).end(); return; }

  // 1) SSE 헤더 3종 세트
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",        // nginx 뒤에 있다면 필수
  });

  // 2) 클라이언트가 끊으면 에이전트도 끊는다
  const controller = new AbortController();
  req.on("close", () => controller.abort());

  const send = (event: string, data: unknown): void => {
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);   // ← 빈 줄로 프레임 종료
  };

  try {
    for await (const [mode, chunk] of await agent.stream(question, {
      streamMode: ["messages", "custom"],
      signal: controller.signal,
    })) {
      if (mode === "messages") {
        const [token, metadata] = chunk;
        if (metadata.langgraph_node === "model" && token.text !== "") {
          send("token", { text: token.text });
        }
      } else if (mode === "custom") {
        send("progress", chunk);
      }
    }
    send("done", { ok: true });
  } catch (err) {
    // 3) 이미 200 을 보냈다 → 500 으로 못 바꾼다. 에러도 "이벤트"로.
    if ((err as Error).name !== "AbortError") {
      send("error", { message: (err as Error).message });
    }
  } finally {
    res.end();
  }
});

server.listen(3000);
```

**클라이언트 쪽 출력 예시** (모델 응답이므로 텍스트는 매번 다릅니다. **프레임 형식은 결정적입니다**)
```
event: progress
data: {"type":"progress","data":"서울 관측소 조회 중..."}

event: token
data: {"text":"서울"}

event: token
data: {"text":"은 현재 맑고"}

event: done
data: {"ok":true}

```

브라우저에서는 이렇게 받습니다.

```ts
const es = new EventSource("/chat");
es.addEventListener("token", (e) => { output.textContent += JSON.parse(e.data).text; });
es.addEventListener("progress", (e) => { status.textContent = JSON.parse(e.data).data; });
es.addEventListener("error", (e) => { status.textContent = "오류가 발생했습니다"; });
es.addEventListener("done", () => es.close());
```

여기서 실전 함정 네 가지가 전부 등장합니다.

> ⚠️ **함정 1 (에러를 되돌릴 수 없다)**: `res.writeHead(200)` 을 부른 순간 **상태 코드는 확정**됩니다. 스트림 중간에 모델이 rate limit 에러를 던져도 500 으로 바꿀 수 없습니다. 이미 보낸 토큰도 회수할 수 없습니다 — 사용자는 반쯤 쓰다 만 문장을 보고 있습니다.
>
> 그래서 스트리밍 API 의 에러 처리는 REST 와 근본적으로 다릅니다: **에러는 상태 코드가 아니라 이벤트로 전달**하고, 클라이언트가 그걸 보고 UI 를 정리(반쯤 쓰인 답에 "⚠️ 응답이 중단되었습니다"를 붙이는 등)해야 합니다. 재시도([Step 11](../step-11-middleware-builtin/) 의 `modelRetryMiddleware`)를 걸어도 **첫 토큰이 나간 뒤에는 소용없습니다** — 재시도하면 처음부터 다시 쓰는 다른 답이 나오니까요. 그래서 프로덕션에서는 "첫 토큰을 내보내기 전"에 최대한 실패하게 만드는 설계(입력 검증, 사전 guardrail)가 중요합니다.

> ⚠️ **함정 2 (break 는 실행을 멈추지 않는다)**: `for await` 를 `break` 로 빠져나오면 **소비만 멈춥니다.**
>
> ```ts
> for await (const [token] of await agent.stream(question, { streamMode: "messages" })) {
>   if (token.text.includes("STOP")) break;   // ⚠️ 에이전트는 계속 돈다
> }
> ```
>
> 에이전트는 백그라운드에서 계속 돌고, 도구를 계속 부르고, **토큰 과금도 계속됩니다.** 체크포인터가 있으면 상태도 계속 저장됩니다. 정말 멈추려면 **`AbortSignal`** 을 써야 합니다.
>
> ```ts
> const controller = new AbortController();
> try {
>   for await (const [token] of await agent.stream(question, {
>     streamMode: "messages",
>     signal: controller.signal,
>   })) {
>     if (token.text.includes("STOP")) controller.abort();   // ✅ 실행까지 멈춘다
>   }
> } catch (err) {
>   if ((err as Error).name !== "AbortError") throw err;     // 취소는 정상 흐름
> }
> ```
>
> `abort()` 는 `AbortError` 를 던지므로 `try/catch` 가 필요합니다. **이건 에러가 아니라 정상적인 취소**이므로 에러 로그로 올리면 안 됩니다. 대시보드가 취소 알람으로 뒤덮입니다.

> ⚠️ **함정 3 (버퍼링)**: SSE 인데 토큰이 한 번에 몰려 나온다면 **중간 어딘가가 버퍼링하고 있는 것**입니다. 범인은 대개 셋 중 하나입니다 — (1) `Cache-Control: no-cache` 누락, (2) nginx 의 `proxy_buffering`(→ `X-Accel-Buffering: no` 헤더), (3) 압축 미들웨어(`compression`)가 응답을 모으는 것. 로컬에서는 프록시가 없어 잘 되다가 배포하면 스트리밍이 사라지는 전형적인 패턴입니다.

> ⚠️ **함정 4 (프레임 종료)**: SSE 프레임은 **빈 줄 하나**(`\n\n`)로 끝납니다. `\n` 하나만 쓰면 브라우저는 "아직 안 끝났다"고 판단하고 영원히 기다립니다. 서버는 정상 동작 중이고 데이터도 나가는데 화면에는 아무것도 안 보입니다.

> 💡 **실무 팁 — SSE vs WebSocket vs 스트리밍 응답**:
> | 방식 | 언제 |
> |---|---|
> | **SSE** | 서버 → 클라이언트 단방향. **에이전트 채팅의 기본값.** 자동 재연결, HTTP 그대로, 디버깅 쉬움 |
> | **WebSocket** | 양방향이 진짜 필요할 때 (음성, 실시간 협업). 인프라 부담 증가 |
> | **HTTP 스트리밍 응답** (`ReadableStream`) | Next.js Route Handler 등 프레임워크가 이미 제공할 때. 사실상 SSE 를 감싼 것 |
>
> Next.js 를 쓴다면 `node:http` 대신 Route Handler 에서 `ReadableStream` 을 반환하는 게 자연스럽습니다. 흘려보내는 내용(`event:`/`data:` 프레임)은 위와 똑같습니다.

---

## 9-9. 종합 — 취소와 에러를 견디는 스트림 소비자

지금까지의 것을 하나로 묶습니다. 실전 스트리밍 소비자가 반드시 갖춰야 할 것들입니다.

```ts
import { MemorySaver } from "@langchain/langgraph";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  checkpointer: new MemorySaver(),
});

const config = { configurable: { thread_id: crypto.randomUUID() } };
const controller = new AbortController();
setTimeout(() => controller.abort(), 1500);   // 사용자가 "그만"을 누른 상황

let received = "";
let full: AIMessageChunk | null = null;

try {
  for await (const [mode, chunk] of await agent.stream(
    { messages: [{ role: "user", content: "서울 날씨를 알려주고 옷차림을 길게 조언해줘" }] },
    { ...config, streamMode: ["messages", "custom"], signal: controller.signal },
  )) {
    if (mode === "custom") {
      console.log(`\n[진행] ${JSON.stringify(chunk)}`);
      continue;
    }
    const [token, metadata] = chunk;
    if (metadata.langgraph_node !== "model") continue;     // 도구 결과 노출 방지

    process.stdout.write(token.text);                       // 표시는 .text
    received += token.text;
    full = full === null ? token : full.concat(token);      // 누적은 concat
  }
  console.log(`\n완료. 토큰: ${JSON.stringify(full?.usage_metadata)}`);
} catch (err) {
  if ((err as Error).name === "AbortError") {
    console.log(`\n[중단됨] 이미 보낸 ${received.length}자는 되돌릴 수 없습니다.`);
  } else {
    throw err;
  }
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[진행] {"type":"progress","data":"서울 관측소 조회 중..."}
[진행] {"type":"progress","data":"서울 관측 데이터 수신 완료"}
서울은 현재 맑고 기온은 21도입니다. 낮에는 가벼운
[중단됨] 이미 보낸 31자는 되돌릴 수 없습니다.
```

체크리스트로 정리하면 이렇습니다.

- [x] 표시는 `chunk.text`, 누적은 `concat`
- [x] `langgraph_node` 로 도구 노드 토큰 필터링
- [x] 여러 모드를 쓰면 `[mode, chunk]` 튜플 구조 인지
- [x] `break` 대신 `AbortSignal` 로 취소
- [x] `AbortError` 는 에러가 아니라 정상 취소로 처리
- [x] 이미 보낸 것은 되돌릴 수 없다는 전제로 UI 설계

---

## 정리

| API | 무엇을 위한 것 | 대표 사용 |
|---|---|---|
| `model.stream()` | 모델 하나의 토큰 | 단순 텍스트 생성 |
| `chunk.text` | 청크의 텍스트 (항상 `string`) | **화면 표시** |
| `full.concat(chunk)` | 청크 병합 | **누적** — usage/tool_calls 까지 살린다 |
| `agent.stream({ streamMode })` | 에이전트 채널 구독 | 채팅 UI |
| `agent.streamEvents({ version: "v3" })` | 세밀한 이벤트 + 프로젝션 | 추론 분리, 서브에이전트 필터 |
| `config.writer?.()` | 도구에서 임의 데이터 방출 | 진행률 |
| `AbortController` + `signal` | 실행까지 취소 | 사용자 "그만" |

**streamMode 요약**

| 모드 | 무엇을 | 페이로드 |
|---|---|---|
| `values` | 전체 상태 | `{ messages: [...전부] }` — 크다, 네트워크로 보내지 말 것 |
| `updates` | 델타 | `{ 노드이름: { messages: [...새것] } }` |
| `messages` | **토큰** | `[messageChunk, metadata]` |
| `custom` | writer 데이터 | 내가 정한 그대로 |
| `tools` | 도구 생명주기 | `on_tool_start` / `on_tool_event` / `on_tool_end` / `on_tool_error` |
| `debug` | 전부 | 로컬 전용 |

**핵심 함정 3가지**

1. **`.content` 를 이어붙이지 마라 — `concat` 을 써라.** 텍스트 전용 모델에선 우연히 잘 동작하다가, 콘텐츠 블록(추론/도구)이 섞이면 `[object Object]` 가 되고, `usage_metadata` 는 애초에 얻을 수 없다.
2. **`tool_call_chunks.args` 는 부분 JSON 이다 — 파싱하면 터진다.** `index` 별로 완성될 때까지 누적해야 한다. `concat` 이 이걸 대신 해 주고, 완성된 것만 `tool_calls` 로 올려 준다.
3. **스트림 중간의 예외는 되돌릴 수 없다.** 이미 200 을 보냈고 토큰도 나갔다. 에러는 상태 코드가 아니라 **이벤트**로 전달하라. 그리고 `break` 는 실행을 멈추지 않는다 — 백그라운드에서 계속 돌며 과금된다. **`AbortSignal`** 을 써라.

**추가로 기억할 것**: `streamMode: "messages"` 는 최종 상태가 아니라 **토큰**이다. 그리고 도구 노드의 `ToolMessage` 도 같이 흘러나오므로 `langgraph_node` 로 걸러야 한다.

---

## 연습문제

1. **TTFT 측정** — `model.stream()` 을 호출해 "첫 청크 도착까지의 시간"(TTFT)과 "전체 완료 시간"을 각각 밀리초로 반환하는 함수를 작성하세요. 루프가 끝난 뒤에 시간을 재면 안 됩니다.
2. **concat 으로 청크 합치기** — 스트림의 청크를 `concat` 으로 합쳐 완성된 `AIMessageChunk` 하나를 반환하세요. 문자열 `+=` 금지. 반환값에서 `usage_metadata` 가 살아 있어야 정답입니다.
3. **updates 로 도구 호출 추적** — `streamMode: "updates"` 를 구독해서 "모델이 도구를 부르기로 결정한 순간"과 "도구 결과가 돌아온 순간"을 각각 한 줄씩 출력하세요. (힌트: chunk 는 노드 이름으로 한 겹 감싸여 있습니다)
4. **노드 필터링** — `streamMode: "messages"` 로 구독하되 `metadata.langgraph_node === "model"` 인 토큰만 출력하세요. 먼저 필터 없이 돌려 어떤 노드 이름이 나오는지 눈으로 확인한 뒤 거세요.
5. **여러 모드 동시 구독** — `streamMode: ["updates", "messages"]` 로 구독해서, messages 는 화면에 흘리고 updates 가 오면 줄바꿈 후 `[스텝 완료: 노드이름]` 을 찍으세요. 튜플 모양이 바뀐다는 점에 주의하세요.
6. **tool_call_chunks 수동 누적** (★어려움) — `concat` 을 쓰지 말고 `tool_call_chunks` 를 직접 `index` 별로 모아 "인자 JSON 이 완성되는 순간"을 감지하세요. `name` 이 `undefined` 로 덮어써지지 않게, 파싱 실패가 에러로 새어 나가지 않게 하는 게 핵심입니다.
7. **커스텀 진행률** — `config.writer?.()` 로 진행률을 3번 내보내는 도구를 만들고, `streamMode: "custom"` 으로 구독해 `1/3`, `2/3`, `3/3` 을 출력하세요. 도구 함수가 `config` 를 안 받으면 어떻게 되는지도 확인해 보세요.
8. **SSE 핸들러** (★어려움) — `node:http` 서버를 띄워 `/chat` 에서 SSE 로 토큰을 흘리세요. 요구사항: SSE 헤더, `event: token`, 예외는 `event: error` 로(500 금지), `req` 의 `close` 에서 `AbortController` 로 취소. 같은 프로세스에서 `fetch` 로 구독해 검증하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 10 — 단기 메모리와 스레드](../step-10-memory/)

이 스텝의 `[9-9]` 에서 `MemorySaver` 와 `thread_id` 를 슬쩍 썼습니다. 그게 무엇이고, **왜 `thread_id` 만 줘서는 메모리가 안 남는지**를 다음 스텝에서 다룹니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(9-1 ~ 9-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 청크가 어떻게 쪼개져 오는지 **눈으로** 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 실제로 모델을 호출하므로 `project/.env` 에 `ANTHROPIC_API_KEY` 가 필요합니다. 실행은 프로젝트 루트에서 `npx tsx docs/reference/langchain/step-09-streaming/practice.ts` 입니다. 출력이 파일로 리다이렉트되면 색이 빠지도록 되어 있으니, **터미널에서 직접 보는 것을 권합니다** — 이 스텝은 "글자가 흘러나오는 것"을 보는 게 목적이라 파일로 받으면 의미가 없습니다.

### practice.ts

본문을 따라가며 손으로 쳐볼 예제를 `[9-1] ~ [9-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 대응하므로, 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[9-1]` 은 같은 프롬프트를 `invoke` 와 `stream` 으로 각각 부르고 TTFT 를 재서 **나란히 출력**합니다. 두 숫자의 격차가 이 스텝의 존재 이유입니다. 모델·네트워크 상태에 따라 값은 크게 달라지니 비율만 보세요.
- `[9-2]` 의 마지막 블록이 중요합니다. `naive += typeof chunk.content === "string" ? ... : JSON.stringify(...)` 로 **일부러 순진한 이어붙이기**를 해 봅니다. 텍스트 전용 응답에서는 결과가 멀쩡해 보이는데, **그게 이 함정이 위험한 이유**입니다. 바로 위 블록의 `concat` 결과(`usage_metadata` 가 살아 있음)와 비교하세요.
- `[9-3]` 은 `updates` / `values` / `messages` / `debug` 를 **각각 별도 함수로** 나눠 놓았습니다. 같은 질문에 같은 에이전트인데 나오는 게 완전히 다릅니다. `section_9_3_values` 는 messages 배열의 **길이만** 찍어서 "매번 전체가 온다"는 걸 보여줍니다.
- `[9-6]` 의 마지막 블록은 `JSON.parse(tc.args)` 를 **일부러 try/catch 로 감싸 실패를 출력**합니다. "파싱 실패 ← 정상입니다" 가 줄줄이 찍히는 게 정답입니다. 조각이 짧아서 우연히 파싱에 성공하는 경우도 있는데, 그 우연이 프로덕션에서 사라지는 것이 함정의 본질입니다.
- `[9-8]` 은 `server.listen(0)` 으로 **빈 포트를 자동 할당**하고, 같은 프로세스에서 `fetch` 로 자기 자신을 구독한 뒤 서버를 닫습니다. 별도 브라우저 없이 SSE 프레임(`event:` / `data:` / 빈 줄)을 raw 로 볼 수 있습니다.
- `[9-9]` 는 1.5초 뒤 `controller.abort()` 를 걸어 **일부러 중단**시킵니다. 모델이 그전에 답을 끝내면 "끝까지 완료"가 나올 수도 있습니다 — 그럴 땐 타임아웃을 500ms 로 줄여서 다시 돌려 보세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래 함수 본문이 `// TODO` 로 비어 있습니다.

- 파일을 그대로 실행하면 문제 1, 2, 6 은 자리 표시자 값(`{ ttftMs: -1, ... }`, `null`, `[]`)을 찍고 나머지는 아무것도 출력하지 않습니다. **정상입니다.**
- 도구는 `search_docs` 하나로 통일해 두었습니다. `await new Promise((r) => setTimeout(r, 200))` 로 200ms 지연을 넣어 뒀는데, 이게 있어야 `[문제 7]` 의 진행률이 눈에 보입니다.
- `[문제 6]` 이 이 파일에서 가장 어렵습니다. 문제 주석에 규칙 세 가지(index 로 묶기 / name 을 undefined 로 덮어쓰지 않기 / 파싱 실패는 정상)를 미리 적어 두었으니, 답을 보기 전에 세 규칙을 **하나씩 일부러 어겨 보고** 무엇이 깨지는지 관찰하세요. 특히 index 를 무시했을 때 "영원히 파싱되지 않는" 현상을 직접 겪어 보는 게 이 문제의 핵심입니다.
- `[문제 4]` 의 지시문 "먼저 필터 없이 돌려서 어떤 노드 이름들이 나오는지 눈으로 확인한 뒤 거세요" 를 건너뛰지 마세요. 노드 이름을 문서에서 외우는 것보다 한 번 찍어 보는 게 확실합니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답과 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 "왜 이렇게 해야 하는가"와 **흔한 오답**이 함께 적혀 있습니다.

- `[정답 2]` 의 해설이 이 스텝 전체의 요약입니다. 문자열 `+=` 가 왜 안 되는지를 세 가지(콘텐츠 블록, `usage_metadata`, `tool_call_chunks`)로 나눠 설명합니다. 실행하면 `usage_metadata: {"input_tokens":...}` 가 찍히는데, **이 한 줄이 `+=` 로는 절대 나올 수 없는 값**입니다.
- `[정답 4]` 는 노드 이름을 `"model"` 로 하드코딩하면서 동시에 "노드 이름은 프레임워크 내부 명칭이라 버전에 따라 바뀔 수 있다" 고 경고합니다. 더 안전한 대안은 `withConfig({ tags: [...] })` 로 **내가 붙인 태그**를 거는 것입니다.
- `[정답 6]` 은 실무에서 직접 쓸 일이 거의 없는 코드입니다(`concat` 이 대신 해 주니까). 그럼에도 손으로 짜 보는 이유는 **"부분 JSON" 을 몸으로 알기 위해서**입니다. `doneIndexes` Set 으로 "이미 완성된 index 는 다시 파싱하지 않기"를 처리하는 부분도 눈여겨보세요 — 없으면 완성 이후에도 매 조각마다 중복 파싱합니다.
- `[정답 7]` 은 문제의 지시대로 도구를 새로 만들면서 `config.writer?.()` 의 **옵셔널 체이닝**을 강조합니다. `config.writer(...)` 로 쓰면 writer 가 없는 실행 경로에서 `TypeError` 가 나 도구 자체가 실패합니다.
- `[정답 8]` 의 주석에 SSE 실전 함정 네 가지(헤더 / 에러를 이벤트로 / close 에서 abort / `\n\n` 프레임)가 번호로 정리되어 있습니다. `catch` 에서 `AbortError` 를 걸러내는 부분에 주목하세요 — 사용자가 탭을 닫은 것을 에러 이벤트로 보내면(그리고 알람을 울리면) 대시보드가 거짓 장애로 뒤덮입니다.

```ts file="./solution.ts"
```
