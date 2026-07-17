/**
 * Step 14 — 컨텍스트와 런타임 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-14-context-runtime/solution.ts
 *       npx tsx docs/reference/langchain/step-14-context-runtime/solution.ts 7   ← 7번만
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어 보세요.
 */
import "dotenv/config";

import { createAgent, createMiddleware, dynamicSystemPromptMiddleware, tool } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore as GraphStore } from "@langchain/langgraph";
import { ChatAnthropic } from "@langchain/anthropic";
import type { ToolRuntime } from "@langchain/core/tools";
import type { BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection, printKV, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");

function graphStore(runtime: { store: unknown }): GraphStore | null {
  return (runtime.store ?? null) as GraphStore | null;
}
function toolName(t: { name?: unknown }): string {
  return typeof t.name === "string" ? t.name : "";
}
function lastText(messages: BaseMessage[]): string {
  return (messages.at(-1) as BaseMessage).text.trim();
}
function inputTokens(messages: BaseMessage[]): number | undefined {
  const last = messages.at(-1) as BaseMessage;
  return (last as { usage_metadata?: { input_tokens: number } }).usage_metadata?.input_tokens;
}

const haiku = new ChatAnthropic({ model: "claude-haiku-4-5", temperature: 0 });
const sonnet = new ChatAnthropic({ model: "claude-sonnet-4-6", temperature: 0 });

/* ===== [정답 1] contextSchema 정의하고 도구에서 읽기 ===== */

const greetContext = z.object({
  userId: z.string(),
  locale: z.enum(["ko", "en"]),
});

const getGreeting = tool(
  // 핵심: 두 번째 인자 runtime 으로 받습니다. 클로저가 아닙니다.
  // ToolRuntime<TState, TContext> 이므로 상태를 안 쓰면 첫 인자는 any 로 둡니다.
  async (_input, runtime: ToolRuntime<any, typeof greetContext>) => {
    const { userId, locale } = runtime.context;
    return locale === "ko" ? `안녕하세요, ${userId}님` : `Hello, ${userId}`;
  },
  {
    name: "get_greeting",
    description: "현재 사용자에게 맞는 인사말을 반환합니다.",
    schema: z.object({}),
  },
);

async function solution1(): Promise<void> {
  printSection("[정답 1] contextSchema 정의하고 도구에서 읽기");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getGreeting],
    systemPrompt: "get_greeting 도구를 호출해 그 결과를 그대로 출력하세요.",
    contextSchema: greetContext,
  });

  const ko = await agent.invoke(
    { messages: [{ role: "user", content: "인사해줘" }] },
    { context: { userId: "민수", locale: "ko" } },
  );
  console.log("ko:", lastText(ko.messages));

  const en = await agent.invoke(
    { messages: [{ role: "user", content: "인사해줘" }] },
    { context: { userId: "Alice", locale: "en" } },
  );
  console.log("en:", lastText(en.messages));

  // 해설: 에이전트 정의는 하나인데 invoke 마다 다른 사람에게 인사합니다.
  //       사용자별로 에이전트를 새로 만들 필요가 없습니다 —
  //       그게 contextSchema 를 쓰는 이유입니다.
}

/* ===== [정답 2] 비밀은 어디에 두어야 하나 — state vs context ===== */

