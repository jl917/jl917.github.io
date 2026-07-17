/**
 * Step 10 — 단기 메모리와 스레드 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-10-memory/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어 보세요.
 * 각 정답 위 주석에 "무엇을 확인해야 하는가" 와 함정 포인트가 적혀 있습니다.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool } from "langchain";
import { MemorySaver, REMOVE_ALL_MESSAGES } from "@langchain/langgraph";
import { RemoveMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection, printMessages, printKV } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [정답 1] 조용한 실패 재현 =====
 *
 * 핵심: checkpointer 가 없으면 thread_id 는 **읽히기만 하고 아무 일도 안 합니다.**
 * LangGraph 입장에서 thread_id 는 "저장소에서 이 키로 찾아라" 라는 지시인데,
 * 저장소 자체가 없으니 지시가 갈 곳이 없습니다. 에러도 경고도 없습니다.
 *
 * 기대 결과:
 *  - checkpointer 없음 → 2턴 messages 길이 = 2 (user, ai). 이름을 모른다고 답함.
 *  - checkpointer 있음 → 2턴 messages 길이 = 4 (user, ai, user, ai). "홍길동" 이라고 답함.
 *
 * 이 길이 숫자가 진단 도구입니다. 응답 텍스트는 모델이 눈치껏 둘러댈 수 있어서
 * (예: "성함을 말씀해 주시면...") 믿을 수 없습니다. messages 길이는 거짓말을 안 합니다.
 */

async function solution1(): Promise<void> {
  printSection("[정답 1] checkpointer 유무 비교");

  const config = { configurable: { thread_id: "ex1" } };

  // (A) checkpointer 없음
  const without = createAgent({ model: MODEL, tools: [] });
  await without.invoke(
    { messages: [{ role: "user", content: "제 이름은 홍길동입니다." }] },
    config,
  );
  const withoutSecond = await without.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    config,
  );

  console.log("\n[checkpointer 없음]");
  printMessages(withoutSecond.messages.at(-1)!);
  printKV({ "messages 길이": withoutSecond.messages.length }); // 2

  // (B) checkpointer 있음 — 딱 한 줄 차이
  const withSaver = createAgent({
    model: MODEL,
    tools: [],
    checkpointer: new MemorySaver(),
  });
  await withSaver.invoke(
    { messages: [{ role: "user", content: "제 이름은 홍길동입니다." }] },
    config,
  );
  const withSecond = await withSaver.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    config,
  );

  console.log("\n[checkpointer 있음]");
  printMessages(withSecond.messages.at(-1)!);
  printKV({ "messages 길이": withSecond.messages.length }); // 4
}

/* ===== [정답 2] 스레드 격리 =====
 *
 * 핵심: checkpointer 는 "스레드들이 사는 아파트" 고 thread_id 는 "호수" 입니다.
 * 같은 건물에 살아도 옆집 대화는 안 들립니다.
 *
 * 두 번째 포인트: 없는 thread_id 로 getState 를 하면 **에러가 아닙니다.**
 * values 는 {} (또는 빈 상태), next 는 [], parentConfig 는 undefined 인 빈 스냅샷이 옵니다.
 * "존재하지 않는 스레드" 와 "아직 아무 말 안 한 스레드" 를 LangGraph 는 구분하지 않습니다.
 * → 그래서 "이 스레드가 처음인가?" 를 판별하려면 getState 성공 여부가 아니라
 *   values.messages 의 길이(또는 undefined 여부)를 봐야 합니다.
 */

