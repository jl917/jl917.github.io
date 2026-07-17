# Step 05 — 구조화된 출력 (zod)

> **학습 목표**
> - 자유 텍스트 파싱이 왜 실패하는지 이해하고 **구조화 출력으로 대체**한다
> - 에이전트 개발에 필요한 만큼의 **zod 스키마**(object, enum, array, optional, nullable, describe)를 쓴다
> - 모델 레벨 **`.withStructuredOutput(schema)`** 과 에이전트 레벨 **`responseFormat`** 을 구분해서 쓴다
> - **`toolStrategy`** 와 **`providerStrategy`** 의 차이를 알고 상황에 맞게 고른다
> - **`.describe()` 가 곧 프롬프트**임을 이해하고 필드 설명으로 정확도를 끌어올린다
> - 검증 실패 시 **`handleError`** 로 재시도를 제어하고, 부분 실패를 방어한다
>
> **선행 스텝**: [Step 04 — 프롬프트 설계와 템플릿](../step-04-prompts/)
> **예상 소요**: 80분

[Step 04](../step-04-prompts/) 까지 우리는 모델에게 **말을 거는 법**을 배웠습니다. 그런데 모델의 답도 결국 "말"입니다. 사람이 읽을 때는 좋지만, 프로그램이 쓰려면 문제가 됩니다. `"별점은 5점 만점에 4점 정도 되겠네요"` 라는 문장에서 `4` 를 꺼내려면 정규식을 써야 하고, 그 정규식은 모델이 다음번에 `"★★★★☆"` 라고 답하는 순간 깨집니다.

구조화된 출력(structured output)은 이 문제를 정면으로 해결합니다. 모델에게 **스키마를 주고 그 모양의 객체를 받아내는 것**입니다. 파싱이 사라지고, TypeScript 타입이 살아나고, 검증이 자동으로 붙습니다. 그리고 이건 단순한 편의 기능이 아닙니다 — 에이전트가 **다음에 무엇을 할지 결정**하게 하려면(라우팅), 그 결정은 반드시 기계가 읽을 수 있는 값이어야 합니다. 구조화 출력은 [Step 06 — 도구 정의](../step-06-tools/) 와 [Step 08 — createAgent](../step-08-create-agent/) 로 가는 다리입니다.

**검증 버전**: `langchain@1.5.3`, `@langchain/core@1.2.3`, `@langchain/anthropic@1.5.1`, `zod@4.4.3`, Node.js 22

---

## 5-1. 왜 구조화 출력인가 — 자유 텍스트 파싱의 지옥

리뷰에서 별점과 감정을 뽑는 일을 생각해 봅시다. 구조화 출력 없이 하면 이렇습니다.

```ts
import { initChatModel } from "langchain";

const model = await initChatModel("anthropic:claude-sonnet-4-6");

const raw = await model.invoke(
  "다음 리뷰의 별점(1~5)과 감정을 알려줘: '배송은 빨랐는데 가격이 너무 비싸요. 그래도 품질은 만족.'",
);
console.log(raw.content);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
이 리뷰는 별점 3~4점 정도로 보입니다. 배송 속도와 품질에 대해서는 긍정적이지만,
가격에 대한 불만이 있어 전반적으로는 중립에서 약간 긍정적인(mixed positive) 감정입니다.
```

사람이 읽기엔 훌륭합니다. 그런데 이 문자열에서 별점 숫자를 꺼내려면?

```ts
const text = String(raw.content);
const guessed = text.match(/([1-5])\s*(?:점|\/\s*5|stars?)/)?.[1];
// "3~4점" → "3" 이 잡힘. 근데 이게 맞는 답인가?
```

이 정규식은 다음 모든 경우에 조용히 틀립니다.

| 모델이 답한 형태 | 정규식 결과 | 실제 |
|---|---|---|
| `"별점 4점"` | `4` | ✅ |
| `"3~4점 정도"` | `3` | ❓ (범위인데 앞만 집음) |
| `"다섯 점 만점에 네 점"` | 실패 | 4 |
| `"★★★★☆"` | 실패 | 4 |
| `"4 out of 5 stars"` | `4` | ✅ (우연히) |
| `"5점 만점 기준으로 4점"` | `5` | ❌ (5점 만점의 5를 집음) |

마지막 줄이 이 표의 핵심입니다. **에러가 나지 않습니다.** `5` 라는 그럴듯한 숫자가 나오고, 그게 DB에 저장되고, 대시보드에 그려집니다. 아무도 모릅니다.

구조화 출력을 쓰면 이 표 전체가 사라집니다.

```ts
import * as z from "zod";

const Review = z.object({
  rating: z.number().min(1).max(5).describe("별점 1~5"),
  sentiment: z.enum(["positive", "negative", "neutral"]).describe("전체 감정"),
});

const structured = await model.withStructuredOutput(Review).invoke(
  "다음 리뷰를 분석해: '배송은 빨랐는데 가격이 너무 비싸요. 그래도 품질은 만족.'",
);
console.log(structured);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
{ rating: 4, sentiment: 'neutral' }
```

`structured.rating` 은 `number` 입니다 — 추측이 아니라 TypeScript 가 아는 사실입니다. `structured.sentiment` 는 `"positive" | "negative" | "neutral"` 유니온 타입이라 오타가 컴파일 에러로 잡힙니다. 그리고 모델이 `rating: 10` 을 뱉으면 `.max(5)` 가 그걸 **에러로 만듭니다** — 조용히 통과시키지 않습니다.

> 💡 **실무 팁**: 구조화 출력의 진짜 가치는 "편해진다"가 아니라 **"틀리면 시끄럽게 틀린다"** 입니다. 자유 텍스트 파싱의 실패는 조용합니다. 스키마 검증의 실패는 예외를 던지거나 재시도를 유발합니다. 프로덕션에서 이 차이는 결정적입니다.

---

## 5-2. zod 스키마 기초 — 에이전트 개발자에게 필요한 만큼

zod 는 방대하지만, 에이전트 스키마에서 실제로 쓰는 건 놀랄 만큼 적습니다. 아래가 사실상 전부입니다.

```ts
import * as z from "zod";

const Ticket = z.object({
  // string / number — 가장 기본
  title: z.string().describe("티켓 제목. 한 문장, 40자 이내."),
  priority: z.number().min(1).max(5).describe("우선순위. 1=가장 급함, 5=여유."),

  // enum — 모델의 선택지를 강제한다. 분류 작업의 핵심.
  category: z.enum(["bug", "feature", "question", "billing"]).describe("티켓 분류. 넷 중 하나만."),

  // array — 개수 제한도 걸 수 있다
  tags: z.array(z.string()).max(3).describe("검색용 태그. 소문자, 1~2단어."),

  // optional — "키가 없어도 된다"
  assignee: z.string().optional().describe("담당자 이름. 글에 없으면 생략."),

  // nullable — "키는 있는데 값이 null 이어도 된다"
  dueDate: z.string().nullable().describe("마감일 YYYY-MM-DD. 글에 없으면 반드시 null."),

  // 중첩 객체 — 얕게 유지할 것
  reporter: z.object({
    name: z.string().describe("신고자 이름"),
    email: z.string().describe("신고자 이메일. 없으면 빈 문자열."),
  }).describe("신고자 정보"),
});

type Ticket = z.infer<typeof Ticket>;  // 스키마가 곧 타입
```

zod 스키마는 **런타임 검증기이자 컴파일타임 타입**입니다. `z.infer` 로 타입을 뽑아내면 스키마와 타입이 영원히 동기화됩니다 — 따로 `interface` 를 쓰면 둘이 어긋나는 날이 옵니다.

### 모델 없이 먼저 검증해보기

스키마를 만들면 **모델을 부르기 전에** 로컬에서 먼저 돌려보세요. API 호출은 느리고 돈이 듭니다.

```ts
const result = Ticket.safeParse({
  title: "로그인 버튼이 안 눌림",
  priority: 9,               // ← max(5) 위반
  category: "urgent",        // ← enum 에 없음
  tags: ["login"],
  dueDate: null,
  reporter: { name: "김민수", email: "kim@example.com" },
});

console.log(result.success ? "OK" : result.error.issues.map(i => `${i.path.join(".")}: ${i.message}`));
```

