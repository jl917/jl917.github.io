# Agent 智能体

### 介绍

Google 在 [《Agents》 白皮书所定义](https://www.kaggle.com/whitepaper-agents)的， AI 代理是可以在不须用人类介入的状况下，根据指定目标，去完成相关任务。

### Agent 种类

- 文本（text）
- 图片（image）
- 视频（video）
- 音频（audio）
- 语音（speech）
- 传感器数据（sensor）
- 结构化数据（tables, graph）
- ....
- Multi-Modal Agent

### 流程

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

  ### 举例

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

### 框架

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
