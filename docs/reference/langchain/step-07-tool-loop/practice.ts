/**
 * Step 07 — 도구 호출 루프 직접 구현
 * 실행: npx tsx docs/reference/langchain/step-07-tool-loop/practice.ts
 *
 * 이 파일은 본문 7-1 ~ 7-7 의 예제를 순서대로 담고 있습니다.
 * 모델 응답은 비결정적이므로 출력은 실행할 때마다 달라집니다.
 * (특히 모델이 도구를 몇 번에 나눠 부르는지는 매번 다를 수 있습니다.)
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

/* ===== 공통 준비 — 도구 3개와 모델 ===== */

// 도구가 조회할 가짜 데이터. 결정적(deterministic)이라 결과를 눈으로 검산할 수 있습니다.
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
    // 일부러 던집니다. 7-6 에서 이 에러를 ToolMessage 로 바꿔 모델에게 돌려줍니다.
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
    schema: z.object({
      city: z.string().describe("도시 이름. 예: 서울, 부산, 제주"),
    }),
  },
);

const getPopulation = tool(
  async ({ city }) => {
    // 병렬 실행 효과를 눈으로 보기 위해 일부러 1초 지연을 넣습니다.
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
    schema: z.object({
      city: z.string().describe("도시 이름. 예: 서울, 부산, 제주"),
    }),
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

/**
 * 도구 이름 → 도구 객체 조회용 레지스트리.
 *
 * 도구마다 schema 가 달라서 배열 원소의 타입이 유니온이 됩니다. 그 상태로 invoke 를 부르면
 * TypeScript 가 파라미터 타입을 교집합으로 좁혀 버려서 에러가 납니다. 여기서는 루프의 원리에
 * 집중하기 위해 최소 인터페이스로 단순화합니다. (createAgent 는 내부에서 이 문제를 대신 풀어줍니다.)
 */
type AnyTool = { name: string; invoke: (input: any) => Promise<any> };
const toolsByName: Record<string, AnyTool> = Object.fromEntries(
  tools.map((t) => [t.name, t as unknown as AnyTool]),
);

/**
 * 이름으로 도구를 찾습니다.
 *
 * 모델은 존재하지 않는 도구 이름을 지어낼 수 있습니다(hallucination). 레지스트리 조회 결과가
 * undefined 일 수 있다는 뜻이고, TypeScript 의 noUncheckedIndexedAccess 가 바로 이 지점을
 * 잡아 줍니다. 7-1 ~ 7-6 에서는 일단 던지고, 7-7 의 runAgent 에서는 이 상황마저
 * ToolMessage 로 바꿔 모델에게 돌려주는 '어른스러운' 처리를 합니다.
 */
function findTool(name: string): AnyTool {
  const found = toolsByName[name];
  if (!found) {
    throw new Error(
      `'${name}' 이라는 도구는 없습니다. 사용 가능: ${Object.keys(toolsByName).join(", ")}`,
    );
  }
  return found;
}

// 모델은 문자열 한 줄로 만듭니다. OpenAI 를 쓰려면 "openai:gpt-5.5" 로 바꾸고
// 환경변수를 OPENAI_API_KEY 로 두면 아래 코드는 한 글자도 안 바뀝니다.
const model = await initChatModel("anthropic:claude-sonnet-4-6");

/** 메시지 배열을 한눈에 보기 위한 출력 헬퍼 */
function printMessages(messages: BaseMessage[], title: string): void {
  console.log(`\n----- ${title} (총 ${messages.length}개) -----`);
  for (const [i, m] of messages.entries()) {
    if (m instanceof SystemMessage) {
      console.log(`[${i}] System   | ${m.text}`);
    } else if (m instanceof HumanMessage) {
      console.log(`[${i}] Human    | ${m.text}`);
    } else if (m instanceof AIMessage) {
      const calls = (m.tool_calls ?? [])
        .map((c) => `${c.name}(${JSON.stringify(c.args)})`)
        .join(", ");
      console.log(
        `[${i}] AI       | text=${JSON.stringify(m.text)} tool_calls=[${calls}]`,
      );
    } else if (m instanceof ToolMessage) {
      console.log(
        `[${i}] Tool     | name=${m.name} id=${m.tool_call_id} status=${m.status} content=${JSON.stringify(m.text)}`,
      );
    }
  }
}

/* ===== [7-1] 에이전트 = 모델 + 도구 + 루프 ===== */
async function section7_1(): Promise<void> {
  console.log("\n=== [7-1] 모델은 도구를 '실행'하지 않는다. '요청'만 한다 ===");

  // bindTools 는 모델을 바꾸지 않습니다. 도구 스펙이 붙은 "새 모델 객체"를 돌려줍니다.
  const modelWithTools = model.bindTools(tools);

  const response = await modelWithTools.invoke([
    new HumanMessage("서울 날씨 어때?"),
  ]);

  console.log("응답 타입      :", response.constructor.name); // AIMessage
  console.log("response.text  :", JSON.stringify(response.text));
  console.log("tool_calls     :", JSON.stringify(response.tool_calls, null, 2));

  // 핵심: 여기서 날씨는 아직 조회되지 않았습니다. WEATHER_DB 는 열린 적도 없습니다.
  // 모델이 한 일은 "get_weather 를 이런 인자로 불러줘" 라는 '요청'을 만든 것뿐입니다.
  console.log(
    "\n실제로 도구를 실행한 사람은? → 아무도 없음. 실행은 우리(애플리케이션) 몫입니다.",
  );
}

/* ===== [7-2] 손으로 만드는 ReAct 루프 ===== */
async function section7_2(): Promise<void> {
  console.log("\n=== [7-2] 루프를 펼쳐서 한 바퀴씩 손으로 돌려보기 ===");

  const modelWithTools = model.bindTools(tools);
  const messages: BaseMessage[] = [
    new HumanMessage("서울 인구와 부산 인구를 더하면 몇 명이야?"),
  ];

  // --- 1바퀴: 모델에게 물어본다 ---
  const ai1 = await modelWithTools.invoke(messages);
  messages.push(ai1); // ★ AIMessage 를 반드시 배열에 넣어야 합니다.
  console.log(
    "\n[1바퀴] 모델이 요청한 도구:",
    (ai1.tool_calls ?? []).map((c) => c.name).join(", ") || "(없음)",
  );

  // --- 1바퀴: 우리가 도구를 실행하고 ToolMessage 로 되돌린다 ---
  for (const toolCall of ai1.tool_calls ?? []) {
    const selected = findTool(toolCall.name);
    const output = await selected.invoke(toolCall.args); // 순수 결과값(문자열)

    // ★ ToolMessage 의 tool_call_id 는 반드시 toolCall.id 와 같아야 합니다.
    //   이게 "어떤 요청에 대한 답인지" 를 잇는 유일한 끈입니다.
    messages.push(
      new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
      }),
    );
    console.log(`         └ 실행 결과: ${output}`);
  }

  // --- 2바퀴: 도구 결과가 붙은 메시지 배열을 통째로 다시 넣는다 ---
  const ai2 = await modelWithTools.invoke(messages);
  messages.push(ai2);
  console.log(
    "\n[2바퀴] 모델이 요청한 도구:",
    (ai2.tool_calls ?? []).map((c) => c.name).join(", ") || "(없음 → 종료 조건)",
  );

  // 인구 두 개를 받았으니 이제 calculate 를 부를 겁니다. 한 바퀴 더 돕니다.
  for (const toolCall of ai2.tool_calls ?? []) {
    const selected = findTool(toolCall.name);
    const output = await selected.invoke(toolCall.args);
    messages.push(
      new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
      }),
    );
    console.log(`         └ 실행 결과: ${output}`);
  }

  const ai3 = await modelWithTools.invoke(messages);
  messages.push(ai3);
  console.log("\n[3바퀴] tool_calls 개수:", (ai3.tool_calls ?? []).length);
  console.log("최종 답변:", ai3.text);

  printMessages(messages, "7-2 최종 메시지 배열");

  // 여기까지가 에이전트의 전부입니다. 이 손동작을 while 로 감싸면 그게 에이전트입니다.
}