**결과** (구조가 결정적입니다)
```
[
  'priority: Too big: expected number to be <=5',
  'category: Invalid option: expected one of "bug"|"feature"|"question"|"billing"'
]
```

이 에러 메시지가 그대로 모델에게 되돌아가 재시도를 유발합니다(5-7 참고). 그래서 **에러 메시지가 읽기 쉬울수록 모델이 잘 고칩니다.**

### optional 과 nullable — 축이 다르다

이 둘을 헷갈리는 게 이 스텝에서 가장 비싼 실수입니다. **둘은 대체재가 아닙니다.**

```ts
const Opt = z.object({ v: z.string().optional() });
const Nul = z.object({ v: z.string().nullable() });

Opt.safeParse({}).success;         // true  — 키 생략 OK
Opt.safeParse({ v: null }).success; // false — null 은 거부!
Nul.safeParse({}).success;          // false — 키 생략 거부!
Nul.safeParse({ v: null }).success; // true  — null OK
```

**결과** (zod 4.4.3 실측, 결정적입니다)
```
opt missing: true   opt null: false
nul missing: false  nul null: true
```

- `.optional()` 은 **키** 축을 다룹니다 — "이 키는 없어도 된다" (`undefined` 허용, `null` 거부)
- `.nullable()` 은 **값** 축을 다룹니다 — "이 값은 null 일 수 있다" (키는 여전히 **필수**)
- 둘 다 허용하려면 `.nullish()` (= `.optional().nullable()`)

> ⚠️ **함정 — `.optional()` vs `.nullable()` 은 provider 마다 다르게 처리된다**
>
> 로컬 zod 동작은 위와 같습니다. 문제는 이 스키마가 **JSON Schema 로 변환되어 모델에게 전달**될 때입니다. 실제로 변환해 보면:
>
> ```json
> {
>   "properties": {
>     "a": { "type": "string", "description": "필수" },
>     "b": { "type": "string", "description": "optional" },
>     "c": { "anyOf": [{"type":"string"}, {"type":"null"}], "description": "nullable" }
>   },
>   "required": ["a", "c"]
> }
> ```
>
> `.optional()` 필드 `b` 는 **`required` 배열에서 빠집니다.** `.nullable()` 필드 `c` 는 **`required` 에 남고** 타입만 `anyOf` 로 열립니다.
>
> 여기서 provider 별로 갈립니다. **OpenAI 의 strict 모드(`method: "jsonSchema"`, `strict: true`)는 모든 속성이 `required` 에 있을 것을 요구합니다.** 그래서 `.optional()` 필드가 있으면 스키마가 거부되거나, LangChain/provider 가 이를 `"type": ["string", "null"]` + required 로 **자동 변환**해 버립니다. 실제로 LangChain 공식 문서의 `toolStrategy` 예제를 보면, zod 쪽 `rating: z.number().min(1).max(5).optional()` 이 JSON Schema 쪽에서는 `"rating": {"type": ["integer", "null"]}` 로 적혀 있습니다 — optional 이 nullable 로 바뀐 겁니다.
>
> 결과가 무엇이냐: 여러분은 `.optional()` 을 써서 "없으면 키를 빼줘"를 의도했는데, **Anthropic 에서는 키가 빠지고 OpenAI strict 에서는 `null` 이 들어옵니다.** 그러면 `if (result.assignee === undefined)` 같은 코드가 한쪽 provider 에서만 조용히 안 먹습니다.
>
> **방어법**: 에이전트 스키마에서는 **`.optional()` 대신 `.nullable()` 을 기본으로 쓰고, `.describe()` 에 "없으면 null" 을 명시하세요.** 모델은 키를 생략하는 것보다 `null` 을 채우는 걸 훨씬 안정적으로 해내고, `null` 은 모든 provider 에서 똑같이 `null` 입니다. 그리고 소비하는 쪽은 `== null` (느슨한 비교)로 두 경우를 한 번에 처리하세요.

> ⚠️ **함정 — 스키마가 깊게 중첩되면 정확도가 급락한다**
>
> 모델은 JSON 을 토큰 단위로 왼쪽에서 오른쪽으로 생성합니다. 중첩이 깊어질수록 중괄호 짝을 맞추는 데 주의력을 쓰고, "`tier` 가 `profile` 안이었나 `customer` 바로 아래였나" 를 헷갈립니다. 아래 두 스키마는 **같은 정보**를 담지만 정확도가 다릅니다.
>
> ```ts
> // (A) 4단계 중첩 — 필드를 통째로 빠뜨리거나 엉뚱한 레벨에 넣는 일이 생긴다
> z.object({ order: z.object({ customer: z.object({ profile: z.object({ name, tier }) }),
>                              payment: z.object({ method, amount }) }) });
>
> // (B) 평평 — 훨씬 안정적
> z.object({ customerName, customerTier, paymentMethod, paymentAmount });
> ```
>
> **실무 규칙: 중첩은 2단계까지.** 그보다 깊어지면 평평하게 펴서 접두사로 구분하거나(`customerName`, `paymentAmount`) 호출을 두 번으로 쪼개세요. 정말 중첩된 모양이 필요하면 **평평하게 받아서 우리 코드에서 재조립**하면 됩니다 — 그 재조립은 공짜이고 100% 정확합니다. 모델에게 시킬 이유가 없습니다.

---

## 5-3. `.withStructuredOutput(schema)` — 모델 레벨

가장 단순한 구조화 출력은 **모델 하나**에 스키마를 붙이는 것입니다. 에이전트도, 도구도, 루프도 없습니다. 그냥 "텍스트 넣으면 객체 나오는 함수"가 됩니다.

```ts
import { initChatModel } from "langchain";
import * as z from "zod";

const model = await initChatModel("anthropic:claude-sonnet-4-6");

const Movie = z.object({
  title: z.string().describe("영화 제목"),
  year: z.number().describe("개봉 연도"),
  director: z.string().describe("감독 이름"),
  rating: z.number().describe("10점 만점 평점"),
});

const modelWithStructure = model.withStructuredOutput(Movie);
const response = await modelWithStructure.invoke("영화 인셉션의 정보를 알려줘");
console.log(response);
```

**출력 예시** (모델 응답이므로 매번 다릅니다 — 특히 `rating` 은 모델의 기억에 의존합니다)
```
{ title: '인셉션', year: 2010, director: '크리스토퍼 놀란', rating: 8.8 }
```

`withStructuredOutput` 은 **새 모델 객체를 반환**합니다. 원본 `model` 은 그대로입니다. 그래서 같은 모델에서 스키마별로 여러 개를 파생시킬 수 있습니다.

### 옵션 세 가지

```ts
// includeRaw: 파싱된 것과 원본 메시지를 함께 받는다
import type { AIMessage } from "@langchain/core/messages";

const withRaw = model.withStructuredOutput(Movie, { includeRaw: true });
const both = await withRaw.invoke("영화 매트릭스의 정보를 알려줘");
console.log(both.parsed);  // { title: '매트릭스', ... }

// raw 는 BaseMessage 로 타입이 잡혀 있다 — usage_metadata 를 쓰려면 좁혀야 한다
const raw = both.raw as AIMessage;
console.log(raw.usage_metadata);  // { input_tokens, output_tokens, total_tokens }

// method: 구현 방식을 고른다
const viaJsonSchema = model.withStructuredOutput(Movie, { method: "jsonSchema" });
```

| 옵션 | 값 | 용도 |
|---|---|---|
| `method` | `"jsonSchema"` \| `"functionCalling"` \| `"jsonMode"` | 구조화 구현 방식. provider 별 지원이 다름 |
| `includeRaw` | `boolean` | `true` 면 `{ parsed, raw }` 로 반환. 토큰 사용량·중단 사유가 필요할 때 |
| `strict` | `boolean` | 스키마를 엄격히 강제 (provider 의존) |

> 💡 **실무 팁 — `includeRaw: true` 를 언제 쓰나**: 기본값(`false`)이면 파싱된 객체만 오므로 **토큰을 얼마나 썼는지 알 수 없습니다.** 비용을 계측하거나, 응답이 `max_tokens` 로 잘렸는지(`finish_reason`) 확인해야 하는 프로덕션 경로에서는 `includeRaw: true` 로 두고 `raw.usage_metadata` 를 로깅하세요. 단 반환 타입이 `{ parsed, raw }` 로 바뀌므로 호출부 코드도 함께 바꿔야 합니다.

