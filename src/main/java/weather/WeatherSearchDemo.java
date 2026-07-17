package weather;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WeatherSearchDemo {
    // 你需要先注册获取自己的API Key：https://dev.qweather.com/
    private static final String API_KEY = "9d32045e17044aafa5be91efe73a3cfa";  // 替换成你的key

    public static void runDemo(String[] args) {
        // 测试不同城市的天气查询
        String[] cities = {"Beijing", "Shanghai", "Guangzhou", "New York", "", null};

        for (String city : cities) {
            System.out.println("\n========== 查询城市: " + (city == null ? "null" : city) + " ==========");
            try {
                String weatherInfo = getWeather(city);
                System.out.println("天气信息: " + weatherInfo);
            } catch (IllegalArgumentException e) {
                // 处理城市名为空的情况
                System.err.println("❌ 参数错误: " + e.getMessage());
            } catch (Exception e) {
                // 处理其他异常
                System.err.println("❌ 请求失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 查询天气
     * @param city 城市名称
     * @return 天气信息JSON字符串
     * @throws Exception 各种异常
     */
    public static String getWeather(String city) throws Exception {
        // 1. 参数校验：处理无城市名的情况
        if (city == null || city.trim().isEmpty()) {
            throw new IllegalArgumentException("城市名不能为空，请提供有效的城市名称");
        }

        // 2. 构建请求URL（URL编码处理中文）
        String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
        String urlStr = String.format("https://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s", encodedCity, API_KEY);

        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            // 3. 建立HTTP连接
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);  // 5秒连接超时
            connection.setReadTimeout(5000);     // 5秒读取超时
            connection.setRequestProperty("User-Agent", "Java Weather Client");

            // 4. 获取响应状态码
            int responseCode = connection.getResponseCode();
            System.out.println("响应状态码: " + responseCode);

            // 5. 根据状态码处理响应
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 成功：读取响应内容
                reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            } else {
                // 失败：读取错误流
                reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8)
                );
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorResponse.append(line);
                }
                throw new RuntimeException("HTTP请求失败，状态码: " + responseCode +
                        ", 错误信息: " + errorResponse.toString());
            }

        } finally {
            // 6. 关闭资源
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
