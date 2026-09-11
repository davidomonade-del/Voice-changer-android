package com.example.voicechanger;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.Arrays;

public class VoiceEngine {

    public interface Listener {
        void onError(String msg);
    }

    public static final int EFFECT_DEEP     = 0;
    public static final int EFFECT_FEMALE   = 1;
    public static final int EFFECT_MALE     = 2;
    public static final int EFFECT_CHIPMUNK = 3;
    public static final int EFFECT_ROBOT    = 4;
    public static final int EFFECT_ECHO     = 5;
    public static final int EFFECT_PHONE    = 6;
    public static final int EFFECT_CAVE     = 7;
    public static final int EFFECT_NORMAL   = 8;

    private static final int SAMPLE_RATE = 44100;
    private static final int FRAME = 1024;
    private static final int RING_LEN = 8192;
    private static final int ECHO_LEN = SAMPLE_RATE;

    private final Listener listener;

    private volatile int effect = EFFECT_DEEP;
    private volatile boolean resetFlag = true;
    private volatile boolean running = false;
    private Thread worker;
    private AudioRecord record;
    private AudioTrack track;

    private final short[] inBuf  = new short[FRAME];
    private final short[] outBuf = new short[FRAME];

    private float grain = 1600f;
    private final float[] ring = new float[RING_LEN];
    private int ringW = 0;
    private double phase = 0.0;

    private double rmPhase = 0.0;
    private double rmInc = 0.0;

    private final float[] echo = new float[ECHO_LEN];
    private int echoPos = 0;

    private final Biquad hp1 = new Biquad();
    private final Biquad lp1 = new Biquad();
    private final Biquad lp2 = new Biquad();
    private final Biquad shelfHigh = new Biquad();
    private final Biquad shelfLow  = new Biquad();

    public VoiceEngine(Listener listener) {
        this.listener = listener;
    }

    public void setEffect(int e) {
        effect = e;
        resetFlag = true;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() {
        if (running) return;
        try {
            int recMin = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int trkMin = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (recMin <= 0 || trkMin <= 0) {
                fail("Audio buffers not supported on this device");
                return;
            }

            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(recMin, FRAME * 4) * 2)
                    .build();

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(trkMin, FRAME * 4) * 2)
                    .build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED
                    || track.getState() != AudioTrack.STATE_INITIALIZED) {
                fail("Could not initialize mic or speaker");
                release();
                return;
            }

