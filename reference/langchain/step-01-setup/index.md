# Step 01 — 환경 구축과 첫 모델 호출

> **학습 목표**
> - LangChain 이 **무엇을 해결하고 무엇을 해결하지 않는지** 설명한다
> - v1 이 v0 에서 무엇을 버렸는지 알고, 낡은 예제 코드를 **알아보고 피한다**
> - Node 22 + TypeScript + tsx 실습 환경을 **직접 구축한다**
> - `langchain` / `@langchain/core` / 제공자 패키지 / `@langchain/langgraph` 의 **역할을 구분한다**
> - API 키를 발급하고 `.env` 로 **안전하게** 관리한다
> - `"provider:model"` 문자열과 모델 인스턴스 **두 방식으로** 모델을 호출한다
> - `AIMessage` 의 `content` / `usage_metadata` / `response_metadata` 를 **읽는다**
>
> **선행 스텝**: 없음 (이 코스의 시작입니다)
> **예상 소요**: 50분

이 스텝이 끝나면 여러분의 터미널에 모델이 답을 한 줄 뱉습니다. 그게 전부입니다. 에이전트도, 도구도, 메모리도 아직 없습니다.

그런데도 이 스텝에 50분을 씁니다. 이유는 두 가지입니다. 첫째, **앞으로 20개 스텝이 전부 여기서 만든 환경 위에서 돕니다.** 여기서 대충 넘어간 설정 하나가 Step 12쯤에서 원인 모를 에러로 돌아옵니다. 둘째, `model.invoke()` 가 돌려주는 그 객체 하나를 정확히 읽을 줄 아는 것이 이 코스 전체의 기초입니다. 에이전트란 결국 **저 객체를 보고 다음에 뭘 할지 정하는 루프**이기 때문입니다.

---

## 1-1. LangChain 이 무엇이고 무엇이 아닌가

### 그냥 SDK 를 직접 쓰면 안 됩니까

이 질문에 먼저 답해야 합니다. Anthropic 도 OpenAI 도 훌륭한 공식 SDK 를 냅니다. 모델한테 뭘 물어보는 게 전부라면 **SDK 를 직접 쓰는 게 맞습니다.** LangChain 은 그 경우 순수한 오버헤드입니다.

문제는 코드가 자라기 시작할 때 생깁니다. 같은 일을 하는 코드를 두 SDK 로 나란히 써 보면 이렇게 됩니다.

```ts
// Anthropic SDK 직접
const res = await anthropic.messages.create({
  model: "claude-sonnet-4-6",
  max_tokens: 1024,
  messages: [{ role: "user", content: "안녕" }],
});
const text = res.content[0].type === "text" ? res.content[0].text : "";
const inputTokens = res.usage.input_tokens;

// OpenAI SDK 직접
const res = await openai.chat.completions.create({
  model: "gpt-5.5",
  messages: [{ role: "user", content: "안녕" }],
});
const text = res.choices[0].message.content;
const inputTokens = res.usage.prompt_tokens;
```

응답을 파싱하는 방법이 다르고(`content[0].text` vs `choices[0].message.content`), 토큰 필드 이름이 다르고(`input_tokens` vs `prompt_tokens`), `max_tokens` 는 한쪽만 필수입니다. 도구 호출까지 가면 스키마 형식과 결과를 돌려주는 방법이 또 갈라집니다.

이 차이가 애플리케이션 코드 **여기저기에 스며듭니다.** 그러다 "이번 분기엔 다른 모델이 더 싸고 좋다더라"는 말이 나오면, 모델 이름 한 줄이 아니라 응답을 만지는 모든 곳을 다시 씁니다.

LangChain 이 파는 것은 바로 이 지점입니다.

```ts
// LangChain — 제공자를 바꿔도 아래 코드는 안 바뀝니다
const model = await initChatModel("anthropic:claude-sonnet-4-6");
//                                 ^^^^^^^^^ "openai:gpt-5.5" 로 바꾸면 끝
const res = await model.invoke("안녕");
const text = res.text;
const inputTokens = res.usage_metadata?.input_tokens;
```

공식 문서는 이걸 두 가지 초점으로 정리합니다.

1. **모델 표준화** — 제공자마다 다른 입출력을 하나로 맞춰 벤더 종속을 막고, 더 좋은 모델이 나오면 갈아탈 수 있게 한다.
2. **텍스트 너머의 오케스트레이션** — 모델이 글만 뽑는 게 아니라 "다른 데이터와 상호작용하는 복잡한 흐름을 지휘하게" 한다.

두 번째가 사실 더 중요합니다. 도구를 부르고, 결과를 다시 모델에 먹이고, 언제 멈출지 판단하는 **루프**가 필요한 순간부터 SDK 직접 호출은 급격히 지저분해집니다. LangChain 의 표어는 이렇습니다.

> **Agent = Model + Harness** (에이전트 = 모델 + 하네스)

모델은 제공자가 만듭니다. LangChain 이 파는 건 **하네스**입니다. 모델 주위를 감싸서 도구를 쥐여주고, 루프를 돌리고, 중간에 사람이 끼어들 수 있게 하는 그 껍데기요.

### 무엇이 아닌가

오해를 먼저 걷어내는 편이 빠릅니다.

| LangChain 은 ... | 아닙니다 |
|---|---|
| 모델을 호스팅하지 않습니다 | 여전히 Anthropic/OpenAI 에 요청이 나가고 **여러분 카드로 과금됩니다** |
| 프롬프트를 자동으로 잘 만들어주지 않습니다 | 프롬프트가 나쁘면 결과도 나쁩니다. 프레임워크는 그걸 못 고칩니다 |
| 모델을 똑똑하게 만들지 않습니다 | 모델 성능은 모델이 정합니다 |
| 마법이 아닙니다 | 안에서 하는 일은 결국 HTTP 요청과 `while` 루프입니다 |

마지막 줄이 이 코스의 방침입니다. Step 07 에서는 **도구 호출 루프를 손으로 직접 구현**해 봅니다. `createAgent` 가 뭘 대신 해주는지 알고 쓰는 것과 모르고 쓰는 것은 디버깅할 때 하늘과 땅 차이입니다.

### v0 → v1 에서 무엇이 바뀌었나

**이 절을 건너뛰지 마세요.** 인터넷에 널린 LangChain 예제의 대부분은 v0 이고, **v1 에서 동작하지 않습니다.** 블로그 글을 복사했는데 `Cannot find module` 이 뜨는 이유가 여기 있습니다.

v1.0.0 은 2025년 10월에 나왔고, 두 가지가 크게 바뀌었습니다.

**1. 체인이 사라지고 에이전트 하나로 통합됐습니다.**

