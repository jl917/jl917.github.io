# Step 06 — 도구(Tool) 정의

> **학습 목표**
> - 모델이 도구를 **실행하지 않는다**는 사실을 코드로 확인하고, 실행 책임이 어디 있는지 안다
> - `tool()` 로 name / description / zod schema 를 갖춘 도구를 만든다
> - `model.bindTools([...])` 로 도구를 붙이고 `AIMessage.tool_calls` 를 읽는다
> - 도구 **description 이 곧 프롬프트**임을 실험으로 확인하고, 좋은 설명을 쓴다
> - 도구가 실패했을 때 **모델에게 오류를 돌려주는 것**과 **터뜨리는 것**을 구분해서 설계한다
> - 문자열 / 객체 / `Command` / `artifact` 중 상황에 맞는 반환값을 고른다
> - 외부 API 도구에 **타임아웃과 재시도**를 건다
> - 도구 **개수·입도·이름**을 설계 원칙에 따라 정한다
>
> **선행 스텝**: [Step 05 — 구조화된 출력 (zod)](../step-05-structured-output/)
> **예상 소요**: 90분

[Step 05](../step-05-structured-output/) 에서 우리는 모델이 zod 스키마에 맞는 JSON 을 뱉게 만들었습니다. 도구 호출은 **그것의 다른 이름**입니다. 구조화된 출력이 "이 모양으로 대답해"라면, 도구 호출은 "이 함수를 이 인자로 불러달라고 말해"입니다. 둘 다 모델이 하는 일은 똑같습니다 — **스키마에 맞는 JSON 을 생성하는 것**.

이 사실이 이 스텝 전체를 관통합니다. 모델은 여러분의 함수를 실행할 수 없습니다. 네트워크도 못 씁니다. 파일도 못 읽습니다. 모델이 할 수 있는 건 `{"name": "get_weather", "args": {"city": "서울"}}` 라는 텍스트를 뱉는 것뿐이고, 그걸 읽고 실제로 `getWeather("서울")` 을 부르는 건 **여러분의 코드**입니다. 도구는 에이전트의 손발이지만, 그 손발을 실제로 움직이는 근육은 여러분이 짭니다.

이번 스텝은 도구 하나를 잘 만드는 법에 집중합니다. 그 도구들을 자동으로 실행하는 루프는 [Step 07](../step-07-tool-loop/) 에서 손으로 짜보고, [Step 08](../step-08-create-agent/) 에서 `createAgent` 에게 넘깁니다. 즉 이 스텝의 결과물은 뒤 15개 스텝 내내 재사용됩니다. 여기서 대충 만든 도구는 Step 18 멀티 에이전트에서 그대로 발목을 잡습니다.

---

## 6-1. 도구가 에이전트의 손발이다 — 실제 메커니즘

먼저 오해부터 깨고 갑시다. 도구 본문에 `console.log` 를 넣고, 모델에게 그 도구를 준 뒤 질문해 보겠습니다.

```ts
import { initChatModel, tool } from "langchain";
import * as z from "zod";

let sideEffectCounter = 0;

const addTool = tool(
  ({ a, b }) => {
    sideEffectCounter += 1;               // ← 실제로 실행되면 올라간다
    console.log(`   [실제 함수 실행됨] add(${a}, ${b})`);
    return String(a + b);
  },
  {
    name: "add",
    description: "두 정수를 더한 값을 반환합니다.",
    schema: z.object({
      a: z.number().describe("첫 번째 정수"),
      b: z.number().describe("두 번째 정수"),
    }),
  },
);

const model = await initChatModel("anthropic:claude-sonnet-4-6");
const modelWithTools = model.bindTools([addTool]);

const response = await modelWithTools.invoke([
  { role: "user", content: "17 더하기 25 는 얼마야?" },
]);

console.log("content   :", JSON.stringify(response.content));
console.log("tool_calls:", JSON.stringify(response.tool_calls, null, 2));
console.log("실제 add() 실행 횟수 =", sideEffectCounter);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
content   : ""
tool_calls: [
  {
    "name": "add",
    "args": { "a": 17, "b": 25 },
    "id": "toolu_01A9F2kR3xQmN8vP",
    "type": "tool_call"
  }
]
실제 add() 실행 횟수 = 0
```

**`[실제 함수 실행됨]` 로그가 어디에도 없습니다. 카운터는 0입니다.**

모델은 "add 를 a=17, b=25 로 불러줘"라고 **말만 했습니다.** 함수는 손도 안 댔습니다. `tool_calls` 는 그냥 데이터입니다 — 모델이 생성한 텍스트를 LangChain 이 파싱해서 객체로 만들어 놓은 것뿐입니다.

실제로 실행하려면 우리가 부릅니다.

```ts
const call = response.tool_calls?.[0];
if (call) {
  const result = await addTool.invoke(call.args as { a: number; b: number });
  console.log("결과 :", result);            // "42"
  console.log("실행 횟수 =", sideEffectCounter);  // 1
}
```

**출력**
```
   [실제 함수 실행됨] add(17, 25)
결과 : 42
실행 횟수 = 1
```

전체 흐름은 이렇습니다.

| 단계 | 하는 주체 | 하는 일 |
|---|---|---|
| 1 | 우리 코드 | `bindTools` 로 도구의 name/description/schema 를 JSON Schema 로 변환해 API 요청에 실어 보냄 |
| 2 | 모델 | "이 도구를 이 인자로 부르면 되겠다" 고 판단, **JSON 텍스트를 생성** |
| 3 | LangChain | 그 텍스트를 파싱해 `AIMessage.tool_calls` 배열로 만들어 줌 |
| 4 | **우리 코드** | `tool_calls` 를 읽고 **실제 함수를 실행** |
| 5 | **우리 코드** | 결과를 `ToolMessage`(+ `tool_call_id`)로 만들어 대화에 추가 |
| 6 | 모델 | ToolMessage 를 읽고 최종 답변 생성 (또는 도구를 또 부름) |

4번과 5번이 **전부 우리 몫**이라는 게 핵심입니다. `createAgent`([Step 08](../step-08-create-agent/))는 이 4~6을 대신 돌려주는 루프일 뿐, 마법이 아닙니다.

> ⚠️ **함정 (이 스텝의 1번 함정)**: "모델에게 도구를 줬는데 함수가 안 불려요" 는 버그가 아닙니다. **원래 안 불립니다.** `bindTools` 는 "이런 도구가 있다"고 모델에게 **알려주기만** 합니다. `bindTools` 를 `invoke` 로 착각해서 "모델이 알아서 다 해주겠지" 하고 `tool_calls` 를 처리하는 코드를 안 짜면, 여러분의 앱은 조용히 아무 일도 안 하고 빈 `content` 만 반환합니다. 에러도 안 납니다. 이게 LangChain 첫 주에 가장 많이 겪는 일입니다.

> 💡 **실무 팁**: 도구 호출을 디버깅할 땐 항상 **`response.content` 와 `response.tool_calls` 를 같이 찍으세요.** 모델이 도구를 부를 때 `content` 는 보통 비어 있거나(`""`) 짧은 사고 과정만 담깁니다. `content` 만 보고 "모델이 아무 말도 안 하네?" 라고 헤매는 경우가 많습니다. 답은 `tool_calls` 에 들어 있습니다.

---

## 6-2. `tool()` 로 도구 만들기

도구는 **함수 + 메타데이터 3종**이 전부입니다.

```ts
import { tool } from "langchain";
import * as z from "zod";

const searchProducts = tool(
  async ({ query, limit }) => {
    const db = [
      { id: 1, name: "게이밍 노트북 RTX4060", price: 2190000, stock: 3 },
      { id: 2, name: "27인치 4K 모니터", price: 459000, stock: 12 },
      { id: 3, name: "인체공학 사무용 의자", price: 329000, stock: 0 },
      { id: 4, name: "기계식 키보드 청축", price: 89000, stock: 40 },
    ];
    const hits = db.filter((p) => p.name.includes(query)).slice(0, limit);
    return JSON.stringify(hits);
  },
  {
    name: "search_products",
    description:
      "상품명 부분 일치로 상품 카탈로그를 검색합니다. 상품 ID, 이름, 가격(원), 재고 수량을 반환합니다.",
    schema: z.object({
      query: z.string().describe("상품명에 포함될 검색어. 예: '노트북', '모니터'"),
      limit: z.number().int().min(1).max(20).describe("최대 반환 개수. 기본 5."),
    }),
  },
);
```

네 부분을 하나씩 봅시다.

| 부분 | 누가 보는가 | 역할 |
|---|---|---|
| 첫 번째 인자 (함수) | **우리 코드만** | 실제로 일하는 몸통. 첫 파라미터는 schema 로 파싱된 입력. |
| `name` | **모델** | 모델이 부를 이름. snake_case. |
| `description` | **모델** | 모델이 "언제 부를지" 판단하는 **유일한** 근거. |
| `schema` | **모델 + 런타임** | 모델에겐 인자 명세, 런타임에겐 검증기. |

함수 본문은 모델이 **절대 못 봅니다.** 모델이 아는 것은 name/description/schema 세 개뿐입니다. 여기서 6-3의 결론이 미리 도출됩니다 — 함수를 아무리 잘 짜도 description 이 부실하면 모델은 그 도구의 존재를 모르는 것과 같습니다.

도구는 모델 없이도 그냥 함수처럼 부를 수 있습니다. 테스트할 땐 이렇게 합니다.

```ts
const raw = await searchProducts.invoke({ query: "노트북", limit: 5 });
console.log(raw);
console.log("name       :", searchProducts.name);
console.log("description:", searchProducts.description);
```

**출력**
```
[{"id":1,"name":"게이밍 노트북 RTX4060","price":2190000,"stock":3}]
name       : search_products
description: 상품명 부분 일치로 상품 카탈로그를 검색합니다. 상품 ID, 이름, 가격(원), 재고 수량을 반환합니다.
```

