/**
 * Step 08 — 미들웨어 조합
 * 실행: npx tsx docs/reference/deepagent/step-08-middleware/practice.ts
 *
 * 검증 버전: deepagents 1.11.0 / langchain 1.5.3 / @langchain/anthropic 1.5.1
 *
 * 이 파일은 본문 8-1 ~ 8-8 의 예제를 순서대로 담고 있습니다.
 * 핵심 메시지: createDeepAgent 를 벗기면 createAgent + 미들웨어 스택이다.
 *
 * 필요 환경변수: ANTHROPIC_API_KEY
 *   (OpenAI 를 쓰려면 MODEL 을 "openai:gpt-5.5" 로 바꾸고 OPENAI_API_KEY 를 설정하세요.
 *    단 8-7 의 프롬프트 캐싱은 Anthropic/Bedrock 전용입니다.)
 */
import "dotenv/config";
import {
  createDeepAgent,
  createFilesystemMiddleware,
  createSubAgentMiddleware,
  createSummarizationMiddleware,
  createPatchToolCallsMiddleware,
  createMemoryMiddleware,
  StateBackend,
  TASK_SYSTEM_PROMPT,
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

/**
 * 모델 호출 직전의 request 를 훔쳐보는 스파이.
 * request.tools 로 **모델에게 실제로 보이는 도구 목록**을,
 * request.systemMessage.text 로 최종 시스템 프롬프트를 봅니다.
 * (Step 07 의 makePromptSpy 를 도구까지 보도록 확장한 것)
 */
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
        console.log(`\n--- [${label}] ---`);
        console.log(`도구(${toolNames.length}): ${toolNames.join(", ")}`);
        console.log(`프롬프트 길이: ${prompt.length}`);
        console.log(
          `섹션: base=${prompt.includes("You are a Deep Agent")} ` +
            `fs=${prompt.includes("## Filesystem Tools")} ` +
            `task=${prompt.includes("subagent spawner")}`,
        );
      }
      return handler(request);
    },
  });
}

const getWeather = tool(({ city }: { city: string }) => `${city} 는 맑음, 21도.`, {
  name: "get_weather",
  description: "도시의 현재 날씨를 조회합니다.",
  schema: z.object({ city: z.string().describe("도시 이름") }),
});

/* ===== [8-1] createDeepAgent 의 기본 미들웨어 스택 정체 밝히기 ===== */
/**
 * createDeepAgent 는 마법이 아닙니다. 내부적으로 이 순서의 미들웨어 스택을 조립한 뒤
 * createAgent 에 넘길 뿐입니다 (deepagents@1.11.0 소스 기준):
 *
 *   [기본 세그먼트]
 *     1. todoListMiddleware()                    ← langchain 제공
 *     2. createSkillsMiddleware(...)             ← skills 를 줬을 때만
 *     3. createFilesystemMiddleware({ backend, permissions, tools })
 *     4. createSubAgentMiddleware({ defaultModel, defaultTools, subagents, ... })
 *     5. createSummarizationMiddleware({ backend })
 *     6. createPatchToolCallsMiddleware()
 *     7. createAsyncSubAgentMiddleware(...)      ← 비동기 서브에이전트를 줬을 때만
 *   [내 middleware 옵션이 여기 끼어든다]
 *   [꼬리 세그먼트]
 *     8. 하네스 프로파일의 extraMiddleware
 *     9. anthropicPromptCachingMiddleware() + CacheBreakpointMiddleware  ← Anthropic 모델일 때
 *        bedrockPromptCachingMiddleware()                                ← Bedrock 모델일 때
 *    10. createMemoryMiddleware({ backend, sources: memory })            ← memory 를 줬을 때만
 *    11. humanInTheLoopMiddleware({ interruptOn })                       ← interruptOn 을 줬을 때만
 *
 * 그리고 마지막에:
 *   return createAgent({ model, systemPrompt, tools, middleware, ... })
 *            .withConfig({ recursionLimit: 10000, metadata: { ls_integration: "deepagents" } });
 *
 * 즉 createDeepAgent === createAgent + 위 스택 + 프롬프트 조립 + recursionLimit 1만.
 */
