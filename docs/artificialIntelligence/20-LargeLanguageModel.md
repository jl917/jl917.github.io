# Large Language Model 大模型

大型语言模型，也称为 LLM，是基于大量数据进行预训练的超大型深度学习模型

### 架构

- transfomer

### 추론모델 vs 비추론모델
| 항목       | 추론모델                 | 비추론모델           |
| -------- | -------------------- | --------------- |
| 문제 해결 방식 | 단계적 사고               | 즉시 응답           |
| 강한 영역    | 수학, 논리, 계획, 복잡한 의사결정 | 일반 대화, 요약, 글쓰기  |
| 속도       | 느릴 수 있음              | 빠름              |
| 비용(연산량)  | 높음                   | 낮음              |
| 실수 패턴    | 느리지만 논리적             | 빠르지만 피상적        |
| 긴 문제 처리  | 매우 강함                | 중간에 논리 점프 발생 가능 |


### 训练种类

- 零样本学习(Zero-shot learning)：Base LLM 无需明确训练即可响应各种请求，通常是通过提示，但是答案的准确性各不相同。
- 少量样本学习(Few-shot learning)：通过提供一些相关的训练示例，基础模型在该特定领域的表现显著提升。
- 微调(Fine-tuning)：这是少量样本学习的扩展，其中数据科学家训练基础模型，使模型使用与特定应用相关的其他数据来调整其参数。

### 训练过程

- 预训练（Pretrain）
- 监督微调（Supervised Fine-Tuning, SFT）
- 人类反馈强化学习（Reinforcement Learning with Human Feedback, RLHF）

### 特点

- 规模和参数量大
- 适应性和灵活性强
- 广泛数据集预训练
- 计算资源需求大

### 分类

- 大语言模型 LLM
  - GPT-3, GPT-4
  - Bard
  - 通义千问
  - Deepseek
- 多模态模型
  - 计算机视觉模型
  - 音频处理模型
  - ….

### 工作流程

- 分词化(Tokenization)与词表映射
  - 词粒度(Word-Level Tokenization)
  - 字符力度(Character-Level)
  - 子词粒度(Subword-Level)

### 参数设置

- temperature
  - `temperature` 값이 낮을수록 항상 가장 확률이 높은 토큰(말뭉치의 최소 단위)이 선택되기 때문에 더 결정론적인 결과를 낳습니다. temperature 값을 높였을 때 모델이 선택하는 토큰의 무작위성이 증가하여 보다 다양하고 창조적인 결과를 촉진합니다. 이는 다른 가능한 토큰의 가중치를 증가시키는 것과 같습니다. 애플리케이션의 경우, 사실을 기반으로 하는 질의응답과 같은 작업에는 낮은 temperature 값을 사용하여 보다 사실적이고 간결한 응답을 얻을 수 있습니다. 시를 생성하는 등 다른 창의적인 작업의 경우에는 temperature 값을 높이는 것이 도움이 될 수 있습니다.
- top_p
  - temperature를 활용하는 핵 샘플링 기법인 `top_p`를 사용하면 모델이 응답을 생성하는 결정성을 제어할 수 있습니다. 정확하고 사실적인 답변을 원한다면 이를 낮게 유지합니다. 더 다양한 반응을 원한다면 더 높은 값으로 증가시킵니다.
- Max Length
  - `max length`를 조정하여 모델이 생성하는 토큰의 수를 관리할 수 있습니다. max length를 지정하면 길거나 관련 없는 응답을 방지하고 제어 비용을 관리하는데 도움이 될 수 있습니다.
- Stop Sequences
  - `stop sequence`는 모델의 토큰 생성을 중지하는 문자열입니다. stop sequences를 지정하는 것은 모델의 응답 길이 및 구조를 제어하는데 도움이 될 수 있습니다. 예를 들어, stop sequence로 "11"을 추가하여 항목이 10개를 초과하지 않는 리스트를 생성하도록 모델에 지시할 수 있습니다.
- Frequency Penalty
  - `frequency penalty`는 해당 토큰이 응답 및 프롬프트에 등장한 빈도에 비례하여 다음에 등장할 토큰에 불이익을 적용합니다. frequency penalty가 높을수록 단어가 다시 등장할 가능성이 줄어듭니다. 이 설정은 자주 등장하는 토큰에 대하여 더 많은 페널티를 부여하여 모델의 응답에서 단어의 반복을 방지합니다.
- Presence Penalty
  - `presence penalty`는 반복되는 토큰에 패널티를 적용하지만, frequency penalty와 달리 모든 토큰에 동일한 페널티가 적용됩니다. 다시 말해, 토큰이 2회 등장하는 토큰과 10회 등장하는 토큰이 동일한 페널티를 받습니다. 이 설정은 모델이 응답에서 구문을 너무 자주 반복하는 것을 방지합니다. 다양하거나 창의적인 텍스트를 생성하기 위해 더 높은 presence penalty를 사용할 수 있습니다. 혹은 모델이 집중력을 유지해야 할 경우(사실을 기반으로) 더 낮은 presence penalty를 사용할 수 있습니다.

- verbose
    - 显示执行过程

### 参考链接

- https://www.tensorflow.org/tutorials?hl=ko
- https://bbycroft.net/llm
- https://huggingface.co/
- https://www.modelscope.ai/
- https://ollama.ai/
- https://zhuanlan.zhihu.com/p/1947349437224558654
