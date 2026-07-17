# Step 07 — 도구 호출 루프 직접 구현

> **학습 목표**
> - 에이전트가 **모델 + 도구 + `while` 루프**일 뿐임을 코드로 확인한다
> - `bindTools` → `invoke` → `tool_calls` → 도구 실행 → `ToolMessage` → 반복 의 한 바퀴를 손으로 돌린다
> - 루프 종료 조건을 **`tool_calls` 기준으로만** 판정한다 (`content` 유무로 판정하지 않는다)
> - 한 `AIMessage` 에 담긴 여러 `tool_calls` 를 `Promise.all` 로 **병렬 실행**하고, 짝을 `tool_call_id` 로 맞춘다
> - **최대 반복 횟수**로 무한 루프를 막고, `recursionLimit` 의 정체를 이해한다
> - 도구 실패를 예외로 터뜨리지 않고 **`ToolMessage` 로 모델에게 되돌려** 회복시킨다
> - 직접 만든 ~80줄 미니 에이전트가 `createAgent` 와 **같은 결과**를 내는 것을 확인한다
>
> **선행 스텝**: [Step 06 — 도구(Tool) 정의](../step-06-tools/)
> **예상 소요**: 80분

[Step 06](../step-06-tools/) 에서 `tool()` 로 도구를 정의하고 `bindTools` 로 모델에 붙였습니다. 그리고 모델이 `tool_calls` 를 돌려주는 것까지 봤습니다. 거기서 이야기가 끊겼습니다 — **그래서 누가 그 도구를 실행하나요?**

답은 "우리"입니다. 그리고 실행 결과를 모델에게 돌려주고, 모델이 또 다른 도구를 요청하면 또 실행하고, 더 이상 요청이 없을 때까지 반복하는 것 — **그게 에이전트의 전부입니다.** 이번 스텝에서는 `createAgent` 를 쓰지 않고 이 루프를 손으로 만듭니다.

왜 이런 우회를 하냐면, 다음 스텝부터 나올 모든 기능(스트리밍, 메모리, 미들웨어, HITL, 멀티 에이전트)이 **전부 이 루프의 특정 지점에 무언가를 끼워 넣는 일**이기 때문입니다. 루프를 모르면 그 기능들은 외워야 할 마법 주문이 됩니다. 루프를 알면 "아, 그건 도구 실행 직전에 끼어드는 거구나" 하고 지도 위에 찍을 수 있습니다. 이 스텝을 마치면 `createAgent` 의 동작을 **예측**할 수 있어야 합니다.

이번 스텝에서 쓸 도구는 3개입니다. `get_weather`(도시 → 날씨), `get_population`(도시 → 인구, 일부러 1초 지연), `calculate`(사칙연산). 데이터는 코드 안의 상수 테이블이라 결과를 눈으로 검산할 수 있습니다.

---

## 7-1. 에이전트 = 모델 + 도구 + 루프. 그게 전부다

에이전트를 정의하는 문장은 공식 문서에도 한 줄입니다.

