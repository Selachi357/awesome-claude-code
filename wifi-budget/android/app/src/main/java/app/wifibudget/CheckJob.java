package app.wifibudget;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background check every 15 minutes (survives reboots). */
public class CheckJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Checker.run(getApplicationContext());
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
