# Step 11 — 스트리밍과 프로덕션

> **학습 목표**
> - Deep Agent 의 이벤트가 **계층적으로** 온다는 것을 이해하고, `subgraphs: true` 로 자식 이벤트를 연다
> - `streamMode` 별로 무엇이 나오는지 구분하고, 목적에 맞는 모드를 고른다
> - `streamEvents(..., { version: "v3" })` 의 `run.subagents` 로 **부모와 자식 이벤트를 분리해** UI 에 그린다
> - todo 리스트와 파일 변경을 **실시간 진행상황**으로 보여준다
> - `streamTransformers` 로 나만의 프로젝션을 `run.extensions` 에 얹는다
> - 영속 체크포인터 + `durability` 로 **긴 실행이 죽어도 재개**시킨다
> - 서브에이전트가 곱하는 **토큰 비용을 통제**하고 프로덕션 체크리스트를 적용한다
>
> **선행 스텝**: [Step 10 — 장기 메모리와 스킬](../step-10-memory-skills/)
> **예상 소요**: 90분

지금까지 우리는 `agent.invoke()` 로 에이전트를 부르고, 끝난 뒤에 결과를 봤습니다. 데모로는 충분합니다. 그런데 Deep Agent 는 한 번 돌면 **3분에서 30분**이 걸립니다. 서브에이전트 다섯 개가 각자 조사하고, 파일을 쓰고, 오케스트레이터가 그걸 종합합니다. 그 30분 동안 사용자 화면에 스피너 하나만 돌고 있으면, 사용자는 5초 안에 탭을 닫습니다.

이 스텝은 두 가지를 다룹니다. 앞의 절반(11-1 ~ 11-6)은 **그 30분 동안 무슨 일이 일어나는지 밖으로 꺼내는 법**입니다. 뒤의 절반(11-7 ~ 11-11)은 **그 30분짜리 실행을 실제 서비스에서 죽지 않게, 파산하지 않게 돌리는 법**입니다. [Step 06 — 서브에이전트](../step-06-subagents/)에서 만든 위임 구조가 여기서 청구서로 돌아옵니다. 서브에이전트는 컨텍스트를 격리해 주는 대신, 토큰을 곱합니다.

---

## 11-1. Deep Agent 스트리밍이 어려운 이유

일반 에이전트([LangChain Step 09 — 스트리밍](../../langchain/step-09-streaming/))의 스트리밍은 단순합니다. 모델이 토큰을 뱉고, 그걸 순서대로 화면에 찍으면 끝입니다. 이벤트가 **한 줄**로 옵니다.

Deep Agent 는 다릅니다. `task` 도구가 서브에이전트를 스폰하면, 그 서브에이전트는 **자기만의 완전한 에이전트 루프**를 돕니다. 자기 모델을 부르고, 자기 도구를 부르고, 심지어 자기 서브에이전트를 또 스폰할 수도 있습니다. 그래서 이벤트가 **나무**로 옵니다.

```
사용자 요청
└─ 오케스트레이터
   ├─ write_todos           ← 부모의 도구 호출
   ├─ task(researcher)      ← 부모의 도구 호출인데, 그 안에서…
   │  └─ researcher 서브에이전트
   │     ├─ 모델 호출 (토큰이 여기서도 나온다)
   │     ├─ search_docs     ← 자식의 도구 호출
   │     └─ 모델 호출
   ├─ task(researcher)      ← 위와 병렬로 또 하나
   │  └─ researcher 서브에이전트 (또 다른 인스턴스)
   └─ task(writer)
      └─ writer 서브에이전트
```

여기서 두 가지 문제가 생깁니다.

**첫째, 기본값으로는 자식이 안 보입니다.** LangGraph 의 `stream()` 은 `subgraphs` 옵션이 기본 `false` 입니다. 서브에이전트는 서브그래프이므로, 이 옵션을 안 켜면 자식 내부에서 벌어지는 일이 **통째로** 스트림에서 빠집니다.

**둘째, 켜면 이번엔 뒤섞입니다.** 서브에이전트 세 개가 병렬로 돌면 세 개의 토큰 스트림이 동시에 도착합니다. 이걸 구분 없이 `process.stdout.write()` 하면 세 개의 글이 글자 단위로 뒤엉킨 죽이 됩니다.

직접 봅시다.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchDocs],
  subagents: [
    { name: "researcher", description: "주제 하나를 조사합니다.", tools: [searchDocs], model: "anthropic:claude-haiku-4-5" },
    { name: "writer", description: "보고서 초안을 씁니다." },
  ] as const,
  systemPrompt: "조사는 researcher 에게, 글쓰기는 writer 에게 위임하세요.",
});

const input = { messages: [{ role: "user" as const, content: "'벡터 검색'을 조사해서 알려줘." }] };

// (A) subgraphs 기본값(false)
let aCount = 0;
for await (const _chunk of await agent.stream(input, { streamMode: "updates" })) {
  aCount++;
}
console.log(`(A) subgraphs 없음  → 이벤트 ${aCount}건`);

// (B) subgraphs: true — 튜플 모양이 바뀐다
let bCount = 0;
const namespaces = new Set<string>();
for await (const [ns, _chunk] of await agent.stream(input, { streamMode: "updates", subgraphs: true })) {
  bCount++;
  namespaces.add(JSON.stringify(ns));
}
console.log(`(B) subgraphs: true → 이벤트 ${bCount}건, namespace ${namespaces.size}개`);
for (const ns of namespaces) console.log(`    ${ns}`);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
(A) subgraphs 없음  → 이벤트 9건
(B) subgraphs: true → 이벤트 27건, 서로 다른 namespace 3개
    []
    ["tools:toolu_01A9k2Bx7pQwErTyU3nMzXvC"]
    ["tools:toolu_01A9k2Bx7pQwErTyU3nMzXvC","model_request:8f3c1e4a-..."]
