/**
 * Step 17 — LangGraph 그래프 API
 * 실행: npx tsx docs/reference/langchain/step-17-langgraph/practice.ts
 *
 * [17-1] ~ [17-10] 은 API 키 없이 돌아갑니다 (mock 노드로 그래프 역학만 봅니다).
 * [17-11] 만 실제 모델을 호출하므로 ANTHROPIC_API_KEY 가 필요합니다.
 * 키가 없으면 [17-11] 은 자동으로 건너뜁니다.
 */
import "dotenv/config";
import {
  StateGraph,
  StateSchema,
  MessagesValue,
  ReducedValue,
  UntrackedValue,
  Command,
  Send,
  MemorySaver,
  GraphRecursionError,
  START,
  END,
  type GraphNode,
  type ConditionalEdgeRouter,
} from "@langchain/langgraph";
import { ToolNode } from "@langchain/langgraph/prebuilt";
import { AIMessage, HumanMessage } from "@langchain/core/messages";
import { createAgent, tool } from "langchain";
import * as z from "zod";

const line = (t: string) => console.log(`\n${"=".repeat(60)}\n${t}\n${"=".repeat(60)}`);

/* ===== [17-1] LangGraph 는 상태 머신 — 가장 작은 그래프 ===== */
line("[17-1] 가장 작은 그래프");

const HelloState = new StateSchema({
  messages: MessagesValue,
});

// 노드는 "상태를 받아 부분 업데이트 객체를 반환하는 함수" 그 이상이 아닙니다.
const mockLlm: GraphNode<typeof HelloState> = (state) => {
  const last = state.messages.at(-1);
  return { messages: [{ role: "ai", content: `echo: ${last?.content}` }] };
};

const helloGraph = new StateGraph(HelloState)
  .addNode("mock_llm", mockLlm)
  .addEdge(START, "mock_llm")
  .addEdge("mock_llm", END)
  .compile(); // ← compile() 을 빼면 invoke 자체가 존재하지 않습니다.

{
  const result = await helloGraph.invoke({
    messages: [{ role: "user", content: "hi!" }],
  });
  console.log(result.messages.map((m) => `${m.getType()}: ${m.content}`));
}

/* ===== [17-2] 3요소: State / Node / Edge ===== */
line("[17-2] State / Node / Edge");

const CounterState = new StateSchema({
  count: z.number().default(0),
  history: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (a: string[], b: string[]) => a.concat(b),
  }),
});

const increment: GraphNode<typeof CounterState> = (state) => ({
  count: state.count + 1,
  history: [`count 를 ${state.count} → ${state.count + 1} 로 올림`],
});

const double: GraphNode<typeof CounterState> = (state) => ({
  count: state.count * 2,
  history: [`count 를 ${state.count} → ${state.count * 2} 로 두 배`],
});

const counterGraph = new StateGraph(CounterState)
  .addNode("increment", increment)
  .addNode("double", double)
  .addEdge(START, "increment")
  .addEdge("increment", "double")
  .addEdge("double", END)
  .compile();

console.log(await counterGraph.invoke({ count: 5 }));

/* ===== [17-3] 상태 스키마와 리듀서 ===== */
line("[17-3] 리듀서 — 없으면 덮어쓰기, 있으면 합치기");

const ReducerDemo = new StateSchema({
  // 리듀서 없음 → LastValue: 마지막 값이 이긴다
  lastOnly: z.string().default(""),
  // 리듀서 있음 → 누적된다
  accumulated: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (current: string[], update: string[]) => current.concat(update),
  }),
  // 체크포인트에 저장되지 않는 임시 값
  scratch: new UntrackedValue(z.string().default("")),
});

const reducerGraph = new StateGraph(ReducerDemo)
  .addNode("first", () => ({ lastOnly: "첫 번째", accumulated: ["첫 번째"], scratch: "임시1" }))
  .addNode("second", () => ({ lastOnly: "두 번째", accumulated: ["두 번째"], scratch: "임시2" }))
  .addEdge(START, "first")
  .addEdge("first", "second")
  .addEdge("second", END)
  .compile();

