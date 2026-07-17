# Step 14 — 컨텍스트와 런타임

> **학습 목표**
> - 컨텍스트의 **4가지 출처**(지시 / 상태 / 런타임 컨텍스트 / 장기 메모리)를 구분하고, 어떤 데이터를 어디에 둘지 판단한다
> - `contextSchema` 로 실행 시점 데이터(user_id, 권한, 언어)를 주입하고 `Runtime` 으로 읽는다
> - `dynamicSystemPromptMiddleware` 로 사용자별·권한별 시스템 프롬프트를 만든다
> - `wrapModelCall` 로 컨텍스트에 따라 **모델과 도구 세트**를 갈아끼운다
> - **컨텍스트 예산**을 의식하고, 컨텍스트 오염(context rot)을 피한다
> - 하나의 정의로 여러 고객사를 서빙하는 **멀티테넌트 에이전트**를 만든다
>
> **선행 스텝**: [Step 13 — Human-in-the-Loop](../step-13-hitl/)
> **예상 소요**: 90분

지금까지 우리는 에이전트에게 **무엇을 시킬지**를 다뤘습니다. 도구를 주고([Step 06](../step-06-tools/)), 루프를 돌리고([Step 08](../step-08-create-agent/)), 기억을 붙이고([Step 10](../step-10-memory/)), 미들웨어로 감쌌습니다([Step 12](../step-12-middleware-custom/)). 이번 스텝은 방향이 다릅니다. **모델에게 무엇을 보여줄지**를 다룹니다.

이게 왜 중요한가. 실무에서 에이전트가 이상하게 굴 때, 원인의 대부분은 모델이 멍청해서가 아닙니다. **모델이 필요한 걸 못 봤거나, 볼 필요 없는 걸 너무 많이 봤기 때문**입니다. 공식 문서도 같은 말을 합니다 — 에이전트 실패는 대개 모델 능력의 한계가 아니라 컨텍스트 부족에서 온다고. 프롬프트를 백 번 고쳐 쓰는 것보다 "이 모델이 지금 정확히 무엇을 받고 있는가"를 한 번 들여다보는 게 빠릅니다. 이 설계 작업을 **컨텍스트 엔지니어링(context engineering)** 이라고 부릅니다.

---

## 14-1. 컨텍스트 엔지니어링이란

프롬프트 엔지니어링은 "문장을 어떻게 쓸까"입니다. 컨텍스트 엔지니어링은 그보다 한 층 위입니다 — **"모델의 컨텍스트 윈도우에 무엇을, 어떤 형식으로, 언제 넣을까"**. 프롬프트는 그 안의 한 조각일 뿐입니다.

| | 프롬프트 엔지니어링 | 컨텍스트 엔지니어링 |
|---|---|---|
| 다루는 것 | 지시 문장의 표현 | 윈도우에 들어가는 **모든 것** |
| 대상 | 시스템 프롬프트 | 프롬프트 + 도구 + 메시지 + 검색 결과 + 메모리 |
| 시점 | 개발할 때 한 번 | **매 모델 호출마다** |
| 질문 | "어떻게 말해야 잘 알아들을까" | "무엇을 보여주고 무엇을 빼야 할까" |

말로만 하면 와닿지 않으니 먼저 **모델이 실제로 본 것**을 눈으로 봅시다. `wrapModelCall` 은 모델 호출 직전에 끼어들어 최종 요청(`ModelRequest`)을 통째로 넘겨받습니다.

```ts
import { createAgent, createMiddleware } from "langchain";

const inspectContext = createMiddleware({
  name: "InspectContext",
  wrapModelCall: async (request, handler) => {
    const systemText = request.systemMessage.text;
    console.log(`시스템 프롬프트: ${systemText.length}자`);
    console.log(`도구        : [${request.tools.map((t) => t.name).join(", ")}]`);
    console.log(`메시지      : ${request.messages.length}개`);
    console.log(`런타임 컨텍스트: ${JSON.stringify(request.runtime.context)}`);
    return handler(request);   // ← 반드시 호출해야 모델이 실행된다
  },
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  systemPrompt: "당신은 간결한 사내 도우미입니다. 한국어로 두 문장 이내로 답합니다.",
  middleware: [inspectContext],
});

await agent.invoke({ messages: [{ role: "user", content: "환불 규정을 알려줘." }] });
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
시스템 프롬프트: 42자
도구        : []
메시지      : 1개
런타임 컨텍스트: undefined
AI     │ 죄송하지만 저는 회사의 구체적인 환불 규정 정보를 가지고 있지 않습니다.
       │ 고객센터나 사내 인트라넷을 확인해 주시겠어요?
```

모델은 환불 규정을 모릅니다. 당연합니다 — **우리가 안 줬으니까요.** 여기서 프롬프트를 아무리 다듬어도("너는 환불 전문가야", "반드시 정확히 답해") 없는 정보는 나오지 않습니다. 필요한 건 더 나은 문장이 아니라 **환불 규정 문서를 컨텍스트에 넣는 일**입니다. 이 차이가 이 스텝의 전부입니다.

그리고 `런타임 컨텍스트: undefined` 를 기억해 두세요. 이 자리를 14-3 에서 채웁니다.

> 💡 **실무 팁**: 이 `inspectContext` 같은 미들웨어를 프로젝트에 하나 만들어 두세요. "왜 모델이 저 도구를 안 부르지?", "왜 갑자기 반말을 하지?" 같은 질문의 절반은 요청을 한 번 찍어 보면 그냥 풀립니다. 도구 목록이 비어 있거나, 프롬프트가 예상과 다르게 이어붙어 있거나, 메시지가 300개까지 불어나 있는 걸 발견하게 됩니다. `handler(request)` 를 호출하는 것만 잊지 마세요 — 안 부르면 모델이 아예 실행되지 않습니다.

---

## 14-2. 컨텍스트의 4가지 출처 — 이 스텝의 핵심

에이전트에 들어가는 데이터는 **네 곳** 중 하나에서 옵니다. 이걸 구분하는 게 이번 스텝에서 가장 중요합니다. 여기서 틀리면 비밀이 DB 에 영구 저장되거나, 사용자 정보가 요청끼리 섞입니다.

| 출처 | 무엇 | 어디에 선언 | 수명 | 저장되나 | 누가 쓰나 |
|---|---|---|---|---|---|
| **지시** (instructions) | 고정된 규칙 | `systemPrompt` | 영원 (코드) | 코드에 | 모든 요청 |
| **상태** (state) | 이 대화의 진행 데이터 | `stateSchema` | 스레드(thread) | **체크포인터에 저장됨** | 이 대화만 |
| **런타임 컨텍스트** (context) | 이 요청의 실행 정보 | `contextSchema` | **invoke 1회** | **저장 안 됨** | 이 호출만 |
| **장기 메모리** (store) | 대화를 넘어 남는 지식 | `store` | 영구 | **스토어에 저장됨** | 여러 대화 |

각각의 판단 기준을 한 문장으로 줄이면 이렇습니다.

- **지시**: 모든 사용자에게 똑같은가? → `systemPrompt`
- **상태**: 이 대화가 진행되면서 변하고, 다음 턴에도 기억해야 하는가? → `stateSchema`
- **런타임 컨텍스트**: 요청마다 다르고, 저장되면 **안 되는** 것인가? → `contextSchema`
- **장기 메모리**: 대화가 끝나도 남아야 하는가? → `store`

네 곳을 한 번에 읽는 도구를 만들어 봅시다.

