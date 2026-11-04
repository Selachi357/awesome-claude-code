package app.wifibudget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Warnings at 50/75/90/100 % and automatic Data Saver. Runs every 15 min in the background. */
public final class Checker {
    static final String CH_ALERT = "alerts";
    static final String CH_SAVER = "saver";
    private static final int JOB_ID = 1;
    private static final int[] LEVELS = {50, 75, 90, 100};

    private Checker() {}

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Data warnings",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_SAVER, "Data Saver status",
                NotificationManager.IMPORTANCE_LOW));
    }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, CheckJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void run(Context c) {
        if (!Usage.hasAccess(c)) return;
        long used = Usage.today(c);
        if (used < 0) return;
        long limit = Prefs.limitBytes(c);
        String day = Usage.dayKey(System.currentTimeMillis());
        int pct = (int) (used * 100 / Math.max(limit, 1));

        int hit = 0;
        for (int l : LEVELS) if (pct >= l) hit = l;
        if (hit > Prefs.warnedLevel(c, day)) {
            Prefs.setWarnedLevel(c, day, hit);
            String left = Usage.fmt(Math.max(limit - used, 0));
            alert(c, 10, hit >= 100 ? "Daily Wi-Fi allowance used up" : hit + "% of today's Wi-Fi used",
                    hit >= 100 ? "Resets at 00:00 UTC." : left + " left until 00:00 UTC.", null);
        }

        int auto = Prefs.autoSaverPct(c);
        if (auto > 0 && pct >= auto && !SaverVpnService.running && !Prefs.autoTriggered(c, day)) {
            Prefs.setAutoTriggered(c, day);
            boolean started = false;
            if (VpnService.prepare(c) == null) { // permission already given once
                try {
                    Prefs.setSaverAuto(c, true);
                    c.startService(new Intent(c, SaverVpnService.class).setAction(SaverVpnService.ACTION_START));
                    started = true;
                } catch (RuntimeException e) { // Android may refuse background starts
                    Prefs.setSaverAuto(c, false);
                }
            }
            if (started) {
                alert(c, 12, "Data Saver switched on", pct + "% used. Only your allowed apps can use Wi-Fi now.", null);
            } else {
                Intent i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_AUTO_SAVER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                alert(c, 12, pct + "% used - turn on Data Saver?", "Tap to block apps you don't need.",
                        PendingIntent.getActivity(c, 3, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
        }
    }

    static void alert(Context c, int id, String title, String text, PendingIntent tap) {
        channels(c);
        if (tap == null) {
            tap = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        }
        Notification n = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException ignored) {
        }
    }
}
