/**
 * Step 12 — 종합 프로젝트: 딥 리서치 에이전트
 * 서브에이전트 정의.
 */
import "dotenv/config";
import type { SubAgent } from "deepagents";
import { createSearchTool } from "./tools.js";
import { RESEARCHER_PROMPT, CRITIQUE_PROMPT } from "./prompts.js";

/* ===== [12-3] 모델 선택 — 역할마다 다른 모델 ===== */

/**
 * 부모(종합) 모델. 여러 findings 를 읽고 하나의 보고서로 엮는, 이 시스템에서 가장 어려운 일을 합니다.
 * 여기서 아끼면 보고서 품질이 무너집니다.
 */
export const ORCHESTRATOR_MODEL =
  process.env.ORCHESTRATOR_MODEL ?? "anthropic:claude-sonnet-4-6";

/**
 * 조사 모델. 검색 결과를 읽고 요약하는, 상대적으로 쉬운 일입니다.
 * 호출 횟수가 가장 많은 것도 여기라서, 싼 모델로 바꿨을 때 비용 절감 효과가 가장 큽니다.
 *
 * OpenAI 를 쓰려면: ORCHESTRATOR_MODEL=openai:gpt-5.5 RESEARCH_MODEL=openai:gpt-5.5-mini
 */
export const RESEARCH_MODEL =
  process.env.RESEARCH_MODEL ?? "anthropic:claude-haiku-4-5";

/** 비평 모델. 판정만 내리므로 싼 모델로 충분합니다. */
export const CRITIQUE_MODEL = process.env.CRITIQUE_MODEL ?? RESEARCH_MODEL;

/* ===== [12-3] 조사 서브에이전트 ===== */

/**
 * 조사 담당.
 *
 * `description` 은 부모가 "언제 이 서브에이전트를 부를지" 판단하는 **유일한 근거**입니다.
 * 부모는 systemPrompt 를 볼 수 없습니다. description 이 부실하면 부모가 안 부릅니다.
 */
export const researchSubagent: SubAgent = {
  name: "research-subagent",
  description:
    "웹 검색으로 하나의 구체적인 주제를 깊이 조사하고, 결과를 /findings/ 에 저장한 뒤 요약을 돌려줍니다. " +
    "한 번에 하나의 주제만 주세요. 배경 설명을 포함해 자기완결적으로 지시해야 합니다.",
  systemPrompt: RESEARCHER_PROMPT,
  tools: [createSearchTool()],
  model: RESEARCH_MODEL,
};

/* ===== [12-3] 비평 서브에이전트 ===== */

/**
 * 비평 담당.
 *
 * `tools` 를 **빈 배열로 두지 않은** 것에 주의하세요. 빈 배열이면 파일도 못 읽습니다.
 * 여기서는 tools 를 아예 지정하지 않아, 파일시스템 도구(read_file/ls)는 그대로 상속받고
 * web_search 는 부모가 갖고 있지 않으므로 자연히 없습니다.
 */
export const critiqueSubagent: SubAgent = {
  name: "critique-subagent",
  description:
    "/findings/ 의 조사 결과를 심사해 PASS 또는 REVISE 를 판정합니다. 검색은 하지 않습니다. " +
    "인용 누락, 출처 유령, 질문 미달, 근거 빈약, 모순을 찾아냅니다. 조사가 끝난 뒤 호출하세요.",
  systemPrompt: CRITIQUE_PROMPT,
  model: CRITIQUE_MODEL,
};

export const allSubagents: SubAgent[] = [researchSubagent, critiqueSubagent];
