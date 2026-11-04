package app.wifibudget;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String EXTRA_AUTO_SAVER = "auto_saver";
    private static final int REQ_VPN = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {time, total bytes} for speed
    private Ui ui;

    private LinearLayout accessCard, appsBox, daysBox;
    private TextView nowText, usedText, pctText, leftText, speedText, resetText, runoutText, saverInfo, saverError;
    private ProgressBar usedBar;
    private Switch saverSwitch;
    private boolean binding, pendingAuto;
    private long lastAppsRefresh;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        buildUi();
        Checker.channels(this);
        Checker.schedule(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_AUTO_SAVER, false)) {
            i.removeExtra(EXTRA_AUTO_SAVER);
            pendingAuto = true;
            startSaver();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastAppsRefresh = 0;
        main.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------ layout
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(ui.bg);
        LinearLayout root = ui.vbox();
        root.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(32));
        scroll.addView(root);

        root.addView(ui.text("WiFi Budget", 24, ui.fg, true));
        nowText = ui.text("", 13, ui.mute, false);
        root.addView(nowText);

        // usage access needed
        accessCard = ui.card(root);
        accessCard.addView(ui.text("One permission needed", 17, ui.fg, true));
        TextView why = ui.text("To count your Wi-Fi data, allow \"Usage access\" for WiFi Budget on the next screen. "
                + "The data never leaves your phone.", 14, ui.mute, false);
        why.setPadding(0, ui.dp(6), 0, ui.dp(10));
        accessCard.addView(why);
        Button grant = new Button(this);
        grant.setText("Allow usage access");
        grant.setOnClickListener(v -> openUsageAccess());
        accessCard.addView(grant);

        // usage
        LinearLayout u = ui.card(root);
        u.addView(ui.label("Used today"));
        usedText = ui.text("-", 34, ui.fg, true);
        u.addView(usedText);
        usedBar = ui.bar(12);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) usedBar.getLayoutParams();
        bp.topMargin = ui.dp(8);
        u.addView(usedBar);
        pctText = ui.text("", 13, ui.mute, false);
        u.addView(pctText);
        LinearLayout row = ui.hbox();
        row.setPadding(0, ui.dp(14), 0, 0);
        leftText = stat(row, "Left");
        speedText = stat(row, "Speed now");
        resetText = stat(row, "Resets in");
        u.addView(row);
        runoutText = ui.text("", 13, ui.warn, true);
        runoutText.setPadding(0, ui.dp(10), 0, 0);
        u.addView(runoutText);
        TextView fix = ui.text("Doesn't match the ship's portal? Tap to correct", 13, ui.accent, false);
        fix.setPadding(0, ui.dp(10), 0, 0);
        fix.setOnClickListener(v -> askCorrection());
        u.addView(fix);

        // data saver
        LinearLayout s = ui.card(root);
        LinearLayout head = ui.hbox();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.text("Data Saver", 18, ui.fg, true), Ui.weight(1));
        saverSwitch = new Switch(this);
        saverSwitch.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            if (on) startSaver();
            else stopSaver();
        });
        head.addView(saverSwitch);
        s.addView(head);
        TextView desc = ui.text("Blocks every app except the ones you allow: no background updates, "
                + "photo backups, game downloads or autoplaying videos.", 14, ui.mute, false);
        desc.setPadding(0, ui.dp(4), 0, ui.dp(8));
        s.addView(desc);
        saverInfo = ui.text("", 14, ui.fg, false);
        s.addView(saverInfo);
        saverError = ui.text("", 13, ui.bad, false);
        s.addView(saverError);
        Button apps = new Button(this);
        apps.setText("Choose allowed apps");
        apps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        s.addView(apps);

        // top apps
        LinearLayout a = ui.card(root);
        a.addView(ui.label("What used your Wi-Fi today"));
        appsBox = ui.vbox();
        a.addView(appsBox);

        // history
        LinearLayout h = ui.card(root);
        h.addView(ui.label("Last 14 days"));
        daysBox = ui.vbox();
        h.addView(daysBox);

        // settings
        LinearLayout st = ui.card(root);
        st.addView(ui.label("Settings"));
        st.addView(setting("Daily allowance (MB)", String.valueOf(Math.round(Prefs.limitMb(this))), v -> {
            float mb = Float.parseFloat(v);
            if (mb < 10) throw new NumberFormatException();
            Prefs.setLimitMb(this, mb);
        }));
        st.addView(setting("Turn Data Saver on automatically at % (0 = never)",
                String.valueOf(Prefs.autoSaverPct(this)), v -> {
                    int p = Integer.parseInt(v);
                    if (p < 0 || p > 100) throw new NumberFormatException();
                    Prefs.setAutoSaverPct(this, p);
                }));
        CheckBox off = new CheckBox(this);
        off.setText("Turn automatic Data Saver off again at 00:00 UTC");
        off.setTextColor(ui.fg);
        off.setChecked(Prefs.autoOffAtReset(this));
        off.setOnCheckedChangeListener((b, on) -> Prefs.setAutoOffAtReset(this, on));
        st.addView(off);

        // tips
        LinearLayout t = ui.card(root);
        t.addView(ui.label("Extra savings"));
        TextView tips = ui.text(
                "• Mark the ship Wi-Fi as metered: Settings → Wi-Fi → (network) → Metered → \"Treat as metered\". "
                        + "Android then holds back updates and big background downloads.\n"
                        + "• Turn off Google Photos backup, and auto-update apps in the Play Store.\n"
                        + "• Video: set YouTube/Netflix to 480p or lower. HD can use 1.5 GB in an hour.\n"
                        + "• WhatsApp: Settings → Storage and data → Media auto-download → off.", 14, ui.fg, false);
        tips.setLineSpacing(ui.dp(3), 1f);
        tips.setPadding(0, ui.dp(6), 0, ui.dp(8));
        t.addView(tips);
        Button wifi = new Button(this);
        wifi.setText("Open Wi-Fi settings");
        wifi.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        t.addView(wifi);

        setContentView(scroll);
    }

    private TextView stat(LinearLayout row, String label) {
        LinearLayout box = ui.vbox();
        box.addView(ui.label(label));
        TextView v = ui.text("-", 18, ui.fg, true);
        box.addView(v);
        row.addView(box, Ui.weight(1));
        return v;
    }

    interface Saver {
        void save(String v) throws NumberFormatException;
    }

    private LinearLayout setting(String label, String value, Saver saver) {
        LinearLayout box = ui.vbox();
        box.setPadding(0, ui.dp(10), 0, 0);
        box.addView(ui.text(label, 14, ui.fg, false));
        LinearLayout row = ui.hbox();
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        e.setTextColor(ui.fg);
        row.addView(e, Ui.weight(1));
        Button b = new Button(this);
        b.setText("Save");
        b.setOnClickListener(v -> {
            try {
                saver.save(e.getText().toString().trim());
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                refresh();
            } catch (NumberFormatException ex) {
                Toast.makeText(this, "That number doesn't look right", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(b);
        box.addView(row);
        return box;
    }

    // ------------------------------------------------------------------ actions
    private void openUsageAccess() {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        if (Build.VERSION.SDK_INT >= 29) i.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void startSaver() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            Toast.makeText(this, "Android will ask to allow a VPN. It's only used to block apps on this phone.",
                    Toast.LENGTH_LONG).show();
            startActivityForResult(consent, REQ_VPN);
        } else {
            launchSaver();
        }
    }

    private void launchSaver() {
        Prefs.setSaverAuto(this, pendingAuto);
        pendingAuto = false;
        startService(new Intent(this, SaverVpnService.class).setAction(SaverVpnService.ACTION_START));
        main.postDelayed(this::refresh, 600);
    }

    private void stopSaver() {
        startService(new Intent(this, SaverVpnService.class).setAction(SaverVpnService.ACTION_STOP));
        main.postDelayed(this::refresh, 400);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_VPN) return;
        if (result == RESULT_OK) launchSaver();
        else {
            pendingAuto = false;
            refresh();
        }
    }

    private void askCorrection() {
        if (!Usage.hasAccess(this)) return;
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint("e.g. 620");
        new AlertDialog.Builder(this)
                .setTitle("How many MB does the portal say you've used today?")
                .setView(e)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        long target = (long) (Double.parseDouble(e.getText().toString().trim()) * Usage.MB);
                        long raw = Usage.todayRaw(this);
                        if (raw >= 0) Prefs.setCorrection(this, Usage.dayKey(System.currentTimeMillis()), target - raw);
                        refresh();
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNeutralButton("Clear correction", (d, w) -> {
                    Prefs.setCorrection(this, Usage.dayKey(System.currentTimeMillis()), 0);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ data
    private static final class Snapshot {
        boolean access;
        long used, limit, speed;
        long[] days;
        List<Usage.AppUse> apps;
    }

    private void refresh() {
        final boolean needApps = System.currentTimeMillis() - lastAppsRefresh > 20_000;
        if (needApps) lastAppsRefresh = System.currentTimeMillis();
        bg.execute(() -> {
            Snapshot s = new Snapshot();
            s.access = Usage.hasAccess(this);
            s.limit = Prefs.limitBytes(this);
            s.speed = speed();
            if (s.access) {
                s.used = Usage.today(this);
                if (needApps) {
                    s.apps = Usage.topAppsToday(this, 8);
                    s.days = Usage.lastDays(this, 14);
                }
            }
            main.post(() -> render(s));
        });
    }

    /** Current speed from the phone's live traffic counters, averaged over up to 30 s. */
    private long speed() {
        long now = System.currentTimeMillis();
        long total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        samples.addLast(new long[]{now, total});
        while (samples.size() > 2 && now - samples.peekFirst()[0] > 30_000) samples.removeFirst();
        long[] first = samples.peekFirst();
        long dt = now - first[0];
        return dt < 1000 ? 0 : Math.max(0, (total - first[1]) * 1000 / dt);
    }

    private void render(Snapshot s) {
        if (isFinishing()) return;
        long now = System.currentTimeMillis();
        long reset = Usage.dayStartUtc(now) + Usage.DAY;
        nowText.setText("Resets at 00:00 UTC · now " + Usage.utc(now, "HH:mm") + " UTC");
        accessCard.setVisibility(s.access ? LinearLayout.GONE : LinearLayout.VISIBLE);

        long secs = (reset - now) / 1000;
        resetText.setText(String.format(Locale.US, "%dh %02dm", secs / 3600, secs % 3600 / 60));
        speedText.setText(Usage.fmt(s.speed) + "/s");

        if (s.access && s.used >= 0) {
            double pct = s.used * 100.0 / s.limit;
            int col = ui.colorFor(pct);
            long left = Math.max(s.limit - s.used, 0);
            usedText.setText(Usage.fmt(s.used) + " / " + Usage.fmt(s.limit));
            usedBar.setProgress((int) Math.min(1000, pct * 10));
            ui.tint(usedBar, col);
            pctText.setText(String.format(Locale.US, "%.1f%% used", pct));
            leftText.setText(Usage.fmt(left));
            leftText.setTextColor(col);
            String warn = "";
            if (left == 0) warn = "Daily allowance used up.";
            else if (s.speed > 2000) {
                long eta = now + left * 1000 / s.speed;
                if (eta < reset) warn = "At this speed you run out at " + Usage.utc(eta, "HH:mm") + " UTC.";
            }
            runoutText.setText(warn);
        } else if (s.access) {
            usedText.setText("Can't read Wi-Fi data");
        }

        binding = true;
        saverSwitch.setChecked(SaverVpnService.running);
        binding = false;
        if (SaverVpnService.running) {
            saverInfo.setText("On" + (Prefs.saverAuto(this) ? " (switched on automatically)" : "") + ": "
                    + SaverVpnService.blockedCount + " apps blocked, " + allowedCount() + " allowed.");
        } else {
            int auto = Prefs.autoSaverPct(this);
            saverInfo.setText("Off." + (auto > 0 ? " Turns on by itself at " + auto + "%." : ""));
        }
        String err = SaverVpnService.lastError;
        saverError.setText(err == null ? "" : err);

        if (s.apps != null) renderApps(s.apps, s.limit);
        if (s.days != null) renderDays(s.days, s.limit);
    }

    private int allowedCount() {
        return Prefs.allowed(this).size();
    }

    private void renderApps(List<Usage.AppUse> apps, long limit) {
        appsBox.removeAllViews();
        if (apps.isEmpty()) {
            appsBox.addView(ui.text("Nothing yet today.", 14, ui.mute, false));
            return;
        }
        long max = apps.get(0).bytes;
        for (Usage.AppUse a : apps) {
            LinearLayout r = ui.vbox();
            r.setPadding(0, ui.dp(10), 0, 0);
            LinearLayout line = ui.hbox();
            TextView name = ui.text(a.label, 14, ui.fg, false);
            name.setSingleLine(true);
            line.addView(name, Ui.weight(1));
            line.addView(ui.text(Usage.fmt(a.bytes), 14, ui.fg, true));
            r.addView(line);
            ProgressBar b = ui.bar(5);
            b.setProgress((int) (a.bytes * 1000 / Math.max(max, 1)));
            ui.tint(b, ui.accent);
            r.addView(b);
            appsBox.addView(r);
        }
    }

    private void renderDays(long[] days, long limit) {
        daysBox.removeAllViews();
        long now = System.currentTimeMillis();
        long max = limit;
        for (long d : days) max = Math.max(max, d);
        for (int i = days.length - 1; i >= 0; i--) {
            long dayStart = Usage.dayStartUtc(now) - (long) (days.length - 1 - i) * Usage.DAY;
            LinearLayout r = ui.hbox();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, ui.dp(7), 0, 0);
            TextView date = ui.text(i == days.length - 1 ? "Today" : Usage.utc(dayStart, "EEE d MMM"), 13, ui.mute, false);
            r.addView(date, new LinearLayout.LayoutParams(ui.dp(88), -2));
            ProgressBar b = ui.bar(8);
            b.setProgress((int) (days[i] * 1000 / Math.max(max, 1)));
            ui.tint(b, days[i] > limit ? ui.bad : ui.accent);
            r.addView(b, new LinearLayout.LayoutParams(0, ui.dp(8), 1));
            TextView v = ui.text(Usage.fmt(days[i]), 13, ui.fg, false);
            v.setGravity(Gravity.END);
            r.addView(v, new LinearLayout.LayoutParams(ui.dp(76), -2));
            daysBox.addView(r);
        }
    }
}