console.log(await reducerGraph.invoke({}));
// lastOnly 는 "두 번째" 만 남고, accumulated 는 둘 다 남습니다.

// --- messages 리듀서가 특별한 이유: append + 같은 id 는 교체 ---
const MsgState = new StateSchema({ messages: MessagesValue });
const msgGraph = new StateGraph(MsgState)
  .addNode("add", () => ({ messages: [new AIMessage({ id: "fixed-id", content: "초안" })] }))
  .addNode("revise", () => ({
    messages: [new AIMessage({ id: "fixed-id", content: "교체됨 (id 가 같으므로)" })],
  }))
  .addEdge(START, "add")
  .addEdge("add", "revise")
  .addEdge("revise", END)
  .compile();

{
  const r = await msgGraph.invoke({ messages: [new HumanMessage("안녕")] });
  console.log("메시지 2개만 남습니다 (id 가 같은 것은 교체):");
  console.log(r.messages.map((m) => `  ${m.getType()}(${m.id?.slice(0, 8)}): ${m.content}`).join("\n"));
}

/* ===== [17-4] StateGraph — addNode / addEdge / addConditionalEdges ===== */
/* ===== [17-5] 컴파일과 실행 ===== */
line("[17-5] compile({ checkpointer }) 와 스레드");

const checkpointedGraph = new StateGraph(CounterState)
  .addNode("increment", increment)
  .addEdge(START, "increment")
  .addEdge("increment", END)
  .compile({ checkpointer: new MemorySaver() });

{
  // 체크포인터를 달아 놓고 thread_id 를 안 주면 즉시 에러입니다 (조용히 넘어가지 않습니다).
  try {
    await checkpointedGraph.invoke({});
  } catch (error) {
    console.log("thread_id 없이 호출:", (error as Error).message.split(".")[0]);
  }

  const cfg = { configurable: { thread_id: "thread-A" } };
  const first = await checkpointedGraph.invoke({}, cfg);
  console.log("thread-A 첫 번째 호출 count:", first.count);
  const second = await checkpointedGraph.invoke({}, cfg);
  const third = await checkpointedGraph.invoke({}, cfg);
  console.log("thread-A 두 번째 호출 count:", second.count);
  console.log("thread-A 세 번째 호출 count:", third.count, "← 상태가 이어집니다");
  const other = await checkpointedGraph.invoke({}, { configurable: { thread_id: "thread-B" } });
  console.log("thread-B 첫 호출 count:", other.count, "← 다른 스레드는 처음부터");
}

// stream 으로 노드 단위 중간 결과 보기
console.log("\nstream (노드가 끝날 때마다 update 가 흘러나옵니다):");
for await (const chunk of await counterGraph.stream({ count: 1 }, { streamMode: "updates" })) {
  console.log(" ", JSON.stringify(chunk));
}

/* ===== [17-6] 조건부 분기 — 라우팅 노드 ===== */
line("[17-6] 조건부 분기");

const RouteState = new StateSchema({
  input: z.string().default(""),
  category: z.string().default(""),
  output: z.string().default(""),
});

// 라우팅 "노드" 는 판단 결과를 상태에 적고,
// 라우팅 "함수" 는 그 상태를 읽어 다음 노드 이름만 반환합니다. 둘은 역할이 다릅니다.
const classify: GraphNode<typeof RouteState> = (state) => {
  const category = state.input.includes("환불") ? "refund" : "general";
  return { category };
};

const handleRefund: GraphNode<typeof RouteState> = () => ({ output: "환불 팀으로 연결합니다." });
const handleGeneral: GraphNode<typeof RouteState> = () => ({ output: "일반 상담으로 처리합니다." });

// 타입 인자는 `<Schema, Context, Nodes>` 순서입니다. 노드 이름만 좁히고 싶을 땐
// 가운데 Context 를 건너뛸 수 없으므로, 아래처럼 "타입 백(type bag)" 형태가 편합니다.
const route: ConditionalEdgeRouter<{
  InputSchema: typeof RouteState;
  Nodes: "handleRefund" | "handleGeneral";
}> = (state) => (state.category === "refund" ? "handleRefund" : "handleGeneral");

