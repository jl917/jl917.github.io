# Agent

### 소개

Google 이 [《Agents》 whitepaper 에서정의](https://www.kaggle.com/whitepaper-agents)정의한 바에 의하면.

인공 지능(AI) 에이전트는 가용 도구를 사용해서 워크플로를 설계하고 작업을 자동으로 수행하는 시스템입니다.
AI 에이전트는 자연어 처리 외에도 의사 결정, 문제 해결, 외부 환경과의 상호 작용, 작업 실행 등 다양한 기능을 포괄할 수 있습니다.

### 핵심원칙

1. **인식.** 이는 에이전트가 작동하는 공간을 의미합니다. 이는 도로, 창고 또는 집의 바닥과 같은 물리적 공간일 수 있습니다. 또는 웹사이트나 서버와 같은 디지털 공간일 수 있습니다. 에이전트는 센서를 통해 그들의 환경을 평가하고 인식합니다. 자율주행 자동차의 입력은 센서, 카메라, 레이더가 될 수 있습니다. 한편, 챗봇은 고객의 질문이나 피드백에서 입력을 받습니다.
2. **추론.** 그러면 에이전트가 어떤 결정을 내려야 할지 결정할 수 있습니다. 에이전트는 지식 기반을 바탕으로 규칙 기반 시스템, 머신 러닝 모델 또는 다른 알고리즘을 사용하여 의사결정을 안내할 수 있습니다.
3. **목표 설정.** AI는 사전 정의된 목표 또는 사용자 입력을 기반으로 목표를 설정합니다. 그런 다음 의사 결정 트리, 강화 학습 또는 기타 계획 알고리즘을 사용하여 이러한 목표를 달성하기 위한 전략을 개발합니다.
4. **결정.** 지식 기반 에이전트는 메모리와 세계에 대한 이해를 바탕으로 결정을 내립니다. 그것은 사용자의 목표와 객체와 이벤트 간의 관계를 고려합니다.
5. **행동.** 한 번 결정이 내려지면, 에이전트는 환경 내에서 행동합니다.
6. **학습.** AI 에이전트를 단순한 AI 시스템과 구별하는 것은 그것이 학습하고, 세밀화하고, 그것의 결정 만들기를 향상시킬 수 있는 능력입니다. 시간이 지남에 따라 에이전트는 그 경험에서 배우고 더욱 능숙하고 최적화됩니다.

### 구성요소

- 인식
  - 시각적
  - 청각적
  - 텍스트
  - 환경적
  - 예측적
- 추론
  - 조건부 논리
  - 휴리스틱
  - ReAct(이유+행동)
  - ReWOO(관찰 없이 추론하기 reasoning without observation)
  - 자기반성
- 메모리
  - 단기
  - 장기(에피소드, 의미, 절차 등등)
- 계획 수립(Plan)
  - 목표 정의
  - 상태 표현
  - 행동 순서화
  - 최적화 및 평가
  - 협업
- 도구 사용(Tool)
- 커뮤니케이션
  - agent간 통신
    - KQML(Knowledge Query and Manipulation Language)
    - FIPA-ACL(Foundation for Intelligent Physical Agents – Agent Communication Language)
  - 인간-AI 통신
    - 자연어 처리(Natural Language Processing), 음성 인식 등등
- 러닝
  - 지도 학습 supervised-learning
  - 비지도 학습 unsupervised-learning
  - 강화 학습
  - 지속적인 학습
  - 다중 에이전트 학습

### 구현

1. **문제를 정의**
2. **데이터, 도구 준비**
3. **적절한 AI 모델을 선택**
4. **지속적인 모니터링**
5. **성공 측정 및 평가**

### 워크플로우 / AI 어시스턴트 / AI 에이전트

워크플로는 LLM(Learning Leadership Machine)과 도구가 미리 정의된 코드 경로를 통해 조율되는 시스템입니다.

AI 에이전트

에이전트는 LLM이 자체 프로세스와 도구 사용을 동적으로 지시하고 작업 수행 방식을 제어하는 시스템입니다.

AI 어시스턴트

**AI 어시스턴트**는 사용자의 직접적인 요청이나 명령에 **반응하여(Reactive)** 작업을 수행합니다.

### 기타

##### 流程

- 规划 Planning
  - 概念
    - 观察与思考怎么完成任务
    - 利用拥有的工具实现目的
    - 任务拆分成子任务
    - 执行任务的过程中进行反思和完善 吸取教训以完善未来的步骤
- 记忆 Memory
  - 分类
    - 感觉记忆(Sensory Memory)
    - 短期记忆
    - 长期记忆
- 工具 Tools
  - 预制工具
    - Bing Search
    - dall-E Image
  - 自定义工具
  - 工具集
- 执行 Action
  - 执行任务
  - 反馈结果

##### 举例

1. 输入: 请问现任美国总统是谁？他的年龄的平方是多少？请用中文告诉我这两个问题
2. 规划: 我需要使用搜索引擎来找到美国现任总统的名字 然后使用计算器来计算他的年龄的平方‘
3. 执行: search
4. 执行输入: 美国现任总统
5. Observation: joe biden
6. Thought: 现任美国总统是 joe biden
7. 执行: Calculator
8. 执行输入: 68^2
9. Observation: 4624
10. Thought: 我现在知道了美国现任总统是 joe biden 他的年龄的平方是 4624
11. Final Answer: 美国现任总统是 joe biden 他的年龄的平方是 4624

##### 框架

- Plan and Execute
  1. user request
  2. plan
  3. generate tasks
  4. exec tasks(single task agent(loop))
  5. update state with task results
  6. rePlan
  7. response to user
- Self Ask
- Thiking and Self-Reflection 思考并自我反思
  - 框架主要用于模拟和实现复杂决策过程，通过不断自我评估和调整，使系统能够学习并改进决策过程，从而在面对复杂问题时做出更加有效的决策
  - Thinking
    - LLM ⇒ Thougth1 ⇒ Action1 ⇒ Thougth2 ⇒ Action2 ⇒ Thougth3 ⇒ Action3 ⇒ End
  - Self-Reflection
    - LLM ⇒ Thougth1 ⇒ Action1 ⇒ LLM ⇒ Thougth2 ⇒ Action2 ⇒ LLM ⇒ End