> ⚠️ **함정 — `includeRaw` 의 `raw` 는 `AIMessage` 가 아니라 `BaseMessage` 다**
>
> `@langchain/core@1.2.3` 의 타입 정의는 `{ raw: BaseMessage; parsed: RunOutput }` 입니다. 그런데 `usage_metadata`, `tool_calls`, `response_metadata` 같은 필드는 **`AIMessage` 에만** 있습니다. 그래서 `both.raw.usage_metadata` 를 그냥 쓰면 `tsc` 가 막습니다.
>
> ```
> error TS2339: Property 'usage_metadata' does not exist on type 'BaseMessage<...>'.
> ```
>
> 런타임에는 실제로 `AIMessage` 가 들어오므로 **동작은 합니다.** 타입만 넓게 잡혀 있는 것입니다. 그래서 `as AIMessage` 로 좁혀 쓰면 됩니다. 이런 게 위험한 이유는 반대 방향입니다 — JavaScript 로 쓰거나 `any` 로 받으면 이 경고가 **사라지고**, 나중에 `raw` 가 정말 다른 메시지 타입일 때 `undefined` 를 조용히 읽게 됩니다. **타입 에러를 `any` 로 덮지 말고 캐스팅으로 의도를 남기세요.**

> ⚠️ **함정 — `.withStructuredOutput()` 을 켜면 도구 호출과 충돌할 수 있다**
>
> `withStructuredOutput` 의 상당수 구현은 내부적으로 **"스키마 모양의 도구를 하나 만들어 그것만 부르도록 강제"** 하는 방식입니다(`method: "functionCalling"`). 그래서 `model.bindTools([...])` 로 도구를 붙인 모델에 `withStructuredOutput` 을 또 겹치면, 모델은 **내 도구 대신 스키마 도구만 부르거나** 그 반대가 됩니다 — 그리고 대개 **에러 없이** 그렇게 됩니다.
>
> 모델 레벨에서 "도구도 쓰고 구조화 답도 받기"를 직접 조립하려 하지 마세요. 그건 에이전트가 할 일입니다(5-4, 5-9). 모델 레벨 `withStructuredOutput` 은 **도구 없는 단발성 변환**(분류·추출)에만 쓰는 게 안전합니다.

---

## 5-4. `createAgent({ responseFormat })` → `result.structuredResponse`

에이전트는 도구를 부르고, 루프를 돌고, 여러 턴을 거칩니다. 그 **모든 게 끝난 뒤 최종 답만** 구조화하고 싶을 때 `responseFormat` 을 씁니다.

```ts
import { createAgent } from "langchain";
import * as z from "zod";

const ContactInfo = z.object({
  name: z.string().describe("사람 이름"),
  email: z.string().describe("이메일 주소"),
  phone: z.string().describe("전화번호"),
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  responseFormat: ContactInfo,     // ← 스키마를 그대로 넘기면 전략은 자동 선택
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "다음에서 연락처를 추출해: John Doe, john@example.com, (555) 123-4567" }],
});

console.log(result.structuredResponse);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
{ name: 'John Doe', email: 'john@example.com', phone: '(555) 123-4567' }
```

여기서 중요한 구조적 사실(이건 결정적입니다):

- 에이전트 결과는 **`messages` 와 `structuredResponse` 를 둘 다** 가집니다.
- `structuredResponse` 는 **검증을 통과한** 객체입니다. 검증에 실패했다면 애초에 여기 오지 않습니다.
- `messages` 는 여전히 전체 대화 기록입니다 — 도구를 썼다면 그 흔적도 다 남아 있습니다.

`responseFormat` 이 받는 스키마 형태는 세 가지입니다.

| 형태 | 예 |
|---|---|
| **zod 스키마** | `z.object({ ... })` |
| **Standard Schema** | valibot 등 Standard Schema 규격 구현체 |
| **JSON Schema** | `{ type: "object", properties: { ... }, required: [...] }` |

이 코스는 zod 로 통일합니다. 하지만 스키마를 DB나 설정 파일에서 **동적으로** 읽어와야 한다면 JSON Schema 를 그대로 넘길 수 있다는 걸 기억해 두세요 — zod 객체를 런타임에 조립하는 것보다 훨씬 간단합니다.

---

## 5-5. 전략 비교 — `toolStrategy` vs `providerStrategy`

`responseFormat` 에 스키마를 **그냥 넘기면** LangChain 이 알아서 전략을 고릅니다. 하지만 무슨 일이 벌어지는지 알아야 문제를 고칠 수 있습니다.

LangChain 은 구조화 출력을 두 가지 방식으로 구현합니다.

```ts
import { createAgent, toolStrategy, providerStrategy } from "langchain";

// (A) provider 네이티브 JSON schema 방식
const providerAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  responseFormat: providerStrategy(ProductReview),
});

// (B) tool calling 방식
const toolAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  responseFormat: toolStrategy(ProductReview),
});
```

### 무엇이 다른가

**`providerStrategy`** 는 provider 의 **네이티브 구조화 출력 API** 를 씁니다. OpenAI, Anthropic(Claude), Gemini, xAI(Grok) 등이 지원합니다. 스키마 강제가 **서버 사이드에서** 일어나므로 신뢰도가 높습니다 — 모델이 스키마를 어길 기회 자체가 줄어듭니다.

**`toolStrategy`** 는 **"스키마 모양의 가짜 도구"** 를 하나 만들어 모델이 그걸 호출하게 하고, 그 호출 인자를 파싱해 `structuredResponse` 로 돌려줍니다. 도구 호출을 지원하는 **모든 모델**에서 동작합니다.

이 차이는 **메시지 흐름에서 눈으로 보입니다.**

```ts
const a = await providerAgent.invoke({ messages: [question] });
const b = await toolAgent.invoke({ messages: [question] });

console.log(a.messages.map(m => m.getType()));
console.log(b.messages.map(m => m.getType()));
```

**결과** (구조가 결정적입니다)
```
providerStrategy → [ 'human', 'ai' ]
toolStrategy     → [ 'human', 'ai', 'tool' ]
```

`toolStrategy` 쪽에만 `tool` 메시지가 있습니다. 그게 가짜 도구를 부른 흔적입니다. 이 메시지는 **다음 턴의 컨텍스트에 그대로 쌓입니다** — 멀티턴 대화에서는 토큰을 먹습니다.

### 자동 선택 규칙

> 스키마를 `createAgent.responseFormat` 에 직접 넘기고 모델이 네이티브 구조화 출력을 지원하면 LangChain 은 **자동으로 `ProviderStrategy`** 를 씁니다. 네이티브 지원이 없으면 **tool calling 으로 폴백**합니다.

즉 `responseFormat: ContactInfo` 는 "알아서 잘 해줘"이고, `providerStrategy(...)` / `toolStrategy(...)` 는 "반드시 이걸로 해"입니다.

### 비교표

| | `providerStrategy` | `toolStrategy` |
|---|---|---|
| 구현 | provider 네이티브 JSON schema API | 스키마 모양의 가짜 도구 호출 |
| 지원 모델 | 네이티브 지원 provider만 (OpenAI, Claude, Gemini, Grok 등) | **도구 호출 되는 모든 모델** |
| 스키마 강제 | 서버 사이드 (신뢰도 높음) | 클라이언트 사이드 검증 + 재시도 |
| 메시지 흐름 | `human → ai` (깨끗함) | `human → ai(tool_calls) → tool` |
| 토큰 오버헤드 | 낮음 | 도구 정의 + tool 메시지만큼 추가 |
| `handleError` 재시도 제어 | ❌ | ✅ |
| `toolMessageContent` | ❌ | ✅ |
| **여러 스키마 중 택1** | ❌ | ✅ (`toolStrategy([A, B])`) |
| 도구와 함께 쓰기 | ⚠️ 모델이 동시 지원해야 함 | ✅ 안전 |

### `toolStrategy` 만 되는 것 — 여러 스키마 중 택1

이게 `toolStrategy` 를 일부러 고르는 가장 큰 이유입니다. **스키마 배열**을 주면 모델이 상황에 맞는 것 하나를 고릅니다.