스키마 검증은 **도구 함수가 실행되기 전에** 일어납니다. 타입이 안 맞는 입력은 함수에 도달하지 못합니다.

```ts
try {
  await searchProducts.invoke({ query: "의자", limit: "많이" } as never);
} catch (err) {
  console.log("스키마 검증 실패(정상):", (err as Error).message.split("\n")[0]);
}
```

**출력** (zod 버전에 따라 문구는 다를 수 있습니다)
```
스키마 검증 실패(정상): Received tool input did not match expected schema
```

이건 좋은 일입니다. 모델이 헛소리를 해도 여러분의 함수 본문은 항상 타입이 맞는 입력만 받습니다. **스키마는 모델과 코드 사이의 방화벽입니다.**

> 💡 **실무 팁**: `.describe()` 를 **모든 필드에** 붙이세요. `.describe()` 문자열은 JSON Schema 의 `description` 필드로 변환되어 모델에게 그대로 전달됩니다. 즉 이건 개발자용 주석이 아니라 **프롬프트의 일부**입니다. 특히 형식이 있는 값(`"YYYY-MM-DD"`, `"ISO 4217 3자리"`, `"ORD-1234"`)은 반드시 예시까지 적으세요. 안 적으면 모델은 자기가 편한 형식으로 지어냅니다.

> 💡 **실무 팁**: 제약은 프롬프트로 부탁하지 말고 **스키마로 강제**하세요. `z.enum(["C", "F"])` 은 모델이 `"celsius"` 나 `"섭씨"` 를 넣는 것을 **원천 차단**합니다. `z.string().describe("C 또는 F")` 는 부탁일 뿐이고, 모델은 종종 부탁을 안 듣습니다. `z.number().int().min(1).max(20)`, `z.string().email()`, `z.string().regex(/^\d{6}$/)` 도 마찬가지입니다. 검증할 수 있는 건 검증하세요.

---

## 6-3. 도구 설명(description)이 곧 프롬프트다

이게 이 스텝에서 가장 중요한 절입니다. **함수는 똑같고, description 만 다른** 두 도구를 놓고 모델의 행동을 비교해 봅시다.

```ts
// (A) 나쁜 설명
const stockPriceBad = tool(async ({ ticker }) => `${ticker}: 71800`, {
  name: "get_data",
  description: "데이터를 가져옵니다.",
  schema: z.object({ ticker: z.string() }),
});

// (B) 좋은 설명 — 함수 본문은 (A)와 완전히 동일하다
const stockPriceGood = tool(async ({ ticker }) => `${ticker}: 71800`, {
  name: "get_stock_price",
  description: [
    "한국거래소(KRX) 상장 종목의 현재 주가를 원(KRW) 단위로 조회합니다.",
    "사용자가 특정 회사의 주가·시세·주식 가격을 물어보면 이 도구를 사용하세요.",
    "종목코드(6자리 숫자)를 알아야 하며, 회사 이름만 주어졌다면 먼저 사용자에게 종목코드를 물어보세요.",
    "장 마감 후에는 종가를 반환합니다. 해외 주식은 지원하지 않습니다.",
  ].join(" "),
  schema: z.object({
    ticker: z
      .string()
      .regex(/^\d{6}$/)
      .describe("KRX 6자리 종목코드. 예: 삼성전자=005930, SK하이닉스=000660"),
  }),
});
```

같은 질문을 각각 던집니다.

```ts
const model = await initChatModel("anthropic:claude-sonnet-4-6");
const question = "삼성전자(005930) 지금 주가 얼마야?";

const bad = await model.bindTools([stockPriceBad]).invoke([{ role: "user", content: question }]);
console.log("tool_calls 개수:", bad.tool_calls?.length ?? 0);
console.log("content        :", JSON.stringify(bad.content).slice(0, 160));

const good = await model.bindTools([stockPriceGood]).invoke([{ role: "user", content: question }]);
console.log("tool_calls 개수:", good.tool_calls?.length ?? 0);
console.log("args           :", JSON.stringify(good.tool_calls?.[0]?.args));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[나쁜 설명]
  tool_calls 개수: 0
  content        : "죄송하지만 실시간 주가 정보에 접근할 수 없습니다. 네이버 금융이나 증권사 앱에서..."

[좋은 설명]
  tool_calls 개수: 1
  args           : {"ticker":"005930"}
```

**같은 함수인데 한쪽은 아예 안 불립니다.** 나쁜 쪽에서 모델은 "실시간 주가에 접근할 수 없다"고 사과까지 합니다 — 주가를 조회하는 도구를 손에 쥐고서. 모델 입장에서 `get_data` / "데이터를 가져옵니다" 는 세상 모든 질문에 해당하거나 아무 질문에도 해당하지 않는 말이라, 위험을 피해 안 부르는 쪽을 택한 겁니다.

> ⚠️ **함정 (조용히 틀리는 대표 사례)**: description 이 부실하면 **에러가 나지 않습니다.** 모델은 그냥 도구를 안 부르고 "그건 제가 할 수 없습니다" 라고 정중하게 답할 뿐입니다. 로그에는 아무 이상이 없고, 예외도 없고, 테스트도 통과합니다. 그래서 개발자는 "모델이 멍청하다" 고 결론 내리고 시스템 프롬프트에 `"주가를 물어보면 반드시 get_data 도구를 사용하세요"` 같은 땜질을 추가합니다. **고쳐야 할 건 시스템 프롬프트가 아니라 description 입니다.** 시스템 프롬프트는 대화 전체에 붙는 공용 공간이고, description 은 그 도구에만 딱 붙는 전용 공간입니다. 도구에 관한 지시는 도구에 쓰세요.

### 좋은 description 의 3요소

| 요소 | 질문 | 예시 |
|---|---|---|
| **무엇을** | 뭘 하고 뭘 반환하는가? 단위와 형식까지 | "KRX 상장 종목의 현재 주가를 **원(KRW) 단위**로 조회합니다" |
| **언제** | 어떤 사용자 발화가 트리거인가? | "사용자가 **주가·시세·주식 가격**을 물어보면" |
| **제약** | 못 하는 건? 전제 조건은? 다른 도구와의 순서는? | "**해외 주식은 미지원**. 종목코드를 모르면 **먼저 사용자에게 물어보세요**" |

3번 "제약"을 특히 빼먹기 쉽습니다. 그런데 실무에서 사고를 막는 건 대부분 3번입니다. "이 도구는 되돌릴 수 없습니다", "먼저 X 도구로 ID를 확인하세요", "재고가 0이면 부르지 마세요" 같은 문장이 도구 오용을 사전에 차단합니다.

> 💡 **실무 팁**: description 을 **다른 팀원에게 주는 함수 문서**라고 생각하고 쓰세요. 그 사람이 여러분 코드를 못 보고 이 설명만 읽고 함수를 써야 한다면 뭘 적어야 할까요? 모델의 처지가 정확히 그렇습니다. 그리고 description 은 **버전 관리 대상**입니다. 프롬프트를 고치듯 A/B 테스트하고, 바꾸면 회귀 테스트를 돌리세요([Step 19](../step-19-observability-eval/)). "description 한 줄 고쳤는데 뭐" 라고 넘기면 프로덕션 동작이 바뀝니다.

---

## 6-4. `bindTools([...])` 와 `AIMessage.tool_calls` 관찰

`bindTools` 는 모델 인스턴스에 도구 목록을 붙인 **새 인스턴스를 반환**합니다. 원본은 안 바뀝니다.

```ts
const model = await initChatModel("anthropic:claude-sonnet-4-6");
const modelWithTools = model.bindTools([getWeather]);   // model 은 그대로
```

### (1) 도구를 붙여도 필요 없으면 안 부른다

```ts
const noCall = await model.bindTools([getWeather]).invoke([
  { role: "user", content: "안녕! 자기소개 한 줄만 해줘." },
]);
console.log("tool_calls:", noCall.tool_calls?.length ?? 0);
console.log("content   :", JSON.stringify(noCall.content).slice(0, 120));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
tool_calls: 0
content   : "안녕하세요! 저는 여러 도구를 활용해 질문에 답하는 AI 어시스턴트입니다."
```

도구를 붙이는 건 **선택지를 주는 것**이지 강제가 아닙니다. 모델이 판단합니다.

### (2) 병렬 도구 호출 — `tool_calls` 는 배열이다

```ts
const parallel = await model.bindTools([getWeather]).invoke([
  { role: "user", content: "서울, 부산, 제주 날씨를 한 번에 알려줘." },
]);
console.log("tool_calls 개수:", parallel.tool_calls?.length ?? 0);
for (const c of parallel.tool_calls ?? []) {
  console.log(`- id=${c.id} name=${c.name} args=${JSON.stringify(c.args)}`);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
tool_calls 개수: 3
- id=toolu_01Xk9... name=get_weather args={"city":"서울"}
- id=toolu_01Bm2... name=get_weather args={"city":"부산"}
- id=toolu_01Qr7... name=get_weather args={"city":"제주"}
```

`tool_calls` 가 **배열인 이유**가 여기 있습니다. 하나의 `AIMessage` 에 도구 호출이 여러 개 담길 수 있습니다. 이걸 "병렬 도구 호출(parallel tool calls)" 이라고 합니다.

> ⚠️ **함정**: 병렬 호출된 도구들 사이엔 **순서 보장도 의존 관계 보장도 없습니다.** 배열 순서는 모델이 텍스트를 생성한 순서일 뿐입니다. "먼저 고객을 찾고 그 ID로 주문을 조회" 처럼 순서가 중요한 작업을 모델이 병렬로 호출해 버리면, 두 번째 도구는 아직 존재하지 않는 ID를 인자로 받습니다. 방어법은 (a) description 에 `"이 도구를 쓰기 전에 반드시 X 를 먼저 호출하세요"` 라고 명시하거나, (b) 스키마상 앞 도구의 출력을 받아야만 부를 수 있게 만드는 것입니다(예: `customerId` 를 필수로). 프롬프트로만 부탁하면 언젠가는 어깁니다.

