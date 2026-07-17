# Step 01 — Deep Agent란 무엇인가

> **학습 목표**
> - 얕은(shallow) 에이전트가 **왜** 긴 작업에서 무너지는지 재현하고 설명한다
> - Deep Agent 의 **4대 기둥**(계획 / 파일시스템 / 서브에이전트 / 시스템 프롬프트)이 각각 어떤 실패를 고치는지 짝지어 말한다
> - `createDeepAgent` 가 **마법이 아니라 `createAgent` + 미들웨어 묶음**임을 코드로 확인한다
> - `createAgent` 와 `createDeepAgent` 를 **언제 무엇을** 쓸지 기준을 세운다
> - **컨텍스트 윈도우가 유한 자원**이라는 사실이 Deep Agent 의 모든 설계를 지배함을 이해한다
>
> **선행 스텝**: [실습 환경 (project/)](../project/)
> **예상 소요**: 50분

에이전트를 처음 만들면 대개 잘 돌아갑니다. "이 도시 날씨 알려 줘" 같은 요청은 도구 한 번 부르고 끝나니까요. 그런데 "리서치 보고서를 써 줘" 처럼 **수십 단계가 필요한 일**을 시키는 순간 무너집니다. 중간에 자기가 뭘 하려 했는지 잊고, 앞에서 찾은 자료를 잃어버리고, 결국 그럴듯하지만 얕은 글 한 덩이를 뱉고 끝냅니다.

Deep Agent 는 이 문제를 풀려고 나온 **에이전트 하네스(harness)** 입니다. 이 스텝에서는 새 API 를 배우지 않습니다. 대신 **얕은 에이전트가 정확히 어디서 무너지는지**를 보고, Deep Agent 가 그 자리에 무엇을 채워 넣는지를 봅니다. 앞으로 11개 스텝에서 배울 모든 것이 여기서 본 4개의 기둥을 하나씩 분해하는 작업입니다.

---

## 1-1. 얕은 에이전트의 한계

`createAgent` 로 에이전트를 하나 만들고 리서치 보고서를 시켜 봅시다. 도구는 주지 않습니다.

```ts
import { createAgent } from "langchain";

const RESEARCH_TASK =
  "LangGraph, CrewAI, AutoGen 세 프레임워크를 비교하는 리서치 보고서를 써 주세요. " +
  "각각의 아키텍처, 상태 관리 방식, 적합한 사용처를 다루고, 마지막에 선택 가이드를 붙여 주세요.";

const shallowAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
});

const result = await shallowAgent.invoke({
  messages: [{ role: "user", content: RESEARCH_TASK }],
});

console.log(`메시지 개수: ${result.messages.length}`);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
메시지 개수: 2
[ 0] human  LangGraph, CrewAI, AutoGen 세 프레임워크를 비교하는 리서치 보고서를 써 주세요. …
[ 1] ai     # 에이전트 프레임워크 비교 보고서 ## 1. 개요 LangGraph는 그래프 기반의 상태 머신…
```

메시지가 **2개**입니다. 사람이 한 번 묻고, AI 가 한 번 답했습니다. 이게 전부입니다.

내용 자체는 그럴듯합니다. 문단도 나뉘어 있고 표도 있습니다. 그런데 이 결과물에는 다음이 **전부 없습니다**.

| 없는 것 | 왜 문제인가 |
|---|---|
| **계획** | 무엇을 조사할지 정하지 않고 바로 쓰기 시작했습니다. 빠뜨린 항목이 있어도 본인이 모릅니다. |
| **자료** | 검색을 안 했습니다. 전부 모델이 학습 때 외운 것이고, 최신이 아닐 수 있으며, 출처가 없습니다. |
| **중간 산출물** | 초안이 없습니다. 한 번에 다 쏟아냈으니 고칠 대상도 없습니다. |
| **검증** | 자기 글을 다시 읽어 본 적이 없습니다. |

가장 중요한 건 **한 턴에 끝났다**는 사실입니다. 모델이 가진 유일한 전략은 "지금 아는 걸 최대한 쏟아내기" 였습니다. 실제로 사람이 이 일을 한다면 자료를 찾고, 메모하고, 초안을 쓰고, 다시 읽고 고칩니다. 얕은 에이전트에겐 **메모할 곳도, 계획을 적을 곳도 없습니다.**

> ⚠️ **함정 — "결과가 나왔으니 성공"이 아니다**
>
> 이 함정이 이 코스 전체의 출발점입니다. 얕은 에이전트는 **에러를 내지 않습니다.** 보고서를 달라면 보고서 모양의 글을 줍니다. 그래서 문제를 발견하기가 어렵습니다.
>
> 에이전트가 실패했는지 보려면 결과물이 아니라 **과정**을 봐야 합니다. `result.messages.length` 가 2 라는 건 "도구를 한 번도 안 썼고, 스스로 점검한 적도 없다"는 뜻입니다. 이 숫자가 앞으로 우리가 볼 첫 번째 계기판입니다.

