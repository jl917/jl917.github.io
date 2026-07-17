# Step 10 — 단기 메모리와 스레드

> **학습 목표**
> - LLM 이 **상태가 없다**는 것과, 메모리가 사실은 "배열을 다시 보내는 일"임을 이해한다
> - `checkpointer` 와 `thread_id` 의 역할을 구분하고, **둘 중 하나만 있으면 아무 일도 안 일어난다**는 것을 안다
> - `MemorySaver` 로 멀티턴 대화를 만들고 `thread_id` 로 대화를 격리한다
> - `getState` / `getStateHistory` / `updateState` 로 저장된 상태를 들여다보고 고친다
> - 과거 체크포인트에서 **리플레이·포크**(타임트래블)한다
> - `SqliteSaver` / `PostgresSaver` 로 영속 저장으로 넘어가고, 그때 드러나는 문제를 예측한다
> - 대화가 길어질 때 컨텍스트를 **잘라낼지 요약할지** 판단한다
>
> **선행 스텝**: [Step 09 — 스트리밍](../step-09-streaming/)
> **예상 소요**: 75분

[Step 08](../step-08-create-agent/) 에서 만든 에이전트는 도구를 부르고 답을 냈습니다. 그런데 한 가지 이상한 점이 있었습니다. 두 번째로 `invoke` 를 하면 에이전트가 방금 한 대화를 완전히 잊어버립니다. 이름을 알려줘도, 조건을 말해줘도, 다음 호출에서는 처음 보는 사람 취급입니다.

이건 버그가 아닙니다. **LLM 은 원래 상태가 없습니다.** 챗봇처럼 보이는 모든 것은 누군가가 뒤에서 "지금까지의 대화 전체"를 매번 다시 보내주고 있기 때문에 그렇게 보이는 것뿐입니다. 이 스텝은 그 "누군가"를 직접 만드는 이야기입니다. LangChain 에서는 `checkpointer` 가 그 일을 합니다. 그리고 이 구조를 이해하고 나면 공짜로 딸려오는 것들이 있습니다 — 대화 상태를 들여다보고, 고치고, 과거로 되돌리고, 거기서 다른 미래를 만들어 볼 수 있게 됩니다.

---

## 10-1. LLM은 상태가 없다 — 메모리는 네가 만드는 것

모델 API 는 함수입니다. 메시지 배열을 넣으면 메시지 하나가 나옵니다. 그게 전부입니다. 서버 어딘가에 "당신과의 대화"가 저장되어 있지 않습니다.

```ts
import { createAgent } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
});

const first = await agent.invoke({
  messages: [{ role: "user", content: "안녕하세요. 제 이름은 김민수입니다." }],
});

const second = await agent.invoke({
  messages: [{ role: "user", content: "제 이름이 뭐라고 했죠?" }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 안녕하세요, 김민수님! 만나서 반갑습니다. 무엇을 도와드릴까요?
AI     │ 죄송하지만 이전에 이름을 알려주신 적이 없습니다. 성함을 말씀해 주시겠어요?
```

두 번째 호출은 첫 번째를 모릅니다. 왜 그런지는 반환값의 모양을 보면 명확합니다.

```ts
console.log(first.messages.length);   // 2  — user, ai
console.log(second.messages.length);  // 2  — user, ai  (4가 아닙니다)
```

이 숫자가 이 스텝 내내 가장 믿을 만한 진단 도구입니다. `second.messages` 가 2개라는 건 **모델이 본 것이 딱 그 2개**라는 뜻입니다. 첫 대화는 애초에 전달되지 않았습니다.

> ⚠️ **함정**: 응답 텍스트만 보고 메모리 동작을 판단하지 마세요. 모델은 눈치가 좋습니다. 히스토리가 없어도 "말씀해 주신 대로…" 처럼 있는 척 둘러대거나, 반대로 히스토리가 있는데도 "이전에 말씀 안 하셨는데요" 라고 헛발질할 수 있습니다. **`result.messages.length`** 를 보세요. 이건 모델이 아니라 프레임워크가 만든 숫자라서 거짓말을 하지 않습니다.

그럼 메모리는 뭘까요. 별거 아닙니다. **직전 결과를 다음 입력에 이어 붙이면** 됩니다.

```ts
const history = [...first.messages];   // user, ai

const manual = await agent.invoke({
  messages: [...history, { role: "user", content: "제 이름이 뭐라고 했죠?" }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 김민수님이라고 하셨습니다.
```

기억했습니다. 우리가 한 일은 배열 하나를 이어 붙인 것뿐입니다. **ChatGPT 도 Claude 앱도 내부적으로는 이걸 하고 있습니다.** 마법은 없습니다.

그렇다면 왜 `checkpointer` 같은 게 필요할까요? 위 방식을 실제 서비스에 쓰려면 이런 걸 직접 해야 하기 때문입니다.

| 직접 해야 하는 일 | 왜 귀찮은가 |
|---|---|
| 사용자별로 배열 보관 | 메모리에 두면 서버 재시작 시 소실, 스케일아웃하면 인스턴스마다 딴소리 |
| 어디에 저장할지 | JSON 직렬화? AIMessage 의 tool_calls 는? 커스텀 상태 필드는? |
| 동시 요청 처리 | 같은 사용자가 두 탭에서 보내면 배열이 꼬임 |
| 도구 실행 중 실패 | 중간까지 진행된 걸 어떻게 복구? |
| 대화가 길어지면 | 자르는 로직을 어디에 끼워 넣나 |

`checkpointer` 는 이 전부를 대신해 주는 물건입니다.

---

## 10-2. checkpointer와 thread_id — 대화가 저장되는 원리

두 가지가 짝을 이룹니다.

- **`checkpointer`** — *어디에* 저장할지. 에이전트를 만들 때 줍니다.
- **`thread_id`** — *어느 대화로* 저장할지. 호출할 때마다 줍니다.

```ts
import { createAgent } from "langchain";
import { MemorySaver } from "@langchain/langgraph";

const checkpointer = new MemorySaver();

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  checkpointer,            // ← 어디에
});

await agent.invoke(
  { messages: [{ role: "user", content: "제 이름은 김민수입니다." }] },
  { configurable: { thread_id: "ok-thread" } },   // ← 어느 대화로
);

const remembered = await agent.invoke(
  { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
  { configurable: { thread_id: "ok-thread" } },   // ← 같은 대화
);

console.log(remembered.messages.length);  // 4  — user, ai, user, ai
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 김민수님이십니다.
```

`messages.length` 가 **4** 입니다. 우리는 메시지를 하나만 보냈는데 4개가 돌아왔습니다. `invoke` 가 실행되기 전에 체크포인터가 `ok-thread` 의 저장된 상태를 불러와서, 우리가 준 메시지를 그 **뒤에 붙였기** 때문입니다.

동작 순서는 이렇습니다.

```
invoke(입력, { configurable: { thread_id: "ok-thread" } })
   │
   ├─ 1. checkpointer 에서 thread_id="ok-thread" 의 최신 체크포인트를 읽는다
   │       → { messages: [user, ai] }
   ├─ 2. 입력의 messages 를 리듀서로 합친다 (append)
   │       → { messages: [user, ai, user] }
   ├─ 3. 그래프를 실행한다 (모델 호출 → 필요하면 도구 → …)
   │       → { messages: [user, ai, user, ai] }
   └─ 4. 매 super-step 마다 checkpointer 에 새 체크포인트를 쓴다
```

### thread_id 는 아무 문자열이나 됩니다

```ts
{ configurable: { thread_id: "1" } }
{ configurable: { thread_id: crypto.randomUUID() } }
{ configurable: { thread_id: "user:8821:chat:2026-07-17" } }
```

실무에서는 세 번째 같은 **구조화된 키**나 UUID 를 씁니다. 사람이 읽을 수 있는 키는 디버깅에 유리하고, UUID 는 충돌이 없습니다.

> 💡 **실무 팁**: `PostgresSaver` 를 쓸 계획이라면 **`thread_id` 를 255자 아래로** 유지하세요. 공식 문서가 명시하는 제약입니다 — 해당 컬럼 길이 제한 때문입니다. 사용자 이메일이나 긴 세션 토큰을 그대로 `thread_id` 로 쓰다가 프로덕션에서 터지는 경우가 있습니다. 로컬 `MemorySaver` 로 테스트하면 길이 제한이 없어서 안 드러납니다.

