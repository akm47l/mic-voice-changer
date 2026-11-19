package com.cue.micvoicechanger.dsp;

/**
 * Small streaming PCM processor. It keeps all state in memory and performs no I/O.
 * Pitch is shifted with two cross-faded variable-delay read heads; timbre is a
 * simple high-frequency tilt, suitable for a lightweight prototype rather than
 * studio-quality voice conversion.
 */
public final class RealtimeVoiceProcessor {
    private final float[] delayLine;
    private final float minimumDelaySamples;
    private final float delaySpanSamples;
    private final float lowPassAlpha;

    private int writeIndex;
    private float phase;
    private float lowPassState;

    private volatile float pitchSemitones;
    private volatile float timbre;
    private volatile float outputGain = 1.0f;

    public RealtimeVoiceProcessor(int sampleRate) {
        if (sampleRate < 8000) {
            throw new IllegalArgumentException("sampleRate must be at least 8000 Hz");
        }
        this.minimumDelaySamples = sampleRate * 0.020f;
        this.delaySpanSamples = sampleRate * 0.080f;
        this.delayLine = new float[Math.max(2048, (int) (sampleRate * 0.250f) + 8)];
        this.lowPassAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * 1400.0 / sampleRate));
    }

    public void setPitchSemitones(float semitones) {
        pitchSemitones = clamp(semitones, -12.0f, 12.0f);
    }

    public void setTimbre(float amount) {
        timbre = clamp(amount, -1.0f, 1.0f);
    }

    public void setOutputGain(float gain) {
        outputGain = clamp(gain, 0.0f, 1.5f);
    }

    public static float pitchRatioForSemitones(float semitones) {
        float bounded = clamp(semitones, -12.0f, 12.0f);
        return (float) Math.pow(2.0, bounded / 12.0);
    }

    /** Processes {@code count} mono PCM16 samples, preserving state between calls. */
    public void process(short[] input, short[] output, int count) {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output must not be null");
        }
        if (count < 0 || count > input.length || count > output.length) {
            throw new IllegalArgumentException("count must fit both buffers");
        }

        float semitones = pitchSemitones;
        float color = timbre;
        float gain = outputGain;
        float ratio = pitchRatioForSemitones(semitones);
        boolean pitchEnabled = Math.abs(semitones) >= 0.001f;
        float phaseStep = pitchEnabled ? (1.0f - ratio) / delaySpanSamples : 0.0f;

        for (int i = 0; i < count; i++) {
            float dry = input[i] / 32768.0f;
            delayLine[writeIndex] = dry;

            float shifted = dry;
            if (pitchEnabled) {
                phase = wrapUnit(phase + phaseStep);
                float phaseB = wrapUnit(phase + 0.5f);
                float sampleA = readDelayed(minimumDelaySamples + phase * delaySpanSamples);
                float sampleB = readDelayed(minimumDelaySamples + phaseB * delaySpanSamples);
                float weightA = triangleWindow(phase);
                float weightB = triangleWindow(phaseB);
                float weightSum = weightA + weightB;
                shifted = weightSum > 0.0001f
                        ? (sampleA * weightA + sampleB * weightB) / weightSum
                        : sampleA;
            }

            lowPassState += lowPassAlpha * (shifted - lowPassState);
            float highBand = shifted - lowPassState;
            float colored = shifted + color * 1.25f * highBand;
            if (!pitchEnabled && color == 0.0f && gain == 1.0f) {
                output[i] = input[i];
            } else {
                float outputSample = clamp(colored * gain, -1.0f, 1.0f);
                output[i] = outputSample <= -1.0f
                        ? Short.MIN_VALUE
                        : (short) Math.round(outputSample * Short.MAX_VALUE);
            }

            writeIndex++;
            if (writeIndex == delayLine.length) {
                writeIndex = 0;
            }
        }
    }

    private float readDelayed(float delaySamples) {
        float position = writeIndex - delaySamples;
        while (position < 0.0f) {
            position += delayLine.length;
        }
        while (position >= delayLine.length) {
            position -= delayLine.length;
        }
        int first = (int) position;
        int second = first + 1;
        if (second == delayLine.length) {
            second = 0;
        }
        float fraction = position - first;
        return delayLine[first] + (delayLine[second] - delayLine[first]) * fraction;
    }

    private static float triangleWindow(float p) {
        return 1.0f - Math.abs(2.0f * p - 1.0f);
    }

    private static float wrapUnit(float value) {
        value %= 1.0f;
        return value < 0.0f ? value + 1.0f : value;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
