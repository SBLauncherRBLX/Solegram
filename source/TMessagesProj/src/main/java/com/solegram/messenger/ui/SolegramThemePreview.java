/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramThemeShare;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Solegram: a theme sent in a chat is tried on for real. Its colours, icons, avatar shape, glass and the rest are put on
 * (with the circle of new colours), and the user's own Saved Messages open in it: Telegram's real chat with the user's
 * wallpaper. A card over it keeps the theme («Установить») or gives the old look back («Отмена», or just leaving the
 * chat). What the look was is kept on disk, so a preview the app never ended is undone at the next start.
 */
public final class SolegramThemePreview {

    private SolegramThemePreview() {
    }

    public static final String ARG = "sg_theme_preview";

    @Nullable
    private static SolegramThemeShare.Theme theme;
    private static boolean installed;
    @Nullable
    private static View card;
    private static int oldAccent;

    /** Puts the theme on for a while and opens the chat to look at it. */
    public static void start(BaseFragment from, SolegramThemeShare.Theme t) {
        final Activity activity = from.getParentActivity();
        if (activity == null || theme != null) {
            return;
        }
        theme = t;
        installed = false;
        oldAccent = Theme.getColor(Theme.key_featuredStickers_addButton);
        SolegramThemeShare.savePreviewSnapshot();
        final INavigationLayout layout = from.getParentLayout();
        final View decor = activity.getWindow().getDecorView();
        final boolean dark = Theme.isCurrentThemeDark();
        SolegramThemeReveal.start(activity, decor.getWidth() / 2f, decor.getHeight() * 0.62f, t.accent(dark), () -> {
            SolegramThemeShare.apply(t);
            SolegramThemeShare.refresh(layout);
        });
        AndroidUtilities.runOnUIThread(() -> {
            final Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(from.getCurrentAccount()).getClientUserId());
            args.putBoolean(ARG, true);
            from.presentFragment(new ChatActivity(args));
        }, 120);
    }

    /** The chat of the preview was made: the card over it. */
    public static void attach(ChatActivity chat) {
        final Activity activity = chat.getParentActivity();
        if (theme == null || activity == null) {
            return;
        }
        detachCard();
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Theme.getColor(Theme.key_dialogBackground));
        box.setBackground(bg);
        box.setElevation(dp(8));
        box.setClickable(true);

        final TextView title = new TextView(activity);
        title.setText(LocaleController.formatString(R.string.SG_ThemePreviewTitle, theme.name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView info = new TextView(activity);
        info.setText(getString(R.string.SG_ThemePreviewInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 10));

        final LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final ButtonWithCounterView cancel = new ButtonWithCounterView(activity, false, null);
        cancel.setText(getString(R.string.Cancel), false);
        final ButtonWithCounterView install = new ButtonWithCounterView(activity, null);
        install.setText(getString(R.string.SG_ThemeShareInstall), false);
        buttons.addView(cancel, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 5, 0));
        buttons.addView(install, LayoutHelper.createLinear(0, 44, 1f, 5, 0, 0, 0));
        box.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        cancel.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            chat.finishFragment(); // leaving the chat undoes the theme (see closed)
        });
        install.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            installed = true;
            SolegramThemeShare.dropPreviewSnapshot();
            detachCard();
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_ThemeShareInstalled),
                getString(R.string.SG_ThemeShareRestartHint)).show();
        });

        int bottomInset = 0;
        final WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) {
            bottomInset = insets.getSystemWindowInsetBottom();
        }
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.leftMargin = lp.rightMargin = dp(12);
        // above the chat's input
        lp.bottomMargin = bottomInset + dp(72);
        decor.addView(box, lp);
        card = box;
        box.setAlpha(0f);
        box.setTranslationY(dp(40));
        box.animate().alpha(1f).translationY(0).setStartDelay(450).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    /** Another screen over the chat: the card waits (it comes back with the chat). */
    public static void paused() {
        detachCard();
    }

    private static void detachCard() {
        final View c = card;
        card = null;
        if (c != null && c.getParent() instanceof ViewGroup) {
            ((ViewGroup) c.getParent()).removeView(c);
        }
    }

    /** The chat of the preview is gone: without «Установить» the old look comes back (with the circle again). */
    public static void closed(ChatActivity chat) {
        if (theme == null) {
            return;
        }
        detachCard();
        final boolean keep = installed;
        theme = null;
        installed = false;
        if (keep) {
            return;
        }
        final Activity activity = chat.getParentActivity();
        final INavigationLayout layout = chat.getParentLayout();
        final Runnable back = () -> {
            SolegramThemeShare.restorePreviewSnapshot();
            SolegramThemeShare.refresh(layout);
        };
        if (activity != null && activity.getWindow() != null) {
            final View decor = activity.getWindow().getDecorView();
            AndroidUtilities.runOnUIThread(() -> SolegramThemeReveal.start(activity, decor.getWidth() / 2f, decor.getHeight() * 0.62f, oldAccent, back));
        } else {
            back.run();
        }
    }

    /** At the start: a preview the app did not end (it was closed meanwhile) is undone. */
    public static void undoUnfinished() {
        if (SolegramThemeShare.hasPreviewSnapshot()) {
            SolegramThemeShare.restorePreviewSnapshot();
        }
    }
}
