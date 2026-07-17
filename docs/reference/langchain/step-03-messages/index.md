# Step 03 — 메시지와 콘텐츠 블록

> **학습 목표**
> - 대화가 **메시지 배열이라는 상태(state)** 라는 것을 이해하고, 그 배열을 직접 만들고 조작한다
> - `SystemMessage` / `HumanMessage` / `AIMessage` / `ToolMessage` 를 **누가 언제 만드는지** 구분해서 쓴다
> - 객체 리터럴(`{ role: "user", content: "..." }`)과 클래스(`new HumanMessage(...)`) **두 표기법**을 상황에 맞게 고른다
> - `content` 가 **문자열일 때와 배열일 때**를 구분하고, `.text` / `.contentBlocks` 접근자로 안전하게 읽는다
> - 이미지·파일을 **콘텐츠 블록**으로 넣어 멀티모달 요청을 만든다
> - `AIMessage` 를 해부해 `tool_calls` / `usage_metadata` / `response_metadata` 를 꺼낸다
> - `ToolMessage` 의 `tool_call_id` 계약을 지켜 대화가 깨지지 않게 한다
> - `trimMessages` 로 컨텍스트를 줄이면서 **대화를 망가뜨리지 않는 법**을 익힌다
>
> **선행 스텝**: [Step 02 — 챗 모델과 파라미터](../step-02-chat-models/)
> **예상 소요**: 70분

[Step 02](../step-02-chat-models/) 에서 `model.invoke("안녕")` 처럼 문자열을 던져 답을 받아봤습니다. 편하지만 거기까지입니다. 문자열 하나로는 "너는 어떤 역할이다"를 지정할 수 없고, 이전 대화를 기억시킬 수 없고, 이미지를 붙일 수 없고, 도구 실행 결과를 돌려줄 수도 없습니다. 이 모든 것이 **메시지(message)** 로 표현됩니다.

이 스텝은 이 코스 전체에서 가장 자주 되돌아오게 될 곳입니다. 에이전트가 하는 일이라곤 결국 **메시지 배열을 읽고, 메시지를 하나 더 붙이는 것**의 반복이기 때문입니다. Step 07(도구 호출 루프)에서 손으로 짜게 될 루프도, Step 08의 `createAgent` 가 내부에서 하는 일도, Step 10의 메모리도 전부 "이 배열을 어떻게 관리하느냐"의 문제입니다. 여기서 대충 넘어가면 이후 스텝의 버그가 전부 여기로 돌아옵니다.

> **이 스텝의 검증 버전**: `langchain@1.5.3`, `@langchain/core@1.2.3`, `@langchain/anthropic@1.5.1`.
> 본문의 **구조 출력**(객체 shape, 필드명, 트리밍 결과)은 이 버전에서 실제로 실행해 확인한 값입니다.
> 반면 **모델이 생성한 텍스트**는 매번 다릅니다 — 그런 출력에는 따로 표시를 해 두었습니다.

---

## 3-1. 메시지가 곧 상태다

LLM API 는 **상태가 없습니다(stateless)**. 서버는 여러분이 어제 무슨 대화를 했는지 기억하지 않습니다. 그럼 ChatGPT 는 어떻게 이전 대화를 기억하는 걸까요? 답은 시시합니다. **매번 전체 대화를 통째로 다시 보내기 때문입니다.**

```ts
import { initChatModel } from "langchain";

const model = await initChatModel("anthropic:claude-sonnet-4-6");

// 1번째 턴
const a1 = await model.invoke([{ role: "user", content: "내 이름은 김민수야." }]);

// 2번째 턴 — 이전 대화를 "직접" 배열에 쌓아서 다시 보낸다
const a2 = await model.invoke([
  { role: "user", content: "내 이름은 김민수야." },   // 1턴 질문
  { role: "assistant", content: a1.text },            // 1턴 답변
  { role: "user", content: "내 이름이 뭐라고?" },      // 2턴 질문
]);

console.log(a2.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
김민수님이라고 하셨습니다.
```

여기서 배열의 마지막 항목만 보내면 어떻게 될까요?

```ts
const a3 = await model.invoke([{ role: "user", content: "내 이름이 뭐라고?" }]);
console.log(a3.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
죄송하지만 이름을 알려주신 적이 없어서 알 수 없습니다.
```

모델이 "까먹은" 게 아닙니다. **애초에 안 준 것**입니다. 이것이 이 코스 전체를 관통하는 첫 번째 원칙입니다.

> 💡 **실무 팁 — 이 문장을 외우세요**: **"대화 = 메시지 배열(state), 한 턴 = 배열에 메시지를 append"**.
> 앞으로 배울 모든 것이 이 문장의 변주입니다. 메모리(Step 10)는 "이 배열을 어디에 저장하느냐", 컨텍스트 엔지니어링(Step 14)은 "이 배열에 무엇을 넣고 뺄 것이냐", 미들웨어(Step 11~12)는 "모델에 보내기 직전 이 배열을 어떻게 손볼 것이냐"의 문제입니다.
> 에이전트가 이상하게 동작하면 **가장 먼저 `console.dir(messages, { depth: null })` 로 배열을 통째로 찍어보세요.** 열에 아홉은 배열이 여러분 생각과 다르게 생겼습니다.

---

## 3-2. 메시지 타입 완전 정복

메시지는 **역할(role)** 로 구분됩니다. LangChain 은 역할마다 클래스를 하나씩 줍니다. 네 가지가 전부이고, 이 네 개로 모든 대화가 표현됩니다.

| 타입 | role 문자열 | `.type` 값 | **누가 만드나** | 언제 쓰나 |
|---|---|---|---|---|
| `SystemMessage` | `"system"` | `system` | **개발자** | 모델의 역할·규칙·페르소나 지정. 보통 배열 맨 앞에 1개 |
| `HumanMessage` | `"user"` | `human` | **사용자(=개발자 코드)** | 사용자 입력. 이미지·파일도 여기에 실림 |
| `AIMessage` | `"assistant"` | `ai` | **모델** | 모델의 응답. 텍스트 + 도구 호출 + 토큰 사용량을 담음 |
| `ToolMessage` | `"tool"` | `tool` | **개발자(도구 실행 결과)** | 도구를 실행한 결과를 모델에게 돌려줌. `tool_call_id` 필수 |

여기서 헷갈리는 지점을 먼저 정리합니다. **`role` 문자열과 `.type` 값이 다릅니다.**

```ts
import { HumanMessage, AIMessage } from "langchain";

const h = new HumanMessage("안녕");
console.log(h.type);   // "human"   ← "user" 가 아니다
const a = new AIMessage("반가워");
console.log(a.type);   // "ai"      ← "assistant" 가 아니다
```

**출력** (구조가 결정적입니다)
```
human
ai
```

객체 리터럴로 쓸 땐 `role: "user"` / `role: "assistant"` (OpenAI 호환 명칭)를 쓰지만, 만들어진 객체의 `.type` 은 `human` / `ai` 입니다. 이 비대칭은 LangChain 의 역사적 유산이며, 필터링 코드를 짤 때 자주 발을 겁니다.

