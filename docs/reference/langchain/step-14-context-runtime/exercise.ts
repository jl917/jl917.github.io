/**
 * Step 14 — 컨텍스트와 런타임 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-14-context-runtime/exercise.ts
 *
 * 각 [문제 N] 아래 빈 자리를 채우세요.
 * 그대로 실행하면 아무것도 출력되지 않습니다 — 정상입니다.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어 보세요.
 */
import "dotenv/config";

import { createAgent, createMiddleware, dynamicSystemPromptMiddleware, tool } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore as GraphStore } from "@langchain/langgraph";
import { ChatAnthropic } from "@langchain/anthropic";
import type { ToolRuntime } from "@langchain/core/tools";
import * as z from "zod";

import { printSection, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");

// practice.ts 와 같은 헬퍼입니다. 그대로 쓰세요.
function graphStore(runtime: { store: unknown }): GraphStore | null {
  return (runtime.store ?? null) as GraphStore | null;
}
function toolName(t: { name?: unknown }): string {
  return typeof t.name === "string" ? t.name : "";
}

/* ===== [문제 1] contextSchema 정의하고 도구에서 읽기 =====
 *
 * 요구사항:
 *   - contextSchema 를 만드세요: userId(문자열, 필수), locale("ko" | "en", 필수)
 *   - get_greeting 도구를 만드세요. 인자는 없습니다.
 *     runtime.context 에서 userId 와 locale 을 읽어
 *     locale 이 "ko" 면 `안녕하세요, {userId}님`, "en" 이면 `Hello, {userId}` 를 반환합니다.
 *   - 에이전트를 만들고 invoke 에 context 를 넘겨 실행하세요.
 *
 * 힌트: 도구의 두 번째 인자에 runtime: ToolRuntime<any, typeof 스키마> 를 선언합니다.
 *       클로저로 userId 를 캡처하면 안 됩니다 — 문제 7 에서 그 이유를 봅니다.
 */

async function exercise1(): Promise<void> {
  printSection("[문제 1] contextSchema 정의하고 도구에서 읽기");
  // 여기에 작성하세요.
}

/* ===== [문제 2] 비밀은 어디에 두어야 하나 — state vs context =====
 *
 * 아래 코드는 API 토큰을 state 에 넣습니다. 잘못된 코드입니다.
 *
 *   const badState = z.object({ apiToken: z.string().default("") });
 *   const agent = createAgent({
 *     model: "anthropic:claude-sonnet-4-6",
 *     tools: [],
 *     stateSchema: badState,
 *     checkpointer: saver,
 *   });
 *   await agent.invoke(
 *     { messages: [{ role: "user", content: "안녕" }], apiToken: "sk-SECRET-1234" },
 *     { configurable: { thread_id: "leak-1" } },
 *   );
 *
 * 할 일:
 *   (a) 위 코드를 그대로 실행한 뒤, saver.getTuple(config) 로 체크포인트를 꺼내
 *       checkpoint.channel_values 안에 apiToken 이 그대로 들어 있는 것을 출력하세요.
 *       (이게 "비밀이 디스크에 영구 저장된다"는 뜻입니다.)
 *   (b) 같은 토큰을 contextSchema 로 옮긴 버전을 만들고,
 *       체크포인트에 토큰이 없다는 것을 출력으로 확인하세요.
 *
 * 힌트: saver.getTuple({ configurable: { thread_id: "..." } }) 는
 *       CheckpointTuple | undefined 를 돌려줍니다.
 *       tuple?.checkpoint.channel_values 를 들여다보세요.
 */

async function exercise2(): Promise<void> {
  printSection("[문제 2] 비밀은 어디에 두어야 하나 — state vs context");
  // 여기에 작성하세요.
}

/* ===== [문제 3] 동적 시스템 프롬프트로 언어 전환 =====
 *
 * 요구사항:
 *   - contextSchema: locale("ko" | "en" | "ja", 필수)
 *   - dynamicSystemPromptMiddleware 로 locale 에 따라 시스템 프롬프트를 바꾸세요.
 *       ko → "반드시 한국어로만 답하세요."
 *       en → "Answer only in English."
 *       ja → "必ず日本語だけで答えてください。"
 *   - 같은 질문("물은 몇 도에서 끓나요?")을 세 locale 로 각각 invoke 해서
 *     답변 언어가 바뀌는지 확인하세요.
 *
 * 주의: createAgent 에 systemPrompt 를 같이 주면 두 문자열이
 *       구분자 없이 이어붙습니다. 이 문제에서는 systemPrompt 를 주지 마세요.
 * 힌트: dynamicSystemPromptMiddleware<z.infer<typeof 스키마>>((state, runtime) => ...)
 */

async function exercise3(): Promise<void> {
  printSection("[문제 3] 동적 시스템 프롬프트로 언어 전환");
  // 여기에 작성하세요.
}

/* ===== [문제 4] 권한별 도구 필터링 =====
 *
 * 아래 도구 3개가 주어집니다. (그대로 쓰세요)
 */

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

/* 요구사항:
 *   - contextSchema: role("viewer" | "editor" | "owner", 필수)
 *   - createMiddleware 의 wrapModelCall 로 role 에 따라 도구를 거르세요.
 *       viewer → [read_report]
 *       editor → [read_report, export_csv]
 *       owner  → 전부
 *   - "리포트 삭제해줘" 를 viewer 와 owner 로 각각 invoke 해서 결과를 비교하세요.
 *
 * 힌트: request.tools.filter(...) 후 handler({ ...request, tools }) 를 호출합니다.
 *       도구 이름을 읽을 때는 위의 toolName(t) 헬퍼를 쓰세요 (t.name 은 unknown 입니다).
 */

async function exercise4(): Promise<void> {
  printSection("[문제 4] 권한별 도구 필터링");
  // 여기에 작성하세요.
}

/* ===== [문제 5] 대화 길이에 따른 모델 라우팅 =====
 *
 * 요구사항:
 *   - wrapModelCall 로 request.messages.length 를 보고 모델을 고르세요.
 *       6개 미만 → claude-haiku-4-5
 *       6개 이상 → claude-sonnet-4-6
 *   - 어떤 모델이 선택됐는지 console.log 로 찍으세요.
 *   - checkpointer 를 붙이고 같은 thread 로 4번 연달아 invoke 해서
 *     도중에 모델이 바뀌는 것을 확인하세요.
 *
 * 힌트: const fast = new ChatAnthropic({ model: "claude-haiku-4-5" });
 *       handler({ ...request, model }) 로 넘깁니다.
 */

async function exercise5(): Promise<void> {
  printSection("[문제 5] 대화 길이에 따른 모델 라우팅");
  // 여기에 작성하세요.
}

/* ===== [문제 6] 컨텍스트 예산 — 관련 문서만 골라 넣기 =====
 *
 * 아래 문서 6개가 주어집니다. (그대로 쓰세요)
 */

const DOCS = [
  { id: "hr-01", keywords: ["연차", "휴가"], text: "연차는 입사 1년 후 15일이 부여된다." },
  { id: "hr-02", keywords: ["연차", "휴가"], text: "미사용 연차는 다음 해로 이월되지 않는다." },
  { id: "it-01", keywords: ["비밀번호", "보안"], text: "사내 비밀번호는 90일마다 변경해야 한다." },
  { id: "it-02", keywords: ["VPN", "보안"], text: "재택 근무 시 VPN 접속이 필수다." },
  { id: "ga-01", keywords: ["주차"], text: "지하 주차장은 오전 7시부터 오후 10시까지 개방한다." },
  { id: "ga-02", keywords: ["식당"], text: "구내식당 점심은 11시 30분부터 1시까지다." },
];

/* 요구사항:
 *   (a) 문서 6개를 전부 시스템 프롬프트에 넣고 "연차가 이월되나요?" 를 물어보세요.
 *       응답의 usage_metadata.input_tokens 를 출력하세요.
 *   (b) 질문에 등장하는 키워드로 관련 문서만 걸러(여기서는 "연차")
 *       그 문서만 넣고 같은 질문을 하세요. 입력 토큰을 출력하세요.
 *   (c) 두 입력 토큰 수를 비교해 몇 % 줄었는지 출력하세요.
 *
 * 힌트: usage_metadata 는 optional 입니다.
 *       (msg as { usage_metadata?: { input_tokens: number } }).usage_metadata 로 방어적으로 읽으세요.
 */

async function exercise6(): Promise<void> {
  printSection("[문제 6] 컨텍스트 예산 — 관련 문서만 골라 넣기");
  // 여기에 작성하세요.
}

/* ===== [문제 7] 클로저 캡처 버그 재현하고 고치기 (중요) =====
 *
 * 아래는 흔한 안티패턴입니다. 도구가 runtime 을 안 받고 모듈 전역 변수를 읽습니다.
 *
 *   let currentUserId = "";                       // ← 전역
 *   const whoAmIBad = tool(async () => `현재 사용자: ${currentUserId}`, {
 *     name: "who_am_i_bad", description: "현재 사용자를 반환합니다.", schema: z.object({}),
 *   });
 *   // 요청마다 이렇게 세팅한다:
 *   currentUserId = "alice";
 *   await agent.invoke(...);
 *
 * 할 일:
 *   (a) 위 구조로 에이전트를 만들고, alice 와 bob 의 요청을 Promise.all 로 "동시에" 보내세요.
 *       각 invoke 직전에 currentUserId 를 세팅합니다.
 *       두 답변이 같은 사용자로 섞이는 것을 출력으로 확인하세요.
 *   (b) contextSchema + runtime.context 를 쓰는 버전으로 고치고,
 *       같은 동시 요청에서 각자 올바른 사용자가 나오는 것을 확인하세요.
 *
 * 이 문제가 이 스텝에서 가장 중요합니다. 실무 사고의 단골입니다.
 */

async function exercise7(): Promise<void> {
  printSection("[문제 7] 클로저 캡처 버그 재현하고 고치기");
  // 여기에 작성하세요.
}

/* ===== [문제 8] 멀티테넌트 에이전트 =====
 *
 * 요구사항:
 *   - contextSchema: tenantId("alpha" | "beta", 필수), userId(문자열, 필수)
 *   - 테넌트 설정 표를 만드세요:
 *       alpha → 말투 "격식체", 도구 [read_report, export_csv], 모델 sonnet
 *       beta  → 말투 "간결체", 도구 [read_report],             모델 haiku
 *     (도구는 문제 4 의 것을 재사용하세요)
 *   - wrapModelCall 하나에서 프롬프트 · 도구 · 모델을 전부 테넌트에 맞게 바꾸세요.
 *   - context 가 없으면 조용히 넘어가지 말고 에러를 던지세요.
 *   - store 에 테넌트별 네임스페이스로 데이터를 하나씩 넣고
 *     ["reports", tenantId] 네임스페이스에서만 읽는 도구를 추가하세요.
 *     alpha 로 실행했을 때 beta 데이터가 보이지 않아야 합니다.
 *
 * 힌트: 시스템 프롬프트를 이어붙일 때는 request.systemMessage.concat("...") 을 쓰세요.
 *       store 접근은 graphStore(runtime)?.get([...], key) 형태입니다.
 */

async function exercise8(): Promise<void> {
  printSection("[문제 8] 멀티테넌트 에이전트");
  // 여기에 작성하세요.
}

/* ===== 실행 ===== */

const EXERCISES: Array<[string, () => Promise<void>]> = [
  ["1", exercise1],
  ["2", exercise2],
  ["3", exercise3],
  ["4", exercise4],
  ["5", exercise5],
  ["6", exercise6],
  ["7", exercise7],
  ["8", exercise8],
];

const only = process.argv[2];
for (const [id, fn] of EXERCISES) {
  if (only === undefined || only === id) {
    await fn();
  }
}

// 사용하지 않는 import 경고를 피하기 위한 참조입니다.
// 문제를 풀면서 실제로 쓰게 되면 이 줄은 지우세요.
void [createAgent, createMiddleware, dynamicSystemPromptMiddleware, InMemoryStore, MemorySaver, ChatAnthropic, graphStore, toolName, readReport, exportCsv, deleteReport, DOCS];
export type { ToolRuntime };
