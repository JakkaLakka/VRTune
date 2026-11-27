package com.vrtune.optimizer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Turns the headset's Wireless debugging back on without a PC.
 * Needs WRITE_SECURE_SETTINGS, which VRTune grants itself (via adb "pm grant") the first time it connects.
 */
final class WirelessDebug {
    static final String KEY = "adb_wifi_enabled";

    static boolean canToggle(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), KEY, 0) == 1;
    }

    /** Returns true if Wireless debugging is (now) on. */
    static boolean enable(Context c) {
        if (isOn(c)) return true;
        if (!canToggle(c)) return false;
        try {
            Settings.Global.putInt(c.getContentResolver(), KEY, 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Re-enables Wireless debugging when the headset finishes booting. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("auto_wireless", true))
                enable(context);
        }
    }
}