```ts
// ❌ 걸러지지 않는다 — .type 은 "user" 가 절대 아니다
const userMsgs = messages.filter((m) => m.type === "user");   // 항상 []

// ✅
const userMsgs = messages.filter((m) => m.type === "human");
```

타입 판별에는 문자열 비교보다 **타입가드**가 안전합니다. TypeScript 가 타입을 좁혀줘서 `m.tool_calls` 같은 필드에 안전하게 접근할 수 있기 때문입니다.

```ts
import { isAIMessage, isHumanMessage, isToolMessage, isSystemMessage } from "@langchain/core/messages";

for (const m of messages) {
  if (isAIMessage(m)) {
    console.log("AI:", m.text, "| 도구호출:", m.tool_calls?.length ?? 0);  // ← m 이 AIMessage 로 좁혀짐
  }
}
```

> ⚠️ **함정 (import 경로)**: `HumanMessage` 같은 **클래스**와 `trimMessages` 는 `"langchain"` 에서 바로 import 되지만, **`isAIMessage` 등 타입가드 함수는 `"langchain"` 에 없습니다.** 반드시 `"@langchain/core/messages"` 에서 가져와야 합니다.
> ```ts
> import { isAIMessage } from "langchain";                    // ❌ undefined
> import { isAIMessage } from "@langchain/core/messages";     // ✅
> ```
> `"langchain"` 에서 가져오면 import 자체는 조용히 통과하고(번들러 설정에 따라) 호출하는 순간 `isAIMessage is not a function` 으로 터집니다. 컴파일 타임이 아니라 **런타임에** 터지는 게 고약한 점입니다.

---

## 3-3. 두 가지 표기법 — 객체 리터럴 vs 클래스

같은 메시지를 두 가지로 쓸 수 있습니다. 둘은 **완전히 동등**합니다.

```ts
import { HumanMessage, SystemMessage, AIMessage, ToolMessage } from "langchain";

// (A) 객체 리터럴 — 가볍다
const msgsA = [
  { role: "system", content: "너는 간결한 한국어 도우미다." },
  { role: "user", content: "안녕?" },
];

// (B) 클래스 인스턴스 — 명시적이다
const msgsB = [
  new SystemMessage("너는 간결한 한국어 도우미다."),
  new HumanMessage("안녕?"),
];

// 둘 다 그대로 넘길 수 있다
await model.invoke(msgsA);
await model.invoke(msgsB);
```

LangChain 은 리터럴을 내부에서 클래스로 **강제 변환(coerce)** 합니다. 실제로 확인해 봅시다.

```ts
import { coerceMessageLikeToMessage } from "@langchain/core/messages";

console.log(coerceMessageLikeToMessage({ role: "user", content: "안녕" }).constructor.name);
console.log(coerceMessageLikeToMessage({ role: "assistant", content: "네" }).constructor.name);
console.log(coerceMessageLikeToMessage({ role: "system", content: "너는" }).constructor.name);
console.log(coerceMessageLikeToMessage({ role: "tool", content: "결과", tool_call_id: "call_1" }).constructor.name);
console.log(coerceMessageLikeToMessage("맨 문자열").constructor.name);
```

**출력** (구조가 결정적입니다)
```
HumanMessage
AIMessage
SystemMessage
ToolMessage
HumanMessage
```

마지막 줄이 중요합니다. **맨 문자열은 `HumanMessage` 가 됩니다.** `model.invoke("안녕")` 이 동작했던 이유가 이것입니다 — 내부적으로 `[new HumanMessage("안녕")]` 이 된 것뿐입니다.

| | 객체 리터럴 | 클래스 인스턴스 |
|---|---|---|
| 타이핑 양 | 적다 | 많다 |
| import | 불필요 | 필요 |
| JSON 직렬화 | 그대로 됨 | `.toDict()` 필요 |
| `.text` / `.contentBlocks` | **못 쓴다** (그냥 객체다) | 쓸 수 있다 |
| `tool_calls` 등 헬퍼 | 없음 | 있음 |
| 주 용도 | **모델에 넣는 입력** | **모델에서 나온 출력**을 다룰 때 |

> 💡 **실무 팁 — 실무에서의 관행**: **넣을 땐 리터럴, 받을 땐 클래스**입니다.
> 모델이 반환하는 것은 **항상 `AIMessage` 인스턴스**이므로 `.text`, `.tool_calls` 를 바로 쓸 수 있습니다. 반면 내가 만들어 넣는 입력은 리터럴이 짧고 읽기 좋습니다.
> 단, **`ToolMessage` 만은 클래스로 쓰길 권합니다.** `tool_call_id` 를 빼먹으면 리터럴은 런타임까지 조용하지만, 클래스는 타입 에러로 잡아주기 때문입니다(3-7 참고).

배열에 리터럴과 클래스를 **섞어도 됩니다.** 실제 에이전트 코드가 대개 이렇게 생겼습니다.

```ts
const messages = [
  { role: "system", content: "너는 날씨 도우미다." },  // 내가 쓴 것 → 리터럴
  { role: "user", content: "서울 날씨?" },             // 내가 쓴 것 → 리터럴
  aiMessage,                                          // 모델이 준 것 → AIMessage 인스턴스
  new ToolMessage({ content: "맑음", tool_call_id: "call_1" }),  // 도구 결과 → 클래스
];
```

---

## 3-4. 콘텐츠 블록 — content 가 문자열일 때와 배열일 때

지금까지 `content` 는 전부 문자열이었습니다. 하지만 `content` 의 실제 타입은 이렇습니다.

```ts
type MessageContent = string | Array<ContentBlock>;
```

**둘 중 하나입니다.** 이미지를 넣거나, 모델이 추론(reasoning)과 텍스트를 함께 반환하면 배열이 됩니다. 이 이중성이 이 스텝 최대의 함정을 만듭니다.

```ts
import { HumanMessage } from "langchain";

// (A) content 가 문자열
const m1 = new HumanMessage("안녕");
console.log(typeof m1.content);          // "string"
console.log(m1.content.toUpperCase());   // 동작한다

// (B) content 가 배열
const m2 = new HumanMessage({
  content: [
    { type: "text", text: "이 이미지 설명해줘" },
    { type: "image", url: "https://example.com/a.png" },
  ],
});
console.log(typeof m2.content);          // "object"
console.log(m2.content.toUpperCase());   // 💥 TypeError
```

**출력** (구조가 결정적입니다)
```
string
안녕
object
TypeError: m2.content.toUpperCase is not a function
```