async function solution2(): Promise<void> {
  printSection("[정답 2] 비밀은 어디에 두어야 하나 — state vs context");

  /* (a) 잘못된 방법 — state 에 토큰을 넣는다 */
  const badState = z.object({ apiToken: z.string().default("") });
  const badSaver = new MemorySaver();

  const badAgent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "한 문장으로 인사하세요.",
    stateSchema: badState,
    checkpointer: badSaver,
  });

  const badConfig = { configurable: { thread_id: "leak-1" } };
  await badAgent.invoke(
    { messages: [{ role: "user", content: "안녕" }], apiToken: "sk-SECRET-1234" },
    badConfig,
  );

  const badTuple = await badSaver.getTuple(badConfig);
  console.log("[state 에 넣은 경우] 체크포인트 안의 apiToken =");
  console.log("   ", JSON.stringify(badTuple?.checkpoint.channel_values?.["apiToken"]));
  // → "sk-SECRET-1234"  ← 토큰이 그대로 저장되었습니다.

  /* (b) 올바른 방법 — context 로 옮긴다 */
  const goodContext = z.object({ apiToken: z.string() });
  const goodSaver = new MemorySaver();

  const goodAgent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "한 문장으로 인사하세요.",
    contextSchema: goodContext,
    checkpointer: goodSaver,
  });

  const goodConfig = { configurable: { thread_id: "safe-1" } };
  await goodAgent.invoke(
    { messages: [{ role: "user", content: "안녕" }] },
    { ...goodConfig, context: { apiToken: "sk-SECRET-1234" } },
  );

  const goodTuple = await goodSaver.getTuple(goodConfig);
  const channels = Object.keys(goodTuple?.checkpoint.channel_values ?? {});
  console.log("\n[context 로 옮긴 경우] 체크포인트의 채널 목록 =");
  console.log("   ", JSON.stringify(channels));
  console.log("    apiToken 채널 존재? →", channels.includes("apiToken"));
  // → false. 토큰은 어디에도 저장되지 않았습니다.

  // 해설: 이게 이 스텝에서 가장 비싼 함정입니다.
  //   state 는 "체크포인터에 저장되는 대화 데이터"입니다. MemorySaver 면 메모리에서
  //   끝나지만, 프로덕션에서 쓰는 Postgres 체크포인터라면 토큰이 DB 테이블에
  //   평문으로 영구히 남습니다. 로그·백업·복제본까지 따라갑니다.
  //   반면 context 는 invoke 인자로만 흐르고 아무 데도 저장되지 않습니다.
  //   판단 기준: "이 값이 6개월 뒤 DB 에 남아 있어도 괜찮은가?"
  //   아니라면 context 입니다.
}

/* ===== [정답 3] 동적 시스템 프롬프트로 언어 전환 ===== */

const localeContext = z.object({
  locale: z.enum(["ko", "en", "ja"]),
});

const RULES: Record<z.infer<typeof localeContext>["locale"], string> = {
  ko: "반드시 한국어로만 답하세요.",
  en: "Answer only in English.",
  ja: "必ず日本語だけで答えてください。",
};

const localePrompt = dynamicSystemPromptMiddleware<z.infer<typeof localeContext>>(
  // 콜백은 (state, runtime) 을 받습니다. state 는 안 쓰므로 _ 로 둡니다.
  (_state, runtime) => `당신은 사실을 간결히 알려주는 도우미입니다.\n${RULES[runtime.context.locale]}`,
);

async function solution3(): Promise<void> {
  printSection("[정답 3] 동적 시스템 프롬프트로 언어 전환");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    // systemPrompt 를 주지 않은 것이 포인트입니다.
    // 주면 "systemPrompt + 동적 프롬프트" 가 구분자 없이 이어붙습니다.
    contextSchema: localeContext,
    middleware: [localePrompt],
  });

  const question = { messages: [{ role: "user" as const, content: "물은 몇 도에서 끓나요?" }] };

  for (const locale of ["ko", "en", "ja"] as const) {
    const result = await agent.invoke(question, { context: { locale } });
    console.log(`${locale} →`, lastText(result.messages).slice(0, 60));
  }

  // 해설: 언어별로 에이전트를 3개 만들 필요가 없습니다.
  //       프롬프트는 "실행 시점에 계산되는 값"이지 상수가 아닙니다.
}

/* ===== [정답 4] 권한별 도구 필터링 ===== */

const readReport = tool(async () => "이번 달 매출은 1억 2천만원입니다.", {
  name: "read_report",
  description: "매출 리포트를 조회합니다.",
  schema: z.object({}),
});

const exportCsv = tool(async () => "report.csv 로 내보냈습니다.", {
  name: "export_csv",
  description: "리포트를 CSV 파일로 내보냅니다.",
  schema: z.object({}),
});

const deleteReport = tool(async () => "리포트를 삭제했습니다.", {
  name: "delete_report",
  description: "리포트를 영구 삭제합니다.",
  schema: z.object({}),
});

const roleContext = z.object({
  role: z.enum(["viewer", "editor", "owner"]),
});

const ALLOWED: Record<z.infer<typeof roleContext>["role"], string[]> = {
  viewer: ["read_report"],
  editor: ["read_report", "export_csv"],
  owner: ["read_report", "export_csv", "delete_report"],
};

