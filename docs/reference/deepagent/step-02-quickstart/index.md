# Step 02 — 첫 Deep Agent

> **학습 목표**
> - `deepagents` 를 설치하고 **peer dependency** 가 한 벌인지 검증한다
> - `deepagents` / `deepagents/browser` / `deepagents/node` **세 엔트리포인트**를 구분해 고른다
> - `createDeepAgent` 로 에이전트를 만들고 **`await` 를 어디에 붙여야 하는지** 안다
> - `CreateDeepAgentParams` **17개 옵션 전체**의 지도를 그리고, 어느 스텝에서 뭘 배울지 안다
> - 도구를 안 줘도 실리는 **내장 도구 8개**를 직접 찍어서 확인한다
> - 커스텀 도구를 붙이고, 모델을 바꾸고, `stream` 으로 실행 흐름을 관찰한다
>
> **선행 스텝**: [Step 01 — Deep Agent란 무엇인가](../step-01-why-deep-agents/)
> **예상 소요**: 60분

[Step 01](../step-01-why-deep-agents/) 에서 `createDeepAgent` 가 "`createAgent` + 미들웨어 묶음"이라는 걸 봤습니다. 스파이 미들웨어로 도구 8개와 프롬프트 6,979자를 훔쳐보기도 했죠.

이 스텝에서는 그걸 정식으로 다룹니다. 설치부터 시작해서 옵션 17개의 지도를 펼치고, 도구를 붙이고, 모델을 바꾸고, 실행 흐름을 눈으로 봅니다. **새 개념보다 지도를 그리는 게 목적**입니다 — 여기서 본 옵션 하나하나가 앞으로 10개 스텝의 목차가 됩니다.

---

## 2-1. 설치와 peer dependency

```bash
npm install deepagents langchain @langchain/core
```

세 개를 **같이** 설치하는 게 핵심입니다. 왜 `deepagents` 하나로 안 될까요?

`deepagents` 는 이 패키지들을 **peer dependency** 로 선언합니다. 직접 찍어 봅시다.

```ts
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);

const meta = require("deepagents/package.json") as { peerDependencies?: Record<string, string> };
console.log(meta.peerDependencies);
```

**결과** (`deepagents@1.11.0` — 결정적입니다)

```
deepagents 가 요구하는 peer dependency:
  @langchain/core                    ^1.2.0
  @langchain/langgraph               ^1.4.4
  @langchain/langgraph-checkpoint    ^1.1.2
  @langchain/langgraph-sdk           ^1.9.23
  langchain                          ^1.5.0
  langsmith                          ^0.7.1
```

peer dependency 는 **"내가 깔 테니 넌 빠져"가 아니라 "네가 깐 걸 같이 쓰겠다"** 는 선언입니다. `deepagents` 가 만든 `AIMessage` 와 여러분이 `@langchain/core` 에서 import 한 `AIMessage` 가 **같은 클래스**여야 하기 때문입니다.

### 설치 직후 반드시 확인할 것

```bash
npm ls @langchain/core
```

```
deepagent-course@1.0.0
├─┬ @langchain/anthropic@1.5.1
│ └── @langchain/core@1.2.3 deduped
├── @langchain/core@1.2.3
├─┬ deepagents@1.11.0
│ ├── @langchain/core@1.2.3 deduped
│ └── langchain@1.5.3 deduped
└─┬ langchain@1.5.3
  └── @langchain/core@1.2.3 deduped
```

**`deduped`** 라는 단어와 `@langchain/core` 가 **1.2.3 하나뿐**이라는 게 핵심입니다.

코드로 검증할 수도 있습니다. 여러 패키지 입장에서 각각 `@langchain/core` 를 resolve 해 보고, 경로가 전부 같으면 한 벌입니다.

```ts
const coreResolvedFrom = ["deepagents", "langchain", "@langchain/anthropic"].map((from) => {
  const req = createRequire(require.resolve(`${from}/package.json`));
  return req.resolve("@langchain/core/package.json");
});
const uniquePaths = [...new Set(coreResolvedFrom)];
console.log(`서로 다른 사본 개수: ${uniquePaths.length}`);
```

**결과**

```
@langchain/core 버전: 1.2.3
서로 다른 사본 개수: 1
  ✅ 한 벌입니다 — instanceof 가 정상 동작합니다.
```

> ⚠️ **함정 — `@langchain/core` 가 두 벌이면 `instanceof` 가 조용히 false 가 된다**
>
> peer 버전 범위가 어긋나면 npm 이 친절하게도 사본을 하나 더 깔아 줍니다.
>
> ```
> node_modules/@langchain/core                          ← 1.2.3
> node_modules/deepagents/node_modules/@langchain/core  ← 1.1.0  ⚠️
> ```
>
> 이러면 `AIMessage` 가 **서로 다른 두 개의 클래스**가 됩니다. `deepagents` 가 만든 메시지를 여러분이 import 한 클래스로 `instanceof` 검사하면 **에러 없이 그냥 `false`** 입니다.
>
> ```ts
> const last = result.messages.at(-1);
> if (last instanceof AIMessage) {   // ← 항상 false
>   console.log("도구 호출:", last.tool_calls);  // ← 절대 실행 안 됨
> }
> ```
>
> 조건문이 통째로 죽는데 에러는 없습니다. "왜 도구 호출을 못 잡지?" 하며 몇 시간을 태웁니다.
>
> **해결**: `rm -rf node_modules package-lock.json && npm install`.
> **예방**: `@langchain/core` 를 `package.json` 에 **직접 명시**하세요. 그래야 npm 이 하나로 고정합니다.
> **회피**: 라이브러리를 만든다면 `instanceof` 대신 필드 존재 여부(duck typing)로 검사하세요. 이 코스의 `src/lib/print.ts` 가 그렇게 짜여 있습니다.

