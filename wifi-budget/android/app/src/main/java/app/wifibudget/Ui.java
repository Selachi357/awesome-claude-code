package app.wifibudget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Tiny helpers for building the screens in code (no layout XML, no libraries). */
final class Ui {
    final Context c;
    final boolean dark;
    final int bg, card, fg, mute, line, ok, warn, bad, accent;

    Ui(Context c) {
        this.c = c;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF0F1115 : 0xFFF6F7F9;
        card = dark ? 0xFF181B21 : 0xFFFFFFFF;
        fg = dark ? 0xFFE8EAED : 0xFF1B1F24;
        mute = dark ? 0xFF9AA0A6 : 0xFF6B7280;
        line = dark ? 0xFF2A2F37 : 0xFFE5E7EB;
        ok = dark ? 0xFF22C55E : 0xFF16A34A;
        warn = dark ? 0xFFF59E0B : 0xFFD97706;
        bad = dark ? 0xFFEF4444 : 0xFFDC2626;
        accent = dark ? 0xFF60A5FA : 0xFF2563EB;
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    int colorFor(double pct) {
        return pct >= 90 ? bad : pct >= 75 ? warn : ok;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s.toUpperCase(), 11, mute, false);
        t.setLetterSpacing(0.06f);
        return t;
    }

    LinearLayout vbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout hbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout l = vbox();
        GradientDrawable d = new GradientDrawable();
        d.setColor(card);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), line);
        l.setBackground(d);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(l, lp);
        return l;
    }

    ProgressBar bar(int heightDp) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        p.setProgressBackgroundTintList(ColorStateList.valueOf(line));
        p.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(heightDp)));
        return p;
    }

    void tint(ProgressBar p, int color) {
        p.setProgressTintList(ColorStateList.valueOf(color));
    }

    View gap(int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, -2, w);
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }
}
