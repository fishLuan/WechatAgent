package com.xwc.demo;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/bot")
public class WeChatBotController {

    private final WeChatBotService botService;

    public WeChatBotController(WeChatBotService botService) {
        this.botService = botService;
    }

    @PostMapping("/start")
    public WeChatBotService.BotStartResult start() {
        try {
            return botService.start();
        } catch (Exception e) {
            return new WeChatBotService.BotStartResult(false, "启动失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        botService.stop();
        return Map.of("success", true, "message", "机器人已停止");
    }

    @GetMapping("/status")
    public WeChatBotService.BotStatus status() {
        return botService.getStatus();
    }

    @PostMapping("/send")
    public Map<String, Object> send(@RequestParam String userId, @RequestParam String text) {
        try {
            botService.sendMessage(userId, text);
            return Map.of("success", true, "message", "已发送给 " + userId);
        } catch (IllegalStateException e) {
            return Map.of("success", false, "message", e.getMessage());
        } catch (IOException e) {
            return Map.of("success", false, "message", "发送失败: " + e.getMessage());
        }
    }

    @GetMapping("/messages")
    public List<WeChatBotService.BotMessage> messages() {
        return botService.getMessages();
    }

    @GetMapping("/users")
    public Set<String> users() {
        return botService.getActiveUsers();
    }
}