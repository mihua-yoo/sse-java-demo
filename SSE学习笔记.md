# SSE 学习笔记

这份笔记基于当前 `sse-learning-demo` 项目整理，用来复习 SSE、HTTP with SSE、Streamable HTTP，以及它们和 MCP 通信模型的关系。

## 1. 先理解普通 HTTP

普通 HTTP 最常见的工作方式是“一问一答”：

```text
浏览器/客户端 -> 服务端：发起请求
服务端 -> 浏览器/客户端：返回响应
连接结束
```

例如：

```text
GET /users/1
服务端返回用户 JSON
```

这种模式适合：

```text
查询数据
提交表单
调用一次接口拿一次结果
```

但如果服务端需要不断推送消息，普通 HTTP 就不太自然。比如：

```text
任务进度
日志刷新
AI 流式输出
服务端通知
工具调用的异步结果
```

这些场景不是“一次响应就结束”，而是“后面有消息继续发”。

## 2. SSE 是什么

SSE，全名是 Server-Sent Events，可以理解为：

```text
客户端先打开一条 HTTP 连接
服务端不立刻关闭响应
服务端后续有消息时，就往这条响应里持续写事件
```

它的方向主要是：

```text
服务端 -> 客户端
```

浏览器原生提供了 `EventSource`：

```javascript
const eventSource = new EventSource("/events");

eventSource.addEventListener("message", event => {
  console.log(event.data);
});
```

服务端响应头里最关键的是：

```text
Content-Type: text/event-stream
```

SSE 一条事件的文本格式类似：

```text
event: message
data: {"text":"hello"}

```

最后的空行很重要，它表示一条事件结束。

## 3. Spring 里的 SseEmitter 是什么

在 Spring MVC 中，`SseEmitter` 可以理解成：

```text
Spring 提供的一根 SSE 推送管道
```

Controller 返回 `SseEmitter`，意思是告诉 Spring：

```text
这个 HTTP 请求不要按普通 JSON 一次性返回
请把它保持为 SSE 响应
后续我会通过 emitter.send(...) 继续推消息
```

示例：

```java
@GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter events() {
    SseEmitter emitter = new SseEmitter(0L);
    executorService.execute(() -> sendEvents(emitter));
    return emitter;
}
```

为什么要尽快 `return emitter`？

因为 Controller 的返回值是 Spring 建立 HTTP 响应的依据。只有 Spring 拿到 `SseEmitter` 后，才知道这次响应要按 SSE 长连接处理。

所以常见结构是：

```text
Controller 创建 SseEmitter
Controller 立刻返回 SseEmitter
后台线程慢慢 emitter.send(...)
```

## 4. 阶段 1：可控连接

当前 demo 的旧版页面：

```text
http://localhost:8089/
```

页面不会自动连接，需要手动点击“连接”。

流程：

```text
点击连接
-> 浏览器创建 EventSource("/events")
-> 服务端创建 SseEmitter
-> Controller 返回 SseEmitter
-> 服务端每 2 秒推送一次 message 事件
```

断开流程：

```text
点击断开
-> 浏览器调用 eventSource.close()
-> 浏览器关闭 SSE 连接
-> 服务端后续继续写入时会感知连接结束或异常
```

这一阶段要理解的是：

```text
SSE 是一条服务端持续写消息的 HTTP 响应
客户端可以主动关闭这条连接
服务端需要处理连接完成、超时、异常
```

## 5. 阶段 2：sessionId

如果只有一条 SSE 连接，服务端直接推送即可。

但真实系统会有很多客户端：

```text
用户 A 的浏览器
用户 B 的浏览器
另一个 MCP Client
另一个页面窗口
```

服务端必须知道：

```text
这条消息应该推给哪一条 SSE 连接
```

所以 demo 引入了 `sessionId`：

```text
GET /events
-> 服务端创建 sessionId
-> 服务端保存 sessionId 和 SseEmitter 的关系
-> 服务端通过 session 事件把 sessionId 推给页面
```