> "An agent is a model calling tools in a loop until a given task is complete."
> — [LangChain Agents 문서](https://docs.langchain.com/oss/javascript/langchain/agents)

이 문장을 의사코드로 옮기면 이렇습니다. 정말 이게 전부입니다.

```
messages = [사용자 질문]
반복:
    ai = 모델.invoke(messages)          # 모델에게 물어본다
    messages.push(ai)
    만약 ai.tool_calls 가 비었으면:
        return ai.text                  # 끝
    각 tool_call 마다:
        결과 = 도구를 실행한다           # ← 이 줄만 우리가 한다
        messages.push(ToolMessage(결과))
```

그런데 이 의사코드에서 가장 중요한 줄은 주석이 붙은 그 줄입니다. **모델은 도구를 실행하지 않습니다.** 실행을 "요청"할 뿐입니다.

당연한 말 같지만 실감하기 전까지는 계속 헷갈립니다. 직접 봅시다.

```ts
const modelWithTools = model.bindTools(tools);

const response = await modelWithTools.invoke([
  new HumanMessage("서울 날씨 어때?"),
]);

console.log("응답 타입      :", response.constructor.name);
console.log("response.text  :", JSON.stringify(response.text));
console.log("tool_calls     :", JSON.stringify(response.tool_calls, null, 2));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
응답 타입      : AIMessage
response.text  : "서울의 날씨를 확인해드리겠습니다."
tool_calls     : [
  {
    "name": "get_weather",
    "args": {
      "city": "서울"
    },
    "id": "toolu_01A9x2mKp3nQr7sT8uVwXyZa",
    "type": "tool_call"
  }
]
```

여기서 **날씨는 아직 조회되지 않았습니다.** `WEATHER_DB` 는 열린 적조차 없습니다. 모델이 한 일은 `{name: "get_weather", args: {city: "서울"}, id: "toolu_..."}` 라는 **JSON 조각을 만든 것**뿐입니다.

생각해 보면 당연합니다. 모델은 HTTP 너머 남의 서버에 있습니다. 우리 프로세스의 `getWeather` 함수를 실행할 방법이 물리적으로 없습니다. 모델이 할 수 있는 건 "이 함수를 이 인자로 불러줘" 라고 **말하는 것**뿐이고, 그 말을 듣고 실제로 함수를 부르는 건 **우리 애플리케이션 코드**입니다.

`tool_calls` 의 각 원소는 이 형태입니다. `@langchain/core` 의 `ToolCall` 타입이며, 이 shape 은 결정적입니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `name` | `string` | 호출할 도구 이름 |
| `args` | `Record<string, any>` | 스키마에 맞게 파싱된 인자 |
| `id` | `string \| undefined` | 이 호출의 고유 ID. **결과를 되돌릴 때 이 값이 열쇠** |
| `type` | `"tool_call" \| undefined` | 판별용 태그 |

> 💡 **실무 팁**: `id` 의 접두사는 provider 마다 다릅니다. Anthropic 은 `toolu_...`, OpenAI 는 `call_...` 로 시작합니다. **이 값을 절대 우리가 지어내면 안 됩니다.** 모델이 준 것을 그대로 되돌려줘야 합니다. 로그를 볼 때 이 접두사만 봐도 어느 provider 를 탔는지 알 수 있어서 디버깅에 유용합니다.

> ⚠️ **함정**: `bindTools` 는 모델 객체를 **변경하지 않습니다.** 도구가 붙은 **새 객체를 반환**합니다.
> ```ts
> model.bindTools(tools);              // ❌ 반환값을 버렸다. model 은 그대로다
> const m = model.bindTools(tools);    // ✅
> ```
> 첫 줄처럼 쓰고 나서 `model.invoke(...)` 를 부르면 에러는 나지 않습니다. 모델은 도구가 있는 줄도 모르니 그냥 "죄송하지만 실시간 날씨 정보에 접근할 수 없습니다" 같은 그럴듯한 답변을 합니다. **에러 없이 조용히 에이전트가 아닌 것이 됩니다.** `tool_calls` 가 항상 비어 있다면 이걸 가장 먼저 의심하세요.

---

## 7-2. 손으로 만드는 ReAct 루프

이제 한 바퀴를 손으로 돌립니다. `while` 을 쓰지 않고 일부러 펼쳐서 씁니다. 질문은 도구가 **여러 번, 여러 종류** 필요하도록 골랐습니다.

> "서울 인구와 부산 인구를 더하면 몇 명이야?"

이걸 풀려면 `get_population` 을 두 번 부르고, 그 결과로 `calculate` 를 한 번 불러야 합니다. 최소 **3바퀴**입니다.

### 1바퀴 — 모델에게 물어본다

```ts
const modelWithTools = model.bindTools(tools);
const messages: BaseMessage[] = [
  new HumanMessage("서울 인구와 부산 인구를 더하면 몇 명이야?"),
];

const ai1 = await modelWithTools.invoke(messages);
messages.push(ai1);   // ★ AIMessage 를 반드시 배열에 넣는다
```

`messages.push(ai1)` 을 빠뜨리면 안 됩니다. 모델 API 는 **stateless** 입니다. 이전 호출을 기억하지 못하므로, 매 바퀴마다 지금까지의 대화 전체를 다시 보내야 합니다. **에이전트의 "기억"은 결국 이 `messages` 배열 하나가 전부입니다.**

### 1바퀴 — 우리가 도구를 실행하고 결과를 되돌린다

```ts
for (const toolCall of ai1.tool_calls ?? []) {
  const selected = findTool(toolCall.name);           // 이름으로 도구를 찾고
  const output = await selected.invoke(toolCall.args); // 실행한다 → 순수 결과값(문자열)

  messages.push(
    new ToolMessage({
      content: String(output),
      tool_call_id: toolCall.id!,   // ★ 모델이 준 id 를 그대로
      name: toolCall.name,
    }),
  );
}
```

`ToolMessage` 의 필드는 이렇습니다 (`@langchain/core` 의 `ToolMessageFields`).

| 필드 | 필수 | 설명 |
|---|---|---|
| `content` | ✅ | 도구가 돌려준 결과. **모델이 실제로 읽는 유일한 필드** |
| `tool_call_id` | ✅ | 어떤 `tool_call` 에 대한 답인지. `toolCall.id` 와 **정확히 같아야** 함 |
| `name` | ❌ | 도구 이름. 로그 가독성용 |
| `status` | ❌ | `"success"` \| `"error"` |
| `artifact` | ❌ | 모델에게 안 보내는 부가 데이터 |

`tool_call_id` 가 이 스텝의 심장입니다. **"어떤 요청에 대한 답인지" 를 잇는 유일한 끈**입니다.

> ⚠️ **함정 (이번 스텝 1번 함정)**: `ToolMessage` 를 `messages` 에 넣지 않고 다음 `invoke` 를 부르면 **provider 가 400 을 던집니다.**
> ```ts
> const ai = await modelWithTools.invoke(messages);
> messages.push(ai);
> // ← 여기서 ToolMessage 를 안 넣고
> await modelWithTools.invoke(messages);   // 💥 400 Bad Request
> ```
> Anthropic 은 대략 이렇게 말합니다: `messages: tool_use ids were found without tool_result blocks immediately after`. OpenAI 는 이렇게 말합니다: `An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'`.
>
> 주목할 점 두 가지입니다. 첫째, **이건 LangChain 이 아니라 provider 가 내는 에러입니다.** LangChain 은 우리 `messages` 배열을 그대로 직렬화해 보낼 뿐, 짝이 맞는지 검사해 주지 않습니다. 그래서 에러 메시지도 provider 말투입니다. 둘째, **`tool_calls` 가 N개면 `ToolMessage` 도 정확히 N개여야 합니다.** "도구 하나가 실패했으니 그건 빼고 보내자" 는 생각이 바로 이 에러를 만듭니다 (7-6 에서 다룹니다).
>
> 그나마 이 함정은 시끄럽게 터져서 다행입니다. 진짜 무서운 건 7-3 입니다.

### 2바퀴, 3바퀴 — 같은 걸 반복한다

```ts
const ai2 = await modelWithTools.invoke(messages);   // 도구 결과가 붙은 배열을 통째로
messages.push(ai2);
// → 이번엔 calculate 를 요청할 것이다. 도구를 실행하고 ToolMessage 를 또 push...

const ai3 = await modelWithTools.invoke(messages);
messages.push(ai3);
console.log("최종 답변:", ai3.text);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[1바퀴] 모델이 요청한 도구: get_population, get_population
         └ 실행 결과: 서울의 인구는 9,386,000명입니다.
         └ 실행 결과: 부산의 인구는 3,293,000명입니다.

[2바퀴] 모델이 요청한 도구: calculate
         └ 실행 결과: 12679000

[3바퀴] tool_calls 개수: 0
최종 답변: 서울 인구 9,386,000명과 부산 인구 3,293,000명을 더하면 총 12,679,000명입니다.
```

최종 `messages` 배열은 이렇게 생겼습니다.

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
----- 7-2 최종 메시지 배열 (총 7개) -----
[0] Human    | 서울 인구와 부산 인구를 더하면 몇 명이야?
[1] AI       | text="두 도시의 인구를 조회하겠습니다." tool_calls=[get_population({"city":"서울"}), get_population({"city":"부산"})]
[2] Tool     | name=get_population id=toolu_01Ab... status=success content="서울의 인구는 9,386,000명입니다."
[3] Tool     | name=get_population id=toolu_01Cd... status=success content="부산의 인구는 3,293,000명입니다."
[4] AI       | text="" tool_calls=[calculate({"a":9386000,"b":3293000,"op":"add"})]
[5] Tool     | name=calculate id=toolu_01Ef... status=success content="12679000"
[6] AI       | text="서울 인구 9,386,000명과 부산 인구 3,293,000명을 더하면 총 12,679,000명입니다." tool_calls=[]
```

**이게 에이전트의 전부입니다.** `Human → AI → Tool → Tool → AI → Tool → AI`. 이 손동작을 `while` 로 감싸면 그게 에이전트고, 그 `while` 에 이름을 붙인 게 `createAgent` 입니다.

한 가지 더 눈여겨볼 것: `[4]` 의 `text` 가 빈 문자열입니다. 반면 `[1]` 은 text 와 tool_calls 를 **둘 다** 갖고 있습니다. 이 비일관성이 다음 절의 함정으로 이어집니다.

> 💡 **실무 팁 — `tool.invoke(toolCall)` 지름길**: 위에서는 `invoke(toolCall.args)` 로 순수 결과값을 받아 `ToolMessage` 를 손으로 만들었습니다. 원리를 보려고 그랬습니다. 사실 LangChain 도구는 **`toolCall` 객체를 통째로 받으면 `ToolMessage` 를 직접 돌려줍니다.**
> ```ts
> // 인자만 넘기면 → 순수 결과값 (string)
> const raw = await getWeather.invoke({ city: "서울" });
>
> // toolCall 을 통째로 넘기면 → ToolMessage
> const msg = await getWeather.invoke({
>   name: "get_weather",
>   args: { city: "부산" },
>   id: "call_demo_123",
>   type: "tool_call",
> });
> console.log(msg.constructor.name);  // "ToolMessage"
> console.log(msg.tool_call_id);      // "call_demo_123"
> ```
> 타입 수준에서도 `TInput extends ToolCall ? ToolMessage : TOutput` 로 갈립니다. `tool_call_id` 를 손으로 채우다 틀릴 일이 없어지므로 **실전에서는 이 형태를 쓰세요.** 다만 지금은 루프를 배우는 중이니, 무엇이 자동화되고 있는지 알고 쓰는 게 중요합니다.

---

## 7-3. 루프 종료 조건 — `tool_calls` 가 비면 끝

의사코드의 이 줄을 다시 봅시다.

```
만약 ai.tool_calls 가 비었으면:
    return ai.text
```

정답은 이것 하나뿐입니다. 그런데 여기서 아주 그럴듯한 오답이 하나 있습니다.

```ts
// ✅ 올바른 종료 판정
const isDone = (m: AIMessage) => (m.tool_calls ?? []).length === 0;

// ❌ 틀린 종료 판정 — "모델이 뭐라도 말했으면 끝난 거 아냐?"
const wrongIsDone = (m: AIMessage) => m.text.length > 0;
```

두 함수를 실제 응답에 물려 봅시다.

```ts
const noTool = await modelWithTools.invoke([new HumanMessage("안녕! 너는 누구야?")]);
const needTool = await modelWithTools.invoke([new HumanMessage("제주 날씨 알려줘")]);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
[도구 불필요] tool_calls 길이: 0
[도구 불필요] text 있음?     : true

[도구 필요]   tool_calls 길이: 1
[도구 필요]   text 있음?     : true   ← text 가 있어도 tool_calls 가 있으면 아직 안 끝났습니다
[도구 필요]   text 내용    : "제주 날씨를 확인해볼게요."

isDone(noTool)   = true    ← 종료
isDone(needTool) = false   ← 계속
wrongIsDone(needTool) = true    ← text 로 판단하면 도구를 실행도 안 하고 끝내버립니다
```

`needTool` 이 문제입니다. **`text` 와 `tool_calls` 가 동시에 있습니다.**

이건 버그가 아니라 정상입니다. `AIMessage` 의 `content` 는 문자열이 아니라 **블록의 배열**이라서, `[{type: "text", ...}, {type: "tool_use", ...}]` 처럼 둘이 공존할 수 있습니다. 모델은 "제주 날씨를 확인해볼게요" 라고 말하면서 동시에 도구를 부르는 게 자연스럽습니다. 사람도 그렇게 합니다.

> ⚠️ **함정 (이번 스텝 2번 함정 — 가장 조용한 것)**: **종료 조건을 `content`/`text` 유무로 판단하면 안 됩니다.** `wrongIsDone` 을 쓰면 도구를 **실행조차 하지 않고** 루프를 끝내고, `"제주 날씨를 확인해볼게요."` 를 최종 답변으로 사용자에게 내놓습니다.
>
> 이게 왜 최악이냐면 — **에러가 안 납니다. 로그도 깨끗합니다. 400 도 안 뜹니다.** 테스트에서 "도구 안 부르는 질문"만 던져봤다면 통과합니다. 사용자만 날씨를 영영 못 받습니다. 게다가 답변이 "확인해볼게요" 라서 언뜻 정상 응답처럼 보입니다.
>
> 반대 방향의 오답도 있습니다. `text` 가 비었으면 계속 돌리는 코드는, 모델이 도구 없이 빈 답변을 준 순간 **무한 루프**에 빠집니다.
>
> **종료는 `tool_calls` 하나로만 판단하세요. `text` 는 종료 여부와 무관합니다.**

### 그 외의 종료 조건들

`tool_calls` 가 비는 것이 **정상 종료**이고, 실무에서는 아래 조건들이 추가로 붙습니다.

| 종료 조건 | 성격 | 대응 |
|---|---|---|
| `tool_calls` 가 빔 | 정상 종료 | `ai.text` 를 답으로 반환 |
| 최대 반복 횟수 도달 | 비정상 | throw 하거나 "미완료" 표시. **조용히 넘기면 안 됨** (7-5) |
| 특정 도구 호출 (`final_answer` 등) | 정상 | 그 도구를 종료 신호로 약속 |
| 사용자 중단 (`AbortSignal`) | 취소 | 진행 중 요청 정리 |
| 예산/토큰 초과 | 비정상 | 부분 결과 반환 또는 요약 후 재개 |
| HITL 승인 대기 | 일시 정지 | 상태 저장 후 중단 → 나중에 재개 ([Step 13](../step-13-hitl/)) |

지금 우리 루프는 위의 1번과 2번만 다룹니다. 나머지는 각 스텝에서 다시 만납니다. 중요한 건 **"루프를 빠져나온 이유가 여러 개"** 라는 사실이고, 이걸 구분하지 않으면 7-5 의 함정에 빠집니다.

---

## 7-4. 병렬 도구 호출 — 한 `AIMessage` 에 `tool_calls` 가 여러 개일 때

7-2 의 출력을 다시 보세요.

```
[1] AI | tool_calls=[get_population({"city":"서울"}), get_population({"city":"부산"})]
```

**한 `AIMessage` 에 `tool_calls` 가 2개** 들어 있었습니다. 모델은 서로 의존하지 않는 도구들을 **한 번에** 요청할 수 있습니다. 서울 인구와 부산 인구는 서로 관계가 없으니 굳이 순서대로 물어볼 이유가 없습니다.

이걸 어떻게 실행하느냐로 성능이 갈립니다. `get_population` 에는 1초 지연이 걸려 있습니다.

```ts
// (A) 순차 실행 — 도구 개수 × 1초
for (const toolCall of ai.tool_calls ?? []) {
  const output = await findTool(toolCall.name).invoke(toolCall.args);
  sequential.push(new ToolMessage({
    content: String(output),
    tool_call_id: toolCall.id!,
    name: toolCall.name,
  }));
}

// (B) 병렬 실행 — 약 1초
const parallel = await Promise.all(
  (ai.tool_calls ?? []).map(async (toolCall) => {
    const output = await findTool(toolCall.name).invoke(toolCall.args);
    return new ToolMessage({
      content: String(output),
      tool_call_id: toolCall.id!,   // ← 클로저로 잡은 toolCall 에서 직접
      name: toolCall.name,
    });
  }),
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다. 시간은 환경에 따라 다릅니다)
```
tool_calls 개수: 3
  - get_population({"city":"서울"}) id=toolu_01Ab...
  - get_population({"city":"부산"}) id=toolu_01Cd...
  - get_population({"city":"제주"}) id=toolu_01Ef...

(A) 순차 실행: 3021ms
(B) 병렬 실행: 1008ms  ← 약 1/3
```

도구가 3개면 3배, 10개면 10배 차이입니다. 실제 도구는 대부분 네트워크 I/O(DB 조회, API 호출)라서 이 차이가 그대로 사용자 대기 시간이 됩니다.

> ⚠️ **함정 (이번 스텝 3번 함정)**: 병렬 실행에서 **`ToolMessage` 의 짝을 배열 인덱스로 맞추면 안 됩니다.**
> ```ts
> // ❌ 위험한 코드
> const outputs = await Promise.all(toolCalls.map((c) => findTool(c.name).invoke(c.args)));
> const toolMessages = outputs.map((output, i) => new ToolMessage({
>   content: String(output),
>   tool_call_id: toolCalls[i].id!,   // ← 인덱스로 짝 맞추기
>   name: toolCalls[i].name,
> }));
> ```
> "`Promise.all` 은 순서를 보장하잖아요?" — 맞습니다. `Promise.all` 이 보장하는 건 **결과 배열의 순서**입니다 (실행 완료 순서가 아니라). 그래서 위 코드는 **지금은 동작합니다.** 그게 함정입니다.
>
> 문제는 그 다음입니다. 첫째, provider 는 **배열 순서가 아니라 `tool_call_id` 로 짝을 찾습니다.** id 만 맞으면 `ToolMessage` 를 어떤 순서로 넣든 됩니다. 반대로 순서가 맞아도 id 가 틀리면 400 입니다. 즉 **배열 순서는 애초에 짝짓기에 쓰이지 않습니다.**
>
> 둘째, 진짜 사고는 `Promise.all` 을 벗어날 때 납니다. 실무에서는 리팩터링이 들어옵니다 — 도구 실행을 큐에 넣거나, `Promise.allSettled` 로 바꾸거나, 일부는 캐시에서 즉답하고 일부만 실행하거나, 실패한 것만 재시도하거나. 그 순간 "i번째 결과는 i번째 `tool_call` 의 것" 이라는 가정이 **조용히** 깨집니다. 인덱스로 짝을 맞춘 코드는 그때 **부산의 인구를 서울 질문에 붙여 놓고도 에러 없이 잘 돌아갑니다.** 모델은 그 틀린 숫자로 태연히 계산합니다.
>
> 위의 (B) 처럼 `map` 콜백 **안에서** `toolCall` 을 클로저로 잡으면 인덱스를 쓸 일 자체가 없어집니다. 인덱스 버그를 구조적으로 막는 방법입니다.

> 💡 **실무 팁**: 병렬 실행에는 대가가 있습니다. **부작용이 있는 도구**(파일 쓰기, 결제, 이메일 발송)를 무조건 `Promise.all` 로 돌리면 경합이 생깁니다. 모델이 같은 파일에 두 번 쓰기를 동시에 요청할 수도 있습니다. 실무에서는 도구를 "읽기(병렬 안전) / 쓰기(직렬화 필요)" 로 나누고, 쓰기 도구는 순차 처리하거나 락을 겁니다. 또 하나 — 도구 하나가 30초 걸리면 `Promise.all` 전체가 30초를 기다립니다. 도구별 타임아웃을 걸고, 타임아웃도 **에러 `ToolMessage`** 로 되돌리세요 (7-6).

---

## 7-5. 무한 루프 방어 — `recursionLimit` 의 정체

7-1 의 의사코드에는 치명적인 결함이 있습니다.

```
반복:        # ← 언제까지?
```

상한이 없습니다. 모델이 같은 도구를 계속 요청하면 루프는 영원히 돕니다. 그리고 **매 바퀴가 유료 API 호출입니다.**

더 나쁜 건 비용이 선형이 아니라는 점입니다. 매 바퀴마다 `messages` 배열이 길어지고, 그 배열 **전체**를 다시 보냅니다. 즉 입력 토큰이 바퀴마다 누적됩니다. 10바퀴째의 호출은 1바퀴째보다 훨씬 비쌉니다. **비용은 대략 이차로 증가합니다.** 밤새 돌면 청구서가 폭발합니다.

해결은 간단합니다. 세면 됩니다.

```ts
const MAX_ITERATIONS = 5;

let iteration = 0;
let finished = false;   // ★ 루프를 빠져나온 '이유' 를 기록하는 플래그

while (iteration < MAX_ITERATIONS) {
  iteration += 1;
  const ai = await modelWithTools.invoke(messages);
  messages.push(ai);

  if ((ai.tool_calls ?? []).length === 0) {
    finished = true;          // ← 정상 종료
    break;
  }

  const toolMessages = await Promise.all(/* ... 도구 실행 ... */);
  messages.push(...toolMessages);
}

if (!finished) {
  // ← 상한 도달. 여기가 핵심입니다.
  console.log(`상한 도달! ${MAX_ITERATIONS}바퀴를 다 썼는데 아직 tool_calls 가 남아 있습니다.`);
}
```

> ⚠️ **함정 (이번 스텝 4번 함정)**: `finished` 플래그가 이 코드의 요점입니다. **`while` 을 빠져나오는 길이 두 개**인데(정상 종료 / 상한 도달) 플래그 없이는 구분할 수 없습니다.
>
> 구분하지 않으면 무슨 일이 벌어질까요? 상한에 걸려 **잘려나간 미완성 상태**의 마지막 `AIMessage` 를 "완성된 답변" 으로 착각해 사용자에게 줍니다. 그 `AIMessage` 는 `tool_calls` 만 있고 `text` 는 비어 있을 수도 있습니다. 그러면 사용자는 빈 답변을 받습니다. 아니면 "잠시만요, 확인해볼게요" 를 받습니다. **에러도 안 나고 로그도 깨끗한데 답만 없습니다.**
>
> 상한 도달은 **예외 상황**입니다. throw 하거나, 최소한 "미완료" 를 명시적으로 표시하세요.

### `recursionLimit` — 프레임워크 버전의 같은 것

`createAgent` 에도 당연히 이 상한이 있습니다. 이름이 `recursionLimit` 입니다.

```ts
const agent = createAgent({ model, tools });

await agent.invoke(
  { messages: [{ role: "user", content: "서울과 부산 인구를 더해줘" }] },
  { recursionLimit: 1 },   // ← configurable 안이 아니라 config 최상위
);
```

**출력 예시** (에러 shape 은 결정적입니다)
```
에러 이름   : GraphRecursionError
에러 메시지 : Recursion limit of 1 reached without hitting a stop condition. You can increase the limit by setting the `recursionLimit` config key.
```

우리 `runAgent` 가 던지던 그 에러의 프레임워크 버전입니다. 알아둘 사실이 세 가지 있습니다.

| 항목 | 값 |
|---|---|
| 기본값 | **25** |
| 단위 | **super-step** (바퀴가 아님) |
| 초과 시 | `GraphRecursionError` throw |
| 지정 위치 | `invoke` 의 config **최상위** (`configurable` 안이 **아님**) |

> ⚠️ **함정**: **`recursionLimit: 25` 는 "도구를 25번 부를 수 있다" 는 뜻이 아닙니다.** 단위는 LangGraph 의 **super-step** 입니다. 공식 문서 표현으로는 "A super-step can be considered a single iteration over the graph nodes. Nodes that run in parallel are part of the same super-step, while nodes that run sequentially belong to separate super-steps."
>
> 에이전트의 한 바퀴 = 모델 노드(1 step) + 도구 노드(1 step) = **2 step** 입니다. 그러니 **25 는 대략 12바퀴**입니다. 절반으로 나눠 생각하세요.
>
> 그리고 `{ configurable: { recursionLimit: 25 } }` 로 넣으면 **조용히 무시됩니다.** 에러가 안 납니다 — `configurable` 은 아무 키나 받는 자유 영역이라 오타를 잡아주지 않기 때문입니다. 그냥 기본값 25 로 돌아갑니다. `thread_id` 는 `configurable` **안**에, `recursionLimit` 은 **최상위**에. 헷갈리기 쉬우니 외워두세요.

> 💡 **실무 팁 — 상한에 자꾸 걸린다면 그건 증상이지 원인이 아닙니다.** `recursionLimit` 을 100 으로 올리는 건 대개 오답입니다. 진짜 원인은 보통 이 셋 중 하나입니다.
> 1. **도구 설명이 부실해서** 모델이 뭘 골라야 할지 몰라 이것저것 찔러본다 → `description` 을 고치세요 ([Step 06](../step-06-tools/)).
> 2. **도구가 결과를 안 주거나 애매하게 줘서** 모델이 같은 도구를 반복 호출한다 → `content` 를 명확하게.
> 3. **작업이 진짜로 크다** → 이땐 서브에이전트로 쪼개는 게 맞습니다 ([Step 18](../step-18-multi-agent/)).
>
> 프로덕션에서는 상한을 **넉넉하지 않게** 잡는 게 안전합니다. 상한은 안전벨트지 성능 옵션이 아닙니다.

---

## 7-6. 에러 처리 — 도구 실패를 `ToolMessage` 로 모델에게 돌려주기

지금까지 우리 루프에는 `try/catch` 가 없었습니다. 도구가 `throw` 하면 어떻게 될까요?

```ts
await getWeather.invoke({ city: "도쿄" });   // WEATHER_DB 에 도쿄가 없다
```

**출력 예시** (에러 메시지는 우리 도구가 만든 것이라 결정적입니다)
```
→ 던져진 에러: '도쿄' 의 날씨 데이터가 없습니다. 지원 도시: 서울, 부산, 제주
→ 이 예외가 while 루프를 뚫고 나가면 대화가 통째로 죽습니다.
```

예외가 루프를 뚫고 나가면 **대화 전체가 끝납니다.** 사용자는 500 을 받습니다. 그런데 생각해 보면 이건 과잉 반응입니다. 도구 하나가 실패한 것뿐인데 대화를 죽일 이유가 없습니다. 사람이라면 "도쿄는 안 되는구나, 그럼 다른 걸 해보자" 하겠죠.

**모델도 그럴 수 있습니다. 실패했다고 알려주기만 하면 됩니다.**

```ts
const toolMessages = await Promise.all(
  (ai.tool_calls ?? []).map(async (toolCall) => {
    try {
      const output = await findTool(toolCall.name).invoke(toolCall.args);
      return new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
        status: "success",
      });
    } catch (error) {
      // ★ 에러도 '결과'입니다. tool_call_id 를 채워서 반드시 돌려줍니다.
      return new ToolMessage({
        content: `도구 실행 실패: ${(error as Error).message}`,
        tool_call_id: toolCall.id!,
        name: toolCall.name,
        status: "error",
      });
    }
  }),
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
  [1바퀴] get_weather:error

최종 답변: 죄송합니다. 현재 도쿄의 날씨 정보는 제공되지 않습니다. 조회 가능한 도시는 서울, 부산, 제주입니다. 이 중에서 확인하고 싶은 도시가 있으신가요?

----- 7-6 최종 메시지 배열 (총 5개) -----
[0] System   | 너는 날씨 비서다. 도구가 에러를 돌려주면 사용자에게 사과하고 가능한 대안을 안내해라.
[1] Human    | 도쿄 날씨 알려줘
[2] AI       | text="" tool_calls=[get_weather({"city":"도쿄"})]
[3] Tool     | name=get_weather id=toolu_01Ab... status=error content="도구 실행 실패: '도쿄' 의 날씨 데이터가 없습니다. 지원 도시: 서울, 부산, 제주"
[4] AI       | text="죄송합니다. 현재 도쿄의 날씨 정보는..." tool_calls=[]
```

모델이 **스스로 회복했습니다.** 우리는 아무 분기도 안 썼습니다. 그냥 실패 사실을 말해줬을 뿐입니다. 이게 에이전트가 강력한 이유입니다 — 예외 처리를 `if` 로 짜는 게 아니라 **모델에게 상황을 알려주고 판단을 맡기는 것**입니다.

### `status` 는 모델이 안 읽는다

> ⚠️ **함정**: `status: "error"` 를 `"success"` 로 바꿔도 **모델의 답변은 거의 달라지지 않습니다.** 모델이 실제로 읽는 것은 **`content` 뿐**이기 때문입니다. `status` 는 LangChain 이 메시지 객체에 붙여 두는 메타데이터이고, provider 포맷으로 나갈 때 "이 결과는 에러였다" 정도의 플래그로만 전달되거나 아예 무시됩니다.
>
> 모델을 움직인 것은 `"'도쿄' 의 날씨 데이터가 없습니다. 지원 도시: 서울, 부산, 제주"` 라는 **문장 자체**입니다. `status` 를 정확히 채워놓고 `content` 에 `"Error"` 라고만 적으면 모델은 아무것도 못 합니다.
>
> 그러면 `status` 는 왜 채우나요? **사람과 코드를 위해서**입니다. 로그를 필터링하거나, "에러가 2번 연속이면 중단" 같은 정책을 **우리 코드가** 판단할 때 씁니다. 모델용이 아니라 우리용입니다.

> 💡 **실무 팁 — 에러 메시지도 프롬프트입니다.** 도구가 실패했을 때 `content` 에 뭘 적느냐가 회복률을 결정합니다.
> | | 예시 | 모델의 반응 |
> |---|---|---|
> | ❌ | `"Error"` | 뭘 해야 할지 모름. 같은 호출을 반복하거나 포기 |
> | ❌ | `"TypeError: Cannot read properties of undefined (reading 'temp')"` | 스택 트레이스는 모델에게 소음. 토큰만 먹음 |
> | ❌ | `"HTTP 429"` | 재시도해야 할지 판단 불가 |
> | ✅ | `"'도쿄' 의 날씨 데이터가 없습니다. 지원 도시: 서울, 부산, 제주"` | 대안을 제시하거나 사용자에게 되물음 |
> | ✅ | `"요청이 너무 많습니다. 10초 후 재시도하세요."` | 다른 도구를 먼저 처리하거나 안내 |
>
> 원칙은 **"무엇이 / 왜 실패했고 / 어떤 선택지가 있는지"** 입니다. 내부 예외를 그대로 `String(error)` 로 흘려보내지 말고, 도구 안에서 모델이 읽을 문장으로 번역하세요. 반대로 **민감 정보(DB 접속 문자열, 내부 경로, 토큰)가 에러 메시지에 섞여 모델에게 전달되지 않도록** 주의해야 합니다.

> ⚠️ **함정 (7-2 함정의 재확인)**: 절대 하면 안 되는 것 — **실패한 도구의 `ToolMessage` 를 빼고 보내기.**
> ```ts
> // ❌ 이렇게 하면 400
> catch (error) {
>   console.error(error);
>   return null;             // 그리고 filter(Boolean) 으로 걸러냄
> }
> ```
> "실패했으니 결과가 없다" 는 자연스러운 생각이지만, provider 는 `tool_calls` N개에 `ToolMessage` N개를 요구합니다. **실패해도 자리는 채워야 합니다.** 도구 3개 중 1개가 실패했을 때 2개만 보내면 나머지 2개의 성공 결과까지 함께 400 으로 날아갑니다.

---

## 7-7. 종합 — 우리가 만든 루프 vs `createAgent`

이제 7-2 ~ 7-6 을 전부 합칩니다. 이게 완성된 미니 에이전트입니다.

```ts
async function runAgent(options: {
  input: string;
  systemPrompt?: string;
  maxIterations?: number;
  verbose?: boolean;
}): Promise<{ messages: BaseMessage[]; output: string; iterations: number }> {
  const { input, systemPrompt, maxIterations = 10, verbose = false } = options;

  // 1) 모델에 도구 스펙을 붙인다.
  const modelWithTools = model.bindTools(tools);

  // 2) 대화 상태 = 메시지 배열. 이게 에이전트의 전체 기억입니다.
  const messages: BaseMessage[] = [];
  if (systemPrompt) messages.push(new SystemMessage(systemPrompt));
  messages.push(new HumanMessage(input));

  // 3) 루프.
  for (let iteration = 1; iteration <= maxIterations; iteration++) {
    const ai = await modelWithTools.invoke(messages);
    messages.push(ai);

    const toolCalls = ai.tool_calls ?? [];
    if (verbose) {
      console.log(`  [${iteration}바퀴] tool_calls=${toolCalls.length}`);
    }

    // 4) 종료 조건 — tool_calls 가 비었으면 끝. text 유무로 판단하지 않습니다.
    if (toolCalls.length === 0) {
      return { messages, output: ai.text, iterations: iteration };
    }

    // 5) 도구를 병렬로 실행하고, 결과를 ToolMessage 로 되돌린다.
    const toolMessages = await Promise.all(
      toolCalls.map(async (toolCall) => {
        const selected = toolsByName[toolCall.name];

        // 모델이 없는 도구를 지어낼 수도 있습니다. 이것도 에러로 되돌립니다.
        if (!selected) {
          return new ToolMessage({
            content: `'${toolCall.name}' 이라는 도구는 없습니다. 사용 가능: ${Object.keys(toolsByName).join(", ")}`,
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "error",
          });
        }

        try {
          const output = await selected.invoke(toolCall.args);
          return new ToolMessage({
            content: String(output),
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "success",
          });
        } catch (error) {
          return new ToolMessage({
            content: `도구 실행 실패: ${(error as Error).message}`,
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "error",
          });
        }
      }),
    );
    messages.push(...toolMessages);
  }

  // 6) 상한 도달. 조용히 넘어가지 않고 명시적으로 알립니다.
  throw new Error(
    `최대 반복 횟수(${maxIterations})를 넘었습니다. 도구 설명이 부실하거나 모델이 같은 도구를 반복 호출하고 있을 수 있습니다.`,
  );
}
```

**약 80줄입니다.** 이게 에이전트입니다.

### 같은 질문, 두 구현

```ts
const question = "서울과 부산의 인구를 더하면 몇 명이야? 계산은 도구로 해줘.";
const systemPrompt = "너는 도시 정보 비서다. 숫자 계산은 반드시 calculate 도구를 써라.";

// (A) 우리가 손으로 만든 것
const mine = await runAgent({ input: question, systemPrompt, verbose: true });

// (B) 프레임워크
const agent = createAgent({ model, tools, systemPrompt });
const result = await agent.invoke({
  messages: [{ role: "user", content: question }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
(A) runAgent (우리가 손으로 만든 것)
  [1바퀴] tool_calls=2 (get_population, get_population)
  [2바퀴] tool_calls=1 (calculate)
  [3바퀴] tool_calls=0
  → 답변: 서울과 부산의 인구를 합하면 12,679,000명입니다.
  → 바퀴 수: 3
  → 최종 메시지 개수: 8

(B) createAgent (프레임워크)
  → 답변: 서울(9,386,000명)과 부산(3,293,000명)의 인구를 더하면 12,679,000명입니다.
  → 최종 메시지 개수: 8

두 메시지 배열의 타입 시퀀스가 같은지 비교:
  (A) System → Human → AI → Tool → Tool → AI → Tool → AI
  (B) System → Human → AI → Tool → Tool → AI → Tool → AI
```

**같습니다.** 답도 같고(12,679,000 = 9,386,000 + 3,293,000, 검산 가능합니다), 메시지 배열의 모양도 같습니다. `createAgent` 는 우리 `runAgent` 와 **같은 루프**입니다. 마법이 아닙니다.

### 그러면 `createAgent` 에는 뭐가 더 있나

| 기능 | 우리 `runAgent` | `createAgent` | 어디서 배우나 |
|---|---|---|---|
| 모델 + 도구 + 루프 | ✅ 직접 구현 | ✅ 동일 | **이번 스텝** |
| 종료 조건 (`tool_calls` 기준) | ✅ | ✅ 동일 | **이번 스텝** |
| 병렬 도구 실행 | ✅ `Promise.all` | ✅ 동일 | **이번 스텝** |
| 반복 상한 | ✅ `maxIterations` | ✅ `recursionLimit` (기본 25 super-step) | **이번 스텝** |
| 도구 에러 → `ToolMessage` | ✅ `try/catch` | ✅ 내장 + `toolRetryMiddleware` | **이번 스텝** |
| `tool_call_id` 짝 맞추기 | ✅ 수동 | ✅ 자동 | **이번 스텝** |
| 시스템 프롬프트 | ✅ `SystemMessage` | ✅ `systemPrompt` | [Step 04](../step-04-prompts/) |
| 구조화된 출력 | ❌ | ✅ `responseFormat` → `structuredResponse` | [Step 05](../step-05-structured-output/) |
| 스트리밍 | ❌ | ✅ `stream` / `streamEvents` | [Step 09](../step-09-streaming/) |
| 대화 저장 (체크포인트) | ❌ | ✅ `checkpointer` + `thread_id` | [Step 10](../step-10-memory/) |
| 미들웨어 훅 | ❌ | ✅ `middleware` (`wrapToolCall` 등) | [Step 11](../step-11-middleware-builtin/), [12](../step-12-middleware-custom/) |
| Human-in-the-Loop (중단/재개) | ❌ | ✅ `humanInTheLoopMiddleware` | [Step 13](../step-13-hitl/) |
| 런타임 컨텍스트 주입 | ❌ | ✅ `contextSchema` → `runtime.context` | [Step 14](../step-14-context-runtime/) |
| 관측(trace) | ❌ | ✅ 콜백/LangSmith 연동 | [Step 19](../step-19-observability-eval/) |

핵심은 **위쪽 6줄이 이미 우리 손에 있다**는 것입니다. `createAgent` 가 추가로 주는 것들은 전부 **"이 루프의 어느 지점에 무엇을 끼워 넣느냐"** 의 문제입니다.

- 스트리밍 = `modelWithTools.invoke` 를 청크 단위로 흘리는 것
- 체크포인터 = 매 바퀴 끝에 `messages` 배열을 저장하는 것
- 미들웨어 = `selected.invoke(toolCall.args)` 앞뒤를 감싸는 것
- HITL = 도구 실행 직전에 루프를 멈추고 상태를 저장했다가 나중에 재개하는 것

이제 이 문장들이 전부 우리 `runAgent` 코드의 **몇 번째 줄**을 가리키는지 보이나요? 그게 이 스텝의 목적이었습니다.

> 💡 **실무 팁 — 그래서 직접 만들어 쓰나요?** 아니요. `createAgent` 를 쓰세요. 위 표의 아래쪽 기능들을 직접 구현하면 코드가 80줄에서 수천 줄이 되고, 그건 여러분의 제품이 아니라 프레임워크를 만드는 일입니다. 이번 스텝의 목적은 **대체가 아니라 예측**입니다. 프레임워크를 블랙박스로 두지 않는 것. 프로덕션에서 `GraphRecursionError` 가 뜨거나, `tool_call_id` 400 이 뜨거나, 도구가 안 불리거나 할 때 — 루프를 아는 사람은 5분 만에 고치고, 모르는 사람은 GitHub 이슈를 뒤집니다.

> 💡 **실무 팁**: 이 스텝의 코드는 provider 를 전혀 타지 않습니다. `initChatModel("anthropic:claude-sonnet-4-6")` 을 `initChatModel("openai:gpt-5.5")` 로 바꾸고 환경변수를 `OPENAI_API_KEY` 로 두면 `runAgent` 는 **한 글자도 안 바뀝니다.** `tool_calls` / `ToolMessage` / `tool_call_id` 는 LangChain 이 provider 차이를 흡수해 만든 공통 추상이기 때문입니다. 달라지는 건 `id` 접두사(`toolu_` vs `call_`) 정도입니다. 이게 LangChain 을 쓰는 가장 실질적인 이유입니다.

---

## 정리

에이전트의 한 바퀴:

```
bindTools → invoke → tool_calls 확인 → (비었나?) → 도구 실행 → ToolMessage 추가 → 반복
                                          ↓ 예
                                       ai.text 반환
```

| 개념 | 요점 |
|---|---|
| 모델의 역할 | 도구를 **실행하지 않는다.** `tool_calls` 라는 **요청**만 만든다 |
| 우리의 역할 | 도구를 실행하고 결과를 `ToolMessage` 로 되돌린다 |
| `messages` 배열 | 에이전트의 기억 **전부**. 모델은 stateless 라 매 바퀴 통째로 다시 보낸다 |
| `tool_call_id` | 요청과 응답을 잇는 **유일한 끈**. 순서가 아니다 |
| 종료 조건 | `tool_calls.length === 0` **하나뿐**. `text` 는 무관 |
| 병렬 실행 | 한 `AIMessage` 의 여러 `tool_calls` → `Promise.all` |
| 반복 상한 | 필수. `createAgent` 의 `recursionLimit` 기본 **25 super-step ≈ 12바퀴** |
| 도구 에러 | throw 하지 말고 `ToolMessage` 로 되돌려 모델이 회복하게 한다 |
| `createAgent` | **같은 루프.** 스트리밍/메모리/미들웨어/HITL 이 더 있을 뿐 |

**핵심 함정 3가지**

1. **`ToolMessage` 누락 → provider 400.** `tool_calls` 가 N개면 `ToolMessage` 도 정확히 N개. 하나라도 빠지면 `tool_use ids were found without tool_result blocks`. **실패한 도구도 자리는 채워야 합니다.** LangChain 이 검사해 주지 않으므로 에러는 provider 서버에서 납니다.
2. **종료를 `content`/`text` 로 판정 → 도구를 실행도 안 하고 끝난다.** 모델은 `text` 와 `tool_calls` 를 **동시에** 보냅니다. "확인해볼게요" 를 최종 답변으로 내놓으면서 **에러도 로그도 남기지 않습니다.** 종료는 `tool_calls` 로만 판정하세요.
3. **병렬 결과를 인덱스로 짝짓기 → 지금은 되고 나중에 조용히 틀린다.** `Promise.all` 이 순서를 보장해서 당장은 동작하지만, 큐/`allSettled`/캐시로 리팩터링하는 순간 엉뚱한 결과가 엉뚱한 질문에 붙습니다. 짝은 `tool_call_id` 로. `map` 콜백 안에서 `toolCall` 을 클로저로 잡으면 인덱스를 쓸 일이 없어집니다.

**보너스 함정**: 상한 도달을 정상 종료와 구분하지 않으면 미완성 결과를 완성된 답으로 착각합니다. `recursionLimit` 을 `configurable` 안에 넣으면 조용히 무시됩니다(최상위에 넣으세요). `bindTools` 의 반환값을 버리면 에러 없이 에이전트가 아닌 것이 됩니다.

---

## 연습문제

1. `tools` 를 `bindTools` 로 붙인 모델에게 **"부산 날씨 어때?"** 라고 물어, `tool_calls` 의 개수 / 첫 번째 `tool_call` 의 `name`·`args`·`id` / `response.text` 를 각각 출력하세요. 그리고 주석으로 답하세요 — **이 시점에 `WEATHER_DB` 는 조회되었나요?** (예/아니오, 왜?)
2. **"제주 날씨 알려줘"** 로 시작해 한 바퀴를 손으로 돌리세요. `while` 을 쓰지 말고 펼쳐서 쓰세요: Human 넣기 → `invoke` → AIMessage push → 도구 실행 → `ToolMessage` push → `invoke` 한 번 더 → 최종 답변 출력. 마지막에 `messages.length` 를 출력하세요. **몇 개가 나와야 할까요?**
3. (함정) 문제 2와 똑같이 하되 **`ToolMessage` 를 만드는 단계를 통째로 건너뛰고** 바로 다시 `invoke` 하세요. `try/catch` 로 감싸 에러의 `name` 과 `message` 를 출력하고, 주석으로 답하세요 — **이건 LangChain 이 낸 에러인가요, provider 가 낸 에러인가요? HTTP 상태 코드는?**
4. (a) 올바른 종료 판정 `isDone(m: AIMessage): boolean` 을 구현하세요. (b) 흔한 오답 `wrongIsDone` (text 가 있으면 끝)도 구현하세요. (c) **"안녕, 반가워!"**(도구 불필요)와 **"서울 날씨 알려줘"**(도구 필요)에 대해 두 함수의 판정을 각각 출력하고, **판정이 갈리는 케이스**를 찾아 주석으로 설명하세요.
5. **"서울, 부산, 제주 인구를 각각 알려줘"** 로 `tool_calls` 를 여러 개 받아, (a) `for` 순차 실행 시간(ms), (b) `Promise.all` 병렬 실행 시간(ms) 을 각각 재서 비교 출력하세요. 그리고 주석으로 답하세요 — **`Promise.all` 이 결과 순서를 보장하는데도 짝을 인덱스가 아니라 `tool_call_id` 로 맞춰야 하는 이유는?**
6. `MAX_ITERATIONS = 3` 상한을 건 `while` 루프로 **"서울과 부산과 제주 인구를 모두 더하면?"** 을 처리하세요. **루프를 빠져나온 이유(정상 종료 / 상한 도달)를 반드시 구분**해서 출력하고, 실제 사용한 바퀴 수도 출력하세요. 주석으로 답하세요 — **상한을 안 걸면 최악의 경우 무슨 일이 벌어지나요? `recursionLimit` 기본값은 몇이고 그 단위는 무엇인가요?**
7. **"도쿄 날씨 알려줘"** 를 처리하세요. `get_weather` 가 `throw` 하므로, 이를 `try/catch` 로 잡아 `status: "error"` 인 `ToolMessage` 로 모델에게 되돌리고, 모델이 사과하며 대안을 안내하고 **정상 종료**하는 것을 확인하세요. 주석으로 답하세요 — **`status` 를 `"success"` 로 바꿔도 답변이 달라지나요? 왜?**
8. (종합) `runAgent` 를 완성하세요 — systemPrompt, `maxIterations` 상한, `tool_calls` 기준 종료, `Promise.all` 병렬 실행, 도구 에러 회복, **모델이 없는 도구 이름을 지어낸 경우도** `ToolMessage` 로 회복, 상한 도달 시 throw. 그다음 `createAgent` 에게 같은 질문·같은 systemPrompt 를 주고 **두 결과의 메시지 타입 시퀀스가 같은지** 비교 출력하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 08 — createAgent, 첫 에이전트](../step-08-create-agent/)

