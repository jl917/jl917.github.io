/**
 * Step 07 — 도구 호출 루프 직접 구현 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-07-tool-loop/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 여세요.
 * 모델 응답은 비결정적이므로 출력 숫자(바퀴 수, 메시지 개수)는 실행마다 달라질 수 있습니다.
 * 반면 객체의 shape (tool_call_id, status, 메시지 타입 순서)은 결정적입니다.
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

/* ===== 공통 준비 (exercise.ts 와 동일) ===== */

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
    await new Promise((resolve) => setTimeout(resolve, 1000));
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

/* ===== [정답 1] 모델은 도구를 실행하지 않는다 ===== */
async function solution1(): Promise<void> {
  console.log("\n=== [정답 1] ===");

  const modelWithTools = model.bindTools(tools);
  const response = await modelWithTools.invoke([new HumanMessage("부산 날씨 어때?")]);

  const toolCalls = response.tool_calls ?? [];
  console.log("tool_calls 개수:", toolCalls.length); // 대개 1

  const first = toolCalls[0];
  if (first) {
    console.log("  name:", first.name); // "get_weather"
    console.log("  args:", JSON.stringify(first.args)); // {"city":"부산"}
    console.log("  id  :", first.id); // "toolu_..." (Anthropic) / "call_..." (OpenAI)
  }

  console.log("text:", JSON.stringify(response.text));

  // → WEATHER_DB 는 조회되지 않았습니다.
  //
  //   모델이 만든 것은 "get_weather 를 {city:'부산'} 으로 불러줘" 라는 '요청'(JSON)일 뿐입니다.
  //   모델은 우리 프로세스 밖(HTTP 너머)에 있어서 우리 함수를 실행할 방법이 아예 없습니다.
  //   도구를 실제로 실행하는 주체는 언제나 우리 애플리케이션 코드입니다.
  //   이 비대칭이 Step 07 전체의 출발점입니다 — 모델은 '요청', 실행은 '우리'.
  //
  //   id 의 접두사가 provider 마다 다른 것에 주목하세요. 우리는 이 값을 절대 지어내면 안 되고,
  //   모델이 준 것을 그대로 ToolMessage.tool_call_id 에 되돌려줘야 합니다.
}

/* ===== [정답 2] 한 바퀴를 손으로 돌리기 ===== */
async function solution2(): Promise<void> {
  console.log("\n=== [정답 2] ===");

  const modelWithTools = model.bindTools(tools);

  // 1. Human
  const messages: BaseMessage[] = [new HumanMessage("제주 날씨 알려줘")];

  // 2. 모델 호출 → AIMessage push
  const ai = await modelWithTools.invoke(messages);
  messages.push(ai);

  // 3~4. 도구 실행 → ToolMessage push
  for (const toolCall of ai.tool_calls ?? []) {
    const output = await findTool(toolCall.name).invoke(toolCall.args);
    messages.push(
      new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!, // ★ 모델이 준 id 를 그대로
        name: toolCall.name,
      }),
    );
  }

  // 5. 도구 결과가 붙은 배열을 통째로 다시
  const final = await modelWithTools.invoke(messages);
  messages.push(final);

  console.log("최종 답변:", final.text);
  console.log("messages.length:", messages.length); // 4

  // → 4개입니다: Human → AI(tool_calls) → Tool → AI(최종 답변).
  //
  //   여기서 가장 중요한 것은 5번에서 messages '전체'를 다시 넣는다는 점입니다.
  //   모델 API 는 stateless 입니다. 이전 호출을 기억하지 못하므로, 매 바퀴마다
  //   지금까지의 대화를 통째로 다시 보내야 합니다. 그래서 에이전트의 '기억'은
  //   결국 이 messages 배열 하나가 전부입니다.
  //
  //   흔한 실수: final 을 push 하지 않는 것. 지금은 대화가 끝나서 티가 안 나지만,
  //   이 messages 를 다음 턴에 이어 쓰면 모델의 마지막 답변이 사라진 채로 대화가 이어집니다.
}

