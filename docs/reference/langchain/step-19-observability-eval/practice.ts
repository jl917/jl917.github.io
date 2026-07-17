/**
 * Step 19 — 관측·테스트·평가
 * 실행: npx tsx docs/reference/langchain/step-19-observability-eval/practice.ts
 *
 * 이 파일은 [19-1] ~ [19-10] 블록으로 나뉘며, 본문 소제목과 1:1 대응합니다.
 *
 * 중요: 이 파일은 대부분 `fakeModel()` 로 동작하므로 **API 키 없이도 실행됩니다.**
 * 실제 모델을 호출하는 블록은 [19-2] 하나뿐이고, 키가 없으면 스스로 건너뜁니다.
 */
import "dotenv/config";
import {
  createAgent,
  createMiddleware,
  fakeModel,
  tool,
  AIMessage,
  HumanMessage,
  type BaseMessage,
} from "langchain";
import { ConsoleCallbackHandler } from "@langchain/core/tracers/console";
import * as z from "zod";

/* ===== 공통: 이 스텝 내내 쓰는 도구 2개 ===== */

// 도구는 결정적입니다. 같은 입력 → 항상 같은 출력. 그래서 단위 테스트가 됩니다.
const getWeather = tool(
  ({ city }) => {
    const table: Record<string, string> = {
      서울: "맑음, 23도",
      부산: "흐림, 26도",
      제주: "비, 21도",
    };
    const found = table[city];
    if (!found) return `${city}: 날씨 정보 없음`;
    return `${city}: ${found}`;
  },
  {
    name: "get_weather",
    description: "도시의 현재 날씨를 조회합니다. city 는 한국 도시명입니다.",
    schema: z.object({ city: z.string().describe("도시명, 예: 서울") }),
  }
);

const getPopulation = tool(
  ({ city }) => {
    const table: Record<string, number> = { 서울: 9_400_000, 부산: 3_300_000, 제주: 670_000 };
    const found = table[city];
    if (found === undefined) return `${city}: 인구 정보 없음`;
    return `${city}: ${found.toLocaleString("ko-KR")}명`;
  },
  {
    name: "get_population",
    description: "도시의 인구를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시명, 예: 서울") }),
  }
);

const tools = [getWeather, getPopulation];

/* ===== [19-1] 에이전트는 왜 디버깅이 어려운가 ===== */

// invoke() 의 반환값만 보면 "최종 답" 하나입니다.
// 그 사이에 모델이 몇 번 불렸고 어떤 도구를 어떤 인자로 불렀는지는 안 보입니다.
{
  console.log("\n===== [19-1] 최종 답만 보면 아무것도 모른다 =====");

  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "call_1" }])
    .respond(new AIMessage("서울은 맑고 23도입니다."));

  const agent = createAgent({ model, tools });
  const result = await agent.invoke({ messages: [new HumanMessage("서울 날씨 알려줘")] });

  // 흔히 이렇게만 봅니다 — 여기엔 정보가 거의 없습니다.
  console.log("최종 답:", result.messages.at(-1)?.text);

  // 사실은 이만큼 일어났습니다. messages 배열이 곧 "궤적(trajectory)" 입니다.
  console.log("\n실제로 벌어진 일 (messages 배열 전체):");
  for (const m of result.messages) {
    const toolCalls = (m as AIMessage).tool_calls ?? [];
    const suffix = toolCalls.length ? ` → tool_calls: ${toolCalls.map((t) => t.name).join(", ")}` : "";
    console.log(`  [${m.getType()}] ${JSON.stringify(m.text).slice(0, 40)}${suffix}`);
  }

  // 모델이 몇 번 불렸나? fakeModel 은 이것도 세어 줍니다.
  console.log("모델 호출 횟수:", model.callCount); // 2 (도구 호출 결정 1번 + 최종 답 1번)
}

/* ===== [19-2] LangSmith 트레이싱 켜기 ===== */