const routeGraph = new StateGraph(RouteState)
  .addNode("classify", classify)
  .addNode("handleRefund", handleRefund)
  .addNode("handleGeneral", handleGeneral)
  .addEdge(START, "classify")
  // 세 번째 인자로 도착 가능한 노드를 명시하면 그림도 정확해지고 타입도 좁혀집니다.
  .addConditionalEdges("classify", route, ["handleRefund", "handleGeneral"])
  .addEdge("handleRefund", END)
  .addEdge("handleGeneral", END)
  .compile();

console.log(await routeGraph.invoke({ input: "환불 해주세요" }));
console.log(await routeGraph.invoke({ input: "영업시간 알려주세요" }));

/* ===== [17-7] 병렬 실행(fan-out / fan-in)과 리듀서의 필요성 ===== */
line("[17-7] 병렬 실행과 리듀서");

// (A) 리듀서 없는 키에 두 노드가 동시에 쓰면 → InvalidUpdateError
const BadParallel = new StateSchema({ value: z.string().default("") });
const badGraph = new StateGraph(BadParallel)
  .addNode("branchA", () => ({ value: "A" }))
  .addNode("branchB", () => ({ value: "B" }))
  .addEdge(START, "branchA")
  .addEdge(START, "branchB")
  .addEdge("branchA", END)
  .addEdge("branchB", END)
  .compile();

try {
  await badGraph.invoke({});
  console.log("여기는 실행되지 않습니다");
} catch (error) {
  console.log("(A) 리듀서 없이 병렬 쓰기:", (error as Error).constructor.name);
  console.log("   ", (error as Error).message.split("\n")[0]);
}

// (B) 리듀서를 주면 두 브랜치의 결과가 합쳐집니다
const GoodParallel = new StateSchema({
  value: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (a: string[], b: string[]) => a.concat(b),
  }),
});
const goodGraph = new StateGraph(GoodParallel)
  .addNode("branchA", () => ({ value: ["A"] }))
  .addNode("branchB", () => ({ value: ["B"] }))
  .addNode("merge", (state) => {
    console.log("    fan-in 노드는 두 브랜치가 모두 끝난 뒤 한 번만 실행됩니다:", state.value);
    return {};
  })
  .addEdge(START, "branchA")
  .addEdge(START, "branchB")
  .addEdge("branchA", "merge")
  .addEdge("branchB", "merge")
  .addEdge("merge", END)
  .compile();

console.log("(B) 리듀서 있음:", await goodGraph.invoke({}));

// (C) 병렬 브랜치의 "실행 순서" 를 가정하면 틀립니다
const OrderState = new StateSchema({
  log: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (a: string[], b: string[]) => a.concat(b),
  }),
});
const delayed = (name: string, ms: number): GraphNode<typeof OrderState> => async () => {
  await new Promise((r) => setTimeout(r, ms));
  return { log: [name] };
};
const orderGraph = new StateGraph(OrderState)
  .addNode("slow", delayed("slow(50ms)", 50)) // 먼저 선언했지만
  .addNode("fast", delayed("fast(0ms)", 0)) // 이쪽이 먼저 끝납니다
  .addEdge(START, "slow")
  .addEdge(START, "fast")
  .addEdge("slow", END)
  .addEdge("fast", END)
  .compile();

console.log("(C) 선언 순서는 slow → fast 지만 결과는:", (await orderGraph.invoke({})).log);

// (D) Send 로 동적 fan-out (map-reduce)
const MapReduce = new StateSchema({
  subjects: z.array(z.string()).default(() => []),
  results: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (a: string[], b: string[]) => a.concat(b),
  }),
});
const WorkerState = new StateSchema({
  subject: z.string().default(""),
  results: new ReducedValue(z.array(z.string()).default(() => []), {
    reducer: (a: string[], b: string[]) => a.concat(b),
  }),
});
const worker: GraphNode<typeof WorkerState> = (state) => ({
  results: [`${state.subject} 처리 완료`],
});
const mapReduceGraph = new StateGraph(MapReduce)
  .addNode("worker", worker)
  // Send(노드이름, 그 노드에만 줄 상태) — 런타임에 개수가 정해지는 fan-out
  .addConditionalEdges(START, (state) => state.subjects.map((s) => new Send("worker", { subject: s })), [
    "worker",
  ])
  .addEdge("worker", END)
  .compile();

