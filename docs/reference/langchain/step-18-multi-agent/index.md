# Step 18 — 멀티 에이전트

> **학습 목표**
> - **에이전트를 나눠야 할 순간과 나누면 안 될 순간**을 구분한다
> - 서브에이전트 / 핸드오프 / 라우터 / 커스텀 워크플로 **4종 아키텍처를 비교해 고른다**
> - 에이전트를 **도구로 감싸** 컨텍스트를 격리한다
> - `Command` 로 **제어권을 이양**하고 대화 히스토리 공유 범위를 정한다
> - 구조화 출력으로 **분류 후 위임**하는 라우터를 만든다
> - **무엇을 공유하고 무엇을 격리할지** 설계한다 — 이게 멀티에이전트의 전부다
>
> **선행 스텝**: [Step 17 — LangGraph 그래프 API](../step-17-langgraph/)
> **예상 소요**: 90분

지금까지 우리는 에이전트 **하나**를 잘 만드는 법을 배웠습니다. 도구를 붙이고([Step 06](../step-06-tools/)), 미들웨어로 감싸고([Step 11](../step-11-middleware-builtin/)), 메모리를 달고([Step 10](../step-10-memory/)), 그래프로 흐름을 제어했습니다([Step 17](../step-17-langgraph/)). 이번 스텝은 그 에이전트를 **여러 개**로 나누는 이야기입니다.

먼저 경고부터 하겠습니다. 이 스텝의 내용 절반은 "이렇게 나눠라" 이고, 나머지 절반은 **"나누지 마라"** 입니다. 공식 문서도 첫 문단에서 못을 박습니다 — *"not every complex task requires this approach—a single agent with the right tools and prompt can often achieve similar results."* 멀티에이전트는 화려해 보이지만 지연이 곱해지고 디버깅이 지옥이 됩니다. 실무에서 가장 흔한 실패는 "멀티에이전트를 못 만들어서" 가 아니라 **"안 나눠도 될 걸 나눠서"** 생깁니다. 그래서 18-1 은 아키텍처 소개가 아니라 "언제 나누나" 로 시작합니다.

---

## 18-1. 언제 에이전트를 나누나

### "도구 하나 더" 로 되는 일이면 나누지 마라

판단 기준은 단순합니다. **도구를 하나 더 붙여서 해결되면 나누지 마세요.**

재고 조회와 환불 정책 안내를 하는 상담원을 생각해 봅시다. "재고 에이전트" 와 "환불 에이전트" 로 나누고 싶어질 수 있습니다. 하지만 이건 도구 2개짜리 단일 에이전트로 충분합니다.

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [checkStock, getRefundPolicy],
  systemPrompt: "너는 쇼핑몰 상담원이다. 도구로 사실을 확인한 뒤 한국어로 간결히 답하라.",
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "노트북 재고 있나요? 그리고 환불 정책도 알려주세요." }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
HUMAN  │ 노트북 재고 있나요? 그리고 환불 정책도 알려주세요.
AI     │ (빈 내용)
       │ → tool check_stock({"product":"노트북"})
       │ → tool get_refund_policy({})
TOOL   │ 노트북: 재고 3개
TOOL   │ 구매 후 7일 이내 미개봉 상품은 전액 환불. ...
AI     │ 노트북은 현재 재고 3개 있습니다. 환불은 구매 후 7일 이내 미개봉이면 전액...
모델 호출 횟수  2
```

모델 호출 **2번**입니다. 도구 두 개를 **한 번에 병렬로** 부르고([Step 07](../step-07-tool-loop/)의 병렬 도구 호출), 결과를 받아 한 번 더 불러 답을 만듭니다. 이걸 두 에이전트로 나누면 최소 2번이 4번이 됩니다. 얻는 건 없고 잃는 것만 있습니다.

### 그럼 언제 나누나 — 세 가지 명분

공식 문서는 멀티에이전트의 동기를 셋으로 정리합니다.

| 명분 | 내용 | 현실에서의 신호 |
|---|---|---|
| **컨텍스트 관리** | 지식을 나눠 담아 컨텍스트 창 초과를 막는다 | 도구가 20개를 넘어 모델이 엉뚱한 걸 고르기 시작함 |
| **분산 개발** | 팀이 각자 자기 전문 영역을 독립적으로 유지보수한다 | 프롬프트 한 줄 고치는 데 3개 팀 승인이 필요함 |
| **병렬화** | 여러 워커가 하위 작업을 동시에 실행한다 | 독립적인 조사 5건을 순차로 돌려서 느림 |

이 중 실무에서 가장 강력한 명분은 의외로 **분산 개발**입니다. 성능 때문이 아니라 **조직 때문에** 나눕니다. 결제팀과 기술지원팀이 각자 자기 에이전트를 배포할 수 있다는 것 — 이게 멀티에이전트를 정당화하는 가장 정직한 이유인 경우가 많습니다.

### 나누면 생기는 비용 — 정직하게

나누기 전에 무엇을 지불하는지 알아야 합니다.

**1. 지연이 곱해진다.** 이게 가장 큽니다. 에이전트를 하나 거칠 때마다 모델 호출이 최소 1번 늘어납니다. 모델 호출 한 번이 1~3초입니다. 3단계 파이프라인이면 앞뒤로 6~9초가 그냥 사라집니다. 사용자가 2초 안에 답을 받아야 하는 제품이라면 멀티에이전트는 시작부터 탈락입니다.

**2. 토큰이 늘어난다.** 서브에이전트마다 자기 시스템 프롬프트와 도구 정의를 들고 갑니다. 같은 질문을 3개 에이전트가 각자 처리하면 시스템 프롬프트도 3번 청구됩니다.

**3. 디버깅이 어려워진다.** 단일 에이전트는 messages 배열 하나만 보면 무슨 일이 있었는지 다 보입니다. 멀티에이전트는 각 에이전트의 내부 대화가 **서로 안 보입니다** — 그게 격리의 이점이자 디버깅의 저주입니다. "왜 이렇게 답했지?" 를 추적하려면 [Step 19](../step-19-observability-eval/)의 관측 도구가 사실상 필수가 됩니다.

> ⚠️ **함정 — 멀티에이전트가 단일 에이전트보다 나쁜 경우가 흔하다**: "복잡한 문제니까 에이전트를 나누자" 는 거의 항상 틀린 추론입니다. 문제가 복잡하다고 **구조**가 복잡해야 하는 게 아닙니다. 나눈 순간 (1) 부모가 서브에이전트에게 맥락을 제대로 못 넘기고, (2) 서브에이전트가 반쯤 틀린 답을 확신에 차서 돌려주고, (3) 부모가 그걸 검증 없이 믿는 3중 실패가 시작됩니다. **먼저 단일 에이전트로 만들어서 실제로 실패하는 걸 확인한 뒤에 나누세요.** 실패 지점을 모르면 어디서 나눠야 할지도 모릅니다.

> 💡 **실무 팁 — 나누기 전에 해볼 것**: 도구가 너무 많아 모델이 헷갈린다면, 나누기 전에 (1) 도구 description 을 다듬고, (2) `llmToolSelectorMiddleware` 로 관련 도구만 추려서 모델에 보여주고, (3) 도구 개수를 통합해서 줄여 보세요. 이 셋으로 해결되는 경우가 생각보다 많습니다. `llmToolSelectorMiddleware` 는 `langchain` 에서 바로 import 할 수 있습니다 — 도구 후보를 모델에게 미리 걸러 주는 미들웨어라, 아키텍처를 안 바꾸고 "도구 과다" 문제만 떼어낼 수 있습니다.

---

## 18-2. 아키텍처 4종 비교

나누기로 결정했다면, 이제 **어떻게** 나눌지 고릅니다. 네 가지가 있습니다.

| 아키텍처 | 한 줄 정의 | 제어권 | 상태 유지 | 언제 쓰나 |
|---|---|---|---|---|
| **서브에이전트** | 에이전트를 **도구로** 감싸 부모가 호출 | 부모가 계속 쥠 | 없음(매번 새로) | 도메인이 여럿이고 부모가 총괄해야 할 때 |
| **핸드오프** | `Command` 로 **제어권 자체를 이양** | 넘어감 | 있음(다음 턴까지) | 사용자와 직접 길게 대화할 때 |
| **라우터** | 분류 → 전문 에이전트에 위임 | 분류 후 위임 | 없음(스테이트리스) | 문의 종류가 명확히 갈릴 때 |
| **커스텀 워크플로** | LangGraph 로 흐름을 직접 짬 | 그래프가 쥠 | 그래프 상태 | 결정적 로직과 에이전트를 섞을 때 |

### 공식 문서의 벤치마크

공식 문서는 같은 작업을 네 아키텍처로 구현해 호출 수와 토큰을 비교합니다. (아래 숫자는 **공식 문서에 실린 측정치**이며, 여러분의 워크로드에서는 당연히 달라집니다.)

| 시나리오 | 서브에이전트 | 핸드오프 | 라우터 | Skills |
|---|---|---|---|---|
| **단발 요청** ("커피 사줘") | 4 호출 | 3 호출 | 3 호출 | 3 호출 |
| **반복 요청** (같은 걸 또) | 4 호출 | **2 호출** | 3 호출 | **2 호출** |
| **다중 도메인** (병렬 비교) | **5 호출 / 9K 토큰** | 7+ 호출 / 14K+ 토큰 | **5 호출 / 9K 토큰** | 3 호출 / 15K 토큰 |

읽는 법이 중요합니다.

- **단발 요청**에서 서브에이전트가 1번 더 부르는 건 부모가 "서브를 부를지" 를 판단하는 데 한 번 쓰기 때문입니다. 이게 서브에이전트의 고정 세금입니다.
- **반복 요청**에서 핸드오프와 Skills 가 2번으로 떨어지는 건 **상태가 남아서** 입니다. 이미 결제 상담원으로 전환됐으면 두 번째 턴부터는 분류가 필요 없습니다. 서브에이전트는 스테이트리스라 매번 4번을 냅니다.
- **다중 도메인**에서 핸드오프가 7+ 호출로 폭발하는 건 **순차 실행**이기 때문입니다. 제어권을 넘긴다는 건 한 번에 하나만 돈다는 뜻입니다. 반면 Skills 는 호출 수는 적은데(3회) 토큰이 15K 로 가장 많습니다 — 스킬을 여러 개 불러오면 그게 전부 **한 컨텍스트에 쌓이기** 때문입니다.

### 고르는 법

```
사용자와 직접, 길게, 여러 턴 대화하는가?
├─ 예 → 핸드오프 또는 Skills (상태가 남아 반복 비용이 싸다)
│        └─ 도메인 전환이 잦고 인격이 확 바뀌어야 하나? → 핸드오프
│        └─ 그냥 지식만 더 있으면 되나?                → Skills
└─ 아니오 → 요청 하나를 처리하고 끝나는가?
           ├─ 도메인이 명확히 갈리나?   → 라우터
           ├─ 부모가 총괄·종합해야 하나? → 서브에이전트
           └─ 결정적 단계가 섞여 있나?   → 커스텀 워크플로
