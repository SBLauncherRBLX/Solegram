/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Premium stickers without Premium, for Solegram users. Telegram does not let an account without Premium send a
 * Premium sticker, so Solegram sends an ordinary message instead: the sticker's emoji with an invisible mark at its
 * end (an invisible separator and variation selectors, four bits each) holding the sticker's pack and id. People in
 * other apps see the emoji; Solegram reads the mark, loads the pack if it has to, and shows the Premium sticker
 * itself in the message's place.
 */
public final class SolegramPremiumStickers {

    private static final char START = '⁢';
    private static final int VERSION = 1;
    private static final int LENGTH = 1 + 1 + 16 * 3;

    /** Packs being loaded: pack id -> messages waiting for it. */
    private static final HashMap<Long, ArrayList<Object[]>> waiting = new HashMap<>();
    private static final HashSet<Long> failed = new HashSet<>();

    private SolegramPremiumStickers() {
    }

    /** On by default: Premium stickers can be picked and sent, Solegram users see them. */
    public static boolean enabled() {
        return SolegramConfig.prefs().getBoolean("premiumStickersShare", true);
    }

    /** A Premium sticker this account cannot send itself. */
    public static boolean needsWorkaround(int account, TLRPC.Document document) {
        return enabled() && document != null && MessageObject.isPremiumSticker(document) && !UserConfig.getInstance(account).isPremium();
    }

    // region The mark

    private static void put(StringBuilder sb, long v) {
        for (int i = 15; i >= 0; i--) {
            sb.append((char) (0xFE00 + (int) ((v >>> (i * 4)) & 0xF)));
        }
    }

    @Nullable
    private static TLRPC.TL_inputStickerSetID setOf(TLRPC.Document document) {
        for (TLRPC.DocumentAttribute a : document.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeSticker && a.stickerset instanceof TLRPC.TL_inputStickerSetID) {
                return (TLRPC.TL_inputStickerSetID) a.stickerset;
            }
        }
        return null;
    }

    private static String emojiOf(TLRPC.Document document) {
        for (TLRPC.DocumentAttribute a : document.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeSticker && !TextUtils.isEmpty(a.alt)) {
                return a.alt;
            }
        }
        return "✨";
    }

    @Nullable
    private static long[] read(String text) {
        if (text == null) {
            return null;
        }
        final int at = text.lastIndexOf(START);
        if (at < 0 || text.length() - at < LENGTH) {
            return null;
        }
        final int[] n = new int[LENGTH - 1];
        for (int i = 0; i < n.length; i++) {
            final char c = text.charAt(at + 1 + i);
            if (c < 0xFE00 || c > 0xFE0F) {
                return null;
            }
            n[i] = c - 0xFE00;
        }
        if (n[0] != VERSION) {
            return null;
        }
        final long[] out = new long[3];
        for (int k = 0; k < 3; k++) {
            long v = 0;
            for (int i = 0; i < 16; i++) {
                v = (v << 4) | n[1 + k * 16 + i];
            }
            out[k] = v;
        }
        return out;
    }
    // endregion

    // region Sending

    /**
     * Sends a Premium sticker the way Solegram users see it (the emoji plus the mark). Returns false when the sticker
     * has no pack to point to, so the caller can fall back to Telegram's own handling.
     */
    public static boolean send(int account, TLRPC.Document document, long peer, MessageObject replyToMsg, MessageObject replyToTopMsg, boolean notify, int scheduleDate) {
        final TLRPC.TL_inputStickerSetID set = setOf(document);
        if (set == null) {
            return false;
        }
        final StringBuilder sb = new StringBuilder(emojiOf(document));
        sb.append(START);
        sb.append((char) (0xFE00 + VERSION));
        put(sb, set.id);
        put(sb, set.access_hash);
        put(sb, document.id);
        final SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(sb.toString(), peer);
        p.replyToMsg = replyToMsg;
        p.replyToTopMsg = replyToTopMsg;
        p.notify = notify;
        p.scheduleDate = scheduleDate;
        p.searchLinks = false;
        SendMessagesHelper.getInstance(account).sendMessage(p);
        MediaDataController.getInstance(account).addRecentSticker(MediaDataController.TYPE_IMAGE, null, document, (int) (System.currentTimeMillis() / 1000), false);
        return true;
    }
    // endregion

    // region Showing

    /**
     * Called for every message as it becomes a MessageObject: a message carrying the mark turns into the sticker,
     * when the pack is known; otherwise the pack is loaded and the message is shown again once it is.
     */
    public static void transform(int account, TLRPC.Message message) {
        if (message == null || TextUtils.isEmpty(message.message) || !enabled()) {
            return;
        }
        if (message.message.indexOf(START) < 0) {
            return;
        }
        if (message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty) && !(message.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return;
        }
        final long[] ref = read(message.message);
        if (ref == null) {
            return;
        }
        final TLRPC.Document document = find(account, ref[0], ref[2]);
        if (document == null) {
            load(account, ref[0], ref[1], message);
            return;
        }
        final TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        media.flags |= 1;
        message.media = media;
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        message.message = "";
        if (message.entities != null) {
            message.entities.clear();
        }
    }

    @Nullable
    private static TLRPC.Document find(int account, long setId, long documentId) {
        final TLRPC.TL_messages_stickerSet set = MediaDataController.getInstance(account).getStickerSetById(setId);
        if (set == null || set.documents == null) {
            return null;
        }
        for (TLRPC.Document d : set.documents) {
            if (d.id == documentId) {
                return d;
            }
        }
        return null;
    }

    private static void load(int account, long setId, long accessHash, TLRPC.Message message) {
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (waiting) {
                if (failed.contains(setId)) {
                    return;
                }
                ArrayList<Object[]> list = waiting.get(setId);
                final boolean first = list == null;
                if (first) {
                    list = new ArrayList<>();
                    waiting.put(setId, list);
                }
                list.add(new Object[]{account, message});
                if (!first) {
                    return;
                }
            }
            final TLRPC.TL_messages_getStickerSet req = new TLRPC.TL_messages_getStickerSet();
            final TLRPC.TL_inputStickerSetID input = new TLRPC.TL_inputStickerSetID();
            input.id = setId;
            input.access_hash = accessHash;
            req.stickerset = input;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<Object[]> list;
                synchronized (waiting) {
                    list = waiting.remove(setId);
                    if (!(response instanceof TLRPC.TL_messages_stickerSet)) {
                        failed.add(setId);
                        return;
                    }
                }
                MediaDataController.getInstance(account).putStickerSet((TLRPC.TL_messages_stickerSet) response);
                if (list == null) {
                    return;
                }
                // the messages are shown again, now as the sticker
                final HashMap<Long, ArrayList<MessageObject>> byDialog = new HashMap<>();
                for (Object[] w : list) {
                    final int acc = (Integer) w[0];
                    final TLRPC.Message m = (TLRPC.Message) w[1];
                    final MessageObject mo = new MessageObject(acc, m, true, true);
                    ArrayList<MessageObject> l = byDialog.get(mo.getDialogId());
                    if (l == null) {
                        l = new ArrayList<>();
                        byDialog.put(mo.getDialogId(), l);
                    }
                    l.add(mo);
                }
                for (java.util.Map.Entry<Long, ArrayList<MessageObject>> e : byDialog.entrySet()) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, e.getKey(), e.getValue());
                }
            }));
        });
    }
    // endregion

}
