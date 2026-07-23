package com.clawbot.wechatbot.tools.webPageTool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 独立运行入口：不启动微信机器人，只测试网页正文抓取工具。 */
public class WebPageExtractToolDemo {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://example.com";
        int maxBodyChars = args.length > 1 ? Integer.parseInt(args[1]) : 6000;
        WebPageExtractTool tool = new WebPageExtractTool(10, 15, maxBodyChars);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode argumentsNode = mapper.createObjectNode();
        argumentsNode.put("url", url);
        argumentsNode.put("max_body_chars", maxBodyChars);
        System.out.println(tool.execute(argumentsNode));
    }
}
