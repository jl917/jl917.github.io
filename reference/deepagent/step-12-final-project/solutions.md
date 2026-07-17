# Step 12 — 종합 프로젝트 : 해설

[`problems.md`](./problems.md) 의 8과제 정답입니다. **스스로 풀어본 뒤에** 보세요.

각 과제는 (정답 코드 → 왜 이렇게 했나 → 함정) 순서로 갑니다.

> 아래 코드는 `step-12-final-project/ex/` 에 둔다고 가정하고, 원본 모듈을 `../` 로 import 합니다.

---

## 과제 1. 인용(citation) 강제하기 (15점)

### 정답 코드

`ex/citations.ts`

```ts
/**
 * 보고서의 인용을 기계적으로 검증합니다.
 * 이 파일에는 LLM 호출이 없습니다 — 전부 결정적이라 유닛 테스트가 가능합니다.
 */

export interface CitationIssue {
  kind:
    | "ghost_citation"
    | "unused_source"
    | "hallucinated_url"
    | "no_sources_section"
    | "gap_in_numbering";
  detail: string;
}

/** '### 출처' 제목. m 플래그로 줄 단위 매칭. */
const SOURCES_HEADING = /^###\s*출처\s*$/m;

/**
 * 보고서를 본문과 출처 섹션으로 자릅니다.
 *
 * 이 분리가 이 과제의 핵심입니다. 자르지 않고 전체에서 [n] 을 뽑으면
 * 출처 목록 자체의 "[1] 제목: URL" 이 본문 인용으로 잡혀서,
 * 유령 인용이 **영원히 검출되지 않습니다**.
 */
export function splitReport(report: string): { body: string; sources: string } {
  const m = SOURCES_HEADING.exec(report);
  if (!m) return { body: report, sources: "" };
  return {
    body: report.slice(0, m.index),
    sources: report.slice(m.index + m[0].length),
  };
}

/** "[1] 제목: https://..." 줄들을 파싱합니다. */
export function parseSources(sources: string): Map<number, { title: string; url: string }> {
  const out = new Map<number, { title: string; url: string }>();
  const re = /^\s*\[(\d+)\]\s*(.+?):\s*(https?:\/\/\S+)\s*$/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sources)) !== null) {
    out.set(Number(m[1]), { title: m[2]!.trim(), url: m[3]!.trim() });
  }
  return out;
}

/** 본문에서 [n] 인용 번호를 뽑습니다. */
export function parseBodyCitations(body: string): Set<number> {
  const out = new Set<number>();
  const re = /\[(\d+)\]/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(body)) !== null) out.add(Number(m[1]));
  return out;
}

export function verifyCitations(report: string, allowedUrls: Set<string>): CitationIssue[] {
  const issues: CitationIssue[] = [];
  const { body, sources } = splitReport(report);

  // 출처 섹션이 아예 없으면 나머지 검사는 의미가 없습니다. 조기 반환.
  if (!SOURCES_HEADING.test(report)) {
    issues.push({ kind: "no_sources_section", detail: "'### 출처' 섹션이 없습니다." });
    return issues;
  }

  const sourceMap = parseSources(sources);
  const cited = parseBodyCitations(body);

  // 1) 유령 인용: 본문엔 있는데 출처 목록에 없음
  for (const n of [...cited].sort((a, b) => a - b)) {
    if (!sourceMap.has(n)) {
      issues.push({
        kind: "ghost_citation",
        detail: `본문이 [${n}] 을 인용하지만 출처 목록에 ${n}번이 없습니다.`,
      });
    }
  }

  // 2) 미사용 출처: 목록엔 있는데 본문에서 안 씀
  for (const n of [...sourceMap.keys()].sort((a, b) => a - b)) {
    if (!cited.has(n)) {
      issues.push({
        kind: "unused_source",
        detail: `출처 ${n}번(${sourceMap.get(n)!.url})이 본문에서 인용되지 않았습니다.`,
      });
    }
  }

  // 3) URL 환각: findings 에 없는 URL — 가장 위험한 위반
  for (const [n, { url }] of [...sourceMap].sort((a, b) => a[0] - b[0])) {
    if (!allowedUrls.has(url)) {
      issues.push({
        kind: "hallucinated_url",
        detail: `출처 ${n}번의 URL 이 findings 에 없습니다(지어냈을 가능성): ${url}`,
      });
    }
  }

  // 4) 번호 갭: 1,2,3... 으로 이어져야 함
  const nums = [...sourceMap.keys()].sort((a, b) => a - b);
  for (let i = 0; i < nums.length; i++) {
    if (nums[i] !== i + 1) {
      issues.push({
        kind: "gap_in_numbering",
        detail: `출처 번호가 1부터 연속이어야 하는데 ${JSON.stringify(nums)} 입니다.`,
      });
      break; // 갭은 하나만 보고하면 충분합니다
    }
  }

  return issues;
}

/**
 * findings 파일들에서 실제로 등장한 URL 을 모읍니다.
 *
 * ⚠️ `/findings/` 로 시작하는 파일만 봅니다. `/report.md` 를 포함시키면
 * 보고서가 지어낸 URL 이 "허용 목록"에 들어가 버려서, 환각 검사가
 * 자기 자신을 근거로 통과하는 순환이 생깁니다.
 */
export function collectUrlsFromFindings(files: Record<string, unknown>): Set<string> {
  const urls = new Set<string>();
  for (const [path, entry] of Object.entries(files ?? {})) {
    if (!path.startsWith("/findings/")) continue;

    const content = (entry as { content?: unknown })?.content;
    // 파일 형식 v1/v2 를 모두 받아냅니다 (12-4 참고)
    const text =
      typeof content === "string"
        ? content
        : Array.isArray(content)
          ? content.join("\n")
          : content instanceof Uint8Array
            ? new TextDecoder().decode(content)
            : "";

    for (const m of text.matchAll(/https?:\/\/[^\s)\]<>"']+/g)) {
      // 문장 끝 구두점이 URL 에 붙어 들어오는 것을 떼어냅니다.
      urls.add(m[0].replace(/[.,;:]+$/, ""));
    }
  }
  return urls;
}
```

### 검증

이 코드는 LLM 을 안 부르므로 **결정적**입니다. 그래서 진짜 테스트를 쓸 수 있습니다.

```ts
const findings = {
  "/findings/a.md": {
    content:
      "본문 [1]\n\n### 출처\n" +
      "[1] RAG 개요: https://example.com/docs/rag-overview\n" +
      "[2] 비용: https://example.com/research/rag-cost-analysis",
  },
  // /findings/ 가 아니므로 무시되어야 함
  "/report.md": { content: "https://evil.example.com/should-not-count" },
};
const allowed = collectUrlsFromFindings(findings);
console.log([...allowed]);
```

**출력** (결정적입니다 — 매번 동일)

```
[
  'https://example.com/docs/rag-overview',
  'https://example.com/research/rag-cost-analysis'
]
```

`evil.example.com` 이 안 들어온 것을 확인하세요. 5가지 위반도 각각 잡힙니다.

```
[정상]     []
[유령]     [ 'ghost_citation' ]
[환각]     [ 'hallucinated_url' ]
[섹션없음] [ 'no_sources_section' ]
[번호갭]   [ 'gap_in_numbering' ]
[미사용]   [ 'unused_source' ]
```

