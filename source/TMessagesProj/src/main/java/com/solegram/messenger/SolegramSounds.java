/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Notification sounds the user added from the phone: any length and size, copied into the app and kept on this
 * device (Telegram's own tones are uploaded and limited to a few seconds). They show among the system tones.
 */
public final class SolegramSounds {

    private static final long MAX_SIZE = 20L * 1024 * 1024;

    public static final class Sound {
        public final String title;
        /** content:// address the notification system can read. */
        public final String uri;

        Sound(String title, String uri) {
            this.title = title;
            this.uri = uri;
        }
    }

    private SolegramSounds() {
    }

    private static File dir() {
        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "solegram_sounds");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String authority() {
        return ApplicationLoader.getApplicationId() + ".provider";
    }

    private static String uriOf(File file) {
        return FileProvider.getUriForFile(ApplicationLoader.applicationContext, authority(), file).toString();
    }

    /** The added sounds, by name. */
    public static ArrayList<Sound> list() {
        final ArrayList<Sound> result = new ArrayList<>();
        try {
            final File[] files = dir().listFiles();
            if (files == null) {
                return result;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                if (f.isFile() && f.length() > 0) {
                    String title = f.getName();
                    final int dot = title.lastIndexOf('.');
                    if (dot > 0) {
                        title = title.substring(0, dot);
                    }
                    result.add(new Sound(title, uriOf(f)));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    /** Whether a sound address is one of these (the notification system is let in to read it). */
    public static boolean isOwn(String uri) {
        return uri != null && uri.startsWith("content://" + authority() + "/solegram_sounds/");
    }

    /** Copies a picked audio file in; returns its address, or null when it could not be read. */
    public static String add(Uri source) {
        try {
            String name = null;
            try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(source, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    final int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        name = cursor.getString(index);
                    }
                }
            }
            if (TextUtils.isEmpty(name)) {
                name = "sound_" + System.currentTimeMillis() + ".mp3";
            }
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File target = new File(dir(), name);
            int n = 1;
            while (target.exists()) {
                final int dot = name.lastIndexOf('.');
                target = new File(dir(), dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot) : name + " (" + n + ")");
                n++;
            }
            long total = 0;
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    return null;
                }
                final byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_SIZE) {
                        break;
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (total == 0 || total > MAX_SIZE) {
                target.delete();
                return null;
            }
            return uriOf(target);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
