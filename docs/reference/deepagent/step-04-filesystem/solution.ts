/**
 * Step 04 — 가상 파일시스템 · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-04-filesystem/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 주의: LLM 응답은 비결정적입니다. 주석의 토큰 수치는 "경향"이며,
 *       도구 반환 문자열과 files 구조는 결정적입니다.
 */
import "dotenv/config";
import { createDeepAgent, createFilesystemMiddleware, StateBackend } from "deepagents";
import { createAgent, tool } from "langchain";
import { MemorySaver } from "@langchain/langgraph";
import * as z from "zod";

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

function getUsage(result: any): number | undefined {
  const aiMessages = result.messages.filter((m: any) => m.getType?.() === "ai");
  return (aiMessages.at(-1) as any)?.usage_metadata?.input_tokens;
}

/** 도구 호출 인자를 모아 출력하는 헬퍼 */
function printToolCalls(result: any, toolName: string) {
  for (const m of result.messages) {
    if (m.getType?.() === "ai" && m.tool_calls?.length) {
      for (const tc of m.tool_calls) {
        if (tc.name === toolName) console.log(`  ${toolName} 인자:`, JSON.stringify(tc.args));
      }
    }
  }
}

/** 특정 도구의 ToolMessage 반환값을 출력하는 헬퍼 */
function printToolResults(result: any, toolName: string) {
  for (const m of result.messages) {
    if (m.getType?.() === "tool" && (m as any).name === toolName) {
      console.log(`  [${toolName}] ${String((m as any).text ?? "").slice(0, 300)}`);
    }
  }
}

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

/* ===== [정답 1] 파일시스템 유무의 토큰 차이 ===== */
async function sol1() {
  console.log("\n===== [정답 1] 파일시스템 유무의 토큰 차이 =====");

  const task = "payment 서비스 로그를 분석해서 ERROR 가 몇 건인지 알려줘.";

  // (A) 파일시스템 있음
  const withFs = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "긴 도구 결과는 즉시 /logs/<서비스>.log 에 저장하고,",
      "grep 으로 필요한 줄만 찾아 읽어라. 파일을 통째로 읽지 마라.",
    ].join("\n"),
  });
  const a = await withFs.invoke({ messages: [{ role: "user", content: task }] });

  // (B) 파일시스템 없음 — 로그가 ToolMessage 로 컨텍스트에 통째로 박힙니다
  const withoutFs = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: "너는 로그 분석 담당자다.",
  });
  const b = await withoutFs.invoke({ messages: [{ role: "user", content: task }] });

  console.log("(A) 파일시스템 O — input_tokens:", getUsage(a));
  printFiles(a.files, "  (A) files");
  console.log("(B) 파일시스템 X — input_tokens:", getUsage(b));

  // → (A) input_tokens: 수천 (로그는 파일에 있고 grep 결과만 컨텍스트에)
  // → (B) input_tokens: 3만~4만 (로그 13만 자가 ToolMessage 로 통째로)
  // → 왜 차이가 나는가:
  //
  // 해설: (B) 에서 fetch_logs 의 결과 13만 자는 ToolMessage 가 되어 대화에
  //   영원히 박힙니다. 모델이 답을 낸 뒤에도 대화가 이어지는 내내 매 턴
  //   다시 전송됩니다. (A) 에서는 그 13만 자가 files 상태로 빠지고,
  //   컨텍스트에는 "Successfully wrote to '/logs/payment.log'" 한 줄과
  //   grep 결과 20줄만 남습니다.
  //
  //   주의: (B) 도 Deep Agent 였다면 자동 오프로딩(20,000 토큰 초과)이
  //   작동했을 겁니다. 여기서 (B) 는 미들웨어가 아예 없는 순수 createAgent 라
  //   그 안전망조차 없습니다. 이것이 "날것" 의 모습입니다.
  //
  //   그리고 이 차이는 대화가 길어질수록 곱해집니다 (정답 8 참고).
}