```

> 💡 **실무 팁 — 섞어 쓰는 게 정상이다**: 이 넷은 배타적이지 않습니다. 공식 문서도 *"Patterns can be combined—subagents can invoke routers, skills can supplement subagents, and custom workflows can embed any pattern as nodes within the graph"* 라고 명시합니다. 18-8 의 실전 예제도 **라우터 + 서브에이전트** 조합입니다. "우리는 라우터 아키텍처입니다" 같은 순수주의는 필요 없습니다.

### 커스텀 워크플로는 언제

넷 중 커스텀 워크플로만 이 스텝에서 코드로 깊이 다루지 않습니다. [Step 17](../step-17-langgraph/)에서 이미 `StateGraph` 를 배웠고, 커스텀 워크플로란 사실상 "그래프 노드 안에서 에이전트를 부르는 것" 이기 때문입니다.

```ts
import { StateGraph, START, END, StateSchema, MessagesValue } from "@langchain/langgraph";
import * as z from "zod";

const State = new StateSchema({
  messages: MessagesValue,
  query: z.string(),
  answer: z.string().default(""),
});

const agentNode: typeof State.Node = async (state) => {
  const result = await agent.invoke({ messages: [{ role: "user", content: state.query }] });
  return { answer: result.messages.at(-1)?.text ?? "" };
};

const workflow = new StateGraph(State)
  .addNode("agent", agentNode)
  .addEdge(START, "agent")
  .addEdge("agent", END)
  .compile();
```

공식 문서의 기준은 명확합니다 — *"Use custom workflows when standard patterns don't fit your requirements, you need to mix deterministic logic with agentic behavior, or your use case requires complex routing or multi-stage processing."* **결정적 로직과 에이전트를 섞어야 할 때** 쓰라는 뜻입니다. 예를 들어 "무조건 DB 검증 → 에이전트 판단 → 무조건 감사 로그" 처럼 앞뒤가 고정된 파이프라인이면 그 앞뒤는 에이전트에게 맡길 이유가 없습니다. 모델에게 맡기지 않아도 되는 건 맡기지 마세요.

---

## 18-3. 서브에이전트 — 에이전트를 도구로 감싸기

### 특별한 클래스는 없다

가장 먼저 알아야 할 것: **서브에이전트라는 특별한 타입은 없습니다.** 그냥 `createAgent` 로 만든 평범한 에이전트를, `tool()` 로 감싸서 다른 에이전트에게 도구로 준 것뿐입니다.

이 패턴에서 부모를 **supervisor(총괄)** 라고 부릅니다. 공식 문서는 supervisor 와 router 를 명확히 구분합니다 — *"The supervisor is a full agent that maintains conversation context and dynamically decides which subagents to call across multiple turns."* 라우터는 한 번 분류하고 넘기면 끝이지만, supervisor 는 **대화 맥락을 유지하면서 여러 턴에 걸쳐 계속 판단**합니다.

```ts
import { createAgent, tool } from "langchain";
import * as z from "zod";

// 1) 서브에이전트 — 그냥 에이전트입니다.
const researchSubagent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [checkStock],
  systemPrompt: "너는 재고 조사 전문가다. check_stock 으로 확인한 사실만 보고하라. 추측하지 마라.",
});

// 2) 도구로 감싸기 — 이게 전부입니다.
const researchTool = tool(
  async ({ query }) => {
    const result = await researchSubagent.invoke({
      messages: [{ role: "user", content: query }],
    });

    // ★ 핵심: messages 전체가 아니라 마지막 메시지의 텍스트만 반환합니다.
    const last = result.messages.at(-1);
    return last?.text ?? "(빈 응답)";
  },
  {
    name: "research_stock",
    description:
      "재고 조사 전문가에게 조사를 위임합니다. 상품명과 알고 싶은 것을 자연어로 완결되게 적으세요. " +
      "이 도구는 대화 맥락을 볼 수 없으므로 '그거', '아까 그 상품' 같은 지시어를 쓰지 마세요.",
    schema: z.object({
      query: z.string().describe("조사 요청. 예: '노트북과 키보드의 재고 수량을 확인해줘'"),
    }),
  },
);

