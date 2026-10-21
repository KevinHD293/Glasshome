package com.local.glasshome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Tiny UI sounds synthesised in code (no audio files needed). */
final class Sfx {
    private static final int RATE = 22050;

    private final SoundPool pool;
    private final int sMove, sOpen, sPick, sDrop;
    private boolean enabled = true;
    private float vol = 0.5f;
    private long lastMove = 0;

    Sfx(Context c, boolean enabled, float vol) {
        configure(enabled, vol);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(aa).build();
        sMove = load(c, "move", wav(26, 0.5f, 2000f));
        sOpen = load(c, "open", wav(110, 0.6f, 880f, 1320f));
        sPick = load(c, "pick", wav(70, 0.6f, 700f, 1000f));
        sDrop = load(c, "drop", wav(70, 0.6f, 900f, 600f));
    }

    void configure(boolean on, float v) {
        enabled = on;
        vol = Math.max(0f, Math.min(1f, v));
    }

    void move() {
        long now = System.nanoTime();
        if (now - lastMove < 35000000L) return;   // fast scrolling: at most one tick per 35 ms
        lastMove = now;
        play(sMove);
    }

    void open() { play(sOpen); }
    void pick() { play(sPick); }
    void drop() { play(sDrop); }

    void release() { pool.release(); }

    private void play(int id) {
        if (enabled && id != 0) pool.play(id, vol, vol, 1, 0, 1f);
    }

    private int load(Context c, String name, byte[] data) {
        try {
            File f = new File(c.getCacheDir(), "sfx_" + name + ".wav");
            FileOutputStream o = new FileOutputStream(f);
            o.write(data);
            o.close();
            return pool.load(f.getAbsolutePath(), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 16-bit mono WAV: a soft sine with a quick attack and exponential decay; several freqs = a short glide. */
    private static byte[] wav(int ms, float amp, float... freqs) {
        int n = RATE * ms / 1000;
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] { 'R', 'I', 'F', 'F' });
        b.putInt(36 + n * 2);
        b.put(new byte[] { 'W', 'A', 'V', 'E' });
        b.put(new byte[] { 'f', 'm', 't', ' ' });
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(RATE);
        b.putInt(RATE * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put(new byte[] { 'd', 'a', 't', 'a' });
        b.putInt(n * 2);
        double dur = ms / 1000.0;
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            float f = freqs[Math.min(freqs.length - 1, i * freqs.length / n)];
            phase += 2 * Math.PI * f / RATE;
            double env = Math.exp(-5.0 * t / dur) * Math.min(1.0, t / 0.003);
            b.putShort((short) (Math.sin(phase) * env * amp * 32767));
        }
        return b.array();
    }
}