### 재작성 루프

검증만으로는 부족합니다. 실패하면 고치게 해야 합니다.

```ts
import { buildResearchAgent, readStateFile } from "../agent.js";
import { verifyCitations, collectUrlsFromFindings } from "./citations.js";

const MAX_FIX_ROUNDS = 2; // ← 상한 필수

export async function researchWithCitationCheck(question: string) {
  const agent = buildResearchAgent();
  const config = { configurable: { thread_id: crypto.randomUUID() } };

  let result = await agent.invoke(
    { messages: [{ role: "user", content: question }] },
    { ...config, recursionLimit: 100 },
  );

  for (let round = 0; round < MAX_FIX_ROUNDS; round++) {
    const files = (result as { files?: Record<string, unknown> }).files ?? {};
    const report = readStateFile(files, "/report.md");
    if (!report) break;

    const allowed = collectUrlsFromFindings(files);
    const issues = verifyCitations(report, allowed);
    if (issues.length === 0) {
      console.log("인용 검증 통과");
      break;
    }

    console.log(`인용 위반 ${issues.length}건 — 재작성 요청 (라운드 ${round + 1})`);
    for (const i of issues) console.log(`  - [${i.kind}] ${i.detail}`);

    // 문제를 자연어로 알려주고 다시 쓰게 합니다.
    // 같은 thread_id 라서 에이전트는 findings 를 그대로 갖고 있습니다 — 재조사가 필요 없습니다.
    result = await agent.invoke(
      {
        messages: [
          {
            role: "user",
            content:
              "/report.md 의 인용에 다음 문제가 있습니다. /findings/ 를 다시 읽고 " +
              "출처를 바로잡아 /report.md 를 다시 쓰세요. " +
              "findings 에 없는 URL 은 절대 쓰지 마세요.\n\n" +
              issues.map((i) => `- ${i.detail}`).join("\n"),
          },
        ],
      },
      { ...config, recursionLimit: 100 },
    );
  }

  return result;
}
```

### 왜 이렇게 했나

- **`splitReport` 로 본문/출처를 자른 것**이 이 과제의 전부입니다. 안 자르면 유령 인용이 절대 안 잡힙니다.
- **재조사가 아니라 재작성**입니다. 같은 `thread_id` 로 호출하므로 findings 가 그대로 있습니다. 인용이 틀린 건 종합 단계의 실수지 조사의 실수가 아니므로, 다시 조사할 이유가 없습니다. (비용 차이가 큽니다.)
- **상한 2회.** 모델이 계속 실패할 수 있습니다.

> ⚠️ **함정**: `hallucinated_url` 검사는 **URL 완전 일치**로 합니다. 그런데 모델이 `https://example.com/docs/rag-overview/` 처럼 슬래시 하나를 더 붙이거나, `http` 를 `https` 로 바꿔 쓰면 **멀쩡한 URL 이 환각으로 잡힙니다.** 실무에서는 URL 을 정규화(끝 슬래시 제거, 스킴 통일, 쿼리 정렬)한 뒤 비교하세요. 위 코드는 교육용이라 구두점만 떼어냈습니다.

---

## 과제 2. 비평 → 재조사 루프 (20점)

### 정답 코드

`ex/critique-loop.ts`

```ts
import * as z from "zod";
import { createDeepAgent } from "deepagents";
import type { SubAgent } from "deepagents";
import { createSearchTool } from "../tools.js";
import { ORCHESTRATOR_PROMPT, RESEARCHER_PROMPT, CRITIQUE_PROMPT } from "../prompts.js";
import { ORCHESTRATOR_MODEL, RESEARCH_MODEL, CRITIQUE_MODEL } from "../subagents.js";
import { readStateFile } from "../agent.js";

/* ===== 1) 구조화된 판정 스키마 ===== */

export const CritiqueVerdict = z.object({
  verdict: z.enum(["PASS", "REVISE"]),
  issues: z
    .array(
      z.object({
        problem: z.string().describe("무엇이 잘못되었는가"),
        location: z.string().describe("어느 파일의 어느 부분인가"),
        researchTopic: z
          .string()
          .describe("부모가 그대로 task() 에 넣을 수 있는 조사 주제 한 문장"),
      }),
    )
    .describe("PASS 인 경우 빈 배열"),
});

export type CritiqueVerdictT = z.infer<typeof CritiqueVerdict>;

/* ===== 2) responseFormat 을 붙인 비평 서브에이전트 ===== */

const critiqueSubagent: SubAgent = {
  name: "critique-subagent",
  description:
    "/findings/ 의 조사 결과를 심사해 PASS 또는 REVISE 를 구조화된 JSON 으로 판정합니다. 검색은 하지 않습니다.",
  systemPrompt: CRITIQUE_PROMPT,
  model: CRITIQUE_MODEL,
  // 이것 하나로 부모가 문자열을 파싱할 필요가 사라집니다.
  responseFormat: CritiqueVerdict,
};

const researchSubagent: SubAgent = {
  name: "research-subagent",
  description:
    "웹 검색으로 하나의 구체적인 주제를 깊이 조사하고, 결과를 /findings/ 에 저장한 뒤 요약을 돌려줍니다.",
  systemPrompt: RESEARCHER_PROMPT,
  tools: [createSearchTool()],
  model: RESEARCH_MODEL,
};

/* ===== 3) 바깥 코드가 루프를 돈다 ===== */

const MAX_REVISE_ROUNDS = 2;

export async function researchWithCritiqueLoop(question: string) {
  const agent = createDeepAgent({
    model: ORCHESTRATOR_MODEL,
    systemPrompt: ORCHESTRATOR_PROMPT,
    tools: [],
    subagents: [researchSubagent, critiqueSubagent],
  });

  const config = { configurable: { thread_id: crypto.randomUUID() } };

  // 1단계: 조사만 시킵니다. 보고서는 아직 쓰지 말라고 명시합니다.
  let result = await agent.invoke(
    {
      messages: [
        {
          role: "user",
          content:
            `다음 질문을 조사하세요: ${question}\n\n` +
            "지금은 **조사만** 하세요. /question.txt 를 저장하고, 계획을 세우고, " +
            "research-subagent 로 조사해 /findings/ 를 채우세요. " +
            "아직 /report.md 는 쓰지 마세요.",
        },
      ],
    },
    { ...config, recursionLimit: 100 },
  );

  // 2단계: 비평 → 재조사 루프
  for (let round = 0; round <= MAX_REVISE_ROUNDS; round++) {
    const isLastRound = round === MAX_REVISE_ROUNDS;

    result = await agent.invoke(
      {
        messages: [
          {
            role: "user",
            content:
              "critique-subagent 를 호출해 /findings/ 를 심사시키세요. " +
              "판정 JSON 을 그대로 보여주세요.",
          },
        ],
      },
      { ...config, recursionLimit: 100 },
    );

    const verdict = extractVerdict(result);
    console.log(`[라운드 ${round}] 판정:`, verdict?.verdict ?? "(파싱 실패)");

    if (!verdict || verdict.verdict === "PASS") break;

    if (isLastRound) {
      // ⚠️ 마지막 라운드에서 반드시 탈출구를 줘야 무한 루프가 안 납니다.
      console.log("재조사 한도 도달 — 현재 findings 로 보고서를 씁니다.");
      break;
    }

    // REVISE → 지적된 주제를 그대로 재조사시킵니다.
    const topics = verdict.issues.map((i) => i.researchTopic);
    console.log(`  재조사 주제 ${topics.length}건:`, topics);

    result = await agent.invoke(
      {
        messages: [
          {
            role: "user",
            content:
              "비평가가 아래 구멍을 지적했습니다. 각 주제를 research-subagent 로 " +
              "재조사해 /findings/ 를 보강하세요. 아직 /report.md 는 쓰지 마세요.\n\n" +
              topics.map((t, i) => `${i + 1}. ${t}`).join("\n"),
          },
        ],
      },
      { ...config, recursionLimit: 100 },
    );
  }

  // 3단계: 이제 보고서를 씁니다.
  result = await agent.invoke(
    {
      messages: [
        {
          role: "user",
          content:
            "이제 /findings/ 를 모두 읽고 종합해 /report.md 를 작성하세요. " +
            "마지막에 /question.txt 를 다시 읽고 질문의 모든 측면에 답했는지 확인하세요.",
        },
      ],
    },
    { ...config, recursionLimit: 100 },
  );

  return result;
}

/**
 * 부모 메시지에서 비평 판정 JSON 을 찾아냅니다.
 *
 * responseFormat 을 준 서브에이전트의 결과는 JSON 문자열로 직렬화되어
 * ToolMessage 내용으로 들어옵니다. 뒤에서부터 훑어 가장 최근 판정을 찾습니다.
 */
function extractVerdict(result: unknown): CritiqueVerdictT | undefined {
  const messages = (result as { messages?: Array<{ content?: unknown }> }).messages ?? [];
  for (let i = messages.length - 1; i >= 0; i--) {
    const content = messages[i]?.content;
    if (typeof content !== "string") continue;
    // 문자열 안에 JSON 이 섞여 있을 수 있으므로 중괄호 범위를 뽑아 시도합니다.
    const start = content.indexOf("{");
    const end = content.lastIndexOf("}");
    if (start < 0 || end <= start) continue;
    try {
      const parsed = CritiqueVerdict.safeParse(JSON.parse(content.slice(start, end + 1)));
      if (parsed.success) return parsed.data;
    } catch {
      // 이 메시지는 JSON 이 아니었습니다. 계속 훑습니다.
    }
  }
  return undefined;
}
```