/* ===== [정답 3] (함정) ToolMessage 를 빼먹으면 ===== */
async function solution3(): Promise<void> {
  console.log("\n=== [정답 3] ===");

  const modelWithTools = model.bindTools(tools);
  const messages: BaseMessage[] = [new HumanMessage("제주 날씨 알려줘")];

  const ai = await modelWithTools.invoke(messages);
  messages.push(ai);
  console.log("tool_calls 개수:", (ai.tool_calls ?? []).length);

  // ToolMessage 를 일부러 빼고 바로 다시 호출합니다.
  try {
    await modelWithTools.invoke(messages);
    console.log(
      "(에러가 안 났다면 모델이 이번엔 도구를 안 불렀을 수 있습니다. 다시 실행해 보세요.)",
    );
  } catch (error) {
    console.log("에러 name   :", (error as Error).name);
    console.log("에러 message:", (error as Error).message);
  }

  // → 이건 provider(Anthropic) 가 낸 에러입니다. HTTP 400 (Bad Request) 입니다.
  //
  //   LangChain 은 우리 messages 배열을 그대로 provider 포맷으로 직렬화해서 보낼 뿐,
  //   "tool_calls 에 대응하는 ToolMessage 가 다 있는지" 를 검사해 주지 않습니다.
  //   검사는 provider 서버에서 일어나고, 그래서 에러 메시지도 provider 말투입니다.
  //   Anthropic 은 대략 이렇게 말합니다:
  //     "messages: tool_use ids were found without tool_result blocks immediately after"
  //   OpenAI 는 이렇게 말합니다:
  //     "An assistant message with 'tool_calls' must be followed by tool messages
  //      responding to each 'tool_call_id'"
  //
  //   교훈이 두 개입니다.
  //   (1) tool_calls 가 N개면 ToolMessage 도 정확히 N개여야 합니다. 하나라도 빠지면 400.
  //       "도구 하나가 실패했으니 그건 빼고 보내자" 는 생각이 바로 이 에러를 만듭니다.
  //       실패한 것도 ToolMessage 로 채워 보내야 합니다 (정답 7).
  //   (2) 이건 조용히 틀리는 게 아니라 시끄럽게 터지는 함정이라 그나마 다행입니다.
  //       진짜 무서운 함정은 정답 4 쪽입니다.
}

/* ===== [정답 4] 종료 조건 ===== */
function isDone(m: AIMessage): boolean {
  // 오직 이것뿐입니다. tool_calls 가 비었으면 끝.
  return (m.tool_calls ?? []).length === 0;
}

function wrongIsDone(m: AIMessage): boolean {
  // 흔한 오답: "모델이 뭐라도 말했으면 끝난 거 아냐?"
  return m.text.length > 0;
}

async function solution4(): Promise<void> {
  console.log("\n=== [정답 4] ===");

  const modelWithTools = model.bindTools(tools);

  const noTool = await modelWithTools.invoke([new HumanMessage("안녕, 반가워!")]);
  const needTool = await modelWithTools.invoke([new HumanMessage("서울 날씨 알려줘")]);

  console.log("\n[도구 불필요] isDone =", isDone(noTool), "/ wrongIsDone =", wrongIsDone(noTool));
  console.log("  text:", JSON.stringify(noTool.text.slice(0, 40)));

  console.log(
    "\n[도구 필요]   isDone =",
    isDone(needTool),
    "/ wrongIsDone =",
    wrongIsDone(needTool),
  );
  console.log("  text      :", JSON.stringify(needTool.text));
  console.log("  tool_calls:", (needTool.tool_calls ?? []).map((c) => c.name).join(", "));

  // → 판정이 갈리는 케이스: "도구도 부르면서 말도 하는" AIMessage 입니다.
  //
  //   모델은 tool_calls 와 text 를 '동시에' 보낼 수 있습니다. AIMessage 의 content 는
  //   블록 배열이라 [{type:"text", ...}, {type:"tool_use", ...}] 처럼 둘이 공존합니다.
  //   이때:
  //     isDone      → false (tool_calls 가 있으니 계속) ✅
  //     wrongIsDone → true  (text 가 있으니 끝) ❌
  //
  //   wrongIsDone 을 쓰면 무슨 일이 벌어질까요? 도구를 실행조차 하지 않고 루프를 끝내고,
  //   "서울 날씨를 확인해볼게요!" 라는 text 를 최종 답변으로 사용자에게 내놓습니다.
  //   에러는 안 납니다. 로그도 깨끗합니다. 사용자만 날씨를 영영 못 받습니다.
  //
  //   이게 이 스텝에서 가장 조용한 함정입니다. 반대 방향의 오답도 있습니다:
  //     "text 가 비었으면 계속"  → 도구 없이 빈 답변을 준 경우 무한 루프
  //   종료 조건은 tool_calls 하나로만 판단하세요. text 는 종료와 무관합니다.
}

