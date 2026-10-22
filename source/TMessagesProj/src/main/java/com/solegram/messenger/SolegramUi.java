/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.text.TextUtils;

import com.solegram.messenger.plugins.PluginMenuItem;
import com.solegram.messenger.plugins.PluginsController;
import com.solegram.messenger.ui.SolegramSettingsActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.time.FastDateFormat;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small UI helpers used by the patched Telegram screens. */
public final class SolegramUi {

    private SolegramUi() {
    }

    // region Text

    public static String formatOwnPhone(String formatted) {
        if (!SolegramConfig.hidePhoneNumber || formatted == null) {
            return formatted;
        }
        StringBuilder sb = new StringBuilder(formatted.length());
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            sb.append(Character.isDigit(c) ? '•' : c);
        }
        return sb.toString();
    }

    public static FastDateFormat timeFormatter() {
        return SolegramConfig.showSeconds ? LocaleController.getInstance().getFormatterDayWithSeconds() : LocaleController.getInstance().getFormatterDay();
    }

    /** Adds the "deleted" mark and the message id to a message time label. */
    public static String decorateTime(int account, MessageObject messageObject, String time) {
        if (messageObject == null || time == null) {
            return time;
        }
        String result = time;
        if (SolegramConfig.showMessageId && messageObject.getId() > 0 && !messageObject.scheduled) {
            result = "#" + messageObject.getId() + " " + result;
        }
        if (SolegramConfig.saveDeletedMessages && MessageVault.isDeleted(account, messageObject)) {
            result = LocaleController.getString(R.string.SG_DeletedMark) + " " + result;
        }
        return result;
    }
    // endregion

    // region Avatars

    /**
     * Avatar corner radius for a view of the given size. Telegram draws avatars as circles
     * (radius = size / 2); Solegram scales that by the "avatar corners" option.
     */
    public static int avatarRadius(int sizePx) {
        int full = sizePx / 2;
        if (SolegramConfig.avatarCorners >= 100) {
            return full;
        }
        return Math.round(full * SolegramConfig.avatarCorners / 100f);
    }

    public static boolean isCircleAvatars() {
        return SolegramConfig.avatarCorners >= 100;
    }

    // region Animations

    private static final java.util.WeakHashMap<android.view.View, android.animation.ValueAnimator> pressAnimators = new java.util.WeakHashMap<>();

    /** Springy press feedback for list rows: shrink a little while held, bounce back on release. */
    public static void animatePress(android.view.View view, boolean pressed) {
        if (!SolegramConfig.pressScale || view == null) {
            return;
        }
        if (view instanceof org.telegram.ui.Cells.ChatMessageCell || view instanceof org.telegram.ui.Cells.ChatActionCell
                || view.getHeight() <= 0 || view.getHeight() > AndroidUtilities.dp(140)) {
            return;
        }
        android.animation.ValueAnimator running = pressAnimators.remove(view);
        if (running != null) {
            running.cancel();
        }
        final float target = pressed ? 0.97f : 1f;
        if (Math.abs(view.getScaleX() - target) < 0.001f) {
            return;
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(view.getScaleX(), target);
        // the map holds the animator, so the animator must not hold the view strongly (or the view never leaves the map)
        final java.lang.ref.WeakReference<android.view.View> viewRef = new java.lang.ref.WeakReference<>(view);
        animator.addUpdateListener(a -> {
            final android.view.View v = viewRef.get();
            if (v != null) {
                final float s = (float) a.getAnimatedValue();
                v.setScaleX(s);
                v.setScaleY(s);
            }
        });
        if (pressed) {
            animator.setDuration(110);
            animator.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT);
        } else {
            animator.setDuration(420);
            animator.setInterpolator(new android.view.animation.OvershootInterpolator(2.4f));
        }
        if (!pressed) {
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    final android.view.View v = viewRef.get();
                    if (v != null && pressAnimators.get(v) == animation) {
                        pressAnimators.remove(v);
                    }
                }
            });
        }
        pressAnimators.put(view, animator);
        animator.start();
    }

    /** Recycled rows must never keep a half-finished press scale (only rows Solegram scaled are touched). */
    public static void resetPress(android.view.View view) {
        if (view == null) {
            return;
        }
        android.animation.ValueAnimator running = pressAnimators.remove(view);
        if (running != null) {
            running.cancel();
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    /** Rows of settings lists fade and rise in one after another when a screen opens. */
    public static android.view.animation.LayoutAnimationController listAppearAnimation() {
        android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
        set.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
        set.addAnimation(new android.view.animation.TranslateAnimation(0, 0, AndroidUtilities.dp(14), 0));
        set.setDuration(300);
        set.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
        android.view.animation.LayoutAnimationController controller = new android.view.animation.LayoutAnimationController(set, 0.06f);
        controller.setOrder(android.view.animation.LayoutAnimationController.ORDER_NORMAL);
        return controller;
    }

    /** Android 12+ can blur what lies behind a window (menus, dialogs, sheets) unless the system turned it off. */
    public static boolean canBlurWindows() {
        return android.os.Build.VERSION.SDK_INT >= 31 && org.telegram.ui.LaunchActivity.systemBlurEnabled;
    }

    /** Adds a frosted backdrop behind a window: everything under it is blurred. */
    public static void blurBehind(android.view.WindowManager.LayoutParams params) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && params != null) {
            params.flags |= android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(blurBehindRadius());
        }
    }

    /** Full strength of the frosted backdrop behind sheets, px. */
    public static int blurBehindRadius() {
        return AndroidUtilities.dp(Math.max(4, Math.round(18 * SolegramGlass.blurScale())));
    }

    /** Overscroll glow: Telegram tints it with the header colour, which flashes the accent over panels. */
    public static int overscrollGlow(int telegramColor) {
        return SolegramConfig.neutralOverscroll ? 0x33888888 : telegramColor;
    }
    // endregion

    /** Avatar outside chats: dialogs list, profiles, menus. Follows the "avatar corners" option. */
    public static final int SHAPE_DEFAULT = 0;
    /** Avatar inside a chat (messages, service messages, chat header): round unless "shape in chats" is on. */
    public static final int SHAPE_CHAT = 1;
    /** Avatar drawn inside a circular frame (stacked avatars, map pins): always round. */
    public static final int SHAPE_CIRCLE = 2;

    /** Roundness in percent (100 = circle) for an avatar drawn in the given context. */
    public static int avatarPercent(int shape) {
        if (shape == SHAPE_CIRCLE || shape == SHAPE_CHAT && !SolegramConfig.avatarShapeInChats) {
            return 100;
        }
        return SolegramConfig.avatarCorners;
    }

    public static final int TITLE_APP = 0;
    public static final int TITLE_ACCOUNT = 1;
    public static final int TITLE_CUSTOM = 2;
    public static final int TITLE_NONE = 3;

    /** Title of the chats screen: Solegram, the account's first name, custom text or nothing. */
    public static String mainScreenTitle(int account) {
        switch (SolegramConfig.mainTitle) {
            case TITLE_ACCOUNT: {
                org.telegram.tgnet.TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
                String name = user != null ? org.telegram.messenger.UserObject.getFirstName(user) : null;
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
                break;
            }
            case TITLE_CUSTOM:
                if (!TextUtils.isEmpty(SolegramConfig.mainTitleText)) {
                    return SolegramConfig.mainTitleText;
                }
                break;
            case TITLE_NONE:
                return "";
        }
        return Solegram.NAME;
    }
    // endregion

    // region Menus

    /** Solegram entries of the main "⋮" menu on the chats screen. */
    public static void addMainMenuItems(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) {
            return;
        }
        io.addGap();
        if (SolegramConfig.ghostInDrawer) {
            io.addChecked(SolegramConfig.isGhostModeActive(), R.drawable.msg_secret, LocaleController.getString(R.string.SG_GhostMode), () -> {
                boolean enable = !SolegramConfig.isGhostModeActive();
                SolegramConfig.setGhostMode(enable);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(enable ? R.string.SG_GhostEnabled : R.string.SG_GhostDisabled)).show();
            });
        }
        // Saved Messages: Telegram 13 has its own item in this menu (DialogsActivity), which savedInMenu now shows or hides
        if (SolegramConfig.feedInsteadOfContacts) {
            // the Contacts tab gave way to the Feed: keep contacts one tap away
            io.add(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), () -> fragment.presentFragment(new org.telegram.ui.ContactsActivity(null)));
        }
        if (SolegramConfig.markAllReadInMenu) {
            io.add(R.drawable.msg_markread, LocaleController.getString(R.string.SG_MarkAllRead), () -> markAllRead(fragment));
        }
        io.add(R.drawable.sg_settings_mark, LocaleController.getString(R.string.SG_SettingsTitle), () -> fragment.presentFragment(new SolegramSettingsActivity()));
        addPluginItems(io, fragment, PluginMenuItem.DRAWER_MENU, baseContext(fragment));
        addPluginItems(io, fragment, PluginMenuItem.MAIN_MENU, baseContext(fragment));
    }

    /** Marks every chat in every folder (archive included) as read, after a confirmation. */
    public static void markAllRead(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SG_MarkAllRead));
        builder.setMessage(LocaleController.getString(R.string.SG_MarkAllReadConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.SG_MarkAllReadAction), (d, w) -> {
            fragment.getMessagesStorage().readAllDialogs(-1);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.SG_MarkAllReadDone)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    public static Map<String, Object> baseContext(BaseFragment fragment) {
        HashMap<String, Object> ctx = new HashMap<>();
        ctx.put("account", fragment != null ? fragment.getCurrentAccount() : UserConfig.selectedAccount);
        ctx.put("fragment", fragment);
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        ctx.put("context", activity);
        return ctx;
    }

    /** Adds plugin-provided items of a menu type to an ItemOptions popup. */
    public static void addPluginItems(ItemOptions io, BaseFragment fragment, String menuType, Map<String, Object> context) {
        if (!PluginsController.isEngineAvailable()) {
            return;
        }
        List<PluginMenuItem> items = PluginsController.getInstance().getMenuItems(menuType, context);
        for (PluginMenuItem item : items) {
            CharSequence text = item.text;
            if (!TextUtils.isEmpty(item.subtext)) {
                io.add(text, item.subtext, () -> PluginsController.getInstance().onMenuItemClick(item, context));
            } else {
                int icon = item.getIconResId();
                io.add(icon != 0 ? icon : R.drawable.msg_bots, text, () -> PluginsController.getInstance().onMenuItemClick(item, context));
            }
        }
    }
    // endregion

    public static void copyId(BaseFragment fragment, long id) {
        AndroidUtilities.addToClipboard(String.valueOf(id));
        if (fragment != null) {
            BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.SG_IdCopied)).show();
        }
    }
}
