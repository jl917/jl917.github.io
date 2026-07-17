/**
 * Step 13 — Human-in-the-Loop
 * 실행: npx tsx docs/reference/langchain/step-13-hitl/practice.ts
 *
 * 본문 13-1 ~ 13-8 의 예제를 순서대로 담았습니다.
 * [13-2] 와 [13-6] 의 일부는 모델 없이 도는 그래프 예제라 API 키 없이도 실행됩니다.
 * [13-3] 이후는 실제 모델을 호출하므로 ANTHROPIC_API_KEY 가 필요합니다.
 *
 * 마지막 [13-8] 은 터미널에서 y/n 입력을 받습니다. 파일을 통째로 실행하면
 * 앞 절의 출력이 다 지나간 뒤 프롬프트가 뜹니다.
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
import * as readline from "node:readline/promises";
import * as z from "zod";

import { printSection, printMessages, printJson } from "../project/src/lib/print.js";

/* ===== [13-1] 왜 사람이 끼어들어야 하나 ===== */

// 이 절은 개념 설명이라 실행할 코드가 없습니다.
// 다만 "되돌릴 수 없는 행동"이 무엇인지 코드로 표시해 두면 감이 옵니다.
//
// 아래 도구는 이 파일 전체에서 재사용합니다. 실제로 메일을 보내지는 않고
// 콘솔에 찍기만 하지만, sentLog 에 흔적을 남깁니다.
// "승인 없이 실행되었는가"를 이 배열로 검증할 겁니다.

const sentLog: string[] = [];

const sendEmail = tool(
  async ({ to, subject, body }) => {
    // 실제 서비스라면 여기서 SMTP 로 진짜 메일이 나갑니다. 되돌릴 수 없습니다.
    sentLog.push(to);
    console.log(`   📧 [실제 발송] to=${to} subject=${subject} body=${body.slice(0, 30)}...`);
    return `${to} 에게 메일을 보냈습니다.`;
  },
  {
    name: "send_email",
    description: "지정한 수신자에게 이메일을 발송합니다. 되돌릴 수 없습니다.",
    schema: z.object({
      to: z.string().describe("수신자 이메일 주소"),
      subject: z.string().describe("메일 제목"),
      body: z.string().describe("메일 본문"),
    }),
  },
);

// 읽기 전용 도구 — 되돌릴 수 있으므로 승인이 필요 없습니다.
const searchContact = tool(
  async ({ name }) => {
    const book: Record<string, string> = {
      김민수: "minsu@example.com",
      이지은: "jieun@example.com",
    };
    return book[name] ?? "연락처를 찾지 못했습니다.";
  },
  {
    name: "search_contact",
    description: "이름으로 이메일 주소를 찾습니다.",
    schema: z.object({ name: z.string().describe("찾을 사람 이름") }),
  },
);

/* ===== [13-2] interrupt() 의 동작 원리 ===== */

printSection("[13-2] interrupt() — 실행이 멈추고 체크포인트에 저장된다");

// 모델 없이 순수 그래프로 interrupt 의 동작만 관찰합니다.
// 부수효과가 몇 번 실행되는지 세기 위한 카운터입니다.
let sideEffectCount = 0;

const ApprovalState = z.object({
  amount: z.number(),
  status: z.string().nullable().default(() => null),
});

const approvalGraph = new StateGraph(ApprovalState)
  .addNode("charge", (state) => {
    // ⚠️ interrupt 이전의 코드입니다. 재개할 때 "다시" 실행됩니다.
    sideEffectCount += 1;
    console.log(`   [interrupt 이전 코드 실행] 누적 ${sideEffectCount}회`);

    // 여기서 실행이 멈춥니다. 아래 코드는 지금 시점에 실행되지 않습니다.
    const decision = interrupt({
      question: `${state.amount}원을 결제할까요?`,
      amount: state.amount,
    });

    return { status: decision === true ? "결제완료" : "취소됨" };
  })
  .addEdge(START, "charge")
  .addEdge("charge", END)
  .compile({ checkpointer: new MemorySaver() }); // ← checkpointer 없으면 interrupt 가 에러

const chargeConfig = { configurable: { thread_id: "charge-1" } };

const paused = await approvalGraph.invoke({ amount: 50_000 }, chargeConfig);