/* ===== [정답 2] 도구 스키마 직접 확인 ===== */
async function sol2() {
  console.log("\n===== [정답 2] 도구 스키마 직접 확인 =====");

  const mw: any = createFilesystemMiddleware({ backend: new StateBackend() });

  console.log("도구 목록:", mw.tools.map((t: any) => t.name).join(", "));

  for (const t of mw.tools) {
    // read_file/write_file/edit_file 은 z.preprocess 로 감싸여 있습니다.
    // ZodPreprocess 는 내부적으로 pipe(in → out) 이라 객체 스키마는 _def.out 에 있습니다.
    // (_def.schema 가 아닙니다 — zod v4 의 구조입니다)
    const shape = t.schema?.shape ?? t.schema?._def?.out?.shape;
    console.log(`  - ${t.name}: ${shape ? Object.keys(shape).join(", ") : "(?)"}`);
  }

  const readFile: any = mw.tools.find((t: any) => t.name === "read_file");

  // 방법 1) 스키마 내부를 뒤져 기본값을 꺼냅니다.
  //   zod v4 에서 _def.defaultValue 는 함수가 아니라 "값" 입니다. (v3 는 함수였습니다)
  const shape = readFile.schema._def.out.shape;
  console.log("\n[방법 1] offset 기본값:", shape.offset?._def?.defaultValue);
  console.log("[방법 1] limit  기본값:", shape.limit?._def?.defaultValue);

  // 방법 2) 최소 입력을 parse 해 봅니다. zod 가 기본값을 채워 돌려줍니다.
  //   내부 구조에 의존하지 않으므로 훨씬 견고합니다. 이 방법을 권합니다.
  console.log("[방법 2] parse 결과:", JSON.stringify(readFile.schema.parse({ file_path: "/a.txt" })));
  // → {"file_path":"/a.txt","offset":0,"limit":100}

  // → 도구 목록 (결정적):
  //   ls, read_file, write_file, edit_file, glob, grep, execute
  // → read_file 의 offset 기본값: 0
  // → read_file 의 limit  기본값: 100
  //
  // 해설: 이 문제의 진짜 교훈은 "문서를 믿지 말고 스키마를 직접 확인하라" 입니다.
  //   그리고 확인하는 방법도 두 가지의 품질이 다릅니다. 방법 1 은 zod 의
  //   내부 구조(_def.out)에 의존하므로 zod 버전이 오르면 깨집니다.
  //   실제로 zod v3 → v4 에서 이 경로가 바뀌었습니다. 방법 2(parse)는
  //   공개 API 만 쓰므로 안전합니다.
  //   실제로 이 라이브러리에는 문서와 구현이 어긋난 곳이 있습니다:
  //   grep 의 도구 설명에는 output_mode 가 언급되지만, 실제 스키마에는
  //   pattern/path/glob 세 개뿐입니다. 설명만 믿고 output_mode 를 쓰려 하면
  //   모델이 스키마에 없는 인자를 보내 검증에서 걸립니다.
  //
  //   execute 가 목록에 있는 것도 함정입니다. StateBackend 는 샌드박스가
  //   아니므로, execute 는 모델에게 노출되기 전에 백엔드 능력 검사에서
  //   걸러집니다. 목록에 있다고 쓸 수 있는 게 아닙니다.
  //
  //   limit 기본값 100 은 "컨텍스트 오버플로 방지" 를 위한 것입니다.
  //   도구 설명 원문에도 "IMPORTANT for large files ... to avoid context
  //   overflow" 라고 명시돼 있습니다.
}