const filterByRole = createMiddleware({
  name: "FilterByRole",
  contextSchema: roleContext,
  wrapModelCall: async (request, handler) => {
    // context 가 없으면 가장 좁은 권한으로 떨어집니다(fail-safe).
    // 반대로 하면(없으면 owner) 사고가 납니다.
    const role = request.runtime.context?.role ?? "viewer";
    const allowed = ALLOWED[role];
    const tools = request.tools.filter((t) => allowed.includes(toolName(t)));

    console.log(`  [필터] role=${role} → [${tools.map(toolName).join(", ")}]`);
    return handler({ ...request, tools });
  },
});

async function solution4(): Promise<void> {
  printSection("[정답 4] 권한별 도구 필터링");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [readReport, exportCsv, deleteReport],
    systemPrompt: "요청을 처리하세요. 도구가 없어 불가능하면 그렇다고 말하세요.",
    contextSchema: roleContext,
    middleware: [filterByRole],
  });

  const ask = { messages: [{ role: "user" as const, content: "리포트 삭제해줘." }] };

  console.log("── viewer ──");
  const viewer = await agent.invoke(ask, { context: { role: "viewer" } });
  console.log("  ", lastText(viewer.messages).slice(0, 70));

  console.log("── owner ──");
  const owner = await agent.invoke(ask, { context: { role: "owner" } });
  console.log("  ", lastText(owner.messages).slice(0, 70));

  // 해설: viewer 에게는 delete_report 가 "존재하지 않습니다".
  //   모델은 없는 도구를 부를 수 없으니 거절합니다.
  //   단, 이건 "안 보여주기"일 뿐 "실행 차단"이 아닙니다.
  //   도구 본문에서도 runtime.context.role 을 다시 확인해야 진짜 방어입니다
  //   (practice.ts 의 admin_delete_user 참고).
  //   미들웨어 필터링은 프롬프트 절약 + 오작동 감소가 주목적이고,
  //   보안 경계는 도구 안에 두세요.
}

/* ===== [정답 5] 대화 길이에 따른 모델 라우팅 ===== */

const routeByLength = createMiddleware({
  name: "RouteByLength",
  wrapModelCall: async (request, handler) => {
    const count = request.messages.length;
    const model = count < 6 ? haiku : sonnet;
    console.log(`  [라우팅] 메시지 ${count}개 → ${count < 6 ? "haiku" : "sonnet"}`);
    return handler({ ...request, model });
  },
});

async function solution5(): Promise<void> {
  printSection("[정답 5] 대화 길이에 따른 모델 라우팅");

  const agent = createAgent({
    model: haiku, // 기본값. 미들웨어가 매번 덮어씁니다.
    tools: [],
    systemPrompt: "한 문장으로 짧게 답하세요.",
    checkpointer: new MemorySaver(),
    middleware: [routeByLength],
  });

  const config = { configurable: { thread_id: "route-1" } };
  const turns = ["안녕", "오늘 기분 어때?", "재미있는 사실 하나만", "하나만 더"];

  for (const text of turns) {
    const result = await agent.invoke({ messages: [{ role: "user", content: text }] }, config);
    console.log(`   "${text}" →`, lastText(result.messages).slice(0, 40));
  }

  // 해설: 같은 thread 라 메시지가 누적됩니다(1 → 3 → 5 → 7 ...).
  //   3번째 턴을 지나며 haiku 에서 sonnet 으로 넘어갑니다.
  //   실무에서는 "짧은 잡담은 싼 모델, 길고 복잡해지면 좋은 모델" 같은 식으로
  //   비용을 크게 줄일 수 있습니다.
  //   주의: 모델이 바뀌면 프롬프트 캐시가 무효화됩니다. 자주 왔다갔다하면
  //   오히려 손해일 수 있으니 임계값은 넉넉히 잡으세요.
}

/* ===== [정답 6] 컨텍스트 예산 — 관련 문서만 골라 넣기 ===== */

