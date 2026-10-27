package com.gabriel.projectorhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Tela de Descanso (Screensaver) nativa para Projetor com Desligamento Automático estilo TCL Android TV:
 * - Fundo escuro profundo (#04070D) para descanso da lâmpada/LCD e conforto visual no escuro
 * - Nebulosa / Aurora suave em movimento contínuo + Campo de estrelas cintilantes
 * - Card flutuante anti-retenção (movimento harmônico suave pela tela)
 * - Relógio grande, Data por extenso em Português, Clima ao vivo de Andradas - MG e status HDMI
 * - Limite de inatividade de 12 min no descanso (15 min totais somando os 3 min da Home):
 *   nos últimos 60 segundos exibe um Card de Aviso com contagem regressiva (60s -> 0s)
 *   permitindo cancelar com qualquer tecla antes de desligar o projetor com segurança.
 */
public class ScreensaverView extends View {

    private static final String PREFS_NAME = "projector_clean_home_prefs";
    private static final String KEY_CITY_NAME = "weather_city_name_v2";
    private static final String KEY_CACHED_TEMP = "weather_cached_temp_v2";
    private static final String KEY_CACHED_CODE = "weather_cached_code_v2";
    private static final String DEFAULT_CITY_NAME = "Andradas - MG";
    private static final String INPUT_ID_HDMI1 = "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5";

    // 12 minutos na Tela de Descanso (= 15 minutos totais após 3 min de ociosidade na Home)
    private static final float TOTAL_SCREENSAVER_LIMIT_SEC = 12 * 60f;
    // Mostra o card de contagem regressiva nos últimos 60 segundos (estilo TCL Android TV)
    private static final float COUNTDOWN_WARNING_SEC = 60f;

    private static final int STAR_COUNT = 55;
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starRadius = new float[STAR_COUNT];
    private final float[] starSpeed = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];
    private boolean starsInitialized = false;

    private final Paint bgPaint = new Paint();
    private final Paint auroraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondsArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weatherTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Paints para o Card de Contagem Regressiva de Desligamento (60s)
    private final Paint warnCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warnBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warnTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warnCountdownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warnSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warnBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cardRect = new RectF();
    private final RectF pillRect = new RectF();
    private final RectF hdmiPillRect = new RectF();
    private final RectF warnRect = new RectF();
    private final RectF warnBtnRect = new RectF();
    private final RectF arcRect = new RectF();
    private final Path vectorPath = new Path();

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR"));

    private final long startTimeMs;
    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean shutdownTriggered = false;

    private final Runnable frameTicker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            animHandler.postDelayed(this, 33); // ~30 FPS suave e leve para CPU
        }
    };

    public ScreensaverView(Context context) {
        super(context);
        startTimeMs = System.currentTimeMillis();

        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(Color.parseColor("#CC0D1526"));

        cardBorderPaint.setStyle(Paint.Style.STROKE);
        cardBorderPaint.setStrokeWidth(dp(1.5f));
        cardBorderPaint.setColor(Color.parseColor("#3338BDF8"));

        timePaint.setColor(Color.parseColor("#F8FAFC"));
        timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        timePaint.setTextSize(sp(86));
        timePaint.setTextAlign(Paint.Align.CENTER);

        secondsArcPaint.setStyle(Paint.Style.STROKE);
        secondsArcPaint.setStrokeWidth(dp(3));
        secondsArcPaint.setStrokeCap(Paint.Cap.ROUND);

        datePaint.setColor(Color.parseColor("#94A3B8"));
        datePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        datePaint.setTextSize(sp(20));
        datePaint.setTextAlign(Paint.Align.CENTER);

        pillBgPaint.setStyle(Paint.Style.FILL);
        pillBgPaint.setColor(Color.parseColor("#18243A"));

        pillStrokePaint.setStyle(Paint.Style.STROKE);
        pillStrokePaint.setStrokeWidth(dp(1.2f));
        pillStrokePaint.setColor(Color.parseColor("#2B3F63"));

        weatherTextPaint.setColor(Color.parseColor("#E2E8F0"));
        weatherTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        weatherTextPaint.setTextSize(sp(16));
        weatherTextPaint.setTextAlign(Paint.Align.LEFT);

        hintPaint.setColor(Color.parseColor("#475569"));
        hintPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hintPaint.setTextSize(sp(13));
        hintPaint.setTextAlign(Paint.Align.CENTER);

        warnCardPaint.setStyle(Paint.Style.FILL);
        warnCardPaint.setColor(Color.parseColor("#F21A101D"));

        warnBorderPaint.setStyle(Paint.Style.STROKE);
        warnBorderPaint.setStrokeWidth(dp(2.5f));
        warnBorderPaint.setColor(Color.parseColor("#F87171"));

        warnTitlePaint.setColor(Color.parseColor("#FCA5A5"));
        warnTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        warnTitlePaint.setTextSize(sp(14));
        warnTitlePaint.setTextAlign(Paint.Align.CENTER);

        warnCountdownPaint.setColor(Color.parseColor("#F8FAFC"));
        warnCountdownPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        warnCountdownPaint.setTextSize(sp(26));
        warnCountdownPaint.setTextAlign(Paint.Align.CENTER);

        warnSubPaint.setColor(Color.parseColor("#FDE047"));
        warnSubPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        warnSubPaint.setTextSize(sp(14));
        warnSubPaint.setTextAlign(Paint.Align.CENTER);

        warnBtnBgPaint.setStyle(Paint.Style.FILL);
        warnBtnBgPaint.setColor(Color.parseColor("#2A1B2E"));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        animHandler.removeCallbacks(frameTicker);
        animHandler.post(frameTicker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        animHandler.removeCallbacks(frameTicker);
    }

    private void initStarsIfNeeded(int w, int h) {
        if (starsInitialized || w <= 0 || h <= 0) return;
        Random rnd = new Random(42L);
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = rnd.nextFloat() * w;
            starY[i] = rnd.nextFloat() * h;
            starRadius[i] = dp(0.8f + rnd.nextFloat() * 1.8f);
            starSpeed[i] = dp(0.08f + rnd.nextFloat() * 0.22f);
            starPhase[i] = rnd.nextFloat() * 6.28f;
        }
        starsInitialized = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        starsInitialized = false;
        initStarsIfNeeded(w, h);
        bgPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                Color.parseColor("#04070E"),
                Color.parseColor("#09101F"),
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        initStarsIfNeeded(w, h);
        float elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000f;
        float remainingUntilOffSec = TOTAL_SCREENSAVER_LIMIT_SEC - elapsedSec;

        if (remainingUntilOffSec <= 0f && !shutdownTriggered) {
            shutdownTriggered = true;
            triggerCleanShutdown();
        }

        boolean isWarningActive = remainingUntilOffSec <= COUNTDOWN_WARNING_SEC;

        // 1. Fundo escuro protetor de lâmpada do projetor
        canvas.drawRect(0, 0, w, h, bgPaint);

        // 2. Orbes de Aurora / Nebulosa sutis em movimento lento
        drawAuroraOrb(canvas,
                w * (0.35f + 0.18f * (float) Math.sin(elapsedSec * 0.11f)),
                h * (0.40f + 0.15f * (float) Math.cos(elapsedSec * 0.09f)),
                Math.min(w, h) * 0.48f,
                Color.argb(26, 56, 189, 248) // Ciano suave
        );
        drawAuroraOrb(canvas,
                w * (0.65f + 0.16f * (float) Math.cos(elapsedSec * 0.08f)),
                h * (0.60f + 0.14f * (float) Math.sin(elapsedSec * 0.12f)),
                Math.min(w, h) * 0.45f,
                Color.argb(22, 99, 102, 241) // Índigo suave
        );

        // 3. Campo de estrelas cintilantes com leve flutuação vertical
        for (int i = 0; i < STAR_COUNT; i++) {
            starY[i] -= starSpeed[i];
            if (starY[i] < 0) {
                starY[i] = h;
            }
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(elapsedSec * 1.4f + starPhase[i]));
            int alpha = (int) (twinkle * 170);
            starPaint.setColor(Color.argb(alpha, 226, 232, 240));
            canvas.drawCircle(starX[i], starY[i], starRadius[i], starPaint);
        }

        // 4. Posição flutuante do Card Central (Curva de Lissajous lenta anti-burn-in)
        float maxDriftX = w * 0.13f;
        float maxDriftY = isWarningActive ? (h * 0.04f) : (h * 0.11f);
        float baseCenterY = isWarningActive ? (h * 0.34f) : (h * 0.47f);
        float centerX = (w * 0.50f) + maxDriftX * (float) Math.sin(elapsedSec * 0.07f);
        float centerY = baseCenterY + maxDriftY * (float) Math.cos(elapsedSec * 0.05f);

        float cardW = dp(470);
        float cardH = dp(265);
        cardRect.set(centerX - cardW / 2f, centerY - cardH / 2f, centerX + cardW / 2f, centerY + cardH / 2f);

        // Sombra / Brilho externo discreto do card
        canvas.drawRoundRect(cardRect, dp(28), dp(28), cardPaint);
        canvas.drawRoundRect(cardRect, dp(28), dp(28), cardBorderPaint);

        // 5. Relógio Digital + Barra de Progresso dos Segundos
        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        int seconds = cal.get(Calendar.SECOND);
        int millis = cal.get(Calendar.MILLISECOND);
        float smoothSec = seconds + (millis / 1000f);

        String timeStr = timeFormat.format(now);
        float timeY = cardRect.top + dp(104);
        canvas.drawText(timeStr, centerX, timeY, timePaint);

        // Linha elegante de progresso dos segundos logo abaixo da hora
        float barWidth = dp(180);
        float barY = timeY + dp(16);
        float barLeft = centerX - barWidth / 2f;
        float barRight = centerX + barWidth / 2f;
        secondsArcPaint.setColor(Color.parseColor("#1E293B"));
        canvas.drawLine(barLeft, barY, barRight, barY, secondsArcPaint);

        secondsArcPaint.setColor(Color.parseColor("#38BDF8"));
        float progressRight = barLeft + (barWidth * (smoothSec / 60f));
        canvas.drawLine(barLeft, barY, progressRight, barY, secondsArcPaint);

        // 6. Data por extenso em Português
        String dateStr = dateFormat.format(now);
        if (!TextUtils.isEmpty(dateStr)) {
            dateStr = dateStr.substring(0, 1).toUpperCase() + dateStr.substring(1);
        }
        float dateY = barY + dp(34);
        canvas.drawText(dateStr, centerX, dateY, datePaint);

        // 7. Pílula de Clima (Andradas - MG) + Status HDMI
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String city = prefs.getString(KEY_CITY_NAME, DEFAULT_CITY_NAME);
        int temp = prefs.getInt(KEY_CACHED_TEMP, 22);
        int weatherCode = prefs.getInt(KEY_CACHED_CODE, 1);
        boolean hasTemp = prefs.contains(KEY_CACHED_TEMP);

        String weatherLabel = (hasTemp ? (temp + "°C • ") : "") + city + " • " + getWeatherConditionPt(weatherCode);
        float textW = weatherTextPaint.measureText(weatherLabel);
        float iconSpace = dp(26);
        float pillW = textW + iconSpace + dp(32);
        float pillH = dp(38);
        float pillTop = dateY + dp(20);

        pillRect.set(centerX - pillW / 2f, pillTop, centerX + pillW / 2f, pillTop + pillH);
        canvas.drawRoundRect(pillRect, dp(19), dp(19), pillBgPaint);
        canvas.drawRoundRect(pillRect, dp(19), dp(19), pillStrokePaint);

        // Ícone vetorial do clima dentro da pílula
        float iconCx = pillRect.left + dp(22);
        float iconCy = pillRect.centerY();
        drawWeatherVectorIcon(canvas, iconCx, iconCy, weatherCode);

        Paint.FontMetrics fm = weatherTextPaint.getFontMetrics();
        float textBaseline = pillRect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(weatherLabel, pillRect.left + dp(38), textBaseline, weatherTextPaint);

        // Se o cabo HDMI estiver com sinal ativo, mostra aviso no topo do card
        if (isHdmiConnected()) {
            String hdmiMsg = "● Sinal HDMI Ativo";
            weatherTextPaint.setTextSize(sp(12));
            weatherTextPaint.setColor(Color.parseColor("#4ADE80"));
            float hw = weatherTextPaint.measureText(hdmiMsg) + dp(24);
            hdmiPillRect.set(centerX - hw / 2f, cardRect.top - dp(16), centerX + hw / 2f, cardRect.top + dp(12));
            canvas.drawRoundRect(hdmiPillRect, dp(14), dp(14), pillBgPaint);
            canvas.drawRoundRect(hdmiPillRect, dp(14), dp(14), pillStrokePaint);
            canvas.drawText(hdmiMsg, hdmiPillRect.left + dp(12), hdmiPillRect.centerY() + dp(4), weatherTextPaint);
            weatherTextPaint.setTextSize(sp(16));
            weatherTextPaint.setColor(Color.parseColor("#E2E8F0"));
        }

        // 8. Card de Contagem Regressiva de 60 Segundos (Estilo TCL Android TV) nos últimos 60s
        if (isWarningActive) {
            int secLeft = Math.max(0, (int) Math.ceil(remainingUntilOffSec));
            float warnW = dp(520);
            float warnH = dp(156);
            float warnTop = cardRect.bottom + dp(22);
            warnRect.set(w * 0.50f - warnW / 2f, warnTop, w * 0.50f + warnW / 2f, warnTop + warnH);

            // Borda pulsante vermelha suave de alerta
            int pulseAlpha = 140 + (int) (115 * (0.5f + 0.5f * Math.sin(elapsedSec * 4.5f)));
            warnBorderPaint.setColor(Color.argb(pulseAlpha, 248, 113, 113));

            canvas.drawRoundRect(warnRect, dp(22), dp(22), warnCardPaint);
            canvas.drawRoundRect(warnRect, dp(22), dp(22), warnBorderPaint);

            String warnHeader = "DESLIGAMENTO AUTOMÁTICO POR INATIVIDADE";
            float headerW = warnTitlePaint.measureText(warnHeader);
            float totalHeaderW = dp(24) + headerW;
            float startHeaderX = warnRect.centerX() - totalHeaderW / 2f;
            drawWarningTriangleIcon(canvas, startHeaderX + dp(9), warnRect.top + dp(27));

            warnTitlePaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(warnHeader, startHeaderX + dp(24), warnRect.top + dp(32), warnTitlePaint);
            warnTitlePaint.setTextAlign(Paint.Align.CENTER);

            canvas.drawText("O projetor será desligado em " + secLeft + "s", warnRect.centerX(), warnRect.top + dp(70), warnCountdownPaint);

            // Barra regressiva dos 60 segundos
            float cBarW = dp(420);
            float cBarY = warnRect.top + dp(92);
            float cBarLeft = warnRect.centerX() - cBarW / 2f;
            float cBarRight = warnRect.centerX() + cBarW / 2f;
            secondsArcPaint.setStrokeWidth(dp(5));
            secondsArcPaint.setColor(Color.parseColor("#331E293B"));
            canvas.drawLine(cBarLeft, cBarY, cBarRight, cBarY, secondsArcPaint);

            float ratio = Math.max(0f, Math.min(1f, remainingUntilOffSec / COUNTDOWN_WARNING_SEC));
            secondsArcPaint.setColor(Color.parseColor("#EF4444"));
            canvas.drawLine(cBarLeft, cBarY, cBarLeft + cBarW * ratio, cBarY, secondsArcPaint);
            secondsArcPaint.setStrokeWidth(dp(3));

            // Pílula de destaque para cancelar
            float btnW = dp(430);
            float btnH = dp(34);
            float btnTop = warnRect.top + dp(108);
            warnBtnRect.set(warnRect.centerX() - btnW / 2f, btnTop, warnRect.centerX() + btnW / 2f, btnTop + btnH);
            canvas.drawRoundRect(warnBtnRect, dp(17), dp(17), warnBtnBgPaint);
            canvas.drawText("Pressione qualquer tecla no controle para cancelar", warnRect.centerX(), warnBtnRect.centerY() + dp(5), warnSubPaint);
        } else {
            // Rodapé discreto indicando quantos minutos faltam para o desligamento automático
            int minsLeft = Math.max(1, (int) Math.ceil(remainingUntilOffSec / 60f));
            int hintAlpha = 90 + (int) (55 * Math.sin(elapsedSec * 1.2f));
            hintPaint.setColor(Color.argb(hintAlpha, 148, 163, 184));
            canvas.drawText(
                    "Projetor em Descanso (Desliga em " + minsLeft + " min) • Pressione qualquer botão para voltar",
                    w * 0.50f,
                    h - dp(28),
                    hintPaint
            );
        }
    }

    private void triggerCleanShutdown() {
        Context ctx = getContext();
        try {
            android.provider.Settings.Global.putInt(ctx.getContentResolver(), android.provider.Settings.Global.BLUETOOTH_ON, 0);
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) {
                bt.disable();
            }
        } catch (Exception ignored) {
        }
        try {
            Intent shutdown = new Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN");
            shutdown.putExtra("android.intent.extra.KEY_CONFIRM", false);
            shutdown.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(shutdown);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent shutdown2 = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
            shutdown2.putExtra("android.intent.extra.KEY_CONFIRM", false);
            shutdown2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(shutdown2);
            return;
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                java.lang.reflect.Method m = pm.getClass().getMethod("shutdown", boolean.class, String.class, boolean.class);
                m.invoke(pm, false, "inactivity", false);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "svc power shutdown || reboot -p"});
        } catch (Exception ignored) {
        }
    }

    private void drawAuroraOrb(Canvas canvas, float cx, float cy, float radius, int centerColor) {
        RadialGradient grad = new RadialGradient(
                cx, cy, Math.max(10f, radius),
                centerColor,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        );
        auroraPaint.setShader(grad);
        canvas.drawCircle(cx, cy, radius, auroraPaint);
    }

    private void drawWarningTriangleIcon(Canvas c, float cx, float cy) {
        // Desenha o ícone vetorial de aviso ⚠️ (Triângulo amarelo com ponto de exclamação escuro)
        float halfW = dp(8.5f);
        float topY = cy - dp(7.5f);
        float botY = cy + dp(7.0f);

        vectorPath.reset();
        vectorPath.moveTo(cx, topY);
        vectorPath.lineTo(cx + halfW, botY);
        vectorPath.lineTo(cx - halfW, botY);
        vectorPath.close();

        vectorPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        vectorPaint.setStrokeWidth(dp(2.2f));
        vectorPaint.setStrokeJoin(Paint.Join.ROUND);
        vectorPaint.setStrokeCap(Paint.Cap.ROUND);
        vectorPaint.setColor(Color.parseColor("#FACC15")); // Amarelo alerta
        c.drawPath(vectorPath, vectorPaint);

        // Traço e ponto da exclamação (!) dentro do triângulo
        vectorPaint.setStyle(Paint.Style.STROKE);
        vectorPaint.setStrokeWidth(dp(1.8f));
        vectorPaint.setColor(Color.parseColor("#18121E"));
        c.drawLine(cx, topY + dp(4.2f), cx, botY - dp(4.2f), vectorPaint);

        vectorPaint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, botY - dp(1.6f), dp(1.1f), vectorPaint);
    }

    private void drawWeatherVectorIcon(Canvas c, float cx, float cy, int code) {
        if (code == 0) {
            vectorPaint.setStyle(Paint.Style.FILL);
            vectorPaint.setColor(Color.parseColor("#FBBF24"));
            c.drawCircle(cx, cy, dp(5.5f), vectorPaint);
            vectorPaint.setStyle(Paint.Style.STROKE);
            vectorPaint.setStrokeWidth(dp(1.6f));
            for (int a = 0; a < 360; a += 45) {
                double rad = Math.toRadians(a);
                c.drawLine(
                        cx + (float) Math.cos(rad) * dp(7.5f),
                        cy + (float) Math.sin(rad) * dp(7.5f),
                        cx + (float) Math.cos(rad) * dp(9.8f),
                        cy + (float) Math.sin(rad) * dp(9.8f),
                        vectorPaint
                );
            }
        } else {
            if (code <= 2) {
                vectorPaint.setStyle(Paint.Style.FILL);
                vectorPaint.setColor(Color.parseColor("#FBBF24"));
                c.drawCircle(cx - dp(3), cy - dp(3), dp(4.5f), vectorPaint);
            }
            vectorPaint.setStyle(Paint.Style.FILL);
            vectorPaint.setColor(Color.parseColor("#38BDF8"));
            arcRect.set(cx - dp(7), cy - dp(2), cx + dp(7), cy + dp(5));
            c.drawRoundRect(arcRect, dp(4), dp(4), vectorPaint);
            c.drawCircle(cx - dp(2), cy - dp(2), dp(4.5f), vectorPaint);
            c.drawCircle(cx + dp(3), cy - dp(1), dp(3.8f), vectorPaint);
        }
    }

    private String getWeatherConditionPt(int code) {
        if (code == 0) return "Céu Limpo";
        if (code == 1) return "Poucas Nuvens";
        if (code == 2) return "Parc. Nublado";
        if (code == 3) return "Nublado";
        if (code >= 45 && code <= 48) return "Neblina";
        if (code >= 51 && code <= 67) return "Chuva";
        if (code >= 80 && code <= 82) return "Pancadas";
        if (code >= 95) return "Tempestade";
        return "Clima Atual";
    }

    private boolean isHdmiConnected() {
        try {
            TvInputManager tim = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                return tim.getInputState(INPUT_ID_HDMI1) == TvInputManager.INPUT_STATE_CONNECTED;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
