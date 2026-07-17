/**
 * Step 05 — 백엔드와 권한 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-05-backends/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 채우세요. 전부 LLM 호출 없이 검증 가능한 문제입니다.
 * (문제 7 만 선택적으로 모델을 호출합니다.)
 *
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 */
import "dotenv/config";
import {
  createDeepAgent,
  StateBackend,
  StoreBackend,
  CompositeBackend,
  type BackendProtocolV2,
  type LsResult,
  type ReadResult,
  type ReadRawResult,
  type WriteResult,
  type EditResult,
  type GlobResult,
  type GrepResult,
  type FilesystemPermission,
} from "deepagents";
import { FilesystemBackend, LocalShellBackend } from "deepagents/node";
import { InMemoryStore } from "@langchain/langgraph";
import * as path from "node:path";
import * as os from "node:os";
import * as fs from "node:fs/promises";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [문제 1] 백엔드 고르기 ===== */
// 아래 4가지 상황 각각에 어떤 백엔드를 쓸지 고르고, 왜인지 한 줄 주석으로 적으세요.
// 보기: StateBackend / FilesystemBackend / StoreBackend / CompositeBackend / LocalShellBackend
//
//  (a) 브라우저에서 도는 데모. 새로고침하면 다 날아가도 됨.
//      → 답:
//  (b) 사용자의 코드 저장소를 리팩터링하는 CLI 도구.
//      → 답:
//  (c) 대화가 끝나도 사용자 선호("답변은 한국어로")를 기억해야 하는 챗봇.
//      → 답:
//  (d) (c) 를 하면서 동시에 작업 파일은 디스크에 두고 싶다.
//      → 답:

/* ===== [문제 2] virtualMode 로 탈출을 막아라 ===== */
// 임시 디렉터리를 rootDir 로 하는 FilesystemBackend 를 virtualMode: true 로 만들고,
// 아래 3가지 탈출 시도가 전부 막히는지(= 파일 내용이 안 읽히는지) 확인하세요.
//   "/../../etc/passwd"  /  "/etc/hosts"  /  "/~/.ssh/id_rsa"
// 힌트: read() 는 throw 하지 않고 { error } 를 돌려줍니다.
async function exercise2() {
  console.log("\n=== [문제 2] virtualMode 탈출 시도 ===");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "ex5-2-"));
  await fs.writeFile(path.join(dir, "ok.txt"), "안에 있는 파일");

  // TODO: 여기에 작성
}

/* ===== [문제 3] StoreBackend 로 사용자별 칸막이 ===== */
// InMemoryStore 하나를 공유하되, namespace 를 달리한 StoreBackend 2개를 만드세요.
// user-a 가 쓴 파일이 user-b 에게 안 보이는 것을 확인하세요.
async function exercise3() {
  console.log("\n=== [문제 3] namespace 격리 ===");
  const store = new InMemoryStore();

  // TODO: 여기에 작성
}

/* ===== [문제 4] CompositeBackend 조립 ===== */
// 다음 라우팅을 가진 CompositeBackend 를 만들고, 각 경로에 파일을 하나씩 쓴 뒤
// 정말 의도한 곳에 저장됐는지 확인하세요.
//   "/memories/" → StoreBackend (영속)
//   "/workspace/" → FilesystemBackend (실제 디스크)
//   그 외        → StateBackend 는 런타임이 필요하니, 여기선 StoreBackend 로 대체
// 확인 포인트: /workspace/ 에 쓴 파일이 실제 디스크에 나타나야 합니다 (fs.readdir 로 검증).
async function exercise4() {
  console.log("\n=== [문제 4] CompositeBackend 라우팅 ===");
  const store = new InMemoryStore();
  const workspaceDir = await fs.mkdtemp(path.join(os.tmpdir(), "ex5-4-"));

  // TODO: 여기에 작성
}

/* ===== [문제 5] 권한 규칙의 순서 ===== */
// 아래 두 규칙 배열은 "같은 규칙, 다른 순서" 입니다.
// checkWrite() 헬퍼를 완성해서, /workspace/.env 에 대한 write 결정이
// 두 배열에서 각각 어떻게 나오는지 출력하세요.
// (첫 매칭 규칙이 이기고, 아무것도 안 걸리면 "allow" 입니다.)
async function exercise5() {
  console.log("\n=== [문제 5] 규칙 순서 ===");

  const good: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
  ];
  const bad: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
  ];

  // TODO: checkWrite(rules, path) 를 만들어 good / bad 각각에 대해 "/workspace/.env" 를 평가하세요.
  //       glob 매칭은 아래 simpleGlob 을 쓰면 됩니다.
}

