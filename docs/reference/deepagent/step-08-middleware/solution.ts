/**
 * Step 08 — 미들웨어 조합 · 정답
 * 실행: npx tsx docs/reference/deepagent/step-08-middleware/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 여세요.
 * 각 정답 위 주석에 기대 결과와 해설이 있습니다.
 */
import "dotenv/config";
import {
  createDeepAgent,
  createFilesystemMiddleware,
  createSubAgentMiddleware,
  createSummarizationMiddleware,
  createPatchToolCallsMiddleware,
  StateBackend,
} from "deepagents";
import {
  createAgent,
  createMiddleware,
  todoListMiddleware,
  anthropicPromptCachingMiddleware,
  summarizationMiddleware,
  tool,
} from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== 공용 준비물 ===== */

function makeInspector(label: string, sink?: { tools?: string[]; prompt?: string }) {
  let done = false;
  return createMiddleware({
    name: "InspectorMiddleware",
    wrapModelCall: async (request, handler) => {
      if (!done) {
        done = true;
        const toolNames = request.tools.map((t: any) => t.name).sort();
        const prompt = request.systemMessage.text ?? "";
        if (sink) {
          sink.tools = toolNames;
          sink.prompt = prompt;
        }
        console.log(`[${label}] 도구(${toolNames.length}): ${toolNames.join(", ")}`);
        console.log(`[${label}] 프롬프트 길이: ${prompt.length}`);
      }
      return handler(request);
    },
  });
}

const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

const saveNote = tool(({ text }: { text: string }) => `메모함: ${text}`, {
  name: "save_note",
  description: "메모를 저장합니다.",
  schema: z.object({ text: z.string() }),
});

/* ===== [정답 1] =====
 * 기대 결과: 도구 8개.
 *   edit_file, glob, grep, ls, read_file, task, write_file, write_todos
 *
 * → 매핑:
 *   write_todos                                       ← todoListMiddleware  (langchain)
 *   ls, read_file, write_file, edit_file, glob, grep  ← FilesystemMiddleware (deepagents)
 *   task                                              ← subAgentMiddleware   (deepagents)
 *
 * 해설: 도구를 하나도 안 줬는데 8개가 보입니다. createDeepAgent 는 "에이전트 생성기" 가
 * 아니라 "미들웨어 스택 조립기" 라는 것이 이 출력의 의미입니다.
 * execute 가 없는 이유는 기본 백엔드인 StateBackend 가 명령 실행을 지원하지 않아
 * 백엔드 능력 필터에서 제거되기 때문입니다 (Step 05 의 샌드박스 백엔드를 쓰면 나타납니다).
 */
async function sol1() {
  console.log("\n===== [정답 1] =====");
  const agent = await createDeepAgent({
    model: MODEL,
    systemPrompt: "당신은 도우미입니다.",
    middleware: [makeInspector("도구 없이 생성")],
  });
  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
}

/* ===== [정답 2] =====
 * 기대 결과: ConfigurationError 를 던진다.
 *   Tool name(s) [read_file] conflict with built-in tools.
 *   Rename your custom tools to avoid this.
 *
 * → 왜 그런가: createDeepAgent 는 BUILTIN_TOOL_NAMES 집합
 *   (ls, read_file, write_file, edit_file, glob, grep, execute, task, write_todos
 *    + 비동기 태스크 도구들)과 내 도구 이름을 대조해 충돌하면 즉시 던집니다.
 *   조용히 덮어쓰면 "내 도구를 부른 줄 알았는데 내장 도구가 불렸다" 는
 *   최악의 디버깅이 되므로, 던지는 것이 옳은 설계입니다.
 *
 * → 에러 시점: **createDeepAgent 호출 시점**입니다. invoke 전에 터지므로
 *   API 호출 비용이 발생하지 않습니다. 부팅 시 검증되는 것과 같아서 좋습니다.
 */
async function sol2() {
  console.log("\n===== [정답 2] =====");
  const colliding = tool(({ path }: { path: string }) => `읽음: ${path}`, {
    name: "read_file", // ← 내장 도구와 충돌
    description: "내 방식대로 파일을 읽습니다.",
    schema: z.object({ path: z.string() }),
  });

  try {
    await createDeepAgent({
      model: MODEL,
      tools: [colliding],
      systemPrompt: "당신은 도우미입니다.",
    });
    console.log("생성 성공 — 예상과 다름!");
  } catch (err) {
    console.log("createDeepAgent 시점에 throw:", (err as Error).message);
    console.log("에러 타입:", (err as Error).constructor.name); // ConfigurationError
  }
}