const DOCS = [
  { id: "hr-01", keywords: ["연차", "휴가"], text: "연차는 입사 1년 후 15일이 부여된다." },
  { id: "hr-02", keywords: ["연차", "휴가"], text: "미사용 연차는 다음 해로 이월되지 않는다." },
  { id: "it-01", keywords: ["비밀번호", "보안"], text: "사내 비밀번호는 90일마다 변경해야 한다." },
  { id: "it-02", keywords: ["VPN", "보안"], text: "재택 근무 시 VPN 접속이 필수다." },
  { id: "ga-01", keywords: ["주차"], text: "지하 주차장은 오전 7시부터 오후 10시까지 개방한다." },
  { id: "ga-02", keywords: ["식당"], text: "구내식당 점심은 11시 30분부터 1시까지다." },
];

async function askWith(docs: typeof DOCS, question: string): Promise<number | undefined> {
  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: [
      "아래 사내 문서만 근거로 답하세요. 한 문장으로.",
      "",
      ...docs.map((d) => `[${d.id}] ${d.text}`),
    ].join("\n"),
  });

  const result = await agent.invoke({ messages: [{ role: "user", content: question }] });
  console.log(`   답변: ${lastText(result.messages).slice(0, 50)}`);
  return inputTokens(result.messages);
}

async function solution6(): Promise<void> {
  printSection("[정답 6] 컨텍스트 예산 — 관련 문서만 골라 넣기");

  const question = "연차가 이월되나요?";

  // (a) 전부 넣기
  console.log("(a) 문서 6개 전부");
  const allTokens = await askWith(DOCS, question);

  // (b) 관련 문서만 — 아주 단순한 키워드 필터입니다.
  //     실무에서는 이 자리에 임베딩 검색이 들어갑니다 (Step 16).
  const relevant = DOCS.filter((d) => d.keywords.some((k) => question.includes(k)));
  console.log(`\n(b) 관련 문서만 ${relevant.length}개 [${relevant.map((d) => d.id).join(", ")}]`);
  const fewTokens = await askWith(relevant, question);

  // (c) 비교
  if (allTokens !== undefined && fewTokens !== undefined) {
    const saved = Math.round((1 - fewTokens / allTokens) * 100);
    console.log("");
    printKV({
      "전부 넣었을 때 입력 토큰": allTokens,
      "골라 넣었을 때 입력 토큰": fewTokens,
      절감: `${saved}%`,
    });
  }

  // 해설: 문서 6개짜리 장난감 예제라 절감폭이 작아 보이지만,
  //   실제 RAG 는 문서가 수천 개입니다. "일단 다 넣자"는 곧바로
  //   비용 폭증 + 지연 증가 + 정확도 하락으로 이어집니다.
  //   답이 컨텍스트 어딘가에 "있다"는 것과 모델이 그걸 "쓴다"는 건 다릅니다.
}

/* ===== [정답 7] 클로저 캡처 버그 재현하고 고치기 ===== */

/* (a) 잘못된 방법 — 모듈 전역에 현재 사용자를 담는다 */
let currentUserId = ""; // ← 이 줄이 버그의 원인입니다

const whoAmIBad = tool(
  async () => {
    // runtime 을 안 받고 바깥 변수를 읽습니다.
    // 이 변수는 프로세스에 딱 하나뿐입니다 — 요청마다 있는 게 아닙니다.
    return `현재 사용자: ${currentUserId}`;
  },
  { name: "who_am_i_bad", description: "현재 사용자를 반환합니다.", schema: z.object({}) },
);

/* (b) 올바른 방법 — runtime.context 에서 읽는다 */
const userContext = z.object({ userId: z.string() });

const whoAmIGood = tool(
  async (_input, runtime: ToolRuntime<any, typeof userContext>) => {
    // 이 값은 "이 도구 호출"에 묶여 있습니다. 동시 요청끼리 섞일 수 없습니다.
    return `현재 사용자: ${runtime.context.userId}`;
  },
  { name: "who_am_i_good", description: "현재 사용자를 반환합니다.", schema: z.object({}) },
);