// 코드 변경 없음. 환경변수 3개만 세팅하면 자동으로 트레이스가 올라갑니다.
//   LANGSMITH_TRACING=true
//   LANGSMITH_API_KEY=lsv2_...
//   LANGSMITH_PROJECT=langchain-course   (선택. 없으면 "default")
{
  console.log("\n===== [19-2] LangSmith 트레이싱 =====");
  console.log("LANGSMITH_TRACING =", process.env.LANGSMITH_TRACING ?? "(미설정)");
  console.log("LANGSMITH_API_KEY =", process.env.LANGSMITH_API_KEY ? "(설정됨)" : "(미설정)");
  console.log("LANGSMITH_PROJECT =", process.env.LANGSMITH_PROJECT ?? "(미설정 → default)");

  // 이 블록만 실제 모델을 씁니다. 키가 없으면 건너뜁니다.
  if (process.env.ANTHROPIC_API_KEY) {
    const agent = createAgent({
      model: "anthropic:claude-sonnet-4-6", // OpenAI 대안: "openai:gpt-5.5"
      tools,
      systemPrompt: "너는 한국 도시 정보 도우미다. 도구를 써서 답하라.",
    });
    const result = await agent.invoke({ messages: [{ role: "user", content: "제주 날씨랑 인구 알려줘" }] });
    console.log("실제 모델 응답:", result.messages.at(-1)?.text);
    console.log("→ LANGSMITH_TRACING=true 였다면 smith.langchain.com 에 트레이스가 올라갔습니다.");
  } else {
    console.log("(ANTHROPIC_API_KEY 없음 → 실제 모델 호출 건너뜀)");
  }
}

/* ===== [19-3] 트레이스에 메타데이터/태그/runName 붙이기 ===== */

// 트레이스는 쌓이면 수만 개가 됩니다. 나중에 찾으려면 미리 라벨을 붙여야 합니다.
// runName / tags / metadata 는 invoke() 의 두 번째 인자(config)에 넣습니다.
{
  console.log("\n===== [19-3] runName / tags / metadata =====");

  const model = fakeModel().respond(new AIMessage("네, 도와드리겠습니다."));
  const agent = createAgent({ model, tools });

  await agent.invoke(
    { messages: [new HumanMessage("안녕")] },
    {
      runName: "greeting-flow", // 트레이스 목록에 표시될 이름
      tags: ["production", "weather-agent", "v1.0"], // 필터용 (완전 일치 검색)
      metadata: {
        userId: "user-123", // 특정 사용자 민원 추적
        sessionId: "sess-456",
        environment: "production",
        promptVersion: "2026-07-17", // 프롬프트 버전 — A/B 비교의 핵심
      },
    }
  );
  console.log("runName='greeting-flow', tags=[production, weather-agent, v1.0] 로 실행됨");

  // ⚠️ 함정: config 의 tags/metadata 는 트레이서로 갑니다.
  // fakeModel 이 기록하는 calls[i].options 에는 들어오지 않습니다.
  console.log("fakeModel.calls[0].options 의 키:", Object.keys(model.calls[0].options ?? {}));
  console.log("→ tags/metadata 가 없습니다. 이걸로는 라벨을 검증할 수 없습니다.");
}

/* ===== [19-4] LangSmith 없이 디버깅 ===== */

// (a) 콘솔 콜백 — 가장 빠른 방법. 모든 체인/모델/도구 시작·종료를 stdout 에 찍습니다.
{
  console.log("\n===== [19-4a] ConsoleCallbackHandler =====");

  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "부산" }, id: "call_1" }])
    .respond(new AIMessage("부산은 흐리고 26도입니다."));

  const agent = createAgent({ model, tools });
  await agent.invoke(
    { messages: [new HumanMessage("부산 날씨?")] },
    { callbacks: [new ConsoleCallbackHandler()] } // ← 이 한 줄
  );
  console.log("(위 [chain/start] ... 로그가 ConsoleCallbackHandler 의 출력입니다)");
}

// (b) streamMode: "debug" — 노드 단위로 무슨 일이 일어나는지 이벤트로 받습니다.
{
  console.log("\n===== [19-4b] streamMode: 'debug' =====");

  const model = fakeModel()
    .respondWithTools([{ name: "get_population", args: { city: "제주" }, id: "call_1" }])
    .respond(new AIMessage("제주 인구는 67만명입니다."));

  const agent = createAgent({ model, tools });

  for await (const ev of await agent.stream(
    { messages: [new HumanMessage("제주 인구?")] },
    { streamMode: "debug" }
  )) {
    // 이벤트 구조는 결정적입니다: { step, type, timestamp, payload }
    //   type: "task"        → 노드 실행 시작 (payload.input 있음)
    //   type: "task_result" → 노드 실행 완료 (payload.result 있음)
    const e = ev as { step: number; type: string; payload: { name?: string } };
    console.log(`  step=${e.step} type=${e.type} node=${e.payload?.name}`);
  }
}

