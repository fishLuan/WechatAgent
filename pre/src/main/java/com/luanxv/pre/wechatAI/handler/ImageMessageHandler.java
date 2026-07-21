package com.luanxv.pre.wechatAI.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.luanxv.pre.wechatAI.core.ConversationMemoryService;
import com.luanxv.pre.wechatAI.core.GeneratedImageStore;
import com.luanxv.pre.wechatAI.model.MessageContext;
import com.luanxv.pre.wechatAI.service.QwenService;
import com.luanxv.pre.wechatAI.util.TextUtils;

import java.io.IOException;
import java.util.Base64;

public class ImageMessageHandler implements MessageTypeHandler {
    private final ILinkClient client;
    private final QwenService qwenService;
    private final ConversationMemoryService memoryService;
    private final GeneratedImageStore imageStore;

    public ImageMessageHandler(ILinkClient client, QwenService qwenService,
                               ConversationMemoryService memoryService, GeneratedImageStore imageStore) {
        this.client = client;
        this.qwenService = qwenService;
        this.memoryService = memoryService;
        this.imageStore = imageStore;
    }

    @Override
    public String handle(MessageContext context) {
        try {
            String userId = context.getFromUserId();
            MessageItem imageItem = context.getImageItem();
            if (imageItem == null) {
                return "没有找到图片数据。";
            }
            byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
            String instruction = context.getTextContent();
            imageStore.save(userId, imageBytes);

            if (TextUtils.isImageEditInstruction(instruction)) {
                sendProgress(userId, "图片修改中，请等待…");
                byte[] editedImage = qwenService.editImage(imageBytes, instruction);
                if (editedImage == null) {
                    return "图片修改失败了，请稍后再试。";
                }
                imageStore.save(userId, editedImage);
                memoryService.addMessage(userId, "user", "[图片修改请求] " + instruction);
                memoryService.addMessage(userId, "assistant", "[已修改用户上传的图片]");
                return "IMAGE:" + Base64.getEncoder().encodeToString(editedImage);
            }

            String reply = qwenService.recognizeImage(userId, imageBytes, instruction);
            String summary = instruction == null || instruction.isBlank() ? "请描述这张图片" : instruction;
            memoryService.addMessage(userId, "user", "[图片消息] " + summary);
            if (reply != null && !reply.isBlank()) {
                memoryService.addMessage(userId, "assistant", reply);
            }
            return reply == null || reply.isBlank() ? "暂时无法识别这张图片，请换一张再试。" : reply;
        } catch (IOException exception) {
            System.err.println("读取或保存用户图片失败: " + exception.getMessage());
            return "保存图片失败，请重新发送一次。";
        } catch (Exception exception) {
            System.err.println("处理图片失败: " + exception.getMessage());
            return "处理图片时出现问题，请稍后再试。";
        }
    }

    private void sendProgress(String userId, String message) {
        try {
            client.sendText(userId, message);
        } catch (IOException exception) {
            System.err.println("发送图片修改状态失败: " + exception.getMessage());
        }
    }

    @Override
    public boolean supports(MessageContext context) {
        return context.isHasImage();
    }
}
