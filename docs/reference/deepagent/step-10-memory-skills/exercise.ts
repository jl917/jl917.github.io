/**
 * Step 10 — 장기 메모리와 스킬 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-10-memory-skills/exercise.ts
 *
 * 각 [문제 N] 아래 빈 곳을 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 */
import "dotenv/config";
import {
  createDeepAgent,
  createMemoryMiddleware,
  createSkillsMiddleware,
  CompositeBackend,
  StateBackend,
  StoreBackend,
} from "deepagents";
import { InMemoryStore, MemorySaver } from "@langchain/langgraph";
import { tool } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

/** state 의 files 에 넣을 FileData 객체를 만드는 헬퍼. */
function textFile(content: string) {
  const now = new Date().toISOString();
  return { content, mimeType: "text/markdown", created_at: now, modified_at: now };
}

/* ===== [문제 1] 영속되는 메모리 구성하기 =====
 * /memories/ 아래만 영속되고 나머지는 스레드 안에서만 사는 에이전트를 만드세요.
 *   - memory 옵션으로 /memories/AGENTS.md 를 주입할 것
 *   - store 를 넘길 것
 * 그리고 StateBackend 만 썼을 때(= CompositeBackend 없이) 스레드를 바꾸면
 * 기억이 사라지는 것도 직접 확인해 보세요.
 * 힌트: CompositeBackend(defaultBackend, routes)
 */
async function ex1() {
  // 여기에 작성
}

/* ===== [문제 2] 스레드를 넘는 기억 확인 =====
 * 문제 1의 에이전트로 스레드 A 에서 "나는 다크 모드를 쓴다. 기억해줘" 라고 말하고,
 * 완전히 다른 스레드 B 에서 "내 테마 취향이 뭐였지?" 라고 물어보세요.
 * 스레드 B 가 답할 수 있어야 합니다.
 * 그리고 store.search() 로 실제로 뭐가 저장됐는지 키를 출력하세요.
 * 힌트: 에이전트가 저장하게 하려면 systemPrompt 에 저장 규칙을 적어줘야 합니다.
 */
async function ex2() {
  // 여기에 작성
}

/* ===== [문제 3] 사용자별 네임스페이스 격리 =====
 * 사용자 "alice" 와 "bob" 이 서로의 메모리를 절대 볼 수 없도록
 * StoreBackend 의 namespace 를 구성하세요.
 * 같은 store 인스턴스를 공유하되 네임스페이스만 분리해야 합니다.
 * alice 로 뭔가 저장한 뒤 bob 으로 물어봤을 때 모른다고 답하는지 확인하세요.
 * 힌트: namespace 는 문자열 배열을 반환하는 팩토리 함수입니다.
 */
async function ex3() {
  // 여기에 작성
}

/* ===== [문제 4] createMemoryMiddleware 직접 쓰기 =====
 * memory: [...] 옵션 대신 createMemoryMiddleware 를 직접 붙이세요.
 *   - sources 는 /memories/AGENTS.md 와 /memories/STYLE.md 두 개
 *   - Anthropic 프롬프트 캐싱이 걸리도록 설정
 * 힌트: 미들웨어에도 backend 를 넘겨야 합니다. 에이전트와 같은 인스턴스를 쓰세요.
 */
async function ex4() {
  // 여기에 작성
}

/* ===== [문제 5] SKILL.md 작성하기 =====
 * "커밋 메시지 작성" 스킬의 SKILL.md 를 문자열로 작성하세요.
 *   - frontmatter 에 name 과 description 이 있어야 합니다
 *   - name 은 소문자·하이픈이고 부모 디렉터리 이름과 같아야 합니다
 *   - description 에는 "언제 이 스킬을 켜야 하는지" 활성화 키워드가 들어가야 합니다
 *   - 본문에는 절차를 번호 매겨 적으세요
 * 그리고 이 스킬을 files 로 주입해 에이전트가 쓰게 하세요.
 * 힌트: 경로는 /skills/{name}/SKILL.md 여야 합니다.
 */
const COMMIT_SKILL = `
// 여기에 작성
`;

async function ex5() {
  // 여기에 작성
}

/* ===== [문제 6] 스킬 vs 도구 판별 =====
 * 아래 세 가지 요구사항을 스킬 / 도구 / 서브에이전트 중 무엇으로 구현해야 할지
 * 고르고, 그 이유를 주석으로 적으세요. 그리고 하나를 골라 실제로 구현하세요.
 *
 *   (a) 현재 시각을 조회한다
 *   (b) 우리 팀의 PR 리뷰 절차(5단계)를 따르게 한다
 *   (c) 50개 파일을 각각 읽고 요약해 최종 리포트를 만든다
 *
 * 답:
 *   (a) → ?  이유:
 *   (b) → ?  이유:
 *   (c) → ?  이유:
 */
async function ex6() {
  // 여기에 작성
}

/* ===== [문제 7] 스킬을 읽기 전용으로 잠그기 =====
 * 에이전트가 /skills 아래를 읽을 수는 있지만 절대 수정할 수 없게 permissions 를 거세요.
 * /memories 와 /workspace 는 읽기·쓰기 모두 허용해야 합니다.
 * 그 외 경로는 전부 금지하세요.
 * 힌트: Step 09 의 first-match-wins 와 "마지막 빗장" 을 떠올리세요.
 */
async function ex7() {
  // 여기에 작성
}

/* ===== [문제 8] grep RAG =====
 * 벡터 DB 없이 내장 grep/glob 만으로 문서를 뒤져 답하는 에이전트를 만드세요.
 * files 로 /docs 아래에 문서 3개를 넣고, 그중 하나에만 있는 사실을 물어보세요.
 * 힌트: systemPrompt 로 "grep 으로 직접 뒤져라" 를 지시해야 합니다.
 *       files 값은 문자열이 아니라 FileData 객체입니다 — textFile() 을 쓰세요.
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
