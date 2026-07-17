# Step 13 — Human-in-the-Loop

> **학습 목표**
> - **되돌릴 수 없는 행동** 앞에 사람의 승인을 끼워 넣는다
> - `interrupt()` 가 실행을 멈추고 체크포인트에 저장하는 원리를 이해하고, **checkpointer 가 왜 필수인지** 설명한다
> - `humanInTheLoopMiddleware` 의 `interruptOn` 으로 **특정 도구만** 승인 대상으로 지정한다
> - `Command({ resume })` 로 **approve / edit / reject** 세 가지 결정을 보내 재개한다
> - `__interrupt__` 를 읽어 "무엇을 승인해달라는지" 사람에게 보여준다
> - **비동기 승인**(몇 시간 뒤 응답)이 왜 가능한지를 영속 체크포인터로 설명한다
>
> **선행 스텝**: [Step 12 — 커스텀 미들웨어](../step-12-middleware-custom/)
> **예상 소요**: 80분

[Step 11](../step-11-middleware-builtin/) 에서 내장 미들웨어를, [Step 12](../step-12-middleware-custom/) 에서 직접 만드는 미들웨어를 봤습니다. 지금까지의 에이전트는 한 번 `invoke()` 하면 끝까지 혼자 달렸습니다. 도구를 부르고, 결과를 받고, 또 부르고, 답을 냅니다. 사람은 처음에 질문을 던지고 마지막에 답을 받을 뿐입니다.

이 스텝에서는 그 흐름을 **중간에 멈춥니다.** 에이전트가 위험한 도구를 부르려는 순간 실행을 정지시키고, 사람에게 "이거 해도 됩니까?" 를 물은 뒤, 답을 받아 이어서 갑니다. LangGraph 는 이걸 **interrupt** 라고 부릅니다. 개념 자체는 단순한데 — 멈췄다가 다시 간다 — 그 "다시 간다" 를 구현하는 방식 때문에 조용히 어긋나는 함정이 유난히 많은 영역입니다. 특히 **interrupt 이전의 코드가 재개할 때 다시 실행된다**는 사실은 모르고 쓰면 결제가 두 번 나갑니다.

---

## 13-1. 왜 사람이 끼어들어야 하나

에이전트에게 도구를 쥐여 주는 순간, 모델의 판단이 곧 실행입니다. 모델은 확률적으로 다음 토큰을 고를 뿐이고, 그 결과가 `send_email({ to: "전체임직원@회사.com" })` 일 수도 있습니다. 문제는 이 행동들이 **되돌릴 수 없다**는 것입니다.

행동을 두 부류로 나눠 보면 어디에 게이트를 세울지 분명해집니다.

| 되돌릴 수 있는 행동 | 되돌릴 수 없는 행동 |
|---|---|
| 검색 (`search`) | 결제 (`charge_card`) |
| 파일 읽기 (`read_file`) | 파일 삭제 (`delete_file`) |
| DB `SELECT` | DB `DELETE` / `UPDATE` |
| 계산, 요약 | 메일·슬랙 발송 |
| 초안 작성 | 배포, 외부 API 쓰기 |

왼쪽은 모델이 틀려도 토큰과 시간만 낭비합니다. 오른쪽은 틀리면 **사고**입니다. 메일은 회수가 안 되고, 결제는 환불 절차가 필요하고, 삭제는 백업이 없으면 끝입니다.

여기서 흔한 오해 하나. "프롬프트에 '메일 보내기 전에 꼭 확인받아' 라고 쓰면 되지 않나?" — 안 됩니다. 그건 **모델에게 부탁하는 것**이지 강제하는 게 아닙니다. 모델은 그 지시를 무시할 수 있고, 실제로 무시합니다. 승인 게이트는 모델이 협조하든 말든 **코드가 강제**해야 합니다. 프롬프트는 정책이 아니라 제안입니다.

> 💡 **실무 팁**: 승인 게이트를 설계할 때 "어떤 도구를 막을까" 가 아니라 **"어떤 도구를 열어 둘까"** 로 뒤집어 생각하세요. 기본을 "전부 승인 필요" 로 두고 안전하다고 검증된 것만 여는 편이, 기본을 "전부 통과" 로 두고 위험한 걸 하나씩 막는 것보다 안전합니다. 후자는 도구를 새로 추가할 때마다 `interruptOn` 에 넣는 걸 잊어버리면 그대로 구멍이 됩니다. 실제로 13-3 의 함정이 정확히 그 사고입니다.

---

## 13-2. `interrupt()` 의 동작 원리

`interrupt()` 는 `@langchain/langgraph` 가 내보내는 함수입니다. 노드 안에서 호출하면 **그 자리에서 실행이 멈추고**, 호출자에게 제어가 돌아갑니다.

미들웨어를 쓰기 전에 원리부터 봅시다. 모델 없이 순수 그래프로 결제 승인을 흉내 냅니다.

```ts
import { Command, END, MemorySaver, START, StateGraph, interrupt, isInterrupted } from "@langchain/langgraph";
import * as z from "zod";

let sideEffectCount = 0;

const ApprovalState = z.object({
  amount: z.number(),
  status: z.string().nullable().default(() => null),
});

const approvalGraph = new StateGraph(ApprovalState)
  .addNode("charge", (state) => {
    // ⚠️ interrupt 이전의 코드입니다. 재개할 때 "다시" 실행됩니다.
    sideEffectCount += 1;
    console.log(`[interrupt 이전 코드 실행] 누적 ${sideEffectCount}회`);

    // 여기서 실행이 멈춥니다. 아래 코드는 지금 실행되지 않습니다.
    const decision = interrupt({
      question: `${state.amount}원을 결제할까요?`,
      amount: state.amount,
    });

    return { status: decision === true ? "결제완료" : "취소됨" };
  })
  .addEdge(START, "charge")
  .addEdge("charge", END)
  .compile({ checkpointer: new MemorySaver() }); // ← 없으면 에러

const chargeConfig = { configurable: { thread_id: "charge-1" } };

const paused = await approvalGraph.invoke({ amount: 50_000 }, chargeConfig);
if (isInterrupted(paused)) {
  console.log(JSON.stringify(paused.__interrupt__, null, 2));
}
console.log("status =", paused.status);

const resumed = await approvalGraph.invoke(new Command({ resume: true }), chargeConfig);
console.log("재개 후 status =", resumed.status);
console.log(`부수효과 ${sideEffectCount}회`);
```

**출력** (이 예제는 모델을 쓰지 않아 결정적입니다)
```
[interrupt 이전 코드 실행] 누적 1회
[
  {
    "id": "892d2d193a6cc2013118702be88e5113",
    "value": {
      "question": "50000원을 결제할까요?",
      "amount": 50000
    }
  }
]
status = null
[interrupt 이전 코드 실행] 누적 2회
재개 후 status = 결제완료
부수효과 2회
```

읽을 것이 많습니다. 하나씩 봅시다.

**첫째, `invoke()` 가 에러 없이 정상 반환했습니다.** 다만 `status` 는 `null` 입니다. 노드가 끝까지 못 갔으니 반환값이 상태에 반영되지 않은 것입니다. 대신 결과에 `__interrupt__` 가 붙어 있습니다.

**둘째, `__interrupt__` 는 배열이고, 항목은 `{ id, value }` 입니다.** `value` 는 `interrupt()` 에 넘긴 값 그대로입니다. JSON 직렬화만 되면 아무거나 넣을 수 있습니다. 이 값이 곧 "사람에게 보여줄 내용" 입니다.

**셋째, `Command({ resume: true })` 의 `resume` 값이 `interrupt()` 의 반환값이 됩니다.** 재개하면 `decision` 변수에 `true` 가 들어가고, 노드는 `status: "결제완료"` 를 반환합니다.