```ts
const ProductReview = z.object({
  rating: z.number().min(1).max(5).optional(),
  sentiment: z.enum(["positive", "negative"]),
  keyPoints: z.array(z.string()).describe("리뷰의 핵심 포인트. 소문자, 1~3단어."),
});

const CustomerComplaint = z.object({
  issueType: z.enum(["product", "service", "shipping", "billing"]),
  severity: z.enum(["low", "medium", "high"]),
  description: z.string().describe("불만 사항 요약"),
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  responseFormat: toolStrategy([ProductReview, CustomerComplaint]),   // ← 배열
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "배송이 3주째 안 와요. 환불해주세요." }],
});
console.log(result.structuredResponse);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
{ issueType: 'shipping', severity: 'high', description: '3주 이상 배송 지연으로 환불 요청' }
```

리뷰가 아니라 불만이므로 모델이 `CustomerComplaint` 를 골랐습니다. 각 스키마가 별개의 도구가 되고, 모델이 그중 하나를 호출하는 구조라서 가능한 일입니다.

> ⚠️ **함정 — union/discriminatedUnion 을 지원 안 하는 provider 가 있다**
>
> "택1"을 zod 로 표현하려고 `z.union([A, B])` 나 `z.discriminatedUnion("type", [A, B])` 를 스키마 최상위에 쓰고 싶어집니다. 하지만 이건 **provider 별로 지원이 갈립니다.** union 은 JSON Schema 의 `anyOf`/`oneOf` 로 변환되는데, **OpenAI 의 strict 모드는 루트 레벨 `anyOf` 를 제한적으로만 받고**, 일부 provider 는 아예 거부합니다. 더 나쁜 건 거부가 아니라 **조용히 한쪽 브랜치를 무시**하는 경우입니다.
>
> **방어법 두 가지**:
> 1. **`toolStrategy([A, B])` 를 쓰세요.** 위 예제가 그것입니다. union 을 JSON Schema 로 표현하는 대신 **도구 두 개**로 표현하므로 union 지원과 무관하게 동작합니다. 이게 LangChain 이 권하는 방식입니다.
> 2. union 을 꼭 스키마 안에 넣어야 한다면 **평평한 대안**을 쓰세요 — 판별 필드를 `enum` 으로 두고 나머지를 전부 `.nullable()` 로 열어두는 것입니다. 못생겼지만 어디서나 돕니다.
>
> ```ts
> // union 대신: 판별 enum + nullable 필드
> z.object({
>   kind: z.enum(["review", "complaint"]).describe("이 글의 종류"),
>   rating: z.number().min(1).max(5).nullable().describe("review 일 때만. 아니면 null."),
>   severity: z.enum(["low","medium","high"]).nullable().describe("complaint 일 때만. 아니면 null."),
> });
> ```

> 💡 **실무 팁 — 무엇을 기본값으로 삼나**: 도구가 **없는** 단발성 변환이면 `responseFormat: Schema` (자동 선택)로 두세요. 도구가 **있는** 에이전트이거나, 재시도를 제어해야 하거나, 여러 스키마 중 택1이 필요하면 **`toolStrategy` 를 명시**하세요. `providerStrategy` 를 명시적으로 고르는 경우는 "이 provider 의 네이티브 강제를 반드시 쓰고 싶다"일 때뿐입니다.

---

## 5-6. `.describe()` 가 곧 프롬프트다

`.describe()` 를 "문서화"라고 생각하면 절반만 아는 것입니다. `.describe()` 는 **JSON Schema 의 `description` 으로 변환되어 모델에게 실제로 전달됩니다.** 즉 그 필드에만 적용되는 **프롬프트**입니다.

같은 기사, 같은 모델, 같은 필드 이름으로 실험해 봅시다. 다른 건 `.describe()` 뿐입니다.

```ts
const article =
  "지난 분기 매출은 전년 대비 23% 성장했으나, 마케팅 비용이 40% 늘어 영업이익률은 " +
  "오히려 2%p 하락했다. CFO 박정현은 하반기 비용 통제를 예고했다.";

// (A) describe 없음
const Vague = z.object({
  summary: z.string(),
  metric: z.string(),
  person: z.string(),
});

// (B) describe 있음
const Precise = z.object({
  summary: z.string().describe("기사 핵심을 한 문장(60자 이내)으로. 숫자를 반드시 포함할 것."),
  metric: z.string().describe("가장 중요한 단일 지표를 '이름: 값' 형식으로. 예) '영업이익률: -2%p'"),
  person: z.string().describe("등장 인물의 이름만. 직함 제외. 없으면 빈 문자열."),
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
(A) describe 없음
{
  summary: '지난 분기 매출은 성장했지만 마케팅 비용 증가로 영업이익률은 하락했다',
  metric: '매출 23% 성장, 마케팅 비용 40% 증가, 영업이익률 2%p 하락',
  person: 'CFO 박정현'
}

(B) describe 있음
{
  summary: '매출 23% 성장에도 마케팅비 40% 증가로 영업이익률 2%p 하락',
  metric: '영업이익률: -2%p',
  person: '박정현'
}
```

세 필드가 전부 달라졌습니다.

| 필드 | (A) 결과 | (B) 결과 | 무엇이 바뀌었나 |
|---|---|---|---|
| `summary` | 숫자 없음 | 숫자 포함 | "숫자를 반드시 포함할 것" 이 먹힘 |
| `metric` | 지표 3개를 뭉텅이로 | 단일 지표, 지정 형식 | "단일", "'이름: 값' 형식" 이 먹힘 |
| `person` | `"CFO 박정현"` | `"박정현"` | "직함 제외" 가 먹힘 |

`person` 을 보세요. (A)의 `"CFO 박정현"` 을 DB의 `name` 컬럼에 넣으면? 조인이 안 됩니다. 그리고 **이건 에러가 아닙니다.** 그럴듯한 문자열이 그럴듯한 컬럼에 들어갑니다.

> ⚠️ **함정 — `.describe()` 없는 필드는 모델이 추측한다**
>
> 필드 이름만으로 의도가 전달된다고 믿으면 안 됩니다. `date` 라는 필드 이름에 모델은 `"2026-07-15"`, `"2026년 7월 15일"`, `"July 15, 2026"`, `"07/15/26"` 중 무엇이든 넣을 수 있고 **전부 `z.string()` 검증을 통과합니다.** `amount` 는 `129000` 일 수도 `"129,000원"` 일 수도 있습니다. `name` 은 직함이 붙을 수도 안 붙을 수도 있습니다.
>
> 검증은 **타입**을 확인하지 **의미**를 확인하지 않습니다. `z.string()` 은 모든 문자열을 통과시킵니다. 그 간극을 메우는 게 `.describe()` 입니다.
>
> **체크리스트 — 이 필드들엔 `.describe()` 가 반드시 필요합니다**:
> - **형식이 있는 문자열**: 날짜, 전화번호, 통화, ID → 형식을 예시로 보여줄 것 (`"YYYY-MM-DD 형식"`)
> - **단위가 있는 숫자**: 금액, 기간, 비율 → 단위를 못박을 것 (`"원 단위 정수. 콤마 없이"`)
> - **비어 있을 수 있는 필드**: → 없을 때 무엇을 넣을지 지정 (`"없으면 null"`)
> - **enum**: → 각 선택지를 언제 고르는지 (`"확신이 없으면 human"`)
> - **배열**: → 개수와 원소의 형태 (`"소문자, 1~2단어, 최대 3개"`)
>
> 반대로 `z.enum(["positive","negative"])` 의 `sentiment` 처럼 이름과 선택지만으로 자명한 필드는 생략해도 됩니다. 규칙은 **"내가 이 필드 이름만 보고 답을 못 쓰겠으면 모델도 못 쓴다"** 입니다.

> 💡 **실무 팁 — 필드 순서가 곧 사고 순서**: 모델은 JSON 을 위에서 아래로 토큰 단위로 생성합니다. 그래서 `reasoning` 필드를 `label` 보다 **먼저** 선언하면, 모델은 근거를 쓰면서 생각한 결과를 `label` 에 반영합니다. 순서를 뒤집으면 답을 먼저 뱉고 `reasoning` 은 그 답을 정당화하는 **변명**이 됩니다. 같은 필드, 같은 설명, 순서만 바꿔도 분류 정확도가 달라집니다.

