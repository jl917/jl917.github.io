/**
 * Step 06 — 도구(Tool) 정의
 * 실행: npx tsx docs/reference/langchain/step-06-tools/practice.ts
 *
 * 이 파일은 본문 6-1 ~ 6-10 의 예제를 순서대로 담았습니다.
 * 블록 주석 [6-N] 이 본문 소제목 번호와 1:1 로 대응합니다.
 *
 * 필요 환경변수: ANTHROPIC_API_KEY
 * (OpenAI 로 바꾸려면 MODEL_ID 를 "openai:gpt-5.5" 로, OPENAI_API_KEY 를 설정)
 */
import "dotenv/config";
import { initChatModel, tool, ToolMessage, type ToolRuntime } from "langchain";
import type { StructuredToolInterface } from "@langchain/core/tools";
import * as z from "zod";

const MODEL_ID = process.env.MODEL_ID ?? "anthropic:claude-sonnet-4-6";

/** 절 구분용 출력 헬퍼 */
function section(title: string) {
  console.log("\n" + "=".repeat(70));
  console.log(title);
  console.log("=".repeat(70));
}

/* ===== [6-1] 모델은 도구를 실행하지 않는다 ===== */
/**
 * 이 절의 목적은 딱 하나입니다:
 * "모델에게 도구를 줬는데 왜 함수가 안 불렸지?" 를 눈으로 확인시키는 것.
 *
 * 아래 도구의 본문에는 console.log 가 있습니다.
 * 6-1 을 실행했을 때 이 로그가 찍히지 않는다면, 모델은 함수를 실행한 게 아니라
 * "이 함수를 이런 인자로 불러줘" 라는 JSON 만 뱉은 것입니다.
 */
let sideEffectCounter = 0;

const addTool = tool(
  ({ a, b }) => {
    sideEffectCounter += 1; // ← 실제로 실행되면 이 값이 올라간다
    console.log(`   [실제 함수 실행됨] add(${a}, ${b})`);
    return String(a + b);
  },
  {
    name: "add",
    description: "두 정수를 더한 값을 반환합니다.",
    schema: z.object({
      a: z.number().describe("첫 번째 정수"),
      b: z.number().describe("두 번째 정수"),
    }),
  },
);

async function step6_1() {
  section("[6-1] 모델은 도구를 실행하지 않는다 — JSON 만 뱉는다");

  const model = await initChatModel(MODEL_ID);
  const modelWithTools = model.bindTools([addTool]);

  sideEffectCounter = 0;
  const response = await modelWithTools.invoke([
    { role: "user", content: "17 더하기 25 는 얼마야?" },
  ]);

  console.log("\n-- 모델 응답 --");
  console.log("content   :", JSON.stringify(response.content));
  console.log("tool_calls:", JSON.stringify(response.tool_calls, null, 2));
  console.log("\n실제 add() 가 실행된 횟수 =", sideEffectCounter, "  ← 0 입니다!");
  console.log("모델은 '불러라' 라고 말만 했을 뿐, 실행은 우리 코드의 책임입니다.");

  // 우리가 직접 실행해야 비로소 함수가 돈다.
  const call = response.tool_calls?.[0];
  if (call) {
    console.log("\n-- 우리가 직접 실행 --");
    const result = await addTool.invoke(call.args as { a: number; b: number });
    console.log("결과 :", result);
    console.log("실행 횟수 =", sideEffectCounter, "  ← 이제 1");
  }
}

/* ===== [6-2] tool() 로 도구 만들기 ===== */
/**
 * tool(fn, { name, description, schema }) 세 가지가 도구의 전부입니다.
 * - fn          : 실제로 일하는 함수. 첫 인자는 schema 로 파싱된 입력.
 * - name        : 모델이 부를 이름. snake_case.
 * - description : 모델이 "언제 부를지" 판단하는 유일한 근거.
 * - schema      : zod 스키마. .describe() 로 각 필드도 설명한다.
 */
