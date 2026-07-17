/**
 * Step 13 — Human-in-the-Loop / 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-13-hitl/solution.ts
 *
 * exercise.ts 를 스스로 풀어 본 뒤에 열어 보세요.
 * 각 정답 위 주석에 "왜 그런가" 와 기대 출력이 적혀 있습니다.
 */
import "dotenv/config";

import { createAgent, humanInTheLoopMiddleware, tool } from "langchain";
import {
  Command,
  END,
  MemorySaver,
  START,
  StateGraph,
  interrupt,
  isInterrupted,
} from "@langchain/langgraph";
import * as z from "zod";

/* ===== 공용 도구 ===== */

const deleteLog: string[] = [];

const deleteFile = tool(
  async ({ path }) => {
    deleteLog.push(path);
    return `${path} 를 삭제했습니다.`;
  },
  {
    name: "delete_file",
    description: "파일을 삭제합니다. 되돌릴 수 없습니다.",
    schema: z.object({ path: z.string().describe("삭제할 파일 경로") }),
  },
);

const listFiles = tool(async () => "a.txt, b.txt, /etc/passwd", {
  name: "list_files",
  description: "파일 목록을 봅니다.",
  schema: z.object({}),
});

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [정답 1] interrupt 와 checkpointer ===== */
/*
 * 핵심: interrupt 는 "멈춘 지점"을 어딘가에 적어 둬야 재개할 수 있습니다.
 * 그 저장소가 checkpointer 입니다. 없으면 멈출 수가 없으니 에러입니다.
 *
 * → 에러 클래스 이름: GraphValueError
 * → 에러 메시지 첫 줄: No checkpointer set
 *
 * 이 함정은 다행히 "요란하게" 실패합니다. 조용히 통과했다면 훨씬 위험했을 겁니다.
 * thread_id 를 빼먹으면 다른 에러가 납니다 — 체크포인터는 있는데 어느 대화에
 * 저장할지 모르기 때문입니다. 둘은 항상 짝으로 다닙니다.
 */

console.log("=== [정답 1] checkpointer 없이 interrupt ===");

const Q1State = z.object({ result: z.string().nullable().default(() => null) });

const q1Nodes = (builder: StateGraph<typeof Q1State>) =>
  builder
    .addNode("ask", () => {
      const answer = interrupt("계속할까요?");
      return { result: String(answer) };
    })
    .addEdge(START, "ask")
    .addEdge("ask", END);

// (1) 고장난 버전 — checkpointer 없음
const q1Broken = q1Nodes(new StateGraph(Q1State)).compile();

try {
  await q1Broken.invoke({});
} catch (error) {
  console.log("  에러 클래스:", (error as Error).constructor.name);
  console.log("  메시지 첫 줄:", (error as Error).message.split("\n")[0]);
}

// (2) 고친 버전 — checkpointer 를 붙입니다
const q1Fixed = q1Nodes(new StateGraph(Q1State)).compile({ checkpointer: new MemorySaver() });
const q1Config = { configurable: { thread_id: "q1" } };

const q1Paused = await q1Fixed.invoke({}, q1Config);
// 직접 만든 그래프의 상태 타입에는 __interrupt__ 가 없습니다.
// isInterrupted 타입 가드를 통과해야 tsc 가 읽게 해 줍니다.
console.log("  중단됨:", isInterrupted(q1Paused) ? JSON.stringify(q1Paused.__interrupt__[0]?.value) : "아니오");
const q1Done = await q1Fixed.invoke(new Command({ resume: "네" }), q1Config);
console.log("  재개 결과:", q1Done.result);