---

## 2-2. 엔트리포인트 3종

`deepagents` 는 서브패스 export 를 세 개 가집니다.

| import 경로 | 용도 |
|---|---|
| `deepagents` | **기본**. Node 에서 그냥 이걸 쓰면 됩니다 |
| `deepagents/browser` | 브라우저·엣지 런타임. Node 전용 심볼이 **빠져** 있습니다 |
| `deepagents/node` | 명시적 Node. 번들러가 헷갈릴 때 |

말로는 안 와닿으니 직접 세어 봅시다.

```ts
const mainMod = await import("deepagents");
const browserMod = await import("deepagents/browser");
const nodeMod = await import("deepagents/node");

const main = Object.keys(mainMod).sort();
const browser = Object.keys(browserMod).sort();
const browserSet = new Set(browser);
const missingInBrowser = main.filter((k) => !browserSet.has(k));
console.log(missingInBrowser);
```

**결과** (결정적입니다)

```
deepagents         : export 50개
deepagents/browser : export 41개
deepagents/node    : export 50개

deepagents 에는 있는데 /browser 에는 없는 것 (9개):
  - FilesystemBackend
  - LocalShellBackend
  - SUBAGENT_RESPONSE_FORMAT_CONFIG_KEY
  - createAgentMemoryMiddleware
  - createSettings
  - createSubAgent
  - findProjectRoot
  - listSkills
  - parseSkillMetadata

deepagents 와 /node 의 차이: 0개
```

빠진 9개의 공통점이 보이시나요? **전부 실제 디스크나 프로세스를 건드리는 것들**입니다. `FilesystemBackend`(실제 파일 읽기/쓰기), `LocalShellBackend`(셸 실행), `findProjectRoot`(경로 탐색), `listSkills`(디렉터리 스캔)… 브라우저에는 `fs` 모듈이 없으니 당연히 뺀 것입니다.

반대로 `StateBackend`(메모리 안의 가상 파일시스템)와 `StoreBackend` 는 **양쪽에 다 있습니다.** 디스크를 안 쓰니까요.

### 선택 기준

| 상황 | 고를 것 |
|---|---|
| Node 서버, CLI, 스크립트 | `deepagents` |
| 브라우저, Cloudflare Workers, Vercel Edge | `deepagents/browser` |
| Node 인데 번들러가 browser 필드를 잘못 골라 `fs` 관련 에러가 날 때 | `deepagents/node` |

**Node 환경에서 `deepagents` 와 `deepagents/node` 는 export 가 완전히 같습니다** (차이 0개). 그래서 평소엔 짧은 쪽을 쓰면 됩니다.

> ⚠️ **함정 — 번들러가 여러분 몰래 `browser` 엔트리를 고른다**
>
> `deepagents` 의 `package.json` 을 열어 보면 `"."` 안에 이런 게 있습니다.
>
> ```json
> ".": {
>   "browser": "./dist/browser.js",
>   "import": { "types": "./dist/index.d.ts", "default": "./dist/index.js" }
> }
> ```
>
> `"browser"` 조건이 **먼저** 걸려 있습니다. webpack/vite 같은 번들러는 기본적으로 `browser` 조건을 우선하므로, 여러분이 `import { FilesystemBackend } from "deepagents"` 라고 써도 번들러가 `browser.js` 를 물어 옵니다. 그럼 `FilesystemBackend` 가 `undefined` 가 되고, 런타임에 이렇게 터집니다.
>
> ```
> TypeError: FilesystemBackend is not a constructor
> ```
>
> import 문은 멀쩡하고 타입 체크도 통과합니다 — tsc 는 `index.d.ts` 를 보는데 번들러는 `browser.js` 를 물어 오기 때문입니다. **타입과 런타임이 서로 다른 파일을 보는 상태**라 특히 찾기 어렵습니다.
>
> Node 용 번들을 만드는데 이 에러가 나면 **`deepagents/node` 로 명시**하세요. 조건부 해석을 우회합니다.

> 💡 **실무 팁 — tsconfig 의 `moduleResolution` 을 `NodeNext` 로**
>
> 구버전 `"moduleResolution": "node"` 는 `exports` 맵을 **아예 못 읽습니다.** 그래서 `import { FilesystemBackend } from "deepagents/node"` 라고 쓰면 `Cannot find module 'deepagents/node' or its corresponding type declarations` 가 납니다. 모듈은 멀쩡히 있는데도요.
>
> `NodeNext` 로 두면 tsc 가 Node 와 같은 규칙으로 exports 를 읽습니다. 이 코스의 `project/tsconfig.json` 이 그렇게 되어 있습니다.

---

## 2-3. `createDeepAgent` 첫 실행

이제 만들어 봅시다.

```ts
import { createDeepAgent } from "deepagents";
import { tool } from "langchain";
import * as z from "zod";

const getWeather = tool(({ city }: { city: string }) => `${city}는 언제나 맑음!`, {
  name: "get_weather",
  description: "주어진 도시의 날씨를 알려 줍니다",
  schema: z.object({ city: z.string().describe("도시 이름") }),
});

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "당신은 친절한 날씨 안내원입니다. 한국어로 답하세요.",
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "도쿄 날씨 알려 줘" }],
});

console.log(result.messages.at(-1)?.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
[ 0] human  도쿄 날씨 알려 줘
[ 1] ai     → 도구 호출: get_weather
[ 2] tool   ← get_weather: 도쿄는 언제나 맑음!
[ 3] ai     도쿄는 언제나 맑습니다! 오늘 나들이하기 좋은 날씨네요. ☀️

최종 답변: 도쿄는 언제나 맑습니다! 오늘 나들이하기 좋은 날씨네요. ☀️
```