v0 에는 `LLMChain`, `ConversationChain`, `RetrievalQAChain`, `initializeAgentExecutorWithOptions` 같은 것들이 수십 개 있었습니다. v1 은 이걸 전부 걷어내고 **LangGraph 위에 올린 `createAgent` 하나**로 대체했습니다. 옛 코드가 꼭 필요하면 `@langchain/classic` 을 따로 설치해야 합니다.

**2. 메시지 포맷이 표준화됐습니다.**

모델 출력이 더 이상 단순한 문자열이 아닙니다. 추론(reasoning) 블록, 인용(citation), 서버 사이드 도구 호출이 섞여 옵니다. v1 은 이걸 제공자와 무관하게 같은 모양으로 표준화했습니다 — 1-6 에서 직접 보게 될 `contentBlocks` 가 그 결과물입니다.

낡은 예제를 알아보는 법:

| 이게 보이면 | v0 입니다 |
|---|---|
| `import { LLMChain } from "langchain/chains"` | 없어졌습니다 |
| `import { ChatOpenAI } from "langchain/chat_models/openai"` | 이제 `@langchain/openai` 입니다 |
| `new LLMChain({ llm, prompt })` | `createAgent` 로 대체 |
| `initializeAgentExecutorWithOptions(...)` | `createAgent` 로 대체 |
| `chain.call({ ... })` | `.invoke(...)` 입니다 |

> 💡 **실무 팁**: LangChain 문서를 검색할 때는 **반드시 URL 에 `/oss/javascript/` 가 들어있는지** 확인하세요. 구글 상위에는 아직도 v0 문서와 Python 예제가 잔뜩 올라옵니다. Python 예제를 TypeScript 로 옮기는 것도 위험합니다 — 이름이 미묘하게 다릅니다(`create_agent` vs `createAgent`). 이 코스의 모든 코드는 v1 JavaScript 공식 문서 기준입니다.

---

## 1-2. 실습 환경 구축

### 필요한 것

- **Node.js 22 이상** (공식 요구사항입니다. 20 에서는 안 됩니다)
- 편집기 (TypeScript 지원되는 것 아무거나)
- Anthropic API 키 (1-4 에서 발급합니다)

먼저 버전부터 확인합니다.

```bash
node -v
```

**출력**
```
v22.22.0
```

