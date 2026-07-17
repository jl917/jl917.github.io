# Step 07 — 시스템 프롬프트 설계

> **학습 목표**
> - Deep Agent 의 네 번째 기둥이 왜 "상세한 시스템 프롬프트"인지 설명한다
> - `systemPrompt` 문자열이 내장 프롬프트를 **교체하지 않고 앞에 붙는다**는 것을 직접 증명한다
> - `{ prefix, base, suffix }` 구조체로 내장 프롬프트를 교체하거나 제거한다
> - 내장 프롬프트와 미들웨어가 덧붙이는 도구 지침을 뜯어보고 어느 겹이 내 소관인지 구분한다
> - 역할·워크플로·도구 규칙·출력 형식·중단 조건의 다섯 요소로 프롬프트를 작성한다
> - 하네스 프로파일(`registerHarnessProfile`)로 모델별 기본값을 호출부 밖에서 관리한다
> - 실패 케이스를 테스트로 박제하고 규칙을 한 줄씩 추가하는 개선 루프를 돌린다
>
> **선행 스텝**: [Step 06 — 서브에이전트](../step-06-subagents/)
> **예상 소요**: 75분

Step 03 부터 06 까지 우리는 Deep Agent 의 세 기둥을 만들었습니다. 계획 도구(`write_todos`), 가상 파일시스템, 서브에이전트. 셋 다 **도구**였습니다. 그런데 도구를 쥐여준다고 에이전트가 그 도구를 잘 쓰지는 않습니다. `write_todos` 를 붙여놔도 모델이 안 부르면 없는 것과 같고, 파일시스템이 있어도 모델이 "굳이 파일에 쓸 이유" 를 모르면 그냥 컨텍스트에 다 쏟아붓다 터집니다.

네 번째 기둥이 그래서 필요합니다. **상세한 시스템 프롬프트**. 이건 "친절하게 대답해줘" 같은 인격 설정이 아니라, **도구를 언제 어떻게 쓸지 가르치는 운영 매뉴얼**입니다. 이 스텝에서는 deepagents 가 이미 써 놓은 매뉴얼이 무엇인지 직접 뜯어보고, 그 위에 내 것을 어떻게 얹는지, 그리고 언제 통째로 걷어내야 하는지를 다룹니다. 특히 `systemPrompt` 를 주면 내장 프롬프트가 대체된다는 **흔한 오해**를 코드로 깨는 것이 이 스텝의 절반입니다.

> **검증 버전**: `deepagents` 1.11.0 / `langchain` 1.5.3 / `@langchain/anthropic` 1.5.1 / Node.js 22
> 이 스텝의 프롬프트 원문·조립 순서·상수값은 모두 `deepagents@1.11.0` 패키지에서 직접 확인한 것입니다.

---

## 7-1. 네 번째 기둥 — 왜 프롬프트가 이렇게 긴가

Claude Code 류의 코딩 에이전트를 뜯어보면 시스템 프롬프트가 수천 자입니다. 처음 보면 과해 보입니다. "모델이 똑똑하니까 알아서 하지 않나?"

안 합니다. 이유는 세 가지입니다.

**1. 모델은 당신의 도구가 처음입니다.** 모델은 `get_stock_price` 를 학습한 적이 없습니다. 도구 설명(description)만 보고 언제 부를지 추론해야 합니다. 설명이 부실하면 안 부르거나, 엉뚱한 때 부릅니다.

**2. 실패 경로가 정의되지 않으면 모델은 지어냅니다.** 도구가 `NOT_FOUND` 를 반환했을 때 뭘 해야 하는지 안 알려주면, 모델은 "사용자를 돕는 것" 이 목표라고 믿기 때문에 그럴듯한 답을 **만들어냅니다.** 환각의 절반은 모델 탓이 아니라 프롬프트가 실패 경로를 안 적어서 생깁니다.

**3. 중단 조건이 없으면 안 멈춥니다.** "충분히 조사했으면 멈춰라" 를 안 적으면 에이전트는 같은 검색을 다섯 번 하고, 이미 읽은 파일을 또 읽습니다. 토큰과 시간이 그대로 요금입니다.

Deep Agent 는 이 셋이 전부 증폭됩니다. 도구가 많고(`ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`, `task`, `write_todos` + 내 도구들), 루프가 길고, 서브에이전트까지 스폰합니다. 그래서 긴 프롬프트가 필요합니다.

좋은 소식은, **그 긴 프롬프트의 대부분을 deepagents 가 이미 써 뒀다**는 것입니다. 우리가 할 일은 그 위에 도메인 지식을 얹는 것입니다. 나쁜 소식은, 그 사실을 모르면 **내가 쓴 프롬프트가 그걸 지웠다고 착각**한다는 것입니다.

```ts
import { createDeepAgent } from "deepagents";

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getStockPrice],
  systemPrompt: "당신은 주식 리서치 어시스턴트입니다.",
});
```

이 에이전트의 실제 시스템 프롬프트는 몇 자일까요? 위 문자열은 20자쯤입니다. 실제로는 **5천 자가 넘습니다.** 왜 그런지가 다음 절입니다.

> 💡 **실무 팁**: OpenAI 를 쓴다면 `model: "openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하면 됩니다. 이 스텝의 프롬프트 조립 규칙은 프로바이더와 무관하게 동일합니다. 다만 7-7 의 하네스 프로파일은 프로바이더별로 다른 기본값을 붙일 수 있으므로, 모델을 바꾸면 최종 프롬프트가 미세하게 달라질 수 있습니다.

---

## 7-2. 프롬프트 조립 구조 — `systemPrompt` 는 prefix 다

`createDeepAgent` 는 최종 시스템 프롬프트를 **여러 조각을 이어 붙여** 만듭니다. `systemPrompt` 에 문자열을 주면 그것은 내부에서 이렇게 정규화됩니다.

```ts
// deepagents 내부 (개념적으로)
function normalizeSystemPrompt(systemPrompt) {
  if (systemPrompt === undefined) return {};
  if (typeof systemPrompt === "string" || SystemMessage.isInstance(systemPrompt))
    return { prefix: systemPrompt };   // ← 문자열은 prefix 가 된다
  return systemPrompt;
}
```

**문자열 = `{ prefix: 문자열 }`.** 교체가 아닙니다. 조립 순서는 이렇습니다.

```
prefix  →  base  →  suffix  →  하네스 프로파일 systemPromptSuffix
```

각 조각은 빈 줄 두 개(`"\n\n"`)로 이어집니다. 그리고 **여기서 끝이 아닙니다.** 미들웨어가 런타임에 도구 지침을 뒤에 더 붙입니다. 전체 그림은 이렇습니다.

| 겹 | 내용 | 누가 붙이나 | 내가 제어 가능한가 |
|---|---|---|---|
| 1 | `prefix` | 내 `systemPrompt` | ✅ 완전히 |
| 2 | `base` (기본값 = `BASE_AGENT_PROMPT`) | `createDeepAgent` | ✅ 교체/제거 가능 |
| 3 | `suffix` | 내 `systemPrompt` | ✅ 완전히 |
| 4 | 프로파일 `systemPromptSuffix` | `registerHarnessProfile` | ✅ 7-7 참고 |
| 5 | todo 사용 지침 | `todoListMiddleware` | ⚠️ 미들웨어 교체로만 |
| 6 | `## Filesystem Tools ...` | `FilesystemMiddleware` | ⚠️ `systemPrompt` 옵션으로 |
| 7 | `` ## `task` (subagent spawner) `` | `SubAgentMiddleware` | ⚠️ `systemPrompt` 옵션으로 |