async function solution2(): Promise<void> {
  printSection("[정답 2] 스레드 격리");

  const checkpointer = new MemorySaver();
  const agent = createAgent({ model: MODEL, tools: [], checkpointer });

  const names = ["가", "나", "다"];

  for (const name of names) {
    await agent.invoke(
      { messages: [{ role: "user", content: `제 이름은 ${name}입니다.` }] },
      { configurable: { thread_id: `ex2-${name}` } },
    );
  }

  for (const name of names) {
    const config = { configurable: { thread_id: `ex2-${name}` } };
    const result = await agent.invoke(
      { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
      config,
    );
    console.log(`\n[ex2-${name}]`);
    printMessages(result.messages.at(-1)!);

    const state = await agent.getState(config);
    printKV({ "저장된 messages": (state.values.messages as BaseMessage[]).length }); // 각 4
  }

  // 없는 스레드 — 에러가 아니라 빈 스냅샷
  printSection("[정답 2] 없는 thread_id");
  const empty = await agent.getState({ configurable: { thread_id: "ex2-없음" } });
  printKV({
    values: JSON.stringify(empty.values),
    next: JSON.stringify(empty.next),
    "parentConfig 있음": String(empty.parentConfig !== undefined), // false
    비고: "에러가 아닙니다. 빈 스냅샷입니다.",
  });
}

/* ===== [정답 3] 체크포인트 히스토리 =====
 *
 * 답:
 *  - index 0 은 **가장 최신**입니다. 문서 표현 그대로 "most recent checkpoint being
 *    the first in the list". 시간 역순이라 for await 로 돌면 과거로 거슬러 갑니다.
 *  - metadata.source 로는 "input" (invoke 로 입력이 들어온 순간),
 *    "loop" (그래프가 한 스텝 돌 때마다), "update" (updateState 로 사람이 끼어든 것)
 *    이 보입니다.
 *  - 2턴인데 체크포인트가 6개 안팎입니다. **턴당 1개가 아니라 super-step 당 1개**이기
 *    때문입니다. invoke 한 번에 input → 모델 노드 실행 → 종료 로 여러 개가 찍힙니다.
 *    도구를 부르면 더 늘어납니다.
 *
 * 함정: "턴 수 = 체크포인트 수" 라고 가정하고 history[1] 을 "직전 턴" 이라고 집으면 틀립니다.
 *       반드시 metadata.step 이나 next / messages 개수로 판별하세요.
 */

async function solution3(): Promise<void> {
  printSection("[정답 3] 체크포인트 히스토리");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로만 답하세요.",
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "ex3" } };

  await agent.invoke({ messages: [{ role: "user", content: "안녕하세요." }] }, config);
  await agent.invoke({ messages: [{ role: "user", content: "오늘 기분이 좋네요." }] }, config);

  const history = [];
  for await (const state of agent.getStateHistory(config)) {
    history.push(state);
  }

  console.log(`체크포인트 ${history.length}개\n`);
  for (const [i, s] of history.entries()) {
    console.log(
      `[${i}] step=${String(s.metadata?.step).padStart(3)} ` +
        `source=${String(s.metadata?.source).padEnd(6)} ` +
        `next=${JSON.stringify(s.next).padEnd(12)} ` +
        `messages=${(s.values.messages as BaseMessage[] | undefined)?.length ?? 0} ` +
        `id=${String(s.config.configurable?.checkpoint_id).slice(0, 8)}…`,
    );
  }

  console.log(
    "\n→ index 0 이 최신이고 마지막 index 가 가장 오래된 것입니다 (시간 역순).\n" +
      "→ step 이 -1 인 것이 최초 입력, 그 뒤로 0, 1, 2... 로 증가합니다.",
  );
}

/* ===== [정답 4] updateState 와 리듀서 =====
 *
 * 이 스텝에서 가장 많이 틀리는 곳입니다.
 *
 * (a) updateState 는 상태를 **교체하지 않습니다.** 채널에 리듀서가 정의되어 있으면
 *     그 리듀서를 거칩니다. messages 채널의 리듀서는 "append + id 기준 병합" 입니다.
 *     그래서 2 → 3 이 됩니다. 1이 아닙니다.
 *
 *     비유하자면 updateState 는 "값을 대입" 이 아니라 "노드 하나가 그 값을 반환한 것처럼
 *     처리" 입니다. 노드가 { messages: [...] } 를 반환하면 append 되니까 여기서도 append 됩니다.
 *
 * (b) 정말 덮어쓰려면 리듀서에게 "다 지워라" 를 명령해야 합니다.
 *     RemoveMessage({ id: REMOVE_ALL_MESSAGES }) 를 **먼저** 넣고 그 뒤에 남길 것을 둡니다.
 *     순서가 중요합니다 — 뒤에 두면 방금 넣은 것까지 지워집니다.
 *
 * (c) 결과 1.
 *
 * 참고: 개별 메시지 하나만 지우려면 RemoveMessage({ id: 그메시지의id }) 를 씁니다.
 *       id 가 undefined 인 메시지(직접 만든 plain object)는 이 방법으로 못 지웁니다.
 */