```ts
import { createAgent, tool } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore as GraphStore } from "@langchain/langgraph";
import type { ToolRuntime } from "@langchain/core/tools";
import * as z from "zod";

// (b) 상태 — 이 대화 안에서만 살아 있고, 체크포인터에 저장된다.
const fourState = z.object({
  ticketCount: z.number().default(0),
});

// (c) 런타임 컨텍스트 — 이번 invoke 에만 살아 있고, 어디에도 저장되지 않는다.
const fourContext = z.object({
  userId: z.string(),
  plan: z.enum(["free", "pro"]),
});

const whereAmI = tool(
  async (_input, runtime: ToolRuntime<typeof fourState, typeof fourContext>) => {
    const ticketCount = runtime.state.ticketCount;              // (b) 상태
    const { userId, plan } = runtime.context;                   // (c) 컨텍스트
    const saved = await graphStore(runtime)?.get(["preferences", userId], "profile"); // (d) 장기 메모리
    const nickname = (saved?.value as { nickname?: string } | undefined)?.nickname ?? "(없음)";

    return [
      `상태(state).ticketCount = ${ticketCount}`,
      `컨텍스트(context).userId = ${userId}, plan = ${plan}`,
      `장기메모리(store).nickname = ${nickname}`,
    ].join("\n");
  },
  {
    name: "where_am_i",
    description: "현재 실행에서 접근 가능한 상태/컨텍스트/장기메모리 값을 그대로 보고합니다.",
    schema: z.object({}),
  },
);

const store = new InMemoryStore();
await store.put(["preferences", "u-77"], "profile", { nickname: "민수님" });

const agent = createAgent({
  systemPrompt: "당신은 사내 헬프데스크입니다. 도구 결과를 그대로 옮겨 적으세요.", // (a) 지시
  model: "anthropic:claude-sonnet-4-6",
  tools: [whereAmI],
  stateSchema: fourState,      // (b)
  contextSchema: fourContext,  // (c)
  store,                       // (d)
  checkpointer: new MemorySaver(),
});

const result = await agent.invoke(
  { messages: [{ role: "user", content: "지금 내가 접근 가능한 값들을 보고해줘." }] },
  {
    configurable: { thread_id: "t-142" },     // ← 스레드 (상태의 주소)
    context: { userId: "u-77", plan: "pro" }, // ← 런타임 컨텍스트
  },
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ 상태(state).ticketCount = 0
       │ 컨텍스트(context).userId = u-77, plan = pro
       │ 장기메모리(store).nickname = 민수님
```

`configurable.thread_id` 와 `context` 가 **config 객체의 다른 자리에 있는 것**에 주목하세요. 공식 문서의 표현을 그대로 옮기면: `thread_id` 는 *대화*(메시지 히스토리, 체크포인트)를 지정하고, `context` 는 도구와 미들웨어가 호출 시점에 읽는 *요청별* 데이터를 나릅니다. 둘은 완전히 다른 축입니다.

> ⚠️ **함정 (이 스텝에서 가장 비싼 실수) — context 를 state 에 넣으면 체크포인트에 영구 저장된다**
>
> `user_id`, API 토큰, 세션 키 같은 걸 "어차피 도구에서 읽어야 하니까" 하고 `stateSchema` 에 넣는 코드를 자주 봅니다. **state 는 체크포인터에 저장됩니다.** 즉 그 토큰이 DB 에 평문으로 박힙니다.
>
> ```ts
> // 잘못된 코드
> const badState = z.object({ apiToken: z.string().default("") });
> const agent = createAgent({ /* ... */ stateSchema: badState, checkpointer: saver });
> await agent.invoke(
>   { messages: [...], apiToken: "sk-SECRET-1234" },
>   { configurable: { thread_id: "leak-1" } },
> );
>
> // 체크포인트를 열어 보면:
> const tuple = await saver.getTuple({ configurable: { thread_id: "leak-1" } });
> console.log(tuple?.checkpoint.channel_values?.["apiToken"]);
> // → "sk-SECRET-1234"     ← 토큰이 그대로 저장되어 있다
> ```
>
> `MemorySaver` 면 프로세스가 죽을 때 같이 사라지지만, 프로덕션의 Postgres 체크포인터라면 **DB 테이블에 영구히 남습니다.** 백업으로, 복제본으로, 로그로 따라갑니다. 6개월 뒤 감사(audit)에서 발견됩니다.
>
> 같은 값을 `contextSchema` 로 옮기면 체크포인트에 **채널 자체가 생기지 않습니다**:
> ```ts
> const tuple = await saver.getTuple({ configurable: { thread_id: "safe-1" } });
> Object.keys(tuple?.checkpoint.channel_values ?? {});
> // → ["messages", "jumpTo", "__pregel_tasks"]     ← apiToken 이 없다
> ```
>
> **판단 기준 한 줄: "이 값이 6개월 뒤 DB 에 남아 있어도 괜찮은가?"** 아니라면 `context` 입니다.

> 💡 **실무 팁**: state 와 context 가 헷갈릴 때는 "재현"을 생각하세요. 체크포인트는 **대화를 그대로 재생**할 수 있어야 합니다([Step 10](../step-10-memory/)의 time-travel). 그러니 "재생할 때 다시 필요한 것"(메시지, 진행 상황, 수집한 정보)은 state 입니다. 반면 "재생할 때 새로 주면 되는 것"(누가 요청했는지, 어떤 토큰으로)은 context 입니다. 어제 발급된 토큰을 오늘 재생하며 쓰면 안 되죠 — 그게 context 여야 하는 이유입니다.

---

## 14-3. `contextSchema` — 실행 시점 데이터 주입

`contextSchema` 는 zod 스키마로 "이 에이전트는 실행할 때 이런 데이터를 받는다"를 선언합니다. 그리고 `invoke` 의 두 번째 인자에 `context` 로 실제 값을 넘깁니다.

```ts
import { createAgent, tool } from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import type { ToolRuntime } from "@langchain/core/tools";
import * as z from "zod";

const strictContext = z.object({
  userId: z.string(),
  role: z.enum(["member", "admin"]),
});

const whoAmI = tool(
  async (_input, runtime: ToolRuntime<any, typeof strictContext>) => {
    const ctx = runtime.context;
    return `userId=${ctx.userId} role=${ctx.role}`;
  },
  {
    name: "who_am_i",
    description: "현재 요청을 보낸 사용자의 식별 정보를 반환합니다.",
    schema: z.object({}),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [whoAmI],
  systemPrompt: "who_am_i 도구를 호출해 결과를 그대로 알려주세요.",
  contextSchema: strictContext,
  checkpointer: new MemorySaver(),
});

const thread = { configurable: { thread_id: "t-143" } };

// 1회차 — context 를 준다
await agent.invoke(
  { messages: [{ role: "user", content: "내 정보 알려줘." }] },
  { ...thread, context: { userId: "u-1", role: "admin" } },
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
AI     │ userId=u-1 role=admin
```

### state 와 뭐가 다른가

같은 `u-1` 을 state 에 넣어도 도구에서 읽을 수 있습니다. 그런데 왜 굳이 나눌까요? 세 가지가 다릅니다.

| | `stateSchema` | `contextSchema` |
|---|---|---|
| 넘기는 자리 | `invoke({ messages, myField })` — **첫 번째** 인자 | `invoke({...}, { context })` — **두 번째** 인자 |
| 저장 | 체크포인터에 저장됨 | **저장 안 됨** |
| 수정 | 도구가 `Command({ update })` 로 바꿀 수 있음 | **읽기 전용** |
| 스레드 재개 시 | 자동 복원됨 | **매번 다시 줘야 함** |

공식 문서가 `createMiddleware` 의 `contextSchema` 를 설명하며 쓴 표현이 정확합니다 — *"미들웨어 컨텍스트는 읽기 전용이며 여러 호출 사이에 유지되지 않는다."*

마지막 줄이 함정으로 이어집니다.

> ⚠️ **함정 — context 는 스레드에 저장되지 않는다. 매 invoke 마다 다시 줘야 한다**
>
> 체크포인터를 붙이면 `thread_id` 만으로 메시지 히스토리가 복원됩니다. 그래서 "한 번 줬으니 이 스레드는 계속 기억하겠지" 라고 착각하기 쉽습니다. **아닙니다.** context 는 어디에도 저장되지 않으므로 **복원될 것이 없습니다.**
>
> ```ts
> // 2회차 — 같은 thread_id 인데 context 를 빼먹었다
> await agent.invoke({ messages: [{ role: "user", content: "한 번 더 알려줘." }] }, thread);
> ```
> ```
> 에러 발생 → [ { "expected": "string", "code": "invalid_type",
>                 "path": [ "userId" ],
>                 "message": "Invalid input: expected string, received undefined" } ] …
> ```
>
> 메시지는 이어졌는데 context 는 사라졌습니다. **다행히 이 경우는 시끄럽게 실패합니다** — 필수 필드가 있으면 zod 가 검증에서 잡아 줍니다. TypeScript 도 잡아 줍니다(아래 팁 참고). 문제는 조용히 실패하는 경우입니다.

