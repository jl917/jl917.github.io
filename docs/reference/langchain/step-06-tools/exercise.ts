/**
 * Step 06 — 도구(Tool) 정의 : 연습문제
 * 실행: npx tsx docs/reference/langchain/step-06-tools/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 정답과 해설은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 *
 * 필요 환경변수: ANTHROPIC_API_KEY
 */
import "dotenv/config";
import { initChatModel, tool, ToolMessage, type ToolRuntime } from "langchain";
import * as z from "zod";

const MODEL_ID = process.env.MODEL_ID ?? "anthropic:claude-sonnet-4-6";

function section(title: string) {
  console.log("\n" + "=".repeat(70));
  console.log(title);
  console.log("=".repeat(70));
}

/* ===== [문제 1] ===== */
/**
 * tool() 로 `convert_temperature` 도구를 만드세요.
 *  - 섭씨 ↔ 화씨를 변환합니다.
 *  - schema: value(number), from("C" | "F")  ← z.enum 을 쓰세요
 *  - 모든 필드에 .describe() 를 붙이세요.
 *  - 변환 결과를 사람이 읽는 문장으로 반환하세요. 예: "25°C = 77°F"
 * 그리고 도구를 직접 invoke 해서(모델 없이) 결과를 출력하세요.
 */
async function exercise1() {
  section("[문제 1] convert_temperature 도구 만들기");

  // 여기에 작성
}

/* ===== [문제 2] ===== */
/**
 * 아래 `mysteryTool` 은 description 이 부실해서 모델이 잘 안 부릅니다.
 * description 과 name 을 고쳐서 모델이 확실히 부르도록 만드세요.
 *  - "무엇을 / 언제 쓰는지 / 제약" 세 가지를 모두 넣으세요.
 *  - 고치기 전/후 각각 bindTools 로 같은 질문을 던져 tool_calls 개수를 비교 출력하세요.
 * 질문: "서울 지하철 2호선 첫차 시간 알려줘"
 */
const mysteryTool = tool(async ({ line }) => `${line}호선 첫차 05:30`, {
  name: "info",
  description: "정보를 반환합니다.",
  schema: z.object({ line: z.string() }),
});

async function exercise2() {
  section("[문제 2] description 고쳐서 모델이 부르게 만들기");

  // 여기에 작성
}

/* ===== [문제 3] ===== */
/**
 * 모델에게 도구 2개(get_weather, get_time)를 붙이고
 * "서울 날씨랑 지금 시각 알려줘" 를 물어 tool_calls 를 관찰하세요.
 *  - tool_calls 가 몇 개 오는지, 각각의 id/name/args 를 출력하세요.
 *  - 이어서 toolChoice: "get_weather" 로 특정 도구를 강제했을 때
 *    tool_calls 가 어떻게 달라지는지 함께 출력하세요.
 */
async function exercise3() {
  section("[문제 3] 병렬 tool_calls 와 toolChoice 관찰");

  // 여기에 작성
}

/* ===== [문제 4] ===== */
/**
 * 아래 `divideUnsafe` 는 0으로 나누면 throw 합니다.
 * 이 도구를 감싸서, 실패해도 throw 하지 않고
 * "모델이 읽고 스스로 고칠 수 있는" 오류 문자열을 반환하는
 * `divideSafe` 를 만드세요.
 *
 * 그리고 모델에게 divideSafe 를 붙여 "10을 0으로 나눠줘" 를 물은 뒤,
 * ToolMessage 를 돌려주고 모델의 후속 반응까지 출력하세요.
 * (ToolMessage 의 tool_call_id 를 반드시 채워야 합니다 — 왜일까요?)
 */
const divideUnsafe = tool(
  async ({ a, b }) => {
    if (b === 0) throw new Error("0으로 나눌 수 없습니다");
    return String(a / b);
  },
  {
    name: "divide_unsafe",
    description: "a를 b로 나눕니다.",
    schema: z.object({ a: z.number(), b: z.number() }),
  },
);

