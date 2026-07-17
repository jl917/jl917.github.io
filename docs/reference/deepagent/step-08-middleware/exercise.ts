/**
 * Step 08 — 미들웨어 조합 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-08-middleware/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보고 여세요.
 *
 * 필요 환경변수: ANTHROPIC_API_KEY
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

/* ===== 공용 준비물 (그대로 쓰세요) ===== */

/**
 * 모델 호출 직전의 도구 목록과 시스템 프롬프트를 훔쳐봅니다.
 * sink 를 넘기면 결과를 바깥으로 빼낼 수 있습니다.
 * ⚠️ 미들웨어 배열의 **맨 뒤**에 두세요. 앞에 두면 다른 미들웨어가
 *    프롬프트를 덧붙이기 전을 보게 됩니다.
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
        console.log(`[${label}] 도구(${toolNames.length}): ${toolNames.join(", ")}`);
        console.log(`[${label}] 프롬프트 길이: ${prompt.length}`);
      }
      return handler(request);
    },
  });
}

/** StateBackend 는 런타임 state 가 필요하므로 팩토리로 넘깁니다. */
const backend = (config: { state: unknown; store?: any }) => new StateBackend(config);

const saveNote = tool(({ text }: { text: string }) => `메모함: ${text}`, {
  name: "save_note",
  description: "메모를 저장합니다.",
  schema: z.object({ text: z.string() }),
});

/* ===== [문제 1] =====
 * createDeepAgent 에 도구를 **하나도 주지 말고**(tools 생략) 인스펙터를 붙여
 * 모델에게 보이는 도구 목록을 출력하세요.
 *
 * 몇 개가 나오나요? 각 도구가 어느 미들웨어에서 온 것인지 주석으로 매핑하세요.
 */
async function ex1() {
  console.log("\n===== [문제 1] =====");
  // 여기에 작성
  // → 매핑:
  //   write_todos                                    ← ?
  //   ls, read_file, write_file, edit_file, glob, grep ← ?
  //   task                                           ← ?
}

/* ===== [문제 2] =====
 * createDeepAgent 에 `name: "read_file"` 인 커스텀 도구를 주고
 * try/catch 로 감싸 실행하세요.
 *
 * 무슨 일이 일어나는지 출력하고, 왜 그런지 주석으로 설명하세요.
 * (에러가 나는 것이 정답입니다 — 어떤 에러가 **어느 시점에** 나는지가 답입니다)
 */
async function ex2() {
  console.log("\n===== [문제 2] =====");
  // 여기에 작성
  // → 왜 그런가:
  // → 에러가 나는 시점은 createDeepAgent 호출 시점인가, invoke 시점인가?
}

/* ===== [문제 3] =====
 * 아래 미들웨어들을 각각 만들어 **.name 을 전부 출력**하세요. (API 호출 없음, 비용 0)
 *
 *   todoListMiddleware()                          (langchain)
 *   createFilesystemMiddleware({ backend })       (deepagents)
 *   createSubAgentMiddleware({ defaultModel })    (deepagents)
 *   createSummarizationMiddleware({ backend })    (deepagents)
 *   createPatchToolCallsMiddleware()              (deepagents)
 *   summarizationMiddleware({ model })            (langchain)
 *
 * 팩토리 이름과 .name 이 다른 것을 찾아 주석에 표로 정리하세요.
 * 특히 **두 패키지의 미들웨어 중 .name 이 같은 쌍**을 찾으세요 — 문제 6 의 전제입니다.
 */
async function ex3() {
  console.log("\n===== [문제 3] =====");
  // 여기에 작성
  // → 팩토리 이름 != .name 인 것:
  // → .name 이 같은 쌍:
}

/* ===== [문제 4] =====
 * createAgent + 미들웨어로 createDeepAgent 와 **도구 집합이 동일한** 에이전트를 조립하세요.
 *
 * 두 도구 집합의 차집합을 **양방향으로** 출력해 [] , [] 가 나오는 것을 보이세요.
 * (힌트: makeInspector 에 sink 를 넘기면 도구 목록을 바깥으로 빼낼 수 있습니다)
 *
 * 프롬프트 길이는 왜 다른가요? 주석으로 설명하세요.
 */
async function ex4() {
  console.log("\n===== [문제 4] =====");
  // 여기에 작성
  // → 프롬프트 길이가 다른 이유:
}

/* ===== [문제 5] =====
 * createFilesystemMiddleware({ backend, tools: ["ls", "glob"] }) 를 시도해 보세요.
 * (read_file 이 빠져 있습니다)
 *
 * 정상 동작하나요? try/catch 로 감싸 결과를 출력하고,
 * 문서상의 제약을 근거로 주석에 설명하세요.
 */
async function ex5() {
  console.log("\n===== [문제 5] =====");
  // 여기에 작성
  // → 왜 read_file 은 필수인가:
}

/* ===== [문제 6] =====
 * createDeepAgent 에 다음 둘을 각각 넣고 도구 목록을 비교하세요.
 *   (a) 새 이름의 커스텀 미들웨어 (예: name: "MyAuditMiddleware")
 *   (b) name: "SummarizationMiddleware" 인 커스텀 미들웨어
 *
 * (b) 에서 **아무 에러도 안 나는 것**을 확인하고,
 * 실제로 무엇이 교체되었는지 주석으로 설명하세요.
 * (힌트: mergeMiddlewareStack 의 병합 키는 무엇인가?)
 */
async function ex6() {
  console.log("\n===== [문제 6] =====");
  // 여기에 작성
  // → (b) 에서 실제로 교체된 것:
  // → 그 대가는 무엇인가:
}

/* ===== [문제 7] =====
 * createAgent 로 서브에이전트를 가진 에이전트를 **두 벌** 만드세요.
 *   (a) createSubAgentMiddleware 에 defaultMiddleware 를 준 것
 *   (b) 안 준 것
 *
 * 서브에이전트에게 "/notes.md 에 저장" 을 시키고 두 결과의
 * state.files 를 비교하세요.
 *
 * ⚠️ 모델의 응답 텍스트가 아니라 **files 목록**을 보세요. 왜일까요?
 */
async function ex7() {
  console.log("\n===== [문제 7] =====");
  // 여기에 작성
  // → 응답 텍스트를 믿으면 안 되는 이유:
}

/* ===== [문제 8] =====
 * (a) usage_metadata 스파이를 붙여 Anthropic 모델로 2턴 대화를 돌리고
 *     2회차에 cache_read 가 0보다 커지는 것을 확인하세요.
 *     (힌트: afterModel 훅에서 state.messages.at(-1).usage_metadata 를 보세요.
 *            필드는 input_token_details.cache_read / cache_creation 입니다)
 *
 * (b) 커스텀 미들웨어의 wrapModelCall 에서
 *     request.systemMessage.concat(`\n현재 시각: ${new Date().toISOString()}`)
 *     로 **매 요청 변하는 값**을 프롬프트에 주입하고, 다시 2턴을 돌려
 *     cache_read 가 어떻게 되는지 관찰하세요.
 *
 * 첫 번째 실행의 숫자를 메모해 두고 비교하세요.
 */
async function ex8() {
  console.log("\n===== [문제 8] =====");
  // 여기에 작성
  // → (b) 에서 cache_read 가 그렇게 되는 이유:
}

async function main() {
  await ex1();
  await ex2();
  await ex3();
  await ex4();
  await ex5();
  await ex6();
  await ex7();
  await ex8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