const searchProducts = tool(
  async ({ query, limit }) => {
    // 실제로는 DB/검색엔진을 호출할 자리. 여기선 고정 데이터로 흉내낸다.
    const db = [
      { id: 1, name: "게이밍 노트북 RTX4060", price: 2190000, stock: 3 },
      { id: 2, name: "27인치 4K 모니터", price: 459000, stock: 12 },
      { id: 3, name: "인체공학 사무용 의자", price: 329000, stock: 0 },
      { id: 4, name: "기계식 키보드 청축", price: 89000, stock: 40 },
    ];
    const hits = db.filter((p) => p.name.includes(query)).slice(0, limit);
    return JSON.stringify(hits);
  },
  {
    name: "search_products",
    description:
      "상품명 부분 일치로 상품 카탈로그를 검색합니다. 상품 ID, 이름, 가격(원), 재고 수량을 반환합니다.",
    schema: z.object({
      query: z.string().describe("상품명에 포함될 검색어. 예: '노트북', '모니터'"),
      limit: z.number().int().min(1).max(20).describe("최대 반환 개수. 기본 5."),
    }),
  },
);

async function step6_2() {
  section("[6-2] tool() 로 도구 만들기");

  // 도구는 모델 없이도 그냥 함수처럼 부를 수 있다. 테스트할 때 이렇게 한다.
  const raw = await searchProducts.invoke({ query: "노트북", limit: 5 });
  console.log("직접 호출 결과:", raw);

  // 모델이 보는 것은 name/description/schema 뿐이다.
  console.log("\n-- 모델에게 전달되는 메타데이터 --");
  console.log("name       :", searchProducts.name);
  console.log("description:", searchProducts.description);

  // 스키마 검증은 도구 실행 전에 일어난다. 틀린 입력은 여기서 막힌다.
  try {
    // limit 은 number 인데 문자열을 넣어본다.
    await searchProducts.invoke({ query: "의자", limit: "많이" } as never);
  } catch (err) {
    console.log("\n스키마 검증 실패(정상):", (err as Error).message.split("\n")[0]);
  }
}

/* ===== [6-3] description 이 곧 프롬프트다 ===== */
/**
 * 같은 함수, 같은 스키마. description 만 다른 두 도구를 놓고
 * 모델이 부르는지 안 부르는지를 비교합니다.
 */
const stockPriceBad = tool(async ({ ticker }) => `${ticker}: 71800`, {
  name: "get_data",
  description: "데이터를 가져옵니다.", // ← 나쁜 설명: 언제 쓰는지 알 수 없다
  schema: z.object({ ticker: z.string() }),
});

const stockPriceGood = tool(async ({ ticker }) => `${ticker}: 71800`, {
  name: "get_stock_price",
  description: [
    "한국거래소(KRX) 상장 종목의 현재 주가를 원(KRW) 단위로 조회합니다.",
    "사용자가 특정 회사의 주가·시세·주식 가격을 물어보면 이 도구를 사용하세요.",
    "종목코드(6자리 숫자)를 알아야 하며, 회사 이름만 주어졌다면 먼저 사용자에게 종목코드를 물어보세요.",
    "장 마감 후에는 종가를 반환합니다. 해외 주식은 지원하지 않습니다.",
  ].join(" "),
  schema: z.object({
    ticker: z
      .string()
      .regex(/^\d{6}$/)
      .describe("KRX 6자리 종목코드. 예: 삼성전자=005930, SK하이닉스=000660"),
  }),
});

async function step6_3() {
  section("[6-3] description 이 곧 프롬프트다 — 나쁜 설명 vs 좋은 설명");

  const model = await initChatModel(MODEL_ID);
  const question = "삼성전자(005930) 지금 주가 얼마야?";

  const bad = await model.bindTools([stockPriceBad]).invoke([
    { role: "user", content: question },
  ]);
  console.log("\n[나쁜 설명] name=get_data / description='데이터를 가져옵니다.'");
  console.log("  tool_calls 개수:", bad.tool_calls?.length ?? 0);
  console.log("  content        :", JSON.stringify(bad.content).slice(0, 160));

  const good = await model.bindTools([stockPriceGood]).invoke([
    { role: "user", content: question },
  ]);
  console.log("\n[좋은 설명] name=get_stock_price / 언제·무엇을·제약까지 명시");
  console.log("  tool_calls 개수:", good.tool_calls?.length ?? 0);
  console.log("  args           :", JSON.stringify(good.tool_calls?.[0]?.args));
}