console.log("\n   멈춘 뒤 돌아온 결과:");
// isInterrupted 는 타입 가드입니다. 직접 만든 그래프의 상태 타입에는
// __interrupt__ 가 선언돼 있지 않아서, 이 가드를 통과해야 tsc 가 읽게 해 줍니다.
if (isInterrupted(paused)) {
  printJson("   __interrupt__", paused.__interrupt__);
}
console.log("   status =", paused.status, "(아직 노드가 끝나지 않아 null 입니다)");

// 재개. Command 의 resume 값이 곧 interrupt() 의 반환값이 됩니다.
const resumed = await approvalGraph.invoke(new Command({ resume: true }), chargeConfig);
console.log("\n   재개 후 status =", resumed.status);
console.log(`   ⚠️ 부수효과는 총 ${sideEffectCount}회 실행되었습니다 (1회가 아닙니다!)`);

/* ===== [13-2b] checkpointer 없이 interrupt 하면 ===== */

printSection("[13-2b] checkpointer 없이 interrupt 하면 무슨 일이 일어나나");

const noCheckpointerGraph = new StateGraph(ApprovalState)
  .addNode("ask", () => {
    interrupt("승인해 주세요");
    return {};
  })
  .addEdge(START, "ask")
  .addEdge("ask", END)
  .compile(); // ← checkpointer 를 일부러 뺐습니다

try {
  await noCheckpointerGraph.invoke({ amount: 1000 });
  console.log("   여기에 도달하면 안 됩니다.");
} catch (error) {
  // GraphValueError: No checkpointer set
  console.log(`   ✅ 예상대로 에러: ${(error as Error).constructor.name}`);
  console.log(`   메시지: ${(error as Error).message.split("\n")[0]}`);
}

/* ===== [13-2c] interrupt 를 try/catch 로 감싸면 ===== */

printSection("[13-2c] interrupt 를 try/catch 로 감싸면 — 조용히 삼켜진다");

const swallowGraph = new StateGraph(ApprovalState)
  .addNode("ask", () => {
    try {
      const decision = interrupt("승인해 주세요");
      return { status: `승인값=${String(decision)}` };
    } catch (error) {
      // ⚠️ GraphInterrupt 는 "예외처럼" 던져집니다.
      //    여기서 잡으면 중단 신호가 그래프까지 전달되지 않습니다.
      console.log(`   😱 interrupt 를 잡아버렸습니다: ${(error as Error).name}`);
      return { status: "삼켜짐" };
    }
  })
  .addEdge(START, "ask")
  .addEdge("ask", END)
  .compile({ checkpointer: new MemorySaver() });

const swallowed = await swallowGraph.invoke({ amount: 1000 }, { configurable: { thread_id: "sw" } });
console.log("   status =", swallowed.status);
console.log("   __interrupt__ 있음?", "__interrupt__" in swallowed, "← 중단이 사라졌습니다");

/* ===== [13-2d] 노드 안에 interrupt 가 여러 개면 ===== */

printSection("[13-2d] 한 노드에 interrupt 가 두 개 — 순서(index)로 매칭된다");

const TwoState = z.object({ answers: z.array(z.string()).default(() => []) });

const twoGraph = new StateGraph(TwoState)
  .addNode("askTwice", () => {
    const first = interrupt("첫 번째 질문");
    const second = interrupt("두 번째 질문");
    return { answers: [`1=${String(first)}`, `2=${String(second)}`] };
  })
  .addEdge(START, "askTwice")
  .addEdge("askTwice", END)
  .compile({ checkpointer: new MemorySaver() });

const twoConfig = { configurable: { thread_id: "two" } };

const t1 = await twoGraph.invoke({}, twoConfig);
const firstId = isInterrupted(t1) ? t1.__interrupt__[0]?.id : undefined;
console.log("   1차 중단:", isInterrupted(t1) ? JSON.stringify(t1.__interrupt__[0]?.value) : "없음");

const t2 = await twoGraph.invoke(new Command({ resume: "답변A" }), twoConfig);
const secondId = isInterrupted(t2) ? t2.__interrupt__[0]?.id : undefined;
console.log("   2차 중단:", isInterrupted(t2) ? JSON.stringify(t2.__interrupt__[0]?.value) : "없음");
// 두 interrupt 는 id 가 같습니다. 구분되는 건 id 가 아니라 "노드 안에서의 순서"입니다.
console.log("   ⚠️ id 가 1차와 같습니다:", firstId === secondId);

