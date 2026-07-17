/**
 * Step 05 — 구조화된 출력 (zod)
 * 실행: npx tsx docs/reference/langchain/step-05-structured-output/practice.ts
 *
 * 본문 5-1 ~ 5-9 의 예제를 순서대로 담았습니다.
 * 블록 주석 [5-N] 번호가 교재 소제목과 1:1 대응합니다.
 *
 * 준비물: .env 에 ANTHROPIC_API_KEY=sk-ant-...
 *   (OpenAI 로 바꾸려면 MODEL 상수를 "openai:gpt-5.5" 로, OPENAI_API_KEY 를 설정)
 */
import "dotenv/config";
import * as z from "zod";
import {
  createAgent,
  initChatModel,
  toolStrategy,
  providerStrategy,
  tool,
} from "langchain";
import type { AIMessage } from "@langchain/core/messages";

/** 이 파일 전체가 쓰는 기본 모델. OpenAI 대안: "openai:gpt-5.5" */
const MODEL = "anthropic:claude-sonnet-4-6";

/** 결과를 보기 좋게 찍는 로컬 헬퍼 (외부 의존 없음) */
function show(label: string, value: unknown): void {
  console.log(`\n=== ${label} ===`);
  console.dir(value, { depth: null });
}

/* ===== [5-1] 왜 구조화 출력인가 — 자유 텍스트 파싱의 지옥 ===== */

async function step5_1(): Promise<void> {
  const model = await initChatModel(MODEL);

  // (A) 구조화 없이: 모델은 "말"을 한다. 우리는 그 말을 파싱해야 한다.
  const raw = await model.invoke(
    "다음 리뷰의 별점(1~5)과 감정을 알려줘: '배송은 빨랐는데 가격이 너무 비싸요. 그래도 품질은 만족.'",
  );
  show("[5-1] 자유 텍스트 응답", raw.content);

  // 이 텍스트에서 별점을 뽑으려면 결국 이런 코드를 쓰게 됩니다.
  const text = typeof raw.content === "string" ? raw.content : JSON.stringify(raw.content);
  const guessed = text.match(/([1-5])\s*(?:점|\/\s*5|stars?)/)?.[1];
  show("[5-1] 정규식으로 긁어낸 별점 (신뢰할 수 없음)", guessed ?? "파싱 실패");

  // 문제: 모델이 "다섯 점" 이라고 쓰면? "★★★★☆" 로 쓰면? 영어로 답하면?
  // 정규식은 프롬프트가 바뀔 때마다 깨집니다. 그래서 구조화 출력이 필요합니다.

  // (B) 구조화 출력: 모델이 스키마에 맞는 객체를 준다.
  const Review = z.object({
    rating: z.number().min(1).max(5).describe("별점 1~5"),
    sentiment: z.enum(["positive", "negative", "neutral"]).describe("전체 감정"),
  });

  const structured = await model.withStructuredOutput(Review).invoke(
    "다음 리뷰를 분석해: '배송은 빨랐는데 가격이 너무 비싸요. 그래도 품질은 만족.'",
  );
  show("[5-1] 구조화 응답 (파싱 불필요)", structured);
  // structured.rating 은 number 타입. TypeScript 가 알고 있습니다.
}

/* ===== [5-2] zod 스키마 기초 — 에이전트 개발자에게 필요한 만큼 ===== */