/* ===== [6-4] bindTools 와 AIMessage.tool_calls 관찰 ===== */
const getWeather = tool(
  async ({ city }) => `${city}: 맑음, 24도`,
  {
    name: "get_weather",
    description: "지정한 도시의 현재 날씨를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시 이름. 예: 서울, 부산") }),
  },
);

async function step6_4() {
  section("[6-4] bindTools 와 AIMessage.tool_calls 관찰");

  const model = await initChatModel(MODEL_ID);

  // (1) 도구를 붙여도 필요 없으면 안 부른다.
  const noCall = await model.bindTools([getWeather]).invoke([
    { role: "user", content: "안녕! 자기소개 한 줄만 해줘." },
  ]);
  console.log("\n(1) 도구가 필요 없는 질문");
  console.log("    tool_calls:", noCall.tool_calls?.length ?? 0);
  console.log("    content   :", JSON.stringify(noCall.content).slice(0, 120));

  // (2) 병렬 도구 호출 — tool_calls 배열에 여러 개가 한 번에 온다.
  const parallel = await model.bindTools([getWeather]).invoke([
    { role: "user", content: "서울, 부산, 제주 날씨를 한 번에 알려줘." },
  ]);
  console.log("\n(2) 병렬 호출");
  console.log("    tool_calls 개수:", parallel.tool_calls?.length ?? 0);
  for (const c of parallel.tool_calls ?? []) {
    console.log(`    - id=${c.id} name=${c.name} args=${JSON.stringify(c.args)}`);
  }

  // (3) toolChoice: "any" — 무조건 도구를 부르게 강제한다.
  const forced = await model.bindTools([getWeather], { toolChoice: "any" }).invoke([
    { role: "user", content: "안녕! 자기소개 한 줄만 해줘." },
  ]);
  console.log("\n(3) toolChoice: 'any' 로 강제");
  console.log("    tool_calls:", JSON.stringify(forced.tool_calls));

  // (4) 도구를 ToolCall 객체째로 invoke 하면 ToolMessage 가 나온다.
  const call = parallel.tool_calls?.[0];
  if (call) {
    const toolMessage = await getWeather.invoke(call);
    console.log("\n(4) tool.invoke(toolCall) → ToolMessage");
    console.log("    type        :", toolMessage.getType());
    console.log("    content     :", toolMessage.content);
    console.log("    tool_call_id:", toolMessage.tool_call_id);
    console.log("    name        :", toolMessage.name);
  }
}

/* ===== [6-5] 도구 에러 처리 ===== */
/**
 * 도구 안에서 throw 하면 그 예외는 도구를 실행한 사람(=우리 코드/에이전트)에게
 * 그대로 튀어오릅니다. 아무도 안 잡으면 프로세스가 죽습니다.
 */
const flakyTool = tool(
  async ({ orderId }) => {
    if (!/^ORD-\d{4}$/.test(orderId)) {
      throw new Error(`잘못된 주문번호 형식: ${orderId} (ORD-1234 형식이어야 함)`);
    }
    return JSON.stringify({ orderId, status: "SHIPPED" });
  },
  {
    name: "get_order_status",
    description: "주문번호로 배송 상태를 조회합니다.",
    schema: z.object({
      orderId: z.string().describe("주문번호. 'ORD-' + 4자리 숫자 형식"),
    }),
  },
);

