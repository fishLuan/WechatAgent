package WechatAI.service.impl;

import WechatAI.config.AiProperties;
import WechatAI.service.SpeechRecognitionService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kasukusakura.silkcodec.SilkCoder;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 千问 ASR 实现，负责微信 Silk 语音转码和语音识别调用。
 */
public class QwenSpeechRecognitionService implements SpeechRecognitionService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties properties;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private String lastErrorMessage;

    public QwenSpeechRecognitionService(AiProperties properties) {
        this(properties, defaultHttpClient(), new Gson());
    }

    public QwenSpeechRecognitionService(AiProperties properties, OkHttpClient httpClient, Gson gson) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    @Override
    public String recognize(byte[] audioBytes, String fileName) {
        return recognize(audioBytes, fileName, null, null);
    }

    @Override
    public String recognize(byte[] audioBytes, String fileName, String format, Integer sampleRate) {
        lastErrorMessage = null;
        if (!hasApiKey(properties.getSpeechRecognitionApiKey())) {
            setLastErrorMessage("语音识别模型还没有配置 API Key。");
            return null;
        }

        AudioPayload audioPayload = prepareAudioPayload(audioBytes, fileName, format, sampleRate);
        if (audioPayload == null) {
            return null;
        }

        Request request = new Request.Builder()
                .url(properties.getSpeechRecognitionApiUrl())
                .addHeader("Authorization", "Bearer " + properties.getSpeechRecognitionApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON, gson.toJson(buildRequestBody(audioPayload))))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                setLastErrorMessage("语音识别API请求失败：" + response.code() + " - " + response.message() + formatErrorBody(responseBody));
                return null;
            }
            return parseText(responseBody);
        } catch (Exception e) {
            setLastErrorMessage("调用语音识别模型失败：" + e.getMessage());
            return null;
        }
    }

    @Override
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private String parseText(String responseBody) {
        JsonObject response = gson.fromJson(responseBody, JsonObject.class);
        if (response.has("text")) {
            return response.get("text").getAsString();
        }
        if (response.has("output")) {
            JsonObject output = response.getAsJsonObject("output");
            if (output.has("text")) {
                return output.get("text").getAsString();
            }
            if (output.has("sentence")) {
                return output.get("sentence").getAsString();
            }
            if (output.has("sentences")) {
                StringBuilder textBuilder = new StringBuilder();
                for (int i = 0; i < output.getAsJsonArray("sentences").size(); i++) {
                    JsonObject sentence = output.getAsJsonArray("sentences").get(i).getAsJsonObject();
                    if (sentence.has("text")) {
                        textBuilder.append(sentence.get("text").getAsString());
                    }
                }
                if (textBuilder.length() > 0) {
                    return textBuilder.toString();
                }
            }
        }
        setLastErrorMessage("语音识别响应中没有 text 字段：" + responseBody);
        return null;
    }

    private JsonObject buildRequestBody(AudioPayload audioPayload) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", properties.getSpeechRecognitionModel());

        JsonObject input = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject audioPart = new JsonObject();
        audioPart.addProperty("type", "input_audio");
        JsonObject inputAudio = new JsonObject();
        inputAudio.addProperty("data", buildAudioDataUrl(audioPayload.audioBytes, audioPayload.format));
        audioPart.add("input_audio", inputAudio);
        content.add(audioPart);

        userMessage.add("content", content);
        messages.add(userMessage);
        input.add("messages", messages);
        requestBody.add("input", input);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("format", audioPayload.format);
        if (audioPayload.sampleRate != null && audioPayload.sampleRate > 0) {
            parameters.addProperty("sample_rate", audioPayload.sampleRate);
        }
        requestBody.add("parameters", parameters);
        return requestBody;
    }

    private AudioPayload prepareAudioPayload(byte[] audioBytes, String fileName, String format, Integer sampleRate) {
        String detectedFormat = isBlank(format) ? inferAudioFormat(audioBytes, fileName) : format;
        int actualSampleRate = sampleRate == null || sampleRate <= 0 ? 16000 : sampleRate;
        if ("silk".equals(detectedFormat)) {
            try {
                System.out.println("🎙️ 检测到微信 Silk 语音，开始转码为 wav");
                byte[] silkBytes = normalizeSilkBytes(audioBytes);
                ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
                SilkCoder.decode(new ByteArrayInputStream(silkBytes), pcmOutput, true, actualSampleRate, 0);
                byte[] wavBytes = wrapPcmAsWav(pcmOutput.toByteArray(), actualSampleRate, 1, 16);
                return new AudioPayload(wavBytes, "wav", actualSampleRate);
            } catch (Exception e) {
                setLastErrorMessage("微信 Silk 语音转 WAV 失败：" + e.getMessage());
                return null;
            }
        }
        return new AudioPayload(audioBytes, detectedFormat, actualSampleRate);
    }

    private String buildAudioDataUrl(byte[] audioBytes, String format) {
        String firstPrompt = "正在为你生成";
        String mimeType = firstPrompt+ "/" + format;
        if ("wav".equals(format)) {
            mimeType = firstPrompt + "/wav";
        }
        if ("mp3".equals(format)) {
            mimeType = firstPrompt + "/mpeg";
        }
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
    }

    private String inferAudioFormat(byte[] audioBytes, String fileName) {
        String detected = detectAudioFormat(audioBytes);
        if (!isBlank(detected)) {
            return detected;
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            return "amr";
        }
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".wav")) {
            return "wav";
        }
        if (lowerName.endsWith(".mp3")) {
            return "mp3";
        }
        if (lowerName.endsWith(".opus")) {
            return "opus";
        }
        if (lowerName.endsWith(".amr")) {
            return "amr";
        }
        return "amr";
    }

    private String detectAudioFormat(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length < 12) {
            return null;
        }
        String header12 = new String(audioBytes, 0, Math.min(audioBytes.length, 12));
        if (header12.startsWith("RIFF") && header12.contains("WAVE")) {
            return "wav";
        }
        if (header12.startsWith("ID3") || ((audioBytes[0] & 0xFF) == 0xFF && (audioBytes[1] & 0xE0) == 0xE0)) {
            return "mp3";
        }
        if (header12.startsWith("#!AMR")) {
            return "amr";
        }
        if (header12.startsWith("OggS")) {
            return "opus";
        }
        if (header12.contains("#!SILK_V3")) {
            return "silk";
        }
        System.err.println("⚠️ 未识别的语音文件头: " + toHex(audioBytes, 16));
        return null;
    }

    private byte[] normalizeSilkBytes(byte[] audioBytes) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IOException("语音内容为空");
        }
        byte[] silkHeader = "#!SILK_V3".getBytes();
        int headerIndex = indexOf(audioBytes, silkHeader);
        if (headerIndex < 0) {
            throw new IOException("没有找到 Silk V3 文件头");
        }
        if (headerIndex == 0) {
            return audioBytes;
        }

        byte[] normalized = new byte[audioBytes.length - headerIndex];
        System.arraycopy(audioBytes, headerIndex, normalized, 0, normalized.length);
        return normalized;
    }

    private int indexOf(byte[] source, byte[] target) {
        for (int i = 0; i <= source.length - target.length; i++) {
            boolean matched = true;
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    private byte[] wrapPcmAsWav(byte[] pcmBytes, int sampleRate, int channels, int bitsPerSample) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("RIFF".getBytes());
        writeLittleEndianInt(output, 36 + pcmBytes.length);
        output.write("WAVE".getBytes());
        output.write("fmt ".getBytes());
        writeLittleEndianInt(output, 16);
        writeLittleEndianShort(output, (short) 1);
        writeLittleEndianShort(output, (short) channels);
        writeLittleEndianInt(output, sampleRate);
        writeLittleEndianInt(output, byteRate);
        writeLittleEndianShort(output, (short) blockAlign);
        writeLittleEndianShort(output, (short) bitsPerSample);
        output.write("data".getBytes());
        writeLittleEndianInt(output, pcmBytes.length);
        output.write(pcmBytes);
        return output.toByteArray();
    }

    private void writeLittleEndianInt(ByteArrayOutputStream output, int value) throws IOException {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private void writeLittleEndianShort(ByteArrayOutputStream output, short value) throws IOException {
        output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array());
    }

    private String toHex(byte[] bytes, int maxLength) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int length = Math.min(bytes.length, maxLength);
        for (int i = 0; i < length; i++) {
            builder.append(String.format("%02X", bytes[i]));
            if (i < length - 1) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class AudioPayload {
        private final byte[] audioBytes;
        private final String format;
        private final Integer sampleRate;

        private AudioPayload(byte[] audioBytes, String format, Integer sampleRate) {
            this.audioBytes = audioBytes;
            this.format = format;
            this.sampleRate = sampleRate;
        }
    }

    private String formatErrorBody(String responseBody) {
        return responseBody == null || responseBody.isEmpty() ? "" : "，错误详情：" + responseBody;
    }

    private void setLastErrorMessage(String message) {
        lastErrorMessage = message;
        System.err.println("❌ " + message);
    }

    private static boolean hasApiKey(String apiKey) {
        return apiKey != null && !apiKey.trim().isEmpty() && !"sk-your-actual-api-key-here".equals(apiKey);
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }
}