### (3) `toolChoice` — 강제로 부르게 하기

```ts
const forced = await model.bindTools([getWeather], { toolChoice: "any" }).invoke([
  { role: "user", content: "안녕! 자기소개 한 줄만 해줘." },
]);
console.log(JSON.stringify(forced.tool_calls));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[{"name":"get_weather","args":{"city":"서울"},"id":"toolu_01Zz...","type":"tool_call"}]
```

자기소개를 부탁했는데 날씨를 조회합니다. `toolChoice` 옵션은 세 가지 형태가 있습니다.

| 값 | 뜻 |
|---|---|
| (생략) | 모델이 알아서 판단 (기본) |
| `"any"` | 아무 도구든 **반드시** 하나는 부른다 |
| `"도구이름"` | 그 도구를 **반드시** 부른다 |

> ⚠️ **함정**: `toolChoice` 를 에이전트 루프에 걸어두면 **무한 루프**가 됩니다. 모델은 도구를 부르지 않으면 안 되므로 최종 답변을 영영 못 냅니다. `toolChoice` 는 "이번 한 번의 호출" 을 강제하는 용도입니다. 루프 안에서 첫 턴만 강제하고 싶다면 첫 턴과 나머지 턴에 서로 다른 바인딩을 써야 합니다. 참고로 일부 provider 는 `parallelToolCalls: false` 로 병렬 호출을 끌 수도 있습니다.

### (4) `tool.invoke(toolCall)` → `ToolMessage`

도구에 `args` 만 넘기면 함수의 반환값이 그대로 나옵니다. 반면 **`ToolCall` 객체를 통째로** 넘기면 `ToolMessage` 가 나옵니다.

```ts
const call = parallel.tool_calls?.[0];
const toolMessage = await getWeather.invoke(call!);

console.log("type        :", toolMessage.getType());
console.log("content     :", toolMessage.content);
console.log("tool_call_id:", toolMessage.tool_call_id);
console.log("name        :", toolMessage.name);
```

**출력**
```
type        : tool
content     : 서울: 맑음, 24도
tool_call_id: toolu_01Xk9...
name        : get_weather
```

`ToolMessage` 의 필드는 구조가 정해져 있습니다.

| 필드 | 필수 | 내용 |
|---|---|---|
| `content` | ✅ | 도구 출력의 문자열화된 결과. **모델에게 전달됨** |
| `tool_call_id` | ✅ | 응답 대상인 tool call 의 id |
| `name` | ✅ | 호출된 도구 이름 |
| `artifact` | ❌ | **모델에게 전달되지 않는** 부가 데이터 (6-6) |

> 💡 **실무 팁**: 가급적 `tool.invoke(call.args)` 대신 **`tool.invoke(call)`** 을 쓰세요. `tool_call_id` 와 `name` 을 자동으로 채워 주므로 손으로 옮겨 적다가 틀릴 일이 없습니다. `tool_call_id` 가 하나라도 안 맞으면 다음 `invoke` 에서 provider 가 400 을 뱉습니다 — 이건 [Step 07](../step-07-tool-loop/) 에서 아주 자세히 겪게 됩니다.

---

## 6-5. 도구 에러 처리 — throw 하면 어떻게 되나

도구는 결국 함수고, 함수는 실패합니다. 문제는 **누가 그 실패를 받느냐**입니다.

```ts
const flakyTool = tool(
  async ({ orderId }) => {
    if (!/^ORD-\d{4}$/.test(orderId)) {
      throw new Error(`잘못된 주문번호 형식: ${orderId} (ORD-1234 형식이어야 함)`);
    }
    return JSON.stringify({ orderId, status: "SHIPPED" });
  },
  {
    name: "get_order_status",
    description: "주문번호로 배송 상태를 조회합니다.",
    schema: z.object({ orderId: z.string().describe("주문번호. 'ORD-' + 4자리 숫자 형식") }),
  },
);

await flakyTool.invoke({ orderId: "12345" });
```

**출력**
```
Error: 잘못된 주문번호 형식: 12345 (ORD-1234 형식이어야 함)
    at ...
```

**예외는 도구를 실행한 사람에게 그대로 튀어오릅니다.** 지금은 우리가 `invoke` 했으니 우리에게 옵니다. 에이전트 루프 안이었다면 루프에게 튀고, 아무도 안 잡으면 **에이전트 전체가 죽습니다.** 사용자 입장에선 "채팅창이 그냥 멈췄다" 가 됩니다.

> ⚠️ **함정 (에이전트를 죽이는 가장 흔한 원인)**: 도구가 `throw` 하면 **에이전트 전체가 죽습니다.** 도구 하나가 실패했다고 대화 전체가 끝나는 게 정상일까요? 사람이라면 "어, 주문번호 형식이 틀렸네요. 다시 알려주시겠어요?" 라고 할 겁니다. 그런데 기본 동작은 프로세스 크래시입니다. 게다가 도구는 대부분 **외부 세계**를 만집니다 — 네트워크는 끊기고, API 는 500 을 뱉고, DB 는 타임아웃 납니다. 즉 **도구는 반드시 실패한다고 가정해야 합니다.** "이건 절대 실패 안 해" 라고 생각한 도구가 새벽 3시에 여러분을 깨웁니다.

### 방법 A — 도구 안에서 잡아 문자열로 반환

가장 단순하고 가장 자주 쓰는 방법입니다.

```ts
const safeOrderTool = tool(
  async ({ orderId }) => {
    try {
      if (!/^ORD-\d{4}$/.test(orderId)) {
        throw new Error(`잘못된 주문번호 형식: ${orderId}`);
      }
      return JSON.stringify({ orderId, status: "SHIPPED" });
    } catch (err) {
      // 모델이 읽고 스스로 고칠 수 있게: 무엇이 왜 틀렸고, 이제 뭘 해야 하는지
      return `오류: ${(err as Error).message}. 주문번호는 'ORD-' 뒤에 숫자 4자리입니다(예: ORD-1234). 사용자에게 올바른 주문번호를 물어보세요.`;
    }
  },
  {
    name: "get_order_status_safe",
    description: "주문번호로 배송 상태를 조회합니다. 형식이 틀리면 오류 메시지를 반환합니다.",
    schema: z.object({ orderId: z.string().describe("주문번호. 'ORD-' + 4자리 숫자") }),
  },
);
```

이제 그 오류를 모델에게 `ToolMessage` 로 돌려주면 어떻게 되는지 봅시다.

```ts
const bound = model.bindTools([safeOrderTool]);
const messages: unknown[] = [{ role: "user", content: "주문 12345 어디까지 왔어?" }];

const first = await bound.invoke(messages as never);
messages.push(first);

for (const c of first.tool_calls ?? []) {
  const tm = await safeOrderTool.invoke(c);
  messages.push(tm);
}

const second = await bound.invoke(messages as never);
console.log(second.content);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
주문번호 형식이 올바르지 않습니다. 주문번호는 'ORD-' 뒤에 숫자 4자리 형태입니다.
혹시 'ORD-1234' 처럼 앞에 ORD- 가 붙은 번호는 아니신가요? 정확한 주문번호를 알려주시면 다시 조회해 드리겠습니다.
```

**모델이 스스로 복구했습니다.** 크래시 대신 사용자에게 도움이 되는 답변이 나갔습니다. 이게 오류 메시지를 "모델이 읽는 지시문" 으로 쓴다는 뜻입니다.

### 방법 B — 미들웨어로 한 번에 감싸기

도구가 20개인데 전부 try/catch 를 넣는 건 지겹습니다. `createMiddleware` 의 `wrapToolCall` 로 한 번에 처리할 수 있습니다.

```ts
import { createAgent, createMiddleware, ToolMessage } from "langchain";

const handleToolErrors = createMiddleware({
  name: "HandleToolErrors",
  wrapToolCall: async (request, handler) => {
    try {
      return await handler(request);
    } catch (error) {
      return new ToolMessage({
        content: `Tool error: Please check input and try again. (${error})`,
        tool_call_id: request.toolCall.id!,
      });
    }
  },
});

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [flakyTool],
  middleware: [handleToolErrors],
});
```

미들웨어는 [Step 12](../step-12-middleware-custom/) 의 주제입니다. 여기선 "이런 게 있다" 만 알아두세요.

### 세 가지 전략 비교

| 전략 | 언제 | 결과 |
|---|---|---|
| 도구 안에서 잡아 문자열 반환 | **기본값.** 모델이 고칠 수 있는 오류(인자 형식, 없는 ID) | 모델이 재시도하거나 사용자에게 설명 |
| 미들웨어로 일괄 처리 | 도구가 많고 오류 처리 정책이 공통일 때 | 모든 도구가 자동으로 보호됨 |
| 일부러 `throw` | **고칠 수 없는 오류.** 인증 실패, 설정 누락, 프로그래밍 버그 | 루프 중단. 사람이 봐야 함 |

> 💡 **실무 팁**: 판단 기준은 **"모델이 이걸 알면 뭔가 다르게 할 수 있는가?"** 입니다.
> - `"주문번호 형식 오류"` → 모델이 사용자에게 되물을 수 있다 → **문자열 반환**
> - `"API 키가 만료됨"` → 모델이 뭘 해도 소용없다. 20턴 동안 재시도만 반복하며 토큰을 태울 것이다 → **throw**
>
> 그리고 오류 문자열엔 반드시 **다음 행동 지시**를 넣으세요. `"오류 발생"` 은 모델에게 아무 정보가 없습니다. `"오류: X. 대신 Y 하세요"` 라고 쓰면 모델이 실제로 Y 를 합니다.