`v22` 미만이면 여기서 멈추고 업그레이드하세요. [nvm](https://github.com/nvm-sh/nvm) 을 쓴다면 `nvm install 22 && nvm use 22` 입니다.

### 폴더 구조

이 코스의 실습 환경은 이렇게 생겼습니다.

```
docs/reference/langchain/
├── package.json          ← 워크스페이스 루트
├── node_modules/         ← 설치 결과가 여기 생깁니다
├── project/
│   ├── package.json      ← 실제 의존성 선언
│   ├── tsconfig.json
│   ├── .env.example
│   ├── .env              ← 직접 만듭니다
│   ├── .gitignore
│   └── src/lib/print.ts  ← 공용 출력 헬퍼
├── step-01-setup/        ← 지금 여기
│   ├── practice.ts
│   ├── exercise.ts
│   └── solution.ts
└── step-02-chat-models/
```

`package.json` 이 두 개인 게 이상해 보일 겁니다. 이유가 있습니다.

실습 파일(`step-01-setup/practice.ts`)이 `project/` **바깥**에 있습니다. 그런데 Node.js 는 `import { createAgent } from "langchain"` 같은 bare import 를 만나면 **그 파일이 있는 폴더에서부터 위로 올라가며** `node_modules` 를 찾습니다. `node_modules` 가 `project/` 안에만 있으면 `step-01-setup/` 에서는 영원히 못 찾습니다 — `project/` 는 부모가 아니라 **형제**니까요.

그래서 워크스페이스 루트를 하나 두어 `node_modules` 를 `langchain/` 레벨로 끌어올렸습니다. 이제 어느 스텝 폴더에서든 한 칸만 올라가면 만납니다.

### 설치

```bash
cd docs/reference/langchain
npm install
```

`node_modules` 가 `project/` 가 **아니라** `docs/reference/langchain/` 에 생깁니다. 정상입니다.

설치되는 것은 `project/package.json` 이 선언한 목록입니다.

```json
{
  "type": "module",
  "dependencies": {
    "langchain": "^1.5.3",
    "@langchain/core": "^1.2.3",
    "@langchain/anthropic": "^1.5.1",
    "@langchain/openai": "^1.5.5",
    "@langchain/langgraph": "^1.4.8",
    "zod": "^4.1.5",
    "dotenv": "^17.2.1"
  },
  "devDependencies": {
    "tsx": "^4.20.3",
    "typescript": "^5.9.2",
    "@types/node": "^22.15.3"
  }
}
```

`"type": "module"` 이 핵심입니다. 이게 있어야 `.ts` 파일이 ESM 으로 취급되고, top-level `await` 를 쓸 수 있습니다.

### TypeScript 설정

`tsconfig.json` 에서 진짜 중요한 건 두 줄입니다.

```json
{
  "compilerOptions": {
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "target": "ES2022",
    "strict": true,
    "noEmit": true
  }
}
```

`NodeNext` 는 Node.js 의 실제 ESM 해석 규칙을 그대로 따라갑니다. 덕분에 "타입 검사는 통과했는데 실행하면 모듈을 못 찾는" 사고가 안 납니다. 대신 규칙 하나를 받아들여야 합니다 — **로컬 파일 import 시 확장자를 `.js` 로 씁니다.**

```ts
import { printSection } from "../project/src/lib/print.js";
//                                                   ^^^ .ts 가 아니라 .js
```

`.ts` 파일을 가리키면서 `.js` 라고 쓰는 게 이상하지만, TypeScript 가 "컴파일된 뒤의 경로"를 기준으로 해석하기 때문입니다. 확장자를 빼면 `tsc` 가 에러를 냅니다. `node_modules` 의 패키지(`langchain` 등)에는 해당 없습니다 — 그건 bare import 니까요.

### 실행

**항상 `project/` 에서 실행합니다.**

```bash
cd docs/reference/langchain/project
npx tsx ../step-01-setup/practice.ts
```

`tsx` 는 TypeScript 를 컴파일 단계 없이 바로 실행해 줍니다. `tsc` 로 빌드하고 `node dist/...` 를 돌리는 왕복을 없애줘서 학습용으로 최적입니다.

> ⚠️ **함정 (tsx 는 타입을 검사하지 않는다)**: `tsx` 는 타입을 **지우고** 실행할 뿐, 검사하지 않습니다. 타입이 틀려도 그냥 돌아갑니다. `usage_metadata` 가 `undefined` 인데 `.input_tokens` 를 읽는 코드는 tsx 에서 "잘 돌다가" 어느 날 `Cannot read properties of undefined` 로 죽습니다. **타입 검사는 반드시 따로 돌리세요.**
> ```bash
> cd docs/reference/langchain/project
> npm run typecheck        # tsc --noEmit
> ```

> ⚠️ **함정 (dotenv 는 cwd 기준이다)**: `import "dotenv/config"` 는 `.env` 를 **`process.cwd()` 기준**으로 찾습니다. 즉 "파일이 어디 있느냐"가 아니라 **"어느 폴더에서 명령을 쳤느냐"**가 기준입니다. 저장소 루트에서 `npx tsx docs/reference/langchain/step-01-setup/practice.ts` 를 치면 dotenv 는 저장소 루트의 `.env` 를 찾다 실패하고, "키가 없다"며 죽습니다 — **`.env` 는 멀쩡히 `project/` 에 있는데도요.** 이 코스가 굳이 `cd project` 를 요구하는 이유입니다.

---

## 1-3. 패키지 지형도

`@langchain/` 으로 시작하는 패키지가 너무 많아서 처음엔 뭘 깔아야 할지 막막합니다. 역할로 나누면 간단합니다.

| 패키지 | 담당 | 여기서 가져오는 것 | 언제 쓰나 |
|---|---|---|---|
| **`langchain`** | 조립 도구. 사용자가 직접 만지는 고수준 API | `createAgent`, `initChatModel`, `tool`, 내장 미들웨어 | **항상** |
| **`@langchain/core`** | 공용 언어. 모든 패키지가 공유하는 기본 타입 | `AIMessage`, `HumanMessage`, `SystemMessage`, `ToolMessage` | **항상** (대개 간접적으로) |
| **`@langchain/anthropic`** | Anthropic 어댑터 | `ChatAnthropic` | Claude 를 쓸 때 |
| **`@langchain/openai`** | OpenAI 어댑터 | `ChatOpenAI`, `OpenAIEmbeddings` | GPT 를 쓸 때 |
| **`@langchain/langgraph`** | 실행 엔진. 루프·상태·영속성 | `MemorySaver`, 그래프 API | Step 10 부터 직접 |

관계를 한 줄로 요약하면 이렇습니다.

```
당신의 코드
    ↓  import { createAgent } from "langchain"
langchain            ← 조립 도구
    ↓  내부적으로 사용
@langchain/langgraph ← 루프를 실제로 돌리는 엔진
    ↓  메시지를 주고받음
@langchain/core      ← 모두가 공유하는 타입 (AIMessage 등)
    ↑  구현
@langchain/anthropic ← 실제 HTTP 요청을 보냄
    ↓
   Anthropic API
```

**`@langchain/core` 를 직접 설치하는 이유**가 여기서 나옵니다. 이건 다른 패키지들의 **peer dependency** 입니다. `langchain` 도, `@langchain/anthropic` 도, `@langchain/langgraph` 도 전부 core 를 필요로 합니다. 그리고 **셋이 반드시 같은 core 를 봐야 합니다.**

> ⚠️ **함정 (@langchain/core 가 두 벌이면 `instanceof` 가 조용히 깨진다)**: 이 코스에서 가장 진단하기 어려운 함정입니다.
>
> `@langchain/*` 패키지들의 버전이 어긋나면 npm 이 core 를 **두 벌** 설치합니다.
> ```
> node_modules/@langchain/core                          ← 1.2.3
> node_modules/@langchain/anthropic/node_modules/@langchain/core  ← 1.0.5
> ```
> 이러면 `AIMessage` 클래스가 **두 개** 존재하게 됩니다. 이름도 같고 모양도 같지만 JavaScript 에게는 완전히 다른 클래스입니다. 그래서 `@langchain/anthropic` 이 만든 메시지를 여러분이 import 한 `AIMessage` 로 검사하면:
> ```ts
> response instanceof AIMessage   // false — 에러도 없이 그냥 false
> ```
> **에러가 안 납니다.** 타입 검사도 통과합니다. 그냥 조건문이 조용히 빗나가고, 여러분은 "왜 이 분기를 안 타지?"를 몇 시간 헤맵니다.
>
> **진단**: `npm ls @langchain/core` — `deduped` 만 보이면 안전합니다. 서로 다른 버전이 여러 줄 나오면 위험합니다.
> ```
> ├─┬ @langchain/anthropic@1.5.1
> │ └── @langchain/core@1.2.3 deduped     ← 이렇게 나와야 정상
> ├─┬ @langchain/core@1.2.3
> └─┬ @langchain/langgraph@1.4.8
>   └── @langchain/core@1.2.3 deduped
> ```
> **해결**: `@langchain/*` 버전을 서로 맞추고 `rm -rf node_modules package-lock.json && npm install`. 그래도 안 되면 `package.json` 의 `overrides` 로 core 버전을 하나로 못박습니다.
>
> **예방**: 애초에 `instanceof` 에 의존하지 않는 코드를 쓰세요. `message.getType() === "ai"` 로 판별하면 core 가 몇 벌이든 항상 동작합니다. `project/src/lib/print.ts` 가 그렇게 짜여 있습니다.

> 💡 **실무 팁**: 모노레포(pnpm workspace, Turborepo 등)에서 이 문제가 특히 자주 터집니다. 패키지 A 는 core 1.2 를, 패키지 B 는 core 1.0 을 물고 있으면 위 상황이 그대로 재현됩니다. LangChain 을 쓰는 모노레포라면 `@langchain/core` 버전을 루트에서 **단일 버전으로 고정**하는 걸 처음부터 정책으로 삼으세요.

---

## 1-4. API 키 발급과 안전한 보관

### 발급

1. [console.anthropic.com](https://console.anthropic.com) 에 가입합니다.
2. **Settings → API Keys → Create Key**
3. 생성된 키(`sk-ant-api03-...`)를 복사합니다. **이 화면을 벗어나면 다시 못 봅니다.**
4. 결제 수단을 등록합니다. 무료 크레딧이 있을 수 있지만 없으면 첫 호출부터 실패합니다.

이 코스 전체를 도는 데 드는 비용은 몇 달러 수준입니다. 다만 Step 09(스트리밍)나 Step 18(멀티 에이전트)처럼 호출이 많은 스텝은 조금 더 나옵니다.

OpenAI 를 쓸 거라면 [platform.openai.com/api-keys](https://platform.openai.com/api-keys) 에서 같은 절차를 밟고 `OPENAI_API_KEY` 로 넣으면 됩니다.

### 보관

`.env.example` 을 복사해서 `.env` 를 만듭니다.

```bash
cd docs/reference/langchain/project
cp .env.example .env
```

`.env` 를 열어 키를 채웁니다.

```bash
ANTHROPIC_API_KEY=sk-ant-api03-실제키를여기에
```

따옴표는 필요 없습니다. 앞뒤에 공백이 붙지 않게 하세요.

이제 코드에서 읽습니다. **파일 맨 위 한 줄**이 전부입니다.

```ts
import "dotenv/config";
```

> ⚠️ **함정 (키가 `.env` 에 있어도 `import "dotenv/config"` 를 안 하면 못 읽는다)**: 이게 이 스텝에서 여러분이 가장 높은 확률로 만날 함정입니다.
>
> `.env` 는 **그냥 텍스트 파일**입니다. Node.js 는 이 파일의 존재를 모릅니다. `import "dotenv/config"` 가 실행되는 순간 dotenv 가 파일을 읽어 `process.env` 에 채워 넣는 것이지, 파일이 있다고 저절로 읽히지 않습니다.
>
> 더 고약한 건 **어떤 사람에게는 이게 잘 동작한다**는 점입니다. `~/.zshrc` 에 `export ANTHROPIC_API_KEY=...` 를 해 둔 사람은 dotenv 없이도 잘 됩니다. 그래서 "내 컴퓨터에선 되는데" 가 만들어집니다.
>
> 반대 방향도 알아 두세요. **dotenv 는 이미 존재하는 환경변수를 덮어쓰지 않습니다.** 셸에 낡은 키가 export 되어 있으면 `.env` 를 아무리 고쳐도 낡은 키가 계속 이깁니다. `.env` 를 고쳤는데 여전히 401 이 뜬다면 `echo $ANTHROPIC_API_KEY` 로 셸부터 의심하세요.

### 절대 커밋하지 마세요

`.gitignore` 에 이렇게 들어 있습니다.

```bash
.env
.env.*
!.env.example
```

**순서가 중요합니다.** `.env.*` 로 `.env.local`, `.env.production` 까지 전부 막은 다음 `!.env.example` 로 예시 파일만 다시 꺼냅니다. 나중 줄이 앞 줄을 덮어쓰므로 순서를 바꾸면 `.env.example` 이 커밋되지 않습니다.

`.env.example` 은 **커밋합니다.** 값이 아니라 "어떤 키가 필요한지"를 알려주는 문서이기 때문입니다.

커밋 전에 확인하는 습관을 들이세요.

```bash
git status --short          # .env 가 목록에 있으면 안 됩니다
git check-ignore -v .env    # 무시되고 있는지 확인
```

> 💡 **실무 팁 — 키를 실수로 커밋했다면**: 가장 먼저 할 일은 커밋을 지우는 게 아니라 **콘솔에서 그 키를 폐기(revoke)하는 것**입니다. 순서를 헷갈리지 마세요.
>
> `git commit --amend` 나 `git rebase` 로 히스토리를 고쳐도 **이미 늦었다고 가정해야 합니다.** 공개 저장소에 푸시됐다면 봇이 몇 초 만에 긁어갑니다. GitHub 의 push protection 이 막아주기도 하지만 믿지 마세요. 히스토리 정리는 그다음 문제입니다.
>
> 실무에서는 `.env` 를 아예 쓰지 않고 AWS Secrets Manager, Vault, Doppler 같은 비밀 관리 서비스에서 런타임에 주입합니다. 그래도 로컬 개발은 여전히 `.env` 입니다 — 그래서 `.gitignore` 가 첫 방어선입니다.

키를 확인할 때는 항상 마스킹하세요. 통째로 출력하면 그게 터미널 스크롤백에, CI 로그에, 스크린샷에 남습니다.

```ts
const apiKey = process.env["ANTHROPIC_API_KEY"];
console.log(`${apiKey.slice(0, 14)}...${apiKey.slice(-4)}`);
```

**출력**
```
sk-ant-api03-a...9f2c
```

---

## 1-5. 첫 모델 호출

드디어 모델을 부릅니다. 방법은 두 가지이고, **결과는 같습니다.**

### 방법 A — `"provider:model"` 문자열

```ts
import "dotenv/config";
import { initChatModel } from "langchain";

const model = await initChatModel("anthropic:claude-sonnet-4-6");
const response = await model.invoke("LangChain을 한 문장으로 설명해줘.");

console.log(response.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
LangChain은 여러 LLM 제공자를 하나의 인터페이스로 다루면서 도구 호출과
에이전트 루프를 조립할 수 있게 해주는 TypeScript/Python 프레임워크입니다.
```

`initChatModel` 은 접두사(`anthropic:`)를 보고 알맞은 제공자 패키지를 **동적으로 불러옵니다.** 그래서 `await` 가 필요합니다.

문서에서 확인된 접두사는 다음과 같습니다.

| 접두사 | 예시 | 필요한 패키지 |
|---|---|---|
| `anthropic` | `anthropic:claude-sonnet-4-6` | `@langchain/anthropic` |
| `openai` | `openai:gpt-5.5` | `@langchain/openai` |
| `azure_openai` | `azure_openai:...` | `@langchain/openai` |
| `google-genai` | `google-genai:gemini-3.5-flash` | `@langchain/google-genai` |
| `bedrock` | `bedrock:...` | `@langchain/aws` |

접두사는 **패키지를 설치했다고 자동으로 생기지 않습니다.** 반대입니다 — 접두사에 맞는 패키지가 설치돼 있어야 합니다.

### 방법 B — 모델 인스턴스

```ts
import "dotenv/config";
import { ChatAnthropic } from "@langchain/anthropic";

const model = new ChatAnthropic({
  model: "claude-sonnet-4-6",
  temperature: 0,
  maxTokens: 1024,
});
const response = await model.invoke("LangChain을 한 문장으로 설명해줘.");

console.log(response.text);
```

`apiKey` 를 안 넘겼습니다. 안 주면 `ANTHROPIC_API_KEY` 환경변수를 자동으로 읽습니다. 명시하려면 `new ChatAnthropic({ model: "...", apiKey: "..." })` 처럼 줄 수 있지만, 키를 코드에 하드코딩하지 않는 편이 낫습니다.

### 어느 쪽을 쓰나

| | 문자열 (`initChatModel`) | 인스턴스 (`new ChatAnthropic`) |
|---|---|---|
| `await` 필요 | **예** (동적 import) | 아니오 |
| 제공자 갈아끼우기 | 문자열 한 줄 | import 문까지 교체 |
| 설정을 환경변수로 빼기 | 쉽다 (`process.env.MODEL`) | 어렵다 |
| 제공자 고유 옵션 | 타입 지원 약함 | **전부 타입 지원** |
| 오타 방어 | 없음 (그냥 `string`) | 클래스 이름이라 컴파일 타임에 걸림 |

**실무 감각**: 제공자를 바꿀 여지가 있거나 설정을 환경변수로 빼고 싶으면 **문자열**. 특정 제공자에만 있는 기능(Anthropic 의 프롬프트 캐싱 등)을 깊게 쓸 거면 **인스턴스**. 섞어 써도 됩니다 — `createAgent` 의 `model` 은 둘 다 받습니다.

> ⚠️ **함정 (provider 접두사 오타는 런타임에야 터진다)**: `model` 파라미터의 타입은 그냥 `string` 입니다. 그래서 이 코드는 **컴파일이 멀쩡히 통과합니다.**
> ```ts
> const model = await initChatModel("anthropi:claude-sonnet-4-6");
> //                                 ^^^^^^^^ 오타 — tsc 는 아무 말 안 함
> ```
> 에러는 코드를 짤 때가 아니라, 실행해서 **그 줄에 도달했을 때** 납니다.
> ```
> Error: Unable to infer model provider for { model: anthropi:claude-sonnet-4-6 },
> please specify modelProvider directly.
> ```
> 모델 이름이 조건문 안이나 잘 안 타는 분기에 있으면 **배포 후에** 터집니다. 방어법은 모델 문자열을 상수로 모으고 타입으로 좁히는 것입니다.
> ```ts
> const MODELS = {
>   sonnet: "anthropic:claude-sonnet-4-6",
>   gpt: "openai:gpt-5.5",
> } as const;
> type ModelId = (typeof MODELS)[keyof typeof MODELS];
>
> const MODEL: ModelId = "anthropi:claude-sonnet-4-6";  // ← 이제 컴파일 에러
> ```
> 연습문제 2번에서 직접 해 봅니다.

> ⚠️ **함정 (`initChatModel` 의 `await` 를 빠뜨리면)**: `initChatModel` 은 `Promise` 를 돌려줍니다. `await` 를 빠뜨리면 `model` 이 Promise 가 되고, 에러 메시지는 원인과 한참 동떨어진 곳에서 나옵니다.
> ```
> TypeError: model.invoke is not a function
> ```
> "invoke 가 없다니?" 하며 LangChain 문서를 뒤지게 되지만, 범인은 두 줄 위의 `await` 입니다. `new ChatAnthropic()` 은 동기라서 이 함정이 없습니다.

---

## 1-6. 응답 객체 해부

`invoke()` 가 돌려주는 건 **문자열이 아닙니다.** `AIMessage` 객체입니다.

```ts
const response = await model.invoke("안녕");

console.log(typeof response);              // "object"  ← 문자열 아님!
console.log(response.constructor.name);    // "AIMessage"
```

문자열인 줄 알고 `response.length` 를 세거나 `response.toUpperCase()` 를 부르면 엉뚱한 결과가 나옵니다. 이 객체에서 알아야 할 필드는 다섯 개입니다.

### `.text` — 텍스트만 안전하게

```ts
console.log(response.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
안녕하세요! 무엇을 도와드릴까요?
```

**대부분의 경우 이것만 쓰면 됩니다.** 응답에 무엇이 섞여 있든 텍스트 블록만 이어붙여 `string` 을 돌려줍니다.

### `.content` — 원본 페이로드

```ts
console.log(Array.isArray(response.content) ? "배열" : "문자열");
```

타입이 **`string | ContentBlock[]`** 입니다. 제공자와 응답 종류에 따라 문자열일 수도, 블록 배열일 수도 있습니다.

> ⚠️ **함정 (`.content` 가 문자열이라고 가정하지 마라)**: 이 코드는 **오늘은** 동작할 수 있습니다.
> ```ts
> const text = response.content.toUpperCase();      // 타입 에러 (strict 라면)
> const text = (response.content as string).trim(); // 캐스팅으로 눌렀다면 더 위험
> ```
> 그러다 모델을 바꾸거나, 추론(reasoning) 기능을 켜거나, 인용이 붙는 순간 `content` 가 배열이 되고 `.toUpperCase is not a function` 으로 죽습니다. **`as string` 캐스팅으로 타입 에러를 눌러버리는 게 최악입니다** — 컴파일러가 정확히 이걸 경고하려던 것이었으니까요. `.text` 를 쓰세요.

### `.contentBlocks` — 표준화된 블록

```ts
console.log(JSON.stringify(response.contentBlocks, null, 2));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```json
[
  {
    "type": "text",
    "text": "안녕하세요! 무엇을 도와드릴까요?"
  }
]
```

`.content` 가 제공자 원본에 가깝다면, `.contentBlocks` 는 **제공자와 무관하게 표준화된** 모양입니다. 1-1 에서 말한 "v1 의 메시지 표준화"가 바로 이겁니다. 추론 블록이나 인용이 섞여 와도 같은 방식으로 다룰 수 있습니다. 자세한 건 [Step 03 — 메시지와 콘텐츠 블록](../step-03-messages/) 에서 다룹니다.

여기서 이름을 눈여겨보세요. **`contentBlocks` 는 camelCase 인데 `usage_metadata` 와 `response_metadata` 는 snake_case 입니다.** 헷갈리기 딱 좋고, 실제로 자주 틀립니다. Python 판과 필드명을 맞추면서 생긴 흔적입니다.

### `.usage_metadata` — 토큰 사용량 (= 돈)

```ts
const usage = response.usage_metadata;
if (usage !== undefined) {
  console.log(usage.input_tokens, usage.output_tokens, usage.total_tokens);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
14 32 46
```

필드 이름은 제공자와 무관하게 **결정적**입니다.

| 필드 | 뜻 |
|---|---|
| `input_tokens` | 보낸 토큰 수 |
| `output_tokens` | 받은 토큰 수 |
| `total_tokens` | 합계 |
| `input_token_details` | 입력 세부 (예: `cache_read`) |
| `output_token_details` | 출력 세부 (예: `reasoning`) |

**`usage_metadata` 는 optional 입니다.** `strict` 모드에서 그냥 읽으면 타입 에러가 나는데, 이건 tsc 의 잔소리가 아니라 실제로 자주 없는 값입니다 — 스트리밍 청크(Step 09)나 일부 제공자에서는 안 옵니다. 반드시 확인하고 읽으세요.

> 💡 **실무 팁 — 비용은 여기서 나옵니다**: 프로덕션에서 가장 먼저 터지는 사고는 "모델이 이상한 소리를 한다"가 아니라 **"이번 달 청구서가 예상의 40배"** 입니다. 그리고 그 원인은 대개 에이전트가 도구 호출 루프를 돌면서 매 턴마다 **대화 전체를 다시 보내기** 때문입니다. 10턴짜리 대화의 입력 토큰은 10턴째에 첫 턴의 10배가 됩니다.
>
> `input_tokens` 와 `output_tokens` 는 단가가 보통 **5배쯤** 차이납니다. `total_tokens` 에 출력 단가를 곱하는 실수를 하지 마세요 — 비용을 크게 부풀려 잡게 됩니다. 반드시 따로 곱합니다.
>
> ```ts
> const cost =
>   (usage.input_tokens / 1_000_000) * 3.0 +      // 입력 단가
>   (usage.output_tokens / 1_000_000) * 15.0;     // 출력 단가
> ```
>
> 처음부터 모든 호출의 `usage_metadata` 를 로그에 남기세요. Step 19 에서 LangSmith 로 이걸 자동화합니다.

### `.response_metadata` — 제공자 원본

```ts
console.log(JSON.stringify(response.response_metadata, null, 2));
```

제공자가 준 응답 메타데이터가 거의 그대로 들어옵니다. **모양이 제공자마다 다릅니다.** Anthropic 이면 `model`, `stop_reason` 같은 것들이, OpenAI 면 `logprobs`, `finish_reason` 같은 것들이 옵니다.

표준화된 필드가 아니므로 **여기에 의존하는 코드는 제공자를 바꾸는 순간 깨집니다.** 디버깅할 때 들여다보는 용도로 쓰고, 애플리케이션 로직은 `usage_metadata` 나 `contentBlocks` 같은 표준 필드 위에 세우세요.

### `.id`

```ts
console.log(response.id);
```

**출력 예시** (호출마다 다릅니다)
```
msg_01AbCdEfGhIjKlMnOpQrStUv
```

제공자가 부여한 응답 ID 입니다. 로그와 제공자 콘솔의 기록을 대조할 때 씁니다. 지원 문의를 넣을 때 이 값이 있으면 훨씬 빠릅니다.

---

## 1-7. 첫 에이전트 맛보기

모델은 "물으면 답한다"가 전부입니다. **에이전트는 여기에 루프와 도구가 붙습니다.**

```ts
import { createAgent } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],   // 도구는 Step 06 에서 만듭니다
  systemPrompt: "너는 간결하게 답하는 한국어 조수다. 두 문장을 넘기지 마라.",
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "에이전트와 그냥 모델 호출의 차이가 뭐야?" }],
});

