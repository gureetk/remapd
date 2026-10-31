package io.github.remapd;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The shell commands the app runs over adb, and how it reads their output.
 * Plain Java (no Android classes) so it can be tested on a computer.
 */
final class Commands {
    static final String DIR = "/data/local/tmp";
    static final String BIN = DIR + "/remapd";
    static final String CONF = DIR + "/remapd.conf";
    static final String LOG = DIR + "/remapd.log";

    /**
     * Copies the remapd that comes with the app (bundled: its path in the
     * app's native library folder, which the shell user can read) to
     * /data/local/tmp when it isn't there or differs. Prints @@updated then.
     */
    static String install(String bundled) {
        if (bundled == null || !bundled.matches("[A-Za-z0-9_./~=+-]+")) {
            return "true";   // nothing usable to copy
        }
        String next = BIN + ".new";
        return "if [ -f " + bundled + " ] && ! cmp -s " + bundled + " " + BIN + "; then "
                + "cp -f " + bundled + " " + next + " && chmod 755 " + next + " && mv -f " + next + " " + BIN
                + " && echo @@updated; fi";
    }

    /** Starts remapd unless it's already running, restarting it if the app brought a different one. */
    static String ensure(String bundled) {
        return "u=$(" + install(bundled) + " 2>&1); "
                + "if [ ! -x " + BIN + " ]; then echo 'remapd isn'\\''t in " + DIR + " yet.'; exit 1; fi; "
                + "if [ ! -f " + CONF + " ]; then echo 'There'\\''s no remapd.conf yet. Set up buttons in the app.'; exit 1; fi; "
                + "case \"$u\" in *@@updated*) " + BIN + " start " + CONF + ";; "
                + "*) if " + BIN + " status >/dev/null 2>&1; then echo 'remapd is already running.'; "
                + "else " + BIN + " start " + CONF + "; fi;; esac";
    }

    /** Starts or restarts remapd, with the app's copy of it. */
    static String start(String bundled) {
        return install(bundled) + " >/dev/null; " + BIN + " start " + CONF;
    }

    static final String STOP = BIN + " stop";

    /** remapd's status line, then @@-markers the app reads, then the end of the log. */
    static final String STATUS =
            "if [ ! -x " + BIN + " ]; then echo @@missing; "
                    + "elif [ ! -f " + CONF + " ]; then echo @@noconf; "
                    + "else " + BIN + " status; echo @@$?; fi; "
                    + "echo @@log; tail -n 15 " + LOG + " 2>/dev/null";

    /** The adb service that runs a command, with stderr in the output too. */
    static String service(String command) {
        return "shell:exec 2>&1; " + command;
    }