/* ===== [정답 2] 부수효과 재실행 ===== */
/*
 * counter 는 2 가 됩니다. 1 이 아닙니다.
 *
 * 이유: interrupt 는 노드를 "일시정지" 시키는 게 아니라 노드 실행을 통째로
 * 중단시킵니다. 재개하면 그 노드가 처음부터 다시 돌고, 이미 답이 있는
 * interrupt 만 저장된 값을 즉시 반환합니다. 그래서 interrupt "앞"의 코드는
 * 항상 두 번(또는 중단 횟수+1번) 실행됩니다.
 *
 * 이게 왜 무서운가: 그 자리에 결제나 메일 발송이 있으면 두 번 나갑니다.
 *
 * 해결책 세 가지:
 *  (a) 부수효과를 멱등(idempotent)하게 만든다 — 같은 키로 두 번 호출해도 한 번만
 *      먹히게. 결제 API 의 idempotency key 가 정확히 이걸 위한 장치입니다.
 *  (b) interrupt 를 부수효과보다 "앞"으로 옮긴다 — 아래에서 택한 방법.
 *  (c) 부수효과를 별도 노드로 분리한다 — 승인 노드와 실행 노드를 나누면
 *      승인 노드만 재실행되고 실행 노드는 한 번만 돕니다.
 */

console.log("\n=== [정답 2] 부수효과 재실행 ===");

let q2Counter = 0;

const Q2State = z.object({ done: z.boolean().default(() => false) });

// (1) 문제 재현
const q2Broken = new StateGraph(Q2State)
  .addNode("work", () => {
    q2Counter += 1;
    const ok = interrupt("승인?");
    return { done: ok === true };
  })
  .addEdge(START, "work")
  .addEdge("work", END)
  .compile({ checkpointer: new MemorySaver() });

const q2Config = { configurable: { thread_id: "q2" } };
await q2Broken.invoke({}, q2Config);
await q2Broken.invoke(new Command({ resume: true }), q2Config);
console.log("  고장난 버전 counter =", q2Counter, "(2 입니다 — 부수효과가 두 번!)");

// (2) 해결책 (b): interrupt 를 맨 앞으로. 승인받기 전엔 아무 일도 하지 않습니다.
let q2FixedCounter = 0;

const q2Fixed = new StateGraph(Q2State)
  .addNode("work", () => {
    const ok = interrupt("승인?"); // ← 부수효과보다 먼저 멈춥니다
    if (ok !== true) return { done: false };
    q2FixedCounter += 1; // ← 재개 후 딱 한 번만 실행됩니다
    return { done: true };
  })
  .addEdge(START, "work")
  .addEdge("work", END)
  .compile({ checkpointer: new MemorySaver() });

const q2FixedConfig = { configurable: { thread_id: "q2-fixed" } };
await q2Fixed.invoke({}, q2FixedConfig);
await q2Fixed.invoke(new Command({ resume: true }), q2FixedConfig);
console.log("  고친 버전 counter =", q2FixedCounter, "(1 입니다)");

/* ===== [정답 3] try/catch 함정 ===== */
/*
 * interrupt 는 GraphInterrupt 예외를 throw 해서 실행을 빠져나갑니다.
 * "예외처럼 생겼지만 에러가 아니라 제어 흐름"입니다.
 * 그래서 넓은 try/catch 는 중단 신호를 삼켜 버리고,
 * 노드는 catch 블록으로 흘러가 아무 일 없었다는 듯 끝납니다.
 * 결과에 __interrupt__ 가 없으니 호출자는 중단을 알아챌 방법이 없습니다.
 *
 * → 잡힌 예외의 name: "GraphInterrupt"
 *
 * 고치는 법: interrupt 는 try 밖에 두는 게 가장 깔끔합니다.
 * 부득이하게 감싸야 하면 name 을 검사해 다시 throw 하세요.
 */

console.log("\n=== [정답 3] try/catch 함정 ===");

const Q3State = z.object({ status: z.string().nullable().default(() => null) });

// (1) 문제 재현
const q3Broken = new StateGraph(Q3State)
  .addNode("risky", () => {
    try {
      const ok = interrupt("승인?");
      return { status: `승인=${String(ok)}` };
    } catch (error) {
      console.log("  잡힌 예외의 name:", (error as Error).name);
      return { status: "에러를 삼켰습니다" };
    }
  })
  .addEdge(START, "risky")
  .addEdge("risky", END)
  .compile({ checkpointer: new MemorySaver() });

