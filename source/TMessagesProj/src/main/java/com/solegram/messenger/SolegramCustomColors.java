/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.util.SparseArray;

import org.telegram.messenger.MessagesController;

import java.util.HashMap;

/**
 * Own colours (chosen in Solegram) behind an ordinary colour id: Telegram resolves name and profile colours by id
 * (MessagesController.PeerColors.getColor), so an own colour gets an id of its own, far above Telegram's, and every
 * place that draws a name in its colour (the sender's name, replies, link previews) shows it as is.
 */
public final class SolegramCustomColors {

    /** Own colours' ids start here (Telegram's are small). */
    public static final int BASE = 0x40000000;

    private static final HashMap<Long, Integer> ids = new HashMap<>();
    private static final SparseArray<MessagesController.PeerColor> colors = new SparseArray<>();
    private static int next = BASE;

    private SolegramCustomColors() {
    }

    public static boolean isCustom(int colorId) {
        return colorId >= BASE;
    }

    /** The id of a name colour made of these colours (the name, and the reply line's second stripe). */
    public static synchronized int nameId(int c1, int c2) {
        return id(c1, c2, false);
    }

    /** The id of a profile colour made of these colours (the top and bottom of the gradient). */
    public static synchronized int profileId(int c1, int c2) {
        return id(c1, c2, true);
    }

    private static int id(int c1, int c2, boolean profile) {
        if (c1 == 0 || c2 == 0) {
            return -1;
        }
        final long key = ((long) (c1 & 0xFFFFFF) << 25) ^ ((long) (c2 & 0xFFFFFF) << 1) ^ (profile ? 1 : 0);
        final Integer have = ids.get(key);
        if (have != null) {
            return have;
        }
        final int id = next++;
        ids.put(key, id);
        colors.put(id, profile ? MessagesController.PeerColor.fromCustom(c1, c2) : MessagesController.PeerColor.fromCustomName(c1, c2));
        return id;
    }

    public static synchronized MessagesController.PeerColor get(int colorId) {
        return colors.get(colorId);
    }
}