> ⚠️ **함정 — `contextSchema` 의 `.default()` 는 발동하지 않는다 (조용히 틀린다)**
>
> 위 함정을 피하려고 필드를 전부 optional / default 로 바꾸면 더 나빠집니다.
>
> ```ts
> const looseContext = z.object({
>   userId: z.string().optional(),
>   role: z.enum(["member", "admin"]).default("member"),   // 기본값을 줬으니 안전하겠지?
> });
> ```
>
> `z.infer` 상으로 `role` 의 타입은 `"member" | "admin"` — **필수**입니다. TypeScript 는 `runtime.context.role` 이 항상 있다고 믿습니다. 그런데 `context` 를 아예 안 넘기고 invoke 하면:
>
> ```
> runtime.context = undefined
> ```
>
> `{ role: "member" }` 가 아닙니다. **`{}` 도 아닙니다. 통째로 `undefined` 입니다.** `.default()` 는 발동하지 않습니다 — 파싱할 객체 자체가 없으니까요. 부분적으로 `{ userId: "u-1" }` 만 넘겨도 결과는 `{"userId":"u-1"}` 그대로이고 `role` 은 여전히 `undefined` 입니다.
>
> 즉 **타입은 "항상 있다"고 말하는데 런타임에는 없습니다.** `runtime.context.role` 이 `undefined` 인 채로 `ALLOWED[role]` 같은 조회에 들어가면 `undefined` 가 나오고, 거기서 `.includes()` 를 부르면 그제서야 엉뚱한 곳에서 터집니다. 원인을 찾느라 한참 헤맵니다.
>
> **방어법**: context 를 읽을 때는 타입을 믿지 말고 항상 옵셔널 체이닝과 명시적 기본값을 쓰세요. 그리고 그 기본값은 **가장 좁은 권한**이어야 합니다.
> ```ts
> const role = request.runtime.context?.role ?? "viewer";   // 없으면 최소 권한 (fail-safe)
> ```
> 권한이 걸린 곳이라면 아예 터뜨리는 게 낫습니다 (14-8 참고).

> 💡 **실무 팁**: `contextSchema` 에 **필수 필드를 두면 TypeScript 가 컴파일 타임에 막아 줍니다.**
> ```ts
> await agent.invoke({ messages: [...] }, thread);
> // error TS2345: ... 'context' is missing
> ```
> 이건 공짜로 얻는 안전장치입니다. "혹시 몰라서" optional 로 만들고 싶은 유혹을 참으세요 — optional 로 만드는 순간 이 방어선이 사라지고, 위 함정의 조용한 실패 영역으로 들어갑니다. 다만 config 를 다른 함수에서 조립해 넘기면 타입이 헐거워져 이 방어선이 뚫릴 수 있으니, 그럴 땐 미들웨어에서 런타임 가드를 한 겹 더 두세요.

---

## 14-4. `Runtime` 객체 — 도구와 미들웨어 안에서

`Runtime` 은 "지금 이 실행에 대한 모든 것"입니다. 도구에서는 **두 번째 인자**로 받고, 미들웨어에서는 훅에 따라 두 번째 인자(`beforeModel`) 또는 `request.runtime`(`wrapModelCall`)으로 받습니다.

```ts
import type { ToolRuntime } from "@langchain/core/tools";

const dumpRuntime = tool(
  async (_input, runtime: ToolRuntime<any, typeof runtimeContext>) => {
    runtime.writer?.({ phase: "start", tool: "dump_runtime" });   // 커스텀 스트림으로 흘려보내기

    console.log("context.userId  =", runtime.context?.userId);
    console.log("toolCallId      =", runtime.toolCallId);
    console.log("store 연결됨    =", runtime.store !== null);

    runtime.writer?.({ phase: "done", tool: "dump_runtime" });
    return "런타임 정보를 콘솔에 출력했습니다.";
  },
  { name: "dump_runtime", description: "디버깅용...", schema: z.object({}) },
);
```

`ToolRuntime<TState, TContext>` 의 제네릭 인자는 **상태가 먼저, 컨텍스트가 나중**입니다. 순서를 바꿔 쓰면 `runtime.context` 가 엉뚱한 타입이 됩니다. 상태를 안 쓰면 첫 인자를 `any` 로 둡니다.

주요 필드:

| 필드 | 타입 | 용도 |
|---|---|---|
| `context` | `contextSchema` 추론 타입 | 이번 요청의 데이터 |
| `state` | `stateSchema` 추론 타입 | 현재 그래프 상태 |
| `store` | `BaseStore \| null` | 장기 메모리 |
| `writer` | `((chunk: unknown) => void) \| null` | `custom` 스트림 모드로 청크 전송 |
| `toolCallId` | `string` | 이 도구 호출의 ID ([Step 07](../step-07-tool-loop/)의 `ToolMessage` 짝) |
| `config` | `ToolRunnableConfig` | 원본 RunnableConfig |

`writer` 로 보낸 청크는 `streamMode: "custom"` 으로 실행할 때 받습니다.

```ts
const stream = await agent.stream(
  { messages: [{ role: "user", content: "런타임 정보 좀 보여줘." }] },
  { context: { userId: "u-9", tenantId: "acme" }, streamMode: "custom" },
);

for await (const chunk of stream) {
  console.log("custom 스트림:", chunk);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
context.userId  = u-9
toolCallId      = toolu_01A9F...
store 연결됨    = true
custom 스트림: { phase: 'start', tool: 'dump_runtime' }
custom 스트림: { phase: 'done', tool: 'dump_runtime' }
```

> ⚠️ **함정 — `runtime.store` 는 타입과 런타임이 어긋나 있다**
>
> 문서대로 `runtime.store.get(["preferences"], userId)` 를 쓰면 tsc 가 막습니다:
> ```
> error TS2551: Property 'get' does not exist on type 'BaseStore<string, unknown>'.
>               Did you mean 'mget'?
> ```
>
> 이유는 이름 충돌입니다. LangChain 생태계에는 **`BaseStore` 가 두 개** 있습니다.
>
> | | `@langchain/core/stores` | `@langchain/langgraph` |
> |---|---|---|
> | 모양 | `BaseStore<K, V>` | `BaseStore` |
> | 메서드 | `mget` / `mset` / `mdelete` | `get` / `put` / `search` / `delete` |
> | 성격 | 옛 키-값 스토어 | 네임스페이스 스토어 |
>
> `ToolRuntime.store` 의 **타입 선언**은 core 쪽(`mget`/`mset`)을 가리키는데, **실제로 주입되는 객체**는 LangGraph 쪽(`get`/`put`)입니다. 확인해 보면 이렇습니다 (`@langchain/core` 1.2.3 기준):
> ```
> runtime.store 의 실제 생성자 = AsyncBatchedStore
> get/put  존재? = function function
> mget/mset 존재? = undefined undefined
> ```
> **코드는 맞는데 타입만 틀린** 상황입니다. 타입을 믿고 `mget` 을 부르면 컴파일은 통과하고 런타임에 `is not a function` 으로 죽습니다. 반대로 올바른 `get` 을 부르면 컴파일이 막힙니다.
>
> 실습 파일에서는 헬퍼 하나로 정리했습니다:
> ```ts
> import { type BaseStore as GraphStore } from "@langchain/langgraph";
>
> function graphStore(runtime: { store: unknown }): GraphStore | null {
>   return (runtime.store ?? null) as GraphStore | null;
> }
>
> // 사용
> const saved = await graphStore(runtime)?.get(["preferences", userId], "profile");
> ```
> 장기 메모리는 [Step 15](../step-15-long-term-memory/)에서 본격적으로 다룹니다.

