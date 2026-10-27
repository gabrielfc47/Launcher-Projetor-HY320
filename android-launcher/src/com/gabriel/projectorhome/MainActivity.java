package com.gabriel.projectorhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "projector_clean_home_prefs";
    private static final String KEY_FAVORITES = "favorite_packages";
    private static final String KEY_HIDDEN = "hidden_packages";
    private static final String KEY_CITY_NAME = "weather_city_name_v2";
    private static final String KEY_CITY_LAT = "weather_city_lat_v2";
    private static final String KEY_CITY_LON = "weather_city_lon_v2";
    private static final String KEY_CACHED_TEMP = "weather_cached_temp_v2";
    private static final String KEY_CACHED_CODE = "weather_cached_code_v2";
    private static final String KEY_CACHED_TIME = "weather_cached_time_v2";

    private static final String DEFAULT_CITY_NAME = "Andradas - MG";
    private static final String DEFAULT_CITY_LAT = "-22.0681";
    private static final String DEFAULT_CITY_LON = "-46.5692";

    // IDs nativos de hardware do projetor t950s_be311 (DroidLogic TvInput)
    private static final String INPUT_ID_HDMI1 = "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5";
    private static final String INPUT_ID_AV1 = "com.droidlogic.tvinput/.services.AV1InputService/HW1";

    private static final int ICON_TYPE_HDMI = 1;
    private static final int ICON_TYPE_USB = 2;
    private static final int ICON_TYPE_RCA_AV = 3;
    private static final int ICON_TYPE_CAST = 4;
    private static final int ICON_TYPE_KEYSTONE = 5;
    private static final int ICON_TYPE_WIFI = 6;
    private static final int ICON_TYPE_BLUETOOTH = 7;
    private static final int ICON_TYPE_SETTINGS = 8;
    private static final int ICON_TYPE_SCREENSAVER = 9;
    private static final int ICON_TYPE_RESTART = 10;
    private static final int ICON_TYPE_POWER_OFF = 11;

    // Entra automaticamente na Tela de Descanso após 3 minutos sem mexer no controle
    private static final long IDLE_SCREENSAVER_TIMEOUT_MS = 3 * 60 * 1000L;

    private TextView clockTimeText;
    private TextView clockDateText;
    private TextView weatherText;
    private GridLayout inputsGrid;
    private GridLayout favoritesGrid;
    private GridLayout allAppsGrid;
    private TextView favoritesHeader;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private SoundPool navSoundPool;
    private int navSoundId = 0;
    private boolean navSoundLoaded = false;
    private long resumeTimeMs = 0L;
    private long lastTickTimeMs = 0L;
    private long lastDpadKeyTimeMs = 0L;
    private boolean pendingNavSoundForKeyPress = false;
    private static boolean bootBtDisabledOnce = false;

    private StatusDotView wifiStatusDot;
    private VectorIconView wifiIconView;
    private TextView wifiLabelText;
    private StatusDotView btStatusDot;
    private VectorIconView btIconView;
    private TextView btLabelText;

    private static final String[] DEFAULT_PRIORITY_PACKAGES = new String[]{
            "com.google.android.youtube.tv",
            "com.netflix.mediaclient",
            "com.abc.ninja",
            "com.nathnetwork.xciptv",
            "com.xciptv.player",
            "com.ottrun.xciptv",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "br.com.sbt.mais",
            "com.esaba.downloader",
            "com.aftvnews.downloader",
            "com.google.android.youtube.tvmusic",
            "org.videolan.vlc",
            "com.android.chrome",
            "com.android.vending",
            "com.droidlogic.FileBrower",
            "com.droidlogic.mediacenter"
    };

    private static final String[] DEFAULT_IGNORED_PACKAGES = new String[]{
            "com.gabriel.projectorhome",
            "com.htc.htclauncherhighenglishd08",
            "com.jm.jmlauncher",
            "com.google.android.leanbacklauncher",
            "com.spocky.projengmenu",
            "com.android.cts.ctsshim",
            "com.android.cts.priv.ctsshim",
            "com.google.android.tvrecommendations"
    };

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            updateClock();
            updateConnectivityIndicators();
            mainHandler.postDelayed(this, 10000);
        }
    };

    private final Runnable idleScreensaverRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                startScreensaverNow();
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    private final BroadcastReceiver bootUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            initDefaultFavoritesIfNeeded();
            populateInputsSection();
            loadApps();
            updateConnectivityIndicators();
            refreshWeatherAsync(false);
        }
    };

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateConnectivityIndicators();
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateConnectivityIndicators();
                }
            }, 600);
        }
    };

    /**
     * Bolinha indicadora de status ao vivo (Verde pulsante quando ligado, Cinza quando desligado).
     */
    private class StatusDotView extends View {
        private boolean isActive = false;
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long startTimeMs = System.currentTimeMillis();

        StatusDotView(Context context, boolean initialActive) {
            super(context);
            this.isActive = initialActive;
            dotPaint.setStyle(Paint.Style.FILL);
            haloPaint.setStyle(Paint.Style.FILL);
        }

        void setActive(boolean active) {
            if (this.isActive != active) {
                this.isActive = active;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;

            if (isActive) {
                // Animação suave de pulso verde (efeito respiração LED)
                double t = (System.currentTimeMillis() - startTimeMs) / 420.0;
                float pulse = (float) ((Math.sin(t) + 1.0) * 0.5); // 0.0 a 1.0

                float haloRadius = dp(3) + pulse * dp(2);
                int haloAlpha = (int) (45 + pulse * 70);
                haloPaint.setColor(Color.argb(haloAlpha, 34, 197, 94));
                canvas.drawCircle(cx, cy, haloRadius, haloPaint);

                dotPaint.setColor(Color.parseColor("#22C55E")); // Verde esmeralda vivo
                canvas.drawCircle(cx, cy, dp(3), dotPaint);

                // Brilho central do LED
                dotPaint.setColor(Color.parseColor("#86EFAC"));
                canvas.drawCircle(cx - dp(1) * 0.3f, cy - dp(1) * 0.3f, dp(1), dotPaint);

                if (isAttachedToWindow()) {
                    postInvalidateDelayed(45);
                }
            } else {
                // Cinza discreto quando desligado (sem gastar CPU com animação)
                dotPaint.setColor(Color.parseColor("#64748B"));
                canvas.drawCircle(cx, cy, dp(3), dotPaint);
            }
        }
    }

    private static class AppEntry {
        String packageName;
        String activityName;
        String label;
        Drawable icon;
        boolean isFavorite;
        int priorityScore;
    }

    /**
     * Ícones vetoriais modernos desenhados nativamente via Canvas (Zero emojis antigos).
     */
    private class VectorIconView extends View {
        private final int iconType;
        private final boolean hasBadgeBg;
        private boolean activeState = true;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        VectorIconView(Context context, int iconType, String badgeHex) {
            super(context);
            this.iconType = iconType;
            this.hasBadgeBg = !TextUtils.isEmpty(badgeHex);
            if (this.hasBadgeBg) {
                bgPaint.setStyle(Paint.Style.FILL);
                bgPaint.setColor(Color.parseColor(badgeHex));
            }
        }

        void setActiveState(boolean active) {
            if (this.activeState != active) {
                this.activeState = active;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (hasBadgeBg) {
                float r = dp(12);
                rect.set(0, 0, w, h);
                canvas.drawRoundRect(rect, r, r, bgPaint);
            }

            if (iconType == ICON_TYPE_HDMI) {
                drawHdmiPortIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_USB) {
                drawUsbIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_RCA_AV) {
                drawRcaAvIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_CAST) {
                drawCastIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_KEYSTONE) {
                drawKeystoneIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_WIFI) {
                drawWifiIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_BLUETOOTH) {
                drawBluetoothIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_SETTINGS) {
                drawSettingsIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_SCREENSAVER) {
                drawScreensaverIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_RESTART) {
                drawRestartIcon(canvas, w, h);
            } else if (iconType == ICON_TYPE_POWER_OFF) {
                drawPowerOffIcon(canvas, w, h);
            }
        }

        private void drawHdmiPortIcon(Canvas c, float w, float h) {
            // Desenha uma porta HDMI chanfrada com exatamente o mesmo formato no topo e no card inferior
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(hasBadgeBg ? dp(2) : Math.max(2f, dp(2) * 0.82f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.parseColor("#38BDF8"));

            float left = hasBadgeBg ? w * 0.16f : w * 0.08f;
            float right = hasBadgeBg ? w * 0.84f : w * 0.92f;
            float top = hasBadgeBg ? h * 0.30f : h * 0.25f;
            float bottom = hasBadgeBg ? h * 0.70f : h * 0.75f;
            float bevel = (right - left) * 0.18f;
            float shoulderY = top + (bottom - top) * 0.55f;

            path.reset();
            path.moveTo(left, top);
            path.lineTo(right, top);
            path.lineTo(right, shoulderY);
            path.lineTo(right - bevel, bottom);
            path.lineTo(left + bevel, bottom);
            path.lineTo(left, shoulderY);
            path.close();
            c.drawPath(path, paint);

            // Barra central de contatos dourada/ciano da porta HDMI
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#E0F2FE"));
            float barMargin = (right - left) * 0.22f;
            float barTop = top + (bottom - top) * 0.40f;
            float barBottom = top + (bottom - top) * 0.56f;
            rect.set(left + barMargin, barTop, right - barMargin, barBottom);
            c.drawRoundRect(rect, dp(1), dp(1), paint);
        }

        private void drawUsbIcon(Canvas c, float w, float h) {
            // Desenha o símbolo oficial USB (Tridente com seta, círculo e quadrado)
            paint.setColor(Color.parseColor("#60A5FA"));
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float cx = w * 0.50f;
            float topY = h * 0.18f;
            float botY = h * 0.80f;

            // Haste central
            paint.setStyle(Paint.Style.STROKE);
            c.drawLine(cx, topY + dp(4), cx, botY - dp(3), paint);

            // Círculo da base
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, botY, dp(4), paint);

            // Seta superior (triângulo)
            path.reset();
            path.moveTo(cx, topY - dp(1));
            path.lineTo(cx - dp(5), topY + dp(6));
            path.lineTo(cx + dp(5), topY + dp(6));
            path.close();
            c.drawPath(path, paint);

            // Braço esquerdo (com círculo)
            float leftX = w * 0.27f;
            float leftNodeY = h * 0.38f;
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            path.moveTo(cx, h * 0.62f);
            path.lineTo(leftX, h * 0.50f);
            path.lineTo(leftX, leftNodeY);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(leftX, leftNodeY, dp(3), paint);

            // Braço direito (com quadrado)
            float rightX = w * 0.73f;
            float rightNodeY = h * 0.34f;
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            path.moveTo(cx, h * 0.54f);
            path.lineTo(rightX, h * 0.44f);
            path.lineTo(rightX, rightNodeY);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            float sq = dp(3);
            c.drawRect(rightX - sq, rightNodeY - sq, rightX + sq, rightNodeY + sq, paint);
        }

        private void drawRcaAvIcon(Canvas c, float w, float h) {
            // Desenha os 3 conectores RCA (Amarelo = Vídeo, Branco = Áudio L, Vermelho = Áudio R)
            int[] ringColors = new int[]{
                    Color.parseColor("#FACC15"), // Amarelo
                    Color.parseColor("#F8FAFC"), // Branco
                    Color.parseColor("#F87171")  // Vermelho
            };
            float[] centersX = new float[]{w * 0.25f, w * 0.50f, w * 0.75f};
            float cy = h * 0.50f;
            float outerR = dp(6);
            float innerR = dp(2);

            for (int i = 0; i < 3; i++) {
                // Cabo inferior e pino superior do plugue RCA
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(ringColors[i]);
                c.drawCircle(centersX[i], cy, outerR, paint);

                // Pino central metálico do conector RCA
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.parseColor("#94A3B8"));
                c.drawCircle(centersX[i], cy, innerR, paint);
            }
        }

        private void drawCastIcon(Canvas c, float w, float h) {
            // Desenha tela widescreen com ondas de transmissão sem fio (Cast)
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.parseColor("#A78BFA"));

            float left = w * 0.18f;
            float top = h * 0.24f;
            float right = w * 0.82f;
            float bottom = h * 0.76f;

            // Moldura da tela
            rect.set(left, top, right, bottom);
            c.drawRoundRect(rect, dp(4), dp(4), paint);

            // Ondas wireless no canto inferior esquerdo
            paint.setColor(Color.parseColor("#38BDF8"));
            float originX = left + dp(4);
            float originY = bottom - dp(4);

            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(originX, originY, dp(2), paint);

            paint.setStyle(Paint.Style.STROKE);
            float r1 = dp(7);
            rect.set(originX - r1, originY - r1, originX + r1, originY + r1);
            c.drawArc(rect, 270, 90, false, paint);

            float r2 = dp(12);
            rect.set(originX - r2, originY - r2, originX + r2, originY + r2);
            c.drawArc(rect, 270, 90, false, paint);
        }

        private void drawKeystoneIcon(Canvas c, float w, float h) {
            // Desenha um trapézio de correção Keystone / Projeção
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.parseColor("#FBBF24"));

            path.reset();
            path.moveTo(w * 0.24f, h * 0.22f);
            path.lineTo(w * 0.76f, h * 0.22f);
            path.lineTo(w * 0.90f, h * 0.78f);
            path.lineTo(w * 0.10f, h * 0.78f);
            path.close();
            c.drawPath(path, paint);
        }

        private void drawWifiIcon(Canvas c, float w, float h) {
            // Desenha arcos de sinal Wi-Fi (aceso em azul quando ligado, cinza quando desligado)
            paint.setColor(Color.parseColor(activeState ? "#38BDF8" : "#64748B"));
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);

            float cx = w * 0.50f;
            float cy = h * 0.78f;

            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, dp(2), paint);

            paint.setStyle(Paint.Style.STROKE);
            float r1 = w * 0.28f;
            rect.set(cx - r1, cy - r1, cx + r1, cy + r1);
            c.drawArc(rect, 225, 90, false, paint);

            float r2 = w * 0.48f;
            rect.set(cx - r2, cy - r2, cx + r2, cy + r2);
            c.drawArc(rect, 225, 90, false, paint);
        }

        private void drawBluetoothIcon(Canvas c, float w, float h) {
            // Desenha o símbolo oficial do Bluetooth (aceso em azul quando ligado, cinza quando desligado)
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.parseColor(activeState ? "#60A5FA" : "#64748B"));

            float cx = w * 0.48f;
            path.reset();
            path.moveTo(cx, h * 0.14f);
            path.lineTo(cx, h * 0.86f);
            path.lineTo(w * 0.76f, h * 0.66f);
            path.lineTo(w * 0.24f, h * 0.34f);
            path.moveTo(w * 0.24f, h * 0.66f);
            path.lineTo(w * 0.76f, h * 0.34f);
            path.lineTo(cx, h * 0.14f);
            c.drawPath(path, paint);
        }

        private void drawSettingsIcon(Canvas c, float w, float h) {
            // Desenha sliders de ajuste/configuração modernos
            paint.setColor(Color.parseColor("#CBD5E1"));
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);

            float left = w * 0.15f;
            float right = w * 0.85f;
            float y1 = h * 0.34f;
            float y2 = h * 0.66f;

            paint.setStyle(Paint.Style.STROKE);
            c.drawLine(left, y1, right, y1, paint);
            c.drawLine(left, y2, right, y2, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#38BDF8"));
            c.drawCircle(w * 0.38f, y1, dp(3), paint);
            c.drawCircle(w * 0.65f, y2, dp(3), paint);
        }

        private void drawScreensaverIcon(Canvas c, float w, float h) {
            // Lua Crescente perfeitamente geométrica via subtração de círculos (Path.Op.DIFFERENCE)
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#FACC15")); // Dourado vivo

            Path outerMoon = new Path();
            Path innerCutout = new Path();
            outerMoon.addCircle(w * 0.47f, h * 0.52f, w * 0.36f, Path.Direction.CW);
            innerCutout.addCircle(w * 0.66f, h * 0.34f, w * 0.31f, Path.Direction.CW);
            outerMoon.op(innerCutout, Path.Op.DIFFERENCE);
            c.drawPath(outerMoon, paint);
        }

        private void drawRestartIcon(Canvas c, float w, float h) {
            // Ícone clássico de Reiniciar (Arco circular + ponta de seta nítida no topo apontando para a direita)
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.parseColor("#38BDF8")); // Azul ciano harmônico com a barra superior

            float cx = w * 0.50f;
            float cy = h * 0.54f;
            float r = w * 0.31f;
            rect.set(cx - r, cy - r, cx + r, cy + r);
            // Arco de 240 graus terminando exatamente no topo central (270 graus)
            c.drawArc(rect, 30, 240, false, paint);

            // Ponta de seta triangular preenchida no topo do arco apontando para a direita
            paint.setStyle(Paint.Style.FILL);
            float tipX = cx + dp(4);
            float tipY = cy - r;
            path.reset();
            path.moveTo(tipX, tipY);
            path.lineTo(cx - dp(1), tipY - dp(3));
            path.lineTo(cx - dp(1), tipY + dp(3));
            path.close();
            c.drawPath(path, paint);
        }

        private void drawPowerOffIcon(Canvas c, float w, float h) {
            // Ícone padrão universal de Desligar (Arco aberto no topo + traço vertical central)
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.parseColor("#F87171")); // Vermelho coral suave

            float cx = w * 0.50f;
            float cy = h * 0.54f;
            float r = w * 0.31f;
            rect.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rect, 300, 300, false, paint);

            // Haste vertical superior do botão Power
            c.drawLine(cx, h * 0.14f, cx, h * 0.48f, paint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        initDefaultFavoritesIfNeeded();

        setContentView(buildRootLayout());

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        bootFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        registerReceiver(bootUnlockReceiver, bootFilter);

        IntentFilter connFilter = new IntentFilter();
        connFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        connFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(connectivityReceiver, connFilter);

        updateClock();
        updateConnectivityIndicators();
        loadCachedWeather();
        initNavSoundTrack();
        VolumeOverlayService.ensureStarted(this);
        ensureWifiAndOptimizeMemory();
        refreshWeatherAsync(false);
        populateInputsSection();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumeTimeMs = System.currentTimeMillis();
        mainHandler.removeCallbacks(clockTicker);
        mainHandler.post(clockTicker);
        resetIdleScreensaverTimer();
        VolumeOverlayService.ensureStarted(this);
        ensureWifiAndOptimizeMemory();
        updateConnectivityIndicators();
        populateInputsSection();
        loadApps();
        refreshWeatherAsync(false);
    }

    private void updateConnectivityIndicators() {
        boolean wifiOn = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiOn = (wm != null && wm.isWifiEnabled());
        } catch (Exception ignored) {
        }

        boolean btOn = false;
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            btOn = (bt != null && bt.isEnabled());
        } catch (Exception ignored) {
        }

        if (wifiStatusDot != null) {
            wifiStatusDot.setActive(wifiOn);
        }
        if (wifiIconView != null) {
            wifiIconView.setActiveState(wifiOn);
        }
        if (wifiLabelText != null) {
            wifiLabelText.setTextColor(Color.parseColor(wifiOn ? "#F8FAFC" : "#94A3B8"));
        }

        if (btStatusDot != null) {
            btStatusDot.setActive(btOn);
        }
        if (btIconView != null) {
            btIconView.setActiveState(btOn);
        }
        if (btLabelText != null) {
            btLabelText.setTextColor(Color.parseColor(btOn ? "#F8FAFC" : "#94A3B8"));
        }
    }

    private void ensureWifiAndOptimizeMemory() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Settings.Global.putInt(getContentResolver(), Settings.Global.WIFI_ON, 1);
                } catch (Exception ignored) {
                }
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null && !wm.isWifiEnabled()) {
                        wm.setWifiEnabled(true);
                    }
                } catch (Exception ignored) {
                }
                if (!bootBtDisabledOnce) {
                    bootBtDisabledOnce = true;
                    disableBluetoothQuietly();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateConnectivityIndicators();
                        }
                    });
                }
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses("com.jm.jmlauncher");
                        am.killBackgroundProcesses("com.htc.htclauncherhighenglishd08");
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void disableBluetoothQuietly() {
        try {
            Settings.Global.putInt(getContentResolver(), Settings.Global.BLUETOOTH_ON, 0);
        } catch (Exception ignored) {
        }
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) {
                bt.disable();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(clockTicker);
        mainHandler.removeCallbacks(idleScreensaverRunnable);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetIdleScreensaverTimer();
    }

    private void resetIdleScreensaverTimer() {
        mainHandler.removeCallbacks(idleScreensaverRunnable);
        mainHandler.postDelayed(idleScreensaverRunnable, IDLE_SCREENSAVER_TIMEOUT_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(packageReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(bootUnlockReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (navSoundPool != null) {
                navSoundPool.release();
                navSoundPool = null;
            }
        } catch (Exception ignored) {
        }
        bgExecutor.shutdownNow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            int code = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_DPAD_UP
                        || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    long now = System.currentTimeMillis();
                    // Só libera 1 som por toque (ou a cada 110ms se segurar a seta apertada)
                    if (now - lastTickTimeMs >= 110) {
                        pendingNavSoundForKeyPress = true;
                        lastDpadKeyTimeMs = now;
                    }
                } else if (code == KeyEvent.KEYCODE_VOLUME_UP
                        || code == KeyEvent.KEYCODE_VOLUME_DOWN
                        || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                    VolumeOverlayService.triggerVolumePopup(this);
                }
            }

            // Nas setas Esquerda/Direita, o ScrollView vertical retorna false e deixaria o
            // ViewRootImpl do Android tocar o som nativo do sistema por cima do nosso som!
            // Fazendo a mudança de foco horizontal aqui e retornando true, igualamos ao Cima/Baixo.
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (super.dispatchKeyEvent(event)) {
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int dir = (code == KeyEvent.KEYCODE_DPAD_LEFT) ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                    View focused = getCurrentFocus();
                    if (focused != null) {
                        View next = focused.focusSearch(dir);
                        if (next != null && next != focused) {
                            next.requestFocus(dir);
                        }
                    }
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Sintetiza um arquivo WAV curto de 18ms e carrega no SoundPool (maxStreams = 1)
     * para nunca encavalar dois sons ao trocar de card rapidamente.
     */
    private void initNavSoundTrack() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int sampleRate = 44100;
                    int durationMs = 18;
                    int numSamples = (sampleRate * durationMs) / 1000;
                    byte[] pcmBytes = new byte[numSamples * 2];
                    double phase = 0.0;
                    for (int i = 0; i < numSamples; i++) {
                        double progress = (double) i / numSamples;
                        // Sweep suave de 520Hz para 190Hz com envelope exponencial (som aveludado de TV)
                        double freq = 520.0 - (330.0 * progress);
                        phase += (2.0 * Math.PI * freq) / sampleRate;
                        double env = Math.sin(Math.PI * Math.min(1.0, i / (numSamples * 0.12)))
                                * Math.exp(-progress * 5.2);
                        double sample = (Math.sin(phase) * 0.80 + Math.sin(phase * 2.0) * 0.20) * env;
                        short val = (short) (sample * 14000);
                        pcmBytes[i * 2] = (byte) (val & 0xff);
                        pcmBytes[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
                    }

                    File wavFile = new File(getCacheDir(), "nav_tick_v2.wav");
                    writeWavFile(wavFile, pcmBytes, sampleRate);

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
                                navSoundLoaded = true;
                            }
                        }
                    });
                    navSoundId = pool.load(wavFile.getAbsolutePath(), 1);
                    navSoundPool = pool;
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void writeWavFile(File file, byte[] pcmData, int sampleRate) throws Exception {
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
        header[20] = 1; header[21] = 0; // PCM
        header[22] = 1; header[23] = 0; // Mono
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = 2; header[33] = 0; // Block align
        header[34] = 16; header[35] = 0; // 16-bit
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

    private void playNavigationTickSound(View v) {
        long now = System.currentTimeMillis();
        // Só toca se veio de um aperto real de seta no controle e consome a flag imediatamente
        // para que saltos intermediários de foco do ScrollView/GridLayout nunca toquem 2 vezes!
        if (!pendingNavSoundForKeyPress || now - lastDpadKeyTimeMs > 280 || now - lastTickTimeMs < 105) {
            return;
        }
        pendingNavSoundForKeyPress = false;
        lastTickTimeMs = now;
        try {
            if (navSoundPool != null && navSoundLoaded && navSoundId != 0) {
                navSoundPool.play(navSoundId, 0.85f, 0.85f, 1, 0, 1.0f);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        // Na tela inicial (Launcher), o botão Voltar não fecha a Home.
    }

    private void initDefaultFavoritesIfNeeded() {
        Set<String> favs = new HashSet<String>(prefs.getStringSet(KEY_FAVORITES, new HashSet<String>()));
        boolean firstRun = !prefs.contains(KEY_FAVORITES);
        if (firstRun) {
            favs.add("com.google.android.youtube.tv");
            favs.add("com.netflix.mediaclient");
            favs.add("com.abc.ninja");
            favs.add("com.amazon.avod.thirdpartyclient");
            favs.add("com.disney.disneyplus");
            favs.add("br.com.sbt.mais");
            favs.add("org.videolan.vlc");
            favs.add("com.android.chrome");
        }
        // Garante que XCIPTV e Downloader entrem nos Destaques automaticamente
        favs.add("com.nathnetwork.xciptv");
        favs.add("com.xciptv.player");
        favs.add("com.ottrun.xciptv");
        favs.add("com.esaba.downloader");
        favs.add("com.aftvnews.downloader");
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply();
    }

    private void disableClipping(ViewGroup vg) {
        vg.setClipChildren(false);
        vg.setClipToPadding(false);
    }

    private View buildRootLayout() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setSoundEffectsEnabled(false);
        try {
            getWindow().getDecorView().setSoundEffectsEnabled(false);
        } catch (Exception ignored) {
        }
        rootScroll.setFillViewport(true);
        rootScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rootScroll.setVerticalScrollBarEnabled(false);
        disableClipping(rootScroll);

        GradientDrawable bgGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#111827"), Color.parseColor("#090D16")}
        );
        rootScroll.setBackground(bgGradient);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(32), dp(24), dp(32), dp(36));
        disableClipping(container);

        // --- 1. TOP BAR: Relógio, Data, Clima em linha única + Atalhos Rápidos ---
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        disableClipping(topBar);
        LinearLayout.LayoutParams topBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topBarLp.bottomMargin = dp(18);
        container.addView(topBar, topBarLp);

        LinearLayout clockCluster = new LinearLayout(this);
        clockCluster.setOrientation(LinearLayout.HORIZONTAL);
        clockCluster.setGravity(Gravity.CENTER_VERTICAL);
        disableClipping(clockCluster);
        LinearLayout.LayoutParams clockClusterLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );
        topBar.addView(clockCluster, clockClusterLp);

        LinearLayout timeDateBox = new LinearLayout(this);
        timeDateBox.setOrientation(LinearLayout.VERTICAL);
        clockCluster.addView(timeDateBox);

        clockTimeText = new TextView(this);
        clockTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        clockTimeText.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        clockTimeText.setTextColor(Color.parseColor("#F8FAFC"));
        clockTimeText.setText("00:00");
        timeDateBox.addView(clockTimeText);

        clockDateText = new TextView(this);
        clockDateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clockDateText.setTextColor(Color.parseColor("#94A3B8"));
        clockDateText.setSingleLine(true);
        clockDateText.setText("Carregando...");
        timeDateBox.addView(clockDateText);

        weatherText = createQuickPill("⛅ --°C • " + DEFAULT_CITY_NAME, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangeCityDialog();
            }
        });
        LinearLayout.LayoutParams weatherLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        weatherLp.leftMargin = dp(8);
        weatherLp.topMargin = dp(4);
        weatherLp.bottomMargin = dp(4);
        weatherLp.rightMargin = dp(2);
        clockCluster.addView(weatherText, weatherLp);

        LinearLayout actionsCluster = new LinearLayout(this);
        actionsCluster.setOrientation(LinearLayout.HORIZONTAL);
        actionsCluster.setGravity(Gravity.CENTER_VERTICAL);
        disableClipping(actionsCluster);
        topBar.addView(actionsCluster);

        addQuickActionButton(actionsCluster, ICON_TYPE_HDMI, "HDMI", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTvInputSource(INPUT_ID_HDMI1, "HDMI");
            }
        });

        addQuickActionButton(actionsCluster, ICON_TYPE_KEYSTONE, "Keystone", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProjectorProjectionSettings();
            }
        });

        addQuickActionButton(actionsCluster, ICON_TYPE_WIFI, "Wi-Fi", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProjectorWifiSettings();
            }
        });

        addQuickActionButton(actionsCluster, ICON_TYPE_BLUETOOTH, "Bluetooth", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProjectorBluetoothSettings();
            }
        });

        addQuickActionButton(actionsCluster, ICON_TYPE_SETTINGS, "Config", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProjectorMainSettings();
            }
        });

        // Trio estilo menu de energia do Windows: Descanso (Lua) | Reiniciar (Girar) | Desligar (Power)
        addQuickActionButton(actionsCluster, ICON_TYPE_SCREENSAVER, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScreensaverNow();
            }
        });

        addQuickActionButton(actionsCluster, ICON_TYPE_RESTART, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRestartConfirmDialog();
            }
        });

        addQuickActionButton(actionsCluster, ICON_TYPE_POWER_OFF, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPowerOffConfirmDialog();
            }
        });

        // --- 2. BARRA DE BUSCA RÁPIDA (YouTube / Google / URL) ---
        LinearLayout searchBar = createSearchBar();
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        searchLp.leftMargin = dp(6);
        searchLp.rightMargin = dp(6);
        searchLp.topMargin = dp(4);
        searchLp.bottomMargin = dp(18);
        container.addView(searchBar, searchLp);

        // --- 3. SEÇÃO DE ENTRADAS & CONEXÕES (HDMI / USB / AV / ESPELHAMENTO) ---
        TextView inputsHeader = createSectionHeader("ENTRADAS & CONEXÕES");
        container.addView(inputsHeader);

        inputsGrid = new GridLayout(this);
        inputsGrid.setColumnCount(4);
        inputsGrid.setUseDefaultMargins(false);
        disableClipping(inputsGrid);
        LinearLayout.LayoutParams inputsGridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputsGridLp.bottomMargin = dp(18);
        container.addView(inputsGrid, inputsGridLp);

        // --- 4. SEÇÃO DE FAVORITOS / STREAMING ---
        favoritesHeader = createSectionHeader("DESTAQUES & STREAMING");
        container.addView(favoritesHeader);

        favoritesGrid = new GridLayout(this);
        favoritesGrid.setColumnCount(4);
        favoritesGrid.setUseDefaultMargins(false);
        disableClipping(favoritesGrid);
        LinearLayout.LayoutParams favGridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        favGridLp.bottomMargin = dp(18);
        container.addView(favoritesGrid, favGridLp);

        // --- 5. SEÇÃO DE TODOS OS APLICATIVOS ---
        LinearLayout allHeaderRow = new LinearLayout(this);
        allHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        allHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        disableClipping(allHeaderRow);
        LinearLayout.LayoutParams allHeaderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        allHeaderLp.bottomMargin = dp(8);
        container.addView(allHeaderRow, allHeaderLp);

        TextView allHeader = createSectionHeader("TODOS OS APLICATIVOS");
        LinearLayout.LayoutParams allTitleLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );
        allHeaderRow.addView(allHeader, allTitleLp);

        TextView manageHiddenBtn = createQuickPill("Ocultos / Segure OK num app para opções", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHiddenAppsDialog();
            }
        });
        manageHiddenBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hiddenBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hiddenBtnLp.topMargin = dp(4);
        hiddenBtnLp.bottomMargin = dp(4);
        hiddenBtnLp.rightMargin = dp(6);
        allHeaderRow.addView(manageHiddenBtn, hiddenBtnLp);

        allAppsGrid = new GridLayout(this);
        allAppsGrid.setColumnCount(4);
        allAppsGrid.setUseDefaultMargins(false);
        disableClipping(allAppsGrid);
        LinearLayout.LayoutParams allGridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        container.addView(allAppsGrid, allGridLp);

        rootScroll.addView(container);
        return rootScroll;
    }

    // --- SEÇÃO DE ENTRADAS (HDMI, USB, AV RCA, Espelhamento) COM ÍCONES VETORIAIS ---
    private void populateInputsSection() {
        if (inputsGrid == null) return;
        inputsGrid.removeAllViews();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int totalHorizontalSpace = dp(76) + dp(48);
        int cardWidth = Math.max(dp(170), (dm.widthPixels - totalHorizontalSpace) / 4);
        int cardHeight = dp(88);

        boolean hdmi1Connected = isInputConnected(INPUT_ID_HDMI1);
        boolean avConnected = isInputConnected(INPUT_ID_AV1);

        View hdmiCard = createInputCard(
                ICON_TYPE_HDMI,
                "Porta HDMI",
                hdmi1Connected ? "● Sinal Conectado" : "PC / Console / TV Box",
                hdmi1Connected,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openTvInputSource(INPUT_ID_HDMI1, "HDMI");
                    }
                }
        );
        addCardToGridWithMargins(inputsGrid, hdmiCard, cardWidth, cardHeight);

        View usbCard = createInputCard(
                ICON_TYPE_USB,
                "Porta USB",
                "Pendrive / Arquivos",
                false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openUsbFileBrowser();
                    }
                }
        );
        addCardToGridWithMargins(inputsGrid, usbCard, cardWidth, cardHeight);

        View avCard = createInputCard(
                ICON_TYPE_RCA_AV,
                "Entrada AV (RCA)",
                avConnected ? "● Sinal Conectado" : "Cabo RCA Áudio/Vídeo",
                avConnected,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openTvInputSource(INPUT_ID_AV1, "AV");
                    }
                }
        );
        addCardToGridWithMargins(inputsGrid, avCard, cardWidth, cardHeight);

        View castCard = createInputCard(
                ICON_TYPE_CAST,
                "Espelhar Tela",
                "Miracast / Sem Fio",
                false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openScreenMirroring();
                    }
                }
        );
        addCardToGridWithMargins(inputsGrid, castCard, cardWidth, cardHeight);
    }

    private void addCardToGridWithMargins(GridLayout grid, View card, int width, int height) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.height = height;
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        grid.addView(card, lp);
    }

    private View createInputCard(
            int iconType,
            String titleStr,
            String subtitleStr,
            boolean isConnected,
            View.OnClickListener onClick
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.setFocusable(true);
        card.setClickable(true);

        String bgHex = isConnected ? "#132A2E" : "#182235";
        applyFocusableSurface(card, bgHex, "#24385A", dp(16));

        VectorIconView iconView = new VectorIconView(this, iconType, "#0F172A");
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconLp.rightMargin = dp(12);
        card.addView(iconView, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        card.addView(textCol);

        TextView title = new TextView(this);
        title.setText(titleStr);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setSingleLine(true);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitleStr);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(Color.parseColor(isConnected ? "#4ADE80" : "#94A3B8"));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(sub);

        card.setOnClickListener(onClick);
        return card;
    }

    private boolean isInputConnected(String inputId) {
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                int state = tim.getInputState(inputId);
                return state == TvInputManager.INPUT_STATE_CONNECTED;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void openTvInputSource(String inputId, String label) {
        try {
            Intent intent = new Intent("android.media.tv.action.SETUP_INPUTS");
            intent.putExtra("from_tv_source", true);
            intent.putExtra("android.media.tv.extra.INPUT_ID", inputId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {
        }

        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(viewIntent);
            return;
        } catch (Exception ignored) {
        }

        try {
            Intent signalBroadcast = new Intent("intent.start.signal");
            sendBroadcast(signalBroadcast);
            Toast.makeText(this, "Abrindo entrada " + label + "...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível alternar para " + label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openUsbFileBrowser() {
        if (tryStartComponent("com.droidlogic.FileBrower", "com.droidlogic.FileBrower.FileBrower")) {
            return;
        }
        PackageManager pm = getPackageManager();
        String[] usbApps = new String[]{
                "com.droidlogic.FileBrower",
                "com.droidlogic.mediacenter",
                "org.videolan.vlc"
        };
        for (String pkg : usbApps) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            }
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        Toast.makeText(this, "Gerenciador USB não encontrado.", Toast.LENGTH_SHORT).show();
    }

    private void openScreenMirroring() {
        PackageManager pm = getPackageManager();
        String[] castPackages = new String[]{
                "com.droidlogic.miracast",
                "com.ionitech.airscreen"
        };
        for (String pkg : castPackages) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            }
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        Toast.makeText(this, "App de espelhamento não encontrado.", Toast.LENGTH_SHORT).show();
    }

    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(Color.parseColor("#64748B"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = dp(6);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addQuickActionButton(LinearLayout parent, int iconType, String text, View.OnClickListener listener) {
        boolean iconOnly = TextUtils.isEmpty(text);
        boolean hasStatusDot = (iconType == ICON_TYPE_WIFI || iconType == ICON_TYPE_BLUETOOTH);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        if (iconOnly) {
            pill.setPadding(dp(8), dp(8), dp(8), dp(8));
        } else {
            pill.setPadding(dp(9), dp(8), dp(9), dp(8));
        }
        pill.setFocusable(true);
        pill.setClickable(true);
        applyFocusableSurface(pill, "#1E293B", "#334155", dp(18));

        VectorIconView icon = new VectorIconView(this, iconType, null);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.rightMargin = iconOnly ? 0 : dp(5);
        pill.addView(icon, iconLp);

        if (!iconOnly) {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setTextColor(Color.parseColor("#E2E8F0"));
            tv.setSingleLine(true);
            pill.addView(tv);

            if (hasStatusDot) {
                StatusDotView dot = new StatusDotView(this, iconType == ICON_TYPE_WIFI);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
                dotLp.leftMargin = dp(5);
                pill.addView(dot, dotLp);

                if (iconType == ICON_TYPE_WIFI) {
                    wifiStatusDot = dot;
                    wifiIconView = icon;
                    wifiLabelText = tv;
                } else {
                    btStatusDot = dot;
                    btIconView = icon;
                    btLabelText = tv;
                }
            }
        }

        pill.setOnClickListener(listener);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = iconOnly ? dp(2) : dp(2);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        lp.rightMargin = dp(2);
        parent.addView(pill, lp);
    }

    private TextView createQuickPill(String label, View.OnClickListener onClick) {
        final TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(Color.parseColor("#E2E8F0"));
        btn.setPadding(dp(13), dp(8), dp(13), dp(8));
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setGravity(Gravity.CENTER);
        applyFocusableSurface(btn, "#1E293B", "#334155", dp(18));
        btn.setOnClickListener(onClick);
        return btn;
    }

    private LinearLayout createSearchBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(13), dp(20), dp(13));
        bar.setFocusable(true);
        bar.setClickable(true);

        applyFocusableSurface(bar, "#172033", "#263554", dp(16));

        TextView icon = new TextView(this);
        icon.setText("🔍");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        bar.addView(icon);

        TextView hint = new TextView(this);
        hint.setText("  Pesquisar no YouTube, Google ou digitar endereço web (URL)...");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setTextColor(Color.parseColor("#94A3B8"));
        hint.setSingleLine(true);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );
        bar.addView(hint, hintLp);

        TextView badge = new TextView(this);
        badge.setText("Pressione OK");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTextColor(Color.parseColor("#38BDF8"));
        badge.setSingleLine(true);
        bar.addView(badge);

        bar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQuickSearchDialog();
            }
        });

        return bar;
    }

    private void applyFocusableSurface(final View view, final String normalHex, final String focusedHex, final int cornerRadiusPx) {
        final GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.parseColor(normalHex));
        normalBg.setCornerRadius(cornerRadiusPx);
        normalBg.setStroke(dp(2), Color.parseColor("#243044"));

        final GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(Color.parseColor(focusedHex));
        focusedBg.setCornerRadius(cornerRadiusPx);
        focusedBg.setStroke(dp(3), Color.parseColor("#38BDF8"));

        view.setSoundEffectsEnabled(false);
        view.setBackground(normalBg);
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? focusedBg : normalBg);
                float scale = hasFocus ? 1.03f : 1.0f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(100).start();
                v.setElevation(hasFocus ? dp(8) : 0);
                if (hasFocus) {
                    playNavigationTickSound(v);
                }
            }
        });
    }

    private void updateClock() {
        Date now = new Date();
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
        SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR"));

        clockTimeText.setText(timeFmt.format(now));
        String dateStr = dateFmt.format(now);
        if (!TextUtils.isEmpty(dateStr)) {
            dateStr = dateStr.substring(0, 1).toUpperCase() + dateStr.substring(1);
        }
        clockDateText.setText(dateStr);
    }

    // --- CLIMA DISCRETO (Open-Meteo, Zero Anúncios/Chaves) ---
    private void loadCachedWeather() {
        String city = prefs.getString(KEY_CITY_NAME, DEFAULT_CITY_NAME);
        if (prefs.contains(KEY_CACHED_TEMP)) {
            int temp = prefs.getInt(KEY_CACHED_TEMP, 22);
            int code = prefs.getInt(KEY_CACHED_CODE, 1);
            weatherText.setText(getWeatherEmoji(code) + " " + temp + "°C • " + city);
        } else {
            weatherText.setText("⛅ --°C • " + city);
        }
    }

    private String getWeatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁️";
        if (code >= 45 && code <= 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code >= 95) return "⛈️";
        return "⛅";
    }

    private void refreshWeatherAsync(final boolean force) {
        final long lastTime = prefs.getLong(KEY_CACHED_TIME, 0);
        if (!force && (System.currentTimeMillis() - lastTime) < 30 * 60 * 1000L) {
            return;
        }

        final String city = prefs.getString(KEY_CITY_NAME, DEFAULT_CITY_NAME);
        final String lat = prefs.getString(KEY_CITY_LAT, DEFAULT_CITY_LAT);
        final String lon = prefs.getString(KEY_CITY_LON, DEFAULT_CITY_LON);

        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String urlStr = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code&timezone=auto";
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject current = json.getJSONObject("current");
                    final int temp = (int) Math.round(current.getDouble("temperature_2m"));
                    final int code = current.getInt("weather_code");

                    prefs.edit()
                            .putInt(KEY_CACHED_TEMP, temp)
                            .putInt(KEY_CACHED_CODE, code)
                            .putLong(KEY_CACHED_TIME, System.currentTimeMillis())
                            .apply();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            weatherText.setText(getWeatherEmoji(code) + " " + temp + "°C • " + city);
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    private void showChangeCityDialog() {
        final EditText input = new EditText(this);
        input.setHint("Ex: Andradas, Poços de Caldas, Campinas...");
        input.setText(prefs.getString(KEY_CITY_NAME, DEFAULT_CITY_NAME));
        input.setSingleLine(true);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cidade do Clima")
                .setMessage("Digite o nome da sua cidade:")
                .setView(input)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String query = input.getText().toString().trim();
                        if (!TextUtils.isEmpty(query)) {
                            geocodeAndSaveCity(query);
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void geocodeAndSaveCity(final String cityName) {
        weatherText.setText("⏳ Buscando " + cityName + "...");
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String encoded = URLEncoder.encode(cityName, "UTF-8");
                    URL url = new URL("https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=pt&format=json");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    if (json.has("results")) {
                        JSONArray results = json.getJSONArray("results");
                        if (results.length() > 0) {
                            JSONObject first = results.getJSONObject(0);
                            final String foundName = first.getString("name");
                            final String lat = String.valueOf(first.getDouble("latitude"));
                            final String lon = String.valueOf(first.getDouble("longitude"));

                            prefs.edit()
                                    .putString(KEY_CITY_NAME, foundName)
                                    .putString(KEY_CITY_LAT, lat)
                                    .putString(KEY_CITY_LON, lon)
                                    .apply();

                            refreshWeatherAsync(true);
                            return;
                        }
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "Cidade não encontrada.", Toast.LENGTH_SHORT).show();
                            loadCachedWeather();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            loadCachedWeather();
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    // --- BUSCA RÁPIDA (YouTube / Google / URL Direta) ---
    private void showQuickSearchDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));

        final EditText input = new EditText(this);
        input.setHint("O que você quer assistir ou pesquisar?");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(input);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Busca Rápida / Abrir Site")
                .setView(box)
                .setPositiveButton("▶ Buscar no YouTube", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String q = input.getText().toString().trim();
                        if (!TextUtils.isEmpty(q)) {
                            searchOnYouTube(q);
                        }
                    }
                })
                .setNeutralButton("🌐 Google / Abrir URL", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String q = input.getText().toString().trim();
                        if (!TextUtils.isEmpty(q)) {
                            openWebOrGoogle(q);
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void searchOnYouTube(String query) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEARCH);
            intent.setPackage("com.google.android.youtube.tv");
            intent.putExtra("query", query);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try {
                String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8");
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(web);
            } catch (Exception ignored) {
            }
        }
    }

    private void openWebOrGoogle(String query) {
        try {
            String url;
            if (query.startsWith("http://") || query.startsWith("https://")) {
                url = query;
            } else if (query.contains(".") && !query.contains(" ")) {
                url = "https://" + query;
            } else {
                url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8");
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir o navegador.", Toast.LENGTH_SHORT).show();
        }
    }

    // --- ATALHOS NATIVOS DO PROJETOR t950s_be311 ---
    private void openProjectorProjectionSettings() {
        if (!tryStartAction("action.htcsettings.prjsettings")) {
            if (!tryStartComponent("com.htc.htcsettings", "com.htc.activity.ProjectionActivity")) {
                openProjectorMainSettings();
            }
        }
    }

    private void openProjectorWifiSettings() {
        if (!tryStartAction("action.htcsettings.networking")) {
            if (!tryStartComponent("com.htc.htcsettings", "com.htc.activity.NetWorkActivity")) {
                tryStartAction(Settings.ACTION_WIFI_SETTINGS);
            }
        }
    }

    private void openProjectorBluetoothSettings() {
        if (!tryStartAction("action.htcsettings.bluetooth")) {
            if (!tryStartComponent("com.htc.htcsettings", "com.htc.activity.BluetoothSettingActivity")) {
                tryStartAction(Settings.ACTION_BLUETOOTH_SETTINGS);
            }
        }
    }

    private void openProjectorMainSettings() {
        if (!tryStartComponent("com.htc.htcsettings", "com.htc.activity.HomeActivity")) {
            if (!tryStartAction(Settings.ACTION_SETTINGS)) {
                Toast.makeText(this, "Configurações não encontradas", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startScreensaverNow() {
        try {
            Intent intent = new Intent(this, ScreensaverActivity.class);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível iniciar a tela de descanso.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRestartConfirmDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reiniciar Projetor")
                .setMessage("Deseja reiniciar o sistema agora?")
                .setPositiveButton("Reiniciar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performSystemReboot();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showPowerOffConfirmDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Desligar Projetor")
                .setMessage("Deseja desligar o projetor agora?")
                .setPositiveButton("Desligar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performSystemShutdown();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void performSystemReboot() {
        Toast.makeText(this, "Reiniciando projetor...", Toast.LENGTH_SHORT).show();
        disableBluetoothQuietly();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                pm.reboot(null);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(Intent.ACTION_REBOOT);
            intent.putExtra("nowait", 1);
            intent.putExtra("interval", 1);
            intent.putExtra("window", 0);
            sendBroadcast(intent);
            return;
        } catch (Exception ignored) {
        }
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "svc power reboot || reboot"});
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível reiniciar.", Toast.LENGTH_SHORT).show();
        }
    }

    private void performSystemShutdown() {
        Toast.makeText(this, "Desligando projetor...", Toast.LENGTH_SHORT).show();
        disableBluetoothQuietly();
        try {
            Intent shutdown = new Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN");
            shutdown.putExtra("android.intent.extra.KEY_CONFIRM", false);
            shutdown.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(shutdown);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent shutdown2 = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
            shutdown2.putExtra("android.intent.extra.KEY_CONFIRM", false);
            shutdown2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(shutdown2);
            return;
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                java.lang.reflect.Method m = pm.getClass().getMethod("shutdown", boolean.class, String.class, boolean.class);
                m.invoke(pm, false, "userrequested", false);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "svc power shutdown || reboot -p"});
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível desligar.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean tryStartAction(String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryStartComponent(String pkg, String cls) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- CARREGAMENTO E GRADE DE APLICATIVOS ---
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> discovered = new LinkedHashMap<String, AppEntry>();

        Set<String> ignoredSet = new HashSet<String>();
        Collections.addAll(ignoredSet, DEFAULT_IGNORED_PACKAGES);

        Set<String> userHidden = prefs.getStringSet(KEY_HIDDEN, new HashSet<String>());
        Set<String> userFavorites = prefs.getStringSet(KEY_FAVORITES, new HashSet<String>());

        Map<String, Integer> priorityMap = new HashMap<String, Integer>();
        for (int i = 0; i < DEFAULT_PRIORITY_PACKAGES.length; i++) {
            priorityMap.put(DEFAULT_PRIORITY_PACKAGES[i], 100 - i);
        }

        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN, null);
        leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> tvApps = pm.queryIntentActivities(leanbackIntent, 0);
        if (tvApps != null) {
            for (ResolveInfo ri : tvApps) {
                addResolveInfo(pm, ri, discovered, ignoredSet, userHidden, userFavorites, priorityMap);
            }
        }

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> stdApps = pm.queryIntentActivities(mainIntent, 0);
        if (stdApps != null) {
            for (ResolveInfo ri : stdApps) {
                addResolveInfo(pm, ri, discovered, ignoredSet, userHidden, userFavorites, priorityMap);
            }
        }

        List<AppEntry> favList = new ArrayList<AppEntry>();
        List<AppEntry> allList = new ArrayList<AppEntry>();

        for (AppEntry entry : discovered.values()) {
            if (entry.isFavorite) {
                favList.add(entry);
            }
            allList.add(entry);
        }

        Comparator<AppEntry> sorter = new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (a.priorityScore != b.priorityScore) {
                    return b.priorityScore - a.priorityScore;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        };

        Collections.sort(favList, sorter);
        Collections.sort(allList, sorter);

        populateGrid(favoritesGrid, favList, true);
        favoritesHeader.setVisibility(favList.isEmpty() ? View.GONE : View.VISIBLE);
        favoritesGrid.setVisibility(favList.isEmpty() ? View.GONE : View.VISIBLE);

        populateGrid(allAppsGrid, allList, false);
    }

    private void addResolveInfo(
            PackageManager pm,
            ResolveInfo ri,
            Map<String, AppEntry> discovered,
            Set<String> ignoredSet,
            Set<String> userHidden,
            Set<String> userFavorites,
            Map<String, Integer> priorityMap
    ) {
        if (ri == null || ri.activityInfo == null) return;
        String pkg = ri.activityInfo.packageName;
        if (TextUtils.isEmpty(pkg) || ignoredSet.contains(pkg) || userHidden.contains(pkg)) {
            return;
        }
        if (discovered.containsKey(pkg)) {
            return;
        }

        AppEntry entry = new AppEntry();
        entry.packageName = pkg;
        entry.activityName = ri.activityInfo.name;
        CharSequence labelCs = ri.loadLabel(pm);
        entry.label = (labelCs != null) ? labelCs.toString().trim() : pkg;

        Drawable icon = null;
        try {
            icon = ri.activityInfo.loadIcon(pm);
            if (icon == null) {
                icon = pm.getApplicationIcon(pkg);
            }
        } catch (Exception ignored) {
        }
        entry.icon = icon;
        entry.isFavorite = userFavorites.contains(pkg);
        entry.priorityScore = priorityMap.containsKey(pkg) ? priorityMap.get(pkg) : 0;

        discovered.put(pkg, entry);
    }

    private void populateGrid(GridLayout grid, List<AppEntry> apps, boolean isFavRow) {
        grid.removeAllViews();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int totalHorizontalSpace = dp(76) + dp(48);
        int cardWidth = Math.max(dp(170), (dm.widthPixels - totalHorizontalSpace) / 4);
        int cardHeight = isFavRow ? dp(100) : dp(92);

        for (int i = 0; i < apps.size(); i++) {
            final AppEntry app = apps.get(i);
            View card = createAppCard(app, cardWidth, cardHeight);
            addCardToGridWithMargins(grid, card, cardWidth, cardHeight);
        }
    }

    private View createAppCard(final AppEntry app, int width, int height) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setFocusable(true);
        card.setClickable(true);

        String normalColor = getBrandSurfaceColor(app.packageName);
        applyFocusableSurface(card, normalColor, "#23324D", dp(16));

        ImageView iconView = new ImageView(this);
        if (app.icon != null) {
            iconView.setImageDrawable(app.icon);
        }
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconLp.rightMargin = dp(12);
        card.addView(iconView, iconLp);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );
        card.addView(textBox, textLp);

        TextView title = new TextView(this);
        title.setText(app.label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textBox.addView(title);

        if (app.isFavorite) {
            TextView sub = new TextView(this);
            sub.setText("★ Favorito");
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sub.setTextColor(Color.parseColor("#38BDF8"));
            sub.setSingleLine(true);
            textBox.addView(sub);
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchApp(app);
            }
        });

        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showAppOptionsDialog(app);
                return true;
            }
        });

        return card;
    }

    private String getBrandSurfaceColor(String pkg) {
        if ("com.google.android.youtube.tv".equals(pkg) || "com.google.android.youtube.tvmusic".equals(pkg)) {
            return "#28151B";
        }
        if ("com.netflix.mediaclient".equals(pkg) || "com.abc.ninja".equals(pkg)) {
            return "#241318";
        }
        if ("com.amazon.avod.thirdpartyclient".equals(pkg) || "com.disney.disneyplus".equals(pkg)) {
            return "#132238";
        }
        return "#161F30";
    }

    private void launchApp(AppEntry app) {
        try {
            PackageManager pm = getPackageManager();
            Intent leanback = pm.getLeanbackLaunchIntentForPackage(app.packageName);
            if (leanback != null) {
                leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(leanback);
                return;
            }
            Intent std = pm.getLaunchIntentForPackage(app.packageName);
            if (std != null) {
                std.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(std);
                return;
            }
            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.setComponent(new ComponentName(app.packageName, app.activityName));
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(explicit);
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao abrir " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppOptionsDialog(final AppEntry app) {
        final String favOption = app.isFavorite
                ? "⭐ Remover dos Destaques (Favoritos)"
                : "⭐ Fixar nos Destaques (Favoritos)";
        String[] options = new String[]{
                "▶ Abrir " + app.label,
                favOption,
                "🙈 Ocultar aplicativo da Tela Inicial",
                "ℹ️ Informações / Forçar parada / Desinstalar"
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            launchApp(app);
                        } else if (which == 1) {
                            toggleFavorite(app.packageName);
                        } else if (which == 2) {
                            hidePackage(app.packageName);
                        } else if (which == 3) {
                            openAppInfo(app.packageName);
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void toggleFavorite(String pkg) {
        Set<String> current = new HashSet<String>(prefs.getStringSet(KEY_FAVORITES, new HashSet<String>()));
        if (current.contains(pkg)) {
            current.remove(pkg);
        } else {
            current.add(pkg);
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply();
        loadApps();
    }

    private void hidePackage(String pkg) {
        Set<String> hidden = new HashSet<String>(prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        hidden.add(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        loadApps();
        Toast.makeText(this, "Aplicativo ocultado. Use o botão 'Ocultos' para restaurar.", Toast.LENGTH_SHORT).show();
    }

    private void showHiddenAppsDialog() {
        final Set<String> hidden = new HashSet<String>(prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Nenhum aplicativo está oculto no momento.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] pkgArray = hidden.toArray(new String[0]);
        String[] labels = new String[pkgArray.length];
        PackageManager pm = getPackageManager();
        for (int i = 0; i < pkgArray.length; i++) {
            try {
                CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkgArray[i], 0));
                labels[i] = (cs != null ? cs.toString() : pkgArray[i]) + " (Clique para reexibir)";
            } catch (Exception e) {
                labels[i] = pkgArray[i];
            }
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Aplicativos Ocultos")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        hidden.remove(pkgArray[which]);
                        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
                        loadApps();
                    }
                })
                .setPositiveButton("Reexibir Todos", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().remove(KEY_HIDDEN).apply();
                        loadApps();
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void openAppInfo(String pkg) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir detalhes do app.", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