여기서 흔한 오해 하나를 짚고 갑시다. **"프롬프트를 더 잘 쓰면 되지 않나?"** — 어느 정도는 맞습니다. "먼저 계획을 세우고, 단계별로 진행하세요" 라고 시키면 조금 나아집니다. 하지만 모델이 세운 계획은 **대화 기록 안의 텍스트일 뿐**이라, 대화가 길어지면 앞쪽으로 밀려나 잊힙니다. 계획을 **상태로 저장하고 갱신할 자리**가 없다는 게 구조적 한계입니다. 그 자리를 만들어 주는 게 Deep Agent 입니다.

---

## 1-2. Deep Agent 의 4대 기둥

같은 요청을 `createDeepAgent` 에 던져 봅시다. **도구는 여전히 하나도 안 줍니다.**

```ts
import { createDeepAgent } from "deepagents";

const deepAgent = createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  // tools 없음
});

const deep = await deepAgent.invoke({
  messages: [{ role: "user", content: RESEARCH_TASK }],
});

console.log(`메시지 개수: ${deep.messages.length}`);
console.log(deep.todos);   // ← 계획
console.log(deep.files);   // ← 파일
```

**출력 예시** (모델 응답이므로 매번 다릅니다 — 개수와 파일명은 실행마다 달라집니다)

```
메시지 개수: 34
[ 1] ai     → 도구 호출: write_todos
[ 2] tool   ← write_todos: Updated todo list to [...]
[ 3] ai     → 도구 호출: task
[ 4] tool   ← task: LangGraph는 상태 그래프 기반으로…
[ 5] ai     → 도구 호출: write_file
...
── 에이전트가 세운 계획(todos) ──
  ☑ 세 프레임워크의 아키텍처 조사 (completed)
  ☑ 상태 관리 방식 비교 (completed)
  ▶ 보고서 초안 작성 (in_progress)
  ☐ 선택 가이드 추가 (pending)

── 에이전트가 만든 파일(files) ──
  /report.md 4821자
  /notes.md  1203자
```

메시지가 **2개에서 수십 개로** 늘었습니다. 도구를 하나도 안 줬는데 `write_todos`, `task`, `write_file` 을 부르고 있습니다. 이 넷이 Deep Agent 의 4대 기둥입니다.

### 기둥 1 — 계획 (planning)

`write_todos` 도구가 할 일 목록을 **상태(state)로** 만듭니다. 앞서 말한 "계획이 대화에 묻혀 잊히는" 문제를 고칩니다. 계획이 `todos` 라는 별도 필드에 살아 있으므로, 대화가 아무리 길어져도 밀려나지 않습니다. 상태가 `pending → in_progress → completed` 로 바뀌는 것도 중요합니다 — 에이전트가 **자기가 어디까지 했는지** 매 턴 다시 확인할 수 있습니다.

> **고치는 실패**: "중간에 자기가 뭘 하려 했는지 잊는다"

### 기둥 2 — 파일시스템 (context offloading)

`ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep` 이 **가상 파일시스템**을 다룹니다. 핵심 개념은 **오프로딩(offloading)** 입니다.

검색 결과 3만 토큰을 대화에 그대로 쌓으면 컨텍스트가 순식간에 찹니다. 대신 `/notes.md` 에 써 두고 대화에는 "`/notes.md` 에 저장했음" 한 줄만 남기면, 컨텍스트는 거의 안 쓰면서 내용은 언제든 `read_file` 로 되찾을 수 있습니다. **컨텍스트를 메모리가 아니라 디스크처럼 쓰는 것**입니다.

초안을 파일에 두는 것도 같은 이유입니다. 파일이면 `edit_file` 로 **고칠 수 있습니다.** 대화에 뱉은 글은 고칠 수 없습니다.

> **고치는 실패**: "앞에서 찾은 자료를 잃어버린다", "한 번 뱉으면 못 고친다"

### 기둥 3 — 서브에이전트 (context isolation)

`task` 도구가 **일회용 서브에이전트**를 띄웁니다. 핵심은 **격리(isolation)** 입니다.

"LangGraph 를 조사해" 를 서브에이전트에 맡기면, 그 서브에이전트는 자기만의 **깨끗한 컨텍스트**에서 검색을 20번 하든 파일을 10개 읽든 마음대로 합니다. 그리고 부모에게는 **최종 요약 한 덩이만** 돌려줍니다. 부모의 컨텍스트에는 20번의 검색 기록이 아니라 결과 한 줄만 남습니다.

공식 문서의 표현을 그대로 옮기면 이렇습니다:

> "the main agent receives only the final result, not the dozens of tool calls that produced it"

> **고치는 실패**: "탐색 과정이 컨텍스트를 다 잡아먹는다"

### 기둥 4 — 상세 시스템 프롬프트

위 도구들을 **언제 어떻게 쓰는지**를 가르치는 긴 지침입니다. 도구만 쥐여 주고 "알아서 써" 하면 모델은 잘 안 씁니다. `write_todos` 를 언제 쓰고 언제 쓰지 말아야 하는지, `task` 로 뭘 위임해야 하는지를 수천 자에 걸쳐 설명해 줘야 비로소 제대로 씁니다.

> **고치는 실패**: "도구는 있는데 모델이 안 쓴다"