**넷째 — 그리고 이게 이 스텝에서 가장 중요합니다 — 부수효과가 2회 실행되었습니다.**

### 재개는 "이어서" 가 아니라 "처음부터 다시" 다

`interrupt()` 를 함수 중간에서 멈췄다가 그 지점부터 이어가는 것(코루틴, `yield`)으로 상상하기 쉽습니다. **그렇지 않습니다.** 실제로 일어나는 일은 이렇습니다.

1. 노드 함수가 처음부터 실행됩니다.
2. `interrupt()` 를 만나면 예외를 던져 노드 실행을 **통째로 중단**합니다. 이때까지의 작업은 버려집니다.
3. 체크포인터가 "이 노드가 이 질문에서 멈췄다" 를 저장합니다.
4. 재개하면 **노드 함수를 처음부터 다시 실행**합니다.
5. 이번엔 `interrupt()` 자리에 저장된 답(`resume` 값)이 있으므로, 멈추지 않고 그 값을 **즉시 반환**하며 지나갑니다.
6. 노드가 끝까지 갑니다.

그래서 `interrupt()` **앞**에 있는 코드는 중단 횟수 + 1 번 실행됩니다. 위 예제에서 `sideEffectCount += 1` 이 두 번 돈 이유입니다.

> ⚠️ **함정 (부수효과 재실행 — 이 스텝의 핵심)**: `interrupt()` 앞에 결제·발송·삽입 같은 부수효과를 두면 **재개할 때마다 한 번 더 실행됩니다.** 위 예제가 진짜 결제 API 였다면 5만원이 두 번 빠져나갔을 겁니다. 게다가 이 사고는 **에러 없이** 일어납니다 — 로그를 보면 성공, 성공입니다.
>
> 방어법 세 가지:
> 1. **`interrupt()` 를 부수효과보다 앞으로 옮긴다.** 승인받기 전엔 아무것도 하지 않는 게 원칙적으로 옳습니다. 대부분 이걸로 해결됩니다.
> 2. **부수효과를 멱등(idempotent)하게 만든다.** 결제 API 의 idempotency key 가 정확히 이걸 위한 장치입니다. 같은 키로 두 번 호출해도 한 번만 먹힙니다.
> 3. **부수효과를 별도 노드로 분리한다.** 승인 노드와 실행 노드를 나누면, 재실행되는 건 승인 노드뿐이고 실행 노드는 한 번만 돕니다.
>
> 뒤에서 볼 `humanInTheLoopMiddleware` 는 이 문제를 구조적으로 피합니다 — 승인은 도구 **실행 전**에 일어나고, 도구 함수 자체는 승인이 떨어진 뒤 한 번만 호출되기 때문입니다. 직접 `interrupt()` 를 쓸 때만 조심하면 됩니다.

### checkpointer 가 없으면

멈춘 지점을 어딘가에 적어 둬야 재개할 수 있습니다. 그 저장소가 **checkpointer** 입니다. 빼면 이렇게 됩니다.

```ts
const noCheckpointerGraph = new StateGraph(ApprovalState)
  .addNode("ask", () => {
    interrupt("승인해 주세요");
    return {};
  })
  .addEdge(START, "ask")
  .addEdge("ask", END)
  .compile(); // ← checkpointer 를 일부러 뺐습니다

try {
  await noCheckpointerGraph.invoke({ amount: 1000 });
} catch (error) {
  console.log((error as Error).constructor.name);
  console.log((error as Error).message.split("\n")[0]);
}
```

**출력**
```
GraphValueError
No checkpointer set
```

`GraphValueError: No checkpointer set` 입니다. 다행히 **요란하게** 실패합니다. 조용히 통과해서 승인 없이 실행됐다면 훨씬 위험했을 겁니다.

`checkpointer` 와 `thread_id` 는 항상 **짝**으로 다닙니다. 체크포인터는 "어디에 저장할지", `thread_id` 는 "어느 대화에 저장할지" 를 정합니다. 하나만 있으면 의미가 없습니다. [Step 10](../step-10-memory/) 에서 본 그 구조 그대로입니다 — 메모리와 HITL 이 같은 장치를 공유합니다.

> ⚠️ **함정**: `checkpointer` 없이 `thread_id` 만 줘도 에러가 나지 않고 그냥 아무것도 저장되지 않습니다. HITL 에서는 `interrupt()` 가 `No checkpointer set` 으로 잡아 주지만, [Step 10](../step-10-memory/) 의 메모리는 조용히 안 남습니다. 같은 실수가 한쪽에서는 시끄럽고 한쪽에서는 조용합니다.

### `interrupt` 는 예외처럼 던져진다 — try/catch 금지

3번 단계에서 "예외를 던져 중단한다" 고 했습니다. 이게 무슨 뜻인지 직접 봅시다.

```ts
const swallowGraph = new StateGraph(ApprovalState)
  .addNode("ask", () => {
    try {
      const decision = interrupt("승인해 주세요");
      return { status: `승인값=${String(decision)}` };
    } catch (error) {
      // ⚠️ 중단 신호를 잡아 버렸습니다.
      console.log(`interrupt 를 잡아버렸습니다: ${(error as Error).name}`);
      return { status: "삼켜짐" };
    }
  })
  .addEdge(START, "ask")
  .addEdge("ask", END)
  .compile({ checkpointer: new MemorySaver() });

const swallowed = await swallowGraph.invoke({ amount: 1000 }, { configurable: { thread_id: "sw" } });
console.log("status =", swallowed.status);
console.log("__interrupt__ 있음?", "__interrupt__" in swallowed);
```

**출력**
```
interrupt 를 잡아버렸습니다: GraphInterrupt
status = 삼켜짐
__interrupt__ 있음? false
```

> ⚠️ **함정 (try/catch 가 중단을 삼킨다)**: `interrupt()` 는 `GraphInterrupt` 예외를 throw 해서 실행을 빠져나갑니다. **예외처럼 생겼지만 에러가 아니라 제어 흐름**입니다. 그래서 넓은 `try/catch` 로 감싸면 중단 신호가 그래프까지 도달하지 못하고, 노드는 `catch` 블록으로 흘러가 아무 일 없었다는 듯 끝납니다. 결과에 `__interrupt__` 가 없으니 **호출자는 승인이 필요했다는 사실조차 모릅니다.** 에러도 안 납니다.
>
> 이 함정이 특히 잘 나는 곳은 "DB 작업이라 혹시 몰라서" 방어적으로 `try/catch` 를 두른 노드입니다. 그 안에 승인 로직을 넣는 순간 승인이 증발합니다.
>
> **원칙: `interrupt()` 는 `try` 밖에 두세요.** 부득이하면 다시 던지세요.
> ```ts
> } catch (error) {
>   if (error instanceof Error && error.name === "GraphInterrupt") throw error;
>   return { status: "DB 에러를 처리했습니다" };
> }
> ```

### 한 노드에 `interrupt` 가 여러 개면

```ts
const twoGraph = new StateGraph(TwoState)
  .addNode("askTwice", () => {
    const first = interrupt("첫 번째 질문");
    const second = interrupt("두 번째 질문");
    return { answers: [`1=${String(first)}`, `2=${String(second)}`] };
  })
  // ...
  .compile({ checkpointer: new MemorySaver() });

const t1 = await twoGraph.invoke({}, twoConfig);                              // "첫 번째 질문"
const t2 = await twoGraph.invoke(new Command({ resume: "답변A" }), twoConfig); // "두 번째 질문"
const t3 = await twoGraph.invoke(new Command({ resume: "답변B" }), twoConfig); // 완료
```

