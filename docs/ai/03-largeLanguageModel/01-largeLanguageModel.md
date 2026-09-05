# Large Language Model 대형 언어 모델

대형 언어 모델(LLM, Large Language Model)은 방대한 데이터로 사전학습된 초대형 딥러닝 모델이다.

### 아키텍처

- Transformer

### token

- tokenization은 텍스트를 더 작은 단위(token)로 쪼개는 과정이다. 보통 단어, 서브워드, 문자 단위로 나뉜다.
- 자주 쓰이는 토크나이저 알고리즘
  - BPE(Byte Pair Encoding): 가장 빈번하게 붙어 나오는 바이트 쌍을 반복적으로 병합해 서브워드 단위를 만든다.
  - WordPiece: BPE와 비슷하지만 병합 과정에서 단어 빈도와 언어 모델 성능을 함께 고려한다.
  - ULM(Unigram Language Model): 확률 모델에 기반한 분할 방식으로, 가장 그럴듯한 서브워드 조합을 선택한다.
  - SentencePiece: Google이 만든 토크나이저 도구. 여러 분할 알고리즘을 지원하고 미등록 단어(OOV)도 처리할 수 있다.

### 모델을 구분하는 기준

- 구조에 따른 구분
  - decoder-only 모델: GPT 계열. 주로 텍스트 생성 작업에 쓰인다.
  - encoder-only 모델: BERT 계열. 주로 텍스트 이해 작업에 쓰인다.
  - encoder-decoder 모델: T5 계열. 생성과 이해 양쪽에 모두 쓸 수 있다.
- 규모에 따른 구분
  - 소형 모델: 파라미터가 수백만~수억 규모. 자원이 제한된 환경에 적합하다.
  - 대형 모델: 파라미터가 수십억~수백억 규모. 높은 성능이 필요한 애플리케이션에 적합하다.
  - 초대형 모델: 파라미터가 수백억을 넘어가는 규모. GPT-4처럼 매우 높은 성능이 요구되는 곳에 쓰인다.
- 모달리티에 따른 구분
  - 단일 모달 모델: 텍스트처럼 한 종류의 데이터만 처리한다.
  - 멀티모달 모델: 텍스트, 이미지, 오디오 등 여러 종류의 데이터를 함께 처리한다.
- 파인튜닝 방식에 따른 구분
  - 파인튜닝을 거치지 않은 베이스 모델
  - 인스트럭션 튜닝을 거친 모델
  - 인간 피드백 강화학습(RLHF)까지 거친 모델

### 대형 모델 개발 흐름

1. 목표와 요구사항 정의
2. 데이터 수집과 전처리
3. 모델 선택과 아키텍처 설계
4. 모델 학습 — 사전학습과 파인튜닝
5. 모델 평가와 최적화
6. 배포와 서비스 적용
7. 모니터링과 유지보수

### 추론모델 vs 비추론모델

| 항목       | 추론모델                 | 비추론모델           |
| -------- | -------------------- | --------------- |
| 문제 해결 방식 | 단계적 사고               | 즉시 응답           |
| 강한 영역    | 수학, 논리, 계획, 복잡한 의사결정 | 일반 대화, 요약, 글쓰기  |
| 속도       | 느릴 수 있음              | 빠름              |
| 비용(연산량)  | 높음                   | 낮음              |
| 실수 패턴    | 느리지만 논리적             | 빠르지만 피상적        |
| 긴 문제 처리  | 매우 강함                | 중간에 논리 점프 발생 가능 |

### 학습 방식의 종류

- 전이학습(Transfer learning): 한 도메인에서 사전학습한 베이스 모델을 다른 도메인으로 옮겨 파인튜닝한다.
- 제로샷 학습(Zero-shot learning): 별도 학습 없이 프롬프트만으로 다양한 요청에 응답한다. 다만 답변의 정확도는 편차가 크다.
- 퓨샷 학습(Few-shot learning): 관련 예시를 몇 개 제공하면 해당 영역에서의 성능이 눈에 띄게 올라간다.
- 지속학습(Continual learning): 새로운 데이터를 계속 받아들이면서도 이전에 배운 지식을 잊지 않고 유지·활용한다.
- 멀티태스크 학습(Multi-task learning): 여러 관련 작업을 동시에 학습해 일반화 성능을 끌어올린다.
- 강화학습(Reinforcement learning): 환경과 상호작용하며 보상 신호를 기준으로 행동 정책을 최적화한다.
- 파인튜닝(Fine-tuning): 퓨샷 학습의 확장이다. 특정 용도에 맞는 추가 데이터로 베이스 모델의 파라미터를 조정한다.

### 학습 과정

- 사전학습(Pretrain)
- 지도 파인튜닝(Supervised Fine-Tuning, SFT)
- 인간 피드백 강화학습(Reinforcement Learning with Human Feedback, RLHF)

### 특징

- 규모와 파라미터 수가 크다
- 적응성과 유연성이 높다
- 광범위한 데이터셋으로 사전학습한다
- 연산 자원 요구량이 크다

### 분류

- 대형 언어 모델 LLM
  - GPT-3, GPT-4
  - Bard
  - Qwen(통이첸원)
  - DeepSeek
