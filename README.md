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

- 页面加载后会通过 `EventSource("/events")` 建立 SSE 连接。
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
