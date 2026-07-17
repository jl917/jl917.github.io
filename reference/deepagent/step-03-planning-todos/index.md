# Step 03 — 계획 도구 (write_todos)

> **학습 목표**
> - 긴 작업에서 에이전트가 지시를 잃어버리는 현상(**drift**)을 재현하고 원인을 설명한다
> - `write_todos` 도구가 **상태에 계획을 저장하고 매 턴 컨텍스트에 재주입**하는 메커니즘을 설명한다
> - `agent.stream()` 과 `result.todos` 로 계획이 갱신되는 과정을 관찰한다
> - `todoListMiddleware()` 를 일반 `createAgent` 에 붙여 **deepagents 없이 계획 능력만** 빌려온다
> - `todoListMiddleware({ systemPrompt })` 로 계획의 품질을 프롬프팅으로 조절한다
> - 계획이 **오히려 손해인** 상황을 토큰 수로 판단한다
>
> **선행 스텝**: [Step 02 — 첫 Deep Agent](../step-02-quickstart/)
> **예상 소요**: 60분

[Step 02](../step-02-quickstart/) 에서 `createDeepAgent()` 로 첫 Deep Agent 를 만들었습니다. 그때 아무 설정도 하지 않았는데 에이전트가 `write_todos` 라는 도구를 부르는 걸 봤을 겁니다. 이번 스텝은 그 도구 하나만 파고듭니다.

Deep Agent 를 "깊게" 만드는 네 기둥은 **계획 · 파일시스템 · 서브에이전트 · 상세한 프롬프트** 입니다. 그중 가장 이해하기 쉽고, 가장 효과가 즉각적인 것이 계획입니다. 그런데 이 도구의 동작을 오해하는 사람이 많습니다. `write_todos` 는 할 일을 **어딘가에 저장해두는 기능이 아닙니다.** 저장은 부수효과이고, 진짜 목적은 **모델의 주의(attention)를 매 턴 붙잡아 두는 것**입니다. 이 차이를 이해하면 계획 도구를 언제 쓰고 언제 빼야 하는지가 명확해집니다.

---

## 3-1. 왜 계획이 필요한가 — drift 현상

에이전트에게 5단계짜리 작업을 시켜 봅시다. 계획 도구가 **없는** 일반 에이전트입니다.

```ts
import { createAgent } from "langchain";

const plain = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  systemPrompt: "너는 기술 문서 작성 보조자다. 요청받은 작업을 수행한다.",
});

const task =
  "사내 REST API 가이드 문서를 만들려고 한다. " +
  "(1) 목차를 짜고 (2) 인증 章 초안을 쓰고 (3) 에러 코드 표를 만들고 " +
  "(4) 각 章마다 예제 요청/응답을 넣고 (5) 마지막에 빠진 항목을 스스로 점검해라.";

const result = await plain.invoke({
  messages: [{ role: "user", content: task }],
});

console.log(result.messages.at(-1)?.text);
console.log("todos 키 존재?", "todos" in result);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
# 사내 REST API 가이드

## 목차
1. 시작하기
2. 인증
3. 엔드포인트
4. 에러 코드
...

## 2. 인증
API 는 Bearer 토큰을 사용합니다...
(예제 요청/응답 일부만 등장, (5) 자가 점검은 나오지 않음)

todos 키 존재? false
```

전형적인 실패 모습입니다. (1)(2)(3)은 그럭저럭 하고, (4)는 일부만, (5)는 아예 잊습니다. 이것이 **drift** 입니다. 모델이 멍청해서가 아닙니다. 구조적인 이유가 있습니다.

에이전트는 매 턴 "메시지 목록 전체"를 모델에 다시 보냅니다. 턴이 쌓이면 원래 지시는 **대화의 저 위쪽**으로 밀려납니다. 모델의 주의는 보통 **최근 메시지**와 **시스템 프롬프트**에 강하게 쏠립니다. 5턴쯤 지나면 "(5) 마지막에 빠진 항목을 점검해라" 는 20개 메시지 뒤에 파묻힌 한 줄이 되고, 모델은 바로 앞 턴에서 자기가 쓰던 에러 코드 표를 마저 쓰는 데만 몰두합니다.

마지막 줄의 `"todos" in result` 가 `false` 인 것도 눈여겨보세요. 일반 `createAgent` 의 상태에는 `todos` 키가 **존재하지도 않습니다.** 이 키는 계획 미들웨어가 상태 스키마에 추가해 주는 것입니다(3-5 에서 다룹니다).

> 💡 **실무 팁**: drift 는 "작업 단계 수"보다 **"턴 수"** 에 비례합니다. 도구를 많이 부르는 작업일수록 턴이 폭발하고, 원래 지시는 그만큼 빨리 묻힙니다. 도구 호출이 10턴을 넘길 것 같으면 계획 도구를 검토하세요. 반대로 도구 없이 한 번에 끝나는 작업은 애초에 drift 가 생길 수 없습니다.

---

## 3-2. `write_todos` 도구 동작 — 핵심 메커니즘