async function solution4(): Promise<void> {
  printSection("[정답 4] updateState 와 리듀서");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "ex4" } };

  await agent.invoke({ messages: [{ role: "user", content: "안녕하세요." }] }, config);

  const before = await agent.getState(config);
  const beforeCount = (before.values.messages as BaseMessage[]).length;

  // (a) 덮어쓰기를 의도했지만 append 된다
  await agent.updateState(config, {
    messages: [{ role: "user", content: "덮어쓰기 시도" }],
  });
  const after = await agent.getState(config);
  const afterCount = (after.values.messages as BaseMessage[]).length;

  printKV({
    "(a) 전": beforeCount,   // 2
    "(a) 후": afterCount,    // 3 ← 1이 아님
    해석: "messages 리듀서가 append 했습니다",
  });

  // (b) 진짜 덮어쓰기 — REMOVE_ALL_MESSAGES 를 먼저
  await agent.updateState(config, {
    messages: [
      new RemoveMessage({ id: REMOVE_ALL_MESSAGES }),
      { role: "user", content: "이것만 남는다" },
    ],
  });

  // (c) 확인
  const replaced = await agent.getState(config);
  const replacedMessages = replaced.values.messages as BaseMessage[];
  printKV({ "(c) 최종 개수": replacedMessages.length }); // 1
  printMessages(replacedMessages);
}

/* ===== [정답 5] 포크 =====
 *
 * 핵심 3가지:
 *  1. 스냅샷의 config 에는 checkpoint_id 가 들어 있습니다. 그 config 를 invoke 나
 *     updateState 에 넘기면 "그 시점" 을 가리킵니다. 그냥 { thread_id } 만 넘기면
 *     항상 최신입니다 — 이걸 헷갈리면 타임트래블이 아니라 그냥 이어쓰기가 됩니다.
 *  2. updateState 는 원본을 수정하지 않습니다. 과거 체크포인트를 부모로 삼는
 *     **새 체크포인트**를 만들고 그것을 가리키는 config 를 반환합니다.
 *  3. 그래서 원본 thread_id 의 최신 상태는 그대로 남습니다.
 *
 * 함정: (a) 에서 스냅샷을 index 로 집으면(history[2] 같은 식) 모델이 도구를 부르거나
 *       미들웨어가 끼면 index 가 밀려서 엉뚱한 곳을 잡습니다.
 *       반드시 next / messages 개수 같은 **내용 기준**으로 찾으세요.
 *
 * 기대 결과: (c) 는 "금연", (d) 의 원본은 여전히 "마라톤" 대화(messages 6개).
 */

async function solution5(): Promise<void> {
  printSection("[정답 5] 포크");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로만 답하세요.",
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "ex5" } };

  await agent.invoke(
    { messages: [{ role: "user", content: "제 목표는 마라톤 완주입니다." }] },
    config,
  );
  await agent.invoke(
    { messages: [{ role: "user", content: "목표 달성을 위한 팁 하나만요." }] },
    config,
  );
  const third = await agent.invoke(
    { messages: [{ role: "user", content: "제 목표가 뭐라고 했죠?" }] },
    config,
  );
  console.log("\n[원본 3턴]");
  printMessages(third.messages.at(-1)!);

  // (a) 1턴이 끝난 시점 찾기 — 내용 기준
  const history = [];
  for await (const state of agent.getStateHistory(config)) {
    history.push(state);
  }
  const afterTurn1 = history.find(
    (s) => s.next.length === 0 && (s.values.messages as BaseMessage[] | undefined)?.length === 2,
  );

  if (afterTurn1 === undefined) {
    console.log("조건에 맞는 체크포인트가 없습니다.");
    return;
  }

  // (b) 포크 — 반환값이 새 가지를 가리키는 config
  const forkConfig = await agent.updateState(afterTurn1.config, {
    messages: [{ role: "user", content: "정정합니다. 제 목표는 금연입니다." }],
  });

  // (c) 새 가지에서 물어보기
  const forked = await agent.invoke(
    { messages: [{ role: "user", content: "제 목표가 뭐죠?" }] },
    forkConfig,
  );
  console.log("\n[포크된 가지]");
  printMessages(forked.messages.at(-1)!); // "금연"

  // (d) 원본 확인
  const mainline = await agent.getState(config);
  printKV({
    "원본 최신 messages": (mainline.values.messages as BaseMessage[]).length,
    비고: "포크는 원본을 건드리지 않았습니다",
  });
}

/* ===== [정답 6] thread_id 재사용 오염 =====
 *
 * 이건 테스트 코드와 데모 서버에서 실제로 자주 터집니다.
 * "thread_id: 'test'" 를 하드코딩해 두고 테스트를 두 번 돌리면, 두 번째 실행은
 * 첫 번째 실행의 대화를 물려받은 채 시작합니다. 그러면
 *  - 테스트가 처음엔 통과하고 재실행하면 실패하거나 (혹은 그 반대로)
 *  - 로컬에선 되는데 CI(깨끗한 프로세스)에선 다르게 동작합니다.
 *
 * MemorySaver 를 쓰면 프로세스 재시작마다 초기화돼서 이 문제가 가려집니다.
 * SqliteSaver 로 바꾸는 순간 드러납니다. "영속성으로 바꿨더니 테스트가 깨졌다" 의 정체입니다.
 *
 * 해법:
 *  - 사용자 대화: thread_id 를 crypto.randomUUID() 로 새로 발급하고 세션에 저장.
 *  - 테스트: 케이스마다 새 thread_id. 또는 checkpointer 자체를 케이스마다 새로 만들기.
 */

