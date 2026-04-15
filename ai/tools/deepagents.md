# Deep Agents

## Model

모델 문자열 또는 초기화된 모델 인스턴스를 전달하세요.

```javascript
const agent = createDeepAgent({ model: "gpt-5.4" });
const agent = createDeepAgent({
  model: new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxRetries: 10, // Increase for unreliable networks (default: 6)
    timeout: 120_000, // Increase timeout for slow connections
  }),
});
```

## Tools

계획 수립, 파일 관리 및 하위 에이전트 생성에 필요한 내장 도구 외에도 사용자 지정 도구를 제공할 수 있습니다.

```javascript
const internetSearch = tool(
  async ({
    query,
    maxResults = 5,
    topic = "general",
    includeRawContent = false,
  }: {
    query: string;
    maxResults?: number;
    topic?: "general" | "news" | "finance";
    includeRawContent?: boolean;
  }) => {
    const tavilySearch = new TavilySearch({
      maxResults,
      tavilyApiKey: process.env.TAVILY_API_KEY,
      includeRawContent,
      topic,
    });
    return await tavilySearch._call({ query });
  },
  {
    name: "internet_search",
    description: "Run a web search",
    schema: z.object({
      query: z.string().describe("The search query"),
      maxResults: z.number().optional().default(5),
      topic: z
        .enum(["general", "news", "finance"])
        .optional()
        .default("general"),
      includeRawContent: z.boolean().optional().default(false),
    }),
  },
);

const agent = createDeepAgent({
  tools: [internetSearch],
});
```

## System Prompt

Deep Agents에는 기본 시스템 프롬프트가 내장되어 있습니다. 기본 시스템 프롬프트에는 내장 계획 도구, 파일 시스템 도구 및 하위 에이전트 사용에 대한 자세한 지침이 포함되어 있습니다. 미들웨어가 파일 시스템 도구와 같은 특수 도구를 추가하면 해당 도구가 시스템 프롬프트에 추가됩니다.

```javascript
const researchInstructions =
  `You are an expert researcher. ` + `Your job is to conduct thorough research, and then ` + `write a polished report.`;

const agent = createDeepAgent({
  systemPrompt: researchInstructions,
});
```

## Middlewares

기본적으로 Deep Agent는 다음과 같은 미들웨어에 접근할 수 있습니다.

- TodoListMiddleware: 에이전트 작업 및 업무 구성을 위한 할 일 목록을 추적하고 관리합니다.
- FilesystemMiddleware: 디렉터리 읽기, 쓰기 및 탐색과 같은 파일 시스템 작업을 처리합니다.
- SubAgentMiddleware: 전문 에이전트에게 작업을 위임하기 위해 하위 에이전트를 생성하고 조정합니다.
- SummarizationMiddleware: 대화가 길어질 경우 컨텍스트 제한을 준수하도록 메시지 기록을 요약합니다.
- AnthropicPromptCachingMiddleware: Anthropic 모델을 사용할 때 중복 토큰 처리를 자동으로 줄입니다.
- PatchToolCallsMiddleware: 도구 호출이 결과를 받기 전에 중단되거나 취소될 경우 메시지 기록을 자동으로 수정합니다.

메모리, 스킬 또는 휴먼 인 더 루프를 사용하는 경우 다음 미들웨어도 포함됩니다.

- MemoryMiddleware: memory 인수가 제공되면 세션 간에 대화 컨텍스트를 유지하고 검색합니다.
- SkillsMiddleware: skills 인수가 제공되면 사용자 지정 스킬을 활성화합니다. 제공됨
- HumanInTheLoopMiddleware: interruptOn 인수가 제공될 때 지정된 지점에서 사람의 승인 또는 입력을 위해 일시 ​​중지합니다.

사전 구축된 미들웨어

LangChain은 재시도, 대체 기능, 개인 식별 정보(PII) 감지 등 다양한 기능을 추가할 수 있는 사전 구축된 미들웨어를 제공합니다. 자세한 내용은 사전 구축된 미들웨어를 참조하세요.

deepagents 패키지는 동일한 워크플로를 위한 createSummarizationMiddleware도 제공합니다. 자세한 내용은 요약을 참조하세요.

공급자별 미들웨어
특정 LLM 공급자에 최적화된 공급자별 미들웨어는 [공식 통합](https://docs.langchain.com/oss/javascript/integrations/middleware#official-integrations) 및 [커뮤니티 통합](https://docs.langchain.com/oss/javascript/integrations/middleware#community-integrations)을 참조하세요.

custom middleware

```javascript
let callCount = 0;

const logToolCallsMiddleware = createMiddleware({
  name: "LogToolCallsMiddleware",
  wrapToolCall: async (request, handler) => {
    // Intercept and log every tool call - demonstrates cross-cutting concern
    callCount += 1;
    const toolName = request.toolCall.name;

    console.log(`[Middleware] Tool call #${callCount}: ${toolName}`);
    console.log(`[Middleware] Arguments: ${JSON.stringify(request.toolCall.args)}`);

    // Execute the tool call
    const result = await handler(request);

    // Log the result
    console.log(`[Middleware] Tool call #${callCount} completed`);

    return result;
  },
});
```

## Subagents

세부적인 작업을 분리하고 컨텍스트 과부하를 방지하려면 하위 에이전트를 사용

```javascript
const researchSubagent: SubAgent = {
  name: "research-agent",
  description: "Used to research more in depth questions",
  systemPrompt: "You are a great researcher",
  tools: [internetSearch],
  model: "openai:gpt-5.2",  // Optional override, defaults to main agent model
};
const subagents = [researchSubagent];