우리가 만든 80줄을 한 줄로 줄입니다. 대신 이제는 그 한 줄 안에서 무슨 일이 벌어지는지 **예측할 수 있는 상태**로 갑니다. `recursionLimit`, `responseFormat`, `systemPrompt` 가 각각 우리 `runAgent` 의 몇 번째 줄에 해당하는지 짚어가며 진행합니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(7-1 ~ 7-7)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 루프가 도는 것을 눈으로 확인하고, `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 **자기완결적**입니다. 도구 3개(`get_weather`, `get_population`, `calculate`)와 모델 초기화가 각 파일 상단에 그대로 들어 있어, 복사해서 바로 돌릴 수 있습니다. 실행 전에 `project/.env` 에 `ANTHROPIC_API_KEY` 가 있어야 합니다.

```bash
npx tsx docs/reference/langchain/step-07-tool-loop/practice.ts
```

세 파일이 공유하는 설계 결정이 하나 있습니다. `toolsByName` 레지스트리를 `AnyTool` 이라는 최소 인터페이스(`{ name, invoke }`)로 단순화한 것입니다. 도구마다 `schema` 가 달라 배열 원소의 타입이 유니온이 되는데, 그 상태로 `invoke` 를 부르면 TypeScript 가 파라미터 타입을 교집합으로 좁혀 컴파일 에러를 냅니다. 이 스텝은 루프의 원리에 집중하는 곳이라 타입 곡예를 피했습니다 — 그리고 이것도 `createAgent` 가 대신 풀어주는 문제 중 하나입니다.

> ⚠️ **비용 주의**: `practice.ts` 를 한 번 통째로 실행하면 모델을 20회 이상 호출합니다. `solution.ts` 도 비슷합니다. 학습용으로는 큰 금액이 아니지만, 파일 하단의 `await sectionX()` 줄을 주석 처리해 필요한 절만 돌리는 것을 권합니다. 특히 `get_population` 에는 1초 지연이 있어 전체 실행에 1~2분 걸립니다.

### practice.ts

본문을 따라가며 손으로 쳐볼 예제를 `[7-1] ~ [7-7]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[7-2]` 가 이 파일의 심장입니다. **`while` 을 일부러 쓰지 않고** 1바퀴 → 2바퀴 → 3바퀴를 펼쳐서 씁니다. 각 바퀴가 "모델에게 물어본다 / 우리가 도구를 실행한다" 두 덩어리로 나뉘어 있어, 루프의 한 주기가 정확히 무엇인지 눈으로 셀 수 있습니다. 마지막의 `printMessages` 출력이 `Human → AI → Tool → Tool → AI → Tool → AI` 로 나오는 것을 꼭 확인하세요.
- `[7-2 보너스]` 는 `getWeather.invoke({city:"서울"})` 과 `getWeather.invoke({name, args, id, type:"tool_call"})` 을 나란히 실행해 **반환 타입이 갈리는 것**(`string` vs `ToolMessage`)을 보여줍니다. 본문 팁의 지름길을 직접 확인하는 블록입니다.
- `[7-3]` 은 `isDone` 과 `wrongIsDone` 을 **한 응답에 동시에** 물려서 판정이 갈리는 순간을 잡아냅니다. 다만 모델이 매번 text 를 함께 보내지는 않으므로, `wrongIsDone(needTool) = true` 가 안 나올 수도 있습니다. **몇 번 다시 실행해 보세요.** 이 비결정성 자체가 함정의 무서움입니다 — 열 번 중 아홉 번 통과하는 버그입니다.
- `[7-4]` 는 순차와 병렬을 **같은 `tool_calls` 로 두 번** 실행해 시간을 잽니다. 도구를 두 번 실행하는 셈이라 실전에선 하면 안 되는 짓이지만, 3021ms vs 1008ms 를 한 화면에서 보려고 그렇게 했습니다. 마지막의 id 순서 비교 출력은 "순서는 같지만 그게 요점이 아니다" 를 말하기 위한 것입니다.
- `[7-5]` 의 `finished` 플래그를 눈여겨보세요. `while` 을 빠져나온 이유가 두 가지인데, 이 플래그가 없으면 "상한 도달"과 "정상 종료"를 구분할 수 없습니다. 본문 함정과 짝지어 읽으세요.
- `[7-7 보너스]` 는 `createAgent` 에 `recursionLimit: 1` 을 걸어 **`GraphRecursionError` 를 일부러 터뜨립니다.** 에러 이름과 메시지를 직접 보는 것이 목적입니다. 프로덕션에서 이 에러를 만났을 때 당황하지 않으려면 한 번은 봐 둬야 합니다. `recursionLimit` 이 `configurable` 밖 최상위에 있다는 것도 이 블록에서 확인하세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 파일입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되고 그 아래 `// TODO: 여기에 작성하세요` 자리가 비어 있습니다.

