package com.xwc.demo;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/weather")
public class WeatherQuery {

    @Value("${weather.api-key:}")
    private String apiKey;

    @Value("${weather.base-url:https://api.openweathermap.org/data/2.5/weather}")
    private String baseUrl;

    @GetMapping("/query")
    public String queryWeather(@RequestParam("city") String cityName) {
        try {
            return getWeather(cityName);
        } catch (IllegalArgumentException e) {
            return "❌ 查询失败：" + e.getMessage();
        } catch (RuntimeException e) {
            return "❌ 查询失败：" + e.getMessage();
        } catch (Exception e) {
            return "❌ 系统异常，请求失败：" + e.getMessage();
        }
    }

    private String getWeather(String cityName) throws Exception {
        if (cityName == null || cityName.trim().isEmpty()) {
            throw new IllegalArgumentException("【参数错误】未输入城市名称，请提供有效城市名！");
        }

        if (apiKey == null || apiKey.isEmpty() || "your-weather-api-key-here".equalsIgnoreCase(apiKey)) {
            throw new RuntimeException("【配置错误】请在 application.properties 中设置 weather.api.key！");
        }

        String url = String.format("%s?q=%s&appid=%s&units=metric&lang=zh_cn",
                baseUrl, cityName.trim(), apiKey);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                String respJson = EntityUtils.toString(response.getEntity(), "UTF-8");
                JSONObject json = new JSONObject(respJson);

                int code = json.optInt("cod", 200);
                if (code != 200) {
                    String msg = json.optString("message", "未知接口错误");
                    throw new RuntimeException("【接口错误】code:" + code + " 说明：" + msg);
                }

                String city = json.getString("name");
                JSONObject main = json.getJSONObject("main");
                double temp = main.getDouble("temp");
                int humidity = main.getInt("humidity");
                String weatherDesc = json.getJSONArray("weather")
                        .getJSONObject(0).getString("description");
                double windSpeed = json.getJSONObject("wind").getDouble("speed");

                return String.format("""
                        ========== %s 天气 ==========
                        天气状况：%s
                        当前温度：%.1f ℃
                        空气湿度：%d %%
                        风速：%.1f m/s
                        """, city, weatherDesc, temp, humidity, windSpeed);
            }
        }
    }
}