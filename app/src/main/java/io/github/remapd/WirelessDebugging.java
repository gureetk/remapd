package io.github.remapd;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The developer settings involved. Wireless debugging is the
 * "adb_wifi_enabled" global setting. Android switches it back off by itself
 * when it can't use the current network: not on Wi-Fi, or on a Wi-Fi access
 * point nobody has allowed wireless debugging on yet, in which case the TV asks.
 *
 * <p>When the app switches it on, it notes that first, so wireless debugging
 * isn't left on if the app is stopped halfway, or if the TV's question about
 * the network gets answered after the app stopped waiting (see StartJob).
 * That note is written with commit(), not apply(), so it's saved before
 * wireless debugging changes.
 */
@SuppressLint("ApplySharedPref")
final class WirelessDebugging {
    private static final String WIRELESS = "adb_wifi_enabled";
    private static final String ON_BY_APP = "wireless_on_by_app";
    private static final String AWAITING_APPROVAL = "wireless_awaiting_approval";

    private WirelessDebugging() {
    }

    static boolean isOn(ContentResolver cr) {
        return Settings.Global.getInt(cr, WIRELESS, 0) == 1;
    }

    /** remapd runs under adbd, which stops (and takes remapd with it) when USB debugging is off. */
    static boolean usbDebuggingOn(ContentResolver cr) {
        return Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** Whether the TV is connected to Wi-Fi, even if another network (Ethernet) is the main one. */
    @SuppressWarnings("deprecation")   // getAllNetworks: still the simplest way to look at every network
    static boolean onWifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return false;
    }

    /** On, and not because the app left it on: someone turned it on themselves. */
    static boolean onByUser(Context c) {
        return isOn(c.getContentResolver()) && !onByApp(c);
    }

    /** The app switched it on and hasn't switched it off yet. */
    static boolean onByApp(Context c) {
        return prefs(c).getBoolean(ON_BY_APP, false);
    }

    static boolean awaitingApproval(Context c) {
        return prefs(c).getBoolean(AWAITING_APPROVAL, false);
    }

    /** Switches it on for the app. Needs WRITE_SECURE_SETTINGS. */
    static void turnOnForApp(Context c) {
        prefs(c).edit().putBoolean(ON_BY_APP, true).putBoolean(AWAITING_APPROVAL, false).commit();
        StartJob.scheduleCleanup(c);   // switches it off later if the app is stopped first
        set(c.getContentResolver(), true);
    }

    /** Switches it off again, and checks it stays off. Blocks for a few seconds. */
    static void turnOffForApp(Context c) {
        ContentResolver cr = c.getContentResolver();
        set(cr, false);
        // Android may still be finishing switching it on (Android 11 polls adbd
        // for the port) and turn it back on just after; check again.
        for (int i = 0; i < 2; i++) {
            SystemClock.sleep(1500);
            if (isOn(cr)) {
                set(cr, false);
            }
        }
        forget(c);
    }

    /**
     * The TV is asking whether to allow wireless debugging on this access
     * point and nobody answered in time. Android has switched it off; keep the
     * note, and have StartJob finish the job if someone allows it later.
     */
    static void awaitApproval(Context c) {
        prefs(c).edit().putBoolean(ON_BY_APP, true).putBoolean(AWAITING_APPROVAL, true).commit();
        StartJob.scheduleOnApproval(c);
    }

    /** Nothing left switched on by the app. */
    static void forget(Context c) {
        prefs(c).edit().putBoolean(ON_BY_APP, false).putBoolean(AWAITING_APPROVAL, false).commit();
        StartJob.cancelFollowUps(c);
    }

    /** Switches it off without touching the app's note (for setup, which switches off the user's). */
    static void switchOff(ContentResolver cr) {
        set(cr, false);
    }

    private static void set(ContentResolver cr, boolean on) {
        Settings.Global.putInt(cr, WIRELESS, on ? 1 : 0);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("remapd", Context.MODE_PRIVATE);
    }
}