const agent = createDeepAgent({
  model: "claude-sonnet-4-6",
  subagents,
});
```

## Backends

- StateBacnend: 랭그래프 상태에 저장되는 임시 파일 시스템 백엔드입니다. 이 파일 시스템은 단일 스레드에 대해서만 유지됩니다.
- FileSystemBackend: 호스트에서 직접 셸 실행이 가능한 파일 시스템입니다. 파일 시스템 도구와 명령 실행 도구를 제공합니다.
- LocalShellBackend: 로컬 머신의 파일 시스템입니다.
- StoreBackend: 스레드 간에 유지되는 장기 저장소를 제공하는 파일 시스템입니다.

```javascript
// By default we provide a StateBackend
const agent = createDeepAgent();

// Under the hood, it looks like
const agent2 = createDeepAgent({
  backend: new StateBackend(),
});

// filesystem backend
const agent3 = createDeepAgent({
  backend: new FilesystemBackend({ rootDir: ".", virtualMode: true }),
});

// localshell backend
const agent4 = createDeepAgent({
  backend: new LocalShellBackend({ workingDirectory: "." }),
});

// store backend
const agent5 = createDeepAgent({
  backend: new LocalShellBackend({ workingDirectory: "." }),
  store: new InMemoryStore(),
});
```

### Backend Sandboxes

샌드박스는 에이전트 코드를 자체 파일 시스템과 셸 명령 실행 도구를 갖춘 격리된 환경에서 실행하는 특수 백엔드입니다. 딥 에이전트가 로컬 머신을 변경하지 않고 파일을 작성하고, 종속성을 설치하고, 명령을 실행하도록 하려면 샌드박스 백엔드를 사용하십시오.
샌드박스는 딥 에이전트를 생성할 때 백엔드에 샌드박스 백엔드를 전달하여 구성합니다.

```javascript
// Create and initialize the sandbox
const sandbox = await DenoSandbox.create({
  memoryMb: 1024,
  lifetime: "10m",
});

try {
  const agent = createDeepAgent({
    model: new ChatAnthropic({ model: "claude-opus-4-6" }),
    systemPrompt: "You are a JavaScript coding assistant with sandbox access.",
    backend: sandbox,
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content: "Create a simple HTTP server using Deno.serve and test it with curl",
      },
    ],
  });
} finally {
  await sandbox.close();
}
```

## Human-in-the-loop

일부 도구 작업은 민감한 정보를 포함하므로 실행 전에 사람의 승인이 필요할 수 있습니다. 각 도구에 대한 승인 절차를 구성할 수 있습니다.

```javascript
// Checkpointer is REQUIRED for human-in-the-loop
const checkpointer = new MemorySaver();

const agent = createDeepAgent({
  model: "claude-sonnet-4-6",
  tools: [deleteFile, readFile, sendEmail],
  interruptOn: {
    delete_file: true, // Default: approve, edit, reject
    read_file: false, // No interrupts needed
    send_email: { allowedDecisions: ["approve", "reject"] }, // No editing
  },
  checkpointer, // Required!
});
```

## Skills

스킬을 사용하면 딥 에이전트에 새로운 기능과 전문 지식을 제공할 수 있습니다. 도구는 일반적으로 기본 파일 시스템 작업이나 계획과 같은 하위 수준 기능을 다루는 반면, 스킬에는 작업 완료 방법에 대한 자세한 지침, 참조 정보 및 템플릿과 같은 기타 자산이 포함될 수 있습니다. 이러한 파일은 에이전트가 현재 프롬프트에 스킬이 유용하다고 판단했을 때만 로드됩니다. 이러한 점진적 공개 방식을 통해 에이전트가 시작 시 고려해야 하는 토큰 및 컨텍스트의 양이 줄어듭니다.

```javascript
const agent = await createDeepAgent({
  checkpointer,
  // IMPORTANT: deepagents skill source paths are virtual (POSIX) paths relative to the backend root.
  skills: ["/skills/"],
});
```

## Memory

AGENTS.md 파일을 사용하여 딥 에이전트에 추가적인 컨텍스트를 제공할 수 있습니다.
딥 에이전트를 생성할 때 메모리 매개변수에 하나 이상의 파일 경로를 전달할 수 있습니다.

```javascript
const agent = await createDeepAgent({
  memory: ["/AGENTS.md"],
  checkpointer: checkpointer,
});
```

## Structured output

createDeepAgent() 호출 시 responseFormat 인수로 원하는 구조화된 출력 스키마를 전달할 수 있습니다. 모델이 구조화된 데이터를 생성하면, 해당 데이터는 캡처, 유효성 검사를 거쳐 에이전트 상태의 'structuredResponse' 키에 반환됩니다.

```javascript
const weatherReportSchema = z.object({
  location: z.string().describe("The location for this weather report"),
  temperature: z.number().describe("Current temperature in Celsius"),
  condition: z.string().describe("Current weather condition (e.g., sunny, cloudy, rainy)"),
  humidity: z.number().describe("Humidity percentage"),
  windSpeed: z.number().describe("Wind speed in km/h"),
  forecast: z.string().describe("Brief forecast for the next 24 hours"),
});

const agent = await createDeepAgent({
  responseFormat: weatherReportSchema,
  tools: [internetSearch],
});
```

## MCP

```javascript
import { MultiServerMCPClient } from "@langchain/mcp-adapters";

const client = new MultiServerMCPClient({
  "Framelink MCP for Figma": {
    command: "npx",
    args: ["-y", "figma-developer-mcp", "--figma-api-key=", "--stdio"],
  },
});

const tools = await client.getTools();
```