/* ===== [정답 3] =====
 * 기대 출력:
 *   todoListMiddleware()                     → todoListMiddleware
 *   createFilesystemMiddleware()             → FilesystemMiddleware
 *   createSubAgentMiddleware()               → subAgentMiddleware        ← 소문자 s!
 *   createSummarizationMiddleware()          → SummarizationMiddleware
 *   createPatchToolCallsMiddleware()         → patchToolCallsMiddleware  ← 소문자 p!
 *   summarizationMiddleware()   [langchain]  → SummarizationMiddleware   ← 위와 같음!
 *
 * → 팩토리 이름 != .name 인 것: 전부. create 접두사가 빠지고, 대소문자도 일관성이 없습니다.
 *   특히 subAgentMiddleware / patchToolCallsMiddleware 는 **소문자로 시작**합니다.
 *   REQUIRED_MIDDLEWARE_NAMES 에는 "SubAgentMiddleware"(대문자)로 적혀 있어 더 헷갈립니다.
 *
 * → .name 이 같은 쌍:
 *   deepagents 의 createSummarizationMiddleware 와
 *   langchain 의 summarizationMiddleware 가 **둘 다 "SummarizationMiddleware"**.
 *   미들웨어 병합 키가 .name 문자열이므로, 이 둘은 서로를 교체합니다. → 정답 6
 *
 * 해설: 이 문제는 API 를 호출하지 않아 비용이 0입니다.
 * 미들웨어 이름을 추측하지 말고 **항상 찍어서 확인**하는 습관을 들이세요.
 */
async function sol3() {
  console.log("\n===== [정답 3] =====");
  const entries: [string, { name: string }][] = [
    ["todoListMiddleware()            [langchain] ", todoListMiddleware()],
    ["createFilesystemMiddleware()    [deepagents]", createFilesystemMiddleware({ backend })],
    ["createSubAgentMiddleware()      [deepagents]", createSubAgentMiddleware({ defaultModel: MODEL })],
    ["createSummarizationMiddleware() [deepagents]", createSummarizationMiddleware({ backend })],
    ["createPatchToolCallsMiddleware()[deepagents]", createPatchToolCallsMiddleware()],
    ["summarizationMiddleware()       [langchain] ", summarizationMiddleware({ model: MODEL })],
  ];

  for (const [factory, m] of entries) {
    console.log(`${factory} → ${m.name}`);
  }
}

/* ===== [정답 4] =====
 * 기대 결과: 도구 집합이 완전히 동일 (차집합 양방향 모두 []).
 *
 * → 프롬프트 길이가 다른 이유: 내장 BASE_AGENT_PROMPT 가 **export 되지 않기** 때문입니다.
 *   deepagents 는 TASK_SYSTEM_PROMPT, DEFAULT_SUBAGENT_PROMPT,
 *   DEFAULT_GENERAL_PURPOSE_DESCRIPTION, GENERAL_PURPOSE_SUBAGENT,
 *   REQUIRED_MIDDLEWARE_NAMES 는 export 하지만 BASE_AGENT_PROMPT 는 안 합니다.
 *   직접 조립하면 그 2천 자를 손으로 써야 합니다 — 이것이 직접 조립의 실질적 비용입니다.
 *
 * 해설: 도구가 같다는 것이 이 스텝의 증명입니다.
 *   createDeepAgent 는 마법이 아니라 createAgent + 미들웨어 스택 조립입니다.
 */