---

## 6-6. 도구 반환값 — 문자열, 객체, Command, artifact

도구는 네 종류의 값을 반환할 수 있고, 각각 쓰임이 다릅니다.

### (a) 문자열 — 기본

```ts
const asString = tool(async ({ city }) => `${city}은 맑고 24도입니다.`, {
  name: "weather_string",
  description: "날씨를 사람이 읽는 문장으로 반환합니다.",
  schema: z.object({ city: z.string() }),
});
```

가장 단순하고, 90% 는 이걸로 충분합니다.

### (b) 객체 — 구조화된 데이터

```ts
const asObject = tool(
  async ({ city }) => ({ city, tempC: 24, condition: "sunny", humidity: 41 }),
  {
    name: "weather_object",
    description: "날씨를 구조화된 객체로 반환합니다.",
    schema: z.object({ city: z.string() }),
  },
);
```

객체를 반환하면 모델에게 갈 때 직렬화됩니다. 필드 이름이 그대로 모델에게 보이므로, **필드 이름 자체가 설명 역할**을 합니다. `tempC` 는 `temp` 보다 낫습니다 — 단위가 이름에 있으니까요.

멀티모달 콘텐츠 블록 배열도 반환할 수 있습니다.

```ts
return [
  { type: "text", text: "Screenshot:" },
  { type: "image", url: "..." },
];
```

### (c) `Command` — 에이전트 상태를 바꾸기

도구가 단순히 값만 돌려주는 게 아니라 **에이전트의 상태를 갱신**해야 할 때가 있습니다. 이때 `Command` 를 반환합니다.

```ts
import { tool, ToolMessage, type ToolRuntime } from "langchain";
import { Command } from "@langchain/langgraph";
import * as z from "zod";

const setLanguage = tool(
  async ({ language }, config: ToolRuntime) => {
    return new Command({
      update: {
        preferredLanguage: language,
        messages: [
          new ToolMessage({
            content: `Language set to ${language}.`,
            tool_call_id: config.toolCallId,
          }),
        ],
      },
    });
  },
  {
    name: "set_language",
    description: "Set the preferred response language.",
    schema: z.object({ language: z.string() }),
  },
);
```

`update` 안에 상태 필드와 `messages` 를 같이 넣습니다. `messages` 를 빼먹으면 tool call 에 대한 응답이 없어서 대화가 깨집니다. `config.toolCallId` 는 두 번째 인자 `ToolRuntime` 에서 나옵니다 — 상태 스키마와 `ToolRuntime` 은 [Step 14](../step-14-context-runtime/) 의 주제이니 여기선 "도구가 상태도 바꿀 수 있다" 만 기억하세요.

(`Command` 를 반환하는 도구는 애초에 에이전트 안에서만 의미가 있으므로 `config.toolCallId` 가 항상 주입됩니다. 하지만 바로 아래 `artifact` 절에서 보듯, **직접 `invoke` 하는 도구라면 얘기가 달라집니다.**)

### (d) `artifact` — 모델에게 안 보내는 데이터

이게 실무에서 정말 중요합니다. 검색 도구가 200건을 찾았다고 합시다. 그걸 다 `content` 에 넣으면?

```ts
const searchWithArtifact = tool(
  async ({ query }, config: ToolRuntime) => {
    const rows = Array.from({ length: 200 }, (_, i) => ({
      id: i + 1,
      title: `${query} 관련 문서 ${i + 1}`,
      body: "…본문 수천 자…",
    }));

    return new ToolMessage({
      // 모델에게 가는 것: 짧은 요약만
      content: `'${query}' 검색 결과 ${rows.length}건. 상위 3건: ${rows
        .slice(0, 3)
        .map((r) => r.title)
        .join(", ")}`,
      // 코드가 꺼내 쓰는 것: 원본 전체
      artifact: { rows, total: rows.length },
      tool_call_id: config.toolCallId ?? config.toolCall?.id ?? "",
      name: "search_docs",
    });
  },
  {
    name: "search_docs",
    description: "문서를 검색해 상위 결과 요약을 반환합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);
```

**출력**
```
모델이 보는 content : 'LangChain' 검색 결과 200건. 상위 3건: LangChain 관련 문서 1, LangChain 관련 문서 2, LangChain 관련 문서 3
코드가 보는 artifact: 200건 (rows.length=200)
tool_call_id        : call_demo_1
```

`artifact` 는 **모델에게 전송되지 않습니다.** 코드가 `toolMessage.artifact` 로 꺼내서 UI에 렌더링하거나 파일로 저장하면 됩니다.

`tool_call_id` 를 왜 저렇게 세 겹으로 썼는지 궁금할 겁니다. 이게 이 절의 진짜 함정입니다.

> ⚠️ **함정 (실행 경로에 따라 값이 사라진다)**: `ToolRuntime` 타입에는 `toolCallId: string` 이 **필수 필드로 선언**되어 있습니다. 그런데 이 값은 **에이전트 실행 시스템**(`createAgent` / `ToolNode`)이 도구를 실행할 때 **주입해 주는 것**입니다. 즉 `tool.invoke(toolCall)` 로 도구를 **직접** 부르면 주입 과정이 없어서 `config.toolCallId` 는 **`undefined`** 입니다. 타입은 `string` 이라고 하는데 런타임은 `undefined` 를 주므로, TypeScript 는 아무 경고도 안 해 줍니다.
>
> 결과가 고약합니다. `tool_call_id` 가 빈 `ToolMessage` 가 만들어지고, **그 자리에선 아무 에러도 안 납니다.** 한참 뒤 그 메시지를 모델에게 보낼 때가 되어서야 provider 가 400 을 뱉습니다. 원인 지점과 증상 지점이 멀어서 찾기가 정말 어렵습니다.
>
> 다행히 직접 호출 경로에서도 **`config.toolCall`(ToolCall 객체 전체)은 채워집니다.** 그래서 `config.toolCallId ?? config.toolCall?.id ?? ""` 로 받으면 에이전트 안이든 직접 호출이든 양쪽 다 안전합니다. 유닛 테스트에서 도구를 직접 `invoke` 하는 일이 흔하므로, `ToolMessage` 를 반환하는 도구엔 이 패턴을 기본으로 쓰세요.

> ⚠️ **함정 (컨텍스트를 조용히 태우는 것)**: 도구 반환값은 **매 턴 다시 모델에게 전송됩니다.** 대화 히스토리에 남아 있으니까요. 검색 결과 200건(10만 토큰)을 `content` 에 넣으면, 그 뒤 모든 턴에서 10만 토큰을 계속 재전송합니다. 5턴이면 50만 토큰입니다. 비용도 비용이지만 더 큰 문제는 **모델이 그 안에서 길을 잃는다**는 것입니다. 정작 중요한 지시문이 노이즈에 파묻힙니다. 원칙은 하나입니다 — **모델이 다음 판단을 내리는 데 필요한 최소 정보만 `content` 에 넣고, 나머지는 `artifact` 로 빼세요.**

> 💡 **실무 팁**: 반환값 선택 기준
> | 상황 | 반환 |
> |---|---|
> | 짧은 결과, 모델이 그대로 읽으면 됨 | **문자열** |
> | 여러 필드를 모델이 각각 참조해야 함 | **객체** |
> | 결과가 크고 모델은 요약만 필요 | **`ToolMessage` + `artifact`** |
> | 에이전트 상태를 바꿔야 함 | **`Command`** |
> | 도구 결과가 곧 최종 답변 (모델 재가공 불필요) | `returnDirect: true` |

`returnDirect: true` 도 알아두면 좋습니다.

```ts
const fetchOrderStatus = tool(
  ({ order_id }) => `Order ${order_id} is shipped and will arrive in 2 days.`,
  {
    name: "fetch_order_status",
    description: "Fetch the current status of a customer order.",
    schema: z.object({ order_id: z.string() }),
    returnDirect: true,  // ← 도구 출력을 그대로 최종 응답으로
  },
);
```

에이전트 루프를 즉시 끝내고 도구 출력을 그대로 사용자에게 반환합니다. 모델이 한 번 더 요약하는 비용과 지연을 아낍니다. 단, **병렬 호출된 도구가 전부 `returnDirect` 여야** 효과가 납니다.

---

## 6-7. 비동기·외부 API 도구 — 타임아웃과 재시도

도구가 외부 세계를 만지는 순간 두 가지가 필요합니다: **타임아웃**과 **재시도**.

### 타임아웃 — `AbortSignal.timeout`

```ts
async function fetchWithTimeout(url: string, ms: number): Promise<Response> {
  return fetch(url, { signal: AbortSignal.timeout(ms) });
}
```

`AbortSignal.timeout(ms)` 는 Node 18+ 내장입니다. 지정 시간이 지나면 요청을 끊고 `name` 이 `"TimeoutError"` 인 에러를 던집니다.

> ⚠️ **함정**: `fetch` 는 **기본 타임아웃이 없습니다.** 서버가 연결만 열어두고 응답을 안 주면 여러분의 도구는 **영원히 매달립니다.** 에러도 안 나고, 로그도 안 남고, 그냥 조용히 멈춥니다. 사용자는 로딩 스피너만 봅니다. 그러다 요청이 쌓이면 커넥션 풀이 고갈되고 서비스 전체가 죽습니다. **외부 호출에는 예외 없이 타임아웃을 거세요.**

### 재시도 — 지수 백오프와 지터

