# Step 19 — 관측·테스트·평가

> **학습 목표**
> - 에이전트 디버깅이 왜 일반 코드와 다른지 설명하고, **궤적(trajectory)** 을 messages 배열에서 추출한다
> - 환경변수 3개로 **LangSmith 트레이싱**을 켜고, `runName`/`tags`/`metadata` 로 나중에 찾을 수 있게 라벨링한다
> - LangSmith 없이 **`ConsoleCallbackHandler`**, **`streamMode: "debug"`**, 커스텀 로깅 미들웨어로 디버깅한다
> - **`fakeModel()`** 로 모델을 갈아끼워 에이전트를 빠르고 결정적으로 테스트한다
> - 데이터셋 + 평가자로 **평가(eval)** 를 돌리고, **궤적 평가**로 "운 좋게 맞은 답"을 잡아낸다
> - 평가를 **CI 게이트**로 만들어 회귀를 막고, **LangGraph Studio** 로 시각적으로 디버깅한다
>
> **선행 스텝**: [Step 18 — 멀티 에이전트](../step-18-multi-agent/)
> **예상 소요**: 80분

Step 01부터 18까지 우리는 에이전트를 **만드는** 법을 배웠습니다. 이제 만든 것이 **실제로 잘 도는지 확인하는** 법을 배웁니다. 이 스텝이 없으면 여러분의 에이전트는 "데모에서는 되는데 실서비스에서 왜 이러는지 모르겠는 것"이 됩니다.

에이전트 개발의 실제 시간 배분은 대략 이렇습니다: 만드는 데 20%, **왜 이상하게 동작하는지 알아내는 데 80%**. 이 스텝은 그 80%를 줄이는 도구들에 관한 것입니다. 순서는 관측(무슨 일이 있었나) → 테스트(코드가 안 깨졌나) → 평가(품질이 안 떨어졌나) 입니다.

평가 지표의 **이론**(정확성, 환각, 문맥 관련성 등)과 LLM Judge의 편향 문제는 이 사이트의 별도 문서에서 이미 다룹니다 — [평가](/ai/100-Evaluation) 와 [LLM Judge](/ai/9990-LLMJudge) 를 먼저 읽으면 좋습니다. **이 스텝은 그 이론을 LangChain/LangSmith로 어떻게 실행하는가**에만 집중합니다.

> **이 스텝의 실습은 API 키가 필요 없습니다.** `fakeModel()` 덕분에 `practice.ts` 전체가 네트워크 없이 돕니다. 실제 모델을 부르는 블록은 [19-2] 하나뿐이고, 키가 없으면 스스로 건너뜁니다.

---

## 19-1. 에이전트는 왜 디버깅이 어려운가

일반 함수를 디버깅할 땐 입력을 넣고 출력을 봅니다. 틀리면 중단점을 걸고 한 줄씩 따라갑니다. 에이전트에서는 이게 통하지 않습니다. 이유가 세 가지 있습니다.

| 문제 | 일반 코드 | 에이전트 |
|---|---|---|
| **비결정성** | 같은 입력 → 같은 출력 | 같은 입력 → **매번 다른 궤적**. 어제 되던 게 오늘 안 된다 |
| **다단계** | 스택 트레이스 하나 | 모델 호출 N번 + 도구 호출 M번이 얽힘. 어느 단계에서 틀어졌는지 안 보인다 |
| **블랙박스** | 로직이 코드에 있다 | 결정 로직이 **모델 가중치 안**에 있다. 왜 그 도구를 골랐는지 코드에 안 적혀 있다 |

가장 큰 함정은 **`invoke()` 의 반환값만 보는 습관**입니다. 반환값에는 최종 답 하나가 들어 있고, 그 사이에 무슨 일이 있었는지는 눈에 띄지 않습니다.

```ts
const model = fakeModel()
  .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "call_1" }])
  .respond(new AIMessage("서울은 맑고 23도입니다."));

const agent = createAgent({ model, tools });
const result = await agent.invoke({ messages: [new HumanMessage("서울 날씨 알려줘")] });

// 흔히 이렇게만 봅니다 — 여기엔 정보가 거의 없습니다.
console.log("최종 답:", result.messages.at(-1)?.text);

// 사실은 이만큼 일어났습니다. messages 배열이 곧 "궤적(trajectory)" 입니다.
for (const m of result.messages) {
  const toolCalls = (m as AIMessage).tool_calls ?? [];
  const suffix = toolCalls.length ? ` → tool_calls: ${toolCalls.map((t) => t.name).join(", ")}` : "";
  console.log(`  [${m.getType()}] ${JSON.stringify(m.text).slice(0, 40)}${suffix}`);
}

console.log("모델 호출 횟수:", model.callCount);
```

**출력**
```
최종 답: 서울은 맑고 23도입니다.

실제로 벌어진 일 (messages 배열 전체):
  [human] "서울 날씨 알려줘"
  [ai] "서울 날씨 알려줘" → tool_calls: get_weather
  [tool] "서울: 맑음, 23도"
  [ai] "서울은 맑고 23도입니다."
모델 호출 횟수: 2
```

한 줄짜리 답 뒤에 **메시지 4개와 모델 호출 2번**이 숨어 있었습니다. 관측의 출발점은 이겁니다: **`result.messages` 배열이 곧 궤적이고, 그것이 여러분의 스택 트레이스입니다.** 도구를 어떤 인자로 불렀는지, 도구가 뭘 돌려줬는지, 모델이 그걸 보고 뭐라 했는지가 전부 여기 있습니다.

`tool_calls` 는 AI 메시지에만, 그것도 **있을 때만** 붙습니다. 최종 답 메시지에는 없으므로 `?? []` 방어가 필요합니다 — 이건 19-7에서 궤적 추출 함수를 만들 때 다시 나옵니다.

> 💡 **실무 팁**: "에이전트가 이상해요" 라는 제보를 받으면 가장 먼저 할 일은 **그 실행의 messages 배열 전체를 찍어 보는 것**입니다. 열에 아홉은 모델이 이상한 게 아니라 (1) 도구가 이상한 값을 돌려줬거나 (2) 도구 설명이 부실해서 모델이 엉뚱한 도구를 골랐거나 (3) 도구를 아예 안 불렀거나 셋 중 하나입니다. 셋 다 messages 배열에 그대로 보입니다.

---

## 19-2. LangSmith 트레이싱 켜기

messages 배열을 매번 `console.log` 로 찍는 건 한계가 있습니다. 실행이 하루 10만 건이면? 어제 오후 3시에 특정 사용자가 겪은 오류를 찾아야 한다면? 이때 필요한 게 **트레이스 저장소**이고, LangChain의 기본 선택지가 **LangSmith** 입니다.

핵심은 **코드를 한 줄도 안 고쳐도 된다**는 것입니다. 환경변수만 세팅하면 LangChain이 알아서 트레이스를 올립니다.

```bash
# .env
LANGSMITH_TRACING=true            # ← 이게 스위치. "true" 문자열이어야 합니다
LANGSMITH_API_KEY=lsv2_pt_...     # smith.langchain.com 에서 발급
LANGSMITH_PROJECT=langchain-course # 선택. 없으면 "default" 프로젝트로 들어갑니다
```

이러면 끝입니다. 평소처럼 실행하면 됩니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6", // OpenAI 대안: "openai:gpt-5.5"
  tools,
  systemPrompt: "너는 한국 도시 정보 도우미다. 도구를 써서 답하라.",
});

const result = await agent.invoke({ messages: [{ role: "user", content: "제주 날씨랑 인구 알려줘" }] });
// → 이 실행이 통째로 smith.langchain.com 에 트레이스로 올라갑니다
```

### 트레이스 읽는 법

LangSmith에서 트레이스를 열면 **트리**가 보입니다. 용어부터 정리합시다.

| 용어 | 뜻 |
|---|---|
| **Run** | 하나의 단계. 모델 호출 1번, 도구 호출 1번이 각각 하나의 Run |
| **Trace** | 입력에서 출력까지의 Run 전체 트리. `invoke()` 한 번 = 트레이스 하나 |
| **Project** | 트레이스를 모아두는 폴더. `LANGSMITH_PROJECT` 로 지정 |

트레이스를 열었을 때 볼 순서는 이렇습니다.

1. **맨 아래(마지막) Run부터 거꾸로** 봅니다. 최종 답이 틀렸다면 그 답을 만든 마지막 모델 호출이 뭘 입력으로 받았는지가 첫 단서입니다.
2. **도구 Run의 output** 을 봅니다. 도구가 이상한 값을 줬으면 모델 탓이 아닙니다.
3. **모델 Run의 input** 을 봅니다. 여기에 시스템 프롬프트 + 전체 대화가 있습니다. 컨텍스트가 잘렸거나 이상한 게 섞였는지 보입니다.
4. **지연시간과 토큰**을 봅니다. 어느 단계가 느린지, 컨텍스트가 얼마나 부풀었는지 보입니다.

### 선택적 트레이싱

전부가 아니라 **특정 실행만** 추적하고 싶을 때가 있습니다. 환경변수 대신 트레이서를 콜백으로 넘깁니다.

```ts
import { LangChainTracer } from "@langchain/core/tracers/tracer_langchain";