async function solution6(): Promise<void> {
  printSection("[정답 6] 오염 재현");

  const checkpointer = new MemorySaver();
  const agent = createAgent({ model: MODEL, tools: [], checkpointer });

  // 잘못된 방식: 고정 thread_id
  const shared = { configurable: { thread_id: "shared" } };

  await agent.invoke(
    { messages: [{ role: "user", content: "제 이름은 앨리스입니다." }] },
    shared,
  );

  // 다른 사용자인데 같은 thread_id 를 씁니다
  const leaked = await agent.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    shared,
  );
  console.log("\n[오염됨 — 앨리스가 나오면 남의 대화가 샌 것입니다]");
  printMessages(leaked.messages.at(-1)!);

  printSection("[정답 6] 고친 방식 — 사용자/세션마다 새 thread_id");

  // 세션 시작 시점에 발급해 두고 그 세션 동안만 재사용합니다.
  const aliceThread = { configurable: { thread_id: crypto.randomUUID() } };
  const bobThread = { configurable: { thread_id: crypto.randomUUID() } };

  await agent.invoke(
    { messages: [{ role: "user", content: "제 이름은 앨리스입니다." }] },
    aliceThread,
  );

  const bobAsks = await agent.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    bobThread,
  );
  console.log("\n[격리됨 — 밥은 앨리스를 모릅니다]");
  printMessages(bobAsks.messages.at(-1)!);
}

/* ===== [정답 7] 토큰 비용 비교 =====
 *
 * 관찰:
 *  - 트리밍 없음: 입력 토큰이 턴마다 계단식으로 증가합니다. N턴 대화의 총 입력 토큰은
 *    대략 N²/2 에 비례합니다. 매 턴 전체 히스토리를 다시 보내기 때문입니다.
 *    턴당 비용이 아니라 **누적 비용**을 봐야 이게 보입니다.
 *  - 트리밍 있음: 입력 토큰이 어느 선에서 평평해집니다. 총합은 N 에 비례(선형).
 *
 * 얻은 것: 비용 상한, 컨텍스트 윈도우 초과 방지, 지연시간 안정.
 * 잃은 것: 잘려나간 구간의 맥락. 사용자가 3턴 전에 말한 제약을 모델이 모릅니다.
 *          게다가 **조용히** 모릅니다 — 에이전트는 "모른다" 고 하지 않고 그냥 다르게 답합니다.
 *
 * 그래서 실무에서는 통짜 트리밍보다 요약(summarizationMiddleware)을 씁니다.
 * 오래된 구간을 버리는 대신 한 문단으로 압축해 남기면 맥락 손실이 훨씬 적습니다.
 * 단 요약도 공짜가 아닙니다 — 요약 자체가 모델 호출입니다. Step 11 에서 저울질합니다.
 */