/* ===== [7-2 보너스] tool.invoke(toolCall) 지름길 ===== */
async function section7_2_shortcut(): Promise<void> {
  console.log("\n=== [7-2 보너스] tool.invoke 에 toolCall 통째로 넘기기 ===");

  // 인자(args)만 넘기면 → 순수 결과값이 나옵니다.
  const raw = await getWeather.invoke({ city: "서울" });
  console.log("invoke(args)     →", raw.constructor?.name ?? typeof raw, ":", raw);

  // toolCall 객체(= {name, args, id}) 를 통째로 넘기면 → ToolMessage 가 나옵니다.
  const asMessage = await getWeather.invoke({
    name: "get_weather",
    args: { city: "부산" },
    id: "call_demo_123",
    type: "tool_call",
  });
  console.log("invoke(toolCall) →", asMessage.constructor.name);
  console.log("  tool_call_id :", asMessage.tool_call_id);
  console.log("  name         :", asMessage.name);
  console.log("  content      :", asMessage.text);

  // tool_call_id 를 손으로 채우다 틀릴 일이 없으니 실전에서는 이 형태를 씁니다.
}

/* ===== [7-3] 루프 종료 조건 ===== */
async function section7_3(): Promise<void> {
  console.log("\n=== [7-3] 종료 조건은 tool_calls 가 비었는지로만 판단한다 ===");

  const modelWithTools = model.bindTools(tools);

  // 도구가 전혀 필요 없는 질문 → 1바퀴 만에 tool_calls 가 빈 채로 끝납니다.
  const noTool = await modelWithTools.invoke([
    new HumanMessage("안녕! 너는 누구야?"),
  ]);
  console.log("\n[도구 불필요] tool_calls 길이:", (noTool.tool_calls ?? []).length);
  console.log("[도구 불필요] text 있음?     :", noTool.text.length > 0);

  // 도구가 필요한 질문 → tool_calls 가 채워집니다.
  const needTool = await modelWithTools.invoke([
    new HumanMessage("제주 날씨 알려줘"),
  ]);
  console.log("\n[도구 필요]   tool_calls 길이:", (needTool.tool_calls ?? []).length);

  // ★ 함정: text 가 있다고 끝난 게 아닙니다.
  //   많은 모델이 "제주 날씨를 확인해볼게요" 같은 말과 tool_calls 를 '동시에' 보냅니다.
  console.log(
    "[도구 필요]   text 있음?     :",
    needTool.text.length > 0,
    "← text 가 있어도 tool_calls 가 있으면 아직 안 끝났습니다",
  );
  console.log("[도구 필요]   text 내용    :", JSON.stringify(needTool.text));

  // 올바른 종료 판정 — 오직 이것뿐입니다.
  const isDone = (m: AIMessage) => (m.tool_calls ?? []).length === 0;
  console.log("\nisDone(noTool)   =", isDone(noTool), "  ← 종료");
  console.log("isDone(needTool) =", isDone(needTool), "  ← 계속");

  // 틀린 종료 판정 예시 (절대 쓰지 마세요)
  const wrongIsDone = (m: AIMessage) => m.text.length > 0;
  console.log(
    "wrongIsDone(needTool) =",
    wrongIsDone(needTool),
    "  ← text 로 판단하면 도구를 실행도 안 하고 끝내버립니다",
  );
}

