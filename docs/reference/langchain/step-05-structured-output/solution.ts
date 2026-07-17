/**
 * Step 05 — 구조화된 출력 (zod) · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-05-structured-output/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 각 정답 위 주석에 채점 포인트와 함정이 적혀 있습니다.
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

const MODEL = "anthropic:claude-sonnet-4-6";

function show(label: string, value: unknown): void {
  console.log(`\n=== ${label} ===`);
  console.dir(value, { depth: null });
}

/* ===== [정답 1] optional vs nullable =====
 *
 * 채점 포인트: 둘은 대체재가 아니라 서로 다른 축을 다룹니다.
 *   .optional() = 키가 없어도 됨 (undefined 허용, null 은 거부)
 *   .nullable() = 값이 null 이어도 됨 (키는 필수, 생략은 거부)
 *
 * 결과 (zod 4.4.3 실측): (a) false / (b) false / (c) true / (d) true
 *   (a) 가 실패하는 이유: bio 는 .nullable() 이므로 키가 "필수"입니다.
 *       nullable 은 값에 null 을 허용할 뿐, 키를 생략해도 된다는 뜻이 아닙니다.
 *       → "bio: Invalid input: expected string, received undefined"
 *   (b) 가 실패하는 이유: 두 가지가 겹칩니다.
 *       nickname 은 .optional() 이라 undefined 는 되지만 null 은 거부되고,
 *       bio 키가 여전히 빠져 있습니다.
 *   (c),(d) 는 통과합니다.
 *
 * 한 줄 요약: optional 은 "키" 축, nullable 은 "값" 축입니다. 축이 다릅니다.
 * 둘 다 허용하려면 .nullish() (= .optional().nullable()) 를 씁니다.
 */
