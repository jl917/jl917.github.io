/**
 * 출력 포맷 헬퍼 — Deep Agents 코스 Step 01~12 공용
 *
 * Deep Agent 를 invoke 하면 결과 객체 안에 messages 수십 개, files, todos 가
 * 한꺼번에 들어 있습니다. 그걸 그냥 console.log 하면 터미널이 수백 줄로
 * 덮여서 정작 봐야 할 "무슨 일이 있었는가"가 묻힙니다. 그걸 정리하는 파일입니다.
 *
 * 사용:
 *   import { printSection, printMessages } from "../project/src/lib/print.js";
 *                                                                      ^^^
 *   ESM(NodeNext) 에서는 .ts 파일을 import 할 때도 확장자를 ".js" 로 씁니다.
 *   TypeScript 가 "컴파일 후의 경로"를 기준으로 해석하기 때문입니다.
 *   확장자를 빼거나 ".ts" 로 쓰면 tsc 가 에러를 냅니다.
 */

import type { BaseMessage } from "@langchain/core/messages";

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
 * 본문의 소제목([2-5] 같은 것)에 대응하는 구분선을 찍습니다.
 * practice.ts 를 통째로 실행했을 때 "지금 출력이 몇 번 절의 결과인지"를
 * 스크롤하며 찾을 수 있게 해 줍니다.
 */
export function printSection(title: string): void {
  const line = "─".repeat(60);
  console.log(`\n${dim(line)}`);
  console.log(` ${bold(cyan(title))}`);
  console.log(dim(line));
}

/* ===== 타입 가드 =====
 *
 * BaseMessage 에는 tool_calls 가 없습니다. 그 필드는 AIMessage 에만 있습니다.
 * instanceof AIMessage 로 좁힐 수도 있지만, instanceof 는 @langchain/core 가
 * 두 벌 설치되면 조용히 false 가 됩니다(Step 02 의 함정 참고).
 * 그래서 여기서는 "그 필드가 실제로 있는지"만 봅니다 — duck typing 이
 * 이 경우엔 instanceof 보다 튼튼합니다.
 */

type ToolCallLike = { name?: string; args?: unknown };

function getToolCalls(m: BaseMessage): ToolCallLike[] {
  const tc = (m as { tool_calls?: unknown }).tool_calls;
  return Array.isArray(tc) ? (tc as ToolCallLike[]) : [];
}

/** content 는 문자열일 수도, 콘텐츠 블록 배열일 수도 있습니다. 텍스트만 뽑습니다. */
function textOf(m: BaseMessage): string {
  const c = m.content;
  if (typeof c === "string") return c;
  if (!Array.isArray(c)) return "";
  return c
    .map((b) => (typeof b === "string" ? b : ((b as { text?: string }).text ?? "")))
    .join("");
}

/** 메시지의 역할 이름. getType() 은 "human" | "ai" | "tool" | "system" 등을 돌려줍니다. */
function roleOf(m: BaseMessage): string {
  return typeof m.getType === "function" ? m.getType() : "unknown";
}

function truncate(s: string, n: number): string {
  const flat = s.replace(/\s+/g, " ").trim();
  return flat.length <= n ? flat : `${flat.slice(0, n)}…`;
}

/* ===== 메시지 목록 ===== */

/**
 * 메시지 배열을 한 줄에 하나씩 요약합니다.
 *
 *   [ 3] ai    → 도구 호출: write_todos, write_file
 *   [ 4] tool  ← write_todos: Updated todo list to [...]
 *
 * Deep Agent 의 결과에는 messages 가 40개씩 들어 있는 게 예사라
 * 전문을 찍으면 못 읽습니다. 기본은 요약, 필요하면 maxLen 을 키우세요.
 */
export function printMessages(messages: BaseMessage[], maxLen = 100): void {
  messages.forEach((m, i) => {
    const idx = dim(`[${String(i).padStart(2)}]`);
    const role = roleOf(m);
    const calls = getToolCalls(m);

    if (calls.length > 0) {
      const names = calls.map((c) => c.name ?? "?").join(", ");
      console.log(`${idx} ${magenta(role.padEnd(6))} → 도구 호출: ${bold(names)}`);
      return;
    }

    // ToolMessage 는 어느 도구의 결과인지가 핵심 정보입니다.
    const toolName = (m as { name?: string }).name;
    const prefix = role === "tool" && toolName ? `← ${yellow(toolName)}: ` : "";
    const body = truncate(textOf(m), maxLen);
    console.log(`${idx} ${green(role.padEnd(6))} ${prefix}${body}`);
  });
}

/* ===== todos ===== */

/** write_todos 가 만든 계획. shape 은 { content, status } 입니다. */
export type Todo = { content: string; status: "pending" | "in_progress" | "completed" };

const TODO_MARK: Record<Todo["status"], string> = {
  pending: "☐",
  in_progress: "▶",
  completed: "☑",
};

export function printTodos(todos: Todo[] | undefined): void {
  if (!todos || todos.length === 0) {
    console.log(dim("  (todos 없음 — 에이전트가 계획이 필요 없다고 판단했습니다)"));
    return;
  }
  for (const t of todos) {
    console.log(`  ${TODO_MARK[t.status]} ${t.content} ${dim(`(${t.status})`)}`);
  }
}

/* ===== files ===== */

/**
 * 가상 파일시스템의 내용. state 의 files 는
 * { [경로]: { content: string | Uint8Array, ... } } 형태입니다.
 * (구버전 v1 포맷은 content 가 string[] 이라 둘 다 받아 줍니다.)
 */
export function printFiles(files: Record<string, unknown> | undefined, showContent = false): void {
  const paths = Object.keys(files ?? {});
  if (paths.length === 0) {
    console.log(dim("  (파일 없음)"));
    return;
  }
  for (const p of paths.sort()) {
    const raw = (files as Record<string, { content?: unknown }>)[p]?.content;
    const text = Array.isArray(raw) ? raw.join("\n") : typeof raw === "string" ? raw : "";
    const bytes = typeof raw === "string" || Array.isArray(raw) ? `${text.length}자` : "(바이너리)";
    console.log(`  ${bold(p)} ${dim(bytes)}`);
    if (showContent && text) {
      for (const line of text.split("\n")) console.log(dim(`    │ ${line}`));
    }
  }
}
