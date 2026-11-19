package com.cue.micvoicechanger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.cue.micvoicechanger.dsp.RealtimeVoiceProcessor;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 41;
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 1024;

    private interface ProgressCallback {
        void onProgressChanged(int progress);
    }

    private TextView statusView;
    private TextView pitchValue;
    private TextView timbreValue;
    private TextView gainValue;
    private SeekBar pitchSeek;
    private SeekBar timbreSeek;
    private SeekBar gainSeek;
    private Button startButton;
    private Button stopButton;

    private volatile boolean running;
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread audioThread;
    private RealtimeVoiceProcessor processor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        setStatus("جاهز. لن يبدأ التقاط الصوت إلا بعد الضغط على زر البدء ومنح الإذن.");
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(24), dp(22), dp(28));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(page);

        TextView title = text("مبدّل الصوت المحلي", 26, true);
        page.addView(title, matchWrap());
        TextView intro = text("اختبر مؤثرات صوتية مباشرة على هذا الجهاز. الإذن يُطلب عند بدء الاختبار فقط.", 16, false);
        addGap(page, 8);
        page.addView(intro, matchWrap());

        TextView warning = text("للاختبار داخل التطبيق فقط — لا يغيّر صوت WhatsApp أو أي تطبيق آخر. استخدم سماعات لتقليل الصدى.", 15, true);
        warning.setPadding(dp(12), dp(12), dp(12), dp(12));
        warning.setBackgroundColor(0xFFEAF2FF);
        addGap(page, 14);
        page.addView(warning, matchWrap());

        addGap(page, 18);
        page.addView(text("إعدادات سريعة", 18, true), matchWrap());
        LinearLayout presetRowOne = row(page);
        addPresetButton(presetRowOne, "طبيعي", 0.0f, 0.0f, 1.0f);
        addPresetButton(presetRowOne, "عميق", -4.0f, -0.25f, 1.0f);
        LinearLayout presetRowTwo = row(page);
        addPresetButton(presetRowTwo, "لامع", 4.0f, 0.55f, 1.0f);
        addPresetButton(presetRowTwo, "آلي", 7.0f, 0.85f, 0.9f);

        addGap(page, 12);
        pitchValue = text("النغمة: 0 نصف نغمة", 16, true);
        page.addView(pitchValue, matchWrap());
        pitchSeek = addSlider(page, 24, 12, progress -> {
            float value = progress - 12;
            pitchValue.setText("النغمة: " + formatSigned(value) + " نصف نغمة");
            updateProcessorControls();
        });

        timbreValue = text("لون الصوت: 0%", 16, true);
        addGap(page, 10);
        page.addView(timbreValue, matchWrap());
        timbreSeek = addSlider(page, 40, 20, progress -> {
            int percent = (progress - 20) * 5;
            timbreValue.setText("لون الصوت: " + formatSigned(percent) + "%");
            updateProcessorControls();
        });

        gainValue = text("مستوى الخرج: 100%", 16, true);
        addGap(page, 10);
        page.addView(gainValue, matchWrap());
        gainSeek = addSlider(page, 150, 100, progress -> {
            gainValue.setText("مستوى الخرج: " + progress + "%");
            updateProcessorControls();
        });

        addGap(page, 14);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        startButton = new Button(this);
        startButton.setText("بدء الاختبار");
        startButton.setOnClickListener(v -> requestAndStart());
        controls.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        stopButton = new Button(this);
        stopButton.setText("إيقاف");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopAudio("تم إيقاف الاختبار."));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        stopParams.setMarginStart(dp(8));
        controls.addView(stopButton, stopParams);
        page.addView(controls, matchWrap());

        addGap(page, 10);
        statusView = text("", 14, false);
        page.addView(statusView, matchWrap());
        return scroll;
    }

    private void addPresetButton(LinearLayout row, String label, float pitch, float color, float gain) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> applyPreset(pitch, color, gain));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        row.addView(button, params);
    }

    private void applyPreset(float pitch, float color, float gain) {
        pitchSeek.setProgress(Math.round(pitch + 12.0f));
        timbreSeek.setProgress(Math.round(color * 20.0f + 20.0f));
        gainSeek.setProgress(Math.round(gain * 100.0f));
        updateProcessorControls();
    }

    private void requestAndStart() {
        if (running) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startAudio();
    }

    private void startAudio() {
        if (running) {
            return;
        }
        int inputMin = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int outputMin = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inputMin <= 0 || outputMin <= 0) {
            setStatus("تعذر بدء الصوت: الجهاز لا يقبل إعداد PCM المطلوب.");
            return;
        }

        int inputBufferBytes = Math.max(inputMin, FRAME_SAMPLES * 4);
        int outputBufferBytes = Math.max(outputMin, FRAME_SAMPLES * 4);
        AudioRecord newRecorder = null;
        AudioTrack newPlayer = null;
        try {
            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            newRecorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(inputBufferBytes)
                    .build();
            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            newPlayer = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(outputFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(outputBufferBytes)
                    .build();

            if (newRecorder.getState() != AudioRecord.STATE_INITIALIZED
                    || newPlayer.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("تعذر تهيئة مسار الصوت على هذا الجهاز.");
            }

            recorder = newRecorder;
            player = newPlayer;
            processor = new RealtimeVoiceProcessor(SAMPLE_RATE);
            updateProcessorControls();
            recorder.startRecording();
            player.play();
            running = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            setStatus("يعمل الآن على الجهاز. اضغط إيقاف لإنهاء الالتقاط والتشغيل.");

            final AudioRecord activeRecorder = recorder;
            final AudioTrack activePlayer = player;
            final RealtimeVoiceProcessor activeProcessor = processor;
            audioThread = new Thread(() -> processAudio(activeRecorder, activePlayer, activeProcessor),
                    "LocalVoiceAudio");
            audioThread.start();
        } catch (Exception error) {
            running = false;
            release(newRecorder, newPlayer);
            recorder = null;
            player = null;
            processor = null;
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            setStatus("تعذر بدء الصوت: " + safeMessage(error));
        }
    }

    private void processAudio(AudioRecord source, AudioTrack sink, RealtimeVoiceProcessor dsp) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] input = new short[FRAME_SAMPLES];
        short[] output = new short[FRAME_SAMPLES];
        try {
            while (running) {
                int count = source.read(input, 0, input.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    dsp.process(input, output, count);
                    int written = sink.write(output, 0, count, AudioTrack.WRITE_BLOCKING);
                    if (written < 0 && running) {
                        throw new IllegalStateException("تعذرت كتابة الصوت إلى خرج الجهاز.");
                    }
                } else if (count < 0 && running) {
                    throw new IllegalStateException("انقطع التقاط الصوت (" + count + ").");
                }
            }
        } catch (Exception error) {
            if (running) {
                running = false;
                final String message = safeMessage(error);
                runOnUiThread(() -> {
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    setStatus("توقف مسار الصوت: " + message);
                    release(recorder, player);
                    recorder = null;
                    player = null;
                    processor = null;
                });
            }
        }
    }

    private void stopAudio(String message) {
        running = false;
        AudioRecord oldRecorder = recorder;
        AudioTrack oldPlayer = player;
        Thread oldThread = audioThread;
        recorder = null;
        player = null;
        audioThread = null;
        processor = null;
        if (oldRecorder != null) {
            try { oldRecorder.stop(); } catch (IllegalStateException ignored) { }
        }
        if (oldPlayer != null) {
            try { oldPlayer.stop(); } catch (IllegalStateException ignored) { }
        }
        if (oldThread != null && oldThread != Thread.currentThread()) {
            try { oldThread.join(800); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        release(oldRecorder, oldPlayer);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        setStatus(message);
    }

    private void release(AudioRecord source, AudioTrack sink) {
        if (source != null) {
            try { source.release(); } catch (Exception ignored) { }
        }
        if (sink != null) {
            try { sink.release(); } catch (Exception ignored) { }
        }
    }

    private void updateProcessorControls() {
        if (processor == null) {
            return;
        }
        processor.setPitchSemitones(pitchSeek.getProgress() - 12.0f);
        processor.setTimbre((timbreSeek.getProgress() - 20) / 20.0f);
        processor.setOutputGain(gainSeek.getProgress() / 100.0f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudio();
            } else {
                setStatus("لم يُمنح إذن الميكروفون؛ لم يبدأ أي التقاط للصوت.");
            }
        }
    }

    @Override
    protected void onPause() {
        if (running) {
            stopAudio("أُوقف الاختبار عند مغادرة الشاشة.");
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (running || recorder != null || player != null) {
            stopAudio("تم إيقاف الاختبار.");
        }
        super.onDestroy();
    }

    private LinearLayout row(ViewGroup parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        parent.addView(row, matchWrap());
        return row;
    }

    private SeekBar addSlider(LinearLayout parent, int max, int initial, ProgressCallback callback) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                callback.onProgressChanged(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(2);
        parent.addView(bar, params);
        return bar;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(0xFF172033);
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return view;
    }

    private void setStatus(String message) {
        if (statusView != null) {
            statusView.setText(message);
        }
    }

    private String formatSigned(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addGap(LinearLayout parent, int heightDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