### `await` 는 어디에 붙이는가

공식 문서는 `await createDeepAgent({...})` 로 씁니다. 이 코스도 그 표기를 따릅니다. 그런데 [Step 01 의 1-4](../step-01-why-deep-agents/) 에서 봤듯 **`deepagents@1.11.0` 에서 이 함수는 사실 동기**입니다.

```ts
const ret = createDeepAgent({ model: MODEL });
console.log(ret instanceof Promise);   // false
console.log(ret.constructor.name);     // "ReactAgent"
```

그래도 `await` 를 붙이세요. Promise 가 아닌 값에 `await` 를 붙이면 JS 는 그냥 그 값을 돌려주므로 **손해가 없고**, 문서 표기와 같아지고, 향후 버전이 실제로 비동기가 되어도 안 깨집니다.

> ⚠️ **함정 — 진짜 위험한 건 `invoke` 앞의 `await` 다**
>
> `createDeepAgent` 의 `await` 는 있으나 없으나 그만이지만, **`agent.invoke()` 는 진짜 비동기**입니다. 여기서 `await` 를 빠뜨리면:
>
> ```ts
> const result = agent.invoke({ messages: [...] });   // ← await 없음
> console.log(result.messages.at(-1)?.text);
> // TypeError: Cannot read properties of undefined (reading 'at')
> ```
>
> `result` 가 `Promise` 라서 `.messages` 가 `undefined` 입니다. 에러 메시지가 `invoke` 를 가리키지 않고 **한참 아래 줄**을 가리켜서 헷갈립니다.
>
> 더 고약한 건 `await` 없이 `invoke` 를 부르고 결과를 안 쓰는 경우입니다. 에이전트는 백그라운드에서 조용히 돌면서 **토큰을 태우고**, 예외가 나면 `unhandled rejection` 으로 프로세스를 죽입니다. 에러 위치는 당연히 엉뚱한 곳입니다.
>
> 규칙은 간단합니다. **`createDeepAgent` 에 붙이는 `await` 는 예의, `invoke` / `stream` 에 붙이는 `await` 는 필수.**

---

## 2-4. `CreateDeepAgentParams` — 옵션 17개 전체 지도

`createDeepAgent` 가 받는 옵션은 **17개**이고, **전부 optional** 입니다. `createDeepAgent({})` 도 됩니다.

이 표가 이 스텝의 핵심이자 **앞으로 10개 스텝의 목차**입니다.

| 옵션 | 한 줄 설명 | 다루는 곳 |
|---|---|---|
| `model` | 쓸 모델. `"provider:model"` 문자열 또는 모델 인스턴스 | **2-7** |
| `tools` | 붙일 커스텀 도구. 내장 8개에 **더해진다** | **2-6** |
| `systemPrompt` | 커스텀 지침. 내장 프롬프트 **앞**에 붙는다 | [Step 07](../step-07-prompting/) |
| `subagents` | 위임용 서브에이전트 목록 | [Step 06](../step-06-subagents/) |
| `middleware` | 표준 미들웨어 **뒤**에 덧붙일 미들웨어 | [Step 08](../step-08-middleware/) |
| `backend` | 파일이 실제로 저장될 곳. 기본은 `StateBackend` | [Step 05](../step-05-backends/) |
| `permissions` | 파일시스템 경로별 접근 제어(glob 규칙) | [Step 05](../step-05-backends/) |
| `interruptOn` | 실행 전 사람 승인이 필요한 도구 지정 | [Step 09](../step-09-hitl-permissions/) |
| `checkpointer` | 실행 간 상태 저장. 대화를 이어가려면 필수 | [Step 10](../step-10-memory-skills/) |
| `store` | 스레드를 넘는 장기 메모리 저장소 | [Step 10](../step-10-memory-skills/) |
| `memory` | 시작 시 읽어들일 `AGENTS.md` 경로 목록 | [Step 10](../step-10-memory-skills/) |
| `skills` | 필요할 때만 불러올 `SKILL.md` 디렉터리 | [Step 10](../step-10-memory-skills/) |
| `responseFormat` | 구조화된 출력 스키마(zod) | [Step 11](../step-11-streaming-production/) |
| `contextSchema` | 실행마다 주입할 런타임 컨텍스트(`userId`, API 키 등) | [Step 11](../step-11-streaming-production/) |
| `stateSchema` | `messages`/`todos`/`files` 외에 추가할 커스텀 상태 | [Step 11](../step-11-streaming-production/) |
| `streamTransformers` | 스트림 변환기. `streamEvents(..., { version: "v3" })` 에서 노출 | [Step 11](../step-11-streaming-production/) |
| `name` | 에이전트 이름 | — |

### 헷갈리기 쉬운 세 쌍

**`stateSchema` vs `contextSchema`** — 둘 다 zod 스키마를 받지만 수명이 다릅니다.

| | `stateSchema` | `contextSchema` |
|---|---|---|
| 지속 | checkpointer 가 있으면 **실행 간 유지** | **실행 1회 한정** |
| 용도 | 대화 기록, 누적 결과 | `userId`, API 키, 기능 플래그 |
| 접근 | 미들웨어/훅에서 state 로 | 도구 안에서 `runtime.context` 로 |

**`checkpointer` vs `store`** — 전자는 **한 스레드 안**의 상태를(대화 이어가기), 후자는 **스레드를 넘는** 기억을(어제 대화 기억) 담당합니다.