- 멀티모달 모델
  - 컴퓨터 비전 모델
  - 오디오 처리 모델
  - ….

### 동작 흐름

- 토큰화(Tokenization)와 어휘 사전 매핑
  - 단어 단위(Word-Level Tokenization)
  - 문자 단위(Character-Level)
  - 서브워드 단위(Subword-Level)

### 파라미터 설정

- temperature
  - `temperature` 값이 낮을수록 항상 가장 확률이 높은 토큰(말뭉치의 최소 단위)이 선택되기 때문에 더 결정론적인 결과를 낳습니다. temperature 값을 높였을 때 모델이 선택하는 토큰의 무작위성이 증가하여 보다 다양하고 창조적인 결과를 촉진합니다. 이는 다른 가능한 토큰의 가중치를 증가시키는 것과 같습니다. 애플리케이션의 경우, 사실을 기반으로 하는 질의응답과 같은 작업에는 낮은 temperature 값을 사용하여 보다 사실적이고 간결한 응답을 얻을 수 있습니다. 시를 생성하는 등 다른 창의적인 작업의 경우에는 temperature 값을 높이는 것이 도움이 될 수 있습니다.
- top_p
  - temperature를 활용하는 핵 샘플링 기법인 `top_p`를 사용하면 모델이 응답을 생성하는 결정성을 제어할 수 있습니다. 정확하고 사실적인 답변을 원한다면 이를 낮게 유지합니다. 더 다양한 반응을 원한다면 더 높은 값으로 증가시킵니다.
- Max Length
  - `max length`를 조정하여 모델이 생성하는 토큰의 수를 관리할 수 있습니다. max length를 지정하면 길거나 관련 없는 응답을 방지하고 제어 비용을 관리하는데 도움이 될 수 있습니다.
- Stop Sequences
  - `stop sequence`는 모델의 토큰 생성을 중지하는 문자열입니다. stop sequences를 지정하는 것은 모델의 응답 길이 및 구조를 제어하는데 도움이 될 수 있습니다. 예를 들어, stop sequence로 "11"을 추가하여 항목이 10개를 초과하지 않는 리스트를 생성하도록 모델에 지시할 수 있습니다.
- Frequency Penalty
  - `frequency penalty`는 해당 토큰이 응답 및 프롬프트에 등장한 빈도에 비례하여 다음에 등장할 토큰에 불이익을 적용합니다. frequency penalty가 높을수록 단어가 다시 등장할 가능성이 줄어듭니다. 이 설정은 자주 등장하는 토큰에 대하여 더 많은 페널티를 부여하여 모델의 응답에서 단어의 반복을 방지합니다.
- Presence Penalty
  - `presence penalty`는 반복되는 토큰에 패널티를 적용하지만, frequency penalty와 달리 모든 토큰에 동일한 페널티가 적용됩니다. 다시 말해, 토큰이 2회 등장하는 토큰과 10회 등장하는 토큰이 동일한 페널티를 받습니다. 이 설정은 모델이 응답에서 구문을 너무 자주 반복하는 것을 방지합니다. 다양하거나 창의적인 텍스트를 생성하기 위해 더 높은 presence penalty를 사용할 수 있습니다. 혹은 모델이 집중력을 유지해야 할 경우(사실을 기반으로) 더 낮은 presence penalty를 사용할 수 있습니다.
- verbose
  - 실행 과정을 그대로 출력합니다.

### 용어

- NLP(Natural Language Processing): 자연어 처리. 컴퓨터와 인간 언어 사이의 상호작용을 다루는 분야.
- RNN(Recurrent Neural Network): 순환 신경망. 순차 데이터 처리에 적합하다.
- LSTM(Long Short-Term Memory): 장단기 메모리 네트워크. RNN의 변형으로, 멀리 떨어진 토큰 사이의 의존 관계를 포착할 수 있다.
- Transformer: 셀프 어텐션 메커니즘에 기반한 모델 아키텍처. 자연어 처리 전반에 널리 쓰인다.
- Word2Vec: 단어를 연속적인 벡터 공간에 매핑해 단어 사이의 의미 관계를 담아내는 기법.
- BERT(Bidirectional Encoder Representations from Transformers): 문맥 정보를 양방향으로 포착하는 사전학습 언어 표현 모델.
- GPT(Generative Pre-trained Transformer): Transformer 아키텍처 기반의 생성형 사전학습 모델. 텍스트 생성에 강하다.
- PGC(Professional Generated Content): 전문가가 제작한 콘텐츠.
- UGC(User Generated Content): 일반 사용자가 제작한 콘텐츠.
- AIGC(Artificial Intelligence Generated Content): 인공지능 시스템이 생성한 콘텐츠.
- RLHF(Reinforcement Learning with Human Feedback): 강화학습과 인간 피드백을 결합해 모델의 행동을 정교하게 다듬는 학습 방법.

### 참고 링크

- https://www.tensorflow.org/tutorials?hl=ko
- https://bbycroft.net/llm
- https://huggingface.co/
- https://www.modelscope.ai/
- https://ollama.ai/
- https://zhuanlan.zhihu.com/p/1947349437224558654
- https://github.com/jingyaogong/minimind