核心结构：

```java
private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
```

可以理解为：

```text
sessionId -> SSE 推送管道
```

## 6. 阶段 3：SSE + 普通 HTTP 配合

SSE 主要解决：

```text
服务端 -> 客户端
```

但客户端还需要给服务端发请求。比如：

```text
调用工具
发送指令
查询工具列表
提交 JSON-RPC 请求
```

这部分通常继续用普通 HTTP POST。

demo 的旧版模型：

```text
GET  /events                    建立 SSE 连接，拿 sessionId
POST /messages?sessionId=xxx     客户端发送请求
SSE message event                服务端推送响应
```

这就是早期 MCP HTTP with SSE 模式的核心结构。

关键点：

```text
POST 本身只负责把请求送到服务端
真正的业务响应通过已有 SSE 连接推回来
sessionId 用来找到对应的 SseEmitter
```

## 7. 阶段 4：JSON-RPC 风格协议

SSE 只是传输层，它不关心业务语义。

真正表达“我要做什么”的，是消息内容。

demo 把请求体升级成 JSON-RPC 风格：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "initialize",
  "params": {}
}
```

几个字段的含义：

```text
jsonrpc  协议版本标识
id       请求编号，用来匹配响应
method   要调用的方法
params   方法参数
```

服务端响应：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "serverName": "sse-learning-demo"
  }
}
```

如果方法不存在，返回错误：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "error": {
    "code": -32601,
    "message": "method not found"
  }
}
```

这一阶段要理解：

```text
HTTP/SSE 负责传输
JSON-RPC 风格 JSON 负责表达协议动作
```

## 8. 阶段 5：模拟工具列表

demo 支持：

```text
initialize
tools/list
```

`tools/list` 请求：

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "method": "tools/list",
  "params": {}
}
```

服务端返回模拟工具：

```text
time.now
echo
random.number
```

这一步对应 MCP 里的“客户端询问服务端有哪些工具可以用”。

后续如果继续扩展，可以加入：

```text
tools/call
```

也就是根据工具名和参数，真正执行某个工具。

## 9. HTTP with SSE

HTTP with SSE 是早期 MCP 远程传输的一种典型模型。

结构是：

```text
GET  /sse 或 /events              建立 SSE 长连接
POST /messages?sessionId=xxx      客户端发送 JSON-RPC 请求
SSE message event                 服务端推送 JSON-RPC 响应
```

它的特点：

```text
SSE 是固定接收通道
POST 是发送通道
sessionId 把 POST 请求和 SSE 连接关联起来
服务端响应通常从 SSE 通道回来
```

优点：

```text
模型清晰
容易理解服务端主动推送
适合解释 MCP 旧版 SSE 通信流程
```

缺点：

```text
需要两个端点配合
简单请求也必须依赖已经建立的 SSE 会话
sessionId 通常出现在 URL 或 endpoint 里
```

## 10. Streamable HTTP

Streamable HTTP 是新版 MCP 推荐的远程传输方式。

它不是一种全新的底层网络协议，而是：

```text
一套用 HTTP 承载 JSON-RPC 消息，并允许响应流式返回的规则
```

当前 demo 的 Streamable HTTP 页面：

```text
http://localhost:8089/streamable.html
```

统一端点：

```text
POST /mcp
GET  /mcp
```

普通 JSON 响应：

```text
POST /mcp
Accept: application/json
Content-Type: application/json

服务端返回 application/json
```

流式响应：

```text
POST /mcp
Accept: text/event-stream
Content-Type: application/json

服务端把这一次 POST 响应变成 text/event-stream
```

服务端主动通知流：

```text
GET /mcp
Accept: text/event-stream
```

核心变化：

```text
旧版 HTTP with SSE：先建立固定 SSE 通道，再通过 POST 投递请求
新版 Streamable HTTP：POST /mcp 自己就可以普通 JSON 返回，也可以 SSE 流式返回
```

## 11. Streamable HTTP 和 HTTP with SSE 的区别

