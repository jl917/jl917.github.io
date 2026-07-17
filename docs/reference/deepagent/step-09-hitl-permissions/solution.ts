/**
 * Step 09 — HITL과 권한 제어 · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-09-hitl-permissions/solution.ts
 *
 * exercise.ts 를 먼저 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";
import { createDeepAgent, StateBackend } from "deepagents";
import { Command, MemorySaver } from "@langchain/langgraph";
import { tool } from "langchain";
import type { HITLRequest, HITLResponse, Interrupt } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [정답 1] 승인 게이트 켜기 =====
 * 핵심은 checkpointer 입니다. interruptOn 만 주고 checkpointer 를 빼면
 * 에이전트는 "멈추지 않고 그냥 실행" 합니다. 에러도 안 납니다 — 이게 이 스텝 최대의 함정입니다.
 * interrupt 는 상태를 저장했다가 나중에 되살리는 메커니즘인데,
 * 저장할 곳(checkpointer)이 없으면 되살릴 수도 없기 때문입니다.
 *
 * read_file: false 는 사실 생략해도 같습니다 — interruptOn 에 없는 키는 자동 승인이 기본입니다.
 * 그래도 "읽기는 일부러 자유롭게 뒀다"는 의도를 코드로 남기려고 명시하는 편이 좋습니다.
 */
function makeAgent1() {
  return createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(), // ← 이게 없으면 interrupt 는 무동작
    interruptOn: {
      write_file: true, // true = approve/edit/reject 모두 허용하며 멈춤
      read_file: false, // false = 자동 승인 (명시적 의도 표현)
      ls: false,
    },
  });
}

async function sol1() {
  console.log("\n===== [정답 1] =====");
  const agent = await makeAgent1();
  const result = await agent.invoke(
    { messages: [{ role: "user", content: "/notes/todo.md 에 '할 일'이라고 써줘." }] },
    { configurable: { thread_id: "sol-1" } },
  );
  console.log("interrupt 발생?", Boolean(result.__interrupt__));
}

/* ===== [정답 2] __interrupt__ 읽기 =====
 * __interrupt__ 는 배열입니다. HITL 미들웨어는 한 턴의 승인 요청을
 * "하나의 interrupt" 로 묶으므로 보통 [0] 만 보면 됩니다.
 * 대신 그 안의 actionRequests 가 배열이라 도구 호출이 여러 개일 수 있습니다.
 *
 * 함정: reviewConfigs 를 actionRequests 와 같은 인덱스로 짝지으면 안 됩니다.
 * reviewConfigs 는 "도구별" 정책이라 같은 도구를 두 번 부르면 항목이 하나뿐일 수 있습니다.
 * 반드시 actionName 으로 찾으세요.
 */
async function sol2() {
  console.log("\n===== [정답 2] =====");
  const agent = await makeAgent1();
  const result = await agent.invoke(
    { messages: [{ role: "user", content: "/notes/todo.md 에 '할 일'이라고 써줘." }] },
    { configurable: { thread_id: "sol-2" } },
  );

  if (!result.__interrupt__) {
    console.log("모델이 도구를 부르지 않았습니다.");
    return;
  }

  const req = result.__interrupt__[0] as Interrupt<HITLRequest>;
  for (const action of req.value.actionRequests) {
    console.log("도구 이름:", action.name);
    console.log("인자:", JSON.stringify(action.args, null, 2));
    // 인덱스가 아니라 actionName 으로 찾는 것이 포인트
    const review = req.value.reviewConfigs.find((c) => c.actionName === action.name);
    console.log("허용된 결정:", review?.allowedDecisions);
  }
}

/* ===== [정답 3] edit 결정으로 경로 교정 =====
 * editedAction 은 원본을 "부분 수정" 하는 게 아니라 "통째로 교체" 합니다.
 * args 에 file_path 만 넣으면 content 가 사라져서 도구 호출이 실패합니다.
 * 그래서 원본 args 를 스프레드한 뒤 바꿀 키만 덮어써야 합니다.
 *
 * name 도 바꿀 수 있지만(다른 도구로 갈아끼우기), 보통은 원본 이름을 그대로 씁니다.
 */
async function sol3() {
  console.log("\n===== [정답 3] =====");
  const agent = await makeAgent1();
  const config = { configurable: { thread_id: "sol-3" } };

  const r1 = await agent.invoke(
    { messages: [{ role: "user", content: "/notes/todo.md 에 '할 일'이라고 써줘." }] },
    config,
  );
  if (!r1.__interrupt__) return;

  const req = r1.__interrupt__[0] as Interrupt<HITLRequest>;
  const original = req.value.actionRequests[0];
  if (!original) return;

  const resume: HITLResponse = {
    decisions: [
      {
        type: "edit",
        editedAction: {
          name: original.name,
          // ...original.args 가 핵심. content 를 잃지 않으려면 반드시 펼쳐야 합니다.
          args: { ...original.args, file_path: "/workspace/todo.md" },
        },
      },
    ],
  };

  const r2 = await agent.invoke(new Command({ resume }), config);
  // /notes/todo.md 가 아니라 /workspace/todo.md 가 만들어졌는지 확인
  console.log("파일 목록:", Object.keys(r2.files ?? {}));
}