> ⚠️ **함정 (이 스텝의 핵심) — `.content` 를 문자열로 가정하지 마라**: `msg.content` 는 문자열일 수도 **배열일 수도** 있습니다. 개발 중에 텍스트만 다뤄서 `content.slice(0, 100)`, `content.trim()`, `` `${content}` `` 같은 코드를 짜 두면, **나중에 이미지를 붙이거나 reasoning 모델로 바꾸는 순간** 조용히 `[object Object]` 가 되거나 `TypeError` 로 터집니다.
> **해법은 하나입니다 — 텍스트가 필요하면 `.content` 가 아니라 `.text` 를 쓰세요.**
> ```ts
> msg.content   // ❌ string | ContentBlock[] — 매번 분기해야 한다
> msg.text      // ✅ 항상 string
> ```
> `.text` 는 문자열이면 그대로, 배열이면 **텍스트 블록만 골라 이어붙여** 반환합니다. 이 코스의 모든 예제가 `.text` 를 쓰는 이유입니다.

반대로 **구조가 필요하면 `.contentBlocks`** 를 씁니다. 이쪽은 `.content` 와 달리 **항상 배열**로, 게다가 provider 차이를 흡수한 **표준 형태**로 정규화해 줍니다.

```ts
const m1 = new HumanMessage("안녕");
console.log(JSON.stringify(m1.contentBlocks));
```

**출력** (구조가 결정적입니다)
```json
[{"type":"text","text":"안녕"}]
```

문자열로 넣었는데도 **블록 배열로 정규화**되어 나옵니다. 즉 `.contentBlocks` 를 쓰면 "문자열이냐 배열이냐" 분기가 아예 사라집니다.

### 세 접근자 정리

| 접근자 | 타입 | 성질 | 언제 |
|---|---|---|---|
| `.content` | `string \| ContentBlock[]` | **원본 그대로.** 분기 필요 | provider 원형이 필요한 특수 상황 |
| `.text` | `string` | 항상 문자열. 텍스트 블록만 이어붙임 | **텍스트를 읽을 때 (기본값)** |
| `.contentBlocks` | `ContentBlock[]` | 항상 배열. 표준 형태로 정규화 | 이미지/추론/도구호출 등 **구조**를 볼 때 |

### 주요 표준 블록 타입

```ts
// 텍스트
{ type: "text", text: "안녕", annotations: [] }

// 추론 (reasoning 모델의 사고 과정)
{ type: "reasoning", reasoning: "사용자는 날씨를 물었다..." }

// 도구 호출
{ type: "tool_call", name: "get_weather", args: { city: "서울" }, id: "call_1" }

// 이미지 / 파일 (3-5 에서 자세히)
{ type: "image", url: "https://..." }
{ type: "file", data: "<base64>", mimeType: "application/pdf" }

// provider 고유 블록 (표준화되지 않은 것)
{ type: "non_standard", value: { /* provider 원본 */ } }
```

`.text` 가 이 블록들을 어떻게 처리하는지 직접 봅시다.

```ts
import { AIMessage } from "langchain";

const a = new AIMessage({
  content: [
    { type: "reasoning", reasoning: "사용자는 날씨를 물었다" },
    { type: "text", text: "서울은" },
    { type: "text", text: " 맑습니다" },
  ],
});
console.log(JSON.stringify(a.text));
```

**출력** (구조가 결정적입니다)
```
"서울은 맑습니다"
```

**텍스트 블록 2개는 이어붙고, reasoning 블록은 `.text` 에서 빠집니다.** 이건 대개 원하는 동작입니다 — 사용자에게 모델의 내부 사고를 그대로 보여줄 일은 없으니까요. 하지만 추론 과정을 로그로 남기고 싶다면 `.contentBlocks` 에서 직접 꺼내야 합니다.

```ts
const reasoning = a.contentBlocks
  .filter((b) => b.type === "reasoning")
  .map((b) => b.reasoning)
  .join("\n");
console.log(reasoning);   // "사용자는 날씨를 물었다"
```

> 💡 **실무 팁**: reasoning 블록은 **`.text` 에 안 잡히므로 로그에서 조용히 사라집니다.** "모델이 왜 이런 답을 했지?"를 디버깅할 때 가장 값진 정보인데도 말입니다. 관측(Step 19)을 붙일 때 `.contentBlocks` 에서 reasoning 을 따로 추출해 남기면 디버깅이 훨씬 수월해집니다. 단, reasoning 토큰은 `usage_metadata.output_token_details.reasoning` 으로 **과금**되니 비용도 같이 보세요(3-6).

---

## 3-5. 멀티모달 — 이미지 넣기

이미지는 `HumanMessage` 의 content 배열에 **이미지 블록**을 넣어 전달합니다. 소스는 세 가지입니다.

```ts
// (1) URL — 가장 간단. provider 가 대신 다운로드한다
{ type: "image", url: "https://example.com/cat.png" }

// (2) base64 — 로컬 파일. mimeType 이 필수다
{ type: "image", data: "<base64 문자열>", mimeType: "image/png" }

// (3) file id — provider 의 파일 API 에 미리 업로드한 것
{ type: "image", fileId: "file-abc123" }
```

로컬 이미지를 base64 로 실어 보내는 전체 코드입니다.

```ts
import { readFile } from "node:fs/promises";
import { HumanMessage, initChatModel } from "langchain";

const model = await initChatModel("anthropic:claude-sonnet-4-6");
const bytes = await readFile("./cat.png");

const msg = new HumanMessage({
  content: [
    { type: "text", text: "이 사진에 무엇이 보이나요? 한 문장으로." },
    { type: "image", data: bytes.toString("base64"), mimeType: "image/png" },
  ],
});

const res = await model.invoke([msg]);
console.log(res.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
창가에 앉아 있는 회색 고양이 한 마리가 보입니다.
```

PDF 같은 파일도 같은 모양입니다.

```ts
const pdf = await readFile("./report.pdf");
const msg = new HumanMessage({
  content: [
    { type: "text", text: "이 문서를 3줄로 요약해줘." },
    { type: "file", data: pdf.toString("base64"), mimeType: "application/pdf" },
  ],
});
```

> ⚠️ **함정 (필드 이름이 버전마다 다르다)**: 인터넷과 예전 블로그에는 이런 형태가 널려 있습니다.
> ```ts
> { type: "image", source_type: "url", url: "..." }                       // ❌ 구버전(v0.3) 형식
> { type: "image_url", image_url: { url: "..." } }                        // ❌ OpenAI 원형
> { type: "image", source_type: "base64", mime_type: "...", data: "..." } // ❌ 구버전 + snake_case
> ```
> **`@langchain/core@1.x` 의 표준 블록은 `source_type` 이 없고, `mimeType` 이 camelCase 입니다.**
> ```ts
> { type: "image", url: "..." }                              // ✅
> { type: "image", data: "...", mimeType: "image/png" }      // ✅
> { type: "image", fileId: "file-abc123" }                   // ✅
> ```
> 헷갈리기 딱 좋은 게, **틀린 블록을 넣어도 LangChain 은 대체로 에러를 안 냅니다.** 블록의 타입 정의가 `[key: string]: unknown` 으로 열려 있어서 모르는 키는 그냥 통과시키기 때문입니다. 그리고 provider 단에서 "이미지가 없는데?" 라거나 400 이 납니다. **에러 메시지가 원인에서 멀리 떨어져 나타나는** 전형적인 함정입니다.