### 이 스텝의 대표 함정

이제 이 스텝에서 가장 중요한 함정입니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  // checkpointer 를 깜빡했습니다
});

const cfg = { configurable: { thread_id: "trap-thread" } };

await agent.invoke({ messages: [{ role: "user", content: "제 이름은 김민수입니다." }] }, cfg);
const forgot = await agent.invoke({ messages: [{ role: "user", content: "제 이름이 뭐죠?" }] }, cfg);

console.log(forgot.messages.length);  // 2  — 4가 아닙니다
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 아직 이름을 알려주지 않으셨어요. 어떻게 불러드리면 될까요?
```

> ⚠️ **함정 (이 스텝의 핵심)**: **`checkpointer` 없이 `thread_id` 만 주면 아무것도 저장되지 않습니다. 그리고 에러도, 경고도 나지 않습니다.** `thread_id` 는 "저장소에서 이 키로 찾아라" 라는 지시일 뿐인데 저장소 자체가 없으니 지시가 갈 곳이 없습니다. LangGraph 는 이걸 실수로 보지 않습니다 — `thread_id` 는 `configurable` 에 담긴 임의의 설정값이고, 체크포인터가 없으면 그냥 아무도 안 읽습니다.
>
> 이게 왜 특히 위험하냐면, **코드가 그럴싸하게 돌아가기 때문**입니다. `thread_id` 를 넣었으니 개발자는 메모리를 켰다고 믿습니다. 모델은 눈치껏 대화를 이어가는 척합니다. 그러다 "가끔 대화를 까먹는 것 같다" 는 제보를 받고 몇 시간을 헤맵니다.
>
> **진단법**: `result.messages.length` 를 찍어 보세요. 2턴째인데 2가 나오면 저장이 안 되고 있는 겁니다. 또는 `await agent.getState(config)` 를 호출해 보세요. 체크포인터가 없으면 빈 스냅샷이 옵니다.

---

## 10-3. MemorySaver로 멀티턴 대화 만들기

이제 진짜 대화를 만들어 봅니다. 도구도 하나 붙입니다 — 도구 호출과 결과도 체크포인트에 남는지 보기 위해서입니다.

```ts
import { createAgent, tool } from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import * as z from "zod";

const getWeather = tool(
  async ({ city }) => `${city}의 날씨: 맑음, 24도`,
  {
    name: "get_weather",
    description: "도시의 현재 날씨를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시 이름") }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "당신은 간결하게 답하는 여행 도우미입니다.",
  checkpointer: new MemorySaver(),
});

const config = { configurable: { thread_id: "trip-2026-07" } };

const turns = [
  "저는 부산에 살고 있어요.",
  "제가 사는 도시 날씨 알려주세요.",   // ← "부산"이라고 안 말했습니다
  "그럼 반팔 입어도 될까요?",
  "제가 어디 산다고 했죠?",
];

