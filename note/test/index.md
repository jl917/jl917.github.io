# 测试

## 测试种类

- 单元测试(Unit Test)

  每个模块或者函数进行测试

- 集成测试(Integration Testing)

  验证每个单元之间传递的正确性和实现性

- 用户验收测试(User Acceptance Test)

  根据环境来进行功能以及性能的测试

- 系统测试(System Testing)

  用户自己来测试是否达到需求

- 黑盒测试(数据驱动测试)

  不需要知道软件的具体代码， 数据的输入和输出

- 白盒测试(单元测试)

  程序员自己根据代码来写实现

## 测试工具

### Client/Server 自动化

- qtp
- winrunner(IBM)
- autoit

### 功能测试

- jest(vitest)
- Coverage
- Unit Test
- Integration Test (Module Integration Test)

### 界面测试

- cypress
- pupptter

### 兼容性

- 跨浏览器(包括手机端) **https://www.browserstack.com/**
- 软件环境(版本环境)
- 硬件环境
- 系统环境

### 性能测试

- loadrunner
- lighthouse **https://developers.google.com/web/tools/lighthouse/**
- pagespeed **https://developers.google.com/speed/pagespeed/insights/**

```sh
brew install wrk
wrk -t12 -c400 -d30s http://127.0.0.1:8080/index.html

npm install -g loadtest
loadtest http://localhost:8054/test -t 20 -c 100
```

### 接口测试

- Jmeter
- postman
- SoapUI

### 网络测试

- lxia
- wireshark
- tc
- iperf
- tcpping

### web 安全测试

- appscan
- Netsparker community Edition
- Websecurify
- Wapiti
- N-Stalker Free Version
- Scrawlr
- Watcher
- WebScrab
- 授权测试

### 抓包

- fiddler
- burpsuite
- reqable


## 其他
### 提升产品质量

- 编写测试用例并且加强对应的一个评审
- 加强对需求的理解
- 加强对问题的跟进
- 交叉测试

### Mock 的好处

1. 由于其他系统模块出错引起本模块的测试错误，我们可以采用 mock 隔离，避免干预；
2. 开发过程中，只要交互双方定义好接口，团队之间可以并行工作，进程互不影响；
3. 依赖系统无法响应，或者响应异常时，可以用 mock Object 代替，快速反应，不会影响测试进度；
4. 提前接入测试，提供测试效率，当接口定义好后，测试人员就可以创建 Mock，把接口添加到自动化测试环境，提前开始测试，起到测试驱动开发效果；
5. 可以有效的增加覆盖，接口涉及入参，或者业务逻辑复杂的情况，某些场景无法通过正常手段进行操作，可以通过 mock 虚拟模拟；

### 易用性 - Easy of Use

- 符合标准和规范
- 直观
- 一致
- 灵活
- 舒适
- 正确
- 实用
