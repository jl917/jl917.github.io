/**
 * Step 09 — HITL과 권한 제어 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-09-hitl-permissions/exercise.ts
 *
 * 각 [문제 N] 아래 빈 곳을 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 */
import "dotenv/config";
import { createDeepAgent, StateBackend } from "deepagents";
import { Command, MemorySaver } from "@langchain/langgraph";
import { tool } from "langchain";
import type { HITLRequest, HITLResponse, Interrupt } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [문제 1] 승인 게이트 켜기 =====
 * write_file 에만 승인을 걸고 read_file / ls 는 자동 승인되는 에이전트를 만드세요.
 * 힌트: interruptOn 과 checkpointer 를 함께 줘야 합니다.
 *       checkpointer 를 빼면 어떻게 되는지도 직접 확인해 보세요.
 */
async function ex1() {
  // 여기에 작성
}

/* ===== [문제 2] __interrupt__ 읽기 =====
 * 문제 1의 에이전트에게 "/notes/todo.md 에 '할 일'이라고 써줘" 를 시키고,
 * interrupt 가 발생하면 actionRequests 의 도구 이름과 인자를 출력하세요.
 * 그리고 reviewConfigs 에서 그 도구의 allowedDecisions 를 찾아 출력하세요.
 * 힌트: reviewConfigs 는 actionName 으로 찾습니다.
 */
async function ex2() {
  // 여기에 작성
}

/* ===== [문제 3] edit 결정으로 경로 교정 =====
 * 문제 2에서 받은 interrupt 를 approve 하지 말고 edit 으로 재개하세요.
 * 모델이 요청한 file_path 가 무엇이든 "/workspace/todo.md" 로 바꿔서 실행되게 하세요.
 * 힌트: editedAction 에는 name 과 args 를 "전부" 다시 줘야 합니다.
 *       원본 args 를 스프레드한 뒤 file_path 만 덮어쓰세요.
 */
async function ex3() {
  // 여기에 작성
}

/* ===== [문제 4] reject 와 피드백 =====
 * write_file 을 reject 하되, message 에 "쓰기는 금지입니다. 대신 내용을 채팅으로 알려주세요."
 * 를 넣어 모델에게 피드백하세요. 재개 후 모델이 뭐라고 응답하는지 확인하세요.
 * 재개 결과에 또 __interrupt__ 가 있는지도 확인하세요 (모델이 재시도할 수 있습니다).
 */
async function ex4() {
  // 여기에 작성
}

/* ===== [문제 5] permissions 로 샌드박싱 =====
 * 다음을 만족하는 permissions 배열을 작성하세요.
 *   - /workspace 아래: 읽기·쓰기 모두 허용
 *   - /config 아래: 읽기만 허용, 쓰기 금지
 *   - 그 외 모든 경로: 읽기·쓰기 모두 금지
 * 힌트: 규칙은 first-match-wins 이고, 아무 규칙에도 안 걸리면 기본이 allow 입니다.
 *       규칙 순서가 중요합니다. 그리고 마지막 "빗장" 규칙을 잊지 마세요.
 */
async function ex5() {
  // 여기에 작성
}

/* ===== [문제 6] when 으로 조건부 승인 =====
 * 아래 transferMoney 도구에 승인을 걸되,
 * 100000원 이하는 자동 승인되고 그보다 크면 사람을 부르게 하세요.
 * 힌트: InterruptOnConfig 의 when 은 true 를 반환하면 "멈춘다" 입니다.
 */
const transferMoney = tool(
  async ({ to, amount }) => `${to} 에게 ${amount}원을 송금했습니다.`,
  {
    name: "transfer_money",
    description: "지정한 상대에게 돈을 송금한다.",
    schema: z.object({ to: z.string(), amount: z.number() }),
  },
);

async function ex6() {
  // 여기에 작성
}

/* ===== [문제 7] 읽기 전용 서브에이전트 =====
 * "reviewer" 서브에이전트를 정의하세요.
 *   - 어떤 경로에도 쓰기를 할 수 없어야 합니다
 *   - /workspace 아래는 읽을 수 있어야 합니다
 *   - 부모의 permissions 를 상속하면 안 됩니다 (통째로 교체)
 * 부모 에이전트는 /workspace 읽기·쓰기가 모두 허용된 상태로 두세요.
 * 힌트: permissions 를 생략하면 상속, 명시하면 교체, [] 는 무제한입니다.
 */
async function ex7() {
  // 여기에 작성
}

/* ===== [문제 8] 승인 루프 =====
 * 에이전트를 실행하고, __interrupt__ 가 사라질 때까지 반복하며
 * 모든 요청을 자동으로 approve 하는 루프를 작성하세요.
 * 단, 무한 루프를 막기 위해 최대 5회까지만 재개하고 그 이상이면 경고를 출력하세요.
 * 힌트: while (result.__interrupt__) 형태이되 카운터를 두세요.
 *       decisions 배열의 길이는 actionRequests 길이와 같아야 합니다.
 */
async function ex8() {
  // 여기에 작성
}

async function main() {
  await ex1();
  await ex2();
  await ex3();
  await ex4();
  await ex5();
  await ex6();
  await ex7();
  await ex8();
}

main().catch(console.error);
