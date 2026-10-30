package io.github.remapd;

/**
 * The shell commands the app runs over adb, and how it reads their output.
 * Plain Java (no Android classes) so it can be tested on a computer.
 */
final class Commands {
    static final String DIR = "/data/local/tmp";
    static final String BIN = DIR + "/remapd";
    static final String CONF = DIR + "/remapd.conf";
    static final String LOG = DIR + "/remapd.log";

    /** Starts remapd unless it's already running. */
    static final String ENSURE =
            "if [ ! -x " + BIN + " ]; then echo 'remapd isn'\\''t in " + DIR + " yet.'; exit 1; fi; "
                    + "if " + BIN + " status >/dev/null 2>&1; then echo 'remapd is already running.'; "
                    + "else " + BIN + " start " + CONF + "; fi";

    static final String START = BIN + " start " + CONF;
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

    /** One line saying what an ENSURE run did. */
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
