package app.calmlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Small wrapper around SharedPreferences: pinned apps, "mindful" apps and daily open counts. */
final class Prefs {
    static final int MAX_PINNED = 6;

    /** Apps that get a pause screen by default — the usual infinite-scroll suspects. */
    private static final Set<String> DEFAULT_MINDFUL = new HashSet<>(Arrays.asList(
            "com.instagram.android",
            "com.zhiliaoapp.musically",      // TikTok
            "com.ss.android.ugc.trill",      // TikTok (some regions)
            "com.facebook.katana",
            "com.google.android.youtube",
            "com.twitter.android",           // X
            "com.snapchat.android",
            "com.reddit.frontpage",
            "com.pinterest",
            "com.netflix.mediaclient",
            "tv.twitch.android.app",
            "com.linkedin.android"
    ));

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("calm", Context.MODE_PRIVATE);
        if (!sp.contains("mindful")) {
            sp.edit().putStringSet("mindful", DEFAULT_MINDFUL).apply();
        }
    }

    // Stored as a "|"-joined string so pin order is preserved.
    Set<String> pinned() {
        String raw = sp.getString("pinned", "");
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split("\\|")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    boolean togglePinned(String pkg) {
        Set<String> p = pinned();
        boolean nowPinned;
        if (p.remove(pkg)) {
            nowPinned = false;
        } else {
            if (p.size() >= MAX_PINNED) return false;
            p.add(pkg);
            nowPinned = true;
        }
        sp.edit().putString("pinned", String.join("|", p)).apply();
        return nowPinned;
    }

    boolean isMindful(String pkg) {
        return sp.getStringSet("mindful", new HashSet<>()).contains(pkg);
    }

    void toggleMindful(String pkg) {
        Set<String> m = new HashSet<>(sp.getStringSet("mindful", new HashSet<>()));
        if (!m.remove(pkg)) m.add(pkg);
        sp.edit().putStringSet("mindful", m).apply();
    }

    int pauseSeconds() { return sp.getInt("pause_seconds", 10); }

    void setPauseSeconds(int s) { sp.edit().putInt("pause_seconds", s).apply(); }

    private static String todayKey(String pkg) { return "opens_" + LocalDate.now() + "_" + pkg; }

    int opensToday(String pkg) { return sp.getInt(todayKey(pkg), 0); }

    void recordOpen(String pkg) {
        String k = todayKey(pkg);
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply();
    }
}