const tracer = new LangChainTracer();
await agent.invoke({ messages: [{ role: "user", content: "..." }] }, { callbacks: [tracer] });

// 프로젝트를 코드에서 동적으로 지정할 수도 있습니다
const testTracer = new LangChainTracer({ projectName: "email-agent-test" });
```

> ⚠️ **함정 (이 스텝에서 가장 중요합니다)**: **`LANGSMITH_TRACING=true` 는 여러분의 프롬프트·도구 인자·모델 응답을 통째로 외부 서버로 보냅니다.** 이건 버그가 아니라 설계입니다 — 트레이스를 보려면 데이터가 거기 있어야 하니까요. 문제는 이걸 **모르고 켜는 것**입니다. 고객 이름, 주민번호, 내부 문서, 계약서가 프롬프트에 들어간다면 그게 전부 올라갑니다. 에러도 안 나고 경고도 없습니다. 조용히 잘 됩니다.
>
> 방어법 세 가지:
> 1. **`.env` 를 환경별로 분리** — 로컬은 `true`, 운영은 정책 검토 후 결정. `.env` 를 커밋하지 마세요.
> 2. **익명화** — `langsmith/anonymizer` 의 `createAnonymizer` 로 패턴을 마스킹한 뒤 전송합니다.
>    ```ts
>    import { createAnonymizer } from "langsmith/anonymizer";
>    const anonymizer = createAnonymizer([
>      { pattern: /\b\d{3}-?\d{2}-?\d{4}\b/, replace: "<ssn>" },
>    ]);
>    ```
> 3. **애초에 안 넣기** — [Step 11](../step-11-middleware-builtin/) 의 `piiMiddleware` 로 마스킹한 뒤 트레이싱합니다.
>
> 규제 산업(금융·의료)이라면 켜기 전에 법무/보안 검토를 받으세요. "일단 켜고 나중에 정리" 가 가장 위험합니다 — 한 번 올라간 데이터는 되돌릴 수 없습니다.

> 💡 **실무 팁**: `LANGSMITH_PROJECT` 를 환경별로 나누세요(`myagent-dev`, `myagent-staging`, `myagent-prod`). 안 나누면 개발 중 삽질 트레이스와 운영 트레이스가 `default` 에 섞여서 둘 다 못 씁니다. CI 평가용 프로젝트(`myagent-eval`)도 따로 두는 게 좋습니다.

---

## 19-3. 트레이스에 메타데이터·태그 붙이기

트레이싱을 켜고 일주일이 지나면 트레이스가 수만 개 쌓입니다. 그때 "어제 결제 실패한 그 사용자의 실행"을 찾아야 합니다. 미리 라벨을 붙여두지 않았다면 **못 찾습니다.**

라벨은 `invoke()` 의 **두 번째 인자(config)** 에 넣습니다.

```ts
await agent.invoke(
  { messages: [new HumanMessage("안녕")] },
  {
    runName: "greeting-flow",                        // 트레이스 목록에 표시될 이름
    tags: ["production", "weather-agent", "v1.0"],   // 필터용 (완전 일치 검색)
    metadata: {
      userId: "user-123",           // 특정 사용자 민원 추적
      sessionId: "sess-456",
      environment: "production",
      promptVersion: "2026-07-17",  // 프롬프트 버전 — A/B 비교의 핵심
    },
  }
);
```

세 가지를 구분해서 씁니다.

| 필드 | 타입 | 용도 | 예 |
|---|---|---|---|
| `runName` | `string` | 트레이스 목록에서 **이 실행이 뭔지** 한눈에 | `"greeting-flow"`, `"checkout-agent"` |
| `tags` | `string[]` | **필터링**. 값 종류가 적어야 함 (저카디널리티) | `["production", "v1.0"]` |
| `metadata` | `object` | **되짚기**. 값 종류가 많아도 됨 (고카디널리티) | `{ userId, sessionId, requestId }` |

`tags` 와 `metadata` 의 구분이 헷갈린다면 기준은 하나입니다: **"이 값으로 트레이스를 묶어서 볼 것인가(tags), 특정 하나를 찾아갈 것인가(metadata)"**.

> ⚠️ **함정**: `tags` 에 `userId` 를 넣지 마세요. 태그는 필터 UI에 **목록으로** 뜨는데, 사용자가 만 명이면 태그가 만 개가 됩니다. 필터가 쓸모없어지고 UI가 느려집니다. 고카디널리티 값은 전부 `metadata` 로 보내세요.

`metadata.promptVersion` 은 특히 값어치가 큽니다. 프롬프트를 고친 뒤 "고치기 전/후 실패율" 을 비교하려면 각 트레이스가 **어느 프롬프트로 돌았는지** 알아야 합니다. 이걸 안 붙여놓으면 나중에 절대 복원할 수 없습니다.

```ts
console.log("fakeModel.calls[0].options 의 키:", Object.keys(model.calls[0].options ?? {}));
```

**출력**
```
fakeModel.calls[0].options 의 키: [
  'writer',
  'interrupt',
  'control',
  'executionInfo',
  'signal',
  'promptIndex'
]
```

> ⚠️ **함정**: config의 `tags`/`metadata` 는 **콜백/트레이서로 흘러가지, 모델 호출 옵션에는 안 들어갑니다.** 위 출력이 그 증거입니다 — `fakeModel` 이 기록한 `calls[0].options` 에 `tags` 도 `metadata` 도 없습니다. 그래서 "메타데이터가 잘 붙었는지" 를 `fakeModel` 로 단위 테스트할 수는 없습니다. 라벨링이 제대로 됐는지는 LangSmith UI에서 눈으로 확인하거나, 커스텀 콜백 핸들러를 붙여서 검증해야 합니다.

> 💡 **실무 팁**: 라벨은 **애플리케이션 진입점 한 곳에서** 붙이세요. 호출 지점마다 손으로 넣으면 반드시 빠뜨립니다. 웹 요청 핸들러에서 `requestId`/`userId` 를 뽑아 config를 만드는 헬퍼 함수 하나를 두고 전 호출이 그걸 쓰게 하는 게 정석입니다.

---

## 19-4. LangSmith 없이 디버깅

LangSmith를 못 쓰는 상황이 있습니다. 보안 정책상 외부 전송이 금지됐거나, CI 안이거나, 그냥 지금 당장 5초 안에 뭔가 보고 싶을 때. 세 가지 방법이 있습니다.

### (a) 콘솔 콜백 — 가장 빠른 방법

```ts
import { ConsoleCallbackHandler } from "@langchain/core/tracers/console";

