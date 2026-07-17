/**
 * Step 19 — 관측·테스트·평가 / 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-19-observability-eval/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
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
import * as z from "zod";

/* ===== 공통 도구 ===== */

const getWeather = tool(
  ({ city }) => {
    const table: Record<string, string> = { 서울: "맑음, 23도", 부산: "흐림, 26도", 제주: "비, 21도" };
    const found = table[city];
    if (!found) return `${city}: 날씨 정보 없음`;
    return `${city}: ${found}`;
  },
  {
    name: "get_weather",
    description: "도시의 현재 날씨를 조회합니다.",
    schema: z.object({ city: z.string() }),
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
    schema: z.object({ city: z.string() }),
  }
);

const tools = [getWeather, getPopulation];

/* 간단한 단언 헬퍼 */
function check(label: string, cond: boolean) {
  console.log(`  ${cond ? "✓" : "✗"} ${label}`);
}

/* ===================================================================
 * [정답 1] 도구 단위 테스트
 *
 * 해설: 도구는 순수 함수입니다 — 모델도, 네트워크도, API 키도 필요 없습니다.
 * 에이전트 테스트의 90%는 사실 여기서 끝나야 합니다. 도구가 틀리면
 * 모델이 아무리 똑똑해도 답이 틀리는데, 이건 모델 없이 1ms 만에 잡힙니다.
 * =================================================================== */
console.log("\n===== [정답 1] 도구 단위 테스트 =====");
{
  check("제주 날씨", (await getWeather.invoke({ city: "제주" })) === "제주: 비, 21도");
  check("없는 도시 폴백", (await getWeather.invoke({ city: "런던" })) === "런던: 날씨 정보 없음");
  check("천단위 콤마", (await getPopulation.invoke({ city: "서울" })).includes("9,400,000"));

  // 함정: 도구의 "정보 없음" 경로를 테스트 안 하면, 모델이 그 문자열을 받고
  // 어떻게 반응하는지도 영영 모릅니다. 폴백 경로도 반드시 테스트하세요.
}

/* ===================================================================
 * [정답 2] fakeModel 로 2단계 도구 호출 에이전트 테스트
 *
 * 해설: 핵심은 "도구 호출 1번당 모델 호출이 1번 더 필요하다" 입니다.
 * 도구를 2번 부르는 궤적은 모델 응답이 3개 필요합니다.
 *   모델#1 → get_weather 호출 결정
 *   모델#2 → (도구 결과 보고) get_population 호출 결정
 *   모델#3 → 최종 답
 * 큐에 2개만 넣으면 "FakeModel: no response queued for invocation 2" 로 터집니다.
 * =================================================================== */
console.log("\n===== [정답 2] 2단계 도구 호출 =====");
{
  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "제주" }, id: "c1" }])
    .respondWithTools([{ name: "get_population", args: { city: "제주" }, id: "c2" }])
    .respond(new AIMessage("제주는 비, 21도 이고 인구는 670,000명 입니다."));

  const agent = createAgent({ model, tools });
  const result = await agent.invoke({ messages: [new HumanMessage("제주 날씨랑 인구 알려줘")] });

  check("메시지 6개", result.messages.length === 6);
  check("ToolMessage 2개", result.messages.filter((m) => m.getType() === "tool").length === 2);
  check("모델 3번 호출", model.callCount === 3);

  console.log("  궤적:", result.messages.map((m) => m.getType()).join(" → "));
}

/* ===================================================================
 * [정답 3] 궤적 추출 함수
 *
 * 해설: 궤적은 새로 만드는 게 아니라 messages 배열에 이미 들어 있습니다.
 * AI 메시지의 tool_calls 만 순서대로 모으면 됩니다.
 * 최종 답 AI 메시지에는 tool_calls 가 없으므로 `?? []` 로 방어해야 합니다.
 * =================================================================== */
console.log("\n===== [정답 3] 궤적 추출 =====");

