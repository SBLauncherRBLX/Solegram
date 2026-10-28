/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.solegram.messenger.Solegram;
import com.solegram.messenger.SolegramConfig;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates without the user's hands (GitHub source, "download and install automatically").
 *
 * <p>A periodic job checks update.json every 6 hours (and the app checks on start). A newer build is downloaded
 * in the background, resumably, by a job that waits for Wi-Fi (by default) and a battery that is not low. The file
 * must match the size and SHA-256 of update.json, be this app (package name), be signed with our certificate and
 * be newer, or it is deleted. Then:</p>
 * <ul>
 * <li>Android 12+: a PackageInstaller session that asks for no confirmation. A self-update of an app holding
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION and targeting a recent SDK installs silently. When the system still wants a
 * confirmation (STATUS_PENDING_USER_ACTION) one tap is left: a notification, or the update sheet in the app.</li>
 * <li>Older Android always asks, so the notification "Update ready, tap to install" opens the system installer.</li>
 * </ul>
 * <p>The install restarts the process, so it never runs while the app is open, during a call, a recording or
 * while Telegram plays audio: it waits until the app has been in the background for a while or the screen is off.
 * On the first start of the new version a bulletin says so, with what is new.</p>
 */
public final class AutoUpdater {

    static final int JOB_CHECK = 0x5E1AD01;
    static final int JOB_DOWNLOAD = 0x5E1AD02;
    static final int JOB_INSTALL = 0x5E1AD03;

    private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
    /** After the user leaves: picking a photo in another app or a quick switch must not end in a restart. */
    private static final long BACKGROUND_GRACE = 2 * 60 * 1000L;
    /** A call, a recording or audio playing: look again later. */
    private static final long BUSY_RETRY = 10 * 60 * 1000L;
    private static final int LOW_BATTERY = 20;

    private static final String CHANNEL_ID = "solegram_updates";
    private static final int NOTIFICATION_ID = 0x5E1AD0;

    static final int STATUS_NONE = 0;
    static final int STATUS_UP_TO_DATE = 1;
    static final int STATUS_AVAILABLE = 2;
    static final int STATUS_WAIT_WIFI = 3;
    static final int STATUS_WAIT_NETWORK = 4;
    static final int STATUS_WAIT_BATTERY = 5;
    static final int STATUS_DOWNLOADING = 6;
    static final int STATUS_READY = 7;
    static final int STATUS_CONFIRM = 8;
    static final int STATUS_INSTALLING = 9;
    static final int STATUS_INSTALLED = 10;
    static final int STATUS_REJECTED = 11;
    static final int STATUS_FAILED = 12;

    private static final Pattern FILE_BUILD = Pattern.compile("-b(\\d+)(?:-[0-9a-f]*)?\\.apk(?:\\.part)?$");

    private static boolean started;
    /** elapsedRealtime when the app went to the background in this process; 0 = it was not open in this process. */
    private static long backgroundSince;
    private static boolean installing;
    /** A "new version" notification was posted instead of the sheet: the sheet comes when the app is opened. */
    private static boolean sheetOnForeground;
    private static int sheetShownBuild;
    private static int foregroundRetries;
    private static DispatchQueue queue;

    private static final class JobRef {
        final JobService service;
        final JobParameters params;

        JobRef(JobService service, JobParameters params) {
            this.service = service;
            this.params = params;
        }
    }

    /** Jobs that keep the process alive while their work runs. Main thread. */
    private static final ArrayList<JobRef> jobs = new ArrayList<>();

    private static final ForegroundDetector.Listener foregroundListener = new ForegroundDetector.Listener() {
        @Override
        public void onBecameForeground() {
            foregroundRetries = 0;
            AndroidUtilities.runOnUIThread(AutoUpdater::onForeground, 1200);
        }

        @Override
        public void onBecameBackground() {
            backgroundSince = SystemClock.elapsedRealtime();
            onBackground();
        }
    };

