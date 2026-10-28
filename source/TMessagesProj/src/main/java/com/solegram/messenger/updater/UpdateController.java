/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.text.TextUtils;

import com.solegram.messenger.Solegram;
import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Over-the-air updates published in a Telegram channel.
 *
 * <p>Publishing a release: post the APK into the channel (default @solegram_updates, configurable)
 * with a file name like {@code Solegram-1.0.1-b2.apk} — or put {@code build: 2} / {@code #b2} and
 * {@code version: 1.0.1} in the caption. The rest of the caption is shown as the changelog.
 * The newest post with a higher build number than the installed one is offered to the user.</p>
 */
public class UpdateController implements NotificationCenter.NotificationCenterDelegate {

    public static class UpdateInfo {
        public int build;
        public String versionName;
        public String changelog;
        public java.util.ArrayList<TLRPC.MessageEntity> entities;
        public TLRPC.Document document;
        public MessageObject messageObject;
        public long size;
        public int date;
        /** GitHub source: direct APK link and its expected SHA-256. */
        public String url;
        public String sha256;
        /** GitHub source: a link that always serves the newest APK, for downloading in the browser. */
        public String latestUrl;
        /** SHA-256 of the file as UpdateInstaller.verify checked it (signature included); only those bytes are installed. Not saved. */
        public String verifiedSha256;
    }

    private static final long AUTO_CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
    /** A start the user sees checks more often; the periodic job covers the rest (AutoUpdater). */
    private static final long VISIBLE_START_CHECK_INTERVAL = 30 * 60 * 1000L;
    private static final Pattern FILE_PATTERN = Pattern.compile("(?i)solegram[-_ ]v?(\\d+(?:\\.\\d+)*)(?:[-_ ]+(?:b|build)?(\\d+))?");
    private static final Pattern BUILD_PATTERN = Pattern.compile("(?i)(?:#b|build\\s*[:=]?\\s*#?)(\\d+)");
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)version\\s*[:=]?\\s*v?(\\d+(?:\\.\\d+)+)");
    private static final Pattern TAG_LINE = Pattern.compile("(?im)^\\s*(#\\w+\\s*)+$|(?im)^\\s*(build|version)\\s*[:=].*$");

    private static volatile UpdateController instance;

    private volatile boolean checking;
    private UpdateInfo pending;
    private String downloadingName;
    private float downloadProgress;
    private boolean downloadFailed;
    private UpdateSheet visibleSheet;
    private java.lang.ref.WeakReference<android.app.Activity> sheetActivity;
    private long checkStartedAt;
    private int githubDownloadingBuild;
    /** The running GitHub download was started by AutoUpdater, not from the sheet. */
    private boolean backgroundDownload;

    public static UpdateController getInstance() {
        if (instance == null) {
            synchronized (UpdateController.class) {
                if (instance == null) {
                    instance = new UpdateController();
                }
            }
        }
        return instance;
    }

    public boolean isChecking() {
        return checking;
    }

    public boolean hasPendingUpdate() {
        return pending != null && pending.build > Solegram.getBuild();
    }

    public UpdateInfo getPending() {
        return pending;
    }

    public String getPendingVersionName() {
        return pending == null ? "" : pending.versionName + " (" + pending.build + ")";
    }

    public void scheduleAutoCheck() {
        AutoUpdater.start();
        if (!SolegramConfig.autoUpdateCheck) {
            return;
        }
        final long interval = AutoUpdater.isAppVisible() ? VISIBLE_START_CHECK_INTERVAL : AUTO_CHECK_INTERVAL;
        if (Math.abs(System.currentTimeMillis() - SolegramConfig.lastUpdateCheck) < interval) {
            return;
        }
        checkNow(null, false);
    }

    private static int findAccount() {
        if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return UserConfig.selectedAccount;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    /** @param fragment used to report the result of a manual check (bulletins), may be null */
    public void checkNow(BaseFragment fragment, boolean manual) {
        if (checking && !(manual && System.currentTimeMillis() - checkStartedAt > 60_000)) {
            return;
        }
        checkStartedAt = System.currentTimeMillis();
        if (SolegramConfig.updateSource == SolegramConfig.UPDATE_SOURCE_GITHUB) {
            checking = true;
            postChanged();
            GithubUpdater.check((info, error) -> finishCheck(fragment, manual, info, error == null ? null : LocaleController.formatString(R.string.SG_UpdateGithubError, error)));
            return;
        }
        final int account = findAccount();
        final String channel = SolegramConfig.getUpdateChannel();
        if (account < 0 || TextUtils.isEmpty(channel)) {
            if (manual && fragment != null) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.SG_UpdateNoAccount)).show();
            }
            return;
        }
        checking = true;
        postChanged();
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = channel;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                finishCheck(fragment, manual, null, LocaleController.formatString(R.string.SG_UpdateChannelNotFound, channel));
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            TLRPC.Chat chat = null;
            for (TLRPC.Chat c : resolved.chats) {
                if (resolved.peer != null && c.id == resolved.peer.channel_id) {
                    chat = c;
                    break;
                }
            }
            if (chat == null) {
                finishCheck(fragment, manual, null, LocaleController.formatString(R.string.SG_UpdateChannelNotFound, channel));
                return;
            }
            final TLRPC.Chat channelChat = chat;
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(resolved.users, false);
                MessagesController.getInstance(account).putChats(resolved.chats, false);
            });
            TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
            peer.channel_id = channelChat.id;
            peer.access_hash = channelChat.access_hash;
            history.peer = peer;
            history.limit = 30;
            ConnectionsManager.getInstance(account).sendRequest(history, (response2, error2) -> {
                if (!(response2 instanceof TLRPC.messages_Messages)) {
                    finishCheck(fragment, manual, null, error2 != null ? error2.text : "error");
                    return;
                }
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response2;
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    UpdateInfo best = null;
                    for (TLRPC.Message msg : res.messages) {
                        UpdateInfo info = parse(account, msg);
                        if (info != null && (best == null || info.build > best.build)) {
                            best = info;
                        }
                    }
                    finishCheck(fragment, manual, best, null);
                });
            });
        });
    }

    private void finishCheck(BaseFragment fragment, boolean manual, UpdateInfo found, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            checking = false;
            // written directly: putLong re-reads every setting and bumps SolegramConfig.version, which makes each
            // open chat lay out all its messages again on resume - for a time stamp that changes no message
            final long now = System.currentTimeMillis();
            SolegramConfig.prefs().edit().putLong("lastUpdateCheck", now).apply();
            SolegramConfig.lastUpdateCheck = now;
            if (error != null) {
                AutoUpdater.onCheckFinished(null, error);
                if (manual && fragment != null) {
                    BulletinFactory.of(fragment).createErrorBulletin(error).show();
                }
                postChanged();
                return;
            }
            if (found != null && found.build > Solegram.getBuild()) {
                if (pending == null || pending.build != found.build || !isDownloading(pending)) {
                    pending = found; // a running download keeps its own copy of the release
                }
                postChanged();
                // auto mode downloads and installs it by itself: no sheet then (a manual check still shows it)
                final boolean automatic = AutoUpdater.onCheckFinished(found, null);
                if (!(automatic && !manual) && (manual || found.build != SolegramConfig.skippedUpdateBuild)) {
                    BaseFragment target = fragment != null ? fragment : LaunchActivity.getLastFragment();
                    if (target != null && (manual || AutoUpdater.isAppVisible())) {
                        showUpdateSheet(target);
                    } else if (!manual && SolegramConfig.autoUpdateMode != SolegramConfig.AUTO_UPDATE_OFF) {
                        AutoUpdater.notifyAvailable(found);
                    }
                }
            } else {
                pending = null;
                AutoUpdater.onCheckFinished(null, null);
                postChanged();
                if (manual && fragment != null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.SG_UpToDate)).show();
                }
            }
        });
    }

    void postChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramUpdateAvailable));
    }

    static UpdateInfo parse(int account, TLRPC.Message msg) {
        if (msg == null || !(msg.media instanceof TLRPC.TL_messageMediaDocument) || msg.media.document == null) {
            return null;
        }
        TLRPC.Document doc = msg.media.document;
        String fileName = FileLoader.getDocumentFileName(doc);
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            return null;
        }
        String caption = msg.message != null ? msg.message : "";
        int build = 0;
        String version = null;
        Matcher m = BUILD_PATTERN.matcher(caption);
        if (m.find()) {
            build = parseInt(m.group(1));
        }
        m = VERSION_PATTERN.matcher(caption);
        if (m.find()) {
            version = m.group(1);
        }
        m = FILE_PATTERN.matcher(fileName);
        if (m.find()) {
            if (version == null) {
                version = m.group(1);
            }
            if (build == 0 && m.group(2) != null) {
                build = parseInt(m.group(2));
            }
        }
        if (build <= 0) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if ((lower.contains("armeabi") || lower.contains("armv7") || lower.contains("x86")) && !lower.contains("universal")) {
            return null;
        }
        UpdateInfo info = new UpdateInfo();
        info.build = build;
        info.versionName = version != null ? version : String.valueOf(build);
        info.changelog = TAG_LINE.matcher(caption).replaceAll("").trim();
        info.entities = null;
        info.document = doc;
        info.size = doc.size;
        info.date = msg.date;
        info.messageObject = new MessageObject(account, msg, false, false);
        return info;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    public void skip(UpdateInfo info) {
        SolegramConfig.putInt("skippedUpdateBuild", info.build);
        AutoUpdater.onSkipped(info);
    }

    public void showUpdateSheet(BaseFragment fragment) {
        if (pending == null || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (visibleSheet != null) {
            // only a sheet that is really on screen blocks a new one; a sheet that never made it on screen
            // (the screen was in a transition) or whose activity is gone must not block the button forever
            final android.app.Activity activity = sheetActivity == null ? null : sheetActivity.get();
            if (visibleSheet.isShown() && visibleSheet.isShowing() && activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                return;
            }
            try {
                visibleSheet.dismiss(); // releases its observer
            } catch (Exception ignore) {
            }
            visibleSheet = null;
        }
        final UpdateSheet sheet = new UpdateSheet(fragment, pending);
        // BaseFragment.showDialog replaces the dismiss listener, the hide listener stays ours
        sheet.setOnHideListener(d -> {
            if (visibleSheet == sheet) {
                visibleSheet = null;
            }
        });
        if (fragment.showDialog(sheet) == null) {
            try {
                sheet.show(); // the screen was busy with a transition: show the sheet on its own
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
        }
        visibleSheet = sheet;
        sheetActivity = new java.lang.ref.WeakReference<>(fragment.getParentActivity());
    }

    /** Where the update can be downloaded by hand: the APK link (GitHub) or the update channel. */
    public String getBrowserUrl() {
        if (pending != null && pending.latestUrl != null) {
            return pending.latestUrl;
        }
        if (pending != null && pending.url != null) {
            return pending.url;
        }
        if (SolegramConfig.updateSource == SolegramConfig.UPDATE_SOURCE_GITHUB) {
            return "https://github.com/" + SolegramConfig.getUpdateRepo() + "#readme";
        }
        return "https://t.me/" + SolegramConfig.getUpdateChannel();
    }

    public void openInBrowser(android.content.Context context) {
        // the system browser, not Telegram's in-app one: it downloads the APK and hands it to the installer
        final String url = getBrowserUrl();
        try {
            final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            org.telegram.messenger.browser.Browser.openUrl(context, url);
        }
    }

    // region Download

    public File getApkFile(UpdateInfo info) {
        if (info.url != null) {
            return GithubUpdater.apkFile(info);
        }
        return FileLoader.getInstance(info.messageObject.currentAccount).getPathToMessage(info.messageObject.messageOwner);
    }

    public boolean isDownloaded(UpdateInfo info) {
        File f = getApkFile(info);
        // GitHub downloads are renamed into place only after the SHA-256 check passed
        return f != null && f.exists() && (info.url != null || f.length() >= info.size);
    }

    public boolean isDownloading(UpdateInfo info) {
        if (info.url != null) {
            return githubDownloadingBuild == info.build;
        }
        return downloadingName != null && downloadingName.equals(FileLoader.getAttachFileName(info.document));
    }

    public float getDownloadProgress() {
        return downloadProgress;
    }

    public boolean isDownloadFailed() {
        return downloadFailed;
    }

    /** A newer release found by the background updater becomes the one the sheet and the settings show. */
    void setPendingIfNewer(UpdateInfo info) {
        if (info != null && (pending == null || pending.build < info.build)) {
            pending = info;
            postChanged();
        }
    }

    public boolean isBackgroundDownload() {
        return backgroundDownload && githubDownloadingBuild != 0;
    }

    /** The background updater downloads a GitHub release; the sheet shows its progress like its own. */
    void downloadInBackground(UpdateInfo info) {
        download(info, true);
    }

    void cancelBackgroundDownload() {
        if (isBackgroundDownload()) {
            GithubUpdater.cancel();
            githubDownloadingBuild = 0;
            postChanged();
        }
    }

    public void download(UpdateInfo info) {
        download(info, false);
    }

    private void download(UpdateInfo info, boolean background) {
        if (isDownloading(info)) {
            return;
        }
        if (info.url != null) {
            githubDownloadingBuild = info.build;
            backgroundDownload = background;
            downloadProgress = 0;
            downloadFailed = false;
            postChanged();
            // update.json may have changed since it was read (a newer release replaces the APK it named):
            // read it again and download the file it names now
            GithubUpdater.check((fresh, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (githubDownloadingBuild != info.build) {
                    return; // cancelled meanwhile
                }
                if (fresh != null && fresh.build > info.build && fresh.build > Solegram.getBuild()) {
                    // a newer version came out: offer that one instead (its own sheet shows the progress)
                    pending = fresh;
                    githubDownloadingBuild = 0;
                    if (background) {
                        postChanged();
                        if (!AutoUpdater.onCheckFinished(fresh, null)) {
                            AutoUpdater.onDownloadCancelled();
                        }
                        return;
                    }
                    if (visibleSheet != null) {
                        try {
                            visibleSheet.dismiss();
                        } catch (Exception ignore) {
                        }
                        visibleSheet = null;
                    }
                    final BaseFragment target = LaunchActivity.getLastFragment();
                    if (target != null) {
                        showUpdateSheet(target);
                    }
                    download(fresh);
                    return;
                }
                startGithubDownload(fresh != null && fresh.build == info.build ? copyLinks(info, fresh) : info);
            }));
            return;
        }
        int account = info.messageObject.currentAccount;
        downloadingName = FileLoader.getAttachFileName(info.document);
        downloadProgress = 0;
        downloadFailed = false;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.fileLoaded);
        nc.addObserver(this, NotificationCenter.fileLoadProgressChanged);
        nc.addObserver(this, NotificationCenter.fileLoadFailed);
        FileLoader.getInstance(account).loadFile(info.document, info.messageObject, FileLoader.PRIORITY_HIGH, 0);
        postChanged();
    }

    private void startGithubDownload(UpdateInfo info) {
        githubDownloadingBuild = info.build;
        GithubUpdater.download(info, new GithubUpdater.DownloadListener() {
            @Override
            public void onProgress(float progress) {
                AndroidUtilities.runOnUIThread(() -> {
                    downloadProgress = progress;
                    postChanged();
                });
            }

            @Override
            public void onDone(boolean success, String error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (githubDownloadingBuild != info.build) {
                        return; // cancelled
                    }
                    final boolean background = backgroundDownload;
                    githubDownloadingBuild = 0;
                    backgroundDownload = false;
                    downloadFailed = !success;
                    downloadProgress = success ? 1f : 0;
                    postChanged();
                    AutoUpdater.onDownloadFinished(info, success, error, background);
                    if (success && !background) {
                        install(info);
                    }
                });
            }
        });
    }

    /** The same release as listed now: take its current file links (the file may have moved). */
    private static UpdateInfo copyLinks(UpdateInfo info, UpdateInfo fresh) {
        if (TextUtils.equals(info.sha256, fresh.sha256)) {
            info.url = fresh.url;
            info.latestUrl = fresh.latestUrl;
        }
        return info;
    }

    public void cancelDownload(UpdateInfo info) {
        if (!isDownloading(info)) {
            return;
        }
        if (info.url != null) {
            GithubUpdater.cancel();
            githubDownloadingBuild = 0;
            backgroundDownload = false;
            postChanged();
            AutoUpdater.onDownloadCancelled();
            return;
        }
        FileLoader.getInstance(info.messageObject.currentAccount).cancelLoadFile(info.document);
        stopObserving(info.messageObject.currentAccount);
        downloadingName = null;
        postChanged();
    }

    private void stopObserving(int account) {
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.fileLoaded);
        nc.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        nc.removeObserver(this, NotificationCenter.fileLoadFailed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (downloadingName == null || args.length == 0 || !downloadingName.equals(args[0])) {
            return;
        }
        if (id == NotificationCenter.fileLoadProgressChanged) {
            Long loaded = (Long) args[1];
            Long total = (Long) args[2];
            downloadProgress = total != null && total > 0 ? Math.min(1f, loaded / (float) total) : 0;
            postChanged();
        } else if (id == NotificationCenter.fileLoaded) {
            downloadProgress = 1f;
            downloadingName = null;
            stopObserving(account);
            postChanged();
            if (pending != null) {
                AndroidUtilities.runOnUIThread(() -> install(pending));
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            downloadingName = null;
            downloadFailed = true;
            stopObserving(account);
            postChanged();
        }
    }

    public void install(UpdateInfo info) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        File f = getApkFile(info);
        if (f == null || !f.exists()) {
            downloadFailed = true;
            postChanged();
            return;
        }
        // the file is checked first (same app, same signature, newer, checksum): AutoUpdater then installs it
        AutoUpdater.installByUser(info, f);
    }

    /** The system installer for the file (Android 11 and older, where every install is confirmed there). */
    void openInstaller(UpdateInfo info, File f) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        try {
            String name = info.document != null ? FileLoader.getDocumentFileName(info.document) : f.getName();
            AndroidUtilities.openForView(f, name, "application/vnd.android.package-archive", fragment.getParentActivity(), null, false);
        } catch (Throwable e) {
            FileLog.e(e);
            showInstallError(e.getClass().getSimpleName());
        }
    }

    /** The downloaded file failed its check and is gone: the sheet offers the download again. */
    void onInstallRejected() {
        downloadFailed = true;
        downloadProgress = 0;
        postChanged();
        final BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null) {
            return;
        }
        try {
            final BulletinFactory factory = visibleSheet != null && visibleSheet.isShowing()
                ? BulletinFactory.of(visibleSheet.getContainer(), null) : BulletinFactory.of(fragment);
            factory.createErrorBulletin(LocaleController.getString(R.string.SG_UpdateRejected)).show();
        } catch (Throwable ignore) {
        }
    }

    /** Never fail silently: say why and offer the browser. */
    void showInstallError(String reason) {
        final BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        try {
            // over the update sheet when it is open, otherwise the bulletin would hide behind it
            final BulletinFactory factory = visibleSheet != null && visibleSheet.isShowing()
                ? BulletinFactory.of(visibleSheet.getContainer(), null) : BulletinFactory.of(fragment);
            factory.createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.SG_UpdateInstallFailed, reason),
                LocaleController.getString(R.string.SG_UpdateInBrowser), () -> openInBrowser(fragment.getParentActivity())).show();
        } catch (Throwable ignore) {
        }
    }
    // endregion
}