console.log(result.messages.length);
```

**출력**
```
2
```

여기서 **입출력 모양이 모델과 다르다**는 점이 핵심입니다.

| | 모델 | 에이전트 |
|---|---|---|
| 입력 | 문자열 또는 메시지 배열 | `{ messages: [...] }` |
| 출력 | `AIMessage` 하나 | `{ messages: [...] }` — **대화 전체** |
| 루프 | 없음. 한 번 답하고 끝 | 도구가 있으면 스스로 돈다 |

에이전트가 **대화 전체**를 돌려주는 게 중요합니다. 도구가 없는 지금은 `human` + `ai` 로 2개뿐이지만, Step 06~08 에서 도구를 붙이면 `ai`(도구 호출) → `tool`(결과) → `ai`(최종 답) 이 끼어들어 4개, 6개로 늘어납니다. **그 메시지 배열이 곧 에이전트가 무슨 생각을 했는지에 대한 기록**이고, 디버깅은 전부 그걸 읽는 일입니다.

`systemPrompt` 로 준 내용은 `result.messages` 에 **없습니다.** 대화 기록이 아니라 에이전트의 설정이기 때문입니다. 매 모델 호출 때 앞에 붙지만 결과 배열에는 남지 않습니다.

`createAgent` 는 이 코스의 중심입니다. [Step 08 — createAgent](../step-08-create-agent/) 에서 본격적으로 다루고, 그 전에 [Step 06 — 도구 정의](../step-06-tools/) 와 [Step 07 — 도구 호출 루프 직접 구현](../step-07-tool-loop/) 을 거칩니다. Step 07 에서 이 루프를 **손으로 만들어 본 다음** `createAgent` 로 돌아오면, 이 함수가 뭘 대신 해주고 있었는지가 훨씬 선명해집니다.

---

## 1-8. 문제 해결

처음 겪을 만한 에러를 모았습니다.

| 증상 | 원인 | 해결 |
|---|---|---|
| `Cannot find module 'langchain'` | `npm install` 을 `project/` 에서 함 | `cd docs/reference/langchain && npm install` |
| `ANTHROPIC_API_KEY` 없다고 나옴 | `import "dotenv/config"` 누락 / cwd 가 `project/` 가 아님 | 둘 다 확인 |
| `401 Unauthorized` | 키가 틀렸거나 폐기됨 / 셸의 낡은 키가 이김 | `echo $ANTHROPIC_API_KEY` |
| `400 credit balance is too low` | 결제 수단 미등록 | 콘솔에서 결제 설정 |
| `429 rate_limit_error` | 레이트 리밋 | 아래 참고 |
| `require is not defined in ES module scope` | ESM 에서 `require` 사용 | `import` 로 |
| `The current file is a CommonJS module` (top-level await) | `"type": "module"` 누락 | `package.json` 확인 |
| `Relative import paths need explicit file extensions` | 로컬 import 확장자 누락 | `from "./print.js"` |
| `model.invoke is not a function` | `initChatModel` 의 `await` 누락 | `await` 추가 |
| `Unable to infer model provider` | provider 접두사 오타 | 1-5 참고 |
| `instanceof` 가 false | `@langchain/core` 중복 | `npm ls @langchain/core` |

### ESM / CJS 문제

이 환경은 **ESM 전용**입니다. `package.json` 에 `"type": "module"` 이 있고 `tsconfig` 가 `NodeNext` 입니다. 여기서 `require` 는 못 씁니다.

```ts
const { createAgent } = require("langchain");
// ReferenceError: require is not defined in ES module scope
```

```ts
import { createAgent } from "langchain";   // 이렇게
```

블로그에서 복사한 코드가 `require` 를 쓰고 있다면 십중팔구 v0 시절 예제입니다 (1-1 참고).

ESM 이라 좋은 점도 있습니다 — **top-level `await`** 가 됩니다. `main()` 함수로 감쌀 필요가 없습니다.

```ts
// 이게 파일 최상단에서 바로 됩니다
const model = await initChatModel("anthropic:claude-sonnet-4-6");
```

`"type": "module"` 이 없으면 이 줄에서 `The current file is a CommonJS module and cannot use 'await' at the top level` 이 납니다.

### 타입 에러

가장 흔한 두 가지는 이미 다뤘습니다.

- **`usage_metadata` 가 `undefined` 일 수 있음** → optional 이니 확인하고 읽으세요 (1-6)
- **`content` 가 `string | ContentBlock[]`** → `.text` 를 쓰세요 (1-6)

둘 다 `as any` 나 `as string` 으로 누르고 싶어집니다. **누르지 마세요.** 이 두 타입 에러는 컴파일러가 실제 런타임 사고를 미리 알려주는 것입니다.

그리고 `tsx` 는 타입을 검사하지 않는다는 걸 잊지 마세요. 타입 에러는 `npm run typecheck` 를 돌려야 보입니다.

### 레이트 리밋 (429)

`429 rate_limit_error` 는 **정상적인 운영 상황**입니다. 버그가 아닙니다. 계정 등급마다 분당 요청 수와 분당 토큰 수에 상한이 있고, 넘으면 거절됩니다.

지금은 잠시 기다렸다 다시 돌리면 됩니다. 하지만 프로덕션에서는 **재시도가 필수**입니다. LangChain 에는 이걸 위한 `modelRetryMiddleware` 가 내장되어 있습니다 — [Step 11 — 내장 미들웨어](../step-11-middleware-builtin/) 에서 다룹니다.

> 💡 **실무 팁 — 재시도는 반드시 지수 백오프로**: 429 를 받고 곧바로 다시 던지면 또 429 를 받습니다. 그리고 그 재시도들이 뭉치면서 상황이 더 나빠집니다. `1초 → 2초 → 4초 → 8초` 로 간격을 늘리고, 거기에 **지터(jitter, 무작위 흔들기)** 를 더하세요. 지터가 없으면 동시에 429 를 맞은 인스턴스들이 **정확히 같은 타이밍에** 재시도해서 리밋을 다시 때립니다 (thundering herd).
>
> 그리고 모든 에러를 재시도하면 안 됩니다. `429`(리밋)와 `529`/`5xx`(서버 문제)는 재시도할 가치가 있지만, `401`(키 틀림)이나 `400`(요청 자체가 잘못됨)은 100번 재시도해도 100번 실패합니다. 돈과 시간만 씁니다.

---

## 정리

| 개념 | 요점 |
|---|---|
| LangChain 의 가치 | 제공자 표준화 + 에이전트 루프 하네스. **모델을 똑똑하게 만들지는 않음** |
| v1 (2025-10) | 체인 전부 제거 → `createAgent` 하나. 옛 코드는 `@langchain/classic` |
| `langchain` | `createAgent`, `initChatModel`, `tool` — 조립 도구 |
| `@langchain/core` | `AIMessage` 등 공용 타입. **peer dependency — 한 벌만** |
| `@langchain/anthropic` / `openai` | 제공자 어댑터 |
| `@langchain/langgraph` | 루프 엔진. Step 10 부터 직접 |
| 모델 지정 | `"anthropic:claude-sonnet-4-6"` (await 필요) 또는 `new ChatAnthropic({...})` |
| `.text` | 텍스트만 안전하게. **기본으로 쓸 것** |
| `.content` | `string \| ContentBlock[]` — 문자열 가정 금지 |
| `.contentBlocks` | 표준화된 블록 (camelCase!) |
| `.usage_metadata` | `input_tokens` / `output_tokens` / `total_tokens` — optional (snake_case!) |
| `.response_metadata` | 제공자 원본. 모양이 제각각 — 의존 금지 |
| 에이전트 | `{ messages }` 를 받아 `{ messages }` 를 돌려줌 (대화 전체) |

**핵심 함정 3가지**

1. **`import "dotenv/config"` 를 안 하면 `.env` 는 그냥 텍스트 파일이다.** 파일이 있어도 아무도 안 읽습니다. 게다가 셸에 `export` 해 둔 사람에겐 잘 동작해서 "내 컴퓨터에선 되는데"가 만들어집니다. dotenv 는 **cwd 기준**으로 `.env` 를 찾는다는 것도 함께 기억하세요.
2. **`@langchain/core` 가 두 벌이면 `instanceof` 가 에러 없이 `false` 가 된다.** 같은 이름의 클래스가 두 개 존재하기 때문입니다. `npm ls @langchain/core` 로 진단하고, 애초에 `getType()` 으로 판별해 예방하세요.
3. **provider 접두사 오타는 컴파일을 통과하고 런타임에 터진다.** `model` 파라미터가 그냥 `string` 이라서입니다. 모델 문자열은 상수로 모아 타입으로 좁히세요.

---

## 연습문제

1. `exercise.ts` 맨 위의 `import "dotenv/config";` 를 주석 처리하고 실행해 보세요. 키를 못 읽는 걸 확인한 뒤, **(a)** `.env` 는 그대로인데 왜 못 읽는지, **(b)** 그런데도 어떤 사람의 컴퓨터에서는 왜 잘 되기도 하는지 주석으로 설명하세요.
2. 모델 이름을 상수로 정의하고 `as const` 를 붙여 보세요. 붙였을 때와 안 붙였을 때 타입이 어떻게 다릅니까? 나아가 `"anthropi:..."` 같은 오타를 **컴파일 타임에** 잡으려면 어떻게 해야 합니까?
3. `initChatModel` 로 모델을 만들고 "너는 어떤 모델이야?" 라고 물어 답을 출력하세요. `await` 를 일부러 빼 보고 어떤 에러가 나는지 읽어 보세요.
4. 3번의 응답에서 `.text` / `.content` / `.contentBlocks` 를 모두 출력해 비교하세요. **(a)** 텍스트만 안전하게 꺼내려면 뭘 써야 하고 왜입니까? **(b)** `content` 가 배열로 오는 경우는 언제입니까?
5. 3번의 응답에서 `usage_metadata` 를 읽어 이번 호출의 비용을 달러로 계산하세요. 단가는 100만 토큰당 입력 $3.00 / 출력 $15.00 을 쓰세요. (`usage_metadata` 가 optional 이라는 점을 반드시 처리할 것)
6. 3번의 코드를 OpenAI 로 바꾸면 **몇 줄**을 고쳐야 합니까? 실제로 바꿔 보고 주석으로 적으세요.
7. `npm ls @langchain/core` 를 실행해 코어가 한 벌인지 확인하세요. 그리고 3번의 응답이 `instanceof AIMessage` 인지 코드로 확인하세요. 만약 `false` 가 나온다면 무엇을 의심해야 합니까?
8. `createAgent` 로 도구 없는 에이전트를 만들고 `systemPrompt` 에 "너는 무조건 한 문장으로만 답한다."를 주세요. **(a)** `result.messages` 는 몇 개이고 각각의 역할은 무엇입니까? **(b)** `systemPrompt` 도 그 배열에 들어 있습니까? **(c)** `model.invoke()` 와 입출력 모양이 어떻게 다릅니까?

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 02 — 챗 모델과 파라미터](../step-02-chat-models/)

`temperature`, `maxTokens`, `stopSequences` 같은 파라미터가 실제로 무엇을 바꾸는지 다룹니다. 그리고 **`temperature: 0` 이 결정성을 보장하지 않는다**는, 많은 사람이 끝까지 모르고 지나가는 사실을 확인합니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(1-2 ~ 1-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 맨 위에 `import "dotenv/config";` 가 있고, **반드시 `project/` 를 작업 디렉터리로 삼아** 실행해야 합니다.

```bash
cd docs/reference/langchain/project
npx tsx ../step-01-setup/practice.ts
```

실행 환경 자체에 대한 설명은 [실습 환경](../project/) 페이지에 있습니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[1-2] ~ [1-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다. (`[1-1]` 은 개념 설명이라 코드가 없어 `[1-2]` 부터 시작합니다.)

- `[1-3]` 은 네 개의 import 를 `typeof` 로 찍어 봅니다. 시시해 보이지만 **여기서 `function` 이 네 번 찍히면 설치와 모듈 해석이 전부 정상**이라는 뜻입니다. 모델 호출이 안 될 때 원인을 "환경"과 "키"로 반씩 갈라주는 체크포인트입니다.
- `[1-4]` 는 키를 **마스킹해서** 출력합니다. `앞뒤 공백` 항목을 눈여겨보세요 — `.env` 에 키를 붙여넣다 뒤에 공백이 딸려 들어가는 사고가 잦은데, 그러면 401 이 나면서도 키는 "있는" 것으로 보여 원인을 찾기 어렵습니다.
- `[1-5]` 는 문자열 방식과 인스턴스 방식을 **연달아** 실행합니다. 두 응답이 같은 모양이라는 걸 확인하는 게 목적입니다. 바로 아래 주석에 OpenAI 로 바꾸는 방법을 적어 두었으니, 키가 있다면 주석을 풀어 비교해 보세요.
- `[1-6]` 이 이 파일의 핵심입니다. `.text`, `.contentBlocks`, `usage_metadata`, `response_metadata` 를 차례로 출력합니다. **`response_metadata` 출력이 제공자마다 완전히 다르다**는 걸 눈으로 확인하는 것이 포인트입니다 — 그래서 여기에 의존하면 안 됩니다.
- `[1-8]` 은 일부러 `"anthropi:..."` 라는 오타를 내고 `try/catch` 로 잡습니다. `Unable to infer model provider` 라는 실제 에러 메시지를 미리 한 번 봐 두면, 나중에 진짜로 만났을 때 몇 분을 아낍니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래가 비어 있으니, 거기에 직접 코드를 써 넣고 파일을 통째로 실행해 검증하면 됩니다.

- `[문제 1]` 만 예외적으로 코드가 **이미 작성되어 있습니다.** 여러분이 할 일은 코드를 쓰는 게 아니라 맨 위의 `import "dotenv/config";` 를 주석 처리했다 되돌리며 관찰하고, 그 아래 `(a) 답:` / `(b) 답:` 자리를 채우는 것입니다. **확인이 끝나면 주석을 반드시 되돌리세요** — 안 그러면 나머지 문제가 전부 키를 못 읽습니다.
- `[문제 2]` 의 `as const` 는 그 자체로는 오타를 막지 못합니다(처음 적을 때 틀리면 그 오타가 리터럴 타입이 됩니다). "그럼 뭘 더 해야 하나"까지 가야 이 문제를 푼 것입니다. 힌트는 **허용 목록**입니다.
- `[문제 5]` 는 `usage_metadata` 가 optional 이라 `strict` 모드에서 그냥 못 읽습니다. 이 타입 에러를 `as any` 로 누르지 말고 정직하게 처리하는 게 문제의 절반입니다.
- `[문제 7]` 은 파일을 실행하기 전에 **터미널에서 `npm ls @langchain/core` 를 먼저** 돌려야 합니다. 대부분 `true` 가 나올 텐데, "왜 false 가 날 수 있는가"를 답할 수 있어야 진짜로 이해한 것입니다.
- 파일을 그대로 실행하면 `[문제 1]` 의 `true`/`false` 한 줄만 출력되고 나머지는 아무것도 안 나옵니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 2]` 가 이 파일에서 가장 실무적인 대목입니다. `MODELS` 객체 + `as const` + `type ModelId = (typeof MODELS)[keyof typeof MODELS]` 조합으로 **허용 목록을 타입으로** 만듭니다. 주석 처리된 `const BAD: ModelId = "anthropi:..."` 줄을 풀어 보세요 — 컴파일 에러가 나는 걸 확인하는 게 이 문제의 정답입니다. LangChain 의 `model` 파라미터가 그냥 `string` 인 이상, 이게 접두사 오타에 대한 유일한 실질적 방어입니다.
- `[정답 5]` 는 `total_tokens` 에 출력 단가를 곱하는 흔한 실수를 짚습니다. 입력과 출력은 단가가 5배 차이나므로 반드시 따로 곱해야 합니다. 마지막에 "이 호출을 100만 번 하면 얼마"를 찍어 주는데, 이 숫자를 한 번 보고 나면 토큰을 대하는 태도가 바뀝니다.
- `[정답 6]` 의 답은 **1줄**입니다. `invoke`, `.text`, `.usage_metadata`, `.contentBlocks` 가 글자 하나 안 바뀝니다. 1-1 의 "제공자 표준화"가 추상적인 마케팅 문구가 아니라는 걸 확인하는 문제입니다. `OPENAI_API_KEY` 가 없으면 자동으로 건너뛰니 그냥 실행해도 됩니다.
- `[정답 7]` 은 `instanceof` 가 `true` 로 나오는 걸 확인한 뒤, **곧바로 `getType()` 이라는 대안**을 보여줍니다. 이 순서가 의도적입니다 — "지금은 잘 되지만 언제든 깨질 수 있으니 애초에 의존하지 마라"가 요점입니다.
- `[정답 8]` 의 답은 메시지 **2개**(human, ai)이고, `systemPrompt` 는 그 배열에 **없습니다.** 설정이지 대화 기록이 아니기 때문입니다. 이 구분이 Step 10(메모리)에서 "무엇이 저장되고 무엇이 안 저장되는가"로 곧바로 이어집니다.

```ts file="./solution.ts"
```
