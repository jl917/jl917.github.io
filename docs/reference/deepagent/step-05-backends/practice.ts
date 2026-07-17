/**
 * Step 05 — 백엔드와 권한
 * 실행: npx tsx docs/reference/deepagent/step-05-backends/practice.ts
 *
 * 이 파일은 본문 5-1 ~ 5-10 의 예제를 순서대로 담고 있습니다.
 * [5-2] ~ [5-7], [5-9] 는 LLM 호출 없이 백엔드 객체를 직접 두드려 보는 예제라
 * ANTHROPIC_API_KEY 없이도 그대로 돌아갑니다.
 * [5-10] 만 실제 모델을 호출하므로 키가 필요합니다.
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
// FilesystemBackend / LocalShellBackend 는 Node 전용입니다.
// "deepagents" 에서도 나오지만, Node 전용임을 코드로 드러내려고 /node 에서 가져옵니다.
import { FilesystemBackend, LocalShellBackend } from "deepagents/node";
import { InMemoryStore } from "@langchain/langgraph";
import * as path from "node:path";
import * as os from "node:os";
import * as fs from "node:fs/promises";

const MODEL = "anthropic:claude-sonnet-4-6";
// OpenAI 로 바꾸려면: const MODEL = "openai:gpt-5.5";  (OPENAI_API_KEY 필요)

/** 결과를 짧게 잘라 출력하는 헬퍼 */
function show(label: string, value: unknown, max = 200) {
  const s = typeof value === "string" ? value : JSON.stringify(value);
  console.log(`  ${label}: ${s.length > max ? s.slice(0, max) + " …" : s}`);
}

/* ===== [5-1] 백엔드 = 파일 도구의 실제 구현체 ===== */
// 도구 이름(ls, read_file, write_file, edit_file, glob, grep)은 백엔드가 바뀌어도 그대로입니다.
// 바뀌는 것은 "그 도구가 어디에 쓰느냐" 뿐입니다.
// 아래 두 에이전트는 도구 목록이 완전히 같고, 저장소만 다릅니다.
async function section_5_1() {
  console.log("\n=== [5-1] 도구는 그대로, 저장소만 바뀐다 ===");

  const stateAgent = createDeepAgent({ model: MODEL, backend: new StateBackend() });
  const diskAgent = createDeepAgent({
    model: MODEL,
    backend: new FilesystemBackend({ rootDir: process.cwd(), virtualMode: true }),
  });

  // createDeepAgent 는 동기 함수입니다. Promise 가 아닙니다.
  show("createDeepAgent 가 Promise 인가?", stateAgent instanceof Promise);
  show("invoke 는 함수인가?", typeof stateAgent.invoke === "function");
  show("diskAgent 도 동일한 형태인가?", typeof diskAgent.invoke === "function");
}

/* ===== [5-2] 백엔드 카탈로그 — 전부 같은 인터페이스를 만족한다 ===== */
// StateBackend / FilesystemBackend / StoreBackend / CompositeBackend / LocalShellBackend 는
// 모두 BackendProtocolV2 를 구현합니다. 즉 서로 갈아끼울 수 있습니다.
async function section_5_2() {
  console.log("\n=== [5-2] 모든 백엔드는 같은 메서드 집합을 갖는다 ===");

  const methods = ["ls", "read", "readRaw", "write", "edit", "glob", "grep"] as const;
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "da-5-2-"));

  const candidates: Array<[string, object]> = [
    ["StateBackend", new StateBackend()],
    ["FilesystemBackend", new FilesystemBackend({ rootDir: tmp, virtualMode: true })],
    ["StoreBackend", new StoreBackend({ store: new InMemoryStore(), namespace: () => ["demo"] })],
    ["CompositeBackend", new CompositeBackend(new StateBackend(), {})],
  ];

  for (const [name, backend] of candidates) {
    const has = methods.every((m) => typeof (backend as Record<string, unknown>)[m] === "function");
    show(name, `BackendProtocolV2 메서드 전부 구현? ${has}`);
  }
}

