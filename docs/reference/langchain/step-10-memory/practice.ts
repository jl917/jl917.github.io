/**
 * Step 10 — 단기 메모리와 스레드
 * 실행: npx tsx docs/reference/langchain/step-10-memory/practice.ts
 *
 * 이 파일은 본문 10-1 ~ 10-9 의 예제를 순서대로 담고 있습니다.
 * 블록 주석의 [10-x] 번호가 본문 소제목과 1:1 로 대응합니다.
 *
 * 주의: 모델을 실제로 호출하므로 ANTHROPIC_API_KEY 가 필요하고 요금이 발생합니다.
 *       전체 실행에 대략 20~30회의 모델 호출이 들어갑니다.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool } from "langchain";
import { MemorySaver, REMOVE_ALL_MESSAGES } from "@langchain/langgraph";
import { RemoveMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection, printMessages, printKV } from "../project/src/lib/print.js";

// 이 스텝 전체에서 쓰는 모델. OpenAI 로 바꾸려면 "openai:gpt-5.5" 로만 고치면 됩니다
// (checkpointer 동작은 제공자와 무관합니다 — 저장은 LangGraph 쪽 일이니까요).
const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [10-1] LLM은 상태가 없다 — 메모리는 네가 만드는 것 ===== */

async function step10_1(): Promise<void> {
  printSection("[10-1] checkpointer 없는 에이전트 — 두 번째 턴에서 이름을 잊는다");

  // checkpointer 를 주지 않았습니다. 이게 기본값입니다.
  const agent = createAgent({
    model: MODEL,
    tools: [],
  });

  const first = await agent.invoke({
    messages: [{ role: "user", content: "안녕하세요. 제 이름은 김민수입니다." }],
  });
  printMessages(first.messages.at(-1)!);

  // 두 번째 invoke. 앞의 대화와 아무 연결이 없습니다.
  const second = await agent.invoke({
    messages: [{ role: "user", content: "제 이름이 뭐라고 했죠?" }],
  });
  printMessages(second.messages.at(-1)!);

  // 반환된 messages 배열의 길이를 보면 "이 호출이 무엇을 봤는지"가 드러납니다.
  printKV({
    "1턴 messages 길이": first.messages.length,
    "2턴 messages 길이": second.messages.length, // 3이 아니라 2입니다
  });

  printSection("[10-1] 직접 히스토리를 이어 붙이면 기억한다 (수동 메모리)");

  // 메모리의 정체는 이겁니다. 마법이 아니라 "배열을 다시 보내는 것".
  const history: BaseMessage[] = [...first.messages];
  const manual = await agent.invoke({
    messages: [...history, { role: "user", content: "제 이름이 뭐라고 했죠?" }],
  });
  printMessages(manual.messages.at(-1)!);
}

/* ===== [10-2] checkpointer와 thread_id — 대화가 저장되는 원리 ===== */

async function step10_2(): Promise<void> {
  printSection("[10-2] thread_id 만 주고 checkpointer 는 안 준 경우 (조용한 실패)");

  const agentWithoutSaver = createAgent({
    model: MODEL,
    tools: [],
    // checkpointer 없음
  });

  const cfg = { configurable: { thread_id: "trap-thread" } };

  await agentWithoutSaver.invoke(
    { messages: [{ role: "user", content: "제 이름은 김민수입니다." }] },
    cfg,
  );

  const forgot = await agentWithoutSaver.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    cfg,
  );

  // thread_id 를 줬는데도 기억하지 못합니다. 에러도, 경고도 없습니다.
  printMessages(forgot.messages.at(-1)!);
  printKV({ "messages 길이": forgot.messages.length });

  printSection("[10-2] checkpointer 를 붙이면 저장된다");

  const checkpointer = new MemorySaver();
  const agent = createAgent({
    model: MODEL,
    tools: [],
    checkpointer,
  });

  await agent.invoke(
    { messages: [{ role: "user", content: "제 이름은 김민수입니다." }] },
    { configurable: { thread_id: "ok-thread" } },
  );

  const remembered = await agent.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    { configurable: { thread_id: "ok-thread" } },
  );

  printMessages(remembered.messages.at(-1)!);
  // 이번에는 4입니다: user, ai, user, ai — 앞 턴이 되살아났습니다.
  printKV({ "messages 길이": remembered.messages.length });
}