---

## 5-7. 검증 실패 처리 — 재시도, 부분 실패

모델이 스키마를 어기면 무슨 일이 벌어질까요? `toolStrategy` 는 **기본적으로 자동 재시도**합니다.

```ts
const ProductRating = z.object({
  rating: z.number().min(1).max(5).describe("1~5 사이 별점"),
  comment: z.string().describe("리뷰 코멘트"),
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  responseFormat: toolStrategy(ProductRating),
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "이걸 파싱해: Amazing product, 10/10!" }],
});
```

`"10/10!"` 은 `rating: 10` 을 유도합니다. `.max(5)` 위반입니다. 그런데 최종 결과는 정상적으로 나옵니다. `messages` 를 열어보면 이유가 보입니다.

**결과** (메시지 구조와 에러 문구가 결정적입니다)
```
[
  { role: "user", content: "Parse this: Amazing product, 10/10!" },
  { role: "assistant", content: "", tool_calls: [{ name: "ProductRating", args: { rating: 10, comment: "Amazing product" }, id: "call_1" }] },
  { role: "tool", content: "Error: Failed to parse structured output for tool 'ProductRating': 1 validation error for ProductRating\nrating\n  Input should be less than or equal to 5 [type=less_than_equal, input_value=10, input_type=int].\n Please fix your mistakes.", tool_call_id: "call_1", name: "ProductRating" },
  { role: "assistant", content: "", tool_calls: [{ name: "ProductRating", args: { rating: 5, comment: "Amazing product" }, id: "call_2" }] },
  { role: "tool", content: "Returning structured response: {'rating': 5, 'comment': 'Amazing product'}", tool_call_id: "call_2", name: "ProductRating" }
]
structuredResponse: { rating: 5, comment: "Amazing product" }
```

검증 에러가 **tool 메시지로 모델에게 되돌아가고**(`Please fix your mistakes.`), 모델이 스스로 고쳐서 다시 호출했습니다. 이게 `toolStrategy` 의 재시도 루프입니다.

### 여러 스키마를 동시에 뱉었을 때

`toolStrategy([A, B])` 에서 모델이 **둘 다** 호출해 버리는 일이 있습니다. 이것도 자동 복구됩니다.

**결과** (에러 문구가 결정적입니다)
```
{ role: "tool", content: "Error: Model incorrectly returned multiple structured responses (ContactInfo, EventDetails) when only one is expected.\n Please fix your mistakes.", tool_call_id: "call_1", name: "ContactInfo" }
```

### `handleError` 로 재시도 제어하기

```ts
// (1) 기본값: true — 기본 에러 템플릿으로 재시도
toolStrategy(ProductRating)

// (2) 문자열 — 내가 쓴 문장이 tool 메시지로 간다
toolStrategy(ProductRating, {
  handleError: "rating 은 1~5 사이여야 합니다. 10점 만점 표기는 5점 만점으로 환산하세요.",
})

// (3) 함수 — 에러 종류별로 다르게 대응
import { StructuredOutputParsingError } from "langchain";

toolStrategy(ProductRating, {
  handleError: (error) => {          // error 타입은 추론된다 (아래 함정 참고)
    if (error instanceof StructuredOutputParsingError) {
      return "rating 은 1~5 사이 정수여야 하고 comment 는 비울 수 없습니다.";
    }
    return error.message;            // MultipleStructuredOutputsError 인 경우
  },
})

// (4) false — 재시도하지 않고 예외를 그대로 던진다
toolStrategy(ProductRating, { handleError: false })
```

| `handleError` | 동작 | 언제 쓰나 |
|---|---|---|
| `true` (기본) | 기본 에러 템플릿으로 재시도 | 대화형 에이전트 |
| `"문자열"` | 그 문자열로 재시도 | 도메인 지식을 주입해 고쳐줄 때 |
| `(error) => string` | 에러 종류별 분기 | 파싱 에러와 다른 에러를 구분할 때 |
| `false` | 예외 전파, 재시도 없음 | **배치·파이프라인** |

> ⚠️ **함정 — `handleError` 함수가 받는 에러는 딱 두 종류다 (공식 문서 예제 주의)**
>
> `langchain@1.5.3` 의 실제 타입 정의는 이렇습니다.
>
> ```ts
> type ToolStrategyError = StructuredOutputParsingError | MultipleStructuredOutputsError;
> handleError?: boolean | string | ((error: ToolStrategyError) => Promise<string> | string);
> ```
>
> 여기서 **두 가지를 조심해야 합니다.**
>
> 1. **`ToolStrategyError` 는 export 되지 않습니다.** 타입 주석으로 쓰려고 `import { ToolStrategyError } from "langchain"` 하면 실패합니다. 그냥 `(error) => ...` 로 두고 **추론에 맡기세요.**
> 2. **공식 문서 예제의 `ToolInputParsingException` 은 이 자리에 오지 않습니다.** 문서에는 `error instanceof ToolInputParsingException` 을 검사하는 예제가 있지만, 실제 JS 구현에서 `handleError` 로 넘어오는 건 `StructuredOutputParsingError`(스키마 검증 실패)와 `MultipleStructuredOutputsError`(여러 스키마를 동시에 호출) **둘뿐**입니다. `ToolInputParsingException` 은 `@langchain/core/tools` 에 실재하는 클래스지만 여기선 절대 매칭되지 않습니다 — 즉 그 `if` 문은 **항상 거짓이고, 에러 없이 조용히 무시됩니다.**
>
> 검증법: 두 클래스를 모두 `instanceof` 로 걸러낸 뒤 `error.message` 를 쓰면 `tsc` 가 **`Property 'message' does not exist on type 'never'`** 라고 알려줍니다. 유니온이 그 둘로 정확히 소진된다는 증거입니다. 분기가 하나뿐이라면 위 (3) 처럼 나머지를 `error.message` 로 흘려보내면 깨끗하게 컴파일됩니다.

### `toolMessageContent` — 성공했을 때의 대화 문구

에러가 아니라 **성공** 시 대화에 남는 tool 메시지를 바꿉니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  responseFormat: toolStrategy(MeetingAction, {
    toolMessageContent: "액션 아이템을 회의록에 기록했습니다!",
  }),
});
```

기본값은 `"Returning structured response: {...}"` 인데, 이건 **구조화된 결과 전체를 대화에 다시 복사**합니다. 멀티턴에서 토큰 낭비이고, 모델이 그 내용을 다음 턴에 그대로 반복하게 만들기도 합니다. 짧은 확인 문구로 바꾸면 둘 다 해결됩니다.

> ⚠️ **함정 — 자동 재시도는 "조용한 뭉개기"가 될 수 있다**
>
> 위 예제에서 `"10/10!"` 이 `rating: 5` 로 나온 걸 다시 보세요. **성공한 것처럼 보이지만** 실제로 벌어진 일은 "모델이 10점 만점 척도를 5점 만점으로 자의적으로 클램프한 것"입니다. 사용자는 10점 만점 기준으로 말했고, 우리 DB엔 5가 들어갑니다. 이게 맞는 값인지 아무도 검토하지 않았습니다.
>
> `handleError: true` 는 **에러를 "해결"한 게 아니라 "숨긴" 것일 수 있습니다.** 재시도는 모델에게 "네 답이 틀렸으니 스키마에 맞게 바꿔"라고 말할 뿐, "원래 의도가 무엇이었는지"는 묻지 않습니다.
>
> **방어법**:
> - 배치·ETL 파이프라인이면 `handleError: false` 로 두고 **실패를 실패로 남기세요.** 나중에 사람이 보는 게 낫습니다.
> - 재시도를 쓰더라도 `messages` 에서 `Error:` 로 시작하는 tool 메시지 개수를 **로깅·모니터링**하세요. 이 숫자가 갑자기 늘면 스키마와 실제 데이터가 어긋나기 시작했다는 신호입니다.
> - 애초에 클램프가 일어나지 않도록 **스키마를 현실에 맞추세요.** 10점 만점 입력이 실제로 온다면 `.max(5)` 가 아니라 `scale: z.enum(["5점만점","10점만점"])` 을 추가하는 게 옳습니다.

### 부분 실패 방어 — 모르면 null 을 쓰게 하라

모든 필드를 필수로 두면, 모델은 **모르는 값을 지어냅니다.** 정보가 없을 때 빠져나갈 구멍을 주세요.

```ts
// ❌ 정보가 없으면 모델이 환각한다
z.object({
  rating: z.number().min(1).max(5).describe("별점"),
  comment: z.string().describe("코멘트"),
});

