# Step 08 — 미들웨어 조합

> **학습 목표**
> - `createDeepAgent` 의 기본 미들웨어 스택을 순서까지 정확히 나열한다
> - deepagents 가 export 하는 미들웨어 팩토리와 각각의 옵션을 구분해 쓴다
> - `createAgent` + 미들웨어로 `createDeepAgent` 와 동등한 것을 직접 조립하고 결과를 비교한다
> - Deep Agent 전체가 아니라 파일시스템만, 계획만 필요한 에이전트를 만든다
> - `middleware` 옵션이 기본 스택을 **추가**하는지 **대체**하는지 이름 규칙으로 설명한다
> - 미들웨어 순서와 서브에이전트 미들웨어 상속 문제를 진단한다
> - 프롬프트 캐싱 미들웨어가 언제 자동으로 붙고 언제 직접 붙여야 하는지 안다
>
> **선행 스텝**: [Step 07 — 시스템 프롬프트 설계](../step-07-prompting/)
> **예상 소요**: 80분

Step 07 내내 같은 말을 반복했습니다. "그건 미들웨어가 붙인 겁니다." 파일 지침도, task 지침도, todo 지침도. `base: null` 로도 못 지웠고, `REQUIRED_MIDDLEWARE_NAMES` 는 아예 제외를 거부했습니다. 계속 미들웨어라는 벽에 부딪혔습니다.

이 스텝에서 그 벽을 넘습니다. **핵심 메시지는 하나입니다: `createDeepAgent` 를 벗기면 `createAgent` + 미들웨어 스택이다.** 특별한 클래스도, 숨겨진 그래프도 없습니다. [LangChain Step 08](../../langchain/step-08-create-agent/) 에서 만든 그 `createAgent` 에 미들웨어 여섯 개를 정해진 순서로 꽂고 프롬프트를 조립해 넘기는 함수일 뿐입니다. 그걸 말로 주장하지 않고 **직접 조립해서 증명**합니다. 두 에이전트의 도구 목록을 나란히 찍어 같은지 확인할 겁니다.

이걸 알면 세 가지가 열립니다. Deep Agent 의 일부만 빌려쓸 수 있고, 기본 스택에 내 미들웨어를 안전하게 끼울 수 있고, 뭔가 안 될 때 **어느 미들웨어의 책임인지** 짚을 수 있습니다.

> **검증 버전**: `deepagents` 1.11.0 / `langchain` 1.5.3 / `@langchain/anthropic` 1.5.1 / Node.js 22
> 이 스텝의 스택 순서·미들웨어 이름·옵션 필드는 모두 `deepagents@1.11.0` 패키지에서 직접 확인한 것입니다. 미들웨어 순서는 내부 구현이므로 마이너 버전에서 바뀔 수 있습니다.

---

## 8-1. `createDeepAgent` 의 기본 미들웨어 스택 정체 밝히기

먼저 결론입니다. `createDeepAgent` 는 이 스택을 조립합니다.

```
[기본(default) 세그먼트]
  1. todoListMiddleware()                     ← langchain 제공
  2. createSkillsMiddleware({ backend, sources })   ← skills 를 줬을 때만
  3. createFilesystemMiddleware({ backend, permissions, tools })
  4. createSubAgentMiddleware({ defaultModel, defaultTools, subagents, ... })
  5. createSummarizationMiddleware({ backend })
  6. createPatchToolCallsMiddleware()
  7. createAsyncSubAgentMiddleware({ asyncSubAgents })  ← 비동기 서브에이전트를 줬을 때만

[내 middleware 옵션이 여기 끼어든다]

[꼬리(tail) 세그먼트]
  8. 하네스 프로파일의 extraMiddleware
  9. anthropicPromptCachingMiddleware() + CacheBreakpointMiddleware  ← Anthropic 모델일 때
     bedrockPromptCachingMiddleware()                                ← Bedrock 모델일 때
 10. createMemoryMiddleware({ backend, sources })   ← memory 를 줬을 때만
 11. humanInTheLoopMiddleware({ interruptOn })      ← interruptOn 을 줬을 때만
```

그리고 마지막 줄이 이것입니다.

```ts
return createAgent({
  model,
  systemPrompt: assemblePromptParts(promptParts),  // ← Step 07 의 prefix→base→suffix
  stateSchema,
  tools: effectiveTools,
  middleware,                                       // ← 위 스택
  responseFormat,
  contextSchema,
  checkpointer,
  store,
  name,
  streamTransformers,
}).withConfig({
  recursionLimit: 10000,
  metadata: { ls_integration: "deepagents", lc_agent_name: name },
});
```

**끝입니다.** `createDeepAgent` 는 `createAgent` 를 감싼 조립 함수입니다. `recursionLimit: 10000` 도 여기서 나옵니다 — Deep Agent 가 수백 스텝을 도는 게 이상하지 않은 이유입니다(기본 `createAgent` 는 25 입니다).

### 조건부로 붙는 것들에 주목

스택의 절반은 **조건부**입니다.

| 미들웨어 | 붙는 조건 |
|---|---|
| `SkillsMiddleware` | `skills` 배열을 줬을 때 |
| `AsyncSubAgentMiddleware` | `subagents` 에 `graphId` 를 가진 항목이 있을 때 |
| 프롬프트 캐싱 | 모델이 Anthropic 또는 Bedrock 일 때 |
| `MemoryMiddleware` | `memory` 배열을 줬을 때 |
| `HumanInTheLoopMiddleware` | `interruptOn` 을 줬을 때 |

즉 **같은 코드라도 옵션에 따라 스택이 달라집니다.** "문서에는 11개라는데 내 스택엔 6개뿐" 이면 정상입니다.

### 눈으로 확인하기

Step 07 의 스파이를 확장합니다. `request.tools` 로 **모델에게 실제로 보이는 도구 목록**을 볼 수 있습니다.

```ts
import { createMiddleware } from "langchain";

function makeInspector(label: string) {
  let done = false;
  return createMiddleware({
    name: "InspectorMiddleware",
    wrapModelCall: async (request, handler) => {
      if (!done) {
        done = true;
        const toolNames = request.tools.map((t: any) => t.name).sort();
        console.log(`[${label}] 도구(${toolNames.length}): ${toolNames.join(", ")}`);
        console.log(`[${label}] 프롬프트 길이: ${(request.systemMessage.text ?? "").length}`);
      }
      return handler(request);
    },
  });
}

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],                        // ← 내가 준 건 이거 하나
  systemPrompt: "당신은 날씨 봇입니다.",
  middleware: [makeInspector("createDeepAgent 기본")],
});

await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
```

**출력** (도구 목록은 결정적입니다)

```
[createDeepAgent 기본] 도구(9): edit_file, get_weather, glob, grep, ls, read_file, task, write_file, write_todos
[createDeepAgent 기본] 프롬프트 길이: 5000자 내외
```

내가 준 도구는 `get_weather` **하나**인데 9개가 보입니다. 나머지 8개의 출처는 이렇습니다.

| 도구 | 등록한 미들웨어 |
|---|---|
| `write_todos` | `todoListMiddleware` |
| `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep` | `FilesystemMiddleware` |
| `task` | `subAgentMiddleware` |

> 💡 **실무 팁**: `request.tools` 스파이는 디버깅의 1순위 도구입니다. "모델이 이 도구를 안 부른다" 는 문제의 상당수는 **애초에 그 도구가 목록에 없어서**입니다. 프로파일의 `excludedTools`, `createFilesystemMiddleware` 의 `tools` 화이트리스트, 백엔드 능력 필터(예: 실행을 지원 안 하는 백엔드에서는 `execute` 가 자동 제거) 세 군데가 도구를 조용히 지웁니다. 부르라고 프롬프트를 고치기 전에 목록부터 찍어보세요.