await agent.invoke(
  { messages: [new HumanMessage("부산 날씨?")] },
  { callbacks: [new ConsoleCallbackHandler()] }  // ← 이 한 줄
);
```

**출력** (일부 — 실제로는 훨씬 깁니다)
```
[chain/start] [1:chain:LangGraph] Entering Chain run with input: {
  "messages": [ ... ]
}
[tool/start] [1:chain:LangGraph > 6:chain:tools > 7:tool:get_weather] Entering Tool run with input: "{"city":"부산"}"
[tool/end] [1:chain:LangGraph > 6:chain:tools > 7:tool:get_weather] [0ms] Exiting Tool run with output: "..."
```

`[tool/start]` 줄의 `입력: {"city":"부산"}` 이 핵심입니다 — **모델이 도구에 정확히 어떤 인자를 넘겼는지** 가 보입니다. 도구가 이상하게 동작할 때 90%는 여기서 원인이 드러납니다.

> ⚠️ **함정**: `ConsoleCallbackHandler` 는 **모든 것을 전부** 찍습니다. 메시지 객체를 통째로 JSON 직렬화하기 때문에, 대화가 조금만 길어져도 터미널이 수천 줄로 폭발해서 정작 보고 싶은 게 파묻힙니다. 위 출력에서 `...` 로 줄인 부분이 실제로는 수백 줄입니다. **일회성 디버깅용**으로만 쓰고, 상시 로깅에는 절대 쓰지 마세요(운영에 켜두면 로그 비용이 폭발합니다). 상시 로깅은 (c)의 커스텀 미들웨어로 필요한 것만 찍는 게 맞습니다.

### (b) `streamMode: "debug"` — 노드 단위로 보기

에이전트는 내부적으로 LangGraph 그래프입니다([Step 17](../step-17-langgraph/)). `streamMode: "debug"` 를 주면 **노드가 시작하고 끝날 때마다** 이벤트를 받습니다.

```ts
for await (const ev of await agent.stream(
  { messages: [new HumanMessage("제주 인구?")] },
  { streamMode: "debug" }
)) {
  // 이벤트 구조는 결정적입니다: { step, type, timestamp, payload }
  const e = ev as { step: number; type: string; payload: { name?: string } };
  console.log(`  step=${e.step} type=${e.type} node=${e.payload?.name}`);
}
```

**출력**
```
  step=1 type=task node=model_request
  step=1 type=task_result node=model_request
  step=2 type=task node=tools
  step=2 type=task_result node=tools
  step=3 type=task node=model_request
  step=3 type=task_result node=model_request
```

이 출력은 (모델 응답과 달리) **구조가 결정적**입니다. 읽는 법:

| 필드 | 값 | 뜻 |
|---|---|---|
| `step` | `1`, `2`, `3` | 그래프의 몇 번째 단계인가 |
| `type` | `"task"` | 노드 실행 **시작**. `payload.input` 에 입력이 들어있음 |
| `type` | `"task_result"` | 노드 실행 **완료**. `payload.result` 에 결과가 들어있음 |
| `payload.name` | `"model_request"`, `"tools"` | 어느 노드인가 |

`model_request → tools → model_request` 라는 왕복이 눈에 보입니다. 이게 에이전트 루프의 정체입니다. **step이 계속 늘어나기만 하고 안 끝난다면 무한 루프**이고, `recursionLimit` 을 확인할 때입니다.

`streamMode` 는 `"debug"` 외에도 `"values"`, `"updates"`, `"messages"`, `"checkpoints"`, `"tasks"`, `"custom"`, `"tools"` 가 있습니다([Step 09](../step-09-streaming/) 참고). 디버깅엔 `"debug"`, 사용자에게 보여줄 땐 `"messages"` 입니다.

### (c) 커스텀 로깅 미들웨어 — 팀 표준으로

필요한 것만, 일관된 포맷으로 찍고 싶다면 미들웨어를 만듭니다([Step 12](../step-12-middleware-custom/)).

```ts
const loggingMiddleware = createMiddleware({
  name: "LoggingMiddleware",
  // 모델 호출을 감싸서 지연시간을 잰다
  wrapModelCall: async (request, handler) => {
    const started = Date.now();
    const response = await handler(request);
    console.log(`  [model] ${Date.now() - started}ms`);
    return response;
  },
  // 도구 호출을 감싸서 이름과 인자를 남긴다
  wrapToolCall: async (request, handler) => {
    const started = Date.now();
    console.log(`  [tool:start] ${request.toolCall.name} args=${JSON.stringify(request.toolCall.args)}`);
    const response = await handler(request);
    console.log(`  [tool:end]   ${request.toolCall.name} (${Date.now() - started}ms)`);
    return response;
  },
});

const agent = createAgent({ model, tools, middleware: [loggingMiddleware] });
```

**출력**
```
  [model] 1ms
  [tool:start] get_weather args={"city":"서울"}
  [tool:end]   get_weather (0ms)
  [model] 0ms
```

(`fakeModel` 이라 1ms입니다. 실제 모델이면 수백~수천 ms가 나옵니다.)

이 방식의 장점은 **여러분이 포맷을 통제한다**는 것입니다. JSON 구조화 로그로 찍어서 Datadog·CloudWatch로 보낼 수도 있고, 도구 인자에서 민감 필드만 지우고 찍을 수도 있습니다. 외부로 아무것도 안 나갑니다.

> ⚠️ **함정**: `wrapToolCall` 에서 **`handler(request)` 의 결과를 반드시 `return`** 해야 합니다. 로그만 찍고 `return` 을 빼먹으면 도구 결과가 `undefined` 가 되어 사라집니다. 에러는 안 납니다 — 모델이 빈 `ToolMessage` 를 받고 "정보를 못 가져왔다" 며 엉뚱한 답을 하거나, 같은 도구를 무한히 다시 부릅니다. 미들웨어를 붙였더니 에이전트가 갑자기 멍청해졌다면 이걸 먼저 의심하세요.

세 방법의 선택 기준:

| 방법 | 언제 | 장점 | 단점 |
|---|---|---|---|
| `ConsoleCallbackHandler` | 지금 당장 5초 안에 | 한 줄이면 끝 | 출력 폭발, 상시 사용 불가 |
| `streamMode: "debug"` | 루프 구조가 궁금할 때 | 노드 흐름이 한눈에, 구조가 결정적 | 메시지 내용은 직접 파야 함 |
| 커스텀 미들웨어 | 운영 상시 로깅 | 포맷 통제, 외부 전송 없음 | 직접 만들어야 함 |
| LangSmith | 팀·운영·평가 | 검색·비교·평가 전부 | 데이터 외부 전송 |

---

## 19-5. 에이전트 테스트

여기서 발상을 하나 뒤집어야 합니다. "LLM은 비결정적이라 테스트가 안 된다"는 건 **핑계**입니다. 에이전트에서 비결정적인 부분은 **모델뿐**이고, 나머지는 전부 평범한 코드입니다.

| 구성요소 | 결정적인가 | 테스트 방법 |
|---|---|---|
| **도구(tool)** | ✅ 완전히 결정적 | 그냥 단위 테스트. 모델 필요 없음 |
| **미들웨어** | ✅ 결정적 | 단위 테스트 |
| **에이전트 루프** | ✅ 결정적 | `fakeModel` 로 모델만 갈아끼우면 결정적 |
| **모델의 판단** | ❌ 비결정적 | 통합 테스트 / 평가(19-6) |

### (a) 도구 단위 테스트 — 모델이 필요 없다

```ts
check("서울 날씨 조회", (await getWeather.invoke({ city: "서울" })) === "서울: 맑음, 23도");
check("없는 도시는 '정보 없음'", (await getWeather.invoke({ city: "런던" })) === "런던: 날씨 정보 없음");
check("인구는 천단위 콤마", (await getPopulation.invoke({ city: "부산" })) === "부산: 3,300,000명");
```

**출력**
```
  ✓ 서울 날씨 조회
  ✓ 없는 도시는 '정보 없음'
  ✓ 인구는 천단위 콤마
```

이게 왜 중요하냐면 — **도구가 틀리면 모델이 아무리 똑똑해도 답이 틀립니다.** 그리고 그건 모델 없이 1ms 만에 잡힙니다. 에이전트 테스트 노력의 상당 부분은 여기 있어야 합니다.

특히 **폴백 경로**("정보 없음")를 꼭 테스트하세요. 이 문자열이 모델에게 그대로 전달되고, 모델은 이걸 보고 사용자에게 뭐라고 할지 결정합니다. 폴백 문자열이 애매하면("null", "") 모델이 그걸 데이터로 착각하고 이상한 답을 합니다.

### (b) `fakeModel()` 로 모델 갈아끼우기

`langchain` 은 테스트용 가짜 모델을 내장하고 있습니다. **빌더 스타일**로 응답을 미리 대본처럼 짜 넣습니다.

```ts
import { fakeModel } from "langchain";

const model = fakeModel()
  .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "call_1" }])
  .respond(new AIMessage("서울은 맑고 23도입니다."));

const agent = createAgent({ model, tools });
const result = await agent.invoke({ messages: [new HumanMessage("서울 날씨?")] });