**출력**
```
1차 중단: "첫 번째 질문"
2차 중단: "두 번째 질문"
⚠️ id 가 1차와 같습니다: true
최종: ["1=답변A","2=답변B"]
```

`resume: "답변A"` 를 보내면 노드가 처음부터 다시 돌면서 **첫 번째** `interrupt` 는 저장된 "답변A" 를 반환하고 지나가고, **두 번째** `interrupt` 에서 새로 멈춥니다. 세 번 invoke 해야 끝납니다.

주목할 점: **두 interrupt 의 `id` 가 같습니다.** 그러니 `id` 로 "어느 질문에 대한 답인지" 를 구분할 수 없습니다.

> ⚠️ **함정 (여러 interrupt 는 순서로 매칭된다)**: 한 노드 안의 `interrupt` 들은 **호출 순서(index)** 로 답을 찾아갑니다. `id` 가 아닙니다. 그래서 조건문으로 `interrupt` 를 건너뛰면 인덱스가 밀려서 **엉뚱한 질문에 엉뚱한 답이 들어갑니다.**
> ```ts
> // 위험: 조건에 따라 interrupt 개수가 달라집니다
> if (state.amount > 10000) {
>   const a = interrupt("고액 결제 승인?");   // 재개 시 이 조건이 달라지면
> }
> const b = interrupt("최종 확인?");           // 답이 뒤바뀝니다
> ```
> 재개할 때 노드가 처음부터 다시 도는데, 그 사이 상태가 바뀌어 `if` 의 결과가 달라지면 인덱스가 어긋납니다. 에러 없이 답만 뒤섞입니다.
>
> **같은 이유로 `while` 루프 안에서 `interrupt()` 를 부르지 마세요.** 재개할 때마다 이전 반복이 전부 재생됩니다. 반복 승인이 필요하면 루프를 노드 밖(호출자)에 두세요 — 13-8 의 CLI 가 그 방식입니다.

---

## 13-3. `humanInTheLoopMiddleware` — 도구를 골라 막는다

원리를 봤으니 실전 도구로 갑니다. `interrupt()` 를 직접 쓰는 대신, `langchain` 이 주는 미들웨어를 얹으면 **도구 호출 직전**에 자동으로 승인 게이트가 생깁니다.

```ts
import { createAgent, humanInTheLoopMiddleware, tool } from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import * as z from "zod";

const sendEmail = tool(
  async ({ to, subject, body }) => {
    console.log(`📧 [실제 발송] to=${to}`);
    return `${to} 에게 메일을 보냈습니다.`;
  },
  {
    name: "send_email",
    description: "지정한 수신자에게 이메일을 발송합니다. 되돌릴 수 없습니다.",
    schema: z.object({
      to: z.string().describe("수신자 이메일 주소"),
      subject: z.string().describe("메일 제목"),
      body: z.string().describe("메일 본문"),
    }),
  },
);

const emailAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  // OpenAI 를 쓰려면: model: "openai:gpt-5.5"
  tools: [sendEmail, searchContact],
  systemPrompt: "당신은 사용자를 대신해 메일을 보내는 비서입니다.",
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        send_email: {
          allowedDecisions: ["approve", "edit", "reject"],
          description: "🚨 메일이 실제로 발송됩니다. 수신자와 내용을 확인하세요.",
        },
        search_contact: false, // 읽기 전용은 막지 않습니다
      },
      descriptionPrefix: "실행 승인이 필요합니다",
    }),
  ],
  checkpointer: new MemorySaver(), // ← HITL 미들웨어도 checkpointer 필수
});
```

`interruptOn` 은 **도구 이름 → 정책** 의 매핑입니다. 값으로 세 가지를 줄 수 있습니다.

| 값 | 의미 |
|---|---|
| `true` | 승인 필요. 허용 결정은 기본값 `["approve", "edit", "reject"]` |
| `false` | 승인 없이 통과 (`interruptOn` 에 아예 안 적은 것과 같음) |
| `{ allowedDecisions, description?, argsSchema?, when? }` | 세부 정책 지정 |

`InterruptOnConfig` 객체의 필드는 이렇습니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `allowedDecisions` | `("approve" \| "edit" \| "reject")[]` | 사람이 내릴 수 있는 결정. **필수** |
| `description` | `string \| (toolCall, state, runtime) => string` | 승인 요청에 붙일 설명. 함수로 주면 동적 생성 |
| `argsSchema` | `Record<string, any>` | 인자의 JSON 스키마. 수정 UI 를 만들 때 씁니다 |
| `when` | `(request) => boolean \| Promise<boolean>` | `true` 면 중단, `false` 면 자동 승인 |

`descriptionPrefix` 는 `description` 을 **주지 않은** 도구에만 붙는 기본 문구입니다. 기본값은 `"Tool execution requires approval"` 이고, 실제로 만들어지는 설명은 이렇게 생겼습니다.

```
Tool execution requires approval

Tool: send_email
Args: {
  "to": "a@b.com",
  "subject": "s",
  "body": "b"
}
```

`description` 을 지정하면 이 프리픽스는 **무시되고** 지정한 문구가 그대로 쓰입니다. 둘이 합쳐지지 않습니다.

에이전트를 실행하면 도구 직전에 멈춥니다.

```ts
const agentConfig = { configurable: { thread_id: "email-1" } };
const step1 = await emailAgent.invoke(
  { messages: [{ role: "user", content: "김민수에게 '내일 회의 확인' 이라는 제목으로 메일 보내줘." }] },
  agentConfig,
);
console.log("실제 발송된 메일 수:", sentLog.length); // → 0
```

`search_contact` 는 승인 없이 실행되고, `send_email` 직전에 멈춥니다. **메일은 아직 나가지 않았습니다.**

> ⚠️ **함정 (`interruptOn` 의 도구 이름 오타 — 조용히 실행된다)**: 이게 이 스텝에서 **가장 위험한** 함정입니다.
>
> ```ts
> const typoAgent = createAgent({
>   model: "anthropic:claude-sonnet-4-6",
>   tools: [sendEmail],                                    // 실제 이름은 "send_email"
>   middleware: [humanInTheLoopMiddleware({
>     interruptOn: { sendEmail: true },                    // ← 오타!
>   })],
>   checkpointer: new MemorySaver(),
> });
>
> const result = await typoAgent.invoke(
>   { messages: [{ role: "user", content: "typo@example.com 으로 '테스트' 메일 보내줘." }] },
>   { configurable: { thread_id: "typo-1" } },
> );
> ```
> **출력**
> ```
> 중단됐나요? false
> 승인 없이 발송된 메일: 1건 😱
> ```
>
> **에러도, 경고도, 중단도 없습니다. 메일이 그냥 나갑니다.** `interruptOn` 은 `Record<string, ...>` 이라 아무 문자열 키나 받고, 존재하지 않는 도구 이름이어도 타입 시스템이 잡아 주지 않습니다. 그리고 `interruptOn` 에 없는 도구는 **자동 승인**이 기본값이므로, 오타는 곧 "그 도구를 승인 대상에서 뺀 것" 과 정확히 같은 결과가 됩니다.
>
> `sendEmail`(변수명) 과 `send_email`(도구 이름) 이 다른 것도 사고를 부릅니다. `tool()` 에 준 `name` 이 진짜 이름입니다.
>
> **방어법**: 도구 이름을 상수로 뽑아 `tool()` 정의와 `interruptOn` 양쪽에서 같은 상수를 쓰세요.
> ```ts
> const TOOL_NAMES = { deleteFile: "delete_file" } as const;
>
> const deleteFile = tool(async ({ path }) => { /* ... */ }, {
>   name: TOOL_NAMES.deleteFile,   // ← 같은 상수
>   description: "파일을 삭제합니다.",
>   schema: z.object({ path: z.string() }),
> });
>
> humanInTheLoopMiddleware({
>   interruptOn: { [TOOL_NAMES.deleteFile]: true },  // ← 오타 내면 tsc 가 잡아 줍니다
> });
> ```
> 배포 전에 "승인 게이트가 실제로 걸리는가" 를 테스트로 한 번 확인하는 것도 필수입니다. 이 함정은 눈으로는 절대 못 잡습니다.