async function sol4() {
  console.log("\n===== [정답 4] =====");
  const tools = [saveNote];
  const deepSink: { tools?: string[]; prompt?: string } = {};
  const manualSink: { tools?: string[]; prompt?: string } = {};

  const deep = await createDeepAgent({
    model: MODEL,
    tools,
    systemPrompt: "당신은 메모 도우미입니다.",
    middleware: [makeInspector("A. createDeepAgent", deepSink)],
  });
  await deep.invoke({ messages: [{ role: "user", content: "안녕" }] });

  const manual = createAgent({
    model: MODEL,
    tools,
    systemPrompt: "당신은 메모 도우미입니다.",
    middleware: [
      todoListMiddleware(),
      createFilesystemMiddleware({ backend }),
      createSubAgentMiddleware({
        defaultModel: MODEL,
        defaultTools: tools,
        subagents: [],
        generalPurposeAgent: true,
      }),
      createSummarizationMiddleware({ backend }),
      createPatchToolCallsMiddleware(),
      anthropicPromptCachingMiddleware({
        unsupportedModelBehavior: "ignore",
        minMessagesToCache: 1,
      }),
      makeInspector("B. 직접 조립", manualSink),
    ],
  }).withConfig({ recursionLimit: 10000 });
  await manual.invoke({ messages: [{ role: "user", content: "안녕" }] });

  const a = new Set(deepSink.tools ?? []);
  const b = new Set(manualSink.tools ?? []);
  console.log("createDeepAgent 에만:", [...a].filter((x) => !b.has(x))); // []
  console.log("직접 조립에만:", [...b].filter((x) => !a.has(x))); // []
  console.log(
    `프롬프트 길이 차이: ${(deepSink.prompt?.length ?? 0) - (manualSink.prompt?.length ?? 0)}자 ` +
      `(= BASE_AGENT_PROMPT 분량)`,
  );
}

/* ===== [정답 5] =====
 * 기대 결과: read_file 이 없다는 취지의 에러가 발생합니다.
 *
 * → 왜 read_file 은 필수인가: 문서에 명시되어 있습니다 —
 *   "read_file must be included in every explicit array because it is used
 *    by normal file-inspection flows and by large-result recovery guidance."
 *
 *   FilesystemMiddleware 는 도구 결과가 toolTokenLimitBeforeEvict(기본 20000 토큰)를
 *   넘으면 그 결과를 **파일로 오프로드**하고, 모델에게 "이 경로를 read_file 로 읽어라" 는
 *   안내를 줍니다. read_file 이 없으면 이 복구 경로가 끊겨서 큰 결과가 그냥 사라집니다.
 *   그래서 화이트리스트에서 뺄 수 없게 막아둔 것입니다.
 *
 * 참고: 화이트리스트를 통과해도 **백엔드 능력 필터**가 한 번 더 거릅니다.
 *   tools 에 "execute" 를 넣어도 StateBackend 처럼 실행을 지원하지 않는 백엔드면
 *   조용히 빠집니다.
 */
async function sol5() {
  console.log("\n===== [정답 5] =====");
  try {
    const mw = createFilesystemMiddleware({ backend, tools: ["ls", "glob"] }); // read_file 없음
    console.log("생성됨. name:", mw.name);
    const agent = createAgent({
      model: MODEL,
      systemPrompt: "탐색기",
      middleware: [mw, makeInspector("read_file 없는 화이트리스트")],
    });
    await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
  } catch (err) {
    console.log("throw:", (err as Error).message);
  }

  // 올바른 사용: read_file 을 반드시 포함
  const ok = createFilesystemMiddleware({ backend, tools: ["read_file", "ls", "glob", "grep"] });
  const readOnly = createAgent({
    model: MODEL,
    systemPrompt: "탐색기",
    middleware: [ok, makeInspector("올바른 읽기 전용")],
  });
  await readOnly.invoke({ messages: [{ role: "user", content: "안녕" }] });
}

/* ===== [정답 6] =====
 * 기대 결과: (a) 와 (b) 모두 **도구 9개로 동일**하고 **에러가 없습니다.**
 *
 * → (b) 에서 실제로 교체된 것: deepagents 의 SummarizationMiddleware.
 *   mergeMiddlewareStack 의 병합 키는 **.name 문자열**입니다.
 *   내 커스텀 미들웨어의 name 이 "SummarizationMiddleware" 라서
 *   기본 세그먼트의 같은 이름 항목을 **그 자리에서 교체**했습니다.
 *   (a) 는 새 이름이라 기본 세그먼트와 꼬리 세그먼트 사이에 **삽입**되었습니다.
 *
 * → 그 대가: deepagents 의 요약 미들웨어는 잘라낸 대화를 backend 의
 *   /conversation_history 아래로 **오프로드**합니다. 교체하면 그 기능을 잃습니다.
 *   긴 리서치에서 요약이 트리거되면 에이전트가 자기가 만든 파일 경로를 잊고,
 *   최종 리포트에서 중간 산출물을 참조하지 못합니다.
 *   증상이 "가끔 결과가 부실함" 으로 나타나 추적이 매우 어렵습니다.
 *
 * 확인법: 대화를 길게 만들어(요약 트리거) state.files 에
 *   "/conversation_history..." 로 시작하는 키가 생기는지 보세요.
 *   교체한 쪽에서는 안 생깁니다.
 *
 * 교훈: 도구 목록이 같다고 스택이 같은 게 아닙니다.
 *   요약 미들웨어는 도구를 등록하지 않으므로 도구 목록으로는 교체를 감지할 수 없습니다.
 */
