/**
 * Step 04 — 가상 파일시스템
 * 실행: npx tsx docs/reference/deepagent/step-04-filesystem/practice.ts
 *
 * 이 파일은 본문 4-1 ~ 4-9 의 예제를 순서대로 담았습니다.
 * 블록 주석의 [4-N] 번호가 본문 소제목 번호와 1:1 대응합니다.
 *
 * 주의: LLM 응답은 비결정적입니다. 하지만 파일시스템 도구의 반환 문자열
 *       ("Successfully wrote to '/x.md'" 등)과 files 상태의 구조는 결정적입니다.
 */
import "dotenv/config";
import { createDeepAgent, createFilesystemMiddleware, StateBackend } from "deepagents";
import { createAgent, tool } from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import * as z from "zod";

/**
 * files 상태를 사람이 읽기 좋게 출력하는 헬퍼.
 *
 * FileData 는 v1/v2 두 형식의 유니온입니다.
 *   v1: { content: string[] (줄 배열), created_at, modified_at }
 *   v2: { content: string | Uint8Array, mimeType, created_at, modified_at }
 * 새로 쓰는 파일은 v2 로 저장됩니다.
 */
function printFiles(files: Record<string, any> | undefined, label = "files") {
  if (!files || Object.keys(files).length === 0) {
    console.log(`  (${label}: 비어 있음)`);
    return;
  }
  console.log(`  ${label} (${Object.keys(files).length}개):`);
  for (const [path, data] of Object.entries(files)) {
    const raw = data?.content;
    const text = Array.isArray(raw)            // v1 형식
      ? raw.join("\n")
      : typeof raw === "string"                 // v2 텍스트
        ? raw
        : `<binary ${data?.mimeType ?? "?"}>`;  // v2 바이너리
    const bytes = typeof text === "string" ? text.length : 0;
    console.log(`    ${path}  (${bytes}자, mime=${data?.mimeType ?? "-"})`);
  }
}

/* ===== [4-1] 컨텍스트 오프로딩 — 파일시스템은 외부 기억장치다 ===== */

/**
 * 아주 긴 결과를 반환하는 도구.
 * 이런 도구의 결과를 통째로 컨텍스트에 넣으면 창이 금방 찹니다.
 */
const fetchLogs = tool(
  async ({ service }) => {
    // 실제 로그 대신 긴 더미 텍스트를 만듭니다 (약 2000줄).
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

async function step4_1() {
  console.log("\n===== [4-1] 컨텍스트 오프로딩 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "",
      "긴 도구 결과는 컨텍스트에 쌓아두지 말고 파일로 저장해라.",
      "그 다음 grep 으로 필요한 줄만 찾아 읽어라.",
    ].join("\n"),
  });

  const result = await agent.invoke({
    messages: [{
      role: "user",
      content:
        "payment 서비스 로그를 가져와서 /logs/payment.log 에 저장하고, " +
        "ERROR 가 몇 건인지 세어라. 로그 전문을 응답에 옮기지 마라.",
    }],
  });

  // files 는 에이전트 "상태"에 있습니다 — 진짜 디스크가 아닙니다.
  printFiles(result.files, "실행 후 files");
  console.log("\n최종 응답:\n", String(result.messages.at(-1)?.text ?? "").slice(0, 400));
}

/* ===== [4-2] 도구 6종 — 미들웨어에서 직접 꺼내 보기 ===== */

async function step4_2() {
  console.log("\n===== [4-2] 파일시스템 도구 목록 =====");

  // 모델 없이도 미들웨어가 등록하는 도구 목록을 확인할 수 있습니다.
  const mw: any = createFilesystemMiddleware({ backend: new StateBackend() });

  console.log("등록된 도구:", mw.tools.map((t: any) => t.name).join(", "));

  // 각 도구의 파라미터를 출력합니다.
  // read_file/write_file/edit_file 은 z.preprocess 로 감싸여 있습니다.
  // ZodPreprocess 는 내부적으로 pipe(in → out) 이라 실제 객체 스키마는
  // _def.out 에 있습니다. (_def.schema 가 아닙니다 — zod v4 구조)
  for (const t of mw.tools) {
    const shape = t.schema?.shape ?? t.schema?._def?.out?.shape;
    console.log(`  - ${t.name}: ${shape ? Object.keys(shape).join(", ") : "(?)"}`);
  }

  // 기본값을 확인하는 가장 확실한 방법: 최소 입력을 parse 해 보는 것입니다.
  // zod 가 기본값을 채워서 돌려줍니다.
  const readFile: any = mw.tools.find((t: any) => t.name === "read_file");
  console.log(
    "\nread_file 기본값 확인:",
    JSON.stringify(readFile.schema.parse({ file_path: "/a.txt" })),
  );
  // → {"file_path":"/a.txt","offset":0,"limit":100}  (결정적)

  // execute 는 목록에 등록되지만, 백엔드가 SandboxBackendProtocol 을
  // 구현하지 않으면 모델에게 노출되기 전에 걸러집니다.
  // StateBackend 는 샌드박스가 아니므로 execute 는 실제로는 안 보입니다.
}