/* ===== [10-3] MemorySaver로 멀티턴 대화 만들기 ===== */

async function step10_3(): Promise<void> {
  printSection("[10-3] 4턴 대화 — 도구까지 섞어서");

  // 도구를 하나 붙여 둡니다. 도구 호출/결과(ToolMessage)도 체크포인트에
  // 그대로 저장된다는 것을 보여주기 위해서입니다.
  const getWeather = tool(
    async ({ city }) => `${city}의 날씨: 맑음, 24도`,
    {
      name: "get_weather",
      description: "도시의 현재 날씨를 조회합니다.",
      schema: z.object({ city: z.string().describe("도시 이름") }),
    },
  );

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "당신은 간결하게 답하는 여행 도우미입니다.",
    checkpointer: new MemorySaver(),
  });

  // thread_id 는 문자열이면 무엇이든 됩니다. 실무에서는 uuid 나 "user:123:chat:456" 같은
  // 구조화된 키를 씁니다. PostgresSaver 를 쓸 거라면 255자를 넘기지 마세요.
  const config = { configurable: { thread_id: "trip-2026-07" } };

  const turns = [
    "저는 부산에 살고 있어요.",
    "제가 사는 도시 날씨 알려주세요.",  // ← "부산"이라고 말하지 않았습니다
    "그럼 반팔 입어도 될까요?",
    "제가 어디 산다고 했죠?",
  ];

  for (const [i, text] of turns.entries()) {
    const result = await agent.invoke(
      { messages: [{ role: "user", content: text }] },
      config,
    );
    console.log(`\n--- 턴 ${i + 1} (누적 messages: ${result.messages.length}) ---`);
    printMessages(result.messages.at(-1)!);
  }

  // 마지막에 전체 대화를 통째로 확인합니다.
  printSection("[10-3] 저장된 전체 대화");
  const finalState = await agent.getState(config);
  printMessages(finalState.values.messages as BaseMessage[]);
}

/* ===== [10-4] 스레드 격리 — thread_id를 바꾸면 남남 ===== */

async function step10_4(): Promise<void> {
  printSection("[10-4] 같은 checkpointer, 다른 thread_id");

  // checkpointer 하나가 여러 스레드를 담습니다. 스레드끼리는 서로를 못 봅니다.
  const checkpointer = new MemorySaver();
  const agent = createAgent({ model: MODEL, tools: [], checkpointer });

  const alice = { configurable: { thread_id: "user-alice" } };
  const bob = { configurable: { thread_id: "user-bob" } };

  await agent.invoke(
    { messages: [{ role: "user", content: "제 이름은 앨리스입니다." }] },
    alice,
  );
  await agent.invoke(
    { messages: [{ role: "user", content: "제 이름은 밥입니다." }] },
    bob,
  );

  const askAlice = await agent.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    alice,
  );
  const askBob = await agent.invoke(
    { messages: [{ role: "user", content: "제 이름이 뭐죠?" }] },
    bob,
  );

  console.log("\n[user-alice]");
  printMessages(askAlice.messages.at(-1)!);
  console.log("\n[user-bob]");
  printMessages(askBob.messages.at(-1)!);

  // 존재하지 않는 thread_id 는 에러가 아니라 "빈 스레드"입니다.
  printSection("[10-4] 처음 보는 thread_id 의 상태");
  const fresh = await agent.getState({ configurable: { thread_id: "never-used" } });
  printKV({
    values: JSON.stringify(fresh.values),
    next: JSON.stringify(fresh.next),
    createdAt: String(fresh.createdAt),
    parentConfig: JSON.stringify(fresh.parentConfig),
  });
}

