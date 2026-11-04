package app.wifibudget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Data Saver. Uses Android's VPN interface purely as an on-device app filter:
 * every app that is NOT on the allowed list is routed into a dead-end interface whose
 * packets are thrown away, so those apps can't use any Wi-Fi data.
 * Allowed apps bypass it completely and work normally. Nothing is sent anywhere.
 */
public class SaverVpnService extends VpnService {
    public static final String ACTION_START = "app.wifibudget.START";
    public static final String ACTION_STOP = "app.wifibudget.STOP";
    static final int NOTIF_ID = 2;

    public static volatile boolean running;
    public static volatile String lastError;
    public static volatile int blockedCount;
    public static volatile int allowedCount;

    private ParcelFileDescriptor tun;
    private Drain drain;
    private String lastDay;
    private boolean stopping;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            if (running) handler.postDelayed(this, 60_000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSaver(true);
            return START_NOT_STICKY;
        }
        if (action == null && !Prefs.saverWanted(this)) { // restarted by the system but user turned it off
            stopSelf();
            return START_NOT_STICKY;
        }
        startSaver();
        return START_STICKY;
    }

    private void startSaver() {
        lastError = null;
        stopping = false;
        PackageManager pm = getPackageManager();
        Set<String> allowed = Prefs.allowed(this);
        Builder b = new Builder()
                .setSession("WiFi Budget Data Saver")
                .addAddress("10.215.173.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:7b:7b::1", 128)
                .addRoute("::", 0)
                .setBlocking(false);
        int n = 0, ok = 0;
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!Prefs.blockable(pm, ai, getPackageName())) continue;
            if (allowed.contains(ai.packageName)) {
                ok++;
                continue;
            }
            try {
                b.addAllowedApplication(ai.packageName); // "allowed into the filter" = blocked
                n++;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (n == 0) {
            lastError = "Every app is on the allowed list, so there is nothing to block.";
            stopSaver(true);
            return;
        }
        ParcelFileDescriptor fresh;
        try {
            fresh = b.establish();
        } catch (Exception e) {
            lastError = "Couldn't start Data Saver: " + e.getMessage();
            stopSaver(true);
            return;
        }
        if (fresh == null) {
            lastError = "VPN permission missing. Open WiFi Budget and switch Data Saver on again.";
            stopSaver(true);
            return;
        }
        ParcelFileDescriptor old = tun;
        Drain oldDrain = drain;
        tun = fresh;
        drain = new Drain(fresh);
        drain.start();
        if (oldDrain != null) oldDrain.alive = false;
        closeQuietly(old);

        running = true;
        stopping = false;
        blockedCount = n;
        allowedCount = ok;
        Prefs.setSaverWanted(this, true);
        lastDay = Usage.dayKey(System.currentTimeMillis());
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void onTick() {
        long now = System.currentTimeMillis();
        String day = Usage.dayKey(now);
        if (lastDay != null && !day.equals(lastDay) && Prefs.saverAuto(this) && Prefs.autoOffAtReset(this)) {
            Checker.alert(this, 11, "New day: Data Saver off",
                    Usage.fmt(Prefs.limitBytes(this)) + " available again.", null);
            stopSaver(true);
            return;
        }
        lastDay = day;
        Checker.run(this);
        showNotification(Usage.today(this));
    }

    private void showNotification(long used) {
        Intent stop = new Intent(this, SaverVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        String text = (used >= 0 ? Usage.fmt(used) + " of " + Usage.fmt(Prefs.limitBytes(this)) + " used · " : "")
                + blockedCount + " apps blocked";
        Notification n = new Notification.Builder(this, Checker.CH_SAVER)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle("Data Saver is on")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Turn off", stopPi).build())
                .build();
        Checker.channels(this);
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
        } catch (SecurityException ignored) { // notifications not allowed
        }
    }

    private void stopSaver(boolean byUser) {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacks(tick);
        if (drain != null) drain.alive = false;
        closeQuietly(tun);
        tun = null;
        drain = null;
        running = false;
        if (byUser) {
            Prefs.setSaverWanted(this, false);
            Prefs.setSaverAuto(this, false);
        }
        getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        stopSelf();
    }

    @Override
    public void onRevoke() { // user turned the VPN off in system settings, or another VPN took over
        stopSaver(true);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopSaver(false);
        super.onDestroy();
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }

    /** Reads and discards packets from blocked apps so they never leave the phone. */
    private static final class Drain extends Thread {
        volatile boolean alive = true;
        private final FileInputStream in;

        Drain(ParcelFileDescriptor pfd) {
            super("data-saver-drain");
            setDaemon(true);
            in = new FileInputStream(pfd.getFileDescriptor());
        }

        @Override
        public void run() {
            byte[] buf = new byte[32767];
            while (alive) {
                try {
                    if (in.read(buf) <= 0) Thread.sleep(100);
                } catch (IOException e) { // EAGAIN when idle, or the interface was closed
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        return;
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
