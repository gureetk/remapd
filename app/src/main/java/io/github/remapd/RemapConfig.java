package io.github.remapd;

import java.nio.charset.StandardCharsets;
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
        /** For an action that also has a map line: that line's key (the key the button still reports). */
        final String alsoMap;

        Rule(int usage, Kind kind, String arg, String label) {
            this(usage, kind, arg, label, null);
        }

        Rule(int usage, Kind kind, String arg, String label, String alsoMap) {
            this.usage = usage;
            this.kind = kind;
            this.arg = arg;
            this.label = label;
            this.alsoMap = alsoMap;
        }

        /** A map line that switches the button off. */
        boolean doesNothing() {
            return kind == Kind.MAP && isOffKey(arg);
        }

        /** The key a map line next to this action makes the button report as well, or null. */
        String alsoActsAs() {
            return alsoMap == null || isOffKey(alsoMap) ? null : alsoMap;
        }
    }

    /** Linux keys Android ignores: mapping a button to one switches it off. */
    static boolean isOffKey(String key) {
        return key.equals("KEY_UNKNOWN") || key.equals("KEY_RESERVED") || key.equals("240") || key.equals("0");
    }

    /** remapd reads lines of up to this many bytes (load_config's buffer). */
    static final int MAX_LINE = 1022;
    private static final String HEADER = "# Buttons for remapd; the remapd app's Buttons screen edits this file.";

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

    /**
     * Each button's rule, in file order. remapd allows a map line and an
     * action (send, run, launch) for the same button; the action is the rule
     * then, with the map line's key in {@link Rule#alsoMap}.
     */
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
                continue;
            }
            Rule had = out.get(i);
            if (had.kind == Kind.MAP && r.kind != Kind.MAP) {
                out.set(i, new Rule(r.usage, r.kind, r.arg, r.label.isEmpty() ? had.label : r.label, had.arg));
            } else if (had.kind != Kind.MAP && r.kind == Kind.MAP && had.alsoMap == null) {
                out.set(i, new Rule(had.usage, had.kind, had.arg, had.label, r.arg));
            }
        }
        return out;
    }

    Rule ruleFor(int usage) {
        List<Rule> all = rules();
        int i = indexOf(all, usage);
        return i < 0 ? null : all.get(i);
    }

    /**
     * Replaces whatever the file says about a button with one rule, where its
     * first line was. remapd allows a map line next to a run or launch line
     * (the button then reports that key as well): changing such a button to
     * another run or launch keeps its map line.
     */
    void set(int usage, Kind kind, String arg, String label) {
        String line = format(new Rule(usage, kind, check(kind, arg), clean(label)));
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE) {
            throw new IllegalArgumentException("That's too long for remapd.conf (" + MAX_LINE
                    + " characters a line at most).");
        }
        int keep = kind == Kind.RUN || kind == Kind.LAUNCH ? companionMap(usage) : -1;
        int first = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            Rule r = ruleOf(lines.get(i));
            if (r != null && r.usage == usage && i != keep) {
                lines.remove(i);
                first = i;
                if (keep > i) {
                    keep--;
                }
            }
        }
        if (first >= 0) {
            lines.add(first, line);
        } else if (keep >= 0) {
            lines.add(keep + 1, line);
        } else {
            if (lines.isEmpty()) {
                lines.add(HEADER);
            }
            lines.add(line);
        }
    }

    /**
     * The button's map line, if it's next to a run or launch line and doesn't
     * switch the button off; -1 otherwise. (A map line on its own is the
     * button's rule, which a new one replaces.)
     */
    private int companionMap(int usage) {
        int map = -1;
        boolean action = false;
        for (int i = 0; i < lines.size(); i++) {
            Rule r = ruleOf(lines.get(i));
            if (r == null || r.usage != usage) {
                continue;
            }
            if (r.kind == Kind.MAP) {
                if (map < 0 && !r.doesNothing()) {
                    map = i;
                }
            } else if (r.kind == Kind.RUN || r.kind == Kind.LAUNCH) {
                action = true;
            }
        }
        return action ? map : -1;
    }

    /** Leaves the button to Android again: removes all its lines. */
    void remove(int usage) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            Rule r = ruleOf(lines.get(i));
            if (r != null && r.usage == usage) {
                lines.remove(i);
            }
        }
    }

    /** Changes a button's label, leaving everything else about it (all its lines) as it is. */
    void rename(int usage, String label) {
        int primary = -1;
        for (int i = 0; i < lines.size(); i++) {
            Rule r = ruleOf(lines.get(i));
            if (r != null && r.usage == usage && (primary < 0 || (r.kind != Kind.MAP
                    && ruleOf(lines.get(primary)).kind == Kind.MAP))) {
                primary = i;
            }
        }
        if (primary >= 0) {
            Rule r = ruleOf(lines.get(primary));
            String line = format(new Rule(r.usage, r.kind, r.arg, clean(label)));
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE) {
                throw new IllegalArgumentException("That name is too long for remapd.conf.");
            }
            lines.set(primary, line);
        }
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

    /** remapd takes at most this many device lines (MAX_FILTERS). */
    static final int MAX_DEVICES = 8;

    /** Adds a device line, after the others (or first in the file). At most {@link #MAX_DEVICES}. */
    void addDevice(String filter) {
        String f = filter.replaceAll("[\\r\\n#]+", " ").trim();
        if (f.isEmpty()) {
            throw new IllegalArgumentException("no name for a device line");
        }
        if (devices().size() >= MAX_DEVICES) {
            throw new IllegalArgumentException("remapd.conf already has " + MAX_DEVICES
                    + " device lines, the most remapd takes");
        }
        String line = "device " + f;
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
        if (!s.matches("[0-9A-Fa-f]+")) {
            return null;
        }
        s = s.replaceFirst("^0+(?=.)", "");   // leading zeros, as strtoul allows
        if (s.length() > 8) {
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
            // The whole rest goes to the shell, which ignores a comment at its end: that's the label.
            int hash = commentStart(rest);
            String command = hash >= 0 ? rest.substring(0, hash).trim() : rest;
            if (command.isEmpty()) {
                command = rest;   // all comment: remapd still takes it, as a command that does nothing
                hash = -1;
            }
            return rest.isEmpty() ? null
                    : new Rule(usage, kind, command, hash >= 0 ? rest.substring(hash + 1).trim() : "");
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

    /**
     * Where the shell's comment starts in a command: the first # that begins a
     * word, outside quotes, $( ), ${ } and ` `. -1 if there's none, or if the
     * command is too odd to tell (an unclosed quote, say): then the whole line
     * stays the command.
     */
    static int commentStart(String s) {
        boolean wordStart = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' && wordStart) {
                return i;
            }
            if (c == '\\') {
                i++;   // the next character is just a character
            } else if (opensSomething(s, i)) {
                i = skip(s, i);
                if (i < 0) {
                    return -1;
                }
            }
            wordStart = c == ' ' || c == '\t' || c == ';' || c == '&' || c == '|'
                    || c == '(' || c == ')' || c == '<' || c == '>';
        }
        return -1;
    }

    /** A quote, ` or a $( or ${ starts at i. */
    private static boolean opensSomething(String s, int i) {
        char c = s.charAt(i);
        return c == '\'' || c == '"' || c == '`'
                || (c == '$' && i + 1 < s.length() && (s.charAt(i + 1) == '(' || s.charAt(i + 1) == '{'));
    }

    /** Where what starts at i (see opensSomething) ends, or -1 if it doesn't, or it's too odd to tell. */
    private static int skip(String s, int i) {
        char c = s.charAt(i);
        if (c == '\'') {
            return s.indexOf('\'', i + 1);
        }
        boolean nested = c == '$';
        char open = nested ? s.charAt(i + 1) : c;
        char close = open == '(' ? ')' : open == '{' ? '}' : c;
        int depth = 0;
        for (int j = nested ? i + 2 : i + 1; j < s.length(); j++) {
            char d = s.charAt(j);
            if (d == '\\') {
                j++;
            } else if (d == close && depth == 0) {
                return j;
            } else if (c == '"' ? d == '`' || d == '$' && opensSomething(s, j) : opensSomething(s, j)) {
                j = skip(s, j);   // quotes inside $( ), and $( ) inside quotes
                if (j < 0) {
                    return -1;
                }
            } else if (close == ')' && d == '#' && " \t;&|(".indexOf(s.charAt(j - 1)) >= 0) {
                return -1;   // a comment inside $( ): the line wouldn't work anyway
            } else if (nested && d == open) {
                depth++;
            } else if (nested && d == close) {
                depth--;
            }
        }
        return -1;
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

    /** Labels stay on their line. */
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
