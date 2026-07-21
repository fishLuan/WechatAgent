package com.luanxv.pre.wechatAI.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextUtilsTest {
    @Test
    void recognizesARequestToEditThePreviousImage() {
        assertTrue(TextUtils.isImageEditRequest("\u628a\u4e0a\u4e00\u5f20\u56fe\u7247\u6539\u6210\u508d\u665a\u7684\u6d77\u8fb9"));
        assertTrue(TextUtils.isImageEditRequest("\u521a\u624d\u90a3\u5f20\u56fe\u628a\u5929\u7a7a\u6362\u6210\u7c89\u8272"));
        assertTrue(TextUtils.isImageEditRequest("\u8fd9\u5f20\u56fe\u7247\u4e0d\u592a\u6ee1\u610f\uff0c\u5e2e\u6211\u4fee\u6539\u4e00\u4e0b"));
    }

    @Test
    void doesNotTreatAnOrdinaryChatMessageAsImageEditing() {
        assertFalse(TextUtils.isImageEditRequest("\u5e2e\u6211\u4fee\u6539\u8fd9\u6bb5 Java \u4ee3\u7801"));
    }

    @Test
    void recognizesQuestionsThatNeedCurrentWebInformation() {
        assertTrue(TextUtils.isWebSearchRequest("\u4eca\u5929\u676d\u5dde\u5929\u6c14\u600e\u4e48\u6837"));
        assertTrue(TextUtils.isWebSearchRequest("\u5e2e\u6211\u8054\u7f51\u641c\u7d22\u6700\u65b0\u7684 AI \u65b0\u95fb"));
        assertTrue(TextUtils.isWebSearchRequest("latest exchange rate"));
        assertFalse(TextUtils.isWebSearchRequest("\u5e2e\u6211\u89e3\u91ca\u8fd9\u6bb5 Java \u4ee3\u7801"));
    }

    @Test
    void recognizesAnEditingInstructionAttachedToAnImage() {
        assertTrue(TextUtils.isImageEditInstruction("\u628a\u8fd9\u5f20\u56fe\u6539\u6210\u6c34\u5f69\u98ce\u683c"));
        assertTrue(TextUtils.isImageEditInstruction("\u628a\u5b83\u6539\u6210\u6c34\u5f69\u98ce\u683c"));
        assertTrue(TextUtils.isImageEditInstruction("\u7ed9\u8fd9\u5f20\u56fe\u6dfb\u52a0\u4e00\u53ea\u732b"));
        assertFalse(TextUtils.isImageEditInstruction("\u5e2e\u6211\u4fee\u6539 Java \u4ee3\u7801"));
        assertFalse(TextUtils.isImageEditInstruction("\u8fd9\u662f\u4ec0\u4e48"));
    }
}