async function solution7(): Promise<void> {
  printSection("[정답 7] 클로저 캡처 버그 재현하고 고치기");

  /* (a) 버그 재현 */
  const badAgent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [whoAmIBad],
    systemPrompt: "who_am_i_bad 도구를 호출해 결과를 그대로 출력하세요.",
  });

  const askBad = async (userId: string): Promise<string> => {
    currentUserId = userId; // 요청 직전에 전역을 세팅 — 안티패턴
    const result = await badAgent.invoke({
      messages: [{ role: "user", content: "내가 누구지?" }],
    });
    return lastText(result.messages);
  };

  console.log("── (a) 전역 변수 버전, 동시 요청 ──");
  const [badA, badB] = await Promise.all([askBad("alice"), askBad("bob")]);
  console.log("   alice 요청의 답:", badA.slice(0, 50));
  console.log("   bob   요청의 답:", badB.slice(0, 50));
  console.log("   → 둘 다 'bob' 이라고 나올 겁니다. alice 의 답이 오염됐습니다.");

  /* (b) 고친 버전 */
  const goodAgent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [whoAmIGood],
    systemPrompt: "who_am_i_good 도구를 호출해 결과를 그대로 출력하세요.",
    contextSchema: userContext,
  });

  const askGood = async (userId: string): Promise<string> => {
    const result = await goodAgent.invoke(
      { messages: [{ role: "user", content: "내가 누구지?" }] },
      { context: { userId } }, // 전역이 아니라 이 호출에 묶인 값
    );
    return lastText(result.messages);
  };

  console.log("\n── (b) runtime.context 버전, 동시 요청 ──");
  const [goodA, goodB] = await Promise.all([askGood("alice"), askGood("bob")]);
  console.log("   alice 요청의 답:", goodA.slice(0, 50));
  console.log("   bob   요청의 답:", goodB.slice(0, 50));
  console.log("   → 각자 올바른 이름이 나옵니다.");

  // 해설: 왜 섞이는가.
  //   Promise.all 은 askBad("alice") 를 시작하고, 그게 await 에서 멈춘 사이에
  //   askBad("bob") 이 실행되어 currentUserId 를 "bob" 으로 덮어씁니다.
  //   그 다음 alice 요청의 도구가 실행될 때 전역은 이미 "bob" 입니다.
  //
  //   Node 는 싱글 스레드지만 "동시성"은 있습니다. 이 사실을 놓치면
  //   개발할 때는(요청 1개씩) 멀쩡하다가 트래픽이 붙는 순간
  //   남의 데이터가 보이기 시작합니다. 재현도 안 되는 최악의 버그입니다.
  //
  //   규칙: 요청마다 달라지는 값은 절대 모듈 스코프에 두지 마세요.
  //        contextSchema 로 넘기고 runtime.context 로 읽으세요.
}

/* ===== [정답 8] 멀티테넌트 에이전트 ===== */

const tenantContext = z.object({
  tenantId: z.enum(["alpha", "beta"]),
  userId: z.string(),
});
type TenantId = z.infer<typeof tenantContext>["tenantId"];

interface TenantProfile {
  tone: string;
  allowedTools: string[];
  model: ChatAnthropic;
}

const TENANTS: Record<TenantId, TenantProfile> = {
  alpha: {
    tone: "격식 있는 존댓말로 답합니다.",
    allowedTools: ["read_report", "export_csv", "read_tenant_note"],
    model: sonnet,
  },
  beta: {
    tone: "간결한 평서문으로 답합니다.",
    allowedTools: ["read_report", "read_tenant_note"],
    model: haiku,
  },
};

// 테넌트별 네임스페이스에서만 읽는 도구.
// 네임스페이스에 tenantId 를 넣는 것이 테넌트 격리의 핵심입니다.
const readTenantNote = tool(
  async (_input, runtime: ToolRuntime<any, typeof tenantContext>) => {
    const tenantId = runtime.context?.tenantId;
    if (tenantId === undefined) return "테넌트를 알 수 없습니다.";

    const item = await graphStore(runtime)?.get(["reports", tenantId], "note");
    const note = (item?.value as { text?: string } | undefined)?.text;
    return note ?? "메모가 없습니다.";
  },
  {
    name: "read_tenant_note",
    description: "우리 회사에 저장된 메모를 읽습니다.",
    schema: z.object({}),
  },
);

