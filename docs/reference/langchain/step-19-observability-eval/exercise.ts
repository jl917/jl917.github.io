/**
 * Step 19 — 관측·테스트·평가 / 연습문제
 * 실행: npx tsx docs/reference/langchain/step-19-observability-eval/exercise.ts
 *
 * 각 [문제 N] 아래를 채우세요. 정답은 solution.ts 에 있습니다.
 * 이 파일은 API 키 없이 돌아가야 합니다 — 실제 모델을 부르지 마세요.
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

/* ===== 공통 도구 (practice.ts 와 동일) ===== */

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

/* ===================================================================
 * [문제 1] 도구 단위 테스트
 *
 * getWeather / getPopulation 을 모델 없이 직접 호출해서 아래 3가지를 검증하세요.
 *   (1) getWeather({ city: "제주" }) === "제주: 비, 21도"
 *   (2) 표에 없는 도시("런던")는 "런던: 날씨 정보 없음" 을 반환한다
 *   (3) getPopulation({ city: "서울" }) 에 천단위 콤마가 들어있다
 *
 * 통과/실패를 console.log 로 출력하세요.
 * 힌트: await getWeather.invoke({ city: "제주" })
 * =================================================================== */
console.log("\n===== [문제 1] 도구 단위 테스트 =====");
// 여기에 작성

/* ===================================================================
 * [문제 2] fakeModel 로 2단계 도구 호출 에이전트 테스트
 *
 * "제주 날씨랑 인구 알려줘" 에 대해 에이전트가
 *   get_weather → get_population → 최종 답
 * 순서로 동작하도록 fakeModel 을 스크립팅하고 createAgent 로 실행하세요.
 *
 * 그리고 아래를 검증하세요.
 *   (1) result.messages 의 길이가 6 이다 (human, ai, tool, ai, tool, ai)
 *   (2) ToolMessage 가 2개다
 *   (3) model.callCount === 3
 *
 * 힌트: fakeModel().respondWithTools([...]).respondWithTools([...]).respond(new AIMessage("..."))
 * 힌트: 도구 호출 1번당 모델 호출이 1번씩 더 필요합니다.
 * =================================================================== */
console.log("\n===== [문제 2] 2단계 도구 호출 =====");
// 여기에 작성

/* ===================================================================
 * [문제 3] 궤적 추출 함수
 *
 * messages 배열에서 "호출된 도구 이름을 순서대로" 뽑는 extractTrajectory 를 구현하세요.
 *
 *   function extractTrajectory(messages: BaseMessage[]): string[]
 *
 * 문제 2 의 결과에 적용해서 ["get_weather", "get_population"] 이 나오는지 확인하세요.
 *
 * 힌트: AI 메시지만 고르고(m.getType() === "ai"), 각 메시지의 tool_calls 를 flatMap 하세요.
 * 힌트: tool_calls 는 없을 수 있습니다 (최종 답 메시지).
 * =================================================================== */
console.log("\n===== [문제 3] 궤적 추출 =====");
// 여기에 작성

/* ===================================================================
 * [문제 4] 궤적 평가자 — unordered 모드
 *
 * 아래 두 궤적은 "순서만 다르고 내용은 같습니다".
 *   actual    = ["get_population", "get_weather"]
 *   reference = ["get_weather", "get_population"]
 *
 * strict 모드로는 FAIL, unordered 모드로는 PASS 가 나오는
 * trajectoryMatch(actual, reference, mode) 를 구현하고 둘 다 출력하세요.
 *
 * 힌트: unordered 는 정렬 후 비교. 길이도 함께 봐야 합니다.
 * 힌트: 실무에서는 agentevals 의 createTrajectoryMatchEvaluator({ trajectoryMatchMode })
 *       를 쓰면 되지만, 원리를 알기 위해 직접 만들어 봅니다.
 * =================================================================== */
console.log("\n===== [문제 4] unordered 궤적 매칭 =====");
// 여기에 작성