function solution1(): void {
  const Profile = z.object({
    name: z.string().describe("이름"),
    nickname: z.string().optional().describe("별명. 없으면 생략."),
    bio: z.string().nullable().describe("자기소개. 없으면 null."),
  });

  const cases: Array<[string, unknown]> = [
    ["(a) { name }", { name: "김민수" }],
    ["(b) { name, nickname: null }", { name: "김민수", nickname: null }],
    ["(c) { name, bio: null }", { name: "김민수", bio: null }],
    ["(d) 전부", { name: "김민수", bio: null, nickname: "민수" }],
  ];

  for (const [label, input] of cases) {
    const r = Profile.safeParse(input);
    show(`[정답 1] ${label}`, {
      success: r.success,
      issues: r.success ? undefined : r.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`),
    });
  }

  // 실무 결론: 에이전트 스키마에서는 "모르면 null" 이 "모르면 생략" 보다 낫습니다.
  // 모델은 키를 생략하는 것보다 null 을 쓰는 걸 훨씬 안정적으로 해냅니다.
}

/* ===== [정답 2] 영수증 추출 =====
 *
 * 채점 포인트:
 *   1) total 을 z.string() 이 아니라 z.number() 로 두었는가.
 *      문자열로 두면 "16,900원" 같은 값이 와서 결국 다시 파싱해야 합니다.
 *      숫자 타입이면 모델이 콤마와 단위를 알아서 떼줍니다.
 *   2) date 의 describe 에 "YYYY-MM-DD" 형식을 명시했는가.
 *      안 쓰면 "2026년 7월 15일", "07/15/2026" 등이 섞여 나옵니다.
 *   3) 영수증엔 시각(14:32)도 있지만 date 만 요구했으므로
 *      "시각은 제외" 를 describe 에 적어주면 더 안정적입니다.
 */
async function solution2(): Promise<void> {
  const receipt = `
    스타벅스 강남점
    2026-07-15 14:32
    아메리카노(T)      4,500
    카페라떼(G)        5,900
    치즈케이크         6,500
    ------------------------
    합계              16,900
  `;

  const Receipt = z.object({
    storeName: z.string().describe("상점 이름. 지점명 포함."),
    total: z.number().describe("총액. 원 단위 정수. 콤마와 '원' 없이 숫자만."),
    date: z.string().describe("결제 날짜 YYYY-MM-DD 형식. 시각은 제외."),
    items: z
      .array(
        z.object({
          name: z.string().describe("품목명. 괄호 안 사이즈 표기 포함."),
          price: z.number().describe("품목 가격. 원 단위 정수."),
        }),
      )
      .describe("영수증의 모든 품목. 합계 줄은 제외."),
  });

  const model = await initChatModel(MODEL);
  const result = await model.withStructuredOutput(Receipt).invoke(
    `다음 영수증에서 정보를 추출해:\n${receipt}`,
  );
  show("[정답 2] 영수증 추출", result);

  // 타입이 살아있으므로 바로 계산에 쓸 수 있습니다 — 이게 구조화 출력의 목적입니다.
  const sum = result.items.reduce((acc, item) => acc + item.price, 0);
  show("[정답 2] 검산 (품목 합 == total?)", { sum, total: result.total, match: sum === result.total });
}

/* ===== [정답 3] describe 유무 비교 =====
 *
 * 채점 포인트: 필드 이름만으로는 모델이 "무엇을 원하는지" 모릅니다.
 *   (A) company → "주식회사 넥스트테크" 또는 "넥스트테크" (매번 다름)
 *       amount  → "350억원 규모의 투자" 처럼 문장이 섞여 나오기 쉬움
 *   (B) company → "넥스트테크" (법인격 제거 지시가 먹힘)
 *       amount  → "350억원" (형식 예시가 먹힘)
 *
 * 핵심: .describe() 는 문서가 아니라 "그 필드에만 적용되는 프롬프트"입니다.
 * JSON Schema 의 description 으로 변환되어 실제로 모델에게 전달됩니다.
 */
async function solution3(): Promise<void> {
  const news =
    "주식회사 넥스트테크는 시리즈B 라운드에서 총 350억원 규모의 투자를 유치했다고 밝혔다.";

  const Vague = z.object({
    company: z.string(),
    amount: z.string(),
  });

  const Precise = z.object({
    company: z.string().describe("회사 이름만. 법인격(주식회사/(주)/Inc.) 표기는 제외."),
    amount: z.string().describe("투자 금액을 숫자와 단위만. 예) '350억원'. 부연 설명 금지."),
  });

  const model = await initChatModel(MODEL);
  const prompt = `다음 뉴스에서 정보를 추출해:\n${news}`;

  show("[정답 3] (A) describe 없음", await model.withStructuredOutput(Vague).invoke(prompt));
  show("[정답 3] (B) describe 있음", await model.withStructuredOutput(Precise).invoke(prompt));

  // 모델 응답은 비결정적이라 (A)가 우연히 잘 나올 수도 있습니다.
  // 중요한 건 "우연에 기대는가"입니다. describe 는 그 우연을 제거합니다.
}

/* ===== [정답 4] 두 전략의 메시지 흐름 =====
 *
 * 채점 포인트:
 *   providerStrategy → ["human", "ai"]
 *     provider 가 API 레벨에서 스키마를 강제하므로 도구 호출이 발생하지 않습니다.
 *     대화 기록이 깨끗합니다.
 *   toolStrategy     → ["human", "ai", "tool"]
 *     "Sentiment 라는 이름의 가짜 도구"를 하나 만들어 모델이 그걸 호출하게 하고,
 *     그 인자를 파싱해 structuredResponse 로 돌려줍니다.
 *     그래서 ai(tool_calls) → tool 메시지가 대화에 남습니다.
 *
 * 이 차이가 중요한 이유: toolStrategy 의 tool 메시지는 다음 턴의 컨텍스트에
 * 그대로 쌓입니다. 멀티턴 대화에서 토큰을 먹고, 모델이 "아, 저 도구를 또 부르면 되는구나"
 * 하고 학습하기도 합니다. 반면 재시도 제어(handleError)는 toolStrategy 에만 있습니다.
 */
async function solution4(): Promise<void> {
  const Sentiment = z.object({
    label: z.enum(["positive", "negative", "neutral"]).describe("감정"),
    score: z.number().min(-1).max(1).describe("-1(부정) ~ 1(긍정)"),
  });
  const question = "이 문장의 감정을 분석해: '기대했던 것보단 별로였지만 못 쓸 정도는 아니에요.'";
  const messages = [{ role: "user" as const, content: question }];

  const providerAgent = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: providerStrategy(Sentiment),
  });
  const a = await providerAgent.invoke({ messages });
  show("[정답 4] providerStrategy", {
    flow: a.messages.map((m) => m.getType()),
    structuredResponse: a.structuredResponse,
  });

  const toolAgent = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(Sentiment),
  });
  const b = await toolAgent.invoke({ messages });
  show("[정답 4] toolStrategy", {
    flow: b.messages.map((m) => m.getType()),
    structuredResponse: b.structuredResponse,
  });
}

/* ===== [정답 5] 검증 실패와 재시도 =====
 *
 * 채점 포인트 — 셋의 차이:
 *   (a) handleError: true (기본값)
 *       검증 에러가 tool 메시지로 모델에게 되돌아가고, 모델이 스스로 고쳐서 재호출합니다.
 *       messages 에 "Error: Failed to parse structured output for tool ..." 이 남습니다.
 *       최종적으로는 rating: 5 같은 유효한 값이 나옵니다.
 *       → 이게 무섭습니다. 성공한 것처럼 보이지만 200점이 5점으로 "조용히 뭉개진" 겁니다.
 *   (b) handleError: false
 *       재시도 없이 예외가 그대로 올라옵니다. 배치 파이프라인에서는 이쪽이 옳습니다.
 *   (c) handleError: "문자열"
 *       기본 에러 템플릿 대신 내가 쓴 문장이 tool 메시지로 갑니다.
 *       "10점 만점은 5점 만점으로 환산하라" 같은 도메인 지식을 주입할 수 있습니다.
 */
async function solution5(): Promise<void> {
  const ProductRating = z.object({
    rating: z.number().min(1).max(5).describe("1~5 사이 별점"),
    comment: z.string().describe("리뷰 코멘트"),
  });
  const messages = [{ role: "user" as const, content: "이 제품 100점 만점에 200점! 최고예요." }];

  // (a) 기본 — 자동 재시도
  const auto = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductRating),
  });
  const r1 = await auto.invoke({ messages });
  show("[정답 5-a] 자동 재시도", {
    structuredResponse: r1.structuredResponse,
    errorTraces: r1.messages
      .filter((m) => m.getType() === "tool" && String(m.content).startsWith("Error:"))
      .map((m) => String(m.content).slice(0, 120)),
  });

  // (b) 예외 전파
  const strict = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductRating, { handleError: false }),
  });
  try {
    const r2 = await strict.invoke({ messages });
    show("[정답 5-b] 예외 없이 통과 (모델이 한 번에 맞힘)", r2.structuredResponse);
  } catch (error) {
    show("[정답 5-b] 예외 전파됨", (error as Error).message.slice(0, 200));
  }

  // (c) 커스텀 에러 메시지
  const custom = createAgent({
    model: MODEL,
    tools: [],
    responseFormat: toolStrategy(ProductRating, {
      handleError:
        "rating 은 1~5 사이여야 합니다. 100점 만점 점수는 20으로 나눠 5점 만점으로 환산하세요.",
    }),
  });
  const r3 = await custom.invoke({ messages });
  show("[정답 5-c] 커스텀 에러 메시지", r3.structuredResponse);
  // 200/20 = 10 → 여전히 5 초과. 모델은 다시 재시도해 5로 클램프합니다.
  // 교훈: 재시도로 못 고치는 입력이 있습니다. 그때는 스키마를 nullable 로 열어주는 게 답입니다.
}

/* ===== [정답 6] 분류기 =====
 *
 * 채점 포인트 — reasoning 을 label 보다 먼저 선언하는 이유:
 *   모델은 JSON 을 "위에서 아래로" 토큰 단위로 생성합니다.
 *   reasoning 이 먼저 오면 근거를 쓰면서 생각한 결과가 label 에 반영됩니다.
 *   label 이 먼저 오면 답을 먼저 뱉고 reasoning 은 그 답을 정당화하는 변명이 됩니다.
 *   (사고의 순서 = 필드의 순서. 스키마 설계에서 자주 놓치는 지점입니다.)
 *
 * 기대 결과: 비밀번호 메일 → bug 또는 question / 아이폰 무료 증정 → spam (confidence 높음)
 *            엑셀 내보내기 → feature
 */
async function solution6(): Promise<void> {
  const Classification = z.object({
    // 순서 중요: reasoning 이 label 보다 위에 있어야 합니다.
    reasoning: z.string().describe("이 분류를 택한 이유를 한 문장으로. 반드시 먼저 작성할 것."),
    label: z
      .enum(["bug", "feature", "question", "billing", "spam"])
      .describe("문의 유형. 다섯 중 정확히 하나."),
    confidence: z
      .number()
      .min(0)
      .max(1)
      .describe("분류 확신도 0~1. 두 카테고리 사이에서 애매하면 0.5 미만을 쓸 것."),
  });

  const model = await initChatModel(MODEL);
  const classifier = model.withStructuredOutput(Classification);

  const inquiries = [
    "비밀번호 재설정 메일이 안 옵니다.",
    "🔥🔥 지금 클릭하면 아이폰 무료 증정 🔥🔥 http://spam.example.com",
    "엑셀 내보내기 기능도 추가해주실 수 있나요?",
  ];

  for (const text of inquiries) {
    const r = await classifier.invoke(`다음 고객 문의를 분류해:\n${text}`);
    show(`[정답 6] "${text.slice(0, 18)}..."`, r);

    // confidence 를 실제로 쓰는 법: 낮으면 사람에게 넘긴다.
    if (r.confidence < 0.5) {
      console.log("  → 확신도 낮음. 사람 검토 큐로 보냅니다.");
    }
  }
}

/* ===== [정답 7] 중첩 깊이 =====
 *
 * 채점 포인트:
 *   (B) 평평한 스키마가 더 안정적입니다.
 *   깊게 중첩할수록 모델은 여는 중괄호와 닫는 중괄호를 짝 맞추는 데 토큰을 쓰고,
 *   "profile 안에 tier 였나, customer 바로 아래였나" 를 헷갈립니다.
 *   4단계쯤 되면 필드를 통째로 빠뜨리거나 엉뚱한 레벨에 넣는 일이 생깁니다.
 *
 *   실무 규칙: 중첩은 2단계까지. 그보다 깊어지면
 *     (1) 평평하게 펴서 접두사로 구분하거나 (customerName, paymentAmount)
 *     (2) 호출을 두 번으로 쪼개세요.
 *   호출 후 우리 코드에서 원하는 모양으로 재조립하는 게 훨씬 싸고 정확합니다.
 */
async function solution7(): Promise<void> {
  const text = "VIP 고객 이수진님이 신용카드로 129,000원을 결제했습니다.";

  // (A) 깊게 중첩 — 4단계
  const Deep = z.object({
    order: z.object({
      customer: z.object({
        profile: z.object({
          name: z.string().describe("고객 이름"),
          tier: z.enum(["VIP", "GOLD", "SILVER", "BRONZE"]).describe("고객 등급"),
        }),
      }),
      payment: z.object({
        method: z.enum(["card", "transfer", "cash"]).describe("결제 수단"),
        amount: z.number().describe("결제 금액. 원 단위 정수."),
      }),
    }),
  });

  // (B) 평평하게 — 1단계
  const Flat = z.object({
    customerName: z.string().describe("고객 이름"),
    customerTier: z.enum(["VIP", "GOLD", "SILVER", "BRONZE"]).describe("고객 등급"),
    paymentMethod: z.enum(["card", "transfer", "cash"]).describe("결제 수단"),
    paymentAmount: z.number().describe("결제 금액. 원 단위 정수."),
  });

  const model = await initChatModel(MODEL);
  const prompt = `다음에서 정보를 추출해:\n${text}`;

  const deep = await model.withStructuredOutput(Deep).invoke(prompt);
  show("[정답 7] (A) 4단계 중첩", deep);

  const flat = await model.withStructuredOutput(Flat).invoke(prompt);
  show("[정답 7] (B) 평평", flat);

  // 원하는 모양이 정말 중첩이라면, 평평하게 받아서 우리가 조립합니다.
  const assembled = {
    order: {
      customer: { profile: { name: flat.customerName, tier: flat.customerTier } },
      payment: { method: flat.paymentMethod, amount: flat.paymentAmount },
    },
  };
  show("[정답 7] (B)를 우리 코드에서 재조립 — 공짜이고 100% 정확", assembled);
}

/* ===== [정답 8] 도구 + 구조화 출력 라우팅 에이전트 =====
 *
 * 채점 포인트:
 *   1) responseFormat 을 toolStrategy 로 감쌌는가.
 *      도구가 있는 에이전트에서 providerStrategy 를 쓰면
 *      "도구 호출"과 "네이티브 구조화 출력"을 동시에 요구하게 되어
 *      provider 에 따라 도구를 무시하거나 에러가 납니다. (본문 5-9 함정)
 *   2) messages 흐름이 human → ai(tool_calls) → tool → ai(구조화) 로 가는가.
 *      도구 호출이 먼저 끝나고 나서 구조화 응답이 나옵니다.
 *   3) switch 가 타입 안전한가. enum 이라 default 없이도 모든 분기가 커버됩니다.
 *
 * 기대 결과: destination="refund_agent", priority="urgent"
 */
async function solution8(): Promise<void> {
  const lookupOrder = tool(
    async ({ orderId }: { orderId: string }) => {
      const db: Record<string, string> = {
        "ORD-1001": "배송중, 예상 도착 2026-07-19, 3주 지연됨",
        "ORD-1002": "배송완료 2026-07-10",
      };
      return db[orderId] ?? `주문번호 '${orderId}' 를 찾을 수 없습니다.`;
    },
    {
      name: "lookup_order",
      description:
        "주문번호(ORD-XXXX 형식)를 받아 현재 배송 상태를 조회한다. 주문 관련 문의는 반드시 먼저 호출할 것.",
      schema: z.object({ orderId: z.string().describe("주문번호. 예) ORD-1001") }),
    },
  );

  const Route = z.object({
    destination: z
      .enum(["refund_agent", "tech_support", "sales", "human"])
      .describe("이 문의를 넘길 곳. 확신이 없으면 human."),
    priority: z
      .enum(["low", "normal", "urgent"])
      .describe("처리 우선순위. 고객이 화가 났거나 지연이 2주 이상이면 urgent."),
    summary: z.string().describe("담당자가 읽을 한 문장 요약. 조회한 배송 상태를 반드시 포함."),
  });

  const agent = createAgent({
    model: MODEL,
    tools: [lookupOrder],
    systemPrompt:
      "너는 고객 문의를 적절한 담당자에게 배정하는 라우터다. " +
      "주문번호가 언급되면 반드시 lookup_order 로 실제 상태를 확인한 뒤 판단하라. 추측하지 마라.",
    responseFormat: toolStrategy(Route),
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "ORD-1001 주문이 3주째 안 오는데 환불해주세요" }],
  });

  show("[정답 8] 메시지 흐름", result.messages.map((m) => m.getType()));
  show("[정답 8] 라우팅 결정", result.structuredResponse);

  const { destination, priority, summary } = result.structuredResponse;
  console.log(`\n[${priority.toUpperCase()}] ${summary}`);
  switch (destination) {
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

async function main(): Promise<void> {
  solution1();
  await solution2();
  await solution3();
  await solution4();
  await solution5();
  await solution6();
  await solution7();
  await solution8();
}

main().catch((error) => {
  console.error("실행 실패:", error);
  process.exitCode = 1;
});
