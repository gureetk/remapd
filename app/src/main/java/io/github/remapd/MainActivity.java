package io.github.remapd;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows whether Shizuku and remapd are running, and lets you start or stop remapd. */
public class MainActivity extends Activity {
    private TextView shizukuView, remapdView, lastView, outputView, logView;
    private Button allowButton, openShizukuButton, startButton, stopButton;
    private final Runnable onChange = this::refresh;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        Starter.addListener(onChange);
        // Opening the app is enough to start remapd, e.g. from a launcher after a restart.
        if (Starter.hasPermission()) {
            Starter.ensureRunning(getString(R.string.reason_opened));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        Starter.removeListener(onChange);
        super.onDestroy();
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean running = Starter.shizukuRunning();
        boolean allowed = running && Starter.hasPermission();
        boolean installed = running || Starter.shizukuInstalled(this);

        if (allowed) {
            shizukuView.setText(R.string.shizuku_ok);
        } else if (running && Starter.shizukuTooOld()) {
            shizukuView.setText(R.string.shizuku_old);
        } else if (running) {
            shizukuView.setText(Starter.permissionDeniedForGood() ? R.string.shizuku_denied : R.string.shizuku_ask);
        } else if (installed) {
            shizukuView.setText(R.string.shizuku_stopped);
        } else {
            shizukuView.setText(R.string.shizuku_missing);
        }
        allowButton.setVisibility(running && !allowed && !Starter.shizukuTooOld() ? View.VISIBLE : View.GONE);
        openShizukuButton.setVisibility(installed && !allowed ? View.VISIBLE : View.GONE);
        startButton.setEnabled(allowed && !busy);
        stopButton.setEnabled(allowed && !busy);
        lastView.setText(Starter.lastSummary(this));

        View focused = getCurrentFocus();
        if (focused == null || focused.getVisibility() != View.VISIBLE || !focused.isEnabled()) {
            if (allowButton.getVisibility() == View.VISIBLE) {
                allowButton.requestFocus();
            } else if (openShizukuButton.getVisibility() == View.VISIBLE) {
                openShizukuButton.requestFocus();
            } else {
                startButton.requestFocus();
            }
        }

        if (!allowed) {
            remapdView.setText(R.string.remapd_unknown);
            logView.setText("");
            return;
        }
        Starter.run(Commands.STATUS, this::showStatus);
    }

    private void showStatus(Starter.Result r) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (r.code == -1) {
            remapdView.setText(r.output);
            return;
        }
        Commands.Status status = Commands.parseStatus(r.output);
        switch (status.marker) {
            case "missing":
                remapdView.setText(R.string.remapd_missing);
                break;
            case "noconf":
                remapdView.setText(R.string.remapd_noconf);
                break;
            default:
                remapdView.setText(status.text);
                break;
        }
        logView.setText(status.log.isEmpty() ? "" : getString(R.string.recent_log) + "\n" + status.log);
    }

    private void runAndShow(String command) {
        busy = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        outputView.setText(R.string.working);
        Starter.run(command, r -> {
            busy = false;
            outputView.setText(r.output);
            refresh();
        });
    }

    private void openShizuku() {
        Intent intent = Starter.shizukuLaunchIntent(this);
        if (intent == null) {
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ---- layout -------------------------------------------------------------

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // TV-safe margins.
        root.setPadding(dp(56), dp(32), dp(56), dp(32));

        TextView title = text(34);
        title.setText(R.string.app_name);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = text(18);
        subtitle.setText(R.string.subtitle);
        subtitle.setAlpha(0.8f);
        root.addView(subtitle, spaced(0, 20));

        shizukuView = text(20);
        root.addView(shizukuView, spaced(0, 8));
        remapdView = text(20);
        root.addView(remapdView, spaced(0, 8));
        lastView = text(16);
        lastView.setAlpha(0.8f);
        root.addView(lastView, spaced(0, 20));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        allowButton = button(R.string.allow, v -> Starter.requestPermission());
        openShizukuButton = button(R.string.open_shizuku, v -> openShizuku());
        startButton = button(R.string.start, v -> runAndShow(Commands.START));
        stopButton = button(R.string.stop, v -> runAndShow(Commands.STOP));
        Button refreshButton = button(R.string.refresh, v -> refresh());
        for (Button b : new Button[]{allowButton, openShizukuButton, startButton, stopButton, refreshButton}) {
            buttons.addView(b, spaced(dp(12), 0));
        }
        root.addView(buttons, spaced(0, 16));

        outputView = mono();
        root.addView(outputView, spaced(0, 12));
        logView = mono();
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView text(int sp) {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private TextView mono() {
        TextView view = text(14);
        view.setTypeface(Typeface.MONOSPACE);
        view.setAlpha(0.85f);
        return view;
    }

    private Button button(int label, View.OnClickListener onClick) {
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
