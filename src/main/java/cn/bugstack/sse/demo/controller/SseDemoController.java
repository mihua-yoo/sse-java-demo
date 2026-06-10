package cn.bugstack.sse.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

@RestController
public class SseDemoController {

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        // SseEmitter 是 Spring MVC 对 SSE 长连接的封装；timeout=0L 表示不主动超时。
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> System.out.println("SSE connection completed."));
        emitter.onTimeout(() -> System.out.println("SSE connection timeout."));
        emitter.onError(error -> System.out.println("SSE connection error: " + error.getMessage()));

        // Controller 方法要尽快返回 emitter，真正的持续推送放到独立线程里执行。
        executorService.execute(() -> sendEvents(emitter));

        return emitter;
    }

    private void sendEvents(SseEmitter emitter) {
        try {
            for (int count = 1; count <= 100; count++) {
                EventMessage message = new EventMessage(count, LocalDateTime.now().toString());

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

    public static class EventMessage {

        private final int count;
        private final String time;

        public EventMessage(int count, String time) {
            this.count = count;
            this.time = time;
        }

        public int getCount() {
            return count;
        }

        public String getTime() {
            return time;
        }
    }
}
