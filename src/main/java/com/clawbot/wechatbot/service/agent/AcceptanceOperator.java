package com.clawbot.wechatbot.service.agent;

/** 任务输出验收条件支持的受控操作符。 */
public enum AcceptanceOperator {
    EXISTS,
    NOT_EMPTY,
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    MATCHES_REGEX,
    TYPE_IS
}
