package com.example.smartcs.controller;

import com.example.smartcs.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 智能客服对话控制器
 * <p>
 * 提供两种交互方式：
 * 1. 同步接口: POST /api/chat - 等待完整回答后返回
 * 2. 流式接口: GET /api/chat/stream - SSE 逐字推送（打字机效果）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 同步对话接口
     * <p>
     * 完整链路: 意图识别 → Query改写 → 多路召回/NL2SQL → 生成回答
     *
     * @param question 用户问题
     * @return AI 回答
     */
    @PostMapping
    public ChatApiResponse chat(@RequestBody ChatApiRequest request) {
        String answer = chatService.chat(request.question());
        return new ChatApiResponse(answer);
    }

    /**
     * 带文档类型过滤的对话接口
     * <p>
     * 支持元数据过滤，只在指定类型的文档中检索。
     * 例如: docType="faq" 只在 FAQ 文档中搜索
     *
     * @param question 用户问题
     * @param docType 文档类型过滤（可选）
     * @return AI 回答
     */
    @PostMapping("/filter")
    public ChatApiResponse chatWithFilter(
            @RequestParam String question,
            @RequestParam(required = false) String docType) {
        if (docType != null && !docType.isEmpty()) {
            String answer = chatService.chatWithDocTypeFilter(question, docType);
            return new ChatApiResponse(answer);
        }
        String answer = chatService.chat(question);
        return new ChatApiResponse(answer);
    }

    /**
     * 流式对话接口 (Server-Sent Events)
     * <p>
     * 使用 SSE 协议逐字推送 AI 回答，实现"打字机"效果。
     * 适用于前端实时显示 AI 生成过程。
     * <p>
     * 使用方式: 浏览器访问 /api/chat/stream?question=你好
     * 或使用 EventSource: new EventSource('/api/chat/stream?question=你好')
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String question) {
        // 注意: 这里简化为同步调用后按字符拆分
        // 生产环境应使用 chatClient.prompt().stream().content() 实现真正的流式输出
        return Flux.defer(() -> {
            String answer = chatService.chat(question);
            return Flux.fromArray(answer.split("(?<=.)"))
                .delayElements(java.time.Duration.ofMillis(30));
        });
    }

    // ========================
    // 请求/响应 DTO
    // ========================

    public record ChatApiRequest(String question) {}
    public record ChatApiResponse(String answer) {}
}