```ts
async function withRetry<T>(
  fn: () => Promise<T>,
  opts: { maxRetries?: number; initialDelayMs?: number } = {},
): Promise<T> {
  const { maxRetries = 2, initialDelayMs = 500 } = opts;
  let lastError: unknown;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err;
      const status = (err as { status?: number }).status;
      // 4xx(429 제외)는 우리 요청이 잘못된 것 — 재시도해도 똑같이 실패한다
      if (status !== undefined && status >= 400 && status < 500 && status !== 429) {
        throw err;
      }
      if (attempt === maxRetries) break;
      // 지수 백오프 + ±25% 지터
      const delay = initialDelayMs * 2 ** attempt * (0.75 + Math.random() * 0.5);
      await new Promise((r) => setTimeout(r, delay));
    }
  }
  throw lastError;
}
```

세 가지 포인트가 있습니다.

1. **4xx 는 재시도하지 않습니다.** 요청 자체가 잘못된 건데 100번 더 보내도 똑같이 400 입니다. 단 **429(Rate Limit)는 예외** — 이건 기다리면 풀립니다.
2. **지수 백오프**: 500ms → 1s → 2s. 서버가 힘들어하는데 같은 속도로 때리면 더 죽습니다.
3. **지터(±25%)**: 이게 왜 필요할까요? 지터가 없으면 동시에 실패한 100개 요청이 **정확히 같은 시각에** 재시도합니다. 서버는 다시 죽고, 다시 100개가 같은 시각에 재시도합니다. 이걸 thundering herd 라고 합니다.

이 둘을 합친 도구는 이렇게 생겼습니다.

```ts
const exchangeRate = tool(
  async ({ base, quote }) => {
    try {
      const data = await withRetry(async () => {
        const res = await fetchWithTimeout(
          `https://api.frankfurter.app/latest?from=${base}&to=${quote}`,
          3000,
        );
        if (!res.ok) {
          const e = new Error(`HTTP ${res.status}`) as Error & { status: number };
          e.status = res.status;
          throw e;
        }
        return (await res.json()) as { rates: Record<string, number> };
      });
      const rate = data.rates[quote];
      return rate === undefined
        ? `오류: ${quote} 환율을 찾을 수 없습니다. 통화코드를 확인하세요(예: USD, EUR, KRW).`
        : `1 ${base} = ${rate} ${quote}`;
    } catch (err) {
      const reason =
        (err as Error).name === "TimeoutError" ? "응답 시간 초과(3초)" : (err as Error).message;
      return `오류: 환율 API 호출 실패 (${reason}). 잠시 후 다시 시도하거나 사용자에게 알리세요.`;
    }
  },
  {
    name: "get_exchange_rate",
    description:
      "두 통화 사이의 최신 환율을 조회합니다. ISO 4217 통화코드 3자리를 사용합니다. 실패 시 '오류:' 로 시작하는 메시지를 반환합니다.",
    schema: z.object({
      base: z.string().length(3).describe("기준 통화코드. 예: USD"),
      quote: z.string().length(3).describe("대상 통화코드. 예: KRW"),
    }),
  },
);
```

**출력 예시** (외부 API 응답이므로 환율 값은 매번 다릅니다)
```
정상 호출 : 1 USD = 1479.45 KRW
잘못된 통화: 오류: 환율 API 호출 실패 (HTTP 404). 잠시 후 다시 시도하거나 사용자에게 알리세요.
```

두 경우 모두 `throw` 하지 않습니다. 에이전트가 안 죽습니다.

잘못된 통화 쪽 출력을 자세히 보세요. 우리는 `rates[quote]` 가 `undefined` 일 때의 메시지(`"XXX 환율을 찾을 수 없습니다"`)를 준비해 뒀는데, **실제로 나온 건 `HTTP 404` 쪽 메시지**입니다. 이 API 는 모르는 통화코드에 대해 빈 결과가 아니라 **404 를 반환**하기 때문에, `res.ok` 검사에서 먼저 걸린 겁니다. 그리고 404 는 4xx 이므로 `withRetry` 가 **재시도 없이 즉시 포기**했습니다 — 의도한 대로입니다.

> 💡 **실무 팁**: 방금 것이 외부 API 도구의 전형적인 모습입니다. **"실패는 내가 예상한 모양으로 오지 않습니다."** 통화코드가 틀리면 `{ rates: {} }` 가 올 거라 가정했지만 실제로는 404 였습니다. 그래서 외부 API 도구는 `if` 로 알려진 실패를 하나하나 막는 것보다, **`try/catch` 로 전부 감싸고 catch 에서 "오류: ..." 문자열을 반환하는 그물을 치는 것**이 훨씬 중요합니다. 예상 못 한 실패가 그물에 걸려도 에이전트는 계속 돕니다.

> 💡 **실무 팁**: 재시도 로직을 손으로 안 짜고 싶다면 LangChain 내장 **`toolRetryMiddleware`** 가 있습니다([Step 11](../step-11-middleware-builtin/)).
> ```ts
> toolRetryMiddleware({
>   maxRetries: 2,          // 기본 2 (초기 호출 포함 총 3회)
>   backoffFactor: 2.0,     // 지수 백오프 배수
>   initialDelayMs: 1000,
>   maxDelayMs: 60000,
>   jitter: true,           // ±25% 지터
>   retryOn: [/* 에러 생성자 배열 또는 판별 함수 */],
>   onFailure: "continue",  // 'continue'=ToolMessage 반환 / 'error'=예외 재발생
>   tools: [/* 특정 도구만 */],
> })
> ```
> 단 **주의**: 이 미들웨어는 "도구가 `throw` 한 경우" 를 재시도합니다. 위 `exchangeRate` 처럼 도구가 오류를 문자열로 흡수해 버리면 미들웨어 입장에선 성공이라 재시도가 안 됩니다. **도구 안에서 흡수하든가 미들웨어에 맡기든가, 둘 중 하나만 고르세요.** 둘 다 하면 미들웨어가 아무 일도 안 합니다.

---

## 6-8. MCP 도구 연결 맛보기

MCP(Model Context Protocol)는 도구를 **프로세스 밖**에서 제공하는 표준 프로토콜입니다. 남이 만든 도구 서버를 그대로 가져다 쓸 수 있습니다.

```bash
npm install @langchain/mcp-adapters
```

```ts
import { MultiServerMCPClient } from "@langchain/mcp-adapters";

const client = new MultiServerMCPClient({
  math: {
    transport: "stdio",
    command: "node",
    args: ["/path/to/math_server.js"],
  },
  weather: {
    transport: "http",
    url: "http://localhost:8000/mcp",
  },
});

const tools = await client.getTools();
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools,
});
```

전송 방식은 두 가지입니다.

| transport | 동작 | 언제 |
|---|---|---|
| `"stdio"` | 클라이언트가 서버를 **자식 프로세스로 띄우고** 표준입출력으로 통신 | 로컬 도구, 간단한 구성 |
| `"http"` / `"sse"` | HTTP 로 원격 서버와 통신 | 원격 서버, 팀 공용 도구 |

`getTools()` 가 돌려주는 것은 **평범한 LangChain 도구**입니다. 우리가 `tool()` 로 만든 도구와 배열에 섞어 써도 됩니다.

```ts
const mcpTools = await client.getTools();
const bound = model.bindTools([...mcpTools, getWeather]);   // 그냥 섞인다
```

이게 MCP 의 매력입니다. 파일시스템, GitHub, Slack, DB 커넥터 같은 걸 직접 안 짜고 기존 서버를 붙이면 됩니다.

> ⚠️ **함정**: MCP 도구는 실패 시 **`ToolException` 을 던집니다.** 오류 메시지를 모델에게 문자열로 돌려주는 게 아니라 예외를 발생시킵니다. 즉 6-5 의 "throw 하면 에이전트가 죽는다" 가 그대로 적용됩니다. MCP 도구를 쓸 땐 `try/catch` 로 감싸거나 `wrapToolCall` 미들웨어를 반드시 붙이세요. 게다가 MCP 서버는 **남의 코드**입니다 — 여러분이 고칠 수 없습니다. 방어는 순전히 여러분 쪽에서 해야 합니다.

> ⚠️ **함정**: `MultiServerMCPClient` 는 **기본적으로 stateless** 입니다. 도구를 호출할 때마다 새 세션을 만들고, 실행하고, 정리합니다. stdio 전송이라면 **호출마다 프로세스를 띄우고 죽인다**는 뜻입니다. 로컬 개발에선 안 보이지만, 도구를 초당 수십 번 부르는 프로덕션에선 프로세스 생성 비용이 그대로 지연으로 나타납니다. 성능이 문제가 되면 세션 관리 설정을 확인하세요.

> 💡 **실무 팁**: MCP 서버의 도구 이름과 description 은 **남이 쓴 것**입니다. 6-3에서 봤듯 description 품질이 곧 모델의 도구 선택 정확도인데, 그걸 통제할 수 없습니다. MCP 서버 하나가 도구 30개를 뱉는 경우도 흔합니다(6-9의 개수 함정 직행). 프로덕션에선 **`getTools()` 결과를 그대로 쓰지 말고 필터링**하세요 — 실제로 쓸 도구만 골라 배열에 넣는 것만으로도 정확도가 올라갑니다.

---

## 6-9. 도구 설계 원칙 — 개수, 입도, 이름

### 개수 — 적을수록 정확하다

공식 문서의 표현을 그대로 옮기면, 도구가 너무 많으면 "모델을 압도하고(컨텍스트 과부하) 에러가 늘어나며", 너무 적으면 "능력이 제한됩니다".

경험칙은 이렇습니다.

| 도구 개수 | 상태 |
|---|---|
| 1~5개 | 대부분 잘 고른다 |
| 6~15개 | 도구끼리 역할이 겹치면 헷갈리기 시작 |
| 16~30개 | 선택 정확도가 눈에 띄게 떨어짐 |
| 30개 초과 | 도구 정의만으로 컨텍스트를 크게 잡아먹고, 오선택이 일상이 됨 |

> ⚠️ **함정 (조용히 나빠지는 것)**: 도구를 20개 넘게 붙이면 **에러 없이 정확도만 떨어집니다.** 어느 날 도구 하나를 추가했는데 **전혀 관련 없는 다른 기능**의 정확도가 떨어집니다. 에러 로그엔 아무것도 없습니다. 그냥 모델이 가끔 엉뚱한 도구를 부를 뿐입니다. 게다가 도구 정의는 매 요청 컨텍스트에 실리므로, 도구 30개면 사용자가 "안녕" 이라고만 해도 수천 토큰이 나갑니다. **해법은 도구를 줄이거나, 상황에 따라 도구를 동적으로 골라 주는 것(dynamic tool selection)입니다** — 미들웨어로 상태·컨텍스트를 보고 필요 없는 도구를 모델 호출 직전에 빼는 방식이며 [Step 12](../step-12-middleware-custom/) 에서 다룹니다. 도구가 많아지면 [Step 18](../step-18-multi-agent/) 의 서브에이전트로 쪼개는 것도 답입니다.

### 입도 — 만능 도구는 도구가 아니다

```ts
// (나쁨) 만능 도구
const godTool = tool(
  async ({ action, payload }) => `${action} 실행: ${JSON.stringify(payload)}`,
  {
    name: "db_operation",
    description: "데이터베이스 작업을 수행합니다. action 에 원하는 작업을 넣으세요.",
    schema: z.object({
      action: z.string().describe("수행할 작업"),
      payload: z.record(z.string(), z.unknown()).describe("작업에 필요한 데이터"),
    }),
  },
);
```

```ts
// (좋음) 목적별 도구
const findCustomer = tool(
  async ({ email }) => JSON.stringify({ id: 42, email, grade: "GOLD" }),
  {
    name: "find_customer_by_email",
    description: "이메일 주소로 고객 한 명을 찾습니다. 고객 ID, 이메일, 등급을 반환합니다.",
    schema: z.object({ email: z.string().email().describe("고객 이메일 주소") }),
  },
);