const t3 = await twoGraph.invoke(new Command({ resume: "답변B" }), twoConfig);
console.log("   최종:", JSON.stringify(t3.answers));

/* ===== [13-3] humanInTheLoopMiddleware — 특정 도구만 승인 요구 ===== */

printSection("[13-3] humanInTheLoopMiddleware — interruptOn 으로 도구를 골라 막는다");

const emailAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  // OpenAI 를 쓰려면: model: "openai:gpt-5.5"
  tools: [sendEmail, searchContact],
  systemPrompt: "당신은 사용자를 대신해 메일을 보내는 비서입니다. 연락처를 먼저 찾고 메일을 보내세요.",
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        // 도구 이름은 tool() 에 준 name 과 "정확히" 같아야 합니다.
        send_email: {
          allowedDecisions: ["approve", "edit", "reject"],
          description: "🚨 메일이 실제로 발송됩니다. 수신자와 내용을 확인하세요.",
        },
        // 읽기 전용 도구는 막지 않습니다. false 는 생략해도 같습니다
        // (interruptOn 에 없는 도구는 자동 승인).
        search_contact: false,
      },
      descriptionPrefix: "실행 승인이 필요합니다",
    }),
  ],
  checkpointer: new MemorySaver(), // ← HITL 미들웨어도 checkpointer 가 필수입니다
});

const agentConfig = { configurable: { thread_id: "email-1" } };

const step1 = await emailAgent.invoke(
  { messages: [{ role: "user", content: "김민수에게 '내일 회의 확인' 이라는 제목으로 메일 보내줘." }] },
  agentConfig,
);

console.log("\n   에이전트가 멈췄습니다. 지금까지의 메시지:");
printMessages(step1.messages);
console.log("\n   실제 발송된 메일 수:", sentLog.length, "← 아직 0 이어야 합니다");

/* ===== [13-6] __interrupt__ 읽기 ===== */

printSection("[13-6] __interrupt__ 읽기 — 무엇을 승인해달라는지 꺼내 보기");

printJson("   __interrupt__ 전문", step1.__interrupt__);

// 사람에게 보여줄 형태로 가공하는 함수. 13-8 의 CLI 에서도 씁니다.
type ActionRequest = { name: string; args: Record<string, unknown>; description?: string };
type ReviewConfig = { actionName: string; allowedDecisions: string[] };
type HITLValue = { actionRequests: ActionRequest[]; reviewConfigs: ReviewConfig[] };

function describeInterrupt(result: unknown): void {
  // isInterrupted 는 타입 가드입니다. __interrupt__ 를 직접 뒤지는 것보다 안전합니다.
  if (!isInterrupted(result)) {
    console.log("   중단되지 않았습니다.");
    return;
  }

  for (const item of result.__interrupt__) {
    const value = item.value as HITLValue;
    for (const [i, action] of value.actionRequests.entries()) {
      const review = value.reviewConfigs.find((r) => r.actionName === action.name);
      console.log(`\n   ── 승인 요청 #${i + 1} ─────────────────────`);
      console.log(`   도구      : ${action.name}`);
      console.log(`   인자      : ${JSON.stringify(action.args, null, 2).replace(/\n/g, "\n              ")}`);
      console.log(`   설명      : ${action.description ?? "(없음)"}`);
      console.log(`   가능한 결정: ${review?.allowedDecisions.join(" / ") ?? "?"}`);
    }
  }
}

describeInterrupt(step1);

/* ===== [13-4] 재개 — Command({ resume }) ===== */

printSection("[13-4] Command({ resume }) 로 승인하기");

const approved = await emailAgent.invoke(
  new Command({ resume: { decisions: [{ type: "approve" }] } }),
  agentConfig, // ← 같은 thread_id 여야 합니다. 다르면 재개가 아니라 새 대화입니다.
);

printMessages(approved.messages.slice(-3));
console.log("\n   실제 발송된 메일 수:", sentLog.length, "← 이제 1 입니다");

/* ===== [13-4b] 잘못된 resume 형식 ===== */

printSection("[13-4b] resume 형식이 틀리면 — 조용히 무시되지 않고 에러가 납니다");

const wrongAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail],
  middleware: [humanInTheLoopMiddleware({ interruptOn: { send_email: true } })],
  checkpointer: new MemorySaver(),
});
const wrongConfig = { configurable: { thread_id: "wrong-1" } };