/* ===== [정답 5] 병렬 도구 호출 ===== */
async function solution5(): Promise<void> {
  console.log("\n=== [정답 5] ===");

  const modelWithTools = model.bindTools(tools);
  const messages: BaseMessage[] = [
    new HumanMessage("서울, 부산, 제주 인구를 각각 알려줘."),
  ];

  const ai = await modelWithTools.invoke(messages);
  messages.push(ai);
  const toolCalls = ai.tool_calls ?? [];
  console.log("tool_calls 개수:", toolCalls.length);

  // (a) 순차
  const t0 = Date.now();
  const sequential: ToolMessage[] = [];
  for (const toolCall of toolCalls) {
    const output = await findTool(toolCall.name).invoke(toolCall.args);
    sequential.push(
      new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
      }),
    );
  }
  const seqMs = Date.now() - t0;

  // (b) 병렬
  const t1 = Date.now();
  const parallel = await Promise.all(
    toolCalls.map(async (toolCall) => {
      const output = await findTool(toolCall.name).invoke(toolCall.args);
      return new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
      });
    }),
  );
  const parMs = Date.now() - t1;

  // (c) 비교
  console.log(`순차: ${seqMs}ms`);
  console.log(`병렬: ${parMs}ms`);
  console.log(`→ ${(seqMs / Math.max(parMs, 1)).toFixed(1)}배 빠름`);

  messages.push(...parallel);
  const final = await modelWithTools.invoke(messages);
  console.log("최종 답변:", final.text);

  // → Promise.all 이 순서를 보장하는데도 tool_call_id 로 짝을 맞춰야 하는 이유:
  //
  //   Promise.all 이 보장하는 것은 '결과 배열의 순서'이지 '실행 완료 순서'가 아닙니다.
  //   그건 맞습니다. 문제는 그 다음입니다.
  //
  //   (1) provider 는 messages 배열의 순서가 아니라 tool_call_id 로 짝을 찾습니다.
  //       id 만 맞으면 ToolMessage 를 어떤 순서로 넣든 동작합니다. 반대로 순서가 맞아도
  //       id 가 틀리면 400 입니다. 즉 배열 순서는 애초에 짝짓기에 쓰이지 않습니다.
  //
  //   (2) 진짜 사고는 Promise.all 을 벗어날 때 납니다. 실무에서는 도구 실행을 큐에 넣거나,
  //       allSettled 로 바꾸거나, 일부는 캐시에서 즉답하고 일부만 실행하는 식으로
  //       리팩터링이 들어옵니다. 그 순간 "i번째 결과는 i번째 tool_call 의 것" 이라는
  //       가정이 조용히 깨집니다. 인덱스로 짝을 맞춘 코드는 그때 엉뚱한 도시의 인구를
  //       엉뚱한 질문에 붙여 놓고도 에러 없이 잘 돌아갑니다.
  //
  //   그래서 짝은 처음부터 id 로 맞춥니다. 아래처럼 map 안에서 toolCall 을 클로저로 잡으면
  //   인덱스를 쓸 일 자체가 없어집니다 — 이게 인덱스 버그를 구조적으로 막는 방법입니다.
  console.log("\n[검산] 순차/병렬 결과의 id 순서:");
  console.log("  순차:", sequential.map((m) => m.tool_call_id).join(", "));
  console.log("  병렬:", parallel.map((m) => m.tool_call_id).join(", "));
}

