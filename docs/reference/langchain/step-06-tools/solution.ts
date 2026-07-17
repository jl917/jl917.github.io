/**
 * Step 06 — 도구(Tool) 정의 : 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-06-tools/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 각 정답 위 주석에 "왜 이렇게 하는가" 와 함정 포인트를 적어두었습니다.
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

/* ===== [정답 1] convert_temperature ===== */
/**
 * 포인트
 * - z.enum(["C", "F"]) 를 쓰면 모델이 "celsius", "섭씨" 같은 걸 지어낼 수 없습니다.
 *   z.string() 이었다면 모델이 뭘 넣을지 알 수 없고, 우리 코드가 그 변형을 다 처리해야 합니다.
 *   **스키마로 좁힐 수 있는 건 스키마로 좁히세요.** 프롬프트로 부탁하는 것보다 확실합니다.
 * - .describe() 는 모델에게 가는 문서입니다. 개발자용 주석이 아닙니다.
 * - 반환은 문자열. 모델은 결국 텍스트를 읽으므로 단위를 명시하는 게 안전합니다.
 */
const convertTemperature = tool(
  async ({ value, from }) => {
    if (from === "C") {
      const f = (value * 9) / 5 + 32;
      return `${value}°C = ${f.toFixed(1)}°F`;
    }
    const c = ((value - 32) * 5) / 9;
    return `${value}°F = ${c.toFixed(1)}°C`;
  },
  {
    name: "convert_temperature",
    description:
      "섭씨와 화씨 사이의 온도를 변환합니다. 사용자가 온도 단위 변환을 요청하면 사용하세요.",
    schema: z.object({
      value: z.number().describe("변환할 온도 값. 단위는 from 필드로 지정합니다."),
      from: z
        .enum(["C", "F"])
        .describe("입력 값의 단위. 'C'=섭씨(이 경우 화씨로 변환), 'F'=화씨(섭씨로 변환)"),
    }),
  },
);

async function solution1() {
  section("[정답 1] convert_temperature");

  console.log(await convertTemperature.invoke({ value: 25, from: "C" }));
  console.log(await convertTemperature.invoke({ value: 77, from: "F" }));

  // enum 밖의 값은 도구에 도달하기 전에 막힙니다.
  try {
    await convertTemperature.invoke({ value: 25, from: "켈빈" } as never);
  } catch (err) {
    console.log("enum 위반은 실행 전에 차단:", (err as Error).message.split("\n")[0]);
  }
}

/* ===== [정답 2] description 고치기 ===== */
/**
 * 나쁜 description 의 문제는 "틀린 정보" 가 아니라 "정보가 없는 것" 입니다.
 * 모델 입장에서 name="info" / description="정보를 반환합니다." 는
 * 세상 모든 질문에 해당하거나 아무 질문에도 해당하지 않습니다. 그래서 안 부릅니다.
 *
 * 좋은 description 의 3요소:
 *  1) 무엇을 하는가  — 반환값의 내용과 형식까지
 *  2) 언제 쓰는가    — 트리거가 되는 사용자 발화를 구체적으로
 *  3) 제약           — 못 하는 것, 전제 조건, 다른 도구와의 순서
 */
const mysteryTool = tool(async ({ line }) => `${line}호선 첫차 05:30`, {
  name: "info",
  description: "정보를 반환합니다.",
  schema: z.object({ line: z.string() }),
});

const subwayFirstTrain = tool(
  async ({ line }) => `서울 지하철 ${line}호선 첫차: 평일 05:30, 주말 05:40`,
  {
    name: "get_subway_first_train",
    description: [
      // 1) 무엇을
      "서울 지하철 특정 호선의 첫차 출발 시각을 조회합니다. 평일/주말 시각을 함께 반환합니다.",
      // 2) 언제
      "사용자가 지하철 첫차, 시작 시간, 몇 시부터 운행하는지를 물으면 사용하세요.",
      // 3) 제약
      "서울 지하철 1~9호선만 지원하며, 막차 시각과 역별 시각표는 제공하지 않습니다.",
    ].join(" "),
    schema: z.object({
      line: z.string().describe("지하철 호선 번호. '1'~'9' 중 하나. 예: 2호선이면 '2'"),
    }),
  },
);