### 왜 이렇게 했나

- **바깥 코드가 루프를 돕니다.** 부모 에이전트에게 "REVISE 면 재조사해"라고 프롬프트로 맡길 수도 있지만, 그러면 모델 기분에 따라 건너뜁니다. 코드로 돌리면 **반드시** 돕니다.
- **조사와 보고서 작성을 분리**했습니다. 1단계에서 "아직 보고서는 쓰지 마"라고 못 박지 않으면, 에이전트가 조사하자마자 보고서를 써 버려서 비평할 대상이 사라집니다.
- **`researchTopic` 을 그대로 재조사에 넣습니다.** 비평 결과를 "다음 행동으로 바로 변환 가능한 모양"으로 받았기 때문에 가능합니다.

> ⚠️ **함정 (무한 REVISE)**: 비평가는 기준이 엄격할수록 **항상 REVISE 를 줍니다.** 완벽한 findings 는 없으니까요. 상한이 없으면 영원히 조사합니다. 그리고 상한에 도달했을 때 그냥 break 하면 안 되고, **"현재 것으로 보고서를 써라"는 탈출구**를 명시적으로 줘야 합니다. 안 그러면 상한에 걸린 뒤 아무 보고서도 없이 끝납니다.

> ⚠️ **함정 (extractVerdict 가 조용히 실패)**: `responseFormat` 결과를 못 찾으면 `undefined` 가 나오고, 위 코드는 그걸 PASS 처럼 취급해 루프를 빠져나갑니다. **파싱 실패와 PASS 를 구분하세요.** 로그에 "(파싱 실패)"를 찍게 해 둔 이유입니다 — 안 그러면 비평이 아예 동작 안 하는데도 "PASS 됐네" 하고 넘어갑니다.

---

## 과제 3. 병렬 조사 (15점)

### 정답 코드 (B안 — 코드로 강제)

`ex/parallel.ts`

```ts
import { createDeepAgent } from "deepagents";
import { createSearchTool } from "../tools.js";
import { RESEARCHER_PROMPT } from "../prompts.js";
import { RESEARCH_MODEL } from "../subagents.js";

export interface ResearchOutcome {
  topic: string;
  ok: boolean;
  findings: string;
  files: Record<string, unknown>;
  error?: string;
}

/** 조사 전용 독립 에이전트. 서브에이전트가 아니라 그냥 에이전트로 씁니다. */
function createResearcher() {
  return createDeepAgent({
    model: RESEARCH_MODEL,
    systemPrompt: RESEARCHER_PROMPT,
    tools: [createSearchTool()],
    // 서브에이전트가 필요 없습니다 — 이 에이전트가 곧 말단입니다.
    subagents: [],
  });
}

async function researchOne(topic: string): Promise<ResearchOutcome> {
  try {
    const agent = createResearcher();
    const result = await agent.invoke(
      { messages: [{ role: "user", content: topic }] },
      {
        configurable: { thread_id: crypto.randomUUID() }, // 조사마다 독립 스레드
        recursionLimit: 50,
      },
    );
    const messages = (result as { messages?: Array<{ content?: unknown }> }).messages ?? [];
    const last = messages[messages.length - 1]?.content;
    return {
      topic,
      ok: true,
      findings: typeof last === "string" ? last : JSON.stringify(last),
      files: (result as { files?: Record<string, unknown> }).files ?? {},
    };
  } catch (e) {
    // 하나가 죽어도 나머지는 살아야 합니다.
    return { topic, ok: false, findings: "", files: {}, error: String(e) };
  }
}

/** 배열을 size 크기 청크로 자릅니다. */
function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

export async function researchInParallel(
  topics: string[],
  concurrency = 3,
): Promise<ResearchOutcome[]> {
  const results: ResearchOutcome[] = [];
  // 청크 단위로 병렬 → 동시 실행 수가 concurrency 를 넘지 않습니다.
  for (const group of chunk(topics, concurrency)) {
    const settled = await Promise.all(group.map(researchOne));
    results.push(...settled);
  }
  return results;
}

/**
 * 여러 조사 결과의 files 를 병합합니다.
 *
 * ⚠️ 키 충돌 주의: 두 조사가 같은 슬러그(/findings/cost.md)를 쓰면 하나가 덮입니다.
 * 그래서 충돌 시 접미사를 붙여 살립니다.
 */
export function mergeFiles(outcomes: ResearchOutcome[]): Record<string, unknown> {
  const merged: Record<string, unknown> = {};
  for (const o of outcomes) {
    for (const [path, data] of Object.entries(o.files)) {
      if (!(path in merged)) {
        merged[path] = data;
        continue;
      }
      // 충돌 → -2, -3 ... 을 붙여 새 이름을 찾습니다.
      const dot = path.lastIndexOf(".");
      const stem = dot > 0 ? path.slice(0, dot) : path;
      const ext = dot > 0 ? path.slice(dot) : "";
      let n = 2;
      while (`${stem}-${n}${ext}` in merged) n++;
      merged[`${stem}-${n}${ext}`] = data;
    }
  }
  return merged;
}
```

