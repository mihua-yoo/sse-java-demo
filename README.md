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

## 你会看到什么

- 页面加载后不会自动连接，需要点击“连接”按钮才会创建 `EventSource("/events")`。
- 连接建立后，服务端会先推送一条 `session` 事件，里面包含当前连接的 `sessionId`。
- 页面拿到 `sessionId` 后，可以通过 `POST /messages?sessionId=xxx` 给服务端发消息。
- 服务端收到 POST 后，会通过对应的 SSE 连接把结果推回页面。
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