> 💡 **실무 팁**: `import type { ToolRuntime } from "@langchain/core/tools"` 를 쓰세요. 공식 문서에는 `from "langchain"` 으로 쓴 예제도 있는데 둘 다 동작하지만, 타입의 원산지는 `@langchain/core/tools` 입니다. 원산지에서 가져오면 `@langchain/core` 가 두 벌 설치됐을 때 생기는 미묘한 타입 불일치를 줄일 수 있습니다([Step 01](../step-01-setup/)의 중복 코어 함정).

---

## 14-5. 동적 시스템 프롬프트

사용자마다 프롬프트가 달라야 할 때, 사용자 수만큼 에이전트를 만들 수는 없습니다. `dynamicSystemPromptMiddleware` 는 **모델 호출 직전마다** 프롬프트를 계산합니다.

```ts
import { createAgent, dynamicSystemPromptMiddleware } from "langchain";
import * as z from "zod";

const promptContext = z.object({
  userName: z.string(),
  plan: z.enum(["free", "pro", "enterprise"]),
  locale: z.enum(["ko", "en"]),
});

const dynamicPrompt = dynamicSystemPromptMiddleware<z.infer<typeof promptContext>>(
  (state, runtime) => {
    const ctx = runtime.context;
    const lines = [
      ctx.locale === "ko"
        ? "당신은 한국어로만 답하는 고객 지원 에이전트입니다."
        : "You are a customer support agent. Answer only in English.",
      `상대방의 이름은 ${ctx.userName} 입니다. 이름으로 불러 주세요.`,
    ];

    // 요금제별 규칙 — 없는 기능을 약속하지 않게 막는 것이 핵심
    if (ctx.plan === "free") {
      lines.push(
        "이 사용자는 무료 요금제입니다. 유료 전용 기능(우선 지원, 전화 상담)은 안내하지 마세요.",
        "복잡한 요청은 요금제 업그레이드를 부드럽게 제안하세요.",
      );
    } else if (ctx.plan === "enterprise") {
      lines.push("이 사용자는 엔터프라이즈 고객입니다. 전담 매니저 연결을 먼저 제안하세요.");
    }

    // 상태(state)도 함께 볼 수 있습니다 — 대화가 길어지면 더 짧게
    if (state.messages.length > 10) {
      lines.push("대화가 길어졌습니다. 답변을 세 문장 이내로 줄이세요.");
    }

    return lines.join("\n");
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  contextSchema: promptContext,
  middleware: [dynamicPrompt],
});
```

같은 질문을 두 사용자로 던져 봅니다.

```ts
const question = { messages: [{ role: "user" as const, content: "전화로 상담받고 싶어요." }] };

await agent.invoke(question, { context: { userName: "김민수", plan: "free", locale: "ko" } });
await agent.invoke(question, { context: { userName: "박지훈", plan: "enterprise", locale: "ko" } });
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
── free 사용자 ──
AI     │ 김민수님, 죄송하지만 현재 요금제에서는 전화 상담을 제공하지 않습니다.
       │ 채팅으로 도와드릴 수 있고, 더 빠른 지원이 필요하시면 Pro 요금제를 고려해 보셔도 좋습니다.

── enterprise 사용자 (같은 질문, 같은 에이전트) ──
AI     │ 박지훈님, 전담 매니저를 바로 연결해 드리겠습니다.
       │ 편하신 시간대를 알려주시면 담당자가 연락드리도록 하겠습니다.
```

**에이전트 정의는 하나입니다.** 프롬프트가 실행 시점에 계산될 뿐입니다. 콜백의 시그니처는 `(state, runtime) => string | SystemMessage` 이고, `state.messages` 를 보고 있으므로 **대화가 진행되면서 프롬프트가 변할 수도** 있습니다.

> ⚠️ **함정 — `systemPrompt` 와 동적 프롬프트를 둘 다 주면 이어붙는다 (구분자 없이)**
>
> `dynamicSystemPromptMiddleware` 가 `systemPrompt` 를 **대체한다고 생각하기 쉽지만, 이어붙입니다.** 그것도 구분자 없이 그대로 붙입니다.
>
> ```ts
> const agent = createAgent({
>   systemPrompt: "STATIC-BASE.",
>   middleware: [dynamicSystemPromptMiddleware(() => "DYNAMIC-ONLY")],
>   /* ... */
> });
> ```
> 모델이 실제로 받는 시스템 메시지:
> ```
> "STATIC-BASE.DYNAMIC-ONLY"
> ```
>
> 공백도 줄바꿈도 없이 붙었습니다. 문장 두 개가 `"...합니다.반드시 한국어로..."` 처럼 뭉개지면 모델이 지시를 흘려 읽습니다. 에러는 안 납니다 — 그냥 품질이 조금 떨어질 뿐이라 알아채기 어렵습니다.
>
> **해결**: 동적 프롬프트를 쓸 거면 `systemPrompt` 를 **주지 마세요.** 고정 부분이 필요하면 콜백 안에서 같이 조립하세요.
> ```ts
> dynamicSystemPromptMiddleware<Ctx>((_state, runtime) =>
>   [BASE_RULES, localeRule(runtime.context.locale)].join("\n\n"),  // ← 구분자를 직접 관리
> );
> ```

> ⚠️ **함정 — `dynamicSystemPromptMiddleware` 는 한 에이전트에 하나만**
>
> "언어 담당 하나, 권한 담당 하나" 식으로 두 개를 넣으면 생성 시점에 죽습니다.
> ```
> Error: Middleware DynamicSystemPromptMiddleware is defined multiple times
> ```
> 미들웨어는 이름으로 식별되는데 둘 다 같은 이름이라 그렇습니다. 프롬프트 조립 로직은 **콜백 하나 안에서** 하세요. 정말 나눠야 한다면 `createMiddleware` 로 이름이 다른 미들웨어를 만들어 `request.systemMessage.concat(...)` 으로 이어붙이면 됩니다(14-8 에서 씁니다).

> 💡 **실무 팁**: 제네릭 인자에는 **zod 스키마가 아니라 추론된 타입**을 넣습니다.
> ```ts
> dynamicSystemPromptMiddleware<z.infer<typeof promptContext>>(...)   // ✅
> dynamicSystemPromptMiddleware<typeof promptContext>(...)            // ❌ context 가 스키마 타입이 된다
> ```
> `ToolRuntime` 은 스키마를 그대로 받아 알아서 추론하는데(`ToolRuntime<any, typeof schema>`), 이쪽은 추론된 타입을 받습니다. 두 API 의 관례가 달라서 자주 틀립니다. 빠뜨리면 `runtime.context` 가 `unknown` 이 되어 필드 접근에서 타입 에러가 납니다.

---

## 14-6. 동적 모델/도구 선택

프롬프트만 바꿀 수 있는 게 아닙니다. `wrapModelCall` 은 `ModelRequest` 를 통째로 받으므로 **모델도, 도구 목록도** 요청마다 갈아끼울 수 있습니다.

`ModelRequest` 의 주요 필드:

| 필드 | 타입 | 설명 |
|---|---|---|
| `model` | `AgentLanguageModelLike` | 이번 호출에 쓸 모델 |
| `systemMessage` | `SystemMessage` | 시스템 메시지 (**문자열이 아니라 객체**) |
| `messages` | `BaseMessage[]` | 모델에게 보낼 메시지 |
| `tools` | `(ServerTool \| ClientTool)[]` | 이번 호출에 노출할 도구 |
| `toolChoice` | `"auto" \| "none" \| "required" \| {...}` | 도구 선택 강제 |
| `responseFormat` | `ResponseFormatInput?` | 구조화 출력 |
| `state` | `TState & AgentBuiltInState` | 현재 상태 |
| `runtime` | `Runtime<TContext>` | 런타임 (여기서 `context` 를 읽는다) |

