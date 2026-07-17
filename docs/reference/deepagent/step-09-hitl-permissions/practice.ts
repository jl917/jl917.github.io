/**
 * Step 09 — HITL과 권한 제어
 * 실행: npx tsx docs/reference/deepagent/step-09-hitl-permissions/practice.ts
 *
 * 본문 9-1 ~ 9-8 의 예제를 순서대로 담았습니다.
 * 검증 버전: deepagents 1.11.0 / langchain 1.5.3 / @langchain/langgraph 1.4.8
 */
import "dotenv/config";
import { createDeepAgent, StateBackend } from "deepagents";
import { Command, MemorySaver } from "@langchain/langgraph";
import { tool } from "langchain";
import type { HITLRequest, HITLResponse, Interrupt } from "langchain";
import * as z from "zod";
import * as readline from "node:readline/promises";

const MODEL = "anthropic:claude-sonnet-4-6";
// OpenAI 대안: const MODEL = "openai:gpt-5.5";

/* ===== [9-1] Deep Agent 는 왜 더 위험한가 ===== */
// 일반 에이전트는 "말"만 하지만 Deep Agent 는 파일을 쓰고 서브에이전트를 스폰합니다.
// 아래는 아무 통제 없이 write_file 을 쓰는 에이전트 — 실행하면 그냥 써버립니다.

async function step9_1() {
  console.log("\n===== [9-1] 통제 없는 Deep Agent =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(), // 가상 FS 라서 실습은 안전합니다
    checkpointer: new MemorySaver(),
    systemPrompt: "너는 파일 관리 도우미다. 요청받은 파일 작업을 수행해라.",
  });

  const result = await agent.invoke(
    { messages: [{ role: "user", content: "/notes/hello.txt 에 '안녕'이라고 써줘." }] },
    { configurable: { thread_id: "9-1" } },
  );

  // 승인 절차가 하나도 없었습니다. __interrupt__ 는 undefined 입니다.
  console.log("__interrupt__:", result.__interrupt__);
  console.log("파일 목록:", Object.keys(result.files ?? {}));
}

/* ===== [9-2] interruptOn — 어떤 도구에 승인을 걸 것인가 ===== */
// interruptOn: Record<string, boolean | InterruptOnConfig>
//   true                 -> 승인 대기 (approve/edit/reject 모두 허용)
//   false                -> 자동 승인 (= 키를 안 적은 것과 같음)
//   InterruptOnConfig    -> 허용 결정을 명시적으로 지정

async function step9_2() {
  console.log("\n===== [9-2] interruptOn 설정 형식 =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(), // ← 없으면 interrupt 가 동작하지 않습니다
    interruptOn: {
      // (a) 불리언 true — 가장 단순한 형태
      write_file: true,

      // (b) InterruptOnConfig — 허용 결정을 좁힌다
      edit_file: {
        allowedDecisions: ["approve", "reject"], // edit 은 뺐다 = 인자 수정 불가
        description: "⚠️ 파일을 수정하려 합니다. 내용을 확인하세요.",
      },

      // (c) description 을 함수로 — 툴 호출 인자를 보고 문구를 만든다
      execute: {
        allowedDecisions: ["approve", "reject"],
        description: (toolCall) =>
          `⛔ 셸 명령 실행 요청\n명령: ${JSON.stringify(toolCall.args, null, 2)}`,
      },

      // (d) 읽기 도구는 자동 승인 — 명시적으로 false
      read_file: false,
      ls: false,
      grep: false,
    },
  });

  console.log("에이전트 생성됨. interruptOn 이 걸린 도구: write_file, edit_file, execute");
  return agent;
}

/* ===== [9-3] 승인 흐름 — interrupt → __interrupt__ → Command resume ===== */