// (c) 커스텀 로깅 미들웨어 — 팀 전체에 일관된 로그를 강제하고 싶을 때.
{
  console.log("\n===== [19-4c] 커스텀 로깅 미들웨어 =====");

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

  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "call_1" }])
    .respond(new AIMessage("서울은 맑고 23도입니다."));

  const agent = createAgent({ model, tools, middleware: [loggingMiddleware] });
  await agent.invoke({ messages: [new HumanMessage("서울 날씨?")] });
}

/* ===== [19-5] 에이전트 테스트 ===== */

// 아주 작은 테스트 러너. 실제로는 vitest 를 씁니다(본문 19-5 참고).
let passed = 0;
let failed = 0;
function check(label: string, cond: boolean) {
  if (cond) {
    passed++;
    console.log(`  ✓ ${label}`);
  } else {
    failed++;
    console.log(`  ✗ ${label}`);
  }
}

// (a) 도구 단위 테스트 — 모델이 전혀 필요 없습니다. 빠르고 결정적입니다.
{
  console.log("\n===== [19-5a] 도구 단위 테스트 (모델 없음) =====");

  check("서울 날씨 조회", (await getWeather.invoke({ city: "서울" })) === "서울: 맑음, 23도");
  check("없는 도시는 '정보 없음'", (await getWeather.invoke({ city: "런던" })) === "런던: 날씨 정보 없음");
  check("인구는 천단위 콤마", (await getPopulation.invoke({ city: "부산" })) === "부산: 3,300,000명");
}

// (b) 에이전트 테스트 — 모델을 fakeModel 로 갈아끼웁니다.
{
  console.log("\n===== [19-5b] fakeModel 로 에이전트 테스트 =====");

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

  // ⚠️ respondWithTools 의 content 는 입력 메시지를 그대로 되돌려줍니다.
  // 즉 "서울 날씨?" 가 AI 메시지 content 로 들어옵니다. 여기에 단언하지 마세요.
  console.log("  (참고) tool_calls 를 담은 AI 메시지의 content =", JSON.stringify(result.messages[1].text));
}

// (c) fakeModel 로 에러 경로 테스트 — 실제 모델로는 재현이 거의 불가능한 상황.
{
  console.log("\n===== [19-5c] 에러 경로 테스트 =====");

  const failing = fakeModel().alwaysThrow(new Error("service unavailable"));
  const agent = createAgent({ model: failing, tools });
  let caught = "";
  try {
    await agent.invoke({ messages: [new HumanMessage("서울 날씨?")] });
  } catch (e) {
    caught = (e as Error).message;
  }
  check("모델 장애가 위로 전파된다", caught === "service unavailable");

  // 큐가 비면 fakeModel 은 던집니다. 메시지는 결정적입니다.
  const oneShot = fakeModel().respond(new AIMessage("한 번만"));
  await oneShot.invoke([new HumanMessage("a")]);
  let queueErr = "";
  try {
    await oneShot.invoke([new HumanMessage("b")]);
  } catch (e) {
    queueErr = (e as Error).message;
  }
  console.log("  큐 소진 에러:", queueErr);
  check("큐 소진 시 명확한 에러", queueErr.startsWith("FakeModel: no response queued"));
}

/* ===== [19-6] 평가 — 데이터셋 + 평가자 ===== */

// 평가의 최소 구성: (1) 데이터셋 (2) 실행 함수 (3) 평가자 (4) 집계
type Example = {
  id: string;
  input: string;
  referenceOutput: string; // 기대 답변
  referenceTools: string[]; // 기대 도구 호출 순서 (19-7 에서 사용)
};

const dataset: Example[] = [
  { id: "ex-1", input: "서울 날씨 알려줘", referenceOutput: "맑음, 23도", referenceTools: ["get_weather"] },
  { id: "ex-2", input: "부산 인구는?", referenceOutput: "3,300,000명", referenceTools: ["get_population"] },
  {
    id: "ex-3",
    input: "제주 날씨랑 인구 둘 다",
    referenceOutput: "비, 21도 / 670,000명",
    referenceTools: ["get_weather", "get_population"],
  },
];