async function solution2() {
  section("[정답 2] description 고쳐서 모델이 부르게 만들기");

  const model = await initChatModel(MODEL_ID);
  const q = "서울 지하철 2호선 첫차 시간 알려줘";

  const before = await model.bindTools([mysteryTool]).invoke([{ role: "user", content: q }]);
  console.log("\n[고치기 전] name=info");
  console.log("  tool_calls:", before.tool_calls?.length ?? 0);
  console.log("  content   :", JSON.stringify(before.content).slice(0, 140));

  const after = await model.bindTools([subwayFirstTrain]).invoke([{ role: "user", content: q }]);
  console.log("\n[고친 후] name=get_subway_first_train");
  console.log("  tool_calls:", after.tool_calls?.length ?? 0);
  console.log("  args      :", JSON.stringify(after.tool_calls?.[0]?.args));

  console.log(
    "\n해설: 함수 본문은 사실상 같습니다. 바뀐 건 문자열 두 개(name, description)뿐인데\n" +
      "모델의 행동이 달라집니다. description 은 주석이 아니라 프롬프트입니다.",
  );
}

/* ===== [정답 3] 병렬 tool_calls 와 toolChoice ===== */
const getWeather = tool(async ({ city }) => `${city}: 맑음, 24도`, {
  name: "get_weather",
  description: "지정한 도시의 현재 날씨를 조회합니다.",
  schema: z.object({ city: z.string().describe("도시 이름. 예: 서울") }),
});

const getTime = tool(async ({ timezone }) => new Date().toLocaleString("ko-KR", { timeZone: timezone }), {
  name: "get_time",
  description: "지정한 타임존의 현재 시각을 조회합니다.",
  schema: z.object({ timezone: z.string().describe("IANA 타임존. 예: Asia/Seoul") }),
});

async function solution3() {
  section("[정답 3] 병렬 tool_calls 와 toolChoice");

  const model = await initChatModel(MODEL_ID);

  const parallel = await model
    .bindTools([getWeather, getTime])
    .invoke([{ role: "user", content: "서울 날씨랑 지금 시각 알려줘" }]);

  console.log("\n(1) 자유 선택");
  console.log("  tool_calls 개수:", parallel.tool_calls?.length ?? 0);
  for (const c of parallel.tool_calls ?? []) {
    console.log(`  - id=${c.id}\n    name=${c.name}\n    args=${JSON.stringify(c.args)}`);
  }
  console.log(
    "  → 대개 2개가 한 AIMessage 에 함께 옵니다. 이게 '병렬 도구 호출' 입니다.\n" +
      "    ⚠️ 배열 순서는 모델이 정한 것일 뿐, 실행 순서나 의존 관계를 뜻하지 않습니다.",
  );

  const forced = await model
    .bindTools([getWeather, getTime], { toolChoice: "get_weather" })
    .invoke([{ role: "user", content: "서울 날씨랑 지금 시각 알려줘" }]);

  console.log("\n(2) toolChoice: 'get_weather' 로 강제");
  console.log("  tool_calls:", JSON.stringify(forced.tool_calls?.map((c) => c.name)));
  console.log(
    "  → get_weather 만 남습니다. toolChoice 는 '이번 한 번' 의 강제이며,\n" +
      "    루프에서 계속 걸어두면 모델이 최종 답변을 못 내고 무한히 도구만 부릅니다.",
  );
}

/* ===== [정답 4] 에러를 모델에게 돌려주기 ===== */
/**
 * 핵심: tool_call_id 를 반드시 채워야 하는 이유
 * -----------------------------------------------
 * AIMessage 에 tool_calls 가 있으면 provider 는 "그 각각에 대한 ToolMessage 가 있어야 한다"
 * 고 요구합니다. 하나라도 빠지거나 id 가 안 맞으면 다음 invoke 에서 400 에러가 납니다.
 * 즉 도구가 실패해도 **ToolMessage 는 반드시 채워 보내야** 대화가 유지됩니다.
 * "에러니까 그냥 건너뛰자" 가 대화를 깨뜨리는 전형적인 실수입니다.
 */