### 소요 시간 증명

```ts
const topics = [
  "RAG(검색 증강 생성)의 개념과 장단점을 조사하라.",
  "파인튜닝의 개념과 장단점을 조사하라.",
  "RAG와 파인튜닝의 비용 구조를 비교 조사하라.",
];

console.time("순차");
for (const t of topics) await researchOne(t);
console.timeEnd("순차");

console.time("병렬");
await researchInParallel(topics, 3);
console.timeEnd("병렬");
```

**출력 예시** (모델 호출이라 매번 다릅니다 — 절대값이 아니라 **비율**을 보세요)

```
순차: 47.２s
병렬: 18.1s
```

병렬이 정확히 1/3 이 되지는 않습니다. 가장 느린 조사 하나가 전체를 결정하기 때문입니다.

### 왜 이렇게 했나

- **B안(코드로 강제)** 을 골랐습니다. A안(프롬프트로 유도)은 모델이 안 따르면 그만입니다.
- **조사마다 독립 `thread_id`** 를 줍니다. 같은 스레드를 공유하면 상태가 섞입니다.
- **`try/catch` 로 개별 실패를 격리**합니다. `Promise.all` 은 하나가 reject 하면 전부 버리므로, 각 작업 안에서 잡아 `ok: false` 로 돌려줍니다. (`Promise.allSettled` 를 써도 되지만, 이렇게 하면 결과 타입이 일관됩니다.)

> ⚠️ **함정 (순서 의존)**: `Promise.all` 은 **결과 배열의 순서**를 입력 순서대로 지켜 줍니다. 하지만 **완료 순서**는 보장하지 않습니다. 이 차이가 중요한 이유는, 인용 번호를 "완료된 순서대로" 매기면 매 실행마다 번호가 달라지기 때문입니다. 번호는 반드시 **URL 을 키로** 결정론적으로 매기세요.

> ⚠️ **함정 (rate limit)**: `concurrency` 없이 10개를 한 번에 띄우면 429 를 맞습니다. 그러면 전부 실패합니다. 청크로 자르는 것은 성능이 아니라 **생존**을 위한 것입니다.

---

## 과제 4. 토큰 예산 상한 (15점)

### 정답 코드

`ex/budget.ts`

```ts
import { createMiddleware, ToolMessage } from "langchain";

export interface BudgetOptions {
  maxSearches: number;
  maxTokens?: number;
}

/**
 * 검색 예산 미들웨어.
 *
 * ⚠️ 이 함수는 **팩토리**입니다. 호출할 때마다 새 카운터가 생깁니다.
 * 모듈 최상단에서 한 번 만들어 여러 실행이 공유하면, 카운터가 누적되어
 * 두 번째 실행이 시작하자마자 예산 소진 상태가 됩니다. 실행마다 새로 부르세요.
 */
export function createSearchBudgetMiddleware(options: BudgetOptions) {
  const { maxSearches, maxTokens } = options;

  let searchCount = 0;
  let totalTokens = 0;

  // createMiddleware 를 쓰면 훅의 타입이 전부 추론됩니다.
  // 객체 리터럴에 `as AgentMiddleware` 를 붙이는 것보다 안전합니다 —
  // 단언은 훅 시그니처가 틀려도 통과시켜 버립니다.
  return createMiddleware({
    name: "SearchBudgetMiddleware",

    // 모델 응답마다 토큰을 누적합니다.
    afterModel: (state) => {
      const last = state.messages[state.messages.length - 1];
      // usage_metadata 는 AIMessage 에만 있으므로 unknown 을 거쳐 좁힙니다.
      const usage = (last as unknown as { usage_metadata?: unknown })?.usage_metadata as
        | { input_tokens?: number; output_tokens?: number }
        | undefined;
      if (usage) {
        totalTokens += (usage.input_tokens ?? 0) + (usage.output_tokens ?? 0);
      }
      return undefined; // 상태를 바꾸지 않습니다
    },

    // 도구 실행을 가로챕니다.
    wrapToolCall: (request, handler) => {
      if (request.toolCall.name !== "web_search") {
        return handler(request); // 다른 도구는 통과
      }

      // ⚠️ wrapToolCall 은 ToolMessage | Command 를 돌려줘야 합니다.
      //    { content, tool_call_id } 같은 평범한 객체를 돌려주면 타입이 맞지 않습니다.
      const deny = (reason: string) =>
        new ToolMessage({ content: reason, tool_call_id: request.toolCall.id ?? "" });

      if (searchCount >= maxSearches) {
        // throw 하지 않습니다. 문자열로 알려줘야 모델이 대처합니다.
        return deny(
          `검색 예산(${maxSearches}회)을 모두 사용했습니다. 더 이상 검색할 수 없습니다. ` +
            "지금까지 찾은 정보로 결론을 정리하세요.",
        );
      }

      if (maxTokens !== undefined && totalTokens >= maxTokens) {
        return deny(
          `토큰 예산(${maxTokens.toLocaleString()})을 초과했습니다(현재 ${totalTokens.toLocaleString()}). ` +
            "검색을 중단하고 지금까지 찾은 정보로 결론을 정리하세요.",
        );
      }

      searchCount++;
      return handler(request);
    },
  });
}
```

### 검증

이 미들웨어는 모델 없이도 훅을 직접 불러 시험할 수 있습니다. `maxSearches: 2` 로 두고 4번 부르면:

**출력** (결정적입니다 — 모델을 안 부릅니다)

```
검색 1회차 -> 실제 검색 결과
검색 2회차 -> 실제 검색 결과
검색 3회차 -> 검색 예산(2회)을 모두 사용했습니다. 더 이상 검색할 수 없습니다. 지금까...
검색 4회차 -> 검색 예산(2회)을 모두 사용했습니다. 더 이상 검색할 수 없습니다. 지금까...
다른 도구  -> 실제 검색 결과 (예산 무관하게 통과해야 함)
```

3회차부터 실제 핸들러가 호출되지 않고, `read_file` 같은 다른 도구는 영향받지 않는 것을 확인하세요.

사용:

```ts
import { createDeepAgent } from "deepagents";
import { createSearchBudgetMiddleware } from "./budget.js";

function createBudgetedResearcher() {
  return createDeepAgent({
    model: RESEARCH_MODEL,
    systemPrompt: RESEARCHER_PROMPT,
    tools: [createSearchTool()],
    // 실행마다 새 팩토리 호출 → 카운터 격리
    middleware: [createSearchBudgetMiddleware({ maxSearches: 5, maxTokens: 100_000 })],
  });
}
```

### 왜 이렇게 했나