> 💡 **실무 팁 — 4대 기둥은 서로를 필요로 합니다**
>
> 넷 중 하나만 켜면 효과가 잘 안 납니다. 계획만 있고 파일이 없으면 계획을 실행한 결과를 둘 곳이 없고, 파일만 있고 계획이 없으면 뭘 써야 할지 모릅니다. 서브에이전트만 있고 프롬프트가 부실하면 모델이 위임을 아예 안 합니다.
>
> `createDeepAgent` 의 가치는 개별 기능이 아니라 **넷을 같이 켜 주고 서로 맞물리게 조율해 준다**는 데 있습니다. 그래서 "하네스"라고 부릅니다.

---

## 1-3. Deep Agent = 에이전트 하네스 (마법이 아니다)

여기서 정체를 밝히고 갑시다. `createDeepAgent` 는 **새로운 종류의 에이전트가 아닙니다.** `createAgent` 에 **엄선된 미들웨어 묶음**을 얹은 것입니다. 공식 문서도 스스로를 "agent harness"라고 부릅니다.

증명해 봅시다. 모델을 부르기 **직전에 가로채는** 미들웨어를 하나 만들어서, 이번 호출에 실제로 무엇이 실리는지 찍어 보겠습니다.

```ts
import { createMiddleware } from "langchain";
import { AIMessage } from "@langchain/core/messages";

type Observed = { tools: string[]; systemPrompt: string };

function createSpy(sink: Observed) {
  return createMiddleware({
    name: "Spy",
    wrapModelCall: async (request) => {
      sink.tools = (request.tools ?? []).map((t) => (t as { name: string }).name);
      sink.systemPrompt =
        typeof request.systemPrompt === "string" ? request.systemPrompt : "";
      // handler(request) 를 부르지 않는다 = 진짜 모델을 부르지 않는다.
      return new AIMessage("(스파이가 가로챘습니다 — 모델 호출 없음)");
    },
  });
}
```

`wrapModelCall` 은 "모델을 부르기 직전"에 끼어드는 훅입니다. `request` 안에 이번 호출에 실릴 **도구 목록과 시스템 프롬프트**가 전부 들어 있습니다. 우리는 `handler(request)` 를 부르지 않고 가짜 `AIMessage` 를 돌려줍니다 — 그래서 **모델 호출이 0번**이고, API 키 없이도 돌아가며, 토큰도 안 씁니다.

이제 도구를 하나도 안 준 Deep Agent 에 붙여 봅시다.

```ts
const sink: Observed = { tools: [], systemPrompt: "" };
const agent = createDeepAgent({ model: MODEL, middleware: [createSpy(sink)] });
await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });

console.log(`도구: ${sink.tools.length}개`);
console.log(`시스템 프롬프트 길이: ${sink.systemPrompt.length}자`);
```

**결과** (이 값들은 결정적입니다 — `deepagents@1.11.0` 에서 그대로 재현됩니다)

```
createDeepAgent 가 모델에 실은 도구: 8개
  - edit_file
  - glob
  - grep
  - ls
  - read_file
  - task
  - write_file
  - write_todos

시스템 프롬프트 길이: 6,979자
시스템 프롬프트 앞 120자:
  "You are a Deep Agent, an AI assistant that helps users accomplish tasks using tools. You respond with text and tool call"
```

**`tools: []` 를 줬는데 도구가 8개입니다.** 그리고 시스템 프롬프트를 한 글자도 안 썼는데 **6,979자**가 실려 있습니다.

이게 하네스의 정체입니다. `createDeepAgent` 가 한 일은:

| 미들웨어 | 얹어 주는 것 | 대응하는 기둥 |
|---|---|---|
| `TodoListMiddleware` | `write_todos` 도구 + 사용 지침 | 기둥 1 (계획) |
| `FilesystemMiddleware` | `ls` `read_file` `write_file` `edit_file` `glob` `grep` + 지침 | 기둥 2 (파일) |
| `SubAgentMiddleware` | `task` 도구 + 위임 지침 | 기둥 3 (서브에이전트) |
| `SkillsMiddleware` | 스킬을 필요할 때만 불러오는 장치 | (Step 10) |
| `SummarizationMiddleware` | 컨텍스트가 찰 때 자동 요약 | (Step 08) |
| `PatchToolCallsMiddleware` | 중단된 도구 호출 복구 | (Step 09) |

여기에 Anthropic/Bedrock 모델이면 **프롬프트 캐싱** 미들웨어가 자동으로 더 붙습니다.

> 💡 **실무 팁 — 이 사실이 중요한 이유**
>
> "Deep Agent = createAgent + 미들웨어"를 이해하면 세 가지가 따라옵니다.
>
> 1. **직접 조립할 수 있습니다.** `createFilesystemMiddleware` 같은 함수가 전부 `deepagents` 에서 export 되어 있습니다. 파일시스템만 필요하면 `createAgent` 에 그것만 붙이면 됩니다 (Step 08).
> 2. **디버깅 방법이 같습니다.** Deep Agent 가 이상하게 굴면 "미들웨어 중 누가 그랬나"를 보면 됩니다. 방금 만든 스파이가 바로 그 도구입니다.
> 3. **비용을 예측할 수 있습니다.** 6,979자는 공짜가 아닙니다. 1-5 에서 계산합니다.