async function step8_1() {
  console.log("\n########## [8-1] createDeepAgent 의 정체 ##########");

  const agent = await createDeepAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "당신은 날씨 봇입니다.",
    middleware: [makeInspector("createDeepAgent 기본")],
  });

  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
  // 도구 목록에 내가 준 get_weather 말고도
  // write_todos / ls / read_file / write_file / edit_file / glob / grep / task 가 보입니다.
  // 전부 미들웨어가 등록한 것입니다.
}

/* ===== [8-2] deepagents 가 export 하는 미들웨어들 ===== */
/**
 * 각 팩토리를 직접 만들어서 .name 을 찍어봅니다.
 * ⚠️ .name 이 표기와 다른 것들이 있습니다 (subAgentMiddleware 는 소문자 s!).
 *    이 이름이 8-5 의 "이름 충돌" 규칙에서 결정적입니다.
 */
async function step8_2() {
  console.log("\n########## [8-2] 미들웨어 카탈로그 ##########");

  // StateBackend 는 런타임 state 가 필요하므로 팩토리 형태로 넘깁니다.
  // createDeepAgent 의 기본값도 정확히 이것입니다: (config) => new StateBackend(config)
  const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

  const list = [
    todoListMiddleware(),
    createFilesystemMiddleware({ backend }),
    createSubAgentMiddleware({ defaultModel: MODEL }),
    createSummarizationMiddleware({ backend }),
    createPatchToolCallsMiddleware(),
    createMemoryMiddleware({ backend, sources: ["/AGENTS.md"] }),
    anthropicPromptCachingMiddleware({ unsupportedModelBehavior: "ignore" }),
    summarizationMiddleware({ model: MODEL }), // ← langchain 쪽. 이름을 잘 보세요.
  ];

  for (const m of list) {
    const tools = (m as any).tools?.map((t: any) => t.name) ?? [];
    console.log(`${m.name.padEnd(28)} 등록 도구: ${tools.length ? tools.join(", ") : "(없음)"}`);
  }

  // 출력을 보면 deepagents 의 createSummarizationMiddleware 와
  // langchain 의 summarizationMiddleware 가 **같은 이름**("SummarizationMiddleware")입니다.
  // 8-5 의 함정이 여기서 시작됩니다.
}

/* ===== [8-3] 직접 조립 — createDeepAgent 와 동등한 것 만들기 ===== */
/**
 * 8-1 의 스택을 손으로 쌓아 createAgent 에 넣습니다.
 * 두 에이전트의 **도구 목록**을 비교해 동등한지 확인합니다.
 *
 * ⚠️ 한 가지는 재현할 수 없습니다: 내장 BASE_AGENT_PROMPT 는 export 되지 않습니다.
 *    (TASK_SYSTEM_PROMPT, DEFAULT_SUBAGENT_PROMPT 는 export 됩니다)
 *    그래서 base 프롬프트는 내가 직접 써 넣어야 합니다 — 이것이 직접 조립의 비용입니다.
 */