1~4 는 에이전트를 **만들 때** 문자열로 조립되고, 5~7 은 **모델을 부를 때마다** 미들웨어가 `request.systemMessage` 에 덧붙입니다. 이 구분이 7-3 함정의 핵심입니다.

### 직접 눈으로 보기 — 스파이 미들웨어

최종 프롬프트를 볼 공식 게터는 없습니다. 대신 미들웨어의 `wrapModelCall` 훅이 모델 호출 직전의 `request` 를 그대로 넘겨주므로, 이걸로 가로채면 됩니다. 이 스텝 내내 쓸 도구입니다.

```ts
import { createMiddleware } from "langchain";

function makePromptSpy(label: string) {
  return createMiddleware({
    name: "PromptSpyMiddleware",
    wrapModelCall: async (request, handler) => {
      const text = request.systemMessage.text ?? "";
      console.log(`===== [${label}] 최종 시스템 프롬프트 (${text.length}자) =====`);
      console.log(text);
      return handler(request);
    },
  });
}

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: "### MY-PREFIX-MARKER ###\n당신은 주식 리서치 어시스턴트입니다.",
  middleware: [makePromptSpy("7-2")],
});

await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
```

**출력** (프롬프트 원문은 라이브러리 상수라 결정적입니다. 길이는 버전에 따라 달라집니다)

```
===== [7-2] 최종 시스템 프롬프트 (5000자 내외) =====
### MY-PREFIX-MARKER ###
당신은 주식 리서치 어시스턴트입니다.

You are a Deep Agent, an AI assistant that helps users accomplish tasks using tools. ...

## Core Behavior
...
## Filesystem Tools `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`
...
## `task` (subagent spawner)
...
```

내 마커가 **맨 앞(index 0)** 에 있고, 그 뒤로 `You are a Deep Agent...` 가 **멀쩡히 살아있습니다.** 내가 쓴 20자가 5천 자를 대체한 게 아니라, 5천 자 앞에 20자가 붙은 것입니다.

> ⚠️ **함정**: `systemPrompt` 를 주면 내장 프롬프트가 **교체된다고 착각**하기 쉽습니다. LangChain 의 `createAgent({ systemPrompt })` 는 실제로 교체이기 때문에 더 헷갈립니다. `createDeepAgent` 만 다릅니다 — 여기서는 **prefix 로 추가**입니다. 그래서 "간결하게만 답해" 라고 썼는데 에이전트가 장황하게 파일을 뒤지고 서브에이전트를 스폰한다면, 그건 모델이 말을 안 듣는 게 아니라 **뒤에 붙어있는 5천 자가 그렇게 하라고 시키고 있는** 것입니다. 내 prefix 와 내장 base 가 **충돌**하면 대개 더 구체적이고 긴 쪽(내장)이 이깁니다.

---

## 7-3. `{ prefix, base, suffix }` — 구조체로 완전 제어

문자열 대신 구조체를 주면 각 겹을 개별 제어할 수 있습니다. 실제 타입은 이렇습니다.

```ts
interface SystemPromptConfig {
  /** base 프롬프트 앞에 놓일 내용 */
  prefix?: string | SystemMessage | null;
  /**
   * 활성 base 프롬프트의 교체물.
   * 이 필드를 생략하면 하네스 프로파일 base 또는 내장 base 프롬프트가 유지된다.
   * null 로 두면 base 프롬프트를 완전히 생략한다.
   */
  base?: string | SystemMessage | null;
  /** base 뒤, 하네스 프로파일 suffix 앞에 놓일 내용 */
  suffix?: string | SystemMessage | null;
}

// createDeepAgent 의 시그니처
systemPrompt?: string | SystemMessage | SystemPromptConfig;
```

`base` 필드는 **세 가지 상태**를 가집니다.

| `base` 값 | 결과 |
|---|---|
| 생략 (필드 자체가 없음) | 내장 `BASE_AGENT_PROMPT` **유지** (기본 동작) |
| `"내 문자열"` | 내장 base 를 내 것으로 **교체** |
| `null` | base 를 **완전히 제거** |

```ts
// (A) base 교체 — 내장 인격은 버리고 내 매뉴얼로 대체
const replaced = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: {
    prefix: "### PREFIX ###",
    base: "### 내가 직접 쓴 BASE 프롬프트. 내장 Deep Agent 프롬프트는 사라졌다. ###",
    suffix: "### SUFFIX ###",
  },
});

// (B) base 제거 — 내장 base 없이 내 prefix 만
const removed = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: {
    prefix: "당신은 사내 규정만 인용해 답하는 컴플라이언스 봇입니다. 추측 금지.",
    base: null,
  },
});
```

### `base: null` 이 지우지 **못하는** 것

여기가 이 절의 핵심입니다. `base: null` 을 준 에이전트의 최종 프롬프트를 스파이로 찍어보면:

```
당신은 사내 규정만 인용해 답하는 컴플라이언스 봇입니다. 추측 금지.

## Filesystem Tools `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`

You have access to a filesystem which you can interact with using these tools.
All file paths must start with a /.
...

## `task` (subagent spawner)

You have access to a `task` tool to launch short-lived subagents ...
```

`You are a Deep Agent...` 는 사라졌습니다. 하지만 **파일 지침과 task 지침은 그대로 있습니다.**

> ⚠️ **함정 (이 절의 핵심)**: `base: null` 은 **`createDeepAgent` 가 붙이는 한 겹만** 지웁니다. 미들웨어(`FilesystemMiddleware`, `SubAgentMiddleware`, `todoListMiddleware`)가 붙이는 도구 지침은 **조립 단계 밖**, 즉 매 모델 호출마다 `wrapModelCall` 에서 `request.systemMessage` 에 덧붙는 것이라 전혀 영향을 받지 않습니다. "완전 제어" 라는 말을 "프롬프트에 내 글자만 남는다" 로 이해하면 틀립니다. 도구 지침까지 없애려면 미들웨어 레벨에서 손대야 하고, 그건 [Step 08](../step-08-middleware/) 의 주제입니다.

> ⚠️ **함정 (`null` vs `undefined`)**: 타입이 `base?: string | SystemMessage | null` 이라 `undefined` 도 통과합니다. 그런데 내부 판정은 `promptConfig.base !== undefined` 입니다.
> - `base: null` → 제거 ✅
> - `base: undefined` → **"생략한 것" 으로 취급되어 내장 base 가 그대로 남습니다** ❌
>
> 설정 객체를 스프레드로 조립할 때(`{ ...defaults, base: maybeNull }`) `maybeNull` 이 `undefined` 로 흘러들면 에러 하나 없이 내장 프롬프트가 부활합니다. 두 에이전트의 프롬프트 길이를 찍어보면 2천 자쯤 차이가 나는데, 이걸 안 찍어보면 영영 모릅니다.

### 언제 `base: null` 을 써야 하나

기본은 **쓰지 않는 것**입니다. 내장 base 는 좋은 프롬프트고, 공짜로 얻는 품질입니다. 다음 경우에만 걷어내세요.

| 상황 | 권장 |
|---|---|
| 도메인 지식을 더하고 싶다 | `prefix` 만 (base 유지) — **90% 의 경우** |
| 말투/언어만 바꾸고 싶다 | `suffix` 또는 프로파일 `systemPromptSuffix` |
| 내장 base 의 규칙과 내 규칙이 정면 충돌한다 | `base` 교체 |
| 규제 산업이라 모델에게 가는 모든 문장을 감사(audit)해야 한다 | `base: null` |
| 프롬프트 토큰을 극단적으로 줄여야 한다 (초경량 모델) | `base: null` |
| 에이전트가 "Deep Agent" 로서가 아니라 아주 좁은 역할만 해야 한다 | `base: null` + 짧은 prefix |