> ⚠️ **함정 — 기본 `general-purpose` 서브에이전트가 이미 켜져 있다**
>
> 위 목록의 `task` 도구를 눈여겨보세요. `subagents` 를 **하나도 지정하지 않았는데** `task` 가 실렸습니다. Deep Agent 는 `general-purpose` 라는 기본 서브에이전트를 **자동으로 켭니다.**
>
> 그 서브에이전트의 설명은 이렇습니다(실제 문자열):
>
> ```
> general-purpose: General-purpose agent for researching complex questions,
> searching for files and content, and executing multi-step tasks. …
> This agent has access to all tools as the main agent.
> ```
>
> "메인 에이전트와 **똑같은 도구를 전부** 가진다"는 뜻입니다. 즉 모델은 언제든 `task` 를 불러 **자기와 동급의 에이전트를 통째로 하나 더 띄울 수 있습니다.** 그 서브에이전트도 6,979자 프롬프트를 다시 싣고 시작합니다.
>
> 여기서 예상 못 한 토큰이 샙니다. 간단한 질문에도 모델이 "이건 복잡하니 위임하자"고 판단해 버리면 비용이 몇 배가 됩니다. 에러는 안 납니다 — **청구서에만 나타납니다.** 끄고 켜는 법은 [Step 06](../step-06-subagents/) 에서 다룹니다.

---

## 1-4. `createAgent` vs `createDeepAgent`

같은 스파이를 `createAgent` 에도 붙여서 둘을 나란히 놓아 봅시다.

```ts
const shallowObs = await observe((spy) =>
  createAgent({ model: MODEL, tools: [], middleware: [spy] }),
);
const deepObs = await observe((spy) =>
  createDeepAgent({ model: MODEL, middleware: [spy] }),
);

console.table({
  createAgent: { 도구수: shallowObs.tools.length, 시스템프롬프트길이: shallowObs.systemPrompt.length },
  createDeepAgent: { 도구수: deepObs.tools.length, 시스템프롬프트길이: deepObs.systemPrompt.length },
});
```

**결과** (결정적입니다)

```
┌─────────────────┬────────┬────────────────────┐
│ (index)         │ 도구수 │ 시스템프롬프트길이 │
├─────────────────┼────────┼────────────────────┤
│ createAgent     │ 0      │ 0                  │
│ createDeepAgent │ 8      │ 6979               │
└─────────────────┴────────┴────────────────────┘
```

**0 대 8, 0자 대 6,979자.** 이게 두 함수의 차이 전부입니다. 다른 마법은 없습니다.

### 비교표

| 항목 | `createAgent` | `createDeepAgent` |
|---|---|---|
| 출처 | `langchain` | `deepagents` |
| 기본 도구 | **0개** | **8개** (`ls` `read_file` `write_file` `edit_file` `glob` `grep` `task` `write_todos`) |
| 기본 시스템 프롬프트 | **없음(0자)** | **약 7천 자** |
| 계획 수단 | 없음 (직접 `todoListMiddleware` 추가) | `write_todos` 내장 |
| 파일시스템 | 없음 | 가상 FS 내장 (백엔드 교체 가능) |
| 서브에이전트 | 없음 (직접 구성) | `task` + `general-purpose` 자동 활성화 |
| 자동 요약 | 없음 | `SummarizationMiddleware` 내장 |
| 기본 모델 | 지정 필수 | 생략 시 `claude-sonnet-4-5-20250929` |
| 시작 토큰 비용 | 거의 0 | 약 1,700 토큰 |
| 적합한 작업 | 1~5턴짜리 정해진 일 | 수십 턴짜리 열린 일 |

### 언제 무엇을

**`createAgent` 를 쓰세요:**

- 턴 수가 예측 가능합니다 (도구 한두 번 부르고 끝).
- 무슨 도구를 언제 쓸지 **여러분이** 정해 놨습니다.
- 지연 시간과 토큰이 빠듯합니다. 챗봇, 분류기, 단순 질의응답.
- 흐름을 완전히 통제하고 싶습니다. 에이전트가 멋대로 파일을 쓰거나 서브에이전트를 띄우면 곤란합니다.

**`createDeepAgent` 를 쓰세요:**

- 턴 수를 **예측할 수 없습니다**. 리서치, 코드베이스 분석, 다단계 데이터 처리.
- 작업 중 만들어지는 중간 산출물이 많고, 그걸 다시 읽거나 고쳐야 합니다.
- 탐색이 컨텍스트를 다 잡아먹을 위험이 있습니다.
- 무엇을 해야 할지 **에이전트가 스스로 정해야** 합니다.

