/**
 * Step 12 — 종합 프로젝트: 딥 리서치 에이전트
 * 에이전트 배선.
 */
import "dotenv/config";
import { createDeepAgent } from "deepagents";
import { MemorySaver } from "@langchain/langgraph";
import { ORCHESTRATOR_PROMPT } from "./prompts.js";
import { allSubagents, ORCHESTRATOR_MODEL } from "./subagents.js";

/* ===== [12-7] HITL 옵션 ===== */

export interface BuildOptions {
  /**
   * true 면 /report.md 를 쓰기 직전에 멈춰 사람의 승인을 받습니다.
   * checkpointer 가 반드시 함께 있어야 합니다 — 없으면 멈춘 지점을 저장할 곳이 없습니다.
   */
  requireApproval?: boolean;
}

/* ===== [12-1] 에이전트 조립 ===== */

/**
 * 딥 리서치 에이전트를 만듭니다.
 *
 * 설계 요약:
 * - 부모는 web_search 를 **갖지 않습니다**. tools 가 비어 있는 게 실수가 아니라 설계입니다.
 *   부모가 검색하면 결과 원문이 부모 컨텍스트에 쌓여 서브에이전트의 존재 이유가 사라집니다.
 * - 검색 도구는 research-subagent 만 갖습니다 (subagents.ts 참고).
 * - 파일시스템/write_todos/task 는 deepagents 가 기본으로 넣어 줍니다.
 * - 백엔드를 지정하지 않았으므로 기본값인 StateBackend 를 씁니다.
 *   파일이 실제 디스크가 아니라 **에이전트 상태(state) 안**에만 존재합니다.
 */
export function buildResearchAgent(options: BuildOptions = {}) {
  const { requireApproval = false } = options;

  // 체크포인터는 HITL 에 필수입니다. interrupt 는 "여기서 멈췄다"를 어딘가에 적어야 재개할 수 있는데,
  // 그 어딘가가 체크포인터입니다. MemorySaver 는 프로세스 메모리라 재시작하면 사라집니다(문제 5 참고).
  const checkpointer = requireApproval ? new MemorySaver() : undefined;

  return createDeepAgent({
    model: ORCHESTRATOR_MODEL,
    systemPrompt: ORCHESTRATOR_PROMPT,
    tools: [], // 의도적으로 비움 — 위 주석 참고
    subagents: allSubagents,
    checkpointer,
    // write_file 중에서도 /report.md 를 쓸 때만 멈춥니다.
    // when 없이 write_file 전체에 걸면 findings 저장마다 승인 창이 떠서 실습이 불가능해집니다.
    interruptOn: requireApproval
      ? {
          write_file: {
            allowedDecisions: ["approve", "edit", "reject"],
            when: (request) =>
              String(request.toolCall.args.file_path ?? "") === "/report.md",
          },
        }
      : undefined,
  });
}

/* ===== [12-4] 상태에서 파일 꺼내기 ===== */

/**
 * StateBackend 가 상태에 넣어 둔 파일 내용을 문자열로 꺼냅니다.
 *
 * ⚠️ 파일 content 는 두 가지 형식이 존재합니다.
 *   - v1: string[] (줄 배열)
 *   - v2: string | Uint8Array (텍스트 또는 바이너리)
 * 어느 쪽이 올지 가정하고 `.join("\n")` 만 부르면 v2 에서 조용히 깨집니다
 * (문자열에 .join 은 없으므로 TypeError, 혹은 타입 단언을 썼다면 이상한 값).
 * 그래서 두 형식을 모두 받아냅니다.
 */
export function readStateFile(
  files: Record<string, unknown> | undefined,
  path: string,
): string | undefined {
  const entry = files?.[path] as { content?: unknown } | undefined;
  if (!entry) return undefined;

  const content = entry.content;
  if (typeof content === "string") return content; // v2 텍스트
  if (Array.isArray(content)) return content.join("\n"); // v1 줄 배열
  if (content instanceof Uint8Array) {
    return new TextDecoder().decode(content); // v2 바이너리
  }
  return undefined;
}

/** 상태에 들어 있는 파일 경로를 정렬해 돌려줍니다. CLI 에서 결과 요약에 씁니다. */
export function listStateFiles(files: Record<string, unknown> | undefined): string[] {
  return Object.keys(files ?? {}).sort();
}