/* ===== [정답 3] read_file 의 limit 효과 ===== */
async function sol3() {
  console.log("\n===== [정답 3] read_file 의 limit 효과 =====");

  // (A) 전체를 읽게 유도
  const readAll = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: "너는 로그 분석 담당자다.",
  });
  const a = await readAll.invoke({
    messages: [{
      role: "user",
      content:
        "payment 로그를 /logs/payment.log 에 저장한 뒤, " +
        "파일 전체를 읽어서 어떤 형식인지 알려줘.",
    }],
  });
  console.log("(A) 전체 읽기:");
  printToolCalls(a, "read_file");
  console.log("  input_tokens:", getUsage(a));

  // (B) limit 20 으로 읽게 유도
  const readHead = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "큰 파일은 절대 통째로 읽지 마라.",
      "read_file 의 limit 은 항상 20 이하로 지정해라.",
    ].join("\n"),
  });
  const b = await readHead.invoke({
    messages: [{
      role: "user",
      content:
        "payment 로그를 /logs/payment.log 에 저장한 뒤, " +
        "앞부분만 조금 읽어서 어떤 형식인지 알려줘.",
    }],
  });
  console.log("\n(B) limit 20:");
  printToolCalls(b, "read_file");
  console.log("  input_tokens:", getUsage(b));

  // → (A) read_file 인자: {"file_path":"/logs/payment.log","limit":2000} 같은 큰 값
  //       또는 limit 생략(=100). input_tokens: 수만
  // → (B) read_file 인자: {"file_path":"/logs/payment.log","limit":20}
  //       input_tokens: 수천
  //
  // 해설: 재미있는 함정이 있습니다. limit 을 "생략" 하면 무제한이 아니라
  //   기본값 100 이 적용됩니다. 그런데 도구 설명에는
  //   "Only omit limit (read full file) when necessary for editing" 이라고
  //   적혀 있어, 마치 생략하면 전체를 읽는 것처럼 읽힙니다.
  //   이 문장에 낚인 모델이 "전체를 읽으려면 큰 limit 을 명시해야겠다" 며
  //   limit: 2000 같은 값을 보내는 경우가 실제로 있습니다.
  //
  //   즉 (A) 에서 컨텍스트가 터지는 이유는 기본값 때문이 아니라
  //   모델이 "일부러 큰 값을 명시했기" 때문입니다.
  //   방어법은 (B) 처럼 프롬프트에서 상한을 못 박는 것입니다.
  //
  //   참고: 한 줄이 5000자를 넘으면 여러 줄로 쪼개지고 그 조각들이 각각
  //   limit 에 카운트됩니다. 미니파이된 JS 한 줄이 limit: 100 을 통째로
  //   먹을 수 있습니다.
}

/* ===== [정답 4] edit_file 의 다중 매치 ===== */
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

async function sol4() {
  console.log("\n===== [정답 4] edit_file 의 다중 매치 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 문서 편집자다. 기존 파일은 read_file 로 읽고 edit_file 로 수정해라.",
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "sol4" } };

  // 1) 파일 저장 + 다중 매치 치환 시도
  const a = await agent.invoke(
    {
      messages: [{
        role: "user",
        content: [
          "1. /docs/api.md 에 아래 내용을 정확히 저장해라:",
          "",
          DOC_WITH_3_JSON,
          "",
          "2. 그 다음 edit_file 로 old_string 을 정확히 \"JSON\" 으로 지정해서",
          "   \"JSON5\" 로 바꿔라. replace_all 은 쓰지 마라.",
        ].join("\n"),
      }],
    },
    config,
  );

  console.log("edit_file 반환값:");
  printToolResults(a, "edit_file");

  // → 에러 메시지 전문 (결정적 형식):
  //   Error: String 'JSON' has multiple occurrences (appears 3 times) in file.
  //   Use replace_all=True to replace all instances, or provide a more specific
  //   string with surrounding context.

  // 2) 해결책 1 — replace_all: true
  const b = await agent.invoke(
    {
      messages: [{
        role: "user",
        content: "이번엔 replace_all 을 true 로 해서 JSON 을 전부 JSON5 로 바꿔라.",
      }],
    },
    config,
  );
  console.log("\n해결책 1 (replace_all: true):");
  printToolResults(b, "edit_file");
  // → Successfully replaced 3 occurrence(s) in '/docs/api.md'

  // 3) 해결책 2 — 문맥을 포함해 유일하게 만들기
  const agent2 = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 문서 편집자다.",
    checkpointer: new MemorySaver(),
  });
  const config2 = { configurable: { thread_id: "sol4b" } };

  await agent2.invoke(
    { messages: [{ role: "user", content: `/docs/api.md 에 아래를 저장해라:\n\n${DOC_WITH_3_JSON}` }] },
    config2,
  );
  const c = await agent2.invoke(
    {
      messages: [{
        role: "user",
        content:
          "'## 응답' 절의 JSON 만 JSON5 로 바꿔라. 다른 절의 JSON 은 건드리지 마라. " +
          "old_string 에 주변 문맥을 포함시켜 유일하게 만들어라.",
      }],
    },
    config2,
  );
  console.log("\n해결책 2 (문맥 포함):");
  printToolResults(c, "edit_file");
  // → Successfully replaced 1 occurrence(s) in '/docs/api.md'
  //   old_string 은 "응답 본문도 JSON 이다." 같은 유일한 문자열이 됩니다.

  const doc = (c.files as any)?.["/docs/api.md"];
  const text = Array.isArray(doc?.content) ? doc.content.join("\n") : doc?.content;
  console.log("\n최종 문서:\n" + text);

  // → 왜 이게 안전장치인가:
  //
  // 해설: "어느 것을 바꿀지 모르겠으니 아무것도 안 바꾸겠다" 는 태도입니다.
  //   만약 라이브러리가 "첫 번째 매치만 바꾼다" 로 동작했다면, 모델이 의도한
  //   것과 다른 곳이 조용히 바뀌었을 것입니다. 에러가 나는 편이 훨씬 낫습니다.
  //
  //   주목: 에러 메시지의 "replace_all=True" 는 파이썬 표기가 그대로 남은
  //   것입니다. TypeScript 에서는 replace_all: true 입니다. 모델이 이 메시지를
  //   곧이곧대로 읽고 "True" 라는 문자열을 보내려다 zod boolean 검증에
  //   걸리는 일이 실제로 있습니다. 로그에서
  //   "Received tool input did not match expected schema" 가 edit_file 에서
  //   반복되면 이걸 의심하세요.
  //
  //   또 하나: occurrence(s) 앞의 숫자를 항상 확인하세요. 1을 기대했는데
  //   3이 나왔다면 의도하지 않은 곳까지 바뀐 것입니다. 이건 에러가 아니라
  //   "성공" 으로 보고되므로 숫자를 안 보면 놓칩니다.
}

