package io.github.remapd;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.InputDevice;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.remapd.adb.AdbAuthException;
import io.github.remapd.adb.AdbConnection;
import io.github.remapd.adb.AdbIdentity;

/**
 * Starts remapd, and runs the app's other commands, through adb on the TV itself.
 *
 * <p>Each time, it switches wireless debugging on (if it's off), finds its port,
 * signs in with the app's key over TLS, runs the command, and switches
 * wireless debugging back off. remapd keeps running afterwards: adbd only stops
 * when USB debugging is off too.
 *
 * <p>The key is allowed once, during setup: `adb tcpip 5555` on a computer
 * opens a plain TCP port, the app connects to it, and the TV asks "Allow USB
 * debugging?". "Always allow" saves the key in the same list that wireless
 * debugging checks, so no pairing is needed.
 */
final class Starter {
    enum Outcome {
        OK, NOT_SET_UP, USB_DEBUGGING_OFF, NO_WIFI, NETWORK_NOT_ALLOWED, NOT_FOUND,
        KEY_REJECTED, NO_SETUP_PORT, NOT_ALLOWED, FAILED
    }

    /** What a command printed (OK), or what went wrong. */
    static final class Result {
        final Outcome outcome;
        final String output;

        Result(Outcome outcome, String output) {
            this.outcome = outcome;
            this.output = output;
        }

        boolean ok() {
            return outcome == Outcome.OK;
        }

        /** Could work if tried again in a while, without anyone doing anything. */
        boolean worthRetrying() {
            return outcome == Outcome.NO_WIFI || outcome == Outcome.NOT_FOUND || outcome == Outcome.FAILED;
        }
    }

    interface Progress {
        void step(String message);
    }

    interface Callback {
        void done(Result result);
    }

    /** remapd.c's VIRT_NAME. */
    static final String VIRTUAL_REMOTE = "remapd virtual remote";

    private static final String TAG = "remapd";
    private static final int SETUP_PORT = 5555;
    private static final int PROMPT_TIMEOUT_MS = 120_000;
    private static final int COMMAND_TIMEOUT_MS = 30_000;
    private static final long FIND_TIMEOUT_MS = 30_000;
    private static final long NETWORK_APPROVAL_TIMEOUT_MS = 120_000;
    private static final long AFTER_REJECTION_MS = 5_000;
    private static final String ADBD_SERVICE = "adbd_service";

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static int pending;   // tasks queued or running; only touched on the main thread

    private Starter() {
    }

    // ---- state ------------------------------------------------------------

    static boolean isSetUp(Context context) {
        return AppKey.exists(context) && canChangeSettings(context);
    }

    static boolean canChangeSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether something is queued or running. Call on the main thread. */
    static boolean busy() {
        return pending > 0;
    }

    /**
     * Whether remapd's virtual remote exists, which means remapd is running.
     * It only has one when its config has send lines.
     */
    static boolean virtualRemotePresent(Context context) {
        InputManager im = context.getSystemService(InputManager.class);
        for (int id : im.getInputDeviceIds()) {
            InputDevice device = im.getInputDevice(id);
            if (device != null && VIRTUAL_REMOTE.equals(device.getName())) {
                return true;
            }
        }
        return false;
    }

    /** The remapd that comes with the app, where Android unpacked it (the shell user can read it there). */
    static String bundledRemapd(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libremapd.so";
    }

    /** Whether remapd was started successfully since the TV last restarted. */
    static boolean startedThisBoot(Context c) {
        return prefs(c).getInt("ok_boot", -1) == bootCount(c);
    }

    // ---- running things in the background -------------------------------

    /** Starts remapd unless it's already running. The callback runs on the main thread. */
    static void ensureRunning(Context context, String reason, Progress progress, Callback callback) {
        Context app = context.getApplicationContext();
        submit(() -> ensureBlocking(app, reason, progress), callback);
    }

    static void run(Context context, String command, Progress progress, Callback callback) {
        Context app = context.getApplicationContext();
        submit(() -> runBlocking(app, command, progress), callback);
    }

