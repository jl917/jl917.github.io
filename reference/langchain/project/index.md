# 실습 환경

이 코스(Step 01 ~ Step 20)의 모든 `.ts` 파일은 **이 폴더의 의존성과 설정**으로 실행됩니다. 여러분이 매 스텝마다 `npm install` 을 다시 할 필요는 없습니다. 여기서 한 번 준비해 두면 20개 스텝이 전부 그 위에서 돕니다.

이 페이지는 그 환경이 **어떻게 구성되어 있는지**를 설명합니다. Step 01에서 처음 세팅할 때, 그리고 "왜 내 환경에서는 이 import 를 못 찾지?" 싶을 때 다시 찾아오면 됩니다.

## 전체 구성

```
docs/reference/langchain/
├── package.json          ← 워크스페이스 루트 (node_modules 가 여기 생깁니다)
├── node_modules/         ← 설치 결과. step-*/ 에서도 여기를 찾습니다
├── project/              ← 이 페이지가 설명하는 폴더
│   ├── package.json      ← 실제 의존성 선언
│   ├── tsconfig.json     ← TypeScript 설정
│   ├── .env.example      ← API 키 서식 (커밋함)
│   ├── .env              ← 실제 키 (커밋 안 함, 직접 만듭니다)
│   ├── .gitignore
│   └── src/lib/print.ts  ← 공용 출력 헬퍼
├── step-01-setup/
│   ├── practice.ts
│   ├── exercise.ts
│   └── solution.ts
├── step-02-chat-models/
└── ...
```

`package.json` 이 **두 개**인 것이 눈에 띌 겁니다. 이유가 있습니다.

| 파일 | 역할 |
|------|------|
| `langchain/package.json` | 워크스페이스 루트. 의존성을 선언하지 않고, `node_modules` 를 이 위치로 끌어올리는 일만 합니다 |
| `langchain/project/package.json` | 실제 의존성(`langchain`, `@langchain/anthropic` ...)을 선언하는 곳 |

**왜 이렇게 나눴습니까?** 실습 파일이 `project/` 바깥의 `step-01-setup/` 에 있기 때문입니다.

Node.js 는 `import { createAgent } from "langchain"` 같은 bare import 를 만나면 **그 파일이 있는 디렉터리에서부터 위로** 올라가며 `node_modules` 를 찾습니다. 만약 `node_modules` 가 `project/` 안에만 있다면, `step-01-setup/practice.ts` 는 위로 올라가도 그걸 못 만납니다 — `project/` 는 부모가 아니라 **형제**니까요. 그래서 워크스페이스 루트를 하나 두어 `node_modules` 를 `langchain/` 레벨로 올렸습니다. 이제 어느 스텝 폴더에서든 위로 한 칸만 올라가면 찾습니다.

이 루트 `package.json` 에는 `"type": "module"` 도 들어 있습니다. 이게 없으면 `step-*/*.ts` 가 CommonJS 로 인식되어 **top-level `await` 가 컴파일 에러**가 납니다.

## 준비하기

```bash
cd docs/reference/langchain
npm install
```

`node_modules` 는 `project/` 가 아니라 `docs/reference/langchain/` 에 생깁니다. 정상입니다.

이어서 API 키를 넣습니다.

```bash
cd project
cp .env.example .env
# 편집기로 .env 를 열어 ANTHROPIC_API_KEY 를 채웁니다
```

