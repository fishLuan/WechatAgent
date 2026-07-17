package weather;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class WeatherHttpRequest {
    private static final String API_KEY = "9d32045e17044aafa5be91efe73a3cfa";
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public static void runDemo(String[] args) {
        String[] cities = {"Beijing", "Shanghai", "Guangzhou", "New York", "", null};

        for (String city : cities) {
            System.out.println("\n========== 查询: " + (city == null ? "null" : city) + " ==========");
            try {
                String result = getWeather(city);
                System.out.println("✅ 成功: " + result);
            } catch (IllegalArgumentException e) {
                System.err.println("❌ 参数错误: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("❌ 请求失败: " + e.getMessage());
            }
        }
    }

    public static String getWeather(String city) throws Exception {
        // 参数校验
        if (city == null || city.trim().isEmpty()) {
            throw new IllegalArgumentException("城市名不能为空");
        }

        // 构建请求
        String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String url = String.format("https://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s", encodedCity, API_KEY);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Java Weather Client")
                .GET()
                .build();

        // 发送请求
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        // 检查状态码
        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new RuntimeException("HTTP错误: " + response.statusCode() +
                    ", 响应: " + response.body());
        }
    }
}