같은 작업을 Deep Agent 에게 시킵니다. `createDeepAgent()` 는 기본 미들웨어 스택에 계획 미들웨어를 포함하므로 **아무 설정도 필요 없습니다.**

```ts
import { createDeepAgent } from "deepagents";

// createDeepAgent 는 await 이 필요합니다 (Step 02 참고)
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: "너는 기술 문서 작성 보조자다.",
});

const result = await agent.invoke({
  messages: [{ role: "user", content: task }],  // 3-1 과 같은 작업
});

console.log(result.todos);
```

**출력 예시** (내용은 매번 다르지만 **구조는 결정적입니다**)

```
[
  { content: '문서 목차 설계', status: 'completed' },
  { content: '인증 章 초안 작성', status: 'completed' },
  { content: '에러 코드 표 작성', status: 'completed' },
  { content: '각 章에 예제 요청/응답 추가', status: 'completed' },
  { content: '누락 항목 자가 점검', status: 'completed' }
]
```

5단계가 전부 살아남았습니다. `todos` 배열의 타입은 라이브러리에 다음과 같이 **고정**되어 있습니다.

```ts
type Todo = {
  content: string;                                   // 할 일 내용
  status: "pending" | "in_progress" | "completed";   // 이 3개가 전부
};
```

`status` 값은 정확히 이 세 개뿐입니다. `"done"`, `"todo"`, `"blocked"` 같은 값은 zod enum 검증에서 튕깁니다.

### 도구 시그니처

`write_todos` 는 파라미터가 **하나**입니다.

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `todos` | `Todo[]` | 갱신할 **할 일 목록 전체** |

여기가 첫 번째 오해 지점입니다. `write_todos` 는 "할 일 하나를 추가"하거나 "3번 항목을 완료로 바꾸는" 도구가 **아닙니다.** 매 호출마다 **목록 전체를 통째로 교체**합니다. 모델은 항목 하나의 상태를 바꾸고 싶어도 5개짜리 배열을 5개 모두 다시 써서 보냅니다.

### 저장 → 재주입 사이클

도구를 호출하면 내부적으로 LangGraph `Command` 가 반환되어 상태의 `todos` 키를 덮어씁니다.

```ts
// 라이브러리 내부 동작 (직접 작성할 필요 없음)
return new Command({
  update: {
    todos,                                   // ← 상태의 todos 를 통째로 교체
    messages: [new ToolMessage({
      content: `Updated todo list to ${JSON.stringify(todos)}`,
      // ...
    })],
  },
});
```

ToolMessage 의 content 형식 `Updated todo list to [{"content":"...","status":"..."}]` 은 결정적입니다. 그리고 이 ToolMessage 는 **대화 기록에 남습니다.**

이제 핵심입니다. 계획이 효과를 내는 경로는 두 갈래입니다.

1. **상태 저장**: `todos` 가 상태에 남아 `result.todos` 로 읽을 수 있다 (UI 표시용)
2. **컨텍스트 재주입**: 계획을 담은 ToolMessage 가 대화에 남아, **매 턴 모델에게 다시 보인다** (동작 제어용)

효과의 대부분은 **2번**에서 나옵니다. 1번은 사람이 보기 위한 부산물입니다.

> ⚠️ **함정**: `write_todos` 를 **한 턴에 두 번 이상 병렬 호출**하면 에러가 됩니다. 목록 전체를 교체하는 도구라서, 병렬 호출은 "어느 쪽이 최종인가"를 알 수 없게 만들기 때문입니다. 미들웨어의 `afterModel` 훅이 이를 감지해 아래 ToolMessage 를 `status: "error"` 로 돌려줍니다.
>
> ```
> Error: The `write_todos` tool should never be called multiple times in parallel.
> Please call it only once per model invocation to update the todo list.
> ```
>
> 이건 **조용히 잘못되지 않고 에러가 나는** 착한 경우입니다. 다만 에러 메시지가 ToolMessage 로 돌아가므로 실행이 멈추지는 않고, 모델이 이를 읽고 재시도합니다. 즉 **턴 하나를 통째로 낭비**합니다. 로그에 이 메시지가 반복되면 프롬프트로 "한 번만 부르라"고 못 박으세요.

---

## 3-3. 계획은 프롬프트다

`todoListMiddleware` 가 하는 일은 사실 두 가지가 전부입니다.

1. `write_todos` 도구를 등록한다
2. **시스템 프롬프트에 계획 사용법을 덧붙인다**

2번이 진짜입니다. 이 프롬프트는 상수로 export 되어 있어 직접 읽어볼 수 있습니다.

```ts
import { TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT } from "langchain";

console.log(TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT);
```

**출력** (라이브러리 상수이므로 **결정적입니다** — 원문 그대로)

```
## `write_todos`

You have access to the `write_todos` tool to help you manage and plan complex objectives.
Use this tool for complex objectives to ensure that you are tracking each necessary step and giving the user visibility into your progress.
This tool is very helpful for planning complex objectives, and for breaking down these larger complex objectives into smaller steps.

It is critical that you mark todos as completed as soon as you are done with a step. Do not batch up multiple steps before marking them as completed.
For simple objectives that only require a few steps, it is better to just complete the objective directly and NOT use this tool.
Writing todos takes time and tokens, use it when it is helpful for managing complex many-step problems! But not for simple few-step requests.

## Important To-Do List Usage Notes to Remember
- The `write_todos` tool should never be called multiple times in parallel.
- Don't be afraid to revise the To-Do list as you go. New information may reveal new tasks that need to be done, or old tasks that are irrelevant.
```

