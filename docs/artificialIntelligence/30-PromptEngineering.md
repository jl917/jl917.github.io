# Prompt Engineering 提示词工程

## 구성요소

- **Instruction** 지시
- **Context** 문맥
- **Input Data** 입력 데이터
- **Output Indicator** 출력 지시자

## 예시

- 문장 요약
- 정보 추출
- 질의응답
- 텍스트 분류
- 대화
- 코드 생성
- 추론

## Best practices

- 최신 모델 사용
- 프롬프트 시작 부분에 지침을 넣고 ### 또는 “””를 사용하여 지침과 맥락을 구분
- 원하는 맥락, 결과, 길이, 형식, 스타일 등에 대해 구체적이고 설명적이며 가능한 한 자세하게 작성
- 예시를 통해 원하는 출력 형식을 명확하게 표현
- 제로샷(zero-shot)으로 시작한 다음, 퓨샷(few-shot)으로 진행하여 둘 다 작동하지 않으면 미세 조정
- "불분명하고" 부정확한 설명을 줄임
- 단순히 무엇을 하지 말아야 하는지 말하는 대신, 무엇을 해야 하는지 설명
- 코드 생성에 특화 - "선행 단어? 힌트"를 사용하여 모델을 특정 패턴으로 유도.
- Use the Generate Anything feature??????

## Techniques

- Zero-shot
  - 대량의 데이터를 학습하고 지침을 따르도록 튜닝된 오늘날의 머신러닝은 제로샷(zero-shot)으로 작업을 수행할 수 있습니다
- Few-shot
  - 퓨샷(few-shot) 프롬프트는 프롬프트에서 데모를 제공하여 모델이 더 나은 성능을 발휘하도록 유도하는 문맥 내 학습을 가능하게 하는 기술로 사용할 수 있습니다