> 💡 **실무 팁**: `base: null` 로 시작해서 프롬프트를 처음부터 쓰는 건 거의 항상 손해입니다. 내장 base 의 "Verify — 자기 출력이 아니라 요구사항에 비추어 검증하라", "첫 시도는 대개 틀리니 반복하라" 같은 문장은 에이전트 품질에 실제로 기여합니다. 먼저 `prefix` 만으로 3~4번 반복 개선(7-8)해 보고, 그래도 내장 base 가 방해가 될 때만 걷어내세요. 걷어낼 때는 **내장 base 를 복사해와서 필요한 문장만 지우는** 편이 백지에서 쓰는 것보다 낫습니다.

---

## 7-4. 내장 프롬프트가 실제로 뭘 시키는가

내가 상속받는 것이 뭔지 모르면 위에 뭘 얹을지도 모릅니다. 뜯어봅시다.

### 겹 2 — `BASE_AGENT_PROMPT`

`createDeepAgent` 가 붙이는 base 프롬프트의 실제 구조입니다 (`deepagents@1.11.0`).

```
You are a Deep Agent, an AI assistant that helps users accomplish tasks using tools.
You respond with text and tool calls. The user can see your responses and tool outputs in real time.

## Core Behavior
- Be concise and direct. Don't over-explain unless asked.
- NEVER add unnecessary preamble ("Sure!", "Great question!", "I'll now...").
- Don't say "I'll now do X" — just do it.
- If the request is ambiguous, ask questions before acting.
- If asked how to approach something, explain first, then act.

## Professional Objectivity
- Prioritize accuracy over validating the user's beliefs
- Disagree respectfully when the user is incorrect
- Avoid unnecessary superlatives, praise, or emotional validation

## Doing Tasks
1. **Understand first** — read relevant files, check existing patterns. ...
2. **Act** — implement the solution. Work quickly but accurately.
3. **Verify** — check your work against what was asked, not against your own output.
   Your first attempt is rarely correct — iterate.

Keep working until the task is fully complete. Don't stop partway and explain what you
would do — just do it. Only yield back to the user when the task is done or you're
genuinely blocked.

**When things go wrong:**
- If something fails repeatedly, stop and analyze *why* — don't keep retrying the same approach.
- If you're blocked, tell the user what's wrong and ask for guidance.

## Progress Updates
For longer tasks, provide brief progress updates at reasonable intervals ...
```

읽어보면 이건 **인격 설정이 아니라 작업 규율**입니다. 다섯 요소(7-5)에 대입해 보면 정확히 들어맞습니다.

| 요소 | 내장 base 의 해당 부분 |
|---|---|
| 역할 | "You are a Deep Agent ... helps users accomplish tasks using tools" |
| 워크플로 | "Understand first → Act → Verify" |
| 도구 사용 규칙 | (여기엔 없음 — 미들웨어가 겹 5~7 로 채운다) |
| 출력 형식 | "Be concise", "NEVER add unnecessary preamble" |
| 중단 조건 | "Keep working until the task is fully complete", "Only yield back when done or genuinely blocked" |

**"도구 사용 규칙" 칸이 비어 있는 것**에 주목하세요. base 는 도구를 모릅니다. 도구별 지침은 그 도구를 등록한 미들웨어가 붙입니다. 구조가 이렇게 나뉜 이유입니다.

### 겹 5 — todo 사용 지침 (`todoListMiddleware`)

`write_todos` 도구의 설명(description)이 곧 지침입니다. 요지는 이렇습니다.

```
Only use this tool if you think it will be helpful in staying organized.
If the user's request is trivial and takes less than 3 steps, it is better to NOT use
this tool and just do the task directly.

## When to Use This Tool
1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
3. User explicitly requests todo list
4. User provides multiple tasks
5. The plan may need future revisions or updates based on results from the first few steps.

## How to Use This Tool
1. When you start working on a task - Mark it as in_progress BEFORE beginning work.
2. After completing a task - Mark it as completed and add any new follow-up tasks discovered ...

## When NOT to Use This Tool
1. There is only a single, straightforward task
2. The task is trivial and tracking it provides no benefit
3. The task can be completed in less than 3 trivial steps
4. The task is purely conversational or informational
```

"3단계 미만이면 쓰지 마라" 가 못박혀 있습니다. [Step 03](../step-03-planning-todos/) 에서 "간단한 질문엔 todo 가 안 뜬다" 고 했던 것의 출처가 바로 이 문장입니다.

### 겹 6 — 파일 사용 지침 (`FilesystemMiddleware`)

```
## Filesystem Tools `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`

You have access to a filesystem which you can interact with using these tools.
All file paths must start with a /.

- Lists all files in a directory. ... You should almost ALWAYS use this tool before
  using the read_file or edit_file tools.
- Reads a file from the filesystem. ...
  **IMPORTANT for large files and codebase exploration**: Use pagination with offset
  and limit parameters to avoid context overflow
  ...
```

이 지침은 **동적으로 생성**됩니다. 헤더의 도구 목록이 "현재 모델에게 실제로 보이는 도구" 로 채워지고, 아래 불릿도 그 도구들만 나열됩니다. 즉 `createFilesystemMiddleware({ tools: ["read_file", "ls"] })` 로 도구를 줄이면 **지침도 같이 줄어듭니다.** 도구를 뺐는데 지침엔 남아서 모델이 없는 도구를 부르는 사고를 구조적으로 막아둔 것입니다.

"All file paths must start with a /" 도 여기 있습니다. [Step 04](../step-04-filesystem/) 에서 상대경로를 주면 에러가 났던 이유입니다.

### 겹 7 — 서브에이전트 위임 지침 (`SubAgentMiddleware`)

`TASK_SYSTEM_PROMPT` 입니다.

```
## `task` (subagent spawner)

You have access to a `task` tool to launch short-lived subagents that handle isolated
tasks. These agents are ephemeral — they live only for the duration of the task and
return a single result.

When to use the task tool:
- When a task is complex and multi-step, and can be fully delegated in isolation
- When a task is independent of other tasks and can run in parallel
- When a task requires focused reasoning or heavy token/context usage that would bloat
  the orchestrator thread
- When sandboxing improves reliability
- When you only care about the output of the subagent, and not the intermediate steps

Subagent lifecycle:
1. **Spawn** → Provide clear role, instructions, and expected output
2. **Run** → The subagent completes the task autonomously
3. **Return** → The subagent provides a single structured result
4. **Reconcile** → Incorporate or synthesize the result into the main thread

When NOT to use the task tool:
- If you need to see the intermediate reasoning or steps after the subagent has completed
  (the task tool hides them)
- If the task is trivial (a few tool calls or simple lookup)
- If delegating does not reduce token usage, complexity, or context switching
- If splitting would add latency without benefit

## Important Task Tool Usage Notes to Remember
- Whenever possible, parallelize the work that you do. ...
```

[Step 06](../step-06-subagents/) 에서 "서브에이전트는 중간 과정을 안 보여준다" 고 배운 그 성질이, 여기 "the task tool hides them" 으로 모델에게도 고지되어 있습니다. 그리고 마지막 줄 "Whenever possible, parallelize" 가 서브에이전트가 자주 병렬로 뜨는 이유입니다.

### 직접 확인하기

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getStockPrice],
  subagents: [
    {
      name: "ticker-checker",
      description: "티커 심볼이 유효한지만 확인하는 서브에이전트",
      systemPrompt: "티커가 유효한지 한 줄로만 답하세요.",
      tools: [getStockPrice],
    },
  ],
  systemPrompt: "당신은 주식 리서치 어시스턴트입니다.",
  middleware: [makePromptSpy("7-4 전체 조립본")],
});

