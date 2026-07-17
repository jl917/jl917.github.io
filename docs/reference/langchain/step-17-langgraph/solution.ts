/**
 * Step 17 — LangGraph 그래프 API — 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-17-langgraph/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";
import {
  StateGraph,
  StateSchema,
  MessagesValue,
  ReducedValue,
  Command,
  Send,
  GraphRecursionError,
  START,
  END,
  type GraphNode,
  type ConditionalEdgeRouter,
} from "@langchain/langgraph";
import { AIMessage, HumanMessage } from "@langchain/core/messages";
import * as z from "zod";

const line = (t: string) => console.log(`\n${"=".repeat(60)}\n${t}\n${"=".repeat(60)}`);

/* ===== [정답 1] 순차 그래프 =====
 * 핵심: 노드는 "상태 전체" 가 아니라 "바뀐 부분만" 담은 객체를 반환합니다.
 * { text: ... } 만 돌려주면 나머지 키는 알아서 유지됩니다.
 * 그리고 state 를 직접 고치면(state.text = ...) 반영되지 않습니다 — 반드시 return.
 */
line("[정답 1] 순차 그래프");

const TextState = new StateSchema({ text: z.string().default("") });

const trim: GraphNode<typeof TextState> = (state) => ({ text: state.text.trim() });
const upper: GraphNode<typeof TextState> = (state) => ({ text: state.text.toUpperCase() });
const exclaim: GraphNode<typeof TextState> = (state) => ({ text: `${state.text}!` });

const textGraph = new StateGraph(TextState)
  .addNode("trim", trim)
  .addNode("upper", upper)
  .addNode("exclaim", exclaim)
  .addEdge(START, "trim")
  .addEdge("trim", "upper")
  .addEdge("upper", "exclaim")
  .addEdge("exclaim", END)
  .compile();

console.log(await textGraph.invoke({ text: "  hello graph  " }));
// → { text: 'HELLO GRAPH!' }

/* ===== [정답 2] 커스텀 리듀서 =====
 * 리듀서 시그니처는 (현재값, 이번 업데이트) => 새 값 입니다.
 * Math.max 를 리듀서로 쓰면 "쓰는 순서와 무관하게" 최댓값이 남습니다.
 * 이게 리듀서의 본질입니다 — 병렬 브랜치의 순서가 보장되지 않아도
 * 결과가 결정적이려면 리듀서가 교환법칙을 만족해야 합니다.
 * (concat 은 순서에 의존하지만 max 는 그렇지 않습니다.)
 */
line("[정답 2] 커스텀 리듀서");

const MaxState = new StateSchema({
  maxScore: new ReducedValue(z.number().default(0), {
    reducer: (current: number, update: number) => Math.max(current, update),
  }),
});

const maxGraph = new StateGraph(MaxState)
  .addNode("scoreA", () => ({ maxScore: 30 }))
  .addNode("scoreB", () => ({ maxScore: 90 }))
  .addNode("scoreC", () => ({ maxScore: 50 }))
  .addEdge(START, "scoreA")
  .addEdge(START, "scoreB")
  .addEdge(START, "scoreC")
  .addEdge("scoreA", END)
  .addEdge("scoreB", END)
  .addEdge("scoreC", END)
  .compile();

console.log(await maxGraph.invoke({}));
// → { maxScore: 90 } — 리듀서가 없었다면 InvalidUpdateError 로 터졌을 그래프입니다.

/* ===== [정답 3] 조건부 분기 =====
 * 함정: 라우터 함수는 "노드 이름" 을 반환할 뿐 상태를 바꾸지 못합니다.
 * 판단 결과(tier)를 상태에 남기고 싶으면 classify "노드" 가 따로 있어야 합니다.
 * 세 번째 인자 ["vipNode", "normalNode"] 는 타입 안전성 + 그림 정확도를 줍니다.
 */
line("[정답 3] 조건부 분기");

const TierState = new StateSchema({
  amount: z.number().default(0),
  tier: z.string().default(""),
  message: z.string().default(""),
});

const classify: GraphNode<typeof TierState> = (state) => ({
  tier: state.amount >= 10000 ? "vip" : "normal",
});

const routeTier: ConditionalEdgeRouter<{
  InputSchema: typeof TierState;
  Nodes: "vipNode" | "normalNode";
}> = (state) => (state.tier === "vip" ? "vipNode" : "normalNode");

const tierGraph = new StateGraph(TierState)
  .addNode("classify", classify)
  .addNode("vipNode", () => ({ message: "VIP 혜택 적용" }))
  .addNode("normalNode", () => ({ message: "일반 처리" }))
  .addEdge(START, "classify")
  .addConditionalEdges("classify", routeTier, ["vipNode", "normalNode"])
  .addEdge("vipNode", END)
  .addEdge("normalNode", END)
  .compile();