- **`wrapToolCall` 로 가로챕니다.** 도구 자체를 고치면 그 도구를 쓰는 모든 곳이 영향받습니다. 미들웨어는 이 에이전트에만 붙습니다.
- **`throw` 하지 않고 ToolMessage 를 돌려줍니다.** 이게 핵심입니다. throw 하면 루프가 죽어 그때까지 조사가 날아갑니다. 문자열이면 모델이 읽고 "그럼 정리하자"고 판단합니다.
- **메시지에 "지금까지 찾은 정보로 결론을 정리하세요"를 넣습니다.** "예산 소진"만 알려주면 모델이 뭘 해야 할지 몰라 헤맵니다. **다음 행동을 지시**해야 합니다.

> ⚠️ **함정 (카운터 공유)**: 이 과제의 진짜 함정입니다.
>
> ```ts
> // ❌ 나쁜 예 — 모듈 최상단에서 한 번만 생성
> const budget = createSearchBudgetMiddleware({ maxSearches: 5 });
> function createResearcher() {
>   return createDeepAgent({ middleware: [budget] });  // 모든 실행이 카운터 공유!
> }
> ```
>
> 첫 실행이 5회를 쓰면 두 번째 실행은 **시작하자마자 예산 소진**입니다. 에러는 안 납니다 — 그냥 검색을 한 번도 못 하고 "정보를 찾을 수 없었습니다"라고 답합니다. 병렬 조사(과제 3)와 합치면 더 심각합니다: 3개가 카운터를 공유해 서로의 예산을 갉아먹습니다. **실행마다 팩토리를 새로 부르세요.**

> 💡 진짜 격리가 필요하면 카운터를 클로저가 아니라 **상태(state)** 에 넣으세요. `stateSchema` 로 `searchCount` 필드를 만들면 스레드마다 자동으로 격리되고, 체크포인터에 저장되어 재개 시에도 이어집니다.

---

## 과제 5. 중단 후 재개 (10점)

### 정답 코드

먼저 설치:

```bash
npm install @langchain/langgraph-checkpoint-sqlite --workspace project
```

`ex/resumable.ts`

```ts
import "dotenv/config";
import { SqliteSaver } from "@langchain/langgraph-checkpoint-sqlite";
import { Command } from "@langchain/langgraph";
import { createDeepAgent } from "deepagents";
import { ORCHESTRATOR_PROMPT } from "../prompts.js";
import { allSubagents, ORCHESTRATOR_MODEL } from "../subagents.js";
import { readStateFile } from "../agent.js";

const argv = process.argv.slice(2);
const threadIdx = argv.indexOf("--thread");
// ⚠️ crypto.randomUUID() 를 기본값으로 두면 재개가 원천적으로 불가능합니다.
const threadId = threadIdx >= 0 ? argv[threadIdx + 1] : undefined;
if (!threadId) {
  console.error("--thread <id> 는 필수입니다. 재개하려면 같은 id 를 쓰세요.");
  process.exit(1);
}

// 디스크에 저장되는 체크포인터. 프로세스가 죽어도 남습니다.
const checkpointer = SqliteSaver.fromConnString("./research-checkpoints.sqlite");

const agent = createDeepAgent({
  model: ORCHESTRATOR_MODEL,
  systemPrompt: ORCHESTRATOR_PROMPT,
  tools: [],
  subagents: allSubagents,
  checkpointer,
  interruptOn: {
    write_file: {
      allowedDecisions: ["approve", "reject"],
      when: (request) => String(request.toolCall.args.file_path ?? "") === "/report.md",
    },
  },
});

const config = { configurable: { thread_id: threadId }, recursionLimit: 100 };

// 지금 이 스레드가 어떤 상태인지 먼저 확인합니다.
const snapshot = await agent.getState(config);
const hasHistory = snapshot.values && Object.keys(snapshot.values).length > 0;
const isPending = (snapshot.next?.length ?? 0) > 0;

console.log(`thread: ${threadId}`);
console.log(`  이전 기록: ${hasHistory ? "있음" : "없음"}`);
console.log(`  대기 중인 노드: ${snapshot.next?.join(", ") || "(없음)"}`);

let result;

if (!hasHistory) {
  // 신규 실행
  const question = argv.filter((a, i) => i !== threadIdx && i !== threadIdx + 1).join(" ").trim();
  console.log(`\n새 리서치 시작: ${question}\n`);
  result = await agent.invoke({ messages: [{ role: "user", content: question }] }, config);
} else if (isPending && argv.includes("--approve-pending")) {
  // 승인 대기 중이었던 것을 승인하며 재개
  console.log("\n승인 대기 지점부터 재개합니다.\n");
  result = await agent.invoke(new Command({ resume: { decisions: [{ type: "approve" }] } }), config);
} else {
  // 중간에 죽었던 것을 그냥 이어서 진행
  // 입력에 null 을 주면 "새 메시지 없이 마지막 지점부터 계속"이라는 뜻입니다.
  console.log("\n마지막 지점부터 이어서 진행합니다.\n");
  result = await agent.invoke(null, config);
}

const files = (result as { files?: Record<string, unknown> }).files;
console.log(readStateFile(files, "/report.md") ?? "(아직 보고서 없음 — 승인 대기 중일 수 있습니다)");
```

### 사용

```bash
# 1회차: 조사하다가 승인 대기 중 Ctrl+C
npx tsx step-12-final-project/ex/resumable.ts --thread my-research-1 "RAG와 파인튜닝의 차이는?"

# 2회차: 같은 thread → 승인 지점부터 이어짐
npx tsx step-12-final-project/ex/resumable.ts --thread my-research-1 --approve-pending
```

### 왜 이렇게 했나

- **`thread_id` 를 필수 인자로** 만들었습니다. 기본값을 `crypto.randomUUID()` 로 두면 매 실행이 새 대화가 되어 재개가 원천적으로 불가능합니다.
- **`getState` 로 상태를 먼저 확인**합니다. `next` 가 비어 있지 않으면 어딘가에서 멈춰 있다는 뜻입니다. 이걸 안 보면 "재개해야 할지 새로 시작해야 할지"를 코드가 알 수 없습니다.
- **`invoke(null, config)`** 는 "새 입력 없이 이어서"입니다. 여기에 메시지를 또 넣으면 대화에 사용자 발화가 하나 더 추가되어, 에이전트가 "또 질문했네?" 하고 처음부터 다시 합니다.

> ⚠️ **함정**: `thread_id` 만 고정하고 체크포인터를 `MemorySaver` 로 두면 **아무것도 안 됩니다.** 프로세스가 죽으면서 메모리가 통째로 사라졌으니까요. `thread_id` 고정과 영구 체크포인터는 **둘 다** 필요합니다. 하나만 하면 "왜 재개가 안 되지?"로 한참을 헤맵니다.

> 💡 SQLite 파일은 계속 자랍니다. 스레드마다 모든 체크포인트가 쌓입니다. 프로덕션이라면 오래된 스레드를 정리하는 작업이 필요합니다. LangSmith Deployments 를 쓰면 체크포인터를 자동으로 관리해 줍니다.

---

## 과제 6. 다른 도메인으로 이식 (10점)

코드 리뷰 에이전트로 이식한 예입니다.

`ex/code-review.ts`

```ts
import { createDeepAgent, FilesystemBackend } from "deepagents";
import type { SubAgent } from "deepagents";
import { ORCHESTRATOR_MODEL, RESEARCH_MODEL, CRITIQUE_MODEL } from "../subagents.js";

const TODAY = new Date().toISOString().split("T")[0];

/* ===== 프롬프트 3개를 도메인에 맞게 다시 씀 ===== */

const REVIEW_ORCHESTRATOR_PROMPT = `당신은 코드 리뷰 오케스트레이터입니다. 오늘 날짜는 ${TODAY} 입니다.