이 프롬프트는 `wrapModelCall` 훅에서 **매 모델 호출마다** 시스템 메시지 뒤에 이어붙습니다.

```ts
// 라이브러리 내부
wrapModelCall: (request, handler) => handler({
  ...request,
  systemMessage: request.systemMessage.concat(`\n\n${TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT}`),
})
```

여기에 더해 `write_todos` 의 **도구 설명(description)** 이 별도로 있는데, 이건 훨씬 깁니다 — 수천 자짜리 문서에 "언제 쓰는가 / 언제 쓰지 말아야 하는가" 예시가 8개나 들어 있습니다. 그중 "쓰지 말아야 할 때"의 예시를 하나 보면 이 도구의 설계 철학이 드러납니다.

```
<example>
User: I need to write a function that checks if a number is prime and then test it out.
Assistant: *Writes function* *Tests the function*

<reasoning>
Even though this is a multi-step task, it is very straightforward and can be completed
in two trivial steps (which is less than 3 steps!). Using the todo list here is overkill
and wastes time and tokens.
</reasoning>
</example>
```

### 왜 이게 통하는가

계획이 모델의 주의를 붙잡는 원리는 3-1 의 drift 설명을 뒤집으면 됩니다.

| | 계획 없음 | 계획 있음 |
|---|---|---|
| 원래 지시의 위치 | 대화 맨 위 (점점 멀어짐) | 매 턴 갱신되는 **최근 ToolMessage** |
| 사용법 안내 | 없음 | **매 턴** 시스템 프롬프트에 재주입 |
| 남은 일 목록 | 모델이 기억해야 함 | `status: "pending"` 로 **눈앞에** 있음 |
| 진행 상황 | 대화를 거슬러 읽어야 앎 | `completed` / `pending` 로 한눈에 |

핵심은 계획이 **최근 메시지 위치를 계속 차지한다**는 것입니다. 모델이 3번 항목을 끝내고 `write_todos` 를 부르면, 갱신된 목록(= 아직 `pending` 인 4·5번이 적힌)이 대화의 **가장 최근**에 놓입니다. 원래 지시가 20개 메시지 뒤로 밀려나도, 그 요약본이 계속 맨 앞으로 끌려 나오는 셈입니다.

> 💡 **실무 팁**: 그래서 "계획을 세워라"라고 **사용자 메시지에 한 번 적는 것**은 계획 도구와 효과가 전혀 다릅니다. 사용자 메시지의 지시는 시간이 갈수록 묻히지만, 도구가 만든 계획은 **매 턴 새로 고쳐 쓰이며 앞으로 나옵니다.** "프롬프트로 시키면 되는 거 아닌가?"라는 질문의 답이 이것입니다. 계획 도구는 프롬프트의 **위치를 매 턴 갱신하는** 장치입니다.

---

## 3-4. todo 상태 관찰

계획이 갱신되는 과정을 실시간으로 봅시다. `streamMode: "values"` 는 매 스텝마다 **상태 전체**를 흘려보냅니다.

```ts
const stream = await agent.stream(
  {
    messages: [{
      role: "user",
      content:
        "블로그 글 '타입스크립트 제네릭 입문'을 기획해라. " +
        "개요 → 예제 3개 선정 → 각 예제 설명 → 마무리 순으로 진행하고, " +
        "각 단계를 끝낼 때마다 진행 상황을 갱신해라.",
    }],
  },
  { streamMode: "values" },
);

let prev = "";
for await (const chunk of stream) {
  const todos = chunk.todos;
  const snapshot = JSON.stringify(todos ?? []);
  if (snapshot !== prev) {         // 계획이 "바뀐 순간"에만 출력
    prev = snapshot;
    console.log(todos);
  }
}
```

**출력 예시** (내용/횟수는 매번 다릅니다)

```
--- 갱신 #1 ---
  [~] 글 개요 설계
  [ ] 제네릭 예제 3개 선정
  [ ] 각 예제 설명 작성
  [ ] 마무리 작성

--- 갱신 #2 ---
  [x] 글 개요 설계
  [~] 제네릭 예제 3개 선정
  [ ] 각 예제 설명 작성
  [ ] 마무리 작성

--- 갱신 #3 ---
  [x] 글 개요 설계
  [x] 제네릭 예제 3개 선정
  [~] 각 예제 설명 작성
  [ ] 마무리 작성
```

기본 프롬프트가 "계획을 쓰는 즉시 첫 항목을 `in_progress` 로 표시하라", "끝나면 **즉시** `completed` 로 바꿔라(몰아서 하지 마라)" 라고 지시하기 때문에 이런 패턴이 나옵니다.

진행률 계산은 배열에서 바로 뽑습니다.