> 💡 **실무 팁 (`when` 으로 조건부 승인)**: 도구 단위로 전부 막거나 전부 열지 않고, **인자를 보고** 결정할 수 있습니다.
> ```ts
> humanInTheLoopMiddleware({
>   interruptOn: {
>     delete_file: {
>       allowedDecisions: ["approve", "reject"],
>       when: (request) => {
>         const path = request.toolCall.args.path;
>         if (typeof path !== "string") return true;  // 모르면 막는다 (fail-safe)
>         return path.startsWith("/etc");             // 시스템 경로만 승인 요구
>       },
>     },
>   },
> });
> ```
> "10만원 넘는 결제만", "외부 도메인 메일만" 같은 정책이 이걸로 표현됩니다. 승인 피로(approval fatigue)를 줄이는 데 중요합니다 — 사람이 모든 걸 승인해야 하면 결국 아무것도 안 읽고 y 만 누릅니다.
>
> 두 가지 주의: `when` 이 받는 인자는 **모델이 만들어 낸 값**이라 예상과 다른 모양일 수 있습니다. 반드시 방어적으로 읽으세요. 그리고 `when` 이 예외를 던지면 승인 자체가 망가집니다. 판단이 애매하면 **중단하는 쪽**으로 기울이세요.

---

## 13-4. 재개 — `Command({ resume })`

멈춘 에이전트를 이어가려면 `Command` 에 `resume` 을 담아 다시 `invoke` 합니다.

```ts
import { Command } from "@langchain/langgraph";

const approved = await emailAgent.invoke(
  new Command({ resume: { decisions: [{ type: "approve" }] } }),
  agentConfig, // ← 같은 thread_id 여야 합니다
);
console.log("실제 발송된 메일 수:", sentLog.length); // → 1
```

두 가지가 핵심입니다.

**첫째, `thread_id` 가 같아야 합니다.** 체크포인터는 `thread_id` 로 "어느 대화를 재개할지" 찾습니다. 다른 값을 주면 재개가 아니라 **새 대화**가 시작되고, 승인은 영영 처리되지 않은 채 남습니다.

**둘째, `resume` 의 형식이 13-2 와 다릅니다.** 순수 그래프에서는 `resume: true` 처럼 아무 값이나 됐습니다. `interrupt()` 에 넘긴 값과 짝이 되는 아무 값이니까요. 하지만 **HITL 미들웨어는 정해진 형식만** 받습니다.

```ts
new Command({
  resume: {
    decisions: [ /* 도구 호출 하나당 결정 하나 */ ],
  },
})
```

형식을 틀리면 어떻게 될까요? 직접 확인해 봅시다.

```ts
// 그래프 예제에서는 맞았던 형식
await wrongAgent.invoke(new Command({ resume: true }), wrongConfig);
```
**출력**
```
Error: Invalid HITLResponse: decisions must be a non-empty array
```

```ts
// "accept" 는 존재하지 않습니다. 올바른 이름은 "approve" 입니다.
await wrongAgent.invoke(new Command({ resume: { decisions: [{ type: "accept" }] } }), wrongConfig);
```
**출력**
```
Error: Unexpected human decision: {"type":"accept"}. Decision type 'accept' is not allowed for tool 'send_email'. Expected one of ["approve","edit","reject"]
```

```ts
// 도구 호출은 1개인데 결정을 2개 보내면
await agent.invoke(new Command({ resume: { decisions: [{ type: "approve" }, { type: "approve" }] } }), config);
```
**출력**
```
Error: Number of human decisions (2) does not match number of hanging tool calls (1).
```

> 💡 **실무 팁**: `resume` 형식이 틀리면 **조용히 무시되지 않고 또렷한 에러가 납니다.** 메시지에 무엇이 잘못됐고 무엇을 기대했는지까지 적혀 있습니다. 이 영역에서 몇 안 되는 친절한 부분이니 안심하고 개발하세요. 다만 이 에러는 **런타임**에 납니다 — `resume` 값이 `any` 로 흘러 들어가면 tsc 가 못 잡습니다. `HITLResponse` 타입을 `langchain` 에서 import 해 명시적으로 붙여 두면 컴파일 타임에 걸립니다.
>
> 그리고 **결정의 개수는 대기 중인 도구 호출 수와 정확히 같아야 합니다.** 모델이 도구를 병렬로 3개 부르면 `decisions` 도 3개여야 하고, `actionRequests` 와 **같은 순서**로 짝지어집니다.

---

## 13-5. 승인 패턴 — approve / edit / reject

여기서 문서와 실제가 갈립니다. 먼저 확인된 사실부터.

**`langchain@1.5.3` 이 지원하는 결정은 세 가지입니다: `approve` / `edit` / `reject`.**

```ts
// node_modules/langchain/dist/agents/middleware/hitl.d.ts 에서 확인
declare const DecisionType: z.ZodEnum<["approve", "edit", "reject"]>;

interface ApproveDecision { type: "approve"; }
interface EditDecision { type: "edit"; editedAction: Action; }   // Action = { name, args }
interface RejectDecision { type: "reject"; message?: string; }
type Decision = ApproveDecision | EditDecision | RejectDecision;
```

| 결정 | 형식 | 동작 |
|---|---|---|
| `approve` | `{ type: "approve" }` | 모델이 요청한 인자 그대로 도구를 실행 |
| `edit` | `{ type: "edit", editedAction: { name, args } }` | 인자(또는 도구 이름)를 바꿔서 실행 |
| `reject` | `{ type: "reject", message?: string }` | 실행하지 않고, `message` 를 `ToolMessage` 로 모델에 전달 |