console.log("(D) Send fan-out:", (await mapReduceGraph.invoke({ subjects: ["고양이", "강아지", "햄스터"] })).results);

/* ===== [17-8] Command — 상태 갱신 + 라우팅 동시에 ===== */
line("[17-8] Command");

const CmdState = new StateSchema({ score: z.number().default(0), verdict: z.string().default("") });

// Command 를 반환하는 노드는 goto 대상 이름을 타입으로 좁혀 둘 수 있습니다.
const evaluate: GraphNode<{
  InputSchema: typeof CmdState;
  Nodes: "pass" | "fail";
}> = (state) => {
  const score = state.score + 60;
  return new Command({
    update: { score }, // 상태를 갱신하면서
    goto: score >= 60 ? "pass" : "fail", // 동시에 다음 노드를 정한다
  });
};

const cmdGraph = new StateGraph(CmdState)
  .addNode("evaluate", evaluate, { ends: ["pass", "fail"] }) // ends 를 꼭 알려줘야 그림이 그려집니다
  .addNode("pass", () => ({ verdict: "합격" }))
  .addNode("fail", () => ({ verdict: "불합격" }))
  .addEdge(START, "evaluate")
  .addEdge("pass", END)
  .addEdge("fail", END)
  .compile();

console.log(await cmdGraph.invoke({ score: 10 }));

/* ===== [17-9] 서브그래프 ===== */
line("[17-9] 서브그래프");

const SharedState = new StateSchema({ messages: MessagesValue, note: z.string().default("") });

// 서브그래프도 그냥 컴파일된 그래프입니다 — 부모의 addNode 에 그대로 넣습니다.
const subgraph = new StateGraph(SharedState)
  .addNode("subWork", () => ({
    note: "서브그래프가 씀",
    messages: [new AIMessage("서브그래프에서 처리했습니다")],
  }))
  .addEdge(START, "subWork")
  .addEdge("subWork", END)
  .compile();

const parentGraph = new StateGraph(SharedState)
  .addNode("child", subgraph) // ← 컴파일된 그래프가 곧 노드
  .addNode("after", (state) => ({ note: `${state.note} → 부모가 이어받음` }))
  .addEdge(START, "child")
  .addEdge("child", "after")
  .addEdge("after", END)
  .compile();

{
  const r = await parentGraph.invoke({ messages: [new HumanMessage("시작")] });
  console.log("note:", r.note);
  console.log("messages:", r.messages.map((m) => m.content));
  // 키 이름을 공유하면 부모/자식 상태가 자동으로 이어집니다.
}

/* ===== [17-10] 시각화 ===== */
line("[17-10] 시각화 — drawMermaid()");

console.log("routeGraph 의 mermaid 소스:\n");
console.log((await routeGraph.getGraphAsync()).drawMermaid());
// PNG 로 저장하려면:
//   const image = await (await routeGraph.getGraphAsync()).drawMermaidPng();
//   await fs.writeFile("graph.png", new Uint8Array(await image.arrayBuffer()));

/* ===== [17-11] ReAct 에이전트를 그래프로 직접 구현 → createAgent 와 비교 ===== */
line("[17-11] ReAct 를 손으로 짜기 vs createAgent");

const getWeather = tool(({ city }: { city: string }) => `${city}: 맑음, 24도`, {
  name: "get_weather",
  description: "도시의 현재 날씨를 조회한다",
  schema: z.object({ city: z.string().describe("도시 이름") }),
});

// createAgent 는 "사실 그래프" 입니다. 껍데기를 벗겨 구조를 확인합니다.
const packaged = createAgent({ model: "anthropic:claude-sonnet-4-6", tools: [getWeather] });
console.log("createAgent 가 돌려준 것의 클래스:", packaged.constructor.name);
console.log("createAgent 내부 그래프의 노드:", Object.keys((await (packaged as any).getGraphAsync()).nodes));
console.log("\ncreateAgent 내부 그래프:\n");
console.log((await (packaged as any).getGraphAsync()).drawMermaid());