/** 방법 A — 도구 안에서 잡아 문자열로 반환: 모델이 스스로 고쳐 재시도할 수 있다 */
const safeOrderTool = tool(
  async ({ orderId }) => {
    try {
      if (!/^ORD-\d{4}$/.test(orderId)) {
        throw new Error(`잘못된 주문번호 형식: ${orderId}`);
      }
      return JSON.stringify({ orderId, status: "SHIPPED" });
    } catch (err) {
      // 모델이 읽고 고칠 수 있게 "무엇이 왜 틀렸고 어떻게 하면 되는지" 를 적는다.
      return `오류: ${(err as Error).message}. 주문번호는 'ORD-' 뒤에 숫자 4자리입니다(예: ORD-1234). 사용자에게 올바른 주문번호를 물어보세요.`;
    }
  },
  {
    name: "get_order_status_safe",
    description: "주문번호로 배송 상태를 조회합니다. 형식이 틀리면 오류 메시지를 반환합니다.",
    schema: z.object({ orderId: z.string().describe("주문번호. 'ORD-' + 4자리 숫자") }),
  },
);

async function step6_5() {
  section("[6-5] 도구 에러 처리 — throw 하면 어떻게 되나");

  // (1) 그냥 throw: 안 잡으면 여기서 프로세스가 죽는다.
  console.log("\n(1) throw 하는 도구를 try/catch 없이 부르면 →");
  try {
    await flakyTool.invoke({ orderId: "12345" });
  } catch (err) {
    console.log("    예외가 호출자에게 튀어나옴:", (err as Error).message);
    console.log("    (이 try/catch 가 없었다면 프로세스 종료)");
  }

  // (2) 도구 안에서 잡아 문자열로 반환
  const msg = await safeOrderTool.invoke({ orderId: "12345" });
  console.log("\n(2) 도구 안에서 잡아 문자열로 반환 →");
  console.log("   ", msg);

  // (3) 모델에게 오류를 돌려주면 스스로 고친다.
  const model = await initChatModel(MODEL_ID);
  const bound = model.bindTools([safeOrderTool]);

  const messages: Array<Record<string, unknown>> = [
    { role: "user", content: "주문 12345 어디까지 왔어?" },
  ];
  const first = await bound.invoke(messages as never);
  messages.push(first as never);

  console.log("\n(3) 모델에게 오류를 ToolMessage 로 돌려주기");
  console.log("    1차 tool_calls:", JSON.stringify(first.tool_calls?.[0]?.args));

  for (const c of first.tool_calls ?? []) {
    const tm = await safeOrderTool.invoke(c);
    messages.push(tm as never);
    console.log("    ToolMessage   :", String(tm.content).slice(0, 80) + "...");
  }

  const second = await bound.invoke(messages as never);
  console.log("    모델의 후속 반응:", JSON.stringify(second.content).slice(0, 200));
}

/* ===== [6-6] 도구 반환값 — 문자열, 객체, artifact ===== */
/** (a) 문자열 — 가장 단순 */
const asString = tool(async ({ city }) => `${city}은 맑고 24도입니다.`, {
  name: "weather_string",
  description: "날씨를 사람이 읽는 문장으로 반환합니다.",
  schema: z.object({ city: z.string() }),
});

/** (b) 객체 — 구조화된 데이터. 모델에게 갈 때 직렬화된다. */
const asObject = tool(
  async ({ city }) => ({ city, tempC: 24, condition: "sunny", humidity: 41 }),
  {
    name: "weather_object",
    description: "날씨를 구조화된 객체로 반환합니다.",
    schema: z.object({ city: z.string() }),
  },
);

/**
 * (c) artifact — 모델에게는 요약만 보내고, 원본은 프로그램이 꺼내 쓴다.
 * ToolMessage 를 직접 만들어 반환하면 content(모델용)와 artifact(코드용)를 분리할 수 있다.
 * tool_call_id 를 채우려면 두 번째 인자로 ToolRuntime 을 받는다.
 *
 * ⚠️ config.toolCallId 는 "에이전트 실행 시스템"(createAgent/ToolNode)이 주입합니다.
 *    아래처럼 tool.invoke(toolCall) 로 직접 부르면 주입되지 않아 undefined 입니다.
 *    대신 config.toolCall.id 에는 값이 들어 있으므로 둘 다 fallback 으로 받습니다.
 *    (?? "" 까지 두는 이유: tool_call_id 는 string 필수라 undefined 면 대화가 깨진다)
 */