for (const [i, text] of turns.entries()) {
  const result = await agent.invoke({ messages: [{ role: "user", content: text }] }, config);
  console.log(`--- 턴 ${i + 1} (누적 messages: ${result.messages.length}) ---`);
  printMessages(result.messages.at(-1)!);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
--- 턴 1 (누적 messages: 2) ---
AI     │ 부산에 사시는군요! 무엇을 도와드릴까요?

--- 턴 2 (누적 messages: 6) ---
AI     │ 부산은 지금 맑고 24도입니다.

--- 턴 3 (누적 messages: 8) ---
AI     │ 24도면 반팔로 충분합니다. 저녁엔 바닷바람이 있으니 얇은 겉옷 하나 챙기세요.

--- 턴 4 (누적 messages: 10) ---
AI     │ 부산에 사신다고 하셨습니다.
```

세 가지를 보세요.

**첫째, 턴 2 에서 "부산"이라는 단어를 쓰지 않았는데 부산 날씨가 나왔습니다.** 턴 1 의 발화가 히스토리에 있으니 모델이 `get_weather({ city: "부산" })` 를 스스로 채운 것입니다. 이게 메모리가 있는 에이전트의 실질적 가치입니다 — 사용자가 매번 맥락을 반복하지 않아도 됩니다.

**둘째, 턴 2 에서 messages 가 2 → 6 으로 뛰었습니다.** 4개가 늘었죠. `user` + `ai(tool_calls)` + `tool` + `ai` 입니다. 도구를 부르는 턴은 메시지를 2개가 아니라 4개(이상) 만듭니다. **도구 호출과 그 결과도 체크포인트에 그대로 저장됩니다.**

**셋째, 턴 3 의 "그럼 반팔 입어도 될까요?" 는 그 자체로는 아무 의미가 없는 문장입니다.** 어디의, 몇 도인지가 없습니다. 히스토리의 `ToolMessage`("부산의 날씨: 맑음, 24도")를 읽고 답한 것입니다.

저장된 전체 대화는 이렇게 꺼냅니다.

```ts
const finalState = await agent.getState(config);
printMessages(finalState.values.messages);
```

> 💡 **실무 팁**: 도구 결과(`ToolMessage`)가 히스토리에 남는 건 양날의 검입니다. 좋은 점은 같은 걸 두 번 조회하지 않는다는 것. 나쁜 점은 **날씨는 변하는데 모델은 30분 전 `ToolMessage` 를 현재 사실로 믿는다**는 것입니다. 시간에 민감한 도구라면 결과 문자열에 조회 시각을 같이 담으세요 — `"부산의 날씨(2026-07-17 14:03 기준): 맑음, 24도"`. 그러면 모델이 최소한 "아까 조회한 바로는" 이라고 말할 근거가 생깁니다.

> 💡 **실무 팁**: 컨텍스트 윈도우를 터뜨리는 범인은 대개 사용자 발화가 아니라 **`ToolMessage`** 입니다. 사용자는 한 줄 쓰지만 검색 도구는 5천 토큰짜리 문서를 돌려줍니다. 10-8 의 컨텍스트 관리를 고민할 때 제일 먼저 볼 곳이 여기입니다.

---

## 10-4. 스레드 격리 — thread_id를 바꾸면 남남

`checkpointer` 하나가 스레드 여러 개를 담습니다. 아파트 건물(`checkpointer`)과 호수(`thread_id`) 라고 생각하면 됩니다. 같은 건물에 살아도 옆집 대화는 안 들립니다.

```ts
const checkpointer = new MemorySaver();
const agent = createAgent({ model: "anthropic:claude-sonnet-4-6", tools: [], checkpointer });

const alice = { configurable: { thread_id: "user-alice" } };
const bob   = { configurable: { thread_id: "user-bob" } };

await agent.invoke({ messages: [{ role: "user", content: "제 이름은 앨리스입니다." }] }, alice);
await agent.invoke({ messages: [{ role: "user", content: "제 이름은 밥입니다." }] }, bob);

const askAlice = await agent.invoke({ messages: [{ role: "user", content: "제 이름이 뭐죠?" }] }, alice);
const askBob   = await agent.invoke({ messages: [{ role: "user", content: "제 이름이 뭐죠?" }] }, bob);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[user-alice]
AI     │ 앨리스입니다.

[user-bob]
AI     │ 밥입니다.
```

에이전트 인스턴스는 **하나**입니다. 체크포인터도 하나입니다. 그런데 두 대화가 섞이지 않았습니다. `thread_id` 가 다르기 때문입니다.

**중요한 결론**: 사용자마다 에이전트를 새로 만들 필요가 없습니다. 에이전트는 앱 시작 시 한 번 만들고, 요청마다 `thread_id` 만 바꾸면 됩니다.

### 없는 thread_id 는 에러가 아닙니다

```ts
const fresh = await agent.getState({ configurable: { thread_id: "never-used" } });
```

**출력**
```
values        {}
next          []
createdAt     undefined
parentConfig  undefined
```

에러가 아니라 **빈 스냅샷**이 옵니다. LangGraph 는 "존재하지 않는 스레드"와 "아직 아무 말도 안 한 스레드"를 구분하지 않습니다. 처음 오는 `thread_id` 는 그 순간 새 스레드가 됩니다.

> ⚠️ **함정 (스레드 오염 — 테스트를 조용히 망친다)**: 이 성질의 반대편이 위험합니다. **`thread_id` 를 재사용하면 이전 대화가 딸려옵니다.**
>
> 전형적인 사고는 이겁니다. 테스트 코드에 `thread_id: "test"` 를 하드코딩합니다. 첫 실행은 통과합니다. 두 번째 실행은 첫 실행의 대화를 물려받은 채 시작합니다. 그래서:
> - 테스트가 처음엔 통과하고 재실행하면 실패하거나, 반대로 **재실행해야만 통과**합니다
> - 로컬에선 되는데 CI 에선 다르게 동작합니다
> - 테스트 순서를 바꾸면 결과가 바뀝니다
>
> `MemorySaver` 를 쓰면 프로세스가 죽을 때 다 지워져서 이 문제가 **가려집니다.** 그러다 `SqliteSaver` 로 바꾸는 순간 드러납니다. "영속 체크포인터로 바꿨더니 테스트가 깨졌다"의 정체가 대부분 이겁니다. 체크포인터가 깨뜨린 게 아니라, 원래 깨져 있던 게 보이게 된 겁니다.
>
> **방어**: 테스트는 케이스마다 `crypto.randomUUID()` 로 새 `thread_id` 를 쓰거나, `checkpointer` 자체를 케이스마다 새로 만드세요. 프로덕션에서는 세션 생성 시점에 UUID 를 발급해 세션에 저장하고, 세션이 끝날 때까지만 재사용합니다.

> ⚠️ **함정 (보안)**: `thread_id` 는 **인증이 아닙니다.** 클라이언트가 보낸 `thread_id` 를 검증 없이 그대로 쓰면, 남의 `thread_id` 를 넣은 요청이 남의 대화 전체를 읽어갑니다. `thread_id` 는 반드시 **서버에서 인증된 사용자 정보로 유도**하세요 — 예: `` `${session.userId}:${chatId}` `` 를 만들고 `chatId` 가 그 사용자 소유인지 확인. UUID 라서 못 맞힐 거라고 믿는 건 방어가 아닙니다.

---

## 10-5. 상태 조회/조작 — getState, getStateHistory, updateState

체크포인터를 붙이면 저장 말고도 세 가지가 딸려옵니다. 상태를 **보고**, **이력을 훑고**, **고칠** 수 있습니다.

### getState — 지금 상태의 스냅샷

```ts
const snapshot = await agent.getState({ configurable: { thread_id: "inspect-1" } });
```

`StateSnapshot` 의 필드는 결정적입니다. 정확히 이 8개입니다.

| 필드 | 타입/의미 |
|---|---|
| `values` | 이 체크포인트 시점의 **상태 채널 값**. `values.messages` 가 대화입니다 |
| `next` | 다음에 실행될 노드 이름의 배열. **`[]` 면 실행이 끝난 것** |
| `config` | `thread_id`, `checkpoint_ns`, `checkpoint_id` 를 담은 config. **이게 타임트래블의 열쇠입니다** |
| `metadata` | `source`(`"input"` / `"loop"` / `"update"`), `writes`(노드가 쓴 값), `step`(super-step 카운터) |
| `createdAt` | ISO 8601 타임스탬프 문자열 |
| `parentConfig` | 직전 체크포인트의 config. 최초 체크포인트면 없음 |
| `tasks` | `PregelTask` 배열. 각각 `id`, `name`, `error`, `interrupts`, (서브그래프면) `state` |

```ts
printKV({
  "values.messages 개수": snapshot.values.messages.length,
  next: JSON.stringify(snapshot.next),
  "config.checkpoint_id": snapshot.config.configurable?.checkpoint_id,
  "metadata.source": snapshot.metadata?.source,
  "metadata.step": snapshot.metadata?.step,
  createdAt: snapshot.createdAt,
});
```

**출력 예시** (checkpoint_id 와 시각은 실행마다 다릅니다)
```
values.messages 개수  2
next                  []
config.checkpoint_id  1f0a2c48-9e31-6d02-8003-b7c1e2f5a904
metadata.source       loop
metadata.step         1
createdAt             2026-07-17T05:12:44.108Z
```

`next` 가 `[]` 인 게 "실행이 완료된 상태" 라는 뜻입니다. `next` 에 노드 이름이 들어 있으면 **아직 안 끝난 것** — 인터럽트로 멈췄거나([Step 13](../step-13-hitl/)), 과거 시점을 보고 있는 것입니다.

### getStateHistory — 이력 훑기

```ts
// 배열이 아니라 async iterable 입니다. for await 로 돕니다.
const history = [];
for await (const state of agent.getStateHistory(config)) {
  history.push(state);
}
```

**출력 예시** (checkpoint_id 는 실행마다 다릅니다)
```
체크포인트 6개 (index 0 이 가장 최신)

[0] step=  1 source=loop   next=[]           messages=4 checkpoint_id=1f0a2c48…
[1] step=  0 source=loop   next=["model"]    messages=3 checkpoint_id=1f0a2c47…
[2] step= -1 source=input  next=["__start__"] messages=3 checkpoint_id=1f0a2c46…
[3] step=  1 source=loop   next=[]           messages=2 checkpoint_id=1f0a2c31…
[4] step=  0 source=loop   next=["model"]    messages=1 checkpoint_id=1f0a2c30…
[5] step= -1 source=input  next=["__start__"] messages=1 checkpoint_id=1f0a2c2f…
```

읽는 법:
- **`index 0` 이 가장 최신입니다.** 문서 표현 그대로 "most recent checkpoint being the first in the list". 시간 **역순**이라 `for await` 로 돌면 과거로 거슬러 갑니다.
- `step = -1` 은 `invoke` 로 입력이 들어온 순간, `0` 부터가 그래프 실행입니다.
- `source` 는 `"input"`(외부 입력) / `"loop"`(그래프 실행) / `"update"`(`updateState` 로 사람이 개입) 세 종류입니다.

> ⚠️ **함정**: **체크포인트는 "턴당 1개"가 아니라 "super-step 당 1개"** 입니다. 위에서 2턴 대화에 체크포인트가 6개 생겼습니다. 도구를 부르면 더 늘어납니다. 그래서 `history[1]` 을 "직전 턴" 이라고 집으면 틀립니다 — 모델이 도구를 부르느냐 마느냐에 따라 index 가 밀립니다. 과거 시점을 찾을 때는 **index 가 아니라 내용 기준**(`next.length === 0` + `messages` 개수, 또는 `metadata.step`)으로 찾으세요.

### updateState — 상태를 직접 고치기

여기가 이 스텝에서 가장 많이 틀리는 곳입니다.

```ts
const before = await agent.getState(config);
console.log(before.values.messages.length);   // 2

// "이 메시지 하나로 바꿔치기" 를 의도했습니다
await agent.updateState(config, {
  messages: [{ role: "user", content: "(수동으로 끼워 넣은 메시지)" }],
});

const after = await agent.getState(config);
console.log(after.values.messages.length);    // 3  ← 1이 아닙니다
```

> ⚠️ **함정 (리듀서)**: **`updateState` 는 대입이 아닙니다.** 공식 문서 표현으로 "values are passed through reducer functions when defined, so channels with reducers *accumulate* values rather than overwrite them" — 리듀서가 있는 채널은 **누적**됩니다. `messages` 채널의 리듀서는 "append + id 기준 병합" 이므로, 위 코드는 메시지를 **교체한 게 아니라 하나 추가**한 것입니다.
>
> 이해하는 법: `updateState(config, X)` 는 **"노드 하나가 X 를 반환한 것처럼 처리하라"** 는 뜻입니다. 노드가 `{ messages: [...] }` 를 반환하면 append 되죠? 그래서 여기서도 append 됩니다. 완전히 같은 규칙입니다.
>
> 이게 조용한 이유: 3이든 1이든 코드는 잘 돌아갑니다. "히스토리를 정리했다" 고 믿고 넘어갔는데 실은 쓰레기가 하나 더 쌓인 겁니다. 반복하면 컨텍스트가 눈덩이처럼 붑니다.

**진짜로 덮어쓰려면** 리듀서에게 "다 지워라" 를 명령해야 합니다.

```ts
import { RemoveMessage } from "@langchain/core/messages";
import { REMOVE_ALL_MESSAGES } from "@langchain/langgraph";

await agent.updateState(config, {
  messages: [
    new RemoveMessage({ id: REMOVE_ALL_MESSAGES }),      // ← 먼저 전부 비우고
    { role: "user", content: "이것만 남는다" },            // ← 남길 것을 뒤에
  ],
});

const replaced = await agent.getState(config);
console.log(replaced.values.messages.length);   // 1
```

**순서가 중요합니다.** `RemoveMessage` 를 뒤에 두면 방금 넣은 것까지 지워집니다.

메시지 하나만 콕 집어 지우려면 그 메시지의 `id` 를 씁니다.

```ts
const lastId = after.values.messages.at(-1)!.id!;
await agent.updateState(config, {
  messages: [new RemoveMessage({ id: lastId })],
});
```

> 💡 **실무 팁**: `updateState` 는 원본 체크포인트를 **수정하지 않습니다.** 언제나 **새 체크포인트를 하나 더 만듭니다**(그래서 `metadata.source` 가 `"update"` 입니다). 즉 이력은 append-only 입니다. 뭘 해도 과거는 남아 있으므로 되돌릴 수 있습니다 — 다음 절이 그 이야기입니다.

---

## 10-6. 타임트래블 — 과거 체크포인트에서 다시 실행하기

이력이 남아 있고 각 체크포인트에 `checkpoint_id` 가 있다는 것은, **과거의 어느 시점으로 돌아가서 다시 실행할 수 있다**는 뜻입니다. 두 가지를 할 수 있습니다.

- **리플레이(replay)** — 과거 시점의 상태 그대로 다시 실행
- **포크(fork)** — 과거 상태를 **고쳐서** 다른 가지를 만들기

원리는 하나뿐입니다. **config 에 `checkpoint_id` 가 있으면 그 시점을, 없으면 최신을 가리킵니다.**

```ts
// 최신을 가리킴
{ configurable: { thread_id: "1" } }

// 특정 체크포인트를 가리킴
{ configurable: { thread_id: "1", checkpoint_id: "1ef663ba-28fe-6528-8002-5a559208592c" } }
```

그리고 `getStateHistory` 가 돌려주는 스냅샷의 `.config` 에는 **이미 `checkpoint_id` 가 박혀 있습니다.** 그러니 직접 만들 필요 없이 그걸 그대로 쓰면 됩니다.

3턴 대화를 준비합니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  systemPrompt: "당신은 한 문장으로만 답합니다.",
  checkpointer: new MemorySaver(),
});
const config = { configurable: { thread_id: "timetravel-1" } };

await agent.invoke({ messages: [{ role: "user", content: "제가 좋아하는 색은 파란색입니다." }] }, config);
await agent.invoke({ messages: [{ role: "user", content: "제가 좋아하는 동물은 고양이입니다." }] }, config);
await agent.invoke({ messages: [{ role: "user", content: "제 취향을 요약해 주세요." }] }, config);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 파란색을 좋아하시고 고양이를 좋아하시는군요.
```

### 과거 시점 찾기

```ts
const history = [];
for await (const state of agent.getStateHistory(config)) {
  history.push(state);
}

// 2턴이 끝난 시점: 실행이 멈춰 있고(next=[]) messages 가 4개
const afterTurn2 = history.find(
  (s) => s.next.length === 0 && s.values.messages?.length === 4,
);
```

index 가 아니라 **내용**으로 찾은 것에 주목하세요. 10-5 의 함정 그대로입니다.

### 리플레이

```ts
const replay = await agent.invoke(
  { messages: [{ role: "user", content: "제가 좋아하는 동물이 뭐죠?" }] },
  afterTurn2.config,        // ← checkpoint_id 가 박힌 config
);
console.log(replay.messages.length);  // 6  — 8이 아닙니다
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 고양이를 좋아하신다고 하셨습니다.
```

`messages` 가 **6** 입니다. 원래 3턴 대화는 6개였고 여기에 질문+답이 붙으면 8이어야 할 것 같지만, 우리는 **2턴이 끝난 시점(4개)** 에서 출발했으므로 4 + 2 = 6 입니다. 3턴째 대화("취향 요약")는 이 실행에서 존재하지 않았던 셈입니다.

### 포크

`updateState` 로 과거 체크포인트 위에 새 체크포인트를 얹습니다.

```ts
const forkConfig = await agent.updateState(afterTurn2.config, {
  messages: [{ role: "user", content: "정정합니다. 제가 좋아하는 동물은 강아지입니다." }],
});

const forked = await agent.invoke(
  { messages: [{ role: "user", content: "제가 좋아하는 동물이 뭐죠?" }] },
  forkConfig,               // ← updateState 가 돌려준 config = 새 가지
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 강아지를 좋아하신다고 하셨습니다.
```

`updateState` 의 **반환값**이 새 가지를 가리키는 config 입니다. 이걸 안 받고 원래 `config` 로 invoke 하면 포크가 아니라 최신 상태에서 이어집니다.

원본은 무사합니다.

```ts
const mainline = await agent.getState(config);
console.log(mainline.values.messages.length);   // 6 — 원래 3턴 대화 그대로
```

이력이 이렇게 갈라집니다.

```
[턴1: user, ai]
      │
[턴2: user, ai]  ← afterTurn2
      ├──────────────────────── 원본: [턴3: 취향 요약]        ← thread_id 의 "최신"
      ├──────────────────────── 리플레이: [동물이 뭐죠? → 고양이]
      └──────────────────────── 포크: [정정: 강아지] → [동물이 뭐죠? → 강아지]
```

### 언제 쓰나

| 용도 | 방법 |
|---|---|
| 에이전트가 이상한 도구를 불렀다 — 그 직전으로 돌아가 프롬프트를 바꿔 재시도 | 포크 |
| 같은 지점에서 모델 A vs 모델 B 를 비교 | 리플레이(체크포인터를 공유하고 에이전트만 바꿔서) |
| 사용자가 "이전 답변 다시 생성" 을 눌렀다 | 마지막 AI 메시지 이전 체크포인트에서 리플레이 |
| 사용자가 메시지를 수정했다(ChatGPT 의 연필 아이콘) | 그 지점에서 포크 |
| 버그 재현 — 프로덕션 스레드를 그대로 가져와 그 지점부터 실행 | 리플레이(영속 체크포인터 필요) |

> 💡 **실무 팁**: 마지막 항목이 영속 체크포인터를 쓰는 가장 저평가된 이유입니다. 프로덕션에서 "에이전트가 이상하게 굴었다" 는 제보를 받으면, 로그를 읽으며 추측하는 대신 **그 `thread_id` 의 체크포인트를 로컬로 가져와 문제 지점 직전에서 다시 돌려볼 수 있습니다.** 프롬프트를 바꿔가며 몇 번이고요. 이건 로그로는 절대 안 되는 일입니다.

> ⚠️ **함정**: 포크해서 이것저것 시도하다 보면 **체크포인트가 계속 쌓입니다.** `MemorySaver` 면 메모리, `PostgresSaver` 면 디스크입니다. 프로덕션에서 스레드 수 × 턴 수 × super-step 수만큼 행이 생기고 아무도 안 지웁니다. 체크포인터에는 자동 만료(TTL)가 없습니다 — **보존 정책은 직접 만들어야 합니다.** 오래된 `thread_id` 를 주기적으로 삭제하는 배치를 처음부터 계획에 넣으세요.

---

## 10-7. 영속 체크포인터 — SqliteSaver / PostgresSaver

`MemorySaver` 는 이름 그대로 **메모리**에 담습니다. 자바스크립트 객체 하나입니다.

```ts
const saverA = new MemorySaver();
const agentA = createAgent({ model: MODEL, tools: [], checkpointer: saverA });
const cfg = { configurable: { thread_id: "restart-demo" } };

await agentA.invoke({ messages: [{ role: "user", content: "제 이름은 김민수입니다." }] }, cfg);
console.log((await agentA.getState(cfg)).values.messages.length);   // 2

// 프로세스 재시작을 흉내: 새 MemorySaver = 새 (빈) 저장소
const saverB = new MemorySaver();
const agentB = createAgent({ model: MODEL, tools: [], checkpointer: saverB });
console.log((await agentB.getState(cfg)).values.messages?.length ?? 0);   // 0
```

**출력**
```
재시작 전 messages  2
재시작 후 messages  0
```

`thread_id` 는 같습니다. 그런데 0 입니다. 저장소가 다른 객체니까요.

> ⚠️ **함정**: **`MemorySaver` 는 프로세스가 죽으면 전부 사라집니다. 데모용입니다.** 그리고 이 사실은 개발 중엔 잘 안 드러납니다 — 로컬에서 `tsx` 로 한 번 돌리는 동안은 프로세스가 안 죽으니 완벽하게 동작하거든요.
>
> 프로덕션에서 터지는 방식이 고약합니다. 배포할 때마다 **모든 사용자의 대화가 초기화**됩니다. 컨테이너가 OOM 으로 재시작하면 그때도 사라집니다. 게다가 인스턴스를 2대 이상 띄우면 **로드밸런서가 어느 인스턴스로 보내느냐에 따라 대화가 있기도 하고 없기도 합니다.** 사용자는 "가끔 봇이 기억을 잃는다" 고 제보하고, 개발자는 재현이 안 돼서 헤맵니다. 재현이 안 되는 이유는 그게 라운드로빈 확률 문제이기 때문입니다.
>
> 규칙: **`MemorySaver` 는 로컬 실습, 단위 테스트, 노트북 데모에만.** 사용자가 두 번 이상 방문하는 물건이면 영속 체크포인터입니다.

### 패키지

체크포인터마다 **별도 npm 패키지**입니다. `@langchain/langgraph` 는 인터페이스와 `MemorySaver` 만 갖고 있습니다.

| 패키지 | 클래스 |
|---|---|
| `@langchain/langgraph` | `MemorySaver` (기본 포함) |
| `@langchain/langgraph-checkpoint-sqlite` | `SqliteSaver` |
| `@langchain/langgraph-checkpoint-postgres` | `PostgresSaver` |
| `@langchain/langgraph-checkpoint-mongodb` | `MongoDBSaver` |
| `@langchain/langgraph-checkpoint-redis` | `RedisSaver` |

### SQLite

```bash
npm install @langchain/langgraph-checkpoint-sqlite
```

```ts
import { SqliteSaver } from "@langchain/langgraph-checkpoint-sqlite";

const checkpointer = SqliteSaver.fromConnString("./checkpoints.sqlite");

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  checkpointer,          // ← MemorySaver 자리에 그냥 끼우면 됩니다
});
```

에이전트 코드는 **한 글자도 안 바뀝니다.** `createAgent` 는 `checkpointer` 가 무엇인지 모릅니다 — 인터페이스만 맞으면 됩니다.

### PostgreSQL

```bash
npm install @langchain/langgraph-checkpoint-postgres
```

```ts
import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";

const checkpointer = PostgresSaver.fromConnString(
  "postgresql://postgres:postgres@localhost:5432/postgres?sslmode=disable",
);

await checkpointer.setup();   // ← 최초 1회. 테이블을 만듭니다.
```

> ⚠️ **함정**: **`await checkpointer.setup()` 을 빼먹으면 테이블이 없어서 런타임에 터집니다.** 그런데 이 에러는 에이전트를 만들 때가 아니라 **첫 `invoke` 에서** 납니다. 그래서 "에이전트는 잘 만들어졌는데 부르면 DB 에러" 라는 헷갈리는 모양이 됩니다. `setup()` 은 멱등이므로 앱 부팅 시 무조건 한 번 호출해도 됩니다. 다만 프로덕션에서는 마이그레이션 파이프라인에서 한 번만 돌리고 앱은 스키마가 이미 있다고 가정하는 편이 낫습니다 — 인스턴스 여러 대가 동시에 `setup()` 을 때리면 경합이 납니다.

### 무엇을 고를 것인가

| 상황 | 선택 |
|---|---|
| 실습, 단위 테스트, 노트북 | `MemorySaver` |
| 로컬 개발인데 재시작해도 대화가 남았으면 | `SqliteSaver` |
| 단일 서버 소규모 서비스 | `SqliteSaver` |
| 다중 인스턴스 / 프로덕션 | `PostgresSaver` |
| 이미 Mongo/Redis 를 쓰고 있다 | `MongoDBSaver` / `RedisSaver` |

> 💡 **실무 팁**: **로컬 개발도 `SqliteSaver` 로 하세요.** 비용은 파일 하나인데, 얻는 게 큽니다. `MemorySaver` 로 개발하면 10-4 의 **스레드 오염 함정이 프로세스 재시작에 가려져** 프로덕션에서 처음 드러납니다. SQLite 로 개발하면 그게 첫날 드러납니다. 문제는 빨리 보는 게 이깁니다.

> 💡 **실무 팁**: 쓰기 지속성 수준은 `durability` 옵션으로 조절합니다 — 예: `await agent.stream(input, { durability: "sync" })`. 체크포인트 쓰기를 언제 확정할지의 문제이고, 성능과 내구성의 저울질입니다. 기본값으로 시작하고, 장애 복구 요구사항이 명확해진 뒤에 손대세요.

---

## 10-8. 컨텍스트 윈도우 관리 — 대화가 길어질 때

메모리를 켜면 새 문제가 생깁니다. 매 턴 **전체 히스토리를 다시 보냅니다.**

```ts
for (let i = 1; i <= 5; i++) {
  const result = await agent.invoke(
    { messages: [{ role: "user", content: `${i}번째 질문입니다. 아무 말이나 한 문장 해 주세요.` }] },
    config,
  );
  const last = result.messages.at(-1)!;
  const usage = "usage_metadata" in last ? last.usage_metadata : undefined;
  console.log(`턴 ${i}: 입력 토큰 ${usage?.input_tokens}`);
}
```

**출력 예시** (토큰 수는 모델과 응답에 따라 다릅니다)
```
턴 1: 입력 토큰    28 | 누적 입력     28
턴 2: 입력 토큰    71 | 누적 입력     99
턴 3: 입력 토큰   118 | 누적 입력    217
턴 4: 입력 토큰   169 | 누적 입력    386
턴 5: 입력 토큰   224 | 누적 입력    610
```

> ⚠️ **함정 (비용이 제곱으로 는다)**: 턴당 입력 토큰이 **선형으로 증가**합니다. 그러면 N턴 대화의 **누적** 입력 토큰은 대략 **N² / 2 에 비례**합니다. 20턴이면 10턴의 2배가 아니라 **4배**입니다.
>
> 이게 잘 안 보이는 이유: 대시보드는 보통 "호출당 평균 토큰"을 보여줍니다. 그 숫자는 얌전해 보입니다. 문제는 **긴 대화 하나**가 짧은 대화 여러 개보다 훨씬 비싸다는 것이고, 평균에는 그게 안 나타납니다. 청구서에는 나타납니다.
>
> 그리고 끝은 컨텍스트 윈도우 초과입니다. 그때는 조용하지 않습니다 — **에러**입니다. 사용자 입장에서는 "대화를 오래 하면 갑자기 봇이 죽는다" 입니다.

### 해법 1 — 잘라내기(trim)

`beforeModel` 훅에서 히스토리를 잘라냅니다. 공식 문서의 패턴입니다.

```ts
import { RemoveMessage } from "@langchain/core/messages";
import { createAgent, createMiddleware } from "langchain";
import { MemorySaver, REMOVE_ALL_MESSAGES } from "@langchain/langgraph";

const trimMessages = createMiddleware({
  name: "TrimMessages",
  beforeModel: (state) => {
    const messages = state.messages;
    if (messages.length <= 3) return;      // 짧으면 손대지 않음

    const firstMsg = messages[0];
    const recentMessages = messages.length % 2 === 0
      ? messages.slice(-3)
      : messages.slice(-4);

    return {
      messages: [
        new RemoveMessage({ id: REMOVE_ALL_MESSAGES }),   // 전부 비우고
        firstMsg,                                          // 첫 메시지는 살리고
        ...recentMessages,                                 // 최근 것만
      ],
    };
  },
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  middleware: [trimMessages],
  checkpointer: new MemorySaver(),
});
```

**출력 예시** (토큰 수는 모델과 응답에 따라 다릅니다)
```
턴 1: 입력 토큰    28 | 저장된 messages 2
턴 2: 입력 토큰    71 | 저장된 messages 4
턴 3: 입력 토큰   104 | 저장된 messages 5
턴 4: 입력 토큰   112 | 저장된 messages 5
턴 5: 입력 토큰   109 | 저장된 messages 5
```

토큰이 어느 선에서 **평평해졌습니다.** 누적 비용이 제곱이 아니라 선형이 됩니다.

여기서 10-5 의 함정이 되돌아옵니다. `RemoveMessage({ id: REMOVE_ALL_MESSAGES })` 를 **먼저** 넣는 이유가 뭐였죠? `messages` 리듀서는 append 이기 때문입니다. 그냥 `{ messages: [firstMsg, ...recentMessages] }` 를 반환하면 자르기는커녕 **오히려 더 늘어납니다.** 트리밍 코드가 컨텍스트를 부풀리는 웃지 못할 상황이 됩니다 — 그리고 조용합니다.

> ⚠️ **함정**: 트리밍은 상태 자체를 바꾸므로 **체크포인트에도 반영됩니다.** 위 예에서 저장된 `messages` 가 5개에서 멈춘 걸 보세요. "모델에게만 덜 보여주고 저장은 다 하고 싶다" 라면 이 패턴은 답이 아닙니다 — 잘려나간 메시지는 진짜로 사라집니다. 감사 로그나 대화 다시보기가 필요하면 체크포인터 밖에 원본을 따로 남기세요.

트리밍의 정직한 손익:

| | |
|---|---|
| **얻는 것** | 비용 상한, 컨텍스트 초과 방지, 지연시간 안정 |
| **잃는 것** | 잘려나간 구간의 맥락 — **그리고 조용히 잃습니다.** 에이전트는 "모르겠다" 고 하지 않고 그냥 다르게 답합니다 |

사용자가 3턴 전에 "저는 채식주의자예요" 라고 말했는데 그 메시지가 잘려나갔다면, 에이전트는 스테이크를 추천합니다. 당당하게.

### 해법 2 — 요약(summarize)

그래서 실무에서는 통짜 트리밍보다 **요약**을 씁니다. 오래된 구간을 버리는 대신 한 문단으로 압축해 남기면 맥락 손실이 훨씬 적습니다. 내장 미들웨어가 있습니다.

```ts
import { createAgent, summarizationMiddleware } from "langchain";
import { MemorySaver } from "@langchain/langgraph";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  middleware: [
    summarizationMiddleware({
      model: "anthropic:claude-haiku-4-5",   // 요약은 싼 모델로
      trigger: { tokens: 4000 },             // 4000 토큰 넘으면 발동
      keep: { messages: 20 },                // 최근 20개는 원문 유지
    }),
  ],
  checkpointer: new MemorySaver(),
});
```

옵션은 이렇습니다.

| 옵션 | 의미 |
|---|---|
| `model` | 요약을 만들 모델 |
| `trigger` | 발동 조건 (토큰 수 / 메시지 수 / 컨텍스트 비율) |
| `keep` | 요약 후 원문으로 남길 양 |
| `summaryPrompt` | 요약 프롬프트 커스터마이즈 |
| `trimTokensToSummarize` | 요약 생성 시 넣을 최대 토큰 (기본 4000) |

도구 결과만 골라 비우는 `contextEditingMiddleware` 도 있습니다. `ClearToolUsesEdit` 전략으로 오래된 `ToolMessage` 를 `"[cleared]"` 같은 자리표시자로 바꿔치기합니다 — 10-3 에서 본 "컨텍스트를 터뜨리는 범인은 대개 ToolMessage" 에 대한 직접적인 답입니다.

둘 다 [Step 11 — 내장 미들웨어](../step-11-middleware-builtin/) 에서 실제로 돌려봅니다.

> ⚠️ **함정**: 요약도 공짜가 아닙니다. **요약 자체가 모델 호출**입니다. 발동될 때마다 지연시간이 튀고 비용이 듭니다. `trigger` 를 너무 낮게 잡으면 매 턴 요약하느라 원래보다 더 비싸질 수 있습니다. 그리고 요약은 **손실 압축**입니다 — "예산은 300만원 이하" 같은 구체적 제약이 "예산을 논의함" 으로 뭉개지면 그 뒤로 에이전트가 500만원짜리를 추천합니다. 조용히요.

> 💡 **실무 팁**: 판단 기준은 단순합니다. **대화가 짧게 끝나면(고객 문의 등) 아무것도 하지 마세요.** 컨텍스트 윈도우가 200K 인데 20턴 대화를 요약할 이유가 없습니다. 길어지는 게 확실할 때(코딩 에이전트, 리서치 에이전트) 요약을 켜세요. 최적화는 측정한 뒤에 합니다 — 실제 `usage_metadata.input_tokens` 를 며칠 찍어 보고 결정하세요.

---

## 10-9. 단기 vs 장기 메모리 — 차이와 경계

지금까지 만든 건 **단기 메모리**입니다. 경계가 명확합니다.

```ts
// 월요일
await agent.invoke(
  { messages: [{ role: "user", content: "저는 매운 음식을 못 먹습니다. 기억해 주세요." }] },
  { configurable: { thread_id: "chat-monday" } },
);

// 화요일 — 새 대화
const tuesday = await agent.invoke(
  { messages: [{ role: "user", content: "저녁 메뉴 하나만 추천해 주세요. 제 식성 알죠?" }] },
  { configurable: { thread_id: "chat-tuesday" } },
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 식성을 알려주시면 맞춰서 추천해 드릴게요! 어떤 음식을 선호하시나요?
```

사용자는 "기억해 주세요" 라고 명시했습니다. 그런데 못 합니다. **이건 버그가 아니라 단기 메모리의 정의입니다.** `checkpointer` 는 스레드 **안**의 일만 합니다.

| | 단기 메모리 (checkpointer) | 장기 메모리 (store) |
|---|---|---|
| **경계** | 스레드 안 | 스레드 밖 — 사용자/조직 단위 |
| **담는 것** | 이 대화의 메시지·상태 전체 | 사용자에 대한 사실 ("매운 것 못 먹음") |
| **저장 시점** | 자동 — 매 super-step | 명시적 — 누군가 저장하기로 결정해야 |
| **꺼내는 법** | 자동 — `thread_id` 로 통째로 | 검색 — 필요한 것만 골라서 |
| **수명** | 대화가 끝나면 보통 무의미 | 사용자가 있는 한 |
| **크기 문제** | 커지면 잘라야 함 (10-8) | 커져도 됨 — 검색해서 일부만 씀 |
| **API** | `new MemorySaver()` → `checkpointer` | `new MemoryStore()` → `store` |

둘은 **별개**이고 **같이 씁니다.**

```ts
import { MemorySaver, MemoryStore } from "@langchain/langgraph";

const checkpointer = new MemorySaver();   // 스레드 안
const store = new MemoryStore();          // 스레드 밖
```

경계선의 감각은 이렇습니다.

- "아까 조회한 부산 날씨" → **단기.** 대화가 끝나면 쓸모없습니다. 게다가 내일이면 틀린 정보입니다.
- "이 사람은 부산에 산다" → **장기.** 다음 대화에서도, 다음 달에도 유효합니다.
- "3번 옵션으로 하겠다고 했다" → **단기.** 이 대화의 결정입니다.
- "이 사람은 항상 간결한 답을 선호한다" → **장기.**

> ⚠️ **함정**: 단기 메모리로 장기 메모리를 흉내 내려고 **모든 대화를 한 `thread_id` 에 몰아넣는 것**. "그러면 다 기억하겠지" 라는 발상인데, 사용자가 100번 대화하면 그 스레드는 수천 개의 메시지를 갖게 됩니다. 그러면 (1) 매 요청 비용이 폭발하고, (2) 컨텍스트가 터지고, (3) 요약을 켜면 **정작 중요한 사실이 뭉개져** 사라집니다. 스레드는 대화 단위로 나누고, 스레드를 넘어야 하는 것만 장기 메모리에 명시적으로 넣으세요.

[Step 15 — 장기 메모리와 Store](../step-15-long-term-memory/) 에서 다룹니다.

---

## 10-10. 종합 — 조각을 맞추면

정리하면 전체 그림이 이렇습니다.

```
createAgent({ ..., checkpointer })         ← 어디에 저장할지 (앱 시작 시 1번)
        │
        ├─ invoke(입력, { configurable: { thread_id } })    ← 어느 대화 (요청마다)
        │     ├─ 저장된 상태를 읽고
        │     ├─ 리듀서로 입력을 합치고 (messages 는 append)
        │     ├─ 그래프 실행
        │     └─ super-step 마다 체크포인트 기록
        │
        ├─ getState(config)              ← 지금 상태 (checkpoint_id 없으면 최신)
        ├─ getStateHistory(config)       ← 이력 (index 0 = 최신, async iterable)
        └─ updateState(config, values)   ← 상태 수정 (리듀서 통과! 반환값 = 새 가지)
                                            config 에 checkpoint_id 가 있으면 포크
```

프로덕션 체크리스트:

- [ ] `checkpointer` 를 **실제로** 넘겼는가 (`thread_id` 만 있는 게 아니라)
- [ ] `MemorySaver` 가 아닌 영속 체크포인터인가
- [ ] `PostgresSaver` 라면 `setup()` 을 부팅/마이그레이션에서 호출하는가
- [ ] `thread_id` 가 **서버에서 인증 정보로 유도**되는가 (클라이언트 입력 그대로가 아니라)
- [ ] `thread_id` 가 255자 이하인가
- [ ] 오래된 체크포인트를 정리하는 배치가 있는가
- [ ] 긴 대화의 컨텍스트 전략이 있는가 (트림 / 요약 / 아무것도 안 함 — **의식적으로** 고른 것)
- [ ] `usage_metadata.input_tokens` 를 대화 길이별로 관측하고 있는가
- [ ] 테스트가 `thread_id` 를 하드코딩하지 않는가

---

## 정리

| 개념 | 하는 일 | API |
|---|---|---|
| LLM 은 무상태 | 메모리는 히스토리 재전송일 뿐 | — |
| `checkpointer` | **어디에** 저장할지. 에이전트 생성 시 | `createAgent({ checkpointer })` |
| `thread_id` | **어느 대화**로. 호출 시마다 | `{ configurable: { thread_id } }` |
| `MemorySaver` | 인메모리 저장. **데모용** | `new MemorySaver()` — `@langchain/langgraph` |
| `SqliteSaver` | 파일 저장 | `SqliteSaver.fromConnString("./x.sqlite")` — `@langchain/langgraph-checkpoint-sqlite` |
| `PostgresSaver` | DB 저장. `setup()` 필수 | `PostgresSaver.fromConnString(url)` — `@langchain/langgraph-checkpoint-postgres` |
| `getState` | 현재(또는 특정) 스냅샷 | `await agent.getState(config)` |
| `getStateHistory` | 이력. **index 0 = 최신** | `for await (const s of agent.getStateHistory(config))` |
| `updateState` | 상태 수정. **리듀서 통과** | `await agent.updateState(config, values, { asNode })` |
| 리플레이 | 과거 시점에서 재실행 | `invoke(입력, snapshot.config)` |
| 포크 | 과거를 고쳐 분기 | `invoke(입력, await updateState(snapshot.config, ...))` |
| 트리밍 | 히스토리 잘라내기 | `createMiddleware({ beforeModel })` + `REMOVE_ALL_MESSAGES` |
| 요약 | 히스토리 압축 | `summarizationMiddleware({ model, trigger, keep })` |
| 장기 메모리 | 스레드 **밖** | `new MemoryStore()` → `store` (Step 15) |

**핵심 함정 3가지**

1. **`checkpointer` 없이 `thread_id` 만 주면 아무것도 저장되지 않는다 — 조용히.** 에러도 경고도 없습니다. `thread_id` 는 저장소를 만들어 주지 않습니다. 진단은 `result.messages.length`.
2. **`updateState` 는 대입이 아니라 리듀서를 거친다.** `messages` 는 덮어쓰기가 아니라 **append** 됩니다. 정말 덮어쓰려면 `RemoveMessage({ id: REMOVE_ALL_MESSAGES })` 를 **맨 앞에**.
3. **`MemorySaver` 는 프로세스와 함께 사라지고, `thread_id` 재사용은 이전 대화를 딸려온다.** 이 둘은 짝입니다 — `MemorySaver` 가 재시작마다 초기화해 주는 바람에 `thread_id` 오염이 개발 중엔 안 보이다가, 영속 체크포인터로 바꾸는 순간 드러납니다.

**추가로 기억할 것**: 매 턴 전체 히스토리를 재전송하므로 **누적 비용이 N² 에 비례**합니다. 체크포인트는 **턴당 1개가 아니라 super-step 당 1개**라서 index 로 과거를 찾으면 틀립니다.

---

## 연습문제

1. `checkpointer` 를 주지 않은 에이전트에 `thread_id` 를 주고 두 번 `invoke` 하세요("제 이름은 홍길동입니다." → "제 이름이 뭐죠?"). 그다음 `checkpointer: new MemorySaver()` 만 추가해 똑같이 하세요. 두 경우의 **답변**과 **`result.messages.length`** 를 나란히 출력해 "에러 없이 조용히 다르다" 를 확인하세요.
2. `MemorySaver` 하나를 공유하는 에이전트 1개로 `thread_id` 3개를 만들어 각각 다른 이름을 알려준 뒤 되물으세요. `getState` 로 각 스레드의 messages 개수를 출력하고, **한 번도 쓴 적 없는 `thread_id`** 로 `getState` 를 하면 무엇이 나오는지도 확인하세요 (에러인가요?).
3. 2턴 대화 후 `getStateHistory` 를 `for await` 로 돌려 각 스냅샷의 `step` / `source` / `next` / messages 개수 / `checkpoint_id` 앞 8자리를 출력하세요. **index 0 은 최신인가요 최고참인가요? `source` 에 어떤 값들이 보이나요? 2턴인데 체크포인트가 왜 여러 개인가요?**
4. 1턴 대화한 스레드에 `updateState(config, { messages: [{ role: "user", content: "덮어쓰기 시도" }] })` 를 하세요. (a) 전후 개수를 출력하고 왜 그런지 설명하세요. (b) `RemoveMessage` 와 `REMOVE_ALL_MESSAGES` 로 **정말로** messages 를 하나로 만드세요. (c) 개수가 1인지 확인하세요.
5. 3턴 대화("제 목표는 마라톤 완주입니다." → "팁 하나만요." → "제 목표가 뭐라고 했죠?")를 만든 뒤, (a) **1턴이 끝난 시점**의 스냅샷을 내용 기준으로 찾고, (b) 거기서 `updateState` 로 "정정합니다. 제 목표는 금연입니다." 를 끼워 넣어 `forkConfig` 를 받고, (c) 그 config 로 "제 목표가 뭐죠?" 를 물어 답이 바뀌는지 확인하고, (d) 원본 `thread_id` 가 안 망가졌는지 확인하세요.
6. 같은 `thread_id` "shared" 로 "제 이름은 앨리스입니다." → (다른 사용자인 척) "제 이름이 뭐죠?" 를 보내 **오염을 재현**하세요. 그다음 `crypto.randomUUID()` 로 고친 버전을 보이세요.
7. `checkpointer` 를 붙인 에이전트로 6턴 대화하며 매 턴 `usage_metadata.input_tokens` 와 누적 합계를 출력하세요. 같은 걸 `TrimMessages` 미들웨어를 붙여 반복하고 두 누적 합계를 비교하세요. **트리밍으로 얻은 것과 잃은 것**을 주석으로 답하세요.
8. 도구를 붙인 에이전트로 도구를 부르게 만드는 질문을 1턴 던지고, `getState` 로 `values.messages` 의 각 `getType()` 을 출력하세요. **`ToolMessage` 가 체크포인트에 남아 있나요?** 이어서 "방금 조회한 날씨가 뭐였죠?" 를 물어 **도구를 다시 부르는지** 확인하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 11 — 내장 미들웨어](../step-11-middleware-builtin/)