async function sol6() {
  console.log("\n===== [정답 6] =====");

  // (a) 새 이름 → 추가
  const audit = createMiddleware({
    name: "MyAuditMiddleware",
    wrapToolCall: async (request, handler) => {
      console.log(`  [감사] ${request.toolCall.name}`);
      return handler(request);
    },
  });
  const added = await createDeepAgent({
    model: MODEL,
    tools: [saveNote],
    systemPrompt: "메모 도우미",
    middleware: [audit, makeInspector("(a) 새 이름 → 추가")],
  });
  await added.invoke({ messages: [{ role: "user", content: "안녕" }] });

  // (b) 이름 충돌 → 조용한 교체. 에러 없음, 도구 목록 동일.
  const impostor = createMiddleware({
    name: "SummarizationMiddleware", // ← deepagents 것과 같은 이름
    beforeModel: async () => undefined, // 아무것도 안 함 = 요약 기능이 통째로 사라짐
  });
  const replaced = await createDeepAgent({
    model: MODEL,
    tools: [saveNote],
    systemPrompt: "메모 도우미",
    middleware: [impostor, makeInspector("(b) 이름 충돌 → 교체")],
  });
  await replaced.invoke({ messages: [{ role: "user", content: "안녕" }] });

  console.log("→ 에러도 없고 도구 목록도 같습니다. 그게 이 함정의 핵심입니다.");
}

/* ===== [정답 7] =====
 * 기대 결과:
 *   (a) defaultMiddleware 있음 → files: [ '/notes.md' ]
 *   (b) defaultMiddleware 없음 → files: []
 *
 * → 응답 텍스트를 믿으면 안 되는 이유:
 *   서브에이전트의 중간 과정은 부모에게 **보이지 않습니다** (Step 06).
 *   task 도구는 서브에이전트의 마지막 AI 메시지 텍스트만 부모에게 돌려줍니다.
 *   그래서 서브에이전트가 write_file 이 없어 실패해놓고 "저장했습니다" 라고
 *   보고하면, 부모는 그 말을 그대로 믿고 사용자에게 전달합니다.
 *   **관측 가능한 사실(state.files)만 믿으세요.** 이것이 서브에이전트 디버깅의 기본입니다.
 *
 * 해설: 부모의 middleware 배열은 부모에게만 적용됩니다.
 *   서브에이전트의 미들웨어는 createSubAgentMiddleware 의
 *   defaultMiddleware / generalPurposeMiddleware 로만 정해집니다.
 *   createDeepAgent 를 쓰면 이 배선을 자동으로 해 줍니다 —
 *   서브에이전트마다 [todoList, filesystem, summarization, patchToolCalls] 를 깔아줍니다.
 */
async function sol7() {
  console.log("\n===== [정답 7] =====");

  const makeAgent = (withDefaultMiddleware: boolean) =>
    createAgent({
      model: MODEL,
      middleware: [
        createFilesystemMiddleware({ backend }),
        createSubAgentMiddleware({
          defaultModel: MODEL,
          ...(withDefaultMiddleware
            ? { defaultMiddleware: [createFilesystemMiddleware({ backend })] }
            : {}),
          subagents: [
            {
              name: "note-taker",
              description: "받은 내용을 /notes.md 에 저장하는 서브에이전트",
              systemPrompt: "받은 내용을 /notes.md 에 write_file 로 저장하세요.",
            },
          ],
          generalPurposeAgent: false,
        }),
      ],
      systemPrompt: "note-taker 서브에이전트에게 task 로 위임하세요.",
    }).withConfig({ recursionLimit: 10000 });

  for (const [label, flag] of [
    ["(a) defaultMiddleware 있음", true],
    ["(b) defaultMiddleware 없음", false],
  ] as const) {
    const agent = makeAgent(flag);
    const r = await agent.invoke({
      messages: [{ role: "user", content: "'배포는 금요일' 을 note-taker 에게 저장시켜줘." }],
    });
    console.log(`\n--- ${label} ---`);
    console.log("모델의 말:", r.messages.at(-1)?.text?.slice(0, 120).replace(/\n/g, " "));
    console.log("실제 files:", Object.keys((r as any).files ?? {})); // ← 이것만 믿는다
  }
}