```ts
const completed = todos.filter((t) => t.status === "completed").length;
const percentage = todos.length
  ? Math.round((completed / todos.length) * 100)
  : 0;
```

이 패턴이 그대로 프런트엔드로 이어집니다. React 라면 `useStream` 훅의 `stream.values.todos` 를 읽어 같은 계산을 합니다.

```tsx
const stream = useStream<typeof myAgent>({
  apiUrl: AGENT_URL,
  assistantId: "deep_agent_todo_list",
});

const todos = stream.values?.todos ?? [];
```

`?? []` 를 빼지 마세요. 모델이 아직 `write_todos` 를 부르지 않았으면 `todos` 는 `undefined` 입니다.

> ⚠️ **함정**: `streamMode: "values"` 는 **상태 전체**를 매 스텝 내보냅니다. 파일시스템까지 쓰는 에이전트([Step 04](../step-04-filesystem/))라면 여기에 `files` 도 통째로 실려 나옵니다. 큰 파일을 다루면 스트림이 무거워집니다. todos 만 필요하면 위 예제처럼 **직전 스냅샷과 비교해 변한 경우에만** 처리하거나, `streamMode: "updates"` 로 바꿔 변경분만 받으세요.

> ⚠️ **함정**: `result.todos` 는 **마지막 계획 상태**일 뿐 "작업이 성공했다"는 증거가 아닙니다. 모델은 실패한 작업도 `completed` 로 표시할 수 있습니다 — 기본 프롬프트가 "완전히 끝냈을 때만 completed 로 바꿔라"라고 지시하지만 **강제되지는 않습니다.** 이건 도구가 아니라 프롬프트일 뿐이고, 모델은 프롬프트를 어길 수 있습니다. 모든 항목이 `completed` 인 것을 **검증 통과로 착각하지 마세요.** 실제 산출물을 따로 확인해야 합니다.

---

## 3-5. `todoListMiddleware` 를 일반 `createAgent` 에 붙이기

Deep Agent 의 계획 능력은 사실 `langchain` 의 내장 미들웨어입니다. `deepagents` 를 쓰지 않아도 **계획 능력만** 빌려올 수 있습니다.

```ts
import { createAgent, todoListMiddleware, tool } from "langchain";
import * as z from "zod";

const lookupSpec = tool(
  async ({ topic }) => `[${topic}] 스펙 요약: 필드 3개, 필수 2개, deprecated 1개.`,
  {
    name: "lookup_spec",
    description: "주어진 주제의 사내 스펙 요약을 반환한다.",
    schema: z.object({ topic: z.string().describe("조회할 주제") }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [lookupSpec],
  systemPrompt: "너는 사내 API 문서 작성 보조자다.",
  middleware: [todoListMiddleware()],   // ← 이 한 줄이 전부
});

const result = await agent.invoke({
  messages: [{
    role: "user",
    content:
      "결제/회원/알림 3개 도메인의 스펙을 각각 조회하고, " +
      "도메인별 요약과 deprecated 필드 목록을 정리한 표를 만들어라.",
  }],
});

console.log(result.todos);   // ← 이제 이 키가 존재합니다
```

3-1 에서 `"todos" in result` 가 `false` 였던 것과 대조하세요. `todoListMiddleware()` 는 상태 스키마에 `todos` 를 추가합니다.

```ts
// 미들웨어가 상태에 추가하는 스키마
const stateSchema = z.object({
  todos: z.array(TodoSchema).default([]),
});
```

`.default([])` 덕분에 `result.todos` 는 도구가 안 불려도 `[]` 입니다(`undefined` 가 아닙니다). 다만 **스트리밍 중간 청크**에서는 아직 `undefined` 일 수 있으니 3-4 처럼 방어하세요.

> 💡 **실무 팁**: 언제 `createDeepAgent` 대신 `createAgent + todoListMiddleware` 를 쓰나?
>
> | 필요한 것 | 선택 |
> |---|---|
> | 계획만 | `createAgent` + `todoListMiddleware()` |
> | 계획 + 파일시스템 | `createAgent` + `todoListMiddleware()` + `createFilesystemMiddleware()` |
> | 계획 + 파일 + 서브에이전트 + 요약 + 캐싱 | `createDeepAgent()` |
>
> `createDeepAgent` 는 미들웨어 10개짜리 스택을 한 번에 켭니다(TodoList → Skills → Filesystem → SubAgent → Summarization → PatchToolCalls → …). 계획만 필요한데 이걸 쓰면 파일시스템 도구 6개와 `task` 도구의 스키마·설명이 **매 턴 컨텍스트를 차지**합니다. 필요한 것만 조립하는 쪽이 싸고 예측 가능합니다.

OpenAI 로 바꾸려면 모델 문자열만 교체하면 됩니다 — 미들웨어는 그대로입니다.

```ts
const agent = createAgent({
  model: "openai:gpt-5.5",
  middleware: [todoListMiddleware()],
});
```

---

## 3-6. 계획을 잘 세우게 하는 프롬프팅

기본 프롬프트로 만든 계획은 종종 이렇게 나옵니다.

**출력 예시** (A) 기본

