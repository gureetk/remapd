package io.github.remapd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

public class RemapConfigTest {
    private static String example() throws Exception {
        return new String(Files.readAllBytes(Paths.get("../daemon/examples/ace666bt.conf")),
                StandardCharsets.UTF_8);
    }

    @Test
    public void readsTheExample() throws Exception {
        RemapConfig c = RemapConfig.parse(example());
        List<RemapConfig.Rule> rules = c.rules();
        assertEquals(17, rules.size());
        RemapConfig.Rule red = c.ruleFor(0x000c0069);
        assertEquals(RemapConfig.Kind.SEND, red.kind);
        assertEquals("PROG_RED", red.arg);
        assertEquals("", red.label);
        RemapConfig.Rule up = c.ruleFor(0x000c004b);
        assertEquals("Pg/Ch Up   (PAGE_UP for page keys instead)", up.label);
        RemapConfig.Rule settings = c.ruleFor(0x000c0004);
        assertEquals(RemapConfig.Kind.RUN, settings.kind);
        assertEquals("am start -a android.settings.SETTINGS", settings.arg);
        assertEquals("Settings", settings.label);
        RemapConfig.Rule iptv = c.ruleFor(0x000c0008);
        assertEquals(RemapConfig.Kind.LAUNCH, iptv.kind);
        assertEquals("org.jellyfin.androidtv", iptv.arg);
        assertTrue(c.ruleFor(0x000c0221).doesNothing());
        assertNull(c.ruleFor(0x000c0003));   // commented out
        assertEquals(List.of("ACE666BT"), c.devices());
    }

    @Test
    public void unchangedFileStaysTheSame() throws Exception {
        String text = example();
        assertEquals(text, RemapConfig.parse(text).text());
    }

    @Test
    public void changingARuleKeepsItsPlace() throws Exception {
        RemapConfig c = RemapConfig.parse(example());
        c.set(0x000c004b, RemapConfig.Kind.SEND, "page_up", "Pg Up");
        String text = c.text();
        assertTrue(text.contains("\nsend 000c004b PAGE_UP   # Pg Up\nsend 000c004e CHANNEL_DOWN"));
        assertFalse(text.contains("CHANNEL_UP"));
        assertEquals(17, c.rules().size());
    }

    @Test
    public void addingAndRemoving() throws Exception {
        RemapConfig c = RemapConfig.parse(example());
        c.set(0x000c0003, RemapConfig.Kind.LAUNCH, "com.example.app", "Favourite");
        assertTrue(c.text().endsWith("launch 000c0003 com.example.app   # Favourite\n"));
        assertEquals(18, c.rules().size());
        c.remove(0x000c0221);
        assertNull(c.ruleFor(0x000c0221));
        assertFalse(c.text().contains("000c0221"));
        // The comment line after it stays.
        assertTrue(c.text().contains("# (replace with a launch, run or send line"));
    }

    @Test
    public void oneRulePerButton() {
        RemapConfig c = RemapConfig.parse("map 000c0004 KEY_SETUP\nrun 000c0004 echo hi\nsend 000c0069 prog_red\n");
        assertEquals(2, c.rules().size());
        assertEquals(RemapConfig.Kind.RUN, c.ruleFor(0x000c0004).kind);   // the action wins
        assertEquals("PROG_RED", c.ruleFor(0x000c0069).arg);
        c.set(0x000c0004, RemapConfig.Kind.MAP, "KEY_UNKNOWN", "");
        assertEquals("map 000c0004 KEY_UNKNOWN\nsend 000c0069 prog_red\n", c.text());
    }

    @Test
    public void newFile() {
        RemapConfig c = RemapConfig.parse("");
        assertTrue(c.rules().isEmpty());
        assertTrue(c.coversDevice("Anything", "1234:5678"));
        c.set(0x000c0069, RemapConfig.Kind.SEND, "PROG_RED", "Red");
        c.addDevice("0508:0110");
        String[] lines = c.text().split("\n");
        assertTrue(lines[0].startsWith("# "));
        assertEquals("device 0508:0110", lines[1]);
        assertEquals("send 000c0069 PROG_RED   # Red", lines[2]);
        assertTrue(c.coversDevice("ACE666BT Mouse", "0508:0110"));
        assertFalse(c.coversDevice("Chromecast Remote", "0957:0001"));
    }

    @Test
    public void devicesMatchLikeRemapd() throws Exception {
        RemapConfig c = RemapConfig.parse(example());
        assertTrue(c.coversDevice("ACE666BT Keyboard", "0508:0110"));
        assertTrue(c.coversDevice("ace666bt", "0000:0000"));
        assertFalse(c.coversDevice("Chromecast Remote", "0957:0001"));
        c.addDevice("0957:0001");
        assertTrue(c.coversDevice("Chromecast Remote", "0957:0001"));
        assertTrue(c.text().contains("device ACE666BT\ndevice 0957:0001\n"));
    }

    @Test
    public void usagesLikeRemapd() {
        assertEquals(Integer.valueOf(0x000c0069), RemapConfig.parseUsage("000c0069"));
        assertEquals(Integer.valueOf(0x000c0069), RemapConfig.parseUsage("0xC0069"));
        assertNull(RemapConfig.parseUsage("0"));
        assertNull(RemapConfig.parseUsage("12345678a"));
        assertNull(RemapConfig.parseUsage("zz"));
        assertEquals(Integer.valueOf(0xffffffff), RemapConfig.parseUsage("ffffffff"));
    }

    @Test
    public void ignoresLinesRemapdWouldReject() {
        RemapConfig c = RemapConfig.parse("send 000c0069 TWO WORDS\nfrobnicate 000c006a x\nsend nope PROG_RED\n");
        assertTrue(c.rules().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesBadPackageNames() {
        RemapConfig.parse("").set(1, RemapConfig.Kind.LAUNCH, "com.x; reboot", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesMultilineCommands() {
        RemapConfig.parse("").set(1, RemapConfig.Kind.RUN, "echo a\nreboot", "");
    }

    @Test
    public void labelsStayOnOneLine() {
        RemapConfig c = RemapConfig.parse("");
        c.set(0x000c0069, RemapConfig.Kind.SEND, "PROG_RED", "Red\nsend 000c006a PROG_GREEN");
        assertEquals(1, c.rules().size());
    }
}