async function solution7(): Promise<void> {
  // 마지막 AIMessage 에서 입력 토큰을 꺼냅니다.
  // usage_metadata 는 optional 이므로 in 연산자로 좁힌 뒤 읽습니다.
  const inputTokens = (m: BaseMessage): number => {
    if (!("usage_metadata" in m)) return 0;
    return m.usage_metadata?.input_tokens ?? 0;
  };

  printSection("[정답 7] 트리밍 없음");
  const plain = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로만 답하세요.",
    checkpointer: new MemorySaver(),
  });

  let plainTotal = 0;
  for (let i = 1; i <= 6; i++) {
    const result = await plain.invoke(
      { messages: [{ role: "user", content: `${i}번째 질문입니다. 한 문장으로 답해 주세요.` }] },
      { configurable: { thread_id: "ex7-plain" } },
    );
    const input = inputTokens(result.messages.at(-1)!);
    plainTotal += input;
    console.log(
      `[전체] 턴 ${i}: 입력 ${String(input).padStart(5)} | 누적 ${String(plainTotal).padStart(6)} | ` +
        `messages ${result.messages.length}`,
    );
  }

  printSection("[정답 7] 트리밍 있음");
  const trimMiddleware = createMiddleware({
    name: "TrimMessages",
    beforeModel: (state) => {
      const messages = state.messages;
      if (messages.length <= 3) return;
      const firstMsg = messages[0];
      if (firstMsg === undefined) return;
      const recentMessages = messages.length % 2 === 0 ? messages.slice(-3) : messages.slice(-4);
      return {
        messages: [new RemoveMessage({ id: REMOVE_ALL_MESSAGES }), firstMsg, ...recentMessages],
      };
    },
  });

  const trimmed = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로만 답하세요.",
    middleware: [trimMiddleware],
    checkpointer: new MemorySaver(),
  });

  let trimTotal = 0;
  for (let i = 1; i <= 6; i++) {
    const result = await trimmed.invoke(
      { messages: [{ role: "user", content: `${i}번째 질문입니다. 한 문장으로 답해 주세요.` }] },
      { configurable: { thread_id: "ex7-trim" } },
    );
    const input = inputTokens(result.messages.at(-1)!);
    trimTotal += input;
    console.log(
      `[트림] 턴 ${i}: 입력 ${String(input).padStart(5)} | 누적 ${String(trimTotal).padStart(6)} | ` +
        `messages ${result.messages.length}`,
    );
  }

  printSection("[정답 7] 비교");
  printKV({
    "트리밍 없음 누적 입력": plainTotal,
    "트리밍 있음 누적 입력": trimTotal,
    절감률: `${Math.round((1 - trimTotal / plainTotal) * 100)}%`,
    비고: "턴 수가 늘어날수록 격차가 벌어집니다 (제곱 vs 선형)",
  });
}

/* ===== [정답 8] 도구 결과의 저장 =====
 *
 * 답: 네, ToolMessage 도 체크포인트에 그대로 남습니다.
 * 체크포인터가 저장하는 건 "대화 텍스트" 가 아니라 **상태 채널 전체**이고,
 * messages 채널에는 AIMessage(tool_calls 포함)와 ToolMessage 가 다 들어 있습니다.
 *
 * 그래서 다음 턴에 "방금 조회한 날씨" 를 물으면 도구를 다시 안 부르고 답합니다 —
 * 히스토리에 답이 이미 있으니까요.
 *
 * 이게 양날의 검입니다:
 *  - 좋은 점: 중복 호출 없음, 비용 절약.
 *  - 나쁜 점: **날씨는 변하는데 모델은 옛 ToolMessage 를 사실로 믿습니다.**
 *    "아까 조회한 값" 이라고 명시하지 않으면 사용자는 실시간 값으로 오해합니다.
 *    시간에 민감한 도구는 도구 결과에 조회 시각을 같이 넣거나,
 *    contextEditingMiddleware 로 오래된 도구 결과를 비우는 걸 고려하세요 (Step 11).
 *
 * 또 하나: 도구 결과는 대개 **길고** 대화의 대부분을 차지합니다.
 * 컨텍스트가 터지면 범인은 보통 사용자 발화가 아니라 ToolMessage 입니다.
 */

async function solution8(): Promise<void> {
  printSection("[정답 8] 도구 결과의 저장");

  const getWeather = tool(
    async ({ city }) => {
      console.log(`  (도구 실제 실행됨: ${city})`);
      return `${city}의 날씨: 흐림, 19도`;
    },
    {
      name: "get_weather",
      description: "도시의 현재 날씨를 조회합니다.",
      schema: z.object({ city: z.string().describe("도시 이름") }),
    },
  );

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "ex8" } };

  await agent.invoke(
    { messages: [{ role: "user", content: "제주 날씨 알려주세요." }] },
    config,
  );

  const state = await agent.getState(config);
  const messages = state.values.messages as BaseMessage[];

  console.log("\n체크포인트에 저장된 메시지 타입:");
  for (const [i, m] of messages.entries()) {
    console.log(`  [${i}] ${m.getType()}`);
  }
  console.log("→ tool 이 목록에 있으면 도구 결과도 저장된 것입니다.");

  console.log("\n다음 턴 — 도구가 다시 실행되는지 보세요:");
  const second = await agent.invoke(
    { messages: [{ role: "user", content: "방금 조회한 날씨가 뭐였죠?" }] },
    config,
  );
  printMessages(second.messages.at(-1)!);
  console.log("→ '도구 실제 실행됨' 이 안 찍혔다면 히스토리에서 읽어 답한 것입니다.");
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await solution1();
  await solution2();
  await solution3();
  await solution4();
  await solution5();
  await solution6();
  await solution7();
  await solution8();
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