await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
```

출력에서 다음 세 문자열을 찾아보세요. 순서대로 나옵니다.

- `You are a Deep Agent` — 겹 2
- `## Filesystem Tools` — 겹 6
- `` ## `task` (subagent spawner) `` — 겹 7

> 💡 **실무 팁**: 에이전트가 이상하게 굴 때 **가장 먼저 할 일은 최종 프롬프트를 찍어보는 것**입니다. 개발자가 상상하는 프롬프트와 모델이 받는 프롬프트는 자주 다릅니다. 스파이 미들웨어 20줄을 프로젝트 공용 유틸로 두고, `DEBUG_PROMPT=1` 같은 환경변수로 껐다 켜세요. LangSmith 를 쓴다면 트레이스에서 시스템 메시지가 그대로 보이므로 별도 미들웨어가 필요 없습니다([Step 19 — 관측·테스트·평가](../../langchain/step-19-observability-eval/)).

---

## 7-5. 좋은 Deep Agent 프롬프트의 다섯 요소

내장 base 가 "일반적인 작업 규율" 을 담당하므로, 내 prefix 는 **내 도메인에서만 참인 것**을 담아야 합니다. 다섯 칸을 채우세요.

| 요소 | 답해야 할 질문 | 나쁜 예 | 좋은 예 |
|---|---|---|---|
| **역할** | 이 에이전트는 누구인가 | "도움을 주는 AI" | "주식 리서치 어시스턴트. 가격은 도구 결과만 인용한다." |
| **워크플로** | 어떤 순서로 일하나 | (없음) | "1. 티커 확인 → 2. 가격 조회(병렬) → 3. 표로 정리 → 4. 코멘트" |
| **도구 사용 규칙** | 각 도구를 언제, 어떤 실패 처리로 | "도구를 잘 써라" | "`Unknown ticker` 를 받으면 '조회 불가' 로 표기하고 넘어간다" |
| **출력 형식** | 결과가 어떻게 생겼나 | "보기 좋게" | 표 스키마를 그대로 제시 |
| **중단 조건** | 언제 멈추나 | (없음) | "표를 다 채웠으면 즉시 종료. 같은 인자로 두 번 호출 금지." |

실제 예시입니다.

```ts
const GOOD_PREFIX = `당신은 주식 리서치 어시스턴트입니다. (역할)

## 워크플로
1. 사용자가 요청한 티커를 확인한다.
2. get_stock_price 로 각 티커의 가격을 조회한다. 여러 개면 **반드시 병렬로** 호출한다.
3. 조회 결과를 표로 정리한다.
4. 마지막에 한 줄 코멘트를 단다.

## 도구 사용 규칙
- 가격은 절대 기억이나 추측으로 말하지 않는다. 반드시 get_stock_price 결과만 인용한다.
- get_stock_price 가 "Unknown ticker" 를 반환하면 그 티커는 "조회 불가" 로 표기하고 넘어간다.
- 티커가 3개 이상이면 write_todos 로 계획을 먼저 세운다.

## 출력 형식
| 티커 | 가격(USD) |
|---|---|
| ... | ... |

표 아래에 코멘트 한 줄. 그 외 서론/맺음말 금지.

## 중단 조건
- 모든 티커의 가격을 표에 채웠으면 즉시 종료한다.
- 같은 도구를 같은 인자로 두 번 부르지 않는다.
- 사용자가 티커를 하나도 주지 않았으면 조회하지 말고 티커를 되묻는다.`;
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
| 티커 | 가격(USD) |
|---|---|
| AAPL | 231.4 |
| NVDA | 178.2 |
| TSLA | 조회 불가 |

NVDA 와 AAPL 은 조회되었으나 TSLA 는 데이터가 없습니다.
```

TSLA 는 도구의 테이블에 없어서 `Unknown ticker: TSLA` 를 반환합니다. 규칙이 지켜졌다면 표에 "조회 불가" 로 들어가고, 안 지켜졌다면 모델이 **TSLA 가격을 지어냅니다.**

### 규칙을 쓰는 요령

**1. 도구의 실제 반환 문자열을 프롬프트에 못박으세요.**

```
❌ "모르는 티커는 모른다고 해."
✅ "get_stock_price 가 'Unknown ticker' 를 반환하면 '조회 불가' 로 표기한다."
```

모델은 `"Unknown ticker: TSLA"` 라는 **실제 문자열**을 `ToolMessage` 로 받습니다. 프롬프트에 그 문자열이 그대로 있으면 매칭이 쉽고, 추상적 지시("모르면")는 해석이 필요해서 흔들립니다.

**2. 검증 가능한 문장으로 쓰세요.** "간결하게" 는 못 채점합니다. "표 아래 코멘트 한 줄, 그 외 금지" 는 채점됩니다. 채점 못 하는 규칙은 7-8 의 개선 루프에 못 넣습니다.

**3. 중단 조건은 반드시 넣으세요.** 내장 base 는 "Keep working until the task is fully complete" 라고 **계속하라고** 시킵니다. 이건 코딩 에이전트엔 맞지만 조회 봇엔 과합니다. 내 prefix 의 중단 조건이 그 균형을 잡아줍니다.

> ⚠️ **함정**: 도구 사용 규칙을 prefix 에 쓰는 것과 **도구의 `description` 에 쓰는 것**은 다릅니다. `description` 은 모델이 "이 도구를 부를까?" 를 판단할 때 보는 것이고, prefix 는 전체 전략입니다. `description` 이 부실하면 prefix 에 아무리 "이 도구를 써라" 라고 써도 모델이 인자를 틀리게 채웁니다. 둘 다 필요합니다 — 자세한 건 [LangChain Step 06 — 도구 정의](../../langchain/step-06-tools/) 를 보세요.

> 💡 **실무 팁**: 다섯 요소를 마크다운 헤더(`## 워크플로`)로 나누세요. 모델은 구조화된 프롬프트를 더 잘 따릅니다. 그리고 이 구조는 **diff 가 읽힙니다** — 나중에 "누가 언제 왜 이 규칙을 추가했나" 를 git blame 으로 추적할 수 있습니다. 프롬프트는 코드입니다. 코드 리뷰하세요.

---

## 7-6. 나쁜 프롬프트 vs 좋은 프롬프트 A/B 실험

말로만 하면 안 믿깁니다. 같은 모델·같은 도구·같은 질문에 프롬프트만 바꿔 돌려봅시다.

```ts
const BAD_PREFIX = "주식 도와줘.";

const question = "AAPL, NVDA, TSLA 가격 비교해줘.";

for (const [label, prompt] of [
  ["BAD ", BAD_PREFIX],
  ["GOOD", GOOD_PREFIX],
] as const) {
  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getStockPrice],
    systemPrompt: prompt,
  });

  const result = await agent.invoke({ messages: [{ role: "user", content: question }] });

  const toolCalls = result.messages.filter((m) => m.getType() === "tool").length;
  const answer = result.messages.at(-1)?.text ?? "";

  console.log(`\n--- ${label} ---`);
  console.log(`도구호출=${toolCalls} 메시지수=${result.messages.length} 응답길이=${answer.length}`);
  console.log(answer);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다. 경향만 보세요)

```
--- BAD  ---
도구호출=3 메시지수=6 응답길이=612
세 종목을 조회했습니다!

- AAPL: $231.4 — 애플은 최근 강세를 보이고 있습니다.
- NVDA: $178.2 — 엔비디아는 AI 수요로 상승 중입니다.
- TSLA: $242.8 — 테슬라는 변동성이 큰 편입니다.       ← 지어낸 가격!

투자 결정에 참고하시되 전문가와 상담하세요.

--- GOOD ---
도구호출=3 메시지수=6 응답길이=134
| 티커 | 가격(USD) |
|---|---|
| AAPL | 231.4 |
| NVDA | 178.2 |
| TSLA | 조회 불가 |