const searchWithArtifact = tool(
  async ({ query }, config: ToolRuntime) => {
    const rows = Array.from({ length: 200 }, (_, i) => ({
      id: i + 1,
      title: `${query} 관련 문서 ${i + 1}`,
      body: "…본문 수천 자…",
    }));

    return new ToolMessage({
      // 모델에게 가는 것: 짧은 요약만. 200건 전체를 넣으면 컨텍스트가 터진다.
      content: `'${query}' 검색 결과 ${rows.length}건. 상위 3건: ${rows
        .slice(0, 3)
        .map((r) => r.title)
        .join(", ")}`,
      // 코드가 꺼내 쓰는 것: 원본 전체
      artifact: { rows, total: rows.length },
      tool_call_id: config.toolCallId ?? config.toolCall?.id ?? "",
      name: "search_docs",
    });
  },
  {
    name: "search_docs",
    description: "문서를 검색해 상위 결과 요약을 반환합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

async function step6_6() {
  section("[6-6] 도구 반환값 — 문자열 / 객체 / artifact");

  console.log("\n(a) 문자열 :", await asString.invoke({ city: "서울" }));

  const obj = await asObject.invoke({ city: "서울" });
  console.log("(b) 객체   :", JSON.stringify(obj));

  const tm = await searchWithArtifact.invoke({
    name: "search_docs",
    args: { query: "LangChain" },
    id: "call_demo_1",
    type: "tool_call",
  });
  console.log("\n(c) artifact");
  console.log("    모델이 보는 content :", tm.content);
  const artifact = tm.artifact as { rows: unknown[]; total: number };
  console.log("    코드가 보는 artifact:", `${artifact.total}건 (rows.length=${artifact.rows.length})`);
  console.log("    tool_call_id        :", tm.tool_call_id, "← toolCall.id fallback 덕에 채워짐");
  console.log("    → 컨텍스트에는 한 줄, 원본은 코드가 통째로 보유");
}

/* ===== [6-7] 비동기·외부 API 도구 — 타임아웃과 재시도 ===== */
/** AbortSignal.timeout 으로 매달린 요청을 끊는다. Node 18+ 내장. */
async function fetchWithTimeout(url: string, ms: number): Promise<Response> {
  return fetch(url, { signal: AbortSignal.timeout(ms) });
}

/** 지수 백오프 재시도. 4xx 는 재시도해봐야 소용없으니 즉시 포기한다. */
async function withRetry<T>(
  fn: () => Promise<T>,
  opts: { maxRetries?: number; initialDelayMs?: number } = {},
): Promise<T> {
  const { maxRetries = 2, initialDelayMs = 500 } = opts;
  let lastError: unknown;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err;
      const status = (err as { status?: number }).status;
      if (status !== undefined && status >= 400 && status < 500 && status !== 429) {
        throw err; // 클라이언트 잘못 — 재시도 무의미
      }
      if (attempt === maxRetries) break;
      const delay = initialDelayMs * 2 ** attempt * (0.75 + Math.random() * 0.5); // ±25% 지터
      console.log(`   [재시도] ${attempt + 1}회차 실패, ${Math.round(delay)}ms 후 재시도`);
      await new Promise((r) => setTimeout(r, delay));
    }
  }
  throw lastError;
}