/* ===== [7-4] 병렬 도구 호출 ===== */
async function section7_4(): Promise<void> {
  console.log("\n=== [7-4] 한 AIMessage 안에 tool_calls 가 여러 개일 때 ===");

  const modelWithTools = model.bindTools(tools);
  const messages: BaseMessage[] = [
    new HumanMessage("서울, 부산, 제주 인구를 각각 알려줘."),
  ];

  const ai = await modelWithTools.invoke(messages);
  messages.push(ai);
  console.log("tool_calls 개수:", (ai.tool_calls ?? []).length);
  for (const c of ai.tool_calls ?? []) {
    console.log(`  - ${c.name}(${JSON.stringify(c.args)}) id=${c.id}`);
  }

  // (A) 순차 실행 — 도구마다 1초씩 걸리므로 3초 이상 걸립니다.
  const t0 = Date.now();
  const sequential: ToolMessage[] = [];
  for (const toolCall of ai.tool_calls ?? []) {
    const output = await findTool(toolCall.name).invoke(toolCall.args);
    sequential.push(
      new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
      }),
    );
  }
  console.log(`\n(A) 순차 실행: ${Date.now() - t0}ms`);

  // (B) 병렬 실행 — Promise.all 은 입력 배열 순서대로 결과를 돌려주므로
  //     tool_call_id 짝도 자동으로 맞습니다.
  const t1 = Date.now();
  const parallel = await Promise.all(
    (ai.tool_calls ?? []).map(async (toolCall) => {
      const output = await findTool(toolCall.name).invoke(toolCall.args);
      return new ToolMessage({
        content: String(output),
        tool_call_id: toolCall.id!,
        name: toolCall.name,
      });
    }),
  );
  console.log(`(B) 병렬 실행: ${Date.now() - t1}ms  ← 약 1/3`);

  messages.push(...parallel);
  const final = await modelWithTools.invoke(messages);
  console.log("\n최종 답변:", final.text);

  // ★ 함정: "첫 번째 tool_call 은 서울이겠지" 라고 순서를 가정하면 안 됩니다.
  //   짝을 잇는 것은 배열 순서가 아니라 tool_call_id 입니다.
  console.log("\n[검산] 순차 결과와 병렬 결과의 id 순서가 같은가?");
  console.log("  순차:", sequential.map((m) => m.tool_call_id).join(", "));
  console.log("  병렬:", parallel.map((m) => m.tool_call_id).join(", "));
}