> 💡 **실무 팁 — 애매하면 `createAgent` 부터**
>
> "Deep 이 더 좋아 보이니까 Deep 으로 시작하자"는 흔한 실수입니다. 날씨 챗봇에 `createDeepAgent` 를 쓰면 매 요청마다 1,700 토큰을 헛되이 태우고, 모델이 간단한 질문에도 `write_todos` 를 부르며 뜸을 들입니다. 사용자는 "왜 이렇게 느리지?" 합니다.
>
> **작업이 몇 턴 만에 끝나는가**를 먼저 재 보세요. `createAgent` 로 만들었는데 `result.messages.length` 가 계속 20을 넘고 컨텍스트가 부족해지기 시작하면, 그때가 Deep 으로 옮길 때입니다. 반대 방향(Deep → 얕게)으로 가는 리팩터링이 훨씬 귀찮습니다.

> ⚠️ **함정 — 공식 문서는 `await createDeepAgent(...)` 라고 쓰는데, 이 함수는 동기다**
>
> 공식 문서와 이 코스의 예제는 전부 `await createDeepAgent({...})` 로 씁니다. 그래서 "비동기 함수구나, `await` 를 빼면 Promise 에 `.invoke` 를 부르게 되겠구나" 하고 생각하기 쉽습니다. 직접 찍어 보면 그렇지 않습니다.
>
> ```ts
> const ret = createDeepAgent({ model: MODEL });
> console.log(ret instanceof Promise);   // false
> console.log(ret.constructor.name);     // "ReactAgent"
> ```
>
> `deepagents@1.11.0` 의 타입 선언도 `Promise<DeepAgent>` 가 아니라 **`DeepAgent`** 를 반환한다고 되어 있고, `memory` / `skills` / `subagents` 를 줘도 Promise 가 되지 않습니다.
>
> **그래도 `await` 를 붙이세요.** 이유는 두 가지입니다. (1) `await` 를 Promise 가 아닌 값에 붙이면 JS 는 그냥 그 값을 돌려줍니다 — 손해가 없습니다. (2) 문서 표기가 `await` 이므로, 향후 버전이 실제로 비동기가 되어도 여러분 코드는 안 깨집니다.
>
> 진짜 함정은 반대쪽입니다. **`agent.invoke(...)` 앞의 `await` 를 빠뜨리는 것.** 이건 진짜 비동기입니다. 빠뜨리면 `result.messages` 가 `undefined` 라며 엉뚱한 곳에서 터집니다.

---

## 1-5. 컨텍스트 엔지니어링 — 유한 자원이 모든 설계를 지배한다

Deep Agent 의 설계를 이해하는 열쇠는 하나입니다. **컨텍스트 윈도우는 유한한 자원이다.**

RAM 이 무한하면 가비지 컬렉터가 필요 없고, 디스크가 무한하면 압축이 필요 없습니다. 컨텍스트가 무한하면 Deep Agent 도 필요 없습니다. 전부 대화에 쌓아 두면 되니까요. **유한하기 때문에** 계획을 상태로 빼고, 자료를 파일로 밀어내고, 탐색을 서브에이전트에 격리합니다.

방금 잰 6,979자가 얼마인지 계산해 봅시다.

```ts
const roughTokens = (s: string) => Math.round(s.length / 4); // 영어 대략 4자 = 1토큰
const promptTokens = roughTokens(deepObs.systemPrompt);
const WINDOW = 200_000;

console.log(`시스템 프롬프트: 약 ${promptTokens.toLocaleString()} 토큰 (어림)`);
console.log(`→ 사용자가 한 글자도 치기 전에 약 ${((promptTokens / WINDOW) * 100).toFixed(1)}% 를 씁니다.`);
```

**결과**

```
시스템 프롬프트: 약 1,745 토큰 (어림)
컨텍스트 윈도우: 200,000 토큰
→ 사용자가 한 글자도 치기 전에 약 0.9% 를 씁니다.
```

0.9% 는 싸 보입니다. 하지만 이건 **시작값**일 뿐입니다. 실제로 컨텍스트를 먹는 건 이쪽입니다.

| 먹는 놈 | 규모 |
|---|---|
| 시스템 프롬프트 | 약 1,700 토큰 (고정) |
| 도구 스키마 8개 | 수백 토큰 (고정) |
| 검색 결과 1건 | **2,000~10,000 토큰** |
| 읽은 파일 1개 | **1,000~20,000 토큰** |
| 서브에이전트를 안 쓴 탐색 20회 | **100,000 토큰+** |

리서치 한 번에 검색 20번이면 고정 비용은 반올림 오차입니다. **변동 비용이 전부입니다.** 그래서 Deep Agent 의 대응책도 전부 변동 비용을 겨눕니다.

### Deep Agent 의 세 가지 대응책

| 전략 | 무엇을 하는가 | 언제 작동하는가 |
|---|---|---|
| **오프로딩 (offloading)** | 도구 입출력이 크면 파일시스템에 저장하고 대화에는 **참조만** 남긴다 | 도구 결과가 **20,000 토큰**을 넘을 때 |
| **요약 (summarization)** | 대화 전체를 "의도 / 만든 것 / 다음 할 일" 구조로 압축해 원본을 대체한다 | 컨텍스트가 `max_input_tokens` 의 **85%** 에 이를 때 |
| **격리 (isolation)** | 서브에이전트가 자기 컨텍스트에서 일하고 **결과만** 돌려준다 | `task` 도구를 부를 때 |