/* ===== [10-5] 상태 조회/조작 — getState, getStateHistory, updateState ===== */

async function step10_5(): Promise<void> {
  printSection("[10-5] getState — 스냅샷의 모양");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "inspect-1" } };

  await agent.invoke(
    { messages: [{ role: "user", content: "1부터 5까지 세어 주세요." }] },
    config,
  );

  const snapshot = await agent.getState(config);
  printKV({
    "values.messages 개수": (snapshot.values.messages as BaseMessage[]).length,
    next: JSON.stringify(snapshot.next), // 실행이 끝났으면 []
    "config.thread_id": snapshot.config.configurable?.thread_id,
    "config.checkpoint_id": snapshot.config.configurable?.checkpoint_id,
    "metadata.source": snapshot.metadata?.source,
    "metadata.step": snapshot.metadata?.step,
    createdAt: snapshot.createdAt,
    "parentConfig 있음": String(snapshot.parentConfig !== undefined),
    "tasks 개수": snapshot.tasks.length,
  });

  printSection("[10-5] getStateHistory — 최신이 먼저");

  // getStateHistory 는 배열이 아니라 async iterable 입니다. for await 로 돕니다.
  const history = [];
  for await (const state of agent.getStateHistory(config)) {
    history.push(state);
  }

  console.log(`체크포인트 ${history.length}개 (index 0 이 가장 최신)\n`);
  for (const [i, s] of history.entries()) {
    console.log(
      `[${i}] step=${String(s.metadata?.step).padStart(3)} ` +
        `source=${String(s.metadata?.source).padEnd(6)} ` +
        `next=${JSON.stringify(s.next).padEnd(12)} ` +
        `messages=${(s.values.messages as BaseMessage[] | undefined)?.length ?? 0} ` +
        `checkpoint_id=${String(s.config.configurable?.checkpoint_id).slice(0, 8)}…`,
    );
  }

  printSection("[10-5] updateState — messages 는 덮어쓰기가 아니라 append 된다");

  const before = await agent.getState(config);
  const beforeCount = (before.values.messages as BaseMessage[]).length;

  // "이 한 개로 바꿔치기" 를 의도했지만...
  await agent.updateState(config, {
    messages: [{ role: "user", content: "(수동으로 끼워 넣은 메시지)" }],
  });

  const after = await agent.getState(config);
  const afterCount = (after.values.messages as BaseMessage[]).length;

  printKV({
    "updateState 전": beforeCount,
    "updateState 후": afterCount, // beforeCount + 1 입니다. 1이 아닙니다.
    결론: afterCount === beforeCount + 1 ? "append 되었다 (리듀서가 개입)" : "?",
  });

  printSection("[10-5] 진짜로 지우려면 RemoveMessage");

  // messages 리듀서는 RemoveMessage 라는 특수 메시지를 "삭제 명령"으로 해석합니다.
  const lastId = (after.values.messages as BaseMessage[]).at(-1)!.id!;
  await agent.updateState(config, {
    messages: [new RemoveMessage({ id: lastId })],
  });

  const removed = await agent.getState(config);
  printKV({
    "삭제 후 messages 개수": (removed.values.messages as BaseMessage[]).length,
  });
}

/* ===== [10-6] 타임트래블 — 과거 체크포인트에서 다시 실행하기 ===== */

