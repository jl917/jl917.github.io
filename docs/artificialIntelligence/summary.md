# 简述

## Prompt 提示词工程

- 要求
    - 指令具体
    - 信息丰富
    - 尽量少歧义
- 角色
    - 你是一个专业的金融投资研究助手 你非常严谨

## RAG Retrieval-Augmented Generation

- 通过外部资源或数据库中纳入相关信息来实现

## Function Calling

- 与外部函数或api交互的能力
- 询问用户 补全信息
- 基于LLM的语言理解能力 通过理解语义 自主决策使用某项工具 并结构化调用

## Agent 智能体

- LLM Based Agent
    - 概念
        - 无状态
        - 有状态
        - 连续
        - 离散
    - 目标
        - 将无状态输出的大模型变成了有状态输出的逻辑大脑目标也是工业化
        - 用好Agent关键是思考那些东西值得被离散化，状态化
        - 不断测试大模型能力并研究如何提高状态判断的准确度
- Multi-Agent Model

## Fine tuning 微调

- step1 收集示范数据， 并制定监督政策 - 准备很多prompt
- step2 收集比较数据，并训练奖励模型 - Fine tuning
- step3 使用强化学习针对奖励模型优化政策
