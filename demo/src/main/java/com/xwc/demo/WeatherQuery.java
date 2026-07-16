package com.xwc.demo;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/weather")
public class WeatherQuery {
    // ========== 请在这里替换成你自己的有效API密钥 ==========
    private static final String API_KEY = "1ee36f7a2f6c9d1ea32c9b80592bef03";
    // =======================================================
    private static final String BASE_URL = "https://api.openweathermap.org/data/2.5/weather";

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

    public static String getWeather(String cityName) throws Exception {
        if (cityName == null || cityName.trim().isEmpty()) {
            throw new IllegalArgumentException("【参数错误】未输入城市名称，请提供有效城市名！");
        }

        if ("YOUR_API_KEY_HERE".equals(API_KEY)) {
            throw new RuntimeException("【配置错误】请在第16行替换成你自己的OpenWeatherMap API密钥！");
        }

        String url = String.format("%s?q=%s&appid=%s&units=metric&lang=zh_cn",
                BASE_URL, cityName.trim(), API_KEY);

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
    public static void main(String[] args) {
        String[] testCityList = {
                "Beijing",
                "Shanghai",
                "Guangzhou",
                "",
                "InvalidCityName"
        };
        // 循环测试所有城市，统一捕获异常打印错误信息
        for (String city : testCityList) {
            System.out.println("正在查询城市：[" + city + "]");
            try {
                String weatherInfo = getWeather(city);
                System.out.println(weatherInfo);
            } catch (IllegalArgumentException e) {
                // 专门捕获【无城市名】参数异常
                System.out.println("❌ 查询失败：" + e.getMessage() + "\n");
            } catch (RuntimeException e) {
                // 捕获接口返回业务错误（城市不存在、密钥失效等）
                System.out.println("❌ 查询失败：" + e.getMessage() + "\n");
            } catch (Exception e) {
                // 兜底捕获网络异常、解析异常等全部未知错误
                System.out.println("❌ 系统异常，请求失败：" + e.getMessage() + "\n");
            }
        }
    }
}