async function step10_6(): Promise<void> {
  printSection("[10-6] 대화를 3턴 쌓는다");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "당신은 한 문장으로만 답합니다.",
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "timetravel-1" } };

  await agent.invoke(
    { messages: [{ role: "user", content: "제가 좋아하는 색은 파란색입니다." }] },
    config,
  );
  await agent.invoke(
    { messages: [{ role: "user", content: "제가 좋아하는 동물은 고양이입니다." }] },
    config,
  );
  const third = await agent.invoke(
    { messages: [{ role: "user", content: "제 취향을 요약해 주세요." }] },
    config,
  );
  printMessages(third.messages.at(-1)!);

  printSection("[10-6] 히스토리에서 과거 체크포인트 고르기");

  const history = [];
  for await (const state of agent.getStateHistory(config)) {
    history.push(state);
  }

  // 두 번째 턴이 끝난 시점을 찾습니다.
  // 판별 기준: 실행이 멈춰 있고(next 가 비었고) messages 가 4개인 스냅샷.
  const afterTurn2 = history.find(
    (s) =>
      s.next.length === 0 &&
      (s.values.messages as BaseMessage[] | undefined)?.length === 4,
  );

  if (afterTurn2 === undefined) {
    console.log("조건에 맞는 체크포인트를 못 찾았습니다 (모델이 도구를 부르면 개수가 달라집니다).");
    return;
  }

  printKV({
    "고른 checkpoint_id": String(afterTurn2.config.configurable?.checkpoint_id),
    "그 시점 messages": (afterTurn2.values.messages as BaseMessage[]).length,
    "그 시점 step": String(afterTurn2.metadata?.step),
  });

  printSection("[10-6] 리플레이 — 과거 시점에 새 질문을 던진다");

  // 스냅샷의 config 에는 checkpoint_id 가 박혀 있습니다.
  // 그 config 로 invoke 하면 "그 시점의 상태"에서 이어서 실행됩니다.
  const replay = await agent.invoke(
    { messages: [{ role: "user", content: "제가 좋아하는 동물이 뭐죠?" }] },
    afterTurn2.config,
  );
  printMessages(replay.messages.at(-1)!);
  printKV({ "리플레이 결과 messages": replay.messages.length });

  printSection("[10-6] 포크 — 과거를 고쳐서 분기시킨다");

  // updateState 로 과거 체크포인트 위에 새 체크포인트를 만듭니다.
  // 원본은 그대로 남고, 반환된 config 가 "새 가지"를 가리킵니다.
  const forkConfig = await agent.updateState(afterTurn2.config, {
    messages: [{ role: "user", content: "정정합니다. 제가 좋아하는 동물은 강아지입니다." }],
  });

  const forked = await agent.invoke(
    { messages: [{ role: "user", content: "제가 좋아하는 동물이 뭐죠?" }] },
    forkConfig,
  );
  printMessages(forked.messages.at(-1)!);

  printSection("[10-6] 원본 스레드는 무사한가");

  // 포크를 만들어도 thread_id 의 "최신"은 여전히 원래 대화의 끝입니다.
  const mainline = await agent.getState(config);
  printKV({
    "원본 최신 messages": (mainline.values.messages as BaseMessage[]).length,
  });
}

/* ===== [10-7] 영속 체크포인터 — SqliteSaver / PostgresSaver ===== */