check("메시지 4개 (human → ai → tool → ai)", result.messages.length === 4);
check("도구가 실제로 실행되어 ToolMessage 가 생김", result.messages[2].getType() === "tool");
check("ToolMessage 내용이 도구의 실제 반환값", result.messages[2].text === "서울: 맑음, 23도");
check("최종 답은 AI 메시지", result.messages.at(-1)?.getType() === "ai");
check("모델은 2번 호출됨", model.callCount === 2);
```

**출력**
```
  ✓ 메시지 4개 (human → ai → tool → ai)
  ✓ 도구가 실제로 실행되어 ToolMessage 가 생김
  ✓ ToolMessage 내용이 도구의 실제 반환값
  ✓ 최종 답은 AI 메시지
  ✓ 모델은 2번 호출됨
  (참고) tool_calls 를 담은 AI 메시지의 content = "서울 날씨?"
```

주목할 점: **도구는 진짜로 실행됩니다.** 가짜인 건 모델뿐입니다. `ToolMessage` 의 내용 `"서울: 맑음, 23도"` 는 실제 `getWeather` 가 계산한 값입니다. 즉 "모델이 이 도구를 이 인자로 부르면, 우리 코드가 올바르게 동작하는가" 를 검증하는 것입니다.

`fakeModel()` 의 API 전체입니다.

| 메서드 | 설명 |
|---|---|
| `.respond(message)` | 다음 호출에 반환할 `AIMessage` 를 큐에 넣기 |
| `.respond(error)` | 다음 호출에 던질 `Error` 를 큐에 넣기 |
| `.respond(factory)` | `(messages) => BaseMessage \| Error` 동적 응답 |
| `.respondWithTools(toolCalls)` | 도구 호출을 담은 AI 메시지를 큐에 넣기. `name`/`args` 필수, `id` 선택 |
| `.alwaysThrow(error)` | 큐와 무관하게 **매번** 이 에러를 던지기 |
| `.structuredResponse(value)` | `.withStructuredOutput()` 이 반환할 값 지정 |
| `.bindTools(tools)` | 도구 바인딩. 응답 큐와 호출 기록을 **공유** |
| `.calls` | 호출 기록 `{ messages, options }[]` (읽기 전용) |
| `.callCount` | 호출 횟수 |

응답은 **FIFO** 로 소비됩니다 — `invoke()` 한 번에 큐에서 하나씩.

> ⚠️ **함정 (가장 자주 틀리는 것)**: **큐에 넣을 응답 개수를 잘못 세는 것**입니다. 도구를 1번 부르는 궤적은 모델 응답이 **2개** 필요합니다(도구 호출 결정 1번 + 결과 보고 최종 답 1번). 도구를 2번 부르면 **3개**입니다. 공식은 **`도구 호출 횟수 + 1`** 입니다. 모자라면 이렇게 터집니다:
> ```
> FakeModel: no response queued for invocation 1 (1 total queued).
> ```
> (invocation 번호는 0부터 셉니다.) 이 에러가 나면 대본이 짧은 것이지 코드가 틀린 게 아닙니다.

> ⚠️ **함정**: `respondWithTools()` 가 만드는 AI 메시지의 **`content` 는 입력 메시지를 그대로 되돌려줍니다.** 위 출력의 `(참고)` 줄을 보세요 — content가 `"서울 날씨?"` 입니다. 모델이 그렇게 답한 게 아니라 fake가 입력을 에코한 것입니다. 여기에 단언을 걸면(`expect(msg.content).toBe("...")`) 의미 없는 테스트가 됩니다. `respondWithTools` 로 만든 메시지는 **`tool_calls` 만** 검증하세요. `id` 를 생략하면 `fake_tc_1` 처럼 자동 생성됩니다.

### (c) 에러 경로 테스트 — fake의 진가

실제 모델로는 재현이 거의 불가능한 상황을 fake로는 1초 만에 만들 수 있습니다. 모델 장애 시 재시도가 도는지, 폴백 모델로 넘어가는지를 테스트하려면 **장애를 일으킬 수 있어야** 합니다.

```ts
const failing = fakeModel().alwaysThrow(new Error("service unavailable"));
const agent = createAgent({ model: failing, tools });
let caught = "";
try {
  await agent.invoke({ messages: [new HumanMessage("서울 날씨?")] });
} catch (e) {
  caught = (e as Error).message;
}
check("모델 장애가 위로 전파된다", caught === "service unavailable");
```

**출력**
```
  ✓ 모델 장애가 위로 전파된다
  큐 소진 에러: FakeModel: no response queued for invocation 1 (1 total queued).
  ✓ 큐 소진 시 명확한 에러
```

이걸 `modelRetryMiddleware`([Step 11](../step-11-middleware-builtin/))와 조합하면 "3번 재시도 후 포기하는가" 를 API 키 없이, 0.1초 만에 검증할 수 있습니다. rate limit(`.respond(new Error("rate limit exceeded"))`), 타임아웃, 부분 실패 전부 같은 방식입니다.

### (d) 통합 테스트 — 실제 모델을 쓰는 층

`fakeModel` 로 못 잡는 게 하나 있습니다: **모델이 실제로 어떤 도구를 고르는가.** 대본을 짜 넣었으니 당연합니다. 이건 실제 모델로만 확인됩니다.

LangChain은 vitest/jest용 커스텀 매처를 제공합니다.

```ts
// vitest.setup.ts
import { langchainMatchers } from "@langchain/core/testing";
expect.extend(langchainMatchers);
```

```ts
// agent.int.test.ts
test("agent calls weather tool", async () => {
  const agent = createAgent({ model: "claude-sonnet-4-6", tools: [getWeather] });
  const result = await agent.invoke({
    messages: [new HumanMessage("What's the weather in SF?")],
  });

  const aiMsg = result.messages.find((m) => AIMessage.isInstance(m) && m.tool_calls?.length);
  expect(aiMsg).toContainToolCall({ name: "get_weather" });
  expect(result.messages.at(-1)).toBeAIMessage();
});
```

주요 매처: `toBeAIMessage`, `toBeHumanMessage`, `toBeToolMessage`, `toBeSystemMessage`, `toHaveToolCalls`, `toHaveToolCallCount`, `toContainToolCall`, `toHaveToolMessages`, `toHaveBeenInterrupted`, `toHaveStructuredResponse`, 그리고 스트리밍용 `toHaveStreamText`/`toHaveStreamToolCalls`/`toHaveStreamUsage`.

통합 테스트는 **분리해서** 돌립니다. 파일명을 `*.int.test.ts` 로 두고 별도 모드로 실행합니다.

```ts
// vitest.config.ts
import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig((env) => {
  if (env.mode === "int") {
    return {
      test: {
        testTimeout: 100_000,          // 실제 모델은 느립니다
        include: ["**/*.int.test.ts"],
        setupFiles: ["dotenv/config"],
      },
    };
  }
  return {
    test: {
      testTimeout: 30_000,
      exclude: ["**/*.int.test.ts", ...configDefaults.exclude],  // 평소엔 제외
    },
  };
});
```

```json
{
  "scripts": {
    "test": "vitest",
    "test:integration": "vitest --mode int"
  }
}
```

키가 없는 환경에서는 알아서 건너뛰게 합니다.

```ts
test.skipIf(!process.env.OPENAI_API_KEY)("agent responds with tool call", async () => { /* ... */ });
```

> ⚠️ **함정**: **단위 테스트에서 실제 모델을 부르지 마세요.** 세 가지가 동시에 망가집니다. (1) **느리다** — 테스트 1개에 3초, 100개면 5분. 아무도 안 돌립니다. (2) **비싸다** — CI가 PR마다 돌면 월 청구서가 실서비스보다 커집니다. (3) **불안정하다(flaky)** — 모델이 같은 입력에 다르게 답해서 아무 코드 변경 없이 빨간불이 뜹니다. 그게 반복되면 팀은 실패를 무시하기 시작하고, 그 순간 테스트 스위트는 죽습니다. **`temperature: 0` 을 줘도 결정성은 보장되지 않습니다** — 인프라·양자화·배치 처리 때문에 같은 요청도 다른 답이 나옵니다.
>
> 규칙: **단위 테스트 = `fakeModel`(매 커밋). 통합 테스트 = 실제 모델(CI/배포 전만).**

> 💡 **실무 팁 — 통합 테스트 비용 줄이기**: (1) 도구 호출 여부만 볼 거면 **작은 모델**을 쓰세요 (`"gemini-3.1-flash-lite"` 등). (2) `modelArgs: { maxTokens: 256 }` 로 응답 길이를 자르세요 — 어차피 `tool_calls` 만 볼 거면 긴 답이 필요 없습니다. (3) **테스트 1개당 동작 1개**, 단일 턴으로. (4) 첫 실행에서 HTTP 응답을 녹화하고 이후엔 재생하는 방식도 있습니다.
> ```ts
> const agent = createAgent({
>   model: "gemini-3.1-flash-lite",
>   tools: [getWeather],
>   modelArgs: { maxTokens: 256 },
> });
> ```

---

## 19-6. 평가(evaluation)

테스트는 "코드가 안 깨졌나" 를 묻습니다(통과/실패). 평가는 **"품질이 얼마나 좋은가"** 를 묻습니다(점수). 프롬프트를 고쳤을 때 테스트는 다 통과하는데 답변 품질이 떨어질 수 있습니다. 그걸 잡는 게 평가입니다.

평가의 최소 구성은 네 가지입니다.

1. **데이터셋** — 입력과 기대 결과의 목록
2. **실행 함수** — 입력을 받아 에이전트를 돌리는 함수
3. **평가자(evaluator)** — 출력에 점수를 매기는 함수
4. **집계** — 점수를 모아 판단

### 데이터셋

```ts
type Example = {
  id: string;
  input: string;
  referenceOutput: string;   // 기대 답변
  referenceTools: string[];  // 기대 도구 호출 순서 (19-7 에서 사용)
};

