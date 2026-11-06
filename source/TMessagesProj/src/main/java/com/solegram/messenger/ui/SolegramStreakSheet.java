/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solegram.messenger.SolegramStreak;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** The streak menu in a chat: the sun, how long the streak is, its level, and how it looks next to the name. */
public class SolegramStreakSheet extends BottomSheet {

    private final int account;
    private final long dialogId;
    private final int days;
    private final Theme.ResourcesProvider rp;
    private final SunView sun;
    private final TextView count;
    private final LinearLayout colorRow;
    private final LinearLayout styleRow;
    private int color;
    private int style;

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (SolegramStreak.days(account, dialogId) <= 0) {
            return;
        }
        fragment.showDialog(new SolegramStreakSheet(fragment.getParentActivity(), account, dialogId, fragment.getResourceProvider()));
    }

    private SolegramStreakSheet(Context context, int account, long dialogId, Theme.ResourcesProvider rp) {
        super(context, false, rp);
        this.account = account;
        this.dialogId = dialogId;
        this.rp = rp;
        this.days = SolegramStreak.days(account, dialogId);
        this.color = SolegramStreak.colorOf(account, dialogId);
        this.style = SolegramStreak.styleOf(account, dialogId);
        fixNavigationBar();
        SolegramMotion.springSheet(this);

        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(12), 0, dp(16));

        final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        final TextView with = new TextView(context);
        with.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        with.setTextColor(gray);
        with.setGravity(Gravity.CENTER);
        with.setText(formatString(R.string.SG_StreakWith, user == null ? "" : UserObject.getFirstName(user)));
        column.addView(with, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sun = new SunView(context);
        column.addView(sun, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160, 0, 8, 0, 0));

        count = new TextView(context);
        count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        count.setTypeface(AndroidUtilities.bold());
        count.setTextColor(SolegramStreak.textColor(color, days));
        count.setGravity(Gravity.CENTER);
        count.setText(formatString(R.string.SG_StreakDaysInRow, formatPluralString("Days", days)));
        column.addView(count, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));

        final int level = SolegramStreak.level(days);
        final int next = SolegramStreak.toNextLevel(days);
        final TextView levelView = new TextView(context);
        levelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        levelView.setTextColor(gray);
        levelView.setGravity(Gravity.CENTER);
        final String levelName = getString(SolegramStreak.LEVEL_NAMES[level]);
        levelView.setText(next > 0
            ? formatString(R.string.SG_StreakLevelNext, levelName, getString(SolegramStreak.LEVEL_NAMES[level + 1]), formatPluralString("Days", next))
            : formatString(R.string.SG_StreakLevelTop, levelName));
        column.addView(levelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        final boolean done = SolegramStreak.doneToday(account, dialogId);
        final TextView today = new TextView(context);
        today.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        today.setTextColor(done ? 0xFF35B262 : 0xFFF08A24);
        today.setGravity(Gravity.CENTER);
        today.setText(getString(done ? R.string.SG_StreakTodayDone : R.string.SG_StreakTodayPending));
        column.addView(today, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 4));

        column.addView(label(context, R.string.SG_StreakColor, gray));
        colorRow = row(context, column);
        column.addView(label(context, R.string.SG_StreakStyle, gray));
        styleRow = row(context, column);
        fillChoices();

        final TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(gray);
        note.setText(formatString(R.string.SG_StreakNote, SolegramStreak.MIN_DAYS));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 0));

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        setCustomView(scroll);
    }

    private TextView label(Context context, int res, int color) {
        final TextView v = new TextView(context);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.setTypeface(AndroidUtilities.bold());
        v.setTextColor(color);
        v.setText(getString(res));
        v.setPadding(dp(22), dp(16), dp(22), dp(8));
        return v;
    }

    private LinearLayout row(Context context, LinearLayout column) {
        final HorizontalScrollView h = new HorizontalScrollView(context);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(dp(16), 0, dp(16), 0);
        final LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(r);
        column.addView(h, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return r;
    }

    private void fillChoices() {
        final Context context = getContext();
        colorRow.removeAllViews();
        for (int i = 0; i < SolegramStreak.COLOR_NAMES.length; i++) {
            final int index = i;
            final Swatch sw = new Swatch(context, SolegramStreak.sunColors(i, days), color == i);
            sw.setContentDescription(getString(SolegramStreak.COLOR_NAMES[i]));
            sw.setOnClickListener(v -> {
                color = index;
                changed();
            });
            colorRow.addView(sw, LayoutHelper.createLinear(44, 44, 3, 0, 3, 0));
        }
        styleRow.removeAllViews();
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        for (int i = 0; i < SolegramStreak.STYLE_NAMES.length; i++) {
            final int index = i;
            final TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setText(getString(SolegramStreak.STYLE_NAMES[i]));
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            if (i == style) {
                bg.setColor(accent);
                chip.setTextColor(0xFFFFFFFF);
                chip.setTypeface(AndroidUtilities.bold());
            } else {
                bg.setColor(Theme.multAlpha(text, 0.07f));
                chip.setTextColor(text);
            }
            chip.setBackground(bg);
            chip.setOnClickListener(v -> {
                style = index;
                changed();
            });
            styleRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 3, 0, 3, 0));
        }
    }

    private void changed() {
        SolegramStreak.setLook(account, dialogId, color, style);
        count.setTextColor(SolegramStreak.textColor(color, days));
        sun.spin();
        fillChoices();
    }

    /** The streak's mark, large: it breathes, and pops when tapped (it does not turn). */
    private class SunView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final long born = SystemClock.elapsedRealtime();
        private float spin;
        private ValueAnimator spinAnimator;

        SunView(Context context) {
            super(context);
            setOnClickListener(v -> spin());
        }

        void spin() {
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
            if (spinAnimator != null) {
                spinAnimator.cancel();
            }
            spinAnimator = ValueAnimator.ofFloat(0, 1);
            spinAnimator.setDuration(420);
            spinAnimator.addUpdateListener(a -> {
                // a short pop (the mark is not a round sun any more: it does not turn)
                spin = (float) Math.sin(Math.PI * (float) a.getAnimatedValue());
                invalidate();
            });
            spinAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float t = (SystemClock.elapsedRealtime() - born) / 1000f;
            final int size = Math.min(getWidth(), getHeight()) - dp(14);
            if (size <= 0) {
                return;
            }
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float breath = (1f + 0.025f * (float) Math.sin(t * 1.9f)) * (1f + 0.12f * spin);
            canvas.save();
            canvas.scale(breath, breath, cx, cy);
            canvas.drawBitmap(SolegramStreak.sun(color, days, size), cx - size / 2f, cy - size / 2f, paint);
            canvas.restore();
            invalidate();
        }
    }

    /** A round two-tone colour choice. */
    private class Swatch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        private final boolean selected;

        Swatch(Context context, int[] colors, boolean selected) {
            super(context);
            this.colors = colors;
            this.selected = selected;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float r = Math.min(cx, cy) - dp(5);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, rp));
                canvas.drawCircle(cx, cy, r + dp(3.5f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(colors[0]);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(colors[colors.length - 1]);
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -45, 180, true, paint);
        }
    }
}