세 숫자(20,000 / 85% / 10% 보존)는 공식 문서에 명시된 기본값입니다. 요약이 걸리면 최근 토큰의 **10%** 는 남겨 둡니다. 모델 프로필을 못 찾으면 **170,000 토큰**을 기준으로 대신 씁니다.

> ⚠️ **함정 — 요약은 공짜가 아니고, 되돌릴 수 없다**
>
> 85% 에서 자동 요약이 걸리는 건 편리해 보이지만, 요약은 **손실 압축**입니다. 원본 대화가 요약본으로 **대체**되고, 사라진 세부사항은 영영 못 돌아옵니다.
>
> 여기서 조용한 사고가 납니다. 에이전트가 20턴째에 찾아낸 중요한 수치가 요약 과정에서 "관련 자료를 조사했음" 한 줄로 뭉개지면, 30턴째의 에이전트는 **그 수치를 모르는 채로** 보고서를 씁니다. 에러는 없습니다. 그냥 **틀린 보고서**가 나옵니다.
>
> 그래서 4대 기둥 중 **파일시스템이 결정적**입니다. 중요한 걸 파일에 써 두면 요약이 대화를 뭉개도 파일은 그대로 남고, `read_file` 로 언제든 되찾습니다. "중요한 건 대화에 두지 말고 파일에 둬라" — 이게 Deep Agent 를 잘 쓰는 첫 번째 원칙입니다. [Step 04](../step-04-filesystem/) 에서 본격적으로 다룹니다.

> 💡 **실무 팁 — 컨텍스트를 예산처럼 다루세요**
>
> 숙련된 에이전트 개발자는 "이 작업에 컨텍스트 예산이 얼마인가"를 먼저 생각합니다. 200,000 토큰 중 시스템이 2,000 을 쓰고, 요약이 170,000 에서 걸린다면, 실질 작업 공간은 약 168,000 토큰입니다. 검색 한 번에 5,000 토큰이면 **33번**이 한계입니다.
>
> 이 계산을 해 두면 설계가 달라집니다. 33번으로 부족하면 검색을 서브에이전트에 격리해야 합니다. 그러면 부모 컨텍스트에는 요약 500 토큰만 남으니 **330번**도 가능해집니다. 이런 판단을 하려면 지금 뭐가 컨텍스트를 먹고 있는지 봐야 하고, 그 관측 도구가 바로 [Step 11](../step-11-streaming-production/) 의 LangSmith 입니다.

---

## 1-6. 코스 로드맵

이 스텝에서 본 4대 기둥을 앞으로 하나씩 분해합니다.

| Step | 주제 | 이 스텝과의 관계 |
|---|---|---|
| **01** | Deep Agent란 무엇인가 | ← 지금 여기 |
| [**02**](../step-02-quickstart/) | 첫 Deep Agent | 옵션 전체 지도. 어느 스텝에서 뭘 다루는지 |
| [**03**](../step-03-planning-todos/) | 계획 도구 (`write_todos`) | **기둥 1** 분해 |
| [**04**](../step-04-filesystem/) | 가상 파일시스템 | **기둥 2** 분해 — 오프로딩 |
| [**05**](../step-05-backends/) | 백엔드와 권한 | 파일이 실제로 어디에 저장되는가 |
| [**06**](../step-06-subagents/) | 서브에이전트 | **기둥 3** 분해 — 격리. `general-purpose` 끄기 |
| [**07**](../step-07-prompting/) | 시스템 프롬프트 설계 | **기둥 4** 분해. 6,979자를 뜯어본다 |
| [**08**](../step-08-middleware/) | 미들웨어 조합 | 1-3 에서 본 하네스를 **직접 조립** |
| [**09**](../step-09-hitl-permissions/) | HITL과 권한 제어 | 위험한 도구 실행 전에 사람이 승인 |
| [**10**](../step-10-memory-skills/) | 장기 메모리와 스킬 | 스레드를 넘어 기억하기 |
| [**11**](../step-11-streaming-production/) | 스트리밍과 프로덕션 | 안에서 무슨 일이 벌어지는지 보기 |
| [**12**](../step-12-final-project/) | 종합 — 딥 리서치 에이전트 | 1-1 의 실패한 요청을 **제대로** 해내기 |

Step 12 에서 다시 만날 요청이 바로 1-1 의 그 리서치 보고서입니다. 그때는 계획을 세우고, 자료를 파일에 모으고, 조사를 서브에이전트에 나눠 맡기고, 초안을 고쳐 쓰는 에이전트가 되어 있을 겁니다.

---

## 정리

| 개념 | 한 줄 |
|---|---|
| 얕은 에이전트 | 도구도 메모장도 없이 한 턴에 다 쏟아낸다. **에러 없이** 얕은 결과를 낸다 |
| Deep Agent | `createAgent` + 엄선된 미들웨어 묶음 = **하네스**. 마법 아님 |
| 기둥 1 — 계획 | `write_todos`. 계획을 **상태**로 만들어 대화에 묻히지 않게 |
| 기둥 2 — 파일시스템 | `read_file`/`write_file` 등. 큰 자료를 **오프로딩**하고 초안을 고칠 수 있게 |
| 기둥 3 — 서브에이전트 | `task`. 탐색을 **격리**해 부모 컨텍스트를 지킨다 |
| 기둥 4 — 시스템 프롬프트 | 약 7천 자. 도구를 **언제 쓰는지** 가르친다 |
| 컨텍스트 엔지니어링 | 윈도우는 유한 자원. 오프로딩 / 요약 / 격리가 그 답 |

