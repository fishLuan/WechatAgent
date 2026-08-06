package com.clawbot.wechatbot.feature.document.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingWordDocumentInstructionStoreTests {

    @Test
    void takesLatestMatchingInstructionFromLastThreeMessages() {
        PendingWordDocumentInstructionStore store =
            new PendingWordDocumentInstructionStore();

        store.put("user-1", "第一条：字体大一点");
        store.put("user-1", "第二条：普通聊天");
        store.put("user-1", "第三条：内容居中");
        store.put("user-1", "第四条：也不是需求");

        String instruction = store.takeLatest(
            "user-1", text -> text.contains("字体") || text.contains("居中"));

        assertEquals("第三条：内容居中", instruction);
    }

    @Test
    void olderThanThreeMessagesIsIgnored() {
        PendingWordDocumentInstructionStore store =
            new PendingWordDocumentInstructionStore();

        store.put("user-1", "字体大一点");
        store.put("user-1", "普通1");
        store.put("user-1", "普通2");
        store.put("user-1", "普通3");

        String instruction = store.takeLatest("user-1", text -> text.contains("字体"));

        assertNull(instruction);
    }

    @Test
    void clearsUserInstructions() {
        PendingWordDocumentInstructionStore store =
            new PendingWordDocumentInstructionStore();

        store.put("user-1", "美化排版");
        store.clear("user-1");

        assertNull(store.takeLatest("user-1", text -> true));
    }

    @Test
    void ignoresDuplicatePendingInstruction() {
        PendingWordDocumentInstructionStore store =
            new PendingWordDocumentInstructionStore();

        store.put("user-1", "美化排版");
        store.put("user-1", "美化排版");

        assertEquals("美化排版", store.takeLatest("user-1", text -> true));
        assertNull(store.takeLatest("user-1", text -> true));
    }
}
