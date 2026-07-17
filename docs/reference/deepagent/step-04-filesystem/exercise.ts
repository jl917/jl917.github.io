/**
 * Step 04 — 가상 파일시스템 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-04-filesystem/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 *
 * [문제 2] 와 [문제 7] 은 모델을 호출하지 않으므로 API 키 없이 풀 수 있습니다.
 */
import "dotenv/config";
import { createDeepAgent, createFilesystemMiddleware, StateBackend } from "deepagents";
import { createAgent, tool } from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import * as z from "zod";

/** files 상태 출력 헬퍼 (v1/v2 유니온 분기 포함) */
function printFiles(files: Record<string, any> | undefined, label = "files") {
  if (!files || Object.keys(files).length === 0) {
    console.log(`  (${label}: 비어 있음)`);
    return;
  }
  console.log(`  ${label} (${Object.keys(files).length}개):`);
  for (const [path, data] of Object.entries(files)) {
    const raw = data?.content;
    const text = Array.isArray(raw) ? raw.join("\n") : typeof raw === "string" ? raw : "<binary>";
    console.log(`    ${path}  (${text.length}자)`);
  }
}

/** 마지막 AI 메시지의 input_tokens 를 꺼내는 헬퍼 */
function getUsage(result: any): number | undefined {
  const aiMessages = result.messages.filter((m: any) => m.getType?.() === "ai");
  return (aiMessages.at(-1) as any)?.usage_metadata?.input_tokens;
}

/** 2000줄 로그를 반환하는 도구. ERROR 는 정확히 20건(i % 97 === 0). */
const fetchLogs = tool(
  async ({ service }) => {
    const lines: string[] = [];
    for (let i = 1; i <= 2000; i++) {
      const level = i % 97 === 0 ? "ERROR" : i % 13 === 0 ? "WARN" : "INFO";
      lines.push(
        `2026-07-17T10:${String(i % 60).padStart(2, "0")}:00Z [${level}] ` +
          `${service} request_id=req-${i} latency=${(i * 7) % 900}ms`,
      );
    }
    return lines.join("\n");
  },
  {
    name: "fetch_logs",
    description: "지정한 서비스의 최근 로그를 반환한다. 결과가 매우 길 수 있다.",
    schema: z.object({ service: z.string().describe("서비스 이름") }),
  },
);

/* ===== [문제 1] =====
 * fetchLogs 도구를 준 에이전트 두 개를 만드세요.
 *   (A) 파일시스템 있음 — createDeepAgent
 *   (B) 파일시스템 없음 — createAgent, 미들웨어 없음
 *
 * 같은 로그 분석 작업을 시키고
 * 마지막 AI 메시지의 usage_metadata.input_tokens 를 비교하세요.
 *
 * 힌트: getUsage(result) 헬퍼를 쓰세요.
 */
async function ex1() {
  console.log("\n===== [문제 1] 파일시스템 유무의 토큰 차이 =====");

  const task = "payment 서비스 로그를 분석해서 ERROR 가 몇 건인지 알려줘.";

  // TODO: (A) createDeepAgent 로 파일시스템 있는 에이전트를 만들고 실행하세요.
  //       systemPrompt 에 "긴 결과는 파일로 저장하고 grep 으로 찾아라" 를 넣으세요.

  // TODO: (B) createAgent 로 파일시스템 없는 에이전트를 만들고 실행하세요.

  // TODO: 두 결과의 input_tokens 를 비교해 출력하세요.

  // → (A) input_tokens: (여기에 답)
  // → (B) input_tokens: (여기에 답)
  // → 왜 차이가 나는가? (여기에 답)
}

/* ===== [문제 2] =====
 * createFilesystemMiddleware({ backend: new StateBackend() }) 에서
 * 도구 목록과 각 도구의 파라미터를 출력하세요.
 * read_file 의 offset/limit "기본값" 을 코드로 확인하세요.
 *
 * 힌트:
 *   - mw.tools 로 도구 배열에 접근합니다.
 *   - read_file 은 z.preprocess 로 감싸여 있어 스키마가 한 겹 안쪽입니다.
 *     ZodPreprocess 는 pipe(in → out) 구조라 객체 스키마는 여기 있습니다:
 *       t.schema._def.out.shape
 *   - 기본값을 꺼내는 방법은 두 가지입니다. 둘 다 해보고 어느 쪽이 나은지 판단하세요.
 *       (1) shape.offset._def.defaultValue     ← zod v4 에서는 함수가 아니라 값입니다
 *       (2) t.schema.parse({ file_path: "/a.txt" })  ← 기본값이 채워져 돌아옵니다
 *
 * 이 문제는 모델을 호출하지 않습니다 (API 키 불필요).
 */