// 3) 부모(supervisor)
const supervisor = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [researchTool, getRefundPolicy],
  systemPrompt: "너는 총괄 상담원이다. 재고 관련 조사는 research_stock 에 위임하라.",
});
```

### 컨텍스트 격리가 핵심 이점

위 코드에서 가장 중요한 줄은 `return last?.text ?? "(빈 응답)"` 입니다. 왜 이게 핵심인지 실제로 돌려서 확인해 봅시다.

서브에이전트가 내부에서 `check_stock` 을 부르며 4개의 메시지를 만들었을 때, **부모의 messages 는 이렇게 생겼습니다.**

```
부모 messages: [ 'human', 'ai', 'tool', 'ai' ]
```

`human`(사용자 질문) → `ai`(research_stock 호출) → `tool`(서브에이전트의 **최종 답변 한 줄**) → `ai`(부모의 종합). 서브에이전트가 내부에서 `check_stock` 을 부른 흔적은 **부모의 messages 어디에도 없습니다.** 부모는 그냥 도구 하나를 부른 것으로만 압니다.

이게 **컨텍스트 격리(context isolation)** 입니다. 서브에이전트가 도구를 10번 부르며 헤매고 실패하고 재시도했어도, 부모의 컨텍스트 창에는 **결론 한 줄만** 들어옵니다. 부모의 컨텍스트가 오염되지 않고, 토큰도 아낍니다.

만약 `return JSON.stringify(result.messages)` 처럼 통째로 넘기면 격리가 완전히 깨집니다. 서브에이전트의 시행착오가 전부 부모에게 쏟아져 들어와서, 나누기 전보다 컨텍스트가 더 지저분해집니다. **나눈 이유가 사라지는 것입니다.**

> ⚠️ **함정 — 서브에이전트는 부모의 대화 컨텍스트를 못 본다**: 이건 버그가 아니라 **설계**입니다. 그런데 이걸 모르고 쓰면 조용히 망가집니다. 사용자가 "노트북 보고 있어요" → "그거 재고 있어?" 라고 하면, 부모 모델은 `query: "그거 재고 있어?"` 를 그대로 넘깁니다. 서브에이전트는 "그거" 가 뭔지 알 방법이 **전혀** 없습니다. 부모 대화를 못 보니까요. 그럼 서브에이전트는 뭘 할까요? **에러를 내지 않습니다.** "어떤 상품을 말씀하시는지 알려주세요" 라고 되묻거나, 최악의 경우 아무 상품이나 찍어서 조회합니다. 부모는 그 답을 그대로 사용자에게 전달합니다. 해결책은 18-7 에서 다룹니다 — 그리고 그 해결책은 "설명을 잘 쓰자" 가 아닙니다.

> 💡 **실무 팁 — 도구 설명이 곧 라우팅 로직이다**: 부모 모델이 서브에이전트에 대해 아는 것은 `description` 문자열 **하나뿐**입니다. 서브에이전트의 시스템 프롬프트도, 가진 도구도 부모는 모릅니다. 그러니 description 에 "무엇을 할 수 있는지" 뿐 아니라 **"언제 불러야 하는지"** 와 **"무엇을 넘겨줘야 하는지"** 를 다 적어야 합니다. 위 예제에서 `"이 도구는 대화 맥락을 볼 수 없으므로..."` 라고 적은 게 그 때문입니다. 서브에이전트를 추가했는데 모델이 안 부른다면, 십중팔구 프롬프트가 아니라 **description 이 부실한 것**입니다.

### 두 가지 감싸는 방식

공식 문서는 두 패턴을 제시합니다.

| 방식 | 형태 | 장점 | 단점 |
|---|---|---|---|
| **에이전트당 도구 하나** | `research_stock`, `check_billing`, ... 각각 tool | 세밀한 제어, 스키마를 도메인별로 다르게 | 에이전트 수만큼 도구가 늘어남 |
| **단일 디스패치 도구** | `task({ agentName, query })` 하나로 전부 | 확장 쉬움, 팀이 에이전트만 등록하면 됨 | 스키마가 공통이라 느슨함 |

에이전트가 3~5개면 첫 번째가 낫습니다. 도메인마다 필요한 입력이 다르니 스키마를 다르게 가져갈 수 있습니다. 에이전트가 수십 개로 늘거나 팀이 각자 등록하는 구조라면 두 번째가 낫습니다 — [DeepAgent 코스](../../deepagent/step-06-subagents/)의 `task` 도구가 정확히 이 방식입니다.

### 동기 vs 비동기

- **동기**: 부모가 서브에이전트 완료를 기다립니다. 단순하지만 그동안 대화가 멈춥니다.
- **비동기**: 서브에이전트를 백그라운드로 돌리고 부모는 계속 진행합니다. 반응성은 좋지만 복잡해집니다.

기본은 동기입니다. 비동기는 "조사에 30초 걸리는데 그동안 사용자와 대화는 계속돼야 한다" 같은 명확한 요구가 있을 때만 가세요.

---

## 18-4. 핸드오프 — 제어권 이양

### 상태가 인격을 바꾼다

서브에이전트는 부모가 제어권을 계속 쥡니다. **핸드오프는 제어권 자체를 넘깁니다.**

핵심 아이디어는 의외로 단순합니다. 공식 문서의 표현을 빌리면 — *"tools update a state variable (e.g., `current_step` or `active_agent`) that persists across turns, and the system reads this variable to adjust behavior."* **상태 변수 하나가 "지금 누가 대화를 맡고 있는가" 를 들고 있고, 미들웨어가 그걸 읽어 인격을 바꿉니다.**

공식 문서는 두 가지 구현을 제시하고, **단일 에이전트 + 미들웨어를 권장**합니다.

| 구현 | 방식 | 권장도 |
|---|---|---|
| **단일 에이전트 + 미들웨어** | 상태에 따라 시스템 프롬프트·도구를 갈아끼움 | ✅ 대부분의 경우 |
| **다중 서브그래프** | 에이전트가 각각 그래프 노드, `Command.PARENT` 로 이동 | 노드 자체가 복잡한 그래프일 때만 |

문서의 기준은 이렇습니다 — 다중 서브그래프는 *"when you need bespoke agent implementations (e.g., a node that's itself a complex graph with reflection or retrieval steps)"* 일 때만. 여기서는 권장안인 단일 에이전트 방식을 봅니다.

### 미들웨어가 인격을 갈아끼운다

```ts
import { createMiddleware } from "langchain";
import * as z from "zod";

const handoffMiddleware = createMiddleware({
  name: "HandoffMiddleware",
  stateSchema: z.object({
    // .default() 를 주지 않으면 첫 턴에 undefined 가 되어 아래 분기가 터집니다.
    activeAgent: z.string().default("general"),
  }),
  wrapModelCall: async (request, handler) => {
    const active = request.state.activeAgent;

    const prompts: Record<string, string> = {
      general: "너는 일반 상담원이다. 결제 문제로 보이면 transfer_to_billing 을 불러 넘겨라.",
      billing: "너는 결제 전문 상담원이다. 환불/청구 문제를 직접 해결하라. 넘기지 마라.",
    };

    // 상태에 따라 시스템 프롬프트를 갈아끼웁니다.
    // 에이전트는 하나지만 "인격" 이 바뀝니다.
    return handler({
      ...request,
      systemPrompt: prompts[active] ?? prompts["general"] ?? "",
    });
  },
});
```

`wrapModelCall` 은 [Step 12](../step-12-middleware-custom/)에서 배운 훅입니다. `request.state` 로 현재 상태를 읽고, `handler(수정된 request)` 로 모델을 부릅니다. 여기서는 `systemPrompt` 만 바꿨지만 `tools` 도 같이 바꾸면 "결제 상담원은 재고 도구를 아예 못 본다" 를 만들 수 있습니다.

### Command 로 제어권 넘기기

전환은 **도구가 `Command` 를 반환**하는 것으로 일어납니다.

```ts
import { tool, ToolMessage } from "langchain";
import { Command } from "@langchain/langgraph";
import type { ToolRuntime } from "@langchain/core/tools";

const transferToBilling = tool(
  (_input, runtime: ToolRuntime) =>
    new Command({
      update: {
        activeAgent: "billing",
        messages: [
          new ToolMessage({
            content: "결제 전문 상담원으로 전환했습니다.",
            tool_call_id: runtime.toolCallId,   // ← 이게 빠지면 대화가 깨집니다
          }),
        ],
      },
    }),
  {
    name: "transfer_to_billing",
    description: "결제/환불/청구 문제일 때 결제 전문 상담원에게 대화를 넘깁니다.",
    schema: z.object({}),
  },
);
```

도구가 문자열을 반환하면 LangChain 이 알아서 `ToolMessage` 로 포장해 줍니다. 하지만 **`Command` 를 반환하면 그 자동 포장이 일어나지 않습니다.** `ToolNode` 는 `Command.update` 를 그래프 상태에 그대로 적용할 뿐입니다. 그래서 `ToolMessage` 를 **직접** 넣어야 합니다.

`runtime.toolCallId` 는 지금 처리 중인 tool_call 의 id 입니다. 도구 함수의 두 번째 인자 `runtime: ToolRuntime` 으로 받습니다.

### 상태는 체크포인터가 있어야 남는다

```ts
const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [transferToBilling, getRefundPolicy],
  systemPrompt: "너는 상담원이다.",           // 미들웨어가 덮어씁니다
  checkpointer: new MemorySaver(),          // ← 없으면 핸드오프가 무의미
  middleware: [handoffMiddleware],
});

const config = { configurable: { thread_id: "handoff-demo" } };