10-8 에서 주석으로만 보고 넘어간 `summarizationMiddleware` 와 `contextEditingMiddleware` 를 실제로 돌려봅니다. 그 밖에 `modelRetryMiddleware`, `toolRetryMiddleware`, `piiMiddleware`, `todoListMiddleware` 같은 것들이 있습니다. 우리가 10-8 에서 `createMiddleware` 로 손수 만든 트리밍 미들웨어가, 사실은 LangChain 이 제공하는 확장 지점의 아주 작은 예였다는 것을 보게 됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(10-1 ~ 10-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 출력을 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 맨 위에 `import "dotenv/config";` 가 있어 `project/.env` 의 `ANTHROPIC_API_KEY` 를 읽습니다. 실행은 `npx tsx docs/reference/langchain/step-10-memory/practice.ts` 입니다. 모델을 `MODEL` 상수 한 곳에서만 참조하므로 OpenAI 로 바꾸려면 `"openai:gpt-5.5"` 로 고치면 끝입니다 — 체크포인터 동작은 **제공자와 무관**합니다. 저장은 모델이 아니라 LangGraph 쪽 일이니까요.

> ⚠️ **주의**: 세 파일 모두 모델을 실제로 호출합니다. `practice.ts` 전체 실행에 대략 20~30회, `solution.ts` 는 문제 7 의 12턴 때문에 그보다 많습니다. 요금이 발생하고 시간도 좀 걸립니다. 특정 절만 보고 싶으면 `main()` 안의 다른 호출을 주석 처리하세요.

### practice.ts

본문을 따라가며 손으로 쳐볼 예제를 `[10-1] ~ [10-9]` 블록 주석으로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다 막히면 같은 번호의 함수를 찾아 실행해 보면 됩니다.

- `[10-1]` 과 `[10-2]` 는 이 스텝의 두 축입니다. `[10-1]` 은 `checkpointer` 자체가 없는 경우, `[10-2]` 는 **`thread_id` 는 줬는데 `checkpointer` 를 깜빡한 경우**입니다. 후자가 진짜 함정입니다 — 코드가 그럴싸해 보이기 때문입니다. 두 블록 모두 답변보다 `printKV({ "messages 길이": ... })` 를 보세요. 2 vs 4 가 전부를 말해 줍니다.
- `[10-3]` 의 4턴 루프는 `console.log` 에 `누적 messages` 를 같이 찍습니다. 턴 2 에서 2 → 6 으로 **4가 뛰는 것**을 놓치지 마세요. 도구를 부르는 턴은 `user` + `ai(tool_calls)` + `tool` + `ai` 로 4개를 만듭니다. 도구 결과도 체크포인트에 남는다는 증거입니다.
- `[10-5]` 는 `getState` 의 8개 필드를 전부 찍은 뒤, `updateState` 전후 개수를 비교합니다. `결론: afterCount === beforeCount + 1 ? "append 되었다 (리듀서가 개입)" : "?"` 줄이 이 절의 핵심입니다 — **1이 아니라 +1** 입니다. 바로 아래에서 `RemoveMessage` 로 진짜 삭제를 보여줍니다.
- `[10-6]` 의 `history.find((s) => s.next.length === 0 && s.values.messages?.length === 4)` 를 주목하세요. `history[2]` 같은 **index 로 찾지 않습니다.** 모델이 도구를 부르거나 미들웨어가 끼면 index 가 밀리기 때문입니다. 못 찾았을 때 조용히 틀리는 대신 메시지를 찍고 `return` 하는 것도 의도적입니다.
- `[10-7]` 의 `SqliteSaver` / `PostgresSaver` 코드는 **주석으로만** 두었습니다. 해당 패키지가 이 프로젝트에 설치되어 있지 않아서, import 하면 `Cannot find module` 로 파일 전체가 죽기 때문입니다. 대신 그 아래에 `MemorySaver` 두 개(`saverA`, `saverB`)로 **프로세스 재시작을 흉내 내는** 실행 가능한 데모를 넣었습니다. 같은 `thread_id` 인데 0이 나오는 것이 핵심입니다.
- `[10-8]` 의 트림 미들웨어에는 `if (firstMsg === undefined) return;` 한 줄이 공식 문서 예제에 없는 채로 추가되어 있습니다. 이 프로젝트의 `tsconfig.json` 이 `noUncheckedIndexedAccess: true` 라서 `messages[0]` 이 `BaseMessage | undefined` 로 좁혀지기 때문입니다. 위에서 `length > 3` 을 확인했으니 실행 시에는 절대 안 걸리는 가드지만, 타입 체커에게는 필요합니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 `[문제 N]` 주석 블록으로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 블록 아래 `// 여기에 작성하세요` 자리에 직접 코드를 써 넣고 실행해 검증하면 됩니다. 파일을 그대로 실행하면 섹션 제목만 찍히고 아무 결과도 안 나옵니다 — 정상입니다.

- 문제 3, 4, 7 의 주석에는 **답이 아니라 질문**이 들어 있습니다("index 0 은 가장 오래된 것인가요, 가장 최신인가요?", "몇 개가 되었나요? 왜죠?"). 코드를 돌려서 눈으로 확인하고 주석으로 답을 적는 것까지가 문제입니다.
- 문제 4 (b) 와 문제 5 (a) 에만 힌트가 붙어 있습니다. 각각 `REMOVE_ALL_MESSAGES` 와 "next 가 []이고 messages 가 2개인 스냅샷" 입니다. 이 두 개는 힌트 없이는 API 문서를 한참 뒤져야 나오는 것들이라 미리 줬습니다.
- 필요한 import 는 파일 상단에 **미리 다 넣어 두었습니다** — `createMiddleware`, `RemoveMessage`, `REMOVE_ALL_MESSAGES`, `tool`, `z` 까지. 이 프로젝트의 tsconfig 에는 `noUnusedLocals` 가 없어서 안 써도 에러가 안 납니다. 다만 이 목록 자체가 힌트이기도 합니다 — 8문제를 다 풀면 이것들을 전부 쓰게 됩니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 여세요. 각 정답 위의 블록 주석에 "무엇을 확인해야 하는가" 와 기대 숫자가 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 1]` 의 해설은 **왜 `messages.length` 를 봐야 하는가**를 못 박습니다. 응답 텍스트는 모델이 "성함을 말씀해 주시면…" 처럼 둘러댈 수 있어서 못 믿습니다. 2 vs 4 는 프레임워크가 만든 숫자라 거짓말을 안 합니다.
- `[정답 2]` 의 해설에 중요한 게 하나 있습니다. LangGraph 는 **"존재하지 않는 스레드"와 "아직 아무 말 안 한 스레드"를 구분하지 않습니다.** 그래서 "이 스레드가 처음인가?" 를 `getState` 성공 여부로 판별하려던 코드는 전부 틀립니다 — `values.messages` 의 길이(또는 `undefined` 여부)를 봐야 합니다.
- `[정답 4]` 가 이 파일의 하이라이트입니다. `updateState` 를 "대입" 으로 이해하면 (a) 의 결과 3 이 설명되지 않습니다. 해설의 비유를 보세요 — **`updateState(config, X)` 는 "노드 하나가 X 를 반환한 것처럼 처리하라" 는 뜻입니다.** 노드가 `{ messages: [...] }` 를 반환하면 append 되니, 여기서도 append 됩니다. 완전히 같은 규칙입니다. (b) 에서 `RemoveMessage` 를 **앞**에 두는 이유도 같은 논리입니다.
- `[정답 5]` 의 해설은 타임트래블의 세 가지를 못 박습니다. 특히 **`updateState` 의 반환값**이 새 가지를 가리킨다는 것 — 이걸 안 받고 원래 `config` 로 invoke 하면 포크가 아니라 그냥 최신에서 이어쓰기가 됩니다. 그리고 스냅샷을 index 로 집으면 안 되는 이유가 다시 나옵니다.
- `[정답 6]` 의 해설이 이 스텝에서 가장 실무적인 대목입니다. **`MemorySaver` 가 `thread_id` 오염을 가려 준다**는 것. 프로세스 재시작마다 초기화되니 테스트가 통과합니다. `SqliteSaver` 로 바꾸는 순간 드러납니다. "영속성으로 바꿨더니 테스트가 깨졌다" 는 체크포인터가 깨뜨린 게 아니라 원래 깨져 있던 게 보이게 된 겁니다.
- `[정답 7]` 은 같은 6턴을 두 에이전트(트림 없음 / 트림 있음)에 돌리고 마지막에 절감률(`Math.round((1 - trimTotal / plainTotal) * 100)`)까지 계산합니다. 토큰을 꺼내는 `inputTokens()` 헬퍼가 `if (!("usage_metadata" in m)) return 0;` 로 시작하는 것에 주의하세요 — `usage_metadata` 는 optional 이고 제공자에 따라 아예 안 실려 옵니다. 해설의 결론이 중요합니다 — **잃은 것을 조용히 잃습니다.** 사용자가 3턴 전에 "저는 채식주의자예요" 라고 했는데 잘려나가면, 에이전트는 스테이크를 당당하게 추천합니다.
- `[정답 8]` 의 도구에는 `console.log("  (도구 실제 실행됨: ...)")` 가 심어져 있습니다. 두 번째 턴에서 이 줄이 **안 찍히는 것**이 정답입니다 — 히스토리의 `ToolMessage` 를 읽고 답했다는 뜻이니까요. 해설은 여기서 곧바로 "그런데 날씨는 변한다" 로 넘어갑니다. 이 절약의 대가가 무엇인지가 진짜 학습 포인트입니다.

```ts file="./solution.ts"
```