function step5_2(): void {
  // 에이전트 스키마에서 실제로 쓰는 건 이 정도가 전부입니다.
  const Ticket = z.object({
    // string / number — 가장 기본
    title: z.string().describe("티켓 제목. 한 문장, 40자 이내."),
    priority: z.number().min(1).max(5).describe("우선순위. 1=가장 급함, 5=여유."),

    // enum — 모델의 선택지를 강제한다. 분류 작업의 핵심.
    category: z
      .enum(["bug", "feature", "question", "billing"])
      .describe("티켓 분류. 넷 중 하나만."),

    // array — 개수 제한도 걸 수 있다
    tags: z.array(z.string()).max(3).describe("검색용 태그. 소문자, 1~2단어."),

    // optional — "키가 없어도 된다" (undefined 허용)
    assignee: z.string().optional().describe("담당자 이름. 글에 없으면 생략."),

    // nullable — "키는 있는데 값이 null 이어도 된다"
    dueDate: z
      .string()
      .nullable()
      .describe("마감일 YYYY-MM-DD. 글에 없으면 반드시 null."),

    // 중첩 객체 — 얕게 유지할 것 (5-2 함정 참고)
    reporter: z
      .object({
        name: z.string().describe("신고자 이름"),
        email: z.string().describe("신고자 이메일. 없으면 빈 문자열."),
      })
      .describe("신고자 정보"),
  });

  // zod 스키마는 런타임 검증기이자 TypeScript 타입입니다.
  type Ticket = z.infer<typeof Ticket>;

  // 모델 없이도 로컬에서 바로 검증해볼 수 있습니다 — 스키마 개발 시 필수 습관.
  const ok: Ticket = {
    title: "로그인 버튼이 안 눌림",
    priority: 1,
    category: "bug",
    tags: ["login", "ui"],
    dueDate: null,
    reporter: { name: "김민수", email: "kim@example.com" },
    // assignee 는 optional 이라 생략 가능
  };
  show("[5-2] safeParse 성공", Ticket.safeParse(ok).success);

  // 일부러 틀린 값 — 모델이 이런 걸 뱉었을 때 무슨 에러가 나는지 미리 봅니다.
  const bad = { ...ok, priority: 9, category: "urgent" };
  const result = Ticket.safeParse(bad);
  show(
    "[5-2] safeParse 실패 이슈",
    result.success ? null : result.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`),
  );

  // optional vs nullable 의 실제 차이 (provider 별 동작 차이는 본문 함정 참고)
  const Opt = z.object({ v: z.string().optional() });
  const Nul = z.object({ v: z.string().nullable() });
  show("[5-2] optional: 키 생략 OK", Opt.safeParse({}).success); // true
  show("[5-2] optional: null 은 거부", Opt.safeParse({ v: null }).success); // false
  show("[5-2] nullable: 키 생략은 거부", Nul.safeParse({}).success); // false
  show("[5-2] nullable: null 은 OK", Nul.safeParse({ v: null }).success); // true
}

/* ===== [5-3] .withStructuredOutput(schema) — 모델 레벨 ===== */

async function step5_3(): Promise<void> {
  const model = await initChatModel(MODEL);

  const Movie = z.object({
    title: z.string().describe("영화 제목"),
    year: z.number().describe("개봉 연도"),
    director: z.string().describe("감독 이름"),
    rating: z.number().describe("10점 만점 평점"),
  });

  // withStructuredOutput 은 "구조화된 응답을 주는 새 모델"을 반환합니다. 원본은 안 변합니다.
  const modelWithStructure = model.withStructuredOutput(Movie);
  const response = await modelWithStructure.invoke("영화 인셉션의 정보를 알려줘");
  show("[5-3] withStructuredOutput 결과", response);

  // includeRaw: true → { parsed, raw } 로 받습니다. 토큰 사용량·중단 사유가 필요할 때.
  const withRaw = model.withStructuredOutput(Movie, { includeRaw: true });
  const both = await withRaw.invoke("영화 매트릭스의 정보를 알려줘");
  show("[5-3] includeRaw.parsed", both.parsed);

  // 주의: raw 의 타입은 AIMessage 가 아니라 BaseMessage 입니다.
  // usage_metadata 는 AIMessage 에만 있으므로 좁혀서 써야 합니다 (본문 5-3 함정 참고).
  const raw = both.raw as AIMessage;
  show("[5-3] includeRaw.raw 의 토큰 사용량", raw.usage_metadata);

  // method 로 구현 방식을 고를 수 있습니다: "jsonSchema" | "functionCalling" | "jsonMode"
  // (지원 여부는 provider 마다 다릅니다 — 본문 5-5 표 참고)
  const viaJsonSchema = model.withStructuredOutput(Movie, { method: "jsonSchema" });
  show("[5-3] method: jsonSchema", await viaJsonSchema.invoke("영화 기생충의 정보를 알려줘"));
}

/* ===== [5-4] createAgent({ responseFormat }) → result.structuredResponse ===== */

async function step5_4(): Promise<void> {
  const ContactInfo = z.object({
    name: z.string().describe("사람 이름"),
    email: z.string().describe("이메일 주소"),
    phone: z.string().describe("전화번호"),
  });

  // 스키마를 그대로 넘기면 LangChain 이 전략을 자동 선택합니다.
  const agent = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: ContactInfo,
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content: "다음에서 연락처를 추출해: John Doe, john@example.com, (555) 123-4567",
      },
    ],
  });

  // 에이전트는 messages 와 structuredResponse 를 함께 돌려줍니다.
  show("[5-4] structuredResponse", result.structuredResponse);
  show("[5-4] messages 개수", result.messages.length);
  show("[5-4] 마지막 메시지 role", result.messages.at(-1)?.getType());
}

/* ===== [5-5] 전략 비교 — toolStrategy vs providerStrategy ===== */

async function step5_5(): Promise<void> {
  const ProductReview = z.object({
    rating: z.number().min(1).max(5).optional(),
    sentiment: z.enum(["positive", "negative"]),
    keyPoints: z.array(z.string()).describe("리뷰의 핵심 포인트. 소문자, 1~3단어."),
  });

  const question = {
    role: "user" as const,
    content: "이 리뷰를 분석해: 'Great product: 5 out of 5 stars. Fast shipping, but expensive'",
  };

  // (A) providerStrategy — provider 가 API 레벨에서 스키마를 강제. 신뢰도 높음.
  const providerAgent = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: providerStrategy(ProductReview),
  });
  const a = await providerAgent.invoke({ messages: [question] });
  show("[5-5] providerStrategy", a.structuredResponse);

  // (B) toolStrategy — "스키마 모양의 도구"를 하나 더 만들어 모델이 호출하게 함.
  //     도구 호출을 지원하는 모든 모델에서 동작. 재시도 제어가 가능한 게 장점.
  const toolAgent = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductReview),
  });
  const b = await toolAgent.invoke({ messages: [question] });
  show("[5-5] toolStrategy", b.structuredResponse);

  // toolStrategy 는 메시지 흐름에 tool 메시지가 남습니다 — 그게 providerStrategy 와의 관찰 가능한 차이.
  show(
    "[5-5] toolStrategy 의 메시지 타입 흐름",
    b.messages.map((m) => m.getType()),
  );
  show(
    "[5-5] providerStrategy 의 메시지 타입 흐름",
    a.messages.map((m) => m.getType()),
  );

  // (C) toolStrategy 만 되는 것: 여러 스키마 중 택1 (union 처럼 동작)
  const CustomerComplaint = z.object({
    issueType: z.enum(["product", "service", "shipping", "billing"]),
    severity: z.enum(["low", "medium", "high"]),
    description: z.string().describe("불만 사항 요약"),
  });

  const unionAgent = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy([ProductReview, CustomerComplaint]),
  });
  const c = await unionAgent.invoke({
    messages: [{ role: "user", content: "배송이 3주째 안 와요. 환불해주세요." }],
  });
  show("[5-5] 스키마 배열 → 모델이 택1", c.structuredResponse);
}

/* ===== [5-6] .describe() 가 곧 프롬프트다 ===== */

async function step5_6(): Promise<void> {
  const article =
    "지난 분기 매출은 전년 대비 23% 성장했으나, 마케팅 비용이 40% 늘어 영업이익률은 " +
    "오히려 2%p 하락했다. CFO 박정현은 하반기 비용 통제를 예고했다.";

  // (A) describe 없음 — 모델이 필드 이름만 보고 "추측"한다.
  const Vague = z.object({
    summary: z.string(),
    metric: z.string(),
    person: z.string(),
  });

  // (B) describe 있음 — 필드마다 정확히 무엇을 원하는지 지시한다.
  const Precise = z.object({
    summary: z
      .string()
      .describe("기사 핵심을 한 문장(60자 이내)으로. 숫자를 반드시 포함할 것."),
    metric: z
      .string()
      .describe(
        "기사에서 가장 중요한 단일 지표를 '이름: 값' 형식으로. 예) '영업이익률: -2%p'",
      ),
    person: z
      .string()
      .describe("기사에 등장하는 인물의 이름만. 직함 제외. 없으면 빈 문자열."),
  });

  const model = await initChatModel(MODEL);
  const prompt = `다음 기사를 분석해:\n${article}`;

  show("[5-6] describe 없음", await model.withStructuredOutput(Vague).invoke(prompt));
  show("[5-6] describe 있음", await model.withStructuredOutput(Precise).invoke(prompt));

  // 같은 모델, 같은 기사, 같은 필드 이름. 다른 건 .describe() 뿐입니다.
  // (A)의 person 은 "CFO 박정현" 처럼 직함이 섞여 나오기 쉽고,
  // (B)는 "박정현" 만 나옵니다. 필드 설명이 곧 프롬프트라는 뜻입니다.
}

/* ===== [5-7] 검증 실패 처리 — 재시도, 부분 실패 ===== */

async function step5_7(): Promise<void> {
  const ProductRating = z.object({
    rating: z.number().min(1).max(5).describe("1~5 사이 별점"),
    comment: z.string().describe("리뷰 코멘트"),
  });

  // (A) 기본값(handleError: true) — 검증 실패 시 에러를 tool 메시지로 돌려주고 자동 재시도.
  //     "10/10!" 은 rating=10 을 유도하지만 max(5) 에 걸립니다. 그래도 최종 결과는 나옵니다.
  const autoRetry = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductRating),
  });
  const r1 = await autoRetry.invoke({
    messages: [{ role: "user", content: "이걸 파싱해: Amazing product, 10/10!" }],
  });
  show("[5-7] 자동 재시도 후 결과", r1.structuredResponse);
  show(
    "[5-7] 재시도 흔적 (tool 메시지에 에러가 남는다)",
    r1.messages.map((m) => `${m.getType()}: ${String(m.content).slice(0, 80)}`),
  );

  // (B) 커스텀 에러 메시지 — 모델에게 무엇을 고쳐야 하는지 직접 알려준다.
  const customMsg = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductRating, {
      handleError:
        "rating 은 반드시 1~5 사이 정수여야 합니다. 10점 만점 표기는 5점 만점으로 환산하세요.",
    }),
  });
  const r2 = await customMsg.invoke({
    messages: [{ role: "user", content: "이걸 파싱해: Amazing product, 10/10!" }],
  });
  show("[5-7] 커스텀 에러 메시지 후 결과", r2.structuredResponse);

  // (C) handleError: false — 재시도하지 않고 예외를 그대로 던진다.
  //     "조용한 잘못된 성공"보다 "시끄러운 실패"가 나은 배치/파이프라인에서 씁니다.
  const strict = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductRating, { handleError: false }),
  });
  try {
    const r3 = await strict.invoke({
      messages: [{ role: "user", content: "이걸 파싱해: Amazing product, 10/10!" }],
    });
    show("[5-7] handleError:false — 이번엔 한 번에 성공", r3.structuredResponse);
  } catch (error) {
    show("[5-7] handleError:false — 예외 발생", (error as Error).message.slice(0, 200));
  }

  // (D) toolMessageContent — 재시도가 아니라 "성공했을 때" 대화에 남는 문구를 바꾼다.
  const MeetingAction = z.object({
    task: z.string().describe("완료해야 할 구체적 작업"),
    assignee: z.string().describe("담당자"),
    priority: z.enum(["low", "medium", "high"]).describe("우선순위"),
  });
  const withMessage = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(MeetingAction, {
      toolMessageContent: "액션 아이템을 회의록에 기록했습니다!",
    }),
  });
  const r4 = await withMessage.invoke({
    messages: [
      { role: "user", content: "회의 내용: 사라가 프로젝트 일정을 최대한 빨리 업데이트해야 함" },
    ],
  });
  show("[5-7] toolMessageContent 적용", {
    structuredResponse: r4.structuredResponse,
    lastToolMessage: r4.messages.find((m) => m.getType() === "tool")?.content,
  });

  // (E) 부분 실패 방어 — 모든 필드를 필수로 두지 말고, 모르면 null 을 쓰게 한다.
  const Lenient = z.object({
    rating: z.number().min(1).max(5).nullable().describe("별점. 글에 없으면 null."),
    comment: z.string().nullable().describe("코멘트. 없으면 null."),
  });
  const lenient = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(Lenient),
  });
  const r5 = await lenient.invoke({
    messages: [{ role: "user", content: "이걸 파싱해: (내용 없음)" }],
  });
  show("[5-7] 관대한 스키마 — 억지 환각 대신 null", r5.structuredResponse);
}

/* ===== [5-8] 실전 패턴 — 분류, 추출, 라우팅 ===== */

async function step5_8(): Promise<void> {
  const model = await initChatModel(MODEL);

  // 패턴 1: 분류(classification) — enum + 근거 + 확신도
  const Classification = z.object({
    // 근거를 먼저 쓰게 하면 정확도가 올라갑니다. 필드 순서가 곧 사고 순서입니다.
    reasoning: z.string().describe("이 분류를 택한 이유를 한 문장으로. 먼저 작성할 것."),
    label: z
      .enum(["bug", "feature", "question", "billing", "spam"])
      .describe("문의 유형. 다섯 중 정확히 하나."),
    confidence: z
      .number()
      .min(0)
      .max(1)
      .describe("분류 확신도 0~1. 애매하면 0.5 미만을 쓸 것."),
  });

  const classifier = model.withStructuredOutput(Classification);
  const inquiries = [
    "결제했는데 두 번 청구됐어요. 환불 부탁드립니다.",
    "다크 모드 지원 계획 있나요?",
    "앱이 실행하자마자 흰 화면에서 멈춥니다.",
  ];
  for (const text of inquiries) {
    show(`[5-8] 분류: "${text.slice(0, 20)}..."`, await classifier.invoke(text));
  }

  // 패턴 2: 추출(extraction) — 비정형 텍스트 → 배열 레코드
  const Extraction = z.object({
    items: z
      .array(
        z.object({
          product: z.string().describe("상품명"),
          quantity: z.number().describe("수량. 명시 없으면 1."),
          unitPrice: z.number().nullable().describe("단가(원). 글에 없으면 null."),
        }),
      )
      .describe("주문서에 등장하는 모든 품목. 빠뜨리지 말 것."),
    orderer: z.string().nullable().describe("주문자 이름. 없으면 null."),
  });

  const order =
    "안녕하세요, 김민수입니다. 27인치 모니터 2대(대당 459,000원)랑 " +
    "무선 키보드 1개 주문하고 싶습니다. 키보드 가격은 잘 모르겠네요.";
  show("[5-8] 추출", await model.withStructuredOutput(Extraction).invoke(order));

  // 패턴 3: 라우팅(routing) — 구조화 출력으로 다음 행동을 고른다
  const Route = z.object({
    destination: z
      .enum(["refund_agent", "tech_support", "sales", "human"])
      .describe("이 문의를 넘길 곳. 확신이 없으면 human."),
    priority: z.enum(["low", "normal", "urgent"]).describe("처리 우선순위"),
    summary: z.string().describe("담당자가 읽을 한 문장 요약"),
  });

  const router = model.withStructuredOutput(Route);
  const decision = await router.invoke(
    "3주 전에 주문한 노트북이 아직 안 왔고 고객센터도 연결이 안 됩니다. 화가 많이 나네요.",
  );
  show("[5-8] 라우팅 결정", decision);

  // 라우팅 결과는 그냥 문자열이 아니라 union 타입이라, switch 가 안전합니다.
  switch (decision.destination) {
    case "refund_agent":
      console.log("→ 환불 담당 에이전트 호출");
      break;
    case "tech_support":
      console.log("→ 기술 지원 에이전트 호출");
      break;
    case "sales":
      console.log("→ 영업 에이전트 호출");
      break;
    case "human":
      console.log("→ 사람에게 에스컬레이션");
      break;
  }
}

/* ===== [5-9] 종합 — 도구 + 구조화 출력을 함께 쓰는 에이전트 ===== */

async function step5_9(): Promise<void> {
  // 사내 재고를 조회하는 가짜 도구
  const checkStock = tool(
    async ({ product }: { product: string }) => {
      const db: Record<string, number> = {
        "27인치 4K 모니터": 12,
        "무선 키보드": 0,
        "게이밍 노트북": 3,
      };
      const qty = db[product];
      return qty === undefined
        ? `'${product}' 는 취급하지 않는 상품입니다.`
        : `'${product}' 재고: ${qty}개`;
    },
    {
      name: "check_stock",
      description: "상품명을 받아 현재 재고 수량을 조회한다. 상품 문의가 오면 반드시 먼저 호출할 것.",
      schema: z.object({ product: z.string().describe("정확한 상품명") }),
    },
  );

  // 도구를 여러 번 호출한 뒤, 최종 답변만 구조화해서 받습니다.
  const StockAnswer = z.object({
    available: z
      .array(z.string())
      .describe("재고가 1개 이상인 상품명 목록. 없으면 빈 배열."),
    unavailable: z
      .array(z.string())
      .describe("재고가 0이거나 취급하지 않는 상품명 목록. 없으면 빈 배열."),
    reply: z.string().describe("고객에게 보낼 한국어 답변. 2문장 이내, 존댓말."),
  });

  const agent = createAgent({
    model: MODEL,
    tools: [checkStock],
    systemPrompt:
      "너는 재고 문의를 처리하는 상담원이다. 재고는 반드시 check_stock 도구로 확인하고, " +
      "추측하지 마라. 확인이 끝나면 구조화된 형식으로 답하라.",
    // 도구를 쓰는 에이전트에서는 toolStrategy 가 안전한 기본값입니다 (본문 5-9 함정 참고).
    responseFormat: toolStrategy(StockAnswer),
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content: "27인치 4K 모니터랑 무선 키보드, 그리고 사무용 의자 재고 있나요?",
      },
    ],
  });

  show("[5-9] 도구 호출 흐름", result.messages.map((m) => m.getType()));
  show("[5-9] 최종 구조화 응답", result.structuredResponse);

  // 타입이 살아있으므로 후속 로직을 안전하게 이어붙일 수 있습니다.
  const { available, unavailable, reply } = result.structuredResponse;
  console.log(`\n재고 있음 ${available.length}건 / 재고 없음 ${unavailable.length}건`);
  console.log(`고객 답변: ${reply}`);
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await step5_1();
  step5_2(); // 이 절만 모델 호출이 없습니다 (API 키 없이도 실행됨)
  await step5_3();
  await step5_4();
  await step5_5();
  await step5_6();
  await step5_7();
  await step5_8();
  await step5_9();
}

main().catch((error) => {
  console.error("실행 실패:", error);
  process.exitCode = 1;
});
