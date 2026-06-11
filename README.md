# SSE Learning Demo

这是一个 Spring Boot 版 SSE 学习 demo，结构尽量贴近普通 Web 项目，方便后续逐步扩展成 session、message endpoint、MCP 风格通信。

当前 demo 使用 Spring Boot `2.7.18` 和 Java 8，目的是兼容本机命令行环境。SSE 的核心写法 `SseEmitter` 在 Spring Boot 3 中也可以继续使用。

## 项目结构

```text
sse-learning-demo
├── pom.xml
├── run.bat
└── src
    └── main
        ├── java
        │   └── cn/bugstack/sse/demo
        │       ├── SseLearningDemoApplication.java
        │       └── controller/SseDemoController.java
        └── resources
            ├── application.yml
            └── static/index.html
```

## IDEA 调试

1. 用 IDEA 打开 `sse-learning-demo` 目录。
2. 等 Maven 导入完成。
3. Project SDK 选择 JDK 8 或更高版本。
4. 打开 `cn.bugstack.sse.demo.SseLearningDemoApplication`。
5. 在 `SseDemoController#events` 或 `SseDemoController#sendEvents` 上打断点。
6. 点击 `main` 方法旁边的 Debug。
7. 浏览器访问 `http://localhost:8089`。

## 命令行运行

Windows 下可以直接运行：

```powershell
.\run.bat
```

也可以手动运行：

```powershell
mvn spring-boot:run
```

然后打开浏览器访问：

```text
http://localhost:8089
```

两个学习页面：

```text
http://localhost:8089/                 HTTP with SSE：/events + /messages
http://localhost:8089/streamable.html  Streamable HTTP：统一 /mcp
```

## 你会看到什么

- 页面加载后不会自动连接，需要点击“连接”按钮才会创建 `EventSource("/events")`。
- 连接建立后，服务端会先推送一条 `session` 事件，里面包含当前连接的 `sessionId`。
- 页面拿到 `sessionId` 后，可以通过 `POST /messages?sessionId=xxx` 给服务端发 JSON-RPC 风格请求。
- 服务端收到 POST 后，会通过对应的 SSE 连接把 JSON-RPC 风格响应推回页面。
- 点击“断开”按钮会调用 `eventSource.close()`，浏览器会主动关闭 SSE 连接。
- `SseDemoController` 返回 `SseEmitter`，Spring Boot 会保持这条 HTTP 响应不立刻关闭。
- 服务端每 2 秒推送一条名为 `message` 的事件。
- 页面会把收到的事件追加到日志区域。

## 关键点

Controller 上的接口声明：

```java
@GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

这里的 `MediaType.TEXT_EVENT_STREAM_VALUE` 对应响应头：

```text
Content-Type: text/event-stream
```

服务端推送事件时使用：

```java
emitter.send(SseEmitter.event()
        .name("message")
        .data(message));
```

前端用下面的代码监听：

```javascript
eventSource.addEventListener("message", (event) => {
  appendLog("[message] " + event.data);
});
```

阶段 1 的重点是观察连接生命周期：

```text
点击连接 -> 浏览器创建 EventSource -> 服务端创建 SseEmitter -> 持续推送消息
点击断开 -> 浏览器 close EventSource -> 服务端感知连接结束或写入失败
```

阶段 2 的重点是理解 session：

```text
点击连接 -> GET /events -> 服务端创建 sessionId -> 保存 sessionId 和 SseEmitter 的关系
服务端推送 session 事件 -> 浏览器保存 sessionId
```

阶段 3 的重点是理解 SSE + 普通 HTTP 如何配合：

```text
浏览器通过 POST /messages?sessionId=xxx 发消息
服务端通过 sessionId 找到对应 SseEmitter
服务端通过这条 SSE 连接把处理结果推回浏览器
```

这也是 MCP SSE 模式里常见的结构：

```text
GET /sse                         建立 SSE 连接，拿到 sessionId
POST /messages?sessionId=xxx      客户端发送请求
SSE message event                 服务端异步推送响应
```

## Streamable HTTP 对比页

访问：

```text
http://localhost:8089/streamable.html
```

这个页面使用统一端点：

```text
POST /mcp
GET  /mcp
```

普通 JSON 响应：

```text
POST /mcp
Accept: application/json
Content-Type: application/json

服务端直接返回 application/json
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

对比重点：

```text
HTTP with SSE：先 GET /events 建立固定 SSE 通道，再 POST /messages 投递请求。
Streamable HTTP：POST /mcp 自己就可以普通 JSON 返回，也可以流式 SSE 返回。
```

阶段 4 的重点是理解“传输层”和“协议内容”的区别：

```text
SSE 负责把服务端消息推给浏览器
POST 负责把浏览器请求发给服务端
JSON-RPC 风格 JSON 负责表达具体要做什么
```

现在页面会生成这样的请求：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "initialize",
  "params": {}
}
```

服务端会通过 SSE `message` 事件推回：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "serverName": "sse-learning-demo",
    "protocolVersion": "demo-2026-06-10",
    "message": "初始化成功，当前连接可以继续请求 tools/list"
  }
}
```

阶段 5 的重点是模拟工具列表：

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "method": "tools/list",
  "params": {}
}
```

服务端会返回 `time.now`、`echo`、`random.number` 三个模拟工具。它们现在只是工具定义，下一阶段再实现真正的 `tools/call`。