// 평가자 (1): 결정적 매칭 — 싸고 빠르고 안 틀립니다. 쓸 수 있으면 이걸 쓰세요.
function containsEvaluator(outputs: { answer: string }, referenceOutput: string) {
  const keys = referenceOutput.split(" / ");
  const hit = keys.filter((k) => outputs.answer.includes(k)).length;
  return { key: "contains", score: hit / keys.length };
}

// 평가자 (2): LLM-as-judge — 여기서는 실제 모델 대신 fakeModel 로 시늉만 냅니다.
// 이론은 /ai/9990-LLMJudge 참고. 여기서는 "어떻게 실행하는가" 만 봅니다.
async function llmJudgeEvaluator(outputs: { answer: string }, referenceOutput: string) {
  // 실전에서는 아래 model 을 "anthropic:claude-sonnet-4-6" 등으로 바꾸고
  // withStructuredOutput 으로 점수를 강제합니다.
  const judge = fakeModel().structuredResponse({
    score: outputs.answer.includes(referenceOutput.split(" / ")[0]) ? 1 : 0,
    reasoning: "기대 답의 핵심 키워드 포함 여부로 판정",
  });
  // 참고: fakeModel 의 withStructuredOutput 은 { raw, parsed } 형태까지 포함한
  // 유니온 타입을 반환하도록 선언돼 있어서, 여기서는 결과 타입을 명시해 줍니다.
  // 실제 모델(ChatAnthropic 등)에서는 이 단언 없이 바로 좁혀집니다.
  const judged = (await judge
    .withStructuredOutput(z.object({ score: z.number(), reasoning: z.string() }))
    .invoke(`기대: ${referenceOutput}\n실제: ${outputs.answer}\n0~1 로 채점하라.`)) as {
    score: number;
    reasoning: string;
  };
  return { key: "llm_judge", score: judged.score, comment: judged.reasoning };
}

// 스크립트로 시연하려면 각 예제의 응답을 미리 정해야 합니다(fakeModel).
const scripted: Record<string, () => ReturnType<typeof fakeModel>> = {
  "ex-1": () =>
    fakeModel()
      .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "c1" }])
      .respond(new AIMessage("서울은 맑음, 23도 입니다.")),
  "ex-2": () =>
    fakeModel()
      .respondWithTools([{ name: "get_population", args: { city: "부산" }, id: "c1" }])
      .respond(new AIMessage("부산 인구는 3,300,000명 입니다.")),
  // ex-3 은 일부러 "운 좋게 맞은" 케이스로 만듭니다 — 도구를 하나만 부르고도 답은 맞습니다.
  "ex-3": () =>
    fakeModel()
      .respondWithTools([{ name: "get_weather", args: { city: "제주" }, id: "c1" }])
      .respond(new AIMessage("제주는 비, 21도 이고 인구는 670,000명 입니다.")),
};

async function runAgentOn(example: Example) {
  const model = scripted[example.id]();
  const agent = createAgent({ model, tools });
  const result = await agent.invoke({ messages: [new HumanMessage(example.input)] });
  return { answer: result.messages.at(-1)?.text ?? "", messages: result.messages };
}

{
  console.log("\n===== [19-6] 평가 실행 =====");
  for (const ex of dataset) {
    const out = await runAgentOn(ex);
    const c = containsEvaluator(out, ex.referenceOutput);
    const j = await llmJudgeEvaluator(out, ex.referenceOutput);
    console.log(`  ${ex.id}: contains=${c.score.toFixed(2)} llm_judge=${j.score.toFixed(2)}`);
  }
  console.log("  → ex-3 도 최종 답 기준으로는 만점입니다. 다음 절에서 이게 왜 위험한지 봅니다.");
}

/* ===== [19-7] 궤적(trajectory) 평가 ===== */

// 궤적 = 에이전트가 부른 도구의 이름과 순서. messages 배열에서 뽑아냅니다.
function extractTrajectory(messages: BaseMessage[]): string[] {
  return messages
    .filter((m): m is AIMessage => m.getType() === "ai")
    .flatMap((m) => (m.tool_calls ?? []).map((tc) => tc.name));
}

