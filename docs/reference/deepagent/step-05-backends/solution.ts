/**
 * Step 05 — 백엔드와 권한 · 정답
 * 실행: npx tsx docs/reference/deepagent/step-05-backends/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 각 정답 위 주석에 "왜 그런가" 와 "무엇이 함정인가" 를 적어 두었습니다.
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
  type PermissionMode,
} from "deepagents";
import { FilesystemBackend, LocalShellBackend } from "deepagents/node";
import { InMemoryStore } from "@langchain/langgraph";
import * as path from "node:path";
import * as os from "node:os";
import * as fs from "node:fs/promises";

const MODEL = "anthropic:claude-sonnet-4-6";

function show(label: string, value: unknown, max = 180) {
  const s = typeof value === "string" ? value : JSON.stringify(value);
  console.log(`  ${label}: ${s.length > max ? s.slice(0, max) + " …" : s}`);
}

/* ===== [정답 1] 백엔드 고르기 ===== */
// (a) 브라우저 데모, 새로고침하면 날아가도 됨
//     → StateBackend.
//       디스크/네트워크를 안 쓰므로 브라우저 번들에 안전하게 들어갑니다.
//       "deepagents/browser" 엔트리포인트에서 가져오세요. FilesystemBackend 는 여기 없습니다.
//
// (b) 사용자의 코드 저장소를 리팩터링하는 CLI
//     → FilesystemBackend({ rootDir: 저장소경로, virtualMode: true }).
//       진짜 파일을 고쳐야 하니 디스크가 필요합니다. 대신 rootDir 밖으로 못 나가게 잠급니다.
//
// (c) 대화가 끝나도 선호를 기억해야 하는 챗봇
//     → StoreBackend.
//       StateBackend 는 스레드가 끝나면 사라집니다. Store 는 스레드 밖에 남습니다.
//
// (d) (c) + 작업 파일은 디스크에
//     → CompositeBackend.
//       "/memories/" → StoreBackend, "/workspace/" → FilesystemBackend 로 라우팅합니다.
//       이게 실무에서 가장 흔한 조합입니다.

/* ===== [정답 2] virtualMode 로 탈출을 막아라 ===== */
// 포인트: 세 시도가 "다른 이유" 로 막힙니다. 하나는 차단, 둘은 rootDir 안으로 재해석.
async function solution2() {
  console.log("\n=== [정답 2] virtualMode 탈출 시도 ===");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "sol5-2-"));
  await fs.writeFile(path.join(dir, "ok.txt"), "안에 있는 파일");

  const backend = new FilesystemBackend({ rootDir: dir, virtualMode: true });

  // 정상 경로는 잘 읽힙니다.
  show("read('/ok.txt')", await backend.read("/ok.txt"));

  for (const attempt of ["/../../etc/passwd", "/etc/hosts", "/~/.ssh/id_rsa"]) {
    const r = await backend.read(attempt);
    show(attempt, r.error ? `막힘 → ${r.error}` : `⚠️ 읽힘! ${String(r.content).slice(0, 40)}`);
  }

  // 해설:
  //  - "/../../etc/passwd" → 경로 정규화 단계에서 "Path traversal not allowed" 로 즉시 차단됩니다.
  //  - "/etc/hosts"        → 차단이 아니라 <rootDir>/etc/hosts 로 "재해석" 되어 ENOENT 가 납니다.
  //                          즉 virtualMode 에서는 "/" 가 rootDir 를 뜻합니다.
  //  - "/~/.ssh/id_rsa"    → 마찬가지로 <rootDir>/~/.ssh/id_rsa 로 재해석되어 ENOENT.
  //
  // ⚠️ 함정: virtualMode: false 로 두면 (2)(3)은 진짜 시스템 파일을 읽습니다.
  //          rootDir 를 줬다고 격리된 게 아닙니다. 격리는 virtualMode 가 합니다.
  const open = new FilesystemBackend({ rootDir: dir, virtualMode: false });
  const leaked = await open.read("/etc/hosts");
  show("virtualMode:false 로 /etc/hosts", leaked.content ? "⚠️ 진짜 /etc/hosts 가 읽혔습니다" : leaked);
}

