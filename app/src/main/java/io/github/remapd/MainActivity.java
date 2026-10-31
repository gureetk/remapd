package io.github.remapd;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Sets up starting remapd after a restart, shows whether it's running, and starts or stops it. */
public class MainActivity extends Activity {
    private TextView setupView, startView, usbView, remapdView, lastView, outputView, logView;
    private Button setUpButton, buttonsButton, startButton, stopButton, checkButton, setUpAgainButton;
    private final Runnable onChange = this::refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        Starter.addListener(onChange);
        // Opening the app starts remapd if starting it after the last restart didn't work.
        // (Not every time: other apps can open this screen too.)
        if (savedInstanceState == null && Starter.isSetUp(this) && !Starter.virtualRemotePresent(this)
                && !Starter.startedThisBoot(this)) {
            outputView.setText(R.string.working);
            Starter.ensureRunning(this, getString(R.string.reason_opened), this::progress, this::showResult);
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
        boolean setUp = Starter.isSetUp(this);
        boolean busy = Starter.busy();
        boolean usbOn = WirelessDebugging.usbDebuggingOn(getContentResolver());

        setupView.setVisibility(setUp ? View.GONE : View.VISIBLE);
        startView.setText(setUp ? R.string.start_ready : R.string.start_not_set_up);
        usbView.setVisibility(usbOn ? View.GONE : View.VISIBLE);
        remapdView.setText(Starter.virtualRemotePresent(this) ? R.string.remapd_running : R.string.remapd_not_seen);
        lastView.setText(Starter.lastSummary(this));

        setUpButton.setVisibility(setUp ? View.GONE : View.VISIBLE);
        for (Button b : new Button[]{buttonsButton, startButton, stopButton, checkButton, setUpAgainButton}) {
            b.setVisibility(setUp ? View.VISIBLE : View.GONE);
        }
        for (Button b : new Button[]{setUpButton, buttonsButton, startButton, stopButton, checkButton,
                setUpAgainButton}) {
            b.setEnabled(!busy);
        }

        View focused = getCurrentFocus();
        if (focused == null || focused.getVisibility() != View.VISIBLE || !focused.isEnabled()) {
            (setUp ? buttonsButton : setUpButton).requestFocus();
        }
    }

    private void progress(String message) {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                outputView.setText(message);
            }
        });
    }

    private void showResult(Starter.Result r) {
        if (isDestroyed()) {
            return;
        }
        outputView.setText(r.output);
        logView.setText("");
    }

    private void setUp() {
        outputView.setText(R.string.working);
        logView.setText("");
        Starter.setUp(this, this::progress, this::showResult);
    }

    private void run(String command) {
        outputView.setText(R.string.working);
        logView.setText("");
        Starter.run(this, command, this::progress, this::showResult);
    }

    private void checkStatus() {
        outputView.setText(R.string.working);
        logView.setText("");
        Starter.run(this, Commands.STATUS, this::progress, r -> {
            if (isDestroyed()) {
                return;
            }
            if (!r.ok()) {
                outputView.setText(r.output);
                return;
            }
            Commands.Status status = Commands.parseStatus(r.output);
            switch (status.marker) {
                case "missing":
                    outputView.setText(R.string.remapd_missing);
                    break;
                case "noconf":
                    outputView.setText(R.string.remapd_noconf);
                    break;
                default:
                    outputView.setText(status.text);
                    break;
            }
            logView.setText(status.log.isEmpty() ? "" : getString(R.string.recent_log) + "\n" + status.log);
        });
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

        setupView = text(18);
        setupView.setText(R.string.setup_steps);
        root.addView(setupView, spaced(0, 20));

        startView = text(20);
        root.addView(startView, spaced(0, 8));
        usbView = text(20);
        usbView.setText(R.string.error_usb_off);
        root.addView(usbView, spaced(0, 8));
        remapdView = text(20);
        root.addView(remapdView, spaced(0, 8));
        lastView = text(16);
        lastView.setAlpha(0.8f);
        root.addView(lastView, spaced(0, 20));

        setUpButton = button(R.string.set_up, v -> setUp());
        buttonsButton = button(R.string.buttons, v -> startActivity(new Intent(this, ButtonsActivity.class)));
        startButton = button(R.string.start, v -> run(Commands.start(Starter.bundledRemapd(this))));
        stopButton = button(R.string.stop, v -> run(Commands.STOP));
        checkButton = button(R.string.check, v -> checkStatus());
        setUpAgainButton = button(R.string.set_up_again, v -> setUp());
        LinearLayout row1 = new LinearLayout(this);
        for (Button b : new Button[]{setUpButton, buttonsButton, startButton, stopButton}) {
            row1.addView(b, spaced(dp(12), 0));
        }
        root.addView(row1, spaced(0, 8));
        LinearLayout row2 = new LinearLayout(this);
        for (Button b : new Button[]{checkButton, setUpAgainButton}) {
            row2.addView(b, spaced(dp(12), 0));
        }
        root.addView(row2, spaced(0, 16));

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