> 💡 **실무 팁 — 셋 중 뭘 쓰나**:
> - **URL**: 이미 공개 URL 이 있으면 최선. 요청 페이로드가 작습니다. 단 provider 가 그 URL 에 **접근 가능해야** 합니다 — 사내망 URL 이나 `localhost` 는 당연히 실패합니다(이것도 흔한 사고입니다).
> - **base64**: 로컬 파일·비공개 이미지의 기본 선택. 단 base64 는 원본보다 **약 33% 커지고**, 그 전체가 요청 본문에 들어갑니다. 큰 이미지를 그대로 넣으면 요청 크기 제한에 걸립니다.
> - **fileId**: **같은 파일을 여러 번** 쓸 때. 한 번 업로드하고 ID 만 재사용하니 대용량 PDF 반복 질의에 유리합니다.
>
> 그리고 이미지는 **토큰을 많이 먹습니다.** 3-6 의 `usage_metadata.input_token_details.image` 로 실제 소모량을 꼭 확인하세요.

---

## 3-6. AIMessage 해부

모델이 반환하는 `AIMessage` 에는 `.text` 말고도 볼 게 많습니다. 실무에서 필요한 정보 대부분이 여기 있습니다.

```ts
const res = await model.invoke([{ role: "user", content: "안녕이라고만 답해." }]);

console.log("id:               ", res.id);
console.log("text:             ", res.text);
console.log("content:          ", JSON.stringify(res.content));
console.log("tool_calls:       ", JSON.stringify(res.tool_calls));
console.log("usage_metadata:   ", JSON.stringify(res.usage_metadata, null, 2));
console.log("response_metadata:", JSON.stringify(res.response_metadata, null, 2));
```

**출력 예시** (모델 응답이므로 값은 매번 다릅니다 — **필드 이름은 결정적입니다**)
```
id:                msg_01ABC...
text:              안녕
content:           "안녕"
tool_calls:        []
usage_metadata:    {
  "input_tokens": 18,
  "output_tokens": 5,
  "total_tokens": 23,
  "input_token_details": { "cache_read": 0, "cache_creation": 0 },
  "output_token_details": {}
}
response_metadata: {
  "model_provider": "anthropic",
  "model_name": "claude-sonnet-4-6",
  ...
}
```

필드별로 정리합니다.

| 필드 | 타입 | 내용 |
|---|---|---|
| `id` | `string?` | 메시지 고유 ID. provider 가 준 값 |
| `text` | `string` | 텍스트 (접근자) |
| `content` | `string \| ContentBlock[]` | 원본 content |
| `contentBlocks` | `ContentBlock[]` | 표준화된 블록 배열 (접근자) |
| `tool_calls` | `ToolCall[]` | 모델이 요청한 도구 호출 목록 |
| `usage_metadata` | `UsageMetadata?` | **토큰 사용량** (아래) |
| `response_metadata` | `ResponseMetadata` | provider 원본 메타(모델명, 종료 사유 등) |

### usage_metadata — 비용의 원천

**snake_case 필드명**을 정확히 외워두면 좋습니다. 대시보드나 비용 계산 코드가 전부 여기서 나옵니다.

```ts
type UsageMetadata = {
  input_tokens: number;              // 입력 토큰 총합
  output_tokens: number;             // 출력 토큰 총합
  total_tokens: number;              // input + output
  input_token_details?: {
    cache_read?: number;             // 프롬프트 캐시 히트 (싸다)
    cache_creation?: number;         // 캐시 생성 (미스)
    text?: number; image?: number; audio?: number; video?: number; document?: number;
  };
  output_token_details?: {
    reasoning?: number;              // 추론 토큰 (출력으로 과금되지만 .text 엔 안 보인다)
    text?: number; image?: number; audio?: number; video?: number; document?: number;
  };
};
```

> 💡 **실무 팁**: `usage_metadata` 는 **optional 입니다(`?`)**. provider·설정에 따라 `undefined` 일 수 있고, 특히 스트리밍(Step 09)에서는 별도 옵션 없이는 안 오거나 마지막 청크에만 붙습니다. 그래서 비용 집계 코드는 반드시 방어적으로 짜야 합니다.
> ```ts
> // ❌ 스트리밍이나 일부 provider 에서 터진다
> total += res.usage_metadata.total_tokens;
> // ✅
> total += res.usage_metadata?.total_tokens ?? 0;
> ```
> 그리고 `input_tokens` 는 **매 턴 대화 전체**에 대해 과금됩니다. 10턴짜리 대화의 10번째 요청은 1~9턴을 전부 다시 입력으로 보내니, 비용은 턴 수에 대해 **선형이 아니라 제곱에 가깝게** 늘어납니다. 3-8 의 트리밍이 필요한 진짜 이유가 이것입니다.

### tool_calls — 다음 스텝으로 가는 다리

모델이 도구를 부르기로 하면 `tool_calls` 가 채워집니다. `AIMessage` 를 손으로 만들어 구조만 확인해 봅시다.

```ts
import { AIMessage } from "langchain";

const ai = new AIMessage({
  content: "",
  tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
});

console.log(JSON.stringify(ai.tool_calls));
console.log(JSON.stringify(ai.contentBlocks));
```

**출력** (구조가 결정적입니다)
```json
[{"name":"get_weather","args":{"city":"서울"},"id":"call_1","type":"tool_call"}]
[{"type":"text","text":""},{"type":"tool_call","id":"call_1","name":"get_weather","args":{"city":"서울"}}]
```

주목할 점: **도구 호출은 `tool_calls` 필드에서도, `contentBlocks` 안의 `tool_call` 블록으로도 보입니다.** 같은 정보의 두 가지 뷰입니다. 실무에서는 `tool_calls` 를 쓰는 게 짧고 명확합니다.

그리고 **`args` 는 이미 파싱된 객체입니다** — 문자열이 아닙니다. OpenAI raw API 를 써 본 사람은 `JSON.parse(arguments)` 하던 습관이 있는데, LangChain 이 대신 해줍니다.

```ts
ai.tool_calls[0].args.city         // ✅ "서울" — 그냥 객체다
JSON.parse(ai.tool_calls[0].args)  // ❌ args 는 이미 객체다
```

---

## 3-7. ToolMessage 와 tool_call_id — 도구 결과를 돌려주는 계약

모델이 `tool_calls` 로 "`get_weather({city:"서울"})` 좀 불러줘, 이 호출의 ID 는 `call_1` 이야" 라고 요청하면, 여러분은 실제로 함수를 실행하고 **그 결과를 `ToolMessage` 로 되돌려줘야** 합니다. 이때 **어느 호출에 대한 답인지**를 `tool_call_id` 로 밝히는 것이 계약입니다.