// 1턴
const t1 = await agent.invoke(
  { messages: [{ role: "user", content: "환불받고 싶은데요." }] },
  config,
);
// 2턴 — 같은 thread_id
const t2 = await agent.invoke(
  { messages: [{ role: "user", content: "20일 전에 샀는데 얼마나 돌려받나요?" }] },
  config,
);
```

**실행하면 이런 일이 벌어집니다** (미들웨어에 로그를 심어 확인한 것)
```
=== 턴 1 ===
  [middleware] activeAgent = general      ← 처음엔 general
  [middleware] activeAgent = billing      ← 도구 실행 후 billing 으로 바뀜
  activeAgent after t1 = billing
  messages: [ 'human', 'ai', 'tool(tool_call_id=call_1)', 'ai' ]
=== 턴 2 ===
  [middleware] activeAgent = billing      ← 유지됨
  activeAgent after t2 = billing
```

1턴 안에서 미들웨어가 두 번 불립니다 — 전환 **전**(general)과 **후**(billing). 그리고 2턴에서는 처음부터 billing 입니다. 상태가 체크포인터에 저장되어 살아남았기 때문입니다. **이게 18-2 비교표에서 핸드오프의 "반복 요청 2호출" 이 나오는 이유입니다.** 두 번째 턴부터는 분류·전환 비용이 0 입니다.

`checkpointer` 를 빼면 `activeAgent` 가 매 턴 `"general"` 로 초기화됩니다. 핸드오프를 해놓고 다음 턴에 도로 일반 상담원이 되는, **에러 없이 조용히 무의미해지는** 상태가 됩니다. [Step 10](../step-10-memory/)에서 배운 그 함정이 여기서 다시 나옵니다.

> ⚠️ **함정 — Command 를 반환하면서 ToolMessage 를 빠뜨리면 로컬에선 조용하다**: 이건 직접 돌려서 확인한 것입니다. `ToolMessage` 없이 `Command` 만 반환하면 messages 가 이렇게 됩니다.
> ```
> 결과 messages: [ 'human', 'ai', 'ai' ]
> ```
> `ai` 메시지에는 `tool_call` 이 들어 있는데 그에 대응하는 `tool` 메시지가 **없습니다.** 그런데 LangChain 은 여기서 **에러를 내지 않습니다.** 조용히 지나갑니다. 터지는 건 다음 모델 호출 때 **제공자 쪽**입니다.
> - Anthropic: `tool_use ids were found without tool_result blocks`
> - OpenAI: `assistant message with 'tool_calls' must be followed by tool messages`
>
> 그래서 이 버그는 **가짜 모델로 하는 로컬 테스트에서는 절대 안 잡히고, 실제 API 를 부르는 순간에만** 터집니다. 반드시 `tool_call_id: runtime.toolCallId` 로 `ToolMessage` 를 넣으세요. 공식 문서도 못을 박습니다 — *"The `ToolMessage` with matching `tool_call_id` completes this request-response cycle—without it, the conversation history becomes malformed."*

> ⚠️ **함정 — 핸드오프 루프 (A→B→A→B)**: 일반 상담원이 "이건 결제 문제네" 하고 결제로 넘기고, 결제 상담원이 "이건 기술 문제네" 하고 넘기고, 기술이 다시 일반으로 넘기는 무한 루프. 각 에이전트의 프롬프트가 "네 전문 분야가 아니면 넘겨라" 라고만 되어 있으면 **반드시** 일어납니다. `recursionLimit` 이 있으니 괜찮다고 생각하기 쉬운데, 그건 해결책이 아닙니다 — 한계에 도달하면 사용자는 답변 대신 `GraphRecursionError` 를 받고, 그때까지 모델은 계속 불려서 토큰은 다 씁니다. 제대로 된 방어는 **전환 횟수를 상태로 세고, 한도를 넘으면 핸드오프 도구를 모델에게서 아예 치워버리는 것**입니다(연습문제 7). 도구가 없으면 모델은 직접 답할 수밖에 없습니다. 에러 대신 답변이 나갑니다.

---

## 18-5. 라우터 — 분류 후 위임

### 구조화 출력으로 분류한다

라우터는 가장 이해하기 쉬운 패턴입니다. 공식 문서의 정의 — *"A routing step classifies input and directs it to one or more specialized agents. Results are synthesized into a combined response."*

분류에는 [Step 05](../step-05-structured-output/)에서 배운 `responseFormat` 을 씁니다.

```ts
const RouteSchema = z.object({
  // ★ z.enum 이 핵심입니다. z.string() 이면 안 됩니다.
  domain: z.enum(["billing", "technical", "general"]).describe("문의가 속한 도메인"),
  reason: z.string().describe("그렇게 분류한 이유 한 문장"),
});

// 분류 전용 에이전트 — 도구가 없고 responseFormat 만 있습니다.
const classifier = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  systemPrompt: "너는 고객 문의 분류기다. 문의를 읽고 도메인 하나를 고르라.",
  responseFormat: RouteSchema,
});

// 도메인별 전문 에이전트
const specialists: Record<string, ReturnType<typeof createAgent>> = {
  billing: createAgent({ model: MODEL, tools: [getRefundPolicy], systemPrompt: "너는 결제 전문 상담원이다." }),
  technical: createAgent({ model: MODEL, tools: [], systemPrompt: "너는 기술지원 상담원이다." }),
  general: createAgent({ model: MODEL, tools: [checkStock], systemPrompt: "너는 일반 상담원이다." }),
};
```

호출은 2단계입니다.

```ts
const question = "결제는 됐다는데 주문 내역에 안 보여요.";

// 1단계: 분류
const routed = await classifier.invoke({ messages: [{ role: "user", content: question }] });
const route = routed.structuredResponse as z.infer<typeof RouteSchema>;

// 2단계: 위임
const specialist = specialists[route.domain];
if (specialist === undefined) throw new Error(`알 수 없는 도메인: ${route.domain}`);

const answer = await specialist.invoke({ messages: [{ role: "user", content: question }] });
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
분류    billing
이유    결제는 완료됐으나 주문 반영이 안 된 결제 처리 문제입니다
AI     │ 결제 후 주문 내역에 반영되지 않는 경우는 보통...
모델 호출 횟수  3
```

`responseFormat` 을 주면 결과의 `structuredResponse` 에 파싱된 객체가 들어옵니다.

> ⚠️ **함정 — 분류 스키마에 z.string() 을 쓰면 조용히 undefined 가 된다**: `domain: z.string()` 으로 두면 모델은 `"billing"` 대신 `"결제팀"`, `"billing 관련"`, `"BILLING"` 같은 값을 자유롭게 뱉습니다. 프롬프트에 "billing, technical, general 중 하나만" 이라고 아무리 적어도 가끔 어깁니다. 그러면 `specialists["결제팀"]` 이 `undefined` 가 되고, 방어 코드가 없으면 `Cannot read properties of undefined` 로 런타임 크래시입니다. **`z.enum` 을 쓰세요.** enum 은 제공자의 구조화 출력 스키마에 "이 셋 중 하나" 로 박히므로 모델이 다른 값을 만들 **수** 없습니다. 프롬프트로 부탁하지 말고 스키마로 강제하세요.

### 스테이트리스가 라우터의 약점

라우터는 상태를 안 남깁니다. 그래서 사용자가 결제 문의를 5번 연달아 해도 **매번 분류 비용을 냅니다**(18-2 표의 "반복 요청 3호출").

공식 문서의 처방은 이렇습니다 — *"For multi-turn conversations, wrapping the router as a tool within a conversational agent is recommended. This keeps the router stateless while the agent manages context and memory."* 라우터 자체는 스테이트리스로 두고, **대화 에이전트가 라우터를 도구로 감싸서** 맥락과 메모리를 관리하라는 것입니다. 즉 라우터 + 서브에이전트 조합입니다.

### 병렬 라우팅

문의 하나가 여러 도메인에 걸칠 수도 있습니다("환불도 궁금하고 앱도 안 돼요"). 이때는 `Send` 로 여러 에이전트에 **동시에** 뿌립니다.

```ts
import { Send } from "@langchain/langgraph";