const exchangeRate = tool(
  async ({ base, quote }) => {
    try {
      const data = await withRetry(async () => {
        const res = await fetchWithTimeout(
          `https://api.frankfurter.app/latest?from=${base}&to=${quote}`,
          3000,
        );
        if (!res.ok) {
          const e = new Error(`HTTP ${res.status}`) as Error & { status: number };
          e.status = res.status;
          throw e;
        }
        return (await res.json()) as { rates: Record<string, number> };
      });
      const rate = data.rates[quote];
      return rate === undefined
        ? `오류: ${quote} 환율을 찾을 수 없습니다. 통화코드를 확인하세요(예: USD, EUR, KRW).`
        : `1 ${base} = ${rate} ${quote}`;
    } catch (err) {
      // 외부 API 도구는 반드시 자기 안에서 실패를 흡수한다.
      const reason =
        (err as Error).name === "TimeoutError" ? "응답 시간 초과(3초)" : (err as Error).message;
      return `오류: 환율 API 호출 실패 (${reason}). 잠시 후 다시 시도하거나 사용자에게 알리세요.`;
    }
  },
  {
    name: "get_exchange_rate",
    description:
      "두 통화 사이의 최신 환율을 조회합니다. ISO 4217 통화코드 3자리를 사용합니다. 실패 시 '오류:' 로 시작하는 메시지를 반환합니다.",
    schema: z.object({
      base: z.string().length(3).describe("기준 통화코드. 예: USD"),
      quote: z.string().length(3).describe("대상 통화코드. 예: KRW"),
    }),
  },
);

async function step6_7() {
  section("[6-7] 비동기·외부 API 도구 — 타임아웃과 재시도");

  console.log("\n정상 호출 :", await exchangeRate.invoke({ base: "USD", quote: "KRW" }));
  console.log("잘못된 통화:", await exchangeRate.invoke({ base: "USD", quote: "XXX" }));
  console.log("\n두 경우 모두 throw 하지 않고 문자열을 반환합니다 — 에이전트가 안 죽습니다.");
}

/* ===== [6-8] MCP 도구 연결 맛보기 ===== */
/**
 * MCP 는 별도 패키지가 필요합니다:
 *   npm install @langchain/mcp-adapters
 *
 * 이 프로젝트 기본 의존성에는 없으므로 아래 코드는 주석으로만 둡니다.
 * 실제로 돌려보려면 패키지를 설치하고 주석을 푸세요.
 */
// import { MultiServerMCPClient } from "@langchain/mcp-adapters";
//
// async function step6_8() {
//   section("[6-8] MCP 도구 연결 맛보기");
//
//   const client = new MultiServerMCPClient({
//     // 로컬 프로세스를 띄워 stdio 로 통신
//     filesystem: {
//       transport: "stdio",
//       command: "npx",
//       args: ["-y", "@modelcontextprotocol/server-filesystem", process.cwd()],
//     },
//     // 원격 서버는 http/sse
//     // weather: { transport: "sse", url: "http://localhost:8000/mcp" },
//   });
//
//   // MCP 서버가 노출한 도구들이 LangChain 도구로 변환되어 나온다.
//   const mcpTools = await client.getTools();
//   for (const t of mcpTools) {
//     console.log(`- ${t.name}: ${String(t.description).slice(0, 60)}`);
//   }
//
//   // tool() 로 만든 우리 도구와 그냥 섞어 쓸 수 있다.
//   const model = await initChatModel(MODEL_ID);
//   const bound = model.bindTools([...mcpTools, getWeather]);
//   const res = await bound.invoke([{ role: "user", content: "현재 폴더 파일 목록 보여줘" }]);
//   console.log(res.tool_calls);
//
//   // MCP 도구는 실패 시 ToolException 을 던진다 — try/catch 필수.
// }

async function step6_8() {
  section("[6-8] MCP 도구 연결 맛보기");
  console.log(
    "\n@langchain/mcp-adapters 설치가 필요해 코드는 주석으로 두었습니다.\n" +
      "  npm install @langchain/mcp-adapters\n" +
      "설치 후 practice.ts 의 [6-8] 블록 주석을 풀어 실행하세요.",
  );
}

