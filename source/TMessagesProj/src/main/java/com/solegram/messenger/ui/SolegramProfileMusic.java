/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Bitmap;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramNowPlaying;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ProfileMusicView;

/**
 * The music on a profile (Telegram's own: the song the user put on the profile) as Solegram's card shows it:
 * its title and artist, its cover (the file's own, else the one Telegram's player finds for it) and, while it plays
 * here, its progress.
 */
public final class SolegramProfileMusic implements NotificationCenter.NotificationCenterDelegate {

    /** The look in which Telegram shows it: a slim line under the photo, no card. */
    public static final int STYLE_TELEGRAM = 3;

    /** Whether profiles show Solegram's card (and not Telegram's line under the photo). */
    public static boolean cardOn() {
        return SolegramConfig.profileMusicStyle != STYLE_TELEGRAM;
    }

    private final int account;
    private final Runnable onChanged;
    @Nullable
    private TLRPC.Document document;
    @Nullable
    private Bitmap art;
    private ImageReceiver receiver;
    private boolean attached;

    public SolegramProfileMusic(int account, Runnable onChanged) {
        this.account = account;
        this.onChanged = onChanged;
    }

    /** The profile's music, or null when it has none. */
    public void setDocument(@Nullable TLRPC.Document document) {
        if (document == this.document || document != null && this.document != null && document.id == this.document.id) {
            return;
        }
        this.document = document;
        art = null;
        loadCover();
        onChanged.run();
    }

    @Nullable
    public TLRPC.Document getDocument() {
        return document;
    }

    /** What the card shows now, or null. */
    @Nullable
    public SolegramNowPlaying.Track track() {
        final TLRPC.Document d = document;
        if (d == null) {
            return null;
        }
        final CharSequence title = ProfileMusicView.getTitle(d), artist = ProfileMusicView.getAuthor(d);
        final long duration = (long) (MessageObject.getDocumentDuration(d) * 1000);
        boolean playing = false;
        long position = 0;
        final MessageObject now = MediaController.getInstance().getPlayingMessageObject();
        if (now != null && now.getDocument() != null && now.getDocument().id == d.id) {
            playing = !MediaController.getInstance().isMessagePaused();
            position = (long) (now.audioProgress * duration);
        }
        return SolegramNowPlaying.pinned(title == null ? "" : title.toString(), artist == null ? "" : artist.toString(), duration, playing, position, art);
    }

    /** The cover as Telegram's player finds it: the file's own picture, else one looked up by the song's name. */
    private void loadCover() {
        if (receiver != null) {
            receiver.onDetachedFromWindow();
            receiver = null;
        }
        final TLRPC.Document d = document;
        if (d == null) {
            return;
        }
        ImageLocation full = null, small = null;
        final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(d.thumbs, 360);
        if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
            small = ImageLocation.getForDocument(thumb, d);
        }
        final String url = MessageObject.getArtworkUrl(d, false);
        if (!TextUtils.isEmpty(url)) {
            full = ImageLocation.getForPath(url);
        }
        if (small == null) {
            final String smallUrl = MessageObject.getArtworkUrl(d, true);
            if (!TextUtils.isEmpty(smallUrl)) {
                small = ImageLocation.getForPath(smallUrl);
            }
        }
        if (full == null && small == null) {
            return;
        }
        final ImageReceiver r = new ImageReceiver();
        receiver = r;
        r.setDelegate((imageReceiver, set, isThumb, memCache) -> {
            if (!set || receiver != imageReceiver || document != d) {
                return;
            }
            final Bitmap bitmap = imageReceiver.getBitmap();
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            if (isThumb && art != null) {
                return; // the full cover came first
            }
            try {
                // a copy of its own: the image cache may recycle the one it hands out
                final int size = Math.min(320, Math.max(bitmap.getWidth(), bitmap.getHeight()));
                final Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                art = copy.getWidth() == size && copy.getHeight() == size ? copy : Bitmap.createScaledBitmap(copy, size, size, true);
                onChanged.run();
            } catch (Throwable ignore) {
            }
        });
        r.onAttachedToWindow();
        r.setImage(full, full != null ? "320_320" : null, small, "90_90", null, 0, null, d, 1);
    }

    /** While the profile is open: the card follows the player. */
    public void attach() {
        if (attached) {
            return;
        }
        attached = true;
        if (receiver == null && art == null) {
            loadCover();
        }
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidSeek);
    }

    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidSeek);
        if (receiver != null) {
            receiver.onDetachedFromWindow();
            receiver = null;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (document != null) {
            onChanged.run();
        }
    }
}
