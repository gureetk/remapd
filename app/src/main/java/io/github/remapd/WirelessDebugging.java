package io.github.remapd;

import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

/**
 * The developer settings involved. Wireless debugging is the
 * "adb_wifi_enabled" global setting; Android switches it back off by itself
 * when it can't use the current network (not Wi-Fi, or a network nobody has
 * allowed wireless debugging on yet, in which case the TV asks).
 */
final class WirelessDebugging {
    private static final String WIRELESS = "adb_wifi_enabled";

    private WirelessDebugging() {
    }

    static boolean isOn(ContentResolver cr) {
        return Settings.Global.getInt(cr, WIRELESS, 0) == 1;
    }

    /** Needs WRITE_SECURE_SETTINGS. */
    static void set(ContentResolver cr, boolean on) {
        Settings.Global.putInt(cr, WIRELESS, on ? 1 : 0);
    }

    /** remapd runs under adbd, which stops (and takes remapd with it) when USB debugging is off. */
    static boolean usbDebuggingOn(ContentResolver cr) {
        return Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1;
    }

    static boolean onWifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
}