**`memory` vs `skills`** — 둘 다 마크다운 파일을 읽지만, `memory`(`AGENTS.md`)는 **시작할 때 항상** 로드되고, `skills`(`SKILL.md`)는 **필요할 때만** 로드됩니다. 후자를 progressive disclosure 라고 부릅니다. 항상 로드하면 컨텍스트를 먹으니까요 — [Step 01 의 1-5](../step-01-why-deep-agents/) 에서 본 유한 자원 문제입니다.

> 💡 **실무 팁 — 17개를 다 외우려 하지 마세요**
>
> 실무에서 처음 만드는 Deep Agent 는 보통 3~4개만 씁니다.
>
> ```ts
> const agent = await createDeepAgent({
>   model,          // 거의 항상
>   tools,          // 거의 항상
>   systemPrompt,   // 거의 항상
>   subagents,      // 작업이 커지면
> });
> ```
>
> 나머지는 **문제가 생겼을 때** 하나씩 켭니다. 대화가 안 이어지면 `checkpointer`, 파일을 실제 디스크에 쓰고 싶으면 `backend`, 위험한 도구가 있으면 `interruptOn`. 이 표는 외우는 게 아니라 **문제가 생겼을 때 찾아보는 색인**입니다.

---

## 2-5. 기본 제공 도구 관찰

[Step 01](../step-01-why-deep-agents/) 의 스파이 미들웨어를 다시 씁니다. `tools` 를 **하나도 안 주고** 무엇이 실리는지 봅시다.

```ts
import { createMiddleware } from "langchain";
import { AIMessage } from "@langchain/core/messages";

function createSpy(sink: { tools: string[] }) {
  return createMiddleware({
    name: "Spy",
    wrapModelCall: async (request) => {
      sink.tools = (request.tools ?? []).map((t) => (t as { name: string }).name);
      return new AIMessage("(가로챔)");   // handler 를 안 부른다 = 모델 호출 0회
    },
  });
}

const sink = { tools: [] as string[] };
const a = createDeepAgent({ model: MODEL, middleware: [createSpy(sink)] });
await a.invoke({ messages: [{ role: "user", content: "안녕" }] });
console.log(sink.tools.length, sink.tools.sort());
```

**결과** (결정적입니다)

```
tools 를 안 줬는데 실린 도구: 8개

  edit_file      파일 부분 수정(문자열 치환)
  glob           패턴으로 파일 찾기
  grep           파일 내용 검색
  ls             디렉터리 목록
  read_file      파일 읽기
  task           서브에이전트 띄우기
  write_file     파일 쓰기
  write_todos    할 일 목록 작성/갱신
```

### 도구 8개의 정체

| 도구 | 하는 일 | 어느 미들웨어가 | 기둥 |
|---|---|---|---|
| `ls` | 디렉터리 목록 | `FilesystemMiddleware` | 2 |
| `read_file` | 파일 읽기 (페이지네이션 지원) | `FilesystemMiddleware` | 2 |
| `write_file` | 파일 생성 | `FilesystemMiddleware` | 2 |
| `edit_file` | 문자열 치환으로 부분 수정 | `FilesystemMiddleware` | 2 |
| `glob` | 패턴으로 파일 찾기 (`**/*.ts`) | `FilesystemMiddleware` | 2 |
| `grep` | 파일 내용 검색 | `FilesystemMiddleware` | 2 |
| `task` | 서브에이전트 띄우기 | `SubAgentMiddleware` | 3 |
| `write_todos` | 할 일 목록 작성/갱신 | `TodoListMiddleware` | 1 |

**`execute`(셸 실행)는 목록에 없습니다.** 문서에는 내장 도구로 나오지만 **샌드박스 백엔드를 쓸 때만** 생깁니다. 기본 `StateBackend` 는 메모리 안의 가상 파일시스템이라 실행할 셸이 없습니다. [Step 05](../step-05-backends/) 에서 다룹니다.

> ⚠️ **함정 — 내장 도구를 끄는 옵션은 없다**
>
> 17개 옵션을 다시 보세요. `tools` 는 있어도 `disableTools` 같은 건 **없습니다.** 즉 `createDeepAgent` 를 쓰는 한 이 8개는 **항상** 실립니다.
>
> "우리 에이전트는 파일을 쓰면 안 되는데" 같은 요구가 있어도 옵션으로는 못 끕니다. 방법은 두 가지입니다.
>
> 1. **`permissions` 로 막기** — 도구는 남아 있되 실행이 거부됩니다. 모델은 여전히 시도하고 매번 거부 메시지를 받습니다(토큰 낭비). [Step 05](../step-05-backends/)
> 2. **`createAgent` 로 내려가 직접 조립** — `createFilesystemMiddleware` 를 빼고 `todoListMiddleware` 만 붙입니다. 진짜로 없앨 수 있습니다. [Step 08](../step-08-middleware/)
>
> `createDeepAgent` 는 **묶음 상품**입니다. 낱개로 사려면 한 층 내려가야 합니다. 이게 Step 01 에서 "하네스일 뿐"이라는 걸 강조한 실용적 이유입니다.

---

## 2-6. 커스텀 도구 추가

내 도구는 내장 8개를 **대체하지 않고 더해집니다.**

```ts
const searchDocs = tool(
  async ({ query }: { query: string }) => {
    return `"${query}" 검색 결과: (예시) LangGraph 는 상태 그래프 기반 프레임워크입니다.`;
  },
  {
    name: "search_docs",
    description:
      "사내 기술 문서를 검색합니다. 프레임워크 사용법이나 API 를 물어볼 때 쓰세요.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const agent = await createDeepAgent({
  model: MODEL,
  tools: [getWeather, searchDocs],
});
```

**결과** (결정적)