```ts
import { createAgent, createMiddleware } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";

const routingContext = z.object({
  plan: z.enum(["free", "pro"]),
  role: z.enum(["member", "admin"]),
});

const fastModel = new ChatAnthropic({ model: "claude-haiku-4-5", temperature: 0 });
const strongModel = new ChatAnthropic({ model: "claude-sonnet-4-6", temperature: 0 });

const routeByContext = createMiddleware({
  name: "RouteByContext",
  contextSchema: routingContext,
  wrapModelCall: async (request, handler) => {
    const ctx = request.runtime.context;
    const role = ctx?.role ?? "member";   // 없으면 가장 좁은 권한으로 (fail-safe)
    const plan = ctx?.plan ?? "free";

    // 도구 필터링 — 관리자가 아니면 admin_ 접두사 도구를 아예 안 보여준다
    const tools =
      role === "admin"
        ? request.tools
        : request.tools.filter((t) => !toolName(t).startsWith("admin_"));

    // 모델 선택 — free 는 싸고 빠른 모델, pro 는 더 좋은 모델
    const model = plan === "pro" ? strongModel : fastModel;

    // ModelRequest 는 평범한 객체입니다. 스프레드로 필요한 필드만 덮어씁니다.
    return handler({ ...request, model, tools });
  },
});

const agent = createAgent({
  model: fastModel,   // 기본값. 미들웨어가 매번 덮어씁니다.
  tools: [publicSearchDocs, adminDeleteUser, adminRefund],
  systemPrompt: "사용 가능한 도구로 요청을 처리하세요. 불가능하면 왜 불가능한지 말하세요.",
  contextSchema: routingContext,
  middleware: [routeByContext],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
── member (free) 가 삭제를 요청 ──
[라우팅] role=member plan=free → 모델=haiku, 도구=[public_search_docs]
AI     │ 죄송하지만 계정 삭제 기능에 접근할 권한이 없습니다.
       │ 관리자에게 문의해 주세요.

── admin (pro) 이 같은 요청 ──
[라우팅] role=admin plan=pro → 모델=sonnet, 도구=[public_search_docs, admin_delete_user, admin_refund]
       │ → tool admin_delete_user({"userId":"u-42"})
AI     │ 사용자 u-42 계정을 삭제했습니다.
```

member 에게 `admin_delete_user` 는 **존재하지 않습니다.** 모델은 없는 도구를 부를 수 없으니 거절합니다.

> ⚠️ **함정 — 도구 필터링은 "안 보여주기"이지 "실행 차단"이 아니다**
>
> `wrapModelCall` 에서 `tools` 를 걸러내는 건 **모델의 눈을 가리는 것**이지 실행 경로를 막는 게 아닙니다. 도구 객체는 여전히 에이전트에 등록되어 있습니다. 이전 대화에 남아 있던 도구 호출이 재개되거나, 다른 미들웨어가 도구를 되살리거나, 프롬프트 인젝션으로 모델이 도구 이름을 추측해 부르면 **그대로 실행됩니다.**
>
> **진짜 권한 검사는 도구 본문 안에 두세요.** 두 겹으로 갑니다.
> ```ts
> const adminDeleteUser = tool(
>   async ({ userId }, runtime: ToolRuntime<any, typeof routingContext>) => {
>     if (runtime.context?.role !== "admin") {          // ← 방어선 2 (진짜 경계)
>       return "권한 없음: 관리자만 사용할 수 있습니다.";
>     }
>     return `사용자 ${userId} 를 삭제했습니다.`;
>   },
>   { name: "admin_delete_user", /* ... */ },
> );
> ```
> 미들웨어 필터링의 목적은 **프롬프트 절약 + 오작동 감소**이고, **보안 경계는 도구 안**입니다. 둘을 혼동하면 "필터링했으니 안전하다"고 믿는 취약한 시스템이 됩니다.

> ⚠️ **함정 — `request.tools` 의 `t.name` 은 `unknown` 이다**
>
> 도구를 이름으로 거르려고 `request.tools.filter((t) => t.name.startsWith("admin_"))` 를 쓰면 막힙니다:
> ```
> error TS18046: 't.name' is of type 'unknown'.
> ```
> `request.tools` 의 원소 타입이 `ClientTool | ServerTool` 인데, `ServerTool` 의 정의가 그냥 `Record<string, unknown>` 이기 때문입니다(제공자가 서버에서 실행하는 내장 도구라 고정된 모양이 없습니다). 유니온에서 `.name` 을 읽으면 `unknown` 이 됩니다. 헬퍼로 정리하세요.
> ```ts
> function toolName(t: { name?: unknown }): string {
>   return typeof t.name === "string" ? t.name : "";
> }
> ```

> 💡 **실무 팁**: 모델 라우팅은 비용을 크게 줄이지만 **프롬프트 캐시를 무효화**합니다. 모델이 바뀌면 캐시가 처음부터 다시 쌓입니다. "메시지 6개 미만이면 haiku" 같은 임계값을 좁게 잡으면 대화 중에 모델이 왔다갔다하며 캐시를 계속 날려서 오히려 손해입니다. 임계값은 넉넉히 잡고, 한 번 넘어가면 되돌아오지 않게(단조 증가) 설계하세요.

---

## 14-7. 컨텍스트 예산 — 무엇을 넣고 무엇을 뺄 것인가

컨텍스트 윈도우가 200K 라고 200K 를 채우면 안 됩니다. **컨텍스트는 예산입니다.** 넣을 수 있다는 것과 넣어야 한다는 것은 다릅니다.

관련 없는 정보를 많이 넣으면 두 가지가 나빠집니다.

1. **비용과 지연**: 입력 토큰에 비례해 돈과 시간이 듭니다.
2. **정확도**: 관련 없는 정보가 모델의 주의를 분산시켜 **정답률이 떨어집니다.** 이 현상을 흔히 **컨텍스트 오염(context rot)** 이라고 부릅니다.

두 번째가 반직관적이라 사람들이 계속 틀립니다. "일단 다 넣으면 그중에 답이 있겠지"는 틀린 직관입니다. **답이 컨텍스트에 "있다"는 것과 모델이 그걸 "쓴다"는 건 다릅니다.**

실험해 봅시다. 정답 문서 1건을 소음 24건 한가운데에 묻습니다.

```ts
const RELEVANT_DOC = "사규 제12조: 연차는 입사 1년 후 15일이 부여되며, 매 2년마다 1일씩 늘어난다.";

const NOISE_DOCS = Array.from({ length: 24 }, (_, i) => {
  const topics = ["3층 정수기 교체", "주차장 공사", "동호회 모집", "보안 교육"];
  return `사내 공지 ${i + 1}: ${topics[i % topics.length]} 안내. 자세한 내용은 인트라넷을 참고하세요.`;
});

// A) 관련 문서 1건만
await askWithDocs("A) 관련 문서 1건", [RELEVANT_DOC]);

// B) 정답을 소음 한가운데 묻는다
const buried = [...NOISE_DOCS.slice(0, 12), RELEVANT_DOC, ...NOISE_DOCS.slice(12)];
await askWithDocs("B) 관련 1건 + 소음 24건", buried);
```

**출력 예시** (모델 응답이므로 매번 다릅니다 — 토큰 수도 문서 길이에 따라 달라집니다)
```
조건      A) 관련 문서 1건
문서 수    1
입력 토큰   118
답변       17일

조건      B) 관련 1건 + 소음 24건
문서 수    25
입력 토큰   871
답변       17일
```

입력 토큰이 **7배 이상** 뛰었습니다. 답의 품질은? 그만큼 좋아지지 않았습니다. 이 예제는 문서가 25개뿐인 장난감이라 모델이 여전히 맞히지만, 실제 RAG 는 문서가 수천 개입니다. 소음이 쌓일수록 모델은 엉뚱한 문서를 근거로 답하기 시작합니다.

### 무엇을 뺄 것인가 — 체크리스트

| 상황 | 대응 |
|---|---|
| 검색 결과를 전부 넣고 있다 | 상위 k개만. 관련도 임계값 아래는 버린다 |
| 도구를 20개 등록했다 | 컨텍스트에 따라 필터링 (14-6) |
| 대화가 100턴을 넘었다 | `summarizationMiddleware` 로 요약 |
| 도구 결과가 거대하다 | 도구 안에서 잘라서 반환. 전문은 store 에 |
| "혹시 몰라서" 넣은 문서가 있다 | 뺀다. 그 "혹시"가 정확도를 갉아먹는다 |

긴 대화 대응은 내장 미들웨어가 있습니다.