const dataset: Example[] = [
  { id: "ex-1", input: "서울 날씨 알려줘", referenceOutput: "맑음, 23도", referenceTools: ["get_weather"] },
  { id: "ex-2", input: "부산 인구는?", referenceOutput: "3,300,000명", referenceTools: ["get_population"] },
  { id: "ex-3", input: "제주 날씨랑 인구 둘 다", referenceOutput: "비, 21도 / 670,000명",
    referenceTools: ["get_weather", "get_population"] },
];
```

> 💡 **실무 팁**: 데이터셋은 상상해서 만들지 마세요. **운영 트레이스에서 실패한 실행을 가져다 넣으세요.** 19-2/19-3에서 트레이싱과 라벨링을 해둔 이유가 이겁니다. LangSmith UI에서 실패한 트레이스를 데이터셋에 바로 추가할 수 있습니다. 20개짜리 진짜 실패 사례가 200개짜리 상상 데이터셋보다 낫습니다.

### 평가자 (1) — 결정적 매칭

**쓸 수 있으면 이걸 쓰세요.** 싸고, 빠르고, 안 틀립니다.

```ts
function containsEvaluator(outputs: { answer: string }, referenceOutput: string) {
  const keys = referenceOutput.split(" / ");
  const hit = keys.filter((k) => outputs.answer.includes(k)).length;
  return { key: "contains", score: hit / keys.length };
}
```

### 평가자 (2) — LLM-as-judge

정답이 하나로 안 떨어질 때(요약 품질, 어조, 도움이 되는가) 모델에게 채점을 시킵니다. 이론과 편향 종류는 [LLM Judge](/ai/9990-LLMJudge) 에 정리돼 있습니다. 여기서는 실행 방법만 봅니다.

```ts
async function llmJudgeEvaluator(outputs: { answer: string }, referenceOutput: string) {
  // 실전에서는 아래 model 을 "anthropic:claude-sonnet-4-6" 등으로 바꿉니다.
  const judge = fakeModel().structuredResponse({
    score: outputs.answer.includes(referenceOutput.split(" / ")[0]) ? 1 : 0,
    reasoning: "기대 답의 핵심 키워드 포함 여부로 판정",
  });
  const judged = (await judge
    .withStructuredOutput(z.object({ score: z.number(), reasoning: z.string() }))
    .invoke(`기대: ${referenceOutput}\n실제: ${outputs.answer}\n0~1 로 채점하라.`)) as {
    score: number;
    reasoning: string;
  };
  return { key: "llm_judge", score: judged.score, comment: judged.reasoning };
}
```

(`as { score, reasoning }` 는 `fakeModel` 의 `withStructuredOutput` 이 `{ raw, parsed }` 까지 포함한 유니온 타입을 반환하도록 선언돼 있어서 붙인 것입니다. 실제 모델에서는 이 단언 없이 바로 좁혀집니다.)

핵심은 **`withStructuredOutput` 으로 점수 형식을 강제**하는 것입니다([Step 05](../step-05-structured-output/)). judge에게 자유 텍스트로 답하게 하면 "음, 대체로 좋은데 8점 정도?" 같은 걸 파싱해야 합니다. `reasoning` 필드를 같이 받는 것도 중요합니다 — judge가 왜 그 점수를 줬는지 봐야 judge를 검증할 수 있습니다.

### 평가 실행

**출력**
```
  ex-1: contains=1.00 llm_judge=1.00
  ex-2: contains=1.00 llm_judge=1.00
  ex-3: contains=1.00 llm_judge=1.00
  → ex-3 도 최종 답 기준으로는 만점입니다. 다음 절에서 이게 왜 위험한지 봅니다.
```

**전부 만점입니다.** 그런데 `ex-3` 의 에이전트는 사실 `get_population` 을 부르지 않았습니다. 인구 숫자를 **지어냈는데** 우연히 맞았습니다. 다음 절이 이 이야기입니다.

> ⚠️ **함정 (평가 데이터셋 오염, contamination)**: 데이터셋의 기대 답을 프롬프트나 도구 설명에 넣지 마세요. "예: '서울 날씨는?' 이라고 물으면 '맑음, 23도' 라고 답해라" 같은 few-shot 예시가 평가 데이터셋과 겹치는 순간, 점수는 **거짓으로 오릅니다.** 에이전트가 문제를 푼 게 아니라 답을 외운 것이기 때문입니다. 점수는 95%인데 실서비스 만족도는 그대로인 상황이 이렇게 생깁니다. **평가 데이터셋과 프롬프트 예시는 물리적으로 분리하고, 데이터셋에 새 예제를 추가할 때 프롬프트에 이미 있는 문구가 아닌지 확인하세요.**

> ⚠️ **함정**: **LLM-as-judge도 틀립니다.** judge는 그냥 또 하나의 LLM이고, 위치 편향(먼저 나온 답을 선호), 길이 편향(긴 답을 선호), 자기 선호 편향(자기 계열 모델의 답을 선호)이 있습니다. 그래서 **judge 자체를 검증해야 합니다**: 사람이 채점한 20~50개 샘플을 준비하고, judge 점수와 사람 점수의 일치율을 재세요. 일치율이 낮으면 고쳐야 할 건 에이전트가 아니라 **judge 프롬프트**입니다. 검증 안 된 judge로 매긴 점수는 숫자처럼 생긴 노이즈입니다. 자세한 편향 목록과 완화법은 [LLM Judge](/ai/9990-LLMJudge) 를 보세요.

---

## 19-7. 궤적(trajectory) 평가 — 이 스텝의 핵심

19-6의 평가는 전부 **최종 답**만 봤습니다. 그런데 에이전트의 가치는 최종 답이 아니라 **거기 도달하는 과정**에 있습니다. 질문을 바꿔야 합니다: "답이 맞았나?" 가 아니라 **"올바른 도구를 올바른 순서로 불렀나?"**

궤적은 새로 만드는 게 아닙니다. 19-1에서 봤듯 `messages` 배열에 이미 있습니다.

```ts
function extractTrajectory(messages: BaseMessage[]): string[] {
  return messages
    .filter((m): m is AIMessage => m.getType() === "ai")
    .flatMap((m) => (m.tool_calls ?? []).map((tc) => tc.name));
}
```

이제 기대 궤적과 비교합니다. 비교 모드가 네 가지 있습니다.

```ts
type MatchMode = "strict" | "unordered" | "superset" | "subset";