```
내장 8개 + 커스텀 2개 = 10개
  edit_file, get_weather, glob, grep, ls, read_file, search_docs, task, write_file, write_todos

새로 늘어난 것: get_weather, search_docs
```

`tool()` 의 세 요소를 짚고 갑시다.

| 요소 | 역할 |
|---|---|
| `name` | 모델이 부를 이름. snake_case 관례 |
| `description` | **모델이 읽는 설명.** 언제 쓸지 판단하는 유일한 근거 |
| `schema` | zod 스키마. 인자 모양 + `.describe()` 로 각 인자 설명 |

> ⚠️ **함정 — `description` 이 곧 프롬프트다**
>
> `description` 을 개발자 주석처럼 대충 쓰면 모델이 그 도구를 **안 부릅니다.** 에러는 없습니다. 그냥 도구가 없는 것처럼 행동하고, 아는 대로 지어내서 답합니다.
>
> ```ts
> // ❌ 나쁨 — 모델이 언제 써야 할지 모른다
> description: "검색"
>
> // ✅ 좋음 — 언제 쓸지가 적혀 있다
> description: "사내 기술 문서를 검색합니다. 프레임워크 사용법이나 API 를 물어볼 때 쓰세요."
> ```
>
> 좋은 `description` 은 "무엇을 하는가"가 아니라 **"언제 써야 하는가"** 를 적습니다. 모델이 "지금이 이 도구를 쓸 때인가?"를 판단해야 하기 때문입니다. 필요하면 쓰지 **말아야** 할 때도 적으세요.
>
> Deep Agent 의 내장 프롬프트가 `task` 하나 설명하는 데 2,000자 넘게 쓰는 것도 같은 이유입니다([Step 01](../step-01-why-deep-agents/) 연습문제 3번).
>
> **모델이 도구를 안 부르면, 모델을 탓하기 전에 `description` 부터 고치세요.**

> 💡 **실무 팁 — 커스텀 도구는 내장 도구와 겹치지 않게**
>
> 내장 8개가 항상 실린다는 걸 잊고 `read_file` 이라는 커스텀 도구를 만들면 **이름이 충돌**합니다. 비슷한 일을 하는 도구가 둘이면 모델은 헷갈려서 엉뚱한 걸 부릅니다.
>
> 도구를 만들기 전에 2-5 의 8개 목록을 확인하고, 겹치면 이름을 다르게(`read_s3_file` 등) 지으세요.

---

## 2-7. 모델 교체

세 가지 방법이 있습니다.

```ts
// (A) "provider:model" 문자열 — 가장 간단
const byString = createDeepAgent({ model: "anthropic:claude-sonnet-4-6" });

// (B) initChatModel — temperature 같은 파라미터를 줄 때
import { initChatModel } from "langchain/chat_models/universal";
const tuned = await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0 });
const byInit = createDeepAgent({ model: tuned });

// (C) 클래스 직접 생성 — provider 고유 옵션까지 다 쓸 때
import { ChatAnthropic } from "@langchain/anthropic";
const direct = new ChatAnthropic({ model: "claude-sonnet-4-6", maxTokens: 4096 });
const byClass = createDeepAgent({ model: direct });

// (D) 생략 — Anthropic 기본 모델
const byDefault = createDeepAgent({});
```

**결과**

```
(A) 문자열        : ReactAgent 생성됨
(B) initChatModel : ReactAgent 생성됨 (temperature: 0)
(C) 클래스 직접   : ReactAgent 생성됨 (maxTokens: 4096)
(D) model 생략    : ReactAgent 생성됨 (claude-sonnet-4-5-20250929)
```

### 모델 문자열의 형식

```
anthropic:claude-sonnet-4-6
   ↑             ↑
   provider      모델 식별자 (그대로 provider 에 전달)
```

공식 문서의 표현을 그대로 옮기면:

> "The provider prefix selects the LangChain integration, and everything after the colon is passed through to that provider as the model identifier."

| provider 접두사 | 예시 | 필요한 패키지 |
|---|---|---|
| `anthropic` | `anthropic:claude-sonnet-4-6` | `@langchain/anthropic` |
| `openai` | `openai:gpt-5.5` | `@langchain/openai` |
| `google-genai` | `google-genai:gemini-3.5-flash` | `@langchain/google-genai` |
| `ollama` | `ollama:llama3.3` | `@langchain/ollama` |

OpenAI 로 바꾸려면:

```bash
npm install @langchain/openai
```

```ts
const agent = await createDeepAgent({ model: "openai:gpt-5.5" });
```

> ⚠️ **함정 — `model` 을 생략하면 Anthropic 이 기본값이다**
>
> `createDeepAgent({})` 처럼 `model` 을 안 주면 조용히 **`claude-sonnet-4-5-20250929`** 가 쓰입니다(`deepagents@1.11.0` 의 타입 선언에 명시된 기본값).
>
> "우리는 OpenAI 만 쓰는데 왜 Anthropic 청구서가 오지?" 의 범인이 대개 이겁니다. 게다가 이 기본값은 **버전이 올라가면 바뀔 수 있습니다.** 코드는 그대로인데 어느 날 모델이 바뀌어 있으면 재현이 안 됩니다.
>
> **프로덕션에서는 `model` 을 항상 명시하세요.** 기본값에 기대지 마세요.

> 💡 **실무 팁 — 서브에이전트마다 다른 모델을 쓸 수 있습니다**
>
> 메인은 똑똑한 모델, 단순 검색 서브에이전트는 싸고 빠른 모델 — 이렇게 섞으면 비용이 크게 줍니다. `SubAgent` 에도 `model` 필드가 있습니다. [Step 06](../step-06-subagents/) 에서 다룹니다.
>
> 실행 중에 모델을 바꾸는 것도 됩니다. `wrapModelCall` 미들웨어에서 `request.model` 을 갈아끼우면 됩니다 — 2-5 에서 만든 스파이와 같은 훅입니다.