/* ===== [정답 3] StoreBackend 로 사용자별 칸막이 ===== */
async function solution3() {
  console.log("\n=== [정답 3] namespace 격리 ===");
  const store = new InMemoryStore();

  const userA = new StoreBackend({ store, namespace: () => ["memories", "user-a"] });
  const userB = new StoreBackend({ store, namespace: () => ["memories", "user-b"] });

  await userA.write("/secret.md", "A 의 비밀 메모");

  show("userA.ls('/')", await userA.ls("/"));
  show("userB.ls('/')", await userB.ls("/")); // → files: [] (안 보임)
  show("userB.read('/secret.md')", await userB.read("/secret.md")); // → error

  // 같은 namespace 로 다시 열면 그대로 있습니다 = 영속.
  const userAAgain = new StoreBackend({ store, namespace: () => ["memories", "user-a"] });
  show("userA 재접속 read", await userAAgain.read("/secret.md"));

  // ⚠️ 함정: namespace 를 고정 문자열로 두면(예: () => ["memories"]) 모든 사용자가
  //          같은 칸을 공유합니다. 멀티테넌트라면 반드시 런타임에서 사용자 식별자를 뽑아야 합니다:
  //            new StoreBackend({ namespace: (ctx) => ["memories", ctx.state.userId as string] })
  //
  // ⚠️ 함정 2: InMemoryStore 는 이름 그대로 프로세스 메모리입니다. 재시작하면 다 날아갑니다.
  //            진짜 영속이 필요하면 DB 기반 Store 를 쓰세요.
}

/* ===== [정답 4] CompositeBackend 조립 ===== */
async function solution4() {
  console.log("\n=== [정답 4] CompositeBackend 라우팅 ===");
  const store = new InMemoryStore();
  const workspaceDir = await fs.mkdtemp(path.join(os.tmpdir(), "sol5-4-"));

  const backend = new CompositeBackend(
    new StoreBackend({ store, namespace: () => ["scratch"] }), // 기본
    {
      "/memories/": new StoreBackend({ store, namespace: () => ["memories"] }),
      "/workspace/": new FilesystemBackend({ rootDir: workspaceDir, virtualMode: true }),
    },
  );

  await backend.write("/memories/pref.md", "한국어로 답할 것");
  await backend.write("/workspace/app.ts", "export const x = 1;");
  await backend.write("/etc.txt", "라우팅 안 걸림 → 기본 백엔드");

  show("read /memories/pref.md", await backend.read("/memories/pref.md"));
  show("read /workspace/app.ts", await backend.read("/workspace/app.ts"));
  show("read /etc.txt", await backend.read("/etc.txt"));

  // 검증: /workspace/ 는 진짜 디스크에 떨어졌는가?
  show("실제 디스크 readdir", await fs.readdir(workspaceDir)); // → ["app.ts"]

  // 검증: /memories/ 는 Store 에만 있고 디스크엔 없다
  show("디스크에 pref.md 있나?", (await fs.readdir(workspaceDir)).includes("pref.md")); // → false

  // ⚠️ 함정 (이 문제의 핵심): 라우팅된 백엔드는 접두사가 "벗겨진" 경로를 받습니다.
  //    에이전트는 "/memories/pref.md" 로 부르지만, StoreBackend 에는 "/pref.md" 로 저장됩니다.
  const raw = new StoreBackend({ store, namespace: () => ["memories"] });
  show("Store 가 실제로 가진 키", await raw.ls("/")); // → "/pref.md" (앞의 /memories/ 가 없음!)
  //    커스텀 백엔드를 CompositeBackend 에 끼울 때 이걸 모르면
  //    "왜 경로가 안 맞지?" 로 한참 헤맵니다.

  // 참고: 접두사가 긴 규칙이 이깁니다. "/a/b/" 와 "/a/" 가 둘 다 있으면 "/a/b/x" 는 "/a/b/" 로 갑니다.
  show("routePrefixes", backend.routePrefixes);
}

