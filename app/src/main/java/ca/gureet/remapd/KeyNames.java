package ca.gureet.remapd;

import java.util.Locale;

/** Friendly names for keys, and the keys a TV remote usually wants. Plain Java. */
final class KeyNames {
    /** Android keys (KeyEvent.KEYCODE_* without the prefix) offered first, with names. */
    static final String[][] COMMON = {
            {"PROG_RED", "Red"}, {"PROG_GREEN", "Green"}, {"PROG_YELLOW", "Yellow"}, {"PROG_BLUE", "Blue"},
            {"MEDIA_PLAY_PAUSE", "Play/Pause"}, {"MEDIA_PLAY", "Play"}, {"MEDIA_PAUSE", "Pause"},
            {"MEDIA_STOP", "Stop"}, {"MEDIA_FAST_FORWARD", "Fast forward"}, {"MEDIA_REWIND", "Rewind"},
            {"MEDIA_NEXT", "Next"}, {"MEDIA_PREVIOUS", "Previous"}, {"MEDIA_RECORD", "Record"},
            {"CHANNEL_UP", "Channel up"}, {"CHANNEL_DOWN", "Channel down"}, {"LAST_CHANNEL", "Last channel"},
            {"GUIDE", "Guide"}, {"INFO", "Info"}, {"CAPTIONS", "Subtitles"}, {"TV_INPUT", "Input"},
            {"DVR", "Recordings"}, {"PAGE_UP", "Page up"}, {"PAGE_DOWN", "Page down"},
            {"VOLUME_UP", "Volume up"}, {"VOLUME_DOWN", "Volume down"}, {"VOLUME_MUTE", "Mute"},
            {"SEARCH", "Search"}, {"SETTINGS", "Settings"}, {"MENU", "Menu"}, {"BOOKMARK", "Bookmark"},
            {"HOME", "Home"}, {"BACK", "Back"},
            {"DPAD_UP", "Up"}, {"DPAD_DOWN", "Down"}, {"DPAD_LEFT", "Left"}, {"DPAD_RIGHT", "Right"},
            {"DPAD_CENTER", "OK"},
    };

    private KeyNames() {
    }

    /** "Red" for PROG_RED; otherwise the name in words ("Media audio track"). */
    static String android(String key) {
        for (String[] k : COMMON) {
            if (k[0].equals(key)) {
                return k[1];
            }
        }
        return words(key);
    }

    /**
     * Keys for getting around the TV. Changing a button that sends one can
     * leave the remote unable to reach this screen to change it back.
     */
    static boolean isNavigation(String key) {
        switch (key) {
            case "DPAD_UP":
            case "DPAD_DOWN":
            case "DPAD_LEFT":
            case "DPAD_RIGHT":
            case "DPAD_CENTER":
            case "ENTER":
            case "NUMPAD_ENTER":
            case "BACK":
            case "HOME":
                return true;
            default:
                return false;
        }
    }

    /** A name for a button from the Linux key it reports, like "Red" for KEY_RED; "" if it says nothing. */
    static String fromLinux(String key) {
        if (!key.startsWith("KEY_") || key.equals("KEY_UNKNOWN") || key.equals("KEY_RESERVED")) {
            return "";
        }
        return words(key.substring(4));
    }

    private static String words(String name) {
        String s = name.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