function extractTrajectory(messages: BaseMessage[]): string[] {
  return messages
    .filter((m): m is AIMessage => m.getType() === "ai")
    .flatMap((m) => (m.tool_calls ?? []).map((tc) => tc.name));
}

{
  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "제주" }, id: "c1" }])
    .respondWithTools([{ name: "get_population", args: { city: "제주" }, id: "c2" }])
    .respond(new AIMessage("제주는 비, 21도 이고 인구는 670,000명 입니다."));

  const agent = createAgent({ model, tools });
  const result = await agent.invoke({ messages: [new HumanMessage("제주 날씨랑 인구 알려줘")] });

  const traj = extractTrajectory(result.messages);
  console.log("  궤적:", traj);
  check("['get_weather','get_population']", traj.join(",") === "get_weather,get_population");
}

/* ===================================================================
 * [정답 4] 궤적 평가자 — unordered 모드
 *
 * 해설: 어떤 모드를 쓸지가 곧 "무엇을 요구사항으로 볼 것인가" 입니다.
 *   strict    — 순서까지 계약이다 (예: 권한 조회 후에만 결제)
 *   unordered — 순서는 모델 자유, 필요한 정보는 다 가져와야 한다
 *   superset  — 최소한 이것들은 불러라 (추가 호출 허용)
 *   subset    — 이 범위 밖의 도구는 부르지 마라 (안전 경계)
 * 대부분의 경우 unordered 가 맞습니다. strict 를 남발하면 모델이
 * 똑같이 정답인 다른 순서로 풀었을 때 테스트가 거짓으로 깨집니다.
 * =================================================================== */
console.log("\n===== [정답 4] unordered 궤적 매칭 =====");

type MatchMode = "strict" | "unordered" | "superset" | "subset";
function trajectoryMatch(actual: string[], reference: string[], mode: MatchMode): boolean {
  const sortedA = [...actual].sort();
  const sortedR = [...reference].sort();
  switch (mode) {
    case "strict":
      return actual.length === reference.length && actual.every((t, i) => t === reference[i]);
    case "unordered":
      // 함정: 길이 비교를 빼먹으면 ["get_weather"] 가
      // ["get_weather","get_weather"] 와 같다고 나옵니다.
      return sortedA.length === sortedR.length && sortedA.every((t, i) => t === sortedR[i]);
    case "superset":
      return reference.every((t) => actual.includes(t));
    case "subset":
      return actual.every((t) => reference.includes(t));
  }
}

{
  const actual = ["get_population", "get_weather"];
  const reference = ["get_weather", "get_population"];
  console.log("  strict   :", trajectoryMatch(actual, reference, "strict") ? "PASS" : "FAIL"); // FAIL
  console.log("  unordered:", trajectoryMatch(actual, reference, "unordered") ? "PASS" : "FAIL"); // PASS
  console.log("  → 같은 일을 했는데 strict 는 FAIL 입니다. 순서가 진짜 요구사항일 때만 strict 를 쓰세요.");
}

/* ===================================================================
 * [정답 5] "운 좋게 맞은" 답 잡아내기 — 이 스텝의 핵심
 *
 * 해설: 이게 최종답 평가만 돌리면 안 되는 이유입니다.
 * 에이전트는 get_population 을 부르지 않았습니다. 인구 숫자는 도구에서
 * 온 게 아니라 모델이 **기억에서 지어낸 것**입니다. 이번엔 우연히 맞았지만
 * 데이터가 바뀌면 즉시 틀립니다. 최종답 평가는 이걸 만점으로 통과시킵니다.
 * 궤적 평가만이 "출처 없이 답했다" 를 잡아냅니다.
 * =================================================================== */