```

이벤트가 9건에서 27건으로 늘었습니다. **18건은 원래 있었는데 우리가 못 보고 있던 것**입니다. 서브에이전트가 그 안에서 모델을 부르고 도구를 부른 전부가 `subgraphs: false` 였을 땐 소리 없이 사라졌던 겁니다.

그리고 `namespace` 라는 것이 등장했습니다. 이것이 이 스텝 전체의 열쇠입니다.

| namespace | 누가 낸 이벤트인가 |
|---|---|
| `[]` (빈 배열) | 메인 에이전트(오케스트레이터)의 노드 |
| `["model_request:<uuid>"]` | 메인 에이전트의 모델 노드 |
| `["tools:<tool_call_id>"]` | `task` 도구가 스폰한 **서브에이전트** |
| `["tools:<tool_call_id>", "model_request:<uuid>"]` | 그 서브에이전트 **안의** 모델 노드 |

규칙은 하나입니다. **`"tools:"` 로 시작하는 세그먼트가 하나라도 있으면 서브에이전트가 낸 것입니다.**

```ts
const isSubagent = (ns: string[]) => ns.some((s) => s.startsWith("tools:"));
```

`tools:` 뒤에 붙는 것은 그 서브에이전트를 스폰한 `task` 도구 호출의 `tool_call_id` 입니다. 그래서 **어느 `task` 호출이 이 자식을 낳았는지**를 역추적할 수 있습니다. 서브에이전트 카드를 "그 위임을 지시한 AI 메시지" 아래에 붙이는 UI 가 가능한 이유입니다.

> ⚠️ **함정 (에러 없이 조용히 반쪽만 보임)**: `subgraphs: true` 를 빼먹어도 **에러가 나지 않습니다.** 스트림은 잘 돌고, 부모 이벤트는 멀쩡히 옵니다. 그래서 "서브에이전트는 원래 토큰을 안 흘리나 보다" 라고 결론 내리고 넘어가기 쉽습니다. 실제로는 30분짜리 실행의 90%가 자식 안에서 벌어지는데, 그 90%를 못 보고 있는 것입니다. Deep Agent 를 스트리밍할 때 `subgraphs: true` 는 선택이 아니라 기본입니다.

> ⚠️ **함정 (튜플 모양이 조용히 바뀐다)**: `subgraphs: true` 를 켜면 yield 되는 값의 **모양 자체가 바뀝니다.**
> - `subgraphs: false` → `chunk`
> - `subgraphs: true` → `[namespace, chunk]`
> - `streamMode` 가 배열 + `subgraphs: true` → `[namespace, mode, data]`
>
> JavaScript 는 이걸 안 잡아 줍니다. `for await (const chunk of ...)` 로 받아 놓고 `chunk.messages` 를 읽으면 `undefined` 가 나옵니다 — 실제로는 `chunk` 가 `[namespace, chunk]` 배열이니까요. TypeScript 를 쓰면 잡히지만, 옵션을 런타임 값으로 넘기는 순간 타입도 못 잡습니다.

---

## 11-2. streamMode 별 관찰 — Deep Agent 에서 무엇이 나오나

`streamMode` 는 "무엇을 흘려줄 것인가"를 고릅니다. 일반 에이전트에서도 같은 옵션이지만, Deep Agent 에서는 각 모드의 **양과 유용성**이 확 달라집니다.

### updates — 노드가 끝날 때마다 "바뀐 것"만

```ts
for await (const [ns, chunk] of await agent.stream(input, { streamMode: "updates", subgraphs: true })) {
  // chunk 의 키가 곧 "방금 실행된 노드 이름"입니다.
  console.log(`${JSON.stringify(ns).padEnd(48)} ${Object.keys(chunk).join(", ")}`);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[]                                               FilesystemMiddleware.before_agent
[]                                               patchToolCallsMiddleware.before_agent
[]                                               model_request
[]                                               todoListMiddleware.after_model
[]                                               tools
["tools:toolu_01A9k2Bx..."]                      FilesystemMiddleware.before_agent
["tools:toolu_01A9k2Bx..."]                      model_request
["tools:toolu_01A9k2Bx..."]                      tools
["tools:toolu_01A9k2Bx..."]                      model_request
[]                                               tools
[]                                               model_request
```

`chunk` 의 **키가 곧 노드 이름**이라는 것에 주목하세요. `FilesystemMiddleware.before_agent` 같은 미들웨어 훅까지 그대로 노드로 보입니다. Deep Agent 가 [Step 08 — 미들웨어 조합](../step-08-middleware/)에서 본 미들웨어 스택 위에 세워졌다는 게 스트림에 그대로 드러납니다.

이 모드는 **진행 로그**에 적합합니다. "지금 3단계 중 2단계" 같은 것을 만들 때.

### values — 매 스텝의 전체 state 스냅샷

```ts
let i = 0;
for await (const chunk of await agent.stream(input, { streamMode: "values" })) {
  const msgs = (chunk as { messages?: unknown[] }).messages ?? [];
  console.log(`스냅샷 #${++i}: messages ${msgs.length}개`);
}
```

**출력 예시**
```
스냅샷 #1: messages 1개
스냅샷 #2: messages 2개
스냅샷 #3: messages 4개
스냅샷 #4: messages 8개
...
스냅샷 #12: messages 31개
```

매번 **state 전체**가 옵니다. 메시지 31개짜리 상태를 12번 받으면, 같은 메시지를 수십 번 다시 받는 셈입니다. 대신 `todos` 나 `files` 처럼 "현재 전체 모습"이 필요한 것에는 이게 유일한 방법입니다 — 11-4, 11-5 에서 씁니다.

### messages — 토큰

```ts
for await (const [chunk, metadata] of await agent.stream(input, { streamMode: "messages" })) {
  const text = (chunk as { text?: string }).text;
  if (typeof text === "string") process.stdout.write(text);
}
```

`[chunk, metadata]` 튜플이 옵니다. `metadata` 에는 `langgraph_node`, `langgraph_step` 같은 필드가 있어서, 이 토큰이 어느 노드에서 나왔는지 알 수 있습니다.

### custom — 도구 안에서 내가 직접 쏘는 신호

도구 내부에서 `config.writer` 로 진행상황을 쏠 수 있습니다.

```ts
const slowTool = tool(
  async ({ query }, config) => {
    const writer = config.writer;
    writer?.({ status: "starting", progress: 0 });
    // …오래 걸리는 작업…
    writer?.({ status: "complete", progress: 100 });
    return "결과";
  },
  { name: "slow_tool", description: "…", schema: z.object({ query: z.string() }) },
);
```

이 신호는 `streamMode: "custom"` 에서 나옵니다. 도구 하나가 3분 걸리는 경우 — 대용량 파일 처리, 외부 API 폴링 — 이게 없으면 그 3분 동안 아무 이벤트도 안 나옵니다.

### 여러 모드 동시에

```ts
// 튜플이 [namespace, mode, data] 3칸으로 바뀝니다.
for await (const [ns, mode, _data] of await agent.stream(input, {
  streamMode: ["updates", "messages", "custom"],
  subgraphs: true,
})) {
  const key = `${mode} @ ${ns.length === 0 ? "(root)" : ns[0]}`;
  seen[key] = (seen[key] ?? 0) + 1;
}
console.table(seen);
```

**출력 예시**
```
┌────────────────────────────────────────┬────────┐
│ (index)                                │ Values │
├────────────────────────────────────────┼────────┤
│ 'updates @ (root)'                     │ 11     │
│ 'messages @ model_request:8f3c1e4a-…'  │ 214    │
│ 'updates @ tools:toolu_01A9k2Bx…'      │ 6      │
│ 'messages @ tools:toolu_01A9k2Bx…'     │ 187    │
└────────────────────────────────────────┴────────┘
```

정리하면 이렇습니다.

| streamMode | 나오는 것 | Deep Agent 에서의 용도 | 양 |
|---|---|---|---|
| `updates` | 노드가 바꾼 것만 | 진행 로그, 단계 표시 | 적음 |
| `values` | 매 스텝 state 전체 | `todos` / `files` 추적 | 많음 (중복) |
| `messages` | LLM 토큰 | 답변 타이핑 효과 | 아주 많음 |
| `custom` | 도구가 `config.writer` 로 쏜 것 | 오래 걸리는 도구의 진행률 | 내가 정함 |
| `debug` | 전부 | 디버깅 전용 | 압도적 |

> 💡 **실무 팁 — 이 표를 다 외울 필요는 없습니다**: 11-3 부터 소개할 `streamEvents(..., { version: "v3" })` 가 이 모드들을 **이미 파싱해서** `run.messages`, `run.toolCalls`, `run.subagents`, `run.values` 라는 프로젝션으로 나눠 줍니다. 즉 `namespace` 를 직접 문자열 파싱할 일이 없어집니다. `stream()` + `streamMode` 는 (1) LangGraph Platform 과 붙일 때, (2) v3 이 안 주는 것을 직접 파야 할 때, (3) 무슨 일이 벌어지는지 원본으로 확인하고 싶을 때 씁니다. 새 코드는 v3 부터 보세요.

---

## 11-3. 서브에이전트 스트리밍 — 자식을 부모와 구분해서 UI 에 보여주기

11-1 의 문제 — "자식이 안 보이거나, 보이면 뒤섞인다" — 를 정면으로 푸는 것이 `streamEvents` 의 v3 인터페이스입니다.

```ts
const run = await agent.streamEvents(input, { version: "v3" });
```

`version: "v3"` 를 **반드시** 줘야 합니다. 안 주면 LangGraph Platform 호환용 레거시 이벤트 스트림이 나옵니다(그건 `[namespace, chunk]` 파싱을 직접 해야 하는 저수준 스트림입니다).

`run` 이 돌려주는 프로젝션은 이렇습니다.

| 프로젝션 | 타입 | 내용 |
|---|---|---|
| `run.messages` | `AsyncIterable<ChatModelStreamHandle>` | **이 에이전트의** 메시지만. 자식 것은 안 섞임 |
| `run.toolCalls` | `AsyncIterable<ToolCallStream>` | 이 에이전트의 도구 호출 |
| `run.subagents` | `AsyncIterable<SubagentRunStream>` | 자식 위임. 각자 자기 `messages`/`toolCalls` 를 가짐 |
| `run.values` | `AsyncIterable<State>` **&** `PromiseLike<State>` | state 스냅샷 스트림 / 최종 state |
| `run.output` | `Promise<State>` | 최종 state |
| `run.subgraphs` | `AsyncIterable<SubgraphRunStream>` | 그래프 수준 자식(내부 노드 포함) |
| `run.extensions` | `TExtensions` | `streamTransformers` 가 얹은 것 (11-6) |
| `run.interrupted` / `run.interrupts` | `boolean` / `readonly InterruptPayload[]` | HITL 중단 여부 |
| `run.abort(reason?)` / `run.signal` | | 실행 취소 |

핵심은 이것입니다. **`run.messages` 에는 자식 토큰이 안 섞입니다.** 부모의 대화만 옵니다. 자식은 `run.subagents` 로 별도 핸들이 나오고, 각 핸들이 자기 `messages` 와 `toolCalls` 를 가집니다. `namespace` 파싱을 라이브러리가 대신 해 준 것입니다.

각 핸들(`SubagentRunStream`)의 모양:

| 필드 | 타입 | 내용 |
|---|---|---|
| `name` | `string` | 서브에이전트 이름 (`subagents: [...]` 에 준 그 이름) |
| `cause` | `LifecycleCause \| undefined` | 이 자식을 낳은 도구 호출 — `{ type: "toolCall", tool_call_id }` |
| `output` | `Promise<State>` | 자식의 최종 state |
| `messages` | `AsyncIterable<ChatModelStreamHandle>` | 자식의 메시지 |
| `toolCalls` | `AsyncIterable<ToolCallStream>` | 자식의 도구 호출 |
| `subagents` | `AsyncIterable<SubagentRunStream>` | 손자 (자식이 또 위임한 경우) |

이제 실제 코드입니다.

```ts
const run = await agent.streamEvents(
  { messages: [{ role: "user" as const, content: "'벡터 검색'과 '전문 검색'을 각각 조사한 뒤, 비교 보고서를 써줘." }] },
  { version: "v3", recursionLimit: 100 },
);

// 부모(오케스트레이터)의 메시지
const parent = (async () => {
  for await (const msg of run.messages) {
    let text = "";
    for await (const token of msg.text) text += token;
    if (text.trim()) console.log(`\n[부모] ${text.trim().slice(0, 200)}`);
  }
})();

// 자식(서브에이전트)
const children = (async () => {
  const pending: Promise<void>[] = [];
  for await (const sub of run.subagents) {
    console.log(`\n[자식 시작] ${sub.name} (cause=${JSON.stringify(sub.cause)})`);
    pending.push((async () => {
      for await (const call of sub.toolCalls) {
        console.log(`  [${sub.name}] 도구 ${call.name}(${JSON.stringify(call.input)})`);
        console.log(`  [${sub.name}] → ${await call.status}`);
      }
    })());
    pending.push((async () => {
      for await (const msg of sub.messages) {
        let text = "";
        for await (const token of msg.text) text += token;
        if (text.trim()) console.log(`  [${sub.name}] ${text.trim().slice(0, 120)}`);
      }
    })());
  }
  await Promise.all(pending);
})();

// 부모의 도구 호출 — task(서브에이전트 스폰)가 여기 보인다
const parentTools = (async () => {
  for await (const call of run.toolCalls) {
    console.log(`\n[부모] 도구 ${call.name} 시작`);
  }
})();

await Promise.all([parent, children, parentTools]);
const state = await run.output;
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[부모] 도구 write_todos 시작

[부모] 두 검색 방식을 각각 조사한 뒤 비교하겠습니다.

[부모] 도구 task 시작

[부모] 도구 task 시작

[자식 시작] researcher (cause={"type":"toolCall","tool_call_id":"toolu_01A9k2Bx7pQwErTyU3nMzXvC"})
  [researcher] 도구 search_docs({"query":"벡터 검색"})

[자식 시작] researcher (cause={"type":"toolCall","tool_call_id":"toolu_01Ff8dGh2jKlMnPqRsTuVwXy"})
  [researcher] 도구 search_docs({"query":"전문 검색"})
  [researcher] → finished
  [researcher] → finished
  [researcher] 벡터 검색은 텍스트를 임베딩 벡터로 변환해 의미적 유사도로 검색합니다…
  [researcher] 전문 검색은 역색인을 사용해 키워드 일치를 기반으로 문서를 찾습니다…

[자식 시작] writer (cause={"type":"toolCall","tool_call_id":"toolu_01Zx4cVb9nMkJhGfDsAqWeRt"})
  [writer] # 벡터 검색 vs 전문 검색 비교…

[부모] 두 검색 방식의 비교 보고서를 완성했습니다…

최종 messages: 14 개
```

`researcher` 두 개가 **거의 동시에** 시작한 게 보입니다. `cause` 의 `tool_call_id` 가 서로 달라서 같은 이름의 두 인스턴스를 구분할 수 있습니다. 이것이 UI 카드를 두 장 그릴 때의 키가 됩니다.

`ToolCallStream` 의 각 필드가 언제 확정되는지도 알아 둘 만합니다.

| 필드 | Promise 인가 | 언제 확정되나 |
|---|---|---|
| `name` | 아니오 | yield 되는 순간 |
| `callId` | 아니오 | yield 되는 순간 |
| `input` | 아니오 | yield 되는 순간 (인자 JSON 이 다 조립된 뒤에야 yield 되므로) |
| `output` | **예** | 도구가 반환할 때 |
| `status` | **예** | `"running"` 을 벗어날 때 (`"finished"` / `"error"`) |
| `error` | **예** | `status` 가 `"error"` 일 때 메시지 |

`input` 이 Promise 가 아닌 게 중요합니다. [LangChain Step 09](../../langchain/step-09-streaming/)에서 본 "도구 호출 청크는 조각난 부분 JSON 으로 온다" 는 함정을, v3 스트림이 **대신 조립해 준** 결과입니다. `content-block-finish` 로 완성된 `tool_call` 블록이 왔을 때만 yield 하기 때문입니다.

> ⚠️ **함정 (자식 스트림을 순차로 소비하면 병렬이 직렬이 된다)**: 이 스텝에서 가장 많이 틀리는 곳입니다.
> ```ts
> // ✗ 틀림
> for await (const sub of run.subagents) {
>   for await (const call of sub.toolCalls) { ... }   // ← 여기서 막힌다
> }
> ```
> 안쪽 `for await` 이 끝날 때까지 바깥 루프가 다음 서브에이전트를 못 받습니다. 서브에이전트 세 개가 **실제로는 병렬로 돌고 있는데** UI 에는 "하나 끝나야 다음이 나타나는" 것처럼 보입니다. 에러는 안 납니다. 결과도 맞습니다. 오직 **UI 만 거짓말**을 합니다. 정답은 자식 소비를 Promise 배열에 모아 두고 마지막에 `Promise.all` 하는 것입니다 (위 코드의 `pending`).

> ⚠️ **함정 (프로젝션을 안 읽으면 그 스트림이 굶는다)**: `run.subagents` 를 소비하지 않고 `await run.output` 만 하면 실행은 잘 끝납니다. 하지만 `run.messages` 를 소비하는 코드와 `run.subagents` 를 소비하는 코드가 **섞여 있을 때** 한쪽만 `await` 로 먼저 붙잡으면 다른 쪽이 진행을 못 합니다. 규칙: **소비할 프로젝션은 전부 동시에 열고, `Promise.all` 로 함께 기다린다. `run.output` 은 맨 마지막.**

> 💡 **실무 팁 — `run.subagents` vs `run.subgraphs`**: 둘 다 "자식"을 줍니다. 차이는 추상화 수준입니다. `run.subagents` 는 **제품 수준의 위임** — `task` 도구가 스폰한 이름 있는 에이전트만 나옵니다. 사용자에게 보여줄 UI 는 이걸 쓰세요. `run.subgraphs` 는 **그래프 실행 구조** — 에이전트 자신의 내부 노드까지 전부 나옵니다. 이건 디버깅용입니다. UI 에 `subgraphs` 를 쓰면 사용자에게 `model_request:8f3c1e4a` 같은 내부 노드 이름이 노출됩니다.

---

## 11-4. todo 리스트 실시간 표시 — 진행상황 UI

[Step 03 — 계획 도구](../step-03-planning-todos/)에서 본 `write_todos` 는 에이전트가 자기 계획을 state 에 적는 도구입니다. 이 계획은 `state.todos` 에 살아 있고, 에이전트가 진행하면서 계속 갱신합니다.

`todos` 의 shape 은 결정적입니다.

```ts
type Todo = { content: string; status: "pending" | "in_progress" | "completed" };
```

`status` 는 정확히 이 세 값입니다. 이걸 실시간으로 그리면, 30분 동안 스피너만 보던 사용자가 **"지금 5개 중 3개째"** 를 보게 됩니다. 체감 대기시간이 완전히 달라집니다.

todos 는 `run.values` 로 옵니다.

```ts
const run = await agent.streamEvents(input, { version: "v3", recursionLimit: 100 });

let lastRendered = "";
for await (const snapshot of run.values) {
  const todos = (snapshot as { todos?: Todo[] }).todos;
  if (!todos || todos.length === 0) continue;

  // 같은 내용을 반복해서 다시 그리지 않도록 지문을 비교합니다.
  const fingerprint = todos.map((t) => `${t.status}:${t.content}`).join("|");
  if (fingerprint === lastRendered) continue;
  lastRendered = fingerprint;

  const done = todos.filter((t) => t.status === "completed").length;
  console.log(`\n── 계획 (${done}/${todos.length}) ${progressBar(done, todos.length)}`);
  printTodos(todos);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
── 계획 (0/4) [░░░░░░░░░░░░░░░░░░░░] 0%
  ▶ 벡터 검색 조사 (in_progress)
  ☐ 전문 검색 조사 (pending)
  ☐ 하이브리드 검색 조사 (pending)
  ☐ 비교 보고서 작성 (pending)

── 계획 (1/4) [█████░░░░░░░░░░░░░░░] 25%
  ☑ 벡터 검색 조사 (completed)
  ▶ 전문 검색 조사 (in_progress)
  ☐ 하이브리드 검색 조사 (pending)
  ☐ 비교 보고서 작성 (pending)

── 계획 (3/4) [███████████████░░░░░] 75%
  ☑ 벡터 검색 조사 (completed)
  ☑ 전문 검색 조사 (completed)
  ☑ 하이브리드 검색 조사 (completed)
  ▶ 비교 보고서 작성 (in_progress)
```

**웹 프론트엔드**에서도 똑같습니다. `@langchain/react` 의 `useStream` 훅이 같은 state 를 노출합니다.

```tsx
import { useStream } from "@langchain/react";

function TodoPanel() {
  const stream = useStream<typeof myAgent>({
    apiUrl: "https://your-deployment.langsmith.dev",
    assistantId: "deep_agent_todo_list",
  });

  const todos = stream.values?.todos ?? [];
  if (todos.length === 0) return null;   // 빈 리스트는 아예 안 그린다

  const done = todos.filter((t) => t.status === "completed").length;
  return (
    <div>
      <ProgressBar percent={(done / todos.length) * 100} />
      {todos.map((t, i) => <TodoItem key={i} todo={t} />)}
    </div>
  );
}
```

공식 문서가 권하는 표시 규칙: `pending` 은 `○` (회색), `in_progress` 는 `◉` (앰버 + 펄스 애니메이션), `completed` 는 `✓` (초록 + 취소선). 그리고 **`todos.length > 0` 일 때만 렌더**하세요 — 에이전트가 간단한 요청이라 판단하면 `write_todos` 를 아예 안 부르고, 그때 빈 패널이 뜨면 "계획이 실패했나?" 하는 오해를 삽니다.

> ⚠️ **함정 (`run.values` 는 iterable 이면서 동시에 promise 다)**: `run.values` 는 `AsyncIterable<State> & PromiseLike<State>` 라는 이중 인터페이스입니다.
> ```ts
> const final = await run.values;              // 최종 state 하나. 중간은 못 본다.
> for await (const snap of run.values) { ... } // 중간 스냅샷 전부.
> ```
> 진행상황을 그리려고 했는데 `await run.values` 를 써 놓으면 — **에러가 안 납니다.** 타입도 맞습니다. 결과값도 맞습니다. 그냥 진행상황이 하나도 안 보이고 끝에 한 번만 그려집니다. 그리고 "왜 todos 가 안 뜨지" 하며 몇 시간을 태웁니다.

> 💡 **실무 팁 — 지문(fingerprint) 비교는 성능이 아니라 UX 문제입니다**: `values` 는 **매 스텝** 스냅샷을 보내는데, `todos` 는 `write_todos` 가 불릴 때만 바뀝니다. 그래서 같은 todo 리스트가 수십 번 옵니다. 터미널이면 같은 줄이 20번 반복되고, React 면 리렌더가 20번 돕니다. `todos` 를 문자열로 직렬화해 이전 값과 비교하는 한 줄이 이걸 막습니다. React 에서는 `useMemo` 의 의존성으로 같은 지문 문자열을 쓰면 됩니다.

---

## 11-5. 파일 변경 스트리밍 — 에이전트가 지금 뭘 쓰고 있나

[Step 04 — 가상 파일시스템](../step-04-filesystem/)에서 본 대로, Deep Agent 는 `write_file` / `edit_file` 로 결과물을 파일에 씁니다. 30분짜리 리서치의 산출물은 대개 **파일**입니다. 그러니 "지금 어느 파일을 쓰고 있는지"가 곧 진행상황입니다.

두 가지 관점이 있고, 둘 다 유용합니다.

### (A) 도구 호출 단위 — "지금 이 파일을 쓰는 중"

```ts
for await (const call of run.toolCalls) {
  if (call.name === "write_file" || call.name === "edit_file") {
    const path = (call.input as { file_path?: string }).file_path ?? "?";
    console.log(`✎ ${call.name} → ${path} (쓰는 중…)`);
    const status = await call.status;
    console.log(`  ${status === "finished" ? "✔" : "✖"} ${path} (${status})`);
  }
}
```

`call.input` 이 이미 확정값이므로 **파일 경로를 즉시** 알 수 있습니다. 파일 내용이 다 만들어지기 전에도 "아, `/notes/vector.md` 를 쓰려는구나" 를 화면에 띄울 수 있다는 뜻입니다.

### (B) state 스냅샷 단위 — "파일 목록이 이렇게 변했다"

```ts
let known = new Set<string>();
for await (const snapshot of run.values) {
  const files = (snapshot as { files?: Record<string, unknown> }).files ?? {};
  const now = new Set(Object.keys(files));
  for (const p of now) if (!known.has(p)) console.log(`  + 새 파일: ${p}`);
  for (const p of known) if (!now.has(p)) console.log(`  - 삭제됨: ${p}`);
  known = now;
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
✎ write_file → /notes/vector.md (쓰는 중…)
✎ write_file → /notes/keyword.md (쓰는 중…)
  + 새 파일: /notes/vector.md
  ✔ /notes/vector.md (finished)
  + 새 파일: /notes/keyword.md
  ✔ /notes/keyword.md (finished)
✎ write_file → /notes/README.md (쓰는 중…)
  + 새 파일: /notes/README.md
  ✔ /notes/README.md (finished)

최종 파일:
  /notes/README.md 84자
    │ # 노트 목록
    │ - vector.md — 벡터 검색 소개
    │ - keyword.md — 키워드 검색 소개
  /notes/keyword.md 213자
  /notes/vector.md 198자
```

(A)가 먼저 뜨고 (B)가 뒤따르는 게 보입니다. (A)는 **의도**를, (B)는 **결과**를 보여줍니다. IDE 같은 UI 를 만든다면 (A)로 파일 탭을 미리 열고, (B)로 파일 트리를 갱신하면 됩니다.

> ⚠️ **함정 (`await call.status` 를 루프 안에 그냥 두면 병렬 쓰기를 놓친다)**: 위 (A) 코드는 설명용으로 단순하게 썼지만, 실제로는 11-3 과 같은 문제가 있습니다. `await call.status` 가 바깥 `for await` 을 막아서, 병렬로 뜬 다음 `write_file` 을 늦게 받습니다. 파일 세 개를 동시에 쓰는 에이전트인데 UI 에는 하나씩 순서대로 뜹니다. `solution.ts` 의 `[정답 4]` 에 제대로 된 버전이 있습니다 — `await` 을 Promise 로 떼어냅니다.

> 💡 **실무 팁 — 파일 내용 전체를 스트리밍하고 싶다면**: `write_file` 의 `content` 인자는 도구 호출 인자이므로, `call.input.content` 로 **한 번에** 나옵니다(조각으로 안 옵니다). 진짜 "타이핑되는 것처럼" 보여주고 싶다면 그건 모델의 토큰 스트림 쪽입니다 — `run.messages` 의 `msg.toolCalls` 를 쓰거나, 그냥 `call.input.content` 를 받아서 프론트엔드에서 타이핑 애니메이션을 흉내내는 게 훨씬 간단하고 안정적입니다. 실무에서는 대부분 후자를 씁니다.

---

## 11-6. `streamTransformers` 옵션

`run.messages` / `run.toolCalls` / `run.subagents` 는 라이브러리가 미리 만들어 둔 프로젝션입니다. 그런데 **내가 원하는 프로젝션**이 없다면? 예를 들어 "서브에이전트별 토큰 사용량" 같은 것.

`createDeepAgent` 의 `streamTransformers` 옵션이 그 자리입니다.

```ts
createDeepAgent({
  model,
  streamTransformers: [createUsageTracker()],   // () => StreamTransformer<P> 의 배열
});
```

`StreamTransformer` 의 계약은 이렇습니다.

| 메서드 | 필수 | 역할 |
|---|---|---|
| `init(): TProjection` | **예** | 반환값이 `run.extensions` 에 병합된다 |
| `process(event: ProtocolEvent): boolean` | **예** | 모든 이벤트를 본다. `false` 를 리턴하면 그 이벤트가 메인 로그에서 **사라진다** |
| `onRegister?(emitter)` | 아니오 | 합성 이벤트를 직접 쏠 때만 |
| `finalize?()` | 아니오 | 성공 종료 시 정리 |
| `fail?(err)` | 아니오 | 실패 시 정리 |

그리고 `process` 가 받는 `ProtocolEvent` 의 모양은 결정적입니다.

```ts
interface ProtocolEvent {
  readonly type: "event";
  readonly seq: number;                  // 순서 보장용 단조 증가 번호
  readonly method: ProtocolMethod;       // "messages" | "updates" | "values" | "tasks" | "checkpoints" | "lifecycle" | "tools" | ...
  readonly params: {
    readonly namespace: string[];        // 11-1 에서 본 그 namespace
    readonly timestamp: number;
    readonly node?: string;
    readonly data: unknown;              // method 에 따라 모양이 다름
  };
}
```

토큰 사용량 추적기를 만들어 봅시다.

```ts
import { StreamChannel } from "@langchain/langgraph";
import type { ProtocolEvent, StreamTransformer } from "@langchain/langgraph";

type UsageEntry = { who: "main" | "subagent"; namespace: string[]; inputTokens: number; outputTokens: number };

function createUsageTracker() {
  return (): StreamTransformer<{ usageLog: StreamChannel<UsageEntry> }> => {
    const channel = StreamChannel.local<UsageEntry>();
    let input = 0;
    let output = 0;

    return {
      init: () => ({ usageLog: channel }),
      process: (event: ProtocolEvent) => {
        if (event.method !== "messages") return true;
        const data = event.params.data as { event?: string; usage?: { input_tokens?: number; output_tokens?: number } };
        if (data?.event !== "message-finish" || !data.usage) return true;

        const ns = event.params.namespace;
        const isSub = ns.some((s) => s.startsWith("tools:"));   // 11-1 의 그 규칙
        input += data.usage.input_tokens ?? 0;
        output += data.usage.output_tokens ?? 0;
        channel.push({
          who: isSub ? "subagent" : "main",
          namespace: ns,
          inputTokens: data.usage.input_tokens ?? 0,
          outputTokens: data.usage.output_tokens ?? 0,
        });
        return true;     // ← 항상 true
      },
      finalize: () => {
        console.log(`\n[usageTracker] 누적 input=${input} output=${output}`);
      },
    };
  };
}
```

소비하는 쪽:

```ts
const agent = await createDeepAgent({
  model: MODEL,
  subagents: [{ name: "researcher", description: "…", model: CHEAP_MODEL }] as const,
  streamTransformers: [createUsageTracker()],
});

const run = await agent.streamEvents(input, { version: "v3", recursionLimit: 100 });

// extensions 는 타입까지 추론됩니다 — usageLog 가 StreamChannel<UsageEntry> 로 잡힙니다.
const watch = (async () => {
  for await (const e of run.extensions.usageLog) {
    console.log(`${e.who === "main" ? "[본체]" : "[서브]"} in=${e.inputTokens} out=${e.outputTokens}`);
  }
})();

await run.output;
await watch;
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[본체]  in=3812 out=94 ns=["model_request:8f3c1e4a-..."]
[서브]  in=2104 out=187 ns=["tools:toolu_01A9k2Bx...","model_request:c1d9..."]
[서브]  in=2455 out=132 ns=["tools:toolu_01A9k2Bx...","model_request:e7a2..."]
[본체]  in=4390 out=241 ns=["model_request:1b6f..."]

[usageTracker] 누적 input=12761 output=654
```

여기서 중요한 사실이 하나 드러납니다. **입력 토큰이 출력 토큰의 20배입니다.** 서브에이전트마다 시스템 프롬프트 전체(11-1 의 probe 에서 봤듯 Deep Agent 기본 프롬프트만 2천 토큰이 넘습니다)를 매번 다시 보내기 때문입니다. 11-9 의 비용 이야기가 여기서 시작됩니다.

`StreamChannel` 에는 두 종류가 있습니다.

| 생성 | 범위 | 원격 클라이언트 |
|---|---|---|
| `StreamChannel.local<T>()` | in-process 전용 | 안 보임 |
| `StreamChannel.remote<T>("이름")` | in-process + 원격 | `custom:<이름>` 채널로 자동 전달 |

웹 프론트엔드에서도 이 데이터를 보려면 `remote` 를 쓰고, 클라이언트에서 `session.subscribe("custom:<이름>")` 로 받습니다. 그리고 `channel.toEventStream()` 이 SSE `ReadableStream` 을 바로 만들어 주므로 `new Response(channel.toEventStream())` 한 줄로 라우트를 만들 수 있습니다.

> ⚠️ **함정 (`process` 가 `false` 를 리턴하면 다른 프로젝션이 굶는다)**: `process` 의 반환값은 "이 이벤트를 메인 로그에 남길까?" 입니다. `false` 를 돌려주면 그 이벤트는 **사라집니다**. 내 트랜스포머가 "messages 이벤트는 내가 처리했으니 `false`" 라고 하는 순간, 같은 런의 `run.messages` / `run.subagents` 가 아무것도 못 받습니다. 에러는 안 납니다 — 그냥 스트림이 조용히 비어 있습니다. **웬만하면 항상 `true` 를 리턴하세요.** 필터링은 소비하는 쪽에서 하세요.

> ⚠️ **함정 (`extensions` 채널을 안 읽으면 `output` 이 안 끝난 것처럼 보인다)**: `run.extensions.usageLog` 를 소비하는 Promise 를 만들어 놓고 `await` 하지 않은 채 프로세스가 끝나면, 이벤트를 다 못 받습니다. 반대로 `await watch` 를 `await run.output` **앞**에 두면 채널이 아직 안 닫혀서 영원히 기다립니다(채널은 런이 끝날 때 mux 가 닫습니다). 순서가 중요합니다: **`await run.output` 먼저, `await watch` 나중.**

> 💡 **실무 팁 — 언제 `streamTransformers` 를 쓰나**: 대부분은 안 씁니다. `run.subagents` / `run.toolCalls` / `run.values` 로 UI 는 다 만들어집니다. `streamTransformers` 가 필요한 경우는 (1) **관측** — 토큰/지연을 Datadog·LangSmith 로 흘려보낼 때, (2) **감사 로그** — 모든 이벤트를 `seq` 순서대로 DB 에 적을 때, (3) **프로토콜 확장** — 커스텀 프론트엔드에 내 도메인 이벤트를 보낼 때입니다. `init()` 만 있고 `process()` 에서 `true` 만 리턴해도 되는, 순수 관측용 트랜스포머가 실무에서 제일 흔합니다.

---

## 11-7. 내결함성 — 긴 실행이 중간에 죽으면?

30분짜리 실행이 25분째에 죽습니다. 배포로 파드가 재시작됐거나, 모델 API 가 503 을 뱉었거나, 사용자가 브라우저를 닫았습니다. 25분치 작업(과 토큰 비용)이 날아가면 안 됩니다.

답은 [Step 09 — HITL과 권한 제어](../step-09-hitl-permissions/)에서 이미 절반 본 **체크포인터**입니다. LangGraph 는 매 스텝마다 state 스냅샷을 저장하고, 같은 `thread_id` 로 다시 부르면 **멈춘 자리부터** 이어갑니다.

세 가지가 다 맞아야 합니다.

### (1) 체크포인터가 있어야 한다

```ts
import { MemorySaver } from "@langchain/langgraph";

const agent = await createDeepAgent({
  model: MODEL,
  tools: [searchDocs],
  checkpointer: new MemorySaver(),
});
```

### (2) `durability` 를 골라야 한다

체크포인트를 **언제** 쓸 것인가입니다. `invoke`/`stream`/`streamEvents` 의 config 에 줍니다.

| `durability` | 동작 | 급사하면 |
|---|---|---|
| `"async"` (기본) | 다음 스텝과 **동시에** 저장 | 마지막 1스텝을 잃을 수 있음 |
| `"sync"` | 다음 스텝 **시작 전에** 저장 | 안 잃음 (대신 조금 느림) |
| `"exit"` | 그래프가 **끝날 때만** 저장 | **전부 잃음** |

### (3) 재개할 때 첫 인자를 `null` 로 준다

```ts
const config = { configurable: { thread_id: threadId } };
const controller = new AbortController();
setTimeout(() => controller.abort(new Error("강제 중단(배포로 인한 인스턴스 종료 상황)")), 2500);

try {
  await agent.invoke(
    { messages: [{ role: "user" as const, content: "'HNSW 인덱스'를 조사해서 자세히 설명해줘." }] },
    { ...config, signal: controller.signal, durability: "sync", recursionLimit: 100 },
  );
} catch (err) {
  console.log(`✖ 실행이 끊겼습니다: ${(err as Error).message}`);
}

// 체크포인트가 남아 있는지 확인
const snapshot = await agent.getState(config);
console.log(`체크포인트에 남은 messages: ${snapshot.values.messages?.length ?? 0}개`);
console.log(`다음에 실행할 노드(next): ${JSON.stringify(snapshot.next)}`);

// 재개 — 첫 인자가 null 인 것이 핵심
if (snapshot.next.length > 0) {
  console.log("↻ 같은 thread_id 로 재개합니다…");
  const resumed = await agent.invoke(null, { ...config, recursionLimit: 100 });
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
✖ 실행이 끊겼습니다: 강제 중단(배포로 인한 인스턴스 종료 상황)
체크포인트에 남은 messages: 4개
다음에 실행할 노드(next): ["tools"]

↻ 같은 thread_id 로 재개합니다…
[ 0] ai     → 도구 호출: search_docs
[ 1] tool   ← search_docs: "HNSW 인덱스" 검색 결과 3건: - HNSW 인덱스의 정의와 배경…
[ 2] ai     HNSW(Hierarchical Navigable Small World)는 근사 최근접 이웃 검색을 위한…
```

`next: ["tools"]` 가 핵심입니다. "다음에 `tools` 노드를 실행할 차례였다" 는 뜻이고, 재개하면 정확히 거기서부터 갑니다. 이미 끝난 `search_docs` 호출을 다시 하지 않습니다.

**`invoke(null, config)` 와 `invoke({messages: [...]}, config)` 의 차이**는 반드시 알아야 합니다.

| 첫 인자 | 의미 |
|---|---|
| `null` | **하던 일 계속**. 멈춘 노드부터 재개 |
| `{ messages: [...] }` | **새 사용자 턴**. 기존 대화에 이어서 새 요청 |

### 다른 종류의 실패에는 다른 처방

죽는 이유가 다 다르므로, 처방도 다릅니다.

| 실패 종류 | 예 | 처방 |
|---|---|---|
| 일시적(transient) | 네트워크 타임아웃, 429 | `modelRetryMiddleware` / `toolRetryMiddleware` — 지수 백오프 |
| LLM 이 고칠 수 있는 것 | 도구 인자 오류, 파싱 실패 | 에러를 `ToolMessage` 로 돌려줘서 모델이 다시 시도 |
| 사용자가 고쳐야 하는 것 | 정보 부족 | `interruptOn` 으로 중단 → 사람에게 물음 (Step 09) |
| 프로바이더 장애 | Anthropic 500 | `modelFallbackMiddleware("openai:gpt-5.5")` |
| 폭주 | 무한 루프, 예산 초과 | `modelCallLimitMiddleware` / `toolCallLimitMiddleware` (11-9) |
| 인프라 사망 | 파드 재시작 | **영속 체크포인터 + 재개** (이 절) |

```ts
middleware: [
  modelRetryMiddleware({
    maxRetries: 3,
    backoffFactor: 2,        // 1초 → 2초 → 4초
    initialDelayMs: 1000,
    maxDelayMs: 20000,
    jitter: true,            // 재시도가 동시에 몰리는 것(thundering herd) 방지
  }),
  toolRetryMiddleware({
    tools: ["search_docs"],  // 이 도구만
    maxRetries: 2,
    initialDelayMs: 500,
    onFailure: "return_message",   // 실패해도 모델에게 알려주고 계속 진행
  }),
]
```

`toolRetryMiddleware` 의 `onFailure` 는 `"continue" | "error" | "raise" | "return_message" | (err) => string` 중 하나입니다. `"return_message"` 가 실무 기본값입니다 — 도구 하나 실패했다고 30분짜리 실행을 통째로 죽이는 것보다, 모델에게 "검색이 실패했다" 고 알려주고 다른 방법을 찾게 하는 게 낫습니다.

> ⚠️ **함정 (`MemorySaver` 는 프로세스 메모리다)**: 이름이 "Saver" 라서 뭔가 저장할 것 같지만, `MemorySaver` 는 **그냥 JS 객체**입니다. 프로세스가 재시작되면 전부 사라집니다. 데모에서는 잘 돌아갑니다. 로컬에서도 잘 돌아갑니다. 그리고 **프로덕션에 배포한 다음 날 첫 배포 때** 진행 중이던 모든 대화가 증발합니다. 게다가 인스턴스가 두 대면, 사용자의 두 번째 요청이 다른 인스턴스로 가서 "대화 기록이 없는" 상태가 됩니다 — 로드밸런서가 라운드로빈이니까요. **긴 실행 + MemorySaver = 재시작 시 전부 소실.** 프로덕션에서는 Postgres/Redis 기반 영속 체크포인터를 쓰거나, LangSmith Deployments 를 쓰세요(영속 체크포인터가 자동으로 설정됩니다).

> ⚠️ **함정 (`durability: "exit"` 는 재개를 무력화한다)**: `"exit"` 는 "그래프가 끝날 때만 저장" 입니다. 성능이 제일 좋습니다. 그리고 **중간에 죽으면 아무것도 안 남습니다** — 재개할 체크포인트가 없습니다. 체크포인터를 붙여 놨는데도요. 짧은 실행이면 괜찮지만, 30분짜리 Deep Agent 에 `"exit"` 를 쓰는 것은 체크포인터를 안 쓰는 것과 같습니다.

> 💡 **실무 팁 — `durability` 는 "sync" 로 시작하세요**: 기본값 `"async"` 는 대부분 괜찮습니다. 하지만 "괜찮다"의 의미가 "급사하면 마지막 1스텝을 잃는다" 입니다. Deep Agent 의 1스텝은 서브에이전트 전체 실행일 수 있고, 그건 수만 토큰입니다. 체크포인트 쓰기 비용(수 ms)과 서브에이전트 재실행 비용(수십 초 + 수만 토큰)을 비교하면 `"sync"` 가 압도적으로 쌉니다. 성능 프로파일링에서 체크포인트 쓰기가 병목으로 잡히면 그때 `"async"` 로 내리세요.

---

## 11-8. 프로덕션 체크리스트

### 모든 invoke 에 반드시 있어야 하는 두 가지

```ts
const result = await agent.invoke(
  { messages: [{ role: "user", content: "…" }] },
  {
    configurable: { thread_id: crypto.randomUUID() },   // ① 대화 식별자
    context: { userId: "user-123" },                    // ② 런 단위 데이터
  },
);
```

**① `thread_id`** — 체크포인터가 이걸 키로 대화를 저장/재개합니다. 없으면 체크포인터를 붙여 놔도 아무것도 안 남습니다.

**② `context`** — 도구와 미들웨어가 `runtime.context` 로 읽는 런 단위 데이터입니다. 사용자 ID, API 키, 피처 플래그, 세션 메타데이터. `contextSchema` 로 모양을 정의합니다([Step 05 — 백엔드와 권한](../step-05-backends/) 참고).

```ts
const agent = await createDeepAgent({
  model: MODEL,
  contextSchema: z.object({ userId: z.string() }),
});
```

### 지연(latency) — Deep Agent 는 느리다, 그게 정상이다

| 구간 | 전형적 소요 |
|---|---|
| 계획 수립 (`write_todos`) | 5~20초 |
| 서브에이전트 1개 | 30초~3분 |
| 최종 종합 | 20초~1분 |
| **전체** | **3~30분** |

관측 코드로 직접 재 봅시다.

```ts
type Span = { name: string; startMs: number; endMs?: number; toolCalls: number };
const spans: Span[] = [];
const t0 = Date.now();

const pending: Promise<void>[] = [];
for await (const sub of run.subagents) {
  const span: Span = { name: sub.name, startMs: Date.now() - t0, toolCalls: 0 };
  spans.push(span);
  pending.push((async () => { for await (const _c of sub.toolCalls) span.toolCalls++; })());
  pending.push((async () => { await sub.output; span.endMs = Date.now() - t0; })());
}
await Promise.all(pending);
await run.output;
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
총 소요: 28431ms
┌─────────┬──────────────┬────────┬─────────┬─────────┬──────────┐
│ (index) │ 서브에이전트 │ 시작   │ 종료    │ 소요    │ 도구호출 │
├─────────┼──────────────┼────────┼─────────┼─────────┼──────────┤
│ 0       │ 'researcher' │ '6210ms' │ '18904ms' │ '12694ms' │ 1      │
│ 1       │ 'researcher' │ '6284ms' │ '19122ms' │ '12838ms' │ 1      │
└─────────┴──────────────┴────────┴─────────┴─────────┴──────────┘

서브에이전트 구간의 합이 총 소요보다 크면 병렬로 돈 것이고,
작으면 오케스트레이터가 순차로 기다린 것입니다.
```

두 `researcher` 가 6.2초에 **거의 동시에** 시작해 19초에 끝났습니다. 구간 합(25.5초) > 총 소요(28.4초)는 아니지만, 두 구간이 겹쳐 있으니 병렬입니다. 만약 하나가 끝난 뒤 다음이 시작했다면 오케스트레이터 프롬프트가 병렬 위임을 유도하지 못한 것이니 [Step 07 — 시스템 프롬프트 설계](../step-07-prompting/)로 돌아가야 합니다.

### 타임아웃 — 짧게 잡으면 계획 단계에서 죽는다

일반 API 감각으로 30초 타임아웃을 걸면 Deep Agent 는 **계획도 못 세우고** 죽습니다. 그리고 로그에는 그냥 타임아웃만 찍혀서, "모델이 응답을 안 한다" 로 오진합니다.

| 레이어 | 흔한 기본값 | Deep Agent 에 필요한 값 |
|---|---|---|
| ALB / nginx | 60초 | 실행 시간 이상 (또는 스트리밍으로 우회) |
| API Gateway | 29초 (하드 리밋) | **우회 필수** — 백그라운드 잡 + 폴링 |
| Lambda | 15분 (하드 리밋) | 30분 실행은 불가 |
| `recursionLimit` | **25** | **100 ~ 10,000** |

`recursionLimit` 이 진짜 함정입니다. 다음 절에서 자세히 봅니다.

### 관측(observability)

LangSmith 를 붙이면 트레이스가 자동으로 올라갑니다.

```bash
LANGSMITH_TRACING=true
LANGSMITH_API_KEY=lsv2_...
LANGSMITH_PROJECT=my-deep-agent
```

Deep Agent 트레이스에서 봐야 할 것은 일반 에이전트와 다릅니다.

1. **서브에이전트별 토큰** — 어느 자식이 예산을 태우는가
2. **`task` 호출 횟수** — 오케스트레이터가 과도하게 위임하고 있지 않은가
3. **서브에이전트 실행 시간 분포** — 병렬인가 직렬인가
4. **재시도 횟수** — 프로바이더가 불안정한가
5. **`recursionLimit` 도달** — 계획이 발산하고 있는가

> ⚠️ **함정 (타임아웃을 짧게 잡으면 계획 단계에서 죽는다)**: 이 함정이 고약한 건 **에러 메시지가 원인을 안 알려주기** 때문입니다. `recursionLimit: 25` (기본값)로 Deep Agent 를 돌리면 이런 게 뜹니다.
> ```
> GraphRecursionError: Recursion limit of 25 reached without hitting a stop condition.
> You can increase the limit by setting the "recursionLimit" config key.
> ```
> 로그만 보면 "에이전트가 무한 루프에 빠졌다" 로 읽힙니다. 실제로는 **정상 동작 중인데 예산이 모자란 것**입니다. `recursionLimit` 은 "그래프 슈퍼스텝의 최대 횟수"인데, Deep Agent 는 미들웨어 훅 → 계획 → 모델 호출 → `task` 스폰 → 서브에이전트의 전체 루프 → 회수 → todo 갱신 → 다음 서브에이전트 → … 를 **전부** 슈퍼스텝으로 소진합니다. 서브에이전트 하나가 도는 동안에도 부모의 예산이 깎입니다. 25는 "계획 세우고 첫 서브에이전트 부르다가" 끝나는 수준입니다. LangGraph Platform 이 Deep Agent 용 기본값으로 **10,000** 을 쓰는 이유입니다. 로컬에서도 최소 100 부터 시작하세요.

> 💡 **실무 팁 — HTTP 요청 안에서 Deep Agent 를 끝내려 하지 마세요**: 30분짜리 실행을 하나의 HTTP 요청으로 처리하려면 모든 중간 레이어(ALB, nginx, CDN, 브라우저)의 타임아웃을 다 늘려야 하고, 그러면 다른 API 까지 위험해집니다. 정석은 둘 중 하나입니다. (1) **SSE/WebSocket 으로 스트리밍** — 연결이 살아 있으므로 idle timeout 에 안 걸립니다. `StreamChannel.toEventStream()` 이 이걸 위해 있습니다. (2) **백그라운드 잡 + `thread_id` 폴링** — invoke 를 큐에 넣고 즉시 `thread_id` 를 반환한 뒤, 클라이언트가 `agent.getState({ configurable: { thread_id } })` 로 진행상황을 폴링합니다. 체크포인터가 있으면 (2)는 공짜로 됩니다.

---

## 11-9. 비용 통제 — 서브에이전트가 토큰을 곱한다

이 절이 이 스텝에서 가장 돈이 되는 부분입니다.

### 왜 곱해지나

일반 에이전트는 대화 하나에 컨텍스트 하나입니다. Deep Agent 는 **서브에이전트 하나당 별도 컨텍스트**입니다. 그리고 각 컨텍스트는 자기 시스템 프롬프트를 **매 모델 호출마다** 다시 보냅니다.

Deep Agent 의 기본 시스템 프롬프트는 (실제로 찍어 보면) `write_todos` 안내, 파일시스템 도구 6종 안내, `task` 도구 안내를 합쳐 **2,000 토큰이 넘습니다.** 여기에 여러분의 `systemPrompt` 가 앞에 붙습니다.

서브에이전트 3개가 각각 도구를 4번씩 부르는 리서치 하나를 계산해 봅시다.

| 주체 | 모델 호출 | 호출당 입력 토큰 (추정) | 소계 |
|---|---|---|---|
| 오케스트레이터 | 6회 | ~4,000 (프롬프트 + 누적 대화 + task 결과) | 24,000 |
| researcher #1 | 5회 | ~2,500 (프롬프트 + 검색 결과) | 12,500 |
| researcher #2 | 5회 | ~2,500 | 12,500 |
| writer | 3회 | ~5,000 (재료가 다 들어감) | 15,000 |
| **합계** | **19회** | | **~64,000 입력 토큰** |

같은 일을 단일 에이전트로 하면 대략 20,000~25,000 토큰입니다. **Deep Agent 는 2~3배**입니다. 그게 컨텍스트 격리의 값입니다 — 격리는 공짜가 아닙니다.

> ⚠️ **함정 (Deep Agent 는 토큰을 훨씬 많이 쓴다)**: Step 06 에서 "서브에이전트는 부모 컨텍스트를 오염시키지 않는다" 를 장점으로 배웠습니다. 맞습니다. 그런데 그 문장의 뒷면이 **"서브에이전트는 자기 컨텍스트를 처음부터 다시 쌓는다"** 입니다. 부모가 이미 읽은 파일을 자식이 또 읽고, 부모의 시스템 프롬프트와 별개로 자식의 시스템 프롬프트가 또 실립니다. 단일 에이전트에서 Deep Agent 로 옮기면서 청구서가 3배가 되는데, 코드에는 아무 경고도 없습니다. 아래 세 가지로 방어하세요.

### 방어 1 — 모델 티어링

오케스트레이터는 **판단**을 합니다. 계획을 세우고, 누구에게 뭘 시킬지 정하고, 결과를 종합합니다. 여기는 좋은 모델이 필요합니다.

서브에이전트는 대개 **단순 작업**을 합니다. 검색하고 요약합니다. 여기는 싼 모델로 충분합니다. 그리고 호출 횟수는 서브에이전트가 압도적으로 많습니다.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",       // 오케스트레이터: 비싼 모델
  tools: [searchDocs],
  subagents: [
    {
      name: "researcher",
      description: "단순 조사. 요약만 잘하면 됩니다.",
      systemPrompt: "search_docs 로 조사하고 2문장으로 요약하세요.",
      tools: [searchDocs],
      model: "anthropic:claude-haiku-4-5",    // 서브에이전트: 싼 모델
    },
  ] as const,
  systemPrompt: "조사는 researcher 서브에이전트에게 위임하세요.",
});
```

`SubAgent` 의 `model` 필드가 이걸 위해 있습니다. OpenAI 를 섞어도 됩니다 — 오케스트레이터는 `"anthropic:claude-sonnet-4-6"`, 서브에이전트는 `"openai:gpt-5.5-mini"` 처럼. 같은 런 안에서 프로바이더가 달라도 상관없습니다.

위 표에서 researcher 두 개(25,000 토큰)를 Haiku 로 내리면, 그 부분의 비용이 대략 1/3 이 됩니다. 전체로는 30~40% 절감입니다. 코드 두 줄로요.

### 방어 2 — 호출 상한

```ts
middleware: [
  // 실행 1회당 모델 호출 20번, 스레드 전체로는 60번까지
  modelCallLimitMiddleware({ runLimit: 20, threadLimit: 60, exitBehavior: "end" }),
  // search_docs 는 실행 1회당 8번까지만
  toolCallLimitMiddleware({ toolName: "search_docs", runLimit: 8, exitBehavior: "continue" }),
]
```

`runLimit` 은 **한 번의 invoke**, `threadLimit` 은 **그 스레드의 전체 대화**(체크포인터가 있어야 의미 있음)입니다.

`exitBehavior` 가 미들웨어마다 다른 것에 주의하세요.

| 미들웨어 | `exitBehavior` 값 | 기본값 |
|---|---|---|
| `modelCallLimitMiddleware` | `"error"` \| `"end"` | `"end"` |
| `toolCallLimitMiddleware` | `"continue"` \| `"error"` \| `"end"` | `"continue"` |

- `"end"` — 조용히 종료. 지금까지의 결과는 남습니다.
- `"error"` — 예외를 던집니다 (`ToolCallLimitExceededError`).
- `"continue"` — 초과한 도구만 에러 메시지로 막고, 다른 도구와 모델은 계속 돕니다.

### 방어 3 — 토큰 예산 미들웨어

`modelCallLimitMiddleware` 는 **횟수**를 셉니다. **토큰**은 안 셉니다. 서브에이전트가 한 번 호출로 5만 토큰짜리 컨텍스트를 태우면 횟수 제한은 아무것도 못 막습니다.

토큰 기준 상한이 필요하면 직접 만듭니다([Step 08 — 미들웨어 조합](../step-08-middleware/)의 `wrapModelCall` 훅).

```ts
function createTokenBudgetMiddleware(maxTokens: number) {
  let used = 0;
  return {
    name: "TokenBudgetMiddleware",
    wrapModelCall: async (request, handler) => {
      if (used >= maxTokens) {
        throw new Error(`토큰 예산 초과: ${used}/${maxTokens} — 실행을 중단합니다.`);
      }
      const response = await handler(request);
      for (const m of response.result ?? []) {
        used += m.usage_metadata?.total_tokens ?? 0;
      }
      console.log(`  [예산] ${used}/${maxTokens} 토큰 사용`);
      return response;
    },
  };
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
  [예산] 2841/3000 토큰 사용
✖ 토큰 예산 초과: 2841/3000 — 실행을 중단합니다.
```

### 방어 4 — 요약 미들웨어

`createSummarizationMiddleware`(from `"deepagents"`)는 대화가 길어지면 오래된 메시지를 요약으로 압축합니다. Deep Agent 는 오케스트레이터 대화가 계속 길어지므로(모든 `task` 결과가 쌓입니다) 효과가 큽니다. 자세한 건 [Step 08](../step-08-middleware/) 참고.

> 💡 **실무 팁 — 비용을 재기 전에는 최적화하지 마세요**: 11-6 의 `createUsageTracker` 를 붙여서 **먼저 재세요.** 대부분의 경우 놀라운 사실이 나옵니다: 비용의 70%가 `input_tokens` 이고, 그중 대부분이 매번 다시 보내는 시스템 프롬프트와 파일 내용입니다. 그러면 답은 "모델을 바꾸자" 가 아니라 "프롬프트 캐싱을 켜자"(`anthropicPromptCachingMiddleware`) 또는 "서브에이전트에게 파일 전체 대신 요약만 주자" 가 됩니다. 재지 않고 최적화하면 엉뚱한 곳을 고칩니다.

---

## 11-10. 배포

### 세 가지 선택지

| 방식 | 무엇을 해 주나 | 언제 |
|---|---|---|
| **Managed Deep Agents** (private preview) | CLI 로 배포. 인프라 전부 자동 | 가장 빠른 길 |
| **LangSmith Deployments** | 영속 체크포인터, 인증, 웹훅, cron, 관측 | 커스텀 라우트/인증이 필요할 때 |
| **직접 서버** | 아무것도. 다 직접 | 기존 인프라에 넣어야 할 때 |

LangSmith Deployments 는 `langgraph.json` 을 프로젝트 루트에 둡니다.

```json
{
  "dependencies": ["."],
  "graphs": {
    "agent": "./src/agent.ts:agent"
  },
  "env": ".env"
}
```

| 필드 | 의미 |
|---|---|
| `dependencies` | 설치할 패키지. `["."]` 는 현재 디렉터리 |
| `graphs` | 그래프 ID → 코드 위치. `"<id>": "./<파일>:<변수>"` |
| `env` | 시크릿이 든 `.env` 경로 (빌드 시점에 설정됨) |

직접 서버로 간다면 자바스크립트 프레임워크(Next.js, SvelteKit, Nuxt, Vite)나 Cloudflare Workers, Deno 위에 올릴 수 있습니다. 이때 여러분이 직접 챙겨야 하는 것:

- 영속 체크포인터 (11-7)
- `thread_id` 발급/관리
- 멀티테넌시 — 사용자 A 의 `thread_id` 를 사용자 B 가 못 읽게
- 시크릿 관리
- 관측 (LangSmith 연동)
- 타임아웃 우회 (11-8)

### 백엔드 선택이 배포를 좌우한다

[Step 05 — 백엔드와 권한](../step-05-backends/)에서 본 백엔드가 여기서 결정적입니다.

| 백엔드 | 스코프 | 프로덕션 |
|---|---|---|
| `StateBackend` | 스레드(대화) 스코프. 체크포인터에 함께 저장됨 | ✅ 기본값 |
| `StoreBackend` | 스레드를 넘어 지속 | ✅ 장기 메모리용 |
| `CompositeBackend` | 둘을 경로별로 조합 | ✅ 권장 조합 |
| `FilesystemBackend` | **호스트의 실제 디스크** | ❌ |
| `LocalShellBackend` | **호스트의 실제 셸** | ❌ |
| `LangSmithSandbox` | 격리된 컨테이너 | ✅ 코드 실행이 필요할 때 |

```ts
import { CompositeBackend, StateBackend, StoreBackend } from "deepagents";

export const agent = await createDeepAgent({
  backend: new CompositeBackend(
    new StateBackend(),                        // 기본: 스레드 스코프 스크래치
    {
      "/memories/": new StoreBackend({         // 이 경로만 스레드를 넘어 지속
        namespace: (rt) => [rt.serverInfo.assistantId, rt.serverInfo.user.identity],
      }),
    },
  ),
});
```

메모리 스코프는 이렇게 나눕니다.

| 스코프 | 네임스페이스 | 용도 |
|---|---|---|
| 사용자 | `(user_id)` | 개인 선호. **기본 권장** |
| 어시스턴트 | `(assistant_id)` | 하나의 어시스턴트가 공유하는 지침 |
| 조직 | `(org_id)` | 조직 정책 (읽기 전용으로) |

> ⚠️ **함정 (파일시스템 백엔드를 프로덕션에 쓰면 인스턴스 간 공유가 안 된다)**: `FilesystemBackend` 는 로컬에서 완벽하게 동작합니다. 파일이 진짜로 디스크에 생기고, 에디터로 열어볼 수도 있습니다. 그리고 프로덕션에 올리는 순간 세 가지가 동시에 터집니다.
> 1. **인스턴스 간 공유 불가** — 인스턴스 A 가 `/notes/a.md` 를 썼는데, 사용자의 다음 요청이 인스턴스 B 로 갑니다. B 에는 그 파일이 없습니다. 에이전트는 "파일이 없다" 고 하고, 사용자는 자기 작업물이 사라졌다고 합니다.
> 2. **컨테이너 재시작 시 소실** — 파드가 재시작되면 파일이 날아갑니다.
> 3. **호스트 침범** — 에이전트가 `/etc/passwd` 나 여러분의 소스 코드를 읽을 수 있습니다. 프롬프트 인젝션 하나면 됩니다.
>
> `LocalShellBackend` 는 여기에 임의 명령 실행까지 더합니다. 이 두 백엔드는 **로컬 개발 전용**입니다. 프로덕션은 `StateBackend` / `StoreBackend` / `CompositeBackend`, 코드 실행이 필요하면 샌드박스(`LangSmithSandbox`)입니다.

> ⚠️ **함정 (공유 메모리는 프롬프트 인젝션 통로다)**: `StoreBackend` 를 조직 스코프로 열어 두면, 사용자 A 의 에이전트가 쓴 내용을 사용자 B 의 에이전트가 읽습니다. A 가 악의적인 문서를 에이전트에게 읽히면, 그 내용이 메모리에 저장되어 B 의 에이전트를 조종할 수 있습니다. 공유 경로는 **`permissions` 로 쓰기를 명시적으로 막고** 읽기 전용으로 두세요.
> ```ts
> permissions: [
>   { operations: ["read"], paths: ["/policies/**"] },
>   { operations: ["read", "write"], paths: ["/workspace/**"] },
>   { operations: ["read"], paths: ["/**"], mode: "deny" },
> ]
> ```

### 프로덕션 스택의 전형

```ts
const agent = await createDeepAgent({
  model: MODEL,
  tools: [searchDocs],
  backend: new StateBackend(),
  checkpointer,                 // 영속 체크포인터 (MemorySaver 아님!)
  contextSchema: z.object({ userId: z.string() }),
  middleware: [
    modelRetryMiddleware({ maxRetries: 3, backoffFactor: 2, initialDelayMs: 1000, maxDelayMs: 20000, jitter: true }),
    toolRetryMiddleware({ tools: ["search_docs"], maxRetries: 2, initialDelayMs: 500, onFailure: "return_message" }),
    modelCallLimitMiddleware({ runLimit: 40, exitBehavior: "end" }),
    toolCallLimitMiddleware({ runLimit: 100, exitBehavior: "continue" }),
    piiMiddleware("email", { strategy: "redact", applyToInput: true }),
  ],
  systemPrompt: "…",
});

const result = await agent.invoke(input, {
  configurable: { thread_id: threadId },
  context: { userId: "user-123" },
  durability: "sync",
  recursionLimit: 1000,        // 25 로는 계획 단계에서 죽습니다
});
```

### 프론트엔드

```tsx
import { useStream } from "@langchain/react";

function App() {
  const stream = useStream({
    apiUrl: "https://your-deployment.langsmith.dev",
    assistantId: "agent",
  });

  const send = (text: string) =>
    stream.submit(
      { messages: [{ type: "human", content: text }] },
      { streamSubgraphs: true, config: { recursionLimit: 10000 } },
    );
  // …
}
```

`streamSubgraphs: true` 가 11-1 의 `subgraphs: true` 에 대응합니다. 이걸 빼면 웹 UI 에서도 서브에이전트가 안 보입니다.

프론트엔드에서 쓸 수 있는 것:

| 노출 | 내용 |
|---|---|
| `stream.messages` | 오케스트레이터 대화 + 최종 종합 |
| `stream.subagents` | 서브에이전트 발견 스냅샷 (상태 메타데이터 포함) |
| `stream.values` | 공유 state (`todos`, `files` 등) |
| `stream.interrupt` | HITL 중단 |
| `useMessages(stream, subagent)` | 특정 서브에이전트의 메시지 |
| `useToolCalls(stream, subagent)` | 특정 서브에이전트의 도구 호출 |

서브에이전트 카드를 "그 위임을 지시한 AI 메시지" 아래에 붙이는 패턴:

```tsx
const turnSubagents = AIMessage.isInstance(message)
  ? (message.tool_calls ?? [])
      .map((tc) => subagentsByCallId.get(tc.id ?? ""))
      .filter((s): s is SubagentDiscoverySnapshot => !!s)
  : [];
```

`tool_call_id` 로 매칭하는 것 — 11-1 에서 본 `tools:<tool_call_id>` 네임스페이스 규칙과 정확히 같은 원리입니다.

---

## 11-11. CLI 프론트엔드 만들기

지금까지 배운 걸 전부 합쳐 봅시다. 터미널에서 Deep Agent 를 돌리면서 진행상황을 실시간으로 그립니다.

설계 원칙 세 가지:

1. **부모 토큰은 흘려보내고, 자식은 카드로 요약한다.** 자식 토큰을 그대로 찍으면 뒤엉킵니다.
2. **화면을 스크롤하지 말고 덮어쓴다.** ANSI 커서 이동으로 같은 영역을 다시 그립니다.
3. **모든 프로젝션을 동시에 소비한다.** 하나라도 안 읽으면 그 스트림이 막힙니다.

```ts
async function runCli(prompt: string) {
  const agent = await makeResearchAgent();
  const run = await agent.streamEvents(
    { messages: [{ role: "user" as const, content: prompt }] },
    { version: "v3", recursionLimit: 100 },
  );

  const state = {
    todos: [] as Todo[],
    subagents: new Map<string, { name: string; status: string; tools: number; last: string }>(),
    files: [] as string[],
  };

  // 스피너는 별도 타이머로 — 이벤트가 없어도 화면이 살아 있게
  let spin = 0;
  const timer = setInterval(() => {
    spin = (spin + 1) % SPINNER.length;
    render(state, SPINNER[spin]!);
  }, 120);

  const tasks: Promise<void>[] = [];

  // 1) todos + files
  tasks.push((async () => {
    for await (const snap of run.values) {
      const todos = (snap as { todos?: Todo[] }).todos;
      if (todos) state.todos = todos;
      const files = (snap as { files?: Record<string, unknown> }).files;
      if (files) state.files = Object.keys(files);
    }
  })());

  // 2) 서브에이전트 카드
  tasks.push((async () => {
    const inner: Promise<void>[] = [];
    for await (const sub of run.subagents) {
      const id = `${sub.name}#${state.subagents.size + 1}`;
      state.subagents.set(id, { name: sub.name, status: "running", tools: 0, last: "" });

      inner.push((async () => {
        for await (const _call of sub.toolCalls) state.subagents.get(id)!.tools++;
      })());
      inner.push((async () => {
        for await (const msg of sub.messages) {
          // msg.text.full 은 "누적된 전체 텍스트"를 매 델타마다 준다 — 카드 미리보기에 딱 맞음
          for await (const full of msg.text.full) {
            state.subagents.get(id)!.last = full.replace(/\s+/g, " ").slice(-60);
          }
        }
      })());
      inner.push((async () => {
        await sub.output;
        state.subagents.get(id)!.status = "done";
      })());
    }
    await Promise.all(inner);
  })());

  // 3) 부모 답변은 마지막에 통째로 — 스피너와 겹쳐 찍으면 화면이 깨진다
  const parentTexts: string[] = [];
  tasks.push((async () => {
    for await (const msg of run.messages) {
      const text = await msg.text;   // await 하면 완성 텍스트
      if (text.trim()) parentTexts.push(text.trim());
    }
  })());

  await Promise.all(tasks);
  await run.output;
  clearInterval(timer);
  render(state, "✔");

  console.log(`\n${c("1;36", "── 최종 답변 ──")}`);
  console.log(parentTexts[parentTexts.length - 1] ?? "(없음)");
}
```

`msg.text` 의 세 가지 얼굴이 여기서 다 나옵니다.

| 사용법 | 얻는 것 |
|---|---|
| `for await (const delta of msg.text)` | 증분 델타 (`"안"`, `"녕"`, `"하"`, …) |
| `for await (const full of msg.text.full)` | 매 델타마다 **누적 전체** (`"안"`, `"안녕"`, `"안녕하"`, …) |
| `await msg.text` | 완성된 전체 텍스트 (한 번) |

카드 미리보기에는 `.full` 이 딱 맞습니다 — 델타를 직접 이어 붙일 필요가 없으니까요.

화면 그리기:

```ts
let lastLineCount = 0;
function render(state, spinner: string) {
  const lines: string[] = [];
  const done = state.todos.filter((t) => t.status === "completed").length;

  lines.push(c("1;36", `${spinner} Deep Agent 실행 중`));

  if (state.todos.length > 0) {
    lines.push(c("2", `  계획 ${done}/${state.todos.length} ${progressBar(done, state.todos.length)}`));
    for (const t of state.todos) {
      const mark = t.status === "completed" ? c("32", "☑") : t.status === "in_progress" ? c("33", "▶") : "☐";
      lines.push(`   ${mark} ${t.content.slice(0, 60)}`);
    }
  }

  if (state.subagents.size > 0) {
    lines.push(c("2", "  서브에이전트"));
    for (const [id, s] of state.subagents) {
      const badge = s.status === "done" ? c("32", "●") : c("33", spinner);
      lines.push(`   ${badge} ${c("1", id.padEnd(16))} 도구 ${String(s.tools).padStart(2)}회  ${c("2", s.last)}`);
    }
  }

  if (state.files.length > 0) {
    lines.push(c("2", `  파일 ${state.files.length}개: ${state.files.join(", ").slice(0, 70)}`));
  }

  // 이전에 그린 줄만큼 커서를 올려 덮어씁니다.
  if (canColor && lastLineCount > 0) process.stdout.write(`\x1b[${lastLineCount}A`);
  for (const line of lines) {
    // \x1b[2K = 그 줄 지우기. 이전 내용이 더 길었을 때 잔상을 막습니다.
    process.stdout.write(`${canColor ? "\x1b[2K" : ""}${line}\n`);
  }
  lastLineCount = lines.length;
}
```

**출력 예시** (실행 중 화면이 계속 갱신됩니다. 모델 응답이므로 매번 다릅니다)
```
⠹ Deep Agent 실행 중
  계획 1/3 [███████░░░░░░░░░░░░░] 33%
   ☑ 벡터 검색 조사
   ▶ 전문 검색 조사
   ☐ 비교 보고서 작성
  서브에이전트
   ● researcher#1     도구  1회  …벡터 검색은 임베딩 기반으로 의미 유사도를 계산합니다.
   ⠹ researcher#2     도구  1회  …전문 검색은 역색인을 사용해 키워드를
  파일 0개