/* ===== [정답 5] grep 은 정규식이 아니다 ===== */
async function sol5() {
  console.log("\n===== [정답 5] grep 은 정규식이 아니다 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: "너는 로그 분석 담당자다.",
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "sol5" } };

  // 1) 정규식으로 grep 유도
  const a = await agent.invoke(
    {
      messages: [{
        role: "user",
        content:
          "payment 로그를 /logs/payment.log 에 저장한 뒤, " +
          "grep 으로 정규식 패턴 ERROR|WARN 을 써서 문제가 있는 줄을 찾아라. " +
          "찾은 결과로 장애 여부를 판단해라.",
      }],
    },
    config,
  );

  console.log("정규식 grep 결과:");
  printToolCalls(a, "grep");
  printToolResults(a, "grep");
  console.log("\n모델의 결론:\n", String(a.messages.at(-1)?.text ?? "").slice(0, 300));

  // 2) 리터럴로 다시 grep — 실제로는 20건이 있었습니다
  const b = await agent.invoke(
    {
      messages: [{
        role: "user",
        content: "이번엔 grep 패턴을 정확히 ERROR 로만 해서 다시 찾아봐라. 몇 건이냐?",
      }],
    },
    config,
  );
  console.log("\n리터럴 grep 결과:");
  printToolCalls(b, "grep");
  console.log("모델의 결론:\n", String(b.messages.at(-1)?.text ?? "").slice(0, 300));

  // → grep 반환 (결정적):
  //   No matches found for pattern 'ERROR|WARN'
  // → 에러인가 "없음" 인가? "없음" 입니다. 에러가 아닙니다.
  // → 모델의 결론은? 이게 왜 위험한가?
  //
  // 해설: 이것이 이 스텝에서 가장 위험한 함정입니다. 무슨 일이 벌어졌나:
  //   1. grep 은 "ERROR|WARN" 이라는 리터럴 문자열을 찾았습니다.
  //      (파이프 문자가 실제로 들어간 줄을 찾은 것입니다)
  //   2. 그런 줄은 없었습니다. 그래서 "No matches found" 를 반환했습니다.
  //   3. 이건 완벽히 정상 동작입니다. 에러가 아닙니다.
  //   4. 모델은 이걸 "ERROR 도 WARN 도 없다" 로 읽고
  //      "장애 없음, 정상입니다" 라고 태연히 보고합니다.
  //   5. 그런데 실제로는 ERROR 가 20건, WARN 이 153건 있었습니다.
  //
  //   에러도 안 나고, 예외도 안 뜨고, 그럴듯한 보고서가 나옵니다.
  //   완전히 틀렸는데 말이죠. 도구 설명 원문에도 명시돼 있습니다:
  //     "Searches for literal text (not regex)"
  //     "Special characters like parentheses, brackets, pipes, etc. are
  //      treated as literal characters, not regex operators."
  //   하지만 도구 이름이 'grep' 이라 모델은 습관적으로 정규식을 씁니다.
  //
  //   방어법:
  //   - 프롬프트에 "grep 은 리터럴 문자열만 찾는다. 정규식을 쓰지 마라.
  //     여러 패턴이 필요하면 grep 을 여러 번 불러라" 를 명시하세요.
  //   - customToolDescriptions 로 grep 설명을 강화하세요.
  //   - "결과 없음" 을 성공으로 취급하지 마세요. 0건이 나오면 의심하세요.
  //     특히 "0건이라 문제 없음" 이라는 결론은 항상 재확인 대상입니다.
}