직접 리뷰하지 마세요. 리뷰는 reviewer-subagent 에게 위임하고, 당신은 종합만 합니다.

# 절차
1. write_file 로 리뷰 대상 범위와 기준을 \`/scope.md\` 에 저장합니다. (← /question.txt 에 해당하는 기준점)
2. write_todos 로 리뷰 단계를 3~6개로 쪼갭니다. 파일이나 관심사 단위로 나누세요.
3. task() 로 reviewer-subagent 에게 위임합니다. 한 번에 하나의 파일 또는 하나의 관심사만 주세요.
   지적 결과는 \`/issues/<파일-슬러그>.md\` 에 저장하라고 지시하세요.
4. task() 로 judge-subagent 를 호출해 지적이 타당한지 심사시킵니다.
5. \`/issues/\` 를 모두 읽고 종합해 \`/review.md\` 에 씁니다.
6. \`/scope.md\` 를 다시 읽고 범위를 다 덮었는지 확인합니다.

# 리뷰 보고서 규칙
- 심각도(critical / major / minor)로 분류합니다.
- 각 지적에는 반드시 \`파일:줄번호\` 를 답니다. ← 리서치의 '인용'에 해당합니다.
- 근거 없는 취향 지적은 쓰지 마세요. "이렇게 하는 게 더 예쁘다"는 리뷰가 아닙니다.
- 코드를 **고치지 마세요.** 지적만 합니다.`;

const REVIEWER_PROMPT = `당신은 코드 리뷰어입니다. 오늘 날짜는 ${TODAY} 입니다.

read_file, grep, glob 으로 코드를 읽고 문제를 찾습니다. 코드를 수정할 권한은 없습니다.

# 예산
- 파일 하나당 read_file/grep 을 합쳐 최대 8회.
- 같은 파일을 두 번 읽지 마세요.

# 무엇을 찾나 (우선순위 순)
1. 버그 — 잘못된 로직, 처리 안 된 에러, 경계 조건
2. 보안 — 인젝션, 시크릿 하드코딩, 검증 누락
3. 성능 — N+1, 불필요한 반복, 누수
4. 가독성 — 이름, 죽은 코드

취향 문제(따옴표 스타일 등)는 **적지 마세요.** 포매터가 할 일입니다.

# 저장
write_file 로 \`/issues/<파일-슬러그>.md\` 에 저장하세요.

\`\`\`markdown
# <파일 경로>

## 지적
- **[critical] 제목** (\`파일:줄번호\`)
  무엇이 문제이고 왜 문제인가.

## 확인하지 못한 것
(읽지 못한 부분. 없으면 "없음")
\`\`\`

부모에게는 요약만 돌려주세요. 코드 원문을 붙여넣지 마세요.`;

const JUDGE_PROMPT = `당신은 코드 리뷰 지적을 심사합니다. 오늘 날짜는 ${TODAY} 입니다.

당신에게는 코드 수정 권한이 없습니다. read_file 로 읽고 판정만 합니다.

# 절차
1. ls 로 \`/issues/\` 를 봅니다.
2. 각 파일을 읽습니다.
3. 지적된 \`파일:줄번호\` 를 read_file 로 **직접 확인**합니다.

# 심사 기준
- **위치 오류**: 지적한 줄번호에 그 코드가 실제로 없는가? (← 리서치의 '출처 유령')
- **오해**: 리뷰어가 코드를 잘못 읽었는가?
- **취향**: 버그가 아니라 취향 문제를 지적했는가?
- **범위 밖**: /scope.md 의 범위를 벗어난 지적인가?
- **누락**: 명백한 문제를 놓쳤는가?

# 출력
판정: PASS 또는 REVISE
REVISE 면 각 항목마다 - 문제 / 위치 / 조치(재리뷰 주제 한 문장).
칭찬하지 마세요.`;

/* ===== 서브에이전트 ===== */

const reviewerSubagent: SubAgent = {
  name: "reviewer-subagent",
  description:
    "코드 파일 하나 또는 하나의 관심사를 리뷰하고, 지적을 /issues/ 에 저장한 뒤 요약을 돌려줍니다. " +
    "한 번에 하나의 파일만 주세요. 파일 경로와 리뷰 관점을 함께 지시해야 합니다.",
  systemPrompt: REVIEWER_PROMPT,
  model: RESEARCH_MODEL,
  // ⚠️ 리뷰어는 코드를 고치면 안 됩니다. 권한으로 강제합니다.
  permissions: [
    { operations: ["read"], paths: ["/**"] },
    // 지적은 저장해야 하므로 /issues/ 만 쓰기 허용 — 전역 deny 보다 **앞**에 와야 합니다.
    { operations: ["write"], paths: ["/issues/**"] },
    { operations: ["write"], paths: ["/**"], mode: "deny" },
  ],
};

const judgeSubagent: SubAgent = {
  name: "judge-subagent",
  description:
    "/issues/ 의 지적이 타당한지 심사해 PASS 또는 REVISE 를 판정합니다. 코드를 수정하지 않습니다.",
  systemPrompt: JUDGE_PROMPT,
  model: CRITIQUE_MODEL,
  permissions: [
    { operations: ["read"], paths: ["/**"] },
    { operations: ["write"], paths: ["/**"], mode: "deny" },
  ],
};

