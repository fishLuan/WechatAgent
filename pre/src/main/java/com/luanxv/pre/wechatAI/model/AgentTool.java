package com.luanxv.pre.wechatAI.model;

/** Whitelisted actions that the LLM is allowed to select. */
public enum AgentTool {
    CHAT,
    WEB_SEARCH,
    IMAGE_GENERATE,
    IMAGE_EDIT
}
