/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/** The background update jobs (check, download, install), see {@link AutoUpdater}. Main thread. */
public class UpdateJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            ApplicationLoader.postInitApplication();
            return AutoUpdater.onStartJob(this, params);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        try {
            return AutoUpdater.onStopJob(params);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }
}
