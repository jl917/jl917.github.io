/**
 * Step 12 — 종합 프로젝트: 딥 리서치 에이전트
 * CLI 진입점.
 *
 * 실행:
 *   npx tsx docs/reference/deepagent/step-12-final-project/cli.ts "질문"
 *   npx tsx docs/reference/deepagent/step-12-final-project/cli.ts --approve "질문"
 *   npx tsx docs/reference/deepagent/step-12-final-project/cli.ts --out ./report.md "질문"
 */
import "dotenv/config";
import * as readline from "node:readline/promises";
import { stdin, stdout } from "node:process";
import { writeFile } from "node:fs/promises";
import { Command } from "@langchain/langgraph";
import { buildResearchAgent, readStateFile, listStateFiles } from "./agent.js";
import { searchToolLabel } from "./tools.js";

/* ===== [12-8] 인자 파싱 ===== */

const argv = process.argv.slice(2);

const requireApproval = argv.includes("--approve");
const outIndex = argv.indexOf("--out");
const outPath = outIndex >= 0 ? argv[outIndex + 1] : undefined;

// 플래그와 그 값을 걷어내고 남은 것이 질문입니다.
const question =
  argv
    .filter((a, i) => {
      if (a === "--approve") return false;
      if (a === "--out") return false;
      if (outIndex >= 0 && i === outIndex + 1) return false;
      return true;
    })
    .join(" ")
    .trim() || "RAG와 파인튜닝의 주요 차이점은 무엇인가?";

if (!process.env.ANTHROPIC_API_KEY) {
  console.error(
    "ANTHROPIC_API_KEY 가 없습니다. .env 에 넣거나 환경변수로 주세요.\n" +
      "(검색은 키 없이도 목으로 동작하지만, 모델 호출은 키가 필요합니다.)",
  );
  process.exit(1);
}

console.log("=".repeat(72));
console.log("딥 리서치 에이전트");
console.log("=".repeat(72));
console.log(`질문   : ${question}`);
console.log(`검색   : ${searchToolLabel()}`);
console.log(`승인   : ${requireApproval ? "켜짐 (/report.md 쓰기 전 확인)" : "꺼짐"}`);
console.log("=".repeat(72));
console.log();

/* ===== [12-8] 실행 ===== */

const agent = buildResearchAgent({ requireApproval });

// thread_id 는 체크포인터가 대화를 식별하는 키입니다.
// HITL 로 멈췄다가 재개할 때 **같은 thread_id 로 다시 호출**해야 이어집니다.
const config = { configurable: { thread_id: crypto.randomUUID() } };

const input = { messages: [{ role: "user" as const, content: question }] };

// recursionLimit 기본값(25)은 딥 리서치에 턱없이 부족합니다.
// 계획 → 위임 → 비평 → 종합만 해도 스텝이 수십 개를 넘어갑니다.
// 넘으면 GraphRecursionError 가 납니다 — 무한루프가 아니라 그냥 일이 많은 겁니다.
let result = await agent.invoke(input, { ...config, recursionLimit: 100 });

/* ===== [12-7] HITL — 최종 보고서 승인 ===== */

// interrupt 로 멈추면 결과에 __interrupt__ 가 들어 있습니다.
// 승인/거절할 때까지 이 루프를 돕니다 — 한 번 거절하면 모델이 다시 쓰려 하므로 또 멈춥니다.
while (requireApproval && "__interrupt__" in result) {
  const interrupts = (result as Record<string, unknown>)["__interrupt__"] as Array<{
    value?: unknown;
  }>;

  const payload = interrupts?.[0]?.value as
    | { actionRequests?: Array<{ name: string; args: Record<string, unknown> }> }
    | undefined;
  const action = payload?.actionRequests?.[0];

  console.log("\n" + "-".repeat(72));
  console.log("[승인 요청] 에이전트가 최종 보고서를 쓰려 합니다.");
  console.log("-".repeat(72));

  const draft = String(action?.args?.content ?? "");
  // 보고서 전문을 다 보여주면 터미널이 넘칩니다. 앞부분만 보여주고 길이를 알려줍니다.
  console.log(draft.slice(0, 1_500));
  if (draft.length > 1_500) {
    console.log(`\n... (총 ${draft.length}자 중 앞 1500자만 표시)`);
  }
  console.log("-".repeat(72));

  const rl = readline.createInterface({ input: stdin, output: stdout });
  const answer = (await rl.question("승인하시겠습니까? [y=승인 / n=거절] : "))
    .trim()
    .toLowerCase();
  rl.close();

  // 재개는 Command({ resume: ... }) 로 합니다.
  // resume 페이로드는 { decisions: [...] } 모양이어야 합니다 — 배열이 아니라 객체입니다.
  if (answer === "y") {
    result = await agent.invoke(
      new Command({ resume: { decisions: [{ type: "approve" }] } }),
      { ...config, recursionLimit: 100 },
    );
  } else {
    const rl2 = readline.createInterface({ input: stdin, output: stdout });
    const reason =
      (await rl2.question("거절 사유 (모델에게 전달됩니다) : ")).trim() ||
      "보고서가 요구사항을 만족하지 않습니다. 다시 작성하세요.";
    rl2.close();

    result = await agent.invoke(
      new Command({ resume: { decisions: [{ type: "reject", message: reason }] } }),
      { ...config, recursionLimit: 100 },
    );
  }
}

/* ===== [12-8] 결과 출력 ===== */

const files = (result as { files?: Record<string, unknown> }).files;

console.log("\n" + "=".repeat(72));
console.log("생성된 파일");
console.log("=".repeat(72));
for (const path of listStateFiles(files)) {
  const body = readStateFile(files, path) ?? "";
  console.log(`  ${path}  (${body.length}자)`);
}

const report = readStateFile(files, "/report.md");

console.log("\n" + "=".repeat(72));
console.log("최종 보고서 (/report.md)");
console.log("=".repeat(72));
console.log(report ?? "(보고서가 생성되지 않았습니다)");

if (outPath && report) {
  await writeFile(outPath, report, "utf-8");
  console.log(`\n→ ${outPath} 에 저장했습니다.`);
}

/* ===== [12-9] 사용량 ===== */

// usage_metadata 는 AIMessage 에만 있습니다. 서브에이전트의 토큰은 부모 메시지에 안 잡힙니다
// (그게 컨텍스트 격리의 요점입니다). 따라서 이 숫자는 **부모만의 사용량**입니다.
const messages = result.messages ?? [];
let inputTokens = 0;
let outputTokens = 0;
for (const m of messages) {
  // usage_metadata 는 AIMessage 에만 있으므로 BaseMessage 타입에는 없습니다.
  // unknown 을 거쳐 좁히는 것이 정직한 방법입니다 — 바로 단언하면 tsc 가 막습니다(TS2352).
  const usage = (m as unknown as { usage_metadata?: unknown }).usage_metadata as
    | { input_tokens?: number; output_tokens?: number }
    | undefined;
  if (usage) {
    inputTokens += usage.input_tokens ?? 0;
    outputTokens += usage.output_tokens ?? 0;
  }
}

console.log("\n" + "=".repeat(72));
console.log("사용량 (부모 에이전트만 — 서브에이전트 토큰은 포함되지 않습니다)");
console.log("=".repeat(72));
console.log(`  입력 토큰 : ${inputTokens.toLocaleString()}`);
console.log(`  출력 토큰 : ${outputTokens.toLocaleString()}`);
console.log(`  메시지 수 : ${messages.length}`);