- 상단의 **공통 준비 블록(도구 3개, `model`, `toolsByName`, `findTool`)은 이미 완성되어 있습니다.** 수정하지 말고 그대로 쓰세요. 여러분이 풀 것은 루프이지 도구 정의가 아닙니다.
- 파일을 그대로 실행하면 **아무것도 출력되지 않습니다. 정상입니다.** 맨 아래 `// await exercise1();` 처럼 전부 주석 처리되어 있으니, 풀고 싶은 문제의 주석을 풀어 실행하세요.
- `[문제 4]` 만 함수 시그니처(`isDone`, `wrongIsDone`)가 미리 적혀 있고 `return false;` 로 채워져 있습니다. 타입체크를 통과시키려는 자리채움이니 몸통을 바꿔 쓰세요.
- `[문제 3]` 은 **일부러 에러를 내는 문제**입니다. 에러가 나야 정답입니다. `try/catch` 를 빼먹으면 파일이 거기서 죽습니다.
- `[문제 8]` 의 `runAgent` 는 문제 2~7 의 답을 전부 합친 것입니다. 앞 문제들을 건너뛰고 8번부터 시작하지 마세요. 특히 "모델이 없는 도구 이름을 지어낸 경우"는 앞 문제에 안 나오는 새 요구사항이니, `findTool` 을 쓰지 말고 `toolsByName` 을 직접 조회해야 한다는 힌트를 놓치지 마세요.
- 파일 맨 아래 `void [...]` 두 줄은 "선언만 하고 안 썼다" 는 TypeScript 경고를 막기 위한 것입니다. 신경 쓰지 마세요.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 여세요. 정답 코드보다 그 아래 `// →` 로 시작하는 해설 주석이 본체입니다.