const listOrders = tool(
  async ({ customerId, limit }) => JSON.stringify([/* ... */]),
  {
    name: "list_customer_orders",
    description:
      "고객 ID로 그 고객의 주문 목록을 최신순으로 조회합니다. 고객 ID를 모르면 먼저 find_customer_by_email 을 사용하세요.",
    schema: z.object({
      customerId: z.number().int().describe("고객 ID (find_customer_by_email 의 반환값)"),
      limit: z.number().int().min(1).max(50).describe("최대 주문 건수"),
    }),
  },
);
```

같은 질문을 던져 봅시다.

```ts
const question = "kim@example.com 고객의 최근 주문 3건 알려줘";
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[만능 도구 1개]
  tool_calls: [{"action":"getRecentOrdersByEmail","payload":{"email":"kim@example.com","limit":3}}]
  → action 문자열을 모델이 '지어냈습니다'. 우리 DB가 getRecentOrdersByEmail 을 알 리 없습니다.

[목적별 도구 2개]
  tool_calls: [{"name":"find_customer_by_email","args":{"email":"kim@example.com"}}]
  → 이메일로 고객을 먼저 찾는 올바른 순서를 스스로 잡습니다.
```

> ⚠️ **함정 (스키마 검증이 무력화된다)**: 만능 도구의 진짜 문제는 **스키마가 아무것도 검증하지 않는다**는 것입니다. `action: z.string()` 은 어떤 문자열이든 통과시킵니다. `payload: z.record(...)` 는 어떤 객체든 통과시킵니다. 6-2 에서 "스키마는 모델과 코드 사이의 방화벽" 이라고 했는데, 만능 도구는 그 방화벽에 구멍을 뻥 뚫어 놓은 것입니다. 모델이 `action: "deleteAllUsers"` 를 지어내도 스키마는 통과합니다. **입도를 나누는 건 편의 문제가 아니라 안전 문제입니다.**

입도 판단 기준:

| 상황 | 판단 |
|---|---|
| 인자로 "무엇을 할지" 를 받는다 | ❌ 쪼개라. 그건 라우터지 도구가 아니다 |
| description 에 "~인 경우엔 A를, ~인 경우엔 B를" 이 있다 | ❌ 쪼개라 |
| `z.string()` / `z.any()` / `z.record()` 가 주요 필드다 | ❌ 스키마를 좁혀라 |
| 항상 같이 불리는 도구가 두 개 있다 | ⭕ 합치는 걸 고려. 왕복 한 번을 아낀다 |

### 이름 — 형식 문제와 의미 문제는 다르다

공식 문서 권장은 명확합니다: **`snake_case` 를 쓰세요.** provider 가 공백이나 특수문자가 든 이름을 거부하기 때문입니다.

| 이름 | 형식 | 의미 | 고친 이름 |
|---|---|---|---|
| `get weather` | ❌ 공백 → provider 400 | - | `get_weather` |
| `날씨_조회` | ❌ 비ASCII → provider 400 | - | `get_weather` |
| `get-weather` | △ provider 마다 다름 | - | `get_weather` |
| `getWeather` | ⭕ 통과는 됨 | △ 관례 이탈 | `get_weather` |
| `tool1` | ⭕ | ❌ 모델이 절대 못 고름 | 하는 일에 따라 명명 |
| `do_it` | ⭕ | ❌ 'it' 이 뭔지 알 수 없음 | 동사_목적어로 |
| `list_customer_orders_v2` | ⭕ | △ 'v2' 는 노이즈 | `list_customer_orders` |
| `search_products` | ⭕ | ⭕ | (그대로) |

> ⚠️ **함정**: 이름에는 **두 종류의 문제**가 있고 위험도가 정반대입니다.
> - **형식 문제**(공백/한글/특수문자): provider 가 400 으로 거부합니다. **즉시 터지므로 오히려 안전합니다.**
> - **의미 문제**(`tool1`, `do_it`, `v2` 접미사): 에러가 **전혀 안 납니다.** 모델이 조용히 안 부르거나 잘못 부를 뿐입니다.
>
> 특히 `_v2` 접미사가 악질입니다. `list_orders` 와 `list_orders_v2` 가 둘 다 등록돼 있으면 모델은 **반드시 언젠가 구버전을 부릅니다.** 모델에게 "v2가 최신이다" 는 상식이 없습니다. 구버전은 이름을 남기지 말고 배열에서 빼세요.

**이름 규칙 요약**: `동사_목적어` 형태의 snake_case. `get_weather`, `search_products`, `list_customer_orders`, `create_event`. 이름만 읽고 뭘 하는지 모르겠으면 그 이름은 실패입니다.

---

## 6-10. 종합 — 도구 세트로 한 바퀴

지금까지 배운 원칙을 다 적용한 도구 세트를 만들고, 도구 호출 루프를 손으로 한 번 돌려봅시다. (제대로 된 루프 구현은 [Step 07](../step-07-tool-loop/) 입니다.)

```ts
const inventory = new Map<number, number>([[1, 3], [2, 12], [3, 0], [4, 40]]);

const checkStock = tool(
  async ({ productId }) => {
    const qty = inventory.get(productId);
    if (qty === undefined) {
      return `오류: 상품 ID ${productId} 를 찾을 수 없습니다. search_products 로 먼저 ID를 확인하세요.`;
    }
    return JSON.stringify({ productId, stock: qty, inStock: qty > 0 });
  },
  {
    name: "check_stock",
    description:
      "상품 ID로 현재 재고 수량을 조회합니다. 사용자가 '살 수 있나', '재고 있나'를 물으면 사용하세요. 상품 ID를 모르면 먼저 search_products 를 사용하세요.",
    schema: z.object({ productId: z.number().int().describe("상품 ID. search_products 의 id 필드") }),
  },
);

const placeOrder = tool(
  async ({ productId, quantity }) => {
    const qty = inventory.get(productId) ?? 0;
    if (qty < quantity) {
      return `오류: 재고 부족 (요청 ${quantity}개, 재고 ${qty}개). 사용자에게 알리고 수량 조정을 제안하세요.`;
    }
    inventory.set(productId, qty - quantity);
    return JSON.stringify({ orderId: `ORD-${1000 + productId}`, productId, quantity, status: "CREATED" });
  },
  {
    name: "place_order",
    description:
      "상품을 주문합니다. 실제로 재고가 차감되는 되돌릴 수 없는 작업이므로, 반드시 사용자가 명시적으로 주문을 요청했을 때만 사용하세요. 먼저 check_stock 으로 재고를 확인하는 것을 권장합니다.",
    schema: z.object({
      productId: z.number().int().describe("주문할 상품 ID"),
      quantity: z.number().int().min(1).max(10).describe("주문 수량. 1~10개"),
    }),
  },
);
```

루프는 이렇게 생겼습니다.

```ts
import type { StructuredToolInterface } from "@langchain/core/tools";

// 서로 다른 스키마의 도구를 한 배열에 담으므로 공통 인터페이스로 타입을 맞춘다.
// 타입을 안 맞추면 union 이 되어 아래 selected.invoke(call) 이 "호출 불가" 타입이 된다.
const tools: StructuredToolInterface[] = [searchProducts, checkStock, placeOrder];
const toolsByName: Record<string, StructuredToolInterface> = Object.fromEntries(
  tools.map((t) => [t.name, t]),
);

const bound = (await initChatModel("anthropic:claude-sonnet-4-6")).bindTools(tools);

const messages: unknown[] = [
  {
    role: "system",
    content:
      "당신은 쇼핑몰 상담원입니다. 주문 전에는 반드시 재고를 확인하고, 주문 결과를 사용자에게 한국어로 알려주세요.",
  },
  { role: "user", content: "게이밍 노트북 재고 있으면 2대 주문해줘." },
];