console.log("\n===== [정답 5] 환각을 통과시키는 평가 =====");
{
  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "부산" }, id: "c1" }])
    .respond(new AIMessage("부산은 흐림, 26도 이고 인구는 3,300,000명 입니다."));

  const agent = createAgent({ model, tools });
  const result = await agent.invoke({ messages: [new HumanMessage("부산 날씨랑 인구 알려줘")] });
  const answer = result.messages.at(-1)?.text ?? "";

  // (1) 최종답 평가
  const answerOk = answer.includes("흐림, 26도") && answer.includes("3,300,000명");
  console.log(`  최종답 평가: ${answerOk ? "PASS" : "FAIL"} — "${answer}"`);

  // (2) 궤적 평가
  const traj = extractTrajectory(result.messages);
  const trajOk = trajectoryMatch(traj, ["get_weather", "get_population"], "strict");
  console.log(`  궤적 평가  : ${trajOk ? "PASS" : "FAIL"} — 실제 궤적 [${traj.join(", ")}]`);

  console.log(
    "  → 왜 위험한가: 인구 숫자가 get_population 이 아니라 모델의 기억에서 나왔습니다.\n" +
      "    지금은 우연히 맞았지만 출처가 없으므로 데이터가 바뀌면 조용히 틀립니다.\n" +
      "    최종답만 보면 이 환각이 만점으로 통과합니다."
  );
}

/* ===================================================================
 * [정답 6] 도구 호출을 기록하는 미들웨어
 *
 * 해설: wrapToolCall 은 반드시 handler(request) 의 결과를 **반환**해야 합니다.
 * 로그만 찍고 return 을 빼먹으면 도구 결과가 사라져 대화가 조용히 깨집니다.
 * 이 미들웨어는 감사(audit) 로그뿐 아니라 "모델이 부르면 안 될 도구를 불렀는가"
 * 를 잡는 런타임 가드로도 확장할 수 있습니다.
 * =================================================================== */
console.log("\n===== [정답 6] 감사 로그 미들웨어 =====");
{
  const auditLog: Array<{ name: string; args: Record<string, unknown> }> = [];

  const auditMiddleware = createMiddleware({
    name: "AuditMiddleware",
    wrapToolCall: async (request, handler) => {
      auditLog.push({ name: request.toolCall.name, args: request.toolCall.args });
      return handler(request); // ← 이 return 을 빼면 도구 결과가 사라집니다
    },
  });

  const model = fakeModel()
    .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "c1" }])
    .respond(new AIMessage("서울은 맑고 23도입니다."));

  const agent = createAgent({ model, tools, middleware: [auditMiddleware] });
  await agent.invoke({ messages: [new HumanMessage("서울 날씨?")] });

  console.log("  auditLog:", JSON.stringify(auditLog));
  check("도구 1건 기록", auditLog.length === 1 && auditLog[0].name === "get_weather");
}

/* ===================================================================
 * [정답 7] 회귀 게이트
 *
 * 해설: 정확도는 67% (2/3) 이고 ex-3 이 깨집니다.
 * ex-3 은 get_weather 만 부르고 get_population 을 건너뛰었습니다.
 * 최종 답은 그럴듯하지만 궤적이 기대와 다릅니다 — [정답 5] 와 같은 환각입니다.
 *
 * CI 의 요점은 점수를 "보여주는" 게 아니라 기준 미달이면 **빌드를 깨는** 것입니다.
 * 대시보드는 아무도 안 봅니다. 빨간 X 는 봅니다.
 * =================================================================== */
console.log("\n===== [정답 7] 회귀 게이트 =====");

const dataset = [
  { id: "ex-1", input: "서울 날씨?", referenceTools: ["get_weather"] },
  { id: "ex-2", input: "부산 인구?", referenceTools: ["get_population"] },
  { id: "ex-3", input: "제주 날씨랑 인구", referenceTools: ["get_weather", "get_population"] },
];