function trajectoryMatch(actual: string[], reference: string[], mode: MatchMode): boolean {
  const sortedA = [...actual].sort();
  const sortedR = [...reference].sort();
  switch (mode) {
    case "strict":     // 같은 도구를 같은 순서로
      return actual.length === reference.length && actual.every((t, i) => t === reference[i]);
    case "unordered":  // 같은 도구 집합, 순서 무관
      return sortedA.length === sortedR.length && sortedA.every((t, i) => t === sortedR[i]);
    case "superset":   // 기대한 건 다 불렀고, 추가로 더 불러도 OK
      return reference.every((t) => actual.includes(t));
    case "subset":     // 기대 범위를 벗어난 도구는 안 불렀다
      return actual.every((t) => reference.includes(t));
  }
}
```

| 모드 | 통과 조건 | 언제 쓰나 |
|---|---|---|
| `strict` | 같은 도구, 같은 순서 | 순서가 **계약**일 때. 예: 권한 조회 **후에만** 결제 |
| `unordered` | 같은 도구 집합, 순서 무관 | **대부분의 경우.** 필요한 정보는 다 가져오되 순서는 모델 자유 |
| `superset` | 기대한 도구를 모두 포함 | "최소한 이것들은 불러라" (추가 호출 허용) |
| `subset` | 기대 범위 밖 도구 없음 | **안전 경계.** "이 범위 밖은 건드리지 마라" |

### 최종 답과 궤적이 갈리는 순간

**출력**
```
  ex-1: 궤적=[get_weather] 기대=[get_weather] strict=PASS 최종답=PASS
  ex-2: 궤적=[get_population] 기대=[get_population] strict=PASS 최종답=PASS
  ex-3: 궤적=[get_weather] 기대=[get_weather → get_population] strict=FAIL 최종답=PASS
  → ex-3: 최종답 PASS / 궤적 FAIL. get_population 을 안 부르고 인구를 '지어냈습니다'.
    최종 답만 봤다면 환각을 만점으로 통과시켰을 겁니다.
```

**`ex-3` 을 보세요. 최종답은 PASS, 궤적은 FAIL 입니다.**

무슨 일이 있었냐면: 에이전트는 `get_weather` 만 부르고 `get_population` 은 건너뛴 채, 인구 숫자 "670,000명" 을 **자기 기억에서 지어냈습니다.** 이번엔 우연히 맞았습니다. 그래서 최종답 평가는 만점을 줬습니다.

이게 왜 심각하냐면:

- 그 숫자는 **출처가 없습니다.** 도구를 안 거쳤으니 검증도 안 됐습니다.
- 데이터가 바뀌면 **즉시 틀립니다.** 도구를 불렀다면 항상 최신값을 줬을 텐데, 모델 기억은 학습 시점에 고정입니다.
- 그리고 **아무도 모릅니다.** 최종답 평가는 계속 100%를 보고할 테니까요.

> ⚠️ **함정 (이 스텝의 결론)**: **최종 답만 평가하면 "운 좋게 맞은 답"과 "제대로 풀어서 맞은 답"을 구분할 수 없습니다.** 위 `ex-3` 처럼 도구를 건너뛰고 환각으로 답했는데 우연히 맞으면, 최종답 평가는 만점을 줍니다. 그 에이전트는 실서비스에서 데이터가 바뀌는 순간 조용히 틀리기 시작합니다. **RAG나 도구 기반 에이전트라면 궤적 평가는 선택이 아니라 필수입니다** — 에이전트의 존재 이유가 "지어내지 말고 도구로 확인해라" 인데, 그걸 검사하지 않으면 에이전트를 쓰는 의미가 없습니다.

> ⚠️ **함정**: 반대로 **`strict` 를 남발하지 마세요.** 모델이 `get_population → get_weather` 순으로 불러도 똑같이 정답인 경우가 대부분입니다. 그런데 `strict` 는 이걸 FAIL로 처리합니다. 그러면 아무 문제 없는 변경에 테스트가 빨간불이 뜨고, 팀은 곧 그 실패를 무시하게 됩니다. **순서가 진짜 요구사항일 때만 `strict`, 기본은 `unordered`.**

### agentevals — 직접 만들지 않아도 됩니다

위 `trajectoryMatch` 는 원리를 이해하려고 직접 짠 것입니다. 실무에서는 LangChain의 `agentevals` 패키지를 씁니다.

```bash
npm install agentevals @langchain/core
```

```ts
import { createTrajectoryMatchEvaluator } from "agentevals";

const evaluator = createTrajectoryMatchEvaluator({
  trajectoryMatchMode: "strict",  // "strict" | "unordered" | "superset" | "subset"
});
```

기준 궤적이 없거나 "효율적이었나" 같은 미묘한 걸 보고 싶으면 궤적용 LLM judge를 씁니다.

```ts
import { createTrajectoryLLMAsJudge, TRAJECTORY_ACCURACY_PROMPT } from "agentevals";

const evaluator = createTrajectoryLLMAsJudge({
  model: "openai:o3-mini",
  prompt: TRAJECTORY_ACCURACY_PROMPT,
});

// 기준 궤적을 함께 줄 수도 있습니다
import { TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE } from "agentevals";
```

`createTrajectoryLLMAsJudge` 는 기준 궤적이 **없어도** 동작합니다(모델이 rubric으로 판단). 유연하지만 LLM 호출 비용이 들고 덜 결정적입니다. **가능하면 `createTrajectoryMatchEvaluator`, 안 되면 judge** 순으로 고르세요.

---

## 19-8. 회귀 방지 — CI에 평가 붙이기

평가를 손으로 돌리면 안 돌립니다. 바쁘면 건너뛰고, 건너뛴 날 사고가 납니다. **CI에 붙여야** 의미가 생깁니다.

핵심 발상: **CI의 목적은 점수를 보여주는 게 아니라 기준 미달이면 빌드를 깨는 것입니다.** 대시보드는 아무도 안 봅니다. 빨간 X는 봅니다.

```ts
const THRESHOLD = 0.9;  // 궤적 정확도 최소치
let trajPass = 0;
for (const ex of dataset) {
  const out = await runAgentOn(ex);
  if (trajectoryMatch(extractTrajectory(out.messages), ex.referenceTools, "strict")) trajPass++;
}
const score = trajPass / dataset.length;
console.log(`  궤적 정확도: ${(score * 100).toFixed(0)}% (기준 ${THRESHOLD * 100}%)`);
if (score < THRESHOLD) {
  console.log(`  → CI 라면 여기서 process.exit(1). 기준 미달이므로 머지 차단.`);
}
```

**출력**
```
  궤적 정확도: 67% (기준 90%)
  → CI 라면 여기서 process.exit(1). 기준 미달이므로 머지 차단.
```

### LangSmith로 실행하기

점수를 시간에 따라 추적하려면 LangSmith에 실험으로 기록합니다. 두 가지 방법이 있습니다.

**(1) vitest/jest 통합** — 테스트처럼 쓰고 결과가 LangSmith에 쌓입니다.

```ts
import * as ls from "langsmith/vitest";

ls.describe("trajectory accuracy", () => {
  ls.test(
    "accurate trajectory",
    {
      inputs: { messages: [/* ... */] },
      referenceOutputs: { messages: [/* ... */] },
    },
    async ({ inputs, referenceOutputs }) => {
      // 에이전트 실행 + 평가자 호출
    }
  );
});
```

**(2) `evaluate` 함수** — LangSmith에 올려둔 데이터셋 이름으로 한 번에 돌립니다.

```ts
import { evaluate } from "langsmith/evaluation";

await evaluate(runAgent, {
  data: "your_dataset_name",       // LangSmith 데이터셋 이름
  evaluators: [trajectoryEvaluator],
});
```

둘 다 아래 환경변수가 필요합니다.

```bash
export LANGSMITH_API_KEY="your_langsmith_api_key"
export LANGSMITH_TRACING="true"
```

### CI 파이프라인 구성

층을 나눠서 **각각 다른 주기**로 돌리는 게 핵심입니다.

| 층 | 주기 | 모델 | 비용/시간 | 실패 시 |
|---|---|---|---|---|
| 도구 단위 테스트 | **매 커밋** | 없음 | 0원 / 1초 | 즉시 차단 |
| 에이전트 테스트 (`fakeModel`) | **매 커밋** | 없음 | 0원 / 5초 | 즉시 차단 |
| 통합 테스트 (`*.int.test.ts`) | **PR** | 작은 모델 | 수백원 / 2분 | 차단 |
| 평가 (궤적 + judge) | **PR / 배포 전** | 실제 모델 | 수천원 / 10분 | 기준 미달 시 차단 |

```yaml
# .github/workflows/ci.yml (발췌)
jobs:
  unit:
    steps:
      - run: npm test              # fakeModel — 키 불필요, 매번
  eval:
    if: github.event_name == 'pull_request'
    steps:
      - run: npm run test:integration
        env:
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
          LANGSMITH_API_KEY: ${{ secrets.LANGSMITH_API_KEY }}
          LANGSMITH_TRACING: "true"
          LANGSMITH_PROJECT: "myagent-eval"   # 운영 트레이스와 섞이지 않게