/* ===== [정답 6] 무한 루프 방어 ===== */
async function solution6(): Promise<void> {
  console.log("\n=== [정답 6] ===");

  const modelWithTools = model.bindTools(tools);
  const MAX_ITERATIONS = 3;

  const messages: BaseMessage[] = [
    new HumanMessage("서울과 부산과 제주 인구를 모두 더하면?"),
  ];

  let iteration = 0;
  let finished = false; // ★ 루프를 빠져나온 '이유' 를 기록하는 플래그

  while (iteration < MAX_ITERATIONS) {
    iteration += 1;
    const ai = await modelWithTools.invoke(messages);
    messages.push(ai);
    console.log(
      `[${iteration}/${MAX_ITERATIONS}] tool_calls=${(ai.tool_calls ?? []).length}`,
    );

    if ((ai.tool_calls ?? []).length === 0) {
      finished = true;
      console.log("→ 정상 종료:", ai.text);
      break;
    }

    const toolMessages = await Promise.all(
      (ai.tool_calls ?? []).map(async (toolCall) => {
        const output = await findTool(toolCall.name).invoke(toolCall.args);
        return new ToolMessage({
          content: String(output),
          tool_call_id: toolCall.id!,
          name: toolCall.name,
        });
      }),
    );
    messages.push(...toolMessages);
  }

  if (!finished) {
    console.log(`→ 상한 도달! ${MAX_ITERATIONS}바퀴를 다 썼는데 tool_calls 가 남아 있습니다.`);
  }
  console.log(`사용한 바퀴: ${iteration}, 정상 종료: ${finished}`);

  // → 상한을 안 걸면: 모델이 같은 도구를 계속 부르는 루프에 빠질 수 있고, 그러면
  //   호출 1회당 API 과금이 계속 발생합니다. 게다가 메시지 배열이 매 바퀴 길어지므로
  //   입력 토큰이 누적해서 늘어납니다 — 비용이 선형이 아니라 이차로 증가합니다.
  //   밤새 돌면 청구서가 폭발합니다. 상한은 선택이 아니라 필수입니다.
  //
  // → createAgent 의 recursionLimit 기본값은 25 입니다.
  //   단위는 '바퀴'가 아니라 LangGraph 의 super-step 입니다.
  //   한 바퀴 = 모델 노드 1 step + 도구 노드 1 step = 2 step 이므로,
  //   25 는 대략 12바퀴에 해당합니다. 25번 도구를 부를 수 있다는 뜻이 아닙니다.
  //   초과하면 GraphRecursionError 가 던져집니다.
  //
  // ★ 이 문제의 핵심은 finished 플래그입니다.
  //   플래그 없이 while 을 빠져나오면 "정상 종료"와 "상한 도달"을 구분할 수 없습니다.
  //   그러면 상한에 걸려 잘려나간 미완성 결과를 완성된 답으로 착각해서 사용자에게 줍니다.
  //   에러도 안 나고 로그도 깨끗한데 답만 틀립니다. 반드시 구분하세요.
}

