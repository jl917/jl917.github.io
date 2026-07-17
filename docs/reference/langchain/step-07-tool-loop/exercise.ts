/**
 * Step 07 — 도구 호출 루프 직접 구현 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-07-tool-loop/exercise.ts
 *
 * 아래 [문제 1] ~ [문제 8] 의 빈 곳을 채우세요.
 * 파일 맨 아래에서 풀고 싶은 문제의 주석을 풀어 실행하면 됩니다.
 *
 * 공통 준비(도구 3개, 모델, 레지스트리)는 이미 되어 있습니다. 그대로 쓰세요.
 */
import "dotenv/config";
import {
  initChatModel,
  tool,
  createAgent,
  AIMessage,
  HumanMessage,
  ToolMessage,
  SystemMessage,
} from "langchain";
import type { BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

/* ===== 공통 준비 (수정하지 마세요) ===== */

const WEATHER_DB: Record<string, string> = {
  서울: "맑음, 기온 28도, 습도 55%",
  부산: "흐림, 기온 26도, 습도 78%",
  제주: "비, 기온 24도, 습도 90%",
};

const POPULATION_DB: Record<string, number> = {
  서울: 9_386_000,
  부산: 3_293_000,
  제주: 675_000,
};

const getWeather = tool(
  ({ city }) => {
    const found = WEATHER_DB[city];
    if (!found) {
      throw new Error(
        `'${city}' 의 날씨 데이터가 없습니다. 지원 도시: ${Object.keys(WEATHER_DB).join(", ")}`,
      );
    }
    return found;
  },
  {
    name: "get_weather",
    description: "특정 도시의 현재 날씨를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시 이름. 예: 서울, 부산, 제주") }),
  },
);

const getPopulation = tool(
  async ({ city }) => {
    await new Promise((resolve) => setTimeout(resolve, 1000)); // 일부러 느리게
    const found = POPULATION_DB[city];
    if (found === undefined) {
      throw new Error(
        `'${city}' 의 인구 데이터가 없습니다. 지원 도시: ${Object.keys(POPULATION_DB).join(", ")}`,
      );
    }
    return `${city}의 인구는 ${found.toLocaleString("ko-KR")}명입니다.`;
  },
  {
    name: "get_population",
    description: "특정 도시의 인구를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시 이름. 예: 서울, 부산, 제주") }),
  },
);

const calculate = tool(
  ({ a, b, op }) => {
    switch (op) {
      case "add":
        return String(a + b);
      case "sub":
        return String(a - b);
      case "mul":
        return String(a * b);
      case "div":
        if (b === 0) throw new Error("0 으로 나눌 수 없습니다.");
        return String(a / b);
    }
  },
  {
    name: "calculate",
    description: "두 수의 사칙연산을 계산합니다. 암산 대신 반드시 이 도구를 쓰세요.",
    schema: z.object({
      a: z.number().describe("첫 번째 피연산자"),
      b: z.number().describe("두 번째 피연산자"),
      op: z.enum(["add", "sub", "mul", "div"]).describe("연산 종류"),
    }),
  },
);

const tools = [getWeather, getPopulation, calculate];

type AnyTool = { name: string; invoke: (input: any) => Promise<any> };
const toolsByName: Record<string, AnyTool> = Object.fromEntries(
  tools.map((t) => [t.name, t as unknown as AnyTool]),
);

function findTool(name: string): AnyTool {
  const found = toolsByName[name];
  if (!found) {
    throw new Error(
      `'${name}' 이라는 도구는 없습니다. 사용 가능: ${Object.keys(toolsByName).join(", ")}`,
    );
  }
  return found;
}

const model = await initChatModel("anthropic:claude-sonnet-4-6");

/* ===== [문제 1] 모델은 도구를 실행하지 않는다 =====
 *
 * tools 를 bindTools 로 붙인 모델에게 "부산 날씨 어때?" 라고 물어보세요.
 * 그리고 돌아온 AIMessage 에서 아래 3가지를 각각 출력하세요.
 *   - tool_calls 의 개수
 *   - 첫 번째 tool_call 의 name / args / id
 *   - response.text (도구를 부르는 응답인데 text 가 비어 있나요? 있나요?)
 *
 * 마지막으로 주석으로 답하세요:
 *   → 이 시점에 WEATHER_DB 는 조회되었나요? (예/아니오, 왜?)
 */
