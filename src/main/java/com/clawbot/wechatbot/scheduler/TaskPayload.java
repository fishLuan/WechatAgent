package com.clawbot.wechatbot.scheduler;

/** 定时任务的「业务内容」接口 —— 与调度元数据（什么时候跑/一次性还是周期）彻底解耦。
 *  以后加任何业务（B 站监控、日报推送、天气提醒），只需要实现这个接口即可。 */
public interface TaskPayload {

    /** Payload 类型标识，存进 ScheduledTask.payloadType，工厂根据它反序列化。 */
    String getType();

    /** 显示在「我的任务」列表里的短标题（15 字左右，不要太长）。 */
    String getDisplayName();

    /** 真正的业务执行入口。调度器只管「到点调 execute」，具体业务由 Payload 自己实现。
     *  @param sender   微信消息发送器（要发微信用这个）
     *  @param userId   任务所属的微信用户 ID（wxid_xxx）
     *  @throws Exception 任何业务异常，抛出来后调度器会走重试流程（3 次） */
    void execute(WeChatMessageSender sender, String userId) throws Exception;

    /** 序列化成 JSON 存进 ScheduledTask.payloadJson（持久化用）。 */
    String toJson();
}