/* ===== [7-5] 무한 루프 방어 ===== */
async function section7_5(): Promise<void> {
  console.log("\n=== [7-5] 최대 반복 횟수 — recursionLimit 의 정체 ===");

  const modelWithTools = model.bindTools(tools);
  const MAX_ITERATIONS = 5; // 상한이 없으면 API 비용이 무한대로 갑니다.

  const messages: BaseMessage[] = [
    new HumanMessage("서울과 부산과 제주 인구를 모두 더하면?"),
  ];

  let iteration = 0;
  let finished = false;

  while (iteration < MAX_ITERATIONS) {
    iteration += 1;
    const ai = await modelWithTools.invoke(messages);
    messages.push(ai);
    console.log(
      `[${iteration}/${MAX_ITERATIONS}바퀴] tool_calls=${(ai.tool_calls ?? []).length}`,
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

  // ★ while 을 빠져나온 이유가 두 가지입니다. 이걸 구분하지 않으면
  //   "상한에 걸려 강제 종료된 것"을 "정상 완료"로 착각합니다.
  if (!finished) {
    console.log(
      `→ 상한 도달! ${MAX_ITERATIONS}바퀴를 다 썼는데 아직 tool_calls 가 남아 있습니다.`,
    );
    console.log("  LangGraph 였다면 여기서 GraphRecursionError 가 던져집니다.");
  }

  console.log(`\n실제 사용한 바퀴 수: ${iteration}, 정상 종료 여부: ${finished}`);

  // 참고: createAgent 의 recursionLimit 기본값은 25 이고, 이건 "바퀴 수"가 아니라
  //      "그래프 super-step 수"입니다. 한 바퀴 = 모델 노드 + 도구 노드 = 2 step 이라
  //      25 는 대략 12바퀴에 해당합니다. 7-7 에서 실제로 걸어봅니다.
}

/* ===== [7-6] 에러 처리 ===== */
async function section7_6(): Promise<void> {
  console.log("\n=== [7-6] 도구가 던진 에러를 ToolMessage 로 모델에게 돌려주기 ===");

  // (A) 방어 없이: 도구가 throw 하면 루프 전체가 폭발합니다.
  console.log("\n(A) try/catch 없이 도구를 부르면:");
  try {
    await getWeather.invoke({ city: "도쿄" });
  } catch (error) {
    console.log("  → 던져진 에러:", (error as Error).message);
    console.log("  → 이 예외가 while 루프를 뚫고 나가면 대화가 통째로 죽습니다.");
  }

  // (B) 에러를 ToolMessage 로 감싸서 모델에게 되돌려주면, 모델이 스스로 고칩니다.
  console.log("\n(B) 에러를 ToolMessage(status: 'error') 로 돌려주면:");
  const modelWithTools = model.bindTools(tools);
  const messages: BaseMessage[] = [
    new SystemMessage(
      "너는 날씨 비서다. 도구가 에러를 돌려주면 사용자에게 사과하고 가능한 대안을 안내해라.",
    ),
    new HumanMessage("도쿄 날씨 알려줘"),
  ];

  for (let i = 0; i < 4; i++) {
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
          // ★ 핵심: 에러도 '결과'입니다. 반드시 tool_call_id 를 채워서 돌려줘야
          //   메시지 배열이 짝을 이루고, 모델이 다음 수를 둘 수 있습니다.
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
      `  [${i + 1}바퀴] ${toolMessages.map((m) => `${m.name}:${m.status}`).join(", ")}`,
    );
  }

  printMessages(messages, "7-6 최종 메시지 배열");

  // status: "error" 는 메시지 객체에 남는 표시일 뿐, 모델이 읽는 것은 content 입니다.
  // 그래서 content 에 "무엇이 왜 실패했고 어떻게 고칠 수 있는지" 를 적어야 모델이 회복합니다.
}

/* ===== [7-7] 미니 에이전트 vs createAgent ===== */

/**
 * ~80줄짜리 미니 에이전트. 7-2 ~ 7-6 에서 배운 것을 전부 합쳤습니다.
 * createAgent 가 하는 일의 뼈대가 정확히 이것입니다.
 */
async function runAgent(options: {
  input: string;
  systemPrompt?: string;
  maxIterations?: number;
  verbose?: boolean;
}): Promise<{ messages: BaseMessage[]; output: string; iterations: number }> {
  const { input, systemPrompt, maxIterations = 10, verbose = false } = options;

  // 1) 모델에 도구 스펙을 붙인다.
  const modelWithTools = model.bindTools(tools);

  // 2) 대화 상태 = 메시지 배열. 이게 에이전트의 전체 기억입니다.
  const messages: BaseMessage[] = [];
  if (systemPrompt) messages.push(new SystemMessage(systemPrompt));
  messages.push(new HumanMessage(input));

  // 3) 루프.
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

    // 4) 종료 조건 — tool_calls 가 비었으면 끝. text 유무로 판단하지 않습니다.
    if (toolCalls.length === 0) {
      return { messages, output: ai.text, iterations: iteration };
    }

    // 5) 도구를 병렬로 실행하고, 결과를 ToolMessage 로 되돌린다.
    const toolMessages = await Promise.all(
      toolCalls.map(async (toolCall) => {
        const selected = toolsByName[toolCall.name];

        // 모델이 없는 도구를 지어낼 수도 있습니다. 이것도 에러로 되돌립니다.
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

  // 6) 상한 도달. 조용히 넘어가지 않고 명시적으로 알립니다.
  throw new Error(
    `최대 반복 횟수(${maxIterations})를 넘었습니다. 도구 설명이 부실하거나 모델이 같은 도구를 반복 호출하고 있을 수 있습니다.`,
  );
}

async function section7_7(): Promise<void> {
  console.log("\n=== [7-7] 우리가 만든 루프 vs createAgent ===");

  const question = "서울과 부산의 인구를 더하면 몇 명이야? 계산은 도구로 해줘.";
  const systemPrompt = "너는 도시 정보 비서다. 숫자 계산은 반드시 calculate 도구를 써라.";

  // (A) 우리가 만든 미니 에이전트
  console.log("\n(A) runAgent (우리가 손으로 만든 것)");
  const mine = await runAgent({ input: question, systemPrompt, verbose: true });
  console.log("  → 답변:", mine.output);
  console.log("  → 바퀴 수:", mine.iterations);
  console.log("  → 최종 메시지 개수:", mine.messages.length);

  // (B) createAgent — 같은 모델, 같은 도구, 같은 프롬프트
  console.log("\n(B) createAgent (프레임워크)");
  const agent = createAgent({ model, tools, systemPrompt });
  const result = await agent.invoke({
    messages: [{ role: "user", content: question }],
  });
  const lastMessage = result.messages.at(-1) as AIMessage;
  console.log("  → 답변:", lastMessage.text);
  console.log("  → 최종 메시지 개수:", result.messages.length);

  // 메시지 배열의 '모양'이 같습니다. 이름만 다를 뿐 같은 루프입니다.
  printMessages(mine.messages, "(A) runAgent 의 메시지");
  printMessages(result.messages as BaseMessage[], "(B) createAgent 의 메시지");

  console.log(
    "\n두 메시지 배열의 타입 시퀀스가 같은지 비교:",
  );
  const shape = (ms: BaseMessage[]) =>
    ms.map((m) => m.constructor.name.replace("Message", "")).join(" → ");
  console.log("  (A)", shape(mine.messages));
  console.log("  (B)", shape(result.messages as BaseMessage[]));
}

/* ===== [7-7 보너스] recursionLimit 를 실제로 터뜨려 보기 ===== */
async function section7_7_recursion(): Promise<void> {
  console.log("\n=== [7-7 보너스] recursionLimit 를 1로 두면 ===");

  const agent = createAgent({ model, tools });

  try {
    // recursionLimit 은 configurable 안이 아니라 config 최상위에 둡니다.
    await agent.invoke(
      { messages: [{ role: "user", content: "서울과 부산 인구를 더해줘" }] },
      { recursionLimit: 1 },
    );
    console.log("(예상과 달리 성공했습니다 — 모델이 도구를 안 불렀을 수 있습니다)");
  } catch (error) {
    // GraphRecursionError. 우리 runAgent 가 던지던 그 에러의 프레임워크 버전입니다.
    console.log("에러 이름   :", (error as Error).name);
    console.log("에러 메시지 :", (error as Error).message);
  }
}

/* ===== 실행 ===== */
await section7_1();
await section7_2();
await section7_2_shortcut();
await section7_3();
await section7_4();
await section7_5();
await section7_6();
await section7_7();
await section7_7_recursion();

console.log("\n=== Step 07 practice 끝 ===");