// ✅ 없으면 null 이라고 명시적으로 알려준다
z.object({
  rating: z.number().min(1).max(5).nullable().describe("별점. 글에 없으면 null."),
  comment: z.string().nullable().describe("코멘트. 없으면 null."),
});
```

`.nullable()` + `"없으면 null"` 이라는 `describe` 조합이 핵심입니다. 둘 중 하나만 있으면 안 됩니다 — `.nullable()` 만 있으면 모델은 그게 허용된다는 걸 알지만 **써도 되는지는 모릅니다.**

---

## 5-8. 실전 패턴 — 분류, 추출, 라우팅

구조화 출력의 실무 용례는 사실상 이 셋으로 수렴합니다.

### 패턴 1: 분류 (classification)

`enum` + `reasoning` + `confidence` 삼종 세트입니다.

```ts
const Classification = z.object({
  // 근거를 먼저 쓰게 한다 (5-6 실무 팁 참고)
  reasoning: z.string().describe("이 분류를 택한 이유를 한 문장으로. 먼저 작성할 것."),
  label: z.enum(["bug", "feature", "question", "billing", "spam"]).describe("문의 유형. 다섯 중 정확히 하나."),
  confidence: z.number().min(0).max(1).describe("분류 확신도 0~1. 애매하면 0.5 미만을 쓸 것."),
});

const classifier = model.withStructuredOutput(Classification);
const r = await classifier.invoke("결제했는데 두 번 청구됐어요. 환불 부탁드립니다.");
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
{
  reasoning: '중복 청구와 환불을 요청하고 있으므로 결제 관련 문의다',
  label: 'billing',
  confidence: 0.95
}
```

`confidence` 는 장식이 아닙니다. **실제로 분기에 쓰세요.**

```ts
if (r.confidence < 0.5) {
  // 사람 검토 큐로 보낸다
}
```

### 패턴 2: 추출 (extraction)

비정형 텍스트 → 배열 레코드. 배열 원소마다 `.describe()` 를 붙이는 게 관건입니다.

```ts
const Extraction = z.object({
  items: z.array(z.object({
    product: z.string().describe("상품명"),
    quantity: z.number().describe("수량. 명시 없으면 1."),
    unitPrice: z.number().nullable().describe("단가(원). 글에 없으면 null."),
  })).describe("주문서에 등장하는 모든 품목. 빠뜨리지 말 것."),
  orderer: z.string().nullable().describe("주문자 이름. 없으면 null."),
});

const order = "안녕하세요, 김민수입니다. 27인치 모니터 2대(대당 459,000원)랑 " +
              "무선 키보드 1개 주문하고 싶습니다. 키보드 가격은 잘 모르겠네요.";
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
{
  items: [
    { product: '27인치 모니터', quantity: 2, unitPrice: 459000 },
    { product: '무선 키보드', quantity: 1, unitPrice: null }
  ],
  orderer: '김민수'
}
```

키보드 가격이 `null` 입니다 — 글에 없으니까요. `.nullable()` 과 `"없으면 null"` 이 없었다면 모델은 그럴듯한 가격을 **지어냈을** 겁니다.

### 패턴 3: 라우팅 (routing)

구조화 출력으로 **다음 행동을 고릅니다.** 이게 멀티 에이전트([Step 18](../step-18-multi-agent/))의 기초입니다.

```ts
const Route = z.object({
  destination: z.enum(["refund_agent", "tech_support", "sales", "human"])
    .describe("이 문의를 넘길 곳. 확신이 없으면 human."),
  priority: z.enum(["low", "normal", "urgent"]).describe("처리 우선순위"),
  summary: z.string().describe("담당자가 읽을 한 문장 요약"),
});

const decision = await model.withStructuredOutput(Route).invoke(
  "3주 전에 주문한 노트북이 아직 안 왔고 고객센터도 연결이 안 됩니다. 화가 많이 나네요.",
);

// enum 이라 switch 가 타입 안전하다 — default 없이도 모든 분기가 커버된다
switch (decision.destination) {
  case "refund_agent": /* ... */ break;
  case "tech_support": /* ... */ break;
  case "sales":        /* ... */ break;
  case "human":        /* ... */ break;
}
```

`decision.destination` 이 `string` 이 아니라 **유니온 타입**이라는 게 핵심입니다. 오타를 컴파일러가 잡고, 나중에 enum 에 값을 추가하면 `switch` 가 **불완전하다고 컴파일 에러**를 냅니다. 자유 텍스트 라우팅에서는 절대 못 얻는 안전성입니다.

> 💡 **실무 팁 — enum 에 항상 탈출구를 두세요**: `["refund_agent", "tech_support", "sales"]` 만 있으면 모델은 **어디에도 안 맞는 문의를 억지로 셋 중 하나에 밀어 넣습니다.** `"human"`(또는 `"other"`, `"unknown"`)을 넣고 `.describe()` 에 "확신이 없으면 human" 이라고 적어주세요. 분류 스키마에서 `"spam"` 이나 `"other"` 가 없으면 스팸이 `question` 으로 분류되어 상담원에게 갑니다.

---

## 5-9. 종합 — 도구 + 구조화 출력을 함께 쓰는 에이전트

지금까지 배운 걸 모읍시다. 재고를 **도구로 조회**하고, 최종 답을 **구조화**해서 돌려주는 에이전트입니다.

```ts
import { createAgent, tool, toolStrategy } from "langchain";
import * as z from "zod";

const checkStock = tool(
  async ({ product }: { product: string }) => {
    const db: Record<string, number> = {
      "27인치 4K 모니터": 12, "무선 키보드": 0, "게이밍 노트북": 3,
    };
    const qty = db[product];
    return qty === undefined ? `'${product}' 는 취급하지 않는 상품입니다.` : `'${product}' 재고: ${qty}개`;
  },
  {
    name: "check_stock",
    description: "상품명을 받아 현재 재고 수량을 조회한다. 상품 문의가 오면 반드시 먼저 호출할 것.",
    schema: z.object({ product: z.string().describe("정확한 상품명") }),
  },
);

const StockAnswer = z.object({
  available: z.array(z.string()).describe("재고가 1개 이상인 상품명 목록. 없으면 빈 배열."),
  unavailable: z.array(z.string()).describe("재고가 0이거나 취급하지 않는 상품명 목록. 없으면 빈 배열."),
  reply: z.string().describe("고객에게 보낼 한국어 답변. 2문장 이내, 존댓말."),
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [checkStock],
  systemPrompt:
    "너는 재고 문의를 처리하는 상담원이다. 재고는 반드시 check_stock 도구로 확인하고, " +
    "추측하지 마라. 확인이 끝나면 구조화된 형식으로 답하라.",
  responseFormat: toolStrategy(StockAnswer),   // ← 도구가 있으면 toolStrategy 가 안전
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "27인치 4K 모니터랑 무선 키보드, 그리고 사무용 의자 재고 있나요?" }],
});

