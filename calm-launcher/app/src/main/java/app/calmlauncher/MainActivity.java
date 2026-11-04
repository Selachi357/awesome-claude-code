package app.calmlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The home screen. Design rules:
 *  - no icons, no colours, no notification badges
 *  - at most 6 pinned apps on the home screen
 *  - there is no scrollable app drawer: every other app has to be found by typing its name
 *  - "mindful" apps go through a short pause screen before they open
 */
public class MainActivity extends Activity {
    private static final int MAX_RESULTS = 6;

    private Prefs prefs;
    private List<Apps.App> allApps = new ArrayList<>();
    private LinearLayout pinnedBox;
    private LinearLayout resultsBox;
    private EditText search;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        int pad = Ui.dp(this, 32);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, Ui.dp(this, 72), pad, Ui.dp(this, 32));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(64);
        clock.setTextColor(Ui.FG);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-thin", 0));
        clock.setOnLongClickListener(v -> { showSettings(); return true; });
        root.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextSize(16);
        date.setTextColor(Ui.DIM);
        root.addView(date);

        pinnedBox = new LinearLayout(this);
        pinnedBox.setOrientation(LinearLayout.VERTICAL);
        pinnedBox.setPadding(0, Ui.dp(this, 48), 0, 0);
        root.addView(pinnedBox);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);

        search = new EditText(this);
        search.setHint("type an app name…");
        search.setHintTextColor(Ui.DIM);
        search.setTextColor(Ui.FG);
        search.setTextSize(18);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable s) { renderResults(); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            List<Apps.App> m = matches(search.getText().toString());
            if (m.size() >= 1) open(m.get(0).pkg);
            return true;
        });
        root.addView(search);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        allApps = Apps.load(this);
        search.setText("");
        search.clearFocus();
        hideKeyboard();
        renderPinned();
    }

    @Override
    public void onBackPressed() {
        // Home screen: back just clears the search.
        search.setText("");
        hideKeyboard();
    }

    private void renderPinned() {
        pinnedBox.removeAllViews();
        for (String pkg : prefs.pinned()) {
            String label = Apps.labelFor(this, pkg);
            if (label == null) continue;
            pinnedBox.addView(appRow(label, pkg, 26, Ui.FG));
        }
        if (pinnedBox.getChildCount() == 0) {
            TextView hint = Ui.text(this,
                    "Search for an app below, then long-press it to pin it here.\n"
                            + "Pin only what you need — calls, messages, maps.",
                    15, Ui.DIM);
            pinnedBox.addView(hint);
        }
    }

    private List<Apps.App> matches(String q) {
        List<Apps.App> out = new ArrayList<>();
        q = q.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;
        for (Apps.App a : allApps) if (a.label.toLowerCase(Locale.ROOT).startsWith(q)) out.add(a);
        for (Apps.App a : allApps) {
            String l = a.label.toLowerCase(Locale.ROOT);
            if (!l.startsWith(q) && l.contains(q)) out.add(a);
        }
        return out.size() > MAX_RESULTS ? out.subList(0, MAX_RESULTS) : out;
    }

    private void renderResults() {
        resultsBox.removeAllViews();
        for (Apps.App a : matches(search.getText().toString())) {
            resultsBox.addView(appRow(a.label, a.pkg, 20, Ui.FG));
        }
    }

    private TextView appRow(String label, String pkg, float size, int color) {
        String suffix = prefs.isMindful(pkg) ? "  ·" : "";
        TextView t = Ui.text(this, label + suffix, size, color);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        t.setGravity(Gravity.START);
        t.setOnClickListener(v -> open(pkg));
        t.setOnLongClickListener(v -> { showAppMenu(label, pkg); return true; });
        return t;
    }

    private void open(String pkg) {
        hideKeyboard();
        Apps.open(this, pkg);
    }

    private void showAppMenu(String label, String pkg) {
        boolean pinned = prefs.pinned().contains(pkg);
        boolean mindful = prefs.isMindful(pkg);
        String[] items = {
                pinned ? "Unpin from home" : "Pin to home",
                mindful ? "Remove pause before opening" : "Add pause before opening",
                "App info (uninstall, limits)"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (!pinned && !prefs.togglePinned(pkg)) {
                            toast("Only " + Prefs.MAX_PINNED + " pins. Fewer is calmer.");
                        } else if (pinned) {
                            prefs.togglePinned(pkg);
                        }
                    } else if (which == 1) {
                        prefs.toggleMindful(pkg);
                    } else {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + pkg)));
                    }
                    renderPinned();
                    renderResults();
                })
                .show();
    }

    private void showSettings() {
        String[] items = {
                "Toggle grayscale screen",
                "Pause length: " + prefs.pauseSeconds() + "s (tap to change)",
                "Digital Wellbeing (screen time & app timers)",
                "Choose default home app"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Calm settings")
                .setItems(items, (d, which) -> {
                    if (which == 0) toggleGrayscale();
                    else if (which == 1) cyclePause();
                    else if (which == 2) openWellbeing();
                    else startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                })
                .show();
    }

    private void cyclePause() {
        int[] options = {5, 10, 20, 30};
        int cur = prefs.pauseSeconds();
        int next = options[0];
        for (int i = 0; i < options.length; i++) {
            if (options[i] == cur) { next = options[(i + 1) % options.length]; break; }
        }
        prefs.setPauseSeconds(next);
        toast("Pause is now " + next + " seconds");
    }

    private void toggleGrayscale() {
        try {
            boolean on = "1".equals(Settings.Secure.getString(getContentResolver(),
                    "accessibility_display_daltonizer_enabled"));
            Settings.Secure.putString(getContentResolver(), "accessibility_display_daltonizer", "0");
            Settings.Secure.putString(getContentResolver(),
                    "accessibility_display_daltonizer_enabled", on ? "0" : "1");
            toast(on ? "Colour restored" : "Grayscale on");
        } catch (SecurityException e) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Grayscale needs one-time permission")
                    .setMessage("Connect the phone to a computer and run:\n\n"
                            + "adb shell pm grant app.calmlauncher android.permission.WRITE_SECURE_SETTINGS\n\n"
                            + "Or without a computer: Settings → Accessibility → Visibility enhancements "
                            + "→ Colour adjustment → Greyscale.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void openWellbeing() {
        Intent i = new Intent();
        i.setClassName("com.samsung.android.forest",
                "com.samsung.android.forest.launcher.LauncherActivity");
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) { }
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