```ts
import { ToolMessage } from "langchain";

// 두 가지 생성 방식 — 동등하다
const t1 = new ToolMessage({ content: "맑음, 27도", tool_call_id: "call_1", name: "get_weather" });
const t2 = new ToolMessage("맑음, 27도", "call_1", "get_weather");   // (content, tool_call_id, name)

console.log(t1.tool_call_id, t1.name, t1.type);
```

**출력** (구조가 결정적입니다)
```
call_1 get_weather tool
```

전체 흐름은 이렇게 생겼습니다. **AIMessage 의 `tool_calls[].id` 와 ToolMessage 의 `tool_call_id` 가 같은 값**이라는 게 핵심입니다.

```ts
const messages = [
  { role: "user", content: "서울 날씨?" },
  new AIMessage({
    content: "",
    tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
    //                                                          ^^^^^^^^^^
  }),
  new ToolMessage({ content: "맑음, 27도", tool_call_id: "call_1" }),
  //                                        ^^^^^^^^^^^^^^^^^^^^^^ 반드시 위와 같은 값
];
const final = await model.invoke(messages);
console.log(final.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
서울은 현재 맑고 기온은 27도입니다.
```

`ToolMessage` 의 나머지 필드도 정리합니다.

| 필드 | 필수 | 내용 |
|---|---|---|
| `content` | ✅ | 도구 실행 결과. **모델이 읽는 값** — 문자열이어야 함 |
| `tool_call_id` | ✅ | 대응하는 `AIMessage.tool_calls[].id` |
| `name` | | 도구 이름 (가독성/디버깅용) |
| `status` | | `"success"` \| `"error"` — 도구가 실패했음을 알림 |
| `artifact` | | **모델에 안 보내는** 부가 데이터. 내 코드만 씀 |

> 💡 **실무 팁 — `artifact` 의 쓸모**: 도구가 1만 행짜리 결과를 냈다고 `content` 에 다 넣으면 컨텍스트가 터지고 비용이 폭발합니다. 이럴 때 `content` 엔 모델이 판단할 만큼만 요약해 넣고, 원본은 `artifact` 에 둡니다. `artifact` 는 **provider 로 전송되지 않으므로** 토큰을 전혀 쓰지 않습니다.
> ```ts
> new ToolMessage({
>   content: "3건 조회됨: 주문 #1, #2, #3",   // ← 모델이 보는 것 (토큰 소모)
>   artifact: { rows: [...10000개...] },      // ← 내 코드만 보는 것 (토큰 0)
>   tool_call_id: "call_1",
> });
> ```
> 도구가 실패했을 때도 **예외를 던져 루프를 죽이는 대신** `status: "error"` 와 에러 메시지를 `content` 에 담아 돌려주는 게 보통 낫습니다. 모델이 그걸 읽고 인자를 고쳐 재시도할 수 있기 때문입니다.

이제 이 스텝에서 가장 비싼 함정 두 개입니다. 둘 다 **여러분 코드에는 에러가 없고, provider 가 뒤늦게 거절**합니다.

> ⚠️ **함정 1 — `tool_call_id` 불일치는 provider 400 이다**: `ToolMessage.tool_call_id` 가 앞선 `AIMessage.tool_calls[].id` 중 **어느 것과도 일치하지 않으면** provider 가 요청을 거절합니다(OpenAI·Anthropic 모두 HTTP 400 계열).
> ```ts
> new AIMessage({ content: "", tool_calls: [{ name: "get_weather", args: {}, id: "call_1", type: "tool_call" }] }),
> new ToolMessage({ content: "맑음", tool_call_id: "call_2" }),   // 💥 오타 하나
> ```
> **LangChain 은 이걸 검사하지 않습니다.** 배열을 만들 때도, `invoke` 를 호출할 때도 조용합니다. 에러는 네트워크 왕복 뒤에 provider 에서 옵니다. 그래서 스택 트레이스가 `ToolMessage` 를 만든 곳이 아니라 `invoke` 를 가리켜 원인을 찾기가 괴롭습니다.
> **방어법**: ID 를 손으로 적지 말고 **항상 `tool_calls` 에서 꺼내 쓰세요.**
> ```ts
> // ❌ 문자열 리터럴을 손으로 — 오타·복붙 사고의 근원
> new ToolMessage({ content: result, tool_call_id: "call_1" });
> // ✅ 원본에서 그대로 전달 — 불일치가 원천적으로 불가능
> for (const call of ai.tool_calls) {
>   const result = await runTool(call.name, call.args);
>   messages.push(new ToolMessage({ content: result, tool_call_id: call.id, name: call.name }));
> }
> ```

> ⚠️ **함정 2 — 도구 호출을 하나라도 빼먹으면 대화가 깨진다**: `AIMessage` 가 도구를 **3개** 호출했으면 `ToolMessage` **3개**가 전부 따라붙어야 합니다. 하나라도 빠진 채 다음 턴을 보내면 provider 가 거절합니다. "1개는 실패했으니 그건 건너뛰자"가 통하지 않습니다 — **실패한 것도 `status: "error"` 로 반드시 돌려줘야** 합니다.
> ```ts
> // ❌ 실패한 호출을 조용히 건너뛴다 → 다음 invoke 에서 400
> for (const call of ai.tool_calls) {
>   try {
>     messages.push(new ToolMessage({ content: await runTool(call), tool_call_id: call.id }));
>   } catch { continue; }   // 💥 이 호출에 대한 응답이 영영 없다
> }
> // ✅ 실패해도 반드시 답한다
> for (const call of ai.tool_calls) {
>   try {
>     messages.push(new ToolMessage({ content: await runTool(call), tool_call_id: call.id }));
>   } catch (e) {
>     messages.push(new ToolMessage({
>       content: `에러: ${e.message}`, status: "error", tool_call_id: call.id,
>     }));
>   }
> }
> ```
> 규칙은 한 문장입니다: **`tool_calls` 를 가진 `AIMessage` 뒤에는, 그 호출 개수만큼의 `ToolMessage` 가 빠짐없이 온다.** Step 07 에서 이 루프를 직접 구현하고, Step 08 의 `createAgent` 는 이걸 대신 해줍니다.

> ⚠️ **함정 3 — SystemMessage 의 위치**: `SystemMessage` 는 **배열 맨 앞에 한 개**가 정석입니다. 중간에 끼워 넣거나 여러 개를 두면 **provider 마다 다르게 처리됩니다.** Anthropic 은 시스템 프롬프트가 메시지 배열과 분리된 별도 파라미터라서 중간의 `SystemMessage` 를 앞으로 끌어올리거나 거절하고, OpenAI 는 배열 중간의 `system` 롤을 그냥 받아들이지만 뒤쪽 지시가 앞쪽을 덮어쓰는 식으로 동작이 미묘해집니다.
> 즉 **같은 코드가 provider 를 바꾸면 다르게 동작합니다.** 에러가 안 나니 눈치채기도 어렵습니다.
> 대화 중간에 규칙을 주입하고 싶다면 `SystemMessage` 를 끼우지 말고 **맨 앞 `SystemMessage` 를 갱신**하거나, `HumanMessage` 로 전달하세요. 이 "모델 호출 직전에 시스템 프롬프트를 동적으로 조립하는" 패턴이 바로 미들웨어(Step 11~12)의 주요 용도입니다.

