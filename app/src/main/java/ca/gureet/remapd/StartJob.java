package ca.gureet.remapd;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * The app's background jobs:
 * <ul>
 * <li>START: after a restart, starts remapd once there's a network. If that
 *     doesn't work yet (no Wi-Fi yet, say), Android runs it again later, a few
 *     times per restart.</li>
 * <li>CLEANUP: a few minutes after the app switches wireless debugging on, in
 *     case the app is stopped before it switches it off again.</li>
 * <li>ON_APPROVAL: when the wireless debugging setting changes, in case the
 *     TV's "Allow wireless debugging on this network?" gets answered after the
 *     app stopped waiting.</li>
 * </ul>
 * The last two finish what the app was doing and switch wireless debugging off.
 */
public class StartJob extends JobService {
    private static final String TAG = "remapd";
    private static final int START = 1;
    private static final int CLEANUP = 2;
    private static final int ON_APPROVAL = 3;
    private static final int MAX_ATTEMPTS = 6;

    static void schedule(Context context) {
        // Any network will do here; StartJob checks for Wi-Fi itself, which also
        // works when Ethernet is the main network and Wi-Fi is connected too.
        scheduler(context).schedule(new JobInfo.Builder(START, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build());
    }

    static void scheduleCleanup(Context context) {
        scheduler(context).schedule(new JobInfo.Builder(CLEANUP, component(context))
                .setMinimumLatency(5 * 60_000)
                .setOverrideDeadline(10 * 60_000)
                .build());
    }

    static void scheduleOnApproval(Context context) {
        scheduler(context).schedule(new JobInfo.Builder(ON_APPROVAL, component(context))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Global.getUriFor("adb_wifi_enabled"), 0))
                .setTriggerContentUpdateDelay(1000)
                .build());
    }

    static void cancelFollowUps(Context context) {
        JobScheduler scheduler = scheduler(context);
        scheduler.cancel(CLEANUP);
        scheduler.cancel(ON_APPROVAL);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() != START) {
            Starter.followUp(this, result -> jobFinished(params, false));
            return true;
        }
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
        return params.getJobId() == START;   // the network went away: try again when it's back
    }

    /** Counts start attempts since the last restart. */
    private int nextAttempt() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        SharedPreferences prefs = getSharedPreferences("remapd", MODE_PRIVATE);
        int attempt = prefs.getInt("attempt_boot", -1) == boot ? prefs.getInt("attempt", 0) + 1 : 1;
        prefs.edit().putInt("attempt_boot", boot).putInt("attempt", attempt).apply();
        return attempt;
    }

    private static JobScheduler scheduler(Context context) {
        return context.getSystemService(JobScheduler.class);
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, StartJob.class);
    }
}