/* ===== [6-9] 도구 설계 원칙 — 입도(granularity) 비교 ===== */
/** (나쁨) 만능 도구: 무엇을 하는지 모델이 추론해야 한다 */
const godTool = tool(
  async ({ action, payload }) => `${action} 실행: ${JSON.stringify(payload)}`,
  {
    name: "db_operation",
    description: "데이터베이스 작업을 수행합니다. action 에 원하는 작업을 넣으세요.",
    schema: z.object({
      action: z.string().describe("수행할 작업"),
      payload: z.record(z.string(), z.unknown()).describe("작업에 필요한 데이터"),
    }),
  },
);

/** (좋음) 목적별로 쪼갠 도구: 이름과 스키마만 봐도 언제 쓸지 명확하다 */
const findCustomer = tool(
  async ({ email }) => JSON.stringify({ id: 42, email, grade: "GOLD" }),
  {
    name: "find_customer_by_email",
    description: "이메일 주소로 고객 한 명을 찾습니다. 고객 ID, 이메일, 등급을 반환합니다.",
    schema: z.object({ email: z.string().email().describe("고객 이메일 주소") }),
  },
);

const listOrders = tool(
  async ({ customerId, limit }) =>
    JSON.stringify([{ orderId: "ORD-1001", customerId, total: 128000, status: "DELIVERED" }].slice(0, limit)),
  {
    name: "list_customer_orders",
    description:
      "고객 ID로 그 고객의 주문 목록을 최신순으로 조회합니다. 고객 ID를 모르면 먼저 find_customer_by_email 을 사용하세요.",
    schema: z.object({
      customerId: z.number().int().describe("고객 ID (find_customer_by_email 의 반환값)"),
      limit: z.number().int().min(1).max(50).describe("최대 주문 건수"),
    }),
  },
);

async function step6_9() {
  section("[6-9] 도구 설계 원칙 — 입도(granularity)");

  const model = await initChatModel(MODEL_ID);
  const question = "kim@example.com 고객의 최근 주문 3건 알려줘";

  const withGod = await model.bindTools([godTool]).invoke([{ role: "user", content: question }]);
  console.log("\n[만능 도구 1개]");
  console.log("  tool_calls:", JSON.stringify(withGod.tool_calls?.map((c) => c.args)));
  console.log("  → action 문자열을 모델이 '지어내야' 합니다. 우리 DB가 그 action 을 알 리 없죠.");

  const withSplit = await model
    .bindTools([findCustomer, listOrders])
    .invoke([{ role: "user", content: question }]);
  console.log("\n[목적별 도구 2개]");
  console.log("  tool_calls:", JSON.stringify(withSplit.tool_calls?.map((c) => ({ name: c.name, args: c.args }))));
  console.log("  → 이메일로 고객을 먼저 찾는 올바른 순서를 스스로 잡습니다.");

  // 이름 규칙: provider 가 거부하는 이름을 만들지 말 것
  console.log("\n-- 도구 이름 규칙 --");
  console.log("  OK : get_weather, search_products, list_customer_orders");
  console.log("  NG : 'get weather'(공백), 'get-weather'(하이픈), '날씨조회'(비ASCII)");
}

/* ===== [6-10] 종합 — 도구 5개를 갖춘 미니 툴 세트 ===== */
/**
 * 지금까지 배운 것을 다 넣은 도구 모음입니다.
 * - snake_case 이름
 * - "무엇을/언제/제약" 3요소를 갖춘 description
 * - 모든 필드에 .describe()
 * - 실패를 문자열로 흡수 (throw 하지 않음)
 * - 큰 결과는 요약만 모델에게
 */
const inventory = new Map<number, number>([
  [1, 3],
  [2, 12],
  [3, 0],
  [4, 40],
]);

const checkStock = tool(
  async ({ productId }) => {
    const qty = inventory.get(productId);
    if (qty === undefined) {
      return `오류: 상품 ID ${productId} 를 찾을 수 없습니다. search_products 로 먼저 ID를 확인하세요.`;
    }
    return JSON.stringify({ productId, stock: qty, inStock: qty > 0 });
  },
  {
    name: "check_stock",
    description:
      "상품 ID로 현재 재고 수량을 조회합니다. 사용자가 '살 수 있나', '재고 있나'를 물으면 사용하세요. 상품 ID를 모르면 먼저 search_products 를 사용하세요.",
    schema: z.object({ productId: z.number().int().describe("상품 ID. search_products 의 id 필드") }),
  },
);