async function step10_7(): Promise<void> {
  printSection("[10-7] 영속 체크포인터 (설명용 — 이 프로젝트엔 패키지가 없습니다)");

  // 아래 코드는 별도 패키지를 설치해야 동작합니다.
  //
  //   npm install @langchain/langgraph-checkpoint-sqlite
  //   npm install @langchain/langgraph-checkpoint-postgres
  //
  // 설치 전에 import 하면 "Cannot find module ..." 로 파일 전체가 죽으므로
  // 주석으로 남깁니다. 설치했다면 주석을 풀고 MemorySaver 자리에 끼우면 끝입니다.
  //
  // --- SQLite ---
  // import { SqliteSaver } from "@langchain/langgraph-checkpoint-sqlite";
  // const checkpointer = SqliteSaver.fromConnString("./checkpoints.sqlite");
  //
  // --- PostgreSQL ---
  // import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";
  // const checkpointer = PostgresSaver.fromConnString(
  //   "postgresql://postgres:postgres@localhost:5432/postgres?sslmode=disable",
  // );
  // await checkpointer.setup();   // ← 최초 1회. 테이블을 만듭니다. 빼먹으면 런타임 에러.
  //
  // const agent = createAgent({ model: MODEL, tools: [], checkpointer });

  console.log("코드는 주석으로만 두었습니다. 본문 10-7 을 보세요.");

  printSection("[10-7] MemorySaver 는 프로세스와 함께 사라진다 — 재현");

  // 프로세스 재시작을 흉내 냅니다: 새 MemorySaver = 새 (빈) 저장소.
  const saverA = new MemorySaver();
  const agentA = createAgent({ model: MODEL, tools: [], checkpointer: saverA });
  const cfg = { configurable: { thread_id: "restart-demo" } };

  await agentA.invoke(
    { messages: [{ role: "user", content: "제 이름은 김민수입니다." }] },
    cfg,
  );
  const stateA = await agentA.getState(cfg);
  printKV({ "재시작 전 messages": (stateA.values.messages as BaseMessage[]).length });

  // "재시작"
  const saverB = new MemorySaver();
  const agentB = createAgent({ model: MODEL, tools: [], checkpointer: saverB });
  const stateB = await agentB.getState(cfg); // 같은 thread_id 인데도...

  printKV({
    "재시작 후 messages": (stateB.values.messages as BaseMessage[] | undefined)?.length ?? 0,
    비고: "thread_id 는 같지만 저장소가 다른 객체입니다",
  });
}

/* ===== [10-8] 컨텍스트 윈도우 관리 ===== */

async function step10_8(): Promise<void> {
  printSection("[10-8] 대화가 길어지면 비용이 제곱으로 는다");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "당신은 한 문장으로만 답합니다.",
    checkpointer: new MemorySaver(),
  });
  const config = { configurable: { thread_id: "cost-demo" } };

  let cumulativeInput = 0;
  for (let i = 1; i <= 5; i++) {
    const result = await agent.invoke(
      { messages: [{ role: "user", content: `${i}번째 질문입니다. 아무 말이나 한 문장 해 주세요.` }] },
      config,
    );
    const last = result.messages.at(-1)!;
    const usage = "usage_metadata" in last ? last.usage_metadata : undefined;
    const input = usage?.input_tokens ?? 0;
    cumulativeInput += input;
    console.log(
      `턴 ${i}: 입력 토큰 ${String(input).padStart(5)} | 누적 입력 ${String(cumulativeInput).padStart(6)}`,
    );
  }
  console.log("\n입력 토큰이 턴마다 늘어납니다 — 매번 전체 히스토리를 다시 보내기 때문입니다.");

  printSection("[10-8] 해법 1: beforeModel 에서 잘라내기");

  // 공식 문서의 트리밍 패턴입니다.
  // RemoveMessage({ id: REMOVE_ALL_MESSAGES }) 로 채널을 비우고, 남길 것만 다시 넣습니다.
  // 이게 "덮어쓰기" 를 하는 유일하게 안전한 방법입니다 (10-5 의 append 함정 회피).
  const trimMiddleware = createMiddleware({
    name: "TrimMessages",
    beforeModel: (state) => {
      const messages = state.messages;
      if (messages.length <= 3) return;

      // noUncheckedIndexedAccess 가 켜져 있으므로 messages[0] 은 undefined 일 수 있습니다.
      // 위에서 length > 3 을 확인했으니 실제로는 항상 존재하지만, 타입상 좁혀 줍니다.
      const firstMsg = messages[0];
      if (firstMsg === undefined) return;

      const recentMessages = messages.length % 2 === 0
        ? messages.slice(-3)
        : messages.slice(-4);

      return {
        messages: [
          new RemoveMessage({ id: REMOVE_ALL_MESSAGES }),
          firstMsg,
          ...recentMessages,
        ],
      };
    },
  });

  const trimmed = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "당신은 한 문장으로만 답합니다.",
    middleware: [trimMiddleware],
    checkpointer: new MemorySaver(),
  });
  const trimCfg = { configurable: { thread_id: "trim-demo" } };

  for (let i = 1; i <= 5; i++) {
    const result = await trimmed.invoke(
      { messages: [{ role: "user", content: `${i}번째 질문입니다. 아무 말이나 한 문장 해 주세요.` }] },
      trimCfg,
    );
    const last = result.messages.at(-1)!;
    const usage = "usage_metadata" in last ? last.usage_metadata : undefined;
    console.log(
      `턴 ${i}: 입력 토큰 ${String(usage?.input_tokens ?? 0).padStart(5)} | ` +
        `저장된 messages ${result.messages.length}`,
    );
  }
  console.log("\n입력 토큰이 더 이상 선형으로 늘지 않습니다. 대신 오래된 맥락은 사라졌습니다.");

  // 트리밍 후 스레드에 실제로 남은 것을 확인합니다.
  const trimState = await trimmed.getState(trimCfg);
  printKV({
    "체크포인트에 남은 messages": (trimState.values.messages as BaseMessage[]).length,
    비고: "미들웨어가 상태 자체를 잘랐습니다 — 체크포인트에도 반영됩니다",
  });

  printSection("[10-8] 해법 2: summarizationMiddleware (Step 11 예고)");

  // import { summarizationMiddleware } from "langchain";
  //
  // const agent = createAgent({
  //   model: MODEL,
  //   tools: [],
  //   middleware: [
  //     summarizationMiddleware({
  //       model: "anthropic:claude-haiku-4-5",  // 요약은 싼 모델로
  //       trigger: { tokens: 4000 },            // 4000 토큰 넘으면 발동
  //       keep: { messages: 20 },               // 최근 20개는 원문 유지
  //     }),
  //   ],
  //   checkpointer: new MemorySaver(),
  // });
  //
  // 그 외 옵션: summaryPrompt, trimTokensToSummarize (기본 4000)
  // 도구 결과만 골라 비우는 contextEditingMiddleware 도 있습니다. Step 11 에서 다룹니다.

  console.log("코드는 주석으로만 두었습니다. Step 11 에서 실제로 돌려봅니다.");
}