async function ex2() {
  console.log("\n===== [문제 2] 도구 스키마 직접 확인 =====");

  // TODO: 미들웨어를 만들고 도구 이름 목록을 출력하세요.

  // TODO: 각 도구의 파라미터 이름을 출력하세요.

  // TODO: read_file 의 offset/limit 기본값을 꺼내 출력하세요.

  // → 도구 목록: (여기에 답)
  // → read_file 의 offset 기본값: (여기에 답)
  // → read_file 의 limit 기본값: (여기에 답)
}

/* ===== [문제 3] =====
 * 에이전트에게 2000줄 로그를 저장시킨 뒤,
 *   (A) read_file 을 "limit 없이" 부르게 하는 프롬프트
 *   (B) "limit: 20" 으로 부르게 하는 프롬프트
 * 를 각각 작성해 input_tokens 차이를 재세요.
 *
 * 힌트: (A) 는 "파일 전체를 읽어라", (B) 는 "앞 20줄만 읽어라" 처럼 유도합니다.
 */
async function ex3() {
  console.log("\n===== [문제 3] read_file 의 limit 효과 =====");

  // TODO: (A) 전체를 읽게 유도하는 에이전트를 만들고 실행하세요.

  // TODO: (B) limit 20 으로 읽게 유도하는 에이전트를 만들고 실행하세요.

  // TODO: 모델이 실제로 어떤 인자로 read_file 을 불렀는지 출력하세요.
  //       (result.messages 에서 tool_calls 를 찾아보세요)

  // TODO: 두 경우의 input_tokens 를 비교하세요.

  // → (A) read_file 인자 / input_tokens: (여기에 답)
  // → (B) read_file 인자 / input_tokens: (여기에 답)
}

/* ===== [문제 4] =====
 * /docs/api.md 에 "JSON" 이라는 단어가 3번 들어가는 문서를 쓰고,
 * edit_file 로 old_string: "JSON" 을 치환하게 시키세요.
 *
 * 어떤 에러가 나나요? 에러 메시지 전문을 기록하고,
 * 두 가지 방법으로 해결하세요.
 */
const DOC_WITH_3_JSON = [
  "# API 가이드",
  "",
  "## 요청",
  "요청 본문은 JSON 으로 보낸다.",
  "",
  "## 응답",
  "응답 본문도 JSON 이다.",
  "",
  "## 에러",
  "에러 응답 역시 JSON 형식을 따른다.",
].join("\n");

async function ex4() {
  console.log("\n===== [문제 4] edit_file 의 다중 매치 =====");

  // TODO: 위 DOC_WITH_3_JSON 을 /docs/api.md 에 저장시키세요.

  // TODO: old_string: "JSON" 으로 치환을 시도하게 하세요.

  // TODO: edit_file 이 반환한 ToolMessage 를 출력해 에러를 확인하세요.

  // TODO: 해결책 1 — replace_all: true 로 전부 바꾸기

  // TODO: 해결책 2 — 주변 문맥을 포함해 old_string 을 유일하게 만들기

  // → 에러 메시지 전문: (여기에 답)
  // → 왜 이게 안전장치인가? (여기에 답)
}

/* ===== [문제 5] =====
 * 에이전트에게 "ERROR|WARN" 정규식으로 grep 하도록 유도하고, 결과를 관찰하세요.
 * 에러가 나나요, 아니면 "없음" 이 나오나요?
 * 모델은 이후 어떻게 행동하나요?
 */
async function ex5() {
  console.log("\n===== [문제 5] grep 은 정규식이 아니다 =====");

  // TODO: 로그를 저장시킨 뒤, 정규식으로 grep 하도록 유도하세요.
  //       예: "grep 으로 정규식 ERROR|WARN 을 써서 문제 줄을 찾아라"

  // TODO: grep ToolMessage 의 반환 문자열을 출력하세요.

  // TODO: 모델의 최종 결론을 출력하세요.

  // → grep 반환: (여기에 답)
  // → 에러인가 "없음" 인가? (여기에 답)
  // → 모델의 결론은? 이게 왜 위험한가? (여기에 답)
}

