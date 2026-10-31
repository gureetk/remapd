package io.github.remapd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart, asks Android to start remapd once the TV is on Wi-Fi (see StartJob). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Starter.isSetUp(context)) {
            StartJob.schedule(context);
        }
    }
}