> ⚠️ **함정 (`respond` 는 없다 / `accept` 도 아니다)**: 공식 문서 [Human-in-the-loop](https://docs.langchain.com/oss/javascript/langchain/human-in-the-loop) 페이지에는 **`respond`** 라는 네 번째 결정이 "사람의 답을 가짜 도구 결과로 돌려준다" 는 설명과 함께 실려 있습니다. 하지만 이 코스가 검증한 `langchain@1.5.3` 에는 **없습니다.** 보내면 이렇게 됩니다.
> ```
> Error: Unexpected human decision: {"type":"respond","message":"hi"}.
> Decision type 'respond' is not allowed for tool 'send_email'.
> Expected one of ["approve","reject"]
> ```
> 문서가 다른 버전(또는 Python 쪽)을 앞서 반영한 것으로 보입니다. **문서에서 본 API 가 안 되면 설치된 버전의 `.d.ts` 를 직접 여세요** — 이 코스에서 가장 확실한 진실의 출처는 `node_modules/langchain/dist/agents/middleware/hitl.d.ts` 입니다.
>
> 이름도 조심하세요. 승인은 `"approve"` 입니다. `"accept"` 가 아닙니다. 다행히 둘 다 조용히 실패하지 않고 에러를 냅니다.
>
> `respond` 로 하려던 것("도구를 실행하지 말고 사람이 대신 답해 주기")은 `reject` 의 `message` 로 거의 같게 됩니다 — `message` 가 그대로 `ToolMessage` 내용이 되기 때문입니다.

### edit — 사람이 인자를 고쳐서 실행

모델이 잘못된 수신자를 골랐을 때, 거부하고 다시 시키는 대신 **사람이 직접 고쳐서** 통과시킬 수 있습니다.

```ts
const editAction = (editPaused.__interrupt__?.[0]?.value as HITLValue).actionRequests[0];
console.log("모델이 요청한 인자:", JSON.stringify(editAction?.args));

const edited = await editAgent.invoke(
  new Command({
    resume: {
      decisions: [{
        type: "edit",
        editedAction: {
          name: "send_email",
          args: {
            to: "correct@example.com",                  // ← 사람이 고친 값
            subject: editAction?.args.subject ?? "초안", // ← 나머지는 원래 값 그대로
            body: editAction?.args.body ?? "본문",
          },
        },
      }],
    },
  }),
  editConfig,
);
```

> ⚠️ **함정 (`editedAction` 은 통째로 교체다)**: `editedAction.args` 는 원래 인자와 **병합되지 않습니다.** 준 값으로 통째로 갈아치웁니다. `{ to: "correct@example.com" }` 만 주면 `subject` 와 `body` 가 사라진 채 도구가 호출됩니다. 도구의 zod 스키마에서 필수 필드라면 검증 에러가 나겠지만, `.optional()` 이었다면 **조용히 빈 메일이 나갑니다.**
>
> 인자가 많으면 스프레드로 원본을 펼치고 바꿀 것만 덮어쓰세요. 안전하고 짧습니다.
> ```ts
> editedAction: { name: action.name, args: { ...action.args, to: newTo } }
> ```
> `name` 도 바꿀 수 있다는 점에 유의하세요 — 사람이 `delete_file` 을 `move_to_trash` 로 돌려 버리는 것도 가능합니다. 강력한 만큼 검증 없이 UI 에 그대로 노출하면 위험합니다.

### reject — 거부하고 이유를 알려주기

```ts
const rejected = await rejectAgent.invoke(
  new Command({
    resume: {
      decisions: [{
        type: "reject",
        message: "발송을 거부했습니다. 이 메일은 보내면 안 됩니다. 다시 시도하지 마세요.",
      }],
    },
  }),
  rejectConfig,
);
```

`message` 는 그대로 `ToolMessage` 내용이 되어 모델에게 전달됩니다. 도구는 **실행되지 않습니다.**

> 💡 **실무 팁**: `reject` 의 `message` 는 사람이 아니라 **모델에게 하는 말**입니다. 여기가 실무에서 자주 어긋나는 지점입니다. "싫어요" 라고만 쓰면 모델은 이유를 모르니 살짝 바꿔서 **또 시도합니다.** 그러면 승인 요청이 또 뜨고, 무한 루프에 빠집니다([Step 08](../step-08-create-agent/) 의 `recursionLimit` 이 결국 끊어 줍니다).
>
> 그래서 `message` 에는 **왜 안 되는지와 다음에 뭘 해야 하는지**를 쓰세요. "다시 시도하지 마세요" 나 "사용자에게 먼저 확인을 요청하세요" 같은 지시를 명시적으로 넣는 게 효과적입니다. `message` 를 생략할 수도 있지만(`message?`), 그러면 모델은 거부됐다는 사실만 알고 이유를 모릅니다.

---

## 13-6. `__interrupt__` 읽기 — 무엇을 승인해달라는지 보여주기

멈춘 건 알겠는데, 사람에게 **뭘 보여줘야** 할까요? `__interrupt__` 안에 다 들어 있습니다.

```ts
const step1 = await emailAgent.invoke({ messages: [/* ... */] }, agentConfig);
console.log(JSON.stringify(step1.__interrupt__, null, 2));
```

**출력** (구조는 결정적입니다. `args` 의 내용은 모델이 만들어서 매번 다릅니다)
```json
[
  {
    "id": "0883de282d3d66524c16842f3d831d06",
    "value": {
      "actionRequests": [
        {
          "name": "send_email",
          "args": {
            "to": "minsu@example.com",
            "subject": "내일 회의 확인",
            "body": "안녕하세요, 내일 회의 참석 확인 부탁드립니다."
          },
          "description": "🚨 메일이 실제로 발송됩니다. 수신자와 내용을 확인하세요."
        }
      ],
      "reviewConfigs": [
        {
          "actionName": "send_email",
          "allowedDecisions": ["approve", "edit", "reject"]
        }
      ]
    }
  }
]
```

구조를 표로 정리하면 이렇습니다.

| 경로 | 타입 | 내용 |
|---|---|---|
| `__interrupt__` | `Interrupt[]` | 중단 목록 |
| `__interrupt__[i].id` | `string` | 중단 식별자 |
| `__interrupt__[i].value` | `HITLRequest` | HITL 미들웨어가 넣은 페이로드 |
| `.value.actionRequests` | `ActionRequest[]` | 승인받을 도구 호출들 |
| `.value.actionRequests[j].name` | `string` | 도구 이름 |
| `.value.actionRequests[j].args` | `Record<string, any>` | **모델이 만든 인자** |
| `.value.actionRequests[j].description` | `string?` | 설명 (`description` 또는 프리픽스로 생성됨) |
| `.value.reviewConfigs` | `ReviewConfig[]` | 도구별 허용 정책 |
| `.value.reviewConfigs[k].actionName` | `string` | 대상 도구 이름 |
| `.value.reviewConfigs[k].allowedDecisions` | `DecisionType[]` | 이 도구에 허용된 결정 |

> ⚠️ **함정 (`args` 이지 `arguments` 가 아니다)**: 공식 문서 페이지의 인터럽트 페이로드 설명에는 필드가 **`arguments`** 로 적혀 있습니다. 실제 타입과 실제 런타임 값은 **`args`** 입니다.
> ```ts
> interface ActionRequest {
>   name: string;
>   args: Record<string, any>;   // ← arguments 아님
>   description?: string;
> }
> ```
> `action.arguments` 로 읽으면 `undefined` 가 나오고, 승인 UI 에 인자가 **빈칸으로** 표시됩니다. 에러는 안 납니다. 사람은 뭘 승인하는지 모르는 채 y 를 누르게 됩니다. 조용한 실패의 교과서 같은 사례입니다.

읽는 코드는 이렇게 씁니다.

```ts
import { isInterrupted } from "@langchain/langgraph";

type ActionRequest = { name: string; args: Record<string, unknown>; description?: string };
type ReviewConfig = { actionName: string; allowedDecisions: string[] };
type HITLValue = { actionRequests: ActionRequest[]; reviewConfigs: ReviewConfig[] };

function describeInterrupt(result: unknown): void {
  if (!isInterrupted(result)) {
    console.log("중단되지 않았습니다.");
    return;
  }

  for (const item of result.__interrupt__) {
    const value = item.value as HITLValue;
    for (const [i, action] of value.actionRequests.entries()) {
      // 인덱스가 아니라 actionName 으로 짝짓습니다
      const review = value.reviewConfigs.find((r) => r.actionName === action.name);
      console.log(`── 승인 요청 #${i + 1} ──`);
      console.log(`도구      : ${action.name}`);
      console.log(`인자      : ${JSON.stringify(action.args, null, 2)}`);
      console.log(`설명      : ${action.description ?? "(없음)"}`);
      console.log(`가능한 결정: ${review?.allowedDecisions.join(" / ") ?? "?"}`);
    }
  }
}
```

두 가지를 짚습니다.

**`isInterrupted` 를 쓰는 이유.** 이건 타입 가드입니다. `createAgent` 의 결과에는 `__interrupt__` 가 타입에 선언돼 있지만, `StateGraph` 로 직접 만든 그래프의 상태 타입에는 **없습니다.** `paused.__interrupt__` 를 그냥 읽으면 tsc 가 이렇게 막습니다.

```
error TS2339: Property '__interrupt__' does not exist on type 'StateType<...>'
```

`isInterrupted(result)` 를 통과시키면 타입이 좁혀져 읽을 수 있게 됩니다. `"__interrupt__" in result` 같은 수동 검사보다 이쪽이 정확합니다.

**`reviewConfigs` 를 인덱스가 아니라 `actionName` 으로 찾는 이유.** 모델이 같은 도구를 **두 번** 부르면 `actionRequests` 는 2개인데 `reviewConfigs` 는 도구별 정책이라 1개일 수 있습니다. `reviewConfigs[i]` 로 읽으면 두 번째가 `undefined` 가 되어 "가능한 결정" 이 빈칸이 됩니다.

> 💡 **실무 팁**: 실제 서비스에서는 `__interrupt__` 를 그대로 UI 에 던지지 말고 **사람이 읽을 수 있는 요약**으로 바꾸세요. `args` 는 모델이 만든 원시 JSON 이라 그대로 보여주면 승인자가 안 읽습니다. "**minsu@example.com** 에게 제목 '**내일 회의 확인**' 으로 메일을 보냅니다" 처럼 문장으로 렌더링하면 승인 품질이 확 올라갑니다. `description` 을 함수로 주면(`(toolCall, state, runtime) => string`) 이 문장을 미들웨어 안에서 만들 수도 있습니다.

---

## 13-7. 비동기 승인 — 사람이 몇 시간 뒤에 답할 때

지금까지는 `invoke` → 멈춤 → `invoke` 를 몇 밀리초 안에 이어서 했습니다. 하지만 현실의 승인은 **슬랙 알림을 보내고 담당자가 점심 먹고 와서 누르는** 일입니다. 몇 시간이 걸립니다.

여기서 대부분의 사람이 놀라는 사실: **그 사이에 프로세스가 죽어도 됩니다.**

```ts
const asyncConfig = { configurable: { thread_id: "async-1" } };