const divideSafe = tool(
  async ({ a, b }) => {
    try {
      if (b === 0) throw new Error("0으로 나눌 수 없습니다");
      return String(a / b);
    } catch (err) {
      // 나쁜 예: return "에러"  ← 모델이 뭘 해야 할지 모른다
      // 좋은 예: 원인 + 다음 행동 지시
      return `오류: ${(err as Error).message}. b 에는 0이 아닌 수를 넣어야 합니다. 사용자에게 0으로 나눌 수 없음을 설명하세요.`;
    }
  },
  {
    name: "divide",
    description:
      "a를 b로 나눈 몫을 반환합니다. b가 0이면 '오류:' 로 시작하는 메시지를 반환합니다.",
    schema: z.object({
      a: z.number().describe("피제수"),
      b: z.number().describe("제수. 0이 아니어야 합니다."),
    }),
  },
);

async function solution4() {
  section("[정답 4] 에러를 모델에게 돌려주기");

  const model = await initChatModel(MODEL_ID);
  const bound = model.bindTools([divideSafe]);

  const messages: unknown[] = [{ role: "user", content: "10을 0으로 나눠줘" }];

  const ai = await bound.invoke(messages as never);
  messages.push(ai);
  console.log("\n1차 tool_calls:", JSON.stringify(ai.tool_calls?.map((c) => c.args)));

  for (const call of ai.tool_calls ?? []) {
    // tool.invoke(call) 에 ToolCall 객체를 통째로 넘기면
    // tool_call_id / name 이 자동으로 채워진 ToolMessage 가 나옵니다. 실수 여지가 줄어듭니다.
    const tm = await divideSafe.invoke(call);
    console.log("ToolMessage    :", tm.content);
    console.log("  tool_call_id :", tm.tool_call_id, "← AIMessage 의 id 와 일치해야 함");
    messages.push(tm);
  }

  const final = await bound.invoke(messages as never);
  console.log("\n모델 최종 답변:", JSON.stringify(final.content).slice(0, 240));
  console.log(
    "\n해설: 도구가 '실패했다'가 아니라 '왜 실패했고 이제 뭘 하라'를 돌려주면\n" +
      "모델이 사용자에게 제대로 설명하거나 스스로 인자를 고쳐 재시도합니다.",
  );

  // 참고: tool_call_id 를 안 맞추면 어떻게 되는지
  const broken = new ToolMessage({ content: "결과", tool_call_id: "존재하지-않는-id" });
  console.log(
    "\n(참고) tool_call_id 가 안 맞는 ToolMessage:",
    broken.tool_call_id,
    "→ 다음 invoke 에서 provider 400 에러",
  );
}

/* ===== [정답 5] artifact 로 컨텍스트 아끼기 ===== */
/**
 * artifact 의 존재 이유: **모델의 컨텍스트와 코드의 데이터를 분리하는 것**입니다.
 * 로그 500줄을 content 에 넣으면 매 턴 그 500줄이 다시 모델에게 전송됩니다.
 * 토큰 비용도 문제지만, 진짜 문제는 모델이 그 안에서 길을 잃는 것입니다.
 *
 * ⚠️ 함정: config.toolCallId 는 createAgent/ToolNode 같은 "에이전트 실행 시스템"이 주입합니다.
 *    아래 solution5() 처럼 tool.invoke(toolCall) 로 직접 부르면 주입되지 않아 undefined 이고,
 *    그러면 tool_call_id 가 빈 ToolMessage 가 만들어져 다음 모델 호출이 400 으로 터집니다.
 *    에러가 그 자리에서 안 나고 "나중에" 터지므로 원인을 찾기 어렵습니다.
 *    config.toolCall?.id 는 직접 호출에서도 채워지므로 둘 다 fallback 으로 받는 게 안전합니다.
 */
const fetchLogs = tool(
  async ({ service, lines }, config: ToolRuntime) => {
    const levels = ["INFO", "INFO", "INFO", "WARN", "ERROR"];
    const logs = Array.from({ length: lines }, (_, i) => {
      const level = levels[i % levels.length];
      return `2026-07-17T10:${String(i % 60).padStart(2, "0")}:00Z ${level} [${service}] message #${i + 1}`;
    });
    const errorCount = logs.filter((l) => l.includes("ERROR")).length;
    const warnCount = logs.filter((l) => l.includes("WARN")).length;

    return new ToolMessage({
      // 모델에게: 요약 + 대표 샘플 몇 줄
      content: [
        `${service} 로그 ${logs.length}줄 수집. ERROR ${errorCount}건, WARN ${warnCount}건.`,
        "ERROR 샘플 3건:",
        ...logs.filter((l) => l.includes("ERROR")).slice(0, 3),
      ].join("\n"),
      // 코드에게: 전문
      artifact: { logs, errorCount, warnCount, service },
      tool_call_id: config.toolCallId ?? config.toolCall?.id ?? "",
      name: "fetch_logs",
    });
  },
  {
    name: "fetch_logs",
    description:
      "지정한 서비스의 최근 로그를 수집해 ERROR/WARN 건수 요약과 대표 샘플을 반환합니다. 장애 원인을 조사할 때 사용하세요.",
    schema: z.object({
      service: z.string().describe("서비스 이름. 예: api-gateway"),
      lines: z.number().int().min(1).max(1000).describe("수집할 로그 줄 수"),
    }),
  },
);

