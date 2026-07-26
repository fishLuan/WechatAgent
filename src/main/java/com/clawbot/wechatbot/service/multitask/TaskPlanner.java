package com.clawbot.wechatbot.service.multitask;

import java.util.List;

/** Splits one user message into independently executable, complete sub-tasks. */
public interface TaskPlanner {
    List<String> plan(String userText) throws Exception;
}
