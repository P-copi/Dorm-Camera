package com.pcopi.dormcamera;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * App-controlled natural shutter click routed through STREAM_MUSIC.
 * V0.1.28 replaces the tonal/electronic click with a softer mechanical
 * click-clack while preserving the clearly separated volume levels.
 */
public final class ShutterClickPlayer {
    private static final int SAMPLE_RATE = 44100;
    private static final int DURATION_MS = 170;

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

        // New cache name forces regeneration after updating from V0.1.27.
        File wave = new File(this.context.getCacheDir(), "dorm_camera_shutter_v128.wav");
        try {
            if (!wave.exists() || wave.length() < 1024L) writeWave(wave);
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
        // Keep the proven V0.1.27 perceptual separation between low/mid/high levels.
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

        int noiseState = 0x13572468;
        double lowNoise = 0.0;

        for (int i = 0; i < sampleCount; i++) {
            double t = i / (double) SAMPLE_RATE;

            // Deterministic filtered noise gives a mechanical texture instead of an electronic tone.
            noiseState = noiseState * 1664525 + 1013904223;
            double white = (((noiseState >>> 8) & 0xFFFF) / 32767.5) - 1.0;
            lowNoise = 0.82 * lowNoise + 0.18 * white;
            double softNoise = 0.78 * lowNoise + 0.22 * white;

            double open = transientEnvelope(t, 0.000, 0.030, 72.0);
            double close = transientEnvelope(t, 0.058, 0.040, 58.0);
            double settle = transientEnvelope(t, 0.105, 0.035, 46.0);

            // Warm mechanical body: intentionally below the harsh high-frequency range.
            double bodyOpen = dampedTone(t, 0.000, 690.0, 54.0)
                    + 0.40 * dampedTone(t, 0.000, 1120.0, 70.0);
            double bodyClose = 0.72 * dampedTone(t, 0.058, 610.0, 48.0)
                    + 0.30 * dampedTone(t, 0.058, 980.0, 62.0);
            double bodySettle = 0.18 * dampedTone(t, 0.105, 430.0, 40.0);

            double texture = softNoise * (0.54 * open + 0.42 * close + 0.12 * settle);
            double value = texture + 0.28 * bodyOpen + 0.24 * bodyClose + bodySettle;

            // Soft saturation rounds the transient so it sounds less sharp on phone speakers.
            value = Math.tanh(value * 1.35) * 0.72;
            short s = (short) Math.round(value * 32767.0);
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

    private static double transientEnvelope(double t, double start, double duration, double decay) {
        double x = t - start;
        if (x < 0.0 || x > duration) return 0.0;
        double attack = Math.min(1.0, x / 0.0015);
        return attack * Math.exp(-x * decay);
    }

    private static double dampedTone(double t, double start, double frequency, double decay) {
        double x = t - start;
        if (x < 0.0 || x > 0.075) return 0.0;
        return Math.sin(2.0 * Math.PI * frequency * x) * Math.exp(-x * decay);
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
