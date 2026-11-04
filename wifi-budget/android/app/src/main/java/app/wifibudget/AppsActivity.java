package app.wifibudget;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pick which apps may still use Wi-Fi while Data Saver is on. */
public class AppsActivity extends Activity {
    private Ui ui;
    private PackageManager pm;
    private Set<String> allowed;
    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView summary;
    private String filter = "";
    private boolean changed;

    private static final class Row {
        String pkg, label;
        long bytes;
        boolean system;
        ApplicationInfo ai;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        pm = getPackageManager();
        allowed = Prefs.allowed(this);

        LinearLayout root = ui.vbox();
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(ui.bg);
        root.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0);
        root.addView(ui.text("Allowed apps", 22, ui.fg, true));
        TextView help = ui.text("Ticked apps keep working when Data Saver is on. Everything else is blocked. "
                + "Sorted by how much Wi-Fi each app used today.", 13, ui.mute, false);
        help.setPadding(0, ui.dp(4), 0, ui.dp(8));
        root.addView(help);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextColor(ui.fg);
        search.setHintTextColor(ui.mute);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        root.addView(search);

        LinearLayout buttons = ui.hbox();
        Button rec = new Button(this);
        rec.setText("Recommended");
        rec.setOnClickListener(v -> {
            allowed.clear();
            allowed.addAll(Prefs.RECOMMENDED);
            allowed.add(getPackageName());
            save();
        });
        buttons.addView(rec, Ui.weight(1));
        Button none = new Button(this);
        none.setText("Block all");
        none.setOnClickListener(v -> {
            allowed.clear();
            allowed.add(getPackageName());
            for (String p : Prefs.RECOMMENDED) if (p.contains("captiveportal") || p.contains("networkstack")) allowed.add(p);
            save();
        });
        buttons.addView(none, Ui.weight(1));
        root.addView(buttons);

        summary = ui.text("Loading apps…", 13, ui.mute, false);
        summary.setPadding(0, ui.dp(6), 0, ui.dp(6));
        root.addView(summary);

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> toggle(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        new Thread(this::load).start();
    }

    private void load() {
        long now = System.currentTimeMillis();
        Map<Integer, Long> use = Usage.hasAccess(this)
                ? Usage.perUid(this, Usage.dayStartUtc(now), now + 60_000) : new java.util.HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!Prefs.blockable(pm, ai, getPackageName())) continue;
            Row r = new Row();
            r.pkg = ai.packageName;
            r.ai = ai;
            r.label = pm.getApplicationLabel(ai).toString();
            Long b = use.get(ai.uid);
            r.bytes = b == null ? 0 : b;
            r.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            rows.add(r);
        }
        rows.sort((a, b) -> a.bytes != b.bytes ? Long.compare(b.bytes, a.bytes)
                : a.label.compareToIgnoreCase(b.label));
        runOnUiThread(() -> {
            all.clear();
            all.addAll(rows);
            applyFilter();
        });
    }

    private void applyFilter() {
        shown.clear();
        for (Row r : all) {
            if (filter.isEmpty() || r.label.toLowerCase(Locale.ROOT).contains(filter)
                    || r.pkg.toLowerCase(Locale.ROOT).contains(filter)) shown.add(r);
        }
        updateSummary();
        adapter.notifyDataSetChanged();
    }

    private void updateSummary() {
        int a = 0;
        for (Row r : all) if (allowed.contains(r.pkg)) a++;
        summary.setText(a + " allowed · " + (all.size() - a) + " blocked when Data Saver is on");
    }

    private void toggle(Row r) {
        if (!allowed.remove(r.pkg)) allowed.add(r.pkg);
        save();
    }

    private void save() {
        Prefs.setAllowed(this, allowed);
        changed = true;
        updateSummary();
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (changed && SaverVpnService.running) { // apply the new list right away
            startService(new Intent(this, SaverVpnService.class).setAction(SaverVpnService.ACTION_START));
        }
        changed = false;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                h = new Holder();
                LinearLayout row = ui.hbox();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, ui.dp(8), 0, ui.dp(8));
                h.icon = new ImageView(AppsActivity.this);
                row.addView(h.icon, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
                LinearLayout txt = ui.vbox();
                txt.setPadding(ui.dp(12), 0, ui.dp(8), 0);
                h.name = ui.text("", 15, ui.fg, false);
                h.name.setSingleLine(true);
                h.sub = ui.text("", 12, ui.mute, false);
                h.sub.setSingleLine(true);
                txt.addView(h.name);
                txt.addView(h.sub);
                row.addView(txt, Ui.weight(1));
                h.box = new CheckBox(AppsActivity.this);
                h.box.setClickable(false);
                h.box.setFocusable(false);
                row.addView(h.box);
                row.setTag(h);
                convert = row;
            } else {
                h = (Holder) convert.getTag();
            }
            Row r = shown.get(pos);
            if (r.icon == null) {
                try {
                    r.icon = pm.getApplicationIcon(r.ai);
                } catch (Exception ignored) {
                }
            }
            h.icon.setImageDrawable(r.icon);
            h.name.setText(r.label);
            h.sub.setText((r.bytes > 0 ? Usage.fmt(r.bytes) + " today · " : "") + (r.system ? "system · " : "") + r.pkg);
            h.box.setChecked(allowed.contains(r.pkg));
            return convert;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView name, sub;
        CheckBox box;
    }
}