const q3BrokenResult = await q3Broken.invoke({}, { configurable: { thread_id: "q3" } });
console.log("  __interrupt__ 있음?", "__interrupt__" in q3BrokenResult, "← 중단이 사라졌습니다");
console.log("  status =", q3BrokenResult.status);

// (2) 고친 버전 — GraphInterrupt 면 다시 throw 합니다.
const q3Fixed = new StateGraph(Q3State)
  .addNode("risky", () => {
    try {
      // 진짜 DB 작업이 여기 있다고 칩시다.
      const ok = interrupt("승인?");
      return { status: `승인=${String(ok)}` };
    } catch (error) {
      // 중단 신호는 에러가 아닙니다. 그대로 통과시켜야 합니다.
      if (error instanceof Error && error.name === "GraphInterrupt") throw error;
      return { status: "DB 에러를 처리했습니다" };
    }
  })
  .addEdge(START, "risky")
  .addEdge("risky", END)
  .compile({ checkpointer: new MemorySaver() });

const q3FixedResult = await q3Fixed.invoke({}, { configurable: { thread_id: "q3-fixed" } });
console.log("  고친 버전 __interrupt__ 있음?", isInterrupted(q3FixedResult), "← 중단이 살아났습니다");

/* ===== [정답 4] interruptOn 으로 위험한 도구만 막기 ===== */
/*
 * 핵심은 "이름을 정확히" 쓰는 것과, 안전한 도구를 굳이 막지 않는 것입니다.
 * list_files 는 interruptOn 에 아예 안 적어도 자동 승인되지만,
 * false 로 명시해 두면 "검토했고 일부러 열어 뒀다"는 의도가 코드에 남습니다.
 */

console.log("\n=== [정답 4] interruptOn ===");

const q4Agent = createAgent({
  model: MODEL,
  tools: [deleteFile, listFiles],
  systemPrompt: "당신은 파일 관리 비서입니다.",
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        delete_file: {
          allowedDecisions: ["approve", "reject"],
          description: "🚨 파일이 영구 삭제됩니다",
        },
        list_files: false,
      },
    }),
  ],
  checkpointer: new MemorySaver(),
});

const q4Config = { configurable: { thread_id: "q4" } };
const q4Result = await q4Agent.invoke(
  { messages: [{ role: "user", content: "a.txt 지워줘." }] },
  q4Config,
);
console.log("  중단됨?", isInterrupted(q4Result));

/* ===== [정답 5] __interrupt__ 읽어서 보여주기 ===== */
/*
 * __interrupt__ 는 배열입니다. 항목마다 { id, value } 를 갖고,
 * HITL 미들웨어가 넣는 value 는 { actionRequests, reviewConfigs } 입니다.
 *
 * 주의: actionRequests 의 인자 필드 이름은 `args` 입니다 (`arguments` 아님).
 * reviewConfigs 는 actionRequests 와 인덱스가 아니라 actionName 으로 짝지어야
 * 정확합니다 — 같은 도구를 두 번 부르면 actionRequests 는 2개인데
 * reviewConfigs 는 1개일 수 있기 때문입니다.
 */

console.log("\n=== [정답 5] __interrupt__ 렌더링 ===");

type ActionRequest = { name: string; args: Record<string, unknown>; description?: string };
type ReviewConfig = { actionName: string; allowedDecisions: string[] };
type HITLValue = { actionRequests: ActionRequest[]; reviewConfigs: ReviewConfig[] };

function renderApproval(result: unknown): void {
  if (!isInterrupted(result)) {
    console.log("  중단 없음");
    return;
  }
  for (const item of result.__interrupt__) {
    const value = item.value as HITLValue;
    for (const action of value.actionRequests) {
      const review = value.reviewConfigs.find((r) => r.actionName === action.name);
      console.log(`  도구      : ${action.name}`);
      console.log(`  인자      : ${JSON.stringify(action.args)}`);
      console.log(`  가능한 결정: ${review?.allowedDecisions.join(" / ") ?? "(알 수 없음)"}`);
    }
  }
}

renderApproval(q4Result);

