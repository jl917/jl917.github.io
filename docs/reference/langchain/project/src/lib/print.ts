/**
 * 출력 포맷 헬퍼 — LangChain 코스 Step 01~20 공용
 *
 * 이 코스의 실습 파일들은 "모델이 뭘 돌려줬는지"를 눈으로 확인하는 게 전부입니다.
 * 그런데 AIMessage 를 그냥 console.log 하면 내부 필드가 수십 줄로 쏟아져서
 * 정작 봐야 할 content 와 토큰 수가 묻힙니다. 그걸 정리해 주는 파일입니다.
 *
 * 사용:
 *   import { printSection, printMessages } from "../project/src/lib/print.js";
 *                                                                      ^^^
 *   ESM(NodeNext) 에서는 .ts 파일을 import 할 때도 확장자를 ".js" 로 씁니다.
 *   TypeScript 가 "컴파일 후의 경로"를 기준으로 해석하기 때문입니다.
 *   확장자를 빼거나 ".ts" 로 쓰면 tsc 가 에러를 냅니다.
 */

import type { BaseMessage, UsageMetadata } from "@langchain/core/messages";

/* ===== 색상 ===== */

// 터미널이 색을 지원하지 않거나 출력이 파일로 리다이렉트되면 색 코드를 빼야
// 깨진 글자(ESC[36m 같은 것)가 안 보입니다.
const useColor = process.stdout.isTTY === true && process.env["NO_COLOR"] === undefined;

const ESC = "\u001b"; // ANSI 이스케이프 시작 문자
const paint = (code: string, s: string): string =>
  useColor ? `${ESC}[${code}m${s}${ESC}[0m` : s;

const dim = (s: string) => paint("2", s);
const bold = (s: string) => paint("1", s);
const cyan = (s: string) => paint("36", s);
const green = (s: string) => paint("32", s);
const yellow = (s: string) => paint("33", s);
const magenta = (s: string) => paint("35", s);

/* ===== 섹션 구분선 ===== */

/**
 * 본문의 소제목([1-5] 같은 것)에 대응하는 구분선을 찍습니다.
 * practice.ts 를 통째로 실행했을 때 "지금 출력이 몇 번 절의 결과인지"를
 * 스크롤하며 찾을 수 있게 해 줍니다.
 *
 * printSection("[1-5] 첫 모델 호출")
 * →
 * ────────────────────────────────────────────────────────
 *  [1-5] 첫 모델 호출
 * ────────────────────────────────────────────────────────
 */
export function printSection(title: string): void {
  const line = "─".repeat(60);
  console.log(`\n${dim(line)}`);
  console.log(` ${bold(cyan(title))}`);
  console.log(dim(line));
}

/* ===== 타입 가드 =====
 *
 * BaseMessage 에는 tool_calls 나 usage_metadata 가 없습니다. 그 필드들은
 * AIMessage / ToolMessage 에만 있습니다. instanceof 로 좁힐 수도 있지만,
 * instanceof 는 @langchain/core 가 두 벌 설치되면 조용히 false 가 됩니다
 * (Step 01 의 함정 참고). 그래서 여기서는 "그 필드가 실제로 있는지"만 보는
 * 구조적(structural) 검사를 씁니다 — 코어가 몇 벌이든 항상 동작합니다.
 */

interface ToolCallLike {
  name: string;
  args: Record<string, unknown>;
  id?: string;
}

function getToolCalls(m: BaseMessage): ToolCallLike[] {
  const calls = (m as { tool_calls?: unknown }).tool_calls;
  return Array.isArray(calls) ? (calls as ToolCallLike[]) : [];
}

function getToolCallId(m: BaseMessage): string | undefined {
  const id = (m as { tool_call_id?: unknown }).tool_call_id;
  return typeof id === "string" ? id : undefined;
}

function getUsage(m: BaseMessage): UsageMetadata | undefined {
  return (m as { usage_metadata?: UsageMetadata }).usage_metadata;
}

/* ===== 메시지 출력 ===== */

// getType() 이 돌려주는 역할 문자열별 색.
// 모르는 타입이 와도 죽지 않도록 fallback 을 둡니다.
const ROLE_STYLE: Record<string, (s: string) => string> = {
  system: magenta,
  human: green,
  ai: cyan,
  tool: yellow,
};

function styleRole(role: string): string {
  const fn = ROLE_STYLE[role] ?? bold;
  return fn(role.toUpperCase().padEnd(6));
}