/* ===== [4-3] read_file 의 offset / limit ===== */

async function step4_3() {
  console.log("\n===== [4-3] read_file 의 offset/limit =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "",
      "큰 파일은 절대 통째로 읽지 마라.",
      "read_file 의 limit 으로 먼저 앞부분만 훑어 구조를 파악하고,",
      "필요한 구간만 offset 으로 짚어 읽어라.",
    ].join("\n"),
  });

  const stream = await agent.stream(
    {
      messages: [{
        role: "user",
        content:
          "payment 로그를 /logs/payment.log 에 저장한 뒤, " +
          "파일이 어떤 형식인지 앞부분만 조금 읽어서 알려줘. " +
          "전체를 읽지 마라.",
      }],
    },
    { streamMode: "values" },
  );

  let final: any = null;
  for await (const chunk of stream) {
    final = chunk;
  }

  // 모델이 read_file 을 어떤 인자로 불렀는지 봅니다.
  // offset/limit 의 기본값은 각각 0 과 100 입니다.
  for (const m of final.messages) {
    if (m.getType?.() === "ai" && m.tool_calls?.length) {
      for (const tc of m.tool_calls) {
        if (tc.name === "read_file") {
          console.log("read_file 호출 인자:", JSON.stringify(tc.args));
        }
      }
    }
  }

  console.log("\n최종 응답:\n", String(final?.messages?.at(-1)?.text ?? "").slice(0, 300));
}

/* ===== [4-4] edit_file 의 문자열 치환 ===== */

async function step4_4() {
  console.log("\n===== [4-4] edit_file — 부분 수정 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: [
      "너는 문서 편집자다.",
      "",
      "기존 파일을 고칠 때는 write_file 로 덮어쓰지 말고 edit_file 을 써라.",
      "edit_file 은 파일을 읽은 뒤에만 쓸 수 있다.",
    ].join("\n"),
  });

  const result = await agent.invoke({
    messages: [{
      role: "user",
      content: [
        "다음 순서로 해라.",
        "1. /docs/api.md 에 아래 내용을 써라:",
        "",
        "# API 가이드",
        "",
        "## 인증",
        "인증은 API_KEY 헤더로 한다.",
        "",
        "## 요청",
        "요청은 JSON 으로 보낸다.",
        "",
        "2. 그 다음 '인증은 API_KEY 헤더로 한다.' 를",
        "   '인증은 Bearer 토큰으로 한다.' 로 바꿔라.",
      ].join("\n"),
    }],
  });

  printFiles(result.files, "편집 후 files");

  // 도구가 돌려준 문자열을 봅니다 — 이 형식은 결정적입니다.
  //   write_file: "Successfully wrote to '/docs/api.md'"
  //   edit_file:  "Successfully replaced 1 occurrence(s) in '/docs/api.md'"
  for (const m of result.messages) {
    if (m.getType?.() === "tool" && ["write_file", "edit_file"].includes((m as any).name)) {
      console.log(`  [${(m as any).name}] ${String((m as any).text ?? "").slice(0, 80)}`);
    }
  }

  // 최종 파일 내용 확인
  const api = (result.files as any)?.["/docs/api.md"];
  const text = Array.isArray(api?.content) ? api.content.join("\n") : api?.content;
  console.log("\n/docs/api.md 내용:\n" + text);
}

/* ===== [4-5] grep / glob — 검색으로 필요한 것만 ===== */