---

## 3-8. 메시지 트리밍 — 컨텍스트 관리 맛보기

3-1 에서 봤듯 대화는 계속 쌓이기만 합니다. 그대로 두면 두 가지가 터집니다.

1. **컨텍스트 윈도우 초과** → 요청 자체가 실패
2. **비용** → 매 턴 전체 대화가 입력 토큰으로 과금

그래서 오래된 메시지를 잘라내야 합니다. `trimMessages` 가 그 일을 합니다.

```ts
import { trimMessages, SystemMessage, HumanMessage, AIMessage } from "langchain";

const messages = [
  new SystemMessage("너는 간결한 도우미다"),
  new HumanMessage("1번 질문"), new AIMessage("1번 답변"),
  new HumanMessage("2번 질문"), new AIMessage("2번 답변"),
  new HumanMessage("3번 질문"), new AIMessage("3번 답변"),
];

// 설명을 위해 "메시지 1개 = 1토큰" 인 가짜 카운터를 쓴다
const counter = (ms: BaseMessage[]) => ms.length;

const trimmed = await trimMessages(messages, {
  maxTokens: 3,
  tokenCounter: counter,
  strategy: "last",
});
console.log(trimmed.map((m) => `${m.type}:${m.text}`));
```

**출력** (구조가 결정적입니다)
```
[ 'ai:2번 답변', 'human:3번 질문', 'ai:3번 답변' ]
```

최근 3개만 남았습니다. 그런데 **문제가 두 개** 보입니다. (1) **시스템 프롬프트가 사라졌습니다.** (2) 배열이 **`ai` 로 시작합니다** — "AI 가 먼저 말한" 이상한 대화가 됐습니다.

옵션으로 고칩니다.

```ts
const better = await trimMessages(messages, {
  maxTokens: 5,
  tokenCounter: counter,
  strategy: "last",
  includeSystem: true,   // 맨 앞 SystemMessage 는 항상 살린다
  startOn: "human",      // human 메시지부터 시작하도록 맞춘다
});
console.log(better.map((m) => `${m.type}:${m.text}`));
```

**출력** (구조가 결정적입니다)
```
[ 'system:너는 간결한 도우미다', 'human:2번 질문', 'ai:2번 답변', 'human:3번 질문', 'ai:3번 답변' ]
```

시스템 프롬프트가 살아남고, `human` 으로 시작하는 온전한 대화가 됐습니다.

### 옵션 정리

| 옵션 | 타입 | 의미 |
|---|---|---|
| `maxTokens` | `number` | **필수.** 남길 토큰 예산 |
| `tokenCounter` | `fn \| 모델` | **필수.** 토큰 세는 함수, 또는 모델 인스턴스를 그대로 |
| `strategy` | `"first" \| "last"` | `"last"`(기본, 최근 유지) / `"first"`(앞부분 유지) |
| `includeSystem` | `boolean` | index 0 의 `SystemMessage` 를 항상 유지 (기본 `false`) |
| `startOn` | 타입 \| 타입[] | 결과가 이 타입부터 시작하도록 |
| `endOn` | 타입 \| 타입[] | 결과가 이 타입에서 끝나도록 |
| `allowPartial` | `boolean` | 메시지 중간을 잘라서라도 예산에 맞춤 |

`trimMessages` 는 **호출 형태가 두 가지**입니다.

```ts
// (A) 즉시 실행 — Promise<BaseMessage[]>
const trimmed = await trimMessages(messages, { maxTokens: 100, tokenCounter: model });

// (B) 인자 1개 — Runnable 을 반환한다 (체인에 끼울 때)
const trimmer = trimMessages({ maxTokens: 100, tokenCounter: model });
const trimmed2 = await trimmer.invoke(messages);
```

> 💡 **실무 팁 — `tokenCounter` 에 모델을 그대로 넣으세요**: 위 예제의 `(ms) => ms.length` 는 설명용 장난감입니다. 실제로는 **모델 인스턴스**를 넘기면 그 모델의 실제 토크나이저로 셉니다.
> ```ts
> const trimmed = await trimMessages(messages, { maxTokens: 4000, tokenCounter: model, includeSystem: true, startOn: "human" });
> ```
> `maxTokens` 는 **모델 컨텍스트 윈도우의 절반 이하**로 잡는 게 안전합니다. 남은 공간에 이번 턴 질문, 도구 정의, 도구 결과, 그리고 **출력 토큰**까지 들어가야 하기 때문입니다. 컨텍스트를 꽉 채우면 모델이 답할 자리가 없어 응답이 잘립니다.

> ⚠️ **함정 (트리밍이 도구 호출 쌍을 갈라놓는다)**: 3-7 에서 "`AIMessage(tool_calls)` 뒤엔 `ToolMessage` 가 반드시 온다"고 했습니다. 트리밍은 이 쌍을 **아무렇지 않게 절단합니다.**
> ```ts
> const messages = [
>   new SystemMessage("너는 도우미다"),
>   new HumanMessage("서울 날씨?"),
>   new AIMessage({ content: "", tool_calls: [{ name: "w", args: {}, id: "call_1", type: "tool_call" }] }),
>   new ToolMessage({ content: "맑음", tool_call_id: "call_1" }),
>   new AIMessage("서울은 맑습니다"),
> ];
> const bad = await trimMessages(messages, { maxTokens: 2, tokenCounter: (ms) => ms.length, strategy: "last" });
> console.log(bad.map((m) => m.type));
> ```
> **출력** (구조가 결정적입니다)
> ```
> [ 'tool', 'ai' ]
> ```
> 배열이 **`ToolMessage` 로 시작합니다.** 짝이 되는 `AIMessage` 는 잘려 나갔고, 이제 `call_1` 을 요청한 적도 없는데 그 결과만 덩그러니 있는 상태입니다. 이걸 그대로 보내면 **provider 400** — 3-7 함정 1과 똑같은 증상이, 이번엔 내 코드 어디에도 `tool_call_id` 를 적은 적이 없는데 발생합니다.
> **방어법은 `startOn: "human"`** 입니다. 결과가 반드시 `human` 부터 시작하므로 고아 `ToolMessage` 가 원천 차단됩니다. **도구를 쓰는 에이전트에서 트리밍을 한다면 `startOn: "human"` 은 선택이 아니라 필수입니다.**

> 💡 **참고**: 트리밍은 오래된 정보를 **버립니다.** 버리는 대신 **요약해서 압축**하는 방법도 있습니다(`createSummarizationMiddleware`). Step 11(내장 미들웨어)과 Step 14(컨텍스트 엔지니어링)에서 다룹니다. 실전 에이전트는 대개 "최근 N턴은 원문 유지 + 그 이전은 요약" 을 조합합니다.

---

