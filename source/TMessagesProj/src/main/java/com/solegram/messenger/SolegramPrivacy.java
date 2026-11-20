/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Solegram: the privacy switches where they act: links without tracking on sending and opening
 * ({@link SolegramLinkCleaner}), photos and videos sent as files without their metadata
 * ({@link SolegramMetadataStripper}, on a copy in the cache: the file on the phone is never changed).
 */
public final class SolegramPrivacy {

    private SolegramPrivacy() {
    }

    // region Links

    /** The text (or the caption) of an outgoing message with its links cleaned; the formatting follows the text. */
    public static void cleanOutgoing(SendMessagesHelper.SendMessageParams params) {
        if (!SolegramConfig.cleanLinks || params == null) {
            return;
        }
        try {
            final boolean isMessage = !TextUtils.isEmpty(params.message);
            final String text = isMessage ? params.message : params.caption;
            if (params.entities != null) {
                for (TLRPC.MessageEntity e : params.entities) {
                    if (e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null) {
                        e.url = SolegramLinkCleaner.cleanUrl(e.url); // a link hidden under a word
                    }
                }
            }
            if (TextUtils.isEmpty(text)) {
                return;
            }
            final ArrayList<SolegramLinkCleaner.Change> changes = SolegramLinkCleaner.changesIn(text);
            if (changes.isEmpty()) {
                return;
            }
            final String cleaned = SolegramLinkCleaner.cleanText(text);
            if (params.entities != null) {
                for (TLRPC.MessageEntity e : params.entities) {
                    final int start = map(e.offset, changes), end = map(e.offset + e.length, changes);
                    e.offset = start;
                    e.length = Math.max(0, end - start);
                }
            }
            if (isMessage) {
                params.message = cleaned;
            } else {
                params.caption = cleaned;
            }
        } catch (Throwable e) {
            FileLog.e(e); // the message goes as it was typed
        }
    }

    /** Where a position of the old text is in the cleaned one. */
    static int map(int pos, ArrayList<SolegramLinkCleaner.Change> changes) {
        int shift = 0;
        for (SolegramLinkCleaner.Change c : changes) {
            final int newLen = c.replacement.length();
            if (pos <= c.start) {
                break;
            }
            if (pos >= c.end) {
                shift += newLen - (c.end - c.start);
            } else {
                return c.start + shift + Math.min(pos - c.start, newLen);
            }
        }
        return pos + shift;
    }

    /** A web link to open without its tracking parameters (any other link as it is). */
    public static Uri cleanUri(Uri uri) {
        if (!SolegramConfig.cleanLinks || uri == null) {
            return uri;
        }
        final String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return uri;
        }
        try {
            final String url = uri.toString();
            final String clean = SolegramLinkCleaner.cleanUrl(url);
            return clean.equals(url) ? uri : Uri.parse(clean);
        } catch (Throwable e) {
            return uri;
        }
    }

    // endregion

    // region Files

    /**
     * A copy of a photo or video without its metadata, under the same file name (it is the name the recipient sees),
     * or null to send the file as it is (the switch is off, not a photo or video, or the copy failed). Background thread.
     */
    public static String strippedCopy(String path) {
        if (!SolegramConfig.stripFileMetadata || path == null) {
            return null;
        }
        try {
            final File src = new File(path);
            if (!src.isFile() || src.length() == 0 || SolegramMetadataStripper.kindOf(src) == SolegramMetadataStripper.NONE) {
                return null;
            }
            final File root = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "sg_clean");
            if (src.getAbsolutePath().startsWith(root.getAbsolutePath())) {
                return null; // already a cleaned copy
            }
            final File dir = new File(root, Utilities.MD5(src.getAbsolutePath() + "_" + src.length() + "_" + src.lastModified()));
            final File dst = new File(dir, src.getName());
            if (dst.isFile() && dst.length() > 0) {
                return dst.getAbsolutePath();
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            final File part = new File(dir, ".part");
            if (!SolegramMetadataStripper.strip(src, part) || !part.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return null;
            }
            return dst.getAbsolutePath();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // endregion
}
