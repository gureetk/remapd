package io.github.remapd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        assertTrue(Commands.ENSURE.contains(Commands.BIN + " status"));
        assertTrue(Commands.ENSURE.contains(Commands.BIN + " start " + Commands.CONF));
        assertTrue(Commands.STATUS.contains("tail -n 15 " + Commands.LOG));
    }

    @Test
    public void setup() {
        String s = Commands.setup("io.github.remapd");
        assertTrue(s.startsWith("pm grant io.github.remapd android.permission.WRITE_SECURE_SETTINGS && echo @@granted;"));
        assertTrue(s.contains("settings put global adb_allowed_connection_time 0;"));
        assertTrue(s.contains("cmd deviceidle whitelist +io.github.remapd "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void setupRefusesOddPackageNames() {
        Commands.setup("a; reboot");
    }
}