const tenantRouting = createMiddleware({
  name: "TenantRouting",
  contextSchema: tenantContext,
  wrapModelCall: async (request, handler) => {
    const ctx = request.runtime.context;
    // context 가 없으면 어느 테넌트인지 모릅니다.
    // 기본값으로 넘어가면 남의 데이터를 보여주게 됩니다. 반드시 터뜨립니다.
    if (ctx === undefined || ctx === null) {
      throw new Error("TenantRouting: context 가 없습니다. invoke 에 context 를 주세요.");
    }

    const profile = TENANTS[ctx.tenantId];
    const tools = request.tools.filter((t) => profile.allowedTools.includes(toolName(t)));

    // concat 을 쓰면 다른 미들웨어가 붙여 둔 캐시 제어나 콘텐츠 블록이 보존됩니다.
    const systemMessage = request.systemMessage.concat(
      `\n\n말투 규칙: ${profile.tone}\n고객사 코드: ${ctx.tenantId}`,
    );

    console.log(`  [테넌트] ${ctx.tenantId} → [${tools.map(toolName).join(", ")}]`);
    return handler({ ...request, model: profile.model, tools, systemMessage });
  },
});

async function solution8(): Promise<void> {
  printSection("[정답 8] 멀티테넌트 에이전트");

  const store = new InMemoryStore();
  // 테넌트별로 다른 네임스페이스에 저장합니다.
  await store.put(["reports", "alpha"], "note", { text: "알파: 3분기 목표는 20% 성장입니다." });
  await store.put(["reports", "beta"], "note", { text: "베타: 신제품 출시일은 11월입니다." });

  const agent = createAgent({
    model: haiku,
    tools: [readReport, exportCsv, deleteReport, readTenantNote],
    systemPrompt: "당신은 B2B SaaS 고객 지원 에이전트입니다.",
    contextSchema: tenantContext,
    store,
    middleware: [tenantRouting],
  });

  const ask = { messages: [{ role: "user" as const, content: "우리 회사 메모 읽어줘." }] };

  console.log("── alpha ──");
  const alpha = await agent.invoke(ask, { context: { tenantId: "alpha", userId: "u-1" } });
  console.log("  ", lastText(alpha.messages).slice(0, 70));

  console.log("── beta ──");
  const beta = await agent.invoke(ask, { context: { tenantId: "beta", userId: "u-2" } });
  console.log("  ", lastText(beta.messages).slice(0, 70));

  // 동시 요청에서도 격리되는지 확인
  console.log("\n── 동시 요청 ──");
  const [a, b] = await Promise.all([
    agent.invoke(ask, { context: { tenantId: "alpha", userId: "u-1" } }),
    agent.invoke(ask, { context: { tenantId: "beta", userId: "u-2" } }),
  ]);
  console.log("   alpha:", lastText(a.messages).slice(0, 45));
  console.log("   beta :", lastText(b.messages).slice(0, 45));

  // context 없이 부르면?
  console.log("\n── context 없이 호출 ──");
  try {
    const noCtx = { configurable: {} } as unknown as Parameters<typeof agent.invoke>[1];
    await agent.invoke(ask, noCtx);
    console.log("   (에러가 안 났다면 미들웨어 가드가 빠진 것입니다)");
  } catch (error) {
    console.log("   에러:", (error as Error).message.replace(/\s+/g, " ").slice(0, 80));
  }

  // 해설: 이 에이전트는 정의가 하나입니다. 테넌트가 100개로 늘어도
  //   TENANTS 표에 줄만 추가하면 됩니다.
  //
  //   격리는 세 겹입니다:
  //     1. 프롬프트  — 테넌트 규칙을 systemMessage 에 붙인다
  //     2. 도구      — allowedTools 로 거른다 (모델에게 안 보임)
  //     3. 데이터    — store 네임스페이스에 tenantId 를 넣는다 (읽기 자체가 스코프됨)
  //   3번이 진짜 방어선입니다. 1·2 는 모델이 협조해야 성립하지만
  //   3번은 모델이 무슨 짓을 해도 남의 네임스페이스를 못 봅니다.
  //
  //   그리고 context 가 없을 때 조용히 기본값으로 넘어가지 않고
  //   에러를 던지는 것 — 이게 멀티테넌트에서 가장 중요한 한 줄입니다.
}

/* ===== 실행 ===== */

const SOLUTIONS: Array<[string, () => Promise<void>]> = [
  ["1", solution1],
  ["2", solution2],
  ["3", solution3],
  ["4", solution4],
  ["5", solution5],
  ["6", solution6],
  ["7", solution7],
  ["8", solution8],
];

const only = process.argv[2];
for (const [id, fn] of SOLUTIONS) {
  if (only === undefined || only === id) {
    await fn();
  }
}