/* ===== [정답 4] reject 와 피드백 =====
 * reject 는 "도구를 실행하지 않고, message 를 도구 결과인 것처럼 모델에게 돌려주는 것" 입니다.
 * 모델 입장에서는 도구가 실패 메시지를 반환한 것처럼 보이므로, 그걸 읽고 다음 행동을 정합니다.
 *
 * 함정: reject 했다고 대화가 끝나지 않습니다. 모델은 다른 경로로 재시도할 수 있고,
 * 그러면 __interrupt__ 가 또 발생합니다. 그래서 실전에서는 정답 8처럼 루프가 필요합니다.
 * message 를 비워두면 모델이 왜 거절당했는지 몰라서 같은 시도를 반복합니다 — 반드시 이유를 적으세요.
 */
async function sol4() {
  console.log("\n===== [정답 4] =====");
  const agent = await makeAgent1();
  const config = { configurable: { thread_id: "sol-4" } };

  const r1 = await agent.invoke(
    { messages: [{ role: "user", content: "/notes/todo.md 에 '할 일'이라고 써줘." }] },
    config,
  );
  if (!r1.__interrupt__) return;

  const resume: HITLResponse = {
    decisions: [
      {
        type: "reject",
        message: "쓰기는 금지입니다. 대신 내용을 채팅으로 알려주세요.",
      },
    ],
  };

  const r2 = await agent.invoke(new Command({ resume }), config);
  console.log("모델 응답:", r2.messages.at(-1)?.text);
  console.log("또 interrupt?", Boolean(r2.__interrupt__)); // 재시도하면 true 일 수 있습니다
}

/* ===== [정답 5] permissions 로 샌드박싱 =====
 * 순서가 전부입니다. first-match-wins 이므로 좁은 규칙을 먼저, 넓은 빗장을 마지막에 둡니다.
 *
 * /config 는 "읽기 허용 + 쓰기 금지" 인데, 쓰기 금지 규칙을 따로 쓰지 않아도
 * 마지막 빗장 규칙이 잡아줍니다. 하지만 의도를 명확히 하려고 명시적으로 적었습니다.
 *
 * 마지막 { paths: ["/**"], mode: "deny" } 를 빼면 어떤 규칙에도 안 걸리는 경로가
 * 기본값 allow 로 통과합니다. "허용 목록만 적었으니 나머지는 막히겠지" 가 아닙니다 —
 * deny 기본이 아니라 allow 기본입니다. 이 빗장이 이 문제의 핵심입니다.
 */
async function sol5() {
  console.log("\n===== [정답 5] =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    permissions: [
      // 1) 가장 구체적인 허용부터
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      // 2) /config 는 읽기만 — 쓰기 금지를 읽기 허용보다 먼저 둬야 합니다.
      //    순서를 바꾸면 read 규칙이 먼저 매칭되지만 operations 가 달라 write 는 안 걸리고,
      //    결국 아래 빗장에 걸립니다. 그래도 의도를 코드에 남기는 편이 안전합니다.
      { operations: ["write"], paths: ["/config/**"], mode: "deny" },
      { operations: ["read"], paths: ["/config/**"], mode: "allow" },
      // 3) 마지막 빗장 — 이게 없으면 나머지 전부 allow 입니다
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
  });

  const result = await agent.invoke(
    { messages: [{ role: "user", content: "/config/app.json 에 설정을 써줘." }] },
    { configurable: { thread_id: "sol-5" } },
  );
  console.log("모델 응답:", result.messages.at(-1)?.text);
}

/* ===== [정답 6] when 으로 조건부 승인 =====
 * when 은 "멈출지 말지" 를 결정하는 술어입니다. true = 멈춘다, false = 자동 승인.
 * 헷갈리기 쉬우니 주의: when 은 "허용 조건" 이 아니라 "개입 조건" 입니다.
 *
 * when 이 유용한 이유는 승인 피로(approval fatigue) 때문입니다.
 * 1000원 송금까지 전부 사람을 부르면, 사람은 곧 내용을 안 읽고 y 만 누르게 됩니다.
 * 그러면 승인 게이트는 있으나 마나입니다. 정말 위험한 것만 골라서 물어야 합니다.
 *
 * 주의: when 안에서는 request.tool 이 undefined 입니다(afterModel 단계라서).
 * request.toolCall.args 만 쓰세요.
 */
const transferMoney = tool(
  async ({ to, amount }) => `${to} 에게 ${amount}원을 송금했습니다.`,
  {
    name: "transfer_money",
    description: "지정한 상대에게 돈을 송금한다.",
    schema: z.object({ to: z.string(), amount: z.number() }),
  },
);

