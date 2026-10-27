package com.gabriel.projectorhome;

import android.service.dreams.DreamService;
import android.view.View;
import android.view.WindowManager;

/**
 * Serviço oficial de Tela de Descanso do Android (DreamService).
 * É acionado automaticamente pelo sistema quando o projetor fica ocioso.
 */
public class ProjectorDreamService extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Qualquer tecla do controle remoto encerra imediatamente o descanso
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);

        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        setContentView(new ScreensaverView(this));
    }
}