async function step8_3() {
  console.log("\n########## [8-3] 직접 조립 ##########");

  const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);
  const tools = [getWeather];

  const deepSink: { tools?: string[]; prompt?: string } = {};
  const manualSink: { tools?: string[]; prompt?: string } = {};

  // (A) createDeepAgent
  const deep = await createDeepAgent({
    model: MODEL,
    tools,
    systemPrompt: "당신은 날씨 봇입니다.",
    middleware: [makeInspector("A. createDeepAgent", deepSink)],
  });
  await deep.invoke({ messages: [{ role: "user", content: "안녕" }] });

  // (B) createAgent + 미들웨어 스택 직접 조립
  const manual = createAgent({
    model: MODEL,
    tools,
    systemPrompt: "당신은 날씨 봇입니다.", // ← createDeepAgent 와 달리 이건 '교체'다 (Step 07 참고)
    middleware: [
      todoListMiddleware(),
      createFilesystemMiddleware({ backend }),
      createSubAgentMiddleware({
        defaultModel: MODEL,
        defaultTools: tools,
        subagents: [],
        // createDeepAgent 는 general-purpose 를 subagents 배열에 직접 넣고 여기선 false 로 끕니다.
        // 직접 조립할 땐 true 로 두면 이 미들웨어가 general-purpose 를 만들어 줍니다.
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

  // 비교
  const a = new Set(deepSink.tools ?? []);
  const b = new Set(manualSink.tools ?? []);
  const onlyA = [...a].filter((x) => !b.has(x));
  const onlyB = [...b].filter((x) => !a.has(x));

  console.log("\n===== 동등성 비교 =====");
  console.log("도구 집합 동일:", onlyA.length === 0 && onlyB.length === 0);
  console.log("createDeepAgent 에만:", onlyA);
  console.log("직접 조립에만:", onlyB);
  console.log(
    `프롬프트 길이: createDeepAgent=${deepSink.prompt?.length} 직접조립=${manualSink.prompt?.length}`,
  );
  console.log(
    "→ 도구는 같지만 프롬프트가 짧습니다. BASE_AGENT_PROMPT 가 빠졌기 때문입니다.",
  );
}

/* ===== [8-4] 필요한 기능만 빌려쓰기 ===== */
/**
 * Deep Agent 전체가 필요한 게 아니라 조각만 필요할 때가 많습니다.
 * 미들웨어는 개별로 export 되어 있으므로 평범한 createAgent 에 원하는 것만 꽂으면 됩니다.
 */
async function step8_4() {
  console.log("\n########## [8-4] 필요한 것만 빌려쓰기 ##########");

  const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

  // (A) 파일시스템만 — 서브에이전트도 todo 도 없는 "메모장 달린 에이전트"
  const fsOnly = createAgent({
    model: MODEL,
    systemPrompt: "당신은 메모 도우미입니다. 요청받은 내용을 /notes/ 아래 파일로 정리하세요.",
    middleware: [createFilesystemMiddleware({ backend }), makeInspector("A. 파일시스템만")],
  });

  const r1 = await fsOnly.invoke({
    messages: [{ role: "user", content: "오늘 회의 결론 '배포는 금요일'을 메모해줘." }],
  });
  console.log("생성된 파일:", Object.keys((r1 as any).files ?? {}));

  // (B) 계획만 — write_todos 만 있는 에이전트
  const planOnly = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "당신은 여행 플래너입니다.",
    middleware: [todoListMiddleware(), makeInspector("B. 계획만")],
  });

  const r2 = await planOnly.invoke({
    messages: [
      { role: "user", content: "서울, 부산, 제주 날씨를 확인하고 여행지를 추천하는 계획을 세워줘." },
    ],
  });
  console.log("todos:", JSON.stringify((r2 as any).todos ?? [], null, 2));

  // (C) 읽기 전용 파일시스템 — tools 화이트리스트로 도구를 줄이면 프롬프트 지침도 같이 줄어듭니다.
  const readOnly = createAgent({
    model: MODEL,
    systemPrompt: "당신은 코드 탐색기입니다. 파일을 수정하지 마세요.",
    middleware: [
      createFilesystemMiddleware({ backend, tools: ["read_file", "ls", "glob", "grep"] }),
      makeInspector("C. 읽기 전용 fs"),
    ],
  });
  await readOnly.invoke({ messages: [{ role: "user", content: "안녕" }] });
  // 도구 목록에 write_file / edit_file 이 없고,
  // "## Filesystem Tools" 헤더의 나열도 4개로 줄어든 것을 확인하세요.
}

/* ===== [8-5] createDeepAgent 에 middleware 추가 — 더하기인가 대체인가 ===== */
/**
 * 정답: **더하기**. 단, **이름이 같으면 대체**입니다.
 *
 * 내부의 mergeMiddlewareStack 규칙 (deepagents@1.11.0):
 *   - 기본/꼬리 세그먼트에 **같은 이름**이 있으면 → 그 자리에서 **교체**
 *   - 이름이 새로우면 → 기본 세그먼트와 꼬리 세그먼트 **사이에 삽입**
 */
async function step8_5() {
  console.log("\n########## [8-5] middleware 옵션: 추가 vs 교체 ##########");

  // (A) 새 이름 → 추가된다. 기본 스택은 그대로.
  let toolCallCount = 0;
  const logging = createMiddleware({
    name: "LogToolCallsMiddleware",
    wrapToolCall: async (request, handler) => {
      toolCallCount += 1;
      console.log(`  [로그] #${toolCallCount} ${request.toolCall.name}`);
      return handler(request);
    },
  });

  const added = await createDeepAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "당신은 날씨 봇입니다.",
    middleware: [logging, makeInspector("A. 커스텀 추가")],
  });
  await added.invoke({ messages: [{ role: "user", content: "서울 날씨 알려줘." }] });
  console.log("→ 기본 스택(파일/task 도구)이 그대로 남아있는지 위 도구 목록을 확인하세요.");

  // (B) 이름이 겹치면 → 교체된다. 이게 함정입니다.
  //     langchain 의 summarizationMiddleware 의 name 도 "SummarizationMiddleware" 입니다.
  //     deepagents 의 createSummarizationMiddleware 와 **이름이 같습니다.**
  //     따라서 아래는 "요약 미들웨어를 추가" 가 아니라 "deepagents 요약을 **교체**" 입니다.
  //     → 대화 기록을 /conversation_history 에 오프로드하는 기능을 잃습니다.
  const replaced = await createDeepAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "당신은 날씨 봇입니다.",
    middleware: [
      summarizationMiddleware({ model: MODEL, maxTokensBeforeSummary: 1000 }),
      makeInspector("B. 이름 충돌"),
    ],
  });
  await replaced.invoke({ messages: [{ role: "user", content: "안녕" }] });
  console.log("→ 에러 없이 조용히 교체되었습니다. 8-6 에서 결과를 봅니다.");
}