```
  [~] 결제 도메인 조사
  [ ] 회원 도메인 조사
  [ ] 알림 도메인 조사
  [ ] 문서 정리
```

나쁘지 않지만 "조사"가 뭘 뜻하는지, 언제 끝난 건지 모호합니다. 모델은 스펙을 한 줄만 읽고도 `completed` 로 바꿀 수 있습니다.

`todoListMiddleware` 는 **두 개의 옵션**을 받습니다.

| 옵션 | 타입 | 설명 |
|---|---|---|
| `systemPrompt` | `string` | 계획 사용법 프롬프트를 **대체**한다 (기본값: `TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT`) |
| `toolDescription` | `string` | `write_todos` 도구의 설명을 **대체**한다 |

`systemPrompt` 로 계획 규칙을 직접 지정해 봅시다.

```ts
const tuned = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [lookupSpec],
  middleware: [
    todoListMiddleware({
      systemPrompt: [
        "## `write_todos`",
        "",
        "복잡한 작업은 반드시 `write_todos` 로 계획을 먼저 세워라.",
        "",
        "계획 작성 규칙:",
        "- 각 항목은 **검증 가능한 산출물**로 적어라.",
        "  ('조사한다' X → '결제 스펙의 필수 필드 목록을 뽑는다' O)",
        "- 항목 수는 3~7개로 유지해라. 그보다 잘게 쪼개면 관리 비용이 더 크다.",
        "- 각 항목은 '무엇을' + '어떻게 확인하는가' 를 포함해라.",
        "- 계획을 쓰는 즉시 첫 항목을 in_progress 로 표시해라.",
        "- 한 항목이 끝나면 **즉시** completed 로 바꿔라. 몰아서 처리하지 마라.",
        "- 새로 알게 된 사실이 있으면 계획을 고쳐 써라. 계획은 고정된 계약이 아니다.",
        "",
        "`write_todos` 를 한 턴에 두 번 이상 병렬로 부르지 마라.",
      ].join("\n"),
    }),
  ],
});
```

**출력 예시** (B) 튜닝 (매번 다릅니다)

```
  [~] 결제 스펙 조회 후 필수 필드 2개 + deprecated 필드 목록 확보
  [ ] 회원 스펙 조회 후 필수 필드 + deprecated 필드 목록 확보
  [ ] 알림 스펙 조회 후 필수 필드 + deprecated 필드 목록 확보
  [ ] 3개 도메인 요약을 표 1개로 병합
  [ ] 표에 3개 도메인이 모두 있는지 확인
```

항목마다 "무엇이 있으면 끝난 것인가"가 적혀 있습니다. 검증 기준이 계획에 박혀 있으니 모델이 대충 넘기기 어렵습니다.

### 계획 프롬프팅 체크리스트

| 원칙 | 나쁜 항목 | 좋은 항목 |
|---|---|---|
| 검증 가능하게 | "API 조사" | "결제 API 의 필수 필드 목록 확보" |
| 산출물 명시 | "정리한다" | "도메인×필드 표 1개 작성" |
| 적정 입도 | 15개 항목 | 3~7개 항목 |
| 마지막에 점검 | (없음) | "3개 도메인이 표에 모두 있는지 확인" |

> ⚠️ **함정**: `systemPrompt` 옵션은 기본 프롬프트에 **덧붙이는 게 아니라 통째로 대체**합니다. 위 예제에서 마지막 줄 "한 턴에 두 번 이상 병렬로 부르지 마라"를 일부러 넣은 이유가 이것입니다. 기본 프롬프트에 있던 그 경고가 대체되면서 사라지기 때문입니다. 이 줄을 빼면 병렬 호출 에러(3-2)가 늘어납니다. 기본 프롬프트를 대체할 거면 **원문을 먼저 읽고**(3-3), 무엇을 잃는지 확인한 뒤 필요한 조항을 다시 넣으세요.
>
> 기본 프롬프트를 유지하면서 규칙만 추가하고 싶다면 이렇게 이어붙입니다.
>
> ```ts
> import { TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT, todoListMiddleware } from "langchain";
>
> todoListMiddleware({
>   systemPrompt: `${TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT}
>
> ## 추가 규칙
> - 각 항목은 검증 가능한 산출물로 적어라.
> - 항목 수는 3~7개로 유지해라.`,
> });
> ```

> 💡 **실무 팁**: `toolDescription` 은 웬만하면 건드리지 마세요. 기본 설명은 "쓸 때/안 쓸 때" 예시 8개가 들어간 수천 자짜리이고, 이 예시들이 "3단계 미만이면 쓰지 마라"는 판단을 실제로 작동시킵니다. 짧게 줄이면 모델이 사소한 요청에도 계획을 세우기 시작합니다(3-7 참고). 계획의 **내용**을 바꾸고 싶으면 `systemPrompt` 로 충분합니다.

---

## 3-7. 언제 계획이 방해가 되나

계획은 공짜가 아닙니다. 비용을 재 봅시다.