async function step9_3() {
  console.log("\n===== [9-3] 승인 흐름 3단계 =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(), // 필수
    interruptOn: { write_file: true },
  });

  // thread_id 는 재개할 때 같은 값을 써야 합니다. 이게 "재개 커서" 입니다.
  const config = { configurable: { thread_id: "9-3" } };

  // (1) 최초 호출 → interrupt 발생
  const first = await agent.invoke(
    { messages: [{ role: "user", content: "/report.md 에 '분기 매출 요약'이라고 써줘." }] },
    config,
  );

  if (!first.__interrupt__) {
    console.log("interrupt 가 발생하지 않았습니다 (모델이 도구를 안 불렀을 수 있음)");
    return;
  }

  // (2) __interrupt__ 읽기 — value 는 HITLRequest 타입
  const req = first.__interrupt__[0] as Interrupt<HITLRequest>;
  console.log("actionRequests:", JSON.stringify(req.value.actionRequests, null, 2));
  console.log("reviewConfigs:", JSON.stringify(req.value.reviewConfigs, null, 2));

  // (3) Command 로 재개 — decisions 는 actionRequests 와 같은 순서/같은 개수
  const resume: HITLResponse = { decisions: [{ type: "approve" }] };
  const second = await agent.invoke(new Command({ resume }), config);

  console.log("재개 후 파일 목록:", Object.keys(second.files ?? {}));
}

/* ===== [9-4] 승인 액션 — approve / edit / reject ===== */
// langchain 1.5.3 의 DecisionType 은 정확히 세 가지입니다: "approve" | "edit" | "reject"

async function step9_4() {
  console.log("\n===== [9-4] 세 가지 승인 액션 =====");

  const makeAgent = async () =>
    createDeepAgent({
      model: MODEL,
      backend: new StateBackend(),
      checkpointer: new MemorySaver(),
      interruptOn: {
        write_file: { allowedDecisions: ["approve", "edit", "reject"] },
      },
    });

  const prompt = "/draft.txt 에 '초안입니다'라고 써줘.";

  // --- (a) approve: 원래 인자 그대로 실행
  {
    const agent = await makeAgent();
    const config = { configurable: { thread_id: "9-4-approve" } };
    const r1 = await agent.invoke({ messages: [{ role: "user", content: prompt }] }, config);
    if (r1.__interrupt__) {
      const resume: HITLResponse = { decisions: [{ type: "approve" }] };
      const r2 = await agent.invoke(new Command({ resume }), config);
      console.log("[approve] 파일:", Object.keys(r2.files ?? {}));
    }
  }

  // --- (b) edit: 인자를 바꿔서 실행. editedAction 에 name 과 args 를 "전부" 다시 준다.
  {
    const agent = await makeAgent();
    const config = { configurable: { thread_id: "9-4-edit" } };
    const r1 = await agent.invoke({ messages: [{ role: "user", content: prompt }] }, config);
    if (r1.__interrupt__) {
      const req = r1.__interrupt__[0] as Interrupt<HITLRequest>;
      const original = req.value.actionRequests[0];
      if (!original) return;
      const resume: HITLResponse = {
        decisions: [
          {
            type: "edit",
            editedAction: {
              name: original.name, // 도구 이름도 바꿀 수 있지만 보통 그대로 둡니다
              args: { ...original.args, file_path: "/safe/draft.txt" }, // 경로만 교정
            },
          },
        ],
      };
      const r2 = await agent.invoke(new Command({ resume }), config);
      console.log("[edit] 파일:", Object.keys(r2.files ?? {}));
    }
  }

  // --- (c) reject: 실행하지 않고 message 를 모델에게 피드백으로 돌려준다
  {
    const agent = await makeAgent();
    const config = { configurable: { thread_id: "9-4-reject" } };
    const r1 = await agent.invoke({ messages: [{ role: "user", content: prompt }] }, config);
    if (r1.__interrupt__) {
      const resume: HITLResponse = {
        decisions: [
          {
            type: "reject",
            message: "루트 경로에는 파일을 만들 수 없습니다. /workspace/ 아래로만 쓰세요.",
          },
        ],
      };
      const r2 = await agent.invoke(new Command({ resume }), config);
      // reject 는 "거절 후 모델이 다시 시도" 하므로 또 interrupt 가 날 수 있습니다.
      console.log("[reject] 또 interrupt?", Boolean(r2.__interrupt__));
    }
  }
}

