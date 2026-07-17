/**
 * Step 12 — 종합 프로젝트: 딥 리서치 에이전트
 * 검색 도구 — 목(mock) 버전과 Tavily 버전.
 *
 * 실행: 이 파일은 직접 실행하지 않고 agent.ts 에서 import 합니다.
 *      목 검색만 단독 확인하려면: npx tsx docs/reference/deepagent/step-12-final-project/tools.ts
 */
import "dotenv/config";
import { tool } from "langchain";
import * as z from "zod";
import { MOCK_CORPUS, type CorpusDoc } from "./mock-corpus.js";

/* ===== [12-2] 검색 결과 포맷 — 두 도구가 같은 모양으로 돌려준다 ===== */

/**
 * 검색 결과 한 건. 목이든 Tavily든 이 모양으로 정규화해서 돌려줍니다.
 * 도구가 돌려주는 "문자열"의 형식을 고정해야 서브에이전트 프롬프트가 안정적으로 동작합니다.
 */
export interface SearchHit {
  url: string;
  title: string;
  content: string;
}

/**
 * 검색 결과를 모델이 읽을 문자열로 만듭니다.
 *
 * URL 을 결과마다 눈에 띄게 박아 넣는 것이 핵심입니다. 모델은 프롬프트에서 본 것만
 * 인용할 수 있습니다 — 결과에 URL 이 없으면 인용을 아무리 시켜도 지어냅니다.
 */
export function formatHits(query: string, hits: SearchHit[]): string {
  if (hits.length === 0) {
    // 빈 결과일 때 빈 문자열을 돌려주면 모델이 "도구가 고장났나?"를 판단하지 못하고
    // 같은 질의를 반복합니다. 그래서 "없다"는 사실을 문장으로 말해 줍니다.
    return `'${query}' 에 대한 검색 결과가 없습니다. 다른 키워드로 다시 시도하세요.`;
  }
  const body = hits
    .map(
      (h, i) =>
        `## [결과 ${i + 1}] ${h.title}\n**URL:** ${h.url}\n\n${h.content}\n\n---`,
    )
    .join("\n");
  return `'${query}' 에 대해 ${hits.length}건을 찾았습니다:\n\n${body}`;
}

/* ===== [12-2] 목 검색 도구 — API 키 없이 동작 ===== */

/** 한국어/영어를 대충 토큰으로 쪼갭니다. 목 검색용이라 정교할 필요는 없습니다. */
function tokenize(s: string): string[] {
  return s
    .toLowerCase()
    .split(/[^a-z0-9가-힣]+/)
    .filter((t) => t.length > 1);
}

/**
 * 아주 단순한 키워드 점수. 질의 토큰이 제목에 있으면 3점, 본문에 있으면 1점.
 * 임베딩 검색이 아니라 **결정적(deterministic)** 이라, 실습에서 매번 같은 결과가 나옵니다.
 */
function scoreDoc(queryTokens: string[], doc: CorpusDoc): number {
  const title = doc.title.toLowerCase();
  const content = doc.content.toLowerCase();
  let score = 0;
  for (const t of queryTokens) {
    if (title.includes(t)) score += 3;
    if (content.includes(t)) score += 1;
  }
  return score;
}

/**
 * 목 검색 도구.
 *
 * 외부 네트워크를 전혀 타지 않고 mock-corpus.ts 의 문서를 키워드로 검색합니다.
 * API 키가 없어도, 비행기 안에서도, 요금 걱정 없이 이 코스를 끝까지 실습할 수 있습니다.
 *
 * 이름(`web_search`)과 스키마를 Tavily 버전과 **완전히 동일하게** 맞춘 것이 중요합니다.
 * 그래야 프롬프트를 한 글자도 안 고치고 목 ↔ 실제를 갈아끼울 수 있습니다.
 */
export const mockSearch = tool(
  async ({
    query,
    maxResults = 3,
    topic = "general",
  }: {
    query: string;
    maxResults?: number;
    topic?: "general" | "news" | "finance";
  }) => {
    const tokens = tokenize(query);
    const pool =
      // topic 이 general 이면 전체를 뒤지고, 아니면 해당 topic 만 봅니다.
      topic === "general" ? MOCK_CORPUS : MOCK_CORPUS.filter((d) => d.topic === topic);

    const hits = pool
      .map((doc) => ({ doc, score: scoreDoc(tokens, doc) }))
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, maxResults)
      .map(({ doc }) => ({ url: doc.url, title: doc.title, content: doc.content }));

    return formatHits(query, hits);
  },
  {
    name: "web_search",
    // 설명이 곧 프롬프트입니다. "언제 부르는가"를 여기에 적어야 모델이 제때 부릅니다.
    description:
      "웹에서 주제에 대한 정보를 검색합니다. 관련 문서의 제목, URL, 본문을 돌려줍니다. " +
      "사실 확인이 필요한 모든 주장에 대해 호출하세요.",
    schema: z.object({
      query: z.string().describe("검색어. 구체적이고 서술적으로 작성하세요."),
      maxResults: z
        .number()
        .optional()
        .describe("돌려받을 최대 결과 수 (기본 3)"),
      topic: z
        .enum(["general", "news", "finance"])
        .optional()
        .describe("주제 필터. 'general'(기본) / 'news'(시사) / 'finance'(금융)"),
    }),
  },
);