/**
 * 메시지 하나 또는 배열을 사람이 읽을 수 있게 출력합니다.
 *
 * - 역할(system/human/ai/tool)을 색으로 구분
 * - 본문은 .text 로 뽑습니다 (content 가 문자열이든 블록 배열이든 안전)
 * - AIMessage 에 tool_calls 가 있으면 이름과 인자를 같이 보여줍니다
 * - usage_metadata 가 있으면 토큰 수를 꼬리에 붙입니다
 *
 *   printMessages(response);
 *   printMessages(result.messages);
 */
export function printMessages(input: BaseMessage | BaseMessage[]): void {
  const messages = Array.isArray(input) ? input : [input];

  for (const m of messages) {
    const body = m.text.trim();
    console.log(`${styleRole(m.getType())} │ ${body === "" ? dim("(빈 내용)") : body}`);

    for (const call of getToolCalls(m)) {
      console.log(
        `       │ ${dim("→ tool")} ${yellow(call.name)}(${dim(JSON.stringify(call.args))})`,
      );
    }

    // ToolMessage 는 어떤 tool_call 에 대한 답인지 tool_call_id 로 연결됩니다.
    // 이 값이 어긋나면 대화가 조용히 깨집니다 — Step 07 에서 자세히 다룹니다.
    const callId = getToolCallId(m);
    if (callId !== undefined) {
      console.log(`       │ ${dim(`↳ tool_call_id: ${callId}`)}`);
    }

    const usage = getUsage(m);
    if (usage !== undefined) {
      const line = `tokens: in=${usage.input_tokens} out=${usage.output_tokens} total=${usage.total_tokens}`;
      console.log(`       │ ${dim(line)}`);
    }
  }
}

/* ===== 토큰 사용량 ===== */

/**
 * 토큰 사용량만 따로, 조금 더 자세히 출력합니다.
 * 캐시 읽기(input_token_details.cache_read)나 추론 토큰
 * (output_token_details.reasoning)처럼 제공자가 얹어 주는 세부 항목이 있으면
 * 같이 보여줍니다.
 *
 * usage_metadata 는 optional 입니다. 스트리밍 청크나 일부 제공자에서는
 * 아예 안 실려 오므로 반드시 존재 확인을 하고 읽어야 합니다.
 */
export function printUsage(m: BaseMessage): void {
  const u = getUsage(m);
  if (u === undefined) {
    console.log(dim("usage_metadata 없음 (제공자가 보내주지 않았거나 스트리밍 청크입니다)"));
    return;
  }

  console.log(`${dim("입력 토큰")}  ${String(u.input_tokens).padStart(7)}`);
  console.log(`${dim("출력 토큰")}  ${String(u.output_tokens).padStart(7)}`);
  console.log(`${dim("합계")}      ${String(u.total_tokens).padStart(7)}`);

  if (u.input_token_details !== undefined) {
    console.log(`${dim("입력 상세")}  ${JSON.stringify(u.input_token_details)}`);
  }
  if (u.output_token_details !== undefined) {
    console.log(`${dim("출력 상세")}  ${JSON.stringify(u.output_token_details)}`);
  }
}

/* ===== 잡다한 것 ===== */

/**
 * 아무 객체나 보기 좋게 들여쓴 JSON 으로 출력합니다.
 * response_metadata 처럼 제공자마다 모양이 다른 걸 들여다볼 때 씁니다.
 */
export function printJson(label: string, value: unknown): void {
  console.log(dim(label));
  console.log(JSON.stringify(value, null, 2));
}

/**
 * "키: 값" 표를 정렬해서 출력합니다.
 */
export function printKV(rows: Record<string, unknown>): void {
  const keys = Object.keys(rows);
  if (keys.length === 0) return;
  const width = Math.max(...keys.map((k) => k.length));
  for (const k of keys) {
    console.log(`${dim(k.padEnd(width))}  ${String(rows[k])}`);
  }
}

/**
 * 필수 환경변수가 있는지 확인하고, 없으면 무엇을 해야 하는지 알려 주며 종료합니다.
 *
 * 이게 없으면 키를 안 넣었을 때 제공자 SDK 가 던지는 난해한 에러를 보게 됩니다.
 * 처음 겪는 사람은 그게 "키가 없다"는 뜻인지 알아채기 어렵습니다.
 */
export function requireEnv(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.trim() === "") {
    console.error(`\n${yellow("환경변수가 없습니다:")} ${bold(name)}\n`);
    console.error("확인할 것:");
    console.error("  1. project/.env 파일이 있습니까?  (cp .env.example .env)");
    console.error(`  2. 그 안에 ${name}=... 이 채워져 있습니까?`);
    console.error('  3. 실행 파일 맨 위에 import "dotenv/config"; 가 있습니까?  ← 가장 흔한 원인');
    console.error("");
    process.exit(1);
  }
  return value;
}