            resetFlag = true;
            running = true;
            record.startRecording();
            track.play();
            worker = new Thread(this::loop, "VoiceEngine");
            worker.setPriority(Thread.MAX_PRIORITY);
            worker.start();
        } catch (Exception e) {
            running = false;
            fail("Start failed: " + e.getMessage());
            release();
        }
    }

    public synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        release();
    }

    private void loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (running) {
            AudioRecord rec = record;
            AudioTrack trk = track;
            if (rec == null || trk == null) break;
            if (resetFlag) {
                resetDsp();
                resetFlag = false;
            }
            int n = rec.read(inBuf, 0, FRAME);
            if (n <= 0) continue;

            int e = effect;
            for (int i = 0; i < n; i++) {
                float x = inBuf[i] / 32768f;
                float y;
                switch (e) {
                    case EFFECT_DEEP:
                        y = lp2.process(fxPitch(x, 0.65f)) * 1.2f;
                        break;
                    case EFFECT_FEMALE:
                        y = shelfHigh.process(lp1.process(fxPitch(x, 1.35f)));
                        break;
                    case EFFECT_MALE:
                        y = shelfHigh.process(shelfLow.process(hp1.process(fxPitch(x, 0.76f))));
                        break;
                    case EFFECT_CHIPMUNK:
                        y = fxPitch(x, 1.6f);
                        break;
                    case EFFECT_ROBOT:
                        y = fxRobot(x);
                        break;
                    case EFFECT_ECHO:
                        y = fxEcho(x, 0.22f, 0.35f, 0.55f);
                        break;
                    case EFFECT_PHONE:
                        y = fxPhone(x);
                        break;
                    case EFFECT_CAVE:
                        y = fxEcho(fxPitch(x, 0.9f), 0.4f, 0.5f, 0.8f);
                        break;
                    default:
                        y = x;
                }
                if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
                outBuf[i] = (short) (y * 32000f);
            }
            trk.write(outBuf, 0, n, AudioTrack.WRITE_BLOCKING);
        }
    }

    private float fxPitch(float x, float pitch) {
        ring[ringW] = x;
        double d1 = grain * phase;
        double p2 = phase + 0.5;
        if (p2 >= 1.0) p2 -= 1.0;
        double d2 = grain * p2;
        float s1 = readRing(ringW - d1);
        float s2 = readRing(ringW - d2);
        float w1 = (float) Math.sin(Math.PI * phase);
        float w2 = (float) Math.sin(Math.PI * p2);
        ringW = (ringW + 1) % RING_LEN;
        phase += (1.0 - pitch) / grain;
        if (phase >= 1.0) phase -= 1.0;
        if (phase < 0.0) phase += 1.0;
        return (s1 * w1 + s2 * w2) * 0.9f;
    }

    private float readRing(double pos) {
        int i0 = (int) Math.floor(pos);
        float frac = (float) (pos - i0);
        i0 = mod(i0, RING_LEN);
        int i1 = (i0 + 1) % RING_LEN;
        return ring[i0] * (1f - frac) + ring[i1] * frac;
    }

    private static int mod(int a, int n) {
        int r = a % n;
        return r < 0 ? r + n : r;
    }

    private float fxRobot(float x) {
        float carrier = (float) Math.sin(rmPhase);
        rmPhase += rmInc;
        if (rmPhase > 2.0 * Math.PI) rmPhase -= 2.0 * Math.PI;
        return lp1.process(x * carrier * 0.9f);
    }

    private float fxEcho(float x, float delaySec, float feedback, float wet) {
        int delaySmp = (int) (delaySec * SAMPLE_RATE);
        int readPos = echoPos - delaySmp;
        if (readPos < 0) readPos += ECHO_LEN;
        float d = echo[readPos];
        echo[echoPos] = x + d * feedback;
        echoPos = (echoPos + 1) % ECHO_LEN;
        return (x + d * wet) * 0.8f;
    }

    private float fxPhone(float x) {
        float y = hp1.process(x);
        y = lp1.process(y);
        y *= 1.4f;
        if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
        return y;
    }

    private void resetDsp() {
        Arrays.fill(ring, 0f);
        Arrays.fill(echo, 0f);
        ringW = 0;
        phase = 0.0;
        rmPhase = 0.0;
        rmInc = 2.0 * Math.PI * 35.0 / SAMPLE_RATE;

        switch (effect) {
            case EFFECT_FEMALE:
                grain = 2400f;
                lp1.setLowPass(SAMPLE_RATE, 6500, 0.7);
                shelfHigh.setHighShelf(SAMPLE_RATE, 4200, 4.0);
                break;
            case EFFECT_MALE:
                grain = 2400f;
                hp1.setHighPass(SAMPLE_RATE, 90, 0.7);
                shelfLow.setLowShelf(SAMPLE_RATE, 220, 2.5);
                shelfHigh.setHighShelf(SAMPLE_RATE, 3200, -2.0);
                break;
            default:
                grain = 1600f;
                lp1.setLowPass(SAMPLE_RATE, 3200, 0.71);
                lp2.setLowPass(SAMPLE_RATE, 2000, 0.71);
                hp1.setHighPass(SAMPLE_RATE, 300, 0.71);
                break;
        }
    }

    private void fail(String msg) {
        if (listener != null) listener.onError(msg);
    }

    private void release() {
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
            try { record.release(); } catch (Exception ignored) { }
            record = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { }
            track = null;
        }
    }

    private static class Biquad {
        private double b0, b1, b2, a1, a2, x1, x2, y1, y2;

        void setLowPass(double fs, double f0, double q) {
            double w = 2 * Math.PI * f0 / fs, cw = Math.cos(w), s = Math.sin(w), al = s / (2 * q);
            double a0 = 1 + al;
            b0 = (1 - cw) / 2 / a0;  b1 = (1 - cw) / a0;  b2 = b0;
            a1 = -2 * cw / a0;       a2 = (1 - al) / a0;
            clear();
        }

        void setHighPass(double fs, double f0, double q) {
            double w = 2 * Math.PI * f0 / fs, cw = Math.cos(w), s = Math.sin(w), al = s / (2 * q);
            double a0 = 1 + al;
            b0 = (1 + cw) / 2 / a0;  b1 = -(1 + cw) / a0;  b2 = b0;
            a1 = -2 * cw / a0;       a2 = (1 - al) / a0;
            clear();
        }

        void setLowShelf(double fs, double f0, double gainDb) {
            double A = Math.pow(10, gainDb / 40.0);
            double w = 2 * Math.PI * f0 / fs, cw = Math.cos(w), s = Math.sin(w);
            double sqA = Math.sqrt(A), al = s / 2 * Math.sqrt(2.0);
            double a0 = (A + 1) + (A - 1) * cw + 2 * sqA * al;
            b0 =  A * ((A + 1) - (A - 1) * cw + 2 * sqA * al) / a0;
            b1 =  2 * A * ((A - 1) - (A + 1) * cw) / a0;
            b2 =  A * ((A + 1) - (A - 1) * cw - 2 * sqA * al) / a0;
            a1 = -2 * ((A - 1) + (A + 1) * cw) / a0;
            a2 = ((A + 1) + (A - 1) * cw - 2 * sqA * al) / a0;
            clear();
        }

        void setHighShelf(double fs, double f0, double gainDb) {
            double A = Math.pow(10, gainDb / 40.0);
            double w = 2 * Math.PI * f0 / fs, cw = Math.cos(w), s = Math.sin(w);
            double sqA = Math.sqrt(A), al = s / 2 * Math.sqrt(2.0);
            double a0 = (A + 1) - (A - 1) * cw + 2 * sqA * al;
            b0 =  A * ((A + 1) + (A - 1) * cw + 2 * sqA * al) / a0;
            b1 = -2 * A * ((A - 1) + (A + 1) * cw) / a0;
            b2 =  A * ((A + 1) + (A - 1) * cw - 2 * sqA * al) / a0;
            a1 =  2 * ((A - 1) - (A + 1) * cw) / a0;
            a2 = ((A + 1) - (A - 1) * cw - 2 * sqA * al) / a0;
            clear();
        }

        void clear() { x1 = x2 = y1 = y2 = 0; }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }
  }