const placeOrder = tool(
  async ({ productId, quantity }) => {
    const qty = inventory.get(productId) ?? 0;
    if (qty < quantity) {
      return `오류: 재고 부족 (요청 ${quantity}개, 재고 ${qty}개). 사용자에게 알리고 수량 조정을 제안하세요.`;
    }
    inventory.set(productId, qty - quantity);
    return JSON.stringify({ orderId: `ORD-${1000 + productId}`, productId, quantity, status: "CREATED" });
  },
  {
    name: "place_order",
    description:
      "상품을 주문합니다. 실제로 재고가 차감되는 되돌릴 수 없는 작업이므로, 반드시 사용자가 명시적으로 주문을 요청했을 때만 사용하세요. 먼저 check_stock 으로 재고를 확인하는 것을 권장합니다.",
    schema: z.object({
      productId: z.number().int().describe("주문할 상품 ID"),
      quantity: z.number().int().min(1).max(10).describe("주문 수량. 1~10개"),
    }),
  },
);

async function step6_10() {
  section("[6-10] 종합 — 도구 세트로 한 바퀴 돌려보기");

  // 도구 목록과 "이름 → 도구" 레지스트리.
  // 서로 다른 스키마의 도구를 한 배열에 담으므로 공통 인터페이스로 타입을 맞춘다.
  // (타입을 안 맞추면 union 이 되어 selected.invoke(call) 이 호출 불가 타입이 된다)
  const tools: StructuredToolInterface[] = [searchProducts, checkStock, placeOrder];
  const toolsByName: Record<string, StructuredToolInterface> = Object.fromEntries(
    tools.map((t) => [t.name, t]),
  );

  const model = await initChatModel(MODEL_ID);
  const bound = model.bindTools(tools);

  const messages: unknown[] = [
    {
      role: "system",
      content:
        "당신은 쇼핑몰 상담원입니다. 주문 전에는 반드시 재고를 확인하고, 주문 결과를 사용자에게 한국어로 알려주세요.",
    },
    { role: "user", content: "게이밍 노트북 재고 있으면 2대 주문해줘." },
  ];

  // 도구 호출 루프를 손으로 한 번 돌려봅니다. (제대로 된 구현은 Step 07)
  for (let turn = 0; turn < 5; turn++) {
    const ai = await bound.invoke(messages as never);
    messages.push(ai);

    if (!ai.tool_calls?.length) {
      console.log(`\n[턴 ${turn + 1}] 최종 답변:`, ai.text ?? ai.content);
      break;
    }

    console.log(`\n[턴 ${turn + 1}] 도구 호출 ${ai.tool_calls.length}건`);
    for (const call of ai.tool_calls) {
      console.log(`  → ${call.name}(${JSON.stringify(call.args)})`);
      const selected = toolsByName[call.name];
      if (!selected) {
        // 모델이 없는 도구를 지어냈을 때도 대화가 깨지지 않게 ToolMessage 를 채운다.
        messages.push(
          new ToolMessage({
            content: `오류: '${call.name}' 라는 도구는 존재하지 않습니다.`,
            tool_call_id: call.id!,
            name: call.name,
          }),
        );
        continue;
      }
      const tm = await selected.invoke(call);
      console.log(`  ← ${String(tm.content).slice(0, 100)}`);
      messages.push(tm);
    }
  }
}

/* ===== 실행 ===== */
async function main() {
  await step6_1();
  await step6_2();
  await step6_3();
  await step6_4();
  await step6_5();
  await step6_6();
  await step6_7();
  await step6_8();
  await step6_9();
  await step6_10();
}

main().catch((err) => {
  console.error("실행 실패:", err);
  process.exit(1);
});
