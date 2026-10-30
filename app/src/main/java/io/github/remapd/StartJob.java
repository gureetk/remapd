package io.github.remapd;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;
import android.util.Log;

/**
 * Starts remapd after a restart, once the TV is on Wi-Fi (wireless debugging
 * needs it). If that doesn't work yet, Android runs it again later, a few
 * times per restart.
 */
public class StartJob extends JobService {
    private static final String TAG = "remapd";
    private static final int JOB_ID = 1;
    private static final int MAX_ATTEMPTS = 6;

    static void schedule(Context context) {
        NetworkRequest wifi = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // the TV itself is enough
                .build();
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, StartJob.class))
                .setRequiredNetwork(wifi)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Starter.isSetUp(this)) {
            return false;
        }
        int attempt = nextAttempt();
        Starter.ensureRunning(this, getString(R.string.reason_boot), null, result -> {
            boolean retry = result.worthRetrying() && attempt < MAX_ATTEMPTS;
            Log.i(TAG, "start after restart, attempt " + attempt + ": " + result.outcome
                    + (retry ? " (will try again)" : ""));
            jobFinished(params, retry);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // Wi-Fi went away: try again when it's back
    }

    /** Counts attempts since the last restart. */
    private int nextAttempt() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        SharedPreferences prefs = getSharedPreferences("remapd", MODE_PRIVATE);
        int attempt = prefs.getInt("attempt_boot", -1) == boot ? prefs.getInt("attempt", 0) + 1 : 1;
        prefs.edit().putInt("attempt_boot", boot).putInt("attempt", attempt).apply();
        return attempt;
    }
}