/* ===== [9-5] permissions — 백엔드 레벨 정적 접근 제어 ===== */
// FilesystemPermission = { operations: ("read"|"write")[]; paths: string[]; mode?: "allow"|"deny" }
// 규칙은 선언 순서대로 평가되고 first-match-wins. 어디에도 안 걸리면 기본 allow.

async function step9_5() {
  console.log("\n===== [9-5] permissions 정적 규칙 =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    permissions: [
      // 1) /workspace 아래는 읽기·쓰기 허용
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      // 2) /secrets 아래는 읽기조차 금지
      { operations: ["read", "write"], paths: ["/secrets/**"], mode: "deny" },
      // 3) 그 외 전부 금지 (기본이 allow 이므로 이 "마지막 빗장"이 반드시 필요)
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
  });

  const result = await agent.invoke(
    { messages: [{ role: "user", content: "/secrets/keys.env 를 읽어서 내용을 알려줘." }] },
    { configurable: { thread_id: "9-5" } },
  );

  // 도구가 에러 문자열을 ToolMessage 로 돌려주고, 모델은 그걸 보고 사용자에게 설명합니다.
  const last = result.messages.at(-1);
  console.log("모델 최종 답변:", last?.text);
}

/* ===== [9-6] 서브에이전트별 interruptOn / permissions ===== */
// permissions: 생략 = 부모 상속 / 명시 = 부모 규칙을 통째로 교체 / [] = 무제한

async function step9_6() {
  console.log("\n===== [9-6] 서브에이전트별 정책 =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    permissions: [
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
    interruptOn: { write_file: true },
    subagents: [
      {
        name: "auditor",
        description: "코드를 읽고 문제점만 보고한다. 절대 파일을 수정하지 않는다.",
        systemPrompt: "너는 감사자다. 읽기만 하고 발견한 문제를 목록으로 보고해라.",
        // 부모 규칙을 통째로 교체 — 쓰기는 전면 금지, 읽기는 /workspace 만
        permissions: [
          { operations: ["write"], paths: ["/**"], mode: "deny" },
          { operations: ["read"], paths: ["/workspace/**"], mode: "allow" },
          { operations: ["read"], paths: ["/**"], mode: "deny" },
        ],
        // 쓰기가 애초에 불가능하므로 승인 게이트가 필요 없다 → 감사자는 멈추지 않고 끝까지 돈다
        interruptOn: {},
      },
      {
        name: "writer",
        description: "감사 결과를 바탕으로 파일을 실제로 수정한다.",
        systemPrompt: "너는 수정자다. 감사 결과를 반영해 파일을 고쳐라.",
        // permissions 생략 → 부모 규칙 상속
        interruptOn: {
          write_file: { allowedDecisions: ["approve", "edit", "reject"] },
          edit_file: { allowedDecisions: ["approve", "edit", "reject"] },
        },
      },
    ],
  });

  console.log("auditor(읽기 전용, 승인 없음) / writer(부모 권한 상속, 승인 필수) 구성 완료");
  return agent;
}

/* ===== [9-7] 위험 등급별 정책 설계 ===== */
// read = 자유 / write = 승인 / execute = 금지(또는 샌드박스)

