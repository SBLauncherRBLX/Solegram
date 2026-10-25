/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.solegram.messenger.Solegram;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a downloaded APK and hands it to the system.
 *
 * <p>Nothing is installed unless the file is the same app (package name), signed with the same certificate
 * (or one rotated from it), newer than the running one (Solegram build and versionCode) and, when update.json
 * gives them, of the expected size and SHA-256.</p>
 */
final class UpdateInstaller {

    static final String ACTION_STATUS = "com.solegram.messenger.updater.INSTALL_STATUS";
    static final String EXTRA_BUILD = "solegram_build";

    /** @return null when the file may be installed, otherwise what is wrong with it (for the log and the status line) */
    static String verify(File apk, UpdateController.UpdateInfo info, boolean requireChecksum) {
        try {
            if (apk == null || !apk.isFile()) {
                return "no file";
            }
            // never a downgrade, never the same build again
            if (info.build <= Solegram.getBuild()) {
                return "build " + info.build + " is not newer";
            }
            if (info.size > 0 && apk.length() != info.size) {
                return "size";
            }
            if (requireChecksum && (TextUtils.isEmpty(info.sha256) || info.size <= 0)) {
                return "no checksum";
            }
            if (!TextUtils.isEmpty(info.sha256) && !info.sha256.equals(GithubUpdater.sha256(apk))) {
                return "sha256";
            }
            final Context context = ApplicationLoader.applicationContext;
            final PackageManager pm = context.getPackageManager();
            // Android 9 fills signingInfo of an archive only when GET_SIGNATURES is asked for too
            int flags = PackageManager.GET_SIGNATURES;
            if (Build.VERSION.SDK_INT >= 28) {
                flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            }
            final PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), flags);
            if (archive == null) {
                return "not an APK";
            }
            if (!context.getPackageName().equals(archive.packageName)) {
                return "package " + archive.packageName;
            }
            final PackageInfo own = pm.getPackageInfo(context.getPackageName(), flags);
            if (versionCode(archive) <= versionCode(own)) {
                return "versionCode " + versionCode(archive) + " is not newer";
            }
            if (!sameSigner(own, archive)) {
                return "signature";
            }
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName();
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static boolean sameSigner(PackageInfo own, PackageInfo archive) {
        if (Build.VERSION.SDK_INT >= 28 && own.signingInfo != null && archive.signingInfo != null) {
            final SigningInfo o = own.signingInfo, a = archive.signingInfo;
            if (o.hasMultipleSigners() || a.hasMultipleSigners()) {
                // several signers: exactly the same set, as Android requires for an update
                return o.hasMultipleSigners() && a.hasMultipleSigners() && digests(o.getApkContentsSigners()).equals(digests(a.getApkContentsSigners()));
            }
            // one signer: ours, or a newer key whose rotation proof includes ours
            final Set<String> ownCurrent = digests(o.getApkContentsSigners());
            final Set<String> archiveLineage = digests(a.getSigningCertificateHistory());
            return !ownCurrent.isEmpty() && archiveLineage.containsAll(ownCurrent);
        }
        final Set<String> o = digests(own.signatures), a = digests(archive.signatures);
        return !o.isEmpty() && o.equals(a);
    }

    private static Set<String> digests(Signature[] signatures) {
        final Set<String> out = new HashSet<>();
        if (signatures == null) {
            return out;
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : signatures) {
                if (s != null) {
                    out.add(hex(md.digest(s.toByteArray())));
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /**
     * Writes the APK into a PackageInstaller session and commits it. On Android 12+ the session asks for no
     * confirmation: a self-update of an app that targets a recent SDK and holds UPDATE_PACKAGES_WITHOUT_USER_ACTION
     * then installs silently (and the process is killed). The result comes to {@link UpdateReceiver}, also when the
     * system wants a confirmation after all. Background thread.
     */
    static void commit(File apk, UpdateController.UpdateInfo info) throws Exception {
        final Context context = ApplicationLoader.applicationContext;
        final PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        // a session left from an earlier try (abandoned, or one the user never confirmed) holds a copy of the APK
        for (PackageInstaller.SessionInfo old : installer.getMySessions()) {
            try {
                installer.abandonSession(old.getSessionId());
            } catch (Throwable ignore) {
            }
        }
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 26) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        final int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            // the bytes that go to the system are hashed again: what is installed is exactly what was checked
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                session.fsync(out);
            }
            if (!TextUtils.isEmpty(info.sha256) && !info.sha256.equals(hex(digest.digest()))) {
                throw new SecurityException("sha256 changed");
            }
            final Intent status = new Intent(context, UpdateReceiver.class)
                .setAction(ACTION_STATUS)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_BUILD, info.build);
            // mutable: the system fills in the status; the intent is explicit, so nobody else can redirect it
            final int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            final PendingIntent pending = PendingIntent.getBroadcast(context, 0x5E1AD5, status, flags);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(id);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** The system installer for the file: one tap on "Update" (older Android, or when a confirmation is needed). */
    static Intent viewIntent(File apk) {
        final Context context = ApplicationLoader.applicationContext;
        final Uri uri = Build.VERSION.SDK_INT >= 24
            ? FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", apk)
            : Uri.fromFile(apk);
        return new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private UpdateInstaller() {
    }
}
