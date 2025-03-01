# 关键性能指标

## 连接性能

### 延迟

延迟是指 IP 数据包从一个网络端点到另一个网络端点所话费的时间。与之相关的是往返延时(RTT - Rount-Trip Time), 它是延迟的时间的两倍。延迟是制约 Web 性能的主要瓶颈，尤其对于 HTTP 这样的协议，因为其中包含大量往返于服务器的请求

(一些移动设备为节省电力，可能暂时关闭移动数据信号。如果设备需要临时唤醒移动数据设备，建立新连接时还要增加数秒的延迟)

### 宽带

只要宽带没有饱和，两个网络端点之间的连接会一次处理尽可能多的数据量。依据 Web 页面饮用资源的大小和网络连接的传输能力，宽带可能会成为性能的瓶颈

### DNS 查询

在客户端能够获取 Web 页面钱，它需要通过域名系统把主机名称转换成 IP 地址，DNS 相当于互联网上的电话号码薄。获取的 HTML 页面中所引用；哦你给的各个不同余名也需要转换；幸运的事，一个域名只需转换一次

### 建立连接时间

在客户端和服务器之间建立连接需要往返数据应答， 成为‘三次握手’。

### TLS(Transport Layer Security)协商时间

如果客户端发起 HTTPS 连接，它需要进行 TLS 协商; TLS 用来取代 SSL(Secure Sockets Layer)。除了服务器和客户端的计算处理耗时之外，TLS 还会造成额外的往返传输

### 单点故障(SPOF - Single Point Of Failure)

Web 页面上引用的某个资源，如果它出问题，将延迟整个页面的加载(甚至导致页面出错)。

## 服务器或内容

### TTFB(Time To First Byte)

客户端从开始定位到 Web 页面，直接收到主体页面响应的第一字节所耗费的时间。它包含了之前连接性能各种耗时，还要加上服务器的处理时间。对于主体页面上的资源，TTFB 测量的是从浏览器发起请求至收到其第一字节之间的耗时。

### Content Download

等同于被请求资源的最后字节到达时间。

### 开始渲染时间(Time to First Meaningful Paint)

客户端的屏幕上什么时候开始现实内容？这个指标测量的是用户看到空白页面的时长

### 文档加载完成时间

这是客户端浏览器认为页面加载完毕的时间

## 额外考虑

- 更多的字节
- 更多的资源
- 更高的复杂度
- 更多的域名
- 更多的 TCP socket

## 网络

#### 减少 http 请求

- 合并 js

- 合并 css

- css sprite

- base64

#### 减少资源大小

- html, css, js minify
- gzip 压缩
- image minify
- 不滥用字体

#### 缓存

- DNS 缓存

```html
<link rel="dns-prefetch" href="//ajax.googleapis.com" />
```

- http 缓存(Cache-Control, E-tag)
- 部署 CDN
- 使用长缓存
- 避免重定向

- TCP 连接(参考: https://istlsfastyet.com/)

```html
<link rel="precontent" href="//fonts.google.com" crossorigin />
```

- 避免阻塞 CSS/JS

## 浏览器渲染

### DOM 优化

- 避免进行繁琐的 DOM 操作
- 复杂的 UI 元素, 设置 position 为 absolute 或者 fixed
- requestAnimationFrame 代替 setTimeout
- 适当使用 canvas
- 使用事件代理

### 样式优化

- 尽量避免内联样式
- 禁用 css Expression
- 尽量使用 css 动画

### html 优化

- css 文件放头部, js 放底部或者异步处理

### 图片优化

- Lazyload

### 异步

- 常用数据缓存
