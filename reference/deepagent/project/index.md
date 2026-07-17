# 실습 환경 (project/)

> **이 페이지의 목적**
> - Step 01~12 의 `.ts` 파일이 **실제로 돌아가는** 환경을 만든다
> - 왜 이 패키지 조합인지, 왜 이 tsconfig 인지 이해한다
> - `@langchain/core` 중복 설치라는 **가장 흔한 사고**를 미리 막는다
>
> **예상 소요**: 15분

DeepAgents 코스의 모든 실습 파일은 이 `project/` 폴더를 실행 환경으로 씁니다. 스텝 폴더(`step-01-why-deep-agents/` 등)는 `project/` **바깥**에 있지만, 의존성과 타입은 전부 여기 것을 빌려 씁니다. 그래서 설치는 딱 한 번만 하면 됩니다.

MySQL 코스가 Docker 컨테이너 하나를 띄워 놓고 모든 스텝이 거기에 쿼리를 흘려 넣었던 것과 같은 구조입니다. 여기서는 컨테이너 대신 `node_modules` 가 그 역할을 합니다.

---

## 요구 사항

| 항목 | 버전 | 확인 |
|---|---|---|
| Node.js | **22 이상** | `node -v` |
| npm | 10 이상 | `npm -v` |
| Anthropic API 키 | — | [console.anthropic.com](https://console.anthropic.com/settings/keys) |

Node 22 를 요구하는 이유는 두 가지입니다. 하나는 이 코스의 실습 파일이 전부 **top-level await** 를 쓰기 때문이고(`await agent.invoke(...)` 를 함수로 감싸지 않고 파일 최상단에서 부릅니다), 다른 하나는 `deepagents` 가 의존하는 LangChain v1 계열이 Node 20 미만을 지원하지 않기 때문입니다.

---

## 설치

```bash
cd docs/reference/deepagent/project
npm install
cp .env.example .env
# .env 를 열어 ANTHROPIC_API_KEY 를 실제 키로 바꾸세요
```

설치가 끝나면 바로 검증합니다. 이 명령이 **이 코스에서 가장 중요한 한 줄**입니다.

```bash
npm run check:core
```

**출력** (이 구조는 결정적입니다 — 이대로 나와야 정상)

```
deepagent-course@1.0.0 /.../docs/reference/deepagent/project
├─┬ @langchain/anthropic@1.5.1
│ └── @langchain/core@1.2.3 deduped
├── @langchain/core@1.2.3
├─┬ @langchain/langgraph@1.4.8
│ ├── @langchain/core@1.2.3 deduped
│ └─┬ @langchain/langgraph-checkpoint@1.1.3
│   └── @langchain/core@1.2.3 deduped
├─┬ deepagents@1.11.0
│ ├── @langchain/core@1.2.3 deduped
│ └── langchain@1.5.3 deduped
└─┬ langchain@1.5.3
  └── @langchain/core@1.2.3 deduped
```

핵심은 **`deduped`** 라는 단어와, `@langchain/core` 가 **1.2.3 하나뿐**이라는 사실입니다. 이게 깨졌을 때 무슨 일이 벌어지는지는 아래 함정에서 다룹니다.

> ⚠️ **함정 (이 코스 전체를 통틀어 1위) — `@langchain/core` 가 두 벌 설치되면 `instanceof` 가 조용히 false 가 된다**
>
> `deepagents`, `langchain`, `@langchain/anthropic`, `@langchain/langgraph` 는 모두 `@langchain/core` 를 **peer dependency** 로 요구합니다. 즉 "내가 설치하는 게 아니라 네가 설치한 걸 같이 쓰겠다"는 선언입니다. 그런데 버전 범위가 어긋나면 npm 이 친절하게도 각자에게 **다른 사본을 하나씩 더** 깔아 줍니다:
>
> ```
> node_modules/@langchain/core            ← 1.2.3
> node_modules/deepagents/node_modules/@langchain/core   ← 1.1.0  ⚠️ 두 벌!
> ```
>
> 이러면 `AIMessage` 클래스가 **서로 다른 두 개의 클래스**가 됩니다. `deepagents` 가 만든 `AIMessage` 를 여러분이 import 한 `AIMessage` 로 `instanceof` 검사하면 **에러 없이 그냥 `false`** 가 나옵니다. 메시지 필터링이 조용히 전부 빗나가고, "왜 도구 호출을 못 잡지?" 하며 몇 시간을 태웁니다.
>
> **에러가 안 난다는 게 이 함정의 본질입니다.** 그래서 `npm run check:core` 로 `deduped` 를 눈으로 확인하는 습관이 필요합니다. 이미 꼬였다면:
>
> ```bash
> rm -rf node_modules package-lock.json && npm install
> ```
>
> 이 코스의 `src/lib/print.ts` 가 `instanceof` 대신 "그 필드가 있는지"만 보는 duck typing 으로 짜여 있는 것도 같은 이유입니다.

---

## 실행 방법

스텝 파일은 `project/` 안에서 상대경로로 부릅니다.

```bash
# project/ 안에서
npx tsx ../step-01-why-deep-agents/practice.ts
npx tsx ../step-02-quickstart/practice.ts

# 저장소 루트에서 부르고 싶다면
npx tsx docs/reference/deepagent/step-02-quickstart/practice.ts
```

`tsx` 는 TypeScript 를 **트랜스파일만** 하고 타입 검사는 건너뜁니다. 덕분에 빠르지만, 타입 오류가 있어도 그냥 돌아갑니다. 타입은 따로 검사하세요.

```bash
npm run typecheck    # tsc --noEmit — 스텝 폴더의 .ts 까지 전부 검사
```

---

## 파일별 해설

### package.json

의존성이 왜 이렇게 묶여 있는지가 핵심입니다.

- **`deepagents`** — 주인공. `createDeepAgent` 와 백엔드/미들웨어가 전부 여기 있습니다.
- **`langchain`** — `tool()`, `createAgent()`, `createMiddleware()` 가 여기 있습니다. `deepagents` 가 이걸 감싸는 구조라 **둘 다** 필요합니다(Step 01 의 1-3 참고).
- **`@langchain/core`** — 위 두 패키지의 peer dependency. **직접 명시해야** 버전이 한 벌로 고정됩니다. 이걸 빼면 npm 이 알아서 깔아 주긴 하는데, 그때 버전이 갈릴 수 있습니다.
- **`@langchain/anthropic`** — `model: "anthropic:..."` 문자열을 실제 모델로 바꿔 주는 provider 어댑터. 문자열만 쓰더라도 이 패키지가 설치돼 있어야 `initChatModel` 이 찾아냅니다.
- **`@langchain/langgraph`** — `MemorySaver`, `InMemoryStore` 등. Deep Agent 의 checkpointer/store 가 이걸 씁니다.
- **`zod`** — 도구 스키마와 `responseFormat` / `contextSchema` 를 정의합니다.

`"type": "module"` 과 `engines.node >= 22` 가 tsconfig 의 `NodeNext` 와 짝을 이룹니다.

```json file="./package.json"
```

### tsconfig.json

`moduleResolution: "NodeNext"` 가 이 파일의 핵심입니다. `deepagents` 는 `"."` / `"./browser"` / `"./node"` 세 개의 **서브패스 export** 를 가지는데(Step 02 의 2-2), 구버전 `"node"` 해석기는 이 exports 맵을 못 읽어서 `deepagents/node` 의 타입을 못 찾습니다. `NodeNext` 로 두면 tsc 가 실제 Node 와 같은 규칙으로 읽으므로, "브라우저 엔트리엔 없는 심볼을 썼다" 같은 실수를 타입 단계에서 잡아 줍니다.

`include` 에 `"../step-*/**/*.ts"` 가 있는 것도 눈여겨보세요. 스텝 폴더가 `project/` 밖에 있어도 `npm run typecheck` 한 번으로 전부 검사됩니다.

```json file="./tsconfig.json"
```

### .env.example

이 파일을 `.env` 로 복사해서 쓰는 이유는, **`.env.example` 은 커밋하고 `.env` 는 커밋하지 않기** 때문입니다. 예시 파일은 "어떤 키가 필요한지"를 팀에 알려 주는 문서고, 실제 값은 각자 로컬에만 둡니다.

`ANTHROPIC_API_KEY` 하나만 채우면 Step 01~12 대부분이 돌아갑니다. `createDeepAgent` 는 `model` 을 생략하면 Anthropic 모델(`claude-sonnet-4-5-20250929`)을 기본으로 쓰기 때문입니다.

```ini file="./.env.example"
```

### .gitignore

`.env` 차단이 첫 번째 목적입니다. 세 줄의 **순서**가 중요합니다 — `.env.*` 로 전부 막은 다음 `!.env.example` 로 하나만 되돌립니다. 순서를 뒤집으면 예시 파일까지 같이 막힙니다.

`workspace/` 와 `.deepagents/` 도 막아 뒀습니다. Step 05 에서 `FilesystemBackend` 를 쓰면 에이전트가 **실제 디스크에 파일을 씁니다.** 그 결과물이 통째로 커밋되는 사고를 미리 막는 것입니다.

```bash file="./.gitignore"
```

### src/lib/print.ts

Deep Agent 의 `invoke` 결과에는 `messages` 가 40개씩 들어 있는 게 예사입니다. 그대로 `console.log` 하면 터미널이 수백 줄로 덮여서 정작 봐야 할 흐름이 묻힙니다. 이 파일은 그걸 **한 줄에 하나씩** 요약해 줍니다.

- `printSection("[2-5] 기본 도구 관찰")` — 본문 소제목과 1:1 대응하는 구분선. `practice.ts` 를 통째로 돌렸을 때 "지금 출력이 몇 절 것인지" 찾게 해 줍니다.
- `printMessages(messages)` — 도구 호출은 `→ 도구 호출: write_todos`, 도구 결과는 `← write_todos: ...` 로 방향을 표시합니다. Deep Agent 의 "계획 → 실행 → 관찰" 루프가 눈에 보입니다.
- `printTodos(todos)` / `printFiles(files)` — `write_todos` 와 가상 파일시스템의 결과를 봅니다. Step 03, Step 04 에서 본격적으로 씁니다.
- **`instanceof` 를 쓰지 않습니다.** 위 함정에서 본 `@langchain/core` 중복 문제 때문입니다. 대신 `"tool_calls" 필드가 배열인가`만 봅니다 — 이 경우엔 duck typing 이 `instanceof` 보다 튼튼합니다.

```ts file="./src/lib/print.ts"
```

---

## 다음 단계

→ [Step 01 — Deep Agent란 무엇인가](../step-01-why-deep-agents/)