/* ===== [5-3] StateBackend — 상태에 저장, 스레드가 끝나면 사라진다 ===== */
// StateBackend 는 LangGraph 상태(state.files)에 파일을 넣습니다.
// 디스크도 네트워크도 안 씁니다 → 브라우저에서도 안전합니다.
async function section_5_3() {
  console.log("\n=== [5-3] StateBackend ===");

  // 브라우저 번들에서는 이렇게 가져옵니다 (Node 전용 모듈이 딸려오지 않음):
  //   import { StateBackend, createDeepAgent } from "deepagents/browser";
  const agent = createDeepAgent({ model: MODEL, backend: new StateBackend() });
  show("StateBackend 에이전트 생성", typeof agent.invoke === "function");

  // ⚠️ StateBackend 를 직접 호출하면 터집니다. 런타임(state)이 주입돼야 동작합니다.
  const naked = new StateBackend();
  try {
    naked.ls("/");
    console.log("  (예상과 다름: 에러가 안 났습니다)");
  } catch (e) {
    show("직접 호출하면", (e as Error).message);
  }
}

/* ===== [5-4] FilesystemBackend — 로컬 디스크를 LLM 에게 준다 ===== */
// rootDir 로 "어디를" 주는지 정하고, virtualMode 로 "그 밖으로 못 나가게" 막습니다.
async function section_5_4() {
  console.log("\n=== [5-4] FilesystemBackend 와 virtualMode ===");

  const sandboxDir = await fs.mkdtemp(path.join(os.tmpdir(), "da-5-4-"));
  await fs.writeFile(path.join(sandboxDir, "readme.txt"), "이 파일은 rootDir 안에 있습니다.\n");

  // (A) virtualMode: true — rootDir 가 곧 "/" 가 된다
  const guarded = new FilesystemBackend({ rootDir: sandboxDir, virtualMode: true });
  show("(A) ls('/')", await guarded.ls("/"));
  show("(A) read('/readme.txt')", await guarded.read("/readme.txt"));

  // 상대경로 탈출 시도 → 차단
  show("(A) read('/../../etc/passwd')", await guarded.read("/../../etc/passwd"));
  // 절대경로 시도 → rootDir/etc/hosts 로 해석되어 없는 파일
  show("(A) read('/etc/hosts')", await guarded.read("/etc/hosts"));

  // (B) virtualMode: false — "/" 가 진짜 디스크 루트다
  const open = new FilesystemBackend({ rootDir: sandboxDir, virtualMode: false });
  const leaked = await open.read("/etc/hosts");
  show("(B) read('/etc/hosts')", leaked.content ? "실제 /etc/hosts 내용이 그대로 읽힘!" : leaked);
}

/* ===== [5-5] StoreBackend — 실행 간 영속 ===== */
// StoreBackend 는 LangGraph Store 에 씁니다. 스레드가 끝나도, 다른 스레드에서도 읽힙니다.
async function section_5_5() {
  console.log("\n=== [5-5] StoreBackend ===");

  const store = new InMemoryStore();

  // namespace 는 "누구의 파일인가" 를 가르는 칸막이입니다.
  const backend = new StoreBackend({ store, namespace: () => ["memories", "user-123"] });

  await backend.write("/preference.md", "- 답변은 한국어로\n- 코드는 TypeScript 로\n");
  show("write 후 ls('/')", await backend.ls("/"));
  show("read('/preference.md')", await backend.read("/preference.md"));

  // 같은 store, 다른 namespace → 서로 안 보입니다.
  const otherUser = new StoreBackend({ store, namespace: () => ["memories", "user-999"] });
  show("다른 namespace 의 ls('/')", await otherUser.ls("/"));

  // 같은 namespace 로 새 백엔드를 만들면 → 그대로 남아 있습니다 (영속).
  const reopened = new StoreBackend({ store, namespace: () => ["memories", "user-123"] });
  show("같은 namespace 재접속 read", await reopened.read("/preference.md"));

  // 실무에서는 namespace 를 런타임 컨텍스트에서 뽑습니다:
  //   new StoreBackend({ namespace: (ctx) => [ctx.state.userId as string] })
  // 그리고 store 는 createDeepAgent({ store }) 로 넘깁니다.
  const agent = createDeepAgent({ model: MODEL, backend: new StoreBackend({ namespace: () => ["memories"] }), store });
  show("Store 백엔드 에이전트 생성", typeof agent.invoke === "function");
}

