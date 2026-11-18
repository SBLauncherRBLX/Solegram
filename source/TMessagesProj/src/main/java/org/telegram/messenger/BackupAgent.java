package org.telegram.messenger;

import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupManager;
import android.app.backup.FullBackupDataOutput;
import android.app.backup.SharedPreferencesBackupHelper;

import java.io.File;

public class BackupAgent extends BackupAgentHelper {

    private static BackupManager backupManager;

    @Override
    public void onCreate() {
        SharedPreferencesBackupHelper helper = new SharedPreferencesBackupHelper(this, "saved_tokens", "saved_tokens_login");
        addHelper("prefs", helper);
    }

    /**
     * Solegram: a full backup (adb backup, a phone-to-phone transfer) would by default copy every file of the app:
     * the account sessions (tgnet.dat), the chats' database, the wallet, the plugins and their data. Only the same two
     * small files as the cloud backup go.
     */
    @Override
    public void onFullBackup(FullBackupDataOutput data) {
        final File prefs = new File(getApplicationInfo().dataDir, "shared_prefs");
        for (String name : new String[]{"saved_tokens", "saved_tokens_login"}) {
            final File file = new File(prefs, name + ".xml");
            if (file.isFile()) {
                fullBackupFile(file, data);
            }
        }
    }

    public static void requestBackup() {
        if (backupManager == null) {
            backupManager = new BackupManager(ApplicationLoader.applicationContext);
        }
        backupManager.dataChanged();
    }
}
