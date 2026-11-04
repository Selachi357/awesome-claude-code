package app.wifibudget;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Settings stored on the phone. */
public final class Prefs {
    private Prefs() {}

    /** Apps allowed by default in Data Saver (only those that are installed are used). */
    public static final List<String> RECOMMENDED = Arrays.asList(
            // messaging & calls
            "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms", "org.telegram.messenger",
            "com.facebook.orca", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.viber.voip", "jp.naver.line.android", "com.skype.raider", "com.discord",
            // email
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
            // browsers (so you can still look things up)
            "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser",
            "com.brave.browser", "com.microsoft.emmx",
            // needed for push notifications and the ship's Wi-Fi login page
            "com.google.android.gms", "com.google.android.gsf",
            "com.android.captiveportallogin", "com.google.android.captiveportallogin",
            "com.android.networkstack", "com.google.android.networkstack",
            "com.android.networkstack.tethering", "com.google.android.networkstack.tethering");

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("wifibudget", Context.MODE_PRIVATE);
    }

    public static long limitBytes(Context c) {
        return (long) (sp(c).getFloat("limit_mb", 1500f) * Usage.MB);
    }

    public static float limitMb(Context c) {
        return sp(c).getFloat("limit_mb", 1500f);
    }

    public static void setLimitMb(Context c, float mb) {
        sp(c).edit().putFloat("limit_mb", mb).apply();
    }

    public static int autoSaverPct(Context c) {
        return sp(c).getInt("auto_pct", 80);
    }

    public static void setAutoSaverPct(Context c, int pct) {
        sp(c).edit().putInt("auto_pct", pct).apply();
    }

    public static boolean autoOffAtReset(Context c) {
        return sp(c).getBoolean("auto_off", true);
    }

    public static void setAutoOffAtReset(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_off", v).apply();
    }

    /** Manual correction so the number matches the ship's portal (bytes, can be negative). */
    public static long correction(Context c, String day) {
        return sp(c).getLong("corr_" + day, 0);
    }

    public static void setCorrection(Context c, String day, long bytes) {
        sp(c).edit().putLong("corr_" + day, bytes).apply();
    }

    /** Highest warning level already shown today. */
    public static int warnedLevel(Context c, String day) {
        SharedPreferences p = sp(c);
        return day.equals(p.getString("warned_day", "")) ? p.getInt("warned_level", 0) : 0;
    }

    public static void setWarnedLevel(Context c, String day, int level) {
        sp(c).edit().putString("warned_day", day).putInt("warned_level", level).apply();
    }

    public static boolean autoTriggered(Context c, String day) {
        return day.equals(sp(c).getString("auto_day", ""));
    }

    public static void setAutoTriggered(Context c, String day) {
        sp(c).edit().putString("auto_day", day).apply();
    }

    public static boolean saverWanted(Context c) {
        return sp(c).getBoolean("saver_on", false);
    }

    public static void setSaverWanted(Context c, boolean on) {
        sp(c).edit().putBoolean("saver_on", on).apply();
    }

    /** True if Data Saver was switched on automatically (so it switches off at the reset). */
    public static boolean saverAuto(Context c) {
        return sp(c).getBoolean("saver_auto", false);
    }

    public static void setSaverAuto(Context c, boolean v) {
        sp(c).edit().putBoolean("saver_auto", v).apply();
    }

    public static Set<String> allowed(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains("allowed")) {
            Set<String> s = new HashSet<>(RECOMMENDED);
            s.add(c.getPackageName());
            p.edit().putStringSet("allowed", s).apply();
            return s;
        }
        return new HashSet<>(p.getStringSet("allowed", new HashSet<>()));
    }

    public static void setAllowed(Context c, Set<String> s) {
        sp(c).edit().putStringSet("allowed", new HashSet<>(s)).apply();
    }

    /** Apps that can use the internet and can be blocked individually. */
    public static boolean blockable(PackageManager pm, ApplicationInfo ai, String self) {
        if (ai.uid < Process.FIRST_APPLICATION_UID) return false; // core system, shares uid with Android
        if (ai.packageName.equals(self)) return false;
        return pm.checkPermission(android.Manifest.permission.INTERNET, ai.packageName)
                == PackageManager.PERMISSION_GRANTED;
    }
}