## 3-9. 종합 — 도구 호출 한 사이클을 손으로

배운 걸 전부 엮어, 모델 없이 **메시지 배열만으로** 도구 호출 한 사이클을 만들어 봅니다. 이 배열의 모양이 Step 07~08 에서 여러분이 만들 에이전트의 심장입니다.

```ts
import { SystemMessage, HumanMessage, AIMessage, ToolMessage } from "langchain";
import { isAIMessage } from "@langchain/core/messages";

// 실제 도구 (Step 06 에서 제대로 배웁니다)
const weatherDB: Record<string, string> = { 서울: "맑음, 27도", 부산: "흐림, 24도" };

const messages: BaseMessage[] = [
  new SystemMessage("너는 날씨 도우미다."),
  new HumanMessage("서울이랑 부산 날씨 알려줘"),
];

// 모델이 도구 2개를 "병렬로" 호출했다고 가정 (실제로는 model.invoke 결과)
const ai = new AIMessage({
  content: "",
  tool_calls: [
    { name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" },
    { name: "get_weather", args: { city: "부산" }, id: "call_2", type: "tool_call" },
  ],
});
messages.push(ai);

// 핵심: tool_calls 를 순회하며 "빠짐없이" ToolMessage 를 붙인다
const last = messages.at(-1)!;
if (isAIMessage(last) && last.tool_calls?.length) {
  for (const call of last.tool_calls) {
    try {
      const result = weatherDB[call.args.city as string];
      if (!result) throw new Error(`알 수 없는 도시: ${call.args.city}`);
      messages.push(new ToolMessage({ content: result, tool_call_id: call.id!, name: call.name }));
    } catch (e) {
      // 실패해도 반드시 답한다 — 안 그러면 다음 invoke 가 400
      messages.push(new ToolMessage({
        content: `에러: ${(e as Error).message}`, status: "error",
        tool_call_id: call.id!, name: call.name,
      }));
    }
  }
}

console.log(messages.map((m) => `${m.type.padEnd(6)} | ${m.text || "(도구호출)"}`).join("\n"));
```

**출력** (구조가 결정적입니다)
```
system | 너는 날씨 도우미다.
human  | 서울이랑 부산 날씨 알려줘
ai     | (도구호출)
tool   | 맑음, 27도
tool   | 흐림, 24도
```

`AIMessage` 1개 → `ToolMessage` 2개. **호출 개수와 응답 개수가 정확히 맞습니다.** 이 배열은 이제 `model.invoke(messages)` 로 그대로 보낼 수 있고, 모델은 두 결과를 종합해 최종 답을 냅니다.

---

## 정리

**메시지 타입**

| 타입 | role | `.type` | 만드는 주체 | 필수 필드 |
|---|---|---|---|---|
| `SystemMessage` | `system` | `system` | 개발자 | `content` |
| `HumanMessage` | `user` | `human` | 사용자 | `content` |
| `AIMessage` | `assistant` | `ai` | 모델 | — |
| `ToolMessage` | `tool` | `tool` | 개발자 | `content`, **`tool_call_id`** |

**content 접근자**

| 접근자 | 타입 | 언제 |
|---|---|---|
| `.content` | `string \| ContentBlock[]` | 원본이 꼭 필요할 때만 |
| `.text` | `string` | **텍스트 읽기 — 기본값** |
| `.contentBlocks` | `ContentBlock[]` | 이미지/추론/도구호출 구조 볼 때 |

**표준 콘텐츠 블록** (`@langchain/core@1.x`)

```ts
{ type: "text", text: "..." }
{ type: "reasoning", reasoning: "..." }
{ type: "tool_call", name: "...", args: {...}, id: "..." }
{ type: "image", url: "..." } | { type: "image", data: "<b64>", mimeType: "image/png" } | { type: "image", fileId: "..." }
{ type: "file", data: "<b64>", mimeType: "application/pdf" }
```

**핵심 함정 3가지**

1. **`tool_call_id` 계약**: `ToolMessage.tool_call_id` 가 `AIMessage.tool_calls[].id` 와 안 맞거나, 호출 개수만큼 `ToolMessage` 를 안 붙이면 **provider 400**. LangChain 은 검사해주지 않는다. **ID 는 손으로 적지 말고 `call.id` 를 그대로 전달**하고, 실패한 도구도 `status: "error"` 로 반드시 답하라.
2. **`.content` 를 문자열로 가정**: `content` 는 `string | ContentBlock[]` 이다. 이미지나 reasoning 이 끼는 순간 `TypeError` 또는 `[object Object]`. **텍스트는 `.text`, 구조는 `.contentBlocks`.**
3. **트리밍이 도구 쌍을 자른다**: `trimMessages` 는 `AIMessage(tool_calls)` 와 `ToolMessage` 를 갈라놓아 고아 `ToolMessage` 를 만든다 → 400. **`startOn: "human"` 은 도구 쓰는 에이전트에선 필수**, `includeSystem: true` 도 대개 필요하다.

**추가 주의**: `.type` 은 `human`/`ai` 이지 `user`/`assistant` 가 아니다 · `isAIMessage` 는 `"@langchain/core/messages"` 에서만 온다 · 이미지 블록은 `source_type` 이 아니라 `url`/`data`+`mimeType`(camelCase) · `SystemMessage` 는 맨 앞 1개.

---

## 연습문제

1. `SystemMessage`("너는 항상 한 문장으로만 답한다") + `HumanMessage`("LangChain이 뭐야?") 배열로 모델을 호출하고, `.text` 와 `.type` 을 출력하세요. 그다음 같은 대화를 **객체 리터럴** 표기법으로 다시 작성해 동일하게 동작함을 확인하세요.
2. 3-1 처럼 **2턴 대화**를 만드세요. 1턴에서 좋아하는 음식을 말하고, 2턴에서 "내가 좋아하는 음식이 뭐라고?"를 물어 모델이 맞히는지 보세요. 그런 다음 **1턴을 배열에서 빼고** 다시 호출해 답이 어떻게 달라지는지 비교하세요.
3. `content` 가 문자열인 메시지와 배열(`text` 블록 2개 + `reasoning` 블록 1개)인 메시지를 각각 만들고, 둘의 `.content`, `.text`, `.contentBlocks` 를 모두 출력해 표로 비교하세요. **`.text` 에 reasoning 이 포함되나요?**
4. 임의의 `BaseMessage` 를 받아 `{ type, text, blockTypes, hasToolCalls }` 를 반환하는 `describe(msg)` 함수를 작성하세요. `content` 가 문자열이든 배열이든 **터지지 않아야** 합니다. (힌트: `.text` 와 `.contentBlocks` 만 쓰면 분기가 필요 없습니다)
5. 3-9 의 예제를 고쳐, `ToolMessage` 의 `tool_call_id` 를 `"call_1"` → `"call_999"` 로 **일부러 틀리게** 만든 뒤 `model.invoke` 를 호출하세요. 어떤 에러가 나는지 **에러 메시지 전문을 주석으로 기록**하세요. (API 키가 없다면, 대신 "왜 LangChain 단계에서는 에러가 안 나는가"를 설명하는 주석을 쓰세요)
6. `AIMessage` 가 도구 3개(`call_1`,`call_2`,`call_3`)를 호출했고 그중 `call_2` 가 실패하는 상황을 만드세요. **세 개 모두** `ToolMessage` 로 답하되 `call_2` 는 `status: "error"` 로 처리하고, 최종 배열의 `tool` 메시지 개수가 3인지 검증하세요.
7. `SystemMessage` 1개 + `human`/`ai` 5쌍(총 11개) 대화를 만들고, `tokenCounter: (ms) => ms.length` 로 `maxTokens: 5` 트리밍을 (a) 옵션 없이 (b) `includeSystem: true, startOn: "human"` 으로 각각 실행해 결과를 비교하세요. 두 결과의 **첫 메시지 타입**이 어떻게 다른가요?
8. 도구 호출 쌍이 포함된 대화에 트리밍을 걸어 **고아 `ToolMessage` 를 일부러 만들어** 보세요(3-8 함정 재현). 그다음 `startOn: "human"` 을 추가해 문제가 사라지는 것을 확인하고, 결과 배열의 첫 메시지 타입을 각각 출력하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 04 — 프롬프트 설계와 템플릿](../step-04-prompts/)