async function exercise4() {
  section("[문제 4] 에러를 모델에게 돌려주기");

  // 여기에 작성
}

/* ===== [문제 5] ===== */
/**
 * artifact 를 쓰는 도구를 만드세요.
 *  - 이름: `fetch_logs`
 *  - 로그 500줄을 생성한다고 가정합니다.
 *  - content(모델용)에는 "총 500줄, 그중 ERROR N건" 같은 요약만 넣으세요.
 *  - artifact(코드용)에는 500줄 전체를 넣으세요.
 *  - ToolRuntime 에서 tool call id 를 받아 ToolMessage 의 tool_call_id 에 채우세요.
 * 실행 후 content 와 artifact 의 크기 차이를 출력해 비교하세요.
 *
 * 함정: 먼저 `config.toolCallId` 만 써서 짜 보고, 완성된 ToolMessage 의
 *       tool_call_id 를 찍어 보세요. 무슨 값이 나오나요? 왜 그럴까요?
 *       (힌트: 이 값을 주입하는 주체가 누구인지 생각해 보세요. 지금 도구를 부르는 건 누구인가요?)
 */
async function exercise5() {
  section("[문제 5] artifact 로 컨텍스트 아끼기");

  // 여기에 작성
}

/* ===== [문제 6] ===== */
/**
 * 외부 API 를 호출하는 도구를 만드세요.
 *  - https://api.frankfurter.app/latest?from=USD&to=KRW 를 씁니다.
 *  - AbortSignal.timeout 으로 2초 타임아웃을 겁니다.
 *  - 실패하면 최대 2회까지 지수 백오프로 재시도합니다.
 *  - 그래도 실패하면 throw 하지 말고 "오류: ..." 문자열을 반환합니다.
 * 힌트: 타임아웃으로 끊긴 에러의 `name` 은 "TimeoutError" 입니다.
 * 검증: 타임아웃을 1ms 로 바꿔 일부러 실패시켜 보세요.
 */
async function exercise6() {
  section("[문제 6] 타임아웃 + 재시도가 있는 외부 API 도구");

  // 여기에 작성
}

/* ===== [문제 7] ===== */
/**
 * 다음 만능 도구를 목적별 도구 3개로 쪼개세요.
 *
 *   name: "calendar",
 *   description: "캘린더 작업을 합니다.",
 *   schema: z.object({ op: z.string(), data: z.record(z.string(), z.unknown()) })
 *
 * 쪼갠 뒤 모델에게 "내일 오후 3시에 팀 회의 1시간 잡아줘" 를 물어
 * 어떤 도구를 어떤 args 로 부르는지 출력하세요.
 * 만능 도구 버전과 나란히 비교 출력하면 더 좋습니다.
 */
async function exercise7() {
  section("[문제 7] 만능 도구를 목적별로 쪼개기");

  // 여기에 작성
}

/* ===== [문제 8] ===== */
/**
 * 아래 도구 이름들 중 provider 가 거부하거나 문제를 일으킬 이름을 골라내고,
 * 각각을 올바른 이름으로 고친 표를 콘솔에 출력하세요.
 *
 *   "get weather", "getWeather", "get-weather", "날씨_조회",
 *   "search_products", "tool1", "do_it", "list_customer_orders_v2"
 *
 * 각 이름에 대해 (a) 형식상 문제가 있는가 (b) 의미상 좋은 이름인가
 * 두 가지를 구분해서 판단하세요. 둘은 다른 문제입니다.
 */
async function exercise8() {
  section("[문제 8] 도구 이름 감별하기");

  // 여기에 작성
}

async function main() {
  await exercise1();
  await exercise2();
  await exercise3();
  await exercise4();
  await exercise5();
  await exercise6();
  await exercise7();
  await exercise8();
}

main().catch((err) => {
  console.error("실행 실패:", err);
  process.exit(1);
});
