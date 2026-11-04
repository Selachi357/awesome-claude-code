package app.calmlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A short, boring pause before opening an app you've marked as a time sink. */
public class PauseActivity extends Activity {
    private CountDownTimer timer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) { finish(); return; }
        Prefs prefs = new Prefs(this);
        String found = Apps.labelFor(this, pkg);
        final String label = found != null ? found : pkg;
        int opens = prefs.opensToday(pkg);

        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        int pad = Ui.dp(this, 32);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.text(this, "Breathe.", 40, Ui.FG));

        TextView q = Ui.text(this, "\nWhy are you opening " + label + "?", 20, Ui.FG);
        root.addView(q);

        String countLine = opens == 0 ? "You haven't opened it yet today."
                : "You've opened it " + opens + (opens == 1 ? " time" : " times") + " today.";
        root.addView(Ui.text(this, "\n" + countLine, 16, Ui.DIM));

        TextView open = Ui.text(this, "", 20, Ui.DIM);
        open.setPadding(0, Ui.dp(this, 64), 0, Ui.dp(this, 16));
        root.addView(open);

        TextView back = Ui.text(this, "Not now", 22, Ui.FG);
        back.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);

        timer = new CountDownTimer(prefs.pauseSeconds() * 1000L, 250) {
            @Override public void onTick(long ms) {
                open.setText("Open in " + (ms / 1000 + 1) + "…");
            }
            @Override public void onFinish() {
                open.setText("Open " + label);
                open.setTextColor(Ui.FG);
                open.setOnClickListener(v -> {
                    prefs.recordOpen(pkg);
                    Apps.launch(PauseActivity.this, pkg);
                    finish();
                });
            }
        }.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the pause screen resets it — no waiting in the background.
        if (timer != null) timer.cancel();
        finish();
    }
}
