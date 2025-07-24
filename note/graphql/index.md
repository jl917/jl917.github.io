# GraphQL

- GraphQL 是一种协议和一种 API 查询语言(实现数据查询的 runtime), 通常基于 http 协议
- GraphQL 是客户端和服务器之间通信的规范。

## 优点

### 1.提高开发速度

- 可以减少发出请求
- 单个调用来获取所需数据
- 减少延迟， 还能降低服务器的压力
- GraphQL 具有自文档的特点

### 2.提升开发者体验

- 更少的时间思考如何获取数据
- 在使用 Apollo 时，只需要在 UI 中声明数据
- 数据和 UI 放在一起，阅读代码和编写代码都变得更方便了
- GraphQL 之于数据， 就如 React 之于 UI

### 3.将复杂的 API 进行简化和标准化

- 可以按照需求自由组合和嵌套对象
- 对于每个对象都能够获得所需的数据，不多也不少

### 4.提升安全性

- 需要进行 schema 验证，而且是强类型的，因为这是它规范的一部分。
- 它可以频繁更新，而不会因为引入了新类型造成重大变更。

## 设计原则

- 分层(查询字段层次分明。字段嵌套在其他字段当中，查询字段的结构与其返回的数据结构相似)
- 以产品为中心(客户端所需的数据，以及客户端支持的语言和 runtime)
- 强类型(每个数据点在模版当中都有其特定的类型，并且均会进行验证)
- 客户端指定查询(服务器提供功能供客户端使用)
- 类型自查(能够查询 GraphQL 服务器的类型检测系统)

## REST 缺点

- 过量获取
- 缺乏灵活性

## 术语

- SEQL(Structured English Query Language) - 结构化英文查询语言
- SQL(Structured Query Language) - 结构化查询语言
- SDL(Schema Definition Language) - 模版定义语言
- scalar type - 标量类型（String, Int, Boolean, ID, Float）
- ID(Identity Document)