```ts
import { createAgent, summarizationMiddleware } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [/* ... */],
  middleware: [
    summarizationMiddleware({
      model: "anthropic:claude-haiku-4-5",  // 요약은 싼 모델로
      trigger: { tokens: 4000 },            // 4000 토큰 넘으면 발동
      keep: { messages: 20 },               // 최근 20개는 원문 유지
    }),
  ],
});
```

`trigger` 는 `tokens` / `messages` / `fraction` 중에서, `keep` 도 `tokens` / `messages` / `fraction` 중 **하나만** 지정합니다. 자세한 건 [Step 11](../step-11-middleware-builtin/)을 참고하세요.

> ⚠️ **함정 — "컨텍스트는 많을수록 좋다"는 착각**
>
> 이건 코드의 버그가 아니라 **설계의 버그**라 더 위험합니다. 에러도 없고 테스트도 통과합니다. 그냥 정확도가 조용히 5% 떨어질 뿐입니다.
>
> 전형적인 경로: RAG 를 붙인다 → 답이 가끔 틀린다 → "검색 결과가 부족한가?" → top_k 를 5에서 20으로 올린다 → **더 틀린다** → 원인을 모른 채 프롬프트를 고치기 시작한다.
>
> top_k 를 올리면 관련도가 낮은 문서가 딸려 들어옵니다. 모델은 그것도 "주어진 근거"로 취급합니다. **관련 없는 정보는 중립이 아닙니다 — 해롭습니다.** 늘리기 전에 "지금 넣고 있는 5개 중 실제로 쓰인 게 몇 개인가"를 먼저 재세요.

> 💡 **실무 팁**: 컨텍스트 예산을 관리하려면 먼저 **재야 합니다.** 응답의 `usage_metadata.input_tokens` 를 로그로 남기세요. Anthropic 모델이라면 `input_token_details.cache_read` 도 같이 보면 캐시가 실제로 먹고 있는지 알 수 있습니다. "이 에이전트는 요청당 평균 몇 토큰을 먹는가"를 모르면 최적화할 대상도 모릅니다. 참고로 `usage_metadata` 는 optional 이라 없을 수도 있으니 방어적으로 읽으세요.
> ```ts
> const usage = (last as { usage_metadata?: { input_tokens: number } }).usage_metadata;
> console.log(usage?.input_tokens ?? "(제공자가 안 줌)");
> ```

---

## 14-8. 실전 — 멀티테넌트 에이전트

지금까지 배운 걸 전부 씁니다. **하나의 에이전트 정의로 여러 고객사(테넌트)를 서빙**합니다. 테넌트마다 프롬프트도, 도구도, 모델도 다릅니다. 프로세스는 하나입니다.

```ts
const tenantContext = z.object({
  tenantId: z.enum(["acme", "globex"]),
  userId: z.string(),
  role: z.enum(["member", "admin"]),
});
type TenantContext = z.infer<typeof tenantContext>;

interface TenantProfile {
  displayName: string;
  tone: string;
  allowedTools: string[];
  model: ChatAnthropic;
}

const TENANTS: Record<TenantContext["tenantId"], TenantProfile> = {
  acme: {
    displayName: "ACME 주식회사",
    tone: "격식 있는 존댓말로, 답변 끝에 담당자 연락처를 안내합니다.",
    allowedTools: ["public_search_docs", "acme_check_stock"],
    model: strongModel,
  },
  globex: {
    displayName: "글로벡스",
    tone: "친근한 반존대로 짧게 답합니다. 이모지는 쓰지 않습니다.",
    allowedTools: ["public_search_docs"],
    model: fastModel,
  },
};

const tenantRouting = createMiddleware({
  name: "TenantRouting",
  contextSchema: tenantContext,
  wrapModelCall: async (request, handler) => {
    const ctx = request.runtime.context;
    if (ctx === undefined || ctx === null) {
      // context 없이 들어온 요청은 어느 테넌트인지 알 수 없습니다.
      // 조용히 기본 테넌트로 넘기면 데이터가 새어 나갑니다. 반드시 막으세요.
      throw new Error("TenantRouting: context.tenantId 가 없습니다. invoke 에 context 를 주세요.");
    }

    const profile = TENANTS[ctx.tenantId];
    const tools = request.tools.filter((t) => profile.allowedTools.includes(toolName(t)));

    // concat 을 쓰면 다른 미들웨어가 붙여 둔 캐시 제어나 콘텐츠 블록이 보존됩니다.
    const systemMessage = request.systemMessage.concat(
      ["", `고객사: ${profile.displayName}`, `말투 규칙: ${profile.tone}`, `현재 사용자 권한: ${ctx.role}`].join("\n"),
    );

    return handler({ ...request, model: profile.model, tools, systemMessage });
  },
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
── ACME 사용자 (재고 도구 있음) ──
[테넌트] acme / u-1 → 도구=[public_search_docs, acme_check_stock]
       │ → tool acme_check_stock({"sku":"SKU-1234"})
AI     │ SKU-1234 의 재고는 42개입니다. 추가 문의사항은 담당자에게 연락 주시기 바랍니다.

── 글로벡스 사용자 (재고 도구 없음, 같은 질문) ──
[테넌트] globex / u-2 → 도구=[public_search_docs]
AI     │ 재고 조회 기능은 제공하지 않아. 문서 검색은 도와줄 수 있어.
```

### 격리는 세 겹이다

| 겹 | 수단 | 모델이 협조해야 하나 |
|---|---|---|
| 1. 프롬프트 | 테넌트 규칙을 `systemMessage` 에 붙임 | **예** (모델이 무시할 수 있음) |
| 2. 도구 | `allowedTools` 로 필터링 | **예** (등록은 되어 있음) |
| 3. **데이터** | **store 네임스페이스에 `tenantId`** | **아니오** ← 진짜 방어선 |

3번이 핵심입니다. 1·2 는 모델이 협조해야 성립하지만, 3번은 모델이 무슨 짓을 해도 남의 네임스페이스를 못 봅니다.

```ts
await store.put(["reports", "acme"], "note", { text: "알파: 3분기 목표는 20% 성장" });
await store.put(["reports", "globex"], "note", { text: "베타: 신제품 출시일은 11월" });

const readTenantNote = tool(
  async (_input, runtime: ToolRuntime<any, typeof tenantContext>) => {
    const tenantId = runtime.context?.tenantId;
    if (tenantId === undefined) return "테넌트를 알 수 없습니다.";
    // 네임스페이스에 tenantId 가 박혀 있으므로 읽기 자체가 스코프된다
    const item = await graphStore(runtime)?.get(["reports", tenantId], "note");
    return (item?.value as { text?: string } | undefined)?.text ?? "메모가 없습니다.";
  },
  { name: "read_tenant_note", description: "우리 회사에 저장된 메모를 읽습니다.", schema: z.object({}) },
);
```

> ⚠️ **함정 (가장 무서운 것) — 도구에서 `runtime` 을 안 받고 클로저로 `user_id` 를 캡처하면 동시 요청에서 섞인다**
>
> 이 코드는 개발할 때 완벽하게 동작합니다.
> ```ts
> let currentUserId = "";                      // ← 모듈 전역
>
> const whoAmIBad = tool(
>   async () => `현재 사용자: ${currentUserId}`,   // runtime 을 안 받고 바깥 변수를 읽는다
>   { name: "who_am_i_bad", description: "...", schema: z.object({}) },
> );
>
> const askBad = async (userId: string) => {
>   currentUserId = userId;                    // 요청 직전에 세팅
>   const result = await badAgent.invoke({ messages: [{ role: "user", content: "내가 누구지?" }] });
>   return lastText(result.messages);
> };
> ```
>
> 요청을 하나씩 보내면 잘 됩니다. **동시에** 보내면:
> ```ts
> const [badA, badB] = await Promise.all([askBad("alice"), askBad("bob")]);
> ```
> ```
> alice 요청의 답: 현재 사용자: bob
> bob   요청의 답: 현재 사용자: bob
> ```
>
> **alice 가 bob 의 정보를 봤습니다.**
>
> 왜? `Promise.all` 이 `askBad("alice")` 를 시작하고, 그게 `await` 에서 멈춘 사이에 `askBad("bob")` 이 실행되어 `currentUserId` 를 `"bob"` 으로 덮어씁니다. 그 다음 alice 요청의 도구가 실행될 때 전역은 이미 `"bob"` 입니다.
>
> Node 는 싱글 스레드지만 **동시성은 있습니다.** 이 사실을 놓치면 로컬 개발(요청 1개씩)에서는 멀쩡하다가 트래픽이 붙는 순간 남의 데이터가 보이기 시작합니다. 재현도 안 되고, 로그를 봐도 정상으로 보입니다. **최악의 버그입니다.**
>
> **고치는 법은 하나입니다 — `runtime.context` 에서 읽으세요.**
> ```ts
> const whoAmIGood = tool(
>   async (_input, runtime: ToolRuntime<any, typeof userContext>) => {
>     // 이 값은 "이 도구 호출"에 묶여 있다. 동시 요청끼리 섞일 수 없다.
>     return `현재 사용자: ${runtime.context.userId}`;
>   },
>   { name: "who_am_i_good", description: "...", schema: z.object({}) },
> );
> ```
> ```
> alice 요청의 답: 현재 사용자: alice
> bob   요청의 답: 현재 사용자: bob
> ```
>
> **규칙: 요청마다 달라지는 값은 절대 모듈 스코프에 두지 마세요.** `contextSchema` 로 넘기고 `runtime.context` 로 읽으세요. 이 규칙 하나가 멀티테넌트 사고의 대부분을 막습니다.

