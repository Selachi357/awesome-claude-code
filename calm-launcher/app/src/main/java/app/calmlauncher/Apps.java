package app.calmlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Process;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Apps {
    static final class App {
        final String label;
        final String pkg;
        App(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }

    static List<App> load(Context c) {
        LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        UserHandle me = Process.myUserHandle();
        List<App> out = new ArrayList<>();
        for (LauncherActivityInfo i : la.getActivityList(null, me)) {
            String pkg = i.getApplicationInfo().packageName;
            if (pkg.equals(c.getPackageName())) continue;
            out.add(new App(i.getLabel().toString(), pkg));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static String labelFor(Context c, String pkg) {
        try {
            return c.getPackageManager()
                    .getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }

    /** Opens an app, routing "mindful" ones through the pause screen first. */
    static void open(Context c, String pkg) {
        Prefs p = new Prefs(c);
        if (p.isMindful(pkg)) {
            Intent i = new Intent(c, PauseActivity.class).putExtra("pkg", pkg);
            c.startActivity(i);
        } else {
            p.recordOpen(pkg);
            launch(c, pkg);
        }
    }
}