- Chain-of-Thought (CoT)
  - LLM의 추론(사고)를 통해서 어려운 질문을 답변하는 기술입니다. 복잡한 로직을 Step을 나눠서 단순화 하고 다시 답변하는 방식.(Let's think step by step)
  - 예시
    ```jsx
    다음 질문을 단계적으로 생각해서 답을 도출해줘.
    질문: 철수가 가지고 있는 사과가 10개이고, 그 중 3개를 친구에게 주었다. 남은 사과는 몇 개인가?
    ```
  - 참고
    - https://www.163.com/dy/article/J6BKPTPH051193U6.html
- Self-Consistency
  - 개념
    - 먼저 언어 모델의 디코더에서 샘플링하여 다양한 추론 경로 세트를 생성합니다. 각 추론 경로는 서로 다른 최종 답으로 이어질 수 있으므로, 샘플링된 추론 경로를 주변화하여 최적의 답을 결정합니다. 최종 답 세트에서 가장 일관된 답을 찾습니다. 이러한 접근 방식은 인간의 경험과 유사합니다.
  - 핵심
    - 여러경로를 통한 추론
    - 답안중 빈도가 제일 높은 답안이 출력
  - 특징
    - 복잡한 추론에 대한 정확도를 올릴수 있다.
    - 안정성을 높일수 있다.
  - 예시
    ```jsx
    다음 질문을 여러 번(다양한 샘플) 답한 뒤, 각 샘플의 최종 답을 모아 다수결로 최종답을 결정해줘.
    질문: 철수가 가지고 있는 사과가 10개이고, 그 중 3개를 친구에게 주었다. 남은 사과는 몇 개인가?
    ```
  - 참고
    - https://zhuanlan.zhihu.com/p/1945455658657875626
- Generated Knowledge Prompting
  - 개념
    - 사용자가 입력한 내용(지식) 기반으로 답변을 생성.
  - 정보
    - 실시간 업데이트 되는 정보(주식, 스포츠 스코어등등)
    - 정책, 계약서
    - 논문이라든가 전문분야의 내용
- [AGENT] Prompt Chaining
  - 개념
    - LLM의 안정성과 성능을 개선하기 위해 중요한 프롬프트 엔지니어링 기법 중 하나는 작업을 하위 작업으로 분할하는 것입니다. 이러한 하위 작업이 식별되면 LLM에 하위 작업에 대한 프롬프트가 표시되고 그 응답이 다른 프롬프트의 입력으로 사용됩니다. 프롬프트 연쇄라는 개념으로 작업을 하위 작업으로 분할하여 프롬프트 작업의 연쇄를 만드는 것을 프롬프트 체이닝 이라고 합니다.
- Tree of Thoughts

  - 개념
    - 하나의 질문에 대해 **여러 개의 사고 흐름을 동시에 전개**하고, 그중 최적의 결과를 선택하도록 유도하는 방식입니다. 브레인스토밍을 확장한 전략으로, **결정 경로를 나무(tree)처럼 분기**시켜 모델이 다양한 가능성을 탐색하게 합니다.
  - 예시
    ```jsx
    이 문제를 해결할 수 있는 여러 접근 방식을 제시하고, 각 방법을 평가한 뒤 최적의 해결책을 추천해줘.
    ```

- [AGENT] Retrieval Augmented Generation (RAG)
- [AGENT]Automatic Reasoning and Tool-use (ART)
- [AGENT] Automatic Prompt Engineer (APE)
- [AGENT] **Active-Prompt**
- [???] **Directional Stimulus Prompting**
- [AGENT] Program-Aided Language Models
- [AGENT] ReAct
  - 예시
    ```jsx
    질문: 대한민국의 2022년 1인당 GDP는 얼마였어?
    Thought → Action → Observation → Answer 순으로 단계별로 응답해줘.
    ```
- [AGENT] **Reflexion 是一种让 AI 智能体通过反思机制持续学习和优化的技术**‌

## Injection

시스템 가드레일을 무시하고 해서는 안 되는 말을 하게 만드는 것입니다.

- 방지
  - 안전 가드레일
  - 명시적 금지
  - 입력 유효성 검사 및 삭제
  - 이상 활동 감지
  - 매개변수화(시스템 명령과 사용자 입력을 명확하게 분리하는것.)
  - 아웃풋 필터링
  - 동적 피드백 및 학습
  - 내부 프롬프트 강화
  - 최소권한
  - 상황 및 시나리오 기반 안내

### OpenAI 官方推荐六大策略

1. 指令要清晰 (write clear instructions)

   ```txt
   // 范例1
   总结会议记录
   ---
   [会议记录]

   // 范例2
   用一个段落总结会议记录。然后写下演讲者的 Markdown 清单以及他们的每个要点。
   最后，列出发言人建议的后续步骤或行动项目。
   ---
   [会议记录]
   ```

2. 提供参照(provide reference text)

   ```txt
   使用下方提供的由三重引号内的文章来回答问题。
   如果在文章中找不到答案，请回答“我找不到答案”。
   ---
   """文章一"""
   """文章二"""
   """文章三"""

   问题：[问题]
   ```

3. 将复杂任务拆成简单的子任务 (split complex tasks into simpler subtasks)
   ```txt
   使用分类来让 GPT 参照处理子任务
   对于需要很长对话的对话应用，总结或过滤先前的对话
   ```
4. 让 GPTs 有时间思考 (give GPTs time to “think”)

   ```txt
   首先制定自己的问题解决方案。
   然后将你的解决方案与学生的解决方案进行比较，并评估学生的解决方案是否正确。
   在你自己完成问题之前，不要决定学生的解决方案是否正确。

    ---

    问题陈述：XXX
    学生的解答：XXX
   ```

5. 善用外部工具 (use external tools)

   ```txt
   // 范例：使用计算器
   你是一个有能力使用计算器的助手。
   当你需要进行数学计算时，请使用以下格式：
   计算：[数学表达式]
   例如：
   计算：23乘以47
   ```

6. 系统性测试改变 (test changes systematically)

   当我们要确定提示词的修改或策略时，会需要定义一个全面性的测试，用系统性的方式评估，才能确保提示词是最佳的，让这个改变对总体影响是正面的。

### 참고자료

- https://www.ibm.com/kr-ko/think/prompt-engineering#605511093
- https://help.openai.com/en/articles/6654000-best-practices-for-prompt-engineering-with-the-openai-api
- https://zhuanlan.zhihu.com/p/1947349437224558654
- https://www.promptingguide.ai/kr