export function createCodeReviewAgent(rootDir: string) {
  return createDeepAgent({
    model: ORCHESTRATOR_MODEL,
    systemPrompt: REVIEW_ORCHESTRATOR_PROMPT,
    tools: [], // 검색 없음 — 도구는 파일시스템뿐
    subagents: [reviewerSubagent, judgeSubagent],
    // 진짜 코드를 읽어야 하므로 FilesystemBackend
    backend: new FilesystemBackend({ rootDir }),
  });
}
```

### 매핑

리서치 → 코드 리뷰의 대응 관계를 보면 아키텍처가 그대로인 게 보입니다.

| 리서치 | 코드 리뷰 |
|---|---|
| `/question.txt` (기준점) | `/scope.md` (리뷰 범위와 기준) |
| `/findings/*.md` | `/issues/*.md` |
| `/report.md` | `/review.md` |
| `web_search` | `read_file` / `grep` / `glob` |
| 인용 `[n]` → URL | 지적 → `파일:줄번호` |
| 출처 유령 (없는 URL) | 위치 오류 (없는 줄번호) |
| 검색 예산 5회 | 파일당 읽기 8회 |
| 비평가에게 검색 없음 | 심사자에게 수정 권한 없음 |

### 왜 이렇게 했나

- **기준점 파일이 무엇인가**가 이 과제의 핵심입니다. 리서치에서는 원 질문이었고, 코드 리뷰에서는 "무엇을 어떤 기준으로 볼 것인가"입니다. 이게 없으면 리뷰어가 범위를 넘어 온 저장소를 리뷰하기 시작합니다.
- **`permissions` 로 쓰기를 막았습니다.** "코드를 고치지 마세요"를 프롬프트에 쓰는 것으로는 부족합니다. 규칙은 **선언 순서대로 평가되고 첫 매치가 이깁니다** — 그래서 `/issues/**` 허용을 전역 deny **앞에** 둬야 합니다. 순서를 뒤집으면 리뷰어가 지적을 저장조차 못 합니다.

> ⚠️ **함정**: `FilesystemBackend` 는 **진짜 디스크**입니다. `permissions` 없이 리뷰 에이전트를 돌리면, 리뷰어가 "고쳐 드릴게요" 하며 **실제 소스 코드를 수정합니다.** 되돌릴 수 없습니다. `StateBackend` 와 달리 안전망이 없으니, `FilesystemBackend` 를 쓸 때는 `permissions` 를 반드시 함께 거세요.

---

## 과제 7. 평가 하네스 (10점)

### 정답 코드

`ex/eval.ts`

```ts
import { buildResearchAgent, readStateFile } from "../agent.js";
import { verifyCitations, collectUrlsFromFindings } from "./citations.js";

export interface EvalResult {
  question: string;
  passed: boolean;
  checks: Record<string, boolean>;
  metrics: {
    reportLength: number;
    sourceCount: number;
    citationCount: number;
    findingsCount: number;
    durationMs: number;
  };
}

const SELF_REFERENCE = /제가\s*조사|찾아보니|검색해\s*보니|제가\s*알아본|저는\s*.{0,10}했습니다/;

async function evalOne(question: string): Promise<EvalResult> {
  const started = Date.now();
  const agent = buildResearchAgent();
  const result = await agent.invoke(
    { messages: [{ role: "user", content: question }] },
    { configurable: { thread_id: crypto.randomUUID() }, recursionLimit: 100 },
  );
  const durationMs = Date.now() - started;

  const files = (result as { files?: Record<string, unknown> }).files ?? {};
  const report = readStateFile(files, "/report.md") ?? "";
  const allowed = collectUrlsFromFindings(files);
  const issues = verifyCitations(report, allowed);
  const findingsPaths = Object.keys(files).filter((p) => p.startsWith("/findings/"));

  const checks: Record<string, boolean> = {
    report_exists: report.length > 0,
    has_sources: /^###\s*출처\s*$/m.test(report),
    no_ghost_citations: !issues.some((i) => i.kind === "ghost_citation"),
    no_hallucinated_urls: !issues.some((i) => i.kind === "hallucinated_url"),
    question_saved: readStateFile(files, "/question.txt") !== undefined,
    findings_exist: findingsPaths.length > 0,
    no_self_reference: !SELF_REFERENCE.test(report),
  };

  return {
    question,
    passed: Object.values(checks).every(Boolean),
    checks,
    metrics: {
      reportLength: report.length,
      sourceCount: (report.match(/^\s*\[\d+\]\s*.+?:\s*https?:\/\//gm) ?? []).length,
      citationCount: new Set(report.match(/\[\d+\]/g) ?? []).size,
      findingsCount: findingsPaths.length,
      durationMs,
    },
  };
}

/**
 * 각 질문을 repeats 회 반복합니다.
 *
 * 모델은 비결정적이라 1회 결과로 "좋아졌다"고 하면 그냥 운입니다.
 * 3회 이상 돌려 통과율을 봐야 프롬프트 변경의 효과를 말할 수 있습니다.
 */
export async function runEval(questions: string[], repeats = 3): Promise<EvalResult[]> {
  const out: EvalResult[] = [];
  for (const q of questions) {
    for (let i = 0; i < repeats; i++) {
      out.push(await evalOne(q));
    }
  }
  return out;
}

export function printReport(results: EvalResult[]): void {
  const checkNames = Object.keys(results[0]?.checks ?? {});

  console.log("\n검사별 통과율");
  console.log("-".repeat(52));
  for (const name of checkNames) {
    const passed = results.filter((r) => r.checks[name]).length;
    const rate = (passed / results.length) * 100;
    const bar = "█".repeat(Math.round(rate / 5)).padEnd(20, "░");
    console.log(`  ${name.padEnd(22)} ${bar} ${rate.toFixed(0).padStart(3)}%`);
  }

  const overall = results.filter((r) => r.passed).length;
  console.log("-".repeat(52));
  console.log(`  전체 통과: ${overall}/${results.length} (${((overall / results.length) * 100).toFixed(0)}%)`);

  const avg = (f: (r: EvalResult) => number) =>
    (results.reduce((s, r) => s + f(r), 0) / results.length).toFixed(0);
  console.log(`  평균 보고서 길이: ${avg((r) => r.metrics.reportLength)}자`);
  console.log(`  평균 출처 수    : ${avg((r) => r.metrics.sourceCount)}`);
  console.log(`  평균 소요       : ${avg((r) => r.metrics.durationMs)}ms`);
}

// 질문 유형을 섞습니다: 단순 사실 / 비교 / 목록
const QUESTIONS = [
  "RAG(검색 증강 생성)란 무엇인가?",
  "RAG와 파인튜닝의 주요 차이점은 무엇인가?",
  "RAG 시스템의 비용을 줄이는 기법들을 나열하라.",
];

if (import.meta.url === `file://${process.argv[1]}`) {
  // ⚠️ 목 검색으로 돌리세요. TAVILY_API_KEY 가 있으면 지워야 재현이 됩니다.
  delete process.env.TAVILY_API_KEY;
  printReport(await runEval(QUESTIONS, 3));
}
```

**출력 예시** (모델 호출이므로 통과율과 수치는 매번 다릅니다 — 표 **형식**은 결정적입니다)

```
검사별 통과율
----------------------------------------------------
  report_exists          ████████████████████ 100%
  has_sources            ████████████████████ 100%
  no_ghost_citations     ██████████████████░░  89%
  no_hallucinated_urls   ████████████████████ 100%
  question_saved         ████████████████████ 100%
  findings_exist         ████████████████████ 100%
  no_self_reference      ████████████████░░░░  78%
----------------------------------------------------
  전체 통과: 6/9 (67%)
  평균 보고서 길이: 4213자
  평균 출처 수    : 4
  평균 소요       : 38104ms
```

### 왜 이렇게 했나

- **7가지 검사가 전부 결정적**입니다. 모델 출력이 매번 달라도 이 검사들은 항상 같은 방식으로 판정합니다. 그래서 CI 에 넣을 수 있습니다.
- **`delete process.env.TAVILY_API_KEY`** 로 목 검색을 강제합니다. 이게 없으면 실제 웹 검색 결과가 매번 달라져서, 통과율이 떨어졌을 때 **프롬프트 탓인지 검색 결과 탓인지 구분할 수 없습니다.** 평가에서 통제 변수를 고정하는 것은 기본입니다.
- **`repeats = 3`**. 1회로는 아무것도 말할 수 없습니다.

> 💡 이 하네스로 다른 과제의 효과를 숫자로 증명하세요. 과제 1(인용 강제)을 적용하기 전후로 `no_ghost_citations` 가 89% → 100% 가 되는 것을 보이면, 그건 느낌이 아니라 **측정**입니다.

> ⚠️ **함정**: `no_self_reference` 같은 정규식 검사는 **거짓 양성**이 납니다. "저는"이 인용된 문장 안에 들어 있을 수도 있습니다. 검사를 추가할 때마다 "이 검사가 멀쩡한 보고서를 떨어뜨리지는 않는가"를 확인하세요. 평가 하네스 자체에도 버그가 있습니다.

---

## 과제 8. 웹 UI (5점)

### 정답 코드 (Node 내장 http + SSE)

`ex/server.ts`

```ts
import "dotenv/config";
import { createServer } from "node:http";
import { buildResearchAgent, readStateFile } from "../agent.js";