> ⚠️ **함정**: 내 도구 이름이 내장 도구와 겹치면 `createDeepAgent` 가 **에러를 던집니다.**
> ```
> ConfigurationError: Tool name(s) [read_file] conflict with built-in tools.
> Rename your custom tools to avoid this.
> ```
> `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`, `execute`, `task`, `write_todos` 는 예약어입니다. 다행히 조용히 덮어쓰지 않고 즉시 던져줍니다. `search` 같은 일반적인 이름은 안전하지만, `read_file` 처럼 자연스러운 이름을 쓰려다 부딪히면 접두사(`db_read_file`)를 붙이세요.

---

## 8-2. deepagents 가 export 하는 미들웨어

`deepagents` 는 자기가 쓰는 미들웨어를 **전부 export 합니다.** 이게 8-3 의 직접 조립을 가능하게 하는 전제입니다.

| 팩토리 | 실제 `.name` | 등록 도구 | 하는 일 |
|---|---|---|---|
| `createFilesystemMiddleware` | `FilesystemMiddleware` | `ls` `read_file` `write_file` `edit_file` `glob` `grep` `execute` | 가상 파일시스템 + 권한 + 큰 결과 오프로드 |
| `createSubAgentMiddleware` | `subAgentMiddleware` | `task` | 서브에이전트 스폰 |
| `createSummarizationMiddleware` | `SummarizationMiddleware` | (없음) | 대화 요약 + **파일로 오프로드** |
| `createSkillsMiddleware` | `SkillsMiddleware` | (없음) | 스킬 메타데이터를 프롬프트에 주입 |
| `createMemoryMiddleware` | `MemoryMiddleware` | (없음) | `AGENTS.md` 류 메모리 파일 주입 |
| `createPatchToolCallsMiddleware` | `patchToolCallsMiddleware` | (없음) | 짝 안 맞는 tool_call/ToolMessage 복구 |
| `createAsyncSubAgentMiddleware` | `asyncSubAgentMiddleware` | `start_async_task` 등 | 원격 비동기 서브에이전트 |

**`.name` 이 팩토리 이름과 다르고, 대소문자도 제각각인 것에 주목하세요.** `createSubAgentMiddleware` 의 `.name` 은 `"SubAgentMiddleware"` 가 아니라 **`"subAgentMiddleware"`**(소문자 s)입니다. 8-5 의 이름 충돌 규칙에서 이게 결정적입니다.

### 주요 옵션

**`createFilesystemMiddleware(options?)`**

| 옵션 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `backend` | 백엔드 인스턴스 또는 팩토리 | `StateBackend` | 파일이 실제로 어디 저장되나 ([Step 05](../step-05-backends/)) |
| `tools` | `FsToolName[] \| "all" \| null` | `"all"` | 노출할 파일 도구 화이트리스트. **`read_file` 은 필수 포함** |
| `systemPrompt` | `string \| null` | 자동 생성 | 파일 지침 통째로 교체 |
| `customToolDescriptions` | `Partial<Record<FsToolName, string>>` | — | 도구별 설명 교체 |
| `permissions` | `FilesystemPermission[]` | `[]` (전부 허용) | 경로별 권한. 선언 순서대로 평가, 첫 매치 승 |
| `toolTokenLimitBeforeEvict` | `number` | `20000` | 도구 결과가 이보다 크면 파일로 내보냄 |
| `humanMessageTokenLimitBeforeEvict` | `number` | `50000` | HumanMessage 가 이보다 크면 파일로 내보냄 |

**`createSubAgentMiddleware(options)`**

| 옵션 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `defaultModel` | 모델 문자열/인스턴스 | **필수** | 서브에이전트 기본 모델 |
| `defaultTools` | `StructuredTool[]` | `[]` | general-purpose 서브에이전트가 쓸 도구 |
| `defaultMiddleware` | `AgentMiddleware[] \| null` | `null` | **커스텀 서브에이전트**에 깔 미들웨어 |
| `generalPurposeMiddleware` | `AgentMiddleware[] \| null` | `defaultMiddleware` 로 폴백 | general-purpose 전용 |
| `subagents` | `(SubAgent \| CompiledSubAgent)[]` | `[]` | 서브에이전트 명세 |
| `generalPurposeAgent` | `boolean` | `true` | general-purpose 를 자동 추가할지 |
| `systemPrompt` | `string \| null` | `TASK_SYSTEM_PROMPT` | task 지침 교체. `null` 이면 안 붙임 |
| `taskDescription` | `string \| null` | 자동 생성 | `task` 도구 설명 교체 |
| `defaultInterruptOn` | `Record<string, ...>` | `null` | 서브에이전트 HITL |

**`createSummarizationMiddleware(options)`**

| 옵션 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `backend` | 백엔드/팩토리 | **필수** | 잘린 대화를 어디 저장하나 |
| `model` | 모델 문자열/인스턴스 | 활성 요청 모델 | 요약 생성용 |
| `trigger` | `ContextSize \| ContextSize[]` | 모델 프로파일 유무에 따라 | 언제 요약할지 |
| `keep` | `ContextSize` | 최근 20개 | 요약 후 남길 분량 |
| `summaryPrompt` | `string` | 내장 | 요약 프롬프트 |
| `trimTokensToSummarize` | `number` | `4000` | 요약 생성 시 넣을 최대 토큰 |
| `historyPathPrefix` | `string` | `"/conversation_history"` | 오프로드 경로 |
| `truncateArgsSettings` | `TruncateArgsSettings` | — | 옛 메시지의 큰 도구 인자 자르기 |

`ContextSize` 는 `{ type: "messages" | "tokens" | "fraction", value: number }` 입니다. 기본 트리거는 모델이 `maxInputTokens` 프로파일을 갖고 있으면 `{ type: "fraction", value: 0.85 }`(컨텍스트의 85%), 없으면 `{ type: "tokens", value: 170000 }` 로 폴백합니다.

**`createSkillsMiddleware(options)`** / **`createMemoryMiddleware(options)`**

| 옵션 | 타입 | 설명 |
|---|---|---|
| `backend` | 백엔드/팩토리 | **필수** |
| `sources` | `string[]` | **필수**. 스킬 디렉터리 또는 메모리 파일 경로. POSIX 슬래시 |
| `addCacheControl` | `boolean` (memory 전용) | 메모리 블록에 캐시 브레이크포인트를 달지. 기본 `false` |

`skills`/`memory` 는 [Step 10 — 장기 메모리와 스킬](../step-10-memory-skills/) 에서 자세히 다룹니다.

> ⚠️ **함정 (같은 이름, 다른 타입)**: `langchain` 에도 `summarizationMiddleware` 가 있고, 그 `.name` 도 **`"SummarizationMiddleware"`** 로 deepagents 것과 **완전히 같습니다.** 그런데 옵션 모양이 다릅니다.
> ```ts
> // deepagents — ContextSize = { type, value }
> createSummarizationMiddleware({ backend, trigger: { type: "tokens", value: 100000 } });
>
> // langchain — ContextSize = { fraction?, tokens?, messages? }
> summarizationMiddleware({ model, trigger: { tokens: 100000 } });
> ```
> 같은 개념(`trigger`, `keep`, `ContextSize`)에 이름도 같은데 **필드 구조가 다릅니다.** 문서를 대충 보고 섞어 쓰면 zod 검증에서 터지거나, 더 나쁘게는 `strip` 모드라 **모르는 필드가 조용히 버려져** 트리거가 기본값으로 도는 일이 생깁니다. 어느 패키지에서 import 했는지 확인하세요. 그리고 이름이 같다는 사실은 8-5 에서 훨씬 큰 사고로 이어집니다.

