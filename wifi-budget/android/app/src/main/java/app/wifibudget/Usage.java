package app.wifibudget;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Reads Wi-Fi data counters from Android's NetworkStatsManager. Days start at 00:00 UTC. */
public final class Usage {
    public static final long MB = 1_000_000L; // decimal, like the ship's portal
    public static final long DAY = 86_400_000L;

    private Usage() {}

    public static long dayStartUtc(long now) {
        return now - (now % DAY);
    }

    public static String dayKey(long now) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(now));
    }

    public static String utc(long t, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.getDefault());
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    public static boolean hasAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        } else {
            mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return c.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Total Wi-Fi bytes (down + up) for the whole phone between start and end. -1 if unavailable. */
    @SuppressWarnings("deprecation")
    public static long wifiBytes(Context c, long start, long end) {
        try {
            NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
            NetworkStats.Bucket b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, start, end);
            return b == null ? 0 : b.getRxBytes() + b.getTxBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Wi-Fi bytes used since 00:00 UTC today, plus any manual correction. */
    public static long today(Context c) {
        long now = System.currentTimeMillis();
        long raw = wifiBytes(c, dayStartUtc(now), now + 60_000);
        if (raw < 0) return -1;
        return Math.max(0, raw + Prefs.correction(c, dayKey(now)));
    }

    /** Raw counter for today, without correction (used when calibrating). */
    public static long todayRaw(Context c) {
        long now = System.currentTimeMillis();
        return wifiBytes(c, dayStartUtc(now), now + 60_000);
    }

    /** Usage for each of the last n UTC days, oldest first; today is the last entry. */
    public static long[] lastDays(Context c, int n) {
        long now = System.currentTimeMillis();
        long today = dayStartUtc(now);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            long start = today - (long) (n - 1 - i) * DAY;
            long end = i == n - 1 ? now + 60_000 : start + DAY;
            long v = wifiBytes(c, start, end);
            out[i] = Math.max(0, v + Prefs.correction(c, dayKey(start)));
        }
        return out;
    }

    public static final class AppUse {
        public final int uid;
        public final long bytes;
        public final String label;
        public final String pkg;

        AppUse(int uid, long bytes, String label, String pkg) {
            this.uid = uid;
            this.bytes = bytes;
            this.label = label;
            this.pkg = pkg;
        }
    }

    /** Per-uid Wi-Fi bytes in a time range. */
    @SuppressWarnings("deprecation")
    public static Map<Integer, Long> perUid(Context c, long start, long end) {
        Map<Integer, Long> m = new HashMap<>();
        try {
            NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
            NetworkStats ns = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, start, end);
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (ns.hasNextBucket()) {
                ns.getNextBucket(b);
                long v = b.getRxBytes() + b.getTxBytes();
                Long prev = m.get(b.getUid());
                m.put(b.getUid(), prev == null ? v : prev + v);
            }
            ns.close();
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Apps that used the most Wi-Fi today, biggest first. */
    public static List<AppUse> topAppsToday(Context c, int limit) {
        long now = System.currentTimeMillis();
        Map<Integer, Long> m = perUid(c, dayStartUtc(now), now + 60_000);
        List<AppUse> list = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (Map.Entry<Integer, Long> e : m.entrySet()) {
            if (e.getValue() <= 0) continue;
            int uid = e.getKey();
            String label, pkg = null;
            if (uid == NetworkStats.Bucket.UID_REMOVED) label = "Uninstalled apps";
            else if (uid == NetworkStats.Bucket.UID_TETHERING) label = "Hotspot / tethering";
            else if (uid == Process.SYSTEM_UID) label = "Android system";
            else if (uid == 0) label = "System (root)";
            else {
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    pkg = pkgs[0];
                    label = labelOf(pm, pkg);
                    if (pkgs.length > 1) label += " +" + (pkgs.length - 1);
                } else {
                    label = "System (uid " + uid + ")";
                }
            }
            list.add(new AppUse(uid, e.getValue(), label, pkg));
        }
        list.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public static String labelOf(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    public static String fmt(long n) {
        if (n < 0) return "-";
        if (n >= 1000 * MB) return String.format(Locale.US, "%.2f GB", n / (1000.0 * MB));
        if (n >= MB) return String.format(Locale.US, "%.1f MB", n / (double) MB);
        return String.format(Locale.US, "%d KB", n / 1000);
    }
}