```

> 💡 **실무 팁 — 기준선(baseline)을 어떻게 정하나**: 처음부터 90%를 요구하면 CI가 항상 빨간불이라 아무도 안 봅니다. 현실적인 순서는 (1) 현재 점수를 측정한다(예: 72%), (2) **기준을 현재보다 살짝 아래**로 잡는다(70%), (3) 개선될 때마다 기준을 올린다. 이러면 CI는 "더 좋아져라"가 아니라 **"나빠지지 마라"** 는 회귀 게이트가 됩니다. 그게 CI가 해야 할 일입니다.

> ⚠️ **함정**: 평가 CI를 **모든 푸시마다** 돌리지 마세요. 실제 모델 호출이 들어가므로 (1) 청구서가 폭발하고 (2) 10분씩 걸려서 개발 흐름이 끊기고 (3) 모델 비결정성 때문에 같은 코드가 어떤 날은 통과, 어떤 날은 실패합니다. PR 단위 또는 배포 전으로 제한하고, 점수가 경계선에서 흔들린다면 **여러 번 돌려 평균**을 내거나 기준을 낮추세요.

---

## 19-9. LangGraph Studio로 시각적 디버깅

지금까지는 전부 텍스트였습니다. **Studio** 는 에이전트를 그래프로 보면서 디버깅하는 무료 시각 도구입니다.

Studio는 코드가 아니라 **설정**입니다. 세 가지가 필요합니다.

**1) 에이전트를 export 하기**

```ts
// src/agent.ts
export const agent = createAgent({ model: "anthropic:claude-sonnet-4-6", tools, systemPrompt: "..." });
```

**2) `langgraph.json`**

```json
{
  "dependencies": ["."],
  "graphs": {
    "agent": "./src/agent.ts:agent"
  },
  "env": ".env"
}
```

`"agent": "./src/agent.ts:agent"` 는 **"파일경로:export이름"** 형식입니다. export 이름이 안 맞으면 Studio가 그래프를 못 찾습니다.

**3) 개발 서버 실행**

```bash
npx @langchain/langgraph-cli dev
```

접속:
- **API**: `http://127.0.0.1:2024`
- **Studio UI**: `https://smith.langchain.com/studio/?baseUrl=http://127.0.0.1:2024`

(Safari라면 `--tunnel` 플래그를 붙여야 합니다 — Safari가 localhost로의 보안 연결을 막습니다.)

Studio에서 할 수 있는 것:

| 기능 | 설명 |
|---|---|
| **그래프 시각화** | 노드와 엣지를 그림으로. 실행 중 어느 노드가 활성인지 실시간 표시 |
| **핫 리로드** | 코드를 고치면 즉시 반영. 서버 재시작 불필요 |
| **전체 실행 추적** | 프롬프트, 도구 인자, 토큰/지연 지표 전부 |
| **스레드 리플레이** | **임의의 지점부터 다시 실행.** 5단계에서 틀어졌으면 5단계 상태를 고쳐서 거기서부터 재실행 |
| **예외 캡처** | 에러를 그때의 상태와 함께 보여줌 |

**스레드 리플레이**가 Studio의 진짜 가치입니다. 텍스트 로그로 디버깅하면 매번 처음부터 다시 돌려야 합니다 — 10단계짜리 에이전트에서 9단계가 틀렸다면 앞의 8단계를 매번 다시 (돈 내고) 실행해야 합니다. Studio는 그 지점의 상태를 고쳐서 거기서부터 재실행할 수 있습니다. 이건 체크포인터([Step 10](../step-10-memory/))가 있어서 가능한 것입니다.

> 💡 **실무 팁**: Studio를 쓸 때 `LANGSMITH_TRACING=false` 로 두면 트레이스를 안 올리면서도 Studio는 씁니다(로컬 서버가 직접 상태를 보여주므로). 민감 데이터를 다루는 개발 단계에서 유용한 조합입니다.

> ⚠️ **함정**: `langgraph.json` 의 `graphs` 값에는 **에이전트 인스턴스**를 export한 이름을 적어야 합니다. 함수(`createMyAgent`)를 export해놓고 그 이름을 적으면 Studio가 그래프를 인식하지 못합니다. 또 `"env": ".env"` 를 빠뜨리면 API 키를 못 읽어서 모델 호출이 전부 실패합니다 — Studio는 셸 환경변수가 아니라 이 설정을 봅니다.

---

## 19-10. 종합 — 3층 구조

세 가지는 각각 다른 질문에 답합니다. 하나로 다른 걸 대신할 수 없습니다.

```
1층 관측(observability): 무슨 일이 있었나?   → LangSmith 트레이스 / ConsoleCallbackHandler / streamMode debug
2층 테스트(test):        코드가 안 깨졌나?   → 도구 단위 테스트 + fakeModel (빠름, 결정적, 무료)
3층 평가(eval):          품질이 안 떨어졌나? → 데이터셋 + 궤적 평가자 + LLM judge (느림, 비쌈, CI 게이트)
```

그리고 세 층은 **순환**합니다. 이게 실제 운영 루프입니다.

1. **관측**으로 운영에서 실패한 실행을 발견한다 (트레이스)
2. 그 실패를 **평가 데이터셋에 추가**한다
3. 고친다
4. **테스트**로 안 깨졌는지, **평가**로 그 실패가 재현 안 되는지 확인한다
5. CI가 앞으로 영원히 그 회귀를 막는다

19-3에서 라벨링을 강조한 이유가 5번에 있습니다. 라벨 없는 트레이스는 1번에서 못 찾고, 못 찾으면 이 루프가 시작되지 않습니다.

---

## 정리

| 도구 | 무엇을 하나 | import / 설정 |
|---|---|---|
| LangSmith 트레이싱 | 모든 실행을 저장·검색 | `LANGSMITH_TRACING=true`, `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT` |
| `LangChainTracer` | 특정 실행만 선택적 추적 | `@langchain/core/tracers/tracer_langchain` |
| `runName`/`tags`/`metadata` | 트레이스 라벨링 | `invoke(input, { runName, tags, metadata })` |
| `createAnonymizer` | 민감 패턴 마스킹 후 전송 | `langsmith/anonymizer` |
| `ConsoleCallbackHandler` | 즉석 콘솔 디버깅 | `@langchain/core/tracers/console` |
| `streamMode: "debug"` | 노드 단위 이벤트 (`task`/`task_result`) | `agent.stream(input, { streamMode: "debug" })` |
| `createMiddleware` | 커스텀 로깅/감사 | `langchain` |
| `fakeModel()` | 모델을 대본으로 대체 | `langchain` |
| `langchainMatchers` | vitest/jest 커스텀 매처 | `@langchain/core/testing` |
| `agentevals` | 궤적 평가자 | `npm i agentevals` |
| `evaluate` / `langsmith/vitest` | LangSmith 실험 실행 | `langsmith/evaluation` |
| LangGraph Studio | 시각적 디버깅·리플레이 | `langgraph.json` + `npx @langchain/langgraph-cli dev` |

**`fakeModel()` 응답 개수 공식**: `도구 호출 횟수 + 1`

**핵심 함정 3가지**

1. **트레이싱은 데이터를 외부로 보낸다.** `LANGSMITH_TRACING=true` 한 줄이 프롬프트·도구 인자·응답을 전부 LangSmith 서버로 올립니다. 조용히 잘 됩니다 — 그래서 위험합니다. 민감정보가 프롬프트에 들어간다면 켜기 전에 검토하고, `createAnonymizer` 나 `piiMiddleware` 로 방어하세요.
2. **최종 답만 평가하면 운 좋게 맞은 답을 못 걸러낸다.** 도구를 건너뛰고 환각으로 답했는데 우연히 맞으면 만점이 나옵니다. 그 에이전트는 데이터가 바뀌는 순간 조용히 틀리기 시작합니다. **궤적 평가**로 "올바른 도구를 올바른 순서로 불렀나"를 봐야 합니다.
3. **단위 테스트에서 실제 모델을 부르면 느리고 비싸고 불안정하다.** flaky한 테스트는 무시당하고, 무시당하는 테스트는 없는 것과 같습니다. `temperature: 0` 도 결정성을 보장하지 않습니다. **단위 테스트는 `fakeModel`, 실제 모델은 통합 테스트/평가 층으로.**

**추가로 기억할 것**: LLM-as-judge도 틀립니다 — 사람이 채점한 샘플로 judge 자체를 먼저 검증하세요. 평가 데이터셋이 프롬프트 예시와 겹치면(오염) 점수가 거짓으로 오릅니다.

---

