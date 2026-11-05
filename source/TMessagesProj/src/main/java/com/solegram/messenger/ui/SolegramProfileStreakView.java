/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramProfileCard;
import com.solegram.messenger.SolegramStreak;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** A Solegram Streak pinned to a profile: the sun in its colour, how many days, and with whom (when shown). */
public class SolegramProfileStreakView extends LinearLayout {

    private final Theme.ResourcesProvider rp;
    private final SunView sun;
    private final TextView title, subtitle;
    private int days, color;

    public SolegramProfileStreakView(Context context, Theme.ResourcesProvider rp) {
        super(context);
        this.rp = rp;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(21), dp(10), dp(21), dp(10));
        sun = new SunView(context);
        addView(sun, LayoutHelper.createLinear(40, 40, 0, 0, 16, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        subtitle.setSingleLine();
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
    }

    public void set(int account, SolegramProfileCard.Card card) {
        if (card == null) {
            return;
        }
        days = card.streakDays;
        color = card.streakColor;
        title.setTextColor(SolegramStreak.textColor(color, days));
        title.setText(LocaleController.formatString(R.string.SG_StreakDaysInRow, LocaleController.formatPluralString("Days", days)));
        final String level = LocaleController.getString(SolegramStreak.LEVEL_NAMES[SolegramStreak.level(days)]);
        final TLRPC.User partner = card.streakPartner != 0 ? MessagesController.getInstance(account).getUser(card.streakPartner) : null;
        subtitle.setText(partner != null
            ? LocaleController.formatString(R.string.SG_ProfileStreakWith, UserObject.getFirstName(partner), level)
            : LocaleController.formatString(R.string.SG_ProfileStreakLevel, level));
        sun.invalidate();
    }

    private class SunView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        SunView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int size = Math.min(getWidth(), getHeight());
            if (size <= 0 || days <= 0) {
                return;
            }
            final float t = SystemClock.elapsedRealtime() / 1000f;
            canvas.save();
            canvas.rotate(t * 6f % 360f, getWidth() / 2f, getHeight() / 2f);
            canvas.drawBitmap(SolegramStreak.sun(color, days, size), (getWidth() - size) / 2f, (getHeight() - size) / 2f, paint);
            canvas.restore();
            if (isShown()) {
                postInvalidateOnAnimation();
            }
        }
    }
}