/* ===== [정답 6] 체크포인터와 파일의 수명 ===== */
async function sol6() {
  console.log("\n===== [정답 6] 체크포인터와 파일의 수명 =====");

  // (A) MemorySaver + 같은 thread_id
  const withCp = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 메모 담당자다.",
    checkpointer: new MemorySaver(),
  });
  const cfgA = { configurable: { thread_id: "sol6-a" } };

  await withCp.invoke(
    { messages: [{ role: "user", content: "/memo.txt 에 '첫 번째 메모' 라고 저장해라." }] },
    cfgA,
  );
  const a2 = await withCp.invoke(
    { messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }] },
    cfgA,
  );
  console.log("(A) 체크포인터 O, 같은 thread_id:");
  printFiles(a2.files, "  files");
  console.log("  응답:", String(a2.messages.at(-1)?.text ?? "").slice(0, 120));

  // (B) 같은 에이전트, 다른 thread_id
  const b = await withCp.invoke(
    { messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }] },
    { configurable: { thread_id: "sol6-b" } },
  );
  console.log("\n(B) 체크포인터 O, 다른 thread_id:");
  printFiles(b.files, "  files");
  console.log("  응답:", String(b.messages.at(-1)?.text ?? "").slice(0, 120));

  // (C) 체크포인터 없음 — thread_id 를 줘도 소용없습니다
  const noCp = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 메모 담당자다.",
  });
  await noCp.invoke(
    { messages: [{ role: "user", content: "/memo.txt 에 '첫 번째 메모' 라고 저장해라." }] },
    { configurable: { thread_id: "sol6-c" } },   // ← thread_id 를 줬지만...
  );
  const c2 = await noCp.invoke(
    { messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }] },
    { configurable: { thread_id: "sol6-c" } },   // ← 같은 thread_id 인데도
  );
  console.log("\n(C) 체크포인터 X, thread_id 있음:");
  printFiles(c2.files, "  files");
  console.log("  응답:", String(c2.messages.at(-1)?.text ?? "").slice(0, 120));

  // → (A) 파일이 남았나? 예. files 에 /memo.txt 가 있고 모델이 내용을 읽습니다.
  // → (B) 파일이 보이나? 아니오. files 는 비어 있고
  //       "Error: File '/memo.txt' not found" 가 납니다.
  // → (C) 파일이 남았나? 아니오. thread_id 를 줬는데도 사라집니다.
  // → thread_id 만 주고 체크포인터를 안 주면?
  //
  // 해설: (C) 가 핵심입니다. thread_id 를 정성껏 넘겼는데 아무 일도
  //   일어나지 않습니다. 경고도, 에러도 없습니다. 저장할 곳(체크포인터)이
  //   없으면 thread_id 는 그냥 무시됩니다.
  //   "thread_id 를 줬으니 대화가 이어지겠지" 는 틀렸습니다.
  //   thread_id 는 "어느 서랍에 넣을까" 를 지정하는 라벨일 뿐,
  //   서랍장(체크포인터) 자체가 없으면 라벨은 의미가 없습니다.
  //
  //   (B) 는 버그가 아니라 보안 기능입니다. StateBackend 는 스레드 격리이므로
  //   사용자 A 의 파일을 사용자 B 가 볼 수 없습니다. 하지만 "지난 대화에서 만든
  //   파일을 꺼내려는" 의도라면 이것도 실패로 느껴집니다.
  //   스레드를 넘는 영속성이 필요하면 StoreBackend 나 CompositeBackend 를
  //   쓰세요 (Step 05).
  //
  //   그리고 (A) 조차 완전하지 않습니다. MemorySaver 는 프로세스 메모리라
  //   서버를 재시작하면 전부 날아갑니다. 이름이 "Saver" 라 영속적인 것 같지만
  //   아닙니다. 프로덕션에서는 DB 기반 체크포인터를 쓰세요.
}