    static void setUp(Context context, Progress progress, Callback callback) {
        Context app = context.getApplicationContext();
        submit(() -> setUpBlocking(app, progress), callback);
    }

    /** Deals with wireless debugging the app left on (see WirelessDebugging). */
    static void followUp(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        submit(() -> followUpBlocking(app), callback);
    }

    /** Call on the main thread. */
    private static void submit(Callable<Result> task, Callback callback) {
        pending++;
        changed();
        worker.execute(() -> {
            Result result;
            try {
                result = task.call();
            } catch (Exception e) {
                Log.w(TAG, "failed", e);
                result = new Result(Outcome.FAILED, e.toString());
            }
            Result r = result;
            main.post(() -> {
                pending--;
                if (callback != null) {
                    callback.done(r);
                }
                changed();
            });
        });
    }

    // ---- the work -----------------------------------------------------------

    static Result ensureBlocking(Context c, String reason, Progress progress) {
        Result r = virtualRemotePresent(c)
                ? new Result(Outcome.OK, c.getString(R.string.already_running))
                : runBlocking(c, Commands.ensure(bundledRemapd(c)), progress);
        remember(c, reason, r);
        return r;
    }

    /** Runs a shell command as the shell user, over wireless debugging. */
    static Result runBlocking(Context c, String command, Progress progress) {
        Progress p = progress != null ? progress : message -> { };
        if (session != null) {
            // The buttons screen has a connection open: use it.
            try {
                p.step(c.getString(R.string.step_running));
                String out = session.adb.run(Commands.service(command), COMMAND_TIMEOUT_MS, false);
                sessionUsed = SystemClock.elapsedRealtime();
                return new Result(Outcome.OK, out.trim());
            } catch (IOException e) {
                Log.w(TAG, "the open connection failed; connecting again", e);
                closeSessionNow(c);
            }
        }
        Link link;
        try {
            link = connect(c, p);
        } catch (Failure f) {
            return f.result;
        }
        try {
            p.step(c.getString(R.string.step_running));
            String out = link.adb.run(Commands.service(command), COMMAND_TIMEOUT_MS, false);
            return new Result(Outcome.OK, out.trim());
        } catch (IOException e) {
            Log.w(TAG, "adb failed", e);
            return new Result(Outcome.FAILED, c.getString(R.string.error_failed, e.getMessage()));
        } finally {
            release(c, link);
        }
    }

    /** An open connection to adbd, and whether wireless debugging was on before. */
    private static final class Link {
        final AdbConnection adb;
        final boolean usersOwn;

        Link(AdbConnection adb, boolean usersOwn) {
            this.adb = adb;
            this.usersOwn = usersOwn;
        }
    }