const scripted: Record<string, () => ReturnType<typeof fakeModel>> = {
  "ex-1": () =>
    fakeModel()
      .respondWithTools([{ name: "get_weather", args: { city: "서울" }, id: "c1" }])
      .respond(new AIMessage("서울은 맑음, 23도 입니다.")),
  "ex-2": () =>
    fakeModel()
      .respondWithTools([{ name: "get_population", args: { city: "부산" }, id: "c1" }])
      .respond(new AIMessage("부산 인구는 3,300,000명 입니다.")),
  "ex-3": () =>
    fakeModel()
      .respondWithTools([{ name: "get_weather", args: { city: "제주" }, id: "c1" }])
      .respond(new AIMessage("제주는 비, 21도 이고 인구는 670,000명 입니다.")),
};

{
  const THRESHOLD = 0.9;
  const failures: string[] = [];

  for (const ex of dataset) {
    const agent = createAgent({ model: scripted[ex.id](), tools });
    const result = await agent.invoke({ messages: [new HumanMessage(ex.input)] });
    const traj = extractTrajectory(result.messages);
    const ok = trajectoryMatch(traj, ex.referenceTools, "unordered");
    if (!ok) failures.push(`${ex.id}: 기대 [${ex.referenceTools.join(", ")}] / 실제 [${traj.join(", ")}]`);
  }

  const score = (dataset.length - failures.length) / dataset.length;
  console.log(`  궤적 정확도: ${(score * 100).toFixed(0)}% (기준 ${THRESHOLD * 100}%)`);
  for (const f of failures) console.log(`    실패 — ${f}`);

  if (score < THRESHOLD) {
    console.log("  REGRESSION — 실제 CI 라면 여기서 process.exit(1) 로 머지를 막습니다.");
  } else {
    console.log("  통과 — 머지 가능.");
  }
}
// → 정확도: 67% (3개 중 2개 통과)
// → ex-3 이 깨집니다. get_population 을 부르지 않고 인구를 지어냈기 때문입니다.

/* ===================================================================
 * [정답 8] 트레이스 라벨 설계
 *
 * 해설:
 * (a) 넣으면 안 됩니다. LANGSMITH_TRACING=true 는 프롬프트·도구 인자·응답을
 *     통째로 외부(LangSmith 서버)로 보냅니다. metadata 도 예외가 아닙니다.
 *     이메일은 개인정보이므로 해시하거나 내부 ID 로 바꿔서 넣어야 합니다.
 *     본문 값 자체를 가려야 한다면 langsmith/anonymizer 의 createAnonymizer 를 쓰거나,
 *     애초에 piiMiddleware 로 마스킹한 뒤 트레이싱하세요.
 *
 * (b) tags 는 "값이 몇 개 안 되고 필터링할 것" (환경, 버전, 기능 플래그).
 *     metadata 는 "값이 무한하고 나중에 되짚을 것" (userId, sessionId, requestId).
 *     tags 에 userId 를 넣으면 태그가 수만 개로 불어나 필터가 쓸모없어집니다.
 * =================================================================== */
console.log("\n===== [정답 8] 트레이스 라벨 설계 =====");
{
  // 개인정보는 그대로 넣지 않고 내부 ID / 해시로 바꿉니다.
  const internalUserId = "u_8f3a91"; // 이메일 대신 내부 ID
  const model = fakeModel().respond(new AIMessage("네, 확인했습니다."));
  const agent = createAgent({ model, tools });

  await agent.invoke(
    { messages: [new HumanMessage("서울 날씨?")] },
    {
      runName: "weather-query", // 이 플로우의 식별자
      tags: ["production", "agent-v1.2"], // 카디널리티 낮음 → 필터용
      metadata: {
        userId: internalUserId, // 카디널리티 높음 → 되짚기용
        sessionId: "sess_20260717_001",
        requestId: "req_abc123",
      },
    }
  );

  console.log("  runName=weather-query tags=[production, agent-v1.2] metadata={userId, sessionId, requestId}");
  console.log("  (a) 이메일은 metadata 에 넣지 않습니다 — 트레이싱은 데이터를 외부로 내보냅니다.");
  console.log("  (b) tags = 저카디널리티 필터 / metadata = 고카디널리티 추적.");
}