/* ===== [문제 6] =====
 * MemorySaver 를 붙인 에이전트에서
 *   (A) 같은 thread_id 로 2회 호출
 *   (B) 다른 thread_id 로 호출
 *   (C) 체크포인터 없이 2회 호출
 * 세 경우의 files 를 비교하세요.
 */
async function ex6() {
  console.log("\n===== [문제 6] 체크포인터와 파일의 수명 =====");

  // TODO: (A) MemorySaver + 같은 thread_id 로 저장 → 읽기

  // TODO: (B) 같은 에이전트, 다른 thread_id 로 읽기

  // TODO: (C) 체크포인터 없는 에이전트로 저장 → 읽기

  // → (A) 파일이 남았나? (여기에 답)
  // → (B) 파일이 보이나? (여기에 답)
  // → (C) 파일이 남았나? (여기에 답)
  // → thread_id 만 주고 체크포인터를 안 주면 어떻게 되나? (여기에 답)
}

/* ===== [문제 7] =====
 * createFilesystemMiddleware({ tools: [...] }) 로
 * "read_file 을 뺀" 허용목록을 만들어 보세요.
 * 어떻게 되나요? 왜 read_file 이 필수인지 설명하세요.
 *
 * 힌트: 생성 시점에 에러가 나는지, 아니면 조용히 통과하는지 확인하세요.
 *       try/catch 로 감싸고 에러 메시지 전문을 기록하세요.
 * 이 문제는 모델을 호출하지 않습니다 (API 키 불필요).
 */
async function ex7() {
  console.log("\n===== [문제 7] read_file 없는 허용목록 =====");

  // TODO: tools: ["ls", "glob", "grep"] 처럼 read_file 을 뺀 미들웨어를 만드세요.
  //       생성 시점에 에러가 나나요? (try/catch)

  // TODO: read_file 을 포함한 올바른 읽기 전용 구성도 만들어
  //       노출되는 도구 목록을 출력하고 비교하세요.

  // → 생성 시 에러가 나나? 에러 메시지 전문은? (여기에 답)
  // → read_file 이 없으면 무엇이 불가능해지나? (여기에 답)
  //   (힌트: 자동 오프로딩으로 "파일 경로 + 앞 10줄" 만 남았을 때를 생각하세요)
}

/* ===== [문제 8] =====
 * 4-7 의 오프로딩 패턴을 구현하되,
 *   (A) 리포트를 "파일로 저장하는" 버전
 *   (B) 리포트를 "최종 응답에만 담는" 버전
 * 을 만드세요.
 *
 * 이어서 후속 질문을 한 번 더 던져 두 버전의 input_tokens 를 비교하세요.
 *
 * 힌트: 후속 질문을 하려면 MemorySaver + 같은 thread_id 가 필요합니다.
 */
async function ex8() {
  console.log("\n===== [문제 8] 산출물도 오프로딩 대상이다 =====");

  // TODO: (A) 리포트를 /reports/summary.md 에 저장하고
  //       최종 응답에는 요약 + 경로만 담는 에이전트를 만드세요.
  //       MemorySaver 를 붙이세요.

  // TODO: (B) 리포트 전문을 최종 응답에 담는 에이전트를 만드세요.
  //       MemorySaver 를 붙이세요.

  // TODO: 각각 로그 분석을 시키세요 (1차 호출).

  // TODO: 같은 thread_id 로 후속 질문을 던지세요 (2차 호출).
  //       예: "그래서 가장 심각한 문제가 뭐야?"

  // TODO: 2차 호출의 input_tokens 를 비교하세요.

  // → (A) 2차 input_tokens: (여기에 답)
  // → (B) 2차 input_tokens: (여기에 답)
  // → 대화가 더 길어지면 격차는 어떻게 되나? (여기에 답)
}

/* ===== 실행 ===== */

async function main() {
  // 풀고 있는 문제만 주석을 해제하세요.
  await ex1();
  await ex2();
  await ex3();
  await ex4();
  await ex5();
  await ex6();
  await ex7();
  await ex8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