```

완료 시:
```
✔ Deep Agent 실행 중
  계획 3/3 [████████████████████] 100%
   ☑ 벡터 검색 조사
   ☑ 전문 검색 조사
   ☑ 비교 보고서 작성
  서브에이전트
   ● researcher#1     도구  1회  …벡터 검색은 임베딩 기반으로 의미 유사도를 계산합니다.
   ● researcher#2     도구  1회  …전문 검색은 역색인을 사용해 키워드 일치를 찾습니다.
   ● writer#3         도구  1회  …# 벡터 검색 vs 전문 검색
  파일 1개: /report.md

── 최종 답변 ──
비교 보고서를 /report.md 에 작성했습니다. 두 방식의 핵심 차이는…
```

> 💡 **실무 팁 — `process.stdout.isTTY` 를 항상 확인하세요**: 커서 이동(`\x1b[<n>A`)은 진짜 터미널에서만 동작합니다. CI 로그나 `> out.txt` 로 리다이렉트하면 그 이스케이프 코드가 **문자 그대로** 파일에 박혀서 로그가 읽을 수 없게 됩니다. `project/src/lib/print.ts` 가 `process.stdout.isTTY === true && process.env["NO_COLOR"] === undefined` 를 확인하는 이유입니다. TTY 가 아니면 커서 이동을 포기하고 줄을 그냥 append 하세요 — 위 `render` 의 `canColor` 분기가 그 역할을 합니다.

---

## 정리

| 하고 싶은 것 | 쓰는 것 |
|---|---|
| 자식 이벤트 보기 | `subgraphs: true` (또는 v3 스트림) |
| 부모/자식 구분 | `namespace.some(s => s.startsWith("tools:"))` — 또는 그냥 `run.subagents` |
| 부모 답변 토큰 | `run.messages` → `msg.text` |
| 자식 진행상황 | `run.subagents` → `sub.messages` / `sub.toolCalls` |
| 계획 진행률 | `run.values` → `snapshot.todos` |
| 파일 변경 | `run.toolCalls` (의도) + `run.values.files` (결과) |
| 내 프로젝션 | `streamTransformers` → `run.extensions` |
| 죽어도 이어가기 | 영속 checkpointer + `durability: "sync"` + `invoke(null, config)` |
| 재시도 | `modelRetryMiddleware` / `toolRetryMiddleware` |
| 비용 통제 | 서브에이전트 모델 티어링 + `modelCallLimitMiddleware` + 커스텀 토큰 예산 |
| 프로덕션 백엔드 | `StateBackend` / `StoreBackend` / `CompositeBackend` (**`FilesystemBackend` 금지**) |

**스트림 API 두 갈래**

| | `agent.stream()` | `agent.streamEvents(..., { version: "v3" })` |
|---|---|---|
| 수준 | 저수준. `namespace` 를 직접 파싱 | 고수준. 프로젝션으로 나뉘어 옴 |
| 부모/자식 | `subgraphs: true` + 문자열 검사 | `run.messages` vs `run.subagents` |
| 언제 | LangGraph Platform 연동, 원본 관찰 | **새 코드는 이걸로** |

**핵심 함정 3가지**

1. **`subgraphs: true` 를 빼면 자식이 통째로 안 보인다** — 에러 없이. 30분 실행의 90%가 자식 안에서 벌어지는데 그걸 못 봅니다. 그리고 켜면 튜플 모양이 `chunk` → `[namespace, chunk]` 로 조용히 바뀝니다.
2. **긴 실행에 `MemorySaver` 를 쓰면 재시작 시 전부 소실** — 로컬에서는 완벽히 동작하고, 프로덕션 첫 배포 때 모든 진행 중 대화가 증발합니다. 인스턴스가 두 대면 그 전에 이미 깨집니다. 같은 부류: `durability: "exit"` 는 체크포인터를 붙여 놓고도 재개를 무력화합니다.
3. **`recursionLimit` 기본값 25 로는 계획 단계에서 죽는다** — 그리고 에러 메시지(`GraphRecursionError`)가 "무한 루프" 처럼 읽혀서 원인을 오진합니다. Deep Agent 는 서브에이전트 루프까지 부모의 슈퍼스텝을 소진합니다. 최소 100, Platform 기본값은 10,000.

**그 밖에**: 자식 스트림을 순차로 소비하면 병렬이 UI 에서 직렬로 보입니다. `run.values` 를 `await` 하면 중간 스냅샷을 못 봅니다. `FilesystemBackend` 는 프로덕션에서 인스턴스 간 공유가 안 됩니다. `streamTransformer` 의 `process` 가 `false` 를 리턴하면 다른 프로젝션이 굶습니다.

---

## 연습문제

1. `agent.stream(..., { streamMode: "messages", subgraphs: true })` 로 토큰을 받으면서, 그 토큰이 부모의 것인지 서브에이전트의 것인지 구분해 각각 몇 개인지 세세요.
2. `streamEvents(..., { version: "v3" })` 의 `run.subagents` 로 서브에이전트 이름별 도구 호출 횟수를 집계해 표로 출력하세요. (힌트: 자식 스트림은 병렬로 소비해야 합니다)
3. `run.values` 를 구독해 todos 가 바뀔 때마다 `계획 2/5 [████████░░░░░░░░░░░░] 40%` 형태로 출력하세요. 같은 내용이면 다시 찍지 마세요.
4. 에이전트가 `write_file` / `edit_file` 을 호출할 때마다 "시작"과 "끝"을 각각 출력하세요. (힌트: `.input` 은 확정값, `.status` 는 Promise)
5. `ProtocolEvent` 를 보고 도구 호출이 시작될 때마다 `StreamChannel` 에 `{ tool, namespace }` 를 push 하는 `StreamTransformer` 를 만들고, `run.extensions.toolLog` 로 소비해 출력하세요.
6. `MemorySaver` 체크포인터를 붙인 에이전트를 `AbortSignal` 로 중간에 끊고, 같은 `thread_id` 로 재개해 끝까지 완료시키세요. `durability` 를 무엇으로 둬야 마지막 스텝을 안 잃을까요? 재개할 때 `invoke` 의 첫 인자로 무엇을 줘야 할까요?
7. 다음을 모두 만족하는 에이전트를 만드세요 — 오케스트레이터는 Sonnet, 서브에이전트는 Haiku / 모델 호출은 실행당 15번·스레드당 50번(초과 시 조용히 종료) / `search_docs` 는 실행당 5번(초과해도 다른 도구는 계속) / 모델 실패 시 최대 3번 재시도 + 지수 백오프 + 지터.
8. `recursionLimit: 5` 로 서브에이전트를 여러 개 쓰는 요청을 던져 어떤 에러가 나는지 확인하고, 그 메시지를 주석으로 적으세요. 그리고 왜 Deep Agent 는 일반 에이전트보다 `recursionLimit` 이 훨씬 커야 하는지 답하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 12 — 종합 프로젝트: 딥 리서치 에이전트](../step-12-final-project/)

Step 01~11 에서 배운 것 — 계획, 파일시스템, 백엔드, 서브에이전트, 프롬프트, 미들웨어, HITL, 메모리, 스킬, 그리고 이 스텝의 스트리밍과 프로덕션 — 을 전부 합쳐 실제로 쓸 수 있는 딥 리서치 에이전트를 만듭니다. 이 스텝의 `runCli` 가 그 프로젝트의 프론트엔드 뼈대가 됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 실행 환경은 [실습 프로젝트 셋업](../project/)의 `project/` 폴더이고, `ANTHROPIC_API_KEY` 가 `project/.env` 에 있어야 합니다.

**중요**: 이 스텝의 예제는 **실제로 모델을 부릅니다.** `practice.ts` 를 전부 돌리면 11개 절에서 모델 호출이 수십 회 발생합니다(= 돈이 나갑니다). 그래서 세 파일 모두 **절/문제 번호를 인자로 받아 골라 실행**할 수 있게 되어 있습니다.

```bash
npx tsx docs/reference/deepagent/step-11-streaming-production/practice.ts 11-3
npx tsx docs/reference/deepagent/step-11-streaming-production/practice.ts 11-3 11-4 11-11
npx tsx docs/reference/deepagent/step-11-streaming-production/solution.ts 2
```

인자를 안 주면 전부 실행됩니다. 처음이라면 `11-11` 부터 보세요 — CLI 프론트엔드가 이 스텝의 모든 개념을 한 화면에 보여줍니다.

### practice.ts

본문 `[11-1] ~ [11-11]` 을 그대로 옮긴 파일입니다. 절 번호가 본문 소제목과 1:1 대응하므로, 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[11-1]` 은 같은 요청을 `subgraphs` 없이 한 번, `subgraphs: true` 로 한 번 돌려서 **이벤트 수가 몇 배로 늘어나는지**를 눈으로 보여줍니다. 그리고 등장한 `namespace` 를 전부 찍습니다 — `tools:` 접두사 규칙이 여기서 처음 보입니다.
- `[11-3]` 이 이 스텝의 심장입니다. `parent` / `children` / `parentTools` 세 개의 async IIFE 를 만들어 놓고 마지막에 `Promise.all` 로 함께 기다리는 구조에 주목하세요. `children` 안에서 다시 `pending` 배열에 자식 소비를 모으는 **이중 구조**가 핵심입니다 — 이걸 안 하면 병렬 서브에이전트가 직렬로 보입니다.
- `[11-6]` 의 `createUsageTracker` 는 `process()` 에서 **항상 `true` 를 리턴**합니다. 주석에 이유가 적혀 있습니다. 이 트랜스포머가 찍는 출력에서 `input` 이 `output` 의 20배쯤 되는 걸 확인하세요 — 11-9 의 비용 이야기가 여기서 증거로 나옵니다.
- `[11-7]` 은 `setTimeout` + `AbortController` 로 "배포로 인한 인스턴스 종료"를 흉내냅니다. 타이머가 2.5초인데, 모델이 그 전에 끝내면 중단이 안 일어납니다 — 그 경우를 대비한 안내 메시지가 들어 있으니 타이머를 줄여 다시 돌리세요.
- `[11-9]` 의 두 번째 데모(`createTokenBudgetMiddleware(3000)`)는 **일부러 실패하도록** 예산을 낮게 잡았습니다. 에러가 나는 게 정상입니다. `middleware: [createTokenBudgetMiddleware(3000) as any]` 의 `as any` 는 데모용 축약이며, 제대로 된 커스텀 미들웨어 타이핑은 [Step 08](../step-08-middleware/)에 있습니다.
- `[11-11]` 의 `render()` 는 `lastLineCount` 를 모듈 스코프에 두고 커서를 그만큼 올려 덮어씁니다. `canColor` 가 `false` 면(파이프/CI) 커서 이동을 통째로 건너뛰므로, `npx tsx ... 11-11 > out.txt` 로 돌려도 로그가 안 깨집니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 담은 파일입니다. 각 문제는 `[문제 N]` 블록으로 구분되어 있고, `// TODO:` 아래가 비어 있습니다. 에이전트 정의와 도구는 이미 다 만들어져 있으니, 여러분은 **스트림을 소비하는 부분만** 쓰면 됩니다.

- `[문제 1]` 과 `[문제 2]` 는 같은 목표(부모/자식 구분)를 **다른 API 로** 풉니다. 1번은 `stream()` + `namespace` 문자열 검사, 2번은 `streamEvents` v3 + `run.subagents`. 둘 다 풀어 보면 11-2 의 "실무 팁 — 새 코드는 v3 부터" 가 왜 그런지 몸으로 알게 됩니다.
- `[문제 5]` 의 `createToolLogger` 는 뼈대가 이미 있고 `process()` 안만 비어 있습니다. 힌트대로 **일단 모든 이벤트를 `console.log` 해서 관찰**하는 것부터 하세요. `method` 와 `params.data` 의 실제 모양을 눈으로 보면 답이 바로 보입니다.
- `[문제 6]` 은 세 단계(중단 → 확인 → 재개)로 나뉘어 있고, 각 단계의 힌트가 주석에 있습니다. `durability` 를 안 주고 돌려보고, 그 다음 `"sync"` 로 주고 돌려서 `next` 배열이 어떻게 달라지는지 비교해 보세요.
- `[문제 8]` 은 코드보다 **답을 적는 게** 핵심입니다. 주석의 `→ (여기에 답을 적으세요)` 자리를 채우세요. 에러 메시지를 실제로 재현해 본 뒤에 적어야 의미가 있습니다.
- 파일 맨 아래의 `void progressBar; void ({} as Todo);` 는 미사용 경고를 막기 위한 것입니다. 문제를 풀면서 실제로 쓰게 되면 지워도 됩니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답과 해설 주석입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석이 "왜 이렇게 되는가" 와 "여기서 흔히 틀리는 곳" 을 설명합니다.

- `[정답 2]` 의 해설이 이 파일에서 가장 중요합니다. 틀린 코드(중첩 `for await`)와 맞는 코드(`pending` 배열 + `Promise.all`)를 나란히 놓고, **왜 틀린 코드도 결과는 맞는지** 설명합니다. 결과가 맞기 때문에 테스트로는 절대 안 잡히고, 오직 UI 만 거짓말을 합니다.
- `[정답 3]` 은 `run.values` 의 이중 인터페이스(`AsyncIterable` **&** `PromiseLike`)를 다룹니다. `await run.values` 와 `for await (... of run.values)` 가 **둘 다 타입이 맞고 둘 다 에러가 안 나는데** 결과가 완전히 다르다는 점이 핵심입니다.
- `[정답 4]` 는 `ToolCallStream` 의 어느 필드가 Promise 이고 어느 필드가 확정값인지 표로 정리합니다. `input` 이 Promise 가 아닌 이유(= v3 스트림이 부분 JSON 을 대신 조립해 줌)가 LangChain 코스 Step 09 의 함정과 연결됩니다.
- `[정답 7]` 의 함정은 `exitBehavior` 입니다. `modelCallLimitMiddleware` 는 `"error" | "end"`, `toolCallLimitMiddleware` 는 `"continue" | "error" | "end"` 로 **받는 값이 다릅니다.** 문자열을 잘못 쓰면 타입 에러로 잡히지만, 의미를 반대로 고르면(예: "조용히 종료"에 `"error"`) 타입은 통과하고 런타임 동작만 달라집니다.
- `[정답 8]` 은 코드가 거의 없고 **주석이 본체**입니다. `GraphRecursionError` 의 실제 메시지 전문과, Deep Agent 가 슈퍼스텝을 소진하는 경로를 단계별로 적어 놨습니다. 마지막에 `recursionLimit: 100` 으로 같은 에이전트가 통과하는 것을 붙여서 대조군으로 삼았습니다.
- `[정답 6]` 의 `agent.getState(config)` 에는 `as unknown as { values; next }` 캐스팅이 있습니다. 제네릭 해석 결과에 따라 반환 타입이 좁혀지지 않는 경우가 있어 필요한 필드만 명시적으로 꺼낸 것이며, 런타임 동작에는 영향이 없습니다.

```ts file="./solution.ts"
```