---

## 2-8. 실행 관찰 — `stream` 으로 흐름 보기

`invoke` 는 **다 끝나야** 결과를 줍니다. Deep Agent 는 수십 턴을 도는데 그동안 화면이 멈춰 있으면 답답할 뿐 아니라, 무엇보다 **디버깅이 안 됩니다.**

`stream` 을 쓰면 계획 → 파일 쓰기 → 서브에이전트 흐름이 실시간으로 보입니다.

```ts
for await (const [namespace, chunk] of await agent.stream(
  { messages: [{ role: "user", content: RESEARCH }] },
  { streamMode: "updates", subgraphs: true },
)) {
  const who = namespace.length === 0 ? "메인" : `서브(${namespace.join("|")})`;

  for (const [node, update] of Object.entries(chunk as Record<string, unknown>)) {
    const msgs = (update as { messages?: BaseMessage[] })?.messages ?? [];
    for (const m of msgs) {
      const calls = (m as { tool_calls?: { name?: string }[] }).tool_calls ?? [];
      if (calls.length > 0) {
        console.log(`[${who}] ${node} → 도구 호출: ${calls.map((c) => c.name).join(", ")}`);
      } else if (m.getType() === "tool") {
        console.log(`[${who}] ${node} ← ${(m as { name?: string }).name} 결과 (${m.text.length}자)`);
      }
    }
  }
}
```

**출력 예시** (모델 응답이므로 순서와 횟수는 매번 다릅니다)

```
[메인] model → 도구 호출: write_todos          ← 계획을 세운다
[메인] tools ← write_todos 결과 (52자)
[메인] model → 도구 호출: task                 ← 서브에이전트에 위임
[서브(tools:abc123)] model → 도구 호출: search_docs
[서브(tools:abc123)] tools ← search_docs 결과 (1841자)
[메인] tools ← task 결과 (612자)               ← 요약만 돌아온다
[메인] model → 도구 호출: write_file           ← 파일로 저장
[메인] tools ← write_file 결과 (38자)
```

이 출력이 [Step 01](../step-01-why-deep-agents/) 의 4대 기둥을 그대로 보여 줍니다. `write_todos`(계획) → `task`(격리) → `write_file`(오프로딩).

**서브에이전트 줄을 주목하세요.** 서브에이전트는 `search_docs` 결과로 **1,841자**를 받았는데, 메인에게 돌아온 `task` 결과는 **612자**입니다. 1,841자는 서브에이전트의 컨텍스트에서만 살다 사라졌습니다. 이게 격리(isolation)의 실물입니다.

### 두 가지 필수 인자

| 인자 | 안 주면 |
|---|---|
| `subgraphs: true` | **서브에이전트 내부가 안 보입니다.** `task` 호출과 결과만 보이고 그 안은 깜깜합니다 |
| `streamMode` | 기본값이 `"values"` 라 매번 **전체 상태**가 통째로 옵니다 (수십 KB) |

### `streamMode` 고르기

| 모드 | 주는 것 | 언제 |
|---|---|---|
| `"updates"` | 각 노드가 **바꾼 것만** | 흐름 추적, 디버깅 (가장 유용) |
| `"values"` | 매 스텝의 **전체 상태** | 최종 상태만 필요할 때 |
| `"messages"` | LLM **토큰 단위** | 사용자에게 실시간으로 글자 흘리기 |
| `"custom"` | 도구가 `writer()` 로 보낸 것 | 진행률 표시 |

배열로 여러 개를 동시에 줄 수도 있습니다.

> ⚠️ **함정 — `stream` 앞에도 `await` 가 필요하다**
>
> ```ts
> // ❌ 틀림
> for await (const chunk of agent.stream({ messages }, { streamMode: "updates" })) { }
>
> // ✅ 맞음
> for await (const chunk of await agent.stream({ messages }, { streamMode: "updates" })) { }
> ```
>
> `agent.stream()` 은 async iterable 을 **직접** 돌려주는 게 아니라 그것의 **Promise** 를 돌려줍니다. `for await` 가 Promise 도 어느 정도 다뤄 주긴 하지만, `deepagents` 의 스트림은 `await` 를 붙여야 정상 동작합니다. 공식 문서 예제도 전부 `await agent.stream(...)` 입니다.
>
> `subgraphs: true` 를 줬는데 `await` 를 빠뜨리면 `[namespace, chunk]` 구조분해가 깨져서 `namespace` 가 `undefined` 가 되고, `namespace.length` 에서 터집니다. 에러 메시지가 `stream` 을 안 가리켜서 찾기 어렵습니다.

> 💡 **실무 팁 — `subgraphs: true` 는 켜 두세요**
>
> 서브에이전트가 안 보이면 Deep Agent 디버깅은 사실상 불가능합니다. `task` 가 20초 걸렸는데 그 안에서 뭘 했는지 모르면 고칠 수가 없죠.
>
> namespace 로 출처를 구분합니다.
>
> ```ts
> namespace.length === 0                              // 메인 에이전트
> namespace.some((s) => s.startsWith("tools:"))       // 서브에이전트
> ```
>
> 더 편한 방법은 LangSmith 입니다. `LANGSMITH_TRACING=true` 만 켜면 전체 트리를 웹에서 볼 수 있습니다. [Step 11](../step-11-streaming-production/) 에서 다룹니다.

---

## 정리