async function solution5() {
  section("[정답 5] artifact 로 컨텍스트 아끼기");

  const tm = await fetchLogs.invoke({
    name: "fetch_logs",
    args: { service: "api-gateway", lines: 500 },
    id: "call_logs_1",
    type: "tool_call",
  });

  const artifact = tm.artifact as { logs: string[]; errorCount: number };
  const contentChars = String(tm.content).length;
  const artifactChars = artifact.logs.join("\n").length;

  console.log("\n--- 모델이 보는 content ---");
  console.log(tm.content);
  console.log("\n--- 크기 비교 ---");
  console.log(`content  : ${contentChars} 자`);
  console.log(`artifact : ${artifactChars} 자 (${artifact.logs.length}줄)`);
  console.log(`절감률   : ${(100 - (contentChars / artifactChars) * 100).toFixed(1)}%`);
  console.log(
    "\n해설: artifact 는 모델에게 전송되지 않습니다. 코드가 `toolMessage.artifact` 로 꺼내\n" +
      "파일에 쓰거나 UI에 렌더링하면 됩니다. '모델이 판단하는 데 필요한 최소 정보만 content 로'가 원칙입니다.",
  );
}

/* ===== [정답 6] 타임아웃 + 재시도 외부 API 도구 ===== */
async function withRetry<T>(
  fn: () => Promise<T>,
  opts: { maxRetries?: number; initialDelayMs?: number } = {},
): Promise<T> {
  const { maxRetries = 2, initialDelayMs = 400 } = opts;
  let lastError: unknown;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err;
      const status = (err as { status?: number }).status;
      // 4xx(429 제외)는 우리 요청이 잘못된 것 — 재시도해도 똑같이 실패한다.
      if (status !== undefined && status >= 400 && status < 500 && status !== 429) throw err;
      if (attempt === maxRetries) break;
      // 지수 백오프 + ±25% 지터. 지터가 없으면 여러 요청이 동시에 몰려 재시도한다(thundering herd).
      const delay = initialDelayMs * 2 ** attempt * (0.75 + Math.random() * 0.5);
      console.log(`  [재시도] ${attempt + 1}회차 실패 → ${Math.round(delay)}ms 대기`);
      await new Promise((r) => setTimeout(r, delay));
    }
  }
  throw lastError;
}

function makeExchangeRateTool(timeoutMs: number) {
  return tool(
    async ({ base, quote }) => {
      try {
        const data = await withRetry(async () => {
          const res = await fetch(`https://api.frankfurter.app/latest?from=${base}&to=${quote}`, {
            signal: AbortSignal.timeout(timeoutMs), // ← 이게 없으면 요청이 영원히 매달릴 수 있다
          });
          if (!res.ok) {
            const e = new Error(`HTTP ${res.status}`) as Error & { status: number };
            e.status = res.status;
            throw e;
          }
          return (await res.json()) as { rates: Record<string, number> };
        });
        const rate = data.rates[quote];
        return rate === undefined
          ? `오류: '${quote}' 환율을 찾을 수 없습니다. ISO 4217 통화코드인지 확인하세요(USD, EUR, JPY 등).`
          : `1 ${base} = ${rate} ${quote}`;
      } catch (err) {
        const e = err as Error;
        const reason = e.name === "TimeoutError" ? `응답 시간 초과(${timeoutMs}ms)` : e.message;
        return `오류: 환율 API 호출 실패 (${reason}). 재시도했으나 실패했습니다. 사용자에게 일시적 장애임을 알리세요.`;
      }
    },
    {
      name: "get_exchange_rate",
      description:
        "두 통화 사이의 최신 환율을 조회합니다. ISO 4217 3자리 통화코드를 사용합니다. 조회 실패 시 '오류:' 로 시작하는 메시지를 반환합니다.",
      schema: z.object({
        base: z.string().length(3).describe("기준 통화코드. 예: USD"),
        quote: z.string().length(3).describe("대상 통화코드. 예: KRW"),
      }),
    },
  );
}