/* ===== [정답 6] 세 가지 결정 전부 ===== */
/*
 * 기대 결과:
 *  - approve → deleteLog 에 "a.txt"
 *  - edit    → deleteLog 에 "b.txt" (모델이 요청한 a.txt 가 아니라)
 *  - reject  → deleteLog 에 아무것도 안 남고, reject 의 message 가
 *              ToolMessage 내용이 되어 모델에게 전달됨
 *
 * editedAction 은 "통째로 교체"입니다. 부분 병합이 아니므로 args 를 전부 다시
 * 줘야 합니다. 인자가 많으면 { ...action.args, path: "b.txt" } 로 펼쳐 쓰세요.
 *
 * 그리고 allowedDecisions 에 없는 타입을 보내면 에러가 납니다 —
 * edit 를 쓰려면 allowedDecisions 에 "edit" 이 있어야 합니다.
 */

console.log("\n=== [정답 6] approve / edit / reject ===");

const makeQ6Agent = () =>
  createAgent({
    model: MODEL,
    tools: [deleteFile],
    middleware: [
      humanInTheLoopMiddleware({
        interruptOn: { delete_file: { allowedDecisions: ["approve", "edit", "reject"] } },
      }),
    ],
    checkpointer: new MemorySaver(),
  });

deleteLog.length = 0;

// (a) approve
const q6a = makeQ6Agent();
const q6aConfig = { configurable: { thread_id: "q6-approve" } };
await q6a.invoke({ messages: [{ role: "user", content: "a.txt 지워줘." }] }, q6aConfig);
await q6a.invoke(new Command({ resume: { decisions: [{ type: "approve" }] } }), q6aConfig);
console.log("  approve 후 deleteLog =", JSON.stringify(deleteLog));

// (b) edit
const q6b = makeQ6Agent();
const q6bConfig = { configurable: { thread_id: "q6-edit" } };
const q6bPaused = await q6b.invoke({ messages: [{ role: "user", content: "a.txt 지워줘." }] }, q6bConfig);
const q6bAction = (q6bPaused.__interrupt__?.[0]?.value as HITLValue).actionRequests[0];
await q6b.invoke(
  new Command({
    resume: {
      decisions: [
        {
          type: "edit",
          editedAction: {
            name: "delete_file",
            args: { ...q6bAction?.args, path: "b.txt" },
          },
        },
      ],
    },
  }),
  q6bConfig,
);
console.log("  edit 후 deleteLog =", JSON.stringify(deleteLog), "← b.txt 가 추가됐습니다");

// (c) reject
const q6c = makeQ6Agent();
const q6cConfig = { configurable: { thread_id: "q6-reject" } };
await q6c.invoke({ messages: [{ role: "user", content: "a.txt 지워줘." }] }, q6cConfig);
const q6cDone = await q6c.invoke(
  new Command({
    resume: {
      decisions: [{ type: "reject", message: "이 파일은 지우면 안 됩니다. 다시 시도하지 마세요." }],
    },
  }),
  q6cConfig,
);
const q6cToolMsg = q6cDone.messages.filter((m) => m.getType() === "tool").at(-1);
console.log("  reject 가 만든 ToolMessage =", JSON.stringify(q6cToolMsg?.text));
console.log("  reject 후 deleteLog =", JSON.stringify(deleteLog), "← 늘지 않았습니다");

/* ===== [정답 7] 오타 함정 ===== */
/*
 * 1) 중단이 걸리지 않습니다.
 * 2) deleteLog 에 "a.txt" 가 남습니다 — 승인 없이 지워졌습니다.
 * 3) interruptOn 은 Record<string, ...> 이라 아무 문자열 키나 받습니다.
 *    타입 시스템이 "그런 도구 없다"고 말해 주지 않습니다.
 *
 * 방어법: 도구 이름을 상수로 뽑고, tool() 정의와 interruptOn 양쪽에서
 * 그 상수를 쓰세요. 오타를 내면 tsc 가 잡아 줍니다.
 * as const 로 리터럴 타입을 고정하는 게 핵심입니다.
 */

console.log("\n=== [정답 7] 오타 함정 ===");

deleteLog.length = 0;