async function step9_7() {
  console.log("\n===== [9-7] 위험 등급별 정책 =====");

  const deployTool = tool(
    async ({ env }) => `${env} 에 배포했습니다.`,
    {
      name: "deploy",
      description: "지정한 환경에 애플리케이션을 배포한다.",
      schema: z.object({ env: z.enum(["staging", "production"]) }),
    },
  );

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    tools: [deployTool],

    // 등급 1(read): 정적 규칙으로 범위만 제한, 승인 없음
    permissions: [
      { operations: ["read"], paths: ["/workspace/**"], mode: "allow" },
      { operations: ["write"], paths: ["/workspace/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],

    interruptOn: {
      // 등급 1: 자동 승인
      read_file: false,
      ls: false,
      grep: false,
      glob: false,

      // 등급 2(write): 승인 필요, 인자 수정 허용
      write_file: {
        allowedDecisions: ["approve", "edit", "reject"],
        description: (toolCall) => `📝 파일 쓰기: ${toolCall.args.file_path}`,
      },
      edit_file: { allowedDecisions: ["approve", "edit", "reject"] },

      // 등급 3(execute): 승인 필요 + edit 금지. 게다가 when 으로 production 만 걸러낸다.
      deploy: {
        allowedDecisions: ["approve", "reject"],
        description: (toolCall) => `🚨 PRODUCTION 배포 요청 (env=${toolCall.args.env})`,
        // staging 배포는 자동 승인, production 만 사람을 부른다
        when: (request) => request.toolCall.args.env === "production",
      },
    },
  });

  console.log("read=자유 / write=승인 / deploy=production 만 승인 구성 완료");
  return agent;
}

/* ===== [9-8] 실전: 승인 게이트 달린 파일 편집 에이전트 (CLI) ===== */

/** __interrupt__ 를 사람에게 보여주고 y/n/e 를 받아 HITLResponse 로 바꾼다. */
async function askHuman(req: Interrupt<HITLRequest>): Promise<HITLResponse> {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const decisions: HITLResponse["decisions"] = [];

  try {
    // for...of 로 도는 이유: 인덱스 접근은 tsconfig 의 noUncheckedIndexedAccess 때문에
    // 매번 undefined 체크를 요구합니다. 순서만 지키면 되므로 for...of 가 깔끔합니다.
    for (const action of req.value.actionRequests) {
      // reviewConfigs 는 actionName 으로 찾습니다 (인덱스 대응이 아닙니다)
      const review = req.value.reviewConfigs.find((c) => c.actionName === action.name);
      const allowed = review?.allowedDecisions ?? ["approve", "reject"];

      console.log("\n" + "─".repeat(60));
      console.log(`도구: ${action.name}`);
      if (action.description) console.log(action.description);
      console.log("인자:");
      console.log(JSON.stringify(action.args, null, 2));
      console.log(`허용된 결정: ${allowed.join(" / ")}`);
      console.log("─".repeat(60));

      const answer = (await rl.question("승인? [y=approve / n=reject / e=edit] ")).trim().toLowerCase();

      if (answer === "y" && allowed.includes("approve")) {
        decisions.push({ type: "approve" });
      } else if (answer === "e" && allowed.includes("edit")) {
        const raw = await rl.question("새 args (JSON): ");
        decisions.push({
          type: "edit",
          editedAction: { name: action.name, args: JSON.parse(raw) },
        });
      } else {
        const why = await rl.question("거절 사유 (모델에게 전달됩니다): ");
        decisions.push({ type: "reject", message: why || "사용자가 거절했습니다." });
      }
    }
  } finally {
    rl.close();
  }

  return { decisions };
}

async function step9_8() {
  console.log("\n===== [9-8] 승인 게이트 달린 파일 편집 에이전트 =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    systemPrompt:
      "너는 파일 편집 도우미다. 파일을 쓰기 전에 항상 먼저 읽어서 현재 내용을 확인해라.",
    permissions: [
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
    interruptOn: {
      write_file: { allowedDecisions: ["approve", "edit", "reject"] },
      edit_file: { allowedDecisions: ["approve", "edit", "reject"] },
    },
  });

  const config = { configurable: { thread_id: "9-8" } };

  let result = await agent.invoke(
    {
      messages: [
        {
          role: "user",
          content: "/workspace/README.md 를 만들고 '# 프로젝트' 라는 제목을 넣어줘.",
        },
      ],
    },
    config,
  );

  // interrupt 가 없어질 때까지 승인 루프를 돈다.
  // 한 번의 실행에서 여러 번 interrupt 가 날 수 있습니다 (도구 호출마다).
  while (result.__interrupt__) {
    const req = result.__interrupt__[0] as Interrupt<HITLRequest>;
    const resume = await askHuman(req);
    result = await agent.invoke(new Command({ resume }), config);
  }

  console.log("\n최종 답변:", result.messages.at(-1)?.text);
  console.log("파일 목록:", Object.keys(result.files ?? {}));
}

/* ===== 실행 ===== */
async function main() {
  await step9_1();
  await step9_2();
  await step9_3();
  await step9_4();
  await step9_5();
  await step9_6();
  await step9_7();
  await step9_8(); // ← stdin 입력이 필요합니다
}

main().catch(console.error);