async function step4_5() {
  console.log("\n===== [4-5] grep/glob — RAG 없이 검색하기 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "",
      "파일을 통째로 읽지 말고 grep 으로 필요한 줄만 찾아라.",
      "grep 은 정규식이 아니라 '리터럴 문자열' 을 찾는다는 점에 주의해라.",
    ].join("\n"),
  });

  const result = await agent.invoke({
    messages: [{
      role: "user",
      content:
        "payment, order, shipping 세 서비스의 로그를 각각 " +
        "/logs/payment.log, /logs/order.log, /logs/shipping.log 에 저장해라. " +
        "그 다음 glob 으로 /logs 아래 파일 목록을 확인하고, " +
        "grep 으로 ERROR 가 있는 파일과 건수를 정리해라. " +
        "파일 전문을 읽지 마라.",
    }],
  });

  printFiles(result.files, "저장된 로그");

  // 모델이 grep/glob 을 어떤 인자로 불렀는지 봅니다.
  for (const m of result.messages as any[]) {
    if (m.getType?.() === "ai" && m.tool_calls?.length) {
      for (const tc of m.tool_calls) {
        if (["grep", "glob"].includes(tc.name)) {
          console.log(`  ${tc.name} 인자:`, JSON.stringify(tc.args));
        }
      }
    }
  }

  console.log("\n최종 응답:\n", String(result.messages.at(-1)?.text ?? "").slice(0, 500));
}

/* ===== [4-6] StateBackend — 파일은 상태에 산다 ===== */

async function step4_6() {
  console.log("\n===== [4-6] StateBackend 들여다보기 =====");

  // (A) 체크포인터 없음 — 호출이 끝나면 files 는 사라집니다.
  const noCheckpoint = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 메모 담당자다.",
  });

  await noCheckpoint.invoke({
    messages: [{ role: "user", content: "/memo.txt 에 '첫 번째 메모' 라고 저장해라." }],
  });

  // 새 invoke 는 완전히 새 상태에서 시작합니다.
  const r2 = await noCheckpoint.invoke({
    messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }],
  });
  console.log("(A) 체크포인터 X — 두 번째 호출:");
  printFiles(r2.files, "  files");
  console.log("  응답:", String(r2.messages.at(-1)?.text ?? "").slice(0, 150));

  // (B) 체크포인터 있음 — 같은 thread_id 안에서 files 가 이어집니다.
  const withCheckpoint = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 메모 담당자다.",
    checkpointer: new MemorySaver(),
  });

  const config = { configurable: { thread_id: "step04-demo" } };

  await withCheckpoint.invoke(
    { messages: [{ role: "user", content: "/memo.txt 에 '첫 번째 메모' 라고 저장해라." }] },
    config,
  );

  const r4 = await withCheckpoint.invoke(
    { messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }] },
    config,   // ← 같은 thread_id
  );
  console.log("\n(B) 체크포인터 O — 두 번째 호출:");
  printFiles(r4.files, "  files");
  console.log("  응답:", String(r4.messages.at(-1)?.text ?? "").slice(0, 150));

  // (C) 다른 thread_id — 파일이 안 보입니다. StateBackend 는 스레드 격리입니다.
  const r5 = await withCheckpoint.invoke(
    { messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }] },
    { configurable: { thread_id: "step04-other" } },
  );
  console.log("\n(C) 다른 thread_id:");
  printFiles(r5.files, "  files");
}

/* ===== [4-7] 실전 패턴 — 오프로딩 후 요약만 남기기 ===== */

async function step4_7() {
  console.log("\n===== [4-7] 오프로딩 → 요약만 컨텍스트에 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "",
      "작업 절차:",
      "1. fetch_logs 결과는 즉시 /logs/<서비스>.log 에 저장한다.",
      "2. grep 으로 ERROR 줄만 찾는다.",
      "3. 분석 결과를 /reports/summary.md 에 저장한다.",
      "4. 최종 응답에는 요약 3줄과 리포트 파일 경로만 적는다.",
      "",
      "원본 로그를 응답에 옮기지 마라.",
    ].join("\n"),
  });

  const result = await agent.invoke({
    messages: [{
      role: "user",
      content: "payment 와 order 서비스의 로그를 분석해서 리포트를 만들어라.",
    }],
  });

  printFiles(result.files, "최종 files");

  const report = (result.files as any)?.["/reports/summary.md"];
  if (report) {
    const text = Array.isArray(report.content) ? report.content.join("\n") : report.content;
    console.log("\n/reports/summary.md:\n" + String(text).slice(0, 500));
  }

  console.log("\n최종 응답 (짧아야 정상):\n", String(result.messages.at(-1)?.text ?? ""));

  // 컨텍스트 절약 효과 확인:
  // 마지막 AI 메시지의 input_tokens 가 로그 4000줄(약 20만 자)보다
  // 훨씬 작아야 합니다.
  const aiMessages = result.messages.filter((m: any) => m.getType?.() === "ai");
  const lastUsage = (aiMessages.at(-1) as any)?.usage_metadata;
  console.log("\n마지막 모델 호출 input_tokens:", lastUsage?.input_tokens);
}