> 💡 **실무 팁**: 두 요약 미들웨어의 진짜 차이는 **`backend` 필수 여부**입니다. deepagents 쪽은 요약하면서 잘라낸 원본 대화를 `/conversation_history` 아래 파일로 **오프로드**합니다 — 에이전트가 나중에 `read_file` 로 되찾아볼 수 있습니다. langchain 쪽은 그냥 버립니다. Deep Agent 에서 긴 리서치를 돌린다면 이 차이가 큽니다.

---

## 8-3. 직접 조립 — `createDeepAgent` 와 동등한 것 만들기

주장을 증명할 차례입니다. 8-1 의 스택을 손으로 쌓아 `createAgent` 에 넣고, 두 에이전트의 도구 목록이 같은지 봅니다.

```ts
import { createAgent, todoListMiddleware, anthropicPromptCachingMiddleware } from "langchain";
import {
  createFilesystemMiddleware,
  createSubAgentMiddleware,
  createSummarizationMiddleware,
  createPatchToolCallsMiddleware,
  StateBackend,
} from "deepagents";

// createDeepAgent 의 backend 기본값이 정확히 이것입니다.
const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);
const tools = [getWeather];

// (A) createDeepAgent
const deep = await createDeepAgent({
  model: MODEL,
  tools,
  systemPrompt: "당신은 날씨 봇입니다.",
  middleware: [makeInspector("A. createDeepAgent", deepSink)],
});
await deep.invoke({ messages: [{ role: "user", content: "안녕" }] });

// (B) createAgent + 미들웨어 스택 직접 조립
const manual = createAgent({
  model: MODEL,
  tools,
  systemPrompt: "당신은 날씨 봇입니다.",   // ← 여기선 '교체'다. createDeepAgent 와 의미가 다르다 (Step 07)
  middleware: [
    todoListMiddleware(),
    createFilesystemMiddleware({ backend }),
    createSubAgentMiddleware({
      defaultModel: MODEL,
      defaultTools: tools,
      subagents: [],
      generalPurposeAgent: true,
    }),
    createSummarizationMiddleware({ backend }),
    createPatchToolCallsMiddleware(),
    anthropicPromptCachingMiddleware({
      unsupportedModelBehavior: "ignore",
      minMessagesToCache: 1,
    }),
    makeInspector("B. 직접 조립", manualSink),
  ],
}).withConfig({ recursionLimit: 10000 });

await manual.invoke({ messages: [{ role: "user", content: "안녕" }] });
```

**출력** (도구 목록은 결정적, 프롬프트 길이는 버전에 따라 다름)

```
--- [A. createDeepAgent] ---
도구(9): edit_file, get_weather, glob, grep, ls, read_file, task, write_file, write_todos
프롬프트 길이: 5000자 내외
섹션: base=true fs=true task=true

--- [B. 직접 조립] ---
도구(9): edit_file, get_weather, glob, grep, ls, read_file, task, write_file, write_todos
프롬프트 길이: 3000자 내외
섹션: base=false fs=true task=true

===== 동등성 비교 =====
도구 집합 동일: true
createDeepAgent 에만: []
직접 조립에만: []
→ 도구는 같지만 프롬프트가 짧습니다. BASE_AGENT_PROMPT 가 빠졌기 때문입니다.
```

**도구 9개가 정확히 일치합니다.** 마법은 없었습니다. `createDeepAgent` 는 이 조립을 대신 해주는 함수입니다.

### 딱 하나 재현 못 하는 것

`섹션: base=false` 를 보세요. **내장 `BASE_AGENT_PROMPT` 는 export 되지 않습니다.**

```ts
// ✅ export 됨
import { TASK_SYSTEM_PROMPT, DEFAULT_SUBAGENT_PROMPT, DEFAULT_GENERAL_PURPOSE_DESCRIPTION,
         GENERAL_PURPOSE_SUBAGENT, REQUIRED_MIDDLEWARE_NAMES } from "deepagents";

// ❌ export 안 됨 — 직접 써야 함
// BASE_AGENT_PROMPT
```

`TASK_SYSTEM_PROMPT` 는 가져다 쓸 수 있는데(8-8 에서 씁니다) base 프롬프트는 아닙니다. 직접 조립하면 Step 07 에서 본 그 좋은 base 프롬프트를 **손으로 써야 합니다.** 이게 직접 조립의 실질적 비용입니다.

### 그래서, 직접 조립해야 하나?

**대부분 아닙니다.** 이 절의 목적은 "이렇게 하세요" 가 아니라 **"이게 전부다" 를 보여주는 것**입니다. 정리하면:

