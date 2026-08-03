package com.clawbot.wechatbot.config;

import com.clawbot.wechatbot.tools.FunctionTool;
import com.clawbot.wechatbot.tools.FunctionToolRegistry;
import com.clawbot.wechatbot.tools.UrlSafetyCheckerTool.UrlSafetyChecker;
import com.clawbot.wechatbot.tools.bazitool.BaziFortuneTool;
import com.clawbot.wechatbot.tools.calculatezodiacinfotool.CalculateZodiacInfoTool;
import com.clawbot.wechatbot.tools.currenttimetool.CurrentTimeTool;
import com.clawbot.wechatbot.tools.exchangeratetool.ExchangeRateTool;
import com.clawbot.wechatbot.tools.idcardtool.IdCardTool;
import com.clawbot.wechatbot.tools.pathplantool.PathPlanTool;
import com.clawbot.wechatbot.tools.searchWeatherTool.AmapWeatherTool;
import com.clawbot.wechatbot.tools.searchonlinetool.WebSearchTool;
import com.clawbot.wechatbot.tools.tiannewstool.TianNewsTool;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractClient;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractTool;
import com.clawbot.wechatbot.tools.webaccess.SafeHttpFetcher;
import com.clawbot.wechatbot.tools.webaccess.UrlAccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 外部查询和通用 Function Tool 的装配。 */
@Configuration(proxyBeanMethods = false)
public class ToolBeanConfiguration {
    @Bean
    AmapWeatherTool amapWeatherTool(BotConfig config) {
        return new AmapWeatherTool(
            config.getAmapWeatherApiKey(), config.getAmapWeatherEndpoint(),
            config.getAmapConnectTimeoutSeconds(), config.getAmapRequestTimeoutSeconds());
    }

    @Bean
    ExchangeRateTool exchangeRateTool(BotConfig config) {
        return new ExchangeRateTool(
            config.getJuheExchangeApiKey(), config.getJuheExchangeEndpoint(),
            config.getJuheExchangeVersion(), config.getJuheExchangeConnectTimeoutSeconds(),
            config.getJuheExchangeRequestTimeoutSeconds());
    }

    @Bean BaziFortuneTool baziFortuneTool(ObjectMapper mapper) { return new BaziFortuneTool(mapper); }
    @Bean WebSearchTool webSearchTool(BotConfig config) {
        return new WebSearchTool(config.getBochaApiKey(), config.getBochaEndpoint(),
            config.getBochaConnectTimeoutSeconds(), config.getBochaRequestTimeoutSeconds());
    }
    @Bean TianNewsTool tianNewsTool(BotConfig config) { return new TianNewsTool(config.getTianapiApiKey()); }

    @Bean
    UrlAccessPolicy urlAccessPolicy(BotConfig config) {
        Set<Integer> allowedPorts = Arrays.stream(config.getWebPageExtractAllowedPorts().split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).map(Integer::parseInt)
            .collect(Collectors.toUnmodifiableSet());
        return new UrlAccessPolicy(allowedPorts);
    }

    @Bean
    SafeHttpFetcher safeHttpFetcher(BotConfig config, UrlAccessPolicy accessPolicy) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getWebPageExtractConnectTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return new SafeHttpFetcher(http, accessPolicy,
            Duration.ofSeconds(config.getWebPageExtractRequestTimeoutSeconds()),
            config.getWebPageExtractMaxResponseBytes(), config.getWebPageExtractMaxRedirects(),
            "ClawBot-SafeHttpFetcher/1.0");
    }

    @Bean
    WebPageExtractTool webPageExtractTool(
        BotConfig config, SafeHttpFetcher fetcher, ObjectMapper mapper
    ) {
        WebPageExtractClient client = new WebPageExtractClient(
            fetcher, config.getWebPageExtractMaxBodyChars());
        return new WebPageExtractTool(client, mapper, config.getWebPageExtractMaxBodyChars());
    }

    @Bean UrlSafetyChecker urlSafetyChecker(ObjectMapper mapper, SafeHttpFetcher fetcher) {
        return new UrlSafetyChecker(mapper, fetcher);
    }
    @Bean CurrentTimeTool currentTimeTool(ObjectMapper mapper) { return new CurrentTimeTool(mapper); }
    @Bean IdCardTool idCardTool(ObjectMapper mapper) { return new IdCardTool(mapper); }
    @Bean PathPlanTool pathPlanTool(BotConfig config) {
        return new PathPlanTool(config.getAmapWeatherApiKey(),
            "https://restapi.amap.com/v3/geocode/geo",
            config.getAmapConnectTimeoutSeconds(), config.getAmapRequestTimeoutSeconds());
    }
    @Bean CalculateZodiacInfoTool calculateZodiacInfoTool(ObjectMapper mapper) {
        return new CalculateZodiacInfoTool(mapper);
    }
    @Bean FunctionToolRegistry functionToolRegistry(ObjectMapper mapper, List<FunctionTool> tools) {
        return new FunctionToolRegistry(mapper, tools);
    }
}