if (!process.env.ANTHROPIC_API_KEY) {
  console.log("\nANTHROPIC_API_KEY 가 없어 실제 호출은 건너뜁니다.");
  console.log("(위의 구조 출력은 키 없이도 나옵니다 — 그래프 조립에는 모델 호출이 필요 없기 때문입니다.)");
} else {
  const { ChatAnthropic } = await import("@langchain/anthropic");
  const llm = new ChatAnthropic({ model: "claude-sonnet-4-6" });
  // OpenAI 를 쓰려면:
  //   const { ChatOpenAI } = await import("@langchain/openai");
  //   const llm = new ChatOpenAI({ model: "gpt-5.5" });
  const llmWithTools = llm.bindTools([getWeather]);

  const AgentState = new StateSchema({ messages: MessagesValue });

  // 노드 1 — 모델을 부른다
  const callModel: GraphNode<typeof AgentState> = async (state) => {
    const response = await llmWithTools.invoke([
      { role: "system", content: "너는 날씨를 알려주는 비서다." },
      ...state.messages,
    ]);
    return { messages: [response] };
  };

  // 노드 2 — 도구를 실행한다 (ToolMessage 로 tool_call_id 를 맞춰 돌려주는 일을 대신해 줍니다)
  const toolNode = new ToolNode([getWeather]);

  // 엣지 — 도구 호출이 남아 있으면 tools 로, 아니면 END 로
  const shouldContinue: ConditionalEdgeRouter<{
    InputSchema: typeof AgentState;
    Nodes: "tools";
  }> = (state) => {
    const last = state.messages.at(-1) as AIMessage;
    return last?.tool_calls?.length ? "tools" : END; // ← END 를 반환하지 않으면 무한 루프
  };

  const handBuilt = new StateGraph(AgentState)
    .addNode("model_request", callModel)
    .addNode("tools", toolNode)
    .addEdge(START, "model_request")
    .addConditionalEdges("model_request", shouldContinue, ["tools", END])
    .addEdge("tools", "model_request") // 도구 결과를 들고 모델로 되돌아간다 = 루프
    .compile();

  console.log("\n손으로 짠 그래프:\n");
  console.log((await handBuilt.getGraphAsync()).drawMermaid());

  const r = await handBuilt.invoke({ messages: [new HumanMessage("서울 날씨 어때?")] });
  console.log("\n손으로 짠 ReAct 결과 (모델 응답이므로 매번 다릅니다):");
  console.log(r.messages.map((m) => `  ${m.getType()}: ${JSON.stringify(m.content).slice(0, 90)}`).join("\n"));

  const r2 = await packaged.invoke({ messages: [new HumanMessage("서울 날씨 어때?")] });
  console.log("\ncreateAgent 결과 (같은 구조, 같은 흐름):");
  console.log(r2.messages.map((m) => `  ${m.getType()}: ${JSON.stringify(m.content).slice(0, 90)}`).join("\n"));
}

/* ===== [17-12] 무한 루프와 recursionLimit ===== */
line("[17-12] recursionLimit — 그래프의 안전벨트");

const LoopState = new StateSchema({ n: z.number().default(0) });
const loopGraph = new StateGraph(LoopState)
  .addNode("a", (state) => ({ n: state.n + 1 }))
  .addNode("b", (state) => ({ n: state.n + 1 }))
  .addEdge(START, "a")
  .addConditionalEdges("a", () => "b", ["b"]) // END 를 절대 반환하지 않는 라우터
  .addEdge("b", "a")
  .compile();

try {
  await loopGraph.invoke({}, { recursionLimit: 6 });
} catch (error) {
  console.log("END 없는 라우터의 최후:", (error as Error).constructor.name);
  console.log("instanceof GraphRecursionError:", error instanceof GraphRecursionError);
}

console.log("\n끝. 각 절의 [17-x] 주석 번호는 본문 소제목과 1:1 로 대응합니다.");