| 원하는 것 | 방법 |
|---|---|
| Deep Agent 에 내 미들웨어 추가 | `createDeepAgent({ middleware: [...] })` — **8-5** |
| 모델별 기본값 조정 | `registerHarnessProfile` — [Step 07](../step-07-prompting/#7-7-하네스-프로파일--호출부를-안-건드리고-기본값-바꾸기) |
| 파일 도구만 줄이기 | `createFilesystemMiddleware({ tools })` — 프로파일 `excludedTools` 로도 가능 |
| Deep Agent 의 **일부만** 필요 | 직접 조립 — **8-4** |
| 스택 순서를 근본적으로 바꿔야 함 | 직접 조립 |

> 💡 **실무 팁**: 직접 조립을 **테스트 도구**로 쓰는 방법이 유용합니다. 프로덕션은 `createDeepAgent` 를 쓰되, 버그를 재현할 때 미들웨어를 하나씩 빼면서 조립해 보면 범인이 금방 나옵니다. "요약 미들웨어를 빼니까 되네" → 요약이 뭔가를 날리고 있다는 뜻입니다. 이분 탐색을 미들웨어 스택에 적용하는 것입니다.

---

## 8-4. 필요한 기능만 빌려쓰기

Deep Agent 전체가 아니라 조각만 필요할 때가 실제로 많습니다. 미들웨어가 개별 export 되어 있으니 평범한 `createAgent` 에 원하는 것만 꽂으면 됩니다.

### 파일시스템만 — "메모장 달린 에이전트"

```ts
const fsOnly = createAgent({
  model: MODEL,
  systemPrompt: "당신은 메모 도우미입니다. 요청받은 내용을 /notes/ 아래 파일로 정리하세요.",
  middleware: [createFilesystemMiddleware({ backend })],
});

const r = await fsOnly.invoke({
  messages: [{ role: "user", content: "오늘 회의 결론 '배포는 금요일'을 메모해줘." }],
});
console.log("생성된 파일:", Object.keys((r as any).files ?? {}));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
[A. 파일시스템만] 도구(6): edit_file, glob, grep, ls, read_file, write_file
생성된 파일: [ '/notes/meeting.md' ]
```

`task` 도 `write_todos` 도 없습니다. 서브에이전트를 안 스폰하니 토큰이 훨씬 적게 듭니다. 챗봇에 "메모 기능" 만 붙이고 싶을 때 Deep Agent 전체를 끌어올 이유가 없습니다.

### 계획만 — `write_todos` 만

```ts
const planOnly = createAgent({
  model: MODEL,
  tools: [getWeather],
  systemPrompt: "당신은 여행 플래너입니다.",
  middleware: [todoListMiddleware()],
});

const r = await planOnly.invoke({
  messages: [{ role: "user", content: "서울, 부산, 제주 날씨를 확인하고 여행지를 추천하는 계획을 세워줘." }],
});
console.log("todos:", (r as any).todos);
```

`todoListMiddleware` 는 `langchain` 에서 옵니다 — deepagents 를 설치조차 안 해도 계획 기능은 쓸 수 있습니다. [LangChain Step 11 — 내장 미들웨어](../../langchain/step-11-middleware-builtin/) 에서 다룬 그 미들웨어입니다.

### 읽기 전용 파일시스템

```ts
const readOnly = createAgent({
  model: MODEL,
  systemPrompt: "당신은 코드 탐색기입니다. 파일을 수정하지 마세요.",
  middleware: [
    createFilesystemMiddleware({ backend, tools: ["read_file", "ls", "glob", "grep"] }),
  ],
});
```

**출력**

```
[C. 읽기 전용 fs] 도구(4): glob, grep, ls, read_file
```

`write_file`, `edit_file` 이 사라졌습니다. 그리고 Step 07 에서 봤듯 **프롬프트의 파일 지침도 같이 줄어듭니다** — 헤더가 `## Filesystem Tools \`read_file\`, \`ls\`, \`glob\`, \`grep\`` 로 바뀌고 없는 도구의 설명은 나열되지 않습니다.

> ⚠️ **함정**: "파일을 수정하지 마세요" 를 **프롬프트에만** 쓰고 도구는 그대로 두는 것은 보안 장치가 아닙니다. 모델은 프롬프트를 어길 수 있고, 프롬프트 인젝션에도 뚫립니다. **도구를 실제로 제거**하거나 `permissions` 로 막아야 합니다. `tools` 화이트리스트는 "모델에게 안 보여주기" 고, `permissions` 는 "불러도 거부하기" 입니다. 진짜 신뢰 경계가 필요하면 [Step 09 — HITL과 권한 제어](../step-09-hitl-permissions/) 의 `permissions` 를 쓰세요.

> ⚠️ **함정**: `createFilesystemMiddleware({ tools: [...] })` 에 배열을 주면 **`read_file` 을 반드시 포함**해야 합니다. 큰 도구 결과를 파일로 오프로드한 뒤 그걸 되읽는 복구 경로가 `read_file` 을 쓰기 때문입니다. 그리고 백엔드 능력 필터가 그 위에 또 적용됩니다 — 화이트리스트에 `execute` 를 넣어도 실행을 지원하지 않는 백엔드(예: `StateBackend`)면 조용히 빠집니다.

> 💡 **실무 팁**: 조각 빌려쓰기의 가장 흔한 실전 용례는 **비용 절감**입니다. Deep Agent 의 시스템 프롬프트는 5천 자, 도구는 9개입니다. 매 턴 다 보냅니다. "파일에 메모만 하면 되는" 워크플로에 이걸 다 태우면 입력 토큰이 서너 배입니다. 필요한 미들웨어만 꽂으면 프롬프트가 3천 자 → 1천 자로 줄어듭니다. 다만 대화가 길어지면 요약 미들웨어가 없는 게 문제가 되므로(8-8 참고), 트래픽이 많은 경로부터 재보고 결정하세요.

---

## 8-5. `createDeepAgent` 에 `middleware` 주기 — 추가인가 대체인가

문서를 보면 "Custom middleware to apply after standard middleware" 라고만 되어 있습니다. 추가일까요 대체일까요?

**정답: 추가입니다. 단, 이름이 같으면 대체입니다.**

내부의 `mergeMiddlewareStack` 규칙입니다.

```
mergeMiddlewareStack(기본 세그먼트, 내 middleware, 꼬리 세그먼트)

1. 내 미들웨어 중 **기본 세그먼트와 이름이 같은 것** → 그 자리에서 교체
2. 내 미들웨어 중 **꼬리 세그먼트와 이름이 같은 것** → 그 자리에서 교체
3. 나머지(새 이름) → 기본 세그먼트와 꼬리 세그먼트 **사이에 삽입**
```

즉 병합 키는 **`.name` 문자열**입니다.

### (A) 새 이름 → 추가된다

```ts
const logging = createMiddleware({
  name: "LogToolCallsMiddleware",       // ← 기본 스택에 없는 이름
  wrapToolCall: async (request, handler) => {
    console.log(`[로그] ${request.toolCall.name}`);
    return handler(request);
  },
});

const agent = await createDeepAgent({
  model: MODEL,
  tools: [getWeather],
  systemPrompt: "당신은 날씨 봇입니다.",
  middleware: [logging],
});
```

**출력 예시** (모델 응답이므로 도구 호출 순서는 매번 다릅니다)

```
[로그] get_weather
도구(9): edit_file, get_weather, glob, grep, ls, read_file, task, write_file, write_todos
```

도구 9개가 그대로입니다. 기본 스택은 온전하고 내 로깅만 추가되었습니다. **이게 원하는 동작이고, 대부분의 경우 이걸 쓰면 됩니다.**

### (B) 이름이 겹치면 → 조용히 대체된다

```ts
import { summarizationMiddleware } from "langchain";   // ← langchain 것

const agent = await createDeepAgent({
  model: MODEL,
  tools: [getWeather],
  systemPrompt: "당신은 날씨 봇입니다.",
  middleware: [
    summarizationMiddleware({ model: MODEL, maxTokensBeforeSummary: 1000 }),
  ],
});
```

"요약 설정을 조정하려고 요약 미들웨어를 추가했다" 고 생각하기 쉽습니다. 실제로는:

- `langchain` 의 `summarizationMiddleware`.name = `"SummarizationMiddleware"`
- `deepagents` 의 `createSummarizationMiddleware`.name = `"SummarizationMiddleware"`

**같습니다.** 그래서 이건 추가가 아니라 **deepagents 의 요약 미들웨어를 통째로 교체**한 것입니다. 에러도 경고도 없습니다. 스택 길이는 그대로고 도구 목록도 그대로라, 겉으로는 아무 일도 안 일어난 것처럼 보입니다.

> ⚠️ **함정 (이 스텝에서 가장 비싼 함정)**: 위 교체의 대가는 **긴 대화에서만** 드러납니다. deepagents 의 요약 미들웨어는 잘라낸 원본 대화를 `/conversation_history` 아래 파일로 오프로드합니다. langchain 것은 **그냥 버립니다.**
>
> 시나리오: 에이전트가 30턴 동안 리서치하며 `/research/notes-1.md` ~ `/research/notes-12.md` 를 씁니다. 요약이 트리거되어 옛 메시지가 요약문 한 덩이로 압축됩니다. 그런데 요약문에 **파일 경로가 안 남습니다.** 에이전트는 자기가 뭘 썼는지 잊고, `/report.md` 를 쓰면서 노트 12개를 참조하지 못합니다. 파일은 `state.files` 에 멀쩡히 있는데 에이전트만 모릅니다.
>
> 증상이 "가끔 결과가 부실함" 으로 나타나서 원인을 찾기가 정말 어렵습니다. 방어법:
> 1. 이름 충돌을 의도한 게 아니면 **`langchain` 의 `summarizationMiddleware` 를 `createDeepAgent` 에 넣지 마세요.** 요약을 조정하려면 deepagents 것을 같은 이름으로 명시적 교체하세요:
>    ```ts
>    middleware: [createSummarizationMiddleware({ backend, trigger: { type: "tokens", value: 100000 } })]
>    ```
> 2. 요약 프롬프트에 "생성한 파일 경로를 반드시 요약에 보존하라" 를 넣으세요 (`summaryPrompt` 옵션).
> 3. 에이전트가 중요 산출물의 **경로 목록을 `/index.md` 에 계속 append** 하게 프롬프트에 규칙을 두세요. 요약은 메시지를 자르지 파일을 지우지 않으므로, 파일에 적힌 것은 살아남습니다.

> ⚠️ **함정 (이름을 몰라서 생기는 사고)**: 교체하려고 이름을 맞췄는데 안 되는 반대 경우도 있습니다. 8-2 의 표를 다시 보세요 — `createSubAgentMiddleware` 의 `.name` 은 **`"subAgentMiddleware"`** 입니다. `"SubAgentMiddleware"` 로 이름을 지은 커스텀 미들웨어를 넣으면 **교체가 아니라 추가**됩니다. 서브에이전트 미들웨어가 두 개가 되고 `task` 도구가 중복 등록됩니다. 이름을 맞출 땐 추측하지 말고 **찍어서 확인**하세요:
> ```ts
> console.log(createSubAgentMiddleware({ defaultModel: MODEL }).name);  // → subAgentMiddleware
> ```

> 💡 **실무 팁**: 커스텀 미들웨어 이름은 **충돌하지 않게 접두사**를 붙이세요. `"AcmeAuditMiddleware"`, `"AcmeRateLimitMiddleware"`. 이름은 병합 키이자 프로파일의 `excludedMiddleware` 키이므로, 사실상 **공개 API** 입니다. 한 번 정하면 바꾸기 어렵습니다.

---

## 8-6. 미들웨어 순서 문제

순서가 중요한 이유는 두 가지인데, 성격이 완전히 다릅니다.

### (1) 훅 실행 순서 — 양파 구조

미들웨어 배열은 양파처럼 겹쳐집니다.

```
middleware: [첫째, 둘째, 셋째]

beforeAgent / beforeModel / wrapModelCall / wrapToolCall 진입:  첫째 → 둘째 → 셋째 → 모델
afterModel / afterAgent:                                        셋째 → 둘째 → 첫째
```

**배열 앞이 바깥**입니다. 확인해 봅시다.

```ts
const marker = (n: string) =>
  createMiddleware({
    name: `Marker${n}Middleware`,
    wrapModelCall: async (request, handler) => {
      console.log(`  들어감: ${n}`);
      const r = await handler({
        ...request,
        systemMessage: request.systemMessage.concat(`\n[${n}]`),
      });
      console.log(`  나옴:   ${n}`);
      return r;
    },
  });

const agent = createAgent({
  model: MODEL,
  systemPrompt: "짧게 답하세요.",
  middleware: [marker("첫째"), marker("둘째"), marker("셋째")],
});
await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
```

**출력** (구조가 결정적입니다)

```
  들어감: 첫째
  들어감: 둘째
  들어감: 셋째
  나옴:   셋째
  나옴:   둘째
  나옴:   첫째
```

프롬프트에는 `[첫째]`, `[둘째]`, `[셋째]` 순으로 붙습니다 — 앞 미들웨어가 먼저 `concat` 하기 때문입니다. 8-1 스택에서 `todoListMiddleware` 가 1번이라 todo 지침이 파일 지침보다 앞에 오는 이유입니다.

실전 함의:

| 하고 싶은 것 | 어디에 둬야 하나 |
|---|---|
| 모든 도구 호출을 로깅/감사 | **앞** (바깥) — 다른 미들웨어의 변형까지 다 본다 |
| 최종 프롬프트를 관찰 | **뒤** (안쪽) — 앞 미들웨어가 다 덧붙인 뒤를 본다 |
| 요청을 변형 후 다음에 넘김 | 변형이 반영되어야 하는 미들웨어보다 **앞** |
| PII 마스킹 | **앞** — 모델에 닿기 전에 |

이 스텝의 `makeInspector` 를 항상 배열 **맨 뒤**에 둔 이유가 이것입니다. 앞에 두면 다른 미들웨어가 붙이기 **전**의 프롬프트를 보게 되어 파일 지침이 안 보입니다.

### (2) 서브에이전트는 부모 미들웨어를 상속하지 않는다

이게 진짜 함정입니다. 부모의 `middleware` 배열은 **부모에게만** 적용됩니다. 서브에이전트의 미들웨어는 `createSubAgentMiddleware` 의 `defaultMiddleware` / `generalPurposeMiddleware` 옵션으로**만** 정해집니다.

**틀린 조립:**

```ts
const wrong = createAgent({
  model: MODEL,
  middleware: [
    createFilesystemMiddleware({ backend }),      // ← 부모에게만 파일 도구가 생긴다
    createSubAgentMiddleware({
      defaultModel: MODEL,
      subagents: [
        {
          name: "note-taker",
          description: "받은 내용을 /notes.md 에 저장하는 서브에이전트",
          systemPrompt: "받은 내용을 /notes.md 에 write_file 로 저장하세요.",
        },
      ],
      generalPurposeAgent: false,
    }),
    // defaultMiddleware 가 없다!
  ],
  systemPrompt: "note-taker 서브에이전트에게 위임하세요.",
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
결과: note-taker 에게 저장을 요청했으나, 해당 에이전트가 파일을 저장할 수 없다고 응답했습니다.
파일: []
```

부모는 `write_file` 이 있는데 서브에이전트는 없습니다. 서브에이전트는 `write_file` 을 부르려다 없어서 헤매다가, "저장했습니다" 라고 **거짓 보고**를 하기도 합니다 — 서브에이전트의 중간 과정은 부모에게 안 보이므로([Step 06](../step-06-subagents/)) 부모는 그 말을 믿습니다. `state.files` 가 비어있는 걸 확인하기 전까지는 아무도 모릅니다.

**올바른 조립:**

```ts
const right = createAgent({
  model: MODEL,
  middleware: [
    createFilesystemMiddleware({ backend }),
    createSubAgentMiddleware({
      defaultModel: MODEL,
      defaultMiddleware: [createFilesystemMiddleware({ backend })],   // ← 이 한 줄이 핵심
      subagents: [ /* 위와 동일 */ ],
      generalPurposeAgent: false,
    }),
  ],
  systemPrompt: "note-taker 서브에이전트에게 위임하세요.",
});
```

**출력 예시**

```
결과: '배포는 금요일' 을 /notes.md 에 저장했습니다.
파일: [ '/notes.md' ]
```

`createDeepAgent` 는 이 배선을 **자동으로** 해 줍니다. 내부에서 서브에이전트마다 이 스택을 깔아줍니다.

```ts
// deepagents 내부 (createSubagentDefaultMiddleware)
[
  todoListMiddleware(),
  createFilesystemMiddleware({ backend, permissions, tools }),
  createSummarizationMiddleware({ backend }),
  createPatchToolCallsMiddleware(),
  ...(서브에이전트가 skills 를 가지면 createSkillsMiddleware(...)),
]
```

> ⚠️ **함정 (스킬은 상속 안 된다)**: 위 스택을 보면 **`SkillsMiddleware` 가 조건부**입니다. `createDeepAgent` 의 커스텀 서브에이전트는 부모의 `skills` 를 **상속하지 않습니다.** 서브에이전트가 스킬을 쓰려면 자기 `skills` 배열을 직접 가져야 합니다. 예외적으로 자동 추가되는 `general-purpose` 서브에이전트만 부모 스킬을 물려받습니다(`generalPurposeMiddleware` 로 별도 배선). "부모에 스킬을 등록했는데 서브에이전트가 못 쓴다" 는 버그가 아니라 설계입니다. 같은 이유로 서브에이전트가 반환하는 state 에서 `skillsMetadata`, `memoryContents`, `todos`, `structuredResponse` 는 **제외**됩니다 — 부모 상태로 새어 들어가지 않게.

> 💡 **실무 팁**: `defaultMiddleware` 와 `generalPurposeMiddleware` 를 나눠 놓은 이유가 위 스킬 상속 규칙 때문입니다. 직접 조립할 때 서브에이전트 미들웨어 배열을 **변수로 빼서 재사용**하세요(8-8 의 `subagentMiddleware` 처럼). 부모 스택과 서브에이전트 스택이 따로 노는 걸 코드에 드러내야, 나중에 "부모에 미들웨어 추가했는데 왜 서브에서 안 되지" 를 안 겪습니다.

---

## 8-7. 캐싱 미들웨어 — provider 프롬프트 캐싱

Step 07 에서 봤듯 Deep Agent 의 시스템 프롬프트는 5천 자가 넘습니다. 도구 정의 9개도 매 요청 같이 갑니다. 30턴 대화면 그 앞부분을 **30번** 다시 보냅니다.

Anthropic 과 Bedrock 은 프롬프트 캐싱을 지원합니다. 앞부분이 바이트 단위로 동일하면 재처리 대신 캐시에서 읽습니다.

**좋은 소식: `createDeepAgent` 는 이걸 자동으로 붙입니다.**

```ts
// deepagents 내부 (createDeepAgent)
let cacheMiddleware = [];
if (anthropicModel) cacheMiddleware = [
  ...cacheMiddleware,
  anthropicPromptCachingMiddleware({ unsupportedModelBehavior: "ignore", minMessagesToCache: 1 }),
  createCacheBreakpointMiddleware(),
];
if (bedrockModel) cacheMiddleware = [
  ...cacheMiddleware,
  bedrockPromptCachingMiddleware({ unsupportedModelBehavior: "ignore" }),
];
```

모델 문자열이 `"anthropic:..."` 이면 아무것도 안 해도 캐싱이 켜집니다. 이건 **꼬리 세그먼트**에 들어가므로 내 미들웨어보다 뒤(안쪽)입니다.

### 확인하기

```ts
const usageSpy = createMiddleware({
  name: "UsageSpyMiddleware",
  afterModel: async (state) => {
    const u = (state.messages.at(-1) as any)?.usage_metadata;
    if (u) {
      console.log(
        `  입력=${u.input_tokens} 출력=${u.output_tokens} ` +
          `캐시생성=${u.input_token_details?.cache_creation ?? 0} ` +
          `캐시읽기=${u.input_token_details?.cache_read ?? 0}`,
      );
    }
    return undefined;
  },
});

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "당신은 날씨 봇입니다.",
  middleware: [usageSpy],
});

const r1 = await agent.invoke({ messages: [{ role: "user", content: "서울 날씨?" }] });
await agent.invoke({ messages: [...r1.messages, { role: "user", content: "부산은?" }] });
```

**출력 예시** (토큰 수는 매번 다릅니다. `usage_metadata` 의 **필드명은 결정적**입니다)

```
1회차 (캐시 생성):
  입력=25 출력=68 캐시생성=2847 캐시읽기=0
2회차 (캐시 읽기 — 같은 대화를 이어감):
  입력=31 출력=54 캐시생성=112 캐시읽기=2847
```

2회차의 `캐시읽기=2847` 이 적중입니다. 그만큼의 입력 토큰이 할인된 요금으로 계산됩니다.

### 직접 조립할 때

`createAgent` 로 직접 조립하면 캐싱은 **자동으로 안 붙습니다.** 넣어야 합니다.

```ts
import { anthropicPromptCachingMiddleware, bedrockPromptCachingMiddleware } from "langchain";

middleware: [
  // ... 나머지 스택
  anthropicPromptCachingMiddleware({ unsupportedModelBehavior: "ignore", minMessagesToCache: 1 }),
]
```

`unsupportedModelBehavior: "ignore"` 가 중요합니다. 이게 없으면 OpenAI 모델로 바꿔 돌릴 때 터집니다. `"ignore"` 면 조용히 아무것도 안 합니다 — 모델을 바꿔가며 테스트하는 코드에서 필수입니다.

> ⚠️ **함정 (캐시가 조용히 안 먹는다)**: 프롬프트 캐싱은 **접두사가 바이트 단위로 동일**할 때만 적중합니다. 앞부분에 조금이라도 변하는 게 있으면 매 요청 캐시를 새로 만들고(비쌉니다) 읽기는 0입니다. 흔한 파괴 요인:
> - 시스템 프롬프트에 **타임스탬프나 랜덤 ID** 를 넣는 미들웨어 (현재 시각 주입, 요청 ID 태깅 등)
> - 매 요청 **도구 순서가 바뀌는** 코드 (`Object.values(toolMap)` 같은 것)
> - `MemoryMiddleware` 가 갱신된 메모리를 프롬프트 앞쪽에 다시 주입
>
> 마지막 것 때문에 `createDeepAgent` 는 **`MemoryMiddleware` 를 캐싱 미들웨어보다 뒤에** 놓습니다 — 메모리 갱신이 캐시를 무효화하지 않게 하려는 의도적 배치입니다. 순서가 성능 설계인 사례입니다. 캐시가 안 먹으면 **비용이 오히려 늘어납니다**(캐시 쓰기가 일반 입력보다 비쌈). `캐시읽기` 가 계속 0이면 반드시 원인을 찾으세요.

> 💡 **실무 팁**: Anthropic 캐시의 기본 TTL 은 5분입니다. 사용자가 5분 넘게 자리를 비우면 다음 턴은 캐시 미스입니다. 그래서 캐싱은 **연속적인 에이전트 루프**(도구를 열 번 부르며 몇 초 안에 도는)에서 가장 크게 먹힙니다 — 정확히 Deep Agent 의 워크로드입니다. 반대로 사람이 띄엄띄엄 말하는 챗봇에서는 효과가 작습니다. OpenAI 는 프롬프트 캐싱이 자동이라 미들웨어가 필요 없습니다.

---

## 8-8. 종합 — 최소 Deep Agent 를 직접 조립

배운 걸 다 씁니다. "파일시스템 + 계획 + 서브에이전트" 만 있는 경량 리서치 에이전트입니다.

```ts
import { TASK_SYSTEM_PROMPT } from "deepagents";

const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

// 서브에이전트용 스택을 변수로 빼서 명시 (8-6)
const subagentMiddleware = [
  createFilesystemMiddleware({ backend }),
  createPatchToolCallsMiddleware(),
];

const agent = createAgent({
  model: MODEL,
  tools: [search],
  systemPrompt: `당신은 언어 리서처입니다.

## 워크플로
1. 조사할 언어가 2개 이상이면 write_todos 로 계획을 세운다.
2. 각 언어는 researcher 서브에이전트에게 task 로 위임한다.
3. 결과를 /report.md 에 write_file 로 저장한다.

## 도구 사용 규칙
- search 가 "NOT_FOUND" 를 반환하면 "자료 없음" 으로 표기한다. 지어내지 않는다.
- 파일 경로는 항상 /로 시작한다.

## 중단 조건
- /report.md 를 쓴 뒤 즉시 종료한다.

${TASK_SYSTEM_PROMPT}`,          // ← 내장 task 지침 재사용. export 되어 있다.
  middleware: [
    todoListMiddleware(),
    createFilesystemMiddleware({ backend }),
    createSubAgentMiddleware({
      defaultModel: MODEL,
      defaultTools: [search],
      defaultMiddleware: subagentMiddleware,   // ← 서브에이전트에도 파일시스템 (8-6)
      subagents: [
        {
          name: "researcher",
          description: "언어 하나를 조사해 3줄 요약을 반환합니다.",
          systemPrompt: "search 로 조사하고 3줄로 요약하세요. NOT_FOUND 면 '자료 없음' 이라고만 답하세요.",
          tools: [search],
        },
      ],
      generalPurposeAgent: false,              // general-purpose 는 안 쓴다 (토큰 절약)
    }),
    anthropicPromptCachingMiddleware({ unsupportedModelBehavior: "ignore", minMessagesToCache: 1 }),
  ],
}).withConfig({ recursionLimit: 10000 });

const result = await agent.invoke({
  messages: [{ role: "user", content: "Rust 와 Go 와 Zig 를 비교 조사해줘." }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
최종 응답: /report.md 에 비교 리포트를 작성했습니다. Rust 와 Go 는 조사되었고 Zig 는 자료가 없었습니다.
파일: [ '/report.md' ]
```

`TASK_SYSTEM_PROMPT` 를 붙인 것에 주목하세요. `createSubAgentMiddleware` 는 `systemPrompt` 옵션의 기본값으로 이미 이걸 붙이므로 사실 중복입니다 — 하지만 "직접 조립할 때 어떤 상수가 export 되어 있는지" 를 보여주기 위한 예시입니다. 실제로는 미들웨어가 알아서 붙이니 빼도 됩니다.

### 얻은 것과 잃은 것

| | |
|---|---|
| **얻음** | 스택이 코드에 명시적으로 보인다. `general-purpose` 를 껐고 요약을 뺐으니 프롬프트/토큰이 가볍다. 어느 미들웨어가 뭘 하는지 팀원이 읽을 수 있다. |
| **잃음** | `BASE_AGENT_PROMPT` (직접 써야 함). 긴 대화의 자동 요약/오프로드 → **컨텍스트 초과 시 그냥 에러**. 부모에 `patchToolCalls` 를 안 넣어 도구 호출 짝이 깨지면 provider 에러. 프로파일/권한/HITL 배선. |

**대부분의 경우 `createDeepAgent` + `middleware` 옵션이 정답입니다.** 직접 조립은 (a) Deep Agent 의 일부만 필요하거나, (b) 스택 순서를 근본적으로 바꿔야 하거나, (c) 디버깅으로 이분 탐색할 때만 쓰세요.

---

## 정리

**`createDeepAgent` 의 정체**

```ts
createDeepAgent(params)
  ≡ createAgent({
      model, systemPrompt: prefix+base+suffix, tools, middleware: [기본 스택], ...
    }).withConfig({ recursionLimit: 10000 })
```

**기본 스택** (조건부 항목 포함)

| # | 미들웨어 | 세그먼트 | 조건 |
|---|---|---|---|
| 1 | `todoListMiddleware` | 기본 | 항상 |
| 2 | `SkillsMiddleware` | 기본 | `skills` 있을 때 |
| 3 | `FilesystemMiddleware` | 기본 | 항상 (**제거 불가**) |
| 4 | `subAgentMiddleware` | 기본 | 항상 (**제거 불가**) |
| 5 | `SummarizationMiddleware` | 기본 | 항상 |
| 6 | `patchToolCallsMiddleware` | 기본 | 항상 |
| 7 | `asyncSubAgentMiddleware` | 기본 | 비동기 서브에이전트 있을 때 |
| — | **내 `middleware`** | — | 새 이름이면 여기 삽입 |
| 8 | 프로파일 `extraMiddleware` | 꼬리 | 프로파일에 있을 때 |
| 9 | 프롬프트 캐싱 | 꼬리 | Anthropic / Bedrock 모델 |
| 10 | `MemoryMiddleware` | 꼬리 | `memory` 있을 때 |
| 11 | `HumanInTheLoopMiddleware` | 꼬리 | `interruptOn` 있을 때 |

**핵심 함정 3가지**

1. **`middleware` 는 추가지만, 이름이 같으면 조용한 교체다.** 병합 키는 `.name` 문자열이다. `langchain` 의 `summarizationMiddleware` 는 deepagents 것과 **이름이 같아서**, 넣는 순간 파일 오프로드 기능을 잃는다. 그러면 에이전트가 요약 뒤에 자기가 쓴 파일 경로를 잊는다 — 증상은 "가끔 결과가 부실함" 이라 추적이 어렵다.
2. **서브에이전트는 부모의 `middleware` 를 상속하지 않는다.** 직접 조립하면서 `createSubAgentMiddleware({ defaultMiddleware })` 를 빠뜨리면 서브에이전트가 파일 도구 없이 태어난다. 중간 과정이 안 보이므로 "저장했습니다" 라는 거짓 보고까지 받는다. `createDeepAgent` 는 이 배선을 자동으로 해준다.
3. **미들웨어 순서는 성능 설계이기도 하다.** 배열 앞이 바깥(먼저 진입, 나중에 반환). `MemoryMiddleware` 가 캐싱 뒤에 있는 건 메모리 갱신이 프롬프트 캐시를 무효화하지 않게 하려는 의도다. 순서를 함부로 바꾸면 캐시 적중률이 0이 되고 **비용이 오히려 는다**.

**export 여부**: `TASK_SYSTEM_PROMPT`, `DEFAULT_SUBAGENT_PROMPT`, `GENERAL_PURPOSE_SUBAGENT`, `REQUIRED_MIDDLEWARE_NAMES` 는 export 됨. **`BASE_AGENT_PROMPT` 는 export 안 됨** — 직접 조립 시 base 프롬프트는 손으로 써야 한다.

**LangChain 코스 연결**: 미들웨어 개념과 내장 미들웨어 목록은 [Step 11 — 내장 미들웨어](../../langchain/step-11-middleware-builtin/), `createMiddleware` 로 훅을 직접 쓰는 법은 [Step 12 — 커스텀 미들웨어](../../langchain/step-12-middleware-custom/) 를 보세요.

---

## 연습문제

1. `createDeepAgent` 에 도구를 **하나도 안 주고**(`tools` 생략) 인스펙터를 붙여, 모델에게 보이는 도구 목록을 출력하세요. 몇 개가 나오고, 각각 어느 미들웨어가 등록한 것인지 주석으로 매핑하세요.
2. `createDeepAgent` 에 `name: "read_file"` 인 커스텀 도구를 주고 `try/catch` 로 감싸 실행하세요. 무슨 일이 일어나는지 출력하고, 왜 그런지 설명하세요.
3. `createFilesystemMiddleware`, `createSubAgentMiddleware`, `createSummarizationMiddleware`, `createPatchToolCallsMiddleware`, `todoListMiddleware`, 그리고 `langchain` 의 `summarizationMiddleware` 를 각각 만들어 **`.name` 을 전부 출력**하세요. 팩토리 이름과 `.name` 이 다른 것을 찾아 표로 정리하세요.
4. `createAgent` + 미들웨어로 `createDeepAgent` 와 **도구 집합이 동일한** 에이전트를 조립하세요. 두 도구 집합의 차집합을 양방향으로 출력해 `[]`, `[]` 가 나오는 것을 보이세요. 프롬프트 길이는 왜 다른지 설명하세요.
5. `createFilesystemMiddleware({ backend, tools: ["ls", "glob"] })` 를 시도해 보세요. 정상 동작하나요? `read_file` 을 빼면 어떻게 되는지 확인하고, 문서상의 제약을 근거로 설명하세요.
6. `createDeepAgent` 에 (a) 새 이름의 커스텀 미들웨어, (b) `name: "SummarizationMiddleware"` 인 커스텀 미들웨어를 각각 넣고 도구 목록과 동작을 비교하세요. (b) 에서 **아무 에러가 안 나는 것**을 확인하고, 실제로 무엇이 교체되었는지 설명하세요.
7. `createAgent` 로 서브에이전트를 가진 에이전트를 두 벌 만드세요. 하나는 `createSubAgentMiddleware` 에 `defaultMiddleware` 를 주고, 하나는 주지 마세요. 서브에이전트에게 파일 저장을 시켜 `state.files` 결과를 비교하세요.
8. `usage_metadata` 스파이를 붙여 Anthropic 모델로 2턴 대화를 돌리고 `cache_read` 가 0보다 커지는 것을 확인하세요. 그다음 커스텀 미들웨어의 `wrapModelCall` 에서 `request.systemMessage.concat(\`\n현재 시각: ${new Date().toISOString()}\`)` 로 **매 요청 변하는 값을 프롬프트에 주입**하고 다시 돌려 `cache_read` 가 어떻게 되는지 관찰하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 09 — HITL과 권한 제어](../step-09-hitl-permissions/)