async function exercise1(): Promise<void> {
  // TODO: 여기에 작성하세요
}

/* ===== [문제 2] 한 바퀴를 손으로 돌리기 =====
 *
 * "제주 날씨 알려줘" 로 시작해서 아래 순서를 손으로 구현하세요. while 을 쓰지 말고 펼쳐서 쓰세요.
 *   1. messages 배열에 HumanMessage 를 넣는다
 *   2. modelWithTools.invoke(messages) → AIMessage 를 받아 messages 에 push
 *   3. AIMessage 의 tool_calls 를 순회하며 findTool 로 도구를 찾아 실행
 *   4. 결과를 ToolMessage 로 만들어 messages 에 push (tool_call_id 를 꼭 채우세요)
 *   5. modelWithTools.invoke(messages) 를 한 번 더 → 최종 답변 출력
 *
 * 마지막에 messages.length 를 출력하세요. 몇 개가 나와야 할까요?
 */
async function exercise2(): Promise<void> {
  // TODO: 여기에 작성하세요
}

/* ===== [문제 3] (함정) ToolMessage 를 빼먹으면 =====
 *
 * 문제 2와 똑같이 하되, 3~4 단계를 통째로 건너뛰세요.
 * 즉 tool_calls 가 담긴 AIMessage 만 messages 에 넣고, ToolMessage 없이 바로 invoke 를 부릅니다.
 *
 *   const messages = [new HumanMessage("제주 날씨 알려줘")];
 *   const ai = await modelWithTools.invoke(messages);
 *   messages.push(ai);
 *   // ← 여기서 ToolMessage 를 안 넣고
 *   await modelWithTools.invoke(messages);   // 무슨 일이 벌어질까요?
 *
 * try/catch 로 감싸서 에러의 name 과 message 를 출력하세요.
 * 그리고 주석으로 답하세요:
 *   → 이건 LangChain 이 낸 에러인가요, provider(Anthropic) 가 낸 에러인가요?
 *   → HTTP 상태 코드는 몇 번인가요?
 */
async function exercise3(): Promise<void> {
  // TODO: 여기에 작성하세요
}

/* ===== [문제 4] 종료 조건 =====
 *
 * (a) 올바른 종료 판정 함수를 구현하세요.
 *       function isDone(m: AIMessage): boolean
 *     tool_calls 가 비었을 때만 true 여야 합니다.
 *
 * (b) 흔히 저지르는 틀린 판정 함수도 구현하세요.
 *       function wrongIsDone(m: AIMessage): boolean   // text 가 있으면 끝났다고 본다
 *
 * (c) "안녕, 반가워!" (도구 불필요) 와 "서울 날씨 알려줘" (도구 필요) 두 질문에 대해
 *     isDone / wrongIsDone 을 각각 출력하고, 두 함수의 판정이 갈리는 케이스를 찾아
 *     주석으로 설명하세요.
 *
 * 힌트: 모델은 "서울 날씨를 확인해볼게요" 같은 text 와 tool_calls 를 동시에 보낼 수 있습니다.
 *       (이건 매번 나오지 않을 수 있으니 몇 번 돌려 보세요.)
 */
function isDone(_m: AIMessage): boolean {
  // TODO: 여기에 작성하세요
  return false;
}

function wrongIsDone(_m: AIMessage): boolean {
  // TODO: 여기에 작성하세요
  return false;
}

async function exercise4(): Promise<void> {
  // TODO: (c) 여기에 작성하세요
}

/* ===== [문제 5] 병렬 도구 호출 =====
 *
 * "서울, 부산, 제주 인구를 각각 알려줘" 를 물어 tool_calls 를 3개 받아내세요.
 * (모델이 3개를 한 번에 안 부를 수도 있습니다. 그러면 몇 개든 받은 만큼으로 진행하세요.)
 *
 * (a) for 루프로 순차 실행하고 걸린 시간(ms)을 재세요.
 * (b) Promise.all 로 병렬 실행하고 걸린 시간(ms)을 재세요.
 * (c) 두 시간을 비교해 출력하세요. get_population 에는 1초 지연이 있습니다.
 *
 * 마지막으로 주석으로 답하세요:
 *   → Promise.all 이 결과 순서를 보장하는데도, ToolMessage 의 짝을 배열 인덱스가 아니라
 *     tool_call_id 로 맞춰야 하는 이유는 무엇일까요?
 */
