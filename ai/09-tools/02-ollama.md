# Ollama

오픈 모델을 **내 컴퓨터에서 한 줄로 내려받아 실행하고, 로컬 HTTP API 로 제공**하는 런타임입니다. 내부적으로 llama.cpp 계열 엔진 위에서 GGUF 모델을 돌리며, `localhost:11434` 에 자체 API·OpenAI 호환 API 를 동시에 띄워 줍니다. 데이터가 밖으로 나가면 안 되는 실험, 비용 없는 반복 테스트, 임베딩 배치 작업에 특히 유용합니다.

## 설치

| OS | 명령 |
|---|---|
| macOS / Linux | `curl -fsSL https://ollama.com/install.sh \| sh` |
| Windows (PowerShell) | `irm https://ollama.com/install.ps1 \| iex` |

macOS·Windows 는 [ollama.com/download](https://ollama.com/download) 의 데스크톱 앱으로도 설치할 수 있습니다. 설치하면 서버가 백그라운드로 뜨고, 기본적으로 `127.0.0.1:11434` 에 바인딩됩니다.

```bash
ollama --version        # 설치 확인
ollama run gemma4       # 모델을 받고 곧바로 대화 세션 시작
```

> ⚠️ **함정**: 기본 바인딩은 `127.0.0.1` 입니다. 다른 기기(도커 컨테이너 포함)에서 접근하려면 `OLLAMA_HOST=0.0.0.0:11434` 로 바꿔야 하는데, Ollama API 에는 **인증이 없습니다.** 외부에 열 때는 반드시 리버스 프록시나 방화벽 뒤에 두세요.

## 주요 명령어

| 명령어 | 기능 |
|---|---|
| `ollama serve` | 서버를 포그라운드로 실행 (데스크톱 앱을 쓰면 보통 필요 없음) |
| `ollama run <모델> [프롬프트]` | 모델이 없으면 받은 뒤 실행. 프롬프트를 주면 한 번 답하고 종료 |
| `ollama pull <모델>` | 다운로드만 수행 |
| `ollama list` (`ls`) | 로컬에 있는 모델 목록 |
| `ollama ps` | 현재 메모리에 올라가 있는 모델과 CPU/GPU 비율 |
| `ollama stop <모델>` | 메모리에서 즉시 내림 |
| `ollama rm <모델>` | 모델 삭제 |
| `ollama show <모델>` | 모델 정보 (`--modelfile`, `--parameters`, `--template`, `--system`, `--license`) |
| `ollama create <이름> -f Modelfile` | Modelfile 로 커스텀 모델 생성 |
| `ollama cp <원본> <대상>` | 모델 복제 (이름 바꾸기 용도) |
| `ollama push <사용자/모델>` | ollama.com 레지스트리에 업로드 |
| `ollama signin` / `signout` | ollama.com 계정 로그인/로그아웃 |
| `ollama launch <통합>` | Claude Code 등 외부 도구를 Ollama 모델로 설정해 실행 |

`ollama run` 에서 자주 쓰는 플래그입니다.

| 플래그 | 용도 |
|---|---|
| `--verbose` | 응답마다 토큰/초 등 타이밍 출력 — 모델·양자화 비교할 때 필수 |
| `--keepalive 30m` | 모델을 메모리에 유지할 시간 |
| `--format json` | JSON 형식으로 응답 |
| `--think` | 추론(thinking) 지원 모델에서 사고 과정 출력 (`true`/`false`, `high`/`medium`/`low`) |

모델 이름은 `이름:태그` 형식입니다. 태그를 생략하면 `latest` 이고, 태그로 크기·양자화를 고릅니다. 사용 가능한 태그는 [ollama.com/search](https://ollama.com/search) 의 모델 페이지에서 확인합니다.

## Modelfile — 커스텀 모델 만들기

Dockerfile 과 비슷한 형식으로 **베이스 모델 + 파라미터 + 시스템 프롬프트**를 묶어 새 모델 이름으로 저장합니다. 가중치를 복사하지 않으므로 거의 공간을 차지하지 않습니다.

| 지시어 | 설명 |
|---|---|
| `FROM` (필수) | 베이스 모델. 기존 모델 이름, GGUF 파일 경로, Safetensors 디렉터리 |
| `PARAMETER` | 실행 파라미터 (`temperature`, `num_ctx`, `stop`, `top_p` 등) |
| `TEMPLATE` | 모델에 전달할 전체 프롬프트 템플릿 (Go template 문법) |
| `SYSTEM` | 템플릿에 들어갈 시스템 메시지 |
| `MESSAGE` | 대화 이력(퓨샷 예시)을 미리 넣어 둠 |
| `LICENSE` | 라이선스 명시 |
| `REQUIRES` | 필요한 최소 Ollama 버전 |

```dockerfile
FROM gemma4
PARAMETER temperature 0.2
PARAMETER num_ctx 8192
SYSTEM """
너는 사내 코드 리뷰어다. 지적은 파일:라인 형식으로 하고, 확신이 없으면 추측하지 말고 질문한다.
"""
```

```bash
ollama create reviewer -f ./Modelfile
ollama run reviewer
ollama show --modelfile reviewer   # 최종 적용된 Modelfile 확인
```

> ⚠️ **함정**: 컨텍스트 길이 기본값은 모델이 지원하는 최대치보다 **훨씬 작게** 잡혀 있습니다(FAQ 기준 4096 토큰). 긴 문서를 넣으면 에러 없이 **앞부분이 조용히 잘립니다.** RAG 나 긴 대화에서 답이 이상하면 먼저 `num_ctx` 를 의심하세요. 서버 전체 기본값은 `OLLAMA_CONTEXT_LENGTH` 환경 변수로 바꿀 수 있고, 늘릴수록 메모리 사용량도 함께 늘어납니다.

### GGUF 모델 가져오기

Hugging Face 에 올라온 GGUF 는 Modelfile 없이 바로 실행할 수 있습니다.

```bash
# 기본: 저장소에 Q4_K_M 이 있으면 그것을 사용
ollama run hf.co/{username}/{repository}

# 양자화 지정
ollama run hf.co/{username}/{repository}:Q8_0

# 예: 한국어 튜닝된 BGE-M3 임베딩 모델
ollama pull hf.co/Neuwhufbox/BGE-m3-ko-gguf
```

로컬 GGUF 파일은 `FROM /path/to/model.gguf` 한 줄짜리 Modelfile 로 `ollama create` 하면 됩니다. Ollama 는 GGUF 를 가져올 때 양자화를 해주지 않으므로, 필요하면 llama.cpp 로 미리 양자화해 두세요. 직접 파인튜닝한 모델을 GGUF 로 변환해 등록하는 전체 과정은 [파인튜닝 예제](/ai/06-fine-tuning/02-sample)에 있습니다.

## REST API

### `/api/chat`

```bash
curl http://localhost:11434/api/chat -d '{
  "model": "gemma4",
  "messages": [
    { "role": "system", "content": "한국어로 세 문장 이내로 답한다." },
    { "role": "user", "content": "벡터 DB 가 왜 필요한가?" }
  ],
  "stream": false,
  "options": { "temperature": 0.2, "num_ctx": 8192 },
  "keep_alive": "10m"
}'
```

| 필드 | 설명 |
|---|---|
| `model`, `messages` | 필수. `messages` 는 `role`/`content` 배열 |
| `stream` | 기본 `true`. 줄 단위 JSON(NDJSON) 청크로 흘려보냄 |
| `format` | `"json"` 또는 **JSON Schema 객체** — 구조화 출력 |
| `options` | `temperature`, `num_ctx`, `top_p` 등 Modelfile `PARAMETER` 와 같은 값 |
| `keep_alive` | 요청 후 모델 유지 시간. `0` 이면 즉시 내림, `-1` 이면 계속 유지 (기본 5분) |
| `think` | 추론 모델의 사고 과정 출력 여부 |
| `tools` | 함수 호출용 도구 정의 |

응답의 `message.content` 가 답변이고, `prompt_eval_count`·`eval_count`(토큰 수), `eval_duration`(나노초)으로 속도를 계산할 수 있습니다. 한 번 묻고 끝나는 단순 생성은 `/api/generate`(`prompt` 필드)를 씁니다.

구조화 출력 예시입니다.

```bash
curl http://localhost:11434/api/chat -d '{
  "model": "gemma4",
  "messages": [{ "role": "user", "content": "서울은 대한민국의 수도이고 인구는 약 930만이다. 정보를 추출해." }],
  "stream": false,
  "format": {
    "type": "object",
    "properties": { "city": { "type": "string" }, "population": { "type": "integer" } },
    "required": ["city", "population"]
  }
}'
```

### OpenAI 호환 엔드포인트

`http://localhost:11434/v1/` 은 OpenAI API 형식을 따릅니다. **기존 OpenAI SDK 코드에서 `baseURL` 만 바꾸면** 로컬 모델로 돌릴 수 있어, 비용 없는 개발·테스트 환경으로 쓰기 좋습니다.

```typescript
import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "http://localhost:11434/v1/",
  apiKey: "ollama", // 필수 값이지만 검사하지 않음
});

const res = await client.chat.completions.create({
  model: "gemma4",
  messages: [{ role: "user", content: "RAG 를 한 문장으로 설명해줘" }],
});
console.log(res.choices[0].message.content);
```

지원 엔드포인트: `/v1/chat/completions`, `/v1/completions`, `/v1/embeddings`, `/v1/models`, `/v1/responses`(상태 없는 요청만). `logprobs` 처럼 지원하지 않는 필드도 있으니 옮길 때 확인하세요.

또한 Anthropic 호환 API 도 제공하므로, `ollama launch claude` 로 Claude Code 를 로컬/Ollama 클라우드 모델에 붙일 수 있습니다. → [Claude Code](./01-claude-code)

## 임베딩 모델 사용

RAG 의 인덱싱 단계는 호출량이 많아 로컬 임베딩의 이득이 가장 큰 곳입니다. → [임베딩 개념](/ai/02-llm/02-embedding), [RAG](/ai/04-rag/01-rag)

```bash
ollama pull embeddinggemma

curl http://localhost:11434/api/embed -d '{
  "model": "embeddinggemma",
  "input": ["첫 번째 문서", "두 번째 문서"]
}'
# → { "embeddings": [[...], [...]], ... }
```

- `input` 에 문자열 하나 또는 배열을 넣습니다. **배열로 묶어 보내면** 요청 수가 줄어 훨씬 빠릅니다.
- `/api/embed` 가 돌려주는 벡터는 **L2 정규화**되어 있어, 코사인 유사도와 내적이 같은 값이 됩니다.
- 공식 문서가 권하는 모델: `embeddinggemma`, `qwen3-embedding`, `all-minilm`. 한국어 비중이 크면 다국어 모델(BGE-M3 계열 등)을 후보에 넣고 **자기 데이터로 검색 품질을 비교**해서 고르세요.

LangChain 에서는 `@langchain/ollama` 패키지로 붙입니다.

```typescript
import { ChatOllama, OllamaEmbeddings } from "@langchain/ollama";

const llm = new ChatOllama({ model: "gemma4", temperature: 0 });
const embeddings = new OllamaEmbeddings({ model: "embeddinggemma" });

const vector = await embeddings.embedQuery("로컬 임베딩 테스트");
```

> ⚠️ **함정**: 인덱싱할 때와 검색할 때 **같은 임베딩 모델(같은 태그·양자화)** 을 써야 합니다. 모델을 바꾸면 벡터 공간이 달라져 기존 인덱스를 전부 다시 만들어야 합니다. 태그를 생략한 `latest` 는 나중에 가리키는 대상이 바뀔 수 있으니 운영에서는 태그를 고정하세요.

## 로컬 모델 선택 팁

| 기준 | 가이드 |
|---|---|
| **메모리** | 대략 `파라미터 수 × 양자화 비트 / 8` + 컨텍스트(KV 캐시) 여유분. 4bit 8B 모델은 가중치만 약 5GB |
| **GPU 적재율** | `ollama ps` 의 `PROCESSOR` 가 `100% GPU` 가 아니면 일부가 CPU 로 넘어가 **속도가 급락**합니다. 한 단계 작은 모델이나 양자화를 고르세요 |
| **양자화** | `Q4_K_M` 이 속도·품질 균형의 기본값. 품질이 아쉬우면 `Q5_K_M`/`Q8_0`, 메모리가 부족하면 더 낮게 |
| **용도별** | 채팅·요약은 범용 instruct 모델, 에이전트는 **도구 호출을 지원하는 모델**(모델 페이지의 tools 표시), 코드 작업은 코드 특화 모델, 검색은 임베딩 전용 모델 |
| **컨텍스트** | 모델이 긴 컨텍스트를 지원해도 `num_ctx` 를 올리지 않으면 쓰지 못합니다 |
| **검증** | 벤치마크 순위보다 **내 작업 샘플 20~30개로 직접 비교**하는 것이 정확합니다. `--verbose` 로 속도도 함께 기록하세요 → [평가](/ai/07-evaluation/01-evaluation) |

> ⚠️ **함정**: 로컬 소형 모델은 도구 호출 형식을 자주 틀리거나 아예 도구를 부르지 않습니다. 클라우드 모델로 만든 에이전트를 그대로 로컬 모델로 바꾸면 **에러 없이 품질만 떨어집니다.** 모델을 바꿀 때마다 평가셋을 다시 돌리세요.

## 참고 자료

- [Ollama 공식 문서](https://docs.ollama.com/)
- [Ollama — CLI Reference](https://docs.ollama.com/cli)
- [Ollama — Modelfile Reference](https://docs.ollama.com/modelfile)
- [Ollama — Chat API](https://docs.ollama.com/api/chat)
- [Ollama — OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [Ollama — Embeddings](https://docs.ollama.com/capabilities/embeddings)
- [Ollama — FAQ](https://docs.ollama.com/faq)
- [Hugging Face — Use Ollama with any GGUF Model](https://huggingface.co/docs/hub/ollama)
- [GitHub — ollama/ollama](https://github.com/ollama/ollama)
