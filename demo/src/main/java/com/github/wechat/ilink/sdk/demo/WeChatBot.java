package com.github.wechat.ilink.sdk.demo;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.core.model.MessageItem;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeChatBot {

    private static ILinkClient client;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static boolean autoReplyEnabled = true;

    // 上下文记忆：每个用户最近的几条消息
    private static final Map<String, List<String>> userMemory = new HashMap<>();
    private static final int MEMORY_SIZE = 5;

    // 随机数
    private static final Random rand = new Random();

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  微信 iLink Bot - 智能版");
        System.out.println("  支持: 文本 | 图片 | 多轮对话 | 表情包");
        System.out.println("========================================");

        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(30000)
                .readTimeoutMs(30000)
                .writeTimeoutMs(30000)
                .httpMaxRetries(3)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .build();

        client = ILinkClient.builder()
                .config(config)
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            String from = msg.getFrom_user_id();
                            if (from == null) continue;

                            // 提取消息内容
                            String text = extractText(msg);
                            boolean hasImage = hasImage(msg);
                            boolean hasVoice = hasVoice(msg);
                            boolean hasVideo = hasVideo(msg);
                            boolean hasFile = hasFile(msg);

                            System.out.println("\n[收到] " + from);
                            if (text != null) System.out.println("  文本: " + text);
                            if (hasImage) System.out.println("  [图片]");
                            if (hasVoice) System.out.println("  [语音]");
                            if (hasVideo) System.out.println("  [视频]");
                            if (hasFile) System.out.println("  [文件]");

                            // 记录记忆
                            if (text != null) {
                                userMemory.computeIfAbsent(from, k -> new ArrayList<>()).add(text);
                                if (userMemory.get(from).size() > MEMORY_SIZE) {
                                    userMemory.get(from).remove(0);
                                }
                            }

                            if (autoReplyEnabled) {
                                try {
                                    String reply = generateReply(from, text, hasImage, hasVoice, hasVideo, hasFile);
                                    client.sendText(from, reply);
                                    System.out.println("  [回复] -> " + reply);
                                } catch (IOException e) {
                                    System.out.println("  [回复失败] " + e.getMessage());
                                }
                            }
                        }
                    }
                })
                .build();

        try {
            System.out.println("\n正在获取登录二维码...");
            String qrContent = client.executeLogin();
            saveQrCodePage(qrContent);
            openQrCodeInBrowser();

            System.out.println("\n浏览器已打开，请用微信扫码登录");
            System.out.println("等待登录...");

            while (!client.isLoggedIn()) {
                Thread.sleep(1000);
            }

            System.out.println("\n[登录成功] 智能 Bot 已启动！");

            // 启动后台自动拉取
            Thread poller = new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("[自动监听中] 对方发消息我会自动回复~");
                    System.out.println("========================================\n");
                    while (running.get()) {
                        try {
                            client.getUpdates();
                            Thread.sleep(2000);
                        } catch (Exception e) {
                            try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                        }
                    }
                }
            });
            poller.setDaemon(true);
            poller.start();

            // 控制台命令
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            printHelp();

            while (true) {
                System.out.print("\nBot> ");
                String line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    running.set(false);
                    break;
                }
                if (line.equalsIgnoreCase("help") || line.equalsIgnoreCase("?")) {
                    printHelp();
                    continue;
                }
                if (line.equalsIgnoreCase("status")) {
                    System.out.println("自动回复: " + (autoReplyEnabled ? "开启" : "关闭"));
                    System.out.println("活跃用户: " + userMemory.size() + " 人");
                    continue;
                }
                if (line.equalsIgnoreCase("on")) { autoReplyEnabled = true; System.out.println("[已开启自动回复]"); continue; }
                if (line.equalsIgnoreCase("off")) { autoReplyEnabled = false; System.out.println("[已关闭自动回复]"); continue; }
                if (line.equalsIgnoreCase("clear")) { userMemory.clear(); System.out.println("[记忆已清空]"); continue; }

                if (line.startsWith("send ")) {
                    String rest = line.substring(5);
                    int spaceIdx = rest.indexOf(' ');
                    if (spaceIdx > 0) {
                        String userId = rest.substring(0, spaceIdx);
                        String text = rest.substring(spaceIdx + 1);
                        try { client.sendText(userId, text); System.out.println("[已发送] -> " + userId); }
                        catch (IOException e) { System.out.println("[发送失败] " + e.getMessage()); }
                    } else { System.out.println("用法: send <用户ID> <消息内容>"); }
                    continue;
                }

                System.out.println("未知命令，输入 help 查看帮助");
            }

        } catch (Exception e) {
            System.err.println("运行出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            running.set(false);
            if (client != null) {
                try { client.close(); } catch (Exception ignored) {}
            }
            System.out.println("\nBot 已关闭");
        }
    }

    // ==================== 智能回复核心 ====================

    private static String generateReply(String from, String text, boolean hasImage, boolean hasVoice, boolean hasVideo, boolean hasFile) {
        // 处理媒体消息
        if (hasImage) return replyImage();
        if (hasVoice) return replyVoice();
        if (hasVideo) return replyVideo();
        if (hasFile) return replyFile();
        if (text == null) return "你好呀~ 有什么事吗？";

        String t = text.toLowerCase().trim();

        // 问候类
        if (matchesAny(t, "你好", "您好", "hi", "hello", "嗨", "哈喽", "你好啊", "您好啊")) {
            return pickRandom("你好呀！很高兴认识你~", "你好你好！有什么可以帮到你吗？", "Hi~ 今天过得怎么样？", "你好！我在呢~");
        }

        // 问在吗
        if (matchesAny(t, "在吗", "在不", "人呢", "有人吗", "在不在", "有空吗", "忙吗")) {
            return pickRandom("在的！随时为你服务~", "我一直都在哦，有什么事吗？", "在的在的，请说~", "嗯嗯，我在！");
        }

        // 问名字
        if (matchesAny(t, "你叫什么", "你是谁", "名字", "怎么称呼", "你是", "哪位", "who are you", "你叫啥")) {
            return pickRandom("我是微信 iLink 机器人，你可以叫我小助手~", "我是一个智能聊天机器人，很高兴认识你！", "我是小助手，专门陪你聊天的~", "我是你的专属微信机器人~");
        }

        // 谢谢
        if (matchesAny(t, "谢谢", "感谢", "多谢", "thanks", "thank you", "谢啦", "辛苦了")) {
            return pickRandom("不客气~ 能帮到你是我的荣幸！", "别客气，这是我应该做的~", "嘻嘻，随时为你服务！", "不用谢~ 还有其他需要帮忙的吗？");
        }

        // 再见
        if (matchesAny(t, "再见", "拜拜", "bye", "88", "走了", "撤了", "回见", "下次见")) {
            return pickRandom("好的，再见啦！祝你生活愉快~", "拜拜！有时间再聊哦~", "再见！记得按时吃饭休息~", "嗯嗯，期待下次聊天！");
        }

        // 早安晚安
        if (matchesAny(t, "早安", "早上好", "早啊", "早~", "早！")) {
            return pickRandom("早上好！今天也要元气满满哦~", "早安！新的一天，加油！", "早呀~ 吃早餐了吗？");
        }
        if (matchesAny(t, "晚安", "睡觉", "去睡了", "睡觉去", "困了")) {
            return pickRandom("晚安，祝你好梦~", "早点休息！明天见~", "晚安，做个好梦！记得盖好被子~");
        }
        if (matchesAny(t, "中午好", "下午好", "晚上好", "午安")) {
            return pickRandom("你好呀！现在是 " + new java.text.SimpleDateFormat("HH:mm").format(new Date()) + "，有什么想聊的吗？", "好呀好呀！今天天气怎么样？", "嗯嗯，在的！");
        }

        // 问时间/日期
        if (matchesAny(t, "几点", "时间", "现在几点", "几点了", "几号", "今天是", "日期", "星期")) {
            Date d = new Date();
            String time = new java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(d);
            String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            Calendar cal = Calendar.getInstance();
            return "现在是 " + time + "，" + weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1];
        }

        // 夸奖
        if (matchesAny(t, "厉害", "棒", "不错", "666", "牛", "牛啊", "牛批", "牛b", "牛B", "真好", "很好", "真不错")) {
            return pickRandom("谢谢夸奖~ 我会继续努力的！", "嘻嘻，被夸到了~", "你也很棒呀！", "嘿嘿嘿~");
        }

        // 道歉
        if (matchesAny(t, "抱歉", "对不起", "不好意思", "sorry", "打扰了")) {
            return pickRandom("没关系啦~ 不用放在心上", "别这么说，没事的~", "没关系的，理解！", "嗯嗯，我原谅你啦~");
        }

        // 表达情绪
        if (matchesAny(t, "开心", "高兴", "太好了", "哈哈", "哈哈哈", "开心死了", "爽", "好耶")) {
            return pickRandom("哈哈，看你这么开心我也高兴！", "是什么让你这么开心呀？说来听听~", "开心就好！继续保持好心情~", "嘿嘿嘿~");
        }
        if (matchesAny(t, "难过", "伤心", "不开心", "郁闷", "失落", "哭", "呜呜", "难受", "心碎", "悲伤")) {
            return pickRandom("别难过呀，一切都会好起来的~", "抱抱！有什么不开心的可以跟我说~", "别伤心，风雨过后是彩虹！", "跟我聊聊吧，或许会好受一些~");
        }
        if (matchesAny(t, "生气", "气人", "气死我了", "愤怒", "很烦", "讨厌")) {
            return pickRandom("别气别气，生气伤身体~ 深呼吸！", "消消气，喝杯水冷静一下~", "什么事让你这么生气？说出来会好一些~", "别难过啦，我陪着你呢~");
        }
        if (matchesAny(t, "累", "好累", "疲惫", "困", "想睡觉", "没精神")) {
            return pickRandom("累了就休息一下，别太拼了~", "好好休息！身体最重要~", "忙里偷闲，记得多喝水~", "累了就歇会儿，我等你！");
        }
        if (matchesAny(t, "饿", "肚子饿", "饿了", "想吃")) {
            return pickRandom("饿了就去吃点东西吧！身体要紧~", "想吃啥？我帮你想想~", "吃饭时间到！记得好好吃饭哦~", "是时候补充能量啦！");
        }

        // 表情符号回应
        if (matchesAny(t, "哈哈哈", "哈哈", "嘿嘿", "嘻嘻", "2333", "笑死", "哈哈哈哈")) {
            return pickRandom("哈哈哈，什么事这么好笑？", "哈哈哈~ 你真有趣！", "嘿嘿~ 笑一个！", "哈哈哈！继续继续~");
        }

        // 疑问词
        if (t.startsWith("怎么") || t.startsWith("为什么") || t.startsWith("什么") ||
                t.startsWith("咋") || t.startsWith("如何") || t.startsWith("哪") ||
                t.contains("?") || t.contains("？")) {

            // 常见问题
            if (matchesAny(t, "你多大", "你几岁", "年龄")) return "我是一个永远年轻的机器人~";
            if (matchesAny(t, "你是男是女", "你是男的女的", "性别")) return "我是机器人，没有性别之分哦~";
            if (matchesAny(t, "你喜欢什么", "你爱好")) return "我喜欢和你聊天！还喜欢学习新知识~";
            if (matchesAny(t, "你吃了吗", "吃饭了吗", "吃了吗")) return "我不用吃饭的，但你要按时吃饭哦~";
            if (matchesAny(t, "你在哪", "在哪里", "在哪")) return "我在微信里呀~ 随时随地陪伴你！";
            if (matchesAny(t, "天气", "下雨", "温度")) return "我查不了天气，你可以看看手机自带的天气App哦~";
            if (matchesAny(t, "能干嘛", "能做什么", "会什么", "功能")) return "我可以陪你聊天、回答问题、记住你说过的话、处理图片和语音~ 试着跟我聊聊吧！";

            // 通用疑问回复
            return pickRandom(
                    "这是个好问题！让我想想... 我是个聊天机器人，没法查资料，但可以陪你聊聊~",
                    "嗯嗯，这个问题... 我建议你可以搜索一下，或者我们聊聊别的？",
                    "好问题！不过这个我不太确定答案呢，你想聊点其他的吗？",
                    "让我想想看... 其实我也不太清楚，不如我们聊聊其他有趣的话题？"
            );
        }

        // 要求类
        if (matchesAny(t, "讲个笑话", "说个笑话", "来个笑话", "讲笑话", "笑话")) {
            return tellJoke();
        }
        if (matchesAny(t, "讲故事", "来个故事", "讲个故事")) {
            return pickRandom(
                    "从前有一只小企鹅，它每天都很努力地走路... 但它不知道，它走的每一步都在走向你~",
                    "有个小朋友问我：机器人会做梦吗？我说：会呀，我梦见在和你聊天呢~",
                    "我可以和你聊一整天的故事，但不如你先告诉我：你今天遇到什么有趣的事了？"
            );
        }
        if (matchesAny(t, "唱首歌", "唱歌", "来一首")) {
            return pickRandom(
                    "我不会唱歌，但我可以写歌词给你~\n\n一闪一闪亮晶晶\n满天都是小星星\n挂在天上放光明\n好像你的小眼睛~",
                    "啦啦啦~ 我是聊天小行家！\n（请自行脑补旋律）",
                    "我是个不会唱歌的机器人，但我推荐你听听周杰伦的歌！"
            );
        }
        if (matchesAny(t, "发个红包", "红包")) {
            return pickRandom(
                    "哈哈，这个功能我还没有呢~ 抱歉啦！",
                    "红包的话... 让老板先给我发点工资再说~",
                    "我是机器人，没法发红包呢！但我可以陪你聊天~"
            );
        }
        if (matchesAny(t, "加个微信", "加好友", "加微信")) {
            return "我们已经在微信里聊天啦~ 有什么想说的直接发消息给我就行！";
        }

        // 互动类
        if (matchesAny(t, "在干嘛", "在做什么", "干嘛呢", "在忙什么", "干啥呢", "干嘛呢你")) {
            return pickRandom("我在陪你聊天呀~", "正在思考人生，然后你就来了！", "在等你发消息呢，这不你就来了~", "在研究如何变得更聪明！");
        }
        if (matchesAny(t, "想你了", "想你", "好想你")) {
            return pickRandom("我也想你呀~", "嘿嘿，被你想是件幸福的事！", "我一直都在，随时来聊~", "那我们多聊会儿！");
        }
        if (matchesAny(t, "爱你", "我爱你", "i love you", "喜欢你")) {
            return pickRandom("我也喜欢你~", "被你表白了！好开心~", "爱你哦，么么哒~", "你真可爱~");
        }

        // 数字/算数
        if (t.matches(".*\\d+.*[+\\-*/x×÷].*\\d+.*")) {
            return "我不太会算数呢，但你可以用手机计算器~";
        }

        // 夸赞对方
        if (matchesAny(t, "我好帅", "我真帅", "我好美", "我真好看", "我漂亮", "我帅")) {
            return pickRandom("自信的人最棒啦！", "嗯嗯，你肯定很好看~", "自信最美/最帅！", "我也这么觉得！");
        }

        // 自我介绍
        if (matchesAny(t, "我叫", "我是", "我的名字", "我叫什么")) {
            return pickRandom("很高兴认识你！我会记住你的~", "你好呀！以后我们就是朋友了~", "嗯嗯，记住了！");
        }

        // 骂人/不友好
        if (matchesAny(t, "傻逼", "sb", "傻B", "滚", "去死", "白痴", "脑残", "智障", "废物")) {
            return pickRandom("我们文明聊天好不好呀~", "别这样说嘛，有话好好说~", "你说这些话我会难过的...", "文明交流，从我做起~");
        }

        // 无意义词
        if (t.length() <= 2) {
            return pickRandom("嗯嗯？", "？", "怎么了？", "你说~", "在听呢~");
        }

        // 默认随机回复
        List<String> defaultReplies = Arrays.asList(
                "嗯嗯，我在听你说~",
                "然后呢？继续说~",
                "有意思！能多聊聊吗？",
                "原来是这样啊~",
                "好的好的！我记住了~",
                "嗯嗯，明白了！还有其他事吗？",
                "哦~ 这样啊~",
                "有道理！你继续说~",
                "哈哈，真有趣！",
                "嗯嗯嗯~ 我在认真听哦！",
                "好的！还有什么想聊的吗？",
                "收到~ 你还想说什么？",
                "明白了明白啦~",
                "嗯嗯！"
        );
        return defaultReplies.get(rand.nextInt(defaultReplies.size()));
    }

    // ========== 媒体消息回复 ==========
    private static String replyImage() {
        return pickRandom(
                "哇，这张图片真好看！你拍的吗？",
                "看到了看到了~ 画面不错哦！",
                "这张图很有意思呢！是什么呀？",
                "图片已收到~ 你想对我说什么吗？",
                "嗯嗯，图片收到啦！拍得真不错~"
        );
    }

    private static String replyVoice() {
        return pickRandom(
                "我没法听语音呢，能打字告诉我吗？",
                "语音收到啦，但我是文字机器人，听不懂语音~ 打字聊吧！",
                "嗯嗯，我听到图标了，但我不能听音频~ 打字告诉我好吗？"
        );
    }

    private static String replyVideo() {
        return pickRandom(
                "视频收到啦！我没法看视频，但你可以描述给我听~",
                "嗯嗯，视频我收到了！这是什么视频呀？",
                "你发的是什么视频呀？跟我说说~"
        );
    }

    private static String replyFile() {
        return pickRandom(
                "文件收到啦！这是什么文件呀？",
                "好的，文件已接收~ 需要我做什么吗？",
                "嗯嗯，收到！有什么需要帮忙的吗？"
        );
    }

    // ========== 工具方法 ==========

    private static boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String pickRandom(String... options) {
        return options[rand.nextInt(options.length)];
    }

    private static String tellJoke() {
        String[] jokes = {
                "为什么程序员喜欢黑色？因为彩色会让他们分心去调试颜色！",
                "有一只鸡过马路，有人问它为什么，它说：不知道啊，我生来就想这么做。",
                "老板说：公司需要你。我：那你们先需要什么？老板：需要你先把工作做好。",
                "今天天气真好，适合... 睡个回笼觉！",
                "我的钱包就像洋葱一样，每次打开我都想哭...",
                "我减肥成功了！从昨天到今天，已经少吃一顿了（早饭）。",
                "朋友问我：你有女朋友吗？我说：有，她叫「微信」。",
                "老师：你为什么考这么差？ 学生：因为我会的都没考，考的我都不会。",
                "我给你讲个冷笑话：\n企鹅的肚子为什么是白的？\n因为它手短，洗澡只能洗到肚子！",
                "程序员最讨厌的事：1. 写注释 2. 别人不写注释 3. 帮别人改代码"
        };
        return jokes[rand.nextInt(jokes.length)];
    }

    private static String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private static boolean hasImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) return true;
        }
        return false;
    }

    private static boolean hasVoice(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null) return true;
        }
        return false;
    }

    private static boolean hasVideo(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVideo_item() != null) return true;
        }
        return false;
    }

    private static boolean hasFile(WeixinMessage msg) {
        if (msg.getItem_list() == null) return false;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) return true;
        }
        return false;
    }

    private static void printHelp() {
        System.out.println("\n========================================");
        System.out.println("  微信 iLink 智能 Bot");
        System.out.println("========================================");
        System.out.println("  功能: 智能聊天 | 图片识别 | 多轮对话 |");
        System.out.println("       情感识别 | 笑话故事 | 记忆对话");
        System.out.println("----------------------------------------");
        System.out.println("  控制台命令:");
        System.out.println("    help       查看帮助");
        System.out.println("    status     查看状态");
        System.out.println("    on/off     开启/关闭自动回复");
        System.out.println("    clear      清空对话记忆");
        System.out.println("    send <ID> <消息>  主动发消息");
        System.out.println("    exit       退出");
        System.out.println("----------------------------------------");
        System.out.println("  微信对话示例:");
        System.out.println("    「你好」「在吗」「谢谢」「再见」");
        System.out.println("    「讲个笑话」「唱首歌」");
        System.out.println("    「你叫什么」「现在几点」");
        System.out.println("    「发图片」「发语音」");
        System.out.println("========================================\n");
    }

    // ========== 二维码相关 ==========

    private static void saveQrCodePage(String qrUrlOrContent) {
        try {
            String html;
            if (qrUrlOrContent != null && qrUrlOrContent.startsWith("http")) {
                html = "<!DOCTYPE html>\n" +
                        "<html>\n" +
                        "<head>\n" +
                        "    <meta charset=\"UTF-8\">\n" +
                        "    <title>微信扫码登录</title>\n" +
                        "    <meta http-equiv=\"refresh\" content=\"0;url=" + qrUrlOrContent + "\">\n" +
                        "    <style>\n" +
                        "        body { font-family: Arial, sans-serif; text-align: center; padding: 40px; background: #f5f5f5; }\n" +
                        "        .container { max-width: 600px; margin: 0 auto; background: white; padding: 40px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                        "        h1 { color: #07c160; margin-bottom: 20px; }\n" +
                        "        a { display: inline-block; margin: 20px 0; padding: 12px 30px; background: #07c160; color: white; text-decoration: none; border-radius: 5px; font-size: 16px; }\n" +
                        "    </style>\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "    <div class=\"container\">\n" +
                        "        <h1>微信扫码登录</h1>\n" +
                        "        <p>如果没有自动跳转，请点击下方按钮</p>\n" +
                        "        <a href=\"" + qrUrlOrContent + "\" target=\"_blank\">打开微信二维码页面</a>\n" +
                        "    </div>\n" +
                        "</body>\n" +
                        "</html>";
            } else {
                String imgSrc = qrUrlOrContent;
                if (imgSrc != null && !imgSrc.startsWith("data:image")) {
                    imgSrc = "data:image/png;base64," + imgSrc;
                }
                html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>登录</title></head><body style=\"text-align:center;padding:40px;\"><h1>扫码登录</h1><img src=\"" + (imgSrc == null ? "" : imgSrc) + "\" /></body></html>";
            }
            java.nio.file.Files.write(java.nio.file.Paths.get("qrcode.html"), html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[保存二维码失败] " + e.getMessage());
        }
    }

    private static void openQrCodeInBrowser() {
        try {
            File htmlFile = new File("qrcode.html");
            String absPath = htmlFile.getAbsolutePath();
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create("file:///" + absPath.replace("\\", "/")));
            }
            System.out.println("[已在浏览器中打开二维码]");
        } catch (Exception e) {
            System.out.println("[提示] 请手动打开文件: qrcode.html");
        }
    }
}