키 발급은 [console.anthropic.com](https://console.anthropic.com/settings/keys) 에서 합니다. 자세한 건 [Step 01 — 환경 구축과 첫 모델 호출](../step-01-setup/) 에서 다룹니다.

## 실행하기

**항상 `project/` 에서 실행합니다.**

```bash
cd docs/reference/langchain/project
npx tsx ../step-01-setup/practice.ts
```

`project/` 를 작업 디렉터리로 삼는 이유는 **dotenv 때문**입니다. `import "dotenv/config"` 는 `.env` 를 `process.cwd()` 기준으로 찾습니다. 즉 "파일이 어디 있느냐"가 아니라 "**어느 디렉터리에서 명령을 쳤느냐**"가 기준입니다. 저장소 루트에서 `npx tsx docs/reference/langchain/step-01-setup/practice.ts` 를 치면 dotenv 는 저장소 루트의 `.env` 를 찾다가 실패하고, 키가 없다며 죽습니다. **`.env` 는 멀쩡히 `project/` 에 있는데도요.**

> ⚠️ **함정**: "키를 분명히 넣었는데 못 읽는다"의 절반은 `import "dotenv/config"` 를 빠뜨린 것이고, 나머지 절반이 이 **cwd** 문제입니다. 파일 위치가 아니라 실행 위치가 기준이라는 걸 기억하세요.

타입 검사는 따로 돌립니다. `tsx` 는 **타입을 검사하지 않고** 트랜스파일만 하기 때문에, 타입이 틀려도 그냥 실행됩니다.

```bash
cd docs/reference/langchain/project
npm run typecheck        # tsc --noEmit
```

## 검증된 버전

아래 조합에서 이 코스의 모든 예제가 동작하는 것을 확인했습니다 (2026-07 기준).

| 항목 | 버전 |
|------|------|
| Node.js | 22.22.0 (**22 이상 필수**) |
| `langchain` | 1.5.3 |
| `@langchain/core` | 1.2.3 |
| `@langchain/anthropic` | 1.5.1 |
| `@langchain/openai` | 1.5.5 |
| `@langchain/langgraph` | 1.4.8 |
| `zod` | 4.4.3 |
| `typescript` | 5.9.x |
| `tsx` | 4.20.x |

## 파일별 설명

### package.json

의존성 선언의 원본입니다. 세 덩어리로 읽으면 됩니다.

- **`langchain` + `@langchain/core`** — 프레임워크 본체. 이 둘은 언제나 같이 갑니다.
- **`@langchain/anthropic` / `@langchain/openai`** — 제공자 어댑터. 쓰는 것만 깔면 됩니다. 이 코스는 Anthropic 을 기본으로 하고 OpenAI 를 대안으로 씁니다.
- **`@langchain/langgraph`** — `createAgent` 밑에서 돌아가는 실행 엔진. Step 10(메모리)부터 직접 import 합니다.

`"type": "module"` 이 핵심입니다. 이게 있어야 이 폴더의 `.ts` 가 ESM 으로 취급됩니다.

`@langchain/*` 패키지들의 버전을 **서로 맞춰 두는 것**도 중요합니다. 어긋나면 `@langchain/core` 가 두 벌 설치되어 `instanceof` 검사가 조용히 깨집니다 (Step 01 의 함정 참고). 확인은 `npm run check:core` 로 합니다 — `deduped` 만 보이면 안전합니다.

```json file="./package.json"
```

### tsconfig.json

`module`/`moduleResolution` 을 **`NodeNext`** 로 둔 것이 이 파일의 전부라고 해도 됩니다. Node.js 의 실제 ESM 해석 규칙을 그대로 흉내내므로, "타입 검사는 통과했는데 실행하면 모듈을 못 찾는" 사고를 막아 줍니다. 대신 규칙 하나를 받아들여야 합니다 — **로컬 파일을 import 할 때 확장자를 `.js` 로 씁니다.**

```ts
import { printSection } from "../project/src/lib/print.js";   // .ts 가 아니라 .js
```

`.ts` 파일을 가리키면서 `.js` 라고 쓰는 게 이상해 보이지만, TypeScript 가 "컴파일된 뒤의 경로"를 기준으로 해석하기 때문입니다. 확장자를 빼거나 `.ts` 로 쓰면 `tsc` 가 에러를 냅니다.

`include` 에 `"../step-*/**/*.ts"` 가 들어 있어서, `npm run typecheck` 한 번으로 **20개 스텝의 모든 실습 파일**을 한꺼번에 검사합니다.

```json file="./tsconfig.json"
```

### .env.example

**이 파일은 커밋합니다. `.env` 는 절대 커밋하지 않습니다.** 값이 아니라 "어떤 키가 필요한지"를 알려 주는 문서이기 때문입니다. 새로 합류한 사람이 `cp .env.example .env` 한 줄로 시작할 수 있게 하는 것이 목적입니다.

`ANTHROPIC_API_KEY` 만 있으면 Step 01~20 이 전부 돌아갑니다. 나머지는 선택입니다.

```bash file="./.env.example"
```

### .gitignore

`.env` 를 막는 세 줄의 순서가 중요합니다.

```
.env
.env.*
!.env.example
```

`.env.*` 로 `.env.local`, `.env.production` 까지 전부 막은 다음, `!.env.example` 로 예시 파일만 다시 꺼냅니다. **나중 줄이 앞 줄을 덮어쓰므로 순서를 바꾸면 `.env.example` 이 커밋되지 않습니다.**

```bash file="./.gitignore"
```

### src/lib/print.ts

모든 스텝이 공유하는 출력 헬퍼입니다. `AIMessage` 를 그냥 `console.log` 하면 내부 필드가 수십 줄로 쏟아져서 정작 봐야 할 내용이 묻히기 때문에 만들었습니다.

| 함수 | 하는 일 |
|------|---------|
| `printSection(title)` | `[1-5]` 같은 절 구분선. 본문 소제목 번호와 1:1 대응합니다 |
| `printMessages(msg \| msgs)` | 메시지를 역할별 색으로 출력. `tool_calls`, `tool_call_id`, 토큰 수까지 |
| `printUsage(msg)` | 토큰 사용량 상세 (캐시 읽기, 추론 토큰 포함) |
| `printJson(label, value)` | 아무 객체나 들여쓴 JSON 으로 |
| `printKV(rows)` | "키: 값" 표 정렬 출력 |
| `requireEnv(name)` | 환경변수 확인. 없으면 원인 후보를 알려주고 종료 |

이 파일에서 눈여겨볼 대목이 두 군데 있습니다.

첫째, `getToolCalls` / `getUsage` 가 `instanceof AIMessage` 를 **쓰지 않습니다.** `@langchain/core` 가 두 벌 설치되면 `instanceof` 는 예외도 없이 조용히 `false` 가 되기 때문입니다. 대신 "그 필드가 실제로 있는지"만 보는 구조적 검사를 씁니다. 코어가 몇 벌이든 항상 동작합니다.

둘째, `usage_metadata` 를 읽기 전에 반드시 `undefined` 확인을 합니다. 이 필드는 optional 이고, 스트리밍 청크(Step 09)나 일부 제공자에서는 정말로 안 옵니다.

```ts file="./src/lib/print.ts"
```

## 문제가 생기면

| 증상 | 원인 | 해결 |
|------|------|------|
| `Cannot find module 'langchain'` | `npm install` 을 `project/` 에서 했음 | `cd docs/reference/langchain && npm install` |
| `ANTHROPIC_API_KEY` 없다고 나옴 | cwd 가 `project/` 가 아님 | `cd project` 후 실행 |
| `The current file is a CommonJS module` | 루트 `package.json` 의 `"type": "module"` 누락 | 루트 `package.json` 확인 |
| `Relative import paths need explicit file extensions` | `.js` 확장자 누락 | `from "./print.js"` 로 |
| `instanceof` 가 false | `@langchain/core` 중복 | `npm ls @langchain/core` 확인 |

환경을 완전히 초기화하려면:

```bash
cd docs/reference/langchain
rm -rf node_modules package-lock.json
npm install
```

`.env` 는 `.gitignore` 되어 있으므로 이 명령으로 지워지지 않습니다. 안심하고 실행하세요.