    // region State

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_updater", Activity.MODE_PRIVATE);
    }

    private static synchronized DispatchQueue queue() {
        if (queue == null) {
            queue = new DispatchQueue("solegramUpdateInstall");
        }
        return queue;
    }

    private static boolean isAuto() {
        return SolegramConfig.autoUpdateMode == SolegramConfig.AUTO_UPDATE_INSTALL;
    }

    /** Only a GitHub release with a size and checksum is installed without the user; a skipped version never is. */
    static boolean canAutoInstall(UpdateController.UpdateInfo info) {
        return isAuto() && info != null && info.url != null && !TextUtils.isEmpty(info.sha256) && info.size > 0
            && info.build > Solegram.getBuild() && info.build != SolegramConfig.skippedUpdateBuild
            && !info.sha256.equals(prefs().getString("rejectedSha", null));
    }

    private static void setStatus(int status, UpdateController.UpdateInfo info, String error) {
        prefs().edit()
            .putInt("status", status)
            .putInt("statusBuild", info != null ? info.build : 0)
            .putString("statusVersion", info != null ? info.versionName : null)
            .putString("statusError", error)
            .apply();
        UpdateController.getInstance().postChanged();
    }

    private static int status() {
        return prefs().getInt("status", STATUS_NONE);
    }

    private static void saveInfo(String key, UpdateController.UpdateInfo info) {
        try {
            final JSONObject o = new JSONObject();
            o.put("build", info.build);
            o.put("version", info.versionName);
            o.put("size", info.size);
            o.put("sha256", info.sha256);
            o.put("url", info.url);
            o.put("latest", info.latestUrl);
            o.put("changelog", info.changelog);
            prefs().edit().putString(key, o.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static UpdateController.UpdateInfo loadInfo(String key) {
        final String json = prefs().getString(key, null);
        if (json == null) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject(json);
            final UpdateController.UpdateInfo info = new UpdateController.UpdateInfo();
            info.build = o.optInt("build");
            info.versionName = o.optString("version", String.valueOf(info.build));
            info.size = o.optLong("size");
            info.sha256 = o.optString("sha256", "");
            info.url = o.optString("url", null);
            info.latestUrl = o.isNull("latest") ? null : o.optString("latest", null);
            info.changelog = o.optString("changelog", "");
            return info.url != null && info.build > 0 ? info : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The release this build number belongs to, as well as it is known here. */
    private static UpdateController.UpdateInfo infoFor(int build) {
        UpdateController.UpdateInfo info = loadInfo("ready");
        if (info != null && info.build == build) {
            return info;
        }
        info = UpdateController.getInstance().getPending();
        if (info != null && info.build == build) {
            return info;
        }
        info = new UpdateController.UpdateInfo();
        info.build = build;
        info.versionName = String.valueOf(build);
        return info;
    }

    /** Remembered before the install, for the message on the first start of the new version. */
    private static void markInstalling(UpdateController.UpdateInfo info) {
        prefs().edit()
            .putInt("installBuild", info.build)
            .putString("installChangelog", info.changelog)
            .apply();
    }
    // endregion

    // region Start, schedule, settings

    /** Once per process: the update schedule, the clean-up and the "updated" message. Main thread. */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        final ForegroundDetector fd = ForegroundDetector.getInstance();
        if (fd != null) {
            fd.addListener(foregroundListener);
        }
        final SharedPreferences p = prefs();
        final int current = Solegram.getBuild();
        final int lastRun = p.getInt("lastRunBuild", 0);
        final SharedPreferences.Editor e = p.edit();
        if (lastRun != current) {
            e.putInt("lastRunBuild", current);
            if (lastRun > 0 && lastRun < current && p.getInt("installBuild", 0) == current) {
                // the first start after an update installed from here: say so once, with what is new
                e.putInt("bulletinBuild", current)
                    .putInt("status", STATUS_INSTALLED)
                    .putInt("statusBuild", current)
                    .putString("statusVersion", Solegram.getVersionName())
                    .remove("statusError");
            }
            cancelNotification();
        }
        // what waited for a version that is installed by now (or older) is done
        final UpdateController.UpdateInfo ready = loadInfo("ready");
        if (ready != null && ready.build <= current) {
            e.remove("ready");
        }
        final UpdateController.UpdateInfo target = loadInfo("target");
        if (target != null && target.build <= current) {
            e.remove("target");
        }
        if (p.getInt("confirmBuild", 0) <= current) {
            e.remove("confirmBuild");
        }
        e.apply();
        queue().postRunnable(AutoUpdater::cleanUp);
        schedule();
        if (isAppVisible()) {
            AndroidUtilities.runOnUIThread(AutoUpdater::onForeground, 1500);
        }
    }

    /** APKs of this version or older, and their partial downloads. */
    private static void cleanUp() {
        final File[] files = GithubUpdater.updateDir().listFiles();
        if (files == null) {
            return;
        }
        final int current = Solegram.getBuild();
        for (File f : files) {
            final Matcher m = FILE_BUILD.matcher(f.getName());
            if (!m.find() || parseInt(m.group(1)) <= current) {
                f.delete();
            }
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static JobScheduler scheduler() {
        return (JobScheduler) ApplicationLoader.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static ComponentName component() {
        return new ComponentName(ApplicationLoader.applicationContext, UpdateJobService.class);
    }

    private static JobInfo pendingJob(JobScheduler js, int id) {
        if (Build.VERSION.SDK_INT >= 24) {
            return js.getPendingJob(id);
        }
        for (JobInfo info : js.getAllPendingJobs()) {
            if (info.getId() == id) {
                return info;
            }
        }
        return null;
    }

    private static boolean hasJob(int id) {
        for (JobRef ref : jobs) {
            if (ref.params.getJobId() == id) {
                return true;
            }
        }
        return false;
    }

    /** The periodic check (any network: update.json is a few hundred bytes); nothing when updates are off. */
    static void schedule() {
        try {
            final JobScheduler js = scheduler();
            if (js == null) {
                return;
            }
            if (SolegramConfig.autoUpdateMode == SolegramConfig.AUTO_UPDATE_OFF) {
                js.cancel(JOB_CHECK);
                js.cancel(JOB_DOWNLOAD);
                js.cancel(JOB_INSTALL);
                return;
            }
            if (pendingJob(js, JOB_CHECK) == null && !hasJob(JOB_CHECK)) {
                final JobInfo.Builder b = new JobInfo.Builder(JOB_CHECK, component())
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
                if (Build.VERSION.SDK_INT >= 24) {
                    b.setPeriodic(CHECK_INTERVAL, 60 * 60 * 1000L);
                } else {
                    b.setPeriodic(CHECK_INTERVAL);
                }
                js.schedule(b.build());
            }
            if (!isAuto()) {
                js.cancel(JOB_DOWNLOAD);
                js.cancel(JOB_INSTALL);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The mode or Wi-Fi only changed in the settings. */
    public static void onSettingsChanged() {
        start();
        try {
            final JobScheduler js = scheduler();
            // the download job carries the network rule: set it up again with the new one
            if (js != null && !hasJob(JOB_DOWNLOAD)) {
                js.cancel(JOB_DOWNLOAD);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        schedule();
        final UpdateController uc = UpdateController.getInstance();
        final UpdateController.UpdateInfo pending = uc.getPending();
        if (SolegramConfig.autoUpdateMode == SolegramConfig.AUTO_UPDATE_OFF) {
            cancelNotification();
        }
        if (!isAuto()) {
            if (uc.isBackgroundDownload()) {
                uc.cancelBackgroundDownload();
            }
            finishJobs(false, false);
        } else if (canAutoInstall(pending)) {
            onUpdateFound(pending);
        } else {
            final UpdateController.UpdateInfo target = loadInfo("target");
            if (canAutoInstall(target)) {
                onUpdateFound(target);
            }
        }
        uc.postChanged();
    }

    /**
     * Shown once: "install unknown apps" is what lets Solegram hand its update to the system installer.
     * @return whether the explanation was shown
     */
    public static boolean guideUnknownSources(BaseFragment fragment) {
        if (Build.VERSION.SDK_INT < 26 || fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context.getPackageManager().canRequestPackageInstalls() || prefs().getBoolean("unknownAsked", false)) {
            return false;
        }
        prefs().edit().putBoolean("unknownAsked", true).apply();
        final Activity activity = fragment.getParentActivity();
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SG_UpdateUnknownTitle));
        builder.setMessage(LocaleController.getString(R.string.SG_UpdateUnknownText));
        builder.setPositiveButton(LocaleController.getString(R.string.SG_UpdateUnknownOpen), (d, w) -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())));
            } catch (Throwable e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName())));
                } catch (Throwable ignore) {
                }
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
        return true;
    }
    // endregion

    // region Jobs

    static boolean onStartJob(JobService service, JobParameters params) {
        start();
        final int id = params.getJobId();
        if (SolegramConfig.autoUpdateMode == SolegramConfig.AUTO_UPDATE_OFF) {
            AndroidUtilities.runOnUIThread(AutoUpdater::schedule); // cancels the jobs
            return false;
        }
        final JobRef ref = new JobRef(service, params);
        if (id == JOB_CHECK) {
            final UpdateController uc = UpdateController.getInstance();
            jobs.add(ref);
            uc.checkNow(null, false);
            if (!uc.isChecking()) {
                jobs.remove(ref); // nothing to check with (a Telegram channel and no account)
                return false;
            }
            return true;
        }
        if (!isAuto()) {
            return false;
        }
        if (id == JOB_DOWNLOAD) {
            UpdateController.UpdateInfo info = UpdateController.getInstance().getPending();
            if (!canAutoInstall(info)) {
                info = loadInfo("target");
            }
            if (!canAutoInstall(info)) {
                return false;
            }
            jobs.add(ref);
            final int blocker = downloadBlocker();
            if (blocker != 0) {
                // the job constraints let it run but the phone says otherwise (battery saver): later, with a backoff
                setStatus(blocker, info, null);
                AndroidUtilities.runOnUIThread(() -> finishJob(ref, true));
                return true;
            }
            onUpdateFound(info);
            return true;
        }
        if (id == JOB_INSTALL) {
            final UpdateController.UpdateInfo ready = loadInfo("ready");
            if (ready == null || ready.build <= Solegram.getBuild()) {
                return false;
            }
            jobs.add(ref);
            prepareInstall(ready);
            return true;
        }
        return false;
    }

    static boolean onStopJob(JobParameters params) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            if (jobs.get(i).params.getJobId() == params.getJobId()) {
                jobs.remove(i);
            }
        }
        final int id = params.getJobId();
        if (id == JOB_DOWNLOAD) {
            // out of time or off Wi-Fi: stop, the part stays and the next run resumes it
            final UpdateController uc = UpdateController.getInstance();
            if (uc.isBackgroundDownload() && !isAppVisible()) {
                uc.cancelBackgroundDownload();
            }
            return true;
        }
        return id == JOB_INSTALL;
    }

    private static void finishJob(JobRef ref, boolean retry) {
        if (jobs.remove(ref)) {
            try {
                ref.service.jobFinished(ref.params, retry);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** @param checks the check jobs, otherwise the download and install ones; retry: download jobs back off and run again */
    private static void finishJobs(boolean checks, boolean retry) {
        for (JobRef ref : new ArrayList<>(jobs)) {
            final boolean isCheck = ref.params.getJobId() == JOB_CHECK;
            if (isCheck == checks) {
                finishJob(ref, retry && ref.params.getJobId() == JOB_DOWNLOAD);
            }
        }
    }

    private static void scheduleDownload(UpdateController.UpdateInfo info) {
        saveInfo("target", info);
        if (hasJob(JOB_DOWNLOAD)) {
            return; // running now: it carries on (scheduling it again would stop it)
        }
        try {
            final JobScheduler js = scheduler();
            if (js == null) {
                return;
            }
            final int network = SolegramConfig.updateWifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
            final JobInfo pending = pendingJob(js, JOB_DOWNLOAD);
            if (pending != null && pending.getNetworkType() == network) {
                return;
            }
            final JobInfo.Builder b = new JobInfo.Builder(JOB_DOWNLOAD, component())
                .setRequiredNetworkType(network)
                .setPersisted(true)
                .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            if (Build.VERSION.SDK_INT >= 26) {
                b.setRequiresBatteryNotLow(true);
                b.setRequiresStorageNotLow(true);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                b.setEstimatedNetworkBytes(info.size, 0);
            }
            js.schedule(b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void scheduleInstall(long delay) {
        if (hasJob(JOB_INSTALL)) {
            return;
        }
        try {
            final JobScheduler js = scheduler();
            if (js != null) {
                js.schedule(new JobInfo.Builder(JOB_INSTALL, component())
                    .setMinimumLatency(delay)
                    .setPersisted(true)
                    .build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    // endregion

    // region Check, download

    /**
     * After every check (UpdateController). Main thread.
     * @return true when the update is taken care of here (downloaded and installed by itself), so no sheet is shown
     */
    static boolean onCheckFinished(UpdateController.UpdateInfo found, String error) {
        start();
        try {
            if (error != null) {
                final int s = status();
                if (s != STATUS_READY && s != STATUS_CONFIRM && s != STATUS_DOWNLOADING) {
                    setStatus(STATUS_FAILED, null, error);
                }
                return false;
            }
            if (found == null || found.build <= Solegram.getBuild()) {
                prefs().edit().remove("target").remove("ready").apply();
                setStatus(STATUS_UP_TO_DATE, null, null);
                return false;
            }
            if (!canAutoInstall(found)) {
                // a file that failed its check keeps saying so until a new one is published
                final boolean rejected = status() == STATUS_REJECTED && found.sha256 != null && found.sha256.equals(prefs().getString("rejectedSha", null));
                if (!rejected) {
                    setStatus(STATUS_AVAILABLE, found, null);
                }
                return false;
            }
            onUpdateFound(found);
            return true;
        } finally {
            finishJobs(true, false);
        }
    }

    /** A newer release that may be installed by itself: download it (or install what is downloaded). Main thread. */
    private static void onUpdateFound(UpdateController.UpdateInfo info) {
        final UpdateController uc = UpdateController.getInstance();
        uc.setPendingIfNewer(info);
        if (uc.isDownloaded(info)) {
            prepareInstall(info);
            return;
        }
        // the job keeps the process alive for the download and resumes it if the process dies
        scheduleDownload(info);
        final int blocker = downloadBlocker();
        if (blocker != 0) {
            if (uc.isBackgroundDownload()) {
                uc.cancelBackgroundDownload(); // moved off Wi-Fi, battery saver came on: the part waits
            }
            setStatus(blocker, info, null);
            finishJobs(false, true);
            return;
        }
        if (!uc.isDownloading(info)) {
            setStatus(STATUS_DOWNLOADING, info, null);
            uc.downloadInBackground(info);
        }
    }

    /** 0, or why a download in the background has to wait. */
    private static int downloadBlocker() {
        final Context context = ApplicationLoader.applicationContext;
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo network = cm != null ? cm.getActiveNetworkInfo() : null;
            if (network == null || !network.isConnected()) {
                return STATUS_WAIT_NETWORK;
            }
            if (SolegramConfig.updateWifiOnly && cm.isActiveNetworkMetered()) {
                return STATUS_WAIT_WIFI;
            }
        } catch (Throwable ignore) {
        }
        try {
            final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                return STATUS_WAIT_BATTERY;
            }
            final BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                final int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                final boolean charging = Build.VERSION.SDK_INT >= 23 && bm.isCharging();
                if (level > 0 && level < LOW_BATTERY && !charging) {
                    return STATUS_WAIT_BATTERY;
                }
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    /** Every download of a GitHub release ends here (UpdateController). Main thread. */
    static void onDownloadFinished(UpdateController.UpdateInfo info, boolean success, String error, boolean background) {
        if (!background) {
            finishJobs(false, false); // the user downloads it from the sheet: it installs from there
            return;
        }
        if (success) {
            prepareInstall(info);
            return;
        }
        final boolean rejected = error != null && error.startsWith("verification");
        if (rejected) {
            prefs().edit().putString("rejectedSha", info.sha256).remove("target").apply();
        }
        setStatus(rejected ? STATUS_REJECTED : STATUS_FAILED, info, error);
        // a lost connection: the job runs again later and resumes; a wrong file is not downloaded again
        finishJobs(false, !rejected);
    }

    /** "Skip this version" in the sheet: nothing of it is downloaded or installed by itself any more. */
    static void onSkipped(UpdateController.UpdateInfo info) {
        final UpdateController uc = UpdateController.getInstance();
        if (uc.isBackgroundDownload()) {
            uc.cancelBackgroundDownload();
        }
        prefs().edit().remove("ready").remove("target").apply();
        try {
            final JobScheduler js = scheduler();
            if (js != null) {
                if (!hasJob(JOB_DOWNLOAD)) {
                    js.cancel(JOB_DOWNLOAD);
                }
                if (!hasJob(JOB_INSTALL)) {
                    js.cancel(JOB_INSTALL);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        finishJobs(false, false);
        setStatus(STATUS_AVAILABLE, info, null);
    }

    static void onDownloadCancelled() {
        finishJobs(false, false);
        final UpdateController.UpdateInfo pending = UpdateController.getInstance().getPending();
        if (pending != null) {
            setStatus(STATUS_AVAILABLE, pending, null);
        }
    }
    // endregion

    // region Install

    /** Checks the downloaded file again and installs it, or asks for the one tap. Main thread. */
    private static void prepareInstall(UpdateController.UpdateInfo info) {
        if (!canAutoInstall(info)) {
            // the mode changed, the version was skipped, or this file was rejected before
            prefs().edit().remove("ready").apply();
            finishJobs(false, false);
            return;
        }
        queue().postRunnable(() -> {
            final File apk = GithubUpdater.apkFile(info);
            final boolean exists = apk.isFile();
            final String problem = exists ? UpdateInstaller.verify(apk, info, true) : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (!exists) {
                    // the system cleared the cache meanwhile: download it again
                    prefs().edit().remove("ready").apply();
                    if (canAutoInstall(info)) {
                        onUpdateFound(info);
                    } else {
                        finishJobs(false, false);
                    }
                    return;
                }
                if (problem != null) {
                    FileLog.d("solegram update " + info.build + " rejected: " + problem);
                    apk.delete();
                    final SharedPreferences.Editor e = prefs().edit().remove("ready").remove("target");
                    if (!problem.startsWith("build ")) {
                        e.putString("rejectedSha", info.sha256);
                    }
                    e.apply();
                    setStatus(STATUS_REJECTED, info, problem);
                    finishJobs(false, false);
                    return;
                }
                saveInfo("ready", info);
                prefs().edit().remove("target").apply();
                if (Build.VERSION.SDK_INT >= 31 && prefs().getInt("confirmBuild", 0) != info.build) {
                    if (status() != STATUS_READY || prefs().getInt("statusBuild", 0) != info.build) {
                        setStatus(STATUS_READY, info, null);
                    }
                    tryInstall(info);
                } else {
                    setStatus(STATUS_CONFIRM, info, null);
                    askToInstall(info, null);
                    finishJobs(false, false);
                }
            });
        });
    }

    /** -1: the app is open; 0: now; otherwise how long to wait. */
    private static long installWait() {
        if (isAppVisible()) {
            return -1;
        }
        if (isBusy()) {
            return BUSY_RETRY;
        }
        if (backgroundSince > 0 && isInteractive()) {
            final long left = BACKGROUND_GRACE - (SystemClock.elapsedRealtime() - backgroundSince);
            if (left > 0) {
                return Math.max(left, 30_000L);
            }
        }
        return 0;
    }

    private static void tryInstall(UpdateController.UpdateInfo info) {
        final long wait = installWait();
        if (wait < 0) {
            // the app is open: it installs once the user leaves it (onBackground)
            finishJobs(false, false);
            onForeground();
            return;
        }
        if (wait > 0) {
            finishJobs(false, false); // before scheduling: scheduling a running job would stop it
            scheduleInstall(wait);
            return;
        }
        install(info, GithubUpdater.apkFile(info), false);
    }

    /** In a call (Telegram or the phone), recording, playing a voice message or music in Telegram, or uploading. */
    private static boolean isBusy() {
        try {
            if (VoIPService.getSharedInstance() != null) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        try {
            final AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                final int mode = am.getMode();
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
                    return true;
                }
                if (Build.VERSION.SDK_INT >= 24 && !am.getActiveRecordingConfigurations().isEmpty()) {
                    return true; // voice or video messages (and the camera's video) record through the microphone
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            final MediaController mc = MediaController.getInstance();
            if (mc.getPlayingMessageObject() != null && !mc.isMessagePaused()) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        try {
            // a video or a file still going up would have to start over
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.isSlotInUse(a) && FileLoader.getInstance(a).isUploadingAnything()) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean isInteractive() {
        try {
            final PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable e) {
            return true;
        }
    }

    static boolean isAppVisible() {
        final ForegroundDetector fd = ForegroundDetector.getInstance();
        return fd != null && fd.isForeground();
    }

    /** Hands the file to the system (a session on Android 5+; the result comes to onInstallStatus). Main thread. */
    private static void install(UpdateController.UpdateInfo info, File apk, boolean byUser) {
        if (installing) {
            return;
        }
        installing = true;
        markInstalling(info);
        setStatus(STATUS_INSTALLING, info, null);
        queue().postRunnable(() -> {
            String error = null;
            try {
                UpdateInstaller.commit(apk, info);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                installing = false;
                if (err != null) {
                    onInstallFailed(info, err, byUser);
                }
                // committed: the system installs it now (and ends this process), or reports why not
                finishJobs(false, false);
            });
        });
    }

    /**
     * "Install" in the update sheet: the same checks (the checksum only when the source gives one), then the
     * session on Android 12+ (no system dialog for a self-update), the system installer before.
     */
    static void installByUser(UpdateController.UpdateInfo info, File apk) {
        queue().postRunnable(() -> {
            final String problem = UpdateInstaller.verify(apk, info, false);
            AndroidUtilities.runOnUIThread(() -> {
                final UpdateController uc = UpdateController.getInstance();
                if (problem != null) {
                    FileLog.d("solegram update " + info.build + " rejected: " + problem);
                    if (info.url != null) {
                        apk.delete(); // a GitHub file is downloaded again; a Telegram file stays Telegram's
                    }
                    setStatus(STATUS_REJECTED, info, problem);
                    uc.onInstallRejected();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    install(info, apk, true);
                } else {
                    markInstalling(info);
                    uc.openInstaller(info, apk);
                }
            });
        });
    }

    /** From UpdateReceiver: what the system did with the session. Main thread. */
    @SuppressWarnings("deprecation")
    static void onInstallStatus(Intent intent) {
        start();
        final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        final int build = intent.getIntExtra(UpdateInstaller.EXTRA_BUILD, 0);
        final String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        final UpdateController.UpdateInfo info = infoFor(build);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                // the system wants a confirmation after all (first time, a policy, a throttle): one tap is left
                prefs().edit().putInt("confirmBuild", build).apply();
                setStatus(STATUS_CONFIRM, info, null);
                Intent confirm = null;
                try {
                    confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                } catch (Throwable ignore) {
                }
                if (!UpdateInstaller.isSystemConfirmation(confirm)) {
                    confirm = null; // then the installer for the file is offered instead
                }
                askToInstall(info, confirm);
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                setStatus(STATUS_INSTALLED, info, null);
                cancelNotification();
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // "Cancel" in the system dialog (or a session replaced by a newer try): it stays one tap away
                if (!installing && build > Solegram.getBuild()) {
                    setStatus(STATUS_CONFIRM, info, null);
                }
                break;
            default:
                onInstallFailed(info, message != null ? message : "status " + status, isAppVisible());
                break;
        }
    }

    private static void onInstallFailed(UpdateController.UpdateInfo info, String error, boolean tellUser) {
        // the next try goes through the system installer, where the user sees what is wrong
        prefs().edit().putInt("confirmBuild", info.build).remove("installBuild").apply();
        setStatus(STATUS_FAILED, info, error);
        if (tellUser) {
            UpdateController.getInstance().showInstallError(error);
        }
    }

    /**
     * One tap to install: the system's confirmation (when it gave one) or the installer for the file; in the app a
     * bulletin or the system dialog right away, outside it a notification.
     */
    private static void askToInstall(UpdateController.UpdateInfo info, Intent confirm) {
        final Context context = ApplicationLoader.applicationContext;
        if (isAppVisible()) {
            if (confirm != null) {
                try {
                    context.startActivity(new Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            onForeground();
            return;
        }
        try {
            final PendingIntent intent;
            if (confirm != null) {
                intent = PendingIntent.getActivity(context, 1, new Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            } else {
                final File apk = GithubUpdater.apkFile(info);
                if (!apk.isFile()) {
                    return;
                }
                intent = PendingIntent.getActivity(context, 2, UpdateInstaller.viewIntent(apk), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            }
            markInstalling(info);
            notify("ready:" + info.build + (confirm != null ? ":s" : ""), "Solegram " + info.versionName,
                LocaleController.getString(R.string.SG_UpdateNotifReady), intent, true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Notify mode with the app in the background: a notification, and the sheet when the app is opened. */
    static void notifyAvailable(UpdateController.UpdateInfo info) {
        sheetOnForeground = true;
        final Context context = ApplicationLoader.applicationContext;
        try {
            final Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch == null) {
                return;
            }
            final PendingIntent intent = PendingIntent.getActivity(context, 3, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notify("available:" + info.build, "Solegram " + info.versionName, LocaleController.getString(R.string.SG_UpdateNotifAvailable), intent, false);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    // endregion

    // region In the app

    private static void onBackground() {
        final UpdateController.UpdateInfo ready = loadInfo("ready");
        if (ready != null && ready.build > Solegram.getBuild() && isAuto() && Build.VERSION.SDK_INT >= 31
            && status() == STATUS_READY && prefs().getInt("confirmBuild", 0) != ready.build) {
            // a job, not a delayed runnable: a process in the background may be frozen, a job wakes it
            scheduleInstall(BACKGROUND_GRACE);
        }
    }

    /** The app came to the screen: the "updated" message, or the update waiting for the user. Main thread. */
    private static void onForeground() {
        if (!isAppVisible()) {
            return;
        }
        final BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null || SharedConfig.isWaitingForPasscodeEnter) {
            if (foregroundRetries++ < 20) {
                AndroidUtilities.runOnUIThread(AutoUpdater::onForeground, 3000);
            }
            return;
        }
        if (showUpdatedBulletin(fragment)) {
            return;
        }
        final UpdateController uc = UpdateController.getInstance();
        final UpdateController.UpdateInfo ready = loadInfo("ready");
        final int s = status();
        if (ready != null && ready.build > Solegram.getBuild()) {
            if (s == STATUS_CONFIRM) {
                if (guideUnknownSources(fragment)) {
                    return;
                }
                uc.setPendingIfNewer(ready);
                if (sheetShownBuild != ready.build) {
                    // older Android, or the system asked: the sheet with its one "Install" tap
                    sheetShownBuild = ready.build;
                    uc.showUpdateSheet(fragment);
                }
                return;
            }
            if (s == STATUS_READY && prefs().getInt("readyBulletinBuild", 0) != ready.build) {
                prefs().edit().putInt("readyBulletinBuild", ready.build).apply();
                uc.setPendingIfNewer(ready);
                try {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.download_finish,
                        LocaleController.formatString(R.string.SG_UpdateReadyBulletin, ready.versionName),
                        LocaleController.getString(R.string.SG_UpdateInstallNow), () -> uc.install(ready)).show();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                return;
            }
        }
        if (sheetOnForeground) {
            sheetOnForeground = false;
            if (uc.hasPendingUpdate()) {
                uc.showUpdateSheet(fragment);
            }
        }
    }

    private static boolean showUpdatedBulletin(BaseFragment fragment) {
        final SharedPreferences p = prefs();
        if (p.getInt("bulletinBuild", 0) != Solegram.getBuild()) {
            return false;
        }
        final String changelog = p.getString("installChangelog", "");
        p.edit().remove("bulletinBuild").remove("installBuild").apply();
        final String version = Solegram.getVersionName();
        final String text = LocaleController.formatString(R.string.SG_UpdatedTo, version);
        try {
            if (TextUtils.isEmpty(changelog)) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, text).show();
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, text, LocaleController.getString(R.string.SG_WhatsNew),
                    () -> showChangelog(fragment, version, changelog)).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return true;
    }

    /** The notes of the installed version: the update sheet's look (icon, title, notes as a list), not a plain alert. */
    private static void showChangelog(BaseFragment fragment, String version, String changelog) {
        UpdateSheet.showChangelog(fragment, version, changelog);
    }

    /** For the settings: what the updater is doing, or null. */
    public static String getStatusText() {
        final UpdateController uc = UpdateController.getInstance();
        final UpdateController.UpdateInfo pending = uc.getPending();
        if (pending != null && uc.isDownloading(pending)) {
            return LocaleController.formatString(R.string.SG_UpdStatusDownloading, pending.versionName, Math.round(Math.max(0f, Math.min(1f, uc.getDownloadProgress())) * 100));
        }
        final SharedPreferences p = prefs();
        final int s = p.getInt("status", STATUS_NONE);
        final int build = p.getInt("statusBuild", 0);
        final String version = p.getString("statusVersion", String.valueOf(build));
        final String error = p.getString("statusError", "");
        final int current = Solegram.getBuild();
        String text = null;
        // a state of a version that is installed by now (or older) is history
        final boolean stale = build > 0 && (s == STATUS_INSTALLED ? build != current : build <= current);
        if (s == STATUS_UP_TO_DATE || stale) {
            text = LocaleController.getString(R.string.SG_UpdStatusUpToDate);
        } else if (s == STATUS_INSTALLED) {
            text = LocaleController.formatString(R.string.SG_UpdStatusInstalled, Solegram.getVersionName());
        } else if (s == STATUS_AVAILABLE || s == STATUS_DOWNLOADING) {
            text = LocaleController.formatString(R.string.SG_UpdStatusAvailable, version);
        } else if (s == STATUS_WAIT_WIFI) {
            text = LocaleController.formatString(R.string.SG_UpdStatusWaitWifi, version);
        } else if (s == STATUS_WAIT_NETWORK) {
            text = LocaleController.formatString(R.string.SG_UpdStatusWaitNetwork, version);
        } else if (s == STATUS_WAIT_BATTERY) {
            text = LocaleController.formatString(R.string.SG_UpdStatusWaitBattery, version);
        } else if (s == STATUS_READY) {
            text = LocaleController.formatString(R.string.SG_UpdStatusReady, version);
        } else if (s == STATUS_CONFIRM) {
            text = LocaleController.formatString(R.string.SG_UpdStatusConfirm, version);
        } else if (s == STATUS_INSTALLING) {
            text = LocaleController.formatString(R.string.SG_UpdStatusInstalling, version);
        } else if (s == STATUS_REJECTED) {
            text = LocaleController.formatString(R.string.SG_UpdStatusRejected, error);
        } else if (s == STATUS_FAILED && !TextUtils.isEmpty(error)) {
            text = LocaleController.formatString(R.string.SG_UpdStatusFailed, error);
        }
        if (isAuto() && SolegramConfig.updateSource != SolegramConfig.UPDATE_SOURCE_GITHUB) {
            final String note = LocaleController.getString(R.string.SG_UpdStatusManualOnly);
            text = text == null ? note : text + "\n" + note;
        }
        return text;
    }
    // endregion

    // region Notifications

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.SG_UpdatesChannel), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    /** @param key once per version and kind: a dismissed notification does not come back every 6 hours */
    private static void notify(String key, String title, String text, PendingIntent intent, boolean installButton) {
        final SharedPreferences p = prefs();
        if (key.equals(p.getString("notified", null))) {
            return;
        }
        p.edit().putString("notified", key).apply();
        final Context context = ApplicationLoader.applicationContext;
        ensureChannel(context);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setColor(0xff11acfa)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(intent);
        if (installButton) {
            builder.addAction(R.drawable.msg_download, LocaleController.getString(R.string.SG_UpdateInstall), intent);
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            FileLog.e(e); // notifications are not allowed (Android 13+): the sheet comes when the app is opened
        }
    }

    private static void cancelNotification() {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
        } catch (Throwable ignore) {
        }
        prefs().edit().remove("notified").apply();
    }
    // endregion

    private AutoUpdater() {
    }
}
