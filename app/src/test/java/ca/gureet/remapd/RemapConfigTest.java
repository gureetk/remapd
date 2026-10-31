package ca.gureet.remapd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        assertEquals("KEY_SETUP", c.ruleFor(0x000c0004).alsoActsAs());   // and the map line shows
        assertEquals("PROG_RED", c.ruleFor(0x000c0069).arg);
        assertNull(c.ruleFor(0x000c0069).alsoActsAs());
        c.set(0x000c0004, RemapConfig.Kind.MAP, "KEY_UNKNOWN", "");
        assertEquals("map 000c0004 KEY_UNKNOWN\nsend 000c0069 prog_red\n", c.text());
    }

    @Test
    public void runAndLaunchKeepTheirMapLine() {
        RemapConfig c = RemapConfig.parse("map 000c0004 KEY_SETUP   # Setup\nrun 000c0004 echo hi\n");
        RemapConfig.Rule r = c.ruleFor(0x000c0004);
        assertEquals("echo hi", r.arg);
        assertEquals("Setup", r.label);   // from the map line, as the run line has none
        c.set(0x000c0004, RemapConfig.Kind.RUN, "echo bye", "Setup");
        assertEquals("map 000c0004 KEY_SETUP   # Setup\nrun 000c0004 echo bye   # Setup\n", c.text());
        c.set(0x000c0004, RemapConfig.Kind.LAUNCH, "com.example.app", "");
        assertEquals("map 000c0004 KEY_SETUP   # Setup\nlaunch 000c0004 com.example.app\n", c.text());
        assertEquals("KEY_SETUP", c.ruleFor(0x000c0004).alsoActsAs());
        // remapd doesn't take map and send together.
        c.set(0x000c0004, RemapConfig.Kind.SEND, "PROG_RED", "");
        assertEquals("send 000c0004 PROG_RED\n", c.text());

        // A map line on its own is the button's rule, which a new one replaces.
        c = RemapConfig.parse("map 000c0004 KEY_SETUP\nsend 000c0069 PROG_RED\n");
        c.set(0x000c0004, RemapConfig.Kind.RUN, "echo hi", "");
        assertEquals("run 000c0004 echo hi\nsend 000c0069 PROG_RED\n", c.text());
        // So does one that switched the button off.
        c = RemapConfig.parse("map 000c0004 KEY_UNKNOWN\nrun 000c0004 echo hi\n");
        c.set(0x000c0004, RemapConfig.Kind.RUN, "echo bye", "");
        assertEquals("run 000c0004 echo bye\n", c.text());
    }

    @Test
    public void renamingKeepsEverythingElse() {
        String run = "run 000c0004 am broadcast -a x --es msg \"a #1\"";
        RemapConfig c = RemapConfig.parse("map 000c0004 KEY_SETUP\n" + run + "   # Old\n");
        c.rename(0x000c0004, "New name");
        assertEquals("map 000c0004 KEY_SETUP\n" + run + "   # New name\n", c.text());
        c.rename(0x000c0004, "");
        assertEquals("map 000c0004 KEY_SETUP\n" + run + "\n", c.text());
        c = RemapConfig.parse("send 000c0069 PROG_RED   # Red\n");
        c.rename(0x000c0069, "Mom's \"red\" one # first");
        assertEquals("Mom's \"red\" one # first", c.ruleFor(0x000c0069).label);
    }

    @Test
    public void runCommentsAsTheShellSeesThem() {
        String[][] cases = {   // the rest of a run line, the command, the label
                {"am start -a android.settings.SETTINGS  # Settings", "am start -a android.settings.SETTINGS", "Settings"},
                {"am broadcast -a x --es msg \"a #1\"   # Send", "am broadcast -a x --es msg \"a #1\"", "Send"},
                {"echo 'it #1' # it's mine", "echo 'it #1'", "it's mine"},
                {"echo $# #count", "echo $#", "count"},
                {"echo a;#b", "echo a;", "b"},
                {"echo ${x:- #} done", "echo ${x:- #} done", ""},
                {"echo a\\ #b", "echo a\\ #b", ""},
                {"echo 'a'#b", "echo 'a'#b", ""},
                {"echo \"$(date) #x\"", "echo \"$(date) #x\"", ""},
                {"echo \"$(date +'%H #%M')\" `echo \"#\"` # time", "echo \"$(date +'%H #%M')\" `echo \"#\"`", "time"},
                {"echo $((1 + (2 * 3))) ${x#*.} # sums", "echo $((1 + (2 * 3))) ${x#*.}", "sums"},
                {"input keyevent 4", "input keyevent 4", ""},
                // Too odd to tell: all of it stays the command.
                {"echo 'unclosed # x", "echo 'unclosed # x", ""},
                {"echo $(echo a #b) # c", "echo $(echo a #b) # c", ""},
                // All comment: remapd takes it, as a command that does nothing.
                {"#only a comment", "#only a comment", ""},
        };
        for (String[] k : cases) {
            RemapConfig.Rule r = RemapConfig.ruleOf("run 000c0004 " + k[0]);
            assertEquals(k[0], k[1], r.arg);
            assertEquals(k[0], k[2], r.label);
            if (k[0].contains("unclosed") || k[0].contains("a #b") || k[0].startsWith("#")) {
                continue;
            }
            // Written back with a new label, the command is still the same.
            RemapConfig c = RemapConfig.parse("run 000c0004 " + k[0] + "\n");
            c.rename(0x000c0004, "New");
            assertEquals(k[0], k[1], c.ruleFor(0x000c0004).arg);
            assertEquals(k[0], "New", c.ruleFor(0x000c0004).label);
        }
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
        assertNull(RemapConfig.parseUsage("0x"));
        assertEquals(Integer.valueOf(0xffffffff), RemapConfig.parseUsage("ffffffff"));
        // strtoul takes any number of leading zeros.
        assertEquals(Integer.valueOf(0x000c0069), RemapConfig.parseUsage("0000000000c0069"));
        assertEquals(Integer.valueOf(0xffffffff), RemapConfig.parseUsage("0x00ffffffff"));
        assertNull(RemapConfig.parseUsage("0100000000"));
        assertEquals(0x000c0069, RemapConfig.parse("send 00000000000c0069 PROG_RED\n").rules().get(0).usage);
    }

    @Test
    public void atMostEightDeviceLines() {
        RemapConfig c = RemapConfig.parse("");
        for (int i = 1; i <= RemapConfig.MAX_DEVICES; i++) {
            c.addDevice("Remote " + i);
        }
        try {
            c.addDevice("Remote 9");
            fail("a ninth device line");
        } catch (IllegalArgumentException expected) {
        }
        assertEquals(RemapConfig.MAX_DEVICES, c.devices().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesLinesTooLongForRemapd() {
        StringBuilder command = new StringBuilder("echo");
        while (command.length() < RemapConfig.MAX_LINE) {
            command.append(" hello");
        }
        RemapConfig.parse("").set(1, RemapConfig.Kind.RUN, command.toString(), "");
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
