package com.xwc.demo.llm;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.util.Scanner;

public class VoiceRecongnition {

    public static void main(String[] args) throws Exception {
        AudioFormat format = new AudioFormat(16000f, 16, 1, true, false);

        System.out.println("🎤 准备录音...");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            try {
                if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, null))) {
                    System.out.println("  设备: " + info.getName());
                }
            } catch (Exception ignored) {}
        }

        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(lineInfo)) {
            System.err.println("❌ 不支持此音频格式的麦克风");
            return;
        }

        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(lineInfo);
        microphone.open(format);
        microphone.start();
        AudioInputStream ais = new AudioInputStream(microphone);
        File outFile = new File("recording.wav");
        System.out.println("正在录音，按回车停止 → " + outFile.getAbsolutePath());

        Thread writeThread = new Thread(() -> {
            try {
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        writeThread.start();

        new Scanner(System.in).nextLine();

        microphone.stop();
        microphone.close();
        System.out.println("✓ 录音结束 (" + outFile.length() + " 字节)");
    }
}