/* ===== [8-6] 미들웨어 순서 문제 ===== */
/**
 * 순서가 중요한 이유 두 가지.
 *
 * 1) 훅 실행 순서
 *    - before* / wrap* 은 배열 **앞** 미들웨어가 **바깥**(먼저 실행)
 *    - after* 는 **뒤** 미들웨어가 먼저 실행 (양파 껍질처럼 감싼다)
 *    프롬프트를 덧붙이는 미들웨어는 앞에 둘수록 프롬프트 **앞쪽**에 붙습니다.
 *
 * 2) 서브에이전트는 부모의 미들웨어 배열을 **상속하지 않는다**
 *    서브에이전트의 미들웨어는 createSubAgentMiddleware 의
 *    defaultMiddleware / generalPurposeMiddleware 옵션으로만 정해집니다.
 *    직접 조립하면서 이걸 안 주면 서브에이전트는 **파일 도구 없이** 태어납니다.
 */
async function step8_6() {
  console.log("\n########## [8-6] 순서와 상속 ##########");

  const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

  // (1) 훅 순서 확인 — 세 미들웨어가 프롬프트에 마커를 덧붙입니다.
  const marker = (n: string) =>
    createMiddleware({
      name: `Marker${n}Middleware`,
      wrapModelCall: async (request, handler) => {
        console.log(`  들어감: ${n}`);
        const r = await handler({
          ...request,
          systemMessage: request.systemMessage.concat(`\n[${n}]`),
        });
        console.log(`  나옴:   ${n}`);
        return r;
      },
    });

  const ordered = createAgent({
    model: MODEL,
    systemPrompt: "짧게 답하세요.",
    middleware: [marker("첫째"), marker("둘째"), marker("셋째")],
  });
  await ordered.invoke({ messages: [{ role: "user", content: "안녕" }] });
  console.log("→ 들어감: 첫째→둘째→셋째, 나옴: 셋째→둘째→첫째 (양파 구조)");

  // (2) 서브에이전트 미들웨어 상속 — 틀린 조립
  console.log("\n[틀린 조립] 서브에이전트에 defaultMiddleware 를 안 줌");
  const wrong = createAgent({
    model: MODEL,
    middleware: [
      createFilesystemMiddleware({ backend }), // ← 부모에게만 파일 도구가 생긴다
      createSubAgentMiddleware({
        defaultModel: MODEL,
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
    systemPrompt: "note-taker 서브에이전트에게 위임하세요.",
  });

  const wrongResult = await wrong.invoke({
    messages: [{ role: "user", content: "'배포는 금요일' 을 note-taker 에게 저장시켜줘." }],
  });
  console.log("결과:", wrongResult.messages.at(-1)?.text?.slice(0, 200));
  console.log("파일:", Object.keys((wrongResult as any).files ?? {}));
  console.log("→ 서브에이전트에 write_file 이 없어 실패하거나 엉뚱하게 답합니다.");

  // (3) 올바른 조립 — defaultMiddleware 로 서브에이전트에게도 파일시스템을 준다
  console.log("\n[올바른 조립] defaultMiddleware 로 파일시스템 전달");
  const right = createAgent({
    model: MODEL,
    middleware: [
      createFilesystemMiddleware({ backend }),
      createSubAgentMiddleware({
        defaultModel: MODEL,
        defaultMiddleware: [createFilesystemMiddleware({ backend })], // ← 이 한 줄이 핵심
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
    systemPrompt: "note-taker 서브에이전트에게 위임하세요.",
  });

  const rightResult = await right.invoke({
    messages: [{ role: "user", content: "'배포는 금요일' 을 note-taker 에게 저장시켜줘." }],
  });
  console.log("결과:", rightResult.messages.at(-1)?.text?.slice(0, 200));
  console.log("파일:", Object.keys((rightResult as any).files ?? {}));

  // 참고: createDeepAgent 를 쓰면 이 배선을 자동으로 해 줍니다.
  //       normalizeSubagentSpec 이 서브에이전트마다
  //       [todoList, filesystem, summarization, patchToolCalls] 를 깔아줍니다.
}

/* ===== [8-7] 캐싱 미들웨어 (provider 프롬프트 캐싱) ===== */
/**
 * Deep Agent 의 시스템 프롬프트는 5천 자가 넘습니다(Step 07). 매 턴 다시 보냅니다.
 * Anthropic/Bedrock 의 프롬프트 캐싱은 이 앞부분을 재사용해 입력 토큰 비용을 줄입니다.
 *
 * createDeepAgent 는 Anthropic 모델이면 이걸 **자동으로** 붙입니다:
 *   anthropicPromptCachingMiddleware({ unsupportedModelBehavior: "ignore", minMessagesToCache: 1 })
 *   + 내부 CacheBreakpointMiddleware
 * Bedrock 이면 bedrockPromptCachingMiddleware({ unsupportedModelBehavior: "ignore" }).
 *
 * 즉 createDeepAgent 를 쓰면 캐싱은 공짜입니다. 직접 조립할 때만 신경 쓰면 됩니다.
 */
async function step8_7() {
  console.log("\n########## [8-7] 프롬프트 캐싱 ##########");

  // usage_metadata 로 캐시 읽기/쓰기 토큰을 확인합니다.
  const usageSpy = createMiddleware({
    name: "UsageSpyMiddleware",
    afterModel: async (state) => {
      const last = state.messages.at(-1) as any;
      const u = last?.usage_metadata;
      if (u) {
        console.log(
          `  입력=${u.input_tokens} 출력=${u.output_tokens} ` +
            `캐시생성=${u.input_token_details?.cache_creation ?? 0} ` +
            `캐시읽기=${u.input_token_details?.cache_read ?? 0}`,
        );
      }
      return undefined;
    },
  });

  const agent = await createDeepAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "당신은 날씨 봇입니다.",
    middleware: [usageSpy],
  });

  console.log("1회차 (캐시 생성):");
  const r1 = await agent.invoke({ messages: [{ role: "user", content: "서울 날씨?" }] });

  console.log("2회차 (캐시 읽기 — 같은 대화를 이어감):");
  await agent.invoke({
    messages: [...r1.messages, { role: "user", content: "부산은?" }],
  });

  // 캐시읽기 값이 0보다 커지면 성공입니다.
  // 캐시는 기본 5분 TTL 이고, 프롬프트 앞부분이 **바이트 단위로 동일**해야 적중합니다.
}

/* ===== [8-8] 종합 — 최소 Deep Agent 를 직접 조립 ===== */
/**
 * "파일시스템 + 계획 + 서브에이전트" 만 있는 경량 리서치 에이전트.
 * 요약/패치/캐싱은 뺐습니다. 무엇을 얻고 무엇을 잃는지 주석으로 정리했습니다.
 */
async function step8_8() {
  console.log("\n########## [8-8] 종합: 최소 조립 ##########");

  const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

  const search = tool(
    ({ query }: { query: string }) => {
      const db: Record<string, string> = {
        rust: "Rust 는 2015년 1.0 출시. 소유권 시스템으로 메모리 안전을 보장.",
        go: "Go 는 2012년 1.0 출시. 고루틴 기반 동시성이 특징.",
      };
      const hit = Object.entries(db).find(([k]) => query.toLowerCase().includes(k));
      return hit ? hit[1] : "NOT_FOUND";
    },
    {
      name: "search",
      description: "프로그래밍 언어 정보를 검색합니다.",
      schema: z.object({ query: z.string() }),
    },
  );

  const subagentMiddleware = [
    createFilesystemMiddleware({ backend }),
    createPatchToolCallsMiddleware(),
  ];

  const agent = createAgent({
    model: MODEL,
    tools: [search],
    systemPrompt: `당신은 언어 리서처입니다.

## 워크플로
1. 조사할 언어가 2개 이상이면 write_todos 로 계획을 세운다.
2. 각 언어는 researcher 서브에이전트에게 task 로 위임한다.
3. 결과를 /report.md 에 write_file 로 저장한다.

## 도구 사용 규칙
- search 가 "NOT_FOUND" 를 반환하면 "자료 없음" 으로 표기한다. 지어내지 않는다.
- 파일 경로는 항상 /로 시작한다.

## 중단 조건
- /report.md 를 쓴 뒤 즉시 종료한다.

${TASK_SYSTEM_PROMPT}`, // ← 내장 task 지침을 재사용. export 되어 있어 그대로 쓸 수 있다.
    middleware: [
      todoListMiddleware(),
      createFilesystemMiddleware({ backend }),
      createSubAgentMiddleware({
        defaultModel: MODEL,
        defaultTools: [search],
        defaultMiddleware: subagentMiddleware, // ← 서브에이전트에도 파일시스템을 준다 (8-6)
        subagents: [
          {
            name: "researcher",
            description: "언어 하나를 조사해 3줄 요약을 반환합니다.",
            systemPrompt: "search 로 조사하고 3줄로 요약하세요. NOT_FOUND 면 '자료 없음' 이라고만 답하세요.",
            tools: [search],
          },
        ],
        generalPurposeAgent: false, // general-purpose 는 안 쓴다 (토큰 절약)
      }),
      anthropicPromptCachingMiddleware({ unsupportedModelBehavior: "ignore", minMessagesToCache: 1 }),
      makeInspector("8-8 최소 조립"),
    ],
  }).withConfig({ recursionLimit: 10000 });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "Rust 와 Go 와 Zig 를 비교 조사해줘." }],
  });

  console.log("\n최종 응답:", result.messages.at(-1)?.text);
  console.log("파일:", Object.keys((result as any).files ?? {}));

  // 얻은 것: 스택이 명시적이라 읽힌다. 요약 미들웨어가 없어 대화가 잘리지 않는다.
  // 잃은 것:
  //   - BASE_AGENT_PROMPT (직접 써야 함)
  //   - 긴 대화에서의 자동 요약/오프로드 → 컨텍스트 초과 시 에러
  //   - patchToolCalls 를 부모에 안 넣었으므로 도구 호출 짝이 깨지면 provider 에러
  //   - 프로파일/권한/HITL 배선
  // → 대부분의 경우 createDeepAgent + middleware 옵션이 정답입니다.
}

/* ===== 실행 ===== */
async function main() {
  await step8_1();
  await step8_2();
  await step8_3();
  await step8_4();
  await step8_5();
  await step8_6();
  await step8_7();
  await step8_8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
