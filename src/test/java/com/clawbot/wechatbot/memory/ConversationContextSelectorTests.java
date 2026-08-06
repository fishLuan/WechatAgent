package com.clawbot.wechatbot.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextSelectorTests {

    private final MemoryProperties properties = properties();
    private final ConversationContextSelector selector =
        new ConversationContextSelector(properties);

    @Test
    void independentToolQueryDoesNotCarryHistory() {
        ConversationContextSelector.Selection selection = selector.select(
            memoryWithTurns("之前聊过电影", "好的"),
            "查询今天杭州天气", null, Set.of("get_weather"), false);

        assertThat(selection.mode())
            .isEqualTo(ConversationContextSelector.Mode.INDEPENDENT_TOOL);
        assertThat(selection.context()).isEmpty();
        assertThat(selection.selectedMessages()).isZero();
    }

    @Test
    void generalChatKeepsOnlyConfiguredRecentTurns() {
        ConversationMemory memory = memoryWithTurns(
            "第一轮", "回答一",
            "第二轮", "回答二",
            "第三轮", "回答三");

        ConversationContextSelector.Selection selection = selector.select(
            memory, "随便聊聊", null, Set.of(), false);

        assertThat(selection.mode())
            .isEqualTo(ConversationContextSelector.Mode.GENERAL_CHAT);
        assertThat(selection.context()).doesNotContain("第一轮", "回答一")
            .contains("第二轮", "回答二", "第三轮", "回答三");
        assertThat(selection.selectedMessages()).isEqualTo(4);
    }

    @Test
    void contextualInstructionRetrievesOnlyMatchingDomainWhenAvailable() {
        ConversationMemory memory = memoryWithTurns(
            "推荐两本小说", "给你两本书",
            "用语音回复南京天气", "语音文件已生成",
            "再推荐一部动漫", "给你三个动漫");

        ConversationContextSelector.Selection selection = selector.select(
            memory, "男声回复", null, Set.of("speech_synthesis"), false);

        assertThat(selection.mode())
            .isEqualTo(ConversationContextSelector.Mode.FOLLOW_UP);
        assertThat(selection.context()).contains("用语音回复南京天气", "语音文件已生成")
            .doesNotContain("推荐两本小说", "再推荐一部动漫");
    }

    @Test
    void ordinalFollowUpUsesTheLatestBusinessDomain() {
        ConversationMemory memory = memoryWithTurns(
            "推荐三本小说", "书籍列表",
            "搜索三部动漫", "动漫结果列表");

        ConversationContextSelector.Selection selection = selector.select(
            memory, "第三个", null, Set.of(), false);

        assertThat(selection.context()).contains("搜索三部动漫", "动漫结果列表")
            .doesNotContain("推荐三本小说", "书籍列表");
    }

    @Test
    void complexTaskSelectsSemanticallyRelatedTurns() {
        ConversationMemory memory = memoryWithTurns(
            "写一首古诗", "古诗内容",
            "查询北京新闻", "北京新闻列表",
            "把数据生成Excel表格", "表格已生成");
        memory.setLongTermSummary("用户过去经常查询新闻并导出Excel表格。");

        ConversationContextSelector.Selection selection = selector.select(
            memory, "查杭州新闻并生成Excel", null, null, true);

        assertThat(selection.mode())
            .isEqualTo(ConversationContextSelector.Mode.SEMANTIC_COMPLEX);
        assertThat(selection.context()).contains("北京新闻列表", "表格已生成")
            .doesNotContain("写一首古诗");
    }

    private static MemoryProperties properties() {
        MemoryProperties properties = new MemoryProperties();
        properties.setGeneralContextTurns(2);
        properties.setFollowUpContextTurns(2);
        properties.setComplexContextTurns(3);
        return properties;
    }

    private static ConversationMemory memoryWithTurns(String... messages) {
        ConversationMemory memory = new ConversationMemory();
        java.util.ArrayList<ConversationMessage> result = new java.util.ArrayList<>();
        for (int index = 0; index < messages.length; index++) {
            result.add(new ConversationMessage(
                index % 2 == 0 ? "user" : "assistant",
                messages[index], Instant.EPOCH.plusSeconds(index)));
        }
        memory.setRecentMessages(List.copyOf(result));
        return memory;
    }
}