```ts
const withPlan = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  middleware: [todoListMiddleware()],
});

const withoutPlan = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
});

const trivial = "TypeScript 에서 문자열을 숫자로 바꾸는 방법을 한 줄로 알려줘.";

const r1 = await withPlan.invoke({ messages: [{ role: "user", content: trivial }] });
const r2 = await withoutPlan.invoke({ messages: [{ role: "user", content: trivial }] });

// usage_metadata 는 AIMessage 에 실려 옵니다 — 필드명은 결정적입니다
const usage1 = r1.messages.find((m) => m.getType() === "ai")?.usage_metadata;
const usage2 = r2.messages.find((m) => m.getType() === "ai")?.usage_metadata;

console.log("계획 O:", usage1?.input_tokens);
console.log("계획 X:", usage2?.input_tokens);
```

**출력 예시** (정확한 수치는 모델·버전마다 다르지만, **계획 O 가 항상 큽니다**)

```
계획 O: 2100
계획 X: 30

차이: 2070 토큰 (계획 프롬프트 + write_todos 도구 스키마가 매 턴 붙는 비용)
```

두 자릿수 배 차이입니다. `write_todos` 의 도구 설명이 수천 자이므로, **도구를 한 번도 부르지 않아도** 그 설명은 매 턴 컨텍스트에 실립니다. 이 요청의 답은 `Number(str)` 한 줄인데 말이죠.

`r1.todos` 는 대개 `[]` 입니다 — 기본 프롬프트가 "3단계 미만이면 쓰지 마라"고 지시하니 모델은 계획을 세우지 않습니다. **하지만 토큰 비용은 이미 지불했습니다.** 계획을 안 세운 대가로 아낀 것은 출력 토큰뿐이고, 입력 토큰 2000개는 그대로 나갔습니다.

### 계획을 쓸까 말까

| 상황 | 계획 | 이유 |
|---|---|---|
| 도구 호출 10턴 이상 예상 | ✅ | drift 위험이 큼 |
| 단계가 서로 의존 (A→B→C) | ✅ | 순서를 잃으면 실패 |
| 사용자에게 진행률을 보여야 함 | ✅ | `todos` 를 UI 에 바로 연결 |
| 작업 중 계획이 바뀔 수 있음 | ✅ | 계획 수정이 곧 재계획 |
| 단발성 질의응답 챗봇 | ❌ | 매 턴 2000 토큰 낭비 |
| 도구 1~2개 부르고 끝 | ❌ | drift 가 생길 틈이 없음 |
| 지연시간이 중요 (실시간 응답) | ❌ | 계획 세우는 턴이 통째로 추가됨 |
| 대량 배치 처리 (건당 단순) | ❌ | 건수 × 2000 토큰 |

> ⚠️ **함정**: 계획 미들웨어를 켜 놓고 **짧은 대화**를 대량으로 처리하면 비용이 조용히 샙니다. 에러도 없고, 계획도 안 세워지고(모델이 알아서 안 씀), 결과도 정상입니다 — 청구서만 몇 배가 됩니다. `todos` 가 늘 `[]` 로 나오는 프로덕션 에이전트라면 미들웨어를 빼는 게 맞습니다. **`todos` 가 비어 있다는 건 "계획이 필요 없었다"는 뜻이고, 그건 곧 "미들웨어가 필요 없었다"는 뜻입니다.**

> 💡 **실무 팁**: 한 에이전트가 짧은 요청과 긴 요청을 모두 받는다면, 계획 미들웨어를 **항상 켜는 대신** 요청 성격에 따라 에이전트를 둘로 나누고 라우팅하는 편이 낫습니다. 아니면 무거운 다단계 작업만 서브에이전트([Step 06](../step-06-subagents/))로 떼어내고, 그 서브에이전트에만 계획을 붙이세요. 부모는 가볍게 유지됩니다.

---

## 3-8. 종합

계획 + 도구 + 진행률 관찰을 한데 묶습니다.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [lookupSpec],
  systemPrompt: [
    "너는 사내 API 문서 담당자다.",
    "",
    "작업 규칙:",
    "- 3단계 이상 걸리는 일은 반드시 계획을 먼저 세운다.",
    "- 각 계획 항목은 검증 가능한 산출물로 적는다.",
    "- 항목을 끝내면 즉시 completed 로 바꾼다.",
  ].join("\n"),
});

const stream = await agent.stream(
  {
    messages: [{
      role: "user",
      content:
        "결제/회원/알림 3개 도메인의 스펙을 조회하고, " +
        "도메인별 요약 + deprecated 필드 목록 표를 만들어라. " +
        "마지막에 빠뜨린 도메인이 없는지 점검해라.",
    }],
  },
  { streamMode: "values" },
);