await asyncAgent.invoke(
  { messages: [{ role: "user", content: "later@example.com 으로 '나중에' 메일 보내줘." }] },
  asyncConfig,
);
console.log("승인 요청을 큐에 넣었습니다.");

// 이 사이에 서버가 재시작돼도, 체크포인터가 영속이라면 재개됩니다.
await new Promise((resolve) => setTimeout(resolve, 3000));

const late = await asyncAgent.invoke(
  new Command({ resume: { decisions: [{ type: "approve" }] } }),
  asyncConfig, // thread_id 만 있으면 재개됩니다
);
```

**왜 죽어도 되나.** 에이전트가 멈추면 그 시점의 상태 — 대화 이력, 어느 도구를 어떤 인자로 부르려 했는지, 어디서 멈췄는지 — 가 **전부 체크포인터에 저장**됩니다. `asyncAgent` 라는 자바스크립트 객체는 아무것도 붙들고 있지 않습니다. 재개에 필요한 것은 **`thread_id` 하나뿐**입니다.

그래서 실전 구조는 이렇게 됩니다.

```
[API 서버 A]  invoke → 멈춤 → thread_id 를 승인 큐에 넣고 응답 종료
                              ↓
                        (체크포인터 = Postgres)
                              ↓
[슬랙 봇]     담당자에게 알림 → 몇 시간 뒤 "승인" 클릭
                              ↓
[API 서버 B]  thread_id 로 invoke(Command({ resume })) → 이어서 실행
```

서버 A 와 서버 B 는 **다른 프로세스, 다른 기계**여도 됩니다. 그 사이 배포가 일어나 A 가 사라져도 상관없습니다. 상태는 코드가 아니라 DB 에 있으니까요.

> ⚠️ **함정 (`MemorySaver` 는 프로세스가 죽으면 다 날아간다)**: 지금까지 쓴 `MemorySaver` 는 이름 그대로 **프로세스 메모리**에만 저장합니다. 서버를 재시작하면 대기 중이던 승인이 **전부 증발**합니다. `thread_id` 로 재개하려 하면 그런 스레드가 없으니, 재개가 아니라 **새 대화**가 시작됩니다. 에러도 안 납니다 — 그냥 처음부터 다시 물어봅니다.
>
> `MemorySaver` 는 학습과 테스트 전용입니다. 비동기 승인을 실제로 하려면 **영속 체크포인터**가 필요합니다.
> ```bash
> npm install @langchain/langgraph-checkpoint-postgres
> # 또는 @langchain/langgraph-checkpoint-sqlite (로컬/단일 프로세스용)
> ```
> ```ts
> import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";
>
> const checkpointer = PostgresSaver.fromConnString(process.env.DATABASE_URL!);
> await checkpointer.setup();   // 최초 1회 테이블 생성
>
> const agent = createAgent({ model, tools, middleware, checkpointer });
> ```
> 이 코스의 실습 프로젝트에는 이 패키지가 설치돼 있지 않아 `practice.ts` 는 `MemorySaver` 로 3초 지연을 흉내 냅니다. 실제 서비스라면 이 자리가 반드시 `PostgresSaver` 여야 합니다. [Step 20 — 프로덕션](../step-20-production/) 에서 다시 다룹니다.

> 💡 **실무 팁**: 승인이 **영영 안 오는** 경우를 반드시 설계하세요. 담당자가 휴가를 갔거나 알림을 놓치면 그 스레드는 DB 에 영원히 남습니다. 실무에서는 (1) 승인 요청에 **만료 시각**을 같이 저장하고, (2) 배치로 만료된 스레드를 찾아 `reject` 로 자동 재개해 정리하고, (3) 대기 중인 승인 수를 **모니터링 지표**로 노출합니다. HITL 을 넣는다는 건 "사람이 병목이 된다" 는 뜻이고, 병목은 관리해야 합니다.

---

## 13-8. 실전 — 승인 게이트가 달린 CLI 이메일 에이전트

배운 걸 전부 합쳐 터미널에서 `y/n/e` 를 받는 에이전트를 만듭니다.

```ts
import * as readline from "node:readline/promises";
import { isInterrupted } from "@langchain/langgraph";

const cliAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail, searchContact],
  systemPrompt: "당신은 메일 비서입니다. 연락처를 찾은 뒤 메일을 발송하세요.",
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        send_email: {
          allowedDecisions: ["approve", "edit", "reject"],
          description: "메일이 실제로 발송됩니다.",
        },
      },
    }),
  ],
  checkpointer: new MemorySaver(),
});

const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
const cliConfig = { configurable: { thread_id: "cli-1" } };

// 첫 입력은 사용자 메시지, 이후부터는 Command 입니다. 타입이 갈리므로 유니온으로 받습니다.
let next: { messages: Array<{ role: string; content: string }> } | Command = {
  messages: [{ role: "user", content: "이지은에게 '점심 같이 먹어요' 라는 제목으로 메일 보내줘." }],
};

// 중단이 여러 번 날 수 있으므로 루프를 돕니다.
while (true) {
  const result = await cliAgent.invoke(next, cliConfig);

  if (!isInterrupted(result)) {
    console.log("\n── 최종 답변 ──");
    printMessages(result.messages.slice(-1));
    break;
  }

  const value = result.__interrupt__[0]?.value as HITLValue;
  const action = value.actionRequests[0];

  console.log("\n┌─ 승인 요청 ─────────────────────────");
  console.log(`│ 도구: ${action?.name}`);
  console.log(`│ 인자: ${JSON.stringify(action?.args)}`);
  console.log("└─────────────────────────────────────");

  const answer = (await rl.question("승인하시겠습니까? [y=승인 / n=거부 / e=수정]: ")).trim().toLowerCase();

  if (answer === "y") {
    next = new Command({ resume: { decisions: [{ type: "approve" }] } });
  } else if (answer === "e") {
    const newTo = await rl.question("새 수신자: ");
    next = new Command({
      resume: {
        decisions: [{
          type: "edit",
          editedAction: {
            name: action?.name ?? "send_email",
            args: { ...action?.args, to: newTo },  // 기존 인자를 펼치고 바꿀 것만 덮어씀
          },
        }],
      },
    });
  } else {
    const reason = await rl.question("거부 이유: ");
    next = new Command({
      resume: { decisions: [{ type: "reject", message: reason || "사용자가 거부했습니다." }] },
    });
  }
}

