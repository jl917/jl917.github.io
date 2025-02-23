# 测试最佳实践(中文)

一段测试代码需要做到让人一眼就能看出其目的。

## 方法

- 3 个部分(测试什么/什么环境/期望结果)
- AAA 模式构造内容(Arrange/Act/Assert 准备/执行/断言)
- 描述期望(Bdd 形式断言) https://jestjs.io/docs/expect#expectextendmatchers
- TDD
- UI 与功能分离(AAA)
- 测试用例标签(npx jest -t=#dao)

## mock

- 优先使用真实数据， 特定情况下使用 mock
- mock 尽量与真实数据同步
- 工具
  - sinon
  - test double
  - https://www.npmjs.com/package/faker

## type

- 基于属性的测试， 测试输入的多种组合

## DOM Tip

- 使用不太容易改变的属性去查询 HTML 元素

## 避免

- 坚持 public 方法, 减少 private 方法
- 尽量使用最短的 Snapshot
- 尽量避免全局的 fixtures 和 seeds(每条测试需要在它自己的 DB 行中运行避免互相污染)
- 不要 catch 错误而是 expect
- 不要 sleep，使用框架内置的对 async 事件的支持。并且尝试提效。(https://testing-library.com/docs/guide-disappearance/)

## 手动道具

- lighthouse https://developers.google.com/web/tools/lighthouse/
- pagespeed https://developers.google.com/speed/pagespeed/insights/

## E2E (https://github.com/puppeteer/puppeteer)

- 写几个跨越整个系统的端到端测试
- 通过复用登录凭证提速 E2E 测试
- 创建一个 E2E 冒烟测试，仅仅走一遍网站地图

## 测试报告

- 将测试以实时协作文档的形式公开(Storybook) https://storybook.js.org/addons/@storybook/addon-jest
- 检查覆盖率报告，以发现未覆盖的区域和其他奇怪的地方(coverage)
- 使用「变异测试」度量逻辑覆盖率????
- 使用 Test linter 防止测试代码问题

## CI 以及其他

- 丰富你的 linter 并丢弃有 lint 问题的构建
- 通过本地的开发 CI 来缩短反馈循环(husky)
- 在真实的生产环境镜像中执行端到端测试
- 并行测试工作
- 使用许可证和抄袭检查避免法务问题 (https://www.npmjs.com/package/license-checker)
- 持续检查有漏洞的依赖
- 自动升级依赖????
- 使用多个 Node 版本执行同一个 CI 流程(质量检查是用于发现意外，你覆盖的部分越多，你就越可能尽早地发现问题。 在开发包或运行具有各种配置和 Node 版本的多客户生产环境时，CI 必须在所有配置的组合上运行测试管道。)