let prev = "";
for await (const chunk of stream) {
  const snapshot = JSON.stringify(chunk.todos ?? []);
  if (snapshot !== prev) {
    prev = snapshot;
    const todos = chunk.todos ?? [];
    const done = todos.filter((t) => t.status === "completed").length;
    const pct = todos.length ? Math.round((done / todos.length) * 100) : 0;
    console.log(`[진행률 ${pct}%] ${done}/${todos.length}`);
  }
}
```

**출력 예시** (매번 다릅니다)

```
[진행률 0%] 0/5
[진행률 20%] 1/5
[진행률 40%] 2/5
[진행률 60%] 3/5
[진행률 80%] 4/5
[진행률 100%] 5/5
```

`systemPrompt` 로 준 문자열은 Deep Agent 의 내장 프롬프트 **앞**에 붙습니다. 즉 위 규칙은 기본 계획 프롬프트를 대체하지 않고 **함께** 작동합니다. `todoListMiddleware({ systemPrompt })` 가 **대체**인 것과 대조하세요 — 같은 이름이지만 동작이 반대입니다.

| 위치 | 동작 |
|---|---|
| `createDeepAgent({ systemPrompt })` | 내장 프롬프트 **앞에 붙임** (prepend) |
| `todoListMiddleware({ systemPrompt })` | 계획 프롬프트를 **대체함** (replace) |

---

## 정리

| 항목 | 내용 |
|---|---|
| 도구 이름 | `write_todos` |
| 파라미터 | `todos: { content: string, status: "pending" \| "in_progress" \| "completed" }[]` |
| 동작 | 목록 **전체 교체** (부분 갱신 아님) |
| 상태 키 | `todos` (기본값 `[]`) |
| 미들웨어 | `todoListMiddleware(options?)` from `"langchain"` |
| 옵션 | `systemPrompt` (프롬프트 대체), `toolDescription` (도구 설명 대체) |
| 프롬프트 상수 | `TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT` from `"langchain"` |
| Deep Agent 기본 포함 | ✅ (`createDeepAgent` 는 자동으로 켬) |
| 읽는 법 | `result.todos` / 스트림의 `chunk.todos` / `stream.values.todos` (React) |
| 병렬 호출 | ❌ 에러 ToolMessage 반환 |

**핵심 함정 3가지**

1. **모든 항목이 `completed` ≠ 작업 성공.** 상태는 모델이 스스로 적는 자기 보고일 뿐입니다. "완료했을 때만 표시하라"는 프롬프트는 강제가 아니라 부탁입니다. 산출물을 따로 검증하세요.
2. **`todoListMiddleware({ systemPrompt })` 는 기본 프롬프트를 대체한다.** 병렬 호출 금지 같은 기본 조항이 조용히 사라집니다. 이어붙이려면 `TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT` 를 명시적으로 포함하세요.
3. **계획을 안 세워도 토큰은 나간다.** 도구 설명 수천 자가 매 턴 컨텍스트에 실립니다. `todos` 가 늘 `[]` 인 에이전트는 미들웨어를 빼야 합니다.

---

## 연습문제

1. 계획 도구가 없는 `createAgent` 와 `todoListMiddleware()` 를 붙인 `createAgent` 에게 **동일한 5단계 작업**을 시키고, 마지막 단계(5번)를 실제로 수행했는지 각각 확인하세요. 결과를 주석으로 기록하세요.
2. `TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT` 를 출력하고, 그 안에서 **"이 도구를 쓰지 말라"** 고 지시하는 문장을 3개 찾아 주석으로 옮겨 적으세요.
3. `agent.stream(..., { streamMode: "values" })` 로 `todos` 가 갱신되는 횟수를 세세요. `write_todos` 를 호출한 횟수(ToolMessage 개수)와 일치하나요? 다르다면 왜 그럴까요?
4. `todoListMiddleware({ systemPrompt })` 로 "모든 항목을 반드시 **영어로** 작성하라"는 규칙을 넣고, 계획이 영어로 나오는지 확인하세요. 그리고 이 옵션이 기본 프롬프트를 **대체**한다는 사실 때문에 무엇을 잃었는지 적으세요.
5. 짧은 작업(`"1+1은?"`)을 계획 미들웨어 O/X 로 각각 실행하고 `usage_metadata.input_tokens` 를 비교하세요. 몇 배 차이가 나나요?
6. `write_todos` 의 `status` 에 `"blocked"` 같은 잘못된 값을 넣어 도구를 직접 호출해 보고, 어떤 에러가 나는지 확인하세요. (힌트: 미들웨어의 `tools` 배열에서 도구를 꺼내 `.invoke()` 할 수 있습니다)
7. 계획 항목을 **검증 가능한 산출물**로 쓰게 만드는 `systemPrompt` 를 직접 작성하고, 기본 프롬프트 대비 계획이 어떻게 달라지는지 A/B 로 비교하세요.
8. `result.todos` 의 모든 항목이 `completed` 인데도 **실제 작업이 안 된** 사례를 만들어 보세요. (힌트: 실패하는 도구를 주고, 그 실패를 무시하도록 유도합니다) 이것이 왜 위험한지 적으세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 04 — 가상 파일시스템](../step-04-filesystem/)

계획은 "무엇을 할지"를 붙잡아 둡니다. 하지만 작업이 커지면 **결과물 자체가 컨텍스트를 넘칩니다.** 다음 스텝에서는 파일시스템을 에이전트의 **외부 기억장치**로 쓰는 법을 배웁니다. 계획이 주의를 관리한다면, 파일시스템은 기억을 관리합니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(3-1 ~ 3-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 눈으로 확인하고, `exercise.ts` 의 8개 문제를 직접 푼 뒤, `solution.ts` 로 채점하는 흐름입니다.

실행은 프로젝트 루트에서 `npx tsx docs/reference/deepagent/step-03-planning-todos/practice.ts` 입니다. `ANTHROPIC_API_KEY` 환경변수가 필요하며, `project/.env.example` 을 `.env` 로 복사해 채우면 `import "dotenv/config"` 가 읽어 갑니다. OpenAI 를 쓰려면 모델 문자열을 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하세요.

**검증 버전**: `deepagents@1.11.0`, `langchain@1.5.3`, `@langchain/core@1.2.3`, `@langchain/anthropic@1.5.1`

### practice.ts

본문 강의를 따라가며 실행할 예제를 `[3-1] ~ [3-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 대응하므로, 본문을 읽다 막히면 같은 번호의 블록을 찾아 실행하면 됩니다.

- `printTodos` 헬퍼가 `pending`/`in_progress`/`completed` 를 `[ ]`/`[~]`/`[x]` 로 표시합니다. 계획이 갱신되는 과정을 텍스트로 보기 위한 것이며, 세 상태가 전부라는 것을 타입으로도 못박아 둡니다.
- `[3-1]` 마지막의 `console.log("todos 키 존재?", "todos" in result)` 가 이 절의 핵심입니다. 일반 `createAgent` 의 상태에는 `todos` 키가 **아예 없습니다**. `[3-5]` 에서 같은 코드가 `true` 를 찍는 것과 대조하세요.
- `[3-4]` 와 `[3-8]` 은 `prev` 스냅샷과 비교해 **변한 순간에만** 출력합니다. `streamMode: "values"` 는 매 스텝 상태 전체를 흘리므로, 이 필터가 없으면 같은 todos 가 수십 번 찍힙니다.
- `[3-7]` 은 이 파일에서 유일하게 **모델을 두 번 호출해 비교**하는 블록입니다. `usage_metadata.input_tokens` 필드명은 결정적이므로, 숫자는 달라도 코드는 그대로 동작합니다.
- `main()` 이 `step3_1` 부터 `step3_8` 까지 전부 실행합니다. **API 호출이 10회 이상 발생**하니, 처음에는 필요한 절만 남기고 나머지를 주석 처리하세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 파일입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래 구현부가 비어 있습니다.

- `[문제 2]` 와 `[문제 3]` 은 코드를 많이 쓰는 문제가 아니라 **관찰하고 주석으로 답을 적는** 문제입니다. 파일 안의 `// → (여기에 답)` 자리를 채우세요.
- `[문제 6]` 은 모델을 전혀 호출하지 않습니다. 미들웨어에서 도구를 꺼내 직접 `.invoke()` 하므로 **API 키 없이도** 돌아갑니다. zod 검증이 어디서 일어나는지 보는 문제입니다.
- `[문제 5]` 와 `[문제 7]` 은 같은 작업을 두 설정으로 돌려 비교합니다. 두 번 호출하는 구조를 미리 잡아 두었으니 설정 부분만 채우면 됩니다.
- `[문제 8]` 이 가장 어렵습니다. "성공한 것처럼 보이는 실패"를 **의도적으로 만드는** 문제입니다. 답을 보기 전에 먼저 "모델이 completed 를 찍게 만들려면 무엇을 숨겨야 하나"를 생각해 보세요.
- 파일을 그대로 실행하면 대부분 아무것도 출력되지 않습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 3]` 의 답은 **"대체로 일치하지만 갱신 횟수가 1 적을 수 있다"** 입니다. `streamMode: "values"` 의 첫 청크는 초기 상태(`todos: []`)이고, 스냅샷 비교 방식에 따라 이 초기값을 갱신으로 셀지가 갈립니다. 스트림의 첫 청크가 무엇인지 눈으로 확인하는 게 핵심입니다.
- `[정답 4]` 의 진짜 포인트는 영어 계획이 나오는지가 아닙니다. `systemPrompt` 로 대체하는 순간 **"병렬 호출 금지"와 "3단계 미만이면 쓰지 마라"가 함께 사라진다**는 것입니다. 정답 코드는 `TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT` 를 이어붙인 버전을 나란히 두어 차이를 보여줍니다.
- `[정답 6]` 의 에러는 zod enum 검증 실패입니다. 중요한 건 이 검증이 **도구 실행 전에** 일어난다는 점 — 모델이 잘못된 status 를 보내면 도구 함수 본문은 실행조차 되지 않고 에러가 ToolMessage 로 모델에게 돌아갑니다. 이건 조용히 틀리지 않는 착한 실패입니다.
- `[정답 8]` 이 이 파일의 하이라이트입니다. 항상 `"검색 결과 없음"` 을 반환하는 도구를 주고 "결과가 없으면 넘어가라"고 지시하면, 모델은 아무것도 못 찾았는데도 전 항목을 `completed` 로 표시합니다. **에러도 안 나고 todos 는 100%** 입니다. 본문 3-4 의 함정을 손으로 재현하는 문제이며, 계획 상태를 성공 지표로 쓰면 안 되는 이유가 여기 있습니다.

```ts file="./solution.ts"
```
