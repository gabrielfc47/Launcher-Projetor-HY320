package com.gabriel.projectorhome;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.provider.Settings;

/**
 * Receptor de Inicialização (Direct Boot Aware):
 * Ao ligar ou reiniciar o projetor, liga APENAS o Wi-Fi e mantém o Bluetooth desligado.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                wm.setWifiEnabled(true);
            }
        } catch (Exception ignored) {
        }

        try {
            Settings.Global.putInt(context.getContentResolver(), Settings.Global.BLUETOOTH_ON, 0);
        } catch (Exception ignored) {
        }

        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) {
                bt.disable();
            }
        } catch (Exception ignored) {
        }

        VolumeOverlayService.ensureStarted(context);
    }
}
