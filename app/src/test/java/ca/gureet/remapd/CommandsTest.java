package ca.gureet.remapd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class CommandsTest {
    @Test
    public void runningWithLog() {
        Commands.Status s = Commands.parseStatus(
                "remapd is running (pid 1734). Log: /data/local/tmp/remapd.log\n@@0\n@@log\n"
                        + "[20:20:47] remapd 1.2 started\n[20:20:48] virtual remote ready\n");
        assertTrue(s.running());
        assertEquals("remapd is running (pid 1734). Log: /data/local/tmp/remapd.log", s.text);
        assertEquals("[20:20:47] remapd 1.2 started\n[20:20:48] virtual remote ready", s.log);
    }

    @Test
    public void notRunning() {
        Commands.Status s = Commands.parseStatus("remapd is not running\n@@1\n@@log\n");
        assertFalse(s.running());
        assertEquals("1", s.marker);
        assertEquals("remapd is not running", s.text);
        assertEquals("", s.log);
    }

    @Test
    public void missingFiles() {
        assertEquals("missing", Commands.parseStatus("@@missing\n@@log\n").marker);
        assertEquals("noconf", Commands.parseStatus("@@noconf\n@@log\n").marker);
    }

    @Test
    public void unexpectedOutput() {
        Commands.Status s = Commands.parseStatus("");
        assertEquals("", s.marker);
        assertFalse(s.running());
        // A log line that happens to start with @@ stays in the log.
        assertEquals("@@0", Commands.parseStatus("@@1\n@@log\n@@0\n").log);
    }

    @Test
    public void summarize() {
        assertEquals("started", Commands.summarize(
                "[x] remapd 1.2 started\n\nremapd is running in the background (pid 5). Log: y"));
        assertEquals("remapd is already running.", Commands.summarize("remapd is already running.\n"));
        assertEquals("", Commands.summarize("  \n"));
    }

    @Test
    public void commandsUseTheShell() {
        assertEquals("shell:exec 2>&1; true", Commands.service("true"));
        String ensure = Commands.ensure("/data/app/~~x==/ca.gureet.remapd-y==/lib/arm/libremapd.so");
        assertTrue(ensure.contains(Commands.BIN + " status"));
        assertTrue(ensure.contains(Commands.BIN + " start " + Commands.CONF));
        assertTrue(ensure.contains("cmp -s /data/app/~~x==/ca.gureet.remapd-y==/lib/arm/libremapd.so " + Commands.BIN));
        assertEquals("true", Commands.install("/data/app/it's odd/libremapd.so"));
        assertTrue(Commands.STATUS.contains("tail -n 15 " + Commands.LOG));
    }

    @Test
    public void setup() {
        String s = Commands.setup("ca.gureet.remapd");
        assertTrue(s.startsWith("pm grant ca.gureet.remapd android.permission.WRITE_SECURE_SETTINGS && echo @@granted;"));
        assertTrue(s.contains("cmd deviceidle whitelist +ca.gureet.remapd "));
        assertTrue(s.contains("settings put global adb_allowed_connection_time 0 && echo @@timeout;"));
    }

    @Test
    public void setupMarkers() {
        String out = "@@granted\nException occurred while executing 'whitelist':\n@@appops\n";
        assertTrue(Commands.ran(out, "granted"));
        assertTrue(Commands.ran(out, "appops"));
        assertFalse(Commands.ran(out, "timeout"));
        assertFalse(Commands.ran(out, "deviceidle"));
        assertFalse(Commands.ran("echo @@granted", "granted"));   // only whole lines count
    }

    @Test
    public void learnedButtons() {
        Commands.Learned l = Commands.parseLearned(
                "listening for 15 seconds: press the button\n"
                        + "learned 000c0069 KEY_RED 0508:0110 UNKNOWN - ACE666BT Keyboard\n");
        assertEquals(Integer.valueOf(0x000c0069), l.usage);
        assertEquals("KEY_RED", l.linuxKey);
        assertEquals("0508:0110", l.vendorProduct);
        assertEquals("UNKNOWN", l.android);
        assertNull(l.globalOwner);
        assertEquals("ACE666BT Keyboard", l.deviceName);

        l = Commands.parseLearned("learned 000c008d KEY_EPG 0957:0001 GUIDE "
                + "com.google.android.tvlauncher/.GlobalKeyReceiver Chromecast Remote");
        assertEquals("GUIDE", l.android);
        assertEquals("com.google.android.tvlauncher/.GlobalKeyReceiver", l.globalOwner);
        assertEquals("Chromecast Remote", l.deviceName);

        assertNull(Commands.parseLearned("learned none KEY_DOWN 0508:0110 DPAD_DOWN - ACE666BT Keyboard").usage);
        assertNull(Commands.parseLearned("listening for 15 seconds: press the button\ntimeout\n"));
    }

    @Test
    public void learnWarnings() {
        String out = "warning event4 (gpio_keys): couldn't hold on to it (Device or resource busy), so its presses"
                + " still reach Android\nlistening for 15 seconds: press the button\n"
                + "learned 000c0069 KEY_RED 0508:0110 UNKNOWN - ACE666BT Keyboard\n";
        assertEquals(List.of("event4 (gpio_keys): couldn't hold on to it (Device or resource busy), so its presses"
                + " still reach Android"), Commands.learnWarnings(out));
        assertTrue(Commands.learnWarnings("listening\ntimeout\n").isEmpty());
    }

    @Test
    public void configReads() {
        Commands.ConfigRead r = Commands.parseConfigRead(
                "Updated remapd to the app's version.\nremapd is running\n@@conf\n# mine\nsend 000c0069 PROG_RED");
        assertEquals("conf", r.marker);
        assertEquals("# mine\nsend 000c0069 PROG_RED", r.config);
        assertEquals("Updated remapd to the app's version.\nremapd is running", r.notes);
        assertTrue(r.running);
        r = Commands.parseConfigRead("There's no remapd.conf yet. Set up buttons in the app.\n@@notrunning\n@@noconf");
        assertEquals("noconf", r.marker);
        assertEquals("", r.config);
        assertEquals("There's no remapd.conf yet. Set up buttons in the app.", r.notes);
        assertFalse(r.running);
        r = Commands.parseConfigRead("@@missing");
        assertEquals("missing", r.marker);
        assertEquals("", r.notes);
        r = Commands.parseConfigRead("sh: something odd");
        assertEquals("", r.marker);
        assertEquals("sh: something odd", r.notes);
    }

    /**
     * Runs commands with sh (or the shell in TEST_SH, like mksh, Android's), in
     * a temporary folder standing in for /data/local/tmp.
     */
    private static String runAll(List<String> commands, Path dir) throws Exception {
        String shell = System.getenv().getOrDefault("TEST_SH", "sh");
        String out = "";
        for (String command : commands) {
            Process p = new ProcessBuilder(shell, "-c",
                    "exec 2>&1; " + command.replace("/data/local/tmp", dir.toString())).start();
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
        }
        return out;
    }

    @Test
    public void savingConfigs() throws Exception {
        Path dir = Files.createTempDirectory("remapd-test");
        Path bin = dir.resolve("remapd");
        Files.write(bin, ("#!/bin/sh\ncase \"$1\" in\n"
                + "check) echo \"what $2 would change\"; if grep -q bad \"$2\"; then"
                + " echo \"$2 line 2: not a good line\" >&2; exit 1; fi;;\n"
                + "start) if grep -q nostart \"$2\"; then echo 'remapd did not start'; exit 1; fi;"
                + " echo \"started with $2\";;\nstop) echo stopped;;\nesac\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(bin.toFile().setExecutable(true));
        Path conf = dir.resolve("remapd.conf");
        Files.write(conf, "old\n".getBytes(StandardCharsets.UTF_8));

        StringBuilder big = new StringBuilder("device ACE666BT\n");
        for (int i = 1; i <= 200; i++) {
            big.append(String.format("send %08x PROG_RED   # button %d, with a fairly long comment\n", i, i));
        }
        assertTrue(Commands.saveConfig(big.toString()).size() > 2);   // in several pieces
        String out = runAll(Commands.saveConfig(big.toString()), dir);
        assertTrue(out, out.contains("started with " + conf) && out.trim().endsWith("@@saved"));
        assertEquals(big.toString(), new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));
        assertEquals("old\n", new String(Files.readAllBytes(dir.resolve("remapd.conf.bak")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("remapd.conf.new")));
        assertFalse(Files.exists(dir.resolve("remapd.conf.b64")));

        out = runAll(Commands.saveConfig("send 000c0069 PROG_RED\nbad\n"), dir);
        assertTrue(out, out.contains("line 2: not a good line") && out.trim().endsWith("@@invalid"));
        assertFalse(out, out.contains("would change"));   // only the complaints
        assertEquals(big.toString(), new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("remapd.conf.new")));

        out = runAll(Commands.saveConfig("send 000c0069 PROG_RED   # nostart\n"), dir);   // saved, not started
        assertTrue(out, out.contains("remapd did not start") && out.trim().endsWith("@@notstarted"));
        assertEquals("send 000c0069 PROG_RED   # nostart\n",
                new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));

        out = runAll(Commands.saveConfig("# nothing left\n"), dir);
        assertTrue(out, out.contains("stopped") && out.trim().endsWith("@@saved"));
        assertEquals("# nothing left\n", new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));
    }

    /** A stand-in remapd: its usage text starts with its version, like remapd's, and it's always running. */
    private static void fakeRemapd(Path file, String version, String name) throws Exception {
        Files.write(file, ("#!/bin/sh\ncase \"$1\" in status) exit 0;; start) echo \"" + name + " started\";;\n"
                + "*) echo \"" + (version.isEmpty() ? "something else" : "remapd " + version + " - fix the buttons")
                + "\"; echo usage; exit 2;; esac\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** ensure() with sh: copies the app's remapd when the TV's is missing or older, and restarts remapd then. */
    @Test
    public void installingRemapd() throws Exception {
        Path dir = Files.createTempDirectory("remapd-test");
        Path bundled = dir.resolve("libremapd.so");
        Path bin = dir.resolve("remapd");
        fakeRemapd(bundled, "1.3", "the app's");
        List<String> ensure = List.of(Commands.ensure(bundled.toString()));

        String out = runAll(ensure, dir);   // no remapd.conf yet: copied, not started
        assertTrue(out, out.contains("There's no remapd.conf yet."));
        assertEquals(read(bundled), read(bin));
        assertTrue(bin.toFile().canExecute());

        Files.write(dir.resolve("remapd.conf"), "send 000c0069 PROG_RED\n".getBytes(StandardCharsets.UTF_8));
        out = runAll(ensure, dir);   // the same one: left alone, and it's running
        assertEquals("remapd is already running.", out.trim());

        String[][] cases = {   // the TV's version, whether the app's 1.3 replaces it
                {"1.2", "yes"}, {"0.9", "yes"}, {"", "yes"},   // older, or doesn't say
                {"1.4", "no"}, {"2.0", "no"}, {"1.3", "no"},   // someone's own build
        };
        for (String[] c : cases) {
            fakeRemapd(bin, c[0], "the TV's");
            assertTrue(bin.toFile().setExecutable(true));
            out = runAll(ensure, dir);
            if (c[1].equals("yes")) {
                assertTrue(c[0] + ": " + out, out.contains("Updated remapd to the app's version.")
                        && out.contains("the app's started"));
                assertEquals(read(bundled), read(bin));
            } else {
                assertEquals(c[0], "remapd is already running.", out.trim());
                assertTrue(read(bin).contains("the TV's"));
            }
            assertFalse(Files.exists(dir.resolve("remapd.new")));
        }

        // Versions are numbers: 1.10 is newer than 1.9.
        fakeRemapd(bundled, "1.10", "the app's");
        fakeRemapd(bin, "1.9", "the TV's");
        out = runAll(ensure, dir);
        assertTrue(out, out.contains("the app's started"));

        // Start or restart: brings the newer one too, then starts.
        fakeRemapd(bin, "1.2", "the TV's");
        out = runAll(List.of(Commands.start(bundled.toString())), dir);
        assertEquals("the app's started", out.trim());

        // No remapd anywhere: says so.
        Files.delete(bin);
        out = runAll(List.of(Commands.ensure(dir.resolve("nothing-here.so").toString())), dir);
        assertTrue(out, out.contains("remapd isn't in"));
    }

    /** openConfig() with sh: remapd is brought up to date, then the config follows its marker. */
    @Test
    public void openingTheConfig() throws Exception {
        Path dir = Files.createTempDirectory("remapd-test");
        Path bundled = dir.resolve("libremapd.so");
        fakeRemapd(bundled, "1.3", "the app's");
        Commands.ConfigRead r = Commands.parseConfigRead(runAll(List.of(Commands.openConfig(bundled.toString())), dir));
        assertEquals("noconf", r.marker);
        assertTrue(r.notes, r.notes.contains("There's no remapd.conf yet."));

        Files.write(dir.resolve("remapd.conf"), "# mine\nsend 000c0069 PROG_RED\n".getBytes(StandardCharsets.UTF_8));
        r = Commands.parseConfigRead(runAll(List.of(Commands.openConfig(bundled.toString())), dir));
        assertEquals("conf", r.marker);
        assertEquals("# mine\nsend 000c0069 PROG_RED\n", r.config);
        assertEquals("remapd is already running.", r.notes);

        assertTrue(r.running);

        // A remapd.conf remapd doesn't take: it says why it didn't start.
        Path bin = dir.resolve("remapd");
        Files.write(bin, ("#!/bin/sh\ncase \"$1\" in status) exit 1;; start) echo \"$2 line 2: not a good line\"; exit 1;;\n"
                + "*) echo 'remapd 1.3 - fix the buttons'; exit 2;; esac\n").getBytes(StandardCharsets.UTF_8));
        r = Commands.parseConfigRead(runAll(List.of(Commands.openConfig(bundled.toString())), dir));
        assertEquals("conf", r.marker);
        assertFalse(r.running);
        assertTrue(r.notes, r.notes.endsWith("line 2: not a good line"));

        r = Commands.parseConfigRead(runAll(List.of(Commands.openConfig(dir.resolve("gone.so").toString())),
                Files.createTempDirectory("remapd-test")));
        assertEquals("missing", r.marker);
        assertTrue(r.notes, r.notes.contains("remapd isn't in"));
        assertFalse(r.running);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setupRefusesOddPackageNames() {
        Commands.setup("a; reboot");
    }
}