/* ===== [정답 7] 에러 처리 ===== */
async function solution7(): Promise<void> {
  console.log("\n=== [정답 7] ===");

  const modelWithTools = model.bindTools(tools);
  const messages: BaseMessage[] = [
    new SystemMessage(
      "너는 날씨 비서다. 도구가 에러를 돌려주면 사용자에게 사과하고 가능한 대안을 안내해라.",
    ),
    new HumanMessage("도쿄 날씨 알려줘"),
  ];

  for (let i = 1; i <= 4; i++) {
    const ai = await modelWithTools.invoke(messages);
    messages.push(ai);

    if ((ai.tool_calls ?? []).length === 0) {
      console.log("\n최종 답변:", ai.text);
      break;
    }

    const toolMessages = await Promise.all(
      (ai.tool_calls ?? []).map(async (toolCall) => {
        try {
          const output = await findTool(toolCall.name).invoke(toolCall.args);
          return new ToolMessage({
            content: String(output),
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "success",
          });
        } catch (error) {
          // ★ 에러도 '결과'입니다. tool_call_id 를 채워서 반드시 돌려주세요.
          //   content 에 원인과 선택지를 담아야 모델이 회복할 수 있습니다.
          return new ToolMessage({
            content: `도구 실행 실패: ${(error as Error).message}`,
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "error",
          });
        }
      }),
    );
    messages.push(...toolMessages);
    console.log(
      `[${i}바퀴] ${toolMessages.map((m) => `${m.name}:${m.status}`).join(", ")}`,
    );
  }

  // → status 를 "success" 로 바꿔도 모델의 답변은 거의 달라지지 않습니다.
  //
  //   모델이 실제로 읽는 것은 content 뿐이기 때문입니다. status 는 LangChain 이
  //   메시지 객체에 붙여 두는 메타데이터이고, provider 포맷으로 나갈 때 대부분
  //   "이 tool_result 는 에러였다" 정도의 플래그로만 전달되거나 아예 무시됩니다.
  //   모델을 움직이는 것은 "'도쿄' 의 날씨 데이터가 없습니다. 지원 도시: 서울, 부산, 제주"
  //   라는 문장 자체입니다.
  //
  //   그래서 에러 content 를 쓸 때의 원칙:
  //     ❌ "Error"                          → 모델이 뭘 해야 할지 모릅니다
  //     ❌ "TypeError: undefined is not..."  → 스택 트레이스는 모델에게 소음입니다
  //     ✅ "'도쿄' 의 날씨 데이터가 없습니다. 지원 도시: 서울, 부산, 제주"
  //        → 무엇이/왜 실패했고 어떤 선택지가 있는지. 모델이 스스로 고칩니다.
  //   에러 메시지도 프롬프트입니다.
  //
  //   그러면 status 는 왜 채우나요? 사람과 코드를 위해서입니다. 로그를 필터링하거나
  //   "에러가 2번 연속이면 중단" 같은 정책을 우리 코드가 판단할 때 씁니다.
  //
  // ★ 그리고 절대 하면 안 되는 것: 실패한 도구의 ToolMessage 를 '빼고' 보내는 것.
  //   그건 정답 3의 400 에러로 직행합니다. 실패해도 자리는 채워야 합니다.
}

/* ===== [정답 8] 미니 에이전트 완성 + createAgent 와 비교 ===== */
async function runAgent(options: {
  input: string;
  systemPrompt?: string;
  maxIterations?: number;
  verbose?: boolean;
}): Promise<{ messages: BaseMessage[]; output: string; iterations: number }> {
  const { input, systemPrompt, maxIterations = 10, verbose = false } = options;

  const modelWithTools = model.bindTools(tools);

  const messages: BaseMessage[] = [];
  if (systemPrompt) messages.push(new SystemMessage(systemPrompt));
  messages.push(new HumanMessage(input));

  for (let iteration = 1; iteration <= maxIterations; iteration++) {
    const ai = await modelWithTools.invoke(messages);
    messages.push(ai);

    const toolCalls = ai.tool_calls ?? [];
    if (verbose) {
      console.log(
        `  [${iteration}바퀴] tool_calls=${toolCalls.length}` +
          (toolCalls.length ? ` (${toolCalls.map((c) => c.name).join(", ")})` : ""),
      );
    }

    // 종료 조건 — tool_calls 기준. text 는 보지 않습니다.
    if (toolCalls.length === 0) {
      return { messages, output: ai.text, iterations: iteration };
    }

    const toolMessages = await Promise.all(
      toolCalls.map(async (toolCall) => {
        // findTool 대신 직접 조회 — 없는 도구도 에러로 회복하기 위해서입니다.
        const selected = toolsByName[toolCall.name];
        if (!selected) {
          return new ToolMessage({
            content: `'${toolCall.name}' 이라는 도구는 없습니다. 사용 가능: ${Object.keys(toolsByName).join(", ")}`,
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "error",
          });
        }
        try {
          const output = await selected.invoke(toolCall.args);
          return new ToolMessage({
            content: String(output),
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "success",
          });
        } catch (error) {
          return new ToolMessage({
            content: `도구 실행 실패: ${(error as Error).message}`,
            tool_call_id: toolCall.id!,
            name: toolCall.name,
            status: "error",
          });
        }
      }),
    );
    messages.push(...toolMessages);
  }

  // 상한 도달 — 조용히 미완성 결과를 돌려주지 않고 명시적으로 던집니다.
  throw new Error(`최대 반복 횟수(${maxIterations})를 넘었습니다.`);
}

