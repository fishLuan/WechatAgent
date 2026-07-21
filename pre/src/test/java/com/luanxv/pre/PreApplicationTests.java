package com.luanxv.pre;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "wechat.bot.enabled=false")
class PreApplicationTests {
    @Test
    void contextLoads() {
    }
}