async function exercise5(): Promise<void> {
  // TODO: 여기에 작성하세요
}

/* ===== [문제 6] 무한 루프 방어 =====
 *
 * while 루프에 MAX_ITERATIONS = 3 상한을 걸고 "서울과 부산과 제주 인구를 모두 더하면?" 을 처리하세요.
 *
 * 요구사항:
 *   - 루프를 빠져나온 이유를 반드시 구분하세요. (정상 종료 / 상한 도달)
 *   - 상한에 걸리면 "상한 도달" 이라고 명시적으로 출력하세요. 조용히 넘어가면 안 됩니다.
 *   - 실제로 몇 바퀴를 썼는지 출력하세요.
 *
 * 그리고 주석으로 답하세요:
 *   → 상한을 아예 안 걸면 최악의 경우 무슨 일이 벌어지나요?
 *   → createAgent 의 recursionLimit 기본값은 몇이고, 그 단위는 '바퀴'인가요 '무엇'인가요?
 */
async function exercise6(): Promise<void> {
  // TODO: 여기에 작성하세요
}

/* ===== [문제 7] 에러 처리 =====
 *
 * "도쿄 날씨 알려줘" 를 처리하세요. WEATHER_DB 에 도쿄가 없으므로 get_weather 는 throw 합니다.
 *
 * 요구사항:
 *   - 도구 실행을 try/catch 로 감싸고, 에러를 ToolMessage 로 바꿔 모델에게 돌려주세요.
 *   - 그 ToolMessage 의 status 를 "error" 로 두세요.
 *   - content 에는 "무엇이 왜 실패했고 어떤 선택지가 있는지" 를 담으세요.
 *   - 모델이 사과하고 대안을 안내하며 정상 종료하는 것을 확인하세요.
 *
 * 그리고 주석으로 답하세요:
 *   → status: "error" 를 "success" 로 바꿔도 모델의 답변이 달라지나요? 왜 그럴까요?
 */
async function exercise7(): Promise<void> {
  // TODO: 여기에 작성하세요
}

/* ===== [문제 8] 미니 에이전트 완성 + createAgent 와 비교 =====
 *
 * 아래 runAgent 를 완성하세요. 문제 2~7 에서 배운 것을 전부 합치면 됩니다.
 *   - systemPrompt 가 있으면 SystemMessage 로 맨 앞에 넣기
 *   - maxIterations 상한 (기본 10)
 *   - 종료 조건은 tool_calls 기준
 *   - 도구는 Promise.all 로 병렬 실행
 *   - 도구 에러는 ToolMessage(status: "error") 로 회복
 *   - 모델이 없는 도구 이름을 지어낸 경우도 ToolMessage 로 회복 (findTool 대신 toolsByName 직접 조회)
 *   - 상한 도달 시 throw
 *
 * 그다음, 완성한 runAgent 와 createAgent 에게 같은 질문/같은 systemPrompt 를 주고
 * 두 결과의 '메시지 타입 시퀀스'(예: Human → AI → Tool → AI)가 같은지 비교해 출력하세요.
 */
async function runAgent(_options: {
  input: string;
  systemPrompt?: string;
  maxIterations?: number;
  verbose?: boolean;
}): Promise<{ messages: BaseMessage[]; output: string; iterations: number }> {
  // TODO: 여기에 작성하세요
  return { messages: [], output: "", iterations: 0 };
}

async function exercise8(): Promise<void> {
  // TODO: runAgent 와 createAgent 를 비교하세요
}

/* ===== 실행 — 풀고 싶은 문제의 주석을 푸세요 ===== */
// await exercise1();
// await exercise2();
// await exercise3();
// await exercise4();
// await exercise5();
// await exercise6();
// await exercise7();
// await exercise8();

// 아래 두 줄은 "선언만 하고 안 썼다" 는 경고를 막기 위한 것입니다. 신경 쓰지 마세요.
void [exercise1, exercise2, exercise3, exercise4, exercise5, exercise6, exercise7, exercise8];
void [isDone, wrongIsDone, runAgent, createAgent, ToolMessage, SystemMessage, findTool, model];
