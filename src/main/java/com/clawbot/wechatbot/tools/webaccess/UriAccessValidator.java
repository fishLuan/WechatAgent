package com.clawbot.wechatbot.tools.webaccess;

import java.net.URI;

@FunctionalInterface
public interface UriAccessValidator {
    void validate(URI uri) throws Exception;
}