**핵심 함정 3가지**

1. **"결과가 나왔으니 성공"이 아니다.** 얕은 에이전트는 에러 없이 얕은 결과를 냅니다. 결과물이 아니라 **과정**(`messages.length`, `todos`, `files`)을 보세요.
2. **기본 `general-purpose` 서브에이전트가 이미 켜져 있다.** `subagents` 를 안 줘도 `task` 가 실리고, 모델이 자기와 동급의 에이전트를 띄워 프롬프트 7천 자를 다시 태울 수 있습니다. 에러 대신 **청구서**로 나타납니다.
3. **자동 요약은 손실 압축이고 되돌릴 수 없다.** 85% 에서 걸리면 원본 대화가 요약본으로 대체됩니다. 중요한 건 대화가 아니라 **파일**에 두세요.

**버전 특이사항**: `createDeepAgent` 는 문서 표기와 달리 `deepagents@1.11.0` 에서 **동기 함수**입니다(`Promise` 를 반환하지 않음). 그래도 문서를 따라 `await` 를 붙이는 편이 안전합니다 — 손해가 없고, 향후 버전 변경에도 안 깨집니다.

---

## 연습문제

1. `createAgent` 로 "피보나치 수열 10번째 항을 구해 줘" 를 시키고 `result.messages.length` 를 찍으세요. 이어서 같은 요청을 `createDeepAgent` 로 시키고 두 숫자를 비교하세요. **어느 쪽이 이 작업에 적합한가요?** 이유를 주석으로 적으세요.
2. 본문의 `createSpy` 를 그대로 써서, `createDeepAgent({ tools: [myTool] })` 처럼 **커스텀 도구를 1개** 줬을 때 모델에 실리는 도구가 몇 개인지 찍으세요. 커스텀 도구는 내장 도구를 **대체하나요, 더해지나요?**
3. `createSpy` 로 `createDeepAgent` 의 시스템 프롬프트 전문을 파일로 저장한 뒤(`fs.writeFileSync`), `write_todos` / `task` / `read_file` 이라는 단어가 각각 몇 번 등장하는지 세세요. **어느 기둥에 가장 많은 지면을 할애했나요?**
4. `createDeepAgent({ model: MODEL })` 의 반환값에 대해 `instanceof Promise` 와 `constructor.name` 을 찍어, 본문 1-4 의 함정을 직접 재현하세요. 이어서 `await` 를 붙인 결과와 안 붙인 결과가 **같은지** 확인하세요.
5. (컨텍스트 예산) 컨텍스트 윈도우가 200,000 토큰이고 자동 요약이 170,000 에서 걸린다고 할 때, 검색 결과 1건이 6,000 토큰이면 요약 전에 검색을 **몇 번** 할 수 있나요? 시스템 프롬프트 약 1,700 토큰을 빼고 계산하는 코드를 쓰세요.
6. (설계 판단) 다음 세 작업에 `createAgent` 와 `createDeepAgent` 중 무엇을 쓸지 고르고, 이유를 한 줄씩 적으세요.
   - (a) 고객 문의를 "환불 / 배송 / 기타" 셋 중 하나로 분류
   - (b) 저장소 전체를 읽고 아키텍처 문서를 작성
   - (c) 사내 위키를 검색해 질문에 답하는 챗봇
7. (기둥 짝짓기) 아래 실패 증상 4개를 4대 기둥 중 하나씩에 짝지으세요.
   - (a) 검색 결과를 15개 쌓았더니 컨텍스트가 넘쳤다
   - (b) 30턴째에 처음 세운 계획을 잊고 엉뚱한 걸 하고 있다
   - (c) 도구는 다 붙여 줬는데 모델이 한 번도 안 부른다
   - (d) 초안을 뱉었는데 고쳐 달라니까 처음부터 다시 쓴다
8. (심화) `createSpy` 를 고쳐서 `handler(request)` 를 **실제로 호출하되** 도구 목록과 프롬프트 길이도 찍게 만드세요. 그리고 `RUN_LIVE=1` 로 1-2 를 돌려, 실행 중 모델이 **몇 번** 호출되고 매번 프롬프트 길이가 어떻게 변하는지 관찰하세요. (힌트: 대화가 길어지면 프롬프트가 아니라 messages 가 커집니다)

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 02 — 첫 Deep Agent](../step-02-quickstart/)

`createDeepAgent` 의 옵션 16개 전체 지도를 펼치고, 어느 스텝에서 무엇을 다루는지 짚습니다. 그리고 이 스텝에서 스파이로 훔쳐본 것들을 정식으로 다뤄 봅니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(1-1 ~ 1-6)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `project/` 폴더에서 실행합니다.

```bash
cd docs/reference/deepagent/project
npx tsx ../step-01-why-deep-agents/practice.ts
```

