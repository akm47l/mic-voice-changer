package com.cue.micvoicechanger.dsp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RealtimeVoiceProcessorTest {
    @Test
    public void pitchRatiosMatchOctaveAndClampLimits() {
        assertEquals(2.0f, RealtimeVoiceProcessor.pitchRatioForSemitones(12.0f), 0.0001f);
        assertEquals(0.5f, RealtimeVoiceProcessor.pitchRatioForSemitones(-12.0f), 0.0001f);
        assertEquals(2.0f, RealtimeVoiceProcessor.pitchRatioForSemitones(30.0f), 0.0001f);
    }

    @Test
    public void neutralSettingsPassPcmThroughExactlyAcrossChunks() {
        RealtimeVoiceProcessor processor = new RealtimeVoiceProcessor(48000);
        short[] input = new short[] {0, 1200, -1200, 32767, -32768, 42};
        short[] output = new short[input.length];

        processor.process(input, output, 3);
        processor.process(input, output, input.length);

        for (int i = 0; i < input.length; i++) {
            assertEquals("sample " + i, input[i], output[i]);
        }
    }

    @Test
    public void silenceRemainsSilentAtAllPitchSettings() {
        RealtimeVoiceProcessor processor = new RealtimeVoiceProcessor(48000);
        processor.setPitchSemitones(7.0f);
        processor.setTimbre(0.7f);
        short[] input = new short[2048];
        short[] output = new short[input.length];

        processor.process(input, output, input.length);

        for (short sample : output) {
            assertEquals(0, sample);
        }
    }

    @Test
    public void outputClipsAtPcm16Limits() {
        RealtimeVoiceProcessor positive = new RealtimeVoiceProcessor(48000);
        positive.setOutputGain(1.5f);
        short[] positiveInput = new short[512];
        short[] positiveOutput = new short[positiveInput.length];
        java.util.Arrays.fill(positiveInput, Short.MAX_VALUE);
        positive.process(positiveInput, positiveOutput, positiveInput.length);
        assertEquals(Short.MAX_VALUE, positiveOutput[positiveOutput.length - 1]);

        RealtimeVoiceProcessor negative = new RealtimeVoiceProcessor(48000);
        negative.setOutputGain(1.5f);
        short[] negativeInput = new short[512];
        short[] negativeOutput = new short[negativeInput.length];
        java.util.Arrays.fill(negativeInput, Short.MIN_VALUE);
        negative.process(negativeInput, negativeOutput, negativeInput.length);
        assertEquals(Short.MIN_VALUE, negativeOutput[negativeOutput.length - 1]);
    }

    @Test
    public void octavePitchShiftRaises440HzToneApproximatelyOneOctave() {
        int sampleRate = 48000;
        int totalSamples = sampleRate * 2;
        short[] inputFrame = new short[1024];
        short[] outputFrame = new short[inputFrame.length];
        short[] outputSignal = new short[totalSamples];
        RealtimeVoiceProcessor processor = new RealtimeVoiceProcessor(sampleRate);
        processor.setPitchSemitones(12.0f);

        for (int base = 0; base < totalSamples; base += inputFrame.length) {
            int count = Math.min(inputFrame.length, totalSamples - base);
            for (int i = 0; i < count; i++) {
                inputFrame[i] = (short) (Math.sin(2.0 * Math.PI * 440.0 * (base + i) / sampleRate) * 12000);
            }
            processor.process(inputFrame, outputFrame, count);
            System.arraycopy(outputFrame, 0, outputSignal, base, count);
        }

        int crossings = 0;
        int start = sampleRate / 2;
        int end = sampleRate * 3 / 2;
        for (int i = start + 1; i < end; i++) {
            if (outputSignal[i - 1] <= 0 && outputSignal[i] > 0) {
                crossings++;
            }
        }
        double measuredHz = crossings / ((end - start) / (double) sampleRate);
        assertTrue("Expected near 880 Hz after one octave; measured " + measuredHz,
                measuredHz > 700.0 && measuredHz < 1050.0);
    }
}
