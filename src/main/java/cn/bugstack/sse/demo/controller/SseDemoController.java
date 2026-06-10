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
    public MessageResponse messages(@RequestParam String sessionId, @RequestBody SendMessageRequest request) throws IOException {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return new MessageResponse(false, "session not found or closed: " + sessionId);
        }

        // 这里模拟“客户端通过普通 HTTP 发消息，服务端再通过 SSE 把结果推回对应客户端”。
        emitter.send(SseEmitter.event()
                .name("message")
                .data(new EventMessage("reply", "服务端收到消息：" + request.getText(), LocalDateTime.now().toString())));

        return new MessageResponse(true, "message sent by SSE");
    }

    private void sendEvents(String sessionId, SseEmitter emitter) {
        try {
            // 建立连接后的第一条事件：告诉前端它当前这条连接对应哪个 sessionId。
            emitter.send(SseEmitter.event()
                    .name("session")
                    .data(new SessionMessage(sessionId, "/messages?sessionId=" + sessionId)));

            for (int count = 1; count <= 100; count++) {
                EventMessage message = new EventMessage("tick", "第 " + count + " 次服务端定时推送", LocalDateTime.now().toString());

                // name("message") 对应前端 addEventListener("message", ...)。
                // data(message) 会由 Spring 自动序列化为 JSON。
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

    public static class SendMessageRequest {

        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
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