await wrongAgent.invoke(
  { messages: [{ role: "user", content: "test@example.com 으로 '안녕' 메일 보내줘." }] },
  wrongConfig,
);

// 그래프 예제([13-2])에서는 resume: true 가 맞았지만,
// HITL 미들웨어는 { decisions: [...] } 형태만 받습니다.
try {
  await wrongAgent.invoke(new Command({ resume: true }), wrongConfig);
} catch (error) {
  console.log(`   ✅ 에러: ${(error as Error).message}`);
}

// 없는 결정 타입을 주면 어떤 에러가 나는지도 봅시다.
try {
  await wrongAgent.invoke(
    // "accept" 는 존재하지 않습니다. 올바른 이름은 "approve" 입니다.
    new Command({ resume: { decisions: [{ type: "accept" }] } }),
    wrongConfig,
  );
} catch (error) {
  console.log(`   ✅ 에러: ${(error as Error).message.split("\n")[0]}`);
}

/* ===== [13-5] 승인 패턴 — approve / edit / reject ===== */

printSection("[13-5] edit — 사람이 인자를 고쳐서 실행");

const editAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail],
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: { send_email: { allowedDecisions: ["approve", "edit", "reject"] } },
    }),
  ],
  checkpointer: new MemorySaver(),
});

const editConfig = { configurable: { thread_id: "edit-1" } };
const editPaused = await editAgent.invoke(
  { messages: [{ role: "user", content: "wrong@example.com 으로 '초안' 메일 보내줘." }] },
  editConfig,
);

const editAction = (editPaused.__interrupt__?.[0]?.value as HITLValue).actionRequests[0];
console.log("   모델이 요청한 인자:", JSON.stringify(editAction?.args));

const edited = await editAgent.invoke(
  new Command({
    resume: {
      decisions: [
        {
          type: "edit",
          // editedAction 은 "통째로 교체"입니다. 부분 병합이 아니므로
          // 바꾸지 않을 인자도 전부 다시 적어야 합니다.
          editedAction: {
            name: "send_email",
            args: {
              to: "correct@example.com", // ← 사람이 수신자를 고쳤습니다
              subject: editAction?.args.subject ?? "초안",
              body: editAction?.args.body ?? "본문",
            },
          },
        },
      ],
    },
  }),
  editConfig,
);
printMessages(edited.messages.slice(-2));
console.log("   발송 로그:", sentLog);

printSection("[13-5b] reject — 거부하고 모델에게 이유를 알려주기");

const rejectAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail],
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: { send_email: { allowedDecisions: ["approve", "reject"] } },
    }),
  ],
  checkpointer: new MemorySaver(),
});

const rejectConfig = { configurable: { thread_id: "reject-1" } };
await rejectAgent.invoke(
  { messages: [{ role: "user", content: "boss@example.com 으로 '퇴사합니다' 메일 보내줘." }] },
  rejectConfig,
);

const rejected = await rejectAgent.invoke(
  new Command({
    resume: {
      decisions: [
        {
          type: "reject",
          // 이 message 가 ToolMessage 내용이 되어 모델에게 전달됩니다.
          message: "발송을 거부했습니다. 이 메일은 보내면 안 됩니다. 다시 시도하지 마세요.",
        },
      ],
    },
  }),
  rejectConfig,
);
printMessages(rejected.messages.slice(-2));

/* ===== [13-3b] 함정: interruptOn 의 도구 이름 오타 ===== */

printSection("[13-3b] ⚠️ interruptOn 의 도구 이름을 틀리면 — 조용히 실행된다");

const typoAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail],
  middleware: [
    humanInTheLoopMiddleware({
      // 실제 도구 이름은 "send_email" 인데 "sendEmail" 이라고 적었습니다.
      // 에러도, 경고도, 중단도 없습니다. 메일이 그냥 나갑니다.
      interruptOn: { sendEmail: true },
    }),
  ],
  checkpointer: new MemorySaver(),
});

const before = sentLog.length;
const typoResult = await typoAgent.invoke(
  { messages: [{ role: "user", content: "typo@example.com 으로 '테스트' 메일 보내줘." }] },
  { configurable: { thread_id: "typo-1" } },
);