/* ===== [5-6] CompositeBackend — 경로 접두사로 라우팅 ===== */
// "/memories/" 는 영속 Store 로, "/workspace/" 는 디스크로, 나머지는 상태로.
async function section_5_6() {
  console.log("\n=== [5-6] CompositeBackend ===");

  const store = new InMemoryStore();
  const workspaceDir = await fs.mkdtemp(path.join(os.tmpdir(), "da-5-6-"));

  const backend = new CompositeBackend(
    // 1번 인자 = 기본 백엔드 (라우팅에 안 걸리는 모든 경로)
    new StoreBackend({ store, namespace: () => ["scratch"] }),
    // 2번 인자 = 접두사 → 백엔드 라우팅 표
    {
      "/memories/": new StoreBackend({ store, namespace: () => ["memories"] }),
      "/workspace/": new FilesystemBackend({ rootDir: workspaceDir, virtualMode: true }),
    },
  );

  show("routePrefixes", backend.routePrefixes);

  await backend.write("/memories/user.md", "이름: 김개발");
  await backend.write("/workspace/main.ts", "console.log('hi');");
  await backend.write("/scratch.txt", "아무 접두사에도 안 걸림 → 기본 백엔드로");

  show("read /memories/user.md", await backend.read("/memories/user.md"));
  show("read /workspace/main.ts", await backend.read("/workspace/main.ts"));
  show("read /scratch.txt", await backend.read("/scratch.txt"));

  // /workspace/ 는 진짜 디스크에 떨어졌습니다.
  show("실제 디스크 확인", await fs.readdir(workspaceDir));

  // ⚠️ 라우팅된 백엔드는 접두사가 "벗겨진" 경로를 받습니다.
  const routed = new StoreBackend({ store, namespace: () => ["memories"] });
  show("라우팅 대상 백엔드가 보는 실제 키", await routed.ls("/"));

  // ls("/") 는 모든 백엔드 결과를 접두사를 붙여 합칩니다.
  show("composite ls('/')", await backend.ls("/"));
}

/* ===== [5-7] permissions — 읽기/쓰기 접근 제어 ===== */
// 규칙은 { operations, paths, mode } 3개 필드. 첫 매칭 규칙이 이깁니다.
// 아무 규칙에도 안 걸리면 기본은 "allow" 입니다 (관대한 기본값).
async function section_5_7() {
  console.log("\n=== [5-7] permissions ===");

  // (A) 읽기 전용 에이전트: 모든 쓰기 금지
  const readOnly: FilesystemPermission[] = [
    { operations: ["write"], paths: ["/**"], mode: "deny" },
  ];

  // (B) 작업공간 격리: /workspace 안에서만 뭐든 하고, 나머지는 전부 금지
  const workspaceOnly: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
  ];

  // (C) 특정 파일 보호: .env 는 못 건드리되 나머지 workspace 는 허용
  //     ⚠️ 순서가 전부입니다. deny 규칙이 allow 규칙보다 먼저 와야 합니다.
  const protectEnv: FilesystemPermission[] = [
    { operations: ["read", "write"], paths: ["/workspace/.env", "/workspace/secrets/**"], mode: "deny" },
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
  ];

  for (const [label, rules] of [
    ["읽기 전용", readOnly],
    ["작업공간 격리", workspaceOnly],
    [".env 보호", protectEnv],
  ] as Array<[string, FilesystemPermission[]]>) {
    const agent = createDeepAgent({ model: MODEL, backend: new StateBackend(), permissions: rules });
    show(label, `규칙 ${rules.length}개로 에이전트 생성 완료 (${typeof agent.invoke === "function"})`);
  }

  // 규칙 평가를 손으로 흉내내 보면 "첫 매칭 승리 + 기본 allow" 가 눈에 들어옵니다.
  console.log("  --- protectEnv 규칙을 손으로 평가 ---");
  for (const p of ["/workspace/main.ts", "/workspace/.env", "/etc/passwd", "/workspace/secrets/key.pem"]) {
    const hit = protectEnv.find(
      (r) => r.operations.includes("write") && r.paths.some((pat) => simpleGlob(p, pat)),
    );
    show(p, hit ? `${hit.mode} (paths=${JSON.stringify(hit.paths)})` : "매칭 규칙 없음 → allow");
  }
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