rl.close();
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
┌─ 승인 요청 ─────────────────────────
│ 도구: send_email
│ 인자: {"to":"jieun@example.com","subject":"점심 같이 먹어요","body":"안녕하세요 지은님, 오늘 점심 같이 하실래요?"}
└─────────────────────────────────────
승인하시겠습니까? [y=승인 / n=거부 / e=수정]: y
   📧 [실제 발송] to=jieun@example.com subject=점심 같이 먹어요 ...

── 최종 답변 ──
AI     │ 이지은님께 '점심 같이 먹어요' 메일을 발송했습니다.
```

설계에서 짚을 것 세 가지입니다.

**`while (true)` 루프가 노드 밖에 있습니다.** 13-2 에서 "루프 안에서 `interrupt()` 를 부르지 마라" 고 했습니다. 여기서 루프는 **호출자** 쪽에 있고, 루프가 도는 건 `invoke()` 입니다. 중단은 매번 새 `invoke` 로 처리되므로 재생 문제가 없습니다. 이게 반복 승인의 올바른 형태입니다.

**`isInterrupted` 로 종료 조건을 판단합니다.** "중단이 아니면 끝" 입니다. 도구를 여러 번 부르면 승인도 여러 번 필요한데, 이 구조는 몇 번이든 자연스럽게 처리합니다.

**`next` 의 타입이 유니온입니다.** 첫 입력만 `{ messages }` 이고 이후는 전부 `Command` 입니다. 이 둘을 한 변수로 받아 `invoke` 에 넘기는 게 재개 루프의 관용적인 형태입니다.

> 💡 **실무 팁**: 실제 서비스에서 CLI 는 프로토타입일 뿐입니다. 하지만 **구조는 그대로**입니다. `rl.question()` 자리에 슬랙 인터랙티브 메시지, 웹 승인 페이지, 이메일 승인 링크가 들어갈 뿐이고, 그 사이 프로세스가 죽어도 되게 만드는 게 13-7 의 영속 체크포인터입니다. CLI 로 흐름을 먼저 검증하고 I/O 만 갈아 끼우는 순서를 권합니다.

---

## 정리

| 개념 | API | 요점 |
|---|---|---|
| 중단 | `interrupt(value)` | 실행을 멈추고 `value` 를 호출자에게 전달. **checkpointer 필수** |
| 중단 확인 | `isInterrupted(result)` | 타입 가드. 통과해야 `__interrupt__` 를 읽을 수 있음 |
| 중단 페이로드 | `result.__interrupt__` | `[{ id, value }]` |
| 재개 | `new Command({ resume })` | `resume` 이 `interrupt()` 의 반환값이 됨. **같은 `thread_id`** |
| 도구 승인 게이트 | `humanInTheLoopMiddleware({ interruptOn })` | 도구별 `true` / `false` / 세부 정책 |
| 조건부 승인 | `interruptOn[tool].when` | `true` 면 중단, `false` 면 자동 승인 |
| HITL 페이로드 | `value.actionRequests` / `value.reviewConfigs` | 인자 필드는 **`args`** |
| HITL 재개 | `{ decisions: [...] }` | 결정 수 == 대기 중인 도구 호출 수 |
| 결정 3종 | `approve` / `edit` / `reject` | `respond` 는 1.5.3 에 **없음** |
| 영속성 | `PostgresSaver` | `MemorySaver` 는 프로세스와 함께 사라짐 |

**핵심 함정 3가지**

1. **`interruptOn` 의 도구 이름 오타 → 조용히 실행된다.** 에러도 경고도 없고, 그 도구는 그냥 승인 없이 실행됩니다. `interruptOn` 에 없는 도구는 자동 승인이 기본이기 때문입니다. 도구 이름을 상수로 뽑아 `tool()` 과 `interruptOn` 양쪽에서 같은 상수를 쓰고, 승인 게이트가 실제로 걸리는지 **테스트로** 확인하세요.
2. **`interrupt()` 이전의 코드는 재개할 때 다시 실행된다.** 재개는 "이어서" 가 아니라 "노드를 처음부터 다시" 입니다. 그 앞에 결제·발송이 있으면 두 번 나갑니다. `interrupt()` 를 부수효과보다 앞에 두거나, 멱등하게 만들거나, 노드를 분리하세요.
3. **`try/catch` 가 중단을 삼킨다.** `interrupt()` 는 `GraphInterrupt` 예외를 던져 동작합니다. 넓은 `try/catch` 로 감싸면 중단이 그래프에 도달하지 못하고 `__interrupt__` 없이 노드가 끝나 버립니다. `interrupt()` 는 `try` 밖에 두거나, `error.name === "GraphInterrupt"` 면 다시 던지세요.

**버전 특이사항**: `langchain@1.5.3` 의 결정은 `approve` / `edit` / `reject` 3종입니다. 문서에 나오는 `respond` 는 이 버전에 없습니다. 인자 필드는 문서의 `arguments` 가 아니라 `args` 입니다. **문서와 설치된 버전이 다르면 `.d.ts` 가 진실입니다.**

---

## 연습문제

1. `interrupt()` 를 쓰면서 checkpointer 를 뺀 그래프를 실행해, 에러 클래스 이름과 메시지 첫 줄을 확인하세요. 그다음 checkpointer 를 붙여 정상 동작하게 고치세요. (`thread_id` 를 빼면 어떻게 되는지도 확인해 보세요)
2. `interrupt()` 앞에 카운터를 올리는 노드를 만들어, 중단 후 재개했을 때 카운터가 몇이 되는지 확인하세요. 그리고 재개해도 부수효과가 한 번만 일어나도록 고치세요. (힌트: 세 가지 길이 있습니다)
3. `interrupt()` 를 `try/catch` 로 감싼 노드를 실행해 `__interrupt__` 가 사라지는 것을 확인하고, 잡힌 예외의 `name` 을 출력하세요. 그다음 "DB 에러는 잡되 interrupt 는 통과시키는" 형태로 고치세요.
4. `delete_file`(승인 필요, `approve`/`reject` 만) 과 `list_files`(승인 불필요) 를 가진 에이전트를 만들고, "a.txt 지워줘" 로 중단이 걸리는지 확인하세요. `description` 은 `"🚨 파일이 영구 삭제됩니다"` 로 하세요.
5. 문제 4 의 결과에서 `__interrupt__` 를 읽어 "도구이름 / 인자 / 허용된 결정" 세 줄로 출력하는 `renderApproval(result)` 를 만드세요. `isInterrupted` 타입 가드를 쓰고, `reviewConfigs` 는 `actionName` 으로 짝지으세요.
6. 같은 요청을 서로 다른 `thread_id` 로 세 번 실행해 각각 `approve` / `edit` / `reject` 로 재개하고 결과를 비교하세요. `edit` 로는 삭제 대상을 `b.txt` 로 바꾸고, 마지막에 삭제 로그를 출력해 실제로 무엇이 지워졌는지 검증하세요.
7. `interruptOn` 의 키를 `"deleteFile"`(잘못된 이름) 로 준 에이전트에 "a.txt 지워줘" 를 시키세요. 중단이 걸리나요? 삭제 로그에 무엇이 남았나요? 이 사고를 **컴파일 타임**에 막도록 도구 이름을 상수로 뽑아 고치세요.
8. `when` 을 써서 "경로가 `/etc` 로 시작하면 승인 요구, 그 외는 자동 통과" 하는 에이전트를 만드세요. `"a.txt 지워줘"` 와 `"/etc/passwd 지워줘"` 로 동작 차이를 확인하세요. (인자를 방어적으로 읽는 것을 잊지 마세요)

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 14 — 컨텍스트와 런타임](../step-14-context-runtime/)

승인 게이트를 세웠으니 이제 "누가 승인하는가" 를 알아야 합니다. `when` 술어와 `description` 함수가 받는 `runtime` 객체가 바로 그 통로입니다. Step 14 에서는 요청마다 달라지는 컨텍스트(사용자 ID, 권한, 테넌트)를 에이전트 안으로 흘려보내는 방법을 다룹니다. "관리자는 자동 승인, 일반 사용자는 승인 필요" 같은 정책이 그때 완성됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(13-1 ~ 13-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

실행은 실습 프로젝트에서 합니다.

```bash
npx tsx docs/reference/langchain/step-13-hitl/practice.ts
```

`[13-2]` 계열 절은 모델을 쓰지 않는 순수 그래프 예제라 **API 키 없이도 실행됩니다.** `[13-3]` 부터는 `ANTHROPIC_API_KEY` 가 필요합니다(`project/.env`). OpenAI 를 쓰려면 각 `createAgent` 의 `model` 을 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 넣으면 그대로 동작합니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[13-1] ~ [13-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[13-2]` 는 이 파일의 심장입니다. `sideEffectCount` 를 노드 안에서 올리고 마지막에 출력하는데, **2가 찍힙니다.** 재개가 "이어서" 가 아니라 "처음부터 다시" 라는 것을 숫자 하나로 증명하는 블록입니다. `[13-2b]`(checkpointer 없음 → `GraphValueError`), `[13-2c]`(try/catch 로 삼킴), `[13-2d]`(interrupt 2개의 id 가 같음)가 나란히 이어지며, 넷 다 모델 없이 돌아서 결과가 결정적입니다.
- `[13-3b]` 는 일부러 사고를 내는 블록입니다. `interruptOn: { sendEmail: true }` 로 이름을 틀리게 준 뒤 `sentLog.length` 의 변화를 출력합니다. 중단은 `false` 이고 메일은 1건 나갑니다. **이 블록만은 출력에 놀라는 게 정상**이니 그냥 지나치지 마세요.
- `[13-4b]` 는 잘못된 `resume` 을 세 가지 방식으로 보내 각각 어떤 에러가 나는지 보여줍니다. `resume: true`(그래프에서는 맞지만 미들웨어에서는 틀림) → `decisions must be a non-empty array`, `type: "accept"` → `Decision type 'accept' is not allowed`. 에러 메시지를 눈에 익혀 두면 나중에 디버깅이 빨라집니다.
- `[13-6]` 의 `describeInterrupt()` 는 `[13-8]` 의 CLI 에서도 같은 구조로 재사용됩니다. `isInterrupted` 타입 가드를 통과해야 `__interrupt__` 를 읽을 수 있다는 점, `reviewConfigs` 를 `actionName` 으로 찾는다는 점 두 가지가 이 함수의 존재 이유입니다.
- `[13-8]` 은 **터미널 입력을 기다립니다.** 파일을 통째로 실행하면 앞 절 출력이 다 지나간 뒤 `승인하시겠습니까? [y/n/e]:` 프롬프트가 뜹니다. `e` 를 고르면 새 수신자를 한 번 더 물어봅니다. 입력하기 전까지 프로세스가 안 끝나니, 자동 실행 환경에서는 이 블록을 잘라내고 쓰세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래가 비어 있으니, 거기에 직접 코드를 써 넣고 파일을 실행해 검증하면 됩니다.