console.log("   중단됐나요?", isInterrupted(typoResult), "← false 입니다");
console.log(`   승인 없이 발송된 메일: ${sentLog.length - before}건 😱`);

/* ===== [13-7] 비동기 승인 — 프로세스가 죽어도 되는 이유 ===== */

printSection("[13-7] 비동기 승인 — 체크포인터가 상태를 들고 있다");

// MemorySaver 는 프로세스 메모리에만 있으므로 "같은 프로세스 안에서 나중에"만 됩니다.
// 아래는 3초 뒤에 승인이 오는 상황을 흉내 냅니다.
const asyncAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail],
  middleware: [humanInTheLoopMiddleware({ interruptOn: { send_email: true } })],
  checkpointer: new MemorySaver(),
});

const asyncConfig = { configurable: { thread_id: "async-1" } };
await asyncAgent.invoke(
  { messages: [{ role: "user", content: "later@example.com 으로 '나중에' 메일 보내줘." }] },
  asyncConfig,
);
console.log("   승인 요청을 큐에 넣었습니다. 에이전트 객체는 이제 아무것도 붙들고 있지 않습니다.");

// 이 사이에 서버가 재시작돼도, 체크포인터가 영속(Postgres/SQLite)이라면 재개됩니다.
await new Promise((resolve) => setTimeout(resolve, 3000));
console.log("   ...3초 후, 승인이 도착했습니다.");

const late = await asyncAgent.invoke(
  new Command({ resume: { decisions: [{ type: "approve" }] } }),
  asyncConfig, // thread_id 만 있으면 재개됩니다
);
printMessages(late.messages.slice(-2));

/* ===== [13-8] 실전 — 승인 게이트가 달린 CLI 이메일 에이전트 ===== */

printSection("[13-8] 실전 — CLI 로 y/n 승인 받기");

const cliAgent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [sendEmail, searchContact],
  systemPrompt: "당신은 메일 비서입니다. 연락처를 찾은 뒤 메일을 발송하세요.",
  middleware: [
    humanInTheLoopMiddleware({
      interruptOn: {
        send_email: {
          allowedDecisions: ["approve", "edit", "reject"],
          description: "메일이 실제로 발송됩니다.",
        },
      },
    }),
  ],
  checkpointer: new MemorySaver(),
});

const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
const cliConfig = { configurable: { thread_id: "cli-1" } };

// 첫 입력은 사용자 메시지, 이후부터는 Command 를 넣습니다.
// 타입이 갈리므로 유니온으로 받습니다.
let next: { messages: Array<{ role: string; content: string }> } | Command = {
  messages: [{ role: "user", content: "이지은에게 '점심 같이 먹어요' 라는 제목으로 메일 보내줘." }],
};

// 중단이 여러 번 날 수 있으므로 루프를 돕니다.
// (도구를 두 번 부르면 승인도 두 번 필요합니다.)
while (true) {
  const result = await cliAgent.invoke(next, cliConfig);

  if (!isInterrupted(result)) {
    console.log("\n   ── 최종 답변 ──");
    printMessages(result.messages.slice(-1));
    break;
  }

  const value = result.__interrupt__[0]?.value as HITLValue;
  const action = value.actionRequests[0];

  console.log("\n   ┌─ 승인 요청 ─────────────────────────");
  console.log(`   │ 도구: ${action?.name}`);
  console.log(`   │ 인자: ${JSON.stringify(action?.args)}`);
  console.log("   └─────────────────────────────────────");

  const answer = (await rl.question("   승인하시겠습니까? [y=승인 / n=거부 / e=수정]: ")).trim().toLowerCase();

  if (answer === "y") {
    next = new Command({ resume: { decisions: [{ type: "approve" }] } });
  } else if (answer === "e") {
    const newTo = await rl.question("   새 수신자: ");
    next = new Command({
      resume: {
        decisions: [
          {
            type: "edit",
            editedAction: {
              name: action?.name ?? "send_email",
              // 기존 인자를 펼치고 바꿀 것만 덮어씁니다.
              args: { ...action?.args, to: newTo },
            },
          },
        ],
      },
    });
  } else {
    const reason = await rl.question("   거부 이유: ");
    next = new Command({
      resume: { decisions: [{ type: "reject", message: reason || "사용자가 거부했습니다." }] },
    });
  }
}

rl.close();

console.log("\n최종 발송 로그:", sentLog);