- `[정답 3]` 의 해설에 **Anthropic 과 OpenAI 의 실제 에러 메시지를 나란히** 적어 두었습니다. `tool_use ids were found without tool_result blocks immediately after` (Anthropic) vs `An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'` (OpenAI). 문구는 달라도 같은 말입니다. 이 400 은 LangChain 이 아니라 **provider 서버**가 냅니다 — LangChain 은 짝이 맞는지 검사해 주지 않습니다.
- `[정답 4]` 가 이 파일에서 가장 중요합니다. `wrongIsDone` 을 쓰면 **도구를 실행조차 하지 않고** "서울 날씨를 확인해볼게요!" 를 최종 답변으로 내놓는데, **에러도 로그도 남지 않습니다.** 반대로 "text 가 비었으면 계속" 이라는 오답은 무한 루프를 만듭니다. 종료 조건에서 `text` 를 쳐다보는 순간 둘 중 하나에 빠진다는 것이 요점입니다.
- `[정답 5]` 의 해설은 "`Promise.all` 은 순서를 보장하잖아요?" 라는 반론에 정면으로 답합니다. 맞습니다 — **그래서 지금은 동작하고, 그게 함정입니다.** 진짜 이유 두 가지(provider 는 배열 순서를 안 본다 / 큐·`allSettled`·캐시로 리팩터링하는 순간 가정이 깨진다)를 짚습니다.
- `[정답 6]` 의 요점은 `finished` 플래그 하나입니다. 그리고 상한을 안 걸면 비용이 **선형이 아니라 이차로** 증가한다는 것(매 바퀴 `messages` 전체를 다시 보내므로 입력 토큰이 누적)을 짚습니다. `recursionLimit` 25 는 25바퀴가 아니라 **약 12바퀴**입니다.
- `[정답 7]` 의 해설에 **에러 `content` 작성 원칙**을 ❌/✅ 표로 정리했습니다. `"Error"` 도 스택 트레이스도 안 됩니다. 모델을 움직이는 것은 `status` 가 아니라 `content` 문장이고, `status` 는 우리 코드와 로그를 위한 것입니다. 그리고 **실패한 도구의 `ToolMessage` 를 빼고 보내면 정답 3의 400 으로 직행**합니다.
- `[정답 8]` 은 `EXPECTED = 12_679_000` (= 9,386,000 + 3,293,000) 으로 **자동 검산**까지 합니다. `runAgent` 와 `createAgent` 의 답변에 이 숫자가 들어 있는지 확인해 두 구현이 같은 결과를 내는 것을 증명합니다. 마지막 주석의 목록(`recursionLimit`, `checkpointer`, `stream`, `middleware`, `responseFormat`, `interrupt`)이 Step 08~13 의 예고편입니다.

```ts file="./solution.ts"
```
