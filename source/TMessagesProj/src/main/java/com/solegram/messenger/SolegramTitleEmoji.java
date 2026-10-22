/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.view.Gravity;
import android.view.View;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.SelectAnimatedEmojiDialog;

/**
 * An emoji next to the title of the chats screen, like Premium's emoji status but kept on this
 * device only: it decorates Solegram, nobody else sees it, and it works without Premium.
 */
public final class SolegramTitleEmoji {

    private SolegramTitleEmoji() {
    }

    /** Custom emoji document id, 0 = none. */
    public static long get() {
        return SolegramConfig.titleEmoji;
    }

    public static void set(long documentId) {
        if (documentId == 0) {
            SolegramConfig.prefs().edit().remove("titleEmoji").apply();
            SolegramConfig.reload();
        } else {
            SolegramConfig.putLong("titleEmoji", documentId);
        }
    }

    /** The emoji next to the title is ours (not the account's emoji status from the server). */
    public static boolean usesLocal(int account) {
        return get() != 0 || !UserConfig.getInstance(account).isPremium();
    }

    /**
     * Opens Telegram's emoji status picker under {@code anchor}; the choice is saved locally.
     * Tapping the selected emoji again removes it.
     */
    /**
     * @param emojiX where the emoji being replaced sits, for the picker's pointer and pick animation; null = no pointer
     */
    public static SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow showPicker(BaseFragment fragment, View anchor, int xoff, int yoff, Integer emojiX,
                                                                                      AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable scrimDrawable, SimpleTextView scrimView, Runnable onPicked) {
        if (fragment == null || fragment.getParentActivity() == null || anchor == null) {
            return null;
        }
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        final SelectAnimatedEmojiDialog layout = new SelectAnimatedEmojiDialog(fragment, fragment.getParentActivity(), true, emojiX,
                SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS, fragment.getResourceProvider()) {
            @Override
            protected boolean willApplyEmoji(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                return gift == null; // collectible gifts are worn through the profile, not here
            }

            @Override
            protected void onEmojiSelected(View emojiView, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (gift == null) {
                    set(documentId == null ? 0 : documentId);
                    if (onPicked != null) {
                        onPicked.run();
                    }
                }
                if (popup[0] != null) {
                    popup[0].dismiss();
                }
            }
        };
        layout.setSelected(get() != 0 ? get() : null);
        layout.setSaveState(1);
        if (scrimDrawable != null && scrimView != null) {
            layout.setScrimDrawable(scrimDrawable, scrimView);
        }
        popup[0] = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        popup[0].showAsDropDown(anchor, xoff, yoff, Gravity.TOP);
        popup[0].dimBehind();
        return popup[0];
    }
}