// 궤적 평가자 — agentevals 의 trajectoryMatchMode 를 직접 구현해 본 것입니다.
type MatchMode = "strict" | "unordered" | "superset" | "subset";
function trajectoryMatch(actual: string[], reference: string[], mode: MatchMode): boolean {
  const sortedA = [...actual].sort();
  const sortedR = [...reference].sort();
  switch (mode) {
    case "strict": // 같은 도구를 같은 순서로
      return actual.length === reference.length && actual.every((t, i) => t === reference[i]);
    case "unordered": // 같은 도구 집합, 순서 무관
      return sortedA.length === sortedR.length && sortedA.every((t, i) => t === sortedR[i]);
    case "superset": // 기대한 건 다 불렀고, 추가로 더 불러도 OK
      return reference.every((t) => actual.includes(t));
    case "subset": // 기대 범위를 벗어난 도구는 안 불렀다
      return actual.every((t) => reference.includes(t));
  }
}

{
  console.log("\n===== [19-7] 궤적 평가 =====");
  for (const ex of dataset) {
    const out = await runAgentOn(ex);
    const traj = extractTrajectory(out.messages);
    const strict = trajectoryMatch(traj, ex.referenceTools, "strict");
    const answerOk = containsEvaluator(out, ex.referenceOutput).score === 1;
    console.log(
      `  ${ex.id}: 궤적=[${traj.join(" → ")}] 기대=[${ex.referenceTools.join(" → ")}] ` +
        `strict=${strict ? "PASS" : "FAIL"} 최종답=${answerOk ? "PASS" : "FAIL"}`
    );
  }
  console.log("  → ex-3: 최종답 PASS / 궤적 FAIL. get_population 을 안 부르고 인구를 '지어냈습니다'.");
  console.log("    최종 답만 봤다면 환각을 만점으로 통과시켰을 겁니다.");
}

/* ===== [19-8] 회귀 방지 — CI 게이트 ===== */

// CI 는 "점수를 보여주는 것" 이 아니라 "기준 미달이면 빌드를 깨는 것" 입니다.
{
  console.log("\n===== [19-8] 회귀 게이트 =====");

  const THRESHOLD = 0.9; // 궤적 정확도 최소치
  let trajPass = 0;
  for (const ex of dataset) {
    const out = await runAgentOn(ex);
    if (trajectoryMatch(extractTrajectory(out.messages), ex.referenceTools, "strict")) trajPass++;
  }
  const score = trajPass / dataset.length;
  console.log(`  궤적 정확도: ${(score * 100).toFixed(0)}% (기준 ${THRESHOLD * 100}%)`);
  if (score < THRESHOLD) {
    console.log(`  → CI 라면 여기서 process.exit(1). 기준 미달이므로 머지 차단.`);
  } else {
    console.log(`  → 통과.`);
  }
}

/* ===== [19-9] LangGraph Studio ===== */

// Studio 는 코드가 아니라 설정입니다. 아래 내용을 project/langgraph.json 에 두고
//   npx @langchain/langgraph-cli dev
// 를 실행하면 됩니다. 본문 19-9 참고.
{
  console.log("\n===== [19-9] Studio =====");
  console.log(
    JSON.stringify(
      {
        dependencies: ["."],
        graphs: { agent: "./src/agent.ts:agent" },
        env: ".env",
      },
      null,
      2
    )
  );
  console.log("→ npx @langchain/langgraph-cli dev  후 https://smith.langchain.com/studio/?baseUrl=http://127.0.0.1:2024");
}

/* ===== [19-10] 종합 ===== */

{
  console.log("\n===== [19-10] 종합 =====");
  console.log(`단위 테스트: ${passed} 통과 / ${failed} 실패`);
  console.log(`
관측·테스트·평가 3층 요약
  1층 관측(observability): 무슨 일이 있었나?      → LangSmith 트레이스 / ConsoleCallbackHandler / streamMode debug
  2층 테스트(test):        코드가 안 깨졌나?      → 도구 단위 테스트 + fakeModel (빠름, 결정적, 무료)
  3층 평가(eval):          품질이 안 떨어졌나?    → 데이터셋 + 궤적 평가자 + LLM judge (느림, 비쌈, CI 게이트)
`);
}
