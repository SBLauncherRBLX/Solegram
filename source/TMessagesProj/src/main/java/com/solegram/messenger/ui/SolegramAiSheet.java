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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramAI;
import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The sheet of Solegram AI: for a message (reply ideas, what it means, its gist), for a chat (what one missed, and
 * questions about the chat) and for one's own text before sending (a better wording, a translation).
 */
public class SolegramAiSheet extends BottomSheet {

    /**
     * The Solegram AI colours, from the theme: its accent and its neighbours on the colour wheel (a cooler one before
     * it, two warmer ones after), kept bright enough to glow.
     */
    public static int[] brand() {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton) | 0xFF000000;
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        final float h = hsl[0], s = Math.max(0.55f, Math.min(0.92f, hsl[1])), l = Math.max(0.5f, Math.min(0.64f, hsl[2]));
        return new int[]{hsl(h - 28, s, l), accent, hsl(h + 34, s * 0.95f, l + 0.02f), hsl(h + 68, s * 0.9f, l + 0.04f)};
    }

    /** The theme's accent itself (icons, the send button). */
    public static int accent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton) | 0xFF000000;
    }

    private static int hsl(float h, float s, float l) {
        return ColorUtils.HSLToColor(new float[]{(h % 360 + 360) % 360, Math.max(0, Math.min(1, s)), Math.max(0, Math.min(1, l))});
    }

    private static final int TASK_REPLIES = 0, TASK_EXPLAIN = 1, TASK_GIST = 2, TASK_CATCH_UP = 3, TASK_ASK = 4, TASK_WRITE = 5;

    private final ChatActivity chat;
    @Nullable
    private final MessageObject message;
    @Nullable
    private final String ownText;

    private final LinearLayout chips;
    private final ArrayList<TextView> chipViews = new ArrayList<>();
    private final FrameLayout resultFrame;
    private final TextView resultText;
    private final LinearLayout repliesBox;
    private final Shimmer shimmer;
    private final TextView statusText;
    private final TextView again;
    private final ImageView copy;
    @Nullable
    private EditTextBoldCursor askField;
    @Nullable
    private SolegramProfileLinksSheet.ActionButton replaceButton;

    private int task;
    private int writeMode = SolegramAI.WRITE_FIX;
    private int token;
    /** Shown: the first request goes once the sheet is up (the consent question then opens above it). */
    private boolean started;
    private String lastAnswer;
    private String lastQuestion;

    /** Solegram AI for a message: replies to it, what it means, its gist. */
    public static void showForMessage(ChatActivity chat, MessageObject message) {
        if (chat == null || chat.getParentActivity() == null || !SolegramAI.canUseMessage(chat, message)) {
            return;
        }
        new SolegramAiSheet(chat, message, null, -1).show();
    }

    /** What one missed in a chat (and questions about it). */
    public static void showCatchUp(ChatActivity chat) {
        if (chat == null || chat.getParentActivity() == null) {
            return;
        }
        if (!SolegramAI.allowedIn(chat)) {
            BulletinFactory.of(chat).createErrorBulletin(getString(R.string.SG_AiNotHere)).show();
            return;
        }
        new SolegramAiSheet(chat, null, null, TASK_CATCH_UP).show();
    }

    /** A better wording of the text in the chat's input field. */
    public static void showWriter(ChatActivity chat, String text) {
        if (chat == null || chat.getParentActivity() == null || TextUtils.isEmpty(text)) {
            return;
        }
        new SolegramAiSheet(chat, null, text, TASK_WRITE).show();
    }

    private SolegramAiSheet(ChatActivity chat, @Nullable MessageObject message, @Nullable String ownText, int forced) {
        super(chat.getParentActivity(), true, chat.getResourceProvider());
        this.chat = chat;
        this.message = message;
        this.ownText = ownText;
        final Context context = chat.getParentActivity();
        fixNavigationBar();
        SolegramMotion.springSheet(this);
        setApplyBottomPadding(false);

        final int textColor = getThemedColor(Theme.key_dialogTextBlack);
        final int grayColor = getThemedColor(Theme.key_dialogTextGray3);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        final ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.6f), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(20), 0, dp(6));
        scroll.addView(content);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // header: the Solegram AI mark, its name (plain, in the sheet's text colour), what it does here
        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new Spark(context), LayoutHelper.createLinear(40, 40));
        final LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        final TextView name = text(context, getString(R.string.SG_AiTitle), 20, textColor);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        name.setTypeface(AndroidUtilities.bold());
        titles.addView(name);
        final TextView sub = text(context, subtitle(forced), 14, grayColor);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        head.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        content.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

        // the tasks to pick from
        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(16), 0, dp(16), 0);
        chipsScroll.addView(chips);
        content.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 16, 0, 0));

        // the answer: a shimmer while it comes, the text or the reply cards, who answered, "again" and "copy"
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(18), ColorUtils.setAlphaComponent(textColor, 0x0E)));
        card.setPadding(dp(16), dp(12), dp(16), dp(2));
        resultFrame = new FrameLayout(context);
        resultText = text(context, "", 16, textColor);
        resultText.setLineSpacing(dp(2), 1f);
        resultText.setTextIsSelectable(true);
        resultFrame.addView(resultText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        repliesBox = new LinearLayout(context);
        repliesBox.setOrientation(LinearLayout.VERTICAL);
        resultFrame.addView(repliesBox, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        shimmer = new Shimmer(context, textColor);
        resultFrame.addView(shimmer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 92));
        card.addView(resultFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout status = new LinearLayout(context);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusText = text(context, "", 13, grayColor);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        status.addView(statusText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        copy = new ImageView(context);
        copy.setImageResource(R.drawable.msg_copy);
        copy.setColorFilter(new android.graphics.PorterDuffColorFilter(grayColor, android.graphics.PorterDuff.Mode.SRC_IN));
        copy.setScaleType(ImageView.ScaleType.CENTER);
        copy.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        copy.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(lastAnswer) && AndroidUtilities.addToClipboard(lastAnswer)) {
                BulletinFactory.of(getContainer(), resourcesProvider).createCopyBulletin(getString(R.string.TextCopied)).show();
            }
        });
        status.addView(copy, LayoutHelper.createLinear(36, 36));
        again = text(context, getString(R.string.SG_AiAgain), 13, accent);
        again.setTypeface(AndroidUtilities.bold());
        again.setGravity(Gravity.CENTER_VERTICAL);
        final android.graphics.drawable.Drawable retry = context.getResources().getDrawable(R.drawable.msg_retry).mutate();
        retry.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN));
        retry.setBounds(0, 0, dp(16), dp(16));
        again.setCompoundDrawables(retry, null, null, null);
        again.setCompoundDrawablePadding(dp(4));
        again.setPadding(dp(8), dp(8), dp(4), dp(8));
        again.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 8, 8));
        again.setOnClickListener(v -> run());
        status.addView(again, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 2, 0, -6, 0));
        card.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 4, 0, 0));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        if (forced == TASK_CATCH_UP) {
            // a question about this chat, answered from its messages
            final LinearLayout askRow = new LinearLayout(context);
            askRow.setOrientation(LinearLayout.HORIZONTAL);
            askRow.setGravity(Gravity.CENTER_VERTICAL);
            askRow.setBackground(Theme.createRoundRectDrawable(dp(22), ColorUtils.setAlphaComponent(textColor, 0x0E)));
            askField = new EditTextBoldCursor(context);
            askField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            askField.setTextColor(textColor);
            askField.setHintTextColor(grayColor);
            askField.setHint(getString(R.string.SG_AiAskChatHint));
            askField.setBackground(null);
            askField.setCursorColor(accent);
            askField.setCursorSize(dp(20));
            askField.setSingleLine(true);
            askField.setImeOptions(EditorInfo.IME_ACTION_SEND);
            askField.setPadding(dp(16), dp(10), dp(8), dp(10));
            askField.setOnEditorActionListener((v, actionId, event) -> {
                ask();
                return true;
            });
            askRow.addView(askField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final ImageView send = new ImageView(context);
            send.setImageResource(R.drawable.ic_send);
            send.setScaleType(ImageView.ScaleType.CENTER);
            send.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
            final GradientDrawable sendBg = new GradientDrawable();
            sendBg.setColor(accent());
            sendBg.setShape(GradientDrawable.OVAL);
            send.setBackground(sendBg);
            send.setOnClickListener(v -> ask());
            askRow.addView(send, LayoutHelper.createLinear(36, 36, 0, 0, 6, 0));
            root.addView(askRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 16, 10, 16, 16));
        } else if (forced == TASK_WRITE) {
            replaceButton = new SolegramProfileLinksSheet.ActionButton(context, resourcesProvider);
            replaceButton.setText(getString(R.string.SG_AiReplace), false);
            replaceButton.setOnClickListener(v -> replaceInput());
            replaceButton.setEnabled(false);
            replaceButton.setAlpha(0.5f);
            root.addView(replaceButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 16));
        } else {
            root.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16));
        }

        setCustomView(root);
        solegramReadingGlass();

        // the tasks, and the first one
        if (forced == TASK_CATCH_UP) {
            addChip(context, TASK_CATCH_UP, SolegramAI.unreadCount(chat) >= 3 ? R.string.SG_AiCatchUp : R.string.SG_AiChatGist, -1);
            select(TASK_CATCH_UP, -1);
        } else if (forced == TASK_WRITE) {
            final int[][] modes = {
                {SolegramAI.WRITE_FIX, R.string.SG_AiWriteFix}, {SolegramAI.WRITE_SHORTER, R.string.SG_AiWriteShorter},
                {SolegramAI.WRITE_LONGER, R.string.SG_AiWriteLonger}, {SolegramAI.WRITE_POLITE, R.string.SG_AiWritePolite},
                {SolegramAI.WRITE_FRIENDLY, R.string.SG_AiWriteFriendly}, {SolegramAI.WRITE_FORMAL, R.string.SG_AiWriteFormal},
                {SolegramAI.WRITE_TRANSLATE, R.string.SG_AiWriteTranslate}};
            for (int[] m : modes) {
                addChip(context, TASK_WRITE, m[1], m[0]);
            }
            select(TASK_WRITE, SolegramAI.WRITE_FIX);
        } else {
            final boolean theirs = message != null && !message.isOutOwner();
            if (theirs && SolegramConfig.aiReplies) {
                addChip(context, TASK_REPLIES, R.string.SG_AiReplies, -1);
            }
            if (SolegramConfig.aiExplain) {
                addChip(context, TASK_EXPLAIN, R.string.SG_AiExplain, -1);
            }
            if (message != null && message.messageOwner.message != null && message.messageOwner.message.length() >= 200) {
                addChip(context, TASK_GIST, R.string.SG_AiGist, -1);
            }
            if (chipViews.isEmpty()) {
                addChip(context, TASK_EXPLAIN, R.string.SG_AiExplain, -1);
            }
            select((Integer) chipViews.get(0).getTag(R.id.sg_ai_task), -1);
        }
    }

    private String subtitle(int forced) {
        if (forced == TASK_CATCH_UP) {
            return getString(R.string.SG_AiSubChat);
        }
        if (forced == TASK_WRITE) {
            return getString(R.string.SG_AiSubWrite);
        }
        return getString(R.string.SG_AiSubMessage);
    }

    // region Tasks

    private void addChip(Context context, int task, int title, int mode) {
        final TextView chip = text(context, getString(title), 14, getThemedColor(Theme.key_dialogTextBlack));
        chip.setTypeface(AndroidUtilities.bold());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setTag(R.id.sg_ai_task, task);
        chip.setTag(R.id.sg_ai_mode, mode);
        chip.setOnClickListener(v -> select(task, mode));
        chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        chipViews.add(chip);
    }

    private void select(int task, int mode) {
        this.task = task;
        if (mode >= 0) {
            writeMode = mode;
        }
        final int text = getThemedColor(Theme.key_dialogTextBlack);
        for (TextView chip : chipViews) {
            final boolean on = (Integer) chip.getTag(R.id.sg_ai_task) == task && (task != TASK_WRITE || (Integer) chip.getTag(R.id.sg_ai_mode) == writeMode);
            if (on) {
                // the chosen one in the theme's colour, plain
                chip.setBackground(Theme.createRoundRectDrawable(dp(17), accent()));
                chip.setTextColor(0xFFFFFFFF);
            } else {
                chip.setBackground(Theme.createRoundRectDrawable(dp(17), ColorUtils.setAlphaComponent(text, 0x12)));
                chip.setTextColor(text);
            }
        }
        if (chipViews.size() < 2 && task != TASK_CATCH_UP) {
            ((View) chips.getParent()).setVisibility(View.GONE);
        }
        if (started) {
            run();
        } else {
            setLoading(true);
        }
    }

    @Override
    public void show() {
        super.show();
        if (!started) {
            started = true;
            AndroidUtilities.runOnUIThread(this::run, 220);
        }
    }

    private void ask() {
        if (askField == null) {
            return;
        }
        final String q = askField.getText().toString().trim();
        if (q.isEmpty()) {
            return;
        }
        lastQuestion = q;
        askField.setText("");
        AndroidUtilities.hideKeyboard(askField);
        task = TASK_ASK;
        for (TextView chip : chipViews) {
            chip.setBackground(Theme.createRoundRectDrawable(dp(17), ColorUtils.setAlphaComponent(getThemedColor(Theme.key_dialogTextBlack), 0x12)));
            chip.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        }
        run();
    }

    private void run() {
        final int t = ++token;
        setLoading(true);
        final SolegramAI.Result result = (answer, error, source) -> {
            if (t != token || !isShowing()) {
                return;
            }
            setLoading(false);
            if (answer == null) {
                lastAnswer = null;
                showText(error != null ? error : getString(R.string.SG_AiDeclined), true);
                statusText.setText("");
                return;
            }
            lastAnswer = answer;
            if (task == TASK_REPLIES) {
                showReplies(SolegramAI.parseReplies(answer));
            } else if (task == TASK_ASK && lastQuestion != null) {
                showText(answer, false);
            } else {
                showText(answer, false);
            }
            statusText.setText(SolegramAI.sourceText(source));
            if (replaceButton != null) {
                replaceButton.setEnabled(true);
                replaceButton.setAlpha(1f);
            }
        };
        switch (task) {
            case TASK_REPLIES:
                SolegramAI.replies(chat, message, result);
                break;
            case TASK_EXPLAIN:
                SolegramAI.explain(chat, message, result);
                break;
            case TASK_GIST:
                SolegramAI.summarizeMessage(message, result);
                break;
            case TASK_CATCH_UP:
                SolegramAI.catchUp(chat, result);
                break;
            case TASK_ASK:
                SolegramAI.askAboutChat(chat, lastQuestion, result);
                break;
            case TASK_WRITE:
                SolegramAI.rewrite(ownText, writeMode, writeMode == SolegramAI.WRITE_TRANSLATE ? translateTarget(ownText) : null, result);
                break;
        }
    }

    /** The language to translate one's own text into: the chosen one, or English when the text is in it already. */
    private static String translateTarget(String text) {
        final String to = org.telegram.ui.Components.TranslateAlert2.getToLanguage();
        int cyr = 0, letters = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
                    cyr++;
                }
            }
        }
        final boolean cyrillicText = letters > 0 && cyr * 2 > letters;
        final boolean cyrillicTarget = "ru".equals(to) || "uk".equals(to) || "be".equals(to) || "bg".equals(to) || "sr".equals(to) || "kk".equals(to);
        if (cyrillicText == cyrillicTarget) {
            return cyrillicText ? "en" : (cyrillicTarget ? to : "en".equals(to) ? LocaleController.getInstance().getCurrentLocaleInfo().pluralLangCode : to);
        }
        return to;
    }

    private void setLoading(boolean loading) {
        shimmer.setVisibility(loading ? View.VISIBLE : View.GONE);
        resultText.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        repliesBox.setVisibility(View.GONE);
        again.setEnabled(!loading);
        again.setAlpha(loading ? 0.4f : 1f);
        copy.setAlpha(loading ? 0.4f : 1f);
        if (loading) {
            resultText.setText("");
            statusText.setText(getString(R.string.SG_AiThinking));
            if (replaceButton != null) {
                replaceButton.setEnabled(false);
                replaceButton.setAlpha(0.5f);
            }
        }
    }

    private void showText(String text, boolean error) {
        repliesBox.setVisibility(View.GONE);
        resultText.setVisibility(View.VISIBLE);
        resultText.setText(text);
        resultText.setTextColor(getThemedColor(error ? Theme.key_dialogTextGray3 : Theme.key_dialogTextBlack));
        resultText.setAlpha(0f);
        resultText.animate().alpha(1f).setDuration(220).start();
    }

    private void showReplies(List<String> replies) {
        if (replies.isEmpty()) {
            showText(getString(R.string.SG_AiFailed), true);
            return;
        }
        resultText.setVisibility(View.GONE);
        repliesBox.removeAllViews();
        repliesBox.setVisibility(View.VISIBLE);
        final int textColor = getThemedColor(Theme.key_dialogTextBlack);
        for (int i = 0; i < replies.size(); i++) {
            final String reply = replies.get(i);
            final TextView v = text(getContext(), reply, 16, textColor);
            v.setPadding(dp(14), dp(11), dp(14), dp(11));
            v.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), Theme.getColor(Theme.key_dialogBackground, resourcesProvider),
                ColorUtils.setAlphaComponent(textColor, 0x18)));
            v.setOnClickListener(view -> insertReply(reply));
            v.setAlpha(0f);
            v.setTranslationY(dp(8));
            v.animate().alpha(1f).translationY(0).setStartDelay(60L * i).setDuration(240).start();
            repliesBox.addView(v, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 8, 0, 0));
        }
        final TextView hint = text(getContext(), getString(R.string.SG_AiRepliesHint), 13, getThemedColor(Theme.key_dialogTextGray3));
        repliesBox.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 10, 0, 0));
    }

    private void insertReply(String reply) {
        final ChatActivityEnterView enter = chat.getChatActivityEnterView();
        if (enter == null) {
            return;
        }
        enter.setFieldText(reply);
        if (enter.getEditField() != null) {
            enter.getEditField().setSelection(enter.getEditField().length());
        }
        dismiss();
        AndroidUtilities.runOnUIThread(enter::openKeyboard, 200);
    }

    private void replaceInput() {
        if (TextUtils.isEmpty(lastAnswer)) {
            return;
        }
        final ChatActivityEnterView enter = chat.getChatActivityEnterView();
        if (enter != null) {
            enter.setFieldText(lastAnswer);
            if (enter.getEditField() != null) {
                enter.getEditField().setSelection(enter.getEditField().length());
            }
        }
        dismiss();
    }

    // endregion

    // region Views

    private static TextView text(Context context, CharSequence s, int sizeDp, int color) {
        final TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        return t;
    }

    /** Text in the Solegram AI colours. */
    public static class GradientText extends TextView {
        public GradientText(Context context) {
            super(context);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            final float width = Math.max(1, getPaint().measureText(getText().toString()));
            getPaint().setShader(new LinearGradient(0, 0, width, 0, brand(), null, Shader.TileMode.CLAMP));
        }
    }

    /**
     * The Solegram AI mark: the Solegram mark (a half sun rising over two message lines) with a spark beside the sun,
     * in the theme's Solegram AI colours, on a soft tile of them (or plain).
     */
    public static class Spark extends View {
        private static final String SUN = "M35.4,55.395 A18.6,18.6 0 0 1 72.6,55.395 A1.24,1.24 0 0 1 71.36,56.635 L36.64,56.635 A1.24,1.24 0 0 1 35.4,55.395 Z";
        private static final String LINES = "M38.345,59.115 L69.655,59.115 A2.325,2.325 0 0 1 69.655,63.765 L38.345,63.765 A2.325,2.325 0 0 1 38.345,59.115 Z M45.165,66.555 L62.835,66.555 A2.325,2.325 0 0 1 62.835,71.205 L45.165,71.205 A2.325,2.325 0 0 1 45.165,66.555 Z";
        // the mark with its spark spans x 35.4..83, y 25.5..71.2 of the icon's 108 units
        private static final float BOX_L = 35.4f, BOX_T = 25.5f, BOX_W = 47.6f, BOX_H = 45.7f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path sun = androidx.core.graphics.PathParser.createPathFromPathData(SUN);
        private final android.graphics.Path lines = androidx.core.graphics.PathParser.createPathFromPathData(LINES);
        private final android.graphics.Path spark = new android.graphics.Path();
        private final RectF rect = new RectF();
        private boolean plain;
        private float breathe = 1f;

        public Spark(Context context) {
            super(context);
            star(spark, 75.5f, 33f, 7.5f);
            star(spark, 81.5f, 44.5f, 2.6f);
        }

        /** Only the mark, no tile (in rows and cards). */
        public Spark setPlain(boolean plain) {
            this.plain = plain;
            invalidate();
            return this;
        }

        /** The spark's size, for a gentle pulse. */
        public void setBreathe(float breathe) {
            this.breathe = breathe;
            invalidate();
        }

        private static void star(android.graphics.Path path, float cx, float cy, float r) {
            path.moveTo(cx, cy - r);
            path.quadTo(cx + r * 0.16f, cy - r * 0.16f, cx + r, cy);
            path.quadTo(cx + r * 0.16f, cy + r * 0.16f, cx, cy + r);
            path.quadTo(cx - r * 0.16f, cy + r * 0.16f, cx - r, cy);
            path.quadTo(cx - r * 0.16f, cy - r * 0.16f, cx, cy - r);
            path.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            final int[] c = brand();
            if (!plain) {
                tile.setShader(new LinearGradient(0, 0, w, h, new int[]{ColorUtils.setAlphaComponent(c[0], 0x38), ColorUtils.setAlphaComponent(c[2], 0x30)}, null, Shader.TileMode.CLAMP));
                rect.set(0, 0, w, h);
                canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, tile);
            }
            final float inner = s * (plain ? 0.96f : 0.6f);
            final float k = inner / Math.max(BOX_W, BOX_H);
            canvas.save();
            canvas.translate((w - BOX_W * k) / 2f, (h - BOX_H * k) / 2f);
            canvas.scale(k, k);
            canvas.translate(-BOX_L, -BOX_T);
            paint.setShader(new LinearGradient(BOX_L, BOX_T + BOX_H, BOX_L + BOX_W, BOX_T, c, null, Shader.TileMode.CLAMP));
            canvas.drawPath(sun, paint);
            canvas.drawPath(lines, paint);
            canvas.save();
            canvas.scale(breathe, breathe, 75.5f, 33f);
            canvas.drawPath(spark, paint);
            canvas.restore();
            canvas.restore();
        }
    }

    /** Lines that shimmer while the answer is on its way. */
    private static class Shimmer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int color;
        private final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        private float phase;

        Shimmer(Context context, int textColor) {
            super(context);
            color = textColor;
            animator.setDuration(1300);
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
            final float w = getWidth();
            final float x = -w + phase * w * 3;
            paint.setShader(new LinearGradient(x, 0, x + w, 0,
                new int[]{ColorUtils.setAlphaComponent(color, 0x14), ColorUtils.setAlphaComponent(brand()[1], 0x40), ColorUtils.setAlphaComponent(color, 0x14)},
                null, Shader.TileMode.CLAMP));
            final float[] widths = {1f, 0.86f, 0.93f, 0.55f};
            for (int i = 0; i < widths.length; i++) {
                final float top = dp(6) + i * dp(22);
                rect.set(0, top, w * widths[i], top + dp(12));
                canvas.drawRoundRect(rect, dp(6), dp(6), paint);
            }
        }
    }

    // endregion
}