/* ===== [4-8] createFilesystemMiddleware 를 일반 createAgent 에 ===== */

async function step4_8() {
  console.log("\n===== [4-8] deepagents 없이 파일시스템만 빌려오기 =====");

  // createDeepAgent 를 쓰지 않고 파일시스템 능력만 추가합니다.
  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: "너는 로그 분석 담당자다. 긴 결과는 파일로 저장하고 grep 으로 찾아 읽어라.",
    middleware: [
      createFilesystemMiddleware({
        backend: new StateBackend(),
      }),
    ],
  });

  const result = await agent.invoke({
    messages: [{
      role: "user",
      content: "payment 로그를 /logs/payment.log 에 저장하고 ERROR 건수를 세어라.",
    }],
  });

  printFiles((result as any).files, "일반 createAgent + 파일시스템");
  console.log("응답:", String(result.messages.at(-1)?.text ?? "").slice(0, 300));

  // 읽기 전용 파일시스템을 주고 싶다면 tools 허용목록을 씁니다.
  // read_file 은 반드시 포함해야 합니다.
  const readOnly = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    middleware: [
      createFilesystemMiddleware({
        backend: new StateBackend(),
        tools: ["read_file", "ls", "glob", "grep"],   // write_file/edit_file 제외
      }),
    ],
  });

  console.log("\n읽기 전용 에이전트 생성 완료 (write_file/edit_file 없음)");
  void readOnly;
}

/* ===== [4-9] 종합 — 계획 + 파일시스템 ===== */

async function step4_9() {
  console.log("\n===== [4-9] 종합 =====");

  // Step 03 의 계획과 이번 스텝의 파일시스템을 함께 씁니다.
  // createDeepAgent 는 둘 다 기본으로 켭니다.
  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 장애 분석 담당자다.",
      "",
      "작업 규칙:",
      "- 3단계 이상 걸리는 일은 계획을 먼저 세운다.",
      "- 긴 도구 결과는 즉시 파일로 내린다. 컨텍스트에 쌓지 않는다.",
      "- 파일은 grep/read_file(offset,limit) 으로 필요한 부분만 읽는다.",
      "- 최종 산출물은 /reports/incident.md 에 저장한다.",
      "- 최종 응답은 5줄 이내로 요약한다.",
    ].join("\n"),
  });

  const stream = await agent.stream(
    {
      messages: [{
        role: "user",
        content:
          "payment, order, shipping 세 서비스의 로그를 분석해서 " +
          "서비스별 ERROR 건수와 가장 느린 요청을 정리한 장애 리포트를 만들어라.",
      }],
    },
    { streamMode: "values" },
  );

  let prevTodos = "";
  let final: any = null;
  for await (const chunk of stream) {
    final = chunk;
    const snap = JSON.stringify(chunk.todos ?? []);
    if (snap !== prevTodos) {
      prevTodos = snap;
      const todos = (chunk.todos ?? []) as { content: string; status: string }[];
      const done = todos.filter((t) => t.status === "completed").length;
      if (todos.length) console.log(`[계획 ${done}/${todos.length}]`);
    }
  }

  printFiles(final?.files, "최종 files");
  console.log("\n최종 응답:\n", String(final?.messages?.at(-1)?.text ?? ""));
}

/* ===== 실행 ===== */

async function main() {
  // 필요한 절만 주석 해제해서 돌려보세요. 전부 돌리면 API 호출이 많이 발생합니다.
  await step4_1();
  await step4_2();
  await step4_3();
  await step4_4();
  await step4_5();
  await step4_6();
  await step4_7();
  await step4_8();
  await step4_9();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