for (let turn = 0; turn < 5; turn++) {
  const ai = await bound.invoke(messages as never);
  messages.push(ai);

  if (!ai.tool_calls?.length) {
    console.log("최종 답변:", ai.text ?? ai.content);
    break;
  }

  for (const call of ai.tool_calls) {
    const selected = toolsByName[call.name];
    if (!selected) {
      // 모델이 없는 도구를 지어냈을 때도 대화가 깨지지 않게 ToolMessage 를 채운다
      messages.push(
        new ToolMessage({
          content: `오류: '${call.name}' 라는 도구는 존재하지 않습니다.`,
          tool_call_id: call.id!,
          name: call.name,
        }),
      );
      continue;
    }
    const tm = await selected.invoke(call);
    messages.push(tm);
  }
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[턴 1] 도구 호출 1건
  → search_products({"query":"노트북","limit":5})
  ← [{"id":1,"name":"게이밍 노트북 RTX4060","price":2190000,"stock":3}]

[턴 2] 도구 호출 1건
  → check_stock({"productId":1})
  ← {"productId":1,"stock":3,"inStock":true}

[턴 3] 도구 호출 1건
  → place_order({"productId":1,"quantity":2})
  ← {"orderId":"ORD-1001","productId":1,"quantity":2,"status":"CREATED"}

[턴 4] 최종 답변: 게이밍 노트북 RTX4060 재고가 3대 있어서 2대 주문 완료했습니다.
주문번호는 ORD-1001 입니다. 가격은 대당 2,190,000원입니다.
```

**4턴, 도구 3번 호출.** 모델이 검색 → 재고확인 → 주문 순서를 스스로 잡았습니다. 이 순서를 시스템 프롬프트에 적어서가 아니라 **description 에 적었기 때문**입니다(`"상품 ID를 모르면 먼저 search_products 를 사용하세요"`, `"먼저 check_stock 으로 재고를 확인하는 것을 권장합니다"`).

여기서 눈여겨볼 점 두 가지:

1. **`toolsByName[call.name]` 이 없을 때도 `ToolMessage` 를 채웁니다.** 모델이 존재하지 않는 도구를 지어내는 일은 실제로 일어납니다. 그때 `continue` 로 그냥 넘어가면 tool call 에 대응하는 ToolMessage 가 없어서 다음 `invoke` 가 400 으로 터집니다.
2. **루프에 `turn < 5` 상한이 있습니다.** 없으면 모델이 도구를 계속 부르며 무한히 돕니다. `createAgent` 에도 `recursionLimit` 이라는 같은 개념의 안전장치가 있습니다([Step 08](../step-08-create-agent/)).
3. **`tools` 배열에 `StructuredToolInterface[]` 타입을 명시했습니다.** 생략하면 TypeScript 가 세 도구의 타입을 union 으로 추론하는데, 각 도구의 `invoke` 시그니처가 서로 다른 zod 스키마로 제네릭화되어 있어 union 이 되는 순간 **호출 불가능한 타입**이 됩니다(`TS2349: This expression is not callable`). 도구를 이종(異種)으로 섞어 배열에 담을 땐 공통 인터페이스로 타입을 맞춰 주세요.

> 💡 **실무 팁**: `place_order` 처럼 **되돌릴 수 없는 작업**은 description 에 경고를 쓰는 것만으로는 부족합니다. 위 예시는 모델이 알아서 확인 없이 주문을 넣어 버렸습니다 — 사용자가 "재고 있으면 주문해줘" 라고 했으니 틀린 건 아니지만, 실무에서 결제·삭제·이메일 발송 같은 작업은 **사람의 승인**을 끼워야 합니다. 그게 [Step 13 — Human-in-the-Loop](../step-13-hitl/) 입니다. **위험한 도구는 프롬프트가 아니라 구조로 막으세요.**

---

## 정리

| 개념 | 요점 |
|---|---|
| 도구의 정체 | 함수 + `name` / `description` / `schema`. 모델은 뒤 3개만 본다 |
| 모델의 역할 | 도구를 **실행하지 않는다**. 스키마에 맞는 JSON 을 생성할 뿐 |
| `bindTools([...])` | 도구를 붙인 **새 모델 인스턴스** 반환. 강제가 아니라 선택지 제공 |
| `AIMessage.tool_calls` | `{ id, name, args, type }` 배열. 여러 개 = 병렬 호출 |
| `ToolMessage` | `content`(모델용) + `tool_call_id`(필수) + `name` + `artifact`(코드용) |
| `tool.invoke(args)` | 함수 반환값이 그대로 나옴 |
| `tool.invoke(toolCall)` | `tool_call_id`/`name` 이 채워진 `ToolMessage` 가 나옴 |
| 반환값 선택 | 짧으면 문자열 / 구조적이면 객체 / 크면 `artifact` / 상태 변경은 `Command` / 최종답이면 `returnDirect` |
| 에러 정책 | 모델이 고칠 수 있으면 **문자열 반환**, 고칠 수 없으면 **throw** |
| 외부 API | `AbortSignal.timeout` + 지수 백오프 + 지터. 4xx 는 재시도 금지 |
| MCP | `@langchain/mcp-adapters`, `MultiServerMCPClient.getTools()`. 실패 시 `ToolException` |
| 설계 | 도구는 적게, 목적별로 쪼개고, `snake_case 동사_목적어` |

**핵심 함정 3가지**

1. **모델은 도구를 실행하지 않는다.** `bindTools` 는 "이런 게 있다" 는 통보일 뿐입니다. `tool_calls` 를 읽어 실제로 함수를 부르고, 결과를 `ToolMessage` 로 되돌려주는 건 전부 여러분 코드의 책임입니다. 이걸 안 짜면 앱은 에러 없이 조용히 빈 응답만 냅니다.
2. **`description` 이 부실하면 모델이 아예 안 부른다.** 에러는 나지 않습니다. 모델이 "그건 제가 할 수 없습니다" 라고 정중하게 답할 뿐입니다. 그리고 그때 고쳐야 할 건 시스템 프롬프트가 아니라 description 입니다. 도구가 20개를 넘어가면 이 문제는 "안 부르는 것" 을 넘어 "엉뚱한 걸 부르는 것" 으로 진화합니다 — 역시 에러 없이.
3. **도구가 `throw` 하면 에이전트 전체가 죽는다.** 도구는 외부 세계를 만지므로 **반드시 실패합니다.** 모델이 고칠 수 있는 오류는 `"오류: X. 대신 Y 하세요"` 문자열로 돌려주고, 고칠 수 없는 오류만 throw 하세요. 그리고 실패한 tool call 에도 **`ToolMessage` 는 반드시 채워 보내야** 다음 턴이 400 으로 터지지 않습니다.

**부수 함정**: 도구 이름에 공백/하이픈/한글을 쓰면 provider 가 400 으로 거부합니다(→ `snake_case`). `fetch` 는 기본 타임아웃이 없어 영원히 매달릴 수 있습니다(→ `AbortSignal.timeout`). 큰 결과를 `content` 에 넣으면 매 턴 재전송되어 컨텍스트를 태웁니다(→ `artifact`). `toolChoice` 를 루프에 걸면 최종 답변이 영영 안 나옵니다. `ToolRuntime.toolCallId` 는 타입상 `string` 필수지만 에이전트 밖에서 직접 `invoke` 하면 `undefined` 입니다(→ `config.toolCallId ?? config.toolCall?.id ?? ""`). 이종 도구를 한 배열에 담을 땐 `StructuredToolInterface[]` 로 타입을 맞추지 않으면 `invoke` 가 호출 불가 타입이 됩니다.

---

## 연습문제

1. `tool()` 로 `convert_temperature` 도구를 만드세요. 섭씨 ↔ 화씨를 변환하며, schema 는 `value`(number) 와 `from`(`"C"` 또는 `"F"`)입니다. `from` 은 반드시 `z.enum` 을 쓰고, 모든 필드에 `.describe()` 를 붙이세요. 모델 없이 직접 `invoke` 해서 결과를 출력하세요.
2. `mysteryTool`(`name: "info"`, `description: "정보를 반환합니다."`)은 모델이 잘 안 부릅니다. name 과 description 을 "무엇을 / 언제 / 제약" 3요소를 갖춰 고치고, 고치기 전후로 `"서울 지하철 2호선 첫차 시간 알려줘"` 를 던져 `tool_calls` 개수를 비교 출력하세요.
3. 도구 2개(`get_weather`, `get_time`)를 붙이고 `"서울 날씨랑 지금 시각 알려줘"` 를 물어 `tool_calls` 를 관찰하세요. 각각의 `id`/`name`/`args` 를 출력하고, 이어서 `toolChoice: "get_weather"` 로 강제했을 때 어떻게 달라지는지 비교하세요.
4. 0으로 나누면 `throw` 하는 `divideUnsafe` 를 고쳐, 실패해도 throw 하지 않고 **모델이 읽고 스스로 고칠 수 있는** 오류 문자열을 반환하는 `divideSafe` 를 만드세요. 모델에게 `"10을 0으로 나눠줘"` 를 물은 뒤 `ToolMessage` 를 돌려주고 모델의 후속 반응까지 출력하세요. (`tool_call_id` 를 반드시 채워야 하는 이유는 무엇일까요?)
5. `artifact` 를 쓰는 `fetch_logs` 도구를 만드세요. 로그 500줄을 생성하되 `content` 에는 `"총 500줄, ERROR N건"` 같은 요약만, `artifact` 에는 500줄 전체를 넣으세요. `ToolRuntime` 에서 tool call id 를 받아 `ToolMessage` 의 `tool_call_id` 에 채우고, 실행 후 content 와 artifact 의 크기 차이를 출력해 비교하세요. **함정**: 먼저 `config.toolCallId` 만 써서 짜 본 뒤 완성된 `ToolMessage` 의 `tool_call_id` 를 찍어 보세요. 무슨 값이 나오나요? 왜 그럴까요?
6. `https://api.frankfurter.app/latest?from=USD&to=KRW` 를 호출하는 도구를 만드세요. `AbortSignal.timeout` 으로 2초 타임아웃, 최대 2회 지수 백오프 재시도, 그래도 실패하면 `"오류: ..."` 문자열 반환(throw 금지). 타임아웃을 1ms 로 바꿔 일부러 실패시켜 동작을 검증하세요.
7. 만능 도구 `calendar`(`schema: { op: z.string(), data: z.record(...) }`)를 목적별 도구 3개로 쪼개세요. 쪼갠 뒤 `"내일 오후 3시에 팀 회의 1시간 잡아줘"` 를 물어 어떤 도구를 어떤 args 로 부르는지 만능 도구 버전과 나란히 비교 출력하세요.
8. 다음 이름들에서 provider 가 거부할 이름과 의미상 나쁜 이름을 **구분해서** 골라내고, 각각 고친 표를 출력하세요: `"get weather"`, `"getWeather"`, `"get-weather"`, `"날씨_조회"`, `"search_products"`, `"tool1"`, `"do_it"`, `"list_customer_orders_v2"`. (a) 형식 문제와 (b) 의미 문제는 위험도가 정반대라는 점을 답에 반영하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 07 — 도구 호출 루프 직접 구현](../step-07-tool-loop/)