TSLA 는 조회할 수 없어 비교에서 제외했습니다.
```

**도구 호출 횟수는 3으로 같습니다.** BAD 도 도구를 제대로 불렀습니다. 차이는 도구가 `Unknown ticker: TSLA` 를 돌려준 **다음**에 갈렸습니다.

- BAD: 실패 경로가 정의 안 됨 → 모델이 "사용자를 도와야 한다" 는 본능으로 **그럴듯한 가격을 생성**
- GOOD: `"Unknown ticker" → "조회 불가"` 규칙이 있음 → 그대로 표기

**응답 길이 612 vs 134.** BAD 는 요청하지 않은 코멘터리와 면책 문구를 답니다. 토큰이 4.5배입니다.

> ⚠️ **함정 (이 스텝에서 가장 비싼 함정)**: 환각을 발견하면 대개 "모델을 더 좋은 걸로 바꾸자" 로 갑니다. 하지만 위 실험에서 **모델은 동일했습니다.** 환각의 큰 부분은 모델 능력이 아니라 **프롬프트가 실패 경로를 정의하지 않아서** 생깁니다. 도구가 실패/빈 결과/부분 결과를 반환했을 때 뭘 해야 하는지를 안 적으면, 모델은 목표(사용자 돕기)를 달성하려고 빈칸을 스스로 메웁니다. 도구를 만들 때마다 **"이 도구가 실패하면 프롬프트의 어느 문장이 그걸 처리하나?"** 를 자문하세요.

> 💡 **실무 팁**: A/B 를 눈으로만 하지 말고 **숫자를 뽑으세요.** 위 코드처럼 도구 호출 횟수, 메시지 수, 응답 길이는 공짜로 얻습니다. `result.messages.filter((m) => m.getType() === "tool").length` 가 도구 호출 횟수, `result.messages.length` 가 루프 길이의 대리 지표입니다. 응답 길이가 3배 줄면 그게 곧 출력 토큰 요금 3분의 1입니다. 프롬프트 개선은 품질 작업인 동시에 **원가 절감 작업**입니다.

---

## 7-7. 하네스 프로파일 — 호출부를 안 건드리고 기본값 바꾸기

여기까지는 `createDeepAgent` 호출부에서 프롬프트를 제어했습니다. 그런데 이런 요구가 생깁니다.

- "우리 조직의 모든 에이전트는 한국어로 답해야 한다"
- "gpt-5.5 로 돌릴 땐 `execute` 도구를 빼야 한다"
- "이 모델은 요약 미들웨어랑 궁합이 나쁘니 빼자"

호출부가 수십 군데면 전부 고칠 수 없습니다. **하네스 프로파일(harness profile)** 이 이걸 위한 장치입니다. **프로바이더/모델별 기본값을 전역에 등록**하면, 해당 모델이 선택될 때 자동으로 적용됩니다.

```ts
import { registerHarnessProfile, getHarnessProfile } from "deepagents";

registerHarnessProfile("anthropic:claude-sonnet-4-6", {
  systemPromptSuffix: "### PROFILE-SUFFIX ### 모든 답변은 한국어로, 200자 이내로 하세요.",
  generalPurposeSubagent: { enabled: false },
});

// createDeepAgent 호출부는 하나도 안 바꿨는데 프롬프트 끝에 suffix 가 붙는다
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: "당신은 주식 리서치 어시스턴트입니다.",
});
```

키는 두 레벨입니다.

| 키 형식 | 예 | 적용 범위 |
|---|---|---|
| 프로바이더 | `"anthropic"`, `"openai"` | 그 프로바이더의 모든 모델 |
| 모델 | `"anthropic:claude-sonnet-4-6"` | 그 모델만 |

둘 다 등록되어 있으면 **머지되고, 모델 레벨이 이깁니다.**

### 옵션 전체

| 옵션 | 타입 | 하는 일 |
|---|---|---|
| `baseSystemPrompt` | `string` | 내장 `BASE_AGENT_PROMPT` 를 통째로 교체 |
| `systemPromptSuffix` | `string` | 조립된 프롬프트 **맨 뒤**에 `\n\n` 으로 덧붙임 |
| `toolDescriptionOverrides` | `Record<string, string>` | 도구 설명 문구를 이름으로 교체 |
| `excludedTools` | `string[]` | 하네스 도구를 모델에게서 감춤 |
| `excludedMiddleware` | `string[]` | 미들웨어를 스택에서 제거 (**필수 2개 제외**) |
| `extraMiddleware` | 미들웨어 배열 | 사용자 미들웨어 **뒤**에 추가 |
| `generalPurposeSubagent` | `{ enabled?, description?, systemPrompt? }` | 자동 추가되는 general-purpose 서브에이전트 설정/비활성화 |

`systemPromptSuffix` 가 프로파일의 **주력 수단**입니다. `baseSystemPrompt` 는 "이 모델은 근본적으로 다른 base 가 필요하다" 는 드문 경우에만 쓰세요.

### 조립 순서에서 프로파일의 자리

7-2 의 순서를 다시 보면:

```
prefix  →  base  →  suffix  →  프로파일 systemPromptSuffix
                                └─ 항상 최후미
```

**프로파일 suffix 는 항상 맨 뒤**입니다. 호출부의 `suffix` 보다도 뒤입니다. 그래서 "모델별 마지막 한마디" 를 프로파일에 두면 호출부와 충돌하지 않습니다. 반대로 `baseSystemPrompt` 는 호출부의 `base` 에게 집니다 — 호출부가 `base` 를 명시하면 프로파일 base 는 무시됩니다.

### 파일로 관리하기

프로파일을 YAML/JSON 으로 두고 싶으면:

```ts
import { parseHarnessProfileConfig, registerHarnessProfile, serializeProfile } from "deepagents";
import YAML from "yaml";

const raw = YAML.parse(fileContent);
registerHarnessProfile("openai", parseHarnessProfileConfig(raw));

// 반대로 직렬화 (extraMiddleware 에 인스턴스가 들어있으면 실패합니다)
const json = serializeProfile(getHarnessProfile("openai"));
```

> ⚠️ **함정 (전역 상태)**: `registerHarnessProfile` 은 **프로세스 전역 레지스트리**를 바꿉니다. 모듈 하나가 이걸 호출하면 그 프로세스의 **모든** `createDeepAgent` 가 영향을 받습니다. 테스트에서 특히 위험합니다 — 테스트 A 가 등록한 프로파일이 테스트 B 에 새어 들어가고, 실행 순서에 따라 결과가 바뀝니다. 게다가 **재등록은 교체가 아니라 머지**입니다. `registerHarnessProfile(key, {})` 로는 안 지워집니다. 되돌리려면 필드를 명시적으로 `undefined`/빈 값으로 덮어써야 합니다. 프로파일 등록은 **앱 부트스트랩 코드 한 곳**에서만 하세요.

> ⚠️ **함정 (지워지지 않는 미들웨어)**: `excludedMiddleware` 로 아무 미들웨어나 뺄 수 있는 게 아닙니다.
> ```ts
> import { REQUIRED_MIDDLEWARE_NAMES } from "deepagents";
> console.log([...REQUIRED_MIDDLEWARE_NAMES]);
> // → [ 'FilesystemMiddleware', 'SubAgentMiddleware' ]
>
> registerHarnessProfile("anthropic", { excludedMiddleware: ["FilesystemMiddleware"] });
> // → Error: Cannot exclude required middleware "FilesystemMiddleware" —
> //   it provides essential agent capabilities that the runtime depends on.
> ```
> 이 둘은 Deep Agent 의 필수 골격이라 프로파일로 못 뺍니다. 다행히 **조용히 무시하는 게 아니라 등록 시점에 즉시 `Error` 를 던집니다** — 이건 좋은 설계입니다. 다만 던지는 시점이 `createDeepAgent` 가 아니라 **`registerHarnessProfile` 호출 시점**이라, 프로파일 등록을 모듈 로드 시점에 해두면 앱이 부팅하다 죽습니다. 검증 규칙은 세 가지입니다: 빈 문자열/공백 불가, `:` 포함 불가(클래스 경로 문법 미지원), `_` 로 시작 불가(비공개 미들웨어). 파일 도구를 정말 줄이고 싶으면 `excludedTools` 를 쓰거나, [Step 08](../step-08-middleware/) 처럼 미들웨어를 직접 조립하세요.

> 💡 **실무 팁**: TypeScript SDK 는 **하네스 프로파일만** 지원합니다(프로바이더 프로파일은 Python 전용). 그리고 프로파일은 "여러 모델을 지원하는 제품" 에서 진가를 발휘합니다 — 모델 스위처를 만들 때 모델마다 다른 quirk 를 호출부에 `if (model === ...)` 로 흩뿌리는 대신 프로파일 파일 하나로 모읍니다.

---

## 7-8. 프롬프트 반복 개선 루프

프롬프트는 "한 번 잘 쓰는" 게 아니라 **실패를 보고 고치는** 것입니다. 그러려면 실패를 **재현 가능하게 박제**해야 합니다.

```ts
type Case = { question: string; expect: (answer: string) => boolean; why: string };