console.log(await tierGraph.invoke({ amount: 50000 }));
console.log(await tierGraph.invoke({ amount: 500 }));

/* ===== [정답 4] 깨진 병렬 그래프 고치기 =====
 * 원인: logs 에 리듀서가 없으면 LastValue 채널이 됩니다.
 * LastValue 는 한 스텝에 값을 하나만 받을 수 있는데, fetchUser 와 fetchOrders 가
 * "같은 스텝에" 동시에 쓰므로 InvalidUpdateError 가 납니다.
 * 이건 "마지막 것이 이긴다" 로 조용히 넘어가지 않고 명시적으로 터집니다 — 다행입니다.
 * 해결: 두 값을 어떻게 합칠지(concat) 리듀서로 알려주면 됩니다.
 */
line("[정답 4] 깨진 병렬 그래프 고치기");

const FixedState = new StateSchema({
  logs: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (current: string[], update: string[]) => current.concat(update),
  }),
});

const fixedGraph = new StateGraph(FixedState)
  .addNode("fetchUser", () => ({ logs: ["유저 조회"] }))
  .addNode("fetchOrders", () => ({ logs: ["주문 조회"] }))
  .addEdge(START, "fetchUser")
  .addEdge(START, "fetchOrders")
  .addEdge("fetchUser", END)
  .addEdge("fetchOrders", END)
  .compile();

console.log(await fixedGraph.invoke({}));
// → 두 값이 모두 남습니다. 다만 순서는 보장되지 않습니다 —
// 이 파일을 돌려보면 [ '주문 조회', '유저 조회' ] 처럼 선언 순서와 뒤집혀 나오기도 합니다.
// 순서가 중요하면 병렬로 만들지 말고 순차 엣지로 잇거나, 정렬 가능한 값을 함께 넣으세요.

/* ===== [정답 5] Command =====
 * Command 는 update(상태 갱신) 와 goto(다음 노드) 를 한 번에 반환합니다.
 * addConditionalEdges 없이 노드 스스로 다음 행선지를 정하므로 라우팅 로직이
 * 판단에 필요한 데이터 바로 옆에 있게 됩니다.
 * 함정: { ends: [...] } 를 안 주면 그래프가 이 노드에서 어디로 갈 수 있는지 몰라
 * 그림이 비고, 도착지가 연결되지 않습니다.
 */
line("[정답 5] Command");

const RetryState = new StateSchema({
  attempts: z.number().default(0),
  status: z.string().default(""),
});

const tryTask: GraphNode<{
  InputSchema: typeof RetryState;
  Nodes: "tryTask" | "giveUp";
}> = (state) => {
  const attempts = state.attempts + 1;
  return new Command({
    update: { attempts },
    goto: attempts < 3 ? "tryTask" : "giveUp", // 자기 자신으로도 갈 수 있습니다
  });
};

const retryGraph = new StateGraph(RetryState)
  .addNode("tryTask", tryTask, { ends: ["tryTask", "giveUp"] })
  .addNode("giveUp", () => ({ status: "포기" }))
  .addEdge(START, "tryTask")
  .addEdge("giveUp", END)
  .compile();

console.log(await retryGraph.invoke({}));
// → { attempts: 3, status: '포기' }

/* ===== [정답 6] Send map-reduce =====
 * Send(노드이름, 그 노드에게만 줄 상태) 는 "런타임에 개수가 정해지는 fan-out" 입니다.
 * 엣지를 미리 N개 그려둘 수 없을 때(단어 수를 실행 전엔 모름) 쓰는 유일한 방법입니다.
 * 함정: Send 로 보낸 상태는 워커 노드에게만 갑니다. 워커가 전체 상태를 볼 거라
 * 기대하면 틀립니다 — 워커용 스키마를 따로 두는 게 정석입니다.
 * 그리고 워커 N개가 동시에 lengths 에 쓰므로 리듀서가 반드시 필요합니다.
 */
line("[정답 6] Send map-reduce");

const WordState = new StateSchema({
  words: z.array(z.string()).default(() => []),
  lengths: new ReducedValue(z.array(z.number()).default(() => []), {
    reducer: (current: number[], update: number[]) => current.concat(update),
  }),
});

const MeasureState = new StateSchema({
  word: z.string().default(""),
  lengths: new ReducedValue(z.array(z.number()).default(() => []), {
    reducer: (current: number[], update: number[]) => current.concat(update),
  }),
});

const measure: GraphNode<typeof MeasureState> = (state) => ({ lengths: [state.word.length] });

// START 에서 곧바로 Send 를 뿌립니다 (조건부 엣지의 출발점이 START 여도 됩니다).
const wordGraph = new StateGraph(WordState)
  .addNode("measure", measure)
  .addConditionalEdges(START, (state) => state.words.map((w) => new Send("measure", { word: w })), [
    "measure",
  ])
  .addEdge("measure", END)
  .compile();