async function solution8(): Promise<void> {
  console.log("\n=== [정답 8] ===");

  const question = "서울과 부산의 인구를 더하면 몇 명이야? 계산은 도구로 해줘.";
  const systemPrompt = "너는 도시 정보 비서다. 숫자 계산은 반드시 calculate 도구를 써라.";

  const shape = (ms: BaseMessage[]) =>
    ms.map((m) => m.constructor.name.replace("Message", "")).join(" → ");

  // (A) 우리 것
  const mine = await runAgent({ input: question, systemPrompt, verbose: true });
  console.log("\n(A) runAgent");
  console.log("  답변:", mine.output);
  console.log("  바퀴:", mine.iterations);
  console.log("  시퀀스:", shape(mine.messages));

  // (B) 프레임워크
  const agent = createAgent({ model, tools, systemPrompt });
  const result = await agent.invoke({
    messages: [{ role: "user", content: question }],
  });
  const lastMessage = result.messages.at(-1) as AIMessage;
  console.log("\n(B) createAgent");
  console.log("  답변:", lastMessage.text);
  console.log("  시퀀스:", shape(result.messages as BaseMessage[]));

  // 검산: 두 답 모두 9,386,000 + 3,293,000 = 12,679,000 이 나와야 합니다.
  const EXPECTED = 12_679_000;
  const hit = (s: string) =>
    s.replace(/[,\s]/g, "").includes(String(EXPECTED)) ||
    s.includes(EXPECTED.toLocaleString("ko-KR"));
  console.log("\n[검산] 기대값:", EXPECTED.toLocaleString("ko-KR"));
  console.log("  (A) 포함?", hit(mine.output));
  console.log("  (B) 포함?", hit(lastMessage.text));

  // → 시퀀스가 같습니다: System → Human → AI → Tool → Tool → AI → Tool → AI
  //   (모델이 인구 두 개를 한 번에 부르면 Tool 이 2개 연속, 나눠 부르면 달라집니다.)
  //
  //   답도 같고 메시지 모양도 같습니다. createAgent 는 우리 runAgent 와 '같은 루프'입니다.
  //   다만 아래 것들이 더 있습니다 (본문 7-7 표 참고):
  //     - recursionLimit (우리의 maxIterations)
  //     - checkpointer (대화 저장 — Step 10)
  //     - stream / streamEvents (Step 09)
  //     - middleware (Step 11~12)
  //     - responseFormat (구조화 출력 — Step 05)
  //     - interrupt (HITL — Step 13)
  //   전부 "이 루프의 어느 지점에 무엇을 끼워 넣느냐" 의 문제입니다.
  //   루프를 손으로 만들어 봤으니, 앞으로 나올 기능들이 루프의 어디에 붙는지
  //   지도 위에 찍을 수 있게 되었습니다.
}

/* ===== 실행 ===== */
await solution1();
await solution2();
await solution3();
await solution4();
await solution5();
await solution6();
await solution7();
await solution8();

console.log("\n=== Step 07 solution 끝 ===");