| 주제 | 핵심 |
|---|---|
| 설치 | `npm install deepagents langchain @langchain/core` — peer 라서 셋 다 명시 |
| 검증 | `npm ls @langchain/core` 에 **`deduped`** 와 **한 벌**이 보여야 함 |
| 엔트리포인트 | `deepagents`(기본) / `/browser`(디스크 관련 9개 빠짐) / `/node`(기본과 동일) |
| `await` | `createDeepAgent` 는 예의, **`invoke`/`stream` 은 필수** |
| 옵션 | **17개, 전부 optional**. 처음엔 `model`/`tools`/`systemPrompt` 3개면 충분 |
| 내장 도구 | **8개가 항상** 실림. `execute` 는 샌드박스 백엔드에서만 |
| 커스텀 도구 | 내장을 **대체하지 않고 추가**. `description` 이 곧 프롬프트 |
| 모델 | `"provider:model"` / `initChatModel` / 클래스 직접. **생략하면 Anthropic** |
| 관찰 | `await agent.stream(input, { streamMode: "updates", subgraphs: true })` |

**핵심 함정 3가지**

1. **`@langchain/core` 가 두 벌이면 `instanceof` 가 조용히 false 가 된다.** 에러가 안 나서 몇 시간을 태웁니다. `npm ls @langchain/core` 로 `deduped` 를 확인하세요.
2. **내장 도구 8개는 끌 수 없다.** `disableTools` 옵션은 없습니다. 진짜로 빼려면 `createAgent` 로 내려가 미들웨어를 직접 골라야 합니다(Step 08).
3. **`stream` 앞에 `await` 를 빠뜨리면 `subgraphs: true` 의 구조분해가 깨진다.** 에러가 엉뚱한 줄을 가리킵니다. `invoke` 와 `stream` 앞의 `await` 는 항상 필수입니다.

**버전 특이사항**: `createDeepAgent` 는 문서와 달리 `deepagents@1.11.0` 에서 동기 함수입니다(`Promise` 반환 안 함). `model` 생략 시 기본값은 `claude-sonnet-4-5-20250929` 이며, 버전에 따라 바뀔 수 있으므로 프로덕션에서는 명시하세요.

---

## 연습문제

1. `npm ls @langchain/core` 를 실행해 출력을 확인하고, `deduped` 가 몇 번 나오는지 세세요. 이어서 `deepagents/package.json` 의 `peerDependencies` 를 코드로 읽어 찍으세요.
2. `deepagents` 와 `deepagents/browser` 의 export 를 각각 세고, **browser 에만 없는 것** 9개를 나열하세요. 그 9개의 **공통점**을 한 줄로 설명하세요.
3. `createDeepAgent({})` 처럼 옵션을 **하나도 안 주고** 에이전트를 만들 수 있나요? 만들어 보고, 스파이 미들웨어로 도구가 몇 개 실리는지 확인하세요.
4. 계산기 도구(`add`: 두 수를 더함)를 만들어 Deep Agent 에 붙이고, 도구가 **9개**가 되는지 확인하세요. 이어서 `description` 을 `"계산"` 이라는 두 글자로 바꿔 보고, `RUN_LIVE=1` 로 "3 더하기 5는?" 을 물었을 때 모델이 그 도구를 부르는지 관찰하세요.
5. 같은 에이전트를 (a) `"anthropic:claude-sonnet-4-6"` 문자열로, (b) `initChatModel(..., { temperature: 0 })` 로, (c) `new ChatAnthropic({ maxTokens: 4096 })` 로 세 번 만들고 셋 다 생성되는지 확인하세요.
6. (스트리밍) `RUN_LIVE=1` 로 `streamMode: "updates"` 와 `subgraphs: true` 를 주고 "/notes.md 에 오늘 할 일 3개를 써 줘" 를 실행해, 어떤 도구가 어떤 순서로 불리는지 기록하세요. 이어서 **`subgraphs` 를 빼고** 다시 돌려 무엇이 안 보이는지 비교하세요.
7. (옵션 지도) `stateSchema` 와 `contextSchema` 의 차이를 한 줄로 쓰고, 다음 셋을 어디에 둘지 고르세요: (a) 요청자의 `userId`, (b) 지금까지 작성한 보고서 초안, (c) 외부 API 키.
8. (심화) 본문 2-8 의 스트리밍 코드를 고쳐서, **서브에이전트가 받은 도구 결과의 총 글자 수**와 **메인에게 돌아온 `task` 결과의 글자 수**를 각각 합산해 찍으세요. 두 숫자의 비율이 곧 격리(isolation)가 아껴 준 컨텍스트입니다.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 03 — 계획 도구 (write_todos)](../step-03-planning-todos/)

