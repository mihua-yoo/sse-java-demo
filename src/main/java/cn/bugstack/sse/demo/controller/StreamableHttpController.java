package cn.bugstack.sse.demo.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class StreamableHttpController {

    private static final String MCP_SESSION_ID = "Mcp-Session-Id";

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object mcp(@RequestBody JsonRpcRequest request,
                      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
                      @RequestHeader(value = MCP_SESSION_ID, required = false) String sessionId) {
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return streamResponse(request, sessionId);
        }

        String responseSessionId = ensureSessionId(sessionId, request);

        // Streamable HTTP 的普通分支：一次 POST 直接拿到一次 JSON 响应，不需要提前建立 SSE 连接。
        return ResponseEntity.ok()
                .header(MCP_SESSION_ID, responseSessionId)
                .body(handleJsonRpcRequest(request));
    }

    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter mcpServerEvents() {
        SseEmitter emitter = new SseEmitter(0L);
        String sessionId = UUID.randomUUID().toString();

        // Streamable HTTP 仍然可以用 GET 打开一条 SSE 流，用来观察服务端主动通知。
        executorService.execute(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(new ServerNotice("session", "GET /mcp notification stream opened", sessionId, LocalDateTime.now().toString())));

                for (int count = 1; count <= 5; count++) {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(new ServerNotice("notice", "服务端主动通知 " + count, sessionId, LocalDateTime.now().toString())));
                    Thread.sleep(2000);
                }

                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private SseEmitter streamResponse(JsonRpcRequest request, String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        String responseSessionId = ensureSessionId(sessionId, request);

        // Streamable HTTP 的流式分支：这次 POST 自己的 HTTP 响应就是 SSE 流。
        executorService.execute(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(new ServerNotice("accepted", "服务端已收到 POST /mcp，准备流式返回", responseSessionId, LocalDateTime.now().toString())));

                Thread.sleep(800);

                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(handleJsonRpcRequest(request)));

                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String ensureSessionId(String sessionId, JsonRpcRequest request) {
        if (sessionId != null && sessionId.trim().length() > 0) {
            return sessionId;
        }

        if ("initialize".equals(request.getMethod())) {
            return UUID.randomUUID().toString();
        }

        return "stateless-demo";
    }

    private JsonRpcResponse handleJsonRpcRequest(JsonRpcRequest request) {
        if ("initialize".equals(request.getMethod())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("serverName", "streamable-http-demo");
            result.put("protocolVersion", "demo-2026-06-11");
            result.put("message", "普通 JSON 和 SSE 流式响应都从 POST /mcp 返回");
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

    public static class ServerNotice {

        private final String type;
        private final String text;
        private final String sessionId;
        private final String time;

        public ServerNotice(String type, String text, String sessionId, String time) {
            this.type = type;
            this.text = text;
            this.sessionId = sessionId;
            this.time = time;
        }

        public String getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getTime() {
            return time;
        }
    }
}