/* ===== [정답 5] 권한 규칙의 순서 ===== */
// 규칙 평가는 "첫 매칭 승리 + 기본 allow" 입니다.
function checkWrite(rules: FilesystemPermission[], p: string): PermissionMode {
  for (const rule of rules) {
    if (!rule.operations.includes("write")) continue;
    if (rule.paths.some((pattern) => simpleGlob(p, pattern))) return rule.mode ?? "allow";
  }
  return "allow"; // 아무 규칙에도 안 걸리면 허용 — 관대한 기본값!
}

async function solution5() {
  console.log("\n=== [정답 5] 규칙 순서 ===");

  const good: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
  ];
  const bad: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
  ];

  show("good → /workspace/.env", checkWrite(good, "/workspace/.env")); // deny
  show("bad  → /workspace/.env", checkWrite(bad, "/workspace/.env")); // allow ⚠️
  show("good → /workspace/app.ts", checkWrite(good, "/workspace/app.ts")); // allow
  show("bad  → /workspace/app.ts", checkWrite(bad, "/workspace/app.ts")); // allow

  // ⚠️ 함정: bad 는 에러도 경고도 없이 .env 를 그대로 열어줍니다.
  //    "/workspace/**" 가 먼저 매칭되어 allow 로 끝나고, 뒤의 deny 규칙은 평가조차 안 됩니다.
  //    규칙은 방화벽처럼 "좁은 deny 를 먼저, 넓은 allow 를 나중에" 쌓으세요.

  // ⚠️ 함정 2: 기본이 allow 라는 것. 아래 규칙은 "workspace 만 허용" 처럼 보이지만
  //    실제로는 /etc/passwd 쓰기를 막지 않습니다 — 아무 규칙에도 안 걸리니까요.
  const leaky: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
  ];
  show("leaky → /etc/passwd", checkWrite(leaky, "/etc/passwd")); // allow ⚠️
  //    막으려면 맨 끝에 "전부 거부" 를 깔아야 합니다:
  const sealed: FilesystemPermission[] = [
    ...leaky,
    { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
  ];
  show("sealed → /etc/passwd", checkWrite(sealed, "/etc/passwd")); // deny
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

/* ===== [정답 6] 셸은 virtualMode 를 지키지 않는다 ===== */
async function solution6() {
  console.log("\n=== [정답 6] execute 는 rootDir 를 안 지킨다 ===");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "sol5-6-"));

  const shell = await LocalShellBackend.create({ rootDir: dir, virtualMode: true, timeout: 10 });

  // (a) 파일 도구 → 막힙니다
  show("read('/etc/hosts')", await shell.read("/etc/hosts"));

  // (b) 셸 → 안 막힙니다
  const escaped = await shell.execute("cat /etc/hosts | head -2");
  show("execute('cat /etc/hosts')", escaped.output.replace(/\n/g, " ⏎ "));

  await shell.close();

  // → 왜 다른가요?
  //   virtualMode 는 "백엔드의 파일 메서드(read/write/edit/ls/glob)가 경로를 어떻게 해석하는가" 만
  //   바꾸는 설정입니다. execute 는 그 경로 해석기를 거치지 않고 그냥 /bin/sh 에 문자열을 넘깁니다.
  //   셸에게 rootDir 는 cwd 일 뿐, 감옥이 아닙니다. `cd /` 한 줄이면 어디든 갑니다.
  //
  // ⚠️ 이게 이 스텝에서 가장 위험한 함정입니다.
  //    "virtualMode: true 니까 안전하지" 라고 믿고 LocalShellBackend 를 쓰면,
  //    에이전트는 ~/.ssh/id_rsa 도, ~/.aws/credentials 도 읽을 수 있습니다.
  //    LocalShellBackend 의 "Local" 은 "격리 없음" 이라는 뜻으로 읽으세요.
  //
  // 그럼 어떻게 하나?
  //   1) 셸이 정말 필요한가? 계산만 필요하면 인터프리터(@langchain/quickjs)를 쓰세요. WASM 격리라 안전합니다.
  //   2) 진짜 격리가 필요하면 프로세스 밖으로 내보내세요 — LangSmithSandbox, Daytona, Modal, Docker 등.
  //   3) LocalShellBackend 를 쓴다면 그 프로세스 자체를 컨테이너에 가두세요.
}