async function sol6() {
  console.log("\n===== [정답 6] =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    tools: [transferMoney],
    interruptOn: {
      transfer_money: {
        allowedDecisions: ["approve", "reject"],
        description: (toolCall) =>
          `🚨 고액 송금: ${toolCall.args.to} 에게 ${toolCall.args.amount}원`,
        // true 를 반환하면 멈춘다. 10만원 초과만 사람을 부른다.
        when: (request) => Number(request.toolCall.args.amount ?? 0) > 100000,
      },
    },
  });

  const config = { configurable: { thread_id: "sol-6" } };

  // 5만원 — 자동 승인되어 interrupt 없이 끝납니다
  const small = await agent.invoke(
    { messages: [{ role: "user", content: "김철수에게 50000원 송금해줘." }] },
    config,
  );
  console.log("5만원 → interrupt?", Boolean(small.__interrupt__)); // false 기대

  // 100만원 — 멈춥니다
  const big = await agent.invoke(
    { messages: [{ role: "user", content: "이영희에게 1000000원 송금해줘." }] },
    { configurable: { thread_id: "sol-6-big" } },
  );
  console.log("100만원 → interrupt?", Boolean(big.__interrupt__)); // true 기대
}

/* ===== [정답 7] 읽기 전용 서브에이전트 =====
 * permissions 를 명시하면 부모 규칙을 "통째로 교체" 합니다. 병합이 아닙니다.
 * 그래서 reviewer 의 규칙만으로 완결적이어야 하고, 마지막 빗장도 직접 넣어야 합니다.
 *
 * 함정: permissions: [] 는 "아무 권한 없음" 이 아니라 "무제한" 입니다.
 * 빈 배열이니 어떤 규칙에도 안 걸리고 → 기본 allow → 전부 통과.
 * 잠그려는 의도로 [] 를 쓰면 정확히 반대 결과가 나옵니다.
 *
 * 또 하나: write 금지 규칙을 read 허용보다 먼저 둡니다. first-match-wins 이므로
 * 순서가 뒤집히면 의도가 흐려집니다.
 */
async function sol7() {
  console.log("\n===== [정답 7] =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    // 부모: /workspace 읽기·쓰기 허용
    permissions: [
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
    subagents: [
      {
        name: "reviewer",
        description: "코드를 읽고 리뷰 의견만 제시한다. 파일을 절대 수정하지 않는다.",
        systemPrompt: "너는 코드 리뷰어다. 읽기만 하고 개선점을 목록으로 보고해라.",
        // 부모 규칙을 통째로 교체 — 이 배열만으로 완결적이어야 합니다
        permissions: [
          { operations: ["write"], paths: ["/**"], mode: "deny" }, // 쓰기 전면 금지
          { operations: ["read"], paths: ["/workspace/**"], mode: "allow" },
          { operations: ["read"], paths: ["/**"], mode: "deny" }, // 빗장
        ],
      },
    ],
  });

  console.log("reviewer: 쓰기 전면 금지 / /workspace 읽기만 허용");
  return agent;
}

/* ===== [정답 8] 승인 루프 =====
 * 실전 코드의 형태입니다. 포인트 세 가지:
 *
 * 1) while 로 도는 이유 — 한 번 재개했다고 끝이 아닙니다. 모델은 다음 턴에 또 도구를
 *    부를 수 있고, 그러면 __interrupt__ 가 또 옵니다. if 로 한 번만 처리하면
 *    두 번째 승인 요청을 놓치고 "왜 파일이 안 만들어지지?" 로 헤매게 됩니다.
 *
 * 2) 카운터 — 모델이 reject 를 받고 계속 재시도하면 무한 루프가 됩니다.
 *    사람이 붙어 있는 CLI 면 사람이 지쳐서 멈추지만, 자동화된 승인자라면 영원히 돕니다.
 *    상한을 두세요.
 *
 * 3) decisions 길이 — actionRequests 와 개수가 같아야 하고 순서도 같아야 합니다.
 *    map 으로 만드는 게 가장 안전합니다. 하나라도 빠지면 매칭이 어긋납니다.
 */
async function sol8() {
  console.log("\n===== [정답 8] =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    interruptOn: { write_file: true, edit_file: true },
  });

  const config = { configurable: { thread_id: "sol-8" } };

  let result = await agent.invoke(
    {
      messages: [
        { role: "user", content: "/workspace/a.txt 와 /workspace/b.txt 를 각각 만들어줘." },
      ],
    },
    config,
  );

  const MAX_RESUMES = 5;
  let resumes = 0;

  while (result.__interrupt__) {
    if (resumes >= MAX_RESUMES) {
      console.warn(`⚠️ 재개 ${MAX_RESUMES}회 초과 — 루프를 강제 종료합니다.`);
      break;
    }

    const req = result.__interrupt__[0] as Interrupt<HITLRequest>;
    // 개수와 순서를 맞추는 가장 안전한 방법: actionRequests 를 map 한다
    const resume: HITLResponse = {
      decisions: req.value.actionRequests.map(() => ({ type: "approve" as const })),
    };

    result = await agent.invoke(new Command({ resume }), config);
    resumes++;
  }

  console.log(`재개 횟수: ${resumes}`);
  console.log("파일 목록:", Object.keys(result.files ?? {}));
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

main().catch(console.error);