> 💡 **실무 팁**: 멀티테넌트에서 가장 중요한 한 줄은 `throw new Error("context 가 없습니다")` 입니다. context 가 없을 때 "일단 기본 테넌트로" 넘어가는 코드는 **언젠가 반드시 남의 데이터를 보여줍니다.** 알 수 없으면 거절하세요. 시끄럽게 실패하는 게 조용히 유출하는 것보다 백 배 낫습니다. 같은 이유로 권한 기본값은 항상 **가장 좁은 것**(`?? "viewer"`)이어야 합니다.

> 💡 **실무 팁**: 테넌트가 100개로 늘어도 코드는 그대로입니다. `TENANTS` 표에 줄만 추가하면 됩니다. 실제로는 이 표를 DB 에서 읽어 오게 만들고, 미들웨어가 `tenantId` 로 조회하도록 합니다. 그러면 **배포 없이** 테넌트를 추가할 수 있습니다. 컨텍스트 엔지니어링이 아키텍처가 되는 지점입니다.

---

## 정리

| 개념 | API | 핵심 |
|---|---|---|
| 지시 | `systemPrompt` | 모든 요청에 동일한 고정 규칙 |
| 상태 | `stateSchema` + `configurable.thread_id` | 대화 범위, **체크포인터에 저장됨** |
| 런타임 컨텍스트 | `contextSchema` + `invoke(..., { context })` | 요청 범위, **저장 안 됨**, 읽기 전용 |
| 장기 메모리 | `store` + `runtime.store` | 대화를 넘어 영구 |
| 런타임 접근 | `ToolRuntime<TState, TContext>` (도구 2번째 인자)<br/>`request.runtime` (`wrapModelCall`) | `context` / `state` / `store` / `writer` / `toolCallId` |
| 동적 프롬프트 | `dynamicSystemPromptMiddleware<z.infer<typeof S>>((state, runtime) => string)` | 한 에이전트에 **하나만** |
| 동적 모델/도구 | `createMiddleware({ wrapModelCall })` → `handler({ ...request, model, tools })` | 요청마다 갈아끼움 |
| 컨텍스트 축소 | `summarizationMiddleware({ model, trigger, keep })` | 긴 대화 요약 |

**핵심 함정 3가지**

1. **context 를 state 에 넣으면 체크포인트에 영구 저장된다.** 토큰·비밀·세션 키가 DB 에 평문으로 박힙니다. 판단 기준: "이 값이 6개월 뒤 DB 에 남아 있어도 괜찮은가?" 아니라면 `contextSchema` 입니다.
2. **context 는 스레드에 저장되지 않는다. 매 invoke 마다 다시 줘야 한다.** 필수 필드면 zod 와 TypeScript 가 잡아 주지만, `.optional()` / `.default()` 로 만들면 조용히 `undefined` 가 됩니다 — **`.default()` 는 발동하지 않습니다.** 타입은 "항상 있다"고 거짓말합니다.
3. **도구에서 `runtime` 을 안 받고 클로저로 `user_id` 를 캡처하면 동시 요청에서 섞인다.** 개발할 땐 멀쩡하고 트래픽이 붙으면 남의 데이터가 보입니다. 요청마다 달라지는 값은 절대 모듈 스코프에 두지 마세요.

**그 밖에 조용히 물리는 것들**

- `systemPrompt` + `dynamicSystemPromptMiddleware` 를 둘 다 주면 **구분자 없이 이어붙습니다** (`"STATIC-BASE.DYNAMIC-ONLY"`).
- `dynamicSystemPromptMiddleware` 를 두 개 넣으면 `Middleware ... is defined multiple times` 로 죽습니다.
- `runtime.store` 는 **타입(core 의 `mget`/`mset`)과 런타임(langgraph 의 `get`/`put`)이 어긋나** 있습니다. 캐스팅이 필요합니다.
- `request.tools` 의 `t.name` 은 `unknown` 입니다 (`ServerTool = Record<string, unknown>`).
- 도구 필터링은 **"안 보여주기"이지 "실행 차단"이 아닙니다.** 진짜 권한 검사는 도구 본문에.
- **컨텍스트는 많을수록 좋지 않습니다.** 관련 없는 정보는 중립이 아니라 해롭습니다(context rot).

**검증 버전**

| 패키지 | 버전 |
|---|---|
| `langchain` | 1.5.3 |
| `@langchain/core` | 1.2.3 |
| `@langchain/anthropic` | 1.5.1 |
| `@langchain/langgraph` | 1.4.8 |

기본 모델은 `anthropic:claude-sonnet-4-6` 입니다. OpenAI 를 쓰려면 `ANTHROPIC_API_KEY` 대신 `OPENAI_API_KEY` 를 넣고 모델 문자열을 `"openai:gpt-5.5"` 로 바꾸면 됩니다 — 이 스텝의 내용은 전부 제공자와 무관하게 동작합니다. 다만 `new ChatAnthropic({...})` 로 인스턴스를 직접 만든 자리(14-6, 14-8)는 `new ChatOpenAI({...})` 로 바꿔야 합니다.

---

## 연습문제

1. `contextSchema` 를 정의하고 도구에서 읽으세요. `userId`(문자열), `locale`(`"ko" | "en"`)을 받아, locale 에 따라 다른 인사말을 반환하는 `get_greeting` 도구를 만듭니다. 클로저로 캡처하지 말고 반드시 `runtime` 에서 꺼내세요.
2. **비밀은 어디에 두어야 하나.** API 토큰을 `stateSchema` 에 넣어 실행한 뒤 `saver.getTuple(config)` 로 체크포인트를 열어 토큰이 그대로 저장된 것을 확인하세요. 그다음 같은 토큰을 `contextSchema` 로 옮기고, 체크포인트에 토큰이 없다는 것을 확인하세요.
3. `dynamicSystemPromptMiddleware` 로 `locale`(`"ko" | "en" | "ja"`)에 따라 답변 언어를 바꾸세요. 같은 질문("물은 몇 도에서 끓나요?")을 세 locale 로 던져 확인합니다. (힌트: `systemPrompt` 를 같이 주면 안 됩니다 — 왜일까요?)
4. **권한별 도구 필터링.** `role`(`"viewer" | "editor" | "owner"`)에 따라 도구를 거르세요. viewer→`[read_report]`, editor→`+export_csv`, owner→전부. "리포트 삭제해줘"를 viewer 와 owner 로 각각 실행해 비교하세요.
5. **모델 라우팅.** `wrapModelCall` 에서 `request.messages.length` 를 보고 6개 미만이면 haiku, 이상이면 sonnet 을 고르세요. 체크포인터를 붙이고 같은 thread 로 4번 연달아 invoke 해서 도중에 모델이 바뀌는 것을 확인하세요.
6. **컨텍스트 예산.** 문서 6개를 전부 넣었을 때와, 질문 키워드로 관련 문서만 걸러 넣었을 때의 `usage_metadata.input_tokens` 를 비교해 몇 % 줄었는지 출력하세요.
7. **클로저 캡처 버그 재현하고 고치기 (가장 중요).** 모듈 전역 `currentUserId` 를 읽는 도구를 만들고, alice 와 bob 의 요청을 `Promise.all` 로 동시에 보내 답이 섞이는 것을 확인하세요. 그다음 `contextSchema` + `runtime.context` 로 고쳐 각자 올바른 답이 나오는 것을 확인하세요.
8. **멀티테넌트 에이전트.** `tenantId`(`"alpha" | "beta"`)에 따라 프롬프트·도구·모델을 전부 바꾸세요. `context` 가 없으면 에러를 던지고, store 를 `["reports", tenantId]` 네임스페이스로 격리해 alpha 로 실행했을 때 beta 데이터가 보이지 않는 것을 확인하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 15 — 장기 메모리와 Store](../step-15-long-term-memory/)