    /**
     * Run once, over the connection the TV allowed during setup. Lets the app
     * switch wireless debugging on and off; stops adb forgetting the app's key
     * 7 days after setup (signing in over wireless debugging doesn't count as
     * using it); and keeps Android from delaying or pausing the app because
     * it's rarely opened, so it still starts remapd after a restart. Each step
     * prints a marker when it worked (see {@link #ran}).
     */
    static String setup(String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("odd package name: " + pkg);
        }
        return "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS && echo @@granted; "
                + "settings put global adb_allowed_connection_time 0 && echo @@timeout; "
                + "cmd deviceidle whitelist +" + pkg + " >/dev/null && echo @@deviceidle; "
                + "appops set " + pkg + " AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore && echo @@appops; true";
    }

    /** Whether a step of {@link #setup} printed its marker. */
    static boolean ran(String output, String step) {
        for (String line : output.split("\n")) {
            if (line.trim().equals("@@" + step)) {
                return true;
            }
        }
        return false;
    }

    /** STATUS output, split up. */
    static final class Status {
        /** "missing", "noconf", "0" (running), "1" (not running), or "" if unknown. */
        final String marker;
        final String text;
        final String log;

        Status(String marker, String text, String log) {
            this.marker = marker;
            this.text = text;
            this.log = log;
        }

        boolean running() {
            return marker.equals("0");
        }
    }

    static Status parseStatus(String output) {
        StringBuilder text = new StringBuilder();
        StringBuilder log = new StringBuilder();
        String marker = "";
        boolean inLog = false;
        for (String line : output.split("\n", -1)) {
            if (line.equals("@@log")) {
                inLog = true;
            } else if (inLog) {
                log.append(line).append('\n');
            } else if (line.startsWith("@@")) {
                marker = line.substring(2).trim();
            } else if (!line.trim().isEmpty()) {
                text.append(line).append('\n');
            }
        }
        return new Status(marker, text.toString().trim(), log.toString().trim());
    }

    // ---- the buttons screen ----------------------------------------------------

    /** "@@conf" and the config, "@@noconf" if there's none yet, or "@@missing" without remapd. */
    static final String READ_CONFIG =
            "if [ ! -x " + BIN + " ]; then echo @@missing; "
                    + "elif [ -f " + CONF + " ]; then echo @@conf; cat " + CONF + "; "
                    + "else echo @@noconf; fi";

    /** Waits for one button press (see remapd's learn command). */
    static String learn(int seconds) {
        return BIN + " learn " + seconds;
    }

    /** A button remapd learned. */
    static final class Learned {
        /** The HID usage, or null if the button sent none (remapd can't use it then). */
        final Integer usage;
        /** The Linux key it reports now, like KEY_RED (or a number). */
        final String linuxKey;
        /** Like 0508:0110. */
        final String vendorProduct;
        /** What Android makes of it now: an Android key, UNKNOWN, or "?" if remapd couldn't tell. */
        final String android;
        /** Who gets the key instead of apps, if the system keeps it for itself; null if nobody. */
        final String globalOwner;
        final String deviceName;

        Learned(Integer usage, String linuxKey, String vendorProduct, String android,
                String globalOwner, String deviceName) {
            this.usage = usage;
            this.linuxKey = linuxKey;
            this.vendorProduct = vendorProduct;
            this.android = android;
            this.globalOwner = globalOwner;
            this.deviceName = deviceName;
        }
    }

    /**
     * Reads remapd learn's answer:
     * "learned <usage|none> <key> <vendor:product> <android> <owner|-> <device name>".
     * Null if nothing was pressed (or the output is something else).
     */
    static Learned parseLearned(String output) {
        for (String line : output.split("\n")) {
            String[] w = line.trim().split(" ", 7);
            if (w.length < 6 || !w[0].equals("learned")) {
                continue;
            }
            Integer usage = w[1].equals("none") ? null : RemapConfig.parseUsage(w[1]);
            return new Learned(usage, w[2], w[3], w[4], w[5].equals("-") ? null : w[5],
                    w.length > 6 ? w[6] : "");
        }
        return null;
    }

    /**
     * Commands that save a new config and restart remapd with it, if remapd
     * accepts it (the old one is kept as remapd.conf.bak). The last one prints
     * @@saved, or @@invalid after remapd's complaints. With no rules left,
     * remapd is stopped instead, since it won't run without any.
     */
    static List<String> saveConfig(String text) {
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String tmp = CONF + ".b64";
        String next = CONF + ".new";
        List<String> commands = new ArrayList<>();
        for (int i = 0; i == 0 || i < b64.length(); i += 3000) {
            String part = b64.substring(i, Math.min(b64.length(), i + 3000));
            commands.add("printf '%s' '" + part + "' " + (i == 0 ? ">" : ">>") + " " + tmp);
        }
        boolean hasRules = !RemapConfig.parse(text).rules().isEmpty();
        String swap = "cp -f " + CONF + " " + CONF + ".bak 2>/dev/null; mv -f " + next + " " + CONF;
        commands.add("base64 -d " + tmp + " > " + next + " && rm -f " + tmp + " || exit 1; "
                + (hasRules
                ? "if out=$(" + BIN + " check " + next + " 2>&1); then " + swap + " && " + BIN + " start " + CONF
                        + "; echo @@saved; else echo \"$out\" | grep -i 'line [0-9]' | head -n 20; rm -f " + next
                        + "; echo @@invalid; fi"
                : swap + " && " + BIN + " stop; echo @@saved"));
        return commands;
    }

    /** One line saying what an ensure() run did. */
    static String summarize(String output) {
        if (output.contains("running in the background")) {
            return "started";
        }
        String trimmed = output.trim();
        return trimmed.isEmpty() ? "" : trimmed.split("\n", 2)[0];
    }

    private Commands() {
    }
}