4대 기둥의 첫 번째를 분해합니다. `write_todos` 가 만드는 `todos` 상태가 왜 대화 속 텍스트보다 강한지, 그리고 모델이 계획을 **안 세우거나 너무 많이 세우는** 문제를 어떻게 다루는지 봅니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(2-1 ~ 2-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `project/` 폴더에서 실행합니다.

```bash
cd docs/reference/deepagent/project
npx tsx ../step-02-quickstart/practice.ts            # API 키 없이 대부분 동작
RUN_LIVE=1 npx tsx ../step-02-quickstart/practice.ts # 실제 호출 (토큰 소모)
```

`[2-3]` 과 `[2-8]` 만 실제 모델을 부릅니다. 나머지 여섯 절은 스파이 미들웨어와 패키지 메타데이터만 보므로 **키 없이 돌아갑니다.**

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[2-1] ~ [2-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다.

- `[2-1]` 의 `coreResolvedFrom` 이 이 파일에서 가장 실용적인 코드입니다. `createRequire(require.resolve("deepagents/package.json"))` 로 **deepagents 입장에서** `@langchain/core` 를 resolve 하고, `langchain` 입장에서도 같은 걸 하고, 경로가 전부 같은지 봅니다. `new Set(...).size === 1` 이면 한 벌입니다. 이 검사를 CI 에 넣어 두면 `instanceof` 함정을 영구히 막을 수 있습니다.
- `[2-2]` 는 세 엔트리포인트를 **동적 import** 해서 export 를 diff 합니다. 브라우저에 없는 9개(`FilesystemBackend`, `LocalShellBackend`, `findProjectRoot` …)가 전부 디스크·프로세스를 건드리는 것들이라는 게 눈에 보입니다. `deepagents` 와 `deepagents/node` 의 차이가 **0개**로 나오는 것도 확인하세요.
- `[2-4]` 의 `OPTIONS` 배열은 본문 표와 같은 내용을 코드로 옮긴 것입니다. 17개 전부와 "어느 스텝에서 다루는지"가 들어 있어, 나중에 "그 옵션이 어디였더라" 할 때 이 블록만 실행해도 됩니다.
- `[2-5]` 의 `TOOL_ROLE` 맵은 도구 이름 옆에 역할을 붙여 줍니다. `execute` 가 목록에 **없다**는 걸 마지막 줄에서 명시적으로 짚습니다 — 문서에는 내장 도구로 나오지만 샌드박스 백엔드에서만 생기기 때문입니다.
- `[2-6]` 의 `searchDocs` 는 `description` 을 일부러 길게 썼습니다. "무엇을 하는가"(사내 기술 문서를 검색합니다)와 **"언제 쓰는가"**(프레임워크 사용법이나 API 를 물어볼 때)를 둘 다 적은 게 포인트입니다. 연습문제 4번에서 이걸 두 글자로 줄여 보면 차이를 체감할 수 있습니다.
- `[2-8]` 의 스트리밍 루프는 `await agent.stream(...)` 에서 **`await` 를 빼면 바로 깨집니다.** `[namespace, chunk]` 구조분해가 실패해 `namespace.length` 에서 터지죠. 한번 빼 보고 에러 메시지가 얼마나 엉뚱한 곳을 가리키는지 보세요 — 본문 함정의 실물입니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래가 비어 있습니다.

- 파일 상단에 `createSpy` 와 `observe` 가 **이미 준비되어 있습니다.** 문제 3, 4 에서 그대로 쓰세요.
- `[문제 4]` 는 두 부분입니다. 앞부분(도구가 9개가 되는지)은 키 없이 풀리고, 뒷부분(`description` 을 두 글자로 줄였을 때 모델이 부르는지)은 `RUN_LIVE=1` 이 필요합니다. **뒷부분이 이 스텝에서 가장 중요한 실험**이니 꼭 돌려 보세요.
- `[문제 6]` 과 `[문제 8]` 도 `RUN_LIVE=1` 이 필요합니다. 나머지는 키 없이 풀립니다.
- `[문제 7]` 은 코드 없이 주석으로만 답하는 문제입니다.
- 파일을 그대로 실행하면 문제 번호만 출력되고 결과는 안 나옵니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 2]` 의 9개는 `FilesystemBackend`, `LocalShellBackend`, `SUBAGENT_RESPONSE_FORMAT_CONFIG_KEY`, `createAgentMemoryMiddleware`, `createSettings`, `createSubAgent`, `findProjectRoot`, `listSkills`, `parseSkillMetadata` 입니다. 공통점은 **전부 Node 의 `fs`/`process` 에 의존**한다는 것. 반대로 `StateBackend` 와 `StoreBackend` 가 양쪽에 다 있는 이유도 같습니다 — 디스크를 안 쓰니까요.
- `[정답 3]` 의 답은 "**됩니다**". `createDeepAgent({})` 는 정상 동작하고 도구 8개가 그대로 실립니다. `model` 도 기본값(`claude-sonnet-4-5-20250929`)이 들어갑니다. 17개 옵션이 전부 optional 이라는 게 이런 뜻입니다.
- `[정답 4]` 가 이 파일의 하이라이트입니다. `description: "계산"` 으로 줄이면 모델이 `add` 도구를 **안 부르고 그냥 8이라고 답해 버립니다.** 에러도 경고도 없습니다. 도구는 멀쩡히 실려 있는데 모델이 "이게 지금 쓸 도구인지" 판단할 근거가 없어서 안 쓰는 겁니다. 비결정적이라 가끔 부를 때도 있지만, 그게 더 나쁩니다 — 테스트에선 통과하고 프로덕션에서 실패하니까요.
- `[정답 7]` 의 답은 (a) `contextSchema`, (b) `stateSchema`, (c) `contextSchema` 입니다. 기준은 **"실행이 끝나도 남아야 하는가"** 입니다. `userId` 와 API 키는 매 실행 주입되는 값이고, 보고서 초안은 다음 턴에도 이어져야 하는 상태입니다. (c) 를 `stateSchema` 라고 답하기 쉬운데, API 키를 state 에 두면 **checkpointer 에 그대로 저장**됩니다 — 보안 사고입니다.
- `[정답 8]` 의 비율이 이 스텝의 결론입니다. 서브에이전트가 소비한 글자 수 대비 메인에게 돌아온 글자 수가 대개 **5:1 ~ 20:1** 입니다. 그 차이만큼 메인의 컨텍스트가 절약된 것이고, 그게 [Step 01](../step-01-why-deep-agents/) 에서 말한 격리(isolation)의 실제 값어치입니다. (모델 응답이라 실행마다 다릅니다)

```ts file="./solution.ts"
```