/* ===== [정답 7] 커스텀 백엔드 — 읽기 전용 래퍼 ===== */
// 포인트: 위임(delegate)만 하면 되므로 짧습니다. 쓰기는 throw 대신 { error } 로 돌려줍니다.
class ReadOnlyBackend implements BackendProtocolV2 {
  constructor(private inner: BackendProtocolV2) {}

  // 읽기 계열 — 그대로 위임
  ls(path: string) {
    return this.inner.ls(path);
  }
  read(filePath: string, offset?: number, limit?: number) {
    return this.inner.read(filePath, offset, limit);
  }
  readRaw(filePath: string) {
    return this.inner.readRaw(filePath);
  }
  glob(pattern: string, path?: string) {
    return this.inner.glob(pattern, path);
  }
  grep(pattern: string, path?: string | null, glob?: string | null) {
    return this.inner.grep(pattern, path, glob);
  }

  // 쓰기 계열 — 거부. throw 가 아니라 error 필드!
  write(filePath: string): WriteResult {
    return { error: `읽기 전용 백엔드입니다. '${filePath}' 에 쓸 수 없습니다.` };
  }
  edit(filePath: string): EditResult {
    return { error: `읽기 전용 백엔드입니다. '${filePath}' 을 수정할 수 없습니다.` };
  }
}

async function solution7() {
  console.log("\n=== [정답 7] ReadOnlyBackend ===");
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "sol5-7-"));
  await fs.writeFile(path.join(dir, "data.txt"), "읽기만 됩니다");

  const readOnly = new ReadOnlyBackend(new FilesystemBackend({ rootDir: dir, virtualMode: true }));

  show("ls('/')", await readOnly.ls("/"));
  show("read('/data.txt')", await readOnly.read("/data.txt"));
  show("write 시도", await readOnly.write("/data.txt"));
  show("edit 시도", await readOnly.edit("/data.txt"));

  // 디스크가 정말 안 바뀌었는지 검증
  show("디스크 원본 그대로?", (await fs.readFile(path.join(dir, "data.txt"), "utf-8")) === "읽기만 됩니다");

  const agent = createDeepAgent({ model: MODEL, backend: readOnly });
  show("에이전트 생성", typeof agent.invoke === "function");

  // 보너스: permissions 로도 같은 일을 할 수 있습니다.
  const viaPermissions = createDeepAgent({
    model: MODEL,
    backend: new FilesystemBackend({ rootDir: dir, virtualMode: true }),
    permissions: [{ operations: ["write"], paths: ["/**"], mode: "deny" }],
  });
  show("permissions 버전 에이전트", typeof viaPermissions.invoke === "function");

  // 어느 쪽이 나을까?
  //   → 대개 permissions 가 낫습니다. 선언적이고, 서브에이전트별로 갈아끼울 수 있고,
  //     백엔드 구현을 건드리지 않으니 백엔드를 교체해도 규칙이 그대로 따라옵니다.
  //   → 커스텀 백엔드가 필요한 때는 "경로 규칙으로 표현할 수 없는 정책" 일 때입니다.
  //     예: "하루 100회까지만 쓰기 허용", "쓰기 전에 감사 로그 남기기", "S3 에 저장".
  //     즉 permissions = 어디에(where), 커스텀 백엔드 = 어떻게(how).
}

async function main() {
  await solution2();
  await solution3();
  await solution4();
  await solution5();
  await solution6();
  await solution7();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
