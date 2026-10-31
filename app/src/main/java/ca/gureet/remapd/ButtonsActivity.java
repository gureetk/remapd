package ca.gureet.remapd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sets up buttons: press one on the remote, then pick what it does. Edits
 * /data/local/tmp/remapd.conf over adb and restarts remapd with it. While the
 * app listens for a press, remapd holds on to the TV's input devices, so the
 * press doesn't do anything on the TV. Wireless debugging stays on while this
 * screen is open.
 */
public class ButtonsActivity extends Activity {
    private static final int LEARN_SECONDS = 15;

    private TextView statusView, outputView;
    private Button addButton;
    private LinearLayout rows;
    private RemapConfig config;   // null until it's been read
    private boolean busy;
    /** Counts visits to the screen. What finishes after the screen was left (and maybe opened again) is dropped. */
    private int visit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        int v = ++visit;
        setBusy(true);
        output("");
        status(getString(R.string.buttons_connecting));
        Starter.openSession(this, progress(v), r -> {
            if (stale(v)) {
                return;
            }
            if (!r.ok()) {
                setBusy(false);
                status(r.output);
                return;
            }
            readConfig(v);
        });
    }

    @Override
    protected void onStop() {
        // Disconnects, which also cuts short a wait for a press (remapd lets go of the remotes then).
        Starter.closeSession(this);
        visit++;
        super.onStop();
    }

    /** Whether a result is from an earlier visit to the screen. */
    private boolean stale(int v) {
        return v != visit || isDestroyed();
    }

    private void readConfig(int v) {
        setBusy(true);
        status(getString(R.string.buttons_reading));
        // Brings remapd up to date with the app's copy first (this screen needs its learn command).
        Starter.sessionRun(this, Collections.singletonList(Commands.openConfig(Starter.bundledRemapd(this))),
                30_000, null, progress(v), r -> {
                    if (stale(v)) {
                        return;
                    }
                    setBusy(false);
                    if (!r.ok()) {
                        status(r.output);
                        return;
                    }
                    Commands.ConfigRead read = Commands.parseConfigRead(r.output);
                    if (read.marker.equals("conf") || read.marker.equals("noconf")) {
                        config = RemapConfig.parse(read.config);
                        if (read.marker.equals("conf") && !read.running) {
                            status(getString(R.string.buttons_not_running));   // a mistake in remapd.conf, say
                            output(read.notes);
                        } else {
                            status(getString(R.string.buttons_ready));
                        }
                    } else {
                        config = null;
                        status(getString(read.marker.equals("missing") ? R.string.buttons_missing
                                : R.string.buttons_unreadable));
                        output(read.notes);
                    }
                    showRules();
                });
    }

    private void showRules() {
        rows.removeAllViews();
        if (config != null) {
            List<RemapConfig.Rule> rules = config.rules();
            if (rules.isEmpty()) {
                TextView none = text(18);
                none.setText(R.string.buttons_none);
                rows.addView(none);
            }
            for (RemapConfig.Rule r : rules) {
                Button row = button(buttonName(r.usage, r.label) + "   →   " + describe(r), v -> choose(r.usage, null));
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                rows.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        setBusy(busy);
    }

    // ---- learning a button -------------------------------------------------------

    private void learn() {
        if (busy || config == null) {
            return;
        }
        int v = visit;
        setBusy(true);
        output("");
        status(getString(R.string.buttons_getting_ready));
        StringBuilder seen = new StringBuilder();   // only touched on the worker thread
        Starter.sessionRun(this, Collections.singletonList(Commands.learn(LEARN_SECONDS)),
                (LEARN_SECONDS + 15) * 1000,
                data -> {
                    boolean first = seen.indexOf("listening") < 0;
                    seen.append(new String(data, StandardCharsets.UTF_8));
                    if (first && seen.indexOf("listening") >= 0) {
                        runOnUiThread(() -> {
                            if (!stale(v)) {
                                status(getResources().getQuantityString(R.plurals.buttons_press,
                                        LEARN_SECONDS, LEARN_SECONDS));
                            }
                        });
                    }
                },
                progress(v), r -> {
                    if (stale(v)) {
                        return;
                    }
                    setBusy(false);
                    if (!r.ok()) {
                        status(r.output);
                        return;
                    }
                    List<String> warnings = Commands.learnWarnings(r.output);
                    if (!warnings.isEmpty()) {
                        output(getString(R.string.buttons_not_held) + "\n" + TextUtils.join("\n", warnings));
                    }
                    Commands.Learned learned = Commands.parseLearned(r.output);
                    if (learned == null) {
                        status(r.output.matches("(?s)(.*\n)?timeout(\n.*)?") ? getString(R.string.buttons_timeout)
                                : r.output);
                        return;
                    }
                    if (learned.usage == null) {
                        status(getString(R.string.buttons_no_code, learned.linuxKey));
                        return;
                    }
                    status(getString(R.string.buttons_ready));
                    choose(learned.usage, learned);
                });
    }

    /** What to do with a button: a new one just pressed (learned), or one from the list. */
    private void choose(int usage, Commands.Learned learned) {
        if (config == null || busy) {
            return;
        }
        RemapConfig.Rule existing = config.ruleFor(usage);
        String name = existing != null ? existing.label
                : learned != null ? KeyNames.fromLinux(learned.linuxKey) : "";

        StringBuilder info = new StringBuilder();
        if (learned != null) {
            info.append(getString(R.string.buttons_on_device, learned.deviceName, learned.vendorProduct));
            if (existing == null) {
                info.append('\n').append(learned.android.equals("UNKNOWN")
                        ? getString(R.string.buttons_android_unknown)
                        : learned.android.equals("?") ? getString(R.string.buttons_android_cant_tell)
                        : getString(R.string.buttons_android_sees, learned.android));
                if (learned.globalOwner != null) {
                    info.append('\n').append(getString(R.string.buttons_global, learned.globalOwner));
                }
                if (KeyNames.isNavigation(learned.android)) {
                    info.append('\n').append(getString(R.string.buttons_navigation));
                }
            }
        }
        if (existing != null) {
            if (info.length() > 0) {
                info.append('\n');
            }
            info.append(getString(R.string.buttons_now, describe(existing)));
        }

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.choose_send));
        actions.add(() -> pickKey(usage, name, learned));
        labels.add(getString(R.string.choose_app));
        actions.add(() -> pickApp(usage, name, learned));
        labels.add(getString(R.string.choose_nothing));
        actions.add(() -> save(usage, RemapConfig.Kind.MAP, "KEY_UNKNOWN", name, learned));
        labels.add(getString(R.string.choose_run));
        actions.add(() -> askCommand(usage, name, existing, learned));
        if (existing != null) {
            labels.add(getString(R.string.choose_rename));
            actions.add(() -> rename(existing));
            labels.add(getString(R.string.choose_remove));
            actions.add(() -> remove(usage));
        }
        new AlertDialog.Builder(this)
                .setCustomTitle(header(buttonName(usage, name), info.toString()))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickKey(int usage, String name, Commands.Learned learned) {
        String[] labels = new String[KeyNames.COMMON.length + 1];
        for (int i = 0; i < KeyNames.COMMON.length; i++) {
            labels[i] = KeyNames.COMMON[i][1] + "   (" + KeyNames.COMMON[i][0] + ")";
        }
        labels[labels.length - 1] = getString(R.string.choose_more_keys);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_key_title)
                .setItems(labels, (d, which) -> {
                    if (which < KeyNames.COMMON.length) {
                        save(usage, RemapConfig.Kind.SEND, KeyNames.COMMON[which][0], name, learned);
                    } else {
                        pickAnyKey(usage, name, learned);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickAnyKey(int usage, String name, Commands.Learned learned) {
        List<String> keys = new ArrayList<>();
        for (int code = 1; code <= KeyEvent.getMaxKeyCode(); code++) {
            String s = KeyEvent.keyCodeToString(code);
            if (s.startsWith("KEYCODE_")) {
                keys.add(s.substring(8));
            }
        }
        Collections.sort(keys);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_key_title)
                .setItems(keys.toArray(new String[0]),
                        (d, which) -> save(usage, RemapConfig.Kind.SEND, keys.get(which), name, learned))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickApp(int usage, String name, Commands.Learned learned) {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();   // label, package
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && seen.add(pkg)) {
                    apps.add(new String[]{ri.loadLabel(pm).toString(), pkg});
                }
            }
        }
        apps.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = apps.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_app_title)
                .setItems(labels, (d, which) ->
                        save(usage, RemapConfig.Kind.LAUNCH, apps.get(which)[1], name, learned))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askCommand(int usage, String name, RemapConfig.Rule existing, Commands.Learned learned) {
        EditText input = editText(existing != null && existing.kind == RemapConfig.Kind.RUN ? existing.arg : "",
                RemapConfig.MAX_LINE - 100);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_run_title)
                .setMessage(R.string.choose_run_hint)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String command = input.getText().toString().trim();
                    if (!command.isEmpty()) {
                        save(usage, RemapConfig.Kind.RUN, command, name, learned);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void rename(RemapConfig.Rule rule) {
        EditText input = editText(rule.label, 200);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_rename_title)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (busy || config == null) {
                        return;
                    }
                    RemapConfig next = RemapConfig.parse(config.text());
                    try {
                        next.rename(rule.usage, input.getText().toString());   // keeps all the button's lines
                    } catch (IllegalArgumentException e) {
                        status(e.getMessage());
                        return;
                    }
                    apply(next, "");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void remove(int usage) {
        if (busy || config == null) {
            return;
        }
        RemapConfig next = RemapConfig.parse(config.text());
        next.remove(usage);
        apply(next, "");
    }

    private void save(int usage, RemapConfig.Kind kind, String arg, String name, Commands.Learned learned) {
        if (busy || config == null) {
            return;
        }
        RemapConfig next = RemapConfig.parse(config.text());
        try {
            next.set(usage, kind, arg, name);
        } catch (IllegalArgumentException e) {
            status(e.getMessage());
            return;
        }
        if (learned != null && config.ruleFor(usage) == null && KeyNames.isNavigation(learned.android)) {
            // Up, OK, Back and so on: changing one can leave this remote unable to get around the TV.
            String key = KeyNames.android(learned.android);
            confirm(getString(R.string.buttons_nav_title, key), getString(R.string.buttons_nav_message, key),
                    getString(R.string.buttons_nav_ok), () -> withDevice(next, learned));
        } else {
            withDevice(next, learned);
        }
    }

    /**
     * remapd only changes the remotes remapd.conf lists (every remote, if it
     * lists none). A new remapd.conf gets a line for the remote the button is
     * on. Adding a remote to the list asks first: every button set up here
     * then applies to that remote too.
     */
    private void withDevice(RemapConfig next, Commands.Learned learned) {
        if (busy || config == null) {
            return;
        }
        boolean fresh = config.devices().isEmpty() && config.rules().isEmpty();
        String filter = learned == null ? ""
                : learned.vendorProduct.equals("0000:0000") ? learned.deviceName.trim() : learned.vendorProduct;
        if (filter.isEmpty() || (!fresh && next.coversDevice(learned.deviceName, learned.vendorProduct))) {
            apply(next, "");
        } else if (fresh) {
            next.addDevice(filter);
            apply(next, getString(R.string.buttons_first_device, learned.deviceName));
        } else if (next.devices().size() >= RemapConfig.MAX_DEVICES) {
            status(getResources().getQuantityString(R.plurals.buttons_too_many_devices, RemapConfig.MAX_DEVICES,
                    RemapConfig.MAX_DEVICES, learned.deviceName));
        } else {
            confirm(getString(R.string.buttons_add_device_title, learned.deviceName),
                    getString(R.string.buttons_add_device_message, TextUtils.join(", ", next.devices()),
                            learned.deviceName),
                    getString(R.string.buttons_add_device_ok), () -> {
                        next.addDevice(filter);
                        apply(next, getString(R.string.buttons_added_device, learned.deviceName));
                    });
        }
    }

    private void apply(RemapConfig next, String note) {
        if (busy) {
            return;
        }
        int v = visit;
        setBusy(true);
        output("");
        status(getString(R.string.buttons_saving));
        Starter.sessionRun(this, Commands.saveConfig(next.text()), 60_000, null, progress(v), r -> {
            if (stale(v)) {
                return;
            }
            setBusy(false);
            if (!r.ok()) {
                status(r.output);
                return;
            }
            String out = r.output;
            int nl = out.lastIndexOf('\n');
            String marker = out.substring(nl + 1).trim();
            String said = nl < 0 ? "" : out.substring(0, nl).trim();
            switch (marker) {
                case "@@saved": {
                    config = next;
                    showRules();
                    String done = getString(next.rules().isEmpty() ? R.string.buttons_saved_stopped
                            : R.string.buttons_saved);
                    status(note.isEmpty() ? done : done + " " + note);
                    output(said);
                    break;
                }
                case "@@notstarted":
                    config = next;
                    showRules();
                    status(getString(R.string.buttons_saved_not_started));
                    output(said);
                    break;
                case "@@invalid":
                    status(getString(R.string.buttons_invalid));
                    output(said);
                    break;
                default:
                    status(getString(R.string.buttons_save_failed));
                    output(out);
                    break;
            }
        });
    }

    /** Asks before doing something; Cancel has the focus. Dropped if the screen is left in between. */
    private void confirm(String title, String message, String ok, Runnable onOk) {
        int v = visit;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(ok, (d, w) -> {
                    if (!stale(v)) {
                        onOk.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    // ---- words -----------------------------------------------------------------

    private String buttonName(int usage, String label) {
        return label == null || label.isEmpty() ? RemapConfig.hex(usage) : label + " (" + RemapConfig.hex(usage) + ")";
    }

    private String describe(RemapConfig.Rule r) {
        String what;
        switch (r.kind) {
            case SEND: {
                String name = KeyNames.android(r.arg);
                boolean common = !name.toUpperCase(Locale.ROOT).replace(' ', '_').equals(r.arg);
                what = getString(R.string.action_sends, common ? name + " (" + r.arg + ")" : r.arg);
                break;
            }
            case LAUNCH:
                what = getString(R.string.action_opens, appName(r.arg));
                break;
            case RUN:
                what = getString(R.string.action_runs, r.arg);
                break;
            default:
                what = r.doesNothing() ? getString(R.string.action_nothing) : getString(R.string.action_acts_as, r.arg);
                break;
        }
        String also = r.alsoActsAs();   // a map line next to a run or launch line
        return also == null ? what : getString(R.string.action_and_acts_as, what, also);
    }

    private String appName(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---- screen ------------------------------------------------------------------

    /** Shows a background step, unless the screen was left since it started. */
    private Starter.Progress progress(int v) {
        return message -> runOnUiThread(() -> {
            if (!stale(v)) {
                status(message);
            }
        });
    }

    private void status(String message) {
        statusView.setText(message);
    }

    private void output(String text) {
        outputView.setText(text);
        outputView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setBusy(boolean b) {
        busy = b;
        addButton.setEnabled(!b && config != null);
        for (int i = 0; i < rows.getChildCount(); i++) {
            rows.getChildAt(i).setEnabled(!b);
        }
        if (!b && getCurrentFocus() == null && addButton.isEnabled()) {
            addButton.requestFocus();
        }
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(32), dp(56), dp(32));

        TextView title = text(34);
        title.setText(R.string.buttons_title);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView intro = text(16);
        intro.setText(R.string.buttons_intro);
        intro.setAlpha(0.8f);
        root.addView(intro, spaced(0, 16));

        statusView = text(20);
        root.addView(statusView, spaced(0, 12));

        addButton = button(getString(R.string.buttons_add), v -> learn());
        root.addView(addButton, spaced(0, 12));

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        outputView = text(14);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setAlpha(0.85f);
        outputView.setVisibility(View.GONE);
        root.addView(outputView, spaced(0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private View header(String title, String info) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(8));
        TextView t = text(22);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        if (!info.isEmpty()) {
            TextView i = text(16);
            i.setText(info);
            i.setAlpha(0.85f);
            box.addView(i);
        }
        return box;
    }

    private EditText editText(String value, int maxLength) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setText(value);
        input.setSelection(input.getText().length());
        return input;
    }

    private TextView text(int sp) {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setOnClickListener(onClick);
        return button;
    }

    private LinearLayout.LayoutParams spaced(int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
