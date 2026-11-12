/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramAI;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Solegram AI as an assistant: a conversation about anything - questions, ideas, writing, translations, Solegram
 * itself. Kept for as long as the app runs, nothing of it is stored.
 */
public class SolegramAiChatActivity extends BaseFragment {

    /** {"user"|"ai", text}: the conversation, while the app runs. */
    private static final ArrayList<String[]> TURNS = new ArrayList<>();

    private static final int MENU_NEW = 1;

    private LinearLayout list;
    private ScrollView scroll;
    private LinearLayout empty;
    private EditTextBoldCursor field;
    private ImageView send;
    private View thinking;
    private boolean waiting;
    private int token;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.SG_AiTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_NEW) {
                    TURNS.clear();
                    token++;
                    waiting = false;
                    rebuild();
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_NEW, R.drawable.msg_clear).setContentDescription(getString(R.string.SG_AiNewChat));

        final int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final FrameLayout middle = new FrameLayout(context);
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(list);
        middle.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // the empty state: the mark, a question, a few ideas to start with
        empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.addView(new SolegramAiSheet.Spark(context), LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));
        final SolegramAiSheet.GradientText hello = new SolegramAiSheet.GradientText(context);
        hello.setText(getString(R.string.SG_AiChatHello));
        hello.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        hello.setTypeface(AndroidUtilities.bold());
        hello.setGravity(Gravity.CENTER);
        empty.addView(hello, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        final TextView about = new TextView(context);
        about.setText(getString(R.string.SG_AiChatAbout));
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        about.setTextColor(grayColor);
        about.setGravity(Gravity.CENTER);
        empty.addView(about, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 6, 32, 18));
        for (int s : new int[]{R.string.SG_AiIdea1, R.string.SG_AiIdea2, R.string.SG_AiIdea3, R.string.SG_AiIdea4}) {
            final TextView idea = new TextView(context);
            idea.setText(getString(s));
            idea.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            idea.setTextColor(textColor);
            idea.setPadding(dp(16), dp(11), dp(16), dp(11));
            idea.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), ColorUtils.setAlphaComponent(textColor, 0x0E), ColorUtils.setAlphaComponent(textColor, 0x1C)));
            idea.setOnClickListener(v -> sendText(getString(s)));
            empty.addView(idea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        }
        middle.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        column.addView(middle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // the input: a rounded field and the send button in the Solegram AI colours
        final LinearLayout input = new LinearLayout(context);
        input.setOrientation(LinearLayout.HORIZONTAL);
        input.setGravity(Gravity.BOTTOM);
        input.setPadding(dp(10), dp(6), dp(10), dp(10));
        field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(textColor);
        field.setHintTextColor(grayColor);
        field.setHint(getString(R.string.SG_AiChatHint));
        field.setCursorColor(accent);
        field.setCursorSize(dp(20));
        field.setMaxLines(6);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(dp(16), dp(11), dp(16), dp(11));
        field.setBackground(Theme.createRoundRectDrawable(dp(22), ColorUtils.setAlphaComponent(textColor, 0x0E)));
        input.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        send = new ImageView(context);
        send.setImageResource(R.drawable.ic_send);
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        final GradientDrawable sendBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SolegramAiSheet.BRAND[0], SolegramAiSheet.BRAND[1]});
        sendBg.setShape(GradientDrawable.OVAL);
        send.setBackground(sendBg);
        send.setOnClickListener(v -> sendText(field.getText().toString()));
        input.addView(send, LayoutHelper.createLinear(44, 44, 0, 8, 0, 0));
        column.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = root;
        rebuild();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    private void rebuild() {
        list.removeAllViews();
        thinking = null;
        for (String[] t : TURNS) {
            list.addView(bubble(t[0].equals("user"), t[1], false));
        }
        empty.setVisibility(TURNS.isEmpty() ? View.VISIBLE : View.GONE);
        scrollToEnd();
    }

    private void sendText(String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty() || waiting) {
            return;
        }
        field.setText("");
        TURNS.add(new String[]{"user", text});
        empty.setVisibility(View.GONE);
        list.addView(bubble(true, text, true));
        thinking = new Dots(getParentActivity());
        list.addView(thinking, LayoutHelper.createLinear(64, 36, Gravity.LEFT, 4, 8, 0, 0));
        scrollToEnd();
        waiting = true;
        send.setAlpha(0.5f);
        final int t = ++token;
        SolegramAI.ask(new ArrayList<>(TURNS), (answer, error, source) -> {
            if (t != token || fragmentView == null) {
                return;
            }
            waiting = false;
            send.setAlpha(1f);
            if (thinking != null) {
                list.removeView(thinking);
                thinking = null;
            }
            if (answer == null) {
                TURNS.remove(TURNS.size() - 1);
                rebuild();
                field.setText(TURNS.isEmpty() ? "" : field.getText());
                if (error != null) {
                    BulletinFactory.of(this).createErrorBulletin(error).show();
                }
                return;
            }
            TURNS.add(new String[]{"ai", answer});
            list.addView(bubble(false, answer, true));
            scrollToEnd();
        });
    }

    private View bubble(boolean mine, String text, boolean animate) {
        final int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final TextView v = new TextView(getParentActivity());
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        v.setLineSpacing(dp(2), 1f);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setTextIsSelectable(!mine);
        if (mine) {
            final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SolegramAiSheet.BRAND[0], SolegramAiSheet.BRAND[1]});
            bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), dp(6), dp(6), dp(20), dp(20)});
            v.setBackground(bg);
            v.setTextColor(0xFFFFFFFF);
        } else {
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(ColorUtils.setAlphaComponent(textColor, 0x0E));
            bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), dp(20), dp(20), dp(6), dp(6)});
            v.setBackground(bg);
            v.setTextColor(textColor);
            v.setOnLongClickListener(view -> {
                if (AndroidUtilities.addToClipboard(text)) {
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
                }
                return true;
            });
        }
        final LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, mine ? Gravity.RIGHT : Gravity.LEFT,
            mine ? 48 : 0, 8, mine ? 0 : 48, 0);
        v.setLayoutParams(lp);
        if (animate) {
            v.setAlpha(0f);
            v.setTranslationY(dp(10));
            v.animate().alpha(1f).translationY(0).setDuration(220).start();
        }
        return v;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, list.getHeight()));
    }

    /** Three dots that breathe while Solegram AI thinks. */
    private class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        private float phase;

        Dots(Context context) {
            super(context);
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), 0x0E));
            bg.setCornerRadius(dp(18));
            setBackground(bg);
            animator.setDuration(1100);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            animator.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int i = 0; i < 3; i++) {
                final float p = (float) (0.5 + 0.5 * Math.sin((phase - i * 0.18f) * Math.PI * 2));
                paint.setColor(ColorUtils.blendARGB(SolegramAiSheet.BRAND[i], 0x00FFFFFF, 0.6f - 0.6f * p));
                paint.setAlpha((int) (110 + 145 * p));
                canvas.drawCircle(getWidth() / 2f + (i - 1) * dp(12), getHeight() / 2f - dp(2) * p, dp(4), paint);
            }
        }
    }
}
