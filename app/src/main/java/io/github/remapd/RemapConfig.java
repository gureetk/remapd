package io.github.remapd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * remapd.conf, as the buttons screen edits it. Each button gets one rule,
 * which stays where it was in the file; comments and lines the screen doesn't
 * change are kept as they are. A rule's label is its line's "# comment".
 *
 * <p>The format is remapd's own (load_config in daemon/remapd.c). Plain Java,
 * so it can be tested on a computer.
 */
final class RemapConfig {
    enum Kind {
        SEND, MAP, RUN, LAUNCH;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the file says one button does. */
    static final class Rule {
        /** The HID usage (MSC_SCAN value), like 0x000c0069. */
        final int usage;
        final Kind kind;
        /** Android key (send), Linux key (map), shell command (run) or package (launch). */
        final String arg;
        /** The line's comment, or "". */
        final String label;

        Rule(int usage, Kind kind, String arg, String label) {
            this.usage = usage;
            this.kind = kind;
            this.arg = arg;
            this.label = label;
        }

        /** A map line that switches the button off. */
        boolean doesNothing() {
            return kind == Kind.MAP && (arg.equals("KEY_UNKNOWN") || arg.equals("KEY_RESERVED")
                    || arg.equals("240") || arg.equals("0"));
        }
    }

    private final List<String> lines = new ArrayList<>();

    static RemapConfig parse(String text) {
        RemapConfig c = new RemapConfig();
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {   // a byte-order mark from some editors
            text = text.substring(1);
        }
        for (String line : text.split("\n", -1)) {
            c.lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        while (!c.lines.isEmpty() && c.lines.get(c.lines.size() - 1).isEmpty()) {
            c.lines.remove(c.lines.size() - 1);
        }
        return c;
    }

    String text() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Each button's rule, in file order. An action (send, run, launch) wins over a map line. */
    List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        for (String line : lines) {
            Rule r = ruleOf(line);
            if (r == null) {
                continue;
            }
            int i = indexOf(out, r.usage);
            if (i < 0) {
                out.add(r);
            } else if (out.get(i).kind == Kind.MAP && r.kind != Kind.MAP) {
                out.set(i, r);
            }
        }
        return out;
    }

    Rule ruleFor(int usage) {
        List<Rule> all = rules();
        int i = indexOf(all, usage);
        return i < 0 ? null : all.get(i);
    }

    /** Replaces whatever the file says about a button with one rule, where its first line was. */
    void set(int usage, Kind kind, String arg, String label) {
        String line = format(new Rule(usage, kind, check(kind, arg), clean(label)));
        int at = removeLines(usage);
        if (at < 0) {
            if (lines.isEmpty()) {
                lines.add("# Buttons for remapd; the remapd app's Buttons screen edits this file.");
            }
            lines.add(line);
        } else {
            lines.add(at, line);
        }
    }

    /** Leaves the button to Android again. */
    void remove(int usage) {
        removeLines(usage);
    }

    /** The device lines' filters. */
    List<String> devices() {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String s = line.trim();
            String[] w = splitWord(s);
            if (w[0].equalsIgnoreCase("device")) {
                String f = stripComment(w[1]).trim();
                if (!f.isEmpty()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Whether remapd would look at a device: no device lines, or one matching it (like matches_filter). */
    boolean coversDevice(String name, String vendorProduct) {
        List<String> filters = devices();
        if (filters.isEmpty()) {
            return true;
        }
        for (String f : filters) {
            if (f.equalsIgnoreCase(vendorProduct)
                    || name.toLowerCase(Locale.ROOT).contains(f.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Adds a device line, after the others (or first in the file). */
    void addDevice(String filter) {
        String line = "device " + clean(filter);
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (splitWord(lines.get(i).trim())[0].equalsIgnoreCase("device")) {
                last = i;
            }
        }
        if (last >= 0) {
            lines.add(last + 1, line);
            return;
        }
        int at = 0;
        while (at < lines.size() && lines.get(at).trim().startsWith("#")) {
            at++;   // after the comments at the top
        }
        lines.add(at, line);
    }

    static String hex(int usage) {
        return String.format("%08x", usage);
    }

    /** A usage written as remapd reads it (hex, maybe with 0x), or null. */
    static Integer parseUsage(String s) {
        if (s.regionMatches(true, 0, "0x", 0, 2)) {
            s = s.substring(2);
        }
        if (s.isEmpty() || s.length() > 8 || !s.matches("[0-9A-Fa-f]+")) {
            return null;
        }
        long v = Long.parseLong(s, 16);
        return v == 0 ? null : (int) v;
    }

    static String format(Rule r) {
        String line = r.kind.word() + " " + hex(r.usage) + " " + r.arg;
        return r.label.isEmpty() ? line : line + "   # " + r.label;
    }

    /** The rule a line holds, or null (comments, device lines, mistakes). */
    static Rule ruleOf(String line) {
        String[] kw = splitWord(line.trim());
        Kind kind;
        try {
            kind = Kind.valueOf(kw[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] u = splitWord(kw[1]);
        Integer usage = parseUsage(u[0]);
        if (usage == null) {
            return null;
        }
        String rest = u[1];
        if (kind == Kind.RUN) {
            // The whole rest is the command; the shell ignores a " # comment" at its end.
            int hash = rest.indexOf(" #");
            String command = (hash >= 0 ? rest.substring(0, hash) : rest).trim();
            String label = hash >= 0 ? rest.substring(hash + 2).trim() : "";
            return command.isEmpty() ? null : new Rule(usage, kind, command, label);
        }
        int hash = rest.indexOf('#');
        String label = hash >= 0 ? rest.substring(hash + 1).trim() : "";
        String arg = (hash >= 0 ? rest.substring(0, hash) : rest).trim();
        if (arg.isEmpty() || arg.contains(" ") || arg.contains("\t")) {
            return null;
        }
        if (kind == Kind.SEND) {
            arg = arg.toUpperCase(Locale.ROOT);
            if (arg.startsWith("KEYCODE_")) {
                arg = arg.substring(8);
            }
        }
        return new Rule(usage, kind, arg, label);
    }

    private int removeLines(int usage) {
        int first = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            Rule r = ruleOf(lines.get(i));
            if (r != null && r.usage == usage) {
                lines.remove(i);
                first = i;
            }
        }
        return first;
    }

    private static int indexOf(List<Rule> rules, int usage) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).usage == usage) {
                return i;
            }
        }
        return -1;
    }

    private static String check(Kind kind, String arg) {
        String a = arg.trim();
        boolean ok;
        switch (kind) {
            case SEND:
            case MAP:
                ok = a.matches("[A-Za-z0-9_]+");
                break;
            case LAUNCH:
                ok = a.matches("[A-Za-z0-9_.]+");
                break;
            default:
                ok = !a.isEmpty() && !a.contains("\n") && !a.contains("\r");
                break;
        }
        if (!ok) {
            throw new IllegalArgumentException("not a valid " + kind.word() + " argument: " + arg);
        }
        return kind == Kind.SEND ? a.toUpperCase(Locale.ROOT) : a;
    }

    private static String clean(String label) {
        return label == null ? "" : label.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String stripComment(String s) {
        int hash = s.indexOf('#');
        return hash >= 0 ? s.substring(0, hash) : s;
    }

    /** The first word, and the rest (trimmed). */
    private static String[] splitWord(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return new String[]{s.substring(0, i), s.substring(i).trim()};
    }
}
