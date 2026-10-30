package io.github.remapd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Runs remapd through Shizuku, which gives this app the same rights as `adb shell`.
 *
 * Shizuku hands its binder to every app that uses it each time it starts,
 * including right after the device restarts, and starts the app's process to
 * do so. That's when remapd gets started here, so nothing has to stay running.
 */
final class Starter {
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    /** What a command printed (stdout and stderr) and its exit status; -1 if it couldn't run. */
    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    interface Callback {
        void done(Result result);
    }

    private static final String TAG = "remapd";
    private static final int PERMISSION_REQUEST = 1;
    private static final long TIMEOUT_SECONDS = 20;
    private static final long ENSURE_DEBOUNCE_MS = 3000;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    @SuppressLint("StaticFieldLeak")   // the application context, which lives as long as the process
    private static Context app;
    private static SharedPreferences prefs;
    private static long lastEnsure;   // only touched on the main thread

    private Starter() {
    }

    static void init(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("remapd", Context.MODE_PRIVATE);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            if (hasPermission()) {
                ensureRunning(app.getString(R.string.reason_shizuku));
            }
            changed();
        });
        Shizuku.addBinderDeadListener(Starter::changed);
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ensureRunning(app.getString(R.string.reason_allowed));
            }
            changed();
        });
    }

    // ---- Shizuku state -------------------------------------------------

    static boolean shizukuRunning() {
        return Shizuku.pingBinder();
    }

    static boolean hasPermission() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the user picked "deny" in a way Shizuku won't ask about again. */
    static boolean permissionDeniedForGood() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.shouldShowRequestPermissionRationale();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean shizukuTooOld() {
        try {
            return Shizuku.pingBinder() && Shizuku.isPreV11();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't ask Shizuku for access", e);
        }
    }

    static boolean shizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static Intent shizukuLaunchIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(SHIZUKU_PACKAGE);
        return intent != null ? intent : pm.getLaunchIntentForPackage(SHIZUKU_PACKAGE);
    }

    // ---- remapd ---------------------------------------------------------

    /** Starts remapd unless it's already running. Call on the main thread. */
    static void ensureRunning(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (lastEnsure != 0 && now - lastEnsure < ENSURE_DEBOUNCE_MS) {
            return;
        }
        lastEnsure = now;
        worker.execute(() -> {
            Result r = runNow(Commands.ENSURE);
            remember(reason, r);
            changed();
        });
    }

    /** Runs a shell command as the shell user, off the main thread; the callback runs on the main thread. */
    static void run(String command, Callback callback) {
        worker.execute(() -> {
            Result r = runNow(command);
            main.post(() -> callback.done(r));
        });
    }

    private static Result runNow(String command) {
        if (!hasPermission()) {
            return new Result(-1, app.getString(R.string.error_no_access));
        }
        final IRemoteProcess process;
        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            process = service.newProcess(Commands.shell(command), null, null);
        } catch (Exception e) {
            Log.w(TAG, "Shizuku couldn't run a command", e);
            return new Result(-1, app.getString(R.string.error_shizuku, e.toString()));
        }
        ScheduledFuture<?> killer = timer.schedule(() -> destroy(process), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            close(process.getOutputStream());
            close(process.getErrorStream());
            String output;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream())) {
                output = readAll(in);
            }
            int code = process.waitFor();
            return new Result(code, output.trim());
        } catch (Exception e) {
            Log.w(TAG, "command failed", e);
            return new Result(-1, app.getString(R.string.error_command, e.toString()));
        } finally {
            killer.cancel(false);
            destroy(process);
        }
    }

    // ---- last automatic start, for the screen -------------------------

    private static void remember(String reason, Result r) {
        String summary = Commands.summarize(r.output);
        if (summary.equals("started")) {
            summary = app.getString(R.string.started);
        }
        prefs.edit()
                .putLong("last_time", System.currentTimeMillis())
                .putString("last_reason", reason)
                .putString("last_result", summary)
                .apply();
    }

    static String lastSummary(Context context) {
        long time = prefs.getLong("last_time", 0);
        if (time == 0) {
            return context.getString(R.string.last_none);
        }
        String when = DateFormat.getMediumDateFormat(context).format(new Date(time)) + " "
                + DateFormat.getTimeFormat(context).format(new Date(time));
        return context.getString(R.string.last_format, when,
                prefs.getString("last_reason", ""), prefs.getString("last_result", ""));
    }

    // ---- listeners -------------------------------------------------------

    static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void changed() {
        main.post(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    // ---- helpers ---------------------------------------------------------

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void close(ParcelFileDescriptor pfd) {
        try {
            if (pfd != null) {
                pfd.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void destroy(IRemoteProcess process) {
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
    }
}