async function solution6() {
  section("[정답 6] 타임아웃 + 재시도 외부 API 도구");

  const normal = makeExchangeRateTool(3000);
  console.log("\n정상 :", await normal.invoke({ base: "USD", quote: "KRW" }));

  // 타임아웃 1ms — 반드시 실패한다. 재시도 로그가 찍히고, 마지막엔 문자열이 나온다(throw 아님).
  const impossible = makeExchangeRateTool(1);
  console.log("\n1ms 타임아웃으로 강제 실패:");
  console.log("  →", await impossible.invoke({ base: "USD", quote: "KRW" }));

  console.log(
    "\n해설: 도구가 문자열을 반환했으므로 에이전트 루프는 계속 돌 수 있습니다.\n" +
      "throw 했다면 이 지점에서 전체가 멈췄을 겁니다.\n" +
      "참고: 재시도를 손으로 안 짜고 싶으면 toolRetryMiddleware(Step 11)를 쓰면 됩니다.\n" +
      "다만 그건 '도구가 throw 한 경우' 를 재시도하므로, 이 도구처럼 오류를 흡수하면\n" +
      "미들웨어가 개입할 여지가 없습니다. 둘 중 하나만 고르세요.",
  );
}

/* ===== [정답 7] 만능 도구 쪼개기 ===== */
const calendarGod = tool(async ({ op, data }) => `${op}: ${JSON.stringify(data)}`, {
  name: "calendar",
  description: "캘린더 작업을 합니다.",
  schema: z.object({
    op: z.string().describe("수행할 작업"),
    data: z.record(z.string(), z.unknown()).describe("작업 데이터"),
  }),
});

const listEvents = tool(
  async ({ date }) => JSON.stringify([{ id: "e1", title: "스프린트 회고", start: `${date}T14:00` }]),
  {
    name: "list_events",
    description:
      "특정 날짜의 일정 목록을 조회합니다. 새 일정을 잡기 전에 겹치는 일정이 있는지 확인할 때도 사용하세요.",
    schema: z.object({ date: z.string().describe("조회할 날짜. YYYY-MM-DD 형식") }),
  },
);

const createEvent = tool(
  async ({ title, start, durationMinutes }) =>
    JSON.stringify({ id: "e2", title, start, durationMinutes, status: "CREATED" }),
  {
    name: "create_event",
    description:
      "새 일정을 생성합니다. 실제로 캘린더에 기록되는 작업이므로 사용자가 명시적으로 요청했을 때만 사용하세요. 시간이 애매하면 먼저 사용자에게 확인하세요.",
    schema: z.object({
      title: z.string().describe("일정 제목"),
      start: z.string().describe("시작 시각. ISO 8601 형식. 예: 2026-07-18T15:00:00+09:00"),
      durationMinutes: z.number().int().min(5).max(480).describe("일정 길이(분). 5~480"),
    }),
  },
);

const deleteEvent = tool(async ({ eventId }) => JSON.stringify({ eventId, status: "DELETED" }), {
  name: "delete_event",
  description:
    "일정을 삭제합니다. 되돌릴 수 없으므로 사용자가 삭제를 명확히 요청했을 때만 사용하세요. 일정 ID는 list_events 로 먼저 확인하세요.",
  schema: z.object({ eventId: z.string().describe("삭제할 일정 ID. list_events 의 id 필드") }),
});