/**
 * 본문 설명용 아주 단순화한 glob 매처 (실제 구현과 다릅니다 — 감을 잡기 위한 것).
 * "**" 로 먼저 쪼개고, 각 조각 안에서 "*" 로 다시 쪼갠 뒤 이어붙입니다.
 *   "**" -> ".*"      (슬래시를 넘어 임의 깊이)
 *   "*"  -> "[^/]*"   (한 세그먼트 안에서만)
 */
function simpleGlob(p: string, pattern: string): boolean {
  const escape = (s: string) => s.replace(/[.*+^${}()|[\]\\]/g, "\\$&");
  const source = pattern
    .split("**")
    .map((chunk) => chunk.split("*").map(escape).join("[^/]*"))
    .join(".*");
  return new RegExp(`^${source}$`).test(p);
}

/* ===== [문제 6] 셸은 virtualMode 를 지키지 않는다 ===== */
// LocalShellBackend 를 rootDir=임시디렉터리, virtualMode: true 로 만드세요.
// 그리고 아래 두 가지가 "서로 다른 결과" 를 낸다는 걸 직접 확인하세요.
//   (a) 파일 도구로 rootDir 밖 읽기:  await shell.read("/etc/hosts")
//   (b) 셸로 rootDir 밖 읽기:        await shell.execute("cat /etc/hosts | head -2")
// 확인 후, 왜 이런 차이가 나는지 한 줄 주석으로 적으세요.
// 반드시 마지막에 await shell.close() 를 호출하세요.
async function exercise6() {
  console.log("\n=== [문제 6] execute 는 rootDir 를 안 지킨다 ===");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "ex5-6-"));

  // TODO: 여기에 작성
  // → 왜 다른가요? (여기에 설명)
}

/* ===== [문제 7] 커스텀 백엔드 — 읽기 전용 래퍼 ===== */
// 다른 백엔드를 감싸서 "쓰기만 막는" ReadOnlyBackend 를 만드세요.
//   - ls / read / readRaw / glob / grep → 감싼 백엔드에 그대로 위임
//   - write / edit → 실제로 쓰지 말고 { error: "..." } 를 반환
// 힌트: throw 하지 말고 error 필드로 돌려줘야 모델이 읽고 대처합니다.
// 완성했다면 아래를 확인하세요:
//   - read 는 되고, write 는 error 가 나온다
//   - createDeepAgent({ backend: readOnly }) 가 정상 생성된다
//
// 보너스: 이걸 permissions 로도 똑같이 할 수 있습니다. 어느 쪽이 나을까요? 주석으로 적으세요.
class ReadOnlyBackend implements BackendProtocolV2 {
  constructor(private inner: BackendProtocolV2) {}

  // TODO: 여기에 작성
  ls(path: string): Promise<LsResult> | LsResult {
    throw new Error("구현하세요");
  }
  read(filePath: string, offset?: number, limit?: number): Promise<ReadResult> | ReadResult {
    throw new Error("구현하세요");
  }
  readRaw(filePath: string): Promise<ReadRawResult> | ReadRawResult {
    throw new Error("구현하세요");
  }
  write(filePath: string, content: string): Promise<WriteResult> | WriteResult {
    throw new Error("구현하세요");
  }
  edit(
    filePath: string,
    oldString: string,
    newString: string,
    replaceAll?: boolean,
  ): Promise<EditResult> | EditResult {
    throw new Error("구현하세요");
  }
  glob(pattern: string, path?: string): Promise<GlobResult> | GlobResult {
    throw new Error("구현하세요");
  }
  grep(pattern: string, path?: string | null, glob?: string | null): Promise<GrepResult> | GrepResult {
    throw new Error("구현하세요");
  }
}

async function exercise7() {
  console.log("\n=== [문제 7] ReadOnlyBackend ===");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "ex5-7-"));
  await fs.writeFile(path.join(dir, "data.txt"), "읽기만 됩니다");

  // TODO: 여기에 작성
}

async function main() {
  await exercise2();
  await exercise3();
  await exercise4();
  await exercise5();
  await exercise6();
  await exercise7();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