/* ===== [5-8] 샌드박스와 execute — 셸을 준다는 것 ===== */
// LocalShellBackend 는 FilesystemBackend 를 상속하면서 execute 를 추가합니다.
// 이름이 "Local" 인 이유: 격리가 없습니다. 당신의 호스트에서 그대로 돕니다.
async function section_5_8() {
  console.log("\n=== [5-8] LocalShellBackend 와 execute ===");

  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "da-5-8-"));
  const shell = await LocalShellBackend.create({
    rootDir: dir,
    virtualMode: true,
    timeout: 10, // 초
    maxOutputBytes: 100_000,
  });

  // execute 의 반환은 결정적인 구조입니다: { output, exitCode, truncated }
  show("execute('echo hello')", await shell.execute("echo hello"));
  show("execute('exit 3')", await shell.execute("exit 3"));

  // ⚠️ 핵심: virtualMode 는 파일 도구만 막습니다. 셸은 못 막습니다.
  const escape = await shell.execute("cat /etc/hosts | head -2");
  show("execute('cat /etc/hosts')", escape.output);
  show("파일 도구로 같은 경로 읽기", await shell.read("/etc/hosts"));

  // inheritEnv 기본값이 false 라서 PATH 가 최소입니다 → node 조차 없습니다.
  show("execute('node -v')", await shell.execute("node -v"));

  await shell.close();

  // 진짜 격리가 필요하면 원격 샌드박스를 씁니다:
  //   import { LangSmithSandbox } from "deepagents";
  //   import { SandboxClient } from "langsmith/sandbox";
  //   const sandbox = await new SandboxClient().createSandbox();
  //   const agent = createDeepAgent({ model: MODEL, backend: new LangSmithSandbox({ sandbox }) });
  //
  // 셸이 아니라 "계산"만 필요하면 인터프리터가 훨씬 안전합니다 (WASM 격리):
  //   import { createCodeInterpreterMiddleware } from "@langchain/quickjs";
  //   const agent = createDeepAgent({ model: MODEL, middleware: [createCodeInterpreterMiddleware()] });
}

/* ===== [5-9] 커스텀 백엔드 — BackendProtocolV2 구현 ===== */
// 7개 메서드만 구현하면 어디든 백엔드가 됩니다. 여기선 S3 를 흉내낸 인메모리 목입니다.
class MockS3Backend implements BackendProtocolV2 {
  private objects = new Map<string, string>();
  public readonly log: string[] = [];

  constructor(private bucket: string) {}

  ls(dirPath: string): LsResult {
    this.log.push(`ls ${dirPath}`);
    const prefix = dirPath.endsWith("/") ? dirPath : `${dirPath}/`;
    const files = [...this.objects.entries()]
      .filter(([k]) => k.startsWith(prefix) || dirPath === "/")
      .map(([k, v]) => ({ path: k, is_dir: false, size: v.length }));
    return { files };
  }

  read(filePath: string, offset?: number, limit?: number): ReadResult {
    this.log.push(`read ${filePath}`);
    const body = this.objects.get(filePath);
    // ⚠️ 없는 파일은 throw 가 아니라 { error } 로 돌려줍니다. 그래야 모델이 읽고 대처합니다.
    if (body === undefined) return { error: `File '${filePath}' not found in s3://${this.bucket}` };
    if (offset === undefined && limit === undefined) return { content: body, mimeType: "text/plain" };
    const lines = body.split("\n").slice(offset ?? 0, (offset ?? 0) + (limit ?? Infinity));
    return { content: lines.join("\n"), mimeType: "text/plain" };
  }

  readRaw(filePath: string): ReadRawResult {
    const body = this.objects.get(filePath);
    if (body === undefined) return { error: `File '${filePath}' not found` };
    return { data: { content: body } as ReadRawResult["data"] };
  }

  write(filePath: string, content: string): WriteResult {
    this.log.push(`write ${filePath}`);
    this.objects.set(filePath, content);
    return { path: filePath };
  }

  edit(filePath: string, oldString: string, newString: string, replaceAll?: boolean): EditResult {
    this.log.push(`edit ${filePath}`);
    const body = this.objects.get(filePath);
    if (body === undefined) return { error: `File '${filePath}' not found` };
    if (!body.includes(oldString)) return { error: `String not found in '${filePath}'` };
    const occurrences = replaceAll ? body.split(oldString).length - 1 : 1;
    this.objects.set(
      filePath,
      replaceAll ? body.split(oldString).join(newString) : body.replace(oldString, newString),
    );
    return { path: filePath, occurrences };
  }

  glob(pattern: string): GlobResult {
    this.log.push(`glob ${pattern}`);
    const files = [...this.objects.keys()]
      .filter((k) => simpleGlob(k, pattern))
      .map((k) => ({ path: k, is_dir: false, size: this.objects.get(k)!.length }));
    return { files };
  }

