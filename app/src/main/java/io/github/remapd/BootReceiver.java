package io.github.remapd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Usually Shizuku isn't up yet at boot; it starts this app itself once it is
 * (see Starter). This only covers Shizuku already running by then.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Starter.hasPermission()) {
            Starter.ensureRunning(context.getString(R.string.reason_boot));
        }
    }
}
