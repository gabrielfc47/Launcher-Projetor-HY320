package com.gabriel.projectorhome;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Serviço global de barra de volume flutuante (substitui o círculo branco antigo da fábrica
 * em todos os aplicativos: Home, YouTube, Netflix, XCIPTV, HDMI, etc.).
 */
public class VolumeOverlayService extends Service {

    public static final String ACTION_SHOW_VOLUME = "com.gabriel.projectorhome.ACTION_SHOW_VOLUME";
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String MASTER_MUTE_CHANGED_ACTION = "android.media.MASTER_MUTE_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private static final long AUTO_HIDE_DELAY_MS = 2300L;

    private WindowManager windowManager;
    private AudioManager audioManager;
    private VolumeHudView hudView;
    private WindowManager.LayoutParams overlayParams;
    private boolean isAddedToWindow = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SoundPool volSoundPool;
    private int volPopSoundId = 0;
    private boolean volSoundLoaded = false;
    private long lastSoundTimeMs = 0L;
    private int lastKnownVolume = -1;
    private boolean lastKnownMuted = false;

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            if (hudView != null && isAddedToWindow) {
                hudView.startHideAnimation();
            }
        }
    };

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (VOLUME_CHANGED_ACTION.equals(action)) {
                int streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
                if (streamType == AudioManager.STREAM_MUSIC || streamType == AudioManager.STREAM_SYSTEM) {
                    onSystemVolumeUpdated(true);
                }
            } else if (STREAM_MUTE_CHANGED_ACTION.equals(action) || MASTER_MUTE_CHANGED_ACTION.equals(action)) {
                onSystemVolumeUpdated(true);
            }
        }
    };

    public static void ensureStarted(Context context) {
        try {
            disableFactoryVolumeService(context);
            Intent i = new Intent(context, VolumeOverlayService.class);
            context.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void triggerVolumePopup(Context context) {
        try {
            Intent i = new Intent(context, VolumeOverlayService.class);
            i.setAction(ACTION_SHOW_VOLUME);
            context.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void disableFactoryVolumeService(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName factoryVol = new ComponentName("com.droidlogic", "com.droidlogic.tvvolume.VolumeService");
            pm.setComponentEnabledSetting(
                    factoryVol,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        disableFactoryVolumeService(this);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        initVolumeSoundPool();

        if (audioManager != null) {
            lastKnownVolume = getNormalizedVolume();
            lastKnownMuted = isMusicMuted();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(VOLUME_CHANGED_ACTION);
        filter.addAction(STREAM_MUTE_CHANGED_ACTION);
        filter.addAction(MASTER_MUTE_CHANGED_ACTION);
        registerReceiver(volumeReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String act = intent.getAction();
            if (ACTION_SHOW_VOLUME.equals(act) || "com.droidlogic.tvvolume.VolumeService".equals(act)) {
                onSystemVolumeUpdated(true);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(volumeReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (isAddedToWindow && windowManager != null && hudView != null) {
                windowManager.removeViewImmediate(hudView);
                isAddedToWindow = false;
            }
        } catch (Exception ignored) {
        }
        try {
            if (volSoundPool != null) {
                volSoundPool.release();
                volSoundPool = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int getNormalizedVolume() {
        if (audioManager == null) return 50;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return 0;
            return Math.max(0, Math.min(100, Math.round((cur * 100f) / max)));
        } catch (Exception e) {
            return 50;
        }
    }

    private boolean isMusicMuted() {
        if (audioManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                        || audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
            }
            return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBluetoothOutputActive() {
        if (audioManager == null) return false;
        try {
            return audioManager.isBluetoothA2dpOn() || audioManager.isBluetoothScoOn();
        } catch (Exception e) {
            return false;
        }
    }

    private void onSystemVolumeUpdated(boolean playFeedbackSound) {
        final int newVol = getNormalizedVolume();
        final boolean newMuted = isMusicMuted();
        final boolean btActive = isBluetoothOutputActive();

        boolean changed = (newVol != lastKnownVolume) || (newMuted != lastKnownMuted);
        lastKnownVolume = newVol;
        lastKnownMuted = newMuted;

        if (playFeedbackSound && !newMuted) {
            playDynamicPitchPop(newVol);
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                showOrUpdateHud(newVol, newMuted, btActive);
            }
        });
    }

    private void showOrUpdateHud(int volumePercent, boolean muted, boolean btActive) {
        if (windowManager == null) return;

        if (hudView == null) {
            hudView = new VolumeHudView(this);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            overlayParams = new WindowManager.LayoutParams(
                    dp(440),
                    dp(82),
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
            );
            overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            overlayParams.y = dp(42);
        }

        if (!isAddedToWindow) {
            try {
                windowManager.addView(hudView, overlayParams);
                isAddedToWindow = true;
            } catch (Exception ignored) {
            }
        }

        hudView.updateVolumeState(volumePercent, muted, btActive);

        mainHandler.removeCallbacks(hideRunnable);
        mainHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS);
    }

    private void initVolumeSoundPool() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int sampleRate = 44100;
                    int durationMs = 20;
                    int numSamples = (sampleRate * durationMs) / 1000;
                    byte[] pcmBytes = new byte[numSamples * 2];
                    double phase1 = 0.0;
                    double phase2 = 0.0;
                    for (int i = 0; i < numSamples; i++) {
                        double progress = (double) i / numSamples;
                        // Som cristalino estilo marimba/gota suave (harmônico puro que muda de tom com o volume)
                        double f1 = 580.0 + (80.0 * (1.0 - progress));
                        double f2 = f1 * 1.5;
                        phase1 += (2.0 * Math.PI * f1) / sampleRate;
                        phase2 += (2.0 * Math.PI * f2) / sampleRate;
                        double attack = Math.min(1.0, i / (numSamples * 0.08));
                        double decay = Math.exp(-progress * 5.8);
                        double env = Math.sin(Math.PI * 0.5 * attack) * decay;
                        double sample = (Math.sin(phase1) * 0.78 + Math.sin(phase2) * 0.22) * env;
                        short val = (short) (sample * 13500);
                        pcmBytes[i * 2] = (byte) (val & 0xff);
                        pcmBytes[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
                    }

                    File wavFile = new File(getCacheDir(), "vol_dynamic_pop.wav");
                    writeWavHeaderAndData(wavFile, pcmBytes, sampleRate);

                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    final SoundPool pool = new SoundPool.Builder()
                            .setMaxStreams(1)
                            .setAudioAttributes(attrs)
                            .build();
                    pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                        @Override
                        public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                            if (status == 0) {
                                volSoundLoaded = true;
                            }
                        }
                    });
                    volPopSoundId = pool.load(wavFile.getAbsolutePath(), 1);
                    volSoundPool = pool;
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void playDynamicPitchPop(int volumePercent) {
        long now = System.currentTimeMillis();
        if (now - lastSoundTimeMs < 60) {
            return;
        }
        lastSoundTimeMs = now;
        try {
            if (volSoundPool != null && volSoundLoaded && volPopSoundId != 0) {
                // Tom dinâmico: grave suave em volumes baixos (0.78x) subindo até agudo cristalino em 100% (1.32x)
                float pitchRate = 0.78f + (Math.max(0, Math.min(100, volumePercent)) / 100.0f) * 0.54f;
                volSoundPool.play(volPopSoundId, 0.75f, 0.75f, 1, 0, pitchRate);
            }
        } catch (Exception ignored) {
        }
    }

    private void writeWavHeaderAndData(File file, byte[] pcmData, int sampleRate) throws Exception {
        int totalDataLen = pcmData.length + 36;
        int byteRate = sampleRate * 2;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = 1; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = 2; header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff);
        header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff);
        header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(header);
            fos.write(pcmData);
        } finally {
            fos.close();
        }
    }

    private int dp(float value) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round(value * dm.density);
    }

    /**
     * View customizada via Canvas da Barra de Som Dinâmica.
     */
    private class VolumeHudView extends View {
        private int targetVolume = 50;
        private float animatedVolume = 50f;
        private boolean isMuted = false;
        private boolean isBluetooth = false;
        private float visibilityAlpha = 0f;
        private boolean hiding = false;
        private long lastBumpTimeMs = 0L;

        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF rect = new RectF();
        private final Path path = new Path();

        VolumeHudView(Context context) {
            super(context);
            cardPaint.setStyle(Paint.Style.FILL);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1.8f));

            badgePaint.setStyle(Paint.Style.FILL);

            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);

            trackPaint.setStyle(Paint.Style.FILL);
            fillPaint.setStyle(Paint.Style.FILL);
            thumbPaint.setStyle(Paint.Style.FILL);

            titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titlePaint.setTextSize(dp(13.5f));

            subPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            subPaint.setTextSize(dp(11.5f));
            subPaint.setTextAlign(Paint.Align.RIGHT);

            numPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            numPaint.setTextSize(dp(24f));
            numPaint.setTextAlign(Paint.Align.RIGHT);
        }

        void updateVolumeState(int volume, boolean muted, boolean bt) {
            if (Math.abs(this.animatedVolume - volume) > 40 && visibilityAlpha <= 0.05f) {
                this.animatedVolume = volume;
            }
            this.targetVolume = volume;
            this.isMuted = muted;
            this.isBluetooth = bt;
            this.hiding = false;
            this.lastBumpTimeMs = System.currentTimeMillis();
            setVisibility(VISIBLE);
            invalidate();
        }

        void startHideAnimation() {
            this.hiding = true;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            boolean needsNextFrame = false;

            // 1. Animação suave de entrada/saída (Alpha + leve flutuação vertical)
            if (hiding) {
                visibilityAlpha -= 0.12f;
                if (visibilityAlpha <= 0f) {
                    visibilityAlpha = 0f;
                    setVisibility(GONE);
                    return;
                } else {
                    needsNextFrame = true;
                }
            } else {
                if (visibilityAlpha < 1f) {
                    visibilityAlpha = Math.min(1f, visibilityAlpha + 0.18f);
                    needsNextFrame = true;
                }
            }

            // 2. Interpolação suave da barra de progresso (60fps)
            float diff = targetVolume - animatedVolume;
            if (Math.abs(diff) > 0.15f) {
                animatedVolume += diff * 0.32f;
                needsNextFrame = true;
            } else {
                animatedVolume = targetVolume;
            }

            // 3. Efeito de pulso dinâmico nas ondas sonoras logo após mexer no volume
            long sinceBump = System.currentTimeMillis() - lastBumpTimeMs;
            float wavePulse = 0f;
            if (sinceBump < 360) {
                wavePulse = (float) Math.sin((sinceBump / 360.0) * Math.PI);
                needsNextFrame = true;
            }

            int masterAlpha = Math.round(visibilityAlpha * 255);
            float slideOffsetY = (1f - visibilityAlpha) * dp(12);

            canvas.save();
            canvas.translate(0, slideOffsetY);

            float w = getWidth();
            float h = getHeight();
            float pad = dp(4);

            // Fundo principal glassmorphic escuro (#0F172A)
            rect.set(pad, pad, w - pad, h - pad);
            cardPaint.setColor(Color.argb(Math.round(visibilityAlpha * 242), 15, 23, 42));
            canvas.drawRoundRect(rect, dp(24), dp(24), cardPaint);

            // Borda dinâmica (Vermelho em Mudo, Verde/Dourado em Volume Alto, Azul Ciano no Normal)
            int accentColor;
            if (isMuted || targetVolume == 0) {
                accentColor = Color.argb(masterAlpha, 248, 113, 113); // #F87171
            } else if (targetVolume >= 85) {
                accentColor = Color.argb(masterAlpha, 52, 211, 153); // #34D399
            } else {
                accentColor = Color.argb(masterAlpha, 56, 189, 248); // #38BDF8
            }
            borderPaint.setColor(Color.argb(Math.round(visibilityAlpha * 165),
                    Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            canvas.drawRoundRect(rect, dp(24), dp(24), borderPaint);

            // --- ESQUERDA: Badge e Ícone Dinâmico de Alto-Falante ---
            float badgeSize = dp(48);
            float badgeLeft = dp(18);
            float badgeTop = (h - badgeSize) * 0.5f;
            rect.set(badgeLeft, badgeTop, badgeLeft + badgeSize, badgeTop + badgeSize);
            if (isMuted || targetVolume == 0) {
                badgePaint.setColor(Color.argb(Math.round(visibilityAlpha * 235), 45, 22, 30));
            } else {
                badgePaint.setColor(Color.argb(Math.round(visibilityAlpha * 235), 30, 41, 59));
            }
            canvas.drawRoundRect(rect, dp(16), dp(16), badgePaint);

            drawDynamicSpeakerIcon(
                    canvas,
                    badgeLeft + badgeSize * 0.5f,
                    badgeTop + badgeSize * 0.5f,
                    targetVolume,
                    isMuted,
                    accentColor,
                    wavePulse
            );

            // --- DIREITA: Número grande do Volume (0 a 100) ---
            float numRightX = w - dp(22);
            float numCenterY = h * 0.5f + dp(8.5f);
            if (isMuted || targetVolume == 0) {
                numPaint.setColor(Color.argb(masterAlpha, 248, 113, 113));
                numPaint.setTextSize(dp(18f));
                canvas.drawText("MUDO", numRightX, numCenterY - dp(2), numPaint);
            } else {
                numPaint.setColor(Color.argb(masterAlpha, 248, 250, 252));
                numPaint.setTextSize(dp(25f));
                canvas.drawText(String.valueOf(targetVolume), numRightX, numCenterY, numPaint);
            }

            // --- CENTRO: Textos informativos + Barra de Progresso Animada ---
            float barLeft = badgeLeft + badgeSize + dp(16);
            float barRight = w - dp(86);
            float textBaselineY = h * 0.38f;

            // Título de estado dinâmico
            String statusLabel;
            if (isMuted || targetVolume == 0) {
                statusLabel = "Som Silenciado";
                titlePaint.setColor(Color.argb(masterAlpha, 252, 165, 165));
            } else if (targetVolume == 100) {
                statusLabel = "Volume Máximo";
                titlePaint.setColor(Color.argb(masterAlpha, 110, 231, 183));
            } else if (targetVolume >= 75) {
                statusLabel = "Volume Alto";
                titlePaint.setColor(Color.argb(masterAlpha, 241, 245, 249));
            } else if (targetVolume <= 25) {
                statusLabel = "Volume Suave";
                titlePaint.setColor(Color.argb(masterAlpha, 241, 245, 249));
            } else {
                statusLabel = "Volume";
                titlePaint.setColor(Color.argb(masterAlpha, 241, 245, 249));
            }
            canvas.drawText(statusLabel, barLeft, textBaselineY, titlePaint);

            // Saída de áudio ativa à direita da barra
            String outputLabel = isBluetooth ? "Bluetooth Áudio" : "Alto-falante";
            subPaint.setColor(Color.argb(Math.round(visibilityAlpha * 185), 148, 163, 184));
            canvas.drawText(outputLabel, barRight, textBaselineY, subPaint);

            // Trilho de fundo da barra
            float barTop = h * 0.56f;
            float barHeight = dp(10);
            float barBottom = barTop + barHeight;
            rect.set(barLeft, barTop, barRight, barBottom);
            trackPaint.setColor(Color.argb(Math.round(visibilityAlpha * 235), 30, 41, 59));
            canvas.drawRoundRect(rect, dp(5), dp(5), trackPaint);

            // Preenchimento dinâmico com gradiente
            float fraction = isMuted ? 0f : Math.max(0f, Math.min(1f, animatedVolume / 100f));
            float fillRight = barLeft + (barRight - barLeft) * fraction;
            if (fraction > 0.005f) {
                int cStart = Color.argb(masterAlpha, 2, 132, 199);   // #0284C7
                int cMid = Color.argb(masterAlpha, 56, 189, 248);    // #38BDF8
                int cEnd = targetVolume >= 85
                        ? Color.argb(masterAlpha, 52, 211, 153)      // #34D399 (Verde esmeralda no topo)
                        : Color.argb(masterAlpha, 34, 211, 238);     // #22D3EE (Ciano brilhante)

                LinearGradient grad = new LinearGradient(
                        barLeft, barTop, barRight, barTop,
                        new int[]{cStart, cMid, cEnd},
                        new float[]{0f, 0.6f, 1f},
                        Shader.TileMode.CLAMP
                );
                fillPaint.setShader(grad);
                rect.set(barLeft, barTop, Math.max(barLeft + dp(8), fillRight), barBottom);
                canvas.drawRoundRect(rect, dp(5), dp(5), fillPaint);

                // Bolinha luminosa (Thumb) na ponta da barra
                float thumbX = Math.max(barLeft + dp(5), Math.min(barRight - dp(5), fillRight));
                float thumbY = (barTop + barBottom) * 0.5f;
                thumbPaint.setColor(Color.argb(Math.round(visibilityAlpha * 95), 56, 189, 248));
                canvas.drawCircle(thumbX, thumbY, dp(7.5f) + wavePulse * dp(1.5f), thumbPaint);
                thumbPaint.setColor(Color.argb(masterAlpha, 248, 250, 252));
                canvas.drawCircle(thumbX, thumbY, dp(4.2f), thumbPaint);
            }

            canvas.restore();

            if (needsNextFrame) {
                postInvalidateOnAnimation();
            }
        }

        private void drawDynamicSpeakerIcon(
                Canvas c, float cx, float cy, int vol, boolean muted, int color, float pulse
        ) {
            iconPaint.setColor(color);

            // Corpo do alto-falante (cone preenchido elegante)
            iconPaint.setStyle(Paint.Style.FILL);
            float spkLeft = cx - dp(9.5f);
            path.reset();
            path.moveTo(spkLeft, cy - dp(3.5f));
            path.lineTo(spkLeft + dp(4.2f), cy - dp(3.5f));
            path.lineTo(spkLeft + dp(9.5f), cy - dp(8.2f));
            path.lineTo(spkLeft + dp(9.5f), cy + dp(8.2f));
            path.lineTo(spkLeft + dp(4.2f), cy + dp(3.5f));
            path.lineTo(spkLeft, cy + dp(3.5f));
            path.close();
            c.drawPath(path, iconPaint);

            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(2.0f));

            if (muted || vol == 0) {
                // Desenha um X nítido ao lado do alto-falante quando mudo
                float xLeft = cx + dp(2.5f);
                float xRight = cx + dp(8.5f);
                c.drawLine(xLeft, cy - dp(3.2f), xRight, cy + dp(3.2f), iconPaint);
                c.drawLine(xRight, cy - dp(3.2f), xLeft, cy + dp(3.2f), iconPaint);
                return;
            }

            // Ondas sonoras dinâmicas (1 onda até 33%, 2 ondas até 66%, 3 ondas acima de 67%)
            float waveCx = spkLeft + dp(8.5f);
            float pulseShift = pulse * dp(1.1f);

            // 1ª onda (sempre visível se vol > 0)
            float r1 = dp(4.5f) + pulseShift * 0.4f;
            rect.set(waveCx - r1, cy - r1, waveCx + r1, cy + r1);
            c.drawArc(rect, -40, 80, false, iconPaint);

            // 2ª onda (visível a partir de 34%)
            if (vol >= 34) {
                float r2 = dp(8.0f) + pulseShift * 0.7f;
                rect.set(waveCx - r2, cy - r2, waveCx + r2, cy + r2);
                c.drawArc(rect, -42, 84, false, iconPaint);
            }

            // 3ª onda (visível a partir de 67%)
            if (vol >= 67) {
                float r3 = dp(11.5f) + pulseShift;
                rect.set(waveCx - r3, cy - r3, waveCx + r3, cy + r3);
                c.drawArc(rect, -45, 90, false, iconPaint);
            }
        }
    }
}