function routeQuery(state: typeof State.State) {
  const classifications = classifyQuery(state.query);   // 여러 개 반환

  // 선택된 에이전트들에 병렬로 팬아웃
  return classifications.map((c) => new Send(c.agent, { query: c.query }));
}
```

`Send(노드이름, 그 노드에 넘길 상태)` 입니다. 이게 18-2 표에서 라우터가 다중 도메인에서 5호출 / 9K 토큰으로 핸드오프(7+호출 / 14K+)를 이기는 이유입니다 — **병렬로 돌기 때문**입니다. `Send` 는 [Step 17](../step-17-langgraph/)의 맵-리듀스 패턴과 같은 도구입니다.

> 💡 **실무 팁 — 분류기에는 값싼 모델을 써라**: 분류는 추론이 거의 필요 없는 일입니다. "이 문의가 결제냐 기술이냐" 를 고르는 데 최상급 모델을 쓸 이유가 없습니다. 분류기만 `"anthropic:claude-haiku-4-5"` 나 `"openai:gpt-5.5-mini"` 급으로 내리고 전문 에이전트만 상급 모델을 쓰면, 정확도는 거의 그대로인데 비용과 **지연**이 눈에 띄게 줍니다. 라우터에서 분류 단계는 반드시 거치는 고정 비용이라 여기서 아낀 200ms 가 모든 요청에 그대로 반영됩니다. `createAgent({ model })` 은 `"provider:model"` 문자열만 바꾸면 되니 실험 비용도 거의 0 입니다.

---

## 18-6. Skills — 필요할 때 지식만 꺼내 쓰기

### 스킬은 에이전트가 아니다

Skills 는 앞의 셋과 결이 다릅니다. **에이전트를 나누지 않습니다.** 에이전트는 계속 하나이고, 필요할 때 **전문 프롬프트와 지식만** 꺼내 옵니다.

공식 문서의 정의 — *"Specialized capabilities are packaged as invocable 'skills' that augment an agent's behavior."* 특징은 넷입니다.

| 특징 | 의미 |
|---|---|
| **프롬프트 주도** | 코드가 아니라 주로 프롬프트로 정의된다 |
| **점진적 공개(progressive disclosure)** | 맥락에 따라 필요한 것만 노출된다 |
| **가벼움** | 완전한 서브에이전트보다 단순하다 |
| **독립 유지보수** | 팀별로 따로 개발할 수 있다 |

즉 스킬은 **그냥 문자열**입니다. 클래스도, 에이전트도 아닙니다.

```ts
// 스킬 = 프롬프트 + 지식 덩어리. 실무에서는 파일이나 DB 에서 읽어옵니다.
const SKILLS: Record<string, string> = {
  refund_expert: `[환불 전문가 스킬]
구매 후 7일 이내 미개봉 상품은 전액 환불. 7일 초과 30일 이내는 50% 환불. 30일 초과는 환불 불가.
답변 규칙:
- 반드시 구매일을 먼저 물어라. 모르면 환불액을 계산하지 마라.
- 환불액은 "정가 × 비율" 로 계산하고 계산식을 보여줘라.`,

  shipping_expert: `[배송 전문가 스킬]
배송 정책: 오후 2시 이전 결제분은 당일 출고. 도서산간 +2일.
답변 규칙:
- 송장번호가 있으면 그것부터 확인하라고 안내하라.`,
};