const CASES: Case[] = [
  {
    question: "AAPL 가격 알려줘.",
    expect: (a) => a.includes("231.4"),
    why: "도구가 준 실제 가격을 인용해야 함",
  },
  {
    question: "TSLA 가격 알려줘.",
    expect: (a) => /조회 불가|Unknown|알 수 없/.test(a),
    why: "모르는 티커를 지어내면 안 됨",
  },
  {
    question: "주식 좀 알려줘.",
    expect: (a) => /어떤|티커|종목/.test(a),
    why: "티커가 없으면 되물어야 함",
  },
];

async function evalPrompt(label: string, systemPrompt: string) {
  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getStockPrice],
    systemPrompt,
  });

  let pass = 0;
  for (const c of CASES) {
    const r = await agent.invoke({ messages: [{ role: "user", content: c.question }] });
    const answer = r.messages.at(-1)?.text ?? "";
    const ok = c.expect(answer);
    if (ok) pass += 1;
    console.log(`  [${ok ? "PASS" : "FAIL"}] ${c.question}  (${c.why})`);
  }
  console.log(`  ${label}: ${pass}/${CASES.length}`);
  return pass;
}

await evalPrompt("v1", BAD_PREFIX);
await evalPrompt("v2", GOOD_PREFIX);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
[v1: 나쁜 프롬프트]
  [PASS] AAPL 가격 알려줘.  (도구가 준 실제 가격을 인용해야 함)
  [FAIL] TSLA 가격 알려줘.  (모르는 티커를 지어내면 안 됨)
  [FAIL] 주식 좀 알려줘.  (티커가 없으면 되물어야 함)
  v1: 1/3

[v2: 다섯 요소 프롬프트]
  [PASS] AAPL 가격 알려줘.  (도구가 준 실제 가격을 인용해야 함)
  [PASS] TSLA 가격 알려줘.  (모르는 티커를 지어내면 안 됨)
  [PASS] 주식 좀 알려줘.  (티커가 없으면 되물어야 함)
  v2: 3/3
```

루프는 이렇게 돕니다.

```
1. 실패를 발견한다 (프로덕션 로그, 사용자 제보, 직접 써보다가)
2. 그 실패를 CASES 에 케이스로 박제한다  ← 가장 중요한 단계
3. 프롬프트에 규칙을 한 줄 추가한다      ← "한 줄". 통째로 다시 쓰지 않는다
4. 다시 돌려 점수를 본다
5. 점수가 올랐고 기존 케이스가 안 깨졌으면 커밋한다
```

**2번이 핵심입니다.** 실패를 박제하지 않으면 3번에서 고쳤다고 믿고 넘어갔다가 두 달 뒤 같은 버그를 다시 만납니다. 케이스 파일은 **프롬프트의 회귀 테스트**입니다.

**3번의 "한 줄"** 도 중요합니다. 프롬프트를 통째로 다시 쓰면 뭐가 효과였는지 모릅니다. 한 줄씩 넣고 재측정하면 각 문장의 기여도를 알게 되고, 결국 **필요 없는 문장을 지울 수 있게** 됩니다. 긴 프롬프트가 좋은 게 아니라, 각 줄이 값을 하는 프롬프트가 좋은 것입니다.

> ⚠️ **함정 (비결정성)**: LLM 응답은 비결정적입니다. `temperature: 0` 도 결정성을 **보장하지 않습니다**. 1회 실행으로 PASS/FAIL 을 판정하면 노이즈에 속습니다. 3/3 이 나왔다고 좋아했다가 다음 실행에 2/3 가 나오는 게 정상입니다. 실무에서는 **케이스당 3~5회 반복해 통과율**로 봅니다. "3/3 → 3/3" 이 아니라 "60% → 95%" 로 읽어야 합니다. 그리고 케이스가 3개면 통계적으로 아무 의미가 없습니다 — 실전 데이터셋은 최소 20~50 케이스입니다.

> ⚠️ **함정 (프롬프트 규칙의 상호 충돌)**: 규칙을 계속 추가하다 보면 서로 모순되는 문장이 생깁니다. "항상 write_todos 로 계획을 세워라" + 내장 지침 "3단계 미만이면 write_todos 를 쓰지 마라". 모델은 이럴 때 조용히 하나를 무시하는데, **어느 쪽을 무시할지는 예측 불가**입니다. 내 규칙을 추가할 때는 7-4 에서 본 내장 지침과 충돌하는지 먼저 확인하세요. 충돌한다면 규칙을 추가할 게 아니라 `base` 를 교체해야 하는 신호입니다.

> 💡 **실무 팁**: 위 하네스는 손으로 만든 최소 버전입니다. 실무에서는 LangSmith 의 데이터셋/평가 기능을 쓰면 케이스 관리·반복 실행·버전 간 비교·LLM-as-judge 채점이 다 됩니다. 정규식으로 채점 못 하는 것("요약이 정확한가")은 다른 모델에게 채점을 시킵니다. [LangChain Step 19 — 관측·테스트·평가](../../langchain/step-19-observability-eval/) 에서 다룹니다. 지금 단계에서는 **정규식으로 채점 가능한 규칙부터 쓰는 습관**을 들이는 게 더 중요합니다.

---

## 7-9. 종합 — 코드 리뷰 에이전트

배운 걸 다 씁니다. 다섯 요소 + `base: null` 완전 제어 + `suffix`.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: {
    prefix: `당신은 TypeScript 코드 리뷰어입니다.

## 워크플로
1. /src 를 ls 로 훑어 구조를 파악한다.
2. 리뷰 대상 파일을 read_file 로 읽는다.
3. 문제를 심각도(HIGH/MEDIUM/LOW)로 분류한다.
4. /review.md 에 결과를 write_file 로 저장한다.

## 도구 사용 규칙
- 파일을 읽지 않고 리뷰하지 않는다. 추측 금지.
- 파일 경로는 항상 /로 시작한다.

## 출력 형식
심각도별 불릿. 각 항목은 "파일:라인 — 문제 — 제안" 형식.

