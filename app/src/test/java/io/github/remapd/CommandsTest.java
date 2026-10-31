package io.github.remapd;

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
        String ensure = Commands.ensure("/data/app/~~x==/io.github.remapd-y==/lib/arm/libremapd.so");
        assertTrue(ensure.contains(Commands.BIN + " status"));
        assertTrue(ensure.contains(Commands.BIN + " start " + Commands.CONF));
        assertTrue(ensure.contains("cmp -s /data/app/~~x==/io.github.remapd-y==/lib/arm/libremapd.so " + Commands.BIN));
        assertEquals("true", Commands.install("/data/app/it's odd/libremapd.so"));
        assertTrue(Commands.STATUS.contains("tail -n 15 " + Commands.LOG));
    }

    @Test
    public void setup() {
        String s = Commands.setup("io.github.remapd");
        assertTrue(s.startsWith("pm grant io.github.remapd android.permission.WRITE_SECURE_SETTINGS && echo @@granted;"));
        assertTrue(s.contains("cmd deviceidle whitelist +io.github.remapd "));
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

    /** Runs saveConfig's commands with sh, in a temporary folder standing in for /data/local/tmp. */
    private static String runAll(List<String> commands, Path dir) throws Exception {
        String out = "";
        for (String command : commands) {
            Process p = new ProcessBuilder("sh", "-c",
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
                + "check) if grep -q bad \"$2\"; then echo \"$2 line 2: not a good line\" >&2; exit 1; fi;"
                + " echo looks fine;;\n"
                + "start) echo \"started with $2\";;\nstop) echo stopped;;\nesac\n").getBytes(StandardCharsets.UTF_8));
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
        assertEquals(big.toString(), new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("remapd.conf.new")));

        out = runAll(Commands.saveConfig("# nothing left\n"), dir);
        assertTrue(out, out.contains("stopped") && out.trim().endsWith("@@saved"));
        assertEquals("# nothing left\n", new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));
    }

    /** ensure() with sh: copies the app's remapd when it differs, and restarts remapd then. */
    @Test
    public void installingRemapd() throws Exception {
        Path dir = Files.createTempDirectory("remapd-test");
        Path bundled = dir.resolve("libremapd.so");
        Files.write(bundled, ("#!/bin/sh\ncase \"$1\" in status) exit 0;; start) echo \"new one started\";; esac\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("remapd.conf"), "send 000c0069 PROG_RED\n".getBytes(StandardCharsets.UTF_8));
        List<String> ensure = List.of(Commands.ensure(bundled.toString()));

        String out = runAll(ensure, dir);   // not there yet: copied and started
        assertTrue(out, out.contains("new one started"));
        assertEquals(new String(Files.readAllBytes(bundled)), new String(Files.readAllBytes(dir.resolve("remapd"))));
        assertTrue(dir.resolve("remapd").toFile().canExecute());

        out = runAll(ensure, dir);   // the same: left alone, and it's running
        assertTrue(out, out.contains("remapd is already running."));

        Files.write(bundled, ("#!/bin/sh\ncase \"$1\" in status) exit 0;; start) echo \"newer one started\";; esac\n")
                .getBytes(StandardCharsets.UTF_8));
        out = runAll(ensure, dir);   // the app brought a different one: copied and restarted
        assertTrue(out, out.contains("newer one started"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void setupRefusesOddPackageNames() {
        Commands.setup("a; reboot");
    }
}