- 문제 1~3 은 모델을 쓰지 않으므로 **API 키 없이 풀 수 있습니다.** 이 스텝의 함정 3종(checkpointer / 부수효과 재실행 / try-catch)이 전부 여기 몰려 있으니, 키가 없더라도 이 셋은 반드시 손으로 돌려 보세요.
- `[문제 1]` 과 `[문제 2]`, `[문제 3]` 은 **고장난 그래프가 이미 작성되어 있습니다.** 여러분이 할 일은 코드를 처음부터 쓰는 게 아니라, 실행해서 증상을 관찰하고 → 원인을 주석으로 적고 → 고치는 것입니다. 특히 문제 1 은 파일 상단에 `.compile(/* 여기를 채우세요 */)` 로 비워 두었습니다.
- `deleteLog` 배열이 파일 상단에 있습니다. 문제 6 과 문제 7 의 채점 기준이 바로 이 배열입니다 — "중단됐다" 는 말보다 "실제로 뭐가 지워졌나" 가 정확한 검증입니다. 문제 7 에서 여기에 `a.txt` 가 남으면 사고를 재현하는 데 성공한 겁니다.
- `[문제 8]` 의 힌트에 `when` 이라는 이름만 주고 시그니처는 주지 않았습니다. `node_modules/langchain/dist/agents/middleware/hitl.d.ts` 에서 `WhenPredicate` 를 찾아보는 연습을 겸하는 문제입니다. 이 스텝의 교훈("문서보다 `.d.ts`")을 직접 해 보는 자리입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 "왜 그런가" 와 기대 출력이 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 2]` 는 세 가지 해결책 중 **(b) interrupt 를 앞으로 옮기기**를 택했습니다. 고장난 버전(`counter = 2`)과 고친 버전(`counter = 1`)을 나란히 실행해 숫자로 비교합니다. 나머지 두 길(멱등성, 노드 분리)이 각각 언제 더 나은지는 주석에 적어 두었으니 함께 읽으세요. 실무에서는 결제 API 의 idempotency key, 즉 (a) 가 정답인 경우가 많습니다.
- `[정답 3]` 의 고친 버전이 이 파일에서 가장 실무적인 코드입니다. `if (error instanceof Error && error.name === "GraphInterrupt") throw error;` 한 줄로 "중단은 통과, 진짜 에러는 처리" 를 가릅니다. 방어적 `try/catch` 를 두르고 싶은 노드에 그대로 복사해 쓸 수 있는 패턴입니다.
- `[정답 6]` 은 `deleteLog` 를 세 번 출력해 approve → `["a.txt"]`, edit → `["a.txt","b.txt"]`, reject → 변화 없음 을 보여줍니다. `editedAction` 에서 `{ ...q6bAction?.args, path: "b.txt" }` 로 **스프레드를 쓴 이유**가 주석에 있습니다 — 통째로 교체되기 때문입니다.
- `[정답 7]` 이 이 파일의 하이라이트입니다. 오타 버전으로 사고를 재현한 뒤(`deleteLog = ["a.txt"]`), `TOOL_NAMES` 상수와 계산된 키 `{ [TOOL_NAMES.deleteFile]: true }` 로 방어 버전을 만들어 중단이 걸리는 것(`deleteLog = []`)까지 확인합니다. 런타임 사고를 컴파일 타임 에러로 옮기는 방법입니다.
- `[정답 8]` 의 `when` 술어는 `typeof path !== "string"` 이면 `true`(중단)를 반환합니다. **모르면 막는다** — 판단이 애매할 때 안전한 쪽으로 기우는 fail-safe 설계입니다. 인자는 모델이 만들어 낸 값이라 언제든 예상과 다를 수 있다는 전제에서 나온 코드입니다.

```ts file="./solution.ts"
```