    /**
     * Connects over wireless debugging, switching it on if it's off. Someone
     * else's wireless debugging is left as it is; the app's own is switched
     * off again by {@link #release}, or straight away if this fails.
     */
    private static Link connect(Context c, Progress p) throws Failure {
        ContentResolver cr = c.getContentResolver();
        if (!isSetUp(c)) {
            throw new Failure(Outcome.NOT_SET_UP, c.getString(R.string.error_not_set_up));
        }
        if (!WirelessDebugging.usbDebuggingOn(cr)) {
            throw new Failure(Outcome.USB_DEBUGGING_OFF, c.getString(R.string.error_usb_off));
        }
        AdbIdentity id;
        try {
            id = AppKey.load(c);
        } catch (GeneralSecurityException | IOException e) {
            Log.w(TAG, "can't read the adb key", e);
            throw new Failure(Outcome.NOT_SET_UP, c.getString(R.string.error_key, e.getMessage()));
        }
        if (id == null) {
            throw new Failure(Outcome.NOT_SET_UP, c.getString(R.string.error_not_set_up));
        }

        boolean usersOwn = WirelessDebugging.onByUser(c);
        if (!usersOwn && !WirelessDebugging.isOn(cr) && !WirelessDebugging.onWifi(c)) {
            throw new Failure(Outcome.NO_WIFI, c.getString(R.string.error_no_wifi));
        }
        boolean connected = false;
        boolean awaitingApproval = false;
        try {
            if (!usersOwn) {
                if (!WirelessDebugging.isOn(cr)) {
                    p.step(c.getString(R.string.step_wireless_on));
                }
                WirelessDebugging.turnOnForApp(c);
            }
            p.step(c.getString(R.string.step_finding));
            AdbConnection adb = findAndConnect(c, cr, id, p);
            connected = true;
            return new Link(adb, usersOwn);
        } catch (Failure f) {
            awaitingApproval = f.result.outcome == Outcome.NETWORK_NOT_ALLOWED;
            throw f;
        } catch (AdbAuthException e) {
            Log.w(TAG, "adbd rejected the key", e);
            throw new Failure(Outcome.KEY_REJECTED, c.getString(R.string.error_key_rejected));
        } catch (IOException e) {
            Log.w(TAG, "adb failed", e);
            throw new Failure(Outcome.FAILED, c.getString(R.string.error_failed, e.getMessage()));
        } catch (SecurityException e) {   // the permission was taken away
            Log.w(TAG, "can't change settings", e);
            throw new Failure(Outcome.NOT_SET_UP, c.getString(R.string.error_not_set_up));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Outcome.FAILED, c.getString(R.string.error_failed, "interrupted"));
        } finally {
            if (!connected && !usersOwn) {
                try {
                    if (awaitingApproval) {
                        WirelessDebugging.awaitApproval(c);
                    } else {
                        WirelessDebugging.turnOffForApp(c);
                    }
                } catch (SecurityException ignored) {
                }
            }
        }
    }

    private static void release(Context c, Link link) {
        try {
            link.adb.close();
        } catch (IOException ignored) {
        }
        if (!link.usersOwn) {
            try {
                WirelessDebugging.turnOffForApp(c);
            } catch (SecurityException ignored) {
            }
        }
    }

    // ---- the buttons screen keeps one connection open -------------------------

    private static final long SESSION_IDLE_MS = 10 * 60_000;
    private static Link session;   // worker thread only
    private static long sessionUsed;   // worker thread only

    /** Connects for the buttons screen, and stays connected until {@link #closeSession}. */
    static void openSession(Context context, Progress progress, Callback callback) {
        Context app = context.getApplicationContext();
        submit(() -> {
            if (session == null) {
                try {
                    session = connect(app, progress != null ? progress : message -> { });
                } catch (Failure f) {
                    return f.result;
                }
            }
            sessionUsed = SystemClock.elapsedRealtime();
            return new Result(Outcome.OK, "");
        }, callback);
    }

    /**
     * Runs commands one after another over the screen's connection (connecting
     * again once if it dropped) and returns the last one's output.
     */
    static void sessionRun(Context context, List<String> commands, int timeoutMs,
                           AdbConnection.OutputListener listener, Progress progress, Callback callback) {
        Context app = context.getApplicationContext();
        Progress p = progress != null ? progress : message -> { };
        submit(() -> {
            for (int attempt = 0; ; attempt++) {
                if (session == null) {
                    try {
                        session = connect(app, p);
                    } catch (Failure f) {
                        return f.result;
                    }
                }
                try {
                    String out = "";
                    for (String command : commands) {
                        out = session.adb.run(Commands.service(command), timeoutMs, false, listener);
                    }
                    sessionUsed = SystemClock.elapsedRealtime();
                    return new Result(Outcome.OK, out.trim());
                } catch (IOException e) {
                    Log.w(TAG, "the screen's connection failed", e);
                    closeSessionNow(app);
                    if (attempt > 0) {
                        return new Result(Outcome.FAILED, app.getString(R.string.error_failed, e.getMessage()));
                    }
                }
            }
        }, callback);
    }

    /** Disconnects, and switches wireless debugging off if the app switched it on. */
    static void closeSession(Context context) {
        Context app = context.getApplicationContext();
        submit(() -> {
            closeSessionNow(app);
            return new Result(Outcome.OK, "");
        }, null);
    }

    private static void closeSessionNow(Context c) {
        Link s = session;
        session = null;
        if (s != null) {
            release(c, s);
        }
    }

    /**
     * Waits for wireless debugging to come up and announce itself, and
     * connects to it. Tries every announcement on this TV, as an old one may
     * linger and another app could announce itself too.
     */
    private static AdbConnection findAndConnect(Context c, ContentResolver cr, AdbIdentity id, Progress p)
            throws IOException, InterruptedException, Failure {
        long deadline = SystemClock.elapsedRealtime() + FIND_TIMEOUT_MS;
        boolean asked = false;
        IOException lastError = null;
        Set<Integer> tried = new HashSet<>();
        try (PortFinder finder = new PortFinder(c, prefs(c).getString(ADBD_SERVICE, null))) {
            finder.start();
            while (true) {
                PortFinder.Found found = finder.await(500);
                if (found != null && tried.add(found.port)) {
                    p.step(c.getString(R.string.step_connecting));
                    try {
                        AdbConnection adb = AdbConnection.open("127.0.0.1", found.port, id,
                                AdbConnection.Mode.TLS, null, 0, null);
                        prefs(c).edit().putString(ADBD_SERVICE, found.name).apply();
                        return adb;
                    } catch (ConnectException e) {
                        Log.i(TAG, "nothing listens on port " + found.port + " (an old announcement?)");
                        lastError = e;
                    } catch (AdbAuthException e) {
                        // Almost always adbd not knowing the key, but it could be
                        // something else announcing itself: look a little longer.
                        Log.w(TAG, "port " + found.port + ": " + e.getMessage());
                        lastError = e;
                        deadline = Math.min(deadline, SystemClock.elapsedRealtime() + AFTER_REJECTION_MS);
                    }
                    continue;
                }
                if (finder.failed()) {
                    throw new Failure(Outcome.NOT_FOUND, c.getString(R.string.error_not_found));
                }
                long now = SystemClock.elapsedRealtime();
                boolean on = WirelessDebugging.isOn(cr);
                if (!on && !WirelessDebugging.onWifi(c)) {
                    throw new Failure(Outcome.NO_WIFI, c.getString(R.string.error_no_wifi));
                }
                if (!on && !asked) {
                    // Android switched it back off: the TV is asking whether to
                    // allow wireless debugging on this access point. Wait for an answer.
                    asked = true;
                    p.step(c.getString(R.string.step_allow_network));
                    deadline = now + NETWORK_APPROVAL_TIMEOUT_MS;
                }
                if (now > deadline) {
                    if (lastError != null) {
                        throw lastError;
                    }
                    throw on ? new Failure(Outcome.NOT_FOUND, c.getString(R.string.error_not_found))
                            : new Failure(Outcome.NETWORK_NOT_ALLOWED, c.getString(R.string.error_network));
                }
            }
        }
    }

    /**
     * The one-time setup, over `adb tcpip 5555`: the TV asks to allow the app's
     * key, then the app gives itself what it needs, closes the port again, and
     * starts remapd the way it will after a restart.
     */
    static Result setUpBlocking(Context c, Progress progress) {
        Progress p = progress != null ? progress : message -> { };
        ContentResolver cr = c.getContentResolver();
        if (!WirelessDebugging.usbDebuggingOn(cr)) {
            return new Result(Outcome.USB_DEBUGGING_OFF, c.getString(R.string.error_usb_off));
        }
        AdbIdentity id = null;
        try {
            id = AppKey.load(c);
        } catch (GeneralSecurityException | IOException e) {
            Log.w(TAG, "can't read the saved key; making a new one", e);
        }
        try {
            if (id == null) {
                p.step(c.getString(R.string.step_key));
                id = AppKey.create(c);
            }
        } catch (GeneralSecurityException | IOException e) {
            Log.w(TAG, "can't make a key", e);
            return new Result(Outcome.FAILED, c.getString(R.string.error_failed, e.getMessage()));
        }

        AdbIdentity key = id;
        String fingerprint = id.fingerprint();
        List<String> notes = new ArrayList<>();
        String out;
        boolean saved;
        boolean signedIn = false;
        p.step(c.getString(R.string.step_connect_setup));
        try (AdbConnection adb = AdbConnection.open("127.0.0.1", SETUP_PORT, key,
                AdbConnection.Mode.ALLOW_PROMPT,
                () -> p.step(c.getString(R.string.step_allow_prompt, fingerprint)),
                PROMPT_TIMEOUT_MS, null)) {
            signedIn = true;
            try {
                p.step(c.getString(R.string.step_permissions));
                out = adb.run(Commands.service(Commands.setup(c.getPackageName())), COMMAND_TIMEOUT_MS, false);
                Log.i(TAG, "setup: " + out.trim());
                saved = keyIsSaved(key);
            } finally {
                p.step(c.getString(R.string.step_close_setup));
                if (!closeSetupPort(adb, key)) {
                    notes.add(c.getString(R.string.note_port_open));
                }
            }
        } catch (ConnectException e) {
            return new Result(Outcome.NO_SETUP_PORT, c.getString(R.string.error_no_setup_port));
        } catch (AdbAuthException e) {
            Log.w(TAG, "setup wasn't allowed", e);
            return new Result(e.reason == AdbAuthException.Reason.NOT_ALLOWED ? Outcome.NOT_ALLOWED
                    : Outcome.KEY_REJECTED,
                    join(c.getString(R.string.error_not_allowed), c.getString(R.string.note_port_open)));
        } catch (IOException e) {
            Log.w(TAG, "setup failed", e);
            if (!signedIn) {
                notes.add(c.getString(R.string.note_port_open));
            }
            return new Result(Outcome.FAILED, join(c.getString(R.string.error_failed, e.getMessage()), notes));
        }

        if (!Commands.ran(out, "granted")) {
            return new Result(Outcome.FAILED, join(c.getString(R.string.error_grant, out.trim()), notes));
        }
        if (!saved) {
            return new Result(Outcome.NOT_ALLOWED, join(c.getString(R.string.error_allowed_once), notes));
        }
        if (!Commands.ran(out, "timeout")) {
            notes.add(c.getString(R.string.warn_timeout));
        }
        if (!Commands.ran(out, "deviceidle") || !Commands.ran(out, "appops")) {
            notes.add(c.getString(R.string.warn_battery));
        }

        // Restarting adbd also stopped remapd if it was running. Start it again the
        // way it'll start after a restart, which tests that.
        SystemClock.sleep(3000);
        p.step(c.getString(R.string.step_testing));
        Result r = runBlocking(c, Commands.ensure(bundledRemapd(c)), p);
        remember(c, c.getString(R.string.reason_setup), r);
        // The app doesn't need wireless debugging left on: it switches it on when it needs it.
        if (WirelessDebugging.isOn(cr) && !WirelessDebugging.awaitingApproval(c)) {
            try {
                WirelessDebugging.switchOff(cr);
                notes.add(c.getString(R.string.note_wireless_off));
            } catch (SecurityException e) {
                Log.w(TAG, "can't switch wireless debugging off", e);
            }
        }
        return new Result(r.outcome, join(r.output, notes));
    }

    /**
     * Whether "Always allow" saved the key: a second connection signs in
     * with the key alone. The TV may take a moment to save it.
     */
    private static boolean keyIsSaved(AdbIdentity id) {
        for (int i = 0; i < 3; i++) {
            try (AdbConnection again = AdbConnection.open("127.0.0.1", SETUP_PORT, id,
                    AdbConnection.Mode.SIGNATURE_ONLY, null, 0, null)) {
                return true;
            } catch (AdbAuthException e) {
                SystemClock.sleep(1000);
            } catch (IOException e) {
                Log.w(TAG, "couldn't check whether the key was saved", e);
                return true;   // can't tell; the test run afterwards will
            }
        }
        return false;
    }

    /** Has adbd stop listening on port 5555 ("usb:"), and checks it did. */
    private static boolean closeSetupPort(AdbConnection adb, AdbIdentity id) {
        try {
            adb.run("usb:", 10_000, true);
        } catch (IOException e) {
            Log.w(TAG, "couldn't close port 5555", e);
        }
        for (int i = 0; i < 2; i++) {
            SystemClock.sleep(2000);
            if (!portOpen(SETUP_PORT)) {
                return true;
            }
            try (AdbConnection again = AdbConnection.open("127.0.0.1", SETUP_PORT, id,
                    AdbConnection.Mode.SIGNATURE_ONLY, null, 0, null)) {
                again.run("usb:", 10_000, true);
            } catch (IOException e) {
                Log.w(TAG, "couldn't close port 5555", e);
            }
        }
        SystemClock.sleep(2000);
        return !portOpen(SETUP_PORT);
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Wireless debugging the app left on: a run that was cut short, or the
     * TV's question about the network answered after the app stopped waiting.
     * Finishes the job, which switches it off again.
     */
    static Result followUpBlocking(Context c) {
        ContentResolver cr = c.getContentResolver();
        Result nothing = new Result(Outcome.OK, "");
        if (session != null) {
            if (SystemClock.elapsedRealtime() - sessionUsed < SESSION_IDLE_MS) {
                StartJob.scheduleCleanup(c);   // the buttons screen is using it; check again later
                return nothing;
            }
            closeSessionNow(c);   // left open and unused
            return nothing;
        }
        if (!WirelessDebugging.onByApp(c)) {
            WirelessDebugging.forget(c);
            return nothing;
        }
        if (!WirelessDebugging.isOn(cr)) {
            if (WirelessDebugging.awaitingApproval(c)) {
                StartJob.scheduleOnApproval(c);   // keep watching for an answer
            } else {
                WirelessDebugging.forget(c);
            }
            return nothing;
        }
        Result r = virtualRemotePresent(c)
                ? new Result(Outcome.OK, c.getString(R.string.already_running))
                : runBlocking(c, Commands.ensure(bundledRemapd(c)), null);
        if (WirelessDebugging.onByApp(c) && !WirelessDebugging.awaitingApproval(c)) {
            try {
                WirelessDebugging.turnOffForApp(c);
            } catch (SecurityException ignored) {
            }
        }
        remember(c, c.getString(R.string.reason_follow_up), r);
        return r;
    }

    private static final class Failure extends Exception {
        private static final long serialVersionUID = 1;
        final transient Result result;

        Failure(Outcome outcome, String message) {
            super(message);
            this.result = new Result(outcome, message);
        }
    }

    private static String join(String first, List<String> more) {
        StringBuilder sb = new StringBuilder(first);
        for (String s : more) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String join(String first, String second) {
        List<String> more = new ArrayList<>();
        more.add(second);
        return join(first, more);
    }

    // ---- the last start, for the screen ------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("remapd", Context.MODE_PRIVATE);
    }

    private static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    private static void remember(Context c, String reason, Result r) {
        String summary;
        if (r.ok()) {
            summary = Commands.summarize(r.output);
            if (summary.equals("started")) {
                summary = c.getString(R.string.started);
            }
        } else {
            summary = r.output.split("\n", 2)[0];
        }
        SharedPreferences.Editor edit = prefs(c).edit()
                .putLong("last_time", System.currentTimeMillis())
                .putString("last_reason", reason)
                .putString("last_result", summary);
        if (r.ok()) {
            edit.putInt("ok_boot", bootCount(c));
        }
        edit.apply();
    }

    static String lastSummary(Context c) {
        long time = prefs(c).getLong("last_time", 0);
        if (time == 0) {
            return c.getString(R.string.last_none);
        }
        String when = DateFormat.getMediumDateFormat(c).format(new Date(time)) + " "
                + DateFormat.getTimeFormat(c).format(new Date(time));
        return c.getString(R.string.last_format, when,
                prefs(c).getString("last_reason", ""), prefs(c).getString("last_result", ""));
    }

    // ---- listeners ---------------------------------------------------------------

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
}