이번 스텝에서 도구 하나하나를 잘 만드는 법을 배웠습니다. 6-10에서 살짝 맛본 "모델 호출 → tool_calls 읽기 → 실행 → ToolMessage 추가 → 다시 모델 호출" 루프를 다음 스텝에서 제대로 짭니다. `tool_call_id` 를 하나만 빠뜨려도 어떻게 400 이 나는지, 무한 루프를 어떻게 막는지, 병렬 호출을 `Promise.all` 로 어떻게 처리하는지를 손으로 겪습니다. 그걸 다 겪고 나면 [Step 08](../step-08-create-agent/) 의 `createAgent` 가 무엇을 대신해 주는 것인지 정확히 알게 됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(6-1 ~ 6-10)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `docs/reference/langchain/project/` 의 의존성 위에서 돌아갑니다. 실행 전에 `project/.env` 에 `ANTHROPIC_API_KEY` 를 넣으세요.

```bash
npx tsx docs/reference/langchain/step-06-tools/practice.ts
```

OpenAI 로 바꾸려면 `MODEL_ID` 환경변수를 `openai:gpt-5.5` 로 두고 `OPENAI_API_KEY` 를 설정하면 세 파일 모두 그대로 동작합니다. 도구 정의는 provider 중립이라 코드를 한 줄도 안 고쳐도 됩니다 — 단 6-9에서 말한 이름 규칙(공백·비ASCII 금지)은 provider 마다 엄격도가 다르니, `snake_case` 를 지켰다면 문제없습니다.

**주의**: `practice.ts` 는 모델 API 를 10회 이상 호출합니다. 토큰 비용이 발생하며 전체 실행에 1~2분 걸립니다. 특정 절만 보고 싶으면 파일 하단 `main()` 에서 원하는 `step6_N()` 만 남기세요.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[6-1] ~ [6-10]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[6-1]` 이 이 파일의 심장입니다. `sideEffectCounter` 라는 모듈 레벨 변수를 도구 함수 안에서 증가시키고, 모델 호출 **직후** 그 값을 찍습니다. `0` 이 나옵니다. 그리고 우리가 직접 `addTool.invoke(call.args)` 를 부른 **뒤에야** `1` 이 됩니다. 이 두 숫자 사이가 "모델은 도구를 실행하지 않는다" 의 전부입니다.
- `[6-3]` 의 `stockPriceBad` 와 `stockPriceGood` 은 **함수 본문이 글자 하나까지 동일합니다**(`async ({ ticker }) => \`${ticker}: 71800\``). 바뀐 것은 `name` 과 `description` 문자열뿐인데 `tool_calls` 개수가 0과 1로 갈립니다. 두 결과를 나란히 출력하도록 짜여 있으니 한 화면에서 비교하세요.
- `[6-5]` 의 (1)번 블록은 `try/catch` 로 감싸 놓았습니다. **이 try/catch 를 지우면 프로세스가 그 자리에서 죽고 뒤 절들이 실행되지 않습니다.** 일부러 한 번 지워서 겪어 보는 것도 학습에 좋지만, 그 뒤엔 되돌려 놓으세요.
- `[6-7]` 의 `exchangeRate` 는 실제 외부 API(frankfurter.app)를 호출합니다. 네트워크가 없거나 API 가 죽어 있으면 `오류: 환율 API 호출 실패 (...)` 문자열이 나오는데, **그게 정상 동작입니다** — 예외로 안 터지고 문자열이 나오는 것 자체가 이 절이 증명하려는 것입니다.
- `[6-8]` 의 MCP 코드는 **주석 처리되어 있습니다.** `@langchain/mcp-adapters` 가 프로젝트 기본 의존성이 아니기 때문입니다. `npm install @langchain/mcp-adapters` 후 주석을 풀면 동작합니다. 주석을 풀지 않으면 대체 함수가 안내 메시지만 출력합니다.
- `[6-10]` 의 루프에는 `toolsByName[call.name]` 이 `undefined` 일 때 `ToolMessage` 를 채우는 분기가 들어 있습니다. 지금은 절대 안 타는 죽은 코드처럼 보이지만, Step 07 에서 왜 이게 필수인지 알게 됩니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록과 빈 `exerciseN()` 함수로 구분되어 있으니, 함수 본문에 직접 코드를 써 넣고 파일을 통째로 실행해 검증하면 됩니다.

- `[문제 2]` 와 `[문제 4]` 는 고쳐야 할 대상(`mysteryTool`, `divideUnsafe`)이 **이미 작성되어 제공됩니다.** 지우지 말고 그대로 두세요 — 고친 버전과 나란히 비교 출력하는 것이 문제의 요구사항입니다.
- `[문제 4]` 의 괄호 안 질문 "`tool_call_id` 를 반드시 채워야 합니다 — 왜일까요?" 가 이 파일에서 가장 중요한 대목입니다. 답을 보기 전에 일부러 `tool_call_id` 를 엉뚱한 값으로 넣고 다음 `invoke` 를 돌려 무슨 에러가 나는지 직접 겪어 보세요.
- `[문제 6]` 의 힌트 "타임아웃으로 끊긴 에러의 `name` 은 `"TimeoutError"` 입니다" 는 그냥 힌트가 아니라 **검증 방법**입니다. 타임아웃을 1ms 로 두면 100% 실패하므로, API 키나 네트워크 상태와 무관하게 재시도 로직이 도는 것을 확인할 수 있습니다.
- `[문제 8]` 만 모델 호출이 필요 없습니다. 순수하게 판단과 출력만 하는 문제이니 API 키 없이도 풀 수 있습니다.
- 파일을 그대로 실행하면 절 제목만 출력되고 아무 내용도 안 나옵니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 "왜 이렇게 하는가" 와 함정 포인트가 적혀 있습니다.

- `[정답 1]` 의 포인트는 `z.enum(["C", "F"])` 입니다. 정답 코드 끝에서 일부러 `from: "켈빈"` 을 넣어 보는데, 이 값은 **도구 함수에 도달조차 못 하고** 차단됩니다. `z.string().describe("C 또는 F")` 였다면 그냥 통과해서 함수 안에서 처리해야 했을 겁니다. "프롬프트로 부탁하지 말고 스키마로 강제하라" 의 실물입니다.
- `[정답 4]` 가 이 파일의 하이라이트입니다. 해설 주석에 `tool_call_id` 가 필수인 이유가 적혀 있습니다 — provider 는 "`tool_calls` 가 있는 `AIMessage` 뒤에는 그 각각에 대응하는 `ToolMessage` 가 반드시 와야 한다"고 요구합니다. **도구가 실패해도 ToolMessage 는 채워 보내야 합니다.** "에러니까 건너뛰자" 가 다음 턴을 400으로 터뜨리는 전형적인 실수입니다. 정답 코드 끝에 `tool_call_id: "존재하지-않는-id"` 인 ToolMessage 를 만들어 보여주는 블록이 있습니다.
- `[정답 5]` 는 content 와 artifact 의 **글자 수를 실제로 세어 절감률을 출력**합니다. 로그 500줄 기준으로 대략 95% 이상 절감되는 것을 숫자로 확인할 수 있습니다. 이 숫자에 5턴을 곱하면 왜 artifact 를 써야 하는지가 분명해집니다.
- `[정답 6]` 의 해설 주석에 **`toolRetryMiddleware` 와 충돌하는 이유**가 적혀 있습니다. 도구가 오류를 문자열로 흡수하면 미들웨어 입장에선 "성공"이라 재시도가 안 됩니다. 둘 다 걸어놓고 "왜 재시도가 안 되지?" 하며 헤매는 사람이 많습니다. 둘 중 하나만 고르세요.
- `[정답 7]` 은 만능 도구가 뱉는 `op` 값이 **매 실행마다 다르다**는 걸 보여줍니다. `create`, `add`, `createEvent`, `새일정`… 모델은 매번 다른 문자열을 지어냅니다. 우리 코드가 그 변형을 전부 `if` 로 받아내야 하고, `data` 안의 필드명도 보장이 없습니다. 즉 만능 도구는 스키마 검증을 스스로 무력화한 도구입니다.
- `[정답 8]` 은 `console.table` 로 형식/의미 두 축을 나눈 표를 출력합니다. 결론은 주석에 있습니다 — **형식 위반은 400으로 즉시 터져서 오히려 안전하고, 의미 위반은 에러 없이 조용히 정확도만 갉아먹어서 훨씬 위험합니다.** `_v2` 접미사가 왜 최악인지도 여기 적혀 있습니다.

```ts file="./solution.ts"
```