const q7Agent = createAgent({
  model: MODEL,
  tools: [deleteFile],
  middleware: [humanInTheLoopMiddleware({ interruptOn: { deleteFile: true } })], // 오타!
  checkpointer: new MemorySaver(),
});

const q7Result = await q7Agent.invoke(
  { messages: [{ role: "user", content: "a.txt 지워줘." }] },
  { configurable: { thread_id: "q7" } },
);
console.log("  중단됨?", isInterrupted(q7Result), "← false");
console.log("  deleteLog =", JSON.stringify(deleteLog), "← 승인 없이 지워졌습니다 😱");

// 방어 버전: 이름을 상수로 고정합니다.
const TOOL_NAMES = { deleteFile: "delete_file", listFiles: "list_files" } as const;

const safeDeleteFile = tool(
  async ({ path }) => {
    deleteLog.push(path);
    return `${path} 를 삭제했습니다.`;
  },
  {
    name: TOOL_NAMES.deleteFile, // ← 같은 상수
    description: "파일을 삭제합니다.",
    schema: z.object({ path: z.string() }),
  },
);

const q7Safe = createAgent({
  model: MODEL,
  tools: [safeDeleteFile],
  middleware: [
    humanInTheLoopMiddleware({
      // 오타를 내면 여기서 tsc 가 잡습니다.
      interruptOn: { [TOOL_NAMES.deleteFile]: true },
    }),
  ],
  checkpointer: new MemorySaver(),
});

deleteLog.length = 0;
const q7SafeResult = await q7Safe.invoke(
  { messages: [{ role: "user", content: "a.txt 지워줘." }] },
  { configurable: { thread_id: "q7-safe" } },
);
console.log("  방어 버전 중단됨?", isInterrupted(q7SafeResult), "← true");
console.log("  deleteLog =", JSON.stringify(deleteLog), "← 비어 있습니다");

/* ===== [정답 8] when 으로 조건부 승인 ===== */
/*
 * when 은 도구 호출마다 불리는 술어입니다. true 를 반환하면 중단,
 * false 면 자동 승인입니다. 인자를 보고 판단할 수 있어서
 * "위험한 경로만", "금액이 10만원 넘을 때만" 같은 정책을 표현하기 좋습니다.
 *
 * 주의: when 이 던지는 예외는 승인 자체를 망가뜨립니다.
 * 인자가 예상과 다른 모양일 수 있으니(모델이 만들어 낸 값입니다)
 * 반드시 방어적으로 읽으세요. 아래에서 typeof 로 확인하는 이유입니다.
 *
 * 또 하나: 판단이 애매하면 "중단하는 쪽"이 안전합니다.
 * 아래는 path 를 못 읽으면 true(중단)를 반환합니다 — fail-safe.
 */

console.log("\n=== [정답 8] when 으로 조건부 승인 ===");

const q8Agent = createAgent({
  model: MODEL,
  tools: [deleteFile],
  systemPrompt: "당신은 파일 관리 비서입니다. 사용자가 지우라는 파일을 지우세요.",
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        delete_file: {
          allowedDecisions: ["approve", "reject"],
          description: "🚨 시스템 경로를 지우려 합니다",
          when: (request) => {
            const path = request.toolCall.args.path;
            if (typeof path !== "string") return true; // 모르면 막는다
            return path.startsWith("/etc");
          },
        },
      },
    }),
  ],
  checkpointer: new MemorySaver(),
});

deleteLog.length = 0;

// (a) 안전한 경로 → 중단 없이 실행
const q8Safe = await q8Agent.invoke(
  { messages: [{ role: "user", content: "a.txt 지워줘." }] },
  { configurable: { thread_id: "q8-safe" } },
);
console.log("  a.txt → 중단됨?", isInterrupted(q8Safe), "← false, 바로 실행");

// (b) 위험한 경로 → 중단
const q8Danger = await q8Agent.invoke(
  { messages: [{ role: "user", content: "/etc/passwd 지워줘." }] },
  { configurable: { thread_id: "q8-danger" } },
);
console.log("  /etc/passwd → 중단됨?", isInterrupted(q8Danger), "← true, 승인 필요");
console.log("  deleteLog =", JSON.stringify(deleteLog));
