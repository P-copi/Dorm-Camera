package com.pcopi.dormcamera;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Small app-controlled shutter click routed through STREAM_MUSIC.
 * The per-play gain is intentionally perceptual so low/mid/high slider values
 * sound clearly different on phones whose hardware volume curve is compressed.
 */
public final class ShutterClickPlayer {
    private static final int SAMPLE_RATE = 22050;
    private static final int DURATION_MS = 135;

    private final Context context;
    private final AudioManager audioManager;
    private final SoundPool soundPool;
    private int soundId;
    private boolean loaded;

    public ShutterClickPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build();
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (sampleId == soundId && status == 0) loaded = true;
        });

        File wave = new File(this.context.getCacheDir(), "dorm_camera_shutter_v127.wav");
        try {
            if (!wave.exists() || wave.length() < 512L) writeWave(wave);
            soundId = soundPool.load(wave.getAbsolutePath(), 1);
        } catch (Exception ignored) {
            soundId = 0;
        }
    }

    public void playCurrentLevel() {
        if (!loaded || soundId == 0 || audioManager == null) return;
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current <= 0) return;

        float normalized = Math.max(0f, Math.min(1f, current / (float) max));
        // Extra perceptual curve on top of Android's own stream curve gives clear separation.
        float gain = (float) Math.pow(normalized, 1.85);
        soundPool.play(soundId, gain, gain, 1, 0, 1.0f);
    }

    public void release() {
        try {
            soundPool.release();
        } catch (Exception ignored) {
        }
    }

    private static void writeWave(File file) throws IOException {
        int sampleCount = SAMPLE_RATE * DURATION_MS / 1000;
        byte[] pcm = new byte[sampleCount * 2];

        for (int i = 0; i < sampleCount; i++) {
            double t = i / (double) SAMPLE_RATE;
            double value = clickBurst(t, 0.000, 1.00)
                    + clickBurst(t, 0.058, 0.58)
                    + clickBurst(t, 0.086, 0.24);
            value = Math.max(-1.0, Math.min(1.0, value));
            short s = (short) Math.round(value * 32767.0 * 0.88);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
        }

        int dataSize = pcm.length;
        int byteRate = SAMPLE_RATE * 2;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{'R','I','F','F'});
            writeIntLE(out, 36 + dataSize);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '});
            writeIntLE(out, 16);
            writeShortLE(out, 1);
            writeShortLE(out, 1);
            writeIntLE(out, SAMPLE_RATE);
            writeIntLE(out, byteRate);
            writeShortLE(out, 2);
            writeShortLE(out, 16);
            out.write(new byte[]{'d','a','t','a'});
            writeIntLE(out, dataSize);
            out.write(pcm);
        }
    }

    private static double clickBurst(double t, double start, double amount) {
        double x = t - start;
        if (x < 0.0 || x > 0.038) return 0.0;
        double envelope = Math.exp(-x * 92.0);
        double body = Math.sin(2.0 * Math.PI * 1550.0 * x)
                + 0.52 * Math.sin(2.0 * Math.PI * 2780.0 * x)
                + 0.26 * Math.sin(2.0 * Math.PI * 4870.0 * x);
        double textured = 0.78 * body + 0.22 * Math.sin((x * 19000.0 + 0.7) * (x * 5100.0 + 1.3));
        return amount * envelope * textured * 0.48;
    }

    private static void writeIntLE(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }
}