/* ===== [12-2] Tavily 검색 도구 — 진짜 웹 ===== */

/**
 * URL 하나의 본문을 가져옵니다. Tavily 가 돌려주는 것은 URL 목록과 짧은 스니펫이라,
 * 깊은 조사를 하려면 본문을 직접 받아와야 합니다.
 *
 * timeout 이 없으면 응답 없는 서버 하나가 에이전트 전체를 멈춥니다. AbortController 필수.
 */
async function fetchWebpageContent(url: string, timeoutMs = 10_000): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
      },
      signal: controller.signal,
    });
    if (!response.ok) {
      return `(${url} 을 가져오지 못했습니다: HTTP ${response.status})`;
    }
    const text = await response.text();
    // 본문을 통째로 넣으면 HTML 태그까지 토큰을 잡아먹습니다. 앞부분만 자릅니다.
    return text.slice(0, 8_000);
  } catch (e) {
    // 실패를 throw 하지 않고 문자열로 돌려주는 게 포인트입니다.
    // 도구가 throw 하면 에이전트 루프가 통째로 죽습니다. 문자열이면 모델이 읽고 대처합니다.
    return `(${url} 을 가져오지 못했습니다: ${String(e)})`;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Tavily 검색 도구. TAVILY_API_KEY 가 필요합니다(무료 티어 있음).
 * 스키마와 이름은 mockSearch 와 동일합니다.
 */
export const tavilySearch = tool(
  async ({
    query,
    maxResults = 3,
    topic = "general",
  }: {
    query: string;
    maxResults?: number;
    topic?: "general" | "news" | "finance";
  }) => {
    const apiKey = process.env.TAVILY_API_KEY;
    if (!apiKey) {
      return "TAVILY_API_KEY 가 설정되지 않아 검색할 수 없습니다.";
    }
    try {
      const response = await fetch("https://api.tavily.com/search", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify({ query, max_results: maxResults, topic }),
      });
      if (!response.ok) {
        return `검색 실패: Tavily HTTP ${response.status}`;
      }
      const data = (await response.json()) as {
        results?: Array<{ url: string; title: string }>;
      };
      const results = data.results ?? [];

      // 본문 fetch 는 서로 독립이므로 병렬로 처리합니다. 순차로 하면 결과 수만큼 느려집니다.
      const hits: SearchHit[] = await Promise.all(
        results.map(async (r) => ({
          url: r.url,
          title: r.title,
          content: await fetchWebpageContent(r.url),
        })),
      );
      return formatHits(query, hits);
    } catch (e) {
      return `검색 실패: ${String(e)}`;
    }
  },
  {
    name: "web_search",
    description:
      "웹에서 주제에 대한 정보를 검색합니다. 관련 문서의 제목, URL, 본문을 돌려줍니다. " +
      "사실 확인이 필요한 모든 주장에 대해 호출하세요.",
    schema: z.object({
      query: z.string().describe("검색어. 구체적이고 서술적으로 작성하세요."),
      maxResults: z
        .number()
        .optional()
        .describe("돌려받을 최대 결과 수 (기본 3)"),
      topic: z
        .enum(["general", "news", "finance"])
        .optional()
        .describe("주제 필터. 'general'(기본) / 'news'(시사) / 'finance'(금융)"),
    }),
  },
);

/* ===== [12-2] 자동 선택 ===== */

/**
 * TAVILY_API_KEY 가 있으면 진짜 검색을, 없으면 목 검색을 돌려줍니다.
 * 두 도구의 이름·스키마가 같기 때문에 나머지 코드는 어느 쪽인지 알 필요가 없습니다.
 */
export function createSearchTool() {
  return process.env.TAVILY_API_KEY ? tavilySearch : mockSearch;
}

/** 지금 어떤 검색을 쓰는지 사람이 읽을 이름. CLI 배너에 씁니다. */
export function searchToolLabel(): string {
  return process.env.TAVILY_API_KEY ? "Tavily (실제 웹)" : "Mock (오프라인 코퍼스)";
}

/* ===== 단독 실행: 목 검색 동작 확인 ===== */

// 이 파일을 직접 실행했을 때만 아래가 돕니다. import 될 때는 실행되지 않습니다.
if (import.meta.url === `file://${process.argv[1]}`) {
  const out = await mockSearch.invoke({
    query: "RAG와 파인튜닝의 비용 차이",
    maxResults: 2,
  });
  console.log(out);
}
