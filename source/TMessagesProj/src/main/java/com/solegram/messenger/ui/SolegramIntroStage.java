/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

/**
 * The pictures of the welcome screen, one for each page: Solegram's avatar first, then an animated sticker in
 * Telegram's style that tells what the page is about. They follow the finger: the one going away shrinks and fades
 * to its side, the next comes in from the other; the sticker of the page that settled plays.
 */
public class SolegramIntroStage extends FrameLayout {

    /** For each page after the first: what it says - glass, chats, music, plugins, protection. */
    private static final int[] STICKERS = {
        R.raw.wallet_diamond_blue,  // liquid glass: a crystal
        R.raw.utyan_empty,          // better in chats: the detective duck reading (summaries, search by meaning)
        R.raw.utyan_schedule,       // music and profile: ducks in headphones over a sound wave
        R.raw.utyan_draw,           // plugins: the duck making something of its own
        R.raw.utyan_passcode,       // protection: the duck at a safe with a passcode
    };
    private static final int SIZE_DP = 150;

    private final SolegramIntroView logo;
    private final RLottieImageView[] stickers = new RLottieImageView[STICKERS.length];
    private int playing = -1;
    private float page;

    public SolegramIntroStage(Context context) {
        super(context);
        setClipChildren(false);
        logo = new SolegramIntroView(context);
        addView(logo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        for (int i = 0; i < STICKERS.length; i++) {
            final RLottieImageView sticker = new RLottieImageView(context);
            sticker.setAnimation(STICKERS[i], SIZE_DP, SIZE_DP);
            sticker.setAutoRepeat(true);
            sticker.setVisibility(INVISIBLE);
            final int index = i + 1;
            // a tap plays it again, as a sticker does
            sticker.setOnClickListener(v -> replay(index));
            stickers[i] = sticker;
            addView(sticker, LayoutHelper.createFrame(SIZE_DP, SIZE_DP, Gravity.CENTER));
        }
        setPage(0);
    }

    /** The pager's position: the page and how far it is swiped towards the next one. */
    public void setPage(float page) {
        this.page = page;
        logo.setPage(page);
        place(logo, 0);
        for (int i = 0; i < stickers.length; i++) {
            place(stickers[i], i + 1);
        }
        final int settled = Math.round(page);
        if (Math.abs(page - settled) < 0.02f && settled != playing) {
            replay(settled);
        }
    }

    private void place(View view, int index) {
        final float d = index - page;
        final float away = Math.min(1f, Math.abs(d));
        final float alpha = Math.max(0f, 1f - away * 1.6f);
        view.setAlpha(alpha);
        view.setVisibility(alpha > 0.01f ? VISIBLE : INVISIBLE);
        view.setTranslationX(d * dp(110));
        final float scale = 0.78f + 0.22f * CubicBezierInterpolator.EASE_OUT.getInterpolation(1f - away);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    /** The sticker of this page from its start; the others stop (only one plays at a time). */
    private void replay(int index) {
        playing = index;
        for (int i = 0; i < stickers.length; i++) {
            final RLottieImageView sticker = stickers[i];
            if (i + 1 == index) {
                if (sticker.getAnimatedDrawable() != null) {
                    sticker.getAnimatedDrawable().setCurrentFrame(0, false);
                }
                sticker.playAnimation();
            } else {
                sticker.stopAnimation();
            }
        }
    }
}