const PORT = 3000;

const PAGE = `<!doctype html>
<html lang="ko"><meta charset="utf-8">
<title>딥 리서치</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 820px; margin: 2rem auto; padding: 0 1rem; }
  #log { background: #111; color: #0f0; padding: 1rem; height: 320px; overflow-y: auto;
         font-family: ui-monospace, monospace; font-size: 12px; white-space: pre-wrap; }
  #report { border: 1px solid #ddd; padding: 1rem; margin-top: 1rem; white-space: pre-wrap; }
  input { width: 70%; padding: .5rem; }
  button { padding: .5rem 1rem; }
</style>
<h1>딥 리서치 에이전트</h1>
<input id="q" value="RAG와 파인튜닝의 차이는?">
<button onclick="run()">조사</button>
<h3>진행 상황</h3>
<div id="log"></div>
<h3>보고서</h3>
<div id="report"></div>
<script>
function run() {
  const log = document.getElementById('log');
  const report = document.getElementById('report');
  log.textContent = ''; report.textContent = '';
  const es = new EventSource('/run?q=' + encodeURIComponent(document.getElementById('q').value));
  es.addEventListener('progress', (e) => {
    log.textContent += JSON.parse(e.data).line + '\\n';
    log.scrollTop = log.scrollHeight;   // 항상 최신 줄이 보이게
  });
  es.addEventListener('report', (e) => { report.textContent = JSON.parse(e.data).report; });
  es.addEventListener('done', () => es.close());
}
</script>`;

createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", \`http://\${req.headers.host}\`);

  if (url.pathname === "/") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(PAGE);
    return;
  }

  if (url.pathname !== "/run") {
    res.writeHead(404).end();
    return;
  }

  // SSE 헤더
  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
  });
  const send = (event: string, data: unknown) =>
    res.write(\`event: \${event}\\ndata: \${JSON.stringify(data)}\\n\\n\`);

  const question = url.searchParams.get("q") ?? "";
  const agent = buildResearchAgent();

  try {
    const stream = await agent.streamEvents(
      { messages: [{ role: "user", content: question }] },
      { version: "v3", configurable: { thread_id: crypto.randomUUID() }, recursionLimit: 100 },
    );

    // run.subagents 로 서브에이전트 활동을 따로 잡습니다 — UI 의 핵심.
    void (async () => {
      for await (const sub of stream.subagents) {
        send("progress", { line: \`  ↳ [\${sub.name}] 작업 중\` });
      }
    })();

    for await (const message of stream.messages) {
      for await (const chunk of message.toolCalls) {
        // ⚠️ 도구 호출 인자는 조각나서 옵니다. 여기서 JSON.parse 하면 터집니다.
        //    이름만 씁니다.
        send("progress", { line: \`도구 호출: \${chunk.name ?? "(...)"}\` });
      }
    }

    const result = await stream.result;
    const files = (result as { files?: Record<string, unknown> }).files;
    send("report", { report: readStateFile(files, "/report.md") ?? "(보고서 없음)" });
  } catch (e) {
    send("progress", { line: \`에러: \${String(e)}\` });
  } finally {
    send("done", {});
    res.end();
  }
}).listen(PORT, () => console.log(\`http://localhost:\${PORT}\`));
```

```bash
npx tsx step-12-final-project/ex/server.ts
# → http://localhost:3000
```

### 왜 이렇게 했나

- **SSE 를 골랐습니다.** 서버 → 클라이언트 단방향이면 WebSocket 이 과합니다. `EventSource` 는 브라우저 내장이고 자동 재연결까지 해 줍니다.
- **`stream.subagents` 를 따로 구독**합니다. 이게 이 UI 의 핵심입니다 — "research-subagent 가 작업 중"을 보여줄 수 있어서, 사용자가 3분간 기다리면서도 죽지 않았다는 걸 압니다.
- **도구 호출 인자를 파싱하지 않습니다.** 이름만 씁니다. 아래 함정 참고.

> ⚠️ **함정 (부분 JSON)**: 스트리밍 중 도구 호출 인자는 **조각나서** 옵니다. `{"query": "RA` 같은 부분 문자열이 그때그때 도착합니다. 이걸 받는 족족 `JSON.parse` 하면 `SyntaxError: Unexpected end of JSON input` 이 납니다. 조각을 전부 모아 완성된 뒤에 파싱하거나, 위처럼 **완성이 보장된 필드(이름)만** 쓰세요.

> ⚠️ **함정 (HITL 과 SSE)**: 이 예제는 승인 버튼을 뺐습니다. 웹에서 HITL 을 하려면 인터럽트 상태를 **서버가 요청 사이에 들고 있어야** 하는데, SSE 응답이 끝나면 그 컨텍스트가 사라집니다. 과제 5의 영구 체크포인터가 필요합니다: 인터럽트가 걸리면 `thread_id` 를 클라이언트에 내려주고, 승인 버튼이 `/approve?thread=...` 를 호출해 `Command` 로 재개하는 구조가 됩니다. 정식 프로덕션이라면 LangSmith Deployments + `useStream` 훅이 이걸 다 해 줍니다.

---

## 총평 — 무엇을 배웠나

8과제를 관통하는 원칙이 하나 있습니다.

**프롬프트는 부탁이고, 구조는 강제다.**

| 과제 | 부탁 (전) | 강제 (후) |
|---|---|---|
| 1 | "인용을 달아라" | `verifyCitations` 로 검사하고 실패하면 재작성 |
| 2 | "구멍이 있으면 재조사해라" | 코드가 루프를 돌림 |
| 3 | "병렬로 조사해라" | `Promise.all` 로 실제 병렬 |
| 4 | "검색 5회까지만" | 미들웨어가 6번째를 차단 |
| 5 | (없음) | 영구 체크포인터 |
| 6 | "코드를 고치지 마라" | `permissions` 로 쓰기 금지 |
| 7 | "잘 됐나 봐줘" | 결정적 불변식 7개 |
| 8 | (없음) | 스트리밍으로 진행 노출 |

에이전트가 말을 안 들을 때 프롬프트를 더 강하게 쓰는 것은 대개 지는 싸움입니다. **할 수 없게 만드는 것**이 언제나 확실합니다. 프롬프트 튜닝은 구조로 해결이 안 될 때의 차선책입니다.

그리고 **에러가 안 나는 버그**를 계속 만났습니다. 카운터 공유, 순서 의존, 부분 JSON, 파싱 실패를 PASS 로 오인, 목/실물 불일치. 이것들은 전부 조용히 잘못 돕니다. 에이전트를 만드는 능력보다 **틀린 걸 알아채는 능력**이 중요한 이유입니다.

---

← [문제로 돌아가기](./problems.md) · [강의 보기](./index.md)