/* ===== [정답 7] read_file 없는 허용목록 ===== */
async function sol7() {
  console.log("\n===== [정답 7] read_file 없는 허용목록 =====");

  // read_file 을 뺀 허용목록
  try {
    const mw: any = createFilesystemMiddleware({
      backend: new StateBackend(),
      tools: ["ls", "glob", "grep"],   // ← read_file 없음
    });
    console.log("생성 성공. 노출 도구:", mw.tools.map((t: any) => t.name).join(", "));
  } catch (err) {
    console.log("생성 시 에러:", (err as Error).message);
  }

  // 정상적인 읽기 전용 구성 — read_file 포함
  const ok: any = createFilesystemMiddleware({
    backend: new StateBackend(),
    tools: ["read_file", "ls", "glob", "grep"],
  });
  console.log("올바른 읽기 전용:", ok.tools.map((t: any) => t.name).join(", "));

  // → 생성 시 에러가 나나? 예. 즉시, 시끄럽게 터집니다. (결정적)
  //     read_file must be included in tools; it is required by FilesystemMiddleware
  //
  //   미들웨어를 만드는 시점에 throw 되므로 모델을 부르기도 전에 알 수 있습니다.
  //   이건 아주 친절한 설계입니다 — 런타임 한참 뒤에 조용히 실패하는 대신
  //   개발 중에 바로 잡힙니다.
  //
  // → 올바른 읽기 전용의 노출 도구 (결정적):
  //     ls, read_file, glob, grep
  //   write_file/edit_file 이 빠졌고, execute 도 없습니다.
  //   (execute 는 허용목록에도 없고, StateBackend 가 샌드박스도 아닙니다)
  //
  // → read_file 이 없으면 무엇이 불가능해지나:
  //
  // 해설: 라이브러리 주석에 이유가 명시돼 있습니다.
  //   "read_file must be included in every explicit array because it is used
  //    by normal file-inspection flows and by large-result recovery guidance."
  //
  //   'large-result recovery' 가 핵심입니다. 도구 결과가 20,000 토큰을 넘으면
  //   자동 오프로딩이 작동해 결과를 파일로 내리고, 컨텍스트에는
  //   "파일 경로 + 앞 10줄 미리보기" 만 남깁니다. 이때 모델이 나머지를 보려면
  //   read_file 이 필요합니다.
  //
  //   read_file 이 없다면 이렇게 됐을 것입니다:
  //   - 큰 도구 결과가 파일로 밀려남 → 앞 10줄만 보임
  //   - 나머지를 꺼낼 방법이 없음 (grep 으로 뭘 찾을지도 모르는 상태)
  //   - 모델은 앞 10줄만 보고 답을 지어냄
  //   결과가 "사라진" 게 아니라 "꺼낼 손이 없는" 상태가 되는 것이죠.
  //
  //   라이브러리가 이 상황을 아예 생성 시점에 차단한 이유가 그것입니다.
  //   위 시나리오는 에러 없이 "답이 조용히 부실해지는" 형태로 나타나
  //   디버깅이 매우 어렵습니다. 그래서 write 를 막고 싶어도 read_file 은
  //   남겨야 합니다. "읽기 전용" 은 ["read_file", "ls", "glob", "grep"] 이지
  //   ["ls", "glob", "grep"] 이 아닙니다.
}