## 중단 조건
- /review.md 를 쓴 뒤 즉시 종료한다. 같은 파일을 두 번 읽지 않는다.`,
    base: null,   // 내장 Deep Agent 인격을 걷어내고 내 규칙만 남긴다
    suffix: "리뷰는 반드시 한국어로 작성한다.",
  },
});

const result = await agent.invoke({
  messages: [{
    role: "user",
    content:
      "먼저 /src/util.ts 에 다음 코드를 저장하고 리뷰해줘:\n" +
      "export function sum(a: any, b: any) { return a + b }\n" +
      "export async function load(url: string) { const r = await fetch(url); return r.json() }",
  }],
});

console.log(result.messages.at(-1)?.text);
console.log("생성된 파일 목록:", Object.keys(result.files ?? {}));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
## HIGH
- /src/util.ts:1 — sum 의 파라미터가 any 라 타입 안전성이 없음 — number 로 좁히거나 제네릭 사용
- /src/util.ts:2 — fetch 응답의 r.ok 를 확인하지 않아 4xx/5xx 를 성공으로 처리 — if (!r.ok) throw 추가

## MEDIUM
- /src/util.ts:2 — r.json() 반환 타입이 any — 제네릭 파라미터로 타입 지정

생성된 파일 목록: [ '/src/util.ts', '/review.md' ]
```

`base: null` 을 썼는데도 에이전트가 `write_file` 과 `read_file` 을 제대로 씁니다. **파일 지침은 미들웨어가 붙였기 때문**입니다(7-3 함정). 내장 base 를 지운 대가는 "Verify — 자기 출력이 아니라 요구사항에 비추어 검증하라" 같은 규율을 잃은 것이고, 그 자리를 내 워크플로 4단계가 메우고 있습니다.

`result.files` 로 파일시스템 상태를 확인하는 것은 [Step 04](../step-04-filesystem/) 에서 배운 그대로입니다.

---

## 정리

**프롬프트 조립 순서** (`deepagents@1.11.0`)

```
prefix → base → suffix → 프로파일 systemPromptSuffix     (createDeepAgent 가 문자열로 조립)
  ↓
+ todo 지침 + 파일 지침 + task 지침                        (미들웨어가 매 모델 호출마다 덧붙임)
```

| 쓰고 싶은 것 | 방법 |
|---|---|
| 도메인 지식을 얹는다 | `systemPrompt: "문자열"` (= `{ prefix }`) — **기본** |
| 말투/언어만 바꾼다 | `{ suffix }` 또는 프로파일 `systemPromptSuffix` |
| 내장 base 를 내 것으로 바꾼다 | `{ base: "..." }` |
| 내장 base 를 없앤다 | `{ base: null }` — `undefined` 아님! |
| 모델별 기본값을 호출부 밖에서 | `registerHarnessProfile(key, {...})` |
| 도구 지침까지 손댄다 | 미들웨어 레벨 → [Step 08](../step-08-middleware/) |

**좋은 프롬프트의 다섯 요소**: 역할 / 워크플로 / 도구 사용 규칙 / 출력 형식 / 중단 조건

**핵심 함정 3가지**

1. **`systemPrompt` 는 교체가 아니라 prefix 다.** 내장 5천 자가 뒤에 그대로 살아있다. 내 규칙과 충돌하면 대개 구체적인 내장 쪽이 이긴다. `createAgent` 와 `createDeepAgent` 의 `systemPrompt` 의미가 다르다.
2. **`base: null` 은 한 겹만 지운다.** 미들웨어가 붙이는 파일/task/todo 지침은 그대로 남는다. 그리고 `base: undefined` 는 아무것도 안 지운다 — 타입은 통과하는데 동작이 정반대다.
3. **환각은 모델 탓이 아니라 실패 경로 미정의 탓인 경우가 많다.** 도구가 `Unknown ticker` 를 반환했을 때 뭘 해야 하는지 프롬프트에 없으면 모델이 빈칸을 메운다. 모델을 바꾸기 전에 프롬프트를 찍어보고 실패 경로를 세어보라.

**전역 상태 주의**: `registerHarnessProfile` 은 프로세스 전역이고, 재등록은 교체가 아니라 머지다. `REQUIRED_MIDDLEWARE_NAMES`(= `FilesystemMiddleware`, `SubAgentMiddleware`)를 `excludedMiddleware` 에 넣으면 등록 시점에 `Error` 를 던진다.

---

## 연습문제

1. `systemPrompt: "### MARKER ###"` 를 준 Deep Agent 를 만들고, 스파이 미들웨어로 최종 프롬프트를 가로채서 (a) 마커가 index 0 에 있는지, (b) `"You are a Deep Agent"` 가 여전히 포함되어 있는지 출력하세요. **systemPrompt 가 prefix 임을 스스로 증명하는 것**이 목적입니다.
2. `{ prefix, base: null }` 로 에이전트를 만들고 최종 프롬프트에서 `"You are a Deep Agent"` 와 `"## Filesystem Tools"` 의 포함 여부를 각각 출력하세요. 결과를 보고 `base: null` 이 "무엇까지" 지우는지 주석으로 한 줄 적으세요.
3. `base: null` 과 `base: undefined` 를 각각 준 에이전트 두 개의 최종 프롬프트 **길이**를 나란히 출력해 다르다는 것을 보이고, 왜 다른지 설명하세요. (힌트: 내부 판정이 `base !== undefined` 입니다)
4. `systemPrompt: { prefix: "P", suffix: "### MY-SUFFIX ###" }` 와 프로파일 `systemPromptSuffix: "### PROFILE-SUFFIX ###"` 를 동시에 설정하고, `indexOf` 로 두 마커 위치를 찍어 **어느 쪽이 더 뒤에 오는지** 확인하세요. 끝나면 프로파일을 되돌리세요.
5. `search_docs` 도구를 쓰는 "사내 규정 안내 봇" 의 prefix 를 다섯 요소를 모두 채워 작성하세요. 요구사항: 규정 원문 없이 답하면 안 됨 / `NOT_FOUND` 면 "해당 규정을 찾지 못했습니다" 라고만 답함 / 출력은 "답변 한 줄 + 근거(규정 N조)". "환불 규정 알려줘" 와 "주차장 규정 알려줘" 로 검증하세요.
6. 나쁜 프롬프트("규정 알려줘")와 문제 5 의 프롬프트를 "주차장 규정 알려줘"(= DB 에 없는 항목)로 A/B 비교하세요. 각각 도구 호출 횟수, 응답 길이, `"찾지 못했"` 포함 여부(= 환각 안 함)를 출력하세요.
7. 프로바이더 레벨 프로파일 `"anthropic"` 에 `excludedMiddleware: ["FilesystemMiddleware"]` 를 등록해 보세요. `try/catch` 로 감싸 **무슨 일이 일어나는지** 출력하고, `REQUIRED_MIDDLEWARE_NAMES` 를 근거로 이유를 설명하세요. 이어서 `excludedMiddleware: ["SummarizationMiddleware"]` 는 등록되는지도 확인해, 둘의 차이를 대조하세요.
8. 문제 5 의 프롬프트에 대해 최소 평가 하네스를 만드세요. 케이스 A: "환불 규정 알려줘" → `"14일"` 포함 / 케이스 B: "주차장 규정 알려줘" → `"찾지 못했"` 포함 / 케이스 C: "규정 알려줘" → `"어떤"` 또는 `"무엇"` 포함. `pass/3` 을 출력하고, FAIL 이 나면 규칙 **한 줄**을 추가해 점수가 오르는지 확인하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 08 — 미들웨어 조합](../step-08-middleware/)

