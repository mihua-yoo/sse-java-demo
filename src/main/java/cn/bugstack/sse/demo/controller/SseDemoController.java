package cn.bugstack.sse.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class SseDemoController {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        // SseEmitter 是 Spring MVC 对 SSE 长连接的封装；timeout=0L 表示不主动超时。
        SseEmitter emitter = new SseEmitter(0L);
        String sessionId = UUID.randomUUID().toString();

        // 用 sessionId 记录每一条 SSE 连接，后续 POST /messages 可以找到对应的推送通道。
        emitters.put(sessionId, emitter);

        emitter.onCompletion(() -> removeEmitter(sessionId, "completed"));
        emitter.onTimeout(() -> removeEmitter(sessionId, "timeout"));
        emitter.onError(error -> removeEmitter(sessionId, "error: " + error.getMessage()));

        // Controller 方法要尽快返回 emitter，真正的持续推送放到独立线程里执行。
        executorService.execute(() -> sendEvents(sessionId, emitter));

        return emitter;
    }

    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse messages(@RequestParam String sessionId, @RequestBody JsonRpcRequest request) throws IOException {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return new MessageResponse(false, "session not found or closed: " + sessionId);
        }

        // 这里模拟“客户端 POST JSON-RPC 请求，服务端通过 SSE 异步推回 JSON-RPC 响应”。
        emitter.send(SseEmitter.event()
                .name("message")
                .data(handleJsonRpcRequest(request)));

        return new MessageResponse(true, "JSON-RPC response sent by SSE");
    }

    private void sendEvents(String sessionId, SseEmitter emitter) {
        try {
            // 建立连接后的第一条事件：告诉前端它当前这条连接对应哪个 sessionId。
            emitter.send(SseEmitter.event()
                    .name("session")
                    .data(new SessionMessage(sessionId, "/messages?sessionId=" + sessionId)));

            for (int count = 1; count <= 100; count++) {
                EventMessage message = new EventMessage("tick", "第 " + count + " 次服务端定时推送", LocalDateTime.now().toString());

                // message 事件既可以承载普通通知，也可以承载 JSON-RPC 响应；SSE 只负责传输。
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(message));

                Thread.sleep(2000);
            }

            // 推送完成后主动结束 SSE 响应。
            emitter.complete();
        } catch (IOException clientDisconnected) {
            // 浏览器关闭页面或网络断开时，继续推送会触发 IOException。
            emitter.completeWithError(clientDisconnected);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(interruptedException);
        }
    }

    private void removeEmitter(String sessionId, String reason) {
        emitters.remove(sessionId);
        System.out.println("SSE session removed. sessionId=" + sessionId + ", reason=" + reason);
    }

    private JsonRpcResponse handleJsonRpcRequest(JsonRpcRequest request) {
        if ("initialize".equals(request.getMethod())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("serverName", "sse-learning-demo");
            result.put("protocolVersion", "demo-2026-06-10");
            result.put("message", "初始化成功，当前连接可以继续请求 tools/list");
            return JsonRpcResponse.success(request.getId(), result);
        }

        if ("tools/list".equals(request.getMethod())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tools", createDemoTools());
            return JsonRpcResponse.success(request.getId(), result);
        }

        return JsonRpcResponse.error(request.getId(), -32601, "method not found: " + request.getMethod());
    }

    private List<ToolDefinition> createDemoTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("time.now", "获取当前服务器时间"));
        tools.add(new ToolDefinition("echo", "原样返回输入内容"));
        tools.add(new ToolDefinition("random.number", "生成一个随机数字"));
        return tools;
    }

    public static class SessionMessage {

        private final String sessionId;
        private final String messageEndpoint;

        public SessionMessage(String sessionId, String messageEndpoint) {
            this.sessionId = sessionId;
            this.messageEndpoint = messageEndpoint;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getMessageEndpoint() {
            return messageEndpoint;
        }
    }

    public static class EventMessage {

        private final String type;
        private final String text;
        private final String time;

        public EventMessage(String type, String text, String time) {
            this.type = type;
            this.text = text;
            this.time = time;
        }

        public String getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        public String getTime() {
            return time;
        }
    }

    public static class JsonRpcRequest {

        private String id;
        private String method;
        private Map<String, Object> params;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params;
        }
    }

    public static class JsonRpcResponse {

        private final String jsonrpc = "2.0";
        private final String id;
        private final Object result;
        private final JsonRpcError error;

        private JsonRpcResponse(String id, Object result, JsonRpcError error) {
            this.id = id;
            this.result = result;
            this.error = error;
        }

        public static JsonRpcResponse success(String id, Object result) {
            return new JsonRpcResponse(id, result, null);
        }

        public static JsonRpcResponse error(String id, int code, String message) {
            return new JsonRpcResponse(id, null, new JsonRpcError(code, message));
        }

        public String getJsonrpc() {
            return jsonrpc;
        }

        public String getId() {
            return id;
        }

        public Object getResult() {
            return result;
        }

        public JsonRpcError getError() {
            return error;
        }
    }

    public static class JsonRpcError {

        private final int code;
        private final String message;

        public JsonRpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class ToolDefinition {

        private final String name;
        private final String description;

        public ToolDefinition(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }

    public static class MessageResponse {

        private final boolean success;
        private final String message;

        public MessageResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