/* ===== [정답 8] 산출물도 오프로딩 대상이다 ===== */
async function sol8() {
  console.log("\n===== [정답 8] 산출물도 오프로딩 대상이다 =====");

  const analysisTask = "payment 와 order 서비스의 로그를 분석해서 리포트를 만들어라.";
  const followUp = "그래서 가장 심각한 문제가 뭐야?";

  // (A) 리포트를 파일로 저장 — 최종 응답에는 요약 + 경로만
  const fileVersion = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "1. fetch_logs 결과는 즉시 /logs/<서비스>.log 에 저장한다.",
      "2. grep 으로 ERROR 줄만 찾는다.",
      "3. 분석 결과를 /reports/summary.md 에 저장한다.",
      "4. 최종 응답에는 요약 3줄과 리포트 파일 경로만 적는다.",
      "원본 로그나 리포트 전문을 응답에 옮기지 마라.",
    ].join("\n"),
    checkpointer: new MemorySaver(),
  });
  const cfgA = { configurable: { thread_id: "sol8-a" } };

  const a1 = await fileVersion.invoke(
    { messages: [{ role: "user", content: analysisTask }] },
    cfgA,
  );
  const a2 = await fileVersion.invoke(
    { messages: [{ role: "user", content: followUp }] },
    cfgA,
  );
  console.log("(A) 파일 저장 버전:");
  printFiles(a2.files, "  files");
  console.log("  1차 input_tokens:", getUsage(a1));
  console.log("  2차 input_tokens:", getUsage(a2));
  console.log("  1차 응답 길이:", String(a1.messages.at(-1)?.text ?? "").length, "자");

  // (B) 리포트 전문을 최종 응답에 담기
  const inlineVersion = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [fetchLogs],
    systemPrompt: [
      "너는 로그 분석 담당자다.",
      "1. fetch_logs 결과는 즉시 /logs/<서비스>.log 에 저장한다.",
      "2. grep 으로 ERROR 줄만 찾는다.",
      "3. 분석 리포트 전문을 최종 응답에 그대로 적는다. 파일로 저장하지 마라.",
      "리포트는 서비스별 상세 내역을 빠짐없이 포함해 길게 작성해라.",
    ].join("\n"),
    checkpointer: new MemorySaver(),
  });
  const cfgB = { configurable: { thread_id: "sol8-b" } };

  const b1 = await inlineVersion.invoke(
    { messages: [{ role: "user", content: analysisTask }] },
    cfgB,
  );
  const b2 = await inlineVersion.invoke(
    { messages: [{ role: "user", content: followUp }] },
    cfgB,
  );
  console.log("\n(B) 응답에 담는 버전:");
  printFiles(b2.files, "  files");
  console.log("  1차 input_tokens:", getUsage(b1));
  console.log("  2차 input_tokens:", getUsage(b2));
  console.log("  1차 응답 길이:", String(b1.messages.at(-1)?.text ?? "").length, "자");

  // → (A) 2차 input_tokens: 상대적으로 작음
  // → (B) 2차 input_tokens: 리포트 전문만큼 더 큼
  // → 대화가 더 길어지면 격차는?
  //
  // 해설: 1차 호출만 보면 두 버전의 차이가 크지 않습니다. 어차피 같은 로그를
  //   읽고 같은 분석을 했으니까요. 차이는 2차부터 벌어집니다.
  //
  //   (B) 에서 리포트 전문(수천 자)은 AIMessage 로 대화에 남습니다.
  //   후속 질문을 할 때마다 그 전문이 통째로 다시 전송됩니다.
  //   3턴이면 3번, 10턴이면 10번입니다.
  //
  //   (A) 에서 리포트는 files 상태에 있고, 대화에는
  //   "리포트는 /reports/summary.md 에 저장했습니다" 한 줄만 남습니다.
  //   후속 질문에 답하려면 모델이 그 파일을 다시 읽어야 하지만,
  //   그건 "필요할 때만" 이고 grep 으로 일부만 읽을 수도 있습니다.
  //
  //   요점: 오프로딩의 가치는 "한 번의 호출" 이 아니라 "누적" 에서 나옵니다.
  //   턴이 늘어날수록 (B) 는 선형으로 비용이 늘고 (A) 는 거의 평평합니다.
  //
  //   그리고 흔히 놓치는 부분: 사람들은 "도구 결과" 를 오프로딩할 생각은
  //   하지만 "에이전트가 만든 산출물" 도 오프로딩 대상이라는 건 잊습니다.
  //   에이전트가 쓴 긴 리포트도 다음 턴부터는 그냥 무거운 짐입니다.
  //
  //   단, 함정도 있습니다 (본문 4-1 참고): (A) 에서 후속 질문에 답하려고
  //   모델이 /reports/summary.md 를 통째로 read_file 하면 결국 컨텍스트에
  //   다 들어옵니다. 파일로 내렸다는 사실만으로 안심하면 안 되고,
  //   "어떻게 다시 읽는가" 까지 프롬프트로 관리해야 합니다.
}

/* ===== 실행 ===== */

async function main() {
  await sol1();
  await sol2();
  await sol3();
  await sol4();
  await sol5();
  await sol6();
  await sol7();
  await sol8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
