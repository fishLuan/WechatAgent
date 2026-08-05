package com.clawbot.wechatbot.idempotency;

public record IdempotencyClaim(boolean acquired, IdempotencyExecution execution) {
}