/* ===================================================================
 * [문제 5] "운 좋게 맞은" 답 잡아내기
 *
 * 아래 에이전트는 get_weather 만 부르고서 인구까지 "지어내서" 답합니다.
 *   fakeModel()
 *     .respondWithTools([{ name: "get_weather", args: { city: "부산" }, id: "c1" }])
 *     .respond(new AIMessage("부산은 흐림, 26도 이고 인구는 3,300,000명 입니다."))
 *
 * 이 에이전트를 실행하고 두 가지 평가를 모두 돌려서, 결과가 갈리는 것을 보이세요.
 *   (1) 최종답 평가: 답에 "흐림, 26도" 와 "3,300,000명" 이 둘 다 있는가? → PASS 가 나올 것
 *   (2) 궤적 평가: 기대 궤적 ["get_weather", "get_population"] 과 strict 매칭 → FAIL 이 나올 것
 *
 * 그리고 "왜 (1) 만으로는 위험한가" 를 console.log 로 한 줄 설명하세요.
 * =================================================================== */
console.log("\n===== [문제 5] 환각을 통과시키는 평가 =====");
// 여기에 작성

/* ===================================================================
 * [문제 6] 도구 호출을 기록하는 미들웨어
 *
 * createMiddleware 로 auditMiddleware 를 만드세요.
 *   - wrapToolCall 에서 호출된 도구의 이름과 인자를 배열 auditLog 에 push
 *   - 그 미들웨어를 붙인 에이전트를 fakeModel 로 실행
 *   - 실행 후 auditLog 를 출력
 *
 * 기대: [{ name: "get_weather", args: { city: "서울" } }]
 *
 * 힌트: wrapToolCall: async (request, handler) => { ...; return handler(request); }
 * 힌트: request.toolCall.name / request.toolCall.args
 * =================================================================== */
console.log("\n===== [문제 6] 감사 로그 미들웨어 =====");
// 여기에 작성

/* ===================================================================
 * [문제 7] 회귀 게이트
 *
 * 아래 3개짜리 데이터셋으로 궤적 정확도를 계산하고,
 * 기준(0.9) 미달이면 "REGRESSION" 을 출력하는 게이트를 만드세요.
 * (실제 CI 라면 process.exit(1) 자리입니다 — 여기서는 출력만 하세요.)
 *
 * 각 예제의 에이전트 응답은 아래 scripted 를 그대로 쓰세요.
 * 정확도는 몇 %가 나오고, 어느 예제가 깨지나요? 주석으로 답을 적으세요.
 * =================================================================== */
console.log("\n===== [문제 7] 회귀 게이트 =====");

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

// 여기에 작성
// → 정확도는 몇 %인가요? (여기에 답)
// → 어느 예제가 왜 깨지나요? (여기에 답)

/* ===================================================================
 * [문제 8] 트레이스 라벨 설계 (코드 + 서술)
 *
 * "특정 사용자가 어제 겪은 오류를 다시 찾아야 한다" 는 상황을 가정합니다.
 * 에이전트를 실행하면서 runName / tags / metadata 를 적절히 붙이세요.
 *
 * 조건:
 *   - runName 으로 이 플로우를 식별할 수 있어야 한다
 *   - tags 로 환경(production)과 에이전트 버전을 필터할 수 있어야 한다
 *   - metadata 로 특정 사용자와 세션을 되짚을 수 있어야 한다
 *
 * 그리고 아래 질문에 주석으로 답하세요.
 *   (a) 사용자 이메일을 metadata 에 넣어도 될까요? 왜 그런가요?
 *   (b) tags 와 metadata 는 어떻게 나눠 쓰는 게 좋을까요?
 * =================================================================== */
console.log("\n===== [문제 8] 트레이스 라벨 설계 =====");
// 여기에 작성
// (a) (여기에 답)
// (b) (여기에 답)
