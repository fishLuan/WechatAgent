package com.xwc.demo.llm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

// 职责：一次性读取 config.properties，提供两个大模型的配置
public class LlmConfig {

    //文本大模型配置
    String textBaseUrl;    //llm.base-url
    String textApiKey;     //llm.api-key
    String textModel;      //llm.model

    //图片生成配置
    boolean imageGenEnabled;    //imagegen.enabled
    String imageGenUrl;         //imagegen.base-url
    String imageGenApiKey;      //imagegen.api-key 或复用 llm.api-key
    String imageGenModel;       //imagegen.model

    //视觉生成配置
    String visionBaseUrl;     //可复用text的base-url
    String visionApiKey;      //imagegen.api-key 或复用 llm.api-key
    String visionModel;       //

    public static LlmConfig load(){
        LlmConfig a = new LlmConfig();
        try{
            Properties props = new Properties();
            // 先找项目根目录
            if(Files.exists(Paths.get("config.properties"))){
                props.load(new FileReader("config.properties"));
            }else {
                // 再找 resources
                var is = LlmConfig.class.getClassLoader().getResourceAsStream("config.properties");
                if(is != null){
                    props.load(is);
                }
            }

            a.textBaseUrl = props.getProperty("llm.base-url","").trim();
            a.textApiKey = props.getProperty("llm.api-key","").trim();
            a.textModel = props.getProperty("llm.model","").trim();

            a.visionBaseUrl = props.getProperty("vision.base-url","").trim();
            a.visionApiKey = props.getProperty("vision.api-key","").trim();
            a.visionModel = props.getProperty("vision.model","").trim();

            a.imageGenEnabled = "true".equalsIgnoreCase(props.getProperty("imagegen.enabled","false"));
            a.imageGenUrl = props.getProperty("imagegen.base-url","").trim();
            a.imageGenApiKey = props.getProperty("imagegen.api-key","").trim();
            a.imageGenModel = props.getProperty("imagegen.model","").trim();
        } catch (Exception e) {
            System.err.println("[LlmConfig] 加载失败: " + e.getMessage());
        }
        return a;
    }
    public boolean isTextEnabled() {
        return !textBaseUrl.isEmpty() && !textApiKey.isEmpty() && !textModel.isEmpty();
    }

    public boolean isVisionEnabled() {
        return !visionBaseUrl.isEmpty() && !visionApiKey.isEmpty() && !visionModel.isEmpty();
    }

    public boolean isImageGenEnabled() {
        return imageGenEnabled && !imageGenUrl.isEmpty() && !imageGenApiKey.isEmpty();
    }

}