{
  const result = await wordGraph.invoke({ words: ["a", "bb", "ccc"] });
  console.log("lengths (정렬 전, 순서 보장 없음):", result.lengths);
  console.log("lengths (정렬 후):", [...result.lengths].sort((a, b) => a - b));
}

/* ===== [정답 7] 서브그래프 =====
 * 서브그래프는 그냥 컴파일된 그래프이고, 부모의 addNode 에 함수 대신 넣으면 됩니다.
 * 부모와 자식이 "같은 이름의 키" 를 공유하면 상태가 자동으로 오갑니다.
 * 함정: 키 이름이 다르면 아무것도 전달되지 않는데, 에러가 아니라 조용히
 * 빈 값으로 동작합니다. 공유하려는 키 이름은 정확히 일치해야 합니다.
 */
line("[정답 7] 서브그래프");

const SharedState = new StateSchema({
  messages: MessagesValue,
  summary: z.string().default(""),
});

const summarize: GraphNode<typeof SharedState> = (state) => ({
  summary: `메시지 ${state.messages.length}개`,
});

const summarySubgraph = new StateGraph(SharedState)
  .addNode("summarize", summarize)
  .addEdge(START, "summarize")
  .addEdge("summarize", END)
  .compile();

const report: GraphNode<typeof SharedState> = (state) => ({
  messages: [new AIMessage(`요약 결과: ${state.summary}`)],
});

const reportGraph = new StateGraph(SharedState)
  .addNode("child", summarySubgraph) // 컴파일된 그래프가 곧 노드
  .addNode("report", report)
  .addEdge(START, "child")
  .addEdge("child", "report")
  .addEdge("report", END)
  .compile();

{
  const result = await reportGraph.invoke({
    messages: [new HumanMessage("a"), new HumanMessage("b")],
  });
  console.log("summary:", result.summary);
  console.log("마지막 메시지:", result.messages.at(-1)?.content);
}

/* ===== [정답 8] ReAct 루프 =====
 * 이 구조가 createAgent 가 내부에서 하는 일의 전부입니다:
 *   START → model → (도구 호출 있나?) → tools → model → ... → END
 * 실제 createAgent 의 노드 이름은 model_request / tools 이고 모양이 똑같습니다.
 * 함정: 라우터가 END 를 반환할 조건이 없으면 tools ↔ model 을 영원히 돕니다.
 * recursionLimit(기본 25) 이 GraphRecursionError 로 끊어주지만,
 * 그건 안전벨트일 뿐 종료 조건의 대체재가 아닙니다.
 */
line("[정답 8] ReAct 루프");

const MockAgentState = new StateSchema({
  messages: MessagesValue,
  toolCallsLeft: z.number().default(0),
});

const mockModel: GraphNode<typeof MockAgentState> = (state) => {
  if (state.toolCallsLeft > 0) {
    return {
      messages: [new AIMessage("도구를 부릅니다")],
      toolCallsLeft: state.toolCallsLeft - 1,
    };
  }
  return { messages: [new AIMessage("최종 답변")] };
};

const mockTools: GraphNode<typeof MockAgentState> = () => ({
  messages: [new AIMessage("도구 결과")],
});

const shouldContinue: ConditionalEdgeRouter<{
  InputSchema: typeof MockAgentState;
  Nodes: "tools";
}> = (state) => (state.messages.at(-1)?.content === "도구를 부릅니다" ? "tools" : END);

const reactGraph = new StateGraph(MockAgentState)
  .addNode("model", mockModel)
  .addNode("tools", mockTools)
  .addEdge(START, "model")
  .addConditionalEdges("model", shouldContinue, ["tools", END])
  .addEdge("tools", "model")
  .compile();

{
  const result = await reactGraph.invoke({
    messages: [new HumanMessage("시작")],
    toolCallsLeft: 2,
  });
  console.log("정상 종료:");
  console.log(result.messages.map((m) => `  ${m.getType()}: ${m.content}`).join("\n"));
  console.log("\n그래프 모양:");
  console.log((await reactGraph.getGraphAsync()).drawMermaid());
}

// END 를 절대 반환하지 않는 라우터로 바꾸면?
const neverEnds = new StateGraph(MockAgentState)
  .addNode("model", mockModel)
  .addNode("tools", mockTools)
  .addEdge(START, "model")
  .addConditionalEdges("model", () => "tools", ["tools"]) // END 가 없다
  .addEdge("tools", "model")
  .compile();

try {
  await neverEnds.invoke({ messages: [new HumanMessage("시작")], toolCallsLeft: 1 });
} catch (error) {
  console.log("\nEND 없는 라우터:", (error as Error).constructor.name);
  console.log("instanceof GraphRecursionError:", error instanceof GraphRecursionError);
}
