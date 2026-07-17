package com.student.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/weather")
public class OpenWeatherDemo {
    private static final String KEY = firstNonNull(
        System.getenv("OPENWEATHER_API_KEY"),
        System.getProperty("openweather.api.key"),
        ""
    );
    private static final String BASE = "https://api.openweathermap.org/data/2.5/weather";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static String firstNonNull(String... items) {
        for (String s : items) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }

    @GetMapping("/{city}")
    public Map<String, Object> getWeatherByCity(@PathVariable String city) {
        return getWeather(city);
    }

    public static Map<String, Object> getWeather(String city) {
        Map<String, Object> result = new HashMap<>();

        if (KEY == null || KEY.trim().isEmpty()) {
            result.put("状态", "失败");
            result.put("错误信息", "API密钥未配置，请设置环境变量 OPENWEATHER_API_KEY 或系统属性 openweather.api.key");
            return result;
        }

        if (city == null || city.isBlank()) {
            result.put("状态", "失败");
            result.put("错误信息", "未传入城市名称");
            return result;
        }
        
        try {
            String urlStr = BASE + "?q=" + city + "&appid=" + KEY + "&units=metric&lang=zh_cn";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);

            int code = conn.getResponseCode();
            if (code == 404) {
                result.put("状态", "失败");
                result.put("错误信息", "城市不存在");
                return result;
            }
            if (code == 401) {
                result.put("状态", "失败");
                result.put("错误信息", "API密钥无效");
                return result;
            }
            if (code != 200) {
                result.put("状态", "失败");
                result.put("错误信息", "接口异常，状态码：" + code);
                return result;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JsonNode root = objectMapper.readTree(sb.toString());
            
            result.put("状态", "成功");
            result.put("城市", root.get("name").asText());
            result.put("国家", root.get("sys").get("country").asText());
            
            JsonNode weather = root.get("weather").get(0);
            result.put("天气状况", weather.get("description").asText());
            result.put("天气图标", weather.get("icon").asText());
            
            JsonNode main = root.get("main");
            result.put("温度", main.get("temp").asDouble() + "°C");
            result.put("体感温度", main.get("feels_like").asDouble() + "°C");
            result.put("最低温度", main.get("temp_min").asDouble() + "°C");
            result.put("最高温度", main.get("temp_max").asDouble() + "°C");
            result.put("湿度", main.get("humidity").asInt() + "%");
            result.put("气压", main.get("pressure").asInt() + " hPa");
            
            JsonNode wind = root.get("wind");
            result.put("风速", wind.get("speed").asDouble() + " m/s");
            
            result.put("能见度", root.get("visibility").asInt() + " 米");
            
            JsonNode clouds = root.get("clouds");
            result.put("云量", clouds.get("all").asInt() + "%");

            return result;
            
        } catch (Exception e) {
            result.put("状态", "失败");
            result.put("错误信息", "网络请求异常：" + e.getMessage());
            return result;
        }
    }

    public static void main(String[] args) {
        String[] testArr = {"Beijing", "", "TestCity999"};
        for (String c : testArr) {
            System.out.println("查询[" + c + "]：" + getWeather(c));
            System.out.println("--------------------------------");
        }
    }
}