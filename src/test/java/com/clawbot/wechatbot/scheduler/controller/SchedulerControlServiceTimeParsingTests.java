package com.clawbot.wechatbot.scheduler.controller;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerControlServiceTimeParsingTests {

    @Test
    void parsesHalfHourRelativeExpressions() {
        assertDelay("半小时后", Duration.ofMinutes(30));
        assertDelay("半个小时后", Duration.ofMinutes(30));
        assertDelay("半个小时之后", Duration.ofMinutes(30));
    }

    @Test
    void parsesNumericRelativeExpressions() {
        assertDelay("30分钟之后", Duration.ofMinutes(30));
        assertDelay("两小时后", Duration.ofHours(2));
        assertDelay("1个小时以后", Duration.ofHours(1));
    }

    private void assertDelay(String expression, Duration expected) {
        long before = System.currentTimeMillis();
        long fireAt = SchedulerControlService.parseOneTimeFireAt(expression);
        long after = System.currentTimeMillis();
        assertThat(fireAt).isBetween(
            before + expected.toMillis() - 1_000,
            after + expected.toMillis() + 1_000);
    }
}