## 연습문제

1. **도구 단위 테스트** — `getWeather` / `getPopulation` 을 모델 없이 호출해 (1) 제주 날씨 정확값 (2) 없는 도시("런던")의 폴백 문자열 (3) 인구의 천단위 콤마를 검증하세요.
2. **2단계 도구 호출** — "제주 날씨랑 인구 알려줘" 에 대해 `get_weather → get_population → 최종 답` 순으로 동작하도록 `fakeModel` 을 스크립팅하고, 메시지 6개 / `ToolMessage` 2개 / `callCount === 3` 을 검증하세요. (힌트: 응답 개수 공식)
3. **궤적 추출** — `messages` 에서 호출된 도구 이름을 순서대로 뽑는 `extractTrajectory(messages)` 를 구현하고 문제 2의 결과에 적용하세요.
4. **unordered 매칭** — `actual = ["get_population","get_weather"]`, `reference = ["get_weather","get_population"]` 에 대해 `strict` 는 FAIL, `unordered` 는 PASS 가 나오는 `trajectoryMatch` 를 구현하세요.
5. **환각을 통과시키는 평가** — `get_weather` 만 부르고 인구를 지어내는 에이전트에 대해 최종답 평가(PASS)와 궤적 평가(FAIL)를 둘 다 돌려 결과가 갈리는 것을 보이고, 왜 최종답 평가만으로는 위험한지 설명하세요.
6. **감사 로그 미들웨어** — `createMiddleware` 의 `wrapToolCall` 로 호출된 도구의 이름과 인자를 `auditLog` 배열에 기록하세요. (힌트: `handler(request)` 를 반드시 `return`)
7. **회귀 게이트** — 3개짜리 데이터셋으로 궤적 정확도를 계산하고 기준(0.9) 미달 시 `"REGRESSION"` 을 출력하세요. 정확도는 몇 %이고 어느 예제가 왜 깨지나요?
8. **트레이스 라벨 설계** — "특정 사용자가 어제 겪은 오류를 다시 찾아야 한다"는 상황에서 `runName`/`tags`/`metadata` 를 설계하세요. 그리고 (a) 사용자 이메일을 `metadata` 에 넣어도 될까요? (b) `tags` 와 `metadata` 는 어떻게 나눠 쓰나요?

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 20 — 프로덕션과 종합 프로젝트](../step-20-production/)

이 스텝에서 만든 트레이싱·테스트·평가는 Step 20에서 배포 파이프라인에 그대로 들어갑니다. 관측 없이 배포하는 것은 계기판 없이 비행하는 것과 같습니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. `practice.ts` 를 먼저 실행해 본문의 출력을 눈으로 확인하고, `exercise.ts` 의 8문제를 직접 푼 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

**세 파일 모두 API 키 없이 실행됩니다.** `fakeModel()` 이 실제 모델 자리를 대신하기 때문입니다. 실제 모델을 부르는 곳은 `practice.ts` 의 `[19-2]` 블록 하나뿐이고, `ANTHROPIC_API_KEY` 가 없으면 스스로 건너뜁니다. 실행은 `npx tsx docs/reference/langchain/step-19-observability-eval/practice.ts` 입니다.

### practice.ts

본문 `[19-1] ~ [19-10]` 을 순서대로 담은 파일입니다. 절 번호가 본문 소제목과 1:1로 대응합니다.

- 파일 상단의 `getWeather` / `getPopulation` 두 도구가 **이 스텝 전체의 공용 소재**입니다. 둘 다 하드코딩된 표를 조회하는 순수 함수 — 즉 **결정적**이라서 `[19-5a]` 에서 모델 없이 그대로 단위 테스트됩니다.
- `[19-3]` 의 마지막 `console.log` 가 함정 확인용입니다. `model.calls[0].options` 의 키를 찍어서 거기에 `tags`/`metadata` 가 **없다**는 것을 눈으로 보여줍니다. 트레이스 라벨을 `fakeModel` 로 검증하려다 실패하는 경험을 미리 하게 하는 장치입니다.
- `[19-4a]` 의 `ConsoleCallbackHandler` 는 실행하면 터미널이 수백 줄로 폭발합니다. **정상입니다** — 본문에서 말한 "출력 폭발" 을 직접 겪으라고 일부러 남겨뒀습니다. 그 사이에서 `[tool/start] ... get_weather ... input: {"city":"부산"}` 줄을 찾아보세요.
- `[19-6]` 과 `[19-7]` 은 **같은 `runAgentOn` 함수를 두 번 돌립니다.** `[19-6]` 은 최종답만 보고 3개 전부 만점을 주고, `[19-7]` 은 궤적을 봐서 `ex-3` 을 잡아냅니다. 이 대비가 이 스텝의 핵심이라 일부러 두 번 실행합니다.
- `scripted` 의 `ex-3` 은 **일부러 틀리게** 만든 대본입니다(`get_weather` 만 부르고 인구는 지어냄). 고치지 마세요 — 이게 틀려야 `[19-7]` 과 `[19-8]` 의 교훈이 성립합니다. `[19-8]` 의 궤적 정확도 67%는 여기서 나옵니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 연습문제 8개를 `[문제 N]` 주석 블록으로 담은 빈칸 채우기 파일입니다. 각 블록 아래 `// 여기에 작성` 자리를 채우세요.

- 공용 도구 2개와 `[문제 7]` 의 `dataset` / `scripted` 는 **이미 작성되어 있습니다.** 여러분이 만들 것은 평가 로직과 게이트뿐입니다.
- `[문제 2]` 가 이 파일에서 가장 자주 막히는 곳입니다. 응답을 2개만 큐에 넣고 `FakeModel: no response queued for invocation 2` 를 만나게 될 텐데, 이건 코드가 틀린 게 아니라 대본이 짧은 것입니다. 본문 19-5의 응답 개수 공식(`도구 호출 횟수 + 1`)을 다시 보세요.
- `[문제 5]` 와 `[문제 7]` 은 **답이 "FAIL이 나오는 것"** 인 문제입니다. 평가가 통과하도록 고치려 들지 마세요 — 에이전트가 실제로 잘못했고, 그걸 잡아내는 게 목적입니다.
- `[문제 8]` 은 코드보다 **주석으로 적는 답**이 중요합니다. (a)와 (b)를 스스로 적어본 뒤 solution과 비교하세요.
- 파일을 그대로 실행하면 각 문제의 헤더만 출력되고 그 아래는 비어 있습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 2]` 의 해설이 응답 개수 공식을 단계별로 풀어 놓습니다: 모델#1(도구 호출 결정) → 모델#2(결과 보고 다음 도구 결정) → 모델#3(최종 답). 출력의 `human → ai → tool → ai → tool → ai` 6단계와 짝지어 보세요.
- `[정답 4]` 의 `unordered` 구현에 주석으로 함정이 하나 박혀 있습니다 — **길이 비교(`sortedA.length === sortedR.length`)를 빼먹으면** `["get_weather"]` 가 `["get_weather","get_weather"]` 와 같다고 판정됩니다. 중복 호출을 못 잡게 되는 미묘한 버그입니다.
- `[정답 5]` 가 이 파일의 하이라이트입니다. 출력이 `최종답 평가: PASS` / `궤적 평가: FAIL` 로 갈립니다. 인구 "3,300,000명" 은 `get_population` 이 아니라 모델 기억에서 나온 값이고, 우연히 맞았을 뿐입니다.
- `[정답 6]` 의 `return handler(request);` 줄에 붙은 주석("이 return 을 빼면 도구 결과가 사라집니다")이 본문 19-4c의 함정과 짝입니다. 실제로 `return` 을 지우고 돌려보면 `auditLog` 는 그대로인데 에이전트 동작만 이상해지는 걸 볼 수 있습니다.
- `[정답 7]` 은 `unordered` 모드로 채점하는데도 **67%** 가 나옵니다. `ex-3` 은 순서 문제가 아니라 **도구를 아예 안 부른** 것이라서 모드를 바꿔도 구제되지 않습니다. 실패 목록에 `ex-3: 기대 [get_weather, get_population] / 실제 [get_weather]` 로 정확히 찍힙니다.
- `[정답 8]` 은 이메일 대신 내부 ID(`u_8f3a91`)를 쓰는 것으로 답합니다. 트레이싱이 데이터를 외부로 보낸다는 19-2의 함정이 여기서 실제 설계 결정으로 이어집니다.

```ts file="./solution.ts"
```