이 스텝에서 "미들웨어가 프롬프트에 도구 지침을 덧붙인다", "`base: null` 로도 그건 못 지운다", "`REQUIRED_MIDDLEWARE_NAMES` 는 제거가 안 된다" 를 계속 만났습니다. 전부 미들웨어 스택 이야기였습니다. 다음 스텝에서는 `createDeepAgent` 의 껍데기를 벗겨서 **그 안이 `createAgent` + 미들웨어 스택일 뿐**임을 직접 조립해 증명합니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(7-1 ~ 7-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 **최종 프롬프트가 실제로 어떻게 조립되는지 눈으로 확인**하고, 그다음 `exercise.ts` 의 8개 문제를 직접 푼 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다. 세 파일 모두 `ANTHROPIC_API_KEY` 가 필요하며, 실제로 모델을 호출하므로 소량의 비용이 발생합니다. OpenAI 로 돌리려면 각 파일의 `model` 값을 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하세요.

### practice.ts

본문을 따라가며 실행할 예제를 `[7-1] ~ [7-9]` 주석 번호로 묶은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다 막히면 같은 번호 블록을 실행해 보면 됩니다.

- 파일 맨 위의 `makePromptSpy` 가 이 스텝 전체의 관측 도구입니다. `createMiddleware({ wrapModelCall })` 로 모델 호출 직전의 `request.systemMessage.text` 를 찍습니다. **매 모델 호출마다** 찍히므로 도구를 여러 번 부르는 예제에서는 출력이 반복됩니다 — 정상입니다. 조용히 보고 싶으면 `makePromptSummarySpy` 쪽을 쓰세요(길이와 섹션 포함 여부만 한 줄로 요약).
- `[7-2]` 가 이 스텝의 첫 번째 "아하" 지점입니다. `### MY-PREFIX-MARKER ###` 20자를 넣었는데 출력이 5천 자입니다. 스크롤해서 마커 바로 밑에 `You are a Deep Agent` 가 붙어 있는 것을 직접 확인하세요.
- `[7-3]` 은 (A) base 교체 / (B) `base: null` / (C) `base: undefined` 세 개를 연달아 돌립니다. (C) 는 일부러 실패하는 코드입니다 — "제거하려던 시도" 라는 prefix 를 달아뒀지만 요약 스파이의 `내장기반=true` 가 뜹니다. 이게 본문 7-3 의 `null`/`undefined` 함정입니다.
- `[7-6]` 과 `[7-8]` 은 실제 모델을 여러 번 호출합니다. `[7-8]` 은 프롬프트 2종 × 케이스 3개 = 6회입니다. 비용과 시간이 신경 쓰이면 `main()` 에서 해당 줄을 주석 처리하세요.
- `[7-7]` 은 프로파일을 등록한 뒤 **반드시 되돌립니다**(`systemPromptSuffix: undefined` 로 재등록). 전역 레지스트리라 안 되돌리면 `[7-8]`, `[7-9]` 의 프롬프트에까지 `### PROFILE-SUFFIX ###` 가 새어 들어갑니다. 이 되돌리기 코드를 일부러 주석 처리하고 돌려보면 전역 상태 함정을 몸으로 느낄 수 있습니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 함수 본문이 비어 있으니, 거기에 직접 코드를 써 넣고 실행해 검증하면 됩니다.

- 상단의 `makePromptSpy(onPrompt)` 와 `searchDocs` 도구는 **그대로 쓰라고 준 준비물**입니다. 스파이는 practice.ts 와 달리 콜백을 받는 형태라, 문제마다 원하는 검사(`indexOf`, `includes`, `length`)를 직접 꽂을 수 있습니다.
- `[문제 2]`, `[문제 3]`, `[문제 7]` 은 코드만 쓰고 끝내면 절반만 한 것입니다. 각 블록 끝의 `// → ...:` 자리에 **왜 그런지 이유를 주석으로** 적어야 완성입니다. 이 세 문제의 정답은 코드가 아니라 이유입니다.
- `[문제 5]` 의 `RULE_BOT_PREFIX` 는 `TODO:` 한 줄로 비어 있습니다. 여기가 이 파일에서 가장 오래 걸리는 대목입니다. 다섯 요소 헤더(`## 워크플로` 등)를 먼저 다 적어놓고 칸을 채우는 순서로 쓰면 빠집니다. 그리고 `[문제 6]`, `[문제 8]` 이 이 프롬프트를 재사용하므로, 5번을 대충 하면 뒤 두 문제가 의미를 잃습니다.
- `[문제 4]` 와 `[문제 7]` 은 `registerHarnessProfile` 을 씁니다. **끝나면 되돌리라**고 문제에 적혀 있습니다. 안 되돌리면 뒤 문제들의 결과가 오염됩니다 — 이것 자체가 본문 7-7 의 전역 상태 함정을 겪게 하려는 의도입니다.
- 파일을 그대로 실행하면 함수 본문이 비어 있어 헤더만 8줄 찍히고 끝납니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 기대 결과값이 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `capturePrompt()` 헬퍼가 이 파일의 핵심 도구입니다. `createDeepAgent` 파라미터를 받아 스파이를 끼워 넣고, 모델을 한 번 호출한 뒤 **첫 번째 시스템 프롬프트만** 잡아 문자열로 돌려줍니다(`if (!captured)` 가드). 프롬프트를 값으로 다룰 수 있게 되면서 정답 1~4, 7 이 전부 두세 줄로 줄었습니다. 프로젝트 공용 유틸로 가져다 쓸 만합니다.
- `[정답 2]` 의 기대값은 **(a) false, (b) true** 입니다. `base: null` 을 줬는데 파일 지침이 남아있는 게 정상입니다. 이 조합이 헷갈린다면 본문 7-2 의 "겹" 표를 다시 보세요 — 1~4 겹은 조립 시점, 5~7 겹은 모델 호출 시점이라 `base: null` 의 사정거리 밖입니다.
- `[정답 3]` 은 두 프롬프트의 길이 차이를 출력하고 "= 내장 base 프롬프트 분량" 이라고 라벨을 붙입니다. 대략 2천 자 근처가 나옵니다. **차이가 0이 나오면 뭔가 잘못 짠 것**이므로 바로 알아챌 수 있게 설계했습니다.
- `[정답 5]` 의 `RULE_BOT_PREFIX` 에서 가장 중요한 줄은 `- search_docs 가 정확히 "NOT_FOUND" 를 반환하면 ... "해당 규정을 찾지 못했습니다." 라고만 답한다` 입니다. **도구의 실제 반환 문자열(`NOT_FOUND`)을 프롬프트에 그대로 박은 것**이 포인트입니다. "모르면 모른다고 해" 로 바꿔서 돌려보면 `[정답 6]`, `[정답 8]` 의 결과가 눈에 띄게 나빠집니다 — 직접 바꿔서 확인해 보세요.
- `[정답 7]` 은 `REQUIRED_MIDDLEWARE_NAMES` 를 실제로 import 해서 `[ 'FilesystemMiddleware', 'SubAgentMiddleware' ]` 를 찍은 뒤, `excludedMiddleware: ["FilesystemMiddleware"]` 등록을 `try/catch` 로 감쌉니다. **`createDeepAgent` 가 아니라 `registerHarnessProfile` 이 던진다**는 것, 그리고 바로 아래 `SummarizationMiddleware` 는 조용히 등록된다는 대조가 이 문제의 핵심입니다. 던지는 시점을 알아야 프로파일 등록 코드를 어디에 둘지(부트스트랩 vs 지연 초기화) 판단할 수 있습니다.
- `[정답 8]` 의 해설 주석에 비결정성 대응이 적혀 있습니다. 3케이스 1회 실행은 통계적으로 의미가 없습니다. 같은 코드를 세 번 돌려서 점수가 흔들리는지 직접 보고, 실무에서는 왜 통과율로 보는지 감을 잡으세요.

```ts file="./solution.ts"
```