const loadSkill = tool(
  ({ skillName }) => {
    const skill = SKILLS[skillName];
    return skill ?? `그런 스킬은 없습니다. 사용 가능: ${Object.keys(SKILLS).join(", ")}`;
  },
  {
    name: "load_skill",
    // ★ 스킬 목록을 description 에 적는 게 핵심입니다. 이게 모델이 보는 "진열장" 입니다.
    description: `전문 스킬을 불러옵니다.

사용 가능한 스킬:
- refund_expert: 환불 정책과 환불액 계산 규칙
- shipping_expert: 배송 정책과 송장 안내 규칙

스킬의 프롬프트와 지식을 반환합니다.`,
    schema: z.object({ skillName: z.string().describe("불러올 스킬 이름") }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [loadSkill],
  systemPrompt:
    "너는 상담원이다. 전문 지식이 필요하면 load_skill 로 먼저 불러온 뒤, " +
    "불러온 스킬의 답변 규칙을 그대로 따르라.",
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
HUMAN  │ 환불 얼마나 받을 수 있어요?
AI     │ (빈 내용)
       │ → tool load_skill({"skillName":"refund_expert"})
TOOL   │ [환불 전문가 스킬] 구매 후 7일 이내 미개봉 상품은 전액 환불...
AI     │ 환불액을 계산해 드리려면 구매일을 먼저 알아야 합니다. 언제 구매하셨나요?
```

주목할 점: 에이전트가 **"구매일을 먼저 물어라"** 라는 규칙을 따랐습니다. 이 규칙은 시스템 프롬프트에 없었습니다 — 스킬을 불러온 순간 컨텍스트에 들어와 행동을 바꾼 것입니다. 이게 "prompt-driven" 의 의미입니다.

### 스킬의 비용은 누적이다

Skills 는 제어권을 넘기지 않습니다. 그래서 단순하고, 반복 요청에 싸고(2호출), 상태도 자연스럽게 유지됩니다.

대신 **불러온 스킬 텍스트가 컨텍스트에 계속 쌓입니다.** 18-2 표에서 Skills 가 다중 도메인에서 호출 수는 가장 적은데(3회) 토큰은 가장 많은(15K) 이유가 이것입니다. 스킬 3개를 불러오면 3개가 전부 한 컨텍스트에 있습니다. 서브에이전트였다면 각자 자기 컨텍스트에서 처리하고 결론만 돌려줬을 겁니다.

**그래서 갈립니다.** 도메인 하나를 깊게 파는 대화라면 Skills 가 낫고, 여러 도메인을 넓게 훑어야 하면 서브에이전트나 라우터가 낫습니다.

> 💡 **실무 팁 — 도구의 실패 메시지도 프롬프트다**: 위 `loadSkill` 에서 없는 스킬을 요청하면 빈 문자열이 아니라 `"그런 스킬은 없습니다. 사용 가능: refund_expert, shipping_expert"` 를 돌려줍니다. 이게 중요합니다. 빈 문자열을 주면 모델은 "아 지식이 없구나" 하고 **그냥 자기가 아는 대로 지어냅니다**(환각). 반면 사용 가능 목록을 주면 모델이 **다시 고를 기회**를 얻습니다. 도구가 실패할 때 무엇을 돌려주는지가 곧 모델의 다음 행동을 결정합니다 — 실패 경로의 문자열도 프롬프트를 쓰는 마음으로 쓰세요.

> 💡 **참고 — DeepAgents 의 Skills**: DeepAgents 에는 `createSkillsMiddleware` 와 `skills` 파라미터로 이 패턴이 프레임워크 차원에서 들어가 있습니다. 여기서 손으로 만든 `load_skill` 이 거기서는 기본 제공됩니다. [DeepAgent Step 10 — 장기 메모리와 스킬](../../deepagent/step-10-memory-skills/)에서 다룹니다.

---

## 18-7. 상태 공유 설계 — 이게 멀티에이전트의 전부다

여기가 이 스텝의 심장입니다.

지금까지 네 아키텍처를 봤지만, 사실 아키텍처 선택은 부차적입니다. 공식 문서가 직접 말합니다 — *"At the center of multi-agent design is **context engineering**—deciding what information each agent sees. The quality of your system depends on ensuring each agent has access to the right data for its task."*

**멀티에이전트 설계란 곧 "누가 무엇을 보는가" 를 정하는 일입니다.** 나머지는 그걸 구현하는 방법의 차이일 뿐입니다.

### 각 아키텍처가 공유하는 것

| 아키텍처 | 하위 에이전트가 보는 것 | 부모/이후가 받는 것 |
|---|---|---|
| **서브에이전트** | 부모가 `query` 에 **써준 것만** | 서브의 **마지막 메시지만** |
| **핸드오프** | **대화 히스토리 전체**(같은 messages) | 상태 + 히스토리 그대로 |
| **라우터** | 라우터가 넘긴 `query` 만 | 전문가의 답변 |
| **Skills** | 전부(같은 에이전트니까) | 전부(컨텍스트에 누적) |

핸드오프와 Skills 는 **공유** 쪽, 서브에이전트와 라우터는 **격리** 쪽입니다. 이게 앞서 본 토큰·호출 수 차이의 근본 원인입니다. 공유하면 맥락이 풍부한 대신 컨텍스트가 무거워지고, 격리하면 가벼운 대신 맥락이 끊깁니다.

**정답은 없습니다.** 다만 결정을 **의식적으로** 해야 합니다. 대부분의 멀티에이전트 버그는 "격리되는 줄 몰랐다" 또는 "공유되는 줄 몰랐다" 에서 나옵니다.

### 격리를 골랐다면, 넘길 것을 스키마로 강제하라

18-3 의 함정으로 돌아옵시다. 서브에이전트는 부모 대화를 못 봅니다. 그럼 어떻게 해야 할까요?

**나쁜 해법: description 에 부탁하기.**

```ts
const badResearchTool = tool(
  async ({ query }) => { /* ... */ },
  {
    name: "research_bad",
    description: "재고를 조사합니다.",       // 맥락을 넘기라는 지시가 없음
    schema: z.object({ query: z.string() }),  // 자유 문자열 한 칸
  },
);
```

자유 문자열 한 칸이면 모델은 사용자가 한 말을 거의 그대로 복사해 넣습니다. `query: "그거 재고 있어?"`. description 에 "맥락을 넘기세요" 라고 적어도 **꽤 자주 무시합니다.** 프롬프트는 부탁이지 강제가 아닙니다.

**좋은 해법: 스키마로 강제하기.**

```ts
const goodResearchTool = tool(
  async ({ productNames, question }) => {
    // 부모가 넘긴 정보만으로 자기완결적인 지시문을 조립합니다.
    const prompt = `다음 상품들의 재고를 확인해줘: ${productNames.join(", ")}
알고 싶은 것: ${question}`;

    const r = await researchSubagent.invoke({ messages: [{ role: "user", content: prompt }] });
    return r.messages.at(-1)?.text ?? "";
  },
  {
    name: "research_good",
    description:
      "재고 조사 전문가에게 위임합니다. 이 전문가는 지금까지의 대화를 전혀 볼 수 없으므로, " +
      "상품명을 반드시 명시적으로 나열하세요.",
    schema: z.object({
      // ★ 배열로 강제하면 모델이 지시어를 넣을 자리가 구조적으로 없어집니다.
      productNames: z.array(z.string()).describe("조사할 상품명들. 지시어 금지, 실제 이름만."),
      question: z.string().describe("이 상품들에 대해 알고 싶은 것"),
    }),
  },
);
```

`productNames: string[]` 로 쪼개는 순간 모델이 `"그거"` 를 넣을 **자리가 없어집니다.** 대화에서 실제 이름("노트북")을 찾아 채우는 수밖에 없습니다.

**이게 이 스텝에서 가장 중요한 한 줄입니다: 서브에이전트에 넘길 컨텍스트는 프롬프트로 부탁하지 말고 스키마로 강제하라.** 자유 문자열 하나짜리 `query` 스키마를 보면 의심하세요.

> ⚠️ **함정 — 서브에이전트 결과를 그대로 믿으면 환각이 전파된다**: 서브에이전트는 **자신 있게 틀립니다.** 재고 조사 서브에이전트가 "그거" 가 뭔지 몰라서 아무 상품이나 조회하고 "재고 3개입니다" 라고 확신에 차서 보고하면, 부모는 그걸 검증할 방법이 없습니다 — 부모는 서브의 내부 도구 호출을 **못 보니까요**(격리의 대가). 그대로 사용자에게 전달됩니다. 방어법 셋: (1) 서브에이전트 프롬프트에 **"확인한 사실만 보고하라. 추측하지 마라"** 를 넣는다, (2) 서브에이전트에 `responseFormat` 을 씌워 `{ answer, confidence, sourcesChecked }` 처럼 **근거를 같이 반환**하게 한다, (3) 중요한 결정이라면 부모가 **원본 도구도 같이 갖고** 교차 검증한다. 격리는 공짜가 아닙니다 — 부모가 눈을 감는 대가로 얻는 것입니다.

### 무엇을 격리하고 무엇을 공유할지 — 체크리스트

설계할 때 에이전트마다 이 네 가지를 명시적으로 적어 보세요.

| 질문 | 예시 답변 |
|---|---|
| **입력**: 이 에이전트가 보는 것은? | 상품명 배열 + 질문 (대화 히스토리 없음) |
| **출력**: 돌려주는 것은? | 재고 요약 한 문단 (내부 도구 호출 안 보임) |
| **도구**: 접근 가능한 것은? | `check_stock` 만 (환불 도구 없음) |
| **상태**: 쓰기 가능한 것은? | 없음 (읽기만) |

이 표를 못 채우면 아직 설계가 안 된 것입니다. 그 상태로 구현하면 "왜 이 에이전트가 이걸 알지?" 또는 "왜 이걸 모르지?" 를 디버깅하는 데 며칠을 씁니다.

---

## 18-8. 실전 — 고객지원 멀티에이전트

이제 합쳐 봅시다. **라우터 + 서브에이전트** 조합으로 고객지원 시스템을 만듭니다.

```
문의 → [분류기] → billing / technical / general 전문 에이전트 → 답변
```

### 전문 에이전트마다 도구가 다르다

```ts
const ORDERS: Record<string, { status: string; product: string; daysAgo: number }> = {
  "A-1001": { status: "결제완료", product: "노트북", daysAgo: 20 },
  "A-1002": { status: "배송중", product: "모니터", daysAgo: 2 },
};

const lookupOrder = tool(
  ({ orderId }) => {
    const o = ORDERS[orderId];
    if (o === undefined) return `주문 ${orderId} 을(를) 찾을 수 없습니다.`;
    return `주문 ${orderId}: 상품=${o.product}, 상태=${o.status}, 구매 ${o.daysAgo}일 전`;
  },
  {
    name: "lookup_order",
    description: "주문번호로 주문 상세를 조회합니다. 주문번호 형식은 A-숫자4자리입니다.",
    schema: z.object({ orderId: z.string().describe("주문번호 (예: A-1001)") }),
  },
);

function buildSupportTeam() {
  return {
    billing: createAgent({
      model: MODEL,
      tools: [lookupOrder, getRefundPolicy],
      systemPrompt:
        "너는 결제 전문 상담원이다. 주문번호가 있으면 lookup_order 로 사실을 확인하고, " +
        "환불 정책을 조회해 실제 환불액까지 계산해 답하라. 정책에 없는 건 지어내지 마라.",
    }),
    technical: createAgent({
      model: MODEL,
      tools: [restartGuide],
      systemPrompt: "너는 기술지원 상담원이다. 해결 절차를 단계별로 안내하라.",
    }),
    general: createAgent({
      model: MODEL,
      tools: [checkStock, lookupOrder],
      systemPrompt: "너는 일반 상담원이다. 재고와 주문 조회를 도울 수 있다.",
    }),
  };
}
```

도구 구성이 **서로 다르다**는 게 핵심입니다. 결제 상담원은 재고 도구를 아예 모르고, 기술지원은 환불 정책을 모릅니다. 이게 18-7 체크리스트의 "도구: 접근 가능한 것은?" 을 실제로 구현한 모습입니다. 모델은 자기가 가진 도구만 보므로, 기술지원 상담원이 실수로 환불을 처리할 **가능성 자체가 없습니다.**

### 분류 → 위임

```ts
async function handleSupportRequest(question: string): Promise<void> {
  const team = buildSupportTeam();

  // 1단계 — 분류
  const routed = await classifier.invoke({ messages: [{ role: "user", content: question }] });
  const route = routed.structuredResponse as z.infer<typeof RouteSchema>;

  // 2단계 — 위임
  const agent = team[route.domain];
  const result = await agent.invoke({ messages: [{ role: "user", content: question }] });

  console.log(`\nQ: ${question}`);
  console.log(`   ↳ 분류: ${route.domain} (${route.reason})`);
  console.log(`   ↳ 답변: ${result.messages.at(-1)?.text ?? ""}`);
}
```

### 병렬로 처리한다

```ts
const questions = [
  "주문 A-1001 환불하면 얼마 받나요?",
  "앱이 자꾸 튕겨요.",
  "모니터 재고 있어요?",
];

// 세 문의는 서로 독립적이므로 병렬로 처리합니다.
await Promise.all(questions.map(handleSupportRequest));
```

**출력 예시** (모델 응답이므로 매번 다릅니다. 병렬 실행이라 **순서도 매번 다릅니다**)
```
Q: 앱이 자꾸 튕겨요.
   ↳ 분류: technical (앱 크래시로 기술적 문제입니다)
   ↳ 답변: 다음 순서로 시도해 보세요. 1) 앱 완전 종료 2) 캐시 삭제 ...

Q: 모니터 재고 있어요?
   ↳ 분류: general (단순 재고 문의입니다)
   ↳ 답변: 모니터는 현재 재고 12개 있습니다.

Q: 주문 A-1001 환불하면 얼마 받나요?
   ↳ 분류: billing (환불 금액 문의입니다)
   ↳ 답변: 주문 A-1001 은 구매 20일 전 건으로, 7~30일 구간이라 50% 환불 대상입니다...

총 모델 호출 횟수  13
```

호출 횟수를 직접 세어 보세요(`practice.ts` 의 `countingMiddleware` 가 해 줍니다). 문의 하나당 **분류 1번 + 전문가 2~3번**이 듭니다 — 전문가가 도구를 몇 번 부르느냐에 따라 달라지므로 총합은 실행마다 변합니다. 중요한 건 정확한 숫자가 아니라 **구조**입니다. 같은 일을 단일 에이전트로 하면 문의당 2번쯤이면 끝납니다. 분류 단계가 **모든 요청에 붙는 고정 세금**이고, 그만큼 지연도 늘어납니다.

그 대가로 얻은 것은 도메인별 독립 배포, 도구 격리, 그리고 각 전문가의 집중된 프롬프트입니다. **그 대가가 값어치를 하는지는 여러분의 상황이 정합니다.** 18-1 로 돌아가서 세 가지 명분(컨텍스트 관리 / 분산 개발 / 병렬화) 중 무엇에 해당하는지 다시 물어보세요. 셋 다 아니라면 단일 에이전트로 돌아가는 게 맞습니다.

> 💡 **실무 팁 — 병렬화가 지연을 되찾는 거의 유일한 수단이다**: `Promise.all` 대신 `for` 루프로 `await` 하면 문의 3개가 순차로 돌아 **3배 느립니다**. 멀티에이전트는 호출 수가 늘어나는 게 숙명인데, 늘어난 지연을 만회할 방법은 사실상 병렬화뿐입니다. 서로 의존하지 않는 작업은 **반드시** 병렬로 돌리세요. 단, 같은 API 키로 동시 요청을 너무 많이 날리면 429(rate limit)를 맞습니다 — 실무에서는 `p-limit` 같은 것으로 동시 실행 수를 제한하거나, `modelRetryMiddleware` 로 재시도를 붙입니다([Step 11](../step-11-middleware-builtin/)).

---

## 정리

| 아키텍처 | 제어권 | 하위가 보는 것 | 상태 | 강점 | 약점 |
|---|---|---|---|---|---|
| **서브에이전트** | 부모가 쥠 | 부모가 써준 query 만 | 없음 | 컨텍스트 격리, 병렬화, 분산 개발 | 고정 세금 1호출, 부모가 내부를 못 봄 |
| **핸드오프** | 넘어감 | 대화 히스토리 전체 | 있음 | 반복 요청이 쌈(2호출), 긴 대화에 자연스러움 | 순차 실행, 루프 위험 |
| **라우터** | 분류 후 위임 | 넘긴 query 만 | 없음 | 단순, 병렬 팬아웃 가능 | 매번 분류 비용 |
| **Skills** | 안 넘김(단일) | 전부 | 있음 | 가장 단순, 호출 수 최소 | 컨텍스트 누적 → 토큰 폭증 |
| **커스텀 워크플로** | 그래프 | 설계하기 나름 | 그래프 상태 | 완전한 제어, 결정적 로직 혼합 | 직접 다 짜야 함 |

**핵심 함정 3가지**

1. **서브에이전트는 부모의 대화 컨텍스트를 못 본다.** 이건 설계지 버그가 아니다. 그래서 `query: string` 같은 자유 문자열 스키마를 주면 모델이 "그거 재고 있어?" 를 그대로 넘기고, 서브에이전트는 **에러 없이** 엉뚱한 답을 확신에 차서 돌려준다. **넘길 정보는 프롬프트로 부탁하지 말고 `productNames: string[]` 처럼 스키마로 강제하라.**

2. **`Command` 를 반환하면서 `ToolMessage` 를 빠뜨리면 로컬에선 조용하다.** messages 가 `[human, ai, ai]` 가 되어 tool_call 에 짝이 없는데 LangChain 은 에러를 안 낸다. 터지는 건 다음 모델 호출 때 제공자 쪽(`tool_use ids were found without tool_result blocks`)이다. 가짜 모델 테스트에선 절대 안 잡힌다. **반드시 `tool_call_id: runtime.toolCallId` 로 `ToolMessage` 를 넣어라.**

3. **나눌수록 지연이 곱해지고, 멀티에이전트가 단일보다 나쁜 경우가 흔하다.** 에이전트를 하나 거칠 때마다 모델 호출이 최소 1번(1~3초) 늘어난다. "복잡한 문제니까 나누자" 는 거의 항상 틀린 추론이다. **먼저 단일 에이전트로 만들어 실제로 실패하는 걸 확인한 뒤에 나눠라.** 실패 지점을 모르면 어디서 나눌지도 모른다.

**그 외 조용히 틀리는 것들**

- 핸드오프에 `checkpointer` 를 안 주면 `activeAgent` 가 매 턴 초기화되어 **핸드오프가 무의미해진다**(에러는 안 남).
- 분류 스키마에 `z.string()` 을 쓰면 모델이 `"결제팀"` 을 뱉어 `specialists[domain]` 이 `undefined` 가 된다. **`z.enum` 을 써라.**
- 핸드오프 루프(A→B→A→B)는 `recursionLimit` 으로 "막히는" 게 아니라 **에러로 터진다.** 전환 횟수를 세서 핸드오프 도구를 치우는 게 진짜 방어다.
- 서브에이전트 결과를 검증 없이 믿으면 **환각이 그대로 전파된다.** 부모는 서브의 내부를 못 보므로 검증할 수단이 없다.

**한 문장 요약**: 멀티에이전트 설계는 아키텍처 고르기가 아니라 **"각 에이전트가 무엇을 보는가" 를 정하는 일**이다.

---

## 연습문제

1. **나눌 것인가, 말 것인가.** 세 상황((a) 위키 검색, 도구 1개 / (b) 고객 문의 3도메인, 도메인당 도구 5~8개, 팀 3개 / (c) CSV 분석, 도구 3개, 2초 제한)에 대해 단일/멀티를 고르고 이유를 적으세요.
2. **서브에이전트를 도구로 감싸기.** "긴 글을 3줄로 요약하는" 서브에이전트를 만들고 `summarize` 도구로 감싸세요. 반드시 **마지막 메시지의 텍스트만** 반환할 것.
3. **컨텍스트 격리 함정 고치기.** `schema: z.object({ query: z.string() })` 인 서브에이전트 도구를, 모델이 지시어("그거")를 쓸 수 **없도록** 스키마를 고치세요.
4. **핸드오프 도구 만들기.** `transfer_to_technical` 을 완성하세요. `Command` 로 `activeAgent` 를 바꾸고, `ToolMessage` 를 `runtime.toolCallId` 와 함께 넣을 것. 2턴에서도 상태가 유지되는지 확인하세요.
5. **라우터 만들기.** `z.enum` 으로 `RouteSchema` 를 정의하고, `routeAndAnswer(question)` 이 분류 → 위임 → 답변을 하도록 완성하세요.
6. **Skills.** `load_skill` 을 완성하세요. 없는 스킬이면 빈 문자열이 아니라 **사용 가능한 목록을 알려주는 문자열**을 반환할 것. description 에 스킬 목록을 적을 것.
7. **핸드오프 루프 막기.** 전환이 3회를 넘으면 `wrapModelCall` 에서 `transfer_` 로 시작하는 도구를 **빼고** 모델을 부르는 미들웨어를 만드세요. 그리고 왜 `recursionLimit` 만으론 부족한지 주석으로 적으세요.
8. **종합 — 라우터 + 서브에이전트.** 문제 5의 라우터와 문제 2의 요약 서브에이전트를 합치세요. 여러 문의를 **병렬로**(`Promise.all`) 처리하고, 각 답변을 3줄 요약해 출력할 것. 총 소요 시간도 재세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 19 — 관측·테스트·평가](../step-19-observability-eval/)

멀티에이전트를 만들었다면 이제 **"왜 이렇게 답했는지"** 를 추적해야 합니다. 18-1 에서 말한 "디버깅이 어려워진다" 는 비용을 실제로 갚는 스텝입니다. 에이전트가 여럿이면 `console.log` 로는 한계가 옵니다.

그리고 서브에이전트를 프레임워크 차원에서 제대로 다루고 싶다면 → **[DeepAgent Step 06 — 서브에이전트](../../deepagent/step-06-subagents/)**. 이 스텝에서 손으로 만든 "에이전트를 도구로 감싸기" 가 거기서는 `createSubAgentMiddleware` 와 `task` 도구로 기본 제공됩니다. 여기서 원리를 알고 가면 거기서 왜 그렇게 설계됐는지가 보입니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(18-1 ~ 18-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `project/` 의 의존성을 쓰며, 저장소 루트에서 `npx tsx docs/reference/langchain/step-18-multi-agent/practice.ts` 로 실행합니다. `ANTHROPIC_API_KEY` 가 `project/.env` 에 있어야 합니다. OpenAI 로 실습하려면 각 파일 상단의 `const MODEL = "anthropic:claude-sonnet-4-6"` 을 `"openai:gpt-5.5"` 로 바꾸기만 하면 됩니다 — 나머지 코드는 그대로 동작합니다.

> ⚠️ **비용 주의**: 이 스텝의 예제는 멀티에이전트라 모델 호출이 많습니다. `practice.ts` 를 통째로 돌리면 30번 넘게 호출합니다. 그래서 파일 상단에 `RUN` 스위치를 두었습니다 — `const RUN = ["18-3"]` 처럼 절 번호 배열을 주면 그 절만 돕니다. 처음에는 관심 있는 절 하나만 켜고 보세요.

### practice.ts

본문 강의를 따라가며 실행할 예제를 `[18-1] ~ [18-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다. (18-2 는 비교표라 코드가 없어 절 번호도 없습니다.)

- **`countingMiddleware`** 가 이 파일의 숨은 주인공입니다. `wrapModelCall` 로 모델 호출을 세기만 하는 8줄짜리 미들웨어인데, 각 절 끝의 `printKV({ "모델 호출 횟수": modelCalls })` 가 **"나누면 호출이 몇 배로 늘어나는가"** 를 숫자로 보여줍니다. 단일 에이전트인 18-1 이 가장 적고, 라우터+서브에이전트인 18-8 이 가장 많습니다(정확한 값은 모델이 도구를 몇 번 부르느냐에 따라 실행마다 달라집니다). 본문의 추상적인 "지연이 곱해진다" 를 직접 체감하는 장치입니다.
- **`[18-3]`** 의 `researchTool` 에서 `return last?.text ?? "(빈 응답)"` 한 줄이 컨텍스트 격리의 전부입니다. 이 줄을 `JSON.stringify(result.messages)` 로 바꿔서 돌려 보세요. 부모의 컨텍스트가 서브에이전트의 시행착오로 어떻게 오염되는지 바로 보입니다 — **나눈 이유가 사라지는 걸** 눈으로 확인할 수 있습니다.
- **`[18-4]`** 는 이 스텝의 심장입니다. `transferToBilling` 이 `Command` 를 반환하며 `ToolMessage` 를 직접 넣는 부분(`tool_call_id: runtime.toolCallId`)이 핵심입니다. 이 `ToolMessage` 를 지워 보면 로컬에서는 아무 에러도 안 나고 실제 API 호출에서만 터지는 걸 확인할 수 있습니다. 그리고 1턴/2턴을 같은 `thread_id` 로 연달아 호출해 **2턴에서 모델 호출이 줄어드는 것**(상태가 남아 분류가 생략됨)을 숫자로 보여줍니다.
- **`[18-7]`** 은 `research_bad` 와 `research_good` 을 **같은 대화로 나란히** 돌립니다. 같은 "그거 재고 있어?" 를 주는데, `research_bad` 는 `query: "그거 재고 있어?"` 를 넘겨 서브에이전트가 헤매고, `research_good` 은 스키마 때문에 `productNames: ["노트북"]` 을 넣을 수밖에 없습니다. 본문 18-7 의 주장을 한 번에 확인하는 블록입니다.
- **`[18-8]`** 의 `Promise.all(questions.map(handleSupportRequest))` 는 병렬 실행이라 **출력 순서가 매번 다릅니다.** 이건 버그가 아닙니다. `for` 루프로 바꿔서 얼마나 느려지는지 비교해 보세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고, 채워야 할 자리에 `TODO` 주석과 `throw new Error("TODO: 문제 N")` 이 있습니다.

- 파일을 그대로 실행하면 **문제 2에서 바로 `TODO` 에러로 멈춥니다.** 정상입니다. 위에서부터 하나씩 채워 나가세요.
- `throw new Error("TODO")` 를 굳이 넣어 둔 이유가 있습니다. 함수 본문을 그냥 비워 두면 반환 타입이 안 맞아 `tsc` 가 에러를 냅니다. `throw` 가 있으면 **타입 체크는 통과하면서** 실행하면 멈추므로, 여러분이 채운 부분만 정확히 검증됩니다.
- **`[문제 1]`** 은 코드가 없습니다. 주석에 답을 적는 문제입니다. 멀티에이전트에서 가장 중요한 결정은 코드가 아니라 **"나눌 것인가" 라는 판단**이라서 일부러 첫 문제로 넣었습니다.
- **`[문제 3]`** 이 가장 어렵습니다. "description 을 더 잘 쓰면 되지 않나?" 라는 유혹이 강한데, 그 길로 가면 안 됩니다. `schema` 를 고치세요. 본문 18-7 을 다시 읽어 보세요.
- **`[문제 7]`** 은 미들웨어 정의만 하면 되고 실행 코드가 없습니다(`main` 에서 `void loopGuardMiddleware` 로 참조만 합니다). 힌트인 `handler({ ...request, tools: request.tools.filter(...) })` 를 그대로 따라가면 됩니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답과 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 여세요. 각 정답 위 주석에 "왜 이렇게 푸는가" 와 **"틀리면 어떻게 되는가"** 를 적어 두었습니다.

- **`[정답 1]`** 의 (b) 해설이 이 파일에서 가장 중요합니다. 멀티에이전트를 고르는 이유가 성능이 아니라 **"팀이 3개다"** 라는 조직 문제라는 것. 그리고 (c) 에서 2초 제한이 결정타가 되는 것 — 지연 예산이 빡빡하면 멀티에이전트는 검토 대상조차 아닙니다.
- **`[정답 3]`** 은 `productNames: z.array(z.string())` 으로 쪼갠 것이 전부입니다. 해설의 핵심 문장은 이것입니다 — *"해법의 핵심은 description 을 잘 쓰는 게 아니라 schema 로 강제하는 것"*. 배열로 만드는 순간 모델이 지시어를 넣을 **자리가 구조적으로 없어집니다.**
- **`[정답 4]`** 의 주석에 실제 제공자 에러 메시지 두 개(Anthropic 의 `tool_use ids were found without tool_result blocks`, OpenAI 의 `assistant message with 'tool_calls' must be followed by tool messages`)를 적어 두었습니다. 언젠가 이 에러를 만나면 이 절을 떠올리세요.
- **`[정답 7]`** 의 해설이 `recursionLimit` 에 대한 오해를 정면으로 다룹니다. `recursionLimit` 은 **해결책이 아니라 안전장치**입니다 — 한계에 도달하면 사용자는 답변 대신 `GraphRecursionError` 를 받고, 그때까지 토큰은 다 씁니다. 반면 도구를 치우면 **에러 대신 답변**이 나갑니다.
- **`[정답 7]`** 의 `filter` 에 `typeof t.name === "string"` 검사가 붙은 이유도 주석에 있습니다. `request.tools` 의 타입은 `(ClientTool | ServerTool)[]` 이고 `ServerTool` 은 `Record<string, unknown>` 이라 `t.name` 이 `unknown` 입니다. 바로 `.startsWith` 를 부르면 `tsc` 가 `TS18046` 으로 막습니다 — 실제로 이 파일을 쓰면서 걸렸던 에러입니다.
- **`[정답 8]`** 은 `Promise.all` 안에서 라우팅과 요약을 **연달아** 합니다. 문의별로는 순차(라우팅 → 요약)지만 문의끼리는 병렬입니다. 이 구조가 실무 파이프라인의 전형입니다.

```ts file="./solution.ts"
```