对比表：

```text
维度                 HTTP with SSE                  Streamable HTTP
端点                 /events + /messages            统一 /mcp
客户端发请求          POST /messages                 POST /mcp
服务端普通响应        通常走 SSE 通道                 可直接 application/json 返回
服务端流式响应        固定 SSE 连接                   本次 POST 可返回 text/event-stream
服务端主动通知        SSE 长连接                      可选 GET /mcp SSE 流
session 表达          常见于 query 参数               更适合放在 header
心智模型              双通道配合                      一个 endpoint 按需普通或流式返回
```

为什么新版 MCP 采用 Streamable HTTP？

```text
端点更统一
简单请求可以简单返回
复杂请求可以流式返回
更符合 HTTP 请求-响应语义
更容易经过网关、代理、鉴权系统
更方便从简单服务器扩展到复杂服务器
```

## 12. 为什么 MCP 不只用普通 HTTP

普通 HTTP 一问一答，适合简单请求。

但 MCP 可能有：

```text
工具调用耗时较长
服务端需要异步返回结果
服务端可能发通知
服务端可能分多段返回进度或中间消息
```

如果只用普通 HTTP：

```text
客户端 POST
服务端必须等所有工作完成后一次性返回
```

这样不适合流式响应和服务端主动通知。

所以 MCP 需要某种“服务端可以连续发消息”的能力。

## 13. 为什么不直接用 WebSocket

WebSocket 是双向长连接：

```text
客户端可以随时发
服务端可以随时发
```

能力更强，但也更重。

MCP 的很多场景其实可以拆成：

```text
客户端 -> 服务端：HTTP POST
服务端 -> 客户端：HTTP 响应或 SSE 流
```

这样仍然保留 HTTP 生态优势：

```text
容易调试
容易代理
容易做鉴权
容易接入网关
请求语义清楚
可以用普通 HTTP 工具观察
```

所以：

```text
普通 HTTP 太短，服务端不好主动推
WebSocket 太重，不一定需要完整双向长连接
SSE/Streamable HTTP 处在中间：HTTP 友好，又支持服务端流式推送
```

## 14. 什么时候适合用 SSE

适合：

```text
AI 流式输出
任务进度推送
日志实时查看
通知中心
监控状态低频刷新
服务端需要持续推送文本事件
客户端主要接收，偶尔通过 HTTP POST 发请求
```

不适合：

```text
游戏实时同步
高频双向通信
协同编辑
实时音视频信令
大量二进制数据传输
```

这些更适合 WebSocket 或其他实时通信方案。

## 15. 当前 demo 代码入口

Spring Boot 启动类：

```text
src/main/java/cn/bugstack/sse/demo/SseLearningDemoApplication.java
```

HTTP with SSE Controller：

```text
src/main/java/cn/bugstack/sse/demo/controller/SseDemoController.java
```

HTTP with SSE 页面：

```text
src/main/resources/static/index.html
```

Streamable HTTP Controller：

```text
src/main/java/cn/bugstack/sse/demo/controller/StreamableHttpController.java
```

Streamable HTTP 页面：

```text
src/main/resources/static/streamable.html
```

运行：

```powershell
mvn spring-boot:run
```

访问：

```text
http://localhost:8089/
http://localhost:8089/streamable.html
```

## 16. 建议复习顺序

建议按这个顺序重新过一遍：

```text
1. 打开 / 页面，只看连接和断开
2. 观察 sessionId 如何生成和保存
3. 发送 initialize，看 POST 请求和 SSE 响应
4. 发送 tools/list，看工具列表如何返回
5. 打开 /streamable.html
6. 对比 application/json 和 text/event-stream 响应
7. 再回头看 MCP 项目里的 /sse 和 /message?sessionId
```

记住一句话：

```text
SSE 是服务端推送消息的通道；
MCP/JSON-RPC 是通道里传输的协议内容；
Streamable HTTP 是新版 MCP 对 HTTP + 流式响应的统一使用方式。
```