/* ===== [10-9] 단기 vs 장기 메모리 ===== */

async function step10_9(): Promise<void> {
  printSection("[10-9] 단기 메모리의 한계 — 스레드를 넘으면 없다");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    checkpointer: new MemorySaver(),
  });

  // 월요일의 대화
  await agent.invoke(
    { messages: [{ role: "user", content: "저는 매운 음식을 못 먹습니다. 기억해 주세요." }] },
    { configurable: { thread_id: "chat-monday" } },
  );

  // 화요일의 새 대화 (새 thread_id)
  const tuesday = await agent.invoke(
    { messages: [{ role: "user", content: "저녁 메뉴 하나만 추천해 주세요. 제 식성 알죠?" }] },
    { configurable: { thread_id: "chat-tuesday" } },
  );

  printMessages(tuesday.messages.at(-1)!);
  console.log(
    "\n월요일에 말한 식성을 모릅니다. 이건 버그가 아니라 단기 메모리의 정의입니다.\n" +
      "스레드를 넘어가는 기억은 장기 메모리(Store)의 일입니다 — Step 15.",
  );

  // MemoryStore 도 @langchain/langgraph 에서 옵니다. 미리보기만.
  //
  // import { MemoryStore } from "@langchain/langgraph";
  // const store = new MemoryStore();
  // → createAgent({ ..., checkpointer, store })
  //   checkpointer = 스레드 안, store = 스레드 밖. 둘은 별개입니다.
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await step10_1();
  await step10_2();
  await step10_3();
  await step10_4();
  await step10_5();
  await step10_6();
  await step10_7();
  await step10_8();
  await step10_9();
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