이번 스텝에서 `store` 를 잠깐 썼지만 "네 번째 출처가 있다"는 것만 보여줬습니다. 다음 스텝에서 네임스페이스 설계, 검색, 메모리 갱신 전략을 제대로 다룹니다. 그리고 이번에 만난 `BaseStore` 타입 함정도 거기서 정리합니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(14-1 ~ 14-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 출력을 눈으로 확인하고, `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `ANTHROPIC_API_KEY` 가 필요하며(`project/.env`), 맨 위의 `requireEnv("ANTHROPIC_API_KEY")` 가 키가 없으면 무엇을 해야 하는지 알려주고 종료합니다. 실행은 프로젝트 루트에서:

```bash
npx tsx docs/reference/langchain/step-14-context-runtime/practice.ts        # 전부
npx tsx docs/reference/langchain/step-14-context-runtime/practice.ts 14-5    # 14-5 절만
npx tsx docs/reference/langchain/step-14-context-runtime/solution.ts 7       # 7번 정답만
```

세 파일 다 인자로 절 번호나 문제 번호를 받습니다. 이 스텝은 모델을 여러 번 호출하므로(14-7 은 긴 프롬프트를 두 번 보냅니다) 전부 돌리면 시간과 토큰이 꽤 듭니다. 한 절씩 돌려 보는 걸 권합니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[14-1] ~ [14-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다.

- 파일 상단의 **`graphStore()` 와 `toolName()` 헬퍼**를 먼저 읽으세요. 둘 다 "라이브러리 타입이 현실과 안 맞아서" 존재하는 함수입니다. 주석에 왜 필요한지 적어 뒀습니다 — 14-4 와 14-6 의 함정과 짝지어 읽으면 됩니다.
- **`inspectContext` 미들웨어**(14-1)는 이 스텝 내내 재사용됩니다. `wrapModelCall` 에서 `request.systemMessage.text` / `request.tools` / `request.runtime.context` 를 찍어 "모델이 실제로 본 것"을 보여줍니다. 이 스텝을 다 읽고 나면 이 30줄이 이 파일에서 가장 실용적인 부분이라는 걸 알게 됩니다.
- **`[14-2]` 가 이 파일의 중심입니다.** `whereAmI` 도구 하나가 state / context / store 를 동시에 읽어 한 번에 출력합니다. 세 값이 각각 어디서 왔는지(체크포인터 / invoke 인자 / 스토어) 출력과 코드를 대조해 보세요.
- **`[14-3]` 의 두 번째 invoke** 는 일부러 타입을 우회합니다(`as unknown as Parameters<typeof agent.invoke>[1]`). 이유는 주석에 있습니다 — TypeScript 가 이 실수를 컴파일 타임에 막아 주기 때문에, **런타임에 무슨 일이 나는지 보려면** 타입 검사를 한 번 비켜가야 합니다. 이어서 optional 스키마 버전이 **에러 없이** `runtime.context = undefined` 를 찍는 걸 보여줍니다. 이 대비가 14-3 함정의 전부입니다.
- **`[14-7]` 은 같은 질문을 두 번 던집니다.** A(문서 1건)와 B(문서 25건)의 `input_tokens` 를 나란히 출력합니다. 숫자는 매번 조금씩 다르지만 배율은 일정합니다. 답변이 양쪽 다 맞더라도 "토큰을 7배 쓰고 같은 답을 얻었다"는 게 이 절의 요점입니다.
- **`[14-8]` 마지막의 `Promise.all` 블록**을 꼭 보세요. 두 테넌트를 동시에 호출해도 답이 안 섞이는 것이 `runtime.context` 를 쓴 덕분입니다. 이게 왜 당연하지 않은지는 `solution.ts` 의 7번에서 반대 사례로 확인합니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 함수 본문이 비어 있으니, 거기에 직접 구현하고 실행해 검증하면 됩니다.

- `graphStore()` 와 `toolName()` 헬퍼는 **이미 들어 있습니다.** 그대로 쓰세요. 이 스텝의 학습 목표는 타입 우회 요령이 아니라 컨텍스트 설계입니다.
- `[문제 2]` 와 `[문제 7]` 은 **잘못된 코드가 주석으로 먼저 주어집니다.** 그걸 그대로 실행해 "무엇이 잘못되는지" 눈으로 본 뒤 고치는 순서입니다. 특히 문제 7 은 (a) 를 건너뛰고 (b) 만 풀면 의미가 없습니다 — 버그를 먼저 재현하세요.
- `[문제 4]` 와 `[문제 8]` 이 도구(`readReport` / `exportCsv` / `deleteReport`)를 공유합니다. 문제 8 은 문제 4 의 도구를 재사용하니 4번을 먼저 푸세요.
- 파일 맨 아래 `void [...]` 줄은 아직 안 쓴 import 때문에 나는 경고를 막는 장치입니다. 문제를 풀며 실제로 쓰게 되면 지우세요.
- 그대로 실행하면 섹션 제목만 찍히고 아무 결과도 안 나옵니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답과 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 아래 주석에 "왜 이렇게 하는가"와 실무에서 어떻게 물리는지를 적어 뒀습니다.

- **`[정답 2]` 가 이 파일에서 가장 중요합니다.** state 버전은 체크포인트에서 `"sk-SECRET-1234"` 를 그대로 꺼내 보여주고, context 버전은 채널 목록이 `["messages", "jumpTo", "__pregel_tasks"]` 뿐이라 `apiToken` 이 아예 **존재하지 않는** 것을 보여줍니다. 이 두 출력을 나란히 보는 게 이 스텝의 핵심 교훈입니다.
- **`[정답 7]` 은 반드시 실행해서 눈으로 보세요.** (a) 는 alice 와 bob 요청이 **둘 다 "bob"** 을 반환합니다. 이건 재현이 잘 되는 편이지만 실제 서비스에서는 타이밍에 따라 가끔만 터져서 훨씬 찾기 어렵습니다. 해설 주석에 `Promise.all` 이 왜 전역을 덮어쓰는지 단계별로 적어 뒀습니다.
- `[정답 4]` 의 `ALLOWED` 표와 `?? "viewer"` 기본값에 주목하세요. context 가 없을 때 **가장 좁은 권한**으로 떨어지는 게 fail-safe 설계입니다. 여기서 `?? "owner"` 를 썼다면 그게 바로 사고입니다.
- `[정답 6]` 의 키워드 필터는 일부러 유치하게 만들었습니다(`d.keywords.some((k) => question.includes(k))`). 실무에서는 이 자리에 임베딩 검색이 들어갑니다([Step 16](../step-16-retrieval-rag/)). 지금 배울 건 "필터가 있다/없다"의 차이지 필터의 정교함이 아닙니다.
- `[정답 8]` 은 격리의 세 겹(프롬프트 / 도구 / **store 네임스페이스**)을 전부 구현하고, 마지막에 `context` 없이 호출해 미들웨어 가드가 실제로 터지는 것까지 확인합니다. 해설 주석에 적었듯 **3번(네임스페이스)만이 모델의 협조 없이 성립하는 진짜 방어선**입니다.

```ts file="./solution.ts"
```