8-1 스택의 11번 `humanInTheLoopMiddleware` 와 `createFilesystemMiddleware` 의 `permissions` 옵션을 이 스텝에서는 표에만 적어두고 넘어갔습니다. 다음 스텝에서 그 둘을 제대로 다룹니다. 미들웨어 스택을 이해하고 나면 "왜 HITL 이 스택의 맨 마지막인가"(도구 호출을 가장 안쪽에서 가로채야 하니까)가 저절로 이해될 것입니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(8-1 ~ 8-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 **createDeepAgent 와 직접 조립한 에이전트의 도구 목록이 같다는 것**을 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 푼 뒤, `solution.ts` 로 채점하는 흐름입니다. 세 파일 모두 `ANTHROPIC_API_KEY` 가 필요합니다. OpenAI 로 돌리려면 각 파일 상단의 `MODEL` 상수만 `"openai:gpt-5.5"` 로 바꾸면 되지만, **8-7 의 프롬프트 캐싱은 Anthropic/Bedrock 전용**이라 그 절만 무의미해집니다(`unsupportedModelBehavior: "ignore"` 덕분에 에러는 안 납니다).

### practice.ts

본문을 따라가며 실행할 예제를 `[8-1] ~ [8-8]` 주석 번호로 묶은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다.

- 파일 맨 위의 `makeInspector(label, sink)` 가 이 스텝의 관측 도구입니다. Step 07 의 프롬프트 스파이를 확장해 `request.tools` 까지 봅니다. `sink` 객체를 넘기면 결과를 **바깥 변수로 빼낼 수 있어서**, `[8-3]` 의 두 에이전트 비교가 가능해집니다. `done` 플래그로 첫 모델 호출만 찍으므로 도구를 여러 번 부르는 예제에서도 출력이 깔끔합니다. 그리고 **항상 미들웨어 배열 맨 뒤에** 둔 것에 주의하세요 — 앞에 두면 다른 미들웨어가 프롬프트를 덧붙이기 전을 보게 되어 `fs=false` 가 나옵니다(8-6 의 양파 구조).
- `[8-1]` 의 `step8_1()` 위 주석 블록이 이 파일에서 가장 중요합니다. deepagents 소스에서 확인한 **실제 스택 순서 11단계**가 그대로 적혀 있습니다. 본문 표와 대조하며 읽으세요.
- `[8-3]` 이 이 스텝의 증명입니다. `deepSink` 와 `manualSink` 두 개를 만들어 도구 집합의 차집합을 양방향으로 찍습니다. `도구 집합 동일: true` 가 나오는 것이 핵심이고, 바로 아래 `프롬프트 길이` 가 다른 것이 "BASE_AGENT_PROMPT 는 export 안 됨" 의 증거입니다.
- `[8-5]` 의 (B) 는 **일부러 함정에 빠지는 코드**입니다. `summarizationMiddleware` 를 `langchain` 에서 import 해서 넣는데, 실행해도 에러가 안 나고 도구 목록도 그대로입니다. 아무 일도 안 일어난 것처럼 보이는 것이 이 함정의 무서운 점입니다. 본문 8-5 의 함정 블록과 반드시 같이 읽으세요.
- `[8-6]` 은 실패하는 조립과 성공하는 조립을 **연달아** 돌립니다. `[틀린 조립]` 의 `파일: []` 과 `[올바른 조립]` 의 `파일: [ '/notes.md' ]` 를 나란히 보는 것이 목적입니다. 틀린 쪽에서 모델이 "저장했습니다" 라고 거짓 보고를 하는 경우도 있으니, **응답 텍스트가 아니라 `파일:` 줄을 믿으세요.** 이게 서브에이전트 디버깅의 기본자세입니다.
- `[8-7]` 은 모델을 2회 호출합니다. `캐시읽기` 가 2회차에 0보다 커지면 성공입니다. 5분 TTL 이라 디버거로 중간에 오래 멈추면 미스가 납니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 함수 본문이 비어 있습니다.

- 상단의 `makeInspector`, `backend`, `saveNote` 도구는 **그대로 쓰라고 준 준비물**입니다. 특히 `backend` 는 `(config) => new StateBackend(config)` 팩토리 형태인데, 이건 `createDeepAgent` 의 실제 기본값과 정확히 같습니다. StateBackend 는 런타임 state 가 필요해서 인스턴스가 아니라 팩토리로 넘겨야 한다는 점을 기억하세요.
- `[문제 2]` 와 `[문제 5]` 는 **일부러 실패시키는 문제**입니다. `try/catch` 로 감싸서 에러 메시지를 출력하는 것이 정답입니다. 에러가 났다고 잘못 푼 게 아닙니다 — 어떤 에러가 어느 시점에 나는지가 답입니다.
- `[문제 3]` 은 API 를 안 호출합니다. 미들웨어를 만들어 `.name` 만 찍으면 되므로 **비용이 0이고 즉시 끝납니다.** 여기서 얻은 이름표가 `[문제 6]` 의 전제이므로 먼저 푸세요.
- `[문제 4]` 는 `[문제 3]` 의 답을 알아야 풀 수 있고, `[문제 6]` 은 `[문제 3]`, `[문제 7]` 은 `[문제 4]` 의 조립 코드를 재활용합니다. **순서대로 푸세요.**
- `[문제 8]` 은 두 번 돌려서 `cache_read` 를 비교해야 합니다. 두 번째 실행에서 캐시가 깨지는 것을 보는 게 목적이므로, 첫 번째 실행의 숫자를 메모해 두세요.
- 파일을 그대로 실행하면 함수 본문이 비어 있어 헤더만 8줄 찍히고 끝납니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 1]` 의 도구 9개(도구를 안 줬으므로 8개)와 미들웨어 매핑표가 이 스텝 전체의 요약입니다. 여기서 헷갈리면 8-1 로 돌아가세요.
- `[정답 2]` 의 에러 메시지는 `Tool name(s) [read_file] conflict with built-in tools.` 이고 **`ConfigurationError`** 타입입니다. `createDeepAgent` 호출 시점에 던집니다 — invoke 전에 터지므로 API 비용이 안 듭니다. 조용히 덮어쓰지 않는 좋은 설계의 예로 기억하세요.
- `[정답 3]` 이 이 파일에서 가장 실용적입니다. 출력 표를 보면 `createSubAgentMiddleware` → `subAgentMiddleware`(소문자 s), `createPatchToolCallsMiddleware` → `patchToolCallsMiddleware`(소문자 p) 로 **일관성이 없습니다.** 그리고 deepagents 의 `createSummarizationMiddleware` 와 langchain 의 `summarizationMiddleware` 가 **둘 다 `SummarizationMiddleware`** 로 찍히는 것이 `[정답 6]` 의 복선입니다.
- `[정답 5]` 는 `tools: ["ls", "glob"]` 에 `read_file` 이 빠진 경우입니다. 문서에 "`read_file` must be included in every explicit array" 라고 명시되어 있습니다 — 큰 도구 결과를 파일로 오프로드한 뒤 되읽는 복구 경로가 `read_file` 을 쓰기 때문입니다.
- `[정답 6]` 의 (b) 는 **아무 에러도 안 나고 도구 목록도 동일**합니다. 그게 정답입니다. 교체되었다는 증거는 도구 목록이 아니라 **긴 대화에서 `/conversation_history` 파일이 안 생기는 것**으로 드러납니다. 해설 주석에 그 확인법이 적혀 있습니다.
- `[정답 7]` 은 응답 텍스트를 믿지 말고 `state.files` 를 보라는 교훈을 코드로 박아뒀습니다. `defaultMiddleware` 없는 쪽이 `files: []` 인데도 모델이 "저장 완료" 라고 말하는 경우가 실제로 나옵니다. 서브에이전트의 중간 과정이 부모에게 안 보인다는 [Step 06](../step-06-subagents/) 의 성질이 여기서 **디버깅 난이도**로 돌아옵니다.
- `[정답 8]` 은 시각 주입 전후의 `cache_read` 를 대조합니다. 주입 후에는 매 요청 `cache_creation` 만 발생하고 `cache_read` 가 0으로 붙박입니다. **캐시 미스는 공짜가 아니라 손해**(캐시 쓰기가 일반 입력보다 비쌈)라는 점을 숫자로 확인하세요.

```ts file="./solution.ts"
```