**이 스텝의 실습 파일은 대부분 API 키 없이 돌아갑니다.** 1-3 에서 만든 스파이 미들웨어가 모델 호출을 가로채기 때문입니다. 실제 호출이 필요한 `[1-1]` `[1-2]` 는 기본적으로 꺼져 있고, 켜려면 앞에 `RUN_LIVE=1` 을 붙입니다.

```bash
RUN_LIVE=1 npx tsx ../step-01-why-deep-agents/practice.ts   # 실제 API 호출 (토큰 소모)
```

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[1-1] ~ [1-6]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[1-3]` 의 `createSpy` 가 이 파일의 심장입니다. `wrapModelCall` 안에서 **`handler(request)` 를 부르지 않는 것**이 핵심입니다 — 그래서 모델 호출이 0번이고 토큰이 안 듭니다. 주석에도 적어 뒀지만, `wrapModelCall` 은 반드시 `AIMessage` 나 `Command` 를 돌려줘야 합니다. `{ result: [...] }` 같은 객체를 돌려주면 `MiddlewareError: … expected AIMessage or Command, got object` 가 납니다.
- `observe()` 헬퍼의 `Invokable` 타입이 `invoke` 를 **메서드 표기**로 선언한 것은 의도적입니다. 프로퍼티 표기(`{ invoke: (i: unknown) => … }`)로 쓰면 `strictFunctionTypes` 가 인자를 반공변으로 검사해서 `createAgent` 와 `createDeepAgent` 를 같은 자리에 못 넣습니다. 바꿔 보면 바로 타입 에러가 나니 한번 해 보세요.
- `[1-4]` 의 `console.table` 이 `0 / 0` 과 `8 / 6979` 를 나란히 보여 줍니다. 이 네 숫자가 이 스텝의 결론입니다.
- `[1-4]` 끝의 `ret instanceof Promise` → `false` 는 본문의 함정을 직접 재현하는 부분입니다. 공식 문서가 `await createDeepAgent(...)` 라고 쓰는 것과 실제 동작이 다르다는 걸 눈으로 확인시킵니다.
- 파일 맨 끝의 "참고" 블록은 커스텀 도구를 1개 줬을 때 도구가 **9개**(8+1)가 되는 걸 보여 줍니다. 연습문제 2번의 답이 여기 있으니, 문제를 풀기 전이라면 스크롤을 멈추세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래가 비어 있으니, 거기에 직접 코드를 써 넣고 파일을 실행해 검증하면 됩니다.

- 파일 상단에 `createSpy` 와 `observe` 가 **이미 준비되어 있습니다.** 문제 2, 3, 8 은 이걸 그대로 쓰면 됩니다. 스파이를 처음부터 다시 만들 필요 없습니다.
- `[문제 5]` `[문제 6]` `[문제 7]` 은 코드가 아니라 **계산과 판단**을 묻습니다. 5번만 코드로 풀고, 6번과 7번은 주석으로 답을 적으면 됩니다.
- `[문제 1]` 과 `[문제 8]` 은 **실제 API 호출이 필요합니다.** 나머지는 키 없이 풀립니다.
- 파일을 그대로 실행하면 문제 번호만 출력되고 결과는 안 나옵니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 2]` 의 답은 **9개**입니다. 커스텀 도구는 내장 8개를 **대체하지 않고 더해집니다.** 이건 Deep Agent 의 기본 도구를 끌 수 없다는 뜻이기도 합니다 — 끄려면 `createAgent` 로 내려가 미들웨어를 직접 고르는 수밖에 없습니다(Step 08).
- `[정답 3]` 이 이 파일에서 가장 재미있는 대목입니다. 6,979자 프롬프트에서 `task` 관련 서술이 압도적으로 깁니다. 서브에이전트 위임은 모델이 **가장 안 하려는 행동**이라 가장 많이 설득해야 하기 때문입니다. 반대로 `write_todos` 는 짧습니다 — 모델이 이미 계획 세우기를 좋아합니다.
- `[정답 5]` 의 답은 **28회**입니다. `(170,000 − 1,700) ÷ 6,000 = 28.05`. 이 숫자가 작다는 게 요점입니다. 서브에이전트로 격리하면 부모 컨텍스트에는 요약만 남으므로 훨씬 많이 할 수 있습니다.
- `[정답 6]` 의 답은 (a) `createAgent`, (b) `createDeepAgent`, (c) `createAgent` 입니다. (c) 가 헷갈릴 수 있는데, 위키 검색 챗봇은 "검색 → 답변" 2턴이면 끝나므로 얕은 쪽이 맞습니다. 만약 "위키를 다 뒤져서 종합 리포트를 써라" 였다면 Deep 입니다.
- `[정답 7]` 의 짝은 (a) 기둥 3(서브에이전트/격리), (b) 기둥 1(계획), (c) 기둥 4(시스템 프롬프트), (d) 기둥 2(파일시스템) 입니다. (d) 를 기둥 4로 착각하기 쉬운데, "고칠 수 있으려면 고칠 대상이 파일로 있어야 한다"가 핵심입니다.

```ts file="./solution.ts"
```