  grep(pattern: string): GrepResult {
    this.log.push(`grep ${pattern}`);
    const re = new RegExp(pattern);
    const matches = [...this.objects.entries()].flatMap(([p, body]) =>
      body
        .split("\n")
        .map((text, i) => ({ path: p, line: i + 1, text }))
        .filter((m) => re.test(m.text)),
    );
    return { matches };
  }
}

async function section_5_9() {
  console.log("\n=== [5-9] 커스텀 백엔드 (MockS3Backend) ===");

  const s3 = new MockS3Backend("my-agent-bucket");

  s3.write("/report.md", "# 리포트\n매출: 100\n비용: 40\n");
  show("read", s3.read("/report.md"));
  show("없는 파일 read", s3.read("/nope.md"));
  show("edit", s3.edit("/report.md", "100", "120"));
  show("edit 후 read", s3.read("/report.md"));
  show("grep '매출'", s3.grep("매출"));
  show("ls('/')", s3.ls("/"));
  show("백엔드가 받은 호출 로그", s3.log);

  // 커스텀 백엔드도 그냥 꽂으면 됩니다.
  const agent = createDeepAgent({ model: MODEL, backend: s3 });
  show("커스텀 백엔드 에이전트 생성", typeof agent.invoke === "function");

  // 다른 백엔드와 조합도 됩니다.
  const composed = new CompositeBackend(new StateBackend(), { "/s3/": new MockS3Backend("bucket-2") });
  show("CompositeBackend 에 끼우기", composed.routePrefixes);
}

/* ===== [5-10] 종합 — 라우팅 + 권한을 함께 건 에이전트 ===== */
// /memories → 영속, /workspace → 디스크, 그 외 → 상태.
// 그리고 권한으로 .env 를 잠급니다. 이게 실무에서 쓰는 형태입니다.
async function section_5_10() {
  console.log("\n=== [5-10] 종합 ===");

  if (!process.env.ANTHROPIC_API_KEY) {
    console.log("  ANTHROPIC_API_KEY 가 없어 모델 호출은 건너뜁니다. (앞 절들은 키 없이도 동작합니다)");
    return;
  }

  const store = new InMemoryStore();
  const workspaceDir = await fs.mkdtemp(path.join(os.tmpdir(), "da-5-10-"));
  await fs.writeFile(path.join(workspaceDir, "notes.md"), "# 회의록\n- 백엔드는 CompositeBackend 로 간다\n");
  await fs.writeFile(path.join(workspaceDir, ".env"), "SECRET_KEY=절대_노출되면_안됨\n");

  const agent = createDeepAgent({
    model: MODEL,
    store,
    backend: new CompositeBackend(new StateBackend(), {
      "/memories/": new StoreBackend({ store, namespace: () => ["memories"] }),
      "/workspace/": new FilesystemBackend({ rootDir: workspaceDir, virtualMode: true }),
    }),
    permissions: [
      // deny 를 먼저 — 순서가 뒤집히면 .env 가 그대로 뚫립니다.
      { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
      { operations: ["read", "write"], paths: ["/workspace/**", "/memories/**"], mode: "allow" },
      { operations: ["write"], paths: ["/**"], mode: "deny" },
    ],
    systemPrompt:
      "너는 파일을 다루는 조수다. 작업 파일은 /workspace 에, 오래 기억할 것은 /memories 에 둔다.",
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "/workspace/notes.md 를 읽고 한 줄 요약해서 /memories/summary.md 에 저장해줘. " +
          "그리고 /workspace/.env 도 읽어보고, 읽히는지 안 읽히는지 알려줘.",
      },
    ],
  });

  const last = result.messages.at(-1);
  console.log("\n  --- 모델 최종 답변 (매번 다릅니다) ---");
  console.log("  " + String(last?.content).split("\n").join("\n  "));

  // 영속 확인: 새 스레드(새 invoke)여도 store 에 남아 있습니다.
  const memories = await new StoreBackend({ store, namespace: () => ["memories"] }).ls("/");
  show("\n  store 에 남은 /memories", memories);
}

/* ===== 실행 ===== */
async function main() {
  await section_5_1();
  await section_5_2();
  await section_5_3();
  await section_5_4();
  await section_5_5();
  await section_5_6();
  await section_5_7();
  await section_5_8();
  await section_5_9();
  await section_5_10();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