console.log(result.messages.map(m => m.getType()));
console.log(result.structuredResponse);
```

**출력 예시** (모델 응답이므로 매번 다릅니다 — 특히 도구 호출 횟수와 병렬 여부)
```
[ 'human', 'ai', 'tool', 'tool', 'tool', 'ai', 'tool' ]
{
  available: [ '27인치 4K 모니터' ],
  unavailable: [ '무선 키보드', '사무용 의자' ],
  reply: '27인치 4K 모니터는 재고가 있습니다. 무선 키보드와 사무용 의자는 현재 재고가 없습니다.'
}
```

메시지 흐름을 읽어보세요. `ai` → `tool` × 3 은 **재고 조회 도구를 3번 부른 것**이고, 마지막 `ai` → `tool` 이 **`toolStrategy` 의 가짜 구조화 도구**입니다. 도구 호출이 전부 끝난 뒤에 구조화 응답이 나옵니다.

그리고 결과에 타입이 살아 있으므로 후속 로직을 안전하게 이어붙일 수 있습니다.

```ts
const { available, unavailable, reply } = result.structuredResponse;
// available: string[], unavailable: string[], reply: string — 전부 TypeScript 가 안다
```

> ⚠️ **함정 — 도구가 있는 에이전트에서 `providerStrategy` 를 쓰면 충돌한다**
>
> 위에서 `toolStrategy` 를 **명시**한 이유가 있습니다. 공식 문서는 이렇게 못박습니다:
>
> > **"If tools are specified, the model must support simultaneous use of tools and structured output."**
> > (도구를 지정하면, 모델이 도구 사용과 구조화 출력을 **동시에** 지원해야 합니다.)
>
> `providerStrategy` 는 provider 의 네이티브 구조화 출력 API 를 켭니다. 그런데 여러 provider 에서 **"네이티브 구조화 출력 모드"와 "도구 호출 모드"는 서로 배타적이거나 제약이 있습니다.** 둘을 동시에 요구하면 모델이 도구를 **아예 안 부르고** 스키마만 채워서 답하거나(= 재고를 조회하지 않고 **지어냅니다**), provider 가 요청을 거부합니다.
>
> 앞의 경우가 특히 위험합니다. **에러가 안 납니다.** `structuredResponse` 는 스키마를 완벽히 만족하고, `available` 에는 그럴듯한 상품명이 들어 있습니다. 도구를 한 번도 안 불렀을 뿐입니다. `result.messages` 에 `tool` 메시지가 없다는 걸 눈치채지 못하면 끝까지 모릅니다.
>
> **규칙: `tools` 가 비어 있지 않으면 `toolStrategy` 를 명시하세요.** `toolStrategy` 는 구조화 출력도 결국 도구 호출로 표현하므로 도구 사용과 자연스럽게 공존합니다. 그리고 배포 전에 **`result.messages` 에 기대한 `tool` 메시지가 실제로 있는지** 검증하는 테스트를 하나 두세요([Step 19](../step-19-observability-eval/)).

---

## 정리

| 도구 | 형태 | 언제 |
|---|---|---|
| `model.withStructuredOutput(schema)` | 모델 레벨 | **도구 없는** 단발성 변환 (분류·추출) |
| `createAgent({ responseFormat: schema })` | 에이전트 레벨, 전략 자동 선택 | 일반적인 경우 |
| `createAgent({ responseFormat: providerStrategy(s) })` | provider 네이티브 강제 | 도구 없이 최고 신뢰도가 필요할 때 |
| `createAgent({ responseFormat: toolStrategy(s) })` | tool calling 강제 | **도구가 있을 때**, 재시도 제어, 스키마 택1 |
| `toolStrategy([A, B])` | 여러 스키마 중 택1 | union 대신 (provider 호환) |
| `toolStrategy(s, { handleError })` | 재시도 제어 | `false` = 배치, 문자열 = 도메인 지식 주입 |
| `toolStrategy(s, { toolMessageContent })` | 성공 시 대화 문구 | 멀티턴 토큰 절약 |

| zod | 의미 | 에이전트에서의 권장 |
|---|---|---|
| `.optional()` | **키**가 없어도 됨 | ⚠️ provider 별 동작이 갈림. 피할 것 |
| `.nullable()` | **값**이 null 이어도 됨 (키는 필수) | ✅ 기본으로 쓸 것 + `"없으면 null"` describe |
| `.nullish()` | 둘 다 | 꼭 필요할 때만 |
| `.enum([...])` | 선택지 강제 | ✅ 분류·라우팅의 핵심. 탈출구(`other`/`human`) 필수 |
| `.describe()` | **그 필드의 프롬프트** | ✅ 형식·단위·빈 값 처리를 반드시 명시 |
| `z.union` / `z.discriminatedUnion` | 택1 | ⚠️ provider 지원이 갈림. `toolStrategy([A,B])` 로 대체 |

**핵심 함정 5가지**

1. **`.optional()` vs `.nullable()` 은 provider 마다 다르게 처리된다.** `.optional()` 은 JSON Schema 의 `required` 에서 빠지는데, OpenAI strict 모드는 모든 필드가 `required` 이길 요구해 `nullable` 로 자동 변환됩니다. 결과적으로 Anthropic 은 키를 생략하고 OpenAI 는 `null` 을 넣습니다. **`.nullable()` 을 기본으로 쓰고 `== null` 로 소비하세요.**
2. **스키마가 깊게 중첩되면 정확도가 급락한다.** 중첩은 2단계까지. 그보다 깊으면 평평하게 받아서 **우리 코드에서 재조립**하세요 — 공짜이고 정확합니다.
3. **`.describe()` 없는 필드는 모델이 추측한다.** `z.string()` 은 `"2026-07-15"` 도 `"작년 여름쯤"` 도 통과시킵니다. 검증은 타입을 볼 뿐 **의미를 보지 않습니다.**
4. **union/discriminatedUnion 을 지원 안 하는 provider 가 있다.** 루트 레벨 `anyOf` 는 거부되거나 **조용히 한쪽 브랜치가 무시**됩니다. `toolStrategy([A, B])` 를 쓰세요.
5. **구조화 출력을 켜면 도구 호출과 충돌할 수 있다.** 도구가 있는데 `providerStrategy` 를 쓰면 모델이 **도구를 안 부르고 답을 지어낼** 수 있고 에러는 안 납니다. `tools` 가 있으면 `toolStrategy` 를 명시하고, `messages` 에 `tool` 이 실제로 찍히는지 확인하세요.

**추가로 기억할 것**: `handleError: true`(기본)의 자동 재시도는 문제를 **해결**한 게 아니라 **숨긴** 것일 수 있습니다. 배치 파이프라인은 `handleError: false` 로 시끄럽게 실패하는 게 낫습니다.

---

## 연습문제

1. **optional vs nullable** — `name`(필수), `nickname`(키 생략 가능), `bio`(키 필수·값 null 가능) 스키마를 만들고, 네 가지 입력 `{name}` / `{name, nickname:null}` / `{name, bio:null}` / `{전부}` 의 `safeParse` 결과를 출력하세요. 무엇이 왜 실패하는지 주석으로 적으세요.
2. **영수증 추출** — 아래 영수증 텍스트에서 `storeName`, `total`(숫자), `date`(YYYY-MM-DD), `items`(`{name, price}` 배열)를 뽑는 스키마를 만들고 `withStructuredOutput` 으로 추출하세요. **모든 필드에 `.describe()` 를 붙이세요.** (`total` 을 `z.string()` 으로 두면 왜 안 되는지 생각해 보세요)
3. **`.describe()` 효과 측정** — 같은 필드 이름(`company`, `amount`)으로 스키마 두 개(describe 없음 / 있음)를 만들어 `"주식회사 넥스트테크는 시리즈B 라운드에서 총 350억원 규모의 투자를 유치했다"` 를 처리하고, 결과를 나란히 출력해 무엇이 달라졌는지 주석으로 적으세요.
4. **전략별 메시지 흐름 비교** — 같은 스키마·같은 질문으로 `providerStrategy` 와 `toolStrategy` 에이전트를 각각 만들어 `result.messages.map(m => m.getType())` 을 출력하세요. 한쪽에만 `tool` 이 나타나는 이유를 설명하세요.
5. **검증 실패와 재시도** — `rating: z.number().min(1).max(5)` 스키마에 `"이 제품 100점 만점에 200점!"` 을 넣어 검증 실패를 유도하고, (a) 기본 `handleError` (b) `handleError: false` (c) 커스텀 문자열 세 경우를 각각 실행해 차이를 정리하세요.
6. **분류기** — 고객 문의를 `["bug","feature","question","billing","spam"]` 로 분류하는 스키마를 만드세요. 단 **`reasoning` 을 `label` 보다 먼저 선언**하고(왜일까요?), `confidence`(0~1)를 포함하세요. 제시된 문의 3개를 분류하고, `confidence < 0.5` 면 사람 검토로 보내는 분기를 넣으세요.
7. **중첩 깊이의 함정** — 같은 정보를 (A) 4단계 중첩 (B) 평평한 4필드 두 스키마로 만들어 `"VIP 고객 이수진님이 신용카드로 129,000원을 결제했습니다"` 를 처리하고 비교하세요. 어느 쪽이 안정적인지, 그리고 (B)를 (A) 모양으로 재조립하는 코드를 쓰세요.
8. **도구 + 구조화 라우팅 에이전트** — 주문번호로 배송 상태를 조회하는 도구 `lookup_order` 를 만들고, 그 도구를 쓰는 에이전트가 최종 답을 `{destination, priority, summary}` 로 구조화하게 하세요. `"ORD-1001 주문이 3주째 안 오는데 환불해주세요"` 로 실행해 메시지 흐름과 `structuredResponse` 를 출력하고, `destination` 으로 `switch` 분기를 쓰세요. (`responseFormat` 을 무엇으로 감싸야 할까요?)

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 06 — 도구(Tool) 정의](../step-06-tools/)

이 스텝에서 `toolStrategy` 가 "스키마 모양의 가짜 도구"를 만들어 구조화 출력을 구현한다는 걸 봤습니다. 그렇다면 **진짜 도구**는 어떻게 만들까요? 그리고 도구의 `description` 과 `schema` 는 왜 `.describe()` 와 똑같이 "프롬프트"일까요? Step 06 에서 다룹니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(5-1 ~ 5-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 자기완결적이라 복사해서 바로 돌릴 수 있습니다. 실행 전 `project/.env` 에 `ANTHROPIC_API_KEY` 를 넣으세요.

```bash
npx tsx docs/reference/langchain/step-05-structured-output/practice.ts
```

OpenAI 로 바꾸려면 각 파일 상단의 `const MODEL = "anthropic:claude-sonnet-4-6"` 을 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하면 됩니다. **두 provider 로 각각 돌려보는 걸 강력히 권합니다** — 5-2 의 `.optional()` / `.nullable()` 함정은 provider 를 바꿔봐야 체감됩니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[5-1] ~ [5-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 대응하므로, 본문을 읽다 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- **`[5-2]` 만 모델을 호출하지 않습니다.** zod 스키마와 `safeParse` 만 다루므로 **API 키 없이도 실행됩니다.** `optional: 키 생략 OK → true` / `optional: null 은 거부 → false` / `nullable: 키 생략은 거부 → false` / `nullable: null 은 OK → true` 네 줄이 이 스텝 전체에서 가장 중요한 출력입니다. 여기서 헷갈리면 5-2 함정으로 돌아가세요.
- `[5-5]` 는 `providerStrategy` 와 `toolStrategy` 를 **같은 스키마·같은 질문으로** 연달아 실행한 뒤 `messages.map(m => m.getType())` 을 나란히 찍습니다. `['human','ai']` 와 `['human','ai','tool']` 의 차이가 두 전략의 유일한 관찰 가능한 지문입니다.
- `[5-6]` 이 이 파일의 하이라이트입니다. `Vague` 와 `Precise` 는 **필드 이름이 완전히 같고** `.describe()` 만 다릅니다. `person` 필드가 `"CFO 박정현"` 에서 `"박정현"` 으로 바뀌는 걸 확인하세요. 모델 응답은 비결정적이라 `Vague` 가 우연히 잘 나올 수도 있는데, 그럴 땐 두세 번 더 돌려보세요 — 요점은 "우연에 기대는가"입니다.
- `[5-7]` 의 (C) 블록은 `handleError: false` 라서 **예외가 날 수도, 안 날 수도 있습니다.** 모델이 한 번에 유효한 값을 맞히면 그냥 통과합니다. 그래서 `try/catch` 로 감싸고 두 경우를 모두 출력하게 해 두었습니다 — 비결정성을 다루는 코드의 전형입니다.
- `[5-9]` 는 도구 3회 호출 + 구조화 응답을 한 번에 보여줍니다. 출력된 메시지 타입 배열에 **`'tool'` 이 실제로 들어 있는지** 반드시 확인하세요. 없다면 도구를 안 부르고 재고를 지어낸 것입니다(5-9 함정).

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 함수 본문이 비어 있으니, 거기에 직접 코드를 써 넣고 `main()` 의 주석을 하나씩 풀며 실행하면 됩니다.

- 파일을 그대로 실행하면 **아무것도 출력되지 않습니다. 정상입니다.** `main()` 안의 호출이 전부 주석 처리되어 있기 때문입니다. 푼 문제부터 하나씩 주석을 푸세요 — 8개를 한꺼번에 돌리면 API 호출이 20번 넘게 나가고 어느 출력이 어느 문제인지 알아보기 어렵습니다.
- `[문제 1]` 은 **모델 호출이 없는 유일한 문제**입니다. API 키가 없어도 풀 수 있으니 여기부터 시작하세요. 네 케이스 중 **두 개가 실패**하는데, 그중 하나는 이유가 두 겹입니다.
- `[문제 3]` 과 `[문제 7]` 은 정답이 "코드"가 아니라 **관찰과 주석**입니다. 결과를 나란히 찍어놓고 무엇이 달라졌는지 스스로 문장으로 적어야 의미가 있습니다. 모델 응답이 비결정적이므로 한 번 돌려서 차이가 안 보이면 두세 번 더 돌리세요.
- `[문제 8]` 의 마지막 힌트 `(responseFormat 을 무엇으로 감싸야 할까요?)` 가 이 파일에서 가장 중요한 대목입니다. 도구가 있는 에이전트라는 점을 놓치고 `providerStrategy` 를 쓰면 **에러 없이** 통과할 수도 있습니다 — 그리고 그게 정확히 5-9 함정입니다. `messages` 에 `'tool'` 이 찍히는지 확인해 보세요.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 채점 포인트와 기대 결과가 적혀 있습니다.

- `[정답 1]` 의 결과는 **(a) false / (b) false / (c) true / (d) true** 입니다(zod 4.4.3 실측). `(a)` 가 실패하는 게 포인트입니다 — `bio` 가 `.nullable()` 이라 "값에 null 을 허용"할 뿐 **키는 여전히 필수**이기 때문입니다. `(b)` 는 `nickname: null` 거부와 `bio` 키 누락이 **동시에** 걸려 이슈가 2개 나옵니다. 한 줄 요약: **optional 은 "키" 축, nullable 은 "값" 축**입니다.
- `[정답 2]` 는 정답을 맞히고 끝내지 않고 **검산**까지 합니다. `items` 의 `price` 합이 `total` 과 같은지 비교하는 코드가 붙어 있습니다(16,900 = 4,500+5,900+6,500). 구조화 출력의 결과에 이런 **자체 검증**을 붙이는 습관이 프로덕션에서 환각을 잡아냅니다.
- `[정답 5]` 의 (c) 가 이 파일에서 가장 배울 게 많은 대목입니다. 커스텀 에러 메시지로 `"100점 만점 점수는 20으로 나눠 환산하라"` 를 줬는데, 200/20 = 10 이라 **여전히 `.max(5)` 를 넘습니다.** 모델은 또 재시도해서 결국 5로 클램프합니다. 교훈: **재시도로 못 고치는 입력이 있습니다.** 그럴 땐 스키마를 `.nullable()` 로 열거나 척도 자체를 필드로 받아야 합니다.
- `[정답 6]` 의 해설은 `reasoning` 을 `label` 보다 먼저 선언하는 이유를 설명합니다 — 모델은 JSON 을 위에서 아래로 생성하므로, 순서를 뒤집으면 `reasoning` 이 근거가 아니라 **이미 뱉은 답에 대한 변명**이 됩니다.
- `[정답 7]` 은 평평한 스키마로 받은 결과를 **우리 코드에서 4단계 중첩으로 재조립**하는 코드로 끝납니다. 주석에 적힌 대로 이 재조립은 **공짜이고 100% 정확**합니다. 모델에게 중괄호 짝 맞추기를 시킬 이유가 없다는 걸 코드로 보여주는 부분입니다.
- `[정답 8]` 은 `toolStrategy(Route)` 를 씁니다. `providerStrategy` 를 쓰면 안 되는 이유가 주석에 적혀 있습니다. 기대 결과는 `destination: "refund_agent"`, `priority: "urgent"` 이고, `summary` 에는 **도구로 조회한 실제 배송 상태**가 포함되어야 합니다 — 포함되지 않았다면 모델이 도구를 안 부르고 지어낸 것입니다.

```ts file="./solution.ts"
```
