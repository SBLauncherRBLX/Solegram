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
        head.addView(new Spark(context).setPlain(true), LayoutHelper.createLinear(36, 36)); // the mark alone, no tile behind it
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
     * The Solegram AI mark: a tall spark with a tilted ring round it - in front of it below, behind it above, with a
     * gap where they cross - and a small spark beside it; in the theme's Solegram AI colours, on a soft tile of them
     * (or plain). The same shape as the sg_ai_spark icon.
     */
    public static class Spark extends View {
        /** The mark in a 24 x 24 box (generated: the spark, the ring's two halves and the small spark as one outline). */
        private static final String MARK = "M20.40,1.60 L20.22,2.17 L20.06,2.46 L19.72,2.87 L19.46,3.06 L19.17,3.22 L18.60,3.40 L18.95,3.50 L19.27,3.63 L19.55,3.80 L19.80,4.00 L20.00,4.25 L20.17,4.53 L20.30,4.85 L20.40,5.20 L20.50,4.85 L20.63,4.53 L20.80,4.25 L21.01,4.00 L21.25,3.80 L21.53,3.63 L21.85,3.50 L22.20,3.40 L21.63,3.22 L21.16,2.94 L20.86,2.64 L20.68,2.37 L20.54,2.06 Z M14.93,7.17 L16.37,7.08 L17.49,7.10 L18.51,7.21 L19.59,7.44 L20.06,7.60 L20.49,7.79 L21.07,8.16 L21.35,8.41 L21.57,8.69 L21.68,8.88 L21.80,9.19 L23.34,8.75 L23.11,8.18 L22.76,7.66 L22.42,7.30 L21.87,6.86 L21.21,6.48 L20.64,6.24 L19.81,5.96 L18.89,5.76 L17.64,5.60 L16.29,5.55 L14.86,5.60 L13.63,5.74 L13.79,6.33 L14.12,7.26 Z M14.86,13.62 L15.41,13.04 L16.01,12.55 L16.33,12.34 L17.00,12.00 L16.66,11.84 L16.01,11.45 L15.71,11.22 L15.13,10.69 L14.60,10.06 L14.35,9.71 L13.89,8.94 L13.68,8.52 L13.10,7.11 L12.63,5.49 L12.26,3.65 L12.00,1.60 L11.74,3.65 L11.37,5.49 L10.90,7.11 L10.32,8.52 L10.11,8.94 L9.65,9.71 L9.40,10.06 L8.87,10.69 L8.29,11.22 L7.99,11.45 L7.34,11.84 L7.00,12.00 L7.67,12.34 L7.99,12.55 L8.59,13.04 L8.87,13.31 L9.14,13.62 L9.65,14.29 L10.11,15.06 L10.44,15.74 L12.16,15.37 L14.03,14.82 L14.35,14.29 Z M9.52,6.70 L8.08,7.22 L6.97,7.70 L5.93,8.23 L4.71,8.95 L3.62,9.71 L2.85,10.36 L2.18,11.02 L1.49,11.86 L1.07,12.54 L0.91,12.87 L0.72,13.37 L0.60,13.86 L0.56,14.18 L0.57,14.81 L0.70,15.40 L0.82,15.68 L1.05,16.08 L1.46,16.58 L1.98,17.03 L2.45,17.34 L2.98,17.60 L3.56,17.84 L4.19,18.04 L4.87,18.20 L5.60,18.32 L6.89,18.44 L8.56,18.43 L10.31,18.27 L12.10,17.95 L13.90,17.49 L15.64,16.89 L17.29,16.17 L18.82,15.35 L19.52,14.90 L20.17,14.44 L20.78,13.97 L21.33,13.48 L21.82,12.98 L22.25,12.48 L22.62,11.97 L22.93,11.46 L23.16,10.96 L23.33,10.46 L23.43,9.98 L23.45,9.66 L23.43,9.19 L23.34,8.75 L21.80,9.19 L21.87,9.63 L21.83,10.09 L21.68,10.57 L21.43,11.07 L21.07,11.58 L20.61,12.10 L20.21,12.48 L19.59,12.99 L18.89,13.49 L18.11,13.97 L17.27,14.43 L16.36,14.87 L14.68,15.55 L13.67,15.88 L12.64,16.18 L11.09,16.53 L9.82,16.74 L8.34,16.89 L6.95,16.91 L5.89,16.84 L4.75,16.65 L4.24,16.51 L3.79,16.34 L3.38,16.14 L3.03,15.92 L2.74,15.68 L2.43,15.31 L2.23,14.92 L2.13,14.49 L2.15,14.03 L2.27,13.55 L2.43,13.18 L2.74,12.67 L3.26,12.03 L3.79,11.52 L4.58,10.88 L5.30,10.39 L6.09,9.91 L7.18,9.35 L8.34,8.83 L9.35,8.44 L9.70,7.71 L10.15,6.50 Z M11.84,21.01 L12.00,22.40 L12.26,20.35 L12.57,18.77 L11.48,18.99 Z";

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path mark = androidx.core.graphics.PathParser.createPathFromPathData(MARK);
        private final RectF rect = new RectF();
        private boolean plain;
        private float breathe = 1f;

        public Spark(Context context) {
            super(context);
        }

        /** Only the mark, no tile (in rows and cards). */
        public Spark setPlain(boolean plain) {
            this.plain = plain;
            invalidate();
            return this;
        }

        /** The mark's size, for a gentle pulse. */
        public void setBreathe(float breathe) {
            this.breathe = breathe;
            invalidate();
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
            final float k = s * (plain ? 1f : 0.64f) / 24f * breathe;
            canvas.save();
            canvas.translate((w - 24 * k) / 2f, (h - 24 * k) / 2f);
            canvas.scale(k, k);
            paint.setShader(new LinearGradient(0, 24, 24, 0, c, null, Shader.TileMode.CLAMP));
            canvas.drawPath(mark, paint);
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