메시지를 손으로 조립하는 법을 배웠습니다. 다음 스텝에서는 이 메시지들을 **재사용 가능한 템플릿**으로 만들어, 변수만 갈아끼우며 찍어내는 법을 다룹니다. 그리고 `SystemMessage` 에 무엇을 어떻게 써야 모델이 말을 듣는지도 여기서 본격적으로 파고듭니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(3-1 ~ 3-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

```bash
npx tsx docs/reference/langchain/step-03-messages/practice.ts
```

세 파일 모두 **API 키 없이도 실행됩니다.** 메시지는 순수한 자료구조라서 대부분의 예제가 네트워크를 타지 않기 때문입니다. `ANTHROPIC_API_KEY` 가 있으면 모델 호출 절까지 함께 돌고, 없으면 그 절만 건너뛰고 나머지 구조 예제는 전부 정상 실행됩니다. OpenAI 를 쓰려면 모델 문자열을 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하면 됩니다 — 메시지 코드는 **한 줄도 바뀌지 않습니다.** 그게 LangChain 메시지 추상화의 요점입니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[3-1] ~ [3-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[3-2]` 는 `.type` 이 `"human"`/`"ai"` 를 반환하는 것을 출력해, `role` 문자열(`"user"`/`"assistant"`)과 **다르다**는 것을 눈으로 박아 넣습니다. 필터링 버그의 예방주사입니다.
- `[3-4]` 의 `try/catch` 블록이 이 파일의 핵심입니다. `m2.content.toUpperCase()` 를 **일부러 호출해 `TypeError` 를 잡아 출력**합니다. 에러를 직접 보는 것과 "배열일 수도 있대"를 읽는 것은 기억에 남는 정도가 다릅니다.
- `[3-6]` 과 `[3-7]` 은 **API 키 없이도** 돌도록 `AIMessage` 를 손으로 만들어 구조를 찍습니다. `usage_metadata` 는 손으로 만든 메시지에선 `undefined` 인 게 정상입니다 — provider 만 채워주는 필드라는 걸 보여주려는 의도입니다.
- `[3-8]` 은 트리밍을 **옵션 없이 / 옵션 붙여서** 연달아 돌려 결과를 나란히 출력합니다. 특히 도구 호출 대화에서 `['tool','ai']` 가 나오는 고아 `ToolMessage` 재현이 백미입니다. 여기 출력은 전부 결정적이라 본문 값과 정확히 일치해야 합니다.
- 맨 아래 `[3-1]`/`[3-5]` 의 모델 호출 부분은 `if (process.env.ANTHROPIC_API_KEY)` 로 감싸 두었습니다. 키가 없으면 안내 문구만 찍고 넘어갑니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래가 비어 있으니, 거기에 직접 코드를 써 넣고 파일을 통째로 실행해 검증하면 됩니다.

- `[문제 4]` 의 `describe(msg)` 는 시그니처와 반환 타입만 주어져 있습니다. **`.content` 로 분기하려 들면 코드가 길어집니다** — `.text` 와 `.contentBlocks` 만 쓰면 분기 없이 3줄로 끝난다는 걸 깨닫는 게 이 문제의 목적입니다.
- `[문제 5]` 는 API 키가 없어도 풀 수 있게 두 갈래로 열어뒀습니다. 키가 있으면 실제 400 을 받아 메시지를 기록하고, 없으면 "왜 LangChain 단계에서는 조용한가"를 주석으로 설명하면 됩니다. **후자가 사실 더 중요한 학습 포인트**입니다.
- `[문제 7]` 과 `[문제 8]` 에는 대화 배열이 **이미 만들어져 있습니다.** 여러분이 할 일은 `trimMessages` 옵션을 채우고 결과를 비교하는 것뿐입니다. 배열 만드느라 시간 쓰지 말라는 뜻입니다.
- 파일을 그대로 실행하면 아무것도 출력되지 않습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 기대 출력까지 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 3]` 의 핵심은 **`.text` 에 reasoning 이 안 들어간다**는 것입니다. `.contentBlocks` 에는 3개 블록이 다 보이는데 `.text` 는 텍스트 블록 2개만 이어붙인 결과를 냅니다. 로그에서 reasoning 이 조용히 사라지는 이유가 이것입니다.
- `[정답 4]` 는 `describe` 를 분기 없이 구현합니다. `.contentBlocks.map(b => b.type)` 하나로 blockTypes 가 나오고, `content` 가 문자열이든 배열이든 **똑같이 동작**합니다. 문제 지문의 "터지지 않아야 한다"는 조건이 사실 "`.content` 를 쓰지 마라"의 다른 표현이었음을 알게 됩니다.
- `[정답 6]` 은 `try/catch` 안의 `continue` 를 **`status: "error"` 로 답하기**로 바꾸는 게 전부입니다. 코드는 두 줄 차이인데 하나는 400 이 나고 하나는 정상입니다. 실무 에이전트 버그의 상당수가 이 두 줄 사이에 있습니다.
- `[정답 8]` 이 이 파일의 하이라이트입니다. 옵션 없는 트리밍은 첫 메시지 타입이 **`tool`**(고아), `startOn: "human"` 을 붙이면 **`human`** 입니다. 주목할 점은 **내 코드 어디에도 `tool_call_id` 를 손으로 적은 곳이 없는데도** 3-7 함정 1과 똑같은 400 이 난다는 것입니다. 트리밍은 "메시지를 몇 개 지우는" 단순 작업이 아니라 **대화의 무결성을 깰 수 있는 위험한 연산**이라는 게 이 스텝의 마지막 교훈입니다.

```ts file="./solution.ts"
```