/* ===== [정답 8] =====
 * 기대 결과 (토큰 수는 매번 다릅니다. 필드명은 결정적입니다):
 *   (a) 1회차: 캐시생성>0, 캐시읽기=0
 *       2회차: 캐시읽기>0            ← 적중
 *   (b) 시각 주입 후: 매 요청 캐시생성만 발생, 캐시읽기=0 으로 붙박이
 *
 * → (b) 에서 cache_read 가 0인 이유: 프롬프트 캐싱은 **접두사가 바이트 단위로 동일**할 때만
 *   적중합니다. 시스템 프롬프트에 매 요청 변하는 타임스탬프가 들어가면 접두사가
 *   달라져 캐시가 전부 무효화됩니다.
 *
 * 중요: 캐시 미스는 공짜가 아닙니다. **캐시 쓰기는 일반 입력보다 비쌉니다.**
 *   즉 캐싱을 켜놓고 접두사를 매번 깨면 캐싱을 안 켠 것보다 **비용이 늘어납니다.**
 *   cache_read 가 계속 0이면 반드시 원인을 찾으세요.
 *
 * 참고: createDeepAgent 가 MemoryMiddleware 를 캐싱 미들웨어보다 **뒤**에 놓는 것이
 *   바로 이 이유입니다 — 메모리 갱신이 프롬프트 캐시를 무효화하지 않게 하려는 의도적 배치.
 *   미들웨어 순서가 곧 성능 설계인 사례입니다.
 */
function makeUsageSpy(label: string) {
  return createMiddleware({
    name: "UsageSpyMiddleware",
    afterModel: async (state) => {
      const u = (state.messages.at(-1) as any)?.usage_metadata;
      if (u) {
        console.log(
          `  [${label}] 입력=${u.input_tokens} ` +
            `캐시생성=${u.input_token_details?.cache_creation ?? 0} ` +
            `캐시읽기=${u.input_token_details?.cache_read ?? 0}`,
        );
      }
      return undefined;
    },
  });
}

async function sol8() {
  console.log("\n===== [정답 8] =====");

  // (a) 정상 — 캐시 적중
  console.log("\n(a) 캐시 정상:");
  const good = await createDeepAgent({
    model: MODEL,
    tools: [saveNote],
    systemPrompt: "당신은 메모 도우미입니다.",
    middleware: [makeUsageSpy("정상")],
  });
  const r1 = await good.invoke({ messages: [{ role: "user", content: "안녕" }] });
  await good.invoke({ messages: [...r1.messages, { role: "user", content: "반가워" }] });

  // (b) 캐시 파괴 — 매 요청 변하는 타임스탬프를 프롬프트에 주입
  console.log("\n(b) 시각 주입으로 캐시 파괴:");
  const cacheBuster = createMiddleware({
    name: "TimestampMiddleware",
    wrapModelCall: async (request, handler) =>
      handler({
        ...request,
        systemMessage: request.systemMessage.concat(
          `\n현재 시각: ${new Date().toISOString()}`, // ← 매 요청 달라진다
        ),
      }),
  });

  const bad = await createDeepAgent({
    model: MODEL,
    tools: [saveNote],
    systemPrompt: "당신은 메모 도우미입니다.",
    middleware: [cacheBuster, makeUsageSpy("파괴")],
  });
  const r2 = await bad.invoke({ messages: [{ role: "user", content: "안녕" }] });
  await bad.invoke({ messages: [...r2.messages, { role: "user", content: "반가워" }] });

  console.log("\n→ (b) 는 2회차에도 캐시읽기가 0입니다. 접두사가 매번 달라졌기 때문입니다.");
}

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