async function solution7() {
  section("[정답 7] 만능 도구 쪼개기");

  const model = await initChatModel(MODEL_ID);
  const q = "내일 오후 3시에 팀 회의 1시간 잡아줘";
  const today = new Date().toISOString().slice(0, 10);
  const sys = { role: "system", content: `오늘은 ${today} 입니다. 타임존은 Asia/Seoul.` };

  const god = await model.bindTools([calendarGod]).invoke([sys, { role: "user", content: q }] as never);
  console.log("\n[만능 도구]");
  console.log("  args:", JSON.stringify(god.tool_calls?.[0]?.args));
  console.log(
    "  → op 에 뭘 넣을지 모델이 '지어냅니다'. create/add/createEvent/새일정… 매번 다릅니다.\n" +
      "    우리 코드는 그 변형을 전부 if 문으로 받아내야 하고, data 안의 필드명도 보장이 없습니다.\n" +
      "    스키마 검증이 사실상 무력화된 상태입니다.",
  );

  const split = await model
    .bindTools([listEvents, createEvent, deleteEvent])
    .invoke([sys, { role: "user", content: q }] as never);
  console.log("\n[목적별 도구 3개]");
  for (const c of split.tool_calls ?? []) {
    console.log(`  ${c.name}(${JSON.stringify(c.args)})`);
  }
  console.log(
    "  → 이름이 곧 의도이고, 스키마가 곧 계약입니다.\n" +
      "    durationMinutes 가 min(5).max(480) 이므로 말도 안 되는 값이 애초에 못 들어옵니다.",
  );
}

/* ===== [정답 8] 도구 이름 감별 ===== */
/**
 * 두 가지를 구분하는 게 이 문제의 핵심입니다.
 *  (a) 형식 문제 — provider 가 400 으로 거부. 즉시 터지므로 오히려 발견하기 쉽다.
 *  (b) 의미 문제 — 에러는 안 나지만 모델이 잘못 고른다. 조용히 틀리므로 훨씬 위험하다.
 *
 * 대부분의 provider 는 도구 이름을 [a-zA-Z0-9_-] 로 제한합니다.
 * 하이픈은 통과하는 provider 도 있지만 안 되는 곳도 있어 이식성이 떨어집니다.
 * → 팀 규칙은 그냥 snake_case 로 통일하는 게 답입니다.
 */
async function solution8() {
  section("[정답 8] 도구 이름 감별하기");

  const rows: Array<{ 이름: string; 형식: string; 의미: string; 고친이름: string }> = [
    {
      이름: "get weather",
      형식: "NG — 공백 불허, provider 400",
      의미: "-",
      고친이름: "get_weather",
    },
    {
      이름: "getWeather",
      형식: "OK — 통과는 된다",
      의미: "△ 관례 이탈",
      고친이름: "get_weather",
    },
    {
      이름: "get-weather",
      형식: "△ — provider 마다 다름(이식성 낮음)",
      의미: "-",
      고친이름: "get_weather",
    },
    {
      이름: "날씨_조회",
      형식: "NG — 비ASCII, provider 400",
      의미: "-",
      고친이름: "get_weather",
    },
    {
      이름: "search_products",
      형식: "OK",
      의미: "OK — 동사+목적어",
      고친이름: "(그대로)",
    },
    {
      이름: "tool1",
      형식: "OK",
      의미: "NG — 아무 의미 없음. 모델이 절대 못 고름",
      고친이름: "무슨 일 하는지에 따라 명명",
    },
    {
      이름: "do_it",
      형식: "OK",
      의미: "NG — 'it' 이 뭔지 모델이 알 수 없음",
      고친이름: "구체적 동사+목적어로",
    },
    {
      이름: "list_customer_orders_v2",
      형식: "OK",
      의미: "△ — 'v2' 는 모델에게 무의미한 노이즈. v1 도 붙어있으면 모델이 헷갈림",
      고친이름: "list_customer_orders (구버전은 제거)",
    },
  ];

  console.table(rows);

  console.log(
    "\n핵심 정리\n" +
      "  - 형식 위반(공백/한글/특수문자)은 provider 가 400 으로 거부합니다. 즉시 터집니다.\n" +
      "  - 의미 위반(tool1, do_it, v2)은 에러가 안 납니다. 모델이 조용히 안 부르거나 잘못 부릅니다.\n" +
      "  - 이름은 모델이 읽는 첫 번째 힌트입니다. 'snake_case 동사_목적어' 를 규칙으로 삼으세요.\n" +
      "  - 버전 접미사는 두지 마세요. 도구 2개가 비슷하면 모델은 반드시 둘 중 하나를 잘못 고릅니다.",
  );
}

async function main() {
  await solution1();
  await solution2();
  await solution3();
  await solution4();
  await solution5();
  await solution6();
  await solution7();
  await solution8();
}

main().catch((err) => {
  console.error("실행 실패:", err);
  process.exit(1);
});
