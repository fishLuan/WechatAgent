package com.clawbot.wechatbot.service.longform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongFormGenerationPolicyTests {
    private final LongFormGenerationPolicy policy =
        new LongFormGenerationPolicy(true, 1200, 10000, 10, 5, 12000);

    @Test
    void detectsExplicitChineseCharacterTarget() {
        assertEquals(
            3000,
            policy.targetChars("帮我生成一篇3000字左右的小故事").orElseThrow());
        assertEquals(
            5000,
            policy.targetChars("写 5000 个汉字的文章").orElseThrow());
    }

    @Test
    void ignoresShortOrdinaryRequestsAndCapsOversizedTargets() {
        assertFalse(policy.targetChars("写一段500字简介").isPresent());
        assertEquals(
            10000,
            policy.targetChars("生成50000字的故事").orElseThrow());
    }

    @Test
    void calculatesConfiguredToleranceLowerBound() {
        assertEquals(2700, policy.lowerBound(3000));
        assertTrue(policy.enabled());
